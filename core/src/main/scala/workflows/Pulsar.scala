//: ----------------------------------------------------------------------------
//: Copyright (C) 2018 Verizon.  All Rights Reserved.
//:
//:   Licensed under the Apache License, Version 2.0 (the "License");
//:   you may not use this file except in compliance with the License.
//:   You may obtain a copy of the License at
//:
//:       http://www.apache.org/licenses/LICENSE-2.0
//:
//:   Unless required by applicable law or agreed to in writing, software
//:   distributed under the License is distributed on an "AS IS" BASIS,
//:   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//:   See the License for the specific language governing permissions and
//:   limitations under the License.
//:
//: ----------------------------------------------------------------------------
package nelson

import cats.syntax.apply._

import nelson.Datacenter.{Deployment, Namespace => DCNamespace}
import nelson.DeploymentStatus.{Pending, Ready, Terminated, Warming}
import nelson.Manifest.{Namespace => ManifestNamespace, Plan, UnitDef, Versioned}
import nelson.Workflow.{WorkflowF, WorkflowOp}
import nelson.Workflow.syntax._
import nelson.docker.DockerOp

/**
 * Kubernetes deployment workflow that deploys and deletes units, whilst provisioning
 * authentication roles in Vault so that Kubernetes pods can talk to Vault at runtime.
 */
object Pulsar extends Workflow[Unit] {
  val name: WorkflowRef = "pulsar"

  def deploy(id: ID, hash: String, vunit: UnitDef @@ Versioned, p: Plan, dc: Datacenter, ns: ManifestNamespace): WorkflowF[Unit] = {
    val unit = Manifest.Versioned.unwrap(vunit)
    val sn = Datacenter.StackName(unit.name, vunit.version, hash)
    val rs = unit.dependencies.keys.toSet ++ unit.resources.map(_.name)

    // When the workflow is completed, we typically want to set the deployment to "Warming", so that once
    // consul indicates the deployment to be passing the health check, we can promote to "Ready" (via the
    // DeploymentMonitor background process).  However, units without ports are not registered in service
    // discovery, and thus we should immediately advance mark the deployment as "Ready".
    def getStatus(unit: UnitDef, plan: Plan):  DeploymentStatus =
      if (Manifest.isPeriodic(unit,plan)) Ready
      else unit.ports.fold[DeploymentStatus](Ready)(_ => Warming)

    for {
      i <- DockerOp.extract(Versioned.unwrap(vunit)).inject[WorkflowOp]
      _ <- status(id, Pending, "Pulsar workflow about to start")
      //// write the policies to Vault
      _  <- logToFile(id, s"Writing policy to vault: ${vaultLoggingFields(sn, ns = ns.name, dcName = dc.name)}")
      _  <- writePolicyToVault(cfg = dc.policy, sn = sn, ns = ns.name, rs = rs)
      //// create a Vault kubernetes auth role
      _  <- logToFile(id, s"Writing Kubernetes auth role '${sn.toString}' to Vault...")
      _  <- writeKubernetesRoleToVault(dc = dc, sn = sn, ns = ns.name)
      //// write the needful to consul
      _  <- logToFile(id, s"writing discovery tables to ${routing.Discovery.consulDiscoveryKey(sn)}")
      _  <- writeDiscoveryToConsul(id, sn, ns.name, dc)
      _  <- getTrafficShift(unit, p, dc).fold(pure(()))(ts => createTrafficShift(id, ns.name, dc, ts.policy, ts.duration) *> logToFile(id, s"Creating traffic shift: ${ts.policy.ref}"))
      //// show kubernetes some love
      _ <- logToFile(id, s"Instructing ${dc.name}'s scheduler to handle service container")
      l <- launch(i, dc, ns.name, vunit, p, hash)
      _ <- debug(s"Scheduler responded with: ${l}")
      _ <- status(id, getStatus(Manifest.Versioned.unwrap(vunit), p), "=====> Pulsar workflow completed <=====")
    } yield ()
  }

  def destroy(d: Deployment, dc: Datacenter, ns: DCNamespace): WorkflowF[Unit] = {
    val stackName = d.stackName
    logToFile(d.id, s"removing policy from vault: ${vaultLoggingFields(stackName, ns = ns.name, dcName = dc.name)}") *>
    deletePolicyFromVault(d.stackName, ns.name) *>
    logToFile(d.id, s"removing kubernetes role from vault: ${vaultLoggingFields(stackName, ns = ns.name, dcName = dc.name)}") *>
    deleteKubernetesRoleFromVault(dc, d.stackName) *>
    logToFile(d.id, s"instructing ${dc.name}'s scheduler to decomission ${stackName}") *>
    delete(dc, d) *>
    status(d.id, Terminated, s"Decomissioning deployment ${stackName} in ${dc.name}")
  }
}

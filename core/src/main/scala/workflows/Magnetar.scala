//: ----------------------------------------------------------------------------
//: Copyright (C) 2017 Verizon.  All Rights Reserved.
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

import Manifest.{UnitDef,Versioned,Plan,TrafficShift}
import Datacenter.{Namespace,Deployment}
import Workflow.WorkflowF
import cats.implicits._

object Magnetar extends Workflow[Unit] {
  import Workflow.syntax._
  import DeploymentStatus._

  val name: WorkflowRef = "magnetar"

  def deploy(id: ID, hash: String, vunit: UnitDef @@ Versioned, p: Plan, dc: Datacenter, ns: Manifest.Namespace): WorkflowF[Unit] = {
    val unit = Manifest.Versioned.unwrap(vunit)
    val sn = Datacenter.StackName(unit.name, vunit.version, hash)
    val rs = unit.dependencies.keys.toSet ++ unit.resources.map(_.name)

    // When the workflow is completed, we typically want to set the deployment to "Warming", so that once
    // consul indicates the deployment to be passing the health check, we can promote to "Ready" (via the
    // DeploymentMonitor background process).  However, units without ports are not registered in consul, and
    // thus we should immediately advance mark the deployment as "Ready".  Once Reconciliation is also used as
    // a gating factor for promoting deployments to "Ready", we can potentially set all units to "Warming" here.
    def getStatus(unit: UnitDef, plan: Plan):  DeploymentStatus =
      if (Manifest.isPeriodic(unit,plan)) Ready
      else unit.ports.fold[DeploymentStatus](Ready)(_ => Warming)

    def getTrafficShift: Option[TrafficShift] =
      if (!Manifest.isPeriodic(unit, p))
        Option(p.environment.trafficShift
                .getOrElse(TrafficShift(dc.defaultTrafficShift.policy, dc.defaultTrafficShift.duration)))
      else None

    for {
      _  <- status(id, Pending, "workflow about to start")
      i  <- dockerOps(id, unit, dc.docker.registry)
      _  <- status(id, Deploying, s"writing alert definitions to ${dc.name}'s consul")
      _  <- writeAlertsToConsul(sn, ns.name, p.name, unit, p.environment.alertOptOuts)
      _  <- logToFile(id, s"writing policy to vault: ${vaultLoggingFields(sn, ns = ns.name, dcName = dc.name)}")
      _  <- writePolicyToVault(cfg = dc.policy, sn = sn, ns = ns.name, rs = rs)
      _  <- logToFile(id, s"writing discovery tables to ${routing.Discovery.consulDiscoveryKey(sn)}")
      _  <- writeDiscoveryToConsul(id, sn, ns.name, dc)
      _  <- getTrafficShift.fold(pure(()))(ts => createTrafficShift(id, ns.name, dc, ts.policy, ts.duration) *> logToFile(id, s"Creating traffic shift: ${ts.policy.ref}"))
      _  <- logToFile(id, s"instructing ${dc.name}'s scheduler to handle service container")
      l  <- launch(i, dc, ns.name, vunit, p, hash)
      _  <- debug(s"response from scheduler $l")

      _  <- status(id, getStatus(unit, p), "======> workflow completed <======")
    } yield ()
  }

  def destroy(d: Deployment, dc: Datacenter, ns: Namespace): WorkflowF[Unit] = {
    val sn = d.stackName
    logToFile(d.id, s"removing policy from vault: ${vaultLoggingFields(sn, ns = ns.name, dcName = dc.name)}") *>
    deletePolicyFromVault(d.stackName, ns.name) *>
    logToFile(d.id, s"removing alerts from consul ${alerts.alertingKey(sn)}") *>
    deleteAlertsFromConsul(d.stackName) *>
    logToFile(d.id, s"removing discovery tables from consul ${routing.Discovery.consulDiscoveryKey(sn)}") *>
    deleteDiscoveryInfoFromConsul(sn) *>
    logToFile(d.id, s"instructing ${dc.name}'s scheduler to decommission ${sn}") *>
    delete(dc,d) *>
    status(d.id, Terminated, s"Decommissioning deployment ${sn} in ${dc.name}")
  }

  private def vaultLoggingFields(sn: Datacenter.StackName, ns: NamespaceName, dcName: String): String =
    s"namespace=${ns} unit=${sn.serviceType} policy=${policies.policyName(sn, ns)} datacenter=${dcName}"
}

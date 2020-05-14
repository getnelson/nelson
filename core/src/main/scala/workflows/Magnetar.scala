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

import Manifest.{UnitDef,Versioned,Plan}
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

    for {
      _  <- status(id, Pending, "workflow about to start")
      //// fetch and replicate the images to the remote datacenters
      i  <- dockerOps(id, unit, dc.docker.registry)
      //// handle the blueprint rendering, save the bp to the db
      _  <- logToFile(id, s"Rendering blueprint and saving to the database...")
      bp <- handleBlueprint(id, i, dc, ns.name, unit, vunit.version, p, hash)
      _  <- status(id, Deploying, s"writing alert definitions to ${dc.name}'s consul")
      _  <- writeAlertsToConsul(sn, ns.name, p.name, unit, p.environment.alertOptOuts)
      _  <- logToFile(id, s"writing policy to vault: ${vaultLoggingFields(sn, ns = ns.name, dcName = dc.name)}")
      _  <- writePolicyToVault(cfg = dc.policy, sn = sn, ns = ns.name, rs = rs)
      _  <- logToFile(id, s"writing discovery tables to ${routing.Discovery.consulDiscoveryKey(sn)}")
      _  <- writeDiscoveryToConsul(id, sn, ns.name, dc)
      _  <- getTrafficShift(p, dc).fold(pure(()))(ts => createTrafficShift(id, ns.name, dc, ts.policy, ts.duration) *> logToFile(id, s"Creating traffic shift: ${ts.policy.ref}"))
      _  <- logToFile(id, s"instructing ${dc.name}'s scheduler to handle service container")
      l  <- launch(i, dc, ns.name, vunit, p, hash, bp)
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
}

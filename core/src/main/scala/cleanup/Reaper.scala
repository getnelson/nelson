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
package cleanup

import nelson.Datacenter.{Deployment, Namespace}
import nelson.Metrics.default.{destroyFailureCounter,destroySuccessCounter}
import nelson.Workflow.WorkflowOp
import nelson.notifications.Notify

import cats.~>
import cats.effect.IO
import cats.syntax.applicativeError._
import cats.syntax.apply._

import fs2.Sink

import scala.util.control.NonFatal

import journal.Logger

object Reaper {

  private val log = Logger[Reaper.type]

  /*
   * Runs destroy workflow to decommission a deployment.
   * This typically invovles making a call to whatever
   * scheduler was used to initially place the deployment
   * to delete the running job.
   */
  def reap(cfg: NelsonConfig): Sink[IO, CleanupRow] =
    Sink { case (dc, ns, d, _) =>
      destroy(dc,ns,d.deployment)(dc.workflow)(cfg)
        .map { _ => destroySuccessCounter.labels(ns.name.asString).inc() }
        .recoverWith {
          // this is a Sink and the end of the world, so we need to handle NonFatal to keep Processes running
          case NonFatal(e) =>
            destroyFailureCounter.labels(ns.name.asString).inc()
            IO(log.warn(s"error occured during destroy phase $e"))
        }
    }

  private def destroy(dc: Datacenter, ns: Namespace, d: Datacenter.Deployment)(t: WorkflowOp ~> IO)(cfg: NelsonConfig): IO[Unit] = {
    import Json._
    import audit.AuditableInstances._
    resolve(d).destroy(d,dc,ns).foldMap(t) <*
      cfg.auditor.write(d, audit.DeleteAction) <*
      IO(log.debug((s"finished cleaning up $d in datacenter $dc"))) <*
      Notify.sendDecommissionedNotifications(dc,ns,d)(cfg)
  }

  private def resolve(d: Deployment): Workflow[Unit] =
    Workflow.fromString(d.workflow).getOrElse(Magnetar)
}

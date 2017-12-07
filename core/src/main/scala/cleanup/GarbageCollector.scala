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

import scalaz.concurrent.Task
import scalaz._, Scalaz._
import scalaz.stream.{Channel,channel}
import java.time.Instant
import journal.Logger


/**
 * The GarbageCollector is a process that periodically traverses the
 * CleanupGraph and marks deployments as `garbage`. Any deployment that
 * is marked as `garbage` becomes a candidate for cleanup.
 */
object GarbageCollector {
  import nelson.Domain.Deployment
  import nelson.storage.{run => runs, StoreOp,StoreOpF}

  private val log = Logger[GarbageCollector.type]

  /* Regardless of UnitType, all deployments are considered garabage if they are expired */
  def expired(d: DeploymentCtx): Boolean =
    d.exp.exists(_.isBefore(Instant.now()))

  /* Marks deployable as Garbage. A separate process will handle the actual cleanup */
  def markAsGarbage(d: Deployment): StoreOpF[Deployment] =
    StoreOp.createDeploymentStatus(d.id, DeploymentStatus.Garbage, None).map(_ => d) <*
      (log.debug(s"marking deployment ${d.stackName} as garbage").point[StoreOpF])

  /**
   * A Channel that captures the output of the ExpirationPolicy process.
   * This should be run after the ExpirationPolicy process to guard
   * against the case where the ExpirationPolicy process fails
   * and GC eagerly marks deployments that it shouldn't.
   */
  def mark(cfg: NelsonConfig): Channel[Task, CleanupRow, CleanupRow] = {
    import Json._
    import audit.AuditableInstances._
    channel.lift { case (dc, ns, d, gr) =>
      runs(cfg.storage, markAsGarbage(d.deployment).map(_ => (dc, ns, d, gr))) <*
        cfg.auditor.write(d.deployment, audit.GarbageAction)
    }
  }
}

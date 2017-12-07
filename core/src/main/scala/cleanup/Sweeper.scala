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

import helm.ConsulOp
import journal.Logger
import nelson.routing.Discovery

import scala.util.control.NonFatal
import scalaz.{-\/, Kleisli, \/, \/-, ~>}
import scalaz.concurrent.Task
import scalaz.stream._
import scalaz.std.list._
import scalaz.std.option.optionSyntax._
import scalaz.syntax.traverse._

/**
  * Infrequently running cleanup of "leaked" data or data which is otherwise unaccounted for.  Unlike the cleanup
  * process (which is optimized for precision and efficiency), the Sweeper is optimized for comprehensiveness over time
  * via repeated execution (even if we miss some "garbage" on execution i, we will eventually clean it up on excution i + N).
  * We only need to ensure that we 1.) cleanup data that was missed during the Cleanup operation
  * and 2.) ensure we aren't progressively leaking resources over the long-term.
  *
  * Items that are identified as an "UnclaimedResource" (items that have no obvious owner -- which may or may not be garbage) are NOT deleted
  * but will be tracked and counted by the end of the task.  The number of Unclaimed Resources will be recorded as a histogram, to allow
  * us to have visibility of accumulation on such items.  In the event that we discover that the amount of Unclaimed Resources is increasing over time
  * this is a possible indication that we have a major leakage signaling the need to modify the sweeper to be more aggressive.
  */
object Sweeper {

  val log = Logger[this.type]

  final case class UnclaimedResources(n: Int)
  final case object SingleUnclaimedResource

  type SweeperHelmOp = (Domain, UnclaimedResources \/ ConsulOp.ConsulOpF[Unit])
  type SweeperHelmOps = List[SweeperHelmOp]

  def cleanupLeakedConsulDiscoveryKeys(cfg: NelsonConfig): Task[SweeperHelmOps] =
    cfg.domains.traverseM[Task, SweeperHelmOp] { dc =>
      for {
        keys <- helm.run(dc.interpreters.consul, Discovery.listDiscoveryKeys).map(_.toList)

        stackNames <- storage.run(cfg.storage, storage.StoreOp.getRoutableDeploymentsByDomain(dc))
          .map(_.map(_.stackName.toString)).map(_.toList)

        items = for {
          (key, sno) <- keys.map(k => k -> Discovery.stackNameFrom(k))
          deleteKey <- sno.cata(sn =>
            Some(sn).filterNot(stackNames.contains).map(_ => \/-(key)).toList, List(-\/(SingleUnclaimedResource))
          )
        } yield deleteKey

        deleteOps = items.flatMap(_.toOption.toSet).map(ConsulOp.delete).map(op => dc -> \/-(op))
        unclaimedResource = dc -> -\/(UnclaimedResources(items.count(_.isLeft)))

      } yield deleteOps :+ unclaimedResource
    }

  def process(cfg: NelsonConfig)(implicit unclaimedResourceTracker: Kleisli[Task, (Domain, Int), Unit]): Process[Task, Unit] =
    Process.repeatEval(Task.delay(cfg.cleanup.sweeperDelay)).flatMap(d =>
      time.awakeEvery(d)(cfg.pools.schedulingExecutor, cfg.pools.schedulingPool).once)
      .flatMap(_ =>
        Process.eval(timer(cleanupLeakedConsulDiscoveryKeys(cfg)))
          .attempt().observeW(cfg.auditor.errorSink)
          .stripW
      )
      .flatMap(Process.emitAll) to sweeperSink

  def sweeperSink(implicit unclaimedResourceTracker: Kleisli[Task, (Domain, Int), Unit]): Sink[Task, SweeperHelmOp] =
    Process.constant {
      case (dc, -\/(UnclaimedResources(n))) => unclaimedResourceTracker.run(dc -> n) handle {
        case NonFatal(e) => log.error(s"error while attempting to track unclaimed resources", e)
      }
      case (dc, \/-(op)) => helm.run(dc.consul, op) handle {
        case NonFatal(e) => log.error(s"error while attempting to perform consul operation", e)
      }
    }

  val timer : Task ~> Task = Metrics.timer(
    before = Task.now(()),
    onComplete = Kleisli[Task, Double, Unit] { elapsed =>
      Task.delay(Metrics.default.sweeperLatencySeconds.observe(elapsed))
    },
    onFail = Kleisli[Task, Throwable, Unit] { _ =>
      Task.delay(Metrics.default.sweeperFailures.inc())
    },
    onSuccess = Task.now(())
  )

}

object SweeperDefaults {
  type NumberWithinDC = (Domain, Int)

  implicit val unclaimedResourceTracker : Kleisli[Task, NumberWithinDC, Unit] = Kleisli { case (dc, n) =>
    Task.delay(Metrics.default.sweeperUnclaimedResourcesDetected.labels(dc.toString).observe(n.toDouble))
  }
}

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

import cats.~>
import cats.data.Kleisli
import cats.effect.{Effect, IO}
import cats.implicits._
import nelson.CatsHelpers._

import fs2.{Scheduler, Sink, Stream}

import scala.util.control.NonFatal

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

  type SweeperHelmOp = (Datacenter, Either[UnclaimedResources,ConsulOp.ConsulOpF[Unit]])
  type SweeperHelmOps = List[SweeperHelmOp]

  def cleanupLeakedConsulDiscoveryKeys(cfg: NelsonConfig): IO[SweeperHelmOps] =
    cfg.datacenters.flatTraverse[IO, SweeperHelmOp] { dc =>
      for {
        keys <- helm.run(dc.interpreters.consul, Discovery.listDiscoveryKeys).map(_.toList)

        stackNames <- storage.StoreOp.getRoutableDeploymentsByDatacenter(dc).foldMap(cfg.storage)
          .map(_.map(_.stackName.toString)).map(_.toList)

        items = for {
          (key, sno) <- keys.map(k => k -> Discovery.stackNameFrom(k))
          deleteKey <- sno.fold[List[Either[SingleUnclaimedResource.type, String]]](List(Left(SingleUnclaimedResource))) { sn =>
            Some(sn).filterNot(stackNames.contains).map(_ => Right(key)).toList
          }
        } yield deleteKey

        deleteOps = items.flatMap(_.toOption.toSet).map(ConsulOp.kvDelete).map(op => dc -> Right(op))
        unclaimedResource = dc -> Left(UnclaimedResources(items.count(_.isLeft)))

      } yield deleteOps :+ unclaimedResource
    }

  def process(cfg: NelsonConfig)(implicit unclaimedResourceTracker: Kleisli[IO, (Datacenter, Int), Unit]): Stream[IO, Unit] =
    Stream.repeatEval(IO(cfg.cleanup.sweeperDelay)).flatMap { d =>
      Scheduler.fromScheduledExecutorService(cfg.pools.schedulingPool).awakeEvery(d)(Effect[IO], cfg.pools.schedulingExecutor).head
    }.flatMap(_ =>
      Stream.eval(timer(cleanupLeakedConsulDiscoveryKeys(cfg)))
        .attempt.observeW(cfg.auditor.errorSink)(Effect[IO], cfg.pools.defaultExecutor)
        .stripW
    )
    .flatMap(os => Stream.emits(os).covary[IO]) to sweeperSink

  def sweeperSink(implicit unclaimedResourceTracker: Kleisli[IO, (Datacenter, Int), Unit]): Sink[IO, SweeperHelmOp] =
    Sink {
      case (dc, Left(UnclaimedResources(n))) => unclaimedResourceTracker.run(dc -> n) recoverWith {
        case NonFatal(e) => IO(log.error(s"error while attempting to track unclaimed resources", e))
      }
      case (dc, Right(op)) => helm.run(dc.consul, op) recoverWith {
        case NonFatal(e) => IO(log.error(s"error while attempting to perform consul operation", e))
      }
    }

  val timer : IO ~> IO = Metrics.timer(
    before = IO.pure(()),
    onComplete = Kleisli[IO, Double, Unit] { elapsed =>
      IO(Metrics.default.sweeperLatencySeconds.observe(elapsed))
    },
    onFail = Kleisli[IO, Throwable, Unit] { _ =>
      IO(Metrics.default.sweeperFailures.inc())
    },
    onSuccess = IO.unit
  )

}

object SweeperDefaults {
  type NumberWithinDC = (Datacenter, Int)

  implicit val unclaimedResourceTracker : Kleisli[IO, NumberWithinDC, Unit] = Kleisli { case (dc, n) =>
    IO(Metrics.default.sweeperUnclaimedResourcesDetected.labels(dc.toString).observe(n.toDouble))
  }
}

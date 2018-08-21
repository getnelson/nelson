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

import cats.~>
import cats.effect.IO

import nelson.scheduler.SchedulerOp
import nelson.scheduler.SchedulerOp._

class InstrumentedNomadClient private (instance: String, interp: SchedulerOp ~> IO, metrics: Metrics)
    extends (SchedulerOp ~> IO) {

  def apply[A](op: SchedulerOp[A]): IO[A] =
    IO(System.nanoTime).flatMap { startNanos =>
      interp(op).attempt.flatMap { att =>
        val elapsed = System.nanoTime - startNanos
        val label = toLabel(op)
        metrics.nomadRequestsLatencySeconds.labels(label, instance).observe(elapsed / 1.0e9)
        att match {
          case Right(a) =>
            IO.pure(a)
          case Left(e) =>
            metrics.nomadRequestsFailuresTotal.labels(label, instance).inc()
            IO.raiseError(e)
        }
      }
    }

  private def toLabel(op: SchedulerOp[_]) = op match {
    case _: Launch => "launch"
    case _: Delete => "delete"
    case _: Summary => "summary"
  }
}

object InstrumentedNomadClient {

  def apply(dockerInstance: String, interp: SchedulerOp ~> IO, metrics: Metrics = Metrics.default): SchedulerOp ~> IO =
    new InstrumentedNomadClient(dockerInstance, interp, metrics)
}

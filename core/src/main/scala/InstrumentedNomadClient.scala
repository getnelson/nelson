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

import scalaz.{\/-, -\/, ~>}
import scalaz.concurrent.Task
import nelson.scheduler.SchedulerOp
import nelson.scheduler.SchedulerOp._

class InstrumentedNomadClient private (instance: String, interp: SchedulerOp ~> Task, metrics: Metrics)
    extends (SchedulerOp ~> Task) {

  def apply[A](op: SchedulerOp[A]): Task[A] =
    Task.delay(System.nanoTime).flatMap { startNanos =>
      interp(op).attempt.flatMap { att =>
        val elapsed = System.nanoTime - startNanos
        val label = toLabel(op)
        metrics.nomadRequestsLatencySeconds.labels(label, instance).observe(elapsed / 1.0e9)
        att match {
          case \/-(a) =>
            Task.now(a)
          case -\/(e) =>
            metrics.nomadRequestsFailuresTotal.labels(label, instance).inc()
            Task.fail(e)
        }
      }
    }

  private def toLabel(op: SchedulerOp[_]) = op match {
    case _: Launch => "launch"
    case _: Delete => "delete"
    case _: Summary => "summary"
    case _: RunningUnits => "running_units"
    case _: Allocations => "allocations"
    case _: EquivalentStatus => "equivalent_status"
  }
}

object InstrumentedNomadClient {

  def apply(dockerInstance: String, interp: SchedulerOp ~> Task, metrics: Metrics = Metrics.default): SchedulerOp ~> Task =
    new InstrumentedNomadClient(dockerInstance, interp, metrics)
}

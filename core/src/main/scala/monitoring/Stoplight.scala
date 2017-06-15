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
package nelson.monitoring

import io.prometheus.client.Gauge
import scalaz.concurrent.Task
import scalaz.stream.Cause
import scalaz.stream.Process

object Stoplight {
  val Stopped = 0.0
  val Running = 1.0
  val Failed = -1.0

  def apply[A](name: String)(p: Process[Task, A]): Process[Task, A] = {
    val gauge = (new Gauge.Builder)
      .name(name)
      .help(s"Status of ${name} process: 0=stopped, 1=running, -1=failed")
      .register()
    apply(gauge)(p)
  }

  def apply[A](gauge: Gauge)(p: Process[Task, A]): Process[Task, A] =
    Process.await(Task.delay(gauge.set(Running))) { _ =>
      p.onHalt {
        case Cause.End => Process.eval_(Task.delay(gauge.set(Stopped)))
        case _ => Process.eval_(Task.delay(gauge.set(Failed)))
      }
    }
}

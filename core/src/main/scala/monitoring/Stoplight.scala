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

import cats.effect.IO

import fs2.Stream

import io.prometheus.client.Gauge

object Stoplight {
  val Stopped = 0.0
  val Running = 1.0
  val Failed = -1.0

  def apply[A](name: String)(p: Stream[IO, A]): Stream[IO, A] = {
    val gauge = (new Gauge.Builder)
      .name(name)
      .help(s"Status of ${name} process: 0=stopped, 1=running, -1=failed")
      .register()
    apply(gauge)(p)
  }

  def apply[A](gauge: Gauge)(p: Stream[IO, A]): Stream[IO, A] =
    (Stream.eval_(IO(gauge.set(Running))) ++ p ++ Stream.eval_(IO(gauge.set(Stopped)))).handleErrorWith(_ => Stream.eval_(IO(gauge.set(Failed))))
}

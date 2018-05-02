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
package monitoring

import cats.effect.IO
import fs2.Stream
import io.prometheus.client._

class StoplightSpec extends NelsonSuite {
  val reg = new CollectorRegistry
  val gaugeName = "testGauge"
  val gauge = (new Gauge.Builder).name(gaugeName).help(gaugeName).register(reg)
  def gaugeValue = Option(reg.getSampleValue(gaugeName))

  it should "be stopped before run" in {
    Stoplight(gauge)(Stream.constant(1))
    gaugeValue should be (Some(Stoplight.Stopped))
  }

  it should "be running while running" in {
    val p = Stoplight(gauge)(Stream.eval(IO(gaugeValue)))
    p.compile.last.unsafeRunSync().flatten should be (Some(Stoplight.Running))
  }

  it should "be stopped after successful run" in {
    Stoplight(gauge)(Stream.emit(1)).compile.drain.unsafeRunSync()
    gaugeValue should be (Some(Stoplight.Stopped))
  }

  it should "be failed after failure" in {
    Stoplight(gauge)(Stream.raiseError(new Exception("sad trombone"))).compile.drain.unsafeRunSync()
    gaugeValue should be (Some(Stoplight.Failed))
  }
}

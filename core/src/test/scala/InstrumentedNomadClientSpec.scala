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

import io.prometheus.client.CollectorRegistry
import org.scalatest.FlatSpec
import nelson.scheduler.SchedulerOp
import nelson.Datacenter._

class InstrumentedNomadClientSpec extends FlatSpec with NelsonSuite {
  val reg = new CollectorRegistry
  val client = InstrumentedNomadClient("test", sched, Metrics(reg))
  def getValue(name: String, labels: (String, String)*): Double =
    Option(reg.getSampleValue(name, labels.map(_._1).toArray, labels.map(_._2).toArray)).fold(0.0)(_.doubleValue)

  behavior of "PrometheusClient"

  val ns = Datacenter.Namespace(0L, NamespaceName("dev"), "dev")

  it should "record latency" in {
    def value = getValue("nomad_requests_latency_seconds_count", "nomad_op" -> "delete", "nomad_instance" -> "test")
    val before = value
    val now = java.time.Instant.now
    val d = Deployment(4L, DCUnit(4L,"foo",Version(2,1,0),"",Set.empty,Set.empty,Set.empty),"e",ns,now,"pulsar","plan","guid","retain-active",None)
    SchedulerOp.delete(config.datacenters.head, d).foldMap(client).unsafeRunSync()
    val after = value
    after should equal (before + 1.0)
  }
}

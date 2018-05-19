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

class PrometheusConsulSpec extends FlatSpec with NelsonSuite {
  val reg = new CollectorRegistry
  val client = PrometheusConsul("test", testConsul, Metrics(reg))
  def getValue(name: String, labels: (String, String)*): Double =
    Option(reg.getSampleValue(name, labels.map(_._1).toArray, labels.map(_._2).toArray)).fold(0.0)(_.doubleValue)

  behavior of "PrometheusClient"

  it should "record latency" in {
    def value = getValue("helm_requests_latency_seconds_count", "helm_op" -> "healthListChecksForService", "consul_instance" -> "test")
    val before = value
    helm.run(client, helm.ConsulOp.healthListChecksForService("foo", None, None, None)).attempt.unsafeRunSync()
    val after = value
    after should equal (before + 1.0)
  }

  it should "record failures" in {
    def value = getValue("helm_requests_failures_total", "helm_op" -> "kvGet", "consul_instance" -> "test")
    val before = value
    helm.run(client, helm.ConsulOp.kvGet("I don't exist in consulMap")).attempt.unsafeRunSync()
    val after = value
    after should equal (before + 1.0)
  }
}

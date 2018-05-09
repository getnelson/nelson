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
import docker._
import nelson.docker.Docker.Image

class InstrumentedDockerClientSpec extends FlatSpec with NelsonSuite {
  val reg = new CollectorRegistry
  val client = InstrumentedDockerClient("test", testDocker, Metrics(reg))
  def getValue(name: String, labels: (String, String)*): Double =
    Option(reg.getSampleValue(name, labels.map(_._1).toArray, labels.map(_._2).toArray)).fold(0.0)(_.doubleValue)

  behavior of "PrometheusClient"

  it should "record latency" in {
    val image = Image("test", "0.0.1")
    def value = getValue("docker_requests_latency_seconds_count", "docker_op" -> "push", "docker_instance" -> "test")
    val before = value
    DockerOp.push(image).foldMap(client).unsafeRunSync()
    val after = value
    after should equal (before + 1.0)
  }
}

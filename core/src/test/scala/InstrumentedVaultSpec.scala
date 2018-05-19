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
import cats.~>
import cats.effect.IO
import vault._

class InstrumentedVaultSpec extends FlatSpec with NelsonSuite {
  val reg = new CollectorRegistry
  val metrics = Metrics(reg)
  val client = InstrumentedVaultClient("test", testVault, metrics)
  def getValue(name: String, labels: (String, String)*): Double =
    Option(reg.getSampleValue(name, labels.map(_._1).toArray, labels.map(_._2).toArray)).fold(0.0)(_.doubleValue)

  behavior of "PrometheusVault"

  it should "record latency" in {
    def value = getValue("vault_requests_latency_seconds_count", "vault_op" -> "getMounts", "vault_instance" -> "test")
    val before = value
    Vault.getMounts.foldMap(client).attempt.unsafeRunSync()
    val after = value
    after should equal (before + 1.0)
  }

  it should "record failures" in {
    val badInterp = new (Vault ~> IO) {
      def apply[A](op: Vault[A]): IO[A] = IO.raiseError(new Exception("sad trombone"))
    }
    val badClient = InstrumentedVaultClient("test", badInterp, metrics)
    def value = getValue("vault_requests_failures_total", "vault_op" -> "get", "vault_instance" -> "test")
    val before = value
    Vault.get("I don't exist in vaultMap").foldMap(badClient).attempt.unsafeRunSync()
    val after = value
    after should equal (before + 1.0)
  }
}

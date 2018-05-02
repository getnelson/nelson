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

import alerts.RuleRewriter, RuleRewriter._
import nelson.Datacenter.StackName
import nelson.Manifest.{ PrometheusAlert, PrometheusConfig, PrometheusRule }

import org.scalatest.prop.Checkers

class RuleRewriterSpec extends NelsonSuite with Checkers {
  "toSerializedRules" should "serialize rules" in {
    // serialization of rules happens before rewrite, so no prefixes
    toSerializedRules(PrometheusConfig(
      List(PrometheusAlert("InstanceDown", "IF up == 0 FOR 1m")),
      List(PrometheusRule("a:b:c", "avg(abc)"))
    )) should equal ("a:b:c = avg(abc)\n\nALERT InstanceDown\nIF up == 0 FOR 1m\n\n")
  }

  "identity" should "return Rewritten for valid rules" in {
    val alerting = PrometheusConfig(
      List(PrometheusAlert("alert_with_rewrite", """IF a:b:c == 0 OR d:e:f == 1 ANNOTATIONS { foo = "bar" }""")),
      List(PrometheusRule("a:b:c", "avg(abc)"))
    )
    RuleRewriter.identity.rewriteRules(StackName("example-unit", Version(1, 0, 0), "abc12345"), NamespaceName("test"), "default", alerting).unsafeRunSync().asInstanceOf[Rewritten].transformed should equal (
      """a:b:c = avg(abc)
        |
        |ALERT alert_with_rewrite
        |IF a:b:c == 0 OR d:e:f == 1 ANNOTATIONS { foo = "bar" }
        |
        |""".stripMargin
    )
  }
}

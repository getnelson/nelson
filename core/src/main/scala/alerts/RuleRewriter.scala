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
package nelson.alerts

import nelson.Datacenter.StackName
import nelson.{NamespaceName, PlanRef}
import nelson.Manifest.PrometheusConfig

import cats.effect.IO

import java.util.regex.Pattern

trait RuleRewriter {
  def rewriteRules(stackName: StackName, ns: NamespaceName, plan: PlanRef, prometheus: PrometheusConfig): IO[RuleRewriter.Result]
}

object RuleRewriter {
  /** The result of calling the rule rewriter */
  abstract class Result(val isValid: Boolean) extends Product with Serializable
  /** Overhaul was able to translate the rules */
  final case class Rewritten(transformed: String) extends Result(true)
  /** The prometheus rules parsed incorrectly */
  final case class Invalid(msg: String) extends Result(false)
  /** Overhaul not called correctly */
  final case class Error(exitCode: Int, msg: String)
      extends RuntimeException(s"overhaul exited with code $exitCode: $msg")

  def toSerializedRules(prometheus: PrometheusConfig): String = {
    val sb = new StringBuffer
    for (rule <- prometheus.rules)
      sb.append(sanitize(rule.rule)).append(" = ").append(rule.expression).append("\n\n")
    for (alert <- prometheus.alerts) {
      sb.append("ALERT ").append(sanitize(alert.alert)).append("\n")
      sb.append(alert.expression).append("\n\n")
    }
    sb.toString
  }

  private val SanitizeBodyPattern = Pattern.compile("[^a-zA-Z0-9_:]");
  // Like Prometheus' sanitizeMetricName, but allow colons
  def sanitize(s: String) =
    SanitizeBodyPattern.matcher(
      SanitizeBodyPattern.matcher(s).replaceFirst("_")
    ).replaceAll("_");

  /** A rule rewriter that passes through rules verbatim */
  val identity: RuleRewriter =
    new RuleRewriter {
      def rewriteRules(stackName: StackName, ns: NamespaceName, plan: PlanRef, prometheus: PrometheusConfig): IO[Result] = {
        val rules = toSerializedRules(prometheus)
        IO.pure(Rewritten(rules))
      }
    }

  val autoDetect: IO[RuleRewriter] =
    nelson.process.isOnPath("overhaul").map {
      case true  => Overhaul
      case false => identity
    }
}

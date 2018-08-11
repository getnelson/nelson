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

/**
 * Contains code related to validating alerts and storing their configuration in
 * Consul.
 *
 * In theory, we can support multiple alerting systems.  In current practice, we
 * only support Prometheus.
 */
package object alerts {
  import nelson.Datacenter.StackName
  import nelson.Manifest._
  import nelson.Manifest.AlertOptOut

  import cats.effect.IO
  import cats.free.Free

  import helm.ConsulOp

  import journal.Logger

  private[this] val logger = Logger("nelson.alerts")

  def alertingKey(stackName: StackName): String =
    s"nelson/alerting/v2/${stackName}"

  /**
   * Writes alert configuration to consul, if not opted out.
   */
  def writeToConsul(sn: StackName, ns: NamespaceName, plan: PlanRef, u: UnitDef, outs: List[AlertOptOut]): ConsulOp.ConsulOpF[Option[String]] = {
    if (outs.contains(ns.asString))
      Free.pure(None)
    else {
      val key = alertingKey(sn)
      // TODO a run not at the end of the world and a throw.  The horror.
      // ConsulOp doesn't represent other tasks well, nor does it represent failure
      val rules = rewriteRules(u, sn, plan, ns, outs).unsafeRunSync().fold(throw _, identity)
      ConsulOp.kvSet(key, rules).map(_ => Some(rules))
    }
  }

  def optedOutPrometheusConfig(unit: UnitDef, outs: List[AlertOptOut]): PrometheusConfig = {
    val optedOut = outs.map(_.ref).toSet
    val pc = unit.alerting.prometheus
    pc.copy(alerts = pc.alerts.filterNot(a => optedOut(a.alert)))
  }

  def rewriteRules(unit: UnitDef, stackName: StackName, plan: PlanRef, ns: NamespaceName, outs: List[AlertOptOut]): IO[Either[NelsonError, String]] = {
    val pc = optedOutPrometheusConfig(unit, outs)
    for {
      rewriter <- RuleRewriter.autoDetect
      result   <- rewriter.rewriteRules(stackName, ns, plan, pc)
    } yield result match {
      case RuleRewriter.Rewritten(s) => Right(s)
      case RuleRewriter.Invalid(s)   => Left(InvalidPrometheusRules(s))
    }
  }

  /**
   * Deletes an alert configuration from consul.  This includes alert
   * definitions, recording rules, and per-namespace opt-outs.
   */
  def deleteFromConsul(stackName: StackName): ConsulOp.ConsulOpF[Unit] =
    ConsulOp.kvDelete(alertingKey(stackName))
}


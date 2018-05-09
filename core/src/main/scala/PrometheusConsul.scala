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

import cats.~>
import cats.effect.IO

import helm.ConsulOp
import helm.ConsulOp._

class PrometheusConsul private (instance: String, interp: ConsulOp ~> IO, metrics: Metrics)
    extends (ConsulOp ~> IO) {

  def apply[A](op: ConsulOp[A]): IO[A] =
    IO(System.nanoTime).flatMap { startNanos =>
      interp(op).attempt.flatMap { att =>
        val elapsed = System.nanoTime - startNanos
        val label = toLabel(op)
        metrics.helmRequestsLatencySeconds.labels(label, instance).observe(elapsed / 1.0e9)
        att match {
          case Right(a) =>
            IO.pure(a)
          case Left(e) =>
            metrics.helmRequestsFailuresTotal.labels(label, instance).inc()
            IO.raiseError(e)
        }
      }
    }

  private def toLabel(op: ConsulOp[_]) = op match {
    case _: KVGet => "kvGet"
    case _: KVSet => "kvSet"
    case _: KVDelete => "kvDelete"
    case _: KVListKeys => "kvListKeys"
    case _: HealthListChecksForService => "healthListChecksForService"
    case _: AgentDeregisterService => "agentDeregisterService"
    case _: AgentEnableMaintenanceMode => "agentEnableMaintenanceMode"
    case _: AgentListServices.type => "agentListServices"
    case _: AgentRegisterService => "agentRegisterService"
    case _: HealthListChecksForNode => "healthListChecksForNode"
    case _: HealthListChecksInState => "healthListChecksInState"
    case _: HealthListNodesForService => "healthListNodesForService"
  }
}

object PrometheusConsul {

  /** Instruments a helm interpreter with Prometheus metrics.
   *  @param consulInstance an identifier for the instance of consul we're talking to
   *  @param interp a helm interpreter to wrap
   *  @param registry the CollectorRegistry to record to; defaults to CollectorRegistry.default
   */
  def apply(consulInstance: String, interp: ConsulOp ~> IO, metrics: Metrics = Metrics.default): ConsulOp ~> IO =
    new PrometheusConsul(consulInstance, interp, metrics)
}

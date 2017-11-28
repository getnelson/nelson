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

import scalaz.{Kleisli, ~>}
import scalaz.concurrent.Task
import vault.Vault
import vault.Vault._

class InstrumentedVaultClient private (instance: String, interp: Vault ~> Task, metrics: Metrics)
    extends (Vault ~> Task) {

  def timer(label: String) : Task ~> Task =
    Metrics.timer(
      before = Task.now(()),
      onComplete = Kleisli[Task, Double, Unit] { elapsed =>
        Task.delay(metrics.vaultRequestsLatencySeconds.labels(label, instance).observe(elapsed))
      },
      onFail = Kleisli[Task, Throwable, Unit] { _ =>
        Task.delay(metrics.vaultRequestsFailuresTotal.labels(label, instance).inc())
      },
      onSuccess = Task.now(())
    )

  def apply[A](op: Vault[A]): Task[A] = timer(toLabel(op))(interp(op))

  private def toLabel(op: Vault[_]) = op match {
    case IsInitialized => "isInitialized"
    case _: Initialize => "initialize"
    case _: Unseal => "unseal"
    case GetSealStatus => "getSealStatus"
    case Seal => "seal"
    case _: Get => "get"
    case _: Set => "set"
    case GetMounts => "getMounts"
    case _: CreatePolicy => "createPolicy"
    case _: DeletePolicy => "deletePolicy"
    case _: CreateToken => "createToken"
  }
}

object InstrumentedVaultClient {
  /** Instruments a vault interpreter with Prometheus metrics.
   *  @param consulInstance an identifier for the instance of consul we're talking to
   *  @param interp a vault interpreter to wrap
   *  @param registry the CollectorRegistry to record to; defaults to CollectorRegistry.default
   */
  def apply(consulInstance: String, interp: Vault ~> Task, metrics: Metrics = Metrics.default): Vault ~> Task =
    new InstrumentedVaultClient(consulInstance, interp, metrics)
}

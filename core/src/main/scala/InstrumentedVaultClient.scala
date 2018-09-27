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
import cats.data.Kleisli
import cats.effect.IO

import vault.Vault
import vault.Vault._

class InstrumentedVaultClient private (instance: String, interp: Vault ~> IO, metrics: Metrics)
    extends (Vault ~> IO) {

  def timer(label: String) : IO ~> IO =
    Metrics.timer(
      before = IO.unit,
      onComplete = Kleisli[IO, Double, Unit] { elapsed =>
        IO(metrics.vaultRequestsLatencySeconds.labels(label, instance).observe(elapsed))
      },
      onFail = Kleisli[IO, Throwable, Unit] { _ =>
        IO(metrics.vaultRequestsFailuresTotal.labels(label, instance).inc())
      },
      onSuccess = IO.unit
    )

  def apply[A](op: Vault[A]): IO[A] = timer(toLabel(op))(interp(op))

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
    case _: CreateKubernetesRole => "createKubernetesRole"
    case _: DeleteKubernetesRole => "deleteKubernetesRole"
  }
}

object InstrumentedVaultClient {
  /** Instruments a vault interpreter with Prometheus metrics.
   *  @param consulInstance an identifier for the instance of consul we're talking to
   *  @param interp a vault interpreter to wrap
   *  @param registry the CollectorRegistry to record to; defaults to CollectorRegistry.default
   */
  def apply(consulInstance: String, interp: Vault ~> IO, metrics: Metrics = Metrics.default): Vault ~> IO =
    new InstrumentedVaultClient(consulInstance, interp, metrics)
}

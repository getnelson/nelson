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
package health

import cats.~>
import cats.effect.IO

import helm.ConsulOp

final case class Http4sConsulHealthClient(client: ConsulOp ~> IO) extends (HealthCheckOp ~> IO) {

  import HealthCheckOp._

  def apply[A](a: HealthCheckOp[A]): IO[A] = a match {
    case Health(_, _, sn) =>
      val op = ConsulOp.healthListChecksForService(sn.toString, None, None, None).
        map(_.map(hcr => HealthStatus(hcr.checkId, toNelsonStatus(hcr.status), hcr.node, Some(hcr.name))))
      helm.run(client, op)
  }

  private def toNelsonStatus(status: helm.HealthStatus): HealthCheck = status match {
    case helm.HealthStatus.Passing  => Passing
    case helm.HealthStatus.Critical => Failing
    case _                          => Unknown
  }
}

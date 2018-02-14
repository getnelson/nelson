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

import argonaut._, Argonaut._

import cats.effect.IO
import nelson.CatsHelpers._

import scalaz.~>
import scalaz.syntax.std.option._

import helm.ConsulOp

final case class Http4sConsulHealthClient(client: ConsulOp ~> IO) extends (HealthCheckOp ~> IO) {

  import HealthCheckOp._

  def apply[A](a: HealthCheckOp[A]): IO[A] = a match {
    case Health(dc, ns, sn) =>
      val op = ConsulOp.healthCheckJson[HealthStatus](sn.toString).map(_.fold(_ => Nil, x => x))
      helm.run(client, op) 
  }

  private def fromConsulString(s: String): HealthCheck = s match {
    case "passing"  => Passing
    case "critical" => Failing
    case _          => Unknown
  }

  implicit val healthStatusDecoder: DecodeJson[HealthStatus] =
    DecodeJson(c => for {
      id     <- (c --\ "CheckID").as[String]
      status <- (c --\ "Status").as[String].map(fromConsulString)
      node   <- (c --\ "Node").as[String]
      name   <- (c --\ "Name").as[String]
    } yield HealthStatus(id, status, node, Some(name)))
}

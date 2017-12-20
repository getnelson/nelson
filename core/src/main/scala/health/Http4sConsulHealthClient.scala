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

import scalaz.~>
import scalaz.syntax.std.option._
import scalaz.concurrent.Task
import helm.ConsulOp
import argonaut._, Argonaut._


final case class Http4sConsulHealthClient(client: ConsulOp ~> Task) extends (HealthCheckOp ~> Task) {
  import HealthCheckOp._
  import Http4sConsulHealthClient._

  def apply[A](a: HealthCheckOp[A]): Task[A] = a match {
    case Healthy(dc, ns, sn) =>
      val op = ConsulOp.healthCheckJson[HealthCheck](sn.toString).map(_.fold(_ => Nil, x => x))
      val checks = helm.run(client, op)
      checks.map { cs =>
        val (passing, notPassing) = cs.foldLeft((0, 0)) {
          case ((p, np), Passing)    => (p + 1, np)
          case ((p, np), NotPassing) => (p, np + 1)
        }

        passing > notPassing
      }
  }

  private def fromConsulString(s: String): HealthCheck = s match {
    case "passing"  => Passing
    case _          => NotPassing
  }

  implicit val healthCheckDecoder: DecodeJson[HealthCheck] =
    DecodeJson[HealthCheck] { c =>
      c.as[String].flatMap { s =>
        DecodeResult.ok(fromConsulString(s))
      }
    }
}

object Http4sConsulHealthClient {
  sealed abstract class HealthCheck extends Product with Serializable
  final case object Passing extends HealthCheck
  final case object NotPassing extends HealthCheck
}

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
import cats.syntax.either._
import cats.syntax.applicativeError._
import fs2.Sink

import helm.ConsulOp
import helm.ConsulOp.ConsulOpF
import helm.http4s.Http4sConsulClient

import journal._

import org.http4s._
import org.http4s.client._

import scala.util.control.NonFatal

object Http4sConsul {
  val log = Logger[Http4sConsul.type]

  def baseUri(consul: Infrastructure.Consul): Uri = {
    val consulHost = consul.endpoint.getHost
    val port = consul.endpoint.getPort
    val port0 = if (port <= 0) 80 else port
    Uri.fromString(s"http://$consulHost:$port0").toOption.yolo("Invalid URI for consul")
  }

  def token(consul: Infrastructure.Consul): Option[String] =
    consul.aclToken.filter(_.nonEmpty)

  def creds(consul: Infrastructure.Consul): Option[(String,String)] =
    consul.creds.map(x => x.username -> x.password)

  def client(consul: Infrastructure.Consul, http4sClient: Client[IO]): ConsulOp ~> IO =
    new Http4sConsulClient(baseUri(consul), http4sClient, token(consul), creds(consul))

  def consulSink: Sink[IO, (Datacenter,ConsulOpF[Unit])] =
    Sink {
      case (dc, op) => helm.run(dc.consul, op) recover {
        case NonFatal(e) => log.error(s"error while attempting to perform consul operation", e)
      }
    }
}

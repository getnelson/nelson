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
package helmhttp4s

import cats.effect.IO
import cats.syntax.applicativeError._

import fs2.Stream
import fs2.interop.scodec.ByteVectorChunk

import helm.{ConsulOp, Key}

import journal.Logger

import org.http4s.Status.NotFound
import org.http4s._
import org.http4s.argonaut.jsonOf
import org.http4s.client._
import org.http4s.headers.Authorization

import scalaz.~>

import scodec.bits.ByteVector

final class Http4sConsulClient(baseUri: Uri,
                               client: Client[IO],
                               accessToken: Option[String] = None,
                               credentials: Option[(String,String)] = None) extends (ConsulOp ~> IO) {

  private implicit val keysDecoder: EntityDecoder[IO, List[String]] = jsonOf[IO, List[String]]

  private val log = Logger[this.type]

  def apply[A](op: ConsulOp[A]): IO[A] = op match {
    case ConsulOp.Get(key)             => get(key)
    case ConsulOp.Set(key, value)      => set(key, value)
    case ConsulOp.ListKeys(prefix)     => list(prefix)
    case ConsulOp.Delete(key)          => delete(key)
    case ConsulOp.HealthCheck(service) => healthCheck(service)
  }

  def addConsulToken(req: Request[IO]): Request[IO] =
    accessToken.fold(req)(tok => req.putHeaders(Header("X-Consul-Token", tok)))

  def addCreds(req: Request[IO]): Request[IO] =
    credentials.fold(req){case (un,pw) => req.putHeaders(Authorization(BasicCredentials(un,pw)))}

  def get(key: Key): IO[Option[String]] = {
    for {
      _     <- IO(log.debug(s"fetching consul key $key"))
      req   = addCreds(addConsulToken(Request(uri = (baseUri / "v1" / "kv" / key).+?("raw"))))
      value <- client.expect[String](req).map(r => Some(r): Option[String]).recoverWith {
        case UnexpectedStatus(NotFound) => IO.pure(None)
      }
    } yield {
      log.debug(s"consul value for key $key is $value")
      value
    }
  }

  def set(key: Key, value: String): IO[Unit] =
    for {
      _ <- IO(log.debug(s"setting consul key $key to $value"))
      response <- client.expect[String](
        addCreds(addConsulToken(
          Request(Method.PUT,
            uri = baseUri / "v1" / "kv" / key,
            body = Stream.chunk(ByteVectorChunk(ByteVector.view(value.getBytes("UTF-8"))))))))
    } yield log.debug(s"setting consul key $key resulted in response $response")

  def list(prefix: Key): IO[Set[Key]] = {
    val req = addCreds(addConsulToken(Request(uri = (baseUri / "v1" / "kv" / prefix).withQueryParam(QueryParam.fromKey("keys")))))

    for {
      _ <- IO(log.debug(s"listing key consul with the prefix: $prefix"))
      response <- client.expect[List[String]](req)
    } yield {
      log.debug(s"listing of keys: " + response)
      response.toSet
    }
  }

  def delete(key: Key): IO[Unit] = {
    val req = addCreds(addConsulToken(Request(Method.DELETE, uri = (baseUri / "v1" / "kv" / key))))

    for {
      _ <- IO(log.debug(s"deleting $key from the consul KV store"))
      response <- client.expect[String](req)
    } yield log.debug(s"response from delete: " + response)
  }

  def healthCheck(service: String): IO[String] = {
    for {
      _ <- IO(log.debug(s"fetching health status for $service"))
      req = addCreds(addConsulToken(Request(uri = (baseUri / "v1" / "health" / "checks" / service))))
      response <- client.expect[String](req)
    } yield {
      log.debug(s"health check response: " + response)
      response
    }
  }
}

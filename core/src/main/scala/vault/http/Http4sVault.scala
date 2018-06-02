//: ----------------------------------------------------------------------------
//: Copyright (C) 2014 Verizon.  All Rights Reserved.
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
package vault
package http4s

import argonaut._, Argonaut._

import cats.{~>, FlatMap}
import cats.effect.IO
import cats.syntax.functor._

import journal._

import org.http4s.{argonaut => _, _}
import org.http4s.argonaut._
import org.http4s.client._

import scala.collection.immutable.SortedMap

final case class Initialized(init: Boolean)


final class Http4sVaultClient(authToken: Token,
                              baseUri: Uri,
                              client: Client[IO]) extends (Vault ~> IO) with Json {

  import Vault._
  import Method._
  import Status._

  val v1BaseUri = baseUri / "v1"

  def apply[A](v: Vault[A]): IO[A] = v match {
    case IsInitialized          => isInitialized
    case Initialize(init)       => initialize(init)
    case GetSealStatus          => sealStatus
    case Seal                   => seal
    case Unseal(key)            => unseal(key)
    case Get(path)              => get(path)
    case Set(path, value)       => put(path,value)
    case cp @ CreatePolicy(_,_) => createPolicy(cp)
    case DeletePolicy(name)     => deletePolicy(name)
    case GetMounts              => getMounts
    case ct: CreateToken        => createToken(ct)
  }

  val log = Logger[this.type]

  val addCreds: Request[IO] => Request[IO] = _.putHeaders(Header("X-Vault-Token", authToken.value))

  def req[T: DecodeJson](req: IO[Request[IO]]): IO[T] =
    client.fetch(req.map(addCreds)){
      case Ok(resp) => resp.as(FlatMap[IO], jsonOf[IO, T])
      case resp =>
        (for {
          r <- req
          body <- resp.as[String]
        } yield {
          val msg = s"unexpected status: ${resp.status} from request: ${r.pathInfo}, msg: ${body}"
          IO.raiseError(new RuntimeException(msg))
        }).flatMap(identity)
    }

  def reqVoid(req: IO[Request[IO]]): IO[Unit] =
    client.fetch(req.map(addCreds)) {
      case NoContent(resp) => IO.pure(())
      case resp =>
        (for {
          r <- req
          body <- resp.as[String]
        } yield {
          val msg = s"unexpected status: ${resp.status} from request: ${r.pathInfo}, msg: ${body}"
          IO.raiseError(new RuntimeException(msg))
        }).flatMap(identity)
    }

  def isInitialized: IO[Boolean] =
    req[Initialized](IO.pure(Request(GET, v1BaseUri / "sys" / "init"))).map(_.init)

  def initialize(init: Initialization): IO[InitialCreds] =
    req[InitialCreds](Request(PUT, v1BaseUri / "sys" / "init").withBody(init.asJson))

  def unseal(key: MasterKey): IO[SealStatus] =
    req[SealStatus](Request(PUT, v1BaseUri / "sys" / "unseal").withBody(jsonUnseal(key)))

  def sealStatus: IO[SealStatus] =
    req[SealStatus](IO.pure(Request(GET, v1BaseUri / "sys" / "seal-status")))

  def seal: IO[Unit] =
    req[String](IO.pure(Request(GET, v1BaseUri / "sys" / "init"))).void

  def get(path: String): IO[String] =
    req[String](IO.pure(Request(GET, v1BaseUri / path)))

  def put(path: String, value: String): IO[Unit] =
    req[String](Request(POST,v1BaseUri / path).withBody(value)).void

  def createPolicy(cp: CreatePolicy): IO[Unit] =
    reqVoid(Request(POST, v1BaseUri / "sys" / "policy" / cp.name).withBody(cp.asJson))

  def deletePolicy(name: String): IO[Unit] =
    reqVoid(IO.pure(Request(DELETE, v1BaseUri / "sys" / "policy" / name)))

  def getMounts: IO[SortedMap[String, Mount]] =
    req[SortedMap[String, Mount]](IO.pure(Request(GET, uri = v1BaseUri / "sys" / "mounts")))

  def createToken(ct: CreateToken): IO[Token] =
    req[argonaut.Json](Request(POST, v1BaseUri / "auth" / "token" / "create").withBody(ct.asJson)).flatMap { json =>
      val clientToken = for {
        cursor <- Some(json.cursor): Option[Cursor]
        auth   <- cursor.downField("auth")
        token  <- auth.downField("client_token")
        str    <- token.focus.string
      } yield str

      clientToken  match {
        case Some(token) => IO.pure(Token(token))
        case None => IO.raiseError(new RuntimeException("No auth/client_token in create token response"))
      }
    }
}

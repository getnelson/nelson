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

import scalaz.{==>>,~>}
import scalaz.syntax.functor._
import scalaz.concurrent.Task
import argonaut._, Argonaut._
import org.http4s.{argonaut => _, _}
import org.http4s.argonaut._
import org.http4s.client._
import journal._

final case class Initialized(init: Boolean)


final class Http4sVaultClient(authToken: Token,
                              baseUri: Uri,
                              client: Client) extends (Vault ~> Task) with Json {

  import Vault._
  import Method._
  import Status._

  val v1BaseUri = baseUri / "v1"

  def apply[A](v: Vault[A]): Task[A] = v match {
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

  val addCreds: Request => Request = _.putHeaders(Header("X-Vault-Token", authToken.value))

  def req[T: DecodeJson](req: Task[Request]): Task[T] =
    client.fetch(req.map(addCreds)){
      case Ok(resp) => resp.as(jsonOf[T])
      case resp =>
        (for {
          r <- req
          body <- resp.as[String]
        } yield {
          val msg = s"unexpected status: ${resp.status} from request: ${r.pathInfo}, msg: ${body}"
          Task.fail(new RuntimeException(msg))
        }).flatMap(identity)
    }

  def reqVoid(req: Task[Request]): Task[Unit] =
    client.fetch(req.map(addCreds)) {
      case NoContent(resp) => Task.now(())
      case resp =>
        (for {
          r <- req
          body <- resp.as[String]
        } yield {
          val msg = s"unexpected status: ${resp.status} from request: ${r.pathInfo}, msg: ${body}"
          Task.fail(new RuntimeException(msg))
        }).flatMap(identity)
    }

  def isInitialized: Task[Boolean] =
    req[Initialized](GET(v1BaseUri / "sys" / "init")).map(_.init)

  def initialize(init: Initialization): Task[InitialCreds] =
    req[InitialCreds](PUT(v1BaseUri / "sys" / "init", init.asJson))

  def unseal(key: MasterKey): Task[SealStatus] =
    req[SealStatus](PUT(v1BaseUri / "sys" / "unseal", jsonUnseal(key)))

  def sealStatus: Task[SealStatus] =
    req[SealStatus](GET(v1BaseUri / "sys" / "seal-status"))

  def seal: Task[Unit] =
    req[String](PUT(v1BaseUri / "sys" / "init")).void

  def get(path: String): Task[String] =
    req[String](GET(v1BaseUri / path))

  def put(path: String, value: String): Task[Unit] =
    req[String](POST(v1BaseUri / path, value)).void

  def createPolicy(cp: CreatePolicy): Task[Unit] =
    reqVoid(POST(v1BaseUri / "sys" / "policy" / cp.name, cp.asJson))

  def deletePolicy(name: String): Task[Unit] =
    reqVoid(DELETE(v1BaseUri / "sys" / "policy" / name))

  def getMounts: Task[String ==>> Mount] =
    req[String ==>> Mount](GET(uri = v1BaseUri / "sys" / "mounts"))

  def createToken(ct: CreateToken): Task[Token] =
    req[argonaut.Json](POST(v1BaseUri / "auth" / "token" / "create", ct.asJson)).flatMap { json =>
      val clientToken = {
        jObjectPL >=>
        jsonObjectPL("auth") >=>
        jObjectPL >=>
        jsonObjectPL("client_token") >=>
        jStringPL
      }
      clientToken.get(json) match {
        case Some(token) => Task.now(Token(token))
        case None => Task.fail(new RuntimeException("No auth/client_token in create token response"))
      }
    }
}

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
package plans

import org.http4s._
import org.http4s.dsl.io._
import org.http4s.headers.{Location, `Set-Cookie`}
import org.http4s.argonaut._
import _root_.argonaut._, Argonaut._
import cats.effect.IO
import cats.implicits._

final case class Auth(config: NelsonConfig) extends Default {
  import nelson.Json._
  private val cfg = config

  private implicit val SessionEncTuple2Encoder: EncodeJson[(java.time.Instant, String)] =
    EncodeJson { case (exp,enc) =>
      ("expires_at" := exp) ->:
      ("session_token" := enc) ->:
      jEmptyObject
    }

  val service: HttpService[IO] = HttpService[IO] {
    case GET -> Root / "auth" / "login" =>
      if(cfg.security.useEnvironmentSession){
        Found(Location(uri("/auth/exchange?code=yolo")))
      } else {
        redirectToLogin
      }

    case GET -> Root / "auth" / "logout" =>
      Found(Location(uri("/"))).map(_.putHeaders(Cookie(CookieName, "").clearCookie))

    /*
     * used to exchange github token for a nelson token. This endpoint
     * is primarily intended for non-web applications to integrate with
     * the nelson API.
     * the body of the post should look like:
     * { "access_token": "<your github token>" }
     */
    case req @ POST -> Root / "auth" / "github" =>
      decode[AccessToken](req){ tk =>
        (for {
          a <- Nelson.createSessionFromGithubToken(tk)(cfg).attempt.unsafeRunSync()
          b <- cfg.security.authenticator.serialize(a)
        } yield (a.expiry, b)).fold(
          e => IO.raiseError(new RuntimeException(e.toString)),
          s => Ok(s.asJson)
        )
      }

    case req @ GET -> Root / "auth" / "exchange" =>
      val code = req.params.getOrElse("code", "unknown")
      (for {
        a <- Nelson.createSessionFromOAuthCode(code)(cfg).attempt.unsafeRunSync()
        b <- cfg.security.authenticator.serialize(a)
      } yield b).fold(
        e => IO.raiseError(new RuntimeException(e.toString)),
        s => {
          val cookie = Cookie(CookieName, s,
            path   = Some("/"),
            domain = Some(cfg.network.externalHost),
            secure = cfg.network.tls,
            maxAge = Some(cfg.security.expireLoginAfter.toSeconds.toLong),
            httpOnly = false // determines if js can read this cookie
          )
          Found(Location(uri("/"))).map(_.putHeaders(`Set-Cookie`(cookie)))
        }
      )

    case req @ GET -> "v1" /: tail & NotAuthenticated() =>
      Response(status = Unauthorized).withBody("Supplied authentication token is invalid.")
  }
}

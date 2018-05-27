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

import cats.effect.IO
import org.http4s._

trait ServiceSpec extends NelsonSuite {
  lazy val session = Session(
    expiry = java.time.Instant.now.plusSeconds(1000),
    github = AccessToken("foobarbaz"),
    user = User(
      login = "scalatest",
      avatar = new java.net.URI("uri"),
      name = "user",
      email = Some("user@verizon.net"),
      orgs = List(Organization(0L, Some("scalatest"), "slug", new java.net.URI("uri")))
    )
  )

  lazy val serialized = config.security.authenticator.serialize(session).fold(e => sys.error(e.toString), identity)

  lazy val cookie = org.http4s.Cookie("nelson.session", serialized,
    path   = Some("/"),
    domain = Some(config.network.externalHost),
    secure = config.network.tls,
    maxAge = Some(config.security.expireLoginAfter.toSeconds.toLong),
    httpOnly = false
  )

  implicit class RequestSyntax(req: Request[IO]) {
    def authed: Request[IO] =
      req.putHeaders(org.http4s.headers.Cookie(cookie))
  }
}

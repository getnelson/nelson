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

import nelson.plans.Auth
import argonaut._, Argonaut._
import org.http4s._, headers._, dsl._, Uri.uri
import org.http4s.argonaut._
import Json._

class AuthSpec extends NelsonSuite {

  val service = Server.json500(Auth(config).service)

  "login" should "use auth exchange if environment sessions are enabled" in {
    val req = Request(GET, uri("/auth/login"))
    val config0 = config.copy(security = config.security.copy(useEnvironmentSession = true))
    val resp = Auth(config0).service.orNotFound(req).run
    resp.status should equal (Found)
    resp.headers.get(Location).map(_.uri) should equal (Some(uri("/auth/exchange?code=yolo")))
  }

  it should "use redirect to git login endpoint if environment sessions are disabled" in {
    val req = Request(GET, uri("/auth/login"))
    val config0 = config.copy(security = config.security.copy(useEnvironmentSession = false))
    val resp = Auth(config0).service.orNotFound(req).run
    resp.status should equal (Found)
    resp.headers.get(Location).fold("")(_.uri.toString) should include ("//github.com")
  }

  "logout" should "redirect to the home page" in {
    val req = Request(GET, uri("/auth/logout"))
    val resp = service.orNotFound(req).run
    resp.status should equal (Found)
    resp.headers.get(Location).map(_.uri) should equal (Some(uri("/")))
  }

  it should "Clear the cookie" in {
    val req = Request(GET, uri("/auth/logout"))
    val resp = service.orNotFound(req).run
    val cookie = resp.cookies.headOption
    cookie.map(_.name) should be (Some("nelson.session"))
    cookie.flatMap(_.maxAge) should be (Some(0L))
  }

  "/auth/github" should "return a Nelson token from a GitHub token" in {
    val accessToken = AccessToken("1234")
    val req = Request(POST, uri("/auth/github")).withBody(accessToken.asJson)
    val resp = req.flatMap(service.orNotFound).run
    resp.status should be (Ok)
    resp.as[Json].run.field("session_token").isDefined should be (true)
  }

  "/auth/github" should "return a 400 for malformed JSON" in {
    val req = Request(POST, uri("/auth/github"))
      .withBody("{")
      .replaceAllHeaders(`Content-Type`(MediaType.`application/json`))
    val resp = req.flatMap(service.orNotFound).run
    resp.status should equal (BadRequest)
    resp.as[Json].run.fieldOrNull("message") should equal (
      "Could not parse JSON".asJson)
  }

  "/auth/github" should "return a 422 for invalid (but well-formed) JSON" in {
    val req = Request(POST, uri("/auth/github")).withBody(().asJson)
    val resp = req.flatMap(service.orNotFound).run
    resp.status should equal (UnprocessableEntity)
    val json = resp.as[Json].run
    json.fieldOrNull("message") should equal ("Validation failed".asJson)
    json.fieldOrNull("cursor_history").stringOrEmpty should include ("access_token")    
  }

  "/auth/github" should "return a 415 for bad media types" in {
    val req = Request(POST, uri("/auth/github"))    
    val resp = service.orNotFound(req).run
    resp.status should equal (UnsupportedMediaType)
  }

  "/auth/github" should "return a 500 for a server error" in {
    val accessToken = AccessToken("crash")
    val req = Request(POST, uri("/auth/github")).withBody(accessToken.asJson)
    val resp = req.flatMap(service.orNotFound).run
    resp.status should equal (InternalServerError)
    resp.as[Json].run.fieldOrNull("message") should equal ("An internal error occurred".asJson)
  }

  "/auth/exchange" should "create a session from oauth code" in {
    val req = Request(GET, uri("/auth/exchange?code=goodcode"))
    val resp = service.orNotFound(req).run
    val cookie = resp.cookies.headOption
    cookie.map(_.name) should be (Some("nelson.session"))
    cookie.map(_.content.isEmpty) should equal (Some(false))
    cookie.flatMap(_.maxAge).map(_ > 0) should equal (Some(true))
  }

  it should "Redirect to login" in {
    val req = Request(GET, uri("/auth/exchange?code=goodcode"))
    val resp = service.orNotFound(req).run
    resp.status should equal (Found)
    resp.headers.get(Location).map(_.uri) should equal (Some(uri("/")))
  }

  it should "Fail with a 401 for a bad code" in pending

  it should "Fail with a 500 for a system failure" in {
    val req = Request(GET, uri("/auth/exchange?code=crash"))
    val resp = service.orNotFound(req).run
    resp.status should equal (InternalServerError)
  }
}

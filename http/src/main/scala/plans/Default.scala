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

import _root_.argonaut.{DecodeResult => _, _}, Argonaut._
import org.http4s._
import org.http4s.argonaut._
import org.http4s.dsl._
import journal.Logger
import nelson.BannedClientsConfig.HttpUserAgent

import scalaz.Order
import scalaz.concurrent.Task
import scalaz.syntax.monad._
import scalaz.syntax.std.option._

abstract class Default extends Product with Serializable { self =>
  import Nelson.{NelsonK,StorageK}
  import audit.AuditableInstances._

  protected val log = Logger[this.type]
  protected val CookieName = "nelson.session"

  def service: HttpService

  def config: NelsonConfig

  protected def json[A : EncodeJson](a: NelsonK[A]): Task[Response] =
    self.jsonHandler(a)(None)

  protected def jsonF[A : EncodeJson](a: NelsonK[A])(rf: A => Task[Response]): Task[Response] =
    self.jsonHandler(a)(Some(rf))

  protected def jsonHandler[A : EncodeJson](a: NelsonK[A])(rf: Option[A => Task[Response]]): Task[Response] = {
    val handler: A => Task[Response] = rf.getOrElse(a => Ok(a.asJson))
    a(config).flatMap(handler)
  }

  protected def handleMessageFailure(req: Request, mf: MessageFailure): Task[Response] = {
    log.error(s"Error handling request ${req.method} ${req.pathInfo}", mf)
    mf match {
      case MalformedMessageBodyFailure(details, _) =>
        BadRequest(Map(
          "message" -> "Could not parse JSON"
        ).asJson)
      case InvalidMessageBodyFailure(details, None) =>
        UnprocessableEntity(Map(
          "message" -> "Validation failed",
          // This dumps the argonaut CursorHistory, which is not a
          // spectacular rendering, but gives some idea what failed.
          "cursor_history" -> details
        ).asJson)
      case mf: MessageFailure =>
        for {
          resp <- mf.toHttpResponse(req.httpVersion)
          msg <- resp.as[String]
          resp0 <- resp.withBody(Map("message" -> msg).asJson)
        } yield resp0
    }
  }

  protected def decode[A : DecodeJson](req: Request)(f: A => Task[Response]): Task[Response] = {
    // http4s puts the cursor history in the "details", along with the JSON.
    // We don't want to reflect the JSON back to the user, but we do want to
    // give some indication what the user needs to fix.
    jsonDecoder.flatMapR[A] { json =>
      DecodeJson.of[A].decodeJson(json).fold(
        (message, history) => DecodeResult.failure(InvalidMessageBodyFailure(s"$history")),
        DecodeResult.success(_))}
      .decode(req, true).fold(
        mf => handleMessageFailure(req, mf),
        f).join
  }

  object IsAuthenticated {
    def unapply[A](req: Request): Option[Session] = {
      req.headers.get(headers.Cookie)
        .flatMap(_.values.list.find(_.name == CookieName))
        .flatMap { cookie =>
          config.security.authenticator
            .authenticate(cookie.content)
            .leftMap(_.toString).toOption
        }
    }
  }

  object IsAuthorized {
    def apply[A](session: Session): Boolean =
      apply(session, config.git.organizationAdminList)

    // TIM: this is a tempoary hack such that we don't have the "teams"
    // metadata currently available on the `User` object
    def apply[A](session: Session, list: List[String]): Boolean =
      if(config.security.useEnvironmentSession) true
      else list.find(_.trim.toLowerCase == session.user.login.trim.toLowerCase).nonEmpty
  }

  object NotAuthenticated {
    def unapply[A](req: Request): Boolean =
      IsAuthenticated.unapply(req).isEmpty
  }

  protected def redirectToLogin: Task[Response] = {
    Uri.fromString(config.git.loginEndpoint).fold(
      e => {
        InternalServerError(s"Invalid login endpoint is configured: ${e.details}")
      },
      Found(_)
    )
  }
}

object ClientValidation {

  def versionLte(v: Version, maxBannedVersion: Version): Boolean = {
    val O = implicitly[Order[Version]]
    O.lessThanOrEqual(v, maxBannedVersion)
  }

  def agentDoesntMatch(ua: headers.`User-Agent`)
    (agentConfig: BannedClientsConfig.HttpUserAgent): Boolean =
    !agentMatches(ua)(agentConfig)

  def agentMatches(ua: headers.`User-Agent`)
    (agentConfig: BannedClientsConfig.HttpUserAgent): Boolean = {
    val namesMatch = agentConfig.name == ua.product.name
    def versionsMatch: Boolean =
      agentConfig.maxBannedVersion.cata(
        // Ban all, regardless of version.
        none = true,
        some = (maxBannedVersion: Version) =>
          // If the UA has no version, allow. If the version can't be parsed, allow.
          ua.product.version.flatMap(Version.fromString).cata(
            none = false,
            // If the UA version exists and is lte the config version, ban.
            some = (uaVersion: Version) =>
              versionLte(uaVersion, maxBannedVersion)
          )
      )

    namesMatch && versionsMatch
  }

  def isAllowedUserAgent(ua: Option[headers.`User-Agent`])
    (config: Option[BannedClientsConfig]): Boolean = (config, ua) match {
    case (None, _) => true
    case (Some(_), None) => false
    case (Some(config), Some(ua)) =>
      config.httpUserAgents.forall(agentDoesntMatch(ua))
  }

  def filterUserAgent(service: HttpService)
    (config: NelsonConfig): HttpService = HttpService.lift { req =>
    val maybeUserAgent = req.headers.get(headers.`User-Agent`)
    if (isAllowedUserAgent(maybeUserAgent)(config.bannedClients)) service(req)
    else BadRequest("User-Agent not allowed. Please upgrade your client to the latest version.")
  }
}

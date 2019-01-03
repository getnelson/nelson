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

import org.http4s._
import org.http4s.argonaut._
import org.http4s.dsl.io._
import org.http4s.server.staticcontent.{FileService, fileService, ResourceService, resourceService}
import org.http4s.server.blaze._
import _root_.argonaut._
import Argonaut._

import cats.data.{Kleisli, OptionT}
import cats.effect.IO
import cats.implicits._
import journal.Logger
import nelson.plans.ClientValidation

object Server {
  private val log = Logger[Server.type]

    Kleisli[OptionT[IO, ?], Request[IO], Response[IO]](_ => OptionT[IO, Response[IO]](IO.pure(None)))

  def api(config: NelsonConfig): HttpService[IO] = List(
    plans.Repos(config),
    plans.Auth(config),
    plans.Misc(config),
    plans.WebHooks(config),
    plans.Graph(config),
    plans.Datacenters(config),
    plans.Audit(config),
    plans.Loadbalancers(config),
    plans.Blueprints(config)
  ).foldLeft(HttpService.empty[IO])(_ <+> _.service)

  val resources: HttpService[IO] = Kleisli[OptionT[IO, ?], Request[IO], Response[IO]] {
    // We want to fall through if someone tries to list a directory
    case req if req.pathInfo endsWith "/" => OptionT[IO, Response[IO]](IO.pure(None))
    case req => OptionT
      resourceService[IO](ResourceService.Config("/nelson/www")).run(req)
  }

  def files(filePath: String): HttpService[IO] = Kleisli[OptionT[IO, ?], Request[IO], Response[IO]] {
    // We want to fall through if someone tries to list a directory
    case req if req.pathInfo endsWith "/" =>
      OptionT[IO, Response[IO]](IO.pure(None))
    case req =>
      fileService[IO](FileService.Config(filePath)).run(req)
  }

  /** Catch internal server errors, log them, and return a JSON response */
  def json500(service: HttpService[IO]): HttpService[IO] =
    // I envisioned a handleError, but resolving MonadError is one of the
    // hard problems of computer science.
    Kleisli[OptionT[IO, ?], Request[IO], Response[IO]] { req =>
      service.run(req).handleErrorWith(err => OptionT.liftF(err match {
        case e@(LoadError(_) | ProblematicRepoManifest(_)) =>
          log.info(s"Unable to load repository YAML file: ${e.getMessage}")
          UnprocessableEntity(
            Map(
              "message" -> s"Please ensure you have a valid .nelson.yml file in your repository. ${e.getMessage}"
            ).asJson
          )
        case DeploymentCommitFailed(msg) =>
          BadRequest(Map("message" -> msg).asJson)
        case e@InvalidTrafficShiftReverse(msg) =>
          BadRequest(Map("message" -> msg).asJson)
        case e@MultipleValidationErrors(errors) =>
          BadRequest(Map("message" -> e.getMessage).asJson)
        case NamespaceCreateFailed(msg) =>
          BadRequest(Map("message" -> msg).asJson)
        case ManualDeployFailed(msg) =>
          BadRequest(Map("message" -> msg).asJson)
        case e@MissingDeployment(guid) =>
          BadRequest(Map("message" -> e.getMessage).asJson)
        case t: Throwable =>
          log.error(s"Error handling request", t)
          InternalServerError(Map("message" -> "An internal error occurred").asJson)
      }))
    }

  def ui(config: NelsonConfig): HttpService[IO] =
    plans.UI(config).service

  def service(config: NelsonConfig): HttpService[IO] = {
    val allServices =
      if(config.ui.enabled)
        config.ui.filePath.map(p =>
          api(config) <+> files(p) <+> ui(config)).getOrElse(
            api(config) <+> resources <+> ui(config))
      else
        api(config)

    json500(ClientValidation.filterUserAgent(allServices)(config))
  }

  def start(config: NelsonConfig): IO[org.http4s.server.Server[IO]] = {
    BlazeBuilder[IO]
      .withIdleTimeout(config.network.idleTimeout)
      .bindHttp(config.network.bindPort, config.network.bindHost)
      .mountService(service(config))
      .start
  }
}

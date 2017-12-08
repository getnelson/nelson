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
import org.http4s.dsl._
import org.http4s.server.staticcontent.{FileService, fileService, ResourceService, resourceService}
import org.http4s.server.syntax._
import org.http4s.server.blaze._
import _root_.argonaut._
import Argonaut._

import scalaz.concurrent.Task
import journal.Logger
import nelson.plans.ClientValidation

object Server {
  private val log = Logger[Server.type]

  def api(config: NelsonConfig): HttpService = List(
    plans.Repos(config),
    plans.Auth(config),
    plans.Misc(config),
    plans.WebHooks(config),
    plans.Graph(config),
    plans.Domains(config),
    plans.Audit(config),
    plans.Loadbalancers(config)
  ).foldLeft(HttpService.empty)(_ orElse _.service)

  val resources = HttpService.lift {
    // We want to fall through if someone tries to list a directory
    case req if req.pathInfo endsWith "/" =>
      Task.now(Pass)
    case req =>
      resourceService(ResourceService.Config("/nelson/www"))(req)
  }

  def files(filePath: String) = HttpService.lift {
    // We want to fall through if someone tries to list a directory
    case req if req.pathInfo endsWith "/" =>
      Task.now(Pass)
    case req =>
      fileService(FileService.Config(filePath))(req)
  }

  /** Catch internal server errors, log them, and return a JSON response */
  def json500(service: HttpService): HttpService =
    // I envisioned a handleError, but resolving MonadError is one of the
    // hard problems of computer science.
    HttpService.lift { req =>
      service(req).handleWith {
        case e@(LoadError(_) | ProblematicRepoManifest(_)) =>
          log.info(s"unable to load repository YAML file ${e.getMessage}")
          UnprocessableEntity(Map(
            "message" -> s"Please ensure you have a valid .nelson.yml file in your repository. ${e.getMessage}"
          ).asJson)
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
          InternalServerError(jSingleObject("message", "An internal error occurred".asJson))
      }
    }

  def ui(config: NelsonConfig): HttpService =
    plans.UI(config).service

  def service(config: NelsonConfig): HttpService = {
    val allServices =
      if(config.ui.enabled)
        config.ui.filePath.map(p =>
          api(config) orElse files(p) orElse ui(config)).getOrElse(
            api(config) orElse resources orElse ui(config))
      else
        api(config)

    json500(ClientValidation.filterUserAgent(allServices)(config))
  }

  def start(config: NelsonConfig): Task[org.http4s.server.Server] = {
    BlazeBuilder
      .bindHttp(config.network.bindPort, config.network.bindHost)
      .mountService(service(config))
      .start
  }
}

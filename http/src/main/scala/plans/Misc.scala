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

import ManifestValidator.{ManifestValidation}
import ManifestValidator.Json._
import cleanup.ExpirationPolicy.Json._
import argonaut._
import Argonaut._
import argonaut.DecodeResultCats._

import org.http4s.{BuildInfo => _, _}
import org.http4s.dsl.io._
import org.http4s.argonaut._

import cats.data.Validated.{Invalid, Valid}
import cats.effect.IO
import cats.syntax.apply._

final case class Misc(config: NelsonConfig) extends Default {
  import Misc._
  import nelson.Json._
  import Json._

  /*
   * {
   *   "stacks_by_status": {
   *     "labels": ["ready", "failed", "terminated"],
   *     "data": [300, 500, 100]
   *   }
   * }
   */
  private implicit val RecentStatisticsEncoder: EncodeJson[Nelson.RecentStatistics] =
    EncodeJson { case (s: Nelson.RecentStatistics) =>
      (("stacks_by_status" :=
        ("labels" := s.statusCounts.map(_._1)) ->:
        ("data"   := s.statusCounts.map(_._2)) ->:
        jEmptyObject
      ) ->:
      ("most_deployed" :=
        ("labels" := s.mostDeployed.map(_._1)) ->:
        ("data"   := s.mostDeployed.map(_._2)) ->:
        jEmptyObject
      ) ->:
      ("least_deployed" :=
        ("labels" := s.leastDeployed.map(_._1)) ->:
        ("data"   := s.leastDeployed.map(_._2)) ->:
        jEmptyObject
      ) ->: jEmptyObject)
    }

  implicit val datacenterEncoder: EncodeJson[Datacenter] = EncodeJson { dc => dc.name.asJson }

  def handleLintRequest(str: String, units: List[ManifestValidator.NelsonUnit]): IO[Response[IO]] =
    ManifestValidator.validate(str, units).run(config).attempt.flatMap {
      case Left(errors) =>
        // server error, blame ourselves
        InternalServerError(errors.toString.asJson)
      case Right(Invalid(errors)) =>
        // validation error, blame the user
        BadRequest(errors.toList.map(_.getMessage).mkString("\n\n"))
      case Right(Valid(mf)) =>
        Ok()
    }

  val service: HttpService[IO] = HttpService[IO] {
    /*
     * POST /v1/profile/sync
     * Purpose of this resource is primarily to support the UI such that
     * it instructs Nelson to refresh its list of repositories from Github.
     */
    case POST -> Root / "v1" / "profile" / "sync" & IsAuthenticated(session) =>
      json(Nelson.syncRepos(session))

    /*
     * POST /v1/lint
     *
     * This resource expects the YAML of a .nelson.yml file to be posted,
     * to the service and we will check it for valid construction.
     *
     * The POST body should look something like:
     *
     * {{{
     * {
     *   "units": [
     *     {
     *       "kind": "nelson",
     *       "name": "nelson-0.4"
     *     }
     *   ],
     *   "manifest": "<base64 yaml>"
     * }
     * }}}
     *
     */
    case req @ POST -> Root / "v1" / "lint" =>
      decode[ManifestValidation](req){ mv => handleLintRequest(mv.config, mv.units) }

    /*
     * POST /v1/validate-template
     *
     * This resource expects a unit name and a template to be posted
     * We run it with a temporary vault policy and fake Nomad environment
     * variables created for the unit.
     *
     * The POST body should look something like this:
     *
     * {{{
     * {
     *   "unit": "howdy-http",
     *   "resources": ["s3"],
     *   "template": "<base64 encoded consul template>"
     * }
     * }}}
     *
     * If the template can be rendered, a 204 response is returned.  Output
     * is not returned to the user, so we don't expose secrets accessible
     * through the vault token.
     *
     * If the template can't be rendered, a 400 response is returned.  It
     * looks like this:
     *
     * {{{
     * {
     *   "details": "2017/02/06 20:31:57.832769 [INFO] consul-template v0.18.0 (5211c66)\n2017/02/06 20:31:57.832781 [INFO] (runner) creating new runner (dry: true, once: true)\n2017/02/06 20:31:57.832968 [INFO] (runner) creating watcher\n2017/02/06 20:31:57.837192 [INFO] (runner) starting\n2017/02/06 20:31:57.837213 [INFO] (runner) initiating run\nConsul Template returned errors:\n/consul-template/templates/nelson8021968182946245276.template: parse: template: :3: unterminated quoted string\n",
     *   "message": "template rendering failed"
     * }
     * }}}
     */
    case req @ POST -> Root / "v1" / "validate-template" & IsAuthenticated(_) => {
      import Templates._
      decode[TemplateValidation](req) { tv =>
        validateTemplate(tv).run(config).flatMap {
          case Rendered =>
            NoContent()
          case InvalidTemplate(errors) =>
            BadRequest {
              ("message" := "template rendering failed") ->:
              ("details" := errors) ->:
              jEmptyObject
            }
          case TemplateTimeout(errors) =>
            GatewayTimeout {
              ("message" := "template rendering timed out") ->:
              ("details" := errors) ->:
              jEmptyObject
            }
        }
      }
    }

    /*
     * GET /v1/cleanup-policies
     *
     * This resource return a list of cleanup policies with a descrption
     *
     * The response json should look something like:
     *
     * {{{
     * [
     *   {
     *    "policy": "retain-latest",
     *    "descrption": "retains the latest version"
     *   },
     *   {
     *    "policy": "retain-latest-two-major",
     *    "descrption": "retains the latest two major versions, i.e. 2.X.X and 1.X.X"
     *   },
     *   {
     *    "policy": "retain-latest-two-feature",
     *    "descrption": "retains the latest two feature versions, i.e. 2.3.X and 2.2.X"
     *   }
     * ]
     * }}}
     *
     */
    case GET -> Root / "v1" / "cleanup-policies" & IsAuthenticated(_) =>
      Ok(cleanup.ExpirationPolicy.policies.toList.asJson)

    /*
     * GET /v1/statistics
     */
    case GET -> Root / "v1" / "statistics" & IsAuthenticated(_) =>
      json(Nelson.recentActivityStatistics)

    /*
     * Get /v1/build-info
     */
    case GET -> Root / "v1" / "build-info" =>
      val buildInfo = BuildInfo.asJson
      val json = ("banner" := Banner.text) ->: buildInfo
      Ok(json)
  }
}

object Misc {
  implicit val codecTemplateValidation: DecodeJson[Templates.TemplateValidation] =
    DecodeJson { c =>
      ((c --\ "unit").as[UnitRef],
       (c --\ "resources").as[Set[String]],
       (c --\ "template").as[Base64].map(_.decoded)).mapN(Templates.TemplateValidation.apply)
    }

  implicit val encodeBuildInfo: EncodeJson[BuildInfo.type] =
    EncodeJson((x: BuildInfo.type) =>
      ("build_info" :=
        ("name" := x.name) ->:
        ("version" := x.version) ->:
        ("scala_version" := x.scalaVersion) ->:
        ("sbt_version" := x.sbtVersion) ->:
        ("git_revision" := x.gitRevision) ->:
        ("build_date" := x.buildDate) ->:
        jEmptyObject
      ) ->: jEmptyObject
    )

}

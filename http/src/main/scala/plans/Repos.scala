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
import org.http4s.argonaut._
import _root_.argonaut._, Argonaut._
import cats.effect.IO

final case class Repos(config: NelsonConfig) extends Default {
  import nelson.Json._

  object Owner extends QueryParamDecoderMatcher[String]("owner")

  object State extends QueryParamDecoderMatcher[String]("state")

  object Page extends QueryParamDecoderMatcher[Int]("page")

  object Limit extends QueryParamDecoderMatcher[Int]("limit")

  private implicit val ServiceNameEncoder: EncodeJson[Datacenter.ServiceName] =
    implicitly[EncodeJson[String]].contramap(_.toString)

  private implicit val DeploymentStatusEncoder: EncodeJson[DeploymentStatus] =
    implicitly[EncodeJson[String]].contramap(_.toString)

  /**
   * {
   *   "name": "howdy-http",
   *   "description": "...",
   *   "id": 6,
   *   "kind": "service",
   *   "dependencies": [],
   *   "namespace": "dev",
   *   "hash": "c0fgd1x",
   *   "timestamp": "2016-02-20T13:52:12.709Z",
   *   "status": "pending",
   *   "deployment_url": "http://.../v1/deployments/6fdsfse245"
   * }
   */
  private implicit val ReleasedDeploymentEncoder: EncodeJson[ReleasedDeployment] =
    EncodeJson { (d: ReleasedDeployment) =>
      ("id" := d.id) ->:
      ("unit_id" := d.unit.id) ->:
      ("name" := d.unit.name) ->:
      ("description" := d.unit.description) ->:
      ("dependencies" := d.unit.dependencies.toList) ->:
      ("hash" := d.hash) ->:
      ("timestamp" := d.timestamp.toString) ->:
      ("status" := d.state) ->:
      ("deployment_url" := linkTo(s"/v1/deployments/${d.guid}")(config.network)) ->:
      jEmptyObject
    }

  /**
   * {
   *   "timestamp": "2016-02-20T13:52:12.709Z",
   *   "release_url": "https://github.com/example/howdy/releases/tag/0.23.33",
   *   "slug": "example/howdy",
   *   "version": "0.23.33",
   *   "id": 287,
   *   "deloyments": [ ... ]
   * }
   */
  private implicit val ReleasedPairEncoder: EncodeJson[(Released,List[ReleasedDeployment])] =
    EncodeJson { (t: (Released,List[ReleasedDeployment])) =>
      ("id" := t._1.releaseId) ->:
      ("slug"    := t._1.slug.toString) ->:
      ("version" := t._1.version.toString) ->:
      ("timestamp" := t._1.timestamp.toString) ->:
      ("github_html_url" := t._1.releaseHtmlUrl) ->:
      ("release_url" := linkTo(s"/v1/releases/${t._1.releaseId.toString}")(config.network)) ->:
      ("deployments" := t._2) ->:
      jEmptyObject
    }

  val service = HttpService[IO] {
    //////////////////// LISTING ////////////////////

    // GET /v1/repos?owner=tim
    // GET /v1/repos?owner=stew
    case GET -> Root / "v1" / "repos" :? Owner(owner) & IsAuthenticated( session) =>
      json(Nelson.listRepositories(session, Option(owner)))

    // GET /v1/repos?state=active
    case GET -> Root / "v1" / "repos" :? State("active") & IsAuthenticated(session) =>
      json(Nelson.listRepositories(session, None))

    // lists repos for the currently logged in user
    case GET -> Root / "v1" / "repos" & IsAuthenticated(session) =>
      json(Nelson.listRepositories(session, Option(session.user.login)))

    //////////////////// WEBHOOKS ////////////////////

    // creates a web hook for the specified repo
    case POST -> Root / "v1" / "repos" / owner / repo / "hook" & IsAuthenticated(session) =>
      json(Nelson.createHook(session, Slug(owner,repo)))

    case DELETE -> Root / "v1" / "repos" / owner / repo / "hook" & IsAuthenticated(session) =>
      json(Nelson.deleteHook(session, Slug(owner,repo)))

    //////////////////// RELEASES ////////////////////

    case GET -> Root / "v1" / "repos" / owner / repo / "releases" & IsAuthenticated(session) =>
      json(Nelson.listRepositoryReleases(Slug(owner,repo)).map(_.toList))

    // GET /v1/releases?limit=30
    case GET -> Root / "v1" / "releases" & IsAuthenticated(session) =>
      json(Nelson.listReleases(None).map(_.toList))

    // GET /v1/releases/12345
    case GET -> Root / "v1" / "releases" / id & IsAuthenticated(session) =>
      jsonF(Nelson.getRelease(id.toLong).map(_.toList.headOption)){ option =>
        option match {
          case Some(dc) => Ok(dc.asJson)
          case None     => NotFound(s"the release with id '$id' was not found")
        }
      }
  }
}

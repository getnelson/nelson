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

import _root_.argonaut._, Argonaut._
import cats.effect.IO
import cats.implicits._
import org.http4s._
import org.http4s.dsl.io._
import _root_.argonaut.DecodeResultCats._

// import org.http4s.argonaut._

object Blueprints {
  import nelson.Json._

  /**
   *  {
   *    "hash": "xidfn4da"
   *    "name": "use-nvidia-1080ti",
   *    "description": "only scheudle on nodes with nvida 1080ti hardware"
   *    "content": "<base64 encoded template>"
   *  }
   */
  final case class BlueprintRequestJson(
    name: String,
    description: String,
    content: String)

  implicit val BlueprintRequestDecoder: DecodeJson[BlueprintRequestJson] =
    DecodeJson { c =>
      ((c --\ "name").as[String],
       (c --\ "description").as[String],
       (c --\ "content").as[Base64].map(_.decoded)
      ).mapN((x,y,z) => BlueprintRequestJson(x,y,z))
    }

  implicit val BlueprintRevisionEncoder: EncodeJson[Blueprint.Revision] =
    EncodeJson((b: Blueprint.Revision) =>
      b match {
        case Blueprint.Revision.HEAD => jString("HEAD")
        case Blueprint.Revision.Discrete(x) => jString(x.toString)
      }
    )

  implicit val BlueprintEncoder: EncodeJson[Blueprint] =
      EncodeJson((b: Blueprint) =>
        ("name" := b.name) ->:
        ("description" :=? b.description) ->?:
        ("revision" := b.revision) ->:
        ("state" := b.state.toString.toLowerCase) ->:
        ("sha256" := b.sha256) ->:
        ("created_at" := b.createdAt) ->:
        jEmptyObject
      )
}

final case class Blueprints(config: NelsonConfig) extends Default {
  import Blueprints._

  val service: HttpService[IO] = HttpService[IO] {
    /*
     * GET /v1/blueprints
     *
     * List all the available blueprints
     */
    case GET -> Root / "v1" / "blueprints" & IsAuthenticated(session) =>
      json(Nelson.listBlueprints)

    /*
     * GET /v1/blueprints/gpu-accelerated-job
     * GET /v1/blueprints/gpu-accelerated-job@HEAD
     * GET /v1/blueprints/gpu-accelerated-job@5
     *
     * List all the available blueprints
     */
    case GET -> Root / "v1" / "blueprints" / keyAndRevision & IsAuthenticated(session) =>
      Blueprint.parseNamedRevision(keyAndRevision) match {
        case Right((n,r)) => json(Nelson.fetchBlueprint(n,r))
        case Left(_) => BadRequest(s"Unable to parse the supplied '${keyAndRevision}' blueprint reference.")
      }

    /*
     * POST /v1/blueprints
     *
     * Create or revise a new blueprint. Here we're using POST constantly as blueprints
     * are entirely immutable and there's no in-place mutation, but rather, discrete versions
     * are revised and all revisions are stored in perpetuity.
     */
    case req @ POST -> Root / "v1" / "blueprints" & IsAuthenticated(session) if IsAuthorized(session) =>
      decode[BlueprintRequestJson](req){ ns =>
        Ok("not implemented yet")
      }
  }
}
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

import nelson.blueprint.Blueprint

import _root_.argonaut._, Argonaut._
import cats.effect.IO
import cats.implicits._
import org.http4s._
import org.http4s.dsl.io._
import _root_.argonaut.DecodeResultCats._
import org.apache.commons.codec.digest.DigestUtils

object Blueprints {
  import nelson.Json._

  final case class BlueprintRequestJson(
    name: String,
    description: Option[String],
    sha256: Sha256,
    template: String
  )

  implicit val BlueprintRequestDecoder: DecodeJson[BlueprintRequestJson] =
    DecodeJson { c =>
      ((c --\ "name").as[String],
       (c --\ "description").as[Option[String]],
       (c --\ "sha256").as[Sha256],
       (c --\ "template").as[Base64].map(_.decoded)
      ).mapN((w,x,y,z) => BlueprintRequestJson(w,x,y,z))
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
        ("template" := b.template.toString) ->:
        ("created_at" := b.createdAt) ->:
        jEmptyObject
      )

  implicit val BlueprintProofEncoder: CodecJson[BlueprintProof] = CodecJson(
    (r: BlueprintProof) => ("content" := Base64(r.content).asJson) ->: jEmptyObject,
    c => for {
      content <- (c --\ "content").as[Base64].map(_.decoded)
    } yield BlueprintProof(content)
  )
}

final case class BlueprintProof(
  content: String
)

final case class Blueprints(config: NelsonConfig) extends Default {
  import Blueprints._

  /* ensure that the the user-supplied template survived encode/decode */
  def hasIntegrity(suppliedSha256: Sha256, template: String): Boolean = {
    val computed = DigestUtils.sha256Hex(template)
    computed == suppliedSha256
  }

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
     * POST /v1/blueprints/proof
     *
     * Provide a blueprint template and have Nelson render it with example data
     * for development purposes. This makes itterating on a blueprint much more
     * seamless and does not require a full deployment cycle.
     *
     * {{{
     *  {
     *    "content": "<base64 encoded template>"
     *  }
     * }}}
     */
    case req @ POST -> Root / "v1" / "blueprints" / "proof" & IsAuthenticated(session) => {
      decode[BlueprintProof](req){ proof =>
        json(Nelson.proofBlueprint(proof.content).map(BlueprintProof(_)))
      }
    }

    /*
     * POST /v1/blueprints
     *
     * Create or revise a new blueprint. Here we're using POST constantly as blueprints
     * are entirely immutable and there's no in-place mutation, but rather, discrete versions
     * are revised and all revisions are stored in perpetuity.
     *
     * {{{
     *  {
     *    "name": "use-nvidia-1080ti",
     *    "description": "only scheudle on nodes with nvida 1080ti hardware"
     *    "sha256": "1e34a423ebe1fafeda8277386ede3263b01357e490b124b69bc0bfb493e64140"
     *    "template": "<base64 encoded template>"
     *  }
     * }}}
     */
    case req @ POST -> Root / "v1" / "blueprints" & IsAuthenticated(session) if IsAuthorized(session) =>
      decode[BlueprintRequestJson](req){ bpr =>
        if (hasIntegrity(bpr.sha256, bpr.template))
          json(Nelson.createBlueprint(bpr.name, bpr.description, bpr.sha256, bpr.template))
        else
          BadRequest(s"The supplied Sha256 for decoded template content did not match the computed Sha256.")
      }
  }
}

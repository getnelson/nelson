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

import cats.effect.IO
import org.http4s._
import org.http4s.dsl.io._

final case class Audit(config: NelsonConfig) extends Default {
  import Json._

  object Action extends OptionalQueryParamDecoderMatcher[String]("action")

  object Category extends OptionalQueryParamDecoderMatcher[String]("category")

  object Limit extends OptionalQueryParamDecoderMatcher[Long]("limit")

  object Offset extends OptionalQueryParamDecoderMatcher[Long]("offset")

  object RId extends OptionalQueryParamDecoderMatcher[Long]("release_id")


  val service: HttpService[IO] = HttpService[IO] {
    /*
     * GET /v1/audit
     * Returns the most recent audit events.
     */
    case GET -> Root / "v1" / "audit" :? RId(id) +& Limit(l) +& Offset(o) +& Action(a) +& Category(c) & IsAuthenticated(_) =>
      json(Nelson.listAuditEvents(l.getOrElse(10L), o.getOrElse(0L), id, a, c))
  }
}

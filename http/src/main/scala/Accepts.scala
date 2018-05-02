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

/** This should be added to http4s */
abstract class Accepts(mediaType: MediaType) {
  def unapply(req: Request[IO]): Boolean =
    req.headers.get(headers.Accept).fold(true)(
      _.values.toList.exists(_.mediaRange.satisfiedBy(mediaType)))
}

/** True if a request accepts svg+xml */
object AcceptsSvg extends Accepts(MediaType.`image/svg+xml`)

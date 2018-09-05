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

import _root_.argonaut._, Argonaut._
import java.nio.charset.StandardCharsets

final case class Base64(decoded: String) extends AnyVal

object Base64 {
  private val base64Decoder = java.util.Base64.getDecoder
  private val base64Encoder = java.util.Base64.getEncoder

  implicit val encodeBase64: EncodeJson[Base64] = EncodeJson(s =>
    jString(new String(base64Encoder.encode(s.decoded.getBytes("UTF-8")), StandardCharsets.UTF_8)))

  implicit val decodeBase64: DecodeJson[Base64] = DecodeJson.optionDecoder(
    _.string.flatMap(s =>
      DecodeJson.tryTo(Base64(new String(base64Decoder.decode(s), StandardCharsets.UTF_8)))),
    "base-64 encoded string")
}

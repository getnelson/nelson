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
package crypto
package protocol

import AuthFailure.UnsupportedTokenVersion

import scodec._, codecs._

object AuthCodecs {
  implicit val tokenVersion: Codec[TokenVersion] = (
    ("major" | uint8) ::
    ("minor" | uint8) ::
    ("micro" | uint8)
  ).as[TokenVersion]

  /**
   * Create a `Codec` that has a version-dependent decoder.
   *
   * @param currentVersion the current token version. This version number will
   *  be encoded and prepended to the serialized token.
   * @param f a partial function matching on the supported token versions. If an
   *  unsupported token version is encountered, it will trigger an appropriate
   *  `UnsupportedTokenVersion` decoding failure.
   */
  def versioned[A](currentVersion: TokenVersion)(f: PartialFunction[TokenVersion, Codec[A]]): Codec[A] =
      tokenVersion.consume[A](v =>
        f.applyOrElse(v, (v: TokenVersion) =>
          codecs.fail((AuthFailureErr(UnsupportedTokenVersion(v)))))
      // we always want to encode with the most recent version
      )(_ => currentVersion)
}

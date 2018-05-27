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

/**
 * A [[TokenAuthenticator]] can authenticate with a provided encoded token
 * and can serialize a decoded token.
 *
 * @tparam SerializedToken the form of the serialized token
 *  (for example String for tokens that are base-64-encoded).
 *
 *  @tparam DeserializedToken the result of successful authentication
 *    (for example an `AuthToken` or `User` model).
 */
trait TokenAuthenticator[SerializedToken, DeserializedToken] {
  def authenticate(token: SerializedToken): AuthResult[DeserializedToken]

  /**
   * Serialize a token.
   *
   * @return an error message (String) if serialization fails,
   *  or the serialized token if serialization succeeds.
   */
  def serialize(token: DeserializedToken): Either[String, SerializedToken]
}

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

import cats.implicits._

import scodec._
import scodec.bits.BitVector

/**
 * Token authenticator that uses Scodec encoding and then base-64 encodes the
 * result to a String token.
 *
 * @tparam A The result of successful authentication (ex: User, AccessToken)
 */
abstract class ScodecTokenAuthenticator[A] extends TokenAuthenticator[String, A] {
  import ScodecTokenAuthenticator._

  /**
   * A codec to encode/decode tokens
   */
  def codec: Codec[A]

  /**
   * Validate a result.
   *
   * This method is called at the end of the [[authenticate]] method to ensure
   * that any domain rules are satisfied. For example, this can be used to
   * verify that a token hasn't expired.
   *
   * @return an authentication failure on the left if there is an error,
   *  otherwise Unit on the right.
   */
  def validate(a: A): AuthResult[Unit]

  /**
   * Decode and validate a token.
   *
   * @param base64Token a base-64 encoded token, as returned by the
   * [[serialize]] method.
   */
  def authenticate(base64Token: String): AuthResult[A] =
    for {
      tokenBits <- bitVectorFromBase64(base64Token)
      token <- decode(tokenBits)
      _ <- validate(token)
    } yield token

  def decode(tokenBits: BitVector): AuthResult[A] =
    toAuthResult(codec.decode(tokenBits))

  /**
   * Serialize a token.
   *
   * @return an error message on the left if serialization fails, otherwise
   *  a base-64 endoded token on the right.
   */
  def serialize(token: A): Either[String, String] =
    codec.encode(token).fold(
      error => Left(error.message),
      bits => Right(bits.toBase64))
}

object ScodecTokenAuthenticator {

  def toAuthResult[A](attempt: Attempt[DecodeResult[A]]): AuthResult[A] =
    attempt.fold(
      error => Left(errToAuthFailure(error)),
      r => Right(r.value))

  def errToAuthFailure(err: scodec.Err): AuthFailure = err match {
    case AuthFailureErr(failure, _) => failure
    case e => AuthFailure.GeneralFailure(e.messageWithContext, None)
  }

  /**
   * Convert a base-64 String into a `BitVector` (if it's a valid base-64
   * String).
   *
   * Scodec has a built-in method to do this, but currently its performance is
   * pretty bad.
   * See {{https://github.com/scodec/scodec-bits/issues/32 the GitHub issue }}.
   */
  def bitVectorFromBase64(base64: String): AuthResult[BitVector] =
    Either.catchNonFatal(
      BitVector.view(java.util.Base64.getDecoder.decode(base64))
    ).leftMap(AuthFailure.Base64DecodeFailure.apply)
}

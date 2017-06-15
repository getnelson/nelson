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

import java.time.Instant

/**
 * An error encountered during authentication.
 */
sealed abstract class AuthFailure extends Product with Serializable {
  import AuthFailure._

  def isExpired: Boolean = this match {
    case Expired(_) => true
    case _ => false
  }
}

object AuthFailure {

  final case class SignatureMismatch(lengthOfExpected: Long, lengthOfActual: Long) extends AuthFailure
  final case class InsufficientSignatureLength(requiredLength: Long, actualLength: Long) extends AuthFailure
  final case class SigningError(keyLength: Long, dataLength: Long, signatureLength: Long, t: Throwable) extends AuthFailure
  final case class InsufficientSignatureKeyLength(actual: Long, required: Long) extends AuthFailure
  final case class EncryptionError(mode: CipherMode, keyLength: Long, dataLength: Long, t: Throwable) extends AuthFailure
  final case class InsufficientEncryptionKeyLength(actual: Long, required: Long) extends AuthFailure
  final case class Expired(expiry: Instant) extends AuthFailure
  final case class UnsupportedTokenVersion(version: TokenVersion) extends AuthFailure
  final case class Base64DecodeFailure(error: Throwable) extends AuthFailure
  final case class InvalidField(fieldName: String, msg: String) extends AuthFailure
  final case class GeneralFailure(msg: String, error: Option[Throwable]) extends AuthFailure
}

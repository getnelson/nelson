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

import cats.effect.IO
import scodec.bits.ByteVector

/**
 * An environment in which authentication is performed.
 *
 * It is expected that you will have a singleton instance of this class for
 * your entire app/service. The `default` method in the companion object
 * creates an instance with some reasonable defaults and caching.
 *
 * It is not strictly necessary to use this class, but it certain classes such
 * as `EncryptedCodec` and `SignedCodec` have convenient instantiation methods
 * that take an authentication environment.
 *
 * The environment can also be set up for asymmetric signing and verification.
 * To set it up for asymmetric signing, set the signing key. For asymmetric
 * verification, set the verify key. Both can be set if the library user needs
 * to perform both functions.
 *
 * @tparam F the context wrapping most of the results of the environment.
 *  For example, `F` is usually `AuthResult`, which means that an `F[A]`
 *  is either an `AuthFailure` or a successful `A`.
 */
sealed abstract class AuthEnvironment[F[_]] {
  def signer: Signer[F]

  def encryptor: Encryptor[F]

  def decryptor: Decryptor[F]

  def nextNonce: IO[Nonce]

  def encryptionKey: EncryptionKey

  def signingKey: SignatureKey
}

object AuthEnv {

  /**
   * An authentication environment that utilizes caching for
   * encryption/decryption keys, Mac instances, and Cipher instances.
   *
   * @param encryptKey a secret value that is used to encrypt secure values
   * @param signKeyBytes Private signing key bytes in its native DER encoding,
   *  wrapped around an Option (since it might not be provided).
   * @param verifyKeyBytes Public verification key bytes in its native DER encoding,
   *  wrapped around an Option (since it might not be provided).
   */
  def instance(
    encryptKey: ByteVector,
    getNextNonce: IO[Nonce]
  ): AuthEnv = new AuthEnv {
    val encryption = new SafeHolderEncryption(new SafeHolder)
    val signer = new SafeHolderHmac(new SafeHolder)
    val encryptor = encryption
    val decryptor = encryption
    val nextNonce = getNextNonce
    val encryptionKey = EncryptionKey.unsafe(encryptKey) // YOLO!
    val signingKey = SignatureKey.unsafe(encryptKey) // YOLO!
  }
}

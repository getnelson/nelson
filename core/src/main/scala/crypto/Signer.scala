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

import AuthFailure._
import scodec.bits.ByteVector
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

final class SignatureKey private(val bytes: ByteVector) {
  lazy val keySpec: java.security.Key =
    new SecretKeySpec(bytes.toArray, Hmac.algorithm)
}

object SignatureKey {
  val minBytes: Int = 8

  def apply(bytes: ByteVector): Either[InsufficientSignatureKeyLength, SignatureKey] =
    if (bytes.length >= minBytes) Right(new SignatureKey(bytes))
    else Left(InsufficientSignatureKeyLength(actual = bytes.length, required = minBytes.toLong))

  def unsafe(bytes: ByteVector): SignatureKey =
    apply(bytes).fold(e => throw new IllegalArgumentException(e.toString), identity)
}

object Hmac {
  val algorithm: String = "HmacSHA256"

  def mkHmac(): Mac = {
    // Note we want to spell out SunJCE here to prevent possible errors when apps
    // using a conflicting security provider like LunaHSM.
    val hmac = Mac.getInstance(algorithm, "SunJCE")
    hmac
  }
}

/**
 * A `Signer` computes a signature (such as a checksum) of data.
 *
 * @tparam F The context in which results are wrapped. This allows a Signer
 *  to return a possible failure via Option, a disjunction, etc.
 */
abstract class Signer[F[_]] {
  def signature(key: SignatureKey, data: ByteVector, signatureLengthBytes: Int): F[ByteVector]
}

/**
 * An HMAC-based implementation of [[Signer]] that caches `Mac` instances to
 * reduce the overhead of initialization.
 *
 * Caching a per-key `Mac` instead of just a thread-local `Mac` would remove
 * the need to initialize the mac on each signature, but benchmarks show that
 * it doesn't make a significant performance difference.
 */
final class SafeHolderHmac(holder: SafeHolder[Mac]) extends Signer[AuthResult] {

  def signature(key: SignatureKey, data: ByteVector, signatureLengthBytes: Int): AuthResult[ByteVector] = {
    try {
      val hmac = holder.getOrCreate(() => Hmac.mkHmac())
      hmac.init(key.keySpec)
      hmac.update(data.toArray)
      val sig = hmac.doFinal
      val actualLength = sig.size
      // this might be able to be slightly optimized
      if (actualLength >= signatureLengthBytes) Right(ByteVector.view(sig).take(signatureLengthBytes.toLong))
      else Left(InsufficientSignatureLength(signatureLengthBytes.toLong, actualLength.toLong))
    } catch {
      case e: Exception =>
        Left(SigningError(key.bytes.length, data.length, signatureLengthBytes.toLong, e))
    }
  }
}

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

import nelson.crypto.AuthFailure.SignatureMismatch
import scodec._
import scodec.bits.{BitVector, ByteVector}

/**
 * A codec that delegates through to the provided target codec but prefixes the
 * encoded value with an HMAC signature when encoding and verifies the
 * signature when decoding. This ensures that the encoded bits were not
 * tampered with.
 */
abstract class SignedCodec[A] extends Codec[A] {

  import SignedCodec._

  def signingKey: SignatureKey

  /** Returns the underlying codec that is to be used before the signature is applied */
  def targetCodec: Codec[A]

  /**
   * The codec to use for the signature portion of the encoded bits.
   */
  def signatureCodec: Codec[BitVector]

  def signature(bits: BitVector): Attempt[ByteVector]

  override def sizeBound: SizeBound = signatureCodec.sizeBound.atLeast

  final def encode(a: A): Attempt[BitVector] = {
    for {
      encodedTarget <- targetCodec.encode(a)
      signature <- signature(encodedTarget)
      sigBits <- signatureCodec.encode(signature.toBitVector)
    } yield sigBits ++ encodedTarget
  }

  final def decode(bits: BitVector): Attempt[DecodeResult[A]] = for {
    sigResult <- signatureCodec.decode(bits)
    expectedSignature <- signature(sigResult.remainder)
    result <- {
      val sigBytes = sigResult.value.toByteVector
      if (verifySignature(expectedSignature, sigBytes))
        targetCodec.decode(sigResult.remainder)
      else Attempt.failure(
        AuthFailureErr(SignatureMismatch(expectedSignature.length, sigBytes.size)))
    }
  } yield result

  override def toString: String = "SignedCodec"
}

object SignedCodec {
  val defaultSignatureLengthBytes: Int = 16
  val defaultSignatureLengthBits: Int = defaultSignatureLengthBytes * 8

  val defaultSignatureCodec: Codec[BitVector] = codecs.bits(defaultSignatureLengthBits.toLong)

  def signingKey(env: AuthEnv): SignatureKey =
    env.signingKey

  /**
   * The MessageDigest.isEqual is a constant time equal method to prevent
   * leaking signature size information.
   * It mitigates attacks like http://www.emerose.com/timing-attacks-explained
   */
  def verifySignature(expected: ByteVector, provided: ByteVector): Boolean =
    // this could probably be slightly optimized, but even replacing it with a
    // constant `true` makes a difference of less than 1% in benchmarks.
    java.security.MessageDigest.isEqual(expected.toArray, provided.toArray)

  def fromAuthEnv[A](env: AuthEnv)(codec: EncryptionKey => Codec[A]): Codec[A] =
    new SignedCodec[A] {
      def signingKey: SignatureKey = env.signingKey

      def targetCodec: Codec[A] = codec(env.encryptionKey)

      def signatureCodec: Codec[BitVector] = defaultSignatureCodec

      def signature(bits: BitVector): Attempt[ByteVector] =
        authResultToAttempt(
          env.signer.signature(signingKey, bits.toByteVector, defaultSignatureLengthBytes))
    }
}

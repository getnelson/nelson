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

import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import java.security.AlgorithmParameters
import javax.crypto.spec.IvParameterSpec
import scodec.bits.ByteVector

final class EncryptionKey private(val bytes: ByteVector) {
  val keySpec: java.security.Key =
    new SecretKeySpec(bytes.toArray, Encryption.keyAlgorithm)
}

object EncryptionKey {
  val minBytes: Int = 16

  def apply(bytes: ByteVector): Either[InsufficientEncryptionKeyLength, EncryptionKey] =
    if (bytes.length >= minBytes) Right(new EncryptionKey(bytes))
    else Left(InsufficientEncryptionKeyLength(actual = bytes.length, required = minBytes.toLong))

  def unsafe(bytes: ByteVector): EncryptionKey =
    apply(bytes).fold(e => throw new IllegalArgumentException(e.toString), identity)
}

final class InitializationVector private(val bytes: ByteVector)

object InitializationVector {
  val requiredBytes: Int = 16

  /**
   * Create an initialization vector instance.
   *
   * @param bytes the 16-byte vector to wrap. NOTE: this method is called
   *  `unsafe` because it will throw an exception if `bytes` is not exactly 16
   *  bytes.
   *
   *  @throws IllegalArgumentException if `bytes` is not the right length.
   */
  def unsafe(bytes: ByteVector): InitializationVector =
    if (bytes.length == requiredBytes) new InitializationVector(bytes)
    else throw new IllegalArgumentException(s"initialization vector must be $requiredBytes long, input vector is only ${bytes.length} bytes")
}

/**
 * Set of cipher functions for Web Service Key (WSK-based) authentication
 * exposed through instances of `Encryptor` and `Decryptor`
 *
 * CBC mode is used with a dynamic initialization vector.
 *
 * There is a large performance improvement with caching a cipher per-thread
 * and reusing it instead of creating new cipher instances.
 */
object Encryption {
  val aes: String = "AES"
  val aesCBC: String = "AES/CBC/PKCS5Padding"
  val keyAlgorithm: String = aes

  def mkCipher(): Cipher = {
    Cipher.getInstance(aesCBC, "SunJCE")
  }
}

sealed abstract class CipherMode(val asInt: Int)

object CipherMode {
  case object Encrypt extends CipherMode(Cipher.ENCRYPT_MODE)
  case object Decrypt extends CipherMode(Cipher.DECRYPT_MODE)
}

trait Encryptor[F[_]] {
  def encrypt(data: ByteVector, key: EncryptionKey, iv: InitializationVector): F[ByteVector]
}

trait Decryptor[F[_]] {
  def decrypt(data: ByteVector, key: EncryptionKey, iv: InitializationVector): F[ByteVector]
}

final class SafeHolderEncryption(holder: SafeHolder[Cipher]) extends Encryptor[AuthResult] with Decryptor[AuthResult] {

  def encrypt(data: ByteVector, key: EncryptionKey, iv: InitializationVector): AuthResult[ByteVector] =
    useCipher(data, key, iv, CipherMode.Encrypt)

  def decrypt(data: ByteVector, key: EncryptionKey, iv: InitializationVector): AuthResult[ByteVector] =
    useCipher(data, key, iv, CipherMode.Decrypt)

  def useCipher(data: ByteVector, key: EncryptionKey, iv: InitializationVector, mode: CipherMode): AuthResult[ByteVector] = {
    try {
      val cipher = holder.getOrCreate(() => Encryption.mkCipher)
      val algoParams = AlgorithmParameters.getInstance(Encryption.aes);
      algoParams.init(new IvParameterSpec(iv.bytes.toArray));
      cipher.init(mode.asInt, key.keySpec, algoParams)
      Right(ByteVector.view(cipher.doFinal(data.toArray)))
    } catch {
      case e: Exception =>
        Left(EncryptionError(mode, key.bytes.length, data.length, e))
    }
  }
}

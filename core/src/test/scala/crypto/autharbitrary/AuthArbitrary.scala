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
package autharbitrary

import ArbFunction0._

import cats.effect.IO
import scodec.bits.ByteVector
import org.scalacheck._
import Arbitrary.arbitrary

object AuthArbitrary {

  implicit def arbSafeHolder[V]: Arbitrary[SafeHolder[V]] = Arbitrary(
    Gen.delay(Gen.const(new SafeHolder[V])))

  def genByteVector(minLength: Int, maxLength: Int): Gen[ByteVector] = for {
    n <- Gen.chooseNum(minLength, maxLength)
    bytes <- Gen.listOfN(n, arbitrary[Byte])
  } yield ByteVector(bytes)

  implicit val arbEncryptionKey: Arbitrary[EncryptionKey] =
    Arbitrary(genByteVector(16, 16).map(EncryptionKey.unsafe))

  implicit val arbSigningKey: Arbitrary[SignatureKey] =
    Arbitrary(genByteVector(16, 16).map(SignatureKey.unsafe))

  def genAscii(minLength: Int, maxLength: Int): Gen[String] = for {
    n <- Gen.chooseNum(minLength, maxLength)
    c <- Gen.listOfN(n, Gen.choose[Char](0, 127))
  } yield c.mkString

  implicit val arbByteVector: Arbitrary[ByteVector] =
    Arbitrary(genByteVector(0, 100))

  implicit val arbInitializationVector: Arbitrary[InitializationVector] =
    Arbitrary(
      genByteVector(
        InitializationVector.requiredBytes,
        InitializationVector.requiredBytes
      ).map(InitializationVector.unsafe))

  implicit val arbAuthEnv: Arbitrary[AuthEnv] = Arbitrary(
    for {
      encKey <- arbitrary[EncryptionKey]
      signKey <- arbitrary[SignatureKey]
      randLong <- arbitrary[() => Long]
    } yield
      AuthEnv.instance(
        encryptKey = encKey.bytes,
        sigKey = signKey.bytes,
        getNextNonce = IO(Nonce.fromLongs(randLong(), randLong()))
      )
    )

  implicit val arbTokenVersion: Arbitrary[TokenVersion] = Arbitrary(for {
    a <- Gen.chooseNum(0, 255)
    b <- Gen.chooseNum(0, 255)
    c <- Gen.chooseNum(0, 255)
  } yield TokenVersion(a, b, c))
}

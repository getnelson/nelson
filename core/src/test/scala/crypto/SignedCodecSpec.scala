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

import autharbitrary.AuthArbitrary._

import org.scalatest.EitherValues
import org.scalacheck._, Arbitrary.arbitrary
import cats.implicits._
import scodec.bits.ByteVector

class SigningSpec extends AuthSpec with EitherValues {
  property("signature is consistent and can be verified"){
    forAll(arbitrary[ByteVector], arbitrary[AuthEnv], Gen.chooseNum(1, 32)) {
        (originalData, env, signatureLengthBytes) =>

      val result = for {
        signature1 <- env.signer.signature(env.signingKey, originalData, signatureLengthBytes)
        signature2 <- env.signer.signature(env.signingKey, originalData, signatureLengthBytes)
      } yield signature1 should ===(signature2)

      result.right.value
    }
  }

  property("signature is of requested length"){
    forAll(arbitrary[ByteVector], arbitrary[AuthEnv], Gen.chooseNum(1, 32)) {
        (originalData, env, signatureLengthBytes) =>

      val result = for {
        signature <- env.signer.signature(env.signingKey, originalData, signatureLengthBytes)
      } yield signature.length should ===(signatureLengthBytes.toLong)

      result.right.value
    }
  }

  property("signatures don't collide"){
    forAll(arbitrary[ByteVector], arbitrary[ByteVector], arbitrary[AuthEnv], Gen.chooseNum(16, 32)) {
        (data1, data2, env, signatureLengthBytes) =>

      whenever(data1 != data2){
        val result = for {
          signature1 <- env.signer.signature(env.signingKey, data1, signatureLengthBytes)
          signature2 <- env.signer.signature(env.signingKey, data2, signatureLengthBytes)
        } yield signature1 should !==(signature2)

        result.right.value
      }
    }
  }
}

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

import nelson.crypto.{EncryptionKey, SignatureKey}
import nelson.crypto.autharbitrary.AuthArbitrary._

import cats.implicits._

import org.scalacheck._, Prop._, Arbitrary.arbitrary

object SessionSpec extends Properties("session"){
  import Fixtures._
  import concurrent.duration._

  val genCfg: Gen[SecurityConfig] =
    for {
      ek <- arbitrary[EncryptionKey]
      sk <- arbitrary[SignatureKey]
    } yield SecurityConfig(
      encryptionKeyBase64 = java.util.Base64.getEncoder.encodeToString(ek.bytes.toArray),
      signingKeyBase64 = java.util.Base64.getEncoder.encodeToString(sk.bytes.toArray),
      expireLoginAfter = 1.hour,
      useEnvironmentSession = false
    )

  implicit lazy val arbCfg: Arbitrary[SecurityConfig] = Arbitrary(genCfg)

  implicit val securityShrink: Shrink[SecurityConfig] =
    Shrink.apply[SecurityConfig] { sc =>
      implicitly[Shrink[Tuple4[String, String, Long, Boolean]]]
        .shrink((sc.encryptionKeyBase64, sc.signingKeyBase64, sc.expireLoginAfter.toMillis, sc.useEnvironmentSession)).map{
          case (b,c,e,d) => SecurityConfig(b,c,e.millis,d)
        }
    }

  property("roundtrip") = forAll { (s: Session, sc: SecurityConfig) =>
    (for {
      enc <- sc.authenticator.serialize(s)
      dec <- sc.authenticator
               .authenticate(enc)
               .leftMap(_.toString)
    } yield s == dec).valueOr(_ => false)
  }
}

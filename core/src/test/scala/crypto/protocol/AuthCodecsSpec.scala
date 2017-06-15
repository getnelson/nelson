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

import autharbitrary.AuthArbitrary._
import AuthFailure.UnsupportedTokenVersion

import scodec._, codecs._

class AuthCodecsSpec extends AuthSpec {
  property("versioned handles supported version"){
    forAll { (i: Int, version: TokenVersion) =>
      val codec = AuthCodecs.versioned(version){
        case TokenVersion(version.major, version.minor, _) => int32
      }

      val result = for {
        encoded <- codec.encode(i)
        decoded <- codec.decode(encoded)
      } yield decoded.value

      result should ===(Attempt.successful(i))
    }
  }

  property("versioned provides error message for unsupported version"){
    forAll { (i: Int, version: TokenVersion) =>
      val supportedMinor = version.minor + 1
      val codec = AuthCodecs.versioned(version){
        case TokenVersion(version.major, `supportedMinor`, _) => int32
      }

      val result = for {
        encoded <- codec.encode(i)
        decoded <- codec.decode(encoded)
      } yield decoded.value

      result should ===(Attempt.failure(AuthFailureErr(UnsupportedTokenVersion(version))))
    }
  }
}

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

import org.scalacheck._, Prop._

class RepoSpec extends Properties("Repo") {
  import RepoSpec._

  property("RepoAccess/String round trip") = forAll { (r: RepoAccess) =>
    RepoAccess.fromString(r.toString) == Right(r)
  }

  property("fromString is case-insensitive") = forAll { (r: RepoAccess) =>
    RepoAccess.fromString(r.toString.toUpperCase) == Right(r)
  }

  property("fromString is an invalid repo access error for an unknown value") = {
    RepoAccess.fromString("foo") == Left(InvalidRepoAccess("foo"))
  }
}

object RepoSpec {
  implicit val arbRepoAccess: Arbitrary[RepoAccess] =
    Arbitrary(Gen.oneOf(RepoAccess.all.toSeq))
}

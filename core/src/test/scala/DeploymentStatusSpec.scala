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

class DeploymentStatusSpec extends Properties("DeploymentStatus") {
  import DeploymentStatusSpec._

  property("DeploymentStatus/String round trip") = forAll { (s: DeploymentStatus) =>
    DeploymentStatus.fromString(s.toString) == s
  }

  property("fromString is case-insensitive") = forAll { (s: DeploymentStatus) =>
    DeploymentStatus.fromString(s.toString.toUpperCase) == s
  }

  property("fromString is Unknown for an unknown value") = {
    DeploymentStatus.fromString("foo") == DeploymentStatus.Unknown
  }
}

object DeploymentStatusSpec {
  implicit val arbDeploymentStatus: Arbitrary[DeploymentStatus] =
    Arbitrary(Gen.oneOf(DeploymentStatus.all.toSeq))
}

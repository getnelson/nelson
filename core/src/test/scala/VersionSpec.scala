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

import org.scalacheck.Gen
import org.scalacheck.Gen.Choose
import org.scalatest.prop.Checkers

class VersionSpec extends NelsonSuite with Checkers {
  def nonNegInt(implicit c: Choose[Int]): Gen[Int] =
    Gen.sized(max => c.choose(0, max))

  behavior of "FeatureVersion"

  it should "parse a string representation with 1 period" in {
    val versionString = "1.2"
    FeatureVersion.fromString(versionString) shouldBe Some(FeatureVersion(1,2))
  }

  behavior of "Version"

  it should "parse a string representation with 2 periods" in {
    val versionString = "1.2.3"
    Version.fromString(versionString) shouldBe Some(Version(1,2,3))
  }

  behavior of "parsing a feature version from a string representation with 1 or 2 periods"

  it should "yield a feature version when given a representation with 2 periods" in {
    val versionString = "1.2.3"

    featureVersionFrom1or2DotString(versionString) shouldBe {
      Some(FeatureVersion(1,2))
    }
  }

  it should "yield a feature version when given a representation with 1 period" in {
    val versionString = "1.2"

    featureVersionFrom1or2DotString(versionString) shouldBe {
      Some(FeatureVersion(1,2))
    }
  }
}

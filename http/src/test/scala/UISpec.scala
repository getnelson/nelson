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

import org.scalatest._, Matchers._
import nelson.plans.UI

class UISpec extends FlatSpec {

  def loadSVGFixture(path: String): Either[Throwable, String] =
    (for {
      xml <- Util.loadResourceAsString(path)
      // _ = println(xml)
    } yield xml).attempt.unsafeRunSync()


  "svg badges" should "match the refrence approved fixtures" in {
    val results: List[Boolean] = DeploymentStatus.all.toList
      .map(s => s -> UI.badge(s)).map { case (status, xml) =>
        loadSVGFixture(s"/badges/${status.toString}.svg") match {
          case Right(x) => x.toString == UI.badge(status).toString
          case _ => false
        }
      }

    results should contain only (true)
  }
}

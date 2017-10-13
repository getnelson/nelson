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

import org.scalatest._
import Github.Interpreter.Step
import scalaz._
import org.scalacheck._
import org.scalatest.prop.PropertyChecks


class GithubSpec extends FlatSpec with Matchers with BeforeAndAfterEach with PropertyChecks {
  import GithubSpec._
  import GitFixtures._

  it should "return access token" in {
    val req = Github.Request.fetchAccessToken("code").runWith(interpreter).attemptRun 
    req should equal(\/-(token))
  }

  it should "return user" in {
    val req = Github.Request.fetchUser(token).runWith(interpreter).attemptRun 
    req should equal(\/-(user))
  }

  it should "return org keys" in {
    val req = Github.Request.fetchUserOrgKeys(token).runWith(interpreter).attemptRun 
    req should equal(\/-(keys))
  }

  it should "return organizations" in {
    val req = Github.Request.fetchOrganizations(Nil, token).runWith(interpreter).attemptRun 
    req should equal(\/-(orgs))
  }

  it should "return release assets" in {
    val req = Github.Request.fetchReleaseAssetContent(asset)(token).runWith(interpreter).attemptRun
    req should equal(\/-(asset))
  }

  it should "return release" in {
    val r = release("250")
    val req = Github.Request.fetchRelease(slug, "0")(token).runWith(interpreter).attemptRun
    req should equal(\/-(r))
  }

  it should "return repositories" in {
    val req = Github.Request.listUserRepositories(token).runWith(interpreter).attemptRun
    req should equal(\/-(repos))
  }

  it should "return file from repository" in {
     val req = Github.Request.fetchFileFromRepository(slug, "","")(token).runWith(interpreter).attemptRun 
     req should equal(contents.attemptRun)
  }

  it should "return webhook" in {
    val req = Github.Request.fetchRepoWebhooks(slug)(token).runWith(interpreter).attemptRun
    req should equal(\/-(List(webhook)))
  }

  it should "create webhook" in {
     val req = Github.Request.createRepoWebhook(slug, webhook)(token).runWith(interpreter).attemptRun 
     req should equal(\/-(webhook))
  }

  it should "delete webhook" in {
     val req = Github.Request.deleteRepoWebhook(slug, 0L)(token).runWith(interpreter).attemptRun 
     req should equal(\/-(()))
  }

  it should "return a Some in Step/String round trip" in {
    forAll { (step: Step) =>
      Step.fromString(step.toString) should equal (Some(step))
    }
  }

  it should "return a Some in Step/String round trip ignoring case" in {
    forAll { (step: Step) =>
      Step.fromString(step.toString.toUpperCase) should equal (Some(step))
    }
  }

  it should "return a None in Step.fromString with a bogus string" in {
    Step.fromString("foo") should equal (None)
  }
}

object GithubSpec {
  implicit val arbStep: Arbitrary[Step] =
    Arbitrary(Gen.oneOf(Step.all.toSeq))
}


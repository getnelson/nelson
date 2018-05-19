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
import org.scalacheck._
import org.scalatest.prop.PropertyChecks

class GithubSpec extends FlatSpec with Matchers with BeforeAndAfterEach with PropertyChecks {
  import GithubSpec._
  import GitFixtures._

  it should "return access token" in {
    val req = Github.Request.fetchAccessToken("code").foldMap(interpreter).attempt.unsafeRunSync()
    req should equal(Right(token))
  }

  it should "return user" in {
    val req = Github.Request.fetchUser(token).foldMap(interpreter).attempt.unsafeRunSync()
    req should equal(Right(user))
  }

  it should "return org keys" in {
    val req = Github.Request.fetchUserOrgKeys(token).foldMap(interpreter).attempt.unsafeRunSync()
    req should equal(Right(keys))
  }

  it should "return organizations" in {
    val req = Github.Request.fetchOrganizations(Nil, token).foldMap(interpreter).attempt.unsafeRunSync()
    req should equal(Right(orgs))
  }

  it should "return release assets" in {
    val req = Github.Request.fetchReleaseAssetContent(asset)(token).foldMap(interpreter).attempt.unsafeRunSync()
    req should equal(Right(asset))
  }

  it should "return release" in {
    val r = release(250L)
    val req = Github.Request.fetchRelease(slug, 0L)(token).foldMap(interpreter).attempt.unsafeRunSync()
    req should equal(Right(r))
  }

  it should "return repositories" in {
    val req = Github.Request.listUserRepositories(token).foldMap(interpreter).attempt.unsafeRunSync()
    req should equal(Right(repos))
  }

  it should "return file from repository" in {
     val req = Github.Request.fetchFileFromRepository(slug, "","")(token).foldMap(interpreter).attempt.unsafeRunSync() 
     req should equal(contents.attempt.unsafeRunSync())
  }

  it should "return webhook" in {
    val req = Github.Request.fetchRepoWebhooks(slug)(token).foldMap(interpreter).attempt.unsafeRunSync()
    req should equal(Right(List(webhook)))
  }

  it should "create webhook" in {
     val req = Github.Request.createRepoWebhook(slug, webhook)(token).foldMap(interpreter).attempt.unsafeRunSync() 
     req should equal(Right(webhook))
  }

  it should "delete webhook" in {
     val req = Github.Request.deleteRepoWebhook(slug, 0L)(token).foldMap(interpreter).attempt.unsafeRunSync() 
     req should equal(Right(()))
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


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

class JsonSpec extends FlatSpec with Matchers {
  import Util._
  import Json._
  import argonaut._

  it should "parse the github release defined in the docs" in {
    val out = for {
      a <- loadResourceAsString("/nelson/github.release.json").attempt.unsafeRunSync()
      b <- Parse.decodeEither[Github.Release](a)
    } yield b

    out.isRight should equal (true)
  }

  private def fromSample[A : DecodeJson](path: String)(f: A => Boolean) =
    (for {
      a <- loadResourceAsString(path).attempt.unsafeRunSync()
      b <- Parse.decodeEither[A](a)
    } yield f(b)) should equal (Right(true))

  it should "parse the arbitrary events from Github" in {
    fromSample[Github.Event]("/nelson/github.webhookping.json"){
      case Github.PingEvent(_) => true
      case _ => false
    }
  }

  it should "parse the deployment response from Github" in {
    fromSample[Github.Event]("/nelson/github.webhookdeployment.json"){
      case Github.DeploymentEvent(_,_,_) => true
      case _ => false
    }
  }

  it should "parse the deployment events from Github" in {
    fromSample[Github.Deployment]("/nelson/github.deployment.json"){
      case Github.Deployment(_,_,_,_,_) => true
      case _ => false
    }
  }

  it should "parse the release events from Github" in {
    fromSample[Github.Event]("/nelson/github.webhookrelease.json"){
      case Github.ReleaseEvent(_,_,_) => true
      case _ => false
    }
  }

  it should "parse the pull request events from Github" in {
    fromSample[Github.Event]("/nelson/github.webhookpullrequest.json"){
      case Github.PullRequestEvent(_,_,_) => true
      case _ => false
    }
  }

}

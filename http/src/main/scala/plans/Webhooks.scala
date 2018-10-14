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
package plans

import org.http4s._
import org.http4s.dsl.io._
import _root_.argonaut._, Argonaut._
import cats.effect.IO

final case class WebHooks(config: NelsonConfig) extends Default {
  import nelson.Json.GithubEventDecoder

  /**
   * This function has to dispatch whatever type of Github.Event
   * we recieve on the wire and dispatch it to the correct
   * nelson function. This is a little hacky, but its the only
   * way to handle the arbitrary message shapes that Github sends.
   */
  val service: HttpService[IO] = HttpService[IO] {
    case req @ POST -> Root / "listener" => {
      decode[Github.Event](req){
        case Github.PingEvent(_) =>
          log.info("received ping event from github, looks good!")
          Ok()
        case d@Github.DeploymentEvent(_,_,_,_,_,_,_) =>
          log.info(s"received deployment event from github: $d")
          json(Nelson.handleDeployment(d))
        case r@Github.PullRequestEvent(_,_,_) =>
          log.info(s"received pull request event from github: $r")
          Ok()
        case r@Github.ReleaseEvent(_,_,_) =>
          log.info(s"received release event from github: $r")
          Ok()
      }
    }
  }
}

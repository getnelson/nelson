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
  import cats.implicits._

  it should "parse the github release defined in the docs" in {
    val out = for {
      a <- loadResourceAsString("/nelson/github.release.json").attempt.unsafeRunSync()
      b <- Parse.decodeEither[Github.Release](a)
    } yield b

    out.isRight should equal (true)
  }

  it should "parse the arbitrary events from Github" in {
    (for {
      a <- loadResourceAsString("/nelson/github.webhookping.json").attempt.unsafeRunSync()
      b <- Parse.decodeEither[Github.Event](a)
    } yield b match {
      case Github.PingEvent(_)              => true
      case Github.ReleaseEvent(_,_,_) => false
    }) should equal (Right(true))
  }

}

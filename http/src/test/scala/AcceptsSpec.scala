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

import cats.effect.IO
import org.http4s._
import org.http4s.headers._

class AcceptsSpec extends NelsonSuite {
  "Accepts.unapply" should "match requests with no Accept header" in {
    val req = Request[IO]()
    AcceptsSvg.unapply(req) should equal (true)
  }

  it should "match requests with an Accept header of the media type" in {
    val req = Request[IO]().putHeaders(Accept(MediaType.`image/svg+xml`))
    AcceptsSvg.unapply(req) should equal (true)
  }

  it should "match requests with an Accept header of the media range" in {
    val req = Request[IO]().putHeaders(Accept(MediaRange.`image/*`))
    AcceptsSvg.unapply(req) should equal (true)
  }

  it should "not match requests without an Accept header" in {
    val req = Request[IO]().putHeaders(Accept(MediaType.`image/png`))
    AcceptsSvg.unapply(req) should equal (false)
  }

  it should "not match requests that declare the media type unacceptable" in {
    val req = Request[IO]().putHeaders(Accept(MediaType.`image/png`.withQValue(QValue.Zero)))
    AcceptsSvg.unapply(req) should equal (false)
  }
}


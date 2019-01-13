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

import org.http4s.Uri._
import org.scalatest._
import scala.concurrent.duration.DurationInt

class Http4sConsulSpec extends FlatSpec with Matchers with BeforeAndAfterEach {
  import Http4sConsul._

  val consul = Infrastructure.Consul(
    new java.net.URI("http://consule.example.com:8500"),
    1.second,
    Option("someacltoken"),
    None)

  it should "handle http port 80" in {
    val consul0 = consul.copy(endpoint = new java.net.URI("http://consule.example.com"))
    baseUri(consul0) should equal (uri("http://consule.example.com:80"))
  }

  it should "handle https port 443" in {
    val consul0 = consul.copy(endpoint = new java.net.URI("https://consule.example.com"))
    baseUri(consul0) should equal (uri("https://consule.example.com:443"))
  }

  it should "support https URIs on non-standard ports" in {
    val consul0 = consul.copy(endpoint = new java.net.URI("http://consule.example.com"))
    baseUri(consul0) should equal (uri("http://consule.example.com:80"))
  }

  it should "respect provided ports" in {
    baseUri(consul) should equal (uri("http://consule.example.com:8500"))
  }

  it should "handle provided ACL tokens" in {
    token(consul) should equal (Some("someacltoken"))
  }

  it should "handle missing ACL tokens" in {
    val consul0 = consul.copy(aclToken = Option(""))
    token(consul0) should equal (None)
  }

  it should "handle missing creds" in {
     creds(consul) should equal (None)
   }

  it should "handle provided creds" in {
    val consul0 = consul.copy(creds = Some(Infrastructure.Credentials("admin", "p4ssw0rd")))
    creds(consul0) should equal (Some("admin" -> "p4ssw0rd"))
  }
}

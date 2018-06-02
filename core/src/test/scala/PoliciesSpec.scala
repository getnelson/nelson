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

import org.scalatest.{Matchers,FlatSpec,Inspectors}
import policies._
import nelson.test._
import vault._

import cats.effect.IO

class PoliciesSpec extends FlatSpec with Matchers with Inspectors with RoutingFixtures {
  val nsRef = "qa"
  val ns = NamespaceName(nsRef)
  val sn = Datacenter.StackName("howdy-http", Version(2, 0, 38), "abcdef12")

  val I = Interpreter.prepare[Vault, IO]
  "createPolicy" should "create the policy" in {
    val interp = for {
      _ <- I.expectU[Unit] {
        case cp: Vault.CreatePolicy =>
          cp should equal (Vault.CreatePolicy(
            "nelson__qa__howdy-http--2-0-38--abcdef12",
            List(
              Rule("sys/*", Nil, Some("deny")),
              Rule("auth/token/revoke-self", Nil, Some("write")),
              Rule("pki/qa/issue/*", List("create", "update"), None),
              Rule("test/qa/mysql-foo/creds/howdy-http", List("read"), None),
              Rule("test/qa/cassandra-bar/creds/howdy-http", List("read"), None),
              Rule("test/qa/s3/creds/howdy-http", List("read"), None),
              Rule("test/qa/testrail/creds/howdy-http", List("read"), None)
            )
          ))
          IO.unit
      }
    } yield ()
    interp.run(createPolicy(
      testPolicyConfig,
      sn = sn,
      ns = ns,
      resources = Set("mysql-foo", "cassandra-bar", "s3", "testrail"))).unsafeRunSync()
  }

  "resourceRule" should "always use root namespace in path" in {
    val r = policies.resourceRule("testroot/%env%/%resource%/creds/%unit%", sn, NamespaceName("dev", List("sanbox")), "s3")
    r.path should equal("testroot/dev/s3/creds/howdy-http")
  }

  "pikRule" should "always use root namespace in path" in {
    val r = policies.pkiRule("certs/%env%", NamespaceName("dev", List("sandbox")))
    r.path should equal("certs/dev/issue/*")
  }
}


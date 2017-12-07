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

import scalaz.~>
import scalaz.Id
import scalaz.std.list._
import helm.ConsulOp

class ConsulDiscoverySpec extends NelsonSuite {
  import Domain._
  import routing._
  import routing.RoutingTable._

  override def beforeAll(): Unit = {
    super.beforeAll()
    nelson.storage.run(config.storage, insertFixtures(testName)).run
    ()
  }

  def consulOps: List[ConsulOp.ConsulOpF[Unit]] = {
    val rts: List[(Namespace, RoutingGraph)] =
      nelson.storage.run(config.storage, generateRoutingTables("ConsulDiscoverySpec")).run
    val dts = Discovery.discoveryTables(rts)

    dts.toList.map {
      case ((sn, nsname), dt) =>
        Discovery.writeDiscoveryInfoToConsul(nsname, sn, "service.example.com", dt)
    }
  }

  "Discovery" should "create a table for each stack" in {
    var stacks: Set[String] = Set.empty
    var gets = false

    val interp = new ~>[ConsulOp,Id.Id] {
      def apply[A](f: ConsulOp[A]): Id.Id[A] = f match {
        case ConsulOp.Get(key) => gets = true
          Some(key)

        case ConsulOp.Set(key,_) =>
          stacks = stacks + key
          ()

        case ConsulOp.ListKeys(prefix) =>
          Set.empty

        case ConsulOp.Delete(key) => ()
          stacks = stacks - key
          ()

        case ConsulOp.HealthCheck(service) =>
          ""
      }
    }

    consulOps.foreach(helm.run(interp, _))

    val expected =
      Set("conductor--1-1-1--abcd",
          "ab--2-2-2--abcd",
          "ab--2-2-1--abcd",
          "inventory--1-2-2--ffff",
          "inventory--1-2-3--ffff",
          "search--2-2-2--aaaa",
          "search--2-2-2--bbbb",
          "search--1-1-0--foo",
          "db--1-2-3--aaaa",
          "job--3-0-0--zzzz4",
          "job--3-1-0--zzzz",
          "job--3-1-1--zzzz1",
          "job--4-1-0--zzzz2",
          "crawler--5-1-0--zzzz3",
          "foo--1-10-100--aaaa",
          "foo--2-0-0--bbbb",
          "foo--2-0-0--bbbb",
          "service-a--6-0-0--aaaa",
          "service-b--6-1-0--aaaa",
          "service-c--6-2-0--aaaa",
          "service-c--6-2-1--bbbb",
          "lb--1-0-0--hash"
        ).map("lighthouse/discovery/v1/" + _)

    stacks should be(expected)
    gets should be(false)
  }
}

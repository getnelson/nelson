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
import scala.collection.immutable.SortedSet
import quiver._
import routing._
import Datacenter._
import Manifest.{Route,BackendDestination}
import scala.concurrent.duration._

class LoadbalancerSpec extends FlatSpec with Matchers with RoutingFixtures {

  private def NOW = java.time.Instant.now

  val dc = datacenter("name")

  private def makeDeployment(id: ID, name: String, version: Version, ps: Set[Port]) = {
    val namespace = Datacenter.Namespace(1L, NamespaceName("devel"), "dc")
    val dc = DCUnit(id,name,version,"",Set.empty,Set.empty,ps)
    Deployment(id,dc,"foo",namespace,NOW,"quasar","plan","guid","retain-latest")
  }

  private def makeLoadbalancer(id: ID, name: String, version: MajorVersion, routes: Vector[Manifest.Route]) =
    LoadbalancerDeployment(id, 0L, "hash", DCLoadbalancer(id, name, version, routes), NOW, "guid", "dns")

  private def makeRoute(ref: String, port: Int, d: Deployment, portName: String): Route = {
    Route(Manifest.Port(ref,port,""),
      BackendDestination(d.unit.name, portName))
  }
}

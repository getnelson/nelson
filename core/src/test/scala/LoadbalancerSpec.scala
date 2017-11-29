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
import quiver._
import routing._
import Datacenter._
import Manifest.{Route,BackendDestination}
import loadbalancers.Inbound

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

  val emptyG = quiver.empty[RoutingNode,Unit,RoutePath]

  it should "generate v1 loadbalancer config" in {
    val ports = Set(Port(1, "one", ""), Port(2, "two", ""), Port(3, "three", ""))
    val d = makeDeployment(0L, "foo", Version(0,0,1), ports)
    val l = makeLoadbalancer(0L, "lb", MajorVersion(0),
      Vector(makeRoute("front-one",81,d,"one"), makeRoute("front-two",82,d,"two")))

    val g = emptyG &
      Context(Vector(), RoutingNode(l), (), Vector((RoutePath(d,"one","http",9000,0), RoutingNode(l)),(RoutePath(d,"two","http",9001,0), RoutingNode(l)))) &
      Context(Vector((RoutePath(d,"one","http",9000,0), RoutingNode(l)),(RoutePath(d,"two","http",9001,0), RoutingNode(l))), RoutingNode(d), (), Vector())

    val config = loadbalancers.loadbalancerV1Configs(g)
    config.head._2 should contain(Inbound(d.stackName, "one", 81))
    config.head._2 should contain(Inbound(d.stackName, "two", 82))
    config.head._2.map(_.label) should not contain("three")
  }
}

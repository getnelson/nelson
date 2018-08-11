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

import cats.instances.list._

class DiscoveryTableSpec extends NelsonSuite {
  import Datacenter._
  import routing._
  import routing.RoutingTable._

  override def beforeAll(): Unit = {
    super.beforeAll()
    insertFixtures(testName).foldMap(config.storage).unsafeRunSync()
    ()
  }

  "discoveryTables" should "be built from database" in {
    val rts: List[(Namespace,RoutingGraph)] =
      generateRoutingTables("DiscoveryTableSpec").foldMap(config.storage).unsafeRunSync()
    var conductorTable: Option[DiscoveryTables] = None
    var serviceBTable: Option[DiscoveryTables] = None
    var serviceCTable: Option[DiscoveryTables] = None
    var lbTable: Option[DiscoveryTables] = None
    val dts = Discovery.discoveryTables(rts)
    dts.toList.foreach {
      case ((sn,y),z) =>
        if(sn.serviceType == "conductor")
          conductorTable = Some(z)
        else if (sn.serviceType == "service-b")
         serviceBTable = Some(z)
        else if (sn == StackName("service-c", Version(6,2,1), "bbbb"))
         serviceCTable = Some(z)
        else if (sn.serviceType == "lb")
          lbTable = Some(z)
    }

    val dt: DiscoveryTable = conductorTable.get.get(NamespaceName("dev")).get
    val sn = dt.get(NamedService("ab", "default")).get.map(_.stack.stackName.toString).head
    val pn = dt.get(NamedService("ab", "default")).get.map(_.portName).head
    val po = dt.get(NamedService("ab", "default")).get.map(_.port).head
    val we = dt.get(NamedService("ab", "default")).get.map(_.weight).head

    val sandbox: DiscoveryTable = serviceBTable.get.get(NamespaceName("dev", List("sandbox"))).get
    val sandboxRodrigo: DiscoveryTable = serviceBTable.get.get(NamespaceName("dev", List("sandbox", "rodrigo"))).get
    val rodrigo: DiscoveryTable = serviceCTable.get.get(NamespaceName("dev")).get // only routes to something 'devel' namespace
    val sn2 = sandbox.get(NamedService("service-c", "default")).get.map(_.stack.stackName.toString).head
    val sn3 = sandboxRodrigo.get(NamedService("service-c", "default")).get.map(_.stack.stackName.toString).head
    val sn4 = rodrigo.get(NamedService("foo", "default")).get.map(_.stack.stackName.toString).head

    val lb = lbTable.get.get(NamespaceName("dev")).get
    val ma = lb.get(NamedService("conductor", "default")).get.map(_.stack.stackName.toString).head

    sn should be("ab--2-2-2--abcd")
    pn should be("default")
    po should be(1)
    we should be(100)
    sn2 should be("service-c--6-2-0--aaaa") // in devel/sandbox
    sn3 should be("service-c--6-2-1--bbbb") // in devel/sanbox/rodrigo
    sn4 should be("foo--1-10-100--aaaa")    // in devel
    ma should be("conductor--1-1-1--abcd")
  }

  it should "use fully qualified namespace paths" in {
    val rts: List[(Namespace,RoutingGraph)] =
      generateRoutingTables("DiscoveryTableSpec").foldMap(config.storage).unsafeRunSync()
    val dts = Discovery.discoveryTables(rts)
    val ns = dts.toList.map { case ((deployment,y),z) => y }
    ns.map(_.asString).toSet should be(Set("dev", "dev/sandbox", "dev/sandbox/rodrigo"))
  }

  "discoveryTables" should "include traffic shifts" in {
    val rts: List[(Namespace, RoutingGraph)] =
      generateRoutingTables("DiscoveryTableSpec").foldMap(config.storage).unsafeRunSync()
    var suTable: Option[DiscoveryTables] = None
    val dts = Discovery.discoveryTables(rts)
    dts.toList.foreach {
      case ((sn,y),z) =>
        if(sn.serviceType == "ab")
          suTable = Some(z)
    }

    val dt: DiscoveryTable = suTable.get.get(NamespaceName("dev")).get
    val sn = dt.get(NamedService("inventory", "default")).get.map(_.stack.stackName.toString)
    val po = dt.get(NamedService("inventory", "default")).get.map(_.port)
    val we = dt.get(NamedService("inventory", "default")).get.map(_.weight).toList.sum

    sn.toList.toSet should be(Set("inventory--1-2-2--ffff", "inventory--1-2-3--ffff"))
    we should be(100)
  }
}

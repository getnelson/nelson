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

import doobie.imports._
import scalaz.syntax.functor._
import org.scalatest.BeforeAndAfterEach
import storage.{run => runs, StoreOp}
import java.time.Instant

class RoutingTableSpec extends NelsonSuite with BeforeAndAfterEach {
  import Domain._
  import routing._
  import routing.RoutingTable._

  override def beforeAll(): Unit = {
    super.beforeAll()
    nelson.storage.run(config.storage, insertFixtures(testName)).run
    ()
  }

  override def beforeEach: Unit =
    sql"TRUNCATE TABLE traffic_shift_reverse".update.run.void.transact(stg.xa).run

  "routingTables" should "be built from database" in {
    var routingTableSize: Int = 0
    var conductor_ab: String = ""
    var conductor_search: String = ""
    var conductor_foo: String = ""
    var proxy_conductor: String = ""
    var ab_inventory_names: List[String] = Nil
    var ab_inventory_weights: List[Int] = Nil

    var serviceA_serviceB: String = ""
    var serviceB_serviceC: String = ""
    var serviceB_serviceC2: String = ""
    var serviceC_foo: String = ""
    var serviceC2_foo: String = ""
    var dev: Set[String] = Set()
    var devSandbox: Set[String] = Set()
    var devSandboxRodrigo: Set[String] = Set()

    try {
      val routingTables: List[(Namespace, RoutingGraph)] =
        runs(config.storage, generateRoutingTables("RoutingTableSpec")).run

      routingTableSize = routingTables.size

      val graph = routingTables(0)._2

      // dev/sandbox
      val graph2 = routingTables(1)._2

      // dev/sandbox/rodrigo
      val graph3 = routingTables(2)._2

      val conductor = runs(config.storage, StoreOp.findDeployment(StackName("conductor", Version(1,1,1), "abcd"))).run.get
      val ab = runs(config.storage, StoreOp.findDeployment(StackName("ab", Version(2,2,2), "abcd"))).run.get

      val ns = runs(config.storage, StoreOp.getNamespace(testName, NamespaceName("dev"))).run.get
      val lb = runs(config.storage, StoreOp.findLoadbalancerDeployment("lb", MajorVersion(1), ns.id)).run.get

      val serviceA = runs(config.storage, StoreOp.findDeployment(StackName("service-a", Version(6,0,0), "aaaa"))).run.get

      val serviceB = runs(config.storage, StoreOp.findDeployment(StackName("service-b", Version(6,1,0), "aaaa"))).run.get

      val serviceC = runs(config.storage, StoreOp.findDeployment(StackName("service-c", Version(6,2,0), "aaaa"))).run.get

      val serviceC2 = runs(config.storage, StoreOp.findDeployment(StackName("service-c", Version(6,2,1), "bbbb"))).run.get

      conductor_ab = graph.decomp(RoutingNode(conductor)).ctx.get.outAdj
        .find(_._2.deployment.exists(_.unit.name === "ab")).get._1.stack.stackName.toString

      conductor_search = graph.decomp(RoutingNode(conductor)).ctx.get.outAdj
        .find(_._2.deployment.exists(_.unit.name === "search")).get._1.stack.stackName.toString

      conductor_foo = graph.decomp(RoutingNode(conductor)).ctx.get.outAdj
        .find(_._2.deployment.exists(_.unit.name === "foo")).get._1.stack.stackName.toString

      proxy_conductor = graph.decomp(RoutingNode(lb)).ctx.get.outAdj
        .find(_._2.deployment.exists(_.unit.name === "conductor")).get._1.stack.stackName.toString

      val ab_inventory_c = graph.decomp(RoutingNode(ab)).ctx.get.outAdj
        .filter(_._2.deployment.exists(_.unit.name === "inventory"))

      ab_inventory_names = ab_inventory_c.map(_._1.stack.stackName.toString).toList
      ab_inventory_weights = ab_inventory_c.map(_._1.weight).toList

      serviceA_serviceB = graph.decomp(RoutingNode(serviceA)).ctx.get.outAdj
        .find(_._2.deployment.exists(_.unit.name === "service-b")).get._1.stack.stackName.toString

      serviceB_serviceC = graph2.decomp(RoutingNode(serviceB)).ctx.get.outAdj
        .find(_._2.deployment.exists(_.stackName.toString === "service-c--6-2-0--aaaa")).get._1.stack.stackName.toString

      serviceB_serviceC2 = graph2.decomp(RoutingNode(serviceB)).ctx.get.outAdj
        .find(_._2.deployment.exists(_.stackName.toString === "service-c--6-2-1--bbbb")).get._1.stack.stackName.toString

      // service-c is in the sandbox namespace, foo is in the devel namespace.
      // should still be able to resolve dependency because sandbox is a subnordinate namespace to devel
      serviceC_foo = graph2.decomp(RoutingNode(serviceC)).ctx.get.outAdj
        .find(_._2.deployment.exists(_.unit.name === "foo")).get._1.stack.stackName.toString

      serviceC2_foo = graph3.decomp(RoutingNode(serviceC2)).ctx.get.outAdj
        .find(_._2.deployment.exists(_.unit.name === "foo")).get._1.stack.stackName.toString

      dev = graph.nodes.map(_.stackName.toString).toSet
      devSandbox = graph2.nodes.map(_.stackName.toString).toSet
      devSandboxRodrigo = graph3.nodes.map(_.stackName.toString).toSet
    } catch {
      case e: Throwable =>
        println(e.getMessage)
        e.printStackTrace
        routingTableSize = -1
    }
    routingTableSize should be (3)
    conductor_search should be ("search--2-2-2--aaaa")
    conductor_ab should be ("ab--2-2-2--abcd")
    conductor_foo should be ("foo--1-10-100--aaaa")
    proxy_conductor should be ("conductor--1-1-1--abcd")
    serviceA_serviceB should be ("service-b--6-1-0--aaaa")
    serviceB_serviceC should be ("service-c--6-2-0--aaaa")
    serviceB_serviceC2 should be ("service-c--6-2-1--bbbb")
    serviceC_foo should be ("foo--1-10-100--aaaa")
    serviceC2_foo should be ("foo--1-10-100--aaaa")

    // inventory currently has a traffic shift in progress
    ab_inventory_names should contain theSameElementsAs List("inventory--1-2-2--ffff","inventory--1-2-3--ffff")
    ab_inventory_weights.fold(0)((a,b) => a + b) should be (100)

    devSandbox should equal (Set("service-a--6-0-0--aaaa", "service-c--6-2-0--aaaa", "service-b--6-1-0--aaaa", "foo--1-10-100--aaaa", "service-c--6-2-1--bbbb"))
    devSandboxRodrigo should equal (Set("service-b--6-1-0--aaaa", "service-c--6-2-1--bbbb", "foo--1-10-100--aaaa"))
    dev should equal (Set(
      "lb--1-0-0--hash", // loadbalancers are in the graph
      "conductor--1-1-1--abcd",
      "ab--2-2-2--abcd",
      "ab--2-2-1--abcd", // no incomming or outgoing links but in the graph because it's in ready state
      "inventory--1-2-2--ffff", // bleed @ 95%
      "inventory--1-2-3--ffff", // bleed @  5%
      "foo--2-0-0--bbbb",
      "foo--1-10-100--aaaa",
      "search--1-1-0--foo", // deprecated but should still be in the graph
      "search--2-2-2--aaaa", // newer singleton
      "search--2-2-2--bbbb", // no incomming or outgoing links but in the graph because it's in ready state
      "db--1-2-3--aaaa", // manual deploy
      "service-a--6-0-0--aaaa",
      "service-b--6-1-0--aaaa", // dev/sandbox but in the graph because service-a depends on it
      "service-c--6-2-1--bbbb", // dev/sandbox/rodrigo but in the graph because it calls foo
      "service-c--6-2-0--aaaa",
      "job--3-0-0--zzzz4",
      "job--3-1-0--zzzz",
      "job--3-1-1--zzzz1",
      "job--4-1-0--zzzz2",
      "crawler--5-1-0--zzzz3"
    ))
  }

  it should "include deployments involved in reverse traffic shift with incoming edges" in {

    val inventory1 = runs(config.storage, StoreOp.findDeployment(StackName("inventory", Version(1,2,2), "ffff"))).run.get
    val inventory2 = runs(config.storage, StoreOp.findDeployment(StackName("inventory", Version(1,2,3), "ffff"))).run.get
    val id = storage.run(config.storage, StoreOp.reverseTrafficShift(inventory2.id, Instant.now.minusSeconds(1))).run

    val rts: List[(Namespace, RoutingGraph)] =
      runs(config.storage, generateRoutingTables("RoutingTableSpec")).run

    val graph = rts(0)._2

    // should be in graph
    graph.nodes.flatMap(_.deployment).find(_.id == inventory1.id) should equal(Some(inventory1))
    graph.nodes.flatMap(_.deployment).find(_.id == inventory2.id) should equal(Some(inventory2))

    val ab1 = runs(config.storage, StoreOp.findDeployment(StackName("ab", Version(2,2,1), "abcd"))).run.get
    val ab2 = runs(config.storage, StoreOp.findDeployment(StackName("ab", Version(2,2,2), "abcd"))).run.get

    // should should have incoming edges from ab
    graph.ins(RoutingNode(inventory1)).flatMap(_._2.deployment).toSet should equal (Set(ab1, ab2))
    graph.ins(RoutingNode(inventory2)).flatMap(_._2.deployment).toSet should equal (Set(ab1, ab2))
  }

  it should "generate outgoing routing graph for single deployment with traffic shiftinging" in {
    val ab = runs(config.storage, StoreOp.findDeployment(StackName("ab", Version(2,2,2), "abcd"))).run.get
    val i1 = runs(config.storage, StoreOp.findDeployment(StackName("inventory", Version(1,2,2), "ffff"))).run.get
    val i2 = runs(config.storage, StoreOp.findDeployment(StackName("inventory", Version(1,2,3), "ffff"))).run.get
    storage.run(config.storage, StoreOp.startTrafficShift(i1.id, i2.id, Instant.now.minusSeconds(120))).run
    val rg = runs(config.storage, outgoingRoutingGraph(ab)).run
    rg.nodes.flatMap(_.deployment).toSet should equal (Set(ab,i1,i2))
  }

  it should "generate outgoing routing graph for single deployment" in {
    val conductor = runs(config.storage, StoreOp.findDeployment(StackName("conductor", Version(1,1,1), "abcd"))).run.get
    val ab = runs(config.storage, StoreOp.findDeployment(StackName("ab", Version(2,2,2), "abcd"))).run.get
    val foo = runs(config.storage, StoreOp.findDeployment(StackName("foo", Version(1,10,100), "aaaa"))).run.get
    val search = runs(config.storage, StoreOp.findDeployment(StackName("search", Version(2,2,2), "aaaa"))).run.get
    val db = runs(config.storage, StoreOp.findDeployment(StackName("db", Version(1,2,3), "aaaa"))).run.get
    val rg = runs(config.storage, outgoingRoutingGraph(conductor)).run
    rg.nodes.flatMap(_.deployment).toSet should equal (Set(ab,conductor,foo,search,db))
  }

  it should "generate outgoing routing graph for single deployment in a subordinate namespace" in {

    val serviceB = runs(config.storage, StoreOp.findDeployment(StackName("service-b", Version(6,1,0), "aaaa"))).run.get
    val serviceC = runs(config.storage, StoreOp.findDeployment(StackName("service-c", Version(6,2,1), "bbbb"))).run.get
    val serviceCDown = runs(config.storage, StoreOp.findDeployment(StackName("service-c", Version(6,2,0), "aaaa"))).run.get
    val foo = runs(config.storage, StoreOp.findDeployment(StackName("foo", Version(1,10,100), "aaaa"))).run.get

    // downstream dependency
    val rg1 = runs(config.storage, outgoingRoutingGraph(serviceB)).run
    rg1.nodes.flatMap(_.deployment).toSet should equal (Set(serviceB, serviceCDown, serviceC))

    // upstream dependency
    val rg2 = runs(config.storage, outgoingRoutingGraph(serviceC)).run
    rg2.nodes.flatMap(_.deployment).toSet should equal (Set(foo, serviceC))
  }

  "routingTables" should "not include deployments with terminated, garbage, unknown or failed status" in {
    val ab = runs(config.storage, StoreOp.findDeployment(StackName("ab", Version(2,2,2), "abcd"))).run.get

    // active -> included
    runs(config.storage, StoreOp.createDeploymentStatus(ab.id, DeploymentStatus.Ready, None)).run
    val routingTables: List[(Namespace, RoutingGraph)] =
     runs(config.storage, generateRoutingTables("RoutingTableSpec")).run
    val graph = routingTables(0)._2
    graph.nodes.flatMap(_.deployment).find(_.id == ab.id) should equal (Some(ab))

    // terminated -> out
    runs(config.storage, StoreOp.createDeploymentStatus(ab.id, DeploymentStatus.Terminated, None)).run
    val routingTables1: List[(Namespace, RoutingGraph)] =
     runs(config.storage, generateRoutingTables("RoutingTableSpec")).run
    val graph1 = routingTables1(0)._2
    graph1.nodes.flatMap(_.deployment).find(_.id == ab.id) should equal (None)

    // garbage -> excluded
    runs(config.storage, StoreOp.createDeploymentStatus(ab.id, DeploymentStatus.Garbage, None)).run
    val routingTables2: List[(Namespace, RoutingGraph)] =
     runs(config.storage, generateRoutingTables("RoutingTableSpec")).run
    val graph2 = routingTables2(0)._2
    graph2.nodes.flatMap(_.deployment).find(_.id == ab.id) should equal (None)

    // failed -> excluded
    runs(config.storage, StoreOp.createDeploymentStatus(ab.id, DeploymentStatus.Failed, None)).run
    val routingTables3: List[(Namespace, RoutingGraph)] =
     runs(config.storage, generateRoutingTables("RoutingTableSpec")).run
    val graph3 = routingTables3(0)._2
    graph3.nodes.flatMap(_.deployment).find(_.id == ab.id) should equal (None)

    // unkonwn -> excluded
    runs(config.storage, StoreOp.createDeploymentStatus(ab.id, DeploymentStatus.Failed, None)).run
    val routingTables4: List[(Namespace, RoutingGraph)] =
     runs(config.storage, generateRoutingTables("RoutingTableSpec")).run
    val graph4 = routingTables4(0)._2
    graph4.nodes.flatMap(_.deployment).find(_.id == ab.id) should equal (None)
  }
}

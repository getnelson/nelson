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

import helm.ConsulOp
import helm.ConsulOp.HealthCheck
import Datacenter.{DCUnit, Deployment, Namespace, TrafficShift}
import DeploymentStatus.Warming
import monitoring.DeploymentMonitor
import monitoring.DeploymentMonitor.{PromoteToReady, RetainAsWarming}
import storage.StoreOp
import storage.StoreOp._

import scala.collection.immutable.Set
import scala.collection.mutable.Map
import scala.collection.mutable.ListBuffer
import scala.concurrent.duration._
import scalaz.stream._
import scalaz.stream.{Process, Sink}
import scalaz.concurrent.Task
import scalaz.{NonEmptyList, ~>}
import scala.language.postfixOps
import scala.concurrent.duration._
import java.time.Instant

class DeploymentMonitorSpec extends NelsonSuite {

  def mkDatacenterWithStorage(name: String)(implicit consul: ConsulOp ~> Task, op: StoreOp ~> Task) : Datacenter = {
    val dc = datacenter(name)
    dc.copy(interpreters = dc.interpreters.copy(
      consul = consul,
      storage = op
    ))
  }
  
  def mkNelsonConfig(dcs: List[Datacenter])(implicit sto: StoreOp ~> Task) =
    config.copy(
      datacenters = dcs,
      interpreters = config.interpreters.copy(storage = sto)
    )

  def mkNelsonConfig(deploymentMonitorDelay: Duration) =
    config.copy(
      deploymentMonitor = config.deploymentMonitor.copy(delay = deploymentMonitorDelay)
    )

  def mkStoreOp(f: Map[String, Set[Namespace]],
                g: Map[(ID, NonEmptyList[DeploymentStatus]), Set[(Deployment, DeploymentStatus)]],
                h: Map[UnitName, List[Deployment]] = Map.empty,
                i: Map[String, TrafficShift] = Map.empty) = new (StoreOp ~> Task) {
    override def apply[A](s: StoreOp[A]): Task[A] = s match {
      case ListNamespacesForDatacenter(dc) => Task.now(f(dc))
      case ListDeploymentsForNamespaceByStatus(nsId, statuses, _) => Task.now(g(nsId -> statuses))
      case GetDeploymentsForServiceNameByStatus(sn, ns, s) => Task.now(h.get(sn.serviceType).getOrElse(Nil))
      case GetTrafficShiftForServiceName(nsid, sn) => Task.now(i.get(sn.serviceType))
      case _ => Task.fail(new Exception("Unexpected Store Operation Executed"))
    }
  }

  val namespace = Datacenter.Namespace(1L, NamespaceName("dev"), "dev")

  def mkConsulOpWithMajorityHealthy(f: Map[UnitName, String]) = new (ConsulOp ~> Task) {
    override def apply[A](c: ConsulOp[A]): Task[A] = c match {
      case HealthCheck(service) => Task.now(s"""[{"Status":"${f(service)}"},{"Status":"warning"}, {"Status":"passing"}, {"Status":"passing"}, {"Status":"passing"}]""")
      case _ => Task.fail(new Exception("Unexpected Store Operation Executed"))
    }
  }

  def mkConsulOp(f: Map[UnitName, String]) = new (ConsulOp ~> Task) {
    override def apply[A](c: ConsulOp[A]): Task[A] = c match {
      case HealthCheck(service) => Task.now(s"""[{"Status":"${f(service)}"}]""")
      case _ => Task.fail(new Exception("Unexpected Store Operation Executed"))
    }
  }

  def mkDcUnit(id: ID, unitName: String, version: Version) : DCUnit =
    DCUnit(id, unitName, version, "", Set.empty, Set.empty, Set.empty)

  def mkNamespace(nsId : nelson.ID, name : NamespaceName, datacenter : String) : Namespace =
    Namespace(nsId, name, datacenter)


  "DeploymentMonitor" should "should properly generate monitor action items" in {

    val dep1 = Deployment(1L, mkDcUnit(1L, "s0", Version(1, 0, 0)), "a", namespace, null, null, "plan-1", "guid-1", "retain-latest")
    val dep2 = Deployment(1L, mkDcUnit(1L, "s1", Version(1, 0, 1)), "a", namespace, null, null, "plan-1", "guid-1", "retain-latest")

    val consulInterp = mkConsulOpWithMajorityHealthy(Map(dep1.stackName.toString -> "passing", dep2.stackName.toString -> "dead"))

    val storeInterp = mkStoreOp(
      Map("dc0" -> Set(mkNamespace(1L, NamespaceName("dev"), "dc0"), mkNamespace(2L, NamespaceName("qa"), "dc0"), mkNamespace(3L, NamespaceName("prod"), "dc0"))),
      Map((1L, NonEmptyList(Warming)) -> Set(dep1 -> DeploymentStatus.Warming, dep2 -> DeploymentStatus.Warming),
          (2L, NonEmptyList(Warming)) -> Set.empty,
          (3L, NonEmptyList(Warming)) -> Set.empty),
      Map("s0" -> List(dep1))
    )

    val dc = mkDatacenterWithStorage("dc0")(consulInterp, storeInterp)

    val cfg = mkNelsonConfig(List(dc))(storeInterp)

    val list = DeploymentMonitor.monitorActionItems(cfg).run

    list.size should equal(2)

    list.exists(_ match {
      case PromoteToReady(datacenter, dep) => datacenter.name == dc.name && dep.stackName == dep1.stackName
      case _ => false
    }) should be(true)

    list.exists(_ match {
      case RetainAsWarming(datacenter, dep, _) => datacenter.name == dc.name && dep.stackName == dep2.stackName
      case _ => false
    }) should be(true)
  }


  // Traffic Shifting

  val now = java.time.Instant.now

  val dep100 = Deployment(0L, mkDcUnit(0L, "service", Version(1, 0, 0)), "a", namespace, now.plusSeconds(1), "magnetar", "plan-1", "guid-1", "retain-latest")
  val dep101 = Deployment(1L, mkDcUnit(1L, "service", Version(1, 0, 1)), "a", namespace, now.plusSeconds(2), "magnetar", "plan-1", "guid-1", "retain-latest")
  val dep102 = Deployment(2L, mkDcUnit(2L, "service", Version(1, 0, 2)), "a", namespace, now.plusSeconds(3), "magnetar", "plan-1", "guid-1", "retain-latest")
  val dep103 = Deployment(3L, mkDcUnit(3L, "service", Version(1, 0, 3)), "a", namespace, now.plusSeconds(4), "magnetar", "plan-1", "guid-1", "retain-latest")

  def mkTrafficShift(dur: FiniteDuration, start: Instant, reverse: Option[Instant]) =
    TrafficShift(
      from = dep100,
      to = dep101,
      policy = LinearShiftPolicy,
      start = start,
      duration = dur,
      reverse = reverse)

  // the first time a service is deployed there will be no preceeding traffic shift
  it should "bootstrap promotion"  in {
    val consul = mkConsulOpWithMajorityHealthy(Map(dep100.stackName.toString -> "passing", dep101.stackName.toString -> "passing", dep102.stackName.toString -> "passing"))
    val stg = mkStoreOp(
      Map("dc0" -> Set(mkNamespace(1L, NamespaceName("dev"), "dc0"))),
      Map((1L, NonEmptyList(Warming)) -> Set(dep100 -> Warming)),
      Map("service" -> List(dep100))
    )
    val dc = mkDatacenterWithStorage("dc0")(consul, stg)
    val res = DeploymentMonitor.monitorActionItem(dc,dep100).run
    res should equal(PromoteToReady(dc,dep100))
  }

  it should "remain in warming state during traffic shift" in {

    val ts = mkTrafficShift(10.minutes, Instant.now.minusSeconds(10), None)

    ts.inProgress(Instant.now) should equal(true)

    val consul = mkConsulOp(Map(
      dep100.stackName.toString -> "passing",
      dep101.stackName.toString -> "passing",
      dep102.stackName.toString -> "passing",
      dep103.stackName.toString -> "passing"
    ))

    val stg = mkStoreOp(
      Map("dc0" -> Set(mkNamespace(1L, NamespaceName("dev"), "dc0"))),
      Map((1L, NonEmptyList(Warming)) -> Set(dep102 -> Warming, dep103 -> Warming)),
      Map("service" -> List(dep101)),
      Map(dep101.unit.name -> ts)
    )

    val dc = mkDatacenterWithStorage("dc0")(consul, stg)

    val i102 = DeploymentMonitor.monitorActionItem(dc, dep102).run
    val i103 = DeploymentMonitor.monitorActionItem(dc, dep103).run

    i102 should equal(RetainAsWarming(dc, dep102, "Traffic shift in progress, can not promote at this time."))
    i103 should equal(RetainAsWarming(dc, dep103, "Traffic shift in progress, can not promote at this time."))
  }

  it should "remain in warming state during traffic shift reverse" in {

    val ts = mkTrafficShift(10.minutes, Instant.now.minusSeconds(10), Some(Instant.now.plusSeconds(120)))

    val consul = mkConsulOpWithMajorityHealthy(Map(
      dep100.stackName.toString -> "failing",
      dep101.stackName.toString -> "failing",
      dep102.stackName.toString -> "passing",
      dep103.stackName.toString -> "passing"
    ))

    val stg = mkStoreOp(
      Map("dc0" -> Set(mkNamespace(1L, NamespaceName("dev"), "dc0"))),
      Map((1L, NonEmptyList(Warming)) -> Set(dep102 -> Warming, dep103 -> Warming)),
      Map("service" -> List(dep101)),
      Map(dep101.unit.name -> ts)
    )

    val dc = mkDatacenterWithStorage("dc0")(consul, stg)

    val i102 = DeploymentMonitor.monitorActionItem(dc, dep102).run
    val i103 = DeploymentMonitor.monitorActionItem(dc, dep103).run

    i102 should equal(RetainAsWarming(dc, dep102, "Traffic shift in progress, can not promote at this time."))
    i103 should equal(RetainAsWarming(dc, dep103, "Traffic shift in progress, can not promote at this time."))
  }

  it should "properly drain values as apart of the deployment monitor background process mechanism" in {

    import nelson.Nelson.lift

    val fib = Seq(1, 2, 3, 5, 8, 13)

    val cfg = mkNelsonConfig(2 seconds)
    val throughSinkBuffer = ListBuffer.empty[Int]
    val finalSinkBuffer = ListBuffer.empty[Int]

    val heartbeat = Process.emitAll(Seq(0 seconds, 0 seconds)).toSource

    val writer = lift[Process, Seq[Int]] { _ =>
      Process.emit(fib).toSource
    }

    val throughSink = sink.lift[Task, Int] { i =>
      Task.delay { throughSinkBuffer += i; () }
    }

    val toSink = lift[Sink, Int] { _ =>
      sink.lift { i =>
        Task.delay { finalSinkBuffer += i; () }
      }
    }

    DeploymentMonitor.drain(cfg)(heartbeat, writer, throughSink, toSink).run.run

    throughSinkBuffer.toList should equal((fib ++ fib).toList)
    finalSinkBuffer.toList should equal((fib ++ fib).toList)
  }
}

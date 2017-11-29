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

import nelson.Datacenter.{DCUnit, Deployment, Namespace, StackName}
import nelson.Reconciliation._
import nelson.storage.StoreOp
import nelson.storage.StoreOp._
import nelson.scheduler.{NomadHttp, RunningUnit, SchedulerOp}
import nelson.scheduler.SchedulerOp._

import scalaz.concurrent.Task
import scalaz.{NonEmptyList, Order, Tag, ~>}

class ReconciliationSpec extends NelsonSuite {

  implicit val stringOrdering : Order[String] = Order.fromScalaOrdering

  def mkStoreOp(f: String => Task[Set[Namespace]],
                g: (Long, NonEmptyList[DeploymentStatus]) => Task[Set[(Deployment,DeploymentStatus)]],
                h: StackName => Task[Option[Deployment]],
                i: Long => Task[Option[DeploymentStatus]]) = new (StoreOp ~> Task) {
    override def apply[A](s: StoreOp[A]): Task[A] = s match {
      case ListNamespacesForDatacenter(dcName) => f(dcName)
      case ListDeploymentsForNamespaceByStatus(nsId, deploymentStatuses, None) => g(nsId, deploymentStatuses)
      case FindDeployment(stackName) => h(stackName)
      case GetDeploymentStatus(deploymentId) => i(deploymentId)
      case _ => Task.fail(new Exception("Unexpected Store Operation Executed"))
    }
  }

  def mkSchedulerOp(f: Datacenter => Task[Set[RunningUnit]], g: (DeploymentStatus, NonEmptyList[Set[TaskStatus]]) => Task[Boolean], h: Datacenter => Task[List[TaskGroupAllocation]]) = new (SchedulerOp ~> Task) {
    override def apply[A](s: SchedulerOp[A]): Task[A] = s match {
      case RunningUnits(dc, p) => f(dc)
      case EquivalentStatus(status, reverseChrono) => g(status, reverseChrono)
      case Allocations(dc, p) => h(dc)
      case _ => Task.fail(new Exception("Unexpected Store Operation Executed"))
    }
  }

  case class DepWithStatus(d: Deployment, s: DeploymentStatus)

  def deriveStoreOp(deps : Map[Long, Deployment], ns : Map[Long, Namespace], dcs : Set[String],
                    dcMap : Map[String, Map[Namespace, Map[StackName, DepWithStatus]]]) : StoreOp ~> Task =
    mkStoreOp((dc: String) => Task.now(dcMap(dc).keys.toSet),

      (nsid: Long, ls: NonEmptyList[DeploymentStatus]) =>
        Task.now(for {
          n : Namespace <- ns.get(nsid).toSet
          nmap : Map[Namespace, Map[StackName, DepWithStatus]] <- dcMap.values
          smap : Map[StackName, DepWithStatus] <- nmap.get(n).toSet
          ds : DepWithStatus <- smap.values.filter { case DepWithStatus(_, s) => ls.list contains s }
        } yield (ds.d, DeploymentStatus.Ready)),

      (fa: StackName) =>
        Task.now((for {
          nmap : Map[Namespace, Map[StackName, DepWithStatus]] <- dcMap.values.toSet
          smap : Map[StackName, DepWithStatus] <- nmap.values.toSet
          ds : DepWithStatus <- smap.get(fa)
        } yield ds.d).headOption),

      (fa: Long) =>
        Task.now((for {
          nmap : Map[Namespace, Map[StackName, DepWithStatus]] <- dcMap.values.toSet
          smap : Map[StackName, DepWithStatus] <- nmap.values.toSet
          ds : DepWithStatus <- smap.values.toSet.filter {f: DepWithStatus => f.d.id == fa}
        } yield ds.s).headOption)
    )

  def deriveSchedulerOp(dcUnits : Map[String, Set[RunningUnit]], allocs: List[TaskGroupAllocation]) : SchedulerOp ~> Task = mkSchedulerOp(
    (dc: Datacenter) => Task(dcUnits(dc.name)),
    (s: DeploymentStatus, l: NonEmptyList[Set[TaskStatus]]) => Task.now(NomadHttp.equivalentStatus(s, l)),  // This is the only Algebra operation that isn't being mocked.
    (dc: Datacenter) => Task(allocs)
  )

  def mkDatacenterWithStorage(name: String)(implicit op: StoreOp ~> Task) : Datacenter = {
    val dc = datacenter(name)
    dc.copy(interpreters = dc.interpreters.copy(
      storage = op
    ))
  }

  def mkDatacenterWithScheduler(name: String)(implicit op: SchedulerOp ~> Task) : Datacenter = {
    val dc = datacenter(name)
    dc.copy(interpreters = dc.interpreters.copy(
      scheduler = op
    ))
  }

  def mkDatacenterWithStorageAndScheduler(name: String)(implicit sto: StoreOp ~> Task, sch: SchedulerOp ~> Task) : Datacenter = {
    val dc = datacenter(name)
    dc.copy(interpreters = dc.interpreters.copy(
      storage = sto,
      scheduler = sch
    ))
  }

  def mkDcUnit(id: ID, unitName: String, version: Version) : DCUnit =
    DCUnit(id, unitName, version, "", Set.empty, Set.empty, Set.empty)

  def mkNelsonConfig(dcs: List[Datacenter])(implicit sto: StoreOp ~> Task) =
    config.copy(
      datacenters = dcs,
      interpreters = config.interpreters.copy(storage = sto)
    )

  val namespace = Datacenter.Namespace(1L, NamespaceName("dev"), "dc")

  "Reconciliation" should "be able to retrieve all running units" in {
    val dcs : Set[String] = Set("san-jose", "santa-clara")

    val unit1 = RunningUnit(Some("xyz"), Some("OnFire"), None)
    val unit2 = RunningUnit(Some("abc"), Some("Ready"), None)

    val dcUnits : Map[String, Set[RunningUnit]] = Map(
      "san-jose" -> Set(unit1),
      "santa-clara" -> Set(unit2)
    )

    implicit val schedulerOp = deriveSchedulerOp(dcUnits, Nil)

    val config = mkNelsonConfig(dcs.map(mkDatacenterWithScheduler).toList)(sto = null)

    val result = Reconciliation.getAllRunningUnits(config).run.mapKeys(_.name)

    result.lookup("san-jose").get.size should be(1)
    result.lookup("san-jose").get contains unit1 should be(true)
    result.lookup("santa-clara").get contains unit2 should be(true)
  }


  it should "handle situations where Nelson has no overlap with the scheduler" in {
    val dep1 = Deployment(10L, mkDcUnit(10L, "foo", Version(1, 0, 0)), "a", namespace, null, null, "plan-1", "guid-1", "retain-latest")
    val dep2 = Deployment(11L, mkDcUnit(10L, "bar", Version(2, 1, 0)), "b", namespace, null, null, "plan-2", "guid-2", "retain-latest")

    val deps : Map[Long, Deployment] = Map(
      10L -> dep1,
      11L -> dep2
    )

    val ns : Map[Long, Namespace] = Map(
      1L -> Namespace(1L, NamespaceName("qa"), "qa"),
      2L -> Namespace(2L, NamespaceName("prod"), "prod")
    )

    val dcs : Set[String] = Set("san-jose", "santa-clara")

    val dcMap : Map[String, Map[Namespace, Map[StackName, DepWithStatus]]] = Map(
      "san-jose" -> Map(
        ns(1L) -> Map(
          StackName("foo", Version(1, 0, 0), "deadb33f") -> DepWithStatus(deps(10L), DeploymentStatus.Ready),
          StackName("bar", Version(2, 1, 0), "l33t") -> DepWithStatus(deps(11L), DeploymentStatus.Ready)
        )
      )
    )

    val unit1 = RunningUnit(Some("xyz"), Some("on-fire"), None)
    val unit2 = RunningUnit(Some("abc"), Some("Ready"), None)

    val dcUnits : Map[String, Set[RunningUnit]] = Map(
      "san-jose" -> Set(unit1, unit2),
      "santa-clara" -> Set()
    )

    implicit val storeOp = deriveStoreOp(deps, ns, dcs, dcMap)
    implicit val schedulerOp = deriveSchedulerOp(dcUnits, Nil)

    val config = mkNelsonConfig(dcs.map(mkDatacenterWithStorageAndScheduler).toList)

    val result = Reconciliation.schedulerConflicts(config).run.mapKeys(_.name)

    result.size should equal(1)
    val sj = result.lookup("san-jose").get

    sj.size should equal(4)

    val nelsonOnly : Set[(Deployment, Option[DeploymentStatus])] = for {
      c : Conflict[Deployment] <- sj
      a : NelsonAware[Deployment] <- c.run.toOption.get.run.swap.toOption.toSet
      o : InNelsonOnly[Deployment] <- a.run.swap.toOption.toSet
    } yield o.a -> o.status

    nelsonOnly.size should equal(2)

    nelsonOnly.exists { case (d, _) => d == dep1 } should be(true)
    nelsonOnly.exists { case (d, _) => d == dep2 } should be(true)

    val nelsonNotExpected : Set[RunningUnit] = for {
      c : Conflict[Deployment] <- sj
      o : Orphaned[Deployment] <- c.run.toOption.get.run.toOption.toSet
      n : NelsonNotExpected[Deployment] <- o.run.toOption.toSet
    } yield n.u

    nelsonNotExpected.contains(unit1) should be(true)
    nelsonNotExpected.contains(unit2) should be(true)
  }

  it should "handle situations where Nelson the scheduler have state conflicts and scheduler has NelsonExpected units across two data-centers" in {
    val depSJ1 = Deployment(10L, mkDcUnit(10L, "alpha", Version(1, 0, 0)), "deadb33f", namespace, null, null, "plan-1", "guid-1", "retain-latest")
    val depSJ2 = Deployment(11L, mkDcUnit(10L, "beta", Version(2, 1, 0)), "l33t", namespace, null, null, "plan-2", "guid-2", "retain-latest")
    val depSC1 = Deployment(12L, mkDcUnit(10L, "delta", Version(3, 1, 0)), "QWERQWER", namespace, null, null, "plan-3", "guid-3", "retain-latest")
    val depSC2 = Deployment(13L, mkDcUnit(10L, "gamma", Version(4, 1, 0)), "WTFOMGBBQ", namespace, null, null, "plan-4", "guid-4", "retain-latest")

    val deps : Map[Long, Deployment] = Map(
      10L -> depSJ1,
      11L -> depSJ2,
      12L -> depSC1,
      13L -> depSC2
    )

    val ns : Map[Long, Namespace] = Map(
      1L -> Namespace(1L, NamespaceName("qa"), "qa"),
      2L -> Namespace(2L, NamespaceName("prod"), "prod")
    )

    val dcs : Set[String] = Set("san-jose", "santa-clara")

    val dcMap : Map[String, Map[Namespace, Map[StackName, DepWithStatus]]] = Map(
     "san-jose" -> Map(
        ns(1L) -> Map(
          StackName("alpha", Version(1, 0, 0), "deadb33f") -> DepWithStatus(deps(10L), DeploymentStatus.Ready),
          StackName("beta", Version(2, 1, 0), "l33t") -> DepWithStatus(deps(11L), DeploymentStatus.Ready)
        )
      ),
    "santa-clara" -> Map(
        ns(2L) -> Map(
          StackName("delta", Version(3, 1, 0), "QWERQWER") -> DepWithStatus(deps(12L), DeploymentStatus.Ready),
          StackName("gamma", Version(4, 1, 0), "WTFOMGBBQ") -> DepWithStatus(deps(13L), DeploymentStatus.Ready)
        )
      )
    )

    val matchingUnitSJ = RunningUnit(Some("alpha--1-0-0--deadb33f"), Some("Ready"), None)
    val matchingUnitSC = RunningUnit(Some("gamma--4-1-0--WTFOMGBBQ"), Some("Ready"), None)
    val unitWithStatusConflictSJ = RunningUnit(Some("beta--2-1-0--l33t"), Some("RunningVerySlowely"), None)
    val unitWithStatusConflictSC = RunningUnit(Some("delta--3-1-0--QWERQWER"), Some("Buggy"), None)
    val unitNotInNelsonButExpectedSJ = RunningUnit(Some("epsilon--1-0-0--ASDFAS"), Some("Foo"), None)
    val unitNotInNelsonButExpectedSC = RunningUnit(Some("sigma--1-5-0--GFXCVB"), Some("Bar"), None)

    val dcUnits : Map[String, Set[RunningUnit]] = Map(
      "san-jose" -> Set(matchingUnitSJ, unitWithStatusConflictSJ, unitNotInNelsonButExpectedSJ),
      "santa-clara" -> Set(matchingUnitSC, unitWithStatusConflictSC, unitNotInNelsonButExpectedSC)
    )

    val allocs : List[TaskGroupAllocation] = List(
      TaskGroupAllocation("alloc0", "alloc0-name", s"${depSJ1.stackName.toString}", "group0", Map(
        (Tag[String, TaskName]("task0"), (TaskStatus.Running, List[TaskEvent]()))
      )),
      TaskGroupAllocation("alloc1", "alloc1-name", s"${depSC2.stackName.toString}", "group1", Map(
        (Tag[String, TaskName]("task1"), (TaskStatus.Running, List[TaskEvent]()))
      )),
      TaskGroupAllocation("alloc2", "alloc2-name", s"${depSJ2.stackName.toString}", "group2", Map(
        (Tag[String, TaskName]("task1"), (TaskStatus.Dead, List[TaskEvent]()))
      )),
      TaskGroupAllocation("alloc3", "alloc3-name", s"${depSC1.stackName.toString}", "group3", Map(
        (Tag[String, TaskName]("task1"), (TaskStatus.Dead, List[TaskEvent]()))
      ))
    )

    implicit val storeOp = deriveStoreOp(deps, ns, dcs, dcMap)
    implicit val schedulerOp = deriveSchedulerOp(dcUnits, allocs)

    val config = mkNelsonConfig(dcs.map(mkDatacenterWithStorageAndScheduler).toList)

    val result = Reconciliation.schedulerConflicts(config).run.mapKeys(_.name)

    result.size should equal(2)
    val sj = result.lookup("san-jose").get
    val sc = result.lookup("santa-clara").get

    sj.size should equal(2)
    sc.size should equal(2)


    val statusConflictSJ : Set[(RunningUnit, Deployment, Option[DeploymentStatus])] = for {
      c : Conflict[Deployment] <- sj
      a : NelsonAware[Deployment] <- c.run.toOption.get.run.swap.toOption.toSet
      x : StateConflict[Deployment] <- a.run.toOption.toSet
    } yield (x.u, x.a, x.status)

    val statusConflictSC : Set[(RunningUnit, Deployment, Option[DeploymentStatus])] = for {
      c : Conflict[Deployment] <- sc
      a : NelsonAware[Deployment] <- c.run.toOption.get.run.swap.toOption.toSet
      x : StateConflict[Deployment] <- a.run.toOption.toSet
    } yield (x.u, x.a, x.status)


    statusConflictSJ.size should equal(1)
    statusConflictSC.size should equal(1)

    statusConflictSJ.exists { case (u, d, s) => u == unitWithStatusConflictSJ && d == depSJ2 &&
      s.contains(DeploymentStatus.Ready) } should be(true)


    statusConflictSC.exists { case (u, d, s) => u == unitWithStatusConflictSC && d == depSC1 &&
      s.contains(DeploymentStatus.Ready)  } should be(true)


    val nelsonExpectedSJ : Set[RunningUnit] = for {
      c : Conflict[Deployment] <- sj
      o : Orphaned[Deployment] <- c.run.toOption.get.run.toOption.toSet
      e : NelsonExpected[Deployment] <- o.run.swap.toOption.toSet
    } yield e.u

    val nelsonExpectedSC : Set[RunningUnit] = for {
      c : Conflict[Deployment] <- sc
      o : Orphaned[Deployment] <- c.run.toOption.get.run.toOption.toSet
      e : NelsonExpected[Deployment] <- o.run.swap.toOption.toSet
    } yield e.u

    nelsonExpectedSJ.contains(unitNotInNelsonButExpectedSJ) should be(true)
    nelsonExpectedSC.contains(unitNotInNelsonButExpectedSC) should be(true)
  }

  it should "handle situations where we have conflicts of all types within a single data center" in {

    val dcs : Set[String] = Set("san-jose")

    val ns : Map[Long, Namespace] = Map(
      1L -> Namespace(1L, NamespaceName("qa"), "qa")
    )

    val depMatching = Deployment(10L, mkDcUnit(10L, "alpha", Version(1, 0, 0)), "deadb33f", namespace, null, null, "plan-1", "guid-1", "retain-latest")
    val depNelsonOnly = Deployment(11L, mkDcUnit(10L, "beta", Version(2, 1, 0)), "l33t", namespace, null, null, "plan-2", "guid-2", "retain-latest")
    val depConflict = Deployment(12L, mkDcUnit(10L, "delta", Version(3, 1, 0)), "QWERQWER", namespace, null, null, "plan-3", "guid-3", "retain-latest")

    val deps : Map[Long, Deployment] = Map(
      10L -> depMatching,
      11L -> depNelsonOnly,
      12L -> depConflict
    )

    val depUnits : Map[Deployment, RunningUnit] = Map(
      depMatching -> RunningUnit(Some(s"${depMatching.stackName.toString}"), Some("Ready"), None),
      depConflict -> RunningUnit(Some(s"${depConflict.stackName.toString}"), Some("Ready"), None)
    )

    val allocs : List[TaskGroupAllocation] = List(
      TaskGroupAllocation("alloc0", "alloc0-name", s"${depMatching.stackName.toString}", "group0", Map(
        (Tag[String, TaskName]("task0"), (TaskStatus.Running, List[TaskEvent]()))
      )),
      TaskGroupAllocation("alloc1", "alloc1-name", s"${depConflict.stackName.toString}", "group1", Map(
        (Tag[String, TaskName]("task1"), (TaskStatus.Dead, List[TaskEvent]()))
      ))
    )

    val nelsonExpected = RunningUnit(Some(StackName("notInNelson", Version(1,0,3), "ASDFASF1234").toString), Some("FakeStatus"), None)
    val nelsonNotExpected = RunningUnit(Some("non-parseable-name"), Some("Ready"), None)

    val dcUnits : Map[String, Set[RunningUnit]] = Map(
      "san-jose" -> Set(depUnits(depMatching), depUnits(depConflict), nelsonExpected, nelsonNotExpected)
    )

    val dcMap : Map[String, Map[Namespace, Map[StackName, DepWithStatus]]] = Map(
      "san-jose" -> Map(
        ns(1L) -> Map(
          depMatching.stackName -> DepWithStatus(depMatching, DeploymentStatus.fromString(depUnits(depMatching).status.get)),
          depConflict.stackName -> DepWithStatus(depConflict, DeploymentStatus.Ready),
          depNelsonOnly.stackName -> DepWithStatus(depNelsonOnly, DeploymentStatus.Deprecated)
        )
      )
    )

    implicit val storeOp = deriveStoreOp(deps, ns, dcs, dcMap)
    implicit val schedulerOp = deriveSchedulerOp(dcUnits, allocs)

    val config = mkNelsonConfig(dcs.map(mkDatacenterWithStorageAndScheduler).toList)

    val result = Reconciliation.schedulerConflicts(config).run.mapKeys(_.name)

    result.size should equal(1)
    val sj = result.lookup("san-jose").get

    sj.size should equal(4)

    val nelsonOnlyR : Set[(Deployment, Option[DeploymentStatus])] = for {
      c : Conflict[Deployment] <- sj
      a : NelsonAware[Deployment] <- c.run.toOption.get.run.swap.toOption.toSet
      o : InNelsonOnly[Deployment] <- a.run.swap.toOption.toSet
    } yield o.a -> o.status

    val statusConflictR : Set[(RunningUnit, Deployment, Option[DeploymentStatus])] = for {
      c : Conflict[Deployment] <- sj
      a : NelsonAware[Deployment] <- c.run.toOption.get.run.swap.toOption.toSet
      x : StateConflict[Deployment] <- a.run.toOption.toSet
    } yield (x.u, x.a, x.status)

    val nelsonExpectedR : Set[RunningUnit] = for {
      c : Conflict[Deployment] <- sj
      o : Orphaned[Deployment] <- c.run.toOption.get.run.toOption.toSet
      e : NelsonExpected[Deployment] <- o.run.swap.toOption.toSet
    } yield e.u

    val nelsonNotExpectedR : Set[RunningUnit] = for {
      c : Conflict[Deployment] <- sj
      o : Orphaned[Deployment] <- c.run.toOption.get.run.toOption.toSet
      n : NelsonNotExpected[Deployment] <- o.run.toOption.toSet
    } yield n.u

    nelsonOnlyR.size should equal(1)
    statusConflictR.size should equal(1)
    nelsonExpectedR.size should equal(1)
    nelsonNotExpectedR.size should equal(1)

    nelsonOnlyR.exists { case (d, s) => d == depNelsonOnly && s.contains(DeploymentStatus.Deprecated) } should be(true)
    statusConflictR.exists { case (u, d, s) => u == depUnits(depConflict) && d == depConflict &&
      s.contains(DeploymentStatus.Ready) } should be(true)
    nelsonExpectedR.contains(nelsonExpected) should be(true)
    nelsonNotExpectedR.contains(nelsonNotExpected) should be(true)
  }

}

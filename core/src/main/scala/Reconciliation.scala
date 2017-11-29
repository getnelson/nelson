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

import java.util.concurrent.atomic.AtomicReference

import nelson.Datacenter.{Deployment, StackName}
import nelson.scheduler._
import nelson.storage.StoreOp

import scalaz.{-\/, ==>>, Coproduct, Failure, Monad, NonEmptyList, Order, Success, ValidationNel, \/, \/-, ~>}
import scalaz.Coproduct._
import scalaz.concurrent.Task
import scalaz.std.list._
import scalaz.std.list.listSyntax._
import scalaz.syntax.all._
import scalaz.syntax.std.string._
import scalaz.std.option._
import scalaz.stream.{Process, Sink, Writer, sink, time}
import scalaz.Id._
import ScalazHelpers._
import journal.Logger
import nelson.DeploymentStatus.{Deploying, Failed, Pending, Terminated, Unknown, Warming}

import scala.language.reflectiveCalls

object Reconciliation {

  protected val log = Logger[this.type]

  type DCDConflictMap = Datacenter ==>> Set[Conflict[Deployment]]
  type DCDConflictLabeledMap = Datacenter ==>> (String ==>> Set[Conflict[Deployment]])

  // This is intended to capture an effect of the most recent reconciliation process which is not expected
  // to be highly contentious (one writer and few readers), but as an effect: we are encapsulating within a Task for soundness.
  def latest : Task[DCDConflictLabeledMap] = Task.delay(latestConflicts.get())
  private val latestConflicts : AtomicReference[DCDConflictLabeledMap]
    = new AtomicReference(==>>.empty[Datacenter, String ==>> Set[Conflict[Deployment]]])

  sealed abstract class ConflictType(val label: String)

  final case class MalformedConflict[A](problem: String, conflict: Option[ConflictEntry[A]]) extends ConflictType("malformed_conflict")

  // Does Nelson know about it?
  sealed trait InNelson
  final case class InNelsonOnly[A](a: A, status: Option[DeploymentStatus])
    extends ConflictType("only_in_nelson") with InNelson
  final case class StateConflict[A](a: A, status: Option[DeploymentStatus], alloc: TaskGroupAllocation, u: RunningUnit)
    extends ConflictType("inconsistent_state") with InNelson

  // Is Running Unit expected to be found in Nelson?
  sealed trait Running
  final case class NelsonExpected[A](u: RunningUnit) extends ConflictType("should_be_in_nelson") with Running
  final case class NelsonNotExpected[A](u: RunningUnit) extends ConflictType("cant_be_in_nelson") with Running

  type Orphaned[A] = Coproduct[NelsonExpected, NelsonNotExpected, A]
  type NelsonAware[A] = Coproduct[InNelsonOnly, StateConflict, A]
  type ConflictEntry[A] = Coproduct[NelsonAware[?], Orphaned[?], A]
  type Conflict[A] = Coproduct[MalformedConflict[?], ConflictEntry[?], A]

  // Conflict Coproduct value creation
  object DeploymentConflict {
    def nelson(d: Deployment, s: Option[DeploymentStatus]) = new {
      def only : Conflict[Deployment] = rightc[MalformedConflict, ConflictEntry, Deployment](
        leftc[NelsonAware[?], Orphaned[?], Deployment](
          leftc[InNelsonOnly, StateConflict, Deployment](InNelsonOnly(d, s)))
      )
      def stateConflict(u: RunningUnit, alloc: TaskGroupAllocation) : Conflict[Deployment] =
        rightc[MalformedConflict, ConflictEntry, Deployment](
          leftc[NelsonAware[?], Orphaned[?], Deployment](
            rightc[InNelsonOnly, StateConflict, Deployment](StateConflict(d, s, alloc, u)))
      )
    }

    def orphaned(u: RunningUnit) = new {
      def nelsonExpected : Conflict[Deployment] = rightc[MalformedConflict, ConflictEntry, Deployment](
        rightc[NelsonAware[?], Orphaned[?], Deployment](
          leftc[NelsonExpected, NelsonNotExpected, Deployment](NelsonExpected(u)))
      )
      def nelsonNotExpected : Conflict[Deployment] = rightc[MalformedConflict, ConflictEntry, Deployment](
        rightc[NelsonAware[?], Orphaned[?], Deployment](
          rightc[NelsonExpected, NelsonNotExpected, Deployment](NelsonNotExpected(u)))
      )
    }

    def malformed(problem: String) : Conflict[Deployment] =
      leftc[MalformedConflict, ConflictEntry, Deployment](MalformedConflict(problem, None))
    def malformed(problem: String, conflict: ConflictEntry[Deployment]) =
      leftc[MalformedConflict, ConflictEntry, Deployment](MalformedConflict(problem, Some(conflict)))
  }

  import DeploymentConflict._

  /*
   * Group all of the conflicts by their labels (indicating the conflict type).  In order to do this
   * grouping we need to reduce (map+flatMap actually) all of the conflict disjunctions down to their labels.
   *
   * TODO: improve w/ recursive coproduct Liskov definitions.
   */
  def groupByConflictLabel[A](conflicts: List[Conflict[A]]) : String ==>> Set[Conflict[A]] =
    conflicts.zgroup {
      _.run.map(                                      // Conflict Entry
        _.run.map(                                    // Nelson Aware
          _.run.map(_.label).leftMap(_.label).merge   // InNelsonOnly, StateConflict
        ).leftMap(                                    // Orphaned
          _.run.map(_.label).leftMap(_.label).merge   // NelsonExpected, NelsonNotExpected
        ).merge
      ).leftMap(_.label).merge                        // Malformed
    }(Order.fromScalaOrdering)

  /*
   * Creates a daemon that will compute all conflicts, that require some form of reconciliation, and drain them.
   */
  def loop(cfg: NelsonConfig) : Process[Task, Unit] =
    ((t: Task[Throwable \/ DCDConflictLabeledMap]) => drain(cfg)(Process.eval(Task.fork(t)) ++
      (time.awakeEvery(cfg.reconciliation.cadence)(cfg.pools.schedulingExecutor, cfg.pools.schedulingPool) >> Process.eval(t))
     ))(schedulerConflicts(cfg).map(_.map(s => groupByConflictLabel(s.toList))).attempt)

  /*
   * Drain all conflicts from the writer process (using an auditor error sink, observing it with counter modification and
   * routing to a final atomic sink that retains the conflicts for later review.
   */
  def drain(cfg: NelsonConfig)(w: Writer[Task, Throwable, DCDConflictLabeledMap]) : Process[Task, Unit] =
    w.attempt().observeW(cfg.auditor.errorSink).stripW
      .observeO(counterSink).throughO(atomicSink.toChannel)
      .to(sink.lift[Task, Throwable \/ DCDConflictLabeledMap] { _ => Task.now(()) })

  /*
   * pull in all deployments or unresolvable running units that are found in the scheduler and then rebuild a
   * mapping with a set of all conflicts per DC.
   */
  def schedulerConflicts(cfg: NelsonConfig) : Task[DCDConflictMap] = for {
    allr <- getAllRunningUnits(cfg)
    cfls <- allr.fold(Task.now(==>>.empty[Datacenter, Set[Conflict[Deployment]]])) { case (dc, s, t) =>
      t.flatMap { m => dcSchedulerConflicts(s, dc)(cfg.storage, dc.interpreters.scheduler).map(m.insert(dc, _)) }
    }
  } yield cfls

  /*
   * Retrieves running units from the scheduler (per data center) and then converts to a map.
   */
  def getAllRunningUnits(cfg: NelsonConfig): Task[Datacenter ==>> Set[RunningUnit]] =
    cfg.datacenters.traverseM { dc : Datacenter =>
      scheduler.run(dc.interpreters.scheduler, scheduler.SchedulerOp.runningUnits(dc)).map(_.toList.map(dc -> _))
    }.map(_.zgroup(_._1).map(_.map(_._2)))

  /*
   * Deployment Status Association from RunningUnit -> Deployment Sets
   */
  implicit class RunningUnitStackNameSet(val s: Set[(RunningUnit, Deployment)]) extends AnyVal {
    def assoc[F[_]: Monad](implicit stoI: StoreOp ~> F) : F[StackName ==>> (RunningUnit, Deployment, DeploymentStatus)] =
      assocStatus[F, (RunningUnit, Deployment, ?), (RunningUnit, Deployment)](s)(_._2){ case ((ru, d), status) => (ru, d, status) }
  }

  /*
   * Deployment Status Association and exclusion of manual deployments from Deployment Sets
   */
  implicit class DeploymentSet(val s: Set[Deployment]) extends AnyVal {
    def assoc[F[_]: Monad](implicit sto: StoreOp ~> F): F[StackName ==>> DeploymentStatus] =
      assocStatus[F, Id, Deployment](s)(identity) { case (_, status) => status }

    def excludeManual : Set[Deployment] = s.filter(_.workflow != "manual")
  }

  // Allocation Associativity Types
  type RunningUnitAssoc[A, B] = (RunningUnit, A, B)
  type TaskAllocAssoc[T[_, _], A, B] = (T[A, B], TaskGroupAllocation)

  /*
   * Given a set of Running Units acquired from the scheduler, acquire all the conflicts between what is running
   * within the scheduler versus what is registered with Nelson.
   */
  def dcSchedulerConflicts(sch: Set[RunningUnit], dc: Datacenter)(implicit stoI: StoreOp ~> Task, schI: SchedulerOp ~> Task) : Task[Set[Conflict[Deployment]]] = for {
    // Get all routable deployments from nelson, and split running units based on StackName parseability
    nrd <- storage.run(stoI, storage.StoreOp.getRoutableDeploymentsByDatacenter(dc))

    spun <- Task.delay(for {
      ru <- sch
      n : String <- ru.name.toSet
      p <- StackName.parsePublic(n)
    } yield ru -> p)

    sxpu <- Task.delay(sch -- spun.map(_._1))

    spud <- assoc(spun)(stoI)                  // From all running units w/ parseable stack names: find deployments assoc
    spudns <- spud.assoc[Task]                 // get deployment status in Nelson and build a map structure

    // get all associated task statuses for running units also in Nelson and then partition jobs based on those which are periodic/invalid
    bst <- assocTaskStatus[Task, RunningUnitAssoc, Deployment, DeploymentStatus](dc, spudns)
    pbst <- Task.delay(partitionJobs(bst.keys.toSet.map((sn : StackName) => sn.toString)))
    malf <- Task.delay(pbst._2)
    peri <- Task.delay(pbst._1)

    beqs <- equivStatusOneShot[Task, RunningUnitAssoc, Deployment](
      bst.filterWithKey { case (sn, _) => !malf.contains(sn.toString) && !peri.keys.contains(sn) }
    )(_._3)

    peris <- equivStatusPeriodic[Task, RunningUnitAssoc, Deployment](
      bst.filterWithKey { case (sn, _) => peri.keys.contains(sn) }.map(_.toList)
    )(_._3)

    both <- Task.delay(nrd intersect spud.map(_._2))           // Deployments that are both in Nelson and associated with scheduler running units
    nonly <- Task.delay((nrd -- spud.map(_._2)).excludeManual) // Non-manual deployments known by Nelson but not running within scheduler
    sponly <- Task.delay(spun.map(_._1) -- spud.map(_._1))     // deployments that in the scheduler (w/ parseable stack names) but not known by Nelson
    nonlys <- nonly.assoc[Task]                                // Get all deployment statuses for Nelson only deployments

    // All deployments that are both in nelson and have corresponding running units within the scheduler, but with different statuses
    bothc <- Task.delay(beqs.filter(_.exists(x => !x._2)).map(_.filterNot(_._2)).values.flatMap(_.toList))
    bothcp <- Task.delay(peris.filter(_.exists(x => !x._2)).map(_.filterNot(_._2)).values.flatMap(_.toList))

    // After we apply a filter that drops deployments that are only expected in Nelson:
    // Compute all conflicts for each classification (those only in nelson, in scheduler but
    // expected/not-expected in nelson, and those running in both w/ conflicting statuses
    nelsonOnly = filterAlsoExpectedInScheduler(nonly, nonlys).map(d => nelson(d, nonlys.lookup(d.stackName)).only)

    nelsonNotExpected = sxpu.map(u => orphaned(u).nelsonNotExpected)
    nelsonExpected = sponly.map(u => orphaned(u).nelsonExpected)
    statusConflict = bothc.map{ case (((ru, d, ds), alloc), _) => nelson(d, Some(ds)).stateConflict(ru, alloc) } ++
      bothcp.map{ case (((ru, d, ds), alloc), _) => nelson(d, Some(ds)).stateConflict(ru, alloc) }
    malConflicts = malf.map(malformed)

  } yield nelsonOnly ++ nelsonExpected ++ nelsonNotExpected ++ statusConflict ++ malConflicts


  // By utilizing storage, derive all running-unit/deployment associations from running-unit/stack-name associations
  private def assoc[F[_]: Monad](s: Set[(RunningUnit, StackName)])(stoI: StoreOp ~> F) : F[Set[(RunningUnit, Deployment)]] =
    Monad[F].map(
      storage.run[F, List[(RunningUnit, Deployment)]](stoI, s.toList.traverseM { case (ru, sn) =>
        storage.StoreOp.findDeployment(sn).map(_.toList.map(ru -> _))
    }))(_.toSet)

  // Acquire Association Status Mappings
  private def assocStatus[F[_] : Monad, G[_], A](s: Set[A])(f: A => Deployment)(g: (A, DeploymentStatus) => G[DeploymentStatus])(implicit stoI: StoreOp ~> F) : F[StackName ==>> G[DeploymentStatus]] =
    storage.run(stoI, s.foldLeft(==>>.empty[StackName, G[DeploymentStatus]].point[storage.StoreOpF]) { case (op, a) =>
      op.flatMap { m =>
        storage.StoreOp.getDeploymentStatus(f(a).id).map(_.map(s => m.insert(f(a).stackName, g(a, s))) getOrElse m)
      }
    })

  private def assocTaskStatus[F[_]: Monad, T[_, _], A, B](dc: Datacenter, m: StackName ==>> T[A, B])(implicit schI: SchedulerOp ~> F): F[StackName ==>> Set[TaskAllocAssoc[T, A, B]]] =
    scheduler.run(schI, m.toList.foldLeft(==>>.empty[StackName, Set[TaskAllocAssoc[T, A, B]]].point[SchedulerOp.SchedulerF]) { case (op, (sn, t)) =>
      op.flatMap { m =>
        scheduler.SchedulerOp.allocations(dc).map(_.find(_.jobId == sn.toString).map { alloc =>
          m.insert(sn, Set(t -> alloc))
        } getOrElse m)
      }
    })

  import ScalazHelpers._

   /*
    * All One Shot Job Task allocation associations are checked for equivalency, and the map is re-constructed containing flags indicating if there is a match. As this
    * is a "parallel" check, we are treating all task statuses as at the same time.
    */
  private def equivStatusOneShot[F[_]: Monad, T[_, _], A](a: StackName ==>> Set[TaskAllocAssoc[T, A, DeploymentStatus]])
                                                         (f: T[A, DeploymentStatus] => DeploymentStatus)
                                                         (implicit schI: SchedulerOp ~> F) : F[StackName ==>> Set[(TaskAllocAssoc[T, A, DeploymentStatus], Boolean)]] =
    Monad[F].map(a.toList.foldLeft(List.empty[(StackName, Set[(TaskAllocAssoc[T, A, DeploymentStatus], Boolean)])].point[F]) { case (fm, (sn, s)) =>
      fm.flatMap { m =>
        Monad[F].map(s.foldLeft(Set.empty[(TaskAllocAssoc[T, A, DeploymentStatus], Boolean)].point[F]) { case (fs, (tads, alloc)) =>
          fs.flatMap { ss =>
            Monad[F].map(Monad[F].map(alloc.tasks.values.map(_._1).toList.toNel.map(nel =>
              scheduler.run(schI, scheduler.SchedulerOp.equivalentStatus(f(tads), NonEmptyList(nel.toSet))))
              .getOrElse(false.point[F]))((tads, alloc) -> _))(ss + _)
          }
        })(si => m :+ (sn -> si))
      }
    })(_.zgroup(_._1).map(_.flatMap(_._2)))

  // TODO: We can unify this with function above
  private def equivStatusPeriodic[F[_]: Monad, T[_, _], A](a: StackName ==>> List[TaskAllocAssoc[T, A, DeploymentStatus]])
                                                         (f: T[A, DeploymentStatus] => DeploymentStatus)
                                                         (implicit schI: SchedulerOp ~> F) : F[StackName ==>> List[(TaskAllocAssoc[T, A, DeploymentStatus], Boolean)]] =
    Monad[F].map(a.toList.foldLeft(List.empty[(StackName, List[(TaskAllocAssoc[T, A, DeploymentStatus], Boolean)])].point[F]) { case (fm, (sn, l)) =>
      fm.flatMap { m =>
        Monad[F].map(l.foldLeft(List.empty[(TaskAllocAssoc[T, A, DeploymentStatus], Boolean)].point[F]) { case (fs, (tads, alloc)) =>
          fs.flatMap { ss =>
            Monad[F].map(Monad[F].map(alloc.tasks.values.map(_._1).toList.toNel.map(nel =>
              scheduler.run(schI, scheduler.SchedulerOp.equivalentStatus(f(tads), nel.map(Set(_)))))
              .getOrElse(false.point[F]))((tads, alloc) -> _))(ss :+ _)
          }
        })(si => m :+ (sn -> si))
      }
    })(_.zgroup(_._1).map(_.flatMap(_._2).toList))


  val onlyExpectedInNelsonStatuses : Set[DeploymentStatus] =
    Set(Terminated, Unknown, Pending, Deploying, Failed, Warming)

  // Filter out deployments that we also expect to be in the scheduler (based on the Deployment Status).
  private def filterAlsoExpectedInScheduler(d: Set[Deployment], s: StackName ==>> DeploymentStatus) : Set[Deployment] =
    d.filterNot(dep => s.lookup(dep.stackName).exists(onlyExpectedInNelsonStatuses.contains))

  // Partition Jobs into all parent-child relationships and the set of all job names that are malformed periodic jobs
  // Note: Periodic Runs are sorted by the timestamps in decending order
  def partitionJobs(s: Set[String]) : (StackName ==>> List[PeriodicRun], Set[String]) = {
    val t = s.foldLeft((List.empty[(StackName, PeriodicRun)], Set.empty[String])) { case ((m, err), s) =>
      validatePeriodicRun(s) match {
        case Success(Some(pr)) => (m :+ (pr.parent -> pr), err)
        case Success(None) => (m, err)
        case Failure(e) => (m, err ++ e.list)
      }
    }

    // Group all periodic children based on their parent jobs and sort them by timestamp
    (t._1.zgroup(_._1).map(_.map(_._2).toList.sortBy(-_.timestamp)), t._2)
  }

  // Represents jobs that are run periodically.  Here we validate them.
  final case class PeriodicRun(parent: StackName, timestamp: Long, suffix: String)

  private def validatePeriodicRun(str: String) : ValidationNel[String, Option[PeriodicRun]] = {
    val PeriodicRunRegEx = """([^/]+)/periodic-([^.]+)\.?(.*)""".r

    str match {
      case PeriodicRunRegEx(parent, ts, suffix) => (StackName.validate(parent) |@|
        ts.parseLong.leftMap(_.getMessage).toValidationNel |@| suffix.successNel[String]) { (p, t, s) =>
          Some(PeriodicRun(p, t, s))
        }
      case _ => None.successNel
    }
  }

  val atomicSink : Sink[Task, DCDConflictLabeledMap] =
    Process.constant { conflicts => Task.delay {
        latestConflicts.set(conflicts)
        log.debug("Conflicts Updated.")
      }.handleWith { case t =>
        Task.delay(log.warn(s"unexpected error occurred whilst attempting to retain conflicts. ${t.getMessage}, cause: ${t.getCause}"))
      }
    }

  val counterSink : Sink[Task, DCDConflictLabeledMap] = Process.constant { m =>
    Task.delay(m.toList.foreach { case (dc, conflicts) =>
      conflicts.values.flatMap(_.toList).foreach {
        _.run match {
          case -\/(_) =>
            Metrics.default.reconciliationMalformedConflictCounter.labels(dc.name).inc()
          case \/-(conflictEntry) => conflictEntry.run match {
            case -\/(nelsonAware) => nelsonAware.run match {
              case -\/(_) => Metrics.default.reconciliationInNelsonOnlyCounter.labels(dc.name).inc()
              case \/-(_) => Metrics.default.reconciliationStateConflictCounter.labels(dc.name).inc()
            }
            case \/-(orphaned) => orphaned.run match {
              case -\/(_) => Metrics.default.reconciliationNelsonExpectedCounter.labels(dc.name).inc()
              case \/-(_) => Metrics.default.reconciliationNelsonNotExpectedCounter.labels(dc.name).inc()
            }
          }
        }
      }
    }).handleWith { case t =>
      Task.delay(log.warn(s"unexpected error occurred whilst attempting to update conflict counter. ${t.getMessage}, cause: ${t.getCause}"))
    }
  }
}

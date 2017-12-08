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
package monitoring

import helm.{ConsulOp, HealthStatus}
import journal.Logger
import Domain.{Deployment, TrafficShift}
import DeploymentStatus.{Ready, Warming}
import storage.{StoreOp, StoreOpF}
import java.time.Instant

import scalaz.{NonEmptyList, OptionT}
import scalaz.concurrent.Task
import scalaz.syntax.bind._
import scalaz.syntax.std.option._
import scalaz.syntax.traverse._
import scalaz.std.list._
import scalaz.stream.{Process, Sink, sink, time}
import helm.HealthStatus._
import helm.HealthStatus
import nelson.Json.DeploymentEncoder
import nelson.audit.AuditableInstances.deploymentAuditable
import nelson.Nelson._

import scala.concurrent.duration.Duration
import scala.concurrent.duration._
import scala.language.postfixOps

/*
 * Intended to be launched as a background daemon for monitoring and acting on deployment status/activity.
 */
object DeploymentMonitor {

  protected val log = Logger[this.type]

  sealed abstract class MonitorActionItem {
    def deployment: Deployment
  }

  final case class PromoteToReady(dc: Domain, deployment: Deployment) extends MonitorActionItem
  final case class RetainAsWarming(dc: Domain, deployment: Deployment, reason: String) extends MonitorActionItem

  def heartbeat(cfg: NelsonConfig): Process[Task, Duration] =
    (Process.eval(Task.now(1 seconds)) ++ Process.repeatEval(Task.delay(cfg.deploymentMonitor.delay))).flatMap(d =>
      time.awakeEvery(d)(cfg.pools.schedulingExecutor, cfg.pools.schedulingPool).once)
  /*
   * Creates a daemon that will decide all deployment monitor actions that need to occur, and drain them.
   */
  def loop(cfg: NelsonConfig): Process[Task, Unit] = drain(cfg)(heartbeat(cfg),
    lift[Process, Seq[MonitorActionItem]](monitorActionItems _ andThen Process.eval), counterSink,
    lift[Sink, MonitorActionItem](promotionSink)
  )

  /*
   * Drain all actions from the writer (using an auditor error sink, observing it and routing to a final sink.
   */
  def drain[A](cfg: NelsonConfig)(h: Process[Task, Duration], w: NelsonFK[Process, Seq[A]], s: Sink[Task, A], k: NelsonFK[Sink, A]): Process[Task, Unit] =
    h >> w.run(cfg).flatMap(Process.emitAll)
    .through(s.toChannel)
    .attempt()
    .observeW(cfg.auditor.errorSink)
    .stripW
    .to(k.run(cfg))

  /*
   * Build a list of MonitorActionItems based on the health of deployments that are presently in the Warming state.
   */
  def monitorActionItems(cfg: NelsonConfig): Task[List[MonitorActionItem]] =
    cfg.domains.traverseM(dc => monitorActionItemsByDomain(dc))

  def monitorActionItemsByDomain(dc: Domain): Task[List[MonitorActionItem]] =
    for {
      ns <- storage.run(dc.storage, StoreOp.listNamespacesForDomain(dc.name)).map(_.toList)
      d  <- ns.traverseM(n => monitorActionItemsByNamespace(dc,n))
    } yield d

  def monitorActionItemsByNamespace(dc: Domain, ns: Domain.Namespace): Task[List[MonitorActionItem]] =
    for {
      d  <- storage.run(dc.storage, StoreOp.listDeploymentsForNamespaceByStatus(ns.id, NonEmptyList(Warming)))
             .map(_.toList.map(_._1))
      ai <- d.traverse(d => monitorActionItem(dc, d))
    } yield ai


  /*
   * Validates deployment is reporting healthy in consul and no preceeding traffic shift are in progress;
   * this guards us from having multiple traffic shifts overlapping.
   *
   * Ask Helm that will for the list of all the health statuses, and determine if a majority of the jobs are passing.
   */
  def monitorActionItem(dc: Domain, d: Deployment): Task[MonitorActionItem] =
    for {
      health <- helm.run(dc.consul, health(d))
      shift  <- storage.run(dc.storage, trafficShift(d))
      next   <- storage.run(dc.storage, next(d))
    } yield {
      if (!majorityPassing(health))
        RetainAsWarming(dc, d, "The majority of all health status checks must be passing.")
      else if (shift.exists(_.inProgress(Instant.now)))
        RetainAsWarming(dc, d, "Traffic shift in progress, can not promote at this time.")
      else {
        if (next.contains(d))
          PromoteToReady(dc, d)
        else
          RetainAsWarming(dc, d, s"A previous deployment exists and will be promoted first.")
      }
      // Note: if the traffic shift is None the deployment will be promoted to ready.
      // This is intended for deployments that are not part of a traffic shift,
      // i.e. periodic jobs or bootstrapping a service
    }

  def majorityPassing(statuses: Option[List[HealthStatus]]) : Boolean =
    statuses.cata(l => l.count(_ == Passing) > l.count(_ != Passing), false)

  def health(d: Deployment): ConsulOp.ConsulOpF[Option[List[HealthStatus]]] =
    ConsulOp.healthCheckJson(d.stackName.toString).map(_.toOption)

  def trafficShift(d: Deployment): StoreOpF[Option[TrafficShift]] =
    StoreOp.getTrafficShiftForServiceName(d.nsid, d.unit.serviceName)

  // returns the next deployment in warming state that should be promoted to ready
  // this allows us to sequence traffic shifts in the order they were deployed
  // getDeploymentForServiceNameByStatus returns an ordered list of deployments
  def next(d: Deployment): StoreOpF[Option[Deployment]] =
    StoreOp.getDeploymentsForServiceNameByStatus(d.unit.serviceName, d.nsid, NonEmptyList(Warming)).map(_.reverse.headOption)

  val counterSink: Sink[Task, MonitorActionItem] =
    sink.lift { item => count(item) }

  def count(item: MonitorActionItem): Task[Unit] = {
    val task: Task[Unit] = item match {
      case PromoteToReady(dc, _) =>
        Task.delay(Metrics.default.deploymentMonitorReadyToPromote.labels(dc.name).inc())
      case RetainAsWarming(dc, d, r) =>
        Task.delay {
          Metrics.default.deploymentMonitorAwaitingHealth.labels(dc.name).inc()
          log.info(s"""Deployment "${d.stackName.toString}" not ready to promote to Ready. Reason: $r""")
        }
    }

    task.handleWith { case t =>
      warn(s"unexpected error occurred whilst attempting to update deployment monitor counters. ${t.getMessage}, cause: ${t.getCause}")
    }
  }

  def promotionSink(cfg: NelsonConfig): Sink[Task, MonitorActionItem] =
    sink.lift { item => promote(item)(cfg.auditor) }

  def promote(item: MonitorActionItem)(auditor: audit.Auditor): Task[Unit] = {
    val task = item match {
      case PromoteToReady(dc, d) =>
        val t = storage.run(dc.storage, promoteToReady(d))
        t >> auditor.write(d, audit.ReadyAction)
      case _ =>
        Task.now(())
    }

    task.handleWith { case t =>
      warn(s"unexpected error occurred whilst attempting to promote deployment. ${t.getMessage}, cause: ${t.getCause}")
    }
  }

  def promoteToReady(d: Deployment): StoreOpF[Unit] = {

    def startTrafficShift: StoreOpF[Unit] =
      (for {
        t  <- OptionT(StoreOp.getCurrentTargetForServiceName(d.nsid, d.unit.serviceName))
        id <- OptionT(StoreOp.startTrafficShift(from = t.deploymentTarget.id, to = d.id, start = Instant.now))
      } yield id).run.map(_ => ())

    def ready: StoreOpF[Unit] =
      StoreOp.createDeploymentStatus(d.id, Ready,
        Some(s"Promoting ${d.stackName} to ready."))

    startTrafficShift >> ready
  }

  private def warn(msg: String): Task[Unit] =
    Task.delay(log.warn(msg))
}

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

import nelson.Datacenter.{Deployment, TrafficShift,StackName}
import nelson.DeploymentStatus.{Ready, Warming}
import nelson.Json.DeploymentEncoder
import nelson.Nelson._
import nelson.audit.AuditableInstances.deploymentAuditable
import nelson.storage.{StoreOp, StoreOpF}
import nelson.health.{HealthCheckOp, Passing}

import cats.data.{NonEmptyList, OptionT}
import cats.effect.{Effect, IO}
import cats.implicits._
import nelson.CatsHelpers._

import fs2.{Scheduler, Sink, Stream}

import helm.HealthStatus._

import java.time.Instant

import journal.Logger

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

  final case class PromoteToReady(dc: Datacenter, deployment: Deployment) extends MonitorActionItem
  final case class RetainAsWarming(dc: Datacenter, deployment: Deployment, reason: String) extends MonitorActionItem

  def heartbeat(cfg: NelsonConfig): Stream[IO, Duration] =
    (Stream.eval(IO.pure(1 seconds)) ++ Stream.repeatEval(IO(cfg.deploymentMonitor.delay))).flatMap(d =>
      Scheduler.fromScheduledExecutorService(cfg.pools.schedulingPool)
        .awakeEvery(d)(Effect[IO], cfg.pools.schedulingExecutor).head)
  /*
   * Creates a daemon that will decide all deployment monitor actions that need to occur, and drain them.
   */
  def loop(cfg: NelsonConfig): Stream[IO, Unit] = drain(cfg)(heartbeat(cfg),
    lift[Stream, Seq[MonitorActionItem]](monitorActionItems _ andThen Stream.eval), counterSink,
    lift[Sink, MonitorActionItem](promotionSink)
  )

  /*
   * Drain all actions from the writer (using an auditor error sink, observing it and routing to a final sink.
   */
  def drain[A](cfg: NelsonConfig)(h: Stream[IO, Duration], w: NelsonFK[Stream, Seq[A]], s: Sink[IO, A], k: NelsonFK[Sink, A]): Stream[IO, Unit] =
    h *> w.run(cfg).flatMap(as => Stream.emits(as).covary[IO])
      .observe(s)(Effect[IO], cfg.pools.defaultExecutor)
      .attempt
      .observeW(cfg.auditor.errorSink)(Effect[IO], cfg.pools.defaultExecutor)
      .stripW
      .to(k.run(cfg))

  /*
   * Build a list of MonitorActionItems based on the health of deployments that are presently in the Warming state.
   */
  def monitorActionItems(cfg: NelsonConfig): IO[List[MonitorActionItem]] =
    cfg.datacenters.flatTraverse(dc => monitorActionItemsByDatacenter(dc))

  def monitorActionItemsByDatacenter(dc: Datacenter): IO[List[MonitorActionItem]] =
    for {
      ns <- StoreOp.listNamespacesForDatacenter(dc.name).foldMap(dc.storage).map(_.toList)
      d  <- ns.flatTraverse(n => monitorActionItemsByNamespace(dc,n))
    } yield d

  def monitorActionItemsByNamespace(dc: Datacenter, ns: Datacenter.Namespace): IO[List[MonitorActionItem]] =
    for {
      d  <- StoreOp.listDeploymentsForNamespaceByStatus(ns.id, NonEmptyList.of(Warming)).foldMap(dc.storage)
             .map(_.toList.map(_._1))
      ai <- d.traverse(d => monitorActionItem(dc, d))
    } yield ai


  /*
   * Validates deployment is reporting healthy in consul and no preceeding traffic shift are in progress;
   * this guards us from having multiple traffic shifts overlapping.
   *
   * Ask Helm that will for the list of all the health statuses, and determine if a majority of the jobs are passing.
   */
  def monitorActionItem(dc: Datacenter, d: Deployment): IO[MonitorActionItem] =
    for {
      hcs    <- getHealth(dc, d.namespace.name, d.stackName).foldMap(dc.health)
      shift  <- trafficShift(d).foldMap(dc.storage)
      next   <- next(d).foldMap(dc.storage)
    } yield {
      if (!majorityPassing(hcs))
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

  def majorityPassing(statuses: List[health.HealthStatus]) : Boolean = {
    val healths = statuses.map(_.status)
    healths.count(_ == Passing) > healths.count(_ != Passing)
  }

  def getHealth(dc: Datacenter, ns: NamespaceName, sn: StackName): HealthCheckOp.HealthCheckF[List[health.HealthStatus]] =
    HealthCheckOp.health(dc, ns, sn)

  def trafficShift(d: Deployment): StoreOpF[Option[TrafficShift]] =
    StoreOp.getTrafficShiftForServiceName(d.nsid, d.unit.serviceName)

  // returns the next deployment in warming state that should be promoted to ready
  // this allows us to sequence traffic shifts in the order they were deployed
  // getDeploymentForServiceNameByStatus returns an ordered list of deployments
  def next(d: Deployment): StoreOpF[Option[Deployment]] =
    StoreOp.getDeploymentsForServiceNameByStatus(d.unit.serviceName, d.nsid, NonEmptyList.of(Warming)).map(_.reverse.headOption)

  val counterSink: Sink[IO, MonitorActionItem] =
    Sink { item => count(item) }

  def count(item: MonitorActionItem): IO[Unit] = {
    val task: IO[Unit] = item match {
      case PromoteToReady(dc, _) =>
        IO(Metrics.default.deploymentMonitorReadyToPromote.labels(dc.name).inc())
      case RetainAsWarming(dc, d, r) =>
        IO {
          Metrics.default.deploymentMonitorAwaitingHealth.labels(dc.name).inc()
          log.info(s"""Deployment "${d.stackName.toString}" not ready to promote to Ready. Reason: $r""")
        }
    }

    task.recoverWith { case t =>
      warn(s"unexpected error occurred whilst attempting to update deployment monitor counters. ${t.getMessage}, cause: ${t.getCause}")
    }
  }

  def promotionSink(cfg: NelsonConfig): Sink[IO, MonitorActionItem] =
    Sink(item => promote(item)(cfg.auditor))

  def promote(item: MonitorActionItem)(auditor: audit.Auditor): IO[Unit] = {
    val task = item match {
      case PromoteToReady(dc, d) =>
        val t = promoteToReady(d).foldMap(dc.storage)
        t *> auditor.write(d, audit.ReadyAction)
      case _ =>
        IO.unit
    }

    task.recoverWith { case t =>
      warn(s"unexpected error occurred whilst attempting to promote deployment. ${t.getMessage}, cause: ${t.getCause}")
    }
  }

  def promoteToReady(d: Deployment): StoreOpF[Unit] = {

    def startTrafficShift: StoreOpF[Unit] =
      (for {
        t  <- OptionT(StoreOp.getCurrentTargetForServiceName(d.nsid, d.unit.serviceName))
        id <- OptionT(StoreOp.startTrafficShift(from = t.deploymentTarget.id, to = d.id, start = Instant.now))
      } yield id).value.map(_ => ())

    def ready: StoreOpF[Unit] =
      StoreOp.createDeploymentStatus(d.id, Ready,
        Some(s"Promoting ${d.stackName} to ready."))

    startTrafficShift *> ready
  }

  private def warn(msg: String): IO[Unit] =
    IO(log.warn(msg))
}

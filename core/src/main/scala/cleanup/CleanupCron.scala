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
package cleanup

import nelson.Datacenter.Deployment
import nelson.routing.RoutingTable

import cats.effect.{Effect, IO}
import cats.implicits._
import nelson.CatsHelpers._

import fs2.{Scheduler, Stream}

import java.time.Instant

import journal.Logger

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.Duration
import scala.concurrent.duration._

final case class DeploymentCtx(deployment: Deployment, status: DeploymentStatus, exp: Option[Instant])

object CleanupCron {
  import nelson.storage.{StoreOp,StoreOpF}
  import nelson.Datacenter.Namespace

  private val log = Logger[CleanupCron.type]

  // exclude Terminated deployments because they don't need to be cleaned up
  private val status =
    DeploymentStatus.all.toList.filter(_ != DeploymentStatus.Terminated).toNel.yolo("can't be empty")

  private val routables = DeploymentStatus.routable.toList

  /* Gets all deployments (exluding terminated) and routing graph for a namespace */
  def getDeploymentsForNamespace(ns: Namespace): StoreOpF[Vector[(Namespace,DeploymentCtx)]] =
    for {
      dps <- StoreOp.listDeploymentsForNamespaceByStatus(ns.id, status)
      dcx <- dps.toVector.traverse {
        case (d,s) => StoreOp.findDeploymentExpiration(d.id).map(DeploymentCtx(d,s,_))
      }
    } yield dcx.map(d => (ns,d))

  /* Gets all deployment (excluding terminated) and routing graph for each namespace within a Datacenter. */
  def getDeploymentsForDatacenter(dc: Datacenter): StoreOpF[Vector[CleanupRow]] =
    for {
      _  <- log.debug(s"cleanup cron running for ${dc.name}").pure[StoreOpF]
      ns <- StoreOp.listNamespacesForDatacenter(dc.name)
      gr <- RoutingTable.generateRoutingTables(dc.name)
      ds <- ns.toVector.flatTraverse(ns => getDeploymentsForNamespace(ns))
    } yield ds.flatMap { case (ns, dcx) => gr.find(_._1 == ns).map(x => (dc,ns,dcx,x._2)) }

  /* Gets all deployments (excluding terminated) for all datacenters and namespaces */
  def getDeployments(cfg: NelsonConfig): IO[Vector[CleanupRow]] =
    cfg.datacenters.toVector.flatTraverse(dc => getDeploymentsForDatacenter(dc)).foldMap(cfg.storage)

  def routable(d: DeploymentCtx): Boolean =
    routables.exists(_ == d.status)

  def process(cfg: NelsonConfig): Stream[IO, CleanupRow] =
     Stream.eval(getDeployments(cfg)).flatMap(rs => Stream.emits(rs).covary[IO])
       .map(a => if (routable(a._3)) Right(a) else Left(a))
       .throughO(ExpirationPolicyProcess.expirationProcess(cfg)) // only apply expiration policy to the rhs
       .map(_.fold(identity, identity))
       .filter(x => GarbageCollector.expired(x._3)) // mark only expired deployments
       .through(GarbageCollector.mark(cfg))

  def refresh(cfg: NelsonConfig): Stream[IO,Duration] =
    Stream.repeatEval(IO(cfg.cleanup.cleanupDelay)).flatMap(d =>
      Scheduler.fromScheduledExecutorService(cfg.pools.schedulingPool).awakeEvery[IO](d)(Effect[IO], cfg.pools.schedulingExecutor).head)

  /*
   * This is the entry point for the cleanup pipeline. The pipeline is run at
   * a predetermined cadence and is responsible for expiration policy management,
   * marking deploymnets as garbage and running the destroy workflow
   * to decommission deployments.
   */
  def pipeline(cfg: NelsonConfig)(implicit ec: ExecutionContext): Stream[IO, Unit] =
    (refresh(cfg) *> process(cfg).attempt).observeW(cfg.auditor.errorSink).stripW.to(Reaper.reap(cfg))
}

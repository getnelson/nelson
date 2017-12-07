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

import Manifest.{Versioned,UnitDef,Loadbalancer,ActionConfig}
import cleanup.ExpirationPolicy
import notifications.Notify
import Metrics.default.{deploySuccessCounter,deployFailureCounter}
import scalaz.concurrent.Task
import scalaz._, Scalaz._
import Workflow.WorkflowOp
import storage.{StoreOp, StoreOpF, run => runs}
import java.time.Instant
import io.prometheus.client.Counter

/**
 * An Actionable is something that can be "acted" upon in the context
 * of a domain and namespace. In Nelson this typically means deploying
 * into a domain using a scheduler such as Nomad, or interacting with
 * Aws to launch some infrastructure.
 */
trait Actionable[A] {
  def action(a: A): Kleisli[Task, (NelsonConfig, ActionConfig), Unit]
}

object Actionable {

  def apply[A](implicit A: Actionable[A]): Actionable[A] = A

  /*
   * A Versioned UnitDef Actionable runs a prescribed Workflow, which will
   * launch the unit into a given domain using a scheduler such as
   * Nomad.
   */
  implicit val UnitDefActionable = new Actionable[UnitDef @@ Versioned] {
    import Manifest.{Namespace,Plan}

    def create(u: UnitDef @@ Versioned, dc: Domain, ns: Namespace, plan: Plan, hash: String, exp: Instant, fallback: ExpirationPolicy): StoreOpF[Option[ID]] = {
      val unit = Manifest.Versioned.unwrap(u)
      val version = u.version
      val policy = Manifest.getExpirationPolicy(unit, plan) getOrElse fallback
      (for {
         u  <- OptionT(StoreOp.getUnit(unit.name, version))
         n  <- OptionT(StoreOp.getNamespace(dc.name, ns.name)) // gets a Datacetner.Namespace
         id <- StoreOp.createDeployment(u.id, hash, n, unit.workflow.name, plan.name, policy.name).liftM[OptionT]
         _  <- plan.environment.resources.map { case (name, uri) =>
                StoreOp.createDeploymentResource(id, name, uri)
               }.toList.sequence.liftM[OptionT]
         _  <- StoreOp.createDeploymentExpiration(id, exp).liftM[OptionT]
       } yield id).run
    }

    def action(unit: UnitDef @@ Versioned): Kleisli[Task, (NelsonConfig,ActionConfig), Unit] =
      Kleisli { case (cfg, actionConfig) =>
        val unitw = Manifest.Versioned.unwrap(unit)
        val ttl = cfg.cleanup.initialTTL.toSeconds
        val exp = Instant.now.plusSeconds(ttl)
        val plan = actionConfig.plan
        val dc = actionConfig.domain
        val ns = actionConfig.namespace
        val hash = actionConfig.hash
        val policy =
          if (Manifest.isPeriodic(unitw, plan))
            cfg.expirationPolicy.defaultPeriodic
          else
            cfg.expirationPolicy.defaultNonPeriodic

        def incCounter(c: Counter): Task[Unit] = Task.delay {
          c.labels(ns.name.asString).inc()
          ()
        }

        def deploy(id: ID)(t: WorkflowOp ~> Task): Task[Throwable \/ Unit] =
          Workflow.run(unitw.workflow.deploy(id, hash, unit, plan, dc, ns))(t).attempt.flatMap(_.fold(
            e => (Workflow.run(Workflow.syntax.status(id, DeploymentStatus.Failed,
                    s"workflow failed because: ${e.getMessage}"))(t) >>
                  incCounter(deployFailureCounter)).attempt,
            s => (Notify.sendDeployedNotifications(unit, actionConfig)(cfg) >>
                  incCounter(deploySuccessCounter)).attempt
        ))

        storage.run(cfg.storage, create(unit, dc, ns, plan, hash, exp, policy)).flatMap(_.cata(
          some = id => deploy(id)(dc.workflow).map(_ => ()),
          none = incCounter(deployFailureCounter)
        ))
      }
  }

  /*
   * A Versioned Loadbalancer Actionable interacts with Aws to launch all the
   * infrastructure needed to loadbalance / proxy outbound traffic into a domain.
   */
  implicit val LoadbalancerActionable = new Actionable[Loadbalancer @@ Versioned] {

    import loadbalancers.LoadbalancerOp
    import Domain.StackName
    import Manifest.Route

    def action(lbv: Loadbalancer @@ Versioned): Kleisli[Task, (NelsonConfig,ActionConfig), Unit] =
      Kleisli { case (cfg, actionConfig) =>
        val lb = Manifest.Versioned.unwrap(lbv)
        val major = lbv.version.toMajorVersion
        val plan = actionConfig.plan
        val dc = actionConfig.domain
        val ns = actionConfig.namespace
        val hash = actionConfig.hash
        val sn = StackName(lb.name, major.minVersion, hash)

        def launch(id: ID, sn: StackName, rs: Vector[Route], nsid: ID)(t: LoadbalancerOp ~> Task): Task[Unit] =
          for {
            _   <- helm.run(dc.consul, loadbalancers.writeLoadbalancerV2ConfigToConsul(sn, rs))
            dns <- loadbalancers.run(t, loadbalancers.launch(lb, major, dc, ns.name, plan, hash))
            _   <- runs(dc.storage, StoreOp.insertLoadbalancerDeployment(id, nsid, hash, dns))
          } yield ()

        def resize(lbd: Domain.LoadbalancerDeployment)(t: LoadbalancerOp ~> Task): Task[Unit] =
          loadbalancers.run(t, loadbalancers.resize(lbd, plan))

        for {
          t   <- dc.loadbalancer.tfold(FailedLoadbalancerDeploy(lb.name, s"domain ${dc.name} is not configured to deploy loadbalancers"))(identity)
          n   <- runs(cfg.storage, StoreOp.getNamespace(dc.name, ns.name))
          nsd <- n.tfold(FailedLoadbalancerDeploy(lb.name, s"namespace ${ns.name.asString} was not found for ${dc.name}"))(identity)
          i   <- runs(cfg.storage, StoreOp.getLoadbalancer(lb.name, major)).map(_.map(_.id))
          id  <- i.tfold(FailedLoadbalancerDeploy(lb.name, s"loadbalancer ${lb.name} was not found for major version ${major}"))(identity)
          lbd <- runs(cfg.storage, StoreOp.findLoadbalancerDeployment(lb.name, major, nsd.id))

          // if loadbalancer exists resize, otherwise create
          _   <- lbd.cata(l => resize(l)(t), launch(id, sn, lb.routes, nsd.id)(t))
        } yield ()
      }
  }
}

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

import nelson.Manifest.{Versioned,UnitDef,Loadbalancer,ActionConfig}
import nelson.Metrics.default.{deploySuccessCounter,deployFailureCounter}
import nelson.Workflow.WorkflowOp
import nelson.cleanup.ExpirationPolicy
import nelson.notifications.Notify
import nelson.storage.{StoreOp, StoreOpF}

import cats.~>
import cats.data.{Kleisli, OptionT}
import cats.effect.IO
import cats.implicits._

import io.prometheus.client.Counter

import java.time.Instant

/**
 * An Actionable is something that can be "acted" upon in the context
 * of a datacenter and namespace. In Nelson this typically means deploying
 * into a datacenter using a scheduler such as Nomad, or interacting with
 * Aws to launch some infrastructure.
 */
trait Actionable[A] {
  def action(a: A): Kleisli[IO, (NelsonConfig, ActionConfig), Unit]
}

object Actionable {

  def apply[A](implicit A: Actionable[A]): Actionable[A] = A

  /*
   * A Versioned UnitDef Actionable runs a prescribed Workflow, which will
   * launch the unit into a given datacenter using a scheduler such as
   * Nomad or Kubernetes.
   */
  implicit val UnitDefActionable = new Actionable[UnitDef @@ Versioned] {
    import Manifest.{Namespace,Plan}

    def create(u: UnitDef @@ Versioned, dc: Datacenter, ns: Namespace, plan: Plan, hash: String, exp: Instant, fallback: ExpirationPolicy): StoreOpF[Option[ID]] = {
      val unit = Manifest.Versioned.unwrap(u)
      val version = u.version
      val policy = Manifest.getExpirationPolicy(unit, plan) getOrElse fallback
      (for {
         u  <- OptionT(StoreOp.getUnit(unit.name, version))
         n  <- OptionT(StoreOp.getNamespace(dc.name, ns.name)) // gets a Datacetner.Namespace
         id <- OptionT.liftF(StoreOp.createDeployment(u.id, hash, n, plan.environment.workflow.name, plan.name, policy.name))
         _  <- OptionT.liftF(plan.environment.resources.map { case (name, uri) =>
                StoreOp.createDeploymentResource(id, name, uri)
               }.toList.sequence)
         _  <- OptionT.liftF(StoreOp.createDeploymentExpiration(id, exp))
       } yield id).value
    }

    def action(unit: UnitDef @@ Versioned): Kleisli[IO, (NelsonConfig,ActionConfig), Unit] =
      Kleisli { case (cfg, actionConfig) =>
        val unitw = Manifest.Versioned.unwrap(unit)
        val ttl = cfg.cleanup.initialTTL.toSeconds
        val exp = Instant.now.plusSeconds(ttl)
        val plan = actionConfig.plan
        val dc = actionConfig.datacenter
        val ns = actionConfig.namespace
        val hash = actionConfig.hash
        val policy =
          if (Manifest.isPeriodic(unitw, plan))
            cfg.expirationPolicy.defaultPeriodic
          else
            cfg.expirationPolicy.defaultNonPeriodic

        def incCounter(c: Counter): IO[Unit] = IO {
          c.labels(ns.name.asString).inc()
          ()
        }

        def deploy(id: ID)(t: WorkflowOp ~> IO): IO[Either[Throwable, Unit]] =
          plan.environment.workflow.deploy(id, hash, unit, plan, dc, ns).foldMap(t).attempt.flatMap(_.fold(
            e => (Workflow.syntax.status(id, DeploymentStatus.Failed,
                    s"workflow failed because: ${e.getMessage}").foldMap(t) *>
                  incCounter(deployFailureCounter)).attempt,
            s => (Notify.sendDeployedNotifications(unit, actionConfig)(cfg) *>
                  incCounter(deploySuccessCounter)).attempt
        ))

        create(unit, dc, ns, plan, hash, exp, policy).foldMap(cfg.storage).flatMap(_.fold(incCounter(deployFailureCounter)) { id =>
          deploy(id)(dc.workflow).map(_ => ())
        })
      }
  }

  /*
   * A Versioned Loadbalancer Actionable interacts with Aws to launch all the
   * infrastructure needed to loadbalance / proxy outbound traffic into a datacenter.
   */
  implicit val LoadbalancerActionable = new Actionable[Loadbalancer @@ Versioned] {

    import loadbalancers.LoadbalancerOp
    import Datacenter.StackName
    import Manifest.Route

    def action(lbv: Loadbalancer @@ Versioned): Kleisli[IO, (NelsonConfig,ActionConfig), Unit] =
      Kleisli { case (cfg, actionConfig) =>
        val lb = Manifest.Versioned.unwrap(lbv)
        val major = lbv.version.toMajorVersion
        val plan = actionConfig.plan
        val dc = actionConfig.datacenter
        val ns = actionConfig.namespace
        val hash = actionConfig.hash
        val sn = StackName(lb.name, major.minVersion, hash)

        def launch(id: ID, sn: StackName, rs: Vector[Route], nsid: ID)(t: LoadbalancerOp ~> IO): IO[Unit] =
          for {
            _   <- helm.run(dc.consul, loadbalancers.writeLoadbalancerV2ConfigToConsul(sn, rs))
            dns <- loadbalancers.launch(lb, major, dc, ns.name, plan, hash).foldMap(t)
            _   <- StoreOp.insertLoadbalancerDeployment(id, nsid, hash, dns).foldMap(dc.storage)
          } yield ()

        def resize(lbd: Datacenter.LoadbalancerDeployment)(t: LoadbalancerOp ~> IO): IO[Unit] =
          loadbalancers.resize(lbd, plan).foldMap(t)

        for {
          t   <- dc.loadbalancer.tfold(FailedLoadbalancerDeploy(lb.name, s"datacenter ${dc.name} is not configured to deploy loadbalancers"))(identity)
          n   <- StoreOp.getNamespace(dc.name, ns.name).foldMap(cfg.storage)
          nsd <- n.tfold(FailedLoadbalancerDeploy(lb.name, s"namespace ${ns.name.asString} was not found for ${dc.name}"))(identity)
          i   <- StoreOp.getLoadbalancer(lb.name, major).foldMap(cfg.storage).map(_.map(_.id))
          id  <- i.tfold(FailedLoadbalancerDeploy(lb.name, s"loadbalancer ${lb.name} was not found for major version ${major}"))(identity)
          lbd <- StoreOp.findLoadbalancerDeployment(lb.name, major, nsd.id).foldMap(cfg.storage)

          // if loadbalancer exists resize, otherwise create
          _   <- lbd.fold(launch(id, sn, lb.routes, nsd.id)(t))(l => resize(l)(t))
        } yield ()
      }
  }
}

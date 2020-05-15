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

import nelson.Datacenter.{Deployment}
import nelson.Manifest.{UnitDef,Versioned,Plan,AlertOptOut}
import nelson.docker.Docker.Image
import nelson.docker.DockerOp
import nelson.logging.LoggingOp
import nelson.scheduler.SchedulerOp
import nelson.storage.{StoreOp}
import nelson.vault.{Vault,policies}
import nelson.blueprint.Render

import cats.data.{EitherK, OptionT}
import cats.free.Free
import cats.implicits._

import helm.ConsulOp

import scala.concurrent.duration.FiniteDuration

/**
 * Workflows must be defined in terms of a particular type of UnitDef
 * they target, and an output of the workflow. By having a workflow
 * specilized to a particular type of unit, we avoid having to have
 * 'uber workflows' that need to handle every possible type of unit,
 * which reduces the implementation complexity of a given workflow quite
 * considerably. In addition, the output type `O` is intended to provide
 * an opertunity for the workflow to output something more than just
 * effects. For example, a workflow could accumulate a set of logs, or
 * some reporting state - whatever - it doesnt matter.
 *
 * Workflows define both setup (deploy) and teardown (detroy) workflow
 */
trait Workflow[O] {
  def name: WorkflowRef
  def deploy(id: ID, hash: String, unit: UnitDef @@ Versioned, p: Plan, dc: Datacenter, ns: Manifest.Namespace): Workflow.WorkflowF[O]
  def destroy(d: Deployment, dc: Datacenter, ns: Datacenter.Namespace): Workflow.WorkflowF[O]
}

object Workflow {

  // There's currently only two workflow implementation.
  val workflows = List(
    Magnetar, // docker + nomad + vault + prometheus
    Canopus,  // docker + kubernetes
    Pulsar    // docker + kubernetes + vault
  )

  def fromString(s: String): Option[Workflow[Unit]] =
    workflows.find(_.name == s)

  /*
   * A Workflow is the Coproduct of:
   * DockerOp, ConsulOp, Vault, LoggingOp, FailureOp, StorageOp and SchedulerOp
   */
  type Op0[A] = EitherK[DockerOp, ConsulOp, A]

  type Op1[A] = EitherK[LoggingOp, Op0,A]

  type Op2[A] = EitherK[StoreOp, Op1, A]

  type Op3[A] = EitherK[WorkflowControlOp, Op2,A]

  type Op4[A] = EitherK[Vault, Op3, A]

  type WorkflowOp[A] = EitherK[SchedulerOp, Op4, A]

  type WorkflowF[A] = Free[WorkflowOp, A]

  object syntax {
    import docker.Docker
    import Datacenter.StackName
    import Datacenter.ServiceName
    import Docker.RegistryURI
    import routing.{RoutingTable,Discovery}

    def pure[A](a: => A): WorkflowF[A] =
      WorkflowControlOp.pure(a).inject

    def launch(i: Image, dc: Datacenter, ns: NamespaceName, u: UnitDef @@ Versioned, p: Plan, hash: String, bp: RenderedBlueprint): WorkflowF[String] =
      SchedulerOp.launch(i, dc, ns, u, p, hash, bp).inject

    def delete(dc: Datacenter, d: Deployment): WorkflowF[Unit] =
      SchedulerOp.delete(dc,d).inject

    def logToFile(id: ID, msg: String): WorkflowF[Unit] =
      LoggingOp.logToFile(id, msg).inject

    def debug(msg: String): WorkflowF[Unit] =
      LoggingOp.debug(msg).inject

    def info(msg: String): WorkflowF[Unit] =
      LoggingOp.info(msg).inject

    def status(id: ID, s: DeploymentStatus, msg: String): WorkflowF[Unit] =
      logToFile(id, msg) *> StoreOp.createDeploymentStatus(id, s ,Some(msg)).inject

    def fail[A](t: Throwable): WorkflowF[A] =
      WorkflowControlOp.fail(t).inject

    def fail[A](reason: String): WorkflowF[A] =
      fail(new RuntimeException(reason))

    def handleBlueprint(id: ID, img: Image, dc: Datacenter, ns: NamespaceName,
      u: UnitDef, v: Version, p: Plan, hash: String): WorkflowF[RenderedBlueprint] = {
      val env = Render.makeEnv(img, dc, ns, u, v, p, hash)

      p.environment.blueprint match {
        case Some(Left(_)) =>
          fail("Internal error occured: un-hydrated blueprint passed to scheduler!")
        case Some(Right(bp)) =>
          val rendered = bp.template.render(env)
          StoreOp.updateDeploymentBlueprint(id, Option(rendered)).map(_ => rendered).inject
        case None =>
          fail("Nelson no longer supports deployment without a blueprint specified; please adjust your manifest and re-submit.")
      }
    }

    def deleteFromConsul(key: String): WorkflowF[Unit] =
      ConsulOp.kvDelete(key).inject

    def deleteDiscoveryInfoFromConsul(sn: StackName): WorkflowF[Unit] =
      deleteFromConsul(routing.Discovery.consulDiscoveryKey(sn))

    def deleteAlertsFromConsul(sn: StackName): WorkflowF[Unit] =
      alerts.deleteFromConsul(sn).inject

    def writeAlertsToConsul(sn: StackName, ns: NamespaceName, p: PlanRef, a: UnitDef, outs: List[AlertOptOut]): WorkflowF[Option[String]] =
      alerts.writeToConsul(sn,ns,p,a,outs).inject

    def writePolicyToVault(cfg: PolicyConfig, sn: StackName, ns: NamespaceName, rs: Set[String]): WorkflowF[Unit] =
      policies.createPolicy(cfg, sn, ns, rs).inject

    def deletePolicyFromVault(sn: StackName, ns: NamespaceName): WorkflowF[Unit] =
      policies.deletePolicy(sn, ns).inject

    /*
     * Here we are essentially doing the equivilent of the following vault
     * CLI execution:
     *
     * vault write auth/<cluster>/role/<stackname> \
     *   bound_service_account_names=<stackname> \
     *   bound_service_account_namespaces=<namespace> \
     *   policies=<stackname>
     */
    def writeKubernetesRoleToVault(dc: Datacenter, sn: StackName, ns: NamespaceName): WorkflowF[Unit] =
      Vault.createKubernetesRole(
        authClusterName = dc.name,
        roleName = sn.toString,
        serviceAccountNames = List(sn.toString),
        seviceAccountNamespaces = List(ns.asString),
        defaultLeaseTTL = None,
        maxLeaseTTL = None,
        policies = Some(List(policies.policyName(sn, ns)))
      ).inject

    def deleteKubernetesRoleFromVault(dc: Datacenter, sn: StackName): WorkflowF[Unit] =
      Vault.deleteKubernetesRole(
        authClusterName = dc.name,
        roleName = sn.toString
      ).inject

    private def pkiPath(pkiPath: Option[String], ns: NamespaceName) = {
      val path = pkiPath.getOrElse("pki")
      path.replaceAllLiterally("%env%", ns.root.asString)
    }

    def writePKIRoleToVault(dc: Datacenter, sn: StackName, ns: NamespaceName): WorkflowF[Unit] = {
      val interpolatedPkiPath = pkiPath(dc.policy.pkiPath, ns)
      Vault.createPKIRole(
        engineName = dc.name,
        roleName = sn.toString,
        serviceAccountNames = List(sn.toString),
        defaultLeaseTTL = None,
        maxLeaseTTL = None,
        allowLocalhost = false,
        pkiPath = interpolatedPkiPath
      ).inject
    }

    def deletePKIRoleFromVault(dc: Datacenter, sn: StackName, ns: NamespaceName): WorkflowF[Unit] = {
      val interpolatedPkiPath = pkiPath(dc.policy.pkiPath, ns)
      Vault.deletePKIRole(
        engineName = dc.name,
        roleName = sn.toString,
        pkiPath = interpolatedPkiPath
      ).inject
    }

    def writeDiscoveryToConsul(id: ID, sn: StackName, ns: NamespaceName, dc: Datacenter): WorkflowF[Unit] =
      for {
        d  <- StoreOp.getDeployment(id).inject[WorkflowOp]
        rg <- RoutingTable.outgoingRoutingGraph(d).inject[WorkflowOp]
        dt  = Discovery.discoveryTable(routing.RoutingNode(d), rg)
        _  <- Discovery.writeDiscoveryInfoToConsul(ns, sn, dc.domain.name, dt).inject[WorkflowOp]
      } yield ()

    /**
     * Provides a way to see if creating a traffic shift is actually relevant. In the case
     * of periodic units, shifting traffic would make no sense, so those are NoOps.
     */
    def getTrafficShift(plan: Plan, d: Datacenter): Option[Manifest.TrafficShift] =
      if (!Manifest.isPeriodic(plan))
        Option(plan.environment.trafficShift
                .getOrElse(Manifest.TrafficShift(d.defaultTrafficShift.policy, d.defaultTrafficShift.duration)))
      else None

    def createTrafficShift(id: ID, nsRef: NamespaceName, dc: Datacenter, p: TrafficShiftPolicy, dur: FiniteDuration): WorkflowF[Unit] = {
      val prog = for {
        ns   <- OptionT(StoreOp.getNamespace(dc.name, nsRef))
        to   <- OptionT(StoreOp.getDeployment(id).map(Option(_)))
        sn    = ServiceName(to.unit.name, to.unit.version.toFeatureVersion)
        _    <- OptionT(StoreOp.createTrafficShift(ns.id, to, p, dur).map(Option(_)))
      } yield ()

      prog.value.inject[WorkflowOp].map(_ => ())
    }

    /*
     * When the workflow is completed, we typically want to set the deployment to "Warming", so that once
     * consul indicates the deployment to be passing the health check, we can promote to "Ready" (via the
     * DeploymentMonitor background process).  However, units without ports are not registered in consul, and
     * thus we should immediately advance mark the deployment as "Ready".  Once Reconciliation is also used as
     * a gating factor for promoting deployments to "Ready", we can potentially set all units to "Warming" here.
     */
    def getStatus(unit: UnitDef, plan: Plan):  DeploymentStatus =
      if (Manifest.isPeriodic(plan)) DeploymentStatus.Ready
      else unit.ports.fold[DeploymentStatus](DeploymentStatus.Ready)(_ => DeploymentStatus.Warming)

    def dockerOps(id: ID, unit: UnitDef, registry: RegistryURI): WorkflowF[Image] = {
      import Docker.Pull.{Error => PullError}
      import Docker.Push.{Error => PushError}

      /* janky way to detect if docker has failed */
      def handleDockerLogs[B](x: (Int, List[B]))(f: PartialFunction[B,B]): WorkflowF[Unit] = {
        val (code, logs) = x
        val errors = logs.collect(f)
        if (code == 0 && errors.isEmpty) pure(())
        else {
          val msg = errors.map(_.toString).mkString(", ")
          fail(s"docker failed with exit code: $code and reason: $msg")
        }
      }

      for {
        i <- DockerOp.extract(unit).inject[WorkflowOp]
        _ <- status(id, DeploymentStatus.Deploying, s"replicating ${i.toString} to remote registry $registry")
        a <- DockerOp.pull(i).inject[WorkflowOp]
        _ <- a._2.traverse(out => logToFile(id, out.asString))
        _ <- handleDockerLogs(a) { case e: PullError => e }
        b <- DockerOp.tag(i, registry).inject[WorkflowOp]
        c <- DockerOp.push(b._2).inject[WorkflowOp]
        _ <- c._2.traverse(out => logToFile(id, out.asString))
        _ <- handleDockerLogs(c) { case e: PushError => e }
      } yield b._2
    }

    def vaultLoggingFields(sn: Datacenter.StackName, ns: NamespaceName, dcName: String): String =
      s"namespace=${ns} unit=${sn.serviceType} policy=${vault.policies.policyName(sn, ns)} datacenter=${dcName}"
  }
}

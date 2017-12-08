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

import scala.concurrent.duration.FiniteDuration
import scalaz.concurrent.Task
import scalaz._, Scalaz._
import Manifest.{UnitDef,Versioned,Plan,AlertOptOut}
import Domain.{Deployment}
import docker.Docker.Image
import storage.{StoreOp}
import docker.DockerOp
import helm.ConsulOp
import logging.LoggingOp
import scheduler.SchedulerOp
import vault.Vault

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
  def deploy(id: ID, hash: String, unit: UnitDef @@ Versioned, p: Plan, dc: Domain, ns: Manifest.Namespace): Workflow.WorkflowF[O]
  def destroy(d: Deployment, dc: Domain, ns: Domain.Namespace): Workflow.WorkflowF[O]
}

object Workflow {

  // There's currently only one workflow implementation.
  val workflows = List(Magnetar)

  def fromString(s: String): Option[Workflow[Unit]] =
    workflows.find(_.name == s)

  /*
   * A Workflow is the Coproduct of:
   * DockerOp, ConsulOp, Vault, LoggingOp, FailureOp, StorageOp and SchedulerOp
   */
  type Op0[A] = Coproduct[DockerOp, ConsulOp, A]

  type Op1[A] = Coproduct[LoggingOp, Op0,A]

  type Op2[A] = Coproduct[StoreOp, Op1, A]

  type Op3[A] = Coproduct[WorkflowControlOp, Op2,A]

  type Op4[A] = Coproduct[Vault, Op3, A]

  type WorkflowOp[A] = Coproduct[SchedulerOp, Op4, A]

  type WorkflowF[A] = Free.FreeC[WorkflowOp, A]

  def run[A](wf: WorkflowF[A])(trans: WorkflowOp ~> Task): Task[A] =
    Free.runFC(wf)(trans)

  object syntax {
    import docker.Docker
    import Domain.StackName
    import Domain.ServiceName
    import Docker.RegistryURI
    import ScalazHelpers._
    import routing.{RoutingTable,Discovery}

    def pure[A](a: => A): WorkflowF[A] =
      WorkflowControlOp.pure(a).inject

    def launch(i: Image, dc: Domain, ns: NamespaceName, u: UnitDef @@ Versioned, p: Plan, hash: String): WorkflowF[String] =
      SchedulerOp.launch(i, dc, ns, u, p, hash).inject

    def delete(dc: Domain, d: Deployment): WorkflowF[Unit] =
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

    def deleteFromConsul(key: String): WorkflowF[Unit] =
      ConsulOp.delete(key).inject

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

    def writeDiscoveryToConsul(id: ID, sn: StackName, ns: NamespaceName, dc: Domain): WorkflowF[Unit] =
      for {
        d  <- StoreOp.getDeployment(id).inject
        rg <- RoutingTable.outgoingRoutingGraph(d).inject
        dt  = Discovery.discoveryTable(routing.RoutingNode(d), rg)
        _  <- Discovery.writeDiscoveryInfoToConsul(ns, sn, dc.domain.name, dt).inject
      } yield ()

    def createTrafficShift(id: ID, nsRef: NamespaceName, dc: Domain, p: TrafficShiftPolicy, dur: FiniteDuration): WorkflowF[Unit] = {

      val prog = for {
        ns   <- OptionT(StoreOp.getNamespace(dc.name, nsRef))
        to   <- OptionT(StoreOp.getDeployment(id).map(Option(_)))
        sn    = ServiceName(to.unit.name, to.unit.version.toFeatureVersion)
        _    <- OptionT(StoreOp.createTrafficShift(ns.id, to, p, dur).map(Option(_)))
      } yield ()

      prog.run.inject.map(_ => ())
    }

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
        i <- DockerOp.extract(unit).inject
        _ <- status(id, DeploymentStatus.Deploying, s"replicating ${i.toString} to remote registry $registry")
        a <- DockerOp.pull(i).inject
        _ <- a._2.traverse(out => logToFile(id, out.asString))
        _ <- handleDockerLogs(a) { case e: PullError => e }
        b <- DockerOp.tag(i, registry).inject
        c <- DockerOp.push(b._2).inject
        _ <- c._2.traverse(out => logToFile(id, out.asString))
        _ <- handleDockerLogs(c) { case e: PushError => e }
      } yield b._2
    }
  }
}

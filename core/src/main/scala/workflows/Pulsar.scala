package nelson

import cats.syntax.apply._

import nelson.Datacenter.{Deployment, Namespace => DCNamespace}
import nelson.DeploymentStatus.{Pending, Ready, Terminated, Warming}
import nelson.Manifest.{Namespace => ManifestNamespace, Plan, UnitDef, Versioned}
import nelson.Workflow.{WorkflowF, WorkflowOp}
import nelson.Workflow.syntax._
import nelson.docker.DockerOp

/**
 * Kubernetes deployment workflow that deploys and deletes units, whilst provisioning
 * authentication roles in Vault so that Kubernetes pods can talk to Vault.
 */
object Pulsar extends Workflow[Unit] {
  val name: WorkflowRef = "pulsar"

  def deploy(id: ID, hash: String, vunit: UnitDef @@ Versioned, p: Plan, dc: Datacenter, ns: ManifestNamespace): WorkflowF[Unit] = {
    // When the workflow is completed, we typically want to set the deployment to "Warming", so that once
    // consul indicates the deployment to be passing the health check, we can promote to "Ready" (via the
    // DeploymentMonitor background process).  However, units without ports are not registered in consul, and
    // thus we should immediately advance mark the deployment as "Ready".  Once Reconciliation is also used as
    // a gating factor for promoting deployments to "Ready", we can potentially set all units to "Warming" here.
    def getStatus(unit: UnitDef, plan: Plan):  DeploymentStatus =
      if (Manifest.isPeriodic(unit,plan)) Ready
      else unit.ports.fold[DeploymentStatus](Ready)(_ => Warming)

    for {
      i <- DockerOp.extract(Versioned.unwrap(vunit)).inject[WorkflowOp]
      _ <- status(id, Pending, "Pulsar workflow about to start")
      _ <- logToFile(id, s"Instructing ${dc.name}'s scheduler to handle service container")
      l <- launch(i, dc, ns.name, vunit, p, None, hash)
      _ <- debug(s"Scheduler responded with: ${l}")
      _ <- status(id, getStatus(Manifest.Versioned.unwrap(vunit), p), "=====> Pulsar workflow completed <=====")
    } yield ()
  }

  def destroy(d: Deployment, dc: Datacenter, ns: DCNamespace): WorkflowF[Unit] = {
    val stackName = d.stackName
    logToFile(d.id, s"Instructing ${dc.name}'s scheduler to decomission ${stackName}") *>
    delete(dc, d) *>
    status(d.id, Terminated, s"Decomissioning deployment ${stackName} in ${dc.name}")
  }
}

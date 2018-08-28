package nelson
package scheduler

import nelson.Datacenter.{Deployment, StackName}
import nelson.Kubectl.{DeploymentStatus, JobStatus}
import nelson.Manifest.{HealthCheck => _, _}
import nelson.blueprint.{DefaultBlueprints, Render, Template}
import nelson.docker.Docker.Image
import nelson.scheduler.SchedulerOp._

import nelson.CatsHelpers._
import cats.~>
import cats.effect.IO
import cats.implicits._

import java.util.concurrent.ScheduledExecutorService

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.FiniteDuration

/**
 * SchedulerOp interpreter that uses the Kubernetes API server.
 *
 * See: https://kubernetes.io/docs/api-reference/v1.8/
 */
final class KubernetesShell(
  kubectl: Kubectl,
  timeout: FiniteDuration,
  executionContext: ExecutionContext,
  scheduledES: ScheduledExecutorService
) extends (SchedulerOp ~> IO) {
  import KubernetesShell._

  private implicit val kubernetesShellExecutionContext = executionContext
  private implicit val kubernetesShellScheduledES = scheduledES

  def apply[A](fa: SchedulerOp[A]): IO[A] = fa match {
    case Delete(dc, deployment) =>
      delete(dc, deployment).timed(timeout)
    case Launch(image, dc, ns, unit, plan, blueprint, hash) =>
      launch(image, dc, ns, Versioned.unwrap(unit), unit.version, plan, blueprint, hash).timed(timeout)
    case Summary(dc, ns, stackName) =>
      summary(dc, ns, stackName).timed(timeout)
  }

  def delete(dc: Datacenter, deployment: Deployment): IO[Unit] = {
    val ns = deployment.namespace.name
    val stack = deployment.stackName

    // We don't have enough information here to determine what exactly
    // we're trying to delete so try each one in turn..
    val fallback =
      kubectl.deleteService(dc, ns, stack).void.recoverWith { case _ =>
        kubectl.deleteCronJob(dc, ns, stack).void.recoverWith { case _ =>
          kubectl.deleteJob(dc, ns, stack).void.recover { case _ => () }
        }
      }

    deployment.renderedBlueprint.fold(fallback)(spec => kubectl.delete(dc, spec).void)
  }

  def launch(image: Image, dc: Datacenter, ns: NamespaceName, unit: UnitDef, version: Version, plan: Plan, blueprint: Option[Template], hash: String): IO[String] = {
    val env = Render.makeEnv(image, dc, ns, unit, version, plan, hash)

    val fallback = Manifest.getSchedule(unit, plan) match {
      case None => DefaultBlueprints.canopus.service
      case Some(sched) => sched.toCron match {
        case None => DefaultBlueprints.canopus.job
        case Some(_) => DefaultBlueprints.canopus.cronJob
      }
    }

    val template = blueprint.fold(fallback)(IO.pure)
    for {
      t <- template
      r <- kubectl.apply(dc, t.render(env))
    } yield r
  }

  def summary(dc: Datacenter, ns: NamespaceName, stackName: StackName): IO[Option[DeploymentSummary]] =
    deploymentSummary(dc, ns, stackName).recoverWith { case _ =>
      cronJobSummary(dc, ns, stackName).recoverWith { case _ =>
        jobSummary(dc, ns, stackName).recover { case _ => None }
      }
    }

  def deploymentSummary(dc: Datacenter, ns: NamespaceName, stackName: StackName): IO[Option[DeploymentSummary]] =
    kubectl.getDeployment(dc, ns, stackName).map {
      case DeploymentStatus(available, unavailable) =>
        Some(DeploymentSummary(
          running = available,
          pending = unavailable,
          completed = None,
          failed = None
        ))
    }

  def cronJobSummary(dc: Datacenter, ns: NamespaceName, stackName: StackName): IO[Option[DeploymentSummary]] =
    kubectl.getCronJob(dc, ns, stackName).map(js => Some(jobStatusToSummary(js)))

  def jobSummary(dc: Datacenter, ns: NamespaceName, stackName: StackName): IO[Option[DeploymentSummary]] =
    kubectl.getJob(dc, ns, stackName).map(js => Some(jobStatusToSummary(js)))
}

object KubernetesShell {
  private def jobStatusToSummary(js: JobStatus): DeploymentSummary =
    DeploymentSummary(
      running   = js.active,
      pending   = None,         // Doesn't seem like K8s API gives this info
      completed = js.succeeded,
      failed    = js.failed
    )
}

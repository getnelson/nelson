package nelson
package scheduler

import nelson.Datacenter.{Deployment, StackName}
import nelson.Kubectl.{DeploymentStatus, JobStatus, KubectlError}
import nelson.scheduler.SchedulerOp._

import nelson.CatsHelpers._
import cats.~>
import cats.effect.IO
import cats.implicits._

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.FiniteDuration
import java.util.concurrent.ScheduledExecutorService

/**
 * SchedulerOp interpreter that uses the Kubernetes API server.
 *
 * See: https://kubernetes.io/docs/api-reference/v1.8/
 */
final class KubernetesShell(
  kubectl: Kubectl,
  timeout: FiniteDuration,
  scheduler: ScheduledExecutorService,
  executionContext: ExecutionContext
) extends (SchedulerOp ~> IO) {
  import KubernetesShell._

  private implicit val kubernetesShellExecutionContext = executionContext

  def apply[A](fa: SchedulerOp[A]): IO[A] = fa match {
    case Delete(_, deployment) =>
      delete(deployment)
        .retryExponentially(limit = 3)(scheduler, kubernetesShellExecutionContext)
        .timed(timeout)
    case Launch(_, _, _, _, _, _, bp) =>
      kubectl.apply(bp)
        .retryExponentially(limit = 3)(scheduler, kubernetesShellExecutionContext)
        .timed(timeout)
    case Summary(_, ns, stackName) =>
      summary(ns, stackName)
        .retryExponentially(limit = 3)(scheduler, kubernetesShellExecutionContext)
        .timed(timeout)
  }

  def delete(deployment: Deployment): IO[Unit] = {
    val ns = deployment.namespace.name
    val stack = deployment.stackName

    // We don't have enough information here to determine what exactly
    // we're trying to delete so try each one in turn..
    val fallback =
      kubectl.deleteService(ns, stack).void.recoverWith {
        case err@KubectlError(_) if notFound(err) => kubectl.deleteCronJob(ns, stack).void.recoverWith {
          case err@KubectlError(_) if notFound(err) => kubectl.deleteJob(ns, stack).void.recover {
            case err@KubectlError(_) if notFound(err) => ()
          }
        }
      }

    deployment.renderedBlueprint.fold(fallback)(spec => kubectl.delete(spec).void)
  }

  // Janky heuristic to see if an attempted (legacy) deletion failed because
  // it was not found as opposed to some other reason like RBAC permissions
  private def notFound(error: KubectlError): Boolean =
    error.stderr.exists(_.startsWith("Error from server (NotFound)"))

  def summary(ns: NamespaceName, stackName: StackName): IO[Option[DeploymentSummary]] =
    deploymentSummary(ns, stackName).recoverWith { case _ =>
      cronJobSummary(ns, stackName).recoverWith { case _ =>
        jobSummary(ns, stackName).recover { case _ => None }
      }
    }

  def deploymentSummary(ns: NamespaceName, stackName: StackName): IO[Option[DeploymentSummary]] =
    kubectl.getDeployment(ns, stackName).map {
      case DeploymentStatus(available, unavailable) =>
        Some(DeploymentSummary(
          running = available,
          pending = unavailable,
          completed = None,
          failed = None
        ))
    }

  def cronJobSummary(ns: NamespaceName, stackName: StackName): IO[Option[DeploymentSummary]] =
    kubectl.getCronJob(ns, stackName).map(js => Some(jobStatusToSummary(js)))

  def jobSummary(ns: NamespaceName, stackName: StackName): IO[Option[DeploymentSummary]] =
    kubectl.getJob(ns, stackName).map(js => Some(jobStatusToSummary(js)))
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

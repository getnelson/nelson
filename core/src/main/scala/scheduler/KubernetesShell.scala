package nelson
package scheduler

import nelson.Datacenter.{Deployment, StackName}
import nelson.Kubectl.{DeploymentStatus, JobStatus, KubectlError}
import nelson.Manifest.{HealthCheck => _, _}
import nelson.blueprint.{DefaultBlueprints, Render}
import nelson.docker.Docker.Image
import nelson.scheduler.SchedulerOp._

import nelson.CatsHelpers._
import cats.~>
import cats.effect.IO
import cats.implicits._

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
  executionContext: ExecutionContext
) extends (SchedulerOp ~> IO) {
  import KubernetesShell._

  private implicit val kubernetesShellExecutionContext = executionContext

  def apply[A](fa: SchedulerOp[A]): IO[A] = fa match {
    case Delete(_, deployment) =>
      delete(deployment).timed(timeout)
    case Launch(image, dc, ns, unit, plan, hash) =>
      launch(image, dc, ns, Versioned.unwrap(unit), unit.version, plan, hash).timed(timeout)
    case Summary(_, ns, stackName) =>
      summary(ns, stackName).timed(timeout)
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

  def launch(image: Image, dc: Datacenter, ns: NamespaceName, unit: UnitDef, version: Version, plan: Plan, hash: String): IO[String] = {
    val env = Render.makeEnv(image, dc, ns, unit, version, plan, hash)

    val fallback = Manifest.getSchedule(plan) match {
      case None => DefaultBlueprints.canopus.service
      case Some(sched) => sched.toCron match {
        case None => DefaultBlueprints.canopus.job
        case Some(_) => DefaultBlueprints.canopus.cronJob
      }
    }

    // NOTE: by this point in the system, we know we're dealing with
    // a hydrated blueprint (i.e. passes manifest validation and exists
    // in the database) so we simply take the supplied plan and extract
    // the `Blueprint`, and `Template` in turn.
    val template = plan.environment.blueprint match {
      case Some(Left(_)) => IO.raiseError(new IllegalArgumentException(s"Internal error occured: un-hydrated blueprint passed to scheduler!"))
      case Some(Right(bp)) => IO.pure(bp.template)
      case None => fallback
    }

    for {
      t <- template
      r <- kubectl.apply(t.render(env))
    } yield r
  }

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

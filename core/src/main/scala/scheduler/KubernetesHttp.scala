package nelson
package scheduler

import nelson.KubernetesJson.{DeploymentStatus, JobStatus}
import nelson.Datacenter.{Deployment, StackName}
import nelson.Manifest.{HealthCheck => _, _}
import nelson.blueprint.{DefaultBlueprints, Render, Template}
import nelson.docker.Docker.Image
import nelson.scheduler.SchedulerOp._

import cats.~>
import cats.effect.IO
import cats.implicits._
import org.http4s.Status.NotFound
import org.http4s.client.UnexpectedStatus

/**
 * SchedulerOp interpreter that uses the Kubernetes API server.
 *
 * See: https://kubernetes.io/docs/api-reference/v1.8/
 */
final class KubernetesHttp(client: KubernetesClient) extends (SchedulerOp ~> IO) {

  def apply[A](fa: SchedulerOp[A]): IO[A] = fa match {
    case Delete(dc, deployment) =>
      delete(dc, deployment)
    case Launch(image, dc, ns, unit, plan, blueprint, hash) =>
      launch(image, dc, ns, Versioned.unwrap(unit), unit.version, plan, blueprint, hash)
    case Summary(dc, ns, stackName) =>
      summary(dc, ns, stackName)
  }

  def delete(dc: Datacenter, deployment: Deployment): IO[Unit] =
    deployment.renderedBlueprint.fold(deleteDefault(dc, deployment)) { spec =>
      Kubectl.delete(spec).void
    }

  def deleteDefault(dc: Datacenter, deployment: Deployment): IO[Unit] = {
    val rootNs = deployment.namespace.name.root.asString
    val name = deployment.stackName.toString

    // Kubernetes has different endpoints for deployments, cron jobs, and jobs - given just
    // the name we don't know which one it is so we try each one in turn, presumably the
    // most common types first
    val deleteService =
      (client.deleteDeployment(rootNs, name), client.deleteService(rootNs, name)).mapN { case (_, _) => () }

    deleteService.recoverWith {
      case UnexpectedStatus(NotFound) =>
        client.deleteCronJob(rootNs, name).map(_ => ()).recoverWith {
          case UnexpectedStatus(NotFound) =>
            client.deleteJob(rootNs, name).map(_ => ()).recoverWith {
              // at this point swallow 404, as we're being asked to delete something that does not exist
              // this can happen when a workflow fails and the cleanup process is subsequently called
              case UnexpectedStatus(NotFound) => IO.unit
            }
        }
    }
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
      r <- Kubectl.apply(t.render(env))
    } yield r
  }

  def summary(dc: Datacenter, ns: NamespaceName, stackName: StackName): IO[Option[DeploymentSummary]] = {
    // K8s has different endpoints for Deployment, CronJob, and Job, so we hit all of them until we find one
    deploymentSummary(dc, ns, stackName).recoverWith {
      case UnexpectedStatus(NotFound) =>
        cronJobSummary(dc, ns, stackName).recoverWith {
          case UnexpectedStatus(NotFound) =>
            jobSummary(dc, ns, stackName).recoverWith {
              case UnexpectedStatus(NotFound) => IO.pure(None)
            }
        }
    }
  }

  private def deploymentSummary(dc: Datacenter, ns: NamespaceName, stackName: StackName): IO[Option[DeploymentSummary]] = {
    val rootNs = ns.root.asString

    client.deploymentSummary(rootNs, stackName.toString).map {
      case DeploymentStatus(availableReplicas, unavailableReplicas) =>
        Some(DeploymentSummary(
          running   = availableReplicas,
          pending   = unavailableReplicas,
          completed = None,
          failed    = None
        ))
    }
  }

  private def cronJobSummary(dc: Datacenter, ns: NamespaceName, stackName: StackName): IO[Option[DeploymentSummary]] = {
    val rootNs = ns.root.asString

    client.cronJobSummary(rootNs, stackName.toString).map {
      case js@JobStatus(_, _, _) => Some(jobStatusToSummary(js))
    }
  }

  private def jobSummary(dc: Datacenter, ns: NamespaceName, stackName: StackName): IO[Option[DeploymentSummary]] = {
    val rootNs = ns.root.asString

    client.jobSummary(rootNs, stackName.toString).map {
      case js@JobStatus(_, _, _) => Some(jobStatusToSummary(js))
    }
  }

  private def jobStatusToSummary(js: JobStatus): DeploymentSummary =
    DeploymentSummary(
      running   = js.active,
      pending   = None,         // Doesn't seem like K8s API gives this info
      completed = js.succeeded,
      failed    = js.failed
    )
}

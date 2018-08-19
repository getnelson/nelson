package nelson

import nelson.Infrastructure.KubernetesMode
import nelson.health._

import argonaut._
import argonaut.Argonaut._

import cats.{Foldable, Monoid}
import cats.effect.{Effect, IO}
import cats.implicits._

import org.http4s.AuthScheme
import org.http4s.Credentials.Token
import org.http4s.Uri
import org.http4s.{Method, Request}
import org.http4s.argonaut._
import org.http4s.client.Client
import org.http4s.headers.Authorization

sealed abstract class KubernetesVersion extends Product with Serializable

object KubernetesVersion {
  final case object `1.6`  extends KubernetesVersion
  final case object `1.7`  extends KubernetesVersion
  final case object `1.8`  extends KubernetesVersion
  final case object `1.9`  extends KubernetesVersion
  final case object `1.10` extends KubernetesVersion

  def fromString(s: String): Option[KubernetesVersion] = s match {
    case "1.6"  => Some(`1.6`)
    case "1.7"  => Some(`1.7`)
    case "1.8"  => Some(`1.8`)
    case "1.9"  => Some(`1.9`)
    case "1.10" => Some(`1.10`)
    case _      => None
  }
}

/**
 * A bare bones Kubernetes client used for impelementing a Kubernetes
 * [[nelson.scheduler.SchedulerOp]] and [[nelson.loadbalancers.LoadBalancerOp]].
 *
 * This should really be a proper library.. at some point.
 *
 * See: https://kubernetes.io/docs/api-reference/v1.8/
 */
final class KubernetesClient(version: KubernetesVersion, endpoint: Uri, client: Client[IO], mode: KubernetesMode) {
  import KubernetesClient._
  import KubernetesJson._
  import KubernetesVersion._

  private[this] val serviceAccountToken = mode match {
    case KubernetesMode.InCluster            => scala.io.Source.fromFile(podTokenPath).getLines.toList.head
    case KubernetesMode.OutCluster(_, token) => token
  }

  def createDeployment(namespace: String, payload: Json): IO[Json] = {
    val request = addCreds(Request(Method.POST, deploymentUri(namespace)))
    client.expect[Json](request.withBody(payload))
  }

  def createService(namespace: String, payload: Json): IO[Json] = {
    val request = addCreds(Request(Method.POST, serviceUri(namespace)))
    client.expect[Json](request.withBody(payload))
  }

  def createCronJob(namespace: String, payload: Json): IO[Json] = {
    val request = addCreds(Request(Method.POST, cronJobUri(namespace)))
    client.expect[Json](request.withBody(payload))
  }

  def createJob(namespace: String, payload: Json): IO[Json] = {
    val request = addCreds(Request(Method.POST, jobUri(namespace)))
    client.expect[Json](request.withBody(payload))
  }

  def deleteDeployment(namespace: String, name: String): IO[Json] = {
    val request = addCreds(Request(Method.DELETE, deploymentUri(namespace) / name))
    client.expect[Json](request.withBody(cascadeDeletionPolicy))
  }

  def deleteService(namespace: String, name: String): IO[Json] = {
    val request = addCreds(Request(Method.DELETE, serviceUri(namespace) / name))
    client.expect[Json](request.withBody(cascadeDeletionPolicy))
  }

  def deleteCronJob(namespace: String, name: String): IO[Json] = {
    val request = addCreds(Request(Method.DELETE, cronJobUri(namespace) / name))
    client.expect[Json](request.withBody(cascadeDeletionPolicy))
  }

  def deleteJob(namespace: String, name: String): IO[Json] = {
    val request = addCreds(Request(Method.DELETE, jobUri(namespace) / name))
    client.expect[Json](request.withBody(cascadeDeletionPolicy))
  }

  def deploymentSummary(namespace: String, name: String): IO[DeploymentStatus] = {
    val request = addCreds(Request(Method.GET, deploymentUri(namespace) / name / "status"))
    client.expect[DeploymentStatus](request)(jsonOf[IO, DeploymentStatus])
  }

  def cronJobSummary(namespace: String, name: String): IO[JobStatus] = {
    // CronJob status doesn't give very useful information so we leverage Nelson-specific
    // information (the stackName label applied to cron jobs deployed by Nelson) to get status information
    val selector = s"stackName=${name}"
    val selectedUri = jobUri(namespace).withQueryParam("labelSelector", selector)
    val request = addCreds(Request(Method.GET, selectedUri))

    val decoder: DecodeJson[List[JobStatus]] = DecodeJson(c => (c --\ "items").as[List[JobStatus]])
    client.expect[List[JobStatus]](request)(jsonOf(Effect[IO], decoder)).map((jss: List[JobStatus]) => Foldable[List].fold(jss))
  }

  def jobSummary(namespace: String, name: String): IO[JobStatus] = {
    val request = addCreds(Request(Method.GET, jobUri(namespace) / name / "status"))
    client.expect[JobStatus](request)(jsonOf[IO, JobStatus])
  }

  def listPods(namespace: String, labelSelectors: Map[String, String]): IO[List[HealthStatus]] = {
    val selectors = labelSelectors.map { case (k, v) => s"${k}=${v}"}.mkString(",")
    val uri = podUri(namespace).withQueryParam("labelSelector", selectors)
    val request = addCreds(Request(Method.GET, uri))

    implicit val statusDecoder = healthStatusDecoder
    val decoder: DecodeJson[List[HealthStatus]] = DecodeJson(c => (c --\ "items").as[List[HealthStatus]])
    client.expect[List[HealthStatus]](request)(jsonOf(Effect[IO], decoder))
  }

  private def addCreds(req: Request[IO]): Request[IO] =
    req.putHeaders(Authorization(Token(AuthScheme.Bearer, serviceAccountToken)))

  private def podUri(ns: String): Uri =
    endpoint / "api" / "v1" / "namespaces" / ns / "pods"

  private def deploymentUri(ns: String): Uri = {
    val apiVersion = version match {
      case `1.6`          => "v1beta1"
      case `1.7`          => "v1beta1"
      case `1.8`          => "v1beta2"
      case `1.9` | `1.10` => "v1"
    }
    endpoint / "apis" / "apps"  / apiVersion / "namespaces" / ns / "deployments"
  }


  private def serviceUri(ns: String): Uri =
    endpoint / "api"  / "v1" / "namespaces" / ns / "services"

  private def cronJobUri(ns: String): Uri = {
    val apiVersion = version match {
      case `1.6`                  => "v2alpha1"
      case `1.7`                  => "v2alpha1"
      case `1.8` | `1.9` | `1.10` => "v1beta1"
    }
    endpoint / "apis" / "batch" / apiVersion / "namespaces" / ns / "cronjobs"
  }

  private def jobUri(ns: String): Uri =
    endpoint / "apis" / "batch" / "v1"      / "namespaces" / ns / "jobs"
}

object KubernetesClient {
  /** All pods in Kubernetes get a token mounted at this path.
    * See https://kubernetes.io/docs/tasks/access-application-cluster/access-cluster/#accessing-the-api-from-a-pod
    * for more info.
    */
  private val podTokenPath = "/var/run/secrets/kubernetes.io/serviceaccount/token"

  // Cascade deletes - deleting a Deployment should delete the associated ReplicaSet and Pods
  // See: https://kubernetes.io/docs/concepts/workloads/controllers/garbage-collection/
  private val cascadeDeletionPolicy = argonaut.Json(
    "kind" := "DeleteOptions",
    "apiVersion" := "v1",
    "propagationPolicy" := "Foreground"
  )
}

object KubernetesJson {
  // Status seems to be largely undocumented in the K8s docs, so your best bet is to stare at
  // https://github.com/kubernetes/kubernetes/tree/master/api/openapi-spec
  // Recommend using 'jq' for your sanity

  final case class DeploymentStatus(
    availableReplicas:   Option[Int],
    unavailableReplicas: Option[Int]
  )

  object DeploymentStatus {
    implicit val deploymentStatusDecoder: DecodeJson[DeploymentStatus] =
      DecodeJson(c => {
        val status = c --\ "status"
        for {
          availableReplicas   <- (status --\ "availableReplicas").as[Option[Int]]
          unavailableReplicas <- (status --\ "unavailableReplicas").as[Option[Int]]
        } yield DeploymentStatus(availableReplicas, unavailableReplicas)
      })
  }

  final case class JobStatus(
    active:    Option[Int],
    failed:    Option[Int],
    succeeded: Option[Int]
  )

  object JobStatus {
    implicit val jobStatusDecoder: DecodeJson[JobStatus] =
      DecodeJson(c => {
        val status = c --\ "status"
        for {
          active    <- (status --\ "active").as[Option[Int]]
          failed    <- (status --\ "failed").as[Option[Int]]
          succeeded <- (status --\ "succeeded").as[Option[Int]]
        } yield JobStatus(active, failed, succeeded)
      })

    implicit val jobStatusMonoid: Monoid[JobStatus] = new Monoid[JobStatus] {
      def combine(f1: JobStatus, f2: JobStatus): JobStatus =
        JobStatus(
          active =    f1.active    |+| f2.active,
          failed =    f1.failed    |+| f2.failed,
          succeeded = f1.succeeded |+| f2.succeeded
        )

      def empty: JobStatus = JobStatus(None, None, None)
    }
  }

  private def combineJsonObjects(x: JsonObject, xs: JsonObject*): JsonObject =
    JsonObject.fromTraversableOnce(x.toList ++ xs.toList.flatMap(_.toList))

  val healthStatusDecoder: DecodeJson[HealthStatus] =
    DecodeJson(c =>
      for {
        name        <- (c --\ "metadata" --\ "name").as[String]
        conditions  =  (c --\ "status" --\ "conditions").downAt(readyCondition)
        status      <- (conditions --\ "status").as[String].map(parseReadyCondition)
        details     <- (conditions --\ "message").as[Option[String]]
        node        <- (c --\ "spec" --\ "nodeName").as[String]
      } yield HealthStatus(name, status, node, details)
    )

  private def readyCondition(json: Json): Boolean =
    json.acursor.downField("type").as[String].map(_ == "Ready").toOption.getOrElse(false)

  private def parseReadyCondition(s: String): HealthCheck = s match {
    case "True"  => Passing
    case "False" => Failing
    case _       => Unknown
  }
}

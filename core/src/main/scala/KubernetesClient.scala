package nelson

import argonaut._
import argonaut.Argonaut._
import org.http4s.AuthScheme
import org.http4s.Credentials.Token
import org.http4s.Uri
import org.http4s.{Method, Request}
import org.http4s.argonaut._
import org.http4s.client.Client
import org.http4s.headers.Authorization
import scalaz.{Foldable, Monoid}
import scalaz.concurrent.Task
import scalaz.Scalaz._

import nelson.Datacenter.StackName
import nelson.Manifest.{EnvironmentVariable, HealthCheck => HealthProbe, Plan, Ports, Port}
import nelson.docker.Docker.Image
import nelson.health._

/**
 * A bare bones Kubernetes client used for impelementing a Kubernetes
 * [[nelson.scheduler.SchedulerOp]] and [[nelson.loadbalancers.LoadBalancerOp]].
 *
 * This should really be a proper library.. at some point.
 *
 * See: https://kubernetes.io/docs/api-reference/v1.8/
 */
final class KubernetesClient(endpoint: Uri, client: Client, serviceAccountToken: String) {
  import KubernetesClient.cascadeDeletionPolicy
  import KubernetesJson._

  def createDeployment(namespace: String, stackName: StackName, image: Image, plan: Plan, ports: Option[Ports]): Task[Json] = {
    val json = KubernetesJson.deployment(namespace,stackName, image, plan, ports)
    val request = addCreds(Request(Method.POST, deploymentUri(namespace)))
    client.expect[Json](request.withBody(json))
  }

  def createService(namespace: String, stackName: StackName, ports: Option[Ports]): Task[Json] = {
    val json = KubernetesJson.service(namespace, stackName, ports)
    val request = addCreds(Request(Method.POST, serviceUri(namespace)))
    client.expect[Json](request.withBody(json))
  }

  def createCronJob(namespace: String, stackName: StackName, image: Image, plan: Plan, cronExpr: String): Task[Json] = {
    val json = KubernetesJson.cronJob(namespace, stackName, image, plan, cronExpr)
    val request = addCreds(Request(Method.POST, cronJobUri(namespace)))
    client.expect[Json](request.withBody(json))
  }

  def createJob(namespace: String, stackName: StackName, image: Image, plan: Plan): Task[Json] = {
    val json = KubernetesJson.job(namespace, stackName, image, plan)
    val request = addCreds(Request(Method.POST, jobUri(namespace)))
    client.expect[Json](request.withBody(json))
  }

  def deleteDeployment(namespace: String, name: String): Task[Json] = {
    val request = addCreds(Request(Method.DELETE, deploymentUri(namespace) / name))
    client.expect[Json](request.withBody(cascadeDeletionPolicy))
  }

  def deleteService(namespace: String, name: String): Task[Json] = {
    val request = addCreds(Request(Method.DELETE, serviceUri(namespace) / name))
    client.expect[Json](request.withBody(cascadeDeletionPolicy))
  }

  def deleteCronJob(namespace: String, name: String): Task[Json] = {
    val request = addCreds(Request(Method.DELETE, cronJobUri(namespace) / name))
    client.expect[Json](request.withBody(cascadeDeletionPolicy))
  }

  def deleteJob(namespace: String, name: String): Task[Json] = {
    val request = addCreds(Request(Method.DELETE, jobUri(namespace) / name))
    client.expect[Json](request.withBody(cascadeDeletionPolicy))
  }

  def deploymentSummary(namespace: String, name: String): Task[DeploymentStatus] = {
    val request = addCreds(Request(Method.GET, deploymentUri(namespace) / name / "status"))
    client.expect[DeploymentStatus](request)(jsonOf[DeploymentStatus])
  }

  def cronJobSummary(namespace: String, name: String): Task[JobStatus] = {
    // CronJob status doesn't give very useful information so we leverage Nelson-specific
    // information (the stackName label applied to cron jobs deployed by Nelson) to get status information
    val selector = s"stackName=${name}"
    val selectedUri = jobUri(namespace).withQueryParam("labelSelector", selector)
    val request = addCreds(Request(Method.GET, selectedUri))

    val decoder: DecodeJson[List[JobStatus]] = DecodeJson(c => (c --\ "items").as[List[JobStatus]])
    client.expect[List[JobStatus]](request)(jsonOf(decoder)).map((jss: List[JobStatus])=> Foldable[List].fold(jss))
  }

  def jobSummary(namespace: String, name: String): Task[JobStatus] = {
    val request = addCreds(Request(Method.GET, jobUri(namespace) / name / "status"))
    client.expect[JobStatus](request)(jsonOf[JobStatus])
  }

  def listPods(namespace: String, labelSelectors: Map[String, String]): Task[List[HealthStatus]] = {
    val selectors = labelSelectors.map { case (k, v) => s"${k}=${v}"}.mkString(",")
    val uri = podUri(namespace).withQueryParam("labelSelector", selectors)
    val request = addCreds(Request(Method.GET, uri))

    implicit val statusDecoder = healthStatusDecoder
    val decoder: DecodeJson[List[HealthStatus]] = DecodeJson(c => (c --\ "items").as[List[HealthStatus]])
    client.expect[List[HealthStatus]](request)(jsonOf(decoder))
  }

  private def addCreds(req: Request): Request =
    req.putHeaders(Authorization(Token(AuthScheme.Bearer, serviceAccountToken)))

  private def podUri(ns: String): Uri =
    endpoint / "api"  /           "v1"      / "namespaces" / ns / "pods"

  private def deploymentUri(ns: String): Uri =
    endpoint / "apis" / "apps"  / "v1beta2" / "namespaces" / ns / "deployments"

  private def serviceUri(ns: String): Uri =
    endpoint / "api"  /           "v1"      / "namespaces" / ns / "services"

  private def cronJobUri(ns: String): Uri =
    endpoint / "apis" / "batch" / "v1beta1" / "namespaces" / ns / "cronjobs"

  private def jobUri(ns: String): Uri =
    endpoint / "apis" / "batch" / "v1"      / "namespaces" / ns / "jobs"
}

object KubernetesClient {
  // Cascade deletes - deleting a Deployment should delete the associated ReplicaSet and Pods
  // See: https://kubernetes.io/docs/concepts/workloads/controllers/garbage-collection/
  private val cascadeDeletionPolicy = argonaut.Json(
    "kind" := "DeleteOptions",
    "apiVersion" := "v1",
    "propagationPolicy" := "Foreground"
  )
}

object KubernetesJson {
  val DEFAULT_CPU = 0.5
  val DEFAULT_MEMORY = 512

  def resources(cpu: Option[(Double, Double)], memory: Option[(Double, Double)]): JsonObject = {
    val (cpuRequest, cpuLimit) = cpu.getOrElse((DEFAULT_CPU, DEFAULT_CPU))
    val (memoryRequest, memoryLimit) = memory.map { case (r, l) => (s"${r}M", s"${l}M") }.getOrElse((s"${DEFAULT_MEMORY}M", s"${DEFAULT_MEMORY}M"))

    JsonObject.single("resources", argonaut.Json(
      "requests" := argonaut.Json(
        "cpu"    := cpuRequest,
        "memory" := memoryRequest
      ),
      "limits" := argonaut.Json(
        "cpu"    := cpuLimit,
        "memory" := memoryLimit
      )
    ))
  }

  def deployment(
    namespace: String,
    stackName: StackName,
    image:     Image,
    plan:      Plan,
    ports:     Option[Ports]
  ): Json =
    argonaut.Json(
      "apiVersion" := "apps/v1beta2",
      "kind"       := "Deployment",
      "metadata"   := argonaut.Json(
        "name"      := stackName.toString,
        "namespace" := namespace,
        "labels"    := argonaut.Json(
          "stackName"   := stackName.toString,
          "serviceName" := stackName.serviceType,
          "version"     := stackName.version.toString,
          "nelson"      := "true"
        )
      ),
      "spec" := argonaut.Json(
        "replicas" := plan.environment.desiredInstances.getOrElse(1),
        "selector" := argonaut.Json(
          "matchLabels" := argonaut.Json("stackName" := stackName.toString)
        ),
        "template" := argonaut.Json(
          "metadata" := argonaut.Json(
            "labels" := argonaut.Json(
              "stackName"   := stackName.toString,
              "serviceName" := stackName.serviceType,
              "version"     := stackName.version.toString,
              "nelson"      := "true"
            )
          ),
          "spec" := argonaut.Json(
            "containers" := List(

              argonaut.Json.jObject(combineJsonObject(combineJsonObject(JsonObject.from(List(
                "name"      := stackName.toString,
                "image"     := image.toString,
                "env"       := plan.environment.bindings,
                "ports"     := containerPortsJson(ports.toList.flatMap(_.nel.list))
              )), resources(plan.environment.cpu, plan.environment.memory)), livenessProbe(plan.environment.healthChecks)))
            )
          )
        )
      )
    )

  def service(namespace: String, stackName: StackName, ports: Option[Ports]): Json =
    argonaut.Json(
      "apiVersion" := "v1",
      "kind"       := "Service",
      "metadata"   := argonaut.Json(
        "name"      := stackName.toString,
        "namespace" := namespace,
        "labels"    := argonaut.Json(
          "stackName"   := stackName.toString,
          "serviceName" := stackName.serviceType,
          "version"     := stackName.version.toString,
          "nelson"      := "true"
        )
      ),
      "spec" := argonaut.Json(
        "selector" := argonaut.Json("stackName" := stackName.toString),
        "ports"    := servicePortsJson(ports.toList.flatMap(_.nel.list)),
        "type"     := "ClusterIP"
      )
    )

  def cronJob(
    namespace: String,
    stackName: StackName,
    image:     Image,
    plan:      Plan,
    cronExpr:  String
  ): Json =
    argonaut.Json(
      "apiVersion" := "batch/v1beta1",
      "kind"       := "CronJob",
      "metadata"   := argonaut.Json(
        "name"      := stackName.toString,
        "namespace" := namespace,
        "labels"    := argonaut.Json(
          "stackName"   := stackName.toString,
          "serviceName" := stackName.serviceType,
          "version"     := stackName.version.toString,
          "nelson"      := "true"
        )
      ),
      "spec" := argonaut.Json(
        "schedule"    := cronExpr,
        "jobTemplate" := argonaut.Json.jObject(jobSpecJson(stackName, image, plan))
      )
    )

  def job(
    namespace: String,
    stackName: StackName,
    image:     Image,
    plan:      Plan
  ): Json =
    argonaut.Json.jObject(combineJsonObject(JsonObject.from(List(
      "apiVersion" := "batch/v1",
      "kind"       := "Job",
      "metadata"   := argonaut.Json(
        "name"      := stackName.toString,
        "namespace" := namespace,
        "labels"    := argonaut.Json(
          "stackName"   := stackName.toString,
          "serviceName" := stackName.serviceType,
          "version"     := stackName.version.toString,
          "nelson"      := "true"
        )
      )
    )), jobSpecJson(stackName, image, plan)))

  private def jobSpecJson(
    stackName: StackName,
    image:     Image,
    plan:      Plan
  ): JsonObject = {
    val backoffLimit = JsonObject.single("backoffLimit", plan.environment.retries.getOrElse(3).asJson)

    JsonObject.from(List(
      "spec" := argonaut.Json.jObject(combineJsonObject(JsonObject.from(List(
        "completions"  := plan.environment.desiredInstances.getOrElse(1),
        "template"     := argonaut.Json(
          "metadata" := argonaut.Json(
            "name" := stackName.toString,
            "labels" := argonaut.Json(
              "stackName"   := stackName.toString,
              "serviceName" := stackName.serviceType,
              "version"     := stackName.version.toString,
              "nelson"      := "true"
            )
          ),
          "spec" := argonaut.Json(
            "containers" := List(
              argonaut.Json.jObject(combineJsonObject(JsonObject.from(List(
                "name"  := stackName.toString,
                "image" := image.toString,
                "env"   := plan.environment.bindings
              )), resources(plan.environment.cpu, plan.environment.memory)))
            ),
            // See: https://kubernetes.io/docs/concepts/workloads/controllers/jobs-run-to-completion/#pod-backoff-failure-policy
            // This should be "OnFailure" but at the time of this writing this section said:
            // Note: Due to a known issue #54870, when the spec.template.spec.restartPolicy field is set to “OnFailure”,
            // the back-off limit may be ineffective. As a short-term workaround, set the restart policy for the embedded template to “Never”
            // https://github.com/kubernetes/kubernetes/issues/54870
            "restartPolicy" := "Never"
          )
        )
      )), backoffLimit))
    ))
  }

  // HealthChecks in Nelson seem to correspond to Kubernetes liveness probes
  // Kubernetes seems to only support one one liveness probe, so take the first one..
  private def livenessProbe(healthChecks: List[HealthProbe]): JsonObject =
    healthChecks.headOption match {
      case None => JsonObject.empty
      case Some(HealthProbe(_, portRef, _, path, interval, timeout)) =>
        JsonObject.single("livenessProbe", argonaut.Json(
          "httpGet" := argonaut.Json(
            "path" := path.getOrElse("/"),
            "port" := portRef
          ),
          "periodSeconds"  := interval.toSeconds,
          "timeoutSeconds" := timeout.toSeconds
        ))
    }

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
      def append(f1: JobStatus, f2: => JobStatus): JobStatus =
        JobStatus(
          active =    f1.active    |+| f2.active,
          failed =    f1.failed    |+| f2.failed,
          succeeded = f1.succeeded |+| f2.succeeded
        )

      def zero: JobStatus = JobStatus(None, None, None)
    }
  }

  private def combineJsonObject(x: JsonObject, y: JsonObject): JsonObject =
    JsonObject.from(x.toList ++ y.toList)

  private def containerPortsJson(ports: List[Port]): List[Json] =
    ports.map { port =>
      argonaut.Json(
        "name"          := port.ref,
        "containerPort" := port.port
      )
    }

  private def servicePortsJson(ports: List[Port]): List[Json] =
    ports.map { port =>
      argonaut.Json(
        "name"       := port.ref,
        "port"       := port.port,
        "targetPort" := port.port
      )
    }

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

  private implicit val envVarEncoder: EncodeJson[EnvironmentVariable] =
    EncodeJson { (ev: EnvironmentVariable) =>
      ("name"  := ev.name)  ->:
      ("value" := ev.value) ->:
      jEmptyObject
    }
}

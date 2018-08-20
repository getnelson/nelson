package nelson
package scheduler

import nelson.KubernetesJson.{DeploymentStatus, JobStatus}
import nelson.Datacenter.{Deployment, StackName}
import nelson.Manifest.{HealthCheck => HealthProbe, _}
import nelson.blueprint.Template
import nelson.docker.Docker.Image
import nelson.scheduler.SchedulerOp._
import argonaut._
import argonaut.Argonaut._

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
    deployment.spec.fold(deleteDefault(dc, deployment)) { spec =>
      // TODO: Delete via spec
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

  def launch(image: Image, dc: Datacenter, ns: NamespaceName, unit: UnitDef, version: Version, plan: Plan, blueprint: Option[Template], hash: String): IO[String] =
    blueprint.fold(launchDefault(image, dc, ns, unit, version, plan, hash)) { template =>
      // TODO..
      Kubectl.apply(template.render(Map.empty))
    }

  def launchDefault(image: Image, dc: Datacenter, ns: NamespaceName, unit: UnitDef, version: Version, plan: Plan, hash: String): IO[String] = {
    val stackName = StackName(unit.name, version, hash)
    val env = plan.environment.bindings ++ List(
      EnvironmentVariable("NELSON_STACKNAME",        stackName.toString),
      EnvironmentVariable("NELSON_DATACENTER",       dc.name),
      EnvironmentVariable("NELSON_ENV",              ns.root.asString),
      EnvironmentVariable("NELSON_NAMESPACE",        ns.asString),
      EnvironmentVariable("NELSON_DNS_ROOT",         dc.domain.name),
      EnvironmentVariable("NELSON_PLAN",             plan.name),
      EnvironmentVariable("NELSON_DOCKER_IMAGE",     image.toString),
      EnvironmentVariable("NELSON_MEMORY_LIMIT",     plan.environment.memory.limitOrElse(512D).toInt.toString),
      EnvironmentVariable("NELSON_NODENAME",         s"$${node.unique.name}"),
      EnvironmentVariable("NELSON_VAULT_POLICYNAME", NomadJson.getPolicyName(ns, stackName.toString))
    )
    val newPlan = plan.copy(environment = plan.environment.copy(bindings = env))
    val schedule = Manifest.getSchedule(unit, plan)

    val rootNs = ns.root.asString
    val response = schedule match {
      case None =>
        val deploymentJson = KubernetesJson.deployment(rootNs, stackName, image, newPlan, unit.ports)
        val serviceJson = KubernetesJson.service(rootNs, stackName, unit.ports)
        (client.createDeployment(rootNs, deploymentJson), client.createService(rootNs, serviceJson)).mapN {
          case (deployment, service) => Json("deployment" := deployment, "service" := service)
        }
      case Some(sched) =>
        sched.toCron match {
          case None =>
            val json = KubernetesJson.job(rootNs, stackName, image, newPlan)
            client.createJob(rootNs, json)
          case Some(cronExpr) =>
            val json = KubernetesJson.cronJob(rootNs, stackName, image, newPlan, cronExpr)
            client.createCronJob(rootNs, json)
        }
    }

    response.map(_.nospaces)
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

object KubernetesJson {
  val defaultCPU = 0.5
  val defaultMemory = 512

  def resources(cpu: ResourceSpec, memory: ResourceSpec): JsonObject = {
    val (cpuRequest, cpuLimit) = cpu.fold(
      (defaultCPU, defaultCPU),
      limit => (limit, limit),
      (request, limit) => (request, limit)
    )
    val (memoryRequest, memoryLimit) = memory.fold(
      (s"${defaultMemory}M", s"${defaultMemory}M"),
      limit => (s"${limit}M", s"${limit}M"),
      (request, limit) => (s"${request}M", s"${limit}M")
    )

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

  def volumeJson(volume: Volume): Json = argonaut.Json.jObject(volume match {
    case Volume(id, _, sizeInMb) =>
      JsonObject.fromTraversableOnce(List(
        "name" := id,
        "emptyDir" := argonaut.Json("sizeLimit" := s"${sizeInMb}M")
      ))
  })

  def volumesJson(volumes: List[Volume]): JsonObject = volumes match {
    case Nil     => JsonObject.empty
    case v :: vs => JsonObject.single("volumes", (volumeJson(v) :: vs.map(volumeJson)).asJson)
  }

  def volumeMountJson(volume: Volume): Json = argonaut.Json.jObject(volume match {
    case Volume(id, mountPath, _) =>
      JsonObject.fromTraversableOnce(List(
        "name" := id,
        "mountPath" := mountPath.toString
      ))
  })

  def volumeMountsJson(volumes: List[Volume]): JsonObject = volumes match {
    case Nil => JsonObject.empty
    case v :: vs => JsonObject.single("volumeMounts", (volumeMountJson(v) :: vs.map(volumeMountJson)).asJson)
  }

  def deployment(
    namespace: String,
    stackName: StackName,
    image:     Image,
    plan:      Plan,
    ports:     Option[Ports]
  ): Json =
    argonaut.Json(
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
          "spec" := argonaut.Json.jObject(combineJsonObjects(
            argonaut.JsonObject.fromTraversableOnce(List("containers" := List(
              argonaut.Json.jObject(combineJsonObjects(JsonObject.fromTraversableOnce(List(
                "name"      := stackName.toString,
                "image"     := image.toString,
                "env"       := plan.environment.bindings,
                "ports"     := containerPortsJson(ports.toList.flatMap(_.nel.toList))
              )), volumeMountsJson(plan.environment.volumes), resources(plan.environment.cpu, plan.environment.memory), livenessProbe(plan.environment.healthChecks)))
            ))),
            volumesJson(plan.environment.volumes)
          ))
        )
      )
    )

  def service(namespace: String, stackName: StackName, ports: Option[Ports]): Json =
    argonaut.Json(
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
        "ports"    := servicePortsJson(ports.toList.flatMap(_.nel.toList)),
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
    argonaut.Json.jObject(combineJsonObjects(JsonObject.fromTraversableOnce(List(
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

    JsonObject.fromTraversableOnce(List(
      "spec" := argonaut.Json.jObject(combineJsonObjects(JsonObject.fromTraversableOnce(List(
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
          "spec" := argonaut.Json.jObject(combineJsonObjects(
            JsonObject.fromTraversableOnce(List("containers" := List(
              argonaut.Json.jObject(combineJsonObjects(JsonObject.fromTraversableOnce(List(
                "name"  := stackName.toString,
                "image" := image.toString,
                "env"   := plan.environment.bindings
              )), volumeMountsJson(plan.environment.volumes), resources(plan.environment.cpu, plan.environment.memory)))
            ))),
            volumesJson(plan.environment.volumes)) :+
            // See: https://kubernetes.io/docs/concepts/workloads/controllers/jobs-run-to-completion/#pod-backoff-failure-policy
            // This should be "OnFailure" but at the time of this writing this section said:
            // Note: Due to a known issue #54870, when the spec.template.spec.restartPolicy field is set to “OnFailure”,
            // the back-off limit may be ineffective. As a short-term workaround, set the restart policy for the embedded template to “Never”
            // https://github.com/kubernetes/kubernetes/issues/54870
            ("restartPolicy" := "Never")
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

  private def combineJsonObjects(x: JsonObject, xs: JsonObject*): JsonObject =
    JsonObject.fromTraversableOnce(x.toList ++ xs.toList.flatMap(_.toList))

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

  private implicit val envVarEncoder: EncodeJson[EnvironmentVariable] =
    EncodeJson { (ev: EnvironmentVariable) =>
      ("name"  := ev.name)  ->:
      ("value" := ev.value) ->:
      jEmptyObject
    }
}

package nelson
package blueprint

import nelson.Datacenter.StackName
import nelson.Manifest._
import nelson.docker.Docker.Image

object Render {
  def makeEnv(img: Image, dc: Datacenter, ns: NamespaceName, unit: UnitDef, v: Version, p: Plan, hash: String): Map[String, Any] = {
    import Render.keys._

    val sn = StackName(unit.name, v, hash)

    val baseEnv = Map(
      (stackName, sn.toString),
      (namespace, ns.root.asString),
      (unitName, sn.serviceType),
      (version, sn.version.toString),
      (image, img.toString)
    )

    val jobEnv = fromOption(Manifest.getSchedule(unit, p).flatMap(_.toCron)) { cronExpr =>
      Map((schedule, cronExpr))
    } ++ Map(
      (instances, p.environment.desiredInstances.getOrElse(1).toString),
      (retries, p.environment.retries.getOrElse(3).toString)
    )

    val instancesEnv = p.environment.desiredInstances

    val portsEnv = fromOption(unit.ports) { ps =>
      val pl = ps.nel.toList.map(port => Map[String, Any]((portName, port.ref), (portNumber, port.port.toString)))
      Map[String, Any]((ports, Map((portsList, pl))))
    }

    val resourceEnv = p.environment.cpu.fold[Map[String, Any]](
      Map.empty,
      limit => Map((cpuLimit, limit)),
      (request, limit) => Map((cpuRequest, request.toString), (cpuLimit, limit.toString))
    ) ++ p.environment.memory.fold[Map[String, Any]](
      Map.empty,
      limit => Map((memoryLimit, limit)),
      (request, limit) => Map((memoryRequest, request.toString), (memoryLimit, limit.toString))
    )

    val volumesEnv = if (p.environment.volumes.nonEmpty) {
      val volumesList = p.environment.volumes.map {
        case Volume(name, mountPath, size) => Map[String, Any](
          (emptyVolumeMountName, name),
          (emptyVolumeMountPath, mountPath.toString),
          (emptyVolumeMountSize, size.toString)
        )
      }
      Map((emptyVolumes, Map((emptyVolumesList, volumesList))))
    } else Map.empty[String, Any]

    val livenessProbeEnv = fromOption(p.environment.healthChecks.headOption) {
      case HealthCheck(_, portRef, _, path, interval, timeout) =>
        Map[String, Any](
          (healthCheck, Map(
            (healthCheckPath, path.getOrElse("/")),
            (healthCheckPort, portRef.toString),
            (healthCheckInterval, interval.toSeconds.toString),
            (healthCheckTimeout, timeout.toSeconds.toString)
          ))
        )
    }

    val nelsonEnvs = List(
      EnvironmentVariable("NELSON_STACKNAME", stackName.toString),
      EnvironmentVariable("NELSON_DATACENTER", dc.name),
      EnvironmentVariable("NELSON_ENV", ns.root.asString),
      EnvironmentVariable("NELSON_NAMESPACE", ns.asString),
      EnvironmentVariable("NELSON_DNS_ROOT", dc.domain.name),
      EnvironmentVariable("NELSON_PLAN", p.name),
      EnvironmentVariable("NELSON_DOCKER_IMAGE", image.toString)
    )
    val envList = (p.environment.bindings ++ nelsonEnvs).map {
      case EnvironmentVariable(name, value) => Map((envvarName, name), (envvarValue, value))
    }
    val envEnv = Map((envvars, Map((envvarsList, envList))))

    baseEnv ++ jobEnv ++ portsEnv ++ resourceEnv ++ volumesEnv ++ livenessProbeEnv
  }

  private def fromOption[A](o: Option[A])(f: A => Map[String, Any]): Map[String, Any] = o.fold[Map[String, Any]](Map.empty)(f)

  object keys {
    // Unit/Deployment
    val stackName = "stack_name"
    val namespace = "namespace"
    val unitName = "unit_name"
    val version = "version"
    val image = "image"

    val ports = "ports"
    val portsList = "ports_list"
    val portName = "port_name"
    val portNumber = "port_number"

    val healthCheck = "health_check"
    val healthCheckPath = "health_check_path"
    val healthCheckPort = "health_check_port"
    val healthCheckInterval = "health_check_interval"
    val healthCheckTimeout = "health_check_timeout"

    // Plan
    val cpuRequest = "cpu_request"
    val cpuLimit = "cpu_limit"
    val memoryRequest = "memory_request"
    val memoryLimit = "memory_limit"
    val retries = "retries"
    val instances = "instances"
    val schedule = "schedule"

    val emptyVolumes = "empty_volumes"
    val emptyVolumesList = "empty_volumes_list"
    val emptyVolumeMountName = "empty_volume_mount_name"
    val emptyVolumeMountPath = "empty_volume_mount_path"
    val emptyVolumeMountSize = "empty_volume_mount_size"

    val envvars = "envvars"
    val envvarsList = "envvars_list"
    val envvarName = "envvar_name"
    val envvarValue = "envvar_value"
  }

}

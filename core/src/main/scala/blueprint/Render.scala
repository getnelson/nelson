package nelson
package blueprint

import nelson.Datacenter.StackName
import nelson.Manifest._
import nelson.blueprint.EnvValue._
import nelson.docker.Docker.Image

object Render {
  def makeEnv(img: Image, dc: Datacenter, ns: NamespaceName, unit: UnitDef, v: Version, p: Plan, hash: String): Map[String, EnvValue] = {
    import Render.keys._

    val sn = StackName(unit.name, v, hash)

    val baseEnv = Map(
      (stackName, StringValue(sn.toString)),
      (namespace, StringValue(ns.root.asString)),
      (unitName, StringValue(sn.serviceType)),
      (version, StringValue(sn.version.toString)),
      (image, StringValue(img.toString))
    )

    val jobEnv = fromOption(Manifest.getSchedule(unit, p).flatMap(_.toCron)) { cronExpr =>
      Map((schedule, StringValue(cronExpr)))
    } ++ fromOption(p.environment.desiredInstances) { instances =>
      Map((desiredInstances, StringValue(instances.toString)))
    } ++ fromOption(p.environment.retries) { r =>
      Map((retries, StringValue(r.toString)))
    }

    val portsEnv = fromOption(unit.ports) { ps =>
      val pl = ps.nel.toList.map(port => MapValue(Map((portName, StringValue(port.ref)), (portNumber, StringValue(port.port.toString)))))
      Map((ports, MapValue(Map((portsList, ListValue(pl))))))
    }

    val resourceEnv = p.environment.cpu.fold(
      Map.empty,
      limit => Map((cpuLimit, StringValue(limit.toString))),
      (request, limit) => Map((cpuRequest, StringValue(request.toString)), (cpuLimit, StringValue(limit.toString)))
    ) ++ p.environment.memory.fold(
      Map.empty,
      limit => Map((memoryLimit, StringValue(limit.toString))),
      (request, limit) => Map((memoryRequest, StringValue(request.toString)), (memoryLimit, StringValue(limit.toString)))
    )

    val volumesEnv = if (p.environment.volumes.nonEmpty) {
      val volumesList = p.environment.volumes.map {
        case Volume(name, mountPath, size) => MapValue(Map(
          (emptyVolumeMountName, StringValue(name)),
          (emptyVolumeMountPath, StringValue(mountPath.toString)),
          (emptyVolumeMountSize, StringValue(size.toString))
        ))
      }
      Map((emptyVolumes, MapValue(Map((emptyVolumesList, ListValue(volumesList))))))
    } else Map.empty

    val livenessProbeEnv = fromOption(p.environment.healthChecks.headOption) {
      case HealthCheck(_, portRef, _, path, interval, timeout) =>
        Map(
          (healthCheck, MapValue(Map(
            (healthCheckPath, StringValue(path.getOrElse("/"))),
            (healthCheckPort, StringValue(portRef.toString)),
            (healthCheckInterval, StringValue(interval.toSeconds.toString)),
            (healthCheckTimeout, StringValue(timeout.toSeconds.toString))
          )))
        )
    }

    val nelsonEnvs = List(
      EnvironmentVariable("NELSON_DNS_ROOT", dc.domain.name),
      EnvironmentVariable("NELSON_DATACENTER", dc.name),
      EnvironmentVariable("NELSON_ENV", ns.root.asString),
      EnvironmentVariable("NELSON_NAMESPACE", ns.asString),
      EnvironmentVariable("NELSON_PLAN", p.name),
      EnvironmentVariable("NELSON_STACKNAME", sn.toString),
      EnvironmentVariable("NELSON_UNITNAME", sn.serviceType)
    )
    val envList = (p.environment.bindings ++ nelsonEnvs).map {
      case EnvironmentVariable(name, value) => MapValue(Map((envvarName, StringValue(name)), (envvarValue, StringValue(value))))
    }
    val envEnv = Map((envvars, MapValue(Map((envvarsList, ListValue(envList))))))

    baseEnv ++ jobEnv ++ portsEnv ++ resourceEnv ++ volumesEnv ++ livenessProbeEnv ++ envEnv
  }

  private def fromOption[A, V](o: Option[A])(f: A => Map[String, V]): Map[String, V] = o.fold(Map.empty[String, V])(f)

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
    val desiredInstances = "desired_instances"
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

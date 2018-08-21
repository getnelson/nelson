package nelson
package blueprint

import nelson.Datacenter.StackName
import nelson.Manifest._
import nelson.docker.Docker.Image

object Render {
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
  }

  def makeEnv(ns: String, sn: StackName, img: Image, p: Plan, ps: Option[Ports]): Map[String, Any] = {
    import Render.keys._

    val baseEnv = Map(
      (stackName, sn.toString),
      (namespace, ns),
      (unitName, sn.serviceType),
      (version, sn.version.toString),
      (image, img.toString)
    )

    val portsEnv = ps match {
      case None => Map.empty[String, Any]

      case Some(ps) =>
        val pl = ps.nel.toList.map(port => Map[String, Any]((portName, port.ref), (portNumber, port.port)))
        Map[String, Any]((ports, Map((portsList, pl))))
    }

    val resourceEnv = p.environment.cpu.fold[Map[String, Any]](
      Map.empty,
      limit => Map((cpuLimit, limit)),
      (request, limit) => Map((cpuRequest, request), (cpuLimit, limit))
    ) ++ p.environment.memory.fold[Map[String, Any]](
      Map.empty,
      limit => Map((memoryLimit, limit)),
      (request, limit) => Map((memoryRequest, request), (memoryLimit, limit))
    )

    val volumesEnv = if (p.environment.volumes.nonEmpty) {
      val volumesList = p.environment.volumes.map {
        case Volume(name, mountPath, size) => Map[String, Any](
          (emptyVolumeMountName, name),
          (emptyVolumeMountPath, mountPath.toString),
          (emptyVolumeMountSize, size)
        )
      }
      Map((emptyVolumes, Map((emptyVolumesList, volumesList))))
    } else Map.empty[String, Any]

    val livenessProbeEnv = p.environment.healthChecks.headOption match {
      case None => Map.empty[String, Any]

      case Some(HealthCheck(_, portRef, _, path, interval, timeout)) =>
        Map[String, Any](
          (healthCheck, Map(
            (healthCheckPath, path.getOrElse("/")),
            (healthCheckPort, portRef),
            (healthCheckInterval, interval.toSeconds),
            (healthCheckTimeout, timeout.toSeconds)
          ))
        )
    }

    baseEnv ++ portsEnv ++ resourceEnv ++ volumesEnv ++ livenessProbeEnv
  }
}

package nelson
package blueprint

import nelson.Datacenter.StackName
import nelson.docker.Docker
import nelson.Manifest._
import nelson.blueprint.EnvValue._
import nelson.docker.Docker.Image

import cats.Id
import cats.instances.string._
import cats.syntax.eq._

object Render {

  type Partial[F[_]] = F[Map[String, EnvValue]]

  def base(img: Image, dc: Datacenter, ns: NamespaceName, unit: UnitDef, plan: Plan, sn: StackName): Partial[Id] =
    makeEnv(img, dc, ns, unit, plan, sn) ++
    makeJob(unit, plan) ++
    makeResources(unit, plan)

  def canopus(img: Image, dc: Datacenter, ns: NamespaceName, unit: UnitDef, plan: Plan, sn: StackName): Partial[Id] =
    base(img, dc, ns, unit, plan, sn) ++
    makeLivenessProbe(plan.environment)

  def magnetar(img: Image, dc: Datacenter, ns: NamespaceName, unit: UnitDef, plan: Plan, sn: StackName, dk: Infrastructure.Docker): Partial[Id] =
    base(img, dc, ns, unit, plan, sn) ++
    makeDocker(dk) ++
    makeNomadHealthChecks(plan.environment) ++
    makeMetadata(unit) ++
    makeVault(List(vault.policies.policyName(sn, ns)), "restart", "")

  def pulsar(img: Image, dc: Datacenter, ns: NamespaceName, unit: UnitDef, plan: Plan, sn: StackName): Partial[Id] =
    canopus(img, dc, ns, unit, plan, sn)

  def makeEnv(img: Image, dc: Datacenter, ns: NamespaceName, unit: UnitDef, p: Plan, sn: StackName): Partial[Id] = {
    import Render.keys._

    val baseEnv = Map(
      (stackName, StringValue(sn.toString)),
      (namespace, StringValue(ns.root.asString)),
      (unitName, StringValue(sn.serviceType)),
      (version, StringValue(sn.version.toString)),
      (image, StringValue(img.toString)),
      (datacenter, StringValue(dc.name))
    )

    val nelsonEnvs = List(
      EnvironmentVariable("NELSON_DNS_ROOT", dc.domain.name),
      EnvironmentVariable("NELSON_DATACENTER", dc.name),
      EnvironmentVariable("NELSON_ENV", ns.root.asString),
      EnvironmentVariable("NELSON_NAMESPACE", ns.asString),
      EnvironmentVariable("NELSON_PLAN", p.name),
      EnvironmentVariable("NELSON_STACKNAME", sn.toString)
    )
    val envList = (p.environment.bindings ++ nelsonEnvs).map {
      case EnvironmentVariable(name, value) => MapValue(Map((envvarName, StringValue(name)), (envvarValue, StringValue(value))))
    }
    val envEnv = Map((envvars, MapValue(Map((envvarsList, ListValue(envList))))))

    baseEnv ++ envEnv
  }

  def makeDocker(d: Infrastructure.Docker): Partial[Id] = {
    import Render.keys._

    val baseEnv = Map(
      (dockerRegistry, StringValue(d.registry)),
      (dockerNetworkMode, StringValue("bridge")))

    val credsEnv = fromOption(d.credentials) {
      case Docker.Credentials(user, password) => Map(
        (dockerUsername, StringValue(user)),
        (dockerPassword, StringValue(password)))
    }

    baseEnv ++ credsEnv
  }

  def makeNomadHealthChecks(env: Manifest.Environment): Partial[Id] = {
    import Render.keys._
    Map((healthCheckList, ListValue(env.healthChecks.map {
      case HealthCheck(name, portRef, protocol, path, interval, timeout) =>
        MapValue(Map(
          (healthCheckName, StringValue(name)),
          (healthCheckPortRef, StringValue(portRef)),
          (healthCheckPath, StringValue(path.getOrElse("/"))),
          (healthCheckProtocol, StringValue(protocol)),
          (healthCheckType, StringValue((if (protocol === "http" || protocol === "https") "http" else protocol).toString)),
          (healthCheckInterval, StringValue(interval.toMillis.toInt.toString)),
          (healthCheckTimeout, StringValue(timeout.toMillis.toInt.toString)),
          (healthCheckTlsSkipVerify, StringValue((if (protocol === "https") true else false).toString))))
    })))
  }

  def makeJob(unit: Manifest.UnitDef, plan: Manifest.Plan): Partial[Id] = {
    import Render.keys._
    fromOption(Manifest.getSchedule(unit, plan).flatMap(_.toCron)) { cronExpr =>
      Map((schedule, StringValue(cronExpr)))
    } ++ fromOption(plan.environment.desiredInstances) { instances =>
      Map((desiredInstances, StringValue(instances.toString)))
    } ++ fromOption(plan.environment.retries) { r =>
      Map((retries, StringValue(r.toString)))
    }
  }

  def makeLivenessProbe(env: Manifest.Environment): Partial[Id] = {
    import Render.keys._
    fromOption(env.healthChecks.headOption) {
      case HealthCheck(_, portRef, _, path, interval, timeout) =>
        Map(
          (healthCheck, MapValue(Map(
            (healthCheckPath, StringValue(path.getOrElse("/"))),
            (healthCheckPort, StringValue(portRef.toString)),
            (healthCheckInterval, StringValue(interval.toSeconds.toString)),
            (healthCheckTimeout, StringValue(timeout.toSeconds.toString))
          ))))
    }
  }

  def makeMetadata(unit: Manifest.UnitDef): Partial[Id] = {
    import Render.keys._
    Map((unitMeta, StringValue(unit.meta.mkString(","))))
  }

  def makeResources(unit: Manifest.UnitDef, plan: Plan): Partial[Id] = {
    import Render.keys._
    val portsEnv = fromOption(unit.ports) { ps =>
      val pl = ps.nel.toList.map(port => MapValue(Map((portName, StringValue(port.ref)), (portNumber, StringValue(port.port.toString)))))
      Map((ports, MapValue(Map((portsList, ListValue(pl))))))
    }

    val resourceEnv = plan.environment.cpu.fold(
      Map.empty,
      limit => Map((cpuLimit, StringValue(limit.toString))),
      (request, limit) => Map((cpuRequest, StringValue(request.toString)), (cpuLimit, StringValue(limit.toString)))
    ) ++ plan.environment.memory.fold(
      Map.empty,
      limit => Map((memoryLimit, StringValue(limit.toString))),
      (request, limit) => Map((memoryRequest, StringValue(request.toString)), (memoryLimit, StringValue(limit.toString)))
    )

    val volumesEnv = if (plan.environment.volumes.nonEmpty) {
      val volumesList = plan.environment.volumes.map {
        case Volume(name, mountPath, size) => MapValue(Map(
          (emptyVolumeMountName, StringValue(name)),
          (emptyVolumeMountPath, StringValue(mountPath.toString)),
          (emptyVolumeMountSize, StringValue(size.toString))
        ))
      }
      Map((emptyVolumes, MapValue(Map((emptyVolumesList, ListValue(volumesList))))))
    } else Map.empty

    portsEnv ++ resourceEnv ++ volumesEnv
  }

  def makeVault(ps: List[String], cm: String, cs: String): Partial[Id] = {
    import Render.keys._
    Map(
      (vaultPolicies, ListValue(ps.map(StringValue))),
      (vaultChangeMode, StringValue(cm)),
      (vaultChangeSignal, StringValue(cs)))
  }

  private def fromOption[A, V](o: Option[A])(f: A => Map[String, V]): Map[String, V] = o.fold(Map.empty[String, V])(f)

  object keys {
    // Unit/Deployment
    val stackName = "stack_name"
    val namespace = "namespace"
    val unitName = "unit_name"
    val unitMeta = "unit_meta"
    val version = "version"
    val image = "image"

    val datacenter = "datacenter"

    val ports = "ports"
    val portsList = "ports_list"
    val portName = "port_name"
    val portNumber = "port_number"

    val healthCheck = "health_check"
    val healthCheckList = "health_checklist"
    val healthCheckInterval = "health_check_interval"
    val healthCheckName = "health_check_name"
    val healthCheckPath = "health_check_path"
    val healthCheckPort = "health_check_port"
    val healthCheckPortRef = "health_check_port_ref"
    val healthCheckProtocol = "health_check_protocol"
    val healthCheckTimeout = "health_check_timeout"
    val healthCheckTlsSkipVerify = "health_check_tls_skip_verify"
    val healthCheckType = "health_check_type"

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

    val dockerRegistry = "docker_registry"
    val dockerUsername = "docker_username"
    val dockerPassword = "docker_password"
    val dockerNetworkMode = "docker_network_mode"

    val vaultPolicies = "vault_policies"
    val vaultChangeMode = "vault_change_mode"
    val vaultChangeSignal = "vault_change_signal"
  }

}

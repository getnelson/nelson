package nelson
package blueprint

import nelson.Datacenter.StackName
import nelson.Manifest._
import nelson.docker.Docker.Image

import simulacrum.typeclass

@typeclass
trait ContextRenderer[A] {
  def inject(a: A): Map[String, EnvValue]
}

object ContextRenderer {
  import EnvValue._

  final case class Base(img: Image, dc: Datacenter, ns: NamespaceName, unit: UnitDef, v: Version, p: Plan, hash: String)

  implicit val baseContextRenderer: ContextRenderer[Base] =
    new ContextRenderer[Base] {
      override def inject(base: Base): Map[String, EnvValue] = {
        import Render.keys._
        import base._

        val sn = StackName(unit.name, v, hash)

        val baseEnv = Map(
          (stackName, StringValue(sn.toString)),
          (namespace, StringValue(ns.root.asString)),
          (unitName, StringValue(sn.serviceType)),
          (version, StringValue(sn.version.toString)),
          (image, StringValue(img.toString)),
          (datacenter, StringValue(dc.name))
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
          EnvironmentVariable("NELSON_STACKNAME", sn.toString)
        )
        val envList = (p.environment.bindings ++ nelsonEnvs).map {
          case EnvironmentVariable(name, value) => MapValue(Map((envvarName, StringValue(name)), (envvarValue, StringValue(value))))
        }
        val envEnv = Map((envvars, MapValue(Map((envvarsList, ListValue(envList))))))

        baseEnv ++ jobEnv ++ portsEnv ++ resourceEnv ++ volumesEnv ++ livenessProbeEnv ++ envEnv
      }
    }

  private def fromOption[A, V](o: Option[A])(f: A => Map[String, V]): Map[String, V] = o.fold(Map.empty[String, V])(f)
}

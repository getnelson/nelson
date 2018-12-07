package nelson
package blueprint

import cats.kernel.Monoid
import cats.syntax.semigroup._

object Render {
  type CR[A] = ContextRenderer[A]

  // todo currently used to determine prioritization of duplicate values, needs more thought
  implicit val envValueMapMonoid: Monoid[Map[String, EnvValue]] =
    new Monoid[Map[String, EnvValue]] {
      def empty: Map[String, EnvValue] = Map.empty[String, EnvValue]

      def combine(x: Map[String, EnvValue],
                  y: Map[String, EnvValue]): Map[String, EnvValue] = x ++ y
    }

  def makeEnv[A0](a0: A0)(implicit CR0: CR[A0]): Map[String, EnvValue] =
    CR0.inject(a0)

  def makeEnv[A0, A1](a0: A0, a1: A1)(implicit CR0: CR[A0], CR1: CR[A1]): Map[String, EnvValue] =
    CR0.inject(a0) |+| CR1.inject(a1)

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

    val datacenter = "datacenter"

    // Vault
    val vaultPolicies = "vault_policies"
    val vaultPolicyName = "vault_policy_name"
    val vaultChangeMode = "vault_change_mode"
    val vaultChangeSignal = "vault_change_signal"
  }
}

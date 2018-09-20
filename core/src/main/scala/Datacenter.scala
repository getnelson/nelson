//: ----------------------------------------------------------------------------
//: Copyright (C) 2017 Verizon.  All Rights Reserved.
//:
//:   Licensed under the Apache License, Version 2.0 (the "License");
//:   you may not use this file except in compliance with the License.
//:   You may obtain a copy of the License at
//:
//:       http://www.apache.org/licenses/LICENSE-2.0
//:
//:   Unless required by applicable law or agreed to in writing, software
//:   distributed under the License is distributed on an "AS IS" BASIS,
//:   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//:   See the License for the specific language governing permissions and
//:   limitations under the License.
//:
//: ----------------------------------------------------------------------------
package nelson

import nelson.Workflow.WorkflowOp
import nelson.docker.DockerOp
import nelson.health.HealthCheckOp
import nelson.loadbalancers.LoadbalancerOp
import nelson.logging.LoggingOp
import nelson.scheduler.SchedulerOp
import nelson.storage.StoreOp
import nelson.vault.Vault

import cats.{~>, Order}
import cats.data.ValidatedNel
import cats.effect.IO
import cats.implicits._

import com.amazonaws.regions.Region

import helm.ConsulOp

import java.net.URI
import java.time.Instant
import java.nio.file.Path

import org.http4s.Uri

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.duration._

object Infrastructure {

  final case class ProxyCredentials(
    username: String,
    password: String
  )

  final case class Docker(
    registry: docker.Docker.RegistryURI
  )

  /**
   * Used when we are setting data in the lighthouse key space, such that
   * lighthouse later knows how to return a fully qualified domain name when
   * returning URIs to users.
   */
  final case class Domain(
    name: String
  )

  final case class Nomad(
    endpoint: Uri,
    timeout: Duration,
    dockerRepoUser: String,
    dockerRepoPassword: String,
    dockerRepoServerAddress: String,
    mhzPerCPU: Int
  )

  final case class Kubernetes(
    mode: KubernetesMode,
    timeout: FiniteDuration
  )

  sealed abstract class KubernetesMode extends Product with Serializable {
    def environment: List[(String, String)] = this match {
      case KubernetesMode.InCluster => List.empty
      case KubernetesMode.OutCluster(path) => List(("KUBECONFIG", path.toString))
    }
  }

  object KubernetesMode {
    final case object InCluster extends KubernetesMode
    final case class OutCluster(kubeconfig: Path) extends KubernetesMode
  }

  final case class Credentials(
    username: String,
    password: String
  )

  final case class Consul(
    endpoint: URI,
    timeout: Duration,
    aclToken: Option[String],
    creds: Option[Credentials]
  )

  final case class AvailabilityZone(
    name: String,
    privateSubnet: String,
    publicSubnet: String
  )

  final case class Aws(
    accessKeyId: String,
    secretAccessKey: String,
    region: Region,
    launchConfigurationName: String,
    elbSecurityGroupNames: Set[String],
    availabilityZones: Set[AvailabilityZone] = Set.empty,
    image: String
  ) {
    import com.amazonaws.auth.BasicAWSCredentials
    import com.amazonaws.services.elasticloadbalancing.AmazonElasticLoadBalancingClient
    import com.amazonaws.services.autoscaling.AmazonAutoScalingClient

    private val creds = new BasicAWSCredentials(accessKeyId, secretAccessKey)

    val asg = new AmazonAutoScalingClient(creds)
      .withRegion[AmazonAutoScalingClient](region)

    val elb = new AmazonElasticLoadBalancingClient(creds)
      .withRegion[AmazonElasticLoadBalancingClient](region)
  }

  final case class TrafficShift(
    policy: TrafficShiftPolicy,
    duration: FiniteDuration
  )

  final case class Interpreters(
    scheduler: SchedulerOp ~> IO,
    consul: ConsulOp ~> IO,
    vault: Vault ~> IO,
    storage: StoreOp ~> IO,
    logger: LoggingOp ~> IO,
    docker: DockerOp ~> IO,
    control: WorkflowControlOp ~> IO,
    health: HealthCheckOp ~> IO
  ) {
    val workflow: WorkflowOp ~> IO =
      scheduler or (vault or (control or (storage or (logger or (docker or consul)))))
  }
}

final case class Datacenter(
  name: String,
  docker: Infrastructure.Docker,
  domain: Infrastructure.Domain,
  defaultTrafficShift: Infrastructure.TrafficShift,
  proxyCredentials: Option[Infrastructure.ProxyCredentials],
  interpreters: Infrastructure.Interpreters,
  loadbalancer: Option[LoadbalancerOp ~> IO],
  policy: PolicyConfig
) {

  @SuppressWarnings(Array("org.brianmckenna.wartremover.warts.IsInstanceOf"))
  override def equals(other: Any): Boolean = {
    other.isInstanceOf[Datacenter] &&
    (other.asInstanceOf[Datacenter].name == this.name)
  }

  lazy val workflow: WorkflowOp ~> IO = interpreters.workflow

  lazy val consul: ConsulOp ~> IO = interpreters.consul

  lazy val storage: StoreOp ~> IO = interpreters.storage

  lazy val health: HealthCheckOp ~> IO = interpreters.health

  override def hashCode: Int = name.hashCode
}

object Datacenter {

  /**
   * A named list of Seed ServiceNames coupled to an environment
   */
  final case class Namespace(
    id: ID,
    name: NamespaceName,
    datacenter: String)

  object Namespace {
    implicit def namespaceOrder: Order[Namespace] =
      Order.whenEqual(Order.by(_.datacenter), Order.by(_.name.asString))
  }

  final case class Port(port: Int, name: String, protocol: String)

  final case class DCUnit(
                    id: ID,
                    name: UnitName,
                    version: Version,
                    description: String,
                    dependencies: Set[ServiceName],
                    resources: Set[String],
                    ports: Set[Port]
                  ) {
    def serviceName: ServiceName = ServiceName(name, version.toFeatureVersion)
  }

  final case class Deployment(
    id: ID,
    unit: DCUnit,
    hash: String,
    namespace: Namespace,
    deployTime: Instant,
    workflow: WorkflowRef,
    plan: String,
    guid: GUID,
    expirationPolicyRef: ExpirationPolicyRef,
    renderedBlueprint: Option[String]
  ) {
    def nsid: ID = namespace.id
    def stackName: StackName = StackName(unit.name, unit.version, hash)
  }
  object Deployment {
    implicit val deploymentOrder: Order[Deployment] =
      Order.whenEqual(
        Order.by(_.unit.version),
        Order.by(_.deployTime)
      )

    def filterByStackName(ds: Set[Deployment], sn: StackName): Set[Deployment] =
      ds.filter(_.stackName != sn)

    def getLatestVersion(ds: Set[Deployment]): Option[Version] =
      ds.foldLeft(Option.empty[Version]) { (maxSoFar, current) =>
        maxSoFar match {
          case Some(version) => Some(version max current.unit.version)
          case None          => Some(current.unit.version)
        }
      }

    def getLatestDeployment(ds: Set[Deployment]): Option[Deployment] = {
      if (ds.isEmpty) None
      else Option(ds.reduceLeft((x,y) => if (x >= y) x else y))
    }
  }

  /**
   * How we will uniquely refer to a deployment outside of Nelson
   *  e.g. service--1-2-3--ae634efe
   */
  final case class StackName(serviceType: UnitName, version: Version, hash: DeploymentHash) {
    override def toString = s"${serviceType}--${version.toExternalString}--${hash}"
  }

  object StackName {
    implicit val stackNameOrder: Order[StackName] = Order.by(_.toString)

    implicit val stackNameOrdering: Ordering[StackName] = stackNameOrder.toOrdering

    def parsePublic(str: String): Option[StackName] = {
      val parts = str.split("--")
      if(parts.length != 3) {
        None
      } else {
        Version.fromPublicString(parts(1)).map(StackName(parts(0), _, parts(2)))
      }
    }

    def validate(str: String): ValidatedNel[String, StackName] =
      parsePublic(str).toValidNel("Unable to parse StackName")
  }

  final case class ServiceName(serviceType: UnitName, version: FeatureVersion) {
    override def toString = s"${serviceType}:${version}"
  }

  final case class LoadbalancerDeployment(
    id: ID,
    nsid: ID,
    hash: String,
    loadbalancer: DCLoadbalancer,
    deployTime: Instant,
    guid: GUID,
    address: String
  ) {

    // because loadbalancers are bound by major version, the version portion of a
    // loadbalancer StackName is always the min version, i.e. 1.0.0 or 2.0.0
    def stackName = StackName(loadbalancer.name, loadbalancer.version.minVersion, hash)
  }

  final case class DCLoadbalancer(
    id: ID,
    name: String,
    version: MajorVersion,
    routes: Vector[Manifest.Route] // didn't want to re-implement these models so i'm using what's in Manifest.scala
  )

  sealed trait Target {
    def serviceType: UnitName
    def deploymentTarget: Deployment
    def deployments: Vector[Deployment]
  }

  final case class TrafficShift(
    from: Deployment,
    to: Deployment,
    policy: TrafficShiftPolicy,
    start: Instant,
    duration: FiniteDuration,
    reverse: Option[Instant] // the instant when a traffic shift was reversed
  ) extends Target {

    val serviceType = from.unit.name

    // The deployment target for shift. In the common case the target is the to deployment.
    // In the case of a reverse then it's the from target as that's now the target being shifted to.
    val deploymentTarget = reverse.fold(to)(_ => from)

    lazy val end = start.plusSeconds(duration.toSeconds)

    def deployments = Vector(from,to)

    def fromValue: Double = policy.run(start, time(Instant.now), duration)

    /*
     * Calculates if the traffic shift is in progress. In the normal case
     * the timestamp (ts) needs to be before the end of the traffic shift (start + duration).
     * In the case of a reverse it is calcuated based on whether or not
     * the amount of time that has elapsed since the reverse is less than
     * the amount of time it will take to reverse to the start
     */
    def inProgress(ts: Instant): Boolean =
      reverse.fold(ts.isAfter(start) && ts.isBefore(end)) { reverseStart =>
        val rsMilli = reverseStart.toEpochMilli
        val tsMilli = ts.toEpochMilli
        val stMilli = start.toEpochMilli
        val reverseTime = rsMilli - stMilli
        val elapsed = tsMilli - rsMilli
        elapsed < reverseTime
      }

    /*
     * time is used by the traffic shifting policy to calculate the
     * current traffic weights. In the normal case the value is just now,
     * however when a traffic shift is reversed time slides backwards from
     * the point when the reverse happened to the start
     */
    private def time(ts: Instant): Instant =
      reverse.fold(ts) { reverseStart =>
        val rsMilli = reverseStart.toEpochMilli
        val tsMilli = ts.toEpochMilli
        val delta = tsMilli - rsMilli
        val reverse = rsMilli - delta
        Instant.ofEpochMilli(reverse)
      }
  }

  final case class SingletonTarget(d: Deployment) extends Target {
    def serviceType = d.unit.name
    def deploymentTarget = d
    def deployments = Vector(d)
  }

  /**
   * Represents the information needed to create a manual deployment.
   */
  final case class ManualDeployment(
    datacenter: String,
    namespace: String,
    serviceType: String,
    version: String,
    hash: String,
    description: String,
    port: Int
  )

  implicit val datacenterOrder: Order[Datacenter] = Order.by(_.name)

  final case class StatusUpdate(stack: StackName,
                                status: DeploymentStatus,
                                msg: Option[String])

  object StatusUpdate {
    import argonaut.DecodeJson
    import routing.Discovery.stackNameCodec

    implicit val decodeStatusUpdate: DecodeJson[StatusUpdate] =
      DecodeJson.jdecode3L(StatusUpdate.apply)("stack", "status", "msg")
  }

}

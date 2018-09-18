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

import nelson.Manifest._
import nelson.cleanup.ExpirationPolicy
import nelson.notifications.NotificationSubscriptions
import nelson.storage.StoreOp
import nelson.blueprint.Blueprint

import cats.{~>, Foldable, Monoid}
import cats.data.{Kleisli, NonEmptyList, ValidatedNel}
import cats.effect.IO
import cats.implicits._

import java.net.URI
import java.nio.file.Path

import scala.concurrent.duration._

final case class Manifest(
  units: List[UnitDef],
  plans: List[Plan],
  loadbalancers: List[Loadbalancer],
  namespaces: List[Namespace],
  targets: DeploymentTarget,
  notifications: NotificationSubscriptions
)

object Manifest {

  trait Versioned

  val Versioned = Tag.of[Versioned]

  final case class UnitDef(
    name: String,
    description: String,
    dependencies: Map[String, FeatureVersion],
    resources: Set[Resource],
    alerting: Alerting,
    ports: Option[Ports],
    deployable: Option[Deployable],
    meta: Set[String]
  )

  final case class Plan(
    name: String,
    environment: Environment
  )

  object Plan {
    val default = Plan("default", Environment(workflow = Pulsar))
  }

  sealed abstract class ResourceSpec extends Product with Serializable {
    import ResourceSpec._

    def fold[A](unspecified: A, limitOnly: Double => A, bounded: (Double, Double) => A): A = this match {
      case Unspecified             => unspecified
      case LimitOnly(limit)        => limitOnly(limit)
      case Bounded(request, limit) => bounded(request, limit)
    }

    def limitOrElse(default: => Double): Double =
      fold(default, identity, (_, limit) => limit)
  }

  object ResourceSpec {
    private final case object Unspecified                                  extends ResourceSpec
    private final case class  LimitOnly   (limit: Double)                  extends ResourceSpec
    private final case class  Bounded     (request: Double, limit: Double) extends ResourceSpec

    val unspecified: ResourceSpec = Unspecified

    def limitOnly(limit: Double): Option[ResourceSpec] =
      if (limit >= 0.0) Some(LimitOnly(limit))
      else None

    def bounded(request: Double, limit: Double): Option[ResourceSpec] =
      if (request >= 0.0 && request <= limit) Some(Bounded(request, limit))
      else None
  }

  final case class Environment(
    cpu: ResourceSpec = ResourceSpec.unspecified,
    memory: ResourceSpec = ResourceSpec.unspecified,
    desiredInstances: Option[Int] = None,
    retries: Option[Int] = None,
    constraints: List[Constraint] = Nil,
    alertOptOuts: List[AlertOptOut] = Nil,
    bindings: List[EnvironmentVariable] = Nil,
    healthChecks: List[HealthCheck] = Nil,
    resources: Map[String, URI] = Map.empty,
    schedule: Option[Schedule] = None,
    policy: Option[ExpirationPolicy] = None,
    trafficShift: Option[TrafficShift] = None,
    volumes: List[Volume] = List.empty,
    workflow: Workflow[Unit] = Magnetar,
    blueprint: Option[Either[BlueprintRef, Blueprint]] = None
  )

  final case class Namespace(
    name: NamespaceName,
    units: Set[(UnitRef, Set[PlanRef])], // String references as defined in the actual manifest yaml file
    loadbalancers: Set[(LoadbalancerRef, Option[PlanRef])]
  )

  final case class Resource(
    name: String,
    description: Option[String] = None
  )

  /*
   * Loadbalancers represent the end of the world for nelson. The allow the outside world
   * to connect to services deployed by nelson inside a private datacenter. A loadbalancer
   * defines a list of routes which it is repsonsible for proxying into the datacenter.
   */
  final case class Loadbalancer(
    name: String,
    routes: Vector[Route],
    majorVersion: Option[MajorVersion] = None
  )
  /**
   * The concept over here is that a loadbalancer has routable units associated
   * to it, which form a superset of the "dependency" concept.
   * An example of a loadbalanced dependecy would be:
   *
   * Route ->  BackendDestination("foo", "default")
   *
   * This makes *nelson* resolve the most recent stack for `foo` and
   * declares that the port exposed by `foo` unit with the reference
   * `default` will be the destination for traffic from this proxy config.
   *
   * The Route also defines a port which is exposed externally on the loadbalancer
   */
  final case class Route(
    port: Port,
    destination: BackendDestination
  ) {
    def asString: String =
      s"${port.asString}:${destination.asString}"
  }

  final case class BackendDestination(
    name: UnitName,
    portReference: String
  ) {
    def asString: String =
      s"$name->$portReference"
  }

  sealed trait DeploymentTarget {
    def values: Seq[String]
  }
  object DeploymentTarget {
    // whitelist
    final case class Only(values: Seq[String]) extends DeploymentTarget
    // blacklist
    final case class Except(values: Seq[String]) extends DeploymentTarget
  }

  final case class Ports(default: Port, others: List[Port]) {
    def nel: NonEmptyList[Port] = NonEmptyList.of(default, others: _*)
  }

  final case class Port(ref: String, port: Int, protocol: String) {
    def isDefault: Boolean = ref === Port.defaultRef
    def asString = s"$ref->$port/$protocol"
  }

  object Port {
    val defaultRef: String = "default"
  }

  /** An empty scratch volume to be mounted onto a container */
  final case class Volume(name: String, mountPath: Path, size: Int)

  final case class AlertOptOut(ref: String)

  final case class Alerting(
    prometheus: PrometheusConfig
  )

  object Alerting {
    val empty = Alerting(PrometheusConfig.empty)
  }

  final case class PrometheusConfig(
    alerts: List[PrometheusAlert],
    rules: List[PrometheusRule]
  )

  object PrometheusConfig {
    val empty = PrometheusConfig(Nil, Nil)
  }

  final case class PrometheusAlert(
    alert: String,
    expression: String
  )

  final case class PrometheusRule(
    rule: String,
    expression: String
  )

  final case class Deployable(
    name: String,
    version: Version,
    output: Deployable.Output
  )
  object Deployable {
    sealed trait Output
    final case class Container(image: String) extends Output
  }

  sealed trait Constraint {
    def fieldName: String
  }

  object Constraint {
    import scala.util.matching.Regex
    final case class Unique(fieldName: String) extends Constraint
    final case class Cluster(fieldName: String, param: String) extends Constraint
    final case class GroupBy(fieldName: String, param: Option[Int]) extends Constraint
    final case class Like(fieldName: String, param: Regex) extends Constraint
    final case class Unlike(fieldName: String, param: Regex) extends Constraint
  }

  final case class EnvironmentVariable(
    name: String,
    value: String
  ) {
    override def toString: String =
      s"${name.trim.toUpperCase}=${value.trim}"
  }

  final case class HealthCheck(
    name: String,
    portRef: String,
    protocol: String,
    path: Option[String],
    interval: FiniteDuration,
    timeout: FiniteDuration
  )

  final case class TrafficShift(
    policy: TrafficShiftPolicy,
    duration: FiniteDuration
  )

  final case class Action(
    config: ActionConfig,
    run: Kleisli[IO, (NelsonConfig, ActionConfig), Unit]
  )

  final case class ActionConfig(
    datacenter: Datacenter,
    namespace: Namespace,
    plan: Plan,
    hash: String,
    notifications: NotificationSubscriptions
  )

  def isPeriodic(unit: UnitDef, plan: Plan): Boolean =
    plan.environment.schedule.isDefined

  def getSchedule(unit: UnitDef, plan: Plan): Option[Schedule] =
    plan.environment.schedule

  def getExpirationPolicy(unit: UnitDef, plan: Plan): Option[cleanup.ExpirationPolicy] =
    plan.environment.policy

  def toAction[A](a: A, dc: Datacenter, ns: Namespace, p: Plan, n: NotificationSubscriptions)(implicit A: Actionable[A]): Action = {
    val hash = randomAlphaNumeric(desiredLength = 8) // create a unique hash for this deployment
    val config = ActionConfig(dc, ns, p, hash, n)
    val action = A.action(a)
    Action(config, action)
  }

  /*
   * Saturates the manifest with all the bits that a unit or loadbalancer needs for deployment.
   */
  def saturateManifest(m: Manifest)(r: Github.Release): IO[Manifest @@ Versioned] = {
    val units = addDeployable(m)(r)
    val lbs = addVersionToLoadbalancers(m)(r)
    units.map(u => Versioned(m.copy(units = u, loadbalancers = lbs)))
  }

  /*
   * convert units in the manifest to actions, filtered by f
   */
  def unitActions(m: Manifest @@ Versioned, dcs: Seq[Datacenter], f: (Datacenter,Namespace,Plan,UnitDef) => Boolean): List[Action] = {
    val mnf = Versioned.unwrap(m)
    val us = units(mnf, dcs)
    val uf = us.filter { case (dc,ns,pl,unit) => f(dc,ns,pl,unit) }
    uf.map { case (dc,ns,pl,unit) =>
      Manifest.toAction(Versioned(unit), dc, ns, pl, mnf.notifications)
    }
  }

  /*
   * convert loadbalancers in the manifest to actions, filtered by f
   */
  def loadbalancerActions(m: Manifest @@ Versioned, dcs: Seq[Datacenter], f: (Datacenter,Namespace,Plan,Loadbalancer) => Boolean): List[Action] = {
    val mnf = Versioned.unwrap(m)
    val lbs = loadbalancers(mnf, dcs)
    val lf = lbs.filter { case (dc,ns,pl,lb) => f(dc,ns,pl,lb) }
    lf.map { case (dc,ns,pl,lb) =>
      Manifest.toAction(Versioned(lb), dc, ns, pl, mnf.notifications)
    }
  }

  /*
   * Enumerates all the combinations of Datacenter/Namespace/Plan/UnitDef as dictated
   * by the manifest. If a unit reference in the namespace plan does't reference a
   * specific plan use the default
   */
  def units(m: Manifest, dcs: Seq[Datacenter]): List[(Datacenter,Namespace,Plan,UnitDef)] = {
    type Res = (Datacenter,Namespace,Plan,UnitDef)
    val unitFolder: (Datacenter,Namespace,Plan,UnitDef,List[Res]) => List[Res] =
      (dc, ns, p, u, res) => (dc, ns, p, u) :: res

    foldUnits(m, dcs, unitFolder, Nil)
  }

  /*
   * Enumerates all the combinations of Datacenter/Namespace/Loadbalancer as dictated
   * by the manifest.
   */
  def loadbalancers(m: Manifest, dcs: Seq[Datacenter]): List[(Datacenter,Namespace,Plan,Loadbalancer)] = {
    type Res = (Datacenter,Namespace,Plan,Loadbalancer)
    val lbFolder: (Datacenter,Namespace,Plan,Loadbalancer,List[Res]) => List[Res] =
      (dc, ns, pl, lb, res) => (dc,ns,pl,lb) :: res

    foldLoadbalancers(m, dcs, lbFolder, Nil)
  }

  /*
   * folds over all the datacenters, and namespaces
   */
  def foldNamespaces[A](m: Manifest, dcs: Seq[Datacenter], f: (Datacenter,Namespace,A) => A, a: A): A =
    filterDatacenters(dcs)(m.targets).foldLeft(a)((a,d) => m.namespaces.foldLeft(a)((a,ns) => f(d,ns,a)))

  /*
   * folds over all the datacenters, namespaces, and loadbalancers specified by the Manifest
   */
  def foldLoadbalancers[A](m: Manifest, dcs: Seq[Datacenter], f: (Datacenter,Namespace,Plan,Loadbalancer,A) => A, a: A): A = {
    val folder: (Datacenter,Namespace,A) => A  =
      (dc,ns,a) => ns.loadbalancers.foldLeft(a){ (a,ref) =>
        val (lbRef, plRef) = ref
        val loadbalancer = m.loadbalancers.find(_.name == lbRef)
        val plan = m.plans.find(p => plRef.exists(_ == p.name)).getOrElse(Plan.default)
        loadbalancer.fold(a)(lb => f(dc,ns,plan,lb,a))
      }

    foldNamespaces(m,dcs,folder,a)
  }

  /**
   * fold over all the datacenters, namespaces, (unit, plans) combinations specified by the Manifest.
   */
  def foldUnits[A](m: Manifest, dcs: Seq[Datacenter], f: (Datacenter,Namespace,Plan,UnitDef,A) => A, a: A): A = {
    val folder: (Datacenter,Namespace,A) => A  =
      (dc,ns,a) => ns.units.foldLeft(a){ (a,u) =>
        val (unitRef, planRefs) = u
        val unit: Option[UnitDef] = m.units.find(_.name == unitRef)
        val ps: List[Plan] = m.plans.filter(p => planRefs.exists(_ == p.name))
        val plans = if (ps.isEmpty) List(Plan.default) else ps
        plans.foldLeft(a)((a,p) => unit.fold(a)(u => f(dc,ns,p,u,a)))
      }

    foldNamespaces(m,dcs,folder,a)
  }

  def verifyDeployable(m: Manifest, dcs: Seq[Datacenter], storage: StoreOp ~> IO): IO[ValidatedNel[NelsonError,Unit]] = {
    val folder: (Datacenter,Namespace,Plan,UnitDef,List[IO[ValidatedNel[NelsonError,Unit]]]) => List[IO[ValidatedNel[NelsonError,Unit]]] =
      (dc,ns,p,u,res) => StoreOp.verifyDeployable(dc.name, ns.name, u).foldMap(storage) ::  res

    implicit val monoid: Monoid[ValidatedNel[NelsonError, Unit]] =
      new Monoid[ValidatedNel[NelsonError, Unit]] {
        def combine(x: ValidatedNel[NelsonError, Unit], y: ValidatedNel[NelsonError, Unit]): ValidatedNel[NelsonError, Unit] =
          x combine y

        def empty: ValidatedNel[NelsonError, Unit] = ().validNel
      }

    foldUnits(m, dcs, folder, Nil)
      .sequence
      .map(l => Foldable[List].fold(l))
  }

  private def addVersionToLoadbalancers(m: Manifest)(r: Github.Release): List[Loadbalancer] = {
    val major = Version.fromString(r.tagName).map(_.toMajorVersion)
    m.loadbalancers.map(lb => lb.copy(majorVersion = major))
  }

  private def addDeployable(m: Manifest)(r: Github.Release): IO[List[UnitDef]] =
    m.units.traverse(u => parseDeployable(r, u.name).map(d => u.copy(deployable = Some(d))))

  /**
   * feels a little weird coupling the DeployableParser to
   * this function, but right now its the most obvious
   * place i could find to put it.
   */
  private def parseDeployable(release: Github.Release, name: String): IO[Deployable] = {
    release.findAssetContent(s"${name}.deployable.yml").recoverWith {
      case ProblematicDeployable(_, _) => release.findAssetContent(s"${name}.deployable.yaml")
    }.flatMap { a =>
      yaml.DeployableParser.parse(a).fold(e => IO.raiseError(MultipleErrors(e)), IO.pure)
    }
  }

  private[nelson] def filterDatacenters(dcs: Seq[Datacenter])(targets: DeploymentTarget): Seq[Datacenter] =
    targets match {
      case DeploymentTarget.Only(what)   =>
        what.flatMap(d => dcs.find(_.name == d))

      case DeploymentTarget.Except(what) =>
        dcs.foldLeft(List.empty[Datacenter])((a,b) =>
          if(what.exists(_.trim.toLowerCase == b.name.trim.toLowerCase)){ a }
          else { a :+ b }
        )
    }

  def versionedUnits(m: Manifest @@ Versioned): List[UnitDef @@ Versioned] =
    Versioned.subst(Versioned.unwrap(m).units)
}

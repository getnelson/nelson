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
package yaml

import java.net.URI
import scala.concurrent.duration.{Duration,FiniteDuration,MILLISECONDS}
import scalaz.Apply
import scala.beans.BeanProperty
import java.util.{ArrayList => JList}
import scalaz.{\/,NonEmptyList,ValidationNel,Validation,State}
import cleanup.{ExpirationPolicy}
import notifications._
import BiggerApplies._

object ManifestV1Parser {
  // putting implicits in module scope
  import scalaz.std.list._
  import scalaz.std.vector._
  import YamlParser.fromYaml
  import scalaz.std.string._
  import scalaz.syntax.nel._
  import scalaz.syntax.equal._
  import scalaz.syntax.either._
  import Manifest._, YamlError._
  import scalaz.syntax.traverse._
  import scalaz.syntax.validation._
  import scalaz.syntax.std.option._
  import scalaz.std.option._
  import scalaz.syntax.applicative._
  import scala.collection.JavaConverters._
  import scala.concurrent.duration._

  type YamlValidation[A] = ValidationNel[YamlError, A]

  def parseDependency(dep: DependencyYaml): YamlValidation[(String,FeatureVersion)] = {
    val str = dep.ref
    val parts = str.split('@')

    if(parts.size == 2) {
      FeatureVersion.fromString(parts(1)).toSuccessNel(InvalidFeatureVersion(str, parts(1))) map {v =>
        val n = parts(0)
        n -> v
      }
    } else {
      Validation.failureNel(InvalidServiceName(str))
    }
  }

  def parseResource(r: ResourceYaml): YamlValidation[Resource] = {
    (parseAlphaNumHyphen(r.name, "unit.resources.name") |@|
     Validation.success(Option(r.description)))((a,b) => Manifest.Resource(a, b))
  }

  def validateMeta(s: String): YamlValidation[String] = {

    def validateLength =
      if (s.length <= 14) Validation.success(s).toValidationNel
      else Validation.failure(invalidMetaLength(s)).toValidationNel

    (parseAlphaNumHyphen(s, "unit.meta") |@| validateLength)((a,_) => a)
  }

  private val alphaNumHyphen = """[a-zA-Z0-9-]*""".r
  def parseAlphaNumHyphen(str: String, fieldName: String): YamlValidation[String] =
    if (alphaNumHyphen.pattern.matcher(str).matches)
      Validation.success(str).toValidationNel
    else
      Validation.failure(invalidAlphaNumHyphen(str,fieldName)).toValidationNel

  def toVolume(v: VolumeYaml): Volume =
    Volume(v.mount, v.source, v.mode)

  val noDefaultPortError: YamlError =
    invalidPortSpecification(s"a ${Port.defaultRef} port must be specified")

  val multipleDefaultPortError: YamlError =
    invalidPortSpecification(s"multiple ${Port.defaultRef} ports were defined")

  def resolveWorkflow(name: String) =
    Workflow.fromString(name)
      .toSuccessNel(YamlError.invalidWorkflow(name))

  def resolvePolicy(name: String): YamlValidation[ExpirationPolicy] =
    ExpirationPolicy.fromString(name)
      .toSuccessNel(YamlError.unknownExpirationPolicyRef(name))

  def parseURI(raw: String): YamlValidation[URI] =
    \/.fromTryCatchNonFatal(new URI(raw))
      .leftMap(e => YamlError.invalidURI(raw)).validationNel

  def parseSchedule(str: String): YamlValidation[Schedule] =
    Schedule.parse(str).leftMap(invalidSchedule).validationNel

  def toPlanResource(raw: PlanResourceYaml): YamlValidation[(String,URI)] =
    (Option(raw.ref).toSuccessNel(missingProperty("plan.resources.ref")) |@|
     parseURI(raw.uri))((a,b) => (a,b))

  def validatePlanConstraints[A : Numeric](n: A, e: A => YamlError)(f: A => Boolean): YamlValidation[A] =
    if (f(n)) Validation.success(n).toValidationNel
    else Validation.failure(e(n)).toValidationNel

  def validateCPU(i:Double): YamlValidation[Double] =
    validatePlanConstraints(i, invalidCPU)(i => i > 0 && i < 100)

  def validateMemory(i:Double): YamlValidation[Double] =
    validatePlanConstraints(i, invalidMemory)(i => i > 0 && i < 50000)

  def validateInstances(i:Int): YamlValidation[Int] =
    validatePlanConstraints(i, invalidInstances)(i => i > 0 && i < 500)

  def parseDuration(d: String): YamlError \/ FiniteDuration =
    \/.fromTryCatchNonFatal {
      val dur = Duration(d)
      FiniteDuration(dur.toMillis, MILLISECONDS)
    }.leftMap(e => YamlError.invalidDuration(d, e.getMessage))

  def validateTrafficShiftDuration(dur: String): YamlValidation[FiniteDuration] =
    parseDuration(dur).flatMap(d =>
      if (d <= 1.minutes || d >= 5.days) \/.left(YamlError.invalidTrafficShiftDuration(dur))
      else \/.right(d)
    ).validationNel

  def validateTrafficShift(raw: TrafficShiftYaml): YamlValidation[TrafficShift] =
    (TrafficShiftPolicy.fromString(raw.policy).toSuccessNel(YamlError.invalidTrafficShiftPolicy(raw.policy)) |@|
     validateTrafficShiftDuration(raw.duration))(TrafficShift)

  def toPlan(raw: PlanYaml): YamlValidation[Plan] =
    Apply[YamlValidation].apply14(
     parseAlphaNumHyphen(raw.name, "plan.name"),
     Option(raw.cpu).filter(_ > 0).traverse(d => validateCPU(d)),
     Option(raw.memory).filter(_ > 0).traverse(d => validateMemory(d)),
     Option(raw.instances).flatMap(x => Option(x.desired).filter(_ > 0)).traverse(d => validateInstances(d)),
     Validation.success(Option(raw.retries).filter(_ > 0)),
     Validation.success(raw.constraints.asScala.toList.flatMap(toConstraint)),
     Validation.success(raw.alert_opt_outs.asScala.toList.map(AlertOptOut)), // opt out -> unit resolution is validated later
     Validation.success(raw.volumes.asScala.toList.map(toVolume)),
     Validation.success(raw.environment.asScala.toList.flatMap(toEnvironmentVariable)),
     raw.health_checks.asScala.toList.traverse(toHealthCheck),
     raw.resources.asScala.toList.traverse(toPlanResource).map(_.toMap),
     Option(raw.schedule).traverse(s => parseSchedule(s)),
     Option(raw.expiration_policy).traverse(resolvePolicy),
     Option(raw.traffic_shift).traverse(validateTrafficShift)
    )((a,b,c,d,e,f,g,h,i,j,k,l,m,n) => Plan(a, Environment(b,c,d,e,f,g,h,i,j,k,l,m,n)))

  def toHealthCheck(raw: HealthCheckYaml): YamlValidation[HealthCheck] = {
    def parseProtocol(s: String): YamlValidation[String] =
      if (s == "http" || s == "https" || s == "tcp")
        Validation.success(s).toValidationNel
      else
        Validation.failure(invalidProtocol(s)).toValidationNel

    def validateDuration(d: String): YamlValidation[FiniteDuration] =
      parseDuration(d).validationNel

    (parseAlphaNumHyphen(raw.name, "health_check.name") |@|
     Option(raw.port_reference).toSuccessNel(missingProperty("health_check.port_reference")) |@|
     parseProtocol(raw.protocol) |@|
     Validation.success(Option(raw.path)) |@|
     validateDuration(raw.interval) |@|
     validateDuration(raw.timeout)
   )((a,b,c,d,e,f) => HealthCheck(a,b,c,d,e,f))
  }

  def toUnit(raw: UnitYaml): YamlValidation[UnitDef] = {
    def mkPort(value: String): YamlValidation[Port] =
      PortParser.parse(value)
        .leftMap(e => YamlError.invalidPortSpecification(e.getMessage))
        .validationNel

    def toPorts(l: List[Port]): YamlError \/ Ports = {
      l.foldLeftM[YamlError \/ ?, (Option[Port], List[Port])]((None, Nil)){ case ((defaultMaybe, rest), port) =>
        if (port.isDefault) {
          if (defaultMaybe.isDefined) multipleDefaultPortError.left
          else (Some(port), rest).right
        } else (defaultMaybe, port :: rest).right
      } flatMap { case (defaultMaybe, rest) =>
          defaultMaybe.toRightDisjunction(noDefaultPortError).map(Ports(_, rest))
      }
    }

    def mkPorts(portDeclarations: List[String]): YamlValidation[Ports] =
      (for {
        portDeclarations <- Option(portDeclarations).toRightDisjunction(missingProperty("unit.ports").wrapNel)
        ports <- portDeclarations.traverse(mkPort).disjunction
        ports <- toPorts(ports).leftMap(_.wrapNel)
      } yield ports).validation

    def validateJList[A, B](field: String, raw: JList[A], f: A => YamlValidation[B]): YamlValidation[List[B]] =
      Option(raw).cata(
        some = _.asScala.toList.traverse(a => f(a)),
        none = Validation.failure(emptyList(field)).toValidationNel)

    Apply[YamlValidation].apply11(
      Option(raw.name).toSuccessNel(missingProperty("unit.name")),
      Option(raw.description).toSuccessNel(missingProperty("unit.description")),
      validateJList("unit.dependencies", raw.dependencies, parseDependency _).map(_.toMap),
      validateJList("unit.resources", raw.resources, parseResource _).map(_.toSet),
      parseAlerting(raw.name, raw.alerting),
      resolveWorkflow(raw.workflow),
      Option(raw.ports).filter(_.size > 0).traverse(p => mkPorts(p.asScala.toList)),
      Validation.success(None),
      validateJList("unit.meta", raw.meta, validateMeta _).map(_.toSet),
      Option(raw.schedule).traverse(s => parseSchedule(s)),
      Option(raw.expiration_policy).traverse(resolvePolicy)
    )(UnitDef.apply)
  }

  def toDeploymentTarget(dcs: DatacenterTargetYaml): DeploymentTarget = {
    val only = Option(dcs).toList.flatMap(_.except.asScala.toList)
    val except = Option(dcs).toList.flatMap(_.except.asScala.toList)
    (only, except) match {
      case (o,Nil) if o.nonEmpty => DeploymentTarget.Only(o)
      case (Nil,e) if e.nonEmpty => DeploymentTarget.Except(e)
      case _                     => DeploymentTarget.Except(Nil) // yolo
    }
  }

  def toLoadbalancer(raw: LoadbalancerYaml): YamlValidation[Loadbalancer] = {

     def fromRoute(r: RouteYaml): ValidationNel[YamlError, Route] = {
       val result = {
         val dest = BackendDestinationParser.parse(r.destination).leftMap(e => YamlError.loadError(e.getMessage))
         val port = PortParser.parse(r.expose).leftMap(e => YamlError.loadError(e.getMessage))
         (dest |@| port)((dest, port) => Route(port, dest))
       }
       result.validation.toValidationNel
     }

    def fromRoutes(r: Vector[RouteYaml]): ValidationNel[YamlError, Vector[Route]] =
       r.traverseU(fromRoute(_))

    (parseAlphaNumHyphen(raw.name, "loadbalancer.name") |@|
     fromRoutes(raw.routes.asScala.toVector) |@|
     Validation.success(None))(Loadbalancer.apply)
  }

  /**
   * TIM: need to do something better here with errors.
   */
  def toEnvironmentVariable(s: String): Option[EnvironmentVariable] =
    \/.fromTryCatchNonFatal {
      val Array(k,v) = s.split('=')
      EnvironmentVariable(k,v)
    }.toOption

  @SuppressWarnings(Array("org.brianmckenna.wartremover.warts.Product", "org.brianmckenna.wartremover.warts.Serializable"))
  def toConstraint(c: ConstraintYaml): List[Constraint] = {
    import scala.util.matching.Regex
    c.unique.asScala.toList.map(Constraint.Unique(_)) ++
    c.cluster.asScala.toList.map(cluster =>
      Constraint.Cluster(cluster.fieldName, cluster.param)) ++
    c.groupBy.asScala.toList.map(group =>
      Constraint.GroupBy(group.fieldName, Some(group.param).filter(_ > 0))) ++
    c.like.asScala.toList.map(like =>
      Constraint.Like(like.fieldName, new Regex(like.param))) ++
    c.unlike.asScala.toList.map(unlike =>
      Constraint.Unlike(unlike.fieldName, new Regex(unlike.param)))
  }

  def parseNotifications(raw: NotificationYaml): YamlValidation[NotificationSubscriptions] = {
    val slack = parseSlackNotifications(raw.slack).map(_.map(SlackSubscription(_)))
    val email = parseEmailNotifications(raw.email).map(_.map(EmailSubscription(_)))
    (slack |@| email)(NotificationSubscriptions.apply)
  }

  private[this] val validEmail = """(?i)\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,4}\b""".r
  def parseEmailNotifications(raw: NotificationEmailYaml): YamlValidation[List[String]] = {
    def parseEmail(str: String): YamlValidation[String] =
      if(validEmail.pattern.matcher(str).matches) Validation.success(str)
      else Validation.failureNel(invalidEmail(str))

    raw.recipients.asScala.toList.traverse(parseEmail)
  }

  def parseSlackNotifications(raw: NotificationSlackYaml): YamlValidation[List[String]] =
    raw.channels.asScala.toList.successNel

  def parseAlerting(unitName: UnitName, rawAlerting: AlertingYaml): YamlValidation[Alerting] =
    parsePrometheusAlerting(unitName, rawAlerting.prometheus).map(Alerting.apply)

  def parsePrometheusAlerting(unitName: UnitName, rawPrometheus: PrometheusConfigYaml): YamlValidation[PrometheusConfig] = {
    (
      rawPrometheus.alerts.asScala.toList.traverseSTrampoline(parsePrometheusAlert).run(Set.empty)._2 |@|
      rawPrometheus.rules.asScala.toList.traverseSTrampoline(parsePrometheusRule).run(Set.empty)._2
    )(PrometheusConfig.apply)
    /*
    configV match {
      case Success(config) =>
        // All we know at this point was that it was well-formed yaml.  If we
        // don't also syntax check the rules, we will bring down Prometheus.
        Promtool.validateRules(unitName, config) map {
          case Promtool.Valid =>
            config.successNel
          case i: Promtool.Invalid =>
            YamlError.invalidPrometheusRules(i.msg).failureNel
        }
      case failure @ Failure(_) =>
        Task.now(failure)
    }
     */
  }

  def parsePrometheusAlert(rawAlert: PrometheusAlertYaml): State[Set[String], YamlValidation[PrometheusAlert]] =
    for {
      seen <- State.get[Set[String]]
      name =  rawAlert.alert.toSnakeCase
      _    <- State.put(seen + name)
    } yield if (seen.contains(name))
      YamlError.duplicateAlertName(name).failureNel
    else
      PrometheusAlert(name, rawAlert.expression).successNel

  def parsePrometheusRule(rawRule: PrometheusRuleYaml): State[Set[String], YamlValidation[PrometheusRule]] =
    for {
      seen <- State.get[Set[String]]
      name =  rawRule.rule.toSnakeCase
      _    <- State.put(seen + name)
    } yield if (seen.contains(name))
      YamlError.duplicateRuleName(name).failureNel
    else
      PrometheusRule(name, rawRule.expression).successNel

  /**
   *
   */
  def parse(input: String): NonEmptyList[NelsonError] \/ Manifest = {

    def toUnits(list: List[UnitYaml]): NonEmptyList[NelsonError] \/ List[UnitDef] =
      list.traverse(toUnit).disjunction

    def toPlans(list: List[PlanYaml]): NonEmptyList[NelsonError] \/ List[Plan] =
      list.traverse(toPlan).disjunction

    def toLoadbalancers(list: List[LoadbalancerYaml]): NonEmptyList[NelsonError] \/ List[Loadbalancer] =
      list.traverse(toLoadbalancer).disjunction

    def toNamespaces(list: List[NamespaceYaml], us: List[UnitDef], ps: List[Plan], lbs: List[Loadbalancer]): NonEmptyList[NelsonError] \/ List[Namespace] = {
      def resolvePlan(name: String) =
        ps.find(_.name === name)
          .toSuccessNel(YamlError.unknownPlanRef(name))

      def validateOptOut(unit: Manifest.UnitDef)(out: AlertOptOut) =
        if (unit.alerting.prometheus.alerts.exists(_.alert === out.ref)) out.successNel
        else YamlError.unknownAlertOptOutRef(unit, out.ref).failureNel

      def parseNamespaceName(s: String) =
         NamespaceName.fromString(s)
           .leftMap(e => YamlError.invalidNamespace(s, e.getMessage))
           .validationNel

       import scalaz.Validation.FlatMap._
      list.traverse { n =>
        val units = n.units.asScala.toList.traverse { ns =>
          val resolvedUnit =
            us.find(_.name === ns.ref)
              .toSuccessNel(YamlError.unknownUnitRef(ns.ref))

          for {
            ru <- resolvedUnit
            rp <- ns.plans.asScala.toList.traverse(resolvePlan)
            _  <- rp.traverse(_.environment.alertOptOuts.traverse(validateOptOut(ru)))
          } yield (ru.name, rp.map(_.name).toSet)
        }

        val loadbalancers = n.loadbalancers.asScala.toList.traverse { lb =>
          val resolveLb =
            lbs.find(_.name == lb.ref).map(_.name)
              .toSuccessNel(YamlError.unknownLoadbalancerRef(lb.ref))

           for {
             rlb <- resolveLb
             rp <- Option(lb.plan).traverse(resolvePlan)
           } yield (rlb, rp.map(_.name))
        }

        val name = parseNamespaceName(n.name)

        (units |@| loadbalancers |@| name)((u,l,n) => Namespace(n, u.toSet, l.toSet)).disjunction
      }
    }

    for {
      mf <- fromYaml[ManifestYaml](input).leftMap(NonEmptyList(_))
      us <- toUnits(mf.units.asScala.toList)
      ps <- toPlans(mf.plans.asScala.toList)
      lb <- toLoadbalancers(mf.loadbalancers.asScala.toList)
      ns <- toNamespaces(mf.namespaces.asScala.toList, us, ps, lb)
      ts  = toDeploymentTarget(mf.datacenters)
      no <- parseNotifications(mf.notifications).disjunction
    } yield Manifest(us, ps, lb, ns, ts, no)
  }
}

/* these classes must be top-level in the package for snake yaml to find them */
class ManifestYaml {
  @BeanProperty var units: JList[UnitYaml] = _
  @BeanProperty var plans: JList[PlanYaml] = new java.util.ArrayList
  @BeanProperty var datacenters: DatacenterTargetYaml = _
  @BeanProperty var namespaces: JList[NamespaceYaml] = _
  @BeanProperty var loadbalancers: JList[LoadbalancerYaml] = new java.util.ArrayList
  @BeanProperty var notifications: NotificationYaml = new NotificationYaml
}

/////// UNITS

/**
 * Ok this is a bit janky: 'required' fields should have their value
 * initially set to '_' whilst fields that are "optional" should have
 * their value set to a sane default, such as an empty list in the case
 * of lists.
 */
class UnitYaml {
  @BeanProperty var name: String = _
  @BeanProperty var description: String = _
  @BeanProperty var ports: JList[String] = new java.util.ArrayList
  @BeanProperty var dependencies: JList[DependencyYaml] = new java.util.ArrayList
  @BeanProperty var resources: JList[ResourceYaml] = new java.util.ArrayList
  @BeanProperty var meta: JList[String] = new java.util.ArrayList
  @BeanProperty var alerting: AlertingYaml = new AlertingYaml
  @BeanProperty var workflow: String = "magnetar"
  @BeanProperty var schedule: String = _
  @BeanProperty var expiration_policy: String = _
}

class LoadbalancerYaml {
  @BeanProperty var name: String = _
  @BeanProperty var routes: JList[RouteYaml] = new java.util.ArrayList
}

class PlanYaml {
  @BeanProperty var name: String = _
  @BeanProperty var cpu: Double = -1
  @BeanProperty var memory: Double = -1
  @BeanProperty var retries: Int = -1
  @BeanProperty var constraints: JList[ConstraintYaml] = new java.util.ArrayList
  @BeanProperty var environment: JList[String] = new java.util.ArrayList
  @BeanProperty var alert_opt_outs: JList[String] = new java.util.ArrayList
  @BeanProperty var instances: InstancesYaml = new InstancesYaml
  @BeanProperty var volumes: JList[VolumeYaml] = new java.util.ArrayList
  @BeanProperty var health_checks: JList[HealthCheckYaml] = new java.util.ArrayList
  @BeanProperty var resources: JList[PlanResourceYaml] = new java.util.ArrayList
  @BeanProperty var schedule: String = _
  @BeanProperty var expiration_policy: String = _
  @BeanProperty var traffic_shift: TrafficShiftYaml = _
}

class TrafficShiftYaml {
  @BeanProperty var policy: String = _
  @BeanProperty var duration: String = _
}

class HealthCheckYaml {
  @BeanProperty var name: String = _
  @BeanProperty var port_reference: String = _
  @BeanProperty var protocol: String = _
  @BeanProperty var path: String = _
  @BeanProperty var interval: String = "10 seconds"
  @BeanProperty var timeout: String = "2 seconds"
}

class PlanResourceYaml {
  @BeanProperty var ref: String = _
  @BeanProperty var uri: String = _
}

/* here be dragons, be **EXTREAMLY** careful with this, fair reader */
class RouteYaml {
  @BeanProperty var name: String = ""
  @BeanProperty var expose: String = _
  @BeanProperty var destination: String = _
}

class VolumeYaml {
  @BeanProperty var mount: String = _
  @BeanProperty var source: String = _
  @BeanProperty var mode: String = _
}

class DependencyYaml {
  @BeanProperty var ref: String = _
}

class ResourceYaml {
  @BeanProperty var name: String = _
  @BeanProperty var description: String = _
}
/////// DATACENTERS

class DatacenterTargetYaml {
  @BeanProperty var only: JList[String] = new java.util.ArrayList
  @BeanProperty var except: JList[String] = new java.util.ArrayList
}

/////// NAMESPACES

class NamespaceYaml {
  @BeanProperty var name: String = _
  @BeanProperty var units: JList[NamespaceUnitYaml] = _
  @BeanProperty var loadbalancers: JList[NamespaceLoadbalancerYaml] = new java.util.ArrayList
}

class NamespaceUnitYaml {
  @BeanProperty var ref: String = _
  @BeanProperty var plans: JList[String] = new java.util.ArrayList
}

class NamespaceLoadbalancerYaml {
  @BeanProperty var ref: String = _
  @BeanProperty var plan: String = _
}

class InstancesYaml {
  @BeanProperty var desired: Int = 1
}

class ConstraintYaml {
  @BeanProperty var unique: JList[String] = new java.util.ArrayList
  @BeanProperty var cluster: JList[ClusterYaml] = new java.util.ArrayList
  @BeanProperty var groupBy: JList[GroupByYaml] = new java.util.ArrayList
  @BeanProperty var like: JList[LikeYaml] = new java.util.ArrayList
  @BeanProperty var unlike: JList[LikeYaml] = new java.util.ArrayList
}

class ClusterYaml {
  @BeanProperty var fieldName: String = _
  @BeanProperty var param: String = _
}

class GroupByYaml {
  @BeanProperty var fieldName: String = _
  @BeanProperty var param: Int = -1
}

class LikeYaml {
  @BeanProperty var fieldName: String = _
  @BeanProperty var param: String = _
}

class AlertingYaml {
  @BeanProperty var prometheus: PrometheusConfigYaml = new PrometheusConfigYaml
}

class PrometheusConfigYaml {
  @BeanProperty var alerts: JList[PrometheusAlertYaml] = new java.util.ArrayList
  @BeanProperty var rules: JList[PrometheusRuleYaml] = new java.util.ArrayList
}

class PrometheusAlertYaml {
  @BeanProperty var alert: String = _
  @BeanProperty var expression: String = _
}

class PrometheusRuleYaml {
  @BeanProperty var rule: String = _
  @BeanProperty var expression: String = _
}

class NotificationYaml {
  @BeanProperty var slack: NotificationSlackYaml = new NotificationSlackYaml
  @BeanProperty var email: NotificationEmailYaml = new NotificationEmailYaml
}

class NotificationSlackYaml {
  @BeanProperty var channels: JList[String] = new java.util.ArrayList
}

class NotificationEmailYaml {
  @BeanProperty var recipients: JList[String] = new java.util.ArrayList
}

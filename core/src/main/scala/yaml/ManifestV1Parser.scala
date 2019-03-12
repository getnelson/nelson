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

import nelson.Manifest._
import nelson.YamlError._
import nelson.YamlParser.fromYaml
import nelson.cleanup.{ExpirationPolicy}
import nelson.notifications._
import nelson.blueprint.Blueprint

import cats.Apply
import cats.data.{NonEmptyList, State, Validated, ValidatedNel}
import cats.implicits._

import java.net.URI
import java.nio.file.Paths
import java.util.{ArrayList => JList}

import scala.beans.BeanProperty
import scala.collection.JavaConverters._
import scala.concurrent.duration._

object ManifestV1Parser {
  type YamlValidation[A] = ValidatedNel[YamlError, A]

  def parseDependency(dep: DependencyYaml): YamlValidation[(String,FeatureVersion)] = {
    val str = dep.ref
    val parts = str.split('@')

    if(parts.size == 2) {
      FeatureVersion.fromString(parts(1)).toValidNel(InvalidFeatureVersion(str, parts(1))) map {v =>
        val n = parts(0)
        n -> v
      }
    } else {
      Validated.invalidNel(InvalidServiceName(str))
    }
  }

  def parseResource(r: ResourceYaml): YamlValidation[Resource] = {
    (parseAlphaNumHyphen(r.name, "unit.resources.name"), Validated.valid(Option(r.description))).mapN((a,b) => Manifest.Resource(a, b))
  }

  def validateMeta(s: String): YamlValidation[String] = {

    def validateLength =
      if (s.length <= 14) Validated.validNel(s)
      else Validated.invalidNel(invalidMetaLength(s))

    (parseAlphaNumHyphen(s, "unit.meta"), validateLength).mapN((a,_) => a)
  }

  private val alphaNumHyphen = """[a-zA-Z0-9-]*""".r
  def parseAlphaNumHyphen(str: String, fieldName: String): YamlValidation[String] =
    if (alphaNumHyphen.pattern.matcher(str).matches)
      Validated.validNel(str)
    else
      Validated.invalidNel(invalidAlphaNumHyphen(str,fieldName))

  val noDefaultPortError: YamlError =
    invalidPortSpecification(s"a ${Port.defaultRef} port must be specified")

  val multipleDefaultPortError: YamlError =
    invalidPortSpecification(s"multiple ${Port.defaultRef} ports were defined")

  def resolveWorkflow(name: String) =
    Workflow.fromString(name)
      .toValidNel(YamlError.invalidWorkflow(name))

  def resolvePolicy(name: String): YamlValidation[ExpirationPolicy] =
    ExpirationPolicy.fromString(name)
      .toValidNel(YamlError.unknownExpirationPolicyRef(name))

  def parseURI(raw: String): YamlValidation[URI] =
    Either.catchNonFatal(new URI(raw))
      .leftMap(e => YamlError.invalidURI(raw)).toValidatedNel

  def parseSchedule(str: String): YamlValidation[Schedule] =
    Schedule.parse(str).leftMap(invalidSchedule).toValidatedNel

  def toPlanResource(raw: PlanResourceYaml): YamlValidation[(String,URI)] =
    (Option(raw.ref).toValidNel(missingProperty("plan.resources.ref")), parseURI(raw.uri)).mapN((a,b) => (a,b))

  def validatePlanConstraints[A : Numeric](n: A, e: A => YamlError)(f: A => Boolean): YamlValidation[A] =
    if (f(n)) Validated.validNel(n)
    else Validated.invalidNel(e(n))

  def validateCPU(i:Double): YamlValidation[Double] =
    validatePlanConstraints(i, invalidCPU)(i => i > 0 && i < 100)

  def validateCPURequest(request: Double, limit: Option[Double]): YamlValidation[ResourceSpec] = limit match {
    case None => Validated.invalidNel(missingCPULimit)
    case Some(limit) =>
      ResourceSpec.bounded(request, limit) match {
        case None       => Validated.invalidNel(invalidCPUBound(request, limit))
        case Some(spec) => Validated.valid(spec)
      }
  }

  def validateMemory(i:Double): YamlValidation[Double] =
    validatePlanConstraints(i, invalidMemory)(i => i > 0 && i < 50000)

  def validateMemoryRequest(request: Double, limit: Option[Double]): YamlValidation[ResourceSpec] = limit match {
    case None => Validated.invalidNel(missingMemoryLimit)
    case Some(limit) =>
      ResourceSpec.bounded(request, limit) match {
        case None       => Validated.invalidNel(invalidMemoryBound(request, limit))
        case Some(spec) => Validated.valid(spec)
      }
  }

  def validateInstances(i:Int): YamlValidation[Int] =
    validatePlanConstraints(i, invalidInstances)(i => i > 0 && i < 500)

  def validateVolumes(volume: VolumeYaml): YamlValidation[Volume] = {
    val name = Option(volume.name).toValidNel(missingVolumeName)

    val mountPath =
      Validated.catchNonFatal(Paths.get(volume.mountPath))
        .leftMap(ex => invalidMountPath(volume.mountPath, ex.getMessage))
        .toValidatedNel

    val size =
      Option(volume.size)
        .toValidNel(missingVolumeSize)
        .andThen(size => if (size > 0) size.valid else invalidVolumeSize(size).invalidNel)

    (name, mountPath, size).mapN { case (n, mp, s) => Volume(n, mp, s) }
  }

  def parseDuration(d: String): Either[YamlError, FiniteDuration] =
    Either.catchNonFatal {
      val dur = Duration(d)
      FiniteDuration(dur.toMillis, MILLISECONDS)
    }.leftMap(e => YamlError.invalidDuration(d, e.getMessage))

  def validateTrafficShiftDuration(dur: String): YamlValidation[FiniteDuration] =
    parseDuration(dur).flatMap(d =>
      if (d <= 1.minutes || d >= 5.days) Left(YamlError.invalidTrafficShiftDuration(dur))
      else Right(d)
    ).toValidatedNel

  def validateTrafficShift(raw: TrafficShiftYaml): YamlValidation[TrafficShift] =
    (TrafficShiftPolicy.fromString(raw.policy).toValidNel(YamlError.invalidTrafficShiftPolicy(raw.policy)), 
      validateTrafficShiftDuration(raw.duration)).mapN(TrafficShift)

  def toPlan(raw: PlanYaml): YamlValidation[Plan] = {
    val validatedCPU =
      Option(raw.cpu).filter(_ > 0).traverse(validateCPU).andThen { cpuLimit =>
        Option(raw.cpu_request).filter(_ > 0).traverse(validateCPURequest(_, cpuLimit)).map { cpuBounds =>
          cpuBounds.orElse(cpuLimit.flatMap(ResourceSpec.limitOnly)).getOrElse(ResourceSpec.unspecified)
        }
      }

    val validatedMemory =
      Option(raw.memory).filter(_ > 0).traverse(validateMemory).andThen { memoryLimit =>
        Option(raw.memory_request).filter(_ > 0).traverse(validateMemoryRequest(_, memoryLimit)).map { memoryBounds =>
          memoryBounds.orElse(memoryLimit.flatMap(ResourceSpec.limitOnly)).getOrElse(ResourceSpec.unspecified)
        }
      }

    // at this point, we do not have access to the database so we can only
    // check that the specified blueprint is syntactically valid. The manifest
    // validator will hydrate the reference into a Blueprint proper.
    val validateBlueprint: YamlValidation[Option[BlueprintRef]] = Option(raw.workflow).flatMap(wf => Option(wf.blueprint)) match {
      // workflow is present, but no blueprint provided
      // this is fine because we expect there to be default blueprints
      case None => Validated.validNel(None)
      case Some(blueprint) => Blueprint.parseNamedRevision(blueprint) match {
        // workflow and blueprint both present, but could not parse blueprint revision
        case Left(error) => Validated.invalidNel(YamlError.invalidBlueprintReference(blueprint, error.getMessage))
        // workflow and blueprint both present and looking good
        case Right(revision) => Validated.validNel(Some(revision))
      }
    }

    Apply[YamlValidation].map16(
      parseAlphaNumHyphen(raw.name, "plan.name"),
      validatedCPU,
      validatedMemory,
      Option(raw.instances).flatMap(x => Option(x.desired).filter(_ > 0)).traverse(d => validateInstances(d)),
      Validated.valid(Option(raw.retries).filter(_ > 0)),
      Validated.valid(raw.constraints.asScala.toList.flatMap(toConstraint)),
      Validated.valid(raw.alert_opt_outs.asScala.toList.map(AlertOptOut)), // opt out -> unit resolution is validated later
      Validated.valid(raw.environment.asScala.toList.flatMap(toEnvironmentVariable)),
      raw.health_checks.asScala.toList.traverse(toHealthCheck),
      raw.resources.asScala.toList.traverse(toPlanResource).map(_.toMap),
      Option(raw.schedule).traverse(s => parseSchedule(s)),
      Option(raw.expiration_policy).traverse(resolvePolicy),
      Option(raw.traffic_shift).traverse(validateTrafficShift),
      raw.volumes.asScala.toList.traverse(validateVolumes),
      Option(raw.workflow).map(x => resolveWorkflow(x.kind)).getOrElse(Validated.valid(nelson.Magnetar)),
      validateBlueprint
    ) {
      case (a,b,c,d,e,f,g,h,i,j,k,l,m,n,o,p) =>
        Plan(a, Environment(b,c,d,e,f,g,h,i,j,k,l,m,n,o,p.map(Left(_))))
    }
  }

  def toHealthCheck(raw: HealthCheckYaml): YamlValidation[HealthCheck] = {
    def parseProtocol(s: String): YamlValidation[String] =
      if (s == "http" || s == "https" || s == "tcp")
        Validated.validNel(s)
      else
        Validated.invalidNel(invalidProtocol(s))

    def validateDuration(d: String): YamlValidation[FiniteDuration] =
      parseDuration(d).toValidatedNel

    (parseAlphaNumHyphen(raw.name, "health_check.name"),
     Option(raw.port_reference).toValidNel(missingProperty("health_check.port_reference")),
     parseProtocol(raw.protocol),
     Validated.valid(Option(raw.path)),
     validateDuration(raw.interval),
     validateDuration(raw.timeout)
   ).mapN((a,b,c,d,e,f) => HealthCheck(a,b,c,d,e,f))
  }

  def toUnit(raw: UnitYaml): YamlValidation[UnitDef] = {
    def mkPort(value: String): YamlValidation[Port] =
      PortParser.parse(value)
        .leftMap(e => YamlError.invalidPortSpecification(e.getMessage))
        .toValidatedNel

    def toPorts(l: List[Port]): Either[YamlError, Ports] = {
      l.foldLeftM[Either[YamlError, ?], (Option[Port], List[Port])]((None, Nil)){ case ((defaultMaybe, rest), port) =>
        if (port.isDefault) {
          if (defaultMaybe.isDefined) Left(multipleDefaultPortError)
          else Right((Some(port), rest))
        } else Right((defaultMaybe, port :: rest))
      } flatMap { case (defaultMaybe, rest) =>
          defaultMaybe.toRight(noDefaultPortError).map(Ports(_, rest))
      }
    }

    def mkPorts(portDeclarations: List[String]): YamlValidation[Ports] =
      (for {
        portDeclarations <- Option(portDeclarations).toRight(NonEmptyList.of(missingProperty("unit.ports")))
        ports <- portDeclarations.traverse(mkPort).toEither
        ports <- toPorts(ports).leftMap(NonEmptyList.of(_))
      } yield ports).toValidated

    def validateJList[A, B](field: String, raw: JList[A], f: A => YamlValidation[B]): YamlValidation[List[B]] =
      Option(raw).fold[YamlValidation[List[B]]](Validated.invalidNel(emptyList(field)))(_.asScala.toList.traverse(f))

    Apply[YamlValidation].map8(
      Option(raw.name).toValidNel(missingProperty("unit.name")),
      Option(raw.description).toValidNel(missingProperty("unit.description")),
      validateJList("unit.dependencies", raw.dependencies, parseDependency _).map(_.toMap),
      validateJList("unit.resources", raw.resources, parseResource _).map(_.toSet),
      parseAlerting(raw.name, raw.alerting),
      Option(raw.ports).filter(_.size > 0).traverse(p => mkPorts(p.asScala.toList)),
      Validated.valid(None),
      validateJList("unit.meta", raw.meta, validateMeta _).map(_.toSet)
    )(UnitDef.apply)
  }

  def toDeploymentTarget(dcs: DatacenterTargetYaml): DeploymentTarget = {
    val only = Option(dcs).toList.flatMap(_.only.asScala.toList)
    val except = Option(dcs).toList.flatMap(_.except.asScala.toList)
    (only, except) match {
      case (o,Nil) if o.nonEmpty => DeploymentTarget.Only(o)
      case (Nil,e) if e.nonEmpty => DeploymentTarget.Except(e)
      case _                     => DeploymentTarget.Except(Nil) // yolo
    }
  }

  def toLoadbalancer(raw: LoadbalancerYaml): YamlValidation[Loadbalancer] = {

     def fromRoute(r: RouteYaml): ValidatedNel[YamlError, Route] = {
       val result = {
         val dest = BackendDestinationParser.parse(r.destination).leftMap(e => YamlError.loadError(e.getMessage))
         val port = PortParser.parse(r.expose).leftMap(e => YamlError.loadError(e.getMessage))
         (dest, port).mapN((dest, port) => Route(port, dest))
       }
       result.toValidatedNel
     }

    def fromRoutes(r: Vector[RouteYaml]): ValidatedNel[YamlError, Vector[Route]] =
       r.traverse(fromRoute(_))

    (parseAlphaNumHyphen(raw.name, "loadbalancer.name"),
     fromRoutes(raw.routes.asScala.toVector),
     Validated.valid(None)).mapN(Loadbalancer.apply)
  }

  /**
   * TIM: need to do something better here with errors.
   */
  def toEnvironmentVariable(s: String): Option[EnvironmentVariable] =
    Either.catchNonFatal {
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
    (slack, email).mapN(NotificationSubscriptions.apply)
  }

  private[this] val validEmail = """(?i)\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,4}\b""".r
  def parseEmailNotifications(raw: NotificationEmailYaml): YamlValidation[List[String]] = {
    def parseEmail(str: String): YamlValidation[String] =
      if(validEmail.pattern.matcher(str).matches) Validated.valid(str)
      else Validated.invalidNel(invalidEmail(str))

    raw.recipients.asScala.toList.traverse(parseEmail)
  }

  def parseSlackNotifications(raw: NotificationSlackYaml): YamlValidation[List[String]] =
    raw.channels.asScala.toList.validNel

  def parseAlerting(unitName: UnitName, rawAlerting: AlertingYaml): YamlValidation[Alerting] =
    parsePrometheusAlerting(unitName, rawAlerting.prometheus).map(Alerting.apply)

  def parsePrometheusAlerting(unitName: UnitName, rawPrometheus: PrometheusConfigYaml): YamlValidation[PrometheusConfig] = {
    import cats.data.Nested
    (
      rawPrometheus.alerts.asScala.toList.traverse(a => Nested(parsePrometheusAlert(a))).value.run(Set.empty).value._2,
      rawPrometheus.rules.asScala.toList.traverse(r => Nested(parsePrometheusRule(r))).value.run(Set.empty).value._2
    ).mapN(PrometheusConfig.apply)
    // (
    //   rawPrometheus.alerts.asScala.toList.traverse(parsePrometheusAlert).run(Set.empty).value._2,
    //   rawPrometheus.rules.asScala.toList.traverse(parsePrometheusRule).run(Set.empty).value._2
    // ).mapN(PrometheusConfig.apply)
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
      _    <- State.set(seen + name)
    } yield if (seen.contains(name))
      YamlError.duplicateAlertName(name).invalidNel
    else
      PrometheusAlert(name, rawAlert.expression).validNel

  def parsePrometheusRule(rawRule: PrometheusRuleYaml): State[Set[String], YamlValidation[PrometheusRule]] =
    for {
      seen <- State.get[Set[String]]
      name =  rawRule.rule.toSnakeCase
      _    <- State.set(seen + name)
    } yield if (seen.contains(name))
      YamlError.duplicateRuleName(name).invalidNel
    else
      PrometheusRule(name, rawRule.expression).validNel

  /**
   *
   */
  def parse(input: String): Either[NonEmptyList[NelsonError], Manifest] = {

    def toUnits(list: List[UnitYaml]): Either[NonEmptyList[NelsonError], List[UnitDef]] =
      list.traverse(toUnit).toEither

    def toPlans(list: List[PlanYaml]): Either[NonEmptyList[NelsonError], List[Plan]] =
      list.traverse(toPlan).toEither

    def toLoadbalancers(list: List[LoadbalancerYaml]): Either[NonEmptyList[NelsonError], List[Loadbalancer]] =
      list.traverse(toLoadbalancer).toEither

    def toNamespaces(list: List[NamespaceYaml], us: List[UnitDef], ps: List[Plan], lbs: List[Loadbalancer]): Either[NonEmptyList[NelsonError], List[Namespace]] = {
      def resolvePlan(name: String) =
        ps.find(_.name === name)
          .toValidNel(YamlError.unknownPlanRef(name))

      def validateOptOut(unit: Manifest.UnitDef)(out: AlertOptOut) =
        if (unit.alerting.prometheus.alerts.exists(_.alert === out.ref)) out.validNel
        else YamlError.unknownAlertOptOutRef(unit, out.ref).invalidNel

      def parseNamespaceName(s: String) =
         NamespaceName.fromString(s)
           .leftMap(e => YamlError.invalidNamespace(s, e.getMessage))
           .toValidatedNel

      list.traverse { n =>
        val units: YamlValidation[List[(String, Set[String])]] = n.units.asScala.toList.traverse { ns =>
          val resolvedUnit = us.find(_.name === ns.ref).toValidNel(YamlError.unknownUnitRef(ns.ref))

          resolvedUnit.andThen { ru =>
            ns.plans.asScala.toList.traverse(resolvePlan).andThen { rp =>
              rp.traverse(_.environment.alertOptOuts.traverse(validateOptOut(ru))).map { _ =>
                (ru.name, rp.map(_.name).toSet)
              }
            }
          }
        }

        val loadbalancers: YamlValidation[List[(String, Option[String])]] = n.loadbalancers.asScala.toList.traverse { lb =>
          val resolveLb =
            lbs.find(_.name == lb.ref).map(_.name)
              .toValidNel(YamlError.unknownLoadbalancerRef(lb.ref))

          resolveLb.andThen { rlb =>
            Option(lb.plan).traverse(resolvePlan).map { rp =>
              (rlb, rp.map(_.name))
            }
          }
        }

        val name: YamlValidation[NamespaceName] = parseNamespaceName(n.name)

        (units, loadbalancers, name).mapN((u,l,n) => Namespace(n, u.toSet, l.toSet)).toEither
      }
    }

    for {
      mf <- fromYaml[ManifestYaml](input).leftMap(NonEmptyList.of(_))
      us <- toUnits(mf.units.asScala.toList)
      ps <- toPlans(mf.plans.asScala.toList)
      lb <- toLoadbalancers(mf.loadbalancers.asScala.toList)
      ns <- toNamespaces(mf.namespaces.asScala.toList, us, ps, lb)
      ts  = toDeploymentTarget(mf.datacenters)
      no <- parseNotifications(mf.notifications).toEither
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
}

class LoadbalancerYaml {
  @BeanProperty var name: String = _
  @BeanProperty var routes: JList[RouteYaml] = new java.util.ArrayList
}

class PlanYaml {
  @BeanProperty var name: String = _
  @BeanProperty var cpu: Double = -1
  @BeanProperty var cpu_request: Double = -1
  @BeanProperty var memory: Double = -1
  @BeanProperty var memory_request: Double = -1
  @BeanProperty var retries: Int = -1
  @BeanProperty var constraints: JList[ConstraintYaml] = new java.util.ArrayList
  @BeanProperty var environment: JList[String] = new java.util.ArrayList
  @BeanProperty var alert_opt_outs: JList[String] = new java.util.ArrayList
  @BeanProperty var instances: InstancesYaml = new InstancesYaml
  @BeanProperty var health_checks: JList[HealthCheckYaml] = new java.util.ArrayList
  @BeanProperty var resources: JList[PlanResourceYaml] = new java.util.ArrayList
  @BeanProperty var schedule: String = _
  @BeanProperty var expiration_policy: String = _
  @BeanProperty var traffic_shift: TrafficShiftYaml = _
  @BeanProperty var volumes: JList[VolumeYaml] = new java.util.ArrayList
  @BeanProperty var workflow: WorkflowYaml = _
}

class WorkflowYaml {
  @BeanProperty var kind: String = Pulsar.name
  @BeanProperty var blueprint: String = _
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

class VolumeYaml {
  @BeanProperty var name: String = _
  @BeanProperty var mountPath: String = _
  @BeanProperty var size: Int = _
}

/* here be dragons, be **EXTREAMLY** careful with this, fair reader */
class RouteYaml {
  @BeanProperty var name: String = ""
  @BeanProperty var expose: String = _
  @BeanProperty var destination: String = _
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

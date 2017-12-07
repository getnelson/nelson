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

import Manifest.{UnitDef,Plan,Resource,Namespace,Loadbalancer,HealthCheck,Route}
import storage.StoreOp
import Domain.StackName
import scalaz._, Scalaz._
import scalaz.concurrent.Task


object ManifestValidator {

  type Valid[A] = ValidationNel[NelsonError, A]

  /**
   * A (potentially) deployable unit
   *
   * @param kind ex: `howdy-http`
   * @param name ex: `howdy-http-0.4`
   */
  final case class NelsonUnit(
    kind: String,
    name: String)

  /**
   * A nelson manifest that we want to validate
   *
   * @param units the units that are to be verified
   * @param config the complete string content of the manifest file
   */
  final case class ManifestValidation(
    units: List[NelsonUnit],
    config: String)


  def validateUnitKind(unit: NelsonUnit, manifest: Manifest): Valid[Unit] = {
    // "name" meaning something in manifest.yml than in .nelson.yml is kind of gross and confusing :(
    if (manifest.units.exists(_.name === unit.kind)) ().successNel
    else ManifestUnitKindMismatch(unit.kind, manifest.units.map(_.name)).failureNel
  }

  def validate(str: String, units: List[NelsonUnit]): Nelson.NelsonK[Valid[Manifest]] =
    Kleisli { cfg =>
      parseManifestAndValidate(str, cfg) map {
        case Success(m) =>
          units.traverse(u => validateUnitKind(u,m)).map(_ => m)
        case f@Failure(_) =>
          f
      }
    }

  /*
   * Validate manifest when commiting a unit to a namespace.
   * Validates that declared namespace exists in manifest
   * Validates that unit is declared in namespace
   */
  def validateOnCommit(un: UnitName, ns: NamespaceName, m: Manifest): Valid[Manifest] = {
    import scalaz.std.anyVal._
    val namespace =
      if (!m.namespaces.exists(_.name == ns))
        DeploymentCommitFailed(
          s"namespace ${ns.asString} is not declared in namespaces stanza of the manifest").failureNel
    else ().successNel

    val unit =
      if (!m.namespaces.find(_.name == ns).exists(_.units.exists(_._1 == un)))
        DeploymentCommitFailed(
          s"unit $un is not declared for namespace ${ns.asString}").failureNel
    else ().successNel

    (namespace +++ unit).map(_ => m)
  }

  def validateLoadbalancer(lb: Loadbalancer,
    units: List[UnitDef],
    whiteList: Option[ProxyPortWhitelist])(stg: StoreOp ~> Task): Valid[Unit] = {

    // aws allows elb names to be at most 32 charasters. Nelson generates a name for
    // lbs that include 8 characters for a hash, 4 characters dashes and at least 1
    // character for version, for a total of 13. Giving the version some wiggle room for
    // a total of 15, so the max length of the name is 32 - 15
    def validateNameLength(lb: Manifest.Loadbalancer): Valid[Unit] =
      if (lb.name.length <= 17) ().successNel
      else InvalidLoadbalancerNameLength(lb.name).failureNel

    def validateAllowedPorts(ps: Vector[Manifest.Port]): Valid[Unit] =
      whiteList.cata(
        some = allowed =>
          ps.traverse_(p =>
            if (allowed.ports.contains(p.port)) ().successNel
            else InvalidLoadbalancerPort(p.port, allowed.ports).failureNel
          ),
        none = ().successNel
      )

    def unitPortExists(u: UnitDef, r: Route): Boolean =
      u.name == r.destination.name &&
      u.ports.exists(_.nel.toList.exists(_.ref == r.destination.portReference))

    def validateExists(r: Route): Valid[Unit] =
      if (units.exists(u => unitPortExists(u,r))) ().successNel
      else UnknownBackendDestination(r, units.map(_.name)).failureNel

    // loadbalancers can define multiple routes but they must all go
    // to the same backened service
    def validateOneUnit(lb: Manifest.Loadbalancer): Valid[Unit] =
      if (lb.routes.map(_.destination.name).distinct.length == 1) ().successNel
      else InvalidRouteDefinition(lb.name).failureNel

    validateNameLength(lb) +++
    validateAllowedPorts(lb.routes.map(_.port)) +++
    lb.routes.traverse_(validateExists) +++
    validateOneUnit(lb)
  }

  def validateHealthCheck(check: HealthCheck, unit: UnitDef): Valid[Unit] = {
    val resolvePort: ValidationNel[NelsonError, Unit] =
      if (unit.ports.exists(_.nel.toList.exists(_.ref == check.portRef))) ().successNel
      else UnknownPortRef(check.portRef, unit.name).failureNel

    val validatePathIfHttp: ValidationNel[NelsonError, Unit] =
      if ((check.protocol == "http" || check.protocol == "https") && check.path.isEmpty)
        MissingHealthCheckPath(check.protocol).failureNel
      else ().successNel

    resolvePort +++ validatePathIfHttp
  }

  def validateAlerts(unit: UnitDef, p: Plan): Task[Valid[Unit]] = {
    import alerts.Promtool

    // These values don't exist yet, but we need them for overhaul
    val DummyVersion = Version(0, 0, 1)
    val DummyNamespaceName = NamespaceName("validation")
    val DummyHash = "0000000"

    def dummyStackName(unit: UnitDef) = StackName(unit.name, DummyVersion, DummyHash)

    (for {
      // Provide a dummy namespace and version
      a <- EitherT(alerts.rewriteRules(unit, dummyStackName(unit), p.name, DummyNamespaceName, p.environment.alertOptOuts))
      b <- EitherT(Promtool.validateRules(unit.name, a).map {
        case Promtool.Valid =>
          ().right
        case i: Promtool.Invalid =>
          (InvalidPrometheusRules(i.msg): NelsonError).left
      })
    } yield b).run.map(_.validationNel)
  }

  def validateResource(r: Resource, plan: Plan): Valid[Unit] =
    plan.environment.resources.get(r.name).cata(
      none = MissingResourceReference(r.name, plan).failureNel,
      some = x => ().successNel)

  def validatePeriodic(unit: UnitDef, plan: Plan): Valid[Unit] =
    if (Manifest.isPeriodic(unit,plan))
      plan.environment.trafficShift.cata(
        some = _ => PeriodicUnitWithTrafficShift(unit.name).failureNel,
        none = ().successNel)
    else
      ().successNel

  def validateUnitNameLength(unit: UnitDef): Valid[Unit] =
    if(unit.name.length() > 41)
      InvalidUnitNameLength(unit.name).failureNel
    else
      ().successNel

  def validateUnitNameChars(unit: UnitDef): Valid[Unit] = {
    val validName = "^[a-zA-Z0-9][a-zA-Z0-9-]{0,}[a-zA-Z0-9]$".r
    val string = unit.name
    if(!validName.pattern.matcher(string).matches)
      InvalidUnitNameChars(string).failureNel
    else
      ().successNel
    }

  def validateUnit(unit: UnitDef, plan: Plan): Task[Valid[Unit]] =
    for {
      a <- validateAlerts(unit, plan)
      b <- Task.now(plan.environment.healthChecks.traverse_(validateHealthCheck(_, unit)))
      c <- Task.now(unit.resources.toList.traverse_(r => validateResource(r, plan)))
      d <- Task.now(validatePeriodic(unit,plan))
      e <- Task.now(validateUnitNameLength(unit))
      f <- Task.now(validateUnitNameChars(unit))
    } yield (a +++ b +++ c +++ d +++ e +++ f).map(_ => ())

  def parseManifestAndValidate(str: String, cfg: NelsonConfig): Task[Valid[Manifest]] = {

    def validateUnits(m: Manifest, dcs: Seq[Domain]): Task[Valid[Unit]] = {
      val folder: (Domain,Namespace,Plan,UnitDef,List[Task[Valid[Unit]]]) => List[Task[Valid[Unit]]] =
        (dc,ns,p,u,res) =>
          validateUnit(u,p) :: res

      Manifest.foldUnits(m, dcs, folder, Nil)
        .sequence
        .map(l => Foldable[List].fold(l))
    }

    def validateReferencesDefaultNamespace(m: Manifest, defaultNamespace: NamespaceName): Task[Valid[Unit]] =
    Task.now(
      m.namespaces.find(x => x.name == cfg.defaultNamespace)
        .cata(
          none = MissingDefaultNamespaceReference(cfg.defaultNamespace).failureNel,
          some = n =>
            if (m.units.forall {u => n.units.exists(_._1 == u.name)})
              ().successNel
            else
              MissingIndividualDefaultNamespaceReference(cfg.defaultNamespace).failureNel
        )
    )


    def validateLoadbalancers(m: Manifest, dcs: Seq[Domain]): Task[Valid[Unit]] = {
      Task.delay {
        m.loadbalancers.foldLeft(Nil : List[Valid[Unit]])((res,lb) =>
          validateLoadbalancer(lb, m.units, cfg.proxyPortWhitelist)(cfg.storage) :: res)
          .sequence
          .map(l => Foldable[List].fold(l))
      }
    }

    def validate(m: Manifest): Task[Valid[Manifest]] = {
      val x: Task[Valid[Manifest]] = for {
        a <- Manifest.verifyDeployable(m, cfg.domains, cfg.storage)
        b <- validateUnits(m, cfg.domains)
        c <- validateLoadbalancers(m, cfg.domains)
        d <- validateReferencesDefaultNamespace(m, cfg.defaultNamespace)
      } yield (a +++ b +++ c +++ d).map(_ => m)

      // We cannot do this validation in parallel with the above.
      // We rely on the assumptions the above validations afford us.
      DisjunctionT(x.map(_.disjunction)).flatMap { m =>
        CycleDetection.validateNoCycles(m, cfg)
      }.run.map(x => x.validation.map(_ => m))
    }

    yaml.ManifestParser.parse(str).fold(
      e => Task.delay(e.failure),
      m => validate(m)
    )
  }

  object Json {
    import argonaut._, Argonaut._

    implicit val codecNelsonUnit: CodecJson[NelsonUnit] =
      CodecJson.casecodec2(NelsonUnit.apply, NelsonUnit.unapply)("kind", "name")

    implicit val decodeManifestValidation: DecodeJson[ManifestValidation] = DecodeJson(c =>
      ((c --\ "units").as[List[NelsonUnit]] |@|
        (c --\ "manifest").as[Base64].map(_.decoded)
      )(ManifestValidation.apply)
    )
  implicit val VersionDecode: DecodeJson[FeatureVersion] =
    DecodeJson.optionDecoder(_.string.flatMap { a =>
      FeatureVersion.fromString(a)
    }, "FeatureVersion")

  }
}

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

import nelson.Datacenter.StackName
import nelson.Manifest.{UnitDef,Plan,Resource,Namespace,Loadbalancer,HealthCheck,Route}
import nelson.storage.StoreOp

import cats.{~>, Foldable}
import cats.data.{EitherT, Kleisli, ValidatedNel}
import cats.data.Validated.{Valid, Invalid}
import cats.effect.IO
import cats.implicits._

object ManifestValidator {

  type Valid[A] = ValidatedNel[NelsonError, A]

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
    if (manifest.units.exists(_.name === unit.kind)) ().validNel
    else ManifestUnitKindMismatch(unit.kind, manifest.units.map(_.name)).invalidNel
  }

  def validate(str: String, units: List[NelsonUnit]): Nelson.NelsonK[Valid[Manifest]] =
    Kleisli { cfg =>
      parseManifestAndValidate(str, cfg) map {
        case Valid(m) =>
          units.traverse(u => validateUnitKind(u,m)).map(_ => m)
        case f@Invalid(_) =>
          f
      }
    }

  /*
   * Validate manifest when commiting a unit to a namespace.
   * Validates that declared namespace exists in manifest
   * Validates that unit is declared in namespace
   */
  def validateOnCommit(un: UnitName, ns: NamespaceName, m: Manifest): Valid[Manifest] = {
    val namespace =
      if (!m.namespaces.exists(_.name == ns))
        DeploymentCommitFailed(
          s"namespace ${ns.asString} is not declared in namespaces stanza of the manifest").invalidNel
    else ().validNel

    val unit =
      if (!m.namespaces.find(_.name == ns).exists(_.units.exists(_._1 == un)))
        DeploymentCommitFailed(
          s"unit $un is not declared for namespace ${ns.asString}").invalidNel
    else ().validNel

    (namespace combine unit).map(_ => m)
  }

  def validateLoadbalancer(lb: Loadbalancer,
    units: List[UnitDef],
    whiteList: Option[ProxyPortWhitelist])(stg: StoreOp ~> IO): Valid[Unit] = {

    // NOTE: aws allows elb names to be at most 32 charasters. Nelson generates a name for
    // lbs that include 8 characters for a hash, 4 characters dashes and at least 1
    // character for version, for a total of 13. Giving the version some wiggle room for
    // a total of 15, so the max length of the name is 32 - 15
    // For example: mypro--1-0-0--ssiididq
    def validateNameLength(lb: Manifest.Loadbalancer): Valid[Unit] = {
      val maximumLength = 12
      if (lb.name.length <= maximumLength) ().validNel
      else InvalidLoadbalancerNameLength(lb.name, maximumLength).invalidNel
    }

    def validateAllowedPorts(ps: Vector[Manifest.Port]): Valid[Unit] =
      whiteList.fold[Valid[Unit]](().validNel) { allowed =>
        ps.traverse_(p =>
          if (allowed.ports.contains(p.port)) ().validNel
          else InvalidLoadbalancerPort(p.port, allowed.ports).invalidNel
        )
      }

    def unitPortExists(u: UnitDef, r: Route): Boolean =
      u.name == r.destination.name &&
      u.ports.exists(_.nel.toList.exists(_.ref == r.destination.portReference))

    def validateExists(r: Route): Valid[Unit] =
      if (units.exists(u => unitPortExists(u,r))) ().validNel
      else UnknownBackendDestination(r, units.map(_.name)).invalidNel

    // loadbalancers can define multiple routes but they must all go
    // to the same backened service
    def validateOneUnit(lb: Manifest.Loadbalancer): Valid[Unit] =
      if (lb.routes.map(_.destination.name).distinct.length == 1) ().validNel
      else InvalidRouteDefinition(lb.name).invalidNel

    validateNameLength(lb) combine
    validateAllowedPorts(lb.routes.map(_.port)) combine
    lb.routes.traverse_(validateExists) combine
    validateOneUnit(lb)
  }

  def validateHealthCheck(check: HealthCheck, unit: UnitDef): Valid[Unit] = {
    val resolvePort: ValidatedNel[NelsonError, Unit] =
      if (unit.ports.exists(_.nel.toList.exists(_.ref == check.portRef))) ().validNel
      else UnknownPortRef(check.portRef, unit.name).invalidNel

    val validatePathIfHttp: ValidatedNel[NelsonError, Unit] =
      if ((check.protocol == "http" || check.protocol == "https") && check.path.isEmpty)
        MissingHealthCheckPath(check.protocol).invalidNel
      else ().validNel

    resolvePort combine validatePathIfHttp
  }

  def validateAlerts(unit: UnitDef, p: Plan): IO[Valid[Unit]] = {
    import alerts.Promtool

    // These values don't exist yet, but we need them for overhaul
    val DummyVersion = Version(0, 0, 1)
    val DummyNamespaceName = NamespaceName("validation")
    val DummyHash = "0000000"

    def dummyStackName(unit: UnitDef) = StackName(unit.name, DummyVersion, DummyHash)

    (for {
      // Provide a dummy namespace and version
      a <- EitherT(alerts.rewriteRules(unit, dummyStackName(unit), p.name, DummyNamespaceName, p.environment.alertOptOuts))
      b <- EitherT(Promtool.validateRules(unit.name, a).map[Either[NelsonError, Unit]] {
        case Promtool.Valid      => Right(())
        case i: Promtool.Invalid => Left(InvalidPrometheusRules(i.msg): NelsonError)
      })
    } yield b).value.map(_.toValidatedNel)
  }

  def validateResource(r: Resource, plan: Plan): Valid[Unit] =
    plan.environment.resources.get(r.name).fold[Valid[Unit]](MissingResourceReference(r.name, plan).invalidNel)(_ => ().validNel)

  def validatePeriodic(unit: UnitDef, plan: Plan): Valid[Unit] =
    if (Manifest.isPeriodic(unit,plan))
      plan.environment.trafficShift.fold[Valid[Unit]](().validNel)(_ => PeriodicUnitWithTrafficShift(unit.name).invalidNel)
    else
      ().validNel

  def validateUnitNameLength(unit: UnitDef): Valid[Unit] =
    if(unit.name.length() > 41)
      InvalidUnitNameLength(unit.name).invalidNel
    else
      ().validNel

  def validateUnitNameChars(unit: UnitDef): Valid[Unit] = {
    val validName = "^[a-zA-Z0-9][a-zA-Z0-9-]{0,}[a-zA-Z0-9]$".r
    val string = unit.name
    if(!validName.pattern.matcher(string).matches)
      InvalidUnitNameChars(string).invalidNel
    else
      ().validNel
    }

  def validateUnit(unit: UnitDef, plan: Plan): IO[Valid[Unit]] =
    for {
      a <- validateAlerts(unit, plan)
      b <- IO.pure(plan.environment.healthChecks.traverse_(validateHealthCheck(_, unit)))
      c <- IO.pure(unit.resources.toList.traverse_(r => validateResource(r, plan)))
      d <- IO.pure(validatePeriodic(unit,plan))
      e <- IO.pure(validateUnitNameLength(unit))
      f <- IO.pure(validateUnitNameChars(unit))
    } yield (a combine b combine c combine d combine e combine f)

  def parseManifestAndValidate(str: String, cfg: NelsonConfig): IO[Valid[Manifest]] = {

    def validateUnits(m: Manifest, dcs: Seq[Datacenter]): IO[Valid[Unit]] = {
      val folder: (Datacenter,Namespace,Plan,UnitDef,List[IO[Valid[Unit]]]) => List[IO[Valid[Unit]]] =
        (dc,ns,p,u,res) =>
          validateUnit(u,p) :: res

      Manifest.foldUnits(m, dcs, folder, Nil)
        .sequence
        .map(l => Foldable[List].fold(l))
    }

    def validateReferencesDefaultNamespace(m: Manifest, defaultNamespace: NamespaceName): IO[Valid[Unit]] =
      IO.pure(
        m.namespaces.find(x => x.name == cfg.defaultNamespace)
          .fold[Valid[Unit]](MissingDefaultNamespaceReference(cfg.defaultNamespace).invalidNel) { n =>
            if (m.units.forall {u => n.units.exists(_._1 == u.name)})
              ().validNel
            else
              MissingIndividualDefaultNamespaceReference(cfg.defaultNamespace).invalidNel
          }
      )

    def validateLoadbalancers(m: Manifest, dcs: Seq[Datacenter]): IO[Valid[Unit]] = {
      IO {
        m.loadbalancers.foldLeft(Nil: List[Valid[Unit]])((res,lb) =>
          validateLoadbalancer(lb, m.units, cfg.proxyPortWhitelist)(cfg.storage) :: res)
          .sequence
          .map(l => Foldable[List].fold(l))
      }
    }

    def validateBlueprints(m: Manifest): IO[(Valid[Unit], List[Plan])] = {
      val plans: IO[List[Valid[Plan]]] = m.plans.foldLeft(IO.pure(List.empty[Valid[Plan]])) { (res, plan) =>
        val result: IO[Valid[Plan]] = plan.environment.blueprint match {
          // supplied manifest held a manifest reference
          case Some(Left((ref,revision))) => {
            storage.StoreOp.findBlueprint(ref, revision)
              .foldMap(cfg.storage)
              .map { bp =>
                if (bp.nonEmpty) plan.copy(
                  environment = plan.environment.copy(
                    blueprint = bp.map(Right(_)))).validNel
                else UnknownBlueprintReference(ref, revision).invalidNel
              }
          }
          // supplied manifest already held a full blueprint
          case Some(Right(bp)) => IO.pure(plan.validNel)
          // supplied manfiest did not specify a blueprint
          // so it will recieve the default when the workflow
          // executes
          case None            => IO.pure(plan.validNel)
        }
        (result, res).mapN(_ :: _)
      }

      for {
        a <- plans
        b  = Foldable[List].fold(a.map(_.map(_ => ())))
        c  = a.traverse(_.toList).flatMap(identity)
      } yield (b, c)
    }

    def validate(m: Manifest): IO[Valid[Manifest]] = {
      val cumulative: IO[Valid[Manifest]] = for {
        a <- Manifest.verifyDeployable(m, cfg.datacenters, cfg.storage)
        b <- validateUnits(m, cfg.datacenters)
        c <- validateLoadbalancers(m, cfg.datacenters)
        d <- validateReferencesDefaultNamespace(m, cfg.defaultNamespace)
        e <- validateBlueprints(m)
        (f,g) = e
      } yield (a combine b combine c combine d combine f).map(_ => m.copy(plans = g))

      // We cannot do this validation in parallel with the above.
      // We rely on the assumptions the above validations afford us.
      (for {
        mn <- EitherT(cumulative.map(_.toEither))
        _  <- CycleDetection.validateNoCycles(mn, cfg)
      } yield mn).toValidated
    }

    yaml.ManifestParser.parse(str).fold(
      e => IO(e.invalid),
      m => validate(m)
    )
  }

  object Json {
    import argonaut._, Argonaut._
    import argonaut.DecodeResultCats._
    import cats.syntax.apply._

    implicit val codecNelsonUnit: CodecJson[NelsonUnit] =
      CodecJson.casecodec2(NelsonUnit.apply, NelsonUnit.unapply)("kind", "name")

    implicit val decodeManifestValidation: DecodeJson[ManifestValidation] = DecodeJson(c =>
      ((c --\ "units").as[List[NelsonUnit]],
        (c --\ "manifest").as[Base64].map(_.decoded)
      ).mapN(ManifestValidation.apply)
    )
  implicit val VersionDecode: DecodeJson[FeatureVersion] =
    DecodeJson.optionDecoder(_.string.flatMap { a =>
      FeatureVersion.fromString(a)
    }, "FeatureVersion")

  }
}

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

import nelson.Manifest.UnitDef
import cats.data.NonEmptyList
import cats.instances.string._
import cats.syntax.reducible._

abstract class NelsonError(msg: String) extends RuntimeException {
  override def getMessage: String = msg
}

final case class InvalidSlug(s: String)
  extends NelsonError(s"Invalid slug '$s'")

final case class InvalidRepoAccess(s: String)
  extends NelsonError(s"Invalid slug '$s'")

final case class RepoNotFound(slug: Slug)
  extends NelsonError(s"repo '${slug.toString}' not found")

final case class ExceededLimitRange(value: Int)
  extends NelsonError(s"the specified limit '$value' exceeded the permitted range nelson allows for querying in a single request.")

final case class UnexpectedMissingHook(slug: Slug)
  extends NelsonError(s"repo '${slug.toString}' was expected to have a hook")

final case class ProblematicRepoManifest(slug: Slug)
  extends NelsonError(s"repo '${slug.toString}' was expected to have a valid .nelson.yml")

final case class ProblematicDeployable(str: String, url: String)
    extends NelsonError(s"Error when attempting to parse deployable from repository. Please ensure that a valid deployable yaml/yml file is available at the specified URL, or attached to the associated Github release: ${url} -- $str")

final case class MissingReleaseAssets(r: Github.ReleaseEvent)
 extends NelsonError(s"fetching the release assets from github returned no results; this is likely a critical error. release event was: $r")

final case class MisconfiguredDatacenter(name: String, problem: String)
    extends NelsonError(s"The datacenter named $name is misconfigured: $problem")

final case class MissingDeployment(guid: String)
  extends NelsonError(s"deployment ${guid} could not be found; please check you have the right GUID")

final case class MissingReleaseForDeployment(guid: String)
  extends NelsonError(s"release could not be found for deployment guid ${guid}")

final case class UnknownDatacenter(name: String)
  extends NelsonError(s"a datacenter called '$name' is not known by nelson.")

final case class UnknownNamespace(dcName: String, nsName: String)
  extends NelsonError(s"there is no namespace named '$nsName' in datacenter '$dcName'")

case object NoTargetDatacenters
  extends NelsonError("there must be at least one datacenter defined before deployments can be conducted.")

final case class FailedDockerOperation(err: String)
  extends NelsonError(s"unexpected error executing docker operation: $err")

final case class FailedDockerExtraction(err: String)
  extends NelsonError(s"failed docker extraction because: $err")

final case class InvalidDockerImage(name: String)
  extends NelsonError(s"'$name' is not a valid docker container name")

final case class UnsatisfiedDeploymentRequirements(u: Manifest.UnitDef)
  extends NelsonError(s"the input unit did not satisfy the deployment requirements. unit was: $u")

final case class MultipleErrors(errors: NonEmptyList[NelsonError])
    extends NelsonError("Multiple errors: " + errors.reduceMap(_.getMessage))

final case class MultipleValidationErrors(errors: NonEmptyList[NelsonError])
  extends NelsonError("Validation errors: " + errors.reduceMap(e => s"${e.getMessage}. "))

final case class UnexpectedConsulResponse(resp: String)
    extends NelsonError(s"got an unexpected response from consul: $resp")

final case class MissingDependency(unit: String,
                             dc: String,
                             ns: String,
                             dependency: Datacenter.ServiceName)
  extends NelsonError(s"There is no deployment in datacetner $dc namespace $ns satisfying the dependency $dependency for unit $unit")

final case class DeprecatedDependency(unit: String,
                                dc: String,
                                ns: String,
                                dependency: Datacenter.ServiceName
                                )
  extends NelsonError(s"Dependency $dependency is deprecated in datacenter $dc namespace $ns for unit $unit")

final case class CyclicDependency(message: String)
  extends NelsonError(message)

final case class FailedWorkflow(name: String, reason: String = "")
  extends NelsonError(s"workflow '$name' failed to execute. $reason")

object UnsatisfiedDeploymentRequirements {
  import Manifest.Versioned

  def apply(u: Manifest.UnitDef @@ Versioned): UnsatisfiedDeploymentRequirements =
    UnsatisfiedDeploymentRequirements(Versioned.unwrap(u))
}

final case class UnparsableReleaseVersion(version: String)
  extends NelsonError(s"the supplied version '$version' was not of the form x.x.x")

final case class ManifestUnitKindMismatch(unitKind: String, unitNames: List[String]) extends NelsonError(
  s"""The unit '$unitKind' does not appear in the supplied list of units '${unitNames.mkString(", ")}'. Please ensure that you are specifying the units in the manifests, as they are being supplied on the command line.`""")

final case class InvalidLoadbalancerPort(port: Int, allowed: List[Int]) extends NelsonError(s"""$port is not supported for loadbalancing; supported ports are ${allowed.mkString(",")}""")

final case class InvalidLoadbalancer(rs: Vector[Manifest.Route]) extends NelsonError(s"""only the default port is allow for loadbalancing: ${rs.map(_.destination.portReference).toList.mkString(",")} have been requested""")

final case class InvalidLoadbalancerNameLength(name: String) extends NelsonError(s"""loadbalancer name ($name) must less that 17 characters""")

final case class InvalidRouteDefinition(name: String) extends
  NelsonError(s"""loadbalancer $name has invalid route definition, all routes must route to the same backend service""")

final case class UnknownBackendDestination(r: Manifest.Route, unames: List[String]) extends NelsonError(s"""backend
destination route (${r.asString}) does not reference a known unit name (${unames.mkString(", ")})""")

final case class LoadbalancerNotFound(guid: GUID)
  extends NelsonError(s"loadbalancer deployment '${guid}' not found")

final case class InvalidPrometheusRules(msg: String)
  extends NelsonError(s"Invalid Prometheus rules: $msg.")

final case class InvalidGrant(unitName: UnitName, grant: String)
  extends NelsonError(s"Invalid grant to unit '$unitName': '$grant'")

final case class FailedLoadbalancerDeploy(name: String, reason: String)
  extends NelsonError(s"Loadbalancer deployment ($name) failed because $reason")

final case class MissingDefaultNamespaceReference(ns: NamespaceName)
  extends NelsonError(s"Default namespace (${ns.asString}) is missing from manifest")

final case class MissingIndividualDefaultNamespaceReference(ns: NamespaceName)
  extends NelsonError(s"Default namespace (${ns.asString}) reference missing from one or more units")

final case class UnknownPortRef(ref: String, unit: UnitName)
  extends NelsonError(s"port reference $ref is not declared for unit $unit")

final case class MissingHealthCheckPath(protocol: String)
  extends NelsonError(s"$protocol protocol must define a health check path, none was defined")

final case class MissingResourceReference(ref: String, p: Manifest.Plan)
  extends NelsonError(s"resources reference $ref is missing from plan ${p.name}")

final case class DeploymentCommitFailed(reason: String)
  extends NelsonError(s"deployment commit failed because $reason")

final case class InvalidTrafficShiftReverse(reason: String)
  extends NelsonError(s"invlaid traffic shift reverse: $reason")

final case class PeriodicUnitWithTrafficShift(name: String)
  extends NelsonError(s"$name can not define both a schedule and traffic shift")

final case class InvalidNamespaceName(name: String)
  extends NelsonError(s"$name is an invalid namespace name")

final case class NamespaceCreateFailed(reason: String)
  extends NelsonError(s"namespace was not created because: $reason")

final case class ManualDeployFailed(reason: String)
  extends NelsonError(s"manual deploy failed because $reason")

final case class InvalidUnitNameLength(name: String)
  extends NelsonError(s"$name is too long.  Unit names must be less than 42 characters")

final case class InvalidUnitNameChars(name: String)
  extends NelsonError(s"$name contains invalid characters. Unit names can only include hyphens, A-Z, a-z, 0-9 where the unit name starts and ends with an alpha-numeric character.")

////////////////////////// YAML ERRORS ///////////////////////////////

abstract class YamlError(msg: String) extends NelsonError(msg)

object YamlError {
  def loadError(error: String): YamlError = LoadError(error)
  def missingProperty(field: String): YamlError = MissingProperty(field)
  def invalidUnitType(st: String): YamlError = InvalidUnitType(st)
  def invalidWorkflow(name: String): YamlError = InvalidWorkflow(name)
  def invalidServiceName(sn: String): YamlError = InvalidServiceName(sn)
  def invalidFeatureVersion(sn: String, v: String): YamlError = InvalidFeatureVersion(sn, v)
  def invalidPortSpecification(msg: String): YamlError = InvalidPortSpecification(msg)
  def unknownUnitRef(ref: String): YamlError = UnknownUnitRef(ref)
  def unknownLoadbalancerRef(ref: String): YamlError = UnknownLoadbalancerRef(ref)
  def unknownPlanRef(ref: String): YamlError = UnknownPlanRef(ref)
  def unknownAlertOptOutRef(unit: UnitDef, ref: String): YamlError = UnknownAlertOptOutRef(unit, ref)
  def duplicateAlertName(name: String): YamlError = DuplicateAlertName(name)
  def duplicateRuleName(name: String): YamlError = DuplicateRuleName(name)
  def invalidIpAddress(ip: String): YamlError = InvalidIpAddress(ip)
  def invalidExternalAddress(uri: String): YamlError = InvalidExternalAddress(uri)
  def invalidExpirationPolicy(str: String): YamlError = InvalidExpirationPolicy(str)
  def invalidEmail(str: String): YamlError = InvalidEmail(str)
  def invalidDesiredContainerCount(i: Int): YamlError = InvalidDesiredContainerCount(i)
  def invalidSchedule(s: String): YamlError = InvalidSchedule(s)
  def unknownExpirationPolicyRef(s: String): YamlError = UnknownExpirationPolicyRef(s)
  def invalidDuration(d: String, reason: String): YamlError = InvalidDuration(d, reason)
  def invalidProtocol(s: String): YamlError = InvalidProtocol(s)
  def invalidAlphaNumHyphen(s: String, field: String): YamlError = InvalidAlphaNumHyphen(s, field)
  def invalidURI(uri: String): YamlError = InvalidURI(uri)
  def invalidCPU(d: Double): YamlError = InvalidCPU(d)
  def invalidCPUBound(request: Double, limit: Double): YamlError = InvalidCPUBound(request, limit)
  val missingCPULimit: YamlError = MissingCPULimit
  def invalidMemory(d: Double): YamlError = InvalidMemory(d)
  def invalidMemoryBound(request: Double, limit: Double): YamlError = InvalidMemoryBound(request, limit)
  val missingMemoryLimit: YamlError = MissingMemoryLimit
  def invalidInstances(d: Int): YamlError = InvalidInstances(d)
  def invalidEphemeralDisk(min: Int, max: Int, request: Int): YamlError = InvalidEphemeralDisk(min, max, request)
  def invalidTrafficShiftPolicy(name: String): YamlError = InvalidTrafficShift(name)
  def invalidTrafficShiftDuration(dur: String): YamlError = InvalidTrafficShiftDuration(dur)
  def invalidNamespace(n: String, reason: String): YamlError = InvalidNamespace(n,reason)
  def emptyList(name: String): YamlError = EmptyList(name)
  def invalidMetaLength(meta: String): YamlError = InvalidMetaLength(meta)
  val missingVolumeName: YamlError = MissingVolumeName
  def invalidMountPath(path: String, reason: String): YamlError = InvalidMountPath(path, reason)
  val missingVolumeSize: YamlError = MissingVolumeSize
  def invalidVolumeSize(request: Int): YamlError = InvalidVolumeSize(request)
}

private final case class InvalidExternalAddress(uri: String)
  extends YamlError(s"specified uri '$uri' is not a valid URI. Must be of the form: protocol://host:port")

private final case class InvalidIpAddress(ip: String)
  extends YamlError(s"specified ip address '$ip' is not a valid ipv4 address.")

private final case class LoadError(error: String)
  extends YamlError(s"unable to load the specified yaml. reported error was:\n\n $error")

private final case class MissingProperty(field: String)
  extends YamlError(s"the field '$field' was not found in the yaml document.")

private final case class InvalidWorkflow(name: String)
  extends YamlError(s"'$name' is not a valid workflow.")

private final case class InvalidUnitType(ut: String)
  extends YamlError(s"'$ut' is not a valid unitType, expected 'job' or 'service'")

private final case class InvalidServiceName(sn: String)
  extends YamlError(s"'$sn' is an invalid Service Name. Expected a name in the form 'serviceType@1.2'")

private final case class InvalidFeatureVersion(sn: String, v: String)
  extends YamlError(s"'$sn' is not a valid Service Name because '$v' is not a valid Feature Version. Should be in the form 'breaking.feature'")

private final case class InvalidPortSpecification(msg: String)
  extends YamlError(msg)

private final case class UnknownUnitRef(ref: String)
    extends YamlError(s"'$ref' isn't a valid unit reference, because it was not declared as a unit.")

private final case class UnknownLoadbalancerRef(ref: String)
    extends YamlError(s"'$ref' isn't a valid loadbalancer reference, because it was not declared as a loadbalancer.")

private final case class UnknownPlanRef(ref: String)
    extends YamlError(s"'$ref' isn't a valid plan reference, because it was not declared as a plan.")

private final case class UnknownAlertOptOutRef(unitDef: UnitDef, ref: String)
    extends YamlError(s"'$ref' isn't a valid alert opt-out, because it was not declared in unit '${unitDef.name}'.")

private final case class DuplicateAlertName(name: String)
    extends YamlError(s"'$name' alert name is not unique within this manifest.")

private final case class DuplicateRuleName(name: String)
    extends YamlError(s"'$name' rule name is not unique within this manifest.")

private final case class InvalidExpirationPolicy(policy: String)
    extends YamlError(s"$policy isn't a valid expiration policy.")

private final case class InvalidEmail(str: String)
    extends YamlError(s"$str isn't a valid email")

private final case class InvalidDesiredContainerCount(i: Int)
    extends YamlError(s"$i isn't a valid desired container count")

private final case class InvalidSchedule(msg: String)
    extends YamlError(msg)

private final case class UnknownExpirationPolicyRef(s: String)
    extends YamlError(s"$s isn't a valid expiration policy.")

private final case class InvalidDuration(d: String, reason: String)
    extends YamlError(s"$d is not a valid duration because $reason")

private final case class InvalidProtocol(s: String)
    extends YamlError(s"$s in not a valid protocol. Valid protocols are: 'http', 'https', or 'tcp'")

private final case class InvalidAlphaNumHyphen(s: String, field: String)
    extends YamlError(s"field $field is not a valid alphanumeric hyphen string: $s")

private final case class InvalidURI(uri: String)
    extends YamlError(s"$uri is an invalid URI")

private final case class InvalidCPU(d: Double)
    extends YamlError(s"$d is an invalid CPU request, must be between 0 and 100")

private final case class InvalidCPUBound(request: Double, limit: Double)
    extends YamlError(s"Invalid CPU request, request must be <= $limit but is $request")

private final case object MissingCPULimit
    extends YamlError(s"Cannot specify CPU request without CPU limit.")

private final case class InvalidMemory(d: Double)
    extends YamlError(s"$d is an invalid memory request, must be between 0 and 50000")

private final case class InvalidMemoryBound(request: Double, limit: Double)
    extends YamlError(s"Invalid memory request, request must be <= $limit but is $request")

private final case object MissingMemoryLimit
    extends YamlError(s"Cannot specify memory request without memory limit.")

private final case class InvalidInstances(d: Int)
    extends YamlError(s"$d is an invalid instances request, must be between 0 and 500")

private final case class InvalidEphemeralDisk(min: Int, max: Int, request: Int)
    extends YamlError(s"$request is an invalid ephemeral disk request, must be between $min and $max")

private final case class InvalidTrafficShift(name: String)
  extends YamlError(s"'$name' is not a valid traffic shift policy.")

private final case class InvalidTrafficShiftDuration(dur: String)
  extends YamlError(s"'$dur' is not a valid traffic shift duration. Duration must be between 15 minutes and 5 days")

private final case class InvalidNamespace(n: String, reason: String)
  extends YamlError(s"$n is an invalid namespace name because: $reason")

private final case class EmptyList(field: String)
  extends YamlError(s"the field '$field' can not be empty")

private final case class InvalidMetaLength(meta: String)
  extends YamlError(s"""meta ($meta) must less that or equal to 14 characters""")

private final case object MissingVolumeName
  extends YamlError(s"Missing volume name in volume manifest.")

private final case class InvalidMountPath(path: String, reason: String)
  extends YamlError(s"${path} is an invalid mount path: ${reason}.")

private final case object MissingVolumeSize
  extends YamlError("Missing volume size in volume manifest.")

private final case class InvalidVolumeSize(request: Int)
  extends YamlError(s"$request is an invalid volume disk request, must be > 0 (measured in megabytes).")

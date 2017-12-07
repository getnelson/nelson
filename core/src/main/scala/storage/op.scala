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
package storage

import java.time.Instant

import scalaz.{==>>, @@, Free, NonEmptyList, ValidationNel, \/}
import scalaz.std.list._
import scalaz.syntax.traverse._

sealed trait StoreOp[A]

object StoreOp {
  import nelson.Manifest.{UnitDef,Versioned}
  import Domain._
  import scala.concurrent.duration.FiniteDuration
  import nelson.audit.{AuditLog,AuditEvent}

  def findRepository(u: User, slug: Slug): StoreOpF[Option[Repo]] =
    Free.liftFC(FindRepository(u, slug))

  def listRepositories(u: User): StoreOpF[List[Repo]] =
    Free.liftFC(ListRepositories(u))

  def listRepositoriesWithOwner(u: User, owner: String): StoreOpF[List[Repo]] =
    Free.liftFC(ListRepositoriesWithOwner(u, owner))

  def listRepositoriesWithActiveHooks(u: User): StoreOpF[List[Repo]] =
    Free.liftFC(ListRepositoriesWithActiveHooks(u))

  def insertOrUpdateRepositories(list: List[Repo]): StoreOpF[Unit] =
    Free.liftFC(InsertOrUpdateRepositories(list))

  def linkRepositoriesToUser(list: List[Repo], u: User): StoreOpF[Unit] =
    Free.liftFC(LinkRepositoriesToUser(list, u))

  def deleteRepositories(nel: NonEmptyList[Repo]): StoreOpF[Unit] =
    Free.liftFC(DeleteRepositories(nel))

  def addUnit(unit: UnitDef @@ Versioned, repoId: ID): StoreOpF[Unit] =
    Free.liftFC(AddUnit(unit, repoId))

  def createRelease(repositoryId: Long, r: Github.Release): StoreOpF[Unit] =
    Free.liftFC(CreateRelease(repositoryId, r))

  def killRelease(slug: Slug, version: String): StoreOpF[Throwable \/ Unit] =
    Free.liftFC(KillRelease(slug, version))

  def listRecentReleasesForRepository(slug: Slug): StoreOpF[Released ==>> List[ReleasedDeployment]] =
    Free.liftFC(ListRecentReleasesForRepository(slug))

  def listReleases(limit: Int): StoreOpF[Released ==>> List[ReleasedDeployment]] =
    Free.liftFC(ListReleases(limit))

  def findRelease(id: Long): StoreOpF[Released ==>> List[ReleasedDeployment]] =
    Free.liftFC(FindRelease(id))

  def createDomain(dc: Domain): StoreOpF[Unit] =
    Free.liftFC(CreateDomain(dc))

  def listDomains: StoreOpF[Set[String]] =
    Free.liftFC(ListDomains)

  def listNamespacesForDomain(dc: String): StoreOpF[Set[Namespace]] =
    Free.liftFC(ListNamespacesForDomain(dc))

  def listDeploymentsForNamespaceByStatus(ns: ID, statuses: NonEmptyList[DeploymentStatus], unit: Option[UnitName] = None): StoreOpF[Set[(Deployment, DeploymentStatus)]] =
    Free.liftFC(ListDeploymentsForNamespaceByStatus(ns, statuses, unit))

  def getNamespace(dc: String, nsName: NamespaceName): StoreOpF[Option[Namespace]] =
    Free.liftFC(GetNamespace(dc, nsName))

  def getNamespaceByID(ns: ID): StoreOpF[Namespace] =
    Free.liftFC(GetNamespaceByID(ns))

  def createNamespace(dc: String, name: NamespaceName): StoreOpF[ID] =
    Free.liftFC(CreateNamespace(dc, name))

  def getUnit(name: String, version: Version): StoreOpF[Option[DCUnit]] =
    Free.liftFC(GetUnit(name, version))

  def listDeploymentsForUnitByStatus(nsid: ID, name: UnitName,s: NonEmptyList[DeploymentStatus]): StoreOpF[Set[Deployment]] =
    Free.liftFC(ListDeploymentsForUnitByStatus(nsid, name, s))

  def listDeploymentStatuses(id: ID): StoreOpF[List[(DeploymentStatus, Option[StatusMessage], Instant)]] =
    Free.liftFC(ListDeploymentStatuses(id))

  def getDeploymentStatus(id: ID): StoreOpF[Option[DeploymentStatus]] =
    Free.liftFC(GetDeploymentStatus(id))

  def getDeploymentStatusByGuid(guid: GUID): StoreOpF[Option[DeploymentStatus]] =
    getDeploymentByGuid(guid).flatMap {
      case Some(d) => getDeploymentStatus(d.id)
      case None    => Free.pure(None)
    }

  def getDeploymentResources(id: ID): StoreOpF[Set[(String, java.net.URI)]] =
    Free.liftFC(GetDeploymentResources(id))

  def getRoutableDeploymentsByDomain(dc: Domain): StoreOpF[Set[Deployment]] =
    (for {
      ns <- listNamespacesForDomain(dc.name)
      ds <- ns.toList.traverseM { n =>
        listDeploymentsForNamespaceByStatus(n.id, DeploymentStatus.routable).map(_.toList.map(_._1))
      }
    } yield ds).map(_.toSet)

  def findDeployment(stackName: StackName): StoreOpF[Option[Deployment]] =
    Free.liftFC(FindDeployment(stackName))

  def getDeployment(id: ID): StoreOpF[Deployment] =
    Free.liftFC(GetDeployment(id))

  def createDeployment(unitId: ID, hash: String, ns: Domain.Namespace, wf: WorkflowRef, plan: PlanRef, policy: ExpirationPolicyRef): StoreOpF[ID] =
    Free.liftFC(CreateDeployment(unitId, hash, ns, wf, plan, policy))

  def getDeploymentByGuid(guid: GUID): StoreOpF[Option[Deployment]] =
    Free.liftFC(GetDeploymentByGuid(guid))

  def createDeploymentStatus(id: ID, status: DeploymentStatus, msg: Option[String]): StoreOpF[Unit] =
    Free.liftFC(CreateDeploymentStatus(id, status, msg))

  def listUnitsByStatus(nsid: ID, statuses: NonEmptyList[DeploymentStatus]): StoreOpF[Vector[(GUID,ServiceName)]] =
    Free.liftFC(ListUnitsByStatus(nsid, statuses))

  def createManualDeployment(domain: Domain, namespace: NamespaceName, serviceType: String, version: String, hash: String, description: String, port: Int, exp: Instant): StoreOpF[GUID] =
    Free.liftFC(CreateManualDeployment(domain, namespace, serviceType, version, hash, description, port, exp))

  def findReleaseByDeploymentGuid(guid: GUID): StoreOpF[Option[(Released, ReleasedDeployment)]] =
    Free.liftFC(FindReleaseByDeploymentGuid(guid: GUID))

  def findReleasedByUnitNameAndVersion(u: UnitName, v: Version): StoreOpF[Option[Released]] =
    Free.liftFC(FindReleasedByUnitNameAndVersion(u, v))

  def getDeploymentsForServiceNameByStatus(sn: ServiceName, ns: ID, s: NonEmptyList[DeploymentStatus]): StoreOpF[List[Deployment]] =
    Free.liftFC(GetDeploymentsForServiceNameByStatus(sn, ns, s))

  def getDeploymentForServiceNameByStatus(sn: ServiceName, ns: ID, s: NonEmptyList[DeploymentStatus]): StoreOpF[Option[Deployment]] =
    getDeploymentsForServiceNameByStatus(sn, ns, s).map(_.headOption)

  def createDeploymentExpiration(id : ID, exp: Instant): StoreOpF[ID] =
    Free.liftFC(CreateDeploymentExpiration(id, exp))

  def createDeploymentResource(dId: ID, name: String, uri: java.net.URI): StoreOpF[ID] =
    Free.liftFC(CreateDeploymentResource(dId, name, uri))

  def findDeploymentExpiration(id: ID): StoreOpF[Option[Instant]] =
    Free.liftFC(FindDeploymentExpiration(id))

  def listShiftableDeployments(unit: Manifest.UnitDef, ns: ID): StoreOpF[List[Deployment]] =
    Free.liftFC(ListShiftableDeployments(unit,ns))

  def findDeploymentExpirationByGuid(guid: GUID): StoreOpF[Option[Instant]] =
     getDeploymentByGuid(guid).flatMap {
      case Some(d) => findDeploymentExpiration(d.id)
      case None    => Free.pure(None)
    }

  def getCurrentTargetForServiceName(nsid: ID, sn: ServiceName): StoreOpF[Option[Target]] =
    Free.liftFC(GetCurrentTargetForServiceName(nsid, sn))

  def createTrafficShift(nsid: ID, to: Deployment, p: TrafficShiftPolicy, dur: FiniteDuration): StoreOpF[ID] =
    Free.liftFC(CreateTrafficShift(nsid, to, p, dur))

  def startTrafficShift(from: ID, to: ID, start: Instant): StoreOpF[Option[ID]] =
    Free.liftFC(StartTrafficShift(from, to, start))

  def reverseTrafficShift(id: ID, rev: Instant): StoreOpF[Option[ID]] =
    Free.liftFC(ReverseTrafficShift(id, rev))

  def getTrafficShiftForServiceName(nsid: ID, sn: ServiceName): StoreOpF[Option[Domain.TrafficShift]] =
    Free.liftFC(GetTrafficShiftForServiceName(nsid, sn))

  def verifyDeployable(dcName: String, nsName: NamespaceName, unit: Manifest.UnitDef): StoreOpF[ValidationNel[NelsonError, Unit]] =
    Free.liftFC(VerifyDeployable(dcName, nsName, unit))

  def audit[A](a: AuditEvent[A]): StoreOpF[ID] =
    Free.liftFC[StoreOp, Long](Audit(a))

  def listAuditLog(limit: Long, offset: Long, action: Option[String] = None, category: Option[String] = None): StoreOpF[List[AuditLog]] =
    Free.liftFC(ListAuditLog(limit, offset, action, category))

  def listAuditLogByReleaseId(limit: Long, offset: Long, releaseId: Long): StoreOpF[List[AuditLog]] =
    Free.liftFC(ListAuditLogByReleaseId(limit, offset, releaseId))

  def getLoadbalancer(name: String, v: MajorVersion): StoreOpF[Option[Domain.DCLoadbalancer]] =
    Free.liftFC(GetLoadbalancer(name, v))

  def getLoadbalancerDeployment(id: ID): StoreOpF[Option[Domain.LoadbalancerDeployment]] =
    Free.liftFC(GetLoadbalancerDeployment(id))

  def getLoadbalancerDeploymentByGUID(guid: GUID): StoreOpF[Option[Domain.LoadbalancerDeployment]] =
    Free.liftFC(GetLoadbalancerDeploymentByGUID(guid))

  def findLoadbalancerDeployment(name: String, v: MajorVersion, nsid: ID): StoreOpF[Option[Domain.LoadbalancerDeployment]] =
    Free.liftFC(FindLoadbalancerDeployment(name, v, nsid))

  def listLoadbalancerDeploymentsForNamespace(id: ID): StoreOpF[Vector[Domain.LoadbalancerDeployment]] =
    Free.liftFC(ListLoadbalancerDeploymentsForNamespace(id))

  def insertLoadbalancerDeployment(lbid: ID, nsid: ID, hash: String, address: String): StoreOpF[ID] =
    Free.liftFC(InsertLoadbalancerDeployment(lbid, nsid, hash, address))

  def insertLoadbalancerIfAbsent(lb: Manifest.Loadbalancer @@ Versioned, repoId: ID): StoreOpF[ID] =
    Free.liftFC(InsertLoadbalancerIfAbsent(lb, repoId))

  def deleteLoadbalancerDeployment(lbid: ID): StoreOpF[Int] =
    Free.liftFC(DeleteLoadbalancerDeployment(lbid))

  def countDeploymentsByStatus(since: Instant): StoreOpF[List[(String, Int)]] =
    Free.liftFC(CountDeploymentsByStatus(since.getEpochSecond * 1000))

  def getMostAndLeastDeployed(since: Instant, number: Int, sortOrder: String): StoreOpF[List[(String, Int)]] =
    Free.liftFC(GetMostAndLeastDeployed(since.getEpochSecond * 1000, number, sortOrder))

  def findLastReleaseDeploymentStatus(s: Slug, u: UnitName): StoreOpF[Option[DeploymentStatus]] =
    Free.liftFC(FindLastReleaseDeploymentStatus(s, u))

  def getLatestReleaseForLoadbalancer(name: String, mv: MajorVersion): StoreOpF[Option[Released]] =
    Free.liftFC(GetLatestReleaseForLoadbalancer(name, mv))

  final case class FindRepository(u: User, slug: Slug) extends StoreOp[Option[Repo]]
  final case class ListRepositories(u: User) extends StoreOp[List[Repo]]
  final case class ListRepositoriesWithOwner(u: User, owner: String) extends StoreOp[List[Repo]]
  final case class ListRepositoriesWithActiveHooks(u: User) extends StoreOp[List[Repo]]
  final case class InsertOrUpdateRepositories(list: List[Repo]) extends StoreOp[Unit]
  final case class LinkRepositoriesToUser(list: List[Repo], u: User) extends StoreOp[Unit]
  final case class DeleteRepositories(nel: NonEmptyList[Repo]) extends StoreOp[Unit]
  final case class AddUnit(unit: UnitDef @@ Versioned, repo_id: ID) extends StoreOp[Unit]
  final case class CreateRelease(repositoryId: Long, r: Github.Release) extends StoreOp[Unit]
  final case class KillRelease(slug: Slug, version: String) extends StoreOp[Throwable \/ Unit]
  final case class ListRecentReleasesForRepository(slug: Slug) extends StoreOp[Released ==>> List[ReleasedDeployment]]
  final case class ListReleases(limit: Int) extends StoreOp[Released ==>> List[ReleasedDeployment]]
  final case class FindRelease(id: Long) extends StoreOp[Released ==>> List[ReleasedDeployment]]
  final case class CreateDomain(dc: Domain) extends StoreOp[Unit]
  case object ListDomains extends StoreOp[Set[String]]
  final case class ListNamespacesForDomain(dc: String) extends StoreOp[Set[Namespace]]
  final case class ListDeploymentsForNamespaceByStatus(ns: ID, statuses: NonEmptyList[DeploymentStatus], unit: Option[UnitName]) extends StoreOp[Set[(Deployment,DeploymentStatus)]]
  final case class GetNamespace(dc: String, nsName: NamespaceName) extends StoreOp[Option[Namespace]]
  final case class GetNamespaceByID(ns: ID) extends StoreOp[Namespace]
  final case class CreateNamespace(dc: String, name: NamespaceName) extends StoreOp[ID]
  final case class GetUnit(name: String, version: Version) extends StoreOp[Option[DCUnit]]
  final case class ListDeploymentsForUnitByStatus(nsid: ID, name: UnitName, s: NonEmptyList[DeploymentStatus]) extends StoreOp[Set[Deployment]]
  final case class ListDeploymentStatuses(id: ID) extends StoreOp[List[(DeploymentStatus, Option[StatusMessage], Instant)]]
  final case class GetDeploymentStatus(id: ID) extends StoreOp[Option[DeploymentStatus]]
  final case class GetDeploymentResources(id: ID) extends StoreOp[Set[(String, java.net.URI)]]
  final case class FindDeployment(stackName: StackName) extends StoreOp[Option[Deployment]]
  final case class GetDeployment(id: ID) extends StoreOp[Deployment]
  final case class CreateDeployment(unitId: ID, hash: String, nn: Domain.Namespace, wf: WorkflowRef, plan: PlanRef, policy: ExpirationPolicyRef) extends StoreOp[ID]
  final case class GetDeploymentByGuid(guid: GUID) extends StoreOp[Option[Deployment]]
  final case class CreateDeploymentStatus(id: ID, status: DeploymentStatus, msg: Option[String]) extends StoreOp[Unit]
  final case class ListUnitsByStatus(nsid: ID, statuses: NonEmptyList[DeploymentStatus]) extends StoreOp[Vector[(GUID,ServiceName)]]
  final case class CreateManualDeployment(domain: Domain, namespace: NamespaceName, serviceType: String, version: String, hash: String, description: String, port: Int, ext: Instant) extends StoreOp[GUID]
  final case class FindReleaseByDeploymentGuid(guid: GUID) extends StoreOp[Option[(Released, ReleasedDeployment)]]
  final case class FindReleasedByUnitNameAndVersion(u: UnitName, v: Version) extends StoreOp[Option[Released]]
  final case class GetDeploymentsForServiceNameByStatus(sn: ServiceName, ns: ID, s: NonEmptyList[DeploymentStatus]) extends StoreOp[List[Deployment]]
  final case class CreateDeploymentExpiration(id : ID, exp: Instant) extends StoreOp[ID]
  final case class CreateDeploymentResource(did: ID, name: String, uri: java.net.URI) extends StoreOp[ID]
  final case class FindDeploymentExpiration(id: ID) extends StoreOp[Option[Instant]]
  final case class ListShiftableDeployments(unit: Manifest.UnitDef, ns: ID) extends StoreOp[List[Deployment]]
  final case class GetCurrentTargetForServiceName(nsid: ID, sn: ServiceName) extends StoreOp[Option[Target]]
  final case class CreateTrafficShift(nsid: ID, to: Deployment,  poliy: TrafficShiftPolicy, dur: FiniteDuration) extends StoreOp[ID]
  final case class StartTrafficShift(to: ID, from: ID, start: Instant) extends StoreOp[Option[ID]]
  final case class ReverseTrafficShift(id: ID, rev: Instant) extends StoreOp[Option[ID]]
  final case class GetTrafficShiftForServiceName(nsid: ID, sn: ServiceName) extends StoreOp[Option[Domain.TrafficShift]]
  final case class VerifyDeployable(dcName: String, nsName: NamespaceName, unit: Manifest.UnitDef) extends StoreOp[ValidationNel[NelsonError, Unit]]
  final case class Audit[A](a: AuditEvent[A]) extends StoreOp[ID]
  final case class ListAuditLog(limit: Long, offset: Long, action: Option[String], category: Option[String]) extends StoreOp[List[AuditLog]]
  final case class ListAuditLogByReleaseId(limit: Long, offset: Long, releaseId: Long) extends StoreOp[List[AuditLog]]
  final case class ListLoadbalancerDeploymentsForNamespace(nsid: ID) extends StoreOp[Vector[Domain.LoadbalancerDeployment]]
  final case class FindLoadbalancerDeployment(name: String, v: MajorVersion, nsid: ID) extends StoreOp[Option[Domain.LoadbalancerDeployment]]
  final case class GetLoadbalancerDeployment(id: ID) extends StoreOp[Option[Domain.LoadbalancerDeployment]]
  final case class GetLoadbalancerDeploymentByGUID(guid: String) extends StoreOp[Option[Domain.LoadbalancerDeployment]]
  final case class GetLoadbalancer(name: String, v: MajorVersion) extends StoreOp[Option[Domain.DCLoadbalancer]]
  final case class InsertLoadbalancerDeployment(lbid: ID, nsid: ID, hash: String, address: String) extends StoreOp[ID]
  final case class DeleteLoadbalancerDeployment(lbid: ID) extends StoreOp[Int]
  final case class InsertLoadbalancerIfAbsent(lb: Manifest.Loadbalancer @@ Versioned, repoId: ID) extends StoreOp[ID]
  final case class CountDeploymentsByStatus(since: Long) extends StoreOp[List[(String, Int)]]
  final case class GetMostAndLeastDeployed(since: Long, number: Int, sortOrder: String) extends StoreOp[List[(String, Int)]]
  final case class FindLastReleaseDeploymentStatus(s: Slug, u: UnitName) extends StoreOp[Option[DeploymentStatus]]
  final case class GetLatestReleaseForLoadbalancer(name: String, mv: MajorVersion) extends StoreOp[Option[Released]]
}

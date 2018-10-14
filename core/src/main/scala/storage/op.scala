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

import nelson.blueprint.Blueprint

import cats.data.{NonEmptyList, ValidatedNel}
import cats.free.Free
import cats.implicits._

import java.time.Instant

import scala.collection.immutable.SortedMap

sealed trait StoreOp[A]

object StoreOp {
  import nelson.Manifest.{UnitDef,Versioned}
  import Datacenter._
  import scala.concurrent.duration.FiniteDuration
  import nelson.audit.{AuditLog,AuditEvent}

  def findRepository(u: User, slug: Slug): StoreOpF[Option[Repo]] =
    Free.liftF(FindRepository(u, slug))

  def listRepositories(u: User): StoreOpF[List[Repo]] =
    Free.liftF(ListRepositories(u))

  def listRepositoriesWithOwner(u: User, owner: String): StoreOpF[List[Repo]] =
    Free.liftF(ListRepositoriesWithOwner(u, owner))

  def listRepositoriesWithActiveHooks(u: User): StoreOpF[List[Repo]] =
    Free.liftF(ListRepositoriesWithActiveHooks(u))

  def insertOrUpdateRepositories(list: List[Repo]): StoreOpF[Unit] =
    Free.liftF(InsertOrUpdateRepositories(list))

  def linkRepositoriesToUser(list: List[Repo], u: User): StoreOpF[Unit] =
    Free.liftF(LinkRepositoriesToUser(list, u))

  def deleteRepositories(nel: NonEmptyList[Repo]): StoreOpF[Unit] =
    Free.liftF(DeleteRepositories(nel))

  def addUnit(unit: UnitDef @@ Versioned, repoId: ID): StoreOpF[Unit] =
    Free.liftF(AddUnit(unit, repoId))

  def createRelease(r: Github.DeploymentEvent): StoreOpF[Unit] =
    Free.liftF(CreateRelease(r))

  def killRelease(slug: Slug, version: String): StoreOpF[Either[Throwable, Unit]] =
    Free.liftF(KillRelease(slug, version))

  def listRecentReleasesForRepository(slug: Slug): StoreOpF[SortedMap[Released, List[ReleasedDeployment]]] =
    Free.liftF(ListRecentReleasesForRepository(slug))

  def listReleases(limit: Int): StoreOpF[SortedMap[Released, List[ReleasedDeployment]]] =
    Free.liftF(ListReleases(limit))

  def findRelease(id: Long): StoreOpF[SortedMap[Released, List[ReleasedDeployment]]] =
    Free.liftF(FindRelease(id))

  def createDatacenter(dc: Datacenter): StoreOpF[Unit] =
    Free.liftF(CreateDatacenter(dc))

  def listDatacenters: StoreOpF[Set[String]] =
    Free.liftF(ListDatacenters)

  def listNamespacesForDatacenter(dc: String): StoreOpF[Set[Namespace]] =
    Free.liftF(ListNamespacesForDatacenter(dc))

  def listDeploymentsForNamespaceByStatus(ns: ID, statuses: NonEmptyList[DeploymentStatus], unit: Option[UnitName] = None): StoreOpF[Set[(Deployment, DeploymentStatus)]] =
    Free.liftF(ListDeploymentsForNamespaceByStatus(ns, statuses, unit))

  def getNamespace(dc: String, nsName: NamespaceName): StoreOpF[Option[Namespace]] =
    Free.liftF(GetNamespace(dc, nsName))

  def getNamespaceByID(ns: ID): StoreOpF[Namespace] =
    Free.liftF(GetNamespaceByID(ns))

  def createNamespace(dc: String, name: NamespaceName): StoreOpF[ID] =
    Free.liftF(CreateNamespace(dc, name))

  def getUnit(name: String, version: Version): StoreOpF[Option[DCUnit]] =
    Free.liftF(GetUnit(name, version))

  def listDeploymentsForUnitByStatus(nsid: ID, name: UnitName,s: NonEmptyList[DeploymentStatus]): StoreOpF[Set[Deployment]] =
    Free.liftF(ListDeploymentsForUnitByStatus(nsid, name, s))

  def listDeploymentStatuses(id: ID): StoreOpF[List[(DeploymentStatus, Option[StatusMessage], Instant)]] =
    Free.liftF(ListDeploymentStatuses(id))

  def getDeploymentStatus(id: ID): StoreOpF[Option[DeploymentStatus]] =
    Free.liftF(GetDeploymentStatus(id))

  def getDeploymentStatusByGuid(guid: GUID): StoreOpF[Option[DeploymentStatus]] =
    getDeploymentByGuid(guid).flatMap {
      case Some(d) => getDeploymentStatus(d.id)
      case None    => Free.pure(None)
    }

  def getDeploymentResources(id: ID): StoreOpF[Set[(String, java.net.URI)]] =
    Free.liftF(GetDeploymentResources(id))

  def getRoutableDeploymentsByDatacenter(dc: Datacenter): StoreOpF[Set[Deployment]] =
    (for {
      ns <- listNamespacesForDatacenter(dc.name)
      ds <- ns.toList.flatTraverse { n =>
        listDeploymentsForNamespaceByStatus(n.id, DeploymentStatus.routable).map(_.toList.map(_._1))
      }
    } yield ds).map(_.toSet)

  def findDeployment(stackName: StackName): StoreOpF[Option[Deployment]] =
    Free.liftF(FindDeployment(stackName))

  def getDeployment(id: ID): StoreOpF[Deployment] =
    Free.liftF(GetDeployment(id))

  def createDeployment(unitId: ID, hash: String, ns: Datacenter.Namespace, wf: WorkflowRef, plan: PlanRef, policy: ExpirationPolicyRef): StoreOpF[ID] =
    Free.liftF(CreateDeployment(unitId, hash, ns, wf, plan, policy))

  def getDeploymentByGuid(guid: GUID): StoreOpF[Option[Deployment]] =
    Free.liftF(GetDeploymentByGuid(guid))

  def createDeploymentStatus(id: ID, status: DeploymentStatus, msg: Option[String]): StoreOpF[Unit] =
    Free.liftF(CreateDeploymentStatus(id, status, msg))

  def listUnitsByStatus(nsid: ID, statuses: NonEmptyList[DeploymentStatus]): StoreOpF[Vector[(GUID,ServiceName)]] =
    Free.liftF(ListUnitsByStatus(nsid, statuses))

  def createManualDeployment(datacenter: Datacenter, namespace: NamespaceName, serviceType: String, version: String, hash: String, description: String, port: Int, exp: Instant): StoreOpF[GUID] =
    Free.liftF(CreateManualDeployment(datacenter, namespace, serviceType, version, hash, description, port, exp))

  def findReleaseByDeploymentGuid(guid: GUID): StoreOpF[Option[(Released, ReleasedDeployment)]] =
    Free.liftF(FindReleaseByDeploymentGuid(guid: GUID))

  def findReleasedByUnitNameAndVersion(u: UnitName, v: Version): StoreOpF[Option[Released]] =
    Free.liftF(FindReleasedByUnitNameAndVersion(u, v))

  def getDeploymentsForServiceNameByStatus(sn: ServiceName, ns: ID, s: NonEmptyList[DeploymentStatus]): StoreOpF[List[Deployment]] =
    Free.liftF(GetDeploymentsForServiceNameByStatus(sn, ns, s))

  def getDeploymentForServiceNameByStatus(sn: ServiceName, ns: ID, s: NonEmptyList[DeploymentStatus]): StoreOpF[Option[Deployment]] =
    getDeploymentsForServiceNameByStatus(sn, ns, s).map(_.headOption)

  def createDeploymentExpiration(id : ID, exp: Instant): StoreOpF[ID] =
    Free.liftF(CreateDeploymentExpiration(id, exp))

  def createDeploymentResource(dId: ID, name: String, uri: java.net.URI): StoreOpF[ID] =
    Free.liftF(CreateDeploymentResource(dId, name, uri))

  def findDeploymentExpiration(id: ID): StoreOpF[Option[Instant]] =
    Free.liftF(FindDeploymentExpiration(id))

  def listShiftableDeployments(unit: Manifest.UnitDef, ns: ID): StoreOpF[List[Deployment]] =
    Free.liftF(ListShiftableDeployments(unit,ns))

  def findDeploymentExpirationByGuid(guid: GUID): StoreOpF[Option[Instant]] =
     getDeploymentByGuid(guid).flatMap {
      case Some(d) => findDeploymentExpiration(d.id)
      case None    => Free.pure(None)
    }

  def getCurrentTargetForServiceName(nsid: ID, sn: ServiceName): StoreOpF[Option[Target]] =
    Free.liftF(GetCurrentTargetForServiceName(nsid, sn))

  def createTrafficShift(nsid: ID, to: Deployment, p: TrafficShiftPolicy, dur: FiniteDuration): StoreOpF[ID] =
    Free.liftF(CreateTrafficShift(nsid, to, p, dur))

  def startTrafficShift(from: ID, to: ID, start: Instant): StoreOpF[Option[ID]] =
    Free.liftF(StartTrafficShift(from, to, start))

  def reverseTrafficShift(id: ID, rev: Instant): StoreOpF[Option[ID]] =
    Free.liftF(ReverseTrafficShift(id, rev))

  def getTrafficShiftForServiceName(nsid: ID, sn: ServiceName): StoreOpF[Option[Datacenter.TrafficShift]] =
    Free.liftF(GetTrafficShiftForServiceName(nsid, sn))

  def verifyDeployable(dcName: String, nsName: NamespaceName, unit: Manifest.UnitDef): StoreOpF[ValidatedNel[NelsonError, Unit]] =
    Free.liftF(VerifyDeployable(dcName, nsName, unit))

  def audit[A](a: AuditEvent[A]): StoreOpF[ID] =
    Free.liftF[StoreOp, Long](Audit(a))

  def listAuditLog(limit: Long, offset: Long, action: Option[String] = None, category: Option[String] = None): StoreOpF[List[AuditLog]] =
    Free.liftF(ListAuditLog(limit, offset, action, category))

  def listAuditLogByReleaseId(limit: Long, offset: Long, releaseId: Long): StoreOpF[List[AuditLog]] =
    Free.liftF(ListAuditLogByReleaseId(limit, offset, releaseId))

  def getLoadbalancer(name: String, v: MajorVersion): StoreOpF[Option[Datacenter.DCLoadbalancer]] =
    Free.liftF(GetLoadbalancer(name, v))

  def getLoadbalancerDeployment(id: ID): StoreOpF[Option[Datacenter.LoadbalancerDeployment]] =
    Free.liftF(GetLoadbalancerDeployment(id))

  def getLoadbalancerDeploymentByGUID(guid: GUID): StoreOpF[Option[Datacenter.LoadbalancerDeployment]] =
    Free.liftF(GetLoadbalancerDeploymentByGUID(guid))

  def findLoadbalancerDeployment(name: String, v: MajorVersion, nsid: ID): StoreOpF[Option[Datacenter.LoadbalancerDeployment]] =
    Free.liftF(FindLoadbalancerDeployment(name, v, nsid))

  def listLoadbalancerDeploymentsForNamespace(id: ID): StoreOpF[Vector[Datacenter.LoadbalancerDeployment]] =
    Free.liftF(ListLoadbalancerDeploymentsForNamespace(id))

  def insertLoadbalancerDeployment(lbid: ID, nsid: ID, hash: String, address: String): StoreOpF[ID] =
    Free.liftF(InsertLoadbalancerDeployment(lbid, nsid, hash, address))

  def insertLoadbalancerIfAbsent(lb: Manifest.Loadbalancer @@ Versioned, repoId: ID): StoreOpF[ID] =
    Free.liftF(InsertLoadbalancerIfAbsent(lb, repoId))

  def deleteLoadbalancerDeployment(lbid: ID): StoreOpF[Int] =
    Free.liftF(DeleteLoadbalancerDeployment(lbid))

  def countDeploymentsByStatus(since: Instant): StoreOpF[List[(String, Int)]] =
    Free.liftF(CountDeploymentsByStatus(since.getEpochSecond * 1000))

  def getMostAndLeastDeployed(since: Instant, number: Int, sortOrder: String): StoreOpF[List[(String, Int)]] =
    Free.liftF(GetMostAndLeastDeployed(since.getEpochSecond * 1000, number, sortOrder))

  def findLastReleaseDeploymentStatus(s: Slug, u: UnitName): StoreOpF[Option[DeploymentStatus]] =
    Free.liftF(FindLastReleaseDeploymentStatus(s, u))

  def getLatestReleaseForLoadbalancer(name: String, mv: MajorVersion): StoreOpF[Option[Released]] =
    Free.liftF(GetLatestReleaseForLoadbalancer(name, mv))

  ////////////////////////////////////////////////////////////////////////////////////
  ///////////////////////////////// BLUEPRINTS ///////////////////////////////////////
  ////////////////////////////////////////////////////////////////////////////////////

  def findBlueprint(name: String, revision: Blueprint.Revision): StoreOpF[Option[Blueprint]] =
    Free.liftF(FindBlueprint(name, revision))

  def insertBlueprint(name: String, description: Option[String], sha: Sha256, template: String): StoreOpF[ID] =
    Free.liftF(InsertBlueprint(name, description, sha, template))

  def listBlueprints: StoreOpF[List[Blueprint]] =
    Free.liftF(ListBlueprints)

  final case class FindRepository(u: User, slug: Slug) extends StoreOp[Option[Repo]]
  final case class ListRepositories(u: User) extends StoreOp[List[Repo]]
  final case class ListRepositoriesWithOwner(u: User, owner: String) extends StoreOp[List[Repo]]
  final case class ListRepositoriesWithActiveHooks(u: User) extends StoreOp[List[Repo]]
  final case class InsertOrUpdateRepositories(list: List[Repo]) extends StoreOp[Unit]
  final case class LinkRepositoriesToUser(list: List[Repo], u: User) extends StoreOp[Unit]
  final case class DeleteRepositories(nel: NonEmptyList[Repo]) extends StoreOp[Unit]
  final case class AddUnit(unit: UnitDef @@ Versioned, repo_id: ID) extends StoreOp[Unit]
  final case class CreateRelease(r: Github.DeploymentEvent) extends StoreOp[Unit]
  final case class KillRelease(slug: Slug, version: String) extends StoreOp[Either[Throwable, Unit]]
  final case class ListRecentReleasesForRepository(slug: Slug) extends StoreOp[SortedMap[Released, List[ReleasedDeployment]]]
  final case class ListReleases(limit: Int) extends StoreOp[SortedMap[Released, List[ReleasedDeployment]]]
  final case class FindRelease(id: Long) extends StoreOp[SortedMap[Released, List[ReleasedDeployment]]]
  final case class CreateDatacenter(dc: Datacenter) extends StoreOp[Unit]
  case object ListDatacenters extends StoreOp[Set[String]]
  final case class ListNamespacesForDatacenter(dc: String) extends StoreOp[Set[Namespace]]
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
  final case class CreateDeployment(unitId: ID, hash: String, nn: Datacenter.Namespace, wf: WorkflowRef, plan: PlanRef, policy: ExpirationPolicyRef) extends StoreOp[ID]
  final case class GetDeploymentByGuid(guid: GUID) extends StoreOp[Option[Deployment]]
  final case class CreateDeploymentStatus(id: ID, status: DeploymentStatus, msg: Option[String]) extends StoreOp[Unit]
  final case class ListUnitsByStatus(nsid: ID, statuses: NonEmptyList[DeploymentStatus]) extends StoreOp[Vector[(GUID,ServiceName)]]
  final case class CreateManualDeployment(datacenter: Datacenter, namespace: NamespaceName, serviceType: String, version: String, hash: String, description: String, port: Int, ext: Instant) extends StoreOp[GUID]
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
  final case class GetTrafficShiftForServiceName(nsid: ID, sn: ServiceName) extends StoreOp[Option[Datacenter.TrafficShift]]
  final case class VerifyDeployable(dcName: String, nsName: NamespaceName, unit: Manifest.UnitDef) extends StoreOp[ValidatedNel[NelsonError, Unit]]
  final case class Audit[A](a: AuditEvent[A]) extends StoreOp[ID]
  final case class ListAuditLog(limit: Long, offset: Long, action: Option[String], category: Option[String]) extends StoreOp[List[AuditLog]]
  final case class ListAuditLogByReleaseId(limit: Long, offset: Long, releaseId: Long) extends StoreOp[List[AuditLog]]
  final case class ListLoadbalancerDeploymentsForNamespace(nsid: ID) extends StoreOp[Vector[Datacenter.LoadbalancerDeployment]]
  final case class FindLoadbalancerDeployment(name: String, v: MajorVersion, nsid: ID) extends StoreOp[Option[Datacenter.LoadbalancerDeployment]]
  final case class GetLoadbalancerDeployment(id: ID) extends StoreOp[Option[Datacenter.LoadbalancerDeployment]]
  final case class GetLoadbalancerDeploymentByGUID(guid: String) extends StoreOp[Option[Datacenter.LoadbalancerDeployment]]
  final case class GetLoadbalancer(name: String, v: MajorVersion) extends StoreOp[Option[Datacenter.DCLoadbalancer]]
  final case class InsertLoadbalancerDeployment(lbid: ID, nsid: ID, hash: String, address: String) extends StoreOp[ID]
  final case class DeleteLoadbalancerDeployment(lbid: ID) extends StoreOp[Int]
  final case class InsertLoadbalancerIfAbsent(lb: Manifest.Loadbalancer @@ Versioned, repoId: ID) extends StoreOp[ID]
  final case class CountDeploymentsByStatus(since: Long) extends StoreOp[List[(String, Int)]]
  final case class GetMostAndLeastDeployed(since: Long, number: Int, sortOrder: String) extends StoreOp[List[(String, Int)]]
  final case class FindLastReleaseDeploymentStatus(s: Slug, u: UnitName) extends StoreOp[Option[DeploymentStatus]]
  final case class GetLatestReleaseForLoadbalancer(name: String, mv: MajorVersion) extends StoreOp[Option[Released]]
  final case class FindBlueprint(name: String, revision: Blueprint.Revision) extends StoreOp[Option[Blueprint]]
  final case class InsertBlueprint(name: String, description: Option[String], sha: Sha256, template: String) extends StoreOp[ID]
  final case object ListBlueprints extends StoreOp[List[Blueprint]]
}

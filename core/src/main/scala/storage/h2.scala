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

import argonaut._
import java.net.URI
import scalaz._
import Scalaz._
import scalaz.concurrent.Task
import doobie.imports._
import journal._
import java.time.Instant
import scala.concurrent.duration.{FiniteDuration,MILLISECONDS}

final case class H2Storage(xa: Transactor[Task]) extends (StoreOp ~> Task) {
  import StoreOp._
  import Datacenter._
  import nelson.audit.{AuditLog,AuditEvent,AuditAction,AuditCategory}
  import Manifest.{Namespace => _, Port => _, TrafficShift => _, _}

  val log = Logger[this.type]

  override def apply[A](s: StoreOp[A]): Task[A] = s match {
    case FindRepository(u, slug) => findRepository(u, slug).transact(xa)
    case ListRepositories(u) => listRepositories(u).transact(xa)
    case ListRepositoriesWithOwner(u, owner) => listRepositoriesWithOwner(u, owner).transact(xa)
    case ListRepositoriesWithActiveHooks(u) => listRepositoriesWithActiveHooks(u).transact(xa)
    case InsertOrUpdateRepositories(list) => insertOrUpdateRepositories(list).transact(xa)
    case DeleteRepositories(list) => deleteRepositories(list).transact(xa)
    case LinkRepositoriesToUser(list, u) => linkRepositoriesToUser(list, u).transact(xa)
    case AddUnit(unit, repoId) => addUnit(unit, repoId).transact(xa)
    case CreateRelease(repositoryId, r) => createRelease(repositoryId, r).transact(xa)
    case KillRelease(slug, version) => killRelease(slug, version).transact(xa)
    case ListRecentReleasesForRepository(slug) => listRecentReleasesForRepository(slug).transact(xa)
    case ListReleases(limit) => listReleases(limit).transact(xa)
    case FindRelease(id) => findRelease(id).transact(xa)
    case CreateDatacenter(dc) => createDatacenter(dc).transact(xa)
    case ListDatacenters => listDatacenters.transact(xa)
    case ListNamespacesForDatacenter(dc) => listNamespacesForDatacenter(dc).transact(xa)
    case ListDeploymentsForNamespaceByStatus(ns, stats, unit) => listDeploymentsForNamespaceByStatus(ns, stats, unit).transact(xa)
    case GetNamespace(dc, nsName) => getNamespace(dc, nsName).transact(xa)
    case GetNamespaceByID(ns) => getNamespaceByID(ns).transact(xa)
    case CreateNamespace(dc, name) => createNamespace(dc, name).transact(xa)
    case GetUnit(name, version) => getUnit(name, version).transact(xa)
    case ListDeploymentsForUnitByStatus(nsid, name, s) => listDeploymentsForUnitByStatus(nsid, name, s).transact(xa)
    case ListDeploymentStatuses(id) => listDeploymentStatuses(id).transact(xa)
    case GetDeploymentStatus(id) => getDeploymentStatus(id).transact(xa)
    case GetDeploymentResources(id) => getDeploymentResources(id).transact(xa)
    case FindDeployment(stackName) => findDeployment(stackName).transact(xa)
    case GetDeployment(id) => getDeployment(id).transact(xa)
    case GetDeploymentByGuid(guid) => getDeploymentByGuid(guid).transact(xa)
    case CreateDeployment(unitId, hash, namespace, wf, plan, policy) => createDeployment(unitId, hash, namespace, wf, plan, policy).transact(xa)
    case CreateDeploymentStatus(id, status, msg) => createDeploymentStatus(id, status, msg).transact(xa)
    case CreateManualDeployment(dc, ns, st, v, hash, desc, port, exp) => createManualDeployment(dc, ns, st, v, hash, desc, port, exp).transact(xa)
    case ListUnitsByStatus(nsid, statuses) => listUnitsByStatus(nsid, statuses).transact(xa)
    case FindReleaseByDeploymentGuid(guid) => findReleaseByDeploymentGuid(guid).transact(xa)
    case FindReleasedByUnitNameAndVersion(u, v) => findReleasedByUnitNameAndVersion(u, v).transact(xa)
    case CreateDeploymentExpiration(id, exp) => deleteAndThenCreateExpiration(id, exp).transact(xa)
    case CreateDeploymentResource(dId, name, uri) => createDeploymentResource(dId, name, uri).transact(xa)
    case FindDeploymentExpiration(id) => findDeploymentExpiration(id).transact(xa)
    case ListShiftableDeployments(unit,ns) => listShiftableDeployments(unit,ns).transact(xa)
    case GetDeploymentsForServiceNameByStatus(sn, nsid, s) => getDeploymentsForServiceNameByStatus(sn, nsid, s).transact(xa)
    case GetCurrentTargetForServiceName(nsid, sn) => getCurrentTargetForServiceName(nsid, sn).transact(xa)
    case CreateTrafficShift(nsid, to, policy, dur) => createTrafficShift(nsid, to, policy, dur).transact(xa)
    case StartTrafficShift(from, to, start) => startTrafficShift(from, to, start).transact(xa)
    case ReverseTrafficShift(id, reverse) => reverseTrafficShift(id, reverse).transact(xa)
    case GetTrafficShiftForServiceName(nsid, sn) => getTrafficShiftForServiceName(nsid, sn).transact(xa)
    case VerifyDeployable(dcName, nsName, unit) => verifyDeployable(dcName, nsName, unit).transact(xa)
    case Audit(a) => audit(a).transact(xa)
    case ListAuditLog(limit, offset, action, category) => listAuditLog(limit, offset, action, category).transact(xa)
    case ListAuditLogByReleaseId(limit, offset, releaseId) => listAuditLogByReleaseId(limit, offset, releaseId).transact(xa)
    case GetLoadbalancer(name, v) => getLoadbalancer(name, v).transact(xa)
    case GetLoadbalancerDeployment(id) => getLoadbalancerDeployment(id).transact(xa)
    case GetLoadbalancerDeploymentByGUID(id) => getLoadbalancerDeploymentByGUID(id).transact(xa)
    case FindLoadbalancerDeployment(n,v,nsid) => findLoadbalancerDeployment(n,v,nsid).transact(xa)
    case InsertLoadbalancerDeployment(lbid, nsid, hash, a) => insertLoadbalancerDeployment(lbid, nsid, hash, a).transact(xa)
    case DeleteLoadbalancerDeployment(lbid) => deleteLoadbalancerDeployment(lbid).transact(xa)
    case InsertLoadbalancerIfAbsent(lb, repoId) => insertLoadbalancerIfAbsent(lb, repoId).transact(xa)
    case ListLoadbalancerDeploymentsForNamespace(nsid) => listLoadbalancerDeploymentsForNamespace(nsid).transact(xa)
    case CountDeploymentsByStatus(since) => countDeploymentsByStatus(since).transact(xa)
    case GetMostAndLeastDeployed(since, number, sortOrder) => getMostAndLeastDeployed(since, number, sortOrder).transact(xa)
    case FindLastReleaseDeploymentStatus(s, u) => findLastReleaseDeploymentStatus(s, u).transact(xa)
    case GetLatestReleaseForLoadbalancer(n, mv) => getLatestReleaseForLoadbalancer(n, mv).transact(xa)
  }

  implicit val metaVersion: Meta[Version] =
    Meta[String].xmap({v => Version.fromString(v).yolo(s"metaVersion: could not extract version from $v")}, _.toString)

  // TODO: remove the .get
  implicit val metaFeatureVersion: Meta[FeatureVersion] =
    Meta[String].xmap({v => FeatureVersion.fromString(v).yolo(s"metaFeatureVersion: could not extract feature version from $v")}, _.toString)

  implicit val metaInstant: Meta[Instant] =
    Meta[Long].xmap(Instant.ofEpochMilli, _.toEpochMilli)

  implicit val metaFiniteDuration: Meta[FiniteDuration] =
    Meta[Long].xmap(x => FiniteDuration(x, MILLISECONDS), _.toMillis)

  implicit val metaMajorVersion: Meta[MajorVersion] =
    Meta[Int].xmap(MajorVersion, _.major)

  implicit val namespaceName: Meta[NamespaceName] =
    Meta[String].xmap({name => NamespaceName.fromString(name).toOption.yolo(s"metaNamespaceName: could not extract namespace name from $name")}, _.asString)

  type AuditRow = (ID, Instant, Option[String], String, String, String, Option[String])

  def listAuditLog(
    limit: Long, offset: Long, action: Option[String], category: Option[String]
  ): ConnectionIO[List[AuditLog]] = {
    val aLike = action.getOrElse("%")
    val cLike = category.getOrElse("%")
    val select = sql"""
      SELECT id, timestamp, release_id, event, category, action, login
      FROM PUBLIC.audit_log
      WHERE action LIKE ${aLike}
      AND category LIKE ${cLike}
      ORDER BY timestamp DESC
      LIMIT $limit
      OFFSET $offset
    """
    select
      .query[AuditRow]
      .process
      .map(auditLogFromRow)
      .list
  }

  def listAuditLogByReleaseId(limit: Long, offset: Long, releaseId: Long): ConnectionIO[List[AuditLog]] = {
    val select = sql"""
      SELECT id, timestamp, release_id, event, category, action, login
      FROM PUBLIC.audit_log
      WHERE release_id = $releaseId
      ORDER BY timestamp DESC
      LIMIT $limit
      OFFSET $offset
    """
    select
      .query[AuditRow]
      .process
      .map(auditLogFromRow)
      .list
  }

  def auditLogFromRow(row: AuditRow): AuditLog =
    AuditLog(row._1, row._2, row._3, Parse.parseOption(row._4), row._5, row._6, row._7)

  def audit[A](event: AuditEvent[A]): ConnectionIO[ID] = {
    val json = event.auditable.encode(event.event)
    val jsonStr = json.toString
    val category = AuditCategory.stringify(event.auditable.category)
    val action = AuditAction.stringify(event.action)
    val insert = sql"""
      INSERT INTO PUBLIC.audit_log (timestamp, event, release_id, category, action, login)
      VALUES (${Instant.now()}, $jsonStr, ${event.releaseId}, $category, $action, ${event.userLogin})
     """

     insert
      .update
      .withUniqueGeneratedKeys[ID]("id")
  }

  def createDatacenter(dc: Datacenter): ConnectionIO[Unit] =
    sql"""MERGE INTO PUBLIC.datacenters (name)
          VALUES (${dc.name})
      """.update.run.map {
      case 0 => log.info(s"[createDatacenter] adding ${dc.name} to datacenters (0)")
      case x => log.debug(s"[createDatacenter] adding ${dc.name} to datacenters ($x)")
    }

  def listDatacenters: ConnectionIO[Set[String]] =
    sql"""SELECT name
           FROM PUBLIC.datacenters
           ORDER BY name"""
      .query[String]
      .list.map(_.toSet)

  type DeploymentRow = (ID, ID, ID, String, Instant, WorkflowRef, PlanRef, GUID, ExpirationPolicyRef)

  /**
   *
   */
  def listDeploymentsForUnitByStatus(nsid: ID, name: UnitName, stats: NonEmptyList[DeploymentStatus]): ConnectionIO[Set[Deployment]] = {
    val statusStrings = stats.map(_.toString)
    implicit val statusesParam: Param[statusStrings.type] = Param.many(statusStrings)
    val cio: ConnectionIO[List[Deployment]] = for {
        dep <- sql"""SELECT d.id,
                            d.unit_id,
                            d.namespace_id,
                            d.hash,
                            d.deploy_time,
                            d.workflow,
                            d.plan,
                            d.guid,
                            d.expiration_policy
                     FROM PUBLIC.deployments d
                     JOIN PUBLIC.units u ON u.id = d.unit_id
                     LEFT JOIN PUBLIC.deployment_statuses AS ds
                       ON ds.deployment_id = d.id
                         AND ds.id = (
                           SELECT TOP 1 x.id
                           FROM PUBLIC.deployment_statuses AS x
                           WHERE x.deployment_id = d.id
                           ORDER BY x.id DESC
                       )
                     WHERE u.name = ${name}
                     AND d.namespace_id = ${nsid}
                     AND ds.state IN (${statusStrings: statusStrings.type})
                  """.query[DeploymentRow].process.list

        dep2 <- dep.traverse(deploymentFromRow)
      } yield dep2

    cio.map(_.toSet).map(_ <| (d =>
      log.debug(s"[listDeploymentsForUnitByStatus] found ${d.size} deployments with serviceType ${name} in namespace ${nsid}")))
  }

  def findDeployment(stackName: StackName): ConnectionIO[Option[Deployment]] = {
    for {
      dep <- sql"""SELECT d.id,
                 d.unit_id,
                 d.namespace_id,
                 d.hash,
                 d.deploy_time,
                 d.workflow,
                 d.plan,
                 d.guid,
                 d.expiration_policy
            FROM PUBLIC.deployments d
            JOIN PUBLIC.units u ON u.id = d.unit_id
            WHERE u.name = ${stackName.serviceType}
              AND u.version = ${stackName.version}
              AND d.hash = ${stackName.hash}
       """.query[DeploymentRow].option
      dep2 <- dep.traverse(deploymentFromRow)
    } yield dep2
  }

  /**
   *
   */
  def listDeploymentsForDatacenter(dc: String): ConnectionIO[Set[Deployment]] = {
    val cio: ConnectionIO[List[Deployment]] = for {
        dep <- sql"""SELECT id,
                            unit_id,
                            namespace_id,
                            hash,
                            deploy_time,
                            workflow,
                            plan,
                            guid,
                            expiration_policy
                     FROM PUBLIC.deployments
                     WHERE datacenter=${dc}
                  """.query[DeploymentRow].process.list

        dep2 <- dep.traverse(deploymentFromRow)
      } yield dep2

    cio.map(_.toSet).map(_ <| (d => log.debug(s"[listDeploymentsForDatacenter] returning ${d.size} deployments for datacenter: ${dc}")))
  }

  def createDeploymentResource(deploymentId: ID, name: String, uri: java.net.URI): ConnectionIO[ID] =
    sql"""INSERT INTO PUBLIC.deployment_resources (deployment_id, name, uri)
          VALUES ($deploymentId, ${name}, ${uri.toString})
       """.update
          .withUniqueGeneratedKeys[ID]("id")

  /**
   *
   */
  def createDeployment(unitId: ID, hash: String, namespace: Namespace, wf: WorkflowRef, plan: PlanRef, expPolicy: String): ConnectionIO[ID] =
    sql"""INSERT INTO PUBLIC.deployments (unit_id, namespace_id, hash, deploy_time, workflow, plan, expiration_policy)
          VALUES(${unitId}, ${namespace.id}, ${hash}, ${Instant.now()}, ${wf}, ${plan}, $expPolicy)
       """.update
          .withUniqueGeneratedKeys[ID]("id")
          .map(_ <| (id => log.info(s"[createDeployment] created a deployment of unit: ${unitId} with hash ${hash} in namespace ${namespace.name}")))

  /**
   *
   */
  def createDeploymentStatus(id: ID,
                              status: DeploymentStatus,
                              msg: Option[String]): ConnectionIO[Unit] =
    sql"""
       INSERT INTO PUBLIC.deployment_statuses
              (deployment_id, state, status_msg, status_time)
       VALUES (${id}, ${status.toString}, ${msg}, ${Instant.now()})
       """.update.run.map(_ => log.info(s"[createDeploymentStatus] deployment: ${id} status: ${status} msg: ${msg}"))

  def getDeploymentStatus(id: ID): ConnectionIO[Option[DeploymentStatus]] =
    listDeploymentStatuses(id).map(_.headOption.map(_._1))

  def getDeploymentResources(id: ID): ConnectionIO[Set[(String,java.net.URI)]] = {
    sql"""
      SELECT name, uri
      FROM PUBLIC.deployment_resources
      where deployment_id = $id
    """.query[(String,String)].map(x => (x._1, new java.net.URI(x._2))).list.map(_.toSet)
  }

  /**
   *
   */
  def listDeploymentStatuses(id: ID): ConnectionIO[List[(DeploymentStatus, Option[StatusMessage], Instant)]] =
    sql"""
    SELECT
      s.state,
      s.status_msg,
      s.status_time
    FROM PUBLIC.deployment_statuses AS s
    WHERE deployment_id = $id
    ORDER BY s.deployment_id, s.status_time DESC
    """
    .query[(String, Option[String], Instant)]
    .process
    .map {
      case (state, msg, timestamp) =>
        (DeploymentStatus.fromString(state), msg, timestamp)
    }
    .list

  def getDeployment(id: ID): ConnectionIO[Deployment] = {
    fetchDeployments(
      sql"""SELECT id,
            unit_id,
            namespace_id,
            hash,
            deploy_time,
            workflow,
            plan,
            guid,
            expiration_policy
        FROM PUBLIC.deployments
        WHERE id = ${id}
        ORDER BY id DESC
      """.query[DeploymentRow].process.list).map(_.head)
  }

  def getDeploymentByGuid(guid: GUID): ConnectionIO[Option[Deployment]] =
    fetchDeployments(
      sql"""SELECT id,
            unit_id,
            namespace_id,
            hash,
            deploy_time,
            workflow,
            plan,
            guid,
            expiration_policy
        FROM PUBLIC.deployments
        WHERE guid = ${guid}
        ORDER BY id DESC
      """.query[DeploymentRow].process.list).map(_.headOption)

  type DeploymentWithStatusRow = (ID, ID, ID, String, Instant, WorkflowRef, PlanRef, GUID, ExpirationPolicyRef, DeploymentStatusString)

  def listDeploymentsForNamespaceByStatus(ns: ID, stats: NonEmptyList[DeploymentStatus], unit: Option[UnitName] = None): ConnectionIO[Set[(Deployment, DeploymentStatus)]] = {
    //  See http://tpolecat.github.io/doobie-0.2.3/05-Parameterized.html#dealing-with-in-clauses
    val statusStrings = stats.map(_.toString)
    implicit val statusesParam: Param[statusStrings.type] = Param.many(statusStrings)
    def query1 = sql"""
      SELECT
        d.id,
        d.unit_id,
        d.namespace_id,
        d.hash,
        d.deploy_time,
        d.workflow,
        d.plan,
        d.guid,
        d.expiration_policy,
        ds.state
      FROM PUBLIC.deployments AS d
      LEFT JOIN PUBLIC.deployment_statuses AS ds
        ON ds.deployment_id = d.id
        AND ds.id = (
          SELECT TOP 1 x.id
          FROM PUBLIC.deployment_statuses AS x
          WHERE x.deployment_id = d.id
          ORDER BY x.id DESC
        )
      WHERE namespace_id = ${ns}
          AND ds.state IN (${statusStrings: statusStrings.type})
      ORDER BY id DESC
    """

    def query2(unitName: UnitName) = sql"""
      SELECT
        d.id,
        d.unit_id,
        d.namespace_id,
        d.hash,
        d.deploy_time,
        d.workflow,
        d.plan,
        d.guid,
        d.expiration_policy,
        ds.state
      FROM PUBLIC.deployments AS d
      LEFT JOIN PUBLIC.deployment_statuses AS ds
        ON ds.deployment_id = d.id
        AND ds.id = (
          SELECT TOP 1 x.id
          FROM PUBLIC.deployment_statuses AS x
          WHERE x.deployment_id = d.id
          ORDER BY x.id DESC
        )
      JOIN PUBLIC.units AS u ON u.id = d.unit_id
      WHERE u.name = ${unitName}
          AND namespace_id = ${ns}
          AND ds.state IN (${statusStrings: statusStrings.type})
      ORDER BY id DESC
    """

    val query = (unit match{
      case Some(unitName) => query2(unitName)
      case None => query1
    }).query[DeploymentWithStatusRow].process.list

    fetchDeploymentsWithStatuses(query)
  }

  def fetchDeployments(sql: ConnectionIO[List[DeploymentRow]]): ConnectionIO[Set[Deployment]] =
    for {
      dep <- sql
      dep2 <- dep.traverse(deploymentFromRow).map(_.toSet)
     } yield dep2.toSet

  def fetchDeploymentsWithStatuses(sql: ConnectionIO[List[DeploymentWithStatusRow]]): ConnectionIO[Set[(Deployment,DeploymentStatus)]] =
    for {
      dep <- sql
      dep2 <- dep.traverse(deploymentWithStatusFromRow).map(_.toSet)
     } yield dep2.toSet

  def deploymentFromRow(row: DeploymentRow): ConnectionIO[Deployment] =
    for {
      u <- getUnitById(row._2)
      n <- getNamespaceByID(row._3)
    } yield Deployment(row._1, u, row._4, n, row._5, row._6, row._7, row._8, row._9)

  def deploymentWithStatusFromRow(row: DeploymentWithStatusRow): ConnectionIO[(Deployment, DeploymentStatus)] =
    for {
      u <- getUnitById(row._2)
      n <- getNamespaceByID(row._3)
      s = DeploymentStatus.fromString(row._10)
    } yield (Deployment(row._1, u, row._4, n, row._5, row._6, row._7, row._8, row._9), s)

  def listUnitsByStatus(
    nsid: ID,
    statuses: NonEmptyList[DeploymentStatus]
  ): ConnectionIO[Vector[(GUID,ServiceName)]] = {
    //  See http://tpolecat.github.io/doobie-0.2.3/05-Parameterized.html#dealing-with-in-clauses
    val statusStrings = statuses.map(_.toString)

    implicit val statusesParam: Param[statusStrings.type] = Param.many(statusStrings)
    sql"""SELECT u.name, u.version, u.guid
        FROM PUBLIC.units u
        JOIN PUBLIC.deployments d
          ON u.id = d.unit_id
        JOIN PUBLIC.deployment_statuses s
          ON s.deployment_id = d.id
        WHERE d.namespace_id = $nsid
          AND s.state IN (${statusStrings: statusStrings.type})
        ORDER BY u.name ASC
     """.query[(UnitName, Version, String)].map { case (name, version, guid) =>
      (guid, ServiceName(name, version.toFeatureVersion))
    }.vector.map(_.groupBy(_._2).values.map(_.head).toVector) // distinct by ServiceName
  }

  def createManualDeployment(datacenter: Datacenter,
                             namespace: NamespaceName,
                             serviceType: String,
                             version: String,
                             hash: String,
                             description: String,
                             port: Int,
                             exp: Instant): ConnectionIO[GUID] = {

    def insertUnitIfAbsent: ConnectionIO[ID] = {
      val insertUnit = sql"""
        INSERT INTO PUBLIC.units
               (description,
                version,
                name,
                repository_id
                )
        VALUES (${description},
                ${version},
                ${serviceType},
                -1
                )
        """.update.withUniqueGeneratedKeys[ID]("id")

      val getUnitId = sql"""
        SELECT u.id
        FROM PUBLIC.units u
        WHERE u.name = ${serviceType} AND u.version = ${version}
      """.query[ID].option

      getUnitId.flatMap(_.cata(
        none = insertUnit,
        some = id => id.point[ConnectionIO]
      ))
    }

    def insertRelease(v: String) = sql"""
        MERGE INTO PUBLIC.releases (repository_id, version, timestamp, release_url, release_html_url)
            KEY(repository_id, version)
            VALUES(-1, ${v}, ${Instant.now()}, '', '')
    """.update.run.map(x => log.debug(s"[insertRelease] merged ${x} rows creating a fake release for ${serviceType} version ${v} in namespace ${namespace.asString}"))

    def insertPort(unit: ID) = sql"""
        MERGE INTO PUBLIC.service_ports (unit, port, ref, protocol)
            KEY(unit, ref)
            VALUES(${unit}, ${port}, 'default', 'tcp')
        """.update.run.map(_ => log.debug(s"[insertPort] inserting port: $port for unit $unit"))


    for {
      nsopt <- getNamespace(datacenter.name, namespace)
      Some(ns) = nsopt // YOLO
      _    <- insertRelease(version)
      unit <- insertUnitIfAbsent
      _    <- insertPort(unit)
      id   <- createDeployment(unit, hash, ns, "manual", Manifest.Plan.default.name, cleanup.RetainUntilDeprecated.name)
      d    <- getDeployment(id)
      _    <- createDeploymentStatus(id, DeploymentStatus.Ready, Some(description))
      _    <- createDeploymentExpiration(id, exp)
    } yield d.guid
  }

  /*
   * The only relevant expiration in the most recent so we actively
   * prune the table. If we don't we could run into a situation
   * where the table becomes too large for H2. Within a year of
   * 1K deployments the table would contain around 8M rows.
   * Delete previous expirations in the same transaction as creating new one.
  */
  def deleteAndThenCreateExpiration(id: ID, exp: Instant): ConnectionIO[ID] =
    deleteExpirations(id) *> createDeploymentExpiration(id, exp)

  /*
   * Deletes all expiration for a deployment
   */
  def deleteExpirations(id: ID): ConnectionIO[Int] = {
    val query = sql"""
      DELETE FROM PUBLIC.deployment_expiration
      WHERE deployment_id = $id
    """
    query.update.run
  }

  def createDeploymentExpiration(id : ID, exp: Instant): ConnectionIO[ID] = {
    val millis = exp.toEpochMilli
    val insert = sql"""
      INSERT INTO PUBLIC.deployment_expiration (deployment_id, expiration)
      VALUES ($id, $millis)
    """

    insert
      .update
      .withUniqueGeneratedKeys[ID]("id")
  }

  def findDeploymentExpiration(id: ID): ConnectionIO[Option[Instant]] = {
    val select = sql"""
      SELECT expiration
      FROM PUBLIC.deployment_expiration
      WHERE deployment_id = $id
      ORDER BY id DESC
      LIMIT 1
    """

    select.query[Instant].option
  }

  def listShiftableDeployments(unit: Manifest.UnitDef, ns: ID): ConnectionIO[List[Deployment]] = {
    listDeployments(unit.name, "%", ns, DeploymentStatus.routable)
  }

  def listNamespacesForDatacenter(dc: String): ConnectionIO[Set[Namespace]] = {
    (for {
      nsids <- sql"SELECT id, name FROM PUBLIC.namespaces WHERE datacenter=${dc}".query[(ID, NamespaceName)].list
      nss = nsids.map {
        case (nsid,nsname) => Namespace(nsid, nsname, dc)
      }
    } yield nss.toSet).map(_ <| (s => log.debug(s"""[listNamespacesForDatacenter] found namespaces: ${s.map(_.name).mkString("<",",",">")} in datacenter: $dc""")))
  }

  /**
   * `datacenter` is the primary key and here acts as a forigen key
   * for namespaces. We never want two datacenters with the same name.
   */
  def createNamespace(dc: String, name: NamespaceName): ConnectionIO[ID] =
    sql"""INSERT INTO PUBLIC.namespaces (datacenter, name)
          VALUES (${dc}, ${name.asString})
       """.update.withUniqueGeneratedKeys[ID]("id").map(_ <| (id => log.info(s"[createNamespace] created namespace ${name.asString} with id ${id} in datacenter ${dc}")))

  def getNamespace(dc: String, nsName: NamespaceName): ConnectionIO[Option[Namespace]] =
    (for {
      nsids <- OptionT(
        sql"""SELECT id, name
              FROM PUBLIC.namespaces
              WHERE name = ${nsName.asString}
                   AND datacenter = ${dc}""".query[(ID,NamespaceName)].option)
      (id,name) = nsids
    } yield Namespace(id, name, dc)).run.map(_ <| (ns => log.debug(s"[getNamespace] found ${ns.map(_.id)} as ${nsName.asString} namespace in dc: $dc")))

  def getNamespaceByID(id: ID): ConnectionIO[Namespace] =
    for {
      nsids <- sql"""
        SELECT id, name, datacenter
        FROM PUBLIC.namespaces
        WHERE id=${id}""".query[(ID,NamespaceName,String)].unique
      (id,name,dc) = nsids
    } yield Namespace(id, name, dc)


  case class ReleaseRow(
    slug: Slug,
    version: Version,
    timestamp: Instant,
    releaseId: String,
    releaseHtmlUrl: URI,
    unitName: String,
    namespace: String,
    hash: String,
    deployid: Long,
    deployTimestamp: Instant,
    deployStatus: DeploymentStatus,
    deployGuid: GUID
  )

  /**
   * Fetch a single repository based on the supplied slug.
   */
  def findRepository(u: User, slug: Slug): ConnectionIO[Option[Repo]] =
    sql"""SELECT r.id, r.slug, u.access, r.hook_id, r.hook_is_active
          FROM PUBLIC.repositories AS r
          LEFT JOIN PUBLIC.user_repositories AS u
          WHERE r.id = u.repository_id
            AND u.login = ${u.login}
            AND r.slug = ${slug.toString}
            AND r.is_deleted = false
        """
      .query[(Long,String,String,Option[Long],Option[Boolean])]
      .process
      .map {
      case (id,slug,acc,Some(hid),Some(hactive)) =>
        Repo(id,slug,acc,Option(Hook(hid,hactive))).toOption
      case (id,slug,acc,_,_) =>
        Repo(id,slug,acc,None).toOption
      }
      .list
      .map(_.flatten)
      .map(_.headOption).map( _ <| (r => log.debug(s"[findRepository] found ${r.map(_.id)} when looking for ${slug} for user: ${u.name}")))

  /**
   * Fetch all repositories for a given owner, that the specified user
   * has access too.
   */
  def listRepositoriesWithOwner(u: User, owner: String): ConnectionIO[List[Repo]] = {
    val constraint = s"$owner/%"
    sql"""SELECT r.id, r.slug, u.access, r.hook_id, r.hook_is_active
          FROM PUBLIC.repositories AS r
          LEFT OUTER JOIN PUBLIC.user_repositories AS u ON r.id = u.repository_id AND u.login = ${u.login}
          WHERE slug LIKE $constraint
            AND is_deleted = false"""
    .query[(Long,String,Option[String],Option[Long],Option[Boolean])]
    .process
    .map {
      case (id,slug,acc,Some(hid),hactive) =>
        Repo(id,slug,acc.getOrElse("unknown"),Option(Hook(hid,hactive.getOrElse(false)))).toOption
      case (id,slug,acc,None,hactive) =>
        Repo(id,slug,acc.getOrElse("unknown"),None).toOption
    }
    .list
    .map(_.flatten)
  }

  /**
   * Fetch all repositories for a given user.
   */
  def listRepositories(u: User): ConnectionIO[List[Repo]] =
    listRepositoriesWithOwner(u, "%") // filthy hack

  /**
   * List all the repositories the user has access too that have
   * webhooks enabled.
   */
  def listRepositoriesWithActiveHooks(u: User): ConnectionIO[List[Repo]] = {
    sql"""SELECT r.id, r.slug, u.access, r.hook_id, r.hook_is_active
          FROM PUBLIC.repositories AS r
          LEFT JOIN PUBLIC.user_repositories AS u
          WHERE r.id = u.repository_id
            AND u.login = ${u.login}
            AND r.hook_is_active = true
            AND r.is_deleted = false"""
    .query[(Long,String,String,Option[Long],Boolean)]
    .process
    .map {
      case (id,slug,acc,Some(hid),hactive) =>
        Repo(id,slug,acc,Option(Hook(hid,hactive))).toOption
      case (id,slug,acc,None,hactive) =>
        Repo(id,slug,acc,None).toOption
    }
    .list
    .map(_.flatten)
  }

  /**
   * Given a set of repositories, associate them with a specific user.
   * This is used when we are syncing repositories at the behence of a user.
   */
  def linkRepositoriesToUser(list: List[Repo], u: User): ConnectionIO[Unit] = {
    def statement(r: Repo): ConnectionIO[Int] =
      sql"""MERGE INTO PUBLIC.user_repositories (repository_id, login, access)
            KEY (repository_id, login)
            VALUES (${r.id}, ${u.login}, ${r.access.toString})""".update.run

    list.traverse(statement)
      .map(_.sum)
      .map(i => log.debug(s"merging $i rows into the existing user_repositories"))
  }

  /**
   * Does what it says on the function.
   */
  def insertOrUpdateRepositories(list: List[Repo]): ConnectionIO[Unit] = {
    def statement(r: Repo): ConnectionIO[Int] =
      sql"""MERGE INTO PUBLIC.repositories (id, slug, hook_id, hook_is_active, is_deleted)
            KEY (id)
            VALUES (${r.id}, ${r.slug.toString}, ${r.hook.map(_.id)}, ${r.hook.map(_.isActive).getOrElse(false)}, false);
            """.update.run

    list.traverse(statement)
      .map(_.sum)
      .map(i => log.debug(s"merging $i rows into the existing repository set"))
  }

  def deleteRepositories(repos: NonEmptyList[Repo]): ConnectionIO[Unit] = {
    val repoIds = repos.map(_.id)
    implicit val reposParam: Param[repoIds.type] = Param.many(repoIds)

    // soft delete
    val query = sql"""
      UPDATE PUBLIC.repositories
      SET is_deleted = true
      WHERE id IN (${repoIds: repoIds.type})
    """

    query.update.run.map(i => log.debug(s"deleting $repos from repositories"))
  }

  def killRelease(slug: Slug, version: String): ConnectionIO[Throwable \/ Unit] =
    sql"""SELECT id FROM repositories WHERE slug=${slug.toString}""".query[Long].option flatMap {
      case Some(r) => sql"""DELETE FROM releases WHERE repository_id=${r} and version=${version}""".update.run.as(\/.right(()))
      case None => \/.left[Throwable, Unit](new NoSuchElementException(s"couldn't find a repo with slug: ${slug}")).point[ConnectionIO]
  }

  /**
   *
   */
  def createRelease(repositoryId: Long, r: Github.Release): ConnectionIO[Unit] = {
    sql"""INSERT INTO PUBLIC.releases (repository_id, version, timestamp, release_id, release_url, release_html_url)
          VALUES ( ${repositoryId}, ${r.tagName}, ${Instant.now()}, ${r.id}, ${r.url}, ${r.htmlUrl} );
       """.update.run.void
  }

  def rowToReleased(row: (String,String,Instant,String,String)): Option[Released] =
    (Slug.fromString(row._1).toOption |@| Version.fromString(row._2)
    )((a,b) => Released(a,b,row._3, row._4, new URI(row._5)))

  // Given a unit name and version find the release event
  def findReleasedByUnitNameAndVersion(un: UnitName, v: Version): ConnectionIO[Option[Released]] = {
    val query = sql"""
      SELECT
        rr.slug,
        r.version,
        r.timestamp,
        r.release_id,
        r.release_html_url
      FROM PUBLIC.units AS u
      INNER JOIN PUBLIC.releases AS r
        ON r.repository_id = u.repository_id
        AND r.version = u.version
      LEFT JOIN PUBLIC.repositories AS rr
        ON rr.id = r.repository_id
      WHERE u.name = $un AND u.version = ${v.toString}
      LIMIT 1
    """.query[(String,String,Instant,String,String)]

    query.map(rowToReleased).option.map(_.flatten)
  }

  def getLatestReleaseForLoadbalancer(name: String, mv: MajorVersion): ConnectionIO[Option[Released]] = {
    val likeVersion = s"${mv}.%"
    val query = sql"""
      SELECT rr.slug, r.version, r.timestamp, r.release_id, release_html_url
      FROM PUBLIC.loadbalancers as lb
      INNER JOIN PUBLIC.releases as r
        ON r.repository_id = lb.repository_id
        AND r.version LIKE ${likeVersion}
      LEFT JOIN PUBLIC.repositories AS rr
        ON rr.id = r.repository_id
      WHERE lb.name = $name AND lb.major_version = ${mv.toString}
      ORDER BY r.release_id DESC
      LIMIT 1
    """.query[(String,String,Instant,String,String)]

    query.map(rowToReleased).option.map(_.flatten)
  }

  type ReleaseTuple = (String,String,Instant,Option[String],Option[String],Option[String],String,String,Long,Instant,Option[String],GUID)

  // TIM: major, major hack. tpolecat could not suggest better though,
  // so for now, we go with this to avoid duplicating the query. Essentially
  // this same SQL is used to list all releases (limited), list all releases
  // for a given repo, and list a single specific release by version.
  private def _listReleasesQuery(
    slug: Option[Slug],
    releaseId: Option[Long],
    deploymentGuid: Option[GUID],
    unitName: Option[UnitName] = None,
    limit: Int = 50
  ): ConnectionIO[Released ==>> List[ReleasedDeployment]] = {
    val base = s"""
      SELECT
        rr.slug,
        r.version AS release_version,
        r.timestamp,
        r.release_id,
        r.release_html_url,
        u.name AS unit_name,
        n.name AS namespace_name,
        d.hash AS deployment_hash,
        d.id AS deployment_id,
        d.deploy_time AS deployment_time,
        ds.state AS deployment_state,
        d.guid
      FROM PUBLIC.deployments AS d
      INNER JOIN PUBLIC.namespaces AS n
        ON n.id = d.namespace_id
      INNER JOIN PUBLIC.units AS u
        ON u.id = d.unit_id
      INNER JOIN PUBLIC.releases AS r
        ON r.repository_id = u.repository_id
        AND r.version = u.version
      LEFT JOIN PUBLIC.repositories AS rr
        ON rr.id = r.repository_id
      LEFT JOIN PUBLIC.deployment_statuses AS ds
        ON ds.deployment_id = d.id
        AND ds.id = (
          SELECT TOP 1 x.id
          FROM PUBLIC.deployment_statuses AS x
          WHERE x.deployment_id = d.id
          ORDER BY x.id DESC
        )
    """

    val constraint: Query0[ReleaseTuple] = (slug, releaseId, deploymentGuid, unitName) match {
      case (Some(s), None, None, None) =>
        Query[(String,Long), ReleaseTuple](s"""
        $base
        WHERE rr.slug IS ?
        ORDER BY r.release_id DESC
        LIMIT ?
        """, None).toQuery0((s.toString, limit.toLong))
      case (None, Some(id), None, None) =>
        Query[(Long,Long), ReleaseTuple](s"""
        $base
        WHERE r.release_id IS ?
        ORDER BY r.release_id DESC
        LIMIT ?
        """, None).toQuery0((id, limit.toLong))
      case (None, None, Some(id), None) =>
        Query[GUID, ReleaseTuple](s"""
        $base
        WHERE d.guid IS ?
        LIMIT 1
        """, None).toQuery0(id)
      case (Some(s), None, None, Some(un)) =>
        Query[(String,String), ReleaseTuple](s"""
        $base
        WHERE rr.slug IS ?
        AND u.name = ?
        ORDER BY r.release_id DESC
        LIMIT 1
        """, None).toQuery0((s.toString, un))
      case _ =>
        Query[Long, ReleaseTuple](
          s"$base ORDER BY r.release_id DESC LIMIT ?"
        ).toQuery0(limit.toLong)
    }

    val result = constraint.process.map {
      case (slug,ver,ts,Some(releaseid),Some(url),Some(uname),ns,hash,deployid,dts,state,dguid) =>
        (Slug.fromString(slug).toOption |@|
          Version.fromString(ver) |@|
          state.map(DeploymentStatus.fromString(_))
        )((a,b,c) => ReleaseRow(a, b, ts, releaseid, new URI(url), uname, ns, hash, deployid, dts, c, dguid))
      case _ =>
        log.error(s"unexpected non-result when querying for recent repo releases for '$slug'")
        None // TIM: ZOMG so hacky. THROW AWAY ALL THE ERRORS
    }
    .list
    .map(_.flatten)

    val combined: ConnectionIO[Released ==>> List[ReleasedDeployment]] =
      for {
        a <- result
        b <- a.traverse(row => getUnit(row.unitName, row.version).map{
          case Some(unit) => {
            val rel = Released(
              slug = row.slug,
              version = row.version,
              timestamp = row.timestamp,
              releaseId = row.releaseId,
              releaseHtmlUrl = row.releaseHtmlUrl)
            List(rel -> ReleasedDeployment(row.deployid, unit, row.namespace, row.hash, row.deployTimestamp, row.deployStatus, row.deployGuid))
          }
          case None => List.empty[(Released,ReleasedDeployment)]
        })
      } yield ==>>.fromListWith(b.flatten.map { case (r,d) => r -> List(d) })((y,z) => y ++ z)

    combined
  }

  /**
   *
   */
  def findLastReleaseDeploymentStatus(r: Slug, u: UnitName): ConnectionIO[Option[DeploymentStatus]] =
    _listReleasesQuery(
      slug = Some(r),
      releaseId = None,
      deploymentGuid = None,
      unitName = Some(u)).map(_.values.flatten.headOption.map(_.state))

  /**
   *
   */
  def listReleases(limit: Int): ConnectionIO[Released ==>> List[ReleasedDeployment]] =
    _listReleasesQuery(
      slug = None,
      releaseId = None,
      deploymentGuid = None,
      limit = limit)

  /**
   *
   */
  def listRecentReleasesForRepository(slug: Slug): ConnectionIO[Released ==>> List[ReleasedDeployment]] =
    _listReleasesQuery(
      slug = Option(slug),
      releaseId = None,
      deploymentGuid = None,
      limit = 50)

  /**
   *
   */
  def findRelease(id: Long): ConnectionIO[Released ==>> List[ReleasedDeployment]] =
    _listReleasesQuery(
      slug = None,
      releaseId = Option(id),
      deploymentGuid = None,
      limit = 20)

  /**
   * Given a deployment ID, find all the associated gubbins.
   */
  def findReleaseByDeploymentGuid(guid: GUID): ConnectionIO[Option[(Released, ReleasedDeployment)]] =
    _listReleasesQuery(
      slug = None,
      releaseId = None,
      deploymentGuid = Option(guid),
      limit = 20).map { pair =>
      for {
        (k,v) <- pair.findMax
        o     <- v.find(_.guid == guid)
      } yield (k,o)
    }

  type TrafficShiftRow =
    (String,Instant,FiniteDuration,Option[Instant],
     ID, ID, ID, String, Instant, WorkflowRef, PlanRef, GUID, ExpirationPolicyRef,
     ID, ID, ID, String, Instant, WorkflowRef, PlanRef, GUID, ExpirationPolicyRef)

  private def trafficShiftFromRow(row: TrafficShiftRow): ConnectionIO[Datacenter.TrafficShift] = {
    val (ref,st,dur,rev,
         fid,fuid,fns,fhash,fdt,fwf,fp,fguid,fexp,
         tid,tuid,tns,thash,tdt,twf,tp,tguid,texp) = row
    for {
      f <- deploymentFromRow((fid,fuid,fns,fhash,fdt,fwf,fp,fguid,fexp))
      t <- deploymentFromRow((tid,tuid,tns,thash,tdt,twf,tp,tguid,texp))
      b <- TrafficShiftPolicy.fromString(ref).yolo(s"can't parse traffic shift policy $ref").point[ConnectionIO]
    } yield TrafficShift(f, t, b, st, dur, rev)
  }

  // Returns the most recent traffic shift for a given service name.
  // Both from and to deployments must be in an routable state (i.e. ready or deprecated)
  def getTrafficShiftForServiceName(ns: ID, sn: ServiceName): ConnectionIO[Option[TrafficShift]] = {
    val statuses: NonEmptyList[String] = DeploymentStatus.routable.map(_.toString)
    implicit val statusesParam: Param[statuses.type] = Param.many(statuses)
    val likeVersion = s"${sn.version.major}.%"
    val sql = sql"""
       SELECT tb.policy, tbs.start_time, tb.duration, tbr.reverse_time,
           fd.id, fu.id, fd.namespace_id, fd.hash, fd.deploy_time,fd.workflow, fd.plan, fd.guid, fd.expiration_policy,
           td.id, tu.id, td.namespace_id, td.hash, td.deploy_time,td.workflow, td.plan, td.guid, td.expiration_policy
       FROM PUBLIC.traffic_shifts tb
       JOIN PUBLIC.traffic_shift_start tbs ON tbs.traffic_shift_id = tb.id
       LEFT OUTER JOIN PUBLIC.traffic_shift_reverse tbr ON tbr.traffic_shift_id = tb.id

       JOIN PUBLIC.deployments fd ON tbs.from_deployment = fd.id
       JOIN PUBLIC.deployment_statuses fd_ds on fd_ds.deployment_id = fd.id
       LEFT JOIN PUBLIC.deployment_statuses fd_ds2 on fd_ds2.deployment_id = fd.id and fd_ds2.status_time > fd_ds.status_time
       JOIN PUBLIC.units fu on fd.unit_id = fu.id

       JOIN PUBLIC.deployments td ON tb.to_deployment = td.id
       JOIN PUBLIC.deployment_statuses td_ds on td_ds.deployment_id = td.id
       LEFT JOIN PUBLIC.deployment_statuses td_ds2 on td_ds2.deployment_id = td.id and td_ds2.status_time > td_ds.status_time
       JOIN PUBLIC.units tu on td.unit_id = tu.id

       WHERE fu.name = ${sn.serviceType}
          AND fu.version like ${likeVersion}
          AND tb.namespace_id=${ns}
          AND fd_ds.state IN (${statuses: statuses.type})
          AND fd_ds2.deployment_id IS NULL
          AND td_ds.state IN (${statuses: statuses.type})
          AND td_ds2.deployment_id IS NULL
       ORDER BY tbs.start_time DESC
       LIMIT 1
     """.query[TrafficShiftRow].option

    sql.flatMap(_.traverse(trafficShiftFromRow))
  }

  private def listDeployments(name: UnitName, likeVersion: String, ns: ID, status: NonEmptyList[DeploymentStatus]): ConnectionIO[List[Deployment]] = {
    val statuses = status.map(_.toString)
    implicit val statusesParam: Param[statuses.type] = Param.many(statuses)
    sql"""
     SELECT d.id,
            d.unit_id,
            d.namespace_id,
            d.hash,
            d.deploy_time,
            d.workflow,
            d.plan,
            d.guid,
            d.expiration_policy
     FROM PUBLIC.deployments d
     JOIN PUBLIC.units u on d.unit_id = u.id
     JOIN PUBLIC.deployment_statuses ds on ds.deployment_id = d.id
     LEFT JOIN PUBLIC.deployment_statuses ds2 on ds2.deployment_id = d.id and ds2.status_time > ds.status_time
     WHERE ds.state IN (${statuses: statuses.type})
        AND d.namespace_id = ${ns}
        AND u.name = $name
        AND u.version LIKE $likeVersion
        AND ds2.deployment_id IS NULL
    """.query[DeploymentRow].list.flatMap(_.traverse(deploymentFromRow _))
  }

  /*
   * Returns an order list of deployment by version and deployment time,
   * within the version bounds provided by likeVersion and the given statuses.
   */
  def getDeploymentsForServiceNameByStatus(sn: ServiceName, ns: ID, status: NonEmptyList[DeploymentStatus]): ConnectionIO[List[Deployment]] = {
    val likeVersion: String = s"${sn.version.toMajorVersion}.%"
    listDeployments(sn.serviceType, likeVersion, ns, status).map { deployments =>
      val minVersion: Version = sn.version.minVersion
      deployments
        .filter(d => Order[Version].greaterThanOrEqual(d.unit.version, minVersion))
        .sorted(Order[Deployment].toScalaOrdering.reverse)
    }
  }

  /**
   * This will first try to find a traffic shift, if none is found, it
   * will try to find the latest matching deployment
   */
  def getCurrentTargetForServiceName(ns: ID, sn: ServiceName): ConnectionIO[Option[Target]] =
    getTrafficShiftForServiceName(ns, sn) flatMap {
      case Some(t) if t.inProgress(Instant.now) =>
        t.some.point[ConnectionIO].map(identity)
      case Some(t) =>
        SingletonTarget(t.deploymentTarget).some.point[ConnectionIO].map(identity)
      case _ =>
        getDeploymentsForServiceNameByStatus(sn, ns, DeploymentStatus.routable).map(_.headOption.map(SingletonTarget.apply))
    }

  def createTrafficShift(nsid: ID, to: Deployment, policy: TrafficShiftPolicy, dur: FiniteDuration): ConnectionIO[ID] = {
    sql"""
       INSERT INTO PUBLIC.traffic_shifts (
          namespace_id,
          to_deployment,
          duration,
          policy
       ) VALUES (
          ${nsid},
          ${to.id},
          ${dur.toMillis},
          ${policy.ref}
       )
    """.update.withUniqueGeneratedKeys[ID]("id")
  }

  /*
   * Start a traffic shift given the to deployment's id.
   * If traffic shift has already been started return same id,
   * otherwise create new start
   */
  def startTrafficShift(from: ID, to: ID, st: Instant): ConnectionIO[Option[ID]] = {

    def getId: ConnectionIO[Option[ID]] =
      sql"""
        SELECT id
        FROM PUBLIC.traffic_shifts
        WHERE to_deployment = ${to}
      """.query[ID].option

    def getStart(tsid: ID): ConnectionIO[Option[ID]] =
      sql"""
        SELECT ID
        FROM PUBLIC.traffic_shift_start
        WHERE traffic_shift_id = ${tsid}
      """.query[ID].option

    def start(tsid: ID): ConnectionIO[ID] = {
      val startTime = st.toEpochMilli
      val insert =
        sql"""
          INSERT INTO PUBLIC.traffic_shift_start (traffic_shift_id, start_time, from_deployment)
          VALUES (${tsid}, ${startTime}, ${from})
        """.update.withUniqueGeneratedKeys[ID]("id")

      getStart(tsid).flatMap(_.cata(
        none = insert,
        some = _.point[ConnectionIO]
      ))
    }

    (for {
      tsid <- OptionT(getId)
      id   <- OptionT(start(tsid).map(Option(_)))
    } yield id).run
  }

  /*
   * Start a traffic shift reverse given the to deployment's id.
   * If traffic reverse has already been started return same id,
   * otherwise create new reverse
   */
  def reverseTrafficShift(toID: ID, rev: Instant): ConnectionIO[Option[ID]] = {

    def getId: ConnectionIO[Option[ID]] =
      sql"""
        SELECT id
        FROM PUBLIC.traffic_shifts
        WHERE to_deployment = ${toID}
      """.query[ID].option

    def getReverse(tsid: ID): ConnectionIO[Option[ID]] =
      sql"""
        SELECT ID
        FROM PUBLIC.traffic_shift_reverse
        WHERE traffic_shift_id = ${tsid}
      """.query[ID].option

    def reverse(tsid: ID): ConnectionIO[ID] = {
      val reverseTime = rev.toEpochMilli
      val insert =
        sql"""
          INSERT INTO PUBLIC.traffic_shift_reverse (traffic_shift_id, reverse_time)
          VALUES (${tsid}, ${reverseTime})
        """.update.withUniqueGeneratedKeys[ID]("id")

       getReverse(tsid).flatMap(_.cata(
        none = insert,
        some = _.point[ConnectionIO]
      ))
    }

    (for {
      tsid <- OptionT(getId)
      id   <- OptionT(reverse(tsid).map(Option(_)))
    } yield id).run
  }

  def verifyDeployable(dcName: String, ns: NamespaceName, unit: Manifest.UnitDef): ConnectionIO[ValidationNel[NelsonError, Unit]] = {

     def error(e: NelsonError): ConnectionIO[ValidationNel[NelsonError, Unit]] =
       e.failureNel[Unit].point[ConnectionIO]


    /*
     * Try to resolve dependency is current namespace.
     * If not resolved then move up namespace ancestory unil
     * depedency is found or root namespace it hit.
     */
     def resolveDependency(sn: ServiceName, ns: Datacenter.Namespace): ConnectionIO[ValidationNel[NelsonError, Unit]] = {

        def hasDefaultPort(d: Deployment, sn: ServiceName): ConnectionIO[ValidationNel[NelsonError, Unit]] =
           if (d.unit.ports.exists(_.name == "default"))
             ().successNel[NelsonError].point[ConnectionIO]
           else
             error(MissingDependency(unit.name, dcName, ns.name.asString, sn))

       // dependencies must expose a default port and be in ready state
       def isReady(d: Deployment, sn: ServiceName): ConnectionIO[ValidationNel[NelsonError, Unit]] =
         getDeploymentStatus(d.id) flatMap {
           case Some(DeploymentStatus.Ready) =>
             ().successNel[NelsonError].point[ConnectionIO]
           case _ =>
             error(DeprecatedDependency(unit.name, dcName, ns.name.asString, sn))
         }

      def checkMinVersion(d: Deployment, sn: ServiceName): ConnectionIO[ValidationNel[NelsonError, Unit]] =
        if (d.unit.version.major == sn.version.major && d.unit.version.minor >= sn.version.minor)
          ().successNel[NelsonError].point[ConnectionIO]
        else
          error(MissingDependency(unit.name, dcName, ns.name.asString, sn))

      def validateTarget(d: Deployment, sn: ServiceName): ConnectionIO[ValidationNel[NelsonError, Unit]] =
        for {
          a <- isReady(d, sn)
          b <- hasDefaultPort(d, sn)
          c <- checkMinVersion(d, sn)
        } yield a +++ b +++ c

      getCurrentTargetForServiceName(ns.id, sn) flatMap {
         case Some(t) =>
           t match {
             case s: SingletonTarget =>
               validateTarget(s.d, sn)
             case t: TrafficShift =>
               validateTarget(t.to, sn)
           }
         case None =>
           // try to resolve dependency is namespace ancestory
           ns.name.parent match {
             case None => // this is a root namespace, dependency is missing
               error(MissingDependency(unit.name, dcName, ns.name.asString, sn))
             case Some(p) =>
               getNamespace(dcName, p) flatMap {
                 case None =>
                   error(UnknownNamespace(dcName, p.asString))
                 case Some(n) =>
                   resolveDependency(sn, n)
               }
           }
       }
     }

     getNamespace(dcName, ns) flatMap {
       case None =>
         error(UnknownNamespace(dcName, ns.asString))
       case Some(n) =>
         unit.dependencies.toVector.traverse { case (st,v) =>
           resolveDependency(ServiceName(st,v), n)
         }.map(_.foldMap(identity))
     }
  }

  def addUnit(vunit: Manifest.UnitDef @@ Versioned, repo_id: ID): ConnectionIO[Unit] = {
    val version = vunit.version
    val unit = Versioned.unwrap(vunit)

    def insertPort(unit: ID)(port: Manifest.Port): ConnectionIO[Unit] =
      sql"""INSERT INTO PUBLIC.service_ports (unit, port, ref, protocol)
            VALUES (${unit}, ${port.port}, ${port.ref}, ${port.protocol})
         """.update.run.map(_ => log.debug(s"[insertPort] inserting port: $port for unit $unit"))

    def insertServiceDependency(unitId: ID)(ds: (String,FeatureVersion)): ConnectionIO[Unit] =
      sql"""INSERT INTO PUBLIC.unit_dependencies (from_unit, to_service, to_version)
            VALUES (${unitId}, ${ds._1}, ${ds._2})
      """.update.run.map(_ => log.debug(s"[insertServiceDependency] adding dependency $ds for unit: $unitId"))

    def insertResource(unitId: ID)(rs: Manifest.Resource): ConnectionIO[Unit] =
      sql"""INSERT INTO PUBLIC.unit_resources (unit_id, name)
            VALUES (${unitId}, ${rs.name})
         """.update.run.map(_ => log.debug(s"[insertResource] adding resource $rs for unit: $unitId"))

    val insertUnit = sql"""
        INSERT INTO PUBLIC.units (
          description,
          version,
          name,
          repository_id
        )
        VALUES (
          ${unit.description},
          ${version},
          ${unit.name},
          ${repo_id}
        )
    """.update.withUniqueGeneratedKeys[ID]("id")

    for {
      id <- insertUnit
      _ <- unit.dependencies.toList.traverse_(insertServiceDependency(id))
      _ <- unit.ports.traverse(_.nel.traverse_(insertPort(id)))
      _ <- unit.resources.traverse_(insertResource(id))
    } yield ()
  }

  def getUnitDependencies(id: ID): ConnectionIO[Set[ServiceName]] =
    sql"""SELECT to_service, to_version FROM unit_dependencies
          WHERE from_unit = ${id}
       """.query[ServiceName].list.map(_.toSet)

  def getUnitResources(id: ID): ConnectionIO[Set[String]] =
    sql"""SELECT name
          FROM PUBLIC.unit_resources
          WHERE unit_id = ${id}
       """.query[String].list.map(_.toSet)

  def getUnitById(id: ID): ConnectionIO[DCUnit] = {
    val res = for {
    un <- sql"""SELECT name, version, description
                FROM PUBLIC.units
                WHERE id = $id""".query[(String, String, String)].unique
    (n,v,desc) = un
    deps <- getUnitDependencies(id)
    ports <- unitPorts(id)
    rs <- getUnitResources(id)
    //TODO .get
    dc = DCUnit(id, n, Version.fromString(v).yolo(s"getUnitById: could not extract version from string $v"), desc, deps, rs, ports)
    } yield dc
    res
  }

  def unitPorts(id: ID): ConnectionIO[Set[Port]] =
    sql"SELECT port, ref, protocol FROM PUBLIC.service_ports WHERE unit = ${id}"
       .query[Port].list.map(_.toSet)

  def unitDependencies(id: ID): ConnectionIO[Set[ServiceName]] =
     sql"""
        SELECT ud.to_service, ud.to_version
        FROM PUBLIC.units d
        JOIN PUBLIC.unit_dependencies ud ON ud.to_service = d.name
        WHERE ud.from_unit=${id}
        """.query[(String,String)].map {
      case (name,versionStr) => FeatureVersion.fromString(versionStr).map(ServiceName(name, _))
    }.list.map(_.collect{ case Some(sn) => sn })
     .map(_.toSet)
     .map(_ <| (sn => log.debug(s"""[unitDependencies] found depedencies: ${sn.mkString("<",",",">")} for unit: $id""")))


  def getUnit(name: String, version: Version): ConnectionIO[Option[DCUnit]] = {

    val unitRowQ: ConnectionIO[Option[(ID, String, String, String)]] = sql"""
                   SELECT id, version, name, description
                   FROM PUBLIC.units
                   WHERE version = ${version.toString} AND name = ${name}
                   """.query[(ID, String, String, String)].option

    val res: OptionT[ConnectionIO, DCUnit] =
      for {
        unitRow                <- OptionT(unitRowQ)
        (id,v,name,desc)        = unitRow
        deps                   <- unitDependencies(id).liftM[OptionT]
        ports <- unitPorts(id).liftM[OptionT]
        rs <- getUnitResources(id).liftM[OptionT]
      } yield DCUnit(
        id,
        name,
        Version.fromString(v).yolo(s"_getUnit: could not extract version from $v"),
        desc,
        deps,
        rs,
        ports
      )

    res.run
  }

  def countDeploymentsByStatus(since: Long): ConnectionIO[List[(String,Int)]] = {
    val present = Instant.now.getEpochSecond * 1000
    sql"""SELECT ds.state, COUNT(ds.state) FROM PUBLIC.deployments d
          LEFT JOIN PUBLIC.deployment_statuses AS ds
          ON ds.deployment_id = d.id
          AND ds.id = (
            SELECT TOP 1 x.id
            FROM PUBLIC.deployment_statuses AS x
            WHERE x.deployment_id = d.id
            ORDER BY x.id DESC
          )
          WHERE d.deploy_time BETWEEN ${since} AND ${present}
          GROUP BY ds.state
      """.query[(String,Int)].list
  }

  def getMostAndLeastDeployed(since: Long, number: Int, sortOrder: String): ConnectionIO[List[(String,Int)]] = {

    val sql =
      s"""SELECT u.name, COUNT(u.name)
          FROM PUBLIC.deployments d
          LEFT JOIN PUBLIC.units AS u
          ON u.id = d.unit_id
          WHERE d.deploy_time BETWEEN ? AND ?
          GROUP BY u.name
          ORDER BY COUNT(u.name) ${sortOrder}
          LIMIT ?
      """

    val present = Instant.now.getEpochSecond * 1000
    Query[(Long, Long, Int), (String, Int)](sql, None)
      .toQuery0((since, present, number))
      .list
  }

  def insertLoadbalancerDeployment(lbid: ID, nsid: ID, hash: String, address: String): ConnectionIO[ID] = {
    sql"""
      INSERT INTO PUBLIC.loadbalancer_deployments (loadbalancer_id, namespace_id, hash, address, deploy_time)
      VALUES (${lbid}, ${nsid}, ${hash}, ${address}, ${Instant.now()})
    """.update.withUniqueGeneratedKeys[ID]("id")
  }

  import Datacenter.{LoadbalancerDeployment, DCLoadbalancer}
  import Manifest.{Route,BackendDestination,Port}

  type RouteRow = (Int,String,String,String,String)
  type LoadbalancerRow = (ID,ID,String,ID,String,MajorVersion,Instant,GUID,String)

  def getLoadbalancerRoutes(id: ID): ConnectionIO[Vector[Manifest.Route]] = {
    def rowToRoute(row: RouteRow): Manifest.Route =
      Route(Port(row._2, row._1, row._3), BackendDestination(row._4,row._5))

    sql"""
      SELECT port, port_reference, protocol, to_unit_name, to_port_reference
      FROM loadbalancer_routes
      WHERE loadbalancer_id = ${id}
    """.query[RouteRow].map(rowToRoute).vector
  }

  def getLoadbalancer(name: String, v: MajorVersion): ConnectionIO[Option[DCLoadbalancer]] = {
    val query = sql"""
      SELECT lb.id, lb.name, lb.major_version
      FROM PUBLIC.loadbalancers lb
      WHERE lb.name = ${name} AND lb.major_version = ${v}
    """.query[(ID,String,MajorVersion)].option

    (for {
      lb <- OptionT(query)
      rt <- getLoadbalancerRoutes(lb._1).liftM[OptionT]
    } yield DCLoadbalancer(lb._1, lb._2, lb._3, rt)).run
  }

  private def loadbalancerRowToDeployment(lb: LoadbalancerRow): ConnectionIO[LoadbalancerDeployment] = {
    getLoadbalancerRoutes(lb._4).map(rt =>
      LoadbalancerDeployment(lb._1, lb._2, lb._3,
        DCLoadbalancer(lb._4, lb._5, lb._6, rt), lb._7, lb._8, lb._9))
  }

  def findLoadbalancerDeployment(name: String, v: MajorVersion, nsid: ID): ConnectionIO[Option[LoadbalancerDeployment]] = {
    val query: ConnectionIO[Option[LoadbalancerRow]] = sql"""
      SELECT d.id, d.namespace_id, d.hash, lb.id, lb.name, lb.major_version, d.deploy_time, d.guid, d.address
      FROM PUBLIC.loadbalancers lb
      JOIN PUBLIC.loadbalancer_deployments d on lb.id = d.loadbalancer_id
      WHERE lb.name = ${name}
        AND lb.major_version = ${v}
        AND d.namespace_id = ${nsid}
      LIMIT 1
    """.query[LoadbalancerRow].option

    (for {
      row <- OptionT(query)
      lb  <- loadbalancerRowToDeployment(row).liftM[OptionT]
    } yield lb).run
  }

  def getLoadbalancerDeployment(id: ID): ConnectionIO[Option[LoadbalancerDeployment]] = {
    val query = sql"""
      SELECT d.id, d.namespace_id, d.hash, lb.id, lb.name, lb.major_version, d.deploy_time, d.guid, d.address
      FROM PUBLIC.loadbalancers lb
      JOIN PUBLIC.loadbalancer_deployments d on lb.id = d.loadbalancer_id
      WHERE d.id = ${id}
    """.query[LoadbalancerRow].option

    (for {
      row <- OptionT(query)
      lb  <- loadbalancerRowToDeployment(row).liftM[OptionT]
    } yield lb).run
  }

  def getLoadbalancerDeploymentByGUID(guid: GUID): ConnectionIO[Option[LoadbalancerDeployment]] = {
    val query = sql"""
      SELECT d.id, d.namespace_id, d.hash, lb.id, lb.name, lb.major_version, d.deploy_time, d.guid, d.address
      FROM PUBLIC.loadbalancers lb
      JOIN PUBLIC.loadbalancer_deployments d on lb.id = d.loadbalancer_id
      WHERE d.guid = ${guid}
    """.query[LoadbalancerRow].option

    (for {
      row <- OptionT(query)
      lb  <- loadbalancerRowToDeployment(row).liftM[OptionT]
    } yield lb).run
  }

  def listLoadbalancerDeploymentsForNamespace(nsid: ID): ConnectionIO[Vector[LoadbalancerDeployment]] = {
    val query = sql"""
      SELECT d.id, d.namespace_id, d.hash, lb.id, lb.name, lb.major_version, d.deploy_time, d.guid, d.address
      FROM PUBLIC.loadbalancers lb
      JOIN PUBLIC.loadbalancer_deployments d on lb.id = d.loadbalancer_id
      WHERE d.namespace_id = ${nsid}
    """.query[LoadbalancerRow].vector

    for {
      lbs <- query
      rts <- lbs.traverse(loadbalancerRowToDeployment)
    } yield rts
  }

  def insertLoadbalancerIfAbsent(lbv: Manifest.Loadbalancer @@ Versioned, repoId: ID): ConnectionIO[ID] = {
    def getLoadbalancerId(name: String, version: MajorVersion) =
      sql"""
        SELECT lb.id
        FROM PUBLIC.loadbalancers lb
        WHERE lb.name = ${name} AND lb.major_version = ${version}
      """.query[ID].option

    val major = lbv.version.toMajorVersion
    val lb = Manifest.Versioned.unwrap(lbv)

    getLoadbalancerId(lb.name, major).flatMap(_.cata(
      none = insertLoadbalancer(lb, major, repoId),
      some = id => id.point[ConnectionIO]
    ))
  }

  def insertLoadbalancer(lb: Manifest.Loadbalancer, version: MajorVersion, repoId: ID): ConnectionIO[ID] = {

    def insertRoutes(lbid: ID, routes: Vector[Manifest.Route]): ConnectionIO[Vector[ID]] = {
      routes.traverse { r =>
        sql"""
          INSERT INTO PUBLIC.loadbalancer_routes (loadbalancer_id, port, port_reference, protocol, to_unit_name, to_port_reference)
          VALUES (${lbid}, ${r.port.port}, ${r.port.ref}, ${r.port.protocol}, ${r.destination.name}, ${r.destination.portReference})
        """.update.withUniqueGeneratedKeys[ID]("id")
      }
    }

    def insertLB(name: String, version: MajorVersion) =
      sql"""
        INSERT INTO PUBLIC.loadbalancers (name, major_version, repository_id)
        VALUES (${name}, ${version}, ${repoId})
      """.update.withUniqueGeneratedKeys[ID]("id")

    for {
      id <- insertLB(lb.name, version)
      _  <- insertRoutes(id, lb.routes)
    } yield id
  }

  def deleteLoadbalancerDeployment(lbid: ID): ConnectionIO[Int] = {
    val query = sql"""DELETE FROM PUBLIC.loadbalancer_deployments WHERE id = ${lbid}"""
    query.update.run
  }
}

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
package cleanup

import nelson.Datacenter.Deployment
import nelson.routing.{RoutingNode,RoutingGraph}

import ca.mrvisser.sealerate

import java.time.Instant

import scala.concurrent.duration._

/*
 * An EpxirationPolicy defines a policy that given
 * a Deployment and RoutingGraph optionally returns
 * a Duration to extend the deploymenst expiration by.
 */
sealed trait ExpirationPolicy {
  def name: String
  def policy(d: DeploymentCtx, g: RoutingGraph)(ext: Duration): Option[Duration]
}

/*
 * Retains the latest major version across stacks, i.e: 2.X.X
 */
object RetainLatest extends ExpirationPolicy {

  val name: String = "retain-latest"

  def policy(d: DeploymentCtx, g: RoutingGraph)(ext: Duration): Option[Duration] = {
    val ds = ExpirationPolicy.filterDeploymentsByName(g.nodes, d.deployment)
    val latest = Deployment.getLatestVersion(ds.toSet)
    if (latest.exists(_ == d.deployment.unit.version) &&
        !ExpirationPolicy.isDeprecated(d))
      Some(ext)
    else
      None
  }
}

/*
 * Retains the latest two major version across stacks, i.e: 2.X.X, 1.X.X
 */
object RetainLatestTwoMajor extends ExpirationPolicy {

  val name = "retain-latest-two-major"

  def policy(d: DeploymentCtx, g: RoutingGraph)(ext: Duration): Option[Duration] = {
    val ds = ExpirationPolicy.filterDeploymentsByName(g.nodes, d.deployment)
    val latest = Deployment.getLatestVersion(ds.toSet)
    val second = Deployment.getLatestVersion(ds.filter(x =>
      latest.exists(y => y.major != x.unit.version.major)).toSet)
    if ((latest.exists(_ == d.deployment.unit.version) || second.exists(_ == d.deployment.unit.version)) &&
        !ExpirationPolicy.isDeprecated(d))
      Some(ext)
    else
      None
  }
}

/*
 * Retains the latest two feature version across stacks, i.e: 2.2.X, 2.1.X
 */
object RetainLatestTwoFeature extends ExpirationPolicy {

  val name = "retain-latest-two-feature"

  def policy(d: DeploymentCtx, g: RoutingGraph)(ext: Duration): Option[Duration] = {
    val ds = ExpirationPolicy.filterDeploymentsByName(g.nodes, d.deployment)
    val latest = Deployment.getLatestVersion(ds.toSet)
    // same major different minor
    val second = Deployment.getLatestVersion(ds.filter(x => latest.exists(y =>
        y.major == x.unit.version.major && y.minor != x.unit.version.minor)).toSet)
    if ((latest.exists(_ == d.deployment.unit.version) || second.exists(_ == d.deployment.unit.version)) &&
        !ExpirationPolicy.isDeprecated(d))
      Some(ext)
    else
      None
  }
}

/*
 * Retains if there are any incomming links in RoutingGraph
 */
object RetainActive extends ExpirationPolicy {

  val name = "retain-active"

  def policy(d: DeploymentCtx, g: RoutingGraph)(ext: Duration): Option[Duration] = {
    val node = RoutingNode(d.deployment)
    if (g.contains(node) && !g.predecessors(node).isEmpty)
      Some(ext)
    else
      None
  }
}

 /*
 * Retains until deprecated or failed
 */
object RetainUntilDeprecated extends ExpirationPolicy {

  val name = "retain-until-deprecated"

  def policy(d: DeploymentCtx, g: RoutingGraph)(ext: Duration): Option[Duration] =
    if (!ExpirationPolicy.isDeprecated(d))
      Some(ext)
    else
      None
}

object ExpirationPolicy {

  val Default = "default"

  val policies: Set[ExpirationPolicy] = sealerate.values[ExpirationPolicy]

  def fromString(str: String): Option[ExpirationPolicy] =
    policies.find(_.name == str)

  def filterDeploymentsByName(nodes: Vector[RoutingNode], d: Deployment): Vector[Deployment] =
    nodes.flatMap(_.deployment).filter(_.unit.name == d.unit.name)

  // deprecated deployments are still in routing graph
  // but for the purposes of and expiration policy we want to filter them out
  def isDeprecated(d: DeploymentCtx): Boolean =
    d.status == DeploymentStatus.Deprecated

  def description(p: ExpirationPolicy): String = p match {
    case RetainLatest => "retains the latest version, only applicable to periodic deployments"
    case RetainLatestTwoMajor => "retains the latest two major versions, i.e. 2.X.X and 1.X.X, only applicable to periodic deployments"
    case RetainLatestTwoFeature => "retains the latest two feature versions, i.e. 2.3.X and 2.2.X, only applicable to periodic deployments"
    case RetainActive => "retains all active versions, i.e any version with an active incoming dependency"
    case RetainUntilDeprecated => "retains until deployment is marked as deprecated"
  }

  object Json {
    import argonaut._, Argonaut._
    implicit val policyEncoder: EncodeJson[ExpirationPolicy] =
      EncodeJson((p: ExpirationPolicy) =>
        ("policy" := p.name) ->:
        ("description" := description(p)) ->:
        jEmptyObject
      )
  }
}


object ExpirationPolicyProcess {
  import nelson.Datacenter.{Namespace,Deployment}
  import nelson.storage.{StoreOp, StoreOpF}
  import cats.effect.IO
  import cats.syntax.order._
  import fs2.{Pipe, Stream}

  private val logger = journal.Logger[ExpirationPolicyProcess.type]

  /*
   * Given a group of Deployments return the latest ExpirationPolicy as defined by Version
   * Expiration policy is defined per unit, so all deployments with the same version
   * will have the same expiration policy.
   */
  def getLatestPolicy(deployments: Vector[Deployment]): Option[ExpirationPolicy] =
    deployments.foldLeft(Option.empty[Deployment]) { (maxSoFar, deployment) =>
      maxSoFar match {
        case Some(deployment2) =>
          if (deployment2.unit.version >= deployment.unit.version) Some(deployment2)
          else Some(deployment)
        case None => Some(deployment)
      }
    }.flatMap(d => ExpirationPolicy.fromString(d.expirationPolicyRef))

  /*
   * Partitions routing graph by unit name
   */
  def partitionByName(g: RoutingGraph, ns: Namespace): Map[String,Vector[Deployment]] =
    g.nodes.flatMap(_.deployment)
      .filter(_.nsid == ns.id) // downstream and upstream depedencies can be in other namespaces so filter them out
      .groupBy(_.unit.name)

  /*
   * Partitions graph by unit name and gets policy
   */
  def getPolicyForDeployment(d: DeploymentCtx, g: RoutingGraph): Option[ExpirationPolicy] =
    partitionByName(g, d.deployment.namespace).get(d.deployment.unit.name).flatMap(ds => getLatestPolicy(ds))

  /*
   * Applies expiration policies to this Deployment. Policies are appended by taking the max duration
   */
  def applyPolicyToDeployment(d: DeploymentCtx, g: RoutingGraph)(ext: Duration): Option[Duration] =
    getPolicyForDeployment(d,g).flatMap(p => p.policy(d,g)(ext))

  /*
   * Extend the deployments expiration by Duration
   */
  def updateExpiration(d: DeploymentCtx, extension: Duration): StoreOpF[DeploymentCtx] = {
    val i = Instant.now.plusSeconds(extension.toSeconds)
    val id = d.deployment.id
    StoreOp.createDeploymentExpiration(id, i)
      .map(_ => logger.debug(s"extending deployment $d expiration from: $d.exp to: $i"))
      .map(_ => d.copy(exp = Some(i)))
  }

  def expirationProcess(cfg: NelsonConfig): Pipe[IO, CleanupRow, CleanupRow] =
    _.flatMap { case (dc, ns, d, graph) =>
      applyPolicyToDeployment(d, graph)(cfg.cleanup.extendTTL)
        .map(ext => Stream.eval(updateExpiration(d,ext).foldMap(cfg.storage).map(d => (dc,ns,d,graph))))
        .getOrElse(Stream.emit((dc,ns,d,graph)))
    }
}


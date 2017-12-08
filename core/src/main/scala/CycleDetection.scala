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

import nelson.storage.{StoreOp, StoreOpF, run => runs}
import Manifest.{apply => _, _}
import nelson.Domain.DCUnit
import routing._

import scalaz._
import Scalaz._
import scalaz.concurrent.Task

object CycleDetection {
  type Valid[A] = ValidationNel[NelsonError, A]
  type ReverseRoutingGraph = RoutingGraph

  def validateNoCycles(
    m: Manifest,
    cfg: NelsonConfig): DisjunctionT[Task, NonEmptyList[NelsonError], Unit] = {
    val op: StoreOpF[Valid[Unit]] = detect(m, cfg)
    DisjunctionT(runs(cfg.storage, op).map(_.disjunction))
  }

  // Detecting definite cycles:
  // For each namespace:
  // - Get the associated routing graph.
  // - Reverse the graph to get the reachability graph.
  // - For each unit:
  //   - Find ids of all shiftable deployments (for now, the active feature versions).
  //   - Find reachability of shiftable deployments.
  //   - For each declared unit dep:
  //     - For each active deployment id that matches the service name.
  //       - If shiftable deployment id is in reachables:
  //         - Then NelsonError("Dependency cycle detected for $unit in $namespace: ${serviceName.name}:${serviceName.version}.
  //       - Else: Success
  def detect(
    m: Manifest,
    cfg: NelsonConfig): StoreOpF[Valid[Unit]] = {
    for {
      ns <- getNamespaces(cfg.domains, m.namespaces)
      ngs <- getNamespaceGraphs(ns)
      result <- ngs.traverse[StoreOpF, Valid[Unit]] {
        case (namespace, reachabilityGraph) =>
          validateUnitsInNamespace(m, namespace, reachabilityGraph)
      }
    } yield result.sequence_
  }

  def validateUnitsInNamespace(
    m: Manifest,
    namespace: Domain.Namespace,
    reachabilityGraph: ReverseRoutingGraph
  ): StoreOpF[Valid[Unit]] = {
    m.units.traverse[StoreOpF, Valid[Unit]](
      validateUnitDeps(namespace, reachabilityGraph)
    ).map(_.sequence_)
  }

  def getNamespaceGraphs(ns: List[Domain.Namespace])
  : StoreOpF[List[(Domain.Namespace, ReverseRoutingGraph)]] = {
    ns.traverse[StoreOpF, (Domain.Namespace, ReverseRoutingGraph)](n =>
      RoutingTable.routingGraph(n).map(g => (n, g.reverse)))
  }

  def getNamespaces(
    domains: List[Domain],
    namespaces: List[Manifest.Namespace]): StoreOpF[List[Domain.Namespace]] = {
    // These namespaces have been validated by the validations this validation depends on.
    // We effectively ignore the optionality of the storage with _.flatten.
    domains.traverseM[StoreOpF, Domain.Namespace] { dc =>
      namespaces.traverse[StoreOpF, Option[Domain.Namespace]] { n =>
        StoreOp.getNamespace(dc.name, n.name)
      }.map(_.flatten)
    }
  }

  def err(
    u: UnitDef,
    namespace: Domain.Namespace,
    dcu: DCUnit): NelsonError = CyclicDependency(
    s"Dependency cycle detected for unit '${u.name}' in namespace '${namespace.name.asString}' in domain '${namespace.domain}': ${dcu.name}@${dcu.version}"
  )

  def dependants(g: ReverseRoutingGraph)
    (d: Domain.Deployment): List[DCUnit] =
    g.reachable(RoutingNode(d)).map(_.deployment).collect {
      case Some(d) => d.unit
    }.toList

  def validateUnitDeps(
    namespace: Domain.Namespace,
    reachabilityGraph: ReverseRoutingGraph)
    (u: UnitDef): StoreOpF[Valid[Unit]] = {

    for {
    // Get all nodes whose dependants could potentially be routed to this unit.
      shiftables <- StoreOp.listShiftableDeployments(u, namespace.id)

      // Get all potential dependants.
      reachables = shiftables.flatMap(dependants(reachabilityGraph)).toSet.toList

      // Check whether unit's declared deps create a cycle.
      result = collectViolations(u, reachables, namespace)
    } yield result
  }

  def collectViolations(
    u: UnitDef,
    reachables: List[DCUnit],
    namespace: Domain.Namespace
  ): Valid[Unit] = {
    u.dependencies.toList.traverse_[Valid] {
      case (dep, depVersion) =>
        reachables
          .filter(isViolation(dep, depVersion))
          .traverse_[Valid] { dcu =>
          Validation.failureNel[NelsonError, Unit](err(u, namespace, dcu))
        }
    }
  }

  def isViolation(
    dep: String,
    depVersion: FeatureVersion)
    (reachable: DCUnit): Boolean = {
    reachable.name == dep && reachable.version.toFeatureVersion == depVersion
  }
}

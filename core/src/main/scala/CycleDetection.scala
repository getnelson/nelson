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

import nelson.Datacenter.DCUnit
import nelson.Manifest.{apply => _, _}
import nelson.storage.{StoreOp, StoreOpF}
import nelson.routing._

import cats.data.{EitherT, NonEmptyList, Validated, ValidatedNel}
import cats.effect.IO
import cats.implicits._
import nelson.CatsHelpers._

object CycleDetection {
  type Valid[A] = ValidatedNel[NelsonError, A]
  type ReverseRoutingGraph = RoutingGraph

  def validateNoCycles(
    m: Manifest,
    cfg: NelsonConfig): EitherT[IO, NonEmptyList[NelsonError], Unit] = {
    val op: StoreOpF[Valid[Unit]] = detect(m, cfg)
    EitherT(op.foldMap(cfg.storage).map(_.toEither))
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
      ns <- getNamespaces(cfg.datacenters, m.namespaces)
      ngs <- getNamespaceGraphs(ns)
      result <- ngs.traverse[StoreOpF, Valid[Unit]] {
        case (namespace, reachabilityGraph) =>
          validateUnitsInNamespace(m, namespace, reachabilityGraph)
      }
    } yield result.sequence_
  }

  def validateUnitsInNamespace(
    m: Manifest,
    namespace: Datacenter.Namespace,
    reachabilityGraph: ReverseRoutingGraph
  ): StoreOpF[Valid[Unit]] = {
    m.units.traverse[StoreOpF, Valid[Unit]](
      validateUnitDeps(namespace, reachabilityGraph)
    ).map(_.sequence_)
  }

  def getNamespaceGraphs(ns: List[Datacenter.Namespace])
  : StoreOpF[List[(Datacenter.Namespace, ReverseRoutingGraph)]] = {
    ns.traverse[StoreOpF, (Datacenter.Namespace, ReverseRoutingGraph)](n =>
      RoutingTable.routingGraph(n).map(g => (n, g.reverse)))
  }

  def getNamespaces(
    datacenters: List[Datacenter],
    namespaces: List[Manifest.Namespace]): StoreOpF[List[Datacenter.Namespace]] = {
    // These namespaces have been validated by the validations this validation depends on.
    // We effectively ignore the optionality of the storage with _.flatten.
    datacenters.flatTraverse[StoreOpF, Datacenter.Namespace] { dc =>
      namespaces.traverse[StoreOpF, Option[Datacenter.Namespace]] { n =>
        StoreOp.getNamespace(dc.name, n.name)
      }.map(_.flatten)
    }
  }

  def err(
    u: UnitDef,
    namespace: Datacenter.Namespace,
    dcu: DCUnit): NelsonError = CyclicDependency(
    s"Dependency cycle detected for unit '${u.name}' in namespace '${namespace.name.asString}' in datacenter '${namespace.datacenter}': ${dcu.name}@${dcu.version}"
  )

  def dependants(g: ReverseRoutingGraph)
    (d: Datacenter.Deployment): List[DCUnit] =
    g.reachable(RoutingNode(d)).map(_.deployment).collect {
      case Some(d) => d.unit
    }.toList

  def validateUnitDeps(
    namespace: Datacenter.Namespace,
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
    namespace: Datacenter.Namespace
  ): Valid[Unit] = {
    u.dependencies.toList.traverse_[Valid, Unit] {
      case (dep, depVersion) =>
        reachables
          .filter(isViolation(dep, depVersion))
          .traverse_[Valid, Unit] { dcu => Validated.invalidNel[NelsonError, Unit](err(u, namespace, dcu)) }
    }
  }

  def isViolation(
    dep: String,
    depVersion: FeatureVersion)
    (reachable: DCUnit): Boolean = {
    reachable.name == dep && reachable.version.toFeatureVersion == depVersion
  }
}

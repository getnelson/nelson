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
package routing

import cats.implicits._

object deprecated {

  import nelson.storage._

  /**
   * Given a deployment write out all incomming paths
   * if the deployment is deprecated.
   */
  private def writeDeployment(d: RoutingNode, g: RoutingGraph): StoreOpF[Vector[DependencyEdge]] = {
    d.node.fold(_ => Vector.empty[DependencyEdge].pure[StoreOpF], de =>
      StoreOp.getDeploymentStatus(de.id).map { status =>
        if (status.exists(_ != DeploymentStatus.Deprecated)) Vector()
        else g.predecessors(d).map(x => (x, d))
      }
    )
  }

  /**
   * Traverse the routing graph and write out all deployment paths
   * that terminate on a deprecated service.
   */
  private def traverseGraph(g: RoutingGraph): StoreOpF[Vector[DependencyEdge]] = {
    g.nodes.flatTraverse(d => writeDeployment(d,g))
  }

  /**
   * Given a datacenter, traverse all namepaces and write out all
   * deployment paths that terminate in a deprecated service. Duplicates
   * accross namespaces can happen so make unique at the end.
   */
  def deploymentsWithDeprecatedDependencies(dc: Datacenter): StoreOpF[Vector[DependencyEdge]] =
    for {
      graph   <- RoutingTable.generateRoutingTables(dc.name)
      deps    <- graph.toVector.flatTraverse(rg => traverseGraph(rg._2))
    } yield deps.distinct
}

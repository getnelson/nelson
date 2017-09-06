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

package object routing {
  import storage._
  import scalaz.{==>>, ~>, Monad,Monoid,NonEmptyList,RWST,Traverse,\/, Id}
  import scalaz.Id._
  import scalaz.std.list._
  import scalaz.std.option._
  import scalaz.std.anyVal._
  import scalaz.concurrent.Task
  import quiver.Graph

  import Datacenter._

  /**
   * the quiver graph which we will build that contains the current
   * running deployments for a particular namespace, from this we
   * build both routing tables and discovery tables
   */
  type RoutingGraph = Graph[RoutingNode, Unit, RoutePath]

  type ServiceTarget = (UnitName, MajorVersion)

  /**
   * A table built for a particular namespace, it says "if you are in
   * this namespace, and looking for this service type, here is
   * the current target
   */
  type RoutingTable = ServiceTarget ==>> Target

  /** A routing table for each known namesapce */
  type RoutingTables = NamespaceName ==>> (RoutingTable)

  /**
   * A table used to discover deployments offering a particular named
   * port in a particular namespace
   */
  type DiscoveryTable = NamedService ==>> NonEmptyList[RoutePath]

  /** A Discovery Table for each known namespace */
  type DiscoveryTables = NamespaceName ==>> DiscoveryTable

  // this just gets our monad in the the expected * → * shape
  type GraphBuild[A] = RWST[Id,RoutingTables,List[String],RoutingGraph,A]

  // this is the type we pass to liftM to lift a task into our RWST
  type GraphBuildT[F[_],A] = RWST[F,RoutingTables,List[String],RoutingGraph,A]

  // this is a value which has all of the MonadReader (ask),
  // MonadState (get,put,modify) syntax for our RWST
  val graphBuild = RWST.rwstMonad[Id,RoutingTables,List[String],RoutingGraph]
}

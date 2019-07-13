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

import storage._
import quiver.{LNode,LEdge}
import cats.Order
import cats.implicits._
import scala.collection.immutable.SortedMap
import journal._

object RoutingTable {
  import Datacenter._

  private val log = Logger[RoutingTable.type]

  implicit val NamespaceNameOrder: Order[ServiceTarget] =
    Order.by(_._1)

  implicit val NamespaceNameOrdering: Ordering[ServiceTarget] =
    NamespaceNameOrder.toOrdering

  /*
   * add a single route to the routing table
   */
  private def addTarget(from: RoutingNode)(to: Target): GraphBuild[Unit] = {
    to match {
      case SingletonTarget(d) =>
        graphBuild.modify { rg =>
          val node = RoutingNode(d)
          d.unit.ports.foldLeft(rg)((rg,p) =>
            rg.addEdge(LEdge(from, node, RoutePath(d, p.name, p.protocol,p.port, 100))))
        }

      case x: TrafficShift =>
        val weightFrom = Math.ceil(x.fromValue * 100).toInt
        val weightTo = 100 - weightFrom

        graphBuild.modify { rg =>
          val toNode = RoutingNode(x.to)
          val fromNode = RoutingNode(x.from)

          val rg2 = x.from.unit.ports.foldLeft(rg)((rg, p) =>
            rg.addEdge(LEdge(from, fromNode, RoutePath(x.from, p.name, p.protocol, p.port, weightFrom))))

          x.to.unit.ports.foldLeft(rg2)((rg, p) =>
            rg.addEdge(LEdge(from, toNode, RoutePath(x.to, p.name, p.protocol, p.port, weightTo))))
        }
    }
  }

  private def addLoadbalancerDependency(from: LoadbalancerDeployment)(r: Manifest.Route): GraphBuild[Unit] =
    for {
      rts <- graphBuild.ask
      ns  <- graphBuild.liftF(StoreOp.getNamespaceByID(from.nsid))
      d   <- graphBuild.liftF(rts.get(ns.name).flatMap(_.get((r.destination.name, from.loadbalancer.version))).pure[StoreOpF])
      _   <- d.fold(graphBuild.tell(List(s"missing ${r.destination.name} dependency for ${from.stackName}")))(t => addTarget(RoutingNode(from))(t))
    } yield ()

  private def addDependencies(from: Deployment, sn: ServiceName): GraphBuild[Unit] = {

    def lookup(ns: NamespaceName, rts: RoutingTables): Option[Target] =
      rts.get(ns).flatMap(_.get((sn.serviceType, sn.version.toMajorVersion)))

    // Look for dependency in current namespace. If target doesn't exist
    // move up the namespace ancestery until target is found.
    def resolveDefault(ns: NamespaceName, rts: RoutingTables): Option[Target] =
      lookup(ns, rts) orElse ns.parent.flatMap(p => resolveDefault(p, rts))

    def addDefault(ns: Namespace): GraphBuild[Unit] =
      for {
        rt <- graphBuild.ask
        d  <- graphBuild.liftF(resolveDefault(ns.name, rt).pure[StoreOpF])
        _  <- d.fold(graphBuild.tell(List(s"missing $sn dependency for ${from.stackName}")))(t => addTarget(RoutingNode(from))(t))
      } yield ()

    // Look for all downsteram dependecies.
    def resolveDownstream(ns: Namespace, rts: RoutingTables): List[Target] =
      rts.keys
        .filter(n => ns.name.isSubordinate(n))
        .flatMap(n => lookup(n, rts)).toList

    def addDownstream(ns: Namespace): GraphBuild[Unit] =
      for {
        rt <- graphBuild.ask
        ds <- graphBuild.liftF(resolveDownstream(ns, rt).pure[StoreOpF])
        _  <- ds.traverse(t => addTarget(RoutingNode(from))(t))
      } yield ()

    addDefault(from.namespace) >> addDownstream(from.namespace)
  }

  /*
   * for each node in the graph, add edges representing the possible routes
   */
  private def addEdges: GraphBuild[Unit] = {

    def add(rn: RoutingNode): GraphBuild[Unit] =
      rn.node.fold(
        lb => lb.loadbalancer.routes.traverse_(r => addLoadbalancerDependency(lb)(r)),
        ds => ds.unit.dependencies.toVector.traverse_(sn => addDependencies(ds, sn))
      )

    graphBuild.get.flatMap(_.nodes.traverse_(rn => add(rn)))
  }

  /*
   * prunes the routing graph by a given namespace.
   * keep nodes that are in the given namepsace or
   * has an incoming edge from a node in the given namespace or
   * has and outgoing edge to a node in the give namepsace
   */
  private def prune(gr: RoutingGraph, n: Namespace): RoutingGraph =
    gr.nfilter { d =>
      d.deployment.exists(_.nsid == n.id) ||
      gr.ins(d).exists(_._2.nsid == n.id) ||     // incoming edge, source
      gr.outs(d).exists(_._1.stack.nsid == n.id) // outgoing edge, destination
    }

  // given a namespace, return all upstream and downstream namespaces
  private def upDownNamespaces(ns: Namespace): StoreOpF[Set[Namespace]] =
    StoreOp.listNamespacesForDatacenter(ns.datacenter).map(_.filter(n =>
      ns.name.isSubordinate(n.name) || // downstream
      n.name.isSubordinate(ns.name)    // upstream
    ))

  // Input deployments are assumed to be in the same namespace
  private def generateRoutingTable(ns: Namespace, ds: List[Deployment]): StoreOpF[RoutingTable] = {

    // only deployments that expose ports can be targets
    val st: Set[ServiceTarget] =
      ds.filter(!_.unit.ports.isEmpty)
        .map(x => (x.unit.name, x.unit.version.toMajorVersion)).toSet

    st.foldLeft[StoreOpF[RoutingTable]](SortedMap.empty[ServiceTarget, Target].pure[StoreOpF]) { (s, x) =>
      for {
        m <- s
        t <- StoreOp.getCurrentTargetForServiceName(ns.id, ServiceName(x._1, x._2.minFeatureVersion))
      } yield t.fold(m)(t => m + ((x, t)))
    }
  }

  private def generateRoutingTables(ds: List[Deployment]): StoreOpF[RoutingTables] = {
    ds.groupBy(_.namespace).foldLeft[StoreOpF[RoutingTables]](SortedMap.empty[NamespaceName, RoutingTable].pure[StoreOpF]) { (s, x) =>
      val (ns, d) = x
      for {
        m  <- s
        rt <- generateRoutingTable(ns, d)
      } yield m + ((ns.name, rt))
    }
  }

  private def getDeployments(ns: Set[Namespace]): StoreOpF[List[Deployment]] =
    ns.toList.flatTraverse { n =>
      StoreOp.listDeploymentsForNamespaceByStatus(n.id, DeploymentStatus.routable)
        .map(_.map(_._1))
        .map(_.toList)
    }

  private def getLoadbalancers(ns: Set[Namespace]): StoreOpF[List[LoadbalancerDeployment]] =
    ns.toList.flatTraverse { n =>
      StoreOp.listLoadbalancerDeploymentsForNamespace(n.id).map(_.toList)
    }

  private def generateGraph(ds: List[Deployment], lb: List[LoadbalancerDeployment]): StoreOpF[RoutingGraph] =
    for {
      rts  <- generateRoutingTables(ds)
      nodes = ds.map(RoutingNode(_)) ::: lb.map(RoutingNode(_))
      seed  = nodes.foldLeft[RoutingGraph](quiver.empty)((g,d) => g.addNode(LNode(d, ())))
      gr   <- addEdges.run(rts, seed)
    } yield {
      gr._1.foreach(e => log.error("ERROR calculating dependency graph: "+ e))
      gr._2
    }

  /*
   * produces a routing graph for a single deployment, outgoing edges only
   */
  def outgoingRoutingGraph(d: Deployment): StoreOpF[RoutingGraph] = {

    def dependencies(n: Namespace): StoreOpF[Vector[Deployment]] =
      d.unit.dependencies.toVector.flatTraverse { sn =>
        StoreOp.getCurrentTargetForServiceName(n.id, sn)
          .map(_.fold(Vector.empty[Deployment])(_.deployments))
      }

    for {
      ud <- upDownNamespaces(d.namespace)
      ns  = ud + d.namespace
      ds <- ns.toVector.flatTraverse(n => dependencies(n))
      gr <- generateGraph(d :: ds.toList, Nil)
    } yield gr
  }

  /*
   * produces a graph containing all routable services in the given namespace.
   *
   * note: Some nodes might be in other namespaces because of how dependency
   * resolution works.
   */
  def routingGraph(n: Namespace): StoreOpF[RoutingGraph] =
    for {
      ud <- upDownNamespaces(n)
      ns  = ud + n
      ds <- getDeployments(ns)
      ls <- getLoadbalancers(ns)
      gr <- generateGraph(ds, ls)
    } yield prune(gr, n)

  def generateRoutingTables(dc: String): StoreOpF[List[(Namespace, RoutingGraph)]] =
    for {
      ns  <- StoreOp.listNamespacesForDatacenter(dc)
      ds  <- getDeployments(ns)
      ls  <- getLoadbalancers(ns)
      gr  <- generateGraph(ds, ls)
    } yield ns.toList.map(n => (n, prune(gr, n)))
}


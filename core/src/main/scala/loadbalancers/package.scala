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

import Manifest._
import Datacenter.{StackName,LoadbalancerDeployment,Namespace}
import scalaz._,Scalaz._
import helm.ConsulOp
import storage.{StoreOp,StoreOpF}
import routing._


package object loadbalancers {
  import LoadbalancerOp._

  def loadbalancerConfigs(graph: RoutingGraph): Vector[(LoadbalancerDeployment, Vector[Inbound])] = {

    def findPort(rs: Vector[Route], d: RoutePath, mv: MajorVersion): Option[Port] =
      rs.find(r =>
        r.destination.name == d.stack.unit.name &&
        r.destination.portReference == d.portName &&
        mv == d.stack.unit.version.toMajorVersion
      ).map(_.port)

    graph.nodes.flatMap(_.loadbalancer).map { lb =>
      val routes: Vector[Inbound] = graph.outs(RoutingNode(lb)).flatMap { case (d, n) =>
        findPort(lb.loadbalancer.routes, d, lb.loadbalancer.version).map(p => Inbound(d.stack.stackName, d.portName, p.port))
      }
      (lb, routes)
    }
  }

  def loadbalancerKey(name: String): String =
    s"nelson/loadbalancers/v1/${name}"

  def writeLoadbalancerConfigToConsul(lb: LoadbalancerDeployment)(routes: Vector[Inbound]): ConsulOp.ConsulOpF[Unit] = {
    import Json._
    ConsulOp.setJson(loadbalancerKey(lb.stackName.toString), routes)
  }

  def deleteLoadbalancerConfigFromConsul(lb: LoadbalancerDeployment): ConsulOp.ConsulOpF[Unit] =
    ConsulOp.delete(loadbalancerKey(lb.stackName.toString))

  def launch(lb: Manifest.Loadbalancer, v: MajorVersion, dc: Datacenter, ns: NamespaceName, pl: Plan, hash: String): LoadbalancerF[String] =
    LoadbalancerOp.launch(lb, v, dc, ns, pl, hash)

  def delete(lb: Datacenter.LoadbalancerDeployment, dc: Datacenter, ns: Datacenter.Namespace): LoadbalancerF[Unit] =
    LoadbalancerOp.delete(lb, dc, ns)

  def resize(lb: Datacenter.LoadbalancerDeployment, p: Manifest.Plan): LoadbalancerF[Unit] =
    LoadbalancerOp.resize(lb, p)

  def run[F[_]:Monad,A](interpreter: LoadbalancerOp ~> F , op: LoadbalancerF[A]): F[A] =
    Free.runFC(op)(interpreter)

  object Json {
    import argonaut._, Argonaut._

    implicit lazy val RouteDestination: EncodeJson[Inbound] =
      EncodeJson { a: Inbound =>
        ("frontend_name" := s"${a.label}-${a.stackName.toString}") ->:
        ("frontend_port" := a.port) ->:
        ("backend_stack" := a.stackName.toString) ->:
        ("port_label"    := a.label) ->:
        jEmptyObject
      }
  }
}

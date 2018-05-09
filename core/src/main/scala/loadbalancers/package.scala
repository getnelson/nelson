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
import Datacenter.{StackName,LoadbalancerDeployment}
import helm.ConsulOp
import routing._

package object loadbalancers {
  import LoadbalancerOp._

  def loadbalancerKeyV1(name: String): String =
    s"nelson/loadbalancers/v1/${name}"

  def loadbalancerKeyV2(name: String): String =
    s"nelson/loadbalancers/v2/${name}"

  def writeLoadbalancerV1ConfigToConsul(sn: StackName, ins: Vector[Inbound]): ConsulOp.ConsulOpF[Unit] = {
    import Json.V1InboundEncode
    ConsulOp.kvSetJson(loadbalancerKeyV1(sn.toString), ins)
  }

  def writeLoadbalancerV2ConfigToConsul(sn: StackName, routes: Vector[Route]): ConsulOp.ConsulOpF[Unit] = {
    import Json.V2RouteEncode
    val mv = sn.version.toMajorVersion
    ConsulOp.kvSetJson(loadbalancerKeyV2(sn.toString), routes.map((mv, _)))
  }

  def deleteLoadbalancerConfigFromConsul(lb: LoadbalancerDeployment): ConsulOp.ConsulOpF[Unit] =
    for {
      _ <- ConsulOp.kvDelete(loadbalancerKeyV1(lb.stackName.toString))
      _ <- ConsulOp.kvDelete(loadbalancerKeyV2(lb.stackName.toString))
    } yield ()

  def loadbalancerV1Configs(graph: RoutingGraph): Vector[(StackName, Vector[Inbound])] = {

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
      (lb.stackName, routes)
    }
  }

  def launch(lb: Manifest.Loadbalancer, v: MajorVersion, dc: Datacenter, ns: NamespaceName, pl: Plan, hash: String): LoadbalancerF[String] =
    LoadbalancerOp.launch(lb, v, dc, ns, pl, hash)

  def delete(lb: Datacenter.LoadbalancerDeployment, dc: Datacenter, ns: Datacenter.Namespace): LoadbalancerF[Unit] =
    LoadbalancerOp.delete(lb, dc, ns)

  def resize(lb: Datacenter.LoadbalancerDeployment, p: Manifest.Plan): LoadbalancerF[Unit] =
    LoadbalancerOp.resize(lb, p)

  object Json {
    import argonaut._, Argonaut._

    implicit lazy val V1InboundEncode: EncodeJson[Inbound] =
      EncodeJson { a: Inbound =>
        ("frontend_name" := s"${a.label}-${a.stackName.toString}") ->:
        ("frontend_port" := a.port) ->:
        ("backend_stack" := a.stackName.toString) ->:
        ("port_label"    := a.label) ->:
        jEmptyObject
      }

    implicit lazy val V2RouteEncode: EncodeJson[(MajorVersion, Route)] =
      EncodeJson { case (mv, r) =>
        ("frontend_port" := r.port.port) ->:
        ("port_label"    := r.destination.portReference) ->:
        ("service_name"  := r.destination.name) ->:
        ("major_version" := mv.major) ->:
        jEmptyObject
      }
  }
}

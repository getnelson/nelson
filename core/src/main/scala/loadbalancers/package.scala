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

  def loadbalancerKey(name: String): String =
    s"nelson/loadbalancers/v1/${name}"

  def writeLoadbalancerConfigToConsul(sn: StackName, routes: Vector[Route]): ConsulOp.ConsulOpF[Unit] = {
    import Json._
    val mv = sn.version.toMajorVersion
    ConsulOp.setJson(loadbalancerKey(sn.toString), routes.map((mv, _)))
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

    implicit lazy val RouteEncode: EncodeJson[(MajorVersion, Route)] =
      EncodeJson { case (mv, r) =>
        ("frontend_port" := r.port.port) ->:
        ("port_label"    := r.destination.portReference) ->:
        ("service_name"  := r.destination.name) ->:
        ("major_version" := mv.major) ->:
        jEmptyObject
      }
  }
}

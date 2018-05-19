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
package loadbalancers

import cats.free.Free

sealed abstract class LoadbalancerOp[A] extends Product with Serializable

object LoadbalancerOp {

  final case class LaunchLoadbalancer(lb: Manifest.Loadbalancer, version: MajorVersion, dc: Datacenter, ns: NamespaceName, pl: Manifest.Plan, hash: String) extends LoadbalancerOp[String]

  final case class DeleteLoadbalancer(lb: Datacenter.LoadbalancerDeployment, dc: Datacenter, ns: Datacenter.Namespace) extends LoadbalancerOp[Unit]

  final case class ResizeLoadbalancer(lb: Datacenter.LoadbalancerDeployment, p: Manifest.Plan) extends LoadbalancerOp[Unit]

  type LoadbalancerF[A] = Free[LoadbalancerOp,A]

  def launch(lb: Manifest.Loadbalancer, v: MajorVersion, dc: Datacenter, ns: NamespaceName, pl: Manifest.Plan, hash: String): LoadbalancerF[String] =
    Free.liftF(LaunchLoadbalancer(lb, v, dc, ns, pl, hash))

  def delete(lb: Datacenter.LoadbalancerDeployment, dc: Datacenter, ns: Datacenter.Namespace): LoadbalancerF[Unit] =
    Free.liftF(DeleteLoadbalancer(lb, dc, ns))

  def resize(lb: Datacenter.LoadbalancerDeployment, p: Manifest.Plan): LoadbalancerF[Unit] =
    Free.liftF(ResizeLoadbalancer(lb, p))
}

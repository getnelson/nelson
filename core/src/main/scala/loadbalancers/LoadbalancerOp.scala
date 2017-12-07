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


import scalaz.Free

sealed abstract class LoadbalancerOp[A] extends Product with Serializable

object LoadbalancerOp {

  final case class LaunchLoadbalancer(lb: Manifest.Loadbalancer, version: MajorVersion, dc: Domain, ns: NamespaceName, pl: Manifest.Plan, hash: String) extends LoadbalancerOp[String]

  final case class DeleteLoadbalancer(lb: Domain.LoadbalancerDeployment, dc: Domain, ns: Domain.Namespace) extends LoadbalancerOp[Unit]

  final case class ResizeLoadbalancer(lb: Domain.LoadbalancerDeployment, p: Manifest.Plan) extends LoadbalancerOp[Unit]

  type LoadbalancerF[A] = Free.FreeC[LoadbalancerOp,A]

  def launch(lb: Manifest.Loadbalancer, v: MajorVersion, dc: Domain, ns: NamespaceName, pl: Manifest.Plan, hash: String): LoadbalancerF[String] =
    Free.liftFC(LaunchLoadbalancer(lb, v, dc, ns, pl, hash))

  def delete(lb: Domain.LoadbalancerDeployment, dc: Domain, ns: Domain.Namespace): LoadbalancerF[Unit] =
    Free.liftFC(DeleteLoadbalancer(lb, dc, ns))

  def resize(lb: Domain.LoadbalancerDeployment, p: Manifest.Plan): LoadbalancerF[Unit] =
    Free.liftFC(ResizeLoadbalancer(lb, p))
}

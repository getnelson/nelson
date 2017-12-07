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

import Domain.{Deployment,LoadbalancerDeployment,StackName}
import scalaz.{\/,Order}
import scalaz.syntax.monoid._
import java.time.Instant

final case class RoutingNode(node: LoadbalancerDeployment \/ Deployment) {
  def stackName: StackName = node.fold(_.stackName, _.stackName)
  def deployment: Option[Deployment] = node.toOption
  def loadbalancer: Option[LoadbalancerDeployment] = node.swap.toOption
  def nsid: ID = node.fold(_.nsid, _.nsid)
}

object RoutingNode {
  def apply(d: Deployment): RoutingNode = new RoutingNode(\/.right(d))

  def apply(lb: LoadbalancerDeployment): RoutingNode = new RoutingNode(\/.left(lb))

  implicit def routingNodeOrder: Order[RoutingNode] =
    (Order[Version].contramap[RoutingNode](_.node.fold(_.loadbalancer.version.minVersion, _.unit.version)) |+|
     Order[Instant].contramap[RoutingNode](_.node.fold(_.deployTime, _.deployTime)))
}

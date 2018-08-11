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

import Datacenter._
import cats.Order
import cats.instances.string._

/**
 * A path to a particular port on a particular deployment, which is
 * weighted, so that we split traffic between two deployments both
 * offering the same named port on a named service.
 *
 * Weights are stored as a percentage of traffic, so 100 means all
 * traffic, 50 means half of the traffic.
 */
final case class RoutePath(stack: Deployment, portName: String, protocol: String, port: Int, weight: Int)

object RoutePath {
  implicit val rpOrder: Order[RoutePath] = Order.by(_.stack.toString)
}


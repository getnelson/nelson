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

import cats.Order
import cats.implicits._

/**
 * Services potentially offer multiple exposed ports, each must be
 * named, for instance, a service might expose both an "admin" and
 * "default" and "funnel" ports. This is a class to capture a
 * serviceType with the port name. It is only used internally to the
 * generation of routing tables and discovery tables.
 */
private[nelson] final case class NamedService(serviceType: UnitName, name: String)

object NamedService {
  implicit val namedServiceOrder: Order[NamedService] =
    Order.by(ns => (ns.serviceType, ns.name))

  implicit val namedServiceOrdering: Ordering[NamedService] =
    namedServiceOrder.toOrdering
}


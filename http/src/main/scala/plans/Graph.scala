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
package plans

import org.http4s._
import org.http4s.dsl._
import _root_.argonaut._, Argonaut._
import scalaz._, Scalaz._

final case class Graph(config: NelsonConfig) extends Default {
  import nelson.Json._
  import routing.RoutingGraph

  def getRoutingGraph(ns: Datacenter.Namespace): storage.StoreOpF[Option[RoutingGraph]] =
     routing.RoutingTable.routingGraph(ns).map(x => Option(x))

  val service: HttpService = HttpService {
    case GET -> Root / "v1" / "datacenters" / datacenter / namespace / "graph" =>
      type CoyoStoreOp[A] = Coyoneda[storage.StoreOp, A]
      type FreeCoyoStoreOp[A] = Free[CoyoStoreOp, A]
      implicit val stgMonad: Monad[FreeCoyoStoreOp] = Free.freeMonad[CoyoStoreOp]
      storage.run(config.storage, (for {
        name <- OptionT(NamespaceName.fromString(namespace).toOption.point[FreeCoyoStoreOp])
        ns  <- OptionT(storage.StoreOp.getNamespace(datacenter, name))
        gr  <- OptionT(getRoutingGraph(ns))
         graph = DependencyGraph(gr)
       } yield graph.svg).run).attempt.flatMap {
        case -\/(e) => BadRequest(e.getMessage)
        case \/-(None) => BadRequest("no such namespace found")
        case \/-(Some(t)) => Ok(t).withContentType(Some(MediaType.`image/svg+xml`))
      }

    case GET -> Root / "v1" / "deprecated" / "graph" =>
      json(Nelson.listDeploymentsWithDeprecatedDependencies)
  }
}

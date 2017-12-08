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

final case class Loadbalancers(config: NelsonConfig) extends Default {
  import Domain.{Namespace, LoadbalancerDeployment}
  import Loadbalancers._
  import Params._

  private implicit val RouteEncoder: EncodeJson[Manifest.Route] =
    EncodeJson((r: Manifest.Route) =>
      ("lb_port" := r.port.port) ->:
      ("backend_name" := r.destination.name) ->:
      ("backend_port_reference":= r.destination.portReference) ->:
      jEmptyObject
    )

  private implicit val LoadbalancerEncoder: EncodeJson[LoadbalancerDeployment] =
    EncodeJson((lb: LoadbalancerDeployment) =>
      ("name" := lb.stackName.toString) ->:
      ("guid" := lb.guid) ->:
      ("deploy_time" := lb.deployTime.toEpochMilli.asJson) ->:
      ("routes" := lb.loadbalancer.routes) ->:
      ("address" := lb.address) ->:
      ("major_version" := lb.loadbalancer.version.major) ->:
      jEmptyObject
    )

  private implicit val DomainNamespaceLoadbalancerEncoder: EncodeJson[(DomainRef, Namespace, LoadbalancerDeployment)] =
    EncodeJson { case ((d: DomainRef, n: Namespace, lb: LoadbalancerDeployment)) =>
      (("domain" := d) ->: ("namespace" := n.name.asString) ->: jEmptyObject).deepmerge(lb.asJson)
    }

  import nelson.Json._
  private implicit val LoadbalancerSummaryEncoder: EncodeJson[Nelson.LoadbalancerSummary] =
    EncodeJson((ls: Nelson.LoadbalancerSummary) =>
      (("namespace"    := ls.namespace.name.asString) ->:
       ("domain"    := ls.namespace.domain) ->:
        ("dependencies" :=
          ("outbound"  := ls.outboundDependencies) ->:
          jEmptyObject
      ) ->: jEmptyObject).deepmerge(ls.loadbalancer.asJson)
    )

  val service: HttpService = HttpService {

    /*
     * POST /v1/loadbalancers
     *
     * {
     *   "name": "howdy-lb",
     *   "major_version": 1,
     *   "domain": "texas",
     *   "namespace": "dev"
     * }
     */
    case req @ POST -> Root / "v1" / "loadbalancers" & IsAuthenticated(session) =>
      decode[LoadbalancerLaunch](req) { lb =>
        json(Nelson.commitLoadbalancer(lb.name, lb.version, lb.domain, lb.namespace))
      }

    /*
     * DELETE /v1/loadbalancers/guid
     *
     * Deletes the loadbalancer for the given guid
     */
    case DELETE -> Root / "v1" / "loadbalancers" / guid & IsAuthenticated(session) =>
      json(Nelson.deleteLoadbalancerDeployment(guid))

    /*
     * GET /v1/loadbalaners/guid
     *
     * Returns the loadbalancer deployment for given guid
     */
    case req @ GET -> Root / "v1" / "loadbalancers" / guid & IsAuthenticated(session) =>
      json(Nelson.fetchLoadbalancerDeployment(guid))

    /*
     * GET /v1/loadbalancers?dc=texas&ns=dev,prod
     *
     * List all the loadbalancer deployments given a list of domains and namespaces.
     * ns is required
     * dc is optional and if empty will query all domains
     */
    case req @ GET -> Root / "v1" / "loadbalancers" :? Ns(ns) +& Dc(dc) & IsAuthenticated(session) =>
      val namespace = commaSeparatedStringToNamespace(ns)
      val domains = dc.map(commaSeparatedStringToList).getOrElse(Nil)
      namespace.toNel.toRightDisjunction("This endpoint requires a non-empty 'ns' parameter.")
        .fold(
          e => BadRequest(e),
          ns => ns.sequenceU.fold(
            e => BadRequest(e.getMessage),
            n => json(Nelson.listLoadbalancers(domains, n)))
        )

  }
}

object Loadbalancers {
  import _root_.argonaut._, Argonaut._

  final case class LoadbalancerLaunch(name: String, version: Int, domain: String, namespace: NamespaceName)

  implicit val LoadbalancerLaunchCodecJson: DecodeJson[LoadbalancerLaunch] =
    DecodeJson(c => for {
      a <- (c --\ "name").as[String]
      b <- (c --\ "major_version").as[Int]
      d <- (c --\ "domain").as[String]
      n <- (c --\ "namespace").as[String]
      nn <- NamespaceName.fromString(n).toOption.map(DecodeResult.ok)
              .getOrElse(DecodeResult.fail(s"unable to parse $n into a namespace", c.history))
    } yield LoadbalancerLaunch(a,b,d,nn))
}

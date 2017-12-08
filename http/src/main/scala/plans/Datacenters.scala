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
import org.http4s.argonaut._
import _root_.argonaut._, Argonaut._
import scalaz.{Applicative, \/}
import scalaz.Scalaz._
import java.time.Instant

final case class Domains(config: NelsonConfig) extends Default {
  import nelson.Json._
  import Domain._
  import Params._

  private implicit val StackSummaryEncoder: EncodeJson[Nelson.StackSummary] =
    EncodeJson { case (s: Nelson.StackSummary) =>
      (("expiration"   := s.expiration) ->:
       ("statuses"     := s.statuses) ->:
       ("namespace"    := s.namespace.name.asString) ->:
       ("dependencies" :=
          ("inbound"   := s.inboundDependencies) ->:
          ("outbound"  := s.outboundDependencies) ->:
          jEmptyObject
       ) ->: jEmptyObject
      ).deepmerge(s.deployment.asJson)
    }

  private implicit val NamespaceRefServiceNameEncoder: EncodeJson[(DomainRef, Namespace, GUID, ServiceName)] =
    EncodeJson { case (d: DomainRef, n: Namespace, i: GUID, s: ServiceName) =>
      (("domain" := d) ->:
       ("namespace" := n.name.asString) ->:
       ("guid" := i) ->:
       jEmptyObject).deepmerge(s.asJson)
    }

  private implicit val NamespaceDeploymentEncoder: EncodeJson[(DomainRef, Namespace, Deployment)] =
    EncodeJson { case ((d: DomainRef, n: Namespace, s: Deployment)) =>
      (("domain" := d) ->:
       ("namespace" := n.name.asString) ->:
       jEmptyObject).deepmerge(s.asJson)
    }

  private implicit val NamespaceDeploymentWithStatusEncoder: EncodeJson[(DomainRef, Namespace, Deployment, DeploymentStatus)] =
    EncodeJson { case ((d: DomainRef, n: Namespace, s: Deployment, ds: DeploymentStatus)) =>
      (("domain" := d) ->:
        ("namespace" := n.name.asString) ->:
        ("status" := ds.toString) ->:
        jEmptyObject
      ).deepmerge(s.asJson)
    }

  private implicit val NamespaceEncoder: EncodeJson[Namespace] =
    EncodeJson { (ns: Namespace) =>
      ("id"    := ns.id) ->:
      ("name"  := ns.name.asString) ->:
      jEmptyObject
    }

  private implicit val DomainEncoder: EncodeJson[(Domain, Set[Namespace])] =
    EncodeJson { case (d: Domain, ns: Set[Namespace]) =>
      ("name"           := d.name) ->:
      ("domain_url" := linkTo(s"/v1/domains/${d.name}")(config.network)) ->:
      ("namespaces"     := ns.map(n =>
        ("deployments_url" := linkTo(s"/v1/deployments?dc=${d.name}&ns=${n.name.asString}")(config.network)) ->:
        ("units_url"       := linkTo(s"/v1/units?dc=${d.name}&status=active,manual,deprecated")(config.network)) ->:
        ("statistics_url"  := linkTo(s"/v1/statistics?dc=${d.name}&namespace=${n.name.asString}")(config.network)) ->: n.asJson).toList) ->:
      jEmptyObject
    }

  private implicit val StatusEncoder: EncodeJson[(DeploymentStatus, Option[StatusMessage], Instant)] =
    EncodeJson { case (s: DeploymentStatus, msg: Option[StatusMessage], ts: Instant) =>
      ("status" := s.toString) ->:
      ("message" :=? msg) ->?:
      ("timestamp" := ts.toString) ->:
      jEmptyObject
    }

  private implicit val ManualDeploymentDecoder: DecodeJson[ManualDeployment] =
    casecodec7(ManualDeployment.apply, ManualDeployment.unapply)(
      "domain",
      "namespace",
      "service_type",
      "version",
      "hash",
      "description",
      "port"
    )

  implicit lazy val FeatureVersionCodec: CodecJson[FeatureVersion] =
    CodecJson.casecodec2(FeatureVersion.apply, FeatureVersion.unapply)("major", "minor")

  implicit lazy val ServiceNameCodec: CodecJson[Domain.ServiceName] =
    CodecJson.casecodec2(Domain.ServiceName.apply, Domain.ServiceName.unapply)("service_type", "version")

  implicit val logFileEncoder: EncodeJson[(Int, List[String])] = EncodeJson[(Int,List[String])](
    (r: (Int,List[String])) =>
      ("offset" := r._1) ->:
      ("content" := r._2) ->:
      jEmptyObject
  )

  val service: HttpService = HttpService {

    /*
     * GET /v1/domains
     *
     * List all the domains and their subordinate namespaces
     */
   case GET -> Root / "v1" / "domains" & IsAuthenticated(session) =>
      json(Nelson.listDomains.map(_.toList))

    /*
     * GET /v1/domains/portland
     *
     * Show details for a single domain
     */
   case GET -> Root / "v1" / "domains" / dcname & IsAuthenticated(session) =>
      jsonF(Nelson.fetchDomainByName(dcname)){ option =>
        option match {
          case Some(dc) => Ok(dc.asJson)
          case None     => NotFound(s"domain '$dcname' does not exist")
        }
      }

    /*
     * GET /v1/domains/portland/graph?ns=devel,prod
     *
     * Returns a list of Namespaces with corresponding RoutingGraph within this domain
     */
   case req @ GET -> Root /"v1" / "domains" / dcname / "graph" :? NsO(ns) & IsAuthenticated(_) =>
     ns.map(commaSeparatedStringToNamespace) match {
       case Some(ns) =>
         Applicative[\/[InvalidNamespaceName, ?]].sequence(ns).fold(
           e => BadRequest(e.getMessage),
           n => json(Nelson.getRoutingGraphs(dcname, n)))
       case None =>
         json(Nelson.getRoutingGraphs(dcname, Nil))
     }

    /*
     * GET /v1/deployments?dc=texas,california&status=active,deploying&ns=devel
     *
     * List all the deployments given a list of domains and namespaces. Filter by deployment status
     * ns is required
     * dc is optional and if empty will query all domains
     * status is optional and if empty will filter by all DeploymentStatus
     */
   case req @ GET -> Root / "v1" / "deployments" :? Ns(ns) +& Status(s) +& Dc(dc) +& U(u) & IsAuthenticated(session) =>
      val namespace = commaSeparatedStringToNamespace(ns)
      val domains = dc.map(commaSeparatedStringToList).getOrElse(Nil)
      val statuses = s.flatMap(commaSeparatedStringToStatus(_).toNel).getOrElse(DeploymentStatus.nel)
      val units = u
      namespace.toNel.toRightDisjunction("This endpoint requires a non-empty 'ns' parameter.")
        .fold(
          e => BadRequest(e),
          ns => ns.sequenceU.fold(
            e => BadRequest(e.getMessage),
            n => json(Nelson.listDeployments(domains, n, statuses, units)))
        )

     /* POST /v1/domains/<dc>/namespaces
      *
      * Create namespace(s) (including roots) in the specified domain, must be an admin
      */
    case req @ POST -> Root / "v1" / "domains" / dcname / "namespaces" & IsAuthenticated(session) if IsAuthorized(session) =>
       decode[NamespaceNameJson](req){ ns =>
         json(Nelson.recursiveCreateNamespace(dcname.trim.toLowerCase, ns.namespace))
       }

    /*
     * POST /v1/domains/<dc>/namespaces
     *
     * Create subordinate namespace(s) in the specified domain.
     */
    case req @ POST -> Root / "v1" / "domains" / dcname / "namespaces" & IsAuthenticated(session) =>
      decode[NamespaceNameJson](req){ ns =>
        if (ns.namespace.isRoot) BadRequest("creating root namespace is not allowed")
        else json(Nelson.recursiveCreateSubordinateNamespace(dcname.trim.toLowerCase, ns.namespace))
      }


    /*
     * POST /v1/deployments
     *
     * Upon posting, if sucsessful will redirect you to the new deployment
     */
    case req @ POST -> Root / "v1" / "deployments" & IsAuthenticated(session) if IsAuthorized(session) =>
      decode[ManualDeployment](req){ md =>
        jsonF(Nelson.createManualDeployment(session,md)){ guid =>
          Uri.fromString(linkTo(s"/v1/deployments/$guid")(config.network).toString).fold(
            e => InternalServerError(s"Bad redirect: ${e.details}"),
            Found.apply
          )
        }
      }

    /*
     * GET /v1/deployments/1a2dfg34
     *
     * Returns a summary of everything we know about this deployment
     */
    case GET -> Root / "v1" / "deployments" / guid & IsAuthenticated(session) =>
      jsonF(Nelson.fetchDeployment(guid)){
         _ match {
          case Some(summary) => Ok(summary.asJson)
          case None          => NotFound(s"the requested deployment, '${guid}', could not be found.")
        }
      }

    /*
     * GET /v1/deployments/1a2dfg34/runtime
     *
     * Returns a summary of everything we know about this deployment runtime
     */
    case GET -> Root / "v1" / "deployments" / guid / "runtime" & IsAuthenticated(session) =>
      jsonF(Nelson.getRuntimeSummary(guid)){
         _ match {
          case Some(summary) => Ok(summary.asJson)
          case None          => NotFound(s"the requested deployment, '${guid}', could not be found.")
        }
      }

    /*
     * GET /v1/deployments/<guid>/log
     *
     * Returns the log of all that Nelson did for a given deployment
     */
    case GET -> Root / "v1" / "deployments" / guid / "log" :? Offset(o) & IsAuthenticated(_) =>
      jsonF(Nelson.fetchWorkflowLog(guid, o.getOrElse(0))){
        _ match {
          case Some(tuple) => Ok(tuple.asJson)
          case None        => NotFound(s"the requested deployment, '${guid}', could not be found.")
        }
      }

    /*
     * POST /v1/deployments/<guid>/redeploy
     *
     * Triggers a redeployment of the specified deployment GUID
     */
    case req @ POST -> Root / "v1" / "deployments" / guid / "redeploy" & IsAuthenticated(_) =>
      json(Nelson.redeploy(guid))

    /*
     * POST /v1/deployments/<guid>/trafficshift/reverse
     *
     * Triggers a reverse of an in progress traffic shift given the guid of the to deployment
     */
    case req @ POST -> Root / "v1" / "deployments" / guid / "trafficshift" / "reverse" & IsAuthenticated(_) =>
      json(Nelson.reverseTrafficShift(guid))

    /*
     * GET /v1/units?dc=texas,california&status=active,deploying&ns=devel
     *
     * List all the units given a list of domains and namespaces. Filter by deployment status
     * ns is required
     * dc is optional and if empty will query all domains
     * status is optional and if empty will filter by all DeploymentStatus
     */
    case req @ GET -> Root / "v1" / "units" :? Ns(ns) +& Status(s) +& Dc(dc) & IsAuthenticated(session) =>
      val namespace = commaSeparatedStringToNamespace(ns)
      val domains = dc.map(commaSeparatedStringToList).getOrElse(Nil)
      val statuses = s.flatMap(commaSeparatedStringToStatus(_).toNel).getOrElse(DeploymentStatus.nel)
      namespace.toNel.toRightDisjunction("This endpoint requires a non-empty 'ns' parameter.")
        .fold(
          e => BadRequest(e),
          ns => ns.sequenceU.fold(
            e => BadRequest(e.getMessage),
            n => json(Nelson.listUnitsByStatus(domains, n, statuses)))
        )

    /*
     * POST /v1/units/deprecate
     *
     * Deprecates all of the deployments given a service and feature version
     * accross all domains and namespaces
     */
    case req @ POST -> Root / "v1" / "units" / "deprecate" & IsAuthenticated(session) =>
      decode[Domain.ServiceName](req) { service =>
        json(Nelson.deprecateService(service))
      }

    /*
     * POST /v1/units/expire
     *
     * Expires all of the deployments given a service and feature version
     * accross all domains and namespaces.
     * Note this does not guarurtee a deployment will be cleaned up as the
     * expiration policy for the deployment will still run.
     */
    case req @ POST -> Root / "v1" / "units" / "expire" & IsAuthenticated(session) =>
      decode[Domain.ServiceName](req) { service =>
        json(Nelson.expireService(service))
      }

    /*
     * POST /v1/units/commit
     *
     * {
     *   "unit": "unit-name",
     *   "version": "1.2.40",
     *   "target": "prod"
     * }
     *
     * commits a unit / version to the specified namespace target
     */
    case req @ POST -> Root / "v1" / "units" / "commit" & IsAuthenticated(session) =>
      decode[Nelson.CommitUnit](req) { commit =>
        json(Nelson.commit(commit.unitName, commit.version, commit.target))
      }
  }
}

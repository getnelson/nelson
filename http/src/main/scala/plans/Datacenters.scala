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

import _root_.argonaut._, Argonaut._
import cats.effect.IO
import cats.implicits._
import org.http4s._
import org.http4s.dsl.io._
import org.http4s.argonaut._
import org.http4s.headers.Location

import java.time.Instant

final case class Datacenters(config: NelsonConfig) extends Default {
  import nelson.Json._
  import Datacenter._
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

  private implicit val NamespaceRefServiceNameEncoder: EncodeJson[(DatacenterRef, Namespace, GUID, ServiceName)] =
    EncodeJson { case (d: DatacenterRef, n: Namespace, i: GUID, s: ServiceName) =>
      (("datacenter" := d) ->:
       ("namespace" := n.name.asString) ->:
       ("guid" := i) ->:
       jEmptyObject).deepmerge(s.asJson)
    }

  private implicit val NamespaceDeploymentWithStatusEncoder: EncodeJson[(DatacenterRef, Namespace, Deployment, DeploymentStatus)] =
    EncodeJson { case ((d: DatacenterRef, n: Namespace, s: Deployment, ds: DeploymentStatus)) =>
      (("datacenter" := d) ->:
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

  private implicit val DatacenterEncoder: EncodeJson[(Datacenter, Set[Namespace])] =
    EncodeJson { case (d: Datacenter, ns: Set[Namespace]) =>
      ("name"           := d.name) ->:
      ("datacenter_url" := linkTo(s"/v1/datacenters/${d.name}")(config.network)) ->:
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

  implicit lazy val FeatureVersionCodec: CodecJson[FeatureVersion] =
    CodecJson.casecodec2(FeatureVersion.apply, FeatureVersion.unapply)("major", "minor")

  implicit lazy val ServiceNameCodec: CodecJson[Datacenter.ServiceName] =
    CodecJson.casecodec2(Datacenter.ServiceName.apply, Datacenter.ServiceName.unapply)("service_type", "version")

  implicit val logFileEncoder: EncodeJson[(Int, List[String])] = EncodeJson[(Int,List[String])](
    (r: (Int,List[String])) =>
      ("offset" := r._1) ->:
      ("content" := r._2) ->:
      jEmptyObject
  )

  val service: HttpService[IO] = HttpService[IO] {

    /*
     * GET /v1/datacenters
     *
     * List all the datacenters and their subordinate namespaces
     */
   case GET -> Root / "v1" / "datacenters" & IsAuthenticated(_) =>
      json(Nelson.listDatacenters(config.pools.defaultExecutor).map(_.toList))

    /*
     * GET /v1/datacenters/portland
     *
     * Show details for a single datacenter
     */
   case GET -> Root / "v1" / "datacenters" / dcname & IsAuthenticated(_) =>
      jsonF(Nelson.fetchDatacenterByName(dcname)){ option =>
        option match {
          case Some(dc) => Ok(dc.asJson)
          case None     => NotFound(s"datacenter '$dcname' does not exist")
        }
      }

    /*
     * GET /v1/datacenters/portland/graph?ns=devel,prod
     *
     * Returns a list of Namespaces with corresponding RoutingGraph within this datacenter
     */
   case GET -> Root /"v1" / "datacenters" / dcname / "graph" :? NsO(ns) & IsAuthenticated(_) =>
     ns.map(commaSeparatedStringToNamespace) match {
       case Some(ns) =>
         ns.sequence.fold(
           e => BadRequest(e.getMessage),
           n => json(Nelson.getRoutingGraphs(dcname, n)))
       case None =>
         json(Nelson.getRoutingGraphs(dcname, Nil))
     }

    /*
     * GET /v1/deployments?dc=texas,california&status=active,deploying&ns=devel
     *
     * List all the deployments given a list of datacenters and namespaces. Filter by deployment status
     * ns is required
     * dc is optional and if empty will query all datacenters
     * status is optional and if empty will filter by all DeploymentStatus
     */
   case GET -> Root / "v1" / "deployments" :? Ns(ns) +& Status(s) +& Dc(dc) +& U(u) & IsAuthenticated(_) =>
      val namespace = commaSeparatedStringToNamespace(ns)
      val datacenters = dc.map(commaSeparatedStringToList).getOrElse(Nil)
      val statuses = s.flatMap(commaSeparatedStringToStatus(_).toNel).getOrElse(DeploymentStatus.nel)
      val units = u
      namespace.toNel.toRight("This endpoint requires a non-empty 'ns' parameter.")
        .fold(
          e => BadRequest(e),
          ns => ns.sequence.fold(
            e => BadRequest(e.getMessage),
            n => json(Nelson.listDeployments(datacenters, n, statuses, units)))
        )

     /* POST /v1/datacenters/<dc>/namespaces
      *
      * Create namespace(s) (including roots) in the specified datacenter, must be an admin
      */
    case req @ POST -> Root / "v1" / "datacenters" / dcname / "namespaces" & IsAuthenticated(session) if IsAuthorized(session) =>
       decode[NamespaceNameJson](req){ ns =>
         json(Nelson.recursiveCreateNamespace(dcname.trim.toLowerCase, ns.namespace))
       }

    /*
     * POST /v1/datacenters/<dc>/namespaces
     *
     * Create subordinate namespace(s) in the specified datacenter.
     */
    case req @ POST -> Root / "v1" / "datacenters" / dcname / "namespaces" & IsAuthenticated(_) =>
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
            s => Found(Location(s))
          )
        }
      }

    /*
     * GET /v1/deployments/1a2dfg34
     *
     * Returns a summary of everything we know about this deployment
     */
    case GET -> Root / "v1" / "deployments" / guid & IsAuthenticated(_) =>
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
    case GET -> Root / "v1" / "deployments" / guid / "runtime" & IsAuthenticated(_) =>
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
    case POST -> Root / "v1" / "deployments" / guid / "redeploy" & IsAuthenticated(_) =>
      json(Nelson.redeploy(guid))

    /*
     * POST /v1/deployments/<guid>/trafficshift/reverse
     *
     * Triggers a reverse of an in progress traffic shift given the guid of the to deployment
     */
    case POST -> Root / "v1" / "deployments" / guid / "trafficshift" / "reverse" & IsAuthenticated(_) =>
      json(Nelson.reverseTrafficShift(guid))

    /*
     * GET /v1/units?dc=texas,california&status=active,deploying&ns=devel
     *
     * List all the units given a list of datacenters and namespaces. Filter by deployment status
     * ns is required
     * dc is optional and if empty will query all datacenters
     * status is optional and if empty will filter by all DeploymentStatus
     */
    case GET -> Root / "v1" / "units" :? Ns(ns) +& Status(s) +& Dc(dc) & IsAuthenticated(_) =>
      val namespace = commaSeparatedStringToNamespace(ns)
      val datacenters = dc.map(commaSeparatedStringToList).getOrElse(Nil)
      val statuses = s.flatMap(commaSeparatedStringToStatus(_).toNel).getOrElse(DeploymentStatus.nel)
      namespace.toNel.toRight("This endpoint requires a non-empty 'ns' parameter.")
        .fold(
          e => BadRequest(e),
          ns => ns.sequence.fold(
            e => BadRequest(e.getMessage),
            n => json(Nelson.listUnitsByStatus(datacenters, n, statuses)))
        )

    /*
     * POST /v1/units/deprecate
     *
     * Deprecates all of the deployments given a service and feature version
     * accross all datacenters and namespaces
     */
    case req @ POST -> Root / "v1" / "units" / "deprecate" & IsAuthenticated(_) =>
      decode[Datacenter.ServiceName](req) { service =>
        json(Nelson.deprecateService(service))
      }

    /*
     * POST /v1/units/expire
     *
     * Expires all of the deployments given a service and feature version
     * accross all datacenters and namespaces.
     * Note this does not guarurtee a deployment will be cleaned up as the
     * expiration policy for the deployment will still run.
     */
    case req @ POST -> Root / "v1" / "units" / "expire" & IsAuthenticated(_) =>
      decode[Datacenter.ServiceName](req) { service =>
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
    case req @ POST -> Root / "v1" / "units" / "commit" & IsAuthenticated(_) =>
      decode[Nelson.CommitUnit](req) { commit =>
        json(Nelson.commit(commit.unitName, commit.version, commit.target))
      }
  }
}

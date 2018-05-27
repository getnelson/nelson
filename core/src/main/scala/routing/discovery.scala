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


import helm.ConsulOp
import cats.Foldable
import cats.data.NonEmptyList
import cats.implicits._
import scala.collection.immutable.SortedMap
import journal._

object Discovery {

  val log: Logger = Logger[Discovery.type]

  val discoveryKeyPrefix = "lighthouse/discovery/v1/"
  val DiscoveryKeyPattern = s"""$discoveryKeyPrefix(.*)""".r

  import Datacenter._
  import NamespaceName._
  import argonaut._
  import Argonaut._
  import NamedService._

  final case class DeploymentDiscovery(defaultNamespace: NamespaceName,
                                 domain: String,
                                 namespaces: DiscoveryTables)

  implicit val ddCodec: EncodeJson[DeploymentDiscovery] =
    EncodeJson(dd =>
      ("defaultNamespace" := dd.defaultNamespace.asString) ->:
      ("domain" := dd.domain) ->:
      ("namespaces" := dd.namespaces) ->:
        jEmptyObject

    )

  implicit val encodeRT: EncodeJson[DiscoveryTables] =
    EncodeJson(rt =>
      rt.foldLeft(jEmptyArray) { case (a, (k, v)) =>
        val routes = v.foldLeft(jEmptyArray) { case (a, (k, v)) =>
          val r = ("service" := k.serviceType) ->: ("targets" := v.toList) ->: ("port" := v.head.j.portName)->: jEmptyObject
          r -->>: a
        }
        val ns = ("name" := k.asString) ->: ("routes" := routes) ->: jEmptyObject

        ns -->>: a
      }
    )

  implicit val versionEncode: EncodeJson[Version] = implicitly[EncodeJson[String]].contramap[Version](_.toString)
  implicit val versionDecode: DecodeJson[Version] =
    DecodeJson.optionDecoder(_.string.flatMap(Version.fromString), "Version")

  implicit val stackNameCodec: CodecJson[StackName] =
    CodecJson.casecodec3(StackName.apply, StackName.unapply)("serviceType", "version", "hash")

  implicit val rpEncode: EncodeJson[RoutePath] = EncodeJson[RoutePath] { rp =>
    ("stack" := rp.stack.stackName.toString)  ->:
    ("port" := rp.port)                       ->:
    ("protocol" := rp.protocol)               ->:
    ("weight" := rp.weight)                   ->: jEmptyObject
  }

  def discoveryTables[F[_]: Foldable](graphs: F[(Namespace, RoutingGraph)]): SortedMap[(StackName,NamespaceName), DiscoveryTables] = {
    graphs.foldLeft[SortedMap[(StackName,NamespaceName), DiscoveryTables]](SortedMap.empty){(smap,g) =>
      val (ns, rg) = g
      rg.nodes.filter(_.nsid == ns.id).foldLeft(smap){(s,rn) =>
        s + (((rn.stackName, ns.name), discoveryTable(rn, rg)))
      }
    }
  }

  def discoveryTable(rn: RoutingNode, rg: RoutingGraph): DiscoveryTables = {
    val context = rg.decomp(rn).ctx.yolo(s"discoveryTables: no ctx after decomposing ${rn.stackName}")
    context.outEdges.foldLeft[DiscoveryTables](SortedMap.empty)((m,e) =>
      e.to.deployment.fold[DiscoveryTables](SortedMap.empty){ to =>
        val path = e.label
        val service = NamedService(to.unit.serviceName.serviceType, path.portName)
        m |+| SortedMap(to.namespace.name -> SortedMap(service -> NonEmptyList.of(path)))
      }
    )
  }

  def writeDiscoveryInfoToConsul(ns: NamespaceName, sn: StackName, domain: String, dt: DiscoveryTables): ConsulOp.ConsulOpF[Unit] =
    ConsulOp.kvSetJson(consulDiscoveryKey(sn), DeploymentDiscovery(ns, domain, dt))

  def listDiscoveryKeys: ConsulOp.ConsulOpF[Set[String]] = ConsulOp.kvListKeys(discoveryKeyPrefix)

  def stackNameFrom(discoveryKey: String): Option[String] = discoveryKey match {
    case DiscoveryKeyPattern(s) if StackName.parsePublic(s).isDefined => Some(s)
    case _ => None
  }

  def consulDiscoveryKey(sn: StackName): String =  discoveryKeyPrefix + sn.toString
}

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

import ca.mrvisser.sealerate
import cats.data.NonEmptyList
import cats.syntax.list._

sealed abstract class DeploymentStatus extends Product with Serializable

object DeploymentStatus {
  import argonaut.DecodeJson

  def fromString(str: String): DeploymentStatus =
    stringToDeploymentStatus.get(str.toLowerCase.trim).getOrElse(Unknown)

  case object Pending extends DeploymentStatus {
    override def toString = "pending"
  }

  case object Deploying extends DeploymentStatus {
    override def toString = "deploying"
  }

  case object Warming extends DeploymentStatus {
    override def toString = "warming"
  }

  case object Ready extends DeploymentStatus {
    override def toString = "ready"
  }

  case object Garbage extends DeploymentStatus {
    override def toString = "garbage"
  }

  case object Failed extends DeploymentStatus {
    override def toString = "failed"
  }

  case object Unknown extends DeploymentStatus {
    override def toString = "unknown"
  }

  case object Deprecated extends DeploymentStatus {
    override def toString = "deprecated"
  }

  case object Terminated extends DeploymentStatus {
    override def toString = "terminated"
  }

  val all: Set[DeploymentStatus] = sealerate.values[DeploymentStatus]

  val nel: NonEmptyList[DeploymentStatus] = all.toList.toNel.yolo("there should be at least one DeploymentStatus")

  // Deployments with a routable status are included in the routing graph.
  // Ready is the common case and inidcates that a deployment is ready to receive traffic.
  // Deprecated deployments are included in routing graph until all upstreams have upgraded.
  val routable = NonEmptyList.of(Ready,Deprecated)

  private val stringToDeploymentStatus: Map[String, DeploymentStatus] =
    all.map(x => x.toString -> x).toMap

  implicit val deploymentStatusDecoder: DecodeJson[DeploymentStatus] =
    DecodeJson.StringDecodeJson.map(fromString)
}

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

import java.net.URI
import java.time.Instant

/**
 * Meant to represent a released version of a unit. These are fixed
 * in time, and immutable after creation.
 */
final case class Released(
  slug: Slug,
  /* what version number does this release have, e.g. 1.2.45 */
  version: Version,
  /* when was this release created */
  timestamp: Instant,
  /* reference id from github */
  releaseId: Long,
  /* informational URIs on github, to be used in a UI */
  releaseHtmlUrl: URI
)

final case class ReleasedDeployment(
  id: Long,
  unit: Datacenter.DCUnit,
  /* the name of the namespace this instance was released too */
  namespace: String,
  /* the unique hash that represents this released-deployment */
  hash: String,
  /* the time the deployment commenced */
  timestamp: Instant,
  /* the latest known state of this deployment */
  state: DeploymentStatus,
  /* the guid for the deployment */
  guid: GUID
)

object Released {
  import cats.Order
  import cats.instances.long._

  implicit def releasedOrder: Order[Released] =
    Order.by(_.releaseId)
}

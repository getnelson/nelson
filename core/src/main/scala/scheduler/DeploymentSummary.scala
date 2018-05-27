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
package scheduler

import cats.Monoid
import cats.implicits._

final case class DeploymentSummary(
  running: Option[Int],
  pending: Option[Int],
  completed: Option[Int],
  failed: Option[Int]
)

object DeploymentSummary {
  implicit val deploymentSummaryMonoid: Monoid[DeploymentSummary] =
    new Monoid[DeploymentSummary] {
      def combine(f1: DeploymentSummary, f2: DeploymentSummary): DeploymentSummary =
        DeploymentSummary(
          running =   f1.running   |+| f2.running,
          pending =   f1.pending   |+| f2.pending,
          completed = f1.completed |+| f2.completed,
          failed =    f1.failed    |+| f2.failed
        )

      def empty: DeploymentSummary =
        DeploymentSummary(None, None, None, None)
    }
}

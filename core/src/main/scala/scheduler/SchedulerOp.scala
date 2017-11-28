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

import scalaz.{@@, Free, NonEmptyList}
import docker.Docker.Image
import Manifest.{Plan, UnitDef, Versioned}

sealed abstract class SchedulerOp[A] extends Product with Serializable

object SchedulerOp {

  final case class Delete(dc: Datacenter, d: Datacenter.Deployment) extends SchedulerOp[Unit]

  final case class Launch(i: Image, dc: Datacenter, ns: NamespaceName, a: UnitDef @@ Versioned, p: Plan, hash: String) extends SchedulerOp[String]

  final case class Summary(dc: Datacenter, sn: Datacenter.StackName) extends SchedulerOp[Option[DeploymentSummary]]

  final case class RunningUnits(dc: Datacenter, prefix: Option[String]) extends SchedulerOp[Set[RunningUnit]]

  final case class Allocations(dc: Datacenter, prefix: Option[String]) extends SchedulerOp[List[TaskGroupAllocation]]

  final case class EquivalentStatus(nelson: DeploymentStatus, reverseChrono: NonEmptyList[Set[TaskStatus]]) extends SchedulerOp[Boolean]

  type SchedulerF[A] = Free.FreeC[SchedulerOp, A]

  def launch(i: Image, dc: Datacenter, ns: NamespaceName, a: UnitDef @@ Versioned, p: Plan, hash: String): SchedulerF[String] =
    Free.liftFC(Launch(i, dc, ns, a, p, hash))

  def delete(dc: Datacenter, d: Datacenter.Deployment): SchedulerF[Unit] =
    Free.liftFC(Delete(dc,d))

  def summary(dc: Datacenter, sn: Datacenter.StackName): SchedulerF[Option[DeploymentSummary]] =
    Free.liftFC(Summary(dc,sn))

  def runningUnits(dc: Datacenter, prefix: Option[String] = None): SchedulerF[Set[RunningUnit]] =
    Free.liftFC(RunningUnits(dc, prefix))

  def equivalentStatus(nelson: DeploymentStatus, reverseChrono: NonEmptyList[Set[TaskStatus]]): SchedulerF[Boolean] =
    Free.liftFC(EquivalentStatus(nelson, reverseChrono))

  def allocations(dc: Datacenter, prefix: Option[String] = None): SchedulerF[List[TaskGroupAllocation]] =
    Free.liftFC(Allocations(dc, prefix))
}


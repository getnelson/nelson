package nelson
package scheduler

import nelson.Datacenter.{Deployment, StackName}
import nelson.Manifest._
import nelson.docker.Docker.Image
import nelson.scheduler.SchedulerOp._

import cats.~>
import cats.effect.IO

final class KubernetesHttpV2(client: KubernetesClient) extends (SchedulerOp ~> IO) {
  def apply[A](fa: SchedulerOp[A]): IO[A] = fa match {
    case Launch(image, dc, ns, unit, plan, hash) =>
      launch(image, dc, ns, Versioned.unwrap(unit), unit.version, plan, hash)
    case Delete(dc, deployment) => ???
    case Summary(dc, ns, stackName) => ???
  }

  def launch(image: Image, dc: Datacenter, ns: NamespaceName, unit: UnitDef, version: Version, plan: Plan, hash: String): IO[String] =
    ???

  def delete(dc: Datacenter, deployment: Deployment): IO[Unit] = ???

  def summary(dc: Datacenter, ns: NamespaceName, stackName: StackName): IO[Option[DeploymentSummary]] = ???
}

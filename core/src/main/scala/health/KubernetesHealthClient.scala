package nelson
package health

import nelson.Datacenter.StackName
import nelson.KubernetesJson.DeploymentStatus
import nelson.health.HealthCheckOp.Health

import cats.effect.IO

import scalaz.~>

final case class KubernetesHealthClient(client: KubernetesClient) extends (HealthCheckOp ~> IO) {
  def apply[A](fa: HealthCheckOp[A]): IO[A] = fa match {
    case Health(dc, ns, sn) =>
      val rootNs = ns.root.asString
      val selectors = Map(("stackName", sn.toString), ("nelson", "true"))
      client.listPods(rootNs, selectors)
  }
}

package nelson
package health

import nelson.health.HealthCheckOp.Health

import cats.~>
import cats.effect.IO

final case class KubernetesHealthClient(client: KubernetesClient) extends (HealthCheckOp ~> IO) {
  def apply[A](fa: HealthCheckOp[A]): IO[A] = fa match {
    case Health(dc, ns, sn) =>
      val rootNs = ns.root.asString
      val selectors = Map(("stackName", sn.toString), ("nelson", "true"))
      client.listPods(rootNs, selectors)
  }
}

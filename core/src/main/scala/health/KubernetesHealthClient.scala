package nelson
package health

import scalaz.~>
import scalaz.concurrent.Task

import nelson.health.HealthCheckOp.Health

final case class KubernetesHealthClient(client: KubernetesClient) extends (HealthCheckOp ~> Task) {
  def apply[A](fa: HealthCheckOp[A]): Task[A] = fa match {
    case Health(dc, ns, sn) =>
      val rootNs = ns.root.asString
      val selectors = Map(("stackName", sn.toString), ("nelson", "true"))
      client.listPods(rootNs, selectors)
  }
}

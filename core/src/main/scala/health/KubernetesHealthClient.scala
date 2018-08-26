package nelson
package health

import cats.~>
import cats.effect.IO

import scala.concurrent.duration.Duration

final class KubernetesHealthClient(kubectl: Kubectl, timeout: Duration) extends (HealthCheckOp ~> IO) {
  def apply[A](fa: HealthCheckOp[A]): IO[A] = ???
}

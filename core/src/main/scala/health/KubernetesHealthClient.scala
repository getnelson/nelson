package nelson
package health

import nelson.health.HealthCheckOp._

import nelson.CatsHelpers._
import cats.~>
import cats.effect.IO

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.ExecutionContext

final class KubernetesHealthClient(
  kubectl: Kubectl,
  timeout: FiniteDuration,
  executionContext: ExecutionContext
) extends (HealthCheckOp ~> IO) {
  private implicit val kubernetesShellExecutionContext = executionContext

  def apply[A](fa: HealthCheckOp[A]): IO[A] = fa match {
    case Health(_, ns, stackName) => kubectl.getPods(ns, stackName).timed(timeout)
  }
}

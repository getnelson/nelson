package nelson
package health

import nelson.health.HealthCheckOp._

import nelson.CatsHelpers._
import cats.~>
import cats.effect.IO

import java.util.concurrent.ScheduledExecutorService

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.ExecutionContext

final class KubernetesHealthClient(
  kubectl: Kubectl,
  timeout: FiniteDuration,
  executionContext: ExecutionContext,
  scheduledES: ScheduledExecutorService
) extends (HealthCheckOp ~> IO) {
  private implicit val kubernetesShellExecutionContext = executionContext
  private implicit val kubernetesShellScheduledES = scheduledES

  def apply[A](fa: HealthCheckOp[A]): IO[A] = fa match {
    case Health(dc, ns, stackName) => kubectl.getPods(ns, stackName).timed(timeout)
  }
}

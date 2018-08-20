package nelson
package scheduler

import cats.effect.IO

object Kubectl {
  def apply(payload: String): IO[String] =
    IO { "kubectl apply .. " }

  def delete(payload: String): IO[String] =
    IO { "kubectl delete .. " }
}

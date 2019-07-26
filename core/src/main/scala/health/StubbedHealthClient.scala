package nelson
package health

import nelson.health.HealthCheckOp._
import cats.~>
import cats.effect.IO

final object StubbedHealthClient extends (HealthCheckOp ~> IO) {
  def apply[A](fa: HealthCheckOp[A]): IO[A] = fa match {
    case Health(_, _, _) => IO.pure(Nil)
  }
}

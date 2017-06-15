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

import scalaz.{-\/, ==>>, Coproduct, Coyoneda, Free, Inject, Order, \/-, ~>}
import Free.FreeC


/* liberally borrowed from lighthouse, hat tip to cody and stew */
object ScalazHelpers {
  import Workflow._

  /**
   * Lift a `FreeC[F]` into a `FreeC` of a `Coproduct` of effects (where `G` is
   * the kind of the `Coproduct` of effects), using an implicit `Inject`
   * instance as evidence of how to lift `F` into the coproduct.
   *
   * This is useful when you have some `FreeC`s of one effect (say `ConsulOp`)
   * and some `FreeC`s of another effect (say `WorkflowOp`) and you want to combine
   * them into a `FreeC` of one effect type or the other.
   */
  def injectFC[F[_], G[_]](implicit I: Inject[F, G]) =
    λ[FreeC[F, ?] ~> FreeC[G, ?]](_ mapSuspension λ[Coyoneda[F, ?] ~> Coyoneda[G, ?]](_.trans(I)))

  /**
  * Turn an `F ~> H` and a `G ~> H` into a natural transformation that takes
  * either an `F` or a `G` and turns it into an `H`.
  */
  private def natTransOr[F[_], G[_], H[_]](f: F ~> H, g: G ~> H): Coproduct[F, G, ?] ~> H =
    new (Coproduct[F, G, ?] ~> H) {
      def apply[A](ca: Coproduct[F, G, A]): H[A] = ca.run match {
        case -\/(fa) => f(fa)
        case \/-(ga) => g(ga)
      }
    }

  final implicit class NatTransOps[F[_], H[_]](val fh: F ~> H) extends AnyVal {
    def or[G[_]](gh: G ~> H): Coproduct[F, G, ?] ~> H =
      natTransOr(fh, gh)
  }

  final implicit class InjectWorkflowF[F[_],A](val fa: Free.FreeC[F,A])(implicit I: Inject[F, WorkflowOp]) {
    def inject[G[_]] = injectFC.apply(fa)
  }

  final implicit class ListOps[A](val la: List[A]) extends AnyVal {
    // The Scalaz analogue of List.groupBy(), except we are generating maps of sets (as opposed to maps of lists).
    def zgroup[K](f: A => K)(implicit ev: Order[K]) : K ==>> Set[A] =
      la.foldLeft(==>>.empty[K, Set[A]]) { case (m, a) =>
        m.insertWithKey((k: K, i: Set[A], e: Set[A]) => i ++ e, f(a), Set(a))
      }
  }
}

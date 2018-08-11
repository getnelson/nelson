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
package test

import cats.{~>, Monad, MonadError}
import cats.free.{Coyoneda, Free}
import cats.implicits._

/** An ad-hoc, informally-specified, bug-ridden, slow implementation of half of smock.
  * https://github.com/djspiewak/smock
  */
sealed trait Interpreter[F[_], M[_], +S] {
  import Interpreter._

  def map[S2](f: S => S2): Interpreter[F, M, S2] = flatMap { s =>
    Return(f(s))
  }

  def flatMap[S2](f: S => Interpreter[F, M, S2]): Interpreter[F, M, S2] =
    this match {
      case Expect(pattern, fail) =>
        val pattern2 = new Forall[({
            type λ[α] = PartialFunction[F[α], (Interpreter[F, M, S2], M[α])]
          })#λ] {
          def apply[A]: PartialFunction[F[A], (Interpreter[F, M, S2], M[A])] = {
            pattern.apply[A] andThen {
              case (it, ma) => (it flatMap f, ma)
            }
          }
        }

        val fail2 = fail flatMap f

        Expect(pattern2, fail2)

      case Bind(ma, extend) => Bind(ma, extend andThen { _ flatMap f })
      case Return(s) => f(s)
      case Fail(str) => Fail(str)
    }

  // elimination for Fail at depth
  def orElse[S2 >: S](that: Interpreter[F, M, S2]): Interpreter[F, M, S2] =
    this match {
      case Expect(pattern, fail) =>
        Expect(pattern.asInstanceOf[Forall[({
              type λ[α] = PartialFunction[F[α], (Interpreter[F, M, S2], M[α])]
            })#λ]], fail orElse that) // note that we don't eliminate within pattern; thus, when we consume from the Free, we move "past" our error handler
      case Bind(ma, extend) => Bind(ma, extend andThen { _ orElse that })
      case Return(s) => Return(s)
      case Fail(_) => that
    }

  // eff1 -> eff2 -> (eff3 -> eff5, eff4) -> eff6
  // expect(eff1) -> expect(eff2) -> (expect(eff3) -> expect(eff5), expect(eff4), expect(eff7)) -> expect(eff6)

  def run[A](fc: Free[F, A])(implicit M: Monad[M], C: MonadError[M, Throwable], T: TestFramework): M[A] = {

    // we need to cache these here to save off the call stack
    val earlyTermination =
      T.failed("unexpected early termination (no actions found)", 2)

    val suspensionTermination = T.failed("unexpected suspension: <unknown>", 2)

    def loop(
      it: Interpreter[F, M, S],
      fc: Free[Coyoneda[F, ?], A],
      lastSuspension: Option[Any]
    ): M[A] = it match {
      case Expect(patternF, fail) =>
        fc.resume match {
          case Left(cy) =>
            val pattern = patternF.apply[cy.Pivot]

            if (pattern isDefinedAt cy.fi) {
              val (it2, ma) = pattern(cy.fi)

              ma map cy.k flatMap { loop(it2, _, Some(cy.fi)) }
            } else {
              loop(fail, fc, lastSuspension) // restart with the failure continuation
            }

          case Right(_) =>
            val t2 = lastSuspension map { a =>
              T.withMessage(
                earlyTermination,
                s"unexpected early termination; last valid suspension: $a")
            } getOrElse earlyTermination

            C.raiseError(t2)
        }

      case Bind(ma, extend) =>
        ma map extend flatMap { loop(_, fc, lastSuspension) }

      case Return(_) =>
        fc.resume match {
          case Left(cy) =>
            val t2 = T.withMessage(
              suspensionTermination,
              s"unexpected suspension: ${cy.fi}")

            C.raiseError(t2)

          case Right(r) => M.pure(r)
        }

      case Fail(t) => C.raiseError(t)
    }

    loop(this, fc.mapK(liftCoyoneda), None)
  }
}

object Interpreter {
  /** Inlined from Scalaz's Forall
    * https://github.com/scalaz/scalaz/blob/series/7.3.x/core/src/main/scala/scalaz/Forall.scala
    */
  trait Forall[F[_]] {
    def apply[A]: F[A]
  }

  def liftCoyoneda[F[_]]: F ~> Coyoneda[F, ?] = new (F ~> Coyoneda[F, ?]) {
    def apply[A](fa: F[A]): Coyoneda[F, A] = Coyoneda.lift(fa)
  }

  trait Functions[F[_], M[_]] {
    def point[S](s: S) = Interpreter.point[F, M, S](s)

    def eval[S](ms: M[S]) = Interpreter.eval[F, M, S](ms)

    def expectU[A](pattern: PartialFunction[F[A], M[A]])(implicit T: TestFramework) = {

      expectOr[Unit, A](pattern andThen { r =>
        ((), r)
      }, fail(T.failed("could not match expected pattern", 2)))
    }

    def expect[S, A](pattern: PartialFunction[F[A], (S, M[A])])(implicit T: TestFramework) = {

      expectOr[S, A](
        pattern,
        fail(T.failed("could not match expected pattern", 2)))
    }

    def expectOr[S, A](
        pattern: PartialFunction[F[A], (S, M[A])],
        fail: Interpreter[F, M, S]) =
      Interpreter.expectOr[F, M, S, A](pattern, fail)

    def fail[S](t: Throwable) = Interpreter.fail[F, M, S](t)
  }

  // a way to partially apply the types for slightly nicer inference
  // note that this disrupts stack offsets (everything must be + 1)
  def prepare[F[_], M[_]]: Functions[F, M] = new Functions[F, M] {}

  def point[F[_], M[_], S](s: S): Interpreter[F, M, S] = Return(s)

  def eval[F[_], M[_], S](ms: M[S]): Interpreter[F, M, S] = {
    Bind(ms, { s: S =>
      point[F, M, S](s)
    })
  }

  def expect[F[_], M[_], S, A](pattern: PartialFunction[F[A], (S, M[A])])(implicit T: TestFramework): Interpreter[F, M, S] =
    expectOr(pattern, fail(T.failed("could not match expected pattern", 1))) // probably not the right offset

  def expectOr[F[_], M[_], S, A](
      pattern: PartialFunction[F[A], (S, M[A])],
      fail: Interpreter[F, M, S]): Interpreter[F, M, S] = {

    val lifted =
      liftPF[F, ({ type λ[α] = (Interpreter[F, M, S], M[α]) })#λ, A](
        pattern andThen { case (s, ma) => (point(s), ma) })

    Expect(lifted, fail)
  }

  def fail[F[_], M[_], S](t: Throwable): Interpreter[F, M, S] = Fail(t)

  // TODO this is only really definable where Copointed[F] cannot be defined, which isn't a constraint we can represent
  private def liftPF[F[_], G[_], A](pf: PartialFunction[F[A], G[A]]): Forall[({
      type λ[α] = PartialFunction[F[α], G[α]]
    })#λ] = new Forall[({ type λ[α] = PartialFunction[F[α], G[α]] })#λ] {
    def apply[B]: PartialFunction[F[B], G[B]] =
      new PartialFunction[F[B], G[B]] {
        def isDefinedAt(fb: F[B]): Boolean =
          pf.isDefinedAt(fb.asInstanceOf[F[A]])
        def apply(fb: F[B]): G[B] =
          pf(fb.asInstanceOf[F[A]])
            .asInstanceOf[G[B]] // this is sound because the only case which can hit it is where A = B
      }
  }

  final case class Bind[F[_], M[_], S, A](
      ma: M[A],
      extend: A => Interpreter[F, M, S])
      extends Interpreter[F, M, S]
  final case class Return[F[_], M[_], S](s: S) extends Interpreter[F, M, S]

  final case class Expect[F[_], M[_], S](pattern: Forall[({
      type λ[α] = PartialFunction[F[α], (Interpreter[F, M, S], M[α])]
    })#λ], fail: Interpreter[F, M, S])
      extends Interpreter[F, M, S]
  final case class Fail[F[_], M[_], S](t: Throwable)
      extends Interpreter[F, M, S] // TODO be less insane than String
}

package nelson

import cats.{Monad, StackSafeMonad}
import cats.arrow.FunctionK
import cats.free.Free
import cats.effect.{Effect, IO}
import cats.syntax.apply._

import doobie.imports.Capture

import fs2.{Pipe, Scheduler, Sink, Stream}
import fs2.async

import java.util.concurrent.{ScheduledExecutorService, TimeoutException}

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.FiniteDuration

object CatsHelpers {
  implicit val catsIOScalazInstances: scalaz.Monad[IO] with scalaz.Catchable[IO] with Capture[IO] =
    new scalaz.Monad[IO] with scalaz.Catchable[IO] with Capture[IO] {
      def bind[A, B](fa: IO[A])(f: A => IO[B]): IO[B] = fa.flatMap(f)
      def point[A](a: => A): IO[A] = IO(a)

      def attempt[A](fa: IO[A]): IO[scalaz.\/[Throwable, A]] = fa.attempt.map {
        case Left(a) => scalaz.-\/(a)
        case Right(b) => scalaz.\/-(b)
      }
      def fail[A](err: Throwable): IO[A] = IO.raiseError(err)

      def apply[A](a: => A): IO[A] = IO(a)
    }

  implicit class NelsonEnrichedEither[A, B](val either: Either[A, B]) extends AnyVal {
    def toDisjunction: scalaz.\/[A, B] = either match {
      case Left(a)  => scalaz.-\/(a)
      case Right(b) => scalaz.\/-(b)
    }
  }

  implicit class NelsonEnrichedScalazFunctionK[F[_], G[_]](val functionK: scalaz.~>[F, G]) extends AnyVal {
    def asCats: FunctionK[F, G] = new FunctionK[F, G] {
      def apply[A](fa: F[A]): G[A] = functionK(fa)
    }
  }

  implicit class NelsonEnrichedCatsFunctionK[F[_], G[_]](val functionK: FunctionK[F, G]) extends AnyVal {
    def asScalaz: scalaz.~>[F, G] = new scalaz.~>[F, G] {
      def apply[A](fa: F[A]): G[A] = functionK(fa)
    }
  }

  private implicit def scalazFreeCCatsInstances[F[_]]: Monad[scalaz.Free.FreeC[F, ?]] = new StackSafeMonad[scalaz.Free.FreeC[F, ?]] {
    def flatMap[A, B](fa: scalaz.Free.FreeC[F, A])(f: A => scalaz.Free.FreeC[F, B]): scalaz.Free.FreeC[F, B] = fa.flatMap(f)
    def pure[A](a: A): scalaz.Free.FreeC[F, A] = scalaz.Free.pure(a)
  }

  private def catsToScalazFreeC[F[_]]: FunctionK[F, scalaz.Free.FreeC[F, ?]] = new FunctionK[F, scalaz.Free.FreeC[F, ?]] {
    def apply[A](fa: F[A]): scalaz.Free.FreeC[F, A] = scalaz.Free.liftFC(fa)
  }

  implicit class NelsonEnrichedCatsFree[F[_], A](val free: Free[F, A]) extends AnyVal {
    def asScalaz: scalaz.Free.FreeC[F, A] = free.foldMap(catsToScalazFreeC[F])
  }

  implicit class NelsonEnrichedIO[A](val io: IO[A]) extends AnyVal {
    /** Run `other` if this IO fails */
    def or(other: IO[A]): IO[A] = io.attempt.flatMap {
      case Right(a) => IO.pure(a)
      case Left(e)  => other
    }

    /** Fail with error if the result of the IO does not satsify the predicate
      *
      * Taken from https://github.com/scalaz/scalaz/blob/series/7.3.x/concurrent/src/main/scala/scalaz/concurrent/Task.scala
      */
    def ensure(failure: => Throwable)(f: A => Boolean): IO[A] =
      io.flatMap(a => if (f(a)) IO.pure(a) else IO.raiseError(failure))

    /** NOTE: Unlike scalaz.concurrent.Task#timed this does not attempt to cancel the IO.
      * IO cancellation is pending https://github.com/typelevel/cats-effect/pull/121
      */
    def unsafeTimed(timeout: FiniteDuration)(implicit ec: ExecutionContext, schedulerES: ScheduledExecutorService): IO[A] = {
      val scheduler = Scheduler.fromScheduledExecutorService(schedulerES)
      async.Promise.empty[IO, Either[Throwable, A]].flatMap { p =>
        async.fork(io.attempt.flatMap(p.complete)) *>
        p.timedGet(timeout, scheduler).flatMap {
          case Some(Left(t))  => IO.raiseError(t)
          case Some(Right(a)) => IO.pure(a)
          case None           => IO.raiseError(new TimeoutException())
        }
      }
    }
  }

  private def sinkW[F[_], W, O](actualSink: Sink[F, W]): Sink[F, Either[W, O]] =
    stream => actualSink(stream.collect { case Left(e) => e })

  private def pipeO[F[_], W, O, O2](actualPipe: Pipe[F, O, O2]): Pipe[F, Either[W, O], Either[W, O2]] =
    _.flatMap {
      case Left(a)  => Stream.emit(Left(a))
      case Right(b) => actualPipe(Stream.emit(b)).map(Right(_))
    }

  implicit class NelsonEnrichedWriterStream[F[_], W, O](val stream: Stream[F, Either[W, O]]) {
    def observeW(sink: Sink[F, W])(implicit F: Effect[F], ec: ExecutionContext): Stream[F, Either[W, O]] =
      stream.observe(sinkW(sink))

    def stripW: Stream[F, O] = stream.collect { case Right(o) => o }

    def throughO[O2](pipe: Pipe[F, O, O2]): Stream[F, Either[W, O2]] =
      stream.through(pipeO(pipe))
  }
}

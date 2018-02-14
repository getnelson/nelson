package nelson

import cats.effect.{Effect, IO}

import doobie.imports.Capture

import fs2.{Pipe, Sink, Stream}

import java.util.concurrent.TimeoutException

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.Duration
import scala.util.{Failure, Success}

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

  // Adapted from https://github.com/Verizon/delorean/blob/master/core/src/main/scala/delorean/package.scala
  implicit class NelsonEnrichedFuture[A](future: => Future[A]) {
    def toIO(implicit ec: ExecutionContext): IO[A] =
      IO.async { callback =>
        future.onComplete {
          case Success(a) => callback(Right(a))
          case Failure(e) => callback(Left(e))
        }
      }
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

    def timed(timeout: Duration)(implicit ec: ExecutionContext): IO[A] = for {
      _ <- IO.shift
      o <- IO(io.unsafeRunTimed(timeout))
      r <- o.fold[IO[A]](IO.raiseError(new TimeoutException(s"IO failed to complete within specified timeout of ${timeout.toMillis}ms.")))(IO.pure)
    } yield r
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

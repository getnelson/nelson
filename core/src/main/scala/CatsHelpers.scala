package nelson

import cats.Eval
import cats.effect.{Effect, IO, Timer}
import cats.free.Cofree
import cats.syntax.functor._
import cats.syntax.monadError._

import fs2.{Pipe, Sink, Stream}

import quiver.{Context, Decomp, Graph}

import java.util.concurrent.{ScheduledExecutorService, TimeoutException}

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.FiniteDuration
import scala.collection.immutable.{Stream => SStream}

object CatsHelpers {
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

    def timed(timeout: FiniteDuration)(implicit ec: ExecutionContext, schedulerES: ScheduledExecutorService): IO[A] =
      IO.race(
        Timer[IO].sleep(timeout).as(new TimeoutException(s"Timed out after ${timeout.toMillis} milliseconds"): Throwable),
        io
      ).rethrow
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


  /** This is pending release of https://github.com/Verizon/quiver/pull/31 */
  private type Tree[A] = Cofree[SStream, A]

  private def flattenTree[A](tree: Tree[A]): SStream[A] = {
    def go(tree: Tree[A], xs: SStream[A]): SStream[A] =
      SStream.cons(tree.head, tree.tail.value.foldRight(xs)(go(_, _)))
    go(tree, SStream.Empty)
  }

  private def Node[A](root: A, forest: => SStream[Tree[A]]): Tree[A] =
    Cofree[SStream, A](root, Eval.later(forest))

  implicit class NelsonEnrichedGraph[N, A, B](val graph: Graph[N, A, B]) extends AnyVal {
    def reachable(v: N): Vector[N] =
      xdfWith(Seq(v), _.successors, _.vertex)._1.flatMap(flattenTree)

    def xdfWith[C](vs: Seq[N], d: Context[N, A, B] => Seq[N], f: Context[N, A, B] => C): (Vector[Tree[C]], Graph[N, A, B]) =
      if (vs.isEmpty || graph.isEmpty) (Vector(), graph)
      else graph.decomp(vs.head) match {
        case Decomp(None, g) => g.xdfWith(vs.tail, d, f)
        case Decomp(Some(c), g) =>
          val (xs, _) = g.xdfWith(d(c), d, f)
          val (ys, g3) = g.xdfWith(vs.tail, d, f)
          (Node(f(c), xs.toStream) +: ys, g3)
      }
  }
}

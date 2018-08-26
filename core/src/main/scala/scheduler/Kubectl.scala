package nelson
package scheduler

import cats.effect.IO

import fs2.Stream

import java.io.{ByteArrayInputStream, InputStream}
import java.nio.charset.StandardCharsets.UTF_8

import scala.sys.process.{Process, ProcessLogger}
import scala.collection.mutable.ListBuffer

object Kubectl {
  def apply(payload: String): IO[String] = {
    val input = IO { new ByteArrayInputStream(payload.getBytes(UTF_8)) }
    for {
      result <- exec(List("kubectl", "apply", "-f", "-"), input)
      output <- result.output
    } yield output.mkString("\n")
  }

  def delete(payload: String): IO[String] = {
    val input = IO { new ByteArrayInputStream(payload.getBytes(UTF_8)) }
    for {
      result <- exec(List("kubectl", "delete", "-f", "-"), input)
      output <- result.output
    } yield output.mkString("\n")
  }

  final case class Output(stdout: List[String], stderr: List[String], exitCode: Int) {
    def output: IO[List[String]] =
      if (exitCode == 0) IO.pure(stdout)
      else IO.raiseError(KubectlError(stderr))
  }

  final case class KubectlError(stderr: List[String]) extends Exception(stderr.mkString("\n"))

  private def exec(cmd: List[String], stdin: IO[InputStream]): IO[Output] = {
    // We need the new cats-effect resource safety hotness..
    val pipeline = Stream.bracket(stdin)(is => {
      Stream.eval {
        for {
          stdout <- IO(ListBuffer.empty[String])
          stderr <- IO(ListBuffer.empty[String])
          logger <- IO(ProcessLogger(sout => { stdout += sout; () }, serr => { stderr += serr; () }))
          exitCode <- IO((Process(cmd) #< is).run(logger).exitValue)
        } yield Output(stdout.toList, stderr.toList, exitCode)
      }
    }, is => IO(is.close()))

    // we "know" there is only one Int..
    // this is what we get for using a Stream from resource safety..
    pipeline.compile.last.map(_.yolo(s"Did not get exit code from kubectl - if you see this this is a serious bug and should be reported"))
  }
}

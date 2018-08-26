package nelson

import nelson.Datacenter.StackName
import nelson.Infrastructure.KubernetesMode

import cats.effect.IO

import fs2.Stream

import java.io.{ByteArrayInputStream, InputStream}
import java.nio.charset.StandardCharsets.UTF_8

import scala.sys.process.{Process, ProcessLogger}
import scala.collection.mutable.ListBuffer

final class Kubectl(mode: KubernetesMode) {
  import Kubectl._

  def apply(dc: Datacenter, payload: String): IO[String] = {
    val input = IO { new ByteArrayInputStream(payload.getBytes(UTF_8)) }
    for {
      result <- exec(dc, List("kubectl", "apply", "-f", "-"), input)
      output <- result.output
    } yield output.mkString("\n")
  }

  def delete(dc: Datacenter, payload: String): IO[String] = {
    val input = IO { new ByteArrayInputStream(payload.getBytes(UTF_8)) }
    for {
      result <- exec(dc, List("kubectl", "delete", "-f", "-"), input)
      output <- result.output
    } yield output.mkString("\n")
  }

  def deleteService(dc: Datacenter, namespace: NamespaceName, stackName: StackName): IO[String] = {
    val ns = namespace.root.asString
    for {
      d <- deleteV1(dc, ns, "deployment", stackName.toString).flatMap(_.output)
      s <- deleteV1(dc, ns, "service", stackName.toString).flatMap(_.output)
    } yield (d ++ s).mkString("\n")
  }

  def deleteJob(dc: Datacenter, namespace: NamespaceName, stackName: StackName): IO[String] =
    deleteV1(dc, namespace.root.asString, "job", stackName.toString).flatMap(_.output).map(_.mkString("\n"))

  def deleteCronJob(dc: Datacenter, namespace: NamespaceName, stackName: StackName): IO[String] =
    deleteV1(dc, namespace.root.asString, "cronjob", stackName.toString).flatMap(_.output).map(_.mkString("\n"))

  private def deleteV1(dc: Datacenter, namespace: String, objectType: String, name: String): IO[Output] =
    exec(dc, List("kubectl", "delete", objectType, name, "-n", namespace), IO { new ByteArrayInputStream(Array.empty) })

  private def exec(dc: Datacenter, cmd: List[String], stdin: IO[InputStream]): IO[Output] = {
    // We need the new cats-effect resource safety hotness..
    val pipeline = Stream.bracket(stdin)(is => {
      Stream.eval {
        for {
          stdout <- IO(ListBuffer.empty[String])
          stderr <- IO(ListBuffer.empty[String])
          logger <- IO(ProcessLogger(sout => { stdout += sout; () }, serr => { stderr += serr; () }))
          exitCode <- IO((Process(cmd, None, mode.environmentFor(dc.name): _*) #< is).run(logger).exitValue)
        } yield Output(stdout.toList, stderr.toList, exitCode)
      }
    }, is => IO(is.close()))

    // we "know" there is only one Int..
    // this is what we get for using a Stream for resource safety..
    pipeline.compile.last.map(_.yolo(s"Did not get exit code from kubectl - if you see this this is a serious bug and should be reported"))
  }
}

object Kubectl {
  final case class Output(stdout: List[String], stderr: List[String], exitCode: Int) {
    def output: IO[List[String]] =
      if (exitCode == 0) IO.pure(stdout)
      else IO.raiseError(KubectlError(stderr))
  }

  final case class KubectlError(stderr: List[String]) extends Exception(stderr.mkString("\n"))
}

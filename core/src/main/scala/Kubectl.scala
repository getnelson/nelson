package nelson

import nelson.Datacenter.StackName
import nelson.Infrastructure.KubernetesMode
import nelson.health.{HealthCheck, HealthStatus, Passing, Failing, Unknown}

import argonaut.{CursorHistory, DecodeJson, Parse}

import cats.{Foldable, Monoid}
import cats.effect.IO
import cats.implicits._

import fs2.Stream

import java.io.{ByteArrayInputStream, InputStream}
import java.nio.charset.StandardCharsets.UTF_8

import scala.sys.process.{Process, ProcessLogger}
import scala.collection.mutable.ListBuffer

final class Kubectl(mode: KubernetesMode) {
  import Kubectl._

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

  def deleteService(namespace: NamespaceName, stackName: StackName): IO[String] = {
    val ns = namespace.root.asString
    for {
      d <- deleteV1(ns, "deployment", stackName.toString).flatMap(_.output)
      s <- deleteV1(ns, "service", stackName.toString).flatMap(_.output)
    } yield (d ++ s).mkString("\n")
  }

  def deleteJob(namespace: NamespaceName, stackName: StackName): IO[String] =
    deleteV1(namespace.root.asString, "job", stackName.toString).flatMap(_.output).map(_.mkString("\n"))

  def deleteCronJob(namespace: NamespaceName, stackName: StackName): IO[String] =
    deleteV1(namespace.root.asString, "cronjob", stackName.toString).flatMap(_.output).map(_.mkString("\n"))

  def getPods(namespace: NamespaceName, stackName: StackName): IO[List[HealthStatus]] = {
    implicit val healthStatusDecoder = healthStatusDecodeJson
    exec(List("kubectl", "get", "pods", "-l", s"stackName=${stackName.toString}", "-n", namespace.root.asString, "-o", "json"), emptyStdin)
      .flatMap(_.output)
      .flatMap { stdout =>
        IO.fromEither(for {
          json <- Parse.decodeEither[argonaut.Json](stdout.mkString("\n")).leftMap(kubectlJsonError)
          items <- json.acursor.downField("items").as[List[HealthStatus]].result.leftMap(kubectlCursorError)
        } yield items)
      }
  }

  def getDeployment(namespace: NamespaceName, stackName: StackName): IO[DeploymentStatus] =
    exec(List("kubectl", "get", "deployment", stackName.toString, "-n", namespace.root.asString, "-o", "json"), emptyStdin)
      .flatMap(_.output)
      .flatMap { stdout =>
        IO.fromEither(Parse.decodeEither[DeploymentStatus](stdout.mkString("\n")).leftMap(kubectlJsonError))
      }

  def getCronJob(namespace: NamespaceName, stackName: StackName): IO[JobStatus] =
    exec(List("kubectl", "get", "job", "-l", s"stackName=${stackName.toString}", "-n", namespace.root.asString, "-o", "json"), emptyStdin)
      .flatMap(_.output)
      .flatMap { stdout =>
        IO.fromEither(for {
          json <- Parse.decodeEither[argonaut.Json](stdout.mkString("\n")).leftMap(kubectlJsonError)
          items <- json.acursor.downField("items").as[List[JobStatus]].result.leftMap(kubectlCursorError)
        } yield Foldable[List].fold(items))
      }

  def getJob(namespace: NamespaceName, stackName: StackName): IO[JobStatus] =
    exec(List("kubectl", "get", "job", stackName.toString, "-n", namespace.root.asString, "-o", "json"), emptyStdin)
      .flatMap(_.output)
      .flatMap { stdout =>
        IO.fromEither(Parse.decodeEither[JobStatus](stdout.mkString("\n")).leftMap(kubectlJsonError))
      }

  private def deleteV1(namespace: String, objectType: String, name: String): IO[Output] =
    exec(List("kubectl", "delete", objectType, name, "-n", namespace), emptyStdin)

  private def exec(cmd: List[String], stdin: IO[InputStream]): IO[Output] = {
    // We need the new cats-effect resource safety hotness..
    val pipeline = Stream.bracket(stdin)(is => {
      Stream.eval {
        for {
          stdout <- IO(ListBuffer.empty[String])
          stderr <- IO(ListBuffer.empty[String])
          logger <- IO(ProcessLogger(sout => { stdout += sout; () }, serr => { stderr += serr; () }))
          exitCode <- IO((Process(cmd, None, mode.environment: _*) #< is).run(logger).exitValue)
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

  final case class JobStatus(
    active:    Option[Int],
    failed:    Option[Int],
    succeeded: Option[Int]
  )

  object JobStatus {
    implicit val nelsonKubectlJobStatusDecodeJson: DecodeJson[JobStatus] = DecodeJson { hcursor =>
      val status = hcursor.downField("status")

      for {
        active <- status.downField("active").as[Option[Int]]
        failed <- status.downField("failed").as[Option[Int]]
        succeeded <- status.downField("succeeded").as[Option[Int]]
      } yield JobStatus(active, failed, succeeded)
    }

    implicit val nelsonKubectlJobStatusMonoid: Monoid[JobStatus] = new Monoid[JobStatus] {
      def combine(f1: JobStatus, f2: JobStatus): JobStatus =
        JobStatus(
          active = f1.active |+| f2.active,
          failed = f1.failed |+| f2.failed,
          succeeded = f1.succeeded |+| f2.succeeded
        )

      def empty: JobStatus = JobStatus(None, None, None)
    }
  }

  final case class DeploymentStatus(
    availableReplicas: Option[Int],
    unavailableReplicas: Option[Int]
  )

  object DeploymentStatus {
    implicit val nelsonKubectlDeploymentStatusDecodeJson: DecodeJson[DeploymentStatus] = DecodeJson { hcursor =>
      val status = hcursor.downField("status")

      for {
        available <- status.downField("availableReplicas").as[Option[Int]]
        unavailable <- status.downField("unavailableReplicas").as[Option[Int]]
      } yield DeploymentStatus(available, unavailable)
    }
  }

  val healthStatusDecodeJson: DecodeJson[HealthStatus] = DecodeJson(hcursor => for {
    name <- hcursor.downField("metadata").downField("name").as[String]
    conditions = hcursor.downField("status").downField("conditions").downAt(readyCondition)
    status <- conditions.downField("status").as[String].map(parseReadyCondition)
    details <- conditions.downField("message").as[Option[String]]
    node <- hcursor.downField("spec").downField("nodeName").as[String]
  } yield HealthStatus(name, status, node, details))

  private def kubectlJsonError(parseError: String): KubectlError =
    KubectlError(List(s"Error parsing kubectl JSON output: ${parseError}"))

  private def kubectlCursorError(parseError: (String, CursorHistory)): KubectlError =
    kubectlJsonError(s"Cursor error: ${parseError._1}")

  private val emptyStdin: IO[InputStream] = IO { new ByteArrayInputStream(Array.empty) }

  private def readyCondition(json: argonaut.Json): Boolean =
    json.acursor.downField("type").as[String].map(_ == "Ready").toOption.getOrElse(false)

  private def parseReadyCondition(s: String): HealthCheck = s match {
    case "True"  => Passing
    case "False" => Failing
    case _       => Unknown
  }
}

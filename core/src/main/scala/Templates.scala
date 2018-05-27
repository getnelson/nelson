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

import cats.data.Kleisli
import cats.effect.{Effect, IO}
import cats.syntax.flatMap._
import nelson.CatsHelpers._

import fs2.{Scheduler, Stream}

import java.nio.file.{Files, Path}
import java.util.concurrent.TimeoutException

import journal._

import scala.sys.process.{Process => _, _}
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

import Datacenter.StackName
import Nelson.NelsonK
import Metrics.default.{consulTemplateContainerCleanupFailuresTotal, consulTemplateContainersRunning, consulTemplateRunsDurationSeconds, consulTemplateRunsFailuresTotal}

object Templates {
  import java.util.concurrent.ScheduledExecutorService

  private[this] val logger = Logger[this.type]

  /** The result of a consul-template validation */
  sealed abstract class ConsulTemplateResult(val isValid: Boolean)
      extends Product with Serializable

  /** The consul-template rendered correctly. */
  case object Rendered extends ConsulTemplateResult(true)

  /** The consul-template had an error */
  final case class InvalidTemplate(msg: String) extends ConsulTemplateResult(false)

  /** The consul-template job timed out. It might be okay, but we gave up trying. */
  final case class TemplateTimeout(msg: String) extends ConsulTemplateResult(false)

  /** We couldn't call consul-template */
  final case class ConsulTemplateError(exitCode: Int, msg: String)
      extends RuntimeException(s"consul-template exited with code $exitCode: $msg")

  /**
   * A template that we want to render in Nelson
   *
   * @param unitRef the name of the unit that owns the template
   * @param resources a set of resources the unit depends on
   * @param template the content of the template
   */
  final case class TemplateValidation(
    unitRef: UnitRef,
    resources: Set[String],
    template: String
  )

  /** Validate a template according to a template validation request */
  def validateTemplate(tv: TemplateValidation): NelsonK[ConsulTemplateResult] =
    Kleisli { cfg =>
      val dc = cfg.datacenters.headOption.getOrElse {
        // We should never see this. Nelson doesn't start without a datacenter.
        sys.error("Can't validate a template without a datacenter")
      }

      val vault = dc.interpreters.vault

      val dcName = dc.name
      val dnsRoot = dc.domain.name

      val ns = cfg.defaultNamespace
      val sn = StackName(tv.unitRef, Version(0, 0, 0), s"test${randomAlphaNumeric(8)}")

      val env = Map(
        "NELSON_DATACENTER" -> dcName,
        "NELSON_DNS_ROOT" -> dnsRoot,
        "NELSON_ENV" -> ns.root.asString,
        "NELSON_PLAN" -> "default",
        "NELSON_STACKNAME" -> sn.toString
      )

      val templateConfig = cfg.template

      policies.withPolicy(dc.policy, sn, ns, tv.resources, vault) { token =>
        Stream.bracket(IO {
          // Mounting a single file in Docker is supported, but troublesome.
          // Instead, we're going to create a temp directory for our temp file.
          //
          // Also troublesome is that we can only mount from our home directory
          // on docker-machine, so we can't just use java.io.tmpdir.
          Files.createDirectories(templateConfig.tempDir)
          val dir = Files.createTempDirectory(templateConfig.tempDir, "nelson")
          val file = dir.toFile
          dir.toFile.deleteOnExit()
          dir
        })(
          dir => withTempFile(tv.template, "nelson", ".template", dir) { file =>
            Stream.eval(renderTemplate(cfg.pools.defaultExecutor, cfg.pools.schedulingPool, templateConfig, cfg.dockercfg, file.toPath, token.value, env))
          },
          dir => IO { dir.toFile.delete(); () }
        )
      }.compile.last.map(_.getOrElse(sys.error("Expected a ConsulTemplateResult")))
    }

  @SuppressWarnings(Array("org.brianmckenna.wartremover.warts.IsInstanceOf")) // false wart
  def renderTemplate(
    ec: ExecutionContext,
    scheduler: ScheduledExecutorService,
    templateConfig: TemplateConfig,
    dockerConfig: DockerConfig,
    path: Path,
    vaultToken: String,
    env: Map[String, String] = Map.empty
  ): IO[ConsulTemplateResult] = {
    val containerName = s"consul-template-${randomAlphaNumeric(12)}"
    val dockerCommand = for {
      vaultAddr <- templateConfig.vaultAddress
    } yield {
      "docker" :: "-H" :: dockerConfig.connection ::
      "run" :: "--name" :: containerName ::
      "--rm" :: "-v" :: s"${path.getParent}:/consul-template/templates" ::
      s"--memory=${templateConfig.memoryMegabytes}m" ::
      // Our kernel doesn't support swap limit capabilites. This supresses a warning we can't do anything about.
      s"--memory-swap=-1" ::
      s"--cpu-period=${templateConfig.cpuPeriod}" :: s"--cpu-quota=${templateConfig.cpuQuota}" ::
      "--net=host" ::
      env.map { case (k, v) => s"--env=${k}=${v}" }.toList :::
      templateConfig.consulTemplateImage ::
      "-once" :: "-dry" ::
      s"-vault-addr=${vaultAddr}" ::
      s"-vault-token=${vaultToken}" ::
      s"-template=/consul-template/templates/${path.getFileName}" ::
      Nil
    }

    def run(cmd: List[String]) = timedRun(IO {
      val err = new StringBuilder
      def writeLine(s: String): Unit = { err.append(s).append("\n"); () }
      val pLogger = ProcessLogger(_ => (), writeLine)

      IO {
        consulTemplateContainersRunning.inc()
        val exitCode = cmd.!(pLogger)
        exitCode
      }.timed(templateConfig.timeout)(ec, scheduler).attempt.flatMap {
        // ^ NOTE: This will return when the timeout is up but will not cancel
        // the already running action - that is pending https://github.com/typelevel/cats-effect/pull/121
        case Right(0) =>
          consulTemplateContainersRunning.dec()
          IO.pure(Rendered)
        case Right(14) =>
          consulTemplateContainersRunning.dec()
          IO.pure(InvalidTemplate(err.toString))
        case Right(n: Int) =>
          consulTemplateContainersRunning.dec()
          IO.raiseError(ConsulTemplateError(n, err.toString))
        case Left(e: TimeoutException) =>
          // We gave up.  We need to terminate the container and show the user as far as we got.
          cleanup(scheduler, dockerConfig, containerName).attempt flatMap { _ => IO.pure(TemplateTimeout(err.toString)) }
        case Left(e) =>
          // Something went wrong internally.  We need to clean up the template container.
          cleanup(scheduler, dockerConfig, containerName).attempt flatMap { _ => IO.raiseError(e) }
      }
    }.flatten)

    dockerCommand match {
      case Some(cmd) => run(cmd)
      case None => IO.raiseError(ConsulTemplateError(2, "No vault address detected. Templates are linted against the vault in the first data center."))
    }
  }

  private def cleanup(scheduler: ScheduledExecutorService, dockerConfig: DockerConfig, containerName: String) = {
    val stop = {
      val pLogger = ProcessLogger(_ => (), s => logger.info(s"[stopping docker $containerName]: ${s}"))
      IO {
        logger.info(s"Stopping failed container: container=${containerName}")
        List("docker", "-H", dockerConfig.connection, "rm", "-f", containerName).!(pLogger)
      }.attempt
    }

    (Scheduler.fromScheduledExecutorService(scheduler).awakeEvery[IO](1.second)(Effect[IO], ExecutionContext.fromExecutorService(scheduler))
      >> Stream.eval(stop))
      .take(5)
      .collect { case Right(0) => () } // look for a success
      .take(1) // Only need to succeed once
      .compile
      .last
      .map {
        case Some(_) =>
          consulTemplateContainersRunning.dec()
        case None =>
          consulTemplateContainerCleanupFailuresTotal.inc()
          logger.error(s"Failed to stop container. This probably leaked a thread: container=$containerName")
      }
  }

  @SuppressWarnings(Array("org.brianmckenna.wartremover.warts.IsInstanceOf")) // false wart
  private def timedRun(task: IO[ConsulTemplateResult]): IO[ConsulTemplateResult] =
    IO(System.nanoTime).flatMap { startNanos =>
      task.attempt.flatMap { att =>
        val elapsed = System.nanoTime - startNanos
        consulTemplateRunsDurationSeconds.observe(elapsed / 1.0e9)
        att match {
          case Right(Rendered) =>
            IO.pure(Rendered)
          case Right(it: InvalidTemplate) =>
            consulTemplateRunsFailuresTotal.labels("invalid_template").inc()
            IO.pure(it)
          case Right(it: TemplateTimeout) =>
            consulTemplateRunsFailuresTotal.labels("timeout").inc()
            IO.pure(it)
          case Left(e) =>
            consulTemplateRunsFailuresTotal.labels("error").inc()
            IO.raiseError(e)
        }
      }
    }
}

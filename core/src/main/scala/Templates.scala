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

import java.nio.file.{Files, Path, Paths}
import java.util.concurrent.{ScheduledExecutorService, TimeoutException}
import io.prometheus.client.{Counter, Gauge, Histogram}
import journal._
import scala.sys.process.{Process => _, _}
import scala.concurrent.duration._
import scalaz.{\/-, -\/}
import scalaz.Kleisli
import scalaz.concurrent.{Strategy, Task}
import scalaz.stream.Process
import scalaz.stream.time
import scalaz.syntax.monad._

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
    Kleisli.kleisli { cfg =>
      val dc = cfg.datacenters.headOption.getOrElse {
        // We should never see this. Nelson doesn't start without a datacenter.
        sys.error("Can't validate a template without a datacenter")
      }

      val vault = dc.interpreters.vault

      val dcName = dc.name
      val dnsRoot = dc.domain.name

      val ns = NamespaceName("dev")
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
        Process.bracket(Task.delay {
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
        })(dir => Process.eval_(Task.delay(dir.toFile.delete()))) { dir =>
          withTempFile(tv.template, "nelson", ".template", dir) { file =>
            Process.eval(renderTemplate(cfg.pools.schedulingPool, templateConfig, cfg.dockercfg, file.toPath, token.value, env))
          }
        }
      }.runLastOr(sys.error("Expected a ConsulTemplateResult"))
    }

  @SuppressWarnings(Array("org.brianmckenna.wartremover.warts.IsInstanceOf")) // false wart
  def renderTemplate(
    scheduler: ScheduledExecutorService,
    templateConfig: TemplateConfig,
    dockerConfig: DockerConfig,
    path: Path,
    vaultToken: String,
    env: Map[String, String] = Map.empty
  ): Task[ConsulTemplateResult] = {
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

    def run(cmd: List[String]) = timedRun(Task.delay {
      val err = new StringBuilder
      def writeLine(s: String): Unit = { err.append(s).append("\n"); () }
      val pLogger = ProcessLogger(_ => (), writeLine)

      Task.delay {
        consulTemplateContainersRunning.inc()
        val exitCode = cmd.!(pLogger)
        exitCode
      }.timed(templateConfig.timeout)(scheduler).attempt.flatMap {
        case \/-(0) =>
          consulTemplateContainersRunning.dec()
          Task.now(Rendered)
        case \/-(14) =>
          consulTemplateContainersRunning.dec()
          Task.now(InvalidTemplate(err.toString))
        case \/-(n: Int) =>
          consulTemplateContainersRunning.dec()
          Task.fail(ConsulTemplateError(n, err.toString))
        case -\/(e: TimeoutException) =>
          // We gave up.  We need to terminate the container and show the user as far as we got.
          cleanup(scheduler, dockerConfig, containerName).attempt >> Task.now(TemplateTimeout(err.toString))
        case -\/(e) =>
          // Something went wrong internally.  We need to clean up the template container.
          cleanup(scheduler, dockerConfig, containerName).attempt >> Task.fail(e)
      }
    }.join)

    dockerCommand match {
      case Some(cmd) => run(cmd)
      case None => Task.fail(ConsulTemplateError(2, "No vault address detected. Templates are linted against the vault in the first data center."))
    }
  }

  private def cleanup(scheduler: ScheduledExecutorService, dockerConfig: DockerConfig, containerName: String) = {
    val stop = {
      val pLogger = ProcessLogger(_ => (), s => logger.info(s"[stopping docker $containerName]: ${s}"))
      Task.delay {
        logger.info(s"Stopping failed container: container=${containerName}")
        List("docker", "-H", dockerConfig.connection, "rm", "-f", containerName).!(pLogger)
      }.attempt
    }

    (time.awakeEvery(1.second)(implicitly, scheduler) >> Process.eval(stop))
      .take(5)
      .collect { case \/-(0) => () } // look for a success
      .take(1) // Only need to succeed once
      .runLast
      .map {
        case Some(_) =>
          consulTemplateContainersRunning.dec()
        case None =>
          consulTemplateContainerCleanupFailuresTotal.inc()
          logger.error(s"Failed to stop container. This probably leaked a thread: container=$containerName")
      }
  }

  @SuppressWarnings(Array("org.brianmckenna.wartremover.warts.IsInstanceOf")) // false wart
  private def timedRun(task: Task[ConsulTemplateResult]): Task[ConsulTemplateResult] =
    Task.delay(System.nanoTime).flatMap { startNanos =>
      task.attempt.flatMap { att =>
        val elapsed = System.nanoTime - startNanos
        consulTemplateRunsDurationSeconds.observe(elapsed / 1.0e9)
        att match {
          case \/-(Rendered) =>
            Task.now(Rendered)
          case \/-(it: InvalidTemplate) =>
            consulTemplateRunsFailuresTotal.labels("invalid_template").inc()
            Task.now(it)
          case \/-(it: TemplateTimeout) =>
            consulTemplateRunsFailuresTotal.labels("timeout").inc()
            Task.now(it)
          case -\/(e) =>
            consulTemplateRunsFailuresTotal.labels("error").inc()
            Task.fail(e)
        }
      }
    }
}

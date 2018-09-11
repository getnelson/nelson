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

import nelson.monitoring.{DeploymentMonitor, Stoplight, registerJvmMetrics}
import nelson.http.MonitoringServer
import nelson.storage.{Migrate, Hikari}

import cats.effect.IO

import fs2.Stream

import java.io.File
import java.util.concurrent.{Executors, ThreadFactory}

import journal.Logger

import knobs._

import org.http4s.client.blaze.Http1Client

import scala.concurrent.ExecutionContext

object Main {
  private val log = Logger[this.type]

  def main(args: Array[String]): Unit = {
    val configThreadFactory = new ThreadFactory {
      def newThread(r: Runnable) = {
        val t = Executors.defaultThreadFactory.newThread(r)
        t.setName("nelson-knobs")
        t
      }
    }

    val configThreadPool = ExecutionContext.fromExecutorService(Executors.newSingleThreadExecutor(configThreadFactory))

    val file = new File(args.headOption.getOrElse("/opt/application/conf/nelson.cfg"))

    def readConfig = for {
      defaults  <- knobs.loadImmutable[IO](Required(ClassPathResource("nelson/defaults.cfg")) :: Nil)
      overrides <- knobs.loadImmutable[IO](Optional(FileResource(file)(configThreadPool)) :: Nil)
      config    <- Config.readConfig(defaults ++ overrides, cfg => Http1Client[IO](cfg), Hikari.build _)
    } yield config

    val cfg = readConfig.unsafeRunSync()

    Migrate.migrate(cfg.database).unsafeRunSync()

    log.info(Banner.text)

    if(cfg.datacenters.nonEmpty){
      log.info("bootstrapping the datacenter definitions known by nelson...")
      Nelson.createDatacenters(cfg.datacenters)(cfg).unsafeRunSync()

      log.info(s"bootstrapping the namespaces to ensure the default '${cfg.defaultNamespace}' namespace is present...")
      Nelson.createDefaultNamespaceIfAbsent(cfg.datacenters, cfg.defaultNamespace).run(cfg).unsafeRunSync()

      log.info("setting up the workflow logger on the filesystem...")
      cfg.workflowLogger.setup().unsafeRunSync()

      def runBackgroundJob[A](name: String, p: Stream[IO, A]): Unit = {
        log.info(s"starting the $name processor")
        Stoplight(s"${name}_stoplight")(p).compile.drain.unsafeRunAsync(_.fold(
          e => log.error(s"fatal error in background process: name=${name}", e),
          _ => log.warn(s"background process completed unexpectedly without exception: name=${name}")
        ))
      }

      import cleanup.SweeperDefaults._

      log.info("booting the background processes nelson needs to operate...")
      runBackgroundJob("auditor", cfg.auditor.process(cfg.storage)(cfg.pools.defaultExecutor))
      runBackgroundJob("pipeline_processor", Stream.eval(Pipeline.task(cfg)(Pipeline.sinks.runAction(cfg))))
      runBackgroundJob("workflow_logger", cfg.workflowLogger.process)
      runBackgroundJob("routing_cron", routing.cron.consulRefresh(cfg) to Http4sConsul.consulSink)
      runBackgroundJob("cleanup_pipeline", cleanup.CleanupCron.pipeline(cfg)(cfg.pools.defaultExecutor))
      runBackgroundJob("sweeper", cleanup.Sweeper.process(cfg))
      runBackgroundJob("deployment_monitor", DeploymentMonitor.loop(cfg))

      registerJvmMetrics()
      MonitoringServer(port = cfg.network.monitoringPort).attempt.unsafeRunSync().fold(
        e => {
          log.error(s"fatal error starting monitoring server: '$e'")
          e.printStackTrace
        },
        r => log.info(s"Started monitoring server on ${cfg.network.monitoringPort}")
      )

      log.info("starting the nelson server...")
      Server.start(cfg).unsafeRunSync()
      ()
    } else {
      log.error("zero datacenters defined, which makes nelson useless. Exiting, to avoid you trying to do something pointless.")
      System.exit(1)
    }
  }
}

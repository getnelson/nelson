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

import knobs._
import java.io.File

import journal.Logger

import scalaz.{Optional => _, _}
import Scalaz._
import scalaz.concurrent.Task
import scalaz.stream.Process
import nelson.monitoring.{DeploymentMonitor, Stoplight, registerJvmMetrics}
import nelson.http.MonitoringServer
import nelson.storage.{Migrate, Hikari}
import dispatch.Http

object Main {
  private val log = Logger[this.type]

  def main(args: Array[String]): Unit = {
    val file = new File(args.headOption.getOrElse("/opt/application/conf/nelson.cfg"))

    def readConfig =
      (knobs.loadImmutable(Required(ClassPathResource("nelson/defaults.cfg")) :: Nil) |@|
        knobs.loadImmutable(Optional(FileResource(file)) :: Nil))((a,b) =>
        Config.readConfig(a ++ b, Http, Hikari.build _)
      )

    val cfg = readConfig.run

    Migrate.migrate(cfg.database).run

    log.info(Banner.text)

    if(cfg.domains.nonEmpty){
      log.info("bootstrapping the domain definitions known by nelson...")
      Nelson.createDomains(cfg.domains)(cfg).run

      log.info(s"bootstrapping the namespaces to ensure the default '${cfg.defaultNamespace}' namespace is present...")
      Nelson.createDefaultNamespaceIfAbsent(cfg.domains, cfg.defaultNamespace).run(cfg).run

      log.info("setting up the workflow logger on the filesystem...")
      cfg.workflowLogger.setup().run

      def runBackgroundJob[A](name: String, p: Process[Task, A]): Unit = {
        log.info(s"starting the $name processor")
        Stoplight(s"${name}_stoplight")(p).run.runAsync(_.fold(
          e => log.error(s"fatal error in background process: name=${name}", e),
          _ => log.warn(s"background process completed unexpectedly without exception: name=${name}")
        ))
      }

      import cleanup.SweeperDefaults._

      log.info("booting the background processes nelson needs to operate...")
      runBackgroundJob("auditor", cfg.auditor.process(cfg.storage))
      runBackgroundJob("pipeline_processor", Process.eval(Pipeline.task(cfg)(Pipeline.sinks.runAction(cfg))))
      runBackgroundJob("workflow_logger", cfg.workflowLogger.process)
      runBackgroundJob("routing_cron", routing.cron.consulRefresh(cfg) to Http4sConsul.consulSink)
      runBackgroundJob("cleanup_pipeline", cleanup.CleanupCron.pipeline(cfg))
      runBackgroundJob("sweeper", cleanup.Sweeper.process(cfg))
      runBackgroundJob("deployment_monitor", DeploymentMonitor.loop(cfg))

      registerJvmMetrics()
      MonitoringServer(port = cfg.network.monitoringPort).attemptRun.fold(
        e => {
          log.error(s"fatal error starting monitoring server: '$e'")
          e.printStackTrace
        },
        r => log.info(s"Started monitoring server on ${cfg.network.monitoringPort}")
      )

      log.info("starting the nelson server...")
      Server.start(cfg).run
      ()
    } else {
      log.error("zero domains defined, which makes nelson useless. Exiting, to avoid you trying to do something pointless.")
      System.exit(1)
    }
  }
}

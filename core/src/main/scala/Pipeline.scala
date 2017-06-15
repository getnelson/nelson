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

import journal.Logger
import scalaz.concurrent.Task
import scala.util.control.NonFatal
import scalaz.stream.{Process,Sink,sink}
import scalaz.stream.async.mutable.{Queue}

object Pipeline {
  import Manifest.Action

  val log = Logger[Pipeline.type]

  object sinks {

    /**
     * Actually execute the workflow specified by the actionable on the stream.
     */
    def runAction(cfg: NelsonConfig): Sink[Task, Action] = {
      sink.lift { action =>
        action.run((cfg, action.config)).handleWith {
          case NonFatal(e) =>
            e.printStackTrace
            Task.delay(log.warn(s"unexpected error occoured whilst running the workflow: ${e.getMessage}, cause: ${e.getCause}"))
        }
      }
    }
  } // e/o sinks

  /**
   * Wire the stream of actionable instances to the
   * effect generating sinks and observations.
   */
  def task(config: NelsonConfig)(effects: Sink[Task, Action]): Task[Unit] = {

    /* needed for parallel consumption of the pipeline */
    import scalaz.stream.nondeterminism.njoin

    def par[A](ps: Process[Task, Process[Task, A]]) =
      njoin(maxOpen = config.pipeline.concurrencyLimit, maxQueued = 1)(ps)(config.pools.defaultExecutor)
        .attempt().observeW(config.auditor.errorSink).stripW

    val p: Process[Task, Process[Task, Unit]] =
      config.queue.dequeue.map(a => Process.emit[Action](a).to(effects))

    par(p).run
  }
}

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

import cats.effect.IO
import nelson.CatsHelpers._
import cats.syntax.applicativeError._
import fs2.{Sink, Stream}

import journal.Logger

import scala.util.control.NonFatal

object Pipeline {
  import Manifest.Action

  val log = Logger[Pipeline.type]

  object sinks {

    /**
     * Actually execute the workflow specified by the actionable on the stream.
     */
    def runAction(cfg: NelsonConfig): Sink[IO, Action] = {
      Sink { action =>
        action.run((cfg, action.config)).recoverWith {
          case NonFatal(e) =>
            e.printStackTrace
            IO(log.warn(s"unexpected error occoured whilst running the workflow: ${e.getMessage}, cause: ${e.getCause}"))
        }
      }
    }
  } // e/o sinks

  /**
   * Wire the stream of actionable instances to the
   * effect generating sinks and observations.
   */
  def task(config: NelsonConfig)(effects: Sink[IO, Action]): IO[Unit] = {
    def par[A](ps: Stream[IO, Stream[IO, A]]): Stream[IO, A] = {
      implicit val ec = config.pools.defaultExecutor

      val withErrors = ps.join(config.pipeline.concurrencyLimit).attempt

      withErrors.observeW(config.auditor.errorSink).stripW
    }

    val p: Stream[IO, Stream[IO, Unit]] = config.queue.dequeue.map(a => Stream.emit(a).covary[IO].to(effects))

    par(p).compile.drain
  }
}

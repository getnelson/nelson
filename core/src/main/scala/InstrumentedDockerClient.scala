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

import cats.~>
import cats.effect.IO

import nelson.docker.DockerOp
import nelson.docker.DockerOp._

class InstrumentedDockerClient private (instance: String, interp: DockerOp ~> IO, metrics: Metrics)
    extends (DockerOp ~> IO) {

  def apply[A](op: DockerOp[A]): IO[A] =
    IO(System.nanoTime).flatMap { startNanos =>
      interp(op).attempt.flatMap { att =>
        val elapsed = System.nanoTime - startNanos
        val label = toLabel(op)
        metrics.dockerRequestsLatencySeconds.labels(label, instance).observe(elapsed / 1.0e9)
        att match {
          case Right(a) =>
            IO.pure(a)
          case Left(e) =>
            metrics.dockerRequestsFailuresTotal.labels(label, instance).inc()
            IO.raiseError(e)
        }
      }
    }

  private def toLabel(op: DockerOp[_]) = op match {
    case _: Extract => "extract"
    case _: Tag => "tag"
    case _: Push => "push"
    case _: Pull => "pull"
  }
}

object InstrumentedDockerClient {

  /** Instruments a helm interpreter with Prometheus metrics.
   *  @param dockerInstance an identifier for the instance of consul we're talking to
   *  @param interp a helm interpreter to wrap
   *  @param registry the CollectorRegistry to record to; defaults to CollectorRegistry.default
   */
  def apply(dockerInstance: String, interp: DockerOp ~> IO, metrics: Metrics = Metrics.default): DockerOp ~> IO =
    new InstrumentedDockerClient(dockerInstance, interp, metrics)
}

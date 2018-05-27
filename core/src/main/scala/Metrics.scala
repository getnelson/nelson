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
import cats.data.Kleisli
import cats.effect.IO
import cats.syntax.apply._

import io.prometheus.client._

// To avoid redefinition of Metrics, all metrics should be defined here.
// Bonus points if you keep it alphabetical.
class Metrics(registry: CollectorRegistry) {
  val consulTemplateRunsDurationSeconds = (new Histogram.Builder)
    .name("consul_template_runs_duration_seconds")
    .help("Duration of consul template runs, in seconds")
    .register(registry)

  val consulTemplateRunsFailuresTotal = (new Counter.Builder)
    .labelNames("reason")
    .name("consul_template_runs_failures_total")
    .help("consul-template runs that failed to render a template")
    .register(registry)

  val consulTemplateContainersRunning = (new Gauge.Builder)
    .name("consul_template_containers_running")
    .help("number of consul-template containers currently running")
    .register(registry)

  val consulTemplateContainerCleanupFailuresTotal = (new Counter.Builder)
    .name("consul_template_container_cleanup_failures_total")
    .help("consul-template containers that we could not clean up")
    .register(registry)

  val deploymentMonitorAwaitingHealth = Counter.build
    .name("deployment_monitor_awaiting_health")
    .help("Deployments shown as not passing in helm, and can't be promoted to ready.")
    .labelNames("datacenter")
    .register(registry)

  val deploymentMonitorReadyToPromote = Counter.build
    .name("deployment_monitor_ready_to_promote")
    .help("Deployments shown as passing in helm, and can be promoted to ready.")
    .labelNames("datacenter")
    .register(registry)

  val deployFailureCounter = Counter.build
    .name("deploy_failure_count")
    .help("Units that failed to deploy")
    .labelNames("env")
    .register(registry)

  val deploySuccessCounter = Counter.build
    .name("deploy_success_count")
    .help("Units successfully deployed")
    .labelNames("env")
    .register(registry)

  val destroySuccessCounter = Counter.build
    .name("destroy_success_count")
    .labelNames("env")
    .help("Units successfully destroyed")
    .register(registry)

  val destroyFailureCounter = Counter.build
    .name("destroy_failure_count")
    .labelNames("env")
    .help("Units that failed to destroy")
    .register(registry)

  val dockerRequestsFailuresTotal = (new Counter.Builder)
    .name(s"docker_requests_failures_total")
    .help(s"docker requests that failed")
    .labelNames("docker_op", "docker_instance")
    .register(registry)

  val dockerRequestsLatencySeconds = (new Histogram.Builder)
    .name(s"docker_requests_latency_seconds")
    .help(s"Latency of docker requests, in seconds")
    .labelNames("docker_op", "docker_instance")
    .register(registry)

  val helmRequestsFailuresTotal = (new Counter.Builder)
    .name(s"helm_requests_failures_total")
    .help(s"Helm requests that failed")
    .labelNames("helm_op", "consul_instance")
    .register(registry)

  val helmRequestsLatencySeconds = (new Histogram.Builder)
    .name(s"helm_requests_latency_seconds")
    .help(s"Latency of helm requests, in seconds")
    .labelNames("helm_op", "consul_instance")
    .register(registry)

  val nomadRequestsFailuresTotal = (new Counter.Builder)
    .name(s"nomad_requests_failures_total")
    .help(s"nomad requests that failed")
    .labelNames("nomad_op", "nomad_instance")
    .register(registry)

  val nomadRequestsLatencySeconds = (new Histogram.Builder)
    .name(s"nomad_requests_latency_seconds")
    .help(s"Latency of nomad requests, in seconds")
    .labelNames("nomad_op", "nomad_instance")
    .register(registry)

  val vaultRequestsFailuresTotal = (new Counter.Builder)
    .name(s"vault_requests_failures_total")
    .help(s"Vault requests that failed")
    .labelNames("vault_op", "vault_instance")
    .register(registry)

  val vaultRequestsLatencySeconds = (new Histogram.Builder)
    .name(s"vault_requests_latency_seconds")
    .help(s"Latency of vault requests, in seconds")
    .labelNames("vault_op", "vault_instance")
    .register(registry)

  val sweeperFailures = (new Counter.Builder)
    .name(s"sweeper_failures_total")
    .help(s"Sweeper operations that failed")
    .register(registry)

  val sweeperLatencySeconds = (new Histogram.Builder)
    .name(s"sweeper_latency_seconds")
    .help(s"Latency of Sweeper execution, in seconds")
    .register(registry)

  val sweeperUnclaimedResourcesDetected = (new Histogram.Builder)
    .name(s"sweeper_unclaimed_resource_count")
    .help(s"Number of Unclaimed Resources (items that have no clear owner, possibly garbage) detected but not deleted.")
    .labelNames("datacenter")
    .register(registry)
}

object Metrics {
  val default: Metrics = new Metrics(CollectorRegistry.defaultRegistry)

  /** Construct only once per registry, or you will regret the ensuing runtime exception. */
  def apply(registry: CollectorRegistry) = new Metrics(registry)

  /*
   * Perform a timed operation with configurable handlers.
   */
  def timer(before: IO[Unit], onComplete: Kleisli[IO, Double, Unit], onFail: Kleisli[IO, Throwable, Unit], onSuccess: IO[Unit]) = new (IO ~> IO) {
    def apply[A](op: IO[A]) : IO[A] = {
      val io = for {
        _       <- before
        start   <- IO(System.nanoTime())
        a       <- op
        elapsed = (System.nanoTime - start) / 1.0e9
        _       <- onComplete.run(elapsed)
      } yield a

      io.attempt.flatMap {
        case Left(e)  => onFail(e) *> IO.raiseError(e)
        case Right(a) => onSuccess *> IO.pure(a)
      }
    }
  }
}

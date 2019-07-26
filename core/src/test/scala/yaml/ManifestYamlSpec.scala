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
package yaml

import java.nio.file.Paths

import org.scalatest.{FlatSpec,Matchers}
import scala.concurrent.duration._

class ManifestYamlSpec extends FlatSpec with Matchers with SnakeCharmer {
  import Manifest._
  import Schedule._
  import cleanup._
  import notifications._
  import blueprint._

  val env = Environment(cpu = ResourceSpec.bounded(0.23, 0.23).get, memory = ResourceSpec.bounded(2048, 2048).get, desiredInstances = Some(2))

  val m1 = Manifest(
    units = List(UnitDef(
              name = "foobar",
              description = "description of foobar",
              ports = Some(Ports(Port("default", 8080, "http"), Port("monitoring", 7390, "tcp") :: Nil)),
              dependencies = Map("inventory" -> FeatureVersion(1,4), "cassandra" -> FeatureVersion(1,0)),
              resources = Set(Manifest.Resource("s3", Some("description of s3"))),
              deployable = None,
              meta = Set("foo","bar"),
              alerting = Alerting(
                PrometheusConfig(
                  alerts = List(
                    PrometheusAlert(
                      alert = "instance_down",
                      expression = """IF up == 0 FOR 5m LABELS { severity = "page" } ANNOTATIONS { summary = "Instance {{ $labels.instance }} down", description = "{{ $labels.instance }} of job {{ $labels.job }} has been down for more than 5 minutes.", }"""),
                    PrometheusAlert(
                      alert = "api_high_request_latency",
                      // We're not interpolating, but we have to pretend we are to dodge a fatal warning.  Therefore, $$
                      expression = s"""IF api_http_request_latencies_second{quantile="0.5"} > 1 FOR 1m ANNOTATIONS { summary = "High request latency on {{ $$labels.instance }}", description = "{{ $$labels.instance }} has a median request latency above 1s (current value: {{ $$value }}s)", }""")),
                  rules = List(
                    PrometheusRule("job_service:rpc_durations_microseconds_count:avg_rate5m", "avg(rate(rpc_durations_microseconds_count[5m])) by (job, service)"))))
            ), UnitDef(
              name = "crawler",
              description = "crawler description",
              dependencies = Map("db.example" -> FeatureVersion(1,0)),
              resources = Set.empty,
              ports = None,
              deployable = None,
              meta = Set.empty[String],
              alerting = Alerting(
                PrometheusConfig(
                  alerts = List(
                    PrometheusAlert(
                      alert = "cassandra_unhealthy",
                      expression = """IF cassandra-traffic-light == 1 FOR 5m LABELS { severity = "page" } ANNOTATIONS { summary = "Cassandra is unhealthy", description = "cassandra-traffic-light has been 1 for more than 5 minutes.", }""")),
                  rules = Nil))
            )),
    plans = List(
      Plan(
        name = "default-foobar",
        environment = Environment(
          cpu = ResourceSpec.bounded(0.25, 0.5).get,
          memory = ResourceSpec.bounded(256.0, 512.0).get,
          desiredInstances = Some(1),
          resources = Map("s3" -> new java.net.URI("http://s3.aws.com")),
          alertOptOuts = List(AlertOptOut("api_high_request_latency")),
          policy = Some(RetainLatestTwoMajor),
          healthChecks = List(HealthCheck("http-status","default","https",Some("v1/status"), 10.seconds, 2.seconds)),
          volumes = List(Volume("an-empty-dir", Paths.get("/foo/bar"), 500)),
          workflow = Magnetar
        )
      ),
      Plan(
        name = "qa-crawler-1",
        environment = Environment(
          cpu = ResourceSpec.bounded(0.5, 1.0).get,
          memory = ResourceSpec.bounded(512.0, 1024.0).get,
          retries = Some(2),
          desiredInstances = Some(1),
          schedule = Some(Schedule(Once)),
          policy = Some(RetainLatest),
          bindings = List(
            EnvironmentVariable("FOO","foo-1"),
            EnvironmentVariable("QUX","qux-1")
          ),
          workflow = Magnetar
        )
      ),
      Plan(
        name = "qa-crawler-2",
        environment = Environment(
          cpu = ResourceSpec.bounded(0.5, 1.0).get,
          memory = ResourceSpec.bounded(512.0, 1024.0).get,
          retries = Some(3),
          desiredInstances = Some(1),
          schedule = Some(Schedule(Cron("*/30 * * * *"))),
          bindings = List(
            EnvironmentVariable("FOO","foo-2"),
            EnvironmentVariable("QUX","qux-2")
          )
        )
      ),
      Plan(
        name = "prod-crawler",
        environment = Environment(
          cpu = ResourceSpec.bounded(1.0, 2.0).get,
          memory = ResourceSpec.bounded(512.0, 1024.0).get,
          retries = Some(2),
          desiredInstances = Some(4),
          schedule = Some(Schedule(Hourly)),
          policy = Some(RetainLatestTwoFeature),
          bindings = List(
            EnvironmentVariable("FOO","foo-prod"),
            EnvironmentVariable("QUX","qux-prod")
          ),
          workflow = Pulsar,
          blueprint = Some(Left(("qux", Blueprint.Revision.HEAD)))
        )
      ),
      Plan(
        name = "lb-plan",
        environment = Environment(
          desiredInstances = Some(4),
          workflow = Magnetar
        )
      )
    ),
    targets = DeploymentTarget.Except(Nil),
    namespaces = List(Namespace(
                  name = NamespaceName("qa"),
                  units = Set(("foobar", Set("default-foobar")), ("crawler", Set("qa-crawler-1","qa-crawler-2"))),
                  loadbalancers = Set(("howdy-lb", Some("lb-plan")))),
                 Namespace(
                  name = NamespaceName("prod"),
                  units = Set(("foobar", Set("default-foobar")), ("crawler", Set("prod-crawler"))),
                  loadbalancers = Set()
                )),
    notifications = NotificationSubscriptions(
                List(SlackSubscription("development"),SlackSubscription("general")),
                List(EmailSubscription("baxter@example.com"))),
    loadbalancers = List(
      Loadbalancer("howdy-lb",
        Vector(Route(Port("default",8444,"http"), BackendDestination("foobar", "default")),
               Route(Port("monitoring",8441,"https"), BackendDestination("foobar", "monitoring"))
      ))
    )
  )

  def base64Encode(s: String): String =
    new String(java.util.Base64.getEncoder.encode(s.getBytes), "UTF-8")

  behavior of "v1 manifest parser:"

  it should "parse an exhaustive manifest file" in {
    loadManifest("/nelson/manifest.v1.everything.yml") should equal (Right(m1))
  }

  it should "load very minimal manifests" in {
    loadManifest("/nelson/dependencies.foo_1.0.1.yml").isRight should equal (true)
    loadManifest("/nelson/dependencies.bar_2.0.0.yml").isRight should equal (true)
    loadManifest("/nelson/dependencies.bar_2.1.1.yml").isRight should equal (true)
    loadManifest("/nelson/dependencies.baz_3.3.1.yml").isRight should equal (true)
  }

  it should "parse an minimal manifest file" in {
    val a = loadManifest("/nelson/manifest.v1.minimal.yml")
    a.isRight should equal (true)
  }

  it should "parse datacenters 'only' filter" in {
    val target = DeploymentTarget.Only(List("arlington"))
    loadManifest("/nelson/manifest.v1.only-dc.yml").right.get.targets should equal (target)
  }

  it should "parse datacenters 'except' filter" in {
    val target = DeploymentTarget.Except(List("arlington"))
    loadManifest("/nelson/manifest.v1.except-dc.yml").right.get.targets should equal (target)
  }

  it should "allow specifying a limit without a request" in {
    val mf = loadManifest("/nelson/manifest.v1.limit-without-request.yml")
    mf.isRight should equal (true)
  }

  it should "reject manifests that specify a request without a limit" in {
    val mf = loadManifest("/nelson/manifest.v1.request-without-limit.yml")
    hasError(mf, "Cannot specify CPU request without CPU limit.")
  }

  it should "reject manifests with request > limit" in {
    val mf = loadManifest("/nelson/manifest.v1.request-gt-limit.yml")
    hasError(mf, "Invalid memory request, request must be <= 512.0 but is 1024.0")
  }

  def hasError[A](mf: Either[Throwable, Manifest], slice: String) = {
    mf.swap.exists { err =>
      val msg = err.getMessage
      msg.containsSlice(slice)
    } should equal (true)
  }

  it should "provide errors for duplicate alert names in the same unit" in {
    val mf = loadManifest("/nelson/manifest.v1.duplicate-alerts.yml")
    hasError(mf, "'duplicate_alert_in_same_unit' alert name is not unique within this manifest.")
  }

  it should "provide errors for duplicate rule names in the same unit" in {
    val mf = loadManifest("/nelson/manifest.v1.duplicate-alerts.yml")
    hasError(mf, "'duplicate_rule_in_same_unit' rule name is not unique within this manifest.")
  }

  it should "provide errors for duplicate alert names after normalization" in {
    val mf = loadManifest("/nelson/manifest.v1.duplicate-alerts.yml")
    hasError(mf, "'duplicate_alert_after_normalization' alert name is not unique within this manifest.")
  }

  it should "provide errors for duplicate rule names after normalization" in {
    val mf = loadManifest("/nelson/manifest.v1.duplicate-alerts.yml")
    hasError(mf, "'duplicate_rule_after_normalization' rule name is not unique within this manifest.")
  }

  it should "provide errors for unknown unit references" in {
    val mf = loadManifest("/nelson/manifest.v1.unknownref.yml")
    hasError(mf, "'unknown1' isn't a valid unit reference, because it was not declared as a unit")
    hasError(mf, "'unknown2' isn't a valid unit reference, because it was not declared as a unit")
  }

  it should "provide errors for invalid opt-outs" in {
    val mf = loadManifest("/nelson/manifest.v1.invalid-alert-opt-out.yml")
    hasError(mf, "'this_is_illegal_yo' isn't a valid alert opt-out, because it was not declared in unit 'howdy'.")
  }

  it should "provide errors for opt-outs declared by other units" in {
    val mf = loadManifest("/nelson/manifest.v1.invalid-alert-opt-out.yml")
    hasError(mf, "'api_high_request_latency' isn't a valid alert opt-out, because it was not declared in unit 'doody'.")
  }

  it should "provide errors for invalid expiration policy for services" in {
    val mf = loadManifest("/nelson/manifest.v1.invalid-service-expiration-policy.yml")
    hasError(mf, "parse failed! bogus isn't a valid expiration policy.")
  }

  it should "provide errors for invalid email" in {
    val mf = loadManifest("/nelson/manifest.v1.invalid-email.yml")
    hasError(mf, "parse failed! randal.mcmurphy isn't a valid email")
  }

  it should "parse a manifest without any plans" in {
    loadManifest("/nelson/manifest.v1.no-plans.yml").isRight should equal (true)
  }

  it should "provide errors for invalid uri in resource definition" in {
    val mf = loadManifest("/nelson/manifest.v1.invalid-uri.yml")
    hasError(mf, "parse failed! s3:^^ is an invalid URI")
  }

  it should "provide errors for invalid CPU" in {
    loadManifest("/nelson/manifest.v1.invalid-cpu.yml").isLeft should equal (true)
  }

  it should "provide errors for invalid memory" in {
    loadManifest("/nelson/manifest.v1.invalid-memory.yml").isLeft should equal (true)
  }

  it should "provide errors for invalid instances" in {
    loadManifest("/nelson/manifest.v1.invalid-instances.yml").isLeft should equal (true)
  }

  it should "provide errors for empty resources stanza" in {
    val mf = loadManifest("/nelson/manifest.v1.empty-resources-stanza.yml")
    hasError(mf, "parse failed! the field 'unit.resources' can not be empty")
  }

  it should "provide errors for invalid meta" in {
    val mf = loadManifest("/nelson/manifest.v1.invalid-meta.yml")
    hasError(mf, "parse failed! field unit.meta is not a valid alphanumeric hyphen string: a.b.c\nmeta (toooooooooooo-looooooong) must less that or equal to 14 characters")
  }

  it should "correctly parse a plan containing a blueprint reference" in {
    // note that at this point, the blueprint may not exist; the parser only
    // cares for syntax / form correctness. see manifest validator for validation.
    loadManifest("/nelson/manifest.v1.blueprint-missing.yml").isRight should equal (true)
  }

  it should "parse a plan with a workflow but no blueprint" in {
    loadManifest("/nelson/manifest.v1.no-blueprint.yml").isRight should equal (true)
  }

  it should "parse a manifest that makes use of YAML anchors and aliases" in {
    val check = loadManifest("/nelson/manifest.v1.anchors.yml").right.map(
      _.plans.flatMap(_.environment.retries))
    check should equal (Right(List(2,2)))
  }

}

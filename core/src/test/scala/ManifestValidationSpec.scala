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

import nelson.Manifest.{Port => MPort,Route,BackendDestination}
import nelson.ManifestValidator.{ManifestValidation}
import nelson.ManifestValidator.Json._

import argonaut.Argonaut._

import cats.data.NonEmptyList
import cats.data.Validated.Invalid
import cats.syntax.either._

import journal.Logger

import org.scalatest.concurrent.TimeLimitedTests
import org.scalatest.time.SpanSugar._

import scala.collection.JavaConverters._

class ManifestValidationSpec extends NelsonSuite with TimeLimitedTests {
  // This spec has been hanging in Travis, and now it's not, but we
  // never identified the root cause.  In case we fail, let's expedite
  // the cycle.
  val timeLimit = convertIntToGrainOfTime(30).seconds
  private[this] val log = Logger("TestInterruptor")
  override val defaultTestSignaler = new org.scalatest.concurrent.Signaler {
    val threadsDumped = new java.util.concurrent.atomic.AtomicBoolean(false)
    def apply(testThread: Thread) = {
      if (threadsDumped.compareAndSet(false, true)) {
        log.info("ManifestValidationSpec interrupted")
        Thread.getAllStackTraces.asScala.foreach { case (t, st) =>
          log.info(t.getName)
          st.foreach(frame => log.info(s"\t${frame}"))
        }
      }
      testThread.interrupt()
    }
  }

  def base64Encode(s: String): String =
    new String(java.util.Base64.getEncoder.encode(s.getBytes), "UTF-8")

  override def beforeAll(): Unit = {
    super.beforeAll()
    insertFixtures(testName).foldMap(config.storage).unsafeRunSync()
    ()
  }

  behavior of "manifest validator:"

  it should "reject a manifest with a plan containing a missing blueprint" in {
    val out = (for {
      a <- Util.loadResourceAsString("/nelson/manifest.v1.blueprint-missing.yml")
      b <- ManifestValidator.parseManifestAndValidate(a, config)
    } yield b).unsafeRunSync()

    val Invalid(x) = out
  }

  it should "accept a valid nelson unit" in {

    Util.loadResourceAsString("/nelson/manifest.v1.minimal.yml").map{ manifest =>
      val json =
        s"""|{
            |  "units": [
            |    {
            |      "kind": "howdy",
            |      "name": "howdy-0.4"
            |    }
            |  ],
            |  "manifest": "${base64Encode(manifest)}"
            |}""".stripMargin

      val mv = json.decodeEither[ManifestValidation].toOption.get
      val res = ManifestValidator.validate(mv.config, mv.units).run(config).attempt.unsafeRunSync()
      assert(res.toOption.get.isValid)
    }.unsafeRunSync()
  }

  it should "reject invalid manifest" in {
    val json =
      s"""|{
          |  "units": [
          |    {
          |      "kind": "howdy",
          |      "name": "howdy-0.4"
          |    }
          |  ],
          |  "manifest": "foo"
          |}""".stripMargin

    val mv = json.decodeEither[ManifestValidation].toOption.get
    val res = ManifestValidator.validate(mv.config, mv.units).run(config).attempt.unsafeRunSync()

    val Right(Invalid(x)) = res
  }

  it should "reject an undeployable manifest" in {
    Util.loadResourceAsString("/nelson/manifest.v1.undeployable.yml").map{ manifest =>

      val json =
        s"""|{
          |  "units": [
          |    {
          |      "kind": "howdy",
          |      "name": "howdy-0.4"
          |    }
          |  ],
          |  "manifest": "${base64Encode(manifest)}"
          |}""".stripMargin
      val mv = json.decodeEither[ManifestValidation].toOption.get // YOLO
      val Right(Invalid(e)) = ManifestValidator.validate(mv.config, mv.units).run(config).attempt.unsafeRunSync()

      e should equal (NonEmptyList.of(DeprecatedDependency("howdy", testName, "dev", Datacenter.ServiceName("search",FeatureVersion(1,1)))))
     }.unsafeRunSync()
  }

  it should "reject an invalid unit kind" in {

    Util.loadResourceAsString("/nelson/manifest.v1.minimal.yml").map{ manifest =>

      val json =
        s"""|{
            |  "units": [
            |    {
            |      "kind": "notright",
            |      "name": "howdy-0.4"
            |    }
            |  ],
            |  "manifest": "${base64Encode(manifest)}"
            |}""".stripMargin

      val mv = json.decodeEither[ManifestValidation].toOption.get // YOLO
      ManifestValidator.validate(mv.config, mv.units).run(config).attempt.unsafeRunSync() should equal (
        Right(Invalid(NonEmptyList.of(ManifestUnitKindMismatch("notright", List("howdy"))))))
    }.unsafeRunSync()
  }

  it should "reject an invalid loadbalancer port" in {

    Util.loadResourceAsString("/nelson/manifest.v1.invalid-proxy-port.yml").map{ manifest =>

      val json =
        s"""|{
            |  "units": [
            |    {
            |      "kind": "howdy",
            |      "name": "howdy-0.4"
            |    }
            |  ],
            |  "manifest": "${base64Encode(manifest)}"
            |}""".stripMargin

      val cfg = config.copy(proxyPortWhitelist = Some(ProxyPortWhitelist(List(80,443))))
      val mv = json.decodeEither[ManifestValidation].toOption.get
      val Right(Invalid(e)) = ManifestValidator.validate(mv.config, mv.units).run(cfg).attempt.unsafeRunSync()
      e should equal (NonEmptyList.of(InvalidLoadbalancerPort(9000, List(80,443))))
    }.unsafeRunSync()
  }

  it should "reject loadbalancer with name longer than 17 characters" in {

    Util.loadResourceAsString("/nelson/manifest.v1.invalid-proxy-name-length.yml").map{ manifest =>

      val json =
        s"""|{
            |  "units": [
            |    {
            |      "kind": "howdy",
            |      "name": "howdy-0.4"
            |    }
            |  ],
            |  "manifest": "${base64Encode(manifest)}"
            |}""".stripMargin

      val mv = json.decodeEither[ManifestValidation].toOption.get
      val Right(Invalid(e)) = ManifestValidator.validate(mv.config, mv.units).run(config).attempt.unsafeRunSync()
      e should equal (NonEmptyList.of(InvalidLoadbalancerNameLength("xxxxxxxxxxxxxxxxxx", 12))) }.unsafeRunSync()
  }

  it should "accept multiple loadbalancer ports" in {
    Util.loadResourceAsString("/nelson/manifest.v1.valid-proxy-port.yml").map{ manifest =>

      val json =
        s"""|{
            |  "units": [
            |    {
            |      "kind": "howdy",
            |      "name": "howdy-0.4"
            |    }
            |  ],
            |  "manifest": "${base64Encode(manifest)}"
            |}""".stripMargin

      val mv = json.decodeEither[ManifestValidation].toOption.get
      val res = ManifestValidator.validate(mv.config, mv.units).run(config).attempt.unsafeRunSync()
      assert(res.map(_.isValid).getOrElse(false))
    }.unsafeRunSync()
  }

  it should "reject an unknown route desitination unit name" in {
    Util.loadResourceAsString("/nelson/manifest.v1.unknown-route.yml").map { manifest =>

      val json =
        s"""|{
            |  "units": [
            |    {
            |      "kind": "howdy",
            |      "name": "howdy-0.4"
            |    }
            |  ],
            |  "manifest": "${base64Encode(manifest)}"
            |}""".stripMargin

      val mv = json.decodeEither[ManifestValidation].toOption.get
      val Right(Invalid(e)) = ManifestValidator.validate(mv.config, mv.units).run(config).attempt.unsafeRunSync()
      val route = Route(MPort("default",8444,"https"),BackendDestination("foo","default"))
      e should equal (NonEmptyList.of(UnknownBackendDestination(route, List("howdy"))))
    }.unsafeRunSync()
  }

  it should "reject an unknown health check port reference" in {
    Util.loadResourceAsString("/nelson/manifest.v1.unknown-health-check-port.yml").map { manifest =>

      val json =
        s"""|{
            |  "units": [
            |    {
            |      "kind": "howdy",
            |      "name": "howdy-0.4"
            |    }
            |  ],
            |  "manifest": "${base64Encode(manifest)}"
            |}""".stripMargin

      val mv = json.decodeEither[ManifestValidation].toOption.get
      val Right(Invalid(e)) = ManifestValidator.validate(mv.config, mv.units).run(config).attempt.unsafeRunSync()
      e should equal (NonEmptyList.of(UnknownPortRef("unknown-port", "howdy")))
    }.unsafeRunSync()
  }

  it should "reject an missing health check path" in {
    Util.loadResourceAsString("/nelson/manifest.v1.missing-health-check-path.yml").map { manifest =>

      val json =
        s"""|{
            |  "units": [
            |    {
            |      "kind": "howdy",
            |      "name": "howdy-0.4"
            |    }
            |  ],
            |  "manifest": "${base64Encode(manifest)}"
            |}""".stripMargin

      val mv = json.decodeEither[ManifestValidation].toOption.get
      val Right(Invalid(e)) = ManifestValidator.validate(mv.config, mv.units).run(config).attempt.unsafeRunSync()
      e should equal (NonEmptyList.of(MissingHealthCheckPath("https"))) }.unsafeRunSync()
  }

  it should "reject resources that aren't specified in plan" in {
    (Util.loadResourceAsString("/nelson/manifest.v1.invalid-resources.yml").flatMap { mf =>
      ManifestValidator.parseManifestAndValidate(mf, config)
    }).unsafeRunSync().fold(_.toList.exists(_.getMessage.contains(
      "resources reference kafka is missing from plan default"
    )), _ => false) should be (true)
  }

  it should "reject invalid alerts" in {
    val errors = (Util.loadResourceAsString("/nelson/manifest.v1.invalid-alert-definition.yml").flatMap { mf =>
      ManifestValidator.parseManifestAndValidate(mf, config)
     }).unsafeRunSync().fold(_.toList, _ => Nil).map(_.getMessage)
    atLeast(1, errors) should include("""unexpected identifier "BALDERDASH" in alert statement, expected "if"""")
  }

  it should "reject manifest if default namespace is not referenced at least once" in {
    (Util.loadResourceAsString("/nelson/manifest.v1.missing-default-namespace-reference.yml").flatMap { mf =>
      ManifestValidator.parseManifestAndValidate(mf, config)
    }).unsafeRunSync().fold(_.toList.exists(_.getMessage.contains(
      "All manifests must reference the default namespace at least once."
    )), _ => false) should be (false)
  }

  it should "reject manifest if default namespace is not referenced by every unit" in {
    (Util.loadResourceAsString("/nelson/manifest.v1.unit-missing-default-namespace-reference.yml").flatMap { mf =>
      ManifestValidator.parseManifestAndValidate(mf, config)
    }).unsafeRunSync().fold(_.toList.exists(_.getMessage.contains(
      "Each unit in the manifest must reference the default namespace."
    )), _ => false) should be (false)
  }

  it should "reject unknown plan reference" in {
    (Util.loadResourceAsString("/nelson/manifest.v1.unknown-plan-ref.yml").flatMap { mf =>
      ManifestValidator.parseManifestAndValidate(mf, config)
    }).unsafeRunSync().fold(_.toList.exists(a => a.getMessage.contains(
      """'unknown' isn't a valid plan reference, because it was not declared as a plan."""
    )), _ => false) should be (true)
  }

  it should "reject a manifest with unit name lengths greater than 41 characters" in {
    val errors = (Util.loadResourceAsString("/nelson/manifest.v1.invalid-unit-name-length.yml").flatMap { mf =>
      ManifestValidator.parseManifestAndValidate(mf, config)
    }).unsafeRunSync().fold(_.toList, _ => Nil).map(_.getMessage)
    atLeast(1, errors) should include("""Unit names must be less than 42 characters""")
  }

  it should "reject a manifest with an unit name with invalid DNS characters" in {
    val errors = (Util.loadResourceAsString("/nelson/manifest.v1.invalid-dns-characters.yml").flatMap { mf =>
      ManifestValidator.parseManifestAndValidate(mf, config)
    }).unsafeRunSync().fold(_.toList, _ => Nil).map(_.getMessage)
    atLeast(1, errors) should include("""Unit names can only include hyphens, A-Z, a-z, 0-9 where the unit name starts and ends with an alpha-numeric character.""")
  }

  behavior of "cycle detection"

  it should "reject manifest with self dependency" in {
    Util.loadResourceAsString("/nelson/manifest.v1.dep-on-self.yml").map { manifest =>
      val json =
        s"""|{
            |  "units": [
            |    {
            |      "kind": "doesn't matter",
            |      "name": "doesn't matter"
            |    }
            |  ],
            |  "manifest": "${base64Encode(manifest)}"
            |}""".stripMargin
      val mv = json.decodeEither[ManifestValidation].toOption.get
      val e = ManifestValidator.validate(mv.config, mv.units).run(config).attempt.unsafeRunSync().toOption.get.swap.toOption.get
      val expectedErrors = NonEmptyList.of(
        CyclicDependency(
          "Dependency cycle detected for unit 'conductor' in namespace 'dev' in datacenter 'ManifestValidationSpec': conductor@1.1.1"
        )
      )
      e.toList.toSet should === (expectedErrors.toList.toSet)
    }.unsafeRunSync()
  }

  it should "reject manifest that declares a dependency on transitively dependent unit" in {
    Util.loadResourceAsString("/nelson/manifest.v1.dep-on-dependant.yml").map { manifest =>
      val json =
        s"""|{
            |  "units": [
            |    {
            |      "kind": "doesn't matter",
            |      "name": "doesn't matter"
            |    }
            |  ],
            |  "manifest": "${base64Encode(manifest)}"
            |}""".stripMargin
      val mv = json.decodeEither[ManifestValidation].toOption.get
      val e = ManifestValidator.validate(mv.config, mv.units).run(config).attempt.unsafeRunSync().toOption.get.swap.toOption.get
      val expectedErrors = NonEmptyList.of(
        CyclicDependency(
          "Dependency cycle detected for unit 'inventory' in namespace 'dev' in datacenter 'ManifestValidationSpec': conductor@1.1.1"
        ),
        CyclicDependency(
          "Dependency cycle detected for unit 'inventory' in namespace 'dev' in datacenter 'ManifestValidationSpec': ab@2.2.1"
        ),
        CyclicDependency(
          "Dependency cycle detected for unit 'inventory' in namespace 'dev' in datacenter 'ManifestValidationSpec': ab@2.2.2"
        ),
        CyclicDependency(
          "Dependency cycle detected for unit 'conductor' in namespace 'dev' in datacenter 'ManifestValidationSpec': conductor@1.1.1"
        )
      )
      e.toList.toSet should === (expectedErrors.toList.toSet)
    }.unsafeRunSync()
  }

  it should "reject an periodic unit that also declares traffic shift" in {
    Util.loadResourceAsString("/nelson/manifest.v1.periodic-traffic-shift.yml").map { manifest =>

      val json =
        s"""|{
            |  "units": [
            |    {
            |      "kind": "howdy",
            |      "name": "howdy-0.4"
            |    }
            |  ],
            |  "manifest": "${base64Encode(manifest)}"
            |}""".stripMargin

      val mv = json.decodeEither[ManifestValidation].toOption.get
      val Right(Invalid(e)) = ManifestValidator.validate(mv.config, mv.units).run(config).attempt.unsafeRunSync()
      e should equal (NonEmptyList.of(PeriodicUnitWithTrafficShift("howdy"))) }.unsafeRunSync()
  }

  it should "reject a manifest with a loadbalancer that routes to different units" in {
    (Util.loadResourceAsString("/nelson/manifest.v1.invalid-loadbalancer-routes.yml").flatMap { mf =>
      ManifestValidator.parseManifestAndValidate(mf, config)
    }).unsafeRunSync().fold(_.toList.exists(a => a.getMessage.contains(
      """loadbalancer lb has invalid route definition, all routes must route to the same backend service"""
    )), _ => false) should be (true)
  }
}

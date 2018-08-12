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

import java.io.File

import org.scalatest.FlatSpec
import scala.concurrent.duration._
import Templates._

class TemplatesSpec extends FlatSpec with NelsonSuite {
  def templatePath(baseName: String) =
    new File(getClass.getResource(s"/nelson/templates/${baseName}.template").toURI).toPath

  val DummyVaultToken = "xxxxxxxx-xxxx-xxxx-xxxxxxxxxxxx"
  val ExtraEnv = Map(
    "CUSTOM_TOKEN" -> "custom",
    "NELSON_ENV" -> "dev",
    "NELSON_STACKNAME" -> "howdy-http--0-0-0--test1234",
    "NELSON_DATACENTER" -> "texas",
    "NELSON_DNS_ROOT" -> "example.com",
    "NELSON_PLAN" -> "default"
  )

  def render(file: String, timeout: FiniteDuration = 15.seconds) =
    renderTemplate(config.pools.defaultExecutor, config.pools.schedulingPool, config.template.copy(timeout = timeout), config.dockercfg,
      templatePath(file), DummyVaultToken, ExtraEnv)
  behavior of "runTemplates"

  ignore should "return Rendered if it can be rendered" in {
    render("valid").unsafeRunSync() should be (Rendered)
  }

  it should "return InvalidTemplate with error message if it can't be rendered" in {
    render("invalid").unsafeRunSync() should matchPattern {
      case InvalidTemplate(msg) =>
    }
  }

  it should "not leak valid output when reporting errors" in {
    render("leak").unsafeRunSync() should matchPattern {
      case InvalidTemplate(msg) if !msg.contains("[leak]") =>
    }
  }

  ignore should "expose custom environment variables" in {
    render("custom-token").unsafeRunSync() shouldBe (Rendered)
  }

  it should "kill slow templates after timeout" in {
    // Intentionally a tight timeout:
    // 1 - We don't want to wait long for something we know we'll kill
    // 2 - We probably get to kill it before it's registered in docker, which fails,
    //     which exercises our cleanup loop.
    render("evil", 1.millisecond).unsafeRunSync() shouldBe a [TemplateTimeout]
  }
}

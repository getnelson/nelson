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
import org.scalatest.{FlatSpec,Matchers}
import org.scalatest.{FlatSpec,Matchers}
import scalaz.concurrent.Task

class ConfigSpec extends FlatSpec with Matchers {

  def read(file: String): Task[List[Domain]] =
    (for {
      a <- knobs.loadImmutable(Required(
        ClassPathResource(file)) :: Nil)
    } yield Config.readDomains(a.subconfig("nelson.domains"), null, DockerConfig("docker.local", true), null, null, null,null))

  it should "correctly parse the domain definitions from file" in {
    val dcs = read("nelson/domains.cfg").run
    dcs.find(_.name == "california").flatMap(_.loadbalancer).isDefined should equal (false)
    dcs.find(_.name == "oregon").flatMap(_.loadbalancer).isDefined should equal (true)
    dcs.length should equal (2)
  }

  it should "fail if consul is not specified" in {
    val cfg = read("nelson/domains-missing-consul.cfg").attemptRun
    cfg.swap.exists { err =>
      val msg = err.getMessage
      msg == "No such key: infrastructure.consul.endpoint"
    } should equal (true)
  }

  it should "fail if domain is not specified" in {
    val cfg = read("nelson/domains-missing-domain.cfg").attemptRun
    cfg.swap.exists { err =>
      val msg = err.getMessage
      msg == "No such key: domain"
    } should equal (true)
  }

  def readAws(file: String): Task[Option[Infrastructure.Aws]] =
    (for {
      a <- knobs.loadImmutable(Required(
        ClassPathResource(file)) :: Nil)
    } yield Config.readAwsInfrastructure(a.subconfig("aws")))

  it should "fail if public subnet is not specified in aws config" in {
    val cfg = readAws("nelson/domains-missing-subnet.cfg").attemptRun
    cfg.swap.exists { err =>
      val msg = err.getMessage
      msg == "No such key: public-subnet"
    } should equal (true)
  }
  it should "fail if private subnet is not specified in aws config" in {
    val cfg = readAws("nelson/domains-missing-private-subnet.cfg").attemptRun
    cfg.swap.exists { err =>
      val msg = err.getMessage
      msg == "No such key: private-subnet"
    } should equal (true)
  }

  behavior of "readTemplate"

  it should "take vault address from first domain, alphabetically" in {
    val config = knobs.loadImmutable(
      List(
        Required(ClassPathResource("nelson/defaults.cfg")),
        Required(ClassPathResource("nelson/nelson-test.cfg")),
        Required(ClassPathResource("nelson/domains.cfg"))
      )).map(Config.readConfig(_, NelsonSuite.testHttp, TestStorage.xa _)).run
    config.template.vaultAddress should equal (Some("https://vault.california.service"))
  }

  it should "find no vault address with no domains" in {
    val config = knobs.loadImmutable(
      List(
        Required(ClassPathResource("nelson/defaults.cfg")),
        Required(ClassPathResource("nelson/nelson-test.cfg"))
      )).map(Config.readConfig(_, NelsonSuite.testHttp, TestStorage.xa _)).run
    config.template.vaultAddress should equal (None)
  }
}

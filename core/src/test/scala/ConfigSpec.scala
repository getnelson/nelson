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
import cats.effect.IO
import cats.implicits._
import nelson.Util._
import loadbalancers.ElbScheme

class ConfigSpec extends FlatSpec with Matchers {

  def read(file: String): IO[List[Datacenter]] =
    (for {
      a <- knobs.loadImmutable[IO](Required(ClassPathResource(file)) :: Nil)
      c <- Config.readDatacenters(a.subconfig("nelson.datacenters"), null, DockerConfig("docker.local", true), null, null, stubbedInterpreter, stubbedInterpreter)
    } yield c)

  it should "correctly parse the datacenter definitions from file" in {
    val dcs = read("nelson/datacenters.cfg").unsafeRunSync()
    dcs.find(_.name == "california").flatMap(_.loadbalancer).isDefined should equal (false)
    dcs.find(_.name == "oregon").flatMap(_.loadbalancer).isDefined should equal (true)
    dcs.length should equal (2)
  }

  it should "fail if domain is not specified" in {
    val cfg = read("nelson/datacenters-missing-domain.cfg").attempt.unsafeRunSync()
    cfg.swap.exists { err =>
      val msg = err.getMessage
      msg == "No such key: domain"
    } should equal (true)
  }

  def readAws(file: String): IO[Option[Infrastructure.Aws]] =
    (for {
      a <- knobs.loadImmutable[IO](Required(
        ClassPathResource(file)) :: Nil)
    } yield Config.readAwsInfrastructure(a.subconfig("aws")))

  it should "fail if public subnet is not specified in aws config" in {
    val cfg = readAws("nelson/datacenters-missing-subnet.cfg").attempt.unsafeRunSync()
    cfg.swap.exists { err =>
      val msg = err.getMessage
      msg == "No such key: public-subnet"
    } should equal (true)
  }

  it should "fail if private subnet is not specified in aws config" in {
    val cfg = readAws("nelson/datacenters-missing-private-subnet.cfg").attempt.unsafeRunSync()
    cfg.swap.exists { err =>
      val msg = err.getMessage
      msg == "No such key: private-subnet"
    } should equal (true)
  }

  it should "successfully read the aws config" in {
    val cfg = readAws("nelson/datacenters-valid-aws.cfg").attempt.unsafeRunSync()
    cfg.right.exists { c =>
      c.map(_.lbScheme) == Some(ElbScheme.Internal)
    } should equal (true)
  }

  it should "successfully provide a default elb scheme in the event its missing" in {
    val cfg = readAws("nelson/datacenters-aws-missing-elb-scheme.cfg").attempt.unsafeRunSync()
    cfg.right.get.get.lbScheme should equal (ElbScheme.External)
  }

  behavior of "readTemplate"

  it should "take vault address from first datacenter, alphabetically" in {
    val config = knobs.loadImmutable[IO](
      List(
        Required(ClassPathResource("nelson/defaults.cfg")),
        Required(ClassPathResource("nelson/nelson-test.cfg")),
        Required(ClassPathResource("nelson/datacenters.cfg"))
      )).flatMap(Config.readConfig(_, NelsonSuite.testHttp, TestStorage.xa _)).unsafeRunSync()
    config.template.vaultAddress should equal (Some("https://vault.california.service"))
  }

  it should "find no vault address with no datacenters" in {
    val config = knobs.loadImmutable[IO](
      List(
        Required(ClassPathResource("nelson/defaults.cfg")),
        Required(ClassPathResource("nelson/nelson-test.cfg"))
      )).flatMap(Config.readConfig(_, NelsonSuite.testHttp, TestStorage.xa _)).unsafeRunSync()
    config.template.vaultAddress should equal (None)
  }
}

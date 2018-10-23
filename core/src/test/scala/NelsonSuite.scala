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
import cats.implicits._

import nelson.notifications.{SlackOp,EmailOp}

import doobie._
import doobie.implicits._

import helm.{ConsulOp, HealthCheckResponse}

import knobs._

import scala.collection.immutable.SortedMap

import org.http4s.client.blaze.{BlazeClientConfig, Http1Client}

import org.scalatest.{FlatSpec,Matchers,BeforeAndAfterAll}

trait NelsonSuite
    extends FlatSpec
    with Matchers
    with RoutingFixtures
    with BeforeAndAfterAll {

  val testName: String = getClass.getSimpleName

  def trunc: ConnectionIO[Unit] = (
    sql"SET REFERENTIAL_INTEGRITY FALSE; -- YOLO".update.run *>
    sql"TRUNCATE TABLE traffic_shifts".update.run *>
    sql"TRUNCATE TABLE traffic_shift_start".update.run *>
    sql"TRUNCATE TABLE traffic_shift_reverse".update.run *>
    sql"TRUNCATE TABLE deployment_statuses".update.run *>
    sql"TRUNCATE TABLE deployments".update.run *>
    sql"TRUNCATE TABLE unit_dependencies".update.run *>
    sql"TRUNCATE TABLE service_ports".update.run *>
    sql"TRUNCATE TABLE units".update.run *>
    sql"TRUNCATE TABLE loadbalancer_routes".update.run *>
    sql"TRUNCATE TABLE loadbalancer_deployments".update.run *>
    sql"TRUNCATE TABLE loadbalancers".update.run *>
    sql"TRUNCATE TABLE events".update.run *>
    sql"TRUNCATE TABLE namespaces".update.run *>
    sql"TRUNCATE TABLE datacenters".update.run *>
    sql"SET REFERENTIAL_INTEGRITY TRUE; -- COYOLO".update.run
  ).void

  val dbConfig = TestStorage.dbConfig(testName)

  override def beforeAll: Unit = {
    trunc.transact(stg.xa).unsafeRunSync()
  }

  /**
   *  a map of string to pretend will be found in consul. override
   *  this if you need your tests to see consul data
   */
  def consulMap: Map[String, String] = Map.empty

  lazy val testConsul: ConsulOp ~> IO = new (ConsulOp ~> IO) {
    @volatile var kvs: Map[String,String] = consulMap
    import helm.Key
    def apply[A](a: ConsulOp[A]): IO[A] = a match {
      case ConsulOp.KVGet(key: Key) => IO(Some(kvs(key)))
      case ConsulOp.KVSet(key: Key, value: String) => IO(kvs = kvs + (key -> value))
      case ConsulOp.KVDelete(key: Key) => IO(kvs = kvs - key)
      case ConsulOp.KVListKeys(prefix: Key) => IO(kvs.keySet.filter(_.startsWith(prefix)))
      case ConsulOp.HealthListChecksForService(service: String, _, _, _) =>
        IO(List(HealthCheckResponse("", "", "", helm.HealthStatus.fromString(kvs(s"health/$service")).get, "", "", "", "", List.empty, 0L, 0L)))
      case _ => throw new Exception("currently not used")
    }
  }

  import vault._
  val testVault: Vault ~> IO = new (Vault ~> IO) {
    def apply[A](v: Vault[A]): IO[A] = IO.pure(v match {
      case Vault.IsInitialized => true
      case Vault.Initialize(init) => InitialCreds(Nil, RootToken("fake"))
      case Vault.GetSealStatus => SealStatus(false, 0, 0, 0)
      case Vault.Seal => ()
      case Vault.Unseal(key) => SealStatus(false, 0, 0, 0)
      case Vault.Get(path) => "fake"
      case Vault.Set(path, value) => ()
      case Vault.CreatePolicy(_,_) => ()
      case Vault.DeletePolicy(_) => ()
      case Vault.GetMounts => SortedMap.empty
      case _: Vault.CreateToken => vault.Token("aaaaaaaa-bbbb-cccc-dddddddddddd")
      case _: Vault.CreateKubernetesRole => ()
      case _: Vault.DeleteKubernetesRole => ()
    })
  }

  lazy val testSlack: SlackOp ~> IO = new (SlackOp ~> IO) {
    import SlackOp._
    def apply[A](op: SlackOp[A]): IO[A] = op match {
      case SendSlackNotification(channels, msg) =>
        IO.unit
    }
  }

  lazy val testEmail: EmailOp ~> IO = new (EmailOp ~> IO) {
    import EmailOp._
    def apply[A](op: EmailOp[A]): IO[A] = op match {
      case SendEmailNotification(r,m,s) =>
        IO.unit
    }
  }

  import docker._
  lazy val testDocker = new (DockerOp ~> IO) {
    def apply[A](op: DockerOp[A]) = op match {
      case DockerOp.Pull(i) =>
        IO((0, Nil))
      case DockerOp.Extract(unit) =>
        IO(Docker.Image(unit.name,None))
      case DockerOp.Push(i) =>
        IO((0, Nil))
      case DockerOp.Tag(i, r) =>
        IO((0, i))
    }
  }

  import scheduler._
  lazy val sched = new (SchedulerOp ~> IO) {
    import scheduler.SchedulerOp._
    def apply[A](op: SchedulerOp[A]) = op match {
      case Launch(i,dc,ns,unit,e,hash) =>
        val name = Manifest.Versioned.unwrap(unit).name
        val sn = Datacenter.StackName(name, unit.version,hash)
        IO(sn.toString)
      case Delete(dc,d) =>
        IO.unit
      case Summary(dc,ns,sn) =>
        IO(None)
    }
  }

  import logging._
  lazy val logger = new (LoggingOp ~> IO) {
    import LoggingOp._
    def apply[A](op: LoggingOp[A]) = op match {
      case Info(msg) =>
        IO.unit
      case Debug(msg) =>
        IO.unit
      case LogToFile(id, msg) =>
        IO.unit
    }
  }

  import health._
  lazy val healthI = new (HealthCheckOp ~> IO) {
    import HealthCheckOp._
    def apply[A](op: HealthCheckOp[A]): IO[A] = op match {
      case Health(dc,ns,sn) => IO.pure(Nil)
    }
  }

  lazy val stg = TestStorage.storage(testName)

  lazy val testInterpreters = Infrastructure.Interpreters(
    sched,testConsul,testVault,stg,logger,testDocker,WorkflowControlOp.trans,healthI)

  lazy val configFiles = List(
    Required(ClassPathResource("nelson/defaults.cfg")),
    Required(ClassPathResource("nelson/nelson-test.cfg")),
    Required(ClassPathResource("nelson/datacenters.cfg"))
  )

  lazy val config = knobs.loadImmutable[IO](configFiles).flatMap(Config.readConfig(_, NelsonSuite.testHttp, TestStorage.xa _))
    .unsafeRunSync()
    .copy( // Configure a minimal set of things in code. Otherwise, we want to test our config.
      database = dbConfig, // let each suite get its own h2
      dockercfg = DockerConfig(sys.env.getOrElse("DOCKER_HOST", "unix:///var/run/docker.sock"), true),
      datacenters = List(datacenter(testName).copy(interpreters = testInterpreters)),
      interpreters = Interpreters(GitFixtures.interpreter,stg,Some(testSlack),Some(testEmail))
    )
}

object NelsonSuite {
  val testHttp = (_: BlazeClientConfig) => Http1Client[IO]()
}

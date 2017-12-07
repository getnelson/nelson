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

import helm.ConsulOp
import notifications.{SlackOp,EmailOp}
import doobie.imports._
import scalaz._, Scalaz._
import org.scalatest.{FlatSpec,Matchers,BeforeAndAfterAll}
import scalaz.IMap
import scalaz.concurrent.Task
import knobs._
import java.util.concurrent.{Executors, ThreadFactory}
import dispatch.Http

trait NelsonSuite
    extends FlatSpec
    with Matchers
    with RoutingFixtures
    with BeforeAndAfterAll {

  val testName: String = getClass.getSimpleName

  def trunc: ConnectionIO[Unit] = (
    sql"SET REFERENTIAL_INTEGRITY FALSE; -- YOLO".update.run >>
    sql"TRUNCATE TABLE traffic_shifts".update.run >>
    sql"TRUNCATE TABLE traffic_shift_start".update.run >>
    sql"TRUNCATE TABLE traffic_shift_reverse".update.run >>
    sql"TRUNCATE TABLE deployment_statuses".update.run >>
    sql"TRUNCATE TABLE deployments".update.run >>
    sql"TRUNCATE TABLE unit_dependencies".update.run >>
    sql"TRUNCATE TABLE service_ports".update.run >>
    sql"TRUNCATE TABLE units".update.run >>
    sql"TRUNCATE TABLE loadbalancer_routes".update.run >>
    sql"TRUNCATE TABLE loadbalancer_deployments".update.run >>
    sql"TRUNCATE TABLE loadbalancers".update.run >>
    sql"TRUNCATE TABLE releases".update.run >>
    sql"TRUNCATE TABLE namespaces".update.run >>
    sql"TRUNCATE TABLE domains".update.run >>
    sql"SET REFERENTIAL_INTEGRITY TRUE; -- COYOLO".update.run
  ).void

  val dbConfig = TestStorage.dbConfig(testName)

  override def beforeAll: Unit = {
    trunc.transact(stg.xa).run
  }

  /**
   *  a map of string to pretend will be found in consul. override
   *  this if you need your tests to see consul data
   */
  def consulMap: Map[String, String] = Map.empty

  lazy val testConsul: ConsulOp ~> Task = new NaturalTransformation[ConsulOp, Task] {
    @volatile var kvs: Map[String,String] = consulMap
    import helm.Key
    def apply[A](a: ConsulOp[A]): Task[A] = a match {
      case ConsulOp.Get(key: Key) => Task.delay(Some(kvs(key)))
      case ConsulOp.Set(key: Key, value: String) => Task.delay(kvs = kvs + (key -> value))
      case ConsulOp.Delete(key: Key) => Task.delay(kvs = kvs - key)
      case ConsulOp.ListKeys(prefix: Key) => Task.delay(kvs.keySet.filter(_.startsWith(prefix)))
      case ConsulOp.HealthCheck(service: String) => Task.delay(kvs(s"health/$service"))
    }
  }

  import vault._
  val testVault: Vault ~> Task = new (Vault ~> Task) {
    def apply[A](v: Vault[A]): Task[A] = Task.now(v match {
      case Vault.IsInitialized => true
      case Vault.Initialize(init) => InitialCreds(Nil, RootToken("fake"))
      case Vault.GetSealStatus => SealStatus(false, 0, 0, 0)
      case Vault.Seal => ()
      case Vault.Unseal(key) => SealStatus(false, 0, 0, 0)
      case Vault.Get(path) => "fake"
      case Vault.Set(path, value) => ()
      case Vault.CreatePolicy(_,_) => ()
      case Vault.DeletePolicy(_) => ()
      case Vault.GetMounts => IMap.empty
      case _: Vault.CreateToken => vault.Token("aaaaaaaa-bbbb-cccc-dddddddddddd")
    })
  }

  lazy val testSlack: SlackOp ~> Task = new NaturalTransformation[SlackOp, Task] {
    import SlackOp._
    def apply[A](op: SlackOp[A]): Task[A] = op match {
      case SendSlackNotification(channels, msg) =>
        Task.now(())
    }
  }

  lazy val testEmail: EmailOp ~> Task = new NaturalTransformation[EmailOp, Task] {
    import EmailOp._
    def apply[A](op: EmailOp[A]): Task[A] = op match {
      case SendEmailNotification(r,m,s) =>
        Task.now(())
    }
  }

  import docker._
  lazy val testDocker = new (DockerOp ~> Task) {
    def apply[A](op: DockerOp[A]) = op match {
      case DockerOp.Pull(i) =>
        Task.delay((0, Nil))
      case DockerOp.Extract(unit) =>
        Task.delay(Docker.Image(unit.name,None))
      case DockerOp.Push(i) =>
        Task.delay((0, Nil))
      case DockerOp.Tag(i, r) =>
        Task.delay((0, i))
    }
  }

  import scheduler._
  lazy val nomad = new (SchedulerOp ~> Task) {
    import scheduler.SchedulerOp._
    def apply[A](op: SchedulerOp[A]) = op match {
      case Launch(i,dc,ns,unit,e,hash) =>
        val name = Manifest.Versioned.unwrap(unit).name
        val sn = Domain.StackName(name, unit.version,hash)
        Task.delay(sn.toString)
      case Delete(dc,d) =>
        Task.delay(())
      case Summary(dc,ns) =>
        Task.delay(None)
      case RunningUnits(dc, prefix) =>
        Task.now(Set.empty)
      case Allocations(dc, prefix) =>
        Task.now(nil)
      case EquivalentStatus(s, l) =>
        Task.now(false)
    }
  }

  import logging._
  lazy val logger = new (LoggingOp ~> Task) {
    import LoggingOp._
    def apply[A](op: LoggingOp[A]) = op match {
      case Info(msg) =>
        Task.delay(())
      case Debug(msg) =>
        Task.delay(())
      case LogToFile(id, msg) =>
        Task.delay(())
    }
  }

  lazy val stg = TestStorage.storage(testName)

  lazy val testInterpreters = Infrastructure.Interpreters(
    nomad,testConsul,testVault,stg,logger,testDocker,WorkflowControlOp.trans)

  lazy val config = knobs.loadImmutable(List(
    Required(ClassPathResource("nelson/defaults.cfg")),
    Required(ClassPathResource("nelson/nelson-test.cfg")),
    Required(ClassPathResource("nelson/domains.cfg"))
  )).map(Config.readConfig(_, NelsonSuite.testHttp, TestStorage.xa _))
    .run
    .copy( // Configure a minimal set of things in code. Otherwise, we want to test our config.
      database = dbConfig, // let each suite get its own h2
      dockercfg = DockerConfig(sys.env.getOrElse("DOCKER_HOST", "unix:///var/run/docker.sock"), true),
      domains = List(domain(testName).copy(interpreters = testInterpreters)),
      interpreters = Interpreters(GitFixtures.interpreter,stg,Some(testSlack),Some(testEmail))
    )
}

object NelsonSuite {
  val testHttp = {
    // Creating a new HTTP client per suite is gruesomely expensive,
    // even if we shut down after every suite.  Using the global one
    // fails because it uses non-daemon threads, and we have no hook
    // to shut them down.  So we create a global HTTP client here for
    // tests, with a daemon-threaded executor, to work around the lack
    // of a shutdown.
    //
    // The better answer is to finish stripping out Dispatch.
    val executor = Executors.newCachedThreadPool(new ThreadFactory {
      def newThread(r: Runnable) = {
        val t = new Thread(r, "AsyncHttpClient-Callback")
        t.setDaemon(true)
        t
      }
    })
    (new Http).configure(_.setExecutorService(executor))
  }
}

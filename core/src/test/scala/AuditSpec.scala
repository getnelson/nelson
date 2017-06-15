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

import scala.concurrent.duration.DurationInt
import org.scalatest._
import scalaz.stream.Process
import scalaz.stream.async.mutable.{Queue}
import scalaz.stream.async
import scalaz.concurrent.{Task, Strategy}
import scala.concurrent.SyncVar
import argonaut._, Argonaut._
import Fixtures._
import doobie.imports._
import scalaz._, Scalaz._


class AuditSpec extends NelsonSuite with BeforeAndAfterEach {

  import Json._
  import audit._
  import AuditableInstances._

  case class Foo(n: Int)
  val encoder: EncodeJson[Foo] = casecodec1(Foo.apply, Foo.unapply)("n")
  case class Bar(n: Int)
  val encoder2: EncodeJson[Bar] = casecodec1(Bar.apply, Bar.unapply)("n")
  val storage = TestStorage.storage("AuditSpec")
  val defaultSystemLogin = "nelson"

  implicit val fooAuditable = new Auditable[Foo] {
    def encode(foo: Foo): Json = encoder.encode(foo)
    def category = InfoCategory
  }

  implicit val barAuditable = new Auditable[Bar] {
    def encode(bar: Bar): Json = encoder2.encode(bar)
    def category = DeploymentCategory
  }

  val truncEvery = { sql"DELETE FROM audit_log".update.run }.void

  val setup = for { _ <- truncEvery.transact(storage.xa) } yield ()

  override def afterAll: Unit = {
    config.auditQueue.close.run
    super.afterAll
  }

  override def beforeEach: Unit = setup.run

  it should "enqueue all events in the stream" in {
    val audit = new Auditor(config.auditQueue,defaultSystemLogin)
    val p: Process[Task, Foo] = Process(Foo(1),Foo(2),Foo(3),Foo(10))

    p.observe(audit.auditSink(LoggingAction)).run.run

    val vec = audit.process(storage).take(4).runLog.run

    vec.length should equal (vec.length)
  }

  it should "store auditable events in storage" in {
    val audit = new Auditor(config.auditQueue,defaultSystemLogin)
    val events = Vector(Foo(1),Foo(2),Foo(3),Foo(10))
    val p: Process[Task, Foo] = Process(events: _*)

    p.observe(audit.auditSink(LoggingAction)).run.run

    audit.process(storage).take(4).runLog.run

    val ev = nelson.storage.run(storage, nelson.storage.StoreOp.listAuditLog(10, 0)).run

    ev.length should equal (events.length)
  }

  it should "be able to write to audit log directly" in {
    val audit = new Auditor(config.auditQueue, defaultSystemLogin)
    val foo = Foo(1)

    audit.write(foo, CreateAction).run
    audit.write(foo, CreateAction).run

    audit.process(storage).take(2).run.run

    val ev = nelson.storage.run(storage, nelson.storage.StoreOp.listAuditLog(10, 0)).run

    ev.length should equal (2)
  }

  it should "accept an optional release id parameter" in {
    val audit = new Auditor(config.auditQueue,defaultSystemLogin)
    val foo = Foo(1)

    val releaseId = Option(10L)

    audit.write(foo, CreateAction, releaseId = releaseId).run
    audit.process(storage).take(1).run.run
    val ev = nelson.storage.run(storage, nelson.storage.StoreOp.listAuditLog(10, 0)).run


    ev.length should === (1)
    val auditLog: AuditLog = ev.head
    auditLog.releaseId should === (releaseId)
  }

  it should "accept an optional user parameter" in {
    val login = "scalatest"
    val audit = new Auditor(config.auditQueue,defaultSystemLogin)
    val foo = Foo(1)

    audit.write(foo, CreateAction, login = login).run
    audit.process(storage).take(1).run.run
    val ev = nelson.storage.run(storage, nelson.storage.StoreOp.listAuditLog(10, 0)).run


    ev.length should === (1)
    val auditLog = ev.head
    auditLog.login should === (Option(login))
  }

  behavior of "listing audit logs"

  it should "accept an optional action parameter for filtering" in {
    val audit = new Auditor(config.auditQueue, defaultSystemLogin)
    val foo = Foo(1)

    audit.write(foo, CreateAction).run
    audit.write(foo, DeleteAction).run

    audit.process(storage).take(2).run.run

    val ev = nelson.storage.run(storage, nelson.storage.StoreOp.listAuditLog(10, 0, action = Option("create"))).run

    ev.length should equal (1)
  }

  it should "accept an optional category parameter for filtering" in {
    val audit = new Auditor(config.auditQueue, defaultSystemLogin)
    val foo = Foo(1)
    val bar = Bar(2)

    audit.write(foo, CreateAction).run
    audit.write(bar, CreateAction).run

    audit.process(storage).take(2).run.run

    val ev = nelson.storage.run(storage, nelson.storage.StoreOp.listAuditLog(10, 0, category = Option("deploy"))).run

    ev.length should equal (1)
  }
}

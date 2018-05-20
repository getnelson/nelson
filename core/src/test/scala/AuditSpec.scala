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

import argonaut._, Argonaut._

import cats.effect.{Effect, IO}
import cats.implicits._

import fs2.Stream

import doobie.implicits._

import org.scalatest._

class AuditSpec extends NelsonSuite with BeforeAndAfterEach {

  import Json._
  import audit._

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

  override def beforeEach: Unit = setup.unsafeRunSync()

  it should "enqueue all events in the stream" in {
    val audit = new Auditor(config.auditQueue,defaultSystemLogin)
    val p: Stream[IO, Foo] = Stream(Foo(1),Foo(2),Foo(3),Foo(10))

    p.observe(audit.auditSink(LoggingAction))(Effect[IO], config.pools.defaultExecutor).compile.drain.unsafeRunSync()

    val vec = audit.process(storage)(config.pools.defaultExecutor).take(4).compile.toVector.unsafeRunSync()

    vec.length should equal (vec.length)
  }

  it should "store auditable events in storage" in {
    val audit = new Auditor(config.auditQueue,defaultSystemLogin)
    val events = Vector(Foo(1),Foo(2),Foo(3),Foo(10))
    val p: Stream[IO, Foo] = Stream(events: _*)

    p.observe(audit.auditSink(LoggingAction))(Effect[IO], config.pools.defaultExecutor).compile.drain.unsafeRunSync()

    audit.process(storage)(config.pools.defaultExecutor).take(4).compile.toVector.unsafeRunSync()

    val ev = nelson.storage.StoreOp.listAuditLog(10, 0).foldMap(storage).unsafeRunSync()

    ev.length should equal (events.length)
  }

  it should "be able to write to audit log directly" in {
    val audit = new Auditor(config.auditQueue, defaultSystemLogin)
    val foo = Foo(1)

    audit.write(foo, CreateAction).unsafeRunSync()
    audit.write(foo, CreateAction).unsafeRunSync()

    audit.process(storage)(config.pools.defaultExecutor).take(2).compile.drain.unsafeRunSync()

    val ev = nelson.storage.StoreOp.listAuditLog(10, 0).foldMap(storage).unsafeRunSync()

    ev.length should equal (2)
  }

  it should "accept an optional release id parameter" in {
    val audit = new Auditor(config.auditQueue,defaultSystemLogin)
    val foo = Foo(1)

    val releaseId = Option(10L)

    audit.write(foo, CreateAction, releaseId = releaseId).unsafeRunSync()
    audit.process(storage)(config.pools.defaultExecutor).take(1).compile.drain.unsafeRunSync()
    val ev = nelson.storage.StoreOp.listAuditLog(10, 0).foldMap(storage).unsafeRunSync()


    ev.length should === (1)
    val auditLog: AuditLog = ev.head
    auditLog.releaseId should === (releaseId)
  }

  it should "accept an optional user parameter" in {
    val login = "scalatest"
    val audit = new Auditor(config.auditQueue,defaultSystemLogin)
    val foo = Foo(1)

    audit.write(foo, CreateAction, login = login).unsafeRunSync()
    audit.process(storage)(config.pools.defaultExecutor).take(1).compile.drain.unsafeRunSync()
    val ev = nelson.storage.StoreOp.listAuditLog(10, 0).foldMap(storage).unsafeRunSync()


    ev.length should === (1)
    val auditLog = ev.head
    auditLog.login should === (Option(login))
  }

  behavior of "listing audit logs"

  it should "accept an optional action parameter for filtering" in {
    val audit = new Auditor(config.auditQueue, defaultSystemLogin)
    val foo = Foo(1)

    audit.write(foo, CreateAction).unsafeRunSync()
    audit.write(foo, DeleteAction).unsafeRunSync()

    audit.process(storage)(config.pools.defaultExecutor).take(2).compile.drain.unsafeRunSync()

    val ev = nelson.storage.StoreOp.listAuditLog(10, 0, action = Option("create")).foldMap(storage).unsafeRunSync()

    ev.length should equal (1)
  }

  it should "accept an optional category parameter for filtering" in {
    val audit = new Auditor(config.auditQueue, defaultSystemLogin)
    val foo = Foo(1)
    val bar = Bar(2)

    audit.write(foo, CreateAction).unsafeRunSync()
    audit.write(bar, CreateAction).unsafeRunSync()

    audit.process(storage)(config.pools.defaultExecutor).take(2).compile.drain.unsafeRunSync()

    val ev = nelson.storage.StoreOp.listAuditLog(10, 0, category = Option("deploy")).foldMap(storage).unsafeRunSync()

    ev.length should equal (1)
  }
}

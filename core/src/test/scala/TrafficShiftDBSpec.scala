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

import doobie.imports._
import scalaz._,Scalaz._
import storage.{run => runs, StoreOp}
import org.scalatest.{BeforeAndAfterEach}
import scala.concurrent.duration._
import Domain.{StackName}
import java.time.Instant

class TrafficShiftDBSpec extends NelsonSuite with BeforeAndAfterEach {

  override def beforeAll(): Unit = {
    super.beforeAll()
    runs(config.storage, insertFixtures(testName)).run
    ()
  }

  override def beforeEach: Unit = {
   (sql"SET REFERENTIAL_INTEGRITY FALSE; -- YOLO".update.run >>
    sql"TRUNCATE TABLE traffic_shift_reverse".update.run >>
    sql"TRUNCATE TABLE traffic_shift_start".update.run >>
    sql"SET REFERENTIAL_INTEGRITY TRUE; -- COYOLO".update.run).void.transact(stg.xa).run
  }

  it should "start a traffic shift" in {
    // inventory is currently in a traffic split, 1.2.3 is the `to` target
    val inventory1 = runs(config.storage, StoreOp.findDeployment(StackName("inventory", Version(1,2,2), "ffff"))).run.get
    val inventory2 = runs(config.storage, StoreOp.findDeployment(StackName("inventory", Version(1,2,3), "ffff"))).run.get
    val res = storage.run(config.storage, StoreOp.startTrafficShift(inventory1.id, inventory2.id, Instant.now.minusSeconds(120))).run
    res.isDefined should equal(true)
  }

  it should "start a traffic shift reverse" in {
    // inventory is currently in a traffic split, 1.2.3 is the `to` target
    val inventory1 = runs(config.storage, StoreOp.findDeployment(StackName("inventory", Version(1,2,2), "ffff"))).run.get
    val inventory2 = runs(config.storage, StoreOp.findDeployment(StackName("inventory", Version(1,2,3), "ffff"))).run.get
    storage.run(config.storage, StoreOp.startTrafficShift(inventory1.id, inventory2.id, Instant.now.minusSeconds(30))).run
    val res = storage.run(config.storage, StoreOp.reverseTrafficShift(inventory2.id, Instant.now.minusSeconds(15))).run
    res.isDefined should equal(true)
  }

  it should "noop when trying to start a traffic shift in progress" in {
    // inventory is currently in a traffic split, 1.2.3 is the `to` target
    val inventory1 = runs(config.storage, StoreOp.findDeployment(StackName("inventory", Version(1,2,2), "ffff"))).run.get
    val inventory2 = runs(config.storage, StoreOp.findDeployment(StackName("inventory", Version(1,2,3), "ffff"))).run.get
    val id = storage.run(config.storage, StoreOp.startTrafficShift(inventory1.id, inventory2.id, Instant.now.minusSeconds(30))).run
    val id2 = storage.run(config.storage, StoreOp.startTrafficShift(inventory1.id, inventory2.id, Instant.now.minusSeconds(30))).run

    id should equal(id2)
  }

  it should "noop when trying to reverse a traffic shift that's already been reversed" in {
    // inventory is currently in a traffic split, 1.2.3 is the `to` target
    val inventory1 = runs(config.storage, StoreOp.findDeployment(StackName("inventory", Version(1,2,2), "ffff"))).run.get
    val inventory2 = runs(config.storage, StoreOp.findDeployment(StackName("inventory", Version(1,2,3), "ffff"))).run.get
    storage.run(config.storage, StoreOp.startTrafficShift(inventory1.id, inventory2.id, Instant.now.minusSeconds(30))).run
    val id = storage.run(config.storage, StoreOp.reverseTrafficShift(inventory2.id, Instant.now.minusSeconds(30))).run
    val id2 = storage.run(config.storage, StoreOp.reverseTrafficShift(inventory2.id, Instant.now.minusSeconds(30))).run

    id should equal(id2)
  }

  it should "get the latest traffic shift after traffic shift has started" in {
    val inventory1 = runs(config.storage, StoreOp.findDeployment(StackName("inventory", Version(1,2,2), "ffff"))).run.get
    val inventory2 = runs(config.storage, StoreOp.findDeployment(StackName("inventory", Version(1,2,3), "ffff"))).run.get

    // gotta start traffic shift
    storage.run(config.storage, StoreOp.startTrafficShift(inventory1.id, inventory2.id, Instant.now.minusSeconds(120))).run

    val res = storage.run(config.storage, StoreOp.getTrafficShiftForServiceName(inventory2.nsid, inventory2.unit.serviceName)).run
    res.map(_.to) should equal(Some(inventory2))
  }

  it should "not get the latest traffic shift if traffic shift hasn't started" in {
    val inventory = runs(config.storage, StoreOp.findDeployment(StackName("inventory", Version(1,2,3), "ffff"))).run.get
    val res = storage.run(config.storage, StoreOp.getTrafficShiftForServiceName(inventory.nsid, inventory.unit.serviceName)).run
    // traffic shift wasn't started so nothing should be returned
    res.map(_.to) should equal(None)
  }

  it should "not get the latest traffic shift if either the from or to deployment is in an un-routable state" in {
    val inventory1 = runs(config.storage, StoreOp.findDeployment(StackName("inventory", Version(1,2,2), "ffff"))).run.get
    val inventory2 = runs(config.storage, StoreOp.findDeployment(StackName("inventory", Version(1,2,3), "ffff"))).run.get

    storage.run(config.storage, StoreOp.startTrafficShift(inventory1.id, inventory2.id, Instant.now.minusSeconds(30))).run

    val res = storage.run(config.storage, StoreOp.getTrafficShiftForServiceName(inventory1.nsid, inventory1.unit.serviceName)).run
    res.isDefined should equal(true)

    runs(config.storage, StoreOp.createDeploymentStatus(inventory1.id, DeploymentStatus.Terminated, None)).run
    val res1 = storage.run(config.storage, StoreOp.getTrafficShiftForServiceName(inventory1.nsid, inventory1.unit.serviceName)).run
    res1 should equal(None)

    runs(config.storage, StoreOp.createDeploymentStatus(inventory1.id, DeploymentStatus.Ready, None)).run
    runs(config.storage, StoreOp.createDeploymentStatus(inventory2.id, DeploymentStatus.Terminated, None)).run
    val res2 = storage.run(config.storage, StoreOp.getTrafficShiftForServiceName(inventory1.nsid, inventory1.unit.serviceName)).run
    res2 should equal(None)

    runs(config.storage, StoreOp.createDeploymentStatus(inventory1.id, DeploymentStatus.Ready, None)).run
    runs(config.storage, StoreOp.createDeploymentStatus(inventory2.id, DeploymentStatus.Ready, None)).run
    val res3 = storage.run(config.storage, StoreOp.getTrafficShiftForServiceName(inventory1.nsid, inventory1.unit.serviceName)).run
    res3.isDefined should equal(true)
  }

  // nelson traffic shift reverse

  it should "start traffic shift reverse from nelson if everything validates" in {
    // inventory is currently in a traffic split, 1.2.3 is the `to` target
    val inventory1 = runs(config.storage, StoreOp.findDeployment(StackName("inventory", Version(1,2,2), "ffff"))).run.get
    val inventory2 = runs(config.storage, StoreOp.findDeployment(StackName("inventory", Version(1,2,3), "ffff"))).run.get
    storage.run(config.storage, StoreOp.startTrafficShift(inventory1.id, inventory2.id, Instant.now.minusSeconds(30))).run
    val res = Nelson.reverseTrafficShift(inventory2.guid).run(config).attempt.run
    res.toOption.isDefined should equal(true)
  }

  it should "not reverse traffic shift that has already been reversed" in {
    // inventory is currently in a traffic split, 1.2.3 is the `to` target
    val inventory1 = runs(config.storage, StoreOp.findDeployment(StackName("inventory", Version(1,2,2), "ffff"))).run.get
    val inventory2 = runs(config.storage, StoreOp.findDeployment(StackName("inventory", Version(1,2,3), "ffff"))).run.get

    storage.run(config.storage, StoreOp.startTrafficShift(inventory1.id, inventory2.id, Instant.now.minusSeconds(30))).run

    val res1 = Nelson.reverseTrafficShift(inventory2.guid).run(config).attempt.run
    res1.toOption.isDefined should equal(true)

    // try it again, this should fail
    val res2 = Nelson.reverseTrafficShift(inventory2.guid).run(config).attempt.run
    res2 should equal(-\/(InvalidTrafficShiftReverse("can't reverse a traffic shift that's already been reversed")))
  }

  it should "not reverse a traffic shift that that is not currently in progress" in {
    val ab = runs(config.storage, for {
      ab1 <- StoreOp.findDeployment(StackName("ab", Version(2,2,2), "abcd")).map(_.get)
      ab2 <- StoreOp.findDeployment(StackName("ab", Version(2,2,1), "abcd")).map(_.get)
      _   <- StoreOp.createTrafficShift(ab2.nsid, ab2, LinearShiftPolicy, 1.minutes)
      // traffic shift duration is 1 minute, so traffic shift in not in progress
      _   <- StoreOp.startTrafficShift(ab1.id, ab2.id, Instant.now.minusSeconds(120))
    } yield ab2).run

    val res = Nelson.reverseTrafficShift(ab.guid).run(config).attempt.run

    res should equal(-\/(InvalidTrafficShiftReverse("can't reverse a traffic shift that is currently not in progress")))
  }
}

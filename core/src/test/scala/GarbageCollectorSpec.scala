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
import scalaz.concurrent.Task
import scalaz.syntax.monad._
import scalaz.std.list._
import scalaz.stream._
import org.scalatest.{FlatSpec,Matchers,BeforeAndAfterAll,BeforeAndAfterEach}
import scala.concurrent.duration._
import java.time.Instant

class GarbageCollectorSpec extends NelsonSuite with BeforeAndAfterEach {
  import cleanup._
  import storage.{run=>runs, StoreOp}
  import Datacenter._
  import routing._
  import quiver._
  import DeploymentStatus.Ready

  override def beforeAll(): Unit = {
    super.beforeAll()
    storage.run(config.storage, insertFixtures(testName)).run
  }

  override def beforeEach: Unit = {
    sql"DELETE FROM deployment_expiration".update.run.void.transact(stg.xa).run
  }

  val dc = config.datacenters.head

  it should "should identify expired deployments" in {
    val st = StackName("search", Version(1,1,0), "foo")
    val su = runs(config.storage, StoreOp.findDeployment(st)).run.get
    val ctx = DeploymentCtx(su, Ready, Some(Instant.now().minusSeconds(1000)))

    GarbageCollector.expired(ctx) should equal (true)
  }

  it should "should identify un-expired deployments" in {
    val st = StackName("search", Version(1,1,0), "foo")
    val su = runs(config.storage, StoreOp.findDeployment(st)).run.get
    val ctx = DeploymentCtx(su, Ready, Some(Instant.now().plusSeconds(1000)))

    GarbageCollector.expired(ctx) should equal (false)
  }

  it should "mark service as garbage in the database" in {
    val st = StackName("search", Version(1,1,0), "foo")
    val su = runs(config.storage, StoreOp.findDeployment(st)).run.get
    val ctx = DeploymentCtx(su, Ready, Some(Instant.now().minusSeconds(1000)))

    val ns = runs(config.storage, StoreOp.listNamespacesForDatacenter(testName)).run.head
    Process.eval(Task.now(((dc,ns,ctx)))).through(GarbageCollector.mark(config)).runLog.run

    val status = runs(config.storage, StoreOp.getDeploymentStatus(su.id)).run
    status should equal(Some(DeploymentStatus.Garbage))
  }

  it should "update expiration before running garbage collection process" in {
    val st = StackName("search", Version(1,1,0), "foo")
    val su = runs(config.storage, StoreOp.findDeployment(st)).run.get
    runs(config.storage, StoreOp.createDeploymentStatus(su.id, DeploymentStatus.Ready, None)).run
    val ctx = DeploymentCtx(su, Ready, Some(Instant.now().minusSeconds(1000))) // expire it

    val in = StackName("inventory", Version(1,2,2), "ffff")
    val ind = runs(config.storage, StoreOp.findDeployment(in)).run.get

    val emptyG = quiver.empty[RoutingNode,Unit,RoutePath]

    val ns = runs(config.storage, StoreOp.listNamespacesForDatacenter(testName)).run.head

    val g = emptyG &
      Context(Vector(), RoutingNode(ind), (), Vector()) &
      Context(Vector((RoutePath(ind,"","",80,80),RoutingNode(su))), RoutingNode(su), (), Vector())

    Process.eval(Task.now(((dc,ns,ctx,g))))
      .through(ExpirationPolicyProcess.expirationProcess(config))
      .filter(d => GarbageCollector.expired(d._3))
      .through(GarbageCollector.mark(config)).runLog.run

    val status = runs(config.storage, StoreOp.getDeploymentStatus(su.id)).run
    status should equal(Some(Ready))
  }
}

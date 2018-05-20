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

import doobie.implicits._
import org.scalatest.BeforeAndAfterEach
import java.time.Instant
import cats.effect.IO
import cats.implicits._
import fs2.Stream

class GarbageCollectorSpec extends NelsonSuite with BeforeAndAfterEach {
  import cleanup._
  import storage.StoreOp
  import Datacenter._
  import routing._
  import quiver._
  import DeploymentStatus.Ready

  override def beforeAll(): Unit = {
    super.beforeAll()
    insertFixtures(testName).foldMap(config.storage).unsafeRunSync()
    ()
  }

  override def beforeEach: Unit = {
    sql"DELETE FROM deployment_expiration".update.run.void.transact(stg.xa).unsafeRunSync()
  }

  val dc = config.datacenters.head

  val gr = quiver.empty[RoutingNode,Unit,RoutePath]

  it should "should identify expired deployments" in {
    val st = StackName("search", Version(1,1,0), "foo")
    val su = StoreOp.findDeployment(st).foldMap(config.storage).unsafeRunSync().get
    val ctx = DeploymentCtx(su, Ready, Some(Instant.now().minusSeconds(1000)))

    GarbageCollector.expired(ctx) should equal (true)
  }

  it should "should identify un-expired deployments" in {
    val st = StackName("search", Version(1,1,0), "foo")
    val su = StoreOp.findDeployment(st).foldMap(config.storage).unsafeRunSync().get
    val ctx = DeploymentCtx(su, Ready, Some(Instant.now().plusSeconds(1000)))

    GarbageCollector.expired(ctx) should equal (false)
  }

  it should "mark service as garbage in the database" in {
    val st = StackName("search", Version(1,1,0), "foo")
    val su = StoreOp.findDeployment(st).foldMap(config.storage).unsafeRunSync().get
    val ctx = DeploymentCtx(su, Ready, Some(Instant.now().minusSeconds(1000)))

    val ns = StoreOp.listNamespacesForDatacenter(testName).foldMap(config.storage).unsafeRunSync().head
    Stream.eval(IO.pure(((dc,ns,ctx,gr)))).through(GarbageCollector.mark(config)).compile.toVector.unsafeRunSync()

    val status = StoreOp.getDeploymentStatus(su.id).foldMap(config.storage).unsafeRunSync()
    status should equal(Some(DeploymentStatus.Garbage))
  }

  it should "update expiration before running garbage collection process" in {
    val st = StackName("search", Version(1,1,0), "foo")
    val su = StoreOp.findDeployment(st).foldMap(config.storage).unsafeRunSync().get
    StoreOp.createDeploymentStatus(su.id, DeploymentStatus.Ready, None).foldMap(config.storage).unsafeRunSync()
    val ctx = DeploymentCtx(su, Ready, Some(Instant.now().minusSeconds(1000))) // expire it

    val in = StackName("inventory", Version(1,2,2), "ffff")
    val ind = StoreOp.findDeployment(in).foldMap(config.storage).unsafeRunSync().get

    val emptyG = quiver.empty[RoutingNode,Unit,RoutePath]

    val ns = StoreOp.listNamespacesForDatacenter(testName).foldMap(config.storage).unsafeRunSync().head

    val g = emptyG &
      Context(Vector(), RoutingNode(ind), (), Vector()) &
      Context(Vector((RoutePath(ind,"","",80,80),RoutingNode(su))), RoutingNode(su), (), Vector())

    Stream.eval(IO.pure((dc,ns,ctx,g)))
      .through(ExpirationPolicyProcess.expirationProcess(config))
      .filter(d => GarbageCollector.expired(d._3))
      .through(GarbageCollector.mark(config)).compile.toVector.unsafeRunSync()

    val status = StoreOp.getDeploymentStatus(su.id).foldMap(config.storage).unsafeRunSync()
    status should equal(Some(Ready))
  }
}

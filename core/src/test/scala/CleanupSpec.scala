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

class CleanupSpec extends NelsonSuite  {
  import Datacenter._
  import storage.StoreOp

  override def beforeAll(): Unit = {
    super.beforeAll()
    insertFixtures(testName).foldMap(config.storage).unsafeRunSync()
    ()
  }

  it should "run the entire cleanup pipeline and mark expired services as terminated" in {
    import cleanup._
    val st = StackName("search", Version(1,1,0), "foo")
    val su = StoreOp.findDeployment(st).foldMap(config.storage).unsafeRunSync().get

    StoreOp.createDeploymentStatus(su.id, DeploymentStatus.Ready, None).foldMap(config.storage).unsafeRunSync()
    val statusBefore = StoreOp.getDeploymentStatus(su.id).foldMap(config.storage).unsafeRunSync()
    statusBefore should equal(Some(DeploymentStatus.Ready))

    StoreOp.createDeploymentExpiration(su.id, java.time.Instant.now().minusSeconds(1000)).foldMap(config.storage).unsafeRunSync()

    (CleanupCron.process(config) to Reaper.reap(config)).compile.toVector.unsafeRunSync()

    val status = StoreOp.getDeploymentStatus(su.id).foldMap(config.storage).unsafeRunSync()
    status should equal(Some(DeploymentStatus.Terminated))
  }

  it should "run the entire cleanup pipeline and not mark active services as terminated" in {
    import cleanup._
    val st = StackName("search", Version(1,1,0), "foo")
    val su = StoreOp.findDeployment(st).foldMap(config.storage).unsafeRunSync().get

    StoreOp.createDeploymentStatus(su.id, DeploymentStatus.Ready, None).foldMap(config.storage).unsafeRunSync()
    val statusBefore = StoreOp.getDeploymentStatus(su.id).foldMap(config.storage).unsafeRunSync()
    statusBefore should equal(Some(DeploymentStatus.Ready))

    StoreOp.createDeploymentExpiration(su.id, java.time.Instant.now().plusSeconds(1000)).foldMap(config.storage).unsafeRunSync()

    (CleanupCron.process(config) to Reaper.reap(config)).compile.toVector.unsafeRunSync()

    val status = StoreOp.getDeploymentStatus(su.id).foldMap(config.storage).unsafeRunSync()
    status should equal(Some(DeploymentStatus.Ready))
  }

  it should "run the entire cleanup pipeline and not apply an expiration policy to non routable deployments" in {
    import cleanup._
    val st = StackName("search", Version(2,2,2), "aaaa")
    val su = StoreOp.findDeployment(st).foldMap(config.storage).unsafeRunSync().get

    StoreOp.createDeploymentStatus(su.id, DeploymentStatus.Warming, None).foldMap(config.storage).unsafeRunSync()
    val statusBefore = StoreOp.getDeploymentStatus(su.id).foldMap(config.storage).unsafeRunSync()
    statusBefore should equal(Some(DeploymentStatus.Warming))

    val exp = java.time.Instant.now().plusSeconds(1000)

    StoreOp.createDeploymentExpiration(su.id, exp).foldMap(config.storage).unsafeRunSync()

    CleanupCron.process(config).compile.toVector.unsafeRunSync()

    val expAfter = StoreOp.findDeploymentExpiration(su.id).foldMap(config.storage).unsafeRunSync()
    expAfter should equal(Some(exp))
  }
}

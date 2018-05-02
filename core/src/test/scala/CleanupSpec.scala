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
  import storage.{run=>runs, StoreOp}
  import nelson.CatsHelpers._

  override def beforeAll(): Unit = {
    super.beforeAll()
    nelson.storage.run(config.storage, insertFixtures(testName)).unsafeRunSync()
    ()
  }

  it should "run the entire cleanup pipeline and mark expired services as terminated" in {
    import cleanup._
    val st = StackName("search", Version(1,1,0), "foo")
    val su = runs(config.storage, StoreOp.findDeployment(st)).unsafeRunSync().get

    runs(config.storage, StoreOp.createDeploymentStatus(su.id, DeploymentStatus.Ready, None)).unsafeRunSync()
    val statusBefore = runs(config.storage, StoreOp.getDeploymentStatus(su.id)).unsafeRunSync()
    statusBefore should equal(Some(DeploymentStatus.Ready))

    runs(config.storage, StoreOp.createDeploymentExpiration(su.id, java.time.Instant.now().minusSeconds(1000))).unsafeRunSync()

    (CleanupCron.process(config) to Reaper.reap(config)).compile.toVector.unsafeRunSync()

    val status = runs(config.storage, StoreOp.getDeploymentStatus(su.id)).unsafeRunSync()
    status should equal(Some(DeploymentStatus.Terminated))
  }

  it should "run the entire cleanup pipeline and not mark active services as terminated" in {
    import cleanup._
    val st = StackName("search", Version(1,1,0), "foo")
    val su = runs(config.storage, StoreOp.findDeployment(st)).unsafeRunSync().get

    runs(config.storage, StoreOp.createDeploymentStatus(su.id, DeploymentStatus.Ready, None)).unsafeRunSync()
    val statusBefore = runs(config.storage, StoreOp.getDeploymentStatus(su.id)).unsafeRunSync()
    statusBefore should equal(Some(DeploymentStatus.Ready))

    runs(config.storage, StoreOp.createDeploymentExpiration(su.id, java.time.Instant.now().plusSeconds(1000))).unsafeRunSync()

    (CleanupCron.process(config) to Reaper.reap(config)).compile.toVector.unsafeRunSync()

    val status = runs(config.storage, StoreOp.getDeploymentStatus(su.id)).unsafeRunSync()
    status should equal(Some(DeploymentStatus.Ready))
  }

  it should "run the entire cleanup pipeline and not apply an expiration policy to non routable deployments" in {
    import cleanup._
    val st = StackName("search", Version(2,2,2), "aaaa")
    val su = runs(config.storage, StoreOp.findDeployment(st)).unsafeRunSync().get

    runs(config.storage, StoreOp.createDeploymentStatus(su.id, DeploymentStatus.Warming, None)).unsafeRunSync()
    val statusBefore = runs(config.storage, StoreOp.getDeploymentStatus(su.id)).unsafeRunSync()
    statusBefore should equal(Some(DeploymentStatus.Warming))

    val exp = java.time.Instant.now().plusSeconds(1000)

    runs(config.storage, StoreOp.createDeploymentExpiration(su.id, exp)).unsafeRunSync()

    CleanupCron.process(config).compile.toVector.unsafeRunSync()

    val expAfter = runs(config.storage, StoreOp.findDeploymentExpiration(su.id)).unsafeRunSync()
    expAfter should equal(Some(exp))
  }
}

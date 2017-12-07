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
  import Domain._
  import storage.{run=>runs, StoreOp}

  override def beforeAll(): Unit = {
    super.beforeAll()
    nelson.storage.run(config.storage, insertFixtures(testName)).run
    ()
  }

  it should "run the entire cleanup pipeline and mark expired services as terminated" in {
    import cleanup._
    val st = StackName("search", Version(1,1,0), "foo")
    val su = runs(config.storage, StoreOp.findDeployment(st)).run.get

    runs(config.storage, StoreOp.createDeploymentStatus(su.id, DeploymentStatus.Ready, None)).run
    val statusBefore = runs(config.storage, StoreOp.getDeploymentStatus(su.id)).run
    statusBefore should equal(Some(DeploymentStatus.Ready))

    runs(config.storage, StoreOp.createDeploymentExpiration(su.id, java.time.Instant.now().minusSeconds(1000))).run

    (CleanupCron.process(config) to Reaper.reap(config)).runLog.run

    val status = runs(config.storage, StoreOp.getDeploymentStatus(su.id)).run
    status should equal(Some(DeploymentStatus.Terminated))
  }

  it should "run the entire cleanup pipeline and not mark active services as terminated" in {
    import cleanup._
    val st = StackName("search", Version(1,1,0), "foo")
    val su = runs(config.storage, StoreOp.findDeployment(st)).run.get

    runs(config.storage, StoreOp.createDeploymentStatus(su.id, DeploymentStatus.Ready, None)).run
    val statusBefore = runs(config.storage, StoreOp.getDeploymentStatus(su.id)).run
    statusBefore should equal(Some(DeploymentStatus.Ready))

    runs(config.storage, StoreOp.createDeploymentExpiration(su.id, java.time.Instant.now().plusSeconds(1000))).run

    (CleanupCron.process(config) to Reaper.reap(config)).runLog.run

    val status = runs(config.storage, StoreOp.getDeploymentStatus(su.id)).run
    status should equal(Some(DeploymentStatus.Ready))
  }

  it should "run the entire cleanup pipeline and not apply an expiration policy to non routable deployments" in {
    import cleanup._
    val st = StackName("search", Version(2,2,2), "aaaa")
    val su = runs(config.storage, StoreOp.findDeployment(st)).run.get

    runs(config.storage, StoreOp.createDeploymentStatus(su.id, DeploymentStatus.Warming, None)).run
    val statusBefore = runs(config.storage, StoreOp.getDeploymentStatus(su.id)).run
    statusBefore should equal(Some(DeploymentStatus.Warming))

    val exp = java.time.Instant.now().plusSeconds(1000)

    runs(config.storage, StoreOp.createDeploymentExpiration(su.id, exp)).run

    CleanupCron.process(config).runLog.run

    val expAfter = runs(config.storage, StoreOp.findDeploymentExpiration(su.id)).run
    expAfter should equal(Some(exp))
  }
}

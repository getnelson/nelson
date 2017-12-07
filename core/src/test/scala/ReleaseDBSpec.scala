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

import storage.StoreOp
import org.scalatest.{BeforeAndAfterEach}

class ReleaseDBSpec extends NelsonSuite with BeforeAndAfterEach {

  override def beforeAll(): Unit = {
    super.beforeAll()
    storage.run(config.storage, insertFixtures(testName)).run
    ()
  }

  val un = "conductor"
  val uv = Version(1,1,1)
  val sn = Domain.StackName(un,uv,"abcd")

  it should "find release by unit name and version" in {
    val release = storage.run(config.storage, StoreOp.findReleasedByUnitNameAndVersion(un,uv))
    release.run.map(x => x.version) should equal (Some(uv))
  }

  it should "not find release if version doesn't exist" in {
    val uv2 = Version(1,1,2)
    val release = storage.run(config.storage, StoreOp.findReleasedByUnitNameAndVersion(un,uv2))
    release.run should equal (None)
  }

  it should "find release even if there's no deployment for unit / version" in {
    val unit = Manifest.UnitDef("foo", "", Map.empty, Set.empty, Manifest.Alerting.empty, Magnetar,
              None, Some(Manifest.Deployable("foo", uv , Manifest.Deployable.Container(""))), Set.empty[String])
    storage.run(config.storage, StoreOp.addUnit(Manifest.Versioned(unit), 9999L)).run
    val release = storage.run(config.storage, StoreOp.findReleasedByUnitNameAndVersion("foo", uv))
    release.run.map(_.version) should equal (Some(uv))
  }

  it should "find release by guid" in {
    val dep = storage.run(config.storage, StoreOp.findDeployment(sn)).run.get
    val release = storage.run(config.storage, StoreOp.findReleaseByDeploymentGuid(dep.guid))
    release.run.map(x => (x._2.unit.name, x._2.unit.version)) should equal (Some((un, uv)))
  }

  it should "find latest release for loadbalancer" in {
    val r = storage.run(config.storage, StoreOp.getLatestReleaseForLoadbalancer("lb", MajorVersion(1))).run
    r.map(_.version) should equal (Some(Version(1,2,3)))
    r.map(_.releaseId) should equal (Some(123L))
  }
}

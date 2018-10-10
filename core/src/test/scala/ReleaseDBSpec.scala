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
    insertFixtures(testName).foldMap(config.storage).unsafeRunSync()
    ()
  }

  val un = "conductor"
  val uv = Version(1,1,1)
  val sn = Datacenter.StackName(un,uv,"abcd")

  it should "find release by unit name and version" in {
    val release = StoreOp.findReleasedByUnitNameAndVersion(un,uv).foldMap(config.storage)
    release.unsafeRunSync().map(x => x.version) should equal (Some(uv))
  }

  it should "not find release if version doesn't exist" in {
    val uv2 = Version(1,1,2)
    val release = StoreOp.findReleasedByUnitNameAndVersion(un,uv2).foldMap(config.storage)
    release.unsafeRunSync() should equal (None)
  }

  it should "find release even if there's no deployment for unit / version" in {
    val unit = Manifest.UnitDef("foo", "", Map.empty, Set.empty, Manifest.Alerting.empty,
              None, Some(Manifest.Deployable("foo", uv , Manifest.Deployable.Container(""))), Set.empty[String])
    StoreOp.addUnit(Manifest.Versioned(unit), 9999L).foldMap(config.storage).unsafeRunSync()
    val release = StoreOp.findReleasedByUnitNameAndVersion("foo", uv).foldMap(config.storage)
    release.unsafeRunSync().map(_.version) should equal (Some(uv))
  }

  it should "find release by guid" in {
    val dep = StoreOp.findDeployment(sn).foldMap(config.storage).unsafeRunSync().get
    val release = StoreOp.findReleaseByDeploymentGuid(dep.guid).foldMap(config.storage)
    release.unsafeRunSync().map(x => (x._2.unit.name, x._2.unit.version)) should equal (Some((un, uv)))
  }

  it should "find latest release for loadbalancer" in {
    val r = StoreOp.getLatestReleaseForLoadbalancer("lb", MajorVersion(1)).foldMap(config.storage).unsafeRunSync()
    r.map(_.version) should equal (Some(Version(1,2,3)))
    r.map(_.referenceId) should equal (Some(123L))
  }
}

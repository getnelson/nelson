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

import org.scalatest.BeforeAndAfterEach
import storage.StoreOp
import Datacenter._
import cleanup._
import cats.effect.IO
import fs2.Stream

class ReaperSpec extends NelsonSuite with BeforeAndAfterEach {

  override def beforeAll(): Unit = {
    super.beforeAll()
    insertFixtures(testName).foldMap(config.storage).unsafeRunSync()
    ()
  }

  val dc = config.datacenters.head

  val gr = quiver.empty[routing.RoutingNode,Unit,routing.RoutePath]

  it should "mark deployment as terminated" in {
    val st = StackName("search", Version(1,1,0), "foo")
    val sn = ServiceName("search", st.version.toFeatureVersion)
    val dep = StoreOp.findDeployment(st).foldMap(config.storage).unsafeRunSync().get

    val ctx = DeploymentCtx(dep, DeploymentStatus.Garbage, Some(java.time.Instant.now))

    Stream.eval(IO.pure((dc,dep.namespace,ctx,gr))).to(Reaper.reap(config)).take(1).compile.toVector.unsafeRunSync()

    val status = StoreOp.getDeploymentStatus(dep.id).foldMap(config.storage).unsafeRunSync()
    status should equal(Some(DeploymentStatus.Terminated))
  }
}

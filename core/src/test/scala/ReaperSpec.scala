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

import scalaz.concurrent.Task
import scalaz.stream._
import org.scalatest.BeforeAndAfterEach
import storage.{run=>runs, StoreOp}
import Datacenter._
import cleanup._

class ReaperSpec extends NelsonSuite with BeforeAndAfterEach {

  override def beforeAll(): Unit = {
    super.beforeAll()
    storage.run(config.storage, insertFixtures(testName)).run
    ()
  }

  val dc = config.datacenters.head

  val gr = quiver.empty[routing.RoutingNode,Unit,routing.RoutePath]

  it should "mark deployment as terminated" in {
    val st = StackName("search", Version(1,1,0), "foo")
    val sn = ServiceName("search", st.version.toFeatureVersion)
    val dep = runs(config.storage, StoreOp.findDeployment(st)).run.get

    val ctx = DeploymentCtx(dep, DeploymentStatus.Garbage, Some(java.time.Instant.now))

    Process.eval(Task.now((dc,dep.namespace,ctx,gr))).to(Reaper.reap(config)).take(1).runLog.run

    val status = runs(config.storage, StoreOp.getDeploymentStatus(dep.id)).run
    status should equal(Some(DeploymentStatus.Terminated))
  }
}

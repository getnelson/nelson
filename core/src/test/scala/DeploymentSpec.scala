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

import Datacenter._

import org.scalacheck._, Prop._

object DeploymentSpec extends Properties("deployment") {
  import Deployment._
  import Fixtures._

  property("order") = forAll { (d1: Deployment) =>

    // same version different deployment times
    // most recent deployment time should be max
    ("verify deployment order when versions are the same" |: {
      val d2 = d1.copy(deployTime = d1.deployTime.plusSeconds(1000))
      deploymentOrder.max(d1,d2) == d2
    })
  }
}

import org.scalatest.{FlatSpec,Matchers}

class DeploymentFlatSpec extends FlatSpec with Matchers {

  val ns = Datacenter.Namespace(1L, NamespaceName("devel"), "dc")
  val now = java.time.Instant.now
  val d = Deployment(4L, DCUnit(4L,"foo",Version(2,1,0),"",Set.empty,Set.empty,Set.empty),"e",ns,now,"pulsar","plan","guid","retain-active",None)
  val ds = Set(
    Deployment(0L, DCUnit(0L,"foo",Version(1,1,1),"",Set.empty,Set.empty,Set.empty),"a",ns,now,"pulsar","plan","guid","retain-active",None),
    Deployment(1L, DCUnit(1L,"foo",Version(1,1,2),"",Set.empty,Set.empty,Set.empty),"b",ns,now,"pulsar","plan","guid","retain-active",None),
    Deployment(2L, DCUnit(2L,"foo",Version(1,2,1),"",Set.empty,Set.empty,Set.empty),"c",ns,now,"pulsar","plan","guid","retain-active",None),
    Deployment(3L, DCUnit(3L,"foo",Version(2,0,0),"",Set.empty,Set.empty,Set.empty),"d",ns,now,"pulsar","plan","guid","retain-active",None),
    d
  )

  it should "get the latest version" in {
    Deployment.getLatestVersion(ds) should equal (Some(d.unit.version))
  }

  it should "filter deployments be stackname" in {
    val filtered = Deployment.filterByStackName(ds,d.stackName)
    filtered should equal (ds.filter(_.id != d.id))
  }
}

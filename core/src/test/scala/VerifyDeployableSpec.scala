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

import cats.data.NonEmptyList
import cats.data.Validated.Valid

class VerifyDeployableSpec extends NelsonSuite {
  import Datacenter._

  var ns: Manifest.Namespace = _

  override def beforeAll(): Unit = {
    super.beforeAll()
    ns = insertFixtures(testName).foldMap(config.storage).unsafeRunSync()
  }

  "verifyDeployable" should "think conductor is deployable" in {
    Manifest.verifyDeployable(conductorManifest(ns),
                              List(datacenter(testName)),
                              config.storage).unsafeRunSync() should be (Valid(()))
  }

  it should "think undeployable is undeployable" in {
    val left = Manifest.verifyDeployable(undeployable(ns), List(datacenter(testName)), config.storage).unsafeRunSync()
    left.swap.toOption.get match {
      case n: NonEmptyList[NelsonError] =>
        n.toList.map(_.asInstanceOf[MissingDependency].dependency) should contain theSameElementsAs List(ServiceName("nonexistant", FeatureVersion(1,0)), ServiceName("ab", FeatureVersion(10,0)))
    }
  }

  it should "not validate deployable if a dependency is deprecated" in {
    val left = Manifest.verifyDeployable(undeployableDeprecatedDep(ns), List(datacenter(testName)), config.storage).unsafeRunSync()
    left.swap.toOption.get match {
      case n: NonEmptyList[NelsonError] =>
        n.toList.map(_.asInstanceOf[DeprecatedDependency].dependency) should contain theSameElementsAs List(ServiceName("search", FeatureVersion(1,1)))
    }
  }

  it should "resolve dependencies in upstream namespaces" in {
    val ns2 = Manifest.Namespace(name = NamespaceName("dev", List("sandbox", "rodrigo")), units = Set(), loadbalancers = Set())
    val res = Manifest.verifyDeployable(serviceC2Manifest(ns2), List(datacenter(testName)), config.storage).unsafeRunSync()
    res should be (Valid(()))
  }
}

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

import storage.{StoreOp, run => runs}

import scalaz.NonEmptyList
import org.scalactic.TypeCheckedTripleEquals

import nelson.CatsHelpers._

class DeploymentTableSpec extends NelsonSuite with TypeCheckedTripleEquals {
  import Datacenter._

  override def beforeAll(): Unit = {
    super.beforeAll()
    runs(config.storage, insertFixtures(testName)).unsafeRunSync()
    ()
  }

  val nsName = NamespaceName("dev")

  it should "deprecate all deployments for a feature version" in {
    val st = StackName("ab", Version(2,2,1), "abcd")
    val sn = ServiceName("ab", st.version.toFeatureVersion)
    val ns = runs(config.storage, StoreOp.getNamespace(testName, nsName)).unsafeRunSync().get
    val ab = runs(config.storage, StoreOp.findDeployment(st)).unsafeRunSync().get

    val before = runs(config.storage, StoreOp.getDeploymentStatus(ab.id)).unsafeRunSync().get
    assert(before != DeploymentStatus.Deprecated)

    Nelson.deprecateService(sn).run(config).unsafeRunSync()

    val after = runs(config.storage, StoreOp.getDeploymentStatus(ab.id)).unsafeRunSync().get
    assert(after == DeploymentStatus.Deprecated)
  }

  it should "not deprecate deployments for a different feature version" in {
    val job410 = StackName("job", Version(4,1,0), "zzzz2")
    val job311 = StackName("job", Version(3,1,1), "zzzz1")
    val job300 = StackName("job", Version(3,0,0), "zzzz4")

    val ns = runs(config.storage, StoreOp.getNamespace(testName, nsName)).unsafeRunSync().get
    val j410 = runs(config.storage, StoreOp.findDeployment(job410)).unsafeRunSync().get
    val j311 = runs(config.storage, StoreOp.findDeployment(job311)).unsafeRunSync().get
    val j300 = runs(config.storage, StoreOp.findDeployment(job300)).unsafeRunSync().get

    val before410 = runs(config.storage, StoreOp.getDeploymentStatus(j410.id)).unsafeRunSync().get
    val before311 = runs(config.storage, StoreOp.getDeploymentStatus(j311.id)).unsafeRunSync().get
    val before300 = runs(config.storage, StoreOp.getDeploymentStatus(j300.id)).unsafeRunSync().get

    assert(before410 != DeploymentStatus.Deprecated)
    assert(before311 != DeploymentStatus.Deprecated)
    assert(before300 != DeploymentStatus.Deprecated)

    // deprecate 311 only
    val sn = ServiceName("job", job311.version.toFeatureVersion)
    Nelson.deprecateService(sn).run(config).unsafeRunSync()

    val after410 = runs(config.storage, StoreOp.getDeploymentStatus(j410.id)).unsafeRunSync().get
    val after311 = runs(config.storage, StoreOp.getDeploymentStatus(j311.id)).unsafeRunSync().get
    val after300 = runs(config.storage, StoreOp.getDeploymentStatus(j300.id)).unsafeRunSync().get

    assert(after410 != DeploymentStatus.Deprecated)
    assert(after300 != DeploymentStatus.Deprecated)

    // 311 is the only version that should be deprecated
    assert(after311 == DeploymentStatus.Deprecated)
  }

  it should "list deployed units" in {
    import DeploymentStatus._
    val ns = runs(config.storage, StoreOp.getNamespace(testName, nsName)).unsafeRunSync().get
    val units = runs(config.storage, StoreOp.listUnitsByStatus(ns.id,NonEmptyList(Ready, Deprecated))).unsafeRunSync()

    // ignore the other parts of the data here, as they are random
    units.map(_._2).toSet should === (
      Set(
        ServiceName("db", FeatureVersion(1, 2)),
        ServiceName("inventory", FeatureVersion(1, 2)),
        ServiceName("job", FeatureVersion(4, 1)),
        ServiceName("job", FeatureVersion(3, 1)),
        ServiceName("job", FeatureVersion(3, 0)),
        ServiceName("crawler", FeatureVersion(5, 1)),
        ServiceName("conductor", FeatureVersion(1, 1)),
        ServiceName("search", FeatureVersion(2, 2)),
        ServiceName("search", FeatureVersion(1, 1)),
        ServiceName("foo", FeatureVersion(1, 10)),
        ServiceName("foo", FeatureVersion(2, 0)),
        ServiceName("ab", FeatureVersion(2, 2)),
        ServiceName("service-a", FeatureVersion(6, 0))
      )
    )
  }

  it should "list deployments by namespace and status" in {
    val ns = runs(config.storage, StoreOp.getNamespace(testName, nsName)).unsafeRunSync().get

    val conductor = runs(config.storage, StoreOp.findDeployment(StackName("conductor", Version(1,1,1), "abcd"))).unsafeRunSync().get
    val ab222 = runs(config.storage, StoreOp.findDeployment(StackName("ab", Version(2,2,2), "abcd"))).unsafeRunSync().get
    val ab221 = runs(config.storage, StoreOp.findDeployment(StackName("ab", Version(2,2,1), "abcd"))).unsafeRunSync().get
    val inventory122 = runs(config.storage, StoreOp.findDeployment(StackName("inventory", Version(1,2,2), "ffff"))).unsafeRunSync().get
    val inventory123 = runs(config.storage, StoreOp.findDeployment(StackName("inventory", Version(1,2,3), "ffff"))).unsafeRunSync().get
    val search110 = runs(config.storage, StoreOp.findDeployment(StackName("search", Version(1,1,0), "foo"))).unsafeRunSync().get
    val search222 = runs(config.storage, StoreOp.findDeployment(StackName("search", Version(2,2,2), "aaaa"))).unsafeRunSync().get
    val search222b = runs(config.storage, StoreOp.findDeployment(StackName("search", Version(2,2,2), "bbbb"))).unsafeRunSync().get
    val job300 = runs(config.storage, StoreOp.findDeployment(StackName("job", Version(3,0,0), "zzzz4"))).unsafeRunSync().get
    val job310 = runs(config.storage, StoreOp.findDeployment(StackName("job", Version(3,1,0), "zzzz"))).unsafeRunSync().get
    val job311 = runs(config.storage, StoreOp.findDeployment(StackName("job", Version(3,1,1), "zzzz1"))).unsafeRunSync().get
    val job410 = runs(config.storage, StoreOp.findDeployment(StackName("job", Version(4,1,0), "zzzz2"))).unsafeRunSync().get
    val crawler = runs(config.storage, StoreOp.findDeployment(StackName("crawler", Version(5,1,0), "zzzz3"))).unsafeRunSync().get
    val db = runs(config.storage, StoreOp.findDeployment(StackName("db", Version(1,2,3), "aaaa"))).unsafeRunSync().get
    val foo1 = runs(config.storage, StoreOp.findDeployment(StackName("foo", Version(1,10,100), "aaaa"))).unsafeRunSync().get
    val foo2 = runs(config.storage, StoreOp.findDeployment(StackName("foo", Version(2,0,0), "bbbb"))).unsafeRunSync().get
    val serviceA = runs(config.storage, StoreOp.findDeployment(StackName("service-a", Version(6,0,0), "aaaa"))).unsafeRunSync().get

    val all: Set[(Deployment, DeploymentStatus)] = runs(config.storage, StoreOp.listDeploymentsForNamespaceByStatus(ns.id, DeploymentStatus.nel)).unsafeRunSync()
    val expected1: Set[(Deployment, DeploymentStatus)] = Set(
      (db, DeploymentStatus.Ready),
      (conductor, DeploymentStatus.Ready),
      (ab222, DeploymentStatus.Deprecated),
      (ab221, DeploymentStatus.Deprecated),
      (inventory122, DeploymentStatus.Ready),
      (inventory123, DeploymentStatus.Ready),
      (search110, DeploymentStatus.Deprecated),
      (search222, DeploymentStatus.Ready),
      (search222b, DeploymentStatus.Ready),
      (job300, DeploymentStatus.Ready),
      (job310, DeploymentStatus.Deprecated),
      (job311, DeploymentStatus.Deprecated),
      (job410, DeploymentStatus.Ready),
      (crawler, DeploymentStatus.Ready),
      (foo1, DeploymentStatus.Ready),
      (foo2, DeploymentStatus.Ready),
      (serviceA, DeploymentStatus.Ready)
    )
    all.toSet should === (expected1)

    runs(config.storage, StoreOp.createDeploymentStatus(ab222.id, DeploymentStatus.Terminated, None)).unsafeRunSync()
    val terminated: Set[(Deployment, DeploymentStatus)] = runs(config.storage, StoreOp.listDeploymentsForNamespaceByStatus(ns.id, NonEmptyList(DeploymentStatus.Terminated))).unsafeRunSync()
    val expected2: Set[(Deployment, DeploymentStatus)] = Set((ab222,DeploymentStatus.Terminated))
    terminated.toSet should === (expected2)
  }

  it should "roudtrip deployment resources" in {
    val dep = runs(config.storage, StoreOp.findDeployment(StackName("conductor", Version(1,1,1), "abcd"))).unsafeRunSync().get
    runs(config.storage, StoreOp.createDeploymentResource(dep.id, "s3", new java.net.URI("s3://foo"))).unsafeRunSync()
    val res = runs(config.storage, StoreOp.getDeploymentResources(dep.id)).unsafeRunSync()
    res should contain (("s3", new java.net.URI("s3://foo")))
  }

}

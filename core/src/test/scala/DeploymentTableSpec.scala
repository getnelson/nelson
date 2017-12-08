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

class DeploymentTableSpec extends NelsonSuite with TypeCheckedTripleEquals {
  import Domain._

  override def beforeAll(): Unit = {
    super.beforeAll()
    runs(config.storage, insertFixtures(testName)).run
    ()
  }

  val nsName = NamespaceName("dev")

  it should "deprecate all deployments for a feature version" in {
    val st = StackName("ab", Version(2,2,1), "abcd")
    val sn = ServiceName("ab", st.version.toFeatureVersion)
    val ns = runs(config.storage, StoreOp.getNamespace(testName, nsName)).run.get
    val ab = runs(config.storage, StoreOp.findDeployment(st)).run.get

    val before = runs(config.storage, StoreOp.getDeploymentStatus(ab.id)).run.get
    assert(before != DeploymentStatus.Deprecated)

    Nelson.deprecateService(sn).run(config).run

    val after = runs(config.storage, StoreOp.getDeploymentStatus(ab.id)).run.get
    assert(after == DeploymentStatus.Deprecated)
  }

  it should "not deprecate deployments for a different feature version" in {
    val job410 = StackName("job", Version(4,1,0), "zzzz2")
    val job311 = StackName("job", Version(3,1,1), "zzzz1")
    val job300 = StackName("job", Version(3,0,0), "zzzz4")

    val ns = runs(config.storage, StoreOp.getNamespace(testName, nsName)).run.get
    val j410 = runs(config.storage, StoreOp.findDeployment(job410)).run.get
    val j311 = runs(config.storage, StoreOp.findDeployment(job311)).run.get
    val j300 = runs(config.storage, StoreOp.findDeployment(job300)).run.get

    val before410 = runs(config.storage, StoreOp.getDeploymentStatus(j410.id)).run.get
    val before311 = runs(config.storage, StoreOp.getDeploymentStatus(j311.id)).run.get
    val before300 = runs(config.storage, StoreOp.getDeploymentStatus(j300.id)).run.get

    assert(before410 != DeploymentStatus.Deprecated)
    assert(before311 != DeploymentStatus.Deprecated)
    assert(before300 != DeploymentStatus.Deprecated)

    // deprecate 311 only
    val sn = ServiceName("job", job311.version.toFeatureVersion)
    Nelson.deprecateService(sn).run(config).run

    val after410 = runs(config.storage, StoreOp.getDeploymentStatus(j410.id)).run.get
    val after311 = runs(config.storage, StoreOp.getDeploymentStatus(j311.id)).run.get
    val after300 = runs(config.storage, StoreOp.getDeploymentStatus(j300.id)).run.get

    assert(after410 != DeploymentStatus.Deprecated)
    assert(after300 != DeploymentStatus.Deprecated)

    // 311 is the only version that should be deprecated
    assert(after311 == DeploymentStatus.Deprecated)
  }

  it should "list deployed units" in {
    import DeploymentStatus._
    val ns = runs(config.storage, StoreOp.getNamespace(testName, nsName)).run.get
    val units = runs(config.storage, StoreOp.listUnitsByStatus(ns.id,NonEmptyList(Ready, Deprecated))).run

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
    val ns = runs(config.storage, StoreOp.getNamespace(testName, nsName)).run.get

    val conductor = runs(config.storage, StoreOp.findDeployment(StackName("conductor", Version(1,1,1), "abcd"))).run.get
    val ab222 = runs(config.storage, StoreOp.findDeployment(StackName("ab", Version(2,2,2), "abcd"))).run.get
    val ab221 = runs(config.storage, StoreOp.findDeployment(StackName("ab", Version(2,2,1), "abcd"))).run.get
    val inventory122 = runs(config.storage, StoreOp.findDeployment(StackName("inventory", Version(1,2,2), "ffff"))).run.get
    val inventory123 = runs(config.storage, StoreOp.findDeployment(StackName("inventory", Version(1,2,3), "ffff"))).run.get
    val search110 = runs(config.storage, StoreOp.findDeployment(StackName("search", Version(1,1,0), "foo"))).run.get
    val search222 = runs(config.storage, StoreOp.findDeployment(StackName("search", Version(2,2,2), "aaaa"))).run.get
    val search222b = runs(config.storage, StoreOp.findDeployment(StackName("search", Version(2,2,2), "bbbb"))).run.get
    val job300 = runs(config.storage, StoreOp.findDeployment(StackName("job", Version(3,0,0), "zzzz4"))).run.get
    val job310 = runs(config.storage, StoreOp.findDeployment(StackName("job", Version(3,1,0), "zzzz"))).run.get
    val job311 = runs(config.storage, StoreOp.findDeployment(StackName("job", Version(3,1,1), "zzzz1"))).run.get
    val job410 = runs(config.storage, StoreOp.findDeployment(StackName("job", Version(4,1,0), "zzzz2"))).run.get
    val crawler = runs(config.storage, StoreOp.findDeployment(StackName("crawler", Version(5,1,0), "zzzz3"))).run.get
    val db = runs(config.storage, StoreOp.findDeployment(StackName("db", Version(1,2,3), "aaaa"))).run.get
    val foo1 = runs(config.storage, StoreOp.findDeployment(StackName("foo", Version(1,10,100), "aaaa"))).run.get
    val foo2 = runs(config.storage, StoreOp.findDeployment(StackName("foo", Version(2,0,0), "bbbb"))).run.get
    val serviceA = runs(config.storage, StoreOp.findDeployment(StackName("service-a", Version(6,0,0), "aaaa"))).run.get

    val all: Set[(Deployment, DeploymentStatus)] = runs(config.storage, StoreOp.listDeploymentsForNamespaceByStatus(ns.id, DeploymentStatus.nel)).run
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

    runs(config.storage, StoreOp.createDeploymentStatus(ab222.id, DeploymentStatus.Terminated, None)).run
    val terminated: Set[(Deployment, DeploymentStatus)] = runs(config.storage, StoreOp.listDeploymentsForNamespaceByStatus(ns.id, NonEmptyList(DeploymentStatus.Terminated))).run
    val expected2: Set[(Deployment, DeploymentStatus)] = Set((ab222,DeploymentStatus.Terminated))
    terminated.toSet should === (expected2)
  }

  it should "roudtrip deployment resources" in {
    val dep = runs(config.storage, StoreOp.findDeployment(StackName("conductor", Version(1,1,1), "abcd"))).run.get
    runs(config.storage, StoreOp.createDeploymentResource(dep.id, "s3", new java.net.URI("s3://foo"))).run
    val res = runs(config.storage, StoreOp.getDeploymentResources(dep.id)).run
    res should contain (("s3", new java.net.URI("s3://foo")))
  }

}

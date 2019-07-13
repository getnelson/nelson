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

import cats.data.NonEmptyList
import org.scalactic.TypeCheckedTripleEquals

class DeploymentTableSpec extends NelsonSuite with TypeCheckedTripleEquals {
  import Datacenter._

  override def beforeAll(): Unit = {
    super.beforeAll()
    insertFixtures(testName).foldMap(config.storage).unsafeRunSync()
    ()
  }

  val nsName = NamespaceName("dev")

  it should "deprecate all deployments for a feature version" in {
    val st = StackName("ab", Version(2,2,1), "abcd")
    val sn = ServiceName("ab", st.version.toFeatureVersion)
    val ab = StoreOp.findDeployment(st).foldMap(config.storage).unsafeRunSync().get

    val before = StoreOp.getDeploymentStatus(ab.id).foldMap(config.storage).unsafeRunSync().get
    assert(before != DeploymentStatus.Deprecated)

    Nelson.deprecateService(sn).run(config).unsafeRunSync()

    val after = StoreOp.getDeploymentStatus(ab.id).foldMap(config.storage).unsafeRunSync().get
    assert(after == DeploymentStatus.Deprecated)
  }

  it should "not deprecate deployments for a different feature version" in {
    val job410 = StackName("job", Version(4,1,0), "zzzz2")
    val job311 = StackName("job", Version(3,1,1), "zzzz1")
    val job300 = StackName("job", Version(3,0,0), "zzzz4")

    val j410 = StoreOp.findDeployment(job410).foldMap(config.storage).unsafeRunSync().get
    val j311 = StoreOp.findDeployment(job311).foldMap(config.storage).unsafeRunSync().get
    val j300 = StoreOp.findDeployment(job300).foldMap(config.storage).unsafeRunSync().get

    val before410 = StoreOp.getDeploymentStatus(j410.id).foldMap(config.storage).unsafeRunSync().get
    val before311 = StoreOp.getDeploymentStatus(j311.id).foldMap(config.storage).unsafeRunSync().get
    val before300 = StoreOp.getDeploymentStatus(j300.id).foldMap(config.storage).unsafeRunSync().get

    assert(before410 != DeploymentStatus.Deprecated)
    assert(before311 != DeploymentStatus.Deprecated)
    assert(before300 != DeploymentStatus.Deprecated)

    // deprecate 311 only
    val sn = ServiceName("job", job311.version.toFeatureVersion)
    Nelson.deprecateService(sn).run(config).unsafeRunSync()

    val after410 = StoreOp.getDeploymentStatus(j410.id).foldMap(config.storage).unsafeRunSync().get
    val after311 = StoreOp.getDeploymentStatus(j311.id).foldMap(config.storage).unsafeRunSync().get
    val after300 = StoreOp.getDeploymentStatus(j300.id).foldMap(config.storage).unsafeRunSync().get

    assert(after410 != DeploymentStatus.Deprecated)
    assert(after300 != DeploymentStatus.Deprecated)

    // 311 is the only version that should be deprecated
    assert(after311 == DeploymentStatus.Deprecated)
  }

  it should "list deployed units" in {
    import DeploymentStatus._
    val ns = StoreOp.getNamespace(testName, nsName).foldMap(config.storage).unsafeRunSync().get
    val units = StoreOp.listUnitsByStatus(ns.id,NonEmptyList.of(Ready, Deprecated)).foldMap(config.storage).unsafeRunSync()

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
    val ns = StoreOp.getNamespace(testName, nsName).foldMap(config.storage).unsafeRunSync().get

    val conductor =    StoreOp.findDeployment(StackName("conductor", Version(1,1,1), "abcd")).foldMap(config.storage).unsafeRunSync().get
    val ab222 =        StoreOp.findDeployment(StackName("ab", Version(2,2,2), "abcd")).foldMap(config.storage).unsafeRunSync().get
    val ab221 =        StoreOp.findDeployment(StackName("ab", Version(2,2,1), "abcd")).foldMap(config.storage).unsafeRunSync().get
    val inventory122 = StoreOp.findDeployment(StackName("inventory", Version(1,2,2), "ffff")).foldMap(config.storage).unsafeRunSync().get
    val inventory123 = StoreOp.findDeployment(StackName("inventory", Version(1,2,3), "ffff")).foldMap(config.storage).unsafeRunSync().get
    val search110 =    StoreOp.findDeployment(StackName("search", Version(1,1,0), "foo")).foldMap(config.storage).unsafeRunSync().get
    val search222 =    StoreOp.findDeployment(StackName("search", Version(2,2,2), "aaaa")).foldMap(config.storage).unsafeRunSync().get
    val search222b =   StoreOp.findDeployment(StackName("search", Version(2,2,2), "bbbb")).foldMap(config.storage).unsafeRunSync().get
    val job300 =       StoreOp.findDeployment(StackName("job", Version(3,0,0), "zzzz4")).foldMap(config.storage).unsafeRunSync().get
    val job310 =       StoreOp.findDeployment(StackName("job", Version(3,1,0), "zzzz")).foldMap(config.storage).unsafeRunSync().get
    val job311 =       StoreOp.findDeployment(StackName("job", Version(3,1,1), "zzzz1")).foldMap(config.storage).unsafeRunSync().get
    val job410 =       StoreOp.findDeployment(StackName("job", Version(4,1,0), "zzzz2")).foldMap(config.storage).unsafeRunSync().get
    val crawler =      StoreOp.findDeployment(StackName("crawler", Version(5,1,0), "zzzz3")).foldMap(config.storage).unsafeRunSync().get
    val db =           StoreOp.findDeployment(StackName("db", Version(1,2,3), "aaaa")).foldMap(config.storage).unsafeRunSync().get
    val foo1 =         StoreOp.findDeployment(StackName("foo", Version(1,10,100), "aaaa")).foldMap(config.storage).unsafeRunSync().get
    val foo2 =         StoreOp.findDeployment(StackName("foo", Version(2,0,0), "bbbb")).foldMap(config.storage).unsafeRunSync().get
    val serviceA =     StoreOp.findDeployment(StackName("service-a", Version(6,0,0), "aaaa")).foldMap(config.storage).unsafeRunSync().get

    val all: Set[(Deployment, DeploymentStatus)] = StoreOp.listDeploymentsForNamespaceByStatus(ns.id, DeploymentStatus.nel).foldMap(config.storage).unsafeRunSync()
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

    StoreOp.createDeploymentStatus(ab222.id, DeploymentStatus.Terminated, None).foldMap(config.storage).unsafeRunSync()
    val terminated: Set[(Deployment, DeploymentStatus)] = StoreOp.listDeploymentsForNamespaceByStatus(ns.id, NonEmptyList.of(DeploymentStatus.Terminated)).foldMap(config.storage).unsafeRunSync()
    val expected2: Set[(Deployment, DeploymentStatus)] = Set((ab222,DeploymentStatus.Terminated))
    terminated.toSet should === (expected2)
  }

  it should "roudtrip deployment resources" in {
    val dep = StoreOp.findDeployment(StackName("conductor", Version(1,1,1), "abcd")).foldMap(config.storage).unsafeRunSync().get
    StoreOp.createDeploymentResource(dep.id, "s3", new java.net.URI("s3://foo")).foldMap(config.storage).unsafeRunSync()
    val res = StoreOp.getDeploymentResources(dep.id).foldMap(config.storage).unsafeRunSync()
    res should contain (("s3", new java.net.URI("s3://foo")))
  }

}

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

import nelson.storage.StoreOp

import cats.data.NonEmptyList

import org.scalacheck._

import org.scalatest.BeforeAndAfterEach

class NelsonSpec extends NelsonSuite with BeforeAndAfterEach {
  import Datacenter._
  import routing.RoutingNode

  override def beforeAll(): Unit = {
    super.beforeAll()
    insertFixtures(testName).foldMap(config.storage).unsafeRunSync()
    ()
  }


  it should "deprecate all services accross datacenters and namespaces" in {
    val st = StackName("ab", Version(2,2,1), "abcd")
    val sn = ServiceName("ab", st.version.toFeatureVersion)

    val ab =  StoreOp.findDeployment(st).foldMap(config.storage).unsafeRunSync().get

    val before = StoreOp.listDeploymentStatuses(ab.id).foldMap(config.storage).unsafeRunSync()
    assert(before.find(_._1 == DeploymentStatus.Deprecated).isEmpty)

    Nelson.deprecateService(sn).run(config).unsafeRunSync()

    val after = StoreOp.listDeploymentStatuses(ab.id).foldMap(config.storage).unsafeRunSync()
    assert(after.find(_._1 == DeploymentStatus.Deprecated).nonEmpty)
  }

  it should "expiration deployment" in {
    val st = StackName("ab", Version(2,2,1), "abcd")
    val sn = ServiceName("ab", st.version.toFeatureVersion)
    val dep = StoreOp.findDeployment(st).foldMap(config.storage).unsafeRunSync().get

    val exp1 = StoreOp.findDeploymentExpiration(dep.id).foldMap(config.storage).unsafeRunSync()
    Nelson.expireService(sn).run(config).unsafeRunSync()
    val exp2 = StoreOp.findDeploymentExpiration(dep.id).foldMap(config.storage).unsafeRunSync()

    assert(exp2.get.isBefore(exp1.get))
  }

  it should "not expire deployments for a different feature version" in {
    val job410 = StackName("job", Version(4,1,0), "zzzz2")
    val job311 = StackName("job", Version(3,1,1), "zzzz1")
    val job300 = StackName("job", Version(3,0,0), "zzzz4")

    StoreOp.getNamespace(testName, NamespaceName("dev")).foldMap(config.storage).unsafeRunSync().get
    val j410 = StoreOp.findDeployment(job410).foldMap(config.storage).unsafeRunSync().get
    val j311 = StoreOp.findDeployment(job311).foldMap(config.storage).unsafeRunSync().get
    val j300 = StoreOp.findDeployment(job300).foldMap(config.storage).unsafeRunSync().get

    val exp410A = StoreOp.findDeploymentExpiration(j410.id).foldMap(config.storage).unsafeRunSync().get
    val exp311A = StoreOp.findDeploymentExpiration(j311.id).foldMap(config.storage).unsafeRunSync().get
    val exp300A = StoreOp.findDeploymentExpiration(j300.id).foldMap(config.storage).unsafeRunSync().get

    // deprecate 311 only
    val sn = ServiceName("job", job311.version.toFeatureVersion)
    Nelson.expireService(sn).run(config).unsafeRunSync()

    val exp410B = StoreOp.findDeploymentExpiration(j410.id).foldMap(config.storage).unsafeRunSync().get
    val exp311B = StoreOp.findDeploymentExpiration(j311.id).foldMap(config.storage).unsafeRunSync().get
    val exp300B = StoreOp.findDeploymentExpiration(j300.id).foldMap(config.storage).unsafeRunSync().get

    assert(exp410A == exp410B)
    assert(exp300A == exp300B)

    // 311 is the only version that should be expired
    assert(exp311B.isBefore(exp311A))
  }


  it should "show everything that depends on a deprecated service" in {
    val abst = StackName("ab", Version(2,2,2), "abcd")
    val absn = ServiceName("ab", abst.version.toFeatureVersion)
    val cond = StackName("conductor", Version(1,1,1), "abcd")

    val ab = StoreOp.findDeployment(abst).foldMap(config.storage).unsafeRunSync().get
    val co = StoreOp.findDeployment(cond).foldMap(config.storage).unsafeRunSync().get

    Nelson.deprecateService(absn).run(config).unsafeRunSync()

    val services = Nelson.listDeploymentsWithDeprecatedDependencies.run(config).unsafeRunSync()

    services should equal (List((RoutingNode(co),RoutingNode(ab))))
  }

  it should "not commit a unit that does not exists" in {
    val res = Nelson.commit("ab", Version(20,2,1), NamespaceName("dev")).run(config).attempt.unsafeRunSync()
    res should equal (Left(DeploymentCommitFailed("could not find release by unit name: ab and version: 20.2.1")))
  }

  it should "not commit when namespace does not exist" in {
    val m = Manifest(
      units = Nil,
      loadbalancers = Nil,
      plans = Nil,
      namespaces = List(Manifest.Namespace(NamespaceName("dev"), Set(), Set())),
      targets = Manifest.DeploymentTarget.Only(Seq()),
      notifications = notifications.NotificationSubscriptions.empty
    )
    val res = Nelson.commit("ab", NamespaceName("foo"), Nil, Manifest.Versioned(m)).run(config).attempt.unsafeRunSync()
    res should equal (Left(MultipleValidationErrors(NonEmptyList.of(
        DeploymentCommitFailed("namespace foo is not declared in namespaces stanza of the manifest"),
        DeploymentCommitFailed("unit ab is not declared for namespace foo")))))
  }

  it should "put all the correct bits in the database when doing a manual deploy" in {
    val session = Session(
      expiry = java.time.Instant.now.plusSeconds(1000),
      github = AccessToken("foobarbaz"),
      user = User(
        login = "scalatest",
        avatar = new java.net.URI("uri"),
        name = Some("user"),
        email = Some("user@example.com"),
        orgs = List(Organization(0L, Some("scalatest"), "slug", new java.net.URI("uri")))
      )
    )
    val md = Datacenter.ManualDeployment(testName, "dev", "manual-deployment", "1.1.1", "hash", "description", 9000)
    Nelson.createManualDeployment(session,md).run(config).unsafeRunSync()
    val st = StackName("manual-deployment", Version(1,1,1), "hash")

    val dep = StoreOp.findDeployment(st).foldMap(config.storage).unsafeRunSync()
    dep.isDefined should equal(true)

    val status = StoreOp.getDeploymentStatus(dep.get.id).foldMap(config.storage).unsafeRunSync()
    status should equal(Some(DeploymentStatus.Ready))

    val exp = StoreOp.findDeploymentExpiration(dep.get.id).foldMap(config.storage).unsafeRunSync()
    exp.isDefined should equal(true)
  }

  it should "recursively create namespaces" in {
    val ns = NamespaceName("qa", List("sandbox", "foo", "bar"))
    Nelson.recursiveCreateNamespace(testName, ns).run(config).unsafeRunSync()

    val res =
      (for {
        root <- StoreOp.getNamespace(testName, NamespaceName("qa"))
        sand <- StoreOp.getNamespace(testName, NamespaceName("qa", List("sandbox")))
        foo <- StoreOp.getNamespace(testName, NamespaceName("qa", List("sandbox", "foo")))
        bar <- StoreOp.getNamespace(testName, NamespaceName("qa", List("sandbox", "foo", "bar")))
      } yield List(root, sand, foo, bar)
    ).foldMap(config.storage).unsafeRunSync()
    res.forall(_.isDefined) should equal (true)
  }

  it should "not create namespaces if datacenter does not exist" in {
    val ns = NamespaceName("foo", List("bar"))
    val res = Nelson.recursiveCreateNamespace("does-not-exists", ns).run(config).attempt.unsafeRunSync()
    res should equal (Left(UnknownDatacenter("does-not-exists")))
  }

  it should "create subordinate namespaces if root exists" in {
    val ns = NamespaceName("dev", List("bar"))
    val res = Nelson.recursiveCreateSubordinateNamespace(testName, ns).run(config).attempt.unsafeRunSync()
    res should equal (Right(()))

    val devbar = StoreOp.getNamespace(testName, ns).foldMap(config.storage).unsafeRunSync().get
    devbar.name should equal (ns)
  }

  it should "not create subordinate namespaces if root does not already exists" in {
    val ns = NamespaceName("does-not-exist", List("bar"))
    val res = Nelson.recursiveCreateSubordinateNamespace(testName, ns).run(config).attempt.unsafeRunSync()
    res should equal (Left(NamespaceCreateFailed("root namespace (does-not-exist) does not exist")))
  }

  it should "fetch load balancer by GUID" in {
    val ns = StoreOp.getNamespace(testName, NamespaceName("dev")).foldMap(config.storage).unsafeRunSync().get
    val lb = StoreOp.findLoadbalancerDeployment("lb", MajorVersion(1), ns.id).foldMap(config.storage).unsafeRunSync().get
    val guid = lb.guid
    val res = Nelson.fetchLoadbalancerDeployment(guid).run(config).unsafeRunSync().get

    (res.outboundDependencies.map(_.stackName.toString) should equal (Vector("conductor--1-1-1--abcd")))
    (res.loadbalancer.stackName.toString should equal("lb--1-0-0--hash"))
  }

  it should "fetch deployment by GUID" in {
    val sn = StackName("conductor", Version(1,1,1), "abcd")
    val dep = StoreOp.findDeployment(sn).foldMap(config.storage).unsafeRunSync().get
    val ma = Nelson.fetchDeployment(dep.guid).run(config).unsafeRunSync().get

    ma.deployment should equal (dep)
    ma.statuses.map(_._1) should equal (List(DeploymentStatus.Ready))
    ma.inboundDependencies.map(_._2.stackName.toString).toSet should equal (Set("lb--1-0-0--hash"))
    ma.outboundDependencies.map(_._2.stackName.toString).toSet should equal (Set("db--1-2-3--aaaa", "foo--1-10-100--aaaa", "search--2-2-2--aaaa", "ab--2-2-2--abcd"))
  }

  it should "fetch datacenter by name" in {
    val dc = Nelson.fetchDatacenterByName(testName).run(config).unsafeRunSync()
    dc.map(_._1.name) should equal (Some(testName))
  }

  it should "list datacenters" in {
    val dcs = Nelson.listDatacenters(config.pools.defaultExecutor).run(config).unsafeRunSync()
    dcs.keys.map(_.name).toSet should equal (Set(testName))
  }

  it should "create default namespace" in {
    val ns = NamespaceName("some-default")
    val cfg = config.copy(defaultNamespace = ns)
    val ns1 = StoreOp.getNamespace(testName, ns).foldMap(config.storage).unsafeRunSync()
    ns1 should equal (None)

    Nelson.createDefaultNamespaceIfAbsent(cfg.datacenters, cfg.defaultNamespace).run(cfg).unsafeRunSync()

    val ns2 = StoreOp.getNamespace(testName, ns).foldMap(config.storage).unsafeRunSync()
    ns2.map(_.name) should equal (Some(ns))
  }

  it should "list deployments" in {
    val deps = Nelson.listDeployments(config.datacenters.map(_.name),
      NonEmptyList.of(NamespaceName("dev")), NonEmptyList.of(DeploymentStatus.Ready), Some("conductor")).run(config).unsafeRunSync()

    deps.map(_._3.stackName.toString).toSet should equal (Set("conductor--1-1-1--abcd"))
  }

  it should "list units by status" in {
    val units = Nelson.listUnitsByStatus(config.datacenters.map(_.name),
      NonEmptyList.of(NamespaceName("dev", List("sandbox"))), NonEmptyList.of(DeploymentStatus.Ready)).run(config).unsafeRunSync()

    units.map(_._4).toSet should equal (Set(
      ServiceName("service-b", FeatureVersion(6,1)),
      ServiceName("service-c", FeatureVersion(6,2))))
  }
}

class NelsonProps extends Properties("Nelson"){
  import cats.kernel.laws.discipline.OrderTests
  import cats.instances.option._
  import Fixtures._

  include(OrderTests[java.time.Instant].order.all)
}

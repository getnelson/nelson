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

import cats.~>
import cats.effect.IO
import storage.StoreOp
import org.scalatest.BeforeAndAfterEach
import org.scalatest.prop.PropertyChecks
import org.scalacheck._
import cleanup._

class ExpirationPolicySpec extends NelsonSuite with BeforeAndAfterEach with PropertyChecks {
  import ExpirationPolicySpec._
  import nelson.routing.{RoutingNode,RoutingTable}
  import Datacenter._
  import DeploymentStatus._
  import scala.concurrent.duration._
  import quiver._
  import nelson.cleanup._
  import nelson.routing.{RoutePath,RoutingTable}
  import java.time.Instant

  override def beforeAll(): Unit = {
    super.beforeAll()
    insertFixtures(testName).foldMap(config.storage).unsafeRunSync()
    ()
  }

  val dc = config.datacenters.head

  val emptyG = quiver.empty[RoutingNode,Unit,RoutePath]

  val ext = 1.day

  private def getDeploymentCtx(sn: StackName)(storage: StoreOp ~> IO): DeploymentCtx = {
      (for {
        od <- StoreOp.findDeployment(sn)
        d =  od.get
        os <- StoreOp.getDeploymentStatus(d.id)
        s = os.get
        e <- StoreOp.findDeploymentExpiration(d.id)
      } yield DeploymentCtx(d,s,e)).foldMap(storage).unsafeRunSync()
  }

  private def makeDeployment(id: ID,name: String, version: Version, policy: ExpirationPolicy,
    hash: String = "hash", deployTime: Instant = NOW) = {
    val namespace = Datacenter.Namespace(1L, NamespaceName("dev"), "dc")
    val dc = DCUnit(id,name,version,"",Set.empty,Set.empty,Set.empty)
    Deployment(id,dc,hash,namespace,deployTime,"quasar","plan","guid",policy.name,None)
  }

  private def NOW = java.time.Instant.now


  it should "partition job deployments by name and namespace" in {
    val ns = StoreOp.getNamespace(dc.name, NamespaceName("dev")).foldMap(config.storage).unsafeRunSync().get
    val graph = RoutingTable.routingGraph(ns).foldMap(config.storage).unsafeRunSync()
    val map = ExpirationPolicyProcess.partitionByName(graph, ns)
    map.keys should equal (Set("inventory", "ab", "db", "job", "conductor", "crawler", "search", "foo", "service-a"))
    map.map { case (k,v) => v.forall(_.unit.name == k) }.foldLeft(true)(_ && _) should equal (true)

    val ns2 = StoreOp.getNamespace(dc.name, NamespaceName("dev", List("sandbox"))).foldMap(config.storage).unsafeRunSync().get
    val graph2 = RoutingTable.routingGraph(ns2).foldMap(config.storage).unsafeRunSync()
    val map2 = ExpirationPolicyProcess.partitionByName(graph2, ns2)
    map2.keys should equal (Set("service-b", "service-c"))


    val ns3 = StoreOp.getNamespace(dc.name, NamespaceName("dev", List("sandbox", "rodrigo"))).foldMap(config.storage).unsafeRunSync().get
    val graph3 = RoutingTable.routingGraph(ns3).foldMap(config.storage).unsafeRunSync()
    val map3 = ExpirationPolicyProcess.partitionByName(graph3, ns3)
    map3.keys should equal (Set("service-c"))
  }

  it should "get latest policy when there are multiple versions" in {

    val dep25 = makeDeployment(0L,"job",Version(2,5,100),RetainActive)

    val dep310 = makeDeployment(0L,"job",Version(3,1,0),RetainLatestTwoFeature)

    val dep311 = makeDeployment(1L,"job",Version(3,1,1),RetainLatestTwoMajor)

    val dep410 = makeDeployment(2L,"job",Version(4,1,0),RetainLatest)

    val policy1 = ExpirationPolicyProcess.getLatestPolicy(Vector(dep310,dep311,dep25))
    policy1.get should equal (RetainLatestTwoMajor)

    val policy2 = ExpirationPolicyProcess.getLatestPolicy(Vector(dep310,dep311,dep410,dep25))
    policy2.get should equal (RetainLatest)
  }

  it should "get latest policy when there are multiple versions that are the same" in {

    val dep310A = makeDeployment(0L,"job",Version(3,1,0),RetainLatest)

    val dep310B = makeDeployment(1L,"job",Version(3,1,0),RetainLatest)

    val dep310C = makeDeployment(2L,"job",Version(3,1,0),RetainLatest)

    val policy1 = ExpirationPolicyProcess.getLatestPolicy(Vector(dep310A,dep310B,dep310C))
    policy1.get should equal (RetainLatest)
  }

  it should "get latest policy when there is only one version" in {
    val job = makeDeployment(0L,"crawler",Version(5,1,0),RetainLatest)

    val policy = ExpirationPolicyProcess.getLatestPolicy(Vector(job))
    policy.get should equal (RetainLatest)
  }

  it should "retain the latest" in {
    val job310 = makeDeployment(0L, "job", Version(3,1,0), RetainLatest)
    val dep310 = DeploymentCtx(job310, Ready,Some(NOW))

    val job311 = makeDeployment(1L, "job", Version(3,1,1), RetainLatest)
    val dep311 = DeploymentCtx(job311,Ready, Some(NOW))

    val job410 = makeDeployment(2L,"job", Version(4,1,0), RetainLatest)
    val dep410 = DeploymentCtx(job410, Ready, Some(NOW))

    val job411 = makeDeployment(3L,"job", Version(4,1,1), RetainLatest)
    val dep411 = DeploymentCtx(job411, Ready, Some(NOW))

    val g = emptyG &
      Context(Vector(), RoutingNode(dep310.deployment), (), Vector()) &
      Context(Vector(), RoutingNode(dep311.deployment), (), Vector()) &
      Context(Vector(), RoutingNode(dep410.deployment), (), Vector()) &
      Context(Vector(), RoutingNode(dep411.deployment), (), Vector())

    RetainLatest.policy(dep310, g)(ext) should equal (None)
    RetainLatest.policy(dep311, g)(ext) should equal (None)
    RetainLatest.policy(dep410, g)(ext) should equal (None)
    RetainLatest.policy(dep411, g)(ext) should equal (Some(ext))
  }

  it should "not retain the latest if deployment is deprecated" in {
    val job310 = makeDeployment(0L, "job", Version(3,1,0), RetainLatest)
    val dep310 = DeploymentCtx(job310, Ready,Some(NOW))

    val job311 = makeDeployment(1L, "job", Version(3,1,1), RetainLatest)
    val dep311 = DeploymentCtx(job311,Ready, Some(NOW))

    val job410 = makeDeployment(2L,"job", Version(4,1,0), RetainLatest)
    val dep410 = DeploymentCtx(job410, Ready, Some(NOW))

    val job411 = makeDeployment(3L,"job", Version(4,1,1), RetainLatest)
    val dep411 = DeploymentCtx(job411, Deprecated, Some(NOW))

    val g = emptyG &
      Context(Vector(), RoutingNode(dep310.deployment), (), Vector()) &
      Context(Vector(), RoutingNode(dep311.deployment), (), Vector()) &
      Context(Vector(), RoutingNode(dep410.deployment), (), Vector()) &
      Context(Vector(), RoutingNode(dep411.deployment), (), Vector())

    RetainLatest.policy(dep310, g)(ext) should equal (None)
    RetainLatest.policy(dep311, g)(ext) should equal (None)
    RetainLatest.policy(dep410, g)(ext) should equal (None)
    RetainLatest.policy(dep411, g)(ext) should equal (None)
  }

  it should "retain the latest with matrix deployment" in {

    val deploy = NOW

    val job411a = makeDeployment(3L,"job", Version(4,1,1), RetainLatest, "hash1", deploy)
    val dep411a = DeploymentCtx(job411a, Ready, Some(NOW))

    val job411b = makeDeployment(4L,"job", Version(4,1,1), RetainLatest, "hash2", deploy.plusSeconds(100))
    val dep411b = DeploymentCtx(job411b, Ready, Some(NOW))

    val g = emptyG &
      Context(Vector(), RoutingNode(dep411a.deployment), (), Vector()) &
      Context(Vector(), RoutingNode(dep411b.deployment), (), Vector())

    RetainLatest.policy(dep411a, g)(ext) should equal (Some(ext))
    RetainLatest.policy(dep411b, g)(ext) should equal (Some(ext))
  }

  it should "retain the latest two major versions" in {
    val job310 = makeDeployment(0L, "job", Version(3,1,0), RetainLatestTwoMajor)
    val dep310 = DeploymentCtx(job310, Ready, Some(NOW))

    val job311 = makeDeployment(1L, "job", Version(3,1,1), RetainLatestTwoMajor)
    val dep311 = DeploymentCtx(job311, Ready, Some(NOW))

    val job410 = makeDeployment(2L, "job", Version(4,1,0), RetainLatestTwoMajor)
    val dep410 = DeploymentCtx(job410, Ready, Some(NOW))

    val job411 = makeDeployment(3L,"job", Version(4,1,1), RetainLatestTwoMajor)
    val dep411 = DeploymentCtx(job411, Ready, Some(NOW))

    val g = emptyG &
      Context(Vector(), RoutingNode(dep310.deployment), (), Vector()) &
      Context(Vector(), RoutingNode(dep311.deployment), (), Vector()) &
      Context(Vector(), RoutingNode(dep410.deployment), (), Vector()) &
      Context(Vector(), RoutingNode(dep411.deployment), (), Vector())

    RetainLatestTwoMajor.policy(dep310, g)(ext) should equal (None)
    RetainLatestTwoMajor.policy(dep311, g)(ext) should equal (Some(ext))
    RetainLatestTwoMajor.policy(dep410, g)(ext) should equal (None)
    RetainLatestTwoMajor.policy(dep411, g)(ext) should equal (Some(ext))
  }

  it should "not retain the latest two major versions if deployment is deprecate" in {
    val job310 = makeDeployment(0L, "job", Version(3,1,0), RetainLatestTwoMajor)
    val dep310 = DeploymentCtx(job310, Ready, Some(NOW))

    val job311 = makeDeployment(1L, "job", Version(3,1,1), RetainLatestTwoMajor)
    val dep311 = DeploymentCtx(job311, Deprecated, Some(NOW))

    val job410 = makeDeployment(2L, "job", Version(4,1,0), RetainLatestTwoMajor)
    val dep410 = DeploymentCtx(job410, Ready, Some(NOW))

    val job411 = makeDeployment(3L,"job", Version(4,1,1), RetainLatestTwoMajor)
    val dep411 = DeploymentCtx(job411, Deprecated, Some(NOW))

    val g = emptyG &
      Context(Vector(), RoutingNode(dep310.deployment), (), Vector()) &
      Context(Vector(), RoutingNode(dep311.deployment), (), Vector()) &
      Context(Vector(), RoutingNode(dep410.deployment), (), Vector()) &
      Context(Vector(), RoutingNode(dep411.deployment), (), Vector())

    RetainLatestTwoMajor.policy(dep310, g)(ext) should equal (None)
    RetainLatestTwoMajor.policy(dep311, g)(ext) should equal (None)
    RetainLatestTwoMajor.policy(dep410, g)(ext) should equal (None)
    RetainLatestTwoMajor.policy(dep411, g)(ext) should equal (None)
  }

  it should "retain the latest two major versions with matrix deployment" in {
    val job310 = makeDeployment(0L, "job", Version(3,1,0), RetainLatestTwoMajor)
    val dep310 = DeploymentCtx(job310, Ready, Some(NOW))

    val job311 = makeDeployment(1L, "job", Version(3,1,0), RetainLatestTwoMajor)
    val dep311 = DeploymentCtx(job311, Ready, Some(NOW))

    val job410 = makeDeployment(2L, "job", Version(4,1,0), RetainLatestTwoMajor)
    val dep410 = DeploymentCtx(job410, Ready, Some(NOW))

    val job411 = makeDeployment(3L,"job", Version(4,1,0), RetainLatestTwoMajor)
    val dep411 = DeploymentCtx(job411, Ready, Some(NOW))

    val g = emptyG &
      Context(Vector(), RoutingNode(dep310.deployment), (), Vector()) &
      Context(Vector(), RoutingNode(dep311.deployment), (), Vector()) &
      Context(Vector(), RoutingNode(dep410.deployment), (), Vector()) &
      Context(Vector(), RoutingNode(dep411.deployment), (), Vector())

    RetainLatestTwoMajor.policy(dep310, g)(ext) should equal (Some(ext))
    RetainLatestTwoMajor.policy(dep311, g)(ext) should equal (Some(ext))
    RetainLatestTwoMajor.policy(dep410, g)(ext) should equal (Some(ext))
    RetainLatestTwoMajor.policy(dep411, g)(ext) should equal (Some(ext))
  }

  it should "retain the latest only when there is only one feature version" in {

    val job300 = makeDeployment(0L, "job", Version(3,0,0), RetainLatestTwoFeature)
    val dep300 = DeploymentCtx(job300, Ready, Some(NOW))

    val job310 = makeDeployment(1L, "job", Version(3,1,0), RetainLatestTwoFeature)
    val dep310 = DeploymentCtx(job310, Ready, Some(NOW))

    val job311 = makeDeployment(2L, "job", Version(3,1,1), RetainLatestTwoFeature)
    val dep311 = DeploymentCtx(job311, Ready, Some(NOW))

    val job410 = makeDeployment(3L, "job", Version(4,1,0), RetainLatestTwoFeature)
    val dep410 = DeploymentCtx(job410, Ready, Some(NOW))

    val g = emptyG &
      Context(Vector(), RoutingNode(dep300.deployment), (), Vector()) &
      Context(Vector(), RoutingNode(dep310.deployment), (), Vector()) &
      Context(Vector(), RoutingNode(dep311.deployment), (), Vector()) &
      Context(Vector(), RoutingNode(dep410.deployment), (), Vector())

    RetainLatestTwoFeature.policy(dep300, g)(ext) should equal (None)
    RetainLatestTwoFeature.policy(dep310, g)(ext) should equal (None)
    RetainLatestTwoFeature.policy(dep311, g)(ext) should equal (None)
    RetainLatestTwoFeature.policy(dep410, g)(ext) should equal (Some(ext))
  }

  it should "retain the latest two feature versions when there are two" in {
    val job300 = makeDeployment(0L, "job", Version(3,0,0), RetainLatestTwoFeature)
    val dep300 = DeploymentCtx(job300, Ready, Some(NOW))

    val job310 = makeDeployment(1L, "job", Version(3,1,0), RetainLatestTwoFeature)
    val dep310 = DeploymentCtx(job310, Ready, Some(NOW))

    val job311 = makeDeployment(2L, "job", Version(3,1,1), RetainLatestTwoFeature)
    val dep311 = DeploymentCtx(job311, Ready, Some(NOW))

    val g = emptyG &
      Context(Vector(), RoutingNode(dep300.deployment), (), Vector()) &
      Context(Vector(), RoutingNode(dep310.deployment), (), Vector()) &
      Context(Vector(), RoutingNode(dep311.deployment), (), Vector())

    RetainLatestTwoFeature.policy(dep310, g)(ext) should equal (None)
    RetainLatestTwoFeature.policy(dep311, g)(ext) should equal (Some(ext))
    RetainLatestTwoFeature.policy(dep300, g)(ext) should equal (Some(ext))
  }

  it should "retain the latest two feature versions with matrix deployment" in {
    val job300 = makeDeployment(0L, "job", Version(3,0,0), RetainLatestTwoFeature)
    val dep300 = DeploymentCtx(job300, Ready, Some(NOW))

    val job310 = makeDeployment(1L, "job", Version(3,1,0), RetainLatestTwoFeature)
    val dep310 = DeploymentCtx(job310, Ready, Some(NOW))

    val job311 = makeDeployment(2L, "job", Version(3,1,1), RetainLatestTwoFeature)
    val dep311 = DeploymentCtx(job311, Ready, Some(NOW))

    val job311b = makeDeployment(3L, "job", Version(3,1,1), RetainLatestTwoFeature)
    val dep311b = DeploymentCtx(job311b, Ready, Some(NOW))

    val g = emptyG &
      Context(Vector(), RoutingNode(dep300.deployment), (), Vector()) &
      Context(Vector(), RoutingNode(dep310.deployment), (), Vector()) &
      Context(Vector(), RoutingNode(dep311.deployment), (), Vector())

    RetainLatestTwoFeature.policy(dep310, g)(ext) should equal (None)
    RetainLatestTwoFeature.policy(dep300, g)(ext) should equal (Some(ext))
    RetainLatestTwoFeature.policy(dep311, g)(ext) should equal (Some(ext))
    RetainLatestTwoFeature.policy(dep311b, g)(ext) should equal (Some(ext))
  }

  it should "not retain the latest two feature versions if deployment is deprecated" in {
    val job300 = makeDeployment(0L, "job", Version(3,0,0), RetainLatestTwoFeature)
    val dep300 = DeploymentCtx(job300, Deprecated, Some(NOW))

    val job310 = makeDeployment(1L, "job", Version(3,1,0), RetainLatestTwoFeature)
    val dep310 = DeploymentCtx(job310, Ready, Some(NOW))

    val job311 = makeDeployment(2L, "job", Version(3,1,1), RetainLatestTwoFeature)
    val dep311 = DeploymentCtx(job311, Deprecated, Some(NOW))

    val g = emptyG &
      Context(Vector(), RoutingNode(dep300.deployment), (), Vector()) &
      Context(Vector(), RoutingNode(dep310.deployment), (), Vector()) &
      Context(Vector(), RoutingNode(dep311.deployment), (), Vector())

    RetainLatestTwoFeature.policy(dep310, g)(ext) should equal (None)
    RetainLatestTwoFeature.policy(dep311, g)(ext) should equal (None)
    RetainLatestTwoFeature.policy(dep300, g)(ext) should equal (None)
  }

  it should "retain until deprecated" in {

    val job300 = makeDeployment(1L, "job", Version(3,0,0), RetainLatestTwoFeature)
    val dep300 = DeploymentCtx(job300, Ready, Some(NOW))

    val job310 = makeDeployment(2L, "job", Version(3,1,0), RetainLatestTwoFeature)
    val dep310 = DeploymentCtx(job310, Ready, Some(NOW))

    val job311 = makeDeployment(3L, "job", Version(3,1,1), RetainLatestTwoFeature)
    val dep311 = DeploymentCtx(job311, Deprecated, Some(NOW))

    val job410 = makeDeployment(4L,"job", Version(4,1,0), RetainLatestTwoFeature)
    val dep410 = DeploymentCtx(job410, Deprecated, Some(NOW))

    RetainUntilDeprecated.policy(dep300, emptyG)(ext) should equal (Some(ext))
    RetainUntilDeprecated.policy(dep310, emptyG)(ext) should equal (Some(ext))
    RetainUntilDeprecated.policy(dep311, emptyG)(ext) should equal (None)
    RetainUntilDeprecated.policy(dep410, emptyG)(ext) should equal (None)
  }

  it should "retain all active versions" in {

    val job300 = makeDeployment(0L, "job", Version(3,0,0), RetainActive)
    val dep300 = DeploymentCtx(job300, Ready, Some(NOW))

    val job310 = makeDeployment(1L, "job", Version(3,1,0), RetainActive)
    val dep310 = DeploymentCtx(job310, Ready, Some(NOW))

    val job311 = makeDeployment(2L, "job", Version(3,1,1), RetainActive)
    val dep311 = DeploymentCtx(job311, Ready, Some(NOW))

    val g = emptyG &
      Context(Vector(), RoutingNode(dep300.deployment), (), Vector()) & // no incomming links, not active
      Context(Vector((RoutePath(dep300.deployment,"","",80,80),RoutingNode(dep310.deployment))), RoutingNode(dep310.deployment), (), Vector()) &
      Context(Vector((RoutePath(dep310.deployment,"","",80,80),RoutingNode(dep311.deployment))), RoutingNode(dep311.deployment), (), Vector())

    RetainActive.policy(dep300, g)(ext) should equal (None)
    RetainActive.policy(dep310, g)(ext) should equal (Some(ext))
    RetainActive.policy(dep311, g)(ext) should equal (Some(ext))
  }

  it should "retain all active versions when a loadbalancer depends on deployment" in {

    val lb = LoadbalancerDeployment(0L, 0L, "hash",
      DCLoadbalancer(0L,"lb",MajorVersion(1),Vector()), Instant.now(), "guid", "dns")

    val job310 = makeDeployment(1L, "job", Version(3,1,0), RetainActive)
    val dep310 = DeploymentCtx(job310, Ready, Some(NOW))

    val job311 = makeDeployment(2L, "job", Version(3,1,1), RetainActive)
    val dep311 = DeploymentCtx(job311, Ready, Some(NOW))

    val g = emptyG &
      Context(Vector(), RoutingNode(lb), (), Vector()) &
      Context(Vector(), RoutingNode(dep310.deployment), (), Vector()) &
      Context(Vector((RoutePath(dep310.deployment,"","",80,80), RoutingNode(lb))), RoutingNode(dep311.deployment), (), Vector())

    RetainActive.policy(dep310, g)(ext) should equal (None)
    RetainActive.policy(dep311, g)(ext) should equal (Some(ext))
  }

  it should "retain all active versions even if they are deprecated" in {

    val job300 = makeDeployment(0L, "job", Version(3,0,0), RetainActive)
    val dep300 = DeploymentCtx(job300, Ready, Some(NOW))

    val job310 = makeDeployment(1L, "job", Version(3,1,0), RetainActive)
    val dep310 = DeploymentCtx(job310, Ready, Some(NOW))

    val job311 = makeDeployment(2L, "job", Version(3,1,1), RetainActive)
    val dep311 = DeploymentCtx(job311, Deprecated, Some(NOW))

    val g = emptyG &
      Context(Vector(), RoutingNode(dep300.deployment), (), Vector()) & // no incomming links, not active
      Context(Vector((RoutePath(dep300.deployment,"","",80,80),RoutingNode(dep310.deployment))), RoutingNode(dep310.deployment), (), Vector()) &
      Context(Vector((RoutePath(dep310.deployment,"","",80,80),RoutingNode(dep311.deployment))), RoutingNode(dep311.deployment), (), Vector())

    RetainActive.policy(dep300, g)(ext) should equal (None)
    RetainActive.policy(dep310, g)(ext) should equal (Some(ext))
    RetainActive.policy(dep311, g)(ext) should equal (Some(ext))
  }

  it should "update expirations in the database" in {
    val job311 = StackName("job", Version(3,1,1), "zzzz1")
    val dep311 = getDeploymentCtx(job311)(config.storage)
    StoreOp.createDeploymentExpiration(dep311.deployment.id, java.time.Instant.now()).foldMap(config.storage).unsafeRunSync()
    val dep311After = getDeploymentCtx(job311)(config.storage)

    val tomorrow = java.time.Instant.now().plusSeconds(1.day.toSeconds)

    val before = StoreOp.findDeploymentExpiration(dep311.deployment.id).foldMap(config.storage).unsafeRunSync().get

    assert(before.isBefore(tomorrow))

    ExpirationPolicyProcess.updateExpiration(dep311After,2.days).foldMap(config.storage).unsafeRunSync()

    val after = StoreOp.findDeploymentExpiration(dep311.deployment.id).foldMap(config.storage).unsafeRunSync().get

    assert(after.isAfter(tomorrow))
  }

  it should "return Some in an ExpirationPolicy/String round trip" in {
    forAll { (p: ExpirationPolicy) =>
      ExpirationPolicy.fromString(p.name) should equal (Some(p))
    }
  }

  it should "return None when calling fromString on a bogus string" in {
    ExpirationPolicy.fromString("foo") should equal (None)
  }
}
object ExpirationPolicySpec {
  implicit val arbExpirationPolicy: Arbitrary[ExpirationPolicy] =
    Arbitrary(Gen.oneOf(ExpirationPolicy.policies.toSeq))
}


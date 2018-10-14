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
//
//    dev namespace
// +-------------------+
// | lb--1-0-0--hash   |
// +-------------------+
//           |
//           |                   singleton                         Shift:
// +------------------------+     +-----------------+         95%  +------------------------+
// | conductor--1-1-1--abcd |---->| ab--2-2-2--abcd |------+------>| inventory--1-2-2--ffff |
// +------------------------+     +-----------------+      |       +------------------------+
//           |                                             |  5%   +------------------------+
//           |                   should not be found:      +------>| inventory--1-2-3--ffff |
//           |                    +-----------------+              +------------------------+
//           |                    | ab--2-2-1--abcd |
//           |                    +-----------------+
//           |
//           |                 newer version but shouldn't be found
//           |                  +------------------+
//           |                  | foo--2-0-0--bbbb |
//           |                  +------------------+
//           |
//           |                  +---------------------+
//           +----------------->| foo--1-10-100--aaaa |<------------------------+
//           |                  +-------------------- +                         |
//           |                                                                  |
//           |                  newer singleton                                 |
//           |                  +---------------------+                         |
//           +----------------->| search--2-2-2--aaaa |                         |
//           |                  +---------------------+                         |
//           |                  older, should not be found                      |
//           |                  +---------------------+                         |
//           |                  | search--2-2-2--bbbb |                         |
//           |                  +---------------------+                         |
//           |                                                                  |
//           |                  +---------------------+                         |
//           +----------------->|   db--1-2-3--aaaa   | ← manually deployed     |
//           |                  +---------------------+                         |
//           |                                                                  |
//           |                  +--------------------------+                    |
//           +----------------->|  service-a--6-0-0--aaaa  |                    |
//                              +--------------------------+                    |
//                                 |                                            |<---------------------+
//                                 |                                            |                      |
//                                 |       dev/sandbox namespace                |                      |
//                                 |   +-------------------------+     +---------------------------+   |
//                                 +-> |  service-b--6-1-0--aaaa |---->|  service-c--6-2-0--aaaa   |   |
//                                     +-------------------------+     +---------------------------+   |
//                                                           |                                         |
//                                                           |             dev/sandbox/rodrigo         |
//                                                           |         +---------------------------+   |
//                                                           +-------> |  service-c--6-2-1--bbbb   |---+
//                                                                     +---------------------------+
//  qa namespace
//
//
//
// long running chronos job
// +----------------------+
// | job--4.1.0--zzzz2    |
// +----------------------+
//       +----------------------+ +--------------------+ +------------------+
//       | job--3.1.1--zzzz     | | job--3.1.0--zzzz   | | job-3.0.0--zzzz  |
//       +----------------------+ +--------------------+ +------------------+
//
//
//



trait RoutingFixtures {
  import nelson.Datacenter.{Port => _, _}
  import nelson.Manifest.Deployable.Container
  import nelson.Manifest.{Namespace => _, _}
  import nelson.Util._
  import nelson.Workflow.WorkflowF
  import nelson.cleanup._
  import nelson.notifications.NotificationSubscriptions
  import nelson.storage.{StoreOp, StoreOpF}

  import cats.Order
  import cats.data.NonEmptyList
  import cats.implicits._

  import java.time.Instant

  import scala.concurrent.duration._

  def insertFixtures(name: String, namespaceName: String = "dev"): StoreOpF[Manifest.Namespace] = {

    val devNs = NamespaceName(namespaceName)

    val sandboxNs = NamespaceName(namespaceName, List("sandbox"))

    val rodrigoNs = NamespaceName(namespaceName, List("sandbox", "rodrigo"))

    val dev = Manifest.Namespace(name = devNs,
                                 units = Set(),
                                 loadbalancers = Set())

    val sandbox = Manifest.Namespace(name = sandboxNs,
                                  units = Set(),
                                  loadbalancers = Set())

    val sandboxRodrigo = Manifest.Namespace(name = rodrigoNs,
                                  units = Set(),
                                  loadbalancers = Set())

    val dc = datacenter(name)

    for {
      repoids <- StoreOp.insertOrUpdateRepositories(List(repo.toOption.get))
      _ <- ingestReleases
      _  <- StoreOp.createDatacenter(dc)
      id <- StoreOp.createNamespace(name, devNs)
      id2 <- StoreOp.createNamespace(name, sandboxNs)
      id3 <- StoreOp.createNamespace(name, rodrigoNs)
      ns = Namespace(id, devNs, name)
      ns2 = Namespace(id2, sandboxNs, name)
      ns3 = Namespace(id3, rodrigoNs, name)
      _ <- ingestAll(dev, sandbox, sandboxRodrigo)
      _ <- deployAll(dc, ns, ns2, ns3)
      _ <- createManualInventoryShift(id)
    } yield dev
  }

  val testPolicyConfig = PolicyConfig(
    resourceCredsPath = "test/%env%/%resource%/creds/%unit%",
    pkiPath = Some("pki/%env%")
  )

  def datacenter(name: String) =
    Datacenter(
      name,
      Infrastructure.Docker(""),
      Infrastructure.Domain(""),
      Infrastructure.TrafficShift(LinearShiftPolicy, 1.minutes),
      None,
      Infrastructure.Interpreters(
        stubbedInterpreter,
        stubbedInterpreter,
        stubbedInterpreter,
        stubbedInterpreter,
        stubbedInterpreter,
        stubbedInterpreter,
        stubbedInterpreter,
        stubbedInterpreter
      ),
      None,
      testPolicyConfig
    )

  val emptyWorkflow = new Workflow[Unit] {
    override val name = "empty"
    def deploy(id: ID, hash: String, unit: Manifest.UnitDef @@ Manifest.Versioned, e: Manifest.Plan, dc: Datacenter, ns: Manifest.Namespace): WorkflowF[Unit] =
      ().pure[WorkflowF]
    def destroy(d: Deployment, dc: Datacenter, ns: Namespace): WorkflowF[Unit] =
      ().pure[WorkflowF]
  }

  def mkDeployment(id: Long, tag: String): Github.DeploymentEvent =
    Github.DeploymentEvent(id, Slug("foo","bar"), 9999L, 
      Github.Reference.fromString(tag), "dev", Nil, "")

  val slug = Slug("owner","RoutingTableSpec")
  val repo = Repo(9999L, slug.toString, RepoAccess.Admin.toString, None)
  val release100    = mkDeployment(100L, "1.0.0")
  val release110    = mkDeployment(110L, "1.1.0")
  val release110100 = mkDeployment(110100L, "1.10.100")
  val release111    = mkDeployment(111L, "1.1.1")
  val release200    = mkDeployment(200L, "2.0.0")
  val release222    = mkDeployment(222L, "2.2.2")
  val release221    = mkDeployment(221L, "2.2.1")
  val release122    = mkDeployment(122L, "1.2.2")
  val release123    = mkDeployment(123L, "1.2.3")
  val release300    = mkDeployment(300L, "3.0.0")
  val release310    = mkDeployment(310L, "3.1.0")
  val release311    = mkDeployment(311L, "3.1.1")
  val release410    = mkDeployment(410L, "4.1.0")
  val release510    = mkDeployment(510L, "5.1.0")
  val release600    = mkDeployment(600L, "6.0.0")
  val release610    = mkDeployment(610L, "6.1.0")
  val release620    = mkDeployment(620L, "6.2.0")
  val release621    = mkDeployment(621L, "6.2.1")

  val ingestReleases: StoreOpF[Unit] =
    StoreOp.createRelease(release100) *>
    StoreOp.createRelease(release110) *>
    StoreOp.createRelease(release110100) *>
    StoreOp.createRelease(release111) *>
    StoreOp.createRelease(release200) *>
    StoreOp.createRelease(release222) *>
    StoreOp.createRelease(release221) *>
    StoreOp.createRelease(release122) *>
    StoreOp.createRelease(release123) *>
    StoreOp.createRelease(release300) *>
    StoreOp.createRelease(release310) *>
    StoreOp.createRelease(release311) *>
    StoreOp.createRelease(release410) *>
    StoreOp.createRelease(release510) *>
    StoreOp.createRelease(release600) *>
    StoreOp.createRelease(release610) *>
    StoreOp.createRelease(release620) *>
    StoreOp.createRelease(release621)

  def lbManifest(ns: Manifest.Namespace) =
    Manifest(
      Nil, Nil,
      List(Loadbalancer("lb", Vector(Route(Port("default",9000,"http"), BackendDestination("conductor", "default"))),
        Some(MajorVersion(1)))),
      Nil, DeploymentTarget.Except(Nil), NotificationSubscriptions.empty
    )

  def abManifest1( ns: Manifest.Namespace) =
    Manifest(List(UnitDef("ab", "",
                          Map("inventory" -> FeatureVersion(1,2)),
                          Set.empty,
                          Alerting.empty,
                          Some(Ports(Port("default", 1, "http"),Nil)),
                          Some(Deployable("ab", Version(2,2,2), Container(""))),
                          Set.empty[String]
                      )),
             Nil,
             Nil,
             ns.copy(units=Set(("ab",Set()))) :: Nil,
             DeploymentTarget.Except(Nil),
             NotificationSubscriptions.empty
           )

  def abManifest2(ns: Manifest.Namespace) =
    Manifest(List(UnitDef("ab", "",
                          Map("inventory" -> FeatureVersion(1,2)),
                          Set.empty,
                          Alerting.empty,
                          Some(Ports(Port("default", 1, "http"), Nil)),
                          Some(Deployable("ab", Version(2,2,1), Container(""))),
                          Set.empty[String]
                      )),
             Nil,
             Nil,
             ns.copy(units=Set(("ab",Set()))) :: Nil,
             DeploymentTarget.Except(Nil),
             NotificationSubscriptions.empty
           )

  def fooManifestOld(ns: Manifest.Namespace) =
    Manifest(List(UnitDef("foo", "",
                          Map.empty,
                          Set.empty,
                          Alerting.empty,
                          Some(Ports(Port("default", 1, "http"), Nil)),
                          Some(Deployable("foo", Version(1,10,100), Container(""))),
                          Set.empty[String]
                      )),
             Nil,
             Nil,
             ns.copy(units=Set(("foo",Set()))) :: Nil,
             DeploymentTarget.Except(Nil),
             NotificationSubscriptions.empty
           )

  def fooManifestNew(ns: Manifest.Namespace) =
    Manifest(List(UnitDef("foo", "",
                          Map.empty,
                          Set.empty,
                          Alerting.empty,
                          Some(Ports(Port("default", 1, "http"), Nil)),
                          Some(Deployable("foo", Version(2,0,0), Container(""))),
                          Set.empty[String]
                      )),
             Nil,
             Nil,
             ns.copy(units=Set(("foo",Set()))) :: Nil,
             DeploymentTarget.Except(Nil),
             NotificationSubscriptions.empty
           )

  def searchManifest(ns: Manifest.Namespace) =
    Manifest(List(UnitDef("search", "",
                          Map.empty,
                          Set.empty,
                          Alerting.empty,
                          Some(Ports(Port("default", 1, "http"), Nil)),
                          Some(Deployable("search", Version(2,2,2), Container(""))),
                          Set.empty[String]
                      )),
             Nil,
             Nil,
             ns.copy(units=Set(("search",Set()))) :: Nil,
             DeploymentTarget.Except(Nil),
             NotificationSubscriptions.empty
           )

  def inventoryManifest1( ns: Manifest.Namespace) =
    Manifest(List(UnitDef("inventory", "",
                          Map.empty,
                          Set.empty,
                          Alerting.empty,
                          Some(Ports(Port("default", 1, "http"), Nil)),
                          Some(Deployable("inventory", Version(1,2,2), Container(""))),
                          Set.empty[String]
                    )),
             Nil,
             Nil,
             ns.copy(units=Set(("inventory",Set()))) :: Nil,
             DeploymentTarget.Except(Nil),
             NotificationSubscriptions.empty
           )

  def inventoryManifest2( ns: Manifest.Namespace) =
    Manifest(List(UnitDef("inventory", "",
                          Map.empty,
                          Set.empty,
                          Alerting.empty,
                          Some(Ports(Port("default", 1, "http"), Nil)),
                          Some(Deployable("inventory", Version(1,2,3), Container(""))),
                          Set.empty[String]
                      )),
             Nil,
             Nil,
             ns.copy(units=Set(("inventory",Set()))) :: Nil,
             DeploymentTarget.Except(Nil),
             NotificationSubscriptions.empty
           )

  def conductorManifest( ns: Manifest.Namespace) =
    Manifest(List(UnitDef("conductor", "",
                           Map("foo" -> FeatureVersion(1,10),
                               "ab" -> FeatureVersion(2,2),
                               "db" -> FeatureVersion(1,2),
                               "search" -> FeatureVersion(2,2)),
                          Set.empty,
                          Alerting.empty,
                          Some(Ports(Port("default", 1, "http"), Nil)),
                          Some(Deployable("conductor", Version(1,1,1), Container(""))),
                          Set.empty[String]
                      )),
             Nil,
             Nil,
             ns.copy(units=Set(("conductor",Set()))) :: Nil,
             DeploymentTarget.Except(Nil),
             NotificationSubscriptions.empty
           )

  def undeployable( ns: Manifest.Namespace) =
    Manifest(List(UnitDef("undeployable", "",
                          Map("nonexistant" -> FeatureVersion(1,0),
                              "ab" -> FeatureVersion(10,0),
                              "search" -> FeatureVersion(2,2)),
                          Set.empty,
                          Alerting.empty,
                          Some(Ports(Port("default", 1, "http"), Nil)),
                          None,
                          Set.empty[String]
                        )),
             Nil,
             Nil,
             ns.copy(units=Set(("undeployable",Set()))) :: Nil,
             DeploymentTarget.Except(Nil),
             NotificationSubscriptions.empty
           )

  def job300(ns: Manifest.Namespace) =
    Manifest(List(UnitDef("job","",
                          Map.empty,
                          Set.empty,
                          Alerting.empty,
                          None,
                          Some(Deployable("job", Version(3,0,0), Container(""))),
                          Set.empty[String]
                      )),
             Nil,
             Nil,
             ns.copy(units=Set(("job",Set()))) :: Nil,
             DeploymentTarget.Except(Nil),
             NotificationSubscriptions.empty
           )

  def job310(ns: Manifest.Namespace) =
    Manifest(List(UnitDef("job","",
                          Map.empty,
                          Set.empty,
                          Alerting.empty,
                          None,
                          Some(Deployable("job", Version(3,1,0), Container(""))),
                          Set.empty[String]
                      )),
             Nil,
             Nil,
             ns.copy(units=Set(("job",Set()))) :: Nil,
             DeploymentTarget.Except(Nil),
             NotificationSubscriptions.empty
           )

  def job311(ns: Manifest.Namespace) =
    Manifest(List(UnitDef("job","",
                          Map.empty,
                          Set.empty,
                          Alerting.empty,
                          None,
                          Some(Deployable("job", Version(3,1,1), Container(""))),
                          Set.empty[String]
                    )),
             Nil,
             Nil,
             ns.copy(units=Set(("job",Set()))) :: Nil,
             DeploymentTarget.Except(Nil),
             NotificationSubscriptions.empty
           )

  def job410(ns: Manifest.Namespace) =
    Manifest(List(UnitDef("job","",
                          Map.empty,
                          Set.empty,
                          Alerting.empty,
                          None,
                          Some(Deployable("job", Version(4,1,0), Container(""))),
                          Set.empty[String]
                      )),
             Nil,
             Nil,
             ns.copy(units=Set(("job",Set()))) :: Nil,
             DeploymentTarget.Except(Nil),
             NotificationSubscriptions.empty
           )

  def crawler(ns: Manifest.Namespace) =
    Manifest(List(UnitDef("crawler","",
                          Map.empty,
                          Set.empty,
                          Alerting.empty,
                          None,
                          Some(Deployable("crawler", Version(5,1,0), Container(""))),
                          Set.empty[String]
                      )),
             Nil,
             Nil,
             ns.copy(units=Set(("crawler",Set()))) :: Nil,
             DeploymentTarget.Except(Nil),
             NotificationSubscriptions.empty
           )

  def deprecated(ns: Manifest.Namespace) =
    Manifest(List(UnitDef("search", "",
                          Map.empty,
                          Set.empty,
                          Alerting.empty,
                          Some(Ports(Port("default", 1, "http"), Nil)),
                          Some(Deployable("search", Version(1,1,0), Container(""))),
                          Set.empty[String]
                      )),
             Nil,
             Nil,
             ns.copy(units=Set(("search",Set()))) :: Nil,
             DeploymentTarget.Except(Nil),
             NotificationSubscriptions.empty
           )

  def undeployableDeprecatedDep(ns: Manifest.Namespace) =
    Manifest(List(UnitDef("deprecated", "",
                          Map("search" -> FeatureVersion(1,1)),
                          Set.empty,
                          Alerting.empty,
                          Some(Ports(Port("default", 1, "http"), Nil)),
                          None,
                          Set.empty[String]
                      )),
             Nil,
             Nil,
             ns.copy(units=Set(("deprecated",Set()))) :: Nil,
             DeploymentTarget.Except(Nil),
             NotificationSubscriptions.empty
           )

  def serviceAManifest(ns: Manifest.Namespace) =
    Manifest(List(UnitDef("service-a", "",
                          Map("service-b" -> FeatureVersion(6,1)),
                          Set.empty,
                          Alerting.empty,
                          Some(Ports(Port("default", 1, "http"), Nil)),
                          Some(Deployable("service-a", Version(6,0,0), Container(""))),
                          Set.empty[String]
                      )),
             Nil,
             Nil,
             ns.copy(units=Set(("service-a",Set()))) :: Nil,
             DeploymentTarget.Except(Nil),
             NotificationSubscriptions.empty
           )

  def serviceBManifest(ns: Manifest.Namespace) =
    Manifest(List(UnitDef("service-b", "",
                          Map("service-c" -> FeatureVersion(6,2)),
                          Set.empty,
                          Alerting.empty,
                          Some(Ports(Port("default", 1, "http"), Nil)),
                          Some(Deployable("service-b", Version(6,1,0), Container(""))),
                          Set.empty[String]
                      )),
             Nil,
             Nil,
             ns.copy(units=Set(("service-b",Set()))) :: Nil,
             DeploymentTarget.Except(Nil),
             NotificationSubscriptions.empty
           )

  def serviceCManifest(ns: Manifest.Namespace) =
    Manifest(List(UnitDef("service-c", "",
                          Map("foo" -> FeatureVersion(1,10)),
                          Set.empty,
                          Alerting.empty,
                          Some(Ports(Port("default", 1, "http"), Nil)),
                          Some(Deployable("service-c", Version(6,2,0), Container(""))),
                          Set.empty[String]
                      )),
             Nil,
             Nil,
             ns.copy(units=Set(("service-c",Set()))) :: Nil,
             DeploymentTarget.Except(Nil),
             NotificationSubscriptions.empty
           )

  def serviceC2Manifest(ns: Manifest.Namespace) =
    Manifest(List(UnitDef("service-c", "",
                          Map("foo" -> FeatureVersion(1,10)),
                          Set.empty,
                          Alerting.empty,
                          Some(Ports(Port("default", 1, "http"), Nil)),
                          Some(Deployable("service-c", Version(6,2,1), Container(""))),
                          Set.empty[String]
                      )),
             Nil,
             Nil,
           ns.copy(units=Set(("service-c",Set()))) :: Nil,
             DeploymentTarget.Except(Nil),
             NotificationSubscriptions.empty
           )

  def ingest(m: Manifest, repo: ID): StoreOpF[Unit] =
    m.units.traverse_(u => StoreOp.addUnit(Versioned(u), repo))

  def ingestLb(m: Manifest, repo: ID): StoreOpF[Unit] =
    m.loadbalancers.traverse_(lb => StoreOp.insertLoadbalancerIfAbsent(Versioned(lb), repo).map(_ => ()))

  def ingestLb( ns: Manifest.Namespace, repo: ID): StoreOpF[Unit] = ingestLb(lbManifest(ns), repo)
  def ingestConductor( ns: Manifest.Namespace, repo: ID): StoreOpF[Unit] = ingest(conductorManifest(ns), repo)
  def ingestAB1( ns: Manifest.Namespace, repo: ID): StoreOpF[Unit] = ingest(abManifest1(ns), repo)
  def ingestAB2( ns: Manifest.Namespace, repo: ID): StoreOpF[Unit] = ingest(abManifest2(ns), repo)
  def ingestFoo1( ns: Manifest.Namespace, repo: ID): StoreOpF[Unit] = ingest(fooManifestOld(ns), repo)
  def ingestFoo2( ns: Manifest.Namespace, repo: ID): StoreOpF[Unit] = ingest(fooManifestNew(ns), repo)
  def ingestInventory1( ns: Manifest.Namespace, repo: ID): StoreOpF[Unit] = ingest(inventoryManifest1(ns), repo)
  def ingestInventory2( ns: Manifest.Namespace, repo: ID): StoreOpF[Unit] = ingest(inventoryManifest2(ns), repo)
  def ingestSearch( ns: Manifest.Namespace, repo: ID): StoreOpF[Unit] = ingest(searchManifest(ns), repo)
  def ingestDeprecated( ns: Manifest.Namespace, repo: ID): StoreOpF[Unit] = ingest(deprecated(ns), repo)
  def ingestJob300( ns: Manifest.Namespace, repo: ID): StoreOpF[Unit] = ingest(job300(ns), repo)
  def ingestJob310( ns: Manifest.Namespace, repo: ID): StoreOpF[Unit] = ingest(job310(ns), repo)
  def ingestJob311( ns: Manifest.Namespace, repo: ID): StoreOpF[Unit] = ingest(job311(ns), repo)
  def ingestJob410( ns: Manifest.Namespace, repo: ID): StoreOpF[Unit] = ingest(job410(ns), repo)
  def ingestCrawler( ns: Manifest.Namespace, repo: ID): StoreOpF[Unit] = ingest(crawler(ns), repo)
  def ingestServiceA( ns: Manifest.Namespace, repo: ID): StoreOpF[Unit] = ingest(serviceAManifest(ns), repo)
  def ingestServiceB( ns: Manifest.Namespace, repo: ID): StoreOpF[Unit] = ingest(serviceBManifest(ns), repo)
  def ingestServiceC( ns: Manifest.Namespace, repo: ID): StoreOpF[Unit] = ingest(serviceCManifest(ns), repo)
  def ingestServiceC2( ns: Manifest.Namespace, repo: ID): StoreOpF[Unit] = ingest(serviceC2Manifest(ns), repo)

  def ingestAll(ns: Manifest.Namespace, ns2: Manifest.Namespace, ns3: Manifest.Namespace) =
    ingestLb(ns,9999) *>
    ingestSearch(ns,9999) *>
    ingestInventory2(ns,9999) *>
    ingestInventory1(ns,9999) *>
    ingestFoo1(ns,9999) *>
    ingestFoo2(ns,9999) *>
    ingestAB2(ns,9999) *>
    ingestAB1(ns,9999) *>
    ingestConductor(ns,9999) *>
    ingestDeprecated(ns,9999) *>
    ingestJob300(ns,9999) *>
    ingestJob310(ns,9999) *>
    ingestJob311(ns,9999) *>
    ingestJob410(ns,9999) *>
    ingestCrawler(ns,9999) *>
    ingestServiceA(ns,9999) *>
    // ingest into devel/sandbox
    ingestServiceB(ns2,9999) *>
    ingestServiceC(ns2,9999) *>
    // ingest service-c into devel/sandbox/rodrigo
    ingestServiceC2(ns3,9999)


  def deploy(ns: Datacenter.Namespace, service: String,
    version: Version, hash: String, status: DeploymentStatus,
    policy: ExpirationPolicy): StoreOpF[ID] =
    for {
      u   <- StoreOp.getUnit(service, version)
      did <- StoreOp.createDeployment(u.get.id, hash, ns, "pulsar", "plan", policy.name)
      _   <- StoreOp.createDeploymentExpiration(did, Instant.now().plusSeconds(1.day.toSeconds))
      _   <- StoreOp.createDeploymentStatus(did, status, None)
    } yield did

  def deployLoadbalancer(ns: Datacenter.Namespace, name: String, v: Version, hash: String): StoreOpF[ID] = {
    for {
      lb <- StoreOp.getLoadbalancer(name, v.toMajorVersion).map(_.get)
      id <- StoreOp.insertLoadbalancerDeployment(lb.id, ns.id, hash, "dns")
    } yield id
  }

  def deployLb(ns: Datacenter.Namespace) = deployLoadbalancer(ns, "lb", Version(1,0,0), "hash")
  def deployConductor(ns: Datacenter.Namespace) = deploy(ns, "conductor", Version(1,1,1), "abcd",DeploymentStatus.Ready, RetainActive)
  def deployAB1(ns: Datacenter.Namespace) = deploy(ns, "ab", Version(2,2,2), "abcd",DeploymentStatus.Ready, RetainActive)
  def deployAB2(ns: Datacenter.Namespace) = deploy(ns, "ab", Version(2,2,1), "abcd",DeploymentStatus.Ready, RetainActive)
  def deployFoo1(ns: Datacenter.Namespace) = deploy(ns, "foo", Version(1,10,100), "aaaa",DeploymentStatus.Ready, RetainActive)
  def deployFoo2(ns: Datacenter.Namespace) = deploy(ns, "foo", Version(2,0,0), "bbbb",DeploymentStatus.Ready, RetainActive)
  def deployInventory1(ns: Datacenter.Namespace) = deploy(ns, "inventory", Version(1,2,2), "ffff",DeploymentStatus.Ready, RetainActive)
  def deployInventory2(ns: Datacenter.Namespace) = deploy(ns, "inventory", Version(1,2,3), "ffff",DeploymentStatus.Ready, RetainActive)
  def deploySearch1(ns: Datacenter.Namespace) = deploy(ns, "search", Version(2,2,2), "bbbb",DeploymentStatus.Ready, RetainActive)
  def deploySearch2(ns: Datacenter.Namespace) = { Thread.sleep(1); deploy(ns, "search", Version(2,2,2), "aaaa",DeploymentStatus.Ready, RetainActive) }
  def deployDeprecated(ns: Datacenter.Namespace) = deploy(ns, "search", Version(1,1,0),"foo",DeploymentStatus.Deprecated, RetainActive)
  def deployJob300(ns: Datacenter.Namespace) = deploy(ns, "job", Version(3,0,0),"zzzz4",DeploymentStatus.Ready, RetainLatest)
  def deployJob310(ns: Datacenter.Namespace) = deploy(ns, "job", Version(3,1,0),"zzzz",DeploymentStatus.Ready, RetainLatest)
  def deployJob311(ns: Datacenter.Namespace) = deploy(ns, "job", Version(3,1,1),"zzzz1",DeploymentStatus.Ready, RetainLatestTwoMajor)
  def deployJob410(ns: Datacenter.Namespace) = deploy(ns, "job", Version(4,1,0),"zzzz2",DeploymentStatus.Ready, RetainLatest)
  def deployCrawler(ns: Datacenter.Namespace) = deploy(ns, "crawler", Version(5,1,0),"zzzz3",DeploymentStatus.Ready, RetainLatest)
  def deployServiceA(ns: Datacenter.Namespace) = deploy(ns, "service-a", Version(6,0,0),"aaaa",DeploymentStatus.Ready, RetainLatest)
  def deployServiceB(ns: Datacenter.Namespace) = deploy(ns, "service-b", Version(6,1,0),"aaaa",DeploymentStatus.Ready, RetainLatest)
  def deployServiceC(ns: Datacenter.Namespace) = deploy(ns, "service-c", Version(6,2,0),"aaaa",DeploymentStatus.Ready, RetainLatest)
  def deployServiceC2(ns: Datacenter.Namespace) = deploy(ns, "service-c", Version(6,2,1),"bbbb",DeploymentStatus.Ready, RetainLatest)


  def deploydb(dc: Datacenter, ns: Datacenter.Namespace): StoreOpF[Unit] =
      StoreOp.createManualDeployment(
        dc,
        ns.name,
        "db",
        "1.2.3",
        "aaaa",
        "desc",
        1234,
        Instant.now.plusSeconds(1.day.toSeconds)
      ).map(_ => ())

  def deployAll(dc: Datacenter, ns: Datacenter.Namespace, ns2: Datacenter.Namespace, ns3: Datacenter.Namespace) =
    (
      deployLb(ns) *>
      deploySearch1(ns) *>
      deploySearch2(ns) *>
      deployInventory2(ns) *>
      deployInventory1(ns) *>
      deployFoo1(ns) *>
      deployFoo2(ns) *>
      deployAB2(ns) *>
      deployAB1(ns) *>
      deployConductor(ns) *>
      deploydb(dc, ns) *>
      deployDeprecated(ns) *>
      deployJob300(ns) *>
      deployJob310(ns) *>
      deployJob311(ns) *>
      deployJob410(ns) *>
      deployCrawler(ns) *>
      deployServiceA(ns) *>
      // deploy to dev/sandbox
      deployServiceB(ns2) *>
      deployServiceC(ns2) *>
      deployServiceC2(ns3)
    ).void

  def createManualInventoryShift(nsid: ID): StoreOpF[Unit] =
     for {
       ds <- StoreOp.listDeploymentsForUnitByStatus(nsid, "inventory", NonEmptyList.of(DeploymentStatus.Ready))
       i1 :: i2 :: Nil = ds.toList.sorted(Order[Deployment].toOrdering)
       // We need to create a shift that lasts longer than our test, so our test runs during the shift
       id <- StoreOp.createTrafficShift(nsid, i2, LinearShiftPolicy, 365.days)
       _  <- StoreOp.startTrafficShift(i1.id, i2.id, Instant.now.minusSeconds(30))
     } yield ()
}


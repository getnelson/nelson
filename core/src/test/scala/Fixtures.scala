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

import cats.implicits._

import java.net.URI
import java.time.{Instant,ZonedDateTime,ZoneId}
import scala.util.matching.Regex
import scala.concurrent.duration._

import org.scalacheck.{ Arbitrary, Cogen, Gen }

import nelson.Manifest.ResourceSpec

object Fixtures {
  import Arbitrary.arbitrary
  import Gen.{ alphaNumChar, listOf, listOfN, oneOf, choose }
  import docker.Docker

  implicit lazy val arbURI: Arbitrary[URI] = Arbitrary(genURI)
  implicit lazy val arbUser: Arbitrary[User] = Arbitrary(genUser)
  implicit lazy val arbInstant: Arbitrary[Instant] = Arbitrary(genInstant)
  implicit lazy val arbCogen: Cogen[Instant] = Cogen[String].contramap(_.toString)
  implicit lazy val arbAccessToken: Arbitrary[AccessToken] = Arbitrary(genAccessToken)
  implicit lazy val arbSession: Arbitrary[Session] = Arbitrary(genSession)
  implicit lazy val arbManifest: Arbitrary[Manifest] = Arbitrary(genManifest)
  implicit lazy val arbDockerImage: Arbitrary[Docker.Image] = Arbitrary(genDockerImage)
  implicit lazy val arbManifestDeployableContainer: Arbitrary[Manifest.Deployable.Container] = Arbitrary(genManifestDeployableContainer)
  implicit lazy val arbManifestDeployable: Arbitrary[Manifest.Deployable] = Arbitrary(genManifestDeployable)
  implicit lazy val arbServiceUnit: Arbitrary[Manifest.UnitDef] = Arbitrary(genManifestUnitDef)
  implicit lazy val arbGithubRelease: Arbitrary[Github.Release] = Arbitrary(genGithubRelease)
  implicit lazy val arbSlackSub: Arbitrary[notifications.SlackSubscription] = Arbitrary(genSlackSubscription)
  implicit lazy val arbEmailSub: Arbitrary[notifications.EmailSubscription] = Arbitrary(genEmailSubscription)
  implicit lazy val arbRegex: Arbitrary[Regex] = Arbitrary(genRegex)
  implicit lazy val arbDeployment: Arbitrary[Datacenter.Deployment] = Arbitrary(genDeployment)
  implicit lazy val arbTrafficShiftPolicy: Arbitrary[TrafficShiftPolicy] = Arbitrary(genTrafficShiftPolicy)
  implicit lazy val arbFiniteDuration: Arbitrary[FiniteDuration] = Arbitrary(genFiniteDuration)
  implicit lazy val arbTrafficShift: Arbitrary[Datacenter.TrafficShift] = Arbitrary(genTrafficShift)

  def alphaStr: Gen[String] =
    listOfN(10, alphaNumChar).map(_.mkString)
      .suchThat(_.forall(_.isLetter))

  def alphaNumStr: Gen[String] =
    listOfN(10, alphaNumChar).map(_.mkString)
      .suchThat(_.forall(c => c.isDigit || c.isLetter))

  def alphaNumHyphenStr: Gen[String] =
     listOfN(10, genAlphaNumHyphenChar).map(_.mkString)
       .suchThat(_.forall(c => c.isDigit || c.isLetter || c.equals('-')))

  def genAlphaNumHyphenChar: Gen[Char] =
    oneOf(alphaNumChar, oneOf('-', 'a'))

  def dnsLabel: Gen[String] =
    for {
      a <- alphaNumChar
      b <- alphaNumHyphenStr
      c <- alphaNumChar
    } yield s"$a$b$c"

  def genSchedule: Gen[Schedule] =
    for {
      a <- genInterval
    } yield Schedule(a)

  def genInterval: Gen[Schedule.Interval] =
    Gen.oneOf(Schedule.Once,Schedule.Monthly,Schedule.Hourly,Schedule.Daily,Schedule.QuarterHourly,Schedule.Cron("1 1 1 1 1 "))

  def genJavaZonedDateTime: Gen[ZonedDateTime] =
    for {
      a <- genInstant
    } yield ZonedDateTime.parse(a.toString)

  def genZoneId: Gen[ZoneId] = {
    import scala.collection.JavaConverters._
    for {
      a <- Gen.oneOf(ZoneId.SHORT_IDS.asScala.values.toList)
    } yield ZoneId.of(a)
  }

  def genURI: Gen[URI] = for {
    str <- alphaNumStr
  } yield new URI("http://localhost:8080/foo/" + str)

  def genOrg: Gen[Organization] = for {
    id      <- choose(1000,10000)
    name   <- alphaNumStr
    avatar <- arbitrary[URI]
  } yield Organization(id.toLong,Option(name), name, avatar)

  def genUser: Gen[User] = for {
    login  <- alphaNumStr
    avatar <- arbitrary[URI]
    name   <- alphaNumStr
    email  <- Gen.option(alphaNumStr)
    orgs   <- listOfN(10, genOrg)
  } yield User(login,avatar,name,email, orgs)

  def genInstant: Gen[Instant] =
    for(a <- choose(2000,100000) // make it choose between 2 second and 100 seconds
      ) yield Instant.now.plusMillis(a.toLong)


  def genAccessToken: Gen[AccessToken] =
    for(a <- alphaNumStr) yield AccessToken(a)

  def genSession: Gen[Session] =
    for {
      expiry <- arbitrary[Instant]
      github <- arbitrary[AccessToken]
      user   <- arbitrary[User]
    } yield Session(expiry,github,user)

  val genVersion: Gen[Version] = for {
    major <- Gen.choose(1,10000)
    minor <- Gen.choose(1,10000)
    patch <- Gen.choose(1,10000)
  } yield Version(major, minor, patch)

  val hexChar: Gen[Char] = Gen.oneOf('a','b','c','d','e','f','1','2','3','4','5','6','7','8','9','0')

  val genHash: Gen[String] =
    Gen.containerOfN[Array, Char](8,hexChar).map(new String(_))

  def stringOf(gen: Gen[Char]): Gen[String] = Gen.containerOf[Array,Char](gen).map(new String(_))

  val genStackName: Gen[Datacenter.StackName] =
    for {
      sn1 <- Gen.alphaNumChar
      sn <- Gen.resize(22, stringOf(Gen.alphaNumChar))
      version <- genVersion
      hash <- genHash
    } yield Datacenter.StackName(sn1 + sn, version, hash)

  val genManifestPort: Gen[Manifest.Port] =
    for {
      a <- alphaNumStr
      b <- choose(1000,65000)
      c <- Gen.oneOf(Seq("http", "https", "tcp"))
    } yield Manifest.Port(a,b,c)

  val genManifestDefaultPort: Gen[Manifest.Port] =
    genManifestPort.map(_.copy(ref = Manifest.Port.defaultRef))

  val genManifestPorts: Gen[Manifest.Ports] =
    for {
      default <- genManifestDefaultPort
      rest <- Gen.listOfN(1, genManifestPort)
    } yield Manifest.Ports(default, rest)

  val genManifestDeploymentTarget: Gen[Manifest.DeploymentTarget] =
    for {
      a <- Gen.listOfN(10, alphaNumStr)
      b <- Gen.oneOf(Seq(Manifest.DeploymentTarget.Only(a), Manifest.DeploymentTarget.Except(a.headOption.toList)))
    } yield b

  val genManifestUnitDef: Gen[Manifest.UnitDef] =
    for {
      a <- alphaNumStr
      c <- genManifestPorts
      d <- alphaNumStr
    } yield Manifest.UnitDef(
      name  = a,
      description = d,
      ports = Some(c),
      dependencies = Map.empty,
      resources = Set.empty,
      workflow = Magnetar,
      alerting = Manifest.Alerting.empty,
      deployable = None,
      meta = Set.empty[String]
    )

  val genBackendDestination: Gen[Manifest.BackendDestination] =
    for {
      a <- alphaNumStr
    } yield Manifest.BackendDestination(a,"default")

  val getRoute: Gen[Manifest.Route] =
    for {
      a <- genManifestDefaultPort
      b <- genBackendDestination
    } yield Manifest.Route(a,b)

  val genLoadbalancer: Gen[Manifest.Loadbalancer] =
    for {
      a <- alphaNumStr
      b <- Gen.listOfN(2, getRoute)
    } yield Manifest.Loadbalancer(a, b.toVector, None)

  val genConstraint: Gen[Manifest.Constraint] = {
    import Manifest.Constraint._

    for {
      field <- alphaNumStr
      op <- Gen.oneOf(
        Gen.const(Unique(field)),
        alphaNumStr.map(s => Cluster(field, s)),
        arbitrary[Option[Int]].map(n => GroupBy(field, n)),
        Gen.const(Like(field, ".*".r)),
        Gen.const(Unlike(field, ".*".r)))
    } yield op
  }

  val genResourceSpec: Gen[ResourceSpec] = {
    val limitOnly = Gen.posNum[Double].flatMap(d => ResourceSpec.limitOnly(d).fold(Gen.fail[ResourceSpec])(Gen.const))
    val bounded = for {
      upper <- Gen.posNum[Double]
      lower <- Gen.chooseNum[Double](0.0, upper)
      spec  <- ResourceSpec.bounded(lower, upper).fold(Gen.fail[ResourceSpec])(Gen.const)
    } yield spec

    Gen.oneOf(Gen.const(ResourceSpec.unspecified), limitOnly, bounded)
  }

  val genEnvironment: Gen[Manifest.Environment] = {
    for {
      cpu <- genResourceSpec
      mem <- genResourceSpec
      bindings <- Gen.listOf(genManifestEnvVar)
      constraints <- Gen.listOf(genConstraint)
    } yield Manifest.Environment(bindings = bindings, cpu = cpu, memory = mem, constraints = constraints)
  }

  val genManifestEnvVar: Gen[Manifest.EnvironmentVariable] =
    for {
      a <- alphaNumStr
      b <- alphaNumStr
    } yield Manifest.EnvironmentVariable(a, b)

  val genRootNamespaceNameStr: Gen[String] =
    alphaStr.map(_.toLowerCase)

  val genSubNamespaceNameStr: Gen[String] =
   for {
     a <- alphaStr.map(_.toLowerCase)
   } yield s"$a/$a/$a"

  val genNamespaceNameStr: Gen[String] =
    oneOf(genRootNamespaceNameStr, genSubNamespaceNameStr)

  val genNamespaceName: Gen[NamespaceName] =
    genNamespaceNameStr.map(x => NamespaceName.fromString(x).toOption.get)

  val genManifestNamespace: Gen[Manifest.Namespace] =
    for {
      a <- alphaNumStr
      b <- arbitrary[Set[String]]
      c <- genEnvironment
      e <- Gen.listOfN(2,genPlan)
      f <- arbitrary[Set[String]]
      g <- genNamespaceName
    } yield Manifest.Namespace(
      name = g,
      units = b.map((_, e.map(_.name).toSet)),
      loadbalancers = f.map((_, e.headOption.map(_.name)))
    )

  val genSlackSubscription: Gen[notifications.SlackSubscription] =
    for {
      a <- arbitrary[String]
    } yield notifications.SlackSubscription(a)

  val genEmailSubscription: Gen[notifications.EmailSubscription] =
    for {
      a <- arbitrary[String]
    } yield notifications.EmailSubscription(a)

  val getNotifications: Gen[notifications.NotificationSubscriptions] =
    for {
      a <- arbitrary[List[notifications.SlackSubscription]]
      b <- arbitrary[List[notifications.EmailSubscription]]
    } yield notifications.NotificationSubscriptions(a,b)

  val genPlan: Gen[Manifest.Plan] =
    for {
      a <- alphaNumStr
      b <- genEnvironment
    } yield {
      Manifest.Plan(a, b)
    }

  // TIM: doing this trick with the namespaces, as we're assuming
  // that only defined units are referenced in the namespaces; it
  // is not *entirely* arbitrary.
  val genManifest: Gen[Manifest] =
    for {
      a <- genManifestDeploymentTarget
      b <- Gen.listOfN(2, genManifestUnitDef)
      c <- Gen.listOfN(2, genManifestUnitDef)
      d <- Gen.listOfN(4, alphaNumStr)
      e <- getNotifications
      f <- genManifestDeployable
      h <- genPlan
      i <- Gen.listOfN(2, genLoadbalancer)
      j <- genNamespaceName
    } yield Manifest(
      targets = a,
      units = (b ++ c),
      plans = List(h),
      loadbalancers = i,
      namespaces =
        d.map(x => Manifest.Namespace(
          name = j,
          units = (b ++ c).map(y => (y.name, Set(h.name))).toSet,
          loadbalancers = i.map(y => (y.name, Some(h.name))).toSet
        )),
      notifications = e
    )

  val genDockerImage: Gen[Docker.Image] =
    for {
      a <- choose(0,3)
      b <- listOfN(a, alphaNumStr)
      c <- choose(0,2)
      d <- listOfN(c, alphaNumStr)
      e <- Gen.option(alphaNumStr)
    } yield Docker.Image(
      name = (b.mkString(".") ++ d).mkString("/"),
      tag = e,
      digest = None
    )

  val genManifestDeployableContainer: Gen[Manifest.Deployable.Container] =
    for {
      a <- genDockerImage
    } yield Manifest.Deployable.Container(a.toString)

  val genManifestDeployable: Gen[Manifest.Deployable] =
    for {
      a <- alphaNumStr
      b <- genManifestDeployableContainer
      c <- genVersion
    } yield Manifest.Deployable(a,c,b)

  val genSlug: Gen[Slug] =
    for {
      a <- alphaNumStr
      b <- alphaNumStr
    } yield Slug(a,b)

  val genRepoAccess: Gen[RepoAccess] =
    for {
      a <- oneOf(RepoAccess.all.toSeq)
    } yield a

  val genRepo: Gen[Repo] =
    for {
      a <- choose(1,10000)
      b <- genSlug
      c <- genRepoAccess
    } yield Repo(a.toLong, b, c, None)

  val genGithubRelease: Gen[Github.Release] =
    for {
      a <- choose(1,10000)
      b <- genVersion
    } yield Github.Release(
      id = a.toLong,
      url = "",
      htmlUrl = "",
      assets = Nil,
      tagName = b.toString
    )

  val genRegex: Gen[Regex] = {
    (for {
      s <- listOf(choose(' ', '~')).map(_.mkString)
    } yield Either.catchNonFatal(s.r).toOption)
      .retryUntil(_.isDefined)
      .map(_.yolo("bug"))
  }

  val genDatacenterPort: Gen[Datacenter.Port] =
    for {
      a <- choose(1000,65000)
      b <- alphaNumStr
      c <- Gen.oneOf(Seq("http", "https", "tcp"))
    } yield Datacenter.Port(a,b,c)

  val genServiceName: Gen[Datacenter.ServiceName] =
    for {
      name <- alphaNumStr
      version <- genVersion
    } yield Datacenter.ServiceName(name, version.toFeatureVersion)

  val genDCUnit: Gen[Datacenter.DCUnit] =
    for {
      a <- choose(1,10000)
      b <- alphaNumStr
      c <- genVersion
      d <- alphaNumStr
      e <- Gen.listOfN(0, genServiceName)
      f <- Gen.listOfN(0, alphaNumStr)
      g <- Gen.listOfN(0, genDatacenterPort)
    } yield Datacenter.DCUnit(a.toLong,b,c,d,e.toSet,f.toSet,g.toSet)

  val genDeployment: Gen[Datacenter.Deployment] =
    for {
      a <- choose(1,10000)
      b <- genDCUnit
      c <- genHash
      d <- choose(1,10000)
      e <- genInstant
      f <- alphaNumStr
    } yield Datacenter.Deployment(a.toLong,b,c,Datacenter.Namespace(1, NamespaceName("dev"), "dc"),e,"manual","default",f,"retain-always")

  val genTrafficShiftPolicy: Gen[TrafficShiftPolicy] =
    Gen.oneOf(TrafficShiftPolicy.policies.toSeq)

  def genPastInstant(lower: Int, upper: Int): Gen[Instant] =
    for(a <- choose(lower,upper)) yield Instant.now.minusSeconds(a.toLong)

  def genFutureInstant(lower: Int, upper: Int): Gen[Instant] =
    for(a <- choose(lower,upper)) yield Instant.now.plusSeconds(a.toLong)

  def genFiniteDuration: Gen[FiniteDuration] =
    for {
      a <- choose(60, 86400) // between 1 minute and 1 day
    } yield FiniteDuration(a.toLong, SECONDS)

  val genTrafficShift: Gen[Datacenter.TrafficShift] =
    for {
      a <- genDeployment
      b <- genDeployment
      c <- genTrafficShiftPolicy
      d <- genPastInstant(60, 3600)
      e <- genFiniteDuration
      f <- genFutureInstant(1, 3600)
    } yield {
      Datacenter.TrafficShift(a,b,c,d,e,Some(f))
    }
}

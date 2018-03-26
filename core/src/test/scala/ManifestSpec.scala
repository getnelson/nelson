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

import org.scalacheck._, Prop._
import scalaz.\/

object ManifestSpec extends Properties("manifest") with RoutingFixtures {
  import Fixtures._
  import Manifest._


  property("toAction") = forAll { (m: Manifest) =>
    val dcs = m.targets.values.map(datacenter(_))
    val units = Manifest.units(m, dcs)
    val lbs = Manifest.loadbalancers(m, dcs)
    val uas = units.map { case (dc,ns,pl,u) => Manifest.toAction(Versioned(u), dc, ns, pl, m.notifications) }
    val las = lbs.map { case (dc,ns,pl,lb) => Manifest.toAction(Versioned(lb), dc, ns, pl, m.notifications) }
    val as = uas ::: las
    val names = as.map(_.config.datacenter.name)

    ("verify blacklist vs whitelist functionaltiy" |: {
      m.targets match {
        case DeploymentTarget.Only(s) if s.nonEmpty =>
          s.toSet == names.toSet

        case DeploymentTarget.Except(s) if s.nonEmpty =>
          s.map(!names.contains(_)).reduceLeft(_ && _)

        case _ => false
      }
    })

    "verify each action gets a unique hash" |: {
      val hs = as.map(_.config.hash)
      hs == hs.distinct
    }
  }

  property("unitActions") = forAll { (m: Manifest) =>
    val dcs = m.targets.values.map(datacenter(_))

    ("verify units are empty") |: {
      val empty = Manifest.unitActions(Versioned(m), dcs, (dc,ns,p,u) => false)
      empty.isEmpty
    }

    ("verify units are full") |: {
      val units = Manifest.units(m, dcs)
      val actions = Manifest.unitActions(Versioned(m), dcs, (dc,ns,p,u) => true)
      units.length == actions.length
    }
  }

  property("lbActions") = forAll { (m: Manifest) =>
    val dcs = m.targets.values.map(datacenter(_))

    ("verify loadbalancers are empty") |: {
      val empty = Manifest.loadbalancerActions(Versioned(m), dcs, (dc,ns,pl,lb) => false)
      empty.isEmpty
    }

    ("verify loadbalancers are full") |: {
      val lbs = Manifest.loadbalancers(m, dcs)
      val actions = Manifest.loadbalancerActions(Versioned(m), dcs, (dc,ns,pl,lb) => true)
      lbs.length == actions.length
    }
  }

  property("units") = forAll { (m: Manifest) =>
    val dcs = m.targets.values.map(datacenter(_))

    // remove plans from manifest, and plan reference from namespaces
    val m2: Manifest = m.copy(
      plans = List(),
      namespaces = m.namespaces.map(ns => ns.copy(units = ns.units.map(a => (a._1, Set.empty[PlanRef])))))

    val units = Manifest.units(m2, dcs)

    ("verify units without plan get default") |: {
      units.foldLeft(true)((a,b) => b._3 == Plan.default && a)
    }
  }
}

class ManifestManualSpec extends NelsonSuite {
  import Manifest._
  import Util._
  import DeploymentTarget._

  def load(what: String) = {
    for {
      a <- loadResourceAsString("/nelson/manifest.howdy-manifest.yml").attemptRun
      b <- yaml.ManifestParser.parse(a)
      c <- loadResourceAsString(what).attemptRun
      d  = Github.Asset(
        id = 45,
        name = "example-howdy.deployable.yml",
        url = "",
        state = "",
        content = Some(c)
      )
      e  = Github.Release(
        id = "123",
        url = "",
        htmlUrl = "",
        assets = List(d),
        tagName = "master"
      )
    } yield Manifest.versionedUnits((Manifest.saturateManifest(b)(e)).run).map(_.version)
  }

  // TIM: so evil!
  implicit class DcList(in: List[String]){
    def asDatacenters: List[Datacenter] =
      in.map(datacenter(_))
  }

  behavior of "loading from YAML"

  it should "be OK with *.yaml" in {
    val manifest = for {
      resource <- loadResourceAsString("/nelson/manifest.howdy-manifest.yml").attemptRun
      manifest <- yaml.ManifestParser.parse(resource)
      contents <- loadResourceAsString("/nelson/manifest.deployable.v1.c.yml").attemptRun
      asset = Github.Asset(
        id = 45,
        name = "example-howdy.deployable.yaml", // this is what is being tested - note .yaml instead of .yml
        url = "",
        state = "",
        content = Some(contents)
      )
      release = Github.Release(
        id = "123",
        url = "",
        htmlUrl = "",
        assets = List(asset),
        tagName = "master"
      )
    } yield Manifest.versionedUnits((Manifest.saturateManifest(manifest)(release)).run).map(_.version)

    manifest should equal (\/.right(List(Version(0, 6, 10))))
  }

  it should "augment the manifest with a release in the happy case" in {
    load("/nelson/manifest.deployable.v1.c.yml") should equal (\/.right(List(Version(0,6,10))))
  }

  it should "fail when the deployable has a different name" in {
    load("/nelson/manifest.deployable.v1.d.yml") should equal (\/.right(List(Version(0,6,10))))
  }

  behavior of "filterDatacenters"

  it should "make an exclusive subset when using whitelist" in {
    filterDatacenters(List("foo","bar").asDatacenters)(Only("foo" :: Nil)
      ) should equal (List("foo").asDatacenters)
  }

  it should "make an inclusive subset when using blacklist" in {
    filterDatacenters(List("foo","bar","baz").asDatacenters)(Except("foo" :: Nil)
      ) should equal (List("bar","baz").asDatacenters)
  }

  // this is essentially what happens when users do not specify a datacenters
  // section in their manifest yaml.
  it should "make use of all known dcs when blacklisting nothing" in {
    filterDatacenters(List("foo","bar","baz").asDatacenters)(Except(Nil)
      ) should equal (List("foo","bar","baz").asDatacenters)
  }
}

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

import nelson.plans.Datacenters
import Datacenter._
import argonaut._
import Argonaut._
import cats.effect.IO
import org.http4s._
import org.http4s.argonaut._
import org.http4s.dsl.io._
import org.http4s.Uri.uri
import Json._

class DatacentersSpec extends ServiceSpec {

  override def beforeAll(): Unit = {
    super.beforeAll()
    insertFixtures(testName).foldMap(config.storage).unsafeRunSync()
    ()
  }

  val manual = ManualDeployment(config.datacenters.head.name,"dev","foo","1.1.1","hash","description",80)

  "Datacenters Object" should "allow us to query for all data centers" in {
    val service = Server.json500(Datacenters(config).service)

    val req = Request[IO](GET, uri("/v1/datacenters")).authed
    val resp = service.orNotFound(req).unsafeRunSync()
    resp.status should equal (Ok)
  }

  it should "allow users of admin github orgs to create manual deployments" in {
    val service = Datacenters(config).service
    val req = Request[IO](POST, uri("/v1/deployments")).authed
      .withBody(manual.asJson)
    val resp = req.flatMap(service.orNotFound.run).unsafeRunSync
    resp.status should equal (Found)
  }

  it should "not allow any user to create manual deployments" in {
    val config0 = config.copy(git = config.git.copy(organizationAdminList = Nil))
    val service = Datacenters(config0).service
    val req = Request[IO](POST, uri("/v1/deployments")).authed
      .withBody(manual.asJson)
    val resp = req.flatMap(service.orNotFound.run).unsafeRunSync()
    resp.status should equal (NotFound)
  }

}

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

import nelson.plans.Domains
import Domain._
import argonaut._
import Argonaut._
import org.http4s._
import org.http4s.argonaut._
import org.http4s.dsl._
import org.http4s.Uri.uri
import Json._

class DomainsSpec extends ServiceSpec {

  override def beforeAll(): Unit = {
    super.beforeAll()
    nelson.storage.run(config.storage, insertFixtures(testName)).run
    ()
  }

  val manual = ManualDeployment(config.domains.head.name,"dev","foo","1.1.1","hash","description",80)

  "Domains Object" should "allow us to query for all data centers" in {
    val service = Server.json500(Domains(config).service)

    val req = Request(GET, uri("/v1/domains")).authed
    val resp = service.orNotFound(req).run
    resp.status should equal (Ok)
  }

  it should "allow users of admin github orgs to create manual deployments" in {
    val service = Domains(config).service
    val req = Request(POST, uri("/v1/deployments")).authed
      .withBody(manual.asJson)
    val resp = req.flatMap(service.orNotFound).run
    resp.status should equal (Found)
  }

  it should "not allow any user to create manual deployments" in {
    val config0 = config.copy(git = config.git.copy(organizationAdminList = Nil))
    val service = Domains(config0).service
    val req = Request(POST, uri("/v1/deployments")).authed
      .withBody(manual.asJson)
    val resp = req.flatMap(service.orNotFound).run
    resp.status should equal (NotFound)
  }

}

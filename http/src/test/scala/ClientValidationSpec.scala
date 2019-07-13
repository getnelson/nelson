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

import nelson.BannedClientsConfig.HttpUserAgent
import nelson.plans.{Auth, ClientValidation}
import cats.effect.IO
import org.http4s.Uri.uri
import org.http4s._
import org.http4s.dsl.io._
import org.http4s.headers._

class ClientValidationSpec extends NelsonSuite {
  val versionStringDoesntMatter: String = "0.42.3"
  val nameStringDoesntMatter: String = "anyName"
  val emptyUserAgent = None
  val emptyConfig = None
  val configThatDoesntMatter = Some(BannedClientsConfig(List()))
  val agentDoesntMatter = agentWithVersion(nameStringDoesntMatter, versionStringDoesntMatter)
  def clientWithVersion(name: String, clientVersion: Version) = HttpUserAgent(name, Some(clientVersion))
  def clientSansVersion(name: String) = HttpUserAgent(name, None)
  def configThatContains(uas: HttpUserAgent*): Option[BannedClientsConfig] =
    Option(BannedClientsConfig(uas.toList))

  def agentWithVersion(name: String, versionString: String) = Option {
    `User-Agent`(
      AgentProduct(
        name = name,
        version = Option(versionString)))
  }

  def agentSansVersion(name: String) = Option {
    `User-Agent`(
      AgentProduct(
        name = name,
        version = None))
  }


  behavior of "auth service"

  it should "return a bad request response when user agent name is not allowed" in {
    val name = "user_agent_name"
    val nelsonConfig = config.copy(bannedClients = configThatContains(clientSansVersion(name)))

    val userAgent = `User-Agent`(AgentProduct(name = name, version = Option(versionStringDoesntMatter)))
    val req = Request[IO](GET, uri("/auth/login")).putHeaders(userAgent)

    val filteredService =
      ClientValidation.filterUserAgent(Auth(nelsonConfig).service)(nelsonConfig)

    val resp = filteredService.orNotFound(req).unsafeRunSync()
    resp.status shouldBe BadRequest
  }

  it should "login successfully when user agent is allowed" in {
    val allowedClient = HttpUserAgent("client", None)
    val clientsConfig = BannedClientsConfig(List(allowedClient))

    val sameName: String = allowedClient.name
    val userAgent = `User-Agent`(AgentProduct(name = sameName, version = Option(versionStringDoesntMatter)))
    val req = Request[IO](GET, uri("/auth/login")).putHeaders(userAgent)

    val config0 = config.copy(bannedClients = Option(clientsConfig))

    val resp = Auth(config0).service.orNotFound(req).unsafeRunSync()
    resp.status shouldBe Found
  }

  behavior of "isAllowed"

  it should "be true when there is no config" in {
    ClientValidation.isAllowedUserAgent(agentDoesntMatter)(emptyConfig) shouldBe true
  }

  it should "be false when there is a config but no user agent is specified" in {
    ClientValidation.isAllowedUserAgent(emptyUserAgent)(configThatDoesntMatter) shouldBe false
  }


  it should "be banned when the config contains the given agent's name with no version" in {
    val name = "agent_name"
    val config = configThatContains(clientSansVersion(name))

    ClientValidation.isAllowedUserAgent(agentWithVersion(name, versionStringDoesntMatter))(config) shouldBe false
    ClientValidation.isAllowedUserAgent(agentSansVersion(name))(config) shouldBe false
  }

  it should "be banned when the config contains the given agent's name with a version that is equal to the given agent's" in {
    val name = "agent_name"
    val versionString = "10.2.3"
    val configVersion = Version(10,2,3)
    val config = configThatContains(clientWithVersion(name, configVersion))

    ClientValidation.isAllowedUserAgent(agentWithVersion(name, versionString))(config) shouldBe false
  }

  it should "be banned when the config contains the given agent's name with a version that is greater than the given agent's" in {
    val name = "agent_name"
    val versionString = "10.2.1"
    val clientVersion = Version(10,2,3)
    val config = configThatContains(clientWithVersion(name, clientVersion))

    ClientValidation.isAllowedUserAgent(agentWithVersion(name, versionString))(config) shouldBe false
  }

  it should "be allowed when the config contains the given agent's name and the given agent's version is greater" in {
    val name = "agent_name"
    val versionString = "10.3.4"
    val clientVersion = Version(10,3,3)
    val config = configThatContains(clientWithVersion(name, clientVersion))

    ClientValidation.isAllowedUserAgent(agentWithVersion(name, versionString))(config) shouldBe true
  }

  it should "be allowed when the config contains the given agent's name and the agent version cannot be parsed" in {
    val name = "agent_name"
    val versionString = "dev"
    val clientVersion = Version(10,3,3)
    val config = configThatContains(clientWithVersion(name, clientVersion))

    ClientValidation.isAllowedUserAgent(agentWithVersion(name, versionString))(config) shouldBe true
  }

  it should "be allowed when the config contains the given agent's name and the agent version does not exist" in {
    val name = "agent_name"
    val clientVersion = Version(10,3,3)
    val config = configThatContains(clientWithVersion(name, clientVersion))

    ClientValidation.isAllowedUserAgent(agentSansVersion(name))(config) shouldBe true
  }
}

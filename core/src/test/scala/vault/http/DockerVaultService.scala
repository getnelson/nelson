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
package vault
package http4s

import com.spotify.docker.client.{DefaultDockerClient, DockerClient}
import com.whisk.docker.impl.spotify.SpotifyDockerFactory
import com.whisk.docker._
import journal.Logger
import scala.concurrent.duration._
import scala.io.Source

trait DockerVaultService extends DockerKit {
  private[this] val logger = Logger[DockerVaultService]

  private val client: DockerClient = DefaultDockerClient.fromEnv().build()
  override implicit val dockerFactory: DockerFactory = new SpotifyDockerFactory(client)

  println(s"==> DOCKER_HOST in kit ${client.getHost}")

  val consulContainer =
    DockerContainer("consul:1.2.2", name = Some("consul"))
      .withPorts(8500 -> Some(18500))
      .withLogLineReceiver(LogLineReceiver(true, s => logger.debug(s"consul: $s")))
      .withReadyChecker(DockerReadyChecker
        .HttpResponseCode(18500, "/v1/status/leader", host = Some("127.0.0.1"))
        .looped(5, 10.seconds))

  private val vaultLocalConfig =
    Source.fromInputStream(getClass.getResourceAsStream("/vault.hcl")).mkString

  val vaultContainer =
    DockerContainer("vault:0.10.4", name = Some("vault"))
      .withPorts(8200 -> Some(18200))
      .withEnv(s"VAULT_LOCAL_CONFIG=$vaultLocalConfig")
      .withLinks(ContainerLink(consulContainer, "consul"))
      .withCommand("server")
      .withLogLineReceiver(LogLineReceiver(true, s => logger.debug(s"vault: $s")))
      .withReadyChecker(DockerReadyChecker
        .HttpResponseCode(18200, "/v1/sys/seal-status", host = Some("127.0.0.1"), code = 400)
        .looped(5, 10.seconds))

  abstract override def dockerContainers: List[DockerContainer] =
    consulContainer :: vaultContainer :: super.dockerContainers
}

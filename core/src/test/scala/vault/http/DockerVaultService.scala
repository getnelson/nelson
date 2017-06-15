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

import com.github.dockerjava.core.DefaultDockerClientConfig
import com.github.dockerjava.netty.NettyDockerCmdExecFactory
import com.whisk.docker.impl.dockerjava.{Docker, DockerJavaExecutorFactory}
import com.whisk.docker._
import journal.Logger
import scala.io.Source

trait DockerVaultService extends DockerKit {
  private[this] val logger = Logger[DockerVaultService]

  override implicit val dockerFactory: DockerFactory = new DockerJavaExecutorFactory(
    new Docker(DefaultDockerClientConfig.createDefaultConfigBuilder().build(),
      factory = new NettyDockerCmdExecFactory()))

  val consulContainer =
    DockerContainer("consul:0.7.0", name = Some("consul"))
      .withPorts(8500 -> Some(8500))
      .withLogLineReceiver(LogLineReceiver(true, s => logger.debug(s"consul: $s")))
      .withReadyChecker(DockerReadyChecker.LogLineContains("agent: Synced"))

  private val vaultLocalConfig =
    Source.fromInputStream(getClass.getResourceAsStream("/vault.hcl")).mkString

  val vaultContainer =
    DockerContainer("vault:0.6.2", name = Some("vault"))
      .withPorts(8200 -> Some(8200))
      .withEnv(s"VAULT_LOCAL_CONFIG=$vaultLocalConfig")
      .withLinks(ContainerLink(consulContainer, "consul"))
      .withCommand("server")
      .withLogLineReceiver(LogLineReceiver(true, s => logger.debug(s"vault: $s")))
      .withReadyChecker(DockerReadyChecker.LogLineContains("Vault server started!"))

  abstract override def dockerContainers: List[DockerContainer] =
    consulContainer :: vaultContainer :: super.dockerContainers
}

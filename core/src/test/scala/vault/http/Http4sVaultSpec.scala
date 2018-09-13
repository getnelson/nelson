// //: ----------------------------------------------------------------------------
// //: Copyright (C) 2017 Verizon.  All Rights Reserved.
// //:
// //:   Licensed under the Apache License, Version 2.0 (the "License");
// //:   you may not use this file except in compliance with the License.
// //:   You may obtain a copy of the License at
// //:
// //:       http://www.apache.org/licenses/LICENSE-2.0
// //:
// //:   Unless required by applicable law or agreed to in writing, software
// //:   distributed under the License is distributed on an "AS IS" BASIS,
// //:   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// //:   See the License for the specific language governing permissions and
// //:   limitations under the License.
// //:
// //: ----------------------------------------------------------------------------
// package nelson
// package vault
// package http4s

// import argonaut._, Argonaut._
// import com.whisk.docker.scalatest._
// import org.http4s.Uri
// import org.http4s.client.blaze._
// import org.scalatest.{FlatSpec, Matchers}
// import scala.concurrent.duration._
// import cats.effect.IO
// import cats.syntax.either._

// class Http4sVaultSpec extends FlatSpec
//     with DockerTestKit
//     with Matchers
//     with DockerVaultService
//     with vault.http4s.Json
// {
//   override val StopContainersTimeout = 15.seconds

//   override def dockerContainers =
//     if (sys.env.get("BUILDKITE").isEmpty) consulContainer :: vaultContainer :: super.dockerContainers
//     else super.dockerContainers

//   // i'm expecting protocol://ip:port
//   def parseDockerHost(url: String): Option[String] = {
//     val parts = url.split(":")
//     if(parts.length == 3)
//       Some(parts(1).substring(2))
//     else None
//   }

//   def vaultHost: Option[Uri] =
//     for {
//       url <- sys.env.get("DOCKER_HOST")
//       host <- parseDockerHost(url)
//       yolo <- Uri.fromString(s"http://$host:8200").toOption
//     } yield yolo

//   var masterKey: MasterKey = _
//   var rootToken: RootToken = _
//   var interp: Http4sVaultClient = _

//   val baseUrl: Uri = vaultHost getOrElse Uri.uri("http://0.0.0.0:8200")

//   val client = Http1Client[IO]().unsafeRunSync()
//   val token = Token("asdf")
//   interp = new Http4sVaultClient(token, baseUrl, client)

//   if (sys.env.get("BUILDKITE").isEmpty) {
//     behavior of "vault"

//     it should "not be initialized" in {
//       Vault.isInitialized.foldMap(interp).unsafeRunSync() should be (false)
//     }

//     it should "initialize" in {
//       val result = Vault.initialize(1,1).foldMap(interp).unsafeRunSync()
//       result.keys.size should be (1)
//       this.masterKey = result.keys(0)
//       this.rootToken = result.rootToken
//       this.interp = new Http4sVaultClient(Token(rootToken.value), baseUrl, client)
//     }

//     it should "be initialized now" in {
//       Vault.isInitialized.foldMap(interp).unsafeRunSync() should be (true)
//     }

//     it should "be sealed at startup" in {
//       val sealStatus = Vault.sealStatus.foldMap(interp).unsafeRunSync()
//       sealStatus.`sealed` should be (true)
//       sealStatus.total should be (1)
//       sealStatus.progress should be (0)
//       sealStatus.quorum should be (1)
//     }

//     it should "be unsealable" in {
//       val sealStatus = Vault.unseal(this.masterKey).foldMap(interp).unsafeRunSync()
//       sealStatus.`sealed` should be (false)
//       sealStatus.total should be (1)
//       sealStatus.progress should be (0)
//       sealStatus.quorum should be (1)
//     }

//     it should "be unsealed after unseal" in {
//       val sealStatus = Vault.sealStatus.foldMap(interp).unsafeRunSync()
//       sealStatus.`sealed` should be (false)
//       sealStatus.total should be (1)
//       sealStatus.progress should be (0)
//       sealStatus.quorum should be (1)
//     }

//     it should "be awesome" in {
//       Vault.get("key").foldMap(interp).attempt.unsafeRunSync().fold(_ => true, _ => false) should be (true)
//     }

//     it should "have cubbyhole, secret, sys mounted" in {
//       val mounts = Vault.getMounts.foldMap(interp).attempt.unsafeRunSync()
//       mounts.toOption.get.size should be (4)
//       mounts.toOption.get.contains("cubbyhole/") should be (true)
//       mounts.toOption.get.contains("secret/") should be (true)
//       mounts.toOption.get.contains("identity/") should be (true)
//       mounts.toOption.get.contains("sys/") should be (true)
//     }

//     // This is how nelson writes policies.  It provides a good test case for us.
//     val StaticRules = List(
//       Rule("sys/*", policy = Some("deny"), capabilities = Nil),
//       Rule("auth/token/revoke-self", policy = Some("write"), capabilities = Nil)
//     )
//     val cp: Vault.CreatePolicy =
//       Vault.CreatePolicy(
//         name = s"qa__howdy",
//         rules = StaticRules :::
//           List("example/qa/mysql", "example/qa/cassandra").map { resource =>
//             Rule(
//               path = s"${resource}/creds/howdy",
//               capabilities = List("read"),
//               policy = None
//             )
//           }
//       )

//     it should "write policies" in {
//       Vault.createPolicy(cp.name, cp.rules).foldMap(interp).unsafeRunSync() should be (())
//     }

//     it should "delete policies" in {
//       Vault.deletePolicy(cp.name).foldMap(interp).unsafeRunSync() should be (())
//     }

//     it should "encode policies correctly" in {
//       cp.asJson.field("policy") should be (Some(jString("""{"path":{"sys/*":{"policy":"deny"},"auth/token/revoke-self":{"policy":"write"},"example/qa/mysql/creds/howdy":{"capabilities":["read"]},"example/qa/cassandra/creds/howdy":{"capabilities":["read"]}}}""")))
//     }

//     it should "create tokens" in {
//       val token2 = Vault.createToken(
//         policies = Some(List("default")),
//         ttl = Some(1.minute)
//       ).foldMap(interp).unsafeRunSync()
//       val interp2 = new Http4sVaultClient(token2, baseUrl, client)
//       Vault.isInitialized.foldMap(interp2).unsafeRunSync() should be (true)
//     }
//   }
// }

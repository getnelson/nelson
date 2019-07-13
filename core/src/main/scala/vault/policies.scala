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

import nelson.Datacenter.StackName

import cats.~>
import cats.effect.IO

import fs2.Stream

object policies {

  val DenySysRule =
    Rule("sys/*", policy = Some("deny"), capabilities = Nil)

  val RevokeSelfRule =
    Rule("auth/token/revoke-self", policy = Some("write"), capabilities = Nil)

  def pkiRule(pkiPath: String, ns: NamespaceName) = {
    val path = pkiPath.replaceAllLiterally("%env%", ns.root.asString)+"/issue/*"
    Rule(path, policy = None, capabilities = List("create", "update"))
  }

  /**
   * Creates a rule to read from a resource based on `resourceCredsPath`.  Variables
   * `%env%`, `%unit%`, and `%resource%` are interpolated.
   */
  def resourceRule(resourceCredsPath: String, sn: StackName, ns: NamespaceName, resource: String): Rule = {
    val path = resourceCredsPath
      .replaceAllLiterally("%env%", ns.root.asString)
      .replaceAllLiterally("%unit%", sn.serviceType)
      .replaceAllLiterally("%resource%", resource)
    Rule(path = path , capabilities = List("read"), policy = None)
  }

  def policyName(sn: StackName, ns: NamespaceName): String =
    s"nelson__${ns.root.asString}__${sn}"

  /**
   * For every resource in the specified namespace and unit, we create
   * grant read access to a resource creds path from `cfg` for each
   * resource, which may be internal or external.
   */
  def createPolicy(cfg: PolicyConfig, sn: StackName, ns: NamespaceName, resources: Set[String]): VaultF[Unit] = {
    val resourceRules = resources.map(r => resourceRule(cfg.resourceCredsPath, sn, ns, r)).toList
    val rules = List(
      List(DenySysRule, RevokeSelfRule),
      cfg.pkiPath.fold(List.empty[Rule])(pkiPath => List(pkiRule(pkiPath, ns))),
      resourceRules
    ).flatten
    Vault.createPolicy(
      name = policyName(sn, ns),
      rules = rules
    ).map(_ => ())
  }

  def deletePolicy(sn: StackName, ns: NamespaceName): VaultF[Unit] = {
    val name = policyName(sn, ns)
    Vault.deletePolicy(name)
  }

  def withPolicy[A](cfg: PolicyConfig, sn: StackName, ns: NamespaceName, resources: Set[String], interp: Vault ~> IO)(f: Token => Stream[IO, A]): Stream[IO, A] = {
    val acquire = (for {
      _ <- createPolicy(cfg, sn, ns, resources)
      token <- Vault.createToken(policies = Some(List(policyName(sn, ns))))
    } yield (token)).foldMap(interp)

    def release = deletePolicy(sn, ns).foldMap(interp)

    Stream.bracket(acquire)(f, (_: Token) => release)
  }
}

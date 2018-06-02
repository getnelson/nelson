//: ----------------------------------------------------------------------------
//: Copyright (C) 2014 Verizon.  All Rights Reserved.
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

import cats.free.Free
import scala.concurrent.duration.FiniteDuration
import scala.collection.immutable.SortedMap

/**
 * An algebra which represents an operation interacting with a
 * [HashiCorp Vault](https://www.vaultproject.io/docs/) server
 */
sealed abstract class Vault[A] extends Product with Serializable

/**
 * This contains the result of the `initialize` call, it *must* have
 * the number of MasterKeys requested. They should distributed to N
 * trusted individuals, a quorum of which will have to present keys to
 * unseal a vault.
 *
 * the rootToken is a omnipotent bearer token for this vault, so treat
 * it with the utmost of care. It will be required to create other
 * less privileged tokens.
 */
final case class InitialCreds(keys: List[MasterKey],
                              rootToken: RootToken)

/**
 * Parameters needed to initialize a new vault
 * @param secretShares     the numbers of unseal keys
 * @param secretThreshold  the quorum of unseal keys needed to unseal
 */
final case class Initialization(secretShares: Int,
                                secretThreshold: Int)


/**
 * @param sealed    Whether or not the vault is sealed.
 * @param total     The number of existing MasterKeys
 * @param qorum     The number of keys needed to unseal the vault
 * @param progress  The number of keys out of total that have been provided so far to unseal
 */
final case class SealStatus(`sealed`: Boolean,
                            total: Int,
                            quorum: Int,
                            progress: Int)

final case class RootToken(value: String) extends AnyVal

final case class Rule(path: String,
                      capabilities: List[String],
                      policy: Option[String])

final case class Mount(path: String,
                       `type`: String,
                       description: String,
                       defaultLeaseTTL: Int,
                       maxLeaseTTL: Int)

object Vault {
  def isInitialized: VaultF[Boolean] =
    Free.liftF(IsInitialized)

  def initialize(masters: Int, quorum: Int): VaultF[InitialCreds] =
    Free.liftF(Initialize(Initialization(masters, quorum)))

  def unseal(key: MasterKey): VaultF[SealStatus] =
    Free.liftF(Unseal(key))

  def seal: VaultF[Unit] =
    Free.liftF(Seal)

  def sealStatus: VaultF[SealStatus] =
    Free.liftF(GetSealStatus)

  def get(path: String): VaultF[String] = //VaultF[Option[SortedMap[String, String]]] =
    Free.liftF(Get(path))

  def set(path: String, value: String): VaultF[Unit] =
    Free.liftF(Set(path, value))

  def createPolicy(name: String, rules: List[Rule]): VaultF[Unit] =
    Free.liftF(CreatePolicy(name, rules))

  def deletePolicy(name: String): VaultF[Unit] =
    Free.liftF(DeletePolicy(name))

  def getMounts: VaultF[SortedMap[String, Mount]] =
    Free.liftF(GetMounts)

  def createToken(
    policies: Option[List[String]] = None,
    renewable: Boolean = true,
    ttl: Option[FiniteDuration] = None,
    numUses: Long = 0L
  ): VaultF[Token] = Free.liftF(CreateToken(policies, renewable, ttl, numUses))

  case object IsInitialized extends Vault[Boolean]
  final case class Initialize(init: Initialization) extends Vault[InitialCreds]
  final case class Unseal(key: MasterKey) extends Vault[SealStatus]
  case object GetSealStatus extends Vault[SealStatus]
  case object Seal extends Vault[Unit]
  final case class Get(path: String) extends Vault[String] // Vault[Option[SortedMap[String, String]]]
  final case class Set(path: String, value: String) extends Vault[Unit]
  case object GetMounts extends Vault[SortedMap[String, Mount]]
  final case class CreatePolicy(name: String, rules: List[Rule]) extends Vault[Unit]
  final case class DeletePolicy(name: String) extends Vault[Unit]
  final case class CreateToken(policies: Option[List[String]], renewable: Boolean, ttl: Option[FiniteDuration], numUses: Long = 0L) extends Vault[Token]
}


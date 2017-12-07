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

import helm.ConsulOp
import nelson.Domain.{DCUnit, Deployment, Namespace}
import nelson.DeploymentStatus.Ready
import nelson.cleanup.Sweeper
import nelson.cleanup.Sweeper.UnclaimedResources
import nelson.storage.StoreOp
import nelson.storage.StoreOp.{ListDeploymentsForNamespaceByStatus, ListNamespacesForDomain}

import scalaz.concurrent.Task
import scalaz.{-\/, \/-, ~>}

class SweeperSpec extends NelsonSuite {

  def cfg(dcs: List[String], sto: StoreOp ~> Task, csl: ConsulOp ~> Task) = config.copy(
    domains = dcs.map(name => Domain(name, null, null, null, None,
      Infrastructure.Interpreters(null, csl, null, null, null, null, null), None, null)),
    interpreters = config.interpreters.copy(storage = sto)
  )

  def ns(name: String, id: Long) : Namespace = Namespace(id, NamespaceName(name), name)

  def dep(id: nelson.ID, name: String, hash: String, namespace: Namespace) : Deployment =
    Deployment(id, DCUnit(id, name, Version(1, 0, 0), "", Set.empty, Set.empty, Set.empty),
      hash, namespace, null, null, s"plan-$hash", s"guid-$hash", "retain-latest")


  "The Sweeper" should "Only delete entres that don't have stack names in the routing graph and track unclaimedResource" in {
    val dcs = List("texas", "california")
    val namespaces = List(ns("qa", 0L), ns("prod", 1L))
    val deps = List(dep(0L, "dep1", "dep1hash", namespaces(0)), dep(0L, "dep2", "dep2hash", namespaces(1)))

    val consulKeys = List(
      "lighthouse/discovery/v1/dep1--1-0-0--dep1hash",      // Is defined in Nelson
      "unclaimedResource-without-discovery-prefix",         // Doesn't have the correct prefix, who owns this?  Track as unclaimedResource.
      "lighthouse/discovery/v1/dep2--1-0-0--dep2hash",      // Also defined in Nelson
      "lighthouse/discovery/v1/dep3--1-0-0--dep3hash",      // Not defined in Nelson (should be deleted)
      "lighthouse/discovery/v1/this-is-unclaimedResource!", // Not even the correct format, who owns this?  Track as unclaimedResource.
      "lighthouse/discovery/v1/dep4--1-0-0--dep4hash"       // Also not in Nelson (should be deleted)
    )

    val (consulKeyInNelson1, consulKeyUnclaimedResource1, consulKeyInNelson2, consulKeyNotInNelson1, consulKeyUnclaimedResource2, consulKeyNotInNelson2) =
      (consulKeys(0), consulKeys(1), consulKeys(2), consulKeys(3), consulKeys(4), consulKeys(5))

    val sto = new (StoreOp ~> Task) {
      def apply[A](fa: StoreOp[A]) : Task[A] = fa match {
        case ListNamespacesForDomain(dcName) => Task.delay(namespaces.toSet)
        case ListDeploymentsForNamespaceByStatus(nsId, deploymentStatuses, None) => Task.delay(deps.toSet.map((d : Deployment) => d -> Ready))
        case _ => Task.fail(new Exception("Unexpected Store Operation Executed"))
      }
    }

    val csl = new (ConsulOp ~> Task) {
      def apply[A](fa: ConsulOp[A]) : Task[A] = fa match {
        case ConsulOp.ListKeys("lighthouse/discovery/v1/") => Task.now(consulKeys.toSet)
        case _ => Task.fail(new Exception("Unexpected Store Operation Executed"))
      }
    }

    //////////// Run And verification

    val results = Sweeper.cleanupLeakedConsulDiscoveryKeys(cfg(dcs, sto, csl)).run

    var deleteKeys = List.empty[(String, String)]
    var unclaimedResource = List.empty[(String, Int)]

    results.foreach { r =>
      r match {
        case (dc, -\/(UnclaimedResources(n))) => unclaimedResource = unclaimedResource :+ (dc.name -> n)
        case (dc, \/-(op)) => op.resume.leftMap(_.fi) match {
          case -\/(c) => deleteKeys = deleteKeys :+ (dc.name -> c.asInstanceOf[ConsulOp.Delete].key)
          case _ => throw new Exception("Unexpected Result.")
        }
      }
    }

    deleteKeys.size should be(4)
    unclaimedResource.size should be(2)

    deleteKeys.contains(dcs(0) -> consulKeyNotInNelson1) should be(true)
    deleteKeys.contains(dcs(1) -> consulKeyNotInNelson1) should be(true)
    deleteKeys.contains(dcs(0) -> consulKeyNotInNelson2) should be(true)
    deleteKeys.contains(dcs(1) -> consulKeyNotInNelson2) should be(true)

    unclaimedResource.contains(dcs(0) -> 2) should be(true)
    unclaimedResource.contains(dcs(1) -> 2) should be(true)
  }

}

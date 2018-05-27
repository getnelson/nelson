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
import nelson.Datacenter.{DCUnit, Deployment, Namespace}
import nelson.DeploymentStatus.Ready
import nelson.Util._
import nelson.cleanup.Sweeper
import nelson.cleanup.Sweeper.UnclaimedResources
import nelson.storage.StoreOp
import nelson.storage.StoreOp.{ListDeploymentsForNamespaceByStatus, ListNamespacesForDatacenter}

import cats.~>
import cats.effect.IO

class SweeperSpec extends NelsonSuite {

  def cfg(dcs: List[String], sto: StoreOp ~> IO, csl: ConsulOp ~> IO) = config.copy(
    datacenters = dcs.map { name =>
      Datacenter(
        name,
        null,
        null,
        null,
        None,
        Infrastructure.Interpreters(
          stubbedInterpreter,
          csl,
          stubbedInterpreter,
          stubbedInterpreter,
          stubbedInterpreter,
          stubbedInterpreter,
          stubbedInterpreter,
          stubbedInterpreter
        ),
        None,
        null)
    },
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

    val sto = new (StoreOp ~> IO) {
      def apply[A](fa: StoreOp[A]) : IO[A] = fa match {
        case ListNamespacesForDatacenter(dcName) => IO(namespaces.toSet)
        case ListDeploymentsForNamespaceByStatus(nsId, deploymentStatuses, None) => IO(deps.toSet.map((d : Deployment) => d -> Ready))
        case _ => IO.raiseError(new Exception("Unexpected Store Operation Executed"))
      }
    }

    val csl = new (ConsulOp ~> IO) {
      def apply[A](fa: ConsulOp[A]) : IO[A] = fa match {
        case ConsulOp.KVListKeys("lighthouse/discovery/v1/") => IO.pure(consulKeys.toSet)
        case _ => IO.raiseError(new Exception("Unexpected Store Operation Executed"))
      }
    }

    //////////// Run And verification

    val results = Sweeper.cleanupLeakedConsulDiscoveryKeys(cfg(dcs, sto, csl)).unsafeRunSync()

    var deleteKeys = List.empty[(String, String)]
    var unclaimedResource = List.empty[(String, Int)]

    def interp(dc: Datacenter): ConsulOp ~> IO = new (ConsulOp ~> IO) {
      def apply[A](fa: ConsulOp[A]): IO[A] = fa match {
        case ConsulOp.KVDelete(key) => IO(deleteKeys = deleteKeys :+ (dc.name -> key))
        case _ => IO.raiseError(new Exception("Unexpected Result."))
      }
    }

    results.foreach { r =>
      r match {
        case (dc, Left(UnclaimedResources(n))) => unclaimedResource = unclaimedResource :+ (dc.name -> n)
        case (dc, Right(op)) => helm.run(interp(dc), op).unsafeRunSync()
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

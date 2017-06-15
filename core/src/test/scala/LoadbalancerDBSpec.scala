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

import doobie.imports._
import scalaz._,Scalaz._
import scalaz.concurrent.Task
import storage.StoreOp
import org.scalatest.{BeforeAndAfterEach}
import scala.concurrent.duration._

class LoadbalancerDBSpec extends NelsonSuite with BeforeAndAfterEach {

  import Manifest.{Loadbalancer,Port,BackendDestination,Route,Versioned}


  override def beforeEach: Unit = {
   (
    sql"SET REFERENTIAL_INTEGRITY FALSE; -- YOLO".update.run >>
    sql"TRUNCATE TABLE loadbalancer_routes".update.run >>
    sql"TRUNCATE TABLE loadbalancer_deployments".update.run >>
    sql"TRUNCATE TABLE loadbalancers".update.run >>
    sql"TRUNCATE TABLE releases".update.run >>
    sql"TRUNCATE TABLE namespaces".update.run >>
    sql"TRUNCATE TABLE datacenters".update.run >>
    sql"SET REFERENTIAL_INTEGRITY TRUE; -- COYOLO".update.run
  ).void.transact(stg.xa).run
  }

  val lb = Loadbalancer("lb", Vector(Route(Port("default", 8080, "http"),
    BackendDestination("service", "default"))), Some(MajorVersion(1)))

  val lb2 = lb.copy(name = "lb2")

  val dc = datacenter(testName)

  val namespace = NamespaceName(testName.toLowerCase)

  it should "be able to create loadbalancer then find it" in {
    (for {
      _  <- nelson.storage.run(config.storage, StoreOp.insertOrUpdateRepositories(List(repo.toOption.get)))
      dc <- nelson.storage.run(config.storage, StoreOp.createDatacenter(dc))
      ns <- nelson.storage.run(config.storage, StoreOp.createNamespace(testName, namespace))
      id <- nelson.storage.run(config.storage, StoreOp.insertLoadbalancerIfAbsent(Versioned(lb),9999))
      d  <- nelson.storage.run(config.storage, StoreOp.insertLoadbalancerDeployment(id, ns, "hash", "dns"))
      a  <- nelson.storage.run(config.storage, StoreOp.findLoadbalancerDeployment(lb.name, MajorVersion(1), ns))
    } yield a).run.map(_.loadbalancer.name) should contain(lb.name)
  }

  it should "be able to create loadbalancer then get it by id" in {
    (for {
      _  <- nelson.storage.run(config.storage, StoreOp.insertOrUpdateRepositories(List(repo.toOption.get)))
      dc <- nelson.storage.run(config.storage, StoreOp.createDatacenter(dc))
      ns <- nelson.storage.run(config.storage, StoreOp.createNamespace(testName, namespace))
      id <- nelson.storage.run(config.storage, StoreOp.insertLoadbalancerIfAbsent(Versioned(lb), 9999))
      d  <- nelson.storage.run(config.storage, StoreOp.insertLoadbalancerDeployment(id, ns, "hash", "dns"))
      a  <- nelson.storage.run(config.storage, StoreOp.getLoadbalancerDeployment(d))
    } yield a).run.map(_.loadbalancer.name) should contain(lb.name)
  }

  it should "be able to create loadbalancer then get it by guid" in {
    (for {
      _  <- nelson.storage.run(config.storage, StoreOp.insertOrUpdateRepositories(List(repo.toOption.get)))
      dc <- nelson.storage.run(config.storage, StoreOp.createDatacenter(dc))
      ns <- nelson.storage.run(config.storage, StoreOp.createNamespace(testName, namespace))
      id <- nelson.storage.run(config.storage, StoreOp.insertLoadbalancerIfAbsent(Versioned(lb), 9999))
      d  <- nelson.storage.run(config.storage, StoreOp.insertLoadbalancerDeployment(id, ns, "hash", "dns"))
      a  <- nelson.storage.run(config.storage, StoreOp.getLoadbalancerDeployment(d))
      b  <- nelson.storage.run(config.storage, StoreOp.getLoadbalancerDeploymentByGUID(a.get.guid))
    } yield b).run.map(_.loadbalancer.name) should contain(lb.name)
  }

  it should "not create a new loadbalancer if it already exists" in {
    val (id1, id2) = (for {
      _   <- nelson.storage.run(config.storage, StoreOp.insertOrUpdateRepositories(List(repo.toOption.get)))
      dc  <- nelson.storage.run(config.storage, StoreOp.createDatacenter(dc))
      ns  <- nelson.storage.run(config.storage, StoreOp.createNamespace(testName, namespace))
      id  <- nelson.storage.run(config.storage, StoreOp.insertLoadbalancerIfAbsent(Versioned(lb),9999))
      id2 <- nelson.storage.run(config.storage, StoreOp.insertLoadbalancerIfAbsent(Versioned(lb),9999))
    } yield (id,id2)).run
    id1 should equal(id2)
  }

  it should "be able to create loadbalancers, make deploy, and then find them by namespace" in {
    (for {
      _  <- nelson.storage.run(config.storage, StoreOp.insertOrUpdateRepositories(List(repo.toOption.get)))
      dc <- nelson.storage.run(config.storage, StoreOp.createDatacenter(dc))
      ns <- nelson.storage.run(config.storage, StoreOp.createNamespace(testName, namespace))
      id <- nelson.storage.run(config.storage, StoreOp.insertLoadbalancerIfAbsent(Versioned(lb2),9999))
      d  <- nelson.storage.run(config.storage, StoreOp.insertLoadbalancerDeployment(id, ns, "hash", "dns"))
      a  <- nelson.storage.run(config.storage, StoreOp.listLoadbalancerDeploymentsForNamespace(ns))
    } yield a).run.map(_.loadbalancer.name) should contain(lb2.name)
  }

  it should "be able to delete loadbalancer by id" in {
    val before = (for {
      _  <- nelson.storage.run(config.storage, StoreOp.insertOrUpdateRepositories(List(repo.toOption.get)))
      dc <- nelson.storage.run(config.storage, StoreOp.createDatacenter(dc))
      ns <- nelson.storage.run(config.storage, StoreOp.createNamespace(testName, namespace))
      id <- nelson.storage.run(config.storage, StoreOp.insertLoadbalancerIfAbsent(Versioned(lb),9999))
      d  <- nelson.storage.run(config.storage, StoreOp.insertLoadbalancerDeployment(id, ns, "hash", "dns"))
      a  <- nelson.storage.run(config.storage, StoreOp.getLoadbalancerDeployment(d))
    } yield a).run

    before.map(_.loadbalancer.name) should contain(lb.name)

    val after = (for {
      _ <- nelson.storage.run(config.storage, StoreOp.deleteLoadbalancerDeployment(before.get.id))
      a <- nelson.storage.run(config.storage, StoreOp.getLoadbalancerDeployment(before.get.id))
    } yield a).run

    after should equal(None)
  }
}

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

import nelson.storage.StoreOp

import cats.data.NonEmptyList
import cats.implicits._

import doobie._
import doobie.implicits._

import org.scalatest.{FlatSpec,Matchers,BeforeAndAfterEach}

class RepoDBSpec extends FlatSpec with Matchers with BeforeAndAfterEach {

  val storage = TestStorage.storage("RepoSpec")

  val uri = new java.net.URI("http://foo")
  val user = User("login", uri, Some("user"), None, List(Organization(0L, Some("org1"), "org1", uri),Organization(1L, Some("org2"), "org2", uri)))

  val slug1 = Slug("org1","repo1")
  val repo1 = Repo(1, slug1.toString, RepoAccess.Admin.toString, Some(Hook(0L, true))).toOption.get

  val slug2 = Slug("org2","repo1")
  val repo2 = Repo(2, slug2.toString, RepoAccess.Admin.toString, None).toOption.get

  val slug3 = Slug("org1","repo2")
  val repo3 = Repo(3, slug3.toString, RepoAccess.Admin.toString, None).toOption.get

  def trunc: ConnectionIO[Unit] = (
    sql"SET REFERENTIAL_INTEGRITY FALSE; -- YOLO".update.run *>
    sql"TRUNCATE TABLE repositories".update.run *>
    sql"TRUNCATE TABLE user_repositories".update.run *>
    sql"SET REFERENTIAL_INTEGRITY TRUE; -- COYOLO".update.run
  ).void

  override def beforeEach: Unit = {
    trunc.transact(storage.xa).unsafeRunSync()
    StoreOp.insertOrUpdateRepositories(List(repo1,repo1,repo2,repo3)).foldMap(storage).unsafeRunSync()
  }

  it should "list repos for user" in {

    val repos = StoreOp.listRepositoriesWithOwner(user, "org1").foldMap(storage).unsafeRunSync()
    repos.map(_.id).toSet should equal (Set(repo1.id,repo3.id))

    val repos1 = StoreOp.listRepositoriesWithOwner(user, "org2").foldMap(storage).unsafeRunSync()
    repos1.map(_.id).toSet should equal (Set(repo2.id))
    repos1.map(_.access).toSet should equal (Set(RepoAccess.Unknown)) // unkown because user is not currently linked to repo

    StoreOp.linkRepositoriesToUser(List(repo1,repo2,repo3), user).foldMap(storage).unsafeRunSync()

    val repos3 = StoreOp.listRepositoriesWithOwner(user, "org1").foldMap(storage).unsafeRunSync() // user is linked so access is known
    repos3.map(_.access).toSet should equal (Set(RepoAccess.Admin))
  }

  it should "soft delete repos" in {

    val repos = StoreOp.listRepositoriesWithOwner(user, "org1").foldMap(storage).unsafeRunSync()
    repos.map(_.id).toSet should equal (Set(repo1.id,repo3.id))

    val repos1 = StoreOp.listRepositoriesWithOwner(user, "org2").foldMap(storage).unsafeRunSync()
    repos1.map(_.id).toSet should equal (Set(repo2.id))

    StoreOp.deleteRepositories(NonEmptyList.of(repo1,repo2)).foldMap(storage).unsafeRunSync()

    val repos3 = StoreOp.listRepositoriesWithOwner(user, "org1").foldMap(storage).unsafeRunSync()
    repos3.map(_.id).toSet should equal (Set(repo3.id))

    val repos4 = StoreOp.listRepositoriesWithOwner(user, "org2").foldMap(storage).unsafeRunSync()
    repos4.map(_.id).toSet should equal (Set())
  }
}

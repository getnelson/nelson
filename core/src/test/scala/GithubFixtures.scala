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

object GitFixtures {
  import cats.~>
  import cats.effect.IO
  import Github._
  import nelson.Json._
  import argonaut._, Argonaut._
  import Util._

  val interpreter = Interpreter()

  val token = AccessToken("some-access-token")

  val user = User(
    "login",
    new java.net.URI("avatar"),
    "Randal McMurphy",
    Some("one@two.com")
  )

  val keys = List(
    OrgKey(0L, "slug 0"),
    OrgKey(1L, "slug 1")
  )

  val orgs = List(Organization(0L, Some("name"),"slug",new java.net.URI("avatar")))

  val asset = Asset(0, "manifest.deployable.v1.b.yml", "", "", Some("content"))

  val contents:IO[Option[Contents]] =
    loadResourceAsString("/nelson/dependencies.bar_2.0.0.yml").map { c =>
      val encoded = java.util.Base64.getMimeEncoder.encodeToString(c.getBytes("utf-8"))
      Some(Contents(encoded,"manifest.deployable.v1.b.yml",encoded.length.toLong))
    }

  def release(id: ID) = Release(id,
    "https://github.example.com/api/v3/repos/tim/howdy/releases/250",
    "https://github.example.com/tim/howdy/releases/tag/0.13.17",
    List(Asset(119,
     "example-howdy.deployable.yml",
     "uploaded",
     "https://github.example.com/api/v3/repos/tim/howdy/releases/assets/119",
     None)
    ),
    "0.13.17"
  )

  val webhook = WebHook(
    1, "web", List("push","pull_request"),
    true, Map("url"->"http://example.com/webhook", "content_type"->"json")
  )

  val slug = Slug("foo", "bar")

  val repo = Repo(1296269L,Slug("octocat","Hello-World"),RepoAccess.Push)

  val repos = List(repo)

  case class Interpreter() extends (Github.GithubOp ~> IO) {

    def apply[A](in: Github.GithubOp[A]): IO[A] = in match {

      case GetAccessToken(fromCode: String) =>
        fromCode match {
          case "crash" =>
            IO.raiseError(new Exception("Crash!"))
          case _ =>
            loadResourceAsString("/nelson/github.oauth.json")
              .flatMap(fromJson[AccessToken])
        }

      case GetUser(token: AccessToken) =>
        token match {
          case AccessToken("crash") =>
            IO.raiseError(new Exception("Crash!"))
          case _ =>
            loadResourceAsString("/nelson/github.user.json")
              .flatMap(fromJson[Github.User])
        }

      case GetUserOrgKeys(token: AccessToken) =>
        loadResourceAsString("/nelson/github.keys.json")
          .flatMap(fromJson[List[OrgKey]])

      case GetOrganizations(keys: List[OrgKey], t: AccessToken) =>
        loadResourceAsString("/nelson/github.organization.json")
          .flatMap(fromJson[List[Organization]])

      case GetReleaseAssetContent(asset: Github.Asset, t: AccessToken) =>
        IO.pure(asset)

      case GetRelease(slug: Slug, releaseId: ID, t: AccessToken) =>
        loadResourceAsString("/nelson/github.release.json")
          .flatMap(fromJson[Github.Release])

      case GetUserRepositories(token: AccessToken) =>
        loadResourceAsString("/nelson/github.repos.json")
          .flatMap(fromJson[List[Repo]])

      case GetFileFromRepository(slug: Slug, path: String, tagOrBranch: String, t: AccessToken) =>
        contents

      case GetRepoWebHooks(slug: Slug, token: AccessToken) =>
        loadResourceAsString("/nelson/github.webhook.json")
          .flatMap(fromJson[List[WebHook]])

      case PostRepoWebHook(slug: Slug, hook: Github.WebHook, t: AccessToken) =>
        IO.pure(hook)

      case DeleteRepoWebHook(slug: Slug, id: Long, token: AccessToken) =>
        IO.unit
    }
  }
}

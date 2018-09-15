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

import cats.effect.IO

import ca.mrvisser.sealerate

import java.net.URI

import org.http4s.{Request => HttpRequest, Response => HttpResponse, argonaut => _, _}
import org.http4s.argonaut._
import org.http4s.client.{Client, UnexpectedStatus}
import org.http4s.headers.Location

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.FiniteDuration

object Github {
  final case class WebHook(
    id: Long,
    name: String,
    events: List[String],
    active: Boolean,
    config: Map[String,String]
  ){
    val callback: Option[URI] =
      config.get("url").map(new URI(_))
  }

  object WebHook {
    def create(
      events: List[String],
      callback: URI
    ): WebHook = WebHook(0l,"web",events,true,
      Map("url" -> callback.toString,
          "content_type" -> "json"))
  }

  final case class OrgKey(
    id: Long,
    slug: String
  )

  final case class User(
    login: String,
    avatar: URI,
    name: String,
    email: Option[String]
  )

  final case class Contents(
    base64: String,
    name: String,
    size: Long
  ){
    def decoded =
      new String(java.util.Base64.getMimeDecoder.decode(base64.replace("\\n","")))
  }

  sealed trait Event

  /**
   * this is sent by github when the hook is initially setup,
   * just to make sure that the specified location is actually
   * reachable by github and is responding
   */
  final case class PingEvent(
    zen: String
  ) extends Event

  final case class ReleaseEvent(
    id: Long,
    slug: Slug,
    repositoryId: Long
  ) extends Event

  final case class Release(
    id: Long,
    url: String,
    htmlUrl: String,
    assets: List[Asset],
    tagName: String
  ){
    def findAssetContent(withName: String): IO[String] =
      this.assets
        .find(_.name.trim.toLowerCase == withName.trim.toLowerCase)
        .flatMap(_.content)
        .fold[IO[String]](IO.raiseError(new ProblematicDeployable(s"could not find an asset named $withName", url)))(IO.pure)
  }

  final case class Asset(
    id: Long,
    name: String,
    state: String,
    url: Uri,
    content: Option[String] = None
  )


  ////// GITHUB DSL & Interpreter

  import cats.~>
  import cats.free.Free

  sealed trait GithubOp[A]

  type GithubOpF[A] = Free[GithubOp, A]

  final case class GetAccessToken(fromCode: String)
    extends GithubOp[AccessToken]

  final case class GetUser(token: AccessToken)
    extends GithubOp[Github.User]

  final case class GetUserOrgKeys(token: AccessToken)
    extends GithubOp[List[Github.OrgKey]]

  final case class GetOrganizations(keys: List[Github.OrgKey], token: AccessToken)
    extends GithubOp[List[Organization]]

  final case class GetReleaseAssetContent(asset: Github.Asset, t: AccessToken)
    extends GithubOp[Github.Asset]

  final case class GetRelease(slug: Slug, releaseId: ID, t: AccessToken)
    extends GithubOp[Github.Release]

  final case class GetUserRepositories(token: AccessToken)
    extends GithubOp[List[Repo]]

  final case class GetFileFromRepository(slug: Slug, path: String, tagOrBranch: String, t: AccessToken)
    extends GithubOp[Option[Github.Contents]]

  final case class GetRepoWebHooks(slug: Slug, token: AccessToken)
    extends GithubOp[List[Github.WebHook]]

  final case class PostRepoWebHook(slug: Slug, hook: Github.WebHook, t: AccessToken)
    extends GithubOp[Github.WebHook]

  final case class DeleteRepoWebHook(slug: Slug, id: Long, token: AccessToken)
    extends GithubOp[Unit]

  object Request {

    /**
     * Given a tempoary access code during OAuth login, callback
     * to github to change it into a legit access token that we can
     * use to make API calls on the users behalf.
     */
    def fetchAccessToken(code: String): GithubOpF[AccessToken] =
      Free.liftF(GetAccessToken(code))

    def fetchUser(token: AccessToken): GithubOpF[Github.User] =
      Free.liftF(GetUser(token))

    def fetchUserOrgKeys(token: AccessToken): GithubOpF[List[Github.OrgKey]] =
      Free.liftF(GetUserOrgKeys(token))

    def fetchOrganizations(keys: List[Github.OrgKey], token: AccessToken): GithubOpF[List[Organization]] =
      Free.liftF(GetOrganizations(keys, token))

    /** * https://developer.github.com/v3/repos/releases/#get-a-single-release-asset */
    def fetchReleaseAssetContent(asset: Github.Asset)(t: AccessToken): GithubOpF[Github.Asset] =
      Free.liftF(GetReleaseAssetContent(asset, t))

    /**
     * reach out and fetch a specific release from a repo. we use this when
     * nelson gets notified of a release, as the payload we get does not contain
     * the assets that we need.
     */
    def fetchRelease(slug: Slug, id: ID)(t: AccessToken): GithubOpF[Github.Release] =
      for {
        r <- Free.liftF(GetRelease(slug, id, t))
        a <- fetchReleaseAssets(r)(t)
      } yield r.copy(assets = a)

    /**
     * given a user access token, recursivly fetch all the repositories said
     * user is an admin or collaborator for.
     */
    def listUserRepositories(token: AccessToken): GithubOpF[List[Repo]] =
      Free.liftF(GetUserRepositories(token))

    /** * https://developer.github.com/v3/repos/contents/#get-contents */
    def fetchFileFromRepository(s: Slug, p: String, tOrB: String)(t: AccessToken):  GithubOpF[Option[Github.Contents]] =
      Free.liftF(GetFileFromRepository(s, p, tOrB, t))

    /** * https://developer.github.com/v3/repos/hooks/#list-hooks */
    def fetchRepoWebhooks(slug: Slug)(token: AccessToken): GithubOpF[List[Github.WebHook]] =
      Free.liftF(GetRepoWebHooks(slug, token))

    /** * https://developer.github.com/v3/repos/hooks/#create-a-hook */
    def createRepoWebhook(slug: Slug, hook: Github.WebHook)(t: AccessToken): GithubOpF[Github.WebHook] =
      Free.liftF(PostRepoWebHook(slug, hook, t))

    /** * https://developer.github.com/v3/repos/hooks/#delete-a-hook */
    def deleteRepoWebhook(slug: Slug, id: Long)(token: AccessToken): GithubOpF[Unit] =
      Free.liftF(DeleteRepoWebHook(slug, id, token))

    /**
     * retrieve the user-specifc information about the agent logging
     * into the system. obtaining this information so we can have a neat
     * user interface / experience without constantly fetching back
     * to github.
     */
    def fetchUserData(token: AccessToken): GithubOpF[nelson.User] =
      for {
        gu   <- fetchUser(token)
        keys <- fetchUserOrgKeys(token)
        orgs <- fetchOrganizations(keys, token)
      } yield nelson.User(gu.login, gu.avatar, gu.name, gu.email, orgs)

    def fetchReleaseAssets(r: Github.Release)(t: AccessToken): GithubOpF[List[Github.Asset]] = {
      import cats.implicits._
      r.assets.toList.traverse(asset => fetchReleaseAssetContent(asset)(t))
    }
  }

  final class GithubHttp(
    cfg: GithubConfig,
    client: Client[IO],
    timeout: FiniteDuration,
    ec: ExecutionContext
  ) extends (GithubOp ~> IO) {
    import Interpreter._
    import nelson.Json._

    import argonaut.DecodeJson
    import argonaut.Argonaut._
    import cats.instances.list._
    import cats.instances.string._
    import cats.syntax.applicativeError._
    import fs2.async.parallelTraverse
    import org.http4s.syntax.string._

    implicit val githubHttpExecutionContext: ExecutionContext = ec

    implicit def argonautEntityDecoder[A: DecodeJson]: EntityDecoder[IO, A] =
      jsonOf[IO, A]

    def apply[A](in: GithubOp[A]): IO[A] = in match {
      case GetAccessToken(fromCode: String) =>
        val json = argonaut.Json(
          "client_id" := cfg.clientId,
          "client_secret" := cfg.clientSecret,
          "code" := fromCode
        )

        val request = HttpRequest[IO](Method.POST, cfg.tokenEndpoint)
          .putHeaders(Header("Accept", "application/json"))
          .withBody(json)

        val handler: HttpResponse[IO] => IO[AccessToken] = response => for {
          raw <- response.bodyAsText.compile.foldSemigroup
          actuallyDecoded <- response.as[AccessToken]
        } yield actuallyDecoded

        client.fetch(request)(handler)

      case GetUser(token: AccessToken) =>
        val request = HttpRequest[IO](Method.GET, cfg.userEndpoint).token(token)
        client.expect[Github.User](request)

      case GetUserOrgKeys(token: AccessToken) =>
        val request = HttpRequest[IO](Method.GET, cfg.userOrgsEndpoint).token(token)
        client.expect[List[Github.OrgKey]](request)

      case GetOrganizations(keys: List[Github.OrgKey], t: AccessToken) =>
        parallelTraverse(keys) { key =>
          val request = HttpRequest[IO](Method.GET, cfg.orgEndpoint(key.slug)).token(t)
          client.expect[Organization](request)
        }

      case GetReleaseAssetContent(asset: Github.Asset, t: AccessToken) =>
        val request = HttpRequest[IO](Method.GET, asset.url)
          .putHeaders(Header("Accept", "application/octet-stream"))
          .token(t)

        val handler: HttpResponse[IO] => IO[Uri] = response => {
          response.headers.get(Location) match {
            case None => IO.raiseError(UnexpectedGithubResponse("fetching release asset contents", "missing location header"))
            case Some(Location(loc)) => IO.pure(loc)
          }
        }

        for {
          assetLocation <- client.fetch(request)(handler)
          assetRequest  = HttpRequest[IO](Method.GET, assetLocation)
          assetContents <- client.fetch(assetRequest)(_.bodyAsText.compile.foldMonoid)
        } yield asset.copy(content = Option(assetContents))

      case GetRelease(slug: Slug, releaseId: ID, t: AccessToken) =>
        val request = HttpRequest[IO](Method.GET, cfg.releaseEndpoint(slug, releaseId)).token(t)
        client.expect[Github.Release](request)

      case GetUserRepositories(t: AccessToken) =>
        def go(uri: Uri)(accum: List[Repo]): IO[List[Repo]] = {
          val req = HttpRequest[IO](Method.GET, uri).token(t)
          for {
            resp <- client.fetch(req)(paginationHandler)
            (repos, links) = resp
            accum0 = accum ++ repos
            reposList <- links.get(Next).fold(IO.pure(accum0))(next => go(next)(accum0))
          } yield reposList
        }
        go(cfg.repoEndpoint(page = 1))(Nil)

      case GetFileFromRepository(slug: Slug, path: String, tagOrBranch: String, t: AccessToken) =>
        val queryParams = Map(("ref", List(java.net.URLEncoder.encode(tagOrBranch, "UTF-8"))))
        val uri = cfg.contentsEndpoint(slug, path).setQueryParams(queryParams)
        val req = HttpRequest[IO](Method.GET, uri).token(t)
        client.expect[Github.Contents](req).map(Option(_)).handleError(_ => None)

      case GetRepoWebHooks(slug: Slug, t: AccessToken) =>
        val req = HttpRequest[IO](Method.GET, cfg.webhookEndpoint(slug)).token(t)
        client.expect[List[Github.WebHook]](req).handleError(_ => List.empty)

      case PostRepoWebHook(slug: Slug, hook: Github.WebHook, t: AccessToken) =>
        import argonaut._, Argonaut._
        val req = HttpRequest[IO](Method.POST, cfg.webhookEndpoint(slug))
          .token(t)
          .withBody(hook.asJson)
        client.expect[Github.WebHook](req)

      case DeleteRepoWebHook(slug: Slug, id: Long, t: AccessToken) =>
        // Apparently this only compiles if I inline everything into one expression
        // Breaking out the request into a variable makes scalac complain IO[Unit] is not <: IO[A], love it
        client.expect[String](HttpRequest[IO](Method.DELETE, cfg.webhookEndpoint(slug) / id.toString).token(t)).map(_ => ()).recover {
          case UnexpectedStatus(Status.NotFound) => ()
        }
    }

    /////////////////////////// INTERNALS ///////////////////////////

    case class GithubApiError(code: Int, body: String)
        extends Exception("Unexpected response status: %d".format(code))

    case class UnexpectedGithubResponse(context: String, message: String)
        extends Exception(s"Unexpected response from Github when ${context}: ${message}")

    import cats.syntax.either._

    def paginationHandler(response: HttpResponse[IO]): IO[(List[Repo], Map[Step, Uri])] =
      if (response.status.code / 100 == 2) {
        val empty = Map.empty[Step, Uri]
        val repos = response.as[List[Repo]]

        val links = response.headers.get("Link".ci).map(_.value).fold(empty) { value =>
          value.split(",").foldLeft(empty) { (map, repoLink) =>
            map ++ parseLink(repoLink).fold(empty)(Map(_))
          }.toMap
        }

        repos.map((_, links))
      } else {
        val statusCode = response.status.code
        for {
          b <- response.bodyAsText.compile.foldSemigroup
          r <- IO.raiseError[(List[Repo], Map[Step, Uri])](GithubApiError(statusCode, b.mkString("")))
        } yield r
      }

    /**
    * for splitting the github links header output
    */
    private[nelson] def parseLink(in: String): Option[(Step, Uri)] = {
      if (in.nonEmpty) {
        val Array(uri,rel) = in.split(";")
        val linkr = "<(.*)>".r
        val relr  = "rel=\"(.*)\"".r

        val ouri = linkr.replaceFirstIn(uri, "$1").trim
        val orel = relr.replaceFirstIn(rel, "$1").trim

        for {
          step <- Step.fromString(orel)
          uri  <- Uri.fromString(ouri).toOption
        } yield (step, uri)
      } else None
    }
  }

  implicit class BedazzledRequest(val r: HttpRequest[IO]) extends AnyVal {
    def token(t: AccessToken): HttpRequest[IO] =
      r.putHeaders(Header("Authorization", s"token ${t.value}"))
  }

  object Interpreter {
    sealed trait Step

    final case object Next extends Step
    final case object Prev extends Step
    final case object First extends Step
    final case object Last extends Step

    object Step {
      val all: Set[Step] = sealerate.values[Step]
      def fromString(in: String): Option[Step] =
        all.find(_.toString.toLowerCase == in.toLowerCase)
    }
  }
}

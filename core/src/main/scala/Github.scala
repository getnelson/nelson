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

import java.net.URI
import scalaz.concurrent.Task
import delorean._
import ca.mrvisser.sealerate

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
    id: String,
    slug: Slug,
    repositoryId: Long
  ) extends Event

  final case class Release(
    id: String,
    url: String,
    htmlUrl: String,
    assets: Seq[Asset],
    tagName: String
  ){
    def findAssetContent(withName: String): Task[String] =
      this.assets
        .find(_.name.trim.toLowerCase == withName.trim.toLowerCase)
        .flatMap(_.content)
        .fold[Task[String]](Task.fail(new ProblematicDeployable(s"could not find an asset named $withName", url)))(Task.now)
  }

  final case class Asset(
    id: Long,
    name: String,
    state: String,
    url: String,
    content: Option[String] = None
  )


  ////// GITHUB DSL & Interpreter

  import scalaz.{Free, ~>, Coyoneda,Monad}

  sealed trait GithubOp[A]

  type GithubOpC[A] = Coyoneda[GithubOp, A]

  type GithubOpF[A] = Free.FreeC[GithubOp, A]

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

  final case class GetRelease(slug: Slug, releaseId: String, t: AccessToken)
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

  def run[F[_]:Monad,A](interpreter: GithubOp ~> F , op: GithubOpF[A]): F[A] =
    Free.runFC(op)(interpreter)

  object Request {

    /**
     * Given a tempoary access code during OAuth login, callback
     * to github to change it into a legit access token that we can
     * use to make API calls on the users behalf.
     */
    def fetchAccessToken(code: String): GithubOpF[AccessToken] =
      Free.liftFC(GetAccessToken(code))

    def fetchUser(token: AccessToken): GithubOpF[Github.User] =
      Free.liftFC(GetUser(token))

    def fetchUserOrgKeys(token: AccessToken): GithubOpF[List[Github.OrgKey]] =
      Free.liftFC(GetUserOrgKeys(token))

    def fetchOrganizations(keys: List[Github.OrgKey], token: AccessToken): GithubOpF[List[Organization]] =
      Free.liftFC(GetOrganizations(keys, token))

    /** * https://developer.github.com/v3/repos/releases/#get-a-single-release-asset */
    def fetchReleaseAssetContent(asset: Github.Asset)(t: AccessToken): GithubOpF[Github.Asset] =
      Free.liftFC(GetReleaseAssetContent(asset, t))

    /**
     * reach out and fetch a specific release from a repo. we use this when
     * nelson gets notified of a release, as the payload we get does not contain
     * the assets that we need.
     */
    def fetchRelease(slug: Slug, id: String)(t: AccessToken): GithubOpF[Github.Release] =
      for {
        r <- Free.liftFC(GetRelease(slug, id, t))
        a <- fetchReleaseAssets(r)(t)
      } yield r.copy(assets = a)

    /**
     * given a user access token, recursivly fetch all the repositories said
     * user is an admin or collaborator for.
     */
    def listUserRepositories(token: AccessToken): GithubOpF[List[Repo]] =
      Free.liftFC(GetUserRepositories(token))

    /** * https://developer.github.com/v3/repos/contents/#get-contents */
    def fetchFileFromRepository(s: Slug, p: String, tOrB: String)(t: AccessToken):  GithubOpF[Option[Github.Contents]] =
      Free.liftFC(GetFileFromRepository(s, p, tOrB, t))

    /** * https://developer.github.com/v3/repos/hooks/#list-hooks */
    def fetchRepoWebhooks(slug: Slug)(token: AccessToken): GithubOpF[List[Github.WebHook]] =
      Free.liftFC(GetRepoWebHooks(slug, token))

    /** * https://developer.github.com/v3/repos/hooks/#create-a-hook */
    def createRepoWebhook(slug: Slug, hook: Github.WebHook)(t: AccessToken): GithubOpF[Github.WebHook] =
      Free.liftFC(PostRepoWebHook(slug, hook, t))

    /** * https://developer.github.com/v3/repos/hooks/#delete-a-hook */
    def deleteRepoWebhook(slug: Slug, id: Long)(token: AccessToken): GithubOpF[Unit] =
      Free.liftFC(DeleteRepoWebHook(slug, id, token))

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
      import scalaz._, Scalaz._
      r.assets.toList.traverse(asset => fetchReleaseAssetContent(asset)(t))
    }
  }

  final class GithubHttp(cfg: ScmConfig, http: dispatch.Http) extends (GithubOp ~> Task) {
    import java.net.URI
    import dispatch._, Defaults._
    import nelson.Json._
    import scalaz.Nondeterminism
    import Interpreter._

    def apply[A](in: GithubOp[A]): Task[A] = in match {

      case GetAccessToken(fromCode: String) =>
        val req: Task[String] = {
          val r = (url(cfg.tokenEndpoint) << Map(
            "client_id" -> cfg.clientId,
            "client_secret" -> cfg.clientSecret,
            "code" -> fromCode)).addHeader("Accept","application/json")
          http(r OK as.String).toTask
        }
        req.flatMap(fromJson[AccessToken])

      case GetUser(token: AccessToken) =>
        for {
          resp <- fetch(cfg.userEndpoint, token)
          user <- fromJson[Github.User](resp)
        } yield user

      case GetUserOrgKeys(token: AccessToken) =>
        for {
          resp <- fetch(cfg.userOrgsEndpoint, token)
          orgs <- fromJson[List[Github.OrgKey]](resp)
        } yield orgs

      case GetOrganizations(keys: List[Github.OrgKey], t: AccessToken) =>
        Nondeterminism[Task].gatherUnordered(
          keys.map(key => fetch(cfg.orgEndpoint(key.slug), t).flatMap(fromJson[Organization]))
        )

      case GetReleaseAssetContent(asset: Github.Asset, t: AccessToken) =>
        val req = url(asset.url)
          .addHeader("Accept","application/octet-stream")
          .setFollowRedirects(false) // TIM: doing this deliberately, such that github.com's s3 storage works.
          .token(t)
        for {
          a <- http(req > as.Response(x => x.getHeader("location"))).toTask
          b <- http(url(a) OK as.String).toTask
        } yield asset.copy(content = Option(b))

      case GetRelease(slug: Slug, releaseId: String, t: AccessToken) =>
        for {
          resp <- fetch(cfg.releaseEndpoint(slug, releaseId), t)
          rel  <- fromJson[Github.Release](resp)
        } yield rel

      case GetUserRepositories(t: AccessToken) =>
        def go(uri: URI)(accum: List[Repo]): List[Repo] = {
          val r = url(uri.toString).token(t)
          val task = for {
            a <- http(r OkWithPagination as.String).toTask
            b <- fromJson[List[Repo]](a._1)
          } yield (b,a._2)
          // going to say this is ok, due to surrounding task...
          // it feels hella janky though.
          val (repos,links) = task.run
          links.get(Next) match {
            case Some(u) => go(u)(accum ++ repos)
            case None    => accum ++ repos
          }
        }
        Task.delay(go(new URI(cfg.repoEndpoint(page = 1)))(Nil))

      case GetFileFromRepository(slug: Slug, path: String, tagOrBranch: String, t: AccessToken) =>
        val r = url(cfg.contentsEndpoint(slug,path))
          .addQueryParameter("ref", java.net.URLEncoder.encode(tagOrBranch, "UTF-8"))
          .token(t)
        for {
          resp <- http(r OkWithErrors as.String).option.toTask
          cont <- resp.fold(
            Task.now(Option.empty[Github.Contents]))(x => fromJson[Github.Contents](x).map(Option(_))
          )
        } yield cont

      case GetRepoWebHooks(slug: Slug, t: AccessToken) =>
        val r = url(cfg.webhookEndpoint(slug)).token(t)
        for {
          resp <- http(r OkWithErrors as.String).option.toTask // returns 404 when repo has no webhooks
          wk   <- resp.fold(
            Task.now(List.empty[Github.WebHook]))(x => fromJson[List[Github.WebHook]](x))
        } yield wk

      case PostRepoWebHook(slug: Slug, hook: Github.WebHook, t: AccessToken) =>
        import argonaut._, Argonaut._
        val json: String = hook.asJson.nospaces
        val req = url(cfg.webhookEndpoint(slug))
          .token(t)
          .setContentType("application/json", "UTF-8") << json
        for {
          resp <- http(req OK as.String).toTask
          wh   <- fromJson[Github.WebHook](resp)
        } yield wh

      case DeleteRepoWebHook(slug: Slug, id: Long, t: AccessToken) =>
        http(url(s"${cfg.webhookEndpoint(slug)}/$id").DELETE.token(t) OK as.String).toTask
          .map(_ => ())
          .handle {
            // swallow 404, as we're being asked to delete something that does not exist
            case StatusCode(404) => ()
         }
    }


    /////////////////////////// INTERNALS ///////////////////////////

    private def fetch(endpoint: String, t: AccessToken): Task[String] =
      http(url(endpoint).token(t) OK as.String).toTask

    case class GithubApiError(code: Int, body: String)
      extends Exception("Unexpected response status: %d".format(code))

    import com.ning.http.client.{Response,AsyncCompletionHandler}
    import collection.JavaConverters._

    class OkWithPaginationHandler[A](f: Response => A) extends AsyncCompletionHandler[(A, Map[Step,URI])] {
      def onCompleted(response: Response): (A,Map[Step,URI]) = {
        if (response.getStatusCode / 100 == 2) {
          val links: Map[Step,URI] =
            Option(response.getHeaders("Link"))
              .map(_.asScala.toList)
              .flatMap(_.headOption)
              .getOrElse("") // TIM: this is hacky, urgh.
              .split(",")
              .foldLeft(List.empty[(Step,URI)])((a,b) =>
                a ++ (if (b.isEmpty) List.empty else tuplize(b).toList)
              ).toMap

          (f(response), links)
        } else {
          throw GithubApiError(response.getStatusCode, response.getResponseBody)
        }
      }

      /**
      * for splitting the github links header output
      */
      private[nelson] def tuplize(in: String): Option[(Step, URI)] = {
        val Array(uri,rel) = in.split(";")
        val linkr = "<(.*)>".r
        val relr  = "rel=\"(.*)\"".r

        val ouri = linkr.replaceFirstIn(uri, "$1").trim
        val orel = relr.replaceFirstIn(rel, "$1").trim

        Step.fromString(orel).map(_ -> new URI(ouri))
      }
    }

    class OkWithErrors[A](f: Response => A) extends AsyncCompletionHandler[A] {
      def onCompleted(response: Response): A = {
        if (response.getStatusCode / 100 == 2){
          f(response)
        } else {
          throw GithubApiError(response.getStatusCode, response.getResponseBody)
        }
      }
    }

    implicit class MyRequestHandlerTupleBuilder(req: Req) {
      def OkWithErrors[T](f: Response => T) =
        (req.toRequest, new OkWithErrors(f))
      def OkWithPagination[T](f: Response => T) =
        (req.toRequest, new OkWithPaginationHandler(f))
    }

    implicit class BedazzledReq(r: Req){
      def token(t: AccessToken): Req = r.addHeader("Authorization",s"token ${t.value}")
    }
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

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

import argonaut.{CodecJson, DecodeJson, DecodeResult, EncodeJson}
import argonaut.Argonaut.{casecodec2, casecodec4, jencode1L}
import nelson.Github._
import org.http4s, http4s._
import org.http4s.Status.Successful
import org.http4s.argonaut.jsonOf
import org.http4s.client.{Client, UnexpectedStatus}
import org.http4s.headers.{Accept, Authorization, MediaRangeAndQValue}
import org.http4s.util.CaseInsensitiveString
import scalaz._, Scalaz._
import scalaz.concurrent.Task
import scala.collection.mutable.ListBuffer
import scala.util.Try
import java.net.{MalformedURLException, URI}

object Gitlab {

  final class GitlabHttp(cfg: ScmConfig, http4sClient: Client) extends (GithubOp ~> Task) {
    def apply[A](in: GithubOp[A]): Task[A] = in match {
      case GetAccessToken(fromCode: String) =>
        fetch[AccessToken](cfg.tokenEndpoint) { uri =>
          http4s.Request(
            Method.POST,
            uri
              .withQueryParam("client_id", cfg.clientId)
              .withQueryParam("client_secret", cfg.clientSecret)
              .withQueryParam("code", fromCode)
              .withQueryParam("grant_type", "authorization_code")
              .withQueryParam("redirect_uri", cfg.redirectUri)
          )
        }

      case GetUser(token: AccessToken) =>
        authFetch[Github.User](cfg.userEndpoint, token)

      case GetUserOrgKeys(token: AccessToken) =>
        authFetch[List[Github.OrgKey]](cfg.userOrgsEndpoint, token)

      case GetOrganizations(keys: List[Github.OrgKey], t: AccessToken) =>
        Nondeterminism[Task].gatherUnordered(
          keys.map(key => authFetch[Organization](cfg.orgEndpoint(key.slug), t))
        )

      case GetUserRepositories(t: AccessToken) =>
        def go(uri: String)(accum: List[Repo]): Task[List[Repo]] = {
          authFetchWithMeta[List[Repo]](uri, t).flatMap { case EntityWithMeta(repos, pagination) =>
            val total = accum ++ repos
            pagination.nextPage.fold(Task.now(total)) { page =>
              val nextLink = pagination.links.getOrElse("next", cfg.repoEndpoint(page))
              go(nextLink)(total)
            }
          }
        }
        go(cfg.repoEndpoint(page = 1))(Nil)

      case GetFileFromRepository(slug: Slug, path: String, tagOrBranch: String, t: AccessToken) =>
        authFetch[Github.Contents](
          cfg.contentsEndpoint(slug, java.net.URLEncoder.encode(path, "UTF-8")),
          t,
          req => req.withUri(req.uri.withQueryParam("ref", tagOrBranch))
        ).map(Option.apply).or(Task.now(Option.empty))

      case GetRepoWebHooks(slug: Slug, t: AccessToken) =>
        authFetch[List[GitlabWebhook]](cfg.webhookEndpoint(slug), t).map(_.map(_.toGithubWebHook))

      case PostRepoWebHook(slug: Slug, hook: Github.WebHook, t: AccessToken) =>
        def eventsParams = {
          val available =
            List(
              "push_events",
              "issues_events",
              "merge_requests_events",
              "tag_push_events",
              "note_events",
              "job_events",
              "pipeline_events",
              "wiki_page_events"
            )
          (available.filter(hook.events.contains).map(_ -> List(true)) ++
            available.filter(!hook.events.contains(_)).map(_ -> List(false))).toMap
        }
        authFetch[GitlabWebhook](cfg.webhookEndpoint(slug), t,
          req =>
            req.withMethod(Method.POST).withUri(req.uri
              .setQueryParams(eventsParams)
              .withQueryParam("id", hook.id)
              .withQueryParam("url", hook.config.getOrElse("url", ""))
            )
        ).map(_.toGithubWebHook)

      case DeleteRepoWebHook(slug: Slug, id: Long, t: AccessToken) =>
        Uri.fromString(s"${ cfg.webhookEndpoint(slug) }/$id")
          .fold(
            t => Task.fail(new MalformedURLException(t.message)), uri =>
              http4sClient.successful(
                http4s.Request(Method.DELETE, uri = uri, headers = Headers(authHeader(t)))
              )
          )
          .map(_ => ())
          .handle {
            // swallow 404 (NotFound) as valid response
            case UnexpectedStatus(Status(404)) => ()
          }

      case GetRelease(slug: Slug, releaseId: String, t: AccessToken) =>
        def urlEncode(s: String) = java.net.URLEncoder.encode(s, "UTF-8")
        authFetch[Tag](cfg.releaseEndpoint(slug, releaseId), t).map { tag =>
          val baseUrl = s"${ cfg.base }/${ urlEncode(slug.owner) }/${ urlEncode(slug.repository) }"
          val tagUrl = s"$baseUrl/tags/${ urlEncode(tag.name) }"
          Release(tag.name, tagUrl, tagUrl, tag.assets(baseUrl), tag.name)
        }.flatMap { release =>
          if (release.assets.isEmpty)
            Task.fail(new IllegalArgumentException(
              s"The tag '$releaseId' for repo '$slug' is not a valid release. " +
                "Have you added an asset as release description?."
            ))
          else
            Task.now(release)
        }

      case GetReleaseAssetContent(asset: Github.Asset, t: AccessToken) =>
        Uri.fromString(asset.url)
            .fold(t => Task.fail(new MalformedURLException(t.message)), uri =>
              http4sClient.fetch(
                http4s.Request(uri = uri, headers = Headers(authHeader(t)))
              )(EntityDecoder.decodeString)
            ).map(body => asset.copy(content = Option(body)))
    }

    private[this] def authFetch[A: EntityDecoder](
        uri: String,
        token: AccessToken,
        f: Request => Request = identity
    ): Task[A] =
      authFetchWithMeta(uri, token, f).map(_.entity)

    private[this] def authHeader(token: AccessToken): Header =
      if (token.isPrivate)
        Header("PRIVATE-TOKEN", token.value)
      else
        Authorization(Credentials.Token(AuthScheme.Bearer, token.value))

    private[this] def authFetchWithMeta[A: EntityDecoder](
        uri: String,
        token: AccessToken,
        f: Request => Request = identity
    ): Task[EntityWithMeta[A]] =
      fetchWithMeta[A](uri) { uri =>
        f(http4s.Request(headers = Headers(authHeader(token)), uri = uri))
      }

    private[this] def fetch[A: EntityDecoder](uri: String)(f: Uri => Request): Task[A] =
      fetchWithMeta(uri)(f).map(_.entity)

    private[this] def fetchWithMeta[A](uri: String)(f: Uri => Request)(implicit d: EntityDecoder[A]): Task[EntityWithMeta[A]] =
      Uri.fromString(uri).map { uri =>
        if (d.consumes.nonEmpty) {
          val m = d.consumes.toList
          f(uri).putHeaders(Accept(MediaRangeAndQValue(m.head), m.tail.map(MediaRangeAndQValue(_)):_*))
        } else f(uri)
      }.fold(
        t => Task.fail(new MalformedURLException(t.message)),
        req => http4sClient.fetch(req) {
          case Successful(resp) =>
            val pagination = resp.headers.toPagination
            d.decode(resp, strict = false).fold(throw _, entity => EntityWithMeta(entity, pagination))
          case failedResponse =>
            Task.fail(UnexpectedStatus(failedResponse.status))
        }
      )
  }

  final case class EntityWithMeta[A](
    entity: A,
    pagination: Pagination
  )

  final case class Pagination(
    totalItems: Long,
    totalPages: Int,
    itemsPerPage: Int,
    currentPage: Int,
    nextPage: Option[Int],
    prevPage: Option[Int],
    links: Map[String, String]
  )

  implicit class HeadersOps(hs: Headers) {
    def toPagination: Pagination =
      Pagination(
        totalItems = getAsLong("X-Total"),
        totalPages = getAsInt("X-Total-Pages"),
        itemsPerPage = getAsInt("X-Per-Page"),
        currentPage = getAsInt("X-Page"),
        nextPage = get("X-Next-Page").map(_.toInt),
        prevPage = get("X-Prev-Page").map(_.toInt),
        links = get("Link").map(buildLinksMap).getOrElse(Map.empty)
      )

    private[this] def getAsInt(label: String): Int =
      get(label).map(_.toInt).getOrElse(0)

    private[this] def getAsLong(label: String): Long =
      get(label).map(_.toLong).getOrElse(0L)

    private[this] def get(label: String): Option[String] =
      hs.get(CaseInsensitiveString(label)).map(_.value).flatMap(toOption)

    private[this] def buildLinksMap(str: String): Map[String, String] =
      str.split(',')
        .map(_.split(';'))
        .map(_.map(_.trim))
        .map(pair => pair.last.split("\"")(1) -> pair.head.substring(1, pair.head.length - 1))
        .toMap

    private[this] def toOption(str: String): Option[String] =
      if (str == null || str.isEmpty) None else Option(str)
  }

  implicit def genericEntityDecoder[A: DecodeJson]: EntityDecoder[A] = jsonOf[A]

  implicit lazy val GithubUser: CodecJson[Github.User] =
    casecodec4(Github.User.apply, Github.User.unapply
    )("username", "avatar_url", "name", "email")

  implicit lazy val GithubOrg: CodecJson[Github.OrgKey] =
    casecodec2(Github.OrgKey.apply, Github.OrgKey.unapply)("id", "full_path")

  implicit val GithubContentsDecoder: DecodeJson[Github.Contents] =
    DecodeJson(c =>
      ((c --\ "content").as[String] |@|
        (c --\ "file_name").as[String] |@|
        (c --\ "size").as[Long]
        )(Github.Contents.apply)
    )

  implicit lazy val OrganizationDecoder: DecodeJson[Organization] =
    DecodeJson(c =>
      ((c --\ "id").as[Long] |@|
        (c --\ "name").as[Option[String]] |@|
        (c --\ "full_path").as[String] |@|
        (c --\ "avatar_url").as[Option[URI]]
        ) { case (id, name, login, avatar) => Organization(id, name, login, avatar) }
    )

  case class GitlabPermissions(projectAccess: Option[RepoAccess], groupAccess: Option[RepoAccess])

  implicit lazy val GitlabPermissionsDecoder: DecodeJson[GitlabPermissions] =
    DecodeJson(c => for {
        pa <- (c --\ "project_access").as[Option[RepoAccess]]
        ga <- (c --\ "group_access").as[Option[RepoAccess]]
      } yield GitlabPermissions(pa, ga)
    )

  implicit lazy val RepoDecoder: DecodeJson[Repo] =
    DecodeJson(c => for {
      z <- (c --\ "id").as[Long]
      y <- (c --\ "path_with_namespace").as[String]
      x <- Slug.fromString(y).map(DecodeResult.ok).valueOr(e => DecodeResult.fail(e.getMessage,c.history))
      w <- (c --\ "permissions").as[GitlabPermissions].map {
        case GitlabPermissions(Some(pa), _) => pa
        case GitlabPermissions(_, Some(ga)) => ga
        case _ => RepoAccess.Forbidden
      }
    } yield Repo(z, x, w))

  implicit lazy val RepoAccessDecoder: DecodeJson[Option[RepoAccess]] =
    DecodeJson(c =>
      (c --\ "access_level").as[Option[Int]].map(_.map(RepoAccess.fromInt))
    )

  implicit lazy val AccessTokenDecoder: DecodeJson[AccessToken] =
    DecodeJson(c => (c --\ "access_token").as[String]).map(AccessToken.apply(_))

  implicit lazy val AccessTokenEncoder: EncodeJson[AccessToken] =
    jencode1L((t: AccessToken) => t.value)("access_token")

  case class GitlabWebhook(
      id: Long,
      url: String,
      pushEvents: Boolean,
      tagPushEvents: Boolean,
      repositoryUpdateEvents: Boolean,
      issuesEvents: Boolean,
      mergeRequestEvents: Boolean,
      noteEvents: Boolean,
      pipelineEvents: Boolean,
      wikiPageEvents: Boolean,
      jobEvents: Boolean
  )

  implicit class GitlabWebhookOps(instance: GitlabWebhook) {
    def toGithubWebHook: Github.WebHook = {
      def eventsList: List[String] = {
        import instance._
        val buffer = new ListBuffer[String]()
        if (pushEvents)             buffer += "push_events"
        if (tagPushEvents)          buffer += "tag_push_events"
        if (repositoryUpdateEvents) buffer += "repository_update_events"
        if (issuesEvents)           buffer += "issues_events"
        if (mergeRequestEvents)     buffer += "merge_requests_events"
        if (noteEvents)             buffer += "note_events"
        if (pipelineEvents)         buffer += "pipeline_events"
        if (wikiPageEvents)         buffer += "wiki_page_events"
        if (jobEvents)              buffer += "job_events"
        buffer.toList
      }
      Github.WebHook(
        instance.id,
        instance.id.toString,
        eventsList,
        active = true,
        config = Map("url" -> instance.url, "content_type" -> "json")
      )
    }
  }

  implicit lazy val GitlabWebhookDecoder: DecodeJson[GitlabWebhook] =
    DecodeJson(c =>
      ((c --\ "id").as[Long] |@|
        (c --\ "url").as[String] |@|
        (c --\ "push_events").as[Boolean] |@|
        (c --\ "tag_push_events").as[Boolean] |@|
        (c --\ "repository_update_events").as[Boolean] |@|
        (c --\ "issues_events").as[Boolean] |@|
        (c --\ "merge_requests_events").as[Boolean] |@|
        (c --\ "note_events").as[Boolean] |@|
        (c --\ "pipeline_events").as[Boolean] |@|
        (c --\ "wiki_page_events").as[Boolean] |@|
        (c --\ "job_events").as[Boolean]
      )(GitlabWebhook.apply)
    )

  final case class Tag(
    name: String,
    releaseDescription: Option[String]
  )

  implicit class TagOps(tag: Tag) {
    def assets(baseUrl: String): Seq[Asset] =
      tag.releaseDescription.flatMap { desc =>
        Try {
          val pattern = """\[(.*)\]\((.*)\)""".r
          val pattern(assetName, assetRelUrl) = desc
          Github.Asset(0L, assetName, "", s"$baseUrl$assetRelUrl")
        }.toOption
      }.toSeq
  }

  implicit val GitlabTagDecoder: DecodeJson[Tag] =
    DecodeJson(z =>
      ((z --\ "name").as[String] |@|
        (z --\ "release" --\ "description").as[Option[String]]
      ) ((a, b) => Tag(a, b.map(_.trim)))
    )

  implicit lazy val UriToJson: EncodeJson[URI] =
    implicitly[EncodeJson[String]].contramap(_.toString)

  implicit lazy val JsonToUri: DecodeJson[URI] =
    implicitly[DecodeJson[String]].map(new URI(_))
}
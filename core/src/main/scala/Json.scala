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

import nelson.scheduler._

object Json {
  import argonaut._, Argonaut._
  import argonaut.DecodeResultCats._
  import cats.implicits._
  import org.http4s.Uri
  import org.http4s.argonaut._

  import Datacenter._
  import concurrent.duration._
  import health.HealthStatus

  implicit lazy val UriToJson: EncodeJson[URI] =
    implicitly[EncodeJson[String]].contramap(_.toString)

  implicit lazy val JsonToUri: DecodeJson[URI] =
    implicitly[DecodeJson[String]].map(new URI(_))

  implicit lazy val DurationEncoder: EncodeJson[Duration] =
    implicitly[EncodeJson[Long]].contramap(_.toMillis)

  implicit lazy val AccessTokenCodec: CodecJson[AccessToken] =
    casecodec1(AccessToken.apply, AccessToken.unapply)("access_token")

  implicit lazy val UserCodec: CodecJson[User] =
    casecodec5(User.apply, User.unapply)("login", "avatar_url", "name", "email", "organizations")

  implicit lazy val OrganizationCodec: CodecJson[Organization] =
    casecodec4(Organization.apply, Organization.unapply)("id", "name", "login", "avatar_url")

  implicit lazy val StackNameEncoder: EncodeJson[StackName] =
    implicitly[EncodeJson[String]].contramap(_.toString)

  implicit lazy val DeploymentEncoder: EncodeJson[Deployment] =
    EncodeJson { (d: Deployment) =>
      ("stack_name"  := d.stackName) ->:
      ("deployed_at" := d.deployTime) ->:
      ("workflow"    := d.workflow) ->:
      ("guid"        := d.guid) ->:
      ("unit"        := d.unit.name) ->:
      ("plan"        := d.plan) ->:
      ("resources"   := d.unit.resources) ->:
      jEmptyObject
    }

  implicit def tagEncoder[A, T](implicit ea: EncodeJson[A]): EncodeJson[A @@ T] = EncodeJson { a => Tag.unwrap[A, T](a).asJson }

  implicit val runningUnitEncoder: EncodeJson[RunningUnit] =
    EncodeJson((u: RunningUnit) =>
      ("name" := u.name) ->:
      ("status" := u.status) ->:
      jEmptyObject
    )

  implicit lazy val RoutingNodeEncoder: EncodeJson[routing.RoutingNode] =
    EncodeJson((rn: routing.RoutingNode) =>
      rn.node.fold(
        lb =>
          ("guid" := lb.guid) ->:
          ("stack_name" := lb.stackName.toString) ->:
          ("type" := "loadbalancer") ->:
          ("deployed_at" := lb.deployTime) ->:
          jEmptyObject,
        de =>
          ("guid" := de.guid) ->:
          ("stack_name" := de.stackName.toString) ->:
          ("type" := "unit") ->:
          ("deployed_at" := de.deployTime) ->:
          jEmptyObject
      )
    )

  implicit lazy val RoutePathRoutingNodeEncoder: EncodeJson[(routing.RoutePath, routing.RoutingNode)] =
    EncodeJson {
      case (rp: routing.RoutePath, rn: routing.RoutingNode) =>
        (("weight" := rp.weight) ->:
         ("port_name" := rp.portName) ->:
         jEmptyObject
        ).deepmerge(rn.asJson)
    }

  implicit lazy val DeploymentDeploymentEncoder: EncodeJson[DependencyEdge] =
    EncodeJson((de: DependencyEdge) =>
      ("from" := de._1) ->:
      ("to" := de._2) ->:
      jEmptyObject
    )

  // Used for auditing purposes
  implicit lazy val ThrowableEncoder: EncodeJson[Throwable] =
    EncodeJson((t: Throwable) =>
      ("msg" := t.getMessage) ->:
      jEmptyObject
    )

  implicit def NelsonErrorEncoder[A <: NelsonError]: EncodeJson[A] =
    EncodeJson((error: A) =>
      ("msg" := error.getMessage) ->:
      jEmptyObject
    )

  implicit lazy val OrganizationEncoder: EncodeJson[Organization] =
    EncodeJson((o: Organization) =>
      ("id" := o.id) ->:
      ("name" := o.name) ->:
      ("slug" := o.slug) ->:
      ("avatar" := o.avatar) ->:
      jEmptyObject
    )

  implicit lazy val OrganizationDecoder: DecodeJson[Organization] =
    DecodeJson(c =>
      ((c --\ "id").as[Long],
        (c --\ "name").as[Option[String]],
        (c --\ "login").as[String],
        (c --\ "avatar_url").as[URI]
      ).mapN(Organization.apply)
    )

  implicit lazy val HookEncoder: EncodeJson[Hook] =
    EncodeJson((h: Hook) =>
      ("id" := h.id) ->:
      ("is_active" := h.isActive) ->:
      jEmptyObject)

  implicit lazy val RepoAccessDecoder: DecodeJson[RepoAccess] =
    DecodeJson(c =>
      ((c --\ "push").as[Boolean],
        (c --\ "pull").as[Boolean],
        (c --\ "admin").as[Boolean]
      ).mapN(RepoAccess.fromBools)
    )

  implicit lazy val RepoDecoder: DecodeJson[Repo] =
    DecodeJson(c => for {
      z <- (c --\ "id").as[Long]
      y <- (c --\ "full_name").as[String]
      x <- Slug.fromString(y).map(DecodeResult.ok
            ).valueOr(e => DecodeResult.fail(e.getMessage,c.history))
      w <- (c --\ "permissions").as[RepoAccess]
    } yield Repo(z,x,w))

  implicit lazy val RepoEncoder: EncodeJson[Repo] =
    EncodeJson((r: Repo) =>
      ("id"         := r.id) ->:
      ("slug"       := r.slug.toString) ->:
      ("owner"      := r.slug.owner) ->:
      ("repository" := r.slug.repository) ->:
      ("access"     := r.access.toString) ->:
      ("hook"       := r.hook) ->:
      jEmptyObject)

  // never encode the whole session - only a non-private subset
  implicit lazy val SessionEncoder: EncodeJson[Session] =
    EncodeJson((s: Session) =>
      ("user" :=
        ("name" := s.user.name) ->:
        ("login" := s.user.login) ->:
        ("avatar" := s.user.avatar.toString) ->:
        ("organizations" := (s.user.toOrganization +: s.user.orgs)) ->:
        jEmptyObject
      ) ->:
      jEmptyObject
    )

  /**
   *{
   *  "id": 1,
   *  "url": "https://api.github.com/repos/octocat/Hello-World/hooks/1",
   *  "test_url": "https://api.github.com/repos/octocat/Hello-World/hooks/1/test",
   *  "ping_url": "https://api.github.com/repos/octocat/Hello-World/hooks/1/pings",
   *  "name": "web",
   *  "events": [
   *    "push",
   *    "pull_request"
   *  ],
   *  "active": true,
   *  "config": {
   *    "url": "http://example.com/webhook",
   *    "content_type": "json"
   *  },
   *  "updated_at": "2011-09-06T20:39:23Z",
   *  "created_at": "2011-09-06T17:26:27Z"
   *}
   */
  implicit lazy val GithubWebHookDecoder: DecodeJson[Github.WebHook] =
    DecodeJson(c =>
      ((c --\ "id").as[Long],
        (c --\ "name").as[String],
        (c --\ "events").as[List[String]],
        (c --\ "active").as[Boolean],
        (c --\ "config").as[Map[String,String]]
      ).mapN(Github.WebHook.apply)
    )

  /**
   * Supplied by Github to ensure that your webhook is active
   */
  implicit lazy val GithubPingEventDecoder: DecodeJson[Github.PingEvent] =
    DecodeJson(c => for {
      z <- (c --\ "zen").as[String]
    } yield Github.PingEvent(z))

  /**
   * a simple aggregate decoder that allows us to refer to all events
   * as a single inbound type; bit hacky but we're bridging the typed
   * and un-typed worlds here... *sigh*.
   *
   * TIM: this seems really hacky.
   */
  implicit lazy val GithubEventDecoder: DecodeJson[Github.Event] =
    ((GithubDeploymentEventInboundDecoder |||
     GithubReleaseEventDecoder: DecodeJson[Github.Event]) |||
     GithubPullRequestEventDecoder: DecodeJson[Github.Event]) |||
     GithubPingEventDecoder

  /*
   * {
   *   "deployment": {
   *     "url": "https://api.github.com/repos/timperrett/example/deployments/107241174",
   *     "id": 107241174,
   *     "node_id": "MDEwOkRlcGxveW1lbnQxMDcyNDExNzQ=",
   *     "sha": "fdb7da2ab3b2cd172e86c1af9adefa3523f6d65b",
   *     "ref": "fdb7da2ab3b2cd172e86c1af9adefa3523f6d65b",
   *     "task": "deploy",
   *     "payload": "{ \"foo\": true }",
   *     "original_environment": "production",
   *     "environment": "production",
   *     "description": null,
   *     "creator": {
   *      ....
   *     },
   *     "created_at": "2018-10-06T04:52:40Z",
   *     "updated_at": "2018-10-06T04:52:40Z",
   *     "statuses_url": "https://api.github.com/repos/timperrett/example/deployments/107241174/statuses",
   *     "repository_url": "https://api.github.com/repos/timperrett/example"
   *   },
   *   "repository": {
   *     "id": 140525376,
   *     "node_id": "MDEwOlJlcG9zaXRvcnkxNDA1MjUzNzY=",
   *     "name": "example",
   *     "full_name": "timperrett/example",
   *     ....
   *   },
   *   "sender": {
   *    ....
   *   }
   * }
  */
  implicit val GithubDeploymentEventDecoder: DecodeJson[Github.Deployment] =
    DecodeJson(z => for {
      a <- (z --\ "id").as[Long]
      c <- (z --\ "ref").as[String]
      s <- (z --\ "sha").as[String]
      d <- (z --\ "environment").as[String]
      e <- (z --\ "payload").as[String]
      g <- (z --\ "url").as[String]
    } yield {
      // NOTE(timperrett): this seems a little sketchy as we're invoking the
      // protobuf decoder right here in the JSON decoder, even thought we've
      // no idea if things might work here or not... we can do better.
      val bytes = java.util.Base64.getDecoder.decode(e)
      val unmarshalled = nelson.api.deployable.Deployables.parseFrom(bytes)
      val converted = unmarshalled.deployables.toList.map { a =>
        val v = a.version.semver.get
        Manifest.Deployable(
          name = a.unitName,
          version = Version(v.major, v.minor, v.patch),
          output = Manifest.Deployable.Container(a.kind.container.get.image)
        )
      }
      Github.Deployment(
        id = a,
        ref = Github.Reference.fromString(c,Option(s)),
        environment = d,
        deployables = converted,
        url = g
      )
    })

  // TODO(timperrett): what do we do here about encoding the assets that
  // are shipped to us as proto format?
  implicit val GithubDeploymentEncoder: EncodeJson[Github.Deployment] =
    EncodeJson((d: Github.Deployment) =>
      ("id" := d.id) ->:
      ("url" := d.url) ->:
      ("ref" := d.ref.toString) ->:
      jEmptyObject
    )

  implicit val GithubDeploymentEventInboundDecoder: DecodeJson[Github.DeploymentEvent] =
    DecodeJson(z => (for {
      a <- (z --\ "deployment").as[Github.Deployment]
      x <- (z --\ "repository" --\ "full_name").as[String]
      b <- Slug.fromString(x).map(DecodeResult.ok
           ).valueOr(e => DecodeResult.fail(e.getMessage,z.history))
      f <- (z --\ "repository" --\ "id").as[Long]
    } yield Github.DeploymentEvent(slug = b, repositoryId = f, deployment = a) ))

  /**
   * {
   *   "name": "web",
   *   "active": true,
   *   "events": [
   *     "push",
   *     "pull_request"
   *   ],
   *   "config": {
   *     "url": "http://example.com/webhook",
   *     "content_type": "json"
   *   }
   * }
   */
  implicit lazy val GithubWebHookEncoder: EncodeJson[Github.WebHook] =
    EncodeJson((w: Github.WebHook) =>
      ("name"   := w.name) ->:
      ("events" := w.events) ->:
      ("active" := w.active) ->:
      ("config" := w.config) ->:
      jEmptyObject
    )

  /**
   * {
   *   "content": "...",
   *   "name": ".travis.yml",
   *   "size": 633
   * }
   */
  implicit val GithubContentsDecoder: DecodeJson[Github.Contents] =
    DecodeJson(c =>
      ((c --\ "content").as[String],
        (c --\ "name").as[String],
        (c --\ "size").as[Long]
      ).mapN(Github.Contents.apply)
    )

  /**
   * {
   *   "assets": [
   *     {
   *       "browser_download_url": "https://github.example.com/tim/howdy/releases/download/0.13.17/example-howdy.deployable.yml",
   *       "content_type": "application/yaml; charset=UTF-8",
   *       "created_at": "2016-02-11T21:31:47Z",
   *       "download_count": 1,
   *       "id": 119,
   *       "label": "",
   *       "name": "example-howdy.deployable.yml",
   *       "size": 206,
   *       "state": "uploaded",
   *       "updated_at": "2016-02-11T21:31:48Z",
   *       "uploader": {
   *         "avatar_url": "https://github.example.com/avatars/u/703?",
   *         "events_url": "https://github.example.com/api/v3/users/travis/events{/privacy}",
   *         "followers_url": "https://github.example.com/api/v3/users/travis/followers",
   *         "following_url": "https://github.example.com/api/v3/users/travis/following{/other_user}",
   *         "gists_url": "https://github.example.com/api/v3/users/travis/gists{/gist_id}",
   *         "gravatar_id": "",
   *         "html_url": "https://github.example.com/travis",
   *         "id": 703,
   *         "login": "travis",
   *         "organizations_url": "https://github.example.com/api/v3/users/travis/orgs",
   *         "received_events_url": "https://github.example.com/api/v3/users/travis/received_events",
   *         "repos_url": "https://github.example.com/api/v3/users/travis/repos",
   *         "site_admin": false,
   *         "starred_url": "https://github.example.com/api/v3/users/travis/starred{/owner}{/repo}",
   *         "subscriptions_url": "https://github.example.com/api/v3/users/travis/subscriptions",
   *         "type": "User",
   *         "url": "https://github.example.com/api/v3/users/travis"
   *       },
   *       "url": "https://github.example.com/api/v3/repos/tim/howdy/releases/assets/119"
   *     }
   *   ],
   *   "assets_url": "https://github.example.com/api/v3/repos/tim/howdy/releases/250/assets",
   *   "author": {
   *     "avatar_url": "https://github.example.com/avatars/u/703?",
   *     "events_url": "https://github.example.com/api/v3/users/travis/events{/privacy}",
   *     "followers_url": "https://github.example.com/api/v3/users/travis/followers",
   *     "following_url": "https://github.example.com/api/v3/users/travis/following{/other_user}",
   *     "gists_url": "https://github.example.com/api/v3/users/travis/gists{/gist_id}",
   *     "gravatar_id": "",
   *     "html_url": "https://github.example.com/travis",
   *     "id": 703,
   *     "login": "travis",
   *     "organizations_url": "https://github.example.com/api/v3/users/travis/orgs",
   *     "received_events_url": "https://github.example.com/api/v3/users/travis/received_events",
   *     "repos_url": "https://github.example.com/api/v3/users/travis/repos",
   *     "site_admin": false,
   *     "starred_url": "https://github.example.com/api/v3/users/travis/starred{/owner}{/repo}",
   *     "subscriptions_url": "https://github.example.com/api/v3/users/travis/subscriptions",
   *     "type": "User",
   *     "url": "https://github.example.com/api/v3/users/travis"
   *   },
   *   "body": "",
   *   "created_at": "2016-02-11T21:30:05Z",
   *   "draft": false,
   *   "html_url": "https://github.example.com/tim/howdy/releases/tag/0.13.17",
   *   "id": 250,
   *   "name": "Inner Elemental",
   *   "prerelease": false,
   *   "published_at": "2016-02-11T21:31:47Z",
   *   "tag_name": "0.13.17",
   *   "tarball_url": "https://github.example.com/api/v3/repos/tim/howdy/tarball/0.13.17",
   *   "target_commitish": "master",
   *   "upload_url": "https://github.example.com/api/uploads/repos/tim/howdy/releases/250/assets{?name,label}",
   *   "url": "https://github.example.com/api/v3/repos/tim/howdy/releases/250",
   *   "zipball_url": "https://github.example.com/api/v3/repos/tim/howdy/zipball/0.13.17"
   * }
   */
  implicit val GithubReleaseDecoder: DecodeJson[Github.Release] =
    DecodeJson(z =>
      ((z --\ "id").as[Long],
        (z --\ "url").as[String],
        (z --\ "html_url").as[String],
        (z --\ "assets").as[List[Github.Asset]],
        (z --\ "tag_name").as[String]
        ).mapN ((a, b, c, d, e) =>
        Github.Release(
          id = a,
          url = b,
          htmlUrl = c,
          assets = d,
          tagName = e
        )
      )
    )

  implicit val GithubReleaseEncoder: EncodeJson[Github.Release] =
    EncodeJson((release: Github.Release) =>
      ("id" := release.id) ->:
      ("url" := release.url) ->:
      ("html_url" := release.htmlUrl) ->:
      ("assets" := release.assets) ->:
      ("tag_name" := release.tagName) ->:
      jEmptyObject
    )

  implicit val GithubAssetsEncoder: EncodeJson[Github.Asset] =
    EncodeJson((asset: Github.Asset) =>
      ("id" := asset.id) ->:
      ("name" := asset.name) ->:
      ("url" := asset.url.toString) ->:
      ("content" := asset.content) ->:
      jEmptyObject
    )

  /**
   *{
   *  "action": "published",
   *  "release": {
   *    "url": "https://api.github.com/repos/baxterthehacker/public-repo/releases/1261438",
   *    "assets_url": "https://api.github.com/repos/baxterthehacker/public-repo/releases/1261438/assets",
   *    "upload_url": "https://uploads.github.com/repos/baxterthehacker/public-repo/releases/1261438/assets{?name}",
   *    "html_url": "https://github.com/baxterthehacker/public-repo/releases/tag/0.0.1",
   *    "id": 1261438,
   *    "tag_name": "0.0.1",
   *    "target_commitish": "master",
   *    "name": null,
   *    "draft": false,
   *    "author": {
   *      "login": "baxterthehacker",
   *      "id": 6752317,
   *      "avatar_url": "https://avatars.githubusercontent.com/u/6752317?v=3",
   *      "gravatar_id": "",
   *      "url": "https://api.github.com/users/baxterthehacker",
   *      "html_url": "https://github.com/baxterthehacker",
   *      ...
   *      "type": "User",
   *      "site_admin": false
   *    },
   *    "prerelease": false,
   *    "created_at": "2015-05-05T23:40:12Z",
   *    "published_at": "2015-05-05T23:40:38Z",
   *    "assets": [
   *
   *    ],
   *    "tarball_url": "https://api.github.com/repos/baxterthehacker/public-repo/tarball/0.0.1",
   *    "zipball_url": "https://api.github.com/repos/baxterthehacker/public-repo/zipball/0.0.1",
   *    "body": null
   *  },
   *  "repository": {
   *    "id": 35129377,
   *    "name": "public-repo",
   *    "full_name": "baxterthehacker/public-repo",
   *    "owner": {
   *      "login": "baxterthehacker",
   *      "id": 6752317,
   *      "avatar_url": "https://avatars.githubusercontent.com/u/6752317?v=3",
   *      "gravatar_id": "",
   *      "url": "https://api.github.com/users/baxterthehacker",
   *      "html_url": "https://github.com/baxterthehacker",
   *      ...
   *      "type": "User",
   *      "site_admin": false
   *    },
   *    "private": false,
   *    "html_url": "https://github.com/baxterthehacker/public-repo",
   *    "description": "",
   *    "fork": false,
   *    ...
   *    "created_at": "2015-05-05T23:40:12Z",
   *    "updated_at": "2015-05-05T23:40:30Z",
   *    "pushed_at": "2015-05-05T23:40:38Z",
   *    "git_url": "git://github.com/baxterthehacker/public-repo.git",
   *    "ssh_url": "git@github.com:baxterthehacker/public-repo.git",
   *    "clone_url": "https://github.com/baxterthehacker/public-repo.git",
   *    "svn_url": "https://github.com/baxterthehacker/public-repo",
   *    "homepage": null,
   *    "size": 0,
   *    "stargazers_count": 0,
   *    "watchers_count": 0,
   *    "language": null,
   *    "has_issues": true,
   *    "has_downloads": true,
   *    "has_wiki": true,
   *    "has_pages": true,
   *    "forks_count": 0,
   *    "mirror_url": null,
   *    "open_issues_count": 2,
   *    "forks": 0,
   *    "open_issues": 2,
   *    "watchers": 0,
   *    "default_branch": "master"
   *  },
   *  "sender": {
   *    "login": "baxterthehacker",
   *    "id": 6752317,
   *    "avatar_url": "https://avatars.githubusercontent.com/u/6752317?v=3",
   *    "gravatar_id": "",
   *    "url": "https://api.github.com/users/baxterthehacker",
   *    "html_url": "https://github.com/baxterthehacker",
   *    ...
   *    "type": "User",
   *    "site_admin": false
   *  }
   *}
   */
  implicit val GithubReleaseEventDecoder: DecodeJson[Github.ReleaseEvent] =
    DecodeJson(z => for {
      a <- (z --\ "release" --\ "id").as[Long]
      d <- (z --\ "repository" --\ "full_name").as[String]
      x <- Slug.fromString(d).map(DecodeResult.ok
           ).valueOr(e => DecodeResult.fail(e.getMessage,z.history))
      e <- (z --\ "repository" --\ "id").as[Long]
    } yield {
      Github.ReleaseEvent(
        id = a,
        slug = x,
        repositoryId = e
      )
    })

  /**
   * {
   *   "url": "https://github.example.com/api/v3/repos/example/howdy/releases/assets/1",
   *   "id": 1,
   *   "name": "example.yml",
   *   "label": "",
   *   "uploader": {
   *     ...
   *   },
   *   "content_type": "application/yaml",
   *   "state": "uploaded",
   *   "size": 36,
   *   "download_count": 0,
   *   "created_at": "2015-12-18T00:27:46Z",
   *   "updated_at": "2015-12-18T00:27:46Z",
   *   "browser_download_url": "https://github.example.com/example/howdy/releases/download/3.0.0/example.yml"
   * }
   */
  implicit lazy val AssetDecoder: DecodeJson[Github.Asset] =
    DecodeJson(c =>
      ((c --\ "id").as[Long],
        (c --\ "name").as[String],
        (c --\ "url").as[Uri]
      ).mapN((x,y,z) => Github.Asset(x,y,z))
    )

  implicit val GithubPullRequestEventDecoder: DecodeJson[Github.PullRequestEvent] =
    DecodeJson(z => for {
      a <- (z --\ "number").as[Long]
      b <- (z --\ "pull_request" --\ "url").as[String]
      d <- (z --\ "repository" --\ "full_name").as[String]
      x <- Slug.fromString(d).map(DecodeResult.ok
           ).valueOr(e => DecodeResult.fail(e.getMessage,z.history))
    } yield {
      Github.PullRequestEvent(
        id = a,
        url = b,
        slug = x
      )
    })

  implicit lazy val GithubOrg: CodecJson[Github.OrgKey] =
    casecodec2(Github.OrgKey.apply, Github.OrgKey.unapply)("id", "login")

  implicit lazy val GithubUser: CodecJson[Github.User] =
    casecodec4(Github.User.apply, Github.User.unapply
      )("login", "avatar_url", "name", "email")

  implicit val timestampCodec: CodecJson[java.time.Instant] = CodecJson(
    (i: java.time.Instant) => i.toEpochMilli.asJson,
    c => for {
      instant <- (c --\ "timestamp").as[Long]
    } yield java.time.Instant.ofEpochMilli(instant)
  )

  implicit val auditLogEncoder: CodecJson[audit.AuditLog] =
    casecodec7(audit.AuditLog.apply, audit.AuditLog.unapply)("id", "timestamp", "releaseId", "event", "category", "action", "login")

  implicit val manualDeployEncoder: CodecJson[Datacenter.ManualDeployment] =
    casecodec7(Datacenter.ManualDeployment.apply, Datacenter.ManualDeployment.unapply)(
      "datacenter", "namespace", "service_type", "version", "hash", "description", "port"
    )

  implicit val NamspaceRoutingGraphEncoder: EncodeJson[(Namespace,routing.RoutingGraph)] =
    EncodeJson { (t: (Namespace, routing.RoutingGraph)) =>
      val (n,g) = t
      ("name" := n.name.asString) ->:
      ("graph" := g.nodes.map(node =>
        ("name" := node.stackName.toString) ->:
        ("dependencies" := g.outs(node).map(_._2.stackName.toString)) ->:
        jEmptyObject)
      ) ->: jEmptyObject
    }

  implicit lazy val DeploymentSummaryEncoder: EncodeJson[scheduler.DeploymentSummary] =
    EncodeJson((ds: scheduler.DeploymentSummary) =>
      ("running"   := ds.running) ->:
      ("pending"   := ds.pending) ->:
      ("completed" := ds.completed) ->:
      ("failed"    := ds.failed) ->:
      jEmptyObject
    )

  implicit val HealthCheckEncoder: EncodeJson[health.HealthCheck] =
    EncodeJson[health.HealthCheck] { hs => jString(health.HealthCheck.toString(hs)) }

  implicit lazy val HealthStatusEncoder: EncodeJson[HealthStatus] =
    EncodeJson((h: HealthStatus) =>
      ("name"    := h.details.getOrElse("unspecified")) ->:
      ("status"  := h.status) ->:
      ("node"    := h.node) ->:
      ("check_id" := h.id) ->:
      jEmptyObject
    )

  implicit lazy val RuntimeSummaryEncoder: EncodeJson[Nelson.RuntimeSummary] =
    EncodeJson((rs: Nelson.RuntimeSummary) =>
      ("scheduler" := rs.deployment) ->:
      ("consul_health" := rs.health) ->:
      ("current_status" := rs.currentStatus.toString) ->:
      ("expires_at" := rs.expiresAt) ->:
      jEmptyObject
    )

  implicit val NamespaceNameDecoder: DecodeJson[NamespaceName] =
    DecodeJson.optionDecoder(_.string.flatMap(s =>
      NamespaceName.fromString(s).toOption), "NamespaceName")

  final case class NamespaceNameJson(
    namespace: NamespaceName
  )

  implicit val NamespaceNameJsonDecoder: DecodeJson[NamespaceNameJson] =
    DecodeJson[NamespaceNameJson](c =>
      for {
        ns <- (c --\ "namespace").as[NamespaceName]
      } yield NamespaceNameJson(ns)
    )

  implicit lazy val CommitUnitDecode: DecodeJson[Nelson.CommitUnit] =
    DecodeJson(c => for {
      u <- (c --\ "unit").as[String]
      v <- (c --\ "version").as[String]
      t <- (c --\ "target").as[NamespaceName]
      vv <- Version.fromString(v).map(DecodeResult.ok)
              .getOrElse(DecodeResult.fail(s"unable to parse $v into a version", c.history))
    } yield Nelson.CommitUnit(u,vv,t))

  implicit lazy val TrafficShiftEncoder: EncodeJson[Datacenter.TrafficShift] =
    EncodeJson((ts: Datacenter.TrafficShift) =>
      ("from" := ts.from) ->:
      ("to" := ts.to) ->:
      ("start" := ts.start) ->:
      ("end" := ts.end) ->:
      ("reverse" := ts.reverse) ->:
      ("policy" := ts.policy.ref) ->:
      jEmptyObject
    )

  /*
   * Encode a map as a transposed JSON list of maps, such that each map has (klabel: key) and (vlabel: value) entries.
   */
  def encodeTransposeMap[K, V](klabel: Symbol, vlabel: Symbol)(implicit ek: EncodeJson[K], ev: EncodeJson[V]): EncodeJson[Map[K, V]] = EncodeJson { m =>
    m.toList.map { case (k, v) =>
      (vlabel.name := v.asJson) ->:
      (klabel.name := k.asJson) ->:
      jEmptyObject
    }.asJson
  }
}

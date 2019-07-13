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
package plans

import cats.effect.IO
import fs2.io
import java.net.URLConnection
import java.nio.file.Paths
import org.http4s._
import org.http4s.headers.{`Content-Type`, Location}
import org.http4s.dsl.io._
import org.http4s.argonaut._
import _root_.argonaut._, Argonaut._
import scala.xml.NodeSeq

final case class UI(config: NelsonConfig) extends Default {
  import nelson.Json._
  import UI._

  val service = HttpService[IO] {
    case GET -> Root & IsAuthenticated(_) =>
      IO.pure(serveStaticFile(path = "index.html"))

    case GET -> Root / "login" & IsAuthenticated(_) =>
      Found(Location(uri("/")))

    case GET -> Root / "login" & NotAuthenticated() =>
      IO.pure(serveStaticFile(path = "login.html"))

    // only used by the javascript ui as a convenience to not have
    // to re-request this common data on every page load. its all
    // public info anyway, and we save to local storage (html5).
    case GET -> Root / "session" & IsAuthenticated(session) =>
      Ok(session.asJson)

    // adding this so that when not logged in, we dont return an error
    case GET -> Root / "session" & NotAuthenticated() =>
      Ok(jEmptyObject)

    ////////////////////// PROFILES //////////////////////

    case GET -> Root / "profile" & IsAuthenticated(s) =>
      Uri.fromString(s"/profile/${s.user.login}").fold(
        e => InternalServerError(s"Bad redirect: ${e.details}"),
        uri => Found(Location(uri))
      )

    case GET -> Root / "profile" / _ & IsAuthenticated(_) =>
      IO.pure(serveStaticFile(path = "index.html"))

    case GET -> Root / "deployments" & IsAuthenticated(_) =>
      IO.pure(serveStaticFile(path = "index.html"))

    case GET -> Root / "deployments" / _ & IsAuthenticated(_) =>
      IO.pure(serveStaticFile(path = "index.html"))

    case GET -> Root / "datacenters" & IsAuthenticated(_) =>
      IO.pure(serveStaticFile(path = "index.html"))

    case GET -> Root / "datacenters" / _ & IsAuthenticated(_) =>
      IO.pure(serveStaticFile(path = "index.html"))

    case GET -> Root / "dashboard" & IsAuthenticated(_) =>
      IO.pure(serveStaticFile(path = "index.html"))

    case GET -> Root / "dashboard" / _ & IsAuthenticated(_) =>
      IO.pure(serveStaticFile(path = "index.html"))

    case GET -> Root / "audit" / _ & IsAuthenticated(_) =>
      IO.pure(serveStaticFile(path = "index.html"))

    case GET -> Root / "audit" & IsAuthenticated(_) =>
      IO.pure(serveStaticFile(path = "index.html"))

    ////////////////////// REPOSITORIES //////////////////////

    case GET -> Root / owner / repo / unit ~ "svg" =>
      Nelson.getStatusOfReleaseUnit(Slug(owner, repo), unit)(config)
        .flatMap { s =>
          Ok(badge(s.getOrElse(DeploymentStatus.Unknown)).toString).map(
            _.withContentType(`Content-Type`(MediaType.`image/svg+xml`)))
        }

    case NotAuthenticated() =>
      Found(Location(uri("/login")))
  }

  /*
   * if the server config has been overriden to provide an fs path
   * then we'll try to serve the file from that location, otherwise,
   * we'll use the built-in nelson-ui that comes from the classpath dependency.
   */
  private def serveStaticFile(path: String): Response[IO] =
    config.ui.filePath
      .map(_.withTrailingSlash + path)
      .map(fileFromFilesystem)
      .getOrElse(fileFromClasspath(path = path))

  private def fileFromClasspath(prefix: String = "nelson/www", path: String): Response[IO] = {
    val resp = Response(body = io.readInputStream(IO(getClass.getClassLoader.getResourceAsStream(s"$prefix/$path")), 4096))

    Option(URLConnection.guessContentTypeFromName(path)).fold(resp) { ct =>
      resp.putHeaders(Header("Content-Type", ct))
    }
  }

  private def fileFromFilesystem(path: String): Response[IO] = {
    val p = Paths.get(path)
    val resp = Response(body = io.file.readAll[IO](p, 4096))
    Option(URLConnection.guessContentTypeFromName(path)).fold(resp) { ct =>
      resp.putHeaders(Header("Content-Type", ct))
    }
  }
}

object UI {
  final case class Sheild(
    left: Int,
    right: Int,
    rightx: Int,
    colour: String
  ){
    val total = left + right
  }

  // later on needs to take a deployment status and pick colours etc
  // based on that status.
  // props to http://shields.io/ for standardising these svgs!
  def badge(status: DeploymentStatus): NodeSeq = {
    import DeploymentStatus._
    val sheild = status match {
      case Pending | Deploying | Warming => Sheild(44, 60, 73, "#007ec6") // blue
      case Failed     => Sheild(44, 46, 66, "#e05d44") // red
      case Deprecated => Sheild(44, 68, 78, "#8f6f59")
      case Terminated => Sheild(44, 68, 78, "#e05d44") //red
      case Ready      => Sheild(44, 46, 66, "#44cc11")  // green
      case Garbage    => Sheild(44, 56, 72, "#8f6f59") // brown
      case Unknown    => Sheild(44, 60, 73, "#9f9f9f") // grey
    }

    <svg xmlns="http://www.w3.org/2000/svg" width={ sheild.total.toString } height="20">
      <linearGradient id="b" x2="0" y2="100%">
        <stop offset="0" stop-color="#bbb" stop-opacity=".1" />
        <stop offset="1" stop-opacity=".1" />
      </linearGradient>
      <mask id="a">
        <rect width={ sheild.total.toString } height="20" rx="3" fill="#fff" />
      </mask>
      <g mask="url(#a)">
        <path fill="#555" d={ "M0 0h" + sheild.left + "v20H0z" } />
        <path fill={ sheild.colour } d={ "M" + sheild.left + " 0h" + sheild.right + "v20H" + sheild.left + "z" } />
        <path fill="url(#b)" d={ "M0 0h" + sheild.total + "v20H0z" } />
      </g>
      <g fill="#fff" text-anchor="middle" font-family="DejaVu Sans,Verdana,Geneva,sans-serif" font-size="11">
        <text x="22" y="15" fill="#010101" fill-opacity=".3">nelson</text>
        <text x="22" y="14">nelson</text>
        <text x={ sheild.rightx.toString } y="15" fill="#010101" fill-opacity=".3">{ status }</text>
        <text x={ sheild.rightx.toString } y="14">{ status }</text>
      </g>
    </svg>
  }
}

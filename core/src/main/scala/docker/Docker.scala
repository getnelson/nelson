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
package docker

import cats.~>
import cats.effect.IO

import java.util.concurrent.ScheduledExecutorService

import journal.Logger

import scala.collection.mutable.MutableList
import scala.concurrent.ExecutionContext

/**
 * The fugiest docker client around.
 * Extreamly crude. Barely works.
 */
class Docker(cfg: DockerConfig, scheduler: ScheduledExecutorService, ec: ExecutionContext) extends (DockerOp ~> IO) {
  import Docker._
  import sys.process._

  private[this] val log = Logger[Docker]

  def apply[A](op: DockerOp[A]): IO[A] =
    op match {
      case DockerOp.Tag(i, r)     => tag(i, i.to(r)).retryExponentially()(scheduler, ec)
      case DockerOp.Push(i)       => push(i).retryExponentially()(scheduler, ec)
      case DockerOp.Pull(i)       => pull(i).retryExponentially()(scheduler, ec)
      case DockerOp.Extract(unit) => extract(unit)
    }

  def extract(unit: Manifest.UnitDef): IO[Image] =
    unit.deployable.map(_.output).map {
      case Manifest.Deployable.Container(name) =>
        Docker.Image.fromString(name).fold(
          a => IO.raiseError(FailedDockerExtraction(s"$name could not be converted to Docker.Image due to '$a'")),
          b => IO.pure(b)
        )
    }.getOrElse(IO.raiseError(new FailedDockerExtraction(s"input unit '${unit.name}' has no specified deployable.")))


  //////////////////////// TAG ////////////////////////

  def tag(src: Image, tagged: Image): IO[(Int, Image)] =
    exec(s"docker -H ${cfg.connection} tag ${src.toString} ${tagged.toString}").map(r => (r._1, tagged))

  //////////////////////// PUSH ////////////////////////

  def push(image: Image): IO[(Int, List[Push.Output])] =
    exec(pushCommand(image)).map(r => (r._1, r._2.flatMap(Push.parseLine)))

  def pushCommand[A](image: Image): String =
    s"docker -H ${cfg.connection} push ${image.toString}"

  //////////////////////// PULL ////////////////////////

  def pull(image: Image): IO[(Int, List[Pull.Output])] =
    exec(pullCommand(image)).map(r => (r._1, r._2.flatMap(Pull.parseLine)))

  def pullCommand(image: Image): String =
    s"docker -H ${cfg.connection} pull ${image.toString}"

  //////////////////////// INTERNALS ////////////////////////

  protected def exec(command: String): IO[(Int, List[String])] =
    IO {
      var err: MutableList[String] = MutableList()
      var win: MutableList[String] = MutableList()
      log.debug(s"executing: $command")
      val exit = Process(command).!(ProcessLogger(l => win = win :+ l, l => err = err :+ l))
      (exit, (win ++ err).toList)
    }
}

object Docker {
  type RegistryURI = String
  type Name = String
  type Tag = String
  type Digest = String

  final case class Image(
    name: Name,
    tag: Option[Tag],
    digest: Option[Digest] = None
  ){
    /**
     * If the name of the image has the registry location encoded
     * into it, then return it. Otherwise, if shorthand is used
     * assume we're dealing with a public docker image and prepend
     * index.docker.io and yield. Anything else should be invalid
     * so we'll just return None there.
     */
    val registry: Option[RegistryURI] =
      name.split('/') match {
        case a@Array(_, _, _) => Option(a.take(2).mkString("/"))
        case Array(user, _)   => Option(s"index.docker.io/$user")
        case _                => None
      }

    /**
     * This is a convenience function that allows you to
     * easily retarget a given docker image to a different
     * registry location (e.g. from index.docker.com to your
     * internal private registry).
     */
    def to(uri: RegistryURI): Image = {
      val without = registry.map(r =>
        this.name.replace(s"$r/", "")
          ).getOrElse(name)

      val next =
        if(uri.endsWith("/"))
          uri.substring(0, uri.length - 1)
        else uri

      this.copy(name = s"$next/$without")
    }

    override def toString: String =
      name + tag.map(":"+_).getOrElse("")
  }
  object Image {
    /* because this annoys me every time - we mostly dont have diget */
    def apply(name: Name, tag: Tag): Image =
      Image(name, Option(tag), None)

    /**
     * crude parser for docker image names. Handles the following cases:
     * yourreg.com/namespace/foo
     * yourreg.com/namespace/foo:sometag
     * yourreg.com/namespace/foo@f4sdsf4f
     * yourreg.com/namespace/foo:latest@sdff44f4f
     */
    def fromString(s: String): Either[Throwable, Image] =
      s.split(':') match {
        case Array(name) =>
          name.split('@') match {
            case Array(_) => Right(Image(name, None, None))
            case Array(n,d) => Right(Image(n, None, Option(d)))
          }
        case Array(name, tag) =>
          tag.split('@') match {
            case Array(_) => Right(Image(name, Option(tag), None))
            case Array(t,digest) => Right(Image(name, Option(t), Option(digest)))
          }
        case _ => Left(InvalidDockerImage(s))
      }
  }

  object Push {
    sealed trait Output { def asString: String }
    final case class Progress(hash: String, message: String) extends Output {
      def asString = s"$hash $message"
    }
    final case class Result(digest: String, size: Long) extends Output {
      def asString = s"$digest $size"
    }
    final case class Error(message: String, context: String) extends Output {
      def asString = s"$message $context"
    }

    val resultR = "^latest: digest: ([a-z0-9]*:[a-z0-9]*) size: ([0-9]*)".r
    val progressR = "(^[a-z0-9]*): (.*)".r
    val errorR = """(^[a-zA-Z\s]*): (.*)""".r

    // zomg this is hacky; wish there was a more stable api.
    def parseLine(line: String): Option[Output] =
      line match {
        case resultR(digest,size)    => Some(Result(digest, size.toLong))
        case progressR(hash,message) => Some(Progress(hash, message))
        case errorR(message,context) => Some(Error(message, context))
        case _                       => None
      }
  }

  object Pull {
    sealed trait Output { def asString: String }
    final case class Info(about: String, details: String) extends Output {
      def asString = s"$about $details"
    }
    final case class Progress(id: String, message: String) extends Output {
      def asString = s"$id $message"
    }
    final case class Error(message: String) extends Output {
      def asString = message
    }

    val infoR = "^(?!Error)([a-zA-Z0-9]*): (.*)".r
    val errorR = "Error: (.*)".r
    val progressR = "(^[a-z0-9]*): (.*)".r

    // zomg this is hacky
    def parseLine(line: String): Option[Output] = {
      line match {
        case progressR(a,b) => Some(Progress(a,b))
        case errorR(a)      => Some(Error(a))
        case infoR(a, b)    => Some(Info(a, b))
        case _              => None
      }
    }
  }
}

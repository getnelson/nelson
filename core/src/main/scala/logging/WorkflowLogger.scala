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
package logging

import cats.~>
import cats.effect.IO
import cats.syntax.apply._

import fs2.{Sink, Stream}
import fs2.async.mutable.Queue
import fs2.{io, text}

import journal.Logger

import java.nio.file.{Path,Files,StandardOpenOption}
import java.time.Instant

/*
 * The WorkflowLogger is a process that the logs workflow deployment progress
 * information to a namespaced file (based on deployment id). The main
 * purpose of this file is to provide the frontend with insight into
 * what is happening with this deployment, similar to what travis provides.
 */
class WorkflowLogger(queue: Queue[IO, (ID, String)], base: Path) extends (LoggingOp ~> IO) {

  private val logger = Logger[this.type]

  import LoggingOp._
  def apply[A](op: LoggingOp[A]) =
    op match {
      case Debug(msg: String) =>
        IO(logger.debug(msg))
      case Info(msg: String) =>
        IO(logger.info(msg))
      case LogToFile(id, msg) =>
        log(id,msg)
    }

  def setup(): IO[Unit] =
    IO {
      if (!exists(base)){
        logger.info(s"creating workflow log base directory at $base")
        Files.createDirectories(base)
        ()
      }
    }

  def log(id: ID, line: String): IO[Unit] =
    queue.enqueue1((id, line))

  def process: Stream[IO, Unit] =
    queue.dequeue.to(appendToFile)

  def read(id: ID, offset: Int): IO[List[String]] = {
    for {
      file <- getPath(id)
      lines <- io.file.readAll[IO](file, 4096) // 4096 is a bit arbitrary..
                 .through(text.utf8Decode)
                 .through(text.lines)
                 .drop(offset.toLong)
                 .compile
                 .toList // yolo
    } yield lines
  }

  private def appendToFile: Sink[IO,(ID,String)] =
    Sink { case (id, line) =>
      for {
        path <- getPath(id)
        _    <- createFile(path) *> append(path, line)
      } yield ()
    }

  private def append(path: Path, line: String): IO[Unit] =
    IO {
      val withNewline = if (!line.endsWith("\n")) s"$line\n" else line
      val withTimestamp = s"$NOW: $withNewline"
      // This will open and close the file every time a line is added.
      // Ideally this would be a stream that we could write to and then
      // close when finished. This is to just get things going.
      Files.write(path, withTimestamp.getBytes(), StandardOpenOption.APPEND)
      ()
    }

  private def getPath(id: ID): IO[Path] =
    IO(base.resolve(s"${id}.log"))

  private def createFile(path: Path): IO[Unit] =
    IO {
      if (!exists(path))
        Files.createFile(path)
        ()
    }

  private def exists(path: Path): Boolean =
    Files.exists(path)

  private def NOW = Instant.now
}

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

import journal.Logger
import scalaz.concurrent.Task
import scalaz._, Scalaz._
import scalaz.stream.{Process,Sink,sink}
import scalaz.stream.async.mutable.{Queue}
import java.nio.file.{Path,Files,StandardOpenOption}
import java.time.Instant


/*
 * The WorkflowLogger is a process that the logs workflow deployment progress
 * information to a namespaced file (based on deployment id). The main
 * purpose of this file is to provide the frontend with insight into
 * what is happening with this deployment, similar to what travis provides.
 */
class WorkflowLogger(queue: Queue[(ID, String)], base: Path) extends (LoggingOp ~> Task) {

  private val logger = Logger[this.type]

  import LoggingOp._
  def apply[A](op: LoggingOp[A]) =
    op match {
      case Debug(msg: String) =>
        Task.delay(logger.debug(msg))
      case Info(msg: String) =>
        Task.delay(logger.info(msg))
      case LogToFile(id, msg) =>
        log(id,msg)
    }

  def setup(): Task[Unit] =
    Task.delay {
      if (!exists(base)){
        logger.info(s"creating workflow log base directory at $base")
        Files.createDirectories(base)
        ()
      }
    }

  def log(id: ID, line: String): Task[Unit] =
    queue.enqueueOne((id, line))

  def process: Process[Task, Unit] =
    queue.dequeue to appendToFile

  def read(id: ID, offset: Int): Task[List[String]] = {
    for {
      file <- getPath(id)
      lines <- scalaz.stream.io.linesR(file.toFile.getAbsolutePath)
                 .drop(offset)
                 .runLog // yolo
                 .map(_.toList)
    } yield lines
  }

  private def appendToFile: Sink[Task,(ID,String)] =
    sink.lift { case (id, line) =>
      for {
        path <- getPath(id)
        _    <- createFile(path) >> append(path, line)
      } yield ()
    }

  private def append(path: Path, line: String): Task[Unit] =
    Task.delay {
      val withNewline = if (!line.endsWith("\n")) s"$line\n" else line
      val withTimestamp = s"$NOW: $withNewline"
      // This will open and close the file every time a line is added.
      // Ideally this would be a stream that we could write to and then
      // close when finished. This is to just get things going.
      Files.write(path, withTimestamp.getBytes(), StandardOpenOption.APPEND)
      ()
    }

  private def getPath(id: ID): Task[Path] =
    Task.delay(base.resolve(s"${id}.log"))

  private def createFile(path: Path): Task[Unit] =
    Task.delay {
      if (!exists(path))
        Files.createFile(path)
        ()
    }

  private def exists(path: Path): Boolean =
    Files.exists(path)

  private def NOW = Instant.now
}

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

import org.scalatest.{FlatSpec,Matchers,BeforeAndAfterAll,BeforeAndAfterEach}
import scalaz.concurrent.Task
import java.nio.file.{Path,Paths,Files}
import scalaz.stream.async.boundedQueue
import scalaz.concurrent.Strategy
import scalaz.stream.nio.file._

class WorkflowLoggerSpec extends FlatSpec with Matchers with BeforeAndAfterAll with BeforeAndAfterEach {

  val queue =
    boundedQueue[(ID,String)](10)(Strategy.DefaultStrategy)

  val base = Files.createTempDirectory("nelson")
  val logger = new logging.WorkflowLogger(queue, base)

  override def beforeAll: Unit = {
    logger.setup().run
  }

  override def beforeEach: Unit = {
    delete()
  }

  override def afterAll: Unit = {
    delete()
  }

  private def delete() = {
    import scala.util.Try
    import scala.collection.JavaConversions._
    Try(Files.newDirectoryStream(base)).map(stream =>
      stream.iterator.toIterator.foreach(file => scala.util.Try(Files.delete(file)))
    )
  }

  def removeTimestamp(line: String): String =
    line.split("Z:")(1).trim

  it should "generate a file with the correct name" in {
    logger.log(1L, "foo").run
    logger.process.take(1).run.run
    assert(Files.exists((base.resolve("1.log"))))
  }

  it should "log to the correct file base on id" in {
    val path = base.resolve("1.log")
    logger.log(1L, "foo").run
    logger.process.take(1).run.run
    val line = linesR(path).runLog.run
    line.map(removeTimestamp) should equal (Vector("foo"))
  }

  it should "log with newlines to the file" in {
    val path = base.resolve("1.log")
    logger.log(1L, "foo").run
    logger.log(1L, "bar").run
    logger.process.take(2).run.run
    val lines = linesR(path).runLog.run
    lines.map(removeTimestamp) should equal (Vector("foo","bar"))
  }

  it should "not add an extra newline" in {
    val path = base.resolve("1.log")
    logger.log(1L, "foo\n").run
    logger.log(1L, "bar").run
    logger.process.take(2).run.run
    val lines = linesR(path).runLog.run
    lines.map(removeTimestamp) should equal (Vector("foo","bar"))
  }

  it should "read the file entirely" in {
    logger.log(1L, "foo\n").run
    logger.log(1L, "bar\n").run
    logger.log(1L, "baz\n").run
    logger.process.take(3).run.run
    val lines = logger.read(1L,0).run
    lines.map(removeTimestamp) should equal (Vector("foo","bar","baz"))
  }
  it should "read the file from offset" in {
    logger.log(1L, "foo\n").run
    logger.log(1L, "bar\n").run
    logger.log(1L, "baz\n").run
    logger.process.take(3).run.run
    val lines = logger.read(1L,2).run
    lines.map(removeTimestamp) should equal (Vector("baz"))
  }
}

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
package alerts

import cats.effect.IO
import fs2.Stream
import scala.sys.process.{Process => _, _}

object Promtool {
  /** The result of a prometheus validation */
  abstract class Result(val isValid: Boolean) extends Product with Serializable
  /** The prometheus rules parsed correctly */
  case object Valid extends Result(true)
  /** The prometheus rules parsed incorrectly */
  final case class Invalid(msg: String) extends Result(false)
  /** The prometheus rules checker was not called correctly */
  final case class PromtoolError(exitCode: Int, msg: String)
      extends RuntimeException(s"Promtool exited with code $exitCode: $msg")

  def validateRules(rules: String): IO[Result] =
    withTempFile(rules, suffix=".rules") { f =>
      Stream.eval(IO {
        val out = new StringBuffer()
        def writeLine(s: String): Unit = { out.append(s).append("\n"); () }
        val logger = ProcessLogger(writeLine, writeLine)
        s"promtool check-rules ${f.toPath}".!(logger) match {
          case 0 =>
            // Great success!
            Valid
          case 1 =>
            // We called promtool correctly, but it didn't like our rules.
            Invalid(out.toString)
          case n =>
            // Anything > 1 means we voided the warranty on promtool.
            throw PromtoolError(n, out.toString)
        }
      })
    }.compile.last.map(_.getOrElse(throw new AssertionError("Promtool process did not emit a result")))
}

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
package yaml

import scala.util.parsing.combinator._

object BackendDestinationParser {
  final case class Error(override val getMessage: String) extends RuntimeException

  /**
   * Create a new instance for every usage, as the scala parsers are
   * not thread safe. This was not fixed until 2.12. See SI-4929
   */
  def parse(input: String): Either[Throwable, Manifest.BackendDestination] =
    (new BackendDestinationParser).parse(input)
}
class BackendDestinationParser extends JavaTokenParsers {
  private def unit: Parser[String] =
    """[a-zA-Z0-9][a-zA-Z0-9-]{1,38}[a-zA-Z0-9]""".r | failure("unit names can only contain a-z, A-Z, 0-9 and hypens. Hyphens can not be the first or last character. Length must be be between 3 and 40 characters")
  private def port: Parser[String] =
    """[a-zA-Z_]\w*""".r | failure("names can only contain a-z, A-Z and underscores")
  private def arrow: Parser[String] = literal("->")

  private def parser: Parser[Manifest.BackendDestination] =
    unit ~ arrow ~ port ^^ {
      case (u ~ _ ~ p) => Manifest.BackendDestination(u,p)
    }

  def parse(input: String): Either[Throwable, Manifest.BackendDestination] =
    parseAll(parser, input) match {
      case Success(result, _) => Right(result)
      case Failure(msg, _) => Left(BackendDestinationParser.Error(msg))
      case Error(msg, _) => Left(BackendDestinationParser.Error(msg))
    }
}

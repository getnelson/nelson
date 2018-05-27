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
package nelson.yaml

import nelson.Manifest
import scala.util.parsing.combinator._

/**
 * This parser expects the following syntax to be used when serialising port
 * definitions - all elements are required.
 *
 * name->port/protocol
 *
 * Where the elements specifically are:
 *
 * * name: represents the logical 'name' or 'reference' you want to use this port for.
 *         Examples would be 'default' for the default service port, 'monitoring' for the
 *         monitoring port, or something like 'mutualtls' for a secure channel. The actual
 *         name is not important, but being a short, obvious reference to what it served on
 *         that particular port is typically useful.
 *
 * * port: the actual port number the container binds too when exposing network functionaltiy.
 *         It goes without saying that the number must be between 1 and 65535 to form a
 *         valid, usable port definition.
 *
 * * protocol: the protocol used by the service being exposed by this port. This param is
 *         used by nelson to know what should be done to the routing tables.
 *
 * Here are some examples of usage, that are valid and accepted by this parser:
 *
 * default->9080/https
 * monitoring->4444/tcp
 *
 */
object PortParser {
  final case class Error(override val getMessage: String) extends RuntimeException

  /**
   * Create a new instance for every usage, as the scala parsers are
   * not thread safe. This was not fixed until 2.12. See SI-4929
   */
  def parse(input: String): Either[Throwable, Manifest.Port] =
    (new PortParser).parse(input)
}
class PortParser extends JavaTokenParsers {
  private def arrow: Parser[String] = literal("->")
  private def forwardSlash: Parser[String] = literal("/")
  private def name: Parser[String] = """[a-zA-Z_]\w*""".r | failure("names can only contain a-zA-Z and underscores")
  private def protocol: Parser[String] = "tcp" | "https" | "http" | "wss" | failure("available protocols are tcp, http or https")
  private def port: Parser[Int] = wholeNumber ^^ (_.toInt)
  private def parser: Parser[Manifest.Port] = name ~ arrow ~ port ~ forwardSlash ~ protocol ^^ {
    case (n ~ _ ~ p ~ _ ~ t) => Manifest.Port(n,p,t)
  }

  def parse(input: String): Either[Throwable, Manifest.Port] =
    parseAll(parser, input) match {
      case Success(result, _) => Right(result)
      case Failure(msg, _) => Left(PortParser.Error(msg))
      case Error(msg, _) => Left(PortParser.Error(msg))
    }
}

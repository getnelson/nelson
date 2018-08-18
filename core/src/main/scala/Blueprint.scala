//: ----------------------------------------------------------------------------
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

// import cats.effect.IO
import java.time.Instant
import org.apache.commons.codec.digest.DigestUtils

case class Blueprint(
  name: String,
  revision: Int,
  state: Blueprint.State,
  sha256: Sha256,
  template: String,
  createdAt: Instant = Instant.now
)

object Blueprint {
  sealed trait Revision
  final object HEAD extends Revision
  final case class Discrete(number: Int)

  sealed trait State
  final object Pending extends State
  final object Validating extends State
  final object Active extends State
  final object Deprecated extends State
  final object Invalid extends State

  final case class Template(content: String){
    val sha256: String = DigestUtils.sha256Hex(content)
  }
}

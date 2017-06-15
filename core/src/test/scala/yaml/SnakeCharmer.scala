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

import scalaz.\/
import scalaz.concurrent.Task
import scalaz.syntax.monad._

trait SnakeCharmer {

  def loadManifest(yamlPath: String): Throwable \/ Manifest =
    (for {
      yml <- Util.loadResourceAsString(yamlPath)
      out <- Task.fromDisjunction(ManifestParser.parse(yml).leftMap(e => new RuntimeException(s"parse failed! ${e.list.map(_.getMessage).mkString("\n")} ${e.map(_.getStackTrace).list.flatten.mkString("\n")}")))
    } yield out).attemptRun

}

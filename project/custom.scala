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

import sbt._, Keys._
import spray.revolver.RevolverPlugin.autoImport._

object custom {

  def resources = Seq(
    unmanagedResourceDirectories in Test += baseDirectory.value / ".." / "etc" / "classpath" / "test"
  )

  def revolver = Seq(
    javaOptions ++= Seq(
      s"-Dlogback.configurationFile=${baseDirectory.value}/../etc/classpath/revolver/logback.xml",
      // workaround for log4j CVE-2021-44228
      "log4j2.formatMsgNoLookups=true"
    ),
    reStartArgs :=
      (baseDirectory.value / ".." / "etc" / "development" / name.value / s"${name.value}.dev.cfg").getCanonicalPath :: Nil,
    mainClass in reStart := (mainClass in run).value
  )
}

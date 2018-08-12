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
package verizon.build

import sbt._, Keys._
import xerial.sbt.Sonatype.autoImport.sonatypeProfileName

object CentralRequirementsPlugin extends AutoPlugin {

  override def trigger = allRequirements

  override def requires = RigPlugin

  override lazy val projectSettings = Seq(
    publishTo := Some("releases"  at "https://oss.sonatype.org/service/local/staging/deploy/maven2"),
    sonatypeProfileName := "io.getnelson",
    pomExtra in Global := {
      <developers>
        <developer>
          <id>timperrett</id>
          <name>Timothy Perrett</name>
          <url>http://github.com/timperrett</url>
        </developer>
        <developer>
          <id>stew</id>
          <name>Stew O'Connor</name>
          <url>http://github.com/stew</url>
        </developer>
        <developer>
          <id>kaiserpelagic</id>
          <name>Greg Flanagan</name>
          <url>http://github.com/kaiserpelagic</url>
        </developer>
        <developer>
          <id>rossabaker</id>
          <name>Ross Baker</name>
          <url>http://github.com/rossabaker</url>
        </developer>
        <developer>
          <id>ceedubs</id>
          <name>Cody Allen</name>
          <url>http://github.com/ceedubs</url>
        </developer>
        <developer>
          <id>andrewmohrland</id>
          <name>Andrew Mohrland</name>
          <url>http://github.com/andrewmohrland</url>
        </developer>
        <developer>
          <id>berkeleybear</id>
          <name>Alice Wu</name>
          <url>http://github.com/berkeleybear</url>
        </developer>
        <developer>
          <id>ryanonsrc</id>
          <name>Ryan Delucchi</name>
          <url>http://github.com/ryanonsrc</url>
        </developer>
      </developers>
    },
    licenses := Seq("Apache-2.0" -> url("https://www.apache.org/licenses/LICENSE-2.0.html")),
    homepage := Some(url("http://getnelson.io/")),
    scmInfo := Some(ScmInfo(url("https://github.com/getnelson/nelson"),
                                "git@github.com:getnelson/nelson.git"))
  )
}
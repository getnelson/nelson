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
import verizon.build._

enablePlugins(DocsPlugin)

libraryDependencies += dependencies.simulacrum.core

addCompilerPlugin(dependencies.macroparadise.plugin)

addCompilerPlugin(dependencies.kindprojector.plugin)

scalacOptions += "-Ypartial-unification"

githubOrg := "getnelson"

githubRepoName := "nelson"

baseURL in Hugo := {
  if (isTravisBuild.value) new URI(s"https://getnelson.io/")
  else new URI(s"http://127.0.0.1:${previewFixedPort.value.getOrElse(1313)}/")
}

// dynamically generate a file here that can be automatically
// imported by hugo as "site data". Doing this here so we don't
// need to manually update the version every time we bump it.
// https://gohugo.io/extras/datafiles/
val hugoGenerateData = taskKey[File]("hugo-generate-data")

hugoGenerateData := {
  val dest = (sourceDirectory in Hugo).value / "data" / "global.json"
  IO.write(dest, s"""{"release_version":"${(version in ThisBuild).value}"}""")
  dest
}

val latestCLIReleaseData = taskKey[File]("hugo-cli-release-data")

latestCLIReleaseData := {
  val dest = (sourceDirectory in Hugo).value / "data" / "release.json"
  val contents = scala.io.Source.fromInputStream(
    new java.net.URL("https://api.github.com/repos/getnelson/cli/releases/latest"
      ).openConnection.getInputStream).mkString
  IO.write(dest, contents)
  dest
}

makeSite := makeSite.dependsOn(hugoGenerateData, latestCLIReleaseData).value

import com.typesafe.sbt.SbtGit.GitKeys.{gitBranch, gitRemoteRepo}

gitRemoteRepo := "git@github.com:getnelson/nelson.git"

includeFilter in Hugo := ("*.html" | "*.ico" | "*.jpg" | "*.svg" | "*.png" | "*.js" | "*.css" | "*.gif" | "CNAME")

minimumHugoVersion in Hugo := "0.48"

excludeFilter in Hugo := HiddenFileFilter

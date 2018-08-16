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

baseURL in Hugo := new URI(s"https://getnelson.io/")

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

makeSite := makeSite.dependsOn(hugoGenerateData).value

import com.typesafe.sbt.SbtGit.GitKeys.{gitBranch, gitRemoteRepo}
// TIM: GITHUB_TOKEN is read from the .travis.yml environment
// gitRemoteRepo := "https://"+sys.env.get("GITHUB_TOKEN").getOrElse("anonymous")+"@github.com/getnelson/nelson.git"
gitRemoteRepo := "git@github.com:getnelson/nelson.git"

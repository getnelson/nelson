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
import verizon.build._
import com.typesafe.sbt.packager.archetypes._
import com.typesafe.sbt.packager.docker._

enablePlugins(AshScriptPlugin, JavaAppPackaging, DockerPlugin)

addCompilerPlugin(dependencies.kindprojector.plugin)

scalacOptions += "-Ypartial-unification"

packageName in Docker := "verizon/nelson"

version in Docker := version.value

daemonUser in Docker := "root"

defaultLinuxInstallLocation in Docker := "/opt/application"

dockerUpdateLatest := true

dockerExposedPorts := Seq(9000, 5775)

dockerBaseImage := "library/openjdk:8u131-jre-alpine"

publishLocal := (publishLocal in Docker).value

publish := (publish in Docker).value

releasePublishArtifactsAction := publish.value

custom.resources

custom.revolver

coverageMinimum := 20

resolvers += "splunk-releases" at "http://splunk.artifactoryonline.com/splunk/ext-releases-local"

libraryDependencies ++= Seq(
  "com.splunk.logging"         % "splunk-library-javalogging" % "1.5.2",
  "org.http4s"                %% "http4s-argonaut61"          % V.http4sArgonaut61,
  "org.http4s"                %% "http4s-dsl"                 % V.http4s,
  "org.http4s"                %% "http4s-blaze-server"        % V.http4s,
  "io.prometheus"              % "simpleclient_common"        % V.prometheus
)

mainClass in run := Some("nelson.Main")

val prometheusVersion = SettingKey[String]("prometheus-version", "The version of Prometheus to install")
prometheusVersion := sys.env.getOrElse("PROMETHEUS_VERSION", "1.4.1")

dockerCommands ++= Seq(
  ExecCmd("RUN", "addgroup", "nelson"),
  ExecCmd("RUN", "adduser", "-s", "/bin/false", "-G", "nelson", "-S", "-D", "-H", "nelson"),
  ExecCmd("RUN", "ln", "-s", s"${(defaultLinuxInstallLocation in Docker).value}/bin/${normalizedName.value}", "/usr/local/bin/sbt"),
  ExecCmd("RUN", "chmod", "555", s"${(defaultLinuxInstallLocation in Docker).value}/bin/${normalizedName.value}"),
  ExecCmd("RUN", "chown", "-R", "nelson:nelson", s"${(defaultLinuxInstallLocation in Docker).value}"),
  ExecCmd("RUN", "apk", "add", "--update-cache", "bash", "graphviz", "wget", "libc6-compat"),
  ExecCmd("RUN", "apk", "add", "docker=1.9.1-r2", "--update-cache", "--repository", "http://dl-3.alpinelinux.org/alpine/v3.3/community/")
)

// Install promtool.  It needs to be on the PATH for validation.
dockerCommands ++= {
  val prometheusBase = s"prometheus-${prometheusVersion.value}.linux-amd64"
  Seq(
    ExecCmd("RUN", "wget", "--retry-connrefused", "--waitretry", "1", "--read-timeout", "10", "--timeout", "15", "-t", "5", s"https://github.com/prometheus/prometheus/releases/download/v${prometheusVersion.value}/${prometheusBase}.tar.gz", "-P", "/tmp"),
    ExecCmd("RUN", "tar", "xzf", s"/tmp/${prometheusBase}.tar.gz", "-C", "/tmp"),
    ExecCmd("RUN", "ls", "/tmp"),
    ExecCmd("RUN", "cp", s"/tmp/${prometheusBase}/promtool", "/usr/local/bin"),
    ExecCmd("RUN", "rm", "-rf", s"/tmp/${prometheusBase}", s"/tmp/${prometheusBase}.tar.gz")
  )
}

scalaTestVersion := "2.2.6"

scalaCheckVersion := "1.12.5"

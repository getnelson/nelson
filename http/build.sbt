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

scalacOptions ++= List("-Ypartial-unification", "-Ywarn-value-discard")

packageName in Docker := "getnelson/nelson"

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

libraryDependencies ++= Seq(
  "org.http4s"                %% "http4s-argonaut"            % V.http4s,
  "org.http4s"                %% "http4s-dsl"                 % V.http4s,
  "org.http4s"                %% "http4s-blaze-server"        % V.http4s,
  "io.prometheus"              % "simpleclient_common"        % V.prometheus
)

mainClass in run := Some("nelson.Main")

val kubectlVersion = SettingKey[String]("kubectl-version", "The version of kubectl to install")
kubectlVersion := sys.env.getOrElse("KUBECTL_VERSION", "1.10.5")

val prometheusVersion = SettingKey[String]("prometheus-version", "The version of Prometheus to install")
prometheusVersion := sys.env.getOrElse("PROMETHEUS_VERSION", "1.4.1")

dockerCommands ++= Seq(
  ExecCmd("RUN", "addgroup", "nelson"),
  ExecCmd("RUN", "adduser", "-s", "/bin/false", "-u", "2000", "-G", "nelson", "-S", "-D", "-H", "nelson"),
  ExecCmd("RUN", "ln", "-s", s"${(defaultLinuxInstallLocation in Docker).value}/bin/${normalizedName.value}", "/usr/local/bin/sbt"),
  ExecCmd("RUN", "chmod", "555", s"${(defaultLinuxInstallLocation in Docker).value}/bin/${normalizedName.value}"),
  ExecCmd("RUN", "chown", "-R", "nelson:nelson", s"${(defaultLinuxInstallLocation in Docker).value}"),
  ExecCmd("RUN", "apk", "add", "--update-cache", "bash", "graphviz", "wget", "libc6-compat", "docker")
)

// Install kubectl for the Kubernetes scheduler implementation
dockerCommands ++= Seq(
  ExecCmd("RUN", "wget", "-nv", "--retry-connrefused", "--waitretry", "1", "--read-timeout", "10", "--timeout", "15", "-t", "5", s"https://storage.googleapis.com/kubernetes-release/release/v${kubectlVersion.value}/bin/linux/amd64/kubectl", "-P", "/usr/local/bin"),
  ExecCmd("RUN", "chmod", "+x", "/usr/local/bin/kubectl")
)

// Install promtool.  It needs to be on the PATH for validation.
dockerCommands ++= {
  val prometheusBase = s"prometheus-${prometheusVersion.value}.linux-amd64"
  Seq(
    ExecCmd("RUN", "wget", "-nv", "--retry-connrefused", "--waitretry", "1", "--read-timeout", "10", "--timeout", "15", "-t", "5", s"https://github.com/prometheus/prometheus/releases/download/v${prometheusVersion.value}/${prometheusBase}.tar.gz", "-P", "/tmp"),
    ExecCmd("RUN", "tar", "xzf", s"/tmp/${prometheusBase}.tar.gz", "-C", "/tmp"),
    ExecCmd("RUN", "ls", "/tmp"),
    ExecCmd("RUN", "cp", s"/tmp/${prometheusBase}/promtool", "/usr/local/bin"),
    ExecCmd("RUN", "rm", "-rf", s"/tmp/${prometheusBase}", s"/tmp/${prometheusBase}.tar.gz")
  )
}

dockerCommands += Cmd("USER", "2000")

scalaTestVersion := "3.0.5"

scalaCheckVersion := "1.13.5"

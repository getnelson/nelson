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

enablePlugins(MetadataPlugin, ScalaTestPlugin)

custom.resources

libraryDependencies ++= Seq(
  dependencies.simulacrum.core,
  "co.fs2"                     %% "fs2-core"                           % V.fs2,
  "co.fs2"                     %% "fs2-io"                             % V.fs2,
  "co.fs2"                     %% "fs2-scodec"                         % V.fs2,
  "org.typelevel"              %% "cats-core"                          % V.cats,
  "org.typelevel"              %% "cats-effect"                        % V.catsEffect,
  "org.typelevel"              %% "cats-free"                          % V.cats,
  "io.verizon.helm"            %% "core"                               % V.helm,
  "io.verizon.helm"            %% "http4s"                             % V.helm,
  "io.verizon.knobs"           %% "core"                               % V.knobs,
  "io.verizon.journal"         %% "core"                               % V.journal,
  "io.verizon.quiver"          %% "core"                               % "7.0.19",
  "org.yaml"                    % "snakeyaml"                          % "1.23",
  "org.scalatra.scalate"       %% "scalate-core"                       % "1.9.0",
  "io.argonaut"                %% "argonaut"                           % V.argonaut,
  "io.argonaut"                %% "argonaut-cats"                      % V.argonaut,
  "io.prometheus"               % "simpleclient"                       % V.prometheus,
  "io.prometheus"               % "simpleclient_hotspot"               % V.prometheus,
  "org.tpolecat"               %% "doobie-core"                        % V.doobie,
  "org.tpolecat"               %% "doobie-h2"                          % V.doobie,
  "org.tpolecat"               %% "doobie-hikari"                      % V.doobie,
  "org.spire-math"             %% "spire"                              % "0.11.0",
  "org.flywaydb"                % "flyway-core"                        % "3.2.1",
  "ca.mrvisser"                %% "sealerate"                          % "0.0.4",
  "org.typelevel"              %% "cats-kernel-laws"                   % V.cats   % "test",
  "org.apache.commons"          % "commons-email"                      % "1.3.3",
  "commons-codec"               % "commons-codec"                      % "1.11",
  "com.amazonaws"               % "aws-java-sdk-autoscaling"           % "1.11.25",
  "com.amazonaws"               % "aws-java-sdk-elasticloadbalancing"  % "1.11.25",
  "com.google.guava"            % "guava"                              % "20.0",
  "com.google.code.findbugs"    % "jsr305"                             % "3.0.1", // needed to provide class javax.annotation.Nullable
  "com.cronutils"               % "cron-utils"                         % "5.0.5",
  "org.scodec"                 %% "scodec-core"                        % V.scodec,
  "org.http4s"                 %% "http4s-argonaut"                    % V.http4s,
  "org.http4s"                 %% "http4s-blaze-client"                % V.http4s,
  "com.whisk"                  %% "docker-testkit-scalatest"           % V.dockerit % "test",
  "com.whisk"                  %% "docker-testkit-impl-spotify"        % V.dockerit % "test"
)

addCompilerPlugin(dependencies.kindprojector.plugin)

scalaModuleInfo := scalaModuleInfo.value map { _.withOverrideScalaVersion(true) }

addCompilerPlugin(dependencies.macroparadise.plugin)

initialCommands in console := """
import nelson._
val dbcfg = DatabaseConfig("org.h2.Driver", s"jdbc:h2:file:/tmp/nelson.console.db;DATABASE_TO_UPPER=FALSE", None, None, None)
storage.Migrate.migrate(dbcfg).unsafeRunSync()
"""

buildInfoPackage := "nelson"

scalacOptions ++= List("-Ypartial-unification", "-Ywarn-value-discard")

scalacOptions in (Compile, doc) ++= Seq(
  "-no-link-warnings" // Suppresses problems with Scaladoc @throws links
)

scalaTestVersion := "3.0.5"

scalaCheckVersion := "1.13.5"

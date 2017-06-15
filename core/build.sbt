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
  "io.verizon.helm"            %% "core"                               % "1.4.78-scalaz-7.1",
  "io.verizon.knobs"           %% "core"                               % V.knobs,
  "io.verizon.journal"         %% "core"                               % V.journal,
  "io.verizon.quiver"          %% "core"                               % "5.4.11",
  "io.verizon.delorean"        %% "core"                               % "1.1.37",
  "org.yaml"                    % "snakeyaml"                          % "1.16",
  "io.argonaut"                %% "argonaut"                           % "6.1",
  "io.prometheus"               % "simpleclient"                       % V.prometheus,
  "io.prometheus"               % "simpleclient_hotspot"               % V.prometheus,
  "org.tpolecat"               %% "doobie-core"                        % V.doobie,
  "org.tpolecat"               %% "doobie-contrib-h2"                  % V.doobie,
  "org.spire-math"             %% "spire"                              % "0.11.0",
  "org.flywaydb"                % "flyway-core"                        % "3.2.1",
  "net.databinder.dispatch"    %% "dispatch-core"                      % "0.11.2",
  "ca.mrvisser"                %% "sealerate"                          % "0.0.4",
  "org.scalaz"                 %% "scalaz-scalacheck-binding"          % V.scalaz % "test",
  "org.apache.commons"          % "commons-email"                      % "1.3.3",
  "com.github.alexarchambault" %% "scalacheck-shapeless_1.12"          % "0.3.1" % "test",
  "com.amazonaws"               % "aws-java-sdk-autoscaling"           % "1.11.25",
  "com.amazonaws"               % "aws-java-sdk-elasticloadbalancing"  % "1.11.25",
  "com.google.guava"            % "guava"                              % "20.0",
  "com.google.code.findbugs"    % "jsr305"                             % "3.0.1", // needed to provide class javax.annotation.Nullable
  "com.cronutils"               % "cron-utils"                         % "5.0.5",
  "org.scodec"                 %% "scodec-core"                        % V.scodec,
  "org.http4s"                 %% "http4s-argonaut61"                  % V.http4s,
  "org.http4s"                 %% "http4s-blaze-client"                % V.http4s,
  "com.whisk"                  %% "docker-testkit-scalatest"           % V.dockerit % "test",
  "com.whisk"                  %% "docker-testkit-impl-docker-java"    % V.dockerit % "test"
)

addCompilerPlugin(dependencies.kindprojector.plugin)

ivyScala := ivyScala.value map { _.copy(overrideScalaVersion = true) }

addCompilerPlugin(dependencies.macroparadise.plugin)

addCompilerPlugin(dependencies.si2712fix.plugin)

initialCommands in console := """
import nelson._
val storage = new nelson.storage.H2Storage(DatabaseConfig("org.h2.Driver", s"jdbc:h2:file:/tmp/nelson.console.db;DATABASE_TO_UPPER=FALSE", None, None))
storage.setup.run
"""

wartremoverErrors in (Compile, compile) ++= Warts.allBut(
  Wart.Any,
  Wart.AsInstanceOf,
  Wart.DefaultArguments,
  Wart.ExplicitImplicitTypes,
  Wart.MutableDataStructures,
  Wart.NoNeedForMonad,
  Wart.NonUnitStatements,
  Wart.Nothing,
  Wart.Option2Iterable,
  Wart.Throw,
  Wart.ToString,
  Wart.Var
)

buildInfoPackage := "nelson"

scalacOptions in (Compile, doc) ++= Seq(
  "-no-link-warnings" // Suppresses problems with Scaladoc @throws links
)

scalaTestVersion := "2.2.6"

scalaCheckVersion := "1.12.5"

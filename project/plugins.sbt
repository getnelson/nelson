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
resolvers += "bintray sbt" at "https://dl.bintray.com/sbt/sbt-plugin-releases/"

addSbtPlugin("com.typesafe.sbt"  % "sbt-native-packager"  % "1.3.9")

addSbtPlugin("io.get-coursier"   % "sbt-coursier"         % "1.0.0")

addSbtPlugin("io.spray"          % "sbt-revolver"         % "0.9.1")

addSbtPlugin("io.verizon.build"  % "sbt-rig"              % "5.0.39")

addSbtPlugin("org.xerial.sbt"    % "sbt-sonatype"  		  % "2.3")

libraryDependencies += "ch.qos.logback" % "logback-classic" % "1.1.3"

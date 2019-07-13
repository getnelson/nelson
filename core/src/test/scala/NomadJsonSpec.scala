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

import org.scalatest._

class NomadJsonSpec extends FlatSpec with Matchers with Inspectors {
  import Json._
  import argonaut._, Argonaut._
  import Manifest.{Ports,Port}
  import scala.concurrent.duration._
  import nelson.scheduler.NomadJson
  import nelson.docker.Docker.Image
  import nelson.Manifest._

  val nomad = Infrastructure.Nomad(
    org.http4s.Uri.uri("http://endpoint:8080"),
    1.second, "user", "pass", "addy",
    2300
  )

  val image = Image("image", None, None)

  val env = Environment(
    bindings = List(EnvironmentVariable("NELSON_STACKNAME", "stackname"), EnvironmentVariable("NELSON_DATACENTER", "dc1")),
    cpu = ResourceSpec.limitOnly(2.0).get,
    memory = ResourceSpec.limitOnly(512.0).get)

  val ports = Ports(Port("http",8080,"http"), Nil)

  it should "generate docker config json with ports" in {
    val json = NomadJson.dockerConfigJson(nomad, image, Some(ports), NomadJson.BridgeMode)

    json should equal (Json(
      "port_map" := List(Json("http" := 8080)),
      "image" := "https://image",
      "network_mode" := "bridge",
      "auth" := List(Json(
        "username" := "user",
        "password" := "pass",
        "server_address":= "addy",
        "SSL" := true
      ))
    ))
  }

  it should "generate docker config json without ports" in {
    val json = NomadJson.dockerConfigJson(nomad, image, None, NomadJson.BridgeMode)
    json should equal (Json(
      "image" := "https://image",
      "network_mode" := "bridge",
      "auth" := List(Json(
        "username" := "user",
        "password" := "pass",
        "server_address":= "addy",
        "SSL" := true
      ))
    ))
  }

  it should "generate resources json with ports" in {
    val json = NomadJson.resourcesJson(4000000, 512, Some(ports))
    json should equal (Json(
      "CPU" := 4000000,
      "MemoryMB" := 512,
      "IOPS" := 0,
      "Networks" := List(Json(
        "mbits" := 1,
        "DynamicPorts" := List(Json(
            "Label" := "http",
            "Value" := 0
        ))
      ))
    ))
  }
  it should "generate resources json without ports" in {
    val json = NomadJson.resourcesJson(4000000, 512, None)
    json should equal (Json(
      "CPU" := 4000000,
      "MemoryMB" := 512,
      "IOPS" := 0,
      "Networks" := List(Json("mbits" := 1))
    ))
  }

  it should "generate environment json" in {
    val json = NomadJson.envJson(env.bindings)
    json should equal (Json(
      "NELSON_STACKNAME":="stackname",
      "NELSON_DATACENTER":="dc1"
    ))
  }

  it should "generate services json" in {
    val json = NomadJson.servicesJson("name", ports.default, Set("tag1", "tag2"), Nil)
    json should equal (Json(
      "Name" := "name",
      "PortLabel" := "http",
      "Tags" := List("tag1","tag2"),
      "Checks" := List(Json(
        "Name" := "tcp http name",
        "Type" := "tcp",
        "PortLabel" := "http",
        "Args" := jNull,
        "Command" := "",
        "Id" := "",
        "Path" := "",
        "Protocol" := jNull,
        "Interval":= 10000000000L,
        "Timeout":= 4000000000L,
        "TLSSkipVerify" := false
      ))
    ))
  }

  it should "generate log json" in {
    val json = NomadJson.logJson(10,10)
    json should equal (Json(
      "MaxFiles"      := 10,
      "MaxFileSizeMB" := 10
    ))
  }

  it should "genrate periodic json" in {
    val json = NomadJson.periodicJson("* * * 24")
    json should equal (Json(
      "Spec" := "* * * 24",
      "Enabled" := true,
      "SpecType" := "cron",
      "ProhibitOverlap" := true
    ))
  }

  it should "generate restart json" in {
    val json = NomadJson.restartJson(3)
    json should equal(Json(
      "Interval":= 5.minutes.toNanos,
      "Attempts":= 3,
      "Delay" := 15.seconds.toNanos,
      "Mode" := "delay"
    ))
  }

  it should "generate empheral disk json" in {
    val json = NomadJson.ephemeralDiskJson(false,false,3)
    json should equal(Json(
      "Sticky" := false,
      "Migrate" := false,
      "SizeMB" := 3
    ))
  }

  it should "generate task json with ports defined" in {
    val json = NomadJson.leaderTaskJson("name--1-0-0--abcdef12", image, env, NomadJson.BridgeMode, Some(ports), nomad, NamespaceName("qa"), "default", Set("required-tag1","required-tag2"))
    json should equal(Json(
      "Name" := "name--1-0-0--abcdef12",
      "Driver" := "docker",
      "Services":= List(Json(
        "Name" := "name--1-0-0--abcdef12",
        "PortLabel" := "http",
        "Tags" := Set("qa","port--http","plan--default","required-tag1","required-tag2"),
        "Checks" := List(Json(
          "Name" := "tcp http name--1-0-0--abcdef12",
          "Type" := "tcp",
          "PortLabel":= "http",
          "Args" := jNull,
          "Command" := "",
          "Id" := "",
          "Path" := "",
          "Protocol" := jNull,
          "Interval":= 10000000000L,
          "Timeout":= 4000000000L,
          "TLSSkipVerify" := false
        ))
      )),
      "leader" := true,
      "Config" := Json(
        "image" := "https://image",
        "network_mode" := "bridge",
        "port_map" := List(Json("http" := 8080)),
        "auth" := List(Json(
          "username" := "user",
          "password" := "pass",
          "server_address":= "addy",
          "SSL" := true
         ))
      ),
      "Vault" := Json(
        "ChangeSignal" := "",
        "ChangeMode" := "restart",
        "Env" := true,
        "Policies" := List("nelson__qa__name--1-0-0--abcdef12")
      ),
      "Env" := Json(
        "NELSON_STACKNAME":= "stackname",
        "NELSON_DATACENTER" := "dc1"
      ),
      "Resources" := Json(
        "CPU" := 4600,
        "MemoryMB" := 512,
        "IOPS" := 0,
        "Networks" := List(Json(
          "mbits" := 1,
          "DynamicPorts" := List(Json(
            "Label" := "http",
            "Value" := 0
          ))
        ))
      ),
      "LogConfig" := Json(
        "MaxFiles" := 10,
        "MaxFileSizeMB" := 10
      )
    ))
  }

  it should "generate task json without ports defined" in {
    val json = NomadJson.leaderTaskJson("name--1-0-0--abcdef12", image, env, NomadJson.HostMode, None, nomad, NamespaceName("qa"), "default", Set("required-tag1","required-tag2"))
    json should equal(Json(
      "Name" := "name--1-0-0--abcdef12",
      "Driver" := "docker",
      "leader" := true,
      "Config" := Json(
        "image" := "https://image",
        "network_mode" := "host",
        "auth" := List(Json(
          "username" := "user",
          "password" := "pass",
          "server_address":= "addy",
          "SSL" := true
        ))
      ),
      "Vault" := Json(
        "ChangeSignal" := "",
        "ChangeMode" := "restart",
        "Env" := true,
        "Policies" := List("nelson__qa__name--1-0-0--abcdef12")
      ),
      "Env" := Json(
        "NELSON_STACKNAME":= "stackname",
        "NELSON_DATACENTER" := "dc1"
      ),
      "Resources" := Json(
        "CPU" := 4600,
        "MemoryMB" := 512,
        "IOPS" := 0,
        "Networks" := List(Json("mbits" := 1))
      ),
      "LogConfig" := Json(
        "MaxFiles" := 10,
        "MaxFileSizeMB" := 10
      )
    ))
  }
}

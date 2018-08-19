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
package scheduler

import nelson.Datacenter.{Deployment, StackName}
import nelson.Json._
import nelson.Manifest.{Environment, EnvironmentVariable, HealthCheck, Plan, Port, Ports, UnitDef}
import nelson.docker.Docker
import nelson.docker.Docker.Image

import cats.~>
import cats.effect.IO
import cats.implicits._

import java.util.concurrent.ScheduledExecutorService

import journal.Logger

import scala.concurrent.ExecutionContext

object NomadHttp {
  private val log = Logger[NomadHttp.type]
}

final class NomadHttp(
  cfg: NomadConfig,
  nomad: Infrastructure.Nomad,
  client: org.http4s.client.Client[IO],
  scheduler: ScheduledExecutorService,
  ec: ExecutionContext
) extends (SchedulerOp ~> IO) {
  import NomadJson._
  import NomadHttp.log
  import SchedulerOp._
  import argonaut._, Argonaut._
  import org.http4s._
  import org.http4s.client._
  import org.http4s.Status.NotFound
  import org.http4s.headers.Authorization
  import org.http4s.argonaut._

  def apply[A](co: SchedulerOp[A]): IO[A] =
    co match {
      case Delete(dc,d) =>
        deleteUnitAndChildren(dc, d).retryExponentially()(scheduler, ec)
      case Launch(i, dc, ns, u, p, hash) =>
        val unit = Manifest.Versioned.unwrap(u)
        launch(unit, hash, u.version, i, dc, ns, p).retryExponentially()(scheduler, ec)
      case Summary(dc,_,sn) =>
        summary(dc,sn)
    }

  private def summary(dc: Datacenter, sn: Datacenter.StackName): IO[Option[DeploymentSummary]] = {
    val name = buildName(sn)
    val req = addCreds(dc, Request[IO](Method.GET, nomad.endpoint / "v1" / "job" / name / "summary"))
    implicit val decoder = deploymentSummaryDecoder(name)
    client.expect[DeploymentSummary](req)(jsonOf[IO, DeploymentSummary]).map(ds => Some(ds): Option[DeploymentSummary]).recoverWith {
      case UnexpectedStatus(NotFound) => IO.pure(None)
    }
  }

  private def runningUnits(dc: Datacenter, prefix: Option[String]): IO[Set[RunningUnit]] = {
    val baseUri = nomad.endpoint / "v1" / "jobs"
    val uri = prefix.fold(baseUri)(p => baseUri.withQueryParam("prefix", p))
    val req = addCreds(dc, Request[IO](Method.GET, uri))

    client.expect[List[RunningUnit]](req)(jsonOf[IO, List[RunningUnit]]).map(_.toSet)
  }

  /*
   * Calls Nomad to delete a unit in a given datacenter
   */
  private def deleteUnit(dc: Datacenter, name: String): IO[Unit] = {
    val req = addCreds(dc,Request[IO](Method.DELETE, nomad.endpoint / "v1" / "job" / name))
    client.expect[String](req).map(_ => ()).recoverWith {
      // swallow 404, as we're being asked to delete something that does not exist
      // this can happen when a workflow fails and the cleanup process is subsequently called
      case UnexpectedStatus(NotFound) => IO.unit
    }
  }

  /*
   * Calls Nomad to delete the given unit and all child jobs
   */
  private def deleteUnitAndChildren(dc: Datacenter, d: Deployment): IO[Unit] = {
    val name = buildName(d.stackName)
    for {
      cs <- listChildren(dc, name).map(_.toList)
      _  <- cs.traverse_(_.name.fold(IO.unit)(deleteUnit(dc, _)))
      _  <- deleteUnit(dc, name)
    } yield ()
  }

  private def listChildren(dc: Datacenter, parentID: String): IO[Set[RunningUnit]] = {
    val prefix = parentID + "/periodic"
    runningUnits(dc, Some(prefix)).map(_.filter(_.parentID.exists(_ == parentID)))
  }

  private def addCreds(dc: Datacenter, req: Request[IO]): Request[IO] =
    dc.proxyCredentials.fold(req){ creds =>
      req.putHeaders(Authorization(BasicCredentials(creds.username,creds.password)))
    }

  private def getJson(u: UnitDef, name: String, img: Image, dc: Datacenter, ns: NamespaceName, plan: Plan): Json = {
    val tags = cfg.requiredServiceTags.getOrElse(List()).toSet.union(u.meta)
    val schedule =  Manifest.getSchedule(u, plan)
    NomadJson.job(name, u.name, plan, img, dc, schedule, u.ports, ns, nomad, tags)
  }

  private def buildName(sn: StackName): String =
    cfg.applicationPrefix.map(prefix => s"${prefix}-${sn.toString}").getOrElse(sn.toString)

  private def launch(u: UnitDef, hash: String, version: Version, img: Image, dc: Datacenter, ns: NamespaceName, plan: Plan): IO[String] = {
    def call(name: String, json: Json): IO[String] = {
      log.debug(s"sending nomad the following payload: ${json.nospaces}")
      val req = addCreds(dc, Request[IO](Method.POST, nomad.endpoint / "v1" / "job" / name))
      client.expect[String](req.withBody(json))
    }

    val sn = StackName(u.name, version, hash)
    val name = buildName(sn)
    val vars = plan.environment.bindings ::: List(
      EnvironmentVariable("NELSON_STACKNAME", sn.toString),
      EnvironmentVariable("NELSON_DATACENTER", dc.name),
      EnvironmentVariable("NELSON_ENV", ns.root.asString),
      EnvironmentVariable("NELSON_NAMESPACE", ns.asString),
      EnvironmentVariable("NELSON_DNS_ROOT", dc.domain.name),
      EnvironmentVariable("NELSON_PLAN", plan.name),
      EnvironmentVariable("NELSON_DOCKER_IMAGE", img.toString),
      EnvironmentVariable("NELSON_MEMORY_LIMIT", plan.environment.memory.limitOrElse(512D).toInt.toString),
      EnvironmentVariable("NELSON_NODENAME", s"$${node.unique.name}"),
      EnvironmentVariable("NELSON_VAULT_POLICYNAME", getPolicyName(ns, name))
    ) ++ nomad.loggingImage.map(x => EnvironmentVariable("NELSON_LOGGING_IMAGE", x.toString)).toList
    val p = plan.copy(environment = plan.environment.copy(bindings = vars))
    val json = getJson(u,name,img,dc,ns,p)
    call(name,json)
  }
}

object NomadJson {
  import argonaut._, Argonaut._
  import argonaut.EncodeJsonCats._
  import Infrastructure.Nomad
  import scala.concurrent.duration._

  sealed abstract class NetworkMode(val asString: String)
  final case object BridgeMode extends NetworkMode("bridge")
  final case object HostMode extends NetworkMode("host")

  // We need to pass in the id because Nomad uses it as a key in the response :(
  def deploymentSummaryDecoder(id: String): DecodeJson[DeploymentSummary] =
    DecodeJson[DeploymentSummary](c => {
      val inner = c --\ "Summary" --\ id
      for {
        running   <- (inner --\ "Running").as[Option[Int]]
        failed    <- (inner --\ "Failed").as[Option[Int]]
        queued    <- (inner --\ "Queued").as[Option[Int]]
        completed <- (inner --\ "Complete").as[Option[Int]]
      } yield DeploymentSummary(running, queued, completed, failed)
    })

  // reference: https://www.nomadproject.io/docs/http/jobs.html
  // Note: implicit from argonaut Decoder[List[A]] => Decoder[A] is already defined
  implicit def runningUnitDecoder: DecodeJson[RunningUnit] =
    DecodeJson[RunningUnit](c => {
      for {
        nm <- (c --\ "Name").as[Option[String]]
        st <- (c --\ "Status").as[Option[String]]
        p  <- (c --\ "ParentID").as[Option[String]]
      } yield RunningUnit(nm, st, p)
    })

  def dockerConfigJson(
    nomad: Nomad,
    container: Docker.Image,
    ports: Option[Ports],
    nm: NetworkMode,
    name: String,
    ns: NamespaceName): argonaut.Json = {

    val maybeLogging = nomad.splunk.map(splunk =>
      List(dockerSplunkJson(name, ns, splunk.splunkUrl, splunk.splunkToken)))

    val maybePorts = ports.map(_.nel.map(p => argonaut.Json(p.ref := p.port)))

    ("logging" :=? maybeLogging) ->?:
    ("port_map" :=? maybePorts) ->?: argonaut.Json(
    "image" := s"https://${container.toString}", // https:// required to work with private repo
    "network_mode" := nm.asString,
    "auth" := List(argonaut.Json(
       "username" := nomad.dockerRepoUser,
       "password" := nomad.dockerRepoPassword,
       "server_address" := nomad.dockerRepoServerAddress,
       "SSL" := true
     ))
    )
  }

  def dockerSplunkJson(name: String, ns: NamespaceName, splunkUrl: String, splunkToken: String): argonaut.Json =
    argonaut.Json (
      "type" := "splunk",
      "config" := List(argonaut.Json (
        "splunk-url" := splunkUrl,
        "splunk-sourcetype" := name,
        "splunk-index" := ns.root.asString,
        "splunk-token" := splunkToken
      ))
    )

  // cpu in MHZ, mem in MB
  def resourcesJson(cpu: Int, mem: Int, ports: Option[Ports]): argonaut.Json = {
    val maybePorts = ports.map(_.nel.map(p => argonaut.Json(
      "Label" := p.ref,
      "Value" := 0 // for dynamic ports this is required but is then ignored, garbage
    )))
    argonaut.Json(
      "CPU"      := cpu,
      "MemoryMB" := mem,
      "IOPS"     := 0,
      "Networks" := List(
        ("DynamicPorts" :=? maybePorts) ->?: argonaut.Json(
        "mbits" := 1 // https://github.com/hashicorp/nomad/issues/1282
      ))
    )
  }

  def logJson(maxFiles: Int, maxFileSize: Int): argonaut.Json = {
    argonaut.Json(
      "MaxFiles"      := maxFiles,
      "MaxFileSizeMB" := maxFileSize
    )
  }

  def getPolicyName(ns: NamespaceName, name: String) = s"nelson__${ns.root.asString}__${name}"

  def vaultJson(ns: NamespaceName, name: String): argonaut.Json = {
    // Names need to be namespaced, because a secret in dev != a secret in prod
    val policyName = getPolicyName(ns, name)
    ("Policies" := List(policyName)) ->:
    ("Env" := true) ->:
    ("ChangeMode" := "restart") ->:
    ("ChangeSignal" := "") ->:
    jEmptyObject
  }

  def healthCheckJson(check: HealthCheck): argonaut.Json = {
    // to make nomad happy
    // type can be http or tcp
    // protocol can be http or empty
    val (typ,protocol): (String, Option[String]) =
      if (check.protocol == "http" || check.protocol == "https")
        ("http", Some(check.protocol))
      else (check.protocol, None)

    val skipVerify =
      if (check.protocol == "https") true else false

    argonaut.Json(
      "Name" := check.name,
      "PortLabel" := check.portRef,
      "Path":= check.path.getOrElse(""),
      "Protocol":= protocol,
      "Interval":= check.interval.toNanos,
      "Timeout":= check.timeout.toNanos,
      "TLSSkipVerify" := skipVerify,
      "Type":= typ,
      "Args":= argonaut.Json.jNull,
      "Id" := "",
      "Command":= ""
    )
  }

  // Kind crude, we need better health checks
  def servicesJson(name: String, port: Port, tags: Set[String], checks: List[HealthCheck]): argonaut.Json = {
    val checksJson =
      if (checks.isEmpty) // default tcp check if no health check is defined
        List(healthCheckJson(HealthCheck(s"tcp ${port.ref} ${name}", port.ref, "tcp", None, 10.seconds, 4.seconds)))
      else
        checks.map(healthCheckJson)

    argonaut.Json(
      "Name" := name,
      "PortLabel" := port.ref,
      "Tags" := tags,
      "Checks" := checksJson
    )
  }

  def ephemeralDiskJson(sticky: Boolean, migrate: Boolean, size: Int): argonaut.Json = {
    argonaut.Json(
      "Sticky":= sticky,
      "Migrate":= migrate,
      "SizeMB":= size
    )
  }

  def envJson(bindings: List[EnvironmentVariable]): argonaut.Json =
    bindings.foldLeft(jEmptyObject)((j,a) => (a.name := a.value) ->: j)

  def periodicJson(expression: String): argonaut.Json = {
    ("Spec" := expression) ->:
    ("Enabled" := true) ->:
    ("SpecType" := "cron") ->:
    ("ProhibitOverlap" := true) ->:
    jEmptyObject
  }

  def restartJson(retries: Int): argonaut.Json = {
    argonaut.Json(
      "Interval":= 5.minutes.toNanos,
      "Attempts":= retries,
      "Delay" := 15.seconds.toNanos,
      "Mode" := "delay"
    )
  }

  def leaderTaskJson(name: String, unitName: UnitName, i: Image, env: Environment, nm: NetworkMode, ports: Option[Ports], nomad: Nomad, ns: NamespaceName, plan: PlanRef, tags: Set[String]): argonaut.Json = {
    // Nomad does not support resource requests + limits so we just use limits here
    val cpu = (nomad.mhzPerCPU * env.cpu.limitOrElse(0.5)).toInt
    val mem = env.memory.limitOrElse(512.0).toInt
    val services = ports.map(_.nel.map(p => servicesJson(
      name,
      p,
      Set(ns.root.asString, s"port--${p.ref}", s"plan--$plan").union(tags),
      env.healthChecks.filter(_.portRef == p.ref)
    )))
    ("Services" :=? services) ->?:
    argonaut.Json(
      "Vault"     := vaultJson(ns, name),
      "Name"      := name,        // Maybe use Static names (primary, sidecar)
      "Driver"    := "docker",
      "leader"    := true,          // When "true", other tasks (necessarily "non leaders") will be gracefully terminated, when the leader task completes
      "Config"    := dockerConfigJson(nomad, i, ports, nm, name, ns),
      "Env"       := envJson(env.bindings),
      "Resources" := resourcesJson(cpu, mem, ports),
      "LogConfig" := logJson(10,10)
    )
  }

  // logging sidecar needs port map for 8089
  // TIM: this is kind of a hack and should probally be factored
  // to a config option in future.
  private val loggingPort = Ports(Port("splunk", 8089, "tcp"), Nil)

  def loggingSidecarJson(nomad: Nomad, vars: List[EnvironmentVariable], name: String, ns: NamespaceName): Option[argonaut.Json] = {
    nomad.loggingImage.map { image =>
      argonaut.Json(
        "Name"      := "logging_sidecar",
        "Driver"    := "docker",
        "Config"    := dockerConfigJson(nomad, image, Some(loggingPort), BridgeMode, name, ns),
        "Services"  := argonaut.Json.jNull,
        "Env"       := envJson(vars),
        "Resources" := resourcesJson(800, 2000, Some(loggingPort)),
        "LogConfig" := logJson(10,10)
      )
    }
  }

  def job(name: String, unitName: UnitName, plan: Plan, i: Image, dc: Datacenter, schedule: Option[Schedule], ports: Option[Ports], ns: NamespaceName, nomad: Nomad, tags: Set[String]): Json = {
    val env = plan.environment
    val periodic = schedule.flatMap(_.toCron().map(periodicJson))
    val scheduler = if (schedule.isDefined) "batch" else "service"
    argonaut.Json(
      "Job" :=
        ("Periodic"   :=? periodic) ->?: argonaut.Json(
        "Region"      := dc.name,
        "Datacenters" := List(dc.name), // regions each have a single eponymous datacenter
        "ID"          := name,
        "Name"        := name,
        "Type"        := scheduler,
        "Priority"    := 50,
        "AllAtOnce"   := false,
        "TaskGroups"  := List(
          argonaut.Json(
            "Name"          := name,
            "Count"         := env.desiredInstances.getOrElse(1),
            "RestartPolicy" := env.retries.map(restartJson).getOrElse(restartJson(3)), // if no retry specified, only try 3 times rather than 15.
            "EphemeralDisk" := ephemeralDiskJson(false,false,plan.environment.ephemeralDisk.getOrElse(101)),
            "Tasks"         := (List(leaderTaskJson(name,unitName,i,env,BridgeMode,ports,nomad,ns,plan.name, tags)) ++
                               loggingSidecarJson(nomad, env.bindings, name, ns))
          )
        )
      )
    )
  }
}

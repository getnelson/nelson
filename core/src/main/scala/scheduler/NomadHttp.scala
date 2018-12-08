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
import nelson.Manifest.{EnvironmentVariable, Plan, UnitDef}
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

  private def delete(dc: Datacenter, d: Deployment): IO[Unit] =
    d.renderedBlueprint.fold(deleteUnitAndChildren(dc, d)) { spec =>
      // TODO: Delete via spec
      deleteUnitAndChildren(dc, d)
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

  private def buildName(sn: StackName): String =
    cfg.applicationPrefix.map(prefix => s"${prefix}-${sn.toString}").getOrElse(sn.toString)

  private def modulateCpu(cpu: Double) = nomad.mhzPerCPU * cpu

  private def launch(u: UnitDef, hash: String, version: Version, img: Image, dc: Datacenter, ns: NamespaceName, plan: Plan): IO[String] = {
    import blueprint._
    import Manifest.ResourceSpec._

    val env = plan.environment
    val sn = StackName(u.name, version, hash)
    val name = buildName(sn)
    val cpu = modulateCpu(env.cpu.limitOrElse(0.5))
    val memory = env.memory.limitOrElse(512D)

    val template = env.blueprint
      .traverse {
        case Left(_)   => IO.raiseError(new IllegalArgumentException("Internal error occured: un-hydrated blueprint passed to scheduler!"))
        case Right(bp) => bp.template.pure[IO]
      } >>=
      (_.fold(DefaultBlueprints.magnetar)(_.pure[IO]))

    val magnetarEnv = List(
      EnvironmentVariable("NELSON_DATACENTER", dc.name),
      EnvironmentVariable("NELSON_DOCKER_IMAGE", img.toString),
      EnvironmentVariable("NELSON_CPU_LIMIT", cpu.toString),
      EnvironmentVariable("NELSON_MEMORY_LIMIT", memory.toString),
      EnvironmentVariable("NELSON_NODENAME", s"$${node.unique.name}"),
      EnvironmentVariable("NELSON_VAULT_POLICYNAME", vault.policies.policyName(sn, ns)))

    val updatePlan = plan.copy(
      environment = env.copy(
        bindings  = env.bindings ++ magnetarEnv,
        cpu       = env.cpu.fold(
                    limitOnly(cpu),
                    l => limitOnly(modulateCpu(l)),
                    (_, l) => limitOnly(modulateCpu(l))).get,
        memory    = env.memory.fold(
                    limitOnly(memory),
                    limitOnly,
                    (_, l) => limitOnly(l)).get))

    for {
      tmpl <- template
      spec  = tmpl.render(Render.magnetar(img, dc, ns, u, updatePlan, sn, nomad.docker))
      json <- parse(dc, spec)
      spec <- call(name, dc, json)
    } yield spec
  }

  def parse(dc: Datacenter, spec: String): IO[Json] = {
    log.debug(s"normalizing the following spec: $spec")
    val req = addCreds(dc, Request[IO](Method.POST, nomad.endpoint / "v1" / "jobs" / "parse"))
    client.expect[Json](req.withBody(makeParseRequest(spec)))
  }

  def call(name: String, dc: Datacenter, json: Json): IO[String] = {
    log.debug(s"sending nomad the following payload: ${json.nospaces}")
    val req = addCreds(dc, Request[IO](Method.POST, nomad.endpoint / "v1" / "job" / name))
    client.expect[String](req.withBody(json))
  }
}

object NomadJson {
  import argonaut._, Argonaut._

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

  def makeParseRequest(spec: String): Json =
    ("JobHCL"       := spec) ->:
    ("Canonicalize" := true) ->:
    jEmptyObject
}

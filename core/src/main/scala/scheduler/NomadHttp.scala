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
import nelson.Manifest._
import nelson.blueprint.{ContextRenderer, EnvValue, Render}
import nelson.blueprint.DefaultBlueprints.magnetar
import nelson.docker.Docker.Image

import cats.~>
import cats.effect.IO
import cats.implicits._

import java.util.concurrent.ScheduledExecutorService

import journal.Logger

import scala.concurrent.ExecutionContext

object NomadHttp {
  private val log = Logger[NomadHttp.type]

  implicit val contextRendererNomadContext: ContextRenderer[Magnetar.Context] =
    new ContextRenderer[Magnetar.Context] {
      import Render.keys._
      import EnvValue._

      override def render(context: Magnetar.Context): Map[String, EnvValue] = {
        import context._
        Map(
          (vaultPolicies, ListValue(
            MapValue(Map((vaultPolicyName, StringValue(getPolicyName(ns, n))))) :: Nil
          ))
        )
      }
    }

  def getPolicyName(ns: NamespaceName, name: String) = s"nelson__${ns.root.asString}__${name}"
}

final class NomadHttp(
  cfg: NomadConfig,
  nomad: Infrastructure.Nomad,
  client: org.http4s.client.Client[IO],
  scheduler: ScheduledExecutorService,
  ec: ExecutionContext
) extends (SchedulerOp ~> IO) {
  import NomadJson._
  import NomadHttp._
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
        launch(i, dc, ns, unit, u.version, p, hash).retryExponentially()(scheduler, ec)
      case Summary(dc,_,sn) =>
        summary(dc,sn)
    }

  private def summary(dc: Datacenter, sn: Datacenter.StackName): IO[Option[DeploymentSummary]] = {
    val name = buildName(sn)
    val req = addCreds(dc, Request[IO](Method.GET, nomad.endpoint / "v1" / "job" / name / "summary"))
    implicit val decoder = deploymentSummaryDecoder(name)
    client.expect[DeploymentSummary](req)(jsonOf[IO, DeploymentSummary]).map(_.some).recoverWith {
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
    client.expect[String](req).void.recoverWith {
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
    runningUnits(dc, Some(prefix)).map(_.filter(_.parentID.exists(_ === parentID)))
  }

  private def addCreds(dc: Datacenter, req: Request[IO]): Request[IO] =
    dc.proxyCredentials.fold(req) { creds =>
      req.putHeaders(Authorization(BasicCredentials(creds.username, creds.password)))
    }

  private def buildName(sn: StackName): String =
    cfg.applicationPrefix.map(prefix => s"${prefix}-${sn.toString}").getOrElse(sn.toString)

  private def launch(image: Image, dc: Datacenter, ns: NamespaceName, unit: UnitDef, version: Version, plan: Plan, hash: String): IO[String] = {
    val sn = StackName(unit.name, version, hash)
    val name = buildName(sn)

    val env = plan.environment.workflow match {
      case Magnetar => Render
        .makeEnv(ContextRenderer.Base(image, dc, ns, unit, version, plan, hash),
                 Magnetar.Context(ns, name)).pure[IO]
      case wf => IO.raiseError(WorkflowNotSupported(wf.name, "nomad"))
    }

    val fallback = mkFallback(unit, plan)(
      magnetar.nomad.service,
      magnetar.nomad.job,
      magnetar.nomad.cronJob)

    for {
      e <- env
      t <- mkTemplate(plan.environment, fallback)
      j <- parse(dc, t.render(e))
      r <- call(name, dc, j)
    } yield r
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

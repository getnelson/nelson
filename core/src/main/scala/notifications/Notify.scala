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
package notifications

import nelson.Datacenter.{Namespace, StackName}
import nelson.Manifest.{UnitDef,Versioned}
import nelson.storage.StoreOp

import cats.~>
import cats.data.OptionT
import cats.effect.IO
import cats.implicits._

import journal._

object Notify {

  def deployedTemplate(dc: DatacenterRef, ns: NamespaceName, sn: StackName): String =
    s"""
    |Nelson finished deploying ${sn.serviceType} version ${sn.version} ($sn)
    |to ${ns.asString} $dc
    """.stripMargin

  def decommissionTemplate(dc: DatacenterRef, ns: NamespaceName, sn: StackName): String =
    s"""
    |Nelson finished decommissioning ${sn.serviceType} version ${sn.version} ($sn)
    |in ${ns.asString} $dc
    """.stripMargin

  def sendDeployedNotifications(unit: UnitDef @@ Versioned, actionConfig: Manifest.ActionConfig)(cfg: NelsonConfig): IO[Unit] = {
    val name = Versioned.unwrap(unit).name
    val sn = StackName(name, unit.version, actionConfig.hash)
    val msg = deployedTemplate(actionConfig.datacenter.name,actionConfig.namespace.name,sn)
    val sub = s"Deployed $sn in ${actionConfig.datacenter.name} ${actionConfig.namespace.name.asString}"
    sendSlack(actionConfig.notifications.slack.map(_.channel), msg)(cfg.slack) productR
    sendEmail(actionConfig.notifications.email.map(_.recipient), sub, msg)(cfg.email)
  }

  def sendDecommissionedNotifications(dc: Datacenter, ns: Namespace, d: Datacenter.Deployment)(cfg: NelsonConfig): IO[Unit] = {

    def fetchNotifications: IO[NotificationSubscriptions] = {
      def fetchManifest(slug: Slug) = Github.Request.fetchFileFromRepository(slug,
        cfg.manifest.filename, Github.Branch("master"))(cfg.git.systemAccessToken).foldMap(cfg.github)

      def findRelease =
        StoreOp.findReleaseByDeploymentGuid(d.guid).map(_.map(_._1.slug)).foldMap(cfg.storage)

      val notes = for {
        slug <- OptionT(findRelease)
        raw  <- OptionT(fetchManifest(slug))
        man  <- OptionT(IO.pure(yaml.ManifestParser.parse(raw.decoded).toOption))
      } yield man.notifications

      notes.value.attempt.map {
        case Right(Some(ns)) => ns
        case _ => NotificationSubscriptions.empty
      }
    }

    val subject = s"Decommissioning deployment ${d.stackName} in ${dc.name}"
    val msg = decommissionTemplate(dc.name,ns.name,d.stackName)
    for {
      n <- fetchNotifications
      _ <- sendSlack(n.slack.map(_.channel), msg)(cfg.slack)
      _ <- sendEmail(n.email.map(_.recipient), subject, msg)(cfg.email)
    } yield ()
  }

  private def sendEmail(rs: List[EmailAddress], sub: String, msg: String)(i: Option[EmailOp ~> IO]) =
    if (rs.isEmpty) IO.unit
    else i.fold(log(s"email ($sub) was not sent because the email server is not configured")) { interp =>
      EmailOp.send(rs, sub, msg).foldMap(interp)
    }

  private def sendSlack(cs: List[SlackChannel], msg: String)(i: Option[SlackOp ~> IO]) =
    if (cs.isEmpty) IO.unit
    else i.fold(log(s"slack notification was not sent because slack integration is not configured")) { interp =>
      SlackOp.send(cs, msg).foldMap(interp)
    }

  private val logger = Logger[Notify.type]

  private def log(msg: String): IO[Unit] =
    IO(logger.info(msg))
}

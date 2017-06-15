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
package audit

import argonaut.{EncodeJson}
import Datacenter.Deployment


/** Enumerates all the known Auditable categories and is used to provides context about the Auditable. */
sealed trait AuditCategory extends Product with Serializable
case object GithubReleaseCategory extends AuditCategory
case object GithubWebHookCategory extends AuditCategory
case object GithubRepoCategory extends AuditCategory
case object ManualDeploymentCategory extends AuditCategory
case object DeploymentCategory extends AuditCategory
case object ErrorCategory extends AuditCategory
case object InfoCategory extends AuditCategory

object AuditCategory {
  def stringify(cat: AuditCategory) = cat match {
    case GithubReleaseCategory   => "release"
    case GithubWebHookCategory   => "hook"
    case GithubRepoCategory      => "repo"
    case ManualDeploymentCategory => "manual_deploy"
    case DeploymentCategory      => "deploy"
    case ErrorCategory           => "error"
    case InfoCategory            => "info"
  }
}

/** Enumerates all the actions that can to applied to an Auditable */
sealed trait AuditAction extends Product with Serializable
case object CreateAction extends AuditAction
case object DeleteAction extends AuditAction
case object UpdateAction extends AuditAction
case object DeprecateAction extends AuditAction
case object GarbageAction extends AuditAction
case object ReadyAction extends AuditAction
case object LoggingAction extends AuditAction

object AuditAction {
  def stringify(action: AuditAction) = action match {
    case CreateAction    => "create"
    case DeleteAction    => "delete"
    case UpdateAction    => "update"
    case DeprecateAction => "deprecate"
    case GarbageAction   => "garbage"
    case ReadyAction     => "ready"
    case LoggingAction   => "logging"
  }
}

final case class AuditContext(action: AuditAction, category: AuditCategory) {
  def stringify = s"${AuditAction.stringify(action)}.${AuditCategory.stringify(category)}"
}

/** Represents an `event` that we wish to audit.
  * Because we are storing the events in a persistent store an encoding is necessary.
  * Json was choosen because the shape of events varies and because most
  * events already have a json encoder available.
  * The category provides context outside of the json blob concerning the `event`.
  * the category is usefull from a querying perspective.
  */
trait Auditable[A] {
  def encode(a: A): argonaut.Json
  def category: AuditCategory
}

object AuditableInstances {

  import argonaut._, Argonaut._

  implicit def githubReleaseAudtiable(implicit e: EncodeJson[Github.Release]): Auditable[Github.Release] = new Auditable[Github.Release] {
    def encode(a: Github.Release) = e.encode(a)
    def category = GithubReleaseCategory
  }

  implicit def githubWebHook(implicit e: EncodeJson[Github.WebHook]): Auditable[Github.WebHook] =
    new Auditable[Github.WebHook] {
      def encode(a: Github.WebHook) = e.encode(a)
      def category = GithubReleaseCategory
    }

  implicit def manualDeploymentAuditable(implicit e: EncodeJson[Datacenter.ManualDeployment]): Auditable[Datacenter.ManualDeployment] =
    new Auditable[Datacenter.ManualDeployment] {
      def encode(a: Datacenter.ManualDeployment) = e.encode(a)
      def category = ManualDeploymentCategory
    }

  implicit def repoAudtiable(implicit e: EncodeJson[Repo]): Auditable[Repo] = new Auditable[Repo] {
    def encode(a: Repo) = e.encode(a)
    def category = GithubRepoCategory
  }

  implicit def hookAudtiable(implicit e: EncodeJson[Hook]): Auditable[Hook] = new Auditable[Hook] {
    def encode(a: Hook) = e.encode(a)
    def category = GithubWebHookCategory
  }

  implicit val stringAuditable: Auditable[String] = new Auditable[String] {
    def encode(s: String) = jString(s)
    def category = InfoCategory
  }

  implicit val nelsonErrorAuditable: Auditable[NelsonError] = new Auditable[NelsonError] {
    def encode(error: NelsonError) = nelson.Json.NelsonErrorEncoder.encode(error)
    def category = ErrorCategory
  }

  implicit def sessionAuditable(implicit e: EncodeJson[Session]): Auditable[Session] = new Auditable[Session] {
    def encode(s: Session) = e.encode(s)
    def category = InfoCategory
  }

  implicit def deploymentAuditable(implicit e: EncodeJson[Deployment]): Auditable[Deployment] = new Auditable[Deployment] {
    def encode(s: Deployment) = e.encode(s)
    def category = DeploymentCategory
  }
}

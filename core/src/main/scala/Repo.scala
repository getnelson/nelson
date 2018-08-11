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

import ca.mrvisser.sealerate
import cats.implicits._

final case class Repo(
  /* e.g. 1296269 */
  id: Long,
  /* e.g. Verizon/knobs */
  slug: Slug,
  /* was the requester of this particular repo an admin? */
  access: RepoAccess,
  /* details about the github webhook for this repo */
  hook: Option[Hook] = None //,
  /* a description of what this repository is about */
  // description: Option[String] = None
)
object Repo {
  def apply(id: Long, slug: String, access: String, hook: Option[Hook]): Either[NelsonError, Repo] = {
    (RepoAccess.fromString(access),
      Slug.fromString(slug)
    ).mapN((a,b) => Repo(id,b,a,hook))
  }
}

/**
 * Represents the webhook added to the Github repository.
 */
final case class Hook(
  id: Long,
  isActive: Boolean
)

/**
 * Typed representation of the owner / repo string that is used
 * to canonically reference a repo within nelson.
 */
final case class Slug(
  owner: String,
  repository: String
){
  override def toString: String =
    s"$owner/$repository"
}
object Slug {
  def fromString(s: String): Either[NelsonError, Slug] =
    s.split('/') match {
      case Array(o,r) => Right(Slug(o,r))
      case _          => Left(InvalidSlug(s))
    }
}

/**
 * Denotes the access aforded to this repository for nelson.
 * Avalible options are (in order of access level, descending):
 * - admin
 * - push
 * - pull
 */
sealed trait RepoAccess {
  override def toString: String =
    getClass.getName.toLowerCase.replace("$","").replace("nelson.repoaccess","")
}
object RepoAccess {
  case object Admin extends RepoAccess
  case object Push extends RepoAccess
  case object Pull extends RepoAccess
  case object Forbidden extends RepoAccess
  case object Unknown extends RepoAccess

  val all: Set[RepoAccess] = sealerate.values[RepoAccess]

  def fromBools(admin: Boolean, push: Boolean, pull: Boolean): RepoAccess =
    if(admin) Admin
    else if(push) Push
    else if(pull) Pull
    else Forbidden

  def fromString(s: String): Either[NelsonError, RepoAccess] =
    all.find(_.toString.toLowerCase == s.toLowerCase) match {
      case Some(a) => Right(a)
      case _       => Left(InvalidRepoAccess(s))
    }
}

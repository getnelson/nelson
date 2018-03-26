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

import java.net.URI

/**
 * represents the current user who's logged into the system.
 */
final case class User(
  login: String,
  avatar: URI,
  name: String,
  email: Option[String],
  orgs: List[Organization]
){
  val toOrganization: Organization =
    Organization(0l, Option(name), login, Some(avatar))
}

final case class Organization(
  id: Long,
  _name: Option[String],
  slug: String,
  avatar: Option[URI]
){
  val name: String =
    _name.getOrElse(slug)
}

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
package plans

import org.http4s.dsl.io._

object Params {

  object Offset extends OptionalQueryParamDecoderMatcher[Int]("offset")

  object Ns extends QueryParamDecoderMatcher[String]("ns")

  object NsO extends OptionalQueryParamDecoderMatcher[String]("ns")

  object Dc extends OptionalQueryParamDecoderMatcher[String]("dc")

  object U extends OptionalQueryParamDecoderMatcher[String]("unit")

  object Status extends OptionalQueryParamDecoderMatcher[String]("status")

  def commaSeparatedStringToList(str: String): List[String] =
    str.split(",").filterNot(_.isEmpty).toList

  def commaSeparatedStringTo[A](str: String, f: String => A): List[A] =
    commaSeparatedStringToList(str).map(f)

  def commaSeparatedStringToStatus(str: String): List[DeploymentStatus] =
    if (str.trim == "all") DeploymentStatus.all.toList
    else commaSeparatedStringTo(str, DeploymentStatus.fromString _)

  def commaSeparatedStringToNamespace(str: String): List[Either[InvalidNamespaceName, NamespaceName]] =
    commaSeparatedStringTo(str, NamespaceName.fromString _)
}

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

import cats.Order
import cats.implicits._

final case class Version(major: Int, minor: Int, patch: Int){
  /**
   * Convert this full version to a feature version.
   */
  def toFeatureVersion = FeatureVersion(major, minor)

  def toMajorVersion = MajorVersion(major)

  override def toString = s"$major.$minor.$patch"
  def toExternalString = s"$major-$minor-$patch"
}

object Version {
  private[this] val versionRegex = "(\\d+)\\.(\\d+)\\.(\\d+)".r

  def fromString(s: String): Option[Version] = s match {
    case versionRegex(major, minor, patch) => Some(Version(major.toInt, minor.toInt, patch.toInt))
    case _ => None
  }

  private[this] val publicVersionRegex = "(\\d+)-(\\d+)-(\\d+)".r

  def fromPublicString(s: String): Option[Version] = s match {
    case publicVersionRegex(major, minor, patch) => Some(Version(major.toInt, minor.toInt, patch.toInt))
    case _ => None
  }

  implicit val versionOrder: Order[Version] =
    Order.whenEqualMonoid.combineAll(List(
      Order.by(_.major),
      Order.by(_.minor),
      Order.by(_.patch)
    ))
}

final case class FeatureVersion(major: Int, minor: Int){
  override def toString = s"$major.$minor"
  def minVersion: Version = Version(major, minor, 0)
  def toMajorVersion: MajorVersion = MajorVersion(major)
}

object FeatureVersion {
  private[this] val versionRegex = "(\\d+)\\.(\\d+)".r

  def fromString(s: String): Option[FeatureVersion] = s match {
    case versionRegex(major, minor) => Some(FeatureVersion(major.toInt, minor.toInt))
    case _ => None
  }

  implicit val featureVersionOrder: Order[FeatureVersion] =
    Order.whenEqual(Order.by(_.major), Order.by(_.minor))
}

final case class MajorVersion(major: Int) {
  override def toString = s"$major"
  def minVersion: Version = Version(major, 0, 0)
  def minFeatureVersion: FeatureVersion = FeatureVersion(major,0)
}

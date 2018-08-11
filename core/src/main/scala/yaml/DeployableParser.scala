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
package yaml

import cats.data.NonEmptyList
import cats.implicits._
import java.util.{Map => JMap}
import scala.beans.BeanProperty

/**
 * This parser is intended to load and read the YAML documents that
 * are output from the build process, and uploaded with the github
 * release such that we know exactly what deployable we're dealing
 * with for a given unit.
 *
 * One release may have multiple units.
 */
object DeployableParser extends YamlParser[Manifest.Deployable] {
  import YamlParser.fromYaml
  import scala.collection.JavaConverters._

  def parse(input: String): Either[NonEmptyList[NelsonError], Manifest.Deployable] =
    v1.parse(input).leftMap(NonEmptyList.of(_))

  object v1 {
    @SuppressWarnings(Array("org.brianmckenna.wartremover.warts.Product", "org.brianmckenna.wartremover.warts.Serializable"))
    def parse(input: String): Either[NelsonError, Manifest.Deployable] = {
      def toDeployable(in: Map[String,String]): Option[Manifest.Deployable.Output] =
        in.get("image").map(Manifest.Deployable.Container.apply)

      def toVersion(v: String) =
        Version.fromString(v).map(Right(_))
          .getOrElse(Left(UnparsableReleaseVersion(v)))

      for {
        a <- fromYaml[DeployableYamlVersion1](input)
        b <- toDeployable(a.output.asScala.toMap)
              .map(d => Right(d))
              .getOrElse(Left(LoadError(
              s"could not convert the input YAML to a Manifest.Deployable. Input was:\n $input")))
        c <- toVersion(a.version)
      } yield Manifest.Deployable(
        name = a.name,
        version = c,
        output = b
      )
    }
  }
}

class DeployableYamlVersion1 {
  @BeanProperty var name: String = _
  @BeanProperty var version: String = _
  @BeanProperty var output: JMap[String,String] = new java.util.HashMap
}

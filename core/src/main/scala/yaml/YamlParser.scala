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

import cats.data.NonEmptyList
import cats.effect.IO
import cats.implicits._

import scala.reflect.ClassTag

import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.Constructor

object YamlParser {
  def fromYaml[A : ClassTag](input: String): Either[NelsonError, A] =
    Either.catchNonFatal {
      val constructor = new Constructor(implicitly[ClassTag[A]].runtimeClass)
      val yaml = new Yaml(constructor)
      yaml.load(input).asInstanceOf[A]
    } leftMap (t => YamlError.loadError(t.getMessage))
}
abstract class YamlParser[A] {
  def parseIO(input: String): IO[A] =
    IO.fromEither(parse(input).leftMap(e => LoadError(e.toList.map(_.getMessage).mkString(","))))

  def parse(input: String): Either[NonEmptyList[NelsonError], A]
}

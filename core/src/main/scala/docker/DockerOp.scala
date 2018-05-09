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
package docker

import nelson.Manifest.UnitDef
import nelson.docker.Docker.{Image,RegistryURI}

import cats.free.Free

sealed abstract class DockerOp[A] extends Product with Serializable

object DockerOp {

  final case class Extract(unit: UnitDef) extends DockerOp[Image]

  final case class Tag(image: Image, registry: RegistryURI) extends DockerOp[(Int, Image)]

  final case class Push(image: Image) extends DockerOp[(Int, List[Docker.Push.Output])]

  final case class Pull(image: Image) extends DockerOp[(Int, List[Docker.Pull.Output])]

  type DockerF[A] = Free[DockerOp, A]

  def extract(unit: UnitDef): DockerF[Image] =
    Free.liftF(Extract(unit))

  def tag(image: Image, registry: RegistryURI): DockerF[(Int, Image)] =
    Free.liftF(Tag(image,registry))

  def push(image: Image): DockerF[(Int, List[Docker.Push.Output])] =
    Free.liftF(Push(image))

  def pull(image: Image): DockerF[(Int, List[Docker.Pull.Output])] =
    Free.liftF(Pull(image))
}

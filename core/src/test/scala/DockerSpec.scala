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

import org.scalacheck._, Prop._

object DockerSpec extends Properties("Docker"){
  import Fixtures._
  import docker.Docker.Image

  case class DockerHost(value: String)

  val genDockerHost: Gen[DockerHost] =
    for {
      a <- Gen.choose(0,3)
      b <- Gen.listOfN(a, alphaNumStr)
      c <- alphaNumStr
    } yield DockerHost(b.mkString(".") + "/" + c)

  implicit lazy val arbDockerHost: Arbitrary[DockerHost] =
    Arbitrary(genDockerHost)

  property("Image") = forAll { (img: Image, h: DockerHost, d: Manifest.Deployable.Container) =>
    ("verify 'to' properly replaces the host, if present" |:
      img.to(h.value).name.startsWith(h.value)) &&
    ("verify fromString works" |:
      Image.fromString(d.image).isRight)
  }
}

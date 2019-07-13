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
package helmhttp4s

import cats.~>
import cats.data.Kleisli
import cats.effect.IO
import fs2.Stream
import fs2.interop.scodec.ByteVectorChunk
import scodec.bits.ByteVector
import org.http4s.{EntityBody, Request, Response, Status, Uri}
import org.http4s.client._
import org.scalatest._
import org.scalactic.TypeCheckedTripleEquals
import helm._
import helm.http4s.Http4sConsulClient

class Http4sConsulClientSpec extends FlatSpec with Matchers with TypeCheckedTripleEquals {
  import Http4sConsulTests._

  "get" should "succeed with some when the response is 200" in {
    val response = consulResponse(Status.Ok, "yay")
    val csl = constantConsul(response)
    helm.run(csl, ConsulOp.kvGet("foo")).attempt.unsafeRunSync() should ===(
      Right(Some("yay")))
  }

  "get" should "succeed with none when the response is 404" in {
    val response = consulResponse(Status.NotFound, "nope")
    val csl = constantConsul(response)
    helm.run(csl, ConsulOp.kvGet("foo")).attempt.unsafeRunSync() should ===(
      Right(None))
  }

  it should "fail when the response is 500" in {
    val response = consulResponse(Status.InternalServerError, "boo")
    val csl = constantConsul(response)
    helm.run(csl, ConsulOp.kvGet("foo")).attempt.unsafeRunSync() should ===(
      Left(UnexpectedStatus(Status.InternalServerError)))
  }

  "set" should "succeed when the response is 200" in {
    val response = consulResponse(Status.Ok, "yay")
    val csl = constantConsul(response)
    helm.run(csl, ConsulOp.kvSet("foo", "bar")).attempt.unsafeRunSync() should ===(
      Right(()))
  }

  it should "fail when the response is 500" in {
    val response = consulResponse(Status.InternalServerError, "boo")
    val csl = constantConsul(response)
    helm.run(csl, ConsulOp.kvSet("foo", "bar")).attempt.unsafeRunSync() should ===(
      Left(UnexpectedStatus(Status.InternalServerError)))
  }
}

object Http4sConsulTests {
  def constantConsul(response: Response[IO]): ConsulOp ~> IO = {
    new Http4sConsulClient(
      Uri.uri("http://localhost:8500/v1/kv/v1"),
      constantResponseClient(response),
      None)
  }

  def consulResponse(status: Status, s: String): Response[IO] = {
    val responseBody = body(s)
    Response(status = status, body = responseBody)
  }

  def constantResponseClient(response: Response[IO]): Client[IO] = {
    val dispResponse = DisposableResponse(response, IO.unit)
    Client[IO](Kleisli{(_: Request[IO]) => IO.pure(dispResponse)}, IO.unit)
  }

  def body(s: String): EntityBody[IO] =
    Stream.chunk(ByteVectorChunk(ByteVector.encodeUtf8(s).right.get)) // YOLO

  val dummyRequest: Request[IO] = Request()
}

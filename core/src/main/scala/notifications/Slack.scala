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
package notifications

import cats.~>
import cats.effect.IO
import cats.free.Free
import cats.implicits._

import org.http4s.Method.POST
import org.http4s.Request
import org.http4s.argonaut._
import org.http4s.client.Client

sealed abstract class SlackOp[A] extends Product with Serializable

object SlackOp {

  type SlackOpF[A] = Free[SlackOp, A]

  final case class SendSlackNotification(channels: List[SlackChannel], message: String) extends SlackOp[Unit]

  def send(channels: List[SlackChannel], msg: String): SlackOpF[Unit] =
    Free.liftF(SendSlackNotification(channels, msg))
}

final class SlackHttp(cfg: SlackConfig, client: Client[IO]) extends (SlackOp ~> IO) {
  import argonaut._, Argonaut._
  import SlackOp._

  def apply[A](op: SlackOp[A]): IO[A] = op match {
    case SendSlackNotification(channels, msg) =>
      channels.traverse_(channel => send(channel,msg))
  }

  def send(channel: String, msg: String): IO[Unit] = {
    val json = Json("channel" := "#"+channel, "text" := msg, "username" := cfg.username)
    val request = Request[IO](POST, cfg.webhook).withBody(json)
    client.expect[String](request).map(_ => ())
  }
}


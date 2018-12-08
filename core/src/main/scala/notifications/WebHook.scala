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

sealed abstract class WebHookOp[A] extends Product with Serializable

object WebHookOp {

  type WebHookOpF[A] = Free[WebHookOp, A]

  final case class SendWebHookNotification(subscribers: List[WebHookSubscription], ev: NotificationEvent) extends WebHookOp[Unit]

  def send(subscribers: List[WebHookSubscription], ev: NotificationEvent): WebHookOpF[Unit] =
    Free.liftF(SendWebHookNotification(subscribers, ev))
}

final class WebHookHttp(client: Client[IO]) extends (WebHookOp ~> IO) {
  import argonaut._, Argonaut._
  import WebHookOp._

  def apply[A](op: WebHookOp[A]): IO[A] = op match {
    case SendWebHookNotification(subscriptions, ev) =>
      subscriptions.traverse_(s => send(s, ev))
  }

  def send(s: WebHookSubscription, ev: NotificationEvent): IO[Unit] =
    client.expect[String](Request[IO](
      method = POST,
      uri = s.params.foldLeft(s.uri)((u, p) => u.withQueryParam(p._1, p._2)),
      headers = s.headers)
    .withBody(ev.asJson)).void
}

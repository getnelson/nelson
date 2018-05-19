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

sealed abstract class EmailOp[A] extends Product with Serializable

object EmailOp {
  type EmailOpF[A] = Free[EmailOp, A]

  final case class SendEmailNotification(
    recipients: List[EmailAddress],
    message: String,
    subject: String
  ) extends EmailOp[Unit]

  def send(rs: List[EmailAddress], msg: String, s: String): EmailOpF[Unit] =
    Free.liftF(SendEmailNotification(rs, msg,s))
}

final class EmailServer(cfg: EmailConfig) extends (EmailOp ~> IO) {
  import org.apache.commons.mail.{Email=>JEmail,SimpleEmail}
  import EmailOp._

  def apply[A](op: EmailOp[A]): IO[A] = op match {
    case SendEmailNotification(r,m,s) =>
      send(cfg.from,r,m,s)
  }

  def send(from: EmailAddress, recipients: List[EmailAddress], msg: String, subject: String): IO[Unit] =
    IO {
      val email = client
      email.setFrom(from)
      recipients.foreach(email.addTo)
      email.setSubject(subject)
      email.setMsg(msg)
      email.send()
      ()
    }

  def client: JEmail = {
    val email = new SimpleEmail
    email.setHostName(cfg.host)
    email.setSmtpPort(cfg.port)
    email.setAuthenticator(cfg.auth)
    email.setSSLOnConnect(cfg.useSSL)
  }
}

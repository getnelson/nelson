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

import org.http4s._

sealed trait NotificationSubscription
final case class SlackSubscription(channel: SlackChannel) extends NotificationSubscription
final case class EmailSubscription(recipient: EmailAddress) extends NotificationSubscription
final case class WebHookSubscription(uri: Uri, headers: Headers, params: List[(String, String)]) extends NotificationSubscription

final case class NotificationSubscriptions(slack: List[SlackSubscription], email: List[EmailSubscription], webhook: List[WebHookSubscription])

object NotificationSubscriptions {
  val empty: NotificationSubscriptions = NotificationSubscriptions(Nil,Nil,Nil)
}

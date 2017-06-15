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
package audit

import journal.Logger
import scalaz.concurrent.Task
import scalaz.stream.{Process,Sink,sink}
import scalaz.stream.async.mutable.{Queue}
import scalaz.{\/, -\/, \/-, ~>, Kleisli}
import scalaz.syntax.functor._
import Nelson._
import argonaut._
import storage.StoreOp

class Auditor(queue: Queue[AuditEvent[_]], defaultLogin: String) {

  private[this] val logger = Logger[Auditor]

  @SuppressWarnings(Array("org.brianmckenna.wartremover.warts.IsInstanceOf"))
  private def logSink: Sink[Task, AuditEvent[_]] =
    sink.lift {
      case AuditEvent(t: Throwable, _, _, _, login, _)  =>
        Task.delay(logger.error(s"[fatal] audit error event ${t.getMessage} by user ${login}"))
      case a =>
        Task.delay(logger.info(s"[info] audit event ${a.event} action ${a.action} by user ${a.userLogin}"))
    }

  private def persist(stg: (StoreOp ~> Task)): Sink[Task, AuditEvent[_]] =
    sink.lift[Task,AuditEvent[_]](a =>
      nelson.storage.run(stg, nelson.storage.StoreOp.audit(a).void))

  def auditSink[A](action: AuditAction)(implicit au: Auditable[A]): Sink[Task, A] =
    sink.lift(a => write(a, action)(au))

  def errorSink: Sink[Task, Throwable] =
    sink.lift[Task,Throwable](t => Task.delay(logger.error(t.getMessage))) // possibly truncate

  def write[A](a: A, action: AuditAction, releaseId: Option[Long] = None, login: String = defaultLogin)(implicit au: Auditable[A]): Task[Unit] =
    queue.enqueueOne(AuditEvent(a, action, releaseId, login))

  def process(stg: (StoreOp ~> Task)): Process[Task, Unit] =
    queue.dequeue observe persist(stg) to logSink
}

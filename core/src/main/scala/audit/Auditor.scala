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

import nelson.storage.StoreOp

import cats.~>
import cats.effect.IO
import cats.implicits._

import fs2.{Sink, Stream}
import fs2.async.mutable.Queue

import journal.Logger

import scala.concurrent.ExecutionContext

class Auditor(queue: Queue[IO, AuditEvent[_]], defaultLogin: String) {

  private[this] val logger = Logger[Auditor]

  @SuppressWarnings(Array("org.brianmckenna.wartremover.warts.IsInstanceOf"))
  private def logSink: Sink[IO, AuditEvent[_]] =
    Sink {
      case AuditEvent(t: Throwable, _, _, _, login, _)  =>
        IO(logger.error(s"[fatal] audit error event ${t.getMessage} by user ${login}"))
      case a =>
        IO(logger.info(s"[info] audit event ${a.event} action ${a.action} by user ${a.userLogin}"))
    }

  private def persist(stg: StoreOp ~> IO): Sink[IO, AuditEvent[_]] =
    Sink { a =>
      storage.StoreOp.audit(a).void.foldMap(stg).recoverWith {
        case t => IO(logger.error(s"[fatal] audit error while persisting event ${t.getMessage}"))
      }
    }

  def auditSink[A](action: AuditAction)(implicit au: Auditable[A]): Sink[IO, A] =
    Sink(a => write(a, action)(au))

  def errorSink: Sink[IO, Throwable] =
    Sink(t => IO(logger.error(t.getMessage))) // possibly truncate

  def write[A](a: A, action: AuditAction, releaseId: Option[Long] = None, login: String = defaultLogin)(implicit au: Auditable[A]): IO[Unit] =
    queue.enqueue1(AuditEvent(a, action, releaseId, login))

  def process(stg: (StoreOp ~> IO))(implicit ec: ExecutionContext): Stream[IO, Unit] =
    (queue.dequeue.observe(persist(stg))).attempt.collect { case Right(a) => a }.to(logSink)
}

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
package logging

import cats.free.Free

sealed abstract class LoggingOp[A] extends Product with Serializable

object LoggingOp {

  final case class Info(msg: String) extends LoggingOp[Unit]

  final case class Debug(msg: String) extends LoggingOp[Unit]

  final case class LogToFile(id: ID, msg: String) extends LoggingOp[Unit]

  type LoggingF[A] = Free[LoggingOp, A]

  def debug(msg: String): LoggingF[Unit] =
    Free.liftF(Info(msg))

  def info(msg: String): LoggingF[Unit] =
    Free.liftF(Info(msg))

  def logToFile(id: ID, msg: String): LoggingF[Unit] =
    Free.liftF(LogToFile(id,msg))
}


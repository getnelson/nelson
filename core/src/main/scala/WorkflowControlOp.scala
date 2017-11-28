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

import scalaz.{Free,~>}
import scalaz.concurrent.Task


/*
 * Basic operations for workflow control
 */
sealed abstract class WorkflowControlOp[+A]

object WorkflowControlOp {
  final case class Pure[A](a: () => A) extends WorkflowControlOp[A]

  final case class Failure(t: Throwable) extends WorkflowControlOp[Nothing]

  type WorkflowControlF[A] = Free.FreeC[WorkflowControlOp, A]

  def fail[A](t: Throwable): WorkflowControlF[A] =
    Free.liftFC(Failure(t))

  def pure[A](a: => A): WorkflowControlF[A] =
    Free.liftFC(Pure(a _))

  val trans: (WorkflowControlOp ~> Task) =
    new (WorkflowControlOp ~> Task) {
      def apply[A](op: WorkflowControlOp[A]) = op match {
        case Pure(a) =>
          Task.delay(a())
        case Failure(t) =>
          Task.fail(t)
      }
    }
}

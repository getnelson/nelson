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

import ca.mrvisser.sealerate

import cats.data.NonEmptyList
import cats.syntax.list._

sealed abstract class TaskStatus extends Product with Serializable

trait TaskName

object TaskStatus {
  import argonaut.DecodeJson

  def fromString(str: String): TaskStatus =
    stringToTaskStatus.getOrElse(str.toLowerCase.trim, Unknown)

  case object Pending extends TaskStatus {
    override def toString = "pending"
  }

  case object Running extends TaskStatus {
    override def toString = "running"
  }

  case object Dead extends TaskStatus {
    override def toString = "dead"
  }

  case object Unknown extends TaskStatus {
    override def toString = "unknown"
  }

  val all: Set[TaskStatus] = sealerate.values[TaskStatus]

  val nel: NonEmptyList[TaskStatus] = all.toList.toNel.yolo("there should be at least one TaskStatus")

  private val stringToTaskStatus: Map[String, TaskStatus] =
    all.map(x => x.toString -> x).toMap

  implicit val taskStatusDecoder: DecodeJson[TaskStatus] =
    DecodeJson.StringDecodeJson.map(fromString)
}

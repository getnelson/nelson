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

import scalaz.{==>>, @@, Order, Tag}

final case class TaskGroupAllocation(
  id: String,
  name: String,
  jobId: String,
  taskGroup: String,
  tasks: Map[String @@ TaskName,(TaskStatus, List[TaskEvent])]
)

final case class TaskEvents(taskStatus: TaskStatus, taskEvents: List[TaskEvent])

object TaskGroupAllocation {

  def build(id: String, name: String, jobID: String, taskGroup: String, taskEvents: Map[String, TaskEvents]) =
    TaskGroupAllocation(id, name, jobID, taskGroup,
      taskEvents.toList.map {
        case (n, e) => Tag[String, TaskName](n) -> (e.taskStatus -> e.taskEvents)
      }.groupBy(_._1).mapValues(_.headOption.map(_._2).getOrElse(TaskStatus.Unknown -> Nil)))
}
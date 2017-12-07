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

import alerts._
import helm.ConsulOp
import nelson.Domain.StackName
import nelson.test._
import org.scalatest.prop.Checkers
import scalaz.concurrent.Task
import scalaz.concurrent.Task.now
import Manifest._
import org.scalatest.{FlatSpec,Matchers,BeforeAndAfterAll}

class AlertSpec extends FlatSpec
    with Matchers
    with RoutingFixtures
    with BeforeAndAfterAll
  with Checkers {

  val alerting = Alerting(
    PrometheusConfig(
      List(
        PrometheusAlert("instance_down", """IF up == 0 FOR 5m ANNOTATIONS { foo="bar" }"""),
        PrometheusAlert("average_latency", """IF average_latency > 1 FOR 1m ANNOTATIONS { foo="bar" }""")),
      List(PrometheusRule("average_latency", "avg(latency)"))))
  val optOuts = Map(
    "qa" -> List(AlertOptOut("average_latency")),
    "dev" -> Nil
  )

  val stackName = StackName("howdy-http", Version(0, 2, 3), "abcd1234")

  val nsRef = "qa"
  val alertKey = alertingKey(stackName)
  val unit = UnitDef("http", "", Map.empty, Set.empty, alerting, Magnetar, None, None, Set.empty)

  "alertingKey" should "be v2/:stackName" in {
    alertKey should equal ("nelson/alerting/v2/howdy-http--0-2-3--abcd1234")
  }

  val I = Interpreter.prepare[ConsulOp, Task]

  "writeToConsul" should "write when not opted out" in {
    val interp = for {
      r <- I.expect[Option[String], Unit] {
        case ConsulOp.Set(alertKey, rules) =>
          Some(rules) -> now(())
      }
    } yield r
    interp.run(writeToConsul(stackName, NamespaceName("dev"), "default-plan", unit, optOuts("dev"))).run should equal (Some(
      """average_latency = avg(latency)
        |
        |ALERT instance_down
        |IF up == 0 FOR 5m ANNOTATIONS { foo="bar" }
        |
        |ALERT average_latency
        |IF average_latency > 1 FOR 1m ANNOTATIONS { foo="bar" }
        |
        |""".stripMargin))
  }

  it should "only write what is not opted out" in {
    val interp = for {
      r <- I.expect[Option[String], Unit] {
        case ConsulOp.Set(alertKey, rules) =>
          Some(rules) -> now(())
      }
    } yield r
    interp.run(writeToConsul(stackName, NamespaceName("qa"), "default-plan", unit, optOuts("qa"))).run should equal (Some(
      """average_latency = avg(latency)
        |
        |ALERT instance_down
        |IF up == 0 FOR 5m ANNOTATIONS { foo="bar" }
        |
        |""".stripMargin))
  }

  "deleteFromConsul" should "delete the value at the key" in {
    val interp = for {
      _ <- I.expectU[Unit] {
        case ConsulOp.Delete(alertKey) =>
          now(())
      }
    } yield ()
    interp.run(deleteFromConsul(stackName)).run should equal (())
  }
}

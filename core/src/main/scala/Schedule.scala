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

import cats.implicits._

/*
 * A schedule is defined by an interval.
 * An interval can be one of:
 *   1) a predefined set (monthly, daily, hourly, quarter-hourly, once) or
 *   2) a cron expression
 */
final case class Schedule(interval: Schedule.Interval) {

  import Schedule._

  // Cron expressions for different intervals.
  def toCron(): Option[String] =
    interval match {
      case Monthly       => Some("@monthly")
      case Daily         => Some("@daily")
      case Hourly        => Some("@hourly")
      case QuarterHourly => Some("*/15 * * * *")
      case Cron(e)       => Some(e) // a pure cron expression, so just use it
      case Once          => None // can't do it in cron
    }
}

object Schedule {

  /* Represents an interval of: monthly, daily, hourly, quarter-hourly, once, or an arbitrary cron expression */
  sealed abstract class Interval(val asString: String) extends Product with Serializable
  final case object Monthly extends Interval("monthly")
  final case object Daily extends Interval("daily")
  final case object Hourly extends Interval("hourly")
  final case object QuarterHourly extends Interval("quarter-hourly")
  final case object Once extends Interval("once")
  final case class Cron(exp: String) extends Interval(exp)

  def parse(input: String): Either[String, Schedule] =
    preDefined.find(_.asString == input.toLowerCase).fold(parseCron(input).map(cron => Schedule(cron))) { i =>
      Right(Schedule(i))
    }

  private val preDefined = Set(Monthly, Daily, Hourly, QuarterHourly, Once)

  private def parseCron(str: String): Either[String, Cron] = {
    import com.cronutils.model.definition.CronDefinitionBuilder
    import com.cronutils.parser.CronParser
    import com.cronutils.model.CronType.UNIX
    Either.catchNonFatal {
      val definition = CronDefinitionBuilder.instanceDefinitionFor(UNIX)
      val parser = new CronParser(definition)
      parser.parse(str)
    }.bimap(e => e.getMessage, _ => Cron(str))
  }
}

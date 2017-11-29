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

import scala.concurrent.duration.FiniteDuration
import java.time.Instant
import spire.math.Rational
import ca.mrvisser.sealerate

/*
 * Defines a policy for shifing traffic from one deployment
 * to another. A policy is a function from (start, timestamp, duration) => Double
 * where start marks the begining of the shift, the timestamp is the current
 * place in time, and duration is the total length of the shift.
 * The return value is the weight of the from target between 0.0 and 1.0
 *
 * Note: Every policy is responsible for returning a valid value when the timestamp
 * is out of range of the start and end.
 */
sealed trait TrafficShiftPolicy {
  def ref: String

  def run(start: Instant, timeStamp: Instant, duration: FiniteDuration): Double

  def end(start: Instant, duration: FiniteDuration): Instant = start.plusSeconds(duration.toSeconds)
}

object LinearShiftPolicy extends TrafficShiftPolicy {
  val ref: String = "linear"

  def run(start: Instant, timeStamp: Instant, duration: FiniteDuration): Double =
    if (timeStamp.isBefore(start)) 1D
    else if (timeStamp.isAfter(end(start,duration))) 0D
    else 1D - Rational(timeStamp.toEpochMilli - start.toEpochMilli, duration.toMillis).toDouble
}

object AtomicShiftPolicy extends TrafficShiftPolicy {
  val ref: String = "atomic"

  def run(start: Instant, timeStamp: Instant, duration: FiniteDuration): Double =
    if (timeStamp.isBefore(end(start,duration))) 1D
    else 0D
}

object TrafficShiftPolicy {

  val policies: Set[TrafficShiftPolicy] = sealerate.values[TrafficShiftPolicy]

  def fromString(ref: String): Option[TrafficShiftPolicy] =
    policies.find(_.ref == ref)
}

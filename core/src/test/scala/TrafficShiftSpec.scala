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

import org.scalacheck._, Prop._
import concurrent.duration._
import java.time.Instant

object TrafficShiftPropertySpec extends Properties("TrafficShift") {
  import Fixtures._
  import Datacenter.TrafficShift

  property("fromValue") = forAll { (ts: TrafficShift) =>
    val ts2 = ts.copy(reverse = None) // remove reverse
    ("verify value is between 0 and 100" |: {
      val value = ts.fromValue
      value >= 0D && value <= 1D
    })
  }
  property("fromValue reverse") = forAll { (ts: TrafficShift) =>
    ("verify value is between 0 and 100" |: {
      val value = ts.fromValue
      value >= 0D && value <= 1D
    })
  }
}

import org.scalatest.{FlatSpec,Matchers}
import java.time.Instant
import scala.concurrent.duration._

class TrafficShiftSpec extends FlatSpec with Matchers {

  behavior of "traffic shift policy"

  it should "return a valid value if timestamp is before start time" in {
    val now = Instant.now
    val timestamp = now.minusSeconds(100)
    LinearShiftPolicy.run(now, timestamp, 1.hour) should equal (1.0)
  }

  it should "return a valid value if timestamp is after end" in {
    val now = Instant.now
    val dur = 1.hour
    val timestamp = Instant.ofEpochMilli(now.toEpochMilli + dur.toMillis + 100)
    LinearShiftPolicy.run(now, timestamp, dur) should equal (0.0)
  }

  it should "return a valid value if timestamp is between start & end" in {
    val now = Instant.now
    val timestamp = now.plusSeconds(100)
    val dur = 1.hour
    val value = LinearShiftPolicy.run(now, timestamp, dur)
    (value <= 1.0) should be(true)
    (value >= 0.0) should be(true)
  }

  import Datacenter._

  val ns = Datacenter.Namespace(0L, NamespaceName("dev"), "dev")

  val d1 = Deployment(0L, DCUnit(0L, "foo", Version(1,1,1),"",Set.empty,Set.empty,Set.empty),
            "hash", ns, Instant.now, "magnetar", "default", "guid", "retain-latest")
  val d2=  Deployment(1L, DCUnit(0L, "foo", Version(1,1,2),"",Set.empty,Set.empty,Set.empty),
            "hash", ns, Instant.now, "magnetar", "default", "guid", "retain-latest")

  behavior of "traffic shift inProgress"

  it should "be in progress if timestamp is between start and end" in {
    val start = Instant.now.minusSeconds(100)
    val timestamp = start.plusSeconds(100)
    val ts = TrafficShift(d1,d2,LinearShiftPolicy,start,1.hour,None)
    ts.inProgress(timestamp) should equal (true)
  }

  it should "not be in progress if timestamp is before start" in {
    val start = Instant.now.minusSeconds(100)
    val timestamp = start.minusSeconds(100)
    val ts = TrafficShift(d1,d2,LinearShiftPolicy,start,1.hour,None)
    ts.inProgress(timestamp) should equal (false)
  }

  behavior of "traffic shift inProgress reverse"

  it should "be in progress if timestamp is between start time & start time + reverse start when in reverse" in {
    val start = Instant.now.minusSeconds(100)
    val reverse = start.plusSeconds(100)
    val timestamp = reverse.plusSeconds(50)
    val ts = TrafficShift(d1,d2,LinearShiftPolicy,start,1.hour,Some(reverse))
    ts.inProgress(timestamp) should equal (true)
  }

  it should "be in progress even if timestamp is after the original traffic shift end but before the end of the reverse" in {
    val start = Instant.now.minusSeconds(100)
    val ts = TrafficShift(d1,d2,LinearShiftPolicy,start,1.hour,None)
    val reverse = ts.end.minusSeconds(1) // reverse just before end
    val timestamp = ts.end.plusSeconds(50) // after end but within range for shift back
    val ts2 = ts.copy(reverse = Some(reverse))
    ts2.inProgress(timestamp) should equal (true)
  }

  it should "not be in progress after reverse is finished" in {
    val start = Instant.now.minusSeconds(100)
    val ts = TrafficShift(d1,d2,LinearShiftPolicy,start,1.hour,None)
    val reverseDuration = (ts.duration.toSeconds/2).toLong // reverse halfway
    val reverse = ts.start.plusSeconds(reverseDuration)
    val ts2 = ts.copy(reverse = Some(reverse))

    val timestamp1 = reverse.plusSeconds(reverseDuration - 1) // instant before reverse ends
    ts2.inProgress(timestamp1) should equal (true)

    val timestamp2 = reverse.plusSeconds(reverseDuration) // reverse ends
    ts2.inProgress(timestamp2) should equal (false)
  }
}

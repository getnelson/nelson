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

object ScheduleSpec extends Properties("Schedule"){
  import Fixtures._
  import Schedule._

  implicit lazy val arbSChedule: Arbitrary[Schedule] = Arbitrary(genSchedule)
  implicit lazy val arbInterval: Arbitrary[Interval] = Arbitrary(genInterval)

  property("parse") = forAll { (i: Interval) =>
    Schedule.parse(i.asString).isRight == true
  }

  property("cron") = forAll { (s: Schedule) =>
    (if (s.interval != Schedule.Once) s.toCron.isDefined else s.toCron.isEmpty) == true
  }
}

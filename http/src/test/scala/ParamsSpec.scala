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

import org.scalatest._, Matchers._

class ParamsSpec extends FlatSpec {

  import DeploymentStatus._

  it should "yield all deployment statuses given the string 'all'" in {
    plans.Params.commaSeparatedStringToStatus("all") should equal (DeploymentStatus.all.toList)
  }

  it should "correctly split comma-delimited statuses" in {
    plans.Params.commaSeparatedStringToStatus("ready,terminated"
      ) should equal ( Ready :: Terminated :: Nil )

    plans.Params.commaSeparatedStringToStatus("deprecated,failed"
      ) should equal ( Deprecated :: Failed :: Nil )
  }

}
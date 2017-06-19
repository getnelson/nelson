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

import org.scalatest.prop.Checkers

class BedazzledStringSpec extends NelsonSuite with Checkers {
  behavior of "toSnakeCase"

  it should "split and lowercase camel-cased words" in {
    "camelCased".toSnakeCase should equal ("camel_cased")
  }

  it should "split camel-cased words with embedded acronyms" in {
    "URLEncoder".toSnakeCase should equal ("url_encoder")
  }

  it should "collapse spaces" in {
    "this__ is \t st00pid but it works".toSnakeCase should equal ("this_is_st00pid_but_it_works")
  }

  behavior of "withTrailingSlash"

  it should "always start with the original" in check { s: String =>
    s.withTrailingSlash.startsWith(s)
  }

  it should "always end in a slash" in check { s: String =>
    s.withTrailingSlash.endsWith("/")
  }
}

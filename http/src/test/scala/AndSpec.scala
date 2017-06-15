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

class AndSpec extends NelsonSuite {
  sealed case class Foo(bar: String, baz: Int)

  "&.unapply" should "extract both sides if it matches" in {
    Foo("forty-two", 42) match {
      case Foo("forty-two", _) & Foo(_, 42) => ()
      case _ => fail("should have matched")
    }
  }

  it should "fail if the left side doesn't match" in {
    Foo("forty-two", 42) match {
      case Foo("twenty-four", _) & Foo(_, 42) => fail("should not have matched")
      case _ => ()
    }
  }

  it should "fail if the right side doesn't match" in {
    Foo("forty-two", 42) match {
      case Foo("forty-two", _) & Foo(_, 24) => fail("should not have matched")
      case _ => ()
    }
  }
}

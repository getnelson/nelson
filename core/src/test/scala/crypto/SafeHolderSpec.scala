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
package crypto

import autharbitrary.AuthArbitrary._
import autharbitrary.ArbFunction0._

class SafeHolderSpec extends AuthSpec {
  property("get on an empty holder is None"){
    forAll { (holder: SafeHolder[Int]) =>
      holder.get should ===(None)
    }
  }

  property("get returns Some after a put"){
    forAll { (value: Int, holder: SafeHolder[Int]) =>
      holder.put(value)
      holder.get should ===(Some(value))
    }
  }

  property("get returns None after a remove"){
    forAll { (value: Int, holder: SafeHolder[Int]) =>
      holder.put(value)
      holder.remove
      holder.get should ===(None)
    }
  }

  property("getOrCreate uses a cached value if present"){
    forAll { (value: Int, holder: SafeHolder[Int], f: () => Int) =>
      holder.put(value)
      holder.getOrCreate(f) should ===(value)
    }
  }

  property("getOrCreate creates and caches if necessary"){
    forAll { (value: Int, holder: SafeHolder[Int]) =>
      val created = holder.getOrCreate(() => value)
      created should ===(value)
      holder.get should ===(Some(value))
    }
  }

  property("getOrCreate doesn't evaluate creation unless necessary"){
    forAll { (value1: Int, value2: Int, holder: SafeHolder[Int]) =>
      var evaluated = false
      holder.put(value1)
      val _ = holder.getOrCreate{ () =>
        evaluated = true
        value2
      }
      evaluated should ===(false)
    }
  }
}

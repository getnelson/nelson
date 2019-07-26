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

object NamespaceNameSpec extends Properties("NamespaceNameSpec") {

  property("roundtrip") = forAll(Fixtures.genNamespaceNameStr) { (name: String) =>
    val ns = NamespaceName.fromString(name)
    val str = ns.map(_.asString)
    str == Right(name)
  }

  property("subordinate") = forAll(Fixtures.genNamespaceNameStr) { (name: String) =>
    val ns = NamespaceName.fromString(name).toOption.get
    val sub1 = NamespaceName.fromString(s"$name/sanbox").toOption.get
    val sub2 = NamespaceName.fromString(s"$name/sanbox/foo").toOption.get

    ns.isSubordinate(sub1) == true
    ns.isSubordinate(sub2) == true
    sub1.isSubordinate(sub2) == true

    ns.isSubordinate(ns) == false
    sub1.isSubordinate(ns) == false
    sub1.isSubordinate(ns) == false

    //  TODO move these to scalatest
    NamespaceName("dev", List("sanbox")).isSubordinate(NamespaceName("dev", List("sanbox2"))) == false
  }

  property("hierarchy") = forAll(Fixtures.genNamespaceNameStr) { (name: String) =>
    val ns = NamespaceName.fromString(s"$name/sanbox/foo").toOption.get
    val h = ns.hierarchy

    h.head == ns.root
    h.last == ns
  }

}

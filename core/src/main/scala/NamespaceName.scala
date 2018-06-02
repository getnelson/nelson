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

import cats.data.NonEmptyList
import scala.annotation.tailrec
import cats.Order
import cats.instances.string._

/*
 * Represents a fully qualified namespace path, where 'dev' is the parent
 * to 'dev/sandbox'. When represented as a string it is delimited by '/'.
 * A NamespaceName is considered a root when it has no parent
 */
final case class NamespaceName(private val nel: NonEmptyList[String]) {
  def root: NamespaceName = NamespaceName(nel.head)
  def isRoot: Boolean = nel.tail.isEmpty
  def parent: Option[NamespaceName] = {
    if (isRoot) None
    else Some(NamespaceName(nel.head, nel.tail.dropRight(1)))
  }
  def asString: String = nel.tail.foldLeft(nel.head)((a,b) => s"$a/$b")

  def isSubordinate(other: NamespaceName): Boolean = {

    @tailrec
    def go(cur: NamespaceName): Boolean =
      if (cur == this) true
      else cur.parent match {
        case Some(p) => go(p)
        case None => false
      }

    other.parent.fold(false)(go)
  }

  /*
   * Returns and ordered list of NamespaceName starting at the root
   */
  def hierarchy: List[NamespaceName] = {

    @tailrec
    def go(cur: NamespaceName, res: List[NamespaceName]): List[NamespaceName] = {
      cur.parent match {
        case Some(p) => go(p, cur :: res)
        case None => cur :: res
      }
    }

    go(this, Nil)
  }
}

object NamespaceName {

  def apply(root: String): NamespaceName = NamespaceName(NonEmptyList.of(root))

  def apply(root: String, rest: List[String]): NamespaceName = NamespaceName(NonEmptyList.of(root, rest: _*))

  private val alphaNumHyphen = """[a-z][a-z-/]*[a-z]""".r
  def fromString(str: String): Either[InvalidNamespaceName, NamespaceName] = {
    if (!alphaNumHyphen.pattern.matcher(str).matches || str.contains("//"))
      Left(InvalidNamespaceName(str))
    else {
      val sp = str.split('/')
      val len = sp.length
      if (len == 0)
        Left(InvalidNamespaceName(str))
      else {
        val root = sp.head // length checked above
        val tail = sp.toList.takeRight(len - 1)
        Right(NamespaceName(root, tail))
      }
    }
  }

  def fromList(ls: List[String]): Either[InvalidNamespaceName, NamespaceName] =
    fromString(ls.mkString("/"))

  implicit val NamespaceNameOrder: Order[NamespaceName] =
    Order.by(_.asString)

  implicit val NamespaceNameordering: Ordering[NamespaceName] =
    NamespaceNameOrder.toOrdering
}

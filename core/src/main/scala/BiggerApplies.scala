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

import scalaz._

// Triskaidekaphobia
object BiggerApplies {
  implicit class MoreApplies[F[_]](val F: Apply[F]) {
    def apply14[A, B, C, D, E, FF, G, H, I, J, K, L, M, N, R]
      (fa: => F[A], fb: => F[B], fc: => F[C], fd: => F[D],
        fe: => F[E], ff: => F[FF], fg: => F[G],
        fh: => F[H], fi: => F[I], fj: => F[J],
        fk: => F[K], fl: => F[L], fm: => F[M], fn: => F[N])(f: (A, B, C, D, E, FF, G, H, I, J, K, L, M, N) => R)
      (implicit F: Apply[F]): F[R] = {
      import F._
      apply4(tuple4(fa, fb, fc, fd), tuple3(fe, ff, fg), tuple3(fh, fi, fj), tuple4(fk, fl, fm, fn))((t, t2, t3, t4) => f(t._1, t._2, t._3, t._4, t2._1, t2._2, t2._3, t3._1, t3._2, t3._3, t4._1, t4._2, t4._3, t4._4))
    }
  }
}

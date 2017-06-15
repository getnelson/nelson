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

import java.security.SecureRandom
import scodec.bits.ByteVector

class Nonce private (long0: Long, long1: Long) {
  def toBytes: ByteVector =
    ByteVector.fromLong(long0) ++ ByteVector.fromLong(long1)
}

object Nonce {
  def fromLongs(long0: Long, long1: Long): Nonce =
    new Nonce(long0, long1)

  def fromSecureRandom(rng: SecureRandom): Nonce =
    fromLongs(rng.nextLong, rng.nextLong)
}

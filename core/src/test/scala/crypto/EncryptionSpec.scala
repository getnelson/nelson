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
import scodec.bits.ByteVector

class EncryptionSpec extends AuthSpec {
  property("encryption round trip"){
    forAll { (originalData: ByteVector, iv: InitializationVector, env: AuthEnv) =>
      val decrypted = for {
        encrypted <- env.encryptor.encrypt(originalData, env.encryptionKey, iv)
        res <- env.decryptor.decrypt(encrypted, env.encryptionKey, iv)
      } yield res

      decrypted should ===(Right(originalData))
    }
  }

  property("decrypt should still work after previous decrypt error"){
    forAll { (originalData: ByteVector, iv: InitializationVector, env: AuthEnv) =>
      val encryptionKey = env.encryptionKey
      val encrypted = env.encryptor.encrypt(originalData, encryptionKey, iv).toOption.get // YOLO
      val modified = ByteVector("garbage".getBytes)
      env.decryptor.decrypt(modified, encryptionKey, iv).swap.map(_.isInstanceOf[AuthFailure.EncryptionError]) should ===(Right(true))
      env.decryptor.decrypt(encrypted, encryptionKey, iv) should ===(
        Right(originalData))
    }
  }

  property("reusing the same initialization vector yiels the same encryption"){
    forAll { (originalData: ByteVector, iv: InitializationVector, env: AuthEnv) =>
      val encryptionKey = env.encryptionKey
      val encrypted1 = env.encryptor.encrypt(originalData, encryptionKey, iv).toOption.get // YOLO
      val encrypted2 = env.encryptor.encrypt(originalData, encryptionKey, iv).toOption.get // YOLO
      encrypted1 should ===(encrypted2)
    }
  }

  property("different initialization vectors yield different encrypted values"){
    forAll { (originalData: ByteVector, iv1: InitializationVector, iv2: InitializationVector, env: AuthEnv) =>
      whenever(iv1.bytes != iv2.bytes) {
        val encryptionKey = env.encryptionKey
        val encrypted1 = env.encryptor.encrypt(originalData, encryptionKey, iv1).toOption.get // YOLO
        val encrypted2 = env.encryptor.encrypt(originalData, encryptionKey, iv2).toOption.get // YOLO
        encrypted1 should !==(encrypted2)
        // they should still decrypt to the same value
        val decrypted1 = env.decryptor.decrypt(encrypted1, encryptionKey, iv1).toOption.get // YOLO
        val decrypted2 = env.decryptor.decrypt(encrypted2, encryptionKey, iv2).toOption.get // YOLO
        decrypted1 should ===(decrypted2)
      }
    }
  }
}

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
package vault
package http4s

import argonaut._, Argonaut._
import argonaut.DecodeResultCats._

import cats.instances.stream._
import cats.syntax.foldable._

import scala.collection.immutable.SortedMap

trait Json {
  import Vault._

  implicit val encodeRule: EncodeJson[Rule] = EncodeJson { r =>
    jEmptyObject
  }

  implicit val enodeCreatePolicyJson: EncodeJson[CreatePolicy] = EncodeJson { cp =>
    def capabilities(rule: Rule) =
      rule.capabilities match {
        case Nil => None
        case cs => Some(cs)
      }

    def path(rule: Rule) =
      (rule.path := {
        ("policy" :?= rule.policy) ->?:
        ("capabilities" :?= capabilities(rule)) ->?:
        jEmptyObject
      })

    jSingleObject("rules",
      jString( // So, yeah, rules is embedded HCL/JSON as a string.
        jSingleObject("path", jObjectFields(cp.rules.map(path): _*)).nospaces))
  }

  implicit val jsonInitialized: DecodeJson[Initialized] = DecodeJson { c =>
    for {
      i <- (c --\ "initialized").as[Boolean]
    } yield Initialized(i)
  }

  def jsonMount(path: String): DecodeJson[Mount] = DecodeJson { c =>
    for {
      defaultLease <- (c --\ "config" --\ "default_lease_ttl").as[Int]
      maxLease <- (c --\ "config" --\ "max_lease_ttl").as[Int]
      tipe <- (c --\ "type").as[String]
      desc <- (c --\ "description").as[String]
    } yield Mount(path, tipe, desc, defaultLease, maxLease)
  }

  implicit val jsonMountMap: DecodeJson[SortedMap[String, Mount]] = DecodeJson { c =>
    def go(obj: JsonObject): DecodeResult[SortedMap[String, Mount]] =
      obj.toMap.toStream.foldLeftM[DecodeResult, SortedMap[String, Mount]](SortedMap.empty) {
        // mounts end with '/'. Starting circa vault-0.6.2, this response includes keys that aren't mounts. */ =>
        case (res, (jf,js)) if jf.endsWith("/") =>
          jsonMount(jf).decodeJson(js).flatMap(m => DecodeResult.ok(res + (jf -> m)))
        case (res, _) =>
          DecodeResult.ok(res)
      }

    c.focus.obj.fold[DecodeResult[SortedMap[String, Mount]]](DecodeResult.fail("expected mounts to be a JsonObject", c.history))(go)
  }

  implicit val jsonRootToken: DecodeJson[RootToken] = implicitly[DecodeJson[String]].map(RootToken.apply)

  implicit val jsonInitialCreds: DecodeJson[InitialCreds] = DecodeJson[InitialCreds] { c =>
    for {
      k <- (c --\ "keys").as[List[MasterKey]]
      t <- (c --\ "root_token").as[RootToken]
    } yield InitialCreds(k,t)
  }

  implicit val jsonInitialization: CodecJson[Initialization] = casecodec2(Initialization.apply,Initialization.unapply)("secret_shares", "secret_threshold")

  implicit val jsonSealStatus: DecodeJson[SealStatus] = casecodec4(SealStatus.apply, SealStatus.unapply)("sealed", "n", "t", "progress")

  val jsonUnseal: EncodeJson[String] = EncodeJson { s =>
    ("key" := s) ->: jEmptyObject
  }

  implicit val jsonCreateToken: EncodeJson[CreateToken] = EncodeJson { ct =>
    ("policies" :?= ct.policies) ->?:
    ("renewable" := ct.renewable) ->:
    ("ttl" :?= ct.ttl.map(d => s"${d.toMillis}ms")) ->?:
    ("num_uses" := ct.numUses) ->:
    jEmptyObject
  }
}

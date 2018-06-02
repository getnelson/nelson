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

import java.time.Instant

/**
 * this is the data that we store (encrypted) into the
 * cookie stored on users browsers.
 */
final case class Session(
  expiry: Instant,
  github: AccessToken,
  user: User
)

object Session { session =>
  import java.net.URI
  import scodec._, codecs._
  import crypto._, protocol._

  val currentVersion: TokenVersion = TokenVersion(3, 0, 0)

  val nonGreedyString = variableSizeBytes(uint8, utf8)

  val uriCodec: Codec[URI] = nonGreedyString.xmap[URI](
    str => new URI(str),
    uri => uri.toString
  )

  val organizationCodec: Codec[Organization] =
    (uint32 ::
     optional(bool, nonGreedyString) ::
     nonGreedyString ::
     uriCodec
    ).as[Organization]

  val userCodec: Codec[User] =
    (nonGreedyString ::
     uriCodec ::
     nonGreedyString ::
     optional(bool, nonGreedyString) ::
     listOfN(uint16, organizationCodec)
    ).as[User]

  val instantCodec: Codec[Instant] = (int64 ~ int32).xmap(
    { case second ~ nano => Instant.ofEpochSecond(second, nano.toLong) },
    instant => (instant.getEpochSecond, instant.getNano))

  val accessTokenCodec: Codec[AccessToken] =
    (nonGreedyString).as[AccessToken]

  def sessionCodecV3(
    authEnv: AuthEnv,
    key: EncryptionKey
  ): Codec[Session] = (
    EncryptedCodec.fromAuthEnv(
      authEnv, key,
      (("instant" | instantCodec) ::
       ("accesstoken" | accessTokenCodec) ::
       ("user" | userCodec)))
  ).as[Session]

  def versionedSessionToken(authEnv: AuthEnv, key: EncryptionKey): Codec[Session] =
    AuthCodecs.versioned(currentVersion){
      case TokenVersion(3, 0, _) => sessionCodecV3(authEnv, key)
    }

  def signedSessionTokenCodec(authEnv: AuthEnv): Codec[Session] =
    SignedCodec.fromAuthEnv(authEnv){ key =>
      versionedSessionToken(authEnv, key)
    }

  def validate(session: Session): AuthResult[Unit] = {
    if (session.expiry.isBefore(Instant.now()))
      Left(AuthFailure.Expired(session.expiry))
    else if (session.user.login.isEmpty)
      Left(AuthFailure.InvalidField("user.login", "user login cannot be empty"))
    else
      Right(())
  }

  def authenticatorForEnv(env: AuthEnv): TokenAuthenticator[String, Session] =
    new ScodecTokenAuthenticator[Session] {
      val codec: Codec[Session] =
        signedSessionTokenCodec(env)

      def validate(s: Session): AuthResult[Unit] =
        session.validate(s)
    }
}

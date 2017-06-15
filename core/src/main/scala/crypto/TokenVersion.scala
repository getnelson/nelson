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

/**
 * The serialization version of a token.
 *
 * While the major/minor/micro versions are represented as `Int` at runtime,
 * note that the serializer treats them as unsigned integers, so their range is
 * 0 to 255.
 *
 * We must increment major whenever there are incompatible structural changes on the part of the core library:
 *  1. changes to secret part structure
 *  2. change to secret encryption algorithm,
 *  3. change token signing algorithm
 *  4. change to keyId -> key calculation
 * Changes 2-4 could come from authentication library or if we overwrite the default values.
 */
final case class TokenVersion(major: Int, minor: Int, micro: Int)

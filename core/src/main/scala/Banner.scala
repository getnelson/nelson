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

object Banner {
  // http://patorjk.com/software/taag/#p=display&h=3&f=Big%20Money-se&t=Nelson
  private val wording = """
  | __    __          __
  ||  \  |  \        |  \
  || $$\ | $$ ______ | $$ _______  ______  _______
  || $$$\| $$/      \| $$/       \/      \|       \
  || $$$$\ $|  $$$$$$| $|  $$$$$$|  $$$$$$| $$$$$$$\
  || $$\$$ $| $$    $| $$\$$    \| $$  | $| $$  | $$
  || $$ \$$$| $$$$$$$| $$_\$$$$$$| $$__/ $| $$  | $$
  || $$  \$$$\$$     | $|       $$\$$    $| $$  | $$
  | \$$   \$$ \$$$$$$$\$$\$$$$$$$  \$$$$$$ \$$   \$$
  |
  """.stripMargin

  private val suffix = s"""
  | Nelson ${BuildInfo.version} (${BuildInfo.gitRevision})
  | Built at ${BuildInfo.buildDate} with SBT ${BuildInfo.sbtVersion}
  | """.stripMargin

  val text = wording+suffix
}

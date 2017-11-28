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

import java.nio.charset.StandardCharsets
import java.nio.file.Files

import org.scalatest.prop.Checkers
import scalaz.concurrent.Task
import scalaz.stream.Process

class WithTempFileSpec extends NelsonSuite with Checkers {
  "withTempFile" should "create a file with the specified contents" in {
    check { s: String =>
      withTempFile(s) { f =>
        Process.eval(Task.delay {
          new String(Files.readAllBytes(f.toPath), StandardCharsets.UTF_8)
        })
      }.runLog.run == Vector(s)
    }
  }

  it should "delete file when done" in {
    withTempFile("foo") { f =>
      Process.eval(Task.delay(f))
    }.runLog.run.map(_.exists) should === (Vector(false))
  }
}

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
package nelson.alerts

import java.io.{BufferedWriter, File, OutputStreamWriter}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.util.regex.Pattern
import nelson.{NamespaceName, PlanRef, Pools, UnitName, Version}
import nelson.Datacenter.StackName
import nelson.Manifest.{ AlertOptOut, Alerting, PrometheusConfig }
import scala.io.Source
import scalaz.stream.Process
import scalaz.concurrent.Task

object Overhaul extends RuleRewriter {
  import RuleRewriter._

  def rewriteRules(stackName: StackName, ns: NamespaceName, plan: PlanRef, prometheus: PrometheusConfig): Task[Result] = {
    Task.delay {
      val rules = toSerializedRules(prometheus)
      val pb = new ProcessBuilder("overhaul", "transform", "-f", "promql", "-s",
        stackName.toString, "-p", plan, "-e", ns.root.asString)
      pb.redirectErrorStream(true)
      val p = pb.start()

      Pools.default.serverExecutor {
        val in = new BufferedWriter(new OutputStreamWriter(p.getOutputStream, UTF_8))
        in.write(rules)
        in.flush()
        in.close()
      }

      val out = Source.fromInputStream(p.getInputStream).mkString

      p.waitFor match {
        case 0 =>
          Rewritten(out)
        case 1 =>
          // We gave overhaul bad input.
          Invalid(out)
        case n =>
          // Anything > 1 means we voided the warranty on promtool.
          throw Error(n, out)
      }
    }
  }
}

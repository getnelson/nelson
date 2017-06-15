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

import scala.sys.process._
import journal.Logger
import routing.RoutingGraph

final case class DependencyGraph(gr: RoutingGraph) {
  private val log = Logger[this.type]

  val graph = gr.vmap(_.stackName)

  def svg: Array[Byte] = {
    val cmd = Seq("/usr/bin/dot", "-Tsvg")

    @volatile var bytes: Array[Byte] = Array()

    def barf(output: java.io.OutputStream): Unit = {
      val str = quiver.viz.graphviz(
        g = graph,
        title = "system",
        pageSize = (6F,6F),
        gridSize = (2,2),
        orient = quiver.viz.Portrait
      )
      output.write(str.getBytes("utf-8"))
      output.flush()
      output.close()
    }

    def slurp(input: java.io.InputStream): Unit = {
      bytes = Stream.continually(input.read).takeWhile(_ != -1).map(_.toByte).toArray
    }

    def err(input: java.io.InputStream): Unit = {
      log.error("ERR: " + new String(Stream.continually(input.read).takeWhile(_ != -1).map(_.toByte).toArray))
    }

    val proc = Process(cmd)
    val pio = new ProcessIO(barf _, slurp _, err _)
    val runningProc = proc.run(pio)
    val exit = runningProc.exitValue()
    bytes
  }
}

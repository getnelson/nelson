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
package routing

import scala.concurrent.duration.{Duration, DurationInt, TimeUnit}
import scala.concurrent.duration._

import scalaz._
import Scalaz._
import scalaz.concurrent.{Strategy, Task}
import scalaz.stream._
import journal.Logger
import helm.ConsulOp
import ConsulOp.ConsulOpF
import Nelson._

import scala.util.Random

object cron {
  private[cron] val log = Logger[cron.type]

  def refresh(cfg: NelsonConfig): Task[List[(Datacenter,ConsulOp.ConsulOpF[Unit])]] = {
    cfg.datacenters.traverseM { dc =>
      log.info(s"cron: refreshing ${dc.name}")
      for {
        rts <- nelson.storage.run(cfg.storage, RoutingTable.generateRoutingTables(dc.name))

        dts = Discovery.discoveryTables(rts).toList

        dtout = dts.map {
          case ((d,ns),dts) =>
            log.debug(s"cron: refressing lighthouse table for ${d}")
            dc -> Discovery.writeDiscoveryInfoToConsul(ns, d.stackName, dc.domain.name, dts)
        }

        lbout = rts.flatMap { case (_ , gr) =>
          loadbalancers.loadbalancerConfigs(gr).map { case ((d, ins)) =>
            log.debug(s"cron: refreshing proxy configuration for ${d}")
            dc -> loadbalancers.writeLoadbalancerConfigToConsul(d)(ins)
          }
        }

      } yield dtout ++ lbout
    }
  }

  def consulRefresh(cfg: NelsonConfig): Process[Task,(Datacenter,ConsulOp.ConsulOpF[Unit])] =
    Process.repeatEval(Task.delay(cfg.discoveryDelay)).flatMap(d =>
      time.awakeEvery(d)(cfg.pools.schedulingExecutor, cfg.pools.schedulingPool).once)
      .flatMap(_ =>
        Process.eval(refresh(cfg))
          .attempt().observeW(cfg.auditor.errorSink)
          .stripW
      )
      .flatMap(Process.emitAll)
}

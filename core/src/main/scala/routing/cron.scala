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

import scalaz._
import Scalaz._
import scalaz.concurrent.Task
import scalaz.stream._
import journal.Logger
import helm.ConsulOp

object cron {
  private[cron] val log = Logger[cron.type]

  def refresh(cfg: NelsonConfig): Task[List[(Domain,ConsulOp.ConsulOpF[Unit])]] = {
    cfg.domains.traverseM { dc =>
      log.info(s"cron: refreshing ${dc.name}")
      for {
        rts <- nelson.storage.run(cfg.storage, RoutingTable.generateRoutingTables(dc.name))

        dts = Discovery.discoveryTables(rts).toList

        dtout = dts.map {
          case ((sn,ns),dts) =>
            log.debug(s"cron: refressing lighthouse table for ${sn}")
            dc -> Discovery.writeDiscoveryInfoToConsul(ns, sn, dc.domain.name, dts)
        }

        lbout = rts.flatMap { case (_ , gr) =>
          loadbalancers.loadbalancerV1Configs(gr).map { case ((lb, ins)) =>
            log.debug(s"cron: refreshing proxy configuration for ${lb}")
            dc -> loadbalancers.writeLoadbalancerV1ConfigToConsul(lb, ins)
          }
        }

      } yield dtout ++ lbout
    }
  }

  def consulRefresh(cfg: NelsonConfig): Process[Task,(Domain,ConsulOp.ConsulOpF[Unit])] =
    Process.repeatEval(Task.delay(cfg.discoveryDelay)).flatMap(d =>
      time.awakeEvery(d)(cfg.pools.schedulingExecutor, cfg.pools.schedulingPool).once)
      .flatMap(_ =>
        Process.eval(refresh(cfg))
          .attempt().observeW(cfg.auditor.errorSink)
          .stripW
      )
      .flatMap(Process.emitAll)
}

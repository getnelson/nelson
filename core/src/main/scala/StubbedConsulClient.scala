package nelson

import cats.~>
import cats.effect.IO

import helm.ConsulOp

import scala.collection.immutable.Set

object StubbedConsulClient extends (ConsulOp ~> IO) {
  def apply[A](fa: ConsulOp[A]): IO[A] = fa match {
    case ConsulOp.KVGet(key) => IO.pure(None)
    case ConsulOp.KVSet(key, value) => IO.unit
    case ConsulOp.KVListKeys(prefix) => IO.pure(Set.empty)
    case ConsulOp.KVDelete(key) => IO.unit
    case ConsulOp.HealthListChecksForService(service, datacenter, near, nodeMeta) => IO.pure(List.empty)
    case ConsulOp.HealthListChecksForNode(node, datacenter) => IO.pure(List.empty)
    case ConsulOp.HealthListChecksInState(state, datacenter, near, nodeMeta) => IO.pure(List.empty)
    case ConsulOp.HealthListNodesForService(service, datacenter, near, nodeMeta, tag, passingOnly) => IO.pure(List.empty)
    case ConsulOp.AgentRegisterService(service, id, tags, address, port, enableTagOverride, check, checks) => IO.unit
    case ConsulOp.AgentDeregisterService(service) => IO.unit
    case ConsulOp.AgentListServices => IO.pure(Map.empty)
    case ConsulOp.AgentEnableMaintenanceMode(id, enable, reason) => IO.unit
  }
}

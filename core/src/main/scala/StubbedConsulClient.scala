package nelson

import cats.~>
import cats.effect.IO

import helm.ConsulOp

import scala.collection.immutable.Set

object StubbedConsulClient extends (ConsulOp ~> IO) {
  def apply[A](fa: ConsulOp[A]): IO[A] = fa match {
    case ConsulOp.KVGet(_) => IO.pure(None)
    case ConsulOp.KVSet(_, _) => IO.unit
    case ConsulOp.KVListKeys(_) => IO.pure(Set.empty)
    case ConsulOp.KVDelete(_) => IO.unit
    case ConsulOp.HealthListChecksForService(_, _, _, _) => IO.pure(List.empty)
    case ConsulOp.HealthListChecksForNode(_, _) => IO.pure(List.empty)
    case ConsulOp.HealthListChecksInState(_, _, _, _) => IO.pure(List.empty)
    case ConsulOp.HealthListNodesForService(_, _, _, _, _, _) => IO.pure(List.empty)
    case ConsulOp.AgentRegisterService(_, _, _, _, _, _, _, _) => IO.unit
    case ConsulOp.AgentDeregisterService(_) => IO.unit
    case ConsulOp.AgentListServices => IO.pure(Map.empty)
    case ConsulOp.AgentEnableMaintenanceMode(_, _, _) => IO.unit
  }
}

package nelson
package notifications

import Manifest._
import argonaut._, Argonaut._

sealed trait NotificationEvent

final case class DeployEvent(
  unit: UnitDef,
  actionConfig: ActionConfig
) extends NotificationEvent

final case class DecommissionEvent(
  d: Datacenter.Deployment
) extends NotificationEvent

object NotificationEvent {

  def deploy(unit: UnitDef @@ Versioned, actionConfig: ActionConfig): DeployEvent = {
    DeployEvent(Versioned.unwrap(unit), actionConfig)
  }

  def decommission(d: Datacenter.Deployment): DecommissionEvent = DecommissionEvent(d)

  /**
    * Encodes the final web hook payload by matching on the event class.
    *
    * The notification model here is influenced heavily by the Github event model, wherein
    * a causal event is a name as well as an event class, the latter of which dictates its
    * actual structure. This provides greater flexibility for the consumer of the payload
    * who can then react contextually, e.g. act on initial deploy but not redeploy.
    *
    * This behavior is not currently type-encoded but should be if the number of event types is expanded.
    */
  implicit def encodeEventDetail: EncodeJson[NotificationEvent] = EncodeJson {
    case d: DeployEvent =>
      ("action"   := "deploy") ->:
      ("deployed" := encodeDeploy(d)) ->:
      jEmptyObject
    case d: DecommissionEvent =>
      ("action"         := "decommission") ->:
      ("decommissioned" := encodeDecommission(d)) ->:
      jEmptyObject
  }

  implicit def encodeDeploy: EncodeJson[DeployEvent] = EncodeJson { ev =>

    val ns = ev.actionConfig.namespace
    val dc = ev.actionConfig.datacenter
    val plan = ev.actionConfig.plan
    val env = plan.environment
    val unit = ev.unit

    ("namespace"       := ns.name.asString) ->:
    ("datacenter"      := dc.name) ->:
    ("plan"            :=
      ("name"          := plan.name) ->:
      ("schedule"      := env.schedule.flatMap(_.toCron())) ->:
      ("health_checks" := env.healthChecks.map(mkHealthCheck)) ->:
      ("constraints"   := env.constraints.map(_.fieldName)) ->:
      ("bindings"      := env.bindings.map(b => b.name -> b.value)) ->:
      ("resources"     := env.resources.mapValues(_.toString)) ->: jEmptyObject
    ) ->:
    ("unit"            :=
      ("name"          := unit.name) ->:
      ("description"   := unit.description) ->:
      ("artifact"      := unit.deployable.map(mkDeployable)) ->:
      ("ports"         := unit.ports.map(mkPorts)) ->:
      ("dependencies"  := unit.dependencies.mapValues(_.toString)) ->:
      ("resources"     := unit.resources.map(_.name)) ->: jEmptyObject
    ) ->:
    jEmptyObject
  }

  implicit def encodeDecommission: EncodeJson[DecommissionEvent] = EncodeJson { ev =>
    val deployment = ev.d
    val unit = deployment.unit

    ("namespace"       := deployment.namespace.name.asString) ->:
    ("datacenter"      := deployment.namespace.datacenter) ->:
    ("deployment"      :=
      ("id"            := deployment.id) ->:
      ("hash"          := deployment.hash) ->:
      ("guid"          := deployment.guid) ->:
      ("plan"          := deployment.plan) ->:
      ("stack"         := deployment.stackName.toString) ->:
      ("deploy_time"   := deployment.deployTime.toString) ->:
      ("workflow"      := deployment.workflow) ->: jEmptyObject
    ) ->:
    ("unit"            :=
      ("name"          := unit.name) ->:
      ("description"   := unit.description) ->:
      ("ports"         := unit.ports.map(mkPort)) ->:
      ("dependencies"  := unit.dependencies.map(sn => (sn.serviceType, sn.version.toString))) ->:
      ("resources"     := unit.resources) ->: jEmptyObject
    ) ->:
    jEmptyObject
  }

  private def mkDeployable(d: Manifest.Deployable) =
    ("name"       := d.name) ->:
    ("version"    := d.version.toString) ->:
    ("deployable" := (d.output match { case Deployable.Container(i) => i })) ->:
    jEmptyObject

  private def mkHealthCheck(h: Manifest.HealthCheck) =
    ("name"     := h.name) ->:
    ("path"     := h.path) ->:
    ("port"     := h.portRef) ->:
    ("protocol" := h.protocol) ->:
    ("interval" := h.interval.toMillis) ->:
    ("timeout"  := h.timeout.toMillis) ->:
    jEmptyObject

  private def mkPort(p: Manifest.Port) =
    ("default"  := p.isDefault) ->:
    ("ref"      := p.ref) ->:
    ("port"     := p.port) ->:
    ("protocol" := p.protocol) ->:
    jEmptyObject

  private def mkPort(p: Datacenter.Port) =
    ("ref"      := p.name) ->:
    ("port"     := p.port) ->:
    ("protocol" := p.protocol) ->:
    jEmptyObject

  private def mkPorts(ps: Ports) = ps.nel.map(mkPort).toList
}

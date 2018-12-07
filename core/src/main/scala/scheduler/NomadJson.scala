package nelson
package scheduler

object NomadJson {
  import argonaut._, Argonaut._

  sealed abstract class NetworkMode(val asString: String)
  final case object BridgeMode extends NetworkMode("bridge")
  final case object HostMode extends NetworkMode("host")

  // We need to pass in the id because Nomad uses it as a key in the response :(
  def deploymentSummaryDecoder(id: String): DecodeJson[DeploymentSummary] =
    DecodeJson[DeploymentSummary](c => {
      val inner = c --\ "Summary" --\ id
      for {
        running   <- (inner --\ "Running").as[Option[Int]]
        failed    <- (inner --\ "Failed").as[Option[Int]]
        queued    <- (inner --\ "Queued").as[Option[Int]]
        completed <- (inner --\ "Complete").as[Option[Int]]
      } yield DeploymentSummary(running, queued, completed, failed)
    })

  // reference: https://www.nomadproject.io/docs/http/jobs.html
  // Note: implicit from argonaut Decoder[List[A]] => Decoder[A] is already defined
  implicit def runningUnitDecoder: DecodeJson[RunningUnit] =
    DecodeJson[RunningUnit](c => {
      for {
        nm <- (c --\ "Name").as[Option[String]]
        st <- (c --\ "Status").as[Option[String]]
        p  <- (c --\ "ParentID").as[Option[String]]
      } yield RunningUnit(nm, st, p)
    })

  def makeParseRequest(spec: String): argonaut.Json = {
    argonaut.Json(
      "JobHCL"       := spec,
      "Canonicalize" := true
    )
  }
}

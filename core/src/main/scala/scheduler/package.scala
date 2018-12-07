package nelson

import nelson.blueprint.Template

import cats.effect.IO

package object scheduler {

  def mkFallback(unit: Manifest.UnitDef, plan: Manifest.Plan)
                (service: IO[Template], job: IO[Template], cron: IO[Template]): IO[Template] =
    Manifest.getSchedule(unit, plan) match {
      case None      => service
      case Some(s)   => s.toCron() match {
        case None    => job
        case Some(_) => cron
      }
    }

  // NOTE: by this point in the system, we know we're dealing with
  // a hydrated blueprint (i.e. passes manifest validation and exists
  // in the database) so we simply take the supplied plan and extract
  // the `Blueprint`, and `Template` in turn.
  def mkTemplate(env: Manifest.Environment, fallback: => IO[Template]): IO[Template] =
    env.blueprint match {
      case Some(Left((ref, rev))) => IO.raiseError(UnhydratedBlueprint(ref, rev))
      case Some(Right(bp))        => IO.pure(bp.template)
      case None                   => fallback
    }
}

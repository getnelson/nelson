package nelson
package storage

import cats.effect.IO
import org.flywaydb.core.Flyway
import journal.Logger

object Migrate {

  val log = Logger[Migrate.type]

  def migrate(cfg: DatabaseConfig): IO[Unit] =
    IO {
      val flyway = new Flyway
      flyway.setDataSource(
        cfg.connection,
        cfg.username.getOrElse(""),
        cfg.password.getOrElse(""))

      try {
        log.info("Conducting database schema migrations if needed.")
        val completed = flyway.migrate()
        log.info(s"Completed $completed succsessful migrations.")
      } catch {
        case e: Throwable => {
          // attempt a repair (useful for local debugging)
          log.error(s"Failed to migrate database. ${e.getMessage}")
          log.info("Repairing database before retrying migration")
          flyway.repair()
          val completed = flyway.migrate()
          log.info(s"After repair, completed $completed succsessful migrations.")
        }
      }
    }
}

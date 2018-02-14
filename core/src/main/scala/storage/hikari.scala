package nelson
package storage

import doobie.contrib.hikari.hikaritransactor._
import cats.effect.IO
import nelson.CatsHelpers._

object Hikari {

  def build(db: DatabaseConfig): HikariTransactor[IO] = {
    val trans = for {
      xa <- HikariTransactor[IO](db.driver, db.connection, db.username.getOrElse(""), db.password.getOrElse(""))
       _ <- xa.configure(hx => IO(db.maxConnections.foreach(max => hx.setMaximumPoolSize(max))))
    } yield xa

    trans.unsafeRunSync()
  }
}

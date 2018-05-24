package nelson
package storage

import doobie.hikari._
import cats.effect.IO

object Hikari {

  def build(db: DatabaseConfig): HikariTransactor[IO] = {
    val trans = for {
      xa <- HikariTransactor.newHikariTransactor[IO](db.driver, db.connection, db.username.getOrElse(""), db.password.getOrElse(""))
       _ <- xa.configure(hx => IO(db.maxConnections.foreach(max => hx.setMaximumPoolSize(max))))
    } yield xa

    trans.unsafeRunSync()
  }
}

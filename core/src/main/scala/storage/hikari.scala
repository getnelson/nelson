package nelson
package storage

import doobie.contrib.hikari.hikaritransactor._
import scalaz.concurrent.Task

object Hikari {

  def build(db: DatabaseConfig): HikariTransactor[Task] = {
    val trans = for {
      xa <- HikariTransactor[Task](db.driver, db.connection, db.username.getOrElse(""), db.password.getOrElse(""))
       _ <- xa.configure(hx => Task.delay(db.maxConnections.foreach(max => hx.setMaximumPoolSize(max))))
    } yield xa

    trans.run
  }
}

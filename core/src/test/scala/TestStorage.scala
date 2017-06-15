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

import nelson.storage.StoreOp

object TestStorage {
  def dbConfig(testname: String): DatabaseConfig =
    DatabaseConfig("org.h2.Driver", s"jdbc:h2:file:db/nelson.test.$testname.db;DATABASE_TO_UPPER=FALSE;", None, None)

  def storage(testname: String) = {
    val s = new nelson.storage.H2Storage(dbConfig(testname))
    nelson.storage.run(s, StoreOp.migrate).run
    s
  }

  import doobie.imports._

  def xa(cfg: DatabaseConfig) = 
    DriverManagerTransactor[scalaz.concurrent.Task](
      cfg.driver,
      cfg.connection,
      cfg.username.getOrElse(""),
      cfg.password.getOrElse("")
    )
}

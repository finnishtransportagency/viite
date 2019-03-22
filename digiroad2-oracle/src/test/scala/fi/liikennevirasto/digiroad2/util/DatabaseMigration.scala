package fi.liikennevirasto.digiroad2.util

import org.flywaydb.core.Flyway
import fi.liikennevirasto.digiroad2.oracle.OracleDatabase._

object DatabaseMigration {
  def main(args: Array[String]) : Unit = {
    val configuration = Flyway.configure.
      dataSource(ds).
      locations("db.migration")
    val flyway = new Flyway(configuration)
    flyway.migrate()
  }
}

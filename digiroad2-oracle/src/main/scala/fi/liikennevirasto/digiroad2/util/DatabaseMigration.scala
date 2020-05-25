package fi.liikennevirasto.digiroad2.util

import fi.liikennevirasto.digiroad2.oracle.OracleDatabase._
import org.flywaydb.core.Flyway

object DatabaseMigration {

  def main(args: Array[String]): Unit = {
    migrate
  }

  def migrate: Unit = {
    val configuration = Flyway.configure.
      dataSource(ds).
      locations("db.migration")
    val flyway = new Flyway(configuration)
    flyway.migrate()
  }

  def flywayInit {
    new Flyway(Flyway.configure().
      dataSource(ds).
      baselineVersion("-1").
      baselineOnMigrate(true).
      locations("db.migration")).
      baseline()
  }

}

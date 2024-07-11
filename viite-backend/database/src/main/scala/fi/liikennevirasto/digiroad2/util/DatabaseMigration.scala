package fi.liikennevirasto.digiroad2.util

import org.flywaydb.core.Flyway
import fi.vaylavirasto.viite.postgis.PostGISDatabase._

object DatabaseMigration {

  def main(args: Array[String]): Unit = {
    migrate()
  }

  def migrate(outOfOrder: Boolean = false): Unit = {
    val flyway = new Flyway()
    flyway.setDataSource(ds)
    flyway.setLocations("db.migration")
    flyway.setOutOfOrder(outOfOrder)
    flyway.migrate()
  }

  def flywayInit(): Unit = {
    val flyway = new Flyway()
    flyway.setDataSource(ds)
    flyway.setBaselineVersionAsString("-1")
    flyway.setBaselineOnMigrate(true)
    flyway.setLocations("db.migration")
    flyway.baseline()
  }

}

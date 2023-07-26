package fi.liikennevirasto.digiroad2.util

import com.googlecode.flyway.core.Flyway
import fi.liikennevirasto.digiroad2.postgis.PostGISDatabase._

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
    flyway.setInitVersion("-1")
    flyway.setInitOnMigrate(true)
    flyway.setLocations("db.migration")
    flyway.init()
  }

}

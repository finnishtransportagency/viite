package fi.liikennevirasto.digiroad2.util

import fi.liikennevirasto.digiroad2.oracle.OracleDatabase._
import com.googlecode.flyway.core.Flyway

object DatabaseMigration {
  def main(args: Array[String]) : Unit = {
    val flyway = new Flyway()
    flyway.setDataSource(ds)
    flyway.setLocations("db.migration")
    flyway.migrate()
  }
}

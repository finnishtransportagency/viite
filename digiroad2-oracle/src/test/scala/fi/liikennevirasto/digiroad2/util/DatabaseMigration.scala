package fi.liikennevirasto.digiroad2.util

import com.googlecode.flyway.core.Flyway
import fi.liikennevirasto.digiroad2.oracle.OracleDatabase._

object DatabaseMigration {
  def main(args: Array[String]) : Unit = {
    throw new Exception("The migration should be done on viite yet.")
    /*
    val flyway = new Flyway()
    flyway.setDataSource(ds)
    flyway.setLocations("db.migration")
    flyway.migrate()*/
  }
}

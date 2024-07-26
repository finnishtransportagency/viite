package fi.liikennevirasto.digiroad2.util

import org.flywaydb.core.Flyway
import fi.vaylavirasto.viite.postgis.PostGISDatabase._
import org.flywaydb.core.api.configuration.FluentConfiguration

object DatabaseMigration {

  def main(args: Array[String]): Unit = {
    migrate()
  }

  def migrate(outOfOrder: Boolean = false): Unit = {

    val flywayConf: FluentConfiguration = Flyway.configure
    flywayConf.dataSource(ds)
    flywayConf.locations("db/migration")
    flywayConf.outOfOrder(outOfOrder)

    val flyway = flywayConf.load()
    flyway.migrate()
  }

  def flywayInit(): Unit = {

    val flywayConf: FluentConfiguration = Flyway.configure
    flywayConf.dataSource(ds)
    flywayConf.baselineVersion("-1")
    flywayConf.baselineOnMigrate(true)
    flywayConf.locations("db/migration")

    val flyway = flywayConf.load()
    flyway.baseline()
  }

}

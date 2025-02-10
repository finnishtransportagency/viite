package fi.liikennevirasto.digiroad2.util

import org.flywaydb.core.Flyway
import org.flywaydb.core.api.configuration.FluentConfiguration
import scalikejdbc.ConnectionPool

object DatabaseMigration {

  def main(args: Array[String]): Unit = {
    migrate()
  }

  def migrate(outOfOrder: Boolean = false): Unit = {

    val flywayConf: FluentConfiguration = Flyway.configure
    flywayConf.dataSource(ConnectionPool.get().dataSource)
    flywayConf.locations("db/migration")
    flywayConf.outOfOrder(outOfOrder)

    val flyway = flywayConf.load()
    flyway.migrate()
  }

  def flywayInit(): Unit = {

    val flywayConf: FluentConfiguration = Flyway.configure
    flywayConf.dataSource(ConnectionPool.get().dataSource)
    flywayConf.baselineVersion("-1")
    flywayConf.baselineOnMigrate(true)
    flywayConf.locations("db/migration")

    val flyway = flywayConf.load()
    flyway.baseline()
  }

}

package fi.vaylavirasto.viite.dao

import slick.driver.JdbcDriver.backend.Database.dynamicSession
import slick.jdbc.StaticQuery.interpolation

object ExportLockDAO {

  def insert: Unit = {
    sqlu"""INSERT INTO EXPORT_LOCK VALUES (1, current_date)""".execute
  }

  def delete: Unit = {
    sqlu"""DELETE FROM EXPORT_LOCK""".execute
  }

}

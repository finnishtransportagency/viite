package fi.liikennevirasto.digiroad2.oracle

import slick.driver.JdbcDriver.backend.Database.dynamicSession
import slick.jdbc.StaticQuery.interpolation

object MassQuery {
  def withIds[T](ids: Set[Long])(f: String => T): T = {
    sqlu"""DELETE FROM temp_id""".execute
    val insertLinkIdPS = dynamicSession.prepareStatement("insert into temp_id (id) values (?)")
    try {
      ids.foreach { id =>
        insertLinkIdPS.setLong(1, id)
        insertLinkIdPS.addBatch()
      }
      insertLinkIdPS.executeBatch()
      val ret = f("temp_id")
      ret
    } finally {
      insertLinkIdPS.close()
    }
  }
}
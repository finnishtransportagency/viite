package fi.liikennevirasto.digiroad2.postgis

import slick.driver.JdbcDriver.backend.Database.dynamicSession
import slick.jdbc.StaticQuery.interpolation

object MassQuery {
  def withIds[T](ids: Iterable[Long])(f: String => T): T = {
    sqlu"""
      CREATE GLOBAL TEMPORARY TABLE IF NOT EXISTS TEMP_ID (
        ID BIGINT NOT NULL,
	      CONSTRAINT TEMP_ID_PK PRIMARY KEY (ID)
      ) ON COMMIT DELETE ROWS
    """.execute
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
package fi.liikennevirasto.digiroad2.postgis

import fi.liikennevirasto.digiroad2.util.LogUtils
import org.slf4j.LoggerFactory
import slick.driver.JdbcDriver.backend.Database.dynamicSession
import slick.jdbc.StaticQuery.interpolation

object MassQuery {
  val logger = LoggerFactory.getLogger(getClass)
  def withIds[T](ids: Iterable[Long])(f: String => T): T = {
    LogUtils.time(logger, s"TEST LOG MassQuery withIds ${ids.size}") {
      LogUtils.time(logger, "TEST LOG create TEMP_ID table") {
        sqlu"""
      CREATE TEMPORARY TABLE IF NOT EXISTS TEMP_ID (
        ID BIGINT NOT NULL,
	      CONSTRAINT TEMP_ID_PK PRIMARY KEY (ID)
      ) ON COMMIT DELETE ROWS
    """.execute
      }
      val insertLinkIdPS = dynamicSession.prepareStatement("insert into temp_id (id) values (?)")

      LogUtils.time(logger, s"TEST LOG insert into TEMP_ID ${ids.size}") {
        try {
          sqlu"TRUNCATE TABLE TEMP_ID".execute
          ids.foreach { id =>
            insertLinkIdPS.setLong(1, id)
            insertLinkIdPS.addBatch()
          }
          logger.debug("added {} entries to temporary table", ids.size)
          insertLinkIdPS.executeBatch()
          val ret = f("temp_id")
          ret
        } finally {
          insertLinkIdPS.close()
        }
      }
    }
  }
}
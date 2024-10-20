package fi.vaylavirasto.viite.postgis

import fi.liikennevirasto.digiroad2.util.LogUtils
import fi.vaylavirasto.viite.dao.BaseDAO
import slick.driver.JdbcDriver.backend.Database.dynamicSession

import scala.language.implicitConversions

object MassQuery extends BaseDAO {
  case class LongList(list: Iterable[Long])
  case class StringList(list: Iterable[String])
  implicit def il(list: Iterable[Long]): LongList = LongList(list)
  implicit def sl(list: Iterable[String]): StringList = StringList(list)

  def withIds[T](ids: LongList)(function: String => T): T = {
    LogUtils.time(logger, s"TEST LOG MassQuery withIds ${ids.list.size}") {
      LogUtils.time(logger, "TEST LOG create TEMP_ID table") {
        runUpdateToDb(s"""
          CREATE TEMPORARY TABLE IF NOT EXISTS TEMP_ID (
            ID BIGINT NOT NULL,
            CONSTRAINT TEMP_ID_PK PRIMARY KEY (ID)
          ) ON COMMIT DELETE ROWS
        """)
      }
      val insertLinkIdPS = dynamicSession.prepareStatement("insert into temp_id (id) values (?)")

      LogUtils.time(logger, s"TEST LOG insert into TEMP_ID ${ids.list.size}") {
        try {
          //Making sure that the table is empty if called multiple times within a transaction
          //Emptied at the end of transaction as per TABLE definition above
          runUpdateToDb(s"TRUNCATE TABLE TEMP_ID")
          ids.list.foreach { id =>
            insertLinkIdPS.setLong(1, id)
            insertLinkIdPS.addBatch()
          }
          logger.debug("added {} entries to temporary table", ids.list.size)
          insertLinkIdPS.executeBatch()
          function("temp_id")
        } finally {
          insertLinkIdPS.close()
        }
      }
    }
  }

  def withIds[T](ids: StringList)(function: String => T): T = {
    LogUtils.time(logger, s"TEST LOG MassQuery withIds ${ids.list.size}") {
      LogUtils.time(logger, "TEST LOG create TEMP_UUID table") {
        runUpdateToDb(s"""
          CREATE TEMPORARY TABLE IF NOT EXISTS TEMP_UUID (
            ID VARCHAR NOT NULL,
            CONSTRAINT TEMP_UUID_PK PRIMARY KEY (ID)
          )
          ON COMMIT DELETE ROWS
        """)
      }
      val insertLinkIdPS = dynamicSession.prepareStatement("insert into temp_uuid (id) values (?)")

      LogUtils.time(logger, s"TEST LOG insert into TEMP_UUID ${ids.list.size}") {
        try {
          //Making sure that the table is empty if called multiple times within a transaction
          //Emptied at the end of transaction as per TABLE definition above
          runUpdateToDb(s"TRUNCATE TABLE TEMP_UUID")
          ids.list.foreach { id =>
            insertLinkIdPS.setString(1, id)
            insertLinkIdPS.addBatch()
          }
          logger.debug("added {} entries to temporary table", ids.list.size)
          insertLinkIdPS.executeBatch()
          function("temp_uuid")
        } finally {
          insertLinkIdPS.close()
        }
      }
    }
  }
}

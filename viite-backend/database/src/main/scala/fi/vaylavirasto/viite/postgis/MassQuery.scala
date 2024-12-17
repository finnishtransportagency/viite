package fi.vaylavirasto.viite.postgis

import fi.liikennevirasto.digiroad2.util.LogUtils
import fi.vaylavirasto.viite.dao.BaseDAO
import scalikejdbc._

import scala.language.implicitConversions

object MassQuery extends BaseDAO {
  case class LongList(list: Iterable[Long])
  case class StringList(list: Iterable[String])
  implicit def il(list: Iterable[Long]): LongList = LongList(list)
  implicit def sl(list: Iterable[String]): StringList = StringList(list)

  def withIds[T](ids: LongList)(function: SQLSyntax => T): T = {
    LogUtils.time(logger, s"TEST LOG MassQuery withIds ${ids.list.size}") {
      LogUtils.time(logger, "TEST LOG create TEMP_ID table") {
        runUpdateToDb(
          sql"""
          CREATE TEMPORARY TABLE IF NOT EXISTS TEMP_ID (
            id BIGINT NOT NULL,
            CONSTRAINT TEMP_ID_PK PRIMARY KEY (id)
          ) ON COMMIT DELETE ROWS
        """)
      }

      LogUtils.time(logger, s"TEST LOG insert into TEMP_ID ${ids.list.size}") {

        //Making sure that the table is empty if called multiple times within a transaction
        //Emptied at the end of transaction as per TABLE definition above
        runUpdateToDb(sql"TRUNCATE TABLE temp_id")

        val insertQuery = sql"INSERT INTO temp_id (id) VALUES (?)"
        val batchParams = ids.list.map(id => Seq(id))

        // Execute batch insert
        runBatchUpdateToDb(insertQuery, batchParams.toSeq)

        logger.debug("added {} entries to temporary table", ids.list.size)

        // Call the function with the temporary table name
        function(sqls"TEMP_ID")
      }
    }
  }

  def withIds[T](ids: StringList)(function: SQLSyntax => T): T = {
    LogUtils.time(logger, s"TEST LOG MassQuery withIds ${ids.list.size}") {
      LogUtils.time(logger, "TEST LOG create temp_uuid table") {
        runUpdateToDb(
          sql"""
          CREATE TEMPORARY TABLE IF NOT EXISTS temp_uuid (
            id VARCHAR NOT NULL,
            CONSTRAINT temp_uuid_pk PRIMARY KEY (id)
          )
          ON COMMIT DELETE ROWS
        """)
      }

      LogUtils.time(logger, s"TEST LOG insert into temp_uuid ${ids.list.size}") {

        //Making sure that the table is empty if called multiple times within a transaction
        //Emptied at the end of transaction as per TABLE definition above
        runUpdateToDb(sql"TRUNCATE TABLE temp_uuid")

        val insertQuery = sql"INSERT INTO temp_uuid (id) VALUES (?)"
        val batchParams = ids.list.map(id => Seq(id))

        // Execute batch insert
        runBatchUpdateToDb(insertQuery, batchParams.toSeq)

        logger.debug("added {} entries to temporary table", ids.list.size)

        // Call the function with the temporary table name
        function(sqls"temp_uuid")

      }
    }
  }
}

package fi.liikennevirasto.digiroad2.dao

import fi.liikennevirasto.digiroad2.dao.Queries._
import slick.driver.JdbcDriver.backend.Database
import Database.dynamicSession

object Sequences {

  def nextViitePrimaryKeySeqValue: Long = {
    nextViitePrimaryKeyId.as[Long].first
  }

  def fetchViitePrimaryKeySeqValues(len: Int): List[Long] = {
    fetchViitePrimaryKeyId(len)
  }

  def nextCommonHistorySeqValue: Long = {
    nextCommonHistoryValue.as[Long].first
  }

  def nextRoadNetworkErrorSeqValue: Long = {
    nextRoadNetworkErrorValue.as[Long].first
  }
}

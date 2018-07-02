package fi.liikennevirasto.digiroad2.dao

import slick.driver.JdbcDriver.backend.Database
import Database.dynamicSession
import slick.jdbc.StaticQuery._
import slick.jdbc.{StaticQuery => Q}

object Queries {

  def nextPrimaryKeyId = sql"select primary_key_seq.nextval from dual"

  def nextViitePrimaryKeyId = sql"select viite_general_seq.nextval from dual"

  def nextCommonHistoryValue = sql"select common_history_seq.nextval from dual"

  def nextRoadNetworkErrorValue = sql"select road_network_errors_key_seq.nextval from dual"

  def fetchViitePrimaryKeyId(len: Int) = {
    sql"""select viite_general_seq.nextval from dual connect by level <= $len""".as[Long].list
  }

  def getMunicipalities: Seq[Int] = {
    sql"""
      select id from municipality
    """.as[Int].list
  }

  def getMunicipalitiesWithoutAhvenanmaa: Seq[Int] = {
    //The road_maintainer_id of Ahvenanmaa is 0
    sql"""
      select id from municipality where ROAD_MAINTAINER_ID != 0
      """.as[Int].list
  }

}

package fi.liikennevirasto.digiroad2.dao

import slick.driver.JdbcDriver.backend.Database
import Database.dynamicSession
import slick.jdbc.StaticQuery._
import slick.jdbc.{StaticQuery => Q}

object Queries {

  def nextViitePrimaryKeyId = sql"select viite_general_seq.nextval from dual"

  def nextRoadwayId = sql"select ROADWAY_SEQ.nextval from dual"

  def nextLinearLocationId = sql"select LINEAR_LOCATION_SEQ.nextval from dual"

  def nextRoadwayNumber = sql"select ROADWAY_NUMBER_SEQ.nextval from dual"

  def nextRoadNetworkErrorId = sql"select ROAD_NETWORK_ERROR_SEQ.nextval from dual"

  def nextProjectId = sql"select VIITE_PROJECT_SEQ.nextval from dual"

  def nextRoadwayChangeLink = sql"select ROADWAY_CHANGE_LINK.nextval from dual"

  def fetchViitePrimaryKeyId(len: Int) = {
    sql"""select viite_general_seq.nextval from dual connect by level <= $len""".as[Long].list
  }

  def fetchRoadwayIds(len: Int) = {
    sql"""select ROADWAY_SEQ.nextval from dual connect by level <= $len""".as[Long].list
  }

  def fetchLinearLocationIds(len: Int) = {
    sql"""select LINEAR_LOCATION_SEQ.nextval from dual connect by level <= $len""".as[Long].list
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

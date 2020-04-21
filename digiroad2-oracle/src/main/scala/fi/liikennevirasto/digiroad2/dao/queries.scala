package fi.liikennevirasto.digiroad2.dao

import slick.driver.JdbcDriver.backend.Database
import Database.dynamicSession
import slick.jdbc.StaticQuery._
import slick.jdbc.{StaticQuery => Q}

object Queries {

  def nextViiteProjectId = sql"select viite_project_seq.nextval from dual"

  def nextProjectLinkId = sql"select project_link_seq.nextval from dual"

  def nextViitePrimaryKeyId = sql"select viite_general_seq.nextval from dual"

  def nextRoadwayId = sql"select ROADWAY_SEQ.nextval from dual"

  def nextLinearLocationId = sql"select LINEAR_LOCATION_SEQ.nextval from dual"

  def nextRoadNameId = sql"select ROAD_NAME_SEQ.nextval from dual"

  def nextRoadwayNumber = sql"select ROADWAY_NUMBER_SEQ.nextval from dual"

  def nextRoadNetworkErrorId = sql"select ROAD_NETWORK_ERROR_SEQ.nextval from dual"

  def nextRoadwayChangeLink = sql"select ROADWAY_CHANGE_LINK.nextval from dual"

  def nextPublishedRoadNetworkId = sql"select PUBLISHED_ROAD_NETWORK_SEQ.nextval from dual"

  def nextRoadwayPointId = sql"select ROADWAY_POINT_SEQ.nextval from dual"

  def nextCalibrationPointId = sql"select CALIBRATION_POINT_SEQ.nextval from dual"

  def nextNodeId = sql"select NODE_SEQ.nextval from dual"

  def nextNodeNumber = sql"select NODE_NUMBER_SEQ.nextval from dual"

  def nextNodePointId = sql"select NODE_POINT_SEQ.nextval from dual"

  def nextJunctionId = sql"select JUNCTION_SEQ.nextval from dual"

  def nextJunctionPointId = sql"select JUNCTION_POINT_SEQ.nextval from dual"

  def fetchProjectLinkIds(len: Int) = {
    sql"""select PROJECT_LINK_SEQ.nextval from dual connect by level <= $len""".as[Long].list
  }

  def fetchRoadwayIds(len: Int) = {
    sql"""select ROADWAY_SEQ.nextval from dual connect by level <= $len""".as[Long].list
  }

  def fetchCalibrationPointIds(len: Int) = {
    sql"""select CALIBRATION_POINT_SEQ.nextval from dual connect by level <= $len""".as[Long].list
  }

  def fetchNodeIds(len: Int) = {
    sql"""select NODE_SEQ.nextval from dual connect by level <= $len""".as[Long].list
  }

  def fetchNodePointIds(len: Int) = {
    sql"""select NODE_POINT_SEQ.nextval from dual connect by level <= $len""".as[Long].list
  }

  def fetchJunctionPointIds(len: Int) = {
    sql"""select JUNCTION_POINT_SEQ.nextval from dual connect by level <= $len""".as[Long].list
  }

  def fetchJunctionIds(len: Int) = {
    sql"""select JUNCTION_SEQ.nextval from dual connect by level <= $len""".as[Long].list
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

package fi.liikennevirasto.digiroad2.dao

import slick.driver.JdbcDriver.backend.Database
import Database.dynamicSession
import slick.jdbc.StaticQuery._
import slick.jdbc.{StaticQuery => Q}

object Queries {

  def nextViiteProjectId = sql"select nextval('viite_project_seq')"

  def nextViitePrimaryKeyId = sql"select nextval('viite_general_seq')"

  def nextRoadwayId = sql"select nextval('ROADWAY_SEQ')"

  def nextLinearLocationId = sql"select nextval('LINEAR_LOCATION_SEQ')"

  def nextRoadwayNumber = sql"select nextval('ROADWAY_NUMBER_SEQ')"

  def nextRoadNetworkErrorId = sql"select nextval('ROAD_NETWORK_ERROR_SEQ')"

  def nextProjectId = sql"select nextval('viite_project_seq')"

  def nextRoadwayChangeLink = sql"select nextval('ROADWAY_CHANGE_LINK')"

  def nextPublishedRoadNetworkId = sql"select nextval('PUBLISHED_ROAD_NETWORK_SEQ')"

  def nextRoadwayPointId = sql"select nextval('ROADWAY_POINT_SEQ')"

  def nextCalibrationPointId = sql"select nextval('CALIBRATION_POINT_SEQ')"

  def nextNodeId = sql"select nextval('NODE_SEQ')"

  def nextNodeNumber = sql"select nextval('NODE_NUMBER_SEQ')"

  def nextJunctionNumber = sql"select nextval('JUNCTION_NUMBER_SEQ')"

  def nextNodePointId = sql"select nextval('NODE_POINT_SEQ')"

  def nextJunctionId = sql"select nextval('JUNCTION_SEQ')"

  def nextJunctionPointId = sql"select nextval('JUNCTION_POINT_SEQ')"

  def fetchViitePrimaryKeyId(len: Int) = {
    sql"""select nextval('viite_general_seq') from generate_series(1, $len)""".as[Long].list
  }

  def fetchRoadwayIds(len: Int) = {
    sql"""select nextval('ROADWAY_SEQ') from generate_series(1, $len)""".as[Long].list
  }

  def fetchNodeIds(len: Int) = {
    sql"""select nextval('NODE_SEQ') from generate_series(1, $len)""".as[Long].list
  }

  def fetchNodePointIds(len: Int) = {
    sql"""select nextval('NODE_POINT_SEQ') from generate_series(1, $len)""".as[Long].list
  }

  def fetchJunctionPointIds(len: Int) = {
    sql"""select nextval('JUNCTION_POINT_SEQ') from generate_series(1, $len)""".as[Long].list
  }

  def fetchJunctionIds(len: Int) = {
    sql"""select nextval('JUNCTION_SEQ') from from generate_series(1, $len)""".as[Long].list
  }

  def fetchLinearLocationIds(len: Int) = {
    sql"""select nextval('LINEAR_LOCATION_SEQ') from generate_series(1, $len)""".as[Long].list
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

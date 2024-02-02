package fi.vaylavirasto.viite.dao

import slick.driver.JdbcDriver.backend.Database
import Database.dynamicSession
import slick.jdbc.StaticQuery._

object Queries {

  def nextViiteProjectId = sql"select nextval('viite_project_seq')"

  def nextProjectLinkId = sql"select nextval('project_link_seq')"

  def nextViitePrimaryKeyId = sql"select nextval('viite_general_seq')"

  def nextRoadwayId = sql"select nextval('ROADWAY_SEQ')"

  def nextLinearLocationId = sql"select nextval('LINEAR_LOCATION_SEQ')"

  def nextRoadNameId = sql"select nextval('ROAD_NAME_SEQ')"

  def nextRoadwayNumber = sql"select nextval('ROADWAY_NUMBER_SEQ')"

  def nextRoadNetworkErrorId = sql"select nextval('ROAD_NETWORK_ERROR_SEQ')"

  def nextRoadwayChangeLink = sql"select nextval('ROADWAY_CHANGE_LINK')"

  @deprecated ("Table published_road_network is no longer in use, and is empty.")
  def nextPublishedRoadNetworkId = sql"select nextval('PUBLISHED_ROAD_NETWORK_SEQ')"

  def nextRoadwayPointId = sql"select nextval('ROADWAY_POINT_SEQ')"

  def nextCalibrationPointId = sql"select nextval('CALIBRATION_POINT_SEQ')"

  def nextNodeId = sql"select nextval('NODE_SEQ')"

  def nextNodeNumber = sql"select nextval('NODE_NUMBER_SEQ')"

  def nextNodePointId = sql"select nextval('NODE_POINT_SEQ')"

  def nextJunctionId = sql"select nextval('JUNCTION_SEQ')"

  def nextJunctionPointId = sql"select nextval('JUNCTION_POINT_SEQ')"

  def fetchProjectLinkIds(len: Int): List[Long] = {
    sql"""select nextval('PROJECT_LINK_SEQ') from generate_series(1, $len)""".as[Long].list
  }

  def fetchRoadwayIds(len: Int): List[Long] = {
    sql"""select nextval('ROADWAY_SEQ') from generate_series(1, $len)""".as[Long].list
  }

  def fetchCalibrationPointIds(len: Int): List[Long] = {
    sql"""select nextval('CALIBRATION_POINT_SEQ') from generate_series(1, $len)""".as[Long].list
  }

  def fetchNodeIds(len: Int): List[Long] = {
    sql"""select nextval('NODE_SEQ') from generate_series(1, $len)""".as[Long].list
  }

  def fetchNodePointIds(len: Int): List[Long] = {
    sql"""select nextval('NODE_POINT_SEQ') from generate_series(1, $len)""".as[Long].list
  }

  def fetchJunctionPointIds(len: Int): List[Long] = {
    sql"""select nextval('JUNCTION_POINT_SEQ') from generate_series(1, $len)""".as[Long].list
  }

  def fetchJunctionIds(len: Int): List[Long] = {
    sql"""select nextval('JUNCTION_SEQ') from generate_series(1, $len)""".as[Long].list
  }

  def fetchLinearLocationIds(len: Int): List[Long] = {
    sql"""select nextval('LINEAR_LOCATION_SEQ') from generate_series(1, $len)""".as[Long].list
  }

  def getMunicipalities: Seq[Int] = {
    sql"""
      select id from municipality
    """.as[Int].list
  }

}

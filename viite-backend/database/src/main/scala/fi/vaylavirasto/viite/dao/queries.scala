package fi.vaylavirasto.viite.dao

import scalikejdbc.{HasExtractor, SQL, scalikejdbcSQLInterpolationImplicitDef}

object Queries {

  def nextViiteProjectId = sql"SELECT nextval('viite_project_seq')"

  def nextProjectLinkId = sql"SELECT nextval('project_link_seq')"

  def nextViitePrimaryKeyId = sql"SELECT nextval('viite_general_seq')"

  def nextRoadwayId = sql"SELECT nextval('roadway_seq')"

  def nextLinearLocationId = sql"SELECT nextval('linear_location_seq')"

  def nextRoadNameId = sql"SELECT nextval('road_name_seq')"

  def nextRoadwayNumber = sql"SELECT nextval('roadway_number_seq')"

  def nextRoadNetworkErrorId = sql"SELECT nextval('road_network_error_seq')"

  def nextRoadwayChangeLink = sql"SELECT nextval('roadway_change_link')"

  @deprecated ("Table published_road_network is no longer in use, and is empty.")
  def nextPublishedRoadNetworkId = sql"SELECT nextval('published_road_network_seq')"

  def nextRoadwayPointId = sql"SELECT nextval('roadway_point_seq')"

  def nextCalibrationPointId = sql"SELECT nextval('calibration_point_seq')"

  def nextNodeId = sql"SELECT nextval('node_seq')"

  def nextNodeNumber = sql"SELECT nextval('node_number_seq')"

  def nextNodePointId = sql"SELECT nextval('node_point_seq')"

  def nextJunctionId = sql"SELECT nextval('junction_seq')"

  def nextJunctionPointId = sql"SELECT nextval('junction_point_seq')"

  def nextProjectCalibrationPointId = sql"SELECT nextval('project_cal_point_id_seq')"

  def fetchRoadwayIds(len: Int): SQL[Long, HasExtractor] = {
    sql"SELECT nextval('roadway_seq') FROM generate_series(1, $len)".map(_.long(1))
  }

  def fetchNodeIds(len: Int): SQL[Long, HasExtractor] = {
    sql"""SELECT nextval('node_seq') FROM generate_series(1, $len)""".map(_.long(1))
  }

  def fetchJunctionIds(len: Int): SQL[Long, HasExtractor] = {
    sql"""SELECT nextval('junction_seq') FROM generate_series(1, $len)""".map(_.long(1))
  }

  def fetchProjectLinkIds(len: Int): SQL[Long, HasExtractor] = {
    sql"""SELECT nextval('project_link_seq') FROM generate_series(1, $len)""".map(_.long(1))
  }

  def fetchCalibrationPointIds(len: Int): SQL[Long, HasExtractor] = {
    sql"""SELECT nextval('calibration_point_seq') FROM generate_series(1, $len)""".map(_.long(1))
  }

  def fetchNodePointIds(len: Int): SQL[Long, HasExtractor] = {
    sql"""SELECT nextval('node_point_seq') FROM generate_series(1, $len)""".map(_.long(1))
  }

  def fetchJunctionPointIds(len: Int): SQL[Long, HasExtractor] = {
    sql"""SELECT nextval('junction_point_seq') FROM generate_series(1, $len)""".map(_.long(1))
  }

  def fetchLinearLocationIds(len: Int): SQL[Long, HasExtractor] = {
    sql"""SELECT nextval('linear_location_seq') FROM generate_series(1, $len)""".map(_.long(1))
  }

  def fetchMunicipalityIds: SQL[Int, HasExtractor] = {
    sql"SELECT id FROM municipality".map(_.int(1))
  }

}

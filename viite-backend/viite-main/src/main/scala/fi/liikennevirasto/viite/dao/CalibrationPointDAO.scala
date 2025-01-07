package fi.liikennevirasto.viite.dao

import scalikejdbc._
import scalikejdbc.interpolation.SQLSyntax.joinWithOr
import scalikejdbc.jodatime.JodaWrappedResultSet.fromWrappedResultSetToJodaWrappedResultSet
import fi.vaylavirasto.viite.dao.{BaseDAO, Sequences}
import fi.vaylavirasto.viite.model.{CalibrationPoint, CalibrationPointLocation, CalibrationPointType}
import fi.vaylavirasto.viite.util.ViiteException

object CalibrationPointDAO extends BaseDAO {

  object CalibrationPoint extends SQLSyntaxSupport[CalibrationPoint] {
    override val tableName = "calibration_point"
    def apply(rs: WrappedResultSet): CalibrationPoint = {
      new CalibrationPoint(
        id             = rs.long("id"),
        roadwayPointId = rs.long("roadway_point_id"),
        linkId         = rs.string("link_id"),
        roadwayNumber  = rs.long("roadway_number"),
        addrM          = rs.long("addr_m"),
        startOrEnd     = CalibrationPointLocation(rs.int("start_end")),
        typeCode       = CalibrationPointType(rs.int("type")),
        validFrom      = rs.jodaDateTimeOpt("valid_from"),
        validTo        = rs.jodaDateTimeOpt("valid_to"),
        createdBy      = rs.string("created_by"),
        createdTime    = rs.jodaDateTimeOpt("created_time")
      )
    }
  }

  def create(cp: CalibrationPoint): Long = {
    logger.debug(s"Trying to create a cp ${cp}")
    create(cp.roadwayPointId, cp.linkId, cp.startOrEnd, cp.typeCode, cp.createdBy)

    val cpOption = CalibrationPointDAO.fetch(cp.linkId, cp.startOrEnd.value) // get the cp for the id
    cpOption.getOrElse(throw ViiteException("The created (or so I thought) calibration point was not found!"))
      .id
  }

  def create(roadwayPointId: Long,
             linkId: String,
             startOrEnd: CalibrationPointLocation,
             calType: CalibrationPointType,
             createdBy: String): Unit = {

    runUpdateToDb(sql"""
      INSERT INTO calibration_point (id, roadway_point_id, link_id, start_end, type, created_by)
      VALUES (${Sequences.nextCalibrationPointId}, $roadwayPointId, $linkId, ${startOrEnd.value}, ${calType.value}, $createdBy)
      """)
  }


  def fetch(id: Long): CalibrationPoint = {
    val query = sql"""
         SELECT cp.id, roadway_point_id, link_id, roadway_number, rp.addr_m, start_end, type, valid_from, valid_to, cp.created_by, cp.created_time
         FROM calibration_point cp
         JOIN roadway_point rp
          ON rp.id = cp.roadway_point_id
         WHERE cp.id = $id
     """
    runSelectSingleOption(query.map(CalibrationPoint.apply)).getOrElse {
      throw new NoSuchElementException("No value returned for fetch")
    }
  }

  def fetch(linkId: String, startOrEnd: Long): Option[CalibrationPoint] = {
    val query = sql"""
         SELECT cp.id, roadway_point_id, link_id, roadway_number, rp.addr_m, start_end, type, valid_from, valid_to, cp.created_by, cp.created_time
         FROM calibration_point cp
         JOIN roadway_point rp ON rp.id = cp.roadway_point_id
         WHERE cp.link_id = $linkId
         AND cp.start_end = $startOrEnd
         AND cp.valid_to IS NULL
     """
    runSelectFirst(query.map(CalibrationPoint.apply))
  }


  private def queryList(query: SQL[Nothing, NoExtractor]): Seq[CalibrationPoint] = {
    runSelectQuery(query.map(CalibrationPoint.apply))
  }



  def fetchByRoadwayPointId(roadwayPointId: Long): Seq[CalibrationPoint] = {
    val query = sql"""
        SELECT cp.id, cp.roadway_point_id, cp.link_id, rp.roadway_number, rp.addr_m, cp.start_end, cp.type,
          cp.valid_from, cp.valid_to, cp.created_by, cp.created_time
        FROM calibration_point cp
        JOIN roadway_point rp ON rp.id = cp.roadway_point_id
          WHERE cp.roadway_point_id = $roadwayPointId AND cp.valid_to IS NULL
      """
    queryList(query)
  }

  def fetchByRoadwayPointIds(roadwayPointIds: Seq[Long]): Seq[CalibrationPoint] = {
    if (roadwayPointIds.isEmpty) {
      Seq()
    } else {
      val query =
        sql"""
          SELECT cp.id, roadway_point_id, link_id, roadway_number, rp.addr_m, start_end, cp.type, valid_from, valid_to,
            cp.created_by, cp.created_time
          FROM calibration_point cp
          JOIN roadway_point rp ON rp.id = cp.roadway_point_id
          WHERE cp.roadway_point_id IN ($roadwayPointIds)
          AND cp.valid_to IS NULL
        """
      queryList(query)
    }
  }

  def fetchIdByRoadwayNumberSection(roadwayNumber: Long, startAddr: Long, endAddr: Long): Set[Long] = {
    val query =  sql"""
         SELECT cp.id
         FROM calibration_point cp
         JOIN roadway_point rp ON rp.id = cp.roadway_point_id
         WHERE rp.roadway_number = $roadwayNumber
         AND rp.addr_m BETWEEN $startAddr AND $endAddr
         AND cp.valid_to IS NULL
     """
    runSelectQuery(query.map(_.long(1))).toSet
  }

  def fetchByLinkId(linkIds: Iterable[String]): Seq[CalibrationPoint] = {
    if (linkIds.isEmpty) {
      Seq()
    } else {
      val query =
        sql"""
      SELECT cp.id, roadway_point_id, link_id, roadway_number, rp.addr_m, cp.start_end, cp.type, valid_from, valid_to, cp.created_by, cp.created_time
      FROM calibration_point cp
      JOIN roadway_point rp
      ON rp.id = cp.roadway_point_id
      WHERE cp.link_id in ($linkIds) AND cp.valid_to IS NULL
      """
      queryList(query)
    }
  }

  def fetch(calibrationPointsLinkIds: Seq[String], startOrEnd: Long): Seq[CalibrationPoint] = {
    val whereConditions = calibrationPointsLinkIds.map(p =>
      sqls" (link_id = $p and start_end = $startOrEnd)"
    )
    // joinWithOr is a helper function that joins the conditions with OR
    val whereClause = sqls"WHERE${joinWithOr(whereConditions: _*)}"

    val query = sql"""
     SELECT cp.id, roadway_point_id, link_id, roadway_number, rp.addr_m, start_end, type, valid_from, valid_to, cp.created_by, cp.created_time
     FROM calibration_point cp
     JOIN roadway_point rp
     ON rp.id = cp.roadway_point_id
     $whereClause
       """
    queryList(query)
  }

  def expireById(ids: Iterable[Long]): Unit = {
    val query =
      sql"""
        UPDATE calibration_point
        SET valid_to = current_timestamp
        WHERE valid_to IS NULL
        AND id in ($ids)
      """
    if (ids.isEmpty)
      0
    else
      runUpdateToDb(query)
  }
}

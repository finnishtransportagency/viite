package fi.liikennevirasto.viite.dao

import org.joda.time.DateTime
import slick.driver.JdbcDriver.backend.Database.dynamicSession
import slick.jdbc.StaticQuery.interpolation
import slick.jdbc.{GetResult, PositionedResult, StaticQuery => Q}
import fi.vaylavirasto.viite.dao.{BaseDAO, Sequences}
import fi.vaylavirasto.viite.model.{CalibrationPoint, CalibrationPointLocation, CalibrationPointType}
import fi.vaylavirasto.viite.util.ViiteException

object CalibrationPointDAO extends BaseDAO {

  implicit val getRoadwayPointRow: GetResult[CalibrationPoint] = new GetResult[CalibrationPoint] {
    def apply(r: PositionedResult): CalibrationPoint = {
      val calibrationPointId = r.nextLong()
      val roadwayPointId = r.nextLong()
      val linkId = r.nextString()
      val roadwayNumber = r.nextLong()
      val addrMValue = r.nextLong()
      val startOrEnd = CalibrationPointLocation.apply(r.nextInt())
      val typeCode = CalibrationPointType.apply(r.nextLong().toInt)
      val validFrom = r.nextDateOption().map(d => new DateTime(d.getTime))
      val validTo = r.nextDateOption().map(d => new DateTime(d.getTime))
      val createdBy = r.nextString()
      val createdTime = r.nextDateOption().map(d => new DateTime(d.getTime))

      CalibrationPoint(calibrationPointId, roadwayPointId, linkId, roadwayNumber, addrMValue, startOrEnd, typeCode, validFrom, validTo, createdBy, createdTime)
    }
  }

  def create(cp: CalibrationPoint): Long = {
    create(cp.roadwayPointId, cp.linkId, cp.startOrEnd, cp.typeCode, cp.createdBy)
    cp.id
  }

  def create(cp: CalibrationPoint): Long = {
    logger.debug(s"Trying to create a CP ${cp}")
    create(cp.roadwayPointId, cp.linkId, cp.startOrEnd, cp.typeCode, cp.createdBy)

    val cpOption = CalibrationPointDAO.fetch(cp.linkId, cp.startOrEnd.value) // get the CP for the id
    cpOption.getOrElse(throw ViiteException("The created (or so I thought) calibration point was not found!"))
      .id
  }

  def create(roadwayPointId: Long,
             linkId: String,
             startOrEnd: CalibrationPointLocation,
             calType: CalibrationPointType,
             createdBy: String): Unit = {

    runUpdateToDb(s"""
      Insert Into CALIBRATION_POINT (ID, ROADWAY_POINT_ID, LINK_ID, START_END, TYPE, CREATED_BY) VALUES
      (${Sequences.nextCalibrationPointId}, $roadwayPointId, '$linkId', ${startOrEnd.value}, ${calType.value}, '$createdBy')
      """)
  }

  def fetch(id: Long): CalibrationPoint = {
    sql"""
         SELECT CP.ID, ROADWAY_POINT_ID, LINK_ID, ROADWAY_NUMBER, RP.ADDR_M, START_END, TYPE, VALID_FROM, VALID_TO, CP.CREATED_BY, CP.CREATED_TIME
         FROM CALIBRATION_POINT CP
         JOIN ROADWAY_POINT RP
          ON RP.ID = CP.ROADWAY_POINT_ID
         WHERE cp.id = $id
     """.as[CalibrationPoint].first
  }

  def fetch(linkId: String, startOrEnd: Long): Option[CalibrationPoint] = {
    sql"""
         SELECT CP.ID, ROADWAY_POINT_ID, LINK_ID, ROADWAY_NUMBER, RP.ADDR_M, START_END, TYPE, VALID_FROM, VALID_TO, CP.CREATED_BY, CP.CREATED_TIME
         FROM CALIBRATION_POINT CP
         JOIN ROADWAY_POINT RP
          ON RP.ID = CP.ROADWAY_POINT_ID
         WHERE CP.LINK_ID = $linkId AND CP.START_END = $startOrEnd AND CP.VALID_TO IS NULL
     """.as[CalibrationPoint].firstOption
  }

  def fetchByRoadwayPointId(roadwayPointId: Long): Seq[CalibrationPoint] = {
    val query = s"""
        SELECT CP.ID, CP.ROADWAY_POINT_ID, CP.LINK_ID, RP.ROADWAY_NUMBER, RP.ADDR_M, CP.START_END, CP.TYPE,
          CP.VALID_FROM, CP.VALID_TO, CP.CREATED_BY, CP.CREATED_TIME
        FROM CALIBRATION_POINT CP
        JOIN ROADWAY_POINT RP ON RP.ID = CP.ROADWAY_POINT_ID
          WHERE cp.roadway_point_id = $roadwayPointId AND cp.valid_to is null
      """
    queryList(query)
  }

  def fetchByRoadwayPointIds(roadwayPointIds: Seq[Long]): Seq[CalibrationPoint] = {
    if (roadwayPointIds.isEmpty) {
      Seq()
    } else {
      val query =
        s"""
          SELECT CP.ID, ROADWAY_POINT_ID, LINK_ID, ROADWAY_NUMBER, RP.ADDR_M, START_END, CP.TYPE, VALID_FROM, VALID_TO,
            CP.CREATED_BY, CP.CREATED_TIME
          FROM CALIBRATION_POINT CP
          JOIN ROADWAY_POINT RP ON RP.ID = CP.ROADWAY_POINT_ID
          WHERE cp.roadway_point_id in (${roadwayPointIds.mkString(", ")}) AND cp.valid_to is null
        """
      queryList(query)
    }
  }

  def fetchIdByRoadwayNumberSection(roadwayNumber: Long, startAddr: Long, endAddr: Long): Set[Long] = {
    sql"""
         SELECT CP.ID
         FROM CALIBRATION_POINT CP
         JOIN ROADWAY_POINT RP ON RP.ID = CP.ROADWAY_POINT_ID
         WHERE RP.roadway_number = $roadwayNumber AND RP.ADDR_M BETWEEN $startAddr AND $endAddr AND CP.VALID_TO IS NULL
     """.as[Long].list.toSet
  }

  def fetchByLinkId(linkIds: Iterable[String]): Seq[CalibrationPoint] = {
    if (linkIds.isEmpty) {
      Seq()
    } else {
      val query =
        s"""
      SELECT CP.ID, ROADWAY_POINT_ID, LINK_ID, ROADWAY_NUMBER, RP.ADDR_M, CP.START_END, CP.TYPE, VALID_FROM, VALID_TO, CP.CREATED_BY, CP.CREATED_TIME
      FROM CALIBRATION_POINT CP
      JOIN ROADWAY_POINT RP
      ON RP.ID = CP.ROADWAY_POINT_ID
      WHERE CP.link_id in (${linkIds.map(lid => "'" + lid + "'").mkString(", ")}) AND CP.VALID_TO IS NULL
      """
      queryList(query)
    }
  }

  def fetch(calibrationPointsLinkIds: Seq[String], startOrEnd: Long): Seq[CalibrationPoint] = {
    val whereClause = calibrationPointsLinkIds.map(p => s" (link_id = '$p' and start_end = $startOrEnd)").mkString(" where ", " or ", "")
    val query = s"""
     SELECT CP.ID, ROADWAY_POINT_ID, LINK_ID, ROADWAY_NUMBER, RP.ADDR_M, START_END, TYPE, VALID_FROM, VALID_TO, CP.CREATED_BY, CP.CREATED_TIME
     FROM CALIBRATION_POINT CP
     JOIN ROADWAY_POINT RP
     ON RP.ID = CP.ROADWAY_POINT_ID
      $whereClause
       """
    queryList(query)
  }

  def expireById(ids: Iterable[Long]): Unit = {
    val query =
      s"""
        Update CALIBRATION_POINT Set valid_to = current_timestamp where valid_to IS NULL and id in (${ids.mkString(", ")})
      """
    if (ids.isEmpty)
      0
    else
      runUpdateToDb(query)
  }

  private def queryList(query: String): Seq[CalibrationPoint] = {
    Q.queryNA[CalibrationPoint](query).list
  }
}

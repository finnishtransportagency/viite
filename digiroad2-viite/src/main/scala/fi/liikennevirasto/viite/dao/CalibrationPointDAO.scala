package fi.liikennevirasto.viite.dao

import fi.liikennevirasto.digiroad2.dao.Sequences
import org.joda.time.DateTime
import slick.driver.JdbcDriver.backend.Database.dynamicSession
import slick.jdbc.StaticQuery.interpolation
import slick.jdbc.{GetResult, PositionedResult, StaticQuery => Q}

object CalibrationPointDAO {

  sealed trait CalibrationPointType {
    def value: Int
  }

  object CalibrationPointType {
    val values = Set(Optional, SemiMandatory, Mandatory)

    def apply(intValue: Int): CalibrationPointType = {
      values.find(_.value == intValue).getOrElse(Optional)
    }

    case object Optional extends CalibrationPointType {def value = 0}
    case object SemiMandatory extends CalibrationPointType {def value = 1;}
    case object Mandatory extends CalibrationPointType {def value = 2;}
  }

  case class CalibrationPoint(id: Long, roadwayPointId: Long, linkId: Long, roadwayNumber: Long, addrM: Long, startOrEnd: Long, typeCode: CalibrationPointType, validFrom: Option[DateTime] = None, validTo: Option[DateTime] = None, createdBy: String, createdTime: Option[DateTime] = None)

  implicit val getRoadwayPointRow = new GetResult[CalibrationPoint] {
    def apply(r: PositionedResult) = {
      val calibrationPointId = r.nextLong()
      val roadwayPointId = r.nextLong()
      val linkId = r.nextLong()
      val roadwayNumber = r.nextLong()
      val addrMValue = r.nextLong()
      val startOrEnd = r.nextLong()
      val typeCode = CalibrationPointType.apply(r.nextLong().toInt)
      val validFrom = r.nextDateOption().map(d => new DateTime(d.getTime))
      val validTo = r.nextDateOption().map(d => new DateTime(d.getTime))
      val createdBy = r.nextString()
      val createdTime = r.nextDateOption().map(d => new DateTime(d.getTime))

      CalibrationPoint(calibrationPointId, roadwayPointId, linkId, roadwayNumber, addrMValue, startOrEnd, typeCode, validFrom, validTo, createdBy, createdTime)
    }
  }


  def create(calibrationPoint: CalibrationPoint): Unit = {
    sqlu"""
      Insert Into CALIBRATION_POINT (ID, ROADWAY_POINT_ID, LINK_ID, START_END, TYPE, CREATED_BY) VALUES
      (${Sequences.nextCalibrationPointId}, ${calibrationPoint.roadwayPointId}, ${calibrationPoint.linkId}, ${calibrationPoint.startOrEnd}, ${calibrationPoint.typeCode.value}, ${calibrationPoint.createdBy})
      """.execute
  }

  def create(roadwayPointId: Long, linkId: Long, startOrEnd: Long, calType: CalibrationPointType, createdBy: String) = {
    sqlu"""
      Insert Into CALIBRATION_POINT (ID, ROADWAY_POINT_ID, LINK_ID, START_END, TYPE, CREATED_BY) VALUES
      (${Sequences.nextCalibrationPointId}, $roadwayPointId, $linkId, $startOrEnd, ${calType.value}, $createdBy)
      """.execute
  }

  def fetch(id:Long) : CalibrationPoint = {
    sql"""
         SELECT CP.ID, ROADWAY_POINT_ID, LINK_ID, ROADWAY_NUMBER, RP.ADDR_M, START_END, TYPE, VALID_FROM, VALID_TO, CP.CREATED_BY, CP.CREATED_TIME
         FROM CALIBRATION_POINT CP
         JOIN ROADWAY_POINT RP
          ON RP.ID = CP.ROADWAY_POINT_ID
         WHERE cp.id = $id
     """.as[CalibrationPoint].first
  }

  def fetch(linkId:Long, startOrEnd: Long) : Option[CalibrationPoint] = {
    sql"""
         SELECT CP.ID, ROADWAY_POINT_ID, LINK_ID, ROADWAY_NUMBER, RP.ADDR_M, START_END, TYPE, VALID_FROM, VALID_TO, CP.CREATED_BY, CP.CREATED_TIME
         FROM CALIBRATION_POINT CP
         JOIN ROADWAY_POINT RP
          ON RP.ID = CP.ROADWAY_POINT_ID
         WHERE cp.link_id = $linkId and start_end = $startOrEnd
     """.as[CalibrationPoint].firstOption
  }

  def fetchByRoadwayPoint(roadwayPointId:Long) : Option[CalibrationPoint] = {
    sql"""
         SELECT CP.ID, ROADWAY_POINT_ID, LINK_ID, ROADWAY_NUMBER, RP.ADDR_M, START_END, TYPE, VALID_FROM, VALID_TO, CP.CREATED_BY, CP.CREATED_TIME
         FROM CALIBRATION_POINT CP
         JOIN ROADWAY_POINT RP
          ON RP.ID = CP.ROADWAY_POINT_ID
         WHERE cp.roadway_point_id = $roadwayPointId
     """.as[CalibrationPoint].firstOption
  }

  def fetch(calibrationPointsLinkIds: Seq[Long], startOrEnd: Long): Seq[CalibrationPoint] = {
    val whereClause = calibrationPointsLinkIds.map(p => s" (link_id = $p and start_end = $startOrEnd)").mkString(" where ", " or ", "")
    val query = s"""
     SELECT CP.ID, ROADWAY_POINT_ID, LINK_ID, ROADWAY_NUMBER, RP.ADDR_M, START_END, TYPE, VALID_FROM, VALID_TO, CP.CREATED_BY, CP.CREATED_TIME
     FROM CALIBRATION_POINT CP
     JOIN ROADWAY_POINT RP
     ON RP.ID = CP.ROADWAY_POINT_ID
      $whereClause
       """
    queryList(query)
  }

  private def queryList(query: String): Seq[CalibrationPoint] = {
    Q.queryNA[CalibrationPoint](query).list
  }
}

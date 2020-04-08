package fi.liikennevirasto.viite.dao

import fi.liikennevirasto.digiroad2.dao.Sequences
import fi.liikennevirasto.viite.dao.BeforeAfter.{After, Before}
import org.joda.time.DateTime
import slick.driver.JdbcDriver.backend.Database.dynamicSession
import slick.jdbc.StaticQuery.interpolation
import slick.jdbc.{GetResult, PositionedResult, StaticQuery => Q}

object CalibrationPointDAO {

  case class CalibrationPointLike(addrM: Option[Long], typeCode: Option[CalibrationPointType], startOrEnd: Option[CalibrationPointLocation]) {}

  trait CalibrationPointType extends Ordered[CalibrationPointType] {
    def value: Int
    def compare(that: CalibrationPointType): Int = {
      this.value - that.value
    }
  }

  object CalibrationPointType {
    val values = Set(UserDefinedCP, JunctionPointCP, RoadAddressCP)

    def apply(intValue: Int): CalibrationPointType = {
      values.find(_.value == intValue).getOrElse(UserDefinedCP)
    }

    case object UserDefinedCP extends CalibrationPointType {def value = 0}
    case object JunctionPointCP extends CalibrationPointType {def value = 1}
    case object RoadAddressCP extends CalibrationPointType {def value = 2}
  }

  trait CalibrationPointLocation {
    def value: Int
  }

  object CalibrationPointLocation {
    val values = Set(StartOfLink, EndOfLink, Unknown)

    def apply(intValue: Int): CalibrationPointLocation = {
      values.find(_.value == intValue).getOrElse(Unknown)
    }

    def apply(pos: BeforeAfter): CalibrationPointLocation = {
      pos match {
        case After => StartOfLink
        case Before => EndOfLink
        case _ => Unknown
      }
    }

    case object StartOfLink extends CalibrationPointLocation {def value = 0}
    case object EndOfLink extends CalibrationPointLocation {def value = 1}
    case object Unknown extends CalibrationPointLocation {def value = 99}
  }

  case class CalibrationPoint(id: Long, roadwayPointId: Long, linkId: Long, roadwayNumber: Long, addrM: Long, startOrEnd: CalibrationPointLocation, typeCode: CalibrationPointType, validFrom: Option[DateTime] = None, validTo: Option[DateTime] = None, createdBy: String, createdTime: Option[DateTime] = None)

  implicit val getRoadwayPointRow = new GetResult[CalibrationPoint] {
    def apply(r: PositionedResult) = {
      val calibrationPointId = r.nextLong()
      val roadwayPointId = r.nextLong()
      val linkId = r.nextLong()
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


  def create(calibrationPoint: CalibrationPoint): Unit = {
    sqlu"""
      Insert Into CALIBRATION_POINT (ID, ROADWAY_POINT_ID, LINK_ID, START_END, TYPE, CREATED_BY) VALUES
      (${Sequences.nextCalibrationPointId}, ${calibrationPoint.roadwayPointId}, ${calibrationPoint.linkId}, ${calibrationPoint.startOrEnd.value}, ${calibrationPoint.typeCode.value}, ${calibrationPoint.createdBy})
      """.execute
  }

  def create(roadwayPointId: Long, linkId: Long, startOrEnd: CalibrationPointLocation, calType: CalibrationPointType, createdBy: String) = {
    sqlu"""
      Insert Into CALIBRATION_POINT (ID, ROADWAY_POINT_ID, LINK_ID, START_END, TYPE, CREATED_BY) VALUES
      (${Sequences.nextCalibrationPointId}, $roadwayPointId, $linkId, ${startOrEnd.value}, ${calType.value}, $createdBy)
      """.execute
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

  def fetch(linkId: Long, startOrEnd: Long): Option[CalibrationPoint] = {
    sql"""
         SELECT CP.ID, ROADWAY_POINT_ID, LINK_ID, ROADWAY_NUMBER, RP.ADDR_M, START_END, TYPE, VALID_FROM, VALID_TO, CP.CREATED_BY, CP.CREATED_TIME
         FROM CALIBRATION_POINT CP
         JOIN ROADWAY_POINT RP
          ON RP.ID = CP.ROADWAY_POINT_ID
         WHERE CP.LINK_ID = $linkId AND CP.START_END = $startOrEnd AND CP.VALID_TO IS NULL
     """.as[CalibrationPoint].firstOption
  }

  def fetchByRoadwayPointId(roadwayPointId: Long): Option[CalibrationPoint] = {
    sql"""
         SELECT CP.ID, ROADWAY_POINT_ID, LINK_ID, ROADWAY_NUMBER, RP.ADDR_M, START_END, CP.TYPE, VALID_FROM, VALID_TO, CP.CREATED_BY, CP.CREATED_TIME
         FROM CALIBRATION_POINT CP
         JOIN ROADWAY_POINT RP
          ON RP.ID = CP.ROADWAY_POINT_ID
         WHERE cp.roadway_point_id = $roadwayPointId
     """.as[CalibrationPoint].firstOption
  }

  def fetchIdByRoadwayNumberSection(roadwayNumber: Long, startAddr: Long, endAddr: Long): Set[Long] = {
    sql"""
         SELECT CP.ID
         FROM CALIBRATION_POINT CP
         JOIN ROADWAY_POINT RP ON RP.ID = CP.ROADWAY_POINT_ID
         WHERE RP.roadway_number = $roadwayNumber AND RP.ADDR_M BETWEEN $startAddr AND $endAddr AND CP.VALID_TO IS NULL
     """.as[Long].list.toSet
  }

  def fetchByRoadwayPointInExpiredJunctionPoint(roadwayPointId: Long, addr: Long): Set[Long] = {
    sql"""
         SELECT CP.ID
         FROM CALIBRATION_POINT CP
         JOIN ROADWAY_POINT RP ON RP.ID = CP.ROADWAY_POINT_ID
         JOIN JUNCTION_POINT JP ON JP.ROADWAY_POINT_ID = RP.ID
         JOIN JUNCTION J ON J.ID = JP.JUNCTION_ID
         WHERE cp.roadway_point_id = $roadwayPointId AND rp.addr_m = $addr AND CP.VALID_TO IS NULL AND JP.VALID_TO IS NOT NULL
     """.as[Long].list.toSet
  }

  def fetchByLinkId(linkIds: Iterable[Long]): Seq[CalibrationPoint] = {
    if (linkIds.isEmpty) {
      Seq()
    } else {
      val query =
        s"""
      SELECT CP.ID, ROADWAY_POINT_ID, LINK_ID, ROADWAY_NUMBER, RP.ADDR_M, CP.START_END, CP.TYPE, VALID_FROM, VALID_TO, CP.CREATED_BY, CP.CREATED_TIME
      FROM CALIBRATION_POINT CP
      JOIN ROADWAY_POINT RP
      ON RP.ID = CP.ROADWAY_POINT_ID
      WHERE CP.link_id in (${linkIds.mkString(", ")}) AND CP.VALID_TO IS NULL
      """
      queryList(query)
    }
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

  def expireById(ids: Iterable[Long]): Int = {
    val query =
      s"""
        Update CALIBRATION_POINT Set valid_to = sysdate where valid_to IS NULL and id in (${ids.mkString(", ")})
      """
    if (ids.isEmpty)
      0
    else
      Q.updateNA(query).first
  }

  private def queryList(query: String): Seq[CalibrationPoint] = {
    Q.queryNA[CalibrationPoint](query).list
  }
}

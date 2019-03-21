package fi.liikennevirasto.viite.dao

import fi.liikennevirasto.digiroad2.dao.Sequences
import org.joda.time.DateTime
import slick.driver.JdbcDriver.backend.Database.dynamicSession
import slick.jdbc.StaticQuery.interpolation
import slick.jdbc.{GetResult, PositionedResult}

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

  case class CalibrationPoint(id: Long, roadwayPointId: Long, linkId: Long, roadwayNumber: Long, addrM: Long, startOrEnd: Long, typeCode: CalibrationPointType, validFrom: DateTime, validTo: Option[DateTime] = None, createdBy: String, createdTime: DateTime)

  implicit val getRoadwayPointRow = new GetResult[CalibrationPoint] {
    def apply(r: PositionedResult) = {
      val calibrationPointId = r.nextLong()
      val roadwayPointId = r.nextLong()
      val linkId = r.nextLong()
      val roadwayNumber = r.nextLong()
      val addrMValue = r.nextLong()
      val startOrEnd = r.nextLong()
      val typeCode = CalibrationPointType.apply(r.nextLong().toInt)
      val validFrom = new DateTime(r.nextDate())
      val validTo = r.nextDateOption().map(d => new DateTime(d.getTime))
      val createdBy = r.nextString()
      val createdTime = new DateTime(r.nextDate())

      CalibrationPoint(calibrationPointId, roadwayPointId, linkId, roadwayNumber, addrMValue, startOrEnd, typeCode, validFrom, validTo, createdBy, createdTime)
    }
  }


  def create(calibrationPoint: CalibrationPoint): Unit = {
    sqlu"""
      Insert Into CALIBRATION_POINT (ID, ROADWAY_POINT_ID, LINK_ID, START_END, TYPE, VALID_FROM, CREATED_BY) VALUES
      (${Sequences.nextCalibrationPointId}, ${calibrationPoint.roadwayPointId}, ${calibrationPoint.linkId}, ${calibrationPoint.startOrEnd}, ${calibrationPoint.typeCode.value}, ${calibrationPoint.validFrom}, ${calibrationPoint.createdBy})
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
}

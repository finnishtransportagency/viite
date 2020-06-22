package fi.liikennevirasto.viite.dao

import fi.liikennevirasto.digiroad2.dao.Sequences
import fi.liikennevirasto.viite._
import fi.liikennevirasto.viite.dao.CalibrationPointDAO.{CalibrationPointLocation, CalibrationPointType}
import fi.liikennevirasto.viite.util.CalibrationPointsUtils
import org.joda.time.DateTime
import slick.driver.JdbcDriver.backend.Database.dynamicSession
import slick.jdbc.StaticQuery.interpolation
import slick.jdbc.{GetResult, PositionedResult, StaticQuery => Q}

case class RoadwayPoint(id: Long, roadwayNumber: Long, addrMValue: Long, createdBy: String, createdTime: Option[DateTime] = None,
                        modifiedBy: Option[String] = None, modifiedTime: Option[DateTime] = None) {

  def isNew: Boolean = {
    id == NewIdValue
  }

  def isNotNew: Boolean = {
    id != NewIdValue
  }

}

class RoadwayPointDAO extends BaseDAO {

  implicit val getRoadwayPointRow = new GetResult[RoadwayPoint] {
    def apply(r: PositionedResult): RoadwayPoint = {
      val roadwayPointId = r.nextLong()
      val roadwayNumber = r.nextLong()
      val addrMValue = r.nextLong()
      val createdBy = r.nextString()
      val createdTime = r.nextDateOption().map(d => new DateTime(d.getTime))
      val modifiedBy = r.nextStringOption()
      val modifiedTime = r.nextDateOption().map(d => new DateTime(d.getTime))

      RoadwayPoint(roadwayPointId, roadwayNumber, addrMValue, createdBy, createdTime, modifiedBy, modifiedTime)
    }
  }

  def create(roadwayPoint: RoadwayPoint): Long = {
    val id = if (roadwayPoint.isNew) {
      Sequences.nextRoadwayPointId
    } else {
      roadwayPoint.id
    }
    logger.info(s"Insert roadway_point $id (roadwayNumber: ${roadwayPoint.roadwayNumber}, addrM: ${roadwayPoint.addrMValue})")
    sqlu"""
      Insert Into ROADWAY_POINT (ID, ROADWAY_NUMBER, ADDR_M, CREATED_BY, MODIFIED_BY) Values
      ($id, ${roadwayPoint.roadwayNumber}, ${roadwayPoint.addrMValue}, ${roadwayPoint.createdBy}, ${roadwayPoint.createdBy})
      """.execute
    id
  }

  def create(roadwayNumber: Long, addrMValue: Long, createdBy: String): Long = {
    val id = Sequences.nextRoadwayPointId
    logger.info(s"Insert roadway_point $id (roadwayNumber: $roadwayNumber, addrM: $addrMValue)")
    sqlu"""
      Insert Into ROADWAY_POINT (ID, ROADWAY_NUMBER, ADDR_M, CREATED_BY, MODIFIED_BY) Values
      ($id, $roadwayNumber, $addrMValue, $createdBy, $createdBy)
      """.execute
    id
  }

  def update(roadwayPoint: RoadwayPoint): Long = {
    update(Seq(roadwayPoint)).head
  }

  def update(roadwayPoints: Seq[RoadwayPoint]): Seq[Long] = {
    val ps = dynamicSession.prepareStatement("update ROADWAY_POINT SET ROADWAY_NUMBER = ?, ADDR_M = ?, MODIFIED_BY = ?, MODIFIED_TIME = current_timestamp WHERE ID = ?")

    roadwayPoints.foreach {
      rwPoint =>
        logger.info(s"Update roadway_point (id: ${rwPoint.id}, roadwayNumber: ${rwPoint.roadwayNumber}, addr: ${rwPoint.addrMValue})")
        ps.setLong(1, rwPoint.roadwayNumber)
        ps.setLong(2, rwPoint.addrMValue)
        ps.setString(3, rwPoint.modifiedBy.getOrElse("-"))
        ps.setLong(4, rwPoint.id)
        ps.addBatch()
    }
    ps.executeBatch()
    ps.close()
    roadwayPoints.map(_.id)
  }

  def fetch(id: Long): RoadwayPoint = {
    sql"""
      SELECT ID, ROADWAY_NUMBER, ADDR_M, CREATED_BY, CREATED_TIME, MODIFIED_BY, MODIFIED_TIME
      from ROADWAY_POINT
      where id = $id
     """.as[RoadwayPoint].first
  }

  def fetch(roadwayNumber: Long, addrM: Long): Option[RoadwayPoint] = {
    sql"""
      SELECT ID, ROADWAY_NUMBER, ADDR_M, CREATED_BY, CREATED_TIME, MODIFIED_BY, MODIFIED_TIME
      from ROADWAY_POINT
      where ROADWAY_NUMBER= $roadwayNumber and ADDR_M = $addrM
     """.as[RoadwayPoint].firstOption
  }

  def fetch(points: Seq[(Long, Long)]): Seq[RoadwayPoint] = {
    if (points.isEmpty) {
      Seq()
    } else {
      val whereClause = points.map(p => s" (roadway_number = ${p._1} and addr_m = ${p._2})").mkString(" where ", " or ", "")
      val query =
        s"""
      SELECT ID, ROADWAY_NUMBER, ADDR_M, CREATED_BY, CREATED_TIME, MODIFIED_BY, MODIFIED_TIME
      from ROADWAY_POINT $whereClause
       """
      queryList(query)
    }
  }

  def fetchByRoadwayNumber(roadwayNumber: Long): Seq[RoadwayPoint] = {
    fetchByRoadwayNumbers(Seq(roadwayNumber))
  }

  def fetchByRoadwayNumberAndAddresses(roadwayNumber: Long, startAddrM: Long, endAddrM: Long): Seq[RoadwayPoint] = {
    val query =
      s"""
      SELECT ID, ROADWAY_NUMBER, ADDR_M, CREATED_BY, CREATED_TIME, MODIFIED_BY, MODIFIED_TIME
      from ROADWAY_POINT where ROADWAY_NUMBER= $roadwayNumber and ADDR_M >= $startAddrM and ADDR_M <= $endAddrM
       """
    queryList(query)
  }

  def fetchByRoadwayNumbers(roadwayNumber: Iterable[Long]): Seq[RoadwayPoint] = {
    if (roadwayNumber.isEmpty) {
      Seq()
    } else {
      val query = s"""
        SELECT ID, ROADWAY_NUMBER, ADDR_M, CREATED_BY, CREATED_TIME, MODIFIED_BY, MODIFIED_TIME
          from ROADWAY_POINT where ROADWAY_NUMBER IN (${roadwayNumber.mkString(", ")})
       """
      queryList(query)
    }
  }

  def toRoadwayAndLinearLocation(p: ProjectLink):(LinearLocation, Roadway) = {
    val startDate = p.startDate.getOrElse(DateTime.now()).minusDays(1)

    (LinearLocation(-1000, 1, p.linkId, p.startMValue, p.endMValue, p.sideCode, p.linkGeometryTimeStamp,
      (CalibrationPointsUtils.toCalibrationPointReference(p.startCalibrationPoint),
        CalibrationPointsUtils.toCalibrationPointReference(p.endCalibrationPoint)),
      p.geometry, p.linkGeomSource, p.roadwayNumber, Some(startDate), p.endDate),
      Roadway(-1000, p.roadwayNumber, p.roadNumber, p.roadPartNumber, p.roadType, p.track, p.discontinuity, p.startAddrMValue, p.endAddrMValue, p.reversed, startDate, p.endDate,
        p.createdBy.getOrElse("-"), p.roadName, p.ely, TerminationCode.NoTermination, DateTime.now(), None))
  }

  private def queryList(query: String): Seq[RoadwayPoint] = {
    Q.queryNA[RoadwayPoint](query).list
  }
}

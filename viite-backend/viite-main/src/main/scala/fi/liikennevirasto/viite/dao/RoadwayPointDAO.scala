package fi.liikennevirasto.viite.dao

import fi.liikennevirasto.viite._
import fi.liikennevirasto.viite.util.CalibrationPointsUtils
import fi.vaylavirasto.viite.dao.{BaseDAO, Sequences}
import fi.vaylavirasto.viite.model.AddrMRange
import org.joda.time.DateTime
import scalikejdbc._
import scalikejdbc.jodatime.JodaWrappedResultSet.fromWrappedResultSetToJodaWrappedResultSet

case class RoadwayPoint(id: Long, roadwayNumber: Long, addrMValue: Long, createdBy: String, createdTime: Option[DateTime] = None,
                        modifiedBy: Option[String] = None, modifiedTime: Option[DateTime] = None) {

  def isNew:    Boolean = { id == NewIdValue }
  def isNotNew: Boolean = { id != NewIdValue }

}

object RoadwayPoint extends SQLSyntaxSupport[RoadwayPoint] {
  override val tableName = "roadway_point"

  def apply(rs: WrappedResultSet): RoadwayPoint = RoadwayPoint(
    id            = rs.long("id"),
    roadwayNumber = rs.long("roadway_number"),
    addrMValue    = rs.long("addr_m"),
    createdBy     = rs.string("created_by"),
    createdTime   = rs.jodaDateTimeOpt("created_time"),
    modifiedBy    = rs.stringOpt("modified_by"),
    modifiedTime  = rs.jodaDateTimeOpt("modified_time")
  )
}

class RoadwayPointDAO extends BaseDAO {

  private def queryList(query: SQL[Nothing, NoExtractor]): Seq[RoadwayPoint] = {
    runSelectQuery(query.map(RoadwayPoint.apply))
  }

  def create(roadwayPoint: RoadwayPoint): Long = {
    val id = if (roadwayPoint.isNew) {
      Sequences.nextRoadwayPointId
    } else {
      roadwayPoint.id
    }

    logger.info(s"Insert roadway_point $id (roadwayNumber: ${roadwayPoint.roadwayNumber}, addrM: ${roadwayPoint.addrMValue})")
    runUpdateToDb(sql"""
      INSERT INTO roadway_point (id, roadway_number, addr_m, created_by, modified_by)
      values ($id, ${roadwayPoint.roadwayNumber}, ${roadwayPoint.addrMValue}, ${roadwayPoint.createdBy}, ${roadwayPoint.createdBy})
      """)
    id
  }

  def create(roadwayNumber: Long, addrMValue: Long, createdBy: String): Long = {
    val id = Sequences.nextRoadwayPointId
    logger.info(s"Insert roadway_point $id (roadwayNumber: $roadwayNumber, addrM: $addrMValue)")
    runUpdateToDb(sql"""
      INSERT INTO roadway_point (id, roadway_number, addr_m, created_by, modified_by)
      VALUES ($id, $roadwayNumber, $addrMValue, $createdBy, $createdBy)
    """)
    id
  }

  def update(roadwayPoint: RoadwayPoint): Long = {
    update(Seq(roadwayPoint)).head
  }

  def update(roadwayPoints: Seq[RoadwayPoint]): Seq[Long] = {
    val query =
      sql"""
          UPDATE roadway_point
          SET roadway_number = ?, addr_m = ?, modified_by = ?, modified_time = current_timestamp
          WHERE id = ?
         """

    val batchParams = roadwayPoints.map { rwPoint =>
      Seq(
        rwPoint.roadwayNumber,
        rwPoint.addrMValue,
        rwPoint.modifiedBy.getOrElse("-"),
        rwPoint.id)
    }

    runBatchUpdateToDb(query, batchParams)

    roadwayPoints.map(_.id) // return the ids of the updated roadway points
  }

  def fetch(id: Long): RoadwayPoint = {
    val query = sql"""
         SELECT id, roadway_number, addr_m, created_by, created_time, modified_by, modified_time
         FROM roadway_point
         WHERE id = $id
        """
    runSelectSingleOption(query.map(RoadwayPoint.apply)).getOrElse(
      throw new NoSuchElementException(s"RoadwayPoint with id $id not found")
    )
  }

  def fetch(roadwayNumber: Long, addrM: Long): Option[RoadwayPoint] = {
    logger.debug(s"Fetching RoadwayPoint with roadwayNumber=$roadwayNumber, addrM=$addrM")
    val query =
      sql"""
       SELECT id, roadway_number, addr_m, created_by, created_time, modified_by, modified_time
       FROM roadway_point
       WHERE roadway_number = $roadwayNumber
       AND addr_m = $addrM
      """
    logger.debug(s"Generated SQL: ${query.statement}")
    try {
      runSelectSingleOption(query.map(RoadwayPoint.apply))
    } catch {
      case e: Exception =>
        logger.error(s"Error in fetch RoadwayPoint: $e")
        throw e
    }
  }

  def fetch(points: Seq[(Long, Long)]): Seq[RoadwayPoint] = {
    if (points.isEmpty) {
      Seq()
    } else {
      val whereConditions = points.map { case (roadwayNumber, addrM) =>
        sqls"(roadway_number = $roadwayNumber AND addr_m = $addrM)"
      }

      // joinWithOr is a helper function that joins the SQLSyntax objects with OR
      val whereClause = sqls"WHERE ${SQLSyntax.joinWithOr(whereConditions: _*)}"

      val query =
        sql"""
       SELECT id, roadway_number, addr_m, created_by, created_time, modified_by, modified_time
       FROM roadway_point $whereClause
      """
      queryList(query)
    }
  }

  def fetchByRoadwayNumber(roadwayNumber: Long): Seq[RoadwayPoint] = {
    fetchByRoadwayNumbers(Seq(roadwayNumber))
  }

  def fetchByRoadwayNumberAndAddresses(roadwayNumber: Long, addrMRange: AddrMRange): Seq[RoadwayPoint] = {
    val query =
      sql"""
         SELECT id, roadway_number, addr_m, created_by, created_time, modified_by, modified_time
         FROM roadway_point
         WHERE roadway_number= $roadwayNumber
         AND addr_m >= ${addrMRange.start}
         AND addr_m <= ${addrMRange.end}
          """
    queryList(query)
  }

  def fetchByRoadwayNumbers(roadwayNumber: Iterable[Long]): Seq[RoadwayPoint] = {
    if (roadwayNumber.isEmpty) {
      Seq()
    } else {
      val query =
        sql"""
          SELECT id, roadway_number, addr_m, created_by, created_time, modified_by, modified_time
          FROM roadway_point
          WHERE roadway_number IN ($roadwayNumber)
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
      Roadway(-1000, p.roadwayNumber, p.roadPart, p.administrativeClass, p.track, p.discontinuity, p.addrMRange, p.reversed, startDate, p.endDate,
        p.createdBy.getOrElse("-"), p.roadName, p.ely, TerminationCode.NoTermination, DateTime.now(), None))
  }

}

package fi.liikennevirasto.viite.dao

import fi.liikennevirasto.viite.NewIdValue
import fi.liikennevirasto.digiroad2.util.LogUtils.time
import fi.vaylavirasto.viite.dao.{BaseDAO, Sequences}
import fi.vaylavirasto.viite.geometry.{BoundingRectangle, Point}
import fi.vaylavirasto.viite.model.{BeforeAfter, Discontinuity, RoadPart, Track}
import fi.vaylavirasto.viite.postgis.GeometryDbUtils
import org.joda.time.DateTime
import scalikejdbc._
import scalikejdbc.jodatime.JodaWrappedResultSet.fromWrappedResultSetToJodaWrappedResultSet


case class JunctionPoint(id: Long, beforeAfter: BeforeAfter, roadwayPointId: Long, junctionId: Long, startDate: Option[DateTime], endDate: Option[DateTime],
                         validFrom: DateTime, validTo: Option[DateTime], createdBy: String, createdTime: Option[DateTime], roadwayNumber: Long, addrM: Long,
                         roadPart: RoadPart, track: Track, discontinuity: Discontinuity, coordinates: Point = Point(0.0, 0.0))

object JunctionPoint extends SQLSyntaxSupport[JunctionPoint] {
  override val tableName = "junction_point"

  def apply(rs: WrappedResultSet): JunctionPoint = JunctionPoint(
    id              = rs.long("id"),
    beforeAfter     = BeforeAfter(rs.int("before_after")),
    roadwayPointId  = rs.long("roadway_point_id"),
    junctionId      = rs.long("junction_id"),
    startDate       = rs.jodaDateTimeOpt("start_date"),
    endDate         = rs.jodaDateTimeOpt("end_date"),
    validFrom       = rs.jodaDateTime("valid_from"),
    validTo         = rs.jodaDateTimeOpt("valid_to"),
    createdBy       = rs.string("created_by"),
    createdTime     = rs.jodaDateTimeOpt("created_time"),
    roadwayNumber   = rs.long("roadway_number"),
    addrM           = rs.long("addr_m"),
    roadPart        = RoadPart(
      roadNumber    = rs.longOpt("road_number").getOrElse(0L),
      partNumber    = rs.longOpt("road_part_number").getOrElse(0L)
    ),
    track = Track(rs.int("track")),
    discontinuity = Discontinuity(rs.int("discontinuity"))
  )
}

class JunctionPointDAO extends BaseDAO {

  private def queryList(query: SQL[Nothing, NoExtractor]): List[JunctionPoint] = {
    runSelectQuery(query.map(JunctionPoint.apply))
      .groupBy(_.id)
      .map {case  (_, junctionPoints) => junctionPoints.head} // Remove duplicates
      .toList
  }

  /** Get joined roadway-roadwayPoint-junctionPoint, junction-junctionPoint information for
   * still viable (end_date, and valid-to are null) junctionPoints in db */
  lazy val junctionPointQuery =
    sqls"""
      SELECT JP.ID, jp.before_after, jp.roadway_point_id, jp.junction_id, j.start_date, j.end_date, jp.valid_from,
        jp.valid_to, jp.created_by, jp.created_time, rp.roadway_number, rp.addr_m, rw.road_number, rw.road_part_number,
        rw.track, rw.discontinuity
      FROM junction_point jp
      JOIN junction j ON (j.id = jp.junction_id AND j.valid_to IS NULL AND j.end_date IS NULL)
      JOIN roadway_point rp ON (rp.id = jp.roadway_point_id)
      JOIN roadway rw ON (rw.roadway_number = rp.roadway_number)
    """

  /** Get joined roadway-roadwayPoint-junctionPoint, junction-junctionPoint information for
   * all junctionPoints in db */
  lazy val junctionPointHistoryQuery =
    sqls"""
      SELECT JP.ID, jp.before_after, jp.roadway_point_id, jp.junction_id, j.start_date, j.end_date, jp.valid_from,
        jp.valid_to, jp.created_by, jp.created_time, rp.roadway_number, rp.addr_m, rw.road_number, rw.road_part_number,
        rw.track, rw.discontinuity
      FROM junction_point jp
      JOIN junction j ON (j.id = jp.junction_id)
      JOIN roadway_point rp ON (rp.id = jp.roadway_point_id)
      JOIN roadway rw ON (rw.roadway_number = rp.roadway_number)
    """

  def fetchByIds(ids: Seq[Long]): Seq[JunctionPoint] = {
    if (ids.isEmpty) {
      Seq()
    } else {
      val query =
        sql"""
          $junctionPointQuery
          WHERE jp.id IN ($ids) AND jp.valid_to IS NULL
        """
      queryList(query)
    }
  }

  def fetchAllByJunctionIds(junctionIds: Seq[Long]): Seq[JunctionPoint] = {
    if (junctionIds.isEmpty) {
      Seq()
    } else {
      val query =
        sql"""
          $junctionPointHistoryQuery
          WHERE j.id IN ($junctionIds)
        """
      queryList(query)
    }
  }

  def fetchByJunctionIds(junctionIds: Seq[Long]): Seq[JunctionPoint] = {
    if (junctionIds.isEmpty) {
      Seq()
    } else {
      val query =
        sql"""
          $junctionPointQuery
          WHERE j.id IN ($junctionIds) AND jp.valid_to IS NULL
          AND rw.valid_to IS NULL AND rw.end_date IS NULL
        """
      queryList(query)
    }
  }

  def fetchByRoadwayPoint(roadwayNumber: Long, addrM: Long, beforeAfter: BeforeAfter): Option[JunctionPoint] = {
    val query =
      sql"""
        $junctionPointQuery
        WHERE jp.valid_to IS NULL
        AND rp.roadway_number = $roadwayNumber AND rp.addr_m = $addrM AND jp.before_after = ${beforeAfter.value}
      """
    queryList(query).headOption
  }

  def fetchByMultipleRoadwayPoints(roadwayNumber: Long, startAddrMValue: Long, endAddrMValue: Long): Option[JunctionPoint] = {
    val query =
      sql"""
        $junctionPointQuery
        WHERE jp.valid_to IS NULL
        AND rp.roadway_number = $roadwayNumber AND rp.addr_m IN ($startAddrMValue, $endAddrMValue)
      """
    queryList(query).headOption
  }

  def fetchByRoadwayPointId(roadwayPointId: Long): Seq[JunctionPoint] = {
    fetchByRoadwayPointIds(Seq(roadwayPointId))
  }

  /** Get viable (end_date, and valid_to are null) junctionPoints having given roadway points. */
  def fetchByRoadwayPointIds(roadwayPointIds: Seq[Long]): Seq[JunctionPoint] = {
    if (roadwayPointIds.isEmpty) {
      Seq()
    } else {
      val query =
        sql"""
          $junctionPointQuery
          WHERE jp.valid_to IS NULL
          AND jp.roadway_point_id IN ($roadwayPointIds)
        """
      queryList(query)
    }
  }

  def fetchByBoundingBox(boundingRectangle: BoundingRectangle): Seq[JunctionPoint] = {
    time(logger, "Fetch JunctionPoints by bounding box") {
      val extendedBoundingRectangle = BoundingRectangle(boundingRectangle.leftBottom + boundingRectangle.diagonal.scale(.15),
        boundingRectangle.rightTop - boundingRectangle.diagonal.scale(.15))

      val boundingBoxFilter = GeometryDbUtils.boundingBoxFilter(extendedBoundingRectangle, sqls"LL.geometry")

      val query =
        sql"""
          $junctionPointQuery
          INNER JOIN linear_location ll ON (ll.roadway_number = rp.roadway_number AND ll.valid_to IS NULL)
          WHERE rw.valid_to IS NULL AND rw.end_date IS NULL
          AND jp.valid_to IS NULL AND $boundingBoxFilter
        """
      queryList(query)
    }
  }


  def create(junctionPoints: Iterable[JunctionPoint]): Seq[Long] = {
    // Set ids for the junction points without one
    val (ready, idLess) = junctionPoints.partition(_.id != NewIdValue)
    val newIds = Sequences.fetchJunctionPointIds(idLess.size)
    val createJunctionPoints = ready ++ idLess.zip(newIds).map(x =>
      x._1.copy(id = x._2)
    )

    val batchParams: Iterable[Seq[Any]] = createJunctionPoints.map {
      junctionPoint =>
        Seq(
          junctionPoint.id,
          junctionPoint.beforeAfter.value,
          junctionPoint.roadwayPointId,
          junctionPoint.junctionId,
          junctionPoint.createdBy,
          junctionPoint.validTo.orNull
        )
    }

    val query =
      sql"""
        INSERT INTO junction_point (ID, before_after, roadway_point_id, junction_id, created_by, valid_to)
        VALUES (?, ?, ?, ?, ?, ?)
      """

    runBatchUpdateToDb(query, batchParams.toSeq)
    createJunctionPoints.map(_.id).toSeq
  }

  def update(junctionPoints: Iterable[JunctionPoint], updatedBy: String = "-"): Seq[Long] = {

    val query =
      sql"""
        UPDATE junction_point
        SET before_after = ?, roadway_point_id = ?, junction_id = ?
        WHERE id = ?
        """

    val batchParams: Iterable[Seq[Any]] = junctionPoints.map {
      junctionPoint =>
        Seq(
          junctionPoint.beforeAfter.value,
          junctionPoint.roadwayPointId,
          junctionPoint.junctionId,
          junctionPoint.id
        )
    }
    runBatchUpdateToDb(query, batchParams.toSeq)
    junctionPoints.map(_.id).toSeq // Return the ids of the updated junction points
  }

  /**
   * Expires junction points (set their valid_to to the current system date).
   *
   * @param ids : Iterable[Long] - The ids of the junction points to expire.
   * @return
   */
  def expireById(ids: Iterable[Long]): Int = {
    val query =
      sql"""
        UPDATE junction_point
        SET valid_to = current_timestamp
        WHERE valid_to IS NULL and id IN ($ids)
      """
    if (ids.isEmpty)
      0
    else
      runUpdateToDb(query)
  }
}

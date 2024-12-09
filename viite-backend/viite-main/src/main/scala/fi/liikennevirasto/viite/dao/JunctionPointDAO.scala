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
  override val tableName = "JUNCTION_POINT"

  def apply(rs: WrappedResultSet): JunctionPoint = JunctionPoint(
    id = rs.long("id"),
    beforeAfter = BeforeAfter(rs.int("before_after")),
    roadwayPointId = rs.long("roadway_point_id"),
    junctionId = rs.long("junction_id"),
    startDate = rs.jodaDateTimeOpt("start_date"),
    endDate = rs.jodaDateTimeOpt("end_date"),
    validFrom = rs.jodaDateTime("valid_from"),
    validTo = rs.jodaDateTimeOpt("valid_to"),
    createdBy = rs.string("created_by"),
    createdTime = rs.jodaDateTimeOpt("created_time"),
    roadwayNumber = rs.long("roadway_number"),
    addrM = rs.long("addr_m"),
    roadPart = RoadPart(
      rs.longOpt("road_number").getOrElse(0L),
      rs.longOpt("road_part_number").getOrElse(0L)
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
      SELECT JP.ID, JP.BEFORE_AFTER, JP.ROADWAY_POINT_ID, JP.JUNCTION_ID, J.START_DATE, J.END_DATE, JP.VALID_FROM,
        JP.VALID_TO, JP.CREATED_BY, JP.CREATED_TIME, RP.ROADWAY_NUMBER, RP.ADDR_M, RW.ROAD_NUMBER, RW.ROAD_PART_NUMBER,
        RW.TRACK, RW.DISCONTINUITY
      FROM JUNCTION_POINT JP
      JOIN JUNCTION J ON (J.ID = JP.JUNCTION_ID AND J.VALID_TO IS NULL AND J.END_DATE IS NULL)
      JOIN ROADWAY_POINT RP ON (RP.ID = JP.ROADWAY_POINT_ID)
      JOIN ROADWAY RW ON (RW.ROADWAY_NUMBER = RP.ROADWAY_NUMBER)
    """

  /** Get joined roadway-roadwayPoint-junctionPoint, junction-junctionPoint information for
   * all junctionPoints in db */
  lazy val junctionPointHistoryQuery =
    sqls"""
      SELECT JP.ID, JP.BEFORE_AFTER, JP.ROADWAY_POINT_ID, JP.JUNCTION_ID, J.START_DATE, J.END_DATE, JP.VALID_FROM,
        JP.VALID_TO, JP.CREATED_BY, JP.CREATED_TIME, RP.ROADWAY_NUMBER, RP.ADDR_M, RW.ROAD_NUMBER, RW.ROAD_PART_NUMBER,
        RW.TRACK, RW.DISCONTINUITY
      FROM JUNCTION_POINT JP
      JOIN JUNCTION J ON (J.ID = JP.JUNCTION_ID)
      JOIN ROADWAY_POINT RP ON (RP.ID = JP.ROADWAY_POINT_ID)
      JOIN ROADWAY RW ON (RW.ROADWAY_NUMBER = RP.ROADWAY_NUMBER)
    """

  def fetchByIds(ids: Seq[Long]): Seq[JunctionPoint] = {
    if (ids.isEmpty) {
      Seq()
    } else {
      val query =
        sql"""
          $junctionPointQuery
          WHERE JP.ID IN ($ids) AND JP.VALID_TO IS NULL
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
          WHERE J.ID IN ($junctionIds)
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
          WHERE J.ID IN ($junctionIds) AND JP.VALID_TO IS NULL
          AND RW.VALID_TO IS NULL AND RW.END_DATE IS NULL
        """
      queryList(query)
    }
  }

  def fetchByRoadwayPoint(roadwayNumber: Long, addrM: Long, beforeAfter: BeforeAfter): Option[JunctionPoint] = {
    val query =
      sql"""
        $junctionPointQuery
        WHERE JP.VALID_TO IS NULL
        AND RP.ROADWAY_NUMBER = $roadwayNumber AND RP.ADDR_M = $addrM and JP.BEFORE_AFTER = ${beforeAfter.value}
      """
    queryList(query).headOption
  }

  def fetchByMultipleRoadwayPoints(roadwayNumber: Long, startAddrMValue: Long, endAddrMValue: Long): Option[JunctionPoint] = {
    val query =
      sql"""
        $junctionPointQuery
        WHERE JP.VALID_TO IS NULL
        AND RP.ROADWAY_NUMBER = $roadwayNumber AND RP.ADDR_M in ($startAddrMValue, $endAddrMValue)
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
          WHERE JP.VALID_TO IS NULL
          AND JP.ROADWAY_POINT_ID IN ($roadwayPointIds)
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
          INNER JOIN LINEAR_LOCATION LL ON (LL.ROADWAY_NUMBER = RP.ROADWAY_NUMBER AND LL.VALID_TO IS NULL)
          WHERE RW.VALID_TO IS NULL AND RW.END_DATE IS NULL
          AND JP.VALID_TO IS NULL AND $boundingBoxFilter
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
      sql"""insert into JUNCTION_POINT (ID, BEFORE_AFTER, ROADWAY_POINT_ID, JUNCTION_ID, CREATED_BY, VALID_TO)
      values (?, ?, ?, ?, ?, ?)"""


    runBatchUpdateToDb(query, batchParams.toSeq)
    createJunctionPoints.map(_.id).toSeq
  }

  def update(junctionPoints: Iterable[JunctionPoint], updatedBy: String = "-"): Seq[Long] = {

    val query =
      sql"""
        UPDATE JUNCTION_POINT
        SET BEFORE_AFTER = ?, ROADWAY_POINT_ID = ?, JUNCTION_ID = ?
        WHERE ID = ?
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
        Update JUNCTION_POINT
        Set valid_to = current_timestamp
        where valid_to IS NULL and id in ($ids)
      """
    if (ids.isEmpty)
      0
    else
      runUpdateToDb(query)
  }
}

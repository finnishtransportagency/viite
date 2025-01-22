package fi.liikennevirasto.viite.dao

import org.joda.time.DateTime
import fi.liikennevirasto.digiroad2.util.LogUtils.time
import fi.vaylavirasto.viite.model.{BeforeAfter, RoadPart}
import fi.vaylavirasto.viite.dao.BaseDAO
import scalikejdbc._
import scalikejdbc.jodatime.JodaWrappedResultSet.fromWrappedResultSetToJodaWrappedResultSet


/** Data type for /summary API data */
case class RoadwayNetworkSummaryRow
(
  roadPart: RoadPart, roadName: String,
  elyCode: Int, administrativeClass: Int,
  track: Int, startAddressM: Int, endAddressM: Int, continuity: Int
)

case class MissingCalibrationPoint(roadPart: RoadPart, track: Long, addrM: Long, createdTime: DateTime, createdBy: String)
case class MissingCalibrationPointFromJunction(missingCalibrationPoint: MissingCalibrationPoint, junctionPointId: Long, junctionNumber: Long, nodeNumber: Long, beforeAfter: BeforeAfter)
case class MissingRoadwayPoint(roadPart: RoadPart, track: Long, addrM: Long, createdTime: DateTime, createdBy: String)
case class InvalidRoadwayLength(roadwayNumber: Long, startDate: DateTime, endDate: Option[DateTime], roadPart: RoadPart, track: Long, startAddrM: Long, endAddrM: Long, length: Long, createdBy: String, createdTime: DateTime)
case class LinksWithExtraCalibrationPoints(linkId: String, roadPart: RoadPart, startEnd: Int, calibrationPointCount: Int, calibrationPointIds: Array[Long])

//TODO better naming case class
case class OverlappingRoadwayOnLinearLocation(roadway: Roadway, linearLocationId: Long, linkId: String, linearLocationRoadwayNumber: Long, linearLocationStartMeasure: Long, linearLocationEndMeasure: Long, linearLocationCreatedBy: String, linearLocationCreatedTime: DateTime)

class RoadNetworkDAO extends BaseDAO {

  object MissingCalibrationPoint extends SQLSyntaxSupport[MissingCalibrationPoint] {
    def apply(rs: WrappedResultSet): MissingCalibrationPoint = new MissingCalibrationPoint(
      roadPart     = RoadPart(
        roadNumber = rs.long("road_number"),
        partNumber = rs.long("road_part_number")
      ),
      track        = rs.long("track"),
      addrM        = rs.long("addr_m"),
      createdTime  = rs.jodaDateTime("created_time"),
      createdBy    = rs.string("created_by")
    )
  }

  object MissingCalibrationPointFromJunction extends SQLSyntaxSupport[MissingCalibrationPointFromJunction] {
    def apply(rs: WrappedResultSet): MissingCalibrationPointFromJunction = new MissingCalibrationPointFromJunction(
      missingCalibrationPoint = MissingCalibrationPoint(rs),
      junctionPointId         = rs.long("junction_point_id"),
      junctionNumber          = rs.long("junction_number"),
      nodeNumber              = rs.long("node_number"),
      beforeAfter             = BeforeAfter(rs.long("before_after"))
    )
  }

  object linksWithExtraCalibrationPoints extends SQLSyntaxSupport[LinksWithExtraCalibrationPoints] {
    def apply(rs: WrappedResultSet): LinksWithExtraCalibrationPoints = LinksWithExtraCalibrationPoints(
      linkId       = rs.string("link_id"),
      roadPart     = RoadPart(
        roadNumber = rs.long("road_number"),
        partNumber = rs.long("road_part_number")
      ),
      startEnd              = rs.int("start_end"),
      calibrationPointCount = rs.int("calibration_point_count"),
      calibrationPointIds   = rs.array("calibration_point_ids") match {
        case null => Array.empty[Long]
        case arr => arr.getArray.asInstanceOf[Array[java.lang.Long]].map(_.longValue)
      }
    )
  }

  object MissingRoadwayPoint extends SQLSyntaxSupport[MissingRoadwayPoint] {
    def apply(rs: WrappedResultSet): MissingRoadwayPoint = new MissingRoadwayPoint(
      roadPart     = RoadPart(
        roadNumber = rs.long("road_number"),
        partNumber = rs.long("road_part_number")
      ),
      track        = rs.long("track"),
      addrM        = rs.long("addr_m"),
      createdTime  = rs.jodaDateTime("created_time"),
      createdBy    = rs.string("created_by")
    )
  }

  object InvalidRoadwayLength extends SQLSyntaxSupport[InvalidRoadwayLength] {
    def apply(rs: WrappedResultSet): InvalidRoadwayLength = new InvalidRoadwayLength(
      roadwayNumber = rs.long("roadway_number"),
      startDate     = rs.jodaDateTime("start_date"),
      endDate       = rs.jodaDateTimeOpt("end_date"),
      roadPart      = RoadPart(
        roadNumber  = rs.long("road_number"),
        partNumber  = rs.long("road_part_number")
      ),
      track         = rs.long("track"),
      startAddrM    = rs.long("start_addr_m"),
      endAddrM      = rs.long("end_addr_m"),
      length        = rs.long("length"),
      createdBy     = rs.string("created_by"),
      createdTime   = rs.jodaDateTime("created_time")
    )
  }

  object OverlappingRoadwayOnLinearLocation extends SQLSyntaxSupport[OverlappingRoadwayOnLinearLocation] {
    def apply(rs: WrappedResultSet): OverlappingRoadwayOnLinearLocation = {
      new OverlappingRoadwayOnLinearLocation(
        roadway                     = Roadway(rs),
        linearLocationId            = rs.long("linearLocationId"),
        linkId                      = rs.string("link_id"),
        linearLocationRoadwayNumber = rs.long("roadway_number"),
        linearLocationStartMeasure  = rs.long("start_measure"),
        linearLocationEndMeasure    = rs.long("end_measure"),
        linearLocationCreatedBy     = rs.string("created_by"),
        linearLocationCreatedTime   = rs.jodaDateTime("created_time")
      )
    }
  }

  object RoadwayNetworkSummaryRow extends SQLSyntaxSupport [RoadwayNetworkSummaryRow]{
    def apply(rs: WrappedResultSet): RoadwayNetworkSummaryRow = new RoadwayNetworkSummaryRow(
      roadPart            = RoadPart(
        roadNumber        = rs.long("road_number"),
        partNumber        = rs.long("road_part_number")
      ),
      roadName            = rs.string("road_name"),
      elyCode             = rs.int("ely"),
      administrativeClass = rs.int("administrative_class"),
      track               = rs.int("track"),
      startAddressM       = rs.int("start_addr_m"),
      endAddressM         = rs.int("end_addr_m"),
      continuity          = rs.int("discontinuity")
    )
  }

  private lazy val selectMissingCalibrationPointFromStart =
    sqls"""SELECT r.road_number,r.road_part_number,r.track,rp.addr_m ,r.created_time,r.created_by"""

  def fetchMissingCalibrationPointsFromStart(): Seq[MissingCalibrationPoint] = {
    val query =
      sql"""
         $selectMissingCalibrationPointFromStart
         FROM roadway_point rp,roadway r
         WHERE (
          NOT EXISTS (
            SELECT 4
            FROM calibration_point cp2
            WHERE cp2.valid_to IS NULL
            AND cp2.roadway_point_id = rp.id
           )
          )
         AND r.roadway_number = rp.roadway_number
         AND r.valid_to IS NULL
         AND r.end_date IS NULL
         AND r.road_number<70000
         AND rp.addr_m = 0
         ORDER BY r.road_number,r.road_part_number,r.track,rp.addr_m
        """
    runSelectQuery(query.map(MissingCalibrationPoint.apply))
  }

  def fetchMissingCalibrationPointsFromStart(roadPart: RoadPart): Seq[MissingCalibrationPoint] = {
    val query =
      sql"""
         WITH selectedRoadways
              AS (SELECT *
                  FROM roadway
                  WHERE valid_to IS NULL
                  AND road_number = ${roadPart.roadNumber}
                  AND road_part_number = ${roadPart.partNumber}
                  )
         $selectMissingCalibrationPointFromStart
         FROM roadway_point rp, selectedRoadways r
         WHERE (
          NOT EXISTS (
            SELECT 4
            FROM calibration_point cp2
            WHERE cp2.valid_to IS NULL
            AND cp2.roadway_point_id = rp.id
           )
          )
         AND r.roadway_number = rp.roadway_number
         AND r.valid_to IS NULL
         AND r.end_date IS NULL
         AND r.road_number<70000
         AND rp.addr_m = 0
         ORDER BY r.road_number,r.road_part_number,r.track,rp.addr_m;
        """
    runSelectQuery(query.map(MissingCalibrationPoint.apply))
  }

  private lazy val selectMissingCalibrationPointFromEnd =
    sqls"""SELECT r.road_number,r.road_part_number,r.track,rp.addr_m ,r.created_time,r.created_by"""

  def fetchMissingCalibrationPointsFromEnd(): Seq[MissingCalibrationPoint] = {
    val query =
      sql"""
        $selectMissingCalibrationPointFromEnd
        FROM roadway_point rp, roadway r
        WHERE (
          NOT EXISTS (
            SELECT 4
            FROM calibration_point cp2
            WHERE cp2.valid_to IS NULL
            AND cp2.roadway_point_id = rp.id
          )
        )
        AND r.roadway_number = rp.roadway_number
        AND r.valid_to IS NULL
        AND r.end_date IS NULL
        AND r.road_number < 70000
        AND rp.addr_m = (
          SELECT MAX(r2.end_addr_m)
          FROM roadway r2
          WHERE r2.valid_to IS NULL
          AND r2.end_date IS NULL
          AND r.road_number = r2.road_number
          AND r.road_part_number = r2.road_part_number
        )
        ORDER BY r.road_number, r.road_part_number, r.track, rp.addr_m
        """
    runSelectQuery(query.map(MissingCalibrationPoint.apply))
  }

  def fetchMissingCalibrationPointsFromEnd(roadPart: RoadPart): Seq[MissingCalibrationPoint] = {
    val query =
      sql"""
        WITH selectedRoadways AS (
          SELECT *
          FROM roadway
          WHERE valid_to IS NULL
          AND road_number = ${roadPart.roadNumber}
          AND road_part_number = ${roadPart.partNumber}
        )
        $selectMissingCalibrationPointFromEnd
        FROM roadway_point rp, selectedRoadways r
        WHERE (
          NOT EXISTS (
            SELECT 4
            FROM calibration_point cp2
            WHERE cp2.valid_to IS NULL
            AND cp2.roadway_point_id = rp.id
          )
        )
        AND r.roadway_number = rp.roadway_number
        AND r.valid_to IS NULL
        AND r.end_date IS NULL
        AND r.road_number < 70000
        AND rp.addr_m = (
          SELECT MAX(r2.end_addr_m)
          FROM roadway r2
          WHERE r2.valid_to IS NULL
          AND r2.end_date IS NULL
          AND r.road_number = r2.road_number
          AND r.road_part_number = r2.road_part_number
        )
        ORDER BY r.road_number, r.road_part_number, r.track, rp.addr_m
        """
    runSelectQuery(query.map(MissingCalibrationPoint.apply))
  }

  private lazy val selectMissingCalibrationPointFromJunction =
    sqls"""
          SELECT jp.id AS junction_point_id,j.junction_number,j.node_number,r.road_number,
            r.road_part_number,r.track,rp.addr_m,jp.before_after,r.created_time,r.created_by
        """

  def fetchMissingCalibrationPointsFromJunctions(): Seq[MissingCalibrationPointFromJunction] = {
    val query =
      sql"""
         $selectMissingCalibrationPointFromJunction
         FROM   junction_point jp,
                roadway_point rp,
                roadway r,
                junction j
         WHERE  jp.valid_to IS NULL
                AND ( NOT EXISTS (SELECT 4
                                  FROM   calibration_point cp2
                                  WHERE  cp2.valid_to IS NULL
                                         AND cp2.roadway_point_id = jp.roadway_point_id)
                    )
                AND rp.id = jp.roadway_point_id
                AND r.roadway_number = rp.roadway_number
                AND r.valid_to IS NULL
                AND r.end_date IS NULL
                AND r.road_number < 70000
                AND j.id = jp.junction_id
                AND j.end_date IS NULL
                AND j.valid_to IS NULL
         ORDER  BY j.node_number,
                   j.junction_number,
                   r.road_number,
                   r.road_part_number,
                   r.track,
                   rp.addr_m
         """
    runSelectQuery(query.map(MissingCalibrationPointFromJunction.apply))
  }

  def fetchMissingCalibrationPointsFromJunctions(roadPart: RoadPart): Seq[MissingCalibrationPointFromJunction] = {
    val query =
      sql"""
         WITH selectedRoadways
                   AS (SELECT *
                   FROM roadway
                   WHERE valid_to IS NULL
                   AND road_number = ${roadPart.roadNumber}
                   AND road_part_number = ${roadPart.partNumber}
                 )
         $selectMissingCalibrationPointFromJunction
         FROM   junction_point jp,
                roadway_point rp,
                selectedRoadways r,
                junction j
         WHERE  jp.valid_to IS NULL
                AND ( NOT EXISTS (SELECT 4
                                  FROM   calibration_point cp2
                                  WHERE  cp2.valid_to IS NULL
                                         AND cp2.roadway_point_id = jp.roadway_point_id)
                    )
                AND rp.id = jp.roadway_point_id
                AND r.roadway_number = rp.roadway_number
                AND r.valid_to IS NULL
                AND r.end_date IS NULL
                AND r.road_number < 70000
                AND j.id = jp.junction_id
                AND j.end_date IS NULL
                AND j.valid_to IS NULL
         ORDER  BY j.node_number,
                   j.junction_number,
                   r.road_number,
                   r.road_part_number,
                   r.track,
                   rp.addr_m;
         """
    runSelectQuery(query.map(MissingCalibrationPointFromJunction.apply))
  }

  private lazy val selectMissingRoadwayPointFromStart =
    sqls"""SELECT r.road_number, r.road_part_number, r.track, r.start_addr_m AS addr_m, r.created_time, r.created_by"""

  def fetchMissingRoadwayPointsFromStart(): Seq[MissingRoadwayPoint] = {
    val query =
      sql"""
         $selectMissingRoadwayPointFromStart
         FROM   roadway r
         WHERE  ( NOT EXISTS (SELECT 4
                              FROM   roadway_point rp
                              WHERE  r.roadway_number = rp.roadway_number
                                     AND r.start_addr_m = rp.addr_m) )
                AND r.valid_to IS NULL
                AND r.end_date IS NULL
                AND r.road_number < 70000
                AND r.start_addr_m = 0
         ORDER  BY r.road_number,
                   r.road_part_number,
                   r.track,
                   r.end_addr_m
                   """
    runSelectQuery(query.map(MissingRoadwayPoint.apply))
  }

  def fetchMissingRoadwayPointsFromStart(roadPart: RoadPart): Seq[MissingRoadwayPoint] = {
    val query =
      sql"""
         WITH selectedRoadways
                   AS (SELECT *
                   FROM roadway
                   WHERE valid_to IS NULL
                   AND road_number = ${roadPart.roadNumber}
                   AND road_part_number = ${roadPart.partNumber}
                 )
         $selectMissingRoadwayPointFromStart
         FROM   selectedRoadways r
         WHERE  ( NOT EXISTS (SELECT 4
                              FROM   roadway_point rp
                              WHERE  r.roadway_number = rp.roadway_number
                                     AND r.start_addr_m = rp.addr_m) )
                AND r.valid_to IS NULL
                AND r.end_date IS NULL
                AND r.road_number < 70000
                AND r.start_addr_m = 0
         ORDER  BY r.road_number,
                   r.road_part_number,
                   r.track,
                   r.end_addr_m
        """
    runSelectQuery(query.map(MissingRoadwayPoint.apply))
  }

  private lazy val selectMissingRoadwayPointFromEnd =
    sqls"""SELECT r.road_number, r.road_part_number, r.track, r.end_addr_m AS addr_m, r.created_time, r.created_by"""

  def fetchMissingRoadwayPointsFromEnd(): Seq[MissingRoadwayPoint] = {
    val query =
      sql"""
         $selectMissingRoadwayPointFromEnd
         FROM   roadway r
         WHERE  ( NOT EXISTS (SELECT 4
                              FROM   roadway_point rp
                              WHERE  r.roadway_number = rp.roadway_number
                                     AND r.end_addr_m = rp.addr_m) )
                AND r.valid_to IS NULL
                AND r.end_date IS NULL
                AND r.road_number < 70000
                AND r.end_addr_m = (SELECT Max(r2.end_addr_m)
                                    FROM   roadway r2
                                    WHERE  r2.valid_to IS NULL
                                           AND r2.end_date IS NULL
                                           AND r.road_number = r2.road_number
                                           AND r.road_part_number = r2.road_part_number)
         ORDER  BY r.road_number,
                   r.road_part_number,
                   r.track,
                   r.end_addr_m
        """
    runSelectQuery(query.map(MissingRoadwayPoint.apply))
  }

  def fetchMissingRoadwayPointsFromEnd(roadPart: RoadPart): Seq[MissingRoadwayPoint] = {
    val query =
      sql"""
         WITH selectedRoadways
                   AS (SELECT *
                   FROM roadway
                   WHERE valid_to IS NULL
                   AND road_number = ${roadPart.roadNumber}
                   AND road_part_number = ${roadPart.partNumber}
                 )
         $selectMissingRoadwayPointFromEnd
         FROM   selectedRoadways r
         WHERE  ( NOT EXISTS (SELECT 4
                              FROM   roadway_point rp
                              WHERE  r.roadway_number = rp.roadway_number
                                     AND r.end_addr_m = rp.addr_m) )
                AND r.valid_to IS NULL
                AND r.end_date IS NULL
                AND r.road_number < 70000
                AND r.end_addr_m = (SELECT Max(r2.end_addr_m)
                                    FROM   roadway r2
                                    WHERE  r2.valid_to IS NULL
                                           AND r2.end_date IS NULL
                                           AND r.road_number = r2.road_number
                                           AND r.road_part_number = r2.road_part_number)
         ORDER  BY r.road_number,
                   r.road_part_number,
                   r.track,
                   r.end_addr_m
          """
    runSelectQuery(query.map(MissingRoadwayPoint.apply))
  }

  private lazy val calibrationPointJoinAndWhereConditions =
    sqls"""
      FROM calibration_point cp
        JOIN roadway_point rp ON cp.roadway_point_id = rp.id
        JOIN roadway r ON rp.roadway_number = r.roadway_number
      WHERE cp.valid_to IS NULL
        AND r.valid_to IS NULL
        AND r.end_date IS NULL
        """

  private lazy val joinGroupAndOrderCalibrationPointData =
    sqls"""
      JOIN roadway r ON rp.roadway_number = r.roadway_number
      WHERE
        cp.valid_to IS NULL
      AND r.valid_to IS NULL
      AND r.end_date IS NULL
        GROUP BY scp.link_id, scp.start_end, scp.calibration_point_count, r.road_number, r.road_part_number
        ORDER BY r.road_number, r.road_part_number
      """

  /**
   * Fetch links that have more than one start or end calibration points
   * The results are grouped by link ID, start/end, and other relevant fields to count and aggregate the calibration points
   *
   * @param roadPartFilter if defined, filter by road part
   * @return Sequence of LinksWithExtraCalibrationPoints
   */
  def fetchLinksWithExtraCalibrationPoints(roadPartFilter: Option[RoadPart] = None): Seq[LinksWithExtraCalibrationPoints] = {
    val roadPartCondition = roadPartFilter.map { rp =>
      sqls"AND r.road_number = ${rp.roadNumber} AND r.road_part_number = ${rp.partNumber}"
    }.getOrElse(sqls"")

    val query =
      sql"""
    WITH selectedCalibrationPoints AS (
      SELECT
        cp.link_id,
        cp.start_end,
        count(DISTINCT cp.id) AS calibration_point_count
      $calibrationPointJoinAndWhereConditions
        $roadPartCondition
      GROUP BY
        cp.link_id,
        cp.start_end
      HAVING
        count(DISTINCT cp.id) > 1
    )
    SELECT scp.link_id, r.road_number, r.road_part_number, scp.start_end,
              count(DISTINCT cp.id) AS calibration_point_count,
              array_agg(DISTINCT cp.id) AS calibration_point_ids
    FROM
      selectedCalibrationPoints scp
    JOIN
      calibration_point cp ON scp.link_id = cp.link_id AND scp.start_end = cp.start_end
    JOIN
      roadway_point rp ON cp.roadway_point_id = rp.id
    $joinGroupAndOrderCalibrationPointData
    """
    runSelectQuery(query.map(linksWithExtraCalibrationPoints.apply))
  }

  /**
   * Fetch links that have more than one start or end calibration points with the same roadway number
   * The results are grouped by link ID, start/end, and other relevant fields to count and aggregate the calibration points
   *
   * @return Sequence of LinksWithExtraCalibrationPoints
   */
  def fetchLinksWithExtraCalibrationPointsWithSameRoadwayNumber(): Seq[LinksWithExtraCalibrationPoints] = {

    val query =
      sql"""
    WITH selectedCalibrationPoints AS (
      SELECT
        cp.link_id,
        rp.roadway_number,
        cp.start_end,
        count(DISTINCT cp.id) AS calibration_point_count
      $calibrationPointJoinAndWhereConditions
      GROUP BY
        cp.link_id,
        rp.roadway_number,
        cp.start_end
      HAVING
        count(DISTINCT cp.id) > 1
    )
    SELECT scp.link_id, r.road_number, r.road_part_number, scp.start_end,
              count(DISTINCT cp.id) AS calibration_point_count,
              array_agg(DISTINCT cp.id) AS calibration_point_ids
    FROM
      selectedCalibrationPoints scp
    JOIN
      calibration_point cp ON scp.link_id = cp.link_id AND scp.start_end = cp.start_end
    JOIN
      roadway_point rp ON cp.roadway_point_id = rp.id AND rp.roadway_number = scp.roadway_number
    $joinGroupAndOrderCalibrationPointData;
    """
    runSelectQuery(query.map(linksWithExtraCalibrationPoints.apply))
  }

  private lazy val selectOverlappingRoadwayOnLinearLocation =
    sqls"""
       SELECT DISTINCT r.id, r.roadway_number, r.road_number, r.road_part_number, r.track,
        r.start_addr_m, r.end_addr_m, r.reversed, r.discontinuity, r.start_date, r.end_date,
        r.created_by, r.administrative_class, r.ely, r.terminated, r.valid_from, r.valid_to,
          (
          SELECT rn.road_name
          FROM road_name rn
          WHERE rn.road_number = r.road_number
          AND rn.end_date IS NULL AND rn.valid_to IS NULL
          ) AS road_name,
        l.id AS linearLocationId, l.link_id, l.roadway_number, l.start_measure, l.end_measure, l.created_by, l.created_time
        """


  //TODO better naming for this query and case class
  def fetchOverlappingRoadwaysOnLinearLocations(): Seq[OverlappingRoadwayOnLinearLocation] = {
    val query =
      sql"""
         WITH selectedRoadways
              AS (SELECT *
                  FROM   roadway
                  WHERE  valid_to IS NULL),
              selectedLinearLocations
              AS (SELECT *
                  FROM   linear_location
                  WHERE  valid_to IS NULL)
         $selectOverlappingRoadwayOnLinearLocation
         FROM   selectedRoadways r
                JOIN selectedLinearLocations l
                  ON r.roadway_number = l.roadway_number
         WHERE EXISTS (SELECT 4
                            FROM   selectedLinearLocations l2
                                   JOIN roadway r2
                                     ON r2.roadway_number = l2.roadway_number
                            WHERE  l.link_id = l2.link_id
                                   AND r2.valid_to IS NULL
                                   AND l2.valid_to IS NULL
                                   AND ( NOT r2.roadway_number = r.roadway_number )
                                   AND ( ( l.start_measure >= l2.start_measure
                                           AND l.start_measure < l2.end_measure ) --  if the beginning of linearlocation l is somewhere between the start and end of linearlocation l2
                                          OR ( l.end_measure > l2.start_measure
                                               AND l.end_measure <= l2.end_measure ) --  if the end of linearlocation l is somewhere between the start and end of linearlocation l2.
                                          OR ( l.start_measure < l2.start_measure
                                               AND l.end_measure > l2.end_measure ) ) -- if linearlocation l completely spans over linearlocation l2
                           )
         """

    runSelectQuery(query.map(OverlappingRoadwayOnLinearLocation.apply))
  }

  //TODO better naming for this query and case class
  def fetchOverlappingRoadwaysOnLinearLocations(roadPart: RoadPart): Seq[OverlappingRoadwayOnLinearLocation] = {
    val query =
      sql"""
         WITH selectedRoadways
              AS (SELECT *
                  FROM   roadway
                  WHERE  valid_to IS NULL
                  AND road_number = ${roadPart.roadNumber}
                  AND road_part_number = ${roadPart.partNumber}),
              selectedLinearLocations
              AS (SELECT *
                  FROM   linear_location
                  WHERE  valid_to IS NULL)
         $selectOverlappingRoadwayOnLinearLocation
         FROM   selectedRoadways r
                JOIN selectedLinearLocations l
                  ON r.roadway_number = l.roadway_number
         WHERE EXISTS (SELECT 4
                            FROM   selectedLinearLocations l2
                                   JOIN roadway r2
                                     ON r2.roadway_number = l2.roadway_number
                            WHERE  l.link_id = l2.link_id
                                   AND r2.valid_to IS NULL
                                   AND l2.valid_to IS NULL
                                   AND ( NOT r2.roadway_number = r.roadway_number )
                                   AND ( ( l.start_measure >= l2.start_measure
                                           AND l.start_measure < l2.end_measure ) --  if the beginning of linearlocation l is somewhere between the start and end of linearlocation l2
                                          OR ( l.end_measure > l2.start_measure
                                               AND l.end_measure <= l2.end_measure ) --  if the end of linearlocation l is somewhere between the start and end of linearlocation l2.
                                          OR ( l.start_measure < l2.start_measure
                                               AND l.end_measure > l2.end_measure ) ) -- if linearlocation l completely spans over linearlocation l2
                           )
         """
    runSelectQuery(query.map(OverlappingRoadwayOnLinearLocation.apply))
  }

  private val selectinvalidRoadwayLength =
    sqls"""
        SELECT DISTINCT r.end_addr_m - r.start_addr_m AS length,
                        r.roadway_number,
                        r.start_date,
                        r.end_date,
                        r.road_number,
                        r.road_part_number,
                        r.track,
                        r.start_addr_m,
                        r.end_addr_m,
                        r.created_by,
                        r.created_time
        """

  def fetchInvalidRoadwayLengths(): Seq[InvalidRoadwayLength] = {
    val query =
      sql"""
         $selectinvalidRoadwayLength
         FROM   roadway r
         WHERE  r.valid_to IS NULL
                AND ( EXISTS (SELECT 4
                              FROM   roadway r2
                              WHERE  r.roadway_number = r2.roadway_number
                                     AND ( NOT r2.end_addr_m - r2.start_addr_m =
                                               r.end_addr_m - r.start_addr_m )
                                     AND r2.valid_to IS NULL) )
         ORDER  BY r.roadway_number, r.road_number, r.road_part_number
         """
    runSelectQuery(query.map(InvalidRoadwayLength.apply))
  }

  def fetchInvalidRoadwayLengths(roadPart: RoadPart): Seq[InvalidRoadwayLength] = {
    val query =
      sql"""
         WITH selectedRoadways
              AS (SELECT *
                  FROM roadway
                  WHERE valid_to IS NULL
                  AND road_number = ${roadPart.roadNumber}
                  AND road_part_number = ${roadPart.partNumber}
                  )
         $selectinvalidRoadwayLength
         FROM   selectedRoadways r
         WHERE  r.valid_to IS NULL
                AND ( EXISTS (SELECT 4
                              FROM   roadway r2
                              WHERE  r.roadway_number = r2.roadway_number
                                     AND ( NOT r2.end_addr_m - r2.start_addr_m =
                                               r.end_addr_m - r.start_addr_m )
                                     AND r2.valid_to IS NULL) )
         ORDER  BY r.road_number
         """
    runSelectQuery(query.map(InvalidRoadwayLength.apply))
  }

  private val selectOverlappingRoadway =
    sqls"""
           SELECT DISTINCT r.id, r.ROADWAY_NUMBER, r.road_number, r.road_part_number, r.TRACK, r.start_addr_m, r.end_addr_m,
            r.reversed, r.discontinuity, r.start_date, r.end_date, r.created_by, r.ADMINISTRATIVE_CLASS, r.ely, r.terminated,
            r.valid_from, r.valid_to,
            (
              SELECT rn.road_name from road_name rn
              WHERE rn.road_number = r.road_number
                AND rn.end_date is null
                AND rn.valid_to is null
            ) as road_name
        """

  def fetchOverlappingRoadwaysInHistory(): Seq[Roadway] = {
    val query =
      sql"""
         $selectOverlappingRoadway
         FROM   roadway r
         WHERE  r.valid_to IS NULL
                AND ( EXISTS (SELECT 4
                              FROM   roadway r2
                              WHERE  r.road_number = r2.road_number
                                     AND ( NOT r.id = r2.id )
                                     AND r.road_part_number = r2.road_part_number
                                     AND ( r.track = 0
                                            OR r2.track = 0
                                            OR r.track = r2.track )
                                     AND ( ( r.start_addr_m >= r2.start_addr_m
                                             AND r.start_addr_m < r2.end_addr_m )
                                            OR ( r.end_addr_m > r2.start_addr_m
                                                 AND r.end_addr_m <= r2.end_addr_m )
                                            OR ( r.start_addr_m < r2.start_addr_m
                                                 AND r.end_addr_m > r2.end_addr_m ) )
                                     AND r2.valid_to IS NULL --and r2.end_date is null
                                     AND ( ( r.start_date > r2.start_date
                                             AND r.start_date < r2.end_date )
                                            OR ( r.end_date > r2.start_date
                                                 AND r.end_date < r2.end_date )
                                            OR ( r.start_date < r2.start_date
                                                 AND r.end_date > r2.end_date ) )) )
         ORDER  BY r.road_number,
                   r.road_part_number,
                   r.start_addr_m,
                   r.track
         """
    runSelectQuery(query.map(Roadway.apply))
  }

  def fetchOverlappingRoadwaysInHistory(roadPart: RoadPart): Seq[Roadway] = {
    val query =
      sql"""
         WITH selectedRoadways
              AS (SELECT *
                  FROM roadway
                  WHERE valid_to IS NULL
                  AND road_number = ${roadPart.roadNumber}
                  AND road_part_number = ${roadPart.partNumber}
                  )
         $selectOverlappingRoadway
         FROM   selectedRoadways r
         WHERE  r.valid_to IS NULL
                AND ( EXISTS (SELECT 4
                              FROM   roadway r2
                              WHERE  r.road_number = r2.road_number
                                     AND ( NOT r.id = r2.id )
                                     AND r.road_part_number = r2.road_part_number
                                     AND ( r.track = 0
                                            OR r2.track = 0
                                            OR r.track = r2.track )
                                     AND ( ( r.start_addr_m >= r2.start_addr_m
                                             AND r.start_addr_m < r2.end_addr_m )
                                            OR ( r.end_addr_m > r2.start_addr_m
                                                 AND r.end_addr_m <= r2.end_addr_m )
                                            OR ( r.start_addr_m < r2.start_addr_m
                                                 AND r.end_addr_m > r2.end_addr_m ) )
                                     AND r2.valid_to IS NULL --and r2.end_date is null
                                     AND ( ( r.start_date > r2.start_date
                                             AND r.start_date < r2.end_date )
                                            OR ( r.end_date > r2.start_date
                                                 AND r.end_date < r2.end_date )
                                            OR ( r.start_date < r2.start_date
                                                 AND r.end_date > r2.end_date ) )) )
         ORDER  BY r.road_number,
                   r.road_part_number,
                   r.start_addr_m,
                   r.track
      """
    runSelectQuery(query.map(Roadway.apply))
  }

  /**
    * Fetches the data required for /summary API result generation.
    * @return The road address information, and road names of the whole latest road network
    */
  def fetchRoadwayNetworkSummary(date: Option[DateTime] = None): Seq[RoadwayNetworkSummaryRow] = {
    time(logger, "Get whole network summary") {
      val dateFilter = date match {
        case Some(date) =>
          sqls"""
            r.start_date <= $date::date
          AND (r.end_date IS NULL OR $date::date <= r.end_date)
          """
        case None =>
          sqls"r.end_date IS NULL"
      }

      val roadNameTable = date match {
        case Some(date) =>
          sqls"""
                 (SELECT * FROM road_name rn
                 WHERE rn.start_date <= $date::date
                 AND (rn.end_date IS NULL OR $date::date <= rn.end_date)
                 AND rn.valid_to IS NULL)
              """
        case None =>
          sqls"(SELECT * FROM road_name rn WHERE rn.end_date IS NULL)"
      }

      val query = sql"""
         SELECT r.road_number, n.road_name,
                r.road_part_number, r.ely, r.administrative_class,
                r.track, r.start_addr_m, r.end_addr_m, r.discontinuity
           FROM roadway r
      LEFT JOIN $roadNameTable n ON n.road_number = r.road_number
          WHERE r.valid_to IS NULL AND $dateFilter    -- no debug rows, no history rows -> latest state
       ORDER BY r.road_number, r.road_part_number, r.track, r.start_addr_m
      """
      runSelectQuery(query.map(RoadwayNetworkSummaryRow.apply))
    }
  }

}

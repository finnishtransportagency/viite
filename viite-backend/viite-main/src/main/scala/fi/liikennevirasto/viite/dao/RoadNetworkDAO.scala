package fi.liikennevirasto.viite.dao

import org.joda.time.DateTime
import slick.driver.JdbcDriver.backend.Database
import Database.dynamicSession
import fi.liikennevirasto.digiroad2.util.LogUtils.time
import fi.vaylavirasto.viite.model.{AddrMRange, AdministrativeClass, BeforeAfter, Discontinuity, RoadPart, Track}
import slick.jdbc.{GetResult, PositionedResult, StaticQuery => Q}
import fi.vaylavirasto.viite.dao.BaseDAO
import fi.vaylavirasto.viite.util.DateTimeFormatters.dateOptTimeFormatter


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
case class LinksWithExtraCalibrationPoints(linkId: String, roadPart: RoadPart, startEnd: Int, calibrationPointCount: Int, calibrationPointIds: Array[Int])

//TODO better naming case class
case class OverlappingRoadwayOnLinearLocation(roadway: Roadway, linearLocationId: Long, linkId: String, linearLocationRoadwayNumber: Long, linearLocationStartMeasure: Long, linearLocationEndMeasure: Long, linearLocationCreatedBy: String, linearLocationCreatedTime: DateTime)

class RoadNetworkDAO extends BaseDAO {

  private implicit val missingCalibrationPoint: GetResult[MissingCalibrationPoint] = new GetResult[MissingCalibrationPoint] {
    def apply(r: PositionedResult): MissingCalibrationPoint = {

      val roadNumber = r.nextLong()
      val roadPartNumber = r.nextLong()
      val track = r.nextLong()
      val addrM = r.nextLong()
      val createdTime = dateOptTimeFormatter.parseDateTime(r.nextDate.toString)
      val createdBy = r.nextString()

      MissingCalibrationPoint(RoadPart(roadNumber, roadPartNumber), track, addrM, createdTime, createdBy)
    }
  }

  private implicit val missingCalibrationPointFromJunction: GetResult[MissingCalibrationPointFromJunction] = new GetResult[MissingCalibrationPointFromJunction] {
    def apply(r: PositionedResult): MissingCalibrationPointFromJunction = {

      val junctionPointId = r.nextLong()
      val junctionNumber = r.nextLong()
      val nodeNumber = r.nextLong()
      val roadNumber = r.nextLong()
      val roadPartNumber = r.nextLong()
      val track = r.nextLong()
      val addrM = r.nextLong()
      val beforeAfter = r.nextLong()
      val createdTime = dateOptTimeFormatter.parseDateTime(r.nextDate.toString)
      val createdBy = r.nextString()

      MissingCalibrationPointFromJunction(MissingCalibrationPoint(RoadPart(roadNumber, roadPartNumber), track, addrM, createdTime, createdBy),junctionPointId, junctionNumber, nodeNumber, BeforeAfter.apply(beforeAfter))
    }
  }

  private implicit val linksWithExtraCalibrationPoints: GetResult[LinksWithExtraCalibrationPoints] = new GetResult[LinksWithExtraCalibrationPoints] {
    def apply(r: PositionedResult): LinksWithExtraCalibrationPoints = {

      val linkId = r.nextString()
      val roadNumber = r.nextLong()
      val roadPartNumber = r.nextLong()
      val startCount = r.nextInt()
      val endCount = r.nextInt()
      val calibrationPointIdsString = r.nextString().replaceAll("[{}]", "") // Remove curly braces
      val calibrationPointIds = calibrationPointIdsString.split(",").map(_.trim.toInt) // Convert to Array[Int]

      LinksWithExtraCalibrationPoints(linkId, RoadPart(roadNumber, roadPartNumber), startCount, endCount, calibrationPointIds)
    }
  }

  private implicit val missingRoadwayPoint: GetResult[MissingRoadwayPoint] = new GetResult[MissingRoadwayPoint] {
    def apply(r: PositionedResult): MissingRoadwayPoint = {

      val roadNumber = r.nextLong()
      val roadPartNumber = r.nextLong()
      val track = r.nextLong()
      val startAddrM = r.nextLong()
      val createdTime = dateOptTimeFormatter.parseDateTime(r.nextDate.toString)
      val createdBy = r.nextString()

      MissingRoadwayPoint(RoadPart(roadNumber,roadPartNumber), track, startAddrM, createdTime, createdBy)
    }
  }

  private implicit val invalidRoadwayLength: GetResult[InvalidRoadwayLength] = new GetResult[InvalidRoadwayLength] {
    def apply(r: PositionedResult): InvalidRoadwayLength = {

      val length = r.nextLong()
      val roadwayNumber = r.nextLong()
      val startDate      = dateOptTimeFormatter.parseDateTime(r.nextDate.toString)
      val endDate        = r.nextDateOption.map(d => dateOptTimeFormatter.parseDateTime(d.toString))
      val roadNumber = r.nextLong()
      val roadPartNumber = r.nextLong()
      val track = r.nextLong()
      val startAddrM = r.nextLong()
      val endAddrM = r.nextLong()
      val createdBy = r.nextString()
      val createdTime    = dateOptTimeFormatter.parseDateTime(r.nextDate.toString)

      InvalidRoadwayLength(roadwayNumber, startDate, endDate, RoadPart(roadNumber,roadPartNumber), track, startAddrM, endAddrM, length, createdBy, createdTime)
    }
  }

  private implicit val getRoadway: GetResult[Roadway] = new GetResult[Roadway] {
    def apply(r: PositionedResult): Roadway = {

      val id = r.nextLong()
      val roadwayNumber = r.nextLong()
      val roadNumber = r.nextLong()
      val roadPartNumber = r.nextLong()
      val trackCode = r.nextInt()
      val startAddrMValue = r.nextLong()
      val endAddrMValue = r.nextLong()
      val reverted = r.nextBoolean()
      val discontinuity = r.nextInt()
      val startDate     = dateOptTimeFormatter.parseDateTime(r.nextDate.toString)
      val endDate       = r.nextDateOption.map(d => dateOptTimeFormatter.parseDateTime(d.toString))
      val createdBy = r.nextString()
      val administrativeClass = AdministrativeClass.apply(r.nextInt())
      val ely = r.nextLong()
      val terminated    = TerminationCode.apply(r.nextInt())
      val validFrom     = dateOptTimeFormatter.parseDateTime(r.nextDate.toString)
      val validTo       = r.nextDateOption.map(d => dateOptTimeFormatter.parseDateTime(d.toString))
      val roadName = r.nextStringOption()

      Roadway(id, roadwayNumber, RoadPart(roadNumber,roadPartNumber), administrativeClass, Track.apply(trackCode), Discontinuity.apply(discontinuity), AddrMRange(startAddrMValue, endAddrMValue), reverted, startDate, endDate, createdBy, roadName, ely, terminated, validFrom, validTo)
    }
  }

  private implicit val getOverlappingRoadwayOnLinearLocation: GetResult[OverlappingRoadwayOnLinearLocation] = new GetResult[OverlappingRoadwayOnLinearLocation] {
    def apply(r: PositionedResult): OverlappingRoadwayOnLinearLocation = {

      val id = r.nextLong()
      val roadwayNumber = r.nextLong()
      val roadNumber = r.nextLong()
      val roadPartNumber = r.nextLong()
      val trackCode = r.nextInt()
      val startAddrMValue = r.nextLong()
      val endAddrMValue = r.nextLong()
      val reverted = r.nextBoolean()
      val discontinuity = r.nextInt()
      val startDate     = dateOptTimeFormatter.parseDateTime(r.nextDate.toString)
      val endDate       = r.nextDateOption.map(d => dateOptTimeFormatter.parseDateTime(d.toString))
      val createdBy = r.nextString()
      val administrativeClass = AdministrativeClass.apply(r.nextInt())
      val ely = r.nextLong()
      val terminated = TerminationCode.apply(r.nextInt())
      val validFrom     = dateOptTimeFormatter.parseDateTime(r.nextDate.toString)
      val validTo       = r.nextDateOption.map(d => dateOptTimeFormatter.parseDateTime(d.toString))
      val roadName = r.nextStringOption()

      val linearLocationId = r.nextLong()
      val linkId = r.nextString()
      val linearLocationRoadwayNumber = r.nextLong()
      val linearLocationStartMeasure = r.nextLong()
      val linearLocationEndMeasure = r.nextLong()
      val linearLocationCreatedBy = r.nextString()
      val linearLocationCreatedTime = dateOptTimeFormatter.parseDateTime(r.nextDate.toString)

      OverlappingRoadwayOnLinearLocation(Roadway(id, roadwayNumber, RoadPart(roadNumber,roadPartNumber), administrativeClass, Track.apply(trackCode), Discontinuity.apply(discontinuity), AddrMRange(startAddrMValue, endAddrMValue), reverted, startDate, endDate, createdBy, roadName, ely, terminated, validFrom, validTo),
        linearLocationId, linkId, linearLocationRoadwayNumber, linearLocationStartMeasure, linearLocationEndMeasure, linearLocationCreatedBy, linearLocationCreatedTime
      )
    }
  }

  private val selectMissingCalibrationPointFromStart = s"""SELECT r.road_number,r.road_part_number,r.track,rp.addr_m ,r.created_time,r.created_by""".stripMargin

  def fetchMissingCalibrationPointsFromStart(): Seq[MissingCalibrationPoint] = {
    val query =
      s"""
         |$selectMissingCalibrationPointFromStart
         |FROM roadway_point rp,roadway r
         |WHERE (NOT EXISTS (SELECT 4 FROM calibration_point cp2 WHERE cp2.valid_to IS NULL AND cp2.roadway_point_id = rp.id ))
         |AND r.roadway_number = rp.roadway_number AND r.valid_to IS NULL AND r.end_date IS NULL
         |AND r.road_number<70000 AND rp.addr_m = 0
         |ORDER BY r.road_number,r.road_part_number,r.track,rp.addr_m;""".stripMargin
    Q.queryNA[MissingCalibrationPoint](query).iterator.toSeq
  }

  def fetchMissingCalibrationPointsFromStart(roadPart: RoadPart): Seq[MissingCalibrationPoint] = {
    val query =
      s"""
         |WITH selectedRoadways
         |     AS (SELECT *
         |         FROM roadway
         |         WHERE valid_to IS NULL
         |         AND road_number = ${roadPart.roadNumber}
         |         AND road_part_number = ${roadPart.partNumber}
         |         )
         |$selectMissingCalibrationPointFromStart
         |FROM roadway_point rp, selectedRoadways r
         |WHERE (NOT EXISTS (SELECT 4 FROM calibration_point cp2 WHERE cp2.valid_to IS NULL AND cp2.roadway_point_id = rp.id ))
         |AND r.roadway_number = rp.roadway_number AND r.valid_to IS NULL AND r.end_date IS NULL
         |AND r.road_number<70000 AND rp.addr_m = 0
         |ORDER BY r.road_number,r.road_part_number,r.track,rp.addr_m;""".stripMargin
    Q.queryNA[MissingCalibrationPoint](query).iterator.toSeq
  }

  private val selectMissingCalibrationPointFromEnd = s"""SELECT r.road_number,r.road_part_number,r.track,rp.addr_m ,r.created_time,r.created_by""".stripMargin

  def fetchMissingCalibrationPointsFromEnd(): Seq[MissingCalibrationPoint] = {
    val query =
      s"""
        $selectMissingCalibrationPointFromEnd
        FROM roadway_point rp, roadway r
        WHERE
        (NOT EXISTS (SELECT 4 FROM calibration_point cp2 WHERE cp2.valid_to IS NULL AND cp2.roadway_point_id = rp.id))
        AND r.roadway_number = rp.roadway_number AND r.valid_to IS NULL AND r.end_date IS NULL
        AND r.road_number < 70000 AND rp.addr_m = (SELECT MAX(r2.end_addr_m) FROM roadway r2
        WHERE r2.valid_to IS NULL AND r2.end_date IS NULL AND r.road_number = r2.road_number AND r.road_part_number = r2.road_part_number)
        ORDER BY r.road_number, r.road_part_number, r.track, rp.addr_m;""".stripMargin
    Q.queryNA[MissingCalibrationPoint](query).iterator.toSeq
  }

  def fetchMissingCalibrationPointsFromEnd(roadPart: RoadPart): Seq[MissingCalibrationPoint] = {
    val query =
      s"""
        WITH selectedRoadways
          AS (SELECT *
          FROM roadway
          WHERE valid_to IS NULL
          AND road_number = ${roadPart.roadNumber}
          AND road_part_number = ${roadPart.partNumber}
        )
        $selectMissingCalibrationPointFromEnd
        FROM roadway_point rp, selectedRoadways r
        WHERE
        (NOT EXISTS (SELECT 4 FROM calibration_point cp2 WHERE cp2.valid_to IS NULL AND cp2.roadway_point_id = rp.id))
        AND r.roadway_number = rp.roadway_number AND r.valid_to IS NULL AND r.end_date IS NULL
        AND r.road_number < 70000 AND rp.addr_m = (SELECT MAX(r2.end_addr_m) FROM roadway r2
        WHERE r2.valid_to IS NULL AND r2.end_date IS NULL AND r.road_number = r2.road_number AND r.road_part_number = r2.road_part_number)
        ORDER BY r.road_number, r.road_part_number, r.track, rp.addr_m;""".stripMargin
    Q.queryNA[MissingCalibrationPoint](query).iterator.toSeq
  }

  private val selectMissingCalibrationPointFromJunction = s"""SELECT jp.id AS junction_point_id,j.junction_number,j.node_number,r.road_number,r.road_part_number,r.track,rp.addr_m,jp.before_after,r.created_time,r.created_by""".stripMargin

  def fetchMissingCalibrationPointsFromJunctions(): Seq[MissingCalibrationPointFromJunction] = {
    val query =
      s"""
         $selectMissingCalibrationPointFromJunction
         |FROM   junction_point jp,
         |       roadway_point rp,
         |       roadway r,
         |       junction j
         |WHERE  jp.valid_to IS NULL
         |       AND ( NOT EXISTS (SELECT 4
         |                         FROM   calibration_point cp2
         |                         WHERE  cp2.valid_to IS NULL
         |                                AND cp2.roadway_point_id = jp.roadway_point_id)
         |           )
         |       AND rp.id = jp.roadway_point_id
         |       AND r.roadway_number = rp.roadway_number
         |       AND r.valid_to IS NULL
         |       AND r.end_date IS NULL
         |       AND r.road_number < 70000
         |       AND j.id = jp.junction_id
         |       AND j.end_date IS NULL
         |       AND j.valid_to IS NULL
         |ORDER  BY j.node_number,
         |          j.junction_number,
         |          r.road_number,
         |          r.road_part_number,
         |          r.track,
         |          rp.addr_m; """.stripMargin
    Q.queryNA[MissingCalibrationPointFromJunction](query).iterator.toSeq
  }

  def fetchMissingCalibrationPointsFromJunctions(roadPart: RoadPart): Seq[MissingCalibrationPointFromJunction] = {
    val query =
      s"""
         |WITH selectedRoadways
         |          AS (SELECT *
         |          FROM roadway
         |          WHERE valid_to IS NULL
         |          AND road_number = ${roadPart.roadNumber}
         |          AND road_part_number = ${roadPart.partNumber}
         |        )
         |$selectMissingCalibrationPointFromJunction
         |FROM   junction_point jp,
         |       roadway_point rp,
         |       selectedRoadways r,
         |       junction j
         |WHERE  jp.valid_to IS NULL
         |       AND ( NOT EXISTS (SELECT 4
         |                         FROM   calibration_point cp2
         |                         WHERE  cp2.valid_to IS NULL
         |                                AND cp2.roadway_point_id = jp.roadway_point_id)
         |           )
         |       AND rp.id = jp.roadway_point_id
         |       AND r.roadway_number = rp.roadway_number
         |       AND r.valid_to IS NULL
         |       AND r.end_date IS NULL
         |       AND r.road_number < 70000
         |       AND j.id = jp.junction_id
         |       AND j.end_date IS NULL
         |       AND j.valid_to IS NULL
         |ORDER  BY j.node_number,
         |          j.junction_number,
         |          r.road_number,
         |          r.road_part_number,
         |          r.track,
         |          rp.addr_m; """.stripMargin
    Q.queryNA[MissingCalibrationPointFromJunction](query).iterator.toSeq
  }

  private val selectMissingRoadwayPointFromStart = s"""SELECT r.road_number,r.road_part_number,r.track,r.start_addr_m,r.created_time, r.created_by""".stripMargin

  def fetchMissingRoadwayPointsFromStart(): Seq[MissingRoadwayPoint] = {
    val query =
      s"""
         $selectMissingRoadwayPointFromStart
         |FROM   roadway r
         |WHERE  ( NOT EXISTS (SELECT 4
         |                     FROM   roadway_point rp
         |                     WHERE  r.roadway_number = rp.roadway_number
         |                            AND r.start_addr_m = rp.addr_m) )
         |       AND r.valid_to IS NULL
         |       AND r.end_date IS NULL
         |       AND r.road_number < 70000
         |       AND r.start_addr_m = 0
         |ORDER  BY r.road_number,
         |          r.road_part_number,
         |          r.track,
         |          r.end_addr_m;""".stripMargin
    Q.queryNA[MissingRoadwayPoint](query).iterator.toSeq
  }

  def fetchMissingRoadwayPointsFromStart(roadPart: RoadPart): Seq[MissingRoadwayPoint] = {
    val query =
      s"""
         |WITH selectedRoadways
         |          AS (SELECT *
         |          FROM roadway
         |          WHERE valid_to IS NULL
         |          AND road_number = ${roadPart.roadNumber}
         |          AND road_part_number = ${roadPart.partNumber}
         |        )
         |$selectMissingRoadwayPointFromStart
         |FROM   selectedRoadways r
         |WHERE  ( NOT EXISTS (SELECT 4
         |                     FROM   roadway_point rp
         |                     WHERE  r.roadway_number = rp.roadway_number
         |                            AND r.start_addr_m = rp.addr_m) )
         |       AND r.valid_to IS NULL
         |       AND r.end_date IS NULL
         |       AND r.road_number < 70000
         |       AND r.start_addr_m = 0
         |ORDER  BY r.road_number,
         |          r.road_part_number,
         |          r.track,
         |          r.end_addr_m;""".stripMargin
    Q.queryNA[MissingRoadwayPoint](query).iterator.toSeq
  }

  private val selectMissingRoadwayPointFromEnd = s"""SELECT r.road_number,r.road_part_number,r.track,r.end_addr_m,r.created_time,r.created_by""".stripMargin

  def fetchMissingRoadwayPointsFromEnd(): Seq[MissingRoadwayPoint] = {
    val query =
      s"""
         $selectMissingRoadwayPointFromEnd
         |FROM   roadway r
         |WHERE  ( NOT EXISTS (SELECT 4
         |                     FROM   roadway_point rp
         |                     WHERE  r.roadway_number = rp.roadway_number
         |                            AND r.end_addr_m = rp.addr_m) )
         |       AND r.valid_to IS NULL
         |       AND r.end_date IS NULL
         |       AND r.road_number < 70000
         |       AND r.end_addr_m = (SELECT Max(r2.end_addr_m)
         |                           FROM   roadway r2
         |                           WHERE  r2.valid_to IS NULL
         |                                  AND r2.end_date IS NULL
         |                                  AND r.road_number = r2.road_number
         |                                  AND r.road_part_number = r2.road_part_number)
         |ORDER  BY r.road_number,
         |          r.road_part_number,
         |          r.track,
         |          r.end_addr_m; """.stripMargin
    Q.queryNA[MissingRoadwayPoint](query).iterator.toSeq
  }

  def fetchMissingRoadwayPointsFromEnd(roadPart: RoadPart): Seq[MissingRoadwayPoint] = {
    val query =
      s"""
         |WITH selectedRoadways
         |          AS (SELECT *
         |          FROM roadway
         |          WHERE valid_to IS NULL
         |          AND road_number = ${roadPart.roadNumber}
         |          AND road_part_number = ${roadPart.partNumber}
         |        )
         |$selectMissingRoadwayPointFromEnd
         |FROM   selectedRoadways r
         |WHERE  ( NOT EXISTS (SELECT 4
         |                     FROM   roadway_point rp
         |                     WHERE  r.roadway_number = rp.roadway_number
         |                            AND r.end_addr_m = rp.addr_m) )
         |       AND r.valid_to IS NULL
         |       AND r.end_date IS NULL
         |       AND r.road_number < 70000
         |       AND r.end_addr_m = (SELECT Max(r2.end_addr_m)
         |                           FROM   roadway r2
         |                           WHERE  r2.valid_to IS NULL
         |                                  AND r2.end_date IS NULL
         |                                  AND r.road_number = r2.road_number
         |                                  AND r.road_part_number = r2.road_part_number)
         |ORDER  BY r.road_number,
         |          r.road_part_number,
         |          r.track,
         |          r.end_addr_m; """.stripMargin
    Q.queryNA[MissingRoadwayPoint](query).iterator.toSeq
  }

  private val calibrationPointJoinAndWhereConditions =
    """FROM
        calibration_point cp
      JOIN
        roadway_point rp ON cp.roadway_point_id = rp.id
      JOIN
        roadway r ON rp.roadway_number = r.roadway_number
      WHERE
        cp.valid_to IS NULL
        AND r.valid_to IS NULL
        AND r.end_date IS NULL"""

  private val joinGroupAndOrderCalibrationPointData =
    """JOIN
        roadway r ON rp.roadway_number = r.roadway_number
      WHERE
        cp.valid_to IS NULL
      AND r.valid_to IS NULL
      AND r.end_date IS NULL
        GROUP BY scp.link_id, scp.start_end, scp.calibration_point_count, r.road_number, r.road_part_number
        ORDER BY
          r.road_number, r.road_part_number
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
      s"AND R.ROAD_NUMBER = ${rp.roadNumber} AND R.ROAD_PART_NUMBER = ${rp.partNumber}"
    }.getOrElse("")

    val query =
      s"""
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
    $joinGroupAndOrderCalibrationPointData;
    """.stripMargin
    Q.queryNA[LinksWithExtraCalibrationPoints](query).iterator.toSeq
  }

  /**
   * Fetch links that have more than one start or end calibration points with the same roadway number
   * The results are grouped by link ID, start/end, and other relevant fields to count and aggregate the calibration points
   *
   * @return Sequence of LinksWithExtraCalibrationPoints
   */
  def fetchLinksWithExtraCalibrationPointsWithSameRoadwayNumber(): Seq[LinksWithExtraCalibrationPoints] = {

    val query =
      s"""
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
    """.stripMargin
    Q.queryNA[LinksWithExtraCalibrationPoints](query).iterator.toSeq
  }

  private val selectOverlappingRoadwayOnLinearLocation =
    s"""SELECT DISTINCT r.id, r.roadway_number, r.road_number, r.road_part_number, r.track,
       | r.start_addr_m, r.end_addr_m, r.reversed, r.discontinuity, r.start_date, r.end_date,
       |  r.created_by, r.administrative_class, r.ely, r.terminated, r.valid_from, r.valid_to,
       |   (SELECT rn.road_name FROM road_name rn WHERE rn.road_number = r.road_number AND rn.end_date IS NULL AND rn.valid_to IS NULL) AS road_name,
       |    l.id AS linearLocationId, l.link_id, l.roadway_number, l.start_measure, l.end_measure, l.created_by, l.created_time""".stripMargin


  //TODO better naming for this query and case class
  def fetchOverlappingRoadwaysOnLinearLocations(): Seq[OverlappingRoadwayOnLinearLocation] = {
    val query =
      s"""WITH selectedRoadways
         |     AS (SELECT *
         |         FROM   roadway
         |         WHERE  valid_to IS NULL),
         |     selectedLinearLocations
         |     AS (SELECT *
         |         FROM   linear_location
         |         WHERE  valid_to IS NULL)
         $selectOverlappingRoadwayOnLinearLocation
         |FROM   selectedRoadways r
         |       JOIN selectedLinearLocations l
         |         ON r.roadway_number = l.roadway_number
         |WHERE EXISTS (SELECT 4
         |                   FROM   selectedLinearLocations l2
         |                          JOIN roadway r2
         |                            ON r2.roadway_number = l2.roadway_number
         |                   WHERE  l.link_id = l2.link_id
         |                          AND r2.valid_to IS NULL
         |                          AND l2.valid_to IS NULL
         |                          AND ( NOT r2.roadway_number = r.roadway_number )
         |                          AND ( ( l.start_measure >= l2.start_measure
         |                                  AND l.start_measure < l2.end_measure ) --  if the beginning of linearlocation l is somewhere between the start and end of linearlocation l2
         |                                 OR ( l.end_measure > l2.start_measure
         |                                      AND l.end_measure <= l2.end_measure ) --  if the end of linearlocation l is somewhere between the start and end of linearlocation l2.
         |                                 OR ( l.start_measure < l2.start_measure
         |                                      AND l.end_measure > l2.end_measure ) ) -- if linearlocation l completely spans over linearlocation l2
         |                  ) """.stripMargin

    Q.queryNA[OverlappingRoadwayOnLinearLocation](query).iterator.toSeq
  }

  //TODO better naming for this query and case class
  def fetchOverlappingRoadwaysOnLinearLocations(roadPart: RoadPart): Seq[OverlappingRoadwayOnLinearLocation] = {
    val query =
      s"""
         WITH selectedRoadways
         |     AS (SELECT *
         |         FROM   roadway
         |         WHERE  valid_to IS NULL
         |         AND road_number = ${roadPart.roadNumber}
         |         AND road_part_number = ${roadPart.partNumber}),
         |     selectedLinearLocations
         |     AS (SELECT *
         |         FROM   linear_location
         |         WHERE  valid_to IS NULL)
         $selectOverlappingRoadwayOnLinearLocation
         |FROM   selectedRoadways r
         |       JOIN selectedLinearLocations l
         |         ON r.roadway_number = l.roadway_number
         |WHERE EXISTS (SELECT 4
         |                   FROM   selectedLinearLocations l2
         |                          JOIN roadway r2
         |                            ON r2.roadway_number = l2.roadway_number
         |                   WHERE  l.link_id = l2.link_id
         |                          AND r2.valid_to IS NULL
         |                          AND l2.valid_to IS NULL
         |                          AND ( NOT r2.roadway_number = r.roadway_number )
         |                          AND ( ( l.start_measure >= l2.start_measure
         |                                  AND l.start_measure < l2.end_measure ) --  if the beginning of linearlocation l is somewhere between the start and end of linearlocation l2
         |                                 OR ( l.end_measure > l2.start_measure
         |                                      AND l.end_measure <= l2.end_measure ) --  if the end of linearlocation l is somewhere between the start and end of linearlocation l2.
         |                                 OR ( l.start_measure < l2.start_measure
         |                                      AND l.end_measure > l2.end_measure ) ) -- if linearlocation l completely spans over linearlocation l2
         |                  ) """.stripMargin
    Q.queryNA[OverlappingRoadwayOnLinearLocation](query).iterator.toSeq
  }

  private val selectinvalidRoadwayLength = s"""SELECT DISTINCT r.end_addr_m - r.start_addr_m AS pituus,
                                       |                r.roadway_number,
                                       |                r.start_date,
                                       |                r.end_date,
                                       |                r.road_number,
                                       |                r.road_part_number,
                                       |                r.track,
                                       |                r.start_addr_m,
                                       |                r.end_addr_m,
                                       |                r.created_by,
                                       |                r.created_time""".stripMargin

  def fetchInvalidRoadwayLengths(): Seq[InvalidRoadwayLength] = {
    val query =
      s"""
         $selectinvalidRoadwayLength
         |FROM   roadway r
         |WHERE  r.valid_to IS NULL
         |       AND ( EXISTS (SELECT 4
         |                     FROM   roadway r2
         |                     WHERE  r.roadway_number = r2.roadway_number
         |                            AND ( NOT r2.end_addr_m - r2.start_addr_m =
         |                                      r.end_addr_m - r.start_addr_m )
         |                            AND r2.valid_to IS NULL) )
         |ORDER  BY r.roadway_number, r.road_number, r.road_part_number""".stripMargin
    Q.queryNA[InvalidRoadwayLength](query).iterator.toSeq
  }

  def fetchInvalidRoadwayLengths(roadPart: RoadPart): Seq[InvalidRoadwayLength] = {
    val query =
      s"""
         |WITH selectedRoadways
         |     AS (SELECT *
         |         FROM roadway
         |         WHERE valid_to IS NULL
         |         AND road_number = ${roadPart.roadNumber}
         |         AND road_part_number = ${roadPart.partNumber}
         |         )
         |$selectinvalidRoadwayLength
         |FROM   selectedRoadways r
         |WHERE  r.valid_to IS NULL
         |       AND ( EXISTS (SELECT 4
         |                     FROM   roadway r2
         |                     WHERE  r.roadway_number = r2.roadway_number
         |                            AND ( NOT r2.end_addr_m - r2.start_addr_m =
         |                                      r.end_addr_m - r.start_addr_m )
         |                            AND r2.valid_to IS NULL) )
         |ORDER  BY r.road_number""".stripMargin
    Q.queryNA[InvalidRoadwayLength](query).iterator.toSeq
  }

  private val selectOverlappingRoadway = s"""SELECT DISTINCT r.id, r.ROADWAY_NUMBER, r.road_number, r.road_part_number, r.TRACK, r.start_addr_m, r.end_addr_m,
                                    |          r.reversed, r.discontinuity, r.start_date, r.end_date, r.created_by, r.ADMINISTRATIVE_CLASS, r.ely, r.terminated,
                                    |          r.valid_from, r.valid_to,
                                    |          (select rn.road_name from road_name rn where rn.road_number = r.road_number and rn.end_date is null and rn.valid_to is null) as road_name""".stripMargin

  def fetchOverlappingRoadwaysInHistory(): Seq[Roadway] = {
    val query =
      s"""
         $selectOverlappingRoadway
         |FROM   roadway r
         |WHERE  r.valid_to IS NULL
         |       AND ( EXISTS (SELECT 4
         |                     FROM   roadway r2
         |                     WHERE  r.road_number = r2.road_number
         |                            AND ( NOT r.id = r2.id )
         |                            AND r.road_part_number = r2.road_part_number
         |                            AND ( r.track = 0
         |                                   OR r2.track = 0
         |                                   OR r.track = r2.track )
         |                            AND ( ( r.start_addr_m >= r2.start_addr_m
         |                                    AND r.start_addr_m < r2.end_addr_m )
         |                                   OR ( r.end_addr_m > r2.start_addr_m
         |                                        AND r.end_addr_m <= r2.end_addr_m )
         |                                   OR ( r.start_addr_m < r2.start_addr_m
         |                                        AND r.end_addr_m > r2.end_addr_m ) )
         |                            AND r2.valid_to IS NULL --and r2.end_date is null
         |                            AND ( ( r.start_date > r2.start_date
         |                                    AND r.start_date < r2.end_date )
         |                                   OR ( r.end_date > r2.start_date
         |                                        AND r.end_date < r2.end_date )
         |                                   OR ( r.start_date < r2.start_date
         |                                        AND r.end_date > r2.end_date ) )) )
         |ORDER  BY r.road_number,
         |          r.road_part_number,
         |          r.start_addr_m,
         |          r.track """.stripMargin
    Q.queryNA[Roadway](query).iterator.toSeq
  }

  def fetchOverlappingRoadwaysInHistory(roadPart: RoadPart): Seq[Roadway] = {
    val query =
      s"""
         |WITH selectedRoadways
         |     AS (SELECT *
         |         FROM roadway
         |         WHERE valid_to IS NULL
         |         AND road_number = ${roadPart.roadNumber}
         |         AND road_part_number = ${roadPart.partNumber}
         |         )
         |$selectOverlappingRoadway
         |FROM   selectedRoadways r
         |WHERE  r.valid_to IS NULL
         |       AND ( EXISTS (SELECT 4
         |                     FROM   roadway r2
         |                     WHERE  r.road_number = r2.road_number
         |                            AND ( NOT r.id = r2.id )
         |                            AND r.road_part_number = r2.road_part_number
         |                            AND ( r.track = 0
         |                                   OR r2.track = 0
         |                                   OR r.track = r2.track )
         |                            AND ( ( r.start_addr_m >= r2.start_addr_m
         |                                    AND r.start_addr_m < r2.end_addr_m )
         |                                   OR ( r.end_addr_m > r2.start_addr_m
         |                                        AND r.end_addr_m <= r2.end_addr_m )
         |                                   OR ( r.start_addr_m < r2.start_addr_m
         |                                        AND r.end_addr_m > r2.end_addr_m ) )
         |                            AND r2.valid_to IS NULL --and r2.end_date is null
         |                            AND ( ( r.start_date > r2.start_date
         |                                    AND r.start_date < r2.end_date )
         |                                   OR ( r.end_date > r2.start_date
         |                                        AND r.end_date < r2.end_date )
         |                                   OR ( r.start_date < r2.start_date
         |                                        AND r.end_date > r2.end_date ) )) )
         |ORDER  BY r.road_number,
         |          r.road_part_number,
         |          r.start_addr_m,
         |          r.track """.stripMargin
    Q.queryNA[Roadway](query).iterator.toSeq
  }

  /**
    * Fetches the data required for /summary API result generation.
    * @return The road address information, and road names of the whole latest road network
    */
  def fetchRoadwayNetworkSummary(date: Option[DateTime] = None): Seq[RoadwayNetworkSummaryRow] = {
    time(logger, "Get whole network summary") {
      val dateFilter = date match {
        case Some(date) =>
          val dateString = date.toString("yyyy-MM-dd")
          s"r.START_DATE <= to_date('$dateString', 'yyyy-MM-dd') " +
          s"AND (r.END_DATE IS NULL OR to_date('$dateString', 'yyyy-MM-dd') <= r.END_DATE) "
        case None => "r.END_DATE IS NULL"
      }

      val roadnameTable = date match {
        case Some(date) =>
          val dateString = date.toString("yyyy-MM-dd")
          s"(SELECT * FROM ROAD_NAME rn WHERE rn.START_DATE <= to_date('$dateString', 'yyyy-MM-dd')" +
          s"AND (rn.END_DATE IS NULL OR to_date('$dateString', 'yyyy-MM-dd') <= rn.END_DATE) and rn.VALID_TO IS NULL)"
        case None => "(SELECT * FROM ROAD_NAME rn WHERE rn.END_DATE IS NULL)"
      }

      val query = s"""
         SELECT r.ROAD_NUMBER, n.ROAD_NAME,
                r.ROAD_PART_NUMBER, r.ELY, r.ADMINISTRATIVE_CLASS,
                r.TRACK, r.START_ADDR_M, r.END_ADDR_M, r.DISCONTINUITY
           FROM ROADWAY r
      LEFT JOIN $roadnameTable n ON n.ROAD_NUMBER = r.ROAD_NUMBER
          WHERE r.VALID_TO IS NULL AND $dateFilter    -- no debug rows, no history rows -> latest state
       ORDER BY r.ROAD_NUMBER, r.ROAD_PART_NUMBER, r.TRACK, r.START_ADDR_M
      ;
      """
      Q.queryNA[RoadwayNetworkSummaryRow](query).list
    }
  }

  /**
    * PositionResult iterator required by queryNA[RoadwayNetworkSummaryRow]
    * @return Returns the next row from the RoadwayNetworkSummary query results as a RoadwayNetworkSummaryRow.
    */
  private implicit val getRoadwayNetworkSummaryRow
  : GetResult[RoadwayNetworkSummaryRow] = new GetResult[RoadwayNetworkSummaryRow] {
    def apply(r: PositionedResult) = {

      val roadNumber = r.nextInt()
      val roadName = r.nextString()
      val roadPartNumber = r.nextInt()
      val elyCode = r.nextInt()
      val administrativeClass = r.nextInt()
      val track = r.nextInt()
      val startAddressM = r.nextInt()
      val endAddressM = r.nextInt()
      val continuity = r.nextInt()

      RoadwayNetworkSummaryRow(
        RoadPart(roadNumber,roadPartNumber), roadName,
        elyCode, administrativeClass,
        track, startAddressM, endAddressM, continuity)
    }
  }

}

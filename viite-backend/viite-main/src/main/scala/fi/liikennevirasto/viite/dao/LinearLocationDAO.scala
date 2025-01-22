package fi.liikennevirasto.viite.dao

import fi.liikennevirasto.digiroad2.util.LogUtils.time
import fi.liikennevirasto.viite._
import fi.liikennevirasto.viite.dao.LinearLocation.privateLogger
import fi.liikennevirasto.viite.process.RoadAddressFiller.LinearLocationAdjustment
import fi.vaylavirasto.viite.dao.{BaseDAO, LinkDAO, Sequences}
import fi.vaylavirasto.viite.geometry.GeometryUtils.scaleToThreeDigits
import fi.vaylavirasto.viite.geometry.{BoundingRectangle, GeometryUtils, Point}
import fi.vaylavirasto.viite.model.{CalibrationPointType, LinkGeomSource, RoadPart, SideCode}
import fi.vaylavirasto.viite.postgis.{GeometryDbUtils, MassQuery}
import org.joda.time.DateTime
import org.slf4j.{Logger, LoggerFactory}
import scalikejdbc._
import scalikejdbc.jodatime.JodaWrappedResultSet.fromWrappedResultSetToJodaWrappedResultSet

trait BaseLinearLocation {
  def id: Long

  def orderNumber: Double

  def linkId: String

  def startMValue: Double

  def endMValue: Double

  def sideCode: SideCode

  def adjustedTimestamp: Long

  def calibrationPoints: (CalibrationPointReference, CalibrationPointReference) // TODO These should be optional and the values in CPR should not be optional

  def geometry: Seq[Point]

  def linkGeomSource: LinkGeomSource

  def roadwayNumber: Long

  def validFrom: Option[DateTime]

  def validTo: Option[DateTime]

  def copyWithGeometry(newGeometry: Seq[Point]): BaseLinearLocation

  def getCalibrationCode: CalibrationCode = {
    (startCalibrationPoint.addrM, endCalibrationPoint.addrM) match {
      case (Some(_), Some(_)) => CalibrationCode.AtBoth
      case (Some(_), _) => CalibrationCode.AtBeginning
      case (_, Some(_)) => CalibrationCode.AtEnd
      case _ => CalibrationCode.No
    }
  }

  def hasCalibrationPointAt(calibrationCode: CalibrationCode): Boolean = {
    val raCalibrationCode = getCalibrationCode
    if (calibrationCode == CalibrationCode.No || calibrationCode == CalibrationCode.AtBoth)
      raCalibrationCode == calibrationCode
    else
      raCalibrationCode == CalibrationCode.AtBoth || raCalibrationCode == calibrationCode
  }

  def connected(ra2: BaseLinearLocation): Boolean = {
    val currEndPoint = sideCode match {
      case SideCode.AgainstDigitizing => geometry.head
      case _ => geometry.last
    }

    val nextStartPoint = ra2.sideCode match {
      case SideCode.AgainstDigitizing => ra2.geometry.last
      case _ => ra2.geometry.head
    }

    GeometryUtils.areAdjacent(nextStartPoint, currEndPoint, fi.liikennevirasto.viite.MaxDistanceForConnectedLinks)
  }

  lazy val startCalibrationPoint: CalibrationPointReference = calibrationPoints._1
  lazy val endCalibrationPoint: CalibrationPointReference = calibrationPoints._2

  def startCalibrationPointType: CalibrationPointType =
    if (startCalibrationPoint.typeCode.isDefined) startCalibrationPoint.typeCode.get else CalibrationPointType.NoCP

  def endCalibrationPointType: CalibrationPointType =
    if (endCalibrationPoint.typeCode.isDefined) endCalibrationPoint.typeCode.get else CalibrationPointType.NoCP
}

case class CalibrationPointReference(addrM: Option[Long], typeCode: Option[CalibrationPointType] = Option.empty) {
  def isEmpty: Boolean = addrM.isEmpty
  def isDefined: Boolean = addrM.isDefined

  def isRoadAddressCP(): Boolean = {
    this.typeCode match {
      case Some(t) if t.equals(CalibrationPointType.RoadAddressCP) => true
      case _ => false
    }
  }

  def isJunctionPointCP(): Boolean = {
    this.typeCode match {
      case Some(t) if t.equals(CalibrationPointType.JunctionPointCP) => true
      case _ => false
    }
  }
}

object CalibrationPointReference {
  val None: CalibrationPointReference = CalibrationPointReference(Option.empty[Long], Option.empty[CalibrationPointType])
}

// Notes:
//  - Geometry on linear location is not directed: it isn't guaranteed to have a direction of digitization or road addressing
//  - Order number is a Double in LinearLocation case class and Long on the database because when there is for example divided change type we need to add more linear locations
case class LinearLocation(id: Long, orderNumber: Double, linkId: String,
                          startMValue: Double, endMValue: Double, sideCode: SideCode, adjustedTimestamp: Long,
                          calibrationPoints: (CalibrationPointReference, CalibrationPointReference) = (CalibrationPointReference.None, CalibrationPointReference.None),
                          geometry: Seq[Point], linkGeomSource: LinkGeomSource,
                          roadwayNumber: Long,
                          validFrom: Option[DateTime] = None, validTo: Option[DateTime] = None) extends BaseLinearLocation {
  def this(id: Long, orderNumber: Double, linkId: Long, startMValue: Double, endMValue: Double, sideCode: SideCode, adjustedTimestamp: Long, calibrationPoints: (CalibrationPointReference, CalibrationPointReference), geometry: Seq[Point], linkGeomSource: LinkGeomSource, roadwayNumber: Long, validFrom: Option[DateTime], validTo: Option[DateTime]) =
   this(id, orderNumber, linkId.toString, startMValue, endMValue, sideCode, adjustedTimestamp, calibrationPoints, geometry, linkGeomSource, roadwayNumber, validFrom, validTo)

  def copyWithGeometry(newGeometry: Seq[Point]): LinearLocation = {
    this.copy(geometry = newGeometry)
  }

  def getFirstPoint: Point = {
    if (sideCode == SideCode.TowardsDigitizing) geometry.head else geometry.last
  }

  def getLastPoint: Point = {
    if (sideCode == SideCode.TowardsDigitizing) geometry.last else geometry.head
  }

}

object LinearLocation extends SQLSyntaxSupport[LinearLocation] {
  override val tableName = "LINEAR_LOCATION"

  val privateLogger: Logger = LoggerFactory.getLogger(getClass) // called from Future; class logger invalid there (diff. thread); we need our own

  def apply(rs: WrappedResultSet): LinearLocation = new LinearLocation (
      id                = rs.long("id"),
      orderNumber       = rs.double("order_number"),
      linkId            = rs.string("link_id"),
      startMValue       = rs.double("start_measure"),
      endMValue         = rs.double("end_measure"),
      sideCode          = SideCode(rs.int("side")),
      adjustedTimestamp = rs.long("adjusted_timestamp"),
      calibrationPoints = (
        CalibrationPointReference(
          addrM         = rs.longOpt("cal_start_addr_m"),
          typeCode      = rs.intOpt("cal_start_type").map(CalibrationPointType.apply)
        ),
        CalibrationPointReference(
          addrM         = rs.longOpt("cal_end_addr_m"),
          typeCode      = rs.intOpt("cal_end_type").map(CalibrationPointType.apply)
        )
      ),
      geometry = Seq(
        Point(x = rs.double("start_x"),y = rs.double("start_y")),
        Point(x = rs.double("end_x"),  y = rs.double("end_y")
        )
      ),
      linkGeomSource  = LinkGeomSource(rs.int("source")),
      roadwayNumber   = rs.long("roadway_number"),
      validFrom       = rs.jodaDateTimeOpt("valid_from"),
      validTo         = rs.jodaDateTimeOpt("valid_to")
    )
}

/**
 * Coordinate point of junction (the blue circle on the map).
 * Used only in /nodes/valid API
 * @param xCoord
 * @param yCoord
 */
case class JunctionCoordinate(xCoord: Double, yCoord: Double)

object JunctionCoordinateScalike extends SQLSyntaxSupport[JunctionCoordinate] {
  def apply(rs: WrappedResultSet): JunctionCoordinate = JunctionCoordinate(
    xCoord = rs.double("xCoord"),
    yCoord = rs.double("yCoord")
  )
}

//TODO Rename all the method names to follow a rule like fetchById instead of have fetchById and QueryById
class LinearLocationDAO extends BaseDAO {

  private def queryList(query: SQL[Nothing, NoExtractor]): List[LinearLocation] = {
    runSelectQuery(query.map(LinearLocation.apply)).groupBy(_.id).map {
        case (_, list) => list.head
      }
      .toList
  }

  lazy val selectFromLinearLocation =
    sqls"""
       SELECT loc.ID, loc.roadway_number, loc.ORDER_NUMBER, loc.LINK_ID, loc.start_measure, loc.END_MEASURE, loc.SIDE,
              (SELECT rp.addr_m FROM calibration_point cp JOIN ROADWAY_POINT RP ON RP.ID = CP.ROADWAY_POINT_ID WHERE cp.LINK_ID = loc.LINK_ID AND loc.roadway_number = rp.roadway_number AND cp.START_END = 0 AND cp.valid_to IS NULL) AS cal_start_addr_m,
              (SELECT cp.type FROM   calibration_point cp JOIN ROADWAY_POINT RP ON RP.ID = CP.ROADWAY_POINT_ID WHERE cp.LINK_ID = loc.LINK_ID AND loc.roadway_number = rp.roadway_number AND cp.START_END = 0 AND cp.valid_to IS NULL) AS cal_start_type,
              (SELECT rp.addr_m FROM calibration_point cp JOIN ROADWAY_POINT RP ON RP.ID = CP.ROADWAY_POINT_ID WHERE cp.LINK_ID = loc.LINK_ID AND loc.roadway_number = rp.roadway_number AND cp.START_END = 1 AND cp.valid_to IS NULL) AS cal_end_addr_m,
              (SELECT cp.type FROM   calibration_point cp JOIN ROADWAY_POINT RP ON RP.ID = CP.ROADWAY_POINT_ID WHERE cp.LINK_ID = loc.LINK_ID AND loc.roadway_number = rp.roadway_number AND cp.START_END = 1 AND cp.valid_to IS NULL) AS cal_end_type,
              link.source, link.adjusted_timestamp,
              ST_X(ST_StartPoint(loc.geometry)) AS start_x,
              ST_Y(ST_StartPoint(loc.geometry)) AS start_y,
              ST_X(ST_EndPoint(loc.geometry)) AS end_x,
              ST_Y(ST_EndPoint(loc.geometry)) AS end_y,
              loc.valid_from, loc.valid_to
        FROM linear_location loc
        JOIN link ON (link.ID = loc.LINK_ID)
    """

  def create(linearLocations: Iterable[LinearLocation], createdBy: String = "-"): Seq[Long] = {

    val query =
      sql"""
      INSERT INTO LINEAR_LOCATION (id, roadway_number, order_number, link_id, start_measure, end_measure, SIDE, geometry, created_by)
      VALUES (?, ?, ?, ?, ?, ?, ?, ST_GeomFromText(?, 3067), ?)
      """

    // Set ids for the linear locations without one
    val (ready, idLess) = linearLocations.partition(_.id != NewIdValue)
    val newIds = Sequences.fetchLinearLocationIds(idLess.size)
    val createLinearLocations = ready ++ idLess.zip(newIds).map(x =>
      x._1.copy(id = x._2)
    )

    val batchParams = createLinearLocations.map { location =>
      LinkDAO.createIfEmptyFetch(location.linkId, location.adjustedTimestamp, location.linkGeomSource.value)
      val roadwayNumber = if (location.roadwayNumber == NewIdValue) {
        Sequences.nextRoadwayNumber
      } else {
        location.roadwayNumber
      }
      Seq(
        location.id,
        roadwayNumber,
        location.orderNumber.toLong,
        location.linkId,
        location.startMValue,
        location.endMValue,
        location.sideCode.value,
        GeometryDbUtils.createXYZMGeometry(Seq((location.geometry.head, location.startMValue), (location.geometry.last, location.endMValue))),
        if (createdBy == null) "-" else createdBy
      )
    }

    runBatchUpdateToDb(query, batchParams.toSeq)

    createLinearLocations.map(_.id).toSeq // Return the ids of the created linear locations
  }


  def fetchLinkIdsInChunk(min: String, max: String): List[String] = { //TODO: Refactor for new linkId
    val query =
      sql"""
        SELECT distinct(loc.link_id)
        from linear_location loc WHERE loc.link_id between $min and $max order by loc.link_id asc
      """ //.as[String].list
    runSelectQuery(query.map(rs => rs.string("link_id")))
  }

  def fetchById(id: Long): Option[LinearLocation] = {
    time(logger, "Fetch linear location by id") {
      val query =
        sql"""
            $selectFromLinearLocation
            WHERE loc.id = $id
          """
      runSelectSingleOption(query.map(LinearLocation.apply))
    }
  }

  def queryById(ids: Set[Long], rejectInvalids: Boolean = true): List[LinearLocation] = {
    time(logger, "Fetch linear locations by ids") {
      if (ids.isEmpty) {
        return List()
      }
      if (ids.size > 1000) {
        return queryByIdMassQuery(ids, rejectInvalids)
      }

      val validToFilter = if (rejectInvalids)
        sqls" AND loc.valid_to IS NULL"
      else
        sqls""

      val query =
        sql"""
            $selectFromLinearLocation
            WHERE loc.id IN ($ids)
            $validToFilter
          """
      queryList(query)
    }
  }

  def fetchByIdMassQuery(ids: Iterable[Long], rejectInvalids: Boolean = true): List[LinearLocation] = {
    time(logger, "Fetch linear locations by id - mass query") {
      MassQuery.withIds(ids)({
        idTableName =>

          val validToFilter = if (rejectInvalids)
            sqls" WHERE loc.valid_to IS NULL"
          else
            sqls""

          val query =
            sql"""
                $selectFromLinearLocation
                JOIN $idTableName i ON i.id = loc.id
                $validToFilter
              """
          queryList(query)
      })
    }
  }

  def queryByIdMassQuery(ids: Set[Long], rejectInvalids: Boolean = true): List[LinearLocation] = {
    fetchByIdMassQuery(ids, rejectInvalids)
  }


  /**
   * Returns the linear locations within the current network (valid_to IS NULL), who
   * have the given <i>linkId</i>, and
   * have their M values fit within <i>filterMvalueMin</i>...<i>filterMvalueMax</i> range.
   * The results are a bit robust filtered: A linear location that overlaps less than [[GeometryUtils.DefaultEpsilon]]
   * the given range at either end, is not included in the results.
   *
   * @todo should it be e.g. 10cm instead of [[GeometryUtils.DefaultEpsilon]]?
   * @param linkId          Filters the returned linear locations to those having this link id.
   * @param filterMvalueMin Filters the returned linear locations to those that enter the range at minimum end
   * @param filterMvalueMax Filters the returned linear locations to those that enter the range at maximum end
   * @return List of Linear locations within given range, ordered by their start measures.
   *         An overlap less than [[GeometryUtils.DefaultEpsilon]] is not seen as fitting the range.
   */
  def fetchByLinkIdAndMValueRange(linkId: String, filterMvalueMin: Double, filterMvalueMax: Double): List[LinearLocation] = {
    time(logger, "Fetch linear locations by link id, and M values") {

      val mustStartBefore = filterMvalueMax - GeometryUtils.DefaultEpsilon // do not count overlap less than epsilon at max value end
      val mustEndAfter = filterMvalueMin + GeometryUtils.DefaultEpsilon // do not count overlap less than epsilon at min value end

      val query =
        sql"""
            $selectFromLinearLocation
            WHERE loc.link_id = $linkId
            AND loc.start_measure <= $mustStartBefore -- The start of a valid linear location is before the given max value
            AND loc.end_measure   >= $mustEndAfter    -- The end   of a valid linear location is after  the given min value
            AND loc.valid_to IS NULL
            ORDER BY loc.start_measure
          """
      queryList(query)
    }
  }

  def fetchByLinkId(linkIds: Set[String], filterIds: Set[Long] = Set()): List[LinearLocation] = {
    time(logger, "Fetch linear locations by link id") {
      if (linkIds.isEmpty) {
        return List()
      }
      if (linkIds.size > 1000 || filterIds.size > 1000) {
        return fetchByLinkIdMassQuery(linkIds).filterNot(ra => filterIds.contains(ra.id))
      }

      val idFilter = if (filterIds.nonEmpty)
        sqls"AND loc.id not in ($filterIds)"
      else
        sqls""

      val query =
        sql"""
            $selectFromLinearLocation
            WHERE loc.link_id IN ($linkIds) $idFilter
            AND loc.valid_to IS NULL
          """
      queryList(query)
    }
  }

  def fetchByLinkIdMassQuery(linkIds: Set[String]): List[LinearLocation] = {
    time(logger, "Fetch linear locations by link id - mass query") {
      MassQuery.withIds(linkIds)({
        idTableName =>
          val query =
            sql"""
                $selectFromLinearLocation
                JOIN $idTableName i ON i.id = loc.link_id
                WHERE loc.valid_to IS NULL
              """
          queryList(query)
      })
    }
  }

  def fetchByRoadwayNumber(roadwayNumbers: Iterable[Long]): List[LinearLocation] = {
    time(logger, "Fetch linear locations by roadway numbers") {
      if (roadwayNumbers.isEmpty) {
        return List()
      }

      val query =
        sql"""
            $selectFromLinearLocation
            WHERE loc.roadway_number IN ($roadwayNumbers) AND loc.valid_to IS NULL
          """
      queryList(query)
    }
  }

  /**
   * Fetch all the linear locations inside roadways with the given link ids
   *
   * @param linkIds The given road link identifiers
   * @return Returns all the filtered linear locations
   */
  def fetchRoadwayByLinkId(linkIds: Set[String]): List[LinearLocation] = {
    time(logger, "Fetch all linear locations of a roadway by link id") {
      if (linkIds.isEmpty) {
        return List()
      }
      if (linkIds.size > 1000) {
        return fetchRoadwayByLinkIdMassQuery(linkIds)
      }

      val query =
        sql"""
            $selectFromLinearLocation
            WHERE loc.valid_to IS NULL and loc.roadway_number IN (
              SELECT roadway_number FROM linear_location
              WHERE valid_to IS NULL AND link_id IN ($linkIds)
              )
          """
      queryList(query)
    }
  }

  def fetchRoadwayByLinkIdMassQuery(linkIds: Set[String]): List[LinearLocation] = {
    time(logger, "Fetch all linear locations of a roadway by link id - mass query") {
      MassQuery.withIds(linkIds)({
        idTableName =>
          val query =
            sql"""
                $selectFromLinearLocation
                WHERE loc.valid_to IS NULL AND loc.roadway_number IN (
                  SELECT roadway_number FROM linear_location
                  JOIN $idTableName i ON i.id = link_id
                  WHERE valid_to IS NULL
                  )
              """
          queryList(query)
      })
    }
  }


  def fetchCoordinatesForJunction(llIds: Seq[Long], crossingRoads: Seq[RoadwaysForJunction], allLL: Seq[LinearLocation]): Option[Point] = {

    val llLength = llIds.length

    if (llLength == 2) { //Check whether the geometry's start and endpoints are the same with 2 linear locations.
      val ll1 = allLL.find(_.id == llIds(0))
      val ll2 = allLL.find(_.id == llIds(1))

      if (ll1.get.geometry == ll2.get.geometry) {
        val cr1 = crossingRoads.find(_.roadwayNumber == ll1.get.roadwayNumber)
        val cr2 = crossingRoads.find(_.roadwayNumber == ll2.get.roadwayNumber)

        val beforeAfter1: Long = cr1.get.beforeAfter
        val beforeAfter2: Long = cr2.get.beforeAfter

        var point = Point(0, 0)

        point =
          (if (beforeAfter1 == beforeAfter2) { // If both have same before-after
            if (beforeAfter1 == 1) ll1.get.getLastPoint
            else ll2.get.getFirstPoint
          } else {
            if (beforeAfter1 == 1) ll1.get.getLastPoint
            else ll2.get.getLastPoint
          })
        return Some(point)
      }
    }

    llIds.size match {
      case size if (size > 1) => { // must have at least two linear locations for crossing to make any sense
        queryjunctionCoordinates(llIds)
      }
      case _ => {
        privateLogger.warn(s"fetchCoordinatesForJunction: Not enough llIds (only ${llIds.size}${if (llIds.size == 1) "; id " + llIds(0)})")
        None
      }
    }
  }

  def queryjunctionCoordinates(llIds: Seq[Long]) = {

    var intersectionFunction = sqls""
    var closingBracket = sqls""
    val precision = MaxDistanceForConnectedLinks
    val maxCheckedLLs = 8 // The closest point most probably is not further away than 8 LLs. 8 is already quite much.

    // build query for finding intersection, or closest points, within the given linear locations. Default algorithm ST_Intersection.
    def buildCoordinateQuery(coordinateFinderFunction: SQLSyntax = sqls"ST_Intersection"): SQL[Nothing, NoExtractor] = {
      // First two LLs handled at intersectionOfTheFirstTwoLLs. Find / Check for the point determined by coordinateFinderFunction, within max. maxCheckedLLs linearLocations.
      for (i <- 2 until math.min(llIds.length, maxCheckedLLs)) {
        val llId = llIds(i)
        intersectionFunction += sqls"$coordinateFinderFunction((SELECT ST_SnapToGrid(ll.geometry, $precision) FROM linear_location ll WHERE ll.id = $llId),"
        closingBracket += sqls")"
      }

      val intersectionOfTheFirstTwoLLs: SQLSyntax =
        sqls"""
            $coordinateFinderFunction(
              (SELECT ST_SnapToGrid(ll.geometry, $precision)  FROM linear_location ll  WHERE ll.id = ${llIds(0)}),
              (SELECT ST_SnapToGrid(ll.geometry, $precision)  FROM linear_location ll  WHERE ll.id = ${llIds(1)})
            )
        """
      val query: SQL[Nothing, NoExtractor] =
        sql"""
             SELECT DISTINCT
             ST_X(${intersectionFunction} $intersectionOfTheFirstTwoLLs ${closingBracket}) AS xCoord,
             ST_Y(${intersectionFunction} $intersectionOfTheFirstTwoLLs ${closingBracket}) AS yCoord
          """
      query
    }

    val intersectionQuery = buildCoordinateQuery(sqls"ST_Intersection")
    try {
      val res = runSelectSingleOption(intersectionQuery.map(JunctionCoordinateScalike.apply))
      // Q.queryNA[JunctionCoordinate](intersectionQuery).firstOption
      val point: Option[Point] = res.map(junction => Point(scaleToThreeDigits(junction.xCoord), scaleToThreeDigits(junction.yCoord)))
      point
    }
    catch {
      case e: org.postgresql.util.PSQLException => {
        // Using ClosestPoint as intersection function, when ST_Intersection failed to produce a coordinate.
        val closestPointQuery = buildCoordinateQuery(sqls"ST_ClosestPoint")
        try {
          val res = runSelectSingleOption(closestPointQuery.map(JunctionCoordinateScalike.apply))
          //Q.queryNA[JunctionCoordinate](closestPointQuery).firstOption
          val point: Option[Point] = res.map(junction => Point(scaleToThreeDigits(junction.xCoord), scaleToThreeDigits(junction.yCoord)))
          privateLogger.warn(s"${e.getClass} at fetchCoordinatesForJunction: ${e.getMessage}. IntersectionQuery query failed for llIds ${llIds.toList} - got Point (${point}) from closestPointQuery.")
          point
        }
        catch {
          case e: Exception =>
            privateLogger.warn(s"${e.getClass} at fetchCoordinatesForJunction: ${e.getMessage}. ClosestPointQuery query failed for llIds ${llIds.toList} - nothing else to try.")
            throw e
        }
      }
      case e: Exception =>
        privateLogger.warn(s"${e.getClass} at fetchCoordinatesForJunction: ${e.getMessage}. IntersectionQuery query failed for llIds ${llIds.toList} - don't know what else to try.")
        throw e
    } // catch
  }

  /**
   * Remove Linear Locations (expire them). Don't use more than 1000 linear locations at once.
   *
   * @param linearLocations Seq[LinearLocation]
   * @return Number of updated rows
   */
  def expire(linearLocations: Seq[LinearLocation]): Int = {
    expireByIds(linearLocations.map(_.id).toSet)
  }

  /**
   * Expire Linear Locations. Don't use more than 1000 linear locations at once.
   *
   * @param ids LinearLocation ids
   * @return Number of updated rows
   */
  def expireByIds(ids: Set[Long]): Int = {
    val query =
      sql"""
          UPDATE LINEAR_LOCATION
          SET valid_to = current_timestamp
          WHERE valid_to IS NULL AND id IN ($ids)
        """
    if (ids.isEmpty)
      0
    else
      runUpdateToDb(query)
  }

  def expireByLinkId(linkIds: Set[String]): Int = {
    val query =
      sql"""
          UPDATE linear_location
          SET valid_to = current_timestamp
          WHERE valid_to IS NULL AND link_id IN ($linkIds)
        """
    if (linkIds.isEmpty)
      0
    else
      runUpdateToDb(query)
  }

  def expireByRoadwayNumbers(roadwayNumbers: Set[Long]): Int = {
    val query =
      sql"""
          UPDATE linear_location
          SET valid_to = current_timestamp
          WHERE valid_to IS NULL AND roadway_number IN ($roadwayNumbers)
        """
    if (roadwayNumbers.isEmpty)
      0
    else
      runUpdateToDb(query)
  }

  def update(adjustment: LinearLocationAdjustment,
             createdBy: String = "updateLinearLocation"): Unit = {

    // Expire old row
    val expired: LinearLocation = fetchById(adjustment.linearLocationId).getOrElse(
      throw new IllegalStateException(s"""Failed to update linear location ${adjustment.linearLocationId}. Linear location not found."""))
    expireByIds(Set(adjustment.linearLocationId))

    // Create new row
    val (startM, endM, geometry) = (adjustment.startMeasure, adjustment.endMeasure, adjustment.geometry)
    if (geometry.isEmpty) {
      (startM, endM) match {
        case (Some(s), Some(e)) =>
          create(Seq(expired.copy(id = NewIdValue, linkId = adjustment.linkId, startMValue = s, endMValue = e)), createdBy)
        case (_, Some(e)) =>
          create(Seq(expired.copy(id = NewIdValue, linkId = adjustment.linkId, endMValue = e)), createdBy)
        case (Some(s), _) =>
          create(Seq(expired.copy(id = NewIdValue, linkId = adjustment.linkId, startMValue = s)), createdBy)
        case _ =>
      }
    } else {
      (startM, endM) match {
        case (Some(s), Some(e)) =>
          create(Seq(expired.copy(id = NewIdValue, linkId = adjustment.linkId, startMValue = s, endMValue = e, geometry = geometry)), createdBy)
        case (_, Some(e)) =>
          create(Seq(expired.copy(id = NewIdValue, linkId = adjustment.linkId, endMValue = e, geometry = geometry)), createdBy)
        case (Some(s), _) =>
          create(Seq(expired.copy(id = NewIdValue, linkId = adjustment.linkId, startMValue = s, geometry = geometry)), createdBy)
        case _ =>
      }
    }

  }

  def updateAll(linearLocationAdjustments: Seq[LinearLocationAdjustment],
                createdBy: String = "updateLinearLocation"): Unit = {
    for (adjustment <- linearLocationAdjustments) update(adjustment, createdBy)
  }

  /**
   * Updates the geometry of a linear location by expiring the current one and inserting a new one.
   *
   * @param linearLocationId
   * @param geometry
   * @param createdBy
   */
  def updateGeometry(linearLocationId: Long, geometry: Seq[Point], createdBy: String = "updateGeometry"): Unit = {
    if (geometry.nonEmpty) {
      val expired = fetchById(linearLocationId).getOrElse(
        throw new IllegalStateException(s"""Failed to update linear location $linearLocationId geometry. Linear location not found."""))
      expireByIds(Set(linearLocationId))

      // Check if the side code should be flipped
      val oldDirectionTowardsDigitization = GeometryUtils.isTowardsDigitisation(expired.geometry)
      val newDirectionTowardsDigitization = GeometryUtils.isTowardsDigitisation(geometry)
      val flipSideCode = oldDirectionTowardsDigitization != newDirectionTowardsDigitization

      val sideCode = if (flipSideCode) {
        SideCode.switch(expired.sideCode)
      } else {
        expired.sideCode
      }

      create(Seq(expired.copy(id = NewIdValue, sideCode = sideCode, geometry = geometry)), createdBy)
    }
  }

  def getRoadwayNumbersFromLinearLocation: Seq[Long] = {
    val query =
      sql"""
        SELECT DISTINCT(loc.roadway_number)
        FROM linear_location loc
        ORDER BY loc.roadway_number ASC
      """
    runSelectQuery(query.map(rs => rs.long("roadway_number")))
  }

  def getLinearLocationsByFilter(queryFilter: SQLSyntax): Seq[LinearLocation] = {
    time(logger, "Get linear_locations by filter") {
      val query = sql"$selectFromLinearLocation $queryFilter"
      queryList(query)
    }
  }

  def withLinkIdAndMeasure(linkId: String, startM: Option[Double], endM: Option[Double]): SQLSyntax = {
    val startFilter = startM.map(s => sqls"AND loc.start_measure  <= $s").getOrElse(sqls"")
    val endFilter   = endM.map(e   => sqls"AND loc.end_measure    >= $e").getOrElse(sqls"")

    sqls"""
          WHERE loc.link_id = $linkId
          $startFilter
          $endFilter
          AND loc.valid_to IS NULL
          """
  }

  def withRoadwayNumbers(fromRoadwayNumber: Long, toRoadwayNumber: Long): SQLSyntax = {
    sqls"WHERE loc.roadway_number >= $fromRoadwayNumber AND loc.roadway_number <= $toRoadwayNumber"
  }

  def fetchByBoundingBox(boundingRectangle: BoundingRectangle): Seq[LinearLocation] = {
    time(logger, "Fetch linear locations by bounding box") {
      val extendedBoundingRectangle = BoundingRectangle(boundingRectangle.leftBottom + boundingRectangle.diagonal.scale(.15),
        boundingRectangle.rightTop - boundingRectangle.diagonal.scale(.15))

      val boundingBoxFilter = GeometryDbUtils.boundingBoxFilter(extendedBoundingRectangle, sqls"geometry")

      val query =
        sql"""
            $selectFromLinearLocation
            WHERE $boundingBoxFilter
            AND loc.valid_to IS NULL
          """
      queryList(query)
    }
  }

  def fetchByRoadAddress(roadPart: RoadPart, addressM: Long, track: Option[Long] = None): Seq[LinearLocation] = {
    time(logger, "Fetch linear locations by Road Address") {
      val trackFilter = track.map(t => sqls"""AND rw.TRACK = $t""").getOrElse(sqls"""""")
      val query =
        sql"""
            $selectFromLinearLocation
            JOIN roadway rw ON loc.roadway_number = rw.roadway_number
            WHERE rw.valid_to IS NULL
              AND rw.end_date IS NULL
              AND loc.valid_to IS NULL
              AND rw.road_number = ${roadPart.roadNumber}
              AND rw.road_part_number = ${roadPart.partNumber}
              AND rw.start_addr_m <= $addressM AND rw.end_addr_m >= $addressM
              $trackFilter
            ORDER BY loc.ORDER_NUMBER
          """
      queryList(query)
    }
  }

  def fetchLinearLocationByBoundingBox(boundingRectangle: BoundingRectangle, roadNumberLimits: Seq[(Int, Int)]): Seq[LinearLocation] = {
    time(logger, "Fetch all the linear locations of the matching roadways by bounding box") {
      val extendedBoundingRectangle = BoundingRectangle(boundingRectangle.leftBottom + boundingRectangle.diagonal.scale(.15),
        boundingRectangle.rightTop - boundingRectangle.diagonal.scale(.15))

      val boundingBoxFilter = GeometryDbUtils.boundingBoxFilter(extendedBoundingRectangle, sqls"iloc.geometry")

      val boundingBoxQuery = if (roadNumberLimits.isEmpty) {
        sqls"""
               SELECT roadway_number
               FROM linear_location iloc
               WHERE iloc.valid_to IS NULL
               AND $boundingBoxFilter
               """
      } else {
        val roadNumberLimitsFilter = withRoadNumbersFilter(roadNumberLimits, alias = sqls"ra")
        sqls"""
              SELECT iloc.roadway_number
              FROM linear_location iloc
              INNER JOIN ROADWAY ra ON ra.roadway_number = iloc.roadway_number
              WHERE $roadNumberLimitsFilter AND iloc.valid_to IS NULL
              AND $boundingBoxFilter
              """
      }

      val query =
        sql"""
          $selectFromLinearLocation
          WHERE loc.valid_to IS NULL AND loc.roadway_number IN ($boundingBoxQuery)
          """
      queryList(query)
    }
  }

  def fetchByRoadways(roadwayNumbers: Set[Long]): Seq[LinearLocation] = {
    if (roadwayNumbers.isEmpty) {
      Seq()
    } else {
      val query = if (roadwayNumbers.size > 1000) {
        MassQuery.withIds(roadwayNumbers)({
          idTableName =>
            sql"""
                $selectFromLinearLocation
                JOIN $idTableName i ON i.id = loc.roadway_number
                WHERE loc.valid_to IS NULL
              """
        })
      } else {
        sql"""
              $selectFromLinearLocation
              WHERE loc.valid_to IS NULL and loc.roadway_number IN ($roadwayNumbers)
            """
      }
      queryList(query)
    }
  }

  def fetchCurrentLinearLocationsByEly(ely: Int): Seq[LinearLocation] = {
    val query =
      sql"""
            $selectFromLinearLocation
            WHERE loc.valid_to IS NULL AND loc.roadway_number IN (SELECT roadway_number FROM ROADWAY WHERE ELY = $ely AND valid_to IS NULL AND end_date IS NULL)
         """
    queryList(query)
  }

  def fetchCurrentLinearLocations: Seq[LinearLocation] = {
    val query =
      sql"""
            $selectFromLinearLocation
            WHERE loc.valid_to IS NULL
         """
    queryList(query)
  }

  def fetchActiveLinearLocationsWithRoadAddresses(): Seq[LinearLocation] = {
    val query =
      sql"""
            $selectFromLinearLocation
            WHERE loc.valid_to IS NULL AND loc.roadway_number IN (SELECT roadway_number FROM roadway WHERE valid_to IS NULL AND end_date IS NULL)
         """
    queryList(query)
  }

  def fetchUpdatedSince(sinceDate: DateTime): Seq[LinearLocation] = {
    time(logger, "Fetch linear locations updated since date") {
      val query =
        sql"""
      $selectFromLinearLocation
      WHERE loc.valid_from >= $sinceDate::date
      OR (loc.valid_to IS NOT NULL AND loc.valid_to >= $sinceDate::date)
    """

      runSelectQuery(query.map(LinearLocation.apply))
    }
  }

  /**
   * Sets up the query filters of road numbers
   *
   * @param roadNumbers : Seq[(Int, Int) - list of lowest and highest road numbers
   * @param alias       : String - The alias of the roadway table on the query
   * @param filter      : String - already existing filters
   * @return
   */
  def withRoadNumbersFilter(roadNumbers: Seq[(Int, Int)], alias: SQLSyntax, filter: SQLSyntax = sqls""): SQLSyntax = {
    if (roadNumbers.isEmpty)
      return sqls"""($filter)"""

    val limit = roadNumbers.head
    val filterAdd = sqls"""($alias.road_number >= ${limit._1} and $alias.road_number <= ${limit._2})"""
    if (sqls"".equals(filter))
      withRoadNumbersFilter(roadNumbers.tail, alias, filterAdd)
    else
      withRoadNumbersFilter(roadNumbers.tail, alias,sqls"""$filter OR $filterAdd""")
  }


}

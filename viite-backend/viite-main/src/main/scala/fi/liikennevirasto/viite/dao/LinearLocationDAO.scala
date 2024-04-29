package fi.liikennevirasto.viite.dao

import fi.liikennevirasto.digiroad2.util.LogUtils.time
import fi.liikennevirasto.viite._
import fi.liikennevirasto.viite.process.RoadAddressFiller.LinearLocationAdjustment
import fi.vaylavirasto.viite.dao.{BaseDAO, LinkDAO, Queries, Sequences}
import fi.vaylavirasto.viite.geometry.{BoundingRectangle, GeometryUtils, Point}
import fi.vaylavirasto.viite.model.{CalibrationPointType, LinkGeomSource, RoadPart, SideCode}
import fi.vaylavirasto.viite.postgis.MassQuery.logger
import fi.vaylavirasto.viite.postgis.{MassQuery, PostGISDatabase}
import fi.vaylavirasto.viite.util.DateTimeFormatters.dateOptTimeFormatter
import org.joda.time.DateTime
import slick.driver.JdbcDriver.backend.Database.dynamicSession
import slick.jdbc.{GetResult, PositionedResult, StaticQuery => Q}
import slick.jdbc.StaticQuery.interpolation

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

/**
 * Coordinate point of junction (the blue circle on the map).
 * Used only in /nodes/valid API
 * @param xCoord
 * @param yCoord
 */
case class JunctionCoordinate(xCoord: Double, yCoord: Double)

//TODO Rename all the method names to follow a rule like fetchById instead of have fetchById and QueryById
class LinearLocationDAO extends BaseDAO {

  val selectFromLinearLocation =
    """
       SELECT loc.ID, loc.ROADWAY_NUMBER, loc.ORDER_NUMBER, loc.LINK_ID, loc.START_MEASURE, loc.END_MEASURE, loc.SIDE,
              (SELECT RP.ADDR_M FROM CALIBRATION_POINT CP JOIN ROADWAY_POINT RP ON RP.ID = CP.ROADWAY_POINT_ID WHERE cp.LINK_ID = loc.LINK_ID AND loc.ROADWAY_NUMBER = rp.ROADWAY_NUMBER AND cp.START_END = 0 AND cp.VALID_TO IS NULL) AS cal_start_addr_m,
              (SELECT CP.TYPE FROM   CALIBRATION_POINT CP JOIN ROADWAY_POINT RP ON RP.ID = CP.ROADWAY_POINT_ID WHERE cp.LINK_ID = loc.LINK_ID AND loc.ROADWAY_NUMBER = rp.ROADWAY_NUMBER AND cp.START_END = 0 AND cp.VALID_TO IS NULL) AS cal_start_type,
              (SELECT RP.ADDR_M FROM CALIBRATION_POINT CP JOIN ROADWAY_POINT RP ON RP.ID = CP.ROADWAY_POINT_ID WHERE cp.LINK_ID = loc.LINK_ID AND loc.ROADWAY_NUMBER = rp.ROADWAY_NUMBER AND cp.START_END = 1 AND cp.VALID_TO IS NULL) AS cal_end_addr_m,
              (SELECT CP.TYPE FROM   CALIBRATION_POINT CP JOIN ROADWAY_POINT RP ON RP.ID = CP.ROADWAY_POINT_ID WHERE cp.LINK_ID = loc.LINK_ID AND loc.ROADWAY_NUMBER = rp.ROADWAY_NUMBER AND cp.START_END = 1 AND cp.VALID_TO IS NULL) AS cal_end_type,
              link.SOURCE, link.ADJUSTED_TIMESTAMP, ST_X(ST_StartPoint(loc.geometry)), ST_Y(ST_StartPoint(loc.geometry)), ST_X(ST_EndPoint(loc.geometry)), ST_Y(ST_EndPoint(loc.geometry)), loc.valid_from, loc.valid_to
        FROM LINEAR_LOCATION loc
        JOIN LINK link ON (link.ID = loc.LINK_ID)
    """

  def getNextLinearLocationId: Long = {
    Queries.nextLinearLocationId.as[Long].first
  }

  def create(linearLocations: Iterable[LinearLocation], createdBy: String = "-"): Seq[Long] = {

    val ps = dynamicSession.prepareStatement(
      """insert into LINEAR_LOCATION (id, ROADWAY_NUMBER, order_number, link_id, start_measure, end_measure, SIDE, geometry, created_by)
      values (?, ?, ?, ?, ?, ?, ?, ST_GeomFromText(?, 3067), ?)""".stripMargin)

    // Set ids for the linear locations without one
    val (ready, idLess) = linearLocations.partition(_.id != NewIdValue)
    val newIds = Sequences.fetchLinearLocationIds(idLess.size)
    val createLinearLocations = ready ++ idLess.zip(newIds).map(x =>
      x._1.copy(id = x._2)
    )

    createLinearLocations.foreach {
      location =>
        LinkDAO.createIfEmptyFetch(location.linkId, location.adjustedTimestamp, location.linkGeomSource.value)
        val roadwayNumber = if (location.roadwayNumber == NewIdValue) {
          Sequences.nextRoadwayNumber
        } else {
          location.roadwayNumber
        }
        ps.setLong(1, location.id)
        ps.setLong(2, roadwayNumber)
        ps.setLong(3, location.orderNumber.toLong)
        ps.setString(4, location.linkId)
        ps.setDouble(5, location.startMValue)
        ps.setDouble(6, location.endMValue)
        ps.setInt(7, location.sideCode.value)
        ps.setString(8, PostGISDatabase.createXYZMGeometry(Seq((location.geometry.head, location.startMValue), (location.geometry.last, location.endMValue))))
        ps.setString(9, if (createdBy == null) "-" else createdBy)
        ps.addBatch()
    }
    ps.executeBatch()
    ps.close()
    createLinearLocations.map(_.id).toSeq
  }

  implicit val getLinearLocation: GetResult[LinearLocation] = new GetResult[LinearLocation] {
    def apply(r: PositionedResult): LinearLocation = {
      val id = r.nextLong()
      val roadwayNumber = r.nextLong()
      val orderNumber = r.nextLong()
      val linkId = r.nextString()
      val startMeasure = r.nextDouble()
      val endMeasure = r.nextDouble()
      val sideCode = r.nextInt()
      val calStartAddrM = r.nextLongOption()
      val calStartType = r.nextIntOption()
      val calEndAddrM = r.nextLongOption()
      val calEndType = r.nextIntOption()
      val linkSource = r.nextInt()
      val adjustedTimestamp = r.nextLong()
      val x1 = r.nextDouble()
      val y1 = r.nextDouble()
      val x2 = r.nextDouble()
      val y2 = r.nextDouble()
      val validFrom = r.nextDateOption.map(d => dateOptTimeFormatter.parseDateTime(d.toString))
      val validTo   = r.nextDateOption.map(d => dateOptTimeFormatter.parseDateTime(d.toString))

      val calStartTypeOption: Option[CalibrationPointType] = if (calStartType.isDefined) Some(CalibrationPointType.apply(calStartType.get)) else None

      val calEndTypeOption: Option[CalibrationPointType] = if (calEndType.isDefined) Some(CalibrationPointType.apply(calEndType.get)) else None

      LinearLocation(id, orderNumber, linkId, startMeasure, endMeasure, SideCode.apply(sideCode), adjustedTimestamp, (CalibrationPointReference(calStartAddrM, calStartTypeOption),
          CalibrationPointReference(calEndAddrM, calEndTypeOption)), Seq(Point(x1, y1), Point(x2, y2)), LinkGeomSource.apply(linkSource), roadwayNumber, validFrom, validTo)
    }
  }

  implicit val getJunctionCoordinate: GetResult[JunctionCoordinate] = new GetResult[JunctionCoordinate] {
    def apply(r: PositionedResult): JunctionCoordinate = {
      val xCoord = r.nextDouble()
      val yCoord = r.nextDouble()

      JunctionCoordinate(xCoord, yCoord)
    }
  }

  def fetchLinkIdsInChunk(min: String, max: String): List[String] = { //TODO: Refactor for new linkId
    sql"""
      select distinct(loc.link_id)
      from linear_location loc where loc.link_id between $min and $max order by loc.link_id asc
    """.as[String].list
  }

  private def queryList(query: String): List[LinearLocation] = {
    Q.queryNA[LinearLocation](query).list.groupBy(_.id).map {
      case (_, list) =>
        list.head
    }.toList
  }

  def fetchById(id: Long): Option[LinearLocation] = {
    time(logger, "Fetch linear location by id") {
      val query =
        s"""
          $selectFromLinearLocation
          where loc.id = $id
        """
      Q.queryNA[LinearLocation](query).firstOption
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
      val idString = ids.mkString(", ")
      val where = s""" where loc.id in ($idString)"""

      val validToFilter = if (rejectInvalids)
        " and loc.valid_to is null"
      else
        ""

      val query =
        s"""
          $selectFromLinearLocation
          $where $validToFilter
        """
      queryList(query)
    }
  }

  def fetchByIdMassQuery(ids: Iterable[Long], rejectInvalids: Boolean = true): List[LinearLocation] = {
    time(logger, "Fetch linear locations by id - mass query") {
      MassQuery.withIds(ids)({
        idTableName =>

          val validToFilter = if (rejectInvalids)
            " where loc.valid_to is null"
          else
            ""

          val query =
            s"""
              $selectFromLinearLocation
              join $idTableName i on i.id = loc.id
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
   * Returns the linear locations within the current network (valid_to is null), who
   * have the given <i>linkId</i>, and
   * have their M values fit within <i>filterMvalueMin</i>...<i>filterMvalueMax</i> range.
   * The results are a bit robust filtered: A linear location that overlaps less than [[GeometryUtils.DefaultEpsilon]]
   * the given range at either end, is not included in the results.
   * @todo should it be e.g. 10cm instead of [[GeometryUtils.DefaultEpsilon]]?
   *
   * @param linkId          Filters the returned linear locations to those having this link id.
   * @param filterMvalueMin Filters the returned linear locations to those that enter the range at minimum end
   * @param filterMvalueMax Filters the returned linear locations to those that enter the range at maximum end
   * @return List of Linear locations within given range, ordered by their start measures.
   *         An overlap less than [[GeometryUtils.DefaultEpsilon]] is not seen as fitting the range.
   */
  def fetchByLinkIdAndMValueRange(linkId: String, filterMvalueMin: Double, filterMvalueMax: Double): List[LinearLocation] = {
    time(logger, "Fetch linear locations by link id, and M values") {

      val mustStartBefore = filterMvalueMax - GeometryUtils.DefaultEpsilon // do not count overlap less than epsilon at max value end
      val mustEndAfter    = filterMvalueMin + GeometryUtils.DefaultEpsilon // do not count overlap less than epsilon at min value end

      val query =
        s"""
          $selectFromLinearLocation
          WHERE loc.link_id = '$linkId'
          AND loc.start_measure <= $mustStartBefore -- The start of a valid linear location is before the given max value
          AND loc.end_measure   >= $mustEndAfter    -- The end   of a valid linear location is after  the given min value
          AND loc.valid_to is null
          ORDER BY loc.START_MEASURE
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
      val linkIdsString = linkIds.map(lid => "'" + lid + "'").mkString(", ")
      val idFilter = if (filterIds.nonEmpty)
        s"AND loc.id not in ${filterIds.mkString("(", ", ", ")")}"
      else
        ""
      val query =
        s"""
          $selectFromLinearLocation
          where loc.link_id in ($linkIdsString) $idFilter and loc.valid_to is null
        """
      queryList(query)
    }
  }

  def fetchByLinkIdMassQuery(linkIds: Set[String]): List[LinearLocation] = {
    time(logger, "Fetch linear locations by link id - mass query") {
      MassQuery.withIds(linkIds)({
        idTableName =>
          val query =
            s"""
              $selectFromLinearLocation
              join $idTableName i on i.id = loc.link_id
              where loc.valid_to is null
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
      val roadwayNumbersString = roadwayNumbers.mkString(", ")
      val query =
        s"""
          $selectFromLinearLocation
          where loc.roadway_number in ($roadwayNumbersString) and loc.valid_to is null
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
      val linkIdsString = linkIds.map(l => "'" + l + "'").mkString(", ")
      val query =
        s"""
          $selectFromLinearLocation
          where loc.valid_to is null and loc.ROADWAY_NUMBER in (select ROADWAY_NUMBER from linear_location
            where valid_to is null and link_id in ($linkIdsString))
        """
      queryList(query)
    }
  }

  def fetchRoadwayByLinkIdMassQuery(linkIds: Set[String]): List[LinearLocation] = {
    time(logger, "Fetch all linear locations of a roadway by link id - mass query") {
      MassQuery.withIds(linkIds)({
        idTableName =>
          val query =
            s"""
              $selectFromLinearLocation
              where loc.valid_to is null and loc.ROADWAY_NUMBER in (
                select ROADWAY_NUMBER from linear_location
                join $idTableName i on i.id = link_id
                where valid_to is null)
            """
          queryList(query)
      })
    }
  }

  private def fetch(queryFilter: String => String): Seq[LinearLocation] = {
    val query =
      s"""
        $selectFromLinearLocation
      """
    val filteredQuery = queryFilter(query)
    Q.queryNA[LinearLocation](filteredQuery).iterator.toSeq
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

        var point = Point(0,0)

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

    var intersectionFunction = s""
    var closingBracket = s""
    val precision = MaxDistanceForConnectedLinks

    for (i <- 2 until math.min(llLength, 8)) {
      val llId = llIds(i)
      intersectionFunction += s"ST_Intersection((SELECT st_ReducePrecision(ll.geometry, $precision) FROM linear_location ll WHERE ll.id = $llId),"
      closingBracket += ")"
    }

    val query = {
      s"""
             SELECT DISTINCT
             ST_X(${intersectionFunction}
                  ST_Intersection((SELECT st_ReducePrecision(ll.geometry, $precision)
                       FROM linear_location ll
                       WHERE ll.id = ${llIds(0)}), (SELECT st_ReducePrecision(ll.geometry, $precision)
                       FROM linear_location ll
                       WHERE ll.id = ${llIds(1)})${closingBracket})) AS xCoord,
             ST_Y(${intersectionFunction}
                  ST_Intersection((SELECT st_ReducePrecision(ll.geometry, $precision)
                       FROM linear_location ll
                       WHERE ll.id = ${llIds(0)}), (SELECT st_ReducePrecision(ll.geometry, $precision)
                       FROM linear_location ll
                       WHERE ll.id = ${llIds(1)})${closingBracket})) AS yCoord
          """
    }
    val res = Q.queryNA[JunctionCoordinate](query).firstOption

    val point: Option[Point] = res.map(junction => Point(junction.xCoord, junction.yCoord))
    point
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
      s"""
        Update LINEAR_LOCATION Set valid_to = current_timestamp where valid_to IS NULL and id in (${ids.mkString(",")})
      """
    if (ids.isEmpty)
      0
    else
      runUpdateToDb(query)
  }

  def expireByLinkId(linkIds: Set[String]): Int = {
    val query =
      s"""
        Update LINEAR_LOCATION Set valid_to = current_timestamp Where valid_to IS NULL and link_id in (${linkIds.map(l => "'" + l + "'").mkString(",")})
      """
    if (linkIds.isEmpty)
      0
    else
      runUpdateToDb(query)
  }

  def expireByRoadwayNumbers(roadwayNumbers: Set[Long]): Int = {
    val query =
      s"""
        Update LINEAR_LOCATION Set valid_to = current_timestamp Where valid_to IS NULL and roadway_number in (${roadwayNumbers.mkString(",")})
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
    sql"""
      select distinct(loc.ROADWAY_NUMBER)
      from linear_location loc order by loc.ROADWAY_NUMBER asc
    """.as[Long].list
  }

  def getLinearLocationsByFilter(queryFilter: String => String): Seq[LinearLocation] = {
    time(logger, "Get linear_locations by filter") {
      queryList(queryFilter(s"$selectFromLinearLocation"))
    }
  }

  def withLinkIdAndMeasure(linkId: String, startM: Option[Double], endM: Option[Double])(query: String): String = {
    val startFilter = startM match {
      case Some(s) => s" AND loc.start_measure <= $s"
      case None => ""
    }
    val endFilter = endM match {
      case Some(e) => s" AND loc.end_measure >= $e"
      case None => ""
    }

    query + s" WHERE loc.link_id = '$linkId' $startFilter $endFilter" + withValidityCheck
  }

  def withRoadwayNumbers(fromRoadwayNumber: Long, toRoadwayNumber: Long)(query: String): String = {
    query + s" WHERE loc.ROADWAY_NUMBER >= $fromRoadwayNumber AND loc.ROADWAY_NUMBER <= $toRoadwayNumber"
  }

  def withValidityCheck(): String = {
    s" AND loc.valid_to IS NULL "
  }

  def fetchByBoundingBox(boundingRectangle: BoundingRectangle): Seq[LinearLocation] = {
    time(logger, "Fetch linear locations by bounding box") {
      val extendedBoundingRectangle = BoundingRectangle(boundingRectangle.leftBottom + boundingRectangle.diagonal.scale(.15),
        boundingRectangle.rightTop - boundingRectangle.diagonal.scale(.15))

      val boundingBoxFilter = PostGISDatabase.boundingBoxFilter(extendedBoundingRectangle, "geometry")

      val query =
        s"""
          $selectFromLinearLocation
          where $boundingBoxFilter and loc.valid_to is null
        """
      queryList(query)
    }
  }

  def fetchByRoadAddress(roadPart: RoadPart, addressM: Long, track: Option[Long] = None): Seq[LinearLocation] = {
    time(logger, "Fetch linear locations by Road Address") {
      val trackFilter = track.map(t => s"""AND rw.TRACK = $t""").getOrElse(s"""""")
      val query =
        s"""
          $selectFromLinearLocation
          JOIN ROADWAY rw ON loc.ROADWAY_NUMBER = rw.ROADWAY_NUMBER
          WHERE rw.VALID_TO IS NULL AND rw.END_DATE IS NULL AND loc.VALID_TO IS NULL AND
          rw.ROAD_NUMBER = ${roadPart.roadNumber} AND rw.ROAD_PART_NUMBER = ${roadPart.partNumber}
          AND rw.START_ADDR_M <= $addressM AND rw.END_ADDR_M >= $addressM
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

      val boundingBoxFilter = PostGISDatabase.boundingBoxFilter(extendedBoundingRectangle, "iloc.geometry")

      val boundingBoxQuery = if (roadNumberLimits.isEmpty) {
        s"""select ROADWAY_NUMBER from linear_location iloc
           where iloc.valid_to is null and $boundingBoxFilter"""
      } else {
        val roadNumberLimitsFilter = withRoadNumbersFilter(roadNumberLimits, alias = "ra")
        s"""select iloc.ROADWAY_NUMBER
            from linear_location iloc
            inner join ROADWAY ra on ra.ROADWAY_NUMBER = iloc.ROADWAY_NUMBER
            where $roadNumberLimitsFilter and iloc.valid_to is null and $boundingBoxFilter"""
      }

      val query =
        s"""
        $selectFromLinearLocation
        where loc.valid_to is null and loc.ROADWAY_NUMBER in ($boundingBoxQuery)
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
            s"""
              $selectFromLinearLocation
              join $idTableName i on i.id = loc.ROADWAY_NUMBER
              where loc.valid_to is null
            """.stripMargin
        })
      } else {
        s"""
            $selectFromLinearLocation
            where loc.valid_to is null and loc.ROADWAY_NUMBER in (${roadwayNumbers.mkString(", ")})
          """
      }
      queryList(query)
    }
  }

  def fetchCurrentLinearLocationsByEly(ely: Int): Seq[LinearLocation] = {
    val query =
      s"""
          $selectFromLinearLocation
          WHERE loc.VALID_TO IS NULL AND loc.ROADWAY_NUMBER IN (SELECT ROADWAY_NUMBER FROM ROADWAY WHERE ELY = $ely AND VALID_TO IS NULL AND END_DATE IS NULL)
       """
    queryList(query)
  }

  def fetchCurrentLinearLocations: Seq[LinearLocation] = {
    val query =
      s"""
          $selectFromLinearLocation
          WHERE loc.VALID_TO IS NULL
       """
    queryList(query)
  }

  def fetchActiveLinearLocationsWithRoadAddresses(): Seq[LinearLocation] = {
    val query =
      s"""
          $selectFromLinearLocation
          WHERE loc.VALID_TO IS NULL AND loc.ROADWAY_NUMBER IN (SELECT ROADWAY_NUMBER FROM ROADWAY WHERE VALID_TO IS NULL AND END_DATE IS NULL)
       """
    queryList(query)
  }

  def fetchCurrentLinearLocationsByMunicipality(municipality: Int): Seq[LinearLocation] = {
    val query =
      s"""
          $selectFromLinearLocation
          WHERE loc.VALID_TO IS NULL AND loc.ROADWAY_NUMBER IN ( SELECT ROADWAY_NUMBER FROM ROADWAY
            WHERE ELY = (SELECT ELY_NRO FROM MUNICIPALITY WHERE ID = $municipality) AND VALID_TO IS NULL AND END_DATE IS NULL)
       """
    queryList(query)
  }

  def fetchUpdatedSince(sinceDate: DateTime): Seq[LinearLocation] = {
    time(logger, "Fetch linear locations updated since date") {
      fetch(withUpdatedSince(sinceDate))
    }
  }

  private def withUpdatedSince(sinceDate: DateTime)(query: String): String = {
    val sinceString = sinceDate.toString("yyyy-MM-dd")
    s"""$query
        where loc.valid_from >= to_date('$sinceString', 'YYYY-MM-DD')
          OR (loc.valid_to IS NOT NULL AND loc.valid_to >= to_date('$sinceString', 'YYYY-MM-DD'))"""
  }

  /**
    * Sets up the query filters of road numbers
    *
    * @param roadNumbers : Seq[(Int, Int) - list of lowest and highest road numbers
    * @param alias       : String - The alias of the roadway table on the query
    * @param filter      : String - already existing filters
    * @return
    */
  def withRoadNumbersFilter(roadNumbers: Seq[(Int, Int)], alias: String, filter: String = ""): String = {
    if (roadNumbers.isEmpty)
      return s"""($filter)"""

    val limit = roadNumbers.head
    val filterAdd = s"""($alias.road_number >= ${limit._1} and $alias.road_number <= ${limit._2})"""
    if ("".equals(filter))
      withRoadNumbersFilter(roadNumbers.tail, alias, filterAdd)
    else
      withRoadNumbersFilter(roadNumbers.tail, alias,s"""$filter OR $filterAdd""")
  }

}

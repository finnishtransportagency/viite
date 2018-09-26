package fi.liikennevirasto.viite.dao

import java.sql.{Timestamp, Types}

import fi.liikennevirasto.digiroad2.asset.SideCode.AgainstDigitizing
import fi.liikennevirasto.digiroad2.asset.{BoundingRectangle, LinkGeomSource, SideCode}
import fi.liikennevirasto.digiroad2.dao.{Queries, Sequences}
import fi.liikennevirasto.digiroad2.oracle.{MassQuery, OracleDatabase}
import fi.liikennevirasto.digiroad2.util.LogUtils.time
import fi.liikennevirasto.digiroad2.{GeometryUtils, Point}
import fi.liikennevirasto.viite._
import fi.liikennevirasto.viite.dao.FloatingReason.NoFloating
import fi.liikennevirasto.viite.process.RoadAddressFiller.LinearLocationAdjustment
import org.joda.time.DateTime
import org.joda.time.format.{DateTimeFormatter, ISODateTimeFormat}
import org.slf4j.LoggerFactory
import slick.driver.JdbcDriver.backend.Database.dynamicSession
import slick.jdbc.StaticQuery.interpolation
import slick.jdbc.{GetResult, PositionedResult, StaticQuery => Q}

sealed trait FloatingReason {
  def value: Int

  def isFloating: Boolean = value != 0
}

object FloatingReason {
  val values = Set(NoFloating, ApplyChanges, GeometryChanged, NewAddressGiven, GapInGeometry, ManualFloating)

  def apply(intValue: Long): FloatingReason = {
    values.find(_.value == intValue).getOrElse(NoFloating)
  }

  case object NoFloating extends FloatingReason {
    def value = 0
  }

  case object ApplyChanges extends FloatingReason {
    def value = 1
  }

  case object GeometryChanged extends FloatingReason {
    def value = 2
  }

  case object NewAddressGiven extends FloatingReason {
    def value = 3
  }

  case object GapInGeometry extends FloatingReason {
    def value = 4
  }

  case object ManualFloating extends FloatingReason {
    def value = 5
  }

  case object SplittingTool extends FloatingReason {
    def value = 6
  }

  case object ProjectToRoadAddress extends FloatingReason {
    def value = 7
  }

}

trait BaseLinearLocation {
  def id: Long

  def orderNumber: Long

  def linkId: Long

  def startMValue: Double

  def endMValue: Double

  def sideCode: SideCode

  def adjustedTimestamp: Long

  def calibrationPoints: (Option[Long], Option[Long])

  def floating: FloatingReason

  def geometry: Seq[Point]

  def linkGeomSource: LinkGeomSource

  def roadwayNumber: Long

  def validFrom: Option[DateTime]

  def validTo: Option[DateTime]

  def isFloating: Boolean = floating.isFloating

  def copyWithGeometry(newGeometry: Seq[Point]): BaseLinearLocation

  def getCalibrationCode: CalibrationCode = {
    calibrationPoints match {
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
      case AgainstDigitizing => geometry.head
      case _ => geometry.last
    }

    val nextStartPoint = ra2.sideCode match {
      case AgainstDigitizing => ra2.geometry.last
      case _ => ra2.geometry.head
    }

    GeometryUtils.areAdjacent(nextStartPoint, currEndPoint, fi.liikennevirasto.viite.MaxDistanceForConnectedLinks)
  }
}

// Note: Geometry on linear location is not directed: it isn't guaranteed to have a direction of digitization or road addressing
case class LinearLocation(id: Long, orderNumber: Long, linkId: Long, startMValue: Double, endMValue: Double, sideCode: SideCode,
                          adjustedTimestamp: Long, calibrationPoints: (Option[Long], Option[Long]) = (None, None),
                          floating: FloatingReason = NoFloating, geometry: Seq[Point], linkGeomSource: LinkGeomSource,
                          roadwayNumber: Long, validFrom: Option[DateTime] = None, validTo: Option[DateTime] = None) extends BaseLinearLocation {

  val startCalibrationPoint: Option[Long] = calibrationPoints._1
  val endCalibrationPoint: Option[Long] = calibrationPoints._2

  def isExpire(): Boolean = {
    validFrom.getOrElse(throw new IllegalStateException("The valid from should be set before call isExpire method")).isAfterNow ||
      validTo.exists(vt => vt.isEqualNow || vt.isBeforeNow)
  }

  def copyWithGeometry(newGeometry: Seq[Point]) = {
    this.copy(geometry = newGeometry)
  }

  // TODO
  /*  def toProjectLinkCalibrationPoints(): (Option[ProjectLinkCalibrationPoint], Option[ProjectLinkCalibrationPoint]) = {
      val calibrationPointSource = if (id == noRoadwayId || id == NewRoadAddress) ProjectLinkSource else RoadAddressSource
      calibrationPoints match {
        case (None, None) => (Option.empty[ProjectLinkCalibrationPoint], Option.empty[ProjectLinkCalibrationPoint])
        case (None, Some(cp1)) => (Option.empty[ProjectLinkCalibrationPoint], Option(ProjectLinkCalibrationPoint(cp1.linkId, cp1.segmentMValue, cp1.addressMValue, calibrationPointSource)))
        case (Some(cp1), None) => (Option(ProjectLinkCalibrationPoint(cp1.linkId, cp1.segmentMValue, cp1.addressMValue, calibrationPointSource)) , Option.empty[ProjectLinkCalibrationPoint])
        case (Some(cp1), Some(cp2)) => (Option(ProjectLinkCalibrationPoint(cp1.linkId, cp1.segmentMValue, cp1.addressMValue, calibrationPointSource)), Option(ProjectLinkCalibrationPoint(cp2.linkId, cp2.segmentMValue, cp2.addressMValue, calibrationPointSource)))
      }
    }*/
}

class LinearLocationDAO {

  private def logger = LoggerFactory.getLogger(getClass)

  val formatter: DateTimeFormatter = ISODateTimeFormat.dateOptionalTimeParser()

  // TODO If not used, remove
  def dateTimeParse(string: String): DateTime = {
    formatter.parseDateTime(string)
  }

  val dateFormatter: DateTimeFormatter = ISODateTimeFormat.basicDate()

  val selectFromLinearLocation =
    """
    select loc.id, loc.ROADWAY_NUMBER, loc.order_number, loc.link_id, loc.start_measure, loc.end_measure, loc.side_code,
      loc.cal_start_addr_m, loc.cal_end_addr_m, loc.link_source, loc.adjusted_timestamp, loc.floating, t.X, t.Y, t2.X, t2.Y,
      loc.valid_from, loc.valid_to
    from LINEAR_LOCATION loc cross join
      TABLE(SDO_UTIL.GETVERTICES(loc.geometry)) t cross join
      TABLE(SDO_UTIL.GETVERTICES(loc.geometry)) t2
    """

  // TODO If not used, remove
  def optDateTimeParse(string: String): Option[DateTime] = {
    try {
      if (string == null || string == "")
        None
      else
        Some(DateTime.parse(string, formatter))
    } catch {
      case ex: Exception => None
    }
  }

  def getNextLinearLocationId: Long = {
    Queries.nextLinearLocationId.as[Long].first
  }

  def create(linearLocations: Iterable[LinearLocation], createdBy: String = "-"): Seq[Long] = {
    val ps = dynamicSession.prepareStatement(
      """insert into LINEAR_LOCATION (id, ROADWAY_NUMBER, order_number, link_id, start_measure, end_measure, side_code,
        cal_start_addr_m, cal_end_addr_m, link_source, adjusted_timestamp, floating, geometry, created_by)
        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
        MDSYS.SDO_GEOMETRY(4002, 3067, NULL, MDSYS.SDO_ELEM_INFO_ARRAY(1,2,1), MDSYS.SDO_ORDINATE_ARRAY(?,?,0.0,?,?,?,0.0,?)), ?)""")

    // Set ids for the linear locations without one
    val (ready, idLess) = linearLocations.partition(_.id != NewLinearLocation)
    val newIds = Sequences.fetchLinearLocationIds(idLess.size)
    val createLinearLocations = ready ++ idLess.zip(newIds).map(x =>
      x._1.copy(id = x._2)
    )

    createLinearLocations.foreach {
      case (location) =>
        val roadwayNumber = if (location.roadwayNumber == NewRoadwayNumber) {
          Sequences.nextRoadwayNumber
        } else {
          location.roadwayNumber
        }
        ps.setLong(1, location.id)
        ps.setLong(2, roadwayNumber)
        ps.setLong(3, location.orderNumber)
        ps.setLong(4, location.linkId)
        ps.setDouble(5, location.startMValue)
        ps.setDouble(6, location.endMValue)
        ps.setInt(7, location.sideCode.value)
        location.startCalibrationPoint match {
          case Some(value) => ps.setLong(8, value)
          case None => ps.setNull(8, Types.BIGINT)
        }
        location.endCalibrationPoint match {
          case Some(value) => ps.setLong(9, value)
          case None => ps.setNull(9, Types.BIGINT)
        }
        ps.setInt(10, location.linkGeomSource.value)
        ps.setLong(11, location.adjustedTimestamp)
        ps.setInt(12, location.floating.value)
        val (p1, p2) = (location.geometry.head, location.geometry.last)
        ps.setDouble(13, p1.x)
        ps.setDouble(14, p1.y)
        ps.setDouble(15, location.startMValue)
        ps.setDouble(16, p2.x)
        ps.setDouble(17, p2.y)
        ps.setDouble(18, location.endMValue)
        ps.setString(19, if (createdBy == null) "-" else createdBy)
        ps.addBatch()
    }
    ps.executeBatch()
    ps.close()
    createLinearLocations.map(_.id).toSeq
  }

  def lockLinearLocationWriting: Unit = {
    sqlu"""LOCK TABLE linear_location IN SHARE MODE""".execute
  }

  implicit val getLinearLocation: GetResult[LinearLocation] = new GetResult[LinearLocation] {
    def apply(r: PositionedResult) = {
      val id = r.nextLong()
      val roadwayNumber = r.nextLong()
      val orderNumber = r.nextLong()
      val linkId = r.nextLong()
      val startMeasure = r.nextDouble()
      val endMeasure = r.nextDouble()
      val sideCode = r.nextInt()
      val calStartM = r.nextLongOption()
      val calEndM = r.nextLongOption()
      val linkSource = r.nextInt()
      val adjustedTimestamp = r.nextLong()
      val floating = r.nextInt()
      val x1 = r.nextDouble()
      val y1 = r.nextDouble()
      val x2 = r.nextDouble()
      val y2 = r.nextDouble()
      val validFrom = r.nextDateOption.map(d => formatter.parseDateTime(d.toString))
      val validTo = r.nextDateOption.map(d => formatter.parseDateTime(d.toString))

      LinearLocation(id, orderNumber, linkId, startMeasure, endMeasure, SideCode.apply(sideCode), adjustedTimestamp,
        (calStartM, calEndM), FloatingReason.apply(floating), Seq(Point(x1, y1), Point(x2, y2)),
        LinkGeomSource.apply(linkSource), roadwayNumber, validFrom, validTo)
    }
  }

  def fetchLinkIdsInChunk(min: Long, max: Long): List[Long] = {
    sql"""
      select distinct(loc.link_id)
      from linear_location loc where loc.link_id between $min and $max order by loc.link_id asc
    """.as[Long].list
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
          where loc.id = $id and t.id < t2.id
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
          $where and t.id < t2.id $validToFilter
        """
      queryList(query)
    }
  }

  def fetchByIdMassQuery(ids: Set[Long], includeFloating: Boolean = false, rejectInvalids: Boolean = true): List[LinearLocation] = {
    time(logger, "Fetch linear locations by id - mass query") {
      MassQuery.withIds(ids) {
        idTableName =>

          val floating = if (!includeFloating)
            "AND loc.floating = 0"
          else
            ""

          val validToFilter = if (rejectInvalids)
            " and loc.valid_to is null"
          else
            ""

          val query =
            s"""
              $selectFromLinearLocation
              join $idTableName i on i.id = loc.id
              where t.id < t2.id $floating $validToFilter
            """
          queryList(query)
      }
    }
  }

  def queryByIdMassQuery(ids: Set[Long], rejectInvalids: Boolean = true): List[LinearLocation] = {
    fetchByIdMassQuery(ids, includeFloating = true, rejectInvalids)
  }

  def fetchByLinkId(linkIds: Set[Long], includeFloating: Boolean = false, filterIds: Set[Long] = Set()): List[LinearLocation] = {
    time(logger, "Fetch linear locations by link id") {
      if (linkIds.isEmpty) {
        return List()
      }
      if (linkIds.size > 1000 || filterIds.size > 1000) {
        return fetchByLinkIdMassQuery(linkIds, includeFloating).filterNot(ra => filterIds.contains(ra.id))
      }
      val linkIdsString = linkIds.mkString(", ")
      val floating = if (!includeFloating)
        "AND loc.floating = 0"
      else
        ""
      val idFilter = if (filterIds.nonEmpty)
        s"AND loc.id not in ${filterIds.mkString("(", ", ", ")")}"
      else
        ""
      val query =
        s"""
          $selectFromLinearLocation
          where loc.link_id in ($linkIdsString) $floating $idFilter and t.id < t2.id and loc.valid_to is null
        """
      queryList(query)
    }
  }

  def fetchByLinkIdMassQuery(linkIds: Set[Long], includeFloating: Boolean = false): List[LinearLocation] = {
    time(logger, "Fetch linear locations by link id - mass query") {
      MassQuery.withIds(linkIds) {
        idTableName =>
          val floating = if (!includeFloating)
            "AND loc.floating = 0"
          else
            ""
          val query =
            s"""
              $selectFromLinearLocation
              join $idTableName i on i.id = loc.link_id
              where t.id < t2.id $floating and loc.valid_to is null
            """
          queryList(query)
      }
    }
  }

  def fetchRoadwayByLinkId(linkIds: Set[Long]): List[LinearLocation] = {
    time(logger, "Fetch all linear locations of a roadway by link id") {
      if (linkIds.isEmpty) {
        return List()
      }
      if (linkIds.size > 1000) {
        return fetchRoadwayByLinkIdMassQuery(linkIds)
      }
      val linkIdsString = linkIds.mkString(", ")
      val query =
        s"""
          $selectFromLinearLocation
          where t.id < t2.id and valid_to is null and loc.ROADWAY_NUMBER in (select ROADWAY_NUMBER from linear_location
            where valid_to is null and link_id in ($linkIdsString))
        """
      queryList(query)
    }
  }

  def fetchRoadwayByLinkIdMassQuery(linkIds: Set[Long]): List[LinearLocation] = {
    time(logger, "Fetch all linear locations of a roadway by link id - mass query") {
      MassQuery.withIds(linkIds) {
        idTableName =>
          val query =
            s"""
              $selectFromLinearLocation

              where t.id < t2.id and loc.valid_to is null and loc.ROADWAY_NUMBER in (
                select ROADWAY_NUMBER from linear_location
                join $idTableName i on i.id = link_id
                where valid_to is null)
            """
          queryList(query)
      }
    }
  }

  def queryFloatingByLinkId(linkIds: Set[Long]): List[LinearLocation] = {
    time(logger, "Fetch floating linear locations by link ids") {
      if (linkIds.isEmpty) {
        return List()
      }
      if (linkIds.size > 1000) {
        return queryFloatingByLinkIdMassQuery(linkIds)
      }
      val linkIdString = linkIds.mkString(", ")
      val where = s""" where loc.link_id in ($linkIdString)"""
      val query =
        s"""
          $selectFromLinearLocation
          $where AND loc.floating > 0 and t.id < t2.id and loc.valid_to is null
        """
      queryList(query)
    }
  }

  def queryFloatingByLinkIdMassQuery(linkIds: Set[Long]): List[LinearLocation] = {
    time(logger, "Fetch floating linear locations by link ids - mass query") {
      MassQuery.withIds(linkIds) {
        idTableName =>
          val query =
            s"""
              $selectFromLinearLocation
              join $idTableName i on i.id = loc.link_id
              where loc.floating > 0 and t.id < t2.id and loc.valid_to is null
            """
          queryList(query)
      }
    }
  }

  def fetchAllFloatingLinearLocations: List[LinearLocation] = {
    time(logger, "Fetch all floating linear locations") {
      val query =
        s"""
          $selectFromLinearLocation
          where t.id < t2.id and loc.floating > 0 and loc.valid_to is null
          order by loc.ROADWAY_NUMBER, loc.order_number
        """
      queryList(query)
    }
  }

  // TODO If not used, should be removed
  def toTimeStamp(dateTime: Option[DateTime]): Option[Timestamp] = {
    dateTime.map(dt => new Timestamp(dt.getMillis))
  }

  /**
    * Remove Linear Locations (expire them). Don't use more than 1000 linear locations at once.
    *
    * @param linearLocations Seq[LinearLocation]
    * @return Number of updated rows
    */
  def remove(linearLocations: Seq[LinearLocation]): Int = {
    expireById(linearLocations.map(_.id).toSet)
  }

  /**
    * Expire Linear Locations. Don't use more than 1000 linear locations at once.
    *
    * @param ids
    * @return Number of updated rows
    */
  def expireById(ids: Set[Long]): Int = {
    val query =
      s"""
        Update LINEAR_LOCATION Set valid_to = sysdate where valid_to IS NULL and id in (${ids.mkString(",")})
      """
    if (ids.isEmpty)
      0
    else
      Q.updateNA(query).first
  }

  def expireByLinkId(linkIds: Set[Long]): Int = {
    val query =
      s"""
        Update LINEAR_LOCATION Set valid_to = sysdate Where valid_to IS NULL and link_id in (${linkIds.mkString(",")})
      """
    if (linkIds.isEmpty)
      0
    else
      Q.updateNA(query).first
  }

  def setLinearLocationFloatingReason(id: Long, geometry: Option[Seq[Point]], floatingReason: FloatingReason,
                                      createdBy: String = "setLinearLocationFloatingReason"): Unit = {

    // Expire old row
    val expired: LinearLocation = fetchById(id).getOrElse(
      throw new IllegalStateException(s"""Failed to set linear location $id floating reason. Linear location not found."""))
    expireById(Set(id))

    // Create new row
    create(Seq(if (geometry.nonEmpty) {
      expired.copy(id = NewLinearLocation, geometry = geometry.get, floating = floatingReason)
    } else {
      expired.copy(id = NewLinearLocation, floating = floatingReason)
    }), createdBy)

  }

  def updateLinearLocation(linearLocationAdjustment: LinearLocationAdjustment,
                           createdBy: String = "updateLinearLocation"): Unit = {

    // Expire old row
    val expired: LinearLocation = fetchById(linearLocationAdjustment.linearLocationId).getOrElse(
      throw new IllegalStateException(s"""Failed to update linear location ${linearLocationAdjustment.linearLocationId}. Linear location not found."""))
    expireById(Set(linearLocationAdjustment.linearLocationId))

    // Create new row
    val (startM, endM) = (linearLocationAdjustment.startMeasure, linearLocationAdjustment.endMeasure)
    (startM, endM) match {
      case (Some(s), Some(e)) =>
        create(Seq(expired.copy(id = NewLinearLocation, linkId = linearLocationAdjustment.linkId, startMValue = s, endMValue = e)), createdBy)
      case (_, Some(e)) =>
        create(Seq(expired.copy(id = NewLinearLocation, linkId = linearLocationAdjustment.linkId, endMValue = e)), createdBy)
      case (Some(s), _) =>
        create(Seq(expired.copy(id = NewLinearLocation, linkId = linearLocationAdjustment.linkId, startMValue = s)), createdBy)
      case _ =>
    }

  }

  // Use this only in the initial import
  def updateLinkSource(id: Long, linkSource: LinkGeomSource): Boolean = {
    sqlu"""
      UPDATE LINEAR_LOCATION SET link_source = ${linkSource.value} WHERE id = $id
    """.execute
    true
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
      expireById(Set(linearLocationId))

      // Check if the side code should be flipped
      val oldDirectionTowardsDigitization = GeometryUtils.isTowardsDigitisation(expired.geometry)
      val newDirectionTowardsDigitization = GeometryUtils.isTowardsDigitisation(geometry)
      val flipSideCode = oldDirectionTowardsDigitization != newDirectionTowardsDigitization

      val sideCode = if (flipSideCode) {
        SideCode.switch(expired.sideCode)
      } else {
        expired.sideCode
      }

      create(Seq(expired.copy(id = NewLinearLocation, geometry = geometry, sideCode = sideCode)), createdBy)
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

  def withLinkIdAndMeasure(linkId: Long, startM: Option[Double], endM: Option[Double])(query: String): String = {
    val startFilter = startM match {
      case Some(s) => s" AND loc.start_Measure <= $s"
      case None => ""
    }
    val endFilter = endM match {
      case Some(e) => s" AND loc.end_Measure >= $endM"
      case None => ""
    }

    query + s" WHERE loc.link_id = $linkId $startFilter $endFilter AND floating = 0" + withValidityCheck
  }

  def withRoadwayNumbers(fromRoadwayNumber: Long, toRoadwayNumber: Long)(query: String): String = {
    query + s" WHERE loc.ROADWAY_NUMBER >= $fromRoadwayNumber AND loc.ROADWAY_NUMBER <= $toRoadwayNumber"
  }

  def withValidityCheck(): String = {
    s" AND loc.valid_to IS NULL "
  }

  def fetchByBoundingBox(boundingRectangle: BoundingRectangle): Seq[LinearLocation] = {
    time(logger, "Fetch road addresses by bounding box") {
      val extendedBoundingRectangle = BoundingRectangle(boundingRectangle.leftBottom + boundingRectangle.diagonal.scale(.15),
        boundingRectangle.rightTop - boundingRectangle.diagonal.scale(.15))

      val boundingBoxFilter = OracleDatabase.boundingBoxFilter(extendedBoundingRectangle, "geometry")

      val query =
        s"""
          $selectFromLinearLocation
          where $boundingBoxFilter and valid_to is null
        """
      queryList(query)
    }
  }

  def fetchRoadwayByBoundingBox(boundingRectangle: BoundingRectangle, roadNumberLimits: Seq[(Int, Int)]): Seq[LinearLocation] = {
    time(logger, "Fetch road addresses by bounding box") {
      val extendedBoundingRectangle = BoundingRectangle(boundingRectangle.leftBottom + boundingRectangle.diagonal.scale(.15),
        boundingRectangle.rightTop - boundingRectangle.diagonal.scale(.15))

      val boundingBoxFilter = OracleDatabase.boundingBoxFilter(extendedBoundingRectangle, "iloc.geometry")

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
        where valid_to is null and ROADWAY_NUMBER in ($boundingBoxQuery)
        """
      queryList(query)
    }
  }

  def fetchByRoadways(roadwayNumbers: Set[Long]): Seq[LinearLocation] = {
    if (roadwayNumbers.isEmpty) {
      Seq()
    } else {
      val query =
        s"""
          $selectFromLinearLocation
          where valid_to is null and ROADWAY_NUMBER in (${roadwayNumbers.mkString(", ")})
        """
      queryList(query)
    }
  }

  private def withRoadNumbersFilter(roadNumbers: Seq[(Int, Int)], alias: String, filter: String = ""): String = {
    if (roadNumbers.isEmpty)
      return s"""($filter)"""

    val limit = roadNumbers.head
    val filterAdd = s"""($alias.road_number >= ${limit._1} and $alias.road_number <= ${limit._2})"""
    if (filter == "")
      withRoadNumbersFilter(roadNumbers.tail, alias, filterAdd)
    else
      withRoadNumbersFilter(roadNumbers.tail, alias,s"""$filter OR $filterAdd""")
  }
}

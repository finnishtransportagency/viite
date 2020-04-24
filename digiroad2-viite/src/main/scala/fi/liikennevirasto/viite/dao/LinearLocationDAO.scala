package fi.liikennevirasto.viite.dao

import java.sql.Timestamp

import fi.liikennevirasto.GeometryUtils
import fi.liikennevirasto.digiroad2.Point
import fi.liikennevirasto.digiroad2.asset.SideCode.AgainstDigitizing
import fi.liikennevirasto.digiroad2.asset.{BoundingRectangle, LinkGeomSource, SideCode}
import fi.liikennevirasto.digiroad2.dao.{Queries, Sequences}
import fi.liikennevirasto.digiroad2.oracle.{MassQuery, OracleDatabase}
import fi.liikennevirasto.digiroad2.util.LogUtils.time
import fi.liikennevirasto.viite._
import fi.liikennevirasto.viite.dao.CalibrationPointDAO.CalibrationPointType
import fi.liikennevirasto.viite.dao.CalibrationPointDAO.CalibrationPointType.{JunctionPointCP, RoadAddressCP}
import fi.liikennevirasto.viite.process.RoadAddressFiller.LinearLocationAdjustment
import org.joda.time.DateTime
import org.joda.time.format.{DateTimeFormatter, ISODateTimeFormat}
import org.slf4j.LoggerFactory
import slick.driver.JdbcDriver.backend.Database.dynamicSession
import slick.jdbc.StaticQuery.interpolation
import slick.jdbc.{GetResult, PositionedResult, StaticQuery => Q}

trait BaseLinearLocation {
  def id: Long

  def orderNumber: Double

  def linkId: Long

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
      case AgainstDigitizing => geometry.head
      case _ => geometry.last
    }

    val nextStartPoint = ra2.sideCode match {
      case AgainstDigitizing => ra2.geometry.last
      case _ => ra2.geometry.head
    }

    GeometryUtils.areAdjacent(nextStartPoint, currEndPoint, fi.liikennevirasto.viite.MaxDistanceForConnectedLinks)
  }

  lazy val startCalibrationPoint: CalibrationPointReference = calibrationPoints._1
  lazy val endCalibrationPoint: CalibrationPointReference = calibrationPoints._2

  def startCalibrationPointType: CalibrationPointType = if (startCalibrationPoint.typeCode.isDefined) startCalibrationPoint.typeCode.get else CalibrationPointType.NoCP
  def endCalibrationPointType: CalibrationPointType = if (endCalibrationPoint.typeCode.isDefined) endCalibrationPoint.typeCode.get else CalibrationPointType.NoCP
}

case class CalibrationPointReference(addrM: Option[Long], typeCode: Option[CalibrationPointType] = Option.empty) {
  def isEmpty: Boolean = addrM.isEmpty
  def isDefined: Boolean = addrM.isDefined

  def isRoadAddressCP(): Boolean = {
    this.typeCode match {
      case Some(t) if t.equals(RoadAddressCP) => true
      case _ => false
    }
  }

  def isJunctionPointCP(): Boolean = {
    this.typeCode match {
      case Some(t) if t.equals(JunctionPointCP) => true
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
case class LinearLocation(id: Long, orderNumber: Double, linkId: Long, startMValue: Double, endMValue: Double, sideCode: SideCode,
                          adjustedTimestamp: Long, calibrationPoints: (CalibrationPointReference, CalibrationPointReference) = (CalibrationPointReference.None, CalibrationPointReference.None),
                          geometry: Seq[Point], linkGeomSource: LinkGeomSource,
                          roadwayNumber: Long, validFrom: Option[DateTime] = None, validTo: Option[DateTime] = None) extends BaseLinearLocation {

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

//TODO Rename all the method names to follow a rule like fetchById instead of have fetchById and QueryById
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
       SELECT loc.ID, loc.ROADWAY_NUMBER, loc.ORDER_NUMBER, loc.LINK_ID, loc.START_MEASURE, loc.END_MEASURE, loc.SIDE,
              (SELECT RP.ADDR_M FROM CALIBRATION_POINT CP JOIN ROADWAY_POINT RP ON RP.ID = CP.ROADWAY_POINT_ID WHERE cp.LINK_ID = loc.LINK_ID AND loc.ROADWAY_NUMBER = rp.ROADWAY_NUMBER AND cp.START_END = 0 AND cp.VALID_TO IS NULL) AS cal_start_addr_m,
              (SELECT CP."TYPE" FROM CALIBRATION_POINT CP JOIN ROADWAY_POINT RP ON RP.ID = CP.ROADWAY_POINT_ID WHERE cp.LINK_ID = loc.LINK_ID AND loc.ROADWAY_NUMBER = rp.ROADWAY_NUMBER AND cp.START_END = 0 AND cp.VALID_TO IS NULL) AS cal_start_type,
              (SELECT RP.ADDR_M FROM CALIBRATION_POINT CP JOIN ROADWAY_POINT RP ON RP.ID = CP.ROADWAY_POINT_ID WHERE cp.LINK_ID = loc.LINK_ID AND loc.ROADWAY_NUMBER = rp.ROADWAY_NUMBER AND cp.START_END = 1 AND cp.VALID_TO IS NULL) AS cal_end_addr_m,
              (SELECT CP."TYPE" FROM CALIBRATION_POINT CP JOIN ROADWAY_POINT RP ON RP.ID = CP.ROADWAY_POINT_ID WHERE cp.LINK_ID = loc.LINK_ID AND loc.ROADWAY_NUMBER = rp.ROADWAY_NUMBER AND cp.START_END = 1 AND cp.VALID_TO IS NULL) AS cal_end_type,
              link.SOURCE, link.ADJUSTED_TIMESTAMP, geometry, loc.valid_from, loc.valid_to
        FROM LINEAR_LOCATION loc
        JOIN LINK link ON (link.ID = loc.LINK_ID)
    """

  def getNextLinearLocationId: Long = {
    Queries.nextLinearLocationId.as[Long].first
  }

  def create(linearLocations: Iterable[LinearLocation], createdBy: String = "-"): Seq[Long] = {

    val ps = dynamicSession.prepareStatement(
      """insert into LINEAR_LOCATION (id, ROADWAY_NUMBER, order_number, link_id, start_measure, end_measure, SIDE, geometry, created_by)
      values (?, ?, ?, ?, ?, ?, ?, ?, ?)""".stripMargin)

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
        val (p1, p2) = (location.geometry.head, location.geometry.last)
        ps.setLong(1, location.id)
        ps.setLong(2, roadwayNumber)
        ps.setLong(3, location.orderNumber.toLong)
        ps.setLong(4, location.linkId)
        ps.setDouble(5, location.startMValue)
        ps.setDouble(6, location.endMValue)
        ps.setInt(7, location.sideCode.value)
        ps.setObject(8, OracleDatabase.createRoadsJGeometry(Seq(p1, p2), dynamicSession.conn, location.endMValue))
        ps.setString(9, if (createdBy == null) "-" else createdBy)
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
    def apply(r: PositionedResult): LinearLocation = {
      val id = r.nextLong()
      val roadwayNumber = r.nextLong()
      val orderNumber = r.nextLong()
      val linkId = r.nextLong()
      val startMeasure = r.nextDouble()
      val endMeasure = r.nextDouble()
      val sideCode = r.nextInt()
      val calStartAddrM = r.nextLongOption()
      val calStartType = r.nextIntOption()
      val calEndAddrM = r.nextLongOption()
      val calEndType = r.nextIntOption()
      val linkSource = r.nextInt()
      val adjustedTimestamp = r.nextLong()
      val geom = OracleDatabase.loadRoadsJGeometryToGeometry(r.nextObjectOption())
      val validFrom = r.nextDateOption.map(d => formatter.parseDateTime(d.toString))
      val validTo = r.nextDateOption.map(d => formatter.parseDateTime(d.toString))

      val calStartTypeOption: Option[CalibrationPointType] = if (calStartType.isDefined) Some(CalibrationPointType.apply(calStartType.get)) else None

      val calEndTypeOption: Option[CalibrationPointType] = if (calEndType.isDefined) Some(CalibrationPointType.apply(calEndType.get)) else None

      LinearLocation(id, orderNumber, linkId, startMeasure, endMeasure, SideCode.apply(sideCode), adjustedTimestamp,
        (CalibrationPointReference(calStartAddrM, calStartTypeOption),
          CalibrationPointReference(calEndAddrM, calEndTypeOption)),
        geom, LinkGeomSource.apply(linkSource), roadwayNumber, validFrom, validTo)
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
      MassQuery.withIds(ids) {
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
      }
    }
  }

  def queryByIdMassQuery(ids: Set[Long], rejectInvalids: Boolean = true): List[LinearLocation] = {
    fetchByIdMassQuery(ids, rejectInvalids)
  }

  def fetchByLinkId(linkIds: Set[Long], filterIds: Set[Long] = Set()): List[LinearLocation] = {
    time(logger, "Fetch linear locations by link id") {
      if (linkIds.isEmpty) {
        return List()
      }
      if (linkIds.size > 1000 || filterIds.size > 1000) {
        return fetchByLinkIdMassQuery(linkIds).filterNot(ra => filterIds.contains(ra.id))
      }
      val linkIdsString = linkIds.mkString(", ")
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

  def fetchByLinkIdMassQuery(linkIds: Set[Long]): List[LinearLocation] = {
    time(logger, "Fetch linear locations by link id - mass query") {
      MassQuery.withIds(linkIds) {
        idTableName =>
          val query =
            s"""
              $selectFromLinearLocation
              join $idTableName i on i.id = loc.link_id
              where loc.valid_to is null
            """
          queryList(query)
      }
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
          where loc.valid_to is null and loc.ROADWAY_NUMBER in (select ROADWAY_NUMBER from linear_location
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
              where loc.valid_to is null and loc.ROADWAY_NUMBER in (
                select ROADWAY_NUMBER from linear_location
                join $idTableName i on i.id = link_id
                where valid_to is null)
            """
          queryList(query)
      }
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

  def expireByRoadwayNumbers(roadwayNumbers: Set[Long]): Int = {
    val query =
      s"""
        Update LINEAR_LOCATION Set valid_to = sysdate Where valid_to IS NULL and roadway_number in (${roadwayNumbers.mkString(",")})
      """
    if (roadwayNumbers.isEmpty)
      0
    else
      Q.updateNA(query).first
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
          create(Seq(expired.copy(id = NewIdValue, linkId = adjustment.linkId, geometry = geometry, startMValue = s, endMValue = e)), createdBy)
        case (_, Some(e)) =>
          create(Seq(expired.copy(id = NewIdValue, linkId = adjustment.linkId, geometry = geometry, endMValue = e)), createdBy)
        case (Some(s), _) =>
          create(Seq(expired.copy(id = NewIdValue, linkId = adjustment.linkId, geometry = geometry, startMValue = s)), createdBy)
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

      create(Seq(expired.copy(id = NewIdValue, geometry = geometry, sideCode = sideCode)), createdBy)
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
      case Some(s) => s" AND loc.start_measure <= $s"
      case None => ""
    }
    val endFilter = endM match {
      case Some(_) => s" AND loc.end_measure >= $endM"
      case None => ""
    }

    query + s" WHERE loc.link_id = $linkId $startFilter $endFilter" + withValidityCheck
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

      val boundingBoxFilter = OracleDatabase.boundingBoxFilter(extendedBoundingRectangle, "geometry")

      val query =
        s"""
          $selectFromLinearLocation
          where $boundingBoxFilter and loc.valid_to is null
        """
      queryList(query)
    }
  }

  def fetchLinearLocationByBoundingBox(boundingRectangle: BoundingRectangle, roadNumberLimits: Seq[(Int, Int)]): Seq[LinearLocation] = {
    time(logger, "Fetch all the linear locations of the matching roadways by bounding box") {
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
        MassQuery.withIds(roadwayNumbers) {
          idTableName =>
            s"""
              $selectFromLinearLocation
              join $idTableName i on i.id = loc.ROADWAY_NUMBER
              where loc.valid_to is null
            """.stripMargin
        }
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
    if (filter == "")
      withRoadNumbersFilter(roadNumbers.tail, alias, filterAdd)
    else
      withRoadNumbersFilter(roadNumbers.tail, alias,s"""$filter OR $filterAdd""")
  }


  /*
    CalibrationPointLocation: StartOfLink = 0
    CalibrationPointLocation: EndOfLink = 1
    CalibrationCode: AtEnd = 1
    CalibrationCode: AtBeginning = 2
    CalibrationCode: AtBoth = 3
   */
  def getJunctionDefinedCalibrationPoints(linearLocationIds: Seq[Long]): Map[Long, CalibrationCode] = {
    if (linearLocationIds.isEmpty) {
      Map()
    } else {
      val query =
        s"""SELECT DISTINCT loc.ID,
             (CASE
             WHEN (SELECT count(distinct(cp.id)) FROM CALIBRATION_POINT WHERE LINK_ID = loc.LINK_ID AND cp.VALID_TO IS null) > 1 THEN 3
             WHEN (SELECT count(distinct(cp.id)) FROM CALIBRATION_POINT WHERE LINK_ID = loc.LINK_ID AND START_END = 0 AND cp.VALID_TO IS null) = 1 THEN 2
             WHEN (SELECT count(distinct(cp.id)) FROM CALIBRATION_POINT WHERE LINK_ID = loc.LINK_ID AND START_END = 1 AND cp.VALID_TO IS null) = 1 THEN 1
             ELSE 0
             END) AS calibrationCode
             FROM LINEAR_LOCATION loc
             JOIN CALIBRATION_POINT cp ON (loc.LINK_ID = cp.LINK_ID AND cp.VALID_TO IS NULL)
             JOIN JUNCTION_POINT jp ON (jp.ROADWAY_POINT_ID = cp.ROADWAY_POINT_ID)
             JOIN JUNCTION J ON (jp.junction_id = j.id)
             WHERE loc.id in (${linearLocationIds.mkString(",")}) AND loc.VALID_TO IS NULL AND jp.VALID_TO IS NULL AND j.valid_to IS NULL and j.end_date IS NULL"""
      Q.queryNA[(Long, Int)](query).list.map {
        case (id, code) => id -> CalibrationCode(code)
      }.toMap
    }
  }

}

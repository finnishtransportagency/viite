package fi.liikennevirasto.viite.dao

import com.github.tototoshi.slick.MySQLJodaSupport._
import fi.liikennevirasto.digiroad2.util.LogUtils.time
import fi.liikennevirasto.viite._
import fi.liikennevirasto.viite.dao.ProjectCalibrationPointDAO.BaseCalibrationPoint
import fi.liikennevirasto.viite.model.RoadAddressLinkLike
import fi.liikennevirasto.viite.process.InvalidAddressDataException
import fi.vaylavirasto.viite.dao.{BaseDAO, Queries, Sequences}
import fi.vaylavirasto.viite.geometry.{GeometryUtils, Point, Vector3d}
import fi.vaylavirasto.viite.model.{AdministrativeClass, CalibrationPointType, Discontinuity, LinkGeomSource, RoadPart, SideCode, Track}
import fi.vaylavirasto.viite.postgis.MassQuery
import fi.vaylavirasto.viite.util.DateTimeFormatters.{basicDateFormatter, dateOptTimeFormatter}
import org.joda.time.DateTime
import slick.driver.JdbcDriver.backend.Database.dynamicSession
import slick.jdbc.{GetResult, PositionedResult, StaticQuery => Q}
import slick.jdbc.StaticQuery.interpolation

sealed trait CalibrationCode {
  def value: Int
}

object CalibrationCode {
  val values: Set[CalibrationCode] = Set(No, AtEnd, AtBeginning, AtBoth)

  def apply(intValue: Int): CalibrationCode = {
    values.find(_.value == intValue).getOrElse(No)
  }

  private def fromBooleans(beginning: Boolean, end: Boolean): CalibrationCode = {
    val beginValue = if (beginning) AtBeginning.value else No.value
    val endValue = if (end) AtEnd.value else No.value
    CalibrationCode.apply(beginValue + endValue)
  }

  def getFromAddress(roadAddress: BaseRoadAddress): CalibrationCode = {
    fromBooleans(roadAddress.startCalibrationPoint.isDefined, roadAddress.calibrationPoints._2.isDefined)
  }

  def getFromAddressLinkLike(roadAddress: RoadAddressLinkLike): CalibrationCode = {
    fromBooleans(roadAddress.startCalibrationPoint.isDefined, roadAddress.endCalibrationPoint.isDefined)
  }

  case object No extends CalibrationCode {
    def value = 0
  }

  case object AtEnd extends CalibrationCode {
    def value = 1
  }

  case object AtBeginning extends CalibrationCode {
    def value = 2
  }

  case object AtBoth extends CalibrationCode {
    def value = 3
  }

  def switch(calibrationCode: CalibrationCode): CalibrationCode = {
    calibrationCode match {
      case AtBeginning => AtEnd
      case AtEnd => AtBeginning
      case _ => calibrationCode
    }
  }

  def existsAtBeginning(calibrationCode: Option[CalibrationCode]): Boolean = {
    calibrationCode match {
      case Some(code) if code.equals(AtBeginning) || code.equals(AtBoth) => true
      case _ => false
    }
  }

  def existsAtEnd(calibrationCode: Option[CalibrationCode]): Boolean = {
    calibrationCode match {
      case Some(code) if code.equals(AtEnd) || code.equals(AtBoth) => true
      case _ => false
    }
  }

}

case class ProjectCalibrationPoint(
                             linkId: String,
                             segmentMValue: Double,
                             addressMValue: Long,
                             typeCode: CalibrationPointType = CalibrationPointType.UnknownCP
                           ) extends BaseCalibrationPoint {
  def this(linkId: Long, segmentMValue: Double, addressMValue: Long, typeCode: CalibrationPointType) =
   this(linkId.toString, segmentMValue, addressMValue, typeCode)
}

case class RoadwaysForJunction(jId: Long, roadwayNumber: Long, roadPart: RoadPart, track: Long, addrM: Long, beforeAfter: Long)

sealed trait TerminationCode {
  def value: Int
}

object TerminationCode {
  val values = Set(NoTermination, Termination, Subsequent)

  def apply(intValue: Int): TerminationCode = {
    values.find(_.value == intValue).getOrElse(NoTermination)
  }

  case object NoTermination extends TerminationCode {
    def value = 0
  }

  case object Termination extends TerminationCode {
    def value = 1
  }

  case object Subsequent extends TerminationCode {
    def value = 2
  }

}

//TODO this will only be used for road address
trait BaseRoadAddress {
  def id: Long

  def linearLocationId: Long

  def roadPart: RoadPart
  def track: Track

  def discontinuity: Discontinuity

  def administrativeClass: AdministrativeClass

  def startAddrMValue: Long
  def endAddrMValue: Long

  def startDate: Option[DateTime]
  def endDate: Option[DateTime]

  def createdBy: Option[String]

  def linkId: String

  def startMValue: Double
  def endMValue: Double

  def sideCode: SideCode

  def calibrationPoints: (Option[BaseCalibrationPoint], Option[BaseCalibrationPoint])

  def geometry: Seq[Point]

  def ely: Long

  def linkGeomSource: LinkGeomSource

  def reversed: Boolean

  def roadwayNumber: Long

  def copyWithGeometry(newGeometry: Seq[Point]): BaseRoadAddress

  def hasCalibrationPointAtStart: Boolean = {
    startCalibrationPoint.getOrElse(CalibrationPointType.NoCP) != CalibrationPointType.NoCP
  }

  def hasCalibrationPointAtEnd: Boolean = {
    endCalibrationPoint.getOrElse(CalibrationPointType.NoCP) != CalibrationPointType.NoCP
  }

  def liesInBetween(ra: BaseRoadAddress): Boolean = {
    (startAddrMValue >= ra.startAddrMValue && startAddrMValue <= ra.endAddrMValue) || (endAddrMValue <= ra.endAddrMValue && endAddrMValue >= ra.startAddrMValue)
  }

  def connected(ra2: BaseRoadAddress): Boolean = {
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

  def connected(p: Point): Boolean = {
    val currEndPoint = sideCode match {
      case SideCode.AgainstDigitizing => geometry.head
      case _ => geometry.last
    }

    GeometryUtils.areAdjacent(p, currEndPoint, fi.liikennevirasto.viite.MaxDistanceForConnectedLinks)
  }

  lazy val roadwayDAO = new RoadwayDAO

  lazy val startingPoint: Point = (sideCode == SideCode.AgainstDigitizing, reversed) match {
    case (true, true) | (false, false) =>
      //reversed for both SideCodes
      geometry.head
    case (true, false) | (false, true) =>
      //NOT reversed for both SideCodes
      geometry.last
  }
  lazy val endPoint: Point = (sideCode == SideCode.AgainstDigitizing, reversed) match {
    case (true, true) | (false, false) =>
      //reversed for both SideCodes
      geometry.last
    case (true, false) | (false, true) =>
      //NOT reversed for both SideCodes
      geometry.head
  }

  // starting- & endPoints for projectLinks that have been reversed. These two are used in nodesAndJunctionsService only
  lazy val newStartingPoint: Point = (sideCode == SideCode.AgainstDigitizing) match {
    case false =>
      geometry.head
    case true =>
      geometry.last
  }

  lazy val newEndPoint: Point = (sideCode == SideCode.AgainstDigitizing) match {
    case false =>
      geometry.last
    case true =>
      geometry.head
  }

  def getEndPoints: (Point, Point) = {
    if (sideCode == SideCode.Unknown) {
      val direction = if (geometry.head.y == geometry.last.y) Vector3d(1.0, 0.0, 0.0) else Vector3d(0.0, 1.0, 0.0)
      Seq((geometry.head, geometry.last), (geometry.last, geometry.head)).minBy(ps => direction.dot(ps._1.toVector - ps._2.toVector))
    } else {
      (startingPoint, endPoint)
    }
  }

  def getEndPointsOnlyBySide: (Point, Point) = {
    if (sideCode == SideCode.Unknown) {
      val direction = if (geometry.head.y == geometry.last.y) Vector3d(1.0, 0.0, 0.0) else Vector3d(0.0, 1.0, 0.0)
      Seq((geometry.head, geometry.last), (geometry.last, geometry.head)).minBy(ps => direction.dot(ps._1.toVector - ps._2.toVector))
    } else {
      val startingPoint: Point = if (sideCode == SideCode.TowardsDigitizing) {
        geometry.head
      } else {
        geometry.last
      }
      val endPoint: Point = if (sideCode == SideCode.TowardsDigitizing) {
        geometry.last
      } else {
        geometry.head
      }
      (startingPoint, endPoint)
    }
  }

  lazy val startCalibrationPoint: Option[BaseCalibrationPoint] = calibrationPoints._1
  lazy val endCalibrationPoint:   Option[BaseCalibrationPoint] = calibrationPoints._2
}

//TODO the start date and the created by should not be optional on the road address case class
// Note: Geometry on road address is not directed: it isn't guaranteed to have a direction of digitization or road addressing
case class RoadAddress(id: Long, linearLocationId: Long, roadPart: RoadPart, administrativeClass: AdministrativeClass, track: Track, discontinuity: Discontinuity,
                       startAddrMValue: Long, endAddrMValue: Long, startDate: Option[DateTime] = None, endDate: Option[DateTime] = None,
                       createdBy: Option[String] = None, linkId: String, startMValue: Double, endMValue: Double, sideCode: SideCode, adjustedTimestamp: Long,
                       calibrationPoints: (Option[ProjectCalibrationPoint], Option[ProjectCalibrationPoint]) = (None, None),
                       geometry: Seq[Point], linkGeomSource: LinkGeomSource,
                       ely: Long, terminated: TerminationCode = TerminationCode.NoTermination, roadwayNumber: Long,
                       validFrom: Option[DateTime] = None, validTo: Option[DateTime] = None, roadName: Option[String] = None) extends BaseRoadAddress {
  def this(id: Long, linearLocationId: Long, roadPart: RoadPart, administrativeClass: AdministrativeClass, track: Track, discontinuity: Discontinuity,
           startAddrMValue: Long, endAddrMValue: Long, startDate: Option[DateTime], endDate: Option[DateTime],
           createdBy: Option[String], linkId: Long, startMValue: Double, endMValue: Double, sideCode: SideCode, adjustedTimestamp: Long,
           calibrationPoints: (Option[ProjectCalibrationPoint], Option[ProjectCalibrationPoint]),
           geometry: Seq[Point], linkGeomSource: LinkGeomSource,
           ely: Long, terminated: TerminationCode, roadwayNumber: Long,
           validFrom: Option[DateTime], validTo: Option[DateTime], roadName: Option[String]) =
   this(id, linearLocationId, roadPart, administrativeClass, track, discontinuity,
     startAddrMValue, endAddrMValue, startDate, endDate,
     createdBy, linkId.toString, startMValue, endMValue, sideCode, adjustedTimestamp,
     calibrationPoints, geometry, linkGeomSource,
     ely, terminated, roadwayNumber,
     validFrom, validTo, roadName)

  override lazy val startCalibrationPoint: Option[ProjectCalibrationPoint] = calibrationPoints._1
  override lazy val endCalibrationPoint:   Option[ProjectCalibrationPoint] = calibrationPoints._2

  def startCalibrationPointType: CalibrationPointType = startCalibrationPoint match {
    case Some(cp) => cp.typeCode
    case None => CalibrationPointType.NoCP
  }
  def endCalibrationPointType: CalibrationPointType = endCalibrationPoint match {
    case Some(cp) => cp.typeCode
    case None => CalibrationPointType.NoCP
  }

  def calibrationPointTypes: (CalibrationPointType, CalibrationPointType) = (startCalibrationPointType, endCalibrationPointType)

  def reversed: Boolean = false

    /** Return true, if the AddrMvalues of this roadAddress at least partially overlap the given range limits.
    * @param rangeStartAddr Minimum startAddress of the road address range to be matched.
    * @param rangeEndAddr   Maximum   endAddress of the road address range to be matched.
    *
    *  rs-re: range start - range end
    *  sA-eA: startAddrMValue-endAddrMValue ot this roadAddress
    *
    *        range:      rs----------------re
    *                sA--|-----eA          |                 => true (1)
    *                    |           sA----|--eA             => true (2)
    *                    |   sA--------eA  |                 => true (3)
    */
  def isBetweenAddresses(rangeStartAddr: Long, rangeEndAddr: Long): Boolean = {
    (startAddrMValue <= rangeStartAddr && rangeStartAddr <= endAddrMValue) || // (1) rangeStartAddr overlaps with Mvalues
    (startAddrMValue <=   rangeEndAddr && rangeEndAddr   <= endAddrMValue) || // (2) rangeEndAddr overlaps with Mvalues
    (startAddrMValue >  rangeStartAddr && rangeEndAddr   >  endAddrMValue)    // (3) MAddresses both within range
  }

  /** Return true, if the Mvalues of this roadAddress at least partially overlap the given range limits.
    * @param rangeStartMeasure Minimum Mvalue of the geometry mvalue range to be matched.
    * @param rangeEndMeasure   Maximum Mvalue of the geometry mvalue range to be matched.
    *
    *  rs-re: rangeStart-rangeEnd
    *  sM-eM: startMValue-endMValue ot this roadAddress
    *
    *        range:      rs----------------re
    *                sM--|-----eM          |                 => true (1)
    *                    |           sM----|--eM             => true (2)
    *                    |   sM--------eM  |                 => true (3)
    */
  def isBetweenMeasures(rangeStartMeasure: Double, rangeEndMeasure: Double): Boolean = {
    (startMValue <= rangeStartMeasure && rangeStartMeasure <= endMValue) || // (1) rangeStartMeasure overlaps with Mvalues
    (startMValue <=   rangeEndMeasure && rangeEndMeasure   <= endMValue) || // (2) rangeEndMeasure overlaps with Mvalues
    (startMValue >  rangeStartMeasure && rangeEndMeasure   > endMValue)     // (3) MValues both within range
  }

  /** Return true, if the given measure is between the Mvalues of this roadAddress.
    * @param measure Value to be compared with the Mvalues of this roadAddress. */
  def isBetweenMeasures(measure: Double): Boolean = {
    startMValue < measure && measure < endMValue
  }

  def addressBetween(a: Double, b: Double): (Long, Long) = {
    val (addrA, addrB) = (addrAt(a), addrAt(b))
    (Math.min(addrA, addrB), Math.max(addrA, addrB))
  }

  def isExpire: Boolean = {
    validFrom.getOrElse(throw new IllegalStateException("The valid from should be set before call isExpire method")).isAfterNow ||
      validTo.exists(vt => vt.isEqualNow || vt.isBeforeNow)
  }

  private def addrAt(a: Double) = {
    val coefficient = (endAddrMValue - startAddrMValue) / (endMValue - startMValue)
    sideCode match {
      case SideCode.AgainstDigitizing =>
        endAddrMValue - Math.round((a - startMValue) * coefficient)
      case SideCode.TowardsDigitizing =>
        startAddrMValue + Math.round((a - startMValue) * coefficient)
      case _ => throw new InvalidAddressDataException(s"Bad sidecode $sideCode on road address $id (link $linkId)")
    }
  }

  def copyWithGeometry(newGeometry: Seq[Point]) = {
    this.copy(geometry = newGeometry)
  }

  def getFirstPoint: Point = {
    if (sideCode == SideCode.TowardsDigitizing) geometry.head else geometry.last
  }

  def getLastPoint: Point = {
    if (sideCode == SideCode.TowardsDigitizing) geometry.last else geometry.head
  }
}

case class Roadway(id: Long, roadwayNumber: Long, roadPart: RoadPart, administrativeClass: AdministrativeClass, track: Track, discontinuity: Discontinuity, startAddrMValue: Long, endAddrMValue: Long, reversed: Boolean = false, startDate: DateTime, endDate: Option[DateTime] = None, createdBy: String, roadName: Option[String], ely: Long, terminated: TerminationCode = TerminationCode.NoTermination, validFrom: DateTime = DateTime.now(), validTo: Option[DateTime] = None)

case class TrackForRoadAddressBrowser(ely: Long, roadPart: RoadPart, track: Long, startAddrM: Long, endAddrM: Long, roadAddressLengthM: Long, administrativeClass: Long, startDate: DateTime)

case class RoadPartForRoadAddressBrowser(ely: Long, roadPart: RoadPart,
                                         startAddrM: Long, endAddrM: Long, roadAddressLengthM: Long, startDate: DateTime)


class RoadwayDAO extends BaseDAO {
  val linearLocationDAO = new LinearLocationDAO

  /**
    * Fetch the roadway by the roadway number
    *
    * @param roadwayNumber  Roadway number
    * @param includeHistory Include also historical roadway
    * @return Current roadway
    */
  def fetchByRoadwayNumber(roadwayNumber: Long, includeHistory: Boolean = false): Option[Roadway] = {
    time(logger, "Fetch roadway by roadway number") {
      if (includeHistory) {
        fetch(withRoadwayNumberEnded(roadwayNumber)).headOption
      } else {
        fetch(withRoadwayNumber(roadwayNumber)).headOption
      }
    }
  }

  /**
    * Fetch all the current road addresses by road part
    *
    * @param roadPart The road part
    * @return Current road addresses at road address section
    */
  def fetchAllBySection(roadPart: RoadPart): Seq[Roadway] = {
    time(logger, "Fetch road address by road number and road part number") {
      fetch(withSection(roadPart))
    }
  }

  /**
    * Fetch all the current road addresses by road number, road part number and track codes
    *
    * @param roadPart  The road part
    * @param tracks    The set of track codes
    * @return Current road addresses at specified section
    */
  def fetchAllBySectionAndTracks(roadPart: RoadPart, tracks: Set[Track]): Seq[Roadway] = {
    time(logger, "Fetch roadway by road number, road part number and tracks") {
      if (tracks == null || tracks.isEmpty) {
        Seq()
      } else {
        fetch(withSectionAndTracks(roadPart, tracks))
      }
    }
  }

  /**
    * Fetch all the current road addresses by road number, set of road parts number and a set track codes
    *
    * @param roadNumber      The road number
    * @param roadPartNumbers The set of road part number
    * @param tracks          The set of track codes
    * @return Current road addresses at specified sections
    */
  def fetchAllBySectionsAndTracks(roadNumber: Long, roadPartNumbers: Set[Long], tracks: Set[Track]): Seq[Roadway] = {
    time(logger, "Fetch roadways by road number, road part numbers and tracks") {
      if (tracks.isEmpty || roadPartNumbers.isEmpty)
        Seq()
      else
        fetch(withSectionAndTracks(roadNumber, roadPartNumbers, tracks))
    }
  }

  def fetchAllByRoadAndTracks(roadNumber: Long, tracks: Set[Track]): Seq[Roadway] = {
    time(logger, "Fetch roadways by road number and tracks") {
      if (tracks.isEmpty)
        Seq()
      else
        fetch(withRoadAndTracks(roadNumber, tracks))
    }
  }

  def fetchAllByRoad(roadNumber: Long): Seq[Roadway] = {
    time(logger, "Fetch road address by road number") {
      fetch(withRoad(roadNumber))
    }
  }

  def fetchAllByRoadNumbers(roadNumbers: Set[Long]): Seq[Roadway] = {
    time(logger, "Fetch roadways by road number") {
      fetch(withRoadNumbersInValidDate(roadNumbers))
    }
  }

  /**
    * Will get a collection of Roadways from our database based on the road part given.
    * Also has query modifiers that will inform if it should return history roadways (default value no) or if should get only the end parts (max end address m value, default value no).
    *
    * @param roadPart     : RoadPart - Road part
    * @param withHistory  : Boolean - Query modifier, indicates if should fetch history roadways or not
    * @param fetchOnlyEnd : Boolean - Query modifier, indicates if should fetch only the end parts or not
    * @return
    */
  def fetchAllByRoadPart(roadPart: RoadPart, withHistory: Boolean = false, fetchOnlyEnd: Boolean = false): Seq[Roadway] = {
    time(logger, "Fetch roadway by road number and part") {
      fetch(withRoadPart(roadPart, withHistory, fetchOnlyEnd))
    }
  }

  def fetchAllByRoadwayNumbers(roadwayNumbers: Set[Long], withHistory: Boolean = false): Seq[Roadway] = {
    time(logger, "Fetch all current road addresses by roadway ids") {
      if (roadwayNumbers.isEmpty)
        Seq()
      else if (roadwayNumbers.size > 1000)
        massFetchWithRoadwayNumbers(roadwayNumbers, withHistory)
      else
        fetch(withRoadwayNumbers(roadwayNumbers, withHistory))
    }
  }

  def fetchAllByRoadwayNumbers(roadwayNumbers: Set[Long], searchDate: DateTime): Seq[Roadway] = {
    time(logger, "Fetch all current road addresses by roadway ids and startDate") {
      if (roadwayNumbers.isEmpty)
        Seq()
      else
        fetch(withRoadwayNumbersAndDate(roadwayNumbers, searchDate))
    }
  }

  def fetchAllByBetweenRoadNumbers(roadNumbers: (Int, Int)): Seq[Roadway] = {
    time(logger, "Fetch all current road addresses by given road numbers") {
      fetch(betweenRoadNumbers(roadNumbers))
    }
  }

  def fetchAllBySectionTrackAndAddresses(roadPart: RoadPart, track: Track, startAddrM: Option[Long], endAddrM: Option[Long]): Seq[Roadway] = {
    time(logger, "Fetch road address by road number, road part number, start address measure and end address measure") {
      fetch(withSectionTrackAndAddresses(roadPart, track, startAddrM, endAddrM))
    }
  }

  def fetchAllBySectionAndAddresses(roadPart: RoadPart, startAddrM: Option[Long], endAddrM: Option[Long], track: Option[Long] = None): Seq[Roadway] = {
    time(logger, "Fetch road address by road number, road part number, start address measure and end address measure") {
      fetch(withSectionAndAddresses(roadPart, startAddrM, endAddrM, track))
    }
  }

  def fetchAllByDateRange(sinceDate: DateTime, untilDate: DateTime): Seq[Roadway] = {
    time(logger, "Fetch road address by dates") {
      fetch(withBetweenDates(sinceDate, untilDate))
    }
  }

  def fetchUpdatedSince(sinceDate: DateTime): Seq[Roadway] = {
    time(logger, "Fetch roadways updated since date") {
      fetch(withUpdatedSince(sinceDate))
    }
  }

  def fetchAllByRoadwayId(roadwayIds: Seq[Long]): Seq[Roadway] = {
    time(logger, "Fetch roadways by id") {
      if (roadwayIds.isEmpty) {
        Seq()
      } else {
        fetch(withRoadWayIds(roadwayIds))
      }
    }
  }

  def fetchAllCurrentRoadNumbers(): Seq[Long] = {
    time(logger, "Fetch all the road numbers") {
      sql"""
			select distinct (ra.road_number)
      from ROADWAY ra
      where ra.valid_to is null and end_date is null
		  """.as[Long].list
    }
  }

  def fetchAllCurrentAndValidRoadwayIds: Set[Long] = {
    time(logger, "Fetch all roadway ids") {
      sql"""
			select distinct (ra.id)
      from ROADWAY ra
      where ra.valid_to is null and (ra.end_date is null or ra.end_date >= current_date)
		  """.as[Long].list.toSet
    }
  }

  private def fetch(queryFilter: String => String): Seq[Roadway] = {
    val query =
      """
        select
          a.id, a.ROADWAY_NUMBER, a.road_number, a.road_part_number, a.TRACK, a.start_addr_m, a.end_addr_m,
          a.reversed, a.discontinuity, a.start_date, a.end_date, a.created_by, a.ADMINISTRATIVE_CLASS, a.ely, a.terminated,
          a.valid_from, a.valid_to,
          (select rn.road_name from road_name rn where rn.road_number = a.road_number and rn.end_date is null and rn.valid_to is null) as road_name
        from ROADWAY a
      """
    val filteredQuery = queryFilter(query)
    Q.queryNA[Roadway](filteredQuery).iterator.toSeq
  }

  private def withSection(roadPart: RoadPart)(query: String): String = {
    s"""$query where valid_to is null and end_date is null and road_number = ${roadPart.roadNumber} and road_part_number = ${roadPart.partNumber}"""
  }

  private def withSectionAndTracks(roadPart: RoadPart, tracks: Set[Track])(query: String): String = {
    s"""$query where valid_to is null and end_date is null and road_number = ${roadPart.roadNumber} and road_part_number = ${roadPart.partNumber} and TRACK in (${tracks.mkString(",")})"""
  }

  private def withSectionAndTracks(roadNumber: Long, roadPartNumbers: Set[Long], tracks: Set[Track])(query: String): String = {
    s"""$query where valid_to is null and end_date is null and road_number = $roadNumber and road_part_number in (${roadPartNumbers.mkString(",")}) and TRACK in (${tracks.mkString(",")})"""
  }

  private def withRoadAndTracks(roadNumber: Long, tracks: Set[Track])(query: String): String = {
    s"""$query where valid_to is null and end_date is null and road_number = $roadNumber and TRACK in (${tracks.mkString(",")})"""
  }

  private def withRoad(roadNumber: Long)(query: String): String = {
    s"""$query where valid_to is null and end_date is null and road_number = $roadNumber"""
  }

  private def withRoadNumbersInValidDate(roadNumbers: Set[Long])(query: String): String = {
    if (roadNumbers.size > 1000) {
      MassQuery.withIds(roadNumbers)({
        idTableName =>
          s"""
            $query
            join $idTableName i on i.id = a.ROAD_NUMBER
            where a.valid_to is null AND (a.end_date is null or a.end_date >= current_date) order by a.road_number, a.road_part_number, a.start_date
          """.stripMargin
      })
    } else {
      s"""$query where a.valid_to is null AND (a.end_date is null or a.end_date >= current_date) AND a.road_number in (${roadNumbers.mkString(",")}) order by a.road_number, a.road_part_number, a.start_date"""
    }
  }

  /**
    * Defines the portion of the query that will filter the results based on the given road number, road part number and if it should include history roadways or fetch only their end parts.
    * Will return the completed SQL query.
    *
    * @param roadPart       : RoadPart - Road Part
    * @param includeHistory : Boolean - Query modifier, indicates if should fetch history roadways or not
    * @param fetchOnlyEnd   : Boolean - Query modifier, indicates if should fetch only the end parts or not
    * @param query          : String - The actual SQL query string
    * @return
    */
  private def withRoadPart(roadPart: RoadPart, includeHistory: Boolean = false, fetchOnlyEnd: Boolean = false)(query: String): String = {
    val historyFilter = if (!includeHistory)
      " AND end_date is null"
    else
      ""

    val endPart = if (fetchOnlyEnd) {
      s" AND a.end_addr_m = (Select max(road.end_addr_m) from roadway road Where road.road_number = ${roadPart.roadNumber} And road.road_part_number = ${roadPart.partNumber} And (road.valid_to IS NULL AND road.end_date is null))"
    } else ""
    s"""$query where valid_to is null AND road_number = ${roadPart.roadNumber} AND Road_Part_Number = ${roadPart.partNumber} $historyFilter $endPart"""
  }

  private def withRoadNumber(road: Long, roadPart: Option[Long], track: Option[Track], mValue: Option[Long])(query: String): String = {
    val roadPartFilter = roadPart match {
      case Some(p) => s" AND a.road_part_number = $p"
      case None => ""
    }
    val trackFilter = track match {
      case Some(t) => s"  AND a.TRACK = $t"
      case None => ""
    }
    val mValueFilter = mValue match {
      case Some(v) => s" AND ra.start_addr_M <= $v AND ra.end_addr_M > $v"
      case None => ""
    }

    query + s" WHERE a.road_number = $road " + s"$roadPartFilter $trackFilter $mValueFilter and a.end_date is null and a.valid_to is null "
  }

  private def withRoadwayNumbersAndDate(roadwayNumbers: Set[Long], searchDate: DateTime)(query: String): String = {
    def dateFilter(table: String): String = {
      val strDate = basicDateFormatter.print(searchDate)
      s" ($table.start_date <= to_date('$strDate', 'yyyymmdd') and (to_date('$strDate', 'yyyymmdd') <= $table.end_date or $table.end_date is null))"
    }

    if (roadwayNumbers.size > 1000) {
      MassQuery.withIds(roadwayNumbers)({
        idTableName =>
          s"""
            $query
            join $idTableName i on i.id = a.ROADWAY_NUMBER
            where a.valid_to is null and ${dateFilter(table = "a")}
          """.stripMargin
      })
    }
    else
      s"""$query where a.valid_to is null and ${dateFilter(table = "a")} and a.roadway_number in (${roadwayNumbers.mkString(",")})"""
  }

  private def withSectionAndAddresses(roadPart: RoadPart, startAddrMOption: Option[Long], endAddrMOption: Option[Long], track: Option[Long] = None)(query: String) = {
    val trackFilter = track.map(t => s"""and a.track = $t""").getOrElse(s"""""")
    val addressFilter = (startAddrMOption, endAddrMOption) match {
      case (Some(startAddrM), Some(endAddrM)) => s"""and ((a.start_addr_m >= $startAddrM and a.end_addr_m <= $endAddrM) or (a.start_addr_m <= $startAddrM and a.end_addr_m > $startAddrM) or (a.start_addr_m < $endAddrM and a.end_addr_m >= $endAddrM)) $trackFilter"""
      case (Some(startAddrM), _) => s"""and a.end_addr_m > $startAddrM $trackFilter"""
      case (_, Some(endAddrM)) => s"""and a.start_addr_m < $endAddrM $trackFilter"""
      case _ => s""""""
    }
    s"""$query where valid_to is null and end_date is null and road_number = ${roadPart.roadNumber} and road_part_number = ${roadPart.partNumber} $addressFilter"""
  }

  private def withSectionTrackAndAddresses(roadPart: RoadPart, track: Track, startAddrMOption: Option[Long], endAddrMOption: Option[Long])(query: String) = {
    val addressFilter = (startAddrMOption, endAddrMOption) match {
      case (Some(startAddrM), Some(endAddrM)) => s"""and ((a.start_addr_m >= $startAddrM and a.end_addr_m <= $endAddrM) or (a.start_addr_m <= $startAddrM and a.end_addr_m > $startAddrM) or (a.start_addr_m < $endAddrM and a.end_addr_m >= $endAddrM))"""
      case (Some(startAddrM), _) => s"""and a.end_addr_m > $startAddrM"""
      case (_, Some(endAddrM)) => s"""and a.start_addr_m < $endAddrM"""
      case _ => s""""""
    }
    s"""$query where valid_to is null and end_date is null and road_number = ${roadPart.roadNumber} and road_part_number = ${roadPart.partNumber} and TRACK = $track $addressFilter"""
  }

  private def withRoadwayNumber(roadwayNumber: Long)(query: String): String = {
    s"""$query where a.valid_to is null and a.end_date is null and a.ROADWAY_NUMBER = $roadwayNumber"""
  }

  private def withRoadwayNumberEnded(roadwayNumber: Long)(query: String): String = {
    s"""$query where a.valid_to is null and a.ROADWAY_NUMBER = $roadwayNumber"""
  }

  private def withRoadwayNumbers(roadwayNumbers: Set[Long], withHistory: Boolean = false)(query: String): String = {
    val endDateFilter = if (withHistory) "" else "and a.end_date is null"
    s"""$query where a.valid_to is null $endDateFilter and a.ROADWAY_NUMBER in (${roadwayNumbers.mkString(",")})"""
  }

  private def massFetchWithRoadwayNumbers(roadwayNumbers: Set[Long], withHistory: Boolean = false): Seq[Roadway] = {
    val endDateFilter = if (withHistory) "" else "and a.end_date is null"
    MassQuery.withIds(roadwayNumbers)({
      idTableName => {
        val joinedQuery = (query: String) => {
          s"""
            $query
            join $idTableName i on i.id = a.ROADWAY_NUMBER
            where a.valid_to is null $endDateFilter
          """.stripMargin
        }
        fetch(joinedQuery)
      }
    })
  }

  private def betweenRoadNumbers(roadNumbers: (Int, Int))(query: String): String = {
    s"""$query where valid_to is null and road_number BETWEEN  ${roadNumbers._1} AND ${roadNumbers._2}"""
  }

  private def withBetweenDates(sinceDate: DateTime, untilDate: DateTime)(query: String): String = {
    s"""$query where valid_to is null
          AND start_date >= to_date('${sinceDate.toString("yyyy-MM-dd")}', 'YYYY-MM-DD')
          AND start_date <= to_date('${untilDate.toString("yyyy-MM-dd")}', 'YYYY-MM-DD')
    """
  }

  private def withUpdatedSince(sinceDate: DateTime)(query: String): String = {
    val sinceString = sinceDate.toString("yyyy-MM-dd")
    s"""$query
        where valid_from >= to_date('$sinceString', 'YYYY-MM-DD')
          OR (valid_to IS NOT NULL AND valid_to >= to_date('$sinceString', 'YYYY-MM-DD'))"""
  }

  /**
    * Composes the original SQL fetch query the the where clause filtering by roadwayId.
    *
    * @param roadwayIds : Seq[Long] - Collection of the roadway id's to return
    * @param query      : String - The original SQL fetch query
    * @return
    */
  private def withRoadWayIds(roadwayIds: Seq[Long])(query: String): String = {
    s"""$query where id in (${roadwayIds.mkString(",")}) and a.valid_to is null and a.end_date is null"""
  }

  private implicit val getRoadAddress: GetResult[Roadway] = new GetResult[Roadway] {
    def apply(r: PositionedResult): Roadway = {

      val id = r.nextLong()
      val roadwayNumber = r.nextLong()
      val roadPart = RoadPart(r.nextLong(), r.nextLong())
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

      Roadway(id, roadwayNumber, roadPart, administrativeClass, Track.apply(trackCode), Discontinuity.apply(discontinuity), startAddrMValue, endAddrMValue, reverted, startDate, endDate, createdBy, roadName, ely, terminated, validFrom, validTo)
    }
  }

  private implicit val getTrackForRoadAddressBrowser: GetResult[TrackForRoadAddressBrowser] = new GetResult[TrackForRoadAddressBrowser] {
    def apply(r: PositionedResult) = {

      val ely = r.nextLong()
      val roadNumber = r.nextLong()
      val trackCode = r.nextInt()
      val roadPartNumber = r.nextLong()
      val startAddrMValue = r.nextLong()
      val endAddrMValue = r.nextLong()
      val lengthAddrM = r.nextLong()
      val administrativeClass = r.nextLong()
      val startDate       = dateOptTimeFormatter.parseDateTime(r.nextDate.toString)

      TrackForRoadAddressBrowser(ely, RoadPart(roadNumber, roadPartNumber), trackCode, startAddrMValue, endAddrMValue, lengthAddrM, administrativeClass, startDate)
    }
  }

  private implicit val getRoadPartForRoadAddressBrowser: GetResult[RoadPartForRoadAddressBrowser] = new GetResult[RoadPartForRoadAddressBrowser] {
    def apply(r: PositionedResult) = {

      val ely = r.nextLong()
      val roadNumber = r.nextLong()
      val roadPartNumber = r.nextLong()
      val startAddrMValue = r.nextLong()
      val endAddrMValue = r.nextLong()
      val lengthAddrM = r.nextLong()
      val startDate       = dateOptTimeFormatter.parseDateTime(r.nextDate.toString)

      RoadPartForRoadAddressBrowser(ely, RoadPart(roadNumber, roadPartNumber), startAddrMValue, endAddrMValue, lengthAddrM, startDate)
    }
  }

  private implicit val getRoadwaysForJunction: GetResult[RoadwaysForJunction] = new GetResult[RoadwaysForJunction] {
    def apply(r: PositionedResult): RoadwaysForJunction = {
      val jId = r.nextLong()
      val roadwayNumber = r.nextLong()
      val roadNumber = r.nextLong()
      val track = r.nextLong()
      val roadPartNumber = r.nextLong()
      val addrM = r.nextLong()
      val beforeAfter = r.nextLong()

      RoadwaysForJunction(jId, roadwayNumber, RoadPart(roadNumber, roadPartNumber), track, addrM, beforeAfter)
    }
  }

  /**
   * Fetch all current road addresses based on the junction they join
   * @return
   */
  def fetchCrossingRoadsInJunction(): Seq[RoadwaysForJunction] = {
    val query =
      s"""
         select jp.junction_id, r.roadway_number, r.road_number, r.track, r.road_part_number, rp.addr_m, jp.before_after
         from roadway r
         join roadway_point rp on r.roadway_number = rp.roadway_number
         join junction_point jp on rp.id = jp.roadway_point_id and jp.valid_to is null
         where r.valid_to is null and r.end_date is null
       """
      Q.queryNA[RoadwaysForJunction](query).iterator.toSeq
  }

  /**
    * Full SQL query to return, if existing, a road part number that is < than the supplied current one.
    *
    * @param roadPart - Roadway Road Part Number
    * @return
    */
  def fetchPreviousRoadPartNumber(roadPart: RoadPart): Option[Long] = {
    val query =
      s"""
            SELECT * FROM (
              SELECT ra.road_part_number
              FROM ROADWAY ra
              WHERE road_number = ${roadPart.roadNumber} AND road_part_number < ${roadPart.partNumber}
                AND valid_to IS NULL AND end_date IS NULL
              ORDER BY road_part_number DESC
            ) AS PREVIOUS
            LIMIT 1
        """
    Q.queryNA[Long](query).firstOption
  }


  def expireHistory(ids: Set[Long]): Int = {
    if (ids.isEmpty) {
      0
    } else {
      expireById(ids)
    }
  }

  /**
    * Expires roadways (set their valid to to the current system date) to all the roadways that have the supplied ids.
    *
    * @param ids : Seq[Long] - The ids of the roadways to expire.
    * @return
    */
  def expireById(ids: Set[Long]): Int = {
    val query =
      s"""
        Update ROADWAY Set valid_to = current_timestamp where valid_to IS NULL and id in (${ids.mkString(",")})
      """
    if (ids.isEmpty)
      0
    else {
      runUpdateToDb(query)
    }
  }

  /**
   * Fetches all road_part_numbers for roadNumber that aren't reserved in another project where
   * each ProjectLink with road_part_number would have status=RoadAddressChangeType.Termination
   * In use because of the considerable delay between accepting road address changes and changes being transferred to TR.
   * Said delay is no longer present in AWS.
   * TODO: Refactor this function as it's no longer needed.
   * @param roadNumber
   * @param startDate
   * @return
   */
  def getValidRoadParts(roadNumber: Long, startDate: DateTime): List[Long] = {
    sql"""
       select distinct ra.road_part_number
              from ROADWAY ra
              where road_number = $roadNumber AND valid_to IS NULL AND START_DATE <= $startDate
              AND END_DATE IS NULL
              AND ra.road_part_number NOT IN (select distinct pl.road_part_number from project_link pl where (select count(distinct pl2.status) from project_link pl2 where pl2.road_part_number = ra.road_part_number and pl2.road_number = ra.road_number and pl.road_number = pl2.road_number)
               = 1 and pl.status = 5)
      """.as[Long].list
  }

  def getNextRoadwayId: Long = {
    Queries.nextRoadwayId.as[Long].first
  }

  def create(roadways: Iterable[Roadway]): Seq[Long] = {
    val roadwayPS = dynamicSession.prepareStatement(
      """
        insert into ROADWAY (id, roadway_number, road_number, road_part_number,
        TRACK, start_addr_m, end_addr_m, reversed, discontinuity, start_date, end_date, created_by,
        ADMINISTRATIVE_CLASS, ely, terminated) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      """)
    val (ready, idLess) = roadways.partition(_.id != NewIdValue)
    val plIds = Sequences.fetchRoadwayIds(idLess.size)
    val createRoadways = ready ++ idLess.zip(plIds).map(x =>
      x._1.copy(id = x._2)
    )
    createRoadways.foreach { case address =>
      val roadwayNumber = if (address.roadwayNumber == NewIdValue) {
        Sequences.nextRoadwayNumber
      } else {
        address.roadwayNumber
      }
      roadwayPS.setLong(1, address.id)
      roadwayPS.setLong(2, roadwayNumber)
      roadwayPS.setLong(3, address.roadPart.roadNumber)
      roadwayPS.setLong(4, address.roadPart.partNumber)
      roadwayPS.setInt(5, address.track.value)
      roadwayPS.setLong(6, address.startAddrMValue)
      roadwayPS.setLong(7, address.endAddrMValue)
      roadwayPS.setInt(8, if (address.reversed) 1 else 0)
      roadwayPS.setInt(9, address.discontinuity.value)
      roadwayPS.setDate(10, new java.sql.Date(address.startDate.getMillis))
      if (address.endDate.isDefined) {
        roadwayPS.setDate(11, new java.sql.Date(address.endDate.get.getMillis))
      } else {
        roadwayPS.setNull(11, java.sql.Types.DATE)
      }
      roadwayPS.setString(12, address.createdBy)
      roadwayPS.setInt(13, address.administrativeClass.value)
      roadwayPS.setLong(14, address.ely)
      roadwayPS.setInt(15, address.terminated.value)
      roadwayPS.addBatch()
    }
    roadwayPS.executeBatch()
    roadwayPS.close()
    createRoadways.map(_.id).toSeq
  }

  // TODO Instead of returning Option[(Long, Long, ...)] return Option[RoadPartInfo]
  def getRoadPartInfo(roadPart: RoadPart): Option[(Long, String, Long, Long, Long, Option[DateTime], Option[DateTime])] = {
    val query =
      s"""SELECT r.id, l.link_id, r.end_addr_M, r.discontinuity, r.ely,
            (Select Max(ra.start_date) from ROADWAY ra Where r.ROAD_PART_NUMBER = ra.ROAD_PART_NUMBER and r.ROAD_NUMBER = ra.ROAD_NUMBER) as start_date,
            (Select Max(ra.end_Date) from ROADWAY ra Where r.ROAD_PART_NUMBER = ra.ROAD_PART_NUMBER and r.ROAD_NUMBER = ra.ROAD_NUMBER) as end_date
          FROM ROADWAY r
            INNER JOIN LINEAR_LOCATION l on r.ROADWAY_NUMBER = l.ROADWAY_NUMBER
            INNER JOIN (Select  MAX(rm.start_addr_m) as maxstartaddrm FROM ROADWAY rm WHERE rm.road_number=${roadPart.roadNumber} AND rm.road_part_number=${roadPart.partNumber} AND
              rm.valid_to is null AND rm.end_date is null AND rm.TRACK in (0,1)) ra
              on r.START_ADDR_M=ra.maxstartaddrm
          WHERE r.road_number=${roadPart.roadNumber} AND r.road_part_number=${roadPart.partNumber} AND
            r.valid_to is null AND r.end_date is null AND r.TRACK in (0,1)"""
    Q.queryNA[(Long, String, Long, Long, Long, Option[DateTime], Option[DateTime])](query).firstOption
  }

  def fetchTracksForRoadAddressBrowser(situationDate: Option[String], ely: Option[Long], roadNumber: Option[Long], minRoadPartNumber: Option[Long], maxRoadPartNumber: Option[Long]): Seq[TrackForRoadAddressBrowser] = {
    def withOptionalParameters(situationDate: Option[String], ely: Option[Long], roadNumber: Option[Long], minRoadPartNumber: Option[Long], maxRoadPartNumber: Option[Long])(query: String): String  = {

      val dateCondition = "AND start_date <='" + situationDate.get + "' AND (end_date >= '" + situationDate.get + "' OR end_date IS NULL)"

      val elyCondition = {
        if (ely.nonEmpty)
          s" AND ely = ${ely.get}"
        else
          ""
      }

      val roadNumberCondition = {
        if (roadNumber.nonEmpty)
          s" AND road_number = ${roadNumber.get}"
        else
          ""
      }

      val roadPartCondition = {
        val parts = (minRoadPartNumber, maxRoadPartNumber)
        parts match {
          case (Some(minPart), Some(maxPart)) => s"AND road_part_number BETWEEN $minPart AND $maxPart"
          case (None, Some(maxPart)) => s"AND road_part_number <= $maxPart"
          case (Some(minPart), None) => s"AND road_part_number >= $minPart"
          case _ => ""
        }
      }

      /** Form homogenous sections by road number, road part number, track, start date and ely
        *
        * Use three CTE's (Common Table Expression)
        * 1. roadways: Select roadways from roadway table with the optional parameters
        * 2. roadwayswithstartaddr: Select roadways from the roadways CTE that don't have another roadway BEFORE it (r2.end_addr_m = r.start_addr_m...). Then set row numbers for these roadways.
        * 3. roadwayswithendaddr: Select roadways from the roadways CTE that don't have another roadway AFTER it (r2.start_addr_m = r.end_addr_m...). Then set row numbers for these roadways as well
        * Now we have two ROW NUMBERED CTE's: roadwayswithstartaddr and roadwayswithendaddr.
        * roadwayswithstartaddr has the homogenous section's startAddrM's and roadwayswithendaddr has the endAddrM's.
        * Joining these CTE's by the row numbers will give us one list that tells us the startAddrM and endAddrM of the homogenous section.
        * */

      s"""WITH roadways
         |     AS (SELECT *
         |         FROM   roadway r
         |         WHERE  r.valid_to IS NULL
         |         $dateCondition
         |         $elyCondition
         |         $roadNumberCondition
         |         $roadPartCondition
         |         ),
         |         $query""".stripMargin
    }

    def fetchTrackSections(queryFilter: String => String): Seq[TrackForRoadAddressBrowser] = {
      val query =
        """     roadwayswithstartaddr
          |     AS (SELECT ely,
          |                road_number,
          |                road_part_number,
          |                start_addr_m,
          |                start_date,
          |                track,
          |                administrative_class,
          |                Row_number()
          |                  OVER (
          |                    ORDER BY road_number, road_part_number, track, start_addr_m)
          |                AS
          |                numb
          |         FROM   roadways r
          |         WHERE  NOT EXISTS (SELECT 1
          |                            FROM   roadways r2
          |                            WHERE  r2.start_date = r.start_date
          |                                   AND r2.end_addr_m = r.start_addr_m
          |                                   AND r2.road_number = r.road_number
          |                                   AND r2.road_part_number = r.road_part_number
          |                                   AND r2.track = r.track
          |                                   AND r2.ely = r.ely
          |                                   AND r2.administrative_class = r.administrative_class)),
          |     roadwayswithendaddr
          |     AS (SELECT ely,
          |                road_number,
          |                road_part_number,
          |                end_addr_m,
          |                start_date,
          |                track,
          |                administrative_class,
          |                Row_number()
          |                  OVER (
          |                    ORDER BY road_number, road_part_number, track, end_addr_m)
          |                AS
          |                numb
          |         FROM   roadways r
          |         WHERE  NOT EXISTS (SELECT 1
          |                            FROM   roadways r2
          |                            WHERE  r2.start_date = r.start_date
          |                                   AND r2.start_addr_m = r.end_addr_m
          |                                   AND r2.road_number = r.road_number
          |                                   AND r2.road_part_number = r.road_part_number
          |                                   AND r2.track = r.track
          |                                   AND r2.ely = r.ely
          |                                   AND r2.administrative_class = r.administrative_class))
          |SELECT s.ely,
          |       s.road_number,
          |       s.track,
          |       s.road_part_number,
          |       s.start_addr_m,
          |       e.end_addr_m,
          |       e.end_addr_m - s.start_addr_m AS length,
          |       s.administrative_class,
          |       s.start_date
          |FROM   roadwayswithstartaddr s
          |       JOIN roadwayswithendaddr e
          |         ON s.numb = e.numb
          |ORDER  BY s.road_number,
          |          s.road_part_number,
          |          s.start_addr_m,
          |          s.track; """.stripMargin
      val filteredQuery = queryFilter(query)
      Q.queryNA[TrackForRoadAddressBrowser](filteredQuery).iterator.toSeq
    }

    fetchTrackSections(withOptionalParameters(situationDate, ely, roadNumber, minRoadPartNumber, maxRoadPartNumber))
  }

  def fetchRoadPartsForRoadAddressBrowser(situationDate: Option[String], ely: Option[Long], roadNumber: Option[Long], minRoadPartNumber: Option[Long], maxRoadPartNumber: Option[Long]): Seq[RoadPartForRoadAddressBrowser] = {
    def withOptionalParameters(situationDate: Option[String], ely: Option[Long], roadNumber: Option[Long], minRoadPartNumber: Option[Long], maxRoadPartNumber: Option[Long])(query: String): String  = {

      val dateCondition = "AND start_date <='" + situationDate.get + "' AND (end_date >= '" + situationDate.get + "' OR end_date IS NULL)"

      val elyCondition = {
        if (ely.nonEmpty)
          s" AND ely = ${ely.get}"
        else
          ""
      }

      val roadNumberCondition = {
        if (roadNumber.nonEmpty)
          s" AND road_number = ${roadNumber.get}"
        else
          ""
      }

      val roadPartCondition = {
        val parts = (minRoadPartNumber, maxRoadPartNumber)
        parts match {
          case (Some(minPart), Some(maxPart)) => s"AND road_part_number BETWEEN $minPart AND $maxPart"
          case (None, Some(maxPart)) => s"AND road_part_number <= $maxPart"
          case (Some(minPart), None) => s"AND road_part_number >= $minPart"
          case _ => ""
        }
      }


      s"""      $query
      |         $dateCondition
      |         $elyCondition
      |         $roadNumberCondition
      |         $roadPartCondition
      |GROUP  BY ely,
      |          road_number,
      |          road_part_number
      |ORDER  BY r.road_number,
      |          r.road_part_number """.stripMargin
    }

    def fetchRoadParts(queryFilter: String => String): Seq[RoadPartForRoadAddressBrowser] = {
      val query =
        """SELECT ely,
          |       road_number,
          |       road_part_number,
          |       Min(start_addr_m) AS startAddr,
          |       Max(end_addr_m)   AS endAddr,
          |       Max(end_addr_m) - Min(start_addr_m) AS "length",
          |       Max(start_date)
          |FROM   roadway r
          |WHERE  r.valid_to IS NULL
          |""".stripMargin
      val filteredQuery = queryFilter(query)
      Q.queryNA[RoadPartForRoadAddressBrowser](filteredQuery).iterator.toSeq
    }
    fetchRoadParts(withOptionalParameters(situationDate, ely, roadNumber, minRoadPartNumber, maxRoadPartNumber))
  }

}

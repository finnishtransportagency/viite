package fi.liikennevirasto.viite.dao

import fi.liikennevirasto.digiroad2.util.LogUtils.time
import fi.liikennevirasto.viite._
import fi.liikennevirasto.viite.dao.ProjectCalibrationPointDAO.BaseCalibrationPoint
import fi.liikennevirasto.viite.model.RoadAddressLinkLike
import fi.liikennevirasto.viite.process.InvalidAddressDataException
import fi.vaylavirasto.viite.dao.{BaseDAO, Sequences}
import fi.vaylavirasto.viite.geometry.{GeometryUtils, Point, Vector3d}
import fi.vaylavirasto.viite.model.{AddrMRange, AdministrativeClass, CalibrationPointType, Discontinuity, LinkGeomSource, RoadPart, SideCode, Track}
import fi.vaylavirasto.viite.postgis.MassQuery
import org.joda.time.DateTime
import scalikejdbc._
import scalikejdbc.jodatime.JodaWrappedResultSet.fromWrappedResultSetToJodaWrappedResultSet

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

object RoadwaysForJunction extends SQLSyntaxSupport[RoadwaysForJunction] {

  def apply(rs: WrappedResultSet): RoadwaysForJunction = new RoadwaysForJunction(
    jId           = rs.long("junction_id"),
    roadwayNumber = rs.long("roadway_number"),
    roadPart      = RoadPart(
      roadNumber  = rs.long("road_number"),
      partNumber  = rs.long("road_part_number")
    ),
    track         = rs.long("track"),
    addrM         = rs.long("addr_m"),
    beforeAfter   = rs.long("before_after")
  )
}

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

  def addrMRange: AddrMRange

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
                       addrMRange: AddrMRange, startDate: Option[DateTime] = None, endDate: Option[DateTime] = None,
                       createdBy: Option[String] = None, linkId: String, startMValue: Double, endMValue: Double, sideCode: SideCode, adjustedTimestamp: Long,
                       calibrationPoints: (Option[ProjectCalibrationPoint], Option[ProjectCalibrationPoint]) = (None, None),
                       geometry: Seq[Point], linkGeomSource: LinkGeomSource,
                       ely: Long, terminated: TerminationCode = TerminationCode.NoTermination, roadwayNumber: Long,
                       validFrom: Option[DateTime] = None, validTo: Option[DateTime] = None, roadName: Option[String] = None) extends BaseRoadAddress {
  def this(id: Long, linearLocationId: Long, roadPart: RoadPart, administrativeClass: AdministrativeClass, track: Track, discontinuity: Discontinuity,
           addrMRange: AddrMRange, startDate: Option[DateTime], endDate: Option[DateTime],
           createdBy: Option[String], linkId: Long, startMValue: Double, endMValue: Double, sideCode: SideCode, adjustedTimestamp: Long,
           calibrationPoints: (Option[ProjectCalibrationPoint], Option[ProjectCalibrationPoint]),
           geometry: Seq[Point], linkGeomSource: LinkGeomSource,
           ely: Long, terminated: TerminationCode, roadwayNumber: Long,
           validFrom: Option[DateTime], validTo: Option[DateTime], roadName: Option[String]) =
   this(id, linearLocationId, roadPart, administrativeClass, track, discontinuity,
     addrMRange, startDate, endDate,
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
    * @param range The address range to be matched.
    *
    *  rs-re: given range
    *  sA-eA: start - end of this roadAddress
    *
    *        range:      rs----------------re
    *                sA--|-----eA          |                 => true (1)
    *                    |           sA----|--eA             => true (2)
    *                    |   sA--------eA  |                 => true (3)
    */
  def isBetweenAddresses(range: AddrMRange): Boolean = {
    (addrMRange.start <= range.start && range.start <= addrMRange.end) || // (1) rangeStartAddr overlaps with Mvalues
    (addrMRange.start <= range.end   && range.end   <= addrMRange.end) || // (2) rangeEndAddr overlaps with Mvalues
    (addrMRange.start >  range.start && range.end   >  addrMRange.end)    // (3) MAddresses both within range
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

  /** Get an address value corresponding to the measured values. */
  private def addrAt(a: Double) = {
    val coefficient = (addrMRange.length) / (endMValue - startMValue) // Coefficient telling how many road address meters is a geometry meter.
    sideCode match {
      case SideCode.AgainstDigitizing =>
        addrMRange.end - Math.round((a - startMValue) * coefficient)
      case SideCode.TowardsDigitizing =>
        addrMRange.start + Math.round((a - startMValue) * coefficient)
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

case class Roadway(id: Long, roadwayNumber: Long, roadPart: RoadPart, administrativeClass: AdministrativeClass, track: Track, discontinuity: Discontinuity, addrMRange: AddrMRange, reversed: Boolean = false, startDate: DateTime, endDate: Option[DateTime] = None, createdBy: String, roadName: Option[String], ely: Long, terminated: TerminationCode = TerminationCode.NoTermination, validFrom: DateTime = DateTime.now(), validTo: Option[DateTime] = None)

object Roadway extends SQLSyntaxSupport[Roadway] {
  override val tableName = "ROADWAY"

  def apply(rs: WrappedResultSet): Roadway = new Roadway(
    id            = rs.long("id"),
    roadwayNumber = rs.long("roadway_number"),
    roadPart      = RoadPart(
      roadNumber  = rs.long("road_number"),
      partNumber  = rs.long("road_part_number")
    ),
    administrativeClass = AdministrativeClass(rs.int("administrative_class")),
    track               = Track(rs.int("track")),
    discontinuity       = Discontinuity(rs.int("discontinuity")),
    addrMRange          = AddrMRange(
      start             = rs.long("start_addr_m"),
      end               = rs.long("end_addr_m")
    ),
    reversed            = rs.boolean("reversed"),
    startDate           = rs.jodaDateTime("start_date"),
    endDate             = rs.jodaDateTimeOpt("end_date"),
    createdBy           = rs.string("created_by"),
    roadName            = rs.stringOpt("road_name"),
    ely                 = rs.long("ely"),
    terminated          = TerminationCode(rs.int("terminated")),
    validFrom           = rs.jodaDateTime("valid_from"),
    validTo             = rs.jodaDateTimeOpt("valid_to")
  )
}

case class TrackForRoadAddressBrowser(ely: Long, roadPart: RoadPart, track: Long, addrMRange: AddrMRange, roadAddressLengthM: Long, administrativeClass: Long, startDate: DateTime)

object TrackForRoadAddressBrowser extends SQLSyntaxSupport[TrackForRoadAddressBrowser] {

  def apply(rs: WrappedResultSet): TrackForRoadAddressBrowser = new TrackForRoadAddressBrowser(
    ely                 = rs.long("ely"),
    roadPart            = RoadPart(
      roadNumber        = rs.long("road_number"),
      partNumber        = rs.long("road_part_number")
    ),
    track               = rs.long("track"),
    addrMRange          = AddrMRange(
      start             = rs.long("start_addr_m"),
      end               = rs.long("end_addr_m")
    ),
    roadAddressLengthM  = rs.long("length"),
    administrativeClass = rs.long("administrative_class"),
    startDate           = rs.jodaDateTime("start_date")
  )
}

case class RoadPartForRoadAddressBrowser(ely: Long, roadPart: RoadPart,
                                         addrMRange: AddrMRange, roadAddressLengthM: Long, startDate: DateTime)

object RoadPartForRoadAddressBrowser extends SQLSyntaxSupport[RoadPartForRoadAddressBrowser] {

  def apply(rs: WrappedResultSet): RoadPartForRoadAddressBrowser = new RoadPartForRoadAddressBrowser(
    ely                = rs.long("ely"),
    roadPart           = RoadPart(
      roadNumber       = rs.long("road_number"),
      partNumber       = rs.long("road_part_number")
    ),
    addrMRange = AddrMRange(
      start            = rs.long("start_addr_m"),
      end              = rs.long("end_addr_m")
    ),
    roadAddressLengthM = rs.long("length"),
    startDate          = rs.jodaDateTime("start_date")
  )
}

case class RoadPartInfo(roadPart: RoadPart, roadwayNumber: Long, beforeAfter: Long, roadwayPointId: Long, addrM: Long, track: Long, addrMRange: AddrMRange, roadwayId: Long)

class RoadwayDAO extends BaseDAO {
  private val rw = Roadway.syntax("rw")

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
      val query = sql"""
			SELECT DISTINCT (ra.road_number)
      FROM roadway ra
      WHERE ra.valid_to IS NULL
        AND end_date IS NULL
		  """
      runSelectQuery(query.map(_.long(1)))
    }
  }

  def fetchAllCurrentAndValidRoadwayIds: Set[Long] = {
    time(logger, "Fetch all roadway ids") {
      val query = sql"""
			SELECT DISTINCT (ra.id)
      FROM roadway ra
      WHERE ra.valid_to IS NULL
        AND (ra.end_date IS NULL OR ra.end_date >= current_date)
		  """
      runSelectQuery(query.map(_.long(1))).toSet
    }
  }

  private def fetch(queryFilter: SQLSyntax => SQL[Nothing, NoExtractor]): Seq[Roadway] = {
    val query =
      sqls"""
        SELECT
          a.id, a.ROADWAY_NUMBER, a.road_number, a.road_part_number, a.track, a.start_addr_m, a.end_addr_m,
          a.reversed, a.discontinuity, a.start_date, a.end_date, a.created_by, a.administrative_class, a.ely, a.terminated,
          a.valid_from, a.valid_to,
          (SELECT rn.road_name FROM road_name rn WHERE rn.road_number = a.road_number and rn.end_date IS NULL and rn.valid_to IS NULL) AS road_name
        FROM ROADWAY a
      """
    val fullQuery = queryFilter(query)
    runSelectQuery(fullQuery.map(Roadway.apply))
  }

  private def withSection(roadPart: RoadPart)(baseQuery: SQLSyntax): SQL[Nothing, NoExtractor] = {
    sql"""
          $baseQuery
          WHERE valid_to IS NULL
          AND end_date IS NULL
          AND road_number = ${roadPart.roadNumber}
          AND road_part_number = ${roadPart.partNumber}
          """
  }

  private def withSectionAndTracks(roadPart: RoadPart, tracks: Set[Track])(query: SQLSyntax): SQL[Nothing, NoExtractor] = {
    val trackValues = tracks.map(_.value) // Set[Track] to Set[Int]
    sql"""
         $query
         WHERE valid_to IS NULL
         AND end_date IS NULL
         AND road_number = ${roadPart.roadNumber}
         AND road_part_number = ${roadPart.partNumber}
         AND track IN ($trackValues)
         """
  }

  private def withSectionAndTracks(roadNumber: Long, roadPartNumbers: Set[Long], tracks: Set[Track])(query: SQLSyntax): SQL[Nothing, NoExtractor] = {
    val trackValues = tracks.map(_.value) // Set[Track] to Set[Int]
    sql"""
         $query
         WHERE valid_to IS NULL
         AND end_date IS NULL
         AND road_number = $roadNumber
         AND road_part_number in ($roadPartNumbers)
         AND track IN ($trackValues)
         """
  }

  private def withRoadAndTracks(roadNumber: Long, tracks: Set[Track])(query: SQLSyntax): SQL[Nothing, NoExtractor] = {
    val trackValues = tracks.map(_.value) // Set[Track] to Set[Int]
    sql"""
         $query
         WHERE valid_to IS NULL
         AND end_date IS NULL
         AND road_number = $roadNumber
         AND track IN ($trackValues)
         """
  }

  private def withRoad(roadNumber: Long)(query: SQLSyntax): SQL[Nothing, NoExtractor] = {
    sql"""
         $query
         WHERE valid_to IS NULL
         AND end_date IS NULL
         AND road_number = $roadNumber
         """
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
  private def withRoadPart(roadPart: RoadPart, includeHistory: Boolean = false, fetchOnlyEnd: Boolean = false)(query: SQLSyntax): SQL[Nothing, NoExtractor] = {
    val historyFilter = if (!includeHistory)
      sqls" AND end_date IS NULL"
    else
      sqls""

    val endPart = if (fetchOnlyEnd) {
      sqls""" AND a.end_addr_m = (
                SELECT max(road.end_addr_m)
                FROM roadway road
                WHERE road.road_number = ${roadPart.roadNumber}
                  AND road.road_part_number = ${roadPart.partNumber}
                  AND (road.valid_to IS NULL AND road.end_date IS NULL)
                  )"""
    } else sqls""

    sql"""
         $query
         WHERE valid_to IS NULL
         AND road_number = ${roadPart.roadNumber}
         AND Road_Part_Number = ${roadPart.partNumber}
         $historyFilter
         $endPart
         """
  }

  private def withRoadwayNumbersAndDate(roadwayNumbers: Set[Long], searchDate: DateTime)(query: SQLSyntax): SQL[Nothing, NoExtractor] = {
    def dateFilter(table: SQLSyntax): SQLSyntax = {
      sqls""" (
            $table.start_date <= $searchDate::date
            AND (
            $searchDate::date <= $table.end_date
            OR $table.end_date IS NULL
            )
            )"""
    }

    if (roadwayNumbers.size > 1000) {
      MassQuery.withIds(roadwayNumbers)({
        idTableName =>
          sql"""
            $query
            JOIN $idTableName i ON i.id = a.ROADWAY_NUMBER
            WHERE a.valid_to IS NULL
            AND ${dateFilter(table = sqls"a")}
          """
      })
    }
    else
      sql"""
           $query
           WHERE a.valid_to IS NULL
           AND ${dateFilter(table = sqls"a")}
           AND a.roadway_number IN ($roadwayNumbers)
           """
  }

  private def withSectionAndAddresses(roadPart: RoadPart, startAddrMOption: Option[Long], endAddrMOption: Option[Long], track: Option[Long] = None)(query: SQLSyntax): SQL[Nothing, NoExtractor] = {
    val trackFilter = track.map(t => sqls"""AND a.track = $t""").getOrElse(sqls"""""")
    val addressFilter = (startAddrMOption, endAddrMOption) match {
      case (Some(startAddrM), Some(endAddrM)) =>
        sqls"""
              AND ((a.start_addr_m >= $startAddrM AND a.end_addr_m <= $endAddrM)
              OR (a.start_addr_m <= $startAddrM AND a.end_addr_m > $startAddrM)
              OR (a.start_addr_m < $endAddrM AND a.end_addr_m >= $endAddrM))
              $trackFilter
              """
      case (Some(startAddrM), _) => sqls"""AND a.end_addr_m > $startAddrM $trackFilter"""
      case (_, Some(endAddrM)) => sqls"""AND a.start_addr_m < $endAddrM $trackFilter"""
      case _ => sqls""
    }

    sql"""
         $query
         WHERE valid_to IS NULL
         AND end_date IS NULL
         AND road_number = ${roadPart.roadNumber}
         AND road_part_number = ${roadPart.partNumber}
         $addressFilter
         """
  }

  private def withSectionTrackAndAddresses(roadPart: RoadPart, track: Track, startAddrMOption: Option[Long], endAddrMOption: Option[Long])(query: SQLSyntax): SQL[Nothing, NoExtractor] = {
    val addressFilter = (startAddrMOption, endAddrMOption) match {
      case (Some(startAddrM), Some(endAddrM)) =>
        sqls"""
              AND (
                (a.start_addr_m >= $startAddrM AND a.end_addr_m <= $endAddrM) OR
                (a.start_addr_m <= $startAddrM AND a.end_addr_m > $startAddrM) OR
                (a.start_addr_m < $endAddrM  AND a.end_addr_m >= $endAddrM)
              )
            """
      case (Some(startAddrM), _) => sqls"""and a.end_addr_m > $startAddrM"""
      case (_, Some(endAddrM)) => sqls"""and a.start_addr_m < $endAddrM"""
      case _ => sqls""
    }
    sql"""
         $query
         WHERE valid_to IS NULL
         AND end_date IS NULL
         AND road_number = ${roadPart.roadNumber}
         AND road_part_number = ${roadPart.partNumber}
         AND track = ${track.value} $addressFilter
         """
  }

  private def withRoadwayNumber(roadwayNumber: Long)(query: SQLSyntax): SQL[Nothing, NoExtractor] = {
    sql"""
         $query
         WHERE a.valid_to IS NULL
         AND a.end_date IS NULL
         AND a.ROADWAY_NUMBER = $roadwayNumber
         """
  }

  private def withRoadwayNumberEnded(roadwayNumber: Long)(query: SQLSyntax): SQL[Nothing, NoExtractor] = {
    sql"""
         $query
         WHERE a.valid_to IS NULL
         AND a.ROADWAY_NUMBER = $roadwayNumber
         """
  }

  private def withRoadwayNumbers(roadwayNumbers: Set[Long], withHistory: Boolean = false)(query: SQLSyntax): SQL[Nothing, NoExtractor] = {
    val endDateFilter = if (withHistory) sqls"" else sqls"and a.end_date IS NULL"
    sql"""
         $query
         WHERE a.valid_to IS NULL $endDateFilter
         AND a.ROADWAY_NUMBER IN ($roadwayNumbers)
         """
  }

  private def massFetchWithRoadwayNumbers(roadwayNumbers: Set[Long], withHistory: Boolean = false): Seq[Roadway] = {
    val endDateFilter = if (withHistory) sqls"" else sqls"and a.end_date IS NULL"
    MassQuery.withIds(roadwayNumbers)({
      idTableName => {
        val joinedQuery = (query: SQLSyntax) => {
          sql"""
            $query
            JOIN $idTableName i ON i.id = a.ROADWAY_NUMBER
            WHERE a.valid_to IS NULL $endDateFilter
          """
        }
        fetch(joinedQuery)
      }
    })
  }

  private def betweenRoadNumbers(roadNumbers: (Int, Int))(query: SQLSyntax): SQL[Nothing, NoExtractor] = {
    sql"""
         $query
         WHERE valid_to IS NULL
         AND road_number BETWEEN  ${roadNumbers._1} AND ${roadNumbers._2}
         """
  }

  private def withBetweenDates(sinceDate: DateTime, untilDate: DateTime)(query: SQLSyntax): SQL[Nothing, NoExtractor] = {
    sql"""
          $query
          WHERE valid_to IS NULL
          AND start_date >= cast(${sinceDate.toDate} as date)
          AND start_date <= cast(${untilDate.toDate} as date)
    """
  }

  private def withUpdatedSince(sinceDate: DateTime)(query: SQLSyntax): SQL[Nothing, NoExtractor] = {
    sql"""
        $query
        WHERE valid_FROM >= $sinceDate::date
        OR (valid_to IS NOT NULL
        AND valid_to >= $sinceDate::date)
        """
  }

  /**
   * Composes the original SQL fetch query the the WHERE clause filtering by roadwayId.
   *
   * @param roadwayIds : Seq[Long] - Collection of the roadway id's to return
   * @param query      : String - The original SQL fetch query
   * @return
   */
  private def withRoadWayIds(roadwayIds: Seq[Long])(query: SQLSyntax): SQL[Nothing, NoExtractor] = {
    sql"""
         $query
         WHERE id IN ($roadwayIds)
         AND a.valid_to IS NULL
         AND a.end_date IS NULL
         """
  }

  /**
   * Fetch all current road addresses based on the junction they join
   * @return
   */
  def fetchCrossingRoadsInJunction(): Seq[RoadwaysForJunction] = {
    val query =
      sql"""
         select jp.junction_id, r.roadway_number, r.road_number, r.track, r.road_part_number, rp.addr_m, jp.before_after
         FROM roadway r
         join roadway_point rp on r.roadway_number = rp.roadway_number
         join junction_point jp on rp.id = jp.roadway_point_id and jp.valid_to IS NULL
         WHERE r.valid_to IS NULL and r.end_date IS NULL
       """
    runSelectQuery(query.map(RoadwaysForJunction.apply))
    //Q.queryNA[RoadwaysForJunction](query).iterator.toSeq
  }

  /**
   * Full SQL query to return, if existing, a road part number that is < than the supplied current one.
   *
   * @param roadPart - Roadway Road Part Number
   * @return
   */
  def fetchPreviousRoadPartNumber(roadPart: RoadPart): Option[Long] = {
    val query =
      sql"""
            SELECT * FROM (
              SELECT ra.road_part_number
              FROM roadway ra
              WHERE road_number = ${roadPart.roadNumber} AND road_part_number < ${roadPart.partNumber}
                AND valid_to IS NULL AND end_date IS NULL
              ORDER BY road_part_number DESC
            ) AS PREVIOUS
            LIMIT 1
        """
    runSelectSingleOption(query.map(_.long(1)))
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
      sql"""
        Update ROADWAY Set valid_to = current_timestamp WHERE valid_to IS NULL and id in ($ids)
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
    val query = sql"""
        SELECT DISTINCT ra.road_part_number
        FROM roadway ra
        WHERE road_number = $roadNumber AND valid_to IS NULL AND START_DATE <= $startDate
            AND end_date IS NULL
            AND ra.road_part_number NOT IN (
        SELECT DISTINCT pl.road_part_number
        FROM project_link pl
            WHERE (
            SELECT count(DISTINCT pl2.status)
            FROM project_link pl2
            WHERE pl2.road_part_number = ra.road_part_number
            AND pl2.road_number = ra.road_number
            AND pl.road_number = pl2.road_number)
         = 1
         AND pl.status = 5)
      """
    runSelectQuery(query.map(_.long(1)))
  }

  def create(roadways: Iterable[Roadway]): Seq[Long] = {
    val column = Roadway.column
    val (ready, idLess) = roadways.partition(_.id != NewIdValue)
    val newIds = Sequences.fetchRoadwayIds(idLess.size)
    val createRoadways = ready ++ idLess.zip(newIds).map { case (rw, newId) =>
      rw.copy(id = newId)
    }

    // Assign new roadway numbers to any roadways that don't have one
    val roadwaysWithNumbers = createRoadways.map { roadway =>
      val roadwayNumber = if (roadway.roadwayNumber == NewIdValue) {
        Sequences.nextRoadwayNumber
      } else {
        roadway.roadwayNumber
      }
      roadway.copy(roadwayNumber = roadwayNumber)
    }

    // Prepare batch parameters
    val batchParams: Seq[Seq[Any]] = roadwaysWithNumbers.map { roadway =>
      Seq(
        roadway.id,
        roadway.roadwayNumber,
        roadway.roadPart.roadNumber,
        roadway.roadPart.partNumber,
        roadway.track.value,
        roadway.addrMRange.start,
        roadway.addrMRange.end,
        if (roadway.reversed) 1 else 0,
        roadway.discontinuity.value,
        new java.sql.Date(roadway.startDate.getMillis),
        roadway.endDate.map(date => new java.sql.Date(date.getMillis)).orNull,
        roadway.createdBy,
        roadway.administrativeClass.value,
        roadway.ely,
        roadway.terminated.value
      )
    }.toSeq

    // Construct the SQL insert query
    val insertQuery = sql"""
      INSERT INTO ROADWAY (
        ${column.id},
        ${column.roadwayNumber},
        road_number,
        road_part_number,
        ${column.track},
        start_addr_m,
        end_addr_m,
        ${column.reversed},
        ${column.discontinuity},
        ${column.startDate},
        ${column.endDate},
        ${column.createdBy},
        ${column.administrativeClass},
        ${column.ely},
        ${column.terminated}
      ) VALUES (
        ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
      )
    """

    // Execute the batch update
    runBatchUpdateToDb(insertQuery, batchParams)

    // Return the list of IDs
    roadwaysWithNumbers.map(_.id).toSeq
  }

  case class RoadPartDetail(id: Long, linkId: String, endAddrM: Long, discontinuity: Long, ely: Long, startDate: Option[DateTime], endDate: Option[DateTime])

  def getRoadPartInfo(roadPart: RoadPart): Option[RoadPartDetail] = {
    val query =
      sql"""SELECT r.id, l.link_id, r.end_addr_m, r.discontinuity, r.ely,
            (SELECT MAX(ra.start_date) FROM roadway ra WHERE r.road_part_number = ra.road_part_number AND r.road_number = ra.road_number) AS start_date,
            (SELECT MAX(ra.end_date)   FROM roadway ra WHERE r.road_part_number = ra.road_part_number AND r.road_number = ra.road_number) AS end_date
            FROM roadway r
            INNER JOIN linear_location l ON r.ROADWAY_NUMBER = l.ROADWAY_NUMBER
            INNER JOIN (
              SELECT  MAX(rm.start_addr_m) AS maxstartaddrm
              FROM roadway rm
              WHERE rm.road_number=${roadPart.roadNumber}
              AND rm.road_part_number=${roadPart.partNumber}
              AND rm.valid_to IS NULL
              AND rm.end_date IS NULL
              AND rm.track IN (0,1)
              ) ra
            ON r.start_addr_m=ra.maxstartaddrm
            WHERE r.road_number=${roadPart.roadNumber}
            AND r.road_part_number=${roadPart.partNumber}
            AND r.valid_to IS NULL
            AND r.end_date IS NULL
            AND r.track IN (0,1)
            """

    runSelectFirst(query.map(rs => RoadPartDetail(
      id            = rs.long("id"),
      linkId        = rs.string("link_id"),
      endAddrM      = rs.long("end_addr_m"),
      discontinuity = rs.long("discontinuity"),
      ely           = rs.long("ely"),
      startDate     = rs.jodaDateTimeOpt("start_date"),
      endDate       = rs.jodaDateTimeOpt("end_date")
    )))
  }

  def fetchTracksForRoadAddressBrowser(situationDate: Option[String], ely: Option[Long], roadNumber: Option[Long],
                                       minRoadPartNumber: Option[Long], maxRoadPartNumber: Option[Long]): Seq[TrackForRoadAddressBrowser] = {

    val dateCondition = situationDate.map(date =>
      sqls"AND start_date <= $date::date AND (end_date >= $date::date OR end_date IS NULL)"
    ).getOrElse(sqls"")

    val elyCondition = ely.map(ely => sqls" AND ely = $ely").getOrElse(sqls"")
    val roadNumberCondition = roadNumber.map(roadNumber => sqls" AND road_number = $roadNumber").getOrElse(sqls"")

    val roadPartCondition = {
      val parts = (minRoadPartNumber, maxRoadPartNumber)
      parts match {
        case (Some(minPart), Some(maxPart)) => sqls"AND road_part_number BETWEEN $minPart AND $maxPart"
        case (None, Some(maxPart)) => sqls"AND road_part_number <= $maxPart"
        case (Some(minPart), None) => sqls"AND road_part_number >= $minPart"
        case _ => sqls""
      }
    }

    /** Form homogenous sections by road number, road part number, track, start date and ely
     *
     * Use three CTE's (Common Table Expression)
     * 1. roadways: SELECT roadways FROM roadway table with the optional parameters
     * 2. roadwayswithstartaddr: SELECT roadways FROM the roadways CTE that don't have another roadway BEFORE it (r2.end_addr_m = r.start_addr_m...). Then set row numbers for these roadways.
     * 3. roadwayswithendaddr: SELECT roadways FROM the roadways CTE that don't have another roadway AFTER it (r2.start_addr_m = r.end_addr_m...). Then set row numbers for these roadways as well
     * Now we have two ROW NUMBERED CTE's: roadwayswithstartaddr and roadwayswithendaddr.
     * roadwayswithstartaddr has the homogenous section's startAddrM's and roadwayswithendaddr has the endAddrM's.
     * Joining these CTE's by the row numbers will give us one list that tells us the startAddrM and endAddrM of the homogenous section.
     * */
    val query =
      sql"""WITH roadways
                AS (SELECT *
                    FROM   roadway r
                    WHERE  r.valid_to IS NULL
                    $dateCondition
                    $elyCondition
                    $roadNumberCondition
                    $roadPartCondition
                    ),
           roadwayswithstartaddr AS (SELECT ely,
                           road_number,
                           road_part_number,
                           start_addr_m,
                           start_date,
                           track,
                           administrative_class,
                           Row_number()
                             OVER (
                               ORDER BY road_number, road_part_number, track, start_addr_m)
                           AS
                           numb
                    FROM   roadways r
                    WHERE  NOT EXISTS (SELECT 1
                                       FROM   roadways r2
                                       WHERE  r2.start_date = r.start_date
                                              AND r2.end_addr_m = r.start_addr_m
                                              AND r2.road_number = r.road_number
                                              AND r2.road_part_number = r.road_part_number
                                              AND r2.track = r.track
                                              AND r2.ely = r.ely
                                              AND r2.administrative_class = r.administrative_class
                                              )
                                              ),
                roadwayswithendaddr AS
                 (SELECT   ely,
                           road_number,
                           road_part_number,
                           end_addr_m,
                           start_date,
                           track,
                           administrative_class,
                           Row_number()
                             OVER (
                               ORDER BY road_number, road_part_number, track, end_addr_m)
                           AS
                           numb
                    FROM   roadways r
                    WHERE  NOT EXISTS (
                                       SELECT 1
                                       FROM   roadways r2
                                       WHERE  r2.start_date = r.start_date
                                              AND r2.start_addr_m = r.end_addr_m
                                              AND r2.road_number = r.road_number
                                              AND r2.road_part_number = r.road_part_number
                                              AND r2.track = r.track
                                              AND r2.ely = r.ely
                                              AND r2.administrative_class = r.administrative_class
                                       )
                       )
           SELECT s.ely,
                  s.road_number,
                  s.track,
                  s.road_part_number,
                  s.start_addr_m,
                  e.end_addr_m,
                  e.end_addr_m - s.start_addr_m AS length,
                  s.administrative_class,
                  s.start_date
           FROM   roadwayswithstartaddr s
                  JOIN roadwayswithendaddr e
                    ON s.numb = e.numb
                    AND s.road_number = e.road_number
                    AND s.road_part_number = e.road_part_number
                    AND s.track = e.track
           ORDER  BY s.road_number,
                     s.road_part_number,
                     s.start_addr_m,
                     s.track;
                     """

    runSelectQuery(query.map(TrackForRoadAddressBrowser.apply))

  }

  def fetchRoadPartsForRoadAddressBrowser(situationDate: Option[String], ely: Option[Long], roadNumber: Option[Long], minRoadPartNumber: Option[Long], maxRoadPartNumber: Option[Long]): Seq[RoadPartForRoadAddressBrowser] = {

    val dateCondition = situationDate.map(date =>
      // using ::date to cast the date string to date type
      sqls"AND start_date <= $date::date AND (end_date >= $date::date OR end_date IS NULL)"
    ).getOrElse(sqls"")

    val elyCondition = ely.map(ely => sqls" AND ely = $ely").getOrElse(sqls"")
    val roadNumberCondition = roadNumber.map(roadNumber => sqls" AND road_number = $roadNumber").getOrElse(sqls"")

    val roadPartCondition = {
      val parts = (minRoadPartNumber, maxRoadPartNumber)
      parts match {
        case (Some(minPart), Some(maxPart)) => sqls"AND road_part_number BETWEEN $minPart AND $maxPart"
        case (None, Some(maxPart)) => sqls"AND road_part_number <= $maxPart"
        case (Some(minPart), None) => sqls"AND road_part_number >= $minPart"
        case _ => sqls""
      }
    }

    val selectPart = sqls"""
    SELECT
      r.ely,
      r.road_number,
      r.road_part_number,
      MIN(r.start_addr_m) AS start_addr_m,
      MAX(r.end_addr_m) AS end_addr_m,
      MAX(r.end_addr_m) - MIN(r.start_addr_m) AS length,
      MAX(r.start_date) AS start_date
  """

    val fromWherePart = sqls"""
    FROM roadway r
    WHERE r.valid_to IS NULL
    $dateCondition
    $elyCondition
    $roadNumberCondition
    $roadPartCondition
  """

    val groupByPart = sqls"""
    GROUP BY r.ely, r.road_number, r.road_part_number
    ORDER BY r.road_number, r.road_part_number
  """

    val query = sql"$selectPart $fromWherePart $groupByPart"

    runSelectQuery(query.map(RoadPartForRoadAddressBrowser.apply))
  }

}

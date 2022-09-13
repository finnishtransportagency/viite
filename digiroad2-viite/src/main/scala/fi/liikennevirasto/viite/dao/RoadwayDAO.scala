package fi.liikennevirasto.viite.dao

import com.github.tototoshi.slick.MySQLJodaSupport._
import fi.liikennevirasto.digiroad2.{GeometryUtils, Point, Vector3d}
import fi.liikennevirasto.digiroad2.asset.{AdministrativeClass, LinkGeomSource, SideCode}
import fi.liikennevirasto.digiroad2.asset.SideCode.AgainstDigitizing
import fi.liikennevirasto.digiroad2.dao.{Queries, Sequences}
import fi.liikennevirasto.digiroad2.postgis.MassQuery
import fi.liikennevirasto.digiroad2.util.LogUtils.time
import fi.liikennevirasto.digiroad2.util.Track
import fi.liikennevirasto.viite._
import fi.liikennevirasto.viite.dao.CalibrationPointDAO.CalibrationPointType
import fi.liikennevirasto.viite.dao.CalibrationPointDAO.CalibrationPointType.NoCP
import fi.liikennevirasto.viite.dao.ProjectCalibrationPointDAO.BaseCalibrationPoint
import fi.liikennevirasto.viite.dao.TerminationCode.NoTermination
import fi.liikennevirasto.viite.model.RoadAddressLinkLike
import fi.liikennevirasto.viite.process.InvalidAddressDataException
import org.joda.time.DateTime
import org.joda.time.format.{DateTimeFormatter, ISODateTimeFormat}
import org.slf4j.LoggerFactory
import slick.driver.JdbcDriver.backend.Database.dynamicSession
import slick.jdbc.{GetResult, PositionedResult, StaticQuery => Q}
import slick.jdbc.StaticQuery.interpolation

//JATKUVUUS (1 = Tien loppu, 2 = epäjatkuva (esim. vt9 välillä Akaa-Tampere), 3 = ELY:n raja, 4 = Lievä epäjatkuvuus (esim kiertoliittymä), 5 = jatkuva)
sealed trait Discontinuity {
  def value: Int

  def description: String

  override def toString: String = s"$value - $description"
}

object Discontinuity {
  val values = Set(EndOfRoad, Discontinuous, ChangingELYCode, MinorDiscontinuity, Continuous, ParallelLink)

  def apply(intValue: Int): Discontinuity = {
    values.find(_.value == intValue).getOrElse(Continuous)
  }

  def apply(longValue: Long): Discontinuity = {
    apply(longValue.toInt)
  }

  def apply(s: String): Discontinuity = {
    values.find(_.description.equalsIgnoreCase(s)).getOrElse(Continuous)
  }

  case object EndOfRoad extends Discontinuity {
    def value = 1

    def description = "Tien loppu"
  }

  case object Discontinuous extends Discontinuity {
    def value = 2

    def description = "Epäjatkuva"
  }

  case object ChangingELYCode extends Discontinuity {
    def value = 3

    def description = "ELY:n raja"
  }

  case object MinorDiscontinuity extends Discontinuity {
    def value = 4

    def description = "Lievä epäjatkuvuus"
  }

  case object Continuous extends Discontinuity {
    def value = 5

    def description = "Jatkuva"
  }

  case object ParallelLink extends Discontinuity {
    def value = 6

    def description = "Parallel Link"
  }

  def replaceParallelLink(currentDiscontinuity: Discontinuity): Discontinuity = {
    if (currentDiscontinuity == ParallelLink)
      Continuous
    else currentDiscontinuity
  }

}

sealed trait CalibrationCode {
  def value: Int
}

object CalibrationCode {
  val values = Set(No, AtEnd, AtBeginning, AtBoth)

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

case class CalibrationPoint(linkId: String, segmentMValue: Double, addressMValue: Long, typeCode: CalibrationPointType = CalibrationPointType.UnknownCP) extends BaseCalibrationPoint {
  def this(linkId: Long, segmentMValue: Double, addressMValue: Long, typeCode: CalibrationPointType) =
   this(linkId.toString, segmentMValue, addressMValue, typeCode)
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

  def roadNumber: Long

  def roadPartNumber: Long

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
    startCalibrationPoint.getOrElse(NoCP) != NoCP
  }

  def hasCalibrationPointAtEnd: Boolean = {
    endCalibrationPoint.getOrElse(NoCP) != NoCP
  }

  def liesInBetween(ra: BaseRoadAddress): Boolean = {
    (startAddrMValue >= ra.startAddrMValue && startAddrMValue <= ra.endAddrMValue) || (endAddrMValue <= ra.endAddrMValue && endAddrMValue >= ra.startAddrMValue)
  }

  def connected(ra2: BaseRoadAddress): Boolean = {
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

  def connected(p: Point): Boolean = {
    val currEndPoint = sideCode match {
      case AgainstDigitizing => geometry.head
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
  lazy val endCalibrationPoint: Option[BaseCalibrationPoint] = calibrationPoints._2
}

//TODO the start date and the created by should not be optional on the road address case class
// Note: Geometry on road address is not directed: it isn't guaranteed to have a direction of digitization or road addressing
case class RoadAddress(id: Long, linearLocationId: Long, roadNumber: Long, roadPartNumber: Long, administrativeClass: AdministrativeClass, track: Track, discontinuity: Discontinuity, startAddrMValue: Long, endAddrMValue: Long, startDate: Option[DateTime] = None, endDate: Option[DateTime] = None, createdBy: Option[String] = None, linkId: String, startMValue: Double, endMValue: Double, sideCode: SideCode, adjustedTimestamp: Long, calibrationPoints: (Option[CalibrationPoint], Option[CalibrationPoint]) = (None, None), geometry: Seq[Point], linkGeomSource: LinkGeomSource, ely: Long, terminated: TerminationCode = NoTermination, roadwayNumber: Long, validFrom: Option[DateTime] = None, validTo: Option[DateTime] = None, roadName: Option[String] = None) extends BaseRoadAddress {
  def this(id: Long, linearLocationId: Long, roadNumber: Long, roadPartNumber: Long, administrativeClass: AdministrativeClass, track: Track, discontinuity: Discontinuity, startAddrMValue: Long, endAddrMValue: Long, startDate: Option[DateTime], endDate: Option[DateTime], createdBy: Option[String], linkId: Long, startMValue: Double, endMValue: Double, sideCode: SideCode, adjustedTimestamp: Long, calibrationPoints: (Option[CalibrationPoint], Option[CalibrationPoint]), geometry: Seq[Point], linkGeomSource: LinkGeomSource, ely: Long, terminated: TerminationCode, roadwayNumber: Long, validFrom: Option[DateTime], validTo: Option[DateTime], roadName: Option[String]) =
   this(id, linearLocationId, roadNumber, roadPartNumber, administrativeClass, track, discontinuity, startAddrMValue, endAddrMValue, startDate, endDate, createdBy, linkId.toString, startMValue, endMValue, sideCode, adjustedTimestamp, calibrationPoints, geometry, linkGeomSource, ely, terminated, roadwayNumber, validFrom, validTo, roadName)

  override lazy val startCalibrationPoint: Option[CalibrationPoint] = calibrationPoints._1
  override lazy val endCalibrationPoint: Option[CalibrationPoint] = calibrationPoints._2

  def startCalibrationPointType: CalibrationPointType = startCalibrationPoint match {
    case Some(cp) => cp.typeCode
    case None => NoCP
  }
  def endCalibrationPointType: CalibrationPointType = endCalibrationPoint match {
    case Some(cp) => cp.typeCode
    case None => NoCP
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

case class Roadway(id: Long, roadwayNumber: Long, roadNumber: Long, roadPartNumber: Long, administrativeClass: AdministrativeClass, track: Track, discontinuity: Discontinuity, startAddrMValue: Long, endAddrMValue: Long, reversed: Boolean = false, startDate: DateTime, endDate: Option[DateTime] = None, createdBy: String, roadName: Option[String], ely: Long, terminated: TerminationCode = NoTermination, validFrom: DateTime = DateTime.now(), validTo: Option[DateTime] = None)

case class RoadForRoadAddressBrowser(ely: Long, roadNumber: Long, track: Long, roadPartNumber: Long, startAddressM: Long, endAddrM: Long, roadAddressLengthM: Long, startDate: DateTime)

class BaseDAO {
  protected def logger = LoggerFactory.getLogger(getClass)

  protected val formatter: DateTimeFormatter = ISODateTimeFormat.dateOptionalTimeParser()

  val basicDateFormatter: DateTimeFormatter = ISODateTimeFormat.basicDate()

}

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
    * Fetch all the current road addresses by road number and road part
    *
    * @param roadNumber     The road number
    * @param roadPartNumber The road part number
    * @return Current road addresses at road address section
    */
  def fetchAllBySection(roadNumber: Long, roadPartNumber: Long): Seq[Roadway] = {
    time(logger, "Fetch road address by road number and road part number") {
      fetch(withSection(roadNumber, roadPartNumber))
    }
  }

  /**
    * Fetch all the current road addresses by road number, road part number and track codes
    *
    * @param roadNumber     The road number
    * @param roadPartNumber The road part number
    * @param tracks         The set of track codes
    * @return Current road addresses at specified section
    */
  def fetchAllBySectionAndTracks(roadNumber: Long, roadPartNumber: Long, tracks: Set[Track]): Seq[Roadway] = {
    time(logger, "Fetch roadway by road number, road part number and tracks") {
      if (tracks == null || tracks.isEmpty) {
        Seq()
      } else {
        fetch(withSectionAndTracks(roadNumber, roadPartNumber, tracks))
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
    * Will get a collection of Roadways from our database based on the road number, road part number given.
    * Also has query modifiers that will inform if it should return history roadways (default value no) or if should get only the end parts (max end address m value, default value no).
    *
    * @param roadNumber   : Long - Road Number
    * @param roadPart     : Long - Road Part Number
    * @param withHistory  : Boolean - Query modifier, indicates if should fetch history roadways or not
    * @param fetchOnlyEnd : Boolean - Query modifier, indicates if should fetch only the end parts or not
    * @return
    */
  def fetchAllByRoadAndPart(roadNumber: Long, roadPart: Long, withHistory: Boolean = false, fetchOnlyEnd: Boolean = false): Seq[Roadway] = {
    time(logger, "Fetch roadway by road number and part") {
      fetch(withRoadAndPart(roadNumber, roadPart, withHistory, fetchOnlyEnd))
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

  def fetchAllByRoadwayNumbers(roadwayNumbers: Set[Long], roadNetworkId: Long, searchDate: Option[DateTime]) : Seq[Roadway] = {
    time(logger, "Fetch all current road addresses by roadway ids and road network id") {
      if (roadwayNumbers.isEmpty)
        Seq()
      else
        fetch(withRoadwayNumbersAndRoadNetwork(roadwayNumbers, roadNetworkId, searchDate))
    }
  }

  def fetchAllByBetweenRoadNumbers(roadNumbers: (Int, Int)): Seq[Roadway] = {
    time(logger, "Fetch all current road addresses by given road numbers") {
      fetch(betweenRoadNumbers(roadNumbers))
    }
  }

  def fetchAllBySectionTrackAndAddresses(roadNumber: Long, roadPartNumber: Long, track: Track, startAddrM: Option[Long], endAddrM: Option[Long]): Seq[Roadway] = {
    time(logger, "Fetch road address by road number, road part number, start address measure and end address measure") {
      fetch(withSectionTrackAndAddresses(roadNumber, roadPartNumber, track, startAddrM, endAddrM))
    }
  }

  def fetchAllBySectionAndAddresses(roadNumber: Long, roadPartNumber: Long, startAddrM: Option[Long], endAddrM: Option[Long], track: Option[Long] = None): Seq[Roadway] = {
    time(logger, "Fetch road address by road number, road part number, start address measure and end address measure") {
      fetch(withSectionAndAddresses(roadNumber, roadPartNumber, startAddrM, endAddrM, track))
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

  private def withSection(roadNumber: Long, roadPartNumber: Long)(query: String): String = {
    s"""$query where valid_to is null and end_date is null and road_number = $roadNumber and road_part_number = $roadPartNumber"""
  }

  private def withSectionAndTracks(roadNumber: Long, roadPartNumber: Long, tracks: Set[Track])(query: String): String = {
    s"""$query where valid_to is null and end_date is null and road_number = $roadNumber and road_part_number = $roadPartNumber and TRACK in (${tracks.mkString(",")})"""
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
    * @param roadNumber     : Long - Road Number
    * @param roadPart       : Long - Road Part Number
    * @param includeHistory : Boolean - Query modifier, indicates if should fetch history roadways or not
    * @param fetchOnlyEnd   : Boolean - Query modifier, indicates if should fetch only the end parts or not
    * @param query          : String - The actual SQL query string
    * @return
    */
  private def withRoadAndPart(roadNumber: Long, roadPart: Long, includeHistory: Boolean = false, fetchOnlyEnd: Boolean = false)(query: String): String = {
    val historyFilter = if (!includeHistory)
      " AND end_date is null"
    else
      ""

    val endPart = if (fetchOnlyEnd) {
      s" AND a.end_addr_m = (Select max(road.end_addr_m) from roadway road Where road.road_number = $roadNumber And road.road_part_number = $roadPart And (road.valid_to IS NULL AND road.end_date is null))"
    } else ""
    s"""$query where valid_to is null AND road_number = $roadNumber AND Road_Part_Number = $roadPart $historyFilter $endPart"""
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

  private def withRoadwayNumbersAndRoadNetwork(roadwayNumbers: Set[Long], roadNetworkId: Long, searchDate: Option[DateTime])(query: String): String = {
    val queryDate = if (searchDate.isDefined) s"to_date('${searchDate.get.toString("yyyy-MM-dd")}', 'YYYY-MM-DD') " else "CURRENT_DATE"

    if (roadwayNumbers.size > 1000) {
      val groupsOf1000 = roadwayNumbers.grouped(1000).toSeq
      val groupedRoadwayNumbers = groupsOf1000.map(group => {
        s"""in (${group.mkString(",")})"""
      }).mkString("", " or a.roadway_number ", "")

      s"""$query
         join published_roadway net on net.ROADWAY_ID = a.id
         where net.network_id = $roadNetworkId and a.valid_to is null and (a.roadway_number $groupedRoadwayNumbers)
            and a.start_date <= $queryDate and (a.end_date is null or a.end_date >= $queryDate)"""
    }
    else
      s"""$query
         join published_roadway net on net.ROADWAY_ID = a.id
         where net.network_id = $roadNetworkId and a.valid_to is null and a.roadway_number in (${roadwayNumbers.mkString(",")})
            and a.start_date <= $queryDate and (a.end_date is null or a.end_date >= $queryDate)"""

  }

  private def withRoadwayNumbersAndDate(roadwayNumbers: Set[Long], searchDate: DateTime)(query: String): String = {
    def dateFilter(table: String): String = {
      val strDate = dateFormatter.print(searchDate)
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

  private def withSectionAndAddresses(roadNumber: Long, roadPartNumber: Long, startAddrMOption: Option[Long], endAddrMOption: Option[Long], track: Option[Long] = None)(query: String) = {
    val trackFilter = track.map(t => s"""and a.track = $t""").getOrElse(s"""""")
    val addressFilter = (startAddrMOption, endAddrMOption) match {
      case (Some(startAddrM), Some(endAddrM)) => s"""and ((a.start_addr_m >= $startAddrM and a.end_addr_m <= $endAddrM) or (a.start_addr_m <= $startAddrM and a.end_addr_m > $startAddrM) or (a.start_addr_m < $endAddrM and a.end_addr_m >= $endAddrM)) $trackFilter"""
      case (Some(startAddrM), _) => s"""and a.end_addr_m > $startAddrM $trackFilter"""
      case (_, Some(endAddrM)) => s"""and a.start_addr_m < $endAddrM $trackFilter"""
      case _ => s""""""
    }
    s"""$query where valid_to is null and end_date is null and road_number = $roadNumber and road_part_number = $roadPartNumber $addressFilter"""
  }

  private def withSectionTrackAndAddresses(roadNumber: Long, roadPartNumber: Long, track: Track, startAddrMOption: Option[Long], endAddrMOption: Option[Long])(query: String) = {
    val addressFilter = (startAddrMOption, endAddrMOption) match {
      case (Some(startAddrM), Some(endAddrM)) => s"""and ((a.start_addr_m >= $startAddrM and a.end_addr_m <= $endAddrM) or (a.start_addr_m <= $startAddrM and a.end_addr_m > $startAddrM) or (a.start_addr_m < $endAddrM and a.end_addr_m >= $endAddrM))"""
      case (Some(startAddrM), _) => s"""and a.end_addr_m > $startAddrM"""
      case (_, Some(endAddrM)) => s"""and a.start_addr_m < $endAddrM"""
      case _ => s""""""
    }
    s"""$query where valid_to is null and end_date is null and road_number = $roadNumber and road_part_number = $roadPartNumber and TRACK = $track $addressFilter"""
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
      val roadNumber = r.nextLong()
      val roadPartNumber = r.nextLong()
      val trackCode = r.nextInt()
      val startAddrMValue = r.nextLong()
      val endAddrMValue = r.nextLong()
      val reverted = r.nextBoolean()
      val discontinuity = r.nextInt()
      val startDate = formatter.parseDateTime(r.nextDate.toString)
      val endDate = r.nextDateOption.map(d => formatter.parseDateTime(d.toString))
      val createdBy = r.nextString()
      val administrativeClass = AdministrativeClass.apply(r.nextInt())
      val ely = r.nextLong()
      val terminated = TerminationCode.apply(r.nextInt())
      val validFrom = formatter.parseDateTime(r.nextDate.toString)
      val validTo = r.nextDateOption.map(d => formatter.parseDateTime(d.toString))
      val roadName = r.nextStringOption()

      Roadway(id, roadwayNumber, roadNumber, roadPartNumber, administrativeClass, Track.apply(trackCode), Discontinuity.apply(discontinuity), startAddrMValue, endAddrMValue, reverted, startDate, endDate, createdBy, roadName, ely, terminated, validFrom, validTo)
    }
  }

  private implicit val getRoadForRoadAddressBrowser: GetResult[RoadForRoadAddressBrowser] = new GetResult[RoadForRoadAddressBrowser] {
    def apply(r: PositionedResult) = {

      val ely = r.nextLong()
      val roadNumber = r.nextLong()
      val trackCode = r.nextInt()
      val roadPartNumber = r.nextLong()
      val startAddrMValue = r.nextLong()
      val endAddrMValue = r.nextLong()
      val lengthAddrM = r.nextLong()
      val startDate = formatter.parseDateTime(r.nextDate.toString)

      RoadForRoadAddressBrowser(ely, roadNumber, trackCode, roadPartNumber, startAddrMValue, endAddrMValue, lengthAddrM, startDate)
    }
  }

  /**
    * Full SQL query to return, if existing, a road part number that is < than the supplied current one.
    *
    * @param roadNumber : Long - Roadway Road Number
    * @param current    : Long - Roadway Road Part Number
    * @return
    */
  def fetchPreviousRoadPartNumber(roadNumber: Long, current: Long): Option[Long] = {
    val query =
      s"""
            SELECT * FROM (
              SELECT ra.road_part_number
              FROM ROADWAY ra
              WHERE road_number = $roadNumber AND road_part_number < $current
                AND valid_to IS NULL AND end_date IS NULL
              ORDER BY road_part_number DESC
            ) AS PREVIOUS
            LIMIT 1
        """
    Q.queryNA[Long](query).firstOption
  }

  val dateFormatter: DateTimeFormatter = ISODateTimeFormat.basicDate()

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
      Q.updateNA(query).first
    }
  }

  /**
   * Flip reversed tags to be the opposite value (0 -> 1, 1 -> 0) in each history row of the road thats reversed
   * @param ids : Seq[Long] - The ids of the roadway rows of which reversed tags should be flipped
   * @return
   */

  def updateReversedTagsInHistoryRows(ids: Set[Long]): Int = {
    val query =
      s"""
          UPDATE ROADWAY
          SET reversed = CASE
          WHEN reversed = 0 THEN 1
          WHEN reversed = 1 THEN 0
          END
          WHERE valid_to IS NULL AND end_date IS NOT NULL AND id IN (${ids.mkString(",")})
      """
    if (ids.isEmpty)
      0

    else {
      Q.updateNA(query).first
    }
  }

  /**
   * Fetches all road_part_numbers for roadNumber that aren't reserved in another project where
   * each ProjectLink with road_part_number would have status=LinkStatus.Terminated
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

  def getValidRoadNumbers: List[Long] = {
    sql"""
       select distinct road_number
              from ROADWAY
              where valid_to IS NULL AND (end_date is NULL or end_date >= current_date) order by road_number
      """.as[Long].list
  }

  def getValidBetweenRoadNumbers(roadNumbers: (Long, Long)): List[Long] = {
    sql"""
       select distinct road_number
              from ROADWAY
              where valid_to IS NULL AND (end_date is NULL or end_date >= current_date) AND road_number BETWEEN ${roadNumbers._1} AND ${roadNumbers._2}
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
      roadwayPS.setLong(3, address.roadNumber)
      roadwayPS.setLong(4, address.roadPartNumber)
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
  def getRoadPartInfo(roadNumber: Long, roadPart: Long): Option[(Long, String, Long, Long, Long, Option[DateTime], Option[DateTime])] = {
    val query =
      s"""SELECT r.id, l.link_id, r.end_addr_M, r.discontinuity, r.ely,
            (Select Max(ra.start_date) from ROADWAY ra Where r.ROAD_PART_NUMBER = ra.ROAD_PART_NUMBER and r.ROAD_NUMBER = ra.ROAD_NUMBER) as start_date,
            (Select Max(ra.end_Date) from ROADWAY ra Where r.ROAD_PART_NUMBER = ra.ROAD_PART_NUMBER and r.ROAD_NUMBER = ra.ROAD_NUMBER) as end_date
          FROM ROADWAY r
            INNER JOIN LINEAR_LOCATION l on r.ROADWAY_NUMBER = l.ROADWAY_NUMBER
            INNER JOIN (Select  MAX(rm.start_addr_m) as maxstartaddrm FROM ROADWAY rm WHERE rm.road_number=$roadNumber AND rm.road_part_number=$roadPart AND
              rm.valid_to is null AND rm.end_date is null AND rm.TRACK in (0,1)) ra
              on r.START_ADDR_M=ra.maxstartaddrm
          WHERE r.road_number=$roadNumber AND r.road_part_number=$roadPart AND
            r.valid_to is null AND r.end_date is null AND r.TRACK in (0,1)"""
    Q.queryNA[(Long, String, Long, Long, Long, Option[DateTime], Option[DateTime])](query).firstOption
  }

  def fetchRoadsForRoadAddressBrowser(startDate: Option[String], ely: Option[Long], roadNumber: Option[Long], minRoadPartNumber: Option[Long], maxRoadPartNumber: Option[Long]): Seq[RoadForRoadAddressBrowser] = {
    def withOptionalParameters(startDate: Option[String], ely: Option[Long], roadNumber: Option[Long], minRoadPartNumber: Option[Long], maxRoadPartNumber: Option[Long])(query: String): String  = {

      val dateCondition = "AND start_date <='" + startDate.get + "'"

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

      /** Use two subqueries: mins and maxs
        * mins: Select roadways that don't have another roadway BEFORE it (r2.end_addr_m = r.start_addr_m...). Then set row numbers for these roadways.
        * maxs: Select roadways that don't have another roadway AFTER it (r2.start_addr_m = r.end_addr_m...). Then set row numbers for these roadways as well
        * Now we have two ROW NUMBERED lists: mins and maxs. Mins has the homogenous section's startAddrM's and maxs has the endAddrM's.
        * Joining these lists by the row numbers will give us one list that tells us the startAddrM and endAddrM of the homogenous section.
        * */

      s"""$query FROM (SELECT ely, road_number, road_part_number, start_addr_m, start_date, track, ROW_NUMBER() OVER (ORDER BY road_number, road_part_number, start_addr_m, track) AS numb
                        FROM roadway r
                        WHERE NOT EXISTS
                          (SELECT 1 FROM roadway r2 WHERE r2.end_addr_m = r.start_addr_m AND r2.road_number = r.road_number AND r2.road_part_number=r.road_part_number AND r2.track = r.track AND r2.start_date = r.start_date AND r2.valid_to IS NULL AND r2.end_date IS NULL)
                        AND r.valid_to IS NULL AND r.end_date IS NULL $dateCondition $elyCondition $roadNumberCondition $roadPartCondition) mins
                   JOIN (SELECT ely, road_number, road_part_number, end_addr_m, start_date, track, ROW_NUMBER() OVER (ORDER BY road_number, road_part_number, end_addr_m, track) AS numb
                              FROM roadway r
                              WHERE NOT EXISTS
                                (SELECT 1 FROM roadway r2 WHERE r2.start_addr_m = r.end_addr_m AND r2.road_number = r.road_number AND r2.road_part_number=r.road_part_number AND r2.track=r.track AND r2.start_date = r.start_date AND r2.valid_to IS NULL AND r2.end_date IS NULL)
                              AND r.valid_to IS NULL and r.end_date IS NULL $dateCondition $elyCondition $roadNumberCondition $roadPartCondition) maxs ON mins.numb = maxs.numb
                  ORDER BY mins.road_number, mins.road_part_number, mins.start_addr_m""".stripMargin
    }

    def fetchRoads(queryFilter: String => String): Seq[RoadForRoadAddressBrowser] = {
      val query =
        """
      SELECT mins.ely, mins.road_number, mins.track, mins.road_part_number, mins.start_addr_m,
       maxs.end_addr_m, maxs.end_addr_m - mins.start_addr_m AS length, mins.start_date
      """
      val filteredQuery = queryFilter(query)
      Q.queryNA[RoadForRoadAddressBrowser](filteredQuery).iterator.toSeq
    }

    fetchRoads(withOptionalParameters(startDate, ely, roadNumber, minRoadPartNumber, maxRoadPartNumber))
  }


}

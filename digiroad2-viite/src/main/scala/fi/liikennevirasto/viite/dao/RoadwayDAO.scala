package fi.liikennevirasto.viite.dao

import com.github.tototoshi.slick.MySQLJodaSupport._
import fi.liikennevirasto.digiroad2.asset.SideCode.AgainstDigitizing
import fi.liikennevirasto.digiroad2.asset.{LinkGeomSource, SideCode}
import fi.liikennevirasto.digiroad2.dao.{Queries, Sequences}
import fi.liikennevirasto.digiroad2.oracle.MassQuery
import fi.liikennevirasto.digiroad2.util.LogUtils.time
import fi.liikennevirasto.digiroad2.util.Track
import fi.liikennevirasto.digiroad2.{GeometryUtils, Point, Vector3d}
import fi.liikennevirasto.viite.AddressConsistencyValidator.{AddressError, AddressErrorDetails}
import fi.liikennevirasto.viite._
import fi.liikennevirasto.viite.dao.ProjectCalibrationPointDAO.BaseCalibrationPoint
import fi.liikennevirasto.viite.dao.CalibrationPointSource.{ProjectLinkSource, RoadAddressSource}
import fi.liikennevirasto.viite.dao.TerminationCode.NoTermination
import fi.liikennevirasto.viite.model.RoadAddressLinkLike
import fi.liikennevirasto.viite.dao.TerminationCode.NoTermination
import fi.liikennevirasto.viite.model.{Anomaly, RoadAddressLinkLike}
import fi.liikennevirasto.viite.model.{Anomaly, RoadAddressLinkLike}
import fi.liikennevirasto.viite.process.InvalidAddressDataException
import org.joda.time.DateTime
import org.joda.time.format.{DateTimeFormatter, ISODateTimeFormat}
import org.slf4j.LoggerFactory
import slick.driver.JdbcDriver.backend.Database.dynamicSession
import slick.jdbc.StaticQuery.interpolation
import slick.jdbc.{GetResult, PositionedResult, StaticQuery => Q}

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
    fromBooleans(roadAddress.calibrationPoints._1.isDefined, roadAddress.calibrationPoints._2.isDefined)
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

}

case class CalibrationPoint(linkId: Long, segmentMValue: Double, addressMValue: Long) extends BaseCalibrationPoint

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

  def roadType: RoadType

  def startAddrMValue: Long

  def endAddrMValue: Long

  def startDate: Option[DateTime]

  def endDate: Option[DateTime]

  def createdBy: Option[String]

  def linkId: Long

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
}

//TODO the start date and the created by should not be optional on the road address case class
// Note: Geometry on road address is not directed: it isn't guaranteed to have a direction of digitization or road addressing
case class RoadAddress(id: Long, linearLocationId: Long, roadNumber: Long, roadPartNumber: Long, roadType: RoadType, track: Track,
                       discontinuity: Discontinuity, startAddrMValue: Long, endAddrMValue: Long,
                       startDate: Option[DateTime] = None, endDate: Option[DateTime] = None, createdBy: Option[String] = None,
                       linkId: Long, startMValue: Double, endMValue: Double, sideCode: SideCode,
                       adjustedTimestamp: Long, calibrationPoints: (Option[CalibrationPoint], Option[CalibrationPoint]) = (None, None),
                       geometry: Seq[Point], linkGeomSource: LinkGeomSource, ely: Long,
                       terminated: TerminationCode = NoTermination, roadwayNumber: Long, validFrom: Option[DateTime] = None, validTo: Option[DateTime] = None,
                       roadName: Option[String] = None) extends BaseRoadAddress {

  val endCalibrationPoint = calibrationPoints._2
  val startCalibrationPoint = calibrationPoints._1

  def reversed: Boolean = false

  def isBetweenAddresses(rangeStartAddr: Long, rangeEndAddr: Long): Boolean = {
    (startAddrMValue <= rangeStartAddr && endAddrMValue >= rangeStartAddr) ||
      (startAddrMValue <= rangeEndAddr && endAddrMValue >= rangeEndAddr) ||
      (rangeStartAddr < startAddrMValue && rangeEndAddr > endAddrMValue)
  }

  def isBetweenMeasures(rangeStartMeasure: Double, rangeEndMeasure: Double): Boolean = {
    (startMValue <= rangeStartMeasure && endAddrMValue >= rangeStartMeasure) ||
      (startMValue <= rangeEndMeasure && endAddrMValue >= rangeEndMeasure) ||
      (rangeStartMeasure < startMValue && rangeEndMeasure > endMValue)
  }

  def isBetweenMeasures(measure: Double): Boolean = {
    startMValue < measure && endMValue > measure
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

  def toProjectLinkCalibrationPoints(): (Option[ProjectLinkCalibrationPoint], Option[ProjectLinkCalibrationPoint]) = {
    val calibrationPointSource = if (id == noRoadwayId || id == NewIdValue) ProjectLinkSource else RoadAddressSource
    calibrationPoints match {
      case (None, None) => (Option.empty[ProjectLinkCalibrationPoint], Option.empty[ProjectLinkCalibrationPoint])
      case (None, Some(cp1)) => (Option.empty[ProjectLinkCalibrationPoint], Option(ProjectLinkCalibrationPoint(cp1.linkId, cp1.segmentMValue, cp1.addressMValue, calibrationPointSource)))
      case (Some(cp1), None) => (Option(ProjectLinkCalibrationPoint(cp1.linkId, cp1.segmentMValue, cp1.addressMValue, calibrationPointSource)), Option.empty[ProjectLinkCalibrationPoint])
      case (Some(cp1), Some(cp2)) => (Option(ProjectLinkCalibrationPoint(cp1.linkId, cp1.segmentMValue, cp1.addressMValue, calibrationPointSource)), Option(ProjectLinkCalibrationPoint(cp2.linkId, cp2.segmentMValue, cp2.addressMValue, calibrationPointSource)))
    }
  }

  def getFirstPoint: Point = {
    if (sideCode == SideCode.TowardsDigitizing) geometry.head else geometry.last
  }

  def getLastPoint: Point = {
    if (sideCode == SideCode.TowardsDigitizing) geometry.last else geometry.head
  }

}

case class Roadway(id: Long, roadwayNumber: Long, roadNumber: Long, roadPartNumber: Long, roadType: RoadType, track: Track,
                   discontinuity: Discontinuity, startAddrMValue: Long, endAddrMValue: Long, reversed: Boolean = false,
                   startDate: DateTime, endDate: Option[DateTime] = None, createdBy: String, roadName: Option[String],
                   ely: Long, terminated: TerminationCode = NoTermination, validFrom: DateTime = DateTime.now(),
                   validTo: Option[DateTime] = None)


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
      if (tracks.isEmpty) {
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
    time(logger, "Fetch road ways by road number") {
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

  def fetchAllByRoadwayNumbers(roadwayNumbers: Set[Long], roadNetworkId: Long): Seq[Roadway] = {
    time(logger, "Fetch all current road addresses by roadway ids and road network id") {
      if (roadwayNumbers.isEmpty)
        Seq()
      else
        fetch(withRoadwayNumbersAndRoadNetwork(roadwayNumbers, roadNetworkId))
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

  def fetchAllBySectionAndAddresses(roadNumber: Long, roadPartNumber: Long, startAddrM: Option[Long], endAddrM: Option[Long]): Seq[Roadway] = {
    time(logger, "Fetch road address by road number, road part number, start address measure and end address measure") {
      fetch(withSectionAndAddresses(roadNumber, roadPartNumber, startAddrM, endAddrM))
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
    time(logger, "Fetch road ways by id") {
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
      where ra.valid_to is null and (ra.end_date is null or ra.end_date >= sysdate)
		  """.as[Long].list.toSet
    }
  }

  // TODO Can we really have errors in history? Do we need includesHistory -parameter?
  def fetchAllRoadAddressErrors(includesHistory: Boolean = false): List[AddressErrorDetails] = {
    time(logger, s"Fetch all road address errors (includesHistory: $includesHistory)") {
      val history = if (!includesHistory) s" where ra.end_date is null " else ""
      val query =
        s"""
        select
        	ll.id, ll.link_id, ra.road_number, ra.road_part_number, re.error_code, ra.ely
        from ROADWAY ra
        join linear_location ll on ll.ROADWAY_NUMBER = ra.ROADWAY_NUMBER
        join road_network_error re on re.ROADWAY_ID = ra.id and re.linear_location_id = ll.id $history
        order by ra.ely, ra.road_number, ra.road_part_number, re.error_code
      """
      Q.queryNA[(Long, Long, Long, Long, Int, Long)](query).list.map {
        case (id, linkId, roadNumber, roadPartNumber, errorCode, ely) =>
          AddressErrorDetails(id, linkId, roadNumber, roadPartNumber, AddressError.apply(errorCode), ely)
      }
    }
  }

  private def fetch(queryFilter: String => String): Seq[Roadway] = {
    val query =
      """
        select
          a.id, a.ROADWAY_NUMBER, a.road_number, a.road_part_number, a.TRACK, a.start_addr_m, a.end_addr_m,
          a.reversed, a.discontinuity, a.start_date, a.end_date, a.created_by, a.road_type, a.ely, a.terminated,
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
      MassQuery.withIds(roadNumbers) {
        idTableName =>
          s"""
            $query
            join $idTableName i on i.id = a.ROAD_NUMBER
            where a.valid_to is null AND (a.end_date is null or a.end_date >= sysdate) order by a.road_number, a.road_part_number, a.start_date
          """.stripMargin
      }
    } else {
      s"""$query where a.valid_to is null AND (a.end_date is null or a.end_date >= sysdate) AND a.road_number in (${roadNumbers.mkString(",")}) order by a.road_number, a.road_part_number, a.start_date"""
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

  private def withRoadwayNumbersAndRoadNetwork(roadwayNumbers: Set[Long], roadNetworkId: Long)(query: String): String = {
    if (roadwayNumbers.size > 1000) {
      val groupsOf1000 = roadwayNumbers.grouped(1000).toSeq
      val groupedRoadwayNumbers = groupsOf1000.map(group => {
        s"""in (${group.mkString(",")})"""
      }).mkString("", " or a.roadway_number ", "")

      s"""$query
         join published_roadway net on net.ROADWAY_ID = a.id
         where net.network_id = $roadNetworkId and a.valid_to is null and (a.roadway_number $groupedRoadwayNumbers)
            and a.start_date <= CURRENT_DATE and (a.end_date is null or a.end_date >= CURRENT_DATE)"""
    }
    else
      s"""$query
         join published_roadway net on net.ROADWAY_ID = a.id
         where net.network_id = $roadNetworkId and a.valid_to is null and a.roadway_number in (${roadwayNumbers.mkString(",")})
            and a.start_date <= CURRENT_DATE and (a.end_date is null or a.end_date >= CURRENT_DATE)"""

  }

  private def withRoadwayNumbersAndDate(roadwayNumbers: Set[Long], searchDate: DateTime)(query: String): String = {
    def dateFilter(table: String): String = {
      val strDate = dateFormatter.print(searchDate)
      s" ($table.start_date <= to_date('$strDate', 'yyyymmdd') and (to_date('$strDate', 'yyyymmdd') <= $table.end_date or $table.end_date is null))"
    }

    if (roadwayNumbers.size > 1000) {
      MassQuery.withIds(roadwayNumbers) {
        idTableName =>
          s"""
            $query
            join $idTableName i on i.id = a.ROADWAY_NUMBER
            where a.valid_to is null and ${dateFilter(table = "a")}
          """.stripMargin
      }
    }
    else
      s"""$query where a.valid_to is null and ${dateFilter(table = "a")} and a.roadway_number in (${roadwayNumbers.mkString(",")})"""
  }

  private def withSectionAndAddresses(roadNumber: Long, roadPartNumber: Long, startAddrMOption: Option[Long], endAddrMOption: Option[Long])(query: String) = {
    val addressFilter = (startAddrMOption, endAddrMOption) match {
      case (Some(startAddrM), Some(endAddrM)) => s"""and ((a.start_addr_m >= $startAddrM and a.end_addr_m <= $endAddrM) or (a.start_addr_m <= $startAddrM and a.end_addr_m > $startAddrM) or (a.start_addr_m < $endAddrM and a.end_addr_m >= $endAddrM))"""
      case (Some(startAddrM), _) => s"""and a.end_addr_m > $startAddrM"""
      case (_, Some(endAddrM)) => s"""and a.start_addr_m < $endAddrM"""
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
    s"""$query where a.valid_to is null and end_date is null and a.ROADWAY_NUMBER = $roadwayNumber"""
  }

  private def withRoadwayNumberEnded(roadwayNumber: Long)(query: String): String = {
    s"""$query where a.valid_to is null and a.ROADWAY_NUMBER = $roadwayNumber"""
  }

  private def withRoadwayNumbers(roadwayNumbers: Set[Long], withHistory: Boolean = false)(query: String): String = {
    val endDateFilter = if (withHistory) "" else "and a.end_date is null"
    if (roadwayNumbers.size > 1000) {
      MassQuery.withIds(roadwayNumbers) {
        idTableName =>
          s"""
            $query
            join $idTableName i on i.id = a.ROADWAY_NUMBER
            where a.valid_to is null $endDateFilter
          """.stripMargin
      }
    } else {
      s"""$query where a.valid_to is null $endDateFilter and a.ROADWAY_NUMBER in (${roadwayNumbers.mkString(",")})"""
    }
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
    def apply(r: PositionedResult) = {

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
      val roadType = RoadType.apply(r.nextInt())
      val ely = r.nextLong()
      val terminated = TerminationCode.apply(r.nextInt())
      val validFrom = formatter.parseDateTime(r.nextDate.toString)
      val validTo = r.nextDateOption.map(d => formatter.parseDateTime(d.toString))
      val roadName = r.nextStringOption()

      Roadway(id, roadwayNumber, roadNumber, roadPartNumber, roadType, Track.apply(trackCode), Discontinuity.apply(discontinuity),
        startAddrMValue, endAddrMValue, reverted, startDate, endDate, createdBy, roadName, ely, terminated, validFrom, validTo)
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
            ) WHERE ROWNUM < 2
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
        Update ROADWAY Set valid_to = sysdate where valid_to IS NULL and id in (${ids.mkString(",")})
      """
    if (ids.isEmpty)
      0
    else
      Q.updateNA(query).first
  }

  def getValidRoadParts(roadNumber: Long, startDate: DateTime): List[Long] = {
    sql"""
       select distinct ra.road_part_number
              from ROADWAY ra
              where road_number = $roadNumber AND valid_to IS NULL AND START_DATE <= $startDate
              AND END_DATE IS NULL
              AND ra.road_part_number NOT IN (select distinct pl.road_part_number from project_link pl where (select count(distinct pl2.status) from project_link pl2 where pl2.road_part_number = ra.road_part_number and pl2.road_number = ra.road_number)
               = 1 and pl.status = 5)
      """.as[Long].list
  }

  def getValidRoadNumbers: List[Long] = {
    sql"""
       select distinct road_number
              from ROADWAY
              where valid_to IS NULL AND (end_date is NULL or end_date >= sysdate) order by road_number
      """.as[Long].list
  }

  def getValidBetweenRoadNumbers(roadNumbers: (Long, Long)): List[Long] = {
    sql"""
       select distinct road_number
              from ROADWAY
              where valid_to IS NULL AND (end_date is NULL or end_date >= sysdate) AND road_number BETWEEN ${roadNumbers._1} AND ${roadNumbers._2}
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
        road_type, ely, terminated) values (?, ?, ?, ?, ?, ?, ?, ?, ?,
        TO_DATE(?, 'YYYY-MM-DD'), TO_DATE(?, 'YYYY-MM-DD'), ?, ?, ?, ?)
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
      roadwayPS.setString(10, dateFormatter.print(address.startDate))
      roadwayPS.setString(11, address.endDate match {
        case Some(dt) => dateFormatter.print(dt)
        case None => ""
      })
      roadwayPS.setString(12, address.createdBy)
      roadwayPS.setInt(13, address.roadType.value)
      roadwayPS.setLong(14, address.ely)
      roadwayPS.setInt(15, address.terminated.value)
      roadwayPS.addBatch()
    }
    roadwayPS.executeBatch()
    roadwayPS.close()
    createRoadways.map(_.id).toSeq
  }

  // TODO Instead of returning Option[(Long, Long, ...)] return Option[RoadPartInfo]
  def getRoadPartInfo(roadNumber: Long, roadPart: Long): Option[(Long, Long, Long, Long, Long, Option[DateTime], Option[DateTime])] = {
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
    Q.queryNA[(Long, Long, Long, Long, Long, Option[DateTime], Option[DateTime])](query).firstOption
  }
}

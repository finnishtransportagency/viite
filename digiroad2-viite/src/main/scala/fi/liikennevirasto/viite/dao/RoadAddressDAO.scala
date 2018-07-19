package fi.liikennevirasto.viite.dao

import java.sql.{PreparedStatement, Timestamp}

import com.github.tototoshi.slick.MySQLJodaSupport._
import fi.liikennevirasto.digiroad2.asset.SideCode.AgainstDigitizing
import fi.liikennevirasto.digiroad2.asset.{BoundingRectangle, LinkGeomSource, SideCode}
import fi.liikennevirasto.digiroad2.dao.{Queries, Sequences}
import fi.liikennevirasto.digiroad2.oracle.{MassQuery, OracleDatabase}
import fi.liikennevirasto.digiroad2.util.LogUtils.time
import fi.liikennevirasto.digiroad2.util.Track
import fi.liikennevirasto.digiroad2.{GeometryUtils, Point}
import fi.liikennevirasto.viite.AddressConsistencyValidator.{AddressError, AddressErrorDetails}
import fi.liikennevirasto.viite._
import fi.liikennevirasto.viite.dao.CalibrationPointDAO.CalibrationPointMValues
import fi.liikennevirasto.viite.dao.TerminationCode.{NoTermination, Subsequent}
import fi.liikennevirasto.viite.model.{Anomaly, RoadAddressLinkLike}
import fi.liikennevirasto.viite.process.InvalidAddressDataException
import fi.liikennevirasto.viite.process.RoadAddressFiller.LinearLocationAdjustment
import fi.liikennevirasto.viite.util.CalibrationPointsUtils
import org.joda.time.DateTime
import org.joda.time.LocalDate
import org.joda.time.format.ISODateTimeFormat
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
  val values = Set(EndOfRoad, Discontinuous, ChangingELYCode, MinorDiscontinuity, Continuous)

  def apply(intValue: Int): Discontinuity = {
    values.find(_.value == intValue).getOrElse(Continuous)
  }

  def apply(longValue: Long): Discontinuity = {
    apply(longValue.toInt)
  }

  def apply(s: String): Discontinuity = {
    values.find(_.description.equalsIgnoreCase(s)).getOrElse(Continuous)
  }

  case object EndOfRoad extends Discontinuity { def value = 1; def description="Tien loppu"}
  case object Discontinuous extends Discontinuity { def value = 2 ; def description = "Epäjatkuva"}
  case object ChangingELYCode extends Discontinuity { def value = 3 ; def description = "ELY:n raja"}
  case object MinorDiscontinuity extends Discontinuity { def value = 4 ; def description= "Lievä epäjatkuvuus"}
  case object Continuous extends Discontinuity { def value = 5 ; def description = "Jatkuva"}
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
    CalibrationCode.apply(beginValue+endValue)
  }
  def getFromAddress(roadAddress: BaseRoadAddress): CalibrationCode = {
    fromBooleans(roadAddress.calibrationPoints._1.isDefined, roadAddress.calibrationPoints._2.isDefined)
  }

  def getFromAddressLinkLike(roadAddress: RoadAddressLinkLike): CalibrationCode = {
    fromBooleans(roadAddress.startCalibrationPoint.isDefined, roadAddress.endCalibrationPoint.isDefined)
  }

  case object No extends CalibrationCode { def value = 0 }
  case object AtEnd extends CalibrationCode { def value = 1 }
  case object AtBeginning extends CalibrationCode { def value = 2 }
  case object AtBoth extends CalibrationCode { def value = 3 }
}
case class CalibrationPoint(linkId: Long, segmentMValue: Double, addressMValue: Long) extends CalibrationPointMValues

sealed trait TerminationCode {
  def value: Int
}

object TerminationCode {
  val values = Set(NoTermination, Termination, Subsequent)

  def apply(intValue: Int): TerminationCode = {
    values.find(_.value == intValue).getOrElse(NoTermination)
  }

  case object NoTermination extends TerminationCode { def value = 0 }
  case object Termination extends TerminationCode { def value = 1 }
  case object Subsequent extends TerminationCode { def value = 2 }
}

trait BaseRoadAddress {
  def id: Long
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
  def calibrationPoints: (Option[CalibrationPoint], Option[CalibrationPoint])
  def floating: Boolean
  def geometry: Seq[Point]
  def ely: Long
  def linkGeomSource: LinkGeomSource
  def reversed: Boolean
  def commonHistoryId: Long
  def blackUnderline: Boolean

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
}

// Note: Geometry on road address is not directed: it isn't guaranteed to have a direction of digitization or road addressing
case class RoadAddress(id: Long, roadNumber: Long, roadPartNumber: Long, roadType: RoadType, track: Track,
                       discontinuity: Discontinuity, startAddrMValue: Long, endAddrMValue: Long,
                       startDate: Option[DateTime] = None, endDate: Option[DateTime] = None, createdBy: Option[String] = None,
                       linkId: Long, startMValue: Double, endMValue: Double, sideCode: SideCode,
                       adjustedTimestamp: Long, calibrationPoints: (Option[CalibrationPoint], Option[CalibrationPoint]) = (None, None),
                       floating: Boolean = false, geometry: Seq[Point], linkGeomSource: LinkGeomSource, ely: Long,
                       terminated: TerminationCode = NoTermination, commonHistoryId: Long, validFrom: Option[DateTime] = None, validTo: Option[DateTime] = None, blackUnderline: Boolean = false, roadName: Option[String] = None) extends BaseRoadAddress {

  val endCalibrationPoint = calibrationPoints._2
  val startCalibrationPoint = calibrationPoints._1

  def reversed: Boolean = false

  def addressBetween(a: Double, b: Double): (Long, Long) = {
    val (addrA, addrB) = (addrAt(a), addrAt(b))
    (Math.min(addrA,addrB), Math.max(addrA,addrB))
  }

  def isExpire(): Boolean = {
    validFrom.getOrElse(throw new IllegalStateException("The valid from should be set before call isExpire method")).isAfterNow ||
    validTo.exists(vt => vt.isEqualNow || vt.isBeforeNow)
  }

  private def addrAt(a: Double) = {
    val coefficient = (endAddrMValue - startAddrMValue) / (endMValue - startMValue)
    sideCode match {
      case SideCode.AgainstDigitizing =>
        endAddrMValue - Math.round((a-startMValue) * coefficient)
      case SideCode.TowardsDigitizing =>
        startAddrMValue + Math.round((a-startMValue) * coefficient)
      case _ => throw new InvalidAddressDataException(s"Bad sidecode $sideCode on road address $id (link $linkId)")
    }
  }

  def copyWithGeometry(newGeometry: Seq[Point]) = {
    this.copy(geometry = newGeometry)
  }
}

case class MissingRoadAddress(linkId: Long, startAddrMValue: Option[Long], endAddrMValue: Option[Long],
                              roadType: RoadType, roadNumber: Option[Long], roadPartNumber: Option[Long],
                              startMValue: Option[Double], endMValue: Option[Double], anomaly: Anomaly,geom: Seq[Point])

object RoadAddressDAO {

  protected def withRoadNumbersFilter(roadNumbers: Seq[(Int, Int)], filter: String = ""): String = {
    if (roadNumbers.isEmpty)
      return s" and  ($filter)"

    val limit = roadNumbers.head
    val filterAdd = s"""(ra.road_number >= ${limit._1} and ra.road_number <= ${limit._2})"""
    if (filter == "")
      withRoadNumbersFilter(roadNumbers.tail,  filterAdd)
    else
      withRoadNumbersFilter(roadNumbers.tail,  s"""$filter OR $filterAdd""")
  }

  def fetchRoadAddressesByBoundingBox(boundingRectangle: BoundingRectangle, fetchOnlyFloating: Boolean, onlyNormalRoads: Boolean = false, roadNumberLimits: Seq[(Int, Int)] = Seq()): (Seq[RoadAddress]) = {
    time(logger, "Fetch road addresses by bounding box") {
      val extendedBoundingRectangle = BoundingRectangle(boundingRectangle.leftBottom + boundingRectangle.diagonal.scale(.15),
        boundingRectangle.rightTop - boundingRectangle.diagonal.scale(.15))
      val filter = OracleDatabase.boundingBoxFilter(extendedBoundingRectangle, "geometry")

      val floatingFilter = if (fetchOnlyFloating)
        " and ra.floating = '1'"
      else
        ""
      val normalRoadsFilter = if (onlyNormalRoads)
        " and ra.link_source = 1"
      else
        ""
      val roadNumbersFilter = if (roadNumberLimits.nonEmpty)
        withRoadNumbersFilter(roadNumberLimits)
      else
        ""


      val query =
        s"""
        select ra.id, ra.road_number, ra.road_part_number, ra.road_type, ra.track_code,
        ra.discontinuity, ra.start_addr_m, ra.end_addr_m, ra.link_id, ra.start_measure, ra.end_measure,
        ra.side_code, ra.adjusted_timestamp,
        ra.start_date, ra.end_date, ra.created_by, ra.valid_from, ra.CALIBRATION_POINTS, ra.floating,
        (SELECT X FROM TABLE(SDO_UTIL.GETVERTICES(ra.geometry)) t WHERE id = 1) as X,
        (SELECT Y FROM TABLE(SDO_UTIL.GETVERTICES(ra.geometry)) t WHERE id = 1) as Y,
        (SELECT X FROM TABLE(SDO_UTIL.GETVERTICES(ra.geometry)) t WHERE id = 2) as X2,
        (SELECT Y FROM TABLE(SDO_UTIL.GETVERTICES(ra.geometry)) t WHERE id = 2) as Y2,
        ra.link_source, ra.ely, ra.terminated, ra.common_history_id, ra.valid_to,
        (SELECT rn.road_name FROM ROAD_NAMES rn WHERE rn.ROAD_NUMBER = ra.ROAD_NUMBER AND rn.END_DATE IS NULL AND rn.VALID_TO IS NULL) as road_name
        from ROAD_ADDRESS ra
        where $filter $floatingFilter $normalRoadsFilter $roadNumbersFilter and
          ra.terminated = 0 and
          ra.valid_to is null and
          ra.end_date is null
      """
      queryList(query)
    }
  }

  def fetchMissingRoadAddressesByBoundingBox(boundingRectangle: BoundingRectangle): (Seq[MissingRoadAddress]) = {
    val extendedBoundingRectangle = BoundingRectangle(boundingRectangle.leftBottom + boundingRectangle.diagonal.scale(.15),
      boundingRectangle.rightTop - boundingRectangle.diagonal.scale(.15))
    val filter = OracleDatabase.boundingBoxFilter(extendedBoundingRectangle, "geometry")

    val query = s"""
        select link_id, start_addr_m, end_addr_m, road_number, road_part_number, anomaly_code, start_m, end_m,
        (SELECT X FROM TABLE(SDO_UTIL.GETVERTICES(geometry)) t WHERE id = 1) as X,
        (SELECT Y FROM TABLE(SDO_UTIL.GETVERTICES(geometry)) t WHERE id = 1) as Y,
        (SELECT X FROM TABLE(SDO_UTIL.GETVERTICES(geometry)) t WHERE id = 2) as X2,
        (SELECT Y FROM TABLE(SDO_UTIL.GETVERTICES(geometry)) t WHERE id = 2) as Y2
        from missing_road_address
        where $filter
      """

    Q.queryNA[(Long, Option[Long], Option[Long], Option[Long], Option[Long], Int, Option[Double], Option[Double],Double, Double, Double, Double)](query).list.map {
      case (linkId, startAddrM, endAddrM, road, roadPart,anomaly, startM, endM, x, y, x2, y2) =>
        MissingRoadAddress(linkId, startAddrM, endAddrM, RoadType.UnknownOwnerRoad ,road, roadPart, startM, endM, Anomaly.apply(anomaly),Seq(Point(x, y), Point(x2, y2)))
    }
  }

  private def logger = LoggerFactory.getLogger(getClass)

  val formatter = ISODateTimeFormat.dateOptionalTimeParser()

  def dateTimeParse(string: String) = {
    formatter.parseDateTime(string)
  }

  val dateFormatter = ISODateTimeFormat.basicDate()

  def optDateTimeParse(string: String): Option[DateTime] = {
    try {
      if (string==null || string == "")
        None
      else
        Some(DateTime.parse(string, formatter))
    } catch {
      case ex: Exception => None
    }
  }

  implicit val getRoadAddress= new GetResult[RoadAddress]{
    def apply(r: PositionedResult) = {

      val id = r.nextLong()
      val roadNumber = r.nextLong()
      val roadPartNumber = r.nextLong()
      val roadType = RoadType.apply(r.nextInt())
      val trackCode = r.nextInt()
      val discontinuity = r.nextInt()
      val startAddrMValue = r.nextLong()
      val endAddrMValue = r.nextLong()
      val linkId = r.nextLong()
      val startMValue = r.nextDouble()
      val endMValue = r.nextDouble()
      val sideCode = r.nextInt()
      val adjustedTimestamp = r.nextLong()
      val startDate = r.nextDateOption.map(d => formatter.parseDateTime(d.toString))
      val endDate = r.nextDateOption.map(d => formatter.parseDateTime(d.toString))
      val createdBy = r.nextStringOption.map(new String(_))
      val validFrom = r.nextDateOption.map(d => formatter.parseDateTime(d.toString))
      val calibrationCode = r.nextInt()
      val floating = r.nextBoolean()
      val x = r.nextDouble()
      val y = r.nextDouble()
      val x2 = r.nextDouble()
      val y2 = r.nextDouble()
      val geomSource = LinkGeomSource.apply(r.nextInt)
      val ely = r.nextLong()
      val terminated = TerminationCode.apply(r.nextInt())
      val commonHistoryId = r.nextLong()
      val validTo = r.nextDateOption.map(d => formatter.parseDateTime(d.toString))
      val roadName = r.nextStringOption()
      RoadAddress(id, roadNumber, roadPartNumber, roadType, Track.apply(trackCode), Discontinuity.apply(discontinuity),
        startAddrMValue, endAddrMValue, startDate, endDate, createdBy, linkId, startMValue, endMValue,
        SideCode.apply(sideCode), adjustedTimestamp, CalibrationPointsUtils.calibrations(CalibrationCode.apply(calibrationCode),
          linkId, startMValue, endMValue, startAddrMValue, endAddrMValue, SideCode.apply(sideCode)), floating,
        Seq(Point(x, y), Point(x2, y2)), geomSource, ely, terminated, commonHistoryId, validFrom, validTo, blackUnderline = false, roadName)
    }
  }

  def fetchByLinkId(linkIds: Set[Long], includeFloating: Boolean = false, includeHistory: Boolean = true, includeTerminated: Boolean = true, includeCurrent: Boolean = true,
                    filterIds: Set[Long] = Set()): List[RoadAddress] = {
    time(logger, "Fetch road addresses by link id") {
      if (linkIds.size > 1000 || filterIds.size > 1000) {
        return fetchByLinkIdMassQuery(linkIds, includeFloating, includeHistory).filterNot(ra => filterIds.contains(ra.id))
      }
      val linkIdString = linkIds.mkString(",")
      val where = linkIds.isEmpty match {
        case true => return List()
        case false => s""" where ra.link_id in ($linkIdString)"""
      }
      val floating = if (!includeFloating)
        "AND ra.floating='0'"
      else
        ""
      val history = if (!includeHistory)
        "AND ra.end_date is null"
      else
        ""
      val current = if (!includeCurrent)
        "AND ra.end_date is not null"
      else
        ""
      val idFilter = if (filterIds.nonEmpty)
        s"AND ra.id not in ${filterIds.mkString("(", ",", ")")}"
      else
        ""

      val valid = if (!includeTerminated) {
        "AND ra.terminated = 0"
      } else {
        ""
      }

      val query =
        s"""
        select ra.id, ra.road_number, ra.road_part_number, ra.road_type, ra.track_code,
        ra.discontinuity, ra.start_addr_m, ra.end_addr_m, ra.link_id, ra.start_measure, ra.end_measure,
        ra.side_code, ra.adjusted_timestamp,
        ra.start_date, ra.end_date, ra.created_by, ra.valid_from, ra.CALIBRATION_POINTS, ra.floating, t.X, t.Y, t2.X, t2.Y, ra.link_source, ra.ely, ra.terminated, ra.common_history_id, ra.valid_to,
        (SELECT rn.road_name FROM ROAD_NAMES rn WHERE rn.ROAD_NUMBER = ra.ROAD_NUMBER AND rn.END_DATE IS NULL AND rn.VALID_TO IS NULL)
        from ROAD_ADDRESS ra cross join
        TABLE(SDO_UTIL.GETVERTICES(ra.geometry)) t cross join
        TABLE(SDO_UTIL.GETVERTICES(ra.geometry)) t2
        $where $floating $history $current $valid $idFilter and t.id < t2.id and
           valid_to is null
      """
      queryList(query)
    }
  }

  def fetchByLinkIdToApi(linkIds: Set[Long], useLatestNetwork: Boolean = true, searchDate: String = LocalDate.now.toString): List[RoadAddress] = {
    time(logger, "Fetch road addresses by link id to API") {
      if (linkIds.size > 1000) {
        return fetchByLinkIdMassQueryToApi(linkIds, useLatestNetwork, searchDate = searchDate)
      }

      def dateFilter(table: String): String = {
        s" ($table.START_DATE <= to_date('$searchDate', 'yyyy-mm-dd') AND (to_date('$searchDate', 'yyyy-mm-dd') < $table.END_DATE OR $table.END_DATE IS NULL))"
      }

      val linkIdString = linkIds.mkString(",")
      if(linkIds.nonEmpty){
        val (networkData, networkWhere) =
          if (useLatestNetwork) {
            (", net.id as road_version, net.created as version_date ",
              "join published_road_network net on net.id = (select MAX(network_id) from published_road_address where ra.id = road_address_id)")
          } else ("", "")

        val query =
          s"""
        select ra.id, ra.road_number, ra.road_part_number, ra.road_type, ra.track_code,
        ra.discontinuity, ra.start_addr_m, ra.end_addr_m, ra.link_id, ra.start_measure, ra.end_measure,
        ra.side_code, ra.adjusted_timestamp,
        ra.start_date, ra.end_date, ra.created_by, ra.valid_from, ra.CALIBRATION_POINTS, ra.floating, t.X, t.Y, t2.X, t2.Y, ra.link_source, ra.ely, ra.terminated, ra.common_history_id, ra.valid_to,
        (SELECT rn.road_name FROM ROAD_NAMES rn WHERE rn.ROAD_NUMBER = ra.ROAD_NUMBER AND rn.VALID_TO IS NULL and ${dateFilter(table = "rn")})
        $networkData
        from ROAD_ADDRESS ra cross join
        TABLE(SDO_UTIL.GETVERTICES(ra.geometry)) t cross join
        TABLE(SDO_UTIL.GETVERTICES(ra.geometry)) t2
        $networkWhere
        where ra.link_id in ($linkIdString) and ${dateFilter(table = "ra")} and t.id < t2.id and
          ra.valid_to is null
      """
        queryList(query)
      } else {
        List()
      }
    }
  }


  private def queryList(query: String): List[RoadAddress] = {
    Q.queryNA[RoadAddress](query).list.groupBy(_.id).map {
      case (id, roadAddressList) =>
        roadAddressList.head
    }.toList
  }

  def fetchPartsByRoadNumbers(boundingRectangle: BoundingRectangle, roadNumbers: Seq[(Int, Int)], coarse: Boolean = false): List[RoadAddress] = {
    time(logger, "Fetch road addresses of road parts by road numbers") {
      val geomFilter = OracleDatabase.boundingBoxFilter(boundingRectangle, "geometry")
      val filter = roadNumbers.map(n => "ra.road_number >= " + n._1 + " and ra.road_number <= " + n._2)
        .mkString("(", ") OR (", ")")
      val where = if (roadNumbers.isEmpty) {
        return List()
      } else {
        s""" where ra.track_code in (0,1) AND $filter"""
      }
      val coarseWhere = if (coarse) {
        " AND ra.calibration_points != 0"
      } else {
        ""
      }
      val query =
        s"""
        select ra.id, ra.road_number, ra.road_part_number, ra.road_type, ra.track_code,
        ra.discontinuity, ra.start_addr_m, ra.end_addr_m, ra.link_id, ra.start_measure, ra.end_measure,
        ra.side_code, ra.adjusted_timestamp,
        ra.start_date, ra.end_date, ra.created_by, ra.valid_from, ra.CALIBRATION_POINTS, ra.floating, t.X, t.Y, t2.X, t2.Y, ra.link_source, ra.ely, ra.terminated, ra.common_history_id, ra.valid_to,
        (SELECT rn.road_name FROM ROAD_NAMES rn WHERE rn.ROAD_NUMBER = ra.ROAD_NUMBER AND rn.END_DATE IS NULL AND rn.VALID_TO IS NULL)
        from ROAD_ADDRESS ra cross join
        TABLE(SDO_UTIL.GETVERTICES(ra.geometry)) t cross join
        TABLE(SDO_UTIL.GETVERTICES(ra.geometry)) t2
        $where AND $geomFilter $coarseWhere AND ra.floating='0' and t.id < t2.id and
          valid_to is null
      """
      queryList(query)
     }
  }

  def fetchByLinkIdMassQuery(linkIds: Set[Long], includeFloating: Boolean = false, includeHistory: Boolean = true): List[RoadAddress] = {
    time(logger, "Fetch road addresses by link id - mass query") {
      MassQuery.withIds(linkIds) {
        idTableName =>
          val floating = if (!includeFloating)
            "AND ra.floating='0'"
          else
            ""
          val history = if (!includeHistory)
            "AND ra.end_date is null"
          else
            ""
          val query =
            s"""
        select ra.id, ra.road_number, ra.road_part_number, ra.road_type, ra.track_code,
        ra.discontinuity, ra.start_addr_m, ra.end_addr_m, ra.link_id, ra.start_measure, ra.end_measure,
        ra.side_code, ra.adjusted_timestamp,
        ra.start_date, ra.end_date, ra.created_by, ra.valid_from, ra.CALIBRATION_POINTS, ra.floating, t.X, t.Y, t2.X, t2.Y, ra.link_source, ra.ely, ra.terminated, ra.common_history_id, ra.valid_to,
        (SELECT rn.road_name FROM ROAD_NAMES rn WHERE rn.ROAD_NUMBER = ra.ROAD_NUMBER AND rn.END_DATE IS NULL AND rn.VALID_TO IS NULL)
        from ROAD_ADDRESS ra cross join
        TABLE(SDO_UTIL.GETVERTICES(ra.geometry)) t cross join
        TABLE(SDO_UTIL.GETVERTICES(ra.geometry)) t2
        join $idTableName i on i.id = ra.link_id
        where t.id < t2.id $floating $history and
          valid_to is null
      """
          queryList(query)
      }
    }
  }

  def fetchByLinkIdMassQueryToApi(linkIds: Set[Long], useLatestNetwork: Boolean = true, searchDate: String = LocalDate.now.toString): List[RoadAddress] = {
    time(logger, "Fetch road addresses by link id - mass query to API") {
      def dateFilter(table: String): String = {
        s"($table.START_DATE <= to_date('$searchDate', 'yyyy-mm-dd') AND (to_date('$searchDate', 'yyyy-mm-dd') < $table.END_DATE OR $table.END_DATE IS NULL))"
      }

      val (networkData, networkWhere) =
        if (useLatestNetwork) {
          (", net.id as road_version, net.created as version_date ",
            "join published_road_network net on net.id = (select MAX(network_id) from published_road_address where ra.id = road_address_id)")
        } else ("", "")
      MassQuery.withIds(linkIds) {
        idTableName =>
          val query =
            s"""
              select ra.id, ra.road_number, ra.road_part_number, ra.road_type, ra.track_code,
              ra.discontinuity, ra.start_addr_m, ra.end_addr_m, ra.link_id, ra.start_measure, ra.end_measure,
              ra.side_code, ra.adjusted_timestamp,
              ra.start_date, ra.end_date, ra.created_by, ra.valid_from, ra.CALIBRATION_POINTS, ra.floating, t.X, t.Y, t2.X, t2.Y, ra.link_source, ra.ely, ra.terminated, ra.common_history_id, ra.valid_to,
              (SELECT max(rn.road_name) FROM ROAD_NAMES rn WHERE rn.ROAD_NUMBER = ra.ROAD_NUMBER AND rn.VALID_TO IS NULL AND ${dateFilter(table = "rn")})
              $networkData
              from ROAD_ADDRESS ra cross join
              TABLE(SDO_UTIL.GETVERTICES(ra.geometry)) t cross join
              TABLE(SDO_UTIL.GETVERTICES(ra.geometry)) t2
              join $idTableName i on i.id = ra.link_id
              $networkWhere
              where t.id < t2.id and
                ra.valid_to is null AND ${dateFilter(table = "ra")}
            """
          println(query)
          queryList(query)
      }
    }
  }

  def fetchByIdMassQuery(ids: Set[Long], includeFloating: Boolean = false, includeHistory: Boolean = true): List[RoadAddress] = {
    time(logger, "Fetch road addresses by id - mass query") {
      MassQuery.withIds(ids) {
        idTableName =>
          val floating = if (!includeFloating)
            "AND ra.floating='0'"
          else
            ""
          val history = if (!includeHistory)
            "AND ra.end_date is null"
          else
            ""
          val query =
            s"""
        select ra.id, ra.road_number, ra.road_part_number, ra.road_type, ra.track_code,
        ra.discontinuity, ra.start_addr_m, ra.end_addr_m, ra.link_id, ra.start_measure, ra.end_measure,
        ra.side_code, ra.adjusted_timestamp,
        ra.start_date, ra.end_date, ra.created_by, ra.valid_from, ra.CALIBRATION_POINTS, ra.floating, t.X, t.Y, t2.X, t2.Y, ra.link_source, ra.ely, ra.terminated, ra.common_history_id, ra.valid_to,
        (SELECT rn.road_name FROM ROAD_NAMES rn WHERE rn.ROAD_NUMBER = ra.ROAD_NUMBER AND rn.END_DATE IS NULL AND rn.VALID_TO IS NULL)
        from ROAD_ADDRESS ra cross join
        TABLE(SDO_UTIL.GETVERTICES(ra.geometry)) t cross join
        TABLE(SDO_UTIL.GETVERTICES(ra.geometry)) t2
        join $idTableName i on i.id = ra.id
        where t.id < t2.id $floating $history and
          valid_to is null
      """
          queryList(query)
      }
    }
  }

  private def fetchByAddress(roadNumber: Long, roadPartNumber: Long, track: Track, startAddrM: Option[Long], endAddrM: Option[Long]) = {
    val startFilter = startAddrM.map(s => s" AND ra.start_addr_m = $s").getOrElse("")
    val endFilter = endAddrM.map(e => s" AND ra.end_addr_m = $e").getOrElse("")
    val query =
      s"""
        select ra.id, ra.road_number, ra.road_part_number, ra.road_type, ra.track_code,
        ra.discontinuity, ra.start_addr_m, ra.end_addr_m, ra.link_id, ra.start_measure, ra.end_measure,
        ra.side_code, ra.adjusted_timestamp,
        ra.start_date, ra.end_date, ra.created_by, ra.valid_from, ra.CALIBRATION_POINTS, ra.floating, t.X, t.Y, t2.X, t2.Y, ra.link_source, ra.ely, ra.terminated, ra.common_history_id, ra.valid_to,
        (SELECT rn.road_name FROM ROAD_NAMES rn WHERE rn.ROAD_NUMBER = ra.ROAD_NUMBER AND rn.END_DATE IS NULL AND rn.VALID_TO IS NULL)
        from ROAD_ADDRESS ra cross join
        TABLE(SDO_UTIL.GETVERTICES(ra.geometry)) t cross join
        TABLE(SDO_UTIL.GETVERTICES(ra.geometry)) t2
        where t.id < t2.id AND ra.road_number = $roadNumber AND ra.road_part_number = $roadPartNumber AND
         ra.track_code = ${track.value} and
          valid_to is null $startFilter $endFilter
      """
    queryList(query).headOption
  }

  def fetchByAddressStart(roadNumber: Long, roadPartNumber: Long, track: Track, startAddrM: Long): Option[RoadAddress] = {
    time(logger, "Fetch road address by address start") {
      fetchByAddress(roadNumber, roadPartNumber, track, Some(startAddrM), None)
    }
  }
  def fetchByAddressEnd(roadNumber: Long, roadPartNumber: Long, track: Track, endAddrM: Long): Option[RoadAddress] = {
    time(logger, "Fetch road address by address end") {
      fetchByAddress(roadNumber, roadPartNumber, track, None, Some(endAddrM))
    }
  }

  def fetchByRoadPart(roadNumber: Long, roadPartNumber: Long, includeFloating: Boolean = false, includeExpired: Boolean = false,
                      includeHistory: Boolean = false, includeSuravage: Boolean = true, fetchOnlyEnd: Boolean = false, fetchOnlyStart: Boolean = false): List[RoadAddress] = {
    time(logger, "Fetch road addresses by road part") {
      val floating = if (!includeFloating)
        "ra.floating='0' AND"
      else
        ""
      val expiredFilter = if (!includeExpired)
        "ra.valid_to IS NULL AND"
      else
        ""
      val historyFilter = if (!includeHistory)
        "ra.end_date is null AND"
      else
        ""
      val suravageFilter = if (!includeSuravage)
        "ra.link_source != 3 AND"
      else ""
      // valid_to > sysdate because we may expire and query the data again in same transaction

      val endPart = if (fetchOnlyEnd) {
        s"And ra.end_addr_m = (Select max(road.end_addr_m) From Road_address road Where road.road_number = $roadNumber And road.road_part_number = $roadPartNumber And (road.valid_to IS NULL AND road.end_date is null))"
      } else ""
      val startPart = if (fetchOnlyStart) {
        s"And ra.start_addr_m = (Select min(road.start_addr_m) From Road_address road Where road.road_number = $roadNumber And road.road_part_number = $roadPartNumber And (road.valid_to IS NULL AND road.end_date is null))"
      } else ""
      val query =
        s"""
        select ra.id, ra.road_number, ra.road_part_number, ra.road_type, ra.track_code,
        ra.discontinuity, ra.start_addr_m, ra.end_addr_m, ra.link_id, ra.start_measure, ra.end_measure,
        ra.side_code, ra.adjusted_timestamp,
        ra.start_date, ra.end_date, ra.created_by, ra.valid_from, ra.CALIBRATION_POINTS, ra.floating, t.X, t.Y, t2.X, t2.Y, ra.link_source, ra.ely, ra.terminated, ra.common_history_id, ra.valid_to,
        (SELECT rn.road_name FROM ROAD_NAMES rn WHERE rn.ROAD_NUMBER = ra.ROAD_NUMBER AND rn.END_DATE IS NULL AND rn.VALID_TO IS NULL)
        from road_address ra cross join
        TABLE(SDO_UTIL.GETVERTICES(ra.geometry)) t cross join
        TABLE(SDO_UTIL.GETVERTICES(ra.geometry)) t2
        where $floating $expiredFilter $historyFilter $suravageFilter ra.road_number = $roadNumber AND ra.road_part_number = $roadPartNumber and t.id < t2.id
        $endPart $startPart
        ORDER BY ra.road_number, ra.road_part_number, ra.track_code, ra.start_addr_m
      """
      queryList(query)
    }
  }

  /**
    * Check that the road part is available for the project at project date (and not modified to be changed
    * later)
    *
    * @param roadNumber Road number to be reserved for project
    * @param roadPartNumber Road part number to be reserved for project
    * @param projectId Project that wants to reserve the road part (used to check the project date vs. address dates)
    * @return True, if unavailable
    */
  def isNotAvailableForProject(roadNumber: Long, roadPartNumber: Long, projectId: Long): Boolean = {
    time(logger, s"Check if the road part $roadNumber/$roadPartNumber is not available for the project $projectId") {
      val query =
        s"""
      SELECT 1 FROM dual WHERE EXISTS(select 1
         from project pro,
         road_address ra
         where  pro.id = $projectId AND road_number = $roadNumber AND road_part_number = $roadPartNumber AND
         (ra.START_DATE >= pro.START_DATE or ra.END_DATE > pro.START_DATE) AND
         ra.VALID_TO is null) OR EXISTS (
         SELECT 1 FROM project_reserved_road_part pro, road_address ra
          WHERE pro.project_id != $projectId AND pro.road_number = ra.road_number AND pro.road_part_number = ra.road_part_number
           AND pro.road_number = $roadNumber AND pro.road_part_number = $roadPartNumber AND ra.end_date IS NULL)"""
      Q.queryNA[Int](query).firstOption.nonEmpty
    }
  }

  /**
    *
    * @param roadNumber roadNumber for roadparts we want to check
    * @param roadPartNumbers roadparts needed to be checked
    * @return returns roadnumber, roadpartnumber and startaddressM for links that are overlapping either by date or m values
    */
  def historySanityCheck(roadNumber: Long, roadPartNumbers: Seq[Long]): List[(Long, Long, Long)] = {
    time(logger, s"Sanity check for the history of the road number $roadNumber") {
      val query =
        s"""
    SELECT r.ROAD_NUMBER, r.ROAD_PART_NUMBER, r.START_ADDR_M FROM ROAD_ADDRESS r
      WHERE  EXISTS(
      SELECT 1 FROM ROAD_ADDRESS r2 WHERE
    r2.id != r.id AND r.ROAD_NUMBER =$roadNumber AND r.ROAD_PART_NUMBER in ( ${roadPartNumbers.mkString(",")}) AND
      ((r2.valid_to is null AND (r.valid_to is null OR r2.valid_from < r.valid_to)) OR
        (r2.valid_to is not null AND NOT (r.valid_to <= r2.valid_from OR r.valid_from >= r2.valid_to))) AND
    ((r2.END_DATE is null AND (r.end_date is null OR r2.start_date < r.end_date)) OR
      (r2.END_DATE is not null AND NOT (r.END_DATE <= r2.START_DATE OR r.START_DATE >= r2.END_DATE))) AND
    r2.ROAD_NUMBER = r.ROAD_NUMBER AND
      r2.ROAD_PART_NUMBER = r.ROAD_PART_NUMBER AND
      (r2.TRACK_CODE = r.TRACK_CODE OR r.TRACK_CODE = 0 OR r2.TRACK_CODE = 0) AND
      NOT (r2.END_ADDR_M <= r.START_ADDR_M OR r2.START_ADDR_M >= r.END_ADDR_M)
    )"""
      Q.queryNA[(Long, Long, Long)](query).list
    }
  }



  def fetchByRoad(roadNumber: Long, includeFloating: Boolean = false) = {
    time(logger, "Fetch road addresses by road number") {
      val floating = if (!includeFloating)
        "ra.floating='0' AND"
      else
        ""
      // valid_to > sysdate because we may expire and query the data again in same transaction
      val query =
        s"""
        select ra.id, ra.road_number, ra.road_part_number, ra.road_type, ra.track_code,
        ra.discontinuity, ra.start_addr_m, ra.end_addr_m, ra.link_id, ra.start_measure, ra.end_measure,
        ra.side_code, ra.adjusted_timestamp,
        ra.start_date, ra.end_date, ra.created_by, ra.valid_from, ra.CALIBRATION_POINTS, ra.floating, t.X, t.Y, t2.X, t2.Y, ra.link_source, ra.ely, ra.terminated, ra.common_history_id, ra.valid_to,
        (SELECT rn.road_name FROM ROAD_NAMES rn WHERE rn.ROAD_NUMBER = ra.ROAD_NUMBER AND rn.END_DATE IS NULL AND rn.VALID_TO IS NULL)
        from ROAD_ADDRESS ra cross join
        TABLE(SDO_UTIL.GETVERTICES(ra.geometry)) t cross join
        TABLE(SDO_UTIL.GETVERTICES(ra.geometry)) t2
        where $floating ra.road_number = $roadNumber AND t.id < t2.id and
        valid_to is null
        ORDER BY ra.road_number, ra.road_part_number, ra.track_code, ra.start_addr_m
      """
      queryList(query)
    }
  }

  def fetchAllFloatingRoadAddresses(includesHistory: Boolean = false) = {
    time(logger, "Fetch all floating road addresses") {
      val history = if (!includesHistory) s" AND ra.END_DATE is null " else ""
      val query =
        s"""
        select ra.id, ra.road_number, ra.road_part_number, ra.road_type, ra.track_code,
          ra.discontinuity, ra.start_addr_m, ra.end_addr_m, ra.link_id, ra.start_measure, ra.end_measure,
          ra.side_code, ra.adjusted_timestamp,
          ra.start_date, ra.end_date, ra.created_by, ra.valid_from, ra.CALIBRATION_POINTS, ra.floating, t.X, t.Y, t2.X, t2.Y, ra.link_source, ra.ely, ra.terminated, ra.common_history_id, ra.valid_to,
          (SELECT rn.road_name FROM ROAD_NAMES rn WHERE rn.ROAD_NUMBER = ra.ROAD_NUMBER AND rn.END_DATE IS NULL AND rn.VALID_TO IS NULL)
          from ROAD_ADDRESS ra cross join
          TABLE(SDO_UTIL.GETVERTICES(ra.geometry)) t cross join
          TABLE(SDO_UTIL.GETVERTICES(ra.geometry)) t2
          where t.id < t2.id and ra.floating = 1 $history and
          valid_to is null
          order by ra.ELY, ra.ROAD_NUMBER, ra.ROAD_PART_NUMBER, ra.START_ADDR_M, ra.END_ADDR_M
      """
      queryList(query)
    }
  }

  def fetchAllRoadAddressErrors(includesHistory: Boolean = false) = {
    time(logger, s"Fetch all road address errors (includesHistory: $includesHistory)") {
      val history = if (!includesHistory) s" where ra.end_date is null " else ""
      val query =
        s"""
        select ra.id, ra.link_id, ra.road_number, ra.road_part_number, re.error_code, ra.ely from road_address ra join road_network_errors re on re.road_address_id = ra.id $history
        order by ra.ely, ra.road_number, ra.road_part_number, re.error_code
      """
      Q.queryNA[(Long, Long, Long, Long, Int, Long)](query).list.map {
        case (id, linkId, roadNumber, roadPartNumber, errorCode, ely) =>
          AddressErrorDetails(id, linkId, roadNumber, roadPartNumber, AddressError.apply(errorCode), ely)
      }
    }
  }

  def fetchMultiSegmentLinkIds(roadNumber: Long) = {
    time(logger, "Fetch road addresses by road number (multi segment link ids)") {
      val query =
        s"""
        select ra.id, ra.road_number, ra.road_part_number, ra.road_type, ra.track_code,
        ra.discontinuity, ra.start_addr_m, ra.end_addr_m, ra.link_id, ra.start_measure, ra.end_measure,
        ra.side_code, ra.adjusted_timestamp,
        ra.start_date, ra.end_date, ra.created_by, ra.valid_from, ra.CALIBRATION_POINTS, ra.floating, t.X, t.Y, t2.X, t2.Y, ra.link_source, ra.ely, ra.terminated, ra.common_history_id, ra.valid_to,
        (SELECT rn.road_name FROM ROAD_NAMES rn WHERE rn.ROAD_NUMBER = ra.ROAD_NUMBER AND rn.END_DATE IS NULL AND rn.VALID_TO IS NULL)
        from ROAD_ADDRESS ra cross join
        TABLE(SDO_UTIL.GETVERTICES(ra.geometry)) t cross join
        TABLE(SDO_UTIL.GETVERTICES(ra.geometry)) t2
        where ra.link_id in (
          select link_id
          from ROAD_ADDRESS
          where road_number = $roadNumber and
          valid_to is null
          GROUP BY link_id
          HAVING COUNT(*) > 1) AND
        ra.road_number = $roadNumber and
          valid_to is null
      """
      queryList(query)
    }
  }
  def fetchNextRoadNumber(current: Int) = {
    val query =
      s"""
          SELECT * FROM (
            SELECT ra.road_number
            FROM road_address ra
            WHERE road_number > $current AND valid_to IS NULL
            ORDER BY road_number ASC
          ) WHERE ROWNUM < 2
      """
    Q.queryNA[Int](query).firstOption
  }

  def fetchNextRoadPartNumber(roadNumber: Int, current: Int) = {
    val query =
      s"""
          SELECT * FROM (
            SELECT ra.road_part_number
            FROM road_address ra
            WHERE road_number = $roadNumber AND road_part_number > $current AND valid_to IS NULL
            ORDER BY road_part_number ASC
          ) WHERE ROWNUM < 2
      """
    Q.queryNA[Int](query).firstOption
  }

  def fetchPreviousRoadPartNumber(roadNumber: Long, current: Long): Option[Long] = {
    val query =
      s"""
          SELECT * FROM (
            SELECT ra.road_part_number
            FROM road_address ra
            WHERE road_number = $roadNumber AND road_part_number < $current AND valid_to IS NULL
            ORDER BY road_part_number DESC
          ) WHERE ROWNUM < 2
      """
    Q.queryNA[Long](query).firstOption
  }

  def update(roadAddress: RoadAddress) : Unit = {
    update(roadAddress, None)
  }

  def toTimeStamp(dateTime: Option[DateTime]) = {
    dateTime.map(dt => new Timestamp(dt.getMillis))
  }

  def update(roadAddress: RoadAddress, geometry: Option[Seq[Point]]) : Unit = {
    if (geometry.isEmpty)
      updateWithoutGeometry(roadAddress)
    else {
      val startTS = toTimeStamp(roadAddress.startDate)
      val endTS = toTimeStamp(roadAddress.endDate)
      val first = geometry.get.head
      val last = geometry.get.last
      val (x1, y1, z1, x2, y2, z2) = (first.x, first.y, first.z, last.x, last.y, last.z)
      val length = GeometryUtils.geometryLength(geometry.get)
      sqlu"""UPDATE ROAD_ADDRESS
        SET road_number = ${roadAddress.roadNumber},
           road_part_number= ${roadAddress.roadPartNumber},
           track_code = ${roadAddress.track.value},
           discontinuity= ${roadAddress.discontinuity.value},
           START_ADDR_M= ${roadAddress.startAddrMValue},
           END_ADDR_M= ${roadAddress.endAddrMValue},
           start_date= $startTS,
           end_date= $endTS,
           geometry= MDSYS.SDO_GEOMETRY(4002, 3067, NULL, MDSYS.SDO_ELEM_INFO_ARRAY(1,2,1), MDSYS.SDO_ORDINATE_ARRAY(
             $x1,$y1,$z1,0.0,$x2,$y2,$z2,$length))
        WHERE id = ${roadAddress.id}""".execute
    }
  }

  def updateGeometry(roadAddressId: Long, geometry: Seq[Point]): Unit = {
    if (!geometry.isEmpty) {
      val first = geometry.head
      val last = geometry.last
      val (x1, y1, z1, x2, y2, z2) = (
        GeometryUtils.scaleToThreeDigits(first.x),
        GeometryUtils.scaleToThreeDigits(first.y),
        GeometryUtils.scaleToThreeDigits(first.z),
        GeometryUtils.scaleToThreeDigits(last.x),
        GeometryUtils.scaleToThreeDigits(last.y),
        GeometryUtils.scaleToThreeDigits(last.z)
      )
      val length = GeometryUtils.geometryLength(geometry)
      sqlu"""UPDATE ROAD_ADDRESS
        SET geometry = MDSYS.SDO_GEOMETRY(4002, 3067, NULL, MDSYS.SDO_ELEM_INFO_ARRAY(1, 2, 1),
             MDSYS.SDO_ORDINATE_ARRAY($x1, $y1, $z1, 0.0, $x2, $y2, $z2, $length))
        WHERE id = ${roadAddressId}""".execute
    }
  }

  private def updateWithoutGeometry(roadAddress: RoadAddress) = {
    val startTS = toTimeStamp(roadAddress.startDate)
    val endTS = toTimeStamp(roadAddress.endDate)
    sqlu"""UPDATE ROAD_ADDRESS
        SET road_number = ${roadAddress.roadNumber},
           road_part_number= ${roadAddress.roadPartNumber},
           track_code = ${roadAddress.track.value},
           discontinuity= ${roadAddress.discontinuity.value},
           START_ADDR_M= ${roadAddress.startAddrMValue},
           END_ADDR_M= ${roadAddress.endAddrMValue},
           start_date= $startTS,
           end_date= $endTS
        WHERE id = ${roadAddress.id}""".execute
  }

  def createMissingRoadAddress (mra: MissingRoadAddress) = {
    val (p1, p2) = (mra.geom.head, mra.geom.last)

    sqlu"""
           insert into missing_road_address
           (select ${mra.linkId}, ${mra.startAddrMValue}, ${mra.endAddrMValue},
             ${mra.roadNumber}, ${mra.roadPartNumber}, ${mra.anomaly.value},
             ${mra.startMValue}, ${mra.endMValue},
             MDSYS.SDO_GEOMETRY(4002, 3067, NULL, MDSYS.SDO_ELEM_INFO_ARRAY(1,2,1),
             MDSYS.SDO_ORDINATE_ARRAY(${p1.x},${p1.y},0.0,0.0,${p2.x},${p2.y},0.0,0.0))
              FROM dual WHERE NOT EXISTS (SELECT * FROM MISSING_ROAD_ADDRESS WHERE link_id = ${mra.linkId}) AND
              NOT EXISTS (SELECT * FROM ROAD_ADDRESS ra
                WHERE link_id = ${mra.linkId} AND valid_to IS NULL ))
           """.execute
  }

  def createMissingRoadAddress (linkId: Long, start_addr_m: Long, end_addr_m: Long, anomaly_code: Int) = {
    sqlu"""
           insert into missing_road_address (link_id, start_addr_m, end_addr_m,anomaly_code)
           values ($linkId, $start_addr_m, $end_addr_m, $anomaly_code)
           """.execute
  }

  def createMissingRoadAddress (linkId: Long, start_addr_m: Long, end_addr_m: Long, anomaly_code: Int, start_m : Double, end_m : Double) = {
    sqlu"""
           insert into missing_road_address (link_id, start_addr_m, end_addr_m,anomaly_code, start_m, end_m)
           values ($linkId, $start_addr_m, $end_addr_m, $anomaly_code, $start_m, $end_m)
           """.execute
  }

  def expireById(ids: Set[Long]): Int = {
    val query =
      s"""
          Update ROAD_ADDRESS Set valid_to = sysdate where valid_to IS NULL and id in (${ids.mkString(",")})
        """
    if (ids.isEmpty)
      0
    else
      Q.updateNA(query).first
  }

  def expireRoadAddresses (sourceLinkIds: Set[Long]) = {
    if (!sourceLinkIds.isEmpty) {
      val query =
        s"""
          Update road_address Set valid_to = sysdate Where valid_to IS NULL and link_id in (${sourceLinkIds.mkString(",")})
        """
      Q.updateNA(query).first
    }
  }

  def expireMissingRoadAddresses (targetLinkIds: Set[Long]) = {

    if (!targetLinkIds.isEmpty) {
      val query =
        s"""
          Delete from missing_road_address Where link_id in (${targetLinkIds.mkString(",")})
        """
      Q.updateNA(query).first
    }
  }

  def getMissingRoadAddresses(linkIds: Set[Long]): List[MissingRoadAddress] = {
    if (linkIds.size > 500) {
      getMissingByLinkIdMassQuery(linkIds)
    } else {
      val where = if (linkIds.isEmpty)
        return List()
      else
        s""" where link_id in (${linkIds.mkString(",")})"""

      val query =
        s"""SELECT link_id, start_addr_m, end_addr_m, road_number, road_part_number, start_m, end_m, anomaly_code,
           (SELECT X FROM TABLE(SDO_UTIL.GETVERTICES(geometry)) t WHERE id = 1) as X,
           (SELECT Y FROM TABLE(SDO_UTIL.GETVERTICES(geometry)) t WHERE id = 1) as Y,
           (SELECT X FROM TABLE(SDO_UTIL.GETVERTICES(geometry)) t WHERE id = 2) as X2,
           (SELECT Y FROM TABLE(SDO_UTIL.GETVERTICES(geometry)) t WHERE id = 2) as Y2
            FROM missing_road_address $where"""
      Q.queryNA[(Long, Option[Long], Option[Long], Option[Long], Option[Long], Option[Double], Option[Double], Int, Double, Double, Double, Double)](query).list.map {
        case (linkId, startAddrM, endAddrM, road, roadPart, startM, endM, anomaly, x1, y1, x2, y2) =>
          MissingRoadAddress(linkId, startAddrM, endAddrM, RoadType.UnknownOwnerRoad ,road, roadPart, startM, endM, Anomaly.apply(anomaly), Seq(Point(x1, y1),Point(x2, y2)))
      }
    }
  }

  def getMissingByLinkIdMassQuery(linkIds: Set[Long]): List[MissingRoadAddress] = {
    MassQuery.withIds(linkIds) {
      idTableName =>
        val query =
          s"""SELECT link_id, start_addr_m, end_addr_m, road_number, road_part_number, start_m, end_m, anomaly_code,
             (SELECT X FROM TABLE(SDO_UTIL.GETVERTICES(geometry)) t WHERE id = 1) as X,
             (SELECT Y FROM TABLE(SDO_UTIL.GETVERTICES(geometry)) t WHERE id = 1) as Y,
             (SELECT X FROM TABLE(SDO_UTIL.GETVERTICES(geometry)) t WHERE id = 2) as X2,
             (SELECT Y FROM TABLE(SDO_UTIL.GETVERTICES(geometry)) t WHERE id = 2) as Y2
            FROM missing_road_address mra join $idTableName i on i.id = mra.link_id"""
        Q.queryNA[(Long, Option[Long], Option[Long], Option[Long], Option[Long], Option[Double], Option[Double], Int, Double, Double, Double, Double)](query).list.map {
          case (linkId, startAddrM, endAddrM, road, roadPart, startM, endM, anomaly, x1, y1, x2, y2) =>
            MissingRoadAddress(linkId, startAddrM, endAddrM, RoadType.UnknownOwnerRoad, road, roadPart, startM, endM, Anomaly.apply(anomaly), Seq(Point(x1, y1),Point(x2, y2)))
        }
    }
  }

  /**
    * Marks the road address identified by the supplied Id as eiher floating or not
    *
    * @param isFloating '0' for not floating, '1' for floating
    * @param roadAddressId The Id of a road addresss
    */
  def changeRoadAddressFloating(isFloating: Int, roadAddressId: Long, geometry: Option[Seq[Point]]): Unit = {
    if (geometry.nonEmpty) {
      val first = geometry.get.head
      val last = geometry.get.last
      val (x1, y1, z1, x2, y2, z2) = (first.x, first.y, first.z, last.x, last.y, last.z)
      val length = GeometryUtils.geometryLength(geometry.get)
      sqlu"""
           Update road_address Set floating = $isFloating,
                  geometry= MDSYS.SDO_GEOMETRY(4002, 3067, NULL, MDSYS.SDO_ELEM_INFO_ARRAY(1,2,1), MDSYS.SDO_ORDINATE_ARRAY(
                  $x1,$y1,$z1,0.0,$x2,$y2,$z2,$length))
             Where id = $roadAddressId
      """.execute
    } else {
      sqlu"""
           Update road_address Set floating = $isFloating
             Where id = $roadAddressId
      """.execute
    }
  }

  /**
    * Marks the road address identified by the supplied Id as eiher floating or not and also updates the history of
    * those who shares the same link_id and common_history_id
    *
    * @param isFloating '0' for not floating, '1' for floating
    * @param roadAddressId The Id of a road addresss
    */
  def changeRoadAddressFloatingWithHistory(isFloating: Int, roadAddressId: Long, geometry: Option[Seq[Point]]): Unit = {
    if (geometry.nonEmpty) {
      val first = geometry.get.head
      val last = geometry.get.last
      val (x1, y1, z1, x2, y2, z2) = (first.x, first.y, first.z, last.x, last.y, last.z)
      val length = GeometryUtils.geometryLength(geometry.get)
      sqlu"""
           Update road_address Set floating = $isFloating,
                  geometry= MDSYS.SDO_GEOMETRY(4002, 3067, NULL, MDSYS.SDO_ELEM_INFO_ARRAY(1,2,1), MDSYS.SDO_ORDINATE_ARRAY(
                  $x1,$y1,$z1,0.0,$x2,$y2,$z2,$length))
             Where id = $roadAddressId
      """.execute
    }
    sqlu"""
       update road_address set floating = $isFloating where id in(
       select road_address.id from road_address where link_id =
       (select link_id from road_address where road_address.id = $roadAddressId))
        """.execute
  }

  def changeRoadAddressFloating(float: Boolean, roadAddressId: Long, geometry: Option[Seq[Point]] = None): Unit = {
    changeRoadAddressFloatingWithHistory(if (float) 1 else 0, roadAddressId, geometry)
  }

  def getAllValidRoadNumbers(filter: String = "") = {
    Q.queryNA[Long](s"""
       select distinct road_number
              from road_address ra
              where ra.floating = '0' AND valid_to IS NULL
              $filter
              order by road_number
      """).list
  }

  def getValidRoadNumbersWithFilterToTestAndDevEnv = {
    getAllValidRoadNumbers("AND (ra.road_number <= 20000 OR (ra.road_number >= 40000 AND ra.road_number <= 70000) OR ra.road_number > 99999 )")
  }

  def getValidRoadParts(roadNumber: Long) = {
    sql"""
       select distinct road_part_number
              from road_address ra
              where road_number = $roadNumber AND valid_to IS NULL
              AND END_DATE IS NULL order by road_part_number
      """.as[Long].list
  }


  /**
    * Used in the ProjectValidator
    *
    * @param roadNumber
    * @param startDate
    * @return
    */
  def getValidRoadParts(roadNumber: Long, startDate: DateTime) = {
    sql"""
       select distinct ra.road_part_number
              from road_address ra
              where road_number = $roadNumber AND valid_to IS NULL AND START_DATE <= $startDate
              AND END_DATE IS NULL
              AND ra.road_part_number NOT IN (select distinct pl.road_part_number from project_link pl where (select count(distinct pl2.status) from project_link pl2 where pl2.road_part_number = ra.road_part_number and pl2.road_number = ra.road_number)
               = 1 and pl.status = 5)
      """.as[Long].list
  }

  def updateLinearLocation(linearLocationAdjustment: LinearLocationAdjustment) = {
    val (startM, endM) = (linearLocationAdjustment.startMeasure, linearLocationAdjustment.endMeasure)
    (startM, endM) match {
      case (Some(s), Some(e)) =>
        sqlu"""
           UPDATE ROAD_ADDRESS
           SET start_measure = $s,
             end_measure = $e,
             link_id = ${linearLocationAdjustment.linkId},
             modified_date = sysdate
           WHERE id = ${linearLocationAdjustment.addressId}
      """.execute
      case (_, Some(e)) =>
        sqlu"""
           UPDATE ROAD_ADDRESS
           SET
             end_measure = ${linearLocationAdjustment.endMeasure.get},
             link_id = ${linearLocationAdjustment.linkId},
             modified_date = sysdate
           WHERE id = ${linearLocationAdjustment.addressId}
      """.execute
      case (Some(s), _) =>
        sqlu"""
           UPDATE ROAD_ADDRESS
           SET start_measure = ${linearLocationAdjustment.startMeasure.get},
             link_id = ${linearLocationAdjustment.linkId},
             modified_date = sysdate
           WHERE id = ${linearLocationAdjustment.addressId}
      """.execute
      case _ =>
    }
  }

  def updateLinkSource(id: Long, linkSource: LinkGeomSource): Boolean = {
    sqlu"""
           UPDATE ROAD_ADDRESS SET link_source = ${linkSource.value} WHERE id = $id
      """.execute
    true
  }
  /**
    * Create the value for geometry field, using the updateSQL above.
    *
    * @param geometry Geometry, if available
    * @return
    */
  private def geometryToSQL(geometry: Option[Seq[Point]]) = {
    geometry match {
      case Some(geom) if geom.nonEmpty =>
      case _ => ""
    }
  }

  def getNextRoadAddressId: Long = {
    Queries.nextViitePrimaryKeyId.as[Long].first
  }

  implicit val getDiscontinuity = GetResult[Discontinuity]( r=> Discontinuity.apply(r.nextInt()))

  implicit val getTrack = GetResult[Track]( r=> Track.apply(r.nextInt()))

  implicit val getCalibrationCode = GetResult[CalibrationCode]( r=> CalibrationCode.apply(r.nextInt()))

  def queryFloatingByLinkIdMassQuery(linkIds: Set[Long]): List[RoadAddress] = {
    time(logger, "Fetch floating road addresses by link id - mass query") {
      MassQuery.withIds(linkIds) {
        idTableName =>
          val query =
            s"""
        select ra.id, ra.road_number, ra.road_part_number, ra.road_type, ra.track_code,
        ra.discontinuity, ra.start_addr_m, ra.end_addr_m, ra.link_id, ra.start_measure, ra.end_measure,
        ra.side_code, ra.adjusted_timestamp,
        ra.start_date, ra.end_date, ra.created_by, ra.valid_from, ra.CALIBRATION_POINTS, ra.floating, t.X, t.Y, t2.X, t2.Y, ra.link_source, ra.ely, ra.terminated, ra.common_history_id, ra.valid_to,
        (SELECT rn.road_name FROM ROAD_NAMES rn WHERE rn.ROAD_NUMBER = ra.ROAD_NUMBER AND rn.END_DATE IS NULL AND rn.VALID_TO IS NULL)
        from ROAD_ADDRESS ra cross join
        TABLE(SDO_UTIL.GETVERTICES(ra.geometry)) t cross join
        TABLE(SDO_UTIL.GETVERTICES(ra.geometry)) t2
        join $idTableName i on i.id = ra.link_id
        where ra.floating='1' and t.id < t2.id and
          valid_to is null
      """
          queryList(query)
      }
    }
  }

  def queryFloatingByLinkId(linkIds: Set[Long]): List[RoadAddress] = {
    time(logger, "Fetch floating road addresses by link ids") {
      if (linkIds.size > 1000) {
        return queryFloatingByLinkIdMassQuery(linkIds)
      }
      val linkIdString = linkIds.mkString(",")
      val where = if (linkIds.isEmpty) {
        return List()
      } else {
        s""" where ra.link_id in ($linkIdString)"""
      }
      val query =
        s"""
        select ra.id, ra.road_number, ra.road_part_number, ra.road_type, ra.track_code,
        ra.discontinuity, ra.start_addr_m, ra.end_addr_m, ra.link_id, ra.start_measure, ra.end_measure,
        ra.side_code, ra.adjusted_timestamp,
        ra.start_date, ra.end_date, ra.created_by, ra.valid_from, ra.CALIBRATION_POINTS, ra.floating, t.X, t.Y, t2.X, t2.Y, ra.link_source, ra.ely, ra.terminated, ra.common_history_id, ra.valid_to,
        (SELECT rn.road_name FROM ROAD_NAMES rn WHERE rn.ROAD_NUMBER = ra.ROAD_NUMBER AND rn.END_DATE IS NULL AND rn.VALID_TO IS NULL)
        from ROAD_ADDRESS ra cross join
        TABLE(SDO_UTIL.GETVERTICES(ra.geometry)) t cross join
        TABLE(SDO_UTIL.GETVERTICES(ra.geometry)) t2
        $where AND ra.floating='1' and t.id < t2.id and
          valid_to is null
      """
      queryList(query)
    }
  }

  /**
    * Return road address table rows that are valid by their ids
    *
    * @param ids
    * @return
    */
  def queryById(ids: Set[Long], includeHistory: Boolean = false, includeTerminated: Boolean = false, rejectInvalids: Boolean = true): List[RoadAddress] = {
    time(logger, "Fetch road addresses by ids") {
      if (ids.size > 1000) {
        return queryByIdMassQuery(ids)
      }
      val idString = ids.mkString(",")
      val where = if (ids.isEmpty) {
        return List()
      } else {
        s""" where ra.id in ($idString)"""
      }
      val terminatedFilter = if (!includeTerminated) {
        "AND ra.terminated = 0"
      } else {
        ""
      }

    val historyFilter = if (includeHistory)
      "AND ra.end_date is null"
    else
      ""

    val validToFilter = if (rejectInvalids)
      " and ra.valid_to is null"
    else
      ""

    val query =
      s"""
        select ra.id, ra.road_number, ra.road_part_number, ra.road_type, ra.track_code,
        ra.discontinuity, ra.start_addr_m, ra.end_addr_m, ra.link_id, ra.start_measure, ra.end_measure,
        ra.side_code, ra.adjusted_timestamp,
        ra.start_date, ra.end_date, ra.created_by, ra.valid_from, ra.CALIBRATION_POINTS, ra.floating, t.X, t.Y, t2.X, t2.Y, ra.link_source, ra.ely, ra.terminated, ra.common_history_id, ra.valid_to,
        (SELECT road_name FROM ROAD_NAMES rn WHERE rn.ROAD_NUMBER = ra.ROAD_NUMBER AND rn.END_DATE IS NULL and rn.VALID_TO IS NULL)
        from road_address ra cross join
        TABLE(SDO_UTIL.GETVERTICES(ra.geometry)) t cross join
        TABLE(SDO_UTIL.GETVERTICES(ra.geometry)) t2
        $where $historyFilter $terminatedFilter and t.id < t2.id $validToFilter
      """
      queryList(query)
    }
  }

  def queryByIdMassQuery(ids: Set[Long], includeHistory: Boolean = false, includeTerminated: Boolean = false): List[RoadAddress] = {
    time(logger, "Fetch road addresses by ids - mass query") {
      val terminatedFilter = if (!includeTerminated) {
        "AND ra.terminated = 0"
      } else {
        ""
      }

      val historyFilter = if (includeHistory)
        "AND ra.end_date is null"
      else
        ""

      MassQuery.withIds(ids) {
        idTableName =>
          val query =
            s"""
        select ra.id, ra.road_number, ra.road_part_number, ra.road_type, ra.track_code,
        ra.discontinuity, ra.start_addr_m, ra.end_addr_m, ra.link_id, ra.start_measure, ra.end_measure,
        ra.side_code, ra.adjusted_timestamp,
        ra.start_date, ra.end_date, ra.created_by, ra.valid_from, ra.CALIBRATION_POINTS, ra.floating, t.X, t.Y, t2.X, t2.Y, ra.link_source, ra.ely, ra.terminated, ra.common_history_id, ra.valid_to,
        (SELECT rn.road_name FROM ROAD_NAMES rn WHERE rn.ROAD_NUMBER = ra.ROAD_NUMBER AND rn.END_DATE IS NULL AND rn.VALID_TO IS NULL)
        from ROAD_ADDRESS ra cross join
        TABLE(SDO_UTIL.GETVERTICES(ra.geometry)) t cross join
        TABLE(SDO_UTIL.GETVERTICES(ra.geometry)) t2
        join $idTableName i on i.id = ra.id
        where t.id < t2.id $historyFilter $terminatedFilter and
          valid_to is null
      """
          queryList(query)
      }
    }
  }

  /**
    * Remove Road Addresses (mark them as removed). Don't use more than 1000 road addresses at once.
    *
    * @param roadAddresses Seq[RoadAddress]
    * @return Number of updated rows
    */
  def remove(roadAddresses: Seq[RoadAddress]): Int = {
    val idString = roadAddresses.map(_.id).mkString(",")
    val query =
      s"""
          UPDATE ROAD_ADDRESS SET VALID_TO = sysdate WHERE id IN ($idString)
        """
    Q.updateNA(query).first
  }

  def create(roadAddresses: Iterable[RoadAddress], createdBy : Option[String] = None): Seq[Long] = {
    val addressPS = dynamicSession.prepareStatement("insert into ROAD_ADDRESS (id, road_number, road_part_number, " +
      "track_code, discontinuity, START_ADDR_M, END_ADDR_M, start_date, end_date, created_by, " +
      "VALID_FROM, geometry, floating, calibration_points, ely, road_type, terminated, common_history_id," +
      "link_id, SIDE_CODE, start_measure, end_measure, adjusted_timestamp, link_source) values (?, ?, ?, ?, ?, ?, ?, TO_DATE(?, 'YYYY-MM-DD'), " +
      "TO_DATE(?, 'YYYY-MM-DD'), ?, sysdate, MDSYS.SDO_GEOMETRY(4002, 3067, NULL, MDSYS.SDO_ELEM_INFO_ARRAY(1,2,1), MDSYS.SDO_ORDINATE_ARRAY(" +
      "?,?,0.0,?,?,?,0.0,?)), ?, ?, ?, ?, ?, ?, " +
      "?, ?, ?, ?, ?, ?)")
    val (ready, idLess) = roadAddresses.partition(_.id != NewRoadAddress)
    val plIds = Sequences.fetchViitePrimaryKeySeqValues(idLess.size)
    val createAddresses = ready ++ idLess.zip(plIds).map(x =>
      x._1.copy(id = x._2)
    )
    val savedIds = createAddresses.foreach { case (address) =>
      val nextId = if (address.id == NewRoadAddress) {
        Sequences.nextViitePrimaryKeySeqValue
      } else {
        address.id
      }
      val nextCommonHistoryId = if (address.commonHistoryId == NewCommonHistoryId) {
        Sequences.nextCommonHistorySeqValue
      } else {
        address.commonHistoryId
      }
      addressPS.setLong(1, nextId)
      addressPS.setLong(2, address.roadNumber)
      addressPS.setLong(3, address.roadPartNumber)
      addressPS.setLong(4, address.track.value)
      addressPS.setLong(5, address.discontinuity.value)
      addressPS.setLong(6, address.startAddrMValue)
      addressPS.setLong(7, address.endAddrMValue)
      addressPS.setString(8, address.startDate match {
        case Some(dt) => dateFormatter.print(dt)
        case None => ""
      })
      addressPS.setString(9, address.endDate match {
        case Some(dt) => dateFormatter.print(dt)
        case None => ""
      })
      val newCreatedBy = createdBy.getOrElse(address.createdBy.getOrElse("-"))
      addressPS.setString(10, if (newCreatedBy == null) "-" else newCreatedBy)
      val (p1, p2) = (address.geometry.head, address.geometry.last)
      addressPS.setDouble(11, p1.x)
      addressPS.setDouble(12, p1.y)
      addressPS.setDouble(13, address.startAddrMValue)
      addressPS.setDouble(14, p2.x)
      addressPS.setDouble(15, p2.y)
      addressPS.setDouble(16, address.endAddrMValue)
      addressPS.setInt(17, if (address.floating) 1 else 0)
      addressPS.setInt(18, CalibrationCode.getFromAddress(address).value)
      addressPS.setLong(19, address.ely)
      addressPS.setInt(20, address.roadType.value)
      addressPS.setInt(21, address.terminated.value)
      addressPS.setLong(22, nextCommonHistoryId)
      addressPS.setLong(23, address.linkId)
      addressPS.setLong(24, address.sideCode.value)
      addressPS.setDouble(25, address.startMValue)
      addressPS.setDouble(26, address.endMValue)
      addressPS.setDouble(27, address.adjustedTimestamp)
      addressPS.setInt(28, address.linkGeomSource.value)
      addressPS.addBatch()
    }
    addressPS.executeBatch()
    addressPS.close()
    createAddresses.map(_.id).toSeq
  }

  def roadPartExists(roadNumber:Long, roadPart:Long) :Boolean = {
    val query = s"""SELECT COUNT(1)
            FROM road_address
             WHERE road_number=$roadNumber AND road_part_number=$roadPart AND ROWNUM < 2"""
    if (Q.queryNA[Int](query).first>0) true else false
  }

  def roadNumberExists(roadNumber:Long) :Boolean = {
    val query = s"""SELECT COUNT(1)
            FROM road_address
             WHERE road_number=$roadNumber AND ROWNUM < 2"""
    if (Q.queryNA[Int](query).first>0) true else false
  }

  def getRoadPartInfo(roadNumber:Long, roadPart:Long): Option[(Long,Long,Long,Long,Long,Option[DateTime],Option[DateTime])] =
  {
    val query = s"""SELECT r.id, r.link_id, r.end_addr_M, r.discontinuity, r.ely,
                (Select Max(ra.start_date) from road_address ra Where r.ROAD_PART_NUMBER = ra.ROAD_PART_NUMBER and r.ROAD_NUMBER = ra.ROAD_NUMBER) as start_date,
                (Select Max(ra.end_Date) from road_address ra Where r.ROAD_PART_NUMBER = ra.ROAD_PART_NUMBER and r.ROAD_NUMBER = ra.ROAD_NUMBER) as end_date
                FROM road_address r
             INNER JOIN (Select  MAX(start_addr_m) as lol FROM road_address rm WHERE road_number=$roadNumber AND road_part_number=$roadPart AND
             rm.valid_to is null AND rm.end_date is null AND track_code in (0,1)) ra
             on r.START_ADDR_M=ra.lol
             WHERE r.road_number=$roadNumber AND r.road_part_number=$roadPart AND
             r.valid_to is null AND track_code in (0,1)"""
    Q.queryNA[(Long,Long,Long,Long, Long, Option[DateTime], Option[DateTime])](query).firstOption
  }

  def setSubsequentTermination(linkIds: Set[Long]): Unit = {
    val roadAddresses = fetchByLinkId(linkIds, true, true).filter(_.terminated == NoTermination)
    expireById(roadAddresses.map(_.id).toSet)
    create(roadAddresses.map(ra => ra.copy(id = NewRoadAddress, terminated = Subsequent)))
  }

  def fetchAllCurrentRoads(options: RoadCheckOptions): List[RoadAddress] = {
    time(logger, "Fetch all current road addresses") {
      val road = if (options.roadNumbers.nonEmpty) {
        s"AND ra.ROAD_NUMBER in (${options.roadNumbers.mkString(",")})"
      } else ""

      val query =
        s"""select ra.id, ra.road_number, ra.road_part_number, ra.road_type, ra.track_code,
        ra.discontinuity, ra.start_addr_m, ra.end_addr_m, ra.link_id, ra.start_measure, ra.end_measure,
        ra.side_code, ra.adjusted_timestamp,
        ra.start_date, ra.end_date, ra.created_by, ra.valid_from, ra.CALIBRATION_POINTS, ra.floating, t.X, t.Y, t2.X, t2.Y, ra.link_source, ra.ely, ra.terminated, ra.common_history_id, ra.valid_to,
        (SELECT rn.road_name FROM ROAD_NAMES rn WHERE rn.ROAD_NUMBER = ra.ROAD_NUMBER AND rn.END_DATE IS NULL AND rn.VALID_TO IS NULL)
        from ROAD_ADDRESS ra cross join
        TABLE(SDO_UTIL.GETVERTICES(ra.geometry)) t cross join
        TABLE(SDO_UTIL.GETVERTICES(ra.geometry)) t2
        where t.id < t2.id and
          valid_to is null and ra.terminated = 0 and ra.end_date is null and ra.floating = '0' $road"""
      queryList(query)
    }
  }

  def lockRoadAddressWriting: Unit = {
    sqlu"""LOCK TABLE road_address IN SHARE MODE""".execute
  }


  def getRoadAddressByFilter(queryFilter: String => String): Seq[RoadAddress] = {
    time(logger, "Get road addresses by filter") {
      val query =
        s"""
         select ra.id, ra.road_number, ra.road_part_number, ra.road_type, ra.track_code,
          ra.discontinuity, ra.start_addr_m, ra.end_addr_m, ra.link_id, ra.start_measure, ra.end_measure,
          ra.side_code, ra.adjusted_timestamp,
          ra.start_date, ra.end_date, ra.created_by, ra.valid_from, ra.CALIBRATION_POINTS, ra.floating,
          (SELECT X FROM TABLE(SDO_UTIL.GETVERTICES(ra.geometry)) t WHERE id = 1) as X,
          (SELECT Y FROM TABLE(SDO_UTIL.GETVERTICES(ra.geometry)) t WHERE id = 1) as Y,
          (SELECT X FROM TABLE(SDO_UTIL.GETVERTICES(ra.geometry)) t WHERE id = 2) as X2,
          (SELECT Y FROM TABLE(SDO_UTIL.GETVERTICES(ra.geometry)) t WHERE id = 2) as Y2,
          link_source, ra.ely, ra.terminated, ra.common_history_id, ra.valid_to, null as road_name
        from road_address ra
      """
      queryList(queryFilter(query))
    }
  }

  def withRoadAddress(road: Long, roadPart: Long, track: Option[Int], mValue: Option[Double])(query: String): String = {
    val trackFilter = track match {
      case Some(t) => s"  AND ra.track_code = $t"
      case None => ""
    }
    val mValueFilter = mValue match {
      case Some(v) => s" AND ra.start_addr_M <= $v AND ra.end_addr_M > $v"
      case None => ""
    }
    query + s" WHERE ra.road_number = $road AND ra.road_part_number = $roadPart " +
      s"$trackFilter $mValueFilter " + withValidityCheck
  }

  def withRoadNumber(road: Long, trackCodes: Seq[Int])(query: String): String = {
    val trackFilter = if(trackCodes.nonEmpty) {
      s" AND ra.TRACK_CODE in (${trackCodes.mkString(",")})"
    } else {
       ""
    }
    query + s" WHERE ra.road_number = $road $trackFilter AND ra.floating = 0 " + withValidityCheck
  }

  def withRoadNumberParts(road: Long, roadParts: Seq[Long], trackCodes: Seq[Int])(query: String): String = {
    val trackFilter = if(trackCodes.nonEmpty) {
      s" AND ra.TRACK_CODE in (${trackCodes.mkString(",")})"
    } else {
      ""
    }
    val roadPartFilter = if(roadParts.nonEmpty) {
      s" AND ra.road_part_number in (${roadParts.mkString(",")})"
    } else {
      ""
    }
    query + s" WHERE ra.road_number = $road $roadPartFilter $trackFilter AND ra.floating = 0 " + withValidityCheck
  }

  def withRoadAddressSinglePart(roadNumber: Long, startRoadPartNumber: Long, track: Int, startM: Long, endM: Option[Long], optFloating: Option[Int] = None)(query: String): String = {
    val floating = optFloating match {
      case Some(floatingValue) => s"AND ra.floating = $floatingValue"
      case None => ""
    }

    val endAddr = endM match {
      case Some(endValue) => s"AND ra.start_addr_m <= $endValue"
      case _ => ""
    }

    query + s" where ra.road_number = $roadNumber " +
      s" AND (ra.road_part_number = $startRoadPartNumber AND ra.end_addr_m >= $startM $endAddr) " +
      s" AND ra.TRACK_CODE = $track " + floating + withValidityCheck +
      s" ORDER BY ra.road_number, ra.road_part_number, ra.track_code, ra.start_addr_m "
  }

  def withLinkIdAndMeasure(linkId: Long, startM: Option[Long], endM: Option[Long])(query: String): String = {
    val startFilter = startM match {
      case Some(s) => s" AND ra.start_Measure <= $s"
      case None => ""
    }
    val endFilter = endM match {
      case Some(e) => s" AND ra.end_Measure >= $endM"
      case None => ""
    }

    query + s" WHERE ra.link_id = $linkId $startFilter $endFilter AND floating = 0" + withValidityCheck
  }

  /**
    * Used in RoadAddressDAO.getRoadAddressByFilter and ChangeApi
    *
    * @param sinceDate
    * @param untilDate
    * @param query
    * @return
    */
  def withBetweenDates(sinceDate: DateTime, untilDate: DateTime)(query: String): String = {
    query + s" WHERE ra.start_date >= CAST(TO_TIMESTAMP_TZ(REPLACE(REPLACE('$sinceDate', 'T', ''), 'Z', ''), 'YYYY-MM-DD HH24:MI:SS.FFTZH:TZM') AS DATE)" +
      s" AND ra.start_date <= CAST(TO_TIMESTAMP_TZ(REPLACE(REPLACE('$untilDate', 'T', ''), 'Z', ''), 'YYYY-MM-DD HH24:MI:SS.FFTZH:TZM') AS DATE)"
  }

  /**
    * Used by OTH SearchAPI
    *
    * @return
    */
  def withValidityCheck(): String = {
    s" AND ra.valid_to IS NULL AND ra.end_date IS NULL "
  }

  def getRoadNumbers(): Seq[Long] = {
    sql"""
			select distinct (ra.road_number)
      from road_address ra
      where ra.valid_to is null
		  """.as[Long].list
  }

  def getRoadAddressesFiltered(roadNumber: Long, roadPartNumber: Long, startAddrM: Option[Double], endAddrM: Option[Double]): Seq[RoadAddress] = {
    time(logger, "Get filtered road addresses") {
      val startEndFilter =
        if (startAddrM.nonEmpty && endAddrM.nonEmpty)
          s"""(( ra.start_addr_m >= ${startAddrM.get} and ra.end_addr_m <= ${endAddrM.get} ) or ( ${startAddrM.get} >= ra.start_addr_m and ${startAddrM.get} < ra.end_addr_m) or
         ( ${endAddrM.get} > ra.start_addr_m and ${endAddrM.get} <= ra.end_addr_m)) and"""
        else ""

      val where =
        s""" where $startEndFilter ra.road_number= $roadNumber and ra.road_part_number= $roadPartNumber
         and ra.floating = 0 """ + withValidityCheck

      val query =
        s"""
         select ra.id, ra.road_number, ra.road_part_number, ra.road_type, ra.track_code,
          ra.discontinuity, ra.start_addr_m, ra.end_addr_m, ra.link_id, ra.start_measure, ra.end_measure,
          ra.side_code, ra.adjusted_timestamp,
          ra.start_date, ra.end_date, ra.created_by, ra.valid_from, ra.CALIBRATION_POINTS, ra.floating,
          (SELECT X FROM TABLE(SDO_UTIL.GETVERTICES(ra.geometry)) t WHERE id = 1) as X,
          (SELECT Y FROM TABLE(SDO_UTIL.GETVERTICES(ra.geometry)) t WHERE id = 1) as Y,
          (SELECT X FROM TABLE(SDO_UTIL.GETVERTICES(ra.geometry)) t WHERE id = 2) as X2,
          (SELECT Y FROM TABLE(SDO_UTIL.GETVERTICES(ra.geometry)) t WHERE id = 2) as Y2,
          ra.link_source, ra.ely, ra.terminated, ra.common_history_id, ra.valid_to
        from ROAD_ADDRESS ra
        $where
      """
      queryList(query)
    }
  }

  def getRoadAddressByEly(ely: Long, onlyCurrent: Boolean = false): List[RoadAddress] = {
    time(logger, "Get road addresses by ELY") {

      val current = if (onlyCurrent) {
        " and ra.end_date IS NULL "
      } else {
        ""
      }

      val query =
        s"""select ra.id, ra.road_number, ra.road_part_number, ra.road_type, ra.track_code,
       ra.discontinuity, ra.start_addr_m, ra.end_addr_m, ra.link_id, ra.start_measure, ra.end_measure,
       ra.side_code, ra.adjusted_timestamp,
       ra.start_date, ra.end_date, ra.created_by, ra.valid_from, ra.CALIBRATION_POINTS, ra.floating, t.X, t.Y, t2.X, t2.Y, ra.link_source, ra.ely, ra.terminated, ra.common_history_id, ra.valid_to,
       (SELECT rn.road_name FROM ROAD_NAMES rn WHERE rn.ROAD_NUMBER = ra.ROAD_NUMBER AND rn.END_DATE IS NULL AND rn.VALID_TO IS NULL)
        from ROAD_ADDRESS ra cross join
        TABLE(SDO_UTIL.GETVERTICES(ra.geometry)) t cross join
        TABLE(SDO_UTIL.GETVERTICES(ra.geometry)) t2
        where t.id < t2.id and
          ra.floating = 0 and valid_to is null and ra.ely = $ely $current"""
      queryList(query)
    }
  }

  /*
   * Get the calibration code of the given road address.
   *
   * @param roadAddressId id of the road link in ROAD_ADDRESS table
   * @return CalibrationCode of the road address (No = 0, AtEnd = 1, AtBeginning = 2, AtBoth = 3).
   *
   * Note that function returns CalibrationCode.No (0) if no road address was found with roadAddressId.
   */
  def getRoadAddressCalibrationCode(roadAddressId: Long): CalibrationCode = {
    val query =
      s"""SELECT ra.calibration_points
                    FROM road_address ra
                    WHERE ra.id=$roadAddressId"""
    CalibrationCode(Q.queryNA[Long](query).firstOption.getOrElse(0L).toInt)
  }


  /*
   * Get the calibration code of the given road addresses.
   *
   * @param roadAddressId id of the road link in ROAD_ADDRESS table
   * @return CalibrationCode of the road address (No = 0, AtEnd = 1, AtBeginning = 2, AtBoth = 3).
   *
   * Note that function returns CalibrationCode.No (0) if no road address was found with roadAddressId.
   */
  def getRoadAddressCalibrationCode(roadAddressIds: Seq[Long]): Map[Long, CalibrationCode] = {
    if (roadAddressIds.isEmpty) {
      Map()
    } else {
      val query =
        s"""SELECT ra.id, ra.calibration_points
                    FROM road_address ra
                    WHERE ra.id in (${roadAddressIds.mkString(",")})"""
      Q.queryNA[(Long, Int)](query).list.map {
        case (id, code) => id -> CalibrationCode(code)
      }.toMap
    }


  }
}

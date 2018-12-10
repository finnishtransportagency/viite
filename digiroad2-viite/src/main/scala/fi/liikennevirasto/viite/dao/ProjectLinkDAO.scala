package fi.liikennevirasto.viite.dao

import java.util.Date

import com.github.tototoshi.slick.MySQLJodaSupport._
import fi.liikennevirasto.digiroad2.{GeometryUtils, Point, Vector3d}
import fi.liikennevirasto.digiroad2.asset.{LinkGeomSource, SideCode}
import fi.liikennevirasto.digiroad2.dao.Sequences
import fi.liikennevirasto.digiroad2.linearasset.PolyLine
import fi.liikennevirasto.digiroad2.oracle.OracleDatabase
import fi.liikennevirasto.digiroad2.util.LogUtils.time
import fi.liikennevirasto.digiroad2.util.Track
import fi.liikennevirasto.digiroad2.{GeometryUtils, Point, Vector3d}
import fi.liikennevirasto.viite._
import fi.liikennevirasto.viite.dao.CalibrationPointDAO.BaseCalibrationPoint
import fi.liikennevirasto.viite.dao.CalibrationPointSource.UnknownSource
import fi.liikennevirasto.viite.dao.FloatingReason.NoFloating
import fi.liikennevirasto.viite.dao.LinkStatus.{NotHandled, UnChanged}
import fi.liikennevirasto.viite.process.InvalidAddressDataException
import fi.liikennevirasto.viite.util.CalibrationPointsUtils
import org.joda.time.DateTime
import org.slf4j.LoggerFactory
import slick.driver.JdbcDriver.backend.Database.dynamicSession
import slick.jdbc.StaticQuery.interpolation
import slick.jdbc.{GetResult, PositionedResult, StaticQuery => Q}

//TODO naming SQL conventions

sealed trait CalibrationPointSource {
  def value: Int
}

object CalibrationPointSource {
  val values = Set(RoadAddressSource, ProjectLinkSource, UnknownSource)

  def apply(intValue: Int): CalibrationPointSource = values.find(_.value == intValue).getOrElse(UnknownSource)

  case object NoCalibrationPoint extends CalibrationPointSource {def value = 0;}
  case object RoadAddressSource extends CalibrationPointSource {def value = 1;}
  case object ProjectLinkSource extends CalibrationPointSource {def value = 2;}
  case object UnknownSource extends CalibrationPointSource{def value = 99;}
}

sealed trait LinkStatus {
  def value: Int
}

object LinkStatus {
  val values = Set(NotHandled, Terminated, New, Transfer, UnChanged, Numbering, Unknown)
  case object NotHandled extends LinkStatus {def value = 0}
  case object UnChanged  extends LinkStatus {def value = 1}
  case object New extends LinkStatus {def value = 2}
  case object Transfer extends LinkStatus {def value = 3}
  case object Numbering extends LinkStatus {def value = 4}
  case object Terminated extends LinkStatus {def value = 5}
  case object Unknown extends LinkStatus {def value = 99}
  def apply(intValue: Int): LinkStatus = {
    values.find(_.value == intValue).getOrElse(Unknown)
  }
}

case class ProjectLinkCalibrationPoint(linkId: Long, override val segmentMValue: Double, override val addressMValue: Long, source: CalibrationPointSource = UnknownSource)
  extends BaseCalibrationPoint {

  def toCalibrationPoint: CalibrationPoint = {
    CalibrationPoint(linkId, segmentMValue, addressMValue)
  }
}

case class ProjectLink(id: Long, roadNumber: Long, roadPartNumber: Long, track: Track,
                       discontinuity: Discontinuity, startAddrMValue: Long, endAddrMValue: Long, originalStartAddrMValue: Long, originalEndAddrMValue: Long, startDate: Option[DateTime] = None,
                       endDate: Option[DateTime] = None, createdBy: Option[String] = None, linkId: Long, startMValue: Double, endMValue: Double, sideCode: SideCode,
                       calibrationPoints: (Option[ProjectLinkCalibrationPoint], Option[ProjectLinkCalibrationPoint]) = (None, None), floating: FloatingReason = NoFloating,
                       geometry: Seq[Point], projectId: Long, status: LinkStatus, roadType: RoadType,
                       linkGeomSource: LinkGeomSource = LinkGeomSource.NormalLinkInterface, geometryLength: Double, roadwayId: Long, linearLocationId: Long,
                       ely: Long, reversed: Boolean, connectedLinkId: Option[Long] = None, linkGeometryTimeStamp: Long, roadwayNumber: Long = NewRoadwayNumber, roadName: Option[String] = None, roadAddressLength: Option[Long] = None,
                       roadAddressStartAddrM: Option[Long] = None, roadAddressEndAddrM: Option[Long] = None, roadAddressTrack: Option[Track] = None, roadAddressRoadNumber: Option[Long] = None, roadAddressRoadPart: Option[Long] = None)
  extends BaseRoadAddress with PolyLine {

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
  lazy val isSplit: Boolean = connectedLinkId.nonEmpty || connectedLinkId.contains(0L)

  def getEndPoints() = {
    if (sideCode == SideCode.Unknown) {
      val direction = if(geometry.head.y == geometry.last.y) Vector3d(1.0, 0.0, 0.0) else Vector3d(0.0, 1.0, 0.0)
      Seq((geometry.head, geometry.last), (geometry.last, geometry.head)).minBy(ps => direction.dot(ps._1.toVector - ps._2.toVector))
    } else {
      (startingPoint, endPoint)
    }
  }

  def oppositeEndPoint(point: Point) : Point = {
    if (GeometryUtils.areAdjacent(point, geometry.head)) geometry.last else geometry.head
  }

  def copyWithGeometry(newGeometry: Seq[Point]): ProjectLink = {
    this.copy(geometry = newGeometry)
  }

  def addrAt(a: Double): Long = {
    val coefficient = (endAddrMValue - startAddrMValue) / (endMValue - startMValue)
    sideCode match {
      case SideCode.AgainstDigitizing =>
        endAddrMValue - Math.round((a-startMValue) * coefficient)
      case SideCode.TowardsDigitizing =>
        startAddrMValue + Math.round((a-startMValue) * coefficient)
      case _ => throw new InvalidAddressDataException(s"Bad sidecode $sideCode on project link")
    }
  }

  def addrMLength(): Long = {
    if (isSplit)
      endAddrMValue - startAddrMValue
    else
      roadAddressLength.getOrElse(endAddrMValue - startAddrMValue)
  }

  def getFirstPoint: Point = {
    if (sideCode == SideCode.TowardsDigitizing) geometry.head else geometry.last
  }

  def getLastPoint: Point = {
    if (sideCode == SideCode.TowardsDigitizing) geometry.last else geometry.head
  }

  def toMeters(address: Long): Double = {
    val coefficient = (endMValue - startMValue) / (endAddrMValue - startAddrMValue)
    coefficient * address
  }

  def toCalibrationPoints: (Option[CalibrationPoint], Option[CalibrationPoint]) = {
    calibrationPoints match {
      case (None, None) => (Option.empty[CalibrationPoint], Option.empty[CalibrationPoint])
      case (Some(cp1), None) => (Option(cp1.toCalibrationPoint), Option.empty[CalibrationPoint])
      case (None, Some(cp1)) => (Option.empty[CalibrationPoint], Option(cp1.toCalibrationPoint))
      case (Some(cp1),Some(cp2)) => (Option(cp1.toCalibrationPoint), Option(cp2.toCalibrationPoint))
    }
  }

  def getCalibrationSources:(Option[CalibrationPointSource],Option[CalibrationPointSource]) = {
    calibrationPoints match {
      case (None, None) => (Option.empty[CalibrationPointSource], Option.empty[CalibrationPointSource])
      case (Some(cp1), None) => (Option(cp1.source) ,Option.empty[CalibrationPointSource])
      case (None, Some(cp1)) => (Option.empty[CalibrationPointSource], Option(cp1.source))
      case (Some(cp1),Some(cp2)) => (Option(cp1.source), Option(cp2.source))
    }
  }

  def calibrationPointsSourcesToDB(): CalibrationPointSource = {
    calibrationPoints match {
      case (None, None) => CalibrationPointSource.NoCalibrationPoint
      case (Some(cp1), None) => cp1.source
      case (None, Some(cp1)) => cp1.source
      case (Some(cp1),Some(cp2)) => cp1.source
    }
  }

  def hasCalibrationPointAt(addressMValue: Long): Boolean = {
    calibrationPoints match {
      case (None, None) => false
      case (Some(cp1), None) => cp1.addressMValue == addressMValue
      case (None, Some(cp1)) => cp1.addressMValue == addressMValue
      case (Some(cp1), Some(cp2)) => cp1.addressMValue == addressMValue || cp2.addressMValue == addressMValue
    }
  }
}

class ProjectLinkDAO {
  private def logger = LoggerFactory.getLogger(getClass)
  val roadwayDAO = new RoadwayDAO

  private val projectLinkQueryBase =
    s"""select PROJECT_LINK.ID, PROJECT_LINK.PROJECT_ID, PROJECT_LINK.TRACK, PROJECT_LINK.DISCONTINUITY_TYPE,
  PROJECT_LINK.ROAD_NUMBER, PROJECT_LINK.ROAD_PART_NUMBER, PROJECT_LINK.START_ADDR_M, PROJECT_LINK.END_ADDR_M,
  PROJECT_LINK.ORIGINAL_START_ADDR_M, PROJECT_LINK.ORIGINAL_END_ADDR_M,
  PROJECT_LINK.START_MEASURE, PROJECT_LINK.END_MEASURE, PROJECT_LINK.SIDE,
  PROJECT_LINK.CREATED_BY, PROJECT_LINK.MODIFIED_BY, PROJECT_LINK.LINK_ID, PROJECT_LINK.GEOMETRY,
  (PROJECT_LINK.END_MEASURE - PROJECT_LINK.START_MEASURE) as length, PROJECT_LINK.CALIBRATION_POINTS, PROJECT_LINK.STATUS,
  PROJECT_LINK.ROAD_TYPE, PROJECT_LINK.LINK_SOURCE as source, PROJECT_LINK.ROADWAY_ID, PROJECT_LINK.LINEAR_LOCATION_ID, PROJECT_LINK.ELY, PROJECT_LINK.REVERSED, PROJECT_LINK.CONNECTED_LINK_ID,
  CASE
    WHEN STATUS = ${LinkStatus.NotHandled.value} THEN null
    WHEN STATUS IN (${LinkStatus.Terminated.value}, ${LinkStatus.UnChanged.value}) THEN ROADWAY.START_DATE
    ELSE PRJ.START_DATE END as start_date,
  CASE WHEN STATUS = ${LinkStatus.Terminated.value} THEN PRJ.START_DATE ELSE null END as end_date,
  PROJECT_LINK.ADJUSTED_TIMESTAMP,
  CASE
    WHEN rn.road_name IS NOT NULL AND rn.END_DATE IS NULL AND rn.VALID_TO IS null THEN rn.road_name
    WHEN rn.road_name IS NULL AND pln.road_name IS NOT NULL THEN pln.road_name
    END AS road_name_pl,
  PROJECT_LINK.ORIGINAL_START_ADDR_M as RA_START_ADDR_M,
  PROJECT_LINK.ORIGINAL_END_ADDR_M as RA_END_ADDR_M,
  ROADWAY.TRACK as TRACK,
  ROADWAY.ROAD_NUMBER as ROAD_NUMBER,
  ROADWAY.ROAD_PART_NUMBER as ROAD_PART_NUMBER,
  PROJECT_LINK.CALIBRATION_POINTS_SOURCE
  from PROJECT prj JOIN PROJECT_LINK ON (prj.id = PROJECT_LINK.PROJECT_ID)
    LEFT JOIN ROADWAY ON (ROADWAY.ID = PROJECT_LINK.ROADWAY_ID)
    LEFT JOIN Linear_Location ON (Linear_Location.ID = PROJECT_LINK.Linear_Location_Id)
    LEFT JOIN ROAD_NAME rn ON (rn.road_number = project_link.road_number AND rn.END_DATE IS NULL AND rn.VALID_TO IS null)
	  LEFT JOIN project_link_name pln ON (pln.road_number = project_link.road_number AND pln.project_id = project_link.project_id)  """

  private val projectLinkHistoryQueryBase =
    s"""
        select plh.ID, plh.PROJECT_ID, plh.TRACK, plh.DISCONTINUITY_TYPE,
          plh.ROAD_NUMBER, plh.ROAD_PART_NUMBER, plh.START_ADDR_M, plh.END_ADDR_M,
          plh.ORIGINAL_START_ADDR_M, plh.ORIGINAL_END_ADDR_M,
          plh.START_MEASURE, plh.END_MEASURE, plh.SIDE,
          plh.CREATED_BY, plh.MODIFIED_BY, plh.LINK_ID, plh.GEOMETRY,
          (plh.END_MEASURE - plh.START_MEASURE) as length, plh.CALIBRATION_POINTS, plh.STATUS,
          plh.ROAD_TYPE, plh.LINK_SOURCE as source, plh.ROADWAY_ID, plh.Linear_Location_Id, plh.ELY, plh.REVERSED, plh.CONNECTED_LINK_ID,
          CASE
            WHEN STATUS = ${LinkStatus.NotHandled.value} THEN null
            WHEN STATUS IN (${LinkStatus.Terminated.value}, ${LinkStatus.UnChanged.value}) THEN ROADWAY.START_DATE
            ELSE PRJ.START_DATE END as start_date,
          CASE WHEN STATUS = ${LinkStatus.Terminated.value} THEN PRJ.START_DATE ELSE null END as end_date,
          plh.ADJUSTED_TIMESTAMP,
          CASE
            WHEN rn.road_name IS NOT NULL AND rn.END_DATE IS NULL AND rn.VALID_TO IS null THEN rn.road_name
            WHEN rn.road_name IS NULL AND pln.road_name IS NOT NULL THEN pln.road_name
            END AS road_name_pl,
          ROADWAY.START_ADDR_M as RA_START_ADDR_M,
          ROADWAY.END_ADDR_M as RA_END_ADDR_M,
          ROADWAY.TRACK as TRACK,
          ROADWAY.ROAD_NUMBER as ROAD_NUMBER,
          ROADWAY.ROAD_PART_NUMBER as ROAD_PART_NUMBER,
          plh.CALIBRATION_POINTS_SOURCE
          from PROJECT prj JOIN PROJECT_LINK_HISTORY plh ON (prj.id = plh.PROJECT_ID)
            LEFT JOIN ROADWAY ON (ROADWAY.ID = plh.ROADWAY_ID)
            LEFT JOIN Linear_Location ON (Linear_Location.ID = plh.Linear_Location_Id)
            LEFT JOIN ROAD_NAME rn ON (rn.road_number = plh.road_number AND rn.END_DATE IS NULL AND rn.VALID_TO IS null)
        	  LEFT JOIN project_link_name pln ON (pln.road_number = plh.road_number AND pln.project_id = plh.project_id)
     """.stripMargin

  implicit val getProjectLinkRow: GetResult[ProjectLink] = new GetResult[ProjectLink] {
    def apply(r: PositionedResult) = {
      val projectLinkId = r.nextLong()
      val projectId = r.nextLong()
      val trackCode = Track.apply(r.nextInt())
      val discontinuityType = Discontinuity.apply(r.nextInt())
      val roadNumber = r.nextLong()
      val roadPartNumber = r.nextLong()
      val startAddrM = r.nextLong()
      val endAddrM = r.nextLong()
      val originalStartAddrMValue = r.nextLong()
      val originalEndAddrMValue = r.nextLong()
      val startMValue = r.nextDouble()
      val endMValue = r.nextDouble()
      val sideCode = SideCode.apply(r.nextInt)
      val createdBy = r.nextStringOption()
      val modifiedBy = r.nextStringOption()
      val linkId = r.nextLong()
      val rawGeom = r.nextObjectOption()
      val decodedGeom = OracleDatabase.loadJGeometryToGeometry(rawGeom)
      val length = r.nextDouble()
      val calibrationPoints =
        CalibrationPointsUtils.calibrations(CalibrationCode.apply(r.nextInt), linkId, startMValue, endMValue,
          startAddrM, endAddrM, sideCode)
      val status = LinkStatus.apply(r.nextInt())
      val roadType = RoadType.apply(r.nextInt())
      val source = LinkGeomSource.apply(r.nextInt())
      val roadwayId = r.nextLong()
      val linearLocationId = r.nextLong()
      val ely = r.nextLong()
      val reversed = r.nextBoolean()
      val connectedLinkId = r.nextLongOption()
      val startDate = r.nextDateOption().map(d => new DateTime(d.getTime))
      val endDate = r.nextDateOption().map(d => new DateTime(d.getTime))
      val geometryTimeStamp = r.nextLong()
      val roadName = r.nextString()
      val roadAddressStartAddrM = r.nextLongOption()
      val roadAddressEndAddrM = r.nextLongOption()
      val roadAddressTrack = r.nextIntOption().map(Track.apply)
      val roadAddressRoadNumber = r.nextLongOption()
      val roadAddressRoadPart = r.nextLongOption()
      val calibrationPointsSource = CalibrationPointSource.apply(r.nextIntOption().getOrElse(99))

      ProjectLink(projectLinkId, roadNumber, roadPartNumber, trackCode, discontinuityType, startAddrM, endAddrM, originalStartAddrMValue, originalEndAddrMValue, startDate, endDate,
        modifiedBy, linkId, startMValue, endMValue, sideCode, CalibrationPointsUtils.toProjectLinkCalibrationPointsWithSourceInfo(calibrationPoints, calibrationPointsSource), NoFloating, decodedGeom, projectId,
        status, roadType, source, length, roadwayId, linearLocationId, ely, reversed, connectedLinkId, geometryTimeStamp, NewRoadwayNumber, Some(roadName),
        roadAddressEndAddrM.map(endAddr => endAddr - roadAddressStartAddrM.getOrElse(0L)),
        roadAddressStartAddrM, roadAddressEndAddrM, roadAddressTrack,
        roadAddressRoadNumber, roadAddressRoadPart)
    }
  }

  private def listQuery(query: String) = {
    Q.queryNA[ProjectLink](query).iterator.toSeq
  }

  def create(links: Seq[ProjectLink]): Seq[Long] = {
    time(logger, "Create project links") {
      val addressPS = dynamicSession.prepareStatement("insert into PROJECT_LINK (id, project_id, " +
        "road_number, road_part_number, " +
        "TRACK, discontinuity_type, START_ADDR_M, END_ADDR_M, ORIGINAL_START_ADDR_M, ORIGINAL_END_ADDR_M, created_by, " +
        "calibration_points, status, road_type, roadway_id, linear_location_id, connected_link_id, ely, reversed, geometry, " +
        "link_id, SIDE, start_measure, end_measure, adjusted_timestamp, link_source, calibration_points_source) values " +
        "(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
      val (ready, idLess) = links.partition(_.id != NewRoadway)
      val plIds = Sequences.fetchViitePrimaryKeySeqValues(idLess.size)
      val projectLinks = ready ++ idLess.zip(plIds).map(x =>
        x._1.copy(id = x._2)
      )
      projectLinks.toList.foreach { pl =>
        addressPS.setLong(1, pl.id)
        addressPS.setLong(2, pl.projectId)
        addressPS.setLong(3, pl.roadNumber)
        addressPS.setLong(4, pl.roadPartNumber)
        addressPS.setLong(5, pl.track.value)
        addressPS.setLong(6, pl.discontinuity.value)
        addressPS.setLong(7, pl.startAddrMValue)
        addressPS.setLong(8, pl.endAddrMValue)
        addressPS.setLong(9, pl.originalStartAddrMValue)
        addressPS.setLong(10, pl.originalEndAddrMValue)
        addressPS.setString(11, pl.createdBy.orNull)
        addressPS.setDouble(12, CalibrationCode.getFromAddress(pl).value)
        addressPS.setLong(13, pl.status.value)
        addressPS.setLong(14, pl.roadType.value)
        if (pl.roadwayId == 0)
          addressPS.setString(15, null)
        else
          addressPS.setLong(15, pl.roadwayId)
        if (pl.linearLocationId == 0)
          addressPS.setString(16, null)
        else
          addressPS.setLong(16, pl.linearLocationId)
        if (pl.connectedLinkId.isDefined)
          addressPS.setLong(17, pl.connectedLinkId.get)
        else
          addressPS.setString(17, null)
        addressPS.setLong(18, pl.ely)
        addressPS.setBoolean(19, pl.reversed)
        addressPS.setObject(20, OracleDatabase.createJGeometry(pl.geometry, dynamicSession.conn))
        addressPS.setLong(21, pl.linkId)
        addressPS.setLong(22, pl.sideCode.value)
        addressPS.setDouble(23, pl.startMValue)
        addressPS.setDouble(24, pl.endMValue)
        addressPS.setDouble(25, pl.linkGeometryTimeStamp)
        addressPS.setInt(26, pl.linkGeomSource.value)
        addressPS.setInt(27, pl.calibrationPointsSourcesToDB().value)
        addressPS.addBatch()
      }
      addressPS.executeBatch()
      addressPS.close()
      projectLinks.map(_.id)
    }

  }

  def updateProjectLinks(projectLinks: Seq[ProjectLink], modifier: String, addresses: Seq[RoadAddress]): Unit = {
    time(logger, "Update project links") {
      val nonUpdatingStatus = Set[LinkStatus](NotHandled, UnChanged)
      val maxInEachTracks = projectLinks.filter(pl => pl.status == UnChanged).groupBy(_.track).map(p => p._2.maxBy(_.endAddrMValue).id).toSeq
      val links = projectLinks.map { pl =>
        if (!pl.isSplit && nonUpdatingStatus.contains(pl.status) && addresses.map(_.linearLocationId).contains(pl.linearLocationId) && !maxInEachTracks.contains(pl.id)) {
          val ra = addresses.find(_.linearLocationId == pl.linearLocationId).get
          // Discontinuity, road type and calibration points may change with Unchanged (and NotHandled) status
          pl.copy(roadNumber = ra.roadNumber, roadPartNumber = ra.roadPartNumber, track = ra.track,
            startAddrMValue = ra.startAddrMValue, endAddrMValue = ra.endAddrMValue,
            reversed = false)
        } else
          pl
      }
      val projectLinkPS = dynamicSession.prepareStatement("UPDATE project_link SET ROAD_NUMBER = ?,  ROAD_PART_NUMBER = ?, TRACK = ?, " +
        "DISCONTINUITY_TYPE = ?, START_ADDR_M=?, END_ADDR_M=?, ORIGINAL_START_ADDR_M=?, ORIGINAL_END_ADDR_M=?, MODIFIED_DATE= ? , MODIFIED_BY= ?, PROJECT_ID= ?, " +
        "CALIBRATION_POINTS= ? , STATUS=?, ROAD_TYPE=?, REVERSED = ?, GEOMETRY = ?, " +
        "SIDE=?, START_MEASURE=?, END_MEASURE=?, CALIBRATION_POINTS_SOURCE=? WHERE id = ?")

      for (projectLink <- links) {
        projectLinkPS.setLong(1, projectLink.roadNumber)
        projectLinkPS.setLong(2, projectLink.roadPartNumber)
        projectLinkPS.setInt(3, projectLink.track.value)
        projectLinkPS.setInt(4, projectLink.discontinuity.value)
        projectLinkPS.setLong(5, projectLink.startAddrMValue)
        projectLinkPS.setLong(6, projectLink.endAddrMValue)
        projectLinkPS.setLong(7, projectLink.originalStartAddrMValue)
        projectLinkPS.setLong(8, projectLink.originalEndAddrMValue)
        projectLinkPS.setDate(9, new java.sql.Date(new Date().getTime))
        projectLinkPS.setString(10, modifier)
        projectLinkPS.setLong(11, projectLink.projectId)
        projectLinkPS.setInt(12, CalibrationCode.getFromAddress(projectLink).value)
        projectLinkPS.setInt(13, projectLink.status.value)
        projectLinkPS.setInt(14, projectLink.roadType.value)
        projectLinkPS.setInt(15, if (projectLink.reversed) 1 else 0)
        projectLinkPS.setObject(16, OracleDatabase.createJGeometry(projectLink.geometry, dynamicSession.conn))
        projectLinkPS.setInt(17, projectLink.sideCode.value)
        projectLinkPS.setDouble(18, projectLink.startMValue)
        projectLinkPS.setDouble(19, projectLink.endMValue)
        projectLinkPS.setLong(20, projectLink.calibrationPointsSourcesToDB().value)
        projectLinkPS.setLong(21, projectLink.id)
        projectLinkPS.addBatch()
      }
      projectLinkPS.executeBatch()
      projectLinkPS.close()
    }
  }

  def updateProjectLinksGeometry(projectLinks: Seq[ProjectLink], modifier: String): Unit = {
    time(logger,
      "Update project links geometry") {
      val projectLinkPS = dynamicSession.prepareStatement("UPDATE project_link SET  GEOMETRY = ?, MODIFIED_BY= ?, ADJUSTED_TIMESTAMP = ? WHERE id = ?")

      for (projectLink <- projectLinks) {
        projectLinkPS.setObject(1, OracleDatabase.createJGeometry(projectLink.geometry, dynamicSession.conn))
        projectLinkPS.setString(2, modifier)
        projectLinkPS.setLong(3, projectLink.linkGeometryTimeStamp)
        projectLinkPS.setLong(4, projectLink.id)
        projectLinkPS.addBatch()
      }
      projectLinkPS.executeBatch()
      projectLinkPS.close()
    }
  }

  def fetchElyFromProjectLinks(projectId:Long): Option[Long]= {
    val query =
      s"""SELECT ELY FROM PROJECT_LINK WHERE PROJECT_ID=$projectId AND ELY IS NOT NULL AND ROWNUM < 2"""
    Q.queryNA[Long](query).firstOption
  }

  def fetchProjectLinksHistory(projectId: Long, linkStatusFilter: Option[LinkStatus] = None): Seq[ProjectLink] = {
    time(logger, "Get project history links") {
      val filter = if (linkStatusFilter.isEmpty) "" else s"plh.STATUS = ${linkStatusFilter.get.value} AND"
      val query =
        s"""$projectLinkHistoryQueryBase
                where $filter plh.PROJECT_ID = $projectId order by plh.ROAD_NUMBER, plh.ROAD_PART_NUMBER, plh.END_ADDR_M """
      listQuery(query)
    }
  }

  def fetchProjectLinks(projectId: Long, linkStatusFilter: Option[LinkStatus] = None): Seq[ProjectLink] = {
    time(logger, "Get project links") {
      val filter = if (linkStatusFilter.isEmpty) "" else s"PROJECT_LINK.STATUS = ${linkStatusFilter.get.value} AND"
      val query =
        s"""$projectLinkQueryBase
                where $filter PROJECT_LINK.PROJECT_ID = $projectId order by PROJECT_LINK.ROAD_NUMBER, PROJECT_LINK.ROAD_PART_NUMBER, PROJECT_LINK.END_ADDR_M """
      listQuery(query)
    }
  }

  //TODO: support for bigger queries than 1000 ids
  def fetchProjectLinksByIds(ids: Iterable[Long]): Seq[ProjectLink] = {
    time(logger, "Get project links by ids") {
      if (ids.isEmpty)
        List()
      else {
        val query =
          s"""$projectLinkQueryBase
                where project_link.id in (${ids.mkString(",")}) order by PROJECT_LINK.ROAD_NUMBER, PROJECT_LINK.ROAD_PART_NUMBER, PROJECT_LINK.END_ADDR_M """
        listQuery(query)
      }
    }
  }

  def fetchProjectLinksByConnectedLinkId(connectedIds: Seq[Long]): Seq[ProjectLink] = {
    time(logger, "Get project links by connected link ids") {
      if (connectedIds.isEmpty) {
        List()
      } else {
        val query =
          s"""$projectLinkQueryBase
                where project_link.connected_link_id in (${connectedIds.mkString(",")}) order by PROJECT_LINK.ROAD_NUMBER, PROJECT_LINK.ROAD_PART_NUMBER, PROJECT_LINK.END_ADDR_M """
        listQuery(query)
      }
    }
  }

  def getProjectLinksByLinkId(projectLinkId: Long): Seq[ProjectLink] = {
    time(logger, "Get project links by link id and project id") {
      val query =
        s"""$projectLinkQueryBase
                where PROJECT_LINK.link_id = $projectLinkId order by PROJECT_LINK.ROAD_NUMBER, PROJECT_LINK.ROAD_PART_NUMBER, PROJECT_LINK.END_ADDR_M """
      listQuery(query)
    }
  }

  def getProjectLinksByIds(projectId: Long, ids: Set[Long]): Seq[ProjectLink] = {
    time(logger, "Get project links by ids") {
      val filter = if (ids.nonEmpty) s"""AND PROJECT_LINK.LINEAR_LOCATION_ID in (${ids.mkString(",")})""" else ""
      val query =

        s"""$projectLinkQueryBase
                where PROJECT_LINK.PROJECT_ID = $projectId $filter"""
      listQuery(query).seq
    }
  }

  def fetchProjectLinksByProjectAndLinkId(projectLinkIds: Set[Long], linkIds: Seq[Long], projectId: Long): Seq[ProjectLink] = {
    if (projectLinkIds.isEmpty && linkIds.isEmpty) {
      List()
    } else {
      val idsFilter = if (projectLinkIds.nonEmpty) s"AND PROJECT_LINK.ID IN (${projectLinkIds.mkString(",")})" else ""
      val linkIdsFilter = if (linkIds.nonEmpty) s"AND PROJECT_LINK.LINK_ID IN (${linkIds.mkString(",")})" else ""
      val query =
        s"""$projectLinkQueryBase
                where PROJECT_LINK.PROJECT_ID = $projectId $idsFilter $linkIdsFilter
                order by PROJECT_LINK.ROAD_NUMBER, PROJECT_LINK.ROAD_PART_NUMBER, PROJECT_LINK.END_ADDR_M """
      listQuery(query)
    }
  }

  def fetchProjectLinksByLinkId(linkIds: Seq[Long]): Seq[ProjectLink] = {
    if (linkIds.isEmpty) {
      List()
    } else {
      val linkIdsFilter =  s" PROJECT_LINK.LINK_ID IN (${linkIds.mkString(",")})"
      val query =
        s"""$projectLinkQueryBase
                where $linkIdsFilter
                order by PROJECT_LINK.ROAD_NUMBER, PROJECT_LINK.ROAD_PART_NUMBER, PROJECT_LINK.END_ADDR_M """
      listQuery(query)
    }
  }

  def fetchProjectLinksByProjectRoadPart(road: Long, part: Long, projectId: Long, linkStatusFilter: Option[LinkStatus] = None): Seq[ProjectLink] = {
    time(logger, "Get project links by project road part") {
      val filter = if (linkStatusFilter.isEmpty) "" else s" PROJECT_LINK.STATUS = ${linkStatusFilter.get.value} AND"
      val query =
        s"""$projectLinkQueryBase
                where $filter PROJECT_LINK.ROAD_NUMBER = $road and PROJECT_LINK.ROAD_PART_NUMBER = $part AND PROJECT_LINK.PROJECT_ID = $projectId order by PROJECT_LINK.ROAD_NUMBER, PROJECT_LINK.ROAD_PART_NUMBER, PROJECT_LINK.END_ADDR_M """
      listQuery(query)
    }
  }

  def fetchByProjectRoadPart(roadNumber: Long, roadPartNumber: Long, projectId: Long): Seq[ProjectLink] = {
    time(logger, "Fetch project links by project road part") {
      val filter = s"PROJECT_LINK.ROAD_NUMBER = $roadNumber AND PROJECT_LINK.ROAD_PART_NUMBER = $roadPartNumber AND"
      val query =
        s"""$projectLinkQueryBase
                where $filter (PROJECT_LINK.PROJECT_ID = $projectId ) order by PROJECT_LINK.ROAD_NUMBER, PROJECT_LINK.ROAD_PART_NUMBER, PROJECT_LINK.END_ADDR_M """
      listQuery(query)
    }
  }

  def fetchByProjectRoadParts(roadParts: Set[(Long, Long)], projectId: Long): Seq[ProjectLink] = {
    time(logger, "Fetch project links by project road parts") {
      if (roadParts.isEmpty)
        return Seq()
      val roadPartsCond = roadParts.map { case (road, part) => s"(PROJECT_LINK.ROAD_NUMBER = $road AND PROJECT_LINK.ROAD_PART_NUMBER = $part)" }
      val filter = s"${roadPartsCond.mkString("(", " OR ", ")")} AND"
      val query =
        s"""$projectLinkQueryBase
                where $filter (PROJECT_LINK.PROJECT_ID = $projectId) order by PROJECT_LINK.ROAD_NUMBER, PROJECT_LINK.ROAD_PART_NUMBER, PROJECT_LINK.END_ADDR_M """
      listQuery(query)
    }
  }

  def updateAddrMValues(projectLink: ProjectLink): Unit = {
    sqlu"""update project_link set modified_date = sysdate, start_addr_m = ${projectLink.startAddrMValue}, end_addr_m = ${projectLink.endAddrMValue}, calibration_points = ${CalibrationCode.getFromAddress(projectLink).value} where id = ${projectLink.id}
          """.execute
  }

  def updateProjectLinkNumbering(ids: Seq[Long], linkStatus: LinkStatus, newRoadNumber: Long, newRoadPart: Long, userName: String, discontinuity: Long): Unit = {
    time(logger, "Update project link numbering") {
      val user = userName.replaceAll("[^A-Za-z0-9\\-]+", "")
      val sql = s"UPDATE PROJECT_LINK SET STATUS = ${linkStatus.value}, MODIFIED_BY='$user', ROAD_NUMBER = $newRoadNumber, ROAD_PART_NUMBER = $newRoadPart" +
        s"WHERE ID IN ${ids.mkString("(", ",", ")")} AND STATUS != ${LinkStatus.Terminated.value}"
      Q.updateNA(sql).execute

      val updateLastLinkWithDiscontinuity =
        s"""UPDATE PROJECT_LINK SET DISCONTINUITY_TYPE = $discontinuity WHERE ID IN (
         SELECT ID FROM PROJECT_LINK WHERE ROAD_NUMBER = $newRoadNumber AND ROAD_PART_NUMBER = $newRoadPart AND STATUS != ${LinkStatus.Terminated.value} AND END_ADDR_M = (SELECT MAX(END_ADDR_M) FROM PROJECT_LINK WHERE ROAD_NUMBER = $newRoadNumber AND ROAD_PART_NUMBER = $newRoadPart AND STATUS != ${LinkStatus.Terminated.value}))"""
      Q.updateNA(updateLastLinkWithDiscontinuity).execute
    }
  }

  def updateProjectLinkRoadTypeDiscontinuity(projectLinkIds: Set[Long], linkStatus: LinkStatus, userName: String, roadType: Long, discontinuity: Option[Long]): Unit = {
    time(logger, "Update project link road type discontinuity") {
      val user = userName.replaceAll("[^A-Za-z0-9\\-]+", "")
      if (discontinuity.isEmpty) {
        projectLinkIds.grouped(500).foreach {
          grp =>
            val sql = s"UPDATE PROJECT_LINK SET STATUS = ${linkStatus.value}, MODIFIED_BY='$user', ROAD_TYPE= $roadType " +
              s"WHERE ID IN ${grp.mkString("(", ",", ")")}"
            Q.updateNA(sql).execute
        }
      } else {
        val sql = s"UPDATE PROJECT_LINK SET STATUS = ${linkStatus.value}, MODIFIED_BY='$user', ROAD_TYPE= $roadType, DISCONTINUITY_TYPE = ${discontinuity.get} " +
          s"WHERE ID = ${projectLinkIds.head}"
        Q.updateNA(sql).execute
      }
    }
  }

  def updateProjectLinksStatus(ids: Set[Long], linkStatus: LinkStatus, userName: String): Unit = {
    val user = userName.replaceAll("[^A-Za-z0-9\\-]+", "")
    ids.grouped(500).foreach {
      grp =>
        val sql = s"UPDATE PROJECT_LINK SET STATUS = ${linkStatus.value}, MODIFIED_BY='$user' " +
          s"WHERE ID IN ${grp.mkString("(", ",", ")")}"
        Q.updateNA(sql).execute
    }
  }

  def updateProjectLinkValues(projectId: Long, roadAddress: RoadAddress, updateGeom : Boolean = true): Unit = {

    time(logger, "Update project link values") {
      val points: Seq[Double] = roadAddress.geometry.flatMap(p => Seq(p.x, p.y, p.z, roadAddress.endMValue))
      val geometryQuery = s"MDSYS.SDO_GEOMETRY(4002, 3067, NULL, MDSYS.SDO_ELEM_INFO_ARRAY(1,2,1), MDSYS.SDO_ORDINATE_ARRAY(${points.mkString(",")}))"
      val updateGeometry = if (updateGeom) s", GEOMETRY = $geometryQuery" else s""

      val updateProjectLink = s"UPDATE PROJECT_LINK SET ROAD_NUMBER = ${roadAddress.roadNumber}, " +
        s" ROAD_PART_NUMBER = ${roadAddress.roadPartNumber}, TRACK = ${roadAddress.track.value}, " +
        s" DISCONTINUITY_TYPE = ${roadAddress.discontinuity.value}, ROAD_TYPE = ${roadAddress.roadType.value}, " +
        s" STATUS = ${LinkStatus.NotHandled.value}, START_ADDR_M = ${roadAddress.startAddrMValue}, END_ADDR_M = ${roadAddress.endAddrMValue}, " +
        s" CALIBRATION_POINTS = ${CalibrationCode.getFromAddress(roadAddress).value}, CONNECTED_LINK_ID = null, REVERSED = 0, " +
        s" CALIBRATION_POINTS_SOURCE = ${CalibrationPointSource.RoadAddressSource.value}, " +
        s" SIDE = ${roadAddress.sideCode.value}, " +
        s" start_measure = ${roadAddress.startMValue}, end_measure = ${roadAddress.endMValue} $updateGeometry" +
        s" WHERE LINEAR_LOCATION_ID = ${roadAddress.linearLocationId} AND PROJECT_ID = $projectId"
      Q.updateNA(updateProjectLink).execute
    }
  }

  /**
    * Reverses the road part in project. Switches side codes 2 <-> 3, updates calibration points start <-> end,
    * updates track codes 1 <-> 2
    *
    * @param projectId
    * @param roadNumber
    * @param roadPartNumber
    */
  def reverseRoadPartDirection(projectId: Long, roadNumber: Long, roadPartNumber: Long): Unit = {
    time(logger, "Reverse road part direction") {
      val roadPartMaxAddr =
        sql"""SELECT MAX(END_ADDR_M) FROM PROJECT_LINK
         where project_link.project_id = $projectId and project_link.road_number = $roadNumber and project_link.road_part_number = $roadPartNumber
         and project_link.status != ${LinkStatus.Terminated.value}
         """.as[Long].firstOption.getOrElse(0L)
      val updateProjectLink = s"update project_link set calibration_points = (CASE calibration_points WHEN 0 THEN 0 WHEN 1 THEN 2 WHEN 2 THEN 1 ELSE 3 END), " +
        s"(start_addr_m, end_addr_m) = (SELECT $roadPartMaxAddr - pl2.end_addr_m, $roadPartMaxAddr - pl2.start_addr_m FROM PROJECT_LINK pl2 WHERE pl2.id = project_link.id), " +
        s"SIDE = (CASE SIDE WHEN 2 THEN 3 ELSE 2 END), " +
        s"reversed = (CASE reversed WHEN 0 THEN 1 WHEN 1 THEN 0 END)" +
        s"where project_link.project_id = $projectId and project_link.road_number = $roadNumber and project_link.road_part_number = $roadPartNumber " +
        s"and project_link.status != ${LinkStatus.Terminated.value}"
      Q.updateNA(updateProjectLink).execute
    }
  }

  def fetchProjectLinkIds(projectId: Long, roadNumber: Long, roadPartNumber: Long, status: Option[LinkStatus] = None,
                          maxResults: Option[Int] = None): List[Long] =
  {
    val filter = status.map(s => s" AND status = ${s.value}").getOrElse("")
    val limit = maxResults.map(s => s" AND ROWNUM <= $s").getOrElse("")
    val query= s"""
         SELECT link_id
         FROM Project_link
         WHERE project_id = $projectId and road_number = $roadNumber and road_part_number = $roadPartNumber $filter $limit
       """
    Q.queryNA[Long](query).list
  }

  def countLinksByStatus(projectId: Long, roadNumber: Long, roadPartNumber: Long, linkStatus: Set[Long]): Long = {
    val filterByStatus = if(linkStatus.nonEmpty) s" AND Status IN (${linkStatus.mkString(",")})" else ""
    val query =
      s"""select count(id) from project_link
          WHERE project_id = $projectId and road_number = $roadNumber and road_part_number = $roadPartNumber $filterByStatus"""
    Q.queryNA[Long](query).first
  }

  def getProjectLinksContinuityCodes(projectId: Long, roadNumber: Long, roadPartNumber: Long): Map[Long, Discontinuity] = {
    sql""" SELECT END_ADDR_M, DISCONTINUITY_TYPE FROM PROJECT_LINK WHERE PROJECT_ID = $projectId AND
         ROAD_NUMBER = $roadNumber AND ROAD_PART_NUMBER = $roadPartNumber AND STATUS != ${LinkStatus.Terminated.value}
         AND (DISCONTINUITY_TYPE != ${Discontinuity.Continuous.value} OR END_ADDR_M =
         (SELECT MAX(END_ADDR_M) FROM PROJECT_LINK WHERE PROJECT_ID = $projectId AND
           ROAD_NUMBER = $roadNumber AND ROAD_PART_NUMBER = $roadPartNumber AND STATUS != ${LinkStatus.Terminated.value}))
       """.as[(Long, Int)].list.map(x => x._1 -> Discontinuity.apply(x._2)).toMap
  }

  def fetchFirstLink(projectId: Long, roadNumber: Long, roadPartNumber: Long): Option[ProjectLink] = {
    val query = s"""$projectLinkQueryBase
    where PROJECT_LINK.ROAD_PART_NUMBER=$roadPartNumber AND PROJECT_LINK.ROAD_NUMBER=$roadNumber AND
    PROJECT_LINK.START_ADDR_M = (SELECT MIN(PROJECT_LINK.START_ADDR_M) FROM PROJECT_LINK
      LEFT JOIN
      ROADWAY ON ((ROADWAY.road_number = PROJECT_LINK.road_number AND ROADWAY.road_part_number = PROJECT_LINK.road_part_number) OR ROADWAY.id = PROJECT_LINK.ROADWAY_ID)
      LEFT JOIN
      Linear_Location ON (Linear_Location.id = Project_Link.Linear_Location_Id)
      WHERE PROJECT_LINK.PROJECT_ID = $projectId AND ((PROJECT_LINK.ROAD_PART_NUMBER=$roadPartNumber AND PROJECT_LINK.ROAD_NUMBER=$roadNumber) OR PROJECT_LINK.ROADWAY_ID = ROADWAY.ID))
    order by PROJECT_LINK.ROAD_NUMBER, PROJECT_LINK.ROAD_PART_NUMBER, PROJECT_LINK.START_ADDR_M, PROJECT_LINK.TRACK """
    listQuery(query).headOption
  }

  private def deleteProjectLinks(ids: Set[Long]): Int = {
    if (ids.size > 900)
      ids.grouped(900).map(deleteProjectLinks).sum
    else {
      val deleteLinks =
        s"""
         DELETE FROM PROJECT_LINK WHERE id IN (${ids.mkString(",")})
       """
      val count = Q.updateNA(deleteLinks).first
      count
    }
  }

  def removeProjectLinksById(ids: Set[Long]): Int = {
    if (ids.nonEmpty)
      deleteProjectLinks(ids)
    else
      0
  }

  def removeProjectLinksByLinkIds(projectId: Long, roadNumber: Option[Long], roadPartNumber: Option[Long],
                                  linkIds: Set[Long] = Set()): Int = {
    if (linkIds.size > 900 || linkIds.isEmpty) {
      linkIds.grouped(900).map(g => removeProjectLinks(projectId, roadNumber, roadPartNumber, g)).sum
    } else {
      removeProjectLinks(projectId, roadNumber, roadPartNumber, linkIds)
    }
  }

  private def removeProjectLinks(projectId: Long, roadNumber: Option[Long], roadPartNumber: Option[Long],
                                 linkIds: Set[Long] = Set()): Int = {
    val roadFilter = roadNumber.map(l => s"AND road_number = $l").getOrElse("")
    val roadPartFilter = roadPartNumber.map(l => s"AND road_part_number = $l").getOrElse("")
    val linkIdFilter = if (linkIds.isEmpty) {
      ""
    } else {
      s"AND LINK_ID IN (${linkIds.mkString(",")})"
    }
    val query =
      s"""SELECT pl.id FROM PROJECT_LINK pl WHERE
        project_id = $projectId $roadFilter $roadPartFilter $linkIdFilter"""
    val ids = Q.queryNA[Long](query).iterator.toSet
    if (ids.nonEmpty) {
      sqlu"""DELETE FROM ROADWAY_CHANGES_LINK WHERE PROJECT_ID = $projectId""".execute
      sqlu"""DELETE FROM ROADWAY_CHANGES WHERE PROJECT_ID = $projectId""".execute
      deleteProjectLinks(ids)
    }
    else
      0
  }

  def moveProjectLinksToHistory(projectId: Long): Unit = {
      sqlu"""INSERT INTO PROJECT_LINK_HISTORY (SELECT DISTINCT ID, PROJECT_ID, TRACK, DISCONTINUITY_TYPE,
            ROAD_NUMBER, ROAD_PART_NUMBER, START_ADDR_M, END_ADDR_M, CREATED_BY, MODIFIED_BY, CREATED_DATE,
            MODIFIED_DATE, STATUS, CALIBRATION_POINTS, ROAD_TYPE, ROADWAY_ID, LINEAR_LOCATION_ID, CONNECTED_LINK_ID, ELY,
            REVERSED, SIDE, START_MEASURE, END_MEASURE, LINK_ID, ADJUSTED_TIMESTAMP,
            LINK_SOURCE, CALIBRATION_POINTS_SOURCE, GEOMETRY, ORIGINAL_START_ADDR_M, ORIGINAL_END_ADDR_M
          FROM PROJECT_LINK WHERE PROJECT_ID = $projectId)""".execute
    sqlu"""DELETE FROM ROADWAY_CHANGES_LINK WHERE PROJECT_ID = $projectId""".execute
    sqlu"""DELETE FROM ROADWAY_CHANGES WHERE PROJECT_ID = $projectId""".execute
    sqlu"""DELETE FROM PROJECT_LINK WHERE PROJECT_ID = $projectId""".execute
    sqlu"""DELETE FROM PROJECT_RESERVED_ROAD_PART WHERE PROJECT_ID = $projectId""".execute
  }

  def removeProjectLinksByProjectAndRoadNumber(projectId: Long, roadNumber: Long, roadPartNumber: Long): Int = {
    removeProjectLinks(projectId, Some(roadNumber), Some(roadPartNumber))
  }

  def removeProjectLinksByProject(projectId: Long): Int = {
    removeProjectLinks(projectId, None, None)
  }

  def removeProjectLinksByLinkId(projectId: Long, linkIds: Set[Long]): Int = {
    if (linkIds.nonEmpty)
      removeProjectLinks(projectId, None, None, linkIds)
    else
      0
  }

  def fetchSplitLinks(projectId: Long, linkId: Long): Seq[ProjectLink] = {
    val query =
      s"""$projectLinkQueryBase
                where PROJECT_LINK.PROJECT_ID = $projectId AND (PROJECT_LINK.LINK_ID = $linkId OR PROJECT_LINK.CONNECTED_LINK_ID = $linkId)"""
    listQuery(query)
  }

  implicit val getDiscontinuity: GetResult[Option[Discontinuity]] = new GetResult[Option[Discontinuity]] {
    def apply(r: PositionedResult) = {
      r.nextLongOption().map(l => Discontinuity.apply(l))
    }
  }
}

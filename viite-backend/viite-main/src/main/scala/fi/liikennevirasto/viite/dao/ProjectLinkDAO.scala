package fi.liikennevirasto.viite.dao

import java.sql.{SQLException, Types}
import java.util.Date
import fi.liikennevirasto.digiroad2.util.LogUtils.time
import fi.liikennevirasto.viite._
import fi.liikennevirasto.viite.process.InvalidAddressDataException
import fi.liikennevirasto.viite.util.CalibrationPointsUtils
import fi.vaylavirasto.viite.dao.{BaseDAO, Sequences}
import fi.vaylavirasto.viite.geometry.{GeometryUtils, Point, PolyLine, Vector3d}
import fi.vaylavirasto.viite.model.{AddrMRange, AdministrativeClass, CalibrationPointType, Discontinuity, LinkGeomSource, RoadAddressChangeType, RoadPart, SideCode, Track}
import fi.vaylavirasto.viite.postgis.PostGISDatabase
import org.joda.time.DateTime
import slick.driver.JdbcDriver.backend.Database.dynamicSession
import slick.jdbc.{GetResult, PositionedResult, StaticQuery => Q}
import slick.jdbc.StaticQuery.interpolation


//TODO naming SQL conventions



case class ProjectLink(id: Long, roadPart: RoadPart, track: Track, discontinuity: Discontinuity, addrMRange: AddrMRange, originalAddrMRange: AddrMRange, startDate: Option[DateTime] = None, endDate: Option[DateTime] = None, createdBy: Option[String] = None, linkId: String, startMValue: Double, endMValue: Double, sideCode: SideCode,
                       calibrationPointTypes: (CalibrationPointType, CalibrationPointType) = (CalibrationPointType.NoCP, CalibrationPointType.NoCP),
                       originalCalibrationPointTypes: (CalibrationPointType, CalibrationPointType) = (CalibrationPointType.NoCP, CalibrationPointType.NoCP),
                       geometry: Seq[Point], projectId: Long, status: RoadAddressChangeType, administrativeClass: AdministrativeClass, linkGeomSource: LinkGeomSource = LinkGeomSource.NormalLinkInterface, geometryLength: Double, roadwayId: Long, linearLocationId: Long, ely: Long, reversed: Boolean, connectedLinkId: Option[String] = None, linkGeometryTimeStamp: Long, roadwayNumber: Long = NewIdValue, roadName: Option[String] = None, roadAddressLength: Option[Long] = None, roadAddressStartAddrM: Option[Long] = None, roadAddressEndAddrM: Option[Long] = None, roadAddressTrack: Option[Track] = None, roadAddressRoadPart: Option[RoadPart] = None)
  extends BaseRoadAddress with PolyLine {

  def this(id: Long, roadPart: RoadPart, track: Track, discontinuity: Discontinuity, addrMRange: AddrMRange, originalAddrMRange: AddrMRange, startDate: Option[DateTime], endDate: Option[DateTime], createdBy: Option[String], linkId: Long, startMValue: Double, endMValue: Double, sideCode: SideCode, calibrationPointTypes: (CalibrationPointType, CalibrationPointType), originalCalibrationPointTypes: (CalibrationPointType, CalibrationPointType), geometry: Seq[Point], projectId: Long, status: RoadAddressChangeType, administrativeClass: AdministrativeClass, linkGeomSource: LinkGeomSource, geometryLength: Double, roadwayId: Long, linearLocationId: Long, ely: Long, reversed: Boolean, connectedLinkId: Option[Long], linkGeometryTimeStamp: Long, roadwayNumber: Long, roadName: Option[String], roadAddressLength: Option[Long], roadAddressStartAddrM: Option[Long], roadAddressEndAddrM: Option[Long], roadAddressTrack: Option[Track], roadAddressRoadPart: Option[RoadPart]) =
    this(id, roadPart, track, discontinuity, addrMRange, originalAddrMRange, startDate, endDate, createdBy, linkId.toString, startMValue, endMValue, sideCode, calibrationPointTypes, originalCalibrationPointTypes, geometry, projectId, status, administrativeClass, linkGeomSource, geometryLength, roadwayId, linearLocationId, ely, reversed, connectedLinkId.asInstanceOf[Option[String]], linkGeometryTimeStamp, roadwayNumber, roadName, roadAddressLength, roadAddressStartAddrM, roadAddressEndAddrM, roadAddressTrack, roadAddressRoadPart)

  override lazy val startCalibrationPoint: Option[ProjectCalibrationPoint] = calibrationPoints._1
  override lazy val endCalibrationPoint: Option[ProjectCalibrationPoint] = calibrationPoints._2

  val isSplit: Boolean = connectedLinkId.nonEmpty || connectedLinkId.contains(0L)

  // TODO refactor
  lazy val isNotCalculated: Boolean = addrMRange.end == 0L
  lazy val isCalculated: Boolean = !isNotCalculated

  private lazy val roadway = roadwayDAO.fetchAllByRoadwayId(Seq(roadwayId)).headOption

  def originalRoadPart: RoadPart = if (roadway.isDefined) roadway.get.roadPart else roadPart

  def originalAdministrativeClass: AdministrativeClass = if (roadway.isDefined) roadway.get.administrativeClass else administrativeClass

  def originalTrack: Track = if (roadway.isDefined) roadway.get.track else track

  def originalEly: Long = if (roadway.isDefined) roadway.get.ely else ely

  private def isTheLastProjectLinkOnRoadway = roadway.isDefined && this.originalAddrMRange.end == roadway.get.addrMRange.end

  def originalDiscontinuity: Discontinuity = if (isTheLastProjectLinkOnRoadway) roadway.get.discontinuity else if (roadway.isDefined) Discontinuity.Continuous else discontinuity

  def copyWithGeometry(newGeometry: Seq[Point]): ProjectLink = {
    this.copy(geometry = newGeometry, geometryLength = GeometryUtils.geometryLength(newGeometry))
  }

  def addrAt(a: Double): Long = {
    val coefficient = (addrMRange.end - addrMRange.start) / (endMValue - startMValue)
    sideCode match {
      case SideCode.AgainstDigitizing =>
        addrMRange.end - Math.round((a-startMValue) * coefficient)
      case SideCode.TowardsDigitizing =>
        addrMRange.start + Math.round((a-startMValue) * coefficient)
      case _ => throw new InvalidAddressDataException(s"Bad sidecode $sideCode on project link")
    }
  }

  def addrMLength(): Long = {
    addrMRange.end - addrMRange.start
  }

  def getFirstPoint: Point = {
    if (sideCode == SideCode.TowardsDigitizing) geometry.head else geometry.last
  }

  def getLastPoint: Point = {
    if (sideCode == SideCode.TowardsDigitizing) geometry.last else geometry.head
  }

  def toMeters(address: Long): Double = {
    val coefficient = (endMValue - startMValue) / (addrMRange.end - addrMRange.start)
    coefficient * address
  }

  def lastSegmentDirection: Vector3d = {
    (sideCode, reversed) match {
      case (SideCode.TowardsDigitizing, false) => GeometryUtils.lastSegmentDirection(geometry)
      case (SideCode.AgainstDigitizing, false) => GeometryUtils.firstSegmentDirection(geometry) scale -1
      case (SideCode.TowardsDigitizing, true) => GeometryUtils.firstSegmentDirection(geometry) scale -1
      case (SideCode.AgainstDigitizing, true) => GeometryUtils.lastSegmentDirection(geometry)
      case (SideCode.Unknown, _) => throw new InvalidAddressDataException(s"Bad sidecode $sideCode on project link")
    }
  }

  def calibrationPoints: (Option[ProjectCalibrationPoint], Option[ProjectCalibrationPoint]) = {
    CalibrationPointsUtils.toCalibrationPoints(calibrationPointTypes._1, calibrationPointTypes._2, linkId,
      startMValue, endMValue, addrMRange, sideCode)
  }

  def hasCalibrationPointAt(addressMValue: Long): Boolean = {
    calibrationPoints match {
      case (None, None) => false
      case (Some(cp1), None) => cp1.addressMValue == addressMValue
      case (None, Some(cp1)) => cp1.addressMValue == addressMValue
      case (Some(cp1), Some(cp2)) => cp1.addressMValue == addressMValue || cp2.addressMValue == addressMValue
    }
  }

  def hasCalibrationPoints: Boolean = {
    calibrationPoints match {
      case (None, None) => false
      case  _ => true
    }
  }

  def hasCalibrationPointCreatedInProject: Boolean = {
    isStartCalibrationPointCreatedInProject || isEndCalibrationPointCreatedInProject
  }

  def isStartCalibrationPointCreatedInProject: Boolean = {
    startCalibrationPoint.isDefined && originalStartCalibrationPointType == CalibrationPointType.NoCP
  }

  def isEndCalibrationPointCreatedInProject: Boolean = {
    endCalibrationPoint.isDefined && originalEndCalibrationPointType == CalibrationPointType.NoCP
  }

  def startCalibrationPointType: CalibrationPointType = {
    if (startCalibrationPoint.isDefined) startCalibrationPoint.get.typeCode
    else CalibrationPointType.NoCP
  }

  def endCalibrationPointType: CalibrationPointType = {
    if (endCalibrationPoint.isDefined) endCalibrationPoint.get.typeCode
    else CalibrationPointType.NoCP
  }

  def originalStartCalibrationPointType: CalibrationPointType = {
    originalCalibrationPointTypes._1
  }

  def originalEndCalibrationPointType: CalibrationPointType = {
    originalCalibrationPointTypes._2
  }
}

class ProjectLinkDAO extends BaseDAO {

  val roadwayDAO = new RoadwayDAO

  private val projectLinkQueryBase =
    s"""select PROJECT_LINK.ID, PROJECT_LINK.PROJECT_ID, PROJECT_LINK.TRACK, PROJECT_LINK.DISCONTINUITY_TYPE,
  PROJECT_LINK.ROAD_NUMBER, PROJECT_LINK.ROAD_PART_NUMBER, PROJECT_LINK.START_ADDR_M, PROJECT_LINK.END_ADDR_M,
  PROJECT_LINK.ORIGINAL_START_ADDR_M, PROJECT_LINK.ORIGINAL_END_ADDR_M,
  PROJECT_LINK.START_MEASURE, PROJECT_LINK.END_MEASURE, PROJECT_LINK.SIDE,
  PROJECT_LINK.CREATED_BY, PROJECT_LINK.MODIFIED_BY, PROJECT_LINK.LINK_ID, PROJECT_LINK.GEOMETRY,
  (PROJECT_LINK.END_MEASURE - PROJECT_LINK.START_MEASURE) as length,
  PROJECT_LINK.START_CALIBRATION_POINT, PROJECT_LINK.END_CALIBRATION_POINT,
  PROJECT_LINK.ORIG_START_CALIBRATION_POINT, PROJECT_LINK.ORIG_END_CALIBRATION_POINT,
  PROJECT_LINK.STATUS, PROJECT_LINK.ADMINISTRATIVE_CLASS, PROJECT_LINK.LINK_SOURCE as source, PROJECT_LINK.ROADWAY_ID,
  PROJECT_LINK.LINEAR_LOCATION_ID, PROJECT_LINK.ELY, PROJECT_LINK.REVERSED, PROJECT_LINK.CONNECTED_LINK_ID,
  CASE
    WHEN STATUS = ${RoadAddressChangeType.NotHandled.value} THEN null
    WHEN STATUS IN (${RoadAddressChangeType.Termination.value}, ${RoadAddressChangeType.Unchanged.value}) THEN ROADWAY.START_DATE
    ELSE PRJ.START_DATE END as start_date,
  CASE WHEN STATUS = ${RoadAddressChangeType.Termination.value} THEN PRJ.START_DATE - 1 ELSE null END as end_date,
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
  ROADWAY.ROADWAY_NUMBER,
  PROJECT_LINK.ROADWAY_NUMBER
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
          (plh.END_MEASURE - plh.START_MEASURE) as length,
          plh.START_CALIBRATION_POINT, plh.END_CALIBRATION_POINT,
          plh.ORIG_START_CALIBRATION_POINT, plh.ORIG_END_CALIBRATION_POINT,
          plh.STATUS, plh.ADMINISTRATIVE_CLASS, plh.LINK_SOURCE as source, plh.ROADWAY_ID, plh.Linear_Location_Id, plh.ELY,
          plh.REVERSED, plh.CONNECTED_LINK_ID,
          CASE
            WHEN STATUS = ${RoadAddressChangeType.NotHandled.value} THEN null
            WHEN STATUS IN (${RoadAddressChangeType.Termination.value}, ${RoadAddressChangeType.Unchanged.value}) THEN ROADWAY.START_DATE
            ELSE PRJ.START_DATE END as start_date,
          CASE WHEN STATUS = ${RoadAddressChangeType.Termination.value} THEN PRJ.START_DATE - 1 ELSE null END as end_date,
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
          ROADWAY.ROADWAY_NUMBER,
          plh.ROADWAY_NUMBER
          from PROJECT prj JOIN PROJECT_LINK_HISTORY plh ON (prj.id = plh.PROJECT_ID)
            LEFT JOIN ROADWAY ON (ROADWAY.ID = plh.ROADWAY_ID)
            LEFT JOIN Linear_Location ON (Linear_Location.ID = plh.Linear_Location_Id)
            LEFT JOIN ROAD_NAME rn ON (rn.road_number = plh.road_number AND rn.END_DATE IS NULL AND rn.VALID_TO IS null)
        	  LEFT JOIN project_link_name pln ON (pln.road_number = plh.road_number AND pln.project_id = plh.project_id)
     """.stripMargin

  private val projectLinksChangeQueryBase =
    s"""
        select PROJECT_LINK.ID, ROADWAY.ID, PROJECT_LINK.LINEAR_LOCATION_ID, ROADWAY.ROAD_NUMBER, ROADWAY.ROAD_PART_NUMBER, PROJECT_LINK.ROAD_NUMBER, PROJECT_LINK.ROAD_PART_NUMBER, PROJECT_LINK.ORIGINAL_START_ADDR_M, PROJECT_LINK.ORIGINAL_END_ADDR_M,
          PROJECT_LINK.START_ADDR_M, PROJECT_LINK.END_ADDR_M,
          PROJECT_LINK.STATUS,
          PROJECT_LINK.REVERSED,
          ROADWAY.ROADWAY_NUMBER,
          PROJECT_LINK.ROADWAY_NUMBER
          from PROJECT prj JOIN PROJECT_LINK ON (prj.id = PROJECT_LINK.PROJECT_ID)
          LEFT JOIN ROADWAY ON (ROADWAY.ID = PROJECT_LINK.ROADWAY_ID)
          LEFT JOIN Linear_Location ON (Linear_Location.ID = PROJECT_LINK.Linear_Location_Id)
      """

  implicit val getProjectLinkRow: GetResult[ProjectLink] = new GetResult[ProjectLink] {
    def apply(r: PositionedResult): ProjectLink = {
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
      val linkId = r.nextString()
      val geom = r.nextObjectOption()
      val length = r.nextDouble()
      val calibrationPoints = (CalibrationPointType.apply(r.nextInt), CalibrationPointType.apply(r.nextInt))
      val originalCalibrationPointTypes = (CalibrationPointType.apply(r.nextInt), CalibrationPointType.apply(r.nextInt))
      val status = RoadAddressChangeType.apply(r.nextInt())
      val administrativeClass = AdministrativeClass.apply(r.nextInt())
      val source = LinkGeomSource.apply(r.nextInt())
      val roadwayId = r.nextLong()
      val linearLocationId = r.nextLong()
      val ely = r.nextLong()
      val reversed = r.nextBoolean()
      val connectedLinkId = r.nextStringOption()
      val startDate = r.nextDateOption().map(d => new DateTime(d.getTime))
      val endDate = r.nextDateOption().map(d => new DateTime(d.getTime))
      val geometryTimeStamp = r.nextLong()
      val roadName = r.nextString()
      val roadAddressStartAddrM = r.nextLongOption()
      val roadAddressEndAddrM = r.nextLongOption()
      val roadAddressTrack = r.nextIntOption().map(Track.apply)
      val roadAddressRoadNumber = r.nextLongOption()
      val roadAddressRoadPartNumber = r.nextLongOption()
      val roadwayNumber = r.nextLong()
      val projectRoadwayNumber = r.nextLong()

      val roadAddressRoadPart = if(roadAddressRoadNumber.nonEmpty && roadAddressRoadPartNumber.nonEmpty) { Some(RoadPart(roadAddressRoadNumber.get, roadAddressRoadPartNumber.get)) } else None
      ProjectLink(projectLinkId, RoadPart(roadNumber, roadPartNumber), trackCode, discontinuityType, AddrMRange(startAddrM, endAddrM), AddrMRange(originalStartAddrMValue, originalEndAddrMValue), startDate, endDate, createdBy, linkId, startMValue, endMValue, sideCode, calibrationPoints, originalCalibrationPointTypes, PostGISDatabase.loadJGeometryToGeometry(geom), projectId, status, administrativeClass, source, length, roadwayId, linearLocationId, ely, reversed, connectedLinkId, geometryTimeStamp, if (projectRoadwayNumber == 0 || projectRoadwayNumber == NewIdValue) roadwayNumber else projectRoadwayNumber, Some(roadName), roadAddressEndAddrM.map(endAddr => endAddr - roadAddressStartAddrM.getOrElse(0L)), roadAddressStartAddrM, roadAddressEndAddrM, roadAddressTrack, roadAddressRoadPart)
    }
  }

  implicit val getProjectLinksChangeRow: GetResult[ProjectRoadLinkChange] = new GetResult[ProjectRoadLinkChange] {
    def apply(r: PositionedResult): ProjectRoadLinkChange = {
      val projectLinkId = r.nextLong()
      val roadwayId = r.nextLong()
      val originalLinearLocationId = r.nextLong()
      val originalRoadNumber = r.nextLong()
      val originalRoadPartNumber = r.nextLong()
      val roadNumber = r.nextLong()
      val roadPartNumber = r.nextLong()
      val originalStartAddrMValue = r.nextLong()
      val originalEndAddrMValue = r.nextLong()
      val startAddrM = r.nextLong()
      val endAddrM = r.nextLong()
      val status = RoadAddressChangeType.apply(r.nextInt())
      val reversed = r.nextBoolean()
      val roadwayNumber = r.nextLong()
      val projectRoadwayNumber = r.nextLong()

      ProjectRoadLinkChange(projectLinkId, roadwayId, originalLinearLocationId, 0, RoadPart(originalRoadNumber, originalRoadPartNumber), RoadPart(originalRoadNumber, originalRoadPartNumber), originalStartAddrMValue, originalEndAddrMValue, startAddrM, endAddrM,
        status, reversed, roadwayNumber, projectRoadwayNumber)
    }
  }

  private def listQuery(query: String): Seq[ProjectLink] = {
    Q.queryNA[ProjectLink](query).iterator.toSeq
  }

  private def changesListQuery(query: String): Seq[ProjectRoadLinkChange] = {
    Q.queryNA[ProjectRoadLinkChange](query).iterator.toSeq
  }

  def create(links: Seq[ProjectLink]): Seq[Long] = {
    if (links.nonEmpty)
    time(logger, "Create project links") {
      val addressPS = dynamicSession.prepareStatement("""
        insert into PROJECT_LINK (id, project_id, road_number, road_part_number, TRACK, discontinuity_type,
          START_ADDR_M, END_ADDR_M, ORIGINAL_START_ADDR_M, ORIGINAL_END_ADDR_M, created_by, modified_by,
          start_calibration_point, end_calibration_point, orig_start_calibration_point, orig_end_calibration_point,
          status, ADMINISTRATIVE_CLASS, roadway_id, linear_location_id, connected_link_id, ely, roadway_number, reversed, geometry,
          link_id, SIDE, start_measure, end_measure, adjusted_timestamp, link_source, modified_date)
          values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ST_GeomFromText(?, 3067), ?, ?, ?, ?, ?, ?, ?)
        """)
      val (ready, idLess) = links.partition(_.id != NewIdValue)
      val plIds = Sequences.fetchProjectLinkIds(idLess.size)
      val projectLinks = ready ++ idLess.zip(plIds).map(x =>
        x._1.copy(id = x._2)
      )
      projectLinks.toList.foreach { pl =>
        addressPS.setLong(1, pl.id)
        addressPS.setLong(2, pl.projectId)
        addressPS.setLong(3, pl.roadPart.roadNumber)
        addressPS.setLong(4, pl.roadPart.partNumber)
        addressPS.setLong(5, pl.track.value)
        addressPS.setLong(6, pl.discontinuity.value)
        addressPS.setLong(7, pl.addrMRange.start)
        addressPS.setLong(8, pl.addrMRange.end)
        addressPS.setLong(9, pl.originalAddrMRange.start)
        addressPS.setLong(10, pl.originalAddrMRange.end)
        addressPS.setString(11, pl.createdBy.orNull)
        addressPS.setString(12, pl.createdBy.orNull)

        addressPS.setLong(13, pl.startCalibrationPointType.value)
        addressPS.setLong(14, pl.endCalibrationPointType.value)
        addressPS.setLong(15, pl.originalStartCalibrationPointType.value)
        addressPS.setLong(16, pl.originalEndCalibrationPointType.value)
        addressPS.setLong(17, pl.status.value)
        addressPS.setLong(18, pl.administrativeClass.value)
        if (pl.roadwayId == 0)
          addressPS.setNull(19, Types.BIGINT)
        else
          addressPS.setLong(19, pl.roadwayId)
        if (pl.linearLocationId == 0)
          addressPS.setNull(20, Types.BIGINT)
        else
          addressPS.setLong(20, pl.linearLocationId)
        if (pl.connectedLinkId.isDefined)
          addressPS.setString(21, pl.connectedLinkId.get)
        else
          addressPS.setNull(21, Types.BIGINT)
        addressPS.setLong(22, pl.ely)
        addressPS.setLong(23, pl.roadwayNumber)
        addressPS.setInt(24, if (pl.reversed) 1 else 0)
        addressPS.setString(25, PostGISDatabase.createJGeometry(pl.geometry))
        addressPS.setString(26, pl.linkId)
        addressPS.setLong(27, pl.sideCode.value)
        addressPS.setDouble(28, pl.startMValue)
        addressPS.setDouble(29, pl.endMValue)
        addressPS.setDouble(30, pl.linkGeometryTimeStamp)
        addressPS.setInt(31, pl.linkGeomSource.value)
        addressPS.setDate(32, new java.sql.Date(new Date().getTime))
        addressPS.addBatch()
      }
      addressPS.executeBatch()
      addressPS.close()
      projectLinks.map(_.id)
    } else
      Seq.empty[Long]
  }

  def updateProjectLinks(projectLinks: Seq[ProjectLink], modifier: String, addresses: Seq[RoadAddress]): Unit = {
      time(logger, "Update project links") {
        val nonUpdatingStatus = Set[RoadAddressChangeType](RoadAddressChangeType.NotHandled)
        val maxInEachTracks = projectLinks.filter(pl => pl.status == RoadAddressChangeType.Unchanged).groupBy(_.track).map(p => p._2.maxBy(_.addrMRange.end).id).toSeq
        val links = projectLinks.map { pl =>
          if (!pl.isSplit && nonUpdatingStatus.contains(pl.status) && addresses.map(_.linearLocationId).contains(pl.linearLocationId) && !maxInEachTracks.contains(pl.id)) {
            val ra = addresses.find(_.linearLocationId == pl.linearLocationId).get
            // Discontinuity, administrative class and calibration points may change with Unchanged status
            pl.copy(roadPart = ra.roadPart, track = ra.track, addrMRange = AddrMRange(ra.addrMRange.start, ra.addrMRange.end), reversed = false)
          } else
            pl
        }
        val projectLinkPS = dynamicSession.prepareStatement("""
          UPDATE project_link
          SET ROAD_NUMBER = ?, ROAD_PART_NUMBER = ?, TRACK = ?, DISCONTINUITY_TYPE = ?, START_ADDR_M = ?, END_ADDR_M = ?,
            ORIGINAL_START_ADDR_M = ?, ORIGINAL_END_ADDR_M = ?, MODIFIED_DATE = CURRENT_TIMESTAMP, MODIFIED_BY = ?, PROJECT_ID = ?,
            START_CALIBRATION_POINT = ?, END_CALIBRATION_POINT = ?, ORIG_START_CALIBRATION_POINT = ?, ORIG_END_CALIBRATION_POINT = ?,
            STATUS = ?, ADMINISTRATIVE_CLASS = ?, REVERSED = ?, GEOMETRY = ST_GeomFromText(?, 3067), SIDE = ?, START_MEASURE = ?, END_MEASURE = ?,  ELY = ?,
            ROADWAY_NUMBER = ?, CONNECTED_LINK_ID = ?
          WHERE id = ?""")

        for (projectLink <- links) {
          projectLinkPS.setLong(1, projectLink.roadPart.roadNumber)
          projectLinkPS.setLong(2, projectLink.roadPart.partNumber)
          projectLinkPS.setInt(3, projectLink.track.value)
          projectLinkPS.setInt(4, projectLink.discontinuity.value)
          projectLinkPS.setLong(5, projectLink.addrMRange.start)
          projectLinkPS.setLong(6, projectLink.addrMRange.end)
          projectLinkPS.setLong(7, projectLink.originalAddrMRange.start)
          projectLinkPS.setLong(8, projectLink.originalAddrMRange.end  )
          projectLinkPS.setString(9, modifier)
          projectLinkPS.setLong(10, projectLink.projectId)
          projectLinkPS.setInt(11, projectLink.startCalibrationPointType.value)
          projectLinkPS.setInt(12, projectLink.endCalibrationPointType.value)
          projectLinkPS.setInt(13, projectLink.originalStartCalibrationPointType.value)
          projectLinkPS.setInt(14, projectLink.originalEndCalibrationPointType.value)
          projectLinkPS.setInt(15, projectLink.status.value)
          projectLinkPS.setInt(16, projectLink.administrativeClass.value)
          projectLinkPS.setInt(17, if (projectLink.reversed) 1 else 0)
          projectLinkPS.setString(18, PostGISDatabase.createJGeometry(projectLink.geometry))
          projectLinkPS.setInt(19, projectLink.sideCode.value)
          projectLinkPS.setDouble(20, projectLink.startMValue)
          projectLinkPS.setDouble(21, projectLink.endMValue)
          projectLinkPS.setLong(22, projectLink.ely)
          projectLinkPS.setLong(23, projectLink.roadwayNumber)
          if (projectLink.connectedLinkId.isDefined)
            projectLinkPS.setString(24, projectLink.connectedLinkId.get)
          else
            projectLinkPS.setNull(24, Types.BIGINT)
          projectLinkPS.setLong(25, projectLink.id)
          projectLinkPS.addBatch()
        }
        projectLinkPS.executeBatch()
        projectLinkPS.close()
      }
  }

  def updateProjectLinksGeometry(projectLinks: Seq[ProjectLink], modifier: String): Unit = {
    time(logger,
      "Update project links geometry") {
      val projectLinkPS = dynamicSession.prepareStatement("UPDATE project_link SET  GEOMETRY = ST_GeomFromText(?, 3067), MODIFIED_BY= ?, ADJUSTED_TIMESTAMP = ? WHERE id = ?")

      for (projectLink <- projectLinks) {
        projectLinkPS.setString(1, PostGISDatabase.createJGeometry(projectLink.geometry))
        projectLinkPS.setString(2, modifier)
        projectLinkPS.setLong(3, projectLink.linkGeometryTimeStamp)
        projectLinkPS.setLong(4, projectLink.id)
        projectLinkPS.addBatch()
      }
      projectLinkPS.executeBatch()
      projectLinkPS.close()
    }
  }

  /* Used from updateProjectLinks() to remove cp from pl when connected to a terminated link.
  *  Used from twoTrackUtils.
  * */
  def updateProjectLinkCalibrationPoints(
                                          projectLink: ProjectLink,
                                          cals: (CalibrationPointType, CalibrationPointType)
                                        ): Unit = {
    time(logger,
      "Update project link calibrationpoints.") {
      runUpdateToDb(s"""
                update project_link
                set modified_date = current_timestamp,
                    start_calibration_point = ${cals._1.value},
                    end_calibration_point = ${cals._2.value}
                where id = ${projectLink.id}
      """)
    }
  }

  def fetchElyFromProjectLinks(projectId:Long): Option[Long]= {
    val query =
      s"""SELECT ELY FROM PROJECT_LINK WHERE PROJECT_ID=$projectId AND ELY IS NOT NULL LIMIT 1"""
    Q.queryNA[Long](query).firstOption
  }

  def fetchProjectLinksHistory(projectId: Long, roadAddressChangeTypeFilter: Option[RoadAddressChangeType] = None): Seq[ProjectLink] = {
    time(logger, "Get project history links") {
      val filter = if (roadAddressChangeTypeFilter.isEmpty) "" else s"plh.STATUS = ${roadAddressChangeTypeFilter.get.value} AND"
      val query =
        s"""$projectLinkHistoryQueryBase
                where $filter plh.PROJECT_ID = $projectId order by plh.ROAD_NUMBER, plh.ROAD_PART_NUMBER, plh.END_ADDR_M """
      listQuery(query)
    }
  }

  def fetchProjectLinks(projectId: Long, roadAddressChangeTypeFilter: Option[RoadAddressChangeType] = None): Seq[ProjectLink] = {
    time(logger, "Get project links") {
      val filter = if (roadAddressChangeTypeFilter.isEmpty) "" else s"PROJECT_LINK.STATUS = ${roadAddressChangeTypeFilter.get.value} AND"
      val query =
        s"""$projectLinkQueryBase
                where $filter PROJECT_LINK.PROJECT_ID = $projectId order by PROJECT_LINK.ROAD_NUMBER, PROJECT_LINK.ROAD_PART_NUMBER, PROJECT_LINK.END_ADDR_M """
      listQuery(query)
    }
  }

  def fetchProjectLinksChange(projectId: Long): Seq[ProjectRoadLinkChange] = {
    time(logger, "Get project links changes") {
      val query =
        s"""$projectLinksChangeQueryBase
                where PROJECT_LINK.PROJECT_ID = $projectId order by PROJECT_LINK.ROAD_NUMBER, PROJECT_LINK.ROAD_PART_NUMBER, PROJECT_LINK.END_ADDR_M """
      changesListQuery(query)
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

  def fetchProjectLinksByConnectedLinkId(connectedIds: Seq[String]): Seq[ProjectLink] = {
    time(logger, "Get project links by connected link ids") {
      if (connectedIds.isEmpty) {
        List()
      } else {
        val query =
          s"""$projectLinkQueryBase
                where project_link.connected_link_id in (${connectedIds.map(cid => "'" + cid + "'").mkString(",")}) order by PROJECT_LINK.ROAD_NUMBER, PROJECT_LINK.ROAD_PART_NUMBER, PROJECT_LINK.END_ADDR_M """
        listQuery(query)
      }
    }
  }

  def getProjectLinksByLinkId(linkId: String): Seq[ProjectLink] = {
    time(logger, "Get project links by link id and project id") {
      val query =
        s"""$projectLinkQueryBase
                where PROJECT_LINK.link_id = '$linkId' order by PROJECT_LINK.ROAD_NUMBER, PROJECT_LINK.ROAD_PART_NUMBER, PROJECT_LINK.END_ADDR_M """
      listQuery(query)
    }
  }

  def getOtherProjectLinks(projectId: Long): Seq[ProjectLink] = {
    time(logger, "Get project links all") {
      val query =
        s"""$projectLinkQueryBase
                where PROJECT_LINK.project_id <> $projectId order by PROJECT_LINK.ROAD_NUMBER, PROJECT_LINK.ROAD_PART_NUMBER, PROJECT_LINK.END_ADDR_M """
      listQuery(query)
    }
  }

  def fetchProjectLinksByProjectAndLinkId(projectLinkIds: Set[Long], linkIds: Set[String], projectId: Long): Seq[ProjectLink] = {
    if (projectLinkIds.isEmpty && linkIds.isEmpty) {
      List()
    } else {
      val idsFilter = if (projectLinkIds.nonEmpty) s"AND PROJECT_LINK.ID IN (${projectLinkIds.mkString(",")})" else ""
      val linkIdsFilter = if (linkIds.nonEmpty) s"AND PROJECT_LINK.LINK_ID IN (${linkIds.map(l => ''' + l + ''').mkString(",")})" else ""
      val query =
        s"""$projectLinkQueryBase
                where PROJECT_LINK.PROJECT_ID = $projectId $idsFilter $linkIdsFilter
                order by PROJECT_LINK.ROAD_NUMBER, PROJECT_LINK.ROAD_PART_NUMBER, PROJECT_LINK.END_ADDR_M """
      listQuery(query)
    }
  }

  def fetchProjectLinksByLinkId(linkIds: Seq[String]): Seq[ProjectLink] = {
    if (linkIds.isEmpty) {
      List()
    } else {
      val linkIdsFilter =  s" PROJECT_LINK.LINK_ID IN (${linkIds.map(l => ''' + l + ''').mkString(",")})"
      val query =
        s"""$projectLinkQueryBase
                where $linkIdsFilter
                order by PROJECT_LINK.ROAD_NUMBER, PROJECT_LINK.ROAD_PART_NUMBER, PROJECT_LINK.END_ADDR_M """
      listQuery(query)
    }
  }

  def fetchProjectLinksByProjectRoadPart(roadPart: RoadPart, projectId: Long, roadAddressChangeTypeFilter: Option[RoadAddressChangeType] = None): Seq[ProjectLink] = {
    time(logger, "Get project links by project road part") {
      val filter = if (roadAddressChangeTypeFilter.isEmpty) "" else s" PROJECT_LINK.STATUS = ${roadAddressChangeTypeFilter.get.value} AND"
      val query =
        s"""$projectLinkQueryBase
                where $filter PROJECT_LINK.ROAD_NUMBER = ${roadPart.roadNumber} and PROJECT_LINK.ROAD_PART_NUMBER = ${roadPart.partNumber} AND PROJECT_LINK.PROJECT_ID = $projectId order by PROJECT_LINK.ROAD_NUMBER, PROJECT_LINK.ROAD_PART_NUMBER, PROJECT_LINK.END_ADDR_M """
      listQuery(query)
    }
  }

  def fetchByProjectRoadPart(roadPart: RoadPart, projectId: Long): Seq[ProjectLink] = {
    time(logger, "Fetch project links by project road part") {
      val filter = s"PROJECT_LINK.ROAD_NUMBER = ${roadPart.roadNumber} AND PROJECT_LINK.ROAD_PART_NUMBER = ${roadPart.partNumber} AND"
      val query =
        s"""$projectLinkQueryBase
                where $filter (PROJECT_LINK.PROJECT_ID = $projectId ) order by PROJECT_LINK.ROAD_NUMBER, PROJECT_LINK.ROAD_PART_NUMBER, PROJECT_LINK.END_ADDR_M """
      listQuery(query)
    }
  }

  def fetchByProjectRoadParts(roadParts: Set[RoadPart], projectId: Long): Seq[ProjectLink] = {
    time(logger, "Fetch project links by project road parts") {
      if (roadParts.isEmpty)
        return Seq()
      val roadPartsCond = roadParts.map { case (roadPart) => s"(PROJECT_LINK.ROAD_NUMBER = ${roadPart.roadNumber} AND PROJECT_LINK.ROAD_PART_NUMBER = ${roadPart.partNumber})" }
      val filter = s"${roadPartsCond.mkString("(", " OR ", ")")} AND"
      val query =
        s"""$projectLinkQueryBase
                where $filter (PROJECT_LINK.PROJECT_ID = $projectId) order by PROJECT_LINK.ROAD_NUMBER, PROJECT_LINK.ROAD_PART_NUMBER, PROJECT_LINK.END_ADDR_M """
      listQuery(query)
    }
  }

  def fetchByProjectRoad(roadNumber: Long, projectId: Long): Seq[ProjectLink] = {
    time(logger, "Fetch project links by project road part") {
      val filter = s"PROJECT_LINK.ROAD_NUMBER = $roadNumber AND"
      val query =
        s"""$projectLinkQueryBase
                where $filter PROJECT_LINK.PROJECT_ID = $projectId order by PROJECT_LINK.ROAD_NUMBER, PROJECT_LINK.ROAD_PART_NUMBER, PROJECT_LINK.END_ADDR_M """
      listQuery(query)
    }
  }

  def updateAddrMValues(projectLink: ProjectLink): Unit = {
    runUpdateToDb(s"""
      update project_link
      set modified_date = current_timestamp, start_addr_m = ${projectLink.addrMRange.start}, end_addr_m = ${projectLink.addrMRange.end},
          start_calibration_point = ${projectLink.startCalibrationPointType.value},
          end_calibration_point = ${projectLink.endCalibrationPointType.value}
      where id = ${projectLink.id}
    """)
  }

  /**
    * Updates all the project links that share the same ids as supplied to the newRoadNumber and newRoadPart also it will put the last link of newRoadNumber and newRoadPart with the given discontinuity value.
    * @param projectId: Long - projectId of the links to update
    * @param roadPart: RoadPart - the existing road part
    * @param roadAddressChangeType: RoadAddressChangeType - The operation done on those project links
    * @param newRoadPart: RoadPart the new road part to apply
    * @param userName: String - user name
    */
  def updateProjectLinkNumbering(projectId: Long, roadPart: RoadPart, roadAddressChangeType: RoadAddressChangeType, newRoadPart: RoadPart, userName: String, ely: Long ): Unit = {
    time(logger, "Update project link numbering") {
      val user = userName.replaceAll("[^A-Za-z0-9\\-]+", "")
      val sql = s"UPDATE PROJECT_LINK SET STATUS = ${roadAddressChangeType.value}, MODIFIED_BY='$user', ROAD_NUMBER = ${newRoadPart.roadNumber}, ROAD_PART_NUMBER = ${newRoadPart.partNumber}, ELY = $ely " +
        s"WHERE PROJECT_ID = $projectId  AND ROAD_NUMBER = ${roadPart.roadNumber} AND ROAD_PART_NUMBER = ${roadPart.partNumber} AND STATUS != ${RoadAddressChangeType.Termination.value}"
println(sql)
      runUpdateToDb(sql)
    }
  }

  def updateProjectLinkAdministrativeClassDiscontinuity(projectLinkIds: Set[Long], roadAddressChangeType: RoadAddressChangeType, userName: String, administrativeClass: Long, discontinuity: Option[Long]): Unit = {
    time(logger, "Update project link administrative class discontinuity") {
      val user = userName.replaceAll("[^A-Za-z0-9\\-]+", "")
      if (discontinuity.isEmpty) {
        projectLinkIds.grouped(500).foreach {
          grp =>
            val sql = s"UPDATE PROJECT_LINK SET STATUS = ${roadAddressChangeType.value}, MODIFIED_BY='$user', ADMINISTRATIVE_CLASS= $administrativeClass " +
              s"WHERE ID IN ${grp.mkString("(", ",", ")")}"
            runUpdateToDb(sql)
        }
      } else {
        val sql = s"UPDATE PROJECT_LINK SET STATUS = ${roadAddressChangeType.value}, MODIFIED_BY='$user', ADMINISTRATIVE_CLASS= $administrativeClass, DISCONTINUITY_TYPE = ${discontinuity.get} " +
          s"WHERE ID = ${projectLinkIds.head}"
        runUpdateToDb(sql)
      }
    }
  }

  def updateProjectLinksStatus(ids: Set[Long], roadAddressChangeType: RoadAddressChangeType, userName: String): Unit = {
    val user = userName.replaceAll("[^A-Za-z0-9\\-]+", "")
    ids.grouped(500).foreach {
      grp =>
        val sql = s"UPDATE PROJECT_LINK SET STATUS = ${roadAddressChangeType.value}, MODIFIED_BY='$user' " +
          s"WHERE ID IN ${grp.mkString("(", ",", ")")}"
        runUpdateToDb(sql)
    }
  }

  /**
    * Applies all the values of the road addresses to the project links sharing the project id and road address information.
    * @param projectId: Long - The id of the project
    * @param roadAddress: RoadAddress - The road address information
    * @param updateGeom: Boolean - controls whether we update or not the geometry of the project links
    */
  def updateProjectLinkValues(projectId: Long, roadAddress: RoadAddress, updateGeom : Boolean = true, plId: Option[Long] = None): Unit = {

    time(logger, "Update project link values") {
      val lineString: String = PostGISDatabase.createJGeometry(roadAddress.geometry)
      val geometryQuery = s"ST_GeomFromText('$lineString', 3067)"
      val updateGeometry = if (updateGeom) s", GEOMETRY = $geometryQuery" else s""
      val idFilter = plId match {
        case Some(id) => s"AND ID = $id"
        case None => s""
      }

      val updateProjectLink = s"""
        UPDATE PROJECT_LINK SET ROAD_NUMBER = ${roadAddress.roadPart.roadNumber},
          ROAD_PART_NUMBER = ${roadAddress.roadPart.partNumber}, TRACK = ${roadAddress.track.value},
          DISCONTINUITY_TYPE = ${roadAddress.discontinuity.value}, ADMINISTRATIVE_CLASS = ${roadAddress.administrativeClass.value},
          STATUS = ${RoadAddressChangeType.NotHandled.value}, START_ADDR_M = ${roadAddress.addrMRange.start}, END_ADDR_M = ${roadAddress.addrMRange.end},
          ORIGINAL_START_ADDR_M = ${roadAddress.addrMRange.start}, ORIGINAL_END_ADDR_M = ${roadAddress.addrMRange.end},
          START_CALIBRATION_POINT = ${roadAddress.startCalibrationPointType.value},
          END_CALIBRATION_POINT = ${roadAddress.endCalibrationPointType.value},
          ORIG_START_CALIBRATION_POINT = ${roadAddress.startCalibrationPointType.value},
          ORIG_END_CALIBRATION_POINT = ${roadAddress.endCalibrationPointType.value},
          SIDE = ${roadAddress.sideCode.value}, ELY = ${roadAddress.ely},
          start_measure = ${roadAddress.startMValue}, end_measure = ${roadAddress.endMValue} $updateGeometry
        WHERE LINEAR_LOCATION_ID = ${roadAddress.linearLocationId} AND PROJECT_ID = $projectId $idFilter
      """
      runUpdateToDb(updateProjectLink)
    }
  }

  /**
   * Updates a batch of project links to their original road address values when terminating links.
   * Logs warnings for no updates or errors during execution.
   * @throws SQLException if an error occurs during the batch update operation
   * @param projectLinks A sequence of ProjectLinks to update
   */
  def batchUpdateProjectLinksToTerminate(projectLinks: Seq[ProjectLink]): Unit = {
    if (projectLinks.nonEmpty) {
      time(logger, "Batch update project links") {
        val updatePS = dynamicSession.prepareStatement(
          """
        UPDATE project_link
          SET
            ROAD_NUMBER = ?,
            ROAD_PART_NUMBER = ?,
            TRACK = ?,
            DISCONTINUITY_TYPE = ?,
            ADMINISTRATIVE_CLASS = ?,
            START_ADDR_M = ?,
            END_ADDR_M = ?,
            ORIGINAL_START_ADDR_M = ?,
            ORIGINAL_END_ADDR_M = ?,
            START_CALIBRATION_POINT = ?,
            END_CALIBRATION_POINT = ?,
            ORIG_START_CALIBRATION_POINT = ?,
            ORIG_END_CALIBRATION_POINT = ?,
            SIDE = ?,
            ELY = ?,
            START_MEASURE = ?,
            END_MEASURE = ?,
            STATUS = ?
          WHERE LINEAR_LOCATION_ID = ? AND PROJECT_ID = ?
      """)

        try {
        projectLinks.foreach { pl =>
          updatePS.setLong(1, pl.roadPart.roadNumber)
          updatePS.setLong(2, pl.roadPart.partNumber)
          updatePS.setInt(3, pl.track.value)
          updatePS.setInt(4, pl.discontinuity.value)
          updatePS.setInt(5, pl.administrativeClass.value)
          updatePS.setLong(6, pl.addrMRange.start)
          updatePS.setLong(7, pl.addrMRange.end)
          updatePS.setLong(8, pl.originalAddrMRange.start)
          updatePS.setLong(9, pl.originalAddrMRange.end)
          updatePS.setInt(10, pl.calibrationPointTypes._1.value)
          updatePS.setInt(11, pl.calibrationPointTypes._2.value)
          updatePS.setInt(12, pl.calibrationPointTypes._1.value)
          updatePS.setInt(13, pl.calibrationPointTypes._2.value)
          updatePS.setInt(14, pl.sideCode.value)
          updatePS.setLong(15, pl.ely)
          updatePS.setDouble(16, pl.startMValue)
          updatePS.setDouble(17, pl.endMValue)
          updatePS.setInt(18, RoadAddressChangeType.Termination.value)
          updatePS.setLong(19, pl.linearLocationId)
          updatePS.setLong(20, pl.projectId)
          updatePS.addBatch()
        }

          val updateCounts = updatePS.executeBatch()
          // Check if any updates were made
          val updatesMade = updateCounts.exists(count => count > 0)
          if (!updatesMade) {
            logger.warn("No rows were updated during the batch update operation.")
          }
        } catch {
          case e: SQLException => logger.error("SQL Exception encountered: ", e)
        } finally {
          updatePS.close()
        }
      }
    }
  }

  def updateProjectLinkGeometry(projectLinkId: Long, geometry: Seq[Point]): Unit = {
    val geometryString = PostGISDatabase.createJGeometry(geometry)
    val sql = s"""
    UPDATE project_link
    SET modified_date = current_timestamp, geometry = ST_GeomFromText('$geometryString', 3067), connected_link_id = NULL
    WHERE id = $projectLinkId
  """

    try {
      val updateCount = runUpdateToDb(sql)
      if (updateCount == 0) {
        logger.warn(s"No records were updated for projectLinkId: $projectLinkId")
      } else {
        logger.info(s"Updated geometry for projectLinkId: $projectLinkId, affected rows: $updateCount")
      }
    } catch {
      case e: SQLException =>
        logger.error(s"SQLException occurred while updating geometry for projectLinkId: $projectLinkId", e)
      case e: Exception =>
        logger.error(s"Unexpected exception occurred while updating geometry for projectLinkId: $projectLinkId", e)
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
  def reverseRoadPartDirection(projectId: Long, roadPart: RoadPart): Unit = {
    time(logger, "Reverse road part direction") {
      val roadPartMaxAddr =
        sql"""SELECT MAX(END_ADDR_M) FROM PROJECT_LINK
         where project_link.project_id = $projectId and project_link.road_number = ${roadPart.roadNumber} and project_link.road_part_number = ${roadPart.partNumber}
         and project_link.status != ${RoadAddressChangeType.Termination.value}
         """.as[Long].firstOption.getOrElse(0L)
      val updateProjectLink = s"""
        update project_link
        set start_calibration_point = end_calibration_point, end_calibration_point = start_calibration_point,
          orig_start_calibration_point = orig_end_calibration_point, orig_end_calibration_point = orig_start_calibration_point,
          (start_addr_m, end_addr_m) = (SELECT $roadPartMaxAddr - pl2.end_addr_m, $roadPartMaxAddr - pl2.start_addr_m FROM PROJECT_LINK pl2 WHERE pl2.id = project_link.id),
          TRACK = (CASE TRACK WHEN ${Track.Combined.value} THEN ${Track.Combined.value} WHEN ${Track.RightSide.value} THEN ${Track.LeftSide.value} WHEN ${Track.LeftSide.value} THEN ${Track.RightSide.value} ELSE ${Track.Unknown.value} END),
          SIDE = (CASE SIDE WHEN ${SideCode.TowardsDigitizing.value} THEN ${SideCode.AgainstDigitizing.value} ELSE ${SideCode.TowardsDigitizing.value} END),
          reversed = (CASE WHEN reversed = 0 AND status != ${RoadAddressChangeType.New.value} THEN 1 WHEN reversed = 1 AND status != ${RoadAddressChangeType.New.value} THEN 0 ELSE 0 END)
        where project_link.project_id = $projectId and project_link.road_number = ${roadPart.roadNumber} and project_link.road_part_number = ${roadPart.partNumber}
          and project_link.status != ${RoadAddressChangeType.Termination.value}"""
      runUpdateToDb(updateProjectLink)
    }
  }

  /**
    * Returns a counting of the project links by all the link status we supplied them limited by the project id, road number and road part number
    * @param projectId: Long - The id of the project
    * @param roadPart: RoadPart - Road part of the project link
    * @param roadAddressChangeTypes: Set[Long] - the collection of operations done to the project links
    * @return
    */
  def countLinksByStatus(projectId: Long, roadPart: RoadPart, roadAddressChangeTypes: Set[Long]): Long = {
    val filterByStatus = if(roadAddressChangeTypes.nonEmpty) s" AND Status IN (${roadAddressChangeTypes.mkString(",")})" else ""
    val query =
      s"""select count(id) from project_link
          WHERE project_id = $projectId and road_number = ${roadPart.roadNumber} and road_part_number = ${roadPart.partNumber} $filterByStatus"""
    Q.queryNA[Long](query).first
  }

  def getProjectLinksContinuityCodes(projectId: Long, roadPart: RoadPart): Map[Long, Discontinuity] = {
    sql""" SELECT END_ADDR_M, DISCONTINUITY_TYPE FROM PROJECT_LINK WHERE PROJECT_ID = $projectId AND
         ROAD_NUMBER = ${roadPart.roadNumber} AND ROAD_PART_NUMBER = ${roadPart.partNumber} AND STATUS != ${RoadAddressChangeType.Termination.value}
         AND (DISCONTINUITY_TYPE != ${Discontinuity.Continuous.value} OR END_ADDR_M =
         (SELECT MAX(END_ADDR_M) FROM PROJECT_LINK WHERE PROJECT_ID = $projectId AND
           ROAD_NUMBER = ${roadPart.roadNumber} AND ROAD_PART_NUMBER = ${roadPart.partNumber} AND STATUS != ${RoadAddressChangeType.Termination.value}))
       """.as[(Long, Int)].list.map(x => x._1 -> Discontinuity.apply(x._2)).toMap
  }

  def fetchFirstLink(projectId: Long, roadPart: RoadPart): Option[ProjectLink] = {
    val query = s"""$projectLinkQueryBase
    where PROJECT_LINK.ROAD_PART_NUMBER=${roadPart.partNumber} AND PROJECT_LINK.ROAD_NUMBER=${roadPart.roadNumber} AND
    PROJECT_LINK.START_ADDR_M = (SELECT MIN(PROJECT_LINK.START_ADDR_M) FROM PROJECT_LINK
      LEFT JOIN
      ROADWAY ON ((ROADWAY.road_number = PROJECT_LINK.road_number AND ROADWAY.road_part_number = PROJECT_LINK.road_part_number) OR ROADWAY.id = PROJECT_LINK.ROADWAY_ID)
      LEFT JOIN
      Linear_Location ON (Linear_Location.id = Project_Link.Linear_Location_Id)
      WHERE PROJECT_LINK.PROJECT_ID = $projectId AND ((PROJECT_LINK.ROAD_PART_NUMBER=${roadPart.partNumber} AND PROJECT_LINK.ROAD_NUMBER=${roadPart.roadNumber}) OR PROJECT_LINK.ROADWAY_ID = ROADWAY.ID))
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
      val count = runUpdateToDb(deleteLinks)
      count
    }
  }

  def removeProjectLinksById(ids: Set[Long]): Int = {
    if (ids.nonEmpty)
      deleteProjectLinks(ids)
    else
      0
  }

  def removeProjectLinksByLinkIds(projectId: Long, roadPart: Option[RoadPart], linkIds: Set[String] = Set()): Int = {
    if (linkIds.size > 900 || linkIds.isEmpty) {
      linkIds.grouped(900).map(g => removeProjectLinks(projectId, roadPart, g)).sum
    } else {
      removeProjectLinks(projectId, roadPart, linkIds)
    }
  }

  private def removeProjectLinks(projectId: Long, roadPart: Option[RoadPart], linkIds: Set[String] = Set()) = {
    val roadPartFilter = roadPart.map(l => s"AND road_number = ${l.roadNumber} AND road_part_number = ${l.partNumber}").getOrElse("")
    val linkIdFilter = if (linkIds.isEmpty) {
      ""
    } else {
      s"AND LINK_ID IN (${linkIds.map(l => ''' + l + ''').mkString(",")})"
    }
    val query =
      s"""SELECT pl.id FROM PROJECT_LINK pl WHERE
        project_id = $projectId $roadPartFilter $linkIdFilter"""
    val ids = Q.queryNA[Long](query).iterator.toSet
    if (ids.nonEmpty) {
      runUpdateToDb(s"""DELETE FROM ROADWAY_CHANGES_LINK WHERE PROJECT_ID = $projectId""")
      runUpdateToDb(s"""DELETE FROM ROADWAY_CHANGES WHERE PROJECT_ID = $projectId""")
      deleteProjectLinks(ids)
    }
    else
      0
  }

  def removeConnectedLinkId(originalLink: ProjectLink): Unit = {
    val sql = s"""
      UPDATE project_link
      SET connected_link_id = NULL
      WHERE id = ${originalLink.id}
    """

    try {
      runUpdateToDb(sql)
      logger.info(s"Removed connected_link_id for projectLinkId: ${originalLink.id}")
    } catch {
      case e: SQLException =>
        logger.error(s"SQLException occurred while removing connected_link_id for projectLinkId: ${originalLink.id}", e)
      case e: Exception =>
        logger.error(s"Unexpected exception occurred while removing connected_link_id for projectLinkId: ${originalLink.id}", e)
    }
  }

  /**
   * Handles the removal of a split link and updates necessary fields for the original link.
   * @throws Exception if an error occurs during the transaction
   * @param splitProjectLinkIds Set of the IDs of the split link to remove.
   * @param originalProjectLink The original link that the split link was created from.
   * @param projectId The ID of the project these links belong to.
   */
  def handleSplitProjectLinksRemovalAndUpdate(splitProjectLinkIds: Seq[Long], originalProjectLink: ProjectLink, projectId: Long): Unit = {
      try {
        // Remove all split project links
        if (splitProjectLinkIds.nonEmpty) {
          removeProjectLinksById(splitProjectLinkIds.toSet)
          logger.info(s"Removed split project links: ${splitProjectLinkIds.mkString(", ")}")
        }
        // Clear the connectedLinkId for the original link if splits were removed
        removeConnectedLinkId(originalProjectLink)
        logger.info(s"Cleared connectedLinkId for original project link: ${originalProjectLink.id}")
      } catch {
        case e: Exception =>
          logger.error(s"Error during split link removal and update for originalLinkId: ${originalProjectLink.id}, projectId: $projectId", e)
      }
  }

  def moveProjectLinksToHistory(projectId: Long): Unit = {
    runUpdateToDb(s"""
      INSERT INTO PROJECT_LINK_HISTORY (ID, PROJECT_ID, TRACK, DISCONTINUITY_TYPE,
        ROAD_NUMBER, ROAD_PART_NUMBER, START_ADDR_M, END_ADDR_M, CREATED_BY, MODIFIED_BY, CREATED_DATE,
        MODIFIED_DATE, STATUS, START_CALIBRATION_POINT, END_CALIBRATION_POINT,
        ORIG_START_CALIBRATION_POINT, ORIG_END_CALIBRATION_POINT, ADMINISTRATIVE_CLASS, ROADWAY_ID, LINEAR_LOCATION_ID,
        CONNECTED_LINK_ID, ELY, REVERSED, SIDE, START_MEASURE, END_MEASURE, LINK_ID, ADJUSTED_TIMESTAMP,
        LINK_SOURCE, GEOMETRY, ORIGINAL_START_ADDR_M, ORIGINAL_END_ADDR_M, ROADWAY_NUMBER)
        (SELECT DISTINCT ID, PROJECT_ID, TRACK, DISCONTINUITY_TYPE,
          ROAD_NUMBER, ROAD_PART_NUMBER, START_ADDR_M, END_ADDR_M, CREATED_BY, MODIFIED_BY, CREATED_DATE,
          MODIFIED_DATE, STATUS, START_CALIBRATION_POINT, END_CALIBRATION_POINT,
          ORIG_START_CALIBRATION_POINT, ORIG_END_CALIBRATION_POINT, ADMINISTRATIVE_CLASS, ROADWAY_ID, LINEAR_LOCATION_ID,
          CONNECTED_LINK_ID, ELY, REVERSED, SIDE, START_MEASURE, END_MEASURE, LINK_ID, ADJUSTED_TIMESTAMP,
          LINK_SOURCE, GEOMETRY, ORIGINAL_START_ADDR_M, ORIGINAL_END_ADDR_M, ROADWAY_NUMBER
        FROM PROJECT_LINK WHERE PROJECT_ID = $projectId)
    """)
    runUpdateToDb(s"""DELETE FROM ROADWAY_CHANGES_LINK WHERE PROJECT_ID = $projectId""")
    runUpdateToDb(s"""DELETE FROM PROJECT_LINK WHERE PROJECT_ID = $projectId""")
    runUpdateToDb(s"""DELETE FROM PROJECT_RESERVED_ROAD_PART WHERE PROJECT_ID = $projectId""")
  }

  //TODO used only from Spec
  def removeProjectLinksByProjectAndRoadNumber(projectId: Long, roadPart: RoadPart): Int = {
    removeProjectLinks(projectId, Some(roadPart))
  }

  def removeProjectLinksByProject(projectId: Long): Int = {
    removeProjectLinks(projectId, None)
  }

  def removeProjectLinksByLinkId(projectId: Long, linkIds: Set[String]): Int = {
    if (linkIds.nonEmpty)
      removeProjectLinks(projectId, None, linkIds)
    else
      0
  }

  def fetchSplitLinks(projectId: Long, linkId: String): Seq[ProjectLink] = {
    val query =
      s"""$projectLinkQueryBase
                where PROJECT_LINK.PROJECT_ID = $projectId AND (PROJECT_LINK.LINK_ID = '$linkId' OR PROJECT_LINK.CONNECTED_LINK_ID = '$linkId')"""
    listQuery(query)
  }

  def fetchProjectLinkElys(projectId: Long): Seq[Long] = {
    time(logger, "Get elys from project links.") {
      val query =
        s"""SELECT DISTINCT ELY FROM PROJECT_LINK
                where PROJECT_ID = $projectId """
      Q.queryNA[Long](query).list.sorted
    }
  }

  implicit val getDiscontinuity: GetResult[Option[Discontinuity]] = new GetResult[Option[Discontinuity]] {
    def apply(r: PositionedResult): Option[Discontinuity] = {
      r.nextLongOption().map(l => Discontinuity.apply(l))
    }
  }
}

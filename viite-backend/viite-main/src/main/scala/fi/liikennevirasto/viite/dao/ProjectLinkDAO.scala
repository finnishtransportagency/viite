package fi.liikennevirasto.viite.dao

import java.sql.SQLException
import java.util.Date
import fi.liikennevirasto.digiroad2.util.LogUtils.time
import fi.liikennevirasto.viite._
import fi.liikennevirasto.viite.process.InvalidAddressDataException
import fi.liikennevirasto.viite.util.CalibrationPointsUtils
import fi.vaylavirasto.viite.dao.{BaseDAO, Sequences}
import fi.vaylavirasto.viite.geometry.{GeometryUtils, Point, PolyLine, Vector3d}
import fi.vaylavirasto.viite.model.{AddrMRange, AdministrativeClass, CalibrationPointType, Discontinuity, LinkGeomSource, RoadAddressChangeType, RoadPart, SideCode, Track}
import fi.vaylavirasto.viite.postgis.GeometryDbUtils
import org.joda.time.DateTime
import scalikejdbc._
import scalikejdbc.jodatime.JodaWrappedResultSet.fromWrappedResultSetToJodaWrappedResultSet


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


object ProjectRoadLinkChange extends SQLSyntaxSupport [ProjectRoadLinkChange]{
  def apply(rs: WrappedResultSet): ProjectRoadLinkChange = {
/*
    logger.warn(
      s"""ProjRoadLinkCh ResultSet values:
         |project_link_id = ${rs.anyOpt("project_link_id")}
         |roadway_id = ${rs.anyOpt("roadway_id")}
         |LINEAR_LOCATION_ID = ${rs.anyOpt("LINEAR_LOCATION_ID")}
         |original_road_number = ${rs.anyOpt("original_road_number")}
         |original_road_part_number = ${rs.anyOpt("original_road_part_number")}
         |project_road_number = ${rs.anyOpt("project_road_number")}
         |project_road_part_number = ${rs.anyOpt("project_road_part_number")}
         |original_start_addr_m = ${rs.anyOpt("original_start_addr_m")}
         |original_end_addr_m = ${rs.anyOpt("original_end_addr_m")}
         |start_addr_m = ${rs.anyOpt("start_addr_m")}
         |end_addr_m = ${rs.anyOpt("end_addr_m")}
         |status = ${rs.anyOpt("status")}
         |reversed = ${rs.anyOpt("reversed")}
         |original_roadway_number = ${rs.anyOpt("original_roadway_number")}
         |project_roadway_number = ${rs.anyOpt("project_roadway_number")}
         |""".stripMargin)
*/
    new ProjectRoadLinkChange(
      id = rs.long("project_link_id"),
      roadwayId = rs.longOpt("roadway_id").getOrElse(0L),
      originalLinearLocationId = rs.longOpt("LINEAR_LOCATION_ID").getOrElse(0L),
      linearLocationId = 0,
      originalRoadPart = RoadPart(
        rs.longOpt("original_road_number").getOrElse(0L),
        rs.longOpt("original_road_part_number").getOrElse(0L)
      ),
      roadPart = RoadPart(
        rs.long("project_road_number"),
        rs.long("project_road_part_number")
        ),
      originalStartAddr = rs.long("original_start_addr_m"),
      originalEndAddr = rs.long("original_end_addr_m"),
      newStartAddr = rs.long("start_addr_m"),
      newEndAddr = rs.long("end_addr_m"),
      status = RoadAddressChangeType(rs.int("status")),
      reversed = rs.boolean("reversed"),
      originalRoadwayNumber = rs.longOpt("original_roadway_number").getOrElse(0L),
      newRoadwayNumber = rs.longOpt("project_roadway_number").getOrElse(0L)
    )
  }
}

class ProjectLinkDAO extends BaseDAO {

  val roadwayDAO = new RoadwayDAO

  private lazy val projectLinkQueryBase =
    sqls"""
  SELECT
    project_link.id,
    project_link.project_id,
    project_link.track,
    project_link.discontinuity_type,
    project_link.road_number,
    project_link.road_part_number,
    project_link.start_addr_m,
    project_link.end_addr_m,
    project_link.original_start_addr_m,
    project_link.original_end_addr_m,
    project_link.start_measure,
    project_link.end_measure,
    project_link.side,
    project_link.created_by,
    project_link.modified_by,
    project_link.link_id,
    project_link.geometry,
    (project_link.end_measure - project_link.start_measure) AS length,
    project_link.start_calibration_point,
    project_link.end_calibration_point,
    project_link.orig_start_calibration_point,
    project_link.orig_end_calibration_point,
    project_link.status,
    project_link.administrative_class,
    project_link.link_source,
    project_link.roadway_id,
    project_link.linear_location_id,
    project_link.ely, project_link.reversed,
    project_link.connected_link_id,
  CASE
    WHEN status = ${RoadAddressChangeType.NotHandled.value} THEN null
    WHEN status IN (${RoadAddressChangeType.Termination.value}, ${RoadAddressChangeType.Unchanged.value}) THEN ROADWAY.START_DATE
    ELSE prj.start_date END AS start_date,
  CASE WHEN STATUS = ${RoadAddressChangeType.Termination.value} THEN prj.start_date - 1 ELSE null END as end_date,
  PROJECT_LINK.ADJUSTED_TIMESTAMP,
  CASE
    WHEN rn.road_name IS NOT NULL AND rn.end_date IS NULL AND rn.valid_to IS NULL THEN rn.road_name
    WHEN rn.road_name IS NULL AND pln.road_name IS NOT NULL THEN pln.road_name
    END AS road_name_pl,
  project_link.original_start_addr_m AS ra_start_addr_m,
  project_link.original_end_addr_m AS ra_end_addr_m,
  roadway.track AS ra_track,
  roadway.road_number AS ra_road_number,
  roadway.road_part_number AS ra_road_part_number,
  roadway.roadway_number AS ra_roadway_number,
  project_link.roadway_number roadway_number
  from project prj JOIN project_link ON (prj.id = project_link.project_id)
    LEFT JOIN roadway on (roadway.id = project_link.roadway_id)
    LEFT JOIN Linear_Location ON (Linear_Location.ID = project_link.Linear_Location_Id)
    LEFT JOIN road_name rn ON (rn.road_number = project_link.road_number AND rn.end_date IS NULL AND rn.VALID_TO IS null)
	  LEFT JOIN project_link_name pln ON (pln.road_number = project_link.road_number AND pln.project_id = project_link.project_id)
	  """

  private lazy val projectLinkHistoryQueryBase =
    sqls"""
        SELECT
          plh.id,
          plh.project_id,
          plh.track,
          plh.discontinuity_type,
          plh.road_number,
          plh.road_part_number,
          plh.start_addr_m,
          plh.end_addr_m,
          plh.original_start_addr_m,
          plh.original_end_addr_m,
          plh.start_measure,
          plh.end_measure,
          plh.side,
          plh.created_by,
          plh.modified_by,
          plh.link_id,
          plh.geometry,
          (plh.end_measure - plh.start_measure) as length,
          plh.start_calibration_point,
          plh.end_calibration_point,
          plh.orig_start_calibration_point,
          plh.orig_end_calibration_point,
          plh.status,
          plh.administrative_class,
          plh.link_source,
          plh.roadway_id,
          plh.linear_location_id,
          plh.ely,
          plh.reversed,
          plh.connected_link_id,
          CASE
            WHEN STATUS = ${RoadAddressChangeType.NotHandled.value} THEN null
            WHEN STATUS IN (${RoadAddressChangeType.Termination.value}, ${RoadAddressChangeType.Unchanged.value}) THEN ROADWAY.START_DATE
            ELSE prj.start_date END as start_date,
          CASE WHEN status = ${RoadAddressChangeType.Termination.value} THEN prj.start_date - 1 ELSE null END as end_date,
          plh.adjusted_timestamp,
          CASE
            WHEN rn.road_name IS NOT NULL AND rn.END_DATE IS NULL AND rn.VALID_TO IS null THEN rn.road_name
            WHEN rn.road_name IS NULL AND pln.road_name IS NOT NULL THEN pln.road_name
            END AS road_name_pl,
          roadway.start_addr_m AS ra_start_addr_m,
          roadway.end_addr_m AS ra_end_addr_m,
          roadway.track AS ra_track,
          roadway.road_number AS ra_road_number,
          roadway.road_part_number AS ra_road_part_number,
          roadway.roadway_number AS ra_roadway_number,
          plh.roadway_number AS roadway_number
          FROM project prj JOIN project_link_history plh ON (prj.id = plh.project_id)
            LEFT JOIN ROADWAY ON (ROADWAY.ID = plh.ROADWAY_ID)
            LEFT JOIN Linear_Location ON (Linear_Location.ID = plh.Linear_Location_Id)
            LEFT JOIN ROAD_NAME rn ON (rn.road_number = plh.road_number AND rn.END_DATE IS NULL AND rn.VALID_TO IS null)
        	  LEFT JOIN project_link_name pln ON (pln.road_number = plh.road_number AND pln.project_id = plh.project_id)
     """

  private lazy val projectLinksChangeQueryBase =
    sqls"""
          SELECT
            project_link.id AS project_link_id,
            roadway.id AS roadway_id,
            project_link.linear_location_id,
            roadway.road_number AS original_road_number,
            roadway.road_part_number AS original_road_part_number,
            project_link.road_number AS project_road_number,
            project_link.road_part_number AS project_road_part_number,
            project_link.original_start_addr_m,
            project_link.original_end_addr_m,
            project_link.start_addr_m,
            project_link.end_addr_m,
            project_link.status,
            project_link.reversed,
            roadway.roadway_number AS original_roadway_number,
            project_link.roadway_number AS project_roadway_number
          from project prj JOIN project_link ON (prj.id = project_link.project_id)
          LEFT JOIN roadway ON (roadway.id = project_link.roadway_id)
          LEFT JOIN Linear_Location ON (Linear_Location.ID = project_link.linear_location_id)
      """

  object ProjectLink extends SQLSyntaxSupport[ProjectLink] {
    override val tableName = "PROJECT_LINK"

    def apply(rs: WrappedResultSet): ProjectLink = {
/*
            // Debug metadata
            val meta = rs.underlying.getMetaData
            val columnCount = meta.getColumnCount

            for (i <- 1 to columnCount) {
              logger.warn(s"Column $i: Name=${meta.getColumnName(i)}, " +
                s"Label=${meta.getColumnLabel(i)}, " +
                s"Type=${meta.getColumnTypeName(i)}")
            }

      logger.warn(
        s"""ResultSet values:
   id = ${rs.anyOpt("id")}
   road_number = ${rs.anyOpt("road_number")}
   road_part_number = ${rs.anyOpt("road_part_number")}
   track = ${rs.anyOpt("track")}
   discontinuity_type = ${rs.anyOpt("discontinuity_type")}
   start_addr_m = ${rs.anyOpt("start_addr_m")}
   end_addr_m = ${rs.anyOpt("end_addr_m")}
   original_start_addr_m = ${rs.anyOpt("original_start_addr_m")}
   original_end_addr_m = ${rs.anyOpt("original_end_addr_m")}
   start_date = ${rs.anyOpt("start_date")}
   end_date = ${rs.anyOpt("end_date")}
   created_by = ${rs.anyOpt("created_by")}
   link_id = ${rs.anyOpt("link_id")}

   start_measure = ${rs.anyOpt("start_measure")}
   end_measure = ${rs.anyOpt("end_measure")}
   side = ${rs.anyOpt("side")}
   start_calibration_point = ${rs.anyOpt("start_calibration_point")}
   end_calibration_point = ${rs.anyOpt("end_calibration_point")}
   orig_start_calibration_point = ${rs.anyOpt("orig_start_calibration_point")}
   orig_end_calibration_point = ${rs.anyOpt("orig_end_calibration_point")}
   geometry = ${rs.anyOpt("geometry")}
   project_id = ${rs.anyOpt("project_id")}
   status = ${rs.anyOpt("status")}
   administrative_class = ${rs.anyOpt("administrative_class")}
   link_source = ${rs.anyOpt("link_source")}
   length = ${rs.anyOpt("length")}
   roadway_id = ${rs.anyOpt("roadway_id")}
   linear_location_id = ${rs.anyOpt("linear_location_id")}


   ely = ${rs.anyOpt("ely")}
   reversed = ${rs.anyOpt("reversed")}
   connected_link_id = ${rs.anyOpt("connected_link_id")}
   adjusted_timestamp = ${rs.anyOpt("adjusted_timestamp")}
   roadway_number = ${rs.anyOpt("roadway_number")}
   ra_roadway_number = ${rs.anyOpt("ra_roadway_number")}
   road_name_pl = ${rs.anyOpt("road_name_pl")}
   ra_start_addr_m = ${rs.anyOpt("ra_start_addr_m")}
   ra_end_addr_m = ${rs.anyOpt("ra_end_addr_m")}
     """)

*/

      new ProjectLink(
        id = rs.long("id"),
        roadPart = RoadPart(rs.long("road_number"), rs.long("road_part_number")),
        track = Track(rs.int("track")),
        discontinuity = Discontinuity(rs.int("discontinuity_type")),
        addrMRange = AddrMRange(rs.long("start_addr_m"), rs.long("end_addr_m")),
        originalAddrMRange = AddrMRange(rs.long("original_start_addr_m"), rs.long("original_end_addr_m")),
        startDate = rs.jodaDateTimeOpt("start_date"),
        endDate = rs.jodaDateTimeOpt("end_date"),
        createdBy = rs.stringOpt("created_by"),
        linkId = rs.string("link_id"),
        startMValue = rs.double("start_measure"),
        endMValue = rs.double("end_measure"),
        sideCode = SideCode(rs.intOpt("side").getOrElse(0)),
        calibrationPointTypes = (
          CalibrationPointType(rs.int("start_calibration_point")),
          CalibrationPointType(rs.int("end_calibration_point"))
        ),
        originalCalibrationPointTypes = (
          CalibrationPointType(rs.int("orig_start_calibration_point")),
          CalibrationPointType(rs.int("orig_end_calibration_point"))
        ),
        geometry = GeometryDbUtils.loadJGeometryToGeometry(rs.anyOpt("geometry")),
        projectId = rs.long("project_id"),
        status = RoadAddressChangeType(rs.int("status")),
        administrativeClass = AdministrativeClass(rs.int("administrative_class")),
        linkGeomSource = LinkGeomSource(rs.int("link_source")),
        geometryLength = rs.double("length"),
        roadwayId = rs.longOpt("roadway_id").getOrElse(0L),
        linearLocationId = rs.longOpt("linear_location_id").getOrElse(0L),
        ely = rs.long("ely"),
        reversed = rs.boolean("reversed"),
        connectedLinkId = rs.stringOpt("connected_link_id"),
        linkGeometryTimeStamp = rs.long("adjusted_timestamp"),
        roadwayNumber = {
          val projectRoadwayNumber = rs.longOpt("roadway_number").getOrElse(0L)
          val roadwayNumber = rs.longOpt("ra_roadway_number").getOrElse(0L)
          if (projectRoadwayNumber == 0 || projectRoadwayNumber == NewIdValue)
            roadwayNumber
          else
            projectRoadwayNumber
        },
        roadName = rs.stringOpt("road_name_pl"),
        roadAddressLength = rs.longOpt("ra_end_addr_m").map(
          endAddr => endAddr - rs.longOpt("ra_start_addr_m").getOrElse(0L)
        ),
        roadAddressStartAddrM = rs.longOpt("ra_start_addr_m"),
        roadAddressEndAddrM = rs.longOpt("ra_end_addr_m"),
        roadAddressTrack = rs.intOpt("track").map(Track.apply),
        roadAddressRoadPart = (rs.longOpt("ra_road_number"), rs.longOpt("ra_road_part_number")) match {
          case (Some(roadNumber), Some(roadPartNumber)) => Some(RoadPart(roadNumber, roadPartNumber))
          case _ => None
        }
      )
    }
  }

  private def listQuery(query: SQL[Nothing, NoExtractor]): Seq[ProjectLink] = {
    runSelectQuery(query.map(ProjectLink.apply))
  }

  private def changesListQuery(query: SQL[Nothing, NoExtractor]): Seq[ProjectRoadLinkChange] = {
    runSelectQuery(query.map(ProjectRoadLinkChange.apply))
  }

  def create(links: Seq[ProjectLink]): Seq[Long] = {
    if (links.nonEmpty)
    time(logger, "Create project links") {
      val insertQuery = sql"""
        INSERT INTO project_link (id, project_id, road_number, road_part_number, track, discontinuity_type,
          start_addr_m, end_addr_m, original_start_addr_m, original_end_addr_m, created_by, modified_by,
          start_calibration_point, end_calibration_point, orig_start_calibration_point, orig_end_calibration_point,
          status, administrative_class, roadway_id, linear_location_id, connected_link_id, ely, roadway_number, reversed, geometry,
          link_id, SIDE, start_measure, end_measure, adjusted_timestamp, link_source, modified_date)
          VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ST_GeomFromText(?, 3067), ?, ?, ?, ?, ?, ?, ?)
        """

      val (ready, idLess) = links.partition(_.id != NewIdValue)
      val plIds = Sequences.fetchProjectLinkIds(idLess.size)
      val projectLinks = ready ++ idLess.zip(plIds).map(x =>
        x._1.copy(id = x._2)
      )
      val batchParams = projectLinks.map { pl =>
        Seq(
          pl.id,
          pl.projectId,
          pl.roadPart.roadNumber,
          pl.roadPart.partNumber,
          pl.track.value,
          pl.discontinuity.value,
          pl.addrMRange.start,
          pl.addrMRange.end,
          pl.originalAddrMRange.start,
          pl.originalAddrMRange.end,
          pl.createdBy.orNull,
          pl.createdBy.orNull,
          pl.startCalibrationPointType.value,
          pl.endCalibrationPointType.value,
          pl.originalStartCalibrationPointType.value,
          pl.originalEndCalibrationPointType.value,
          pl.status.value,
          pl.administrativeClass.value,
          if (pl.roadwayId == 0) null else pl.roadwayId,
          if (pl.linearLocationId == 0) null else pl.linearLocationId,
          pl.connectedLinkId.orNull,
          pl.ely,
          pl.roadwayNumber, if (pl.reversed) 1 else 0,
          GeometryDbUtils.createJGeometry(pl.geometry),
          pl.linkId,
          pl.sideCode.value,
          pl.startMValue,
          pl.endMValue,
          pl.linkGeometryTimeStamp,
          pl.linkGeomSource.value,
          new Date()

        )
      }

      runBatchUpdateToDb(insertQuery, batchParams)

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
      val updateQuery =
        sql"""
          UPDATE project_link
          SET ROAD_NUMBER = ?, ROAD_PART_NUMBER = ?, TRACK = ?, DISCONTINUITY_TYPE = ?, START_ADDR_M = ?, END_ADDR_M = ?,
            ORIGINAL_START_ADDR_M = ?, ORIGINAL_END_ADDR_M = ?, MODIFIED_DATE = CURRENT_TIMESTAMP, MODIFIED_BY = ?, PROJECT_ID = ?,
            START_CALIBRATION_POINT = ?, END_CALIBRATION_POINT = ?, ORIG_START_CALIBRATION_POINT = ?, ORIG_END_CALIBRATION_POINT = ?,
            STATUS = ?, ADMINISTRATIVE_CLASS = ?, REVERSED = ?, GEOMETRY = ST_GeomFromText(?, 3067), SIDE = ?, START_MEASURE = ?, END_MEASURE = ?,  ELY = ?,
            ROADWAY_NUMBER = ?, CONNECTED_LINK_ID = ?
          WHERE id = ?
          """

      val batchParams = links.map { pl =>
        Seq(
          pl.roadPart.roadNumber,
          pl.roadPart.partNumber,
          pl.track.value,
          pl.discontinuity.value,
          pl.addrMRange.start,
          pl.addrMRange.end,
          pl.originalAddrMRange.start,
          pl.originalAddrMRange.end,
          modifier,
          pl.projectId,
          pl.startCalibrationPointType.value,
          pl.endCalibrationPointType.value,
          pl.originalStartCalibrationPointType.value,
          pl.originalEndCalibrationPointType.value,
          pl.status.value,
          pl.administrativeClass.value,
          if (pl.reversed) 1 else 0,
          GeometryDbUtils.createJGeometry(pl.geometry),
          pl.sideCode.value,
          pl.startMValue,
          pl.endMValue,
          pl.ely,
          pl.roadwayNumber,
          pl.connectedLinkId.orNull,
          pl.id
        )
      }

      runBatchUpdateToDb(updateQuery, batchParams)
    }
  }

  def updateProjectLinksGeometry(projectLinks: Seq[ProjectLink], modifier: String): Unit = {
    time(logger,
      "Update project links geometry") {

      val updateQuery = sql"""
                        UPDATE project_link
                        SET  GEOMETRY = ST_GeomFromText(?, 3067), MODIFIED_BY= ?, ADJUSTED_TIMESTAMP = ?
                        WHERE id = ?
                        """

      val batchParams = projectLinks.map { pl =>
        Seq(
          GeometryDbUtils.createJGeometry(pl.geometry),
          modifier,
          pl.linkGeometryTimeStamp,
          pl.id
        )
      }

      runBatchUpdateToDb(updateQuery, batchParams)
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
      runUpdateToDb(sql"""
                UPDATE project_link
                SET modified_date = current_timestamp,
                    start_calibration_point = ${cals._1.value},
                    end_calibration_point = ${cals._2.value}
                WHERE id = ${projectLink.id}
      """)
    }
  }

  def fetchElyFromProjectLinks(projectId:Long): Option[Long]= {
    val query =
      sql"""
           SELECT ELY
           FROM PROJECT_LINK
           WHERE PROJECT_ID=$projectId
           AND ELY IS NOT NULL
           LIMIT 1
           """
    runSelectSingleFirstOptionWithType[Long](query)
  }

  def fetchProjectLinksHistory(projectId: Long, roadAddressChangeTypeFilter: Option[RoadAddressChangeType] = None): Seq[ProjectLink] = {
    time(logger, "Get project history links") {
      val filter = if (roadAddressChangeTypeFilter.isEmpty) sqls"" else sqls"plh.STATUS = ${roadAddressChangeTypeFilter.get.value} AND"
      val query =
        sql"""
            $projectLinkHistoryQueryBase
            WHERE $filter plh.PROJECT_ID = $projectId
            ORDER BY plh.ROAD_NUMBER, plh.ROAD_PART_NUMBER, plh.END_ADDR_M
            """
      listQuery(query)
    }
  }

  def fetchProjectLinks(projectId: Long, roadAddressChangeTypeFilter: Option[RoadAddressChangeType] = None): Seq[ProjectLink] = {
    time(logger, "Get project links") {
      val filter = if (roadAddressChangeTypeFilter.isEmpty) sqls"" else sqls"PROJECT_LINK.STATUS = ${roadAddressChangeTypeFilter.get.value} AND"
      val query =
        sql"""
              $projectLinkQueryBase
              WHERE $filter PROJECT_LINK.PROJECT_ID = $projectId
              ORDER BY PROJECT_LINK.ROAD_NUMBER, PROJECT_LINK.ROAD_PART_NUMBER, PROJECT_LINK.END_ADDR_M
              """
       /*Print the SQL query with values
      logger.warn(s"SQL Query: ${query.statement}")
      logger.warn(s"SQL Parameters: ${query.parameters}")
      */
      listQuery(query)
    }
  }

  def fetchProjectLinksChange(projectId: Long): Seq[ProjectRoadLinkChange] = {
    time(logger, "Get project links changes") {
      val query =
        sql"""
              $projectLinksChangeQueryBase
              WHERE PROJECT_LINK.PROJECT_ID = $projectId
              ORDER BY PROJECT_LINK.ROAD_NUMBER, PROJECT_LINK.ROAD_PART_NUMBER, PROJECT_LINK.END_ADDR_M
              """
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
          sql"""
                $projectLinkQueryBase
                WHERE project_link.id IN ($ids)
                ORDER BY PROJECT_LINK.ROAD_NUMBER, PROJECT_LINK.ROAD_PART_NUMBER, PROJECT_LINK.END_ADDR_M
                """
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
          sql"""
                $projectLinkQueryBase
                WHERE project_link.connected_link_id IN ($connectedIds)
                ORDER BY PROJECT_LINK.ROAD_NUMBER, PROJECT_LINK.ROAD_PART_NUMBER, PROJECT_LINK.END_ADDR_M
                """
        listQuery(query)
      }
    }
  }

  def getProjectLinksByLinkId(linkId: String): Seq[ProjectLink] = {
    time(logger, "Get project links by link id and project id") {
      val query =
        sql"""
            $projectLinkQueryBase
            WHERE PROJECT_LINK.link_id = $linkId
            ORDER BY PROJECT_LINK.ROAD_NUMBER, PROJECT_LINK.ROAD_PART_NUMBER, PROJECT_LINK.END_ADDR_M
            """
      listQuery(query)
    }
  }

  def getOtherProjectLinks(projectId: Long): Seq[ProjectLink] = {
    time(logger, "Get project links all") {
      val query =
        sql"""
            $projectLinkQueryBase
            WHERE PROJECT_LINK.project_id <> $projectId
            ORDER BY PROJECT_LINK.ROAD_NUMBER, PROJECT_LINK.ROAD_PART_NUMBER, PROJECT_LINK.END_ADDR_M
            """
      listQuery(query)
    }
  }

  def fetchProjectLinksByProjectAndLinkId(projectLinkIds: Set[Long], linkIds: Set[String], projectId: Long): Seq[ProjectLink] = {
    if (projectLinkIds.isEmpty && linkIds.isEmpty) {
      List()
    } else {
      val idsFilter = if (projectLinkIds.nonEmpty) sqls"AND PROJECT_LINK.ID IN ($projectLinkIds)" else sqls""
      val linkIdsFilter = if (linkIds.nonEmpty) sqls"AND PROJECT_LINK.LINK_ID IN ($linkIds)" else sqls""
      val query =
        sql"""
              $projectLinkQueryBase
              where PROJECT_LINK.PROJECT_ID = $projectId $idsFilter $linkIdsFilter
              order by PROJECT_LINK.ROAD_NUMBER, PROJECT_LINK.ROAD_PART_NUMBER, PROJECT_LINK.END_ADDR_M
              """
      listQuery(query)
    }
  }

  def fetchProjectLinksByLinkId(linkIds: Seq[String]): Seq[ProjectLink] = {
    if (linkIds.isEmpty) {
      List()
    } else {
      val linkIdsFilter =  sqls" PROJECT_LINK.LINK_ID IN ($linkIds)"
      val query =
        sql"""
              $projectLinkQueryBase
              WHERE $linkIdsFilter
              ORDER BY PROJECT_LINK.ROAD_NUMBER, PROJECT_LINK.ROAD_PART_NUMBER, PROJECT_LINK.END_ADDR_M
              """
      listQuery(query)
    }
  }

  def fetchProjectLinksByProjectRoadPart(roadPart: RoadPart, projectId: Long, roadAddressChangeTypeFilter: Option[RoadAddressChangeType] = None): Seq[ProjectLink] = {
    time(logger, "Get project links by project road part") {
      val filter = if (roadAddressChangeTypeFilter.isEmpty) sqls"" else sqls" PROJECT_LINK.STATUS = ${roadAddressChangeTypeFilter.get.value} AND"
      val query =
        sql"""
              $projectLinkQueryBase
              WHERE $filter PROJECT_LINK.ROAD_NUMBER = ${roadPart.roadNumber}
              AND PROJECT_LINK.ROAD_PART_NUMBER = ${roadPart.partNumber}
              AND PROJECT_LINK.PROJECT_ID = $projectId
              ORDER BY PROJECT_LINK.ROAD_NUMBER, PROJECT_LINK.ROAD_PART_NUMBER, PROJECT_LINK.END_ADDR_M
              """
      listQuery(query)
    }
  }

  def fetchByProjectRoadPart(roadPart: RoadPart, projectId: Long): Seq[ProjectLink] = {
    time(logger, "Fetch project links by project road part") {
      val filter = sqls"PROJECT_LINK.ROAD_NUMBER = ${roadPart.roadNumber} AND PROJECT_LINK.ROAD_PART_NUMBER = ${roadPart.partNumber} AND"
      val query =
        sql"""
            $projectLinkQueryBase
            WHERE $filter (PROJECT_LINK.PROJECT_ID = $projectId )
            ORDER BY PROJECT_LINK.ROAD_NUMBER, PROJECT_LINK.ROAD_PART_NUMBER, PROJECT_LINK.END_ADDR_M
            """
      listQuery(query)
    }
  }

  def fetchByProjectRoadParts(roadParts: Set[RoadPart], projectId: Long): Seq[ProjectLink] = {
    time(logger, "Fetch project links by project road parts") {
      if (roadParts.isEmpty)
        return Seq()
      // Using sqls interpolation for each condition
      val roadPartsCond = roadParts.map { roadPart =>
        sqls"(PROJECT_LINK.ROAD_NUMBER = ${roadPart.roadNumber} AND PROJECT_LINK.ROAD_PART_NUMBER = ${roadPart.partNumber})"
      }
      // Combine conditions with OR
      val roadPartsFilter = sqls.join(roadPartsCond.toSeq, sqls" OR ")

      val query =
        sql"""
              $projectLinkQueryBase
              WHERE $roadPartsFilter
              AND (PROJECT_LINK.PROJECT_ID = $projectId)
              ORDER BY PROJECT_LINK.ROAD_NUMBER, PROJECT_LINK.ROAD_PART_NUMBER, PROJECT_LINK.END_ADDR_M
              """
      listQuery(query)
    }
  }

  def fetchByProjectRoad(roadNumber: Long, projectId: Long): Seq[ProjectLink] = {
    time(logger, "Fetch project links by project road part") {
      val filter = sqls"PROJECT_LINK.ROAD_NUMBER = $roadNumber AND"
      val query =
        sql"""
              $projectLinkQueryBase
              WHERE $filter PROJECT_LINK.PROJECT_ID = $projectId
              ORDER BY PROJECT_LINK.ROAD_NUMBER, PROJECT_LINK.ROAD_PART_NUMBER, PROJECT_LINK.END_ADDR_M """
      listQuery(query)
    }
  }

  def updateAddrMValues(projectLink: ProjectLink): Unit = {
    runUpdateToDb(
      sql"""
      UPDATE project_link
      SET modified_date = current_timestamp, start_addr_m = ${projectLink.addrMRange.start}, end_addr_m = ${projectLink.addrMRange.end},
          start_calibration_point = ${projectLink.startCalibrationPointType.value},
          end_calibration_point = ${projectLink.endCalibrationPointType.value}
      WHERE id = ${projectLink.id}
    """
    )
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
      val query = sql"""
                  UPDATE PROJECT_LINK
                  SET STATUS = ${roadAddressChangeType.value}, MODIFIED_BY=$user, ROAD_NUMBER = ${newRoadPart.roadNumber},
                    ROAD_PART_NUMBER = ${newRoadPart.partNumber}, ELY = $ely
                  WHERE PROJECT_ID = $projectId
                  AND ROAD_NUMBER = ${roadPart.roadNumber}
                  AND ROAD_PART_NUMBER = ${roadPart.partNumber}
                  AND STATUS != ${RoadAddressChangeType.Termination.value}
        """
println(query)
      runUpdateToDb(query)
    }
  }

  def updateProjectLinkAdministrativeClassDiscontinuity(projectLinkIds: Set[Long], roadAddressChangeType: RoadAddressChangeType, userName: String, administrativeClass: Long, discontinuity: Option[Long]): Unit = {
    time(logger, "Update project link administrative class discontinuity") {
      val user = userName.replaceAll("[^A-Za-z0-9\\-]+", "")
      if (discontinuity.isEmpty) {
        projectLinkIds.grouped(500).foreach {
          grp =>
            val query = sql"""
                  UPDATE PROJECT_LINK
                  SET STATUS = ${roadAddressChangeType.value}, MODIFIED_BY=$user, ADMINISTRATIVE_CLASS= $administrativeClass
                  WHERE ID IN ($grp)
                  """
            runUpdateToDb(query)
        }
      } else {
        val query = sql"""
                  UPDATE PROJECT_LINK SET STATUS = ${roadAddressChangeType.value}, MODIFIED_BY = $user,
                    ADMINISTRATIVE_CLASS = $administrativeClass, DISCONTINUITY_TYPE = ${discontinuity.get}
                  WHERE ID = ${projectLinkIds.head}
                  """
        runUpdateToDb(query)
      }
    }
  }

  def updateProjectLinksStatus(ids: Set[Long], roadAddressChangeType: RoadAddressChangeType, userName: String): Unit = {
    val user = userName.replaceAll("[^A-Za-z0-9\\-]+", "")
    ids.grouped(500).foreach {
      grp =>
        val query = sql"""
                  UPDATE PROJECT_LINK SET STATUS = ${roadAddressChangeType.value}, MODIFIED_BY = $user
                  WHERE ID IN ($grp)
                  """
        runUpdateToDb(query)
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
        val lineString: String = GeometryDbUtils.createJGeometry(roadAddress.geometry)
        // Create geometry part of the query using sqls
        val geometryUpdate = if (updateGeom) {
          sqls", GEOMETRY = ST_GeomFromText($lineString, 3067)"
        } else {
          sqls""
        }
        val idFilter = plId match {
          case Some(id) => sqls"AND ID = $id"
          case None => sqls""
        }

        val updateProjectLink = sql"""
          UPDATE PROJECT_LINK
          SET ROAD_NUMBER = ${roadAddress.roadPart.roadNumber},
            ROAD_PART_NUMBER = ${roadAddress.roadPart.partNumber}, TRACK = ${roadAddress.track.value},
            DISCONTINUITY_TYPE = ${roadAddress.discontinuity.value}, ADMINISTRATIVE_CLASS = ${roadAddress.administrativeClass.value},
            STATUS = ${RoadAddressChangeType.NotHandled.value}, START_ADDR_M = ${roadAddress.addrMRange.start}, END_ADDR_M = ${roadAddress.addrMRange.end},
            ORIGINAL_START_ADDR_M = ${roadAddress.addrMRange.start}, ORIGINAL_END_ADDR_M = ${roadAddress.addrMRange.end},
            START_CALIBRATION_POINT = ${roadAddress.startCalibrationPointType.value},
            END_CALIBRATION_POINT = ${roadAddress.endCalibrationPointType.value},
            ORIG_START_CALIBRATION_POINT = ${roadAddress.startCalibrationPointType.value},
            ORIG_END_CALIBRATION_POINT = ${roadAddress.endCalibrationPointType.value},
            SIDE = ${roadAddress.sideCode.value}, ELY = ${roadAddress.ely},
            start_measure = ${roadAddress.startMValue}, end_measure = ${roadAddress.endMValue} $geometryUpdate
          WHERE LINEAR_LOCATION_ID = ${roadAddress.linearLocationId}
          AND PROJECT_ID = $projectId $idFilter
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
        val updateQuery =
          sql"""
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
        """
        val batchParams = projectLinks.map { pl =>
          Seq(
            pl.roadPart.roadNumber,
            pl.roadPart.partNumber,
            pl.track.value,
            pl.discontinuity.value,
            pl.administrativeClass.value,
            pl.addrMRange.start,
            pl.addrMRange.end,
            pl.originalAddrMRange.start,
            pl.originalAddrMRange.end,
            pl.calibrationPointTypes._1.value,
            pl.calibrationPointTypes._2.value,
            pl.calibrationPointTypes._1.value,
            pl.calibrationPointTypes._2.value,
            pl.sideCode.value,
            pl.ely,
            pl.startMValue,
            pl.endMValue,
            RoadAddressChangeType.Termination.value,
            pl.linearLocationId,
            pl.projectId
          )
        }

        val updateCounts = runBatchUpdateToDb(updateQuery, batchParams)
        // Check if any updates were made
        val updatesMade = updateCounts.exists(count => count > 0)
        if (!updatesMade) {
          logger.warn("No rows were updated during the batch update operation.")
        }
        updateCounts
      }
    }
  }




  def updateProjectLinkGeometry(projectLinkId: Long, geometry: Seq[Point]): Unit = {
    val geometryString = GeometryDbUtils.createJGeometry(geometry)
    val query = sql"""
                UPDATE project_link
                SET modified_date = current_timestamp, geometry = ST_GeomFromText('$geometryString', 3067), connected_link_id = NULL
                WHERE id = $projectLinkId
                """

    try {
      val updateCount = runUpdateToDb(query)
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
    * Reverses the given road part in project. Switches side codes 2 <-> 3, updates calibration points start <-> end,
    * updates track codes 1 <-> 2
    *
    * @param projectId
    * @param roadPart Road part to be reversed
    */
  def reverseRoadPartDirection(projectId: Long, roadPart: RoadPart): Unit = {
    time(logger, "Reverse road part direction") {
      val maxAddrQuery =
        sql"""
              SELECT MAX(END_ADDR_M) FROM PROJECT_LINK
              where project_link.project_id = $projectId and project_link.road_number = ${roadPart.roadNumber} and project_link.road_part_number = ${roadPart.partNumber}
              and project_link.status != ${RoadAddressChangeType.Termination.value}
              """

      val roadPartMaxAddr = runSelectSingleFirstOptionWithType[Long](maxAddrQuery).getOrElse(0L)

      val updateProjectLink =
        sql"""
          UPDATE project_link
          SET start_calibration_point = end_calibration_point, end_calibration_point = start_calibration_point,
            orig_start_calibration_point = orig_end_calibration_point, orig_end_calibration_point = orig_start_calibration_point,
            (start_addr_m, end_addr_m) = (SELECT $roadPartMaxAddr - pl2.end_addr_m, $roadPartMaxAddr - pl2.start_addr_m FROM PROJECT_LINK pl2 WHERE pl2.id = project_link.id),
            TRACK = (CASE TRACK WHEN ${Track.Combined.value} THEN ${Track.Combined.value} WHEN ${Track.RightSide.value} THEN ${Track.LeftSide.value} WHEN ${Track.LeftSide.value} THEN ${Track.RightSide.value} ELSE ${Track.Unknown.value} END),
            SIDE = (CASE SIDE WHEN ${SideCode.TowardsDigitizing.value} THEN ${SideCode.AgainstDigitizing.value} ELSE ${SideCode.TowardsDigitizing.value} END),
            reversed = (CASE WHEN reversed = 0 AND status != ${RoadAddressChangeType.New.value} THEN 1 WHEN reversed = 1 AND status != ${RoadAddressChangeType.New.value} THEN 0 ELSE 0 END)
          WHERE project_link.project_id = $projectId and project_link.road_number = ${roadPart.roadNumber} and project_link.road_part_number = ${roadPart.partNumber}
          AND project_link.status != ${RoadAddressChangeType.Termination.value}
          """
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
    val filterByStatus = if(roadAddressChangeTypes.nonEmpty) sqls" AND Status IN ($roadAddressChangeTypes)" else sqls""
    val query =
      sql"""
          SELECT count(id)
          FROM project_link
          WHERE project_id = $projectId
          AND road_number = ${roadPart.roadNumber}
          AND road_part_number = ${roadPart.partNumber} $filterByStatus
          """
    runSelectSingleFirstOptionWithType[Long](query).getOrElse(
      throw new NoSuchElementException(s"No project links found for project $projectId, road number ${roadPart.roadNumber} and road part number ${roadPart.partNumber}")
    )
  }

  def getProjectLinksContinuityCodes(projectId: Long, roadPart: RoadPart): Map[Long, Discontinuity] = {
    val query =
      sql"""
          SELECT END_ADDR_M, DISCONTINUITY_TYPE FROM PROJECT_LINK WHERE PROJECT_ID = $projectId AND
          ROAD_NUMBER = ${roadPart.roadNumber} AND ROAD_PART_NUMBER = ${roadPart.partNumber} AND STATUS != ${RoadAddressChangeType.Termination.value}
          AND (DISCONTINUITY_TYPE != ${Discontinuity.Continuous.value} OR END_ADDR_M =
          (SELECT MAX(END_ADDR_M) FROM PROJECT_LINK WHERE PROJECT_ID = $projectId AND
           ROAD_NUMBER = ${roadPart.roadNumber} AND ROAD_PART_NUMBER = ${roadPart.partNumber} AND STATUS != ${RoadAddressChangeType.Termination.value}))
       """
    runSelectQuery(query.map(rs => (rs.long("END_ADDR_M"), rs.int("DISCONTINUITY_TYPE"))))
      .map(x => x._1 -> Discontinuity.apply(x._2))
      .toMap
  }

  def fetchFirstLink(projectId: Long, roadPart: RoadPart): Option[ProjectLink] = {
    val query =
      sql"""
          $projectLinkQueryBase
          WHERE PROJECT_LINK.ROAD_PART_NUMBER=${roadPart.partNumber}
          AND PROJECT_LINK.ROAD_NUMBER=${roadPart.roadNumber}
          AND PROJECT_LINK.START_ADDR_M = (SELECT MIN(PROJECT_LINK.START_ADDR_M) FROM PROJECT_LINK
          LEFT JOIN
          ROADWAY ON ((ROADWAY.road_number = PROJECT_LINK.road_number AND ROADWAY.road_part_number = PROJECT_LINK.road_part_number) OR ROADWAY.id = PROJECT_LINK.ROADWAY_ID)
          LEFT JOIN
          Linear_Location ON (Linear_Location.id = Project_Link.Linear_Location_Id)
          WHERE PROJECT_LINK.PROJECT_ID = $projectId AND ((PROJECT_LINK.ROAD_PART_NUMBER=${roadPart.partNumber} AND PROJECT_LINK.ROAD_NUMBER=${roadPart.roadNumber}) OR PROJECT_LINK.ROADWAY_ID = ROADWAY.ID))
          ORDER BY PROJECT_LINK.ROAD_NUMBER, PROJECT_LINK.ROAD_PART_NUMBER, PROJECT_LINK.START_ADDR_M, PROJECT_LINK.TRACK
          """
    listQuery(query).headOption
  }

  private def deleteProjectLinks(ids: Set[Long]): Int = {
    if (ids.size > 900)
      ids.grouped(900).map(deleteProjectLinks).sum
    else {
      val deleteLinks =
        sql"""
         DELETE FROM PROJECT_LINK
         WHERE id IN ($ids)
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
    val roadPartFilter = roadPart.map(l =>
      sqls"AND road_number = ${l.roadNumber} AND road_part_number = ${l.partNumber}"
    ).getOrElse(sqls"")

    val linkIdFilter = if (linkIds.isEmpty) {
      sqls""
    } else {
      sqls"AND LINK_ID IN ($linkIds)"
    }

    val query = sql"""
            SELECT pl.id
            FROM PROJECT_LINK pl
            WHERE project_id = $projectId
            $roadPartFilter
            $linkIdFilter
            """
    val ids = runSelectQuery(query.map(rs => rs.long("id"))).toSet

    if (ids.nonEmpty) {
      runUpdateToDb(
        sql"""
             DELETE FROM ROADWAY_CHANGES_LINK
             WHERE PROJECT_ID = $projectId
             """
      )
      runUpdateToDb(
        sql"""
             DELETE FROM ROADWAY_CHANGES
             WHERE PROJECT_ID = $projectId
             """
      )
      deleteProjectLinks(ids)
    }
    else
      0
  }

  def removeConnectedLinkId(originalLink: ProjectLink): Unit = {
    val query = sql"""
      UPDATE project_link
      SET connected_link_id = NULL
      WHERE id = ${originalLink.id}
    """

    try {
      runUpdateToDb(query)
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
    runUpdateToDb(sql"""
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
        FROM PROJECT_LINK
        WHERE PROJECT_ID = $projectId)
    """)
    runUpdateToDb(
      sql"""
           DELETE FROM ROADWAY_CHANGES_LINK
           WHERE PROJECT_ID = $projectId
           """
    )
    runUpdateToDb(
      sql"""
           DELETE FROM PROJECT_LINK
           WHERE PROJECT_ID = $projectId
           """
    )
    runUpdateToDb(
      sql"""
           DELETE FROM PROJECT_RESERVED_ROAD_PART
           WHERE PROJECT_ID = $projectId
           """
    )
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
      sql"""
            $projectLinkQueryBase
            WHERE PROJECT_LINK.PROJECT_ID = $projectId
            AND (PROJECT_LINK.LINK_ID = $linkId
            OR PROJECT_LINK.CONNECTED_LINK_ID = $linkId)
            """
    listQuery(query)
  }

  def fetchProjectLinkElys(projectId: Long): Seq[Long] = {
    time(logger, "Get elys from project links.") {
      val query =
        sql"""
              SELECT DISTINCT ELY
              FROM PROJECT_LINK
              WHERE PROJECT_ID = $projectId
              """
      runSelectQuery(query.map(rs => rs.long("ELY"))).sorted
    }
  }

}

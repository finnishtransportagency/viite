package fi.liikennevirasto.viite.dao

import java.sql.Timestamp
import java.util.Date

import com.github.tototoshi.slick.MySQLJodaSupport._
import fi.liikennevirasto.digiroad2.{GeometryUtils, Point}
import fi.liikennevirasto.digiroad2.asset.{LinkGeomSource, SideCode}
import fi.liikennevirasto.digiroad2.dao.Sequences
import fi.liikennevirasto.digiroad2.linearasset.PolyLine
import fi.liikennevirasto.digiroad2.util.LogUtils.time
import fi.liikennevirasto.digiroad2.util.Track
import fi.liikennevirasto.viite._
import fi.liikennevirasto.viite.dao.LinkStatus.{NotHandled, UnChanged}
import fi.liikennevirasto.viite.dao.ProjectState.Incomplete
import fi.liikennevirasto.viite.process.InvalidAddressDataException
import fi.liikennevirasto.viite.util.CalibrationPointsUtils
import org.joda.time.DateTime
import org.slf4j.LoggerFactory
import slick.driver.JdbcDriver.backend.Database.dynamicSession
import slick.jdbc.StaticQuery.interpolation
import slick.jdbc.{GetResult, PositionedResult, StaticQuery => Q}

sealed trait ProjectState {
  def value: Int

  def description: String
}

object ProjectState {

  val values = Set(Closed, Incomplete, Sent2TR, ErrorInTR, TRProcessing, Saved2TR,
    Failed2GenerateTRIdInViite, Deleted, ErrorInViite, SendingToTR, Unknown)

  // These states are final
  val nonActiveStates = Set(ProjectState.Closed.value, ProjectState.Saved2TR.value)

  def apply(value: Long): ProjectState = {
    values.find(_.value == value).getOrElse(Closed)
  }

  case object Closed extends ProjectState {def value = 0; def description = "Suljettu"}
  case object Incomplete extends ProjectState {def value = 1; def description = "Keskeneräinen"}
  case object Sent2TR extends ProjectState {def value = 2; def description = "Lähetetty tierekisteriin"}
  case object ErrorInTR extends ProjectState {def value = 3; def description = "Virhe tierekisterissä"}
  case object TRProcessing extends ProjectState {def value = 4; def description = "Tierekisterissä käsittelyssä"}
  case object Saved2TR extends ProjectState{def value = 5; def description = "Viety tierekisteriin"}
  case object Failed2GenerateTRIdInViite extends ProjectState {def value = 6; def description = "Tierekisteri ID:tä ei voitu muodostaa"}
  case object Deleted extends ProjectState {def value = 7; def description = "Poistettu projekti"}

  case object ErrorInViite extends ProjectState {
    def value = 8

    def description = "Virhe Viite-sovelluksessa"
  }

  case object SendingToTR extends ProjectState {
    def value = 9

    def description = "Lähettää Tierekisteriin"
  }

  case object Unknown extends ProjectState {
    def value = 99

    def description = "Tuntematon"
  }

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

case class RoadAddressProject(id: Long, status: ProjectState, name: String, createdBy: String, createdDate: DateTime,
                              modifiedBy: String, startDate: DateTime, dateModified: DateTime, additionalInfo: String,
                              reservedParts: Seq[ReservedRoadPart], statusInfo: Option[String], ely: Option[Long] = None, coordinates: Option[ProjectCoordinates] = None) {
  def isReserved(roadNumber: Long, roadPartNumber: Long): Boolean = {
    reservedParts.exists(p => p.roadNumber == roadNumber && p.roadPartNumber == roadPartNumber)
  }
}

case class ProjectCoordinates(x: Double, y: Double, zoom: Int)

case class ProjectLink(id: Long, roadNumber: Long, roadPartNumber: Long, track: Track,
                       discontinuity: Discontinuity, startAddrMValue: Long, endAddrMValue: Long, startDate: Option[DateTime] = None,
                       endDate: Option[DateTime] = None, createdBy: Option[String] = None, linkId: Long, startMValue: Double, endMValue: Double, sideCode: SideCode,
                       calibrationPoints: (Option[CalibrationPoint], Option[CalibrationPoint]) = (None, None), floating: Boolean = false,
                       geometry: Seq[Point], projectId: Long, status: LinkStatus, roadType: RoadType,
                       linkGeomSource: LinkGeomSource = LinkGeomSource.NormalLinkInterface, geometryLength: Double, roadAddressId: Long,
                       ely: Long, reversed: Boolean, connectedLinkId: Option[Long] = None, linkGeometryTimeStamp: Long, commonHistoryId: Long = NewCommonHistoryId, blackUnderline: Boolean = false, roadName: Option[String] = None, roadAddressLength: Option[Long] = None)
  extends BaseRoadAddress with PolyLine {
  lazy val startingPoint = if (sideCode == SideCode.AgainstDigitizing) geometry.last else geometry.head
  lazy val endPoint = if (sideCode == SideCode.AgainstDigitizing) geometry.head else geometry.last
  lazy val isSplit: Boolean = connectedLinkId.nonEmpty || connectedLinkId.contains(0L)

  def copyWithGeometry(newGeometry: Seq[Point]) = {
    this.copy(geometry = newGeometry)
  }

  def addrAt(a: Double) = {
    val coefficient = (endAddrMValue - startAddrMValue) / (endMValue - startMValue)
    sideCode match {
      case SideCode.AgainstDigitizing =>
        endAddrMValue - Math.round((a-startMValue) * coefficient)
      case SideCode.TowardsDigitizing =>
        startAddrMValue + Math.round((a-startMValue) * coefficient)
      case _ => throw new InvalidAddressDataException(s"Bad sidecode $sideCode on project link")
    }
  }

  def addrMLength() = {
    if(isSplit)
      endAddrMValue - startAddrMValue
    else
      roadAddressLength.getOrElse(endAddrMValue - startAddrMValue)
  }

  def getFirstPoint(): Point = {
    if (sideCode == SideCode.TowardsDigitizing) geometry.head else geometry.last
  }

  def getLastPoint(): Point = {
    if (sideCode == SideCode.TowardsDigitizing) geometry.last else geometry.head
  }

  def toMeters(address: Long): Double = {
    val coefficient = (endMValue - startMValue) / (endAddrMValue - startAddrMValue)
    coefficient * address
  }
}

object ProjectDAO {
  private def logger = LoggerFactory.getLogger(getClass)

  private val projectLinkQueryBase =
    s"""select PROJECT_LINK.ID, PROJECT_LINK.PROJECT_ID, PROJECT_LINK.TRACK_CODE, PROJECT_LINK.DISCONTINUITY_TYPE,
  PROJECT_LINK.ROAD_NUMBER, PROJECT_LINK.ROAD_PART_NUMBER, PROJECT_LINK.START_ADDR_M, PROJECT_LINK.END_ADDR_M,
  PROJECT_LINK.START_MEASURE, PROJECT_LINK.END_MEASURE, PROJECT_LINK.SIDE_CODE,
  PROJECT_LINK.CREATED_BY, PROJECT_LINK.MODIFIED_BY, PROJECT_LINK.link_id, PROJECT_LINK.GEOMETRY,
  (PROJECT_LINK.END_MEASURE - PROJECT_LINK.START_MEASURE) as length, PROJECT_LINK.CALIBRATION_POINTS, PROJECT_LINK.STATUS,
  PROJECT_LINK.ROAD_TYPE, PROJECT_LINK.LINK_SOURCE as source, PROJECT_LINK.ROAD_ADDRESS_ID, PROJECT_LINK.ELY, PROJECT_LINK.REVERSED, PROJECT_LINK.CONNECTED_LINK_ID,
  CASE
    WHEN STATUS = ${LinkStatus.NotHandled.value} THEN null
    WHEN STATUS IN (${LinkStatus.Terminated.value}, ${LinkStatus.UnChanged.value}) THEN ROAD_ADDRESS.START_DATE
    ELSE PRJ.START_DATE END as start_date,
  CASE WHEN STATUS = ${LinkStatus.Terminated.value} THEN PRJ.START_DATE ELSE null END as end_date,
  PROJECT_LINK.ADJUSTED_TIMESTAMP,
  CASE
    WHEN rn.road_name IS NOT NULL AND rn.END_DATE IS NULL AND rn.VALID_TO IS null THEN rn.road_name
    WHEN rn.road_name IS NULL AND pln.road_name IS NOT NULL THEN pln.road_name
    END AS road_name_pl,
  ROAD_ADDRESS.START_ADDR_M as RA_START_ADDR_M,
  ROAD_ADDRESS.END_ADDR_M as RA_END_ADDR_M
  from PROJECT prj JOIN PROJECT_LINK ON (prj.id = PROJECT_LINK.PROJECT_ID)
    LEFT JOIN ROAD_ADDRESS ON (ROAD_ADDRESS.ID = PROJECT_LINK.ROAD_ADDRESS_ID)
    LEFT JOIN road_names rn ON (rn.road_number = project_link.road_number AND rn.END_DATE IS NULL AND rn.VALID_TO IS null)
	  LEFT JOIN project_link_name pln ON (pln.road_number = project_link.road_number AND pln.project_id = project_link.project_id)  """

  implicit val getProjectLinkRow = new GetResult[ProjectLink] {
    def apply(r: PositionedResult) = {
      val projectLinkId = r.nextLong()
      val projectId = r.nextLong()
      val trackCode = Track.apply(r.nextInt())
      val discontinuityType = Discontinuity.apply(r.nextInt())
      val roadNumber = r.nextLong()
      val roadPartNumber = r.nextLong()
      val startAddrM = r.nextLong()
      val endAddrM = r.nextLong()
      val startMValue = r.nextDouble()
      val endMValue = r.nextDouble()
      val sideCode = SideCode.apply(r.nextInt)
      val createdBy = r.nextStringOption()
      val modifiedBy = r.nextStringOption()
      val linkId = r.nextLong()
      val geom=r.nextStringOption()
      val length = r.nextDouble()
      val calibrationPoints =
        CalibrationPointsUtils.calibrations(CalibrationCode.apply(r.nextInt), linkId, startMValue, endMValue,
          startAddrM, endAddrM, sideCode)
      val status = LinkStatus.apply(r.nextInt())
      val roadType = RoadType.apply(r.nextInt())
      val source = LinkGeomSource.apply(r.nextInt())
      val roadAddressId = r.nextLong()
      val ely = r.nextLong()
      val reversed = r.nextBoolean()
      val connectedLinkId = r.nextLongOption()
      val startDate = r.nextDateOption().map(d => new DateTime(d.getTime))
      val endDate = r.nextDateOption().map(d => new DateTime(d.getTime))
      val geometryTimeStamp = r.nextLong()
      val roadName = r.nextString()
      val roadAddressStartAddrM = r.nextLongOption()
      val roadAddressEndAddrM = r.nextLongOption()

      ProjectLink(projectLinkId, roadNumber, roadPartNumber, trackCode, discontinuityType, startAddrM, endAddrM, startDate, endDate,
        modifiedBy, linkId, startMValue, endMValue, sideCode, calibrationPoints, false, parseStringGeometry(geom.getOrElse("")), projectId,
        status, roadType, source, length, roadAddressId, ely, reversed, connectedLinkId, geometryTimeStamp, roadName = Some(roadName), roadAddressLength = roadAddressEndAddrM.map(endAddr => endAddr - roadAddressStartAddrM.getOrElse(0L)))
    }
  }


  private def parseStringGeometry(geomString: String): Seq[Point] = {
    if (geomString.nonEmpty)
      toGeometry(geomString)
    else
      Seq()
  }

  private def listQuery(query: String) = {
    Q.queryNA[ProjectLink](query).iterator.toSeq
  }

  def create(links: Seq[ProjectLink]): Seq[Long] = {
    time(logger, "Create project links") {
      val addressPS = dynamicSession.prepareStatement("insert into PROJECT_LINK (id, project_id, " +
        "road_number, road_part_number, " +
        "track_code, discontinuity_type, START_ADDR_M, END_ADDR_M, created_by, " +
        "calibration_points, status, road_type, road_address_id, connected_link_id, ely, reversed, geometry, " +
        "link_id, SIDE_CODE, start_measure, end_measure, adjusted_timestamp, link_source) values " +
        "(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
      val (ready, idLess) = links.partition(_.id != NewRoadAddress)
      val plIds = Sequences.fetchViitePrimaryKeySeqValues(idLess.size)
      val projectLinks = ready ++ idLess.zip(plIds).map(x =>
        x._1.copy(id = x._2)
      )
      projectLinks.toList.foreach { case (pl) =>
        addressPS.setLong(1, pl.id)
        addressPS.setLong(2, pl.projectId)
        addressPS.setLong(3, pl.roadNumber)
        addressPS.setLong(4, pl.roadPartNumber)
        addressPS.setLong(5, pl.track.value)
        addressPS.setLong(6, pl.discontinuity.value)
        addressPS.setLong(7, pl.startAddrMValue)
        addressPS.setLong(8, pl.endAddrMValue)
        addressPS.setString(9, pl.createdBy.orNull)
        addressPS.setDouble(10, CalibrationCode.getFromAddress(pl).value)
        addressPS.setLong(11, pl.status.value)
        addressPS.setLong(12, pl.roadType.value)
        if (pl.roadAddressId == 0)
          addressPS.setString(13, null)
        else
          addressPS.setLong(13, pl.roadAddressId)
        if (pl.connectedLinkId.isDefined)
          addressPS.setLong(14, pl.connectedLinkId.get)
        else
          addressPS.setString(14, null)
        addressPS.setLong(15, pl.ely)
        addressPS.setBoolean(16, pl.reversed)
        addressPS.setString(17, toGeomString(pl.geometry))
        addressPS.setLong(18, pl.linkId)
        addressPS.setLong(19, pl.sideCode.value)
        addressPS.setDouble(20, pl.startMValue)
        addressPS.setDouble(21, pl.endMValue)
        addressPS.setDouble(22, pl.linkGeometryTimeStamp)
        addressPS.setInt(23, pl.linkGeomSource.value)
        addressPS.addBatch()
      }
      addressPS.executeBatch()
      addressPS.close()
      projectLinks.map(_.id)
    }
  }

  def updateProjectLinksToDB(projectLinks: Seq[ProjectLink], modifier: String): Unit = {
    time(logger, "Update project links") {
      val nonUpdatingStatus = Set[LinkStatus](NotHandled, UnChanged)
      val addresses = RoadAddressDAO.fetchByIdMassQuery(projectLinks.map(_.roadAddressId).toSet).map(ra => ra.id -> ra).toMap
      val maxInEachTracks = projectLinks.filter(pl => pl.status == UnChanged).groupBy(_.track).map(p => p._2.maxBy(_.endAddrMValue).id).toSeq
      val links = projectLinks.map { pl =>
        if (!pl.isSplit && nonUpdatingStatus.contains(pl.status) && addresses.contains(pl.roadAddressId) && !maxInEachTracks.contains(pl.id)) {
          val ra = addresses(pl.roadAddressId)
          // Discontinuity, road type and calibration points may change with Unchanged (and NotHandled) status
          pl.copy(roadNumber = ra.roadNumber, roadPartNumber = ra.roadPartNumber, track = ra.track,
            startAddrMValue = ra.startAddrMValue, endAddrMValue = ra.endAddrMValue,
            reversed = false)
        } else
          pl
      }
      val projectLinkPS = dynamicSession.prepareStatement("UPDATE project_link SET ROAD_NUMBER = ?,  ROAD_PART_NUMBER = ?, TRACK_CODE=?, " +
        "DISCONTINUITY_TYPE = ?, START_ADDR_M=?, END_ADDR_M=?, MODIFIED_DATE= ? , MODIFIED_BY= ?, PROJECT_ID= ?, " +
        "CALIBRATION_POINTS= ? , STATUS=?, ROAD_TYPE=?, REVERSED = ?, GEOMETRY = ?, " +
        "SIDE_CODE=?, START_MEASURE=?, END_MEASURE=? WHERE id = ?")

      for (projectLink <- links) {
        projectLinkPS.setLong(1, projectLink.roadNumber)
        projectLinkPS.setLong(2, projectLink.roadPartNumber)
        projectLinkPS.setInt(3, projectLink.track.value)
        projectLinkPS.setInt(4, projectLink.discontinuity.value)
        projectLinkPS.setLong(5, projectLink.startAddrMValue)
        projectLinkPS.setLong(6, projectLink.endAddrMValue)
        projectLinkPS.setDate(7, new java.sql.Date(new Date().getTime))
        projectLinkPS.setString(8, modifier)
        projectLinkPS.setLong(9, projectLink.projectId)
        projectLinkPS.setInt(10, CalibrationCode.getFromAddress(projectLink).value)
        projectLinkPS.setInt(11, projectLink.status.value)
        projectLinkPS.setInt(12, projectLink.roadType.value)
        projectLinkPS.setInt(13, if (projectLink.reversed) 1 else 0)
        projectLinkPS.setString(14, toGeomString(projectLink.geometry))
        projectLinkPS.setInt(15, projectLink.sideCode.value)
        projectLinkPS.setDouble(16, projectLink.startMValue)
        projectLinkPS.setDouble(17, projectLink.endMValue)
        projectLinkPS.setLong(18, projectLink.id)
        projectLinkPS.addBatch()
      }
      projectLinkPS.executeBatch()
      projectLinkPS.close()
    }
  }


  def updateProjectLinksGeometry(projectLinks: Seq[ProjectLink], modifier: String): Unit = {
    time(logger, "Update project links geometry") {
      val projectLinkPS = dynamicSession.prepareStatement("UPDATE project_link SET  GEOMETRY = ?, MODIFIED_BY= ?, ADJUSTED_TIMESTAMP = ? WHERE id = ?")

      for (projectLink <- projectLinks) {
        projectLinkPS.setString(1, toGeomString(projectLink.geometry))
        projectLinkPS.setString(2, modifier)
        projectLinkPS.setLong(3, projectLink.linkGeometryTimeStamp)
        projectLinkPS.setLong(4, projectLink.id)
        projectLinkPS.addBatch()
      }
      projectLinkPS.executeBatch()
      projectLinkPS.close()
    }
  }

  def createRoadAddressProject(roadAddressProject: RoadAddressProject): Unit = {
    sqlu"""
         insert into project (id, state, name, ely, created_by, created_date, start_date ,modified_by, modified_date, add_info)
         values (${roadAddressProject.id}, ${roadAddressProject.status.value}, ${roadAddressProject.name}, null, ${roadAddressProject.createdBy}, sysdate, ${roadAddressProject.startDate}, '-' , sysdate, ${roadAddressProject.additionalInfo})
         """.execute
  }

  def getElyFromProjectLinks(projectId:Long): Option[Long]= {
    val query =
      s"""SELECT ELY FROM PROJECT_LINK WHERE PROJECT_ID=$projectId AND ELY IS NOT NULL AND ROWNUM < 2"""
    Q.queryNA[Long](query).firstOption
  }

  def getProjectLinks(projectId: Long, linkStatusFilter: Option[LinkStatus] = None): Seq[ProjectLink] = {
    time(logger, "Get project links") {
      val filter = if (linkStatusFilter.isEmpty) "" else s"PROJECT_LINK.STATUS = ${linkStatusFilter.get.value} AND"
      val query =
        s"""$projectLinkQueryBase
                where $filter (PROJECT_LINK.PROJECT_ID = $projectId ) order by PROJECT_LINK.ROAD_NUMBER, PROJECT_LINK.ROAD_PART_NUMBER, PROJECT_LINK.END_ADDR_M """
      listQuery(query)
    }
  }

  //TODO: support for bigger queries than 1000 ids
  def getProjectLinksByIds(ids: Iterable[Long]): Seq[ProjectLink] = {
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

  def getProjectLinksByConnectedLinkId(connectedIds: Seq[Long]): Seq[ProjectLink] = {
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

  def getProjectLinksByLinkIdAndProjectId(projectLinkId: Long, projectid:Long): Seq[ProjectLink] = {
    time(logger, "Get project links by link id and project id") {
      val query =
        s"""$projectLinkQueryBase
                where PROJECT_LINK.link_id = $projectLinkId order by PROJECT_LINK.ROAD_NUMBER, PROJECT_LINK.ROAD_PART_NUMBER, PROJECT_LINK.END_ADDR_M """
      listQuery(query)
    }
  }

  def getProjectLinksByIds(projectId: Long, ids: Set[Long]): Seq[ProjectLink] = {
    time(logger, "Get project links by ids") {
      val filter = if (ids.nonEmpty) s"""AND PROJECT_LINK.ROAD_ADDRESS_ID in (${ids.mkString(",")})""" else ""
      val query =

        s"""$projectLinkQueryBase
                where PROJECT_LINK.PROJECT_ID = $projectId $filter"""
      listQuery(query).seq
    }
  }

  def getProjectLinksByProjectAndLinkId(projectLinkIds: Set[Long], linkIds: Seq[Long], projectId: Long): Seq[ProjectLink] = {
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

  def getProjectLinksByProjectRoadPart(road: Long, part: Long, projectId: Long, linkStatusFilter: Option[LinkStatus] = None): Seq[ProjectLink] = {
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

  def isRoadPartNotHandled(roadNumber: Long, roadPartNumber: Long, projectId: Long): Boolean = {
    time(logger, "Is road part not handled") {
      val filter = s"PROJECT_LINK.ROAD_NUMBER = $roadNumber AND PROJECT_LINK.ROAD_PART_NUMBER = $roadPartNumber " +
        s"AND PROJECT_LINK.PROJECT_ID = $projectId AND PROJECT_LINK.STATUS = ${LinkStatus.NotHandled.value}"
      val query =
        s"""select PROJECT_LINK.ID from PROJECT_LINK
                where $filter AND ROWNUM < 2 """
      Q.queryNA[Long](query).firstOption.nonEmpty
    }
  }


  //Should be only one
  def getProjectsWithGivenLinkId(linkId: Long): Seq[Long] = {
    time(logger, "Get projects with given link id") {
      val query =
        s"""SELECT P.ID
               FROM PROJECT P
              JOIN PROJECT_LINK PL ON P.ID=PL.PROJECT_ID
              WHERE P.STATE = ${Incomplete.value} AND PL.LINK_ID=$linkId"""
      Q.queryNA[(Long)](query).list
    }
  }


  def updateAddrMValues(projectLink: ProjectLink): Unit = {
    sqlu"""update project_link set modified_date = sysdate, start_addr_m = ${projectLink.startAddrMValue}, end_addr_m = ${projectLink.endAddrMValue}, calibration_points = ${CalibrationCode.getFromAddress(projectLink).value} where id = ${projectLink.id}
          """.execute
  }

  def updateRoadAddressProject(roadAddressProject: RoadAddressProject): Unit = {
    sqlu"""
         update project set state = ${roadAddressProject.status.value}, name = ${roadAddressProject.name}, modified_by = '-' ,modified_date = sysdate, add_info=${roadAddressProject.additionalInfo}, start_date=${roadAddressProject.startDate}, ely = ${roadAddressProject.ely} where id = ${roadAddressProject.id}
         """.execute
  }

  /**
    * Removes reserved road part and deletes the project links associated to it.
    * Requires links that have been transferred to this road part to be reverted before
    * or this will fail.
    *
    * @param projectId        Project's id
    * @param reservedRoadPart Road part to be removed
    */
  def removeReservedRoadPart(projectId: Long, reservedRoadPart: ReservedRoadPart): Unit = {
    time(logger, "Remove reserved road part") {
      sqlu"""
           DELETE FROM PROJECT_LINK WHERE PROJECT_ID = $projectId AND
           (EXISTS (SELECT 1 FROM ROAD_ADDRESS RA WHERE RA.ID = ROAD_ADDRESS_ID AND
           RA.ROAD_NUMBER = ${reservedRoadPart.roadNumber} AND RA.ROAD_PART_NUMBER = ${reservedRoadPart.roadPartNumber}))
           OR (ROAD_NUMBER = ${reservedRoadPart.roadNumber} AND ROAD_PART_NUMBER = ${reservedRoadPart.roadPartNumber}
           AND (STATUS = ${LinkStatus.New.value} OR STATUS = ${LinkStatus.Numbering.value}))
           """.execute
      sqlu"""
         DELETE FROM PROJECT_RESERVED_ROAD_PART WHERE id = ${reservedRoadPart.id}
         """.execute
    }
  }

  def removeReservedRoadPart(projectId: Long, roadNumber: Long, roadPartNumber: Long): Unit = {
    sqlu"""
         DELETE FROM PROJECT_RESERVED_ROAD_PART WHERE project_id = ${projectId} and road_number = ${roadNumber} and road_part_number = ${roadPartNumber}
         """.execute
  }

  def removeReservedRoadPartsByProject(projectId: Long): Unit = {
    sqlu"""
         DELETE FROM PROJECT_RESERVED_ROAD_PART WHERE project_id = ${projectId}
         """.execute
  }

  def getProjectEly(roadAddressProjectId: Long): Option[Long] = {
    val query =
      s"""
         SELECT ELY
         FROM project
         WHERE id=$roadAddressProjectId
       """
    Q.queryNA[Option[Long]](query).firstOption.getOrElse(None)
  }

  def updateProjectEly(roadAddressProjectId: Long, ely: Long): Unit = {
    sqlu"""
       update project set ely = $ely, modified_date = sysdate where id =  ${roadAddressProjectId}
      """.execute
  }

  def getRoadAddressProjectById(projectId: Long): Option[RoadAddressProject] = {
    time(logger, "Get road address project by id") {
      val where = s""" where id =${projectId}"""
      val query =
        s"""SELECT id, state, name, created_by, created_date, start_date, modified_by, COALESCE(modified_date, created_date),
           add_info, ely, status_info, coord_x, coord_y, zoom
           FROM project $where"""
      Q.queryNA[(Long, Long, String, String, DateTime, DateTime, String, DateTime, String, Option[Long], Option[String], Double, Double, Int)](query).list.map {
        case (id, state, name, createdBy, createdDate, start_date, modifiedBy, modifiedDate, addInfo,
        ely, statusInfo, coordX, coordY, zoom) if ely.contains(-1L) =>
          RoadAddressProject(id, ProjectState.apply(state), name, createdBy, createdDate, modifiedBy, start_date, modifiedDate,
            addInfo, fetchReservedRoadParts(id), statusInfo, None, Some(ProjectCoordinates(coordX, coordY, zoom)))
        case (id, state, name, createdBy, createdDate, start_date, modifiedBy, modifiedDate, addInfo,
        ely, statusInfo, coordX, coordY, zoom) =>
          RoadAddressProject(id, ProjectState.apply(state), name, createdBy, createdDate, modifiedBy, start_date, modifiedDate,
            addInfo, fetchReservedRoadParts(id), statusInfo, ely, Some(ProjectCoordinates(coordX, coordY, zoom)))
      }.headOption
    }
  }

  def fetchReservedRoadParts(projectId: Long): Seq[ReservedRoadPart] = {
    time(logger, s"Fetch reserved road parts for project: $projectId") {
      val sql =
        s"""
        SELECT id, road_number, road_part_number, length, length_new,
          ely, ely_new,
          (SELECT DISCONTINUITY FROM ROAD_ADDRESS ra WHERE ra.road_number = gr.road_number AND
            ra.road_part_number = gr.road_part_number AND RA.END_DATE IS NULL AND RA.VALID_TO IS NULL
            AND END_ADDR_M = gr.length and ROWNUM < 2) as discontinuity,
          (SELECT DISCONTINUITY_TYPE FROM PROJECT_LINK pl WHERE pl.project_id = gr.project_id
            AND pl.road_number = gr.road_number AND pl.road_part_number = gr.road_part_number
            AND PL.STATUS != 5 AND PL.TRACK_CODE IN (0,1)
            AND END_ADDR_M = gr.length_new AND ROWNUM < 2) as discontinuity_new,
          (SELECT LINK_ID FROM PROJECT_LINK pl
            WHERE pl.project_id = gr.project_id
            AND pl.road_number = gr.road_number AND pl.road_part_number = gr.road_part_number
            AND PL.STATUS != 5 AND PL.TRACK_CODE IN (0,1) AND pl.START_ADDR_M = 0
            AND pl.END_ADDR_M > 0 AND ROWNUM < 2) as first_link
          FROM (
            SELECT rp.id, rp.project_id, rp.road_number, rp.road_part_number,
              MAX(ra.END_ADDR_M) as length,
              MAX(pl.END_ADDR_M) as length_new,
              MAX(ra.ely) as ELY,
              MAX(pl.ely) as ELY_NEW
              FROM PROJECT_RESERVED_ROAD_PART rp LEFT JOIN
              PROJECT_LINK pl ON (pl.project_id = rp.project_id AND pl.road_number = rp.road_number AND
              pl.road_part_number = rp.road_part_number AND pl.status != 5)
              LEFT JOIN
              ROAD_ADDRESS ra ON (ra.id = pl.ROAD_ADDRESS_ID OR (ra.road_number = rp.road_number AND ra.road_part_number = rp.road_part_number AND RA.END_DATE IS NULL AND RA.VALID_TO IS NULL))
              WHERE
                rp.project_id = $projectId
                GROUP BY rp.id, rp.project_id, rp.road_number, rp.road_part_number
            ) gr"""
      Q.queryNA[(Long, Long, Long, Option[Long], Option[Long], Option[Long], Option[Long], Option[Long],
        Option[Long], Option[Long])](sql).list.map {
        case (id, road, part, length, newLength, ely, newEly, discontinuity, newDiscontinuity, startingLinkId) =>
          ReservedRoadPart(id, road, part, length, discontinuity.map(Discontinuity.apply), ely, newLength,
            newDiscontinuity.map(Discontinuity.apply), newEly, startingLinkId)
      }
    }
  }


  def fetchReservedRoadPart(roadNumber: Long, roadPartNumber: Long): Option[ReservedRoadPart] = {
    time(logger, "Fetch reserved road part") {
      val sql =
        s"""
        SELECT id, road_number, road_part_number, length, length_new,
          ely, ely_new,
          (SELECT DISCONTINUITY FROM ROAD_ADDRESS ra WHERE ra.road_number = gr.road_number AND
          ra.road_part_number = gr.road_part_number AND RA.END_DATE IS NULL AND RA.VALID_TO IS NULL
          AND END_ADDR_M = gr.length and ROWNUM < 2) as discontinuity,
          (SELECT DISCONTINUITY_TYPE FROM PROJECT_LINK pl WHERE pl.project_id = gr.project_id
          AND pl.road_number = gr.road_number AND pl.road_part_number = gr.road_part_number
          AND PL.STATUS != 5 AND PL.TRACK_CODE IN (0,1)
          AND END_ADDR_M = gr.length_new and ROWNUM < 2) as discontinuity_new,
          (SELECT LINK_ID FROM PROJECT_LINK pl
            WHERE pl.project_id = gr.project_id
            AND pl.road_number = gr.road_number AND pl.road_part_number = gr.road_part_number
            AND PL.STATUS != 5 AND PL.TRACK_CODE IN (0,1) AND pl.START_ADDR_M = 0
            AND pl.END_ADDR_M > 0 AND ROWNUM < 2) as first_link
          FROM (
            SELECT rp.id, rp.project_id, rp.road_number, rp.road_part_number,
              MAX(ra.END_ADDR_M) as length,
              MAX(pl.END_ADDR_M) as length_new,
              MAX(ra.ely) as ELY,
              MAX(pl.ely) as ELY_NEW
              FROM PROJECT_RESERVED_ROAD_PART rp
              LEFT JOIN
              PROJECT_LINK pl ON (pl.project_id = rp.project_id AND pl.road_number = rp.road_number AND pl.road_part_number = rp.road_part_number)
              LEFT JOIN
              ROAD_ADDRESS ra ON ((ra.road_number = rp.road_number AND ra.road_part_number = rp.road_part_number) OR ra.id = pl.ROAD_ADDRESS_ID)
              WHERE
                rp.road_number = $roadNumber AND rp.road_part_number = $roadPartNumber AND
                RA.END_DATE IS NULL AND RA.VALID_TO IS NULL AND
                (PL.STATUS IS NULL OR (PL.STATUS != 5 AND PL.TRACK_CODE IN (0,1)))
              GROUP BY rp.id, rp.project_id, rp.road_number, rp.road_part_number
              ) gr"""
      Q.queryNA[(Long, Long, Long, Option[Long], Option[Long], Option[Long], Option[Long], Option[Long],
        Option[Long], Option[Long])](sql).firstOption.map {
        case (id, road, part, length, newLength, ely, newEly, discontinuity, newDiscontinuity, linkId) =>
          ReservedRoadPart(id, road, part, length, discontinuity.map(Discontinuity.apply), ely, newLength,
            newDiscontinuity.map(Discontinuity.apply), newEly, linkId)
      }
    }
  }

  def getRoadAddressProjects(projectId: Long = 0, withNullElyFilter: Boolean = false): List[RoadAddressProject] = {
    time(logger, "Get road address projects") {
      val filter = projectId match {
        case 0 => if (withNullElyFilter) s""" where ELY IS NULL """ else ""
        case _ => if (withNullElyFilter) s""" where id =$projectId AND ELY IS NULL """ else s""" where id =$projectId """
      }

      val query =
        s"""SELECT id, state, name, created_by, created_date, start_date, modified_by, COALESCE(modified_date, created_date),
            add_info, status_info, ely, coord_x, coord_y, zoom
           FROM project $filter order by ely nulls first, name, id """
      Q.queryNA[(Long, Long, String, String, DateTime, DateTime, String, DateTime, String, Option[String], Option[Long], Double, Double, Int)](query).list.map {
        case (id, state, name, createdBy, createdDate, start_date, modifiedBy, modifiedDate, addInfo, statusInfo, ely, coordX, coordY, zoom) => {
          RoadAddressProject(id, ProjectState.apply(state), name, createdBy, createdDate, modifiedBy, start_date,
            modifiedDate, addInfo, if(projectId != 0) fetchReservedRoadParts(id) else Seq(), statusInfo, ely, Some(ProjectCoordinates(coordX, coordY, zoom)))
        }
      }
    }
  }

  def roadPartReservedTo(roadNumber: Long, roadPart: Long): Option[(Long, String)] = {
    time(logger, "Road part reserved to") {
      val query =
        s"""SELECT p.id, p.name
              FROM project p
              JOIN PROJECT_RESERVED_ROAD_PART l
           ON l.PROJECT_ID =  p.ID
           WHERE l.road_number=$roadNumber AND road_part_number=$roadPart"""
      Q.queryNA[(Long, String)](query).firstOption
    }
  }

  def roadPartReservedByProject(roadNumber: Long, roadPart: Long, projectId: Long = 0, withProjectId: Boolean = false): Option[String] = {
    time(logger, "Road part reserved by project") {
      val filter = if (withProjectId && projectId != 0) s" AND project_id != ${projectId} " else ""
      val query =
        s"""SELECT p.name
              FROM project p
              JOIN PROJECT_RESERVED_ROAD_PART l
           ON l.PROJECT_ID =  p.ID
           WHERE l.road_number=$roadNumber AND road_part_number=$roadPart $filter"""
      Q.queryNA[String](query).firstOption
    }
  }

  def getProjectStatus(projectID: Long): Option[ProjectState] = {
    val query =
      s""" SELECT state
            FROM project
            WHERE id=$projectID
   """
    Q.queryNA[Long](query).firstOption match {
      case Some(statenumber) => Some(ProjectState.apply(statenumber))
      case None => None
    }
  }

  def getCheckCounter(projectID: Long): Option[Long] = {
    val query =
      s"""
         SELECT CHECK_COUNTER
         FROM project
         WHERE id=$projectID
       """
    Q.queryNA[Long](query).firstOption match {
      case Some(number) => Some(number)
      case None => Some(0)
    }
  }

  def setCheckCounter(projectID: Long, counter: Long) = {
    sqlu"""UPDATE project SET check_counter=$counter WHERE id=$projectID""".execute
  }

  def incrementCheckCounter(projectID: Long, increment: Long) = {
    sqlu"""UPDATE project SET check_counter = check_counter + $increment WHERE id=$projectID""".execute
  }

  def updateProjectLinkNumbering(projectId: Long, roadNumber: Long, roadPart: Long, linkStatus: LinkStatus, newRoadNumber: Long, newRoadPart: Long, userName: String, discontinuity: Long): Unit = {
    time(logger, "Update project link numbering") {
      val user = userName.replaceAll("[^A-Za-z0-9\\-]+", "")

      val sql = s"UPDATE PROJECT_LINK SET STATUS = ${linkStatus.value}, MODIFIED_BY='$user', ROAD_NUMBER = $newRoadNumber, ROAD_PART_NUMBER = $newRoadPart" +
        s"WHERE PROJECT_ID = $projectId  AND ROAD_NUMBER = $roadNumber AND ROAD_PART_NUMBER = $roadPart AND STATUS != ${LinkStatus.Terminated.value}"
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

  def updateProjectLinks(projectLinkIds: Set[Long], linkStatus: LinkStatus, userName: String): Unit = {
    val user = userName.replaceAll("[^A-Za-z0-9\\-]+", "")
    projectLinkIds.grouped(500).foreach {
      grp =>
        val sql = s"UPDATE PROJECT_LINK SET STATUS = ${linkStatus.value}, MODIFIED_BY='$user' " +
          s"WHERE ID IN ${grp.mkString("(", ",", ")")}"
        Q.updateNA(sql).execute
    }
  }

  def updateProjectLinksToTerminated(projectLinkIds: Set[Long], userName: String): Unit = {
    val user = userName.replaceAll("[^A-Za-z0-9\\-]+", "")
    projectLinkIds.grouped(500).foreach {
      grp =>
        val sql = s"UPDATE PROJECT_LINK SET STATUS = ${LinkStatus.Terminated.value}, MODIFIED_BY='$user' " +
          s"WHERE ID IN ${grp.mkString("(", ",", ")")}"
        Q.updateNA(sql).execute
    }
  }

  def addRotatingTRProjectId(projectId: Long) = {
    Q.updateNA(s"UPDATE PROJECT SET TR_ID = VIITE_PROJECT_SEQ.nextval WHERE ID= $projectId").execute
  }

  def removeRotatingTRProjectId(projectId: Long) = {
    Q.updateNA(s"UPDATE PROJECT SET TR_ID = NULL WHERE ID= $projectId").execute
  }

  def updateProjectStateInfo(stateInfo: String, projectId: Long) = {
    Q.updateNA(s"UPDATE PROJECT SET STATUS_INFO = '$stateInfo' WHERE ID= $projectId").execute
  }

  def updateProjectCoordinates(projectId: Long, coordinates: ProjectCoordinates) = {
    Q.updateNA(s"UPDATE PROJECT SET COORD_X = ${coordinates.x},COORD_Y = ${coordinates.y}, ZOOM = ${coordinates.zoom} WHERE ID= $projectId").execute
  }

  def getRotatingTRProjectId(projectId: Long) = {
    Q.queryNA[Long](s"Select tr_id From Project WHERE Id=$projectId AND tr_id IS NOT NULL ").list
  }

  def updateProjectLinkValues(projectId: Long, roadAddress: RoadAddress, updateGeom : Boolean = true) = {
    time(logger, "Update project link values") {

      val updateGeometry = if (updateGeom) s", GEOMETRY = '${toGeomString(roadAddress.geometry)}'" else s""

      val updateProjectLink = s"UPDATE PROJECT_LINK SET ROAD_NUMBER = ${roadAddress.roadNumber}, " +
        s" ROAD_PART_NUMBER = ${roadAddress.roadPartNumber}, TRACK_CODE = ${roadAddress.track.value}, " +
        s" DISCONTINUITY_TYPE = ${roadAddress.discontinuity.value}, ROAD_TYPE = ${roadAddress.roadType.value}, " +
        s" STATUS = ${LinkStatus.NotHandled.value}, START_ADDR_M = ${roadAddress.startAddrMValue}, END_ADDR_M = ${roadAddress.endAddrMValue}, " +
        s" CALIBRATION_POINTS = ${CalibrationCode.getFromAddress(roadAddress).value}, CONNECTED_LINK_ID = null, REVERSED = 0, " +
        s" SIDE_CODE = ${roadAddress.sideCode.value}, " +
        s" start_measure = ${roadAddress.startMValue}, end_measure = ${roadAddress.endMValue} $updateGeometry" +
        s" WHERE ROAD_ADDRESS_ID = ${roadAddress.id} AND PROJECT_ID = $projectId"
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
        s"track_code = (CASE track_code WHEN 0 THEN 0 WHEN 1 THEN 2 WHEN 2 THEN 1 ELSE 3 END), " +
        s"(start_addr_m, end_addr_m) = (SELECT $roadPartMaxAddr - pl2.end_addr_m, $roadPartMaxAddr - pl2.start_addr_m FROM PROJECT_LINK pl2 WHERE pl2.id = project_link.id), " +
        s"side_code = (CASE side_code WHEN 2 THEN 3 ELSE 2 END) " +
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

  def reserveRoadPart(projectId: Long, roadNumber: Long, roadPartNumber: Long, user: String): Unit = {
    sqlu"""INSERT INTO PROJECT_RESERVED_ROAD_PART(id, road_number, road_part_number, project_id, created_by)
      SELECT viite_general_seq.nextval, $roadNumber, $roadPartNumber, $projectId, $user FROM DUAL""".execute
  }

  def getReservedRoadPart(projectId: Long, roadNumber: Long, roadPartNumber: Long): Long = {
    val query = s"""SELECT ID FROM PROJECT_RESERVED_ROAD_PART WHERE PROJECT_ID = $projectId AND
            ROAD_NUMBER = $roadNumber AND ROAD_PART_NUMBER = $roadPartNumber"""
    Q.queryNA[Long](query).list.head
  }

  def countLinksUnchangedUnhandled(projectId: Long, roadNumber: Long, roadPartNumber: Long): Long = {
    val query =
      s"""select count(id) from project_link
          WHERE project_id = $projectId and road_number = $roadNumber and road_part_number = $roadPartNumber and
          (status = ${LinkStatus.UnChanged.value} or status = ${LinkStatus.NotHandled.value})"""
    Q.queryNA[Long](query).first
  }

  def updateProjectStatus(projectID: Long, state: ProjectState) {
    sqlu""" update project set state=${state.value} WHERE id=$projectID""".execute
  }

  def getProjectsWithWaitingTRStatus(): List[Long] = {
    val query =
      s"""
         SELECT id
         FROM project
         WHERE state=${ProjectState.Sent2TR.value} OR state=${ProjectState.TRProcessing.value}
       """
    Q.queryNA[Long](query).list
  }

  def getProjectsWithSendingToTRStatus(): List[Long] = {
    val query =
      s"""
         SELECT id
         FROM project
         WHERE state=${ProjectState.SendingToTR.value}
       """
    Q.queryNA[Long](query).list
  }

  def getContinuityCodes(projectId: Long, roadNumber: Long, roadPartNumber: Long): Map[Long, Discontinuity] = {
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
      ROAD_ADDRESS ON ((ROAD_ADDRESS.road_number = PROJECT_LINK.road_number AND ROAD_ADDRESS.road_part_number = PROJECT_LINK.road_part_number) OR ROAD_ADDRESS.id = PROJECT_LINK.ROAD_ADDRESS_ID)
      WHERE PROJECT_LINK.PROJECT_ID = $projectId AND ((PROJECT_LINK.ROAD_PART_NUMBER=$roadPartNumber AND PROJECT_LINK.ROAD_NUMBER=$roadNumber) OR PROJECT_LINK.ROAD_ADDRESS_ID = ROAD_ADDRESS.ID))
    order by PROJECT_LINK.ROAD_NUMBER, PROJECT_LINK.ROAD_PART_NUMBER, PROJECT_LINK.START_ADDR_M, PROJECT_LINK.TRACK_CODE """
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
    val query = s"""SELECT pl.id FROM PROJECT_LINK pl WHERE
        project_id = $projectId $roadFilter $roadPartFilter $linkIdFilter"""
    val ids = Q.queryNA[Long](query).iterator.toSet
    if (ids.nonEmpty)
      deleteProjectLinks(ids)
    else
      0
  }

  def moveProjectLinksToHistory(projectId: Long): Unit = {
    sqlu"""INSERT INTO PROJECT_LINK_HISTORY (SELECT ID,
       PROJECT_ID, TRACK_CODE, DISCONTINUITY_TYPE, ROAD_NUMBER, ROAD_PART_NUMBER, START_ADDR_M,
       END_ADDR_M, CREATED_BY, MODIFIED_BY, CREATED_DATE, MODIFIED_DATE,
       STATUS, CALIBRATION_POINTS, ROAD_TYPE, SIDE_CODE, START_MEASURE, END_MEASURE, LINK_ID, ADJUSTED_TIMESTAMP, LINK_SOURCE
       FROM PROJECT_LINK WHERE PROJECT_ID = $projectId)""".execute
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

  def toTimeStamp(dateTime: Option[DateTime]) = {
    dateTime.map(dt => new Timestamp(dt.getMillis))
  }

  def uniqueName(projectId: Long, projectName: String): Boolean = {
    val query =
      s"""
         SELECT *
         FROM project
         WHERE UPPER(name)=UPPER('$projectName') and state<>7 and ROWNUM=1
       """
    val projects = Q.queryNA[Long](query).list
    projects.isEmpty || projects.contains(projectId)
  }

  implicit val getDiscontinuity = new GetResult[Option[Discontinuity]] {
    def apply(r: PositionedResult) = {
      r.nextLongOption().map(l => Discontinuity.apply(l))
    }
  }
}

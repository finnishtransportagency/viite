package fi.liikennevirasto.viite

import java.sql.SQLException
import java.util.Date
import fi.liikennevirasto.GeometryUtils
import fi.liikennevirasto.digiroad2._
import fi.liikennevirasto.digiroad2.asset.SideCode.{AgainstDigitizing, switch}
import fi.liikennevirasto.digiroad2.asset.{BoundingRectangle, LinkGeomSource, TrafficDirection, _}
import fi.liikennevirasto.digiroad2.client.vvh.VVHRoadlink
import fi.liikennevirasto.digiroad2.dao.Sequences
import fi.liikennevirasto.digiroad2.linearasset.{RoadLink, RoadLinkLike}
import fi.liikennevirasto.digiroad2.postgis.PostGISDatabase
import fi.liikennevirasto.digiroad2.service.RoadLinkService
import fi.liikennevirasto.digiroad2.util.LogUtils.time
import fi.liikennevirasto.digiroad2.util.{RoadAddressException, RoadPartReservedException, Track}
import fi.liikennevirasto.viite.dao.CalibrationPointDAO.CalibrationPointType.{JunctionPointCP, NoCP, UserDefinedCP}
import fi.liikennevirasto.viite.dao.Discontinuity.Continuous
import fi.liikennevirasto.viite.dao.LinkStatus._
import fi.liikennevirasto.viite.dao.ProjectCalibrationPointDAO.UserDefinedCalibrationPoint
import fi.liikennevirasto.viite.dao.ProjectState._
import fi.liikennevirasto.viite.dao.TerminationCode.{NoTermination, Termination}
import fi.liikennevirasto.viite.dao.{LinkStatus, ProjectDAO, RoadwayDAO, _}
import fi.liikennevirasto.viite.model.{ProjectAddressLink, RoadAddressLink}
import fi.liikennevirasto.viite.process.{InvalidAddressDataException, _}
import fi.liikennevirasto.viite.util.SplitOptions
import org.joda.time.DateTime
import org.joda.time.format.DateTimeFormat
import org.slf4j.LoggerFactory

import scala.collection.mutable.ListBuffer
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future}
import scala.util.control.NonFatal

// note, ChangeProject moved here from former ViiteTierekisteriClient at Tierekisteri removal (2021-09)
case class ChangeProject(id:Long,
                         name:String,
                         user:String,
                         changeDate:String,
                         changeInfoSeq:Seq[RoadwayChangeInfo]
                        )

sealed trait RoadNameSource {
  def value: Long

  def sourceName: String
}

object RoadNameSource {
  val values = Set(UnknownSource, ProjectLinkSource, RoadAddressSource)

  def apply(value: Long): RoadNameSource = {
    values.find(_.value == value).getOrElse(UnknownSource)
  }

  case object UnknownSource extends RoadNameSource {
    def value = 99

    def sourceName = "Unknown Source"
  }

  case object ProjectLinkSource extends RoadNameSource {
    def value = 0

    def sourceName = "Project Link Source"
  }

  case object RoadAddressSource extends RoadNameSource {
    def value = 1

    def sourceName = "Road Name Source"
  }

}

case class PreFillInfo(RoadNumber: BigInt, RoadPart: BigInt, roadName: String, roadNameSource: RoadNameSource)

case class LinkToRevert(id: Long, linkId: Long, status: Long, geometry: Seq[Point])

class ProjectService(roadAddressService: RoadAddressService, roadLinkService: RoadLinkService, nodesAndJunctionsService: NodesAndJunctionsService, roadwayDAO: RoadwayDAO,
                     roadwayPointDAO: RoadwayPointDAO, linearLocationDAO: LinearLocationDAO, projectDAO: ProjectDAO, projectLinkDAO: ProjectLinkDAO,
                     nodeDAO: NodeDAO, nodePointDAO: NodePointDAO, junctionPointDAO: JunctionPointDAO, projectReservedPartDAO: ProjectReservedPartDAO, roadwayChangesDAO: RoadwayChangesDAO,
                     roadwayAddressMapper: RoadwayAddressMapper,
                     eventbus: DigiroadEventBus, frozenTimeVVHAPIServiceEnabled: Boolean = false) {

  def withDynTransaction[T](f: => T): T = PostGISDatabase.withDynTransaction(f)

  def withDynSession[T](f: => T): T = PostGISDatabase.withDynSession(f)

  private val logger = LoggerFactory.getLogger(getClass)
  val projectValidator = new ProjectValidator
  val allowedSideCodes = List(SideCode.TowardsDigitizing, SideCode.AgainstDigitizing)
  val roadAddressLinkBuilder = new RoadAddressLinkBuilder(roadwayDAO, linearLocationDAO, projectLinkDAO)

  val roadNetworkDAO = new RoadNetworkDAO

  /**
    *
    * @param roadNumber    Road's number (long)
    * @param roadStartPart Starting part (long)
    * @param roadEndPart   Ending part (long)
    * @return Optional error message, None if no error
    */
  def checkRoadPartsExist(roadNumber: Long, roadStartPart: Long, roadEndPart: Long): Option[String] = {
    if (roadwayDAO.fetchAllByRoadAndPart(roadNumber, roadStartPart).isEmpty) {
      Some(ErrorStartingRoadPartNotFound)
    } else if (roadwayDAO.fetchAllByRoadAndPart(roadNumber, roadEndPart).isEmpty) {
      Some(ErrorEndingRoadPartNotFound)
    } else
      None
  }

  def calculateProjectCoordinates(projectId: Long): ProjectCoordinates = {
    val links = projectLinkDAO.fetchProjectLinks(projectId)
    if (links.nonEmpty) {
      val corners = GeometryUtils.boundingRectangleCorners(links.flatten(_.geometry))
      val centerX = (corners._1.x + corners._2.x) / 2
      val centerY = (corners._1.y + corners._2.y) / 2
      val (xLength, yLength) = (Math.abs(corners._2.x - corners._1.x), Math.abs(corners._2.y - corners._1.y))
      val zoom = Resolutions.map(r => {
        (xLength / r, yLength / r) match {
          case (x, y) if x < DefaultScreenWidth && y < DefaultScreenHeight => Resolutions.indexOf(r)
          case _ => 0
        }
      })
      ProjectCoordinates(centerX, centerY, zoom.max)
    } else {
      ProjectCoordinates(0, 0, 0)
    }
  }

  def saveProjectCoordinates(projectId: Long, coordinates: ProjectCoordinates): Unit = {
    projectDAO.updateProjectCoordinates(projectId, coordinates)
  }

  @deprecated ("Tierekisteri connection has been removed from Viite. TRId to be removed, too.")
  def fetchProjectInfoByTRId(trProjectId: Long): Option[Project] = {
    withDynTransaction {
      projectDAO.fetchByTRId(trProjectId)
    }
  }

  /**
    * Creates the new project
    * Adds the road addresses from the reserved parts to the project link table
    *
    */
  def fetchProjectById(projectId: Long, withNullElyFilter: Boolean = false): Option[Project] = {
    projectDAO.fetchById(projectId, withNullElyFilter).map { project =>

      val projectReservedRoadParts = projectReservedPartDAO.fetchProjectReservedRoadPartsByProjectId(projectId)
      val projectLinks = projectLinkDAO.fetchProjectLinks(projectId).filterNot(pl => List(LinkStatus.NotHandled, LinkStatus.Terminated).contains(pl.status)).groupBy(pl => (pl.roadNumber, pl.roadPartNumber))

      val reservedAndFormedParts: Seq[ProjectReservedPart] = projectReservedRoadParts.flatMap { rp =>
        val sortedAddresses: Seq[RoadAddress] = roadAddressService.getRoadAddressWithRoadAndPart(rp.roadNumber, rp.roadPartNumber, newTransaction = false).sortBy(_.startAddrMValue)
        val roadPartLinks = projectLinks.filter(pl => pl._1 == (rp.roadNumber, rp.roadPartNumber))

        //reservedParts
        val reserved: Seq[ProjectReservedPart] = if (sortedAddresses.nonEmpty) {
          val maxEly = sortedAddresses.map(_.ely).max
          val firstLink = sortedAddresses.head.linkId
          val maxDiscontinuity = sortedAddresses.last.discontinuity
          val maxEndAddr = sortedAddresses.last.endAddrMValue
          Seq(rp.copy(addressLength = Some(maxEndAddr), discontinuity = Some(maxDiscontinuity), ely = Some(maxEly), startingLinkId = Some(firstLink)))
        } else Seq()

        //formedParts
        val formed: Seq[ProjectReservedPart] = if (roadPartLinks.nonEmpty) {
          val sortedProjectLinks = roadPartLinks.head._2.sortBy(_.startAddrMValue)
          val maxEly = sortedProjectLinks.map(_.ely).max
          val firstLink = sortedProjectLinks.head.linkId
          val maxDiscontinuity = sortedProjectLinks.last.discontinuity
          val maxEndAddr = sortedProjectLinks.last.endAddrMValue
          Seq(rp.copy(newLength = Some(maxEndAddr), newDiscontinuity = Some(maxDiscontinuity), newEly = Some(maxEly), startingLinkId = Some(firstLink)))
        } else Seq()

        reserved ++ formed
      }
      val (foundReservedParts, foundFormedParts) = reservedAndFormedParts.partition(_.addressLength.nonEmpty)
      project.copy(reservedParts = foundReservedParts, formedParts = foundFormedParts)
    }
  }

  /**
    * Creates the new project
    * Adds the road addresses from the reserved parts to the project link table
    *
    * @param roadAddressProject
    * @return the created project
    */
  private def createProject(roadAddressProject: Project): Project = {
    val id = Sequences.nextViiteProjectId
    val project = roadAddressProject.copy(id = id)
    projectDAO.create(project)
    val error = addLinksToProject(project)
    if (error.nonEmpty)
      throw new RoadPartReservedException(error.get)
    fetchProjectById(id).get
  }

  private def projectFound(roadAddressProject: Project): Option[Project] = {
    val newRoadAddressProject = 0
    if (roadAddressProject.id == newRoadAddressProject) return None
    withDynTransaction {
      return fetchProjectById(roadAddressProject.id)
    }
  }

  def fetchPreFillFromVVH(linkId: Long, projectId: Long): Either[String, PreFillInfo] = {
    parsePreFillData(roadLinkService.getVVHRoadlinks(Set(linkId)), projectId)
  }

  def parsePreFillData(vvhRoadLinks: Seq[VVHRoadlink], projectId: Long = -1000): Either[String, PreFillInfo] = {
    withDynSession {
      if (vvhRoadLinks.isEmpty) {
        Left("Link could not be found in VVH")
      }
      else {
        val vvhLink = vvhRoadLinks.head
        (vvhLink.attributes.get("ROADNUMBER"), vvhLink.attributes.get("ROADPARTNUMBER")) match {
          case (Some(roadNumber: BigInt), Some(roadPartNumber: BigInt)) =>
            val preFilledRoadName =
              RoadNameDAO.getLatestRoadName(roadNumber.toLong) match {
                case Some(roadName) => PreFillInfo(roadNumber, roadPartNumber, roadName.roadName, RoadNameSource.RoadAddressSource)
                case _ => ProjectLinkNameDAO.get(roadNumber.toLong, projectId) match {
                  case Some(projectLinkName) => PreFillInfo(roadNumber, roadPartNumber, projectLinkName.roadName, RoadNameSource.ProjectLinkSource)
                  case _ => PreFillInfo(roadNumber, roadPartNumber, "", RoadNameSource.UnknownSource)
                }
              }
            Right(preFilledRoadName)
          case _ => Left("Link does not contain valid prefill info")
        }
      }
    }
  }

  def checkRoadPartsReservable(roadNumber: Long, startPart: Long, endPart: Long, projectId: Long): Either[String, (Seq[ProjectReservedPart], Seq[ProjectReservedPart])] = {
    (startPart to endPart).foreach { part =>
      projectReservedPartDAO.fetchProjectReservedPart(roadNumber, part) match {
        case Some(name) => return Left(s"Tie $roadNumber osa $part ei ole vapaana projektin alkupäivämääränä. Tieosoite on jo varattuna projektissa: $name.")
        case _ =>
      }
      val projectsWithCommonJunctions = projectReservedPartDAO.fetchProjectReservedJunctions(roadNumber, part, projectId)
      projectsWithCommonJunctions.headOption.map { _ =>
        return Left(s"Tie $roadNumber osa $part ei ole varattavissa, koska tämän tieosan liittymää/liittymiä käsitellään ${if(projectsWithCommonJunctions.size > 1) "projekteissa " else "projektissa "} ${projectsWithCommonJunctions.mkString(", ")}")
      }
    }
    val reserved: Seq[ProjectReservedPart] = (startPart to endPart).flatMap(part => getReservedAddressPartInfo(roadNumber, part))
    val formed: Seq[ProjectReservedPart] = (startPart to endPart).flatMap(part => getFormedAddressPartInfo(roadNumber, part))
    Right(
      (reserved, formed)
    )
  }

  /**
    * Validator method, this is in charge of evaluating if a combination of road number and road part number already exists in our roadway records.
    * If it does not then we check if this project is able to reserve the combination.
    * If the combination is already reserved in this project we simply return their parts, if not we validate the project date with the dates of the road parts.
    * If the validation of the date passes then we return these road parts.
    * IN ANY OTHER INSTANCE we return a error message detailing what the problem was
    *
    * @param roadNumber  : Long
    * @param startPart   : Long - road part number of the start of the reservation
    * @param endPart     : Long - road part number that ends the reservation
    * @param projectDate : DateTime
    * @return Either the error message or the reserved road parts.
    */
  def checkRoadPartExistsAndReservable(roadNumber: Long, startPart: Long, endPart: Long, projectDate: DateTime, projectId: Long): Either[String, (Seq[ProjectReservedPart], Seq[ProjectReservedPart])] = {
    withDynTransaction {
      checkRoadPartsExist(roadNumber, startPart, endPart) match {
        case None => checkRoadPartsReservable(roadNumber, startPart, endPart, projectId) match {
          case Left(err) => Left(err)
          case Right((reserved, formed)) =>
            if (reserved.isEmpty && formed.isEmpty) {
              Right(reserved, formed)
            } else {
              (validateProjectDate(reserved, projectDate), validateProjectDate(formed, projectDate)) match {
                case (Some(errMsg), _) => Left(errMsg)
                case (_, Some(errMsg)) => Left(errMsg)
                case (None, None) => Right(reserved, formed)
              }
            }
        }
        case Some(error) => Left(error)
      }
    }
  }

  def getRoadLinkDate(): String = {
    withDynSession {
      val timeInMillis = LinkDAO.fetchMaxAdjustedTimestamp()
      val retValue =
        """{ "result":" """ + new DateTime(timeInMillis).toString("dd.MM.yyyy HH:mm:ss") + """"}"""
      retValue
    }
  }

  /**
    *
    * @param projectId project's id
    * @return if state of the project is incomplete
    */

  def isWritableState(projectId: Long): Boolean = {
    projectWritableCheckInSession(projectId) match {
      case Some(_) => false
      case None => true
    }
  }

  /** @return None if the project is writable, or Some(&lt;errorstring&gt;) else. */
  def projectWritableCheckInSession(projectId: Long): Option[String] = {
    projectDAO.fetchProjectStatus(projectId) match {
      case Some(projectState) =>
        if (projectState == ProjectState.Incomplete || projectState == ProjectState.ErrorInViite)
          return None
        Some("Projektin tila ei ole keskeneräinen") //project state is not incomplete
      case None => Some("Projektia ei löytynyt") //project could not be found
    }
  }

  def projectWritableCheck(projectId: Long): Option[String] = {
    withDynSession {
      projectWritableCheckInSession(projectId)
    }
  }

  /**
    * Validation of the start and end dates of the project when compared with those in the roads.
    * The start date of the roadways need to exist and be before the project date, same as the end date.
    *
    * @param reservedParts -Sequence of ProjectReservedParts
    * @param date          : DateTime -  Project Date
    * @return Either an error message or nothing
    */
  def validateProjectDate(reservedParts: Seq[ProjectReservedPart], date: DateTime): Option[String] = {
    // TODO If RoadwayDAO.getRoadPartInfo would return Option[RoadPartInfo], we could use the named attributes instead of these numbers
    reservedParts.map(rp => (rp.roadNumber, rp.roadPartNumber) -> roadwayDAO.getRoadPartInfo(rp.roadNumber, rp.roadPartNumber)).toMap.
      filterNot(_._2.isEmpty).foreach {
      case ((roadNumber, roadPartNumber), value) =>
        val (startDate, endDate) = value.map(v => (v._6, v._7)).get
        if (startDate.nonEmpty && startDate.get.isAfter(date))
          return Option(s"Tieosalla TIE $roadNumber OSA $roadPartNumber alkupäivämäärä " +
            s"${startDate.get.toString("dd.MM.yyyy")} on myöhempi kuin tieosoiteprojektin alkupäivämäärä " +
            s"${date.toString("dd.MM.yyyy")}, tarkista tiedot.")
        if (endDate.nonEmpty && endDate.get.isAfter(date))
          return Option(s"Tieosalla TIE $roadNumber OSA $roadPartNumber loppupäivämäärä " +
            s"${endDate.get.toString("dd.MM.yyyy")} on myöhempi kuin tieosoiteprojektin alkupäivämäärä " +
            s"${date.toString("dd.MM.yyyy")}, tarkista tiedot.")
    }
    None
  }

  private def getReservedAddressPartInfo(roadNumber: Long, roadPart: Long): Option[ProjectReservedPart] = {
    projectReservedPartDAO.fetchReservedRoadPart(roadNumber, roadPart).orElse(generateAddressPartInfo(roadNumber, roadPart))
  }

  private def getFormedAddressPartInfo(roadNumber: Long, roadPart: Long): Option[ProjectReservedPart] = {
    projectReservedPartDAO.fetchFormedRoadPart(roadNumber, roadPart).orElse(None)
  }


  private def generateAddressPartInfo(roadNumber: Long, roadPart: Long): Option[ProjectReservedPart] = {
    roadwayDAO.getRoadPartInfo(roadNumber, roadPart).map {
      case (_, linkId, addrLength, discontinuity, ely, _, _) =>
        ProjectReservedPart(0L, roadNumber, roadPart, Some(addrLength), Some(Discontinuity.apply(discontinuity.toInt)), Some(ely),
          newLength = Some(addrLength), newDiscontinuity = Some(Discontinuity.apply(discontinuity.toInt)), newEly = Some(ely), Some(linkId))
    }
  }

  private def sortRamps(seq: Seq[ProjectLink], linkIds: Seq[Long]): Seq[ProjectLink] = {
    if (seq.headOption.exists(isRamp))
      seq.find(l => linkIds.headOption.contains(l.linkId)).toSeq ++ seq.filter(_.linkId != linkIds.headOption.getOrElse(0L))
    else
      seq
  }

  def setProjectRoadName(projectId: Long, roadNumber: Long, roadName: String): Option[String] = {
    (ProjectLinkNameDAO.get(roadNumber, projectId), RoadNameDAO.getLatestRoadName(roadNumber), roadName != null && roadName.trim.nonEmpty, roadNumber <= MaxRoadNumberDemandingRoadName) match {
      case (Some(projectLinkName), None, true, _) => ProjectLinkNameDAO.update(projectLinkName.id, roadName)
        None
      case (None, None, true, _) => ProjectLinkNameDAO.create(projectId, roadNumber, roadName)
        None
      case (None, Some(existingRoadName), _, _) => ProjectLinkNameDAO.create(projectId, roadNumber, existingRoadName.roadName)
        None
      case (_, _, false, true) =>
        Some(ErrorMaxRoadNumberDemandingRoadNameMessage)
      case (Some(_), None, false, false) => ProjectLinkNameDAO.revert(roadNumber, projectId)
        None
      case _ => None
    }
  }

  def writableWithValidTrack(projectId: Long, track: Int): Option[String] = {
    if (!isWritableState(projectId)) Some(ProjectNotWritable)
    else if (!validateLinkTrack(track)) Some("Ajoratakoodi puuttuu")
    else None
  }

  def createProjectLinks(linkIds: Seq[Long], projectId: Long, roadNumber: Long, roadPartNumber: Long, track: Track, discontinuity: Discontinuity, administrativeClass: AdministrativeClass, roadLinkSource: LinkGeomSource, roadEly: Long, user: String, roadName: String, coordinates: Option[ProjectCoordinates] = None): Map[String, Any] = {
    withDynSession {
      writableWithValidTrack(projectId, track.value) match {
        case None =>
          val linkId = linkIds.head
          val roadLinks = roadLinkService.getRoadLinksByLinkIdsFromVVH(linkIds.toSet).map(l => l.linkId -> l).toMap
          if (roadLinks.keySet != linkIds.toSet)
            return Map("success" -> false,
              "errorMessage" -> (linkIds.toSet -- roadLinks.keySet).mkString(ErrorRoadLinkNotFound + " puuttuvat id:t ", ", ", ""))
          val project = fetchProjectById(projectId).getOrElse(throw new RuntimeException(s"Missing project $projectId"))
          val existingProjectLinks = projectLinkDAO.fetchByProjectRoadPart(roadNumber, roadPartNumber, projectId)
          val reversed = if (existingProjectLinks.nonEmpty) existingProjectLinks.forall(_.reversed) else false

          var projectLinks: Seq[ProjectLink] = linkIds.toSet.map { id: Long =>
            /* Set calibration point and start and end addresses for UI. */
            val connectedProjectlink = existingProjectLinks.filterNot(_.status == LinkStatus.Terminated).find(pl => roadLinks(id).geometry.exists(rl_point => pl.connected(rl_point)))
            val connectedStartProjectlink = existingProjectLinks.filterNot(pl => Seq(LinkStatus.Terminated, LinkStatus.New).contains(pl.status)).find(pl => roadLinks(id).geometry.exists(rl_point => GeometryUtils.areAdjacent(rl_point, if (pl.sideCode == AgainstDigitizing) pl.geometry.head else pl.geometry.last, fi.liikennevirasto.viite.MaxDistanceForConnectedLinks)))
            val connectedEndProjectlink = existingProjectLinks.filterNot(pl => Seq(LinkStatus.Terminated, LinkStatus.New).contains(pl.status)).find(pl => roadLinks(id).geometry.exists(rl_point => GeometryUtils.areAdjacent(rl_point, if (pl.sideCode == AgainstDigitizing) pl.geometry.last else pl.geometry.head, fi.liikennevirasto.viite.MaxDistanceForConnectedLinks)))
            var newPl = newProjectLink(roadLinks(id), project, roadNumber, roadPartNumber, track, Continuous, administrativeClass, roadEly, roadName, reversed)
            val connectedEnd = if (connectedStartProjectlink.isDefined) connectedStartProjectlink else if (connectedEndProjectlink.isDefined) connectedEndProjectlink else None

            val sc = if (connectedEnd.isDefined && connectedEnd.get.endPoint.connected(newPl.geometry.head) || (connectedEnd.isDefined && connectedEnd.get.startingPoint.connected(newPl.geometry.last))) {
              if (connectedEnd.get.sideCode == SideCode.TowardsDigitizing) Some(connectedEnd.get.sideCode) else Some(switch(connectedEnd.get.sideCode))
            }
            else
              if (connectedEnd.isDefined)
                if (connectedEnd.get.sideCode == SideCode.TowardsDigitizing)
                  Some(switch(connectedEnd.get.sideCode))
                else Some(connectedEnd.get.sideCode)
              else
                None

            newPl = if (connectedStartProjectlink.isDefined)
              newPl.copy(startAddrMValue = connectedStartProjectlink.get.endAddrMValue, endAddrMValue = connectedStartProjectlink.get.endAddrMValue + newPl.geometryLength.toInt, sideCode = sc.get)
            else
              newPl

            newPl = if (connectedEndProjectlink.isDefined && !connectedStartProjectlink.isDefined)
                      newPl.copy(startAddrMValue = connectedEndProjectlink.get.startAddrMValue - newPl.geometryLength.toInt, endAddrMValue = connectedEndProjectlink.get.startAddrMValue, sideCode = sc.get)
                  else if (connectedEndProjectlink.isDefined)
                      newPl.copy(endAddrMValue = connectedEndProjectlink.get.startAddrMValue, sideCode = sc.get)
                    else
                      newPl

            if (connectedProjectlink.isDefined && connectedProjectlink.get.hasCalibrationPointAtEnd) {
              newPl.copy(calibrationPointTypes = (connectedProjectlink.get.endCalibrationPoint.get.typeCode, CalibrationPointDAO.CalibrationPointType.NoCP))
            } else if (connectedEndProjectlink.isDefined && connectedEndProjectlink.get.hasCalibrationPointAtStart) {
              newPl.copy(calibrationPointTypes = (CalibrationPointDAO.CalibrationPointType.NoCP,connectedEndProjectlink.get.startCalibrationPoint.get.typeCode))
            } else {
//            newPl

            if (newPl.calibrationPointTypes._2 == NoCP) newPl.copy(calibrationPointTypes = (newPl.calibrationPointTypes._1, CalibrationPointDAO.CalibrationPointType.UserDefinedCP)) else newPl
            }
          }.toSeq

          val endPoints = TrackSectionOrder.findChainEndpoints(projectLinks)
          val mappedEndpoints = (endPoints.head._1, endPoints.head._1)
          val orderedPairs = TrackSectionOrder.orderProjectLinksTopologyByGeometry(mappedEndpoints, projectLinks)
          projectLinks =  if (projectLinks.exists(_.track == Track.RightSide || projectLinks.forall(_.track == Track.Combined))) orderedPairs._1 else orderedPairs._2

          // update continuous projectlinks start and end values for validation.
//          projectLinks = projectLinks.sortBy(-_.startAddrMValue)
          projectLinks = projectLinks.tail.scan(projectLinks.head) { (l: ProjectLink, pl: ProjectLink) => {
            if (pl.endAddrMValue == 0)
              pl.copy(startAddrMValue = l.endAddrMValue, endAddrMValue = (l.endAddrMValue + pl.geometryLength.toInt), sideCode = l.sideCode)
            else
              pl.copy(startAddrMValue = l.endAddrMValue, sideCode = l.sideCode)
          }
          }

          if (isConnectedtoOtherProjects(projectId, projectLinks)) {
            Map("success" -> false, "errorMessage" -> ErrorWithNewAction)
          } else {
            if (coordinates.isDefined) {
              saveProjectCoordinates(project.id, coordinates.get)
            }
            else {
              saveProjectCoordinates(project.id, calculateProjectCoordinates(project.id))
            }
            addNewLinksToProject(sortRamps(projectLinks, linkIds), projectId, user, linkId, newTransaction = false, discontinuity) match {
              case Some(errorMessage) => {
                Map("success" -> false, "errorMessage" -> errorMessage)
              }
              case None => {
                Map("success" -> true, "projectErrors" -> validateProjectByIdHighPriorityOnly(projectId, newSession = false))
              }
            }
          }
        case Some(error) => Map("success" -> false, "errorMessage" -> error)
      }
    }
  }

  def isConnectedtoOtherProjects(projectId: Long, projectLinks: Seq[ProjectLink]): Boolean = {
    val otherProjectLinks = projectLinkDAO.getOtherProjectLinks(projectId)
    var isConnectedLinks = false
    var junctionId = Option(0L)
    otherProjectLinks.foreach(pl => {
      val aPointFirst = pl.geometry.head
      val aPointLast = pl.geometry.last
      projectLinks.foreach(pln => {
        val bPointFirst = pln.geometry.head
        val bPointLast = pln.geometry.last
        if (aPointFirst.connected(bPointFirst) || aPointFirst.connected(bPointLast)
          || aPointLast.connected(bPointFirst) || aPointLast.connected(bPointLast)) {
          isConnectedLinks = true
          junctionId = junctionPointDAO.fetchByMultipleRoadwayPoints(pl.roadwayNumber, pl.startAddrMValue, pl.endAddrMValue).map(_.junctionId)
        }
      })
    })
    isConnectedLinks && junctionId == None
  }

  def addNewLinksToProject(newLinks: Seq[ProjectLink], projectId: Long, user: String, firstLinkId: Long, newTransaction: Boolean = true, discontinuity: Discontinuity): Option[String] = {
    if (newTransaction)
      withDynTransaction {
        addNewLinksToProjectInTX(newLinks, projectId, user, firstLinkId, discontinuity)
      }
    else
      addNewLinksToProjectInTX(newLinks, projectId, user, firstLinkId, discontinuity)
  }

  /**
    * Used when adding road address that do not have a previous address
    */
  private def addNewLinksToProjectInTX(newLinks: Seq[ProjectLink], projectId: Long, user: String, firstLinkId: Long, discontinuity: Discontinuity): Option[String] = {
    val newRoadNumber = newLinks.head.roadNumber
    val newRoadPartNumber = newLinks.head.roadPartNumber
    val linkStatus = newLinks.head.status
    try {
      val project = getProjectWithReservationChecks(projectId, newRoadNumber, newRoadPartNumber, linkStatus, newLinks)

      if (GeometryUtils.isNonLinear(newLinks))
        throw new ProjectValidationException(ErrorGeometryContainsBranches)

      if (!project.isReserved(newRoadNumber, newRoadPartNumber) && !project.isFormed(newRoadNumber, newRoadPartNumber))
        projectReservedPartDAO.reserveRoadPart(project.id, newRoadNumber, newRoadPartNumber, project.modifiedBy)
      // Determine address value scheme (ramp, roundabout, all others)

      val existingLinks = projectLinkDAO.fetchByProjectRoadPart(newRoadNumber, newRoadPartNumber, projectId)
      val createLinks =
        if (newLinks.headOption.exists(isRamp)) {
          logger.info("Added links recognized to be in ramp category")
          if (TrackSectionOrder.isRoundabout(newLinks)) {
            logger.info("Added links recognized to be a roundabout - using roundabout addressing scheme")
            val ordered = newLinks.partition(_.linkId == firstLinkId)
            val created = TrackSectionOrder.mValueRoundabout(ordered._1 ++ ordered._2)
            val endingM = created.map(_.endAddrMValue).max
            created.map(pl =>
              if (pl.endAddrMValue == endingM && endingM > 0)
                pl.copy(discontinuity = Discontinuity.EndOfRoad)
              else
                pl.copy(discontinuity = Discontinuity.Continuous))
          } else {
            val existingLinks = projectLinkDAO.fetchByProjectRoadPart(newRoadNumber, newRoadPartNumber, projectId)
            fillRampGrowthDirection(newLinks.map(_.linkId).toSet, newRoadNumber, newRoadPartNumber, newLinks,
              firstLinkId, existingLinks)
          }
        } else {
          /* Set discontinuity to the last new link if not continuous.
           * Finds the link by assuming the end is not connected, i.e. before round about. */
          if (discontinuity != Discontinuity.Continuous) {
            val existingLinksGeoms = existingLinks.map(nl => (nl.geometry.head, nl.geometry.last))
            val onceConnectedNewLinks = TrackSectionOrder.findOnceConnectedLinks(newLinks)
            val endLinkOfNewLinks = onceConnectedNewLinks.filterNot(ep => existingLinksGeoms.exists(el => ep._1.connected(el._1) || ep._1.connected(el._2))).map(_._2).toList
            if (endLinkOfNewLinks.distinct.size == 1) {
              newLinks.filterNot(_.equals(endLinkOfNewLinks.head)) :+ endLinkOfNewLinks.head.copy(discontinuity = discontinuity)
            }
            else
              newLinks
          }
          else
          newLinks
        }

      projectLinkDAO.create(createLinks.map(_.copy(createdBy = Some(user))))
      newLinks.flatMap(_.roadName).headOption.flatMap(setProjectRoadName(projectId, newRoadNumber, _)).toList.headOption
    } catch {
      case ex: ProjectValidationException => Some(ex.getMessage)
    }
  }

  /**
    * Will attempt to find relevant sideCode information to the projectLinks given a number of factors
    * for example if they are of suravage or complementary origin
    *
    * @param linkIds        the linkIds to process
    * @param roadNumber     the roadNumber to apply/was applied to said linkIds
    * @param roadPartNumber the roadPartNumber to apply/was applied to said linkIds
    * @param newLinks       new project links for this ramp
    * @return the projectLinks with a assigned SideCode
    */
  private def fillRampGrowthDirection(linkIds: Set[Long], roadNumber: Long, roadPartNumber: Long,
                                      newLinks: Seq[ProjectLink], firstLinkId: Long, existingLinks: Seq[ProjectLink]): Seq[ProjectLink] = {
    if (newLinks.exists(nl => existingLinks.exists(pl => pl.status != LinkStatus.Terminated &&
      GeometryUtils.areAdjacent(pl.geometry, nl.geometry)))) {
      // Connected to existing geometry -> let the track section calculation take it's natural course
      newLinks.map(_.copy(sideCode = SideCode.Unknown))
    } else {
      val roadLinks = roadLinkService.fetchVVHRoadLinksAndComplementaryFromVVH(linkIds)
      //Set the sideCode as defined by the trafficDirection
      val sideCode = roadLinks.map(rl => rl.linkId -> (rl.trafficDirection match {
        case TrafficDirection.AgainstDigitizing => SideCode.AgainstDigitizing
        case TrafficDirection.TowardsDigitizing => SideCode.TowardsDigitizing
        case _ => SideCode.Unknown
      })).toMap
      newLinks.map(nl => nl.copy(sideCode = sideCode.getOrElse(nl.linkId, SideCode.Unknown)))
    }
  }

  def getFirstProjectLink(project: Project): Option[ProjectLink] = {
    project.reservedParts.find(_.startingLinkId.nonEmpty) match {
      case Some(rrp) =>
        withDynSession {
          projectLinkDAO.fetchFirstLink(project.id, rrp.roadNumber, rrp.roadPartNumber)
        }
      case _ => None
    }
  }

  /**
    * Main method of reversing the direction of a already created project link.
    * 1st check if the project is writable in the current session, if it is then we check if there still are project links that are unchanged of unhandled, if there are none then the process continues by getting all the discontinuities of all project links.
    * After that we run the query to reverse the directions, after it's execution we re-fetch the project links (minus the terminated ones) and the original information of the roads.
    * Using said information we run an all project links of that project to update the "reversed" tag when relative to the side codes of the original roadways.
    * To finalize we remove all the calibration points, we run the recalculate (which will regenerate calibration points when needed) and update the project coordinates for the UI to jump to when opened.
    *
    * @param projectId      : Long - project id
    * @param roadNumber     : Long - roadway number
    * @param roadPartNumber : Long - roadway part number
    * @param links          : Sequence of project links - Project links targeted to reverse
    * @param coordinates    : ProjectCoordinates - Coordinates for the project to jump to.
    * @param username       : Sting - User
    * @return
    */
  def changeDirection(projectId: Long, roadNumber: Long, roadPartNumber: Long, links: Seq[LinkToRevert], coordinates: ProjectCoordinates, username: String): Option[String] = {
    roadAddressLinkBuilder.municipalityRoadMaintainerMapping // make sure it is populated outside of this TX
    try {
      withDynTransaction {
        projectWritableCheckInSession(projectId) match {
          case None =>
            if (projectLinkDAO.countLinksByStatus(projectId, roadNumber, roadPartNumber, Set(UnChanged.value, NotHandled.value)) > 0)
              return Some(ErrorReversingUnchangedLinks)
            val continuity = projectLinkDAO.getProjectLinksContinuityCodes(projectId, roadNumber, roadPartNumber)
            val newContinuity: Map[Long, Discontinuity] = if (continuity.nonEmpty) {
              val discontinuityAtEnd = continuity.maxBy(_._1)
              continuity.filterKeys(_ < discontinuityAtEnd._1).map { case (addr, d) => (discontinuityAtEnd._1 - addr) -> d } ++
                Map(discontinuityAtEnd._1 -> discontinuityAtEnd._2)
            } else
              Map()
            projectLinkDAO.reverseRoadPartDirection(projectId, roadNumber, roadPartNumber)
            val projectLinks = projectLinkDAO.fetchProjectLinks(projectId).filter(pl => {
              pl.status != LinkStatus.Terminated && pl.roadNumber == roadNumber && pl.roadPartNumber == roadPartNumber
            })
            val originalSideCodes = linearLocationDAO.fetchByRoadways(projectLinks.map(_.roadwayNumber).toSet)
              .map(l => l.id -> l.sideCode).toMap
            val originalAddresses = roadAddressService.getRoadAddressesByRoadwayIds(projectLinks.map(_.roadwayId))
            projectLinkDAO.updateProjectLinks(projectLinks.map(x =>
              x.copy(discontinuity = newContinuity.getOrElse(x.endAddrMValue, Discontinuity.Continuous), reversed = isReversed(originalSideCodes)(x))), username, originalAddresses)
            ProjectCalibrationPointDAO.removeAllCalibrationPoints(projectLinks.map(_.id).toSet)
            saveProjectCoordinates(projectId, coordinates)
            None
          case Some(error) => Some(error)
        }
      }
    } catch {
      case NonFatal(e) =>
        logger.info("Direction change failed", e)
        Some(ErrorSavingFailed)
    }
  }

  private def isReversed(originalSideCodes: Map[Long, SideCode])(projectLink: ProjectLink): Boolean = {
    originalSideCodes.get(projectLink.linearLocationId) match {
      case Some(sideCode) => sideCode != projectLink.sideCode
      case _ => projectLink.reversed
    }
  }

  /**
    * Checks if the road part that user wants to reserve exists
    *
    * @param reservedRoadParts
    * @param projectLinks
    * @param roadways
    * @return None in case of success, error code in case of failed validation
    */

  def validateReservations(reservedRoadParts: ProjectReservedPart, projectLinks: Seq[ProjectLink], roadways: Seq[Roadway]): Option[String] = {
    if (roadways.isEmpty && projectLinks.forall(_.status == LinkStatus.New) && !projectLinks.exists(pl => pl.roadNumber == reservedRoadParts.roadNumber && pl.roadPartNumber == reservedRoadParts.roadPartNumber))
      Some(s"$ErrorFollowingRoadPartsNotFoundInDB TIE ${reservedRoadParts.roadNumber} OSA: ${reservedRoadParts.roadPartNumber}")
    else
      None
  }

  /**
    * Adds reserved road links (from road parts) to a road address project. Clears
    * project links that are no longer reserved for the project. Reservability is check before this.
    * for each reserved part get all roadways
    * validate if the road exists on the roadway table and if there isn't different ely codes reserved
    * in case there is, throw roadPartReserved exception
    * get the road links from the suravage and from the regular interface
    * map the road links into road address objects
    * check, make the reservation and update the ely code of the project
    * map the addresses into project links
    * insert the new project links and remove the ones that were unreserved
    */
  private def addLinksToProject(project: Project): Option[String] = {
    logger.info(s"Adding reserved road parts with links to project ${project.id}")
    val projectLinks = projectLinkDAO.fetchProjectLinks(project.id)
    logger.debug(s"${projectLinks.size} links fetched")
    val projectLinkOriginalParts = if (projectLinks.nonEmpty) roadwayDAO.fetchAllByRoadwayId(projectLinks.map(_.roadwayId)).map(ra => (ra.roadNumber, ra.roadPartNumber)) else Seq()
    val newProjectLinks = project.reservedParts.filterNot(res =>
      projectLinkOriginalParts.contains((res.roadNumber, res.roadPartNumber))).flatMap {
      reserved => {
        val roadways = roadwayDAO.fetchAllBySection(reserved.roadNumber, reserved.roadPartNumber)
        validateReservations(reserved, projectLinks, roadways) match {
          case Some(error) => throw new RoadPartReservedException(error)
          case _ =>
            val roadwaysByLinkSource = linearLocationDAO.fetchByRoadways(roadways.map(_.roadwayNumber).toSet).groupBy(_.linkGeomSource)
            val regularLinkSource = if (frozenTimeVVHAPIServiceEnabled) LinkGeomSource.FrozenLinkInterface else LinkGeomSource.NormalLinkInterface
            val regular = if (roadwaysByLinkSource.contains(regularLinkSource)) roadwaysByLinkSource(regularLinkSource) else Seq()
            val complementary = if (roadwaysByLinkSource.contains(LinkGeomSource.ComplementaryLinkInterface)) roadwaysByLinkSource(LinkGeomSource.ComplementaryLinkInterface) else Seq()
            if (complementary.nonEmpty) {
              logger.debug(s"Adding ${complementary.size} complementary links in project.")
            }
            val regularMapping = roadLinkService.getRoadLinksByLinkIdsFromVVH(regular.map(_.linkId).toSet).map(rm => rm.linkId -> rm).toMap
            val complementaryMapping = roadLinkService.getRoadLinksByLinkIdsFromVVH(complementary.map(_.linkId).toSet).map(rm => rm.linkId -> rm).toMap
            val fullMapping = regularMapping ++ complementaryMapping
            val addresses = roadways.flatMap(r =>
              roadwayAddressMapper.mapRoadAddresses(r, (regular ++ complementary).groupBy(_.roadwayNumber).getOrElse(r.roadwayNumber, {
                logger.error(s"Failed to add links to the project. No links found with roadway number ${r.roadwayNumber}. Reserved parts were: ${project.reservedParts.map(r => s"(road number: ${r.roadNumber}, road part number: ${r.roadPartNumber})").mkString(", ")}")
                throw new RoadAddressException(s"Linkkien lisääminen projektiin epäonnistui Viitteen sisäisen virheen vuoksi. Ota yhteyttä ylläpitoon.")
              })))
            checkAndReserve(project, reserved)
            logger.debug(s"Reserve done")

            // remove uses old links.
//            val test2 = addresses.map(_.linkId).diff(fullMapping.keys.toSeq)
//
//            val test = roadLinkService.getCurrentAndHistoryRoadLinksFromVVH(test2.toSet)
//            val maps = test._2.map(t => (t.linkId -> t)).toMap
//            val fullMapping2 = fullMapping ++ maps
//              addresses.map(ra => newProjectTemplate(fullMapping2(ra.linkId), ra, project))
            addresses.map(ra => newProjectTemplate(fullMapping(ra.linkId), ra, project))
        }
      }
    }

    projectLinkDAO.create(newProjectLinks.filterNot(ad => projectLinks.exists(pl => pl.roadAddressRoadNumber.getOrElse(pl.roadNumber) == ad.roadNumber && pl.roadAddressRoadPart.getOrElse(pl.roadPartNumber) == ad.roadPartNumber)))
    logger.debug(s"New links created ${newProjectLinks.size}")
    val linksOnRemovedParts = projectLinks.filterNot(pl => (project.reservedParts ++ project.formedParts).exists(_.holds(pl)))
    roadwayChangesDAO.clearRoadChangeTable(project.id)
    projectLinkDAO.removeProjectLinksById(linksOnRemovedParts.map(_.id).toSet)
    None
  }

  /**
    * Fetches the projectLink name, first from the project link, if that's not available then search for the road address.
    *
    * @param projectLink
    * @return
    */
  def fillRoadNames(projectLink: ProjectLink): ProjectLink = {
    val projectLinkName = ProjectLinkNameDAO.get(projectLink.roadNumber, projectLink.projectId).map(_.roadName)
      .getOrElse(RoadNameDAO.getLatestRoadName(projectLink.roadNumber).map(_.roadName).getOrElse(projectLink.roadName.get))
    projectLink.copy(roadName = Option(projectLinkName))
  }

  /**
    * Save road link project, reserve new road parts, free previously reserved road parts that were removed
    *
    * @param roadAddressProject Updated road address project case class
    * @return Updated project reloaded from the database
    */
  def saveProject(roadAddressProject: Project): Project = {
    if (projectFound(roadAddressProject).isEmpty)
      throw new IllegalArgumentException("Project not found")
    withDynTransaction {
      if (isWritableState(roadAddressProject.id)) {
        validateProjectDate(roadAddressProject.reservedParts, roadAddressProject.startDate) match {
          case Some(errMsg) => throw new IllegalStateException(errMsg)
          case None =>
            if (projectDAO.isUniqueName(roadAddressProject.id, roadAddressProject.name)) {
              projectDAO.update(roadAddressProject)
              val storedProject = fetchProjectById(roadAddressProject.id).get

              val removedReservation = storedProject.reservedParts.filterNot(part =>
                roadAddressProject.reservedParts.exists(rp => rp.roadPartNumber == part.roadPartNumber &&
                  rp.roadNumber == part.roadNumber))

              val removedFormed = storedProject.formedParts.filterNot(part =>
                roadAddressProject.formedParts.exists(rp => rp.roadPartNumber == part.roadPartNumber &&
                  rp.roadNumber == part.roadNumber))

              (removedReservation ++ removedFormed).foreach(p => projectReservedPartDAO.removeReservedRoadPartAndChanges(roadAddressProject.id, p.roadNumber, p.roadPartNumber))
              (removedReservation ++ removedFormed).groupBy(_.roadNumber).keys.foreach(ProjectLinkNameDAO.revert(_, roadAddressProject.id))
              addLinksToProject(roadAddressProject)
              val savedProject = fetchProjectById(roadAddressProject.id).get
              saveProjectCoordinates(savedProject.id, calculateProjectCoordinates(savedProject.id))
              savedProject
            } else {
              throw new NameExistsException(s"Nimellä ${roadAddressProject.name} on jo olemassa projekti. Muuta nimeä.")
            }
        }
      } else {
        throw new IllegalStateException(ProjectNotWritable)
      }
    }
  }

  /**
    * Delete road link project, if it exists and the state is Incomplete
    *
    * @param projectId Id of the project to delete
    * @return boolean that confirms if the project is deleted
    */
  def deleteProject(projectId: Long): Boolean = {
    withDynTransaction {
      val project = fetchProjectById(projectId)
      val canBeDeleted = projectId != 0 && project.isDefined && project.get.status == ProjectState.Incomplete
      if (canBeDeleted) {
        val links = projectLinkDAO.fetchProjectLinks(projectId)
        projectLinkDAO.removeProjectLinksByProject(projectId)
        projectReservedPartDAO.removeReservedRoadPartsByProject(projectId)
        links.groupBy(_.roadNumber).keys.foreach(ProjectLinkNameDAO.revert(_, projectId))
        projectDAO.updateProjectStatus(projectId, ProjectState.Deleted)
        projectDAO.updateProjectStateInfo(ProjectState.Deleted.description, projectId)
      }
      canBeDeleted
    }
  }

  def createRoadLinkProject(roadAddressProject: Project): Project = {
    if (roadAddressProject.id != 0)
      throw new IllegalArgumentException(s"Road address project to create has an id ${roadAddressProject.id}")
    withDynTransaction {
      if (projectDAO.isUniqueName(roadAddressProject.id, roadAddressProject.name)) {
        val savedProject = createProject(roadAddressProject)
        saveProjectCoordinates(savedProject.id, calculateProjectCoordinates(savedProject.id))
        savedProject
      } else {
        throw new NameExistsException(s"Nimellä ${roadAddressProject.name} on jo olemassa projekti. Muuta nimeä.")
      }
    }
  }

  def getSingleProjectById(projectId: Long): Option[Project] = {
    withDynSession {
      fetchProjectById(projectId)
    }
  }

  def getAllProjects: Seq[Project] = {
    withDynSession {
      projectDAO.fetchAll()
    }
  }

  def updateProjectLinkGeometry(projectId: Long, username: String, onlyNotHandled: Boolean = false): Unit = {
    withDynTransaction {
      val projectLinks = projectLinkDAO.fetchProjectLinks(projectId, if (onlyNotHandled) Some(LinkStatus.NotHandled) else None)
      val roadLinks = roadLinkService.getCurrentAndComplementaryVVHRoadLinks(projectLinks.filter(x => x.linkGeomSource == LinkGeomSource.NormalLinkInterface
        || x.linkGeomSource == LinkGeomSource.FrozenLinkInterface || x.linkGeomSource == LinkGeomSource.ComplementaryLinkInterface).map(x => x.linkId).toSet)
      val vvhLinks = roadLinks
      val geometryMap = vvhLinks.map(l => l.linkId -> (l.geometry, l.vvhTimeStamp)).toMap
      val timeStamp = new Date().getTime
      val updatedProjectLinks = projectLinks.map { pl =>
        val (geometry, time) = geometryMap.getOrElse(pl.linkId, (Seq(), timeStamp))
        pl.copy(geometry = GeometryUtils.truncateGeometry2D(geometry, pl.startMValue, pl.endMValue), linkGeometryTimeStamp = time)
      }
      projectLinkDAO.updateProjectLinksGeometry(updatedProjectLinks, username)
    }
  }

  /**
    * Check that road part is available for reservation and return the id of reserved road part table row.
    * Reservation must contain road number and road part number, other data is not used or saved.
    *
    * @param project          Project for which to reserve (or for which it is already reserved)
    * @param reservedRoadPart Reservation information (req: road number, road part number)
    * @return
    */
  private def checkAndReserve(project: Project, reservedRoadPart: ProjectReservedPart): Unit = {
    //if part not completely reserved and not pseudo reserved in current project, then it can be reserved
    val currentReservedPart = (projectReservedPartDAO.fetchProjectReservedPart(reservedRoadPart.roadNumber, reservedRoadPart.roadPartNumber), projectReservedPartDAO.roadPartReservedTo(reservedRoadPart.roadNumber, reservedRoadPart.roadPartNumber, projectId = project.id, withProjectId = true))
    logger.info(s"Check ${project.id} matching to " + currentReservedPart)
    currentReservedPart match {
      case (None, None) => projectReservedPartDAO.reserveRoadPart(project.id, reservedRoadPart.roadNumber, reservedRoadPart.roadPartNumber, project.modifiedBy)
      case _ =>
    }
  }

  def getProjectLinks(projectId: Long): Seq[ProjectLink] = {
    withDynTransaction {
      projectLinkDAO.fetchProjectLinks(projectId)
    }
  }

  /**
    * This method will not be used now because we don't want to show suravage in project mode. It will be used in future.
    * It will execute a search by bounding box to find both the suravage links and project links contained in them, following that it will filter out all suravages that do not have a match with the project links.
    * With the previous results we just run them through the builder and output the result.
    *
    * @param roadAddressService : RoadAddressService - The road address service
    * @param projectId          : Long - The active project id
    * @param boundingRectangle  : BoundingRectangle - Search rectangle defined by 2 point.
    * @param roadNumberLimits   : Seq(Int, Int) - Defines the upper and lower limits of the road number that can be retrived.
    * @param municipalities     : Seq(Int) - Defines from what municipalities we fetch infomration.
    * @param everything         : Boolean - Used in the filter
    * @param publicRoads        : Boolean - Used in the filter
    * @return
    */
  def getProjectLinks(roadAddressService: RoadAddressService, projectId: Long, boundingRectangle: BoundingRectangle,
                      roadNumberLimits: Seq[(Int, Int)], municipalities: Set[Int], everything: Boolean = false,
                      publicRoads: Boolean = false): Seq[ProjectAddressLink] = {
    val fetch = fetchBoundingBoxF(boundingRectangle, projectId, roadNumberLimits, municipalities, everything, publicRoads)
    fetchProjectRoadLinks(projectId, boundingRectangle, roadNumberLimits, municipalities, everything, publicRoads, fetch)
  }

  //Temporary method that will be replaced for getProjectLinksWithSuravage method
  def getProjectLinksWithoutSuravage(roadAddressService: RoadAddressService, projectId: Long, boundingRectangle: BoundingRectangle,
                                     roadNumberLimits: Seq[(Int, Int)], municipalities: Set[Int], everything: Boolean = false,
                                     publicRoads: Boolean = false): Seq[ProjectAddressLink] = {
    val fetch = fetchBoundingBoxF(boundingRectangle, projectId, roadNumberLimits, municipalities, everything, publicRoads)
    fetchProjectRoadLinks(projectId, boundingRectangle, roadNumberLimits, municipalities, everything, publicRoads, fetch)
  }

  def getProjectLinksLinear(roadAddressService: RoadAddressService, projectId: Long, boundingRectangle: BoundingRectangle,
                            roadNumberLimits: Seq[(Int, Int)], municipalities: Set[Int], everything: Boolean = false,
                            publicRoads: Boolean = false): Seq[ProjectAddressLink] = {
    val projectLinks = fetchProjectRoadLinksLinearGeometry(projectId, boundingRectangle, roadNumberLimits, municipalities, everything)
    projectLinks
  }

  def getChangeProject(projectId: Long): (Option[ChangeProject], Option[String]) = {
    withDynTransaction {
      getChangeProjectInTX(projectId)
    }
  }

  def getChangeProjectInTX(projectId: Long): (Option[ChangeProject], Option[String]) = {
    try {
      val (recalculate, warningMessage) = recalculateChangeTable(projectId)
      if (recalculate) {
        val roadwayChanges = roadwayChangesDAO.fetchRoadwayChangesResume(Set(projectId))
        (Some(convertToChangeProject(roadwayChanges)), warningMessage)
      } else {
        (None, None)
      }
    } catch {
      case NonFatal(e) =>
        logger.info(s"Change info not available for project $projectId: " + e.getMessage)
        (None, None)
    }
  }

  /** Returns a {@link ChangeProject}
    * @throw IllegalArgumentException if <i>changeData</i> has
    * multiple project is'd, project names, change dates, or users
    * @note functionality moved here from former ViiteTierekisteriClient at Tierekisteri removal (2021-09) */
  def convertToChangeProject(changeData: List[ProjectRoadwayChange]): ChangeProject = {
    val projects = changeData.map(cd => {
      convertChangeDataToChangeProject(cd)
    })
    val grouped = projects.groupBy(p => (p.id, p.name, p.changeDate, p.user))
    if (grouped.keySet.size > 1)
      throw new IllegalArgumentException("Multiple projects, users or change dates in single data set")
    projects.tail.foldLeft(projects.head) { case (proj1, proj2) =>
      proj1.copy(changeInfoSeq = proj1.changeInfoSeq ++ proj2.changeInfoSeq)
    }
  }
  private val nullRotatingChangeProjectId = -1   // note: previously nullRotatingTRProjectId, refers to project's TR_ID field in DB

  private def convertChangeDataToChangeProject(changeData: ProjectRoadwayChange): ChangeProject = {
    val changeInfo = changeData.changeInfo
    ChangeProject(changeData.rotatingTRId.getOrElse(nullRotatingChangeProjectId), changeData.projectName.getOrElse(""), changeData.user,
      DateTimeFormat.forPattern("yyyy-MM-dd").print(changeData.projectStartDate), Seq(changeInfo))
  }


  def prettyPrintLog(roadwayChanges: List[ProjectRoadwayChange]): Unit = {
    roadwayChanges.groupBy(a => (a.projectId, a.projectName, a.projectStartDate, a.ely)).foreach(g => {
      val (projectId, projectName, projectStartDate, projectEly) = g._1
      val changes = g._2
      logger.info(s"Changes for project [ID: $projectId; Name: ${projectName.getOrElse("")}; StartDate: $projectStartDate; Ely: $projectEly]:")
      changes.foreach(c => {
        logger.info(s"Change: ${c.toStringWithFields}")
      })
    })
  }

  def getProjectAddressLinksByLinkIds(linkIdsToGet: Set[Long]): Seq[ProjectAddressLink] = {
    if (linkIdsToGet.isEmpty)
      Seq()
    withDynSession {
      new ProjectLinkDAO().fetchProjectLinksByLinkId(linkIdsToGet.toSeq).map(pl => ProjectAddressLinkBuilder.build(pl))
    }
  }

  /**
    * Main function responsible for fetching and building Project Road Links.
    * First we fetch all kinds of road addresses, project links and vvh road links inside a bounding box.
    * After that we fetch the unaddressed links via bounding box as well.
    * With all the information we have now we start to call the various builders to get the information from multiple sources combined.
    * Once our road information is combined we pass it to the fillTopology in order for it to do some adjustments when needed and to finalize it we filter via the complementaryLinkFilter and evoke the final builder to get the result we need.
    *
    * @param projectId         : Long - Project id
    * @param boundingRectangle : BoundingRectangle - designates where we search
    * @param roadNumberLimits  : Seq[(Int, Int)] - used in the filtering of results
    * @param municipalities    : Set[Int] - used to limit the results to these municipalities
    * @param everything        : Boolean - used in the filtering of results
    * @param publicRoads       : Boolean - used in the filtering of results
    * @param fetch             : ProjectBoundingBoxResult - collection of all our combined fetches from different sources
    * @return
    */
  def fetchProjectRoadLinks(projectId: Long, boundingRectangle: BoundingRectangle, roadNumberLimits: Seq[(Int, Int)], municipalities: Set[Int],
                            everything: Boolean = false, publicRoads: Boolean = false, fetch: ProjectBoundingBoxResult): Seq[ProjectAddressLink] = {

    def complementaryLinkFilter(roadNumberLimits: Seq[(Int, Int)], municipalities: Set[Int],
                                everything: Boolean = false, publicRoads: Boolean = false)(roadAddressLink: RoadAddressLink) = {
      everything || publicRoads || roadNumberLimits.exists {
        case (start, stop) => roadAddressLink.roadNumber >= start && roadAddressLink.roadNumber <= stop
      }
    }

    val projectState = getProjectState(projectId)
    val fetchRoadAddressesByBoundingBoxF = Future(withDynTransaction {
      val addresses = roadAddressService.getRoadAddressesByBoundingBox(boundingRectangle,
        roadNumberLimits = roadNumberLimits)
      addresses.groupBy(_.linkId)
    })
    val fetchProjectLinksF = fetch.projectLinkResultF
    val fetchVVHStartTime = System.currentTimeMillis()

    val (regularLinks, complementaryLinks) = awaitRoadLinks(fetch.roadLinkF, fetch.complementaryF)

    val fetchVVHEndTime = System.currentTimeMillis()
    logger.info("Fetch VVH road links completed in %d ms".format(fetchVVHEndTime - fetchVVHStartTime))

    val fetchUnaddressedRoadLinkStartTime = System.currentTimeMillis()
    val (addresses, currentProjectLinks) = Await.result(fetchRoadAddressesByBoundingBoxF.zip(fetchProjectLinksF), Duration.Inf)
    val projectLinks = if (projectState.isDefined && finalProjectStates.contains(projectState.get.value)) {
      fetchProjectHistoryLinks(projectId)
    }
    else currentProjectLinks

    val normalLinks = regularLinks.filterNot(l => projectLinks.exists(_.linkId == l.linkId))

    val fetchUnaddressedRoadLinkEndTime = System.currentTimeMillis()
    logger.info("Fetch unaddressed road links and floating linear locations completed in %d ms".format(fetchUnaddressedRoadLinkEndTime - fetchUnaddressedRoadLinkStartTime))

    val buildStartTime = System.currentTimeMillis()

    val projectRoadLinks = withDynSession {
      projectLinks.groupBy(l => (l.linkId, l.administrativeClass)).flatMap {
        pl => buildProjectRoadLink(pl._2)
      }
    }

    val nonProjectRoadLinks = (normalLinks ++ complementaryLinks).filterNot(rl => projectRoadLinks.exists(_.linkId == rl.linkId))
    val buildEndTime = System.currentTimeMillis()
    logger.info("Build road addresses completed in %d ms".format(buildEndTime - buildStartTime))

    val filledTopology = RoadAddressFiller.fillTopology(nonProjectRoadLinks, addresses.values.flatten.toSeq)

    val complementaryLinkIds = complementaryLinks.map(_.linkId).toSet
    val returningTopology = filledTopology.filter(link => !complementaryLinkIds.contains(link.linkId) ||
      complementaryLinkFilter(roadNumberLimits, municipalities, everything, publicRoads)(link))
    returningTopology.map(ProjectAddressLinkBuilder.build) ++ projectRoadLinks
  }


  def fetchProjectHistoryLinks(projectId: Long): Seq[ProjectLink] = {
    withDynTransaction {
      projectLinkDAO.fetchProjectLinksHistory(projectId)
    }
  }

  /**
    * Main function responsible for fetching and building ProjectAddressLink.
    * First we fetch all kinds of road addresses inside a bounding box, afterwards we fetch all of the project links for a specific project
    * With all the information we have now we start to call the builders to mix road address and project link information, the road addresses that have no match to project links w
    * Once our road information is built and evoke the final builder to get the result we need.
    *
    * @param projectId         : Long - Project id
    * @param boundingRectangle : BoundingRectangle - designates where we search
    * @param roadNumberLimits  : Seq[(Int, Int)] - used in the filtering of results
    * @param municipalities    : Set[Int] - used to limit the results to these municipalities
    * @param everything        : Boolean - used in the filtering of results
    * @param publicRoads       : Boolean - used in the filtering of results
    * @return
    */
  def fetchProjectRoadLinksLinearGeometry(projectId: Long, boundingRectangle: BoundingRectangle, roadNumberLimits: Seq[(Int, Int)], municipalities: Set[Int],
                                          everything: Boolean = false, publicRoads: Boolean = false): Seq[ProjectAddressLink] = {
    val fetchRoadAddressesByBoundingBoxF = Future(withDynTransaction {
      val addresses = roadAddressService.getRoadAddressesByBoundingBox(boundingRectangle,
        roadNumberLimits = roadNumberLimits)
      addresses.groupBy(_.linkId)
    })

    val fetchProjectLinksF = Future(withDynSession {
      val projectState = projectDAO.fetchProjectStatus(projectId)
      if (projectState.isDefined && finalProjectStates.contains(projectState.get.value))
        projectLinkDAO.fetchProjectLinksHistory(projectId).groupBy(_.linkId)
      else
        projectLinkDAO.fetchProjectLinks(projectId).groupBy(_.linkId)
    })
    val (addresses, projectLinks) = time(logger, "Fetch road addresses by bounding box") {
      Await.result(fetchRoadAddressesByBoundingBoxF.zip(fetchProjectLinksF), Duration.Inf)
    }

    val buildStartTime = System.currentTimeMillis()
    val projectRoadLinks = withDynSession {
      projectLinks.map {
        pl => pl._1 -> buildProjectRoadLink(pl._2)
      }
    }

    val nonProjectAddresses = addresses.filterNot(a => projectLinks.contains(a._1))

    val nonProjectLinks = nonProjectAddresses.values.flatten.toSeq.map { address =>
      address.linkId -> roadAddressLinkBuilder.build(address)
    }.toMap

    logger.info("Build road addresses completed in %d ms".format(System.currentTimeMillis() - buildStartTime))


    nonProjectLinks.values.toSeq.map(ProjectAddressLinkBuilder.build) ++ projectRoadLinks.values.flatten
  }


  def fetchBoundingBoxF(boundingRectangle: BoundingRectangle, projectId: Long, roadNumberLimits: Seq[(Int, Int)], municipalities: Set[Int],
                        everything: Boolean = false, publicRoads: Boolean = false): ProjectBoundingBoxResult = {
    ProjectBoundingBoxResult(
      Future(withDynSession(projectLinkDAO.fetchProjectLinks(projectId))),
      Future(roadLinkService.getRoadLinksFromVVH(boundingRectangle, roadNumberLimits, municipalities, everything, publicRoads)),
      Future(
        if (everything) roadLinkService.getComplementaryRoadLinksFromVVH(boundingRectangle, municipalities)
        else Seq())
    )
  }

  def getProjectRoadLinks(projectId: Long, boundingRectangle: BoundingRectangle, roadNumberLimits: Seq[(Int, Int)], municipalities: Set[Int],
                          everything: Boolean = false, publicRoads: Boolean = false): Seq[ProjectAddressLink] = {
    val fetch = fetchBoundingBoxF(boundingRectangle, projectId, roadNumberLimits, municipalities, everything, publicRoads)
    fetchProjectRoadLinks(projectId, boundingRectangle, roadNumberLimits, municipalities, everything, publicRoads, fetch)
  }

  /**
    * Fetches the project while testing for the following:
    * Project existence
    * Road Number and Road Part Number combination is reserved by the project
    * If the road part combination is available for use in this project date
    * If the road part combination is not reserved by another project.
    *
    * @param projectId     : Long - Project Id
    * @param newRoadNumber : Long - Road number
    * @param newRoadPart   : Long - Road part number
    * @param linkStatus    : LinkStatus - What kind of operation is subjected
    * @param projectLinks  : Seq[ProjectLink] - Project links
    * @return
    */
  private def getProjectWithReservationChecks(projectId: Long, newRoadNumber: Long, newRoadPart: Long, linkStatus: LinkStatus, projectLinks: Seq[ProjectLink]): Project = {
    val project = fetchProjectById(projectId).getOrElse(throw new ProjectValidationException(ProjectNotFoundMessage))
    projectValidator.checkReservedExistence(project, newRoadNumber, newRoadPart, linkStatus, projectLinks)
    projectValidator.checkAvailable(newRoadNumber, newRoadPart, project)
    projectValidator.checkReservedPartInProject(newRoadNumber, newRoadPart, project, linkStatus)
    projectValidator.checkReservedPartInOtherProject(newRoadNumber, newRoadPart, project)
    projectValidator.checkFormationInOtherProject(project, newRoadNumber, newRoadPart, linkStatus)
    project
  }

  /**
    * Reverts project links to their previous state, if used on new links it will delete them, if used on the rest they will become unhandled.
    * Also resets values to their starting values.
    *
    * @param links    : Iterable[ProjectLink] - Links to revert
    * @param userName : String - User name
    * @return
    */
  def revertFetchedLinks(links: Iterable[ProjectLink], userName: String): Option[String] = {
    if (links.groupBy(l => (l.projectId, l.roadNumber, l.roadPartNumber)).keySet.size != 1)
      throw new IllegalArgumentException("Reverting links from multiple road parts at once is not allowed")
    val l = links.head
    revertLinksByRoadPart(l.projectId, l.roadNumber, l.roadPartNumber, links.map(
      link => LinkToRevert(link.id, link.linkId, link.status.value, link.geometry)), userName)
  }

  def revertRoadName(projectId: Long, roadNumber: Long): Unit = {
    if (projectLinkDAO.fetchProjectLinks(projectId).exists(pl => pl.roadNumber == roadNumber) && RoadNameDAO.getLatestRoadName(roadNumber).nonEmpty) {
      ProjectLinkNameDAO.revert(roadNumber, projectId)
      val roadAddressName = RoadNameDAO.getLatestRoadName(roadNumber)
      val projectRoadName = ProjectLinkNameDAO.get(roadNumber, projectId)
      if (roadAddressName.nonEmpty && projectRoadName.isEmpty) {
        ProjectLinkNameDAO.create(projectId, roadNumber, roadAddressName.get.roadName)
      }
    }
    if (!projectLinkDAO.fetchProjectLinks(projectId).exists(pl => pl.roadNumber == roadNumber)) {
      ProjectLinkNameDAO.revert(roadNumber, projectId)
    }
  }

  /**
    * Last function on the chain, this is the one that will do all the work.
    * Firstly we isolate the unique link id's that were modified and we remove all the project links that have them them from the project.
    * We use the same link ids we found and fetch the road addresses by combining VVH roadlink information and our roadway+linear location information on the builder.
    * With the road address information we now check that a reservation is possible and reserve them in the project.
    * Afterwards we update the newly reserved project links with the original geometry we obtained previously
    * If we do still have road address information that do not match the original modified links then we check that a reservation is possible and reserve them in the project and we update those reserved links with the information on the road address.
    * With that done we revert any changes on the road names, when applicable.
    * If it is mandated we run the recalculateProjectLinks on this project.
    * After all this we come to the conclusion that we have no more road number and road parts for this project then we go ahead and release them.
    *
    * @param projectId      : Long - Project ID
    * @param roadNumber     : Long - Roadway Road Number
    * @param roadPartNumber : Long - Roadway Road Part Number
    * @param toRemove       : Iterable[LinksToRemove] - Project links that were created in this project
    * @param modified       : Iterable[LinksToRemove] - Project links that existed as road addresses
    * @param userName       : String - User name
    * @param recalculate    : Boolean - Will tell if we recalculate the whole project links or not
    */
  private def revertSortedLinks(projectId: Long, roadNumber: Long, roadPartNumber: Long, toRemove: Iterable[LinkToRevert],
                                modified: Iterable[LinkToRevert], userName: String, recalculate: Boolean = true): Unit = {
    val modifiedLinkIds = modified.map(_.linkId).toSet
    projectLinkDAO.removeProjectLinksByLinkId(projectId, toRemove.map(_.linkId).toSet)
    val vvhRoadLinks = roadLinkService.getCurrentAndComplementaryRoadLinksFromVVH(modifiedLinkIds)
    val roadAddresses = roadwayAddressMapper.getRoadAddressesByLinearLocation(linearLocationDAO.fetchRoadwayByLinkId(modifiedLinkIds))
    roadAddresses.foreach(ra =>
      modified.find(mod => mod.linkId == ra.linkId) match {
        case Some(mod) =>
          checkAndReserve(fetchProjectById(projectId).get, toReservedRoadPart(ra.roadNumber, ra.roadPartNumber, ra.ely))
          if (mod.geometry.nonEmpty) {
            val vvhGeometry = vvhRoadLinks.find(roadLink => roadLink.linkId == mod.linkId && roadLink.linkSource == ra.linkGeomSource)
            if (vvhGeometry.nonEmpty) {
              val geom = GeometryUtils.truncateGeometry3D(vvhGeometry.get.geometry, ra.startMValue, ra.endMValue)
              projectLinkDAO.updateProjectLinkValues(projectId, ra.copy(geometry = geom))
            } else {
              projectLinkDAO.updateProjectLinkValues(projectId, ra, updateGeom = false)
            }
          } else {
            projectLinkDAO.updateProjectLinkValues(projectId, ra, updateGeom = false)
          }
        case _ =>
      })
    revertRoadName(projectId, roadNumber)
    if (recalculate)
      try {
        recalculateProjectLinks(projectId, userName, Set((roadNumber, roadPartNumber)))
      } catch {
        case _: Exception => logger.info("Couldn't recalculate after reverting a link (this may happen)")
      }
    val afterUpdateLinks = projectLinkDAO.fetchByProjectRoadPart(roadNumber, roadPartNumber, projectId)
    if (afterUpdateLinks.isEmpty) {
      releaseRoadPart(projectId, roadNumber, roadPartNumber, userName)
    }
  }

  def isProjectWithGivenLinkIdWritable(linkId: Long): Boolean = {
    val projects =
      withDynSession(projectDAO.fetchAllIdsByLinkId(linkId))
    if (projects.isEmpty)
      return false
    true
  }

  def toReservedRoadPart(roadNumber: Long, roadPartNumber: Long, ely: Long): ProjectReservedPart = {
    ProjectReservedPart(0L, roadNumber, roadPartNumber,
      None, None, Some(ely),
      None, None, None, None)
  }


  /**
    * Splits the links to revert in two separate types, the modified (ones that came from road addresses) and the added (ones that were created in this project).
    * Also fetches the project links by road number, road part number and project id and supply them to the next step.
    *
    * @param projectId      : Long - Project Id
    * @param roadNumber     : Long - Roadway Road Number
    * @param roadPartNumber : Long - Roadway Road Part Number
    * @param links          : Iterable[ProjectLink] - Links to revert
    * @param userName       : String - User name
    * @return
    */
  def revertLinksByRoadPart(projectId: Long, roadNumber: Long, roadPartNumber: Long, links: Iterable[LinkToRevert], userName: String): Option[String] = {
    val (added, modified) = links.partition(_.status == LinkStatus.New.value)
    revertSortedLinks(projectId, roadNumber, roadPartNumber, added, modified, userName)
    None
  }

  /**
    * Continuation of the revert. Sets up the database transaction to save the modifications done to the links to revert.
    * After the modifications are saved this will save the new project coordinates.
    * Otherwise this will issue a error messages.
    *
    * @param projectId      : Long - The id of the project
    * @param roadNumber     : Long - roadway road number
    * @param roadPartNumber : Long - roadway road part number
    * @param links          : Iterable[LinkToRevert] - The links to return to the original values and state
    * @param coordinates    : ProjectCoordinates - New coordinates on where to move the map on project open
    * @param userName       : String - The name of the user
    * @return
    */
  def revertLinks(projectId: Long, roadNumber: Long, roadPartNumber: Long, links: Iterable[LinkToRevert], coordinates: ProjectCoordinates, userName: String): Option[String] = {
    try {
      withDynTransaction {
        val (added, modified) = links.partition(_.status == LinkStatus.New.value)
        projectWritableCheckInSession(projectId) match {
          case None =>
            if (modified.exists(_.status == LinkStatus.Numbering.value)) {
              logger.info(s"Reverting whole road part in $projectId ($roadNumber/$roadPartNumber)")
              // Numbering change affects the whole road part
              revertSortedLinks(projectId, roadNumber, roadPartNumber, added,
                projectLinkDAO.fetchByProjectRoadPart(roadNumber, roadPartNumber, projectId).map(
                  link => LinkToRevert(link.id, link.linkId, link.status.value, link.geometry)),
                userName)
            } else {
              revertSortedLinks(projectId, roadNumber, roadPartNumber, added, modified, userName)
            }
            saveProjectCoordinates(projectId, coordinates)
            None
          case Some(error) => Some(error)
        }
      }
    } catch {
      case NonFatal(e) =>
        logger.info("Error reverting the changes on roadlink", e)
        Some("Virhe tapahtui muutosten palauttamisen yhteydessä")
    }
  }

  private def releaseRoadPart(projectId: Long, roadNumber: Long, roadPartNumber: Long, userName: String) = {
    if (projectLinkDAO.fetchFirstLink(projectId, roadNumber, roadPartNumber).isEmpty) {
      projectReservedPartDAO.removeReservedRoadPartAndChanges(projectId, roadNumber, roadPartNumber)
    } else {
      val links = projectLinkDAO.fetchByProjectRoadPart(roadNumber, roadPartNumber, projectId)
      revertFetchedLinks(links, userName)
    }
  }

  /**
    * Updates project links to given status and recalculates delta and change table.
    *
    * @param projectId  Project's id
    * @param linkIds    Set of link ids that are set to this status
    * @param linkStatus New status for given link ids
    * @param userName   Username of the user that does this change
    * @return true, if the delta calculation is successful and change table has been updated.
    */
  def updateProjectLinks(projectId: Long, ids: Set[Long], linkIds: Seq[Long], linkStatus: LinkStatus, userName: String,
                         newRoadNumber: Long, newRoadPartNumber: Long, newTrackCode: Int,
                         userDefinedEndAddressM: Option[Int], administrativeClass: Long = AdministrativeClass.State.value,
                         discontinuity: Int = Discontinuity.Continuous.value, ely: Option[Long] = None,
                         reversed: Boolean = false, roadName: Option[String] = None, coordinates: Option[ProjectCoordinates] = None): Option[String] = {
    def isCompletelyNewPart(toUpdateLinks: Seq[ProjectLink]): (Boolean, Long, Long) = {
      val reservedPart = projectReservedPartDAO.fetchReservedRoadPart(toUpdateLinks.head.roadNumber, toUpdateLinks.head.roadPartNumber).get
      val newSavedLinks = if (roadwayDAO.fetchAllByRoadAndPart(reservedPart.roadNumber, reservedPart.roadPartNumber).isEmpty) {
        projectLinkDAO.fetchByProjectRoadPart(reservedPart.roadNumber, reservedPart.roadPartNumber, projectId)
      } else Seq.empty
      /*
      replaceable -> New link part should replace New existing part if:
        1. Action is LinkStatus.New
        2. New road or part is different from existing one
        3. All New links in existing part are in selected links for New part
       */
      val replaceable = (linkStatus == New || linkStatus == Transfer) && (reservedPart.roadNumber != newRoadNumber || reservedPart.roadPartNumber != newRoadPartNumber) && newSavedLinks.nonEmpty && newSavedLinks.map(_.id).toSet.subsetOf(ids)
      (replaceable, reservedPart.roadNumber, reservedPart.roadPartNumber)
    }

    def updateAdministrativeClassDiscontinuity(links: Seq[ProjectLink]): Unit = {
      val originalAddresses = roadAddressService.getRoadAddressesByRoadwayIds(links.map(_.roadwayId))
      if (links.nonEmpty) {
        val lastSegment = links.maxBy(_.endAddrMValue)
        if (links.lengthCompare(1) > 0) {
          val linksToUpdate = links.filterNot(_.id == lastSegment.id)
          projectLinkDAO.updateProjectLinks(linksToUpdate, userName, originalAddresses)
        }
        projectLinkDAO.updateProjectLinks(Seq(lastSegment.copy(discontinuity = Discontinuity.apply(discontinuity.toInt))), userName, originalAddresses)
      }
    }

    def checkAndMakeReservation(projectId: Long, newRoadNumber: Long, newRoadPart: Long, linkStatus: LinkStatus, projectLinks: Seq[ProjectLink]): (Boolean, Option[Long], Option[Long]) = {
      val project = getProjectWithReservationChecks(projectId, newRoadNumber, newRoadPart, linkStatus, projectLinks)
      try {
        val (toReplace, road, part) = isCompletelyNewPart(projectLinks)
        if (toReplace && linkStatus == New) {
          val reservedPart = projectReservedPartDAO.fetchReservedRoadPart(road, part).get
          projectReservedPartDAO.removeReservedRoadPartAndChanges(projectId, reservedPart.roadNumber, reservedPart.roadPartNumber)
          val newProjectLinks: Seq[ProjectLink] = projectLinks.map(pl => pl.copy(id = NewIdValue, roadNumber = newRoadNumber, roadPartNumber = newRoadPart, track = Track.apply(newTrackCode), discontinuity = Continuous, endAddrMValue = userDefinedEndAddressM.getOrElse(pl.endAddrMValue.toInt).toLong, administrativeClass = AdministrativeClass.apply(administrativeClass.toInt)))
          if (linkIds.nonEmpty) {
            addNewLinksToProject(sortRamps(newProjectLinks, linkIds), projectId, userName, linkIds.head, newTransaction = false, Discontinuity.apply(discontinuity))
          } else {
            val newSavedLinkIds = projectLinks.map(_.linkId)
            addNewLinksToProject(sortRamps(newProjectLinks, newSavedLinkIds), projectId, userName, newSavedLinkIds.head, newTransaction = false, Discontinuity.apply(discontinuity))
          }
        } else if (!project.isReserved(newRoadNumber, newRoadPart) && !project.isFormed(newRoadNumber, newRoadPart)) {
          projectReservedPartDAO.reserveRoadPart(project.id, newRoadNumber, newRoadPart, project.modifiedBy)
        }
        (toReplace, Some(road), Some(part))
      } catch {
        case e: Exception => println("Unexpected exception occurred: " + e)
          (false, None, None)
      }
    }

    def resetLinkValues(toReset: Seq[ProjectLink]): Unit = {
      val addressesForRoadway = roadAddressService.getRoadAddressesByRoadwayIds(toReset.map(_.roadwayId))
      val filteredAddresses   = addressesForRoadway.filter(link => {
        toReset.map(_.linkId).contains(link.linkId)
      })
      filteredAddresses.foreach(ra => {
        projectLinkDAO.updateProjectLinkValues(projectId, ra.copy(ely = toReset.find(pl => {
          pl.roadwayId == ra.id
        }).get.ely),
          updateGeom = false)
      })
    }

    try {
      withDynTransaction {
        val toUpdateLinks = projectLinkDAO.fetchProjectLinksByProjectAndLinkId(ids, linkIds.toSet, projectId)
        userDefinedEndAddressM.foreach(addressM => {
          val endSegment                = toUpdateLinks.maxBy(_.endAddrMValue)
          val calibrationPoint          = UserDefinedCalibrationPoint(NewIdValue, endSegment.id, projectId, addressM.toDouble - endSegment.startMValue, addressM)
          val lastEndSegmentLink        = toUpdateLinks.find(ul => ul.projectId == projectId && ul.startAddrMValue == endSegment.startAddrMValue)
          val calibrationPointIsPresent = lastEndSegmentLink match {
            case Some(projectLink) =>
              projectLink.hasCalibrationPointAt(calibrationPoint.addressMValue)
            case _ => false
          }
          /* Store the user defined calibration point with given address even if other calibratation type exists. */
          if (!calibrationPointIsPresent || lastEndSegmentLink.last.endCalibrationPointType != CalibrationPointDAO.CalibrationPointType.UserDefinedCP) {
            val foundCalibrationPoint = ProjectCalibrationPointDAO.findEndCalibrationPoint(endSegment.id, projectId)
            if (foundCalibrationPoint.isEmpty)
              ProjectCalibrationPointDAO.createCalibrationPoint(calibrationPoint)
            else
              ProjectCalibrationPointDAO.updateSpecificCalibrationPointMeasures(foundCalibrationPoint.head.id, addressM.toDouble - endSegment.startMValue, addressM)
            Seq(CalibrationPoint)
          } else
            Seq.empty[CalibrationPoint]
        })
        linkStatus match {
          case LinkStatus.Terminated =>
            // Fetching road addresses in order to obtain the original addressMValues, since we may not have those values
            // on project_link table, after previous recalculations
            resetLinkValues(toUpdateLinks)
            projectLinkDAO.updateProjectLinksStatus(toUpdateLinks.map(_.id).toSet, LinkStatus.Terminated, userName)

          case LinkStatus.Numbering =>
            if (toUpdateLinks.nonEmpty) {
              val currentAddresses = roadAddressService.getRoadAddressesFiltered(newRoadNumber, newRoadPartNumber)
              val roadAddresses = roadAddressService.getRoadAddressesByRoadwayIds(toUpdateLinks.map(_.roadwayId))
              if (roadAddresses.exists(x =>
                x.roadNumber == newRoadNumber && x.roadPartNumber == newRoadPartNumber)) // check the original numbering wasn't exactly the same
                throw new ProjectValidationException(ErrorRenumberingToOriginalNumber) // you cannot use current roadnumber and roadpart number in numbering operation
              if (currentAddresses.nonEmpty)
                throw new ProjectValidationException(ErrorRoadAlreadyExistsOrInUse)
              if (toUpdateLinks.map(pl => (pl.roadNumber, pl.roadPartNumber)).distinct.lengthCompare(1) != 0 ||
                roadAddresses.map(ra => (ra.roadNumber, ra.roadPartNumber)).distinct.lengthCompare(1) != 0) {
                throw new ProjectValidationException(ErrorMultipleRoadNumbersOrParts)
              }
              val roadPartLinks = projectLinkDAO.fetchProjectLinksByProjectRoadPart(toUpdateLinks.head.roadNumber, toUpdateLinks.head.roadPartNumber, projectId)
              if (roadPartLinks.exists(rpl => rpl.status == UnChanged || rpl.status == Transfer || rpl.status == New || rpl.status == Terminated)) {
                throw new ProjectValidationException(ErrorOtherActionWithNumbering)
              }
              checkAndMakeReservation(projectId, newRoadNumber, newRoadPartNumber, LinkStatus.Numbering, toUpdateLinks)

              projectLinkDAO.updateProjectLinkNumbering(projectId, toUpdateLinks.head.roadNumber, toUpdateLinks.head.roadPartNumber,
                linkStatus, newRoadNumber, newRoadPartNumber, userName, ely.getOrElse(toUpdateLinks.head.ely))
              projectLinkDAO.updateProjectLinkAdministrativeClassDiscontinuity(Set(toUpdateLinks.maxBy(_.endAddrMValue).id), linkStatus, userName, administrativeClass, Some(discontinuity))
              val nameError = roadName.flatMap(setProjectRoadName(projectId, newRoadNumber, _)).toList.headOption
              if (nameError.nonEmpty)
                return nameError
            } else {
              throw new ProjectValidationException(ErrorRoadLinkNotFoundInProject)
            }

          case LinkStatus.Transfer =>
            val (replaceable, road, part) = checkAndMakeReservation(projectId, newRoadNumber, newRoadPartNumber, LinkStatus.Transfer, toUpdateLinks)
            val updated = toUpdateLinks.map(l => {
              val startCP = l.startCalibrationPointType match {
                case JunctionPointCP => JunctionPointCP
                case UserDefinedCP => UserDefinedCP
                case _ => NoCP
              }
              val endCP = l.endCalibrationPointType match {
                case JunctionPointCP => JunctionPointCP
                case UserDefinedCP => UserDefinedCP
                case _ => NoCP
              }
              l.copy(roadNumber = newRoadNumber, roadPartNumber = newRoadPartNumber, track = Track.apply(newTrackCode), calibrationPointTypes = (startCP, endCP), status = linkStatus, administrativeClass = AdministrativeClass.apply(administrativeClass.toInt), ely = ely.getOrElse(l.ely))
            })
            val originalAddresses = roadAddressService.getRoadAddressesByRoadwayIds(updated.map(_.roadwayId))
            projectLinkDAO.updateProjectLinks(updated, userName, originalAddresses)
            projectLinkDAO.updateProjectLinkAdministrativeClassDiscontinuity(Set(updated.maxBy(_.endAddrMValue).id), linkStatus, userName, administrativeClass, Some(discontinuity))
            //transfer cases should remove the part after the project link table update operation
            if (replaceable) {
              projectReservedPartDAO.removeReservedRoadPart(projectId, road.get, part.get)
            }
            val nameError = roadName.flatMap(setProjectRoadName(projectId, newRoadNumber, _)).toList.headOption
            if (nameError.nonEmpty)
              return nameError

          case LinkStatus.UnChanged =>
            checkAndMakeReservation(projectId, newRoadNumber, newRoadPartNumber, LinkStatus.UnChanged, toUpdateLinks)
            // Reset back to original values
            val updated = toUpdateLinks.map(l => {
              l.copy(ely = ely.getOrElse(l.ely))
            })
            resetLinkValues(updated)
            updateAdministrativeClassDiscontinuity(updated.map(_.copy(status = linkStatus, administrativeClass = AdministrativeClass.apply(administrativeClass.toInt))))

          case LinkStatus.New => {
            // Current logic allows only re adding new road addresses within same road/part group
            if (toUpdateLinks.groupBy(l => (l.roadNumber, l.roadPartNumber)).size <= 1) {
              checkAndMakeReservation(projectId, newRoadNumber, newRoadPartNumber, LinkStatus.New, toUpdateLinks)
              updateAdministrativeClassDiscontinuity(toUpdateLinks.map(l => l.copy(roadNumber = newRoadNumber, roadPartNumber = newRoadPartNumber, track = Track.apply(newTrackCode), administrativeClass = AdministrativeClass.apply(administrativeClass.toInt), ely = ely.getOrElse(l.ely))))
              val nameError = roadName.flatMap(setProjectRoadName(projectId, newRoadNumber, _)).toList.headOption
              if (nameError.nonEmpty)
                return nameError
            } else {
              throw new RoadAddressException(s"Useamman kuin yhden tien/tieosan tallennus kerralla ei ole tuettu.")
            }
          }
          case _ =>
            throw new ProjectValidationException(s"Virheellinen operaatio $linkStatus")
        }

        if (coordinates.isDefined) {
          saveProjectCoordinates(projectId, coordinates.get)
        }
        else {
          saveProjectCoordinates(projectId, calculateProjectCoordinates(projectId))
        }
        None
      }
    } catch {
      case ex: RoadAddressException =>
        logger.info("Road address Exception: " + ex.getMessage)
        Some(s"Tieosoitevirhe: ${ex.getMessage}")
      case ex: ProjectValidationException => Some(ex.getMessage)
      case ex: Exception => Some(ex.getMessage)
    }
  }


  def recalculateProjectLinks(projectId: Long, userName: String, roadParts: Set[(Long, Long)] = Set(), newTrack: Option[Track] = None, newDiscontinuity: Option[Discontinuity] = None, completelyNewLinkIds: Seq[Long] = Seq()): Unit = {
      /* Terminate unhandled links left behind. */
      //    val notHandled = projectLinks.filter(_.status == LinkStatus.NotHandled)
      //    if (notHandled.nonEmpty) {
      //      projectLinkDAO.updateProjectLinksStatus(notHandled.map(_.id).toSet, LinkStatus.Terminated, userName)
      //      projectLinks = projectLinkDAO.fetchProjectLinks(projectId)
      //    }

      logger.info(s"Recalculating project $projectId, parts ${roadParts.map(p => {
        s"${p._1}/${p._2}"
      }).mkString(", ")}")

    time(logger, "Recalculate links") {

      val projectLinks = projectLinkDAO.fetchProjectLinks(projectId)
      /* Remove user defined calibration points before (re)calc. */
      val udcpRemovedProjectLinks = if(projectLinks.isEmpty) {
        Seq()
      } else { projectLinks.tail.scanLeft(projectLinks.head) { (p, r) => {
        if      (r.calibrationPoints._2.getOrElse(NoCP) == UserDefinedCP) r.copy(calibrationPointTypes = (r.startCalibrationPointType, NoCP))
        else if (r.calibrationPoints._1.getOrElse(NoCP) == UserDefinedCP) r.copy(calibrationPointTypes = (NoCP, r.endCalibrationPointType))
        else r
        }}
      }

      val (terminated, others) = udcpRemovedProjectLinks.partition(_.status == LinkStatus.Terminated)

        val recalculated = others.groupBy(pl => {
          (pl.roadNumber, pl.roadPartNumber)
        }).flatMap {
          grp =>
            val calibrationPoints = ProjectCalibrationPointDAO.fetchByRoadPart(projectId, grp._1._1, grp._1._2)
            val calculatedLinks   = ProjectSectionCalculator.assignMValues(grp._2, calibrationPoints).sortBy(_.endAddrMValue)
            if (!calculatedLinks.exists(_.isNotCalculated) && newDiscontinuity.isDefined && newTrack.isDefined &&
                roadParts.contains((calculatedLinks.head.roadNumber, calculatedLinks.head.roadPartNumber))) {
              if (completelyNewLinkIds.nonEmpty) {
                val (completelyNew, others) = calculatedLinks.partition(cl => {
                  completelyNewLinkIds.contains(cl.id) || cl.id == NewIdValue
                })
                others ++ (if (completelyNew.nonEmpty) {
                  completelyNew.init :+ completelyNew.last.copy(discontinuity = newDiscontinuity.get)
                } else {
                  Seq()
                })
              } else {
                val (filtered, rest) = calculatedLinks.partition(_.track == newTrack.get)
                rest ++ (if (filtered.nonEmpty) {
                  filtered.init :+ filtered.last.copy(discontinuity = newDiscontinuity.get)
                } else {
                  Seq()
                })
              }
            }
            else
              calculatedLinks
        }.toSeq

        val assignedTerminatedRoadwayNumbers = assignTerminatedRoadwayNumbers(others ++ terminated)
        val originalAddresses                = roadAddressService.getRoadAddressesByRoadwayIds((recalculated ++ terminated).map(_.roadwayId))
        projectLinkDAO.updateProjectLinks(recalculated ++ assignedTerminatedRoadwayNumbers, userName, originalAddresses)
        val projectLinkIdsToDB = recalculated.map(_.id).diff(projectLinks.map(_.id))
        projectLinkDAO.create(recalculated.filter(pl => projectLinkIdsToDB.contains(pl.id)))
      }
  }

  /*
    1.group and check if all links existing in the section are terminated.
    1.1.If yes Then there is no need to expire the section. for e.g. RwNumber 123 : |--Terminated-->|--Terminated-->|
    1.2.If not, then we are splitting the section for e.g. RwNumber 123 : |--Other action-->|--Terminated-->|
      and if we split the section then we need to assign one new roadwaynumber to the terminated projectlinks
   */
  // VIITE-2179
  private def assignTerminatedRoadwayNumbers(seq: Seq[ProjectLink]): Seq[ProjectLink] = {
    //getting sections by RoadwayNumber
    val sectionGroup = seq.groupBy(pl => (pl.track, pl.roadwayNumber))
    //check if entire section changed
    sectionGroup.values.flatMap { pls =>
      if (!pls.forall(_.status == LinkStatus.Terminated)) {
        val newRoadwayNumber = Sequences.nextRoadwayNumber
        val terminated = pls.filter(_.status == LinkStatus.Terminated)
        terminated.map(_.copy(roadwayNumber = newRoadwayNumber))
      } else pls.filter(_.status == LinkStatus.Terminated)
    }.toSeq
  }

  /** @throws IllegalArgumentException when the given project is not found. */
  private def recalculateChangeTable(projectId: Long): (Boolean, Option[String]) = {
    val projectOpt = fetchProjectById(projectId)
    if (projectOpt.isEmpty)
      throw new IllegalArgumentException("Project not found")
    val project = projectOpt.get
    project.status match {

      // For final ProjectState UpdatingToRoadNetwork we do nothing, but return "everything's fine" (...?)
      case ProjectState.UpdatingToRoadNetwork => (true, None)

      // For other than final states, proceed to recalculation
      case _ => {
        roadwayChangesDAO.clearRoadChangeTable(projectId)
        roadwayChangesDAO.insertDeltaToRoadChangeTable(projectId, projectOpt)
      }
    }
  }

  /**
    * Checks if project is publishable. Add filters for cases we do not want to prevent sending.
    *
    * @param projectId project-id
    * @return if project contains any notifications preventing sending
    */
  def isProjectPublishable(projectId: Long): Boolean = {
    validateProjectById(projectId).isEmpty
  }

  def allLinksHandled(projectId: Long): Boolean = { //some tests want to know if all projectLinks have been handled. to remove this test need to be updated to check if termination is correctly applied etc best done after all validations have been implemented
    withDynSession {
      projectLinkDAO.fetchProjectLinks(projectId, Some(LinkStatus.NotHandled)).isEmpty &&
        projectLinkDAO.fetchProjectLinks(projectId).nonEmpty
    }
  }

  /** Nullifies projects tr_id attribute, changes status to unfinished and saves tr_info value to status_info. Tries to append old status info if it is possible
    * otherwise it only takes first 300 chars
    *
    * @param projectId project-id
    * @return returns option error string
    */
  @deprecated ("Tierekisteri connection has been removed from Viite. TRId to be removed, too.")
  def removeRotatingTRId(projectId: Long): Option[String] = {
    withDynSession {
      val project = fetchProjectById(projectId)
      val rotatingTR_Id = projectDAO.fetchTRIdByProjectId(projectId)
      projectDAO.updateProjectStatus(projectId, ProjectState.Incomplete)
      val addedStatus = if (rotatingTR_Id.isEmpty) "" else "[OLD TR_ID was " + rotatingTR_Id.get + "]"
      if (project.isEmpty)
        return Some("Projektia ei löytynyt")
      appendStatusInfo(project.get, addedStatus)
    }
    None
  }

  /**
    * Tries to append old status info if it is possible
    * otherwise it only takes first 300 chars of the old status
    *
    * @param project
    * @param appendMessage
    */
  private def appendStatusInfo(project: Project, appendMessage: String): Unit = {
    val maxStringLength = 1000
    project.statusInfo match { // before removing tr-id we want to save it in statusInfo if we need it later. Currently it is overwritten when we resend and get new error
      case Some(statusInfo) =>
        if ((statusInfo + appendMessage).length < maxStringLength)
          projectDAO.updateProjectStateInfo(appendMessage + statusInfo, project.id)
        else if (statusInfo.length + appendMessage.length < 600)
          projectDAO.updateProjectStateInfo(appendMessage + statusInfo.substring(0, 300), project.id)
      case None =>
        if (appendMessage.nonEmpty)
          projectDAO.updateProjectStateInfo(appendMessage, project.id)
    }
    projectDAO.removeProjectTRId(project.id)
  }

  /**
    * Publish project with id projectId: recalculates the change table.
    *
    * @param projectId Project to publish
    * @return optional error message, empty if no error
    */
  def publishProject(projectId: Long): PublishResult = {
    logger.info(s"Preparing to send Project ID: $projectId to TR")
    withDynTransaction {
      if (!recalculateChangeTable(projectId)._1) {
        return PublishResult(validationSuccess = false, sendSuccess = false, Some("Muutostaulun luonti epäonnistui. Tarkasta ely"))
      }
      else {
        projectDAO.assignNewProjectTRId(projectId) //Generate new TR_ID // TODO TR id handling to be removed

        projectDAO.updateProjectStatus(projectId, InUpdateQueue)
        logger.info(s"Returning dummy 'Yesyes, TR part ok', as TR call removed")
        PublishResult(validationSuccess = true, sendSuccess = true, Some(""))
      }
    }
  }

  private def newProjectTemplate(rl: RoadLinkLike, ra: RoadAddress, project: Project): ProjectLink = {
    val geometry = GeometryUtils.truncateGeometry3D(rl.geometry, ra.startMValue, ra.endMValue)
    val newEly = project.reservedParts.find(rp => rp.roadNumber == ra.roadNumber && rp.roadPartNumber == ra.roadPartNumber) match {
      case Some(rp) => rp.ely.getOrElse(ra.ely)
      case _ => ra.ely
    }

   ProjectLink(NewIdValue, ra.roadNumber, ra.roadPartNumber, ra.track, ra.discontinuity, ra.startAddrMValue, ra.endAddrMValue, ra.startAddrMValue, ra.endAddrMValue, ra.startDate, ra.endDate, Some(project.modifiedBy), ra.linkId, ra.startMValue, ra.endMValue, ra.sideCode, ra.calibrationPointTypes, (ra.startCalibrationPointType, ra.endCalibrationPointType), geometry, project.id, LinkStatus.NotHandled, ra.administrativeClass, ra.linkGeomSource, GeometryUtils.geometryLength(geometry), ra.id, ra.linearLocationId, newEly, ra.reversed, None, ra.adjustedTimestamp, roadAddressLength = Some(ra.endAddrMValue - ra.startAddrMValue))
  }

  private def newProjectLink(rl: RoadLinkLike, project: Project, roadNumber: Long, roadPartNumber: Long, trackCode: Track, discontinuity: Discontinuity, administrativeClass: AdministrativeClass, ely: Long, roadName: String = "", reversed: Boolean = false): ProjectLink = {
    ProjectLink(NewIdValue, roadNumber, roadPartNumber, trackCode, discontinuity, 0L, 0L, 0L, 0L, Some(project.startDate), None, Some(project.modifiedBy), rl.linkId, 0.0, rl.length, SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP), rl.geometry, project.id, LinkStatus.New, administrativeClass, rl.linkSource, rl.length, 0L, 0L, ely, reversed, None, rl.vvhTimeStamp, roadName = Some(roadName))
  }

  private def newProjectLink(rl: RoadLinkLike, project: Project, splitOptions: SplitOptions): ProjectLink = {
    newProjectLink(rl, project, splitOptions.roadNumber, splitOptions.roadPartNumber, splitOptions.trackCode, splitOptions.discontinuity, splitOptions.administrativeClass, splitOptions.ely)
  }

  private def buildProjectRoadLink(projectLinks: Seq[ProjectLink]): Seq[ProjectAddressLink] = {
    val pl: Seq[ProjectLink] = projectLinks.size match {
      case 0 => return Seq()
      case 1 => projectLinks
      case _ => fuseProjectLinks(projectLinks)
    }
    pl.map(l => ProjectAddressLinkBuilder.build(l))
  }


  /**
    * Combines multiple project links in one only if it is possible
    *
    * @param links : Seq[ProjectLink] - Project links to combine.
    * @return
    */
  private def fuseProjectLinks(links: Seq[ProjectLink]): Seq[ProjectLink] = {
    val linkIds = links.map(_.linkId).distinct
    val existingRoadAddresses = roadAddressService.getRoadAddressesByRoadwayIds(links.map(_.roadwayId))
    val groupedRoadAddresses = existingRoadAddresses.groupBy(record =>
      (record.roadwayNumber, record.roadNumber, record.roadPartNumber, record.track.value, record.startDate, record.endDate, record.linkId, record.administrativeClass, record.ely, record.terminated))

    if (groupedRoadAddresses.size > 1) {
      links
    }
    else {
      if (linkIds.lengthCompare(1) != 0)
        throw new IllegalArgumentException(s"Multiple road link ids given for building one link: ${linkIds.mkString(", ")}")
      if (links.exists(_.isSplit))
        links
      else {
        val geom = links.head.sideCode match {
          case SideCode.TowardsDigitizing => links.map(_.geometry).foldLeft(Seq[Point]())((geometries, ge) => geometries ++ ge)
          case _ => links.map(_.geometry).reverse.foldLeft(Seq[Point]())((geometries, ge) => geometries ++ ge)
        }
        val (startM, endM, startA, endA) = (links.map(_.startMValue).min, links.map(_.endMValue).max,
          links.map(_.startAddrMValue).min, links.map(_.endAddrMValue).max)
        Seq(links.head.copy(discontinuity = links.maxBy(_.startAddrMValue).discontinuity, startAddrMValue = startA, endAddrMValue = endA, startMValue = startM, endMValue = endM, geometry = geom))
      }
    }
  }

  private def awaitRoadLinks(fetch: (Future[Seq[RoadLink]], Future[Seq[RoadLink]])): (Seq[RoadLink], Seq[RoadLink]) = {
    val combinedFuture = for {
      fStandard <- fetch._1
      fComplementary <- fetch._2
    } yield (fStandard, fComplementary)

    val (roadLinks, complementaryLinks) = Await.result(combinedFuture, Duration.Inf)
    (roadLinks, complementaryLinks)
  }


  /** Reserves a road network project for preserving the project information onto the road network.
    * Enclosing withDynTransaction ensures that the retrieved project cannot be given to multiple
    * handler calls at the same time.
    * @return (true, projectId), when there was a reservable project, (false, ...) if not.
    * @throws Exception if an unexpected exception occurred. */
  def atomicallyReserveProjectInUpdateQueue: (Boolean,Long) = {
    val noSuchProjectDummy = -1.0.toLong
    var reserveStatus = (false, noSuchProjectDummy) // nothing reserved yet

    withDynTransaction { // to ensure uniquely retrieved projectId for each calling handler thread
      reserveStatus = {
        try {
          val projectId = projectDAO.fetchSingleProjectIdWithInUpdateQueueStatus // may throw NoSuchElementException
          projectDAO.updateProjectStatus(projectId, ProjectState.UpdatingToRoadNetwork)
          (true, projectId)
        } catch {
          case t: NoSuchElementException => { // Expected exception when nothing to update.
            logger.debug(s"No projects waiting to be preserved: ${t.getMessage}", t)
            (false, noSuchProjectDummy)
          }
          case t: Exception => {
            logger.warn(s"Unexpected exception while reserving a project for preserving: ${t.getMessage}", t)
            throw t // Rethrow the unexpected error.
          }
        }
      }
    } // withDynTransaction
    reserveStatus
  }


  /** Preserves the information of a single road network project to the road network,
    * if any such project is waiting.
    * @throws SQLException if there is an error with preserving the reserved project to the db.
    * @throws Exception if an unexpected exception occurred. */
  def atomicallyPreserveSingleProjectInUpdateQueue(): Unit = {
    val noSuchProjectDummy = -1.0.toLong

    // get a project to update to db, if any
    val (gotProjectToUpdate: Boolean, projectId: Long) = atomicallyReserveProjectInUpdateQueue

    // try preserving the project to the db, in there was one to be updated
    withDynTransaction {
      if(gotProjectToUpdate) {
        try {
          time(logger, s"Preserve the project $projectId to the road network") {
            preserveProjectToDB(projectId)
          }
        } catch {
          // in case of an expected error, set project status to ProjectState.ErrorInViite
          case t: InvalidAddressDataException => {
            logger.warn(s"InvalidAddressDataException while preserving the project $projectId" +
                         s" to road network. ${t.getMessage}", t)
            projectDAO.updateProjectStatus(projectId, ProjectState.ErrorInViite)
          }
          case t: SQLException => {
            logger.error(s"SQL error while preserving the project $projectId" +
                         s" to road network. ${t.getMessage}", t)
            projectDAO.updateProjectStatus(projectId, ProjectState.ErrorInViite)
          }
          // re-throw unexpected errors
          case t: Exception => {
            logger.warn(s"Unexpected exception while preserving the project $projectId", t.getMessage)
            projectDAO.updateProjectStatus(projectId, ProjectState.ErrorInViite)
            throw t  // Rethrow the unexpected error.
          }
        }
        projectDAO.updateProjectStatus(projectId, ProjectState.Accepted)
      }
    }
  }

  def getProjectState(projectId: Long): Option[ProjectState] = {
    withDynTransaction {
      projectDAO.fetchProjectStatus(projectId)
    }
  }

  /**
    * Checks the project information from TR. If TR reply is such then we convert all the project links into regular road addresses and save them on the linear location and roadway tables.
    *
    * @param projectID : Long - The project Id
    * @return
    */
  private def preserveProjectToDB(projectID: Long): Unit = {
    projectDAO.fetchTRIdByProjectId(projectID) match {
      case Some(trId) =>
        val roadNumbers: Option[Set[Long]] = projectDAO.fetchProjectStatus(projectID).map { currentState =>
          logger.info(s"Current status is $currentState")
          if (currentState == UpdatingToRoadNetwork) {
            logger.info(s"Start importing road addresses of project $projectID to the road network")
            try {
              updateRoadwaysAndLinearLocationsWithProjectLinks(projectID)
            } catch {
              case t: InvalidAddressDataException => {
                throw t  // Rethrow the unexpected error.
              }
            }
          } else Set.empty[Long]
        }
        if (roadNumbers.isEmpty || roadNumbers.get.isEmpty) {
          logger.error(s"No road numbers available in project $projectID")
        } else if (roadNetworkDAO.hasCurrentNetworkErrorsForOtherNumbers(roadNumbers.get)) {
          logger.error(s"Current network have errors for another project roads, solve Them first.")
        } else {
          val currNetworkVersion = roadNetworkDAO.getLatestRoadNetworkVersionId
          if (currNetworkVersion.isDefined)
            eventbus.publish("roadAddress:RoadNetworkChecker", RoadCheckOptions(Seq(), roadNumbers.get, currNetworkVersion, currNetworkVersion.get + 1L, throughActor = true))
          else logger.info(s"There is no published version for the network so far")
        }
      case None =>
        logger.info(s"During status checking VIITE wasn't able to find TR_ID to project $projectID")
        appendStatusInfo(fetchProjectById(projectID).head, " Failed to find TR-ID ")
    }
  }

  //TODO: Currently only used on the Project Service Spec, can it be removed?
  def createSplitRoadAddress(roadAddress: RoadAddress, split: Seq[ProjectLink], project: Project): Seq[RoadAddress] = {
    def transferValues(terminated: Option[ProjectLink]): (Long, Long, Double, Double) = {
      terminated.map(termLink =>
        termLink.sideCode match {
          case AgainstDigitizing =>
            if (termLink.startAddrMValue == roadAddress.startAddrMValue)
              (termLink.endAddrMValue, roadAddress.endAddrMValue,
                roadAddress.startMValue, termLink.startMValue)
            else (roadAddress.startAddrMValue, termLink.startAddrMValue,
              termLink.endMValue, roadAddress.endMValue)
          case _ =>
            if (termLink.startAddrMValue == roadAddress.startAddrMValue)
              (termLink.endAddrMValue, roadAddress.endAddrMValue,
                termLink.endMValue, roadAddress.endMValue)
            else (roadAddress.startAddrMValue, termLink.startAddrMValue,
              roadAddress.startMValue, termLink.startMValue)
        }
      ).getOrElse(roadAddress.startAddrMValue, roadAddress.endAddrMValue, roadAddress.startMValue, roadAddress.endMValue)
    }

    split.flatMap(pl => {
      pl.status match {
        case UnChanged =>
          Seq(roadAddress.copy(id = NewIdValue, startAddrMValue = pl.startAddrMValue, endAddrMValue = pl.endAddrMValue, createdBy = Some(project.createdBy), linkId = pl.linkId, startMValue = pl.startMValue, endMValue = pl.endMValue, adjustedTimestamp = pl.linkGeometryTimeStamp, geometry = pl.geometry))
        case New =>
          Seq(RoadAddress(NewIdValue, pl.linearLocationId, pl.roadNumber, pl.roadPartNumber, pl.administrativeClass, pl.track, pl.discontinuity,
            pl.startAddrMValue, pl.endAddrMValue, Some(project.startDate), None, Some(project.createdBy), pl.linkId,
            pl.startMValue, pl.endMValue, pl.sideCode, pl.linkGeometryTimeStamp, pl.calibrationPoints,
            pl.geometry, pl.linkGeomSource, pl.ely, terminated = NoTermination, NewIdValue))
        case Transfer => // TODO if the whole roadway -segment is transferred, keep the original roadway_number, otherwise generate new ids for the different segments
          val (startAddr, endAddr, startM, endM) = transferValues(split.find(_.status == Terminated))
          Seq(
            //TODO we should check situations where we need to create one new roadway for new and transfer/unchanged
            // Transferred part, original values
            roadAddress.copy(id = NewIdValue, startAddrMValue = startAddr, endAddrMValue = endAddr, endDate = Some(project.startDate.minusDays(1)), createdBy = Some(project.createdBy), startMValue = startM, endMValue = endM),
            // Transferred part, new values
            roadAddress.copy(id = NewIdValue, startAddrMValue = pl.startAddrMValue, endAddrMValue = pl.endAddrMValue, startDate = Some(project.startDate), createdBy = Some(project.createdBy), linkId = pl.linkId, startMValue = pl.startMValue, endMValue = pl.endMValue, adjustedTimestamp = pl.linkGeometryTimeStamp, geometry = pl.geometry)
          )
        case Terminated => // TODO Check roadway_number
          Seq(roadAddress.copy(id = NewIdValue, startAddrMValue = pl.startAddrMValue, endAddrMValue = pl.endAddrMValue, endDate = Some(project.startDate.minusDays(1)), createdBy = Some(project.createdBy), linkId = pl.linkId, startMValue = pl.startMValue, endMValue = pl.endMValue, adjustedTimestamp = pl.linkGeometryTimeStamp, geometry = pl.geometry, terminated = Termination))
        case _ =>
          logger.error(s"Invalid status for split project link: ${pl.status} in project ${pl.projectId}")
          throw new InvalidAddressDataException(s"Invalid status for split project link: ${pl.status}")
      }
    })
  }

  /**
    * Expires roadways (valid_to = current_timestamp)
    *
    * @param roadwayId The roadwayId to expire
    */
  def expireHistoryRows(roadwayId: Long): Int = {
    roadwayDAO.expireHistory(Set(roadwayId))
  }

  /**
    *
    * @throws RuntimeException when the given project is not in UpdatingToRoadNetwork state when starting.
    * @throws InvalidAddressDataException when there are no links to process.
    * @throws re-throws explicitly ProjectValidationException, SQLException, Exception
    */
  def updateRoadwaysAndLinearLocationsWithProjectLinks(projectID: Long): Set[Long] = {
    implicit def datetimeOrdering: Ordering[DateTime] = Ordering.fromLessThan(_ isAfter _)
    def handleRoadPrimaryTables(currentRoadways: Map[Long, Roadway], historyRoadways: Map[Long, Roadway], roadwaysToInsert: Iterable[Roadway], historyRoadwaysToKeep: Seq[Long], linearLocationsToInsert: Iterable[LinearLocation], project: Project): Seq[Long] = {
      logger.debug(s"Creating history rows based on operation")
      linearLocationDAO.expireByRoadwayNumbers((currentRoadways ++ historyRoadways).map(_._2.roadwayNumber).toSet)
      (currentRoadways ++ historyRoadways.filterNot(hist => historyRoadwaysToKeep.contains(hist._1))).map(roadway => expireHistoryRows(roadway._1))
      logger.debug(s"Inserting roadways (history + current)")

      // If the project created completely new roadways due to splitting or some other reason we will modify roadwaysToInsert list and its roadways to have reversed values set correctly
      // get reversed roadways from roadwaysToInsert
      val reversedRoadwaysToInsert = roadwaysToInsert.filter(roadway => roadway.reversed && roadway.endDate.isEmpty)
      // get roadwaynumbers from reversed roadways toSet (might be more than one)
      val reversedRoadwayNumbers = reversedRoadwaysToInsert.map(roadway => roadway.roadwayNumber).toSet
      // save not reversed roadways to a list (these roadways will remain the way they are)
      val notReversedRoadwaysToInsert = roadwaysToInsert.filter(roadway => !reversedRoadwayNumbers.contains(roadway.roadwayNumber)).toList
      // get reversed roadwayrows and their historyrows from roadwaysToInsert list with the roadwaynumbers
      val reversedRoadwaysAndHistoryRows = roadwaysToInsert.filter(roadway => reversedRoadwayNumbers.contains(roadway.roadwayNumber)).toList
      val roadwaysToCreate = {
        // Use ListBuffer as a mutable list
        val returnRoadways = new ListBuffer[Roadway]()
        for (rwn <- reversedRoadwayNumbers) { // loop through reversed roadwaynumbers
          // get roadways that have the same roadwayNumber as the current loop variable
          val roadways = reversedRoadwaysAndHistoryRows.filter(roadway => roadway.roadwayNumber == rwn)
          // save the current and newest history to a variable for later use
          val currentAndNewestHistoryRow = roadways.sortBy(_.endDate).take(2)
          // get rid of the current and newest history row because they are always reversed = 1 if a road is reversed
          val historyRowsWithoutCurrentAndNewestHistory = roadways.sortBy(_.endDate).drop(2)
          if (historyRowsWithoutCurrentAndNewestHistory.length < 1) { // check if there are history rows that need to have reversed value flipped
            // add the current and newest history rows back to the list buffer
            currentAndNewestHistoryRow.foreach(returnRoadways += _)
          }
          else { // if there is history rows that need reversed values to be flipped
            val editedRoadways = historyRowsWithoutCurrentAndNewestHistory.map(row => // flip the reversed value in the history rows
              if (row.reversed) {
                row.copy(reversed = false)
              }
              else {
                row.copy(reversed = true)
              }
            )
            // add the current and newest history rows back to the list buffer
            currentAndNewestHistoryRow.foreach(returnRoadways += _)
            // add the edited history rows back to the list buffer
            editedRoadways.foreach(returnRoadways += _)
          }
        }
        // change the listbuffer to a immutable list
        val returnRoadwaysList = returnRoadways.toList
        // concatenate the untouched roadways and the modified roadways lists
        returnRoadwaysList ++ notReversedRoadwaysToInsert
      }

      val roadwayIds = roadwayDAO.create(roadwaysToCreate.filter(roadway => roadway.endDate.isEmpty || !roadway.startDate.isAfter(roadway.endDate.get)).map(_.copy(createdBy = project.createdBy)))
      logger.debug(s"Inserting linear locations")
      linearLocationDAO.create(linearLocationsToInsert, createdBy = project.createdBy)
      // If the project didnt create new roadway numbers we will flip the existing history rows after Viite has created the new "current" roadway and newest history row of the previous "current" roadway
      for(rwn <- reversedRoadwayNumbers) { // loop trough reversed roadway numbers
        // get roadways that have the same roadwayNumber as the current loop variable
        val roadways = reversedRoadwaysAndHistoryRows.filter(roadway => roadway.roadwayNumber == rwn)
        // get rid of the current and newest history row because they already have reversed = 1 if a road is reversed
        val historyRowsWithoutCurrentAndNewestHistory = roadways.sortBy(_.endDate).drop(2)
        // If the project didnt create new roadway numbers, we will flip the existing history rows
        if (historyRowsWithoutCurrentAndNewestHistory.length < 1) {
          // fetch all roadwayrows with the current loop variable that are reversed + their history rows
          val roadwayRows = roadwayDAO.fetchAllByRoadwayNumbers(reversedRoadwayNumbers.filter(roadwayNumber => roadwayNumber == rwn), true).toList
          //get rid of current row and newest history row as they are already reversed = 1
          val roadwayRowsWithoutCurrentAndNewestHistoryRow = roadwayRows.sortBy(_.endDate).drop(2)
          // get ids of the history rows that need to be flipped
          val roadwayRowIds = roadwayRowsWithoutCurrentAndNewestHistoryRow.map(roadwayRow => roadwayRow.id).toSet
          //Flip the reversed tags (0->1 or 1->0) of the history rows with given ids
          roadwayDAO.updateReversedTagsInHistoryRows(roadwayRowIds)
        }
      }
      roadwayIds
    }

    /* @throws RuntimeException whe the given project is not in UpdatingToRoadNetwork state
     * @throws InvalidAddressDataException when there are no links to process. */
    def handleRoadComplementaryTables(roadwayChanges: List[ProjectRoadwayChange], projectLinkChanges: Seq[ProjectRoadLinkChange], linearLocationsToInsert: Iterable[LinearLocation],
                                      roadwayIds: Seq[Long], generatedRoadways: Seq[(Seq[Roadway], Seq[LinearLocation], Seq[ProjectLink])], projectLinks: Seq[ProjectLink],
                                      endDate: Option[DateTime], nodeIds: Seq[Long], username: String): Unit = {
      logger.debug(s"Updating and inserting roadway points")
      roadAddressService.handleRoadwayPointsUpdate(roadwayChanges, projectLinkChanges, username)

      logger.debug(s"Updating and inserting calibration points")
      val linearLocations: Iterable[LinearLocation] = linearLocationsToInsert.filter(l => generatedRoadways.flatMap(_._1).filter(_.endDate.isEmpty).map(_.roadwayNumber).contains(l.roadwayNumber))
      roadAddressService.handleProjectCalibrationPointChanges(linearLocations, username, projectLinkChanges.filter(_.status == LinkStatus.Terminated))
      logger.debug(s"Creating nodes and junctions templates")

      val mappedRoadAddressesProjection: Seq[RoadAddress] = roadAddressService.getRoadAddressesByRoadwayIds(roadwayIds)
      val roadwayLinks = if (generatedRoadways.flatMap(_._3).nonEmpty) generatedRoadways.flatMap(_._3) else projectLinks
      val (enrichedProjectLinks: Seq[ProjectLink], enrichedProjectRoadLinkChanges: Seq[ProjectRoadLinkChange]) = ProjectChangeFiller.mapAddressProjectionsToLinks(
        roadwayLinks, projectLinkChanges, mappedRoadAddressesProjection)

      nodesAndJunctionsService.handleJunctionAndJunctionPoints(roadwayChanges, enrichedProjectLinks, enrichedProjectRoadLinkChanges, username)
      nodesAndJunctionsService.handleNodePoints(roadwayChanges, enrichedProjectLinks, enrichedProjectRoadLinkChanges, username)
      logger.debug(s"Expiring obsolete nodes and junctions")
      val expiredJunctionPoints = nodesAndJunctionsService.expireObsoleteNodesAndJunctions(enrichedProjectLinks, endDate, username)
      logger.debug(s"Expiring obsolete calibration points in ex junction places")
      roadAddressService.expireObsoleteCalibrationPointsInJunctions(expiredJunctionPoints)
      logger.debug(s"Handling road names")
      handleNewRoadNames(roadwayChanges)
      handleRoadNames(roadwayChanges)
      handleTerminatedRoadwayChanges(roadwayChanges)
      ProjectLinkNameDAO.removeByProject(projectID)
      nodesAndJunctionsService.calculateNodePointsForNodes(nodeIds, username)
    }

    projectDAO.fetchProjectStatus(projectID) match {
      case Some(pState) => {
        if (pState == UpdatingToRoadNetwork) {
          /* just continue */
        }
        else {
          logger.error(s"Project state not at UpdatingToRoadNetwork: $pState")
          throw new RuntimeException(s"Project state not at UpdatingToRoadNetwork: $pState")
        }
      }
      case None => {
        logger.error(s"Project $projectID was not found (and thus, state not at UpdatingToRoadNetwork)")
        throw new RuntimeException(s"Project $projectID was not found when fetching ProjectStatus")
      }
    }

    val project = projectDAO.fetchById(projectID).get
    val nodeIds = nodeDAO.fetchNodeNumbersByProject(projectID)
    /* Remove userdefined calibrationpoints from calculation. Assume udcp:s defined at projectlink splits. */
    val projectLinks = projectLinkDAO.fetchProjectLinks(projectID).map(pl => if (pl.calibrationPointTypes._2 == CalibrationPointDAO.CalibrationPointType.UserDefinedCP) pl.copy(calibrationPointTypes = (pl.calibrationPointTypes._1, CalibrationPointDAO.CalibrationPointType.NoCP)) else pl )
    val projectLinkChanges = projectLinkDAO.fetchProjectLinksChange(projectID)
    val currentRoadways = roadwayDAO.fetchAllByRoadwayId(projectLinks.map(pl => pl.roadwayId)).map(roadway => (roadway.id, roadway)).toMap
    val historyRoadways = roadwayDAO.fetchAllByRoadwayNumbers(currentRoadways.map(_._2.roadwayNumber).toSet, withHistory = true).filter(_.endDate.isDefined).map(roadway => (roadway.id, roadway)).toMap

    def findHistoryRoadways(rwnum: Long):Seq[Roadway] = {
      historyRoadways.filter(_._2.roadwayNumber == rwnum).values.toSeq
    }

    val roadwayChanges = roadwayChangesDAO.fetchRoadwayChanges(Set(projectID))
    val mappedRoadwayChanges = currentRoadways.values.map(r => RoadwayFiller.RwChanges(r, findHistoryRoadways(r.roadwayNumber), projectLinks.filter(_.roadwayId == r.id))).toList

    if (projectLinks.isEmpty) {
      logger.error(s" There are no addresses to update, rollbacking update of project ${project.id}")
      throw new InvalidAddressDataException(s"There were no addresses to update in project ${project.id}")
    }
    logger.debug(s"Moving project links to project link history.")
    projectLinkDAO.moveProjectLinksToHistory(projectID)

    try {
      val new_generatedRoadways = RoadwayFiller.applyNewLinks(projectLinks.filter(_.status == LinkStatus.New))
      val generatedRoadways = RoadwayFiller.applyRoadwayChanges(mappedRoadwayChanges).flatten.filter(_._1.nonEmpty) ++ new_generatedRoadways
      val historyRoadwaysToKeep = generatedRoadways.flatMap(_._1).filter(_.id != NewIdValue).map(_.id)
      var linearLocationsToInsert = generatedRoadways.flatMap(_._2).groupBy(l => (l.roadwayNumber, l.orderNumber, l.linkId,
        l.startMValue, l.endMValue, l.validTo.map(_.toYearMonthDay), l.startCalibrationPoint.addrM, l.endCalibrationPoint.addrM, l.sideCode,
        l.linkGeomSource)).map(_._2.head).toSeq

      val roadwaysToInsert = generatedRoadways.flatMap(_._1).filter(_.id == NewIdValue).filter(_.endDate.isEmpty).groupBy(_.track).flatMap(g => (g._2.groupBy(_.roadwayNumber).map(t => t._2.head.copy(startAddrMValue = t._2.minBy(_.startAddrMValue).startAddrMValue, endAddrMValue = t._2.maxBy(_.endAddrMValue).endAddrMValue)))) ++ generatedRoadways.flatMap(_._1).filter(_.id == NewIdValue).filter(_.endDate.nonEmpty)
      roadwaysToInsert.foreach(rwtoinsert => logger.info(s"roadwaysToInsert ${rwtoinsert.roadwayNumber} ${rwtoinsert.endDate} ${rwtoinsert.validTo}"))

      /* Fuse linearlocations with the same roadwaynumber and linkid. */
      val linsToFuse = linearLocationsToInsert.groupBy(ll => (ll.roadwayNumber, ll.linkId)).values.filter(_.size > 1)
      val linsToFuseIds = linsToFuse.flatten.map(_.id).toSeq
      val fusedLinearLocations = linsToFuse.map(lls => {
        val maxOrderNum = lls.map(_.orderNumber).max
        val firstLl = lls.find(_.orderNumber == 1).get
        val lastLl = lls.find(_.orderNumber == maxOrderNum).get
        val geometries = lls.sortBy(_.orderNumber).flatMap(_.geometry).distinct
        firstLl.copy(calibrationPoints = (lastLl.startCalibrationPoint, lastLl.endCalibrationPoint), geometry = geometries)
      })
      linearLocationsToInsert = linearLocationsToInsert.filterNot(ll => linsToFuseIds.contains(ll.id)) ++ fusedLinearLocations

      /* Update order numbers. */
      val rwns = generatedRoadways.flatMap(_._1).map(g => g.roadwayNumber).distinct
      linearLocationsToInsert = rwns.flatMap(r => {
        val lins = linearLocationsToInsert.filter(l => l.roadwayNumber == r).toList
        val lins_link_ids = lins.map(_.linkId)
        val sorted_pls = projectLinks.filter(pl => lins_link_ids.contains(pl.linkId)).sortBy(_.startAddrMValue)
        val sorted_lins: Seq[LinearLocation] = sorted_pls.flatMap(pl => lins.filter(l => l.linkId == pl.linkId && l.startMValue == pl.startMValue).sortBy(_.startMValue) )
        sorted_lins.zip(1 to lins.size).map(ls => ls._1.copy(orderNumber =  ls._2))
      })

      val roadwayIds = handleRoadPrimaryTables(currentRoadways, historyRoadways, roadwaysToInsert, historyRoadwaysToKeep, linearLocationsToInsert, project)
      handleRoadComplementaryTables(roadwayChanges, projectLinkChanges, linearLocationsToInsert,
        roadwayIds, generatedRoadways, projectLinks,
        Some(project.startDate.minusDays(1)), nodeIds, project.createdBy)

      nodesAndJunctionsService.publishNodes(nodeIds, project.createdBy)
      projectLinks.map(_.roadNumber).toSet
    } catch {
      case e: ProjectValidationException =>
        logger.error("Failed to validate project message:" + e.getMessage)
        throw e
      case f: SQLException =>
        logger.error("Failed to update roadways and linear locations with project links due to SQL error.", f)
        throw f
      case ex: Exception =>
        logger.error("Failed to update roadways and linear locations with project links.", ex)
        throw ex
    }
  }

  def handleRoadNames(roadwayChanges: Seq[ProjectRoadwayChange]): Unit = {
    roadwayChanges.foreach(rwc => {
      val srcRoadNumberOptional = rwc.changeInfo.source.roadNumber
      val targetRoadNumberOptional = rwc.changeInfo.target.roadNumber
      if ((rwc.changeInfo.changeType.equals(AddressChangeType.ReNumeration) || rwc.changeInfo.changeType.equals(AddressChangeType.Transfer)
        && targetRoadNumberOptional.isDefined && srcRoadNumberOptional.isDefined)) {
        val srcRoadNumber = srcRoadNumberOptional.get
        val targetRoadNumber = targetRoadNumberOptional.get
        if (srcRoadNumber != targetRoadNumber) {
          val srcExistingRoadName = RoadNameDAO.getLatestRoadName(srcRoadNumber)
          val targetExistingRoadName = RoadNameDAO.getLatestRoadName(targetRoadNumber)

          val srcExistingRoadways = roadwayDAO.fetchAllByRoad(srcRoadNumber)
          val targetExistingRoadways = roadwayDAO.fetchAllByRoad(targetRoadNumber)

          if (srcExistingRoadways.isEmpty && srcExistingRoadName.isDefined) {
            RoadNameDAO.expireAndCreateHistory(srcExistingRoadName.get.id, rwc.user, historyRoadName = srcExistingRoadName.get.copy(endDate = Some(rwc.projectStartDate.minusDays(1))))
          }

          // CREATE NEW ROADNAME FOR TARGET ROADNUMBER
          val projectLinkNames = ProjectLinkNameDAO.get(Set(targetRoadNumber), rwc.projectId)
          if (projectLinkNames.nonEmpty && targetExistingRoadways.nonEmpty && targetExistingRoadName.isEmpty) {
            RoadNameDAO.create(Seq(RoadName(NewIdValue, targetRoadNumber, projectLinkNames.head.roadName, startDate = Some(rwc.projectStartDate), validFrom = Some(DateTime.now()), createdBy = rwc.user)))
          }
        }
      }
    })
  }

  def handleTerminatedRoadwayChanges(roadwayChanges: Seq[ProjectRoadwayChange]): Unit = {
    roadwayChanges.foreach(rwc => {
      val roadNumberOptional = rwc.changeInfo.source.roadNumber
      if (rwc.changeInfo.changeType.equals(AddressChangeType.Termination) && roadNumberOptional.isDefined) {
        val roadNumber = roadNumberOptional.get
        val roadways = roadwayDAO.fetchAllByRoad(roadNumber)
        val roadNameOpt = RoadNameDAO.getLatestRoadName(roadNumber)
        if (roadways.isEmpty && roadNameOpt.isDefined) {
          RoadNameDAO.expireAndCreateHistory(roadNameOpt.get.id, rwc.user, historyRoadName = roadNameOpt.get.copy(endDate = Some(rwc.projectStartDate.minusDays(1))))
        }
      }
    })
  }

  def handleNewRoadNames(roadwayChanges: Seq[ProjectRoadwayChange]): Unit = {
    val roadNames = roadwayChanges.flatMap(rwc => {
      val roadNumberOptional = rwc.changeInfo.target.roadNumber
      if (rwc.changeInfo.changeType.equals(AddressChangeType.New) && roadNumberOptional.isDefined) {
        val roadNumber = roadNumberOptional.get
        val existingRoadNames = RoadNameDAO.getCurrentRoadNamesByRoadNumber(roadNumber)
        val projectLinkNames = ProjectLinkNameDAO.get(Set(roadNumber), rwc.projectId)
        if (existingRoadNames.isEmpty && projectLinkNames.nonEmpty) {
          Some(RoadName(NewIdValue, roadNumber, projectLinkNames.head.roadName, startDate = Some(rwc.projectStartDate), validFrom = Some(DateTime.now()), createdBy = rwc.user))
        } else {
          None
        }
      }
      else None
    }).groupBy(_.roadNumber)

    if (roadNames.nonEmpty && roadNames.values.nonEmpty) {
      roadNames.values.foreach(rn => {
        RoadNameDAO.create(Seq(rn.head))
      })
      logger.info(s"Found ${roadNames.size} names in project that differ from road address name")
    }
  }

  def getProjectEly(projectId: Long): Seq[Long] = {
    withDynSession {
      projectDAO.fetchProjectElyById(projectId)
    }
  }

  /**
    * Main validator method.
    * Calls and executes all the validations we have for a project.
    *
    * @param projectId  : Long - Project ID
    * @param newSession : Boolean - Will determine if we open a new database sesssion
    * @return A sequence of validation errors, can be empty.
    */
  def validateProjectById(projectId: Long, newSession: Boolean = true): Seq[projectValidator.ValidationErrorDetails] = {
    if (newSession) {
      withDynSession {
        projectValidator.validateProject(fetchProjectById(projectId).get, projectLinkDAO.fetchProjectLinks(projectId))
      }
    }
    else {
      projectValidator.validateProject(fetchProjectById(projectId).get, projectLinkDAO.fetchProjectLinks(projectId))
    }
  }

  def validateProjectByIdHighPriorityOnly(projectId: Long, newSession: Boolean = true): Seq[projectValidator.ValidationErrorDetails] = {
    if (newSession) {
      withDynSession {
        projectValidator.projectLinksHighPriorityValidation(fetchProjectById(projectId).get, projectLinkDAO.fetchProjectLinks(projectId))
      }
    }
    else {
      projectValidator.projectLinksHighPriorityValidation(fetchProjectById(projectId).get, projectLinkDAO.fetchProjectLinks(projectId))
    }
  }

  def validateLinkTrack(track: Int): Boolean = {
    Track.values.filterNot(_.value == Track.Unknown.value).exists(_.value == track)
  }

  def getRoadAddressesFromFormedRoadPart(roadNumber: Long, roadPartNumber: Long, projectId: Long) = {
    withDynSession {
      val roadAddresses = projectLinkDAO.fetchByProjectRoadPart(roadNumber, roadPartNumber, projectId)
        .filter(part => part.roadAddressRoadNumber.isDefined && part.roadAddressRoadPart.isDefined
          && (part.roadAddressRoadNumber.get != roadNumber || part.roadAddressRoadPart.get != roadPartNumber))
        .map(roadAddress => (roadAddress.roadAddressRoadNumber.get, roadAddress.roadAddressRoadPart.get, roadAddress.status == LinkStatus.Numbering))
        .map {
          ra => Map("roadAddressNumber" -> ra._1.toString, "roadAddressPartNumber" -> ra._2.toString, "isNumbering" -> ra._3)
        }
      roadAddresses.toSet
    }
  }

  case class PublishResult(validationSuccess: Boolean, sendSuccess: Boolean, errorMessage: Option[String])

}

class SplittingException(s: String) extends RuntimeException {
  override def getMessage: String = s
}

case class ProjectBoundingBoxResult(projectLinkResultF: Future[Seq[ProjectLink]], roadLinkF: Future[Seq[RoadLink]],
                                    complementaryF: Future[Seq[RoadLink]])

case class ProjectRoadLinkChange(id: Long, roadwayId: Long, originalLinearLocationId: Long, linearLocationId: Long, originalRoadNumber: Long, originalRoadPartNumber: Long, roadNumber: Long, roadPartNumber: Long, originalStartAddr: Long, originalEndAddr: Long, newStartAddr: Long, newEndAddr: Long, status: LinkStatus, reversed: Boolean, originalRoadwayNumber: Long, newRoadwayNumber: Long)



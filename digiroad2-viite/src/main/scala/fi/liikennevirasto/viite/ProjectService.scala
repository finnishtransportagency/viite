package fi.liikennevirasto.viite

import java.io.IOException
import java.sql.SQLException
import java.util.Date

import fi.liikennevirasto.digiroad2._
import fi.liikennevirasto.digiroad2.asset.SideCode.AgainstDigitizing
import fi.liikennevirasto.digiroad2.asset.{BoundingRectangle, LinkGeomSource, TrafficDirection, _}
import fi.liikennevirasto.digiroad2.client.vvh.{VVHClient, VVHHistoryRoadLink, VVHRoadlink}
import fi.liikennevirasto.digiroad2.dao.Sequences
import fi.liikennevirasto.digiroad2.linearasset.{RoadLink, RoadLinkLike}
import fi.liikennevirasto.digiroad2.oracle.OracleDatabase
import fi.liikennevirasto.digiroad2.service.RoadLinkService
import fi.liikennevirasto.digiroad2.util.LogUtils.time
import fi.liikennevirasto.digiroad2.util.{RoadAddressException, RoadPartReservedException, Track}
import fi.liikennevirasto.viite.dao.CalibrationPointDAO.UserDefinedCalibrationPoint
import fi.liikennevirasto.viite.dao.Discontinuity.Continuous
import fi.liikennevirasto.viite.dao.FloatingReason.NoFloating
import fi.liikennevirasto.viite.dao.LinkStatus._
import fi.liikennevirasto.viite.dao.ProjectState._
import fi.liikennevirasto.viite.dao.TerminationCode.{NoTermination, Termination}
import fi.liikennevirasto.viite.dao.{LinkStatus, ProjectDAO, RoadwayDAO, _}
import fi.liikennevirasto.viite.model.{ProjectAddressLink, RoadAddressLink}
import fi.liikennevirasto.viite.process._
import fi.liikennevirasto.viite.util.{ProjectLinkSplitter, SplitOptions, SplitResult}
import org.apache.http.client.ClientProtocolException
import org.joda.time.DateTime
import org.slf4j.LoggerFactory
import slick.driver.JdbcDriver.backend.Database.dynamicSession

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future}
import scala.util.control.NonFatal

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

class ProjectService(roadAddressService: RoadAddressService, roadLinkService: RoadLinkService, eventbus: DigiroadEventBus, frozenTimeVVHAPIServiceEnabled: Boolean = false) {

  def withDynTransaction[T](f: => T): T = OracleDatabase.withDynTransaction(f)

  def withDynSession[T](f: => T): T = OracleDatabase.withDynSession(f)

  private val logger = LoggerFactory.getLogger(getClass)
  val roadwayDAO = new RoadwayDAO
  val linearLocationDAO = new LinearLocationDAO
  val projectDAO = new ProjectDAO
  val projectLinkDAO = new ProjectLinkDAO
  val projectReservedPartDAO = new ProjectReservedPartDAO
  val roadwayChangesDAO = new RoadwayChangesDAO
  val projectValidator = new ProjectValidator
  val roadwayAddressMapper = new RoadwayAddressMapper(roadwayDAO, linearLocationDAO)
  val allowedSideCodes = List(SideCode.TowardsDigitizing, SideCode.AgainstDigitizing)
  val roadAddressLinkBuilder = new RoadAddressLinkBuilder(roadwayDAO, linearLocationDAO, projectLinkDAO)

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

  /**
    * Creates the new project
    * Adds the road addresses from the reserved parts to the project link table
    *
    * @param roadAddressProject
    * @return the created project
    */
  private def createProject(roadAddressProject: Project): Project = {
    val id = Sequences.nextViitePrimaryKeySeqValue
    val project = roadAddressProject.copy(id = id)
    projectDAO.create(project)
    val error = addLinksToProject(project)
    if (error.nonEmpty)
      throw new RoadPartReservedException(error.get)
    projectDAO.fetchById(id).get
  }

  private def projectFound(roadAddressProject: Project): Option[Project] = {
    val newRoadAddressProject = 0
    if (roadAddressProject.id == newRoadAddressProject) return None
    withDynTransaction {
      return projectDAO.fetchById(roadAddressProject.id)
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

  def checkRoadPartsReservable(roadNumber: Long, startPart: Long, endPart: Long): Either[String, Seq[ProjectReservedPart]] = {
    (startPart to endPart).foreach(part =>
      projectReservedPartDAO.roadPartReservedByProject(roadNumber, part) match {
        case Some(name) => return Left(s"Tie $roadNumber osa $part ei ole vapaana projektin alkupäivämääränä. Tieosoite on jo varattuna projektissa: $name.")
        case _ =>
      })
    Right((startPart to endPart).flatMap(part => getAddressPartInfo(roadNumber, part))
    )
  }

  /**
    * Validator method, this is in charge of evaluating if a combination of road number and road part number already exists in our roadway records.
    * If it does not then we check if this project is able to reserve the combination.
    * If the combination is already reserved in this project we simply return their parts, if not we validate the project date with the dates of the road parts.
    * If the validation of the date passes then we return these road parts.
    * IN ANY OTHER INSTANCE we return a error message detailing what the problem was
    * @param roadNumber: Long
    * @param startPart: Long - road part number of the start of the reservation
    * @param endPart: Long - road part number that ends the reservation
    * @param projectDate: DateTime
    * @return Either the error message or the reserved road parts.
    */
  def checkRoadPartExistsAndReservable(roadNumber: Long, startPart: Long, endPart: Long, projectDate: DateTime): Either[String, Seq[ProjectReservedPart]] = {
    withDynTransaction {
      checkRoadPartsExist(roadNumber, startPart, endPart) match {
        case None => checkRoadPartsReservable(roadNumber, startPart, endPart) match {
          case Left(err) => Left(err)
          case Right(reservedRoadParts) =>
            if (reservedRoadParts.isEmpty) {
              Right(reservedRoadParts)
            } else {
              validateProjectDate(reservedRoadParts, projectDate) match {
                case Some(errMsg) => Left(errMsg)
                case None => Right(reservedRoadParts)
              }
            }
        }
        case Some(error) => Left(error)
      }
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

  def projectWritableCheckInSession(projectId: Long): Option[String] = {
    projectDAO.fetchProjectStatus(projectId) match {
      case Some(projectState) =>
        if (projectState == ProjectState.Incomplete || projectState == ProjectState.ErrorInViite || projectState == ProjectState.ErrorInTR)
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
    * @param reservedParts -Sequence of ProjectReservedParts
    * @param date: DateTime -  Project Date
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

  private def getAddressPartInfo(roadNumber: Long, roadPart: Long): Option[ProjectReservedPart] = {
    projectReservedPartDAO.fetchReservedRoadPart(roadNumber, roadPart).orElse(generateAddressPartInfo(roadNumber, roadPart))
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

  private def setProjectRoadName(projectId: Long, roadNumber: Long, roadName: String): Unit = {
    ProjectLinkNameDAO.get(roadNumber, projectId) match {
      case Some(projectLinkName) => ProjectLinkNameDAO.update(projectLinkName.id, roadName)
      case _ =>
        val existingRoadName = RoadNameDAO.getLatestRoadName(roadNumber)
        ProjectLinkNameDAO.create(projectId, roadNumber, existingRoadName.map(_.roadName).getOrElse(roadName))
    }
  }

  def writableWithValidTrack(projectId: Long, track: Int): Option[String] = {
    if (!isWritableState(projectId)) Some(projectNotWritable)
    else if (!validateLinkTrack(track)) Some("Ajoratakoodi puuttuu")
    else None
  }

  def createProjectLinks(linkIds: Seq[Long], projectId: Long, roadNumber: Long, roadPartNumber: Long, track: Track,
                         discontinuity: Discontinuity, roadType: RoadType, roadLinkSource: LinkGeomSource,
                         roadEly: Long, user: String, roadName: String, coordinates: Option[ProjectCoordinates] = None): Map[String, Any] = {
    withDynSession {
      writableWithValidTrack(projectId, track.value) match {
        case None =>
          val linkId = linkIds.head
          val roadLinks = (if (roadLinkSource != LinkGeomSource.SuravageLinkInterface) {
            roadLinkService.getRoadLinksByLinkIdsFromVVH(linkIds.toSet, frozenTimeVVHAPIServiceEnabled)
          } else {
            roadLinkService.getSuravageRoadLinksFromVVH(linkIds.toSet)
          }).map(l => l.linkId -> l).toMap
          if (roadLinks.keySet != linkIds.toSet)
            return Map("success" -> false,
              "errorMessage" -> (linkIds.toSet -- roadLinks.keySet).mkString(ErrorRoadLinkNotFound + " puuttuvat id:t ", ", ", ""))
          val project = projectDAO.fetchById(projectId).getOrElse(throw new RuntimeException(s"Missing project $projectId"))
          val existingProjectLinks = projectLinkDAO.fetchByProjectRoadPart(roadNumber, roadPartNumber, projectId)
          val reversed = if (existingProjectLinks.nonEmpty) existingProjectLinks.forall(_.reversed) else false

          val projectLinks: Seq[ProjectLink] = linkIds.toSet.map { id: Long =>
            newProjectLink(roadLinks(id), project, roadNumber, roadPartNumber, track, discontinuity, roadType, roadEly, roadName, reversed)
          }.toSeq
          if (coordinates.isDefined) {
            saveProjectCoordinates(project.id, coordinates.get)
          }
          else {
            saveProjectCoordinates(project.id, calculateProjectCoordinates(project.id))
          }
          setProjectEly(projectId, roadEly) match {
            case Some(errorMessage) => Map("success" -> false, "errorMessage" -> errorMessage)
            case None =>
              addNewLinksToProject(sortRamps(projectLinks.toSeq, linkIds), projectId, user, linkId, newTransaction = false) match {
                case Some(errorMessage) => Map("success" -> false, "errorMessage" -> errorMessage)
                case None => Map("success" -> true, "projectErrors" -> validateProjectById(projectId, newSession = false))
              }
          }
        case Some(error) => Map("success" -> false, "errorMessage" -> error)
      }
    }
  }

  def addNewLinksToProject(newLinks: Seq[ProjectLink], projectId: Long, user: String, firstLinkId: Long, newTransaction: Boolean = true): Option[String] = {
    if (newTransaction)
      withDynTransaction {
        addNewLinksToProjectInTX(newLinks, projectId, user, firstLinkId)
      }
    else
      addNewLinksToProjectInTX(newLinks, projectId, user, firstLinkId)

  }

  /**
    * Used when adding road address that do not have a previous address
    */
  private def addNewLinksToProjectInTX(newLinks: Seq[ProjectLink], projectId: Long, user: String, firstLinkId: Long): Option[String] = {
    val newRoadNumber = newLinks.head.roadNumber
    val newRoadPartNumber = newLinks.head.roadPartNumber
    val linkStatus = newLinks.head.status
    try {
      val project = getProjectWithReservationChecks(projectId, newRoadNumber, newRoadPartNumber, linkStatus, newLinks)

      if (!project.isReserved(newRoadNumber, newRoadPartNumber))
        projectReservedPartDAO.reserveRoadPart(project.id, newRoadNumber, newRoadPartNumber, project.modifiedBy)
      if (GeometryUtils.isNonLinear(newLinks))
        throw new ProjectValidationException(ErrorGeometryContainsBranches)
      // Determine address value scheme (ramp, roundabout, all others)
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
        } else
          newLinks
      projectLinkDAO.create(createLinks.map(_.copy(createdBy = Some(user))))
      recalculateProjectLinks(projectId, user, Set((newRoadNumber, newRoadPartNumber)))
      newLinks.flatMap(_.roadName).headOption.foreach { roadName =>
        setProjectRoadName(projectId, newRoadNumber, roadName)
      }
      None
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
      val roadLinks = roadLinkService.fetchVVHRoadLinksAndComplementaryFromVVH(linkIds) ++ roadLinkService.getSuravageRoadLinksFromVVH(linkIds)
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
    * @param projectId: Long - project id
    * @param roadNumber: Long - roadway number
    * @param roadPartNumber: Long - roadway part number
    * @param links: Sequence of project links - Project links targeted to reverse
    * @param coordinates: ProjectCoordinates - Coordinates for the project to jump to.
    * @param username: Sting - User
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
            val originalSideCodes = linearLocationDAO.fetchByRoadways(projectLinks.map(_.roadwayId).toSet)
              .map(l => l.id -> l.sideCode).toMap

            val originalAddresses = roadAddressService.getRoadAddressesByRoadwayIds(projectLinks.map(_.roadwayId))

            projectLinkDAO.updateProjectLinks(projectLinks.map(x =>
              x.copy(reversed = isReversed(originalSideCodes)(x),
                discontinuity = newContinuity.getOrElse(x.endAddrMValue, Discontinuity.Continuous))), username, originalAddresses)
            CalibrationPointDAO.removeAllCalibrationPoints(projectLinks.map(_.id).toSet)
            recalculateProjectLinks(projectId, username, Set((roadNumber, roadPartNumber)))
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
    * Checks if the road parts that user tries to reserve have different ely    *
    *
    * @param reservedRoadParts
    * @param projectEly
    * @param projectLinks
    * @param roadways
    * @return None in case of success, error code in case of failed validation
    */

  def validateReservations(reservedRoadParts: ProjectReservedPart, projectEly: Option[Long],
                           projectLinks: Seq[ProjectLink], roadways: Seq[Roadway], allPartsToReserve: Seq[ProjectReservedPart] = Seq.empty[ProjectReservedPart]): Option[String] = {
    val differingElyParts = allPartsToReserve.filter(pr => pr.ely != allPartsToReserve.head.ely)
    if (differingElyParts.nonEmpty) {
      val allDifferingElyParts = Seq(allPartsToReserve.head) ++ differingElyParts
      Some(s"$ErrorFollowingPartsHaveDifferingEly " ++ allDifferingElyParts.map(op => {
        s"TIE ${op.roadNumber} OSA: ${op.roadPartNumber} ELY: ${op.ely.getOrElse(-1)}"
      }).mkString(" ja "))
    } else if (roadways.isEmpty && !projectLinks.exists(pl => pl.roadNumber == reservedRoadParts.roadNumber && pl.roadPartNumber == reservedRoadParts.roadPartNumber))
      Some(s"$ErrorFollowingRoadPartsNotFoundInDB TIE ${reservedRoadParts.roadNumber} OSA: ${reservedRoadParts.roadPartNumber}")
    else if ((projectLinks.exists(_.ely != reservedRoadParts.ely.get) || roadways.exists(_.ely != reservedRoadParts.ely.get)) && (projectEly.isEmpty || projectEly.get != defaultProjectEly))
      Some(s"$ErrorFollowingPartsHaveDifferingEly TIE ${reservedRoadParts.roadNumber} OSA: ${reservedRoadParts.roadPartNumber}")
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
        validateReservations(reserved, project.ely, projectLinks, roadways, project.reservedParts) match {
          case Some(error) => throw new RoadPartReservedException(error)
          case _ =>
            val (suravageSource, regular) = linearLocationDAO.fetchByRoadways(roadways.map(_.roadwayNumber).toSet).partition(_.linkGeomSource == LinkGeomSource.SuravageLinkInterface)
            val suravageMapping = roadLinkService.getSuravageRoadLinksByLinkIdsFromVVH(suravageSource.map(_.linkId).toSet).map(sm => sm.linkId -> sm).toMap
            val regularMapping = roadLinkService.getRoadLinksByLinkIdsFromVVH(regular.map(_.linkId).toSet, frozenTimeVVHAPIServiceEnabled).map(rm => rm.linkId -> rm).toMap
            val fullMapping = regularMapping ++ suravageMapping
            val addresses = roadways.flatMap(r => roadwayAddressMapper.mapRoadAddresses(r, (suravageSource ++ regular).groupBy(_.roadwayNumber)(r.roadwayNumber)))
            checkAndReserve(project, reserved)
            logger.debug(s"Reserve done")
            addresses.map(ra => newProjectTemplate(fullMapping(ra.linkId), ra, project))
        }
      }
    }
    projectLinkDAO.create(newProjectLinks.filterNot(ad => projectLinks.exists(pl => pl.roadNumber == ad.roadNumber && pl.roadPartNumber == ad.roadPartNumber)))
    logger.debug(s"New links created ${newProjectLinks.size}")
    val linksOnRemovedParts = projectLinks.filterNot(pl => project.reservedParts.exists(_.holds(pl)))
    roadwayChangesDAO.clearRoadChangeTable(project.id)
    projectLinkDAO.removeProjectLinksById(linksOnRemovedParts.map(_.id).toSet)
    None
  }

  def revertSplit(projectId: Long, linkId: Long, userName: String): Option[String] = {
    withDynTransaction {
      val previousSplit = projectLinkDAO.fetchSplitLinks(projectId, linkId)
      if (previousSplit.nonEmpty) {
        revertSplitInTX(projectId, previousSplit, userName)
        None
      } else
        Some(s"No split for link id $linkId found!")
    }
  }

  private def revertSplitInTX(projectId: Long, previousSplit: Seq[ProjectLink], userName: String): Unit = {

    def getGeometryWithTimestamp(linkId: Long, timeStamp: Long, roadLinks: Seq[RoadLink],
                                 vvhHistoryLinks: Seq[VVHHistoryRoadLink]): Seq[Point] = {
      val matchingLinksGeometry = (roadLinks ++ vvhHistoryLinks).find(rl => rl.linkId == linkId && rl.vvhTimeStamp == timeStamp).map(_.geometry)
      if (matchingLinksGeometry.nonEmpty) {
        matchingLinksGeometry.get
      } else {
        (roadLinks ++ vvhHistoryLinks).find(rl => rl.linkId == linkId).map(_.geometry)
          .getOrElse(throw new InvalidAddressDataException(s"Geometry with linkId $linkId and timestamp $timeStamp not found!"))
      }
    }

    val (roadLinks, vvhHistoryLinks) = roadLinkService.getCurrentAndHistoryRoadLinksFromVVH(previousSplit.map(_.linkId).toSet)
    val (suravage, original) = previousSplit.partition(_.linkGeomSource == LinkGeomSource.SuravageLinkInterface)
    revertLinks(projectId, previousSplit.head.roadNumber, previousSplit.head.roadPartNumber,
      suravage.map(link => LinkToRevert(link.id, link.linkId, link.status.value, link.geometry)),
      original.map(link => LinkToRevert(link.id, link.linkId, link.status.value, getGeometryWithTimestamp(link.linkId,
        link.linkGeometryTimeStamp, roadLinks, vvhHistoryLinks))),
      userName, recalculate = false)
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
    * Performs the operations necessary to the preparation of the Suravage split.
    * This includes checking if the links in the context of this project are writable and fetching all previous split information and updating it to the current information.
    * After this, it will trigger the split without saving, and return the result to the UI, to give the user a chance to view the result and save it, if he desires.
    * @param linkId
    * @param userName
    * @param splitOptions
    * @return
    */
  def preSplitSuravageLink(linkId: Long, userName: String, splitOptions: SplitOptions): (Option[Seq[ProjectLink]], Seq[ProjectLink], Option[String], Option[(Point, Vector3d)]) = {

    def previousSplitToSplitOptions(plSeq: Seq[ProjectLink], splitOptions: SplitOptions): SplitOptions = {
      val splitsAB = plSeq.filter(_.linkId == linkId)
      val (template, splitA, splitB) = (plSeq.find(_.status == LinkStatus.Terminated),
        splitsAB.find(_.status != LinkStatus.New), splitsAB.find(_.status == LinkStatus.New))
      val linkData = splitA.orElse(splitB).orElse(template).map(l => (l.roadNumber, l.roadPartNumber,
        l.track, l.ely)).getOrElse((splitOptions.roadNumber, splitOptions.roadPartNumber, splitOptions.trackCode, splitOptions.ely))
      val discontinuity = splitsAB.filterNot(_.discontinuity == Continuous).map(_.discontinuity).headOption
      splitOptions.copy(statusA = splitA.map(_.status).getOrElse(splitOptions.statusA),
        roadNumber = linkData._1, roadPartNumber = linkData._2, trackCode = linkData._3, ely = linkData._4,
        discontinuity = discontinuity.getOrElse(Continuous))
    }

    withDynTransaction {
      if (isWritableState(splitOptions.projectId)) {
        val previousSplit = projectLinkDAO.fetchSplitLinks(splitOptions.projectId, linkId)
        val updatedSplitOptions =
          if (previousSplit.nonEmpty) {
            previousSplitToSplitOptions(previousSplit, splitOptions)
          } else
            splitOptions
        val (splitResultOption, errorMessage, splitVector) = preSplitSuravageLinkInTX(linkId, userName, updatedSplitOptions)
        dynamicSession.rollback()
        val allTerminatedProjectLinks = if (splitResultOption.isEmpty) {
          Seq.empty[ProjectLink]
        } else {
          splitResultOption.get.allTerminatedProjectLinks.map(fillRoadNames)
        }
        val splitWithMergeTerminated = splitResultOption.map(rs => rs.toSeqWithMergeTerminated.map(fillRoadNames))
        (splitWithMergeTerminated, allTerminatedProjectLinks, errorMessage, splitVector)
      }
      else (None, Seq(), None, None)
    }
  }

  /**
    * Performs the operations necessary to the split of the Suravage Link.
    * This starts by checking if the links in the context of this project are writable and checking that the tracks are valid.
    * After this, it will execute the split saving and save it.
    * @param track: Int - Track code
    * @param projectId: Long - ProjectId
    * @param coordinates: ProjectCoordinates - Coordinates of the center of the work area
    * @param linkId: Long - link id
    * @param username: String - user name
    * @param splitOptions: SplitOptions - Necessary support information to the split
    * @return
    */
  def splitSuravageLink(track: Int, projectId: Long, coordinates: ProjectCoordinates, linkId: Long, username: String,
                        splitOptions: SplitOptions): Option[String] = {
    withDynTransaction {
      writableWithValidTrack(projectId, track) match {
        case None =>
          saveProjectCoordinates(projectId, coordinates)
          splitSuravageLinkInTX(linkId, username, splitOptions)
        case Some(error) => Some(error)
      }
    }
  }

  /**
    * Main workhorse of the pre-split. This works by fetching the project and suravage links, with the suravage links we create a bounding box.
    * Using said bounding box we search for all project links that are inside of it, after we get the project links we get the vvh roadlinks related to them.
    * Afterwards we filter the project links, the filtering criteria is that they must be adjacent to the suravage links then we rank template links near suravage link by how much they overlap with suravage geometry.
    * With the ranking in place we chose the best fit and using that we have our split but we do not save it in the DB.
    * @param linkId: Long - linkId
    * @param username: String - User name
    * @param splitOptions
    * @return
    */
  def preSplitSuravageLinkInTX(linkId: Long, username: String,
                               splitOptions: SplitOptions): (Option[SplitResult], Option[String], Option[(Point, Vector3d)]) = {
    val projectId = splitOptions.projectId
    val sOption = roadLinkService.getSuravageRoadLinksByLinkIdsFromVVH(Set(Math.abs(linkId))).headOption
    val previousSplit = projectLinkDAO.fetchSplitLinks(projectId, linkId)
    val project = projectDAO.fetchById(projectId).get
    if (sOption.isEmpty) {
      (None, Some(ErrorSuravageLinkNotFound), None)
    } else {
      if (previousSplit.nonEmpty)
        revertSplitInTX(projectId, previousSplit, username)
      val suravageLink = sOption.get
      val endPoints = GeometryUtils.geometryEndpoints(suravageLink.geometry)
      val x = if (endPoints._1.x > endPoints._2.x) (endPoints._2.x, endPoints._1.x) else (endPoints._1.x, endPoints._2.x)
      val rightTop = Point(x._2, endPoints._2.y)
      val leftBottom = Point(x._1, endPoints._1.y)
      val projectLinks = getProjectLinksInBoundingBox(BoundingRectangle(leftBottom, rightTop), projectId)
      if (projectLinks.isEmpty)
        return (None, Some(ErrorNoMatchingProjectLinkForSplit), None)
      val roadLink = roadLinkService.getRoadLinkByLinkIdFromVVH(projectLinks.head.linkId)
      if (roadLink.isEmpty) {
        (None, Some(ErrorSuravageLinkNotFound), None)
      }

      val projectLinksConnected = projectLinks.filter(l => GeometryUtils.areAdjacent(l.geometry, suravageLink.geometry))

      //we rank template links near suravage link by how much they overlap with suravage geometry
      val commonSections = projectLinksConnected.map(x =>
        x -> ProjectLinkSplitter.findMatchingGeometrySegment(suravageLink, x).map(GeometryUtils.geometryLength)
          .getOrElse(0.0)).filter(_._2 > MinAllowedRoadAddressLength)
      if (commonSections.isEmpty)
        (None, Some(ErrorNoMatchingProjectLinkForSplit), None)
      else {
        val bestFit = commonSections.maxBy(_._2)._1
        val splitResult = try {
          ProjectLinkSplitter.split(roadLink.get, newProjectLink(suravageLink, project, splitOptions), bestFit, projectLinks, splitOptions)
        } catch {
          case e: Exception => {
            logger.error(e.getMessage)
            throw e
          }
        }
        (Some(splitResult), None, GeometryUtils.calculatePointAndHeadingOnGeometry(suravageLink.geometry, splitOptions.splitPoint))
      }
    }
  }


  /**
    * Main workhorse of the actual split. This works by evoking the preSplitSuravageLinkInTX since it's the one that does most of the work.
    * After the evocation we just remove all the project links sharing a link id with those in the result, create the ones in the result, update the project coordinates and then call the recalculation.
    * @param linkId: Long - linkId
    * @param username: String - User name
    * @param splitOptions
    * @return
    */
  def splitSuravageLinkInTX(linkId: Long, username: String, splitOptions: SplitOptions): Option[String] = {
    val (splitResultOption, errorMessage, _) = preSplitSuravageLinkInTX(linkId, username, splitOptions)
    if (errorMessage.nonEmpty) {
      errorMessage
    } else {
      splitResultOption.foreach {
        splitResult =>
          val splitLinks = splitResult.toSeqWithAllTerminated
          projectLinkDAO.removeProjectLinksByLinkId(splitOptions.projectId, splitLinks.map(_.linkId).toSet)
          projectLinkDAO.create(splitLinks.map(x => x.copy(createdBy = Some(username))))
          projectDAO.updateProjectCoordinates(splitOptions.projectId, splitOptions.coordinates)
          recalculateProjectLinks(splitOptions.projectId, username, Set((splitOptions.roadNumber, splitOptions.roadPartNumber)))
      }
      None
    }
  }

  def getProjectLinksInBoundingBox(bbox: BoundingRectangle, projectId: Long): Seq[ProjectLink] = {
    val roadLinks = roadLinkService.getRoadLinksAndComplementaryFromVVH(bbox, Set()).map(rl => rl.linkId -> rl).toMap
    projectLinkDAO.fetchProjectLinksByProjectAndLinkId(Set(), roadLinks.keys.toSet, projectId).filter(_.status == LinkStatus.NotHandled)
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
              val storedProject = projectDAO.fetchById(roadAddressProject.id).get
              val removed = storedProject.reservedParts.filterNot(part =>
                roadAddressProject.reservedParts.exists(rp => rp.roadPartNumber == part.roadPartNumber &&
                  rp.roadNumber == part.roadNumber))
              removed.foreach(p => projectReservedPartDAO.removeReservedRoadPartAndChanges(roadAddressProject.id, p.roadNumber, p.roadPartNumber))
              removed.groupBy(_.roadNumber).keys.foreach(ProjectLinkNameDAO.revert(_, roadAddressProject.id))
              addLinksToProject(roadAddressProject)
              val updatedProject = projectDAO.fetchById(roadAddressProject.id).get
              if (updatedProject.reservedParts.nonEmpty) {
                projectDAO.update(roadAddressProject.copy(ely = projectLinkDAO.fetchElyFromProjectLinks(roadAddressProject.id)))
              } else { //in empty case we release ely
                projectDAO.update(roadAddressProject.copy(ely = None))
              }
              val savedProject = projectDAO.fetchById(roadAddressProject.id).get
              saveProjectCoordinates(savedProject.id, calculateProjectCoordinates(savedProject.id))
              savedProject
            } else {
              throw new NameExistsException(s"Nimellä ${roadAddressProject.name} on jo olemassa projekti. Muuta nimeä.")
            }
        }
      } else {
        throw new IllegalStateException(projectNotWritable)
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
      val project = projectDAO.fetchById(projectId)
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
      projectDAO.fetchById(projectId)
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
        || x.linkGeomSource == LinkGeomSource.FrozenLinkInterface || x.linkGeomSource == LinkGeomSource.ComplimentaryLinkInterface).map(x => x.linkId).toSet)
      val suravageLinks = roadLinkService.getSuravageRoadLinksFromVVH(projectLinks.filter(x => x.linkGeomSource == LinkGeomSource.SuravageLinkInterface).map(x => x.linkId).toSet)
      val vvhLinks = roadLinks ++ suravageLinks
      val geometryMap = vvhLinks.map(l => l.linkId -> (l.geometry, l.vvhTimeStamp)).toMap
      val timeStamp = new Date().getTime
      val updatedProjectLinks = projectLinks.map { pl =>
        val (geometry, time) = geometryMap.getOrElse(pl.linkId, (Seq(), timeStamp))
        pl.copy(geometry = GeometryUtils.truncateGeometry2D(geometry, pl.startMValue, pl.endMValue),
          linkGeometryTimeStamp = time)
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
  private def checkAndReserve(project: Project, reservedRoadPart: ProjectReservedPart): (Option[ProjectReservedPart], Option[String]) = {
    val currentReservedPart = projectReservedPartDAO.roadPartReservedTo(reservedRoadPart.roadNumber, reservedRoadPart.roadPartNumber)
    logger.info(s"Check ${project.id} matching to " + currentReservedPart)
    currentReservedPart match {
      case Some(proj) if proj._1 != project.id => (None, Some(proj._2))
      case Some(proj) if proj._1 == project.id =>
        (projectReservedPartDAO.fetchReservedRoadPart(reservedRoadPart.roadNumber, reservedRoadPart.roadPartNumber), None)
      case _ =>
        projectReservedPartDAO.reserveRoadPart(project.id, reservedRoadPart.roadNumber, reservedRoadPart.roadPartNumber, project.modifiedBy)
        setProjectEly(project.id, reservedRoadPart.ely.getOrElse(-1))
        (projectReservedPartDAO.fetchReservedRoadPart(reservedRoadPart.roadNumber, reservedRoadPart.roadPartNumber), None)
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
    * @param roadAddressService: RoadAddressService - The road address service
    * @param projectId: Long - The active project id
    * @param boundingRectangle: BoundingRectangle - Search rectangle defined by 2 point.
    * @param roadNumberLimits: Seq(Int, Int) - Defines the upper and lower limits of the road number that can be retrived.
    * @param municipalities: Seq(Int) - Defines from what municipalities we fetch infomration.
    * @param everything: Boolean - Used in the filter
    * @param publicRoads: Boolean - Used in the filter
    * @return
    */
  def getProjectLinksWithSuravage(roadAddressService: RoadAddressService, projectId: Long, boundingRectangle: BoundingRectangle,
                                  roadNumberLimits: Seq[(Int, Int)], municipalities: Set[Int], everything: Boolean = false,
                                  publicRoads: Boolean = false): Seq[ProjectAddressLink] = {
    val fetch = fetchBoundingBoxF(boundingRectangle, projectId, roadNumberLimits, municipalities, everything, publicRoads)
    val suravageList = withDynSession {
      Await.result(fetch.suravageF, Duration.Inf)
        .map(x => (x, Some(projectId))).map(roadAddressLinkBuilder.buildSuravageRoadAddressLink)
    }
    val projectLinks = fetchProjectRoadLinks(projectId, boundingRectangle, roadNumberLimits, municipalities, everything, publicRoads, fetch)
    val keptSuravageLinks = suravageList.filter(sl => !projectLinks.exists(pl => sl.linkId == pl.linkId))
    keptSuravageLinks.map(ProjectAddressLinkBuilder.build) ++
      projectLinks
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

  def getChangeProject(projectId: Long): Option[ChangeProject] = {
    withDynTransaction {
      getChangeProjectInTX(projectId)
    }
  }

  def getChangeProjectInTX(projectId: Long): Option[ChangeProject] = {
    try {
      if (recalculateChangeTable(projectId)) {
        val roadwayChanges = roadwayChangesDAO.fetchRoadwayChangesResume(Set(projectId))
        Some(ViiteTierekisteriClient.convertToChangeProject(roadwayChanges))
      } else {
        None
      }
    } catch {
      case NonFatal(e) =>
        logger.info(s"Change info not available for project $projectId: " + e.getMessage)
        None
    }
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

  def getRoadwayChangesAndSendToTR(projectId: Set[Long]): ProjectChangeStatus = {
    logger.info(s"Fetching all road address changes for projects: ${projectId.toString()}")
    val roadwayChanges = roadwayChangesDAO.fetchRoadwayChanges(projectId)
    prettyPrintLog(roadwayChanges)
    logger.info(s"Sending changes to TR")
    val sentObj = ViiteTierekisteriClient.sendChanges(roadwayChanges)
    logger.info(s"Changes Sent to TR")
    sentObj
  }

  def getProjectAddressLinksByLinkIds(linkIdsToGet: Set[Long]): Seq[ProjectAddressLink] = {
    if (linkIdsToGet.isEmpty)
      Seq()
    withDynSession {
      new ProjectLinkDAO().fetchProjectLinksByLinkId(linkIdsToGet.toSeq).map(pl => ProjectAddressLinkBuilder.build(pl))
    }
  }

  def getProjectSuravageRoadLinksByLinkIds(linkIdsToGet: Set[Long]): Seq[ProjectAddressLink] = {
    if (linkIdsToGet.isEmpty)
      Seq()
    else {
      val suravageRoadLinks = time(logger, "Fetch VVH road links") {
        roadAddressService.getSuravageRoadLinkAddressesByLinkIds(linkIdsToGet)
      }
      suravageRoadLinks.map(ProjectAddressLinkBuilder.build)
    }
  }

  /**
    * Main function responsible for fetching and building Project Road Links.
    * First we fetch all kinds of road addresses, project links and vvh road links inside a bounding box.
    * After that we fetch the unaddressed links via bounding box as well.
    * With all the information we have now we start to call the various builders to get the information from multiple sources combined.
    * Once our road information is combined we pass it to the fillTopology in order for it to do some adjustments when needed and to finalize it we filter via the complementaryLinkFilter and evoke the final builder to get the result we need.
    *
    * @param projectId: Long - Project id
    * @param boundingRectangle: BoundingRectangle - designates where we search
    * @param roadNumberLimits: Seq[(Int, Int)] - used in the filtering of results
    * @param municipalities: Set[Int] - used to limit the results to these municipalities
    * @param everything: Boolean - used in the filtering of results
    * @param publicRoads: Boolean - used in the filtering of results
    * @param fetch: ProjectBoundingBoxResult - collection of all our combined fetches from different sources
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
      val (floating, addresses) = roadAddressService.getRoadAddressesByBoundingBox(boundingRectangle,
        roadNumberLimits = roadNumberLimits).partition(_.isFloating)
      (floating.groupBy(_.linkId), addresses.groupBy(_.linkId))
    })
    val fetchProjectLinksF = fetch.projectLinkResultF
    val fetchVVHStartTime = System.currentTimeMillis()

    val (regularLinks, complementaryLinks, suravageLinks) = awaitRoadLinks(fetch.roadLinkF, fetch.complementaryF, fetch.suravageF)
    // Removing complementary links for now
    val linkIds = regularLinks.map(_.linkId).toSet ++ /*complementaryLinks.map(_.linkId).toSet ++*/ suravageLinks.map(_.linkId).toSet
    val fetchVVHEndTime = System.currentTimeMillis()
    logger.info("Fetch VVH road links completed in %d ms".format(fetchVVHEndTime - fetchVVHStartTime))

    val fetchUnaddressedRoadLinkStartTime = System.currentTimeMillis()
    val ((floating, addresses), currentProjectLinks) = Await.result(fetchRoadAddressesByBoundingBoxF.zip(fetchProjectLinksF), Duration.Inf)
    val projectLinks = if (projectState.isDefined && projectState.get == Saved2TR) {
      fetchProjectHistoryLinks(projectId)
    }
    else currentProjectLinks

    val normalLinks = regularLinks.filterNot(l => projectLinks.exists(_.linkId == l.linkId))

    val fetchUnaddressedRoadLinkEndTime = System.currentTimeMillis()
    logger.info("Fetch unaddressed road links and floating linear locations completed in %d ms".format(fetchUnaddressedRoadLinkEndTime - fetchUnaddressedRoadLinkStartTime))

    val buildStartTime = System.currentTimeMillis()

    val projectRoadLinks = withDynSession {
      projectLinks.groupBy(l => (l.linkId, l.roadType)).flatMap {
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
    * @param projectId: Long - Project id
    * @param boundingRectangle: BoundingRectangle - designates where we search
    * @param roadNumberLimits: Seq[(Int, Int)] - used in the filtering of results
    * @param municipalities: Set[Int] - used to limit the results to these municipalities
    * @param everything: Boolean - used in the filtering of results
    * @param publicRoads: Boolean - used in the filtering of results
    * @return
    */
  def fetchProjectRoadLinksLinearGeometry(projectId: Long, boundingRectangle: BoundingRectangle, roadNumberLimits: Seq[(Int, Int)], municipalities: Set[Int],
                                          everything: Boolean = false, publicRoads: Boolean = false): Seq[ProjectAddressLink] = {
    val fetchRoadAddressesByBoundingBoxF = Future(withDynTransaction {
      val (floating, addresses) = roadAddressService.getRoadAddressesByBoundingBox(boundingRectangle,
        roadNumberLimits = roadNumberLimits).partition(_.isFloating)
      (floating.groupBy(_.linkId), addresses.groupBy(_.linkId))
    })

    val fetchProjectLinksF = Future(withDynSession {
      val projectState = projectDAO.fetchProjectStatus(projectId)
      if (projectState.isDefined && projectState.get == Saved2TR)
        projectLinkDAO.fetchProjectLinksHistory(projectId).groupBy(_.linkId)
      else
        projectLinkDAO.fetchProjectLinks(projectId).groupBy(_.linkId)
    })
    val ((_, addresses), projectLinks) = time(logger, "Fetch road addresses by bounding box") {
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
      Future(roadLinkService.getRoadLinksFromVVH(boundingRectangle, roadNumberLimits, municipalities, everything, publicRoads, frozenTimeVVHAPIServiceEnabled)),
      Future(
        if (everything) roadLinkService.getComplementaryRoadLinksFromVVH(boundingRectangle, municipalities)
        else Seq()),
      Future(roadLinkService.getSuravageLinksFromVVHF(boundingRectangle, municipalities))
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
    * @param projectId: Long - Project Id
    * @param newRoadNumber: Long - Road number
    * @param newRoadPart: Long - Road part number
    * @param linkStatus: LinkStatus - What kind of operation is subjected
    * @param projectLinks: Seq[ProjectLink] - Project links
    * @return
    */
  private def getProjectWithReservationChecks(projectId: Long, newRoadNumber: Long, newRoadPart: Long, linkStatus: LinkStatus, projectLinks: Seq[ProjectLink]): Project = {
    projectValidator.checkProjectExists(projectId)
    val project = projectDAO.fetchById(projectId).get
    projectValidator.checkReservedExistence(project, newRoadNumber, newRoadPart, linkStatus, projectLinks)
    projectValidator.checkAvailable(newRoadNumber, newRoadPart, project)
    projectValidator.checkNotReserved(newRoadNumber, newRoadPart, project)
    project
  }

  /**
    * Reverts project links to their previous state, if used on new links it will delete them, if used on the rest they will become unhandled.
    * Also resets values to their starting values.
    * @param links: Iterable[ProjectLink] - Links to revert
    * @param userName: String - User name
    * @return
    */
  def revertLinks(links: Iterable[ProjectLink], userName: String): Option[String] = {
    if (links.groupBy(l => (l.projectId, l.roadNumber, l.roadPartNumber)).keySet.size != 1)
      throw new IllegalArgumentException("Reverting links from multiple road parts at once is not allowed")
    val l = links.head
    revertLinks(l.projectId, l.roadNumber, l.roadPartNumber, links.map(
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
    * @param projectId: Long - Project ID
    * @param roadNumber: Long - Roadway Road Number
    * @param roadPartNumber: Long - Roadway Road Part Number
    * @param toRemove: Iterable[LinksToRemove] - Project links that were created in this project
    * @param modified: Iterable[LinksToRemove] - Project links that existed as road addresses
    * @param userName: String - User name
    * @param recalculate: Boolean - Will tell if we recalculate the whole project links or not
    */
  private def revertLinks(projectId: Long, roadNumber: Long, roadPartNumber: Long, toRemove: Iterable[LinkToRevert],
                          modified: Iterable[LinkToRevert], userName: String, recalculate: Boolean = true): Unit = {
    val modifiedLinkIds = modified.map(_.linkId).toSet
    projectLinkDAO.removeProjectLinksByLinkId(projectId, toRemove.map(_.linkId).toSet)
    val vvhRoadLinks = roadLinkService.getCurrentAndComplementaryAndSuravageRoadLinksFromVVH(modifiedLinkIds)
    val roadAddresses = roadwayAddressMapper.getRoadAddressesByLinearLocation(linearLocationDAO.fetchRoadwayByLinkId(modifiedLinkIds))
    roadAddresses.foreach(ra =>
      modified.find(mod => mod.linkId == ra.linkId) match {
        case Some(mod) if mod.geometry.nonEmpty =>
          checkAndReserve(projectDAO.fetchById(projectId).get, toReservedRoadPart(ra.roadNumber, ra.roadPartNumber, ra.ely))
          val vvhGeometry = vvhRoadLinks.find(roadLink => roadLink.linkId == mod.linkId && roadLink.linkSource == ra.linkGeomSource)
          val geom = GeometryUtils.truncateGeometry3D(vvhGeometry.get.geometry, ra.startMValue, ra.endMValue)
          projectLinkDAO.updateProjectLinkValues(projectId, ra.copy(geometry = geom))

        case _ =>
          checkAndReserve(projectDAO.fetchById(projectId).get, toReservedRoadPart(ra.roadNumber, ra.roadPartNumber, ra.ely))
          projectLinkDAO.updateProjectLinkValues(projectId, ra, updateGeom = false)
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
    * @param projectId: Long - Project Id
    * @param roadNumber: Long - Roadway Road Number
    * @param roadPartNumber: Long - Roadway Road Part Number
    * @param links: Iterable[ProjectLink] - Links to revert
    * @param userName: String - User name
    * @return
    */
  def revertLinks(projectId: Long, roadNumber: Long, roadPartNumber: Long, links: Iterable[LinkToRevert], userName: String): Option[String] = {
    val (added, modified) = links.partition(_.status == LinkStatus.New.value)
    if (modified.exists(_.status == LinkStatus.Numbering.value)) {
      logger.info(s"Reverting whole road part in $projectId ($roadNumber/$roadPartNumber)")
      // Numbering change affects the whole road part
      revertLinks(projectId, roadNumber, roadPartNumber, added,
        projectLinkDAO.fetchByProjectRoadPart(roadNumber, roadPartNumber, projectId).map(
          link => LinkToRevert(link.id, link.linkId, link.status.value, link.geometry)),
        userName)
    } else {
      revertLinks(projectId, roadNumber, roadPartNumber, added, modified, userName)
    }
    None
  }

  /**
    * Continuation of the revert. Sets up the database transaction to save the modifications done to the links to revert.
    * After the modifications are saved this will save the new project coordinates.
    * Otherwise this will issue a error messages.
    * @param projectId: Long - The id of the project
    * @param roadNumber: Long - roadway road number
    * @param roadPartNumber: Long - roadway road part number
    * @param links: Iterable[LinkToRevert] - The links to return to the original values and state
    * @param coordinates: ProjectCoordinates - New coordinates on where to move the map on project open
    * @param userName: String - The name of the user
    * @return
    */
  def revertLinks(projectId: Long, roadNumber: Long, roadPartNumber: Long, links: Iterable[LinkToRevert], coordinates: ProjectCoordinates, userName: String): Option[String] = {
    try {
      withDynTransaction {
        projectWritableCheckInSession(projectId) match {
          case None =>
            revertLinks(projectId, roadNumber, roadPartNumber, links, userName) match {
              case None =>
                saveProjectCoordinates(projectId, coordinates)
                None
              case Some(error) => Some(error)
            }
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
      revertLinks(links, userName)
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
                         userDefinedEndAddressM: Option[Int], roadType: Long = RoadType.PublicRoad.value,
                         discontinuity: Int = Discontinuity.Continuous.value, ely: Option[Long] = None,
                         reversed: Boolean = false, roadName: Option[String] = None, coordinates: Option[ProjectCoordinates] = None): Option[String] = {
    def isCompletelyNewPart(toUpdateLinks: Seq[ProjectLink]): (Boolean, Long, Long) = {
      val reservedPart = projectReservedPartDAO.fetchReservedRoadPart(toUpdateLinks.head.roadNumber, toUpdateLinks.head.roadPartNumber).get
      val newSavedLinks = projectLinkDAO.fetchByProjectRoadPart(reservedPart.roadNumber, reservedPart.roadPartNumber, projectId)
      /*
      replaceable -> New link part should replace New existing part if:
        1. Action is LinkStatus.New
        2. New road or part is different from existing one
        3. All New links in existing part are in selected links for New part
       */
      val replaceable = (linkStatus == New || linkStatus == Transfer) && (reservedPart.roadNumber != newRoadNumber || reservedPart.roadPartNumber != newRoadPartNumber) && newSavedLinks.map(_.id).toSet.subsetOf(ids)
      (replaceable, reservedPart.roadNumber, reservedPart.roadPartNumber)
    }

    def updateRoadTypeDiscontinuity(links: Seq[ProjectLink]): Unit = {
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
      val project = getProjectWithReservationChecks(projectId, newRoadNumber, newRoadPartNumber, linkStatus, projectLinks)
      try {
        val (toReplace, road, part) = isCompletelyNewPart(projectLinks)
        if (toReplace && linkStatus == New) {
          val reservedPart = projectReservedPartDAO.fetchReservedRoadPart(road, part).get
          projectReservedPartDAO.removeReservedRoadPartAndChanges(projectId, reservedPart.roadNumber, reservedPart.roadPartNumber)
          val newProjectLinks: Seq[ProjectLink] = projectLinks.map(pl => pl.copy(id = NewProjectLink,
            roadNumber = newRoadNumber, roadPartNumber = newRoadPartNumber, track = Track.apply(newTrackCode),
            roadType = RoadType.apply(roadType.toInt), discontinuity = Discontinuity.apply(discontinuity.toInt),
            endAddrMValue = userDefinedEndAddressM.getOrElse(pl.endAddrMValue.toInt).toLong))
          if (linkIds.nonEmpty) {
            addNewLinksToProject(sortRamps(newProjectLinks, linkIds), projectId, userName, linkIds.head, newTransaction = false)
          } else {
            val newSavedLinkIds = projectLinks.map(_.linkId)
            addNewLinksToProject(sortRamps(newProjectLinks, newSavedLinkIds), projectId, userName, newSavedLinkIds.head, newTransaction = false)
          }
        } else if (!project.isReserved(newRoadNumber, newRoadPartNumber)) {
          projectReservedPartDAO.reserveRoadPart(project.id, newRoadNumber, newRoadPartNumber, project.modifiedBy)
        }
        (toReplace, Some(road), Some(part))
      } catch {
        case e: Exception => println("Unexpected exception occurred: " + e)
          (false, None, None)
      }
    }

    def resetLinkValues(toReset: Seq[ProjectLink]): Unit = {
      val addressesForRoadway = roadAddressService.getRoadAddressesByRoadwayIds(toReset.map(_.roadwayId))
      val filteredAddresses = addressesForRoadway.filter(link => toReset.map(_.linkId).contains(link.linkId))
      filteredAddresses.foreach(ra => projectLinkDAO.updateProjectLinkValues(projectId, ra, updateGeom = false))
    }

    try {
      withDynTransaction {
        val toUpdateLinks = projectLinkDAO.fetchProjectLinksByProjectAndLinkId(ids, linkIds.toSet, projectId)
        if (toUpdateLinks.exists(_.isSplit))
          throw new ProjectValidationException(ErrorSplitSuravageNotUpdatable)
        userDefinedEndAddressM.map(addressM => {
          val endSegment = toUpdateLinks.maxBy(_.endAddrMValue)
          val calibrationPoint = UserDefinedCalibrationPoint(newCalibrationPointId, endSegment.id, projectId, addressM.toDouble - endSegment.startMValue, addressM)
          val calibrationPointIsPresent = toUpdateLinks.find(ul => ul.projectId == projectId && ul.startAddrMValue == endSegment.startAddrMValue) match {
            case Some(projectLink) =>
              projectLink.hasCalibrationPointAt(calibrationPoint.addressMValue)
            case _ => false
          }
          if (!calibrationPointIsPresent) {
            val foundCalibrationPoint = CalibrationPointDAO.findEndCalibrationPoint(endSegment.id, projectId)
            if (foundCalibrationPoint.isEmpty)
              CalibrationPointDAO.createCalibrationPoint(calibrationPoint)
            else
              CalibrationPointDAO.updateSpecificCalibrationPointMeasures(foundCalibrationPoint.head.id, addressM.toDouble - endSegment.startMValue, addressM)
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
              val roadAddresses = roadAddressService.getRoadAddressesByRoadwayIds(toUpdateLinks.map(_.roadwayId), includeFloating = true)
              if (roadAddresses.exists(x =>
                x.roadNumber == newRoadNumber && x.roadPartNumber == newRoadPartNumber)) // check the original numbering wasn't exactly the same
                throw new ProjectValidationException(ErrorRenumberingToOriginalNumber) // you cannot use current roadnumber and roadpart number in numbering operation
              if (toUpdateLinks.map(pl => (pl.roadNumber, pl.roadPartNumber)).distinct.lengthCompare(1) != 0 ||
                roadAddresses.map(ra => (ra.roadNumber, ra.roadPartNumber)).distinct.lengthCompare(1) != 0) {
                throw new ProjectValidationException(ErrorMultipleRoadNumbersOrParts)
              }
              checkAndMakeReservation(projectId, newRoadNumber, newRoadPartNumber, LinkStatus.Numbering, toUpdateLinks)

              projectLinkDAO.updateProjectLinkNumbering(projectId, toUpdateLinks.head.roadNumber, toUpdateLinks.head.roadPartNumber, linkStatus, newRoadNumber, newRoadPartNumber, userName, discontinuity, toUpdateLinks.head.track)
              roadName.foreach(setProjectRoadName(projectId, newRoadNumber, _))
            } else {
              throw new ProjectValidationException(ErrorRoadLinkNotFoundInProject)
            }

          case LinkStatus.Transfer =>
            val (replaceable, road, part) = checkAndMakeReservation(projectId, newRoadNumber, newRoadPartNumber, LinkStatus.Transfer, toUpdateLinks)
            val updated = toUpdateLinks.map(l => {
              l.copy(roadNumber = newRoadNumber, roadPartNumber = newRoadPartNumber, track = Track.apply(newTrackCode),
                status = linkStatus, calibrationPoints = (None, None), roadType = RoadType.apply(roadType.toInt))
            })
            val originalAddresses = roadAddressService.getRoadAddressesByRoadwayIds(updated.map(_.roadwayId))
            projectLinkDAO.updateProjectLinks(updated, userName, originalAddresses)
            projectLinkDAO.updateProjectLinkRoadTypeDiscontinuity(Set(updated.maxBy(_.endAddrMValue).id), linkStatus, userName, roadType, Some(discontinuity))
            //transfer cases should remove the part after the project link table update operation
            if (replaceable) {
              projectReservedPartDAO.removeReservedRoadPart(projectId, road.get, part.get)
            }
            roadName.foreach(setProjectRoadName(projectId, newRoadNumber, _))
          case LinkStatus.UnChanged =>
            checkAndMakeReservation(projectId, newRoadNumber, newRoadPartNumber, LinkStatus.UnChanged, toUpdateLinks)
            // Reset back to original values
            resetLinkValues(toUpdateLinks)
            updateRoadTypeDiscontinuity(toUpdateLinks.map(_.copy(roadType = RoadType.apply(roadType.toInt), status = linkStatus)))

          case LinkStatus.New =>
            // Current logic allows only re adding new road addresses whithin same road/part group
            if (toUpdateLinks.groupBy(l => (l.roadNumber, l.roadPartNumber)).size <= 1) {
              checkAndMakeReservation(projectId, newRoadNumber, newRoadPartNumber, LinkStatus.New, toUpdateLinks)
              updateRoadTypeDiscontinuity(toUpdateLinks.map(_.copy(roadType = RoadType.apply(roadType.toInt), roadNumber = newRoadNumber, roadPartNumber = newRoadPartNumber, track = Track.apply(newTrackCode))))
              roadName.foreach(setProjectRoadName(projectId, newRoadNumber, _))
            } else {
              throw new RoadAddressException(s"Useamman kuin yhden tien/tieosan tallennus kerralla ei ole tuettu.")
            }
          case _ =>
            throw new ProjectValidationException(s"Virheellinen operaatio $linkStatus")
        }
        recalculateProjectLinks(projectId, userName, Set((newRoadNumber, newRoadPartNumber)) ++
          toUpdateLinks.map(pl => (pl.roadNumber, pl.roadPartNumber)).toSet)
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
        Some(s"Tieosoitevirhe: (${ex.getMessage}")
      case ex: ProjectValidationException => Some(ex.getMessage)
      case ex: Exception => Some(ex.getMessage)
    }
  }

  private def recalculateProjectLinks(projectId: Long, userName: String, roadParts: Set[(Long, Long)] = Set()): Unit = {

    def setReversedFlag(adjustedLink: ProjectLink, before: Option[ProjectLink]): ProjectLink = {
      before.map(_.sideCode) match {
        case Some(value) if value != adjustedLink.sideCode && value != SideCode.Unknown =>
          adjustedLink.copy(reversed = !adjustedLink.reversed)
        case _ => adjustedLink
      }
    }

    val projectLinks = projectLinkDAO.fetchProjectLinks(projectId)
    logger.info(s"Recalculating project $projectId, parts ${roadParts.map(p => s"${p._1}/${p._2}").mkString(", ")}")

    time(logger, "Recalculate links") {
      val (terminated, others) = projectLinks.partition(_.status == LinkStatus.Terminated)

      val recalculated = others.groupBy(
        pl => (pl.roadNumber, pl.roadPartNumber)).flatMap {
        grp =>
          val calibrationPoints = CalibrationPointDAO.fetchByRoadPart(projectId, grp._1._1, grp._1._2)
          ProjectSectionCalculator.assignMValues(grp._2, calibrationPoints).map(rpl =>
            setReversedFlag(rpl, grp._2.find(pl => pl.id == rpl.id && rpl.roadwayId != 0L))
          )
      }.toSeq

      val recalculatedTerminated = ProjectSectionCalculator.assignTerminatedMValues(terminated, recalculated)
      val originalAddresses = roadAddressService.getRoadAddressesByRoadwayIds((recalculated ++ recalculatedTerminated).map(_.roadwayId))
      projectLinkDAO.updateProjectLinks(recalculated ++ recalculatedTerminated, userName, originalAddresses)
    }
  }

  private def recalculateChangeTable(projectId: Long): Boolean = {
    val projectOpt = projectDAO.fetchById(projectId)
    if (projectOpt.isEmpty)
      throw new IllegalArgumentException("Project not found")
    val project = projectOpt.get
    project.status match {
      case ProjectState.Saved2TR => true
      case _ =>
        val delta = ProjectDeltaCalculator.delta(project)
        setProjectDeltaToDB(delta, projectId)

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
  def removeRotatingTRId(projectId: Long): Option[String] = {
    withDynSession {
      val project = projectDAO.fetchById(projectId)
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
    * Publish project with id projectId
    *
    * @param projectId Project to publish
    * @return optional error message, empty if no error
    */
  def publishProject(projectId: Long): PublishResult = {
    logger.info(s"Preparing to send Project ID: $projectId to TR")
    withDynTransaction {
      try {
        if (!recalculateChangeTable(projectId)) {
          return PublishResult(validationSuccess = false, sendSuccess = false, Some("Muutostaulun luonti epäonnistui. Tarkasta ely"))
        }
        projectDAO.assignNewProjectTRId(projectId) //Generate new TR_ID
        val trProjectStateMessage = getRoadwayChangesAndSendToTR(Set(projectId))
        if (trProjectStateMessage.status == ProjectState.Failed2GenerateTRIdInViite.value) {
          return PublishResult(validationSuccess = false, sendSuccess = false, Some(trProjectStateMessage.reason))
        }
        trProjectStateMessage.status match {
          case it if 200 until 300 contains it =>
            setProjectStatus(projectId, Sent2TR, withSession = false)
            logger.info(s"Sending to TR successful: ${trProjectStateMessage.reason}")
            PublishResult(validationSuccess = true, sendSuccess = true, Some(trProjectStateMessage.reason))

          case _ =>
            //rollback
            logger.info(s"Sending to TR failed: ${trProjectStateMessage.reason}")
            val returningMessage = if (trProjectStateMessage.reason.nonEmpty) trProjectStateMessage.reason else trConnectionError
            PublishResult(validationSuccess = true, sendSuccess = false, Some(returningMessage))
        }
      } catch {
        //Exceptions taken out val response = client.execute(request) of sendJsonMessage in ViiteTierekisteriClient
        case ioe@(_: IOException | _: ClientProtocolException) =>
          projectDAO.updateProjectStatus(projectId, SendingToTR)
          PublishResult(validationSuccess = false, sendSuccess = false, Some(trConnectionError))
        case NonFatal(_) =>
          projectDAO.updateProjectStatus(projectId, ErrorInViite)
          PublishResult(validationSuccess = false, sendSuccess = false, Some(genericViiteErrorMessage))
      }
    }
  }

  private def setProjectDeltaToDB(projectDelta: Delta, projectId: Long): Boolean = {
    roadwayChangesDAO.clearRoadChangeTable(projectId)
    roadwayChangesDAO.insertDeltaToRoadChangeTable(projectDelta, projectId)
  }

  private def newProjectTemplate(rl: RoadLinkLike, ra: RoadAddress, project: Project): ProjectLink = {
    val geometry = GeometryUtils.truncateGeometry3D(rl.geometry, ra.startMValue, ra.endMValue)
    ProjectLink(NewProjectLink, ra.roadNumber, ra.roadPartNumber, ra.track, ra.discontinuity, ra.startAddrMValue,
      ra.endAddrMValue, ra.startAddrMValue, ra.endAddrMValue, ra.startDate, ra.endDate, Some(project.modifiedBy), ra.linkId, ra.startMValue, ra.endMValue,
      ra.sideCode, ra.toProjectLinkCalibrationPoints(), ra.floating, geometry,
      project.id, LinkStatus.NotHandled, ra.roadType, ra.linkGeomSource, GeometryUtils.geometryLength(geometry),
      ra.id, ra.linearLocationId, ra.ely, ra.reversed, None, ra.adjustedTimestamp, roadAddressLength = Some(ra.endAddrMValue - ra.startAddrMValue))
  }

  private def newProjectLink(rl: RoadLinkLike, project: Project, roadNumber: Long,
                             roadPartNumber: Long, trackCode: Track, discontinuity: Discontinuity, roadType: RoadType,
                             ely: Long, roadName: String = "", reversed: Boolean = false): ProjectLink = {
    ProjectLink(NewRoadway, roadNumber, roadPartNumber, trackCode, discontinuity,
      0L, 0L, 0L, 0L, Some(project.startDate), None, Some(project.modifiedBy), rl.linkId, 0.0, rl.length,
      SideCode.Unknown, (None, None), floating = NoFloating, rl.geometry,
      project.id, LinkStatus.New, roadType, rl.linkSource, rl.length,
      0L, 0L, ely, reversed, None, rl.vvhTimeStamp, roadName = Some(roadName))
  }

  private def newProjectLink(rl: RoadLinkLike, project: Project, splitOptions: SplitOptions): ProjectLink = {
    newProjectLink(rl, project, splitOptions.roadNumber, splitOptions.roadPartNumber, splitOptions.trackCode,
      splitOptions.discontinuity, splitOptions.roadType, splitOptions.ely)
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
    * @param links: Seq[ProjectLink] - Project links to combine.
    * @return
    */
  private def fuseProjectLinks(links: Seq[ProjectLink]): Seq[ProjectLink] = {
    val linkIds = links.map(_.linkId).distinct
    val existingRoadAddresses = roadAddressService.getRoadAddressesByRoadwayIds(links.map(_.roadwayId))
    val groupedRoadAddresses = existingRoadAddresses.groupBy(record =>
      (record.roadwayNumber, record.roadNumber, record.roadPartNumber, record.track.value, record.startDate, record.endDate, record.linkId, record.roadType, record.ely, record.terminated))

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
        Seq(links.head.copy(startMValue = startM, endMValue = endM, startAddrMValue = startA, endAddrMValue = endA, geometry = geom, discontinuity = links.maxBy(_.startAddrMValue).discontinuity))
      }
    }
  }

  private def awaitRoadLinks(fetch: (Future[Seq[RoadLink]], Future[Seq[RoadLink]], Future[Seq[VVHRoadlink]])) = {
    val combinedFuture = for {
      fStandard <- fetch._1
      fComplementary <- fetch._2
      fSuravage <- fetch._3
    } yield (fStandard, fComplementary, fSuravage)

    val (roadLinks, complementaryLinks, suravageLinks) = Await.result(combinedFuture, Duration.Inf)
    (roadLinks, complementaryLinks, suravageLinks)
  }

  def getProjectStatusFromTR(projectId: Long): Map[String, Any] = {
    ViiteTierekisteriClient.getProjectStatus(projectId)
  }

  private def getStatusFromTRObject(trProject: Option[TRProjectStatus]): Option[ProjectState] = {
    trProject match {
      case Some(trProjectObject) => mapTRStateToViiteState(trProjectObject.status.getOrElse(""))
      case None => None
      case _ => None
    }
  }

  private def getTRErrorMessage(trProject: Option[TRProjectStatus]): String = {
    trProject match {
      case Some(trProjectobject) => trProjectobject.errorMessage.getOrElse("")
      case None => ""
      case _ => ""
    }
  }

  def setProjectStatus(projectId: Long, newStatus: ProjectState, withSession: Boolean = true): Unit = {
    if (withSession) {
      withDynSession {
        projectDAO.updateProjectStatus(projectId, newStatus)
      }
    } else {
      projectDAO.updateProjectStatus(projectId, newStatus)
    }
  }

  def updateProjectStatusIfNeeded(currentStatus: ProjectState, newStatus: ProjectState, errorMessage: String, projectId: Long): ProjectState = {
    if (currentStatus.value != newStatus.value && newStatus != ProjectState.Unknown) {
      logger.info(s"Status update is needed as Project Current status ($currentStatus) differs from TR Status($newStatus)")
      val project = projectDAO.fetchById(projectId)
      if (project.nonEmpty && newStatus == ProjectState.ErrorInTR) {
        // We write error message and clear old TR_ID which was stored there, so user wont see it in hower
        logger.info(s"Writing error message and clearing old TR_ID: ($errorMessage)")
        projectDAO.updateProjectStateInfo(errorMessage, projectId)
      }
      projectDAO.updateProjectStatus(projectId, newStatus)
    }
    if (newStatus != ProjectState.Unknown) {
      newStatus
    } else {
      currentStatus
    }
  }

  private def getProjectsPendingInTR: Seq[Long] = {
    withDynSession {
      projectDAO.fetchProjectIdsWithWaitingTRStatus
    }
  }

  def updateProjectsWaitingResponseFromTR(): Unit = {
    val listOfPendingProjects = getProjectsPendingInTR
    for (project <- listOfPendingProjects) {
      try {
        withDynTransaction {
          checkAndUpdateProjectStatus(project)
        }
      } catch {
        case t: SQLException => logger.error(s"SQL error while importing project: $project! ${t.getMessage}", t)
        case t: Exception => logger.warn(s"Couldn't update project $project", t.getMessage)
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
    * @param projectID: Long - The project Id
    * @return
    */
  private def checkAndUpdateProjectStatus(projectID: Long): Unit = {
    projectDAO.fetchTRIdByProjectId(projectID) match {
      case Some(trId) =>
        val roadNumbers: Option[Set[Long]] = projectDAO.fetchProjectStatus(projectID).map { currentState =>
          logger.info(s"Current status is $currentState, fetching TR state")
          val trProjectState = ViiteTierekisteriClient.getProjectStatusObject(trId)
          logger.info(s"Retrieved TR status: ${trProjectState.getOrElse(None)}")
          val newState = getStatusFromTRObject(trProjectState).getOrElse(ProjectState.Unknown)
          val errorMessage = getTRErrorMessage(trProjectState)
          logger.info(s"TR returned project status for $projectID: $currentState -> $newState, errMsg: $errorMessage")
          val updatedStatus = updateProjectStatusIfNeeded(currentState, newState, errorMessage, projectID)
          if (updatedStatus == Saved2TR) {
            logger.info(s"Starting project $projectID road addresses importing to road address table")
            updateRoadwaysAndLinearLocationsWithProjectLinks(updatedStatus, projectID)
          } else Set.empty[Long]
        }
        val roadNetworkDAO = new RoadNetworkDAO
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
        appendStatusInfo(projectDAO.fetchById(projectID).head, " Failed to find TR-ID ")
    }
  }

  private def mapTRStateToViiteState(trState: String): Option[ProjectState] = {

    trState match {
      case "S" => Some(ProjectState.apply(ProjectState.TRProcessing.value))
      case "K" => Some(ProjectState.apply(ProjectState.TRProcessing.value))
      case "T" => Some(ProjectState.apply(ProjectState.Saved2TR.value))
      case "V" => Some(ProjectState.apply(ProjectState.ErrorInTR.value))
      case "null" => Some(ProjectState.apply(ProjectState.ErrorInTR.value))
      case _ => None
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
      val floatingValue = if (roadAddress.validTo.isDefined) FloatingReason.SplittingTool else NoFloating
      pl.status match {
        case UnChanged =>
          Seq(roadAddress.copy(id = NewProjectLink, startAddrMValue = pl.startAddrMValue, endAddrMValue = pl.endAddrMValue,
            createdBy = Some(project.createdBy), linkId = pl.linkId, startMValue = pl.startMValue, endMValue = pl.endMValue,
            adjustedTimestamp = pl.linkGeometryTimeStamp, geometry = pl.geometry, floating = floatingValue))
        case New =>
          Seq(RoadAddress(NewProjectLink, pl.linearLocationId, pl.roadNumber, pl.roadPartNumber, pl.roadType, pl.track, pl.discontinuity,
            pl.startAddrMValue, pl.endAddrMValue, Some(project.startDate), None, Some(project.createdBy), pl.linkId,
            pl.startMValue, pl.endMValue, pl.sideCode, pl.linkGeometryTimeStamp, pl.toCalibrationPoints, floating = NoFloating,
            pl.geometry, pl.linkGeomSource, pl.ely, terminated = NoTermination, NewRoadwayNumber))
        case Transfer => // TODO if the whole roadway -segment is transferred, keep the original roadway_number, otherwise generate new ids for the different segments
          val (startAddr, endAddr, startM, endM) = transferValues(split.find(_.status == Terminated))
          Seq(
            //TODO we should check situations where we need to create one new roadway for new and transfer/unchanged
            // Transferred part, original values
            roadAddress.copy(id = NewProjectLink, startAddrMValue = startAddr, endAddrMValue = endAddr,
              endDate = Some(project.startDate), createdBy = Some(project.createdBy), startMValue = startM, endMValue = endM, floating = floatingValue),
            // Transferred part, new values
            roadAddress.copy(id = NewProjectLink, startAddrMValue = pl.startAddrMValue, endAddrMValue = pl.endAddrMValue,
              startDate = Some(project.startDate), createdBy = Some(project.createdBy), linkId = pl.linkId,
              startMValue = pl.startMValue, endMValue = pl.endMValue, adjustedTimestamp = pl.linkGeometryTimeStamp,
              geometry = pl.geometry, floating = floatingValue)
          )
        case Terminated => // TODO Check roadway_number
          Seq(roadAddress.copy(id = NewProjectLink, startAddrMValue = pl.startAddrMValue, endAddrMValue = pl.endAddrMValue,
            endDate = Some(project.startDate), createdBy = Some(project.createdBy), linkId = pl.linkId, startMValue = pl.startMValue,
            endMValue = pl.endMValue, adjustedTimestamp = pl.linkGeometryTimeStamp, geometry = pl.geometry, terminated = Termination))
        case _ =>
          logger.error(s"Invalid status for split project link: ${pl.status} in project ${pl.projectId}")
          throw new InvalidAddressDataException(s"Invalid status for split project link: ${pl.status}")
      }
    })
  }

  /**
    * Expires roadways (valid_to = null and end_date = project_date)
    *
    * @param projectLinks          ProjectLinks
    * @param expiringRoadAddresses A map of (RoadwayId -> RoadAddress)
    */
  def expireHistoryRows(roadwayId: Long): Int = {
    roadwayDAO.expireHistory(Set(roadwayId))
  }

  def updateRoadwaysAndLinearLocationsWithProjectLinks(newState: ProjectState, projectID: Long): Set[Long] = {
    if (newState != Saved2TR) {
      logger.error(s" Project state not at Saved2TR")
      throw new RuntimeException(s"Project state not at Saved2TR: $newState")
    }
    val project = projectDAO.fetchById(projectID).get
    val projectLinks = projectLinkDAO.fetchProjectLinks(projectID)
    val currentRoadways = roadwayDAO.fetchAllByRoadwayId(projectLinks.map(pl => pl.roadwayId)).map(roadway => (roadway.id, roadway)).toMap
    val historyRoadways = roadwayDAO.fetchAllByRoadwayNumbers(currentRoadways.map(_._2.roadwayNumber).toSet, withHistory = true).filter(_.endDate.isDefined).map(roadway => (roadway.id, roadway)).toMap
    val roadwayChanges = roadwayChangesDAO.fetchRoadwayChanges(Set(projectID))
    val roadwayProjectLinkIds = roadwayChangesDAO.fetchRoadwayChangesLinks(projectID)
    val mappedRoadwaysWithLinks = roadwayChanges.map {
      change =>
        val linksRelatedToChange = roadwayProjectLinkIds.filter(link => link._1 == change.changeInfo.orderInChangeTable).map(_._2)
        val projectLinksInChange = projectLinks.filter(pl => linksRelatedToChange.contains(pl.id))
        (change, projectLinksInChange)
    }

    if (projectLinks.isEmpty) {
      logger.error(s" There are no addresses to update, rollbacking update ${project.id}")
      throw new InvalidAddressDataException(s"There are no addresses to update , rollbacking update ${project.id}")
    }

    projectLinkDAO.moveProjectLinksToHistory(projectID)
    logger.info(s"Moving project links to project link history.")
    try {
      val generatedRoadways = RoadwayFiller.fillRoadways(currentRoadways, historyRoadways, mappedRoadwaysWithLinks)
      val historyRoadwaysToKeep = generatedRoadways.flatMap(_._1).filter(_.id != NewRoadway).map(_.id)
      logger.info(s"Creating history rows based on operation")
      linearLocationDAO.expireByRoadwayNumbers((currentRoadways ++ historyRoadways).map(_._2.roadwayNumber).toSet)
      (currentRoadways ++ historyRoadways.filterNot(hRoadway => historyRoadwaysToKeep.contains(hRoadway._1))).map(roadway => expireHistoryRows(roadway._1))
      roadwayDAO.create(generatedRoadways.flatMap(_._1).filter(_.id == NewRoadway).groupBy(roadway => (roadway.roadNumber, roadway.roadPartNumber, roadway.startAddrMValue, roadway.endAddrMValue, roadway.track, roadway.discontinuity, roadway.startDate.toYearMonthDay, roadway.endDate.map(_.toYearMonthDay),
        roadway.validFrom.toYearMonthDay, roadway.validTo.map(_.toYearMonthDay), roadway.ely, roadway.roadType, roadway.terminated)).map(_._2.head))
      linearLocationDAO.create(generatedRoadways.flatMap(_._2).groupBy(l => (l.roadwayNumber, l.orderNumber, l.linkId, l.startMValue, l.endMValue, l.validTo.map(_.toYearMonthDay), l.startCalibrationPoint, l.endCalibrationPoint, l.floating, l.sideCode, l.linkGeomSource)).map(_._2.head), createdBy = project.createdBy)
      handleNewRoadNames(roadwayChanges, project)
      handleTransferAndNumbering(roadwayChanges)
      handleTerminatedRoadwayChanges(roadwayChanges)
      ProjectLinkNameDAO.removeByProject(projectID)
      Some(s"road addresses created")
      projectLinks.map(_.roadNumber).toSet
    } catch {
      case e: ProjectValidationException =>
        logger.error("Failed to validate project message:" + e.getMessage)
        Set.empty[Long]
    }
  }

  def handleTransferAndNumbering(roadwayChanges: Seq[ProjectRoadwayChange]): Unit = {
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
            RoadNameDAO.expireAndCreateHistory(srcExistingRoadName.get.id, rwc.user, historyRoadName = srcExistingRoadName.get.copy(endDate = Some(rwc.projectStartDate)))
          }

          // CREATE NEW ROADNAME FOR TARGET ROADNUMBER
          val projectLinkNames = ProjectLinkNameDAO.get(Set(targetRoadNumber), rwc.projectId)
          if (projectLinkNames.nonEmpty && targetExistingRoadways.nonEmpty && targetExistingRoadName.isEmpty) {
            RoadNameDAO.create(Seq(RoadName(NewRoadNameId, targetRoadNumber, projectLinkNames.head.roadName, startDate = Some(rwc.projectStartDate), validFrom = Some(DateTime.now()), createdBy = rwc.user)))
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
          RoadNameDAO.expireAndCreateHistory(roadNameOpt.get.id, rwc.user, historyRoadName = roadNameOpt.get.copy(endDate = Some(rwc.projectStartDate)))
        }
      }
    })
  }

  def handleNewRoadNames(roadwayChanges: Seq[ProjectRoadwayChange], project: Project): Unit = {
    val roadNames = roadwayChanges.flatMap(rwc => {
      val roadNumberOptional = rwc.changeInfo.target.roadNumber
      if (rwc.changeInfo.changeType.equals(AddressChangeType.New) && roadNumberOptional.isDefined) {
        val roadNumber = roadNumberOptional.get
        val existingRoadNames = RoadNameDAO.getCurrentRoadNamesByRoadNumber(roadNumber)
        val projectLinkNames = ProjectLinkNameDAO.get(Set(roadNumber), rwc.projectId)
        if (existingRoadNames.isEmpty && projectLinkNames.nonEmpty) {
          Some(RoadName(NewRoadNameId, roadNumber, projectLinkNames.head.roadName, startDate = Some(rwc.projectStartDate), validFrom = Some(DateTime.now()), createdBy = rwc.user))
        } else {
          val nameString = s"${existingRoadNames.map(_.roadNumber).mkString(",")}"
          appendStatusInfo(project, roadNameWasNotSavedInProject + nameString)
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

  def setProjectEly(currentProjectId: Long, newEly: Long): Option[String] = {
    getProjectEly(currentProjectId).filterNot(_ == newEly).map { currentProjectEly =>
      logger.info(s"The project can not handle multiple ELY areas (the project ELY range is $currentProjectEly). Recording was discarded.")
      s"Projektissa ei voi käsitellä useita ELY-alueita (projektin ELY-alue on $currentProjectEly). Tallennus hylättiin."
    }.orElse {
      projectDAO.updateProjectEly(currentProjectId, newEly)
      None
    }
  }

  def getProjectEly(projectId: Long): Option[Long] = {
    projectDAO.fetchProjectElyById(projectId)
  }

  /**
    * Main validator method.
    * Calls and executes all the validations we have for a project.
    * @param projectId: Long - Project ID
    * @param newSession: Boolean - Will determine if we open a new database sesssion
    * @return A sequence of validation errors, can be empty.
    */
  def validateProjectById(projectId: Long, newSession: Boolean = true): Seq[projectValidator.ValidationErrorDetails] = {
    if (newSession) {
      withDynSession {
        projectValidator.validateProject(projectDAO.fetchById(projectId).get, projectLinkDAO.fetchProjectLinks(projectId))
      }
    }
    else {
      projectValidator.validateProject(projectDAO.fetchById(projectId).get, projectLinkDAO.fetchProjectLinks(projectId))
    }
  }

  def validateLinkTrack(track: Int): Boolean = {
    Track.values.filterNot(_.value == Track.Unknown.value).exists(_.value == track)
  }

  def sendProjectsInWaiting(): Unit = {
    logger.info(s"Finding projects whose status is SendingToTRStatus/ ${SendingToTR.description}")
    val listOfProjects = getProjectsWaitingForTR
    logger.info(s"Found ${listOfProjects.size} projects")
    for (projectId <- listOfProjects) {
      logger.info(s"Trying to publish project: $projectId")
      publishProject(projectId)
    }

  }

  private def getProjectsWaitingForTR: Seq[Long] = {
    withDynSession {
      projectDAO.fetchProjectIdsWithSendingToTRStatus
    }
  }

  case class PublishResult(validationSuccess: Boolean, sendSuccess: Boolean, errorMessage: Option[String])

}

class SplittingException(s: String) extends RuntimeException {
  override def getMessage: String = s
}

case class ProjectBoundingBoxResult(projectLinkResultF: Future[Seq[ProjectLink]], roadLinkF: Future[Seq[RoadLink]],
                                    complementaryF: Future[Seq[RoadLink]], suravageF: Future[Seq[VVHRoadlink]])


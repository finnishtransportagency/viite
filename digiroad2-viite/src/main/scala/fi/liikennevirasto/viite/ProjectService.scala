package fi.liikennevirasto.viite

import java.io.IOException
import java.sql.SQLException
import java.util.Date

import fi.liikennevirasto.digiroad2._
import fi.liikennevirasto.digiroad2.asset.SideCode.AgainstDigitizing
import fi.liikennevirasto.digiroad2.asset.{BoundingRectangle, LinkGeomSource, TrafficDirection, _}
import fi.liikennevirasto.digiroad2.client.vvh.{VVHHistoryRoadLink, VVHRoadlink}
import fi.liikennevirasto.digiroad2.dao.Sequences
import fi.liikennevirasto.digiroad2.linearasset.{RoadLink, RoadLinkLike}
import fi.liikennevirasto.digiroad2.oracle.OracleDatabase
import fi.liikennevirasto.digiroad2.service.RoadLinkService
import fi.liikennevirasto.digiroad2.util.LogUtils.time
import fi.liikennevirasto.digiroad2.util.{RoadAddressException, RoadPartReservedException, Track}
import fi.liikennevirasto.viite.dao.CalibrationPointDAO.UserDefinedCalibrationPoint
import fi.liikennevirasto.viite.dao.Discontinuity.Continuous
import fi.liikennevirasto.viite.dao.FloatingReason.{NewAddressGiven, NoFloating}
import fi.liikennevirasto.viite.dao.LinkStatus._
import fi.liikennevirasto.viite.dao.ProjectState._
import fi.liikennevirasto.viite.dao.TerminationCode.{NoTermination, Subsequent, Termination}
import fi.liikennevirasto.viite.dao.{LinkStatus, ProjectDAO, RoadwayDAO, _}
import fi.liikennevirasto.viite.model.{Anomaly, ProjectAddressLink, RoadAddressLink}
import fi.liikennevirasto.viite.process._
import fi.liikennevirasto.viite.util.{CalibrationPointsUtils, ProjectLinkSplitter, SplitOptions, SplitResult}
import org.apache.http.client.ClientProtocolException
import org.joda.time.DateTime
import org.slf4j.LoggerFactory
import slick.driver.JdbcDriver.backend.Database.dynamicSession

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future}
import scala.util.control.NonFatal

case class PreFillInfo(RoadNumber: BigInt, RoadPart: BigInt, roadName: String)

case class LinkToRevert(id: Long, linkId: Long, status: Long, geometry: Seq[Point])

class ProjectService(roadAddressService: RoadAddressService, roadLinkService: RoadLinkService, eventbus: DigiroadEventBus) {

  def withDynTransaction[T](f: => T): T = OracleDatabase.withDynTransaction(f)

  def withDynSession[T](f: => T): T = OracleDatabase.withDynSession(f)

  private val logger = LoggerFactory.getLogger(getClass)
  val roadwayDAO = new RoadwayDAO
  val linearLocationDAO = new LinearLocationDAO
  val projectDAO = new ProjectDAO
  val projectLinkDAO = new ProjectLinkDAO
  val projectReservedPartDAO = new ProjectReservedPartDAO
  val projectValidator = new ProjectValidator
  val roadwayAddressMapper = new RoadwayAddressMapper(roadwayDAO, linearLocationDAO)
  val allowedSideCodes = List(SideCode.TowardsDigitizing, SideCode.AgainstDigitizing)

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
      val links = projectLinkDAO.getProjectLinks(projectId)
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
  private def createProject(roadAddressProject: RoadAddressProject): RoadAddressProject = {
    val id = Sequences.nextViitePrimaryKeySeqValue
    val project = roadAddressProject.copy(id = id)
    projectDAO.createRoadAddressProject(project)
    val error = addLinksToProject(project)
    if (error.nonEmpty)
      throw new RoadPartReservedException(error.get)
    projectDAO.getRoadAddressProjectById(id).get
  }

  private def projectFound(roadAddressProject: RoadAddressProject): Option[RoadAddressProject] = {
    val newRoadAddressProject = 0
    if (roadAddressProject.id == newRoadAddressProject) return None
    withDynTransaction {
      return projectDAO.getRoadAddressProjectById(roadAddressProject.id)
    }
  }

  def fetchPreFillFromVVH(linkId: Long): Either[String, PreFillInfo] = {
    parsePreFillData(roadLinkService.getVVHRoadlinks(Set(linkId)))
  }

  def parsePreFillData(vvhRoadLinks: Seq[VVHRoadlink]): Either[String, PreFillInfo] = {
    withDynSession{
      if (vvhRoadLinks.isEmpty) {
        Left("Link could not be found in VVH")
      }
      else {
        val vvhLink = vvhRoadLinks.head
        (vvhLink.attributes.get("ROADNUMBER"), vvhLink.attributes.get("ROADPARTNUMBER")) match {
          case (Some(roadNumber: BigInt), Some(roadPartNumber: BigInt)) =>
            val roadName = RoadNameDAO.getLatestRoadName(roadNumber.toLong)
            Right(PreFillInfo(roadNumber, roadPartNumber, if( roadName.isEmpty) "" else roadName.get.roadName))
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

  def checkRoadPartExistsAndReservable(roadNumber: Long, startPart: Long, endPart: Long, projectDate: DateTime): Either[String, Seq[ProjectReservedPart]] = {
    withDynTransaction {
      checkRoadPartsExist(roadNumber, startPart, endPart) match {
        case None => checkRoadPartsReservable(roadNumber, startPart, endPart) match {
          case Left(err) => Left(err)
          case Right(reservedRoadParts) => {
            if (reservedRoadParts.isEmpty) {
              Right(reservedRoadParts)
            } else {
              validateProjectDate(reservedRoadParts, projectDate) match {
                case Some(errMsg) => Left(errMsg)
                case None => Right(reservedRoadParts)
              }
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
      projectWritableCheck(projectId) match {
        case Some(_) => false
        case None => true
      }
  }

  private def projectWritableCheck(projectId: Long): Option[String] = {
    projectDAO.getProjectStatus(projectId) match {
      case Some(projectState) =>
        if (projectState == ProjectState.Incomplete || projectState == ProjectState.ErrorInViite || projectState == ProjectState.ErrorInTR)
          return None
        Some("Projektin tila ei ole keskeneräinen") //project state is not incomplete
      case None => Some("Projektia ei löytynyt") //project could not be found
    }
  }

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

  private def setProjectRoadName(projectId: Long, roadNumber: Long, roadName: String) = {
    ProjectLinkNameDAO.get(roadNumber, projectId) match {
      case Some(projectLinkName) => ProjectLinkNameDAO.update(projectLinkName.id, roadName)
      case _ =>
        val existingRoadName = RoadNameDAO.getLatestRoadName(roadNumber).headOption
        ProjectLinkNameDAO.create(projectId, roadNumber, existingRoadName.map(_.roadName).getOrElse(roadName))
    }
  }

  def writableWithValidTrack(projectId: Long, track: Int): Option[String] = {
    if (!isWritableState(projectId)) Some(projectNotWritable)
    else if (!validateLinkTrack(track)) Some("Invalid track code")
    else None
  }

  def createProjectLinks(linkIds: Seq[Long], projectId: Long, roadNumber: Long, roadPartNumber: Long, track: Track,
                         discontinuity: Discontinuity, roadType: RoadType, roadLinkSource: LinkGeomSource,
                         roadEly: Long, user: String, roadName: String): Map[String, Any] = {
    withDynSession {
      writableWithValidTrack(projectId, track.value) match {
        case None =>
          val linkId = linkIds.head
          val roadLinks = (if (roadLinkSource != LinkGeomSource.SuravageLinkInterface) {
            roadLinkService.getRoadLinksByLinkIdsFromVVH(linkIds.toSet)
          } else {
            roadLinkService.getSuravageRoadLinksFromVVH(linkIds.toSet)
          }).map(l => l.linkId -> l).toMap
          if (roadLinks.keySet != linkIds.toSet)
            return Map("success" -> false,
              "errorMessage" -> (linkIds.toSet -- roadLinks.keySet).mkString(ErrorRoadLinkNotFound + " puuttuvat id:t ", ", ", ""))
          val project = projectDAO.getRoadAddressProjectById(projectId).getOrElse(throw new RuntimeException(s"Missing project $projectId"))

          val projectLinks: Seq[ProjectLink] = linkIds.map { id =>
            newProjectLink(roadLinks(id), project, roadNumber, roadPartNumber, track, discontinuity, roadType, roadEly, roadName)
          }
          saveProjectCoordinates(project.id, calculateProjectCoordinates(project.id))
          setProjectEly(projectId, roadEly) match {
            case Some(errorMessage) => Map("success" -> false, "errorMessage" -> errorMessage)
            case None => {
              addNewLinksToProject(sortRamps(projectLinks, linkIds), projectId, user, linkId, false) match {
                case Some(errorMessage) => Map("success" -> false, "errorMessage" -> errorMessage)
                case None => Map("success" -> true, "projectErrors" -> validateProjectById(projectId, false))
              }
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

  def getFirstProjectLink(project: RoadAddressProject): Option[ProjectLink] = {
    project.reservedParts.find(_.startingLinkId.nonEmpty) match {
      case Some(rrp) =>
        withDynSession {
          projectLinkDAO.fetchFirstLink(project.id, rrp.roadNumber, rrp.roadPartNumber)
        }
      case _ => None
    }
  }

  def changeDirection(projectId: Long, roadNumber: Long, roadPartNumber: Long, links: Seq[LinkToRevert], coordinates: ProjectCoordinates, username: String): Option[String] = {
    RoadAddressLinkBuilder.municipalityRoadMaintainerMapping // make sure it is populated outside of this TX
    try {
      withDynTransaction {
        projectWritableCheck(projectId) match {
          case None => {
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
            val projectLinks = projectLinkDAO.getProjectLinks(projectId).filter(pl => {
              pl.status != LinkStatus.Terminated && pl.roadNumber == roadNumber && pl.roadPartNumber == roadPartNumber
            })
            val originalSideCodes = linearLocationDAO.fetchByRoadways(projectLinks.map(_.roadwayId).toSet)
              .map(l => l.id -> l.sideCode).toMap

            val originalAddresses = roadAddressService.getRoadAddressesByRoadwayIds(projectLinks.map(_.roadwayId))

            projectLinkDAO.updateProjectLinksToDB(projectLinks.map(x =>
              x.copy(reversed = isReversed(originalSideCodes)(x),
                discontinuity = newContinuity.getOrElse(x.endAddrMValue, Discontinuity.Continuous))), username, originalAddresses)
            CalibrationPointDAO.removeAllCalibrationPoints(projectLinks.map(_.id).toSet)
            recalculateProjectLinks(projectId, username, Set((roadNumber, roadPartNumber)))
            saveProjectCoordinates(projectId, coordinates)
            None
          }
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
      case Some(sideCode) if sideCode != projectLink.sideCode => true
      case _ => false
    }
  }

  /**
    *  Checks if the road part that user wants to reserve exists
    *  Checks if the road parts that user tries to reserve have different ely    *
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
    if(differingElyParts.nonEmpty){
      val allDifferingElyParts = Seq(allPartsToReserve.head) ++ differingElyParts
      Some(s"$ErrorFollowingPartsHaveDifferingEly " ++ allDifferingElyParts.map(op => {
        s"TIE ${op.roadNumber} OSA: ${op.roadPartNumber} ELY: ${op.ely.getOrElse(-1)}"
      }).mkString(" ja "))
    } else if (roadways.isEmpty && !projectLinks.exists(pl => pl.roadNumber == reservedRoadParts.roadNumber && pl.roadPartNumber == reservedRoadParts.roadPartNumber))
      Some(s"$ErrorFollowingRoadPartsNotFoundInDB TIE ${reservedRoadParts.roadNumber} OSA: ${reservedRoadParts.roadPartNumber}")
    else if ((projectLinks.exists(_.ely != reservedRoadParts.ely.get) || roadways.exists(_.ely != reservedRoadParts.ely.get)) && (projectEly.isEmpty || projectEly.get != defaultProjectEly ))
      Some(s"$ErrorFollowingPartsHaveDifferingEly TIE ${reservedRoadParts.roadNumber} OSA: ${reservedRoadParts.roadPartNumber}")
    else
      None
  }

  /**
    * Adds reserved road links (from road parts) to a road address project. Clears
    * project links that are no longer reserved for the project. Reservability is check before this.
    * for each reserved part get all roadways
    * validate if the road exists on the roadway table and if there isn't different ely codes reserved
    *     in case there is, throw roadPartReserved exception
    * get the road links from the suravage and from the regular interface
    * map the road links into road address objects
    * check, make the reservation and update the ely code of the project
    * map the addresses into project links
    * insert the new project links and remove the ones that were unreserved
    */
  private def addLinksToProject(project: RoadAddressProject): Option[String] = {
    logger.info(s"Adding reserved road parts with links to project ${project.id}")
    val projectLinks = projectLinkDAO.getProjectLinks(project.id)
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
            val regularMapping = roadLinkService.getRoadLinksByLinkIdsFromVVH(regular.map(_.linkId).toSet).map(rm => rm.linkId -> rm).toMap
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

  def splitSuravageLink(track: Int, projectId: Long, coordinates: ProjectCoordinates, linkId: Long, username: String,
                        splitOptions: SplitOptions): Option[String] = {
    withDynTransaction {
      writableWithValidTrack(projectId, track) match {
        case None => {
          saveProjectCoordinates(projectId, coordinates)
          splitSuravageLinkInTX(linkId, username, splitOptions) }
        case Some(error) => Some(error)
      }
    }
  }

  def preSplitSuravageLinkInTX(linkId: Long, username: String,
                               splitOptions: SplitOptions): (Option[SplitResult], Option[String], Option[(Point, Vector3d)]) = {
    val projectId = splitOptions.projectId
    val sOption = roadLinkService.getSuravageRoadLinksByLinkIdsFromVVH(Set(Math.abs(linkId))).headOption
    val previousSplit = projectLinkDAO.fetchSplitLinks(projectId, linkId)
    val project = projectDAO.getRoadAddressProjectById(projectId).get
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
        val splitResult = ProjectLinkSplitter.split(roadLink.get, newProjectLink(suravageLink, project, splitOptions), bestFit, projectLinks, splitOptions)
        (Some(splitResult), None, GeometryUtils.calculatePointAndHeadingOnGeometry(suravageLink.geometry, splitOptions.splitPoint))
      }
    }
  }

  def splitSuravageLinkInTX(linkId: Long, username: String, splitOptions: SplitOptions): Option[String] = {
    val (splitResultOption, errorMessage, _) = preSplitSuravageLinkInTX(linkId, username, splitOptions)
    if (errorMessage.nonEmpty) {
      errorMessage
    } else {
      splitResultOption.map {
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

  def getProjectLinksInBoundingBox(bbox: BoundingRectangle, projectId: Long): (Seq[ProjectLink]) = {
    val roadLinks = roadLinkService.getRoadLinksAndComplementaryFromVVH(bbox, Set()).map(rl => rl.linkId -> rl).toMap
    projectLinkDAO.getProjectLinksByProjectAndLinkId(Set(), roadLinks.keys.toSeq, projectId).filter(_.status == LinkStatus.NotHandled)
  }

  /**
    * Save road link project, reserve new road parts, free previously reserved road parts that were removed
    *
    * @param roadAddressProject Updated road address project case class
    * @return Updated project reloaded from the database
    */
  def saveProject(roadAddressProject: RoadAddressProject): RoadAddressProject = {
    if (projectFound(roadAddressProject).isEmpty)
      throw new IllegalArgumentException("Project not found")
    withDynTransaction {
      if (isWritableState(roadAddressProject.id)) {
        validateProjectDate(roadAddressProject.reservedParts, roadAddressProject.startDate) match {
          case Some(errMsg) => throw new IllegalStateException(errMsg)
          case None => {
            if (projectDAO.uniqueName(roadAddressProject.id, roadAddressProject.name)) {
              val storedProject = projectDAO.getRoadAddressProjectById(roadAddressProject.id).get
              val removed = storedProject.reservedParts.filterNot(part =>
                roadAddressProject.reservedParts.exists(rp => rp.roadPartNumber == part.roadPartNumber &&
                  rp.roadNumber == part.roadNumber))
              removed.foreach(p => projectReservedPartDAO.removeReservedRoadPart(roadAddressProject.id, p))
              removed.groupBy(_.roadNumber).keys.foreach(ProjectLinkNameDAO.revert(_, roadAddressProject.id))
              addLinksToProject(roadAddressProject)
              val updatedProject = projectDAO.getRoadAddressProjectById(roadAddressProject.id).get
              if (updatedProject.reservedParts.nonEmpty) {
                projectDAO.updateRoadAddressProject(roadAddressProject.copy(ely = projectLinkDAO.getElyFromProjectLinks(roadAddressProject.id)))
              } else { //in empty case we release ely
                projectDAO.updateRoadAddressProject(roadAddressProject.copy(ely = None))
              }
              val savedProject = projectDAO.getRoadAddressProjectById(roadAddressProject.id).get
              saveProjectCoordinates(savedProject.id, calculateProjectCoordinates(savedProject.id))
              savedProject
            } else {
              throw new NameExistsException(s"Nimellä ${roadAddressProject.name} on jo olemassa projekti. Muuta nimeä.")
            }
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
      val project = projectDAO.getRoadAddressProjectById(projectId)
      val canBeDeleted = projectId != 0 && project.isDefined && project.get.status == ProjectState.Incomplete
      if (canBeDeleted) {
        val links = projectLinkDAO.getProjectLinks(projectId)
        projectLinkDAO.removeProjectLinksByProject(projectId)
        projectReservedPartDAO.removeReservedRoadPartsByProject(projectId)
        links.groupBy(_.roadNumber).keys.foreach(ProjectLinkNameDAO.revert(_, projectId))
        projectDAO.updateProjectStatus(projectId, ProjectState.Deleted)
        projectDAO.updateProjectStateInfo(ProjectState.Deleted.description, projectId)
      }
      canBeDeleted
    }
  }

  def createRoadLinkProject(roadAddressProject: RoadAddressProject): RoadAddressProject = {
    if (roadAddressProject.id != 0)
      throw new IllegalArgumentException(s"Road address project to create has an id ${roadAddressProject.id}")
    withDynTransaction {
      if (projectDAO.uniqueName(roadAddressProject.id, roadAddressProject.name)) {
        val savedProject = createProject(roadAddressProject)
        saveProjectCoordinates(savedProject.id, calculateProjectCoordinates(savedProject.id))
        savedProject
      } else {
        throw new NameExistsException(s"Nimellä ${roadAddressProject.name} on jo olemassa projekti. Muuta nimeä.")
      }
    }
  }

  def getSingleProjectById(projectId: Long): Option[RoadAddressProject] = {
    withDynTransaction {
      projectDAO.getProjects(projectId).headOption
    }
  }

  def getAllProjects: Seq[RoadAddressProject] = {
    withDynTransaction {
      projectDAO.getProjects()
    }
  }

  def updateProjectLinkGeometry(projectId: Long, username: String, onlyNotHandled: Boolean = false): Unit = {
    withDynTransaction {
      val projectLinks = projectLinkDAO.getProjectLinks(projectId, if (onlyNotHandled) Some(LinkStatus.NotHandled) else None)
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

  //Needed for VIITE-1591 batch to import all VARCHAR geometry into Sdo_Geometry collumn. Remove after apply batch
  def updateProjectLinkSdoGeometry(projectId: Long, username: String, onlyNotHandled: Boolean = false): Unit = {
    withDynTransaction {
      val linksGeometry = projectLinkDAO.getProjectLinksGeometry(projectId, if (onlyNotHandled) Some(LinkStatus.NotHandled) else None)
      println(s"Got ${linksGeometry.size} links from project $projectId")
      linksGeometry.foreach(pl => projectLinkDAO.updateGeometryStringToSdo(pl._1, pl._2))
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
  private def checkAndReserve(project: RoadAddressProject, reservedRoadPart: ProjectReservedPart): (Option[ProjectReservedPart], Option[String]) = {
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
      projectLinkDAO.getProjectLinks(projectId)
    }
  }

  //This method will not be used now because we don't want to show suravage in project mode. It will be used in future
  def getProjectLinksWithSuravage(roadAddressService: RoadAddressService, projectId: Long, boundingRectangle: BoundingRectangle,
                                  roadNumberLimits: Seq[(Int, Int)], municipalities: Set[Int], everything: Boolean = false,
                                  publicRoads: Boolean = false): Seq[ProjectAddressLink] = {
    val fetch = fetchBoundingBoxF(boundingRectangle, projectId, roadNumberLimits, municipalities, everything, publicRoads)
    val suravageList = withDynSession {
      Await.result(fetch.suravageF, Duration.Inf)
        .map(x => (x, Some(projectId))).map(RoadAddressLinkBuilder.buildSuravageRoadAddressLink)
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
        val roadwayChanges = RoadwayChangesDAO.fetchRoadwayChanges(Set(projectId))
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

  def prettyPrintLog(roadwayChanges: List[ProjectRoadwayChange]) = {
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
    val roadwayChanges = RoadwayChangesDAO.fetchRoadwayChanges(projectId)
    prettyPrintLog(roadwayChanges)
    logger.info(s"Sending changes to TR")
    val sentObj = ViiteTierekisteriClient.sendChanges(roadwayChanges)
    logger.info(s"Changes Sent to TR")
    sentObj
  }

  def getProjectRoadLinksByLinkIds(linkIdsToGet: Set[Long]): Seq[ProjectAddressLink] = {
    throw new NotImplementedError("Will be implemented at VIITE-1539")
    //    if (linkIdsToGet.isEmpty)
    //      return Seq()
    //
    //    val complementedRoadLinks = time(logger, "Fetch VVH road links") {
    //      roadLinkService.getRoadLinksByLinkIdsFromVVH(linkIdsToGet, useFrozenVVHLinks)
    //    }
    //
    //    val projectRoadLinks = complementedRoadLinks
    //      .map { rl =>
    //        val ra = Seq()
    //        val missed = Seq()
    //        rl.linkId -> roadAddressService.buildRoadAddressLink(rl, ra, missed)
    //      }.toMap
    //
    //    val filledProjectLinks = RoadAddressFiller.fillTopology(complementedRoadLinks, projectRoadLinks)
    //
    //    filledProjectLinks._1.map(ProjectAddressLinkBuilder.build)
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

    val missedRL = withDynTransaction {
        val missingLinkIds = linkIds -- floating.keySet -- addresses.keySet -- projectLinks.map(_.linkId).toSet
        roadAddressService.fetchUnaddressedRoadLinksByLinkIds(missingLinkIds)
      }.groupBy(_.linkId)
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
    withDynTransaction{
      projectLinkDAO.getProjectLinksHistory(projectId)
    }
  }

  def fetchProjectRoadLinksLinearGeometry(projectId: Long, boundingRectangle: BoundingRectangle, roadNumberLimits: Seq[(Int, Int)], municipalities: Set[Int],
                                          everything: Boolean = false, publicRoads: Boolean = false): Seq[ProjectAddressLink] = {
    val fetchRoadAddressesByBoundingBoxF = Future(withDynTransaction {
      val (floating, addresses) = roadAddressService.getRoadAddressesByBoundingBox(boundingRectangle,
        roadNumberLimits = roadNumberLimits).partition(_.isFloating)
      (floating.groupBy(_.linkId), addresses.groupBy(_.linkId))
    })

    val fetchProjectLinksF = Future(withDynSession {
      val projectState = projectDAO.getProjectStatus(projectId)
      if (projectState.isDefined && projectState.get == Saved2TR)
        projectLinkDAO.getProjectLinksHistory(projectId).groupBy(_.linkId)
      else
        projectLinkDAO.getProjectLinks(projectId).groupBy(_.linkId)
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
      address.linkId -> RoadAddressLinkBuilder.build(address)
    }.toMap

    logger.info("Build road addresses completed in %d ms".format(System.currentTimeMillis() - buildStartTime))


    nonProjectLinks.values.toSeq.map(ProjectAddressLinkBuilder.build) ++ projectRoadLinks.values.flatten
  }


  def fetchBoundingBoxF(boundingRectangle: BoundingRectangle, projectId: Long, roadNumberLimits: Seq[(Int, Int)], municipalities: Set[Int],
                        everything: Boolean = false, publicRoads: Boolean = false): ProjectBoundingBoxResult = {
    ProjectBoundingBoxResult(
      Future(withDynSession(projectLinkDAO.getProjectLinks(projectId))),
      Future(roadLinkService.getRoadLinksFromVVH(boundingRectangle, roadNumberLimits, municipalities, everything, publicRoads)),
      Future(
        if (everything) roadLinkService.getComplementaryRoadLinksFromVVH(boundingRectangle, municipalities)
        else Seq()),
      roadLinkService.getSuravageLinksFromVVHF(boundingRectangle, municipalities)
    )
  }

  def getProjectRoadLinks(projectId: Long, boundingRectangle: BoundingRectangle, roadNumberLimits: Seq[(Int, Int)], municipalities: Set[Int],
                          everything: Boolean = false, publicRoads: Boolean = false): Seq[ProjectAddressLink] = {
    val fetch = fetchBoundingBoxF(boundingRectangle, projectId, roadNumberLimits, municipalities, everything, publicRoads)
    fetchProjectRoadLinks(projectId, boundingRectangle, roadNumberLimits, municipalities, everything, publicRoads, fetch)
  }

  private def getProjectWithReservationChecks(projectId: Long, newRoadNumber: Long, newRoadPart: Long, linkStatus: LinkStatus, projectLinks: Seq[ProjectLink]): RoadAddressProject = {
    projectValidator.checkProjectExists(projectId)
    val project = projectDAO.getRoadAddressProjectById(projectId).get
    projectValidator.checkReservedExistence(project, newRoadNumber, newRoadPart, linkStatus, projectLinks)
    projectValidator.checkAvailable(newRoadNumber, newRoadPart, project)
    projectValidator.checkNotReserved(newRoadNumber, newRoadPart, project)
    project
  }

  def revertLinks(links: Iterable[ProjectLink], userName: String): Option[String] = {
    if (links.groupBy(l => (l.projectId, l.roadNumber, l.roadPartNumber)).keySet.size != 1)
      throw new IllegalArgumentException("Reverting links from multiple road parts at once is not allowed")
    val l = links.head
    revertLinks(l.projectId, l.roadNumber, l.roadPartNumber, links.map(
      link => LinkToRevert(link.id, link.linkId, link.status.value, link.geometry)), userName)
  }

  def revertRoadName(projectId: Long, roadNumber: Long): Unit = {
    if (projectLinkDAO.getProjectLinks(projectId).exists(pl => pl.roadNumber == roadNumber) && RoadNameDAO.getLatestRoadName(roadNumber).nonEmpty) {
      ProjectLinkNameDAO.revert(roadNumber, projectId)
      val roadAddressName = RoadNameDAO.getLatestRoadName(roadNumber)
      val projectRoadName = ProjectLinkNameDAO.get(roadNumber, projectId)
      if (roadAddressName.nonEmpty && projectRoadName.isEmpty) {
        ProjectLinkNameDAO.create(projectId, roadNumber, roadAddressName.get.roadName)
      }
    }
    if (!projectLinkDAO.getProjectLinks(projectId).exists(pl => pl.roadNumber == roadNumber)) {
      ProjectLinkNameDAO.revert(roadNumber, projectId)
    }
  }

  private def revertLinks(projectId: Long, roadNumber: Long, roadPartNumber: Long, toRemove: Iterable[LinkToRevert],
                          modified: Iterable[LinkToRevert], userName: String, recalculate: Boolean = true): Unit = {
    val modifiedLinkIds = modified.map(_.linkId).toSet
    projectLinkDAO.removeProjectLinksByLinkId(projectId, toRemove.map(_.linkId).toSet)
    val vvhRoadLinks = roadLinkService.getCurrentAndComplementaryAndSuravageRoadLinksFromVVH(modifiedLinkIds)
    val roadAddresses = roadwayAddressMapper.getRoadAddressesByLinearLocation(linearLocationDAO.fetchRoadwayByLinkId(modifiedLinkIds))
    roadAddresses.foreach(ra =>
      modified.find(mod => mod.linkId == ra.linkId) match {
        case Some(mod) if mod.geometry.nonEmpty => {
          checkAndReserve(projectDAO.getRoadAddressProjectById(projectId).get, toReservedRoadPart(ra.roadNumber, ra.roadPartNumber, ra.ely))
          val vvhGeometry = vvhRoadLinks.find(roadLink => roadLink.linkId == mod.linkId && roadLink.linkSource == ra.linkGeomSource)
          val geom = GeometryUtils.truncateGeometry3D(vvhGeometry.get.geometry, ra.startMValue, ra.endMValue)
          projectLinkDAO.updateProjectLinkValues(projectId, ra.copy(geometry = geom))
        }
        case _ => {
          checkAndReserve(projectDAO.getRoadAddressProjectById(projectId).get, toReservedRoadPart(ra.roadNumber, ra.roadPartNumber, ra.ely))
          projectLinkDAO.updateProjectLinkValues(projectId, ra, updateGeom = false)
        }
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
      withDynSession(projectDAO.getProjectsWithGivenLinkId(linkId))
    if (projects.isEmpty)
      return false
    true
  }

  def toReservedRoadPart(roadNumber: Long, roadPartNumber: Long, ely: Long): ProjectReservedPart = {
    ProjectReservedPart(0L, roadNumber, roadPartNumber,
      None, None, Some(ely),
      None, None, None, None, false)
  }


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

  def revertLinks(projectId: Long, roadNumber: Long, roadPartNumber: Long, links: Iterable[LinkToRevert], coordinates: ProjectCoordinates, userName: String): Option[String] = {
    try {
      withDynTransaction {
        projectWritableCheck(projectId) match {
          case None => {
            revertLinks(projectId, roadNumber, roadPartNumber, links, userName) match {
              case None => {
                saveProjectCoordinates(projectId, coordinates)
                None
              }
              case Some(error) => Some(error)
            }}
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
      val part = projectReservedPartDAO.fetchReservedRoadPart(roadNumber, roadPartNumber)
      if (part.isEmpty) {
        projectReservedPartDAO.removeReservedRoadPart(projectId, roadNumber, roadPartNumber)
      } else {
        projectReservedPartDAO.removeReservedRoadPart(projectId, part.get)
      }
    } else {
      val links = projectLinkDAO.fetchByProjectRoadPart(roadNumber, roadPartNumber, projectId)
      revertLinks(links, userName)
    }
  }

  /**
    * Update project links to given status and recalculate delta and change table
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
                         reversed: Boolean = false, roadName: Option[String] = None): Option[String] = {
    def isCompletelyNewPart(toUpdateLinks: Seq[ProjectLink]): (Boolean, Long, Long) = {
      val reservedPart = projectReservedPartDAO.fetchReservedRoadPart(toUpdateLinks.head.roadNumber, toUpdateLinks.head.roadPartNumber).get
      val newSavedLinks = projectLinkDAO.getProjectLinksByProjectRoadPart(reservedPart.roadNumber, reservedPart.roadPartNumber, projectId)
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
          projectLinkDAO.updateProjectLinksToDB(linksToUpdate, userName, originalAddresses)
        }
        projectLinkDAO.updateProjectLinksToDB(Seq(lastSegment.copy(discontinuity = Discontinuity.apply(discontinuity.toInt))), userName, originalAddresses)
      }
    }

    def checkAndMakeReservation(projectId: Long, newRoadNumber: Long, newRoadPart: Long, linkStatus: LinkStatus, projectLinks: Seq[ProjectLink]): (Boolean, Option[Long], Option[Long]) = {
      val project = getProjectWithReservationChecks(projectId, newRoadNumber, newRoadPartNumber, linkStatus, projectLinks)
      try {
        val (toReplace, road, part) = isCompletelyNewPart(projectLinks)
        if (toReplace && linkStatus == New) {
          val reservedPart = projectReservedPartDAO.fetchReservedRoadPart(road, part).get
          projectReservedPartDAO.removeReservedRoadPart(projectId, reservedPart)
          val newProjectLinks: Seq[ProjectLink] = projectLinks.map(pl => pl.copy(id = NewProjectLink,
            roadNumber = newRoadNumber, roadPartNumber = newRoadPartNumber, track = Track.apply(newTrackCode),
            roadType = RoadType.apply(roadType.toInt), discontinuity = Discontinuity.apply(discontinuity.toInt),
            endAddrMValue = userDefinedEndAddressM.getOrElse(pl.endAddrMValue.toInt).toLong))
          if (linkIds.nonEmpty) {
            addNewLinksToProject(sortRamps(newProjectLinks, linkIds), projectId, userName, linkIds.head, false)
          } else {
            val newSavedLinkIds = projectLinks.map(_.linkId)
            addNewLinksToProject(sortRamps(newProjectLinks, newSavedLinkIds), projectId, userName, newSavedLinkIds.head, false)
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
        val toUpdateLinks = projectLinkDAO.getProjectLinksByProjectAndLinkId(ids, linkIds, projectId)
        if (toUpdateLinks.exists(_.isSplit))
          throw new ProjectValidationException(ErrorSplitSuravageNotUpdatable)
        userDefinedEndAddressM.map(addressM => {
          val endSegment = toUpdateLinks.maxBy(_.endAddrMValue)
          val calibrationPoint = UserDefinedCalibrationPoint(newCalibrationPointId, endSegment.id, projectId, addressM.toDouble - endSegment.startMValue, addressM)
          val foundCalibrationPoint = CalibrationPointDAO.findEndCalibrationPoint(endSegment.id, projectId)
          if (foundCalibrationPoint.isEmpty)
            CalibrationPointDAO.createCalibrationPoint(calibrationPoint)
          else
            CalibrationPointDAO.updateSpecificCalibrationPointMeasures(foundCalibrationPoint.head.id, addressM.toDouble - endSegment.startMValue, addressM)
          Seq(CalibrationPoint)
        })
        linkStatus match {
          case LinkStatus.Terminated =>
            // Fetching road addresses in order to obtain the original addressMValues, since we may not have those values
            // on project_link table, after previous recalculations
            resetLinkValues(toUpdateLinks)
            projectLinkDAO.updateProjectLinksToTerminated(toUpdateLinks.map(_.id).toSet, userName)

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
              projectLinkDAO.updateProjectLinkNumbering(toUpdateLinks.map(_.id), linkStatus, newRoadNumber, newRoadPartNumber, userName, discontinuity)
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
            projectLinkDAO.updateProjectLinksToDB(updated, userName, originalAddresses)
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

    val projectLinks = projectLinkDAO.getProjectLinks(projectId)
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
      projectLinkDAO.updateProjectLinksToDB(recalculated ++ recalculatedTerminated, userName, originalAddresses)
    }
  }

  private def recalculateChangeTable(projectId: Long): Boolean = {
    val projectOpt = projectDAO.getRoadAddressProjectById(projectId)
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
    * method to check if project is publishable. add filters for cases we do not want to prevent sending
    *
    * @param projectId project-id
    * @return if project contains any notifications preventing sending
    */
  def isProjectPublishable(projectId: Long): Boolean = {
    validateProjectById(projectId).isEmpty
  }

  def allLinksHandled(projectId: Long): Boolean = { //some tests want to know if all projectLinks have been handled. to remove this test need to be updated to check if termination is correctly applied etc best done after all validations have been implemented
    withDynSession {
      projectLinkDAO.getProjectLinks(projectId, Some(LinkStatus.NotHandled)).isEmpty &&
        projectLinkDAO.getProjectLinks(projectId).nonEmpty
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
      val projects = projectDAO.getProjects(projectId)
      val rotatingTR_Id = projectDAO.getRotatingTRProjectId(projectId)
      projectDAO.updateProjectStatus(projectId, ProjectState.Incomplete)
      val addedStatus = if (rotatingTR_Id.isEmpty) "" else "[OLD TR_ID was " + rotatingTR_Id.head + "]"
      if (projects.isEmpty)
        return Some("Projektia ei löytynyt")
      val project = projects.head
      appendStatusInfo(project, addedStatus)
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
  private def appendStatusInfo(project: RoadAddressProject, appendMessage: String): Unit = {
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
    projectDAO.removeRotatingTRProjectId(project.id)
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
        projectDAO.addRotatingTRProjectId(projectId) //Generate new TR_ID
        val trProjectStateMessage = getRoadwayChangesAndSendToTR(Set(projectId))
        if (trProjectStateMessage.status == ProjectState.Failed2GenerateTRIdInViite.value) {
          return PublishResult(validationSuccess = false, sendSuccess = false, Some(trProjectStateMessage.reason))
        }
        trProjectStateMessage.status match {
          case it if 200 until 300 contains it =>
            setProjectStatus(projectId, Sent2TR, false)
            logger.info(s"Sending to TR successful: ${trProjectStateMessage.reason}")
            PublishResult(validationSuccess = true, sendSuccess = true, Some(trProjectStateMessage.reason))

          case _ =>
            //rollback
            logger.info(s"Sending to TR failed: ${trProjectStateMessage.reason}")
            val returningMessage = if (trProjectStateMessage.reason.nonEmpty) trProjectStateMessage.reason else trMessageRefusal
            PublishResult(validationSuccess = true, sendSuccess = false, Some(returningMessage))
        }
      } catch {
        //Exceptions taken out val response = client.execute(request) of sendJsonMessage in ViiteTierekisteriClient
        case ioe@(_: IOException | _: ClientProtocolException) => {
          projectDAO.updateProjectStatus(projectId, SendingToTR)
          PublishResult(validationSuccess = false, sendSuccess = false, Some(trUnreachableMessage))
        }
        case NonFatal(_) => {
          projectDAO.updateProjectStatus(projectId, ErrorInViite)
          PublishResult(validationSuccess = false, sendSuccess = false, Some(genericViiteErrorMessage))
        }
      }
    }
  }

  private def setProjectDeltaToDB(projectDelta: Delta, projectId: Long): Boolean = {
    RoadwayChangesDAO.clearRoadChangeTable(projectId)
    RoadwayChangesDAO.insertDeltaToRoadChangeTable(projectDelta, projectId)
  }

  private def newProjectTemplate(rl: RoadLinkLike, ra: RoadAddress, project: RoadAddressProject): ProjectLink = {
    val geometry = GeometryUtils.truncateGeometry3D(rl.geometry, ra.startMValue, ra.endMValue)
    ProjectLink(NewProjectLink, ra.roadNumber, ra.roadPartNumber, ra.track, ra.discontinuity, ra.startAddrMValue,
      ra.endAddrMValue, ra.startAddrMValue, ra.endAddrMValue, ra.startDate, ra.endDate, Some(project.modifiedBy), ra.linkId, ra.startMValue, ra.endMValue,
      ra.sideCode, ra.toProjectLinkCalibrationPoints(), ra.floating, geometry,
      project.id, LinkStatus.NotHandled, ra.roadType, ra.linkGeomSource, GeometryUtils.geometryLength(geometry),
      ra.id, ra.linearLocationId, ra.ely, ra.reversed, None, ra.adjustedTimestamp, roadAddressLength = Some(ra.endAddrMValue - ra.startAddrMValue))
  }

  private def newProjectLink(rl: RoadLinkLike, project: RoadAddressProject, roadNumber: Long,
                             roadPartNumber: Long, trackCode: Track, discontinuity: Discontinuity, roadType: RoadType,
                             ely: Long, roadName: String = ""): ProjectLink = {
    ProjectLink(NewRoadway, roadNumber, roadPartNumber, trackCode, discontinuity,
      0L, 0L, 0L, 0L, Some(project.startDate), None, Some(project.modifiedBy), rl.linkId, 0.0, rl.length,
      SideCode.Unknown, (None, None), floating = NoFloating, rl.geometry,
      project.id, LinkStatus.New, roadType, rl.linkSource, rl.length,
      0L, 0L, ely, reversed = false, None, rl.vvhTimeStamp, roadName = Some(roadName))
  }

  private def newProjectLink(rl: RoadLinkLike, project: RoadAddressProject, splitOptions: SplitOptions): ProjectLink = {
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

  private def fuseProjectLinks(links: Seq[ProjectLink]): Seq[ProjectLink] = {
    val linkIds = links.map(_.linkId).distinct
    val existingRoadAddresses = roadAddressService.getRoadAddressesByRoadwayIds(links.map(_.roadwayId))
    val groupedRoadAddresses = existingRoadAddresses.groupBy(record =>
      (record.roadwayNumber, record.roadNumber, record.roadPartNumber, record.track.value, record.startDate, record.endDate, record.linkId, record.roadType, record.ely, record.terminated))

    if (groupedRoadAddresses.size > 1) {
      return links
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

  def updateProjectStatusIfNeeded(currentStatus: ProjectState, newStatus: ProjectState, errorMessage: String, projectId: Long): (ProjectState) = {
    if (currentStatus.value != newStatus.value && newStatus != ProjectState.Unknown) {
      logger.info(s"Status update is needed as Project Current status (${currentStatus}) differs from TR Status(${newStatus})")
      val projects = projectDAO.getProjects(projectId)
      if (projects.nonEmpty && newStatus == ProjectState.ErrorInTR) {
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
      projectDAO.getProjectsWithWaitingTRStatus
    }
  }

  def updateProjectsWaitingResponseFromTR(): Unit = {
    val listOfPendingProjects = getProjectsPendingInTR
    for (project <- listOfPendingProjects) {
      try {
        if (withDynTransaction {
          logger.info(s"Checking status for $project")
          checkAndUpdateProjectStatus(project)
        }) {
          eventbus.publish("roadAddress:RoadNetworkChecker", RoadCheckOptions(Seq()))
        } else {
          logger.info(s"Not going to check road network (status != Saved2TR)")
        }
      } catch {
        case t: SQLException => logger.error(s"SQL error while importing project: $project! Check if any roads have multiple valid names with out end dates ", t.getStackTrace)
        case t: Exception => logger.warn(s"Couldn't update project $project", t.getMessage)
      }
    }

  }

  def getProjectState(projectId: Long): Option[ProjectState] = {
    withDynTransaction {
      projectDAO.getProjectStatus(projectId)
    }
  }

  private def checkAndUpdateProjectStatus(projectID: Long): Boolean = {
    projectDAO.getRotatingTRProjectId(projectID).headOption match {
      case Some(trId) =>
        projectDAO.getProjectStatus(projectID).map { currentState =>
          logger.info(s"Current status is $currentState, fetching TR state")
          val trProjectState = ViiteTierekisteriClient.getProjectStatusObject(trId)
          logger.info(s"Retrived TR status: ${trProjectState.getOrElse(None)}")
          val newState = getStatusFromTRObject(trProjectState).getOrElse(ProjectState.Unknown)
          val errorMessage = getTRErrorMessage(trProjectState)
          logger.info(s"TR returned project status for $projectID: $currentState -> $newState, errMsg: $errorMessage")
          val updatedStatus = updateProjectStatusIfNeeded(currentState, newState, errorMessage, projectID)
          if (updatedStatus == Saved2TR) {
            logger.info(s"Starting project $projectID roadaddresses importing to roadaddresstable")
            updateRoadAddressWithProjectLinks(updatedStatus, projectID)
          }
        }
        roadAddressService.getFloatingAdresses().isEmpty
      case None =>
        logger.info(s"During status checking VIITE wasnt able to find TR_ID to project $projectID")
        appendStatusInfo(projectDAO.getRoadAddressProjectById(projectID).head, " Failed to find TR-ID ")
        false
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

  def createSplitRoadAddress(roadAddress: RoadAddress, split: Seq[ProjectLink], project: RoadAddressProject): Seq[RoadAddress] = {
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
            pl.startMValue, pl.endMValue, pl.sideCode, pl.linkGeometryTimeStamp, pl.toCalibrationPoints(), floating = NoFloating,
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

  // TODO
  def updateTerminationForHistory(terminatedLinkIds: Set[Long], splitReplacements: Seq[ProjectLink]): Unit = {
    throw new NotImplementedError("Will be implemented at VIITE-1540")
//    withDynSession {
//      roadAddressService.setSubsequentTermination(terminatedLinkIds)
//      val mapping = RoadAddressSplitMapper.createAddressMap(splitReplacements)
//      val splitTerminationLinkIds = mapping.map(_.sourceLinkId).toSet
//      val splitCurrentRoadwayIds = splitReplacements.map(_.roadwayId).toSet
//      val linkGeomSources = splitReplacements.map(pl => pl.linkId -> pl.linkGeomSource).toMap
//      val addresses = roadAddressService.getRoadAddressesByLinkIds(splitTerminationLinkIds, includeFloating = true, includeHistory = true,
//        includeTerminated = false, includeCurrent = false, splitCurrentRoadwayIds) // Do not include current ones as they're created separately with other project links
//
//      // TODO roadways instead of road addresses
//      val splitAddresses = addresses.flatMap(RoadAddressSplitMapper.mapRoadAddresses(mapping, addresses)).map(ra =>
//        ra.copy(terminated = if (splitTerminationLinkIds.contains(ra.linkId)) Subsequent else NoTermination,
//          linkGeomSource = linkGeomSources(ra.linkId)))
//
//      val roadwayIds = addresses.map(_.id)
//      val roadwaysToBeExpired = roadwayDAO.fetchAllByRoadwayId(roadwayIds)
//      roadwayDAO.expireById(roadwayIds.toSet)
//
//      // TODO create Roadways with new terminated and linkGeomSource -values
//      roadwayDAO.create(roadwaysToBeExpired.map(_.copy(id = NewRoadway, ...)))
//    }
  }

  private def getRoadNamesFromProjectLinks(projectLinks: Seq[ProjectLink]): Seq[RoadName] = {
    projectLinks.groupBy(pl => (pl.roadNumber, pl.roadName, pl.startDate, pl.endDate, pl.createdBy)).keys.map(rn =>
      if (rn._2.nonEmpty) {
        RoadName(NewRoadNameId, rn._1, rn._2.get, rn._3, rn._4, rn._3, createdBy = rn._5.getOrElse(""))
      } else {
        throw new RuntimeException(s"Road name is not defined for road ${rn._1}")
      }
    ).toSeq
  }

  /**
    * This will expire roadways (valid_to = null and end_date = project_date)
    *
    * @param projectLinks          ProjectLinks
    * @param expiringRoadAddresses A map of (RoadwayId -> RoadAddress)
    */
  def createHistoryRows(projectLinks: Seq[ProjectLink], expiringRoadAddresses: Map[Long, RoadAddress]) = {
    val groupedProjectLinks = projectLinks.filter(pl => operationsLeavingHistory.contains(pl.status)).groupBy(_.roadwayId)
    val roadwaysToExpire = expiringRoadAddresses.values.flatMap(ex => {
      groupedProjectLinks.get(ex.id) match {
        case Some(pls) =>
          pls.headOption.map(pl => pl.roadwayId)
        case _ => None
      }
    })
    if (!roadwaysToExpire.isEmpty) {
      roadwayDAO.updateEndDateById(roadwaysToExpire.toSet, projectLinks.head.startDate.get)
    }
  }

  /**
    * Will check the road addresses for any whose startDate is the same as the startDates on the projectLinks, if any are found, then their ID's are returned to be expired
    *
    * @param projectLinks
    * @return
    */
  def roadAddressHistoryCorrections(projectLinks: Seq[ProjectLink]): Map[Long, RoadAddress] = {
    throw new NotImplementedError("This method probably will not be needed")
    //    val roadAddresses = RoadAddressDAO.queryById(projectLinks.map(_.roadwayId).toSet, rejectInvalids = false)
    //    val startDates = projectLinks.filter(_.startDate.isDefined).map(_.startDate.get)
    //    roadAddresses.filter(ra => {
    //      ra.startDate.isDefined && startDates.exists(startDate => {
    //        startDate.getDayOfMonth() == ra.startDate.get.getDayOfMonth && startDate.getMonthOfYear() == ra.startDate.get.getMonthOfYear() && startDate.getYear() == ra.startDate.get.getYear()
    //      })
    //    }).map(ra => ra.id -> ra).toMap
  }

  def updateRoadAddressWithProjectLinks(newState: ProjectState, projectID: Long): Option[String] = {
        if (newState != Saved2TR) {
          logger.error(s" Project state not at Saved2TR")
          throw new RuntimeException(s"Project state not at Saved2TR: $newState")
        }
        val project = projectDAO.getRoadAddressProjectById(projectID).get
        val projectLinks = projectLinkDAO.getProjectLinks(projectID)
        if (projectLinks.isEmpty){
          logger.error(s" There are no road addresses to update, rollbacking update ${project.id}")
          throw new InvalidAddressDataException(s"There are no road addresses to update , rollbacking update ${project.id}")
        }
        val (replacements, additions) = projectLinks.partition(_.linearLocationId > 0)
        logger.info(s"Found ${projectLinks.length} project links from projectId: $projectID")
        val expiringRoadAddressesFromReplacements = roadwayAddressMapper.getRoadAddressesByLinearLocation(linearLocationDAO.queryById(replacements.map(_.linearLocationId).toSet, rejectInvalids = false)).map(ra => ra.linearLocationId -> ra).toMap
        //val expiringRoadAddresses = roadAddressHistoryCorrections(projectLinks) ++ expiringRoadAddressesFromReplacements
        //logger.info(s"Found ${expiringRoadAddresses.size} to expire; expected ${replacements.map(_.linearLocationId).toSet.size}")
        projectLinkDAO.moveProjectLinksToHistory(projectID)
        logger.info(s"Moving project links to project link history.")
        handleNewRoadNames(projectLinks, project)
        try {
          val (splitReplacements, pureReplacements) = replacements.partition(_.connectedLinkId.nonEmpty)
          val newRoadAddresses = convertToRoadAddress(splitReplacements, pureReplacements, additions,
            expiringRoadAddressesFromReplacements, project)

          val newRoadwaysWithHistory = RoadwayFiller.fillRoadway(projectLinks, newRoadAddresses, expiringRoadAddressesFromReplacements.values.toSeq)

          logger.info(s"Creating history rows based on operation")
          createHistoryRows(projectLinks, expiringRoadAddressesFromReplacements)
          //Expiring all old addresses by their ID
          /*logger.info(s"Expiring all old addresses by their ID included in ${project.id}")
          roadAddressService.expireRoadAddresses(expiringRoadAddresses.keys.toSet)
          val terminatedLinkIds = pureReplacements.filter(pl => pl.status == Terminated).map(_.linkId).toSet
          logger.info(s"Updating the following terminated linkids to history ${terminatedLinkIds} ")
          updateTerminationForHistory(terminatedLinkIds, splitReplacements)
          //Create endDate rows for old data that is "valid" (row should be ignored after end_date)
          val created = RoadAddressDAO.create(newRoadAddressesWithHistory.map(_.copy(id = NewRoadAddress)))*/
          Some(s"road addresses created")
        } catch {
          case e: ProjectValidationException =>
            logger.error("Failed to validate project message:" + e.getMessage)
            Some(e.getMessage)
        }
  }

  def handleNewRoadNames(projectLinks: Seq[ProjectLink], project: RoadAddressProject): Unit = {
    val projectLinkNames = ProjectLinkNameDAO.get(projectLinks.map(_.roadNumber).toSet, project.id).filterNot(p => {
      p.roadNumber >= maxRoadNumberDemandingRoadName && p.roadName == null
    })
    val existingInRoadNames = projectLinkNames.flatMap(n => RoadNameDAO.getCurrentRoadNamesByRoadNumber(n.roadNumber)).map(_.roadNumber)
    val (existingLinkNames, newLinkNames) = projectLinkNames.partition(pln => existingInRoadNames.contains(pln.roadNumber))
    val newNames = newLinkNames.map {
      ln => RoadName(NewRoadNameId, ln.roadNumber, ln.roadName, startDate = Some(project.startDate), validFrom = Some(DateTime.now()), createdBy = project.createdBy)
    }
    RoadNameDAO.create(newNames)
    projectLinkNames.foreach(en => ProjectLinkNameDAO.removeProjectLinkName(en.roadNumber, project.id))
    if (newNames.nonEmpty) {
      logger.info(s"Found ${newNames.size} names in project that differ from road address name")
    }
    if (existingLinkNames.nonEmpty) {
      val nameString = s"${existingLinkNames.map(_.roadNumber).mkString(",")}"
      appendStatusInfo(project, roadNameWasNotSavedInProject + nameString)
    }
  }

  def convertToRoadAddress(splitReplacements: Seq[ProjectLink], pureReplacements: Seq[ProjectLink], additions: Seq[ProjectLink],
                           roadAddresses: Map[Long, RoadAddress], project: RoadAddressProject): Seq[RoadAddress] = {
    splitReplacements.groupBy(_.linearLocationId).flatMap { case (id, seq) =>
      createSplitRoadAddress(roadAddresses(id), seq, project)
    }.toSeq ++
      pureReplacements.map(pl => convertProjectLinkToRoadAddress(pl, project, roadAddresses.get(pl.linearLocationId))) ++
      additions.map(pl => convertProjectLinkToRoadAddress(pl, project, roadAddresses.get(pl.linearLocationId))) ++
      pureReplacements.flatMap(pl =>
        setEndDate(roadAddresses(pl.linearLocationId), pl, None, project))
  }

  private def convertProjectLinkToRoadAddress(pl: ProjectLink, project: RoadAddressProject,
                                              source: Option[RoadAddress]): RoadAddress = {
        val geom = if (pl.geometry.nonEmpty) {
          val linkGeom = GeometryUtils.geometryEndpoints(GeometryUtils.truncateGeometry2D(pl.geometry, 0, pl.endMValue - pl.startMValue))
          if (pl.sideCode == SideCode.TowardsDigitizing)
            Seq(linkGeom._1, linkGeom._2)
          else
            Seq(linkGeom._2, linkGeom._1)
        } else {
          Seq()
        }

        val floatingReason = if (source.isDefined && source.get.validTo.isDefined && source.get.validTo.get.isBeforeNow) FloatingReason.ProjectToRoadAddress else NoFloating
        val roadAddress = RoadAddress(source.map(_.id).getOrElse(NewProjectLink), source.map(_.linearLocationId).getOrElse(NewLinearLocation), pl.roadNumber, pl.roadPartNumber, pl.roadType, pl.track, pl.discontinuity,
          pl.startAddrMValue, pl.endAddrMValue, None, None, pl.createdBy, pl.linkId, pl.startMValue, pl.endMValue, pl.sideCode,
          pl.linkGeometryTimeStamp, pl.toCalibrationPoints(), floating = NoFloating, geom, pl.linkGeomSource, pl.ely, terminated = NoTermination, source.map(_.roadwayNumber).getOrElse(0), source.flatMap(_.validFrom), source.flatMap(_.validTo))
        pl.status match {
          case UnChanged =>
            if (source.get.roadType == roadAddress.roadType && source.get.discontinuity == roadAddress.discontinuity && source.get.ely == roadAddress.ely) {
              roadAddress.copy(startDate = source.get.startDate, endDate = source.get.endDate, floating = floatingReason)
            } else {
              roadAddress.copy(startDate = Some(project.startDate), floating = floatingReason)
            }
          case Transfer | Numbering =>
            roadAddress.copy(startDate = Some(project.startDate), floating = floatingReason)
          case New =>
            roadAddress.copy(startDate = Some(project.startDate))
          case Terminated =>
            roadAddress.copy(startDate = source.get.startDate, endDate = Some(project.startDate), terminated = Termination)
          case _ =>
            logger.error(s"Invalid status for imported project link: ${pl.status} in project ${pl.projectId}")
            throw new InvalidAddressDataException(s"Invalid status for split project link: ${pl.status}")
        }
  }

  /**
    * Called for road addresses that are replaced by a new version at end date
    *
    * @param roadAddress
    * @param pl
    * @param vvhLink
    * @return
    */
  private def setEndDate(roadAddress: RoadAddress, pl: ProjectLink, vvhLink: Option[VVHRoadlink], project: RoadAddressProject): Option[RoadAddress] = {
    pl.status match {
      // terminated is created from the project link in convertProjectLinkToRoadAddress
      case Terminated =>
        None
      // unchanged will get end_date if the road_type, discontinuity or ely code changes, otherwise we keep the same end_date
      case UnChanged =>
        if (roadAddress.roadType == pl.roadType && roadAddress.ely == pl.ely && roadAddress.discontinuity == pl.discontinuity) {
          None
        } else {
          Some(roadAddress.copy(endDate = Some(project.startDate)))
        }
      case Transfer | Numbering =>
        Some(roadAddress.copy(endDate = pl.startDate))
      case _ =>
        logger.error(s"Invalid status for imported project link: ${pl.status} in project ${pl.projectId}")
        throw new InvalidAddressDataException(s"Invalid status for split project link: ${pl.status}")
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

  def correctNullProjectEly(): Unit = {
    withDynSession {
      //Get all the projects with non-existent ely code
      val nullElyProjects = projectDAO.getProjects(withNullElyFilter = true)
      nullElyProjects.foreach(project => {
        //Get all the reserved road parts of said projects
        val reservedRoadParts = projectReservedPartDAO.fetchReservedRoadParts(project.id).filterNot(_.ely == 0)
        //Find the lowest m-Address Value of the reserved road parts
        val reservedRoadAddresses = roadAddressService.getRoadAddressWithRoadAndPart(reservedRoadParts.head.roadNumber, reservedRoadParts.head.roadPartNumber).minBy(_.endAddrMValue)
        //Use this part ELY code and set it on the project
        projectDAO.updateProjectEly(project.id, reservedRoadAddresses.ely)
      })
    }
  }

  def getProjectEly(projectId: Long): Option[Long] = {
    projectDAO.getProjectEly(projectId)
  }

  def validateProjectById(projectId: Long, newSession: Boolean = true): Seq[projectValidator.ValidationErrorDetails] = {
    if(newSession) {
      withDynSession {
        projectValidator.validateProject(projectDAO.getRoadAddressProjectById(projectId).get, projectLinkDAO.getProjectLinks(projectId))
      }
    }
    else{
      projectValidator.validateProject(projectDAO.getRoadAddressProjectById(projectId).get, projectLinkDAO.getProjectLinks(projectId))
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
      logger.info(s"Trying to publish project: ${projectId}")
      publishProject(projectId)
    }

  }

  private def getProjectsWaitingForTR(): Seq[Long] = {
    withDynSession {
      projectDAO.getProjectsWithSendingToTRStatus
    }
  }

  case class PublishResult(validationSuccess: Boolean, sendSuccess: Boolean, errorMessage: Option[String])

}

class SplittingException(s: String) extends RuntimeException {
  override def getMessage: String = s
}

case class ProjectBoundingBoxResult(projectLinkResultF: Future[Seq[ProjectLink]], roadLinkF: Future[Seq[RoadLink]],
                                    complementaryF: Future[Seq[RoadLink]], suravageF: Future[Seq[VVHRoadlink]])


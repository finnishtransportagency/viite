package fi.liikennevirasto.viite

import fi.liikennevirasto.digiroad2.DigiroadEventBus
import fi.liikennevirasto.digiroad2.service.RoadLinkService
import fi.liikennevirasto.digiroad2.util.{RoadAddressException, RoadPartReservedException}
import fi.liikennevirasto.digiroad2.util.LogUtils.time
import fi.liikennevirasto.viite.ProjectAddressLinkBuilder.municipalityToViiteELYMapping
import fi.liikennevirasto.viite.dao._
import ProjectCalibrationPointDAO.UserDefinedCalibrationPoint
import fi.liikennevirasto.viite.dao.ProjectState._
import fi.liikennevirasto.viite.model.{ProjectAddressLink, RoadAddressLink}
import fi.liikennevirasto.viite.process._
import fi.vaylavirasto.viite.dao.{LinkDAO, ProjectLinkNameDAO, RoadName, RoadNameDAO, Sequences}
import fi.vaylavirasto.viite.geometry.{BoundingRectangle, GeometryUtils, Point}
import fi.vaylavirasto.viite.model.CalibrationPointType.{JunctionPointCP, NoCP, UserDefinedCP}
import fi.vaylavirasto.viite.model.{AddrMRange, AdministrativeClass, CalibrationPointType, Discontinuity, LinkGeomSource, RoadAddressChangeType, RoadLink, RoadLinkLike, RoadPart, SideCode, Track, TrafficDirection}
import fi.vaylavirasto.viite.postgis.PostGISDatabaseScalikeJDBC
import fi.vaylavirasto.viite.util.DateTimeFormatters.ISOdateFormatter
import fi.vaylavirasto.viite.util.ViiteException
import org.joda.time.DateTime
import org.slf4j.LoggerFactory

import java.sql.SQLException
import scala.collection.mutable.ListBuffer
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.Duration
import scala.util.control.NonFatal
import scala.util.Try

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
  val values: Set[RoadNameSource] = Set(UnknownSource, ProjectLinkSource, RoadAddressSource)

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

case class PreFillInfo(RoadNumber    : BigInt,
                       PartNumber    : BigInt,
                       roadName      : String,
                       roadNameSource: RoadNameSource,
                       ely           : Long
                      )

case class LinkToRevert(id: Long, linkId: String, status: Long, geometry: Seq[Point])

class ProjectService(
                      roadAddressService         : RoadAddressService,
                      roadLinkService            : RoadLinkService,
                      nodesAndJunctionsService   : NodesAndJunctionsService,
                      roadwayDAO                 : RoadwayDAO,
                      roadwayPointDAO            : RoadwayPointDAO,
                      linearLocationDAO          : LinearLocationDAO,
                      projectDAO                 : ProjectDAO,
                      projectLinkDAO             : ProjectLinkDAO,
                      nodeDAO                    : NodeDAO,
                      nodePointDAO               : NodePointDAO,
                      junctionPointDAO           : JunctionPointDAO,
                      projectReservedPartDAO     : ProjectReservedPartDAO,
                      roadwayChangesDAO          : RoadwayChangesDAO,
                      roadwayAddressMapper       : RoadwayAddressMapper,
                      eventbus                   : DigiroadEventBus,
                      frozenTimeAPIServiceEnabled: Boolean = false
                    ) {

  def runWithTransaction[T](f: => T): T = PostGISDatabaseScalikeJDBC.runWithTransaction(f)

  def runWithReadOnlySession[T](f: => T): T = PostGISDatabaseScalikeJDBC.runWithReadOnlySession(f)

  private val logger = LoggerFactory.getLogger(getClass)
  val projectValidator = new ProjectValidator
  val roadNetworkValidator = new RoadNetworkValidator
  val roadAddressLinkBuilder = new RoadAddressLinkBuilder(roadwayDAO, linearLocationDAO)
  /**
    *
    * @param roadNumber    Road's number (long)
    * @param roadStartPart Starting part (long)
    * @param roadEndPart   Ending part (long)
    * @return Optional error message, None if no error
    */
  def checkRoadPartsExist(roadNumber: Long, roadStartPart: Long, roadEndPart: Long): Option[String] = {
    if (roadwayDAO.fetchAllByRoadPart(RoadPart(roadNumber, roadStartPart)).isEmpty) {
      Some(ErrorStartingRoadPartNotFound)
    } else if (roadwayDAO.fetchAllByRoadPart(RoadPart(roadNumber, roadEndPart)).isEmpty) {
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
    */
  def fetchProjectById(projectId: Long, withNullElyFilter: Boolean = false): Option[Project] = {
    projectDAO.fetchById(projectId, withNullElyFilter).map { project =>

      val projectReservedRoadParts = projectReservedPartDAO.fetchProjectReservedRoadPartsByProjectId(projectId)
      val projectLinks = projectLinkDAO.fetchProjectLinks(projectId).filterNot(pl => List(RoadAddressChangeType.NotHandled, RoadAddressChangeType.Termination).contains(pl.status)).groupBy(pl => (pl.roadPart))

      val reservedAndFormedParts: Seq[ProjectReservedPart] = projectReservedRoadParts.flatMap { rp =>
        val sortedAddresses: Seq[RoadAddress] = roadAddressService.getRoadAddressWithRoadPart(rp.roadPart, newTransaction = false).sortBy(_.addrMRange.start)
        val roadPartLinks = projectLinks.filter(pl => pl._1 == (rp.roadPart))

        //reservedParts
        val reserved: Seq[ProjectReservedPart] = if (sortedAddresses.nonEmpty) {
          val maxEly = sortedAddresses.map(_.ely).max
          val firstLink = sortedAddresses.head.linkId
          val maxDiscontinuity = sortedAddresses.last.discontinuity
          val maxEndAddr = sortedAddresses.last.addrMRange.end
          Seq(rp.copy(addressLength = Some(maxEndAddr), discontinuity = Some(maxDiscontinuity), ely = Some(maxEly), startingLinkId = Some(firstLink)))
        } else Seq()

        //formedParts
        val formed: Seq[ProjectReservedPart] = if (roadPartLinks.nonEmpty) {
          val sortedProjectLinks = roadPartLinks.head._2.sortBy(_.addrMRange.start)
          val maxEly = sortedProjectLinks.map(_.ely).max
          val firstLink = sortedProjectLinks.head.linkId
          val maxDiscontinuity = sortedProjectLinks.last.discontinuity
          val maxEndAddr = sortedProjectLinks.last.addrMRange.end
          Seq(rp.copy(newLength = Some(maxEndAddr), newDiscontinuity = Some(maxDiscontinuity), newEly = Some(maxEly), startingLinkId = Some(firstLink)))
        } else Seq()

        reserved ++ formed
      }
      val (foundReservedParts, foundFormedParts) = reservedAndFormedParts.partition(_.addressLength.nonEmpty)
      project.copy(reservedParts = foundReservedParts, formedParts = foundFormedParts)
    }
  }

  /**
    * Creates a new project. Adds the road addresses from the reserved road parts to the project link table.
    *
    * @param roadAddressProject Project to be created
    * @return the created project
    * @throws RoadPartReservedException if any of the road parts has already been reserved
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
    val newRoadAddressProject = 0L
    if (roadAddressProject.id == newRoadAddressProject) return None
    runWithReadOnlySession {
      fetchProjectById(roadAddressProject.id)
    }
  }

  def fetchPreFillData(linkId: String, projectId: Long): Either[String, PreFillInfo] = {
    runWithReadOnlySession {
      val preFillFromProject =
        parsePreFillData(projectLinkDAO.getProjectLinksByLinkId(linkId).filter(_.projectId == projectId), projectId = projectId)
      if (preFillFromProject.isRight)
        preFillFromProject
      else parsePreFillData(linkId: String, projectId: Long)
    }
  }

  def parsePreFillData(projectLinks: Seq[ProjectLink], projectId: Long = -1000): Either[String, PreFillInfo] = {
      if (projectLinks.isEmpty) {
        Left(s"Link could not be found from project: $projectId")
      }
      else {
        preFillRoadName(Some(projectLinks.head.roadPart.roadNumber), Some(projectLinks.head.roadPart.partNumber), projectLinks.head.ely, projectId)
      }
  }

  def parsePreFillData(linkId: String, projectId: Long): Either[String, PreFillInfo] = {
    roadLinkService.getSuravageLinksById(Set(linkId)) match {
      case List((roadNumber, roadPartNumber, municipalitycode)) =>
        preFillRoadName(
          roadNumber,
          roadPartNumber,
          Try(municipalityToViiteELYMapping(municipalitycode)).getOrElse(-1),
          projectId
        )
      case _ => Left(s"Link could not be found from project: $projectId")
    }
  }

  private def preFillRoadName(roadNumber    : Option[Long],
                              roadPartNumber: Option[Long],
                              ely           : Long,
                              projectId     : Long
                             ): Either[String, PreFillInfo] = {
    (roadNumber, roadPartNumber) match {
      case (Some(roadNumber: Long), Some(roadPartNumber: Long)) => val preFilledRoadName = RoadNameDAO.getLatestRoadName(roadNumber) match {
        case Some(roadName) => PreFillInfo(roadNumber, roadPartNumber, roadName.roadName, RoadNameSource.RoadAddressSource, ely)
        case _ => ProjectLinkNameDAO.get(roadNumber, projectId) match {
          case Some(projectLinkName) => PreFillInfo(roadNumber, roadPartNumber, projectLinkName.roadName, RoadNameSource.ProjectLinkSource, ely)
          case _ => PreFillInfo(roadNumber, roadPartNumber, "", RoadNameSource.UnknownSource, ely)
        }
      }
        Right(preFilledRoadName)
      case _ => Left("Link does not contain valid prefill info")
    }
  }

  /**
    * Return either the road parts <i>startPart...endPart</i> of the road <i>roadNumber</i>,
    * or an error message, if any of the road parts is reserved to another project than <i>projectId</i>.
    *  If road part is not reserved ProjectReservedPart
    *  If road part is already reserved return error message
    *  If road part is already reserved for the projectId return ProjectReservedPart
    * */
  def checkRoadPartsReservable(roadNumber: Long, startPart: Long, endPart: Long, projectId: Long): Either[String, (Seq[ProjectReservedPart], Seq[ProjectReservedPart])] = {
    (startPart to endPart).foreach { part =>
      val roadPart = RoadPart(roadNumber,part)
      projectReservedPartDAO.fetchProjectReservedPart(roadPart) match {
        case Some((id,name)) => if (id != projectId) return Left(s"Tieosa $roadPart ei ole vapaana projektin alkupäivämääränä. Tieosoite on jo varattuna projektissa: $name.")
        case _ =>
      }
      val projectsWithCommonJunctions = projectReservedPartDAO.fetchProjectReservedJunctions(roadPart, projectId)
      projectsWithCommonJunctions.headOption.map { _ =>
        return Left(s"Tieosa $roadPart ei ole varattavissa, koska tämän tieosan liittymää/liittymiä käsitellään ${if(projectsWithCommonJunctions.size > 1) "projekteissa " else "projektissa "} ${projectsWithCommonJunctions.mkString(", ")}")
      }
    }
    val reserved: Seq[ProjectReservedPart] = (startPart to endPart).flatMap(part => getReservedAddressPartInfo(RoadPart(roadNumber,part)))
    val formed:   Seq[ProjectReservedPart] = (startPart to endPart).flatMap(part => getFormedAddressPartInfo  (RoadPart(roadNumber,part)))
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
    runWithTransaction {
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

  def getRoadLinkDate: String = {
    runWithReadOnlySession {
      val timeInMillis = LinkDAO.fetchMaxAdjustedTimestamp()
      """{ "result":" """ + new DateTime(timeInMillis).toString("dd.MM.yyyy HH:mm:ss") + """"}"""
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
          None
        else
          Some("Projektin tila ei ole keskeneräinen") //project state is not incomplete
      case None => Some("Projektia ei löytynyt") //project could not be found
    }
  }

  def projectWritableCheck(projectId: Long): Option[String] = {
    runWithReadOnlySession {
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
    reservedParts
      .map(rp => rp.roadPart -> roadwayDAO.getRoadPartInfo(rp.roadPart))
      .toMap
      .filterNot(_._2.isEmpty)
      .collect { case (roadPart, Some(detail)) =>
        // Check start date
        detail.startDate.foreach { startDate =>
          // TODO remove this when other systems (esp. Velho) are able to handle corrective projects
          if (startDate.isEqual(date)) {
            return Some(
              s"Tieosalla $roadPart alkupäivämäärä ${startDate.toString("dd.MM.yyyy")} " +
                s"on sama kuin tieosoiteprojektin alkupäivämäärä ${date.toString("dd.MM.yyyy")}. " +
                s"Tieosoite projektin korjaus -ominaisuus on tilapäisesti poissa käytöstä. Ota yhteys Viite tukeen."
            )
          }

          if (startDate.isAfter(date)) {
            return Some(
              s"Tieosalla $roadPart alkupäivämäärä ${startDate.toString("dd.MM.yyyy")} " +
                s"on myöhempi kuin tieosoiteprojektin alkupäivämäärä ${date.toString("dd.MM.yyyy")}, " +
                s"tarkista tiedot."
            )
          }
        }

        // Check end date
        detail.endDate.foreach { endDate =>
          if (endDate.isAfter(date)) {
            return Some(
              s"Tieosalla $roadPart loppupäivämäärä ${endDate.toString("dd.MM.yyyy")} " +
                s"on myöhempi kuin tieosoiteprojektin alkupäivämäärä ${date.toString("dd.MM.yyyy")}, " +
                s"tarkista tiedot."
            )
          }
        }
      }

    None
  }

  private def getReservedAddressPartInfo(roadPart: RoadPart): Option[ProjectReservedPart] = {
    projectReservedPartDAO.fetchReservedRoadPart(roadPart).orElse(generateAddressPartInfo(roadPart))
  }

  private def getFormedAddressPartInfo(roadPart: RoadPart): Option[ProjectReservedPart] = {
    projectReservedPartDAO.fetchFormedRoadPart(roadPart).orElse(None)
  }


  private def generateAddressPartInfo(roadPart: RoadPart): Option[ProjectReservedPart] = {
    roadwayDAO.getRoadPartInfo(roadPart).map { detail =>
      ProjectReservedPart(
        id = 0L,
        roadPart = roadPart,
        addressLength = Some(detail.endAddrM),
        discontinuity = Some(Discontinuity.apply(detail.discontinuity.toInt)),
        ely = Some(detail.ely),
        newLength = Some(detail.endAddrM),
        newDiscontinuity = Some(Discontinuity.apply(detail.discontinuity.toInt)),
        newEly = Some(detail.ely),
        startingLinkId = Some(detail.linkId)
      )
    }
  }

  private def sortRamps(seq: Seq[ProjectLink], linkIds: Seq[String]): Seq[ProjectLink] = {
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

  def createProjectLinks(linkIds: Seq[String], projectId: Long, roadPart: RoadPart, track: Track, userGivenDiscontinuity: Discontinuity, administrativeClass: AdministrativeClass, roadLinkSource: LinkGeomSource, roadEly: Long, user: String, roadName: String, coordinates: Option[ProjectCoordinates] = None, devToolData: Option[ProjectLinkDevToolData] = None): Map[String, Any] = {

    def createProjectElyCodes(): Unit = {
      val elysForProject = projectLinkDAO.fetchProjectLinkElys(projectId) :+ roadEly
      val updatedCount = projectDAO.updateProjectElys(projectId, elysForProject.toSet.toSeq)
      if (updatedCount == 0) {
        logger.warn(s"Ely-codes for project: $projectId were not updated.")
      }
    }

    runWithTransaction {
      writableWithValidTrack(projectId, track.value) match {
        case None =>
          val linkId = linkIds.head
          val roadLinks = roadLinkService.getRoadLinksByLinkIds(linkIds.toSet).map(l => l.linkId -> l).toMap
          if (roadLinks.keySet != linkIds.toSet)
            return Map("success" -> false,
              "errorMessage" -> (linkIds.toSet -- roadLinks.keySet).mkString(ErrorRoadLinkNotFound + " puuttuvat id:t ", ", ", ""))
          val project = fetchProjectById(projectId).getOrElse(throw new RuntimeException(s"Missing project $projectId"))
          val projectLinks: Seq[ProjectLink] = linkIds.distinct.map { id: String =>
            newProjectLink(roadLinks(id), project, roadPart, track, Discontinuity.Continuous, administrativeClass, roadEly, roadName)
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
            createProjectElyCodes()
            addNewLinksToProject(sortRamps(projectLinks, linkIds), projectId, user, linkId, newTransaction = false, userGivenDiscontinuity, devToolData) match {
              case Some(errorMessage) =>
                Map("success" -> false, "errorMessage" -> errorMessage)
              case None =>
                val projectLinks = projectLinkDAO.fetchProjectLinksByProjectRoadPart(roadPart, projectId)
                val errorAddingDiscontinuity = if (!projectLinks.exists(_.discontinuity == userGivenDiscontinuity)) Some(UndeterminedLastNewLinkDiscontinuityNotApplied) else None
                Map("success" -> true, "projectErrors" -> validateProjectByIdHighPriorityOnly(projectId, newSession = false), "errorMessage" -> errorAddingDiscontinuity)
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
          junctionId = junctionPointDAO.fetchByMultipleRoadwayPoints(pl.roadwayNumber, pl.addrMRange.start, pl.addrMRange.end).map(_.junctionId)
        }
      })
    })
    isConnectedLinks && junctionId.isEmpty
  }

  def addNewLinksToProject(newLinks: Seq[ProjectLink], projectId: Long, user: String, firstLinkId: String, newTransaction: Boolean = true, discontinuity: Discontinuity, devToolData: Option[ProjectLinkDevToolData] = None): Option[String] = {
    if (newTransaction)
      runWithTransaction {
        addNewLinksToProjectInTX(newLinks, projectId, user, firstLinkId, discontinuity, devToolData)
      }
    else
      addNewLinksToProjectInTX(newLinks, projectId, user, firstLinkId, discontinuity, devToolData)
  }

  /**
    * Used when adding road address that do not have a previous address
    */
  private def addNewLinksToProjectInTX(newLinks: Seq[ProjectLink], projectId: Long, user: String, firstLinkId: String, discontinuity: Discontinuity, devToolData: Option[ProjectLinkDevToolData] = None): Option[String] = {
    val newRoadPart = newLinks.head.roadPart
    val roadAddressChangeType = newLinks.head.status
    try {
      val project = getProjectWithReservationChecks(projectId, newRoadPart, roadAddressChangeType, newLinks)

      if (GeometryUtils.isNonLinear(newLinks))
        throw new ProjectValidationException(ErrorGeometryContainsBranches)

      if (!project.isReserved(newRoadPart) && !project.isFormed(newRoadPart))
        projectReservedPartDAO.reserveRoadPart(project.id, newRoadPart, project.modifiedBy)
      // Determine address value scheme (ramp, roundabout, all others)

      val createLinks =
        if (newLinks.headOption.exists(isRamp)) {
          logger.info("Added links recognized to be in ramp category")
          if (TrackSectionOrder.isRoundabout(newLinks)) {
            logger.info("Added links recognized to be a roundabout - using roundabout addressing scheme")
            val ordered = newLinks.partition(_.linkId == firstLinkId)
            val created = TrackSectionOrder.mValueRoundabout(ordered._1 ++ ordered._2)
            val endingM = created.map(_.addrMRange.end).max
            created.map(pl =>
              if (pl.addrMRange.endsAt(endingM) && endingM > 0)
                pl.copy(discontinuity = Discontinuity.EndOfRoad)
              else
                pl.copy(discontinuity = Discontinuity.Continuous))
          } else {
            val existingLinks = projectLinkDAO.fetchByProjectRoadPart(newRoadPart, projectId)
            val pls = fillRampGrowthDirection(newLinks.map(_.linkId).toSet, newRoadPart, newLinks, firstLinkId, existingLinks)
            pls.init :+ pls.last.copy(discontinuity = discontinuity)
          }
        } else {
          /* Set discontinuity to the last new link if not continuous.
           * Finds the link by assuming the end is not connected, i.e. before round about. */
          if (discontinuity != Discontinuity.Continuous) {
            val existingLinks         = projectLinkDAO.fetchByProjectRoadPart(newRoadPart, projectId).filter(_.track == newLinks.head.track).map(pl => {
              (pl.geometry.head, pl.geometry.last)
            })
            val prevRoadPartGeom      = if (existingLinks.isEmpty && newRoadPart.partNumber > 1) {
              val rw = roadwayDAO.fetchAllByRoadPart(RoadPart(newRoadPart.roadNumber, newRoadPart.partNumber - 1), withHistory=false, fetchOnlyEnd=true).filter(_.track == newLinks.head.track) //TODO "-1" is incorrect..? The previous part might be more than one number away
              linearLocationDAO.fetchByRoadways(rw.map(_.roadwayNumber).toSet).sortBy(_.orderNumber).map(l => {
                (l.getFirstPoint, l.getLastPoint)
              })
            } else Seq()
            val existingLinksGeoms    = if (existingLinks.nonEmpty) existingLinks else prevRoadPartGeom
            val singlyConnectedNewLinks = TrackSectionOrder.findSinglyConnectedLinks(newLinks)
            val endLinkOfNewLinks     = singlyConnectedNewLinks.filterNot(onceConnected => {
              existingLinksGeoms.exists(el => {
                onceConnected._1.connected(el._1) || onceConnected._1.connected(el._2)
              })
            }).values.toList
            endLinkOfNewLinks.distinct.size match {
              case 0 =>
                Seq(newLinks.head.copy(discontinuity = discontinuity)) // TODO: Add test case for this.
              case 1 =>
                if (TrackSectionOrder.hasTripleConnectionPoint(newLinks)) {
                  val lastProjectLink = newLinks.filterNot(_.linkId == firstLinkId).last
                  newLinks.filterNot(_.linkId == lastProjectLink.linkId) :+ lastProjectLink.copy(discontinuity = discontinuity)
                }
                else
                  newLinks.filterNot(_.equals(endLinkOfNewLinks.head)) :+ endLinkOfNewLinks.head.copy(discontinuity = discontinuity)
              case 2 =>
                val endLink = endLinkOfNewLinks.filterNot(_.linkId == firstLinkId).head
                newLinks.filterNot(_.equals(endLink)) :+ endLink.copy(discontinuity = discontinuity)
              case _ =>  // Case when new link selection has discontinuities in geometry
                val firstLinkGeometry = newLinks.find(_.linkId == firstLinkId).get.geometry
                val otherEndPoints    = singlyConnectedNewLinks.filterNot(_._2.linkId == firstLinkId).keys
                def minDistanceToOtherEndpoints(p: Point) = otherEndPoints.map(p => (p,p.distance2DTo(p))).minBy(_._2)
                val closestEndPointToFirstLink = Seq(minDistanceToOtherEndpoints(firstLinkGeometry.head), minDistanceToOtherEndpoints(firstLinkGeometry.last)).minBy(_._2)._1
                // Startpoint as one of first link end points further a way from closest(next link) link end point
                val startPoint                = Seq((firstLinkGeometry.head, firstLinkGeometry.head.distance2DTo(closestEndPointToFirstLink)), (firstLinkGeometry.last, firstLinkGeometry.last.distance2DTo(closestEndPointToFirstLink))).maxBy(_._2)._1
                val lastLinkInSection         = Seq(TrackSectionOrder.orderProjectLinksTopologyByGeometry((startPoint, startPoint), newLinks)).flatMap(pl => pl._1 ++ pl._2).distinct.last
                // Set discontinuity to the last link
                newLinks.filterNot(_.linkId == lastLinkInSection.linkId) :+ lastLinkInSection.copy(discontinuity = discontinuity)
            }
          } else newLinks
        }

      if (devToolData.isDefined) {
        val editedData = devToolData.get
        val addressesProcessed = if (editedData.startAddrMValue.isDefined && editedData.endAddrMValue.isDefined && editedData.endAddrMValue.get != 0) {
          spreadAddrMValuesToProjectLinks(editedData.startAddrMValue.get, editedData.endAddrMValue.get, createLinks, editOriginalValues = false)
        } else
          createLinks

        val roadwayNumbersUpdated = {
          val newRoadwayNumberForNewLinks = Sequences.nextRoadwayNumber
          addressesProcessed.map(pl => pl.copy(roadwayNumber = newRoadwayNumberForNewLinks))
        }

        val statusAndSideCodeUpdated = roadwayNumbersUpdated.map(pl => pl.copy(status = roadAddressChangeType,sideCode = {if (pl.status == RoadAddressChangeType.New && pl.sideCode == SideCode.Unknown) SideCode.TowardsDigitizing else pl.sideCode}))

        val calibrationPointsUpdated = setCalibrationPoints(editedData.startCp, editedData.endCp, statusAndSideCodeUpdated)
        val updatedRoadways = {
          if (editedData.generateNewRoadwayNumber) {
            val newRoadwayNymber = Sequences.nextRoadwayNumber
            calibrationPointsUpdated.map(pl => pl.copy(roadwayNumber = newRoadwayNymber))
          } else {
            calibrationPointsUpdated
          }
        }
        projectLinkDAO.create(updatedRoadways.map(_.copy(createdBy = Some(user))))
      }
       else {
        projectLinkDAO.create(createLinks.map(_.copy(createdBy = Some(user))))
      }
      newLinks.flatMap(_.roadName).headOption.flatMap(setProjectRoadName(projectId, newRoadPart.roadNumber, _)).toList.headOption
    } catch {
      case ex: ProjectValidationException => Some(ex.getMessage)
    }
  }

  /**
    * Will attempt to find relevant sideCode information to the projectLinks given a number of factors
    * for example if they are of suravage or complementary origin
    *
    * @param linkIds   the linkIds to process
    * @param roadPart  the roadPart to apply/was applied to said linkIds
    * @param newLinks  new project links for this ramp
    * @return the projectLinks with a assigned SideCode
    */
  private def fillRampGrowthDirection(linkIds: Set[String], roadPart: RoadPart, newLinks: Seq[ProjectLink], firstLinkId: String, existingLinks: Seq[ProjectLink]) = {
    if (newLinks.exists(nl => existingLinks.exists(pl => pl.status != RoadAddressChangeType.Termination &&
                                                         GeometryUtils.areAdjacent(pl.geometry, nl.geometry)))) {
      // Connected to existing geometry -> let the track section calculation take it's natural course
      newLinks.map(_.copy(sideCode = SideCode.Unknown))
    } else {
      val roadLinks = roadLinkService.getRoadLinks(linkIds) //fetchVVHRoadLinksAndComplementaryFromVVH(linkIds)
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
        runWithReadOnlySession {
          projectLinkDAO.fetchFirstLink(project.id, rrp.roadPart)
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
    * @param roadPart       : RoadPart - Road part of the roadway
    * @param links          : Sequence of project links - Project links targeted to reverse
    * @param coordinates    : ProjectCoordinates - Coordinates for the project to jump to.
    * @param username       : Sting - User
    * @return
    */
  def changeDirection(projectId: Long, roadPart: RoadPart, links: Seq[LinkToRevert], coordinates: ProjectCoordinates, username: String): Option[String] = {
    roadAddressLinkBuilder.municipalityToViiteELYMapping // make sure it is populated outside of this TX
    try {
      runWithTransaction {
        projectWritableCheckInSession(projectId) match {
          case None =>
            if (projectLinkDAO.countLinksByStatus(projectId, roadPart, Set(RoadAddressChangeType.Unchanged.value, RoadAddressChangeType.NotHandled.value)) > 0)
              return Some(ErrorReversingUnchangedLinks)
            val continuity = projectLinkDAO.getProjectLinksContinuityCodes(projectId, roadPart)
            val newContinuity: Map[Long, Discontinuity] = if (continuity.nonEmpty) {
              val discontinuityAtEnd = continuity.maxBy(_._1)
              continuity.filterKeys(_ < discontinuityAtEnd._1).map { case (addr, d) => (discontinuityAtEnd._1 - addr) -> d } ++
              Map(discontinuityAtEnd._1 -> discontinuityAtEnd._2)
            } else
              Map()
            projectLinkDAO.reverseRoadPartDirection(projectId, roadPart)
            val projectLinks = projectLinkDAO.fetchProjectLinks(projectId).filter(pl => {
              pl.status != RoadAddressChangeType.Termination && pl.roadPart == roadPart
            })
            val originalSideCodes = linearLocationDAO.fetchByRoadways(projectLinks.map(_.roadwayNumber).toSet)
                                                     .map(l => l.id -> l.sideCode).toMap
            val originalAddresses = roadAddressService.getRoadAddressesByRoadwayIds(projectLinks.map(_.roadwayId))
            projectLinkDAO.updateProjectLinks(projectLinks.map(x =>
              x.copy(discontinuity = newContinuity.getOrElse(x.addrMRange.end, Discontinuity.Continuous), reversed = isReversed(originalSideCodes)(x))), username, originalAddresses)
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
    * Checks whether the road part the user wants to reserve exists
    *
    * @param reservedRoadParts
    * @param projectLinks
    * @param roadways
    * @return None in case of success, error code in case of failed validation
    */
  def validateReservations(reservedRoadParts: ProjectReservedPart, projectLinks: Seq[ProjectLink], roadways: Seq[Roadway]): Option[String] = {
    if (roadways.isEmpty && projectLinks.forall(_.status == RoadAddressChangeType.New) && !projectLinks.exists(pl => pl.roadPart == reservedRoadParts.roadPart))
      Some(s"$ErrorFollowingRoadPartsNotFoundInDB TIEOSA: ${reservedRoadParts.roadPart}")
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
    val projectLinkOriginalParts = if (projectLinks.nonEmpty) roadwayDAO.fetchAllByRoadwayId(projectLinks.map(_.roadwayId)).map(_.roadPart) else Seq()
    val newProjectLinks = project.reservedParts.filterNot(res =>
      projectLinkOriginalParts.contains(res.roadPart)).flatMap {
      reserved => {
        val roadways = roadwayDAO.fetchAllBySection(reserved.roadPart)
        validateReservations(reserved, projectLinks, roadways) match {
          case Some(error) => throw new RoadPartReservedException(error)
          case _ =>
            val roadwaysByLinkSource = linearLocationDAO.fetchByRoadways(roadways.map(_.roadwayNumber).toSet).groupBy(_.linkGeomSource)
            val regularLinkSource = if (frozenTimeAPIServiceEnabled) LinkGeomSource.FrozenLinkInterface else LinkGeomSource.NormalLinkInterface
            val regular = if (roadwaysByLinkSource.contains(regularLinkSource)) roadwaysByLinkSource(regularLinkSource) else Seq()
            val complementary = if (roadwaysByLinkSource.contains(LinkGeomSource.ComplementaryLinkInterface)) roadwaysByLinkSource(LinkGeomSource.ComplementaryLinkInterface) else Seq()
            if (complementary.nonEmpty) {
              logger.debug(s"Adding ${complementary.size} complementary links in project.")
            }
            val regularMapping = roadLinkService.getRoadLinksByLinkIds(regular.map(_.linkId).toSet).map(rm => rm.linkId -> rm).toMap
            val complementaryMapping = roadLinkService.getRoadLinksByLinkIds(complementary.map(_.linkId).toSet).map(rm => rm.linkId -> rm).toMap
            val fullMapping = regularMapping ++ complementaryMapping
            val addresses = roadways.flatMap(r =>
              roadwayAddressMapper.mapRoadAddresses(r, (regular ++ complementary).groupBy(_.roadwayNumber).getOrElse(r.roadwayNumber, {
                logger.error(s"Failed to add links to the project. No links found with roadway number ${r.roadwayNumber}. Reserved parts were: ${project.reservedParts.map(r => s"(${r.roadPart})").mkString(", ")}")
                throw new RoadAddressException(AddNewLinksFailed)
              })))
            checkAndReserve(project, reserved)
            logger.debug(s"Reserve done")
            addresses.map(ra => newProjectTemplate(fullMapping(ra.linkId), ra, project))
        }
      }
    }

    projectLinkDAO.create(newProjectLinks.filterNot(ad => projectLinks.exists(pl =>pl.roadAddressRoadPart.getOrElse(pl.roadPart) == ad.roadPart)))
    logger.debug(s"New links created ${newProjectLinks.size}")
    val linksOnRemovedParts = projectLinks.filterNot(pl => (project.reservedParts ++ project.formedParts).exists(_.holds(pl)))
    roadwayChangesDAO.clearRoadChangeTable(project.id)
    projectLinkDAO.removeProjectLinksById(linksOnRemovedParts.map(_.id).toSet)
    None
  }

  /**
    * Fetches the projectLink name, first from the project link, and if that's not available then searches for the road address.
    *
    * @param projectLink
    * @return
    */
  def fillRoadNames(projectLink: ProjectLink): ProjectLink = {
    val projectLinkName = ProjectLinkNameDAO.get(projectLink.roadPart.roadNumber, projectLink.projectId).map(_.roadName)
                                            .getOrElse(RoadNameDAO.getLatestRoadName(projectLink.roadPart.roadNumber).map(_.roadName).getOrElse(projectLink.roadName.get))
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
    runWithTransaction {
      logger.info(s"Checking if project ${roadAddressProject.id} is in writable state")
      logger.info(s"Current project state: ${roadAddressProject.statusInfo}")

      val writableState = isWritableState(roadAddressProject.id)
      logger.info(s"isWritableState result for project ${roadAddressProject.id}: $writableState")

      if (writableState) {
        validateProjectDate(roadAddressProject.reservedParts, roadAddressProject.startDate) match {
          case Some(errMsg) =>
            logger.error(s"Project date validation failed: $errMsg")
            throw new IllegalStateException(errMsg)
          case None =>
            logger.info(s"Checking uniqueness of project name '${roadAddressProject.name}'")
            if (projectDAO.isUniqueName(roadAddressProject.id, roadAddressProject.name)) {
              logger.info(s"Project name is unique, proceeding with update")
              projectDAO.update(roadAddressProject)
              val storedProject = fetchProjectById(roadAddressProject.id).get

              logger.info(s"Calculating removed reservations and formed parts")
              val removedReservation = storedProject.reservedParts.filterNot(part =>
                roadAddressProject.reservedParts.exists(rp => rp.roadPart == part.roadPart))

              val removedFormed = storedProject.formedParts.filterNot(part =>
                roadAddressProject.formedParts.exists(rp => rp.roadPart == part.roadPart))

              logger.info(s"Found ${removedReservation.size} removed reservations and ${removedFormed.size} removed formed parts")

              (removedReservation ++ removedFormed).foreach(p => {
                logger.info(s"Removing reserved road part ${p.roadPart}")
                projectReservedPartDAO.removeReservedRoadPartAndChanges(roadAddressProject.id, p.roadPart)
              })

              (removedReservation ++ removedFormed).groupBy(_.roadPart.roadNumber).keys.foreach(roadNumber => {
                logger.info(s"Reverting project link names for road number $roadNumber")
                ProjectLinkNameDAO.revert(roadNumber, roadAddressProject.id)
              })

              logger.info("Adding links to project")
              addLinksToProject(roadAddressProject)

              val savedProject = fetchProjectById(roadAddressProject.id).get
              logger.info("Saving project coordinates")
              saveProjectCoordinates(savedProject.id, calculateProjectCoordinates(savedProject.id))

              logger.info(s"Successfully saved project ${savedProject.id}")
              savedProject
            } else {
              logger.error(s"Project name '${roadAddressProject.name}' already exists")
              throw new NameExistsException(s"Nimellä ${roadAddressProject.name} on jo olemassa projekti. Muuta nimeä.")
            }
        }
      } else {
        logger.error(s"Project ${roadAddressProject.id} is not in writable state")
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
    runWithTransaction {
      val project = fetchProjectById(projectId)
      val canBeDeleted = projectId != 0 && project.isDefined && project.get.projectState == ProjectState.Incomplete
      if (canBeDeleted) {
        val links = projectLinkDAO.fetchProjectLinks(projectId)
        projectLinkDAO.removeProjectLinksByProject(projectId)
        projectReservedPartDAO.removeReservedRoadPartsByProject(projectId)
        links.groupBy(_.roadPart.roadNumber).keys.foreach(ProjectLinkNameDAO.revert(_, projectId))
        projectDAO.updateProjectStatus(projectId, ProjectState.Deleted)
        projectDAO.updateProjectStateInfo(ProjectState.Deleted.description, projectId)
      }
      canBeDeleted
    }
  }

  def createRoadLinkProject(roadAddressProject: Project): Project = {
    if (roadAddressProject.id != 0)
      throw new IllegalArgumentException(s"Road address project to create has an id ${roadAddressProject.id}")
    runWithTransaction {
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
    runWithReadOnlySession {
      fetchProjectById(projectId)
    }
  }

  def getAllProjects: List[Map[String, Any]] = {
    runWithReadOnlySession {
      projectDAO.fetchAllWithoutDeletedFilter()
    }
  }

  def getActiveProjects: List[Map[String, Any]] = {
    runWithReadOnlySession {
      projectDAO.fetchAllActiveProjects()
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
    val currentReservedPart = (projectReservedPartDAO.fetchProjectReservedPart(reservedRoadPart.roadPart), projectReservedPartDAO.roadPartReservedTo(reservedRoadPart.roadPart, projectId = project.id, withProjectId = true))
    logger.info(s"Check ${project.id} matching to " + currentReservedPart)
    currentReservedPart match {
      case (None, None) => projectReservedPartDAO.reserveRoadPart(project.id, reservedRoadPart.roadPart, project.modifiedBy)
      case _ =>
    }
  }

  def getProjectLinks(projectId: Long): Seq[ProjectLink] = {
    runWithReadOnlySession {
      projectLinkDAO.fetchProjectLinks(projectId)
    }
  }

  //Temporary method that will be replaced for getProjectLinksWithSuravage method
  def getProjectLinksWithoutSuravage(projectId: Long, boundingRectangle: BoundingRectangle,
                                     roadNumberLimits: Seq[(Int, Int)], municipalities: Set[Int], everything: Boolean = false,
                                     publicRoads: Boolean = false): Seq[ProjectAddressLink] = {
    // TODO: Check roadNumberLimit need for KGV.
    val fetch = fetchBoundingBoxF(boundingRectangle, projectId, roadNumberLimits, municipalities, everything, publicRoads)
    fetchProjectRoadLinks(projectId, boundingRectangle, roadNumberLimits, municipalities, everything, publicRoads, fetch)
  }

  def getProjectLinksLinear(projectId: Long, boundingRectangle: BoundingRectangle,
                            roadNumberLimits: Seq[(Int, Int)], municipalities: Set[Int], everything: Boolean = false): Seq[ProjectAddressLink] = {
    val projectLinks = fetchProjectRoadLinksLinearGeometry(projectId, boundingRectangle, roadNumberLimits, municipalities, everything)
    projectLinks
  }

  def getChangeProject(projectId: Long): (Option[ChangeProject], Option[String]) = {
    runWithTransaction {
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
  }

  /** Returns a [[ChangeProject]]
    * @throws IllegalArgumentException if <i>changeData</i> has
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
    ChangeProject(nullRotatingChangeProjectId, changeData.projectName.getOrElse(""), changeData.user,
      ISOdateFormatter.print(changeData.projectStartDate), Seq(changeInfo))
  }


  def getProjectAddressLinksByLinkIds(linkIdsToGet: Set[String]): Seq[ProjectAddressLink] = {
    if (linkIdsToGet.isEmpty)
      Seq()
    runWithReadOnlySession {
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
        case (start, stop) => roadAddressLink.roadPart.roadNumber >= start && roadAddressLink.roadPart.roadNumber <= stop
      }
    }

    val projectState = getProjectState(projectId)
    val fetchRoadAddressesByBoundingBoxF = Future {
      runWithTransaction {
        val addresses = roadAddressService.getRoadAddressesByBoundingBox(boundingRectangle,
          roadNumberLimits = roadNumberLimits)
        addresses.groupBy(_.linkId)
      }
    }
    val fetchProjectLinksF = fetch.projectLinkResultF
    val (regularLinks, complementaryLinks) = time(logger, "Fetch KVG road links") {
      awaitRoadLinks(fetch.roadLinkF, fetch.complementaryF)
    }

    val (addresses, currentProjectLinks) = time(logger, "Fetch unaddressed road links") {
      Await.result(fetchRoadAddressesByBoundingBoxF.zip(fetchProjectLinksF), Duration.Inf)
    }
    val projectLinks = if (projectState.isDefined && finalProjectStates.contains(projectState.get.value)) {
      fetchProjectHistoryLinks(projectId)
    }
    else currentProjectLinks

    val normalLinks = regularLinks.filterNot(l => projectLinks.exists(_.linkId == l.linkId))

    val projectRoadLinks = time(logger, "Build road addresses") {
       runWithReadOnlySession {
        projectLinks.groupBy(l => (l.linkId, l.administrativeClass)).flatMap { pl => buildProjectRoadLink(pl._2)
        }
      }
    }
    val nonProjectRoadLinks = (normalLinks ++ complementaryLinks).filterNot(rl => projectRoadLinks.exists(_.linkId == rl.linkId)) //    val buildEndTime = System.currentTimeMillis()

    val filledTopology = RoadAddressFiller.fillTopology(nonProjectRoadLinks, addresses.values.flatten.toSeq)

    val complementaryLinkIds = complementaryLinks.map(_.linkId).toSet
    val returningTopology = filledTopology.filter(link => !complementaryLinkIds.contains(link.linkId) ||
                                                          complementaryLinkFilter(roadNumberLimits, municipalities, everything, publicRoads)(link))
    returningTopology.map(ProjectAddressLinkBuilder.build) ++ projectRoadLinks
  }


  def fetchProjectHistoryLinks(projectId: Long): Seq[ProjectLink] = {
    runWithReadOnlySession {
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
    val fetchRoadAddressesByBoundingBoxF = Future {
      runWithReadOnlySession {
        val addresses = roadAddressService.getRoadAddressesByBoundingBox(boundingRectangle,
          roadNumberLimits = roadNumberLimits)
        addresses.groupBy(_.linkId)
      }
    }

    val fetchProjectLinksF = Future {
      runWithReadOnlySession {
        val projectState = projectDAO.fetchProjectStatus(projectId)
        if (projectState.isDefined && finalProjectStates.contains(projectState.get.value))
          projectLinkDAO.fetchProjectLinksHistory(projectId).groupBy(_.linkId)
        else
          projectLinkDAO.fetchProjectLinks(projectId).groupBy(_.linkId)
      }
    }
    val (addresses, projectLinks) = time(logger, "Fetch road addresses by bounding box") {
      Await.result(fetchRoadAddressesByBoundingBoxF.zip(fetchProjectLinksF), Duration.Inf)
    }

    val buildStartTime = System.currentTimeMillis()
    val projectRoadLinks = runWithReadOnlySession {
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
      Future(runWithReadOnlySession(projectLinkDAO.fetchProjectLinks(projectId))),
      Future(roadLinkService.getRoadLinks(boundingRectangle, roadNumberLimits, municipalities, everything, publicRoads)),
      if (everything)
        roadLinkService.getComplementaryRoadLinks(boundingRectangle, municipalities)
      else Future(Seq())
    )
  }

  /**
    * Fetches the project while testing for the following:
    * Project existence
    * Road Number and Road Part Number combination is reserved by the project
    * If the road part combination is available for use in this project date
    * If the road part combination is not reserved by another project.
    *
    * @param projectId            : Long - Project Id
    * @param newRoadPart          : RoadPart - Road part reserved for the project
    * @param roadAddressChangeType: RoadAddressChangeType - What kind of operation is subjected
    * @param projectLinks         : Seq[ProjectLink] - Project links
    * @return
    */
  private def getProjectWithReservationChecks(projectId: Long, newRoadPart: RoadPart, roadAddressChangeType: RoadAddressChangeType, projectLinks: Seq[ProjectLink]): Project = {
    val project = fetchProjectById(projectId).getOrElse(throw new ProjectValidationException(ProjectNotFoundMessage))
    projectValidator.checkReservedExistence(project, newRoadPart, roadAddressChangeType, projectLinks)
    projectValidator.checkAvailable(newRoadPart, project)
    projectValidator.checkReservedPartInProject(newRoadPart, project, roadAddressChangeType)
    projectValidator.checkReservedPartInOtherProject(newRoadPart, project)
    projectValidator.checkFormationInOtherProject(project, newRoadPart, roadAddressChangeType)
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
    if (links.groupBy(l => (l.projectId, l.roadPart)).keySet.size != 1)
      throw new IllegalArgumentException("Reverting links from multiple road parts at once is not allowed")
    val l = links.head
    revertLinksByRoadPart(l.projectId, l.roadPart, links.map(
      link => LinkToRevert(link.id, link.linkId, link.status.value, link.geometry)), userName)
  }

  def revertRoadName(projectId: Long, roadNumber: Long): Unit = {
    if (projectLinkDAO.fetchProjectLinks(projectId).exists(pl => pl.roadPart.roadNumber == roadNumber) && RoadNameDAO.getLatestRoadName(roadNumber).nonEmpty) {
      ProjectLinkNameDAO.revert(roadNumber, projectId)
      val roadAddressName = RoadNameDAO.getLatestRoadName(roadNumber)
      val projectRoadName = ProjectLinkNameDAO.get(roadNumber, projectId)
      if (roadAddressName.nonEmpty && projectRoadName.isEmpty) {
        ProjectLinkNameDAO.create(projectId, roadNumber, roadAddressName.get.roadName)
      }
    }
    if (!projectLinkDAO.fetchProjectLinks(projectId).exists(pl => pl.roadPart.roadNumber == roadNumber)) {
      ProjectLinkNameDAO.revert(roadNumber, projectId)
    }
  }

  /**
    * Last function on the chain, this is the one that will do all the work.
    * Firstly we isolate the unique link id's that were modified and we remove all the project links that have them them from the project.
    * We use the same link ids we found and fetch the road addresses by combining KGV roadlink information and our roadway+linear location information on the builder.
    * With the road address information we now check that a reservation is possible and reserve them in the project.
    * Afterwards we update the newly reserved project links with the original geometry we obtained previously
    * If we do still have road address information that do not match the original modified links then we check that a reservation is possible and reserve them in the project and we update those reserved links with the information on the road address.
    * With that done we revert any changes on the road names, when applicable.
    * After all this we come to the conclusion that we have no more road number and road parts for this project then we go ahead and release them.
    *
    * @param projectId      : Long - Project ID
    * @param roadPart       : RoadPart - Road part of the roadway
    * @param toRemove       : Iterable[LinksToRemove] - Project links that were created in this project
    * @param modified       : Iterable[LinksToRemove] - Project links that existed as road addresses
    * @param userName       : String - User name
    */
  private def revertSortedLinks(projectId: Long, roadPart: RoadPart, toRemove: Iterable[LinkToRevert],
                                modified: Iterable[LinkToRevert], userName: String): Unit = {
    val modifiedLinkIds = modified.map(_.linkId).toSet
    projectLinkDAO.removeProjectLinksByLinkId(projectId, toRemove.map(_.linkId).toSet)
    val kgvRoadLinks = roadLinkService.getCurrentAndComplementaryRoadLinks(modifiedLinkIds)
    val roadAddresses = roadwayAddressMapper.getRoadAddressesByLinearLocation(linearLocationDAO.fetchRoadwayByLinkId(modifiedLinkIds))
    roadAddresses.foreach(ra =>
      modified.find(modifiedLink => modifiedLink.linkId == ra.linkId) match {
        case Some(modifiedLink) =>
          checkAndReserve(fetchProjectById(projectId).get, toReservedRoadPart(ra.roadPart, ra.ely))
          if (modifiedLink.geometry.nonEmpty) {
            val kgvGeometry = kgvRoadLinks.find(roadLink => roadLink.linkId == modifiedLink.linkId && roadLink.linkSource == ra.linkGeomSource)
            if (kgvGeometry.nonEmpty) {
              val geom = GeometryUtils.truncateGeometry3D(kgvGeometry.get.geometry, ra.startMValue, ra.endMValue)
              projectLinkDAO.updateProjectLinkValues(projectId, ra.copy(geometry = geom))
            } else {
              projectLinkDAO.updateProjectLinkValues(projectId, ra, updateGeom = false)
            }
          } else {
            projectLinkDAO.updateProjectLinkValues(projectId, ra, updateGeom = false)
          }
        case _ =>
      })
    revertRoadName(projectId, roadPart.roadNumber)

    val afterUpdateLinks = projectLinkDAO.fetchByProjectRoadPart(roadPart, projectId)
    // handle possible split project links that were split in the project
    val preservedProjectLinks = identifyAndHandleSplitProjectLinks(afterUpdateLinks, projectId)

    if (preservedProjectLinks.isEmpty) {
      releaseRoadPart(projectId, roadPart, userName)
    }
  }

  def toReservedRoadPart(roadPart: RoadPart, ely: Long): ProjectReservedPart = {
    ProjectReservedPart(0L, roadPart, None, None, Some(ely), None, None, None, None)
  }


  /**
    * Splits the links to revert in two separate types, the modified (ones that came from road addresses) and the added (ones that were created in this project).
    * Also fetches the project links by road number, road part number and project id and supply them to the next step.
    *
    * @param projectId      : Long - Project Id
    * @param roadPart       : RoadPart - Road part of the roadway
    * @param links          : Iterable[ProjectLink] - Links to revert
    * @param userName       : String - User name
    * @return
    */
  def revertLinksByRoadPart(projectId: Long, roadPart: RoadPart, links: Iterable[LinkToRevert], userName: String): Option[String] = {
    val (added, modified) = links.partition(_.status == RoadAddressChangeType.New.value)
    revertSortedLinks(projectId, roadPart, added, modified, userName)
    None
  }

  /**
    * Starting point of the revert. Sets up the database transaction to save the modifications done to the links to revert.
    * After the modifications are saved this will save the new project coordinates.
    * Otherwise this will issue a error messages.
    *
    * @param projectId      : Long - The id of the project
    * @param roadPart       : RoadPart - Road part of the roadway
    * @param links          : Iterable[LinkToRevert] - The links to return to the original values and state
    * @param coordinates    : ProjectCoordinates - New coordinates on where to move the map on project open
    * @param userName       : String - The name of the user
    * @return
    */
  def revertLinks(projectId: Long, roadPart: RoadPart, links: Iterable[LinkToRevert], coordinates: ProjectCoordinates, userName: String): Option[String] = {
    try {
      runWithTransaction {
        val (added, modified) = links.partition(_.status == RoadAddressChangeType.New.value)
        projectWritableCheckInSession(projectId) match {
          case None =>
            if (modified.exists(_.status == RoadAddressChangeType.Renumeration.value)) {
              logger.info(s"Reverting whole road part in $projectId ($roadPart)")
              // Numbering change affects the whole road part
              revertSortedLinks(projectId, roadPart, added,
                projectLinkDAO.fetchByProjectRoadPart(roadPart, projectId).map(
                  link => LinkToRevert(link.id, link.linkId, link.status.value, link.geometry)),
                userName)
            } else {
              revertSortedLinks(projectId, roadPart, added, modified, userName)
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

  private def releaseRoadPart(projectId: Long, roadPart: RoadPart, userName: String) = {
    if (projectLinkDAO.fetchFirstLink(projectId, roadPart).isEmpty) {
      projectReservedPartDAO.removeReservedRoadPartAndChanges(projectId, roadPart)
    } else {
      val links = projectLinkDAO.fetchByProjectRoadPart(roadPart, projectId)
      revertFetchedLinks(links, userName)
    }
  }

  // Main function that spreads address M values over project links, either modifying original or current address values.
  def spreadAddrMValuesToProjectLinks(startAddrM: Long, endAddrM: Long, projectLinks: Seq[ProjectLink], editOriginalValues: Boolean): Seq[ProjectLink] = {

    // Nested function to map address values iteratively/recursively across each ProjectLink.
    def mappedAddressValues(remaining: Seq[ProjectLink], processed: Seq[ProjectLink], startAddr: Double, endAddr: Double, coef: Double, list: Seq[Long], increment: Int, depth: Int = 1): Seq[Long] = {

      // Base case: If no remaining ProjectLinks, return the final list of address values.
      if (remaining.isEmpty) {
        list
      } else {
        val currentProjectLink = remaining.head  // Get the next ProjectLink to process.

        // Calculate a preview address value based on the coefficient, adjusting increment for rounding if only one link left.
        val previewValue = if (remaining.size == 1) {
          startAddr + Math.round((currentProjectLink.endMValue - currentProjectLink.startMValue) * coef) + increment
        } else {
          startAddr + (currentProjectLink.endMValue - currentProjectLink.startMValue) * coef + increment
        }

        // Safety check to prevent infinite recursion; throws error if depth exceeds limit.
        val warningLimit = 100
        val limit = 105
        if (depth > warningLimit) {
          val message = s"mappedAddressValues got in infinite recursion. ProjectLink id = ${currentProjectLink.id}, startMValue = ${currentProjectLink.startMValue}, endMValue = ${currentProjectLink.endMValue}, previewValue = $previewValue, remaining = ${remaining.length}"
          logger.error(message)
          if (depth > limit) throw ViiteException(message)  // Throws exception if recursive depth is excessive.
        }

        // Adjust the list based on previewValue in relation to endAddrM and startAddr boundaries.
        val adjustedList: Seq[Long] = if ((previewValue < endAddrM) && (previewValue > startAddr)) {
          list :+ Math.round(previewValue)  // Add previewValue if within bounds.
        } else if (previewValue <= startAddr) {
          // Recursively retry with incremented value if below startAddr.
          mappedAddressValues(Seq(remaining.head), processed, list.last, endAddr, coef, list, increment + 1, depth + 1)
        } else if (previewValue <= endAddrM) {
          // Recursively retry with decremented value if between startAddr and endAddr.
          mappedAddressValues(Seq(remaining.head), processed, list.last, endAddr, coef, list, increment - 1, depth + 1)
        } else {
          // Recursively retry with modified processed and remaining sequences to avoid exceeding boundaries.
          mappedAddressValues(processed.last +: remaining, processed.init, list.init.last, endAddr, coef, list.init, increment - 1, depth + 1)
        }

        // Recursive call to process the next ProjectLink with updated parameters and increment depth.
        mappedAddressValues(remaining.tail, processed :+ remaining.head, previewValue, endAddr, coef, adjustedList, increment, depth + 1)
      }
    }

    // Calculate the scaling coefficient for address spreading based on total ProjectLink address range.
    val coefficient = (endAddrM - startAddrM) / projectLinks.map(pl => pl.endMValue - pl.startMValue).sum

    // Generate the list of addresses spread across ProjectLinks.
    val addresses = mappedAddressValues(projectLinks.init, Seq(), startAddrM, endAddrM, coefficient, Seq(startAddrM), 0) :+ endAddrM

    // Map each ProjectLink to a new range (either original or current address values) and return the modified sequence.
    projectLinks.zip(addresses.zip(addresses.tail)).map {
      case (projectLink, (st, en)) =>
        if (editOriginalValues)
          projectLink.copy(originalAddrMRange = AddrMRange(st, en)) // Set original address values.
        else
          projectLink.copy(addrMRange = AddrMRange(st, en)) // Set regular address values.
    }
  }


def setCalibrationPoints(startCp: Long, endCp: Long, projectLinks: Seq[ProjectLink]): Seq[ProjectLink] = {

  val startCalibrationPoint = CalibrationPointType.apply(startCp.toInt)
  val endCalibrationPoint = CalibrationPointType.apply(endCp.toInt)

  val linksWithStartCPSet = {
    if (startCalibrationPoint != NoCP) {
      val firstLinkInSegment = projectLinks.head
      val firstLink = firstLinkInSegment.copy(calibrationPointTypes = (startCalibrationPoint, projectLinks.head.calibrationPointTypes._2))
      if (!firstLink.hasCalibrationPointAt(firstLinkInSegment.addrMRange.start)) {
        ProjectCalibrationPointDAO.createCalibrationPoint(UserDefinedCalibrationPoint(NewIdValue, firstLinkInSegment.id, firstLinkInSegment.projectId, firstLinkInSegment.startMValue, firstLinkInSegment.addrMRange.start))
      }
      logger.info(s"Start calibrationPoint set for link ${firstLink.id} startCP: ${firstLink.startCalibrationPoint}")
      Seq(firstLink) ++ projectLinks.tail
    } else {
      projectLinks
    }
  }

  val linksWithEndCPSet = {
    if (endCalibrationPoint != NoCP) {
      val lastLinksInSegment = linksWithStartCPSet.last
      val lastLink = linksWithStartCPSet.last.copy(calibrationPointTypes = (linksWithStartCPSet.last.calibrationPointTypes._1, endCalibrationPoint))
      if (!lastLink.hasCalibrationPointAt(lastLinksInSegment.addrMRange.end)) {
        ProjectCalibrationPointDAO.createCalibrationPoint(UserDefinedCalibrationPoint(NewIdValue, lastLinksInSegment.id, lastLinksInSegment.projectId, lastLinksInSegment.endMValue, lastLinksInSegment.addrMRange.end))
      }
      logger.info(s"End calibrationPoint set for link ${lastLink.id} startCP: ${lastLink.endCalibrationPoint}")
      linksWithStartCPSet.init ++ Seq(lastLink)
    } else {
      linksWithStartCPSet
    }
  }
  linksWithEndCPSet
}

  /**
    * Updates project links to being of given roadAddressChangeType, and recalculates delta and change table.
    *
    * @param projectId  Project's id
    * @param linkIds    Set of link ids that are set to this status
    * @param roadAddressChangeType New roadAddressChangeType for given link ids
    * @param userName   Username of the user that does this change
    * @return true, if the delta calculation is successful and change table has been updated.
    */
  def updateProjectLinks(projectId             : Long,
                         ids                   : Set[Long],
                         linkIds               : Seq[String],
                         roadAddressChangeType : RoadAddressChangeType,
                         userName              : String,
                         newRoadPart           : RoadPart,
                         newTrackCode          : Int,
                         userDefinedEndAddressM: Option[Int],
                         administrativeClass   : Long                       = AdministrativeClass.State.value,
                         discontinuity         : Int                        = Discontinuity.Continuous.value,
                         ely                   : Option[Long]               = None,
                         reversed              : Boolean                    = false,
                         roadName              : Option[String]             = None,
                         coordinates           : Option[ProjectCoordinates] = None,
                         devToolData           : Option[ProjectLinkDevToolData] = None
                        ): Option[String] = {

    def isCompletelyNewPart(toUpdateLinks: Seq[ProjectLink]): (Boolean, RoadPart) = {
      val reservedPart = projectReservedPartDAO.fetchReservedRoadPart(toUpdateLinks.head.roadPart).get
      val newSavedLinks = if (roadwayDAO.fetchAllByRoadPart(reservedPart.roadPart).isEmpty) {
        projectLinkDAO.fetchByProjectRoadPart(reservedPart.roadPart, projectId)
      } else Seq.empty
      /*
      replaceable -> New link part should replace New existing part if:
        1. Action is RoadAddressChangeType.New, RoadAddressChangeType.Transfer or RoadAddressChangeType.Numbering
        2. New road or part is different from existing one
        3. All New links in existing part are in selected links for New part OR action is RoadAddressChangeType.Numbering
        (Numbering changes all links that are on the same part regardless of how many of the links are actually selected)
       */
      val replaceable =
        (roadAddressChangeType == RoadAddressChangeType.New || roadAddressChangeType == RoadAddressChangeType.Transfer || roadAddressChangeType == RoadAddressChangeType.Renumeration) &&
        (reservedPart.roadPart != newRoadPart) &&
          newSavedLinks.nonEmpty && (newSavedLinks.map(_.id).toSet.subsetOf(ids) || roadAddressChangeType == RoadAddressChangeType.Renumeration)

      (replaceable, reservedPart.roadPart)
    }

    def setDiscontinuityAndUpdateProjectLinks(links: Seq[ProjectLink]): Unit = {
      val originalAddresses = roadAddressService.getRoadAddressesByRoadwayIds(links.map(_.roadwayId))
      if (links.nonEmpty) {
        val lastSegment = links.maxBy(_.addrMRange.end)
        if (links.lengthCompare(1) > 0) {
          val linksToUpdate = links.filterNot(_.id == lastSegment.id)
          projectLinkDAO.updateProjectLinks(linksToUpdate, userName, originalAddresses)
        }
        projectLinkDAO.updateProjectLinks(Seq(lastSegment.copy(discontinuity = Discontinuity.apply(discontinuity))), userName, originalAddresses)
      }
    }

    def checkAndMakeReservation(projectId: Long, newRoadPart: RoadPart, roadAddressChangeType: RoadAddressChangeType, projectLinks: Seq[ProjectLink]): (Boolean, Option[RoadPart]) = {
      val project = getProjectWithReservationChecks(projectId, newRoadPart, roadAddressChangeType, projectLinks)
      try {
        val (toReplace, roadPart) = isCompletelyNewPart(projectLinks)
        if (toReplace && roadAddressChangeType == RoadAddressChangeType.New) {
          val reservedPart = projectReservedPartDAO.fetchReservedRoadPart(roadPart).get
          projectReservedPartDAO.removeReservedRoadPartAndChanges(projectId, reservedPart.roadPart)
          val newProjectLinks: Seq[ProjectLink] = projectLinks.map(pl => pl.copy(id = NewIdValue, roadPart = newRoadPart, track = Track.apply(newTrackCode), discontinuity = Discontinuity.Continuous, addrMRange = AddrMRange(pl.addrMRange.start, userDefinedEndAddressM.getOrElse(pl.addrMRange.end.toInt).toLong), administrativeClass = AdministrativeClass.apply(administrativeClass.toInt)))
          if (linkIds.nonEmpty) {
            addNewLinksToProject(sortRamps(newProjectLinks, linkIds), projectId, userName, linkIds.head, newTransaction = false, Discontinuity.apply(discontinuity))
          } else {
            val newSavedLinkIds = projectLinks.map(_.linkId)
            addNewLinksToProject(sortRamps(newProjectLinks, newSavedLinkIds), projectId, userName, newSavedLinkIds.head, newTransaction = false, Discontinuity.apply(discontinuity))
          }
        } else if (!project.isReserved(newRoadPart) && !project.isFormed(newRoadPart)) {
          projectReservedPartDAO.reserveRoadPart(project.id, newRoadPart, project.modifiedBy)
        }
        (toReplace, Some(roadPart))
      } catch {
        case e: Exception => println("Unexpected exception occurred: " + e)
          (false, None)
      }
    }
    /**
     * Resets project links to match original road addresses.
     * @param toReset A sequence of ProjectLinks to reset.
     */
    def resetAndUpdateTerminatedProjectLinks(toReset: Seq[ProjectLink]): Unit = {
      val roadAddresses = roadAddressService.getRoadAddressesByRoadwayIds(toReset.map(_.roadwayId))
      val updatedLinks = toReset.flatMap { projectLink =>
        roadAddresses.find(roadAddress => projectLink.linearLocationId == roadAddress.linearLocationId).map { ra =>
          val startCpType = ra.calibrationPoints._1.map(_.typeCode).getOrElse(CalibrationPointType.NoCP)
          val endCpType = ra.calibrationPoints._2.map(_.typeCode).getOrElse(CalibrationPointType.NoCP)
          projectLink.copy(
            roadPart = ra.roadPart,
            track = ra.track,
            administrativeClass = ra.administrativeClass,
            addrMRange = AddrMRange(ra.addrMRange.start, ra.addrMRange.end),
            originalAddrMRange = ra.addrMRange,
            calibrationPointTypes = (startCpType, endCpType),
            originalCalibrationPointTypes = (startCpType, startCpType),
            sideCode = ra.sideCode,
            ely = ra.ely,
            discontinuity = ra.discontinuity,
            startMValue = ra.startMValue,
            endMValue = ra.endMValue,
            linearLocationId = ra.linearLocationId,
            projectId = projectLink.projectId,
            linkId = projectLink.linkId
          )
        }
      }
      if (updatedLinks.nonEmpty) {
        projectLinkDAO.batchUpdateProjectLinksToTerminate(updatedLinks)

        // Fetch connected project links to check for splits
        val connectedLinkIds = updatedLinks.flatMap(_.connectedLinkId).distinct
        val connectedProjectLinks = projectLinkDAO.fetchProjectLinksByConnectedLinkId(connectedLinkIds)
        // check and remove links that were split from the project
        identifyAndHandleSplitProjectLinks(connectedProjectLinks, projectId)
      }
    }

    /* Update elycodes into project table */
    def updateProjectElyCodes(): Unit = {
      val elysForProject = projectLinkDAO.fetchProjectLinkElys(projectId)
      val updatedCount = projectDAO.updateProjectElys(projectId, elysForProject)
      if (updatedCount == 0)
        logger.warn(s"Ely-codes for project: $projectId were not updated.")
    }

    try {
      runWithTransaction {
        val projectLinks = projectLinkDAO.fetchProjectLinksByProjectAndLinkId(ids, linkIds.toSet, projectId)
        if (devToolData.isDefined) {
          val editedData = devToolData.get
          if (editedData.startAddrMValue.isDefined && editedData.endAddrMValue.isDefined) {
            val addressesUpdated = spreadAddrMValuesToProjectLinks(editedData.startAddrMValue.get, editedData.endAddrMValue.get, projectLinks, editOriginalValues = false)
            val origAddressesUpdated = if (projectLinks.forall(_.status != RoadAddressChangeType.New)) {
              spreadAddrMValuesToProjectLinks(editedData.originalStartAddrMValue.get, editedData.originalEndAddrMValue.get, addressesUpdated, editOriginalValues = true)
            } else {
              addressesUpdated
            }
            val roadwayNumbersUpdated = {
              if (origAddressesUpdated.forall(_.status == RoadAddressChangeType.New)) {
                val newRoadwayNumberForNewLinks = Sequences.nextRoadwayNumber
                origAddressesUpdated.map(pl => pl.copy(roadwayNumber = newRoadwayNumberForNewLinks))
              } else
                origAddressesUpdated
            }

            val statusAndSideCodeUpdated = roadwayNumbersUpdated.map(pl => pl.copy(status = roadAddressChangeType,sideCode = {if (pl.status == RoadAddressChangeType.New && pl.sideCode == SideCode.Unknown) SideCode.TowardsDigitizing else pl.sideCode}))
            val originalAddresses = roadAddressService.getRoadAddressesByRoadwayIds(statusAndSideCodeUpdated.map(_.roadwayId))
            val calibrationPointsUpdated = setCalibrationPoints(editedData.startCp, editedData.endCp, statusAndSideCodeUpdated)
            val updatedRoadways = {
              if (editedData.generateNewRoadwayNumber) {
                val newRoadwayNymber = Sequences.nextRoadwayNumber
                calibrationPointsUpdated.map(pl => pl.copy(roadwayNumber = newRoadwayNymber))
              } else {
                calibrationPointsUpdated
              }
            }
            val editedLinks = {
              if (devToolData.get.editedSideCode.nonEmpty) {
                updatedRoadways.map(pl => pl.copy(sideCode = SideCode.apply(devToolData.get.editedSideCode.get.toInt)))
              } else
                updatedRoadways
            }
            projectLinkDAO.updateProjectLinks(editedLinks, userName, originalAddresses)
          }
        }

        val toUpdateLinks = projectLinkDAO.fetchProjectLinksByProjectAndLinkId(ids, linkIds.toSet, projectId)
          userDefinedEndAddressM.foreach(addressM => {
            val endSegment                = toUpdateLinks.maxBy(_.addrMRange.end)
            val calibrationPoint          = UserDefinedCalibrationPoint(NewIdValue, endSegment.id, projectId, addressM.toDouble - endSegment.startMValue, addressM)
            val lastEndSegmentLink        = toUpdateLinks.find(ul => ul.projectId == projectId && ul.addrMRange.continuesTo(endSegment.addrMRange))
            val calibrationPointIsPresent = lastEndSegmentLink match {
              case Some(projectLink) =>
                projectLink.hasCalibrationPointAt(calibrationPoint.addressMValue)
              case _ => false
            }
            /* Store the user defined calibration point with given address even if other calibratation type exists. */
            if (!calibrationPointIsPresent || lastEndSegmentLink.last.endCalibrationPointType != UserDefinedCP) {
              val foundCalibrationPoint = ProjectCalibrationPointDAO.findEndCalibrationPoint(endSegment.id, projectId)
              if (foundCalibrationPoint.isEmpty)
                ProjectCalibrationPointDAO.createCalibrationPoint(calibrationPoint)
              else
                ProjectCalibrationPointDAO.updateSpecificCalibrationPointMeasures(foundCalibrationPoint.head.id, addressM.toDouble - endSegment.startMValue, addressM)
              Seq(ProjectCalibrationPoint)
            } else
              Seq.empty[ProjectCalibrationPoint]
          })
          roadAddressChangeType match {
            case RoadAddressChangeType.Termination =>
              if (devToolData.isDefined) {
                if (devToolData.get.startAddrMValue.isDefined || devToolData.get.endAddrMValue.isDefined || devToolData.get.originalStartAddrMValue.isDefined || devToolData.get.originalEndAddrMValue.isDefined)
                  logger.info(s"Skipping termination link reset, because of dev tool given address values...")
              } else {
                // Reset and update projectLinks to their original state based on road addresses
                resetAndUpdateTerminatedProjectLinks(toUpdateLinks)
                projectLinkDAO.updateProjectLinksStatus(toUpdateLinks.map(_.id).toSet, RoadAddressChangeType.Termination, userName)
              }
            case RoadAddressChangeType.Renumeration =>
              if (toUpdateLinks.nonEmpty) {
                val currentAddresses = roadAddressService.getRoadAddressesFiltered(newRoadPart)
                val roadAddresses = roadAddressService.getRoadAddressesByRoadwayIds(toUpdateLinks.map(_.roadwayId))
                if (roadAddresses.exists(x =>
                  x.roadPart == newRoadPart)) // check the original numbering wasn't exactly the same
                  throw new ProjectValidationException(ErrorRenumberingToOriginalNumber) // you cannot use current roadnumber and roadpart number in numbering operation
                if (currentAddresses.nonEmpty)
                  throw new ProjectValidationException(ErrorRoadAlreadyExistsOrInUse)
                if (toUpdateLinks.map(pl => (pl.roadPart)).distinct.lengthCompare(1) != 0 ||
                    roadAddresses.map(ra => (ra.roadPart)).distinct.lengthCompare(1) != 0) {
                  throw new ProjectValidationException(ErrorMultipleRoadNumbersOrParts)
                }
                val roadPartLinks = projectLinkDAO.fetchProjectLinksByProjectRoadPart(toUpdateLinks.head.roadPart, projectId)
                if (roadPartLinks.exists(rpl => rpl.status == RoadAddressChangeType.Unchanged || rpl.status == RoadAddressChangeType.Transfer || rpl.status == RoadAddressChangeType.New || rpl.status == RoadAddressChangeType.Termination)) {
                  throw new ProjectValidationException(ErrorOtherActionWithNumbering)
                }
                val (reservationNotNeeded, oldRoadPart) = checkAndMakeReservation(projectId, newRoadPart, RoadAddressChangeType.Renumeration, toUpdateLinks)

                projectLinkDAO.updateProjectLinkNumbering(projectId, toUpdateLinks.head.roadPart,
                  roadAddressChangeType, newRoadPart, userName, ely.getOrElse(toUpdateLinks.head.ely))
                projectLinkDAO.updateProjectLinkAdministrativeClassDiscontinuity(Set(toUpdateLinks.maxBy(_.addrMRange.end).id), roadAddressChangeType, userName, administrativeClass, Some(discontinuity))
                //numbering cases should remove the reserved part after the project link table update operation
                if (reservationNotNeeded) {
                  projectReservedPartDAO.removeReservedRoadPart(projectId, oldRoadPart.get)
                }
                val nameError = roadName.flatMap(setProjectRoadName(projectId, newRoadPart.roadNumber, _)).toList.headOption
                if (nameError.nonEmpty)
                  return nameError
              } else {
                throw new ProjectValidationException(ErrorRoadLinkNotFoundInProject)
              }

            case RoadAddressChangeType.Transfer =>
              val (reservationNotNeeded, oldRoadPart) = checkAndMakeReservation(projectId, newRoadPart, RoadAddressChangeType.Transfer, toUpdateLinks)

              // VIITE-3203 if dev tool is being used then skip this part.
              // Reason: Dev tool user might need the RoadAddressCP (that is being erased here, and programmatically recalculated later)
              val updated = if (devToolData.isEmpty) {
                toUpdateLinks.map(l => {
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
                  l.copy(roadPart = newRoadPart, track = Track.apply(newTrackCode), calibrationPointTypes = (startCP, endCP), status = roadAddressChangeType, administrativeClass = AdministrativeClass.apply(administrativeClass.toInt), ely = ely.getOrElse(l.ely))
                })
              } else
                toUpdateLinks

              val originalAddresses = roadAddressService.getRoadAddressesByRoadwayIds(updated.map(_.roadwayId))
              projectLinkDAO.updateProjectLinks(updated, userName, originalAddresses)
              projectLinkDAO.updateProjectLinkAdministrativeClassDiscontinuity(Set(updated.maxBy(_.addrMRange.end).id), roadAddressChangeType, userName, administrativeClass, Some(discontinuity))
              //transfer cases should remove the reserved part after the project link table update operation
              if (reservationNotNeeded) {
                projectReservedPartDAO.removeReservedRoadPart(projectId, oldRoadPart.get)
              }
              val nameError = roadName.flatMap(setProjectRoadName(projectId, newRoadPart.roadNumber, _)).toList.headOption
              if (nameError.nonEmpty)
                return nameError

            case RoadAddressChangeType.Unchanged =>
              checkAndMakeReservation(projectId, newRoadPart, RoadAddressChangeType.Unchanged, toUpdateLinks)
              // ely, administrativeClass and discontinuity can change when changeType is unChanged
              val updatedLinks = toUpdateLinks.map { link =>
                link.copy(
                  ely = ely.getOrElse(link.ely),
                  administrativeClass = AdministrativeClass.apply(administrativeClass.toInt),
                  status = roadAddressChangeType
                )
              }
              setDiscontinuityAndUpdateProjectLinks(updatedLinks)

            case RoadAddressChangeType.New => {
              // Current logic allows only re adding new road addresses within same road/part group
              if (toUpdateLinks.groupBy(l => (l.roadPart)).size <= 1) {
                checkAndMakeReservation(projectId, newRoadPart, RoadAddressChangeType.New, toUpdateLinks)
                val updatedLinks = toUpdateLinks.map { link =>
                  link.copy(
                    roadPart = newRoadPart,
                    track = Track.apply(newTrackCode),
                    administrativeClass = AdministrativeClass.apply(administrativeClass.toInt),
                    ely = ely.getOrElse(link.ely)
                  )
                }
                setDiscontinuityAndUpdateProjectLinks(updatedLinks)
                val nameError = roadName.flatMap(setProjectRoadName(projectId, newRoadPart.roadNumber, _)).toList.headOption
                if (nameError.nonEmpty)
                  return nameError
              } else {
                throw new RoadAddressException(s"Useamman kuin yhden tien/tieosan tallennus kerralla ei ole tuettu.")
              }
            }
            case _ =>
              throw new ProjectValidationException(s"Virheellinen operaatio $roadAddressChangeType")
          }

          updateProjectElyCodes()

          if (coordinates.isDefined) {
            saveProjectCoordinates(projectId, coordinates.get)
          }
          else {
            saveProjectCoordinates(projectId, calculateProjectCoordinates(projectId))
          }
          None
        }
      }
    catch {
      case ex: RoadAddressException =>
        logger.info("Road address Exception: " + ex.getMessage)
        Some(s"Tieosoitevirhe: ${ex.getMessage}")
      case ex: ProjectValidationException => Some(ex.getMessage)
      case ex: Exception => Some(ex.getMessage)
    }
  }

  def fuseSplittedProjectLinks(links: Seq[ProjectLink]): Seq[ProjectLink] = {
    val sorted = links.sortBy(_.startMValue)
    val linkIds = sorted.map(_.linkId).distinct
    if (!sorted.exists(_.reversed) &&
        linkIds.lengthCompare(1) == 0 &&
        sorted.forall(_.sideCode == links.head.sideCode) &&
        sorted.forall(_.track == links.head.track) &&
        sorted.tail.scanLeft((true, links.head.addrMRange.end)){ (a,b) => (b.addrMRange.startsAt(a._2), b.addrMRange.end)}.forall(_._1)
    ) {
      val geom = sorted.head.sideCode match {
        case SideCode.AgainstDigitizing => sorted.map(_.geometry).reverse.foldLeft(Seq[Point]())((geometries, ge) => {
          geometries ++ ge
        }).distinct
        case _ => sorted.map(_.geometry).foldLeft(Seq[Point]())((geometries, ge) => {
          geometries ++ ge
        }).distinct
      }
      val (startM, endM, startA, endA, oStartA, oEndA, startCp, endCp, oStartCp, oEndCp) =
        (links.map(_.startMValue).min,
          links.map(_.endMValue).max,
          links.map(_.addrMRange.start).min,
          links.map(_.addrMRange.end).max,
          links.map(_.originalAddrMRange.start).min,
          links.map(_.originalAddrMRange.end).max,
          links.minBy(_.addrMRange.start).startCalibrationPointType,
          links.maxBy(_.addrMRange.end).endCalibrationPointType,
          links.minBy(_.addrMRange.start).originalStartCalibrationPointType,
          links.maxBy(_.addrMRange.end).originalEndCalibrationPointType
        )

      Seq(
        sorted.head.copy(discontinuity                 = links.maxBy(_.addrMRange.start).discontinuity, addrMRange = AddrMRange(startA, endA), originalAddrMRange = AddrMRange(oStartA, oEndA), startMValue                   = startM, endMValue                     = endM, calibrationPointTypes         = (startCp, endCp), originalCalibrationPointTypes = (oStartCp, oEndCp), geometry                      = geom, geometryLength                = GeometryUtils.geometryLength(geom), connectedLinkId               = None)
      )
    } else
      links
  }

  /***
   * Required when user calculates project more than once.
   * Removes user defined calibration points and splits generated in previous calc.
   * @param pls Non-terminated ProjectLinks in the same road/part.
   * @return ProjectLinks without split made in previous calculation if any.
   */
  def getFusedProjectLinks(pls: Seq[ProjectLink]): Seq[ProjectLink] = if (pls.isEmpty) {
    Seq()
  } else {
    val (connectedGroups, unConnectedGroups) = pls.groupBy(_.connectedLinkId).partition(_._1.isDefined)
    val unConnnected                         = unConnectedGroups.values.flatten.toSeq
    val connected                            = connectedGroups.values.flatMap(v => {
      fuseSplittedProjectLinks(v.map(f = c => {
        c.endCalibrationPointType match {case UserDefinedCP => c.copy(calibrationPointTypes = (c.startCalibrationPointType, NoCP)); case _ => c;}
      }))
    }).toSeq
    val connectedIds                         = connected.map(_.id).toSet
    val connectedGroupsIds                   = connectedGroups.values.flatten.map(_.id).toSet
    roadwayChangesDAO.clearRoadChangeTable(pls.head.projectId)
    projectLinkDAO.removeProjectLinksById(connectedGroupsIds.diff(connectedIds))
    unConnnected ++ connected
  }

  /***
   * Required when user calculates project more than once and terminates or reverts previously split project links.
   * If project links are split in previous calculation and are now terminated or reverted, the unnecessary splits need to be removed.
   * The termination or reverting process handles updating the original link to state before split, so the newly created split links can be removed.
   * Removes unnecessary splits generated in previous calc when terminating or reverting road links.
   * Updates the geometry to the original values from KGV.
   *
   * @param projectLinks ProjectLinks to be evaluated.
   * @param projectId The ID of the project being processed.
   * @return A sequence of project links with unnecessary splits removed.
   */
  def identifyAndHandleSplitProjectLinks(projectLinks: Seq[ProjectLink], projectId: Long): Seq[ProjectLink] = {
    // A mutable collection to keep track of updated project links
    val updatedProjectLinks = scala.collection.mutable.Buffer[ProjectLink]()

    // Identify splits to remove
    val splitsToHandle = identifySplitsToHandle(projectLinks)

    if (splitsToHandle.nonEmpty) {
      splitsToHandle.foreach { case (originalProjectLink, splitProjectLinks) =>
        // Remove split project links and update geometry of the original project link
        logger.info(s"Removing split project links for original link ID: ${originalProjectLink.id} with split link IDs: ${splitProjectLinks.map(_.id).mkString(", ")}")
        val splitLinkIds = splitProjectLinks.map(_.id)
        removeSplitProjectLinks(splitLinkIds, originalProjectLink, projectId)

        updateGeometryOfOriginalProjectLink(originalProjectLink) match {
          case Some(updatedLink) => updatedProjectLinks += updatedLink
          case None => updatedProjectLinks += originalProjectLink // If geometry was not updated, keep the original
        }
      }
    } else {
      logger.info("No split project links to handle.")
      // If no splits to handle, return the original not handled project links
      return projectLinks
    }
    // Return the final updated list of project links
    updatedProjectLinks
  }

  /**
   * Identifies splits in project links that need to be handled. This only works after the termination or reverting of project links.
   * Splits are identified based on the linear location ID of the project links.
   * If a split is found where the start and end address M values are the same as the original project link, it is considered unnecessary and should be removed.
   * @param projectLinks The project links to be evaluated.
   * @return A map of original project links to their corresponding split project links or empty map if no splits to remove are found.
   */
  def identifySplitsToHandle(projectLinks: Seq[ProjectLink]): Map[ProjectLink, Seq[ProjectLink]] = {
    projectLinks.groupBy(_.linearLocationId).flatMap { case (_, links) =>
      val originalLink = links.minBy(_.id) // Assuming the first link is always the original
      val splitsToRemove = links.filter { splitLink =>
        splitLink.id != originalLink.id &&
          originalLink.addrMRange == splitLink.addrMRange
      }
      if (splitsToRemove.nonEmpty) Some(originalLink -> splitsToRemove) else None
    }
  }

  /**
   * Removes a collection of split project links based on their IDs and updates the related project link to remove the connection to these splits.
   * This function is integral to maintaining project link data integrity, ensuring that unnecessary splits are efficiently removed from the database.
   *
   * @param splitProjectLinkIds A sequence of IDs for the split project links to be removed.
   * @param originalProjectLink The original project link that is associated with the split links.
   * @param projectId The ID of the project for which the operation is being performed.
   */
  private def removeSplitProjectLinks(splitProjectLinkIds: Seq[Long], originalProjectLink: ProjectLink, projectId: Long): Unit = {
    try {
      roadwayChangesDAO.clearRoadChangeTable(projectId)
      projectLinkDAO.handleSplitProjectLinksRemovalAndUpdate(splitProjectLinkIds, originalProjectLink, projectId)
    } catch {
      case e: ViiteException => logger.error(s"Failed to remove split project links, originalProjectLink: ${originalProjectLink.id}  ${originalProjectLink.connectedLinkId}", e)
    }
  }

  private def updateGeometryOfOriginalProjectLink(originalProjectLink: ProjectLink): Option[ProjectLink] = {
    val kgvRoadLinks = roadLinkService.getCurrentAndComplementaryRoadLinks(Set(originalProjectLink.linkId))
    kgvRoadLinks.find(_.linkId == originalProjectLink.linkId).map { kgvGeometry =>
      val updatedGeom = GeometryUtils.truncateGeometry3D(kgvGeometry.geometry, originalProjectLink.startMValue, originalProjectLink.endMValue)
      projectLinkDAO.updateProjectLinkGeometry(originalProjectLink.id, updatedGeom)
      originalProjectLink.copy(geometry = updatedGeom)
    }
  }

  /**
   * Adjusts calibration points for project links by adding RoadAddressCp to discontinuous links
   * and to spots where the track changes (e.g., from double track to single track or vice versa).
   * @param projectLinks A sequence of project links that need to be checked and adjusted
   * @return A sequence of project links with adjusted calibration points
   */
  def adjustCalibrationPointsOnProjectLinks(projectLinks: Seq[ProjectLink]): Seq[ProjectLink] = {
    projectLinks.map(pl => {
      if (pl.discontinuity != Discontinuity.Continuous ||
        projectLinks.exists(pl2 =>  pl.originalAddrMRange.end != 0 && pl2.originalAddrMRange.continuesFrom(pl.originalAddrMRange) && pl.track != pl2.track) ||
        projectLinks.exists(pl2 =>  pl.originalAddrMRange.end != 0 && pl2.originalAddrMRange.continuesFrom(pl.originalAddrMRange) && pl.track == pl2.track &&
          pl.administrativeClass != pl2.administrativeClass))
      {
        pl.copy(calibrationPointTypes = (pl.calibrationPointTypes._1, CalibrationPointType.RoadAddressCP))
      } else
        pl
    })
  }

  def recalculateProjectLinks(projectId: Long, userName: String, roadParts: Set[RoadPart] = Set()): Unit = {


    logger.info(s"Recalculating project $projectId, parts ${roadParts.mkString(", ")}")

    time(logger, "Recalculate links") {
      val projectLinks = projectLinkDAO.fetchProjectLinks(projectId)
      val nonTerminatedProjectLinks = projectLinks.filterNot(_.status == RoadAddressChangeType.Termination)

      var terminatedProjectLinks = new ListBuffer[ProjectLink]()
      val recalculated = nonTerminatedProjectLinks.groupBy(pl => {
        (pl.roadPart)
      }).flatMap {
        grp =>
          val fusedLinks = getFusedProjectLinks(grp._2)
          val calibrationPoints = ProjectCalibrationPointDAO.fetchByRoadPart(projectId, grp._1)
          val projectLinksWithAdjustedCalibrationPoints = adjustCalibrationPointsOnProjectLinks(fusedLinks)
          val recalculatedProjectLinks = ProjectSectionCalculator.assignAddrMValues(projectLinksWithAdjustedCalibrationPoints, calibrationPoints)
          val (recalculatedNonTerminated, recalculatedTerminated) = recalculatedProjectLinks.partition(pl => pl.status != RoadAddressChangeType.Termination)

          terminatedProjectLinks ++= recalculatedTerminated

          recalculatedNonTerminated.sortBy(_.addrMRange.end)
      }.toSeq

      val terminatedProjectLinksWithAssignedRoadwayNumbers = assignRoadwayNumbersToTerminatedProjectLinks(nonTerminatedProjectLinks ++ terminatedProjectLinks)
      val originalAddresses = roadAddressService.getRoadAddressesByRoadwayIds((recalculated ++ terminatedProjectLinks).map(_.roadwayId))
      projectLinkDAO.updateProjectLinks(recalculated ++ terminatedProjectLinksWithAssignedRoadwayNumbers, userName, originalAddresses)
      val projectLinkIdsToDB = recalculated.map(_.id).diff(projectLinks.map(_.id))
      projectLinkDAO.create(recalculated.filter(pl => projectLinkIdsToDB.contains(pl.id)))
    }
  }

  /**
   * Handles the roadway numbering of terminated project links
   * The process:
   * 1.     group the projectLinks in to sections by roadway and track and check if all the links existing in the section are terminated.
   *
   * 1.1.   If yes Then there is no need to expire the roadway section and the roadway numbers can stay as they are.
   *        e.g. RwNumber 123 : |--Terminated-->|--Terminated-->|
   *
   * 1.2.1  If not, then we are splitting the original roadway section in to smaller sections.
   *        And when we split the original roadway section then we need to assign new roadway number to the terminated project links.
   *
   *        example 1: RwNumber 123 : |--Other action-->|--Terminated-->|
   *        The terminated project links will be assigned new roadway number
   *
   *        example 2: VIITE-3038 If the section is divided in to more than one terminated section
   *        e.g. RwNumber 123 : |--Other action-->|--Terminated-->|--Other action-->|--Terminated-->|--Other action-->|
   *        then each of the terminated sections will need to be assigned new roadway numbers
   *
   * @param allProjectLinks: all the project links of the project (terminated and non terminated)
   */
  // VIITE-2179
  private def assignRoadwayNumbersToTerminatedProjectLinks(allProjectLinks: Seq[ProjectLink]): Seq[ProjectLink] = {
    //getting project link sections by roadway number and track
    val sectionGroup = allProjectLinks.groupBy(pl => (pl.track, pl.roadwayNumber))
    //check if entire project link section was terminated
    sectionGroup.values.flatMap { pls =>
      if (!pls.forall(_.status == RoadAddressChangeType.Termination)) {
        val terminatedProjectLinksOnSameOriginalRoadway = pls.filter(_.status == RoadAddressChangeType.Termination)
        val terminatedSections = divideTerminatedProjectLinksInToSections(terminatedProjectLinksOnSameOriginalRoadway)
        val terminatedWithNewRoadwayNumbers = terminatedSections.flatMap(terminatedSection => {
          val newRoadwayNumber = Sequences.nextRoadwayNumber
          terminatedSection.map(pl => pl.copy(roadwayNumber = newRoadwayNumber))
        })
        terminatedWithNewRoadwayNumbers
      } else pls.filter(_.status == RoadAddressChangeType.Termination)
    }.toSeq
  }

  /**
   * Divide terminated project links in to continuous sections by their original addr M values
   *
   * In this example the original roadway has startAddrm: 0 and endAddrM: 600
   * The roadway has been terminated in three different sections of the original roadway illustrated below (Note: non terminated project links not included in the illustration)
   *
   * original addrM:             100        150        200         250      300       350      400
   * terminated project Links:   |---TPL1--->|---TPL2--->           |--TPL3-->        |--TPL4-->
   *
   * The function divides these terminated project links in to sections like this:
   * Seq(Seq(TPL1,TPL2), Seq(TPL3), Seq(TPL4))
   *
   * @param terminatedProjectLinks: Project links on the same original roadway with Terminated status (The sequence needs to be ordered by the addrM values)
   */
  def divideTerminatedProjectLinksInToSections(terminatedProjectLinks: Seq[ProjectLink]): Seq[Seq[ProjectLink]] = {
    def projectLinksOriginallyContinuousByAddressMValue(pl1: ProjectLink, pl2: ProjectLink): Boolean = {
      pl1.originalAddrMRange.isAdjacentTo(pl2.originalAddrMRange)
    }

    // Iterative function to build sections
    def buildSections(links: Seq[ProjectLink]): Seq[Seq[ProjectLink]] = {
      var result: Seq[Seq[ProjectLink]] = Seq.empty
      var currentSection: Seq[ProjectLink] = Seq.empty

      for (link <- links) {
        if (currentSection.isEmpty || projectLinksOriginallyContinuousByAddressMValue(currentSection.last, link)) {
          // Extend the current section if it's empty or the project links were originally continuous by address M values
          currentSection :+= link
        } else {
          // Start a new section
          result :+= currentSection
          currentSection = Seq(link)
        }
      }

      // Add the last section to the result sets
      if (currentSection.nonEmpty) {
        result :+= currentSection
      }

      result
    }

    // Start building sections with the input project links
    buildSections(terminatedProjectLinks)
  }


  /** @return Whether we did the recalculation or not
    * @throws IllegalArgumentException when the given project is not found. */
  private def recalculateChangeTable(projectId: Long): (Boolean, Option[String]) = {
    val projectOpt = fetchProjectById(projectId)
    if (projectOpt.isEmpty)
      throw new IllegalArgumentException("Project not found")
    val project = projectOpt.get
    project.projectState match {
      case ProjectState.Accepted | ProjectState.InUpdateQueue | ProjectState.UpdatingToRoadNetwork => (true, None)
      case _ =>
        roadwayChangesDAO.clearRoadChangeTable(projectId)
        roadwayChangesDAO.insertDeltaToRoadChangeTable(projectId, projectOpt)
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
    runWithReadOnlySession {
      projectLinkDAO.fetchProjectLinks(projectId, Some(RoadAddressChangeType.NotHandled)).isEmpty &&
      projectLinkDAO.fetchProjectLinks(projectId).nonEmpty
    }
  }

  /** Changes projects status to unfinished
    *
    * @param projectId project-id
    * @return returns option error string
    */

  def reOpenProject(projectId: Long): Option[String] = {
    runWithTransaction {
      val project = fetchProjectById(projectId)
      projectDAO.updateProjectStatus(projectId, ProjectState.Incomplete)
      if (project.isEmpty)
        return Some("Projektia ei löytynyt")
    }
    None
  }

  /**
    * Publish project with id projectId: recalculates the change table.
    *
    * @param projectId Project to publish
    * @return optional error message, empty if no error
    */
  def publishProject(projectId: Long): PublishResult = {
    logger.info(s"Preparing to persist changes of project $projectId to the road network")
    runWithTransaction {
      if (!recalculateChangeTable(projectId)._1) {
        return PublishResult(validationSuccess = false, sendSuccess = false, Some("Muutostaulun luonti epäonnistui. Tarkasta ely"))
      }
      else {
        projectDAO.updateProjectStatus(projectId, InUpdateQueue)
        logger.info(s"Returning dummy 'Yesyes, TR part ok', as TR call removed")
        PublishResult(validationSuccess = true, sendSuccess = true, Some(""))
      }
    }
  }

  private def newProjectTemplate(rl: RoadLinkLike, ra: RoadAddress, project: Project): ProjectLink = {
    val geometry = GeometryUtils.truncateGeometry3D(rl.geometry, ra.startMValue, ra.endMValue)
    val newEly = project.reservedParts.find(rp => rp.roadPart == ra.roadPart) match {
      case Some(rp) => rp.ely.getOrElse(ra.ely)
      case _ => ra.ely
    }

    ProjectLink(NewIdValue, ra.roadPart, ra.track, ra.discontinuity, ra.addrMRange, ra.addrMRange, ra.startDate, ra.endDate, Some(project.modifiedBy), ra.linkId, ra.startMValue, ra.endMValue, ra.sideCode, ra.calibrationPointTypes, (ra.startCalibrationPointType, ra.endCalibrationPointType), geometry, project.id, RoadAddressChangeType.NotHandled, ra.administrativeClass, ra.linkGeomSource, GeometryUtils.geometryLength(geometry), ra.id, ra.linearLocationId, newEly, ra.reversed, None, ra.adjustedTimestamp, roadAddressLength = ra.addrMRange.lengthOption)
  }

  private def newProjectLink(rl: RoadLinkLike, project: Project, roadPart: RoadPart, trackCode: Track, discontinuity: Discontinuity, administrativeClass: AdministrativeClass, ely: Long, roadName: String = "") = {

    val roadLinkTimestamp: Long = Try(rl.roadLinkTimeStamp).getOrElse(0)
    ProjectLink(NewIdValue, roadPart, trackCode, discontinuity, AddrMRange(0L, 0L), AddrMRange(0L, 0L), Some(project.startDate), None, Some(project.modifiedBy), rl.linkId, 0.0, rl.length, SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP), rl.geometry, project.id, RoadAddressChangeType.New, administrativeClass, rl.linkSource, rl.length, 0L, 0L, ely, false, None, roadLinkTimestamp, roadName = Some(roadName))
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
      (record.roadwayNumber, record.roadPart, record.track.value, record.startDate, record.endDate, record.linkId, record.administrativeClass, record.ely, record.terminated))

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
                                             links.map(_.addrMRange.start).min, links.map(_.addrMRange.end).max)
        Seq(links.head.copy(discontinuity = links.maxBy(_.addrMRange.start).discontinuity, addrMRange = AddrMRange(startA, endA), startMValue = startM, endMValue = endM, geometry = geom, geometryLength = GeometryUtils.geometryLength(geom)))
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
    * Enclosing runWithTransaction ensures that the retrieved project cannot be given to multiple
    * handler calls at the same time.
    * @return Some(projectId), when there was a reservable project, None if not.
    * @throws Exception if an unexpected exception occurred. */
  def atomicallyReserveProjectInUpdateQueue: Option[Long] = {
    var reserveStatus: Option[Long] = None  // nothing reserved yet

    runWithTransaction { // to ensure uniquely retrieved projectId for each calling handler thread
      reserveStatus = {
        try {
          val projectId = projectDAO.fetchSingleProjectIdWithInUpdateQueueStatus
          if (projectId.isDefined)
            projectDAO.updateProjectStatus(projectId.get, ProjectState.UpdatingToRoadNetwork)
          projectId
        } catch {
          case t: Exception =>
            logger.warn(s"Unexpected exception while reserving a project for preserving: ${t.getMessage}", t)
            throw t // Rethrow the unexpected error.
        }
      }
    }
    reserveStatus
  }

  /** Preserves the information of a single road network project to the road network,
    * if any such project is waiting.
    * @throws SQLException if there is an error with preserving the reserved project to the db.
    * @throws Exception if an unexpected exception occurred. */
  def preserveSingleProjectToBeTakenToRoadNetwork(): Unit = {
    // Get a project to update to db, if any. Reserved apart from db preserve, to communicate the reservation asap.
    val projectIdOpt: Option[Long] = atomicallyReserveProjectInUpdateQueue

    var projectId: Long = -1L // dummy

    projectIdOpt match {

      case None =>
        logger.debug(s"No projects to update to the road network.")

      case Some(id) => {
        // Try preserving the project to the road network
        try {
          runWithTransaction {
            projectId = id
            time(logger, s"Preserve the project $projectId to the road network") {
              preserveProjectToDB(projectId)
            }
            // Got through without Exceptions -> the project was successfully preserved
            projectDAO.changeProjectStatusToAccepted(projectId)
          }
        } catch {
          // In case of any error, set project status to ProjectState.ErrorInViite
          case t: InvalidAddressDataException =>
            logger.warn(s"InvalidAddressDataException while preserving the project $projectId" +
                        s" to the road network. ${t.getMessage}", t)
            setProjectStatus(projectId, ProjectState.ErrorInViite)
          case t: SQLException =>
            logger.error(s"SQL error while preserving the project $projectId" +
                         s" to the road network. ${t.getMessage}", t)
            setProjectStatus(projectId, ProjectState.ErrorInViite)
          case t: RoadNetworkValidationException =>
            logger.error(s"The project $projectId did not pass the generic Viite network validations. ${t.getMessage}", t)
            setProjectStatusWithInfo(projectId, ProjectState.ErrorInViite, t.getMessage)
          // If we got an unexpected error, re-throw it, too.
          case t: Exception =>
            logger.error(s"Unexpected exception while preserving the project $projectId" +
                         s" to the road network. ${t.getMessage}", t)
            setProjectStatus(projectId, ProjectState.ErrorInViite)
            throw t  // Rethrow the unexpected error.
        }
      } // case Some
    }
  }

  def setProjectStatus(projectId: Long, projectState: ProjectState): Unit = {
    runWithTransaction {
      projectDAO.updateProjectStatus(projectId, projectState)
    }
  }

  def setProjectStatusWithInfo(projectId: Long, projectState: ProjectState, statusInfo: String): Unit = {
    runWithTransaction {
      projectDAO.updateProjectStatusWithInfo(projectId, projectState, statusInfo)
    }
  }

  def getProjectState(projectId: Long): Option[ProjectState] = {
    runWithReadOnlySession {
      projectDAO.fetchProjectStatus(projectId)
    }
  }
  /**
   * Fetch project states for project ids.
   * @param projectIds to query.
   * Returns project ids with state code.
   */
  def getProjectStates(projectIds: Set[Int]): Seq[(Int, Int)] = {
    runWithReadOnlySession {
      projectDAO.fetchProjectStates(projectIds)
    }
  }

  /**
    * Converts all the project links of project <i>projectId</i> into regular road addresses,
    * and saves them to the linear location, and roadway tables.
    *
    * @param projectID : Long - The id of the project to be persisted.
    */
  private def preserveProjectToDB(projectID: Long): Unit = {
    val currentState = projectDAO.fetchProjectStatus(projectID)
      logger.info(s"Current status is $currentState")
      if (currentState.isDefined && currentState.get == UpdatingToRoadNetwork) {
        logger.info(s"Start importing road addresses of project $projectID to the road network")
        try {
          val roadParts = updateRoadwaysAndLinearLocationsWithProjectLinks(projectID)
          roadNetworkValidator.validateRoadNetwork(roadParts)
        } catch {
          case t: InvalidAddressDataException =>
            throw t // Rethrow the unexpected error.
          case t: RoadNetworkValidationException =>
            throw t // Rethrow the unexpected error.
        }
      } else Set.empty[Long]
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
  def updateRoadwaysAndLinearLocationsWithProjectLinks(projectID: Long): Seq[RoadPart] = {
    def handleRoadPrimaryTables(currentRoadways: Map[Long, Roadway], historyRoadways: Map[Long, Roadway], roadwaysToInsert: Iterable[Roadway], historyRoadwaysToKeep: Seq[Long],
                                nonTerminatingLinearLocationsToInsert: Iterable[LinearLocation], project: Project): Seq[Long] = {
      logger.debug(s"Creating history rows based on operation")
      linearLocationDAO.expireByRoadwayNumbers((currentRoadways ++ historyRoadways).map(_._2.roadwayNumber).toSet)
      (currentRoadways ++ historyRoadways.filterNot(hist => historyRoadwaysToKeep.contains(hist._1))).map(roadway => expireHistoryRows(roadway._1))

      logger.debug(s"Inserting roadways (history + current)")
      val roadwayIds = roadwayDAO.create(roadwaysToInsert.filter(roadway =>
        roadway.endDate.isEmpty || !roadway.startDate.isAfter(roadway.endDate.get)
      ).map(_.copy(createdBy = project.createdBy))
      )

      // Insert new linear locations excluding linear locations of terminated links
      logger.debug(s"Inserting linear locations: ${nonTerminatingLinearLocationsToInsert.mkString(", ")}")
      linearLocationDAO.create(nonTerminatingLinearLocationsToInsert, createdBy = project.createdBy)
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
      roadAddressService.handleProjectCalibrationPointChanges(linearLocations, username, projectLinkChanges.filter(_.status == RoadAddressChangeType.Termination))
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
      case Some(pState) =>
        if (pState == UpdatingToRoadNetwork) {
          /* just continue */
        } else {
          logger.error(s"Project state not at UpdatingToRoadNetwork: $pState")
          throw new RuntimeException(s"Project state not at UpdatingToRoadNetwork: $pState")
        }
      case None =>
        logger.error(s"Project $projectID was not found (and thus, state not at UpdatingToRoadNetwork)")
        throw new RuntimeException(s"Project $projectID was not found when fetching ProjectStatus")
    }

    val project = projectDAO.fetchById(projectID).get
    val nodeIds = nodeDAO.fetchNodeNumbersByProject(projectID)
    /* Remove userdefined calibrationpoints from calculation. Assume udcp:s defined at projectlink splits. */
    val projectLinks = projectLinkDAO.fetchProjectLinks(projectID).map(pl => if (pl.calibrationPointTypes._2 == UserDefinedCP) pl.copy(calibrationPointTypes = (pl.calibrationPointTypes._1, NoCP)) else pl )
    if (projectLinks.isEmpty) {
      logger.error(s" There are no addresses to update, rollbacking update of project ${project.id}")
      throw new InvalidAddressDataException(s"There were no addresses to update in project ${project.id}")
    }

    val projectLinkChanges = projectLinkDAO.fetchProjectLinksChange(projectID)
    val currentRoadways = roadwayDAO.fetchAllByRoadwayId(projectLinks.map(pl => pl.roadwayId)).map(roadway => (roadway.id, roadway)).toMap
    val historyRoadways = roadwayDAO.fetchAllByRoadwayNumbers(currentRoadways.map(_._2.roadwayNumber).toSet, withHistory = true).filter(_.endDate.isDefined).map(roadway => (roadway.id, roadway)).toMap

    def findHistoryRoadways(rwnum: Long):Seq[Roadway] = {
      historyRoadways.filter(_._2.roadwayNumber == rwnum).values.toSeq
    }

    val roadwayChanges = roadwayChangesDAO.fetchRoadwayChanges(Set(projectID))
    val mappedRoadwayChanges = currentRoadways.values.map(r => RoadwayFiller.RwChanges(r, findHistoryRoadways(r.roadwayNumber), projectLinks.filter(_.roadwayId == r.id))).toList

    // fetch terminated link ids before moving project links to project link history
    val terminatedLinkIDs = projectLinkDAO.fetchProjectLinks(projectID, Some(RoadAddressChangeType.Termination)).map(_.linkId)

    try {
      logger.debug(s"Moving project links to project link history.")
      projectLinkDAO.moveProjectLinksToHistory(projectID)

      val new_generatedRoadways = RoadwayFiller.applyNewLinks(projectLinks.filter(_.status == RoadAddressChangeType.New))
      val generatedRoadways = RoadwayFiller.applyRoadwayChanges(mappedRoadwayChanges).flatten.filter(_._1.nonEmpty) ++ new_generatedRoadways
      val historyRoadwaysToKeep = generatedRoadways.flatMap(_._1).filter(_.id != NewIdValue).map(_.id)
      var linearLocationsToInsert = generatedRoadways.flatMap(_._2).groupBy(l => (l.roadwayNumber, l.orderNumber, l.linkId,
                                                                                   l.startMValue, l.endMValue, l.validTo.map(_.toYearMonthDay), l.startCalibrationPoint.addrM, l.endCalibrationPoint.addrM, l.sideCode,
                                                                                   l.linkGeomSource)).map(_._2.head).toSeq

      val roadwaysToInsert = generatedRoadways.flatMap(_._1).filter(_.id == NewIdValue).filter(_.endDate.isEmpty).groupBy(_.track).flatMap(g => (g._2.groupBy(_.roadwayNumber).map(t => t._2.head.copy(addrMRange = AddrMRange(t._2.minBy(_.addrMRange.start).addrMRange.start, t._2.maxBy(_.addrMRange.end).addrMRange.end))))) ++ generatedRoadways.flatMap(_._1).filter(_.id == NewIdValue).filter(_.endDate.nonEmpty)
      roadwaysToInsert.foreach(rwtoinsert => logger.info(s"roadwaysToInsert ${rwtoinsert.roadwayNumber} ${rwtoinsert.endDate} ${rwtoinsert.validTo}"))

      /* Fuse linearlocations with the same roadwaynumber and linkid. */
      val linearLocationsToFuse = linearLocationsToInsert.groupBy(ll => (ll.roadwayNumber, ll.linkId)).values.filter(_.size > 1)
      val linearLocationsToFuseIds = linearLocationsToFuse.flatten.map(_.id).toSeq
      val fusedLinearLocations = linearLocationsToFuse.map(lls => {
        val firstLl =  lls.minBy(_.startMValue)
        val lastLl = lls.maxBy(_.endMValue)
        val geometries =
          if (lls.head.sideCode == SideCode.TowardsDigitizing)
            lls.sortBy(_.startMValue).flatMap(_.geometry).distinct
          else
            lls.sortBy(ll => -ll.startMValue).flatMap(_.geometry).distinct
        firstLl.copy(startMValue = firstLl.startMValue, endMValue = lastLl.endMValue, calibrationPoints = (firstLl.startCalibrationPoint, lastLl.endCalibrationPoint), geometry = geometries)
      })
      linearLocationsToInsert = linearLocationsToInsert.filterNot(ll => linearLocationsToFuseIds.contains(ll.id)) ++ fusedLinearLocations

      /* Update order numbers. */
      val rwns = generatedRoadways.flatMap(_._1).map(g => g.roadwayNumber).distinct
      linearLocationsToInsert = rwns.flatMap(r => {
        val lins = linearLocationsToInsert.filter(l => l.roadwayNumber == r).toList
        val lins_link_ids = lins.map(_.linkId)
        val sorted_pls = projectLinks.filter(pl => lins_link_ids.contains(pl.linkId)).sortBy(_.addrMRange.start)
        val sorted_lins: Seq[LinearLocation] = sorted_pls.flatMap(pl => lins.filter(l => l.linkId == pl.linkId && l.startMValue == pl.startMValue).sortBy(_.startMValue) )
        sorted_lins.zip(1 to lins.size).map(ls => ls._1.copy(orderNumber =  ls._2))
      })

      // Exclude linear locations that are terminated from insertion
      val nonTerminatingLinearLocationsToInsert = linearLocationsToInsert.filterNot(l => terminatedLinkIDs.contains(l.linkId))

      val roadwayIds = handleRoadPrimaryTables(currentRoadways, historyRoadways, roadwaysToInsert, historyRoadwaysToKeep,
        nonTerminatingLinearLocationsToInsert, project)
      handleRoadComplementaryTables(roadwayChanges, projectLinkChanges, linearLocationsToInsert,
        roadwayIds, generatedRoadways, projectLinks,
        Some(project.startDate.minusDays(1)), nodeIds, project.createdBy)

      nodesAndJunctionsService.publishNodes(nodeIds, project.createdBy)
      val oldRoadParts = projectLinks.map(pl => pl.originalRoadPart)
      val newRoadParts = projectLinks.map(pl => pl.roadPart)
      (oldRoadParts ++ newRoadParts).distinct
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
      if ((rwc.changeInfo.changeType.equals(RoadAddressChangeType.Renumeration) || rwc.changeInfo.changeType.equals(RoadAddressChangeType.Transfer)
                                                                               && targetRoadNumberOptional.isDefined && srcRoadNumberOptional.isDefined)) {
        val srcRoadNumber = srcRoadNumberOptional.get
        val targetRoadNumber = targetRoadNumberOptional.get
        if (srcRoadNumber != targetRoadNumber) {
          val srcExistingRoadName    = RoadNameDAO.getLatestRoadName(srcRoadNumber)
          val targetExistingRoadName = RoadNameDAO.getLatestRoadName(targetRoadNumber)

          val srcExistingRoadways    = roadwayDAO.fetchAllByRoad(srcRoadNumber)
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
      if (rwc.changeInfo.changeType.equals(RoadAddressChangeType.Termination) && roadNumberOptional.isDefined) {
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
      if (rwc.changeInfo.changeType.equals(RoadAddressChangeType.New) && roadNumberOptional.isDefined) {
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
    runWithReadOnlySession {
      projectDAO.fetchProjectElyById(projectId)
    }
  }
  /**
   * This is for the dev tool VIITE-3203
   * For running validations without recalculation of the project links
   * (validations from the recalculation phase included)
   */
  def runOtherValidations(projectId: Long) = {
    runWithReadOnlySession {
      def throwExceptionWithErrorInfo(errorLinks: Iterable[ProjectLink], msg: String): Nothing = {
        val projectId   = errorLinks.head.projectId
        logger.error(
          s"""$msg
             | $projectId
             | Error links:
             | $errorLinks""".stripMargin)
        throw new RoadAddressException(UnsuccessfulRecalculationMessage)
      }
      def validateCombinedLinksEqualAddresses(leftAdj: Seq[ProjectLink], rightAdj: Seq[ProjectLink]): Unit = {
        val leftCombinedLinks  = leftAdj.filter(_.track == Track.Combined)
        val rightCombinedLinks = rightAdj.filter(_.track == Track.Combined)
        // Partition splitted and nonsplitted links. With splitted projectlinks id:s change and matching must be made by other values combined.
        val (allSplitted, nonSplitted) = (leftCombinedLinks ++ rightCombinedLinks).partition(_.isSplit)
        val groupedById        = nonSplitted.groupBy(pl => pl.id) ++ allSplitted.groupBy(pl => (pl.addrMRange.start, pl.addrMRange.end,pl.linkId))

        if (!groupedById.values.forall(_.size == 2)) {
          val falsePls = groupedById.filter(pls => pls._2.size != 2).values.flatten
          val msg      = "Combined links pairing mismatch in project"
          throwExceptionWithErrorInfo(falsePls, msg)
        }
        if (!groupedById.values.forall(pl => pl.head.addrMRange.start == pl.last.addrMRange.start)) {
          val falsePls = groupedById.filter(pls => pls._2.head.addrMRange.start != pls._2.last.addrMRange.start).values.flatten
          val msg      = "Combined track start address mismatch in project"
          throwExceptionWithErrorInfo(falsePls, msg)
        }
        if (!groupedById.values.forall(pl => pl.head.addrMRange.end == pl.last.addrMRange.end)) {
          val falsePls = groupedById.filter(pls => pls._2.head.addrMRange.end != pls._2.last.addrMRange.end).values.flatten
          val msg      = "Combined track end address mismatch in project"
          throwExceptionWithErrorInfo(falsePls, msg)
        }
      }

      /***
       * Checks splitted links have continuos m-values.
       * Splitting has not caused wrong ordering.
       * @param pls ProjectLinks of left or right side with combined track.
       */
      def validateMValuesOfSplittedLinks(pls: Seq[ProjectLink]): Unit = {
        if (pls.size > 1) {
          val it = pls.sliding(2)
          while (it.hasNext) {
            it.next() match {
              case Seq(first, next) =>
                if (first.connectedLinkId.isDefined && next.connectedLinkId.isDefined && first.connectedLinkId == next.connectedLinkId)
                  if (!(first.endMValue == next.startMValue || first.startMValue == next.endMValue)) {
                    val falsePls = Iterable(first, next)
                    val msg      = "Discontinuity in splitted links endMValue and startMValue in project"
                    throwExceptionWithErrorInfo(falsePls, msg)
                  }
            }
          }
        }
      }

      /***
       * Checks continuity of addresses, positivity of length and length is preserved.
       * @param pls Left or right side ProjectLinks with combined to check for continuity of addresses.
       */
      def validateAddresses(pls: Seq[ProjectLink]): Unit = {
        if (pls.size > 1 && pls.head.originalAddrMRange.start == 0) { //TODO is this a place for .isRoadPartStart, or is this for undefined addresses?
          val maxDiffForChange = 2 // i.e. caused by average calculation
          val it = pls.sliding(2)
          while (it.hasNext) {
            it.next() match {
              case Seq(curr, next) => {
                if (!curr.addrMRange.continuesTo(next.addrMRange)) {
                  logger.error(s"Address not continuous: ${curr.addrMRange.end} ${next.addrMRange.start} linkIds: ${curr.linkId} ${next.linkId}")
                  throw new RoadAddressException(ContinuousAddressCapErrorMessage)
                }
                if (!(curr.addrMRange.end > curr.addrMRange.start)) {
                  logger.error(s"Address length negative. linkId: ${curr.linkId}")
                  throw new RoadAddressException(NegativeLengthErrorMessage.format(curr.linkId))
                }
                if (curr.status != RoadAddressChangeType.New && (curr.originalTrack == curr.track || curr.track == Track.Combined) && !(Math.abs(curr.addrMRange.length - curr.originalAddrMRange.length) < maxDiffForChange)) {
                  logger.error(s"Length mismatch. New: ${curr.addrMRange.start} ${curr.addrMRange.end} original: ${curr.originalAddrMRange.start} ${curr.originalAddrMRange.end} linkId: ${curr.linkId}")
                  throw new RoadAddressException(LengthMismatchErrorMessage.format(curr.linkId, maxDiffForChange - 1))
                }
              }
            }
          }
        }
      }

      /** TODO
       * Checks continuity of addresses and geometry.
       * @param pls Left or right side ProjectLinks with combined to check for continuity.
       */
      def validateAddressesWithGeometry(pls: Seq[ProjectLink]): Unit = {
        val it = pls.sliding(2)
        while (it.hasNext) {
          it.next() match {
            case Seq(curr, next) =>
              if (curr.discontinuity == Discontinuity.Continuous && !curr.connected(next)) {
                logger.error(s"Address geometry mismatch. linkIds: ${curr.linkId} ${next.linkId}")
              }
            case _ =>
          }
        }
      }

      val projectLinks = projectLinkDAO.fetchProjectLinks(projectId)
      val withoutTerminated = projectLinks.filter(_.status != RoadAddressChangeType.Termination)
      val leftProjectLinks = withoutTerminated.filter(_.track != Track.RightSide)
      val rightProjectLinks = withoutTerminated.filter(_.track != Track.LeftSide)

      validateMValuesOfSplittedLinks(leftProjectLinks)
      validateMValuesOfSplittedLinks(rightProjectLinks)

      validateAddresses(leftProjectLinks)
      validateAddresses(rightProjectLinks)
      validateCombinedLinksEqualAddresses(leftProjectLinks, rightProjectLinks)

      validateAddressesWithGeometry(leftProjectLinks)
      validateAddressesWithGeometry(rightProjectLinks)
    }
  }

  /**
    * Main validator method.
    * Calls and executes all the validations we have for a project.
    *
    * @param projectId  : Long - Project ID
    * @return A sequence of validation errors, can be empty.
    */
  def validateProjectById(projectId: Long, newSession: Boolean = true): Seq[projectValidator.ValidationErrorDetails] = {
    if (newSession) {
      runWithReadOnlySession {
        projectValidator.validateProject(fetchProjectById(projectId).get, projectLinkDAO.fetchProjectLinks(projectId))
      }
    } else {
      projectValidator.validateProject(fetchProjectById(projectId).get, projectLinkDAO.fetchProjectLinks(projectId))
    }
  }

  def validateProjectByIdHighPriorityOnly(projectId: Long, newSession: Boolean = true): Seq[projectValidator.ValidationErrorDetails] = {
    if (newSession) {
      runWithReadOnlySession {
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

  def getRoadAddressesFromFormedRoadPart(roadPart: RoadPart, projectId: Long): Set[Map[String, Any]] = {
    runWithReadOnlySession {
      val roadAddresses = projectLinkDAO.fetchByProjectRoadPart(roadPart, projectId)
        .filter(part => part.roadAddressRoadPart.isDefined
                        && part.roadAddressRoadPart.get != roadPart)
        .map(roadAddress => (roadAddress.roadAddressRoadPart.get, roadAddress.status == RoadAddressChangeType.Renumeration))
        .map {
          ra => Map("roadAddressNumber" -> ra._1.roadNumber.toString, "roadAddressPartNumber" -> ra._1.partNumber.toString, "isNumbering" -> ra._2)
        }
      roadAddresses.toSet
    }
  }

  case class PublishResult(validationSuccess: Boolean, sendSuccess: Boolean, errorMessage: Option[String])

}

case class ProjectBoundingBoxResult(projectLinkResultF: Future[Seq[ProjectLink]], roadLinkF: Future[Seq[RoadLink]],
                                    complementaryF: Future[Seq[RoadLink]])

case class ProjectRoadLinkChange(id: Long, roadwayId: Long, originalLinearLocationId: Long, linearLocationId: Long, originalRoadPart: RoadPart, roadPart: RoadPart, originalAddrMRange: AddrMRange, newAddrMRange: AddrMRange, status: RoadAddressChangeType, reversed: Boolean, originalRoadwayNumber: Long, newRoadwayNumber: Long)



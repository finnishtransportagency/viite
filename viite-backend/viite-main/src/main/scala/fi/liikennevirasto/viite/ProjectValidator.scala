package fi.liikennevirasto.viite

import fi.liikennevirasto.digiroad2.{DummyEventBus, DummySerializer}
import fi.liikennevirasto.digiroad2.client.kgv.KgvRoadLink
import fi.liikennevirasto.digiroad2.service.RoadLinkService
import fi.liikennevirasto.digiroad2.util.ViiteProperties
import fi.liikennevirasto.digiroad2.util.LogUtils.time
import fi.liikennevirasto.viite.dao._
import fi.liikennevirasto.viite.process.{RoadwayAddressMapper, TrackSectionOrder}
import fi.liikennevirasto.viite.process.TrackSectionOrder.findChainEndpoints
import fi.vaylavirasto.viite.geometry.{BoundingRectangle, GeometryUtils, Point}
import fi.vaylavirasto.viite.model.{AdministrativeClass, Discontinuity, RoadAddressChangeType, SideCode, Track}
import fi.vaylavirasto.viite.util.DateTimeFormatters.finnishDateFormatter
import org.slf4j.LoggerFactory

import scala.annotation.tailrec
import scala.collection.immutable.ListMap

class ProjectValidator {

  private val logger = LoggerFactory.getLogger(getClass)
  lazy val KGVClient: KgvRoadLink = new KgvRoadLink
  val eventBus = new DummyEventBus
  val linkService = new RoadLinkService(KGVClient, eventBus, new DummySerializer, ViiteProperties.kgvRoadlinkFrozen)
  val roadwayDAO = new RoadwayDAO
  val linearLocationDAO = new LinearLocationDAO
  val roadNetworkDAO: RoadNetworkDAO = new RoadNetworkDAO
  val roadwayPointDAO = new RoadwayPointDAO
  val nodePointDAO = new NodePointDAO
  val junctionPointDAO = new JunctionPointDAO
  val roadAddressService: RoadAddressService = new RoadAddressService(linkService, roadwayDAO, linearLocationDAO, roadNetworkDAO, roadwayPointDAO, nodePointDAO, junctionPointDAO, new RoadwayAddressMapper(roadwayDAO, linearLocationDAO), eventBus, ViiteProperties.kgvRoadlinkFrozen) {

    override def withDynSession[T](f: => T): T = f

    override def withDynTransaction[T](f: => T): T = f
  }

  val projectLinkDAO = new ProjectLinkDAO
  val projectReservedPartDAO = new ProjectReservedPartDAO
  val projectDAO = new ProjectDAO
  val defaultZoomlevel = 12

  def checkReservedExistence(currentProject: Project, newRoadNumber: Long, newRoadPart: Long, roadAddressChangeType: RoadAddressChangeType, projectLinks: Seq[ProjectLink]): Unit = {
    if (RoadAddressChangeType.New.value == roadAddressChangeType.value) {
      if (roadAddressService.getRoadAddressesFiltered(newRoadNumber, newRoadPart).nonEmpty) {
        if (projectReservedPartDAO.fetchProjectReservedPart(newRoadNumber, newRoadPart, currentProject.id, withProjectId = Some(false)).nonEmpty) {
          throw new ProjectValidationException(ErrorRoadAlreadyExistsOrInUse)
        } else if (!projectReservedPartDAO.fetchReservedRoadParts(currentProject.id).exists(p => p.roadNumber == newRoadNumber && p.roadPartNumber == newRoadPart)) {
          throw new ProjectValidationException(RoadNotAvailableMessage)
        }
      } else {
        val roadPartLinks = projectLinkDAO.fetchProjectLinksByProjectRoadPart(newRoadNumber, newRoadPart, currentProject.id)
        if (roadPartLinks.exists(rpl => rpl.status == RoadAddressChangeType.Renumeration)) {
          throw new ProjectValidationException(ErrorNewActionWithNumbering)
        }
      }
    } else if (RoadAddressChangeType.Transfer.value == roadAddressChangeType.value) {
      val roadPartLinks = projectLinkDAO.fetchProjectLinksByProjectRoadPart(newRoadNumber, newRoadPart, currentProject.id)
      if (roadPartLinks.exists(rpl => rpl.status == RoadAddressChangeType.Renumeration)) {
        throw new ProjectValidationException(ErrorTransferActionWithNumbering)
      }
    } else if (RoadAddressChangeType.Renumeration.value == roadAddressChangeType.value) {
      val roadPartLinks = projectLinkDAO.fetchProjectLinksByProjectRoadPart(newRoadNumber, newRoadPart, currentProject.id)
      if (roadPartLinks.exists(rpl => rpl.status != RoadAddressChangeType.Renumeration)) {
        throw new ProjectValidationException(ErrorOtherActionWithNumbering)
      }
    }
  }

  def checkFormationInOtherProject(currentProject: Project, newRoadNumber: Long, newRoadPart: Long, roadAddressChangeType: RoadAddressChangeType): Unit = {
      val formedPartsOtherProjects = projectReservedPartDAO.fetchFormedRoadParts(currentProject.id, withProjectId = false)
      if (formedPartsOtherProjects.nonEmpty && formedPartsOtherProjects.exists(p => p.roadNumber == newRoadNumber && p.roadPartNumber == newRoadPart))
        throw new ProjectValidationException(ErrorRoadAlreadyExistsOrInUse)
  }

  def checkAvailable(number: Long, part: Long, currentProject: Project): Unit = {
    if (projectReservedPartDAO.isNotAvailableForProject(number, part, currentProject.id)) {
      throw new ProjectValidationException(RoadNotAvailableMessage.format(number, part, currentProject.startDate.toString(finnishDateFormatter)))
    }
  }

  def checkReservedPartInProject(number: Long, part: Long, currentProject: Project, roadAddressChangeType: RoadAddressChangeType): Unit = {
    if (RoadAddressChangeType.Transfer.value == roadAddressChangeType.value && roadAddressService.getRoadAddressesFiltered(number, part).nonEmpty && !currentProject.formedParts.map(fp => (fp.roadNumber, fp.roadPartNumber)).contains((number, part))) {
      val partInCurrentProject = projectReservedPartDAO.fetchProjectReservedPart(number, part, currentProject.id, withProjectId = Some(true))
      if (partInCurrentProject.isEmpty) {
        throw new ProjectValidationException(RoadNotAvailableMessage.format(number, part, currentProject.name))
      }
    }
  }

  def checkReservedPartInOtherProject(number: Long, part: Long, currentProject: Project): Unit = {
    val projectsWithPart = projectReservedPartDAO.fetchProjectReservedPart(number, part, currentProject.id, withProjectId = Some(false))
    if (projectsWithPart.nonEmpty) {
      throw new ProjectValidationException(RoadReservedOtherProjectMessage.format(number, part, currentProject.name))
    }
  }

  // Utility method, will return correct GeometryEndpoint
  private def endPoint(b: BaseRoadAddress) = {
    b.sideCode match {
      case SideCode.TowardsDigitizing => b.geometry.last
      case SideCode.AgainstDigitizing => b.geometry.head
      case _ => Point(0.0, 0.0)
    }
  }

  sealed trait ValidationError {
    def value: Int

    def message: String

    def notification: Boolean

    def priority: Int = 9
  }

  object ValidationErrorList {
    val values: Set[ValidationError] = Set(MinorDiscontinuityFound, DiscontinuousFound, InsufficientTrackCoverage, DiscontinuousAddressScheme,
      SharedLinkIdsExist, NoContinuityCodesAtEnd, UnsuccessfulRecalculation, MissingEndOfRoad, HasNotHandledLinks, ConnectedDiscontinuousLink,
      IncompatibleDiscontinuityCodes, EndOfRoadNotOnLastPart, ElyCodeChangeDetected, DiscontinuityOnRamp, DiscontinuityInsideRoadPart,
      DiscontinuousCodeOnConnectedRoadPartOutside, NotDiscontinuousCodeOnDisconnectedRoadPartOutside, ElyDiscontinuityCodeBeforeProjectButNoElyChange,
      WrongDiscontinuityBeforeProjectWithElyChangeInProject,
      ErrorInValidationOfUnchangedLinks, RoadNotEndingInElyBorder, RoadContinuesInAnotherEly,
      MultipleElyInPart, IncorrectOperationTypeOnElyCodeChange,
      ElyCodeChangeButNoRoadPartChange, ElyCodeChangeButNoElyChange, ElyCodeChangeButNotOnEnd, ElyCodeDiscontinuityChangeButNoElyChange, RoadNotReserved, DistinctAdministrativeClassesBetweenTracks, WrongDiscontinuityOutsideOfProject,
      TrackGeometryLengthDeviation, UniformAdminClassOnLink)

    // Viite-942
    case object MissingEndOfRoad extends ValidationError {
      def value = 0

      def message: String = MissingEndOfRoadMessage

      def notification = false
    }

    // Viite-453
    // There must be a minor discontinuity if the jump is longer than 0.1 m (10 cm) between road links
    case object MinorDiscontinuityFound extends ValidationError {
      def value = 1

      def message: String = MinorDiscontinuityFoundMessage

      def notification = true
    }

    // Viite-453
    // There must be a discontinuity if the jump is longer than 50 meters or roadPart is changed
    case object DiscontinuousFound extends ValidationError {
      def value = 2

      def message: String = DiscontinuousFoundMessage

      def notification = false
    }

    // Viite-453
    // For every track 1 there must exist track 2 that covers the same address span and vice versa
    case object InsufficientTrackCoverage extends ValidationError {
      def value = 3

      def message: String = InsufficientTrackCoverageMessage

      def notification = false

      override def priority = 2
    }

    // Viite-453
    // There must be a continuous road addressing scheme so that all values from 0 to the highest number are covered
    case object DiscontinuousAddressScheme extends ValidationError {
      def value = 4

      def message: String = DiscontinuousAddressSchemeMessage

      def notification = false
    }

    // Viite-453
    // There are no link ids shared between the project and the current road address tables at the project date (start_date, end_date)
    case object SharedLinkIdsExist extends ValidationError {
      def value = 5

      def message: String = SharedLinkIdsExistMessage

      def notification = false
    }

    // Viite-453
    // Continuity codes are given for end of road
    case object NoContinuityCodesAtEnd extends ValidationError {
      def value = 6

      def message: String = NoContinuityCodesAtEndMessage

      def notification = false
    }

    // Viite-453
    // Recalculation of M values and delta calculation are both unsuccessful for every road part in project
    case object UnsuccessfulRecalculation extends ValidationError {
      def value = 7

      def message: String = UnsuccessfulRecalculationMessage

      def notification = false
    }

    case object HasNotHandledLinks extends ValidationError {
      def value = 8

      def message: String = ""

      def notification = false

      override def priority = 1
    }

    case object ConnectedDiscontinuousLink extends ValidationError {
      def value = 9

      def message: String = ConnectedDiscontinuousMessage

      def notification = false
    }

    case object IncompatibleDiscontinuityCodes extends ValidationError {
      def value = 10

      def message: String = DifferingDiscontinuityCodesForTracks

      def notification = false
    }

    case object EndOfRoadNotOnLastPart extends ValidationError {
      def value = 11

      def message: String = EndOfRoadNotOnLastPartMessage

      def notification = false
    }

    case object ElyCodeChangeDetected extends ValidationError {
      def value = 12

      def message: String = ElyCodeChangeNotPresent

      def notification = false
    }

    case object DiscontinuityOnRamp extends ValidationError {
      def value = 13

      def message: String = RampDiscontinuityFoundMessage

      def notification = true
    }

    // Viite-473
    // Unchanged project links cannot have any other operation (Transfer, Termination, Renumbering) previously on the same number and part
    case object ErrorInValidationOfUnchangedLinks extends ValidationError {
      def value = 14

      def message: String = ErrorInValidationOfUnchangedLinksMessage

      def notification = false
    }

    case object RoadConnectingRoundabout extends ValidationError {
      def value = 15

      def message: String = MinorDiscontinuousWhenRoadConnectingRoundabout

      def notification = false
    }

    case object RoadNotEndingInElyBorder extends ValidationError {
      def value = 16

      def message: String = RoadNotEndingInElyBorderMessage

      def notification = true
    }

    case object RoadContinuesInAnotherEly extends ValidationError {
      def value = 17

      def message: String = RoadContinuesInAnotherElyMessage

      def notification = true
    }

    case object TerminationContinuity extends ValidationError {
      def value = 18

      def message: String = WrongDiscontinuityWhenAdjacentToTerminatedRoad

      def notification = true
    }

    case object DoubleEndOfRoad extends ValidationError {
      def value = 19

      def message: String = DoubleEndOfRoadMessage

      def notification = true
    }

    case object EndOfRoadMiddleOfPart extends ValidationError {
      def value = 20

      def message: String = EndOfRoadMiddleOfPartMessage

      def notification = true
    }

    case object MultipleElyInPart extends ValidationError {
      def value = 21

      def message: String = MultipleElysInPartMessage

      def notification = true
    }

    case object IncorrectOperationTypeOnElyCodeChange extends ValidationError {
      def value = 22

      def message: String = IncorrectOperationTypeOnElyCodeChangeMessage

      def notification = true
    }

    case object ElyCodeChangeButNoRoadPartChange extends ValidationError {
      def value = 23

      def message: String = ElyCodeChangeButNoRoadPartChangeMessage

      def notification = true
    }

    case object ElyCodeChangeButNoElyChange extends ValidationError {
      def value = 24

      def message: String = ElyCodeChangeButNoElyChangeMessage

      def notification = true
    }

    case object ElyCodeChangeButNotOnEnd extends ValidationError {
      def value = 25

      def message: String = ElyCodeChangeButNotOnEndMessage

      def notification = true
    }

    case object ElyCodeDiscontinuityChangeButNoElyChange extends ValidationError {
      def value = 26

      def message: String = ElyCodeDiscontinuityChangeButNoElyChangeMessage

      def notification = true
    }

    case object RoadNotReserved extends ValidationError {
      def value = 27

      def message: String = RoadNotReservedMessage

      def notification = true
    }

    case object DiscontinuityInsideRoadPart extends ValidationError {
      def value = 28

      def message: String = DiscontinuityInsideRoadPartMessage

      def notification = true
    }

    case object DistinctAdministrativeClassesBetweenTracks extends ValidationError {
      def value = 29

      def message: String = DistinctAdministrativeClassesBetweenTracksMessage

      def notification = true
    }

    //VIITE-2722
    case object UnpairedElyCodeChange extends ValidationError {
      def value = 32

      def message: String = UnpairedElyCodeChangeMessage

      def notification = true
    }

    //VIITE-2675
    case object DiscontinuousCodeOnConnectedRoadPartOutside extends ValidationError {
      def value = 33

      def message: String = DiscontinuousCodeOnConnectedRoadPartOutsideMessage

      def notification = false
    }

    case object NotDiscontinuousCodeOnDisconnectedRoadPartOutside extends ValidationError {
      def value = 34

      def message: String = NotDiscontinuousCodeOnDisconnectedRoadPartOutsideMessage

      def notification = false
    }

    case object ElyDiscontinuityCodeBeforeProjectButNoElyChange extends ValidationError {
      def value = 35

      def message: String = ElyDiscontinuityCodeBeforeProjectButNoElyChangeMessage

      def notification = false
    }

    case object WrongDiscontinuityBeforeProjectWithElyChangeInProject extends ValidationError {
      def value = 36

      def message: String = WrongDiscontinuityBeforeProjectWithElyChangeInProjectMessage

      def notification = false
    }

    case object WrongDiscontinuityOutsideOfProject extends ValidationError {
      def value = 37

      def message: String = WrongDiscontinuityOutsideOfProjectMessage

      def notification = false
    }

    // Viite-2714
    case object NoReverse extends ValidationError {
      def value = 99

      def message: String = NoReverseErrorMessage

      def notification = true
    }

    // Viite-1576
    case object TrackGeometryLengthDeviation extends ValidationError {
      def value = 38

      def message: String = geomLengthDifferenceBetweenTracks

      def notification = false
    }
    
    case object UniformAdminClassOnLink extends ValidationError {
      def value = 39

      def message: String = UniformAdminClassOnLinkMessage

      def notification = true
    }
    
    def apply(intValue: Int): ValidationError = {
      values.find(_.value == intValue).get
    }
  }

  /**
   * @param affectedPlIds Id's of the project links affected by the validation error.
   * @param affectedLinkIds linkId's of the project links affected by the validation error.
   * @param coordinates the coordinates where to zoom when the validation error is clicked on the UI.
   * */
  case class ValidationErrorDetails(projectId: Long, validationError: ValidationError,
                                    affectedPlIds: Seq[Long], affectedLinkIds: Seq[String], coordinates: Seq[ProjectCoordinates],
                                    optionalInformation: Option[String])

  /** Validates project links. If high priority validation errors (to be tackled first) exists, only they are returned.
   * Else the normal priority validation errors get returned (if any). */
  def validateProject(project: Project, projectLinks: Seq[ProjectLink]): Seq[ValidationErrorDetails] = {
    time(logger, "Validating project") {
      projectLinksHighPriorityValidation(project, projectLinks) match {
        case e if e.nonEmpty => e   // return high priority validation errors only, if any
        case _ => projectLinksNormalPriorityValidation(project, projectLinks)   // otherwise, get other validations
      }
    }
  }

  /** Returns the high priority validation errors found within projectLinks (i.e. those that should be addressed
   *  first by the user, before any other possible errors). */
  def projectLinksHighPriorityValidation(project: Project, projectLinks: Seq[ProjectLink]): Seq[ValidationErrorDetails] = {

    // function to get any (high priority) validation errors for the project links
    val highPriorityValidations: Seq[(Project, Seq[ProjectLink]) => Seq[ValidationErrorDetails]] = Seq(
      // sequence of validator functions, in INCREASING priority order (as these get turned around in the next step)
      checkForNotHandledLinks // "Käsittelemätön" is returned as highest priority
    )

    // list all the (high priority) validation errors found within the project links, in reversed order
    val errors: Seq[ValidationErrorDetails] = highPriorityValidations.foldLeft(Seq.empty[ValidationErrorDetails]) { case (errors, validation) =>
      validation(project, projectLinks) ++ errors
    }
    errors.distinct   // return distinct high priority validation errors
  }

  /** Returns the normal priority validation errors found within projectLinks that should be addressed by the user. */
  def projectLinksNormalPriorityValidation(project: Project, projectLinks: Seq[ProjectLink]): Seq[ValidationErrorDetails] = {

    // function to get any (normal priority) validation errors for the project links
    val normalPriorityValidations: Seq[(Project, Seq[ProjectLink]) => Seq[ValidationErrorDetails]] = Seq(
      // sequence of validator functions, in INCREASING priority order (as these get turned around in the next step)
      checkNoReverseInProject,
      checkUniformAdminClassOnLink,
      checkProjectElyCodes,
      checkDiscontinuityOnPreviousRoadPart,
      checkProjectContinuity,
      checkForInvalidUnchangedLinks,
      checkTrackCodePairing,
      checkActionsInRoadsNotInProject
    )

    // lists all the (normal priority) validation errors found within the project links
    val errors: Seq[ValidationErrorDetails] = normalPriorityValidations.foldLeft(Seq.empty[ValidationErrorDetails]) { case (errors, validation) =>
      validation(project, projectLinks) ++ errors
    }
    errors.distinct   // return distinct normal priority validation errors
  }

  def error(id: Long, validationError: ValidationError, info: String = "N/A")(pl: Seq[ProjectLink]): Option[ValidationErrorDetails] = {
    val (splitLinks, nonSplitLinks) = pl.partition(_.isSplit)
    val splitIds = splitLinks.flatMap(s => Seq(s.connectedLinkId.get, s.linkId))
    val connectedSplitLinks = projectLinkDAO.fetchProjectLinksByConnectedLinkId(splitIds)
    val (plIds, points) = (nonSplitLinks ++ connectedSplitLinks).map(pl => (pl.id, GeometryUtils.midPointGeometry(pl.geometry))).unzip
    val linkIds = (nonSplitLinks ++ connectedSplitLinks).map(pl => pl.linkId)
    if (plIds.nonEmpty) {
      Some(ValidationErrorDetails(id, validationError, plIds, linkIds,
        points.map(p => ProjectCoordinates(p.x, p.y, defaultZoomlevel)), Some(info)))
    } else {
      None
    }
  }

  def outsideOfProjectError(id: Long, validationError: ValidationError)(pl: Seq[BaseRoadAddress]): Option[ValidationErrorDetails] = {
    val (ids, points) = pl.map(pl => (pl.id, GeometryUtils.midPointGeometry(pl.geometry))).unzip
    val linkIds = pl.map(pl => pl.linkId)
    if (ids.nonEmpty)
      Some(ValidationErrorDetails(id, validationError, ids, linkIds,
        points.map(p => ProjectCoordinates(p.x, p.y, defaultZoomlevel)), None))
    else
      None
  }

  def checkProjectContinuity(project: Project, projectLinks: Seq[ProjectLink]): Seq[ValidationErrorDetails] = {
    projectLinks.filter(_.status != RoadAddressChangeType.Termination).groupBy(pl => (pl.roadNumber, pl.roadPartNumber)).flatMap {
      case ((road, _), seq) =>
        if (road < RampsMinBound || road > RampsMaxBound) {
          checkRoadContinuityCodes(project, seq)
        } else {
          checkRoadContinuityCodes(project, seq, isRampValidation = true)
        }
      case _ => Seq()
    }.toSeq
  }

  def checkForNotHandledLinks(project: Project, projectLinks: Seq[ProjectLink]): Seq[ValidationErrorDetails] = {
    val notHandled = projectLinks.filter(_.status == RoadAddressChangeType.NotHandled)
    notHandled.groupBy(link => (link.roadNumber, link.roadPartNumber)).foldLeft(Seq.empty[ValidationErrorDetails])((errorDetails, road) =>
      errorDetails :+ ValidationErrorDetails(project.id, ValidationErrorList.HasNotHandledLinks,
        road._2.map(pl => pl.id), road._2.map(pl => pl.linkId),  road._2.map { pl =>
          val point = GeometryUtils.midPointGeometry(pl.geometry)
          ProjectCoordinates(point.x, point.y, 12)
        },
        Some(HasNotHandledLinksMessage.format(road._2.size, road._1._1, road._1._2)))
    )
  }

//  def checkForInvalidUnchangedLinks(project: Project, projectLinks: Seq[ProjectLink]): Seq[ValidationErrorDetails] = {
//    val invalidUnchangedLinks: Seq[ProjectLink] = projectLinks.groupBy(s => (s.roadNumber, s.roadPartNumber)).flatMap { g =>
//      val (unchanged, others) = g._2.partition(_.status == UnChanged)
//      //foreach number and part and foreach UnChanged found in that group, we will check if there is some link in some other different action, that is connected by geometry and addressM values to the UnChanged link starting point
//      unchanged.groupBy(_.track) // Check per track
//        .flatMap(upl => upl._2
//          .filter(u => others.filterNot(opl => opl.status == RoadAddressChangeType.NotHandled || opl.track != upl._1)
//            .exists(o => u.startAddrMValue >= o.startAddrMValue)))
//    }.toSeq
//
//    if (invalidUnchangedLinks.nonEmpty) {
//      Seq(error(project.id, ValidationErrorList.ErrorInValidationOfUnchangedLinks)(invalidUnchangedLinks).get)
//    } else {
//      Seq()
//    }
//  }


  /** The Unchanged status is allowed only for the links whose address information does not change within the project.
    * Any operation, other than Unchanged ("Ennallaan"), applied to the link, changes or may change the addresses of the links following the operation.
    * Thus, the links following the first other operation must not be allowed to have "Unchanged" status, but is returned with a validation error.
    */
  def checkForInvalidUnchangedLinks(project: Project, projectLinks: Seq[ProjectLink]): Seq[ValidationErrorDetails] = {
    def findInvalidUnchangedLinks(pls: Seq[ProjectLink]): Seq[ProjectLink] = {
      var errorLinkList: Seq[ProjectLink]  = Seq()
        val unChangedPls = pls.filter(_.status == RoadAddressChangeType.Unchanged)
        val newPls       = pls.filter(_.isNotCalculated)
          // Check any new links behind UnChanged and filter them as invalid
        errorLinkList ++= unChangedPls.flatMap(ucpl => newPls.map(newpl => (ucpl.startingPoint.connected(newpl.startingPoint) || ucpl.startingPoint.connected(newpl.endPoint), newpl))).filter(_._1 == true).map(_._2)

        // sort groups' project links by original startAddrMValue
        val sortedProjectLinks = pls.filterNot(_.isNotCalculated).sortWith(_.originalStartAddrMValue < _.originalStartAddrMValue)
        // from the sorted project link list, find the first link that has a status of other than Unchanged
        val firstOtherStatus = sortedProjectLinks.find(pl => pl.status != RoadAddressChangeType.Unchanged)
        if (firstOtherStatus.nonEmpty) {
          val limitAddrMValue = {
            if (firstOtherStatus.get.status == RoadAddressChangeType.New)
              firstOtherStatus.get.startAddrMValue // for New links we take the normal startAddrMValue
            else
              firstOtherStatus.get.originalStartAddrMValue // for anything else we take the originalStartAddrMValue
          }
          // from the sorted project link list, find all Unchanged links that start after or at the same value as the limitAddrMValue -> these are the invalid Unchanged links
          errorLinkList ++ sortedProjectLinks.filter(pl => pl.status == RoadAddressChangeType.Unchanged && pl.originalStartAddrMValue >= limitAddrMValue)
        } else
          errorLinkList
    }
    // group project links by roadNumber and roadPart number
    val groupedByRoadNumberAndPart = projectLinks.groupBy(pl => (pl.originalRoadNumber, pl.originalRoadPartNumber))
    val invalidUnchangedLinks = {
      groupedByRoadNumberAndPart.flatMap { group =>
        (findInvalidUnchangedLinks(group._2.filter(_.track != Track.LeftSide)) ++
          findInvalidUnchangedLinks(group._2.filter(_.track != Track.RightSide))).toSet
      }.toSeq
    }

    if (invalidUnchangedLinks.nonEmpty) {
      Seq(error(project.id, ValidationErrorList.ErrorInValidationOfUnchangedLinks)(invalidUnchangedLinks).get)
    } else {
      Seq()
    }
  }

  def isSameTrack(previous: ProjectLink, currentLink: ProjectLink): Boolean = {
    previous.track == currentLink.track && previous.endAddrMValue == currentLink.startAddrMValue
  }

  def getTrackInterval(links: Seq[ProjectLink], track: Track): Seq[ProjectLink] = {
    links.foldLeft(Seq.empty[ProjectLink]) { (linkSameTrack, current) => {
      if (current.track == track && (linkSameTrack.isEmpty || isSameTrack(linkSameTrack.last, current))) {
        linkSameTrack :+ current
      } else {
        linkSameTrack
      }
    }
    }.sortBy(_.startAddrMValue)
  }

  def checkTrackCodePairing(project: Project, projectLinks: Seq[ProjectLink]): Seq[ValidationErrorDetails] = {

    val notCombinedLinks = projectLinks.filterNot(_.track == Track.Combined)
    def checkMinMaxTrack(trackInterval: Seq[ProjectLink]): Option[ProjectLink] = {
      if (trackInterval.head.track != Track.Combined) {
        val minTrackLink = trackInterval.minBy(_.startAddrMValue)
        val maxTrackLink = trackInterval.maxBy(_.endAddrMValue)
        val notCombinedNonTerminatedLinksInRoadPart = notCombinedLinks.filter(l => l.roadNumber == minTrackLink.roadNumber && l.roadPartNumber == minTrackLink.roadPartNumber && l.status != RoadAddressChangeType.Termination)
        if (!notCombinedNonTerminatedLinksInRoadPart.exists(l => l.startAddrMValue == minTrackLink.startAddrMValue && l.track != minTrackLink.track)) {
          Some(minTrackLink)
        }
        else if (!notCombinedNonTerminatedLinksInRoadPart.exists(l => l.endAddrMValue == maxTrackLink.endAddrMValue && l.track != maxTrackLink.track)) {
          Some(maxTrackLink)
        } else None
      } else None
    }

    def checkMinMaxTrackAdministrativeClasses(trackInterval: Seq[ProjectLink]): Option[Seq[ProjectLink]] = {
        val diffLinks = trackInterval.groupBy(_.administrativeClass).flatMap { projectLinksByAdministrativeClass: (AdministrativeClass, Seq[ProjectLink]) =>
          projectLinksByAdministrativeClass._2.partition(_.track == Track.LeftSide) match {
          case (left, right) if left.nonEmpty && right.nonEmpty =>
                val leftSection      = (projectLinksByAdministrativeClass._1, left.minBy(_.startAddrMValue).startAddrMValue, left.maxBy(_.endAddrMValue).endAddrMValue)
                val rightSection     = (projectLinksByAdministrativeClass._1, right.minBy(_.startAddrMValue).startAddrMValue, right.maxBy(_.endAddrMValue).endAddrMValue)
                val startSectionAdrr = Seq(leftSection._2, rightSection._2).max
                val endSectionAddr   = Seq(leftSection._3, rightSection._3).min
                if (leftSection != rightSection) {
                  val criticalLeftLinks  = left.filterNot(link => {link.startAddrMValue >= startSectionAdrr && link.endAddrMValue <= endSectionAddr}).filterNot(link => {right.map(_.startAddrMValue).contains(link.startAddrMValue) || right.map(_.endAddrMValue).contains(link.endAddrMValue)})
                  val criticalRightLinks = right.filterNot(link => {link.startAddrMValue >= startSectionAdrr && link.endAddrMValue <= endSectionAddr}).filterNot(link => {left.map(_.startAddrMValue).contains(link.startAddrMValue) || left.map(_.endAddrMValue).contains(link.endAddrMValue)})
                  criticalLeftLinks ++ criticalRightLinks
                } else Seq.empty[ProjectLink]
            case (left, right) if left.isEmpty || right.isEmpty => left ++ right
          }
        }.toSeq
        if (diffLinks.nonEmpty) Some(diffLinks) else None
    }

    def validateTrackTopology(trackInterval: Seq[ProjectLink]): Seq[ProjectLink] = {
      val validTrackInterval = trackInterval.filterNot(_.status == RoadAddressChangeType.Termination)
      if (validTrackInterval.nonEmpty) {
        checkMinMaxTrack(validTrackInterval) match {
          case Some(link) => Seq(link)
          case None => if (validTrackInterval.size > 1) {
            validTrackInterval.sliding(2).map { case Seq(first, second) =>
              if (first.endAddrMValue != second.startAddrMValue && first.id != second.id) {
                Some(first)
              } else None
            }.toSeq.flatten
          } else Seq.empty[ProjectLink]
        }
      } else Seq.empty[ProjectLink]
    }

    def validateTrackAdministrativeClasses(groupInterval: Seq[(Long, Seq[ProjectLink])]): Seq[ProjectLink] = {
      groupInterval.groupBy(_._1).flatMap{ interval =>
        val leftrRightTracks = interval._2.flatMap(_._2)
        val validTrackInterval = leftrRightTracks.filterNot(r => r.status == RoadAddressChangeType.Termination || r.track == Track.Combined)
        val intervalHasBothTracks = validTrackInterval.exists(_.track == Track.RightSide) && validTrackInterval.exists(_.track == Track.LeftSide)
        if (intervalHasBothTracks) {
          checkMinMaxTrackAdministrativeClasses(validTrackInterval) match {
            case Some(links) => links
            case _ => Seq.empty[ProjectLink]
          }
        } else Seq.empty[ProjectLink]
      }.toSeq
    }

    @tailrec
    def recursiveCheckTrackChange(links: Seq[ProjectLink], errorLinks: Seq[ProjectLink] = Seq()): Option[ValidationErrorDetails] = {
      if (links.isEmpty) {
        error(project.id, ValidationErrorList.InsufficientTrackCoverage)(errorLinks)
      } else {
        val trackToCheck = links.head.track
        val trackInterval = getTrackInterval(links.sortBy(o => (o.roadNumber, o.roadPartNumber, o.track.value, o.startAddrMValue)), trackToCheck)
        recursiveCheckTrackChange(links.filterNot(l => trackInterval.exists(lt => lt.id == l.id)),
          errorLinks ++ validateTrackTopology(trackInterval))
      }
    }

    @tailrec
    def getTwoTrackInterval(links: Seq[ProjectLink], interval: Seq[(Long, Seq[ProjectLink])]): Seq[(Long, Seq[ProjectLink])] = {
      if (links.isEmpty || links.exists(_.isNotCalculated)) {
        interval
      } else {
        val trackToCheck  = links.head.track
        val trackInterval = getTrackInterval(links.sortBy(o => (o.roadNumber, o.roadPartNumber, o.track.value, o.startAddrMValue)), trackToCheck)
        val headerAddr    = trackInterval.minBy(_.startAddrMValue).startAddrMValue
        getTwoTrackInterval(links.filterNot(l => trackInterval.exists(lt => lt.id == l.id)), interval ++ Seq(headerAddr -> trackInterval.filterNot(_.track == Track.Combined)))
      }
    }

    def checkTrackAdministrativeClass(links: Seq[ProjectLink]): Option[ValidationErrorDetails] = {
      val trackIntervals = getTwoTrackInterval(links, Seq())
      val errorLinks = validateTrackAdministrativeClasses(trackIntervals)
      error(project.id, ValidationErrorList.DistinctAdministrativeClassesBetweenTracks)(errorLinks)
    }

    @tailrec
    def recursiveCheckTwoTrackGeometryLength(roadPartLinks: Seq[ProjectLink],
                                             errorLinks   : Seq[ProjectLink] = Seq()
                                            ): Option[ValidationErrorDetails] = {
      if (roadPartLinks.isEmpty) {
        error(project.id, ValidationErrorList.TrackGeometryLengthDeviation)(errorLinks)
      } else {
        val sortedRoadPartLinks  = roadPartLinks.sortBy(pl => (pl.track.value, pl.startAddrMValue))
        val sortedTrackInterval1 = getTrackInterval(sortedRoadPartLinks, Track.LeftSide)
        val sortedTrackInterval2 = getTrackInterval(sortedRoadPartLinks, Track.RightSide)

        val leftGeomLength = sortedTrackInterval1.map(_.geometryLength).sum
        val rightGeomLength = sortedTrackInterval2.map(_.geometryLength).sum

        def geomLengthDiff: Double = Math.abs(leftGeomLength - rightGeomLength)
        def exceptionalLengthDifference: Boolean = {
          val BIG_TRACK_LENGTH_DIFFERENCE__THRESHOLD_IN_METERS = 50
          val BIG_TRACK_LENGTH_DIFFERENCE__THRESHOLD_IN_PERCENT = 0.2

          geomLengthDiff > BIG_TRACK_LENGTH_DIFFERENCE__THRESHOLD_IN_METERS ||
          geomLengthDiff / leftGeomLength > BIG_TRACK_LENGTH_DIFFERENCE__THRESHOLD_IN_PERCENT ||
          geomLengthDiff / rightGeomLength > BIG_TRACK_LENGTH_DIFFERENCE__THRESHOLD_IN_PERCENT
        }

        val trackIntervalRemovedRoadPartLinks = roadPartLinks.filterNot(l => (sortedTrackInterval1 ++ sortedTrackInterval2).exists(lt => lt.id == l.id))
        val exceptionalLengthLinks = if (exceptionalLengthDifference) sortedTrackInterval1 ++ sortedTrackInterval2 else Seq()

        recursiveCheckTwoTrackGeometryLength(
          trackIntervalRemovedRoadPartLinks,
          errorLinks ++ exceptionalLengthLinks
        )
      }
    }

    val groupedLinks = notCombinedLinks.filterNot(_.status == RoadAddressChangeType.Termination).groupBy(pl => (pl.roadNumber, pl.roadPartNumber))
    groupedLinks.flatMap(roadPart => {
      val trackCoverageErrors = recursiveCheckTrackChange(roadPart._2) match {
        case Some(errors) => Seq(errors)
        case _ => Seq()
      }

      val trackGeomLengthErrors = if (trackCoverageErrors.isEmpty)
        recursiveCheckTwoTrackGeometryLength(roadPart._2) match {
          case Some(errors) => Seq(errors)
          case _ => Seq()
        }
        else Seq()

      val administrativeClassPairingErrors = checkTrackAdministrativeClass(roadPart._2) match {
        case Some(errors) => Seq(errors)
        case _ => Seq()
      }

      trackCoverageErrors ++ trackGeomLengthErrors ++ administrativeClassPairingErrors
    }).toSeq
  }

  def checkActionsInRoadsNotInProject(project: Project, projectLinks: Seq[ProjectLink]): Seq[ValidationErrorDetails] = {
    val roadAddressChangeTypes = List(RoadAddressChangeType.Transfer, RoadAddressChangeType.Renumeration)
    val operationsOutsideProject: Seq[Roadway] = (project.reservedParts++project.formedParts).flatMap(r =>
      roadwayDAO.fetchAllByRoadAndPart(r.roadNumber, r.roadPartNumber)).filterNot(
      l => projectLinks.exists(r => r.roadAddressRoadNumber.nonEmpty && r.roadAddressRoadNumber.get == l.roadNumber && r.roadAddressRoadPart.nonEmpty && r.roadAddressRoadPart.get == l.roadPartNumber)
    )
    val erroredProjectLinks = projectLinks.filter(pl => roadAddressChangeTypes.contains(pl.status) && operationsOutsideProject.exists(out => out.roadNumber == pl.roadNumber && out.roadPartNumber == pl.roadPartNumber)).groupBy(pl => (pl.roadNumber, pl.roadPartNumber))
    if (erroredProjectLinks.nonEmpty) {
      erroredProjectLinks.flatMap{ l =>
        Seq(ValidationErrorDetails(project.id, alterShortMessage(ValidationErrorList.RoadNotReserved, currentRoadAndPart = Some(Seq((l._2.head.roadNumber, l._2.head.roadPartNumber))))
          , Seq(l._2.map(_.id)).flatten, Seq(l._2.map(_.linkId)).flatten, l._2.map{ pl =>
            val point = GeometryUtils.midPointGeometry(pl.geometry)
            ProjectCoordinates(point.x, point.y, 12)
          }, None))
      }.toSeq
    } else {
      Seq()
    }
  }

  /**
   * Validations for the discontinuity code of the road part that precedes a reserved road part.
   */
  def checkDiscontinuityOnPreviousRoadPart(project: Project, allProjectLinks: Seq[ProjectLink]): Seq[ValidationErrorDetails] = {

    def validateDiscontinuityBetweenTwoRoadAddresses(previousRoadAddress: Seq[BaseRoadAddress], nextRoadAddress: Seq[BaseRoadAddress]): Seq[ValidationErrorDetails] = {
      val prevLeftRoadAddress = Seq(previousRoadAddress.filter(_.track != Track.RightSide).maxBy(_.endAddrMValue))
      val prevRightRoadAddress = Seq(previousRoadAddress.filter(_.track != Track.LeftSide).maxBy(_.endAddrMValue))

      val leftRoadAddresses = nextRoadAddress.filter(_.track != Track.RightSide)
      val rightRoadAddresses = nextRoadAddress.filter(_.track != Track.LeftSide)

      val nextLeftRoadAddress = if (leftRoadAddresses.nonEmpty) {
        Seq(leftRoadAddresses.minBy(_.startAddrMValue))
      } else Seq.empty[BaseRoadAddress]
      val nextRightRoadAddress = if (rightRoadAddresses.nonEmpty) {
        Seq(rightRoadAddresses.minBy(_.startAddrMValue))
      }  else Seq.empty[BaseRoadAddress]

      /**
       * Runs the provided validation function against the RoadAddress before the start of the road part
       * reserved for current project.
       *
       * @param validationFunction to be run
       * @param error The ValidationError that is returned if validationFunction finds an invalid RoadAddress
       * @return If errors are found in the validation, returns an OutsideOfProjectError with ValidationError error
       */
      def validatePreviousRoadAddress(roadAddressList: Seq[BaseRoadAddress])(validationFunction: BaseRoadAddress => Boolean)(error: ValidationError): Seq[ValidationErrorDetails] = {
        val foundError = roadAddressList.filter(validationFunction)
        if (foundError.nonEmpty) outsideOfProjectError(
          project.id,
          alterMessage(error, currentRoadAndPart = Some(Seq((roadAddressList.head.roadNumber, roadAddressList.head.roadPartNumber))))
        )(foundError).toSeq else Seq()
      }

      /**
       * Validates that the previous RoadAddress has a Discontinuous Discontinuity code, if the RoadAddress
       * is disconnected from the start of the road part reserved to the project
       * This takes priority over other validations and is returned alone if a ValidationError is found.
       */
      def validateDiscontinuity(pl: Seq[BaseRoadAddress])(ra: BaseRoadAddress): Boolean = {
        if (pl.nonEmpty) {
          ra.discontinuity != Discontinuity.Discontinuous &&
            !ra.connected(pl.head)
        } else
          false
      }

      val notDiscontinuousCodeOnDisconnectedRoadAddressErrors = validatePreviousRoadAddress(prevLeftRoadAddress)(validateDiscontinuity(nextLeftRoadAddress)
      )(ValidationErrorList.NotDiscontinuousCodeOnDisconnectedRoadPartOutside) ++
        validatePreviousRoadAddress(prevRightRoadAddress)(validateDiscontinuity(nextRightRoadAddress)
        )(ValidationErrorList.NotDiscontinuousCodeOnDisconnectedRoadPartOutside)

      /**
       * VIITE-2780 If the roadpart outside of project (prevLeftRoadAddress/prevRightRoadaddress) doesn't have 2 tracks but the projectLink does,
       * give validation error only if both tracks have invalid continuity.
       *      (4)
       * (1) |--->-------->-------
       *     ^     (2)
       *     |
       * (3) |--->-------->-------
       *     ^
       *     |
       *     |
       * (1) End of the last roadpart that is not reserved for the project
       * (2) The two-track roadpart reserved for the project
       * (3) The right track of the roadpart would be validated as an error, as the previous roadpart is continuous,
       *     but the start point of the right track is not connected to the end point of the previous roadpart.
       *
       *     Because (4) does not give a validation error in this case, the continuity of the roadpart is valid
       *     and the validation error from (3) can be discarded.
       */
      val notDiscontinuousCodeOnDisconnectedRoadAddress = if (prevLeftRoadAddress.head.track == Track.Combined && nextLeftRoadAddress.head.track == Track.LeftSide
        && notDiscontinuousCodeOnDisconnectedRoadAddressErrors.length != 2)
        Seq()
      else notDiscontinuousCodeOnDisconnectedRoadAddressErrors


      /**
       * Validates that there isn't an end of road discontinuity on a road address before the road part reserved
       * in the project.
       */
      val endOfRoadOutsideOfProject = validatePreviousRoadAddress(prevLeftRoadAddress)(ra =>
        ra.discontinuity == Discontinuity.EndOfRoad
      )(ValidationErrorList.DoubleEndOfRoad)

      /**
       * Validates that the previous RoadAddress doesn't have a Discontinuous Discontinuity code, if the RoadAddress
       * is connected to the start of the road part reserved to the project
       */
      def validateContinuous(pl: Seq[BaseRoadAddress])(ra: BaseRoadAddress): Boolean = {
        ra.discontinuity == Discontinuity.Discontinuous &&
          ra.connected(pl.head)
      }
      val discontinuousCodeOnConnectedRoadAddress = validatePreviousRoadAddress(prevLeftRoadAddress)(validateContinuous(nextLeftRoadAddress)
      )(ValidationErrorList.DiscontinuousCodeOnConnectedRoadPartOutside) ++
        validatePreviousRoadAddress(prevRightRoadAddress)(validateContinuous(nextRightRoadAddress)
        )(ValidationErrorList.DiscontinuousCodeOnConnectedRoadPartOutside)


      /**
       * Validates that the ELY has changed, if the previous RoadAddress has ElyCodeChange Discontinuity
       */
      val elyCodeChangeButSameElyNumber = validatePreviousRoadAddress(prevLeftRoadAddress)(ra =>
        ra.discontinuity == Discontinuity.ChangingELYCode
          && ra.ely == nextRoadAddress.head.ely
      )(ValidationErrorList.ElyDiscontinuityCodeBeforeProjectButNoElyChange)

      /**
       * Validates that the previous RoadAddress has either Discontinuous or ChangingElyCode Discontinuity code,
       * if the ELY number of previous RoadAddress and road part reserved in the project aren't equal.
       */
      val wrongDiscontinuityWithElyChange = validatePreviousRoadAddress(prevLeftRoadAddress)(ra =>
        ra.ely != nextRoadAddress.head.ely
          && (ra.discontinuity != Discontinuity.Discontinuous && ra.discontinuity != Discontinuity.ChangingELYCode)
      )(ValidationErrorList.WrongDiscontinuityBeforeProjectWithElyChangeInProject)


      val errors = notDiscontinuousCodeOnDisconnectedRoadAddress ++
        endOfRoadOutsideOfProject ++ discontinuousCodeOnConnectedRoadAddress ++
        elyCodeChangeButSameElyNumber ++ wrongDiscontinuityWithElyChange
      if (errors.nonEmpty) errors.take(1) else Seq()
    }

    /**
     * Fetches the valid road parts for the roadNumber at the startDate of the [[Project]].
     * Returns the largest road part number that's less than roadPartNumber and the smallest road part number larger than RoadPartNumber.
     *
     * @return A tuple of the largest previous road part number and the smallest next road part number.
     */
    def getPreviousAndNextRoadParts(roadNumber: Long, roadPartNumber: Long): (Option[Long], Option[Long]) = {
      val validRoadPartsAtProjectStartDate = roadAddressService.getValidRoadAddressParts(roadNumber, project.startDate)
      //Value is None, if no such road part numbers exist
      val previousRoadPart = validRoadPartsAtProjectStartDate.filter(rp => rp < roadPartNumber && !project.isReserved(roadNumber, rp)).reduceOption(Ordering.Long.max)
      val nextRoadPart = validRoadPartsAtProjectStartDate.filter(rp => rp > roadPartNumber && !project.isReserved(roadNumber, rp)).reduceOption(Ordering.Long.min)
      (previousRoadPart, nextRoadPart)
    }

    /**
     * Validates the discontinuity of the end of a [[RoadAddress]] with the previous road part number from [[getPreviousAndNextRoadParts]].
     * The discontinuity of the previous [[RoadAddress]] is validated with a [[BaseRoadAddress]] from [[allProjectLinks]] or based on the next road part number given by [[getPreviousAndNextRoadParts]]
     *
     * @param part A road part either formed, terminated or completely transferred in the project
     */
    def validateTheEndOfPreviousRoadPart(roadNumber: Long, part: Long): Seq[ValidationErrorDetails] = {
      val (previousRoadPartNumber, nextRoadPartNumber) = getPreviousAndNextRoadParts(roadNumber, part)

      previousRoadPartNumber match {
        case Some(previousRoadPartNumber) =>
          val previousRoadwayRoadAddresses = roadAddressService.getRoadAddressWithRoadAndPart(roadNumber, previousRoadPartNumber, fetchOnlyEnd = true, newTransaction = false)
          //Check if there are non-terminated project links with the provided roadNumber and part. If none found, try to fetch a RoadAddress with nextRoadPartNumber
          val nextRoadAddress: Seq[BaseRoadAddress] = allProjectLinks.filter(pl => pl.roadNumber == roadNumber && pl.roadPartNumber == part && pl.status != RoadAddressChangeType.Termination) match {
            case roadPartInProject if roadPartInProject.nonEmpty => roadPartInProject
            case _ =>
              nextRoadPartNumber match {
                case Some(nextRoadPartNumber) =>
                  roadAddressService.getRoadAddressWithRoadAndPart(roadNumber, nextRoadPartNumber, newTransaction = false)
                case _ => Seq()
              }
          }
          //Do not validate if there are formed projectLinks with a roadPartNumber between the previousRoadPartNumber and the parameter part
          if (project.formedParts.exists(rp => rp.roadNumber == roadNumber && previousRoadPartNumber < rp.roadPartNumber && rp.roadPartNumber < part))
            Seq()
          else {
            if (previousRoadwayRoadAddresses.nonEmpty && nextRoadAddress.nonEmpty) {
              validateDiscontinuityBetweenTwoRoadAddresses(previousRoadwayRoadAddresses, nextRoadAddress)
            } else if (previousRoadwayRoadAddresses.nonEmpty && nextRoadAddress.isEmpty &&
              !project.formedParts.exists(formed => formed.roadNumber == roadNumber && formed.roadPartNumber > part) &&
              previousRoadwayRoadAddresses.maxBy(_.endAddrMValue).discontinuity != Discontinuity.EndOfRoad) {
              //The previousRoadwayRoadAddress is the last road part with the roadNumber road and needs to have EndOfRoad Discontinuity
              outsideOfProjectError(
                project.id,
                alterMessage(ValidationErrorList.TerminationContinuity, currentRoadAndPart = Some(Seq((roadNumber, previousRoadPartNumber))))
              )(previousRoadwayRoadAddresses).toSeq
            } else Seq()
          }
        case _ => Seq()
      }
    }

    val roadPartsInProject = allProjectLinks.map(pl => (pl.roadNumber, pl.roadPartNumber)).distinct.toList
    val previousRoadPartValidationErrors = roadPartsInProject.flatMap {
      case (road, part) => validateTheEndOfPreviousRoadPart(road, part)
    }

    //Identifies road parts reserved in project where each project link is transferred to another road part, as this requires continuity validation
    val roadPartsTransferredFromProject = allProjectLinks.groupBy(pl => (pl.originalRoadNumber, pl.originalRoadPartNumber)).filter {
      case ((originalRoad, _/*originalPart*/), pls) =>
        pls.forall(pl => (pl.status == RoadAddressChangeType.Transfer || pl.status == RoadAddressChangeType.Renumeration) && pl.roadNumber != pl.originalRoadNumber) &&
          !allProjectLinks.exists(pl => pl.roadNumber == originalRoad)
    }.keys

    val previousOriginalRoadPartValidationErrors = roadPartsTransferredFromProject.flatMap {
      case (transferredRoad, transferredPart) => validateTheEndOfPreviousRoadPart(transferredRoad, transferredPart)
    }

    (previousRoadPartValidationErrors ++ previousOriginalRoadPartValidationErrors).groupBy(_.affectedPlIds).flatMap {
      case (_/*linkIds*/, validationErrors) => Seq(validationErrors.head)
    }.toSeq
  }


  def checkNoReverseInProject(project: Project, allProjectLinks: Seq[ProjectLink]): Seq[ValidationErrorDetails] = {
    val reversedProjectLinks = allProjectLinks.filter(pl => pl.reversed)
    if (reversedProjectLinks.nonEmpty) {
      Seq(error(project.id, ValidationErrorList.NoReverse)(reversedProjectLinks).get)
    } else {
      Seq()
    }
  }
  def checkUniformAdminClassOnLink(project: Project, allProjectLinks: Seq[ProjectLink]): Seq[ValidationErrorDetails] = {
    val groupedProjectLinksByLinkId = allProjectLinks.groupBy(_.linkId).filter(_._2.size > 1)
    groupedProjectLinksByLinkId.mapValues(pls =>
      if (pls.forall(_.administrativeClass == pls.head.administrativeClass))
        Seq()
      else
        Seq(error(project.id, ValidationErrorList.UniformAdminClassOnLink)(pls).get)
    ).values.flatten.toSeq
  }

  def checkProjectElyCodes(project: Project, allProjectLinks: Seq[ProjectLink]): Seq[ValidationErrorDetails] = {

    def prepareCoordinates(links: Seq[ProjectLink]): Seq[ProjectCoordinates] = {
      links.map(p => {
        val middlePoint = GeometryUtils.midPointGeometry(p.geometry)
        ProjectCoordinates(middlePoint.x, middlePoint.y, defaultZoomlevel)
      })
    }

    @tailrec
    def recProjectGroupsEly(unprocessed: Map[(Long, Long), Seq[ProjectLink]], processed: Map[(Long, Long), Seq[ProjectLink]], acumulatedErrors: Seq[ValidationErrorDetails] = Seq.empty[ValidationErrorDetails]): Seq[ValidationErrorDetails] = {
      if (processed.isEmpty && unprocessed.nonEmpty) {
        recProjectGroupsEly(unprocessed.tail, Map(unprocessed.head))
      } else {
        if (unprocessed.isEmpty) {
          acumulatedErrors
        } else {
          val biggestPrevious = processed.head._2.maxBy(_.endAddrMValue)
          val lowestCurrent = unprocessed.head._2.minBy(_.startAddrMValue)
          if (biggestPrevious.ely != lowestCurrent.ely) {
            val lastLinkDoesNotHaveChangeOfEly = biggestPrevious.discontinuity != Discontinuity.ChangingELYCode && biggestPrevious.roadNumber == lowestCurrent.roadNumber
            val roadNumbersAreDifferent = !(lowestCurrent.roadNumber == biggestPrevious.roadNumber && lowestCurrent.roadPartNumber > biggestPrevious.roadPartNumber)

            val errors = (lastLinkDoesNotHaveChangeOfEly, roadNumbersAreDifferent) match {

              case (true, true) =>
                val projectLinksSameEly = Seq(biggestPrevious)
                val projectLinksSameRoadPartNumber = unprocessed.head._2.filter(p => p.roadPartNumber == biggestPrevious.roadPartNumber)
                val sameElyCoords = prepareCoordinates(projectLinksSameEly)
                val sameRoadPartNumberCoords = prepareCoordinates(projectLinksSameRoadPartNumber)
                Seq(ValidationErrorDetails(project.id, ValidationErrorList.ElyCodeChangeButNoElyChange, projectLinksSameEly.map(_.id), projectLinksSameEly.map(_.linkId), sameElyCoords, Option("")),
                  ValidationErrorDetails(project.id, ValidationErrorList.ElyCodeChangeButNoRoadPartChange, projectLinksSameRoadPartNumber.map(_.id), projectLinksSameRoadPartNumber.map(_.linkId), sameRoadPartNumberCoords, Option("")))
              case (true, false) =>
                val affectedProjectLinks = Seq(biggestPrevious)
                val coords = prepareCoordinates(affectedProjectLinks)
                Seq(ValidationErrorDetails(project.id, ValidationErrorList.ElyCodeChangeDetected, affectedProjectLinks.map(_.id), affectedProjectLinks.map(_.linkId), coords, Option("")))
              case (false, true) =>
                val affectedProjectLinks = unprocessed.head._2.filter(p => p.ely == biggestPrevious.ely)
                if (affectedProjectLinks.nonEmpty) {
                  val coords = prepareCoordinates(affectedProjectLinks)
                  Seq(ValidationErrorDetails(project.id, ValidationErrorList.ElyCodeChangeButNoElyChange, affectedProjectLinks.map(_.id), affectedProjectLinks.map(_.linkId), coords, Option("")))
                } else Seq.empty
              case (false, false) =>
                Seq.empty
            }
            recProjectGroupsEly(unprocessed.tail, Map(unprocessed.head) ++ processed, errors ++ acumulatedErrors)
          } else {
            if (biggestPrevious.discontinuity == Discontinuity.ChangingELYCode && biggestPrevious.roadNumber == lowestCurrent.roadNumber) {
              val affectedProjectLinks = Seq(biggestPrevious, lowestCurrent)
              val coords = prepareCoordinates(affectedProjectLinks)
              recProjectGroupsEly(unprocessed.tail, Map(unprocessed.head) ++ processed, acumulatedErrors ++ Seq(ValidationErrorDetails(project.id, alterMessage(ValidationErrorList.ElyCodeDiscontinuityChangeButNoElyChange, currentRoadAndPart = Some(Seq((biggestPrevious.roadNumber, biggestPrevious.roadPartNumber))), nextRoadAndPart = Some(Seq((lowestCurrent.roadNumber, lowestCurrent.roadPartNumber)))), affectedProjectLinks.map(_.id), affectedProjectLinks.map(_.linkId), coords, Option(biggestPrevious.roadNumber.toString))))
            } else {
              recProjectGroupsEly(unprocessed.tail, Map(unprocessed.head) ++ processed, acumulatedErrors)
            }

          }
        }
      }
    }

    /*
      * This will validate if a shift in ely code in all links of a certain part occurred and happened correctly.
      * To be correct, the change needs to:
      * A. have all links transition to a new ELY
      * B. all links must have the UnChanged Link status
      *
      * @param project             : Project - the project to evaluate
      * @param groupedProjectLinks : Map[(Long, Long), Seq[ProjectLink]) - the project links, grouped by road number and road part number
      * @return
      */
    def checkChangeOfEly(project: Project, groupedProjectLinks: Map[(Long, Long), Seq[ProjectLink]]): Seq[ValidationErrorDetails] = {

      def prepareValidationErrorDetails(condition: Either[Seq[Long], Seq[RoadAddressChangeType]]): ValidationErrorDetails = {
        val (wrongProjectLinks, validationError) = condition match {
          case Left(originalElys) =>
            if (originalElys.nonEmpty)
              (allProjectLinks.filterNot(_.ely == originalElys.head), ValidationErrorList.MultipleElyInPart)
            else {
              (allProjectLinks.groupBy(_.ely).map(_._2.maxBy(_.endAddrMValue)).toSeq, ValidationErrorList.MultipleElyInPart)
            }
          case Right(roadAddressChangeTypeSeq) =>
            (allProjectLinks.filterNot(pl => roadAddressChangeTypeSeq.contains(pl.status)), ValidationErrorList.IncorrectOperationTypeOnElyCodeChange)
        }

        val projectCoords = wrongProjectLinks.map(p => {
          val middlePoint = GeometryUtils.middlePoint(Seq(p.geometry))
          ProjectCoordinates(middlePoint.x, middlePoint.y, defaultZoomlevel)
        })
        ValidationErrorDetails(project.id, validationError, wrongProjectLinks.map(_.id), wrongProjectLinks.map(_.linkId), projectCoords, Option.empty[String])
      }

      def findNonLastLinkHasChangeOfEly(pls: Seq[ProjectLink]): Seq[ProjectLink] = {
        val endPointLinks = findChainEndpoints(pls.filter(_.track != Track.LeftSide)).values ++ findChainEndpoints(pls.filter(_.track != Track.RightSide)).values
        pls.diff(endPointLinks.toSeq).filter(_.discontinuity == Discontinuity.ChangingELYCode)
      }

      val validationErrors = groupedProjectLinks.flatMap(group => {
        //Fetch original roadway data
        val workableProjectLinks = allProjectLinks.filterNot(pl => pl.status == RoadAddressChangeType.NotHandled || pl.status == RoadAddressChangeType.Termination)
        val roadways = roadwayDAO.fetchAllByRoadwayNumbers(group._2.map(_.roadwayNumber).toSet)
        val nonLastLinkHasChangeOfEly = if (group._2.exists(_.isNotCalculated))
                                          findNonLastLinkHasChangeOfEly(group._2)
                                        else
                                          group._2.filter(p => p.discontinuity == Discontinuity.ChangingELYCode &&
                                                               p.endAddrMValue != group._2.maxBy(_.endAddrMValue).endAddrMValue)

        val twoTrackLinksWithChangeOfEly = group._2.filter(p => p.isCalculated &&
                                                                p.discontinuity == Discontinuity.ChangingELYCode &&
                                                                p.endAddrMValue == group._2.maxBy(_.endAddrMValue).endAddrMValue &&
                                                                p.track != Track.Combined)

        val tracksWithUnpairedChangeOfEly = twoTrackLinksWithChangeOfEly.flatMap(p => group._2.filter(q => q.endAddrMValue == p.endAddrMValue &&
          q.discontinuity != Discontinuity.ChangingELYCode)).distinct
        val originalElys = roadways.map(_.ely).distinct
        val projectLinkElys = group._2.map(_.ely).distinct

        val errors = if (originalElys.nonEmpty || (originalElys.isEmpty && (projectLinkElys.size > 1 || nonLastLinkHasChangeOfEly.nonEmpty || tracksWithUnpairedChangeOfEly.nonEmpty))) {

          val multi = if (projectLinkElys.size > 1) {
            Seq(prepareValidationErrorDetails(Left(originalElys)))
          }
          else Seq.empty

          val wrongStatusCode = if (!workableProjectLinks.forall(pl => pl.status == RoadAddressChangeType.Unchanged || pl.status == RoadAddressChangeType.Transfer || pl.status == RoadAddressChangeType.New || pl.status == RoadAddressChangeType.Renumeration) && !originalElys.equals(projectLinkElys)) {
            Seq(prepareValidationErrorDetails(Right(Seq(RoadAddressChangeType.Unchanged, RoadAddressChangeType.Transfer, RoadAddressChangeType.New, RoadAddressChangeType.Renumeration))))
          }
          else Seq.empty

          val changeElyCodeNotInFinish = if (nonLastLinkHasChangeOfEly.nonEmpty) {
            val coords = nonLastLinkHasChangeOfEly.map(p => {
              val middlePoint = GeometryUtils.midPointGeometry(p.geometry)
              ProjectCoordinates(middlePoint.x, middlePoint.y, defaultZoomlevel)
            })
            Seq(ValidationErrorDetails(project.id, ValidationErrorList.ElyCodeChangeButNotOnEnd, nonLastLinkHasChangeOfEly.map(_.id), nonLastLinkHasChangeOfEly.map(_.linkId), coords, Option.empty[String]))
          } else Seq.empty

          val changeElyCodeNotInBothTracks = if (tracksWithUnpairedChangeOfEly.nonEmpty) {
            val coords = tracksWithUnpairedChangeOfEly.map(p => {
              val middlePoint = GeometryUtils.midPointGeometry(p.geometry)
              ProjectCoordinates(middlePoint.x, middlePoint.y, defaultZoomlevel)
            })
            Seq(ValidationErrorDetails(project.id, ValidationErrorList.UnpairedElyCodeChange, tracksWithUnpairedChangeOfEly.map(_.id), tracksWithUnpairedChangeOfEly.map(_.linkId), coords, Option.empty[String]))
          } else Seq.empty

          multi ++ wrongStatusCode ++ changeElyCodeNotInFinish ++ changeElyCodeNotInBothTracks
        } else Seq.empty
        errors
      })

      val (notCalculatedParts, calculatedParts) = groupedProjectLinks.partition(_._2.exists(_.isNotCalculated))

      val unCalcElyCodeChangeDetectedErrors: Iterable[ValidationErrorDetails] =
        if (notCalculatedParts.isEmpty)
          Iterable.empty[ValidationErrorDetails]
        else {
          def getProjectLinksFromMap(parts: Map[(Long, Long), Seq[ProjectLink]]): Iterable[ProjectLink] = parts.values.flatten
          def findConnectedTo(pls: Iterable[ProjectLink], roadAddresses: Iterable[BaseRoadAddress]): Seq[ProjectLink] = {
            pls.filter(pl => roadAddresses.exists(ra =>
              ra.ely != pl.ely &&
              (ra.startingPoint.connected(pl.startingPoint) || ra.startingPoint.connected(pl.endPoint))
            )).toSeq
          }
          def createValidationErrorDetails(road: Long,
                                           part: Long,
                                           pls : Seq[ProjectLink]
                                          ): Seq[ValidationErrorDetails] = {
            val coords  = prepareCoordinates(pls)
            val message = alterMessage(ValidationErrorList.ElyCodeChangeDetected, currentRoadAndPart = Some(Seq((road, part))), nextRoadAndPart = Some(Seq((road, part))))
            Seq(ValidationErrorDetails(project.id, message, pls.map(_.id), pls.map(_.linkId), coords, Option("ELY:n vaihdosta ei löytynyt.")))
          }
          val elysInUnCalculated = getProjectLinksFromMap(notCalculatedParts).map(_.ely).toSet
          val validationErrorsWithUnCalculated: Iterable[ValidationErrorDetails] =
          if (elysInUnCalculated.size > 1 && !getProjectLinksFromMap(notCalculatedParts).exists(_.discontinuity == Discontinuity.ChangingELYCode)) {
              val pls = findConnectedTo( getProjectLinksFromMap(notCalculatedParts), getProjectLinksFromMap(notCalculatedParts))
              if (pls.nonEmpty)
                createValidationErrorDetails(pls.head.roadNumber, pls.head.roadPartNumber, pls)
              else Seq.empty[ValidationErrorDetails]
          }
          else Seq.empty[ValidationErrorDetails]

        if (validationErrorsWithUnCalculated.nonEmpty)
            validationErrorsWithUnCalculated
        else {
            val roadways = notCalculatedParts.keys.flatMap(rp => roadwayDAO.fetchAllByRoadAndPart(rp._1, rp._2).filter(r => (r.roadNumber == rp._1 && r.roadPartNumber > rp._2)).sortBy(_.roadPartNumber).take(1))
            val existsSameRoadwayDifferentEly = notCalculatedParts.map {
              case ((road,part),pls) => (road,part) -> roadways.exists(r => (r.roadNumber == road && r.roadPartNumber > part && r.ely != pls.last.ely))
            }

            notCalculatedParts.flatMap { case ((road,part), pls) =>
               if (existsSameRoadwayDifferentEly(road,part).booleanValue()) {
                 createValidationErrorDetails(road, part, pls)
               }
               else Seq.empty[ValidationErrorDetails] }
        }
      }

      val recErrors = recProjectGroupsEly(calculatedParts, Map.empty)
      (validationErrors ++ recErrors ++ unCalcElyCodeChangeDetectedErrors).toSeq
    }

    val workedProjectLinks = allProjectLinks.filterNot(_.status == RoadAddressChangeType.NotHandled)
    if (workedProjectLinks.nonEmpty) {
      val grouped = workedProjectLinks.groupBy(pl => (pl.roadNumber, pl.roadPartNumber)).map(group => group._1 -> group._2.sortBy(_.endAddrMValue))
      val groupedMinusTerminated = grouped.map(g => {
        g._1 -> g._2.filterNot(_.status == RoadAddressChangeType.Termination)
      }).filterNot(_._2.isEmpty)
      val orderedProjectLinks = ListMap(groupedMinusTerminated.toSeq.sortBy(_._1): _*).asInstanceOf[Map[(Long, Long), Seq[ProjectLink]]]
      val projectLinkElyChangeErrors = checkChangeOfEly(project, orderedProjectLinks)
      projectLinkElyChangeErrors
    } else Seq.empty[ValidationErrorDetails]
  }


  /**
    * Check for non-ramp and roundabout roads:
    * 1) If inside a part there is a gap between links > .1 meters, discontinuity 4 (minor) is required
    * 2) If inside a part there is no gap, discontinuity 5 (cont) is required
    * 3) End of road part, discontinuity 2 or 3 (major, ely change) is required if there is a gap
    * 4) If a part that contained end of road discontinuity is terminated / renumbered / transferred,
    * there must be a new end of road link for that road at the last part
    * 5) If the next road part has differing ely code then there must be a discontinuity code 3 at the end
    *
    * @param project          Road address project
    * @param roadProjectLinks Project links
    * @return
    */
  def checkRoadContinuityCodes(project: Project, roadProjectLinks: Seq[ProjectLink], isRampValidation: Boolean = false): Seq[ValidationErrorDetails] = {

    val allProjectLinks = projectLinkDAO.fetchProjectLinks(project.id)

    def isConnectingRoundabout(pls: Seq[ProjectLink]): Boolean = {
      // This code means that this road part (of a ramp) should be connected to a roundabout
      val endPoints = pls.map(endPoint).map(p => (p.x, p.y)).unzip
      val boundingBox = BoundingRectangle(Point(endPoints._1.min,
        endPoints._2.min), Point(endPoints._1.max, endPoints._2.max))
      // Fetch all ramps and roundabouts roads and parts this is connected to (or these, if ramp has multiple links)
      val roadParts = roadAddressService.getCurrentRoadAddresses(roadAddressService.fetchLinearLocationByBoundingBox(boundingBox, Seq((RampsMinBound, RampsMaxBound)))).filter(ra =>
        pls.exists(_.connected(ra))).groupBy(ra => (ra.roadNumber, ra.roadPartNumber))
      // Check all the fetched road parts to see if any of them is a roundabout
      roadParts.keys.exists(rp => TrackSectionOrder.isRoundabout(
        roadAddressService.getRoadAddressWithRoadAndPart(rp._1, rp._2)))
    }

    def checkContinuityBetweenLinksOnParts: Seq[ValidationErrorDetails] = {
      def checkConnected(curr: ProjectLink, next: Option[ProjectLink]): Boolean = {
        if (next.isEmpty)
          false
        else
          curr.endAddrMValue == next.get.startAddrMValue && curr.connected(next.get)
      }

      val discontinuous: Seq[ProjectLink] = roadProjectLinks.groupBy(s => (s.roadNumber, s.roadPartNumber)).flatMap { g =>
        val trackIntervals: Seq[Seq[ProjectLink]] = Seq(g._2.filter(_.track != Track.RightSide), g._2.filter(_.track != Track.LeftSide))
        trackIntervals.flatMap {
          interval => {
            if (interval.size > 1) {
              interval.sortBy(_.startAddrMValue).sliding(2).flatMap {
                case Seq(curr, next) =>
                  if (Track.isTrackContinuous(curr.track, next.track) && checkConnected(curr, Option(next)) && (curr.discontinuity == Discontinuity.MinorDiscontinuity || curr.discontinuity == Discontinuity.Discontinuous))
                    Some(curr)
                  else
                    None
              }
            } else None
          }
        }
      }.toSeq



      error(project.id, ValidationErrorList.ConnectedDiscontinuousLink)(discontinuous).toSeq
    }

    def checkMinorDiscontinuityBetweenLinksOnPart: Seq[ValidationErrorDetails] = {

      def checkConnected(curr: ProjectLink, next: Option[ProjectLink]): Boolean = {
        if (next.isEmpty)
          false
        else
          curr.endAddrMValue == next.get.startAddrMValue && curr.connected(next.get)
      }

      val discontinuous: Seq[ProjectLink] = roadProjectLinks.groupBy(s => (s.roadNumber, s.roadPartNumber)).flatMap { g =>
        val trackIntervals = Seq(g._2.filter(_.track != Track.RightSide), g._2.filter(_.track != Track.LeftSide))
        trackIntervals.flatMap {
          interval => {
            if (interval.size > 1) {
              if (interval.exists(_.endAddrMValue == 0)) {
                val onceConnectedNewLinks = TrackSectionOrder.findOnceConnectedLinks(interval)
                if (onceConnectedNewLinks.size < 3)
                  None
                else {
                  val endPointLinks     = findChainEndpoints(onceConnectedNewLinks.values.toSeq)
                  val middleLinks       = onceConnectedNewLinks.filter((p: (Point, ProjectLink)) => !endPointLinks.exists(p2 => GeometryUtils.areAdjacent(p2._1,GeometryUtils.to2DGeometry(p._1))))
                  val lastLink          = endPointLinks.find(p => p._2.discontinuity != Discontinuity.Continuous).getOrElse(onceConnectedNewLinks.maxBy(_._2.id))
                  val dists             = middleLinks.map(l => l._1.distance2DTo(lastLink._1) -> l._2)
                  val discontinuousLink = dists.maxBy(_._1)._2
                  if (discontinuousLink.discontinuity == Discontinuity.Continuous)
                    Some(discontinuousLink)
                  else
                    None
                }
              } else {
                interval.sortBy(_.startAddrMValue).sliding(2).flatMap { case Seq(curr, next) => /*
                        catches discontinuity between Combined -> RightSide ? true => checks discontinuity between Combined -> LeftSide ? false => No error
                        catches discontinuity between Combined -> RightSide ? true => checks discontinuity between Combined -> LeftSide ? true => Error
                            Track 2
                         ^---------->
                         |
                Track 0  |
                         |  Track 1
                         |---------->


                        catches discontinuity between Combined -> LeftSide ? true => checks discontinuity between Combined -> RightSide ? false => No error
                        catches discontinuity between Combined -> LeftSide ? true => checks discontinuity between Combined -> RightSide ? true => Error
                            Track 1
                         <----------^
                                    |
                                    | Track 0
                           Track 2  |
                         <----------|
                   */
                   val nextOppositeTrack = g._2.find(t => {
                  t.track != next.track && t.startAddrMValue == next.startAddrMValue
                })
                  if (Track.isTrackContinuous(curr.track, next.track) && (checkConnected(curr, Option(next)) || checkConnected(curr, nextOppositeTrack)) || curr.discontinuity == Discontinuity.MinorDiscontinuity || curr.discontinuity == Discontinuity.Discontinuous) None else Some(curr)
                }
              }
            } else None
          }
        }
      }.toSeq

      error(project.id, ValidationErrorList.MinorDiscontinuityFound)(discontinuous).toSeq
    }

    def checkDiscontinuityBetweenLinksOnRamps: Seq[ValidationErrorDetails] = {
      val discontinuousErrors = if (isRampValidation) {
        error(project.id, ValidationErrorList.DiscontinuityOnRamp)(roadProjectLinks.filter { pl =>
          // Check that pl has no discontinuity unless on last link and after it the possible project link is connected
          val nextLink = roadProjectLinks.find(pl2 => pl2.startAddrMValue == pl.endAddrMValue &&
            (pl.track == Track.Combined || pl2.track == Track.Combined || pl.track == pl2.track ))
          (nextLink.nonEmpty && pl.discontinuity != Discontinuity.Continuous) ||
            nextLink.exists(pl2 => !pl.connected(pl2))
        })
      }
      else None
      discontinuousErrors.toSeq
    }

    def checkDiscontinuityInsideRoadPart: Seq[ValidationErrorDetails] = {
      val discontinuousErrors = error(project.id, ValidationErrorList.DiscontinuityInsideRoadPart)(roadProjectLinks.filter { pl =>
        val nextLink = roadProjectLinks.find(pl2 => pl2.startAddrMValue == pl.endAddrMValue)
        (nextLink.nonEmpty && pl.discontinuity == Discontinuity.Discontinuous)
      })
      discontinuousErrors.toSeq
    }

    /**
      * This will evaluate that the last link of the road has EndOfRoad discontinuity value.
      *
      * @return
      */
    def checkEndOfRoadOnLastPart: Seq[ValidationErrorDetails] = {
      val afterCheckErrors = roadProjectLinks.groupBy(_.roadNumber).flatMap { g =>
        val roadNumber = g._1
        val trackIntervals = Seq(g._2.filter(_.track != Track.RightSide), g._2.filter(_.track != Track.LeftSide))
        val validRoadParts = roadAddressService.getValidRoadAddressParts(roadNumber, project.startDate)
        trackIntervals.flatMap {
          interval =>
            val nonTerminated = interval.filter(r => r.status != RoadAddressChangeType.Termination)
            if (nonTerminated.nonEmpty) {

              val last = if (nonTerminated.forall(_.isNotCalculated)) {
                val endPointLinks = findChainEndpoints(nonTerminated)
                val endLink       = endPointLinks.find(_._2.discontinuity == Discontinuity.EndOfRoad).getOrElse(endPointLinks.last)
                endLink._2
              } else {nonTerminated.maxBy(_.endAddrMValue)}

              val (road, part) = (last.roadNumber, last.roadPartNumber)
              val discontinuity = last.discontinuity
              val projectNextRoadParts = (project.reservedParts++project.formedParts).filter(rp =>
                rp.roadNumber == road && rp.roadPartNumber > part && rp.newLength.getOrElse(0L) > 0L && allProjectLinks.exists(l => l.roadPartNumber == rp.roadPartNumber))

              val nextProjectPart = projectNextRoadParts.map(_.roadPartNumber).sorted.headOption
              val nextAddressPart = validRoadParts
                .filter(p => p > part).sorted
                .filterNot(
                  rp => allProjectLinks.exists(l => l.roadAddressRoadNumber.getOrElse(0) == roadNumber && l.roadAddressRoadPart.getOrElse(0) == rp)
                )

              if (nextProjectPart.isEmpty && nextAddressPart.isEmpty && discontinuity != Discontinuity.EndOfRoad) {
                error(project.id, ValidationErrorList.MissingEndOfRoad)(Seq(last))
              } else if (!(nextProjectPart.isEmpty && nextAddressPart.isEmpty) && discontinuity == Discontinuity.EndOfRoad) {
                error(project.id, ValidationErrorList.EndOfRoadNotOnLastPart)(Seq(last))
              } else
                None
            } else
              None
        }
      }.toSeq
      afterCheckErrors.groupBy(_.validationError).map {
        g =>
          val ids: Seq[Long] = g._2.flatMap(_.affectedPlIds)
          val linkIds: Seq[String] = g._2.flatMap(_.affectedLinkIds)
          val coords: Seq[ProjectCoordinates] = g._2.flatMap(_.coordinates)
          ValidationErrorDetails(g._2.head.projectId, g._1, ids, linkIds, coords, None)
      }.toSeq
    }

    /**
      * This will evaluate that the last link of the road part has EndOfRoad, ChangingELYCode or Continuous discontinuity value as needed.
      *
      * @return
      */
    def checkDiscontinuityOnLastLinkPart: Seq[ValidationErrorDetails] = {
      val discontinuityErrors = roadProjectLinks.groupBy(_.roadNumber).flatMap { g =>
        val validRoadParts = roadAddressService.getValidRoadAddressParts(g._1.toInt, project.startDate)
        val trackIntervals = Seq(g._2.filter(_.track != Track.RightSide), g._2.filter(_.track != Track.LeftSide))
        trackIntervals.flatMap {
          interval =>
            val nonTerminated = interval.filter(r => r.status != RoadAddressChangeType.Termination)
            if (nonTerminated.nonEmpty) {
              val last = nonTerminated.maxBy(_.endAddrMValue)
              val (road, part) = (last.roadNumber, last.roadPartNumber)
              val discontinuity = last.discontinuity
              val projectNextRoadParts = (project.reservedParts++project.formedParts).filter(rp =>
                rp.roadNumber == road && rp.roadPartNumber > part)

              val nextProjectPart = projectNextRoadParts.filter(np => np.newLength.getOrElse(0L) > 0L && allProjectLinks.exists(l => l.roadPartNumber == np.roadPartNumber))
                .map(_.roadPartNumber).sorted.headOption
              val nextAddressPart = validRoadParts
                .filter(p => p > part).sorted
                .find(p => roadAddressService.getRoadAddressesFiltered(road, p)
                  .forall(ra => !allProjectLinks.exists(al => al.linearLocationId == ra.linearLocationId && al.roadPartNumber != ra.roadPartNumber)))
              if (!(nextProjectPart.isEmpty && nextAddressPart.isEmpty)) {
                val nextLinks = getNextLinksFromParts(allProjectLinks, road, nextProjectPart, nextAddressPart)

                val rampDiscontinuity = if (isConnectingRoundabout(Seq(last)) && isRampValidation) {
                  discontinuity match {
                    case Discontinuity.EndOfRoad | Discontinuity.ChangingELYCode | Discontinuity.Continuous =>
                      error(project.id, ValidationErrorList.RoadConnectingRoundabout,
                        s"Rampin ${last.roadNumber} tieosa ${last.roadPartNumber} päättyy kiertoliittymään. Korjaa lievä epäjatkuvuus")(Seq(last))
                    case _ => None
                  }
                } else None

                val isConnected = Seq(last).forall(lpl => nextLinks.exists(nl => Track.isTrackContinuous(nl.track, lpl.track) &&
                  lpl.connected(nl)))
                val normalDiscontinuity = discontinuity match {
                  case Discontinuity.Continuous =>
                    if (!isConnected) error(project.id, ValidationErrorList.DiscontinuousFound)(Seq(last)) else None
                  case Discontinuity.Discontinuous =>
                    if (isConnected) error(project.id, ValidationErrorList.ConnectedDiscontinuousLink)(Seq(last)) else None
                  case Discontinuity.MinorDiscontinuity =>
                    if (isConnected)
                      error(project.id, ValidationErrorList.ConnectedDiscontinuousLink)(Seq(last))
                    else
                      error(project.id, ValidationErrorList.DiscontinuousFound)(Seq(last))
                  case _ => None // no error, continue
                }
                rampDiscontinuity.orElse(normalDiscontinuity)
              } else None

            } else None
        }
      }.toSeq
      discontinuityErrors.groupBy(_.validationError).map {
        g =>
          val ids: Seq[Long] = g._2.flatMap(_.affectedPlIds)
          val linkIds: Seq[String] = g._2.flatMap(_.affectedLinkIds)
          val coords: Seq[ProjectCoordinates] = g._2.flatMap(_.coordinates)
          ValidationErrorDetails(g._2.head.projectId, g._1, ids, linkIds, coords, None)
      }.toSeq
    }


    def checkEndOfRoadBetweenLinksOnPart: Seq[ValidationErrorDetails] = {
      val endOfRoadErrors = roadProjectLinks.groupBy(_.roadPartNumber).flatMap {
        roadPart =>
          val addresses = roadPart._2.sortBy(_.startAddrMValue)
          val maxEndAddr = addresses.last.endAddrMValue
          error(project.id, ValidationErrorList.EndOfRoadMiddleOfPart)(addresses.filterNot(_.endAddrMValue == maxEndAddr).filter(_.discontinuity == Discontinuity.EndOfRoad))
      }.toSeq
      endOfRoadErrors.distinct
    }

    /**
      * This will return the next link (being project link or road address) from a road number/road part number combo being them in this project or not
      *
      * @param allProjectLinks : Seq[ProjectLink] - Project Links
      * @param road            : Long - Road number
      * @param nextProjectPart : Long - Road Part Number
      * @param nextAddressPart : Long - Road Part Number
      * @return
      */
    def getNextLinksFromParts(allProjectLinks: Seq[ProjectLink], road: Long, nextProjectPart: Option[Long], nextAddressPart: Option[Long]): Seq[BaseRoadAddress] = {
      if (nextProjectPart.nonEmpty && (nextAddressPart.isEmpty || nextProjectPart.get <= nextAddressPart.get))
        projectLinkDAO.fetchByProjectRoadPart(road, nextProjectPart.get, project.id).filter(l => RoadAddressChangeType.Termination.value != l.status.value && l.startAddrMValue == 0L)
      else {
        roadAddressService.getRoadAddressesFiltered(road, nextAddressPart.get)
          .filterNot(rp => allProjectLinks.exists(link => (rp.roadNumber != link.roadNumber || rp.roadPartNumber != link.roadPartNumber) && rp.linearLocationId == link.linearLocationId)).filter(_.startAddrMValue == 0L)
      }
    }

    val continuityValidations: Seq[Seq[ValidationErrorDetails]] = Seq(
      checkContinuityBetweenLinksOnParts,
      checkMinorDiscontinuityBetweenLinksOnPart,
      checkDiscontinuityBetweenLinksOnRamps,
      checkDiscontinuityInsideRoadPart,
      checkEndOfRoadOnLastPart,
      checkDiscontinuityOnLastLinkPart,
      checkEndOfRoadBetweenLinksOnPart
    )

    val continuityErrors: Seq[ValidationErrorDetails] = continuityValidations.foldLeft(Seq.empty[ValidationErrorDetails]) { case (errors, validation) =>
      (validation ++ errors).distinct
    }
    //TODO - filter errors with ELY change on VIITE-1788
    //val continuityErrorsMinusElyChange =  filterErrorsWithElyChange(continuityErrors.distinct, allProjectLinks)
    continuityErrors.map(ce => {
      ce.copy(affectedPlIds = ce.affectedPlIds.distinct, coordinates = ce.coordinates.distinct)
    })
  }


  private def alterMessage(validationError: ValidationError,
                           elyBorderData: Option[Seq[Long]] = Option.empty[Seq[Long]],
                           currentRoadAndPart: Option[Seq[(Long, Long)]] = Option.empty[Seq[(Long, Long)]],
                           nextRoadAndPart: Option[Seq[(Long, Long)]] = Option.empty[Seq[(Long, Long)]],
                           discontinuity: Option[Seq[Discontinuity]] = Option.empty[Seq[Discontinuity]],
                           projectDate: Option[String] = Option.empty[String]) = {
    val formattedMessage =
      if (projectDate.nonEmpty && currentRoadAndPart.nonEmpty) {
        val unzippedRoadAndPart = currentRoadAndPart.get.unzip
        val changedMsg = validationError.message.format(unzippedRoadAndPart._1.head, unzippedRoadAndPart._2.head, projectDate.get)
        changedMsg
      } else if (currentRoadAndPart.nonEmpty && nextRoadAndPart.nonEmpty) {
        val unzippedPreviousRoadAndPart = currentRoadAndPart.get.unzip
        val unzippedNextRoadAndPart = nextRoadAndPart.get.unzip
        val changedMsg = validationError.message.format(unzippedPreviousRoadAndPart._2.head, unzippedPreviousRoadAndPart._2.head, unzippedNextRoadAndPart._2.head)
        changedMsg
      } else {
        validationError.message.format(if (elyBorderData.nonEmpty) {
          elyBorderData.get.toSet.mkString(", ")
        } else if (currentRoadAndPart.nonEmpty) {
          currentRoadAndPart.get.toSet.mkString(", ")
        } else if (discontinuity.nonEmpty) {
          discontinuity.get.groupBy(_.value).map(_._2.head.toString).mkString(", ")
        }
        else {
          validationError.message
        })
      }

    case object formattedMessageObject extends ValidationError {
      def value: Int = validationError.value

      def message: String = formattedMessage

      def notification: Boolean = validationError.notification
    }
    formattedMessageObject
  }

  private def alterShortMessage(validationError: ValidationError, currentRoadAndPart: Option[Seq[(Long, Long)]] = Option.empty[Seq[(Long, Long)]]) = {
    val formattedMessage =
      if (currentRoadAndPart.nonEmpty) {
        val unzippedRoadAndPart = currentRoadAndPart.get.unzip
        val changedMsg = validationError.message.format(unzippedRoadAndPart._1.head, unzippedRoadAndPart._2.head)
        changedMsg
      } else {
        validationError.message
      }

    case object formattedMessageObject extends ValidationError {
      def value: Int = validationError.value

      def message: String = formattedMessage

      def notification: Boolean = validationError.notification
    }
    formattedMessageObject
  }

  def errorPartsToApi(errorParts: ValidationErrorDetails): Map[String, Any] = {
    Map("ids" -> errorParts.affectedPlIds,
      "linkIds" -> errorParts.affectedLinkIds,
      "errorCode" -> errorParts.validationError.value,
      "errorMessage" -> errorParts.validationError.message,
      "info" -> errorParts.optionalInformation,
      "coordinates" -> errorParts.coordinates,
      "priority" -> errorParts.validationError.priority
    )
  }
}

class ProjectValidationException(s: String, validationErrors: Seq[Map[String, Any]] = Seq()) extends RuntimeException {
  override def getMessage: String = s
  def getValidationErrors: Seq[Map[String, Any]] = validationErrors
}

class NameExistsException(s: String) extends RuntimeException {
  override def getMessage: String = s
}

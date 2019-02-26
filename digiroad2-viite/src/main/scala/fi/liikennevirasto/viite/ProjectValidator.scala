package fi.liikennevirasto.viite

import java.util.Properties

import fi.liikennevirasto.digiroad2.{DummyEventBus, DummySerializer, GeometryUtils, Point}
import fi.liikennevirasto.digiroad2.asset.BoundingRectangle
import fi.liikennevirasto.digiroad2.asset.SideCode.{AgainstDigitizing, TowardsDigitizing}
import fi.liikennevirasto.digiroad2.client.vvh.VVHClient
import fi.liikennevirasto.digiroad2.service.RoadLinkService
import fi.liikennevirasto.digiroad2.util.Track
import fi.liikennevirasto.digiroad2.util.Track.{Combined, LeftSide, RightSide}
import fi.liikennevirasto.viite.dao.Discontinuity.{MinorDiscontinuity, _}
import fi.liikennevirasto.viite.dao.LinkStatus._
import fi.liikennevirasto.viite.dao._
import fi.liikennevirasto.viite.process.{RoadwayAddressMapper, TrackSectionOrder}

import scala.collection.immutable.ListMap
import org.slf4j.LoggerFactory
import fi.liikennevirasto.digiroad2.util.LogUtils.time
import fi.liikennevirasto.viite.process.strategy.DefaultSectionCalculatorStrategy
import org.joda.time.format.DateTimeFormat

import scala.collection.immutable

class ProjectValidator {

  val logger = LoggerFactory.getLogger(getClass)
  lazy val properties: Properties = {
    val props = new Properties()
    props.load(getClass.getResourceAsStream("/digiroad2.properties"))
    props
  }
  val vvhClient = new VVHClient(properties.getProperty("digiroad2.VVHRestApiEndPoint"))
  val eventBus = new DummyEventBus
  val linkService = new RoadLinkService(vvhClient, eventBus, new DummySerializer)
  val roadwayDAO = new RoadwayDAO
  val linearLocationDAO = new LinearLocationDAO
  val roadNetworkDAO: RoadNetworkDAO = new RoadNetworkDAO
  val roadAddressService = new RoadAddressService(linkService, roadwayDAO, linearLocationDAO, roadNetworkDAO, new UnaddressedRoadLinkDAO, new RoadwayAddressMapper(roadwayDAO, linearLocationDAO), eventBus, properties.getProperty("digiroad2.VVHRoadlink.frozen", "false").toBoolean) {
    override def withDynSession[T](f: => T): T = f

    override def withDynTransaction[T](f: => T): T = f
  }

  val projectLinkDAO = new ProjectLinkDAO
  val projectReservedPartDAO = new ProjectReservedPartDAO
  val projectDAO = new ProjectDAO
  val defaultSectionCalculatorStrategy = new DefaultSectionCalculatorStrategy
  val defaultZoomlevel = 12

  private def distanceToPoint = 10.0

  def checkReservedExistence(currentProject: Project, newRoadNumber: Long, newRoadPart: Long, linkStatus: LinkStatus, projectLinks: Seq[ProjectLink]): Unit = {
    if (LinkStatus.New.value == linkStatus.value && roadAddressService.getRoadAddressesFiltered(newRoadNumber, newRoadPart).nonEmpty) {
      if (!projectReservedPartDAO.fetchReservedRoadParts(currentProject.id).exists(p => p.roadNumber == newRoadNumber && p.roadPartNumber == newRoadPart)) {
        val fmt = DateTimeFormat.forPattern("dd.MM.yyyy")
        throw new ProjectValidationException(RoadNotAvailableMessage.format(newRoadNumber, newRoadPart, currentProject.startDate.toString(fmt)))
      }
    }
  }

  def checkAvailable(number: Long, part: Long, currentProject: Project): Unit = {
    if (projectReservedPartDAO.isNotAvailableForProject(number, part, currentProject.id)) {
      val fmt = DateTimeFormat.forPattern("dd.MM.yyyy")
      throw new ProjectValidationException(RoadNotAvailableMessage.format(number, part, currentProject.startDate.toString(fmt)))
    }
  }

  def checkNotReserved(number: Long, part: Long, currentProject: Project): Unit = {
    val project = projectReservedPartDAO.roadPartReservedByProject(number, part, currentProject.id, withProjectId = true)
    if (project.nonEmpty) {
      throw new ProjectValidationException(s"TIE $number OSA $part on jo varattuna projektissa ${project.get}, tarkista tiedot")
    }
  }

  def checkProjectExists(id: Long): Unit = {
    if (projectDAO.fetchById(id).isEmpty)
      throw new ProjectValidationException("Projektikoodilla ei löytynyt projektia")
  }

  // Utility method, will return correct GeometryEndpoint
  private def endPoint(b: BaseRoadAddress) = {
    b.sideCode match {
      case TowardsDigitizing => b.geometry.last
      case AgainstDigitizing => b.geometry.head
      case _ => Point(0.0, 0.0)
    }
  }

  private def startPoint(b: BaseRoadAddress) = {
    b.sideCode match {
      case TowardsDigitizing => b.geometry.head
      case AgainstDigitizing => b.geometry.last
      case _ => Point(0.0, 0.0)
    }
  }

  sealed trait ValidationError {
    def value: Int

    def message: String

    def notification: Boolean
  }

  object ValidationErrorList {
    val values = Set(MinorDiscontinuityFound, MajorDiscontinuityFound, InsufficientTrackCoverage, DiscontinuousAddressScheme,
      SharedLinkIdsExist, NoContinuityCodesAtEnd, UnsuccessfulRecalculation, MissingEndOfRoad, HasNotHandledLinks, ConnectedDiscontinuousLink,
      IncompatibleDiscontinuityCodes, EndOfRoadNotOnLastPart, ElyCodeChangeDetected, DiscontinuityOnRamp,
      ErrorInValidationOfUnchangedLinks, RoadNotEndingInElyBorder, RoadContinuesInAnotherEly, MultipleElyInPart, IncorrectLinkStatusOnElyCodeChange,
      ElyCodeChangeButNoRoadPartChange, ElyCodeChangeButNoElyChange, ElyCodeChangeButNotOnEnd)

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
    // There must be a major discontinuity if the jump is longer than 50 meters
    case object MajorDiscontinuityFound extends ValidationError {
      def value = 2

      def message: String = MajorDiscontinuityFoundMessage

      def notification = false
    }

    // Viite-453
    // For every track 1 there must exist track 2 that covers the same address span and vice versa
    case object InsufficientTrackCoverage extends ValidationError {
      def value = 3

      def message: String = InsufficientTrackCoverageMessage

      def notification = false
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

    case object IncorrectLinkStatusOnElyCodeChange extends ValidationError {
      def value = 22

      def message: String = IncorrectLinkStatusOnElyCodeChangeMessage

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

    def apply(intValue: Int): ValidationError = {
      values.find(_.value == intValue).get
    }
  }

  case class ValidationErrorDetails(projectId: Long, validationError: ValidationError,
                                    affectedIds: Seq[Long], coordinates: Seq[ProjectCoordinates],
                                    optionalInformation: Option[String])

  def findElyChangesOnAdjacentRoads(projectLink: ProjectLink, allProjectLinks: Seq[ProjectLink]) = {
    val dim = 2
    val points = GeometryUtils.geometryEndpoints(projectLink.geometry)
    val roadAddresses = roadAddressService.getRoadAddressLinksByBoundingBox(BoundingRectangle(points._2.copy(x = points._2.x+dim, y= points._2.y+dim), points._2.copy(x = points._2.x-dim, y= points._2.y-dim)), Seq.empty)
    val nextElyCodes = roadAddresses.filterNot(ra => allProjectLinks.exists(_.roadwayNumber == ra.roadwayNumber)).map(_.elyCode).toSet
    nextElyCodes.nonEmpty && !nextElyCodes.forall(_ == projectLink.ely)
  }

  def findElyChangesOnNextProjectLinks(projectLink: ProjectLink, allProjectLinks: Seq[ProjectLink]) = {
    val nextProjectLinks = allProjectLinks.filter(pl => pl.roadNumber == projectLink.roadNumber && pl.roadPartNumber > projectLink.roadPartNumber)
    val nextPartStart =
      if(nextProjectLinks.nonEmpty)
        Some(nextProjectLinks.minBy(p => (p.roadNumber, p.roadPartNumber)))
      else Option.empty
    nextProjectLinks.isEmpty && (nextPartStart.isDefined && nextPartStart.get.ely == projectLink.ely)
  }

  def filterErrorsWithElyChange(continuityErrors: Seq[ValidationErrorDetails], allProjectLinks: Seq[ProjectLink]): Seq[ValidationErrorDetails] = {
    if(allProjectLinks.size > 1) {
      continuityErrors.distinct.filter(ce => {
        val affectedProjectLinks = allProjectLinks.filter(pl => ce.affectedIds.contains(pl.id))
        val filtered = affectedProjectLinks.filter(apl => {
          val elyOnAdjacent = findElyChangesOnAdjacentRoads(apl, allProjectLinks)
          val elyOnNext = findElyChangesOnNextProjectLinks(apl, allProjectLinks)
          elyOnAdjacent || elyOnNext
        })
        filtered.isEmpty || ce.validationError.value == MissingEndOfRoadMessage
      })
    } else continuityErrors

  }

  def validateProject(project: Project, projectLinks: Seq[ProjectLink]): Seq[ValidationErrorDetails] = {
    time(logger, "Validating project") {
      actionsOrderingValidation(project, projectLinks) match {
        case e if e.nonEmpty => e
        case _ => projectLinksValidation(project, projectLinks)
      }
    }
  }

  def actionsOrderingValidation(project: Project, projectLinks: Seq[ProjectLink]): Seq[ValidationErrorDetails] = {
    val actionsOrdering: Seq[(Project, Seq[ProjectLink]) => Seq[ValidationErrorDetails]] = Seq(
      checkForInvalidUnchangedLinks
    )

    val errors: Seq[ValidationErrorDetails] = actionsOrdering.foldLeft(Seq.empty[ValidationErrorDetails]) { case (errors, validation) =>
      validation(project, projectLinks) ++ errors
    }
    errors.distinct
  }

  def projectLinksValidation(project: Project, projectLinks: Seq[ProjectLink]): Seq[ValidationErrorDetails] = {

    val projectValidations: Seq[(Project, Seq[ProjectLink]) => Seq[ValidationErrorDetails]] = Seq(
      checkProjectElyCodes,
      checkProjectContinuity,
      checkForNotHandledLinks,
      checkTrackCodePairing,
      checkRemovedEndOfRoadParts
    )

    val errors: Seq[ValidationErrorDetails] = projectValidations.foldLeft(Seq.empty[ValidationErrorDetails]) { case (errors, validation) =>
      validation(project, projectLinks) ++ errors
    }
    errors.distinct
  }

  def error(id: Long, validationError: ValidationError, info: String = "N/A")(pl: Seq[ProjectLink]): Option[ValidationErrorDetails] = {
    val (splitLinks, nonSplitLinks) = pl.partition(_.isSplit)
    val splitIds = splitLinks.flatMap(s => Seq(s.connectedLinkId.get, s.linkId))
    val connectedSplitLinks = projectLinkDAO.fetchProjectLinksByConnectedLinkId(splitIds)
    val (ids, points) = (nonSplitLinks ++ connectedSplitLinks).map(pl => (pl.id, GeometryUtils.midPointGeometry(pl.geometry))).unzip
    if (ids.nonEmpty) {
      Some(ValidationErrorDetails(id, validationError, ids,
        points.map(p => ProjectCoordinates(p.x, p.y, defaultZoomlevel)), Some(info)))
    } else {
      None
    }
  }

  def outsideOfProjectError(id: Long, validationError: ValidationError)(pl: Seq[RoadAddress]): Option[ValidationErrorDetails] = {
    val (ids, points) = pl.map(pl => (pl.id, GeometryUtils.midPointGeometry(pl.geometry))).unzip
    if (ids.nonEmpty)
      Some(ValidationErrorDetails(id, validationError, ids,
        points.map(p => ProjectCoordinates(p.x, p.y, defaultZoomlevel)), None))
    else
      None
  }

  def checkProjectContinuity(project: Project, projectLinks: Seq[ProjectLink]): Seq[ValidationErrorDetails] = {
    projectLinks.filter(_.status != Terminated).groupBy(pl => (pl.roadNumber, pl.roadPartNumber)).flatMap {
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
    val notHandled = projectLinks.filter(_.status == LinkStatus.NotHandled)
    notHandled.groupBy(link => (link.roadNumber, link.roadPartNumber)).foldLeft(Seq.empty[ValidationErrorDetails])((errorDetails, road) =>
      errorDetails :+ ValidationErrorDetails(project.id, ValidationErrorList.HasNotHandledLinks,
        Seq(road._2.size), road._2.map { l =>
          val point = GeometryUtils.midPointGeometry(l.geometry)
          ProjectCoordinates(point.x, point.y, 12)
        },
        Some(HasNotHandledLinksMessage.format(road._2.size, road._1._1, road._1._2)))
    )
  }

  def checkForInvalidUnchangedLinks(project: Project, projectLinks: Seq[ProjectLink]): Seq[ValidationErrorDetails] = {
    val invalidUnchangedLinks: Seq[ProjectLink] = projectLinks.groupBy(s => (s.roadNumber, s.roadPartNumber)).flatMap { g =>
      val (unchanged, others) = g._2.partition(_.status == UnChanged)
      //foreach number and part and foreach UnChanged found in that group, we will check if there is some link in some other different action, that is connected by geometry and addressM values to the UnChanged link starting point
      unchanged.filter(u => others.exists(o => o.connected(u) && u.startAddrMValue >= o.startAddrMValue))
    }.toSeq

    invalidUnchangedLinks.map { projectLink =>
      val point = GeometryUtils.midPointGeometry(projectLink.geometry)
      ValidationErrorDetails(project.id, ValidationErrorList.ErrorInValidationOfUnchangedLinks,
        Seq(projectLink.id), Seq(ProjectCoordinates(point.x, point.y, defaultZoomlevel)),
        Some("TIE : %d, OSA: %d, AET: %d".format(projectLink.roadNumber, projectLink.roadPartNumber, projectLink.startAddrMValue)))
    }
  }

  def checkTrackCodePairing(project: Project, projectLinks: Seq[ProjectLink]): Seq[ValidationErrorDetails] = {

    val notCombinedLinks = projectLinks.filterNot(_.track == Track.Combined)

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

    def checkMinMaxTrack(trackInterval: Seq[ProjectLink]): Option[ProjectLink] = {
      if (trackInterval.head.track != Combined) {
        val minTrackLink = trackInterval.minBy(_.startAddrMValue)
        val maxTrackLink = trackInterval.maxBy(_.endAddrMValue)
        val notCombinedLinksInRoadPart = notCombinedLinks.filter(l => l.roadNumber == minTrackLink.roadNumber && l.roadPartNumber == minTrackLink.roadPartNumber)
        if (!notCombinedLinksInRoadPart.exists(l => l.startAddrMValue == minTrackLink.startAddrMValue && l.track != minTrackLink.track)) {
          Some(minTrackLink)
        }
        else if (!notCombinedLinksInRoadPart.exists(l => l.endAddrMValue == maxTrackLink.endAddrMValue && l.track != maxTrackLink.track)) {
          Some(maxTrackLink)
        } else None
      } else None
    }

    def validateTrackTopology(trackInterval: Seq[ProjectLink]): Seq[ProjectLink] = {
      val validTrackInterval = trackInterval.filterNot(_.status == Terminated)
      if (validTrackInterval.nonEmpty) {
        checkMinMaxTrack(validTrackInterval) match {
          case Some(link) => Seq(link)
          case None => if (validTrackInterval.size > 1) {
            validTrackInterval.sliding(2).map { case Seq(first, second) => {
              if (first.endAddrMValue != second.startAddrMValue && first.id != second.id) {
                Some(first)
              } else None
            }
            }.toSeq.flatten
          } else Seq.empty[ProjectLink]
        }
      } else Seq.empty[ProjectLink]
    }

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

    val groupedLinks = notCombinedLinks.filterNot(_.status == LinkStatus.Terminated).groupBy(pl => (pl.roadNumber, pl.roadPartNumber))
    groupedLinks.map(roadPart => {
      recursiveCheckTrackChange(roadPart._2) match {
        case Some(errors) => Seq(errors)
        case _ => Seq()
      }
    }).headOption.getOrElse(Seq())
  }

  /**
    * Pick only parts that are terminated and had end of road given before
    * Find previous road part for all terminated road parts with end of road and link it to an error message
    * If previous road address is part of the project and not terminated, don't throw error
    *
    * @param project
    * @param projectLinks
    * @return
    */
  def checkRemovedEndOfRoadParts(project: Project, projectLinks: Seq[ProjectLink]): Seq[ValidationErrorDetails] = {
    projectLinks.filter(pl => pl.status == Terminated && pl.discontinuity == EndOfRoad).flatMap { rrp =>
      roadAddressService.getPreviousRoadAddressPart(rrp.roadNumber, rrp.roadPartNumber) match {
        case Some(previousRoadPartNumber) =>
          roadAddressService.getRoadAddressWithRoadAndPart(rrp.roadNumber, previousRoadPartNumber).reverse
            .find(ra => !projectLinks.exists(link => link.linearLocationId == ra.linearLocationId || link.status != Terminated)) match {
            case Some(actualProjectLinkForPreviousEnd) =>
              return Seq(ValidationErrorDetails(project.id, alterMessage(ValidationErrorList.TerminationContinuity, roadAndPart = Some(Seq((actualProjectLinkForPreviousEnd.roadNumber, actualProjectLinkForPreviousEnd.roadPartNumber)))),
                Seq(actualProjectLinkForPreviousEnd.id),
                Seq(ProjectCoordinates(actualProjectLinkForPreviousEnd.geometry.head.x, actualProjectLinkForPreviousEnd.geometry.head.y, defaultZoomlevel)), Some("")))
            case None => Seq()
          }
        case None => Seq()
      }
    }
  }

  def checkProjectElyCodes(project: Project, allProjectLinks: Seq[ProjectLink]): Seq[ValidationErrorDetails] = {

    def recProjectGroupsEly(unprocessed: Map[(Long, Long), Seq[ProjectLink]], processed: Map[(Long, Long), Seq[ProjectLink]], acumulatedErrors: Seq[ValidationErrorDetails] = Seq.empty[ValidationErrorDetails]): Seq[ValidationErrorDetails] = {

      def prepareCoordinates(links: Seq[ProjectLink]): Seq[ProjectCoordinates] = {
        links.map(p => {
          val middlePoint = GeometryUtils.midPointGeometry(p.geometry)
          ProjectCoordinates(middlePoint.x, middlePoint.y, defaultZoomlevel)
        })
      }

      if (processed.isEmpty && unprocessed.nonEmpty) {
        recProjectGroupsEly(unprocessed.tail, Map(unprocessed.head))
      } else {
        if (unprocessed.isEmpty) {
          acumulatedErrors
        } else {
          val biggestPrevious = processed.head._2.maxBy(_.endAddrMValue)
          val lowestCurrent = unprocessed.head._2.minBy(_.startAddrMValue)
          if (biggestPrevious.ely != lowestCurrent.ely) {
            val lastLinkHasChangeOfEly = biggestPrevious.discontinuity == Discontinuity.ChangingELYCode
            val roadNumbersAreDifferent = (lowestCurrent.roadNumber == biggestPrevious.roadNumber && lowestCurrent.roadPartNumber > biggestPrevious.roadPartNumber) || (lowestCurrent.roadNumber > biggestPrevious.roadNumber)

            val errors = (lastLinkHasChangeOfEly, roadNumbersAreDifferent) match {

              case (true, true) =>
                Seq.empty
              case (true, false) =>
                val affectedProjectLinks = unprocessed.head._2.filter(p => p.ely == biggestPrevious.ely)
                val coords = prepareCoordinates(affectedProjectLinks)
                Seq(ValidationErrorDetails(project.id, ValidationErrorList.ElyCodeChangeButNoElyChange, affectedProjectLinks.map(_.id), coords, Option("")))
              case (false, true) =>
                val affectedProjectLinks = Seq(biggestPrevious)
                val coords = prepareCoordinates(affectedProjectLinks)
                Seq(ValidationErrorDetails(project.id, ValidationErrorList.ElyCodeChangeDetected, affectedProjectLinks.map(_.id), coords, Option("")))
              case (false, false) =>
                val projectLinksSameEly = Seq(biggestPrevious)
                val projectLinksSameRoadPartNumber = unprocessed.head._2.filter(p => p.roadPartNumber == biggestPrevious.roadPartNumber)
                val sameElyCoords = prepareCoordinates(projectLinksSameEly)
                val sameRoadPartNumberCoords = prepareCoordinates(projectLinksSameRoadPartNumber)
                Seq(ValidationErrorDetails(project.id, ValidationErrorList.ElyCodeChangeButNoElyChange, projectLinksSameEly.map(_.id), sameElyCoords, Option("")),
                  ValidationErrorDetails(project.id, ValidationErrorList.ElyCodeChangeButNoRoadPartChange, projectLinksSameRoadPartNumber.map(_.id), sameRoadPartNumberCoords, Option("")))
            }
            recProjectGroupsEly(unprocessed.tail, Map(unprocessed.head) ++ processed, errors ++ acumulatedErrors)
          } else {
            if (biggestPrevious.discontinuity == Discontinuity.ChangingELYCode) {
              val affectedProjectLinks = Seq(biggestPrevious, lowestCurrent)
              val coords = prepareCoordinates(affectedProjectLinks)
              recProjectGroupsEly(unprocessed.tail, Map(unprocessed.head) ++ processed, acumulatedErrors ++ Seq(ValidationErrorDetails(project.id, ValidationErrorList.ElyCodeDiscontinuityChangeButNoElyChange, affectedProjectLinks.map(_.id), coords, Option(""))))
            } else {
              recProjectGroupsEly(unprocessed.tail, Map(unprocessed.head) ++ processed, acumulatedErrors)
            }

          }
        }
      }
    }

    /**
      * Check the project links edges for adjacent road addresses that must have a different ely than a adjacent one
      *
      * @param project             - the current project
      * @param groupedProjectLinks - project links, grouped by road number and road part number
      * @return Validation Errors
      */
    def checkFirstElyBorder(project: Project, groupedProjectLinks: Map[(Long, Long), Seq[ProjectLink]]): Seq[ValidationErrorDetails] = {
      /**
        * Method that will prepare the output of the validation error.
        *
        * @param validationError the validation error
        * @param pl              Sequence of the erroneous ProjectLinks
        * @return An optional value with eventual Validation error details
        */
      def error(validationError: ValidationError)(pl: Seq[BaseRoadAddress]): Option[ValidationErrorDetails] = {
        val (ids, points) = pl.map(pl => (pl.id, GeometryUtils.midPointGeometry(pl.geometry))).unzip
        if (ids.nonEmpty)
          Some(ValidationErrorDetails(project.id, validationError, ids.distinct,
            points.map(p => ProjectCoordinates(p.x, p.y, defaultZoomlevel)).distinct, None))
        else
          None
      }

      groupedProjectLinks.flatMap(group => {
        val projectLinksToEvaluate = group._2.filter(_.discontinuity == Discontinuity.ChangingELYCode)
        val problemRoads = if (projectLinksToEvaluate.nonEmpty) {
          val roadsValidation = evaluateBorderCheck(projectLinksToEvaluate.head, projectLinksToEvaluate.last, secondCheck = false)
          val possibleErrorRoads = roadsValidation.filterNot(_.isEmpty).getOrElse(Seq()).filterNot(er => {
            val found = allProjectLinks.filter(pl => pl.roadNumber == er.roadNumber && pl.roadPartNumber > er.roadPartNumber)
            found.nonEmpty && found.head.ely != er.ely
          })
          possibleErrorRoads
        } else {
          Seq.empty[BaseRoadAddress]
        }
        val uniqueProblemRoads = problemRoads.groupBy(_.id).map(_._2.head).toSeq
        val currError = error(ValidationErrorList.RoadNotEndingInElyBorder)(uniqueProblemRoads)
        val seqErrors = if(currError.isDefined) Seq(currError.get) else Seq.empty[ValidationErrorDetails]
        //TODO - filter errors on ELY change on VIITE-1788
        //filterErrorsWithElyChange(seqErrors, allProjectLinks)
        seqErrors
      }).toSeq
    }

    /**
      * Check the adjacent road addresses of the edges of the project links for ely codes that are different to the ones in the project links,
      * if they are not, issue a error
      *
      * @param project             - the current project
      * @param groupedProjectLinks - project links, grouped by road number and road part number
      * @return Validation Errors
      */
    def checkSecondElyBorder(project: Project, groupedProjectLinks: Map[(Long, Long), Seq[ProjectLink]]): Seq[ValidationErrorDetails] = {
      /**
        * Method that will prepare the output of the validation error.
        *
        * @param validationError the validation error
        * @param pl              Sequence of the erroneous ProjectLinks
        * @return An optional value with eventual Validation error details
        */
      def error(validationError: ValidationError)(pl: Seq[BaseRoadAddress]): Option[ValidationErrorDetails] = {
        val groupedByDiscontinuity = pl.groupBy(_.discontinuity)
        val (gLinkIds, gPoints, gDiscontinuity) = groupedByDiscontinuity.flatMap(g => {
          val links = g._2
          val zoomPoint = GeometryUtils.midPointGeometry(links.minBy(_.endAddrMValue).geometry)
          links.map(l => (l.id, zoomPoint, l.discontinuity))
        }).unzip3

        if (gLinkIds.nonEmpty) {
          if (gDiscontinuity.nonEmpty) {
            Some(ValidationErrorDetails(project.id, alterMessage(validationError, Option.empty, Option.empty, Option(gDiscontinuity.toSeq)), gLinkIds.toSeq.distinct,
              gPoints.map(p => ProjectCoordinates(p.x, p.y, defaultZoomlevel)).toSeq.distinct, Option.empty[String]))
          } else Some(ValidationErrorDetails(project.id, validationError, gLinkIds.toSeq.distinct,
            gPoints.map(p => ProjectCoordinates(p.x, p.y, defaultZoomlevel)).toSeq.distinct, Option.empty[String]))
        }
        else
          Option.empty[ValidationErrorDetails]
      }

      val validationProblems = groupedProjectLinks.flatMap(group => {
        val projectLinks = group._2
        val problemRoads = if (projectLinks.nonEmpty) {
          val (startRoad, endRoad) = (projectLinks.head, projectLinks.last)
          val validationResult = if (startRoad.discontinuity.value != Discontinuity.ChangingELYCode.value) evaluateBorderCheck(startRoad, endRoad, secondCheck = true) else Option.empty[Seq[ProjectLink]]
          validationResult.filterNot(_.isEmpty).getOrElse(Seq())

        } else {
          Seq.empty[BaseRoadAddress]
        }
        val uniqueProblemRoads = problemRoads.groupBy(_.id).map(_._2.head).toSeq
        error(ValidationErrorList.RoadContinuesInAnotherEly)(uniqueProblemRoads)

      })
      validationProblems.toSeq
    }

    /**
      * Main validation we create a bounding box and search for adjacent road addresses to the edgeRoad.
      * Then check if the ely code changed between them, depending whether we are validation the firstBorderCheck or the
      * second we output the edgeRoad based on the finding (or not) of a road address with a different ely code then that of the edgeRoad.
      *
      * @param startRoad   - start of a road number/road part number project link
      * @param endRoad     - end of a road number/road part number project link
      * @param secondCheck - indicates what kind of search we use
      * @return an optional symbolizing a found invalid edgeRoad, or nothing.
      */
    def evaluateBorderCheck(startRoad: ProjectLink, endRoad: ProjectLink, secondCheck: Boolean): Option[Seq[ProjectLink]] = {
      /**
        * Helper method, will find ALL the road addresses in a bounding box whose center is the edge road
        *
        * @param headRoad A project link, at the start of it (lowest endAddressMValue)
        * @param tailRoad A project link, at the end of it(highest endAddressMValue)
        * @return Road addresses contained in a small bounding box
        */
      def findRoads(headRoad: ProjectLink, tailRoad: ProjectLink): Seq[LinearLocation] = {
        val sp = startPoint(headRoad)
        val ep = endPoint(tailRoad)
        val connectingAtStart = {
          val lowerCorner = Point(sp.x - distanceToPoint, sp.y - distanceToPoint, sp.z - distanceToPoint)
          val higherCorner = Point(sp.x + distanceToPoint, sp.y + distanceToPoint, sp.z + distanceToPoint)
          val box = BoundingRectangle(lowerCorner, higherCorner)
          roadAddressService.fetchLinearLocationByBoundingBox(box)
        }
        val connectingAtEnd = {
          val lowerCorner = Point(ep.x - distanceToPoint, ep.y - distanceToPoint, ep.z - distanceToPoint)
          val higherCorner = Point(ep.x + distanceToPoint, ep.y + distanceToPoint, ep.z + distanceToPoint)
          val box = BoundingRectangle(lowerCorner, higherCorner)
          roadAddressService.fetchLinearLocationByBoundingBox(box)
        }
        connectingAtStart ++ connectingAtEnd
      }

      val roadAddresses = roadAddressService.getCurrentRoadAddresses(findRoads(startRoad, endRoad))
      if (roadAddresses.nonEmpty) {
        val filtered = roadAddresses.filterNot(ra => ra.roadNumber == startRoad.roadNumber && ra.roadPartNumber == startRoad.roadPartNumber &&
          !GeometryUtils.areAdjacent(ra.geometry, startRoad.geometry) && !GeometryUtils.areAdjacent(ra.geometry, endRoad.geometry))
        val diffEly = filtered.find(_.ely != startRoad.ely)
        if (!secondCheck && diffEly.isEmpty) {
          Option(Seq(endRoad))
        } else if (secondCheck && diffEly.isDefined) {
          Option(Seq(endRoad))
        } else Option.empty[Seq[ProjectLink]]
      } else Option.empty[Seq[ProjectLink]]
    }

    /**
      * This will validate if a shift in ely code in all links of a certain part ocoured and happened correctly.
      * To be correct, the change needs to:
      * A. have all links transition to a new ELY
      * B. all links must have the UnChanged Link status
      *
      * @param project             : Project - the project to evaluate
      * @param groupedProjectLinks : Map[(Long, Long), Seq[ProjectLink]) - the project links, grouped by road number and road part number
      * @return
      */
    def checkChangeOfEly(project: Project, groupedProjectLinks: Map[(Long, Long), Seq[ProjectLink]]): Seq[ValidationErrorDetails] = {

      def prepareValidationErrorDetails(condition: Either[Seq[Long], Seq[LinkStatus]]): ValidationErrorDetails = {
        val (wrongProjectLinks, validationError) = condition match {
          case Left(originalElys) =>
            if (originalElys.nonEmpty)
              (allProjectLinks.filterNot(_.ely == originalElys.head), ValidationErrorList.MultipleElyInPart)
            else {
              (allProjectLinks.groupBy(_.ely).map(_._2.maxBy(_.endAddrMValue)).toSeq, ValidationErrorList.MultipleElyInPart)
            }
          case Right(linkStatusSeq) =>
            (allProjectLinks.filterNot(pl => linkStatusSeq.contains(pl.status)), ValidationErrorList.IncorrectLinkStatusOnElyCodeChange)
        }

        val projectCoords = wrongProjectLinks.map(p => {
          val middlePoint = GeometryUtils.middlePoint(Seq(p.geometry))
          ProjectCoordinates(middlePoint.x, middlePoint.y, defaultZoomlevel)
        })
        ValidationErrorDetails(project.id, validationError, wrongProjectLinks.map(_.id), projectCoords, Option.empty[String])
      }

      val validationErrors = groupedProjectLinks.flatMap(group => {
        //Fetch original roadway data
        val workableProjectLinks = allProjectLinks.filterNot(pl => pl.status == LinkStatus.NotHandled || pl.status == LinkStatus.Terminated)
        val roadways = roadwayDAO.fetchAllByRoadwayNumbers(group._2.map(_.roadwayNumber).toSet)
        val notLastLinkHasChangeOfEly = group._2.filter(p => p.discontinuity == Discontinuity.ChangingELYCode && p.id != group._2.maxBy(_.endAddrMValue).id)
        val originalElys = roadways.map(_.ely).distinct
        val projectLinkElys = group._2.map(_.ely).distinct

        val errors = if (originalElys.nonEmpty || (originalElys.isEmpty && projectLinkElys.size > 1)) {

          val multi = if (projectLinkElys.size > 1) {
            Seq(prepareValidationErrorDetails(Left(originalElys)))
          }
          else Seq.empty

          val wrongStatusCode = if (!workableProjectLinks.forall(pl => pl.status == LinkStatus.UnChanged || pl.status == LinkStatus.Transfer || pl.status == LinkStatus.New || pl.status == LinkStatus.Numbering) && !originalElys.equals(projectLinkElys)) {
            Seq(prepareValidationErrorDetails(Right(Seq(LinkStatus.UnChanged, LinkStatus.Transfer, LinkStatus.New, LinkStatus.Numbering))))
          }
          else Seq.empty

          val changeElyCodeNotInFinish = if (notLastLinkHasChangeOfEly.nonEmpty) {
            val coords = notLastLinkHasChangeOfEly.map(p => {
              val middlePoint = GeometryUtils.midPointGeometry(p.geometry)
              ProjectCoordinates(middlePoint.x, middlePoint.y, defaultZoomlevel)
            })
            Seq(ValidationErrorDetails(project.id, ValidationErrorList.ElyCodeChangeButNotOnEnd, notLastLinkHasChangeOfEly.map(_.id), coords, Option.empty[String]))
          } else Seq.empty

          multi ++ wrongStatusCode ++ changeElyCodeNotInFinish
        } else Seq.empty
        errors
      })
      val recErrors = recProjectGroupsEly(groupedProjectLinks, Map.empty)
      (validationErrors ++ recErrors).toSeq

    }

    val workedProjectLinks = allProjectLinks.filterNot(_.status == LinkStatus.NotHandled)
    if (workedProjectLinks.nonEmpty) {
      val grouped = workedProjectLinks.groupBy(pl => (pl.roadNumber, pl.roadPartNumber)).map(group => group._1 -> group._2.sortBy(_.endAddrMValue))
      val projectLinksDiscontinuity = workedProjectLinks.map(_.discontinuity.value).distinct.toList
      val errors = if (projectLinksDiscontinuity.contains(Discontinuity.ChangingELYCode.value))
        checkFirstElyBorder(project, grouped)
      else
        checkSecondElyBorder(project, grouped)
      val groupedMinusTerminated = grouped.map(g => {
        g._1 -> g._2.filterNot(_.status == Terminated)
      }).filterNot(_._2.isEmpty)
      val orderedProjectLinks = ListMap(groupedMinusTerminated.toSeq.sortBy(_._1): _*).asInstanceOf[Map[(Long, Long), Seq[ProjectLink]]]
      val projectLinkElyChangeErrors = checkChangeOfEly(project, orderedProjectLinks)
      errors ++ projectLinkElyChangeErrors
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
        roadAddressService.getRoadAddressWithRoadAndPart(rp._1, rp._2, withFloating = true)))
    }

    def checkContinuityBetweenLinksOnParts: Seq[ValidationErrorDetails] = {
      def checkConnected(curr: ProjectLink, next: Option[ProjectLink]): Boolean = {
        if (next.isEmpty)
          false
        else
          curr.endAddrMValue == next.get.startAddrMValue && curr.connected(next.get)
      }

      val discontinuous: Seq[ProjectLink] = roadProjectLinks.groupBy(s => (s.roadNumber, s.roadPartNumber)).flatMap { g =>
        val trackIntervals: Seq[Seq[ProjectLink]] = Seq(g._2.filter(_.track != RightSide), g._2.filter(_.track != LeftSide))
        trackIntervals.flatMap {
          interval => {
            if (interval.size > 1) {
              interval.sortBy(_.startAddrMValue).sliding(2).flatMap {
                case Seq(curr, next) =>
                  if (Track.isTrackContinuous(curr.track, next.track) && checkConnected(curr, Option(next)) && (curr.discontinuity == MinorDiscontinuity || curr.discontinuity == Discontinuous))
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
        val trackIntervals = Seq(g._2.filter(_.track != RightSide), g._2.filter(_.track != LeftSide))
        trackIntervals.flatMap {
          interval => {
            if (interval.size > 1) {
              interval.sortBy(_.startAddrMValue).sliding(2).flatMap {
                case Seq(curr, next) =>
                  /*
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
                  val nextOppositeTrack = g._2.find(t => t.track != next.track && t.startAddrMValue == next.startAddrMValue)
                  if (Track.isTrackContinuous(curr.track, next.track) && (checkConnected(curr, Option(next)) || checkConnected(curr, nextOppositeTrack)) || curr.discontinuity == MinorDiscontinuity)
                    None
                  else
                    Some(curr)
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
          val nextLink = roadProjectLinks.find(pl2 => pl2.startAddrMValue == pl.endAddrMValue)
          (nextLink.nonEmpty && pl.discontinuity != Continuous) ||
            nextLink.exists(pl2 => !pl.connected(pl2))
        })
      }
      else None
      discontinuousErrors.toSeq
    }

    /**
      * This will evaluate that the last link of the road part has EndOfRoad discontinuity value.
      *
      * @return
      */
    def checkEndOfRoadOnLastPart: Seq[ValidationErrorDetails] = {
      val afterCheckErrors = roadProjectLinks.groupBy(_.roadNumber).flatMap { g =>
        val validRoadParts = roadAddressService.getValidRoadAddressParts(g._1.toLong, project.startDate)
        val trackIntervals = Seq(g._2.filter(_.track != RightSide), g._2.filter(_.track != LeftSide))
        trackIntervals.flatMap {
          interval =>
            val nonTerminated = interval.filter(r => r.status != LinkStatus.Terminated)
            if (nonTerminated.nonEmpty) {
              val last = nonTerminated.maxBy(_.endAddrMValue)
              val (road, part) = (last.roadNumber, last.roadPartNumber)
              val discontinuity = last.discontinuity
              val projectNextRoadParts = project.reservedParts.filter(rp =>
                rp.roadNumber == road && rp.roadPartNumber > part)

              val nextProjectPart = projectNextRoadParts.filter(np => np.newLength.getOrElse(0L) > 0L && allProjectLinks.exists(l => l.roadPartNumber == np.roadPartNumber))
                .map(_.roadPartNumber).sorted.headOption
              val nextAddressPart = validRoadParts
                .filter(p => p > part).sorted
                .find(p => roadAddressService.getRoadAddressesFiltered(road, p)
                  .forall(ra => !allProjectLinks.exists(al => al.linearLocationId == ra.linearLocationId && al.roadPartNumber != ra.roadPartNumber)))
              if (nextProjectPart.isEmpty && nextAddressPart.isEmpty && discontinuity != EndOfRoad) {
                error(project.id, ValidationErrorList.MissingEndOfRoad)(Seq(last))
              } else if (!(nextProjectPart.isEmpty && nextAddressPart.isEmpty) && discontinuity == EndOfRoad) {
                error(project.id, ValidationErrorList.EndOfRoadNotOnLastPart)(Seq(last))
              } else
                None

            } else
              None

        }
      }.toSeq
      afterCheckErrors.groupBy(_.validationError).map {
        g =>
          val ids: Seq[Long] = g._2.flatMap(_.affectedIds)
          val coords: Seq[ProjectCoordinates] = g._2.flatMap(_.coordinates)
          ValidationErrorDetails(g._2.head.projectId, g._1, ids, coords, None)
      }.toSeq
    }

    /**
      * This will evaluate that the last link of the road part has EndOfRoad, ChangingELYCode or Continuous discontinuity value as needed.
      *
      * @return
      */
    def checkDiscontinuityOnLastPart: Seq[ValidationErrorDetails] = {
      val discontinuityErrors = roadProjectLinks.groupBy(_.roadNumber).flatMap { g =>
        val validRoadParts = roadAddressService.getValidRoadAddressParts(g._1.toInt, project.startDate)
        val trackIntervals = Seq(g._2.filter(_.track != RightSide), g._2.filter(_.track != LeftSide))
        trackIntervals.flatMap {
          interval =>
            val nonTerminated = interval.filter(r => r.status != LinkStatus.Terminated)
            if (nonTerminated.nonEmpty) {
              val last = nonTerminated.maxBy(_.endAddrMValue)
              val (road, part) = (last.roadNumber, last.roadPartNumber)
              val discontinuity = last.discontinuity
              val projectNextRoadParts = project.reservedParts.filter(rp =>
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
                    case EndOfRoad | ChangingELYCode | Continuous =>
                      error(project.id, ValidationErrorList.RoadConnectingRoundabout,
                        s"Rampin ${last.roadNumber} tieosa ${last.roadPartNumber} päättyy kiertoliittymään. Korjaa lievä epäjatkuvuus")(Seq(last))
                    case _ => None
                  }
                } else None

                val isConnected = Seq(last).forall(lpl => nextLinks.exists(nl => Track.isTrackContinuous(nl.track, lpl.track) &&
                  lpl.connected(nl)))
                val normalDiscontinuity = discontinuity match {
                  case Continuous =>
                    if (!isConnected) error(project.id, ValidationErrorList.MajorDiscontinuityFound)(Seq(last)) else None
                  case MinorDiscontinuity | Discontinuous =>
                    if (isConnected) error(project.id, ValidationErrorList.ConnectedDiscontinuousLink)(Seq(last)) else None
                  case _ => None // no error, continue
                }
                rampDiscontinuity.orElse(normalDiscontinuity)
              } else None

            } else None
        }
      }.toSeq
      discontinuityErrors.groupBy(_.validationError).map {
        g =>
          val ids: Seq[Long] = g._2.flatMap(_.affectedIds)
          val coords: Seq[ProjectCoordinates] = g._2.flatMap(_.coordinates)
          ValidationErrorDetails(g._2.head.projectId, g._1, ids, coords, None)
      }.toSeq
    }

    /**
      * This will validate if the road number and road part number we have in the project has a end of road discontinuity in any road that lies outside of the project.
      *
      * @return
      */
    def checkEndOfRoadOutsideOfProject: Seq[ValidationErrorDetails] = {
      val (road, part): (Long, Long) = (roadProjectLinks.head.roadNumber, roadProjectLinks.head.roadPartNumber)
      roadAddressService.getPreviousRoadAddressPart(road, part) match {
        case Some(previousRoadPartNumber) =>
          val actualProjectLinkForPreviousEnd = roadAddressService.getRoadAddressWithRoadAndPart(road, previousRoadPartNumber, fetchOnlyEnd = true)
            .filter(ra => ra.discontinuity == EndOfRoad && !allProjectLinks.exists(link => link.linearLocationId == ra.linearLocationId))
          if (actualProjectLinkForPreviousEnd.nonEmpty)
            return outsideOfProjectError(
              project.id,
              alterMessage(ValidationErrorList.DoubleEndOfRoad, roadAndPart = Some(Seq((road, previousRoadPartNumber))))
            )(actualProjectLinkForPreviousEnd).toSeq
        case None => Seq()
      }
      Seq()
    }

    def checkEndOfRoadBetweenLinksOnPart: Seq[ValidationErrorDetails] = {
      val endOfRoadErrors = roadProjectLinks.groupBy(_.track).flatMap { track =>
        error(project.id, ValidationErrorList.EndOfRoadMiddleOfPart)(track._2.sortBy(pl => (pl.roadPartNumber, pl.startAddrMValue)).init.filter(_.discontinuity == EndOfRoad))
      }.toSeq
      endOfRoadErrors.distinct
    }

    /**
      * This will return the next link (being project link or road address) from a road number/road part number combo being them in this project or not
      * @param allProjectLinks: Seq[ProjectLink] - Project Links
      * @param road: Long - Road number
      * @param nextProjectPart: Long - Road Part Number
      * @param nextAddressPart: Long - Road Part Number
      * @return
      */
    def getNextLinksFromParts(allProjectLinks: Seq[ProjectLink], road: Long, nextProjectPart: Option[Long], nextAddressPart: Option[Long]):Seq[BaseRoadAddress] = {
      if (nextProjectPart.nonEmpty && (nextAddressPart.isEmpty || nextProjectPart.get <= nextAddressPart.get))
        projectLinkDAO.fetchByProjectRoadPart(road, nextProjectPart.get, project.id).filter(_.startAddrMValue == 0L)
      else {
        roadAddressService.getRoadAddressesFiltered(road, nextAddressPart.get)
          .filterNot(rp => allProjectLinks.exists(link => rp.roadPartNumber != link.roadPartNumber && rp.linearLocationId == link.linearLocationId)).filter(_.startAddrMValue == 0L)
      }
    }

    val continuityValidations: Seq[  Seq[ValidationErrorDetails]] = Seq(
      checkContinuityBetweenLinksOnParts,
      checkMinorDiscontinuityBetweenLinksOnPart,
      checkDiscontinuityBetweenLinksOnRamps,
      checkEndOfRoadOnLastPart,
      checkDiscontinuityOnLastPart,
      checkEndOfRoadOutsideOfProject,
      checkEndOfRoadBetweenLinksOnPart
    )

    val continuityErrors: Seq[ValidationErrorDetails] = continuityValidations.foldLeft(Seq.empty[ValidationErrorDetails]) { case (errors, validation) =>
      (validation ++ errors).distinct
    }
    //TODO - filter errors with ELY change on VIITE-1788
    //val continuityErrorsMinusElyChange =  filterErrorsWithElyChange(continuityErrors.distinct, allProjectLinks)
    continuityErrors
  }


  private def alterMessage(validationError: ValidationError, elyBorderData: Option[Seq[Long]] = Option.empty[Seq[Long]],
                           roadAndPart: Option[Seq[(Long, Long)]] = Option.empty[Seq[(Long, Long)]],
                           discontinuity: Option[Seq[Discontinuity]] = Option.empty[Seq[Discontinuity]], projectDate: Option[String] = Option.empty[String]) = {
    val formattedMessage =
      if (projectDate.nonEmpty && roadAndPart.nonEmpty) {
        val unzippedRoadAndPart = roadAndPart.get.unzip
        val changedMsg = validationError.message.format(unzippedRoadAndPart._1.head, unzippedRoadAndPart._2.head, projectDate.get)
        changedMsg
      } else {
        validationError.message.format(if (elyBorderData.nonEmpty) {
          elyBorderData.get.toSet.mkString(", ")
        } else if (roadAndPart.nonEmpty) {
          roadAndPart.get.toSet.mkString(", ")
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
}

class ProjectValidationException(s: String) extends RuntimeException {
  override def getMessage: String = s
}

class NameExistsException(s: String) extends RuntimeException {
  override def getMessage: String = s
}

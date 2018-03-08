package fi.liikennevirasto.viite

import fi.liikennevirasto.digiroad2.{GeometryUtils, Point}
import fi.liikennevirasto.digiroad2.asset.BoundingRectangle
import fi.liikennevirasto.digiroad2.asset.SideCode.{AgainstDigitizing, TowardsDigitizing}
import fi.liikennevirasto.digiroad2.util.Track
import fi.liikennevirasto.digiroad2.util.Track.Combined
import fi.liikennevirasto.viite.ProjectValidator.checkTerminationContinuity
import fi.liikennevirasto.viite.dao.Discontinuity.{MinorDiscontinuity, _}
import fi.liikennevirasto.viite.dao.LinkStatus._
import fi.liikennevirasto.viite.dao._
import fi.liikennevirasto.viite.process.TrackSectionOrder

object ProjectValidator {

  private def distanceToPoint = 10.0

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
      ErrorInValidationOfUnchangedLinks, RoadNotEndingInElyBorder, RoadContinuesInAnotherEly)

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
    // There are no link ids shared between the project and the current road address + lrm_position tables at the project date (start_date, end_date)
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
    // Unchanged project links cannot have any other operation (transfer, termination) previously on the same number and part
    case object ErrorInValidationOfUnchangedLinks extends ValidationError {
      def value = 14
      def message: String = ErrorInValidationOfUnchangedLinksMessage
      def notification = false
    }

    case object RampConnectingRoundabout extends ValidationError {
      def value = 15
      def message: String = MinorDiscontinuousWhenRampConnectingRoundabout
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

    def apply(intValue: Int): ValidationError = {
      values.find(_.value == intValue).get
    }
  }

  case class ValidationErrorDetails(projectId: Long, validationError: ValidationError,
                                    affectedLinkIds: Seq[Long], coordinates: Seq[ProjectCoordinates],
                                    optionalInformation: Option[String])

  def error(id: Long, validationError: ValidationError)(pl: Seq[ProjectLink]): Option[ValidationErrorDetails] = {
    val (linkIds, points) = pl.map(pl => (pl.linkId, GeometryUtils.midPointGeometry(pl.geometry))).unzip
    if (linkIds.nonEmpty)
      Some(ValidationErrorDetails(id, validationError, linkIds,
        points.map(p => ProjectCoordinates(p.x, p.y, 12)), None))
    else
      None
  }

  def validateProject(project: RoadAddressProject, projectLinks: Seq[ProjectLink]): Seq[ValidationErrorDetails] = {

    def checkProjectContinuity: Seq[ValidationErrorDetails] =
      projectLinks.filter(_.status != Terminated).groupBy(pl => (pl.roadNumber, pl.roadPartNumber)).flatMap {
        case ((road, _), seq) =>
          if (road < RampsMinBound || road > RampsMaxBound) {
            checkOrdinaryRoadContinuityCodes(project, seq)
          } else {
            checkRampContinuityCodes(project, seq)
          }
        case _ => Seq()
      }.toSeq

    def checkProjectCoverage = {
      Seq.empty[ValidationErrorDetails]
    }

    def checkProjectContinuousSchema = {
      Seq.empty[ValidationErrorDetails]
    }

    def checkProjectSharedLinks = {
      Seq.empty[ValidationErrorDetails]
    }

    def checkForContinuityCodes = {
      Seq.empty[ValidationErrorDetails]
    }

    def checkForUnsuccessfulRecalculation = {
      Seq.empty[ValidationErrorDetails]
    }

    def checkForNotHandledLinks = {
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

    def checkForInvalidUnchangedLinks = {
      val roadNumberAndParts = projectLinks.groupBy(pl => (pl.roadNumber, pl.roadPartNumber)).keySet
      val invalidUnchangedLinks = roadNumberAndParts.flatMap(rn => ProjectDAO.getInvalidUnchangedOperationProjectLinks(rn._1, rn._2)).toSeq
      invalidUnchangedLinks.map { projectLink =>
        val point = GeometryUtils.midPointGeometry(projectLink.geometry)
        ValidationErrorDetails(project.id, ValidationErrorList.ErrorInValidationOfUnchangedLinks,
          Seq(projectLink.linkId), Seq(ProjectCoordinates(point.x, point.y, 12)),
          Some("TIE : %d, OSA: %d, AET: %d".format(projectLink.roadNumber, projectLink.roadPartNumber, projectLink.startAddrMValue)))
      }
    }

    val elyCodesResults = checkProjectElyCodes(project, projectLinks)

    def checkTrackCodePairing = {
      checkTrackCode(project, projectLinks)
    }

    checkProjectContinuity ++ checkProjectCoverage ++ checkProjectContinuousSchema ++ checkProjectSharedLinks ++
      checkForContinuityCodes ++ checkForUnsuccessfulRecalculation ++ checkForNotHandledLinks ++ checkForInvalidUnchangedLinks ++ checkTrackCodePairing ++ elyCodesResults ++ checkTerminationContinuity(project, projectLinks)
  }

  def checkRemovedEndOfRoadParts(project: RoadAddressProject): Seq[ValidationErrorDetails] = {
    // Pick only parts that have no length anymore and had end of road given before
    project.reservedParts.filter(rrp => rrp.addressLength.nonEmpty && rrp.newLength.getOrElse(0L) == 0L &&
      rrp.discontinuity.contains(EndOfRoad))

      // There is no EndOfRoad in this project for this road
      .filterNot(rrp => project.reservedParts.exists(np =>
      np.roadNumber == rrp.roadNumber && np.roadPartNumber < rrp.roadPartNumber &&
        np.newLength.getOrElse(0L) > 0L && np.newDiscontinuity.contains(EndOfRoad)))
      .map { rrp =>
        ValidationErrorDetails(project.id, ValidationErrorList.MissingEndOfRoad, Seq(),
          Seq(), Some(s"TIE ${rrp.roadNumber} OSA ${
            project.reservedParts.filter(p => p.roadNumber == rrp.roadNumber &&
              p.newLength.getOrElse(0L) > 0L).map(_.roadPartNumber).max
          }"))
      }
  }

  def checkProjectElyCodes(project: RoadAddressProject, projectLinks: Seq[ProjectLink]): Seq[ValidationErrorDetails] = {
    val workedProjectLinks = projectLinks.filterNot(_.status == LinkStatus.NotHandled)
    if (workedProjectLinks.nonEmpty) {
      val grouped = workedProjectLinks.groupBy(pl => (pl.roadNumber, pl.roadPartNumber)).map(group => group._1 -> group._2.sortBy(_.endAddrMValue))
      val projectLinksDiscontinuity = workedProjectLinks.map(_.discontinuity.value).distinct.toList
      if (projectLinksDiscontinuity.contains(Discontinuity.ChangingELYCode.value))
        firstElyBorderCheck(project, grouped)
      else
        secondElyBorderCheck(project, grouped)
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
    * @param project
    * @param seq
    * @return
    */
  def checkOrdinaryRoadContinuityCodes(project: RoadAddressProject, seq: Seq[ProjectLink]): Seq[ValidationErrorDetails] = {
/*    def error(validationError: ValidationError)(pl: Seq[ProjectLink]) = {
      val (linkIds, points) = pl.map(pl => (pl.linkId, GeometryUtils.midPointGeometry(pl.geometry))).unzip
      if (linkIds.nonEmpty)
        Some(ValidationErrorDetails(project.id, validationError, linkIds,
          points.map(p => ProjectCoordinates(p.x, p.y, 12)), None))
      else
        None
    }*/

    def checkConnectedAreContinuous = {
      error(project.id, ValidationErrorList.ConnectedDiscontinuousLink)(seq.filterNot(pl =>
        // Check that pl is continuous or after it there is no connected project link
        pl.discontinuity == Continuous ||
          !seq.exists(pl2 => pl2.startAddrMValue == pl.endAddrMValue && trackMatch(pl2.track, pl.track) && connected(pl2, pl))
      ))
    }

    def checkNotConnectedHaveMinorDiscontinuity = {
      val possibleDiscontinuous = seq.filterNot { pl =>
        // Check that pl has discontinuity or after it the project links are connected (except last, where forall is true for empty list)
        pl.discontinuity == MinorDiscontinuity ||
          seq.filter(pl2 => pl2.startAddrMValue == pl.endAddrMValue && trackMatch(pl2.track, pl.track)).forall(pl2 => connected(pl2, pl))
      }
      val adjacentRoadAddresses = possibleDiscontinuous.filterNot(pd => {
        val roadsDiscontinuity = RoadAddressDAO.fetchByRoadPart(pd.roadNumber, pd.roadPartNumber)
        val sameDiscontinuity = roadsDiscontinuity.map(_.discontinuity).distinct.contains(pd.discontinuity)
        val overlapping = roadsDiscontinuity.exists(p => GeometryUtils.overlaps((p.startMValue, p.endMValue), (pd.startMValue, pd.endMValue)))
        sameDiscontinuity && overlapping
      })
      error(project.id, ValidationErrorList.MinorDiscontinuityFound)(adjacentRoadAddresses)
    }

    def checkRoadPartEnd(lastProjectLinks: Seq[ProjectLink]): Option[ValidationErrorDetails] = {
      if (lastProjectLinks.exists(_.discontinuity != lastProjectLinks.head.discontinuity))
        error(project.id, ValidationErrorList.IncompatibleDiscontinuityCodes)(lastProjectLinks)
      else {
        val (road, part) = (lastProjectLinks.head.roadNumber, lastProjectLinks.head.roadPartNumber)
        val discontinuity = lastProjectLinks.head.discontinuity
        val ely = lastProjectLinks.head.ely
        val projectNextRoadParts = project.reservedParts.filter(rp =>
          rp.roadNumber == road && rp.roadPartNumber > part)

        val nextProjectPart = projectNextRoadParts.filter(_.newLength.getOrElse(0L) > 0L)
          .map(_.roadPartNumber).sorted.headOption
        val nextAddressPart = RoadAddressDAO.getValidRoadParts(road.toInt, project.startDate)
          .filter(p => p > part || (projectNextRoadParts.nonEmpty && projectNextRoadParts.exists(_.roadPartNumber == p))).sorted.headOption
        if (nextProjectPart.isEmpty && nextAddressPart.isEmpty) {
          if (discontinuity != EndOfRoad)
            return error(project.id, ValidationErrorList.MissingEndOfRoad)(lastProjectLinks)
        } else {
          val nextLinks =
            if (nextProjectPart.nonEmpty && (nextAddressPart.isEmpty || nextProjectPart.get < nextAddressPart.get))
              ProjectDAO.fetchByProjectRoadPart(road, nextProjectPart.get, project.id).filter(_.startAddrMValue == 0L)
            else
              RoadAddressDAO.fetchByRoadPart(road, nextAddressPart.get, includeFloating = true, includeExpired = false, includeHistory = false)
                .filter(_.startAddrMValue == 0L)
          val isConnected = lastProjectLinks.forall(lpl => nextLinks.exists(nl => trackMatch(nl.track, lpl.track) &&
            connected(lpl, nl)))
          val isDisConnected = !lastProjectLinks.exists(lpl => nextLinks.exists(nl => trackMatch(nl.track, lpl.track) &&
            connected(lpl, nl)))
          if (isDisConnected) {
            discontinuity match {
              case Continuous | MinorDiscontinuity =>
                return error(project.id, ValidationErrorList.MajorDiscontinuityFound)(lastProjectLinks)
              case EndOfRoad =>
                return error(project.id, ValidationErrorList.EndOfRoadNotOnLastPart)(lastProjectLinks)
              case _ => // no error, continue
            }
          }
          if (isConnected) {
            discontinuity match {
              case MinorDiscontinuity | Discontinuous =>
                return error(project.id, ValidationErrorList.ConnectedDiscontinuousLink)(lastProjectLinks)
              case EndOfRoad =>
                return error(project.id, ValidationErrorList.EndOfRoadNotOnLastPart)(lastProjectLinks)
              case _ => // no error, continue
            }
          }
        }
        None
      }
    }
    // Checks inside road part (not including last links' checks)
    checkConnectedAreContinuous.toSeq ++ checkNotConnectedHaveMinorDiscontinuity.toSeq ++
      checkRoadPartEnd(seq.filter(r => r.status != LinkStatus.Terminated && r.endAddrMValue == seq.maxBy(_.endAddrMValue).endAddrMValue)).toSeq
  }

  /**
    * Check for ramp and roundabout roads:
    * 1) If inside a part there is a gap between links > .1 meters, discontinuity 4 (minor) is required
    * 2) If inside a part there is no gap, discontinuity 5 (cont) is required
    * 3) End of road part, discontinuity 2 or 3 (major, ely change) is required if there is a gap
    * 4) If a part that contained end of road discontinuity is terminated / renumbered / transferred,
    * there must be a new end of road link for that road at the last part
    * 5) If the next road part has differing ely code then there must be a discontinuity code 3 at the end
    *
    * @param project
    * @param seq
    * @return
    */
  def checkRampContinuityCodes(project: RoadAddressProject, seq: Seq[ProjectLink]): Seq[ValidationErrorDetails] = {
    def isConnectingRoundabout(pls: Seq[ProjectLink]): Boolean = {
      // This code means that this road part (of a ramp) should be connected to a roundabout
      val endPoints = pls.map(endPoint).map(p => (p.x, p.y)).unzip
      val boundingBox = BoundingRectangle(Point(endPoints._1.min,
        endPoints._2.min), Point(endPoints._1.max, endPoints._2.max))
      // Fetch all ramps and roundabouts roads and parts this is connected to (or these, if ramp has multiple links)
      val roadParts = RoadAddressDAO.fetchRoadAddressesByBoundingBox(boundingBox, fetchOnlyFloating = false, onlyNormalRoads = false,
        Seq((RampsMinBound, RampsMaxBound))).filter(ra =>
        pls.exists(pl => connected(pl, ra))).groupBy(ra => (ra.roadNumber, ra.roadPartNumber))

      // Check all the fetched road parts to see if any of them is a roundabout
      roadParts.keys.exists(rp => TrackSectionOrder.isRoundabout(
        RoadAddressDAO.fetchByRoadPart(rp._1, rp._2, includeFloating = true)))

    }

    def endPoint(b: BaseRoadAddress) = {
      b.sideCode match {
        case TowardsDigitizing => b.geometry.last
        case AgainstDigitizing => b.geometry.head
        case _ => Point(0.0, 0.0)
      }
    }

    def errorWithInfo(validationError: ValidationError, info: String = "N/A")(pl: Seq[ProjectLink]) = {
      val (linkIds, points) = pl.map(pl => (pl.linkId, GeometryUtils.midPointGeometry(pl.geometry))).unzip
      if (linkIds.nonEmpty)
        Some(ValidationErrorDetails(project.id, validationError, linkIds,
          points.map(p => ProjectCoordinates(p.x, p.y, 12)), Some(info)))
      else
        None
    }

    def checkDiscontinuityBetweenLinks = {
      error(project.id, ValidationErrorList.DiscontinuityOnRamp)(seq.filter { pl =>
        // Check that pl has no discontinuity unless on last link and after it the possible project link is connected
        val nextLink = seq.find(pl2 => pl2.startAddrMValue == pl.endAddrMValue)
        (nextLink.nonEmpty && pl.discontinuity != Continuous) ||
          nextLink.exists(pl2 => !connected(pl2, pl))
      })
    }

    def checkRoadPartEnd(lastProjectLinks: Seq[ProjectLink]): Option[ValidationErrorDetails] = {
      val (road, part) = (lastProjectLinks.head.roadNumber, lastProjectLinks.head.roadPartNumber)
      val discontinuity = lastProjectLinks.head.discontinuity
      val ely = lastProjectLinks.head.ely
      val projectNextRoadParts = project.reservedParts.filter(rp =>
        rp.roadNumber == road && rp.roadPartNumber > part)

      val nextProjectPart = projectNextRoadParts.filter(_.newLength.getOrElse(0L) > 0L)
        .map(_.roadPartNumber).sorted.headOption
      val nextAddressPart = RoadAddressDAO.getValidRoadParts(road.toInt, project.startDate).filter(_ > part)
        .filterNot(p => projectNextRoadParts.exists(_.roadPartNumber == p)).sorted.headOption
      if (nextProjectPart.isEmpty && nextAddressPart.isEmpty) {
        if (discontinuity != EndOfRoad)
          return error(project.id, ValidationErrorList.MissingEndOfRoad)(lastProjectLinks)
      } else {
        val nextLinks =
          if (nextProjectPart.nonEmpty && (nextAddressPart.isEmpty || nextProjectPart.get < nextAddressPart.get))
            ProjectDAO.fetchByProjectRoadPart(road, nextProjectPart.get, project.id).filter(_.startAddrMValue == 0L)
          else
            RoadAddressDAO.fetchByRoadPart(road, nextAddressPart.get, includeFloating = true, includeExpired = false, includeHistory = false)
              .filter(_.startAddrMValue == 0L)

        if (isConnectingRoundabout(lastProjectLinks)) {
          discontinuity match {
            case EndOfRoad | Discontinuous | ChangingELYCode | Continuous =>
              return errorWithInfo(ValidationErrorList.RampConnectingRoundabout, s"Rampin ${lastProjectLinks.head.roadNumber} tieosa ${lastProjectLinks.head.roadPartNumber} päättyy kiertoliittymään. Korjaa lievä epäjatkuvuus")(lastProjectLinks)
            case _ =>
          }
        }

        val isConnected = lastProjectLinks.forall(lpl => nextLinks.exists(nl => connected(lpl, nl)))
        val isDisConnected = !lastProjectLinks.exists(lpl => nextLinks.exists(nl => connected(lpl, nl)))
        if (isDisConnected) {
          discontinuity match {
            case MinorDiscontinuity =>
              // This code means that this road part (of a ramp) should be connected to a roundabout
              val endPoints = lastProjectLinks.map(endPoint).map(p => (p.x, p.y)).unzip
              val boundingBox = BoundingRectangle(Point(endPoints._1.min,
                endPoints._2.min), Point(endPoints._1.max, endPoints._2.max))
              // Fetch all ramps and roundabouts roads and parts this is connected to (or these, if ramp has multiple links)
              val roadParts = RoadAddressDAO.fetchRoadAddressesByBoundingBox(boundingBox, fetchOnlyFloating = false, onlyNormalRoads = false,
                Seq((RampsMinBound, RampsMaxBound))).filter(ra =>
                lastProjectLinks.exists(pl => connected(pl, ra))).groupBy(ra => (ra.roadNumber, ra.roadPartNumber))

              // Check all the fetched road parts to see if any of them is a roundabout
              if (!roadParts.keys.exists(rp => TrackSectionOrder.isRoundabout(
                RoadAddressDAO.fetchByRoadPart(rp._1, rp._2, includeFloating = true))))
                return error(project.id, ValidationErrorList.DiscontinuityOnRamp)(lastProjectLinks)
            case Continuous =>
              return error(project.id, ValidationErrorList.MajorDiscontinuityFound)(lastProjectLinks)
            case EndOfRoad =>
              return error(project.id, ValidationErrorList.EndOfRoadNotOnLastPart)(lastProjectLinks)
            case _ => // no error, continue
          }
        }
        if (isConnected) {
          discontinuity match {
            case MinorDiscontinuity | Discontinuous =>
              return error(project.id, ValidationErrorList.ConnectedDiscontinuousLink)(lastProjectLinks)
            case EndOfRoad =>
              return error(project.id, ValidationErrorList.EndOfRoadNotOnLastPart)(lastProjectLinks)
            case _ => // no error, continue
          }
        }
      }
      None
    }
    // Checks inside road part (not including last links' checks)
    checkDiscontinuityBetweenLinks.toSeq ++
      checkRoadPartEnd(seq.filter(r => r.status != LinkStatus.Terminated && r.endAddrMValue == seq.maxBy(_.endAddrMValue).endAddrMValue)).toSeq
  }


  def checkTrackCode(project: RoadAddressProject, projectLinks: Seq[ProjectLink]): Seq[ValidationErrorDetails] = {

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
        if (!notCombinedLinks.exists(l => l.startAddrMValue == minTrackLink.startAddrMValue && l.track != minTrackLink.track)) {
          Some(minTrackLink)
        }
        else if (!notCombinedLinks.exists(l => l.endAddrMValue == maxTrackLink.endAddrMValue && l.track != maxTrackLink.track)) {
          Some(maxTrackLink)
        } else None
      } else None
    }

    def validateTrackTopology(trackInterval: Seq[ProjectLink]): Seq[ProjectLink] = {
      checkMinMaxTrack(trackInterval) match {
        case Some(link) => Seq(link)
        case None => {
          trackInterval.sliding(2).map(l => {
            if (l.head.endAddrMValue != l.last.startAddrMValue) {
              Some(l.head)
            } else None
          }).toSeq.flatten
        }
      }
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

    val groupedLinks = projectLinks.groupBy(pl => (pl.roadNumber, pl.roadPartNumber))
    groupedLinks.map(roadPart => {
      recursiveCheckTrackChange(roadPart._2) match {
        case Some(errors) => Seq(errors)
        case _ => Seq()
      }
    }).headOption.getOrElse(Seq())
  }

  /**
    * When terminating any road part, this will validate if the discontinuity of adjacent road addresses or project links.
    * Discontinuty of roads adjacent to terminated must be End Of Road, if not, this method will inform the user places where this does not happen.
    *
    * @param project         The current project to validate
    * @param allProjectLinks All the project links associated with the project in question
    * @return A sequence with ValidationErrorDetails, can be empty.
    */
  def checkTerminationContinuity(project: RoadAddressProject, allProjectLinks: Seq[ProjectLink]): Seq[ValidationErrorDetails] = {
    /**
      * Method that will prepare the output of the validation error.
      *
      * @param validationError the validation error
      * @param roadAddressLike Sequence of the BaseRoadAddresses that did not passed validation
      * @return An optional value with eventual Validation error details
      */
    def error(validationError: ValidationError)(roadAddressLike: Seq[BaseRoadAddress]): Option[ValidationErrorDetails] = {
      val groupedByRoadAndPartNumber = roadAddressLike.groupBy(p => (p.roadNumber, p.roadPartNumber))
      val (gLinkIds, gPoints, roadAndPart) = groupedByRoadAndPartNumber.flatMap(g => {
        val (road, part) = g._1
        val links = g._2
        val zoomPoint = GeometryUtils.midPointGeometry(links.minBy(_.endAddrMValue).geometry)
        links.map(l => (l.linkId, zoomPoint, (road, part)))
      }).unzip3

      if (gLinkIds.nonEmpty)
        Some(ValidationErrorDetails(project.id, alterMessage(validationError, Option.empty, Option(roadAndPart.toSeq)), gLinkIds.toSeq.distinct,
          gPoints.map(p => ProjectCoordinates(p.x, p.y, 12)).toSeq.distinct, Option.empty[String]))
      else
        Option.empty[ValidationErrorDetails]
    }

    /**
      * Will find the endPoints (lowest and highest endAddressM respectively) and run the standard validation to them
      *
      * @param groupedProjectLinks Project Links groupped by Road Number and Road Part Number
      * @return Well formed validation error objects, if applicable
      */
    def checkEndpoints(groupedProjectLinks: Seq[ProjectLink]) = {
      //Find endpoints, start and end
      val startRoad = groupedProjectLinks.head
      val endRoad = groupedProjectLinks.last
      val anomalousAtBorders = checkValidation(startRoad, endRoad)
      /*
      We check the possible anomalous roads if they are:
        Adjacent
        Share the same track code and road number
        Are continuous
      If we have all those conditions then we discard them for they are not anomalous then it means that those roads are in the same road address number and
      only changed part, therefore there is no logic in defining their discontinuity as "End of Road".
       */
      val grouped = anomalousAtBorders.groupBy(anom => {
        (anom.roadNumber, anom.track, anom.discontinuity)
      })
      val (groupedTrueAnomalous, possibleAnomalous) = grouped.partition(_._2.length <= 1)
      val trueAnomalous = groupedTrueAnomalous.values.flatten.toSeq
      val remaining = possibleAnomalous.mapValues(addresses => {
        addresses.sliding(2).toSeq.filterNot(a => {
          GeometryUtils.areAdjacent(a.head.geometry, a.last.geometry)
        }).flatten
      }).values.flatten.toSeq


      error(ValidationErrorList.TerminationContinuity)(trueAnomalous ++ remaining)
    }

    /**
      * Main validation method, logic will find all the road addresses by bounding box surrounding the edge of the projctLink.
      * Will remove from validation the road addresses that possess the same road number and road part number, are non-adjacent and whose discontinuity is not EndOfRoad
      * If any road address survives the removal process then it is a anomalous and must be reported as such.
      * We also search the whole project link list for the same "anomalous" linkIds and run them through the same process, if any returns then it is also deemed "anomalous" and will be reported
      *
      * @param startRoad A project link, at the start of it (lowest endAddressMValue)
      * @param endRoad A project link, at the end of it(highest endAddressMValue)
      * @return the anomalous base road addresses
      */
    def checkValidation(startRoad: ProjectLink, endRoad: ProjectLink): Seq[BaseRoadAddress] = {
      val roadAddresses = findRoads(startRoad, endRoad)
      if (roadAddresses.nonEmpty) {
        //I am aware that in THEORY this filters could be fused into one, unifying the filter clauses with a && operator
        // but it just does not work, you can try it, but using the Viite1120Test project (devtest) it will output 4 entries while the correct result should be only one entry
        val numberAndPartFilter = roadAddresses.filterNot(ra => {
          ra.roadNumber == startRoad.roadNumber && ra.roadPartNumber == startRoad.roadPartNumber
        })
        val onlyAdjacents = numberAndPartFilter.filter(tf => {
          GeometryUtils.areAdjacent(tf.geometry, startRoad.geometry) || GeometryUtils.areAdjacent(tf.geometry, endRoad.geometry)
        })
        val projectLinkIds = projectLinksByIds(startRoad.projectId, onlyAdjacents.map(_.id).toSet)

        onlyAdjacents.map{ra =>
          ra.copy(discontinuity = projectLinkIds.find(_.roadAddressId == ra.id).getOrElse(ra).discontinuity)
        }.filterNot(_.discontinuity == Discontinuity.EndOfRoad)
      } else Seq.empty[RoadAddress]
    }

    val terminatedProjectLinks = allProjectLinks.filter(_.status.value == LinkStatus.Terminated.value)
    if (terminatedProjectLinks.nonEmpty) {
      val grouped = terminatedProjectLinks.groupBy(p => (p.roadNumber, p.roadPartNumber))
      val t = grouped.map(g => {
        checkEndpoints(g._2.sortBy(_.startAddrMValue))
      })
      t.filter(_.nonEmpty).map(_.get).toSeq
    } else Seq.empty[ValidationErrorDetails]
  }

  private def projectLinksByIds(projectId: Long, ids: Set[Long]): Seq[ProjectLink] = {
    ProjectDAO.getProjectLinksByIds(projectId, ids)
  }

  private def connected(pl1: BaseRoadAddress, pl2: BaseRoadAddress) = {
    GeometryUtils.areAdjacent(pl1.geometry, pl2.geometry, fi.liikennevirasto.viite.MaxDistanceForConnectedLinks)
  }

  private def trackMatch(track1: Track, track2: Track) = {
    track1 == track2 || track1 == Track.Combined || track2 == Track.Combined
  }

  /**
    * Check the project links edges for adjacent road addresses that must have a different ely then a adjacent one
    *
    * @param project             - the current project
    * @param groupedProjectLinks - project links, grouped by road number and road part number
    * @return Validation Errors
    */
  private def firstElyBorderCheck(project: RoadAddressProject, groupedProjectLinks: Map[(Long, Long), Seq[ProjectLink]]) = {
    /**
      * Method that will prepare the output of the validation error.
      *
      * @param validationError the validation error
      * @param pl              Sequence of the erroneous ProjectLinks
      * @return An optional value with eventual Validation error details
      */
    def error(validationError: ValidationError)(pl: Seq[BaseRoadAddress]): Option[ValidationErrorDetails] = {
      val (linkIds, points) = pl.map(pl => (pl.linkId, GeometryUtils.midPointGeometry(pl.geometry))).unzip
      if (linkIds.nonEmpty)
        Some(ValidationErrorDetails(project.id, validationError, linkIds.distinct,
          points.map(p => ProjectCoordinates(p.x, p.y, 12)).distinct, None))
      else
        None
    }

    groupedProjectLinks.flatMap(group => {
      val projectLinks = group._2.filter(_.discontinuity == Discontinuity.ChangingELYCode)
      val startRoad = projectLinks.head
      val endRoad = projectLinks.last
      val roadsValidation = evaluateBorderCheck(startRoad, endRoad, secondCheck = false)
      val problemRoads = roadsValidation.filterNot(_.isEmpty).getOrElse(Seq())
      error(ValidationErrorList.RoadNotEndingInElyBorder)(problemRoads)
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
  private def secondElyBorderCheck(project: RoadAddressProject, groupedProjectLinks: Map[(Long, Long), Seq[ProjectLink]]) = {
    /**
      * Method that will prepare the output of the validation error.
      *
      * @param validationError the validation error
      * @param pl              Sequence of the erroneous ProjectLinks
      * @return An optional value with eventual Validation error details
      */
    def error(validationError: ValidationError)(pl: Seq[BaseRoadAddress]): Option[ValidationErrorDetails] = {
      val grouppedByEly = pl.groupBy(_.ely)
      val (gLinkIds, gPoints, gEly) = grouppedByEly.flatMap(g => {
        val links = g._2
        val zoomPoint = GeometryUtils.midPointGeometry(links.minBy(_.endAddrMValue).geometry)
        links.map(l => (l.linkId, zoomPoint, l.ely))
      }).unzip3

      if (gLinkIds.nonEmpty) {
        if(gEly.nonEmpty){
          Some(ValidationErrorDetails(project.id, alterMessage(validationError,Option(gEly.toSeq)), gLinkIds.toSeq.distinct,
            gPoints.map(p => ProjectCoordinates(p.x, p.y, 12)).toSeq.distinct, Option.empty[String]))
        } else Some(ValidationErrorDetails(project.id, validationError, gLinkIds.toSeq.distinct,
          gPoints.map(p => ProjectCoordinates(p.x, p.y, 12)).toSeq.distinct, Option.empty[String]))
    }
      else
        Option.empty[ValidationErrorDetails]
    }

    val validationProblems = groupedProjectLinks.flatMap(group => {
      val projectLinks = group._2
      val startRoad = projectLinks.head
      val endRoad = projectLinks.last
      val validationResult = if (startRoad.discontinuity.value != Discontinuity.ChangingELYCode.value) evaluateBorderCheck(startRoad, endRoad, secondCheck = true) else Option.empty[Seq[ProjectLink]]

      val problemRoads = validationResult.filterNot(_.isEmpty).getOrElse(Seq())
      error(ValidationErrorList.RoadContinuesInAnotherEly)(problemRoads)

    })
    validationProblems.toSeq
  }

  private def alterMessage(validationError: ValidationError, elyBorderData: Option[Seq[Long]] = Option.empty[Seq[Long]], roadAndPart: Option[Seq[(Long,Long)]] = Option.empty[Seq[(Long,Long)]]) = {
    case object formattedMessage extends ValidationError {
      def value = validationError.value
      def message: String = validationError.message.format(if(elyBorderData.nonEmpty) {
        elyBorderData.get.toSet.mkString(", ")
      } else if(roadAndPart.nonEmpty){
        roadAndPart.get.toSet.mkString(", ")
      } else {
        validationError.message
      })
      def notification = validationError.notification
    }
    formattedMessage
  }

  /**
    * Helper method, will find ALL the road addresses in a bounding box whose center is the edge road
    *
    * @param headRoad  A project link, at the start of it (lowest endAddressMValue)
    * @param tailRoad  A project link, at the end of it(highest endAddressMValue)
    * @return Road addresses contained in a small bounding box
    */
  private def findRoads(headRoad: ProjectLink, tailRoad: ProjectLink) = {
    val sp = startPoint(headRoad)
    val ep = endPoint(tailRoad)
    val connectingAtStart = {
    val lowerCorner = Point(sp.x - distanceToPoint, sp.y - distanceToPoint, sp.z - distanceToPoint)
    val higherCorner = Point(sp.x + distanceToPoint, sp.y + distanceToPoint, sp.z + distanceToPoint)
    val box = BoundingRectangle(lowerCorner, higherCorner)
    RoadAddressDAO.fetchRoadAddressesByBoundingBox(box, fetchOnlyFloating = false)
    }
    val connectingAtEnd = {
      val lowerCorner = Point(ep.x - distanceToPoint, ep.y - distanceToPoint, ep.z - distanceToPoint)
      val higherCorner = Point(ep.x + distanceToPoint, ep.y + distanceToPoint, ep.z + distanceToPoint)
      val box = BoundingRectangle(lowerCorner, higherCorner)
      RoadAddressDAO.fetchRoadAddressesByBoundingBox(box, fetchOnlyFloating = false)
    }
    connectingAtStart ++ connectingAtEnd
  }

  /**
    * Main validation we create a bounding box and search for adjacent road addresses to the edgeRoad.
    * Then check if the ely code changed between them, depending whether we are validation the firstBorderCheck or the
    * second we output the edgeRoad based on the finding (or not) of a road address with a different ely code then that of the edgeRoad.
    *
    * @param startRoad    - start of a road number/road part number project link
    * @param endRoad    - end of a road number/road part number project link
    * @param secondCheck - indicates what kind of search we use
    * @return an optional symbolizing a found invalid edgeRoad, or nothing.
    */
  private def evaluateBorderCheck(startRoad: ProjectLink, endRoad: ProjectLink, secondCheck: Boolean): Option[Seq[ProjectLink]] = {
    val roadAddresses = findRoads(startRoad, endRoad)
    if (roadAddresses.nonEmpty) {
      val filtered = roadAddresses.filterNot(ra => ra.roadNumber == startRoad.roadNumber && ra.roadPartNumber == startRoad.roadPartNumber &&
        !GeometryUtils.areAdjacent(ra.geometry, startRoad.geometry) && !GeometryUtils.areAdjacent(ra.geometry, endRoad.geometry))
      val diffEly = filtered.find(_.ely != startRoad.ely)
      if (!secondCheck && diffEly.isEmpty) {
        Option(Seq(startRoad, endRoad))
      } else if (secondCheck && diffEly.isDefined) {
        Option(Seq(startRoad, endRoad))
      } else Option.empty[Seq[ProjectLink]]
    } else Option.empty[Seq[ProjectLink]]
  }
}

class ProjectValidationException(s: String) extends RuntimeException {
  override def getMessage: String = s
}

package fi.liikennevirasto.viite.process

import fi.liikennevirasto.viite.dao.ProjectCalibrationPointDAO.UserDefinedCalibrationPoint
import fi.liikennevirasto.viite.dao.ProjectLink
import fi.vaylavirasto.viite.geometry.Point
import fi.vaylavirasto.viite.model.{AddrMRange, RoadAddressChangeType, Track}

object ProjectSectionMValueCalculator {

  /**
   * Calculates address values for project links on a track based on calibration points.
   *
   * This function processes a sequence of project links, ensuring proper address measure continuity and alignment.
   *
   * @param projectLinks               Sequence of project links to be processed.
   * @param calibrationPoints Map containing calibration points, where keys are link IDs.
   * @return Sequence of project links with calculated address measure values.
   * @throws InvalidAddressDataException if the input data violates address measure constraints.
   */
  def calculateAddressMValuesForTrack(projectLinks: Seq[ProjectLink], calibrationPoints: Map[Long, UserDefinedCalibrationPoint]): Seq[ProjectLink] = {
    // That is an address connected extension of this
    def isExtensionOf(ext: ProjectLink)(thisPL: ProjectLink) = {
      thisPL.originalAddrMRange.end == ext.originalAddrMRange.start &&
        (thisPL.track == ext.track || Set(thisPL.track, ext.track).contains(Track.Combined))
    }

    // Reset the end address measure if have changed
    def resetEndAddrMValue(pl: ProjectLink): ProjectLink = {
      val endAddrMValue = pl.addrMRange.start + pl.addrMRange.length
      if (endAddrMValue != pl.addrMRange.end)
        pl.copy(addrMRange = AddrMRange(pl.addrMRange.start, endAddrMValue))
      else
        pl
    }

    // Group all consecutive links with same status
    val (unchanged, others) = projectLinks.partition(_.status == RoadAddressChangeType.Unchanged)
    val mapped = unchanged.groupBy(_.addrMRange.start)
    if (mapped.values.exists(_.size != 1)) {
      throw new InvalidAddressDataException(s"Multiple unchanged links specified with overlapping address value ${mapped.values.filter(_.size != 1).mkString(", ")}")
    }
    if (unchanged.nonEmpty && mapped.keySet.count(_ == 0L) != 1)
      throw new InvalidAddressDataException("No starting point (Address = 0) found for UnChanged links")
    if (!unchanged.forall(
      pl => {
        val previousLinks = unchanged.filter(isExtensionOf(pl))
        previousLinks.size match {
          case 0 => pl.addrMRange.isRoadPartStart // Does not connect from start; must be a road part start.
          case 1 => true                          // Connects to a single project link from start - this is fine.
          case 2 => pl.track == Track.Combined && // Connects to two project links - must be a combined track, and connect to one left ...
                    previousLinks.map(_.track).toSet == Set(Track.LeftSide, Track.RightSide) // ...side, and one right side project link.
          case _ => false // If there is more than two links connecting from the start, this is a problem
        }
      }))
      throw new InvalidAddressDataException(s"Invalid unchanged link found")
    unchanged.map(resetEndAddrMValue) ++ assignLinkValues(others, calibrationPoints, unchanged.map(_.addrMRange.end.toDouble).sorted.lastOption)
  }

  def isSameTrack(previous: ProjectLink, currentLink: ProjectLink): Boolean = {
    previous.roadPart == currentLink.roadPart && previous.track == currentLink.track
  }
  private def orderEndPoints(firstLink: ProjectLink, onceConnected: ProjectLink, unConnectedEndPoint: Point, tripleConnectionEndPoint: Point) = {
    if (firstLink.id == onceConnected.id)
      (unConnectedEndPoint, tripleConnectionEndPoint)
    else
      (tripleConnectionEndPoint, unConnectedEndPoint)
  }

  /**
   * Assigns address values to project links based on their geometry and calibration points.
   *
   * @param projectLinks    Sequence of project links to be processed.
   * @param calibrationPointMap    Map containing calibration points, where keys are link IDs.
   * @param addrSt Optional starting address value. If not provided, defaults to 0.0.
   * @param coEff  Coefficient to adjust the address values based on geometry length. Defaults to 1.0.
   * @return Sequence of project links with assigned address values.
   */
  def assignLinkValues(projectLinks: Seq[ProjectLink], calibrationPointMap: Map[Long, UserDefinedCalibrationPoint], addrSt: Option[Double], coEff: Double = 1.0): Seq[ProjectLink] = {
    if (projectLinks.isEmpty)
      Seq()
    else {
      val ordered =
        if (projectLinks.exists(pl => pl.isNotCalculated)) {

          val seqOfEnds = TrackSectionOrder.findSinglyConnectedLinks(projectLinks).values.toSeq // Some complex cases may need simplifying to find ends correctly.

          val endPoints = if (projectLinks.exists(pl => pl.addrMRange.end == 0)) TrackSectionOrder.findChainEndpoints(projectLinks) else  TrackSectionOrder.findChainEndpoints(seqOfEnds)

        val firstLastEndpoint = (endPoints.head._1, endPoints.last._1)

        val mappedEndpoints   = if (seqOfEnds.size == 1) {
            val firstEndPoint  = TrackSectionOrder.getUnConnectedPoint(projectLinks)
            val secondEndPoint = TrackSectionOrder.getTripleConnectionPoint(projectLinks)
            if (firstEndPoint.isDefined && secondEndPoint.isDefined) orderEndPoints(projectLinks.head, seqOfEnds.head, firstEndPoint.get, secondEndPoint.get) else firstLastEndpoint
          } else
            firstLastEndpoint
          val orderedPairs      = TrackSectionOrder.orderProjectLinksTopologyByGeometry(mappedEndpoints, projectLinks)
          if (projectLinks.exists(_.track == Track.RightSide || projectLinks.forall(_.track == Track.Combined))) orderedPairs._1 else orderedPairs._2.reverse
      } else
          projectLinks

      val newAddressValues = ordered.scanLeft(addrSt.getOrElse(0.0)) {
        case (m, pl) =>
          val someCalibrationPoint: Option[UserDefinedCalibrationPoint] = calibrationPointMap.get(pl.id)
          pl.status match {
            case RoadAddressChangeType.New => if (someCalibrationPoint.nonEmpty) someCalibrationPoint.get.addressMValue else m + Math.abs(pl.geometryLength) * coEff
            case RoadAddressChangeType.Transfer | RoadAddressChangeType.NotHandled | RoadAddressChangeType.Renumeration | RoadAddressChangeType.Unchanged => m + (pl.originalAddrMRange.length)
            case RoadAddressChangeType.Termination => pl.addrMRange.end
            case _ => throw new InvalidAddressDataException(s"Invalid status found at value assignment ${pl.status}, linkId: ${pl.linkId}")
          }
      }
      projectLinks.zip(newAddressValues.zip(newAddressValues.tail)).map { case (pl, (st, en)) => pl.copy(addrMRange = AddrMRange(Math.round(st), Math.round(en)))
      }
    }
  }

  def calculateAddressingFactors(seq: Seq[ProjectLink]): TrackAddressingFactors = {
    seq.foldLeft[TrackAddressingFactors](TrackAddressingFactors(0, 0, 0.0)) { case (a, pl) =>
      pl.status match {
        case RoadAddressChangeType.Unchanged | RoadAddressChangeType.Renumeration => a.copy(unChangedLength = a.unChangedLength + pl.addrMRange.length)
        case RoadAddressChangeType.Transfer  | RoadAddressChangeType.NotHandled   => a.copy(transferLength  = a.transferLength  + pl.addrMRange.length)
        case RoadAddressChangeType.New => a.copy(newLength = a.newLength + pl.geometryLength)
        case RoadAddressChangeType.Termination => a
        case _ => throw new InvalidAddressDataException(s"Invalid status found at factor assignment ${pl.status}, linkId: ${pl.linkId}")
      }
    }
  }
}

case class TrackAddressingFactors(unChangedLength: Long, transferLength: Long, newLength: Double)

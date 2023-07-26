package fi.liikennevirasto.viite.process

import fi.liikennevirasto.viite.dao.ProjectCalibrationPointDAO.UserDefinedCalibrationPoint
import fi.liikennevirasto.viite.dao.ProjectLink
import fi.vaylavirasto.viite.geometry.Point
import fi.vaylavirasto.viite.model.{RoadAddressChangeType, Track}

object ProjectSectionMValueCalculator {

  def calculateMValuesForTrack(seq: Seq[ProjectLink], calibrationPoints: Map[Long, UserDefinedCalibrationPoint]): Seq[ProjectLink] = {
    // That is an address connected extension of this
    def isExtensionOf(ext: ProjectLink)(thisPL: ProjectLink) = {
      thisPL.originalEndAddrMValue == ext.originalStartAddrMValue &&
        (thisPL.track == ext.track || Set(thisPL.track, ext.track).contains(Track.Combined))
    }

    // Reset the end address measure if have changed
    def resetEndAddrMValue(pl: ProjectLink): ProjectLink = {
      val endAddrMValue = pl.startAddrMValue + pl.addrMLength
      if (endAddrMValue != pl.endAddrMValue)
        pl.copy(endAddrMValue = endAddrMValue)
      else
        pl
    }

    // Group all consecutive links with same status
    val (unchanged, others) = seq.partition(_.status == RoadAddressChangeType.Unchanged)
    val mapped = unchanged.groupBy(_.startAddrMValue)
    if (mapped.values.exists(_.size != 1)) {
      throw new InvalidAddressDataException(s"Multiple unchanged links specified with overlapping address value ${mapped.values.filter(_.size != 1).mkString(", ")}")
    }
    if (unchanged.nonEmpty && mapped.keySet.count(_ == 0L) != 1)
      throw new InvalidAddressDataException("No starting point (Address = 0) found for UnChanged links")
    if (!unchanged.forall(
      pl => {
        val previousLinks = unchanged.filter(isExtensionOf(pl))
        previousLinks.size match {
          case 0 => pl.startAddrMValue == 0
          case 1 => true
          case 2 => pl.track == Track.Combined && previousLinks.map(_.track).toSet == Set(Track.LeftSide, Track.RightSide)
          case _ => false
        }
      }))
      throw new InvalidAddressDataException(s"Invalid unchanged link found")
    unchanged.map(resetEndAddrMValue) ++ assignLinkValues(others, calibrationPoints, unchanged.map(_.endAddrMValue.toDouble).sorted.lastOption, None)
  }

  def isSameTrack(previous: ProjectLink, currentLink: ProjectLink): Boolean = {
    previous.roadNumber == currentLink.roadNumber && previous.roadPartNumber == currentLink.roadPartNumber && previous.track == currentLink.track
  }
  private def orderEndPoints(firstLink: ProjectLink, onceConnected: ProjectLink, unConnectedEndPoint: Point, tripleConnectionEndPoint: Point) = {
    if (firstLink.id == onceConnected.id)
      (unConnectedEndPoint, tripleConnectionEndPoint)
    else
      (tripleConnectionEndPoint, unConnectedEndPoint)
  }

  def assignLinkValues(seq: Seq[ProjectLink], cps: Map[Long, UserDefinedCalibrationPoint], addrSt: Option[Double], addrEn: Option[Double], coEff: Double = 1.0): Seq[ProjectLink] = {
    if (seq.isEmpty) Seq() else {
      val ordered = if (seq.exists(pl => pl.isNotCalculated)) {
        val seqOfEnds = TrackSectionOrder.findOnceConnectedLinks(seq).values.toSeq // Some complex cases may need simplifying to find ends correctly.
        val endPoints = if (seq.exists(pl => pl.endAddrMValue == 0)) TrackSectionOrder.findChainEndpoints(seq) else  TrackSectionOrder.findChainEndpoints(seqOfEnds)
        val firstLastEndpoint = (endPoints.head._1, endPoints.last._1)
        val mappedEndpoints   = if (seqOfEnds.size == 1) {
          val firstEndPoint  = TrackSectionOrder.getUnConnectedPoint(seq)
          val secondEndPoint = TrackSectionOrder.getTripleConnectionPoint(seq)
          if (firstEndPoint.isDefined && secondEndPoint.isDefined) orderEndPoints(seq.head, seqOfEnds.head, firstEndPoint.get, secondEndPoint.get) else firstLastEndpoint
        } else firstLastEndpoint
        val orderedPairs      = TrackSectionOrder.orderProjectLinksTopologyByGeometry(mappedEndpoints, seq)
        if (seq.exists(_.track == Track.RightSide || seq.forall(_.track == Track.Combined))) orderedPairs._1 else orderedPairs._2.reverse
      } else seq

      val newAddressValues = ordered.scanLeft(addrSt.getOrElse(0.0)) {
        case (m, pl) =>
          val someCalibrationPoint: Option[UserDefinedCalibrationPoint] = cps.get(pl.id)
          pl.status match {
            case RoadAddressChangeType.New => if (someCalibrationPoint.nonEmpty) someCalibrationPoint.get.addressMValue else m + Math.abs(pl.geometryLength) * coEff
            case RoadAddressChangeType.Transfer | RoadAddressChangeType.NotHandled | RoadAddressChangeType.Renumeration | RoadAddressChangeType.Unchanged => m + (pl.originalEndAddrMValue - pl.originalStartAddrMValue)
            case RoadAddressChangeType.Termination => pl.endAddrMValue
            case _ => throw new InvalidAddressDataException(s"Invalid status found at value assignment ${pl.status}, linkId: ${pl.linkId}")
          }
      }
      seq.zip(newAddressValues.zip(newAddressValues.tail)).map { case (pl, (st, en)) => pl.copy(startAddrMValue = Math.round(st), endAddrMValue = Math.round(en))
      }
    }
  }

  def calculateAddressingFactors(seq: Seq[ProjectLink]): TrackAddressingFactors = {
    seq.foldLeft[TrackAddressingFactors](TrackAddressingFactors(0, 0, 0.0)) { case (a, pl) =>
      pl.status match {
        case RoadAddressChangeType.Unchanged | RoadAddressChangeType.Renumeration => a.copy(unChangedLength = a.unChangedLength + pl.addrMLength)
        case RoadAddressChangeType.Transfer | RoadAddressChangeType.NotHandled => a.copy(transferLength = a.transferLength + pl.addrMLength)
        case RoadAddressChangeType.New => a.copy(newLength = a.newLength + pl.geometryLength)
        case RoadAddressChangeType.Termination => a
        case _ => throw new InvalidAddressDataException(s"Invalid status found at factor assignment ${pl.status}, linkId: ${pl.linkId}")
      }
    }
  }
}

case class TrackAddressingFactors(unChangedLength: Long, transferLength: Long, newLength: Double)

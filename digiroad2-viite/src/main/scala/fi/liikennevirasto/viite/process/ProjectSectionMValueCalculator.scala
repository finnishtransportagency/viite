package fi.liikennevirasto.viite.process

import fi.liikennevirasto.digiroad2.util.Track
import fi.liikennevirasto.viite.dao.LinkStatus._
import fi.liikennevirasto.viite.dao.ProjectCalibrationPointDAO.UserDefinedCalibrationPoint
import fi.liikennevirasto.viite.dao.{LinkStatus, ProjectLink, ProjectLinkDAO}

object ProjectSectionMValueCalculator {

  val projectLinkDAO = new ProjectLinkDAO

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
    val (unchanged, others) = seq.partition(_.status == LinkStatus.UnChanged)
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
def testF(ordered: Seq[ProjectLink], addrSt: Option[Double], cps: Map[Long, UserDefinedCalibrationPoint], coEff: Double) = {
  ordered.scanLeft(addrSt.getOrElse(0.0)) { case (m, pl) =>
    val someCalibrationPoint: Option[UserDefinedCalibrationPoint] = cps.get(pl.id)
    if (!pl.isSplit) {
      val addressValue = if (someCalibrationPoint.nonEmpty) someCalibrationPoint.get.addressMValue else m + Math.abs(pl.geometryLength) * coEff
      pl.status match {
        case LinkStatus.New => addressValue
        case LinkStatus.Transfer | LinkStatus.NotHandled | LinkStatus.Numbering | LinkStatus.UnChanged => m + pl.addrMOriginalLength
        case LinkStatus.Terminated => pl.endAddrMValue
        case _ => throw new InvalidAddressDataException(s"Invalid status found at value assignment ${pl.status}, linkId: ${pl.linkId}")
      }
    } else {
      pl.endAddrMValue
    }
  }
}
  def isSameTrack(previous: ProjectLink, currentLink: ProjectLink): Boolean = {
    previous.roadNumber == currentLink.roadNumber && previous.roadPartNumber == currentLink.roadPartNumber && previous.track == currentLink.track
  }

  def assignLinkValues(seq: Seq[ProjectLink], cps: Map[Long, UserDefinedCalibrationPoint], addrSt: Option[Double], addrEn: Option[Double], coEff: Double = 1.0): Seq[ProjectLink] = {
    val endPoints = TrackSectionOrder.findChainEndpoints(seq)
    val mappedEndpoints = (endPoints.head._1, endPoints.last._1)
    val orderedPairs = TrackSectionOrder.orderProjectLinksTopologyByGeometry(mappedEndpoints, seq)
    val ordered = if (seq.exists(_.track == Track.RightSide || seq.forall(_.track == Track.Combined))) orderedPairs._1 else orderedPairs._2.reverse

//    val terminated = projectLinkDAO.fetchProjectLinks(ordered.head.projectId, Some(LinkStatus.Terminated))
//
//    val newConnectedToTerminated = ordered.filter(pl => pl.status == LinkStatus.New && terminated.exists(_.connected(pl)))

    val newAddressValues = ordered.scanLeft(addrSt.getOrElse(0.0)) { case (m, pl) => {
//      val x = if (pl.status == LinkStatus.New && pl.track != Track.Combined) terminated.find(_.connected(pl)) else None
//            if (x.isDefined) {
//              val term = x.get
//              terminated.find(t => t.track != term.track && t.endAddrMValue <= term.endAddrMValue && t.startAddrMValue <= term.endAddrMValue)
//            }
      val someCalibrationPoint: Option[UserDefinedCalibrationPoint] = cps.get(pl.id)
      if (!pl.isSplit) {
        val addressValue = if (someCalibrationPoint.nonEmpty) someCalibrationPoint.get.addressMValue else m + Math.abs(pl.geometryLength) * coEff
        pl.status match {
          case LinkStatus.New => addressValue
          case LinkStatus.Transfer | LinkStatus.NotHandled | LinkStatus.Numbering | LinkStatus.UnChanged => m + pl.addrMLength
          case LinkStatus.Terminated => pl.endAddrMValue
          case _ => throw new InvalidAddressDataException(s"Invalid status found at value assignment ${pl.status}, linkId: ${pl.linkId}")
        }
      } else {
        pl.endAddrMValue
      }
    }
    }
    seq.zip(newAddressValues.zip(newAddressValues.tail)).map { case (pl, (st, en)) =>
      pl.copy(startAddrMValue = Math.round(st), endAddrMValue = Math.round(en))
    }
  }

  def calculateAddressingFactors(seq: Seq[ProjectLink]): TrackAddressingFactors = {
    seq.foldLeft[TrackAddressingFactors](TrackAddressingFactors(0, 0, 0.0)) { case (a, pl) =>
      pl.status match {
        case UnChanged | Numbering => a.copy(unChangedLength = a.unChangedLength + pl.addrMLength)
        case Transfer | LinkStatus.NotHandled => a.copy(transferLength = a.transferLength + pl.addrMLength)
        case New => a.copy(newLength = a.newLength + pl.geometryLength)
        case Terminated => a
        case _ => throw new InvalidAddressDataException(s"Invalid status found at factor assignment ${pl.status}, linkId: ${pl.linkId}")
      }
    }
  }

  def assignTerminatedLinkValues(seq: Seq[ProjectLink], addrSt: Long): Seq[ProjectLink] = {
    val newAddressValues = seq.scanLeft(addrSt) { case (m, pl) =>
      pl.status match {
        case LinkStatus.Terminated =>
          m + pl.addrMLength
        case LinkStatus.UnChanged | LinkStatus.Transfer | LinkStatus.NotHandled | LinkStatus.Numbering =>
          pl.roadAddressEndAddrM.getOrElse(pl.endAddrMValue)
        case _ => throw new InvalidAddressDataException(s"Invalid status found at value assignment ${pl.status}, linkId: ${pl.linkId}")
      }
    }
    seq.zip(newAddressValues.zip(newAddressValues.tail)).map { case (pl, (st, en)) =>
      pl.copy(startAddrMValue = Math.round(st), endAddrMValue = Math.round(en))
    }
  }
}

case class TrackAddressingFactors(unChangedLength: Long, transferLength: Long, newLength: Double)

case class CalculatedValue(addressAndGeomLength: Either[Long, Double])

case class AddressingGroup(previous: Option[ProjectLink], group: Seq[ProjectLink])

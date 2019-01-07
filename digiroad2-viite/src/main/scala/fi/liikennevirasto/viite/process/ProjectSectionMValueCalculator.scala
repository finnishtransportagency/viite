package fi.liikennevirasto.viite.process

import fi.liikennevirasto.digiroad2.util.Track
import fi.liikennevirasto.viite.dao.CalibrationPointDAO.UserDefinedCalibrationPoint
import fi.liikennevirasto.viite.dao.LinkStatus._
import fi.liikennevirasto.viite.dao.{LinkStatus, ProjectLink}

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

  def assignLinkValues(seq: Seq[ProjectLink], cps: Map[Long, UserDefinedCalibrationPoint], addrSt: Option[Double], addrEn: Option[Double], coEff: Double = 1.0): Seq[ProjectLink] = {
    val newAddressValues = seq.scanLeft(addrSt.getOrElse(0.0)) { case (m, pl) =>
      val someCalibrationPoint: Option[UserDefinedCalibrationPoint] = cps.get(pl.id)
      val addressValue = if (someCalibrationPoint.nonEmpty) someCalibrationPoint.get.addressMValue else m + pl.geometryLength * coEff
      pl.status match {
        case LinkStatus.New => addressValue
        case LinkStatus.Transfer | LinkStatus.NotHandled | LinkStatus.Terminated => m + pl.addrMLength
        case LinkStatus.UnChanged | LinkStatus.Numbering => pl.endAddrMValue
        case _ => throw new InvalidAddressDataException(s"Invalid status found at value assignment ${pl.status}, linkId: ${pl.linkId}")
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

  def assignLinkValues(seq: Seq[ProjectLink], addrSt: Long): Seq[ProjectLink] = {
    val newAddressValues = seq.scanLeft(addrSt) { case (m, pl) =>
      pl.status match {
        case LinkStatus.Terminated =>
          if(pl.isSplit) m + pl.endAddrMValue - pl.startAddrMValue else m + pl.addrMLength
        case LinkStatus.UnChanged | LinkStatus.Transfer | LinkStatus.NotHandled | LinkStatus.Numbering =>
          if(pl.isSplit) pl.endAddrMValue else pl.roadAddressEndAddrM.getOrElse(pl.endAddrMValue)
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
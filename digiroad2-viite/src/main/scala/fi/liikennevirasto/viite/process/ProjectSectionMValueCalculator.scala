package fi.liikennevirasto.viite.process

import fi.liikennevirasto.digiroad2.util.Track
import fi.liikennevirasto.viite.dao.CalibrationPointDAO.UserDefinedCalibrationPoint
import fi.liikennevirasto.viite.dao.LinkStatus._
import fi.liikennevirasto.viite.dao.{LinkStatus, ProjectLink}

object ProjectSectionMValueCalculator {

  def calculateMValuesForTrack(seq: Seq[ProjectLink], calibrationPoints: Map[Long, UserDefinedCalibrationPoint]): Seq[ProjectLink] = {
    // That is an address connected extension of this
    def isExtensionOf(ext: ProjectLink)(thisPL: ProjectLink) = {
      thisPL.endAddrMValue == ext.startAddrMValue &&
        (thisPL.track == ext.track || Set(thisPL.track, ext.track).contains(Track.Combined))
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
    unchanged ++ assignLinkValues(others, calibrationPoints, unchanged.map(_.endAddrMValue.toDouble).sorted.lastOption, None)
  }

  def assignLinkValues(seq: Seq[ProjectLink], cps: Map[Long, UserDefinedCalibrationPoint], addrSt: Option[Double], addrEn: Option[Double], coEff: Double = 1.0): Seq[ProjectLink] = {
    val newAddressValues = seq.scanLeft(addrSt.getOrElse(0.0)) { case (m, pl) =>
      val someCalibrationPoint: Option[UserDefinedCalibrationPoint] = cps.get(pl.id)
      val addressValue = if (someCalibrationPoint.nonEmpty) someCalibrationPoint.get.addressMValue else m + pl.geometryLength * coEff
      pl.status match {
        case LinkStatus.New => addressValue
        case LinkStatus.Transfer | LinkStatus.UnChanged if pl.id == seq.maxBy(_.endAddrMValue).id && pl.startAddrMValue < (m + addrEn.getOrElse(pl.endAddrMValue.toDouble) - pl.startAddrMValue) && Math.abs(pl.endAddrMValue - addrEn.getOrElse(pl.endAddrMValue.toDouble)) <= fi.liikennevirasto.viite.MaxAdjustmentRange => m + addrEn.getOrElse(pl.endAddrMValue.toDouble) - pl.startAddrMValue
        case LinkStatus.Transfer | LinkStatus.NotHandled => m + pl.endAddrMValue - pl.startAddrMValue
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
        case UnChanged | Numbering => a.copy(unChangedLength = a.unChangedLength + pl.endAddrMValue - pl.startAddrMValue)
        case Transfer | LinkStatus.NotHandled => a.copy(transferLength = a.transferLength + pl.endAddrMValue - pl.startAddrMValue)
        case New => a.copy(newLength = a.newLength + pl.geometryLength)
        case _ => throw new InvalidAddressDataException(s"Invalid status found at factor assignment ${pl.status}, linkId: ${pl.linkId}")
      }
    }
  }
}

case class TrackAddressingFactors(unChangedLength: Long, transferLength: Long, newLength: Double)

case class CalculatedValue(addressAndGeomLength: Either[Long, Double])

case class AddressingGroup(previous: Option[ProjectLink], group: Seq[ProjectLink])
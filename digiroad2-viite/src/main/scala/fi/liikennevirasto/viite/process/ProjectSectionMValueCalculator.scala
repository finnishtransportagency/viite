package fi.liikennevirasto.viite.process

import fi.liikennevirasto.digiroad2.util.Track
import fi.liikennevirasto.viite.dao.LinkStatus._
import fi.liikennevirasto.viite.dao.ProjectCalibrationPointDAO.UserDefinedCalibrationPoint
import fi.liikennevirasto.viite.dao.{LinkStatus, ProjectLink}

object ProjectSectionMValueCalculator {

  def calculateMValuesForTrack(seq: Seq[ProjectLink], calibrationPoints: Map[Long, UserDefinedCalibrationPoint]): Seq[ProjectLink] = {
    assignLinkValues(seq, calibrationPoints, None, None)
  }

  def assignLinkValues(seq: Seq[ProjectLink], cps: Map[Long, UserDefinedCalibrationPoint], addrSt: Option[Double], addrEn: Option[Double], coEff: Double = 1.0): Seq[ProjectLink] = {
    val endPoints = TrackSectionOrder.findChainEndpoints(seq)
    val mappedEndpoints = (endPoints.head._1, endPoints.last._1)
    val orderedPairs = TrackSectionOrder.orderProjectLinksTopologyByGeometry(mappedEndpoints, seq)
    val ordered = if (seq.exists(_.track == Track.RightSide)) orderedPairs._1 else orderedPairs._2.reverse

    val newAddressValues = ordered.scanLeft(addrSt.getOrElse(0.0)) { case (m, pl) =>
      val someCalibrationPoint: Option[UserDefinedCalibrationPoint] = cps.get(pl.id)
        val addressValue = if (someCalibrationPoint.nonEmpty) someCalibrationPoint.get.addressMValue else m + Math.abs(pl.geometryLength) //* coEff
        pl.status match {
          case LinkStatus.New => addressValue
          case LinkStatus.Transfer | LinkStatus.NotHandled | LinkStatus.Numbering | LinkStatus.UnChanged => addressValue
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
        case UnChanged | Numbering => a.copy(unChangedLength = a.unChangedLength + pl.geometryLength.toLong)
        case Transfer | LinkStatus.NotHandled => a.copy(transferLength = a.transferLength + pl.geometryLength.toLong)
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

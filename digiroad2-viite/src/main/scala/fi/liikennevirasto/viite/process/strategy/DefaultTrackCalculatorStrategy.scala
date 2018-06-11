package fi.liikennevirasto.viite.process.strategy

import fi.liikennevirasto.viite.dao.CalibrationPointDAO.UserDefinedCalibrationPoint
import fi.liikennevirasto.viite.dao.{LinkStatus, ProjectLink}

class DefaultTrackCalculatorStrategy extends TrackCalculatorStrategy {

  protected def getFixedAddress(leftLink: ProjectLink, rightLink: ProjectLink,
                                userCalibrationPoint: Option[UserDefinedCalibrationPoint] = None): (Long, Long) = {

    val reversed = rightLink.reversed || leftLink.reversed

    (leftLink.status, rightLink.status) match {
      case (LinkStatus.Transfer, LinkStatus.Transfer) | (LinkStatus.UnChanged, LinkStatus.UnChanged) =>
        (averageOfAddressMValues(rightLink.startAddrMValue, leftLink.startAddrMValue, reversed), averageOfAddressMValues(rightLink.endAddrMValue, leftLink.endAddrMValue, reversed))
      case (LinkStatus.UnChanged, _) | (LinkStatus.Transfer, _) =>
        (rightLink.startAddrMValue, rightLink.endAddrMValue)
      case (_ , LinkStatus.UnChanged) | (_, LinkStatus.Transfer) =>
        (leftLink.startAddrMValue, leftLink.endAddrMValue)
      case _ =>
        userCalibrationPoint.map(c => (c.addressMValue, c.addressMValue)).getOrElse(
          (averageOfAddressMValues(rightLink.startAddrMValue, leftLink.startAddrMValue, reversed), averageOfAddressMValues(rightLink.endAddrMValue, leftLink.endAddrMValue, reversed))
        )
    }
  }

  override def assignTrackMValues(startAddress: Option[Long] , leftProjectLinks: Seq[ProjectLink], rightProjectLinks: Seq[ProjectLink], userDefinedCalibrationPoint: Map[Long, UserDefinedCalibrationPoint]): TrackCalculatorResult = {
    val availableCalibrationPoint = userDefinedCalibrationPoint.get(rightProjectLinks.last.id).orElse(userDefinedCalibrationPoint.get(leftProjectLinks.last.id))

    val startSectionAddress = startAddress.getOrElse(getFixedAddress(leftProjectLinks.head, rightProjectLinks.head)._1)
    val endSectionAddress = getFixedAddress(leftProjectLinks.last, rightProjectLinks.last, availableCalibrationPoint)._2

    val (adjustedLeft, adjustedRight) = adjustTwoTracks(rightProjectLinks, leftProjectLinks, startSectionAddress, endSectionAddress, userDefinedCalibrationPoint)

    TrackCalculatorResult(adjustedLeft, adjustedRight, startSectionAddress, endSectionAddress)
  }
}

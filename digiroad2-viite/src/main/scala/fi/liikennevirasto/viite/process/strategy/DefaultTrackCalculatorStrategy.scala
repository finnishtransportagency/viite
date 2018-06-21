package fi.liikennevirasto.viite.process.strategy

import fi.liikennevirasto.viite.dao.CalibrationPointDAO.UserDefinedCalibrationPoint
import fi.liikennevirasto.viite.dao.{ProjectLink}

class DefaultTrackCalculatorStrategy extends TrackCalculatorStrategy {

  override def assignTrackMValues(startAddress: Option[Long] , leftProjectLinks: Seq[ProjectLink], rightProjectLinks: Seq[ProjectLink], userDefinedCalibrationPoint: Map[Long, UserDefinedCalibrationPoint]): TrackCalculatorResult = {
    val availableCalibrationPoint = userDefinedCalibrationPoint.get(rightProjectLinks.last.id).orElse(userDefinedCalibrationPoint.get(leftProjectLinks.last.id))

    val startSectionAddress = startAddress.getOrElse(getFixedAddress(leftProjectLinks.head, rightProjectLinks.head)._1)

    val withLeftCalibrationPoints = userDefinedCalibrationPoint.exists(_._2.addressMValue == leftProjectLinks.head.startAddrMValue) || leftProjectLinks.head.startAddrMValue == startSectionAddress
    val withRightCalibrationPoints = userDefinedCalibrationPoint.exists(_._2.addressMValue == rightProjectLinks.head.startAddrMValue) || rightProjectLinks.head.startAddrMValue == startSectionAddress

    val estimatedEnd = getFixedAddress(leftProjectLinks.last, rightProjectLinks.last, availableCalibrationPoint, withLeftCalibrationPoints, withRightCalibrationPoints)._2

    val (adjustedLeft, adjustedRight) = adjustTwoTracks(rightProjectLinks, leftProjectLinks, startSectionAddress, estimatedEnd, userDefinedCalibrationPoint)

    //The getFixedAddress method have to be call twice because when we do it the first time we are getting the estimated end measure, that will be used for the calculation of
    // NEW sections. For example if in one of the sides we have a TRANSFER section it will use the value after recalculate all the existing sections with the original length.
    val endSectionAddress = getFixedAddress(adjustedLeft.last, adjustedRight.last, availableCalibrationPoint, withLeftCalibrationPoints, withRightCalibrationPoints)._2

    TrackCalculatorResult(setLastEndAddrMValue(adjustedLeft, endSectionAddress), setLastEndAddrMValue(adjustedRight, endSectionAddress), startSectionAddress, endSectionAddress)
  }
}

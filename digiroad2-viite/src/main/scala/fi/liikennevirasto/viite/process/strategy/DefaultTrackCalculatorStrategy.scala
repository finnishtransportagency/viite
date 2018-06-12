package fi.liikennevirasto.viite.process.strategy

import fi.liikennevirasto.digiroad2.util.Track
import fi.liikennevirasto.viite.dao.CalibrationPointDAO.UserDefinedCalibrationPoint
import fi.liikennevirasto.viite.dao.Discontinuity.MinorDiscontinuity
import fi.liikennevirasto.viite.dao.{Discontinuity, LinkStatus, ProjectLink}

//TODO This file should be splited in one for each strategy

class DefaultTrackCalculatorStrategy extends TrackCalculatorStrategy {

  override def assignTrackMValues(startAddress: Option[Long] , leftProjectLinks: Seq[ProjectLink], rightProjectLinks: Seq[ProjectLink], userDefinedCalibrationPoint: Map[Long, UserDefinedCalibrationPoint]): TrackCalculatorResult = {
    val availableCalibrationPoint = userDefinedCalibrationPoint.get(rightProjectLinks.last.id).orElse(userDefinedCalibrationPoint.get(leftProjectLinks.last.id))

    val startSectionAddress = startAddress.getOrElse(getFixedAddress(leftProjectLinks.head, rightProjectLinks.head)._1)
    val endSectionAddress = getFixedAddress(leftProjectLinks.last, rightProjectLinks.last, availableCalibrationPoint)._2

    val (adjustedLeft, adjustedRight) = adjustTwoTracks(rightProjectLinks, leftProjectLinks, startSectionAddress, endSectionAddress, userDefinedCalibrationPoint)

    TrackCalculatorResult(adjustedLeft, adjustedRight, startSectionAddress, endSectionAddress)
  }
}

class LinkStatusChangeTrackCalculatorStrategy extends TrackCalculatorStrategy {

  protected def getUntilLinkStatusChange(seq: Seq[ProjectLink], status: LinkStatus): (Seq[ProjectLink], Seq[ProjectLink]) = {
    val continuousProjectLinks = seq.takeWhile(pl => pl.status == status)
    (continuousProjectLinks, seq.drop(continuousProjectLinks.size))
  }

  //TODO this is duplicated from the discontinuity strategy (maybe we can have some trait in the middle)
  private def adjustTwoTracks(startAddress: Option[Long], leftProjectLinks: Seq[ProjectLink], rightProjectLinks: Seq[ProjectLink], restLeftProjectLinks: Seq[ProjectLink],
                              restRightProjectLinks: Seq[ProjectLink], calibrationPoints: Map[Long, UserDefinedCalibrationPoint]): TrackCalculatorResult = {

    //TODO change the way the user calibration points are manage
    val availableCalibrationPoint = calibrationPoints.get(rightProjectLinks.last.id).orElse(calibrationPoints.get(leftProjectLinks.last.id))

    val startSectionAddress = startAddress.getOrElse(getFixedAddress(leftProjectLinks.head, rightProjectLinks.head)._1)

    val (lastLeftProjectLink, lastRightProjectLink) = (leftProjectLinks.last, rightProjectLinks.last)
    val endSectionAddress = if(lastRightProjectLink.endAddrMValue >= lastLeftProjectLink.endAddrMValue) lastRightProjectLink.endAddrMValue else lastLeftProjectLink.endAddrMValue

    val (adjustedLeft, adjustedRight) = adjustTwoTracks(rightProjectLinks, leftProjectLinks, startSectionAddress, endSectionAddress, calibrationPoints)

    TrackCalculatorResult(adjustedLeft, adjustedRight, startSectionAddress, endSectionAddress, restLeftProjectLinks, restRightProjectLinks)
  }

  override def applicableStrategy(headProjectLink: ProjectLink, projectLink: ProjectLink): Boolean = {
    projectLink.status != headProjectLink.status && projectLink.track != Track.Combined
  }

  override def assignTrackMValues(startAddress: Option[Long], leftProjectLinks: Seq[ProjectLink], rightProjectLinks: Seq[ProjectLink], userDefinedCalibrationPoint: Map[Long, UserDefinedCalibrationPoint]): TrackCalculatorResult = {
    val (left, restLeft) = getUntilLinkStatusChange(leftProjectLinks, leftProjectLinks.head.status)
    val (right, restRight) = getUntilLinkStatusChange(rightProjectLinks, rightProjectLinks.head.status)

    val (lastLeft, lastRight) = (left.last, right.last)
    val distance = lastRight.toMeters(Math.abs(lastRight.endAddrMValue - lastLeft.endAddrMValue))

    if(distance < 3){
      adjustTwoTracks(startAddress, left, right, restLeft, restRight, userDefinedCalibrationPoint)
    }else{
      if(lastRight.endAddrMValue <= lastLeft.endAddrMValue){
        val (newLeft, newRestLeft) = getUntilNearestAddress(leftProjectLinks, lastRight.endAddrMValue)
        adjustTwoTracks(startAddress, newLeft, right, newRestLeft, restRight, userDefinedCalibrationPoint)
      }else{
        val (newRight, newRestRight) = getUntilNearestAddress(rightProjectLinks, lastLeft.endAddrMValue)
        adjustTwoTracks(startAddress, left, newRight, restLeft, newRestRight, userDefinedCalibrationPoint)
      }
    }
  }
}

class DiscontinuityTrackCalculatorStrategy extends TrackCalculatorStrategy {

  protected def getUntilDiscontinuity(seq: Seq[ProjectLink], discontinuity: Discontinuity): (Seq[ProjectLink], Seq[ProjectLink]) = {
    val continuousProjectLinks = seq.takeWhile(pl => pl.discontinuity != discontinuity)
    val rest = seq.drop(continuousProjectLinks.size)
    if(rest.nonEmpty && rest.head.discontinuity == discontinuity)
      (continuousProjectLinks :+ rest.head, rest.tail)
    else
      (continuousProjectLinks, rest)
  }

  private def adjustTwoTracks(startAddress: Option[Long], leftProjectLinks: Seq[ProjectLink], rightProjectLinks: Seq[ProjectLink], restLeftProjectLinks: Seq[ProjectLink],
                              restRightProjectLinks: Seq[ProjectLink], calibrationPoints: Map[Long, UserDefinedCalibrationPoint]): TrackCalculatorResult = {

    //TODO change the way the user calibration points are manage
    val availableCalibrationPoint = calibrationPoints.get(rightProjectLinks.last.id).orElse(calibrationPoints.get(leftProjectLinks.last.id))

    val startSectionAddress = startAddress.getOrElse(getFixedAddress(leftProjectLinks.head, rightProjectLinks.head)._1)

    val (lastLeftProjectLink, lastRightProjectLink) = (leftProjectLinks.last, rightProjectLinks.last)
    val endSectionAddress = averageOfAddressMValues(lastRightProjectLink.endAddrMValue, lastLeftProjectLink.endAddrMValue, reversed = lastLeftProjectLink.reversed || lastRightProjectLink.reversed)

    val (adjustedLeft, adjustedRight) = adjustTwoTracks(rightProjectLinks, leftProjectLinks, startSectionAddress, endSectionAddress, calibrationPoints)

    TrackCalculatorResult(adjustedLeft, adjustedRight, startSectionAddress, endSectionAddress, restLeftProjectLinks, restRightProjectLinks)
  }

  override def applicableStrategy(headProjectLink: ProjectLink, projectLink: ProjectLink): Boolean = {
    projectLink.discontinuity == MinorDiscontinuity && projectLink.track != Track.Combined
  }

  override def assignTrackMValues(startAddress: Option[Long], leftProjectLinks: Seq[ProjectLink], rightProjectLinks: Seq[ProjectLink], userDefinedCalibrationPoint: Map[Long, UserDefinedCalibrationPoint]): TrackCalculatorResult = {
    val (left, restLeft) = getUntilDiscontinuity(leftProjectLinks, MinorDiscontinuity)
    val (right, restRight) = getUntilDiscontinuity(rightProjectLinks, MinorDiscontinuity)

    (left.last.discontinuity, right.last.discontinuity) match {
      case (MinorDiscontinuity, MinorDiscontinuity) => //If both sides have a minor discontinuity
        //If in the future we have minor discontinuities that can not be related with each other,
        //we should get a way to find the minor discontinuity depending on the road address instead
        //of getting the next first one, from the opposite side
        adjustTwoTracks(startAddress, left, right, restLeft, restRight, userDefinedCalibrationPoint)
      case (MinorDiscontinuity, _) => //If left side have a minor discontinuity
        val (newRight, newRestRight) = getUntilNearestAddress(rightProjectLinks, left.last.endAddrMValue)
        //Can the done here the project link split if needed
        adjustTwoTracks(startAddress, left, newRight, restLeft, newRestRight, userDefinedCalibrationPoint)
      case _ => //If right side have a minor discontinuity
        val (newLeft, newLeftRest) = getUntilNearestAddress(leftProjectLinks, right.last.endAddrMValue)
        //Can the split be performed here
        adjustTwoTracks(startAddress, newLeft, right, newLeftRest, restRight, userDefinedCalibrationPoint)
    }
  }
}

package fi.liikennevirasto.viite.process.strategy

import fi.liikennevirasto.digiroad2.util.Track
import fi.liikennevirasto.viite.dao.CalibrationPointDAO.UserDefinedCalibrationPoint
import fi.liikennevirasto.viite.dao.Discontinuity.MinorDiscontinuity
import fi.liikennevirasto.viite.dao.{Discontinuity, LinkStatus, ProjectLink}

class DefaultTrackCalculatorStrategy extends TrackCalculatorStrategy {

  override def assignTrackMValues(startAddress: Option[Long] , leftProjectLinks: Seq[ProjectLink], rightProjectLinks: Seq[ProjectLink], userDefinedCalibrationPoint: Map[Long, UserDefinedCalibrationPoint]): TrackCalculatorResult = {
    val availableCalibrationPoint = userDefinedCalibrationPoint.get(rightProjectLinks.last.id).orElse(userDefinedCalibrationPoint.get(leftProjectLinks.last.id))

    val startSectionAddress = startAddress.getOrElse(getFixedAddress(leftProjectLinks.head, rightProjectLinks.head)._1)
    val endSectionAddress = getFixedAddress(leftProjectLinks.last, rightProjectLinks.last, availableCalibrationPoint)._2

    val (adjustedLeft, adjustedRight) = adjustTwoTracks(rightProjectLinks, leftProjectLinks, startSectionAddress, endSectionAddress, userDefinedCalibrationPoint)

    TrackCalculatorResult(adjustedLeft, adjustedRight, startSectionAddress, endSectionAddress)
  }
}

class DiscontinuityTrackCalculatorStrategy extends TrackCalculatorStrategy {

  private def containsDiscontinuity(projectLink: ProjectLink): Boolean = {
    projectLink.discontinuity == MinorDiscontinuity && projectLink.track != Track.Combined
  }

  private def getUntilDiscontinuity(seq: Seq[ProjectLink], discontinuity: Discontinuity): (Seq[ProjectLink], Seq[ProjectLink]) = {
    val continuousProjectLinks = seq.takeWhile(pl => pl.discontinuity == discontinuity)
    val rest = seq.drop(continuousProjectLinks.size)
    if(rest.nonEmpty && rest.head.discontinuity == discontinuity)
      (continuousProjectLinks :+ rest.head, rest.tail)
    else
      (continuousProjectLinks, rest)
  }

  private def getUntilNearestAddress(seq: Seq[ProjectLink], address: Long): (Seq[ProjectLink], Seq[ProjectLink]) = {
    val continuousProjectLinks = seq.takeWhile(pl => pl.startAddrMValue < address)
    (continuousProjectLinks, seq.drop(continuousProjectLinks.size))
  }

  override def applicableStrategy(leftProjectLinks: Seq[ProjectLink], rightProjectLinks: Seq[ProjectLink]): Boolean = {
    leftProjectLinks.exists(containsDiscontinuity) || rightProjectLinks.exists(containsDiscontinuity)
  }

  private def adjustTwoTracks(startAddress: Option[Long], leftProjectLinks: Seq[ProjectLink], rightProjectLinks: Seq[ProjectLink], restLeftProjectLinks: Seq[ProjectLink],
                              restRightProjectLinks: Seq[ProjectLink], calibrationPoints: Map[Long, UserDefinedCalibrationPoint]): TrackCalculatorResult = {

    val availableCalibrationPoint = calibrationPoints.get(rightProjectLinks.last.id).orElse(calibrationPoints.get(leftProjectLinks.last.id))

    val startSectionAddress = startAddress.getOrElse(getFixedAddress(leftProjectLinks.head, rightProjectLinks.head)._1)

    val (lastLeftProjectLink, lastRightProjectLink) = (leftProjectLinks.last, rightProjectLinks.last)
    val endSectionAddress = if(lastRightProjectLink.endAddrMValue >= lastLeftProjectLink.endAddrMValue) lastRightProjectLink.endAddrMValue else lastLeftProjectLink.endAddrMValue

    val (adjustedLeft, adjustedRight) = adjustTwoTracks(rightProjectLinks, leftProjectLinks, startSectionAddress, endSectionAddress, calibrationPoints)

    TrackCalculatorResult(adjustedLeft, adjustedRight, startSectionAddress, endSectionAddress, restLeftProjectLinks, restRightProjectLinks)
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
        adjustTwoTracks(startAddress, left, newRight, newRestRight, restRight, userDefinedCalibrationPoint)
      case _ => //If right side have a minor discontinuity
        val (newLeft, newLeftRest) = getUntilNearestAddress(leftProjectLinks, right.last.endAddrMValue)
        //Can the split be performed here
        adjustTwoTracks(startAddress, newLeft, newLeftRest, restLeft, restRight, userDefinedCalibrationPoint)
    }
  }
}

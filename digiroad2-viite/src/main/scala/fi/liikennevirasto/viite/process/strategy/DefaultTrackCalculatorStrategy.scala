package fi.liikennevirasto.viite.process.strategy

import fi.liikennevirasto.digiroad2.GeometryUtils
import fi.liikennevirasto.digiroad2.util.Track
import fi.liikennevirasto.viite.NewRoadAddress
import fi.liikennevirasto.viite.dao.CalibrationPointDAO.UserDefinedCalibrationPoint
import fi.liikennevirasto.viite.dao.Discontinuity.MinorDiscontinuity
import fi.liikennevirasto.viite.dao.{Discontinuity, LinkStatus, ProjectLink}

//TODO This file should be splited in one for each strategy

class DefaultTrackCalculatorStrategy extends TrackCalculatorStrategy {

  override def assignTrackMValues(startAddress: Option[Long] , leftProjectLinks: Seq[ProjectLink], rightProjectLinks: Seq[ProjectLink], userDefinedCalibrationPoint: Map[Long, UserDefinedCalibrationPoint]): TrackCalculatorResult = {
    val availableCalibrationPoint = userDefinedCalibrationPoint.get(rightProjectLinks.last.id).orElse(userDefinedCalibrationPoint.get(leftProjectLinks.last.id))

    val startSectionAddress = startAddress.getOrElse(getFixedAddress(leftProjectLinks.head, rightProjectLinks.head)._1)

    val withLeftCalibrationPoints = userDefinedCalibrationPoint.exists(_._2.addressMValue == leftProjectLinks.head.startAddrMValue) || leftProjectLinks.head.startAddrMValue == startSectionAddress
    val withRightCalibrationPoints = userDefinedCalibrationPoint.exists(_._2.addressMValue == rightProjectLinks.head.startAddrMValue) || rightProjectLinks.head.startAddrMValue == startSectionAddress

    val estimatedEnd = getFixedAddress(leftProjectLinks.last, rightProjectLinks.last, availableCalibrationPoint, withLeftCalibrationPoints, withRightCalibrationPoints)._2

    val (adjustedLeft, adjustedRight) = adjustTwoTracks(rightProjectLinks, leftProjectLinks, startSectionAddress, estimatedEnd, userDefinedCalibrationPoint)

    val endSectionAddress = getFixedAddress(adjustedLeft.last, adjustedRight.last, availableCalibrationPoint, withLeftCalibrationPoints, withRightCalibrationPoints)._2

    TrackCalculatorResult(setLastEndAddrMValue(adjustedLeft, endSectionAddress), setLastEndAddrMValue(adjustedRight, endSectionAddress), startSectionAddress, endSectionAddress)
  }
}

class LinkStatusChangeTrackCalculatorStrategy extends TrackCalculatorStrategy {

  val AdjustmentToleranceMeters = 3L

  protected def getUntilLinkStatusChange(seq: Seq[ProjectLink], status: LinkStatus): (Seq[ProjectLink], Seq[ProjectLink]) = {
    val continuousProjectLinks = seq.takeWhile(pl => pl.status == status)
    (continuousProjectLinks, seq.drop(continuousProjectLinks.size))
  }

  override def applicableStrategy(headProjectLink: ProjectLink, projectLink: ProjectLink): Boolean = {
    //Will be applied if the link status changes FROM or TO a status equal "NEW" or "TERMINATED" and track is Left or Right
    projectLink.status != headProjectLink.status &&
      (projectLink.status == LinkStatus.New || headProjectLink.status == LinkStatus.New || projectLink.status == LinkStatus.Terminated || headProjectLink.status == LinkStatus.Terminated) &&
      (projectLink.track == Track.LeftSide || projectLink.track == Track.RightSide)
  }

  override def assignTrackMValues(startAddress: Option[Long], leftProjectLinks: Seq[ProjectLink], rightProjectLinks: Seq[ProjectLink], userDefinedCalibrationPoint: Map[Long, UserDefinedCalibrationPoint]): TrackCalculatorResult = {
    val (left, restLeft) = getUntilLinkStatusChange(leftProjectLinks, leftProjectLinks.head.status)
    val (right, restRight) = getUntilLinkStatusChange(rightProjectLinks, rightProjectLinks.head.status)

    val (lastLeft, lastRight) = (left.last, right.last)

    if(lastRight.endAddrMValue <= lastLeft.endAddrMValue){
      val distance = lastRight.toMeters(lastLeft.endAddrMValue - lastRight.endAddrMValue)
      if(distance < AdjustmentToleranceMeters){
        adjustTwoTracks(startAddress, left, right, restLeft, restRight, userDefinedCalibrationPoint)
      }else{
        val (newLeft, newRestLeft) = getUntilNearestAddress(leftProjectLinks, lastRight.endAddrMValue)
        adjustTwoTracks(startAddress, newLeft, right, newRestLeft, restRight, userDefinedCalibrationPoint)
      }
    }else{
      val distance = lastLeft.toMeters(lastRight.endAddrMValue - lastLeft.endAddrMValue)
      if(distance < AdjustmentToleranceMeters){
        adjustTwoTracks(startAddress, left, right, restLeft, restRight, userDefinedCalibrationPoint)
      }else {
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

  override def applicableStrategy(headProjectLink: ProjectLink, projectLink: ProjectLink): Boolean = {
      projectLink.discontinuity == MinorDiscontinuity &&
        (projectLink.track == Track.LeftSide || projectLink.track == Track.RightSide)
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
        adjustTwoTracks(startAddress, left, newRight, restLeft, newRestRight, userDefinedCalibrationPoint)
      case _ => //If right side have a minor discontinuity
        val (newLeft, newLeftRest) = getUntilNearestAddress(leftProjectLinks, right.last.endAddrMValue)
        adjustTwoTracks(startAddress, newLeft, right, newLeftRest, restRight, userDefinedCalibrationPoint)
    }
  }
}

package fi.liikennevirasto.viite.process.strategy

import fi.liikennevirasto.digiroad2.util.{Track}
import fi.liikennevirasto.viite.dao.CalibrationPointDAO.UserDefinedCalibrationPoint
import fi.liikennevirasto.viite.dao.{LinkStatus, ProjectLink}


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
        adjustTwoTracks(startAddress, left, right, userDefinedCalibrationPoint, restLeft, restRight)
      } else {
        val (newLeft, newRestLeft) = getUntilNearestAddress(leftProjectLinks, lastRight.endAddrMValue)
        adjustTwoTracks(startAddress, newLeft, right, userDefinedCalibrationPoint, newRestLeft, restRight)
      }
    } else {
      val distance = lastLeft.toMeters(lastRight.endAddrMValue - lastLeft.endAddrMValue)
      if(distance < AdjustmentToleranceMeters){
        adjustTwoTracks(startAddress, left, right, userDefinedCalibrationPoint, restLeft, restRight)
      } else {
        val (newRight, newRestRight) = getUntilNearestAddress(rightProjectLinks, lastLeft.endAddrMValue)
        adjustTwoTracks(startAddress, left, newRight, userDefinedCalibrationPoint, restLeft, newRestRight)
      }
    }
  }
}
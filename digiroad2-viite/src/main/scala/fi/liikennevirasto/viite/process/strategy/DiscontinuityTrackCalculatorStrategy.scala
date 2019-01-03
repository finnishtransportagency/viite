package fi.liikennevirasto.viite.process.strategy

import fi.liikennevirasto.viite.MaxDistanceForSearchDiscontinuityOnOppositeTrack
import fi.liikennevirasto.viite.dao.CalibrationPointDAO.UserDefinedCalibrationPoint
import fi.liikennevirasto.viite.dao.Discontinuity.MinorDiscontinuity
import fi.liikennevirasto.viite.dao.{Discontinuity, ProjectLink}

class DiscontinuityTrackCalculatorStrategy(discontinuity: Discontinuity) extends TrackCalculatorStrategy {

  protected def getUntilDiscontinuity(seq: Seq[ProjectLink], discontinuity: Discontinuity): (Seq[ProjectLink], Seq[ProjectLink]) = {
    val continuousProjectLinks = seq.takeWhile(pl => pl.discontinuity != discontinuity)
    val rest = seq.drop(continuousProjectLinks.size)
    if (rest.nonEmpty && rest.head.discontinuity == discontinuity)
      (continuousProjectLinks :+ rest.head, rest.tail)
    else
      (continuousProjectLinks, rest)
  }

  override def applicableStrategy(headProjectLink: ProjectLink, projectLink: ProjectLink): Boolean = {
    projectLink.discontinuity == discontinuity
  }

  override def assignTrackMValues(startAddress: Option[Long], leftProjectLinks: Seq[ProjectLink], rightProjectLinks: Seq[ProjectLink], userDefinedCalibrationPoint: Map[Long, UserDefinedCalibrationPoint]): TrackCalculatorResult = {
    val (left, restLeft) = getUntilDiscontinuity(leftProjectLinks, discontinuity)
    val (right, restRight) = getUntilDiscontinuity(rightProjectLinks, discontinuity)

    (left.last.discontinuity, right.last.discontinuity) match {
      case (MinorDiscontinuity, MinorDiscontinuity) => // If both sides have a minor discontinuity
        if (Math.abs(left.last.endAddrMValue - right.last.endAddrMValue) < MaxDistanceForSearchDiscontinuityOnOppositeTrack) {
          adjustTwoTracks(startAddress, left, right, userDefinedCalibrationPoint, restLeft, restRight)
        } else if (left.last.endAddrMValue < right.last.endAddrMValue) { // If the left side has a minor discontinuity
          val (newRight, newRestRight) = getUntilNearestAddress(rightProjectLinks, left.last)
          adjustTwoTracks(startAddress, left, newRight, userDefinedCalibrationPoint, restLeft, newRestRight)
        } else { // If the right side has a minor discontinuity
          val (newLeft, newLeftRest) = getUntilNearestAddress(leftProjectLinks, right.last)
          adjustTwoTracks(startAddress, newLeft, right, userDefinedCalibrationPoint, newLeftRest, restRight)
        }
      case (MinorDiscontinuity, _) => // If the left side has a minor discontinuity
        val (newRight, newRestRight) = getUntilNearestAddress(rightProjectLinks, left.last)
        adjustTwoTracks(startAddress, left, newRight, userDefinedCalibrationPoint, restLeft, newRestRight)
      case _ => // If the right side has a minor discontinuity
        val (newLeft, newLeftRest) = getUntilNearestAddress(leftProjectLinks, right.last)
        adjustTwoTracks(startAddress, newLeft, right, userDefinedCalibrationPoint, newLeftRest, restRight)
    }
  }
}
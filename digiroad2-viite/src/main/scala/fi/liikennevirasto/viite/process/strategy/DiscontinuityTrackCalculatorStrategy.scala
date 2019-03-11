package fi.liikennevirasto.viite.process.strategy

import fi.liikennevirasto.viite.MaxDistanceForSearchDiscontinuityOnOppositeTrack
import fi.liikennevirasto.viite.dao.CalibrationPointDAO.UserDefinedCalibrationPoint
import fi.liikennevirasto.viite.dao.Discontinuity.{MinorDiscontinuity, ParallelLink}
import fi.liikennevirasto.viite.dao.{Discontinuity, ProjectLink}

class DiscontinuityTrackCalculatorStrategy(discontinuities: Seq[Discontinuity]) extends TrackCalculatorStrategy {

  protected def getUntilDiscontinuity(seq: Seq[ProjectLink]): (Seq[ProjectLink], Seq[ProjectLink]) = {
    val continuousProjectLinks = seq.takeWhile(pl => !discontinuities.contains(pl.discontinuity))
    val rest = seq.drop(continuousProjectLinks.size)
    if (rest.nonEmpty && discontinuities.contains(rest.head.discontinuity))
      (continuousProjectLinks :+ rest.head, rest.tail)
    else
      (continuousProjectLinks, rest)
  }

  override def applicableStrategy(headProjectLink: ProjectLink, projectLink: ProjectLink): Boolean = {
    discontinuities.contains(projectLink.discontinuity)
  }

  override def assignTrackMValues(startAddress: Option[Long], leftProjectLinks: Seq[ProjectLink], rightProjectLinks: Seq[ProjectLink], userDefinedCalibrationPoint: Map[Long, UserDefinedCalibrationPoint]): TrackCalculatorResult = {
    val (left, restLeft) = getUntilDiscontinuity(leftProjectLinks)
    val (right, restRight) = getUntilDiscontinuity(rightProjectLinks)

    (left.last.discontinuity, right.last.discontinuity) match {
      case (MinorDiscontinuity | ParallelLink, MinorDiscontinuity | ParallelLink) => // If both sides have a minor discontinuity
        if (left.last.getEndPoints._2.distance2DTo(right.last.getEndPoints._2) < MaxDistanceForSearchDiscontinuityOnOppositeTrack || (left.last.discontinuity == ParallelLink || right.last.discontinuity == ParallelLink)) {
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
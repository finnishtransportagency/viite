package fi.liikennevirasto.viite.process.strategy

import fi.liikennevirasto.viite.{MaxDistanceForSearchDiscontinuityOnOppositeTrack, NewIdValue}
import fi.liikennevirasto.viite.dao.Discontinuity.{MinorDiscontinuity, ParallelLink}
import fi.liikennevirasto.viite.dao.ProjectCalibrationPointDAO.UserDefinedCalibrationPoint
import fi.liikennevirasto.viite.dao.{Discontinuity, ProjectLink}

class DiscontinuityTrackCalculatorStrategy(discontinuities: Seq[Discontinuity]) extends TrackCalculatorStrategy {

  override val name: String = "Discontinuity Track Section"

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
    val (firstLeft, othersLeft) = getUntilDiscontinuity(leftProjectLinks)
    val (firstRight, othersRight) = getUntilDiscontinuity(rightProjectLinks)

    val leftRoadwayNumber = firstLeft.headOption.map(_.roadwayNumber).getOrElse(NewIdValue)
    val left = firstLeft.takeWhile(_.roadwayNumber == leftRoadwayNumber)
    val restLeft = firstLeft.drop(left.size) ++ othersLeft

    val rightRoadwayNumber = firstRight.headOption.map(_.roadwayNumber).getOrElse(NewIdValue)
    val right = firstRight.takeWhile(_.roadwayNumber == rightRoadwayNumber)
    val restRight = firstRight.drop(right.size) ++ othersRight

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
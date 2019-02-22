package fi.liikennevirasto.viite.process.strategy

import fi.liikennevirasto.digiroad2.asset.SideCode
import fi.liikennevirasto.digiroad2.util.Track.{Combined, LeftSide, RightSide}
import fi.liikennevirasto.digiroad2.util.{RoadAddressException, Track}
import fi.liikennevirasto.digiroad2.{GeometryUtils, Point, Vector3d}
import fi.liikennevirasto.viite.dao.CalibrationPointDAO.UserDefinedCalibrationPoint
import fi.liikennevirasto.viite.dao._
import fi.liikennevirasto.viite.process._
import org.slf4j.LoggerFactory

class DefaultSectionCalculatorStrategy extends RoadAddressSectionCalculatorStrategy {

  private val logger = LoggerFactory.getLogger(getClass)

  override val name: String = "Normal Section"

  val projectLinkDAO = new ProjectLinkDAO

  override def assignMValues(newProjectLinks: Seq[ProjectLink], oldProjectLinks: Seq[ProjectLink], userCalibrationPoints: Seq[UserDefinedCalibrationPoint]): Seq[ProjectLink] = {

    val groupedProjectLinks = newProjectLinks.groupBy(record => (record.roadNumber, record.roadPartNumber))
    val groupedOldLinks = oldProjectLinks.groupBy(record => (record.roadNumber, record.roadPartNumber))
    val group = (groupedProjectLinks.keySet ++ groupedOldLinks.keySet).map(k =>
      k -> (groupedProjectLinks.getOrElse(k, Seq()), groupedOldLinks.getOrElse(k, Seq())))
    group.flatMap { case (part, (projectLinks, oldLinks)) =>
      try {
        val oldRoadLinks = if (projectLinks.nonEmpty) {
          projectLinkDAO.fetchByProjectRoad(part._1, projectLinks.head.projectId).filterNot(l => l.roadPartNumber == part._2)
        } else {
          Seq.empty[ProjectLink]
        }
        val currStartPoints = findStartingPoints(projectLinks, oldLinks, oldRoadLinks, userCalibrationPoints)
        val (right, left) = TrackSectionOrder.orderProjectLinksTopologyByGeometry(currStartPoints, projectLinks ++ oldLinks)
        val ordSections = TrackSectionOrder.createCombinedSections(right, left)

        // TODO: userCalibrationPoints to Long -> Seq[UserDefinedCalibrationPoint] in method params
        val calMap = userCalibrationPoints.map(c => c.projectLinkId -> c).toMap

        val calculatedSections = calculateSectionAddressValues(ordSections, calMap)
        calculatedSections.flatMap { sec =>
          if (sec.right == sec.left)
            sec.right.links
          else {
            sec.right.links ++ sec.left.links
          }
        }
      } catch {
        case ex: InvalidAddressDataException =>
          logger.info(s"Can't calculate road/road part ${part._1}/${part._2}: " + ex.getMessage)
          projectLinks ++ oldLinks
        case ex: NoSuchElementException =>
          logger.info("Delta calculation failed: " + ex.getMessage, ex)
          projectLinks ++ oldLinks
        case ex: NullPointerException =>
          logger.info("Delta calculation failed (NPE)", ex)
          projectLinks ++ oldLinks
        case ex: Throwable =>
          logger.info("Delta calculation not possible: " + ex.getMessage)
          projectLinks ++ oldLinks
      }
    }.toSeq
  }

  private def getContinuousSection(seq: Seq[ProjectLink]): (Seq[ProjectLink], Seq[ProjectLink]) = {
    val track = seq.headOption.map(_.track).getOrElse(Track.Unknown)
    val roadwayNumber = seq.headOption.map(_.roadwayNumber).getOrElse(0)
    val continuousProjectLinks = seq.takeWhile(pl => (pl.track == track && pl.track == Track.Combined) || (pl.track == track && pl.track != Track.Combined && pl.roadwayNumber == roadwayNumber))
    (continuousProjectLinks, seq.drop(continuousProjectLinks.size))
  }

  private def calculateSectionAddressValues(sections: Seq[CombinedSection],
                                            userDefinedCalibrationPoint: Map[Long, UserDefinedCalibrationPoint]): Seq[CombinedSection] = {

    def adjustTracksToMatch(leftLinks: Seq[ProjectLink], rightLinks: Seq[ProjectLink], previousStart: Option[Long]): (Seq[ProjectLink], Seq[ProjectLink]) = {
      if (rightLinks.isEmpty && leftLinks.isEmpty) {
        (Seq(), Seq())
      } else {

        val (firstRight, restRight) = getContinuousSection(rightLinks)
        val (firstLeft, restLeft) = getContinuousSection(leftLinks)

        if (firstRight.isEmpty || firstLeft.isEmpty)
          throw new RoadAddressException(s"Mismatching tracks, R ${firstRight.size}, L ${firstLeft.size}")

        val strategy = TrackCalculatorContext.getStrategy(firstLeft, firstRight)
        val trackCalcResult = strategy.assignTrackMValues(previousStart, firstLeft, firstRight, userDefinedCalibrationPoint)

        val (adjustedRestRight, adjustedRestLeft) = adjustTracksToMatch(trackCalcResult.restLeft ++ restLeft, trackCalcResult.restRight ++ restRight, Some(trackCalcResult.endAddrMValue))

        val (adjustedLeft, adjustedRight) = strategy.setCalibrationPoints(trackCalcResult, userDefinedCalibrationPoint)

        (adjustedLeft ++ adjustedRestRight, adjustedRight ++ adjustedRestLeft)
      }
    }

    val rightSections = sections.flatMap(_.right.links)
    val leftSections = sections.flatMap(_.left.links)
    val rightLinks = ProjectSectionMValueCalculator.calculateMValuesForTrack(rightSections, userDefinedCalibrationPoint)
    val leftLinks = ProjectSectionMValueCalculator.calculateMValuesForTrack(leftSections, userDefinedCalibrationPoint)
    val (left, right) = adjustTracksToMatch(leftLinks.sortBy(_.startAddrMValue), rightLinks.sortBy(_.startAddrMValue), None)
    TrackSectionOrder.createCombinedSections(right, left)
  }

  def findStartingPoints(newLinks: Seq[ProjectLink], oldLinks: Seq[ProjectLink], otherRoadPartLinks: Seq[ProjectLink],
                         calibrationPoints: Seq[UserDefinedCalibrationPoint]): (Point, Point) = {
    val (rightStartPoint, pl) = findStartingPoint(newLinks.filter(_.track != Track.LeftSide), oldLinks.filter(_.track != Track.LeftSide), otherRoadPartLinks, calibrationPoints, (newLinks ++ oldLinks).filter(_.track == LeftSide))

    if ((oldLinks ++ newLinks).exists(l => GeometryUtils.areAdjacent(l.geometry, rightStartPoint) && l.track == Track.Combined))
      (rightStartPoint, rightStartPoint)
    else {
      // Get left track non-connected points and find the closest to right track starting point
      val leftLinks = newLinks.filter(_.track != Track.RightSide) ++ oldLinks.filter(_.track != Track.RightSide)
      val rightLinks = newLinks.filter(_.track == Track.RightSide) ++ oldLinks.filter(_.track == Track.RightSide)
      val chainEndPoints = TrackSectionOrder.findChainEndpoints(leftLinks)

      if (chainEndPoints.isEmpty)
        throw new InvalidAddressDataException("Missing left track starting points")

      val oldFirst = TrackSectionOrder.findOnceConnectedLinks(leftLinks).values.find(link => link.startAddrMValue == 0 && link.endAddrMValue != 0)
      val endPointsWithValues = chainEndPoints.filter(link => link._2.startAddrMValue == 0 && link._2.endAddrMValue != 0)

      (rightStartPoint,
        if (endPointsWithValues.size == 1) {
          val (linksWithValues, linksWithoutValues) = leftLinks.partition(_.endAddrMValue != 0)
          val otherEndPoint = chainEndPoints.filterNot(_._2.id == endPointsWithValues.head._2.id)
          val onceConnectLinks = TrackSectionOrder.findOnceConnectedLinks(linksWithoutValues)
          if (endPointsWithValues.nonEmpty && onceConnectLinks.nonEmpty && linksWithValues.size == 1 &&
            onceConnectLinks.exists(connected => GeometryUtils.areAdjacent(connected._2.getEndPoints._2, endPointsWithValues.head._2.getEndPoints._1)
              || GeometryUtils.areAdjacent(connected._2.getEndPoints._1, endPointsWithValues.head._2.getEndPoints._1)))
            otherEndPoint.head._1
          else
            endPointsWithValues.head._1
        } else if (chainEndPoints.forall(_._2.endAddrMValue != 0) && oldFirst.isDefined) {
          oldFirst.get.getEndPoints._1
        }
        else {
          if (leftLinks.forall(_.endAddrMValue == 0) && rightLinks.nonEmpty && rightLinks.exists(_.endAddrMValue != 0)) {
            val rightStartPoint = TrackSectionOrder.findChainEndpoints(rightLinks).find(link => link._2.startAddrMValue == 0 && link._2.endAddrMValue != 0)
            chainEndPoints.minBy(p => p._2.geometry.head.distance2DTo(rightStartPoint.get._1))._1
          } else if (leftLinks.forall(_.endAddrMValue == 0) && rightLinks.forall(_.endAddrMValue == 0)) {
            val candidateEndPoint = chainEndPoints.minBy(p => p._1.distance2DTo(rightStartPoint))._1
            val rightSideEndPoint = Seq(pl.getEndPoints._1, pl.getEndPoints._2).filterNot(_ == rightStartPoint)
            val direction = Seq(pl).map(p => p.getEndPoints._2 - p.getEndPoints._1).fold(Vector3d(0, 0, 0)) { case (v1, v2) => v1 + v2 }.normalize2D()
            val candidateLeftStartPoint = TrackSectionOrder.findChainEndpoints(leftLinks).minBy(_._1.distance2DTo(rightStartPoint))
            val candidateLeftOppositeEnd = getOppositeEnd(candidateLeftStartPoint._2, candidateLeftStartPoint._1)
            val startingPointsVector = Vector3d(candidateLeftStartPoint._1.x - candidateLeftOppositeEnd.x, candidateLeftStartPoint._1.y - candidateLeftOppositeEnd.y, candidateLeftStartPoint._1.z - candidateLeftOppositeEnd.z)
            val angle = startingPointsVector.angleXYWithNegativeValues(direction)
            if (candidateEndPoint.distance2DTo(rightStartPoint) > candidateEndPoint.distance2DTo(rightSideEndPoint.head) && angle > 0) {
              chainEndPoints.filterNot(_._1 == candidateEndPoint).head._1
            }
            else
              candidateEndPoint
          } else {
            val startPoint1 = chainEndPoints.minBy(p => p._1.distance2DTo(rightStartPoint))._1
            val startPoint2 = chainEndPoints.maxBy(p => p._1.distance2DTo(rightStartPoint))._1
            val connectingPoint = otherRoadPartLinks.find(l => GeometryUtils.areAdjacent(l.getLastPoint, startPoint1) || GeometryUtils.areAdjacent(l.getFirstPoint, startPoint2))
            if (otherRoadPartLinks.isEmpty || connectingPoint.nonEmpty)
              startPoint1
            else {
              chainEndPoints.maxBy(p => p._1.distance2DTo(rightStartPoint))._1
            }
          }
        }
      )
    }
  }


  /**
    * Find a starting point for this road part
    *
    * @param newLinks          Status = New links that need to have an address
    * @param oldLinks          Other links that already existed before the project
    * @param calibrationPoints The calibration points set by user as fixed addresses
    * @return Starting point
    */
  private def findStartingPoint(newLinks: Seq[ProjectLink], oldLinks: Seq[ProjectLink], otherRoadPartLinks: Seq[ProjectLink],
                                calibrationPoints: Seq[UserDefinedCalibrationPoint], oppositeTrackLinks: Seq[ProjectLink]): (Point, ProjectLink) = {

    def calibrationPointToPoint(calibrationPoint: UserDefinedCalibrationPoint): Option[(Point, ProjectLink)] = {
      val link = oldLinks.find(_.id == calibrationPoint.projectLinkId).orElse(newLinks.find(_.id == calibrationPoint.projectLinkId))
      link.flatMap(pl => GeometryUtils.calculatePointFromLinearReference(pl.geometry, calibrationPoint.segmentMValue).map(p => (p, pl)))
    }

    // Pick the one with calibration point set to zero: or any old link with lowest address: or new links by direction
    calibrationPoints.find(_.addressMValue == 0).flatMap(calibrationPointToPoint).getOrElse(
      oldLinks.filter(_.status == LinkStatus.UnChanged).sortBy(_.startAddrMValue).headOption.map(pl => (pl.startingPoint, pl)).getOrElse {
        val remainLinks = oldLinks ++ newLinks
        if (remainLinks.isEmpty)
          throw new InvalidAddressDataException("Missing right track starting project links")
        // Grab all the endpoints of the links
        val directionLinks = if (remainLinks.exists(_.sideCode != SideCode.Unknown)) remainLinks.filter(_.sideCode != SideCode.Unknown) else remainLinks

        val direction = directionLinks.map(p => p.getEndPoints._2 - p.getEndPoints._1).fold(Vector3d(0, 0, 0)) { case (v1, v2) => v1 + v2 }.normalize2D()

        val points = remainLinks.map(pl => pl.getEndPoints)

        // Approximate estimate of the mid point: averaged over count, not link length
        val midPoint = points.map(p => p._1 + (p._2 - p._1).scale(0.5)).foldLeft(Vector3d(0, 0, 0)) { case (x, p) =>
          (p - Point(0, 0)).scale(1.0 / points.size) + x
        }
        val chainEndPoints = TrackSectionOrder.findChainEndpoints(remainLinks)
        val (linksWithValues, linksWithoutValues) = remainLinks.partition(_.endAddrMValue != 0)
        val endPointsWithValues = chainEndPoints.filter(link => link._2.startAddrMValue == 0 && link._2.endAddrMValue != 0)
        val oldFirst = TrackSectionOrder.findOnceConnectedLinks(remainLinks).values.find(link => link.startAddrMValue == 0 && link.endAddrMValue != 0)
        if (endPointsWithValues.size == 1) {
          val otherEndPoint = chainEndPoints.filterNot(_._2.id == endPointsWithValues.head._2.id)
          val onceConnectLinks = TrackSectionOrder.findOnceConnectedLinks(linksWithoutValues)
          if (endPointsWithValues.nonEmpty && onceConnectLinks.nonEmpty && linksWithValues.size == 1
            && onceConnectLinks.exists(connected => GeometryUtils.areAdjacent(connected._2.getEndPoints._2, endPointsWithValues.head._2.getEndPoints._1)
            || GeometryUtils.areAdjacent(connected._2.getEndPoints._1, endPointsWithValues.head._2.getEndPoints._1)))
            otherEndPoint.head
          else
            endPointsWithValues.head
        } else if (chainEndPoints.forall(_._2.endAddrMValue != 0) && oldFirst.isDefined) {
          (oldFirst.get.getEndPoints._1, oldFirst.get)
        } else {
          if (remainLinks.forall(_.endAddrMValue == 0) && oppositeTrackLinks.nonEmpty && oppositeTrackLinks.exists(_.endAddrMValue != 0)) {
            val leftStartPoint = TrackSectionOrder.findChainEndpoints(oppositeTrackLinks).find(link => link._2.startAddrMValue == 0 && link._2.endAddrMValue != 0)
            chainEndPoints.minBy(p => p._2.geometry.head.distance2DTo(leftStartPoint.get._1))
          } else if (remainLinks.nonEmpty && oppositeTrackLinks.nonEmpty && remainLinks.forall(_.endAddrMValue == 0) && oppositeTrackLinks.forall(_.endAddrMValue == 0)) {
            val candidateRightStartPoint = chainEndPoints.minBy(p => direction.dot(p._1.toVector - midPoint))
            val candidateRightOppositeEnd = getOppositeEnd(candidateRightStartPoint._2, candidateRightStartPoint._1)
            val candidateLeftStartPoint = TrackSectionOrder.findChainEndpoints(oppositeTrackLinks).minBy(_._1.distance2DTo(candidateRightStartPoint._1))
            val candidateLeftOppositeEnd = getOppositeEnd(candidateLeftStartPoint._2, candidateLeftStartPoint._1)
            val startingPointsVector = Vector3d(candidateRightOppositeEnd.x - candidateLeftOppositeEnd.x, candidateRightOppositeEnd.y - candidateLeftOppositeEnd.y, candidateRightOppositeEnd.z - candidateLeftOppositeEnd.z)
            val angle = startingPointsVector.angleXYWithNegativeValues(direction)
            if (angle > 0) {
              chainEndPoints.filterNot(_._1.equals(candidateRightStartPoint._1)).head
            }
            else
              candidateRightStartPoint
          }
          else {
            val startPoint1 = chainEndPoints.minBy(p => direction.dot(p._1.toVector - midPoint))
            val startPoint2 = chainEndPoints.maxBy(p => direction.dot(p._1.toVector - midPoint))
            val connectingPoint = otherRoadPartLinks.find(l => GeometryUtils.areAdjacent(l.getLastPoint, startPoint1._1) || GeometryUtils.areAdjacent(l.getFirstPoint, startPoint2._1))
            if (otherRoadPartLinks.isEmpty || connectingPoint.nonEmpty) {
              startPoint1
            } else {
              chainEndPoints.maxBy(p => direction.dot(p._1.toVector - midPoint))
            }
          }
        }
      }
    )
  }

  private def getOppositeEnd(link: BaseRoadAddress, point: Point): Point = {
    val (st, en) = link.getEndPoints
    if (st.distance2DTo(point) < en.distance2DTo(point)) en else st
  }
}

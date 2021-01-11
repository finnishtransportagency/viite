package fi.liikennevirasto.viite.process.strategy

import fi.liikennevirasto.GeometryUtils
import fi.liikennevirasto.digiroad2.asset.SideCode
import fi.liikennevirasto.digiroad2.util.Track.LeftSide
import fi.liikennevirasto.digiroad2.util.{MissingRoadwayNumberException, MissingTrackException, RoadAddressException, Track}
import fi.liikennevirasto.digiroad2.{Point, Vector3d}
import fi.liikennevirasto.viite.dao.ProjectCalibrationPointDAO.UserDefinedCalibrationPoint
import fi.liikennevirasto.viite.dao._
import fi.liikennevirasto.viite.process._
import org.slf4j.LoggerFactory

import scala.annotation.tailrec
import scala.collection.immutable.ListMap

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
        case ex @ (_: MissingTrackException | _: MissingRoadwayNumberException) =>
          logger.warn(ex.getMessage)
          projectLinks ++ oldLinks
        case ex: InvalidAddressDataException =>
          logger.warn(s"Can't calculate road/road part ${part._1}/${part._2}: " + ex.getMessage)
          projectLinks ++ oldLinks
        case ex: NoSuchElementException =>
          logger.error("Delta calculation failed: " + ex.getMessage, ex)
          throw ex
        case ex: NullPointerException =>
          logger.error("Delta calculation failed (NPE)", ex)
          throw ex
        case ex: Exception =>
          logger.error("Delta calculation not possible: " + ex.getMessage)
          throw ex
      }
    }.toSeq
  }

  @tailrec
  private def continuousSection(seq: Seq[ProjectLink], processed: Seq[ProjectLink], hasNew: Boolean): (Seq[ProjectLink], Seq[ProjectLink]) = {
    if (seq.isEmpty)
      (processed, seq)
    else if (processed.isEmpty)
      continuousSection(seq.tail, Seq(seq.head), hasNew)
    else {
      val track = processed.last.track
      val roadType = processed.last.roadType
      val discontinuity = processed.last.discontinuity
      val discontinuousSections =
      if (hasNew)
        List(Discontinuity.Discontinuous, Discontinuity.MinorDiscontinuity, Discontinuity.ParallelLink)
      else
        List(Discontinuity.Discontinuous)
      if ((seq.head.track == track && seq.head.track == Track.Combined) || (seq.head.track == track && seq.head.track != Track.Combined && seq.head.roadType == roadType) && !discontinuousSections.contains(discontinuity)) {
        continuousSection(seq.tail, processed :+ seq.head, hasNew)
      } else {
        (processed, seq)
      }
    }
  }

  private def calculateSectionAddressValues(sections: Seq[CombinedSection],
                                            userDefinedCalibrationPoint: Map[Long, UserDefinedCalibrationPoint]): Seq[CombinedSection] = {

    def adjustTracksToMatch(leftLinks: Seq[ProjectLink], rightLinks: Seq[ProjectLink], previousStart: Option[Long]): (Seq[ProjectLink], Seq[ProjectLink]) = {
      if (rightLinks.isEmpty && leftLinks.isEmpty) {
        (Seq(), Seq())
      } else {
        if (rightLinks.isEmpty || leftLinks.isEmpty) {
          throw new MissingTrackException(s"Missing track, R: ${rightLinks.size}, L: ${leftLinks.size}")
        }

        val hasNew = if ((rightLinks ++ leftLinks).exists(_.status == LinkStatus.New)) true else false

        val (right, othersRight) = continuousSection(rightLinks.sortBy(pl => (pl.startAddrMValue, pl.roadPartNumber)), Seq(), hasNew)
        val (left, othersLeft) = continuousSection(leftLinks.sortBy(pl => (pl.startAddrMValue, pl.roadPartNumber)), Seq(), hasNew)

          val maxMValues = Seq(left.map(_.endAddrMValue).max, right.map(_.endAddrMValue).max)
          var (leftAligned, rightAligned, otherLeftAligned, otherRightAligned) = if (maxMValues.min == maxMValues.head) {
            val (rightAlignedWithLeft, other) = right.partition(_.endAddrMValue <= maxMValues.head)
            (left, rightAlignedWithLeft, othersLeft, othersRight ++ other)
          } else {
            val (leftAlignedWithRight, other) = left.partition(_.endAddrMValue <= maxMValues.last)
            (leftAlignedWithRight, right, othersLeft ++ other, othersRight)
          }

          leftAligned = if (leftAligned == null || leftAligned.size == 0) left else leftAligned
          rightAligned = if (rightAligned == null || rightAligned.size == 0) right else rightAligned

        val ((firstRight, restRight), (firstLeft, restLeft)): ((Seq[ProjectLink], Seq[ProjectLink]), (Seq[ProjectLink], Seq[ProjectLink])) =

            if ( leftAligned.maxBy(_.endAddrMValue).discontinuity !=
                 rightAligned.maxBy(_.endAddrMValue).discontinuity &&
              left.map(_.roadwayNumber).distinct.size == right.map(_.roadwayNumber).distinct.size) {
              TrackSectionRoadway.handleRoadwayNumbers(rightLinks, rightAligned, otherRightAligned, leftLinks, leftAligned, otherLeftAligned)
            } else {
              TrackSectionRoadway.handleRoadwayNumbers(rightLinks, right, othersRight, leftLinks, left, othersLeft)
            }


        if (firstRight.isEmpty || firstLeft.isEmpty) {
          throw new RoadAddressException(s"Mismatching tracks, R ${firstRight.size}, L ${firstLeft.size}")
        }

        if (firstRight.map(_.roadwayNumber).distinct.size != firstLeft.map(_.roadwayNumber).distinct.size) {
          throw new MissingRoadwayNumberException(s"Roadway numbers doesn't match on both tracks, R ${firstRight.map(_.roadwayNumber).distinct.size}, L ${firstLeft.map(_.roadwayNumber).distinct.size}")
        }

        val strategy: TrackCalculatorStrategy = TrackCalculatorContext.getStrategy(firstLeft, firstRight)
        logger.info(s"${strategy.name} strategy")
        val trackCalcResult = strategy.assignTrackMValues(previousStart, firstLeft, firstRight, userDefinedCalibrationPoint)

        val (adjustedRestRight, adjustedRestLeft) = adjustTracksToMatch(trackCalcResult.restLeft ++ restLeft, trackCalcResult.restRight ++ restRight, Some(trackCalcResult.endAddrMValue))

        (trackCalcResult.leftProjectLinks ++ adjustedRestRight, trackCalcResult.rightProjectLinks ++ adjustedRestLeft)
      }
    }

    val rightSections = sections.flatMap(_.right.links).distinct
    val leftSections = sections.flatMap(_.left.links).distinct
    val rightLinks = ProjectSectionMValueCalculator.calculateMValuesForTrack(rightSections, userDefinedCalibrationPoint)
    val leftLinks = ProjectSectionMValueCalculator.calculateMValuesForTrack(leftSections, userDefinedCalibrationPoint)
    //  adjustedRight and adjustedLeft already ordered by geometry -> TrackSectionOrder.orderProjectLinksTopologyByGeometry
    val (adjustedLeft, adjustedRight) = adjustTracksToMatch(leftLinks, rightLinks, None)
    val (right, left) = TrackSectionOrder.setCalibrationPoints(adjustedRight, adjustedLeft, userDefinedCalibrationPoint)
    TrackSectionOrder.createCombinedSections(right, left)
  }

  /**
    * Find starting point(s) after adding new operation for links in project.
    *
    * @param newLinks new ProjectLinks
    * @param oldLinks non-terminated already existing ProjectLinks
    * @param otherRoadPartLinks
    * @param calibrationPoints
    * @return Right and left starting points
    */
  def findStartingPoints(newLinks: Seq[ProjectLink], oldLinks: Seq[ProjectLink], otherRoadPartLinks: Seq[ProjectLink],
                         calibrationPoints: Seq[UserDefinedCalibrationPoint]): (Point, Point) = {
    val (rightStartPoint, pl) = findStartingPoint(newLinks.filter(_.track != Track.LeftSide), oldLinks.filter(_.track != Track.LeftSide), otherRoadPartLinks, calibrationPoints, (newLinks ++ oldLinks).filter(_.track == LeftSide))

    if ((oldLinks ++ newLinks).exists(l => GeometryUtils.areAdjacent(l.geometry, rightStartPoint) && l.track == Track.Combined)) {
      (rightStartPoint, rightStartPoint)
    } else {
      // Get left track non-connected points and find the closest to right track starting point
      val (leftLinks, rightLinks) = (newLinks ++ oldLinks).filterNot(_.track == Track.Combined).partition(_.track == Track.LeftSide)
      val chainEndPoints = TrackSectionOrder.findChainEndpoints(leftLinks)

      if (chainEndPoints.isEmpty)
        throw new MissingTrackException("Missing left track starting project links")

      val remainLinks = oldLinks ++ newLinks
      val points = remainLinks.map(pl => pl.getEndPoints)

      val (linksWithValues, linksWithoutValues) = remainLinks.partition(_.endAddrMValue != 0)
      val endPointsWithValues = ListMap(chainEndPoints.filter(link => link._2.startAddrMValue >= 0 && link._2.endAddrMValue != 0).toSeq
        .sortWith(_._2.startAddrMValue < _._2.startAddrMValue): _*)

      val foundConnectedLinks = TrackSectionOrder.findOnceConnectedLinks(remainLinks)
        .values.filter(link => link.startAddrMValue == 0 && link.endAddrMValue != 0)

      // In case there is some old starting link, we want to prioritize the one that didn't change or was not treated yet.
      // We could have more than two starting link since one of them can be Transferred from any part to this one.
      val oldFirst: Option[ProjectLink] =
      if (foundConnectedLinks.nonEmpty) {
        foundConnectedLinks.find(_.status == LinkStatus.New)
          .orElse(foundConnectedLinks.find(l => l.status == LinkStatus.UnChanged))
          .orElse(foundConnectedLinks.headOption)
      } else {
        None
      }

      (rightStartPoint,
        if (endPointsWithValues.size == 1) {
          val endLinkWithValues = endPointsWithValues.head._2
          val (currentEndPoint, otherEndPoint) = chainEndPoints.partition(_._2.id == endPointsWithValues.head._2.id)
          val onceConnectLinks = TrackSectionOrder.findOnceConnectedLinks(linksWithoutValues)
          val existsCloserProjectlink = linksWithValues.filter(pl => pl.startAddrMValue < endLinkWithValues.startAddrMValue && pl.id != endLinkWithValues.id)
          if (endPointsWithValues.nonEmpty && onceConnectLinks.nonEmpty && linksWithValues.nonEmpty
            && (oldFirst.isDefined && points.count(p => GeometryUtils.areAdjacent(p._1, oldFirst.get.startingPoint)
            || GeometryUtils.areAdjacent(p._2, oldFirst.get.startingPoint)) > 1) // New links before the old starting point
            && (onceConnectLinks.exists(connected => GeometryUtils.areAdjacent(connected._2.getEndPoints._2, endPointsWithValues.head._2.getEndPoints._1)
            || GeometryUtils.areAdjacent(connected._2.getEndPoints._1, endPointsWithValues.head._2.getEndPoints._1)
            || GeometryUtils.areAdjacent(linksWithValues.minBy(_.startAddrMValue).geometry, connected._2.getEndPoints._2)) || existsCloserProjectlink.nonEmpty)
          ) {
            otherEndPoint.head._1
          } else {
            if (currentEndPoint.head._1 == endPointsWithValues.head._2.endPoint)
              otherEndPoint.head._1
            else
              endPointsWithValues.head._1
          }
        } else {
          if (leftLinks.forall(_.endAddrMValue == 0) && rightLinks.nonEmpty && rightLinks.exists(_.endAddrMValue != 0)) {
            val rightStartPoint = TrackSectionOrder.findChainEndpoints(rightLinks).find(link => link._2.startAddrMValue == 0 && link._2.endAddrMValue != 0)
            chainEndPoints.minBy(p => p._1.distance2DTo(rightStartPoint.get._1))._1
          } else if (leftLinks.forall(_.endAddrMValue == 0) && rightLinks.forall(_.endAddrMValue == 0)) {
            val candidateEndPoint = chainEndPoints.minBy(p => p._1.distance2DTo(rightStartPoint))._1
            val rightSideEndPoint = Seq(pl.getEndPoints._1, pl.getEndPoints._2).filterNot(_ == rightStartPoint)
            val direction = Seq(pl).map(p => p.getEndPoints._2 - p.getEndPoints._1).fold(Vector3d(0, 0, 0)) { case (v1, v2) => v1 + v2 }.normalize2D()
            val candidateLeftStartPoint = TrackSectionOrder.findChainEndpoints(leftLinks).minBy(_._1.distance2DTo(rightStartPoint))
            val candidateLeftOppositeEnd = getOppositeEnd(candidateLeftStartPoint._2, candidateLeftStartPoint._1)
            val startingPointsVector = Vector3d(candidateLeftOppositeEnd.x - candidateLeftStartPoint._1.x, candidateLeftOppositeEnd.y - candidateLeftStartPoint._1.y, candidateLeftOppositeEnd.z - candidateLeftStartPoint._1.z)
            val angle = startingPointsVector.angleXYWithNegativeValues(direction)
            if (candidateEndPoint.distance2DTo(rightStartPoint) > candidateEndPoint.distance2DTo(rightSideEndPoint.head) && angle > 0) {
              chainEndPoints.filterNot(_._1 == candidateEndPoint).head._1
            } else {
              candidateEndPoint
            }
          } else {
            val startPoint1 = chainEndPoints.minBy(p => p._1.distance2DTo(rightStartPoint))._1
            val startPoint2 = chainEndPoints.maxBy(p => p._1.distance2DTo(rightStartPoint))._1
            val connectingPoint = otherRoadPartLinks.find(l => GeometryUtils.areAdjacent(l.getLastPoint, startPoint1) || GeometryUtils.areAdjacent(l.getFirstPoint, startPoint2))
            if (otherRoadPartLinks.isEmpty || connectingPoint.nonEmpty) {
              startPoint1
            } else {
              startPoint2
            }
          }
        }
      )
    }
  }

  /**
    * Find a starting point for this road part.
    *
    * @param newLinks          Status = New links that need to have an address
    * @param oldLinks          Other non-terminated links that already existed before the current operation
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
          throw new MissingTrackException("Missing right track starting project links")
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
        val endPointsWithValues = ListMap(chainEndPoints.filter(link => link._2.startAddrMValue >= 0 && link._2.endAddrMValue != 0).toSeq
          .sortWith(_._2.startAddrMValue < _._2.startAddrMValue): _*)

        val foundConnectedLinks = TrackSectionOrder.findOnceConnectedLinks(remainLinks).values.filter(link => link.startAddrMValue == 0 && link.endAddrMValue != 0)

        // In case there is some old starting link, we want to prioritize the one that didn't change or was not treated yet.
        // We could have more than two starting link since one of them can be Transferred from any part to this one.
        val oldFirst: Option[ProjectLink] =
          if (foundConnectedLinks.nonEmpty) {
            foundConnectedLinks.find(_.status == LinkStatus.New)
              .orElse(foundConnectedLinks.find(l => l.status == LinkStatus.UnChanged))
              .orElse(foundConnectedLinks.headOption)
          } else {
            None
          }

        if (endPointsWithValues.size == 1) {
          val endLinkWithValues = endPointsWithValues.head._2
          val (currentEndPoint, otherEndPoint) = chainEndPoints.partition(_._2.id == endPointsWithValues.head._2.id)
          val onceConnectLinks = TrackSectionOrder.findOnceConnectedLinks(linksWithoutValues)
          val existsCloserProjectlink = linksWithValues.filter(pl => pl.startAddrMValue < endLinkWithValues.startAddrMValue && pl.id != endLinkWithValues.id)
          if (endPointsWithValues.nonEmpty && onceConnectLinks.nonEmpty && linksWithValues.nonEmpty
            && (oldFirst.isDefined && points.count(p => GeometryUtils.areAdjacent(p._1, oldFirst.get.startingPoint)
            || GeometryUtils.areAdjacent(p._2, oldFirst.get.startingPoint)) > 1) // New links before the old starting point
            && (onceConnectLinks.exists(connected => GeometryUtils.areAdjacent(connected._2.getEndPoints._2, endPointsWithValues.head._2.getEndPoints._1)
            || GeometryUtils.areAdjacent(connected._2.getEndPoints._1, endPointsWithValues.head._2.getEndPoints._1)
            || GeometryUtils.areAdjacent(linksWithValues.minBy(_.startAddrMValue).geometry, connected._2.getEndPoints._2)) || existsCloserProjectlink.nonEmpty)
          ) {
            otherEndPoint.head
          } else {
            if (currentEndPoint.head._1 == endPointsWithValues.head._2.endPoint)
              otherEndPoint.head
            else
              endPointsWithValues.head
          }
        } else if (chainEndPoints.forall(_._2.endAddrMValue != 0) && oldFirst.isDefined) {
          val otherEndPoint = chainEndPoints.filterNot(_._2.id == oldFirst.get.id)

          if (otherEndPoint.nonEmpty && otherEndPoint.head._2.endPoint.connected(oldFirst.get.startingPoint)) {
            // Check reversed status to select starting point
            if (otherEndPoint.head._2.reversed && oldFirst.get.reversed) {
              (oldFirst.get.endPoint, oldFirst.get)
            } else {
              (otherEndPoint.head._1, otherEndPoint.head._2)
            }
          } else if (otherEndPoint.isEmpty) { // Only oldFirst is defined
            (oldFirst.get.getEndPoints._1, oldFirst.get)
          }
          else {
            // Check reversed status to select starting point
            if (oldFirst.get.reversed) {
              (oldFirst.get.getEndPoints._2, oldFirst.get)
            } else {
              (oldFirst.get.getEndPoints._1, oldFirst.get)
            }
          }
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
            val angle =
              if (startingPointsVector == Vector3d(0.0, 0.0, 0.0)) {
                val startingPointVector = Vector3d(candidateRightStartPoint._1.x - candidateLeftStartPoint._1.x, candidateRightStartPoint._1.y - candidateLeftStartPoint._1.y, candidateRightStartPoint._1.z - candidateLeftStartPoint._1.z)
                startingPointVector.angleXYWithNegativeValues(direction)
              } else {
                startingPointsVector.angleXYWithNegativeValues(direction)
              }
            if (angle > 0) {
              chainEndPoints.filterNot(_._1.equals(candidateRightStartPoint._1)).head
            } else {
              candidateRightStartPoint
            }
          } else {
            val startPoint1 = chainEndPoints.minBy(p => direction.dot(p._1.toVector - midPoint))
            val startPoint2 = chainEndPoints.maxBy(p => direction.dot(p._1.toVector - midPoint))
            val connectingPoint = otherRoadPartLinks.find(l => GeometryUtils.areAdjacent(l.getLastPoint, startPoint1._1) || GeometryUtils.areAdjacent(l.getFirstPoint, startPoint2._1))
            if (otherRoadPartLinks.isEmpty || connectingPoint.nonEmpty) {
              startPoint1
            } else {
              startPoint2
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

package fi.liikennevirasto.viite.process.strategy

import fi.liikennevirasto.digiroad2.{GeometryUtils, Point, Vector3d}
import fi.liikennevirasto.digiroad2.util.{RoadAddressException, Track}
import fi.liikennevirasto.viite.dao.CalibrationPointDAO.UserDefinedCalibrationPoint
import fi.liikennevirasto.viite.dao._
import fi.liikennevirasto.viite.process._
import org.slf4j.LoggerFactory

class DefaultSectionCalculatorStrategy extends RoadAddressSectionCalculatorStrategy {

  private val logger = LoggerFactory.getLogger(getClass)

  override val name: String = "Normal Section"

  override def assignMValues(newProjectLinks: Seq[ProjectLink], oldProjectLinks: Seq[ProjectLink], userCalibrationPoints: Seq[UserDefinedCalibrationPoint]): Seq[ProjectLink] = {

    val groupedProjectLinks = newProjectLinks.groupBy(record => (record.roadNumber, record.roadPartNumber))
    val groupedOldLinks = oldProjectLinks.groupBy(record => (record.roadNumber, record.roadPartNumber))
    val group = (groupedProjectLinks.keySet ++ groupedOldLinks.keySet).map(k =>
      k -> (groupedProjectLinks.getOrElse(k, Seq()), groupedOldLinks.getOrElse(k, Seq())))
    group.flatMap { case (part, (projectLinks, oldLinks)) =>
      try {
        val (right, left) = TrackSectionOrder.orderProjectLinksTopologyByGeometry(
          findStartingPoints(projectLinks, oldLinks, userCalibrationPoints), projectLinks ++ oldLinks)
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

  private def getContinuousTrack(seq: Seq[ProjectLink]): (Seq[ProjectLink], Seq[ProjectLink]) = {
    val track = seq.headOption.map(_.track).getOrElse(Track.Unknown)
    val continuousProjectLinks = seq.takeWhile(pl => pl.track == track)
    (continuousProjectLinks, seq.drop(continuousProjectLinks.size))
  }

  private def calculateSectionAddressValues(sections: Seq[CombinedSection],
                                            userDefinedCalibrationPoint: Map[Long, UserDefinedCalibrationPoint]): Seq[CombinedSection] = {

    def adjustTracksToMatch(leftLinks: Seq[ProjectLink], rightLinks: Seq[ProjectLink], previousStart: Option[Long]): (Seq[ProjectLink], Seq[ProjectLink]) = {
      if (rightLinks.isEmpty && leftLinks.isEmpty) {
        (Seq(), Seq())
      } else {
        val (firstRight, restRight) = getContinuousTrack(rightLinks)
        val (firstLeft, restLeft) = getContinuousTrack(leftLinks)

        if (firstRight.isEmpty || firstLeft.isEmpty)
          throw new RoadAddressException(s"Mismatching tracks, R ${firstRight.size}, L ${firstLeft.size}")

        val strategy = TrackCalculatorContext.getStrategy(firstLeft, firstRight)
        val trackCalcResult = strategy.assignTrackMValues(previousStart, firstLeft, firstRight, userDefinedCalibrationPoint)

        val (adjustedRestRight, adjustedRestLeft) = adjustTracksToMatch(trackCalcResult.restLeft ++ restLeft, trackCalcResult.restRight ++ restRight, Some(trackCalcResult.endAddrMValue))

        val (adjustedLeft, adjustedRight) = strategy.setCalibrationPoints(trackCalcResult, userDefinedCalibrationPoint)

        (adjustedLeft ++ adjustedRestRight, adjustedRight ++ adjustedRestLeft)
      }
    }
    val rightLinks = ProjectSectionMValueCalculator.calculateMValuesForTrack(sections.flatMap(_.right.links), userDefinedCalibrationPoint)
    val leftLinks = ProjectSectionMValueCalculator.calculateMValuesForTrack(sections.flatMap(_.left.links), userDefinedCalibrationPoint)
    val (left, right) = adjustTracksToMatch(leftLinks.sortBy(_.startAddrMValue), rightLinks.sortBy(_.startAddrMValue), None)
    TrackSectionOrder.createCombinedSections(right, left)
  }

  private def findStartingPoints(newLinks: Seq[ProjectLink], oldLinks: Seq[ProjectLink],
                                    calibrationPoints: Seq[UserDefinedCalibrationPoint]): (Point, Point) = {
    def fetchEndpoints(projectLink: ProjectLink): (Point, Point) = {
      (projectLink.startingPoint, projectLink.endPoint)
    }

    def processTracks(firstTrackToProcess: Track, secondTrackToProcess: Track) = {
      val firstTrackPoint = findStartingPoint(newLinks.filter(_.track != firstTrackToProcess), oldLinks.filter(_.track != firstTrackToProcess),
        calibrationPoints)
      if ((oldLinks ++ newLinks).exists(l => GeometryUtils.areAdjacent(l.geometry, firstTrackPoint) && l.track == Track.Combined))
        (firstTrackPoint, firstTrackPoint)
      else {
        // Get left track non-connected points and find the closest to right track starting point
        val secondTrackLinks = newLinks.filter(_.track != secondTrackToProcess) ++ oldLinks.filter(_.track != secondTrackToProcess)
        val secondTrackPoints = TrackSectionOrder.findOnceConnectedLinks(secondTrackLinks).keys
        if (secondTrackPoints.isEmpty)
          throw new InvalidAddressDataException("Missing left track starting points")
        val secondTrackPoint = secondTrackPoints.minBy(lp => (lp - firstTrackPoint).length())
        (firstTrackToProcess,secondTrackToProcess) match {
          case (Track.LeftSide , Track.RightSide) => (firstTrackPoint, secondTrackPoint)
          case (Track.RightSide , Track.LeftSide) => (secondTrackPoint, firstTrackPoint)
          case _ => throw new Exception(s"Supplied track combination invalid, valid should be either ${(Track.LeftSide , Track.RightSide)}, ${(Track.RightSide , Track.LeftSide)} the supplied values were: ${(firstTrackToProcess , secondTrackToProcess)}")
        }
      }
    }

    /*
    1. Find all new and old links by track
    2. Find what track has the "biggest" start point and use that track as the basis of the calculation
    3. Use the "findStartingPoint" routine on said track side
    4. The remaining track side will be subject to the regular treatment
     */
    val newRightSideLinks = newLinks.filter(_.track != Track.LeftSide)
    val oldRightSideLinks = oldLinks.filter(_.track != Track.LeftSide)
    val newLeftSideLinks = newLinks.filter(_.track != Track.RightSide)
    val oldLeftSideLinks = oldLinks.filter(_.track != Track.RightSide)


    val rightSideLinks = (newRightSideLinks++oldRightSideLinks).map(fetchEndpoints).minBy(p => p._1.distance2DTo(Point(0.0, 0.0, 0.0)))
    val leftSideLinks = (newLeftSideLinks++oldLeftSideLinks).map(fetchEndpoints).minBy(p => p._1.distance2DTo(Point(0.0, 0.0, 0.0)))

    if (rightSideLinks._1.distance2DTo(Point(0.0, 0.0, 0.0)) > leftSideLinks._1.distance2DTo(Point(0.0, 0.0, 0.0))) {
      //Choose right side first
      processTracks(Track.LeftSide, Track.RightSide)
    } else {
      //Choose left side first
      processTracks(Track.RightSide, Track.LeftSide)
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
  private def findStartingPoint(newLinks: Seq[ProjectLink], oldLinks: Seq[ProjectLink],
                                calibrationPoints: Seq[UserDefinedCalibrationPoint]): Point = {
    def calibrationPointToPoint(calibrationPoint: UserDefinedCalibrationPoint): Option[Point] = {
      val link = oldLinks.find(_.id == calibrationPoint.projectLinkId).orElse(newLinks.find(_.id == calibrationPoint.projectLinkId))
      link.flatMap(pl => GeometryUtils.calculatePointFromLinearReference(pl.geometry, calibrationPoint.segmentMValue))
    }
    // Pick the one with calibration point set to zero: or any old link with lowest address: or new links by direction
    calibrationPoints.find(_.addressMValue == 0).flatMap(calibrationPointToPoint).getOrElse(
      oldLinks.filter(_.status == LinkStatus.UnChanged).sortBy(_.startAddrMValue).headOption.map(_.startingPoint).getOrElse {
        val remainLinks = oldLinks ++ newLinks
        if (remainLinks.isEmpty)
          throw new InvalidAddressDataException("Missing right track starting project links")
        //Grab all the endpoints of the links
        val points = remainLinks.map(pl => (pl.startingPoint, pl.endPoint))
        //First it will return the subtraction between endPoint - startPoint of all the points variable
        //Then it will sum all the results of the previous operation as a vector (v1 is the accumulated result, v2 is the next item to sum
        val direction = points.map(p => p._2 - p._1).fold(Vector3d(0, 0, 0)) { case (v1, v2) => v1 + v2 }.normalize2D()
        // Approximate estimate of the mid point: averaged over count, not link length
        val midPoint = points.map(p => p._1 + (p._2 - p._1).scale(0.5)).foldLeft(Vector3d(0, 0, 0)) { case (x, p) =>
          (p - Point(0, 0)).scale(1.0 / points.size) + x
        }
        TrackSectionOrder.findOnceConnectedLinks(remainLinks).keys.minBy(p => Math.abs(direction.dot(p.toVector - midPoint)))
      }

    )
  }
}

package fi.liikennevirasto.viite.process.Strategies

import fi.liikennevirasto.digiroad2.asset.SideCode.{AgainstDigitizing, TowardsDigitizing}
import fi.liikennevirasto.digiroad2.{GeometryUtils, Point, Vector3d}
import fi.liikennevirasto.digiroad2.util.{RoadAddressException, Track}
import fi.liikennevirasto.viite.dao.CalibrationPointDAO.UserDefinedCalibrationPoint
import fi.liikennevirasto.viite.dao.Discontinuity.MinorDiscontinuity
import fi.liikennevirasto.viite.dao._
import fi.liikennevirasto.viite.process._
import org.slf4j.LoggerFactory

object TrackSectionCalculatorContext {

  private lazy val defaultTrackSectionCalculatorStrategy: DefaultTrackSectionCalculatorStrategy = {
    new DefaultTrackSectionCalculatorStrategy
  }

  private val defaultStrategy = defaultTrackSectionCalculatorStrategy
  private val strategies = Seq[TrackSectionCalculatorStrategy]()

  def getStrategy(leftProjectLinks: Seq[ProjectLink], rightProjectLinks: Seq[ProjectLink]): TrackSectionCalculatorStrategy = {
    strategies.find(_.applicableStrategy(leftProjectLinks, rightProjectLinks)).getOrElse(defaultStrategy)
  }
}

case class TrackSectionCalculatorResult(leftProjectLinks: Seq[ProjectLink], rightProjectLinks: Seq[ProjectLink], stratAddrMValue: Long, endAddrMValue: Long)

trait TrackSectionCalculatorStrategy {
  def applicableStrategy(leftProjectLinks: Seq[ProjectLink], rightProjectLinks: Seq[ProjectLink]): Boolean = false

  def assignTrackMValues(startAddress: Option[Long] , leftProjectLinks: Seq[ProjectLink], rightProjectLinks: Seq[ProjectLink], userDefinedCalibrationPoint: Map[Long, UserDefinedCalibrationPoint]): TrackSectionCalculatorResult
}

class DefaultTrackSectionCalculatorStrategy extends TrackSectionCalculatorStrategy {

  protected def averageOfAddressMValues(rAddrM: Double, lAddrM: Double, reversed: Boolean): Long = {
    val average = 0.5 * (rAddrM + lAddrM)

    if (reversed) {
      if (rAddrM > lAddrM) Math.floor(average).round else Math.ceil(average).round
    } else {
      if (rAddrM > lAddrM) Math.ceil(average).round else Math.floor(average).round
    }
  }

  protected def getFixedAddress(leftLink: ProjectLink, rightLink: ProjectLink,
                      userCalibrationPoint: Option[UserDefinedCalibrationPoint] = None): (Long, Long) = {

    val reversed = rightLink.reversed || leftLink.reversed

    (leftLink.status, rightLink.status) match {
      case (LinkStatus.Transfer, LinkStatus.Transfer) | (LinkStatus.UnChanged, LinkStatus.UnChanged) =>
        (averageOfAddressMValues(rightLink.startAddrMValue, leftLink.startAddrMValue, reversed), averageOfAddressMValues(rightLink.endAddrMValue, leftLink.endAddrMValue, reversed))
      case (LinkStatus.UnChanged, _) | (LinkStatus.Transfer, _) =>
        (rightLink.startAddrMValue, rightLink.endAddrMValue)
      case (_ , LinkStatus.UnChanged) | (_, LinkStatus.Transfer) =>
        (leftLink.startAddrMValue, leftLink.endAddrMValue)
      case _ =>
        userCalibrationPoint.map(c => (c.addressMValue, c.addressMValue)).getOrElse(
          (averageOfAddressMValues(rightLink.startAddrMValue, leftLink.startAddrMValue, reversed), averageOfAddressMValues(rightLink.endAddrMValue, leftLink.endAddrMValue, reversed))
        )
    }
  }

  protected def assignValues(seq: Seq[ProjectLink], st: Long, en: Long, factor: TrackAddressingFactors, userDefinedCalibrationPoint: Map[Long, UserDefinedCalibrationPoint]): Seq[ProjectLink] = {
    def setLastEndAddrMValue(projectLinks: Seq[ProjectLink]): Seq[ProjectLink] = {
      if (projectLinks.last.status != LinkStatus.NotHandled)
        projectLinks.init :+ projectLinks.last.copy(endAddrMValue = en)
      else
        projectLinks
    }

    val coEff = (en - st - factor.unChangedLength - factor.transferLength) / factor.newLength
    setLastEndAddrMValue(ProjectSectionMValueCalculator.assignLinkValues(seq, userDefinedCalibrationPoint, Some(st.toDouble), Some(en.toDouble), coEff))
  }

  protected def adjustTwoTracks(right: Seq[ProjectLink], left: Seq[ProjectLink], startM: Long, endM: Long, userDefinedCalibrationPoint: Map[Long, UserDefinedCalibrationPoint]) = {
    (assignValues(left, startM, endM, ProjectSectionMValueCalculator.calculateAddressingFactors(left), userDefinedCalibrationPoint),
      assignValues(right, startM, endM, ProjectSectionMValueCalculator.calculateAddressingFactors(right), userDefinedCalibrationPoint))
  }

  override def assignTrackMValues(startAddress: Option[Long] , leftProjectLinks: Seq[ProjectLink], rightProjectLinks: Seq[ProjectLink], userDefinedCalibrationPoint: Map[Long, UserDefinedCalibrationPoint]): TrackSectionCalculatorResult = {
    val availableCalibrationPoint = userDefinedCalibrationPoint.get(rightProjectLinks.last.id).orElse(userDefinedCalibrationPoint.get(leftProjectLinks.last.id))

    val startSectionAddress = startAddress.getOrElse(getFixedAddress(leftProjectLinks.head, rightProjectLinks.head)._1)
    val endSectionAddress = getFixedAddress(leftProjectLinks.last, rightProjectLinks.last, availableCalibrationPoint)._2

    val (adjustedLeft, adjustedRight) = adjustTwoTracks(rightProjectLinks, leftProjectLinks, startSectionAddress, endSectionAddress, userDefinedCalibrationPoint)

    TrackSectionCalculatorResult(adjustedLeft, adjustedRight, startSectionAddress, endSectionAddress)
  }
}

class CombineSectionCalculatorStrategy extends RoadSectionCalculatorStrategy {

  private val logger = LoggerFactory.getLogger(getClass)

  override val name: String = "Combine Section"

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
        //TODO
        //Delete all the exisint calibration points on the projecLinks
        //Add again the existing road address calibration points
        //Then each section can add calibration points
//        calculateSectionAddressValues(ordSections, calMap)

        //Delete this code
        val calculatedSections = calculateSectionAddressValues(ordSections, calMap)
        val links = calculatedSections.flatMap{ sec =>
          if (sec.right == sec.left)
            assignCalibrationPoints(Seq(), sec.right.links, calMap)
          else {
            assignCalibrationPoints(Seq(), sec.right.links, calMap) ++
              assignCalibrationPoints(Seq(), sec.left.links, calMap)
          }
        }
        eliminateExpiredCalibrationPoints(links)
        //Until here
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
    val track= seq.headOption.map(_.track).getOrElse(Track.Unknown)
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

        val strategy = TrackSectionCalculatorContext.getStrategy(firstLeft, firstRight)
        val trackCalcResult = strategy.assignTrackMValues(previousStart, firstLeft, firstRight, userDefinedCalibrationPoint)

        val (adjustedRestRight, adjustedRestLeft) = adjustTracksToMatch(restLeft, restRight, Some(trackCalcResult.endAddrMValue))

        (trackCalcResult.leftProjectLinks ++ adjustedRestRight, trackCalcResult.rightProjectLinks ++ adjustedRestLeft)
      }
    }

    val rightLinks = ProjectSectionMValueCalculator.calculateMValuesForTrack(sections.flatMap(_.right.links), userDefinedCalibrationPoint)
    val leftLinks = ProjectSectionMValueCalculator.calculateMValuesForTrack(sections.flatMap(_.left.links), userDefinedCalibrationPoint)
    val (left, right) = adjustTracksToMatch(leftLinks.sortBy(_.startAddrMValue), rightLinks.sortBy(_.startAddrMValue), None)
    TrackSectionOrder.createCombinedSections(right, left)
  }

  private def findStartingPoints(newLinks: Seq[ProjectLink], oldLinks: Seq[ProjectLink],
                                 calibrationPoints: Seq[UserDefinedCalibrationPoint]): (Point, Point) = {
    val rightStartPoint = findStartingPoint(newLinks.filter(_.track != Track.LeftSide), oldLinks.filter(_.track != Track.LeftSide),
      calibrationPoints)
    if ((oldLinks ++ newLinks).exists(l => GeometryUtils.areAdjacent(l.geometry, rightStartPoint) && l.track == Track.Combined))
      (rightStartPoint, rightStartPoint)
    else {
      // Get left track non-connected points and find the closest to right track starting point
      val leftLinks = newLinks.filter(_.track != Track.RightSide) ++ oldLinks.filter(_.track != Track.RightSide)
      val leftPoints = TrackSectionOrder.findOnceConnectedLinks(leftLinks).keys
      if (leftPoints.isEmpty)
        throw new InvalidAddressDataException("Missing left track starting points")
      val leftStartPoint = leftPoints.minBy(lp => (lp - rightStartPoint).length())
      (rightStartPoint, leftStartPoint)
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
        val points = remainLinks.map(pl => (pl.startingPoint, pl.endPoint))
        val direction = points.map(p => p._2 - p._1).fold(Vector3d(0, 0, 0)) { case (v1, v2) => v1 + v2 }.normalize2D()
        // Approximate estimate of the mid point: averaged over count, not link length
        val midPoint = points.map(p => p._1 + (p._2 - p._1).scale(0.5)).foldLeft(Vector3d(0, 0, 0)) { case (x, p) =>
          (p - Point(0, 0)).scale(1.0 / points.size) + x
        }
        TrackSectionOrder.findOnceConnectedLinks(remainLinks).keys.minBy(p => direction.dot(p.toVector - midPoint))
      }

    )
  }

  // TODO: use user given start calibration points (US-564, US-666, US-639)
  // Sort: smallest endAddrMValue is first but zero does not count.
  def makeStartCP(projectLink: ProjectLink) = {
    Some(CalibrationPoint(projectLink.linkId, if (projectLink.sideCode == TowardsDigitizing) 0.0 else projectLink.geometryLength, projectLink.startAddrMValue))
  }

  def makeEndCP(projectLink: ProjectLink, userDefinedCalibrationPoint: Option[UserDefinedCalibrationPoint]) = {
    val segmentValue = if (projectLink.sideCode == AgainstDigitizing) 0.0 else projectLink.geometryLength
    val addressValue = (userDefinedCalibrationPoint, (projectLink.startAddrMValue, projectLink.endAddrMValue)) match {
      case (Some(usercp), addr) => if (usercp.addressMValue < addr._1) addr._2 else usercp.addressMValue
      case (None, addr) => addr._2
    }
    Some(CalibrationPoint(projectLink.linkId, segmentValue, addressValue))
  }
  //TODO delete this method
  def makeLink(link: ProjectLink, userDefinedCalibrationPoint: Option[UserDefinedCalibrationPoint],
               startCP: Boolean, endCP: Boolean) = {
    val sCP = if (startCP) makeStartCP(link) else None
    val eCP = if (endCP) makeEndCP(link, userDefinedCalibrationPoint) else None
    link.copy(calibrationPoints = (sCP, eCP))
  }
  //TODO delete this method
  def assignCalibrationPoints(ready: Seq[ProjectLink], unprocessed: Seq[ProjectLink],
                              calibrationPoints: Map[Long, UserDefinedCalibrationPoint]): Seq[ProjectLink] = {
    val link = unprocessed.head
    //checks calibration points which the link had before starting the project
    val raCalibrationCode = RoadAddressDAO.getRoadAddressCalibrationCode(link.roadAddressId)
    val raStartCP = raCalibrationCode == CalibrationCode.AtBeginning || raCalibrationCode == CalibrationCode.AtBoth
    val raEndCP = raCalibrationCode == CalibrationCode.AtEnd || raCalibrationCode == CalibrationCode.AtBoth
    // If first one
    if (ready.isEmpty) {
      // If there is only one link in section we put two calibration points in it
      if (unprocessed.size == 1) {
        Seq(makeLink(link, calibrationPoints.get(link.id), startCP = true, endCP = true))
      } else if (link.discontinuity == MinorDiscontinuity) {
        assignCalibrationPoints(Seq(makeLink(link, calibrationPoints.get(link.id), startCP = true, endCP = true)), unprocessed.tail, calibrationPoints)
      } else {
        assignCalibrationPoints(Seq(makeLink(link, calibrationPoints.get(link.id), startCP = true, endCP = raEndCP)), unprocessed.tail, calibrationPoints)
      }
      // If last one
    } else if (unprocessed.tail.isEmpty) {
      ready ++ Seq(makeLink(link, calibrationPoints.get(link.id), startCP = raStartCP, endCP = true))
    } else {
      //validate if are adjacent in the middle. If it has discontinuity, add a calibration point
      if (!GeometryUtils.areAdjacent(link.getLastPoint(), unprocessed.tail.head.getFirstPoint())) {
        assignCalibrationPoints(ready ++ Seq(makeLink(link, calibrationPoints.get(link.id), startCP = raStartCP, endCP = true)), unprocessed.tail, calibrationPoints)
      } else if (!GeometryUtils.areAdjacent(link.getFirstPoint(), ready.last.getLastPoint())) {
        assignCalibrationPoints(ready ++ Seq(makeLink(link, calibrationPoints.get(link.id), startCP = true, endCP = raEndCP)), unprocessed.tail, calibrationPoints)
      } else {
        // a middle one, add to sequence and continue
        assignCalibrationPoints(ready ++ Seq(makeLink(link, calibrationPoints.get(link.id), startCP = raStartCP, endCP = raEndCP)), unprocessed.tail, calibrationPoints)
      }
    }
  }
  //TODO delete this method
  private def eliminateExpiredCalibrationPoints(roadPartLinks: Seq[ProjectLink]): Seq[ProjectLink] = {
    val tracks = roadPartLinks.groupBy(_.track)
    tracks.mapValues { links =>
      links.map { l =>
        //Doesn't eliminate calibration points which road link had before starting the project
        val raCalibrationCode = RoadAddressDAO.getRoadAddressCalibrationCode(l.roadAddressId)
        val raStartCP = raCalibrationCode == CalibrationCode.AtBeginning || raCalibrationCode == CalibrationCode.AtBoth
        val raEndCP = raCalibrationCode == CalibrationCode.AtEnd || raCalibrationCode == CalibrationCode.AtBoth
        val calibrationPoints =
          l.calibrationPoints match {
            case (None, None) => l.calibrationPoints
            case (Some(st), None) =>
              if (links.exists(link => link.endAddrMValue == st.addressMValue && link.discontinuity != MinorDiscontinuity) && !raStartCP)
                (None, None)
              else
                l.calibrationPoints
            case (None, Some(en)) =>
              if (links.exists(_.startAddrMValue == en.addressMValue && l.discontinuity != MinorDiscontinuity) && !raEndCP)
                (None, None)
              else
                l.calibrationPoints
            case (Some(st), Some(en)) =>
              (
                if (links.exists(link => link.endAddrMValue == st.addressMValue && link.discontinuity != MinorDiscontinuity) && !raStartCP)
                  None
                else
                  Some(st),
                if (links.exists(_.startAddrMValue == en.addressMValue && l.discontinuity != MinorDiscontinuity) && !raEndCP)
                  None
                else
                  Some(en)
              )
          }
        l.copy(calibrationPoints = calibrationPoints)
      }
    }.values.flatten.toSeq
  }
}

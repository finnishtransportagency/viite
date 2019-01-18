package fi.liikennevirasto.viite.process.strategy

import fi.liikennevirasto.digiroad2.{GeometryUtils, Point, Vector3d}
import fi.liikennevirasto.digiroad2.asset.SideCode
import fi.liikennevirasto.digiroad2.util.RoadAddressException
import fi.liikennevirasto.viite.NewRoadway
import fi.liikennevirasto.viite.dao.CalibrationPointDAO.UserDefinedCalibrationPoint
import fi.liikennevirasto.viite.dao.CalibrationPointSource.{ProjectLinkSource, RoadAddressSource, UnknownSource}
import fi.liikennevirasto.viite.dao.Discontinuity.{Discontinuous, MinorDiscontinuity}
import fi.liikennevirasto.viite.dao.LinkStatus.NotHandled
import fi.liikennevirasto.viite.dao._
import fi.liikennevirasto.viite.process.{ProjectSectionMValueCalculator, TrackAddressingFactors}
import fi.liikennevirasto.viite.util.CalibrationPointsUtils


object TrackCalculatorContext {

  private lazy val minorDiscontinuityStrategy: DiscontinuityTrackCalculatorStrategy = {
    new DiscontinuityTrackCalculatorStrategy(MinorDiscontinuity)
  }

  private lazy val discontinuousStrategy: DiscontinuityTrackCalculatorStrategy = {
    new DiscontinuityTrackCalculatorStrategy(Discontinuous)
  }

  private lazy val linkStatusChangeTrackCalculatorStrategy: LinkStatusChangeTrackCalculatorStrategy = {
    new LinkStatusChangeTrackCalculatorStrategy
  }

  private lazy val terminatedLinkStatusChangeStrategy: TerminatedLinkStatusChangeStrategy = {
    new TerminatedLinkStatusChangeStrategy
  }

  private lazy val defaultTrackCalculatorStrategy: DefaultTrackCalculatorStrategy = {
    new DefaultTrackCalculatorStrategy
  }

  private val strategies = Seq(minorDiscontinuityStrategy, discontinuousStrategy, linkStatusChangeTrackCalculatorStrategy, terminatedLinkStatusChangeStrategy)

  def getNextStrategy(projectLinks: Seq[ProjectLink]): Option[(Long, TrackCalculatorStrategy)] = {
    val head = projectLinks.head
    projectLinks.flatMap {
      pl =>
        strategies.filter(strategy => strategy.applicableStrategy(head, pl)).map(strategy => (strategy.getStrategyAddress(pl), strategy))
    }.sortBy(_._1).headOption
  }

  def getStrategy(leftProjectLinks: Seq[ProjectLink], rightProjectLinks: Seq[ProjectLink]): TrackCalculatorStrategy = {

    val leftStrategy = getNextStrategy(leftProjectLinks)
    val rightStrategy = getNextStrategy(rightProjectLinks)

    //Here we should choice the nearest strategy from the lower address, because for the same track
    //we can have for example link status changes and minor discontinuity in that case we should
    //apply the strategy that comes first on the address

    (leftStrategy, rightStrategy) match {
      case (Some(ls), Some(rs)) => if (rs._1 <= ls._1) rs._2 else ls._2
      case (Some(ls), _) => ls._2
      case (_, Some(rs)) => rs._2
      case _ => defaultTrackCalculatorStrategy
    }
  }
}

case class TrackCalculatorResult(leftProjectLinks: Seq[ProjectLink], rightProjectLinks: Seq[ProjectLink], stratAddrMValue: Long, endAddrMValue: Long, restLeft: Seq[ProjectLink] = Seq(), restRight: Seq[ProjectLink] = Seq())

/**
  * Strategy used on the DefaultSectionCalculatorStrategy
  */
trait TrackCalculatorStrategy {

  /**
    * Split the project link at the specified address
    *
    * @param pl      Project link
    * @param address Address measure
    * @return Returns two project links, the existing one with changed measures and a new one
    */
  protected def splitAt(pl: ProjectLink, address: Long): (ProjectLink, ProjectLink) = {
    val coefficient = (pl.endMValue - pl.startMValue) / (pl.endAddrMValue - pl.startAddrMValue)
    val splitMeasure = pl.startMValue + ((pl.startAddrMValue - address) * coefficient)
    (
      pl.copy(geometry = GeometryUtils.truncateGeometry2D(pl.geometry, startMeasure = 0, endMeasure = splitMeasure), geometryLength = splitMeasure, connectedLinkId = Some(pl.linkId)),
      pl.copy(id = NewRoadway, geometry = GeometryUtils.truncateGeometry2D(pl.geometry, startMeasure = splitMeasure, endMeasure = pl.geometryLength), geometryLength = pl.geometryLength - splitMeasure, connectedLinkId = Some(pl.linkId))
    )
  }

  /**
    * Average between two address measures.
    * If the right side is greater than the left side returns the largest (closest to positive infinity) value
    * If the right side is less than the left side returns the smallest (closest to negative infinity) value
    * NOTE: If we have the reversed set to true the previous conditions are inverted
    *
    * @param rAddrM   Right address measure
    * @param lAddrM   Left address measure
    * @param reversed True if the road was reverted
    * @return Returns the average between two measures
    */
  protected def averageOfAddressMValues(rAddrM: Double, lAddrM: Double, reversed: Boolean): Long = {
    val average = 0.5 * (rAddrM + lAddrM)
    if (reversed) {
      if (rAddrM > lAddrM) Math.floor(average).round else Math.ceil(average).round
    } else {
      if (rAddrM > lAddrM) Math.ceil(average).round else Math.floor(average).round
    }
  }

  /**
    * Recalculate all project links and assign new measures, depending on the start and end measures given as parameters
    *
    * @param seq                         Project links to be recalculated
    * @param st                          Start address measure
    * @param en                          End address measure
    * @param factor                      All the factors, that will be used to get the coefficient for project link measures with status NEW
    * @param userDefinedCalibrationPoint Defined calibration point, that will be used to set project link measure with status NEW
    * @return Returns all the given project links with recalculated measures
    */
  protected def assignValues(seq: Seq[ProjectLink], st: Long, en: Long, factor: TrackAddressingFactors, userDefinedCalibrationPoint: Map[Long, UserDefinedCalibrationPoint]): Seq[ProjectLink] = {
    val coEff = (en - st - factor.unChangedLength - factor.transferLength) / factor.newLength
    ProjectSectionMValueCalculator.assignLinkValues(seq, userDefinedCalibrationPoint, Some(st.toDouble), Some(en.toDouble), coEff)
  }

  protected def adjustTwoTracks(right: Seq[ProjectLink], left: Seq[ProjectLink], startM: Long, endM: Long, userDefinedCalibrationPoint: Map[Long, UserDefinedCalibrationPoint]) = {
    (assignValues(left, startM, endM, ProjectSectionMValueCalculator.calculateAddressingFactors(left), userDefinedCalibrationPoint),
      assignValues(right, startM, endM, ProjectSectionMValueCalculator.calculateAddressingFactors(right), userDefinedCalibrationPoint))
  }

  /**
    * Return the calculated values of the start and end addresses of both left and right links depending of the link status:
    *
    * L: Transfer, R: Transfer OR L: Unchanged, R: Unchanged =>  return the Average between two address measures.
    * L: Unchanged, R: WTV OR L: Transfer, R: WTV => Start and end of the left links
    * L: WTV, R: Unchanged OR L: WTV, R: Transfer => Start and end of the right links
    * None of the above => if it exists return the address measure of the user defined calibration point, if not then return the Average between two address measures.
    *
    * @param leftLink
    * @param rightLink
    * @param userCalibrationPoint
    * @return
    */
  protected def getFixedAddress(leftLink: ProjectLink, rightLink: ProjectLink,
                                userCalibrationPoint: Option[UserDefinedCalibrationPoint] = None): (Long, Long) = {

    val reversed = rightLink.reversed || leftLink.reversed

    (leftLink.status, rightLink.status) match {
      case (LinkStatus.Transfer, LinkStatus.Transfer) | (LinkStatus.UnChanged, LinkStatus.UnChanged) =>
        (averageOfAddressMValues(rightLink.startAddrMValue, leftLink.startAddrMValue, reversed), averageOfAddressMValues(rightLink.endAddrMValue, leftLink.endAddrMValue, reversed))
      case (LinkStatus.UnChanged, _) | (LinkStatus.Transfer, _) =>
        (leftLink.startAddrMValue, leftLink.endAddrMValue)
      case (_, LinkStatus.UnChanged) | (_, LinkStatus.Transfer) =>
        (rightLink.startAddrMValue, rightLink.endAddrMValue)
      case _ =>
        userCalibrationPoint.map(c => (c.addressMValue, c.addressMValue)).getOrElse(
          (averageOfAddressMValues(rightLink.startAddrMValue, leftLink.startAddrMValue, reversed), averageOfAddressMValues(rightLink.endAddrMValue, leftLink.endAddrMValue, reversed))
        )
    }
  }

  protected def setLastEndAddrMValue(projectLinks: Seq[ProjectLink], endAddressMValue: Long): Seq[ProjectLink] = {
    if (projectLinks.last.status != LinkStatus.NotHandled)
      projectLinks.init :+ projectLinks.last.copy(endAddrMValue = endAddressMValue)
    else
      projectLinks
  }

  protected def adjustTwoTracks(startAddress: Option[Long], leftProjectLinks: Seq[ProjectLink], rightProjectLinks: Seq[ProjectLink], calibrationPoints: Map[Long, UserDefinedCalibrationPoint],
                                restLeftProjectLinks: Seq[ProjectLink] = Seq(), restRightProjectLinks: Seq[ProjectLink] = Seq()): TrackCalculatorResult = {

    // Find a calibration point annexed to the projectLink Id
    val availableCalibrationPoint = calibrationPoints.get(rightProjectLinks.last.id).orElse(calibrationPoints.get(leftProjectLinks.last.id))

    val startSectionAddress = startAddress.getOrElse(getFixedAddress(leftProjectLinks.head, rightProjectLinks.head)._1)
    val estimatedEnd = getFixedAddress(leftProjectLinks.last, rightProjectLinks.last, availableCalibrationPoint)._2

    val (adjustedLeft, adjustedRight) = adjustTwoTracks(rightProjectLinks, leftProjectLinks, startSectionAddress, estimatedEnd, calibrationPoints)

    //The getFixedAddress method have to be called twice because when we do it the first time we are getting the estimated end measure, that will be used for the calculation of
    // NEW sections. For example if in one of the sides we have a TRANSFER section it will use the value after recalculate all the existing sections with the original length.
    val endSectionAddress = getFixedAddress(adjustedLeft.last, adjustedRight.last, availableCalibrationPoint)._2

    TrackCalculatorResult(setLastEndAddrMValue(adjustedLeft, endSectionAddress), setLastEndAddrMValue(adjustedRight, endSectionAddress), startSectionAddress, endSectionAddress, restLeftProjectLinks, restRightProjectLinks)
  }


  protected def setOnSideCalibrationPoints(projectLinks: Seq[ProjectLink], raCalibrationPoints: Map[Long, CalibrationCode], userCalibrationPoint: Map[Long, UserDefinedCalibrationPoint]): Seq[ProjectLink] = {
    if (projectLinks.head.status == NotHandled)
      projectLinks
    else
      projectLinks.size match {
        case 0 => projectLinks
        case 1 =>
          projectLinks.map(pl => setCalibrationPoint(pl, userCalibrationPoint.get(pl.id), true, true, ProjectLinkSource))
        case _ =>
          val pls = projectLinks.map {
            pl =>
              val raCalibrationCode = raCalibrationPoints.getOrElse(pl.linearLocationId, CalibrationCode.No)
              val raStartCP = raCalibrationCode == CalibrationCode.AtBeginning || raCalibrationCode == CalibrationCode.AtBoth
              val raEndCP = raCalibrationCode == CalibrationCode.AtEnd || raCalibrationCode == CalibrationCode.AtBoth
              setCalibrationPoint(pl, userCalibrationPoint.get(pl.id), raStartCP, raEndCP, RoadAddressSource)
          }

          val calPointSource1 = if (pls.tail.head.calibrationPoints._1.isDefined) pls.tail.head.calibrationPoints._1.get.source else ProjectLinkSource
          val calPointSource2 = if (pls.init.last.calibrationPoints._2.isDefined) pls.init.last.calibrationPoints._2.get.source else ProjectLinkSource
          Seq(setCalibrationPoint(pls.head, userCalibrationPoint.get(pls.head.id), true, pls.tail.head.calibrationPoints._1.isDefined, calPointSource1)) ++ pls.init.tail ++
            Seq(setCalibrationPoint(pls.last, userCalibrationPoint.get(pls.last.id), pls.init.last.calibrationPoints._2.isDefined, true, calPointSource2))
      }
  }

  protected def setCalibrationPoint(pl: ProjectLink, userCalibrationPoint: Option[UserDefinedCalibrationPoint],
                                    startCP: Boolean, endCP: Boolean, source: CalibrationPointSource = UnknownSource): ProjectLink = {
    val sCP = if (startCP) CalibrationPointsUtils.makeStartCP(pl) else None
    val eCP = if (endCP) CalibrationPointsUtils.makeEndCP(pl, userCalibrationPoint) else None
    pl.copy(calibrationPoints = CalibrationPointsUtils.toProjectLinkCalibrationPointsWithSourceInfo((sCP, eCP), source))
  }

  /**
    * Returns project links for the other track before and after the point where there is discontinuity on the track.
    *
    * @param trackA
    * @param linkOnTrackB
    * @return (Project links before the discontinuity point, project links after the discontinuity point)
    */
  protected def getUntilNearestAddress(trackA: Seq[ProjectLink], linkOnTrackB: ProjectLink): (Seq[ProjectLink], Seq[ProjectLink]) = {
    if (linkOnTrackB.discontinuity == MinorDiscontinuity || linkOnTrackB.discontinuity == Discontinuous) {

      // Sort links in order by the distance from the end of the link on track b.
      val linkOnTrackBEndPoint = linkOnTrackB.lastPoint
      val nearestLinks: Seq[(ProjectLink, Double)] = trackA.map(pl => (pl,
        pl.lastPoint.distance2DTo(linkOnTrackBEndPoint)
      )).sortWith(_._2 < _._2) // Links with nearest end points first

      // Choose the nearest link that has similar enough angle to the linkOnTrackB and set it as lastLink
      val linkOnTrackBDirection = linkOnTrackB.lastSegmentDirection
      val maxAngleBetweenLinks = math.toRadians(75) // Tolerance for "triangle" cases is 15 degrees and for "rectangle" cases 25 degrees.
      val lastLink: ProjectLink = nearestLinks.collectFirst { case l if l._1.lastSegmentDirection.angle(linkOnTrackBDirection) < maxAngleBetweenLinks => l._1 }
        .getOrElse(nearestLinks.headOption.getOrElse(throw new RoadAddressException("Could not find any nearest road address"))._1)

      // Return links before the discontinuity point and links after it
      val continuousLinks = trackA.takeWhile(pl => pl.endAddrMValue <= lastLink.endAddrMValue)
      if (continuousLinks.isEmpty)
        throw new RoadAddressException("Could not find any nearest road address")

      (continuousLinks, trackA.drop(continuousLinks.size))
    } else {
      val continuousProjectLinks = trackA.takeWhile(pl => pl.status == linkOnTrackB.status)
      (continuousProjectLinks, trackA.drop(continuousProjectLinks.size))
    }
  }

  /**
    * Re-adds the calibration points to the project links after the calculation. The calibration points are gotten via the information on our current linear locations.
    * 
    * @param calculatorResult: TrackCalculatorResult - the result of the calculation
    * @param userDefinedCalibrationPoint: Map[Long, UserDefinedCalibrationPoint] - Map of linear location id -> UserDefinedCalibrationPoint
    * @return
    */
  def setCalibrationPoints(calculatorResult: TrackCalculatorResult, userDefinedCalibrationPoint: Map[Long, UserDefinedCalibrationPoint]): (Seq[ProjectLink], Seq[ProjectLink]) = {
    val projectLinks = calculatorResult.leftProjectLinks ++ calculatorResult.rightProjectLinks

    val roadAddressCalibrationPoints = new LinearLocationDAO().getLinearLocationCalibrationCode(projectLinks.map(_.linearLocationId).filter(_ > 0).distinct)

    (setOnSideCalibrationPoints(calculatorResult.leftProjectLinks, roadAddressCalibrationPoints, userDefinedCalibrationPoint),
      setOnSideCalibrationPoints(calculatorResult.rightProjectLinks, roadAddressCalibrationPoints, userDefinedCalibrationPoint))
  }

  def getStrategyAddress(projectLink: ProjectLink): Long = projectLink.endAddrMValue

  def applicableStrategy(headProjectLink: ProjectLink, projectLink: ProjectLink): Boolean = false

  def assignTrackMValues(startAddress: Option[Long], leftProjectLinks: Seq[ProjectLink], rightProjectLinks: Seq[ProjectLink], userDefinedCalibrationPoint: Map[Long, UserDefinedCalibrationPoint]): TrackCalculatorResult

}

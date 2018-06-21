package fi.liikennevirasto.viite.process.strategy

import fi.liikennevirasto.digiroad2.GeometryUtils
import fi.liikennevirasto.digiroad2.util.RoadAddressException
import fi.liikennevirasto.viite.NewRoadAddress
import fi.liikennevirasto.viite.dao.CalibrationPointDAO.UserDefinedCalibrationPoint
import fi.liikennevirasto.viite.dao.{CalibrationCode, LinkStatus, ProjectLink, RoadAddressDAO}
import fi.liikennevirasto.viite.process.{ProjectSectionMValueCalculator, TrackAddressingFactors}
import fi.liikennevirasto.viite.util.CalibrationPointsUtils


object TrackCalculatorContext {

  private lazy val discontinuityTrackCalculatorStrategy: DiscontinuityTrackCalculatorStrategy = {
    new DiscontinuityTrackCalculatorStrategy
  }

  private lazy val linkStatusChangeTrackCalculatorStrategy: LinkStatusChangeTrackCalculatorStrategy = {
    new LinkStatusChangeTrackCalculatorStrategy
  }

  private lazy val defaultTrackCalculatorStrategy: DefaultTrackCalculatorStrategy = {
    new DefaultTrackCalculatorStrategy
  }

  private val strategies = Seq(discontinuityTrackCalculatorStrategy, linkStatusChangeTrackCalculatorStrategy)

  def getNextStrategy(projectLinks: Seq[ProjectLink]) : Option[(Long, TrackCalculatorStrategy)] = {
    val head = projectLinks.head
    projectLinks.flatMap{
      pl =>
        strategies.find(strategy => strategy.applicableStrategy(head, pl)) match {
          case Some(strategy) => Some(pl.endAddrMValue, strategy)
          case _ => None
        }
    }.headOption
  }

  def getStrategy(leftProjectLinks: Seq[ProjectLink], rightProjectLinks: Seq[ProjectLink]): TrackCalculatorStrategy = {

    val leftStrategy = getNextStrategy(leftProjectLinks)
    val rightStrategy = getNextStrategy(rightProjectLinks)

    //Here we should choice the nearest strategy from the lower address, because for the same track
    //we can have for example link status changes and minor discontinuity in that case we should
    //apply the strategy that comes first on the address

    (leftStrategy, rightStrategy) match {
      case (Some(ls), Some(rs)) => if(rs._1 <= ls._1) rs._2 else ls._2
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
    * @param pl Project link
    * @param address Address measure
    * @return Returns two project links, the existing one with changed measures and a new one
    */
  protected def splitAt(pl: ProjectLink, address: Long) : (ProjectLink, ProjectLink) = {
    val coefficient = (pl.endMValue - pl.startMValue) / (pl.endAddrMValue - pl.startAddrMValue)
    val splitMeasure = pl.startMValue + ((pl.startAddrMValue - address) * coefficient)
    (
      pl.copy(geometry = GeometryUtils.truncateGeometry2D(pl.geometry, startMeasure = 0, endMeasure = splitMeasure), geometryLength = splitMeasure, connectedLinkId = Some(pl.linkId)),
      pl.copy(id = NewRoadAddress, geometry = GeometryUtils.truncateGeometry2D(pl.geometry, startMeasure = splitMeasure, endMeasure = pl.geometryLength), geometryLength = pl.geometryLength - splitMeasure, connectedLinkId = Some(pl.linkId))
    )
  }

  /**
    * Average between two address measures.
    * If the right side is greater then left side returns the largest (closest to positive infinity) value
    * If the left side is less then the right side returns the smallest (closest to negative infinity) value
    * NOTE: If we have the reversed set to true the previous conditions are inverted
    * @param rAddrM Left address measure
    * @param lAddrM Right address measure
    * @param reversed True if the road was reverted
    * @return Returns te average between two measures
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
    * @param seq Project links to be recalculated
    * @param st Start address measure
    * @param en End address measure
    * @param factor All the factors, that will be used to get the coefficient for project link measures with status NEW
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

  protected def getFixedAddress(leftLink: ProjectLink, rightLink: ProjectLink,
                                userCalibrationPoint: Option[UserDefinedCalibrationPoint] = None): (Long, Long) = {

    val reversed = rightLink.reversed || leftLink.reversed

    (leftLink.status, rightLink.status) match {
      case (LinkStatus.Transfer, LinkStatus.Transfer) | (LinkStatus.UnChanged, LinkStatus.UnChanged) =>
        (averageOfAddressMValues(rightLink.startAddrMValue, leftLink.startAddrMValue, reversed), averageOfAddressMValues(rightLink.endAddrMValue, leftLink.endAddrMValue, reversed))
      case (LinkStatus.UnChanged, _) | (LinkStatus.Transfer, _) =>
        (leftLink.startAddrMValue, leftLink.endAddrMValue)
      case (_ , LinkStatus.UnChanged) | (_, LinkStatus.Transfer) =>
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

    val availableCalibrationPoint = calibrationPoints.get(rightProjectLinks.last.id).orElse(calibrationPoints.get(leftProjectLinks.last.id))

    val startSectionAddress = startAddress.getOrElse(getFixedAddress(leftProjectLinks.head, rightProjectLinks.head)._1)
    val estimatedEnd = getFixedAddress(leftProjectLinks.last, rightProjectLinks.last, availableCalibrationPoint)._2

    val (adjustedLeft, adjustedRight) = adjustTwoTracks(rightProjectLinks, leftProjectLinks, startSectionAddress, estimatedEnd, calibrationPoints)

    //The getFixedAddress method have to be call twice because when we do it the first time we are getting the estimated end measure, that will be used for the calculation of
    // NEW sections. For example if in one of the sides we have a TRANSFER section it will use the value after recalculate all the existing sections with the original length.
    val endSectionAddress = getFixedAddress(adjustedLeft.last, adjustedRight.last, availableCalibrationPoint)._2

    TrackCalculatorResult(setLastEndAddrMValue(adjustedLeft, endSectionAddress), setLastEndAddrMValue(adjustedRight, endSectionAddress), startSectionAddress, endSectionAddress, restLeftProjectLinks, restRightProjectLinks)
  }


  protected def setOnSideCalibrationPoints(projectlinks: Seq[ProjectLink], raCalibrationPoints: Map[Long, CalibrationCode], userCalibrationPoint: Map[Long, UserDefinedCalibrationPoint]): Seq[ProjectLink] = {
    projectlinks.size match {
      case 1 =>
        projectlinks.map(pl => setCalibrationPoint(pl, userCalibrationPoint.get(pl.id), true, true))
      case _ =>
        val pls = projectlinks.map{
          pl =>
            val raCalibrationCode = raCalibrationPoints.get(pl.roadAddressId).getOrElse(CalibrationCode.No)
            val raStartCP = raCalibrationCode == CalibrationCode.AtBeginning || raCalibrationCode == CalibrationCode.AtBoth
            val raEndCP = raCalibrationCode == CalibrationCode.AtEnd || raCalibrationCode == CalibrationCode.AtBoth
            setCalibrationPoint(pl, userCalibrationPoint.get(pl.id), raStartCP, raEndCP)
        }

        Seq(setCalibrationPoint(pls.head, userCalibrationPoint.get(pls.head.id), true, false)) ++ pls.init.tail ++
          Seq(setCalibrationPoint(pls.last, userCalibrationPoint.get(pls.last.id), false, true))
    }
  }

  protected def setCalibrationPoint(pl: ProjectLink, userCalibrationPoint: Option[UserDefinedCalibrationPoint], startCP: Boolean, endCP: Boolean) = {
    val sCP = if (startCP) CalibrationPointsUtils.makeStartCP(pl) else None
    val eCP = if (endCP) CalibrationPointsUtils.makeEndCP(pl, userCalibrationPoint) else None
    pl.copy(calibrationPoints = (sCP, eCP))
  }

  protected def getUntilNearestAddress(seq: Seq[ProjectLink], address: Long): (Seq[ProjectLink], Seq[ProjectLink]) = {
    val continuousProjectLinks = seq.takeWhile(pl => pl.startAddrMValue < address)

    if(continuousProjectLinks.isEmpty)
      throw new RoadAddressException("Could not find any nearest road address")

    val lastProjectLink = continuousProjectLinks.last
    if(continuousProjectLinks.size > 1 && lastProjectLink.toMeters(Math.abs(address - lastProjectLink.startAddrMValue)) < lastProjectLink.toMeters(Math.abs(address - lastProjectLink.endAddrMValue))) {
      (continuousProjectLinks.init, lastProjectLink +: seq.drop(continuousProjectLinks.size))
    } else {
      (continuousProjectLinks, seq.drop(continuousProjectLinks.size))
    }
  }

  def setCalibrationPoints(calculatorResult: TrackCalculatorResult, userDefinedCalibrationPoint: Map[Long, UserDefinedCalibrationPoint]) : (Seq[ProjectLink], Seq[ProjectLink]) = {
    //TODO this can be improved if we use the combine track
    val projectLinks = calculatorResult.leftProjectLinks ++ calculatorResult.rightProjectLinks

    val roadAddressCalibrationPoints = RoadAddressDAO.getRoadAddressCalibrationCode(projectLinks.map(_.roadAddressId).filter(_>0).distinct)

    (
      setOnSideCalibrationPoints(calculatorResult.leftProjectLinks, roadAddressCalibrationPoints, userDefinedCalibrationPoint),
      setOnSideCalibrationPoints(calculatorResult.rightProjectLinks, roadAddressCalibrationPoints, userDefinedCalibrationPoint)
    )
  }

  def applicableStrategy(headProjectLink: ProjectLink, projectLink: ProjectLink): Boolean = false
  def assignTrackMValues(startAddress: Option[Long] , leftProjectLinks: Seq[ProjectLink], rightProjectLinks: Seq[ProjectLink], userDefinedCalibrationPoint: Map[Long, UserDefinedCalibrationPoint]): TrackCalculatorResult

}
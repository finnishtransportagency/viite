package fi.liikennevirasto.viite.process.strategy

import fi.liikennevirasto.digiroad2.util.{MissingTrackException, RoadAddressException}
import fi.liikennevirasto.viite.{NewIdValue, UnsuccessfulRecalculationMessage}
import fi.liikennevirasto.viite.dao._
import fi.liikennevirasto.viite.dao.ProjectCalibrationPointDAO.UserDefinedCalibrationPoint
import fi.liikennevirasto.viite.process.{ProjectSectionMValueCalculator, TrackAddressingFactors}
import fi.vaylavirasto.viite.geometry.GeometryUtils
import fi.vaylavirasto.viite.model.{AddrMRange, CalibrationPointType, Discontinuity, RoadAddressChangeType}
import org.slf4j.LoggerFactory


object TrackCalculatorContext {

  private lazy val minorDiscontinuityStrategy: DefaultTrackCalculatorStrategy = {
    new DefaultTrackCalculatorStrategy
  }

  private lazy val discontinuousStrategy: DefaultTrackCalculatorStrategy = {
    new DefaultTrackCalculatorStrategy
  }

  private lazy val terminationOperationChangeStrategy: TerminationOperationChangeStrategy = {
    new TerminationOperationChangeStrategy
  }

  private lazy val defaultTrackCalculatorStrategy: DefaultTrackCalculatorStrategy = {
    new DefaultTrackCalculatorStrategy
  }

  private val strategies = Seq(minorDiscontinuityStrategy, discontinuousStrategy, terminationOperationChangeStrategy)

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

case class TrackCalculatorResult(leftProjectLinks: Seq[ProjectLink], rightProjectLinks: Seq[ProjectLink], startAddrMValue: Long, endAddrMValue: Long, restLeft: Seq[ProjectLink] = Seq(), restRight: Seq[ProjectLink] = Seq())

/**
  * Strategy used on the DefaultSectionCalculatorStrategy
  */
trait TrackCalculatorStrategy {

  val name: String

  /**
    * Split the project link at the specified address
    *
    * @param pl      Project link
    * @param address Address measure
    * @return Returns two project links, the existing one with changed measures and a new one
    */
  protected def splitAt(pl: ProjectLink, address: Long): (ProjectLink, ProjectLink) = {
    val coefficient = (pl.endMValue - pl.startMValue) / (pl.addrMRange.end - pl.addrMRange.start)
    val splitMeasure = pl.startMValue + ((pl.addrMRange.start - address) * coefficient)
    (
      pl.copy(geometry = GeometryUtils.truncateGeometry2D(pl.geometry, startMeasure = 0, endMeasure = splitMeasure), geometryLength = splitMeasure, connectedLinkId = Some(pl.linkId)),
      pl.copy(id = NewIdValue, geometry = GeometryUtils.truncateGeometry2D(pl.geometry, startMeasure = splitMeasure, endMeasure = pl.geometryLength), geometryLength = pl.geometryLength - splitMeasure, connectedLinkId = Some(pl.linkId))
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
    ProjectSectionMValueCalculator.assignLinkValues(seq, userDefinedCalibrationPoint, Some(st.toDouble), if (coEff.isNaN || coEff.isInfinity) 1.0 else coEff)
  }

  protected def adjustTwoTracks(right: Seq[ProjectLink], left: Seq[ProjectLink], startM: Long, endM: Long,
                                userDefinedCalibrationPoint: Map[Long, UserDefinedCalibrationPoint]): (Seq[ProjectLink],Seq[ProjectLink]) =
  {
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
    (leftLink.calibrationPointTypes._2, rightLink.calibrationPointTypes._2) match {
      case (CalibrationPointType.UserDefinedCP, _ ) | (_, CalibrationPointType.UserDefinedCP) =>
        userCalibrationPoint.map(c => (c.addressMValue, c.addressMValue)).getOrElse(
          (averageOfAddressMValues(rightLink.addrMRange.start, leftLink.addrMRange.start, reversed), averageOfAddressMValues(rightLink.addrMRange.end, leftLink.addrMRange.end, reversed))
        )
      case _ =>
          (averageOfAddressMValues(rightLink.addrMRange.start, leftLink.addrMRange.start, reversed), averageOfAddressMValues(rightLink.addrMRange.end, leftLink.addrMRange.end, reversed))
    }
  }

  protected def setLastEndAddrMValue(projectLinks: Seq[ProjectLink], endAddressMValue: Long): Seq[ProjectLink] = {
    if (projectLinks.last.status != RoadAddressChangeType.NotHandled) {
      if (projectLinks.last.addrMRange.start > endAddressMValue) {
        val logger = LoggerFactory.getLogger(getClass)
        logger.error(s"Averaged address caused negative length. " +
                     s"projectlink.id: ${projectLinks.last.id} " +
                     s"addrMRange.start: ${projectLinks.last.addrMRange.start} " +
                     s"endAddressMValue: $endAddressMValue")
        throw new RoadAddressException(UnsuccessfulRecalculationMessage + s"\nLinkin ${projectLinks.last.linkId} pituudeksi tulee ${endAddressMValue - projectLinks.last.addrMRange.start}")
      }
      projectLinks.init :+ projectLinks.last.copy(addrMRange = AddrMRange(projectLinks.last.addrMRange.start, endAddressMValue))
    }
    else
      projectLinks
  }

  protected def adjustTwoTracks(startAddress: Option[Long], leftProjectLinks: Seq[ProjectLink], rightProjectLinks: Seq[ProjectLink], calibrationPoints: Map[Long, UserDefinedCalibrationPoint],
                                restLeftProjectLinks: Seq[ProjectLink] = Seq(), restRightProjectLinks: Seq[ProjectLink] = Seq()): TrackCalculatorResult = {
    if (leftProjectLinks.isEmpty || rightProjectLinks.isEmpty)
      throw new MissingTrackException(s"Missing track, R: ${rightProjectLinks.size}, L: ${leftProjectLinks.size}")

    //  Find a calibration point annexed to the projectLink Id
    val availableCalibrationPoint = calibrationPoints.get(rightProjectLinks.last.id).orElse(calibrationPoints.get(leftProjectLinks.last.id))

    val startSectionAddress = startAddress.getOrElse(getFixedAddress(leftProjectLinks.head, rightProjectLinks.head)._1)

    // With minimum end address we want to maintain existing links' address lengths. Otherwise, average value could cause existing link lengths change.
    def fixedAddress = getFixedAddress(leftProjectLinks.last, rightProjectLinks.last, availableCalibrationPoint)._2
    def fixedMinimimumAddress = Math.max(Math.max(rightProjectLinks.last.addrMRange.start + 1, leftProjectLinks.last.addrMRange.start + 1), fixedAddress)
    def addressLengthRight = Math.max(0, rightProjectLinks.last.originalAddrMRange.end - rightProjectLinks.last.originalAddrMRange.start)
    def addressLengthLeft  = Math.max(0, leftProjectLinks.last.originalAddrMRange.end  -  leftProjectLinks.last.originalAddrMRange.start)

    val minimumEndAddress = (leftProjectLinks.exists(_.status == RoadAddressChangeType.New), rightProjectLinks.exists(_.status == RoadAddressChangeType.New)) match {
      case (true,true) => fixedMinimimumAddress
      case (true, false)  => rightProjectLinks.last.addrMRange.start + addressLengthRight
      case (false, true)  => leftProjectLinks.last.addrMRange.start + addressLengthLeft
      case (false,false)  =>
        val leftLength = startSectionAddress + leftProjectLinks.last.addrMRange.end - leftProjectLinks.head.addrMRange.start
        val rightLength = startSectionAddress + rightProjectLinks.last.addrMRange.end - rightProjectLinks.head.addrMRange.start
        averageOfAddressMValues(leftLength, rightLength, rightProjectLinks.head.reversed)
    }

    val (adjustedLeft, adjustedRight) = adjustTwoTracks(rightProjectLinks, leftProjectLinks, startSectionAddress, minimumEndAddress, calibrationPoints)

    TrackCalculatorResult(
      setLastEndAddrMValue(adjustedLeft, minimumEndAddress),
      setLastEndAddrMValue(adjustedRight, minimumEndAddress),
      startSectionAddress, minimumEndAddress,
      restLeftProjectLinks,
      restRightProjectLinks)
  }

  /**
    * Returns project links for the other track before and after the point where there is discontinuity on the track.
    *
    * @return (Project links before the discontinuity point, project links after the discontinuity point)
    */
  protected def getUntilNearestAddress(seq: Seq[ProjectLink], endProjectLink: ProjectLink): (Seq[ProjectLink], Seq[ProjectLink]) = {
    if (List(Discontinuity.Discontinuous, Discontinuity.MinorDiscontinuity, Discontinuity.Continuous).contains(endProjectLink.discontinuity)) {
      val continuousProjectLinks = seq.takeWhile(pl => pl.addrMRange.start <= endProjectLink.addrMRange.end)
      if (continuousProjectLinks.isEmpty)
        throw new MissingTrackException("Could not find any nearest road address")

      val lastProjectLink = continuousProjectLinks.last
      if (continuousProjectLinks.size > 1 && lastProjectLink.toMeters(Math.abs(endProjectLink.addrMRange.end - lastProjectLink.addrMRange.start)) < lastProjectLink.toMeters(Math.abs(endProjectLink.addrMRange.end - lastProjectLink.addrMRange.end))) {
        (continuousProjectLinks.init, lastProjectLink +: seq.drop(continuousProjectLinks.size))
      } else {
        (continuousProjectLinks, seq.drop(continuousProjectLinks.size))
      }
    } else {
      val continuousProjectLinks = seq.takeWhile(pl => pl.status == endProjectLink.status)
      (continuousProjectLinks, seq.drop(continuousProjectLinks.size))
    }
  }


  def getStrategyAddress(projectLink: ProjectLink): Long = projectLink.addrMRange.end

  def applicableStrategy(headProjectLink: ProjectLink, projectLink: ProjectLink): Boolean = false

  def assignTrackMValues(startAddress: Option[Long], leftProjectLinks: Seq[ProjectLink], rightProjectLinks: Seq[ProjectLink], userDefinedCalibrationPoint: Map[Long, UserDefinedCalibrationPoint]): TrackCalculatorResult

}

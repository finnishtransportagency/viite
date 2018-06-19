package fi.liikennevirasto.viite.process.strategy

import fi.liikennevirasto.digiroad2.util.RoadAddressException
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

trait TrackCalculatorStrategy {

  protected def averageOfAddressMValues(rAddrM: Double, lAddrM: Double, reversed: Boolean): Long = {
    val average = 0.5 * (rAddrM + lAddrM)

    if (reversed) {
      if (rAddrM > lAddrM) Math.floor(average).round else Math.ceil(average).round
    } else {
      if (rAddrM > lAddrM) Math.ceil(average).round else Math.floor(average).round
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

  protected def getFixedAddress(leftLink: ProjectLink, rightLink: ProjectLink,
                                userCalibrationPoint: Option[UserDefinedCalibrationPoint] = None, withLeftCalibration: Boolean = false, withRightCalibration: Boolean = false): (Long, Long) = {

    val reversed = rightLink.reversed || leftLink.reversed

    (leftLink.status, rightLink.status) match {
      case (LinkStatus.Transfer, LinkStatus.Transfer) | (LinkStatus.UnChanged, LinkStatus.UnChanged) =>
        if(withLeftCalibration && !withRightCalibration)
          (leftLink.startAddrMValue, leftLink.endAddrMValue)
        else if(withRightCalibration && !withLeftCalibration)
          (rightLink.startAddrMValue, rightLink.endAddrMValue)
        else
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

  protected def adjustTwoTracks(startAddress: Option[Long], leftProjectLinks: Seq[ProjectLink], rightProjectLinks: Seq[ProjectLink], restLeftProjectLinks: Seq[ProjectLink],
                              restRightProjectLinks: Seq[ProjectLink], calibrationPoints: Map[Long, UserDefinedCalibrationPoint]): TrackCalculatorResult = {

    //TODO change the way the user calibration points are manage
    val availableCalibrationPoint = calibrationPoints.get(rightProjectLinks.last.id).orElse(calibrationPoints.get(leftProjectLinks.last.id))
    val withLeftCalibrationPoints = calibrationPoints.exists(_._2.addressMValue == leftProjectLinks.head.startAddrMValue)
    val withRightCalibrationPoints = calibrationPoints.exists(_._2.addressMValue == rightProjectLinks.head.startAddrMValue)

    val startSectionAddress = startAddress.getOrElse(getFixedAddress(leftProjectLinks.head, rightProjectLinks.head)._1)
    val endSectionAddress = getFixedAddress(leftProjectLinks.last, rightProjectLinks.last, availableCalibrationPoint, withLeftCalibrationPoints, withRightCalibrationPoints)._2

    val (adjustedLeft, adjustedRight) = adjustTwoTracks(rightProjectLinks, leftProjectLinks, startSectionAddress, endSectionAddress, calibrationPoints)

    TrackCalculatorResult(adjustedLeft, adjustedRight, startSectionAddress, endSectionAddress, restLeftProjectLinks, restRightProjectLinks)
  }

  protected def getUntilNearestAddress(seq: Seq[ProjectLink], address: Long): (Seq[ProjectLink], Seq[ProjectLink]) = {
    val continuousProjectLinks = seq.takeWhile(pl => pl.startAddrMValue < address)
    (continuousProjectLinks, seq.drop(continuousProjectLinks.size))
  }

  protected def getUntilNearestAddress(seq: Seq[ProjectLink], address: Long, tolerance: Long): (Seq[ProjectLink], Seq[ProjectLink]) = {
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

  def applicableStrategy(headProjectLink: ProjectLink, projectLink: ProjectLink): Boolean = false
  def assignTrackMValues(startAddress: Option[Long] , leftProjectLinks: Seq[ProjectLink], rightProjectLinks: Seq[ProjectLink], userDefinedCalibrationPoint: Map[Long, UserDefinedCalibrationPoint]): TrackCalculatorResult

  def setCalibrationPoints(calculatorResult: TrackCalculatorResult, userDefinedCalibrationPoint: Map[Long, UserDefinedCalibrationPoint]) : (Seq[ProjectLink], Seq[ProjectLink]) = {
    //TODO this can be improved if we use the combine track
    val projectLinks = calculatorResult.leftProjectLinks ++ calculatorResult.rightProjectLinks

    val roadAddressCalibrationPoints = RoadAddressDAO.getRoadAddressCalibrationCode(projectLinks.map(_.roadAddressId).filter(_>0).distinct)

    (
      setOnSideCalibrationPoints(calculatorResult.leftProjectLinks, roadAddressCalibrationPoints, userDefinedCalibrationPoint),
      setOnSideCalibrationPoints(calculatorResult.rightProjectLinks, roadAddressCalibrationPoints, userDefinedCalibrationPoint)
    )
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
}
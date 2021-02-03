package fi.liikennevirasto.viite.process

import fi.liikennevirasto.GeometryUtils
import fi.liikennevirasto.digiroad2.Point
import fi.liikennevirasto.digiroad2.asset.SideCode
import fi.liikennevirasto.digiroad2.asset.SideCode.AgainstDigitizing
import fi.liikennevirasto.digiroad2.util.{RoadAddressException, Track}
import fi.liikennevirasto.viite.RoadType
import fi.liikennevirasto.viite.dao.ProjectCalibrationPointDAO.UserDefinedCalibrationPoint
import fi.liikennevirasto.viite.dao._
import fi.liikennevirasto.viite.process.strategy.{RoadAddressSectionCalculatorContext, TrackCalculatorContext}
import org.slf4j.LoggerFactory

object ProjectSectionCalculator {

  private val logger = LoggerFactory.getLogger(getClass)

  /**
    * NOTE! Should be called from project service only at recalculate method - other places are usually wrong places
    * and may miss user given calibration points etc.
    * Recalculates the AddressMValues for project links. LinkStatus.New will get reassigned values and all
    * others will have the transfer/unchanged rules applied for them.
    * Terminated links will not be recalculated
    *
    * @param projectLinks List of addressed links in project
    * @return Sequence of project links with address values and calibration points.
    */
  def assignMValues(projectLinks: Seq[ProjectLink], userGivenCalibrationPoints: Seq[UserDefinedCalibrationPoint] = Seq()): Seq[ProjectLink] = {
    logger.info(s"Starting MValue assignment for ${projectLinks.size} links")
    val others = projectLinks.filterNot(_.status == LinkStatus.Terminated)
    val (newLinks, nonTerminatedLinks) = others.partition(l => l.status == LinkStatus.New)
    try {

      val calculator = RoadAddressSectionCalculatorContext.getStrategy(others)
      logger.info(s"${calculator.name} strategy")
      calculator.assignMValues(newLinks, nonTerminatedLinks, userGivenCalibrationPoints)

    } finally {
      logger.info(s"Finished MValue assignment for ${projectLinks.size} links")
    }
  }

  def assignTerminatedMValues(terminated: Seq[ProjectLink], nonTerminatedLinks: Seq[ProjectLink]) : Seq[ProjectLink] = {
    logger.info(s"Starting MValue assignment for ${terminated.size} links")
    try {

      val allProjectLinks = nonTerminatedLinks.filter(_.status != LinkStatus.New) ++ terminated
      val group = allProjectLinks.groupBy {
        pl => (pl.roadAddressRoadNumber.getOrElse(pl.roadNumber), pl.roadAddressRoadPart.getOrElse(pl.roadPartNumber))
      }

      group.flatMap { case (part, projectLinks) =>
        try {
          calculateSectionAddressValues(part, projectLinks)
        } catch {
          case ex: InvalidAddressDataException =>
            logger.info(s"Can't calculate terminated road/road part ${part._1}/${part._2}: " + ex.getMessage)
            terminated
          case ex: NoSuchElementException =>
            logger.info("Delta terminated calculation failed: " + ex.getMessage, ex)
            terminated
          case ex: NullPointerException =>
            logger.info("Delta terminated calculation failed (NPE)", ex)
            terminated
          case ex: Throwable =>
            logger.info("Delta terminated calculation not possible: " + ex.getMessage)
            terminated
        }
      }.toSeq

    } finally {
      logger.info(s"Finished MValue assignment for ${terminated.size} links")
    }
  }

  private def calculateSectionAddressValues(part: (Long, Long), projectLinks: Seq[ProjectLink]) : Seq[ProjectLink] = {

    def fromProjectLinks(s: Seq[ProjectLink]): TrackSection = {
      val pl = s.head
      TrackSection(pl.roadNumber, pl.roadPartNumber, pl.roadAddressTrack.getOrElse(Track.Unknown), s.map(_.geometryLength).sum, s)
    }

    def groupIntoSections(seq: Seq[ProjectLink]): Seq[TrackSection] = {
      if (seq.isEmpty)
        throw new InvalidAddressDataException("Missing track")
      val changePoints = seq.zip(seq.tail).filter{ case (pl1, pl2) => pl1.roadAddressTrack.getOrElse(Track.Unknown) != pl2.roadAddressTrack.getOrElse(Track.Unknown)}
      seq.foldLeft(Seq(Seq[ProjectLink]())) { case (tracks, pl) =>
        if (changePoints.exists(_._2 == pl)) {
          Seq(Seq(pl)) ++ tracks
        } else {
          Seq(tracks.head ++ Seq(pl)) ++ tracks.tail
        }
      }.reverse.map(fromProjectLinks)
    }

    def getContinuousTrack(seq: Seq[ProjectLink]): (Seq[ProjectLink], Seq[ProjectLink]) = {
      val track = seq.headOption.map(_.roadAddressTrack.getOrElse(Track.Unknown)).getOrElse(Track.Unknown)
      val status = seq.headOption.map(_.status).getOrElse(LinkStatus.Unknown)
      seq.span(pl => pl.roadAddressTrack.getOrElse(Track.Unknown) == track && (if (status.equals(LinkStatus.Terminated)) pl.status == status else !pl.status.equals(LinkStatus.Terminated)))
    }

    def adjustTracksToMatch(leftLinks: Seq[ProjectLink], rightLinks: Seq[ProjectLink], previousStart: Option[Long]): (Seq[ProjectLink], Seq[ProjectLink]) = {
      if (rightLinks.isEmpty && leftLinks.isEmpty) {
        (Seq(), Seq())
      } else {
        val (firstRight, restRight) = getContinuousTrack(rightLinks)
        val (firstLeft, restLeft) = getContinuousTrack(leftLinks)

        if (firstRight.isEmpty || firstLeft.isEmpty)
          throw new RoadAddressException(s"Mismatching tracks, R ${firstRight.size}, L ${firstLeft.size}")

        val strategy = TrackCalculatorContext.getStrategy(firstLeft, firstRight)
        val trackCalcResult = strategy.assignTrackMValues(previousStart, firstLeft, firstRight, Map())

        val (adjustedRestRight, adjustedRestLeft) = adjustTracksToMatch(trackCalcResult.restLeft ++ restLeft, trackCalcResult.restRight ++ restRight, Some(trackCalcResult.endAddrMValue))

        (trackCalcResult.leftProjectLinks ++ adjustedRestRight, trackCalcResult.rightProjectLinks ++ adjustedRestLeft)
      }
    }

    val left = projectLinks.filter(pl => pl.roadAddressTrack.getOrElse(pl.track) != Track.RightSide).sortBy(_.roadAddressStartAddrM)
    val right = projectLinks.filter(pl => pl.roadAddressTrack.getOrElse(pl.track) != Track.LeftSide).sortBy(_.roadAddressStartAddrM)
    if (left.isEmpty || right.isEmpty) {
      Seq[ProjectLink]()
    } else {
//      val leftLinks: Seq[ProjectLink] = ProjectSectionMValueCalculator.assignLinkValues(left, addrSt = 0)
//      val rightLinks: Seq[ProjectLink] = ProjectSectionMValueCalculator.assignLinkValues(right, addrSt = 0)

      val leftTerminated = left.filter(_.status == LinkStatus.Terminated)
      val rightTerminated = right.filter(_.status == LinkStatus.Terminated)

      //if (rightTerminated.exists(rpl => leftTerminated.exists(lpl => lpl.startAddrMValue == rpl.startAddrMValue))

      //leftTerminated.map(pl => pl.connected())

      /* Dummy set terminated heads */
      val leftReassignedStart: Seq[ProjectLink] = leftTerminated.map(leftTerminatedpl => {
        val startingPointLink = projectLinks.filterNot(_.status == LinkStatus.Terminated).sortBy(_.startAddrMValue).find(pl =>
          pl.id != leftTerminatedpl.id && (pl.startingPoint.connected(leftTerminatedpl.startingPoint))
        )
        if (startingPointLink.isDefined) leftTerminatedpl.copy(startAddrMValue = startingPointLink.get.startAddrMValue)
        else {
          val endingPointLink = projectLinks.filterNot(_.status == LinkStatus.Terminated).sortBy(_.startAddrMValue).find(pl =>
            pl.id != leftTerminatedpl.id && (pl.endPoint.connected(leftTerminatedpl.startingPoint))
          )
          if (endingPointLink.isDefined) leftTerminatedpl.copy(startAddrMValue = endingPointLink.get.endAddrMValue)
          else leftTerminatedpl
        }
      }
      )

      val leftReassignedEnd: Seq[ProjectLink] = leftReassignedStart.map(leftTerminatedpl => {
        val startingPointLink = projectLinks.filterNot(_.status == LinkStatus.Terminated).sortBy(_.startAddrMValue).find(pl =>
          (pl.id != leftTerminatedpl.id && ( pl.startingPoint.connected(leftTerminatedpl.endPoint))
        ))
        if (startingPointLink.isDefined) leftTerminatedpl.copy(endAddrMValue = startingPointLink.get.startAddrMValue)
        else {
          val endPointLink = projectLinks.filterNot(_.status == LinkStatus.Terminated).sortBy(_.startAddrMValue).find(pl =>
            pl.id != leftTerminatedpl.id && (pl.endPoint.connected(leftTerminatedpl.endPoint))
          )
          if (endPointLink.isDefined) leftTerminatedpl.copy(endAddrMValue = endPointLink.get.endAddrMValue)
          else leftTerminatedpl
        }
      }
      )

      val rightReassignedStart: Seq[ProjectLink] = rightTerminated.map(rightTerminatedpl => {
        val startingPointLink = projectLinks.filterNot(_.status == LinkStatus.Terminated).sortBy(_.startAddrMValue).find(pl =>
          pl.id != rightTerminatedpl.id && (pl.startingPoint.connected(rightTerminatedpl.startingPoint))
        )
        if (startingPointLink.isDefined) rightTerminatedpl.copy(startAddrMValue = startingPointLink.get.startAddrMValue)
        else {
          val endingPointLink = projectLinks.filterNot(_.status == LinkStatus.Terminated).sortBy(_.startAddrMValue).find(pl =>
            pl.id != rightTerminatedpl.id && (pl.endPoint.connected(rightTerminatedpl.startingPoint))
          )
          if (endingPointLink.isDefined) rightTerminatedpl.copy(startAddrMValue = endingPointLink.get.endAddrMValue)
          else rightTerminatedpl
        }
      }
      )

      val rightReassignedEnd: Seq[ProjectLink] = rightReassignedStart.map(rightTerminatedpl => {
        val startingPointLink = projectLinks.filterNot(_.status == LinkStatus.Terminated).sortBy(_.startAddrMValue).find(pl =>
          (pl.id != rightTerminatedpl.id && ( pl.startingPoint.connected(rightTerminatedpl.endPoint))
            ))
        if (startingPointLink.isDefined) rightTerminatedpl.copy(endAddrMValue = startingPointLink.get.startAddrMValue)
        else {
          val endPointLink = projectLinks.filterNot(_.status == LinkStatus.Terminated).sortBy(_.startAddrMValue).find(pl =>
            pl.id != rightTerminatedpl.id && (pl.endPoint.connected(rightTerminatedpl.endPoint))
          )
          if (endPointLink.isDefined) rightTerminatedpl.copy(endAddrMValue = endPointLink.get.endAddrMValue)
          else rightTerminatedpl
        }
      }
      )
// 11908903


//      val (leftAdjusted, rightAdjusted) = adjustTracksToMatch(leftLinks, rightLinks, None)
//      val (completedAdjustedLeft, completedAdjustedRight) = TrackSectionOrder.setCalibrationPoints(leftAdjusted, rightAdjusted, Map())
//      val (completedAdjustedLeft, completedAdjustedRight) = TrackSectionOrder.setCalibrationPoints(leftLinks, rightLinks, Map())

      if (rightReassignedEnd == leftReassignedEnd)
        rightReassignedEnd
      else
        rightReassignedEnd ++ leftReassignedEnd.filterNot(_.track == Track.Combined)
//      val calculatedSections = TrackSectionOrder.createCombinedSectionss(groupIntoSections(rightReassignedEnd), groupIntoSections(leftReassignedEnd))
//      val calculatedSections = TrackSectionOrder.createCombinedSectionss(groupIntoSections(completedAdjustedRight), groupIntoSections(completedAdjustedLeft))
//      calculatedSections.flatMap { sec =>
//        if (sec.right == sec.left)
//          sec.right.links
//        else
//          sec.right.links ++ sec.left.links
//      }.filter(_.status == LinkStatus.Terminated)
    }
  }
}

case class RoadwaySection(roadNumber: Long, roadPartNumberStart: Long, roadPartNumberEnd: Long, track: Track,
                          startMAddr: Long, endMAddr: Long, discontinuity: Discontinuity, roadType: RoadType, ely: Long, reversed: Boolean, roadwayNumber: Long, projectLinks: Seq[ProjectLink]) {
  def includes(ra: BaseRoadAddress): Boolean = {
    // within the road number and parts included
    ra.roadNumber == roadNumber && ra.roadPartNumber >= roadPartNumberStart && ra.roadPartNumber <= roadPartNumberEnd &&
      // and on the same track
      ra.track == track &&
      // and by reversed direction
      ra.reversed == reversed &&
      // and not starting before this section start or after this section ends
      !(ra.startAddrMValue < startMAddr && ra.roadPartNumber == roadPartNumberStart ||
        ra.startAddrMValue > endMAddr && ra.roadPartNumber == roadPartNumberEnd) &&
      // and not ending after this section ends or before this section starts
      !(ra.endAddrMValue > endMAddr && ra.roadPartNumber == roadPartNumberEnd ||
        ra.endAddrMValue < startMAddr && ra.roadPartNumber == roadPartNumberStart) &&
      // and same roadway
      ra.roadwayNumber == roadwayNumber
  }
}

case class RoadLinkLength(linkId: Long, geometryLength: Double)

case class TrackSection(roadNumber: Long, roadPartNumber: Long, track: Track,
                        geometryLength: Double, links: Seq[ProjectLink]) {
  def reverse = TrackSection(roadNumber, roadPartNumber, track, geometryLength,
    links.map(l => l.copy(sideCode = SideCode.switch(l.sideCode))).reverse)

  lazy val startGeometry: Point = links.head.sideCode match {
    case AgainstDigitizing => links.head.geometry.last
    case _ => links.head.geometry.head
  }
  lazy val endGeometry: Point = links.last.sideCode match {
    case AgainstDigitizing => links.last.geometry.head
    case _ => links.last.geometry.last
  }
  lazy val startAddrM: Long = links.map(_.startAddrMValue).min
  lazy val endAddrM: Long = links.map(_.endAddrMValue).max

  def toAddressValues(start: Long, end: Long): TrackSection = {
    val runningLength = links.scanLeft(0.0) { case (d, pl) => d + pl.geometryLength }
    val coeff = (end - start) / runningLength.last
    val updatedLinks = links.zip(runningLength.zip(runningLength.tail)).map { case (pl, (st, en)) =>
      pl.copy(startAddrMValue = Math.round(start + st * coeff), endAddrMValue = Math.round(start + en * coeff))
    }
    this.copy(links = updatedLinks)
  }
}

case class CombinedSection(startGeometry: Point, endGeometry: Point, geometryLength: Double, left: TrackSection, right: TrackSection) {
  lazy val sideCode: SideCode = {
    if (GeometryUtils.areAdjacent(startGeometry, right.links.head.geometry.head))
      right.links.head.sideCode
    else
      SideCode.apply(5 - right.links.head.sideCode.value)
  }

  lazy val addressStartGeometry: Point = sideCode match {
    case AgainstDigitizing => endGeometry
    case _ => startGeometry
  }

  lazy val addressEndGeometry: Point = sideCode match {
    case AgainstDigitizing => startGeometry
    case _ => endGeometry
  }

  lazy val linkStatus: LinkStatus = right.links.head.status

  lazy val startAddrM: Long = right.links.map(_.startAddrMValue).min

  lazy val endAddrM: Long = right.links.map(_.endAddrMValue).max

  lazy val linkStatusCodes: Set[LinkStatus] = (right.links.map(_.status) ++ left.links.map(_.status)).toSet
}


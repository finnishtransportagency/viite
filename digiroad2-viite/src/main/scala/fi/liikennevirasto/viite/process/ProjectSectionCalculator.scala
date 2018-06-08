package fi.liikennevirasto.viite.process

import fi.liikennevirasto.digiroad2.asset.{BoundingRectangle, SideCode}
import fi.liikennevirasto.digiroad2.asset.SideCode.{AgainstDigitizing, TowardsDigitizing}
import fi.liikennevirasto.digiroad2.util.Track.Combined
import fi.liikennevirasto.digiroad2.util.{RoadAddressException, Track}
import fi.liikennevirasto.digiroad2.{GeometryUtils, Point, Vector3d}
import fi.liikennevirasto.viite.ProjectValidator.{connected, endPoint}
import fi.liikennevirasto.viite.{RampsMaxBound, RampsMinBound, RoadType}
import fi.liikennevirasto.viite.dao.CalibrationPointDAO.UserDefinedCalibrationPoint
import fi.liikennevirasto.viite.dao.Discontinuity.MinorDiscontinuity
import fi.liikennevirasto.viite.dao._
import fi.liikennevirasto.viite.process.Strategies.RoadSectionCalculatorContext
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
    val (terminated, others) = projectLinks.partition(_.status == LinkStatus.Terminated)
    val (newLinks, nonTerminatedLinks) = others.partition(l => l.status == LinkStatus.New)
    try {

      val calculator = RoadSectionCalculatorContext.getStrategy(others)
      logger.info(s"${calculator.name} strategy")
      calculator.assignMValues(newLinks, nonTerminatedLinks, userGivenCalibrationPoints) ++ terminated

    } finally {
      logger.info(s"Finished MValue assignment for ${projectLinks.size} links")
    }
  }





//  /**
//    * Calculates the address M values for the given set of project links and assigns them calibration points where applicable
//    *
//    * @param newProjectLinks List of new addressed links in project
//    * @param oldProjectLinks Other links in project, used as a guidance
//    * @return Sequence of project links with address values and calibration points.
//    */
//  private def assignMValues(newProjectLinks: Seq[ProjectLink], oldProjectLinks: Seq[ProjectLink],
//                            userCalibrationPoints: Seq[UserDefinedCalibrationPoint]): Seq[ProjectLink] = {
//
//
//    val groupedProjectLinks = newProjectLinks.groupBy(record => (record.roadNumber, record.roadPartNumber))
//    val groupedOldLinks = oldProjectLinks.groupBy(record => (record.roadNumber, record.roadPartNumber))
//    val group = (groupedProjectLinks.keySet ++ groupedOldLinks.keySet).map(k =>
//      k -> (groupedProjectLinks.getOrElse(k, Seq()), groupedOldLinks.getOrElse(k, Seq())))
//    group.flatMap { case (part, (projectLinks, oldLinks)) =>
//      try {
//        val (right, left) = TrackSectionOrder.orderProjectLinksTopologyByGeometry(
//          findStartingPoints(projectLinks, oldLinks, userCalibrationPoints), projectLinks ++ oldLinks)
//        val ordSections = TrackSectionOrder.createCombinedSections(right, left)
//
//        // TODO: userCalibrationPoints to Long -> Seq[UserDefinedCalibrationPoint] in method params
//        val calMap = userCalibrationPoints.map(c => c.projectLinkId -> c).toMap
//        //TODO
//        //Delete all the exisint calibration points on the projecLinks
//        //Add again the existing road address calibration points
//        //Then each section can add calibration points
//        calculateSectionAddressValues(ordSections, calMap)
////        val calculatedSections = calculateSectionAddressValues(ordSections, calMap)
////        val links = calculatedSections.flatMap{ sec =>
////          if (sec.right == sec.left)
////            assignCalibrationPoints(Seq(), sec.right.links, calMap)
////          else {
////            assignCalibrationPoints(Seq(), sec.right.links, calMap) ++
////              assignCalibrationPoints(Seq(), sec.left.links, calMap)
////          }
////        }
////        eliminateExpiredCalibrationPoints(links)
//      } catch {
//        case ex: InvalidAddressDataException =>
//          logger.info(s"Can't calculate road/road part ${part._1}/${part._2}: " + ex.getMessage)
//          projectLinks ++ oldLinks
//        case ex: NoSuchElementException =>
//          logger.info("Delta calculation failed: " + ex.getMessage, ex)
//          projectLinks ++ oldLinks
//        case ex: NullPointerException =>
//          logger.info("Delta calculation failed (NPE)", ex)
//          projectLinks ++ oldLinks
//        case ex: Throwable =>
//          logger.info("Delta calculation not possible: " + ex.getMessage)
//          projectLinks ++ oldLinks
//      }
//    }.toSeq
//  }
//
//  private def calculateSectionAddressValues(sections: Seq[CombinedSection],
//                                            userDefinedCalibrationPoint: Map[Long, UserDefinedCalibrationPoint]): Seq[CombinedSection] = {
//
//    def getContinuousTrack(seq: Seq[ProjectLink]): (Seq[ProjectLink], Seq[ProjectLink]) = {
//      val track= seq.headOption.map(_.track).getOrElse(Track.Unknown)
//      val continuousProjectLinks = seq.takeWhile(pl => pl.track == track && pl.discontinuity != MinorDiscontinuity)
//      val other = seq.drop(continuousProjectLinks.size)
//      val head = other.headOption
//      if(head.nonEmpty && head.get.discontinuity == MinorDiscontinuity)
//        (continuousProjectLinks :+ head.get, other.tail)
//      else
//        (continuousProjectLinks, other)
//    }
//
//    def getContinuousLinkStatus(seq: Seq[ProjectLink]): (Seq[ProjectLink], Seq[ProjectLink]) = {
//      val linkStatus = seq.headOption.map(_.status).getOrElse(LinkStatus.Unknown)
//      val continuousProjectLinks = seq.takeWhile(pl => pl.status == linkStatus)
//      (continuousProjectLinks, seq.drop(continuousProjectLinks.size))
//    }
//
//    def getContinuousUntilAddress(seq: Seq[ProjectLink], address: Long): (Seq[ProjectLink], Seq[ProjectLink]) = {
//      val continuousProjectLinks = seq.takeWhile(pl => pl.startAddrMValue > address || pl.endAddrMValue < address)
//      (continuousProjectLinks, seq.drop(continuousProjectLinks.size))
//    }
//
//    def getFixedAddress(rightLink: ProjectLink, leftLink: ProjectLink,
//                        maybeDefinedCalibrationPoint: Option[UserDefinedCalibrationPoint] = None): Option[(Long, Long)] = {
//      if ((rightLink.status == LinkStatus.Transfer && leftLink.status == LinkStatus.Transfer) ||
//        (rightLink.status == LinkStatus.UnChanged && leftLink.status == LinkStatus.UnChanged)) {
//        val reversed = rightLink.reversed || leftLink.reversed
//        Some(averageOfAddressMValues(rightLink.startAddrMValue, leftLink.startAddrMValue, reversed), averageOfAddressMValues(rightLink.endAddrMValue, leftLink.endAddrMValue, reversed))
//      } else if (rightLink.status == LinkStatus.UnChanged || rightLink.status == LinkStatus.Transfer) {
//        Some((rightLink.startAddrMValue, rightLink.endAddrMValue))
//      } else if (leftLink.status == LinkStatus.UnChanged || leftLink.status == LinkStatus.Transfer) {
//        Some((leftLink.startAddrMValue, leftLink.endAddrMValue))
//      } else {
//        maybeDefinedCalibrationPoint.map(c => (c.addressMValue, c.addressMValue)).orElse(None)
//      }
//    }
//
//    def assignValues(seq: Seq[ProjectLink], st: Long, en: Long, factor: TrackAddressingFactors): Seq[ProjectLink] = {
//      val coEff = (en - st - factor.unChangedLength - factor.transferLength) / factor.newLength
//      ProjectSectionMValueCalculator.assignLinkValues(seq, userDefinedCalibrationPoint, Some(st.toDouble), Some(en.toDouble), coEff)
//    }
//
//    def averageOfAddressMValues(rAddrM: Double, lAddrM: Double, reversed: Boolean): Long = {
//      val average = 0.5 * (rAddrM + lAddrM)
//
//      if (reversed) {
//        if (rAddrM > lAddrM) Math.floor(average).round else Math.ceil(average).round
//      } else {
//        if (rAddrM > lAddrM) Math.ceil(average).round else Math.floor(average).round
//      }
//    }
//
//    def adjustTwoTracks(right: Seq[ProjectLink], left: Seq[ProjectLink], startM: Long, endM: Long) = {
//      (assignValues(right, startM, endM, ProjectSectionMValueCalculator.calculateAddressingFactors(right)),
//        assignValues(left, startM, endM, ProjectSectionMValueCalculator.calculateAddressingFactors(left)))
//    }
//
//    def forceLastEndAddrMValue(projectLinks: Seq[ProjectLink], endAddrMValue: Long): Seq[ProjectLink] = {
//      if (projectLinks.last.status != LinkStatus.NotHandled)
//        projectLinks.init :+ projectLinks.last.copy(endAddrMValue = endAddrMValue)
//      else
//        projectLinks
//    }
//
//    def getEndRoadAddrMValue(rProjectLink: ProjectLink, lProjectLink: ProjectLink): Long = {
//      if (rProjectLink.status != LinkStatus.New && lProjectLink.status == LinkStatus.New) {
//        rProjectLink.endAddrMValue
//      } else if (rProjectLink.status == LinkStatus.New && lProjectLink.status != LinkStatus.New) {
//        lProjectLink.endAddrMValue
//      } else {
//        averageOfAddressMValues(rProjectLink.endAddrMValue, lProjectLink.endAddrMValue, reversed = rProjectLink.reversed || lProjectLink.reversed)
//      }
//    }
//
//    def splitAt(seq: Seq[ProjectLink], splittedAddress: Long)= {
//      val (pls, others) =  getContinuousUntilAddress(seq, splittedAddress)
//
//      val toSplitProjectLink = others.headOption
//      val nearTheBegin = toSplitProjectLink.get.toMeters(Math.abs(splittedAddress - toSplitProjectLink.get.startAddrMValue)) < 3
//      val nearTheEnd = toSplitProjectLink.get.toMeters(Math.abs(toSplitProjectLink.get.endAddrMValue - splittedAddress)) < 3
//
//      if(toSplitProjectLink.isEmpty){
//        (assignValues(pls, pls.head.startAddrMValue, splittedAddress, ProjectSectionMValueCalculator.calculateAddressingFactors(pls)), others)
//      } else {
//        (nearTheBegin, nearTheEnd) match {
//          case (true, false) =>
//            (assignValues(pls, pls.head.startAddrMValue, splittedAddress, ProjectSectionMValueCalculator.calculateAddressingFactors(pls)), others)
//          case (false, true) | (true, true) =>
//            //TODO check better that
//            (assignValues(pls :+ toSplitProjectLink.get, pls.head.startAddrMValue, splittedAddress, ProjectSectionMValueCalculator.calculateAddressingFactors(pls :+ toSplitProjectLink.get)), others.tail)
//          case _ =>
//            val (f, l) = toSplitProjectLink.get.splitAt(splittedAddress)
//            (pls :+ f :+ l, others.tail)
//        }
//      }
//    }
//
//    def doThings(_firstLeft: Seq[ProjectLink], _restLeft: Seq[ProjectLink], _firstRight: Seq[ProjectLink], _restRight: Seq[ProjectLink]) ={
//      if(_firstLeft.nonEmpty && _firstLeft.head.track != Combined){
//        val (firstRightLinkStatus, restRightLinkStatus) = getContinuousLinkStatus(_firstRight)
//        val (firstLeftLinkStatus, restLeftLinkStatus) = getContinuousLinkStatus(_firstLeft)
//
//        (restRightLinkStatus.nonEmpty, restLeftLinkStatus.nonEmpty) match {
//          case (true, true) =>
//            val (leftSplittedAddress, rightSplittedAddress) = (restLeftLinkStatus.head.startAddrMValue, restRightLinkStatus.head.startAddrMValue)
//            if(leftSplittedAddress == rightSplittedAddress){
//              (firstLeftLinkStatus, restLeftLinkStatus ++ _restLeft, firstRightLinkStatus, restRightLinkStatus ++ _restRight)
//            }else if(rightSplittedAddress > leftSplittedAddress){
//              val (pls, others) = splitAt(_firstRight, leftSplittedAddress)
//              (firstLeftLinkStatus, restLeftLinkStatus ++ _restLeft, pls, others ++ _restRight)
//            } else {
//              val (pls, others) = splitAt(_firstLeft, rightSplittedAddress)
//              (pls, others ++ _restLeft, firstRightLinkStatus, restRightLinkStatus ++ _restRight)
//            }
//          case (false, true) =>
//            val (pls, others) = splitAt(_firstRight, restLeftLinkStatus.head.startAddrMValue)
//            (firstLeftLinkStatus, restLeftLinkStatus ++ _restLeft, pls, others ++ _restRight)
//          case (true, false) =>
//            val (pls, others) = splitAt(_firstLeft, restRightLinkStatus.head.startAddrMValue)
//            (pls, others ++ _restLeft, firstRightLinkStatus, restRightLinkStatus ++ _restRight)
//          case _ =>
//            (_firstLeft, _restLeft, _firstRight, _restRight)
//        }
//      }else{
//        (_firstLeft, _restLeft, _firstRight, _restRight)
//      }
//    }
//
//    def adjustTracksToMatch(rightLinks: Seq[ProjectLink], leftLinks: Seq[ProjectLink], previousStart: Option[Long]): (Seq[ProjectLink], Seq[ProjectLink]) = {
//      if (rightLinks.isEmpty && leftLinks.isEmpty) {
//        (Seq(), Seq())
//      } else {
//
//        //TODO do it better this is just for test propose
//        val (firstRight, restRight) = getContinuousTrack(rightLinks)
//        val (firstLeft, restLeft) = getContinuousTrack(leftLinks)
//
//        if (firstRight.nonEmpty && firstLeft.nonEmpty) {
//          val availableCalibrationPoint = userDefinedCalibrationPoint.get(firstRight.last.id).orElse(userDefinedCalibrationPoint.get(firstLeft.last.id))
//          val start = previousStart.getOrElse(getFixedAddress(firstRight.head, firstLeft.head).map(_._1).getOrElse(averageOfAddressMValues(firstRight.head.startAddrMValue, firstLeft.head.startAddrMValue, reversed = firstRight.head.reversed || firstLeft.head.reversed)))
//          val estimatedEnd = getFixedAddress(firstRight.last, firstLeft.last, availableCalibrationPoint).map(_._2).getOrElse(averageOfAddressMValues(firstRight.last.endAddrMValue, firstLeft.last.endAddrMValue, reversed = firstRight.last.reversed || firstLeft.last.reversed))
//          val (adjustedFirstRight, adjustedFirstLeft) = adjustTwoTracks(firstRight, firstLeft, start, estimatedEnd)
//
//          val endMValue = getEndRoadAddrMValue(adjustedFirstRight.last, adjustedFirstLeft.last)
//
//          if(endMValue != estimatedEnd)
//            throw new
//
//          val (adjustedRestRight, adjustedRestLeft) = adjustTracksToMatch(restRight, restLeft, Some(endMValue))
//
//          (forceLastEndAddrMValue(adjustedFirstRight, endMValue) ++ adjustedRestRight, forceLastEndAddrMValue(adjustedFirstLeft, endMValue) ++ adjustedRestLeft)
//        } else {
//          throw new RoadAddressException(s"Mismatching tracks, R ${firstRight.size}, L ${firstLeft.size}")
//        }
//      }
//    }
//
//    val rightLinks = ProjectSectionMValueCalculator.calculateMValuesForTrack(sections.flatMap(_.right.links), userDefinedCalibrationPoint)
//    val leftLinks = ProjectSectionMValueCalculator.calculateMValuesForTrack(sections.flatMap(_.left.links), userDefinedCalibrationPoint)
//    val (right, left) = adjustTracksToMatch(rightLinks.sortBy(_.startAddrMValue), leftLinks.sortBy(_.startAddrMValue), None)
//    TrackSectionOrder.createCombinedSections(right, left)
//  }

  //TODO THIS IS NO USED in any place get another utils for it
  def switchSideCode(sideCode: SideCode): SideCode = {
    // Switch between against and towards 2 -> 3, 3 -> 2
    SideCode.apply(5 - sideCode.value)
  }

}

case class RoadAddressSection(roadNumber: Long, roadPartNumberStart: Long, roadPartNumberEnd: Long, track: Track,
                              startMAddr: Long, endMAddr: Long, discontinuity: Discontinuity, roadType: RoadType, ely: Long, reversed: Boolean, commonHistoryId: Long) {
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
      // and same common history
      ra.commonHistoryId == commonHistoryId
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


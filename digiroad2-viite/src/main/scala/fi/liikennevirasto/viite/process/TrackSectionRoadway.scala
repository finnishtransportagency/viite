package fi.liikennevirasto.viite.process

import fi.liikennevirasto.GeometryUtils
import fi.liikennevirasto.digiroad2.asset.SideCode.TowardsDigitizing
import fi.liikennevirasto.digiroad2.dao.Sequences
import fi.liikennevirasto.digiroad2.util.Track
import fi.liikennevirasto.digiroad2.util.Track._
import fi.liikennevirasto.viite.dao._
import fi.liikennevirasto.viite.process.strategy.TrackCalculatorContext
import fi.liikennevirasto.viite.{MaxThresholdDistance, NewIdValue, RoadType}

import scala.annotation.tailrec
import scala.collection.immutable.ListMap

object TrackSectionRoadway {

  lazy val roadwayDAO = new RoadwayDAO
  lazy val linearLocationDAO = new LinearLocationDAO
  lazy val roadwayAddressMapper = new RoadwayAddressMapper(roadwayDAO: RoadwayDAO, linearLocationDAO: LinearLocationDAO)

  /**
    *
    * @param firstRight
    * @param restRight
    * @param firstLeft
    * @param restLeft
    * @return
    */
  def handleRoadwayNumbers(rightLinks: Seq[ProjectLink], firstRight: Seq[ProjectLink], restRight: Seq[ProjectLink],
                           leftLinks: Seq[ProjectLink], firstLeft: Seq[ProjectLink], restLeft: Seq[ProjectLink])
  : ((Seq[ProjectLink], Seq[ProjectLink]), (Seq[ProjectLink], Seq[ProjectLink])) = {
    if (firstRight.map(_.roadwayNumber).distinct.size == firstLeft.map(_.roadwayNumber).distinct.size || (firstRight ++ firstLeft).exists(_.status == LinkStatus.NotHandled)) {
      val newRoadwayNumber1 = Sequences.nextRoadwayNumber
      val newRoadwayNumber2 = if (rightLinks.head.track == Track.Combined || leftLinks.head.track == Track.Combined) newRoadwayNumber1 else Sequences.nextRoadwayNumber
      (continuousRoadwaySection(rightLinks, newRoadwayNumber1), continuousRoadwaySection(leftLinks, newRoadwayNumber2))
    } else {
      val (adjustedRight, adjustedLeft) = adjustTwoTrackRoadwayNumbers(firstRight, firstLeft)
      ((adjustedRight, restRight), (adjustedLeft, restLeft))
    }
  }

  private def continuousRoadwaySection(seq: Seq[ProjectLink], givenRoadwayNumber: Long): (Seq[ProjectLink], Seq[ProjectLink]) = {
    val track = seq.headOption.map(_.track).getOrElse(Track.Unknown)
    val roadType = seq.headOption.map(_.roadType.value).getOrElse(RoadType.Empty.value)
    val status = seq.headOption.map(_.status.value).getOrElse(LinkStatus.NotHandled.value)

    val continuousProjectLinks =
      seq.takeWhile(pl => pl.track == track && pl.roadType.value == roadType && pl.status.value == status).sortBy(_.startAddrMValue)

    val assignedContinuousSection = assignRoadwayNumbersInContinuousSection(continuousProjectLinks, givenRoadwayNumber)
    (assignedContinuousSection, seq.drop(assignedContinuousSection.size))
  }

  private def assignRoadwayNumbersInContinuousSection(links: Seq[ProjectLink], givenRoadwayNumber: Long): Seq[ProjectLink] = {
    val roadwayNumber = links.headOption.map(_.roadwayNumber).getOrElse(NewIdValue)
    val firstLinkStatus = links.headOption.map(_.status).getOrElse(LinkStatus.Unknown)
    val originalHistorySection = if (firstLinkStatus == LinkStatus.New) Seq() else links.takeWhile(pl => pl.roadwayNumber == roadwayNumber)
    val continuousRoadwayNumberSection =
      if (firstLinkStatus == LinkStatus.New)
        links.takeWhile(pl => pl.status.equals(LinkStatus.New)).sortBy(_.startAddrMValue)
      else
        links.takeWhile(pl => pl.roadwayNumber == roadwayNumber).sortBy(_.startAddrMValue)

    val (assignedRoadwayNumber, nextRoadwayNumber) = assignProperRoadwayNumber(continuousRoadwayNumberSection, givenRoadwayNumber, originalHistorySection)
    val rest = links.drop(continuousRoadwayNumberSection.size)
    continuousRoadwayNumberSection.map(pl => pl.copy(roadwayNumber = assignedRoadwayNumber)) ++
      (if (rest.isEmpty) Seq() else assignRoadwayNumbersInContinuousSection(rest, nextRoadwayNumber))
  }

  private def assignProperRoadwayNumber(continuousProjectLinks: Seq[ProjectLink], givenRoadwayNumber: Long, originalHistorySection: Seq[ProjectLink]): (Long, Long) = {
    def getRoadAddressesByRoadwayIds(roadwayIds: Seq[Long]): Seq[RoadAddress] = {
      val roadways = roadwayDAO.fetchAllByRoadwayId(roadwayIds)
      val roadAddresses = roadwayAddressMapper.getRoadAddressesByRoadway(roadways)
      roadAddresses
    }

    val roadwayNumbers = if (continuousProjectLinks.nonEmpty && continuousProjectLinks.exists(_.status == LinkStatus.New)) {
      // then we now that for sure the addresses increased their length for the part => new roadwayNumber for the new sections
      (givenRoadwayNumber, Sequences.nextRoadwayNumber)
    } else if (continuousProjectLinks.nonEmpty && continuousProjectLinks.exists(_.status == LinkStatus.Numbering)) {
      // then we now that for sure the addresses didnt change the address length part, only changed the number of road or part => same roadwayNumber
      (continuousProjectLinks.headOption.map(_.roadwayNumber).get, givenRoadwayNumber)
    } else {
      val originalAddresses = getRoadAddressesByRoadwayIds(originalHistorySection.map(_.roadwayId))
      val isSameAddressLengthSection = (continuousProjectLinks.last.endAddrMValue - continuousProjectLinks.head.startAddrMValue) == (originalAddresses.last.endAddrMValue - originalAddresses.head.startAddrMValue)

      if (isSameAddressLengthSection)
        (continuousProjectLinks.headOption.map(_.roadwayNumber).get, givenRoadwayNumber)
      else
        (givenRoadwayNumber, Sequences.nextRoadwayNumber)
    }
    roadwayNumbers
  }

  private def adjustTwoTrackRoadwayNumbers(firstRight: Seq[ProjectLink], firstLeft: Seq[ProjectLink])
  : (Seq[ProjectLink], Seq[ProjectLink]) = {
    val (referenceLinks, otherLinks) =
      if (firstRight.map(_.roadwayNumber).distinct.size > firstLeft.map(_.roadwayNumber).distinct.size) {
        (firstRight.sortBy(_.startAddrMValue), firstLeft.sortBy(_.startAddrMValue))
      } else {
        (firstLeft.sortBy(_.startAddrMValue), firstRight.sortBy(_.startAddrMValue))
      }

    val groupedReferenceLinks: ListMap[Long, Seq[ProjectLink]] = ListMap(referenceLinks.groupBy(_.roadwayNumber).toSeq.sortBy(r => r._2.minBy(_.startAddrMValue).startAddrMValue): _*)
    val referenceLength: Double = groupedReferenceLinks.values.flatten.map(l => l.endMValue - l.startMValue).sum
    val otherLength: Double = otherLinks.map(l => l.endMValue - l.startMValue).sum
    val resetLinksIfNeed = if (otherLinks.exists(_.connectedLinkId.nonEmpty)) otherLinks else otherLinks.map(_.copy(roadwayNumber = NewIdValue))

    val oppositeTrackRoadwayNumbers =
      if (groupedReferenceLinks.size == resetLinksIfNeed.filterNot(_.roadwayNumber == NewIdValue).map(_.roadwayNumber).distinct.size)
        otherLinks
      else
        splitLinksIfNeed(
          remainingReference = groupedReferenceLinks,
          processedReference = Seq(),
          remainingOppositeTrack = otherLinks.map(_.copy(roadwayNumber = NewIdValue)),
          processedOppositeTrack = Seq(),
          referenceLength, otherLength, groupedReferenceLinks.size)

    val referenceTrackRoadwayNumbers = continuousRoadwaySection(referenceLinks, Sequences.nextRoadwayNumber)._1

    val (right, left) =
      if (oppositeTrackRoadwayNumbers.exists(_.track == Track.RightSide))
        (oppositeTrackRoadwayNumbers, referenceTrackRoadwayNumbers)
      else
        (referenceTrackRoadwayNumbers, oppositeTrackRoadwayNumbers)

    (right, left)
  }

  /**
    *
    * @param remainingReference
    * @param processedReference
    * @param remainingOppositeTrack
    * @param processedOppositeTrack
    * @param totalReferenceMLength
    * @param totalOppositeTrackMLength
    * @param missingRoadwayNumbers
    * @return matched new links by roadway numbers according to their same size proportion
    */
  @tailrec
  private def splitLinksIfNeed(remainingReference: ListMap[Long, Seq[ProjectLink]], processedReference: Seq[ProjectLink], remainingOppositeTrack: Seq[ProjectLink], processedOppositeTrack: Seq[ProjectLink], totalReferenceMLength: Double, totalOppositeTrackMLength: Double, missingRoadwayNumbers: Int): Seq[ProjectLink] = {
    if (missingRoadwayNumbers == 0 || (remainingOppositeTrack.nonEmpty && remainingReference.isEmpty)) {
      val remainingRoadwayNumber = Sequences.nextRoadwayNumber
      val unassignedRoadwayNumber = Sequences.nextRoadwayNumber
      val (unassignedRwnLinks, assignedRwnLinks) = processedOppositeTrack.partition(_.roadwayNumber == NewIdValue)
      assignedRwnLinks ++ unassignedRwnLinks.map(_.copy(roadwayNumber = unassignedRoadwayNumber)) ++ remainingOppositeTrack.map(_.copy(roadwayNumber = remainingRoadwayNumber))
    } else if (remainingOppositeTrack.isEmpty && remainingReference.isEmpty) {
      processedOppositeTrack
    } else {
      //  Reference M length coeff
      val groupTransferMLength = remainingReference.head._2.map(l => l.endMValue - l.startMValue).sum
      val minAllowedTransferGroupCoeff = (groupTransferMLength - MaxThresholdDistance) / totalReferenceMLength
      val maxAllowedTransferGroupCoeff = (groupTransferMLength + MaxThresholdDistance) / totalReferenceMLength

      //  Opposite M length coeff
      val processingOppositeTrack = processedOppositeTrack.filter(_.roadwayNumber == NewIdValue)
      val processingLength: Double = if (processingOppositeTrack.isEmpty) {
        remainingOppositeTrack.head.endMValue - remainingOppositeTrack.head.startMValue
      } else {
        (remainingOppositeTrack.head.endMValue - remainingOppositeTrack.head.startMValue) +
          processingOppositeTrack.map(l => l.endMValue - l.startMValue).sum
      }

      val strategy = TrackCalculatorContext.getStrategy(remainingReference.flatMap(_._2).toSeq, remainingOppositeTrack)

      val currentNewLinksGroupCoeff: Double = processingLength / totalOppositeTrackMLength
      if (minAllowedTransferGroupCoeff <= currentNewLinksGroupCoeff && currentNewLinksGroupCoeff <= maxAllowedTransferGroupCoeff) {
        val (unassignedRwnLinks, assignedRwnLinks) = (processedOppositeTrack :+ remainingOppositeTrack.head).partition(_.roadwayNumber == NewIdValue)
        val nextRoadwayNumber = Sequences.nextRoadwayNumber

        val remainingOpposite = if (remainingOppositeTrack.tail.nonEmpty) {
          remainingOppositeTrack.tail.head.copy(startAddrMValue = remainingReference.head._2.last.endAddrMValue) +: remainingOppositeTrack.tail.tail
        } else { remainingOppositeTrack.tail }

        val estimatedEnd = if (remainingOpposite.nonEmpty) {
          remainingReference.head._2.last.endAddrMValue
        } else {
          remainingReference.head._2.last.track match {
            case r if r.value == Track.RightSide.value =>
              val strategy = TrackCalculatorContext.getStrategy(unassignedRwnLinks, remainingReference.head._2)
              strategy.getFixedAddress(unassignedRwnLinks.last, remainingReference.head._2.last)._2
            case _ =>
              val strategy = TrackCalculatorContext.getStrategy(remainingReference.head._2, unassignedRwnLinks)
              strategy.getFixedAddress(remainingReference.head._2.last, unassignedRwnLinks.last)._2
          }
        }

        val estimatedStart = if (processedOppositeTrack.nonEmpty) {
          estimatedEnd
        } else {
          remainingReference.head._2.last.track match {
            case r if r.value == Track.RightSide.value =>
              val strategy = TrackCalculatorContext.getStrategy(unassignedRwnLinks, remainingReference.head._2)
              strategy.getFixedAddress(unassignedRwnLinks.head, remainingReference.head._2.head)._1
            case _ =>
              val strategy = TrackCalculatorContext.getStrategy(remainingReference.head._2, unassignedRwnLinks)
              strategy.getFixedAddress(remainingReference.head._2.head, unassignedRwnLinks.head)._1
          }
        }

        val processedOpposite = assignedRwnLinks ++ unassignedRwnLinks.init.map(_.copy(roadwayNumber = nextRoadwayNumber)) :+
          unassignedRwnLinks.last.copy(roadwayNumber = nextRoadwayNumber, startAddrMValue = estimatedStart, endAddrMValue = estimatedEnd, connectedLinkId = Some(unassignedRwnLinks.last.linkId))

        splitLinksIfNeed(
          remainingReference = remainingReference.tail,
          processedReference = processedReference ++ remainingReference.head._2.init :+ remainingReference.head._2.last.copy(startAddrMValue = estimatedStart, endAddrMValue = estimatedEnd),
          remainingOppositeTrack = remainingOpposite,
          processedOppositeTrack = processedOpposite,
          totalOppositeTrackMLength, totalOppositeTrackMLength, missingRoadwayNumbers - 1)
      } else if (minAllowedTransferGroupCoeff > currentNewLinksGroupCoeff) {
        splitLinksIfNeed(
          remainingReference = remainingReference,
          processedReference = processedReference,
          remainingOppositeTrack = remainingOppositeTrack.tail,
          processedOppositeTrack = processedOppositeTrack :+ remainingOppositeTrack.head,
          totalReferenceMLength, totalOppositeTrackMLength, missingRoadwayNumbers)
      } else {
        /*  Calculate missing geometry left to fulfill the exactly groupTransfer coefficient
            * Note: and by that we want to pick previous processedLinks
          */

        val processedToBeAssigned = processedOppositeTrack.filter(_.roadwayNumber == NewIdValue)
        val previousProcessed = if (processedToBeAssigned.isEmpty) Seq() else processedToBeAssigned
        val linkToBeSplit = remainingOppositeTrack.head
        val previousProcessedLength: Double = previousProcessed.map(l => l.endMValue - l.startMValue).sum

        val perfectTransferGroupCoeff = groupTransferMLength / totalReferenceMLength

        /*
         *  (previousProcessedLength+m)/totalNewMLength = perfectTransferGroupCoeff <=>
         *  <=> previousProcessedLength+m = perfectTransferGroupCoeff*totalNewMLength <=>
         *  <=> m = (perfectTransferGroupCoeff*totalNewMLength) - previousProcessedLength
         */
        val splitMValue = (perfectTransferGroupCoeff * totalOppositeTrackMLength) - previousProcessedLength
        val firstSplitEndAddr = remainingReference.head._2.last.endAddrMValue
        val firstSplitLinkGeom = if (linkToBeSplit.sideCode == TowardsDigitizing) GeometryUtils.truncateGeometry2D(linkToBeSplit.geometry, 0.0, splitMValue)
        else GeometryUtils.truncateGeometry2D(linkToBeSplit.geometry, linkToBeSplit.endMValue - splitMValue, linkToBeSplit.endMValue)
        val (firstSplitStartMeasure, firstSplitEndMeasure) = if (linkToBeSplit.sideCode == TowardsDigitizing) (linkToBeSplit.startMValue, linkToBeSplit.startMValue + splitMValue) else
          (linkToBeSplit.endMValue - splitMValue, linkToBeSplit.endMValue)
        val (secondSplitStartMeasure, secondSplitEndMeasure) = if (linkToBeSplit.sideCode == TowardsDigitizing) (linkToBeSplit.startMValue + splitMValue, linkToBeSplit.endMValue) else
          (linkToBeSplit.startMValue, linkToBeSplit.endMValue - splitMValue)
        val secondSplitedLinkGeom = if (linkToBeSplit.sideCode == TowardsDigitizing) GeometryUtils.truncateGeometry2D(linkToBeSplit.geometry, splitMValue, linkToBeSplit.endMValue)
        else GeometryUtils.truncateGeometry2D(linkToBeSplit.geometry, 0.0, linkToBeSplit.endMValue - splitMValue)

        //  processedLinks without and with roadwayNumber
        val (unassignedRwnLinks, assignedRwnLinks) = processedOppositeTrack.partition(_.roadwayNumber == NewIdValue)
        val nextRoadwayNumber = Sequences.nextRoadwayNumber
        val processedOppositeTrackWithSplitLink = assignedRwnLinks ++ unassignedRwnLinks.map(_.copy(roadwayNumber = nextRoadwayNumber)) :+ linkToBeSplit.copy(startMValue = firstSplitStartMeasure, endMValue = firstSplitEndMeasure,
          geometry = firstSplitLinkGeom, geometryLength = GeometryUtils.geometryLength(firstSplitLinkGeom), endAddrMValue = firstSplitEndAddr,
          roadwayNumber = nextRoadwayNumber, connectedLinkId = Some(linkToBeSplit.linkId))

        splitLinksIfNeed(
          remainingReference = remainingReference.tail,
          processedReference = processedReference ++ remainingReference.head._2,
          remainingOppositeTrack =
            linkToBeSplit.copy(id = NewIdValue, startMValue = secondSplitStartMeasure, endMValue = secondSplitEndMeasure, geometry = secondSplitedLinkGeom, geometryLength = GeometryUtils.geometryLength(secondSplitedLinkGeom), startAddrMValue = firstSplitEndAddr) +:
              remainingOppositeTrack.tail,
          processedOppositeTrack = processedOppositeTrackWithSplitLink, totalReferenceMLength, totalOppositeTrackMLength, missingRoadwayNumbers - 1)
      }
    }
  }
}

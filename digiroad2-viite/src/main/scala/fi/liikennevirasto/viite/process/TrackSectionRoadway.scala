package fi.liikennevirasto.viite.process

import fi.liikennevirasto.GeometryUtils
import fi.liikennevirasto.digiroad2.asset.SideCode.TowardsDigitizing
import fi.liikennevirasto.digiroad2.dao.Sequences
import fi.liikennevirasto.digiroad2.util.Track
import fi.liikennevirasto.viite.dao.LinkStatus.Terminated
import fi.liikennevirasto.viite.dao._
import fi.liikennevirasto.viite.process.strategy.TrackCalculatorContext
import fi.liikennevirasto.viite.{MaxThresholdDistance, NewIdValue, RoadType}
import org.slf4j.LoggerFactory

import scala.annotation.tailrec
import scala.collection.immutable.ListMap

object TrackSectionRoadway {

  private val logger = LoggerFactory.getLogger(getClass)

  lazy val roadwayDAO = new RoadwayDAO
  lazy val linearLocationDAO = new LinearLocationDAO
  lazy val roadwayAddressMapper = new RoadwayAddressMapper(roadwayDAO: RoadwayDAO, linearLocationDAO: LinearLocationDAO)
  lazy val projectLinkDAO = new ProjectLinkDAO

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

    val currentProcessingProjectLinks = rightLinks ++ leftLinks
    val projectId = currentProcessingProjectLinks.headOption.map(_.projectId)

    val newRoadAddresses: Seq[ProjectLink] = projectId.map { prjId =>
      projectLinkDAO.fetchByProjectRoadParts(currentProcessingProjectLinks.map(pl => (pl.roadNumber, pl.roadPartNumber)).distinct.toSet, prjId)
    }.getOrElse(Seq.empty[ProjectLink])

    val originalRoadAddresses: Seq[ProjectLink] = projectId.map { prjId =>
      projectLinkDAO.fetchByProjectRoadParts(currentProcessingProjectLinks.filter(pl => pl.roadAddressRoadNumber.isDefined && pl.roadAddressRoadPart.isDefined)
          .map(pl => (pl.roadAddressRoadNumber.get, pl.roadAddressRoadPart.get)).distinct.toSet, prjId)
    }.getOrElse(Seq.empty[ProjectLink])

    if (rightLinks.isEmpty || leftLinks.isEmpty || (newRoadAddresses ++ originalRoadAddresses).exists(_.status == LinkStatus.NotHandled)) {
      ((firstRight, restRight), (firstLeft, restLeft))
    } else if (firstRight.map(_.roadwayNumber).distinct.size == firstLeft.map(_.roadwayNumber).distinct.size) {
      val newRoadwayNumber1 = Sequences.nextRoadwayNumber
      val newRoadwayNumber2 = if (rightLinks.head.track == Track.Combined || leftLinks.head.track == Track.Combined) newRoadwayNumber1 else Sequences.nextRoadwayNumber
      val handledRight = continuousRoadwaySection(firstRight, newRoadwayNumber1, firstLeft)
      val handledLeft = continuousRoadwaySection(firstLeft, newRoadwayNumber2, firstRight)
      ((handledRight._1, handledRight._2 ++ restRight), (handledLeft._1, handledLeft._2 ++ restLeft))
    } else {
      val (adjustedRight, adjustedLeft) = adjustTwoTrackRoadwayNumbers(firstRight, firstLeft)
      ((adjustedRight, restRight), (adjustedLeft, restLeft))
    }
  }

  private def continuousRoadwaySection(seq: Seq[ProjectLink], givenRoadwayNumber: Long, opposite: Seq[ProjectLink] = Seq.empty[ProjectLink]): (Seq[ProjectLink], Seq[ProjectLink]) = {
    val track = seq.headOption.map(_.track).getOrElse(Track.Unknown)
    val roadType = seq.headOption.map(_.roadType.value).getOrElse(RoadType.Empty.value)
    val status = seq.headOption.map(_.status.value).getOrElse(LinkStatus.NotHandled.value)

    val (continuousProjectLinks, restProjectLinks) = {
      val splitAddrMValue = opposite.find(_.isSplit).map(_.endAddrMValue)
      val startAddrMValue = seq.headOption.map(_.startAddrMValue)
      val existSplitOnOppositeTrack = opposite.exists(_.isSplit) &&
        (splitAddrMValue.isDefined && startAddrMValue.isDefined && splitAddrMValue.get > startAddrMValue.get)
      val continuousSection = seq.takeWhile { pl =>
        pl.track == track && pl.roadType.value == roadType && pl.status.value == status && !pl.isSplit && !(existSplitOnOppositeTrack && splitAddrMValue.contains(pl.endAddrMValue))
      }.sortBy(_.startAddrMValue)

      if ((seq.exists(_.isSplit) || existSplitOnOppositeTrack) && seq.drop(continuousSection.size).nonEmpty)
        (continuousSection :+ seq.drop(continuousSection.size).head, seq.drop(continuousSection.size + 1))
      else
        (continuousSection, seq.drop(continuousSection.size))
    }

    val assignedContinuousSection = assignRoadwayNumbersInContinuousSection(continuousProjectLinks, givenRoadwayNumber)
    (assignedContinuousSection, restProjectLinks)
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

    def  reAssignedRoadwayNumbers (seq: Seq[ProjectLink], givenRoadwayNumber: Long, opposite: Seq[ProjectLink] = Seq.empty[ProjectLink]): Seq[ProjectLink] = {
      if (seq.isEmpty) { Seq() }
      else {
        val (a, b) = continuousRoadwaySection(seq, Sequences.nextRoadwayNumber, opposite)
        a ++ reAssignedRoadwayNumbers(b, Sequences.nextRoadwayNumber, opposite)
      }
    }

    val (referenceLinks, otherLinks) =
      if (firstRight.map(_.roadwayNumber).distinct.size > firstLeft.map(_.roadwayNumber).distinct.size) {
        (firstRight.sortBy(_.startAddrMValue), firstLeft.sortBy(_.startAddrMValue))
      } else {
        (firstLeft.sortBy(_.startAddrMValue), firstRight.sortBy(_.startAddrMValue))
      }

    val groupedReferenceLinks: ListMap[Long, Seq[ProjectLink]] = ListMap(
      referenceLinks.map { pl =>
        pl.status match {
          case LinkStatus.New => pl.copy(roadwayNumber = NewIdValue)
          case _ => pl
        }
      }.groupBy(_.roadwayNumber).toSeq.sortBy(r => r._2.minBy(_.startAddrMValue).startAddrMValue): _*)
    val referenceLength: Double = groupedReferenceLinks.values.flatten.map(l => l.endMValue - l.startMValue).sum
    val otherLength: Double = otherLinks.map(l => l.endMValue - l.startMValue).sum
    val resetLinksIfNeed = otherLinks.map { pl =>
      pl.status match {
        case LinkStatus.New => pl.copy(roadwayNumber = NewIdValue)
        case _ => pl
      }
    }

    val processedProjectLinks =
      if (groupedReferenceLinks.size == resetLinksIfNeed.filterNot(_.roadwayNumber == NewIdValue).map(_.roadwayNumber).distinct.size)
        referenceLinks ++ otherLinks
      else
        splitLinksIfNeed(
          remainingReference = groupedReferenceLinks,
          processedReference = Seq(),
          remainingOppositeTrack = resetLinksIfNeed,
          processedOppositeTrack = Seq(),
          referenceLength, otherLength, groupedReferenceLinks.size - resetLinksIfNeed.map(_.roadwayNumber).distinct.size)

    val referenceTrack = referenceLinks.headOption.map(_.track)
    val oppositeTrack = otherLinks.headOption.map(_.track)

    val processedOthersProjectLinks: Seq[ProjectLink] = processedProjectLinks.filter(pl => oppositeTrack.contains(pl.track))
    val processedReferenceProjectLinks = reAssignedRoadwayNumbers(processedProjectLinks.filter(pl => referenceTrack.contains(pl.track)), Sequences.nextRoadwayNumber, processedOthersProjectLinks)

    val reAssignedOppositeRoadwayNumbers: Seq[ProjectLink] =
      if (processedOthersProjectLinks.exists(l => l.status == LinkStatus.New && l.isSplit))
        processedOthersProjectLinks
      else
        reAssignedRoadwayNumbers(processedOthersProjectLinks, Sequences.nextRoadwayNumber)

    if (referenceTrack.contains(Track.RightSide)) {
      (processedReferenceProjectLinks, reAssignedOppositeRoadwayNumbers)
    } else {
      (reAssignedOppositeRoadwayNumbers, processedReferenceProjectLinks)
    }
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
  private def splitLinksIfNeed(remainingReference: ListMap[Long, Seq[ProjectLink]], processedReference: Seq[ProjectLink],
                               remainingOppositeTrack: Seq[ProjectLink], processedOppositeTrack: Seq[ProjectLink],
                               totalReferenceMLength: Double, totalOppositeTrackMLength: Double, missingRoadwayNumbers: Int): Seq[ProjectLink] = {
    if (missingRoadwayNumbers == 0 || (remainingOppositeTrack.nonEmpty && remainingReference.isEmpty)) {
      val unassignedRoadwayNumber = Sequences.nextRoadwayNumber
      val (unassignedRwnLinks, assignedRwnLinks) = processedOppositeTrack.partition(_.roadwayNumber == NewIdValue)

      assignedRwnLinks ++
        unassignedRwnLinks.map(_.copy(roadwayNumber = unassignedRoadwayNumber)) ++
        remainingOppositeTrack.map { pl =>
          pl.roadwayNumber match {
            case rn if rn == NewIdValue => pl.copy(roadwayNumber = unassignedRoadwayNumber)
            case _ => pl
          }
        } ++ processedReference ++ remainingReference.flatMap(_._2)
    } else if (remainingOppositeTrack.isEmpty && remainingReference.isEmpty) {
      processedOppositeTrack ++ processedReference
    } else {
      //  Reference M length coeff
      val groupTransferMLength = remainingReference.head._2.map(l => l.endMValue - l.startMValue).sum
      val minAllowedTransferGroupCoeff = (groupTransferMLength - MaxThresholdDistance) / totalReferenceMLength
      val maxAllowedTransferGroupCoeff = (groupTransferMLength + MaxThresholdDistance) / totalReferenceMLength

      //  Opposite M length coeff
      val oppositeToProcess = processedOppositeTrack
      val processingOppositeTrack = oppositeToProcess.filter(_.roadwayNumber == NewIdValue)
      val processingLength: Double = if (processingOppositeTrack.isEmpty && remainingOppositeTrack.nonEmpty) {
        remainingOppositeTrack.head.endMValue - remainingOppositeTrack.head.startMValue
      } else if (processingOppositeTrack.isEmpty && remainingOppositeTrack.isEmpty) {
        val processingRoadwayNumber = processedOppositeTrack.last.roadwayNumber
        val processingOppositeTrackBySameRoadwayNumber = processedOppositeTrack.filter(_.roadwayNumber == processingRoadwayNumber)
        processingOppositeTrackBySameRoadwayNumber.map(l => l.endMValue - l.startMValue).sum
      } else {
        (remainingOppositeTrack.head.endMValue - remainingOppositeTrack.head.startMValue) +
          processingOppositeTrack.map(l => l.endMValue - l.startMValue).sum
      }

      val currentNewLinksGroupCoeff: Double = processingLength / totalOppositeTrackMLength
      if (currentNewLinksGroupCoeff >= minAllowedTransferGroupCoeff && currentNewLinksGroupCoeff <= maxAllowedTransferGroupCoeff) {
        val (unassignedRwnLinks, assignedRwnLinks) = (processedOppositeTrack :+ remainingOppositeTrack.head).partition(_.roadwayNumber == NewIdValue)
        val nextRoadwayNumber = Sequences.nextRoadwayNumber

        val remainingOpposite = if (remainingOppositeTrack.tail.nonEmpty) {
          remainingOppositeTrack.tail.head.copy(startAddrMValue = remainingReference.head._2.last.endAddrMValue) +: remainingOppositeTrack.tail.tail
        } else remainingOppositeTrack.tail

        val unassignedLinks = if (unassignedRwnLinks.nonEmpty) {
          unassignedRwnLinks.init.map(_.copy(roadwayNumber = nextRoadwayNumber)) :+ unassignedRwnLinks.last.copy(roadwayNumber = nextRoadwayNumber, endAddrMValue = remainingReference.head._2.last.endAddrMValue, connectedLinkId = Some(unassignedRwnLinks.last.linkId))
        } else unassignedRwnLinks
        splitLinksIfNeed(
          remainingReference.tail,
          processedReference ++ remainingReference.head._2,
          remainingOpposite,
          assignedRwnLinks ++ unassignedLinks,
          totalReferenceMLength, totalOppositeTrackMLength, missingRoadwayNumbers - 1)
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

        val oppositeToProcess = processedOppositeTrack
        val processedToBeAssigned = oppositeToProcess.filter(_.roadwayNumber == NewIdValue)
        val linkToBeSplit = remainingOppositeTrack.headOption.getOrElse(oppositeToProcess.last)
        val previousProcessedLength: Double = processedToBeAssigned.map(l => l.endMValue - l.startMValue).sum

        val perfectTransferGroupCoeff = groupTransferMLength / totalReferenceMLength

        /*
         *  (previousProcessedLength+m)/totalNewMLength = perfectTransferGroupCoeff <=>
         *  <=> previousProcessedLength+m = perfectTransferGroupCoeff*totalNewMLength <=>
         *  <=> m = (perfectTransferGroupCoeff*totalNewMLength) - previousProcessedLength
         */
        val splitMValue = if (remainingOppositeTrack.nonEmpty) {
          (perfectTransferGroupCoeff * totalOppositeTrackMLength) - previousProcessedLength
        } else {
          (perfectTransferGroupCoeff * totalOppositeTrackMLength) - processedOppositeTrack.init.map(l => l.endMValue - l.startMValue).sum
        }
        val firstSplitLinkGeom = if (linkToBeSplit.sideCode == TowardsDigitizing) GeometryUtils.truncateGeometry2D(linkToBeSplit.geometry, 0.0, splitMValue)
        else GeometryUtils.truncateGeometry2D(linkToBeSplit.geometry, linkToBeSplit.endMValue - splitMValue, linkToBeSplit.endMValue)
        val (firstSplitStartMeasure, firstSplitEndMeasure) = if (linkToBeSplit.sideCode == TowardsDigitizing) (linkToBeSplit.startMValue, linkToBeSplit.startMValue + splitMValue) else
          (linkToBeSplit.endMValue - splitMValue, linkToBeSplit.endMValue)
        val (secondSplitStartMeasure, secondSplitEndMeasure) = if (linkToBeSplit.sideCode == TowardsDigitizing) (linkToBeSplit.startMValue + splitMValue, linkToBeSplit.endMValue) else
          (linkToBeSplit.startMValue, linkToBeSplit.endMValue - splitMValue)
        val secondLinkGeom = if (linkToBeSplit.sideCode == TowardsDigitizing) GeometryUtils.truncateGeometry2D(linkToBeSplit.geometry, splitMValue, linkToBeSplit.endMValue)
        else GeometryUtils.truncateGeometry2D(linkToBeSplit.geometry, 0.0, linkToBeSplit.endMValue - splitMValue)

        //  processedLinks without and with roadwayNumber
        val (unassignedRwnLinks, assignedRwnLinks) = oppositeToProcess.filterNot(_.eq(linkToBeSplit)).partition(_.roadwayNumber == NewIdValue)
        val nextRoadwayNumber = Sequences.nextRoadwayNumber

        // calculate startAddrMValue, when the link is not the 1st link of the roadway, address does not need to be adjusted
        val startAddrMValue = if (remainingReference.head._2.size > 1) {
          None
        } else {
          Some(remainingReference.head._2.head.track match {
            case r if r.value == Track.RightSide.value =>
              val strategy = TrackCalculatorContext.getStrategy(Seq(linkToBeSplit), remainingReference.head._2)
              strategy.getFixedAddress(linkToBeSplit, remainingReference.head._2.head)._1
            case _ =>
              val strategy = TrackCalculatorContext.getStrategy(remainingReference.head._2, Seq(linkToBeSplit))
              strategy.getFixedAddress(remainingReference.head._2.head, linkToBeSplit)._1
          })
        }

        val refAdjust: Long = if (remainingReference.head._2.last.status == Terminated || linkToBeSplit.startAddrMValue == remainingReference.head._2.head.startAddrMValue) {
          (linkToBeSplit.startAddrMValue - remainingReference.head._2.head.startAddrMValue) / 2
        } else 0

        //  calculate splitAddrMValue, must be the endAddrMValue at the reference point
        //  for terminated links, it's possible the address still needs to be adjusted
        val splitEndAddrM: Long = refAdjust + remainingReference.head._2.last.endAddrMValue

        //  calculate endAddrMValue, if it's the last link for the current roadway, it means the endAddr can be adjusted
        //  for terminated links, it's possible the address still needs to be adjusted
        val endAddrMValue = if (remainingOppositeTrack.nonEmpty && remainingOppositeTrack.tail.nonEmpty && remainingOppositeTrack.tail.head.roadwayNumber == linkToBeSplit.roadwayNumber) {
          None
        } else {
          Some(remainingReference.tail.head._2.last.track match {
            case r if r.value == Track.RightSide.value =>
              val strategy = TrackCalculatorContext.getStrategy(Seq(linkToBeSplit), remainingReference.tail.head._2)
              strategy.getFixedAddress(linkToBeSplit, remainingReference.tail.head._2.head)._2
            case _ =>
              val strategy = TrackCalculatorContext.getStrategy(remainingReference.tail.head._2, Seq(linkToBeSplit))
              strategy.getFixedAddress(remainingReference.tail.head._2.head, linkToBeSplit)._2
          })
        }

        logger.info(s"Project link to be split (1st half): (${linkToBeSplit.roadNumber}, ${linkToBeSplit.roadPartNumber}, ${linkToBeSplit.track})")
        logger.info(s"\tfrom: (${linkToBeSplit.originalStartAddrMValue} - ${linkToBeSplit.originalEndAddrMValue})")
        logger.info(s"\tto:  (${startAddrMValue.getOrElse(linkToBeSplit.startAddrMValue)} - $splitEndAddrM)")

        val processedOppositeTrackWithSplitLink = assignedRwnLinks ++ unassignedRwnLinks.map(_.copy(roadwayNumber = nextRoadwayNumber)) :+
          linkToBeSplit.copy(startMValue = firstSplitStartMeasure, endMValue = firstSplitEndMeasure, geometry = firstSplitLinkGeom, geometryLength = GeometryUtils.geometryLength(firstSplitLinkGeom),
            startAddrMValue = startAddrMValue.getOrElse(linkToBeSplit.startAddrMValue), endAddrMValue = splitEndAddrM, roadwayNumber = if (linkToBeSplit.status == LinkStatus.New || remainingReference.head._2.size == 1) nextRoadwayNumber else linkToBeSplit.roadwayNumber, connectedLinkId = Some(linkToBeSplit.linkId), discontinuity = Discontinuity.Continuous)

        logger.info(s"Project link for reference section (matching split): (${remainingReference.head._2.last.roadNumber}, ${remainingReference.head._2.last.roadPartNumber}, ${remainingReference.head._2.last.track})")
        logger.info(s"\tfrom: (${remainingReference.head._2.last.originalStartAddrMValue} - ${remainingReference.head._2.last.originalEndAddrMValue})")
        logger.info(s"\tto:  (${startAddrMValue.getOrElse(remainingReference.head._2.last.startAddrMValue)} - $splitEndAddrM)")

        val processedReferenceWithAdjustedSplitAddr = remainingReference.head._2.dropRight(1) :+
          remainingReference.head._2.last.copy(startAddrMValue = startAddrMValue.getOrElse(remainingReference.head._2.last.startAddrMValue), endAddrMValue = splitEndAddrM)

        logger.info(s"Project link to be split (2nd half): (${linkToBeSplit.roadNumber}, ${linkToBeSplit.roadPartNumber}, ${linkToBeSplit.track})")
        logger.info(s"\tfrom: (${linkToBeSplit.originalStartAddrMValue} - ${linkToBeSplit.originalEndAddrMValue}) ")
        logger.info(s"\tto:  ($splitEndAddrM - ${endAddrMValue.getOrElse(linkToBeSplit.endAddrMValue + (refAdjust * -1))})")

        val remainingOpposite =
          linkToBeSplit.copy(id = NewIdValue, startMValue = secondSplitStartMeasure, endMValue = secondSplitEndMeasure, geometry = secondLinkGeom, geometryLength = GeometryUtils.geometryLength(secondLinkGeom),
            startAddrMValue = splitEndAddrM, endAddrMValue = endAddrMValue.getOrElse(linkToBeSplit.endAddrMValue + (refAdjust * -1))) +:
            remainingOppositeTrack.drop(1).map { pl =>
              pl.copy(startAddrMValue = pl.startAddrMValue + (refAdjust * -1), endAddrMValue = pl.endAddrMValue + (refAdjust * -1))
            }

        logger.info(s"Next Project link for reference section: (${remainingReference.tail.head._2.head.roadNumber}, ${remainingReference.tail.head._2.head.roadPartNumber}, ${remainingReference.tail.head._2.head.track})")
        logger.info(s"\tfrom: (${remainingReference.tail.head._2.head.originalStartAddrMValue} - ${remainingReference.tail.head._2.head.originalEndAddrMValue})")
        logger.info(s"\tto:  ($splitEndAddrM - ${endAddrMValue.getOrElse(remainingReference.tail.head._2.head.endAddrMValue + refAdjust)})")

        val remainingAdjustedLinks: Seq[ProjectLink] = remainingReference.tail.head._2.head.copy(startAddrMValue = splitEndAddrM, endAddrMValue = endAddrMValue.getOrElse(remainingReference.tail.head._2.head.endAddrMValue + refAdjust)) +:
          (remainingReference.tail.head._2.tail ++ remainingReference.tail.tail.flatMap(_._2).toSeq)
        val remainingAdjusted = ListMap(remainingAdjustedLinks.groupBy(_.roadwayNumber).toSeq.sortBy(r => r._2.minBy(_.startAddrMValue).startAddrMValue): _*)

        splitLinksIfNeed(
          remainingReference = remainingAdjusted,
          processedReference = processedReference ++ processedReferenceWithAdjustedSplitAddr,
          remainingOppositeTrack = remainingOpposite,
          processedOppositeTrack = processedOppositeTrackWithSplitLink,
          totalReferenceMLength, totalOppositeTrackMLength, missingRoadwayNumbers - 1)
      }
    }
  }
}

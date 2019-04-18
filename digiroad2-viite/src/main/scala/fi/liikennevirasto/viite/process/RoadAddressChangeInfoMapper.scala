package fi.liikennevirasto.viite.process

import fi.liikennevirasto.digiroad2.Point
import fi.liikennevirasto.digiroad2.client.vvh.ChangeType._
import fi.liikennevirasto.digiroad2.client.vvh.ChangeInfo
import org.slf4j.LoggerFactory


object RoadAddressChangeInfoMapper extends RoadAddressMapper {
  private val logger = LoggerFactory.getLogger(getClass)

  private def isLengthChange(ci: ChangeInfo) = {
    Set(LengthenedCommonPart.value, LengthenedNewPart.value, ShortenedCommonPart.value, ShortenedRemovedPart.value).contains(ci.changeType.value)
  }

  private def max(doubles: Double*) = {
    doubles.max
  }

  private def min(doubles: Double*) = {
    doubles.min
  }

  private def fuseLengthChanges(sources: Seq[ChangeInfo]) = {
    val (lengthened, rest) = sources.partition(ci => ci.changeType.value == LengthenedCommonPart.value ||
      ci.changeType.value == LengthenedNewPart.value)
    val (shortened, others) = rest.partition(ci => ci.changeType.value == ShortenedRemovedPart.value ||
      ci.changeType.value == ShortenedCommonPart.value)
    others ++
      lengthened.groupBy(ci => (ci.newId, ci.vvhTimeStamp)).mapValues { s =>
        val common = s.find(_.changeType.value == LengthenedCommonPart.value)
        val added = s.find(st => st.changeType.value == LengthenedNewPart.value)
        (common, added) match {
          case (Some(c), Some(a)) =>
            val (expStart, expEnd) = if (c.newStartMeasure.get > c.newEndMeasure.get)
              (max(c.newStartMeasure.get, a.newStartMeasure.get, a.newEndMeasure.get), min(c.newEndMeasure.get, a.newStartMeasure.get, a.newEndMeasure.get))
            else
              (min(c.newStartMeasure.get, a.newStartMeasure.get, a.newEndMeasure.get), max(c.newEndMeasure.get, a.newEndMeasure.get, a.newStartMeasure.get))
            Some(c.copy(newStartMeasure = Some(expStart), newEndMeasure = Some(expEnd)))
          case _ => None
        }
      }.values.flatten.toSeq ++
      shortened.groupBy(ci => (ci.oldId, ci.vvhTimeStamp)).mapValues { s =>
        val common = s.find(_.changeType.value == ShortenedCommonPart.value)
        val toRemove = s.filter(_.changeType.value == ShortenedRemovedPart.value)
        val fusedRemove = if (toRemove.lengthCompare(0) > 0) {
          Some(toRemove.head.copy(oldStartMeasure = toRemove.minBy(_.oldStartMeasure).oldStartMeasure, oldEndMeasure = toRemove.maxBy(_.oldEndMeasure).oldEndMeasure))
        } else None
        (common, fusedRemove) match {
          case (Some(c), Some(r)) =>
            val (expStart, expEnd) = if (c.oldStartMeasure.get > c.oldEndMeasure.get)
              (max(c.oldStartMeasure.get, r.oldStartMeasure.get, r.oldEndMeasure.get), min(c.oldEndMeasure.get, c.newStartMeasure.get, r.oldEndMeasure.get))
            else
              (min(c.oldStartMeasure.get, r.oldStartMeasure.get, r.oldEndMeasure.get), max(c.oldEndMeasure.get, r.oldEndMeasure.get, r.oldStartMeasure.get))
            Some(c.copy(oldStartMeasure = Some(expStart), oldEndMeasure = Some(expEnd)))
          case _ => None
        }
      }.values.flatten.toSeq
  }

//  private def createAddressMap(sources: Seq[ChangeInfo]): Seq[LinearLocationMapping] = {
//    val pseudoGeom = Seq(Point(0.0, 0.0), Point(1.0, 0.0))
//    fuseLengthChanges(sources).map(ci => {
//      ci.changeType match {
//        case CombinedModifiedPart | CombinedRemovedPart | DividedModifiedPart | DividedNewPart =>
//          logger.debug("Change info> oldId: " + ci.oldId + " newId: " + ci.newId + " changeType: " + ci.changeType)
//          Some(LinearLocationMapping(ci.oldId.get, ci.newId.get, 0, ci.oldStartMeasure.get, ci.oldEndMeasure.get,
//            ci.newStartMeasure.get, ci.newEndMeasure.get, pseudoGeom, pseudoGeom, Some(ci.vvhTimeStamp)))
//        case LengthenedCommonPart | LengthenedNewPart | ShortenedCommonPart | ShortenedRemovedPart =>
//          logger.debug("Change info, length change > oldId: " + ci.oldId + " newId: " + ci.newId + " changeType: " + ci.changeType + s" $ci")
//          Some(LinearLocationMapping(ci.oldId.get, ci.newId.get, 0, ci.oldStartMeasure.get, ci.oldEndMeasure.get,
//            ci.newStartMeasure.get, ci.newEndMeasure.get, pseudoGeom, pseudoGeom, Some(ci.vvhTimeStamp)))
//        case _ => None
//      }
//    }).filter(c => c.isDefined).map(_.get)
//  }

//  private def applyChanges(changes: Seq[Seq[ChangeInfo]], roadAddresses: Map[(Long, Long), Seq[RoadAddress]]): Map[(Long, Long), Seq[RoadAddress]] = {
//    changes.foldLeft(roadAddresses) { case (addresses, changeInfo) =>
//      val (toFloat, other) = changeInfo.partition(isFloatingChange)
//      val (length, maps) = other.partition(isLengthChange)
//      val changeOperations: Seq[Map[(Long, Long), Seq[RoadAddress]] => Map[(Long, Long), Seq[RoadAddress]]] = Seq(
//        applyFloating(toFloat),
//        applyMappedChanges(maps),
//        applyLengthChanges(length)
//      )
//      changeOperations.foldLeft(addresses) { case (addrMap, op) => op(addrMap) }
//    }
//  }
//
//  private def mapAddress(mapping: Seq[LinearLocationMapping], allRoadAddresses: Seq[LinearLocation])(ra: RoadAddress) = {
//    if (!ra.isFloating && mapping.exists(_.matches(ra, allRoadAddresses))) {
//      val changeVVHTimestamp = mapping.head.vvhTimeStamp.get
//      mapRoadAddresses(mapping, allRoadAddresses)(ra).map(_.copy(adjustedTimestamp = changeVVHTimestamp))
//    } else
//      Seq(ra)
//  }

//  private def applyMappedChanges(changes: Seq[ChangeInfo])(roadAddresses: Map[(Long, Long), Seq[RoadAddress]]): Map[(Long, Long), Seq[RoadAddress]] = {
//    if (changes.isEmpty)
//      roadAddresses
//    else {
//      val mapping = createAddressMap(changes)
//      val mapped = roadAddresses.mapValues(_.flatMap(mapAddress(mapping, roadAddresses.values.flatten.toSeq)))
//      mapped.values.toSeq.flatten.groupBy(m => (m.linkId, m.roadwayNumber))
//    }
//  }

//  private def applyLengthChanges(changes: Seq[ChangeInfo])(roadAddresses: Map[(Long, Long), Seq[LinearLocation]]): Map[(Long, Long), Seq[LinearLocation]] = {
//    if (changes.isEmpty)
//      roadAddresses
//    else {
//      val mapping = createAddressMap(changes)
//      val mapped = roadAddresses.mapValues(_.flatMap(ra =>
//        // If change is not within maximum allowed then float the address
//        if (mapping.exists(m => m.matches(ra, roadAddresses.values.flatten.toSeq) && Math.abs(m.sourceLen - m.targetLen) > fi.liikennevirasto.viite.MaxLengthChange)) {
//          Seq(ra.copy(floating = FloatingReason.ApplyChanges))
//        } else
//          mapAddress(mapping, roadAddresses.values.flatten.toSeq)(ra)
//      ))
//      mapped.values.toSeq.flatten.groupBy(m => (m.linkId, m.roadwayNumber))
//    }
//  }
//
//  private def applyFloating(changes: Seq[ChangeInfo])(roadAddresses: Map[(Long, Long), Seq[LinearLocation]]): Map[(Long, Long), Seq[LinearLocation]] = {
//    if (changes.isEmpty)
//      roadAddresses
//    else {
//      val mapped = roadAddresses.mapValues(_.map(ra =>
//        if (changes.exists(c => c.oldId.contains(ra.linkId) && c.vvhTimeStamp > ra.adjustedTimestamp)) {
//          ra.copy(floating = FloatingReason.ApplyChanges)
//        } else
//          ra
//      ))
//      mapped.values.toSeq.flatten.groupBy(m => (m.linkId, m.roadwayNumber))
//    }
//  }
//
//  override def calculateMeasures(ra: RoadAddress, adjMap: LinearLocationMapping): (Double, Double) = {
//    val coef = adjMap.targetLen / adjMap.sourceLen
//    val (sourceStartM, sourceEndM) = (Math.min(adjMap.sourceStartM, adjMap.sourceEndM), Math.max(adjMap.sourceStartM, adjMap.sourceEndM))
//    val (targetStartM, targetEndM) = (Math.min(adjMap.targetEndM, adjMap.targetStartM), Math.max(adjMap.targetEndM, adjMap.targetStartM))
//    val startM = if ((ra.startMValue - sourceStartM) > MinAllowedRoadAddressLength) {
//      targetStartM + ra.startMValue * coef
//    } else {
//      targetStartM
//    }
//    val endM = if ((sourceEndM - ra.endMValue) > MinAllowedRoadAddressLength) {
//      targetStartM + ra.endMValue * coef
//    } else {
//      targetEndM
//    }
//    (startM, endM)
//  }

//  def resolveChangesToMap(roadAddresses: Map[(Long, Long), LinkRoadAddressHistory], changes: Seq[ChangeInfo]): Map[Long, LinkRoadAddressHistory] = {
//    val current = roadAddresses.flatMap(_._2.currentSegments).toSeq
//    val history = roadAddresses.flatMap(_._2.historySegments).toSeq
//    val currentSections = partition(current)
//    val historySections = partition(history)
//    val (originalCurrentSections, originalHistorySections) = groupByRoadSections(currentSections, historySections, roadAddresses.values)
//    preTransferCheckBySection(originalCurrentSections)
//    val groupedChanges = changes.groupBy(_.vvhTimeStamp).values.toSeq
//    val appliedChanges = applyChanges(groupedChanges.sortBy(_.head.vvhTimeStamp), roadAddresses.mapValues(_.allSegments))
//    val mappedChanges = appliedChanges.values.map(s => LinkRoadAddressHistory(s.partition(_.endDate.isEmpty)))
//    val (changedCurrentSections, changedHistorySections) = groupByRoadSections(currentSections, historySections, mappedChanges)
//    val (resultCurr, resultHist) = postTransferCheckBySection(changedCurrentSections, changedHistorySections, originalCurrentSections, originalHistorySections)
//    (resultCurr.values ++ resultHist.values).flatMap(_.flatMap(_.allSegments)).groupBy(_.linkId).mapValues(s => LinkRoadAddressHistory(s.toSeq.partition(_.endDate.isEmpty)))
//  }
//
//  private def groupByRoadSections(currentSections: Seq[RoadwaySection], historySections: Seq[RoadwaySection], roadAddresses: Iterable[LinkRoadAddressHistory]): (Map[RoadwaySection, Seq[LinkRoadAddressHistory]], Map[RoadwaySection, Seq[LinkRoadAddressHistory]]) = {
//
//    val mappedCurrent = currentSections.map(section => section -> roadAddresses.filter(lh => lh.currentSegments.exists(section.includes)).map {
//      l => LinkRoadAddressHistory((l.currentSegments, Seq()))
//    }.toSeq).toMap
//
//    val mappedHistory = historySections.map(section => section -> roadAddresses.filter(lh => lh.historySegments.exists(section.includes)).map {
//      l => LinkRoadAddressHistory((Seq(), l.historySegments))
//    }.toSeq).toMap
//
//    (mappedCurrent, mappedHistory)
//  }
//
//  // TODO: Don't try to apply changes to invalid sections
//  private def preTransferCheckBySection(sections: Map[RoadwaySection, Seq[LinkRoadAddressHistory]]) = {
//    sections.map(_._2.flatMap(_.currentSegments)).map(seq =>
//      try {
//        preTransferChecks(seq)
//        true
//      } catch {
//        case ex: InvalidAddressDataException =>
//          logger.info(s"Section had invalid road data ${seq.head.roadNumber}/${seq.head.roadPartNumber}: ${ex.getMessage}")
//          false
//      })
//  }
//
//  private def postTransferCheckBySection(currentSections: Map[RoadwaySection, Seq[LinkRoadAddressHistory]], historySections: Map[RoadwaySection, Seq[LinkRoadAddressHistory]],
//                                         original: Map[RoadwaySection, Seq[LinkRoadAddressHistory]], history: Map[RoadwaySection, Seq[LinkRoadAddressHistory]]): (Map[RoadwaySection, Seq[LinkRoadAddressHistory]], Map[RoadwaySection, Seq[LinkRoadAddressHistory]]) = {
//    val curr = currentSections.map(s =>
//      try {
//        postTransferChecksForCurrent(s)
//        s
//      } catch {
//        case ex: InvalidAddressDataException =>
//          logger.info(s"Invalid address data after transfer on ${s._1}, not applying changes (${ex.getMessage})")
//          s._1 -> original(s._1)
//      }
//    )
//    val hist = historySections.map(s =>
//      try {
//        postTransferChecksForHistory(s)
//        s
//      } catch {
//        case ex: InvalidAddressDataException =>
//          logger.info(s"Invalid history address data after transfer on ${s._1}, not applying changes (${ex.getMessage})")
//          s._1 -> history(s._1)
//      }
//    )
//    (curr, hist)
//
//  }


}

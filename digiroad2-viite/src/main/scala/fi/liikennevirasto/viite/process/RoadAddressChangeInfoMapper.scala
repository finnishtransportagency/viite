package fi.liikennevirasto.viite.process

import fi.liikennevirasto.digiroad2.asset.SideCode
import fi.liikennevirasto.digiroad2.{GeometryUtils, Point}
import fi.liikennevirasto.digiroad2.client.vvh.ChangeType._
import fi.liikennevirasto.digiroad2.client.vvh.{ChangeInfo, ChangeType, VVHClient}
import fi.liikennevirasto.digiroad2.linearasset.RoadLinkLike
import fi.liikennevirasto.viite.dao.FloatingReason.NoFloating
import fi.liikennevirasto.viite.{LinkRoadAddressHistory, MinAllowedRoadAddressLength, RoadType, switchSideCode}
import fi.liikennevirasto.viite.dao.{FloatingReason, LinearLocation, RoadAddress}
import fi.liikennevirasto.viite.process.RoadAddressFiller.ChangeSet
import org.slf4j.LoggerFactory

//TODO The all class and specs can be deleted after VIITE-1537
trait BaseRoadAddressMapper {

  //TODO this is no longer needed
  //  /** Used when road address span is larger than mapping: road address must be split into smaller parts
  //    *
  //    * @param roadAddress Road address to split
  //    * @param mapping     Mapping entry that may or may not have smaller or larger span than road address
  //    * @return A pair of address start and address end values this mapping and road address applies to
  //    */
  //  private def splitRoadAddressValues(roadAddress: RoadAddress, mapping: LinearLocationMapping): (Long, Long) = {
  //    if (withinTolerance(roadAddress.startMValue, mapping.sourceStartM) && withinTolerance(roadAddress.endMValue, mapping.sourceEndM)) {
  //      (roadAddress.startAddrMValue, roadAddress.endAddrMValue)
  //    } else {
  //      val (startM, endM) =
  //        if (Math.abs((roadAddress.endMValue - roadAddress.startMValue) - (mapping.sourceEndM - mapping.sourceStartM)) <= MaxAllowedMValueError)
  //          (roadAddress.startMValue, roadAddress.endMValue)
  //        else
  //          (mapping.sourceStartM, mapping.sourceEndM)
  //
  //      val (startAddrM, endAddrM) = roadAddress.addressBetween(startM, endM)
  //      (Math.max(startAddrM, roadAddress.startAddrMValue), Math.min(endAddrM, roadAddress.endAddrMValue))
  //    }
  //  }

  /**
    * Partitioning for transfer checks. Stops at calibration points, changes of road part etc.
    *
    * @param roadAddresses
    * @return
    */
  protected def partition(roadAddresses: Iterable[RoadAddress]): Seq[RoadwaySection] = {
    def combineTwo(r1: RoadAddress, r2: RoadAddress): Seq[RoadAddress] = {
      if (r1.endAddrMValue == r2.startAddrMValue && r1.endCalibrationPoint.isEmpty)
        Seq(r1.copy(discontinuity = r2.discontinuity, endAddrMValue = r2.endAddrMValue))
      else
        Seq(r2, r1)
    }
    def combine(roadAddressSeq: Seq[RoadAddress], result: Seq[RoadAddress] = Seq()): Seq[RoadAddress] = {
      if (roadAddressSeq.isEmpty)
        result.reverse
      else if (result.isEmpty)
        combine(roadAddressSeq.tail, Seq(roadAddressSeq.head))
      else
        combine(roadAddressSeq.tail, combineTwo(result.head, roadAddressSeq.head) ++ result.tail)
    }
    val grouped = roadAddresses.groupBy(ra => (ra.roadNumber, ra.roadPartNumber, ra.track, ra.roadwayNumber))
    grouped.mapValues(v => combine(v.toSeq.sortBy(_.startAddrMValue))).values.flatten.map(ra =>
      RoadwaySection(ra.roadNumber, ra.roadPartNumber, ra.roadPartNumber,
        ra.track, ra.startAddrMValue, ra.endAddrMValue, ra.discontinuity, RoadType.Unknown, ra.ely, ra.reversed, ra.roadwayNumber)
    ).toSeq
  }

  def withinTolerance(mValue1: Double, mValue2: Double) = {
    Math.abs(mValue1 - mValue2) < MinAllowedRoadAddressLength
  }

  def isDirectionMatch(r: LinearLocationMapping): Boolean = {
    ((r.sourceStartM - r.sourceEndM) * (r.targetStartM - r.targetEndM)) > 0
  }

  def calculateMeasures(linearLocation: LinearLocation, adjMap: LinearLocationMapping): (Double, Double) = {
    val coef = adjMap.targetLen / adjMap.sourceLen
    val (sourceStartM, sourceEndM) = (Math.min(adjMap.sourceStartM, adjMap.sourceEndM), Math.max(adjMap.sourceStartM, adjMap.sourceEndM))
    val (targetStartM, targetEndM) = (Math.min(adjMap.targetEndM, adjMap.targetStartM), Math.max(adjMap.targetEndM, adjMap.targetStartM))
    val startM = if ((linearLocation.startMValue - sourceStartM) > MinAllowedRoadAddressLength) {
      targetStartM + linearLocation.startMValue * coef
    } else {
      targetStartM
    }
    val endM = if ((sourceEndM - linearLocation.endMValue) > MinAllowedRoadAddressLength) {
      targetStartM + linearLocation.endMValue * coef
    } else {
      targetEndM
    }
    (startM, endM)
  }

  def mapRoadAddresses(linearLocationMapping: Seq[LinearLocationMapping], allLinerLocations : Seq[LinearLocation])(linearLocation: LinearLocation): Seq[LinearLocation] = {
    //Find the linear locations that should be applied here

    linearLocationMapping.filter(_.matches(linearLocation, allLinerLocations)).map(adjMap => {

      val (sideCode, mappedGeom) =
        if (isDirectionMatch(adjMap)) {
          (linearLocation.sideCode, truncateGeometriesWithAddressValues(linearLocation, adjMap))
        } else {
          (switchSideCode(linearLocation.sideCode), truncateGeometriesWithAddressValues(linearLocation, adjMap).reverse)
        }

      val (startM, endM) = calculateMeasures(linearLocation, adjMap)

      //      val startCP = linearLocation.startCalibrationPoint match {
      //        case None => None
      //        case Some(cp) => if (cp.addressMValue == mappedStartAddrM) Some(cp.copy(linkId = adjMap.targetLinkId,
      //          segmentMValue = if (sideCode == SideCode.AgainstDigitizing) endM - startM else 0.0)) else None
      //      }
      //      val endCP = ra.endCalibrationPoint match {
      //        case None => None
      //        case Some(cp) => if (cp.addressMValue == mappedEndAddrM) Some(cp.copy(linkId = adjMap.targetLinkId,
      //          segmentMValue = if (sideCode == SideCode.TowardsDigitizing) endM - startM else 0.0)) else None
      //      }
      //TODO just missing the order number after changes
      linearLocation.copy(id = -1000/*NewLinearLocation*/, linkId = adjMap.targetLinkId,
        startMValue = startM, endMValue = endM, sideCode = sideCode, adjustedTimestamp = VVHClient.createVVHTimeStamp(),
        floating = NoFloating, geometry = if(mappedGeom.isEmpty) linearLocation.geometry else mappedGeom)
    })
  }

  private def truncateGeometriesWithAddressValues(linearLocation: LinearLocation, mapping: LinearLocationMapping): Seq[Point] = {
    def truncate(geometry: Seq[Point], d1: Double, d2: Double) = {
      // When operating with fake geometries (automatic change tables) the geometry may not have correct length
      val startM = Math.min(Math.max(Math.min(d1, d2), 0.0), GeometryUtils.geometryLength(geometry))
      val endM = Math.min(Math.max(d1, d2), GeometryUtils.geometryLength(geometry))
      GeometryUtils.truncateGeometry3D(geometry, startM, endM)
    }

    if (withinTolerance(linearLocation.startMValue, mapping.sourceStartM) && withinTolerance(linearLocation.endMValue, mapping.sourceEndM))
      truncate(linearLocation.geometry, linearLocation.startMValue, linearLocation.endMValue )
    else if(mapping.sourceLinkId == mapping.targetLinkId) {
      truncate(linearLocation.geometry, mapping.targetStartM, mapping.targetEndM)
    }
    else{
      val (startM, endM) = if (Math.abs((linearLocation.endMValue - linearLocation.startMValue) - (mapping.sourceEndM - mapping.sourceStartM)) <= 0.001 /*MaxAllowedMValueError*/)
        (linearLocation.startMValue, linearLocation.endMValue)
      else
        (mapping.sourceStartM, mapping.sourceEndM)

      truncate(linearLocation.geometry, startM, endM )
    }
  }
}

object RoadAddressChangeInfoMapper extends RoadAddressMapper {
  private val logger = LoggerFactory.getLogger(getClass)

  private def isLengthChange(ci: ChangeInfo) = {
    Set(LengthenedCommonPart.value, LengthenedNewPart.value, ShortenedCommonPart.value, ShortenedRemovedPart.value).contains(ci.changeType.value)
  }

  private def isFloatingChange(ci: ChangeInfo) = {
    Set(Removed.value, ReplacedCommonPart.value, ReplacedNewPart.value, ReplacedRemovedPart.value).contains(ci.changeType.value)
  }

  private def max(doubles: Double*) = {
    doubles.max
  }

  private def min(doubles: Double*) = {
    doubles.min
  }

  private def fuseLengthChanges(sources: Seq[ChangeInfo]) = {
    val (lengthened, rest) = sources.partition(ci => ci.changeType == LengthenedCommonPart.value ||
      ci.changeType == LengthenedNewPart.value)
    val (shortened, others) = rest.partition(ci => ci.changeType == ShortenedRemovedPart.value ||
      ci.changeType == ShortenedCommonPart.value)
    others ++
      lengthened.groupBy(ci => (ci.newId, ci.vvhTimeStamp)).mapValues { s =>
        val common = s.find(_.changeType == LengthenedCommonPart.value)
        val added = s.find(st => st.changeType == LengthenedNewPart.value)
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
        val common = s.find(_.changeType == ShortenedCommonPart.value)
        val toRemove = s.filter(_.changeType == ShortenedRemovedPart.value)
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

  private def createAddressMap(sources: Seq[ChangeInfo]): Seq[LinearLocationMapping] = {
    val pseudoGeom = Seq(Point(0.0, 0.0), Point(1.0, 0.0))
    fuseLengthChanges(sources).map(ci => {
      ci.changeType match {
        case CombinedModifiedPart | CombinedRemovedPart | DividedModifiedPart | DividedNewPart =>
          logger.debug("Change info> oldId: " + ci.oldId + " newId: " + ci.newId + " changeType: " + ci.changeType)
          Some(LinearLocationMapping(ci.oldId.get, ci.newId.get, 0, ci.oldStartMeasure.get, ci.oldEndMeasure.get,
            ci.newStartMeasure.get, ci.newEndMeasure.get, pseudoGeom, pseudoGeom, Some(ci.vvhTimeStamp)))
        case LengthenedCommonPart | LengthenedNewPart | ShortenedCommonPart | ShortenedRemovedPart =>
          logger.debug("Change info, length change > oldId: " + ci.oldId + " newId: " + ci.newId + " changeType: " + ci.changeType + s" $ci")
          Some(LinearLocationMapping(ci.oldId.get, ci.newId.get, 0, ci.oldStartMeasure.get, ci.oldEndMeasure.get,
            ci.newStartMeasure.get, ci.newEndMeasure.get, pseudoGeom, pseudoGeom, Some(ci.vvhTimeStamp)))
        case _ => None
      }
    }).filter(c => c.isDefined).map(_.get)
  }

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

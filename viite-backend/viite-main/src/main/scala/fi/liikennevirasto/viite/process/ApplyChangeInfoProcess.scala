package fi.liikennevirasto.viite.process

import fi.liikennevirasto.digiroad2.client.kgv.{ChangeInfo, ChangeType}
import fi.liikennevirasto.digiroad2.client.kgv.ChangeType._
import fi.liikennevirasto.viite._
import fi.liikennevirasto.viite.dao.{CalibrationPointReference, LinearLocation}
import fi.liikennevirasto.viite.process.RoadAddressFiller.ChangeSet
import fi.vaylavirasto.viite.geometry.GeometryUtils
import fi.vaylavirasto.viite.model.{RoadLinkLike, SideCode}
import org.slf4j.LoggerFactory

object ApplyChangeInfoProcess {

  private val logger = LoggerFactory.getLogger(getClass)

  private case class Projection(oldLinkId: String, newLinkId: String, oldStart: Double, oldEnd: Double, newStart: Double, newEnd: Double, timeStamp: Long, orderIncrement: Int = 0) {
    /**
      * Check if the given measure is equal the old start measure with {MaxAllowedMValueError} error margin allowed
      *
      * @param measure The measure to be compared with old start measure
      * @return Returns true when the given measure match the old start measure
      */
    def oldStartMeasureMatch(measure: Double): Boolean = {
      oldStart - MaxAllowedMValueError <= measure && oldStart + MaxAllowedMValueError >= measure
    }

    /**
      * Check if the given measure is equal the old end measure with {MaxAllowedMValueError} error margin allowed
      *
      * @param measure The measure to be compared with old end measure
      * @return Returns true when the given measure match the old end measure
      */
    def oldEndMeasureMatch(measure: Double): Boolean = {
      oldEnd - MaxAllowedMValueError <= measure && oldEnd + MaxAllowedMValueError >= measure
    }

    /**
      * Check if the linear location is intercepts the projection
      *
      * @param linearLocation
      * @return
      */
    def intercepts(linearLocation: LinearLocation): Boolean = {
      linearLocation.linkId == oldLinkId && !(linearLocation.endMValue <= Math.min(oldStart, oldEnd) || linearLocation.startMValue >= Math.max(oldStart, oldEnd))
    }
  }

  private def nonSupportedChange(change: ChangeInfo): Boolean = !isSupportedChange(change)

  private def isSupportedChange(change: ChangeInfo): Boolean = {
    Seq(
      CombinedModifiedPart, CombinedRemovedPart,
      LengthenedCommonPart, LengthenedNewPart,
      DividedModifiedPart, DividedNewPart,
      ShortenedCommonPart, ShortenedRemovedPart
    ).contains(change.changeType)
  }

  private def isDirectionChangeProjection(projection: Projection): Boolean = {
    ((projection.oldEnd - projection.oldStart) * (projection.newEnd - projection.newStart)) < 0
  }

  private def calculateNewMValuesAndSideCode(linearLocation: LinearLocation, projection: Projection): (Double, Double, SideCode) = {
    val oldLength = Math.abs(projection.oldEnd - projection.oldStart)
    val newLength = Math.abs(projection.newEnd - projection.newStart)
    val maxNewMeasure = Math.max(projection.newStart, projection.newEnd)

    // Test if the direction has changed -> side code will be also affected
    val (newStartMeasure, newEndMeasure, _) = if (isDirectionChangeProjection(projection)) {
      val newStart = projection.newStart - (linearLocation.endMValue - projection.oldStart) * Math.abs(newLength / oldLength)
      val newEnd = projection.newEnd - (linearLocation.startMValue - projection.oldEnd) * Math.abs(newLength / oldLength)
      (newStart, newEnd, SideCode.switch(linearLocation.sideCode))
    } else {
      val newStart = projection.newStart + (linearLocation.startMValue - projection.oldStart) * Math.abs(newLength / oldLength)
      val newEnd = projection.newEnd + (linearLocation.endMValue - projection.oldEnd) * Math.abs(newLength / oldLength)
      (newStart, newEnd, linearLocation.sideCode)
    }

    (Math.min(maxNewMeasure, Math.max(0.0, newStartMeasure)), Math.max(0.0, Math.min(maxNewMeasure, newEndMeasure)), linearLocation.sideCode)
  }

  private def validateLinearLocation(originalLinearLocation: LinearLocation, adjustedLinearLocations: Seq[LinearLocation], mappedRoadLinks: Map[String, RoadLinkLike]) = {

    def checkChangedLength(originalLinearLocation: LinearLocation, adjustedLinearLocations: Seq[LinearLocation]): Boolean = {
      val oldLength = originalLinearLocation.endMValue - originalLinearLocation.startMValue
      val newLength = adjustedLinearLocations.map(linearLocation => linearLocation.endMValue - linearLocation.startMValue).sum

      Math.abs(oldLength - newLength) < MaxAdjustmentRange
    }

    def checkExistingRoadLink(mappedRoadLinks: Map[String, RoadLinkLike])(originalLinearLocation: LinearLocation, adjustedLinearLocations: Seq[LinearLocation]): Boolean = {
      adjustedLinearLocations.forall(linearLocation => mappedRoadLinks.contains(linearLocation.linkId))
    }

    val filterOperations = Seq[(LinearLocation, Seq[LinearLocation]) => Boolean](
      checkChangedLength,
      checkExistingRoadLink(mappedRoadLinks)
    )

    filterOperations.forall(operation => operation(originalLinearLocation, adjustedLinearLocations))
  }

  private def projectLinearLocation(linearLocation: LinearLocation, projections: Seq[Projection], changeSet: ChangeSet, mappedRoadLinks: Map[String, RoadLinkLike]): (Seq[LinearLocation], ChangeSet) = {

    val applicableProjections = projections.filter(_.timeStamp > linearLocation.adjustedTimestamp).filter(_.intercepts(linearLocation))

    applicableProjections match {
      case Seq() =>
        (Seq(linearLocation), changeSet)
      case _ =>

        //Group te changes by created timestamp to support multiple days execution for the same road link identifier.
        //VVH change api doesn't seems to support multiple changes for the same day in the same link id
        val linearLocations = applicableProjections.groupBy(_.timeStamp).toSeq.sortBy(_._1).foldLeft(Seq(linearLocation)) {
          case (adjustedLinearLocations, (_, groupedProjections)) =>
            val (news, existing) = adjustedLinearLocations.partition(_.id == NewIdValue)
            groupedProjections.flatMap {
              projection =>
                existing.filter(projection.intercepts).map(l => projectLinearLocation(l, projection, mappedRoadLinks))
            } ++ news
        }

        if (validateLinearLocation(linearLocation, linearLocations, mappedRoadLinks)) {
          val resultChangeSet = changeSet.copy(newLinearLocations = changeSet.newLinearLocations ++ linearLocations, droppedSegmentIds = changeSet.droppedSegmentIds + linearLocation.id)

          (linearLocations, resultChangeSet)
        } else {
          (Seq(linearLocation), changeSet)
        }
    }
  }

  private def projectLinearLocation(linearLocation: LinearLocation, projection: Projection, mappedRoadLinks: Map[String, RoadLinkLike]) = {

    def decimalPlaces(number: Double, d: Int = 10): Int = {
      if ((number * d).toLong % 10 == 0) d else decimalPlaces(number, d * 10)
    }

    val (newStartMeasure, newEndMeasure, newSideCode) = calculateNewMValuesAndSideCode(linearLocation, projection)

    val (startCalibrationPoint, endCalibrationPoint) = linearLocation.calibrationPoints

    val newStartCalibrationPoint = startCalibrationPoint.addrM match {
      case Some(_) if projection.oldStartMeasureMatch(linearLocation.startMValue) => startCalibrationPoint
      case _ => CalibrationPointReference.None
    }

    val newEndCalibrationPoint = endCalibrationPoint.addrM match {
      case Some(_) if projection.oldEndMeasureMatch(linearLocation.endMValue) => endCalibrationPoint
      case _ => CalibrationPointReference.None
    }

    //TODO check if it's a good idea to generate here the database identifier
    //PROS: Then we are using the right identifier on the next fill topology adjustments
    val newId = if (projection.oldLinkId == projection.newLinkId) {
      linearLocation.id
    } else {
      NewIdValue
    }

    val geometry = mappedRoadLinks.get(projection.newLinkId).map(
      roadLink => GeometryUtils.truncateGeometry2D(roadLink.geometry, newStartMeasure, newEndMeasure)
    ).getOrElse(linearLocation.geometry)

    linearLocation.copy(id = newId, orderNumber = linearLocation.orderNumber + (projection.orderIncrement.toDouble / decimalPlaces(linearLocation.orderNumber)),
      linkId = projection.newLinkId, startMValue = newStartMeasure, endMValue = newEndMeasure, sideCode = newSideCode, adjustedTimestamp = projection.timeStamp,
      calibrationPoints = (newStartCalibrationPoint, newEndCalibrationPoint), geometry = geometry)
  }

  private def filterOutOlderChanges(locations: Map[String, Seq[LinearLocation]])(change: ChangeInfo): Boolean = {
    //TODO check how to act when we have one totally newId (does not exist in current linear location) from changeInfo that doesnt have oldId associated
    val changeLocations = locations.getOrElse(change.oldId.getOrElse(change.newId.get), Seq())
    if (changeLocations.isEmpty) {
      false
    } else {
      val oldestLinearLocationTimestamp = changeLocations.map(_.adjustedTimestamp).min
      change.timeStamp > oldestLinearLocationTimestamp
    }
  }

  private def filterOutChangesWithoutLinkIds(change: ChangeInfo): Boolean = {
    change.newId.nonEmpty || change.oldId.nonEmpty
  }

  private def generateDividedProjections(dividedChanges: Seq[ChangeInfo]): Seq[Projection] = {
    //TODO we can also take always the min date on the divided changes and apply that one then the find floating will set those to floatings
    //VVH change api doesn't seems to support multiple changes for the same day in the same link id.
    dividedChanges.groupBy(ch => ch.timeStamp).flatMap {
      case (_, groupedChanges) =>
        val orderIncrements = 0 to groupedChanges.size
        groupedChanges.sortBy(_.oldStartMeasure).zip(orderIncrements).flatMap {
          case (change, orderIncrement) =>
            logger.debug("Change info, oldId: " + change.oldId + " newId: " + change.newId + " changeType: " + change.changeType)
            Some(Projection(change.oldId.get, change.newId.get, change.oldStartMeasure.get, change.oldEndMeasure.get, change.newStartMeasure.get, change.newEndMeasure.get, change.timeStamp, orderIncrement))
        }
    }.toSeq
  }

  private def generateLengthenedProjections(lengthenedChanges: Seq[ChangeInfo]): Seq[Projection] = {
    //VVH change api doesn't seems to support multiple changes for the same day in the same link id.
    lengthenedChanges.groupBy(ch => ch.timeStamp).flatMap {
      case (_, groupedChanges) =>
        groupedChanges.find(_.changeType == ChangeType.LengthenedCommonPart).map {
          change =>
            val minNewMeasure = groupedChanges.map(c => Math.min(c.newStartMeasure.get, c.newEndMeasure.get)).min
            val maxNewMeasure = groupedChanges.map(c => Math.max(c.newStartMeasure.get, c.newEndMeasure.get)).max
            val (newStartM, newEndM) = if (change.newStartMeasure.get > change.newEndMeasure.get) (maxNewMeasure, minNewMeasure) else (minNewMeasure, maxNewMeasure)
            logger.debug("Change info, oldId: " + change.oldId + " newId: " + change.newId + " changeType: " + change.changeType)
            Projection(change.oldId.get, change.newId.get, change.oldStartMeasure.get, change.oldEndMeasure.get, newStartM, newEndM, change.timeStamp)
        }
    }.toSeq
  }

  private def generateShortenedProjections(shortenedChanges: Seq[ChangeInfo]): Seq[Projection] = {
    //VVH change api doesn't seems to support multiple changes for the same day in the same link id.
    shortenedChanges.groupBy(ch => ch.timeStamp).flatMap {
      case (_, groupedChanges) =>
        groupedChanges.find(_.changeType == ChangeType.ShortenedCommonPart).map {
          change =>
            val minOldMeasure = groupedChanges.map(c => Math.min(c.oldStartMeasure.get, c.oldEndMeasure.get)).min
            val maxOldMeasure = groupedChanges.map(c => Math.max(c.oldStartMeasure.get, c.oldEndMeasure.get)).max
            val (oldStartM, oldEndM) = if (change.oldStartMeasure.get > change.oldEndMeasure.get) (maxOldMeasure, minOldMeasure) else (minOldMeasure, maxOldMeasure)
            logger.debug("Change info, oldId: " + change.oldId + " newId: " + change.newId + " changeType: " + change.changeType)
            Projection(change.oldId.get, change.newId.get, oldStartM, oldEndM, change.newStartMeasure.get, change.newEndMeasure.get, change.timeStamp)
        }
    }.toSeq
  }

  private def generateCombinedProjections(nonDividedChanges: Seq[ChangeInfo]): Seq[Projection] = {
    nonDividedChanges.flatMap {
      change =>
        change.changeType match {
          case CombinedModifiedPart | CombinedRemovedPart =>
            logger.debug("Change info, oldId: " + change.oldId + " newId: " + change.newId + " changeType: " + change.changeType)
            Some(Projection(change.oldId.get, change.newId.get, change.oldStartMeasure.get, change.oldEndMeasure.get, change.newStartMeasure.get, change.newEndMeasure.get, change.timeStamp))
          case _ =>
            logger.debug("Change info ignored, oldId: " + change.oldId + " newId: " + change.newId + " changeType: " + change.changeType)
            None
        }
    }
  }

  private def generateProjections(changes: Seq[ChangeInfo]): Seq[Projection] = {
    val (dividedChanges, nonDividedChanges) = changes.partition(_.changeType.isDividedChangeType)
    val (shortenedChanges, nonShortened) = nonDividedChanges.partition(_.changeType.isShortenedChangeType)
    val (lengthenedChanges, nonLengthened) = nonShortened.partition(_.changeType.isLengthenedChangeType)
    generateDividedProjections(dividedChanges) ++ generateLengthenedProjections(lengthenedChanges) ++
      generateShortenedProjections(shortenedChanges) ++ generateCombinedProjections(nonLengthened)
  }

  private def filterOutChanges(linearLocations: Seq[LinearLocation], changes: Seq[ChangeInfo]): Seq[ChangeInfo] = {
    val filterOperations: Seq[ChangeInfo => Boolean] = Seq(
      filterOutChangesWithoutLinkIds,
      filterOutOlderChanges(linearLocations.groupBy(_.linkId))
    )

    changes.filter(change =>
      filterOperations.forall(filterOperation => filterOperation(change))
    )
  }

  private def applyChanges(linearLocations: Seq[LinearLocation], changes: Seq[ChangeInfo], changeSet: ChangeSet, mappedRoadLinks: Map[String, RoadLinkLike]) = {

    //If contains some unsupported change type there is no need to apply any change
    //because the linear locations will be set as floating
    if (changes.isEmpty || changes.exists(nonSupportedChange)) {
      (linearLocations, changeSet)
    } else {
      val projections = generateProjections(changes)
      linearLocations.foldLeft((Seq[LinearLocation](), changeSet)) {
        case ((cLinearLocations, cChangeSet), linearLocation) =>
          val (adjustedLinearLocations, resultChangeSet) = projectLinearLocation(linearLocation, projections, cChangeSet, mappedRoadLinks)
          (cLinearLocations ++ adjustedLinearLocations, resultChangeSet)
      }
    }
  }

  def applyChanges(linearLocations: Seq[LinearLocation], roadLinks: Seq[RoadLinkLike], changes: Seq[ChangeInfo]): (Seq[LinearLocation], ChangeSet) = {

    val filteredChanges = filterOutChanges(linearLocations, changes)

    val mappedChanges = filteredChanges.groupBy(c => c.oldId.getOrElse(c.newId.get))

    val mappedRoadLinks = roadLinks.groupBy(_.linkId).mapValues(_.head)

    val initialChangeSet = ChangeSet(Set.empty, Seq.empty, Seq.empty)

    linearLocations.groupBy(_.linkId).foldLeft(Seq.empty[LinearLocation], initialChangeSet) {
      case ((existingSegments, changeSet), (linkId, linearLocations)) =>

        val (adjustedLinearLocations, resultChangeSet) = applyChanges(linearLocations, mappedChanges.getOrElse(linkId, Seq()), changeSet, mappedRoadLinks)

        (existingSegments ++ adjustedLinearLocations, resultChangeSet)
    }
  }
}

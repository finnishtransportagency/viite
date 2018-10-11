package fi.liikennevirasto.viite.process

import fi.liikennevirasto.digiroad2.asset.SideCode
import fi.liikennevirasto.digiroad2.client.vvh.ChangeInfo
import fi.liikennevirasto.digiroad2.client.vvh.ChangeType._
import fi.liikennevirasto.digiroad2.linearasset.RoadLinkLike
import fi.liikennevirasto.viite.dao.LinearLocation
import fi.liikennevirasto.viite.process.RoadAddressFiller.ChangeSet
import org.slf4j.LoggerFactory

object ApplyChangeInfoProcess {

  private val logger = LoggerFactory.getLogger(getClass)

  private case class Projection(oldStart: Double, oldEnd: Double, newStart: Double, newEnd: Double, vvhTimeStamp: Long, orderIncrement: Int = 0)

  private def newChangeInfoDetected(linearLocation : LinearLocation, changes: Map[Long, Seq[ChangeInfo]]) = {
    changes.getOrElse(linearLocation.linkId, Seq()).exists(c =>
      c.vvhTimeStamp > linearLocation.adjustedTimestamp && (c.oldId.getOrElse(0) == linearLocation.linkId || c.newId.getOrElse(0) == linearLocation.linkId)
    )
  }

  private def isDirectionChangeProjection(projection: Projection): Boolean = {
    ((projection.oldEnd - projection.oldStart)*(projection.newEnd - projection.newStart)) < 0
  }

  private def calculateNewMValuesAndSideCode(linearLocation: LinearLocation, projection: Projection, roadLinkLength: Double) : (Double, Double, SideCode) = {
    val oldLength = projection.oldEnd - projection.oldStart
    val newLength = projection.newEnd - projection.newStart

    // Test if the direction has changed -> side code will be affected, too
    val (newStartMeasure, newEndMeasure, newSideCode) = if (isDirectionChangeProjection(projection)) {
      val newStart = projection.newStart - (linearLocation.endMValue - projection.oldStart) * Math.abs(newLength / oldLength)
      val newEnd = projection.newEnd - (linearLocation.startMValue - projection.oldEnd) * Math.abs(newLength / oldLength)
      (newStart, newEnd, SideCode.switch(linearLocation.sideCode))
    } else {
      val newStart = projection.newStart + (linearLocation.startMValue - projection.oldStart) * Math.abs(newLength / oldLength)
      val newEnd = projection.newEnd + (linearLocation.endMValue - projection.oldEnd) * Math.abs(newLength / oldLength)
      (newStart, newEnd, linearLocation.sideCode)
    }

    (Math.min(roadLinkLength, Math.max(0.0, newStartMeasure)), Math.max(0.0, Math.min(roadLinkLength, newEndMeasure)), linearLocation.sideCode)
  }

  private def projectLinearLocation(linearLocation: LinearLocation, projection: Projection, roadLink: RoadLinkLike, changeSet: ChangeSet): (LinearLocation, ChangeSet) = {

    val (newStartMeasure, newEndMeasure, newSideCode) = calculateNewMValuesAndSideCode(linearLocation, projection, roadLink.length)

    //Test if the linear location intercepts the projection
    if (linearLocation.endMValue <= Math.min(projection.oldStart, projection.oldEnd) || linearLocation.startMValue >= Math.max(projection.oldStart, projection.oldEnd)) {
      (linearLocation, changeSet)
    } else {
      //TODO check the order number
      //TODO update the change set object with the changed information
      (
        linearLocation.copy(id = -1000, linkId = roadLink.linkId, startMValue =  newStartMeasure, endMValue = newEndMeasure, sideCode = newSideCode),
        changeSet
      )
    }
  }

  private def filterOutOlderChanges(linearLocations: Seq[LinearLocation])(change: ChangeInfo): Boolean = {
    val oldestLinearLocationTimestamp = linearLocations.map(_.adjustedTimestamp).min
    change.vvhTimeStamp > oldestLinearLocationTimestamp
  }

  private def filterOutChangesWithoutLinkIds(change: ChangeInfo) : Boolean = {
    change.newId.nonEmpty || change.oldId.nonEmpty
  }

  private def generateProjections(changes: Seq[ChangeInfo]): Seq[Projection] = {

    val (dividedChanges, nonDividedChanges) = changes.partition(_.changeType.isDividedChangeType)

    //TODO check if this is the right way to group divided changes
    dividedChanges.groupBy(_.mmlId).flatMap {
      case (_, groupedChanges) =>
        val orderIncrements = 0 to changes.size
        groupedChanges.sortBy(_.oldStartMeasure).zip(orderIncrements).flatMap {
          case (change, orderIncrement) =>
            change.changeType match {
              case DividedModifiedPart | DividedNewPart =>
                logger.debug("Change info> oldId: " + change.oldId + " newId: " + change.newId + " changeType: " + change.changeType)
                Some(Projection(change.oldStartMeasure.get, change.oldEndMeasure.get, change.newStartMeasure.get, change.newEndMeasure.get, change.vvhTimeStamp, orderIncrement))
              case _ =>
                logger.debug("Change info ignored, oldId: " + change.oldId + " newId: " + change.newId + " changeType: " + change.changeType)
                None
            }
        }
    }.toSeq ++ nonDividedChanges.flatMap {
      change =>
        change.changeType match {
          case CombinedModifiedPart | CombinedRemovedPart =>
            logger.debug("Change info> oldId: " + change.oldId + " newId: " + change.newId + " changeType: " + change.changeType)
            Some(Projection(change.oldStartMeasure.get, change.oldEndMeasure.get, change.newStartMeasure.get, change.newEndMeasure.get, change.vvhTimeStamp))
          case LengthenedCommonPart  | ShortenedCommonPart => //Lengthened new part and Shortened remove part are not needed
            logger.debug("Change info, length change > oldId: " + change.oldId + " newId: " + change.newId + " changeType: " + change.changeType)
            Some(Projection(change.oldStartMeasure.get, change.oldEndMeasure.get, change.newStartMeasure.get, change.newEndMeasure.get, change.vvhTimeStamp))
          case _ =>
            logger.debug("Change info ignored, oldId: " + change.oldId + " newId: " + change.newId + " changeType: " + change.changeType)
            None
        }
    }
  }

  def applyChanges(mappedChanges: Map[Long, Seq[ChangeInfo]])(roadLink: RoadLinkLike, linearLocations: Seq[LinearLocation], changeSet: ChangeSet): (Seq[LinearLocation], ChangeSet) = {
    //    changes.groupBy(c => c.oldId.getOrElse(c.newId.get))

    //TODO filter out all the changes that are not handled filter by date and validations

    //Remove all non applicable changes types
    //Remove all the changes that have more than x meters long from old to new
    //Remove all the change all the divided and combined that have length changes
    //Remove all the changes that doesn't have old and new link id
    //Remove all the changes that doesn't overlap

    val filterOperations: Seq[ChangeInfo => Boolean] = Seq(
      filterOutChangesWithoutLinkIds, //TODO check if this is realy needed because the changes were mapped before
      filterOutOlderChanges(linearLocations)
    )

    val changes = mappedChanges.getOrElse(roadLink.linkId, Seq()).
      filter(change => filterOperations.exists(filterOperation => filterOperation(change)))

    generateProjections(changes).
      sortBy(_.vvhTimeStamp).
      foldLeft(linearLocations) {
        case (linearLocations, changeInfo) =>
          linearLocations
      }

    throw new NotImplementedError("Work in progress")
  }
}

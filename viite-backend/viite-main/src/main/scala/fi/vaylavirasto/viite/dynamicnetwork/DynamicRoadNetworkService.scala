package fi.vaylavirasto.viite.dynamicnetwork

import fi.liikennevirasto.digiroad2.client.kgv.KgvRoadLink
import fi.liikennevirasto.digiroad2.client.vkm.{TiekamuRoadLinkChange, TiekamuRoadLinkChangeError, TiekamuRoadLinkErrorMetaData, VKMClient}
import fi.liikennevirasto.digiroad2.util.LogUtils.time
import fi.liikennevirasto.digiroad2.util.ViiteProperties
import fi.liikennevirasto.viite.{AwsService, MaxDistanceForConnectedLinks}
import fi.liikennevirasto.viite.dao.{LinearLocation, LinearLocationDAO, Roadway, RoadwayDAO}
import fi.vaylavirasto.viite.dao.ComplementaryLinkDAO
import fi.vaylavirasto.viite.geometry.{GeometryUtils, Point}
import fi.vaylavirasto.viite.geometry.GeometryUtils.scaleToThreeDigits
import fi.vaylavirasto.viite.model.{LinkGeomSource, RoadLink, RoadPart}
import fi.vaylavirasto.viite.postgis.PostGISDatabaseScalikeJDBC
import fi.vaylavirasto.viite.util.ViiteException
import org.apache.hc.client5.http.impl.classic.HttpClients
import org.joda.time.DateTime
import org.json4s.DefaultFormats
import org.json4s.jackson.Json
import org.slf4j.{Logger, LoggerFactory}

import scala.collection.mutable.ListBuffer

class DynamicRoadNetworkService(linearLocationDAO: LinearLocationDAO, roadwayDAO: RoadwayDAO, val kgvClient: KgvRoadLink, awsService: AwsService, linkNetworkUpdater: LinkNetworkUpdater) {

  val bucketName: String = ViiteProperties.dynamicLinkNetworkS3BucketName
  val vkmClient = new VKMClient(ViiteProperties.vkmUrlDev, ViiteProperties.vkmApiKeyDev)

  def runWithTransaction[T](f: => T): T = PostGISDatabaseScalikeJDBC.runWithTransaction(f)
  
  implicit val formats = DefaultFormats
  val logger: Logger = LoggerFactory.getLogger(getClass)

  val complementaryLinkDAO: ComplementaryLinkDAO = new ComplementaryLinkDAO

  def tiekamuRoadLinkChangeErrorToMap(tiekamuRoadLinkChangeError: TiekamuRoadLinkChangeError): Map[String, Any] = {
    Map(
      "errorMessage" -> tiekamuRoadLinkChangeError.errorMessage,
      "oldLinkId" -> tiekamuRoadLinkChangeError.change.oldLinkId,
      "oldStartM" -> tiekamuRoadLinkChangeError.change.oldStartM,
      "oldEndM" -> tiekamuRoadLinkChangeError.change.oldEndM,
      "newLinkId" -> tiekamuRoadLinkChangeError.change.newLinkId,
      "newStartM" -> tiekamuRoadLinkChangeError.change.newStartM,
      "newEndM" -> tiekamuRoadLinkChangeError.change.newEndM,
      "digitizationChange" -> tiekamuRoadLinkChangeError.change.digitizationChange,
      "roadNumber" -> tiekamuRoadLinkChangeError.metaData.roadPart.roadNumber,
      "roadPartNumber" -> tiekamuRoadLinkChangeError.metaData.roadPart.partNumber,
      "linearLocationIds" -> tiekamuRoadLinkChangeError.metaData.linearLocationIds
    )
  }

  def skippedTiekamuRoadLinkChangeToMap(change: TiekamuRoadLinkChange): Map[String, Any] = {
    Map(
      "oldLinkId" -> change.oldLinkId,
      "oldStartM" -> change.oldStartM,
      "oldEndM" -> change.oldEndM,
      "newLinkId" -> change.newLinkId,
      "newStartM" -> change.newStartM,
      "newEndM" -> change.newEndM,
      "digitizationChange" -> change.digitizationChange
    )
  }

  def linkNetworkChangeToMap(change: LinkNetworkChange): Map[String, Any] = {
    Map(
      "changeType" -> change.changeType,
      "oldLink" -> linkInfoToMap(change.oldLink),
      "newLinks" -> change.newLinks.map(changeInfo => linkInfoToMap(changeInfo)),
      "replaceInfos" -> change.replaceInfos.map(replace => replaceInfoToMap(replace))
    )
  }

  def linkInfoToMap(linkInfo: LinkInfo): Map[String, Any] = {
    Map(
      "linkId" -> linkInfo.linkId,
      "linkLength" -> linkInfo.linkLength,
      "geometry" -> linkInfo.geometry
    )
  }

  def replaceInfoToMap(replaceInfo: ReplaceInfo): Map[String, Any] = {
    Map(
      "oldLinkId" -> replaceInfo.oldLinkId,
      "oldFromMValue" -> replaceInfo.oldFromMValue,
      "oldToMValue" -> replaceInfo.oldToMValue,
      "newFromMValue" -> replaceInfo.newFromMValue,
      "newToMValue" -> replaceInfo.newToMValue,
      "digitizationChange" -> replaceInfo.digitizationChange,
      "oldLinkViiteData" -> replaceInfo.oldLinkViiteData.map(oldLinkViiteData => viiteMetaDataToMap(oldLinkViiteData))
    )
  }

  def viiteMetaDataToMap(data: ViiteMetaData): Map[String, Any] = {
    Map(
      "linearLocationId" -> data.linearLocationId,
      "roadwayNumber" -> data.roadwayNumber,
      "orderNumber" -> data.orderNumber,
      "roadNumber" -> data.roadPart.roadNumber,
      "roadPartNumber" -> data.roadPart.partNumber
    )
  }

  def createViiteLinkNetworkChanges(tiekamuRoadLinkChanges: Seq[TiekamuRoadLinkChange],
                                    activeLinearLocations: Seq[LinearLocation],
                                    kgvRoadLinks: Seq[DynamicRoadNetworkService.this.kgvClient.roadLinkVersionsData.LinkType],
                                    complementaryLinks: Seq[RoadLink]): Seq[LinkNetworkChange] = {
    def createOldLinkInfo(kgvRoadLinks: Seq[DynamicRoadNetworkService.this.kgvClient.roadLinkVersionsData.LinkType], complementaryLinks: Seq[RoadLink], oldLinkId: String): LinkInfo = {
      val oldLinkInfo = {
        val oldLink = (kgvRoadLinks ++ complementaryLinks).find(rl => rl.linkId == oldLinkId)
        if (oldLink.isDefined)
          LinkInfo(oldLinkId, scaleToThreeDigits(oldLink.get.length), oldLink.get.geometry)
        else
          throw ViiteException(s"Can't create change set without KGV/complementary road link data for oldLinkId: ${oldLinkId} ")
      }
      oldLinkInfo
    }

    def createNewLinkInfos(kgvRoadLinks: Seq[DynamicRoadNetworkService.this.kgvClient.roadLinkVersionsData.LinkType], complementaryLinks: Seq[RoadLink], distinctChangeInfosByNewLink: Seq[TiekamuRoadLinkChange]): Seq[LinkInfo] = {
      val newLinkInfo = distinctChangeInfosByNewLink.map(ch => {
        val newLink = (kgvRoadLinks ++ complementaryLinks).find(newLink => ch.newLinkId == newLink.linkId)
        if (newLink.isDefined)
          LinkInfo(ch.newLinkId, scaleToThreeDigits(newLink.get.length), newLink.get.geometry)
        else
          throw ViiteException(s"Can't create change set without KGV/complementary road link data for newLinkId: ${ch.newLinkId} ")
      })
      newLinkInfo
    }

    def createReplaceInfos(tiekamuRoadLinkChanges: Seq[TiekamuRoadLinkChange]): Seq[ReplaceInfo] = {
      def createViiteMetaData(linearLocations: Seq[LinearLocation]): Seq[ViiteMetaData] = {
        val viiteMetaData = linearLocations.map(ll => {
          val roadway = roadwayDAO.fetchAllByRoadwayNumbers(Set(ll.roadwayNumber)).head
          ViiteMetaData(ll.id, ll.startMValue, ll.endMValue, ll.roadwayNumber, ll.orderNumber.toInt, roadway.roadPart)
        })
        viiteMetaData
      }

      /**
       * In order to merge TiekamuRoadLinkChanges to one they need to:
       * - share the same oldLinkId
       * - share the same newLinkId
       * - be continuous by the M values
       *
       * So first we group the TiekamuRoadLinkChanges by the old- and newLinkId.
       * Then we order the groups by the oldStartM -value
       * Then we create continuous sections of those groups
       * Then those sections can be merged in to one TiekamuRoadLinkChange
       * And lastly we return the list of merged TiekamuRoadLinkChanges
       */
      def mergeTiekamuRoadLinkChanges(tiekamuRoadLinkChanges: Seq[TiekamuRoadLinkChange]): Seq[TiekamuRoadLinkChange] = {
        def createSections(tiekamuRoadLinkChanges: Seq[TiekamuRoadLinkChange]): Seq[Seq[TiekamuRoadLinkChange]] = {
          tiekamuRoadLinkChanges.foldLeft(Seq[Seq[TiekamuRoadLinkChange]]()) {
            (sections, change) =>
              sections match {
                // If sections is empty, create a new section with the current change and wrap it in a Seq
                case Seq() => Seq(Seq(change))
                // If there are existing sections, check if the current change can be appended to the last section
                case currentSection +: rest =>
                  if (change.oldStartM == currentSection.last.oldEndM && change.digitizationChange == change.digitizationChange) {
                    (currentSection :+ change) +: rest // Add the updated section to the sections list
                  } else {
                    Seq(change) +: sections // Create a new section with the current change and prepend it to sections
                  }
              }
          }
        }

        def mergeSectionIntoOneTiekamuRoadLinkChange(section: Seq[TiekamuRoadLinkChange]): TiekamuRoadLinkChange = {
          val oldLinkId = section.head.oldLinkId
          val lowestOldStartM = section.map(_.oldStartM).min
          val highestOldEndM = section.map(_.oldEndM).max
          val newLinkId = section.head.newLinkId
          val lowestNewStartM = section.map(_.newStartM).min
          val highestNewEndM = section.map(_.newEndM).max
          val digitizationChange = section.map(_.digitizationChange).distinct

          if (digitizationChange.length > 1)
            throw ViiteException("Too many 'digitizationChange' values in one section for it be merged into one! Section: " + section.foreach(change => change))
          else {
            // Create a merged TiekamuRoadLinkChange with the lowest oldStartM and highest oldEndM
            TiekamuRoadLinkChange(oldLinkId, lowestOldStartM, highestOldEndM, newLinkId, lowestNewStartM, highestNewEndM, digitizationChange.head)
          }
        }

        // Create sections and merge them into a single TiekamuRoadLinkChange
        def createMergedTiekamuRoadLinkChanges(changes: Seq[TiekamuRoadLinkChange]): Seq[TiekamuRoadLinkChange] = {
          // Create individual sections
          val sections: Seq[Seq[TiekamuRoadLinkChange]] = createSections(changes)
          // Merge sections into a single change and collect them into a sequence
          val mergedSections: Seq[TiekamuRoadLinkChange] = sections.map(section => mergeSectionIntoOneTiekamuRoadLinkChange(section))
          mergedSections
        }

        // group the changeInfos by both the oldLinkId and the newLinkId  Map[(oldLinkId, newLinkId), Seq[TiekamuRoadLinkChange]]
        val groupedByBothLinkIds = tiekamuRoadLinkChanges.groupBy(ch => (ch.oldLinkId, ch.newLinkId))

        val mergedTiekamuRoadLinkChanges: Seq[TiekamuRoadLinkChange] = groupedByBothLinkIds.values.flatMap(changes => {
          // sort the changes by oldStartM
          val sortedChanges = changes.sortWith(_.oldStartM < _.oldStartM)
          createMergedTiekamuRoadLinkChanges(sortedChanges)
        }).toSeq

        mergedTiekamuRoadLinkChanges
      }

      val mergedTiekamuRoadLinkChanges = mergeTiekamuRoadLinkChanges(tiekamuRoadLinkChanges)

      val replaceInfos = mergedTiekamuRoadLinkChanges.map(ch => {
        val linearLocations = activeLinearLocations.filter(ll => ll.linkId == ch.oldLinkId)
        if (linearLocations.nonEmpty) {
          val viiteMetaData = createViiteMetaData(linearLocations)
          ReplaceInfo(ch.oldLinkId, ch.oldStartM, ch.oldEndM, ch.newLinkId, ch.newStartM, ch.newEndM, ch.digitizationChange, viiteMetaData)
        } else
          throw ViiteException(s"Can't create change set without existing active Linearlocation with old linkId ${ch.oldLinkId} and startMValue ${ch.oldStartM} and endMValue ${ch.oldEndM}")
      })
      replaceInfos
    }

    time(logger, "Creating Viite LinkNetworkChange sets") {
      val groupedByOldLinkId = tiekamuRoadLinkChanges.groupBy(changeInfo => changeInfo.oldLinkId)

      val linkNetworkChanges = groupedByOldLinkId.map(group => {
        val oldLinkId = group._1
        val tiekamuRoadLinkChangeInfos = group._2
        val distinctChangeInfosByNewLink = tiekamuRoadLinkChangeInfos.groupBy(_.newLinkId).values.map(_.head).toSeq

        val viiteRoadLinkChange = {
          val changeType = if (tiekamuRoadLinkChangeInfos.map(_.newLinkId).distinct.size > 1) "split" else "replace"
          val oldInfo = createOldLinkInfo(kgvRoadLinks, complementaryLinks, oldLinkId)
          val newInfo = createNewLinkInfos(kgvRoadLinks, complementaryLinks, distinctChangeInfosByNewLink)
          val replaceInfo = createReplaceInfos(tiekamuRoadLinkChangeInfos)
          LinkNetworkChange(changeType, oldInfo, newInfo, replaceInfo)
        }
        viiteRoadLinkChange
      }).toSeq
      linkNetworkChanges
    }
  }

  def createTiekamuRoadLinkChangeSets(previousDate: DateTime, newDate: DateTime, activeLinearLocations: Seq[LinearLocation]): Seq[TiekamuRoadLinkChange] = {

    /**
     * Viite is only interested in change infos that affect links that have road addressed roads on them.
     * Therefore we filter out all the unnecessary change infos i.e. unaddressed link change infos
     */
    def getChangeInfosWithRoadAddress(tiekamuRoadLinkChanges: Seq[TiekamuRoadLinkChange], activeLinearLocationsInViite: Seq[LinearLocation]): Seq[TiekamuRoadLinkChange] = {
      val oldLinkIds = tiekamuRoadLinkChanges.map(_.oldLinkId).toSet
      val targetLinearLocations = linearLocationDAO.fetchByLinkId(oldLinkIds)
      val filteredLinearLocations = targetLinearLocations.filter(ll => activeLinearLocationsInViite.map(_.id).contains(ll.id))
      val filteredActiveChangeInfos = tiekamuRoadLinkChanges.filter(rlc => filteredLinearLocations.map(_.linkId).contains(rlc.oldLinkId))

      filteredActiveChangeInfos
    }

    time(logger, "Creating Viite road link change info sets") {
      val tiekamuRoadLinkChanges = vkmClient.getTiekamuRoadlinkChanges(previousDate, newDate)
      logger.info(s"${tiekamuRoadLinkChanges.length} TiekamuRoadLinkChanges fetched.")
      // filter change infos so that only the ones that target links with road addresses are left
      val roadAddressedRoadLinkChanges = getChangeInfosWithRoadAddress(tiekamuRoadLinkChanges, activeLinearLocations)

      roadAddressedRoadLinkChanges
    }
  }

  def validateTiekamuRoadLinkChanges(tiekamuRoadLinkChanges: Seq[TiekamuRoadLinkChange], linearLocations: Seq[LinearLocation], kgvRoadLinks: Seq[DynamicRoadNetworkService.this.kgvClient.roadLinkVersionsData.LinkType], complementaryLinks: Seq[RoadLink]
                                    ): Seq[TiekamuRoadLinkChangeError] = {

    def filterByLinkIdAndMValueRange(allLinearLocations: Seq[LinearLocation], linkId: String, filterMvalueMin: Double, filterMvalueMax: Double): List[LinearLocation] = {

      val mustStartBefore = filterMvalueMax - GeometryUtils.DefaultEpsilon
      val mustEndAfter = filterMvalueMin + GeometryUtils.DefaultEpsilon

      allLinearLocations
        .filter(ll =>
          ll.linkId == linkId &&
            ll.startMValue <= mustStartBefore &&
            ll.endMValue >= mustEndAfter &&
            ll.validTo.isEmpty
        )
        .sortBy(_.startMValue)
        .toList
    }

    def filterActiveLinearLocationsWithPoint(allLinearLocations: Seq[LinearLocation], point: Point): Seq[LinearLocation] = {
      allLinearLocations
        .filter(ll =>
          ll.validTo.isEmpty &&
            ll.geometry.exists(geomPoint => geomPoint.distance2DTo(point) <= MaxDistanceForConnectedLinks)
        )
    }

    def existsConnectingOrderNumber(orderNumbers: Seq[Double], otherOrderNumbers: Set[Double]): Boolean = {
      val min = orderNumbers.min
      val max = orderNumbers.max
      otherOrderNumbers.contains(min - 1) || otherOrderNumbers.contains(max + 1)
    }

    def existsConnectingRoadway(roadway: Roadway, otherRoadways: Set[Roadway]): Boolean = {
      otherRoadways.map(_.addrMRange.start).contains(roadway.addrMRange.end) ||
      otherRoadways.map(_.addrMRange.end).contains(roadway.addrMRange.start)
    }

    def checkRoadAddressContinuityForSingleRoadway(linearLocations: Seq[LinearLocation], change: TiekamuRoadLinkChange): Boolean = {
      val linLocsGroupedByLinkId = linearLocations.groupBy(_.linkId)
      val orderNumbers = filterByLinkIdAndMValueRange(linearLocations, change.oldLinkId, change.oldStartM, change.oldEndM).map(_.orderNumber)
      val otherOrderNumbers = linLocsGroupedByLinkId.filter(grp => grp._1 != change.oldLinkId).map(_._2.map(_.orderNumber)).toSet.flatten
      existsConnectingOrderNumber(orderNumbers, otherOrderNumbers)
    }

    def checkRoadAddressContinuityBetweenRoadways(linearLocations: Seq[LinearLocation], change: TiekamuRoadLinkChange, roadwaysForLinearLocations: Set[Roadway]): Boolean = {
      val linLocsGroupedByLinkId = linearLocations.groupBy(_.linkId)
      val roadwayNumber = filterByLinkIdAndMValueRange(linearLocations, change.oldLinkId, change.oldStartM, change.oldEndM).head.roadwayNumber
      val roadway = roadwaysForLinearLocations.find(_.roadwayNumber == roadwayNumber).getOrElse(throw ViiteException(s"No Roadway found for linear location id: ${change.oldLinkId} startM: ${change.oldStartM} endM: ${change.oldEndM}"))
      val otherRoadwayNumbers = linLocsGroupedByLinkId.filterNot(grp => grp._1 == change.oldLinkId).map(_._2.map(_.roadwayNumber)).toSet.flatten
      val otherRoadways = roadwaysForLinearLocations.filter(rw => otherRoadwayNumbers.contains(rw.roadwayNumber)).toSet
      existsConnectingRoadway(roadway, otherRoadways)
    }

    def getMetaData(change: TiekamuRoadLinkChange, activeLinearLocations: Seq[LinearLocation], roadwaysForLinearLocations: Set[Roadway]): TiekamuRoadLinkErrorMetaData = {
      val errorLink = change.oldLinkId
      val errorLinearLocations = activeLinearLocations.filter(ll => ll.linkId == errorLink)
      val errorRoadwayNumbers = errorLinearLocations.map(_.roadwayNumber).toSet
      val errorRoadways = roadwaysForLinearLocations.filter(rw => errorRoadwayNumbers.contains(rw.roadwayNumber)).toSet
      val errorRoadsParts = errorRoadways.map(r => {
        (r.roadPart)
      })
      TiekamuRoadLinkErrorMetaData(errorRoadsParts.head, errorRoadways.head.roadwayNumber, errorLinearLocations.map(_.id), errorLink)
    }

    def validateCombinationCases(otherChangesWithSameNewLinkId: Seq[TiekamuRoadLinkChange], change: TiekamuRoadLinkChange, linearLocations: Seq[LinearLocation], roadwaysForLinearLocations: Set[Roadway]): Seq[TiekamuRoadLinkChangeError] = {
      def areHomogeneous(linearLocations: Set[LinearLocation]): Boolean = {
        val roadwayNumbers = linearLocations.map(_.roadwayNumber)
        val roadways = roadwaysForLinearLocations.filter(rw => roadwayNumbers.contains(rw.roadwayNumber))
        val roadGroups = roadways.groupBy(rw => (rw.roadPart, rw.track)) // We might need to check Administrative class here as well?
        roadGroups.size == 1
      }

      def changesInSameRoadway(linearLocations: Seq[LinearLocation]): Boolean = {
        val roadwayNumbers = linearLocations.map(_.roadwayNumber).toSet
        val roadways = roadwaysForLinearLocations.filter(rw => roadwayNumbers.contains(rw.roadwayNumber))
        val roadGroups = roadways.groupBy(rw => (rw.roadPart, rw.track))
        val singleRoadGroup = roadGroups.head._2
        singleRoadGroup.map(_.roadwayNumber).size == 1
      }

      def homogeneityValidationForCombinationCase(): Seq[TiekamuRoadLinkChangeError] = {
        val oldLinkIds = (otherChangesWithSameNewLinkId.map(_.oldLinkId) :+ change.oldLinkId).toSet
        val oldLinearLocations = linearLocations.filter(ll => oldLinkIds.contains(ll.linkId)).toSet
        val homogeneous = areHomogeneous(oldLinearLocations)

        if (!homogeneous) {
          Seq(
            TiekamuRoadLinkChangeError(
              "Two or more links with non-homogeneous road addresses (road number, road part number, track) cannot merge together.",
              change,
              getMetaData(change, linearLocations, roadwaysForLinearLocations)
            )
          )
        } else {
          Seq.empty
        }
      }

      def continuityValidationForCombinationCase(): Seq[TiekamuRoadLinkChangeError] = {
        val oldLinkIds = (otherChangesWithSameNewLinkId.map(_.oldLinkId) :+ change.oldLinkId).toSet
        val oldLinearLocations = linearLocations.filter(ll => oldLinkIds.contains(ll.linkId))

        val continuityCheckPassed = if (changesInSameRoadway(oldLinearLocations)) {
          checkRoadAddressContinuityForSingleRoadway(oldLinearLocations, change)
        } else {
          checkRoadAddressContinuityBetweenRoadways(oldLinearLocations, change, roadwaysForLinearLocations)
        }

        if (!continuityCheckPassed) {
          Seq(
            TiekamuRoadLinkChangeError(
              "Road address not continuous, cannot merge links together.",
              change,
              getMetaData(change, linearLocations, roadwaysForLinearLocations)
            )
          )
        } else {
          Seq.empty
        }
      }

      def returnCrossRoadValidationError(change: TiekamuRoadLinkChange, linearLocationsAtPoint:Seq[LinearLocation]): Option[TiekamuRoadLinkChangeError] = {
        Some(
          TiekamuRoadLinkChangeError(
            "Links cannot be merged together, found linear location(s) that connect(s) between the two links. (Cross road case)",
            change,
            getMetaData(change, linearLocationsAtPoint, roadwaysForLinearLocations)
          )
        )
      }

      def crossRoadValidationForCombinationCase(): Seq[TiekamuRoadLinkChangeError] = {
        val oldLinkIds = (otherChangesWithSameNewLinkId.map(_.oldLinkId) :+ change.oldLinkId).toSet
        val oldLinearLocations = linearLocations.filter(ll => oldLinkIds.contains(ll.linkId))

        oldLinearLocations.flatMap { oldLinearLocation =>
          val connectingOldLinearLocationOpt = oldLinearLocations.find { another =>
            oldLinearLocation.connected(another)
          }

          connectingOldLinearLocationOpt.flatMap { connectingOld =>
            val linearLocationStartAndEndPoints = Seq(oldLinearLocation.getFirstPoint, oldLinearLocation.getLastPoint)
            val startingPoint = connectingOld.getFirstPoint
            val endPoint = connectingOld.getLastPoint

            val connectingPoint =
              if (linearLocationStartAndEndPoints.exists(p => p.connected(startingPoint))) startingPoint
              else endPoint

            // Find all the active linear locations that have geometry on the connecting point
            val linearLocationsAtPoint = filterActiveLinearLocationsWithPoint(linearLocations, connectingPoint)
            // Filter out the linear locations that are part of the change, leaving only the possibly "problematic" linear locations
            // that may prevent the combination due to a crossing/junction
            val problematicLinearLocations = linearLocationsAtPoint.filterNot(ll =>
              oldLinearLocations.exists(_.linkId == ll.linkId)
            )

            if (problematicLinearLocations.nonEmpty) {
              val problematicLinearLocationChanges = problematicLinearLocations.flatMap(ll => {
                val linkId = ll.linkId
                tiekamuRoadLinkChanges.filter(ch => ch.oldLinkId == linkId)
              })

              if (problematicLinearLocationChanges.nonEmpty) { // If there are changes to the problematic linear locations then we need further validation
                // Get the new links from KGV links
                val newLinkIds = problematicLinearLocationChanges.map(_.newLinkId).toSet
                val kgvLinksForChanges = kgvRoadLinks.filter(kgvLink => newLinkIds.contains(kgvLink.linkId))
                // Get the start and end points
                val startAndEndPoints = kgvLinksForChanges.flatMap(kgvLink => {
                  Seq(kgvLink.geometry.head, kgvLink.geometry.last)
                })

                if (startAndEndPoints.contains(connectingPoint)){ // If the connecting point still has a linear location connected after the days link changes
                  // Then it is an error
                  returnCrossRoadValidationError(change, linearLocationsAtPoint)
                } else {
                  None
                }
              } else { // If there are no changes for the problematic linear location in the same day
                // Then it is an error
                returnCrossRoadValidationError(change, linearLocationsAtPoint)
              }
            } else {
              None
            }
          }
        }
      }

      homogeneityValidationForCombinationCase() ++ continuityValidationForCombinationCase() ++ crossRoadValidationForCombinationCase()
    }

    time(logger, "Validating TiekamuRoadLinkChange sets") {
      val roadwaysForLinearLocations = roadwayDAO.fetchAllByRoadwayNumbers(linearLocations.map(_.roadwayNumber).toSet).toSet

      var tiekamuRoadLinkChangeErrors = new ListBuffer[TiekamuRoadLinkChangeError]()
      tiekamuRoadLinkChanges.foreach(change => {
        val lengthOfChange = GeometryUtils.scaleToThreeDigits(change.oldEndM - change.oldStartM)
        val oldLinkId = change.oldLinkId
        val newLinkId = change.newLinkId
        val newStartM = change.newStartM
        val newEndM = change.newEndM
        val changesWithNewLinkId = tiekamuRoadLinkChanges.filter(ch => ch.newLinkId == newLinkId)
        val otherChangesWithSameNewLinkId = changesWithNewLinkId.filter(ch => ch.newStartM != newStartM && ch.newEndM != newEndM)

        // Fold over the sequence 'changesWithNewLinkId' to calculate the minimum start measure and maximum end measure
        // Initialize the accumulator with (Double.MaxValue, Double.MinValue) to ensure any actual value will replace them
        val (minStartM, maxEndM) = changesWithNewLinkId.foldLeft((Double.MaxValue, Double.MinValue)) {
          case ((min, max), ch) => (math.min(min, ch.newStartM), math.max(max, ch.newEndM))
        }

        // Calculate the length covered by the new link ID by subtracting minStartM from maxEndM
        // Then scale the result to three decimal digits using GeometryUtils
        val newlinkIdChangesLength = GeometryUtils.scaleToThreeDigits(maxEndM - minStartM)

        val newLink = (kgvRoadLinks ++ complementaryLinks).find(kgvLink => kgvLink.linkId == newLinkId).getOrElse(
          throw ViiteException(s"Missing new link from KGV/complementary link table. Cannot validate Tiekamu change infos without KGV/complementary road link. LinkId: ${newLinkId}, changeInfo: ${change}")
        )
        val linearLocationsWithOldLinkId = linearLocations.filter(_.linkId == oldLinkId)
        val roadAddressedLinkLength = GeometryUtils.scaleToThreeDigits(linearLocationsWithOldLinkId.map(_.endMValue).max - linearLocationsWithOldLinkId.map(_.startMValue).min)
        
        // check that the changeset actually has some changes in it
        if (change.oldLinkId == change.newLinkId &&
          change.oldStartM == change.newStartM &&
          change.oldEndM == change.newEndM &&
          !change.digitizationChange) {
          tiekamuRoadLinkChangeErrors += TiekamuRoadLinkChangeError("No changes found in the changeset ", change, getMetaData(change, linearLocations, roadwaysForLinearLocations))
        }

        // check that there are no "partial" changes to road addressed links, i.e. only part of the link changes and the other part has no changes applied to it.
        if (lengthOfChange != roadAddressedLinkLength) {
          val allChangesWithOldLinkId = tiekamuRoadLinkChanges.filter(_.oldLinkId == oldLinkId)
          val combinedLengthOfChanges = GeometryUtils.scaleToThreeDigits(allChangesWithOldLinkId.map(och => och.oldEndM - och.oldStartM).sum)
          if (combinedLengthOfChanges != roadAddressedLinkLength)
            tiekamuRoadLinkChangeErrors += TiekamuRoadLinkChangeError("No partial changes allowed. The old link needs to have changes applied to the whole length of the old link", change, getMetaData(change, linearLocations, roadwaysForLinearLocations))
        }
        else if (newlinkIdChangesLength != GeometryUtils.scaleToThreeDigits(newLink.length)) {
          tiekamuRoadLinkChangeErrors += TiekamuRoadLinkChangeError("No partial changes allowed. The new link needs to have changes applied to the whole length of the new link", change, getMetaData(change, linearLocations, roadwaysForLinearLocations))
        }

        // if there are combined links (A + B = C)
        else if (otherChangesWithSameNewLinkId.nonEmpty) {
          tiekamuRoadLinkChangeErrors ++= validateCombinationCases(otherChangesWithSameNewLinkId, change, linearLocations, roadwaysForLinearLocations)
        }
      })
      tiekamuRoadLinkChangeErrors
    }
  }

  /** If the change set includes an erroneous link update, then this function will filter out the whole road part (where the erroneous link update lies) from the change set.
   * @param activeLinearLocations Linearlocations that are in use on the road network at the moment
   * i.e. linear locations' valid_to IS NULL in the database AND the linear location is on a roadway that is on the current road network (roadways' valid_to IS NULL AND end_date IS NULL)
   */
  def filterOutErroneousParts(tiekamuRoadLinkChanges: Seq[TiekamuRoadLinkChange], activeLinearLocations: Seq[LinearLocation], tiekamuRoadLinkChangeErrors: Seq[TiekamuRoadLinkChangeError]): ((Seq[TiekamuRoadLinkChange], Seq[TiekamuRoadLinkChange]), Seq[LinearLocation]) = {
    val errorLinks = tiekamuRoadLinkChangeErrors.map(err => err.change.oldLinkId)
    val errorLinearLocations = activeLinearLocations.filter(ll => errorLinks.contains(ll.linkId))
    val errorRoadwayNumbers = errorLinearLocations.map(_.roadwayNumber)
    val errorRoadways = roadwayDAO.fetchAllByRoadwayNumbers(errorRoadwayNumbers.toSet)
    val errorRoadsParts = errorRoadways.map(r => {
      (r.roadPart)
    })
    logger.error(s"${tiekamuRoadLinkChangeErrors.size} errors found on road addresses: ${errorRoadsParts.toList}! Here is the list of errors: ${tiekamuRoadLinkChangeErrors.toList}")
    val affectedRoadwayNumbers = errorRoadsParts.flatMap(roadAndPart => roadwayDAO.fetchAllByRoadPart(roadAndPart)).map(_.roadwayNumber)
    val activeLinearLocationsWithoutAffected = activeLinearLocations.filterNot(ll => affectedRoadwayNumbers.contains(ll.roadwayNumber))
    val affectedLinkIds = activeLinearLocations.filter(ll => affectedRoadwayNumbers.contains(ll.roadwayNumber)).map(_.linkId)
    val (affectedTiekamuRoadLinkChanges, validTiekamuRoadLinkChanges) =  tiekamuRoadLinkChanges.partition(ch => affectedLinkIds.contains(ch.oldLinkId))
    ((validTiekamuRoadLinkChanges, affectedTiekamuRoadLinkChanges), activeLinearLocationsWithoutAffected)
  }

  def createChangeSetsAndErrorsList(previousDate: DateTime, newDate: DateTime): (Seq[LinkNetworkChange], Seq[TiekamuRoadLinkChangeError], Seq[TiekamuRoadLinkChange]) = {
    runWithTransaction {
      val activeLinearLocations = linearLocationDAO.fetchActiveLinearLocationsWithRoadAddresses() // get linear locations that are on active road addresses
      val tiekamuRoadLinkChanges = createTiekamuRoadLinkChangeSets(previousDate: DateTime, newDate: DateTime, activeLinearLocations)

      //get the new and the old linkIds to Set[String]
      val newLinkIds = tiekamuRoadLinkChanges.map(_.newLinkId).toSet
      val oldLinkIds = tiekamuRoadLinkChanges.map(_.oldLinkId).toSet
      // fetch roadLinks from KGV these are used for getting geometry and link lengths
      val kgvRoadLinks = kgvClient.roadLinkVersionsData.fetchByLinkIds(newLinkIds ++ oldLinkIds)

      val nonExistentNewLinkIds = {
        if (newLinkIds.nonEmpty) {
          val knownLinkIds = (kgvRoadLinks ++ kgvClient.complementaryData.fetchByLinkIds(newLinkIds)).map(_.linkId)
          newLinkIds.filterNot(newLinkId => knownLinkIds.contains(newLinkId))
        } else {
          Set.empty[String]
        }
      }

      if (nonExistentNewLinkIds.nonEmpty) {
        logger.info(s"Some link ids (${nonExistentNewLinkIds}) were not found in KGV or in Viite complementary link table. Searching from VKM next..")
        nonExistentNewLinkIds.foreach(linkId => {
          val complementaryLinkFromVKM = vkmClient.fetchComplementaryLinkFromVKM(linkId)
          if (complementaryLinkFromVKM.nonEmpty) {
            logger.info(s"Found complementaryLink from VKM: ${complementaryLinkFromVKM.get}, adding to complementary link table in Viite.")
            complementaryLinkDAO.create(complementaryLinkFromVKM.get)
          } else {
            throw ViiteException("Couldn't find new link id in KGV, Viite complementary link table, or VKM complementary links.")
          }
        })
      }

      val complementaryLinks = {
        if ((newLinkIds ++ oldLinkIds).nonEmpty) {
          kgvClient.complementaryData.fetchByLinkIds(newLinkIds ++ oldLinkIds)
        } else {
          Seq.empty[RoadLink]
        }
      }

      val tiekamuRoadLinkChangeErrors = validateTiekamuRoadLinkChanges(tiekamuRoadLinkChanges, activeLinearLocations, kgvRoadLinks, complementaryLinks)

      var skippedTiekamuRoadLinkChanges = Seq.empty[TiekamuRoadLinkChange]
      val (validTiekamuRoadLinkChanges, validActiveLinearLocations) = {
        if (tiekamuRoadLinkChangeErrors.nonEmpty) {
          val ((validTiekamuRoadLinkChanges, affectedTiekamuRoadLinkChanges), validActiveLinearLocations) =  filterOutErroneousParts(tiekamuRoadLinkChanges, activeLinearLocations, tiekamuRoadLinkChangeErrors)
          skippedTiekamuRoadLinkChanges = affectedTiekamuRoadLinkChanges
          (validTiekamuRoadLinkChanges, validActiveLinearLocations)
        } else {
          (tiekamuRoadLinkChanges, activeLinearLocations)
        }
      }

      val viiteChangeSets = createViiteLinkNetworkChanges(validTiekamuRoadLinkChanges, validActiveLinearLocations, kgvRoadLinks, complementaryLinks)
      (viiteChangeSets, tiekamuRoadLinkChangeErrors, skippedTiekamuRoadLinkChanges)
    }
  }

  /**
   * Initiates the update process for the link network, either as a single batch job or divided into daily incremental updates.
   *
   * @param previousDate   The starting date of the current link network state.
   * @param newDate        The target date to which the link network should be updated.
   * @param processPerDay  If true, the update is performed in daily intervals; if false, the entire range is processed as a single batch.
   */
  def initiateLinkNetworkUpdates(previousDate: DateTime, newDate: DateTime, processPerDay: Boolean): Unit = {
    time(logger, s"Link network update from ${previousDate} to ${newDate}") {
      try {
        var currentLinkNetworkStateDate = previousDate
        var skippedTiekamuRoadLinkChanges = Seq[TiekamuRoadLinkChange]()

        if (processPerDay) {
          while (currentLinkNetworkStateDate.isBefore(newDate)) {
            val nextDateTime = currentLinkNetworkStateDate.plusDays(1)
            skippedTiekamuRoadLinkChanges ++= updateLinkNetwork(currentLinkNetworkStateDate, nextDateTime)
            currentLinkNetworkStateDate = nextDateTime
          }
        } else {
          skippedTiekamuRoadLinkChanges ++= updateLinkNetwork(currentLinkNetworkStateDate, newDate)
        }

        if (skippedTiekamuRoadLinkChanges.nonEmpty) {
          // SkippedTiekamuRoadLinkChanges-yyyy-MM-dd-yyyy-MM-dd-yyyy-MM-dd:hh:mm:ss (SkippedTiekamuRoadLinkChanges-previousDate-newDate-currentTimeStamp)
          val s3SkippedChangeSetsName = s"SkippedTiekamuRoadLinkChanges-${previousDate.getYear}-${previousDate.getMonthOfYear}-${previousDate.getDayOfMonth}-" +
                                        s"${newDate.getYear}-${newDate.getMonthOfYear}-${newDate.getDayOfMonth}-${DateTime.now()}"
          val jsonSkippedChanges = Json(DefaultFormats).write(skippedTiekamuRoadLinkChanges.map(skippedChange => skippedTiekamuRoadLinkChangeToMap(skippedChange)))
          awsService.S3.saveFileToS3(bucketName, s3SkippedChangeSetsName, jsonSkippedChanges, "json") // save the error details to S3
        }
      } catch {
        case ex: ViiteException =>
          logger.error(s"Link network update from ${previousDate} to ${newDate} failed with ${ex}")
        case e: Exception =>
          logger.error(s"An error occurred while updating road link network from ${previousDate} to ${newDate}: ${e}")
      }
    }
  }

  def updateLinkNetwork(previousDate: DateTime, newDate: DateTime): Seq[TiekamuRoadLinkChange] = {
    time(logger, s"Updating road link network from ${previousDate} to ${newDate}") {
      val (viiteChangeSets, tiekamuRoadLinkChangeErrors, skippedTiekamuRoadLinkChanges) = createChangeSetsAndErrorsList(previousDate, newDate)

      // yyyy-MM-dd-yyyy-MM-dd
      val changeDateString =  s"${previousDate.getYear}-${previousDate.getMonthOfYear}-${previousDate.getDayOfMonth}-" +
                              s"${newDate.getYear}-${newDate.getMonthOfYear}-${newDate.getDayOfMonth}"

      if (tiekamuRoadLinkChangeErrors.nonEmpty) {
        val jsonErrorParts = Json(DefaultFormats).write(tiekamuRoadLinkChangeErrors.map(error => tiekamuRoadLinkChangeErrorToMap(error)))
        val s3ChangeSetErrorsName = s"${previousDate.getDayOfMonth}-${previousDate.getMonthOfYear}-${previousDate.getYear}-" +
                                    s"${newDate.getDayOfMonth}-${newDate.getMonthOfYear}-${newDate.getYear}-${DateTime.now()}-Errors"
        awsService.S3.saveFileToS3(bucketName, s3ChangeSetErrorsName, jsonErrorParts, "json") // save the error details to S3
      }

      if (viiteChangeSets.nonEmpty) {
        val jsonChangeSets = Json(DefaultFormats).write(viiteChangeSets.map(change => linkNetworkChangeToMap(change)))
        // Samuutus-yyyy-MM-dd-yyyy-MM-dd (Samuutus-previousDate-newDate)
        val changeSetNameForViiteDB = s"Samuutus-" + changeDateString
        if (changeSetNameForViiteDB.length > 32)
          throw ViiteException(s"ChangeSetName: ${changeSetNameForViiteDB} too long, maximum number of characters allowed is 32")

        // ViiteChangeSets-yyyy-MM-dd-yyyy-MM-dd-yyyy-MM-dd:hh:mm:ss (ViiteChangeSets-previousDate-newDate-currentTimeStamp)
        val changeSetNameForS3Bucket = "ViiteChangeSets" + "-" + changeDateString + "-" + DateTime.now()
        awsService.S3.saveFileToS3(bucketName, changeSetNameForS3Bucket, jsonChangeSets, "json")

        linkNetworkUpdater.persistLinkNetworkChanges(viiteChangeSets, changeSetNameForViiteDB, newDate, LinkGeomSource.NormalLinkInterface)
        logger.info(s"${viiteChangeSets.size} links updated successfully!")
      } else {
        logger.info(s"Zero links were updated!")
      }
      skippedTiekamuRoadLinkChanges
    }
  }
}

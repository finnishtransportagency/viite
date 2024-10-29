package fi.vaylavirasto.viite.dynamicnetwork

import fi.liikennevirasto.digiroad2.client.kgv.KgvRoadLink
import fi.liikennevirasto.digiroad2.util.LogUtils.time
import fi.liikennevirasto.digiroad2.util.ViiteProperties
import fi.liikennevirasto.viite.AwsService
import fi.liikennevirasto.viite.dao.{LinearLocation, LinearLocationDAO, Roadway, RoadwayDAO}
import fi.vaylavirasto.viite.geometry.GeometryUtils
import fi.vaylavirasto.viite.geometry.GeometryUtils.scaleToThreeDigits
import fi.vaylavirasto.viite.model.{LinkGeomSource, RoadPart}
import fi.vaylavirasto.viite.postgis.PostGISDatabase
import fi.vaylavirasto.viite.util.DateTimeFormatters.finnishDateFormatter
import fi.vaylavirasto.viite.util.ViiteException
import org.apache.hc.client5.http.classic.methods.HttpGet
import org.apache.hc.client5.http.impl.classic.{CloseableHttpClient,HttpClients}
import org.apache.hc.core5.http.{ClassicHttpResponse, HttpStatus}
import org.apache.hc.core5.http.io.HttpClientResponseHandler
import org.apache.hc.core5.http.io.entity.EntityUtils
import org.joda.time.DateTime
import org.json4s.DefaultFormats
import org.json4s.JsonAST.JArray
import org.json4s.jackson.Json
import org.json4s.jackson.JsonMethods.parse
import org.slf4j.{Logger, LoggerFactory}

import java.io.IOException
import scala.collection.mutable.ListBuffer

case class TiekamuRoadLinkChange(oldLinkId: String,
                                 oldStartM: Double,
                                 oldEndM: Double,
                                 newLinkId: String,
                                 newStartM: Double,
                                 newEndM: Double,
                                 digitizationChange: Boolean)

case class TiekamuRoadLinkChangeError(errorMessage: String,
                                      change: TiekamuRoadLinkChange,
                                      metaData: TiekamuRoadLinkErrorMetaData)

case class TiekamuRoadLinkErrorMetaData(roadPart: RoadPart,
                                        roadwayNumber:Long,
                                        linearLocationIds: Seq[Long],
                                        linkId: String)

class DynamicRoadNetworkService(linearLocationDAO: LinearLocationDAO, roadwayDAO: RoadwayDAO, val kgvClient: KgvRoadLink, awsService: AwsService, linkNetworkUpdater: LinkNetworkUpdater) {

  val bucketName: String = ViiteProperties.dynamicLinkNetworkS3BucketName

  def withDynTransaction[T](f: => T): T = PostGISDatabase.withDynTransaction(f)

  def withDynSession[T](f: => T): T = PostGISDatabase.withDynSession(f)

  implicit val formats = DefaultFormats
  val logger: Logger = LoggerFactory.getLogger(getClass)

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
                                    kgvRoadLinks: Seq[DynamicRoadNetworkService.this.kgvClient.roadLinkVersionsData.LinkType]): Seq[LinkNetworkChange] = {
    def createOldLinkInfo(kgvRoadLinks: Seq[DynamicRoadNetworkService.this.kgvClient.roadLinkVersionsData.LinkType], oldLinkId: String): LinkInfo = {
      val oldLinkInfo = {
        val kgvRoadLinkOld = kgvRoadLinks.find(rl => rl.linkId == oldLinkId)
        if (kgvRoadLinkOld.isDefined)
          LinkInfo(oldLinkId, scaleToThreeDigits(kgvRoadLinkOld.get.length), kgvRoadLinkOld.get.geometry)
        else
          throw ViiteException(s"Can't create change set without KGV road link data for oldLinkId: ${oldLinkId} ")
      }
      oldLinkInfo
    }

    def createNewLinkInfos(kgvRoadLinks: Seq[DynamicRoadNetworkService.this.kgvClient.roadLinkVersionsData.LinkType], distinctChangeInfosByNewLink: Seq[TiekamuRoadLinkChange]): Seq[LinkInfo] = {
      val newLinkInfo = distinctChangeInfosByNewLink.map(ch => {
        val kgvNewLink = kgvRoadLinks.find(newLink => ch.newLinkId == newLink.linkId)
        if (kgvNewLink.isDefined)
          LinkInfo(ch.newLinkId, scaleToThreeDigits(kgvNewLink.get.length), kgvNewLink.get.geometry)
        else
          throw ViiteException(s"Can't create change set without KGV road link data for newLinkId: ${ch.newLinkId} ")
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
          val oldInfo = createOldLinkInfo(kgvRoadLinks, oldLinkId)
          val newInfo = createNewLinkInfos(kgvRoadLinks, distinctChangeInfosByNewLink)
          val replaceInfo = createReplaceInfos(tiekamuRoadLinkChangeInfos)
          LinkNetworkChange(changeType, oldInfo, newInfo, replaceInfo)
        }
        viiteRoadLinkChange
      }).toSeq
      linkNetworkChanges
    }
  }

  def createTiekamuRoadLinkChangeSets(previousDate: DateTime, newDate: DateTime, activeLinearLocations: Seq[LinearLocation]): Seq[TiekamuRoadLinkChange] = {

    val client = HttpClients.createDefault()

    def extractTiekamuRoadLinkChanges(responseString: String): Seq[TiekamuRoadLinkChange] = {
      def getDigitizationChangeValue(newStartMValue: Double, newEndMValue: Double): Boolean = {
        if (newEndMValue < newStartMValue)
          true
        else
          false
      }

      //TODO REMOVE THESE WHEN IN PRODUCTION THIS IS FOR LOCAL DUMMY JSON
      //      val localParsedMockJson = parse(responseString.substring(1, responseString.length() - 1))
      //      val features = (localParsedMockJson \ "features").asInstanceOf[JArray].arr

      // Parse the JSON response
      val parsedJson = parse(responseString)

      // Extract the "features" field as a sequence of JValue
      val features = (parsedJson \ "features").asInstanceOf[JArray].arr
      // Map the JSON objects to the TiekamuRoadLinkChange class
      val tiekamuRoadLinkChanges = features.map { feature =>
        val properties = feature \ "properties"
        val newStartM = (properties \ "m_arvo_alku_kohdepvm").extract[Double]
        val newEndM = (properties \ "m_arvo_loppu_kohdepvm").extract[Double]
        TiekamuRoadLinkChange(
          (properties \ "link_id").extract[String],
          (properties \ "m_arvo_alku").extract[Double],
          (properties \ "m_arvo_loppu").extract[Double],
          (properties \ "link_id_kohdepvm").extract[String],
          if(newStartM > newEndM) newEndM else newStartM ,
          if(newEndM < newStartM) newStartM else newEndM,
          getDigitizationChangeValue(newStartM, newEndM)
        )
      }
      tiekamuRoadLinkChanges
    }

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

    /** Create a response handler,  with handleResponse implementation returning the
      * response body in Right as Seq[TiekamuRoadLinkChanges], or an Exception in Left. */
    def getResponseHandler(url: String) = {
      new HttpClientResponseHandler[Either[ViiteException, Seq[TiekamuRoadLinkChange]]] {
        @throws[IOException]
        override def handleResponse(response: ClassicHttpResponse): Either[ViiteException, Seq[TiekamuRoadLinkChange]]  = {
          if (response.getCode == HttpStatus.SC_OK) {
            val entity = response.getEntity
            val responseString = EntityUtils.toString(entity, "UTF-8")
            val tiekamuRoadLinkChanges = extractTiekamuRoadLinkChanges(responseString)
            Right(tiekamuRoadLinkChanges)
          } else {
            Left(ViiteException(s"Request $url returned HTTP ${response.getCode}"))
          }
        }
      }
    }

    def getTiekamuRoadlinkChanges(client: CloseableHttpClient): Seq[TiekamuRoadLinkChange] = {
      time(logger, "Creating TiekamuRoadLinkChange sets") {
        try {
          val previousDateFinnishFormat = finnishDateFormatter.print(previousDate)
          val newDateFinnishFormat = finnishDateFormatter.print(newDate)
          val tiekamuEndpoint = "https://devapi.testivaylapilvi.fi/viitekehysmuunnin/tiekamu?"
          val tiekamuDateParams = s"tilannepvm=${previousDateFinnishFormat}&asti=${newDateFinnishFormat}"
          val tiekamuReturnValueParam = "&palautusarvot=72"
          val url = tiekamuEndpoint ++ tiekamuDateParams ++ tiekamuReturnValueParam

          val request = new HttpGet(url)
          request.addHeader("accept", "application/geo+json")
          request.addHeader("X-API-Key", ViiteProperties.vkmApiKeyDev)

          time(logger, "Fetching change set data from Tiekamu") {
            try {
              val tiekamuRoadLinkChanges = client.execute(request, getResponseHandler(url))
              tiekamuRoadLinkChanges match {
                case Left(t) => throw t
                case Right(tiekamuRoadLinkChanges) => tiekamuRoadLinkChanges
              }
            } catch {
              case t: Throwable =>
                logger.warn(s"fetching $url failed. Throwable when fetching TiekamuRoadlinkChanges: ${t.getMessage}")
                Seq()
            }
          }
        } catch {
          case t: Throwable =>
            logger.warn(s"creating query URL failed: ${t.getMessage}")
            Seq()
        }
      }
    }

    time(logger, "Creating Viite road link change info sets") {
      val tiekamuRoadLinkChanges = getTiekamuRoadlinkChanges(client)
      // filter change infos so that only the ones that target links with road addresses are left
      val roadAddressedRoadLinkChanges = getChangeInfosWithRoadAddress(tiekamuRoadLinkChanges, activeLinearLocations)

      roadAddressedRoadLinkChanges
    }
  }

  def validateTiekamuRoadLinkChanges(tiekamuRoadLinkChanges: Seq[TiekamuRoadLinkChange], linearLocations: Seq[LinearLocation], kgvRoadLinks: Seq[DynamicRoadNetworkService.this.kgvClient.roadLinkVersionsData.LinkType]
                                    ): Seq[TiekamuRoadLinkChangeError] = {
    def existsConnectingOrderNumber(orderNumbers: Seq[Double], otherOrderNumbers: Seq[Double]): Boolean = {
      val min = orderNumbers.min
      val max = orderNumbers.max
      otherOrderNumbers.contains(min - 1) || otherOrderNumbers.contains(max + 1)
    }

    def existsConnectingRoadway(roadway: Roadway, otherRoadways: Seq[Roadway]): Boolean = {
      val startAddrM = roadway.addrMRange.start
      val endAddrM = roadway.addrMRange.end
      otherRoadways.map(_.addrMRange.start).contains(endAddrM) || otherRoadways.map(_.addrMRange.end).contains(startAddrM)
    }

    def checkRoadAddressContinuityForSingleRoadway(linearLocations: Seq[LinearLocation], change: TiekamuRoadLinkChange): Boolean = {
      val linLocsGroupedByLinkId = linearLocations.groupBy(_.linkId)
      val orderNumbers = linearLocationDAO.fetchByLinkIdAndMValueRange(change.oldLinkId, change.oldStartM, change.oldEndM).map(_.orderNumber)
      val otherOrderNumbers = linLocsGroupedByLinkId.filter(grp => grp._1 != change.oldLinkId).map(_._2.map(_.orderNumber)).toSeq.flatten
      existsConnectingOrderNumber(orderNumbers, otherOrderNumbers)
    }

    def checkRoadAddressContinuityBetweenRoadways(linearLocations: Seq[LinearLocation], change: TiekamuRoadLinkChange): Boolean = {
      val linLocsGroupedByLinkId = linearLocations.groupBy(_.linkId)
      val roadwayNumber = linearLocationDAO.fetchByLinkIdAndMValueRange(change.oldLinkId, change.oldStartM, change.oldEndM).head.roadwayNumber
      val roadway = roadwayDAO.fetchByRoadwayNumber(roadwayNumber).getOrElse(throw ViiteException(s"No Roadway found for linear location id: ${change.oldLinkId} startM: ${change.oldStartM} endM: ${change.oldEndM}"))
      val otherRoadwayNumbers = linLocsGroupedByLinkId.filterNot(grp => grp._1 == change.oldLinkId).map(_._2.map(_.roadwayNumber)).toSeq.flatten.toSet
      val otherRoadways = roadwayDAO.fetchAllByRoadwayNumbers(otherRoadwayNumbers)
      existsConnectingRoadway(roadway, otherRoadways)
    }

    def getMetaData(change: TiekamuRoadLinkChange, activeLinearLocations: Seq[LinearLocation]): TiekamuRoadLinkErrorMetaData = {
      val errorLink = change.oldLinkId
      val errorLinearLocations = activeLinearLocations.filter(ll => ll.linkId == errorLink)
      val errorRoadwayNumbers = errorLinearLocations.map(_.roadwayNumber)
      val errorRoadways = roadwayDAO.fetchAllByRoadwayNumbers(errorRoadwayNumbers.toSet)
      val errorRoadsParts = errorRoadways.map(r => {
        (r.roadPart)
      })
      TiekamuRoadLinkErrorMetaData(errorRoadsParts.head, errorRoadways.head.roadwayNumber, errorLinearLocations.map(_.id), errorLink)
    }

    time(logger, "Validating TiekamuRoadLinkChange sets") {
      var tiekamuRoadLinkChangeErrors = new ListBuffer[TiekamuRoadLinkChangeError]()
      tiekamuRoadLinkChanges.foreach(change => {
        val lengthOfChange = GeometryUtils.scaleToThreeDigits(change.oldEndM - change.oldStartM)
        val oldLinkId = change.oldLinkId
        val newLinkId = change.newLinkId
        val newStartM = change.newStartM
        val newEndM = change.newEndM
        val changesWithNewLinkId = tiekamuRoadLinkChanges.filter(ch => ch.newLinkId == newLinkId)
        val otherChangesWithSameNewLinkId = changesWithNewLinkId.filter(ch => ch.newStartM != newStartM && ch.newEndM != newEndM)

        val newlinkIdChangesLength = GeometryUtils.scaleToThreeDigits(changesWithNewLinkId.map(ch => ch.newEndM).max - changesWithNewLinkId.map(ch => ch.newStartM).min)
        val newLinkKGV = kgvRoadLinks.find(kgvLink => kgvLink.linkId == newLinkId).getOrElse(throw ViiteException(s"Missing new link from kgv. Cannot validate Tiekamu change infos without kgv road link. LinkId: ${newLinkId}, changeInfo: ${change}"))

        val linearLocationsWithOldLinkId = linearLocations.filter(_.linkId == oldLinkId)
        val roadAddressedLinkLength = GeometryUtils.scaleToThreeDigits(linearLocationsWithOldLinkId.map(_.endMValue).max - linearLocationsWithOldLinkId.map(_.startMValue).min)
        
        // check that the changeset actually has some changes in it
        if (change.oldLinkId == change.newLinkId &&
            change.oldStartM == change.newStartM &&
            change.oldEndM == change.newEndM &&
            !change.digitizationChange) {
          tiekamuRoadLinkChangeErrors += TiekamuRoadLinkChangeError("No changes found in the changeset ", change, getMetaData(change, linearLocations))
        }

        // check that there are no "partial" changes to road addressed links, i.e. only part of the link changes and the other part has no changes applied to it.
        if (lengthOfChange != roadAddressedLinkLength) {
          val allChangesWithOldLinkId = tiekamuRoadLinkChanges.filter(_.oldLinkId == oldLinkId)
          val combinedLengthOfChanges = GeometryUtils.scaleToThreeDigits(allChangesWithOldLinkId.map(och => och.oldEndM - och.oldStartM).sum)
          if (combinedLengthOfChanges != roadAddressedLinkLength)
            tiekamuRoadLinkChangeErrors += TiekamuRoadLinkChangeError("No partial changes allowed. The old link needs to have changes applied to the whole length of the old link", change, getMetaData(change, linearLocations))
        }
        else if (newlinkIdChangesLength != GeometryUtils.scaleToThreeDigits(newLinkKGV.length)) {
          tiekamuRoadLinkChangeErrors += TiekamuRoadLinkChangeError("No partial changes allowed. The new link needs to have changes applied to the whole length of the new link", change, getMetaData(change, linearLocations))
        }

        // if there are combined links (A + B = C)
        else if (otherChangesWithSameNewLinkId.nonEmpty) {
          val oldLinkIds = otherChangesWithSameNewLinkId.map(_.oldLinkId) ++ Seq(change.oldLinkId)
          val oldLinearLocations = linearLocations.filter(ll => oldLinkIds.contains(ll.linkId))
          val roadways = roadwayDAO.fetchAllByRoadwayNumbers(oldLinearLocations.map(_.roadwayNumber).toSet)
          // group the roadways with roadPart and track
          val roadGroups = roadways.groupBy(rw => (rw.roadPart, rw.track))
          // if there are change infos that combine two or more links but the road address is not homogeneous between those merging links then its an error
          val isRoadAddressHomogeneous = roadGroups.size < 2
          val changesInSameRoadway = roadGroups.head._2.map(_.roadwayNumber).distinct.size == 1 // used only when we already know roadGroup has only one item, thus handling .head only is enough
          isRoadAddressHomogeneous match {
            case false => tiekamuRoadLinkChangeErrors += TiekamuRoadLinkChangeError("Two or more links with non homogeneous road addresses (road number, road part number, track) cannot merge together", change, getMetaData(change, linearLocations))
            case true => {
              changesInSameRoadway match {
                case true => if (!checkRoadAddressContinuityForSingleRoadway(oldLinearLocations, change))
                  tiekamuRoadLinkChangeErrors += TiekamuRoadLinkChangeError("Road address not continuous, cannot merge links together", change, getMetaData(change, linearLocations))
                case false => if (!checkRoadAddressContinuityBetweenRoadways(oldLinearLocations, change))
                  tiekamuRoadLinkChangeErrors += TiekamuRoadLinkChangeError("Road address not continuous, cannot merge links together", change, getMetaData(change, linearLocations))
              }
            }
          }
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
    withDynTransaction {
      val activeLinearLocations = linearLocationDAO.fetchActiveLinearLocationsWithRoadAddresses() // get linear locations that are on active road addresses
      val tiekamuRoadLinkChanges = createTiekamuRoadLinkChangeSets(previousDate: DateTime, newDate: DateTime, activeLinearLocations)

      //get the new and the old linkIds to Set[String]
      val newLinkIds = tiekamuRoadLinkChanges.map(_.newLinkId).toSet
      val oldLinkIds = tiekamuRoadLinkChanges.map(_.oldLinkId).toSet
      // fetch roadLinks from KGV these are used for getting geometry and link lengths
      val kgvRoadLinks = kgvClient.roadLinkVersionsData.fetchByLinkIds(newLinkIds ++ oldLinkIds)

      val tiekamuRoadLinkChangeErrors = validateTiekamuRoadLinkChanges(tiekamuRoadLinkChanges, activeLinearLocations, kgvRoadLinks)
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
      val viiteChangeSets = createViiteLinkNetworkChanges(validTiekamuRoadLinkChanges, validActiveLinearLocations, kgvRoadLinks)
      (viiteChangeSets, tiekamuRoadLinkChangeErrors, skippedTiekamuRoadLinkChanges)
    }
  }

  def initiateLinkNetworkUpdates(previousDate: DateTime, newDate: DateTime): Unit = {
    time(logger, s"Link network update from ${previousDate} to ${newDate}") {
      try {
        var currentDateTime = previousDate
        var skippedTiekamuRoadLinkChanges = Seq[TiekamuRoadLinkChange]()

        while (currentDateTime.isBefore(newDate)) {
          val nextDateTime = currentDateTime.plusDays(1)
          skippedTiekamuRoadLinkChanges ++= updateLinkNetwork(currentDateTime, nextDateTime)
          currentDateTime = nextDateTime
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
package fi.vaylavirasto.viite.dynamicnetwork

import fi.liikennevirasto.digiroad2.client.kgv.KgvRoadLink
import fi.liikennevirasto.digiroad2.util.LogUtils.time
import fi.liikennevirasto.digiroad2.util.ViiteProperties
import fi.liikennevirasto.viite.AwsService
import fi.liikennevirasto.viite.dao.{LinearLocation, LinearLocationDAO, RoadwayDAO}
import fi.vaylavirasto.viite.geometry.GeometryUtils.scaleToThreeDigits
import fi.vaylavirasto.viite.geometry.Point
import fi.vaylavirasto.viite.model.{LinkGeomSource, RoadLink}
import fi.vaylavirasto.viite.postgis.PostGISDatabase
import fi.vaylavirasto.viite.util.DateTimeFormatters.finnishDateFormatter
import fi.vaylavirasto.viite.util.ViiteException
import org.apache.http.client.config.{CookieSpecs, RequestConfig}
import org.apache.http.client.methods.{CloseableHttpResponse, HttpGet, HttpRequestBase}
import org.apache.http.impl.client.{CloseableHttpClient, HttpClients}
import org.apache.http.util.EntityUtils
import org.joda.time.{DateTime, LocalDateTime}
import org.json4s.DefaultFormats
import org.json4s.JsonAST.JArray
import org.json4s.jackson.Json
import org.json4s.jackson.JsonMethods.parse
import org.slf4j.{Logger, LoggerFactory}

import scala.collection.mutable.ListBuffer

case class TiekamuRoadLinkChange(oldLinkId: String,
                                 oldStartM: Double,
                                 oldEndM: Double,
                                 newLinkId: String,
                                 newStartM: Double,
                                 newEndM: Double,
                                 digitizationChange: Boolean)

case class TiekamuRoadLinkChangeError(errorMessage: String, change: TiekamuRoadLinkChange)

class DynamicRoadNetworkService(linearLocationDAO: LinearLocationDAO, roadwayDAO: RoadwayDAO, val kgvClient: KgvRoadLink, awsService: AwsService, linkNetworkUpdater: LinkNetworkUpdater) {

  def withDynTransaction[T](f: => T): T = PostGISDatabase.withDynTransaction(f)

  def withDynSession[T](f: => T): T = PostGISDatabase.withDynSession(f)

  implicit val formats = DefaultFormats
  val logger: Logger = LoggerFactory.getLogger(getClass)

  protected def addHeaders(request: HttpRequestBase): Unit = {
    request.addHeader("accept", "application/geo+json")
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
      "roadNumber" -> data.roadNumber,
      "roadPartNumber" -> data.roadPartNumber
    )
  }

  def createRoadLinkChangeSets(previousDate: DateTime, newDate: DateTime): Seq[LinkNetworkChange] = {

    var response: CloseableHttpResponse = null
    val client = HttpClients.custom()
      .setDefaultRequestConfig(
        RequestConfig.custom()
          .setCookieSpec(CookieSpecs.STANDARD)
          .build())
      .build()

    def extractTiekamuRoadLinkChanges(responseString: String):Seq[TiekamuRoadLinkChange] = {
      def getDigitizationChangeValue(newStartMValue: Double, newEndMValue: Double): Boolean = {
        if (newEndMValue < newStartMValue)
          true
        else
          false
      }

      //TODO REMOVE THESE WHEN IN PRODUCTION THIS IS FOR LOCAL DUMMY JSON
      val localParsedMockJson = parse(responseString.substring(1, responseString.length() - 1))
      val features = (localParsedMockJson \ "features").asInstanceOf[JArray].arr

      // Parse the JSON response
      //val parsedJson = parse(responseString)

      // Extract the "features" field as a sequence of JValue
      //val features = (parsedJson \ "features").asInstanceOf[JArray].arr
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

    def createViiteLinkNetworkChanges(groupedByOldLinkId:  Map[String, Seq[TiekamuRoadLinkChange]], newLinkIds: Set[String], oldLinkIds:Set[String], activeLinearLocations: Seq[LinearLocation]):Seq[LinkNetworkChange] = {
      def getDigitizationChangedValue(change: TiekamuRoadLinkChange): Boolean = {
        if (change.newStartM > change.newEndM)
          true
        else
          false
      }

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
            ViiteMetaData(ll.id, ll.startMValue, ll.endMValue, ll.roadwayNumber, ll.orderNumber.toInt, roadway.roadNumber, roadway.roadPartNumber)
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

            if (digitizationChange.length > 1 )
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
        // fetch roadLinks from KGV these are used for getting geometry and link lengths
        val kgvRoadLinks = kgvClient.roadLinkVersionsData.fetchByLinkIds(newLinkIds ++ oldLinkIds)

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

    def validateTiekamuRoadLinkChanges(tiekamuRoadLinkChanges: Seq[TiekamuRoadLinkChange], linearLocations: Seq[LinearLocation]): Seq[TiekamuRoadLinkChangeError] = {
      time(logger, "Validating TiekamuRoadLinkChange sets") {
        var tiekamuRoadLinkChangeErrors = new ListBuffer[TiekamuRoadLinkChangeError]()
        tiekamuRoadLinkChanges.foreach(change => {
          val lengthOfChange = change.oldEndM - change.oldStartM
          val oldLinkId = change.oldLinkId
          val newLinkId = change.newLinkId
          val newStartM = change.newStartM
          val newEndM = change.newEndM
          val changesWithSameNewLinkId = tiekamuRoadLinkChanges.filter(ch => ch.newLinkId == newLinkId && ch.newStartM != newStartM && ch.newEndM != newEndM)

          val linearLocationsWithOldLinkId = linearLocations.filter(_.linkId == oldLinkId)
          val roadAddressedLinkLength = linearLocationsWithOldLinkId.map(_.endMValue).max - linearLocationsWithOldLinkId.map(_.startMValue).min

          // check that there are no "partial" changes to road addressed links, i.e. only part of the link changes and the other part has no changes applied to it.
          if (lengthOfChange != roadAddressedLinkLength) {
            val allChangesWithOldLinkId = tiekamuRoadLinkChanges.filter(_.oldLinkId == oldLinkId)
            val combinedLengthOfChanges = allChangesWithOldLinkId.map(och => och.oldEndM - och.oldStartM).sum
            if (combinedLengthOfChanges != roadAddressedLinkLength)
              tiekamuRoadLinkChangeErrors += TiekamuRoadLinkChangeError("No partial changes allowed. The target link needs to have changes applied to the whole length of the link", change)
          }

          // if there are combined links
          else if (changesWithSameNewLinkId.nonEmpty) {
            val oldLinkIds = changesWithSameNewLinkId.map(_.oldLinkId)
            val oldLinearLocations = linearLocations.filter(ll => oldLinkIds.contains(ll.linkId))
            val roadways = roadwayDAO.fetchAllByRoadwayNumbers(oldLinearLocations.map(_.roadwayNumber).toSet)
            // group the roadways with roadNumber, roadPartNumber and track
            val roadGroups = roadways.groupBy(rw => (rw.roadNumber, rw.roadPartNumber, rw.track))
            // if there are change infos that combine two or more links but the road address is not homogeneous between those merging links then its an error
            if (roadGroups.size > 1)
              tiekamuRoadLinkChangeErrors += TiekamuRoadLinkChangeError("Two or more links with non homogeneous road addresses (road number, road part number, track) cannot merge together", change)
          }
        })
        tiekamuRoadLinkChangeErrors
      }
    }

    def getTiekamuRoadlinkChanges(client: CloseableHttpClient): Seq[TiekamuRoadLinkChange] = {
      time(logger, "Creating TiekamuRoadLinkChange sets") {
        try {
          //val previousDateFinnishFormat = finnishDateFormatter.print(previousDate)
          //val newDateFinnishFormat = finnishDateFormatter.print(newDate)
          //val tiekamuEndpoint = "https://paikkatietodev.testivaylapilvi.fi/viitekehysmuunnin/tiekamu?"
          //val tiekamuDateParams = s"tilannepvm=${previousDateFinnishFormat}&asti=${newDateFinnishFormat}"
          //val tiekamuReturnValueParam = "&palautusarvot=72"
          //val url = tiekamuEndpoint ++ tiekamuDateParams ++ tiekamuReturnValueParam

          //TODO remove this local endpoint url
          val url = "http://localhost:3000/muutokset"

          val request = new HttpGet(url)
          addHeaders(request)
          time(logger, "Fetching change set data from Tiekamu") {
            response = client.execute(request)
          }

          val entity = response.getEntity
          val responseString = EntityUtils.toString(entity, "UTF-8")
          val tiekamuRoadLinkChanges = extractTiekamuRoadLinkChanges(responseString)

          tiekamuRoadLinkChanges
        } finally {
          if (response != null) {
            response.close()
          }
        }
      }
    }

    withDynTransaction {
      time(logger, "Creating Viite road link change info sets") {
        val tiekamuRoadLinkChanges = getTiekamuRoadlinkChanges(client)
        // get linear locations that are on active road addresses
        val activeLinearLocations = linearLocationDAO.fetchActiveLinearLocationsWithRoadAddresses()
        // filter change infos so that only the ones that target links with road addresses are left
        val roadAddressedRoadLinkChanges = getChangeInfosWithRoadAddress(tiekamuRoadLinkChanges, activeLinearLocations)
        // Tiekamu road link changes are validated in order to be sure that no harm will be done to the link network by accident.
        val errors = validateTiekamuRoadLinkChanges(roadAddressedRoadLinkChanges, activeLinearLocations)

        if (errors.nonEmpty)
          throw ViiteException(s"Creation of Viite road link change set failed: ${errors.size} errors found. ${errors}")

        //get the new and the old linkIds to Set[String]
        val newLinkIds = roadAddressedRoadLinkChanges.map(_.newLinkId).toSet
        val oldLinkIds = roadAddressedRoadLinkChanges.map(_.oldLinkId).toSet

        // group the changes by old linkId (oldLinkId, Seq[TiekamuRoadLinkChange])
        val groupedByOldLinkId = roadAddressedRoadLinkChanges.groupBy(changeInfo => changeInfo.oldLinkId)

        createViiteLinkNetworkChanges(groupedByOldLinkId, newLinkIds, oldLinkIds, activeLinearLocations)
      }
    }
  }

  def updateLinkNetwork(previousDate: DateTime, newDate: DateTime): Unit = {
    time(logger, s"Updating road link network from ${previousDate} to ${newDate}") {
      try {
        val viiteChangeSets = createRoadLinkChangeSets(previousDate: DateTime, newDate: DateTime)
        val jsonChangeSets = Json(DefaultFormats).write(viiteChangeSets.map(change => linkNetworkChangeToMap(change)))
        val changeSetName = s"Samuutus-${LocalDateTime.now()}" //TODO is this good enough naming/id convention to change sets
        val bucketName: String = ViiteProperties.dynamicLinkNetworkS3BucketName
        awsService.S3.saveFileToS3(bucketName, changeSetName, jsonChangeSets, "json")

        linkNetworkUpdater.persistLinkNetworkChanges(viiteChangeSets, changeSetName, newDate, LinkGeomSource.NormalLinkInterface)
      } catch {
        case ex: ViiteException =>
          logger.error(s"Updating road link network failed with ${ex}")
        case e: Exception =>
          logger.error(s"An error occurred while updating road link network: ${e}")
      }
    }
  }
}
package fi.vaylavirasto.viite.dynamicnetwork

import fi.liikennevirasto.digiroad2.client.kgv.KgvRoadLink
import fi.liikennevirasto.digiroad2.util.LogUtils.time
import fi.liikennevirasto.viite.dao.{LinearLocation, LinearLocationDAO, RoadwayDAO}
import fi.vaylavirasto.viite.geometry.Point
import fi.vaylavirasto.viite.model.{LinkGeomSource, RoadLink}
import fi.vaylavirasto.viite.postgis.PostGISDatabase
import fi.vaylavirasto.viite.util.DateTimeFormatters.finnishDateFormatter
import fi.vaylavirasto.viite.util.ViiteException
import org.apache.http.client.config.{CookieSpecs, RequestConfig}
import org.apache.http.client.methods.{CloseableHttpResponse, HttpGet, HttpRequestBase}
import org.apache.http.impl.client.{CloseableHttpClient, HttpClients}
import org.apache.http.util.EntityUtils
import org.joda.time.DateTime
import org.json4s.DefaultFormats
import org.json4s.JsonAST.JArray
import org.json4s.jackson.JsonMethods.parse
import org.slf4j.{Logger, LoggerFactory}

import scala.collection.mutable.ListBuffer

case class ChangeSet()

case class TiekamuRoadLinkChange(oldLinkId: String,
                                 oldStartM: Double,
                                 oldEndM: Double,
                                 newLinkId: String,
                                 newStartM: Double,
                                 newEndM: Double)

case class TiekamuRoadLinkChangeError(errorMessage: String, change: TiekamuRoadLinkChange)

class DynamicRoadNetworkService(linearLocationDAO: LinearLocationDAO, roadwayDAO: RoadwayDAO, val kgvClient: KgvRoadLink, linkNetworkUpdater: LinkNetworkUpdater) {

  def withDynTransaction[T](f: => T): T = PostGISDatabase.withDynTransaction(f)

  def withDynSession[T](f: => T): T = PostGISDatabase.withDynSession(f)

  implicit val formats = DefaultFormats
  val logger: Logger = LoggerFactory.getLogger(getClass)

  protected def addHeaders(request: HttpRequestBase): Unit = {
    request.addHeader("accept", "application/geo+json")
  }
  def createRoadLinkChangeSets(previousDate: DateTime, newDate: DateTime): Seq[LinkNetworkChange] = {

    var response: CloseableHttpResponse = null
    val client = HttpClients.custom()
      .setDefaultRequestConfig(
        RequestConfig.custom()
          .setCookieSpec(CookieSpecs.STANDARD)
          .build())
      .build()

    def viiteRoadLinkChangeToMap(change: LinkNetworkChange): Map[String, Any] = {
      Map(
        "changeType" -> change.changeType,
        "old" -> linkInfoToMap(change.oldLink),
        "new" -> change.newLinks.map(changeInfo => linkInfoToMap(changeInfo)),
        "replaceInfo" -> change.replaceInfo.map(replace => replaceInfoToMap(replace))
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
        "digitizationChange" -> replaceInfo.digitizationChange
      )
    }

    def extractTiekamuRoadLinkChanges(responseString: String):Seq[TiekamuRoadLinkChange] = {
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
        TiekamuRoadLinkChange(
          (properties \ "link_id").extract[String],
          (properties \ "m_arvo_alku").extract[Double],
          (properties \ "m_arvo_loppu").extract[Double],
          (properties \ "link_id_kohdepvm").extract[String],
          (properties \ "m_arvo_alku_kohdepvm").extract[Double],
          (properties \ "m_arvo_loppu_kohdepvm").extract[Double]
        )
      }
      tiekamuRoadLinkChanges
    }

    def createViiteLinkNetworkChanges(groupedByOldLinkId:  Map[String, Seq[TiekamuRoadLinkChange]], newLinkIds: Set[String], oldLinkIds:Set[String]):Seq[LinkNetworkChange] = {
      def getDigitizationChangedValue(change: TiekamuRoadLinkChange): Boolean = {
        if (change.newStartM > change.newEndM)
          true
        else
          false
      }
      time(logger, "Creating Viite LinkNetworkChange sets") {
        // fetch roadLinks from KGV
        // these are used for getting geometry and link lengths
        val kgvRoadLinks = kgvClient.roadLinkData.fetchByLinkIds(newLinkIds)
        val kgvOldRoadLinks = kgvClient.roadLinkVersionsData.fetchByLinkIds(oldLinkIds)

        val changeInfos = groupedByOldLinkId.map(group => {
          val oldLinkId = group._1
          val changeInfos = group._2
          val kgvLinkNew = kgvRoadLinks.find(rl => rl.linkId == changeInfos.head.newLinkId)
          val kgvRoadLinkOld = kgvOldRoadLinks.find(rl => rl.linkId == oldLinkId)

          val viiteRoadLinkChange = {
            val changeType = if (changeInfos.size > 1) "split" else "replace"
            if (kgvLinkNew.isDefined && kgvRoadLinkOld.isDefined) {
              val oldInfo = LinkInfo(oldLinkId, kgvRoadLinkOld.get.length, kgvRoadLinkOld.get.geometry)
              val newInfo = changeInfos.map(ch => LinkInfo(ch.newLinkId, kgvLinkNew.get.length, kgvLinkNew.get.geometry))
              val replaceInfo = changeInfos.map(ch => ReplaceInfo(ch.oldLinkId, ch.oldStartM, ch.oldEndM, ch.newLinkId, ch.newStartM, ch.newEndM, getDigitizationChangedValue(ch)))
              LinkNetworkChange(changeType, oldInfo, newInfo, replaceInfo)
            } else {
              throw ViiteException(s"Can't create change set without KGV road link data. OldLinkId: ${oldLinkId}, newLinkId: ${changeInfos.head.newLinkId}")
            }

          }
          viiteRoadLinkChange
        }).toSeq
        changeInfos
      }
    }

    /**
     * Viite is only interested in change infos that affect links that have road addressed roads on them.
     * Therefore we filter out all the unnecessary change infos i.e. unaddressed link change infos
     * */
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
        val filteredChangeInfos = getChangeInfosWithRoadAddress(tiekamuRoadLinkChanges, activeLinearLocations)
        // Tiekamu road link changes are validated in order to be sure that no harm will be done to the link network by accident.
        val errors = validateTiekamuRoadLinkChanges(filteredChangeInfos, activeLinearLocations)

        if (errors.nonEmpty)
          throw new ViiteException(s"Creation of Viite road link change set failed: ${errors.head}")

        //get the new and the old linkIds to Set[String]
        val newLinkIds = filteredChangeInfos.map(_.newLinkId).toSet
        val oldLinkIds = filteredChangeInfos.map(_.oldLinkId).toSet

        // group the changes by old linkId (oldLinkId, Seq[TiekamuRoadLinkChange])
        val groupedByOldLinkId = filteredChangeInfos.groupBy(changeInfo => changeInfo.oldLinkId)

        createViiteLinkNetworkChanges(groupedByOldLinkId, newLinkIds, oldLinkIds)
      }
    }
  }

  def updateLinkNetwork(previousDate: DateTime, newDate: DateTime): Unit = {
    logger.info(s"Link network update started, updating from ${previousDate} to ${newDate}")
    try {
      val viiteChangeSets = createRoadLinkChangeSets(previousDate: DateTime, newDate: DateTime)
      // TODO save the created change sets to DB or S3 Bucket
      // TODO naming convention to changeSetName
      linkNetworkUpdater.persistLinkNetworkChanges(viiteChangeSets, "changeSetNamePlaceHolder", newDate, LinkGeomSource.NormalLinkInterface)
    } catch {
      case ex: ViiteException =>
        logger.error(s"Creating road link change info sets failed with ${ex}")
      case e: Exception =>
        logger.error(s"An error occured while creating road link change info sets: ${e}")
    }
  }
}


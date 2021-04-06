package fi.liikennevirasto.viite

import fi.liikennevirasto.GeometryUtils
import fi.liikennevirasto.digiroad2._
import fi.liikennevirasto.digiroad2.asset.SideCode.{AgainstDigitizing, TowardsDigitizing}
import fi.liikennevirasto.digiroad2.asset._
import fi.liikennevirasto.digiroad2.client.vvh._
import fi.liikennevirasto.digiroad2.linearasset.RoadLink
import fi.liikennevirasto.digiroad2.postgis.PostGISDatabase
import fi.liikennevirasto.digiroad2.service.RoadLinkService
import fi.liikennevirasto.digiroad2.util.LogUtils.time
import fi.liikennevirasto.digiroad2.util.Track
import fi.liikennevirasto.viite.dao.AddressChangeType.{ReNumeration, Termination, Transfer, Unchanged}
import fi.liikennevirasto.viite.dao.CalibrationPointDAO.{CalibrationPointLocation, CalibrationPointType}
import fi.liikennevirasto.viite.dao.{RoadwayPointDAO, _}
import fi.liikennevirasto.viite.model.RoadAddressLink
import fi.liikennevirasto.viite.process.RoadAddressFiller.ChangeSet
import fi.liikennevirasto.viite.process._
import fi.liikennevirasto.viite.util.CalibrationPointsUtils
import org.joda.time.DateTime
import org.slf4j.LoggerFactory

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future}
import scala.util.control.NonFatal

class RoadAddressService(roadLinkService: RoadLinkService, roadwayDAO: RoadwayDAO, linearLocationDAO: LinearLocationDAO,
                         roadNetworkDAO: RoadNetworkDAO, roadwayPointDAO: RoadwayPointDAO, nodePointDAO: NodePointDAO,
                         junctionPointDAO: JunctionPointDAO, roadwayAddressMapper: RoadwayAddressMapper,
                         eventbus: DigiroadEventBus, frozenVVH: Boolean) {

  def withDynTransaction[T](f: => T): T = PostGISDatabase.withDynTransaction(f)

  def withDynSession[T](f: => T): T = PostGISDatabase.withDynSession(f)

  private val logger = LoggerFactory.getLogger(getClass)

  private def roadAddressLinkBuilder = new RoadAddressLinkBuilder(roadwayDAO, linearLocationDAO, new ProjectLinkDAO)

  val viiteVkmClient = new ViiteVkmClient
  class VkmException(response: String) extends RuntimeException(response)

  /**
    * Smallest mvalue difference we can tolerate to be "equal to zero". One micrometer.
    * See https://en.wikipedia.org/wiki/Floating_point#Accuracy_problems
    */
  val Epsilon = 1

  val defaultStreetNumber = 1

  private def fetchLinearLocationsByBoundingBox(boundingRectangle: BoundingRectangle, roadNumberLimits: Seq[(Int, Int)] = Seq()) = {
    val linearLocations = withDynSession {
      time(logger, "Fetch addresses") {
        linearLocationDAO.fetchLinearLocationByBoundingBox(boundingRectangle, roadNumberLimits)
      }
    }

    val historyRoadLinks = roadLinkService.getRoadLinksHistoryFromVVH(linearLocations.map(_.linkId).toSet)

    (linearLocations, historyRoadLinks)
  }

  /**
    * Fetches linear locations based on a bounding box and, if defined, within the road number limits supplied.
    *
    * @param boundingRectangle : BoundingRectangle - The search box
    * @param roadNumberLimits  : Seq[(Int, Int) - A sequence of upper and lower limits of road numbers
    * @return
    */
  def fetchLinearLocationByBoundingBox(boundingRectangle: BoundingRectangle, roadNumberLimits: Seq[(Int, Int)] = Seq()) = {
    withDynSession {
      time(logger, "Fetch addresses") {
        linearLocationDAO.fetchLinearLocationByBoundingBox(boundingRectangle, roadNumberLimits)
      }
    }
  }

  /**
    * Returns the roadways that match the supplied linear locations.
    *
    * @param linearLocations : Seq[LinearLocation] - The linear locations to search
    * @return
    */
  def getCurrentRoadAddresses(linearLocations: Seq[LinearLocation]) = {
    roadwayAddressMapper.getCurrentRoadAddressesByLinearLocation(linearLocations)
  }

  private def getRoadAddressLinks(boundingBoxResult: BoundingBoxResult): Seq[RoadAddressLink] = {
    val boundingBoxResultF =
      for {
        changeInfoF <- boundingBoxResult.changeInfoF
        roadLinksF <- boundingBoxResult.roadLinkF
        complementaryRoadLinksF <- boundingBoxResult.complementaryF
        linearLocationsAndHistoryRoadLinksF <- boundingBoxResult.roadAddressResultF
      } yield (changeInfoF, roadLinksF, complementaryRoadLinksF, linearLocationsAndHistoryRoadLinksF)

    val (changeInfos, roadLinks, complementaryRoadLinks, (linearLocations, historyRoadLinks)) =
      time(logger, "Fetch VVH bounding box data") {
        Await.result(boundingBoxResultF, Duration.Inf)
      }

    val allRoadLinks = roadLinks ++ complementaryRoadLinks

    //removed apply changes before adjusting topology since in future NLS will give perfect geometry and supposedly, we will not need any changes
    val (adjustedLinearLocations, changeSet) = if (frozenVVH) (linearLocations, Seq()) else RoadAddressFiller.adjustToTopology(allRoadLinks, linearLocations)
    if (!frozenVVH)
      eventbus.publish("roadAddress:persistChangeSet", changeSet)

    val roadAddresses = withDynSession {
      roadwayAddressMapper.getRoadAddressesByLinearLocation(adjustedLinearLocations)
    }
    RoadAddressFiller.fillTopology(allRoadLinks, roadAddresses)
  }

  def getRoadAddressWithRoadNumberAddress(road: Long): Seq[RoadAddress] = {
    withDynSession {
      roadwayAddressMapper.getRoadAddressesByRoadway(roadwayDAO.fetchAllByRoad(road))
    }
  }

  /**
    * Returns all road address links (combination between our roadway, linear location and vvh information) based on the limits imposed by the boundingRectangle and the roadNumberLimits.
    *
    * @param boundingRectangle : BoundingRectangle - The search box
    * @param roadNumberLimits  : Seq[(Int, Int) - A sequence of upper and lower limits of road numbers
    * @return
    */
  def getRoadAddressLinksByBoundingBox(boundingRectangle: BoundingRectangle, roadNumberLimits: Seq[(Int, Int)]): Seq[RoadAddressLink] = {

    val linearLocations = withDynSession {
      time(logger, "Fetch addresses") {
        linearLocationDAO.fetchLinearLocationByBoundingBox(boundingRectangle, roadNumberLimits)
      }
    }
    val linearLocationsLinkIds = linearLocations.map(_.linkId).toSet

    val boundingBoxResult = BoundingBoxResult(
      roadLinkService.getChangeInfoFromVVHF(linearLocationsLinkIds),
      Future((linearLocations, roadLinkService.getRoadLinksHistoryFromVVH(linearLocationsLinkIds))),
      Future(roadLinkService.getRoadLinksByLinkIdsFromVVH(linearLocationsLinkIds)),
      Future(Seq())
    )

    getRoadAddressLinks(boundingBoxResult)
  }

  /**
    * Returns all of our road addresses (combination of roadway + linear location information) based on the limits imposed by the boundingRectangle and the roadNumberLimits.
    *
    * @param boundingRectangle : BoundingRectangle - The search box
    * @param roadNumberLimits  : Seq[(Int, Int) - A sequence of upper and lower limits of road numbers
    * @return
    */
  def getRoadAddressesByBoundingBox(boundingRectangle: BoundingRectangle, roadNumberLimits: Seq[(Int, Int)]): Seq[RoadAddress] = {
    val linearLocations =
      time(logger, "Fetch linear locations by bounding box") {
        linearLocationDAO.fetchLinearLocationByBoundingBox(boundingRectangle, roadNumberLimits)
      }
    roadwayAddressMapper.getRoadAddressesByLinearLocation(linearLocations)
  }

  /**
    * Gets all the road addresses in the given bounding box, without VVH geometry. Also floating road addresses are filtered out.
    * Indicated to high zoom levels. If the road number limits are given it will also filter all road addresses by those limits.
    *
    * @param boundingRectangle The bounding box
    * @param roadNumberLimits  The road number limits
    * @return Returns all the filtered road addresses
    */
  def getRoadAddressesWithLinearGeometry(boundingRectangle: BoundingRectangle, roadNumberLimits: Seq[(Int, Int)]): Seq[RoadAddressLink] = {
    val roadAddresses = withDynTransaction {
      val linearLocations = linearLocationDAO.fetchLinearLocationByBoundingBox(boundingRectangle, roadNumberLimits)
      roadwayAddressMapper.getRoadAddressesByLinearLocation(linearLocations)
    }

    roadAddresses.map(roadAddressLinkBuilder.build)
  }

  /**
    * Returns all of our road addresses (combination of roadway + linear location information) that share the same linkIds as those supplied.
    *
    * @param linkIds : Seq[Long] - The linkId's to fetch information
    * @return
    */
  def getRoadAddressesByLinkIds(linkIds: Seq[Long]): Seq[RoadAddress] = {
    val linearLocations = linearLocationDAO.fetchByLinkId(linkIds.toSet)
    roadwayAddressMapper.getRoadAddressesByLinearLocation(linearLocations)
  }

  /**
    * Gets all the road addresses in the given municipality code.
    *
    * @param municipality The municipality code
    * @return Returns all the filtered road addresses
    */
  def getAllByMunicipality(municipality: Int, searchDate: Option[DateTime] = None): Seq[RoadAddressLink] = {
    val (roadLinks, _) = roadLinkService.getRoadLinksWithComplementaryAndChangesFromVVH(municipality)

    val linearLocations = withDynTransaction {
      time(logger, "Fetch addresses") {
        linearLocationDAO.fetchRoadwayByLinkId(roadLinks.map(_.linkId).toSet)
      }
    }
    val (adjustedLinearLocations, changeSet) = if (frozenVVH) (linearLocations, Seq()) else RoadAddressFiller.adjustToTopology(roadLinks, linearLocations)
    if (!frozenVVH) {
      //TODO we should think to update both servers with cache at the same time, and before the apply change batch that way we will not need to do any kind of changes here
      eventbus.publish("roadAddress:persistChangeSet", changeSet)
    }


    val roadAddresses = withDynTransaction {
      roadNetworkDAO.getLatestRoadNetworkVersionId match {
        case Some(roadNetworkId) => roadwayAddressMapper.getNetworkVersionRoadAddressesByLinearLocation(adjustedLinearLocations, roadNetworkId, searchDate)
        case _ => roadwayAddressMapper.getCurrentRoadAddressesByLinearLocation(adjustedLinearLocations, searchDate)
      }
    }

    roadAddresses.flatMap { ra =>
      val roadLink = roadLinks.find(rl => rl.linkId == ra.linkId)
      roadLink.map(rl => roadAddressLinkBuilder.build(rl, ra))
    }
  }

  /**
    * Gets all the existing road numbers at the current road network.
    *
    * @return Returns all the road numbers
    */
  def getRoadNumbers: Seq[Long] = {
    withDynSession {
      roadwayDAO.fetchAllCurrentRoadNumbers()
    }
  }

  def collectResult(dataType: String, inputData: Seq[Any], oldSeq: Seq[Map[String, Seq[Any]]] = Seq.empty[Map[String, Seq[Any]]]): Seq[Map[String, Seq[Any]]] = {
    oldSeq ++ Seq(Map((dataType, inputData)))
  }

  def getFirstOrEmpty(seq: Seq[Any]): Seq[Any] = {
    if (seq.isEmpty) {
      seq
    } else {
      Seq(seq.head)
    }
  }

  def getSearchResults(searchString: Option[String]): Seq[Map[String, Seq[Any]]] = {
    logger.debug("getSearchResults")
    val parsedInput = locationInputParser(searchString)
    val searchType = parsedInput.head._1
    val params = parsedInput.head._2
    var searchResult: Seq[Any] = null
    searchType match {
      case "road" => params.size match {
        case 1 =>
          // The params with type long can be LINKID, MTKID or roadNumber
          var searchResultPoint = roadLinkService.getMidPointByLinkId(params.head)
          var partialResultSeq = collectResult("linkId", Seq(searchResultPoint))
          searchResultPoint = roadLinkService.getRoadLinkMiddlePointByMtkId(params.head)
          partialResultSeq = collectResult("mtkId", Seq(searchResultPoint), partialResultSeq)
          searchResult = getFirstOrEmpty(getRoadAddressWithRoadNumberAddress(params.head).sortBy(address => (address.roadPartNumber, address.startAddrMValue)))
          collectResult("road", searchResult, partialResultSeq)
        case 2 => collectResult("road", getFirstOrEmpty(getRoadAddressWithRoadNumberParts(params.head, Set(params(1)), Set(Track.Combined, Track.LeftSide, Track.RightSide)).sortBy(address => (address.roadPartNumber, address.startAddrMValue))))
        case 3 | 4 =>
          val roadPart = params(1)
          val addressM = params(2)
          val track = if (params.size == 4) Some(params(3)) else None
          val ralOption = getRoadAddressLink(params.head, roadPart, addressM, track)
          ralOption.foldLeft(Seq.empty[Map[String, Seq[Any]]])((partialResultSeq, ral) => {
            val roadAddressLinkMValueLengthPercentageFactor = (addressM - ral.startAddressM.toDouble) / (ral.endAddressM.toDouble - ral.startAddressM)
            val geometryLength = ral.endMValue - ral.startMValue
            val geometryMeasure = roadAddressLinkMValueLengthPercentageFactor * geometryLength
            val point = ral match {
              case r if (r.startAddressM.toDouble == addressM && r.sideCode == TowardsDigitizing) || (r.endAddressM == addressM && r.sideCode == AgainstDigitizing) =>
                r.geometry.headOption
              case r if (r.startAddressM.toDouble == addressM && r.sideCode == AgainstDigitizing) || (r.endAddressM == addressM && r.sideCode == TowardsDigitizing) =>
                r.geometry.lastOption
              case r =>
                val mValue: Double = r.sideCode match {
                  case AgainstDigitizing => geometryLength - geometryMeasure
                  case _ => geometryMeasure
                }
                GeometryUtils.calculatePointFromLinearReference(r.geometry, mValue)
            }
            collectResult("roadM", Seq(point), partialResultSeq)
          })
        case _ => Seq.empty[Map[String, Seq[Any]]]
      }
      case "street" =>
        val address = searchString.getOrElse("").split(", ")
        val municipalityId = withDynSession {
          if (address.size > 1) MunicipalityDAO.getMunicipalityIdByName(address.last.trim).headOption.map(_._1) else None
        }
        val (streetName, streetNumber) = address.head.split(" ").partition(_.matches(("\\D+")))
        searchResult = viiteVkmClient.get("/viitekehysmuunnin/muunna", Map(("kuntakoodi", municipalityId.getOrElse("").toString), ("katunimi", streetName.mkString("%20")), ("katunumero", streetNumber.headOption.getOrElse(defaultStreetNumber.toString)))) match {
          case Left(result) => Seq(result)
          case Right(error) => throw new VkmException(error.toString)
        }
        collectResult("street", searchResult)
      case _ => Seq.empty[Map[String, Seq[Any]]]
    }
  }

  def locationInputParser(searchStringOption: Option[String]): Map[String, Seq[Long]] = {
    val searchString = searchStringOption.getOrElse("")
    val numRegex = """(\d+)""".r
    val nums = numRegex.findAllIn(searchString).map(_.toLong).toSeq
    val letterRegex = """([A-Za-zÀ-ÿ])""".r
    val letters = letterRegex.findFirstIn(searchString)

    val searchType = if (letters.isEmpty) "road" else "street"
    Map((searchType, nums))
  }

  /**
    * Gets road address link in the same road number, roadPart and addressM.
    *
    * @param road      The road number
    * @param roadPart  The roadPart
    * @param addressM  The addressM that is in between the returned RoadAddress
    * @return Returns RoadAddressLink in track 0 or 1 which contains dynamically calculated startAddressM and endAddressM
    *         and includes detailed geometry in that link fetched dynamically from VVH
    */
  def getRoadAddressLink(road: Long, roadPart: Long, addressM: Long, track: Option[Long] = None): Option[RoadAddressLink] = {
    val linearLocations = withDynSession {
      time(logger, "Fetch addresses") {
        linearLocationDAO.fetchByRoadAddress(road, roadPart, addressM, track)
      }
    }
    val linearLocationsLinkIds = linearLocations.map(_.linkId).toSet
    val roadAddresses = getRoadAddressForSearch(road, roadPart, addressM, track)
    val roadLinks = roadLinkService.getRoadLinksByLinkIdsFromVVH(linearLocationsLinkIds)
    val rals = RoadAddressFiller.fillTopology(roadLinks, roadAddresses)
    val filteredRals = rals.filter(al => al.startAddressM <= addressM && al.endAddressM >= addressM && (al.startAddressM != 0 || al.endAddressM != 0))
    val ral = filteredRals.filter(al => (track.nonEmpty && track.contains(al.trackCode)) || al.trackCode != Track.LeftSide.value)
    ral.headOption
  }

  /**
    * Gets road address in the same road number, roadPart and addressM.
    *
    * @param road   The road number
    * @param roadPart The roadPart
    * @param addressM The addressM that is in between the returned RoadAddress start and end addresses
    * @return Returns all the filtered road addresses. Note. RoadAddress has normally shorter length than roadway
    */
  def getRoadAddressForSearch(road: Long, roadPart: Long, addressM: Long, track: Option[Long] = None): Seq[RoadAddress] = {
    withDynSession {
      val roadways = roadwayDAO.fetchAllBySectionAndAddresses(road, roadPart, Some(addressM), Some(addressM), track)
      val roadAddresses = roadwayAddressMapper.getRoadAddressesByRoadway(roadways).sortBy(_.startAddrMValue)
      roadAddresses.filter(ra => (track.isEmpty || track.contains(ra.track.value)) && ra.startAddrMValue <= addressM && ra.endAddrMValue >= addressM)
    }
  }

  /**
    * Gets all the road addresses in the same road number, road part number with start address less that
    * the given address measure. If trackOption parameter is given it will also filter by track code.
    *
    * @param road        The road number
    * @param roadPart    The road part number
    * @param addressM    The road address at road number and road part
    * @param trackOption Optional track code
    * @return Returns all the filtered road addresses
    */
  def getRoadAddress(road: Long, roadPart: Long, addressM: Long, trackOption: Option[Track]): Seq[RoadAddress] = {
    withDynSession {
      val roadways = trackOption match {
        case Some(track) =>
          if (addressM != 0)
            roadwayDAO.fetchAllBySectionTrackAndAddresses(road, roadPart, track, None, Some(addressM))
          else
            roadwayDAO.fetchAllBySectionTrackAndAddresses(road, roadPart, track, None, None)
        case _ =>
          if (addressM != 0)
            roadwayDAO.fetchAllBySectionAndAddresses(road, roadPart, None, Some(addressM))
          else
            roadwayDAO.fetchAllBySectionAndAddresses(road, roadPart, None, None)
      }

      val roadAddresses = roadwayAddressMapper.getRoadAddressesByRoadway(roadways).sortBy(_.startAddrMValue)
      if (addressM > 0) {
        roadAddresses.filter(ra => ra.startAddrMValue >= addressM || ra.endAddrMValue == addressM)
      }
      else Seq(roadAddresses.head)
    }
  }

  /**
    * Gets all the road addresses in the same road number and track codes.
    * If the track sequence is empty will filter only by road number
    *
    * @param road   The road number
    * @param tracks The set of track codes
    * @return Returns all the filtered road addresses
    */
  def getRoadAddressWithRoadNumber(road: Long, tracks: Set[Track]): Seq[RoadAddress] = {
    withDynSession {
      val roadways = if (tracks.isEmpty)
        roadwayDAO.fetchAllByRoad(road)
      else
        roadwayDAO.fetchAllByRoadAndTracks(road, tracks)

      roadwayAddressMapper.getRoadAddressesByRoadway(roadways)
    }
  }

  /**
    * Gets all the road addresses in the same road number, road parts and track codes.
    * If the road part number sequence or track codes sequence is empty
    *
    * @param road      The road number
    * @param roadParts The set of road part numbers
    * @param tracks    The set of track codes
    * @return Returns all the filtered road addresses
    */
  def getRoadAddressWithRoadNumberParts(road: Long, roadParts: Set[Long], tracks: Set[Track]): Seq[RoadAddress] = {
    withDynSession {
      val roadways = roadwayDAO.fetchAllBySectionsAndTracks(road, roadParts, tracks)
      roadwayAddressMapper.getRoadAddressesByRoadway(roadways)
    }
  }

  /**
    * Gets all the road addresses in the same road number, road parts and track codes.
    * If the road part number sequence or track codes sequence is empty
    *
    * @param road         The road number
    * @param part         The road part
    * @param withHistory  The optional parameter that allows the search to also look for historic links
    * @param fetchOnlyEnd The optional parameter that allows the search for the link with bigger endAddrM value
    * @return Returns all the filtered road addresses
    */
  def getRoadAddressWithRoadAndPart(road: Long, part: Long, withHistory: Boolean = false, fetchOnlyEnd: Boolean = false, newTransaction: Boolean = true): Seq[RoadAddress] = {
    if (newTransaction)
      withDynSession {
        val roadways = roadwayDAO.fetchAllByRoadAndPart(road, part, withHistory, fetchOnlyEnd)
        roadwayAddressMapper.getRoadAddressesByRoadway(roadways)
      }
    else {
      val roadways = roadwayDAO.fetchAllByRoadAndPart(road, part, withHistory, fetchOnlyEnd)
      roadwayAddressMapper.getRoadAddressesByRoadway(roadways)
    }
  }

  /**
    * Gets all the road addresses in between the given linear location.
    * - If only given the start measure, will return all the road addresses with the start and end measure in between ${startMOption} or start measure equal or greater than ${startMOption}
    * - If only given the end measure, will return all the road addresses with the start and end measure in between ${endMOption} or end measure equal or less than ${endMOption}
    * - If any of the measures are given, will return all the road addresses on the given road link id
    *
    * @param linkId       The link identifier of the linear location
    * @param startMOption The start measure of the linear location
    * @param endMOption   The end measure of the linear location
    * @return Returns all the filtered road addresses
    */
  def getRoadAddressWithLinkIdAndMeasure(linkId: Long, startMOption: Option[Double], endMOption: Option[Double]): Seq[RoadAddress] = {
    withDynSession {
      val linearLocations = linearLocationDAO.fetchRoadwayByLinkId(Set(linkId))
      val roadAddresses = roadwayAddressMapper.getRoadAddressesByLinearLocation(linearLocations)

      (startMOption, endMOption) match {
        case (Some(startM), Some(endM)) =>
          roadAddresses.filter(ra => ra.linkId == linkId && ra.isBetweenMeasures(startM, endM))
        case (Some(startM), _) =>
          roadAddresses.filter(ra => ra.linkId == linkId && (ra.startMValue >= startM || ra.isBetweenMeasures(startM)))
        case (_, Some(endM)) =>
          roadAddresses.filter(ra => ra.linkId == linkId && (ra.endMValue <= endM || ra.isBetweenMeasures(endM)))
        case _ =>
          roadAddresses.filter(ra => ra.linkId == linkId)
      }
    }
  }

  /**
    * Gets all the road address in the given road number and road part
    *
    * @param roadNumber     The road number
    * @param roadPartNumber The road part number
    * @return Returns road addresses filtered given section
    */
  def getRoadAddressesFiltered(roadNumber: Long, roadPartNumber: Long): Seq[RoadAddress] = {
    if (PostGISDatabase.isWithinSession) {
      val roadwayAddresses = roadwayDAO.fetchAllBySection(roadNumber, roadPartNumber)
      roadwayAddressMapper.getRoadAddressesByRoadway(roadwayAddresses)
    } else {
      withDynSession {
        val roadwayAddresses = roadwayDAO.fetchAllBySection(roadNumber, roadPartNumber)
        roadwayAddressMapper.getRoadAddressesByRoadway(roadwayAddresses)
      }
    }
  }

  /**
    * Gets all the valid road address in the given road number and project start date
    *
    * @param roadNumber The road number
    * @param startDate  The project start date
    * @return Returns road addresses filtered given section
    */
  def getValidRoadAddressParts(roadNumber: Long, startDate: DateTime): Seq[Long] = {
    withDynSession {
      roadwayDAO.getValidRoadParts(roadNumber, startDate)
    }
  }

  /**
    * Gets all the previous road address part in the given road number and road part number
    *
    * @param roadNumber The road number
    * @param roadPart   The road part number
    * @return Returns previous parts in road number, if they exist
    */
  def getPreviousRoadAddressPart(roadNumber: Long, roadPart: Long): Option[Long] = {
    withDynSession {
      roadwayDAO.fetchPreviousRoadPartNumber(roadNumber, roadPart)
    }
  }

  /**
    * Gets all the road addresses in given road number, road part number and between given address measures.
    * The road address measures should be in [startAddrM, endAddrM]
    *
    * @param roadNumber     The road number
    * @param roadPartNumber The road part number
    * @param startAddrM     The start address measure
    * @param endAddrM       The end address measure
    * @return Returns road addresses filtered by road section and address measures
    */
  def getRoadAddressesFiltered(roadNumber: Long, roadPartNumber: Long, startAddrM: Long, endAddrM: Long): Seq[RoadAddress] = {
    withDynSession {
      val roadwayAddresses = roadwayDAO.fetchAllBySectionAndAddresses(roadNumber, roadPartNumber, Some(startAddrM), Some(endAddrM))
      val roadAddresses = roadwayAddressMapper.getRoadAddressesByRoadway(roadwayAddresses)
      roadAddresses.filter(ra => ra.isBetweenAddresses(startAddrM, endAddrM))
    }
  }

  /**
    * Gets all the road addresses on top of given road links.
    *
    * @param linkIds The set of road link identifiers
    * @return Returns all filtered the road addresses
    */
  def getRoadAddressByLinkIds(linkIds: Set[Long]): Seq[RoadAddress] = {
    withDynTransaction {
      val linearLocations = linearLocationDAO.fetchRoadwayByLinkId(linkIds)
      val roadAddresses = roadwayAddressMapper.getRoadAddressesByLinearLocation(linearLocations)
      roadAddresses.filter(ra => linkIds.contains(ra.linkId))
    }
  }

  /**
    * Returns all of our road addresses (combination of roadway + linear location information) that share the same roadwayId as those supplied.
    *
    * @param roadwayIds : Seq[Long] - The roadway Id's to fetch
    * @return
    */
  def getRoadAddressesByRoadwayIds(roadwayIds: Seq[Long]): Seq[RoadAddress] = {
    val roadways = roadwayDAO.fetchAllByRoadwayId(roadwayIds)
    roadwayAddressMapper.getRoadAddressesByRoadway(roadways)
  }

  def getChanged(sinceDate: DateTime, untilDate: DateTime): Seq[ChangedRoadAddress] = {
    withDynSession {
      val roadwayAddresses = roadwayDAO.fetchAllByDateRange(sinceDate, untilDate)
      val roadAddresses = roadwayAddressMapper.getRoadAddressesByRoadway(roadwayAddresses)

      val roadLinks = roadLinkService.getRoadLinksAndComplementaryFromVVH(roadAddresses.map(_.linkId).toSet)
      val roadLinksWithoutWalkways = roadLinks.filterNot(_.linkType == CycleOrPedestrianPath).filterNot(_.linkType == TractorRoad)

      roadAddresses.flatMap { roadAddress =>
        roadLinksWithoutWalkways.find(_.linkId == roadAddress.linkId).map { roadLink =>
          ChangedRoadAddress(
            roadAddress = roadAddress.copyWithGeometry(GeometryUtils.truncateGeometry3D(roadLink.geometry, roadAddress.startMValue, roadAddress.endMValue)),
            link = roadLink
          )
        }
      }
    }
  }

  def getUpdatedRoadways(sinceDate: DateTime): Either[String, Seq[Roadway]] = {
    withDynSession {
      try {
        val roadways = roadwayDAO.fetchUpdatedSince(sinceDate)
        Right(roadways)
      } catch {
        case e if NonFatal(e) =>
          logger.error("Failed to fetch updated roadways.", e)
          Left(e.getMessage)
      }
    }
  }

  def getUpdatedLinearLocations(sinceDate: DateTime): Either[String, Seq[LinearLocation]] = {
    withDynSession {
      try {
        val linearLocations = linearLocationDAO.fetchUpdatedSince(sinceDate)
        Right(linearLocations)
      } catch {
        case e if NonFatal(e) =>
          logger.error("Failed to fetch updated linear locations.", e)
          Left(e.getMessage)
      }
    }

  }

  /**
    * Gets all the road addresses errors (excluding history)
    *
    * @param includesHistory - default value = false to exclude history values
    * @return Returns all filtered road address errors
    */
  def getRoadAddressErrors(includesHistory: Boolean = false): List[AddressConsistencyValidator.AddressErrorDetails] = {
    withDynSession {
      roadwayDAO.fetchAllRoadAddressErrors(includesHistory)
    }
  }

  /**
    * returns road addresses with link-id currently does not include terminated links which it cannot build roadaddress with out geometry
    *
    * @param linkId link-id
    * @return roadaddress[]
    */
  def getRoadAddressLink(linkId: Long): Seq[RoadAddressLink] = {

    val roadlinks = roadLinkService.getAllVisibleRoadLinksFromVVH(Set(linkId))

    val roadAddresses = withDynSession {
      val linearLocations = linearLocationDAO.fetchRoadwayByLinkId(Set(linkId))

      roadwayAddressMapper.getRoadAddressesByLinearLocation(linearLocations)
    }

    RoadAddressFiller.fillTopology(roadlinks, roadAddresses).filter(_.linkId == linkId)

  }

  def sortRoadWayWithNewRoads(originalLinearLocationGroup: Map[Long, Seq[LinearLocation]], newLinearLocations: Seq[LinearLocation]): Map[Long, Seq[LinearLocation]] = {
    val newLinearLocationsGroup = newLinearLocations.groupBy(_.roadwayNumber)
    originalLinearLocationGroup.flatMap {
      case (roadwayNumber, locations) =>
        val linearLocationsForRoadNumber = newLinearLocationsGroup.getOrElse(roadwayNumber, Seq())
        linearLocationsForRoadNumber.size match {
          case 0 => Seq() //Doesn't need to reorder or to expire any link for this roadway
          case _ =>
            Map(roadwayNumber ->
              (locations ++ linearLocationsForRoadNumber)
                .sortBy(_.orderNumber)
                .foldLeft(Seq[LinearLocation]()) {
                  case (list, linearLocation) =>
                    list ++ Seq(linearLocation.copy(orderNumber = list.size + 1))
                })
        }
    }
  }

  def updateChangeSet(changeSet: ChangeSet): Unit = {

    withDynTransaction {
      //Getting the linearLocations before the drop
      val linearByRoadwayNumber = linearLocationDAO.fetchByRoadways(changeSet.newLinearLocations.map(_.roadwayNumber).toSet)

      val roadwayCheckSum = linearByRoadwayNumber.groupBy(_.roadwayNumber).mapValues(l => l.map(_.orderNumber).sum)

      //Expire linear locations
      linearLocationDAO.expireByIds(changeSet.droppedSegmentIds)

      //Update all the linear location measures
      linearLocationDAO.updateAll(changeSet.adjustedMValues, "adjustTopology")

      val existingLinearLocations = linearByRoadwayNumber.filterNot(l => changeSet.droppedSegmentIds.contains(l.id))
      val existingLinearLocationsGrouped = existingLinearLocations.groupBy(_.roadwayNumber)

      //Create the new linear locations and update the road order
      val orderedLinearLocations = sortRoadWayWithNewRoads(existingLinearLocationsGrouped, changeSet.newLinearLocations)

      existingLinearLocationsGrouped.foreach {
        case (roadwayNumber, existingLinearLocations) =>
          val roadwayLinearLocations = orderedLinearLocations.getOrElse(roadwayNumber, Seq())
          if (roadwayCheckSum.getOrElse(roadwayNumber, -1) != roadwayLinearLocations.map(_.orderNumber).sum) {
            linearLocationDAO.expireByIds(existingLinearLocations.map(_.id).toSet)
            linearLocationDAO.create(roadwayLinearLocations)
            handleCalibrationPoints(roadwayLinearLocations, username = "applyChanges")
          }
      }

      linearLocationDAO.create(changeSet.newLinearLocations.map(l => l.copy(id = NewIdValue)))
      handleCalibrationPoints(changeSet.newLinearLocations, username = "applyChanges")
      //TODO Implement the missing at user story VIITE-1596
    }

  }

  def getRoadAddressLinks(boundingRectangle: BoundingRectangle, roadNumberLimits: Seq[(Int, Int)],
                          everything: Boolean = false, publicRoads: Boolean = false): Seq[RoadAddressLink] = {

    val boundingBoxResult = BoundingBoxResult(
      roadLinkService.getChangeInfoFromVVHF(boundingRectangle, Set()),
      //Should fetch all the road types
      Future(fetchLinearLocationsByBoundingBox(boundingRectangle)),
      Future(roadLinkService.getRoadLinksFromVVH(boundingRectangle, roadNumberLimits, Set(), everything, publicRoads)),
      Future(roadLinkService.getComplementaryRoadLinksFromVVH(boundingRectangle, Set()))
    )
    getRoadAddressLinks(boundingBoxResult)
  }


  def handleProjectCalibrationPointChanges(linearLocations: Iterable[LinearLocation], username: String = "-", terminated: Seq[ProjectRoadLinkChange] = Seq()): Unit = {
    def handleTerminatedCalibrationPointRoads(pls: Seq[ProjectRoadLinkChange]) = {
      val ids: Set[Long] = pls.flatMap(p => CalibrationPointDAO.fetchIdByRoadwayNumberSection(p.originalRoadwayNumber, p.originalStartAddr, p.originalEndAddr)).toSet
      if (ids.nonEmpty) {
        logger.info(s"Expiring calibration point ids: " + ids.mkString(", "))
        CalibrationPointDAO.expireById(ids)
      }
    }

    handleTerminatedCalibrationPointRoads(terminated)
    handleCalibrationPoints(linearLocations, username)
  }

  def handleCalibrationPoints(linearLocations: Iterable[LinearLocation], username: String = "-"): Unit = {

    val startCalibrationPointsToCheck = linearLocations.filter(_.startCalibrationPoint.isDefined)
    val endCalibrationPointsToCheck = linearLocations.filter(_.endCalibrationPoint.isDefined)

    // Fetch current linear locations and check which calibration points should be expired
    val currentCPs = CalibrationPointDAO.fetchByLinkId(linearLocations.map(l => l.linkId))
    val currentStartCP = currentCPs.filter(_.startOrEnd == CalibrationPointLocation.StartOfLink)
    val currentEndCP = currentCPs.filter(_.startOrEnd == CalibrationPointLocation.EndOfLink)
    val startCPsToBeExpired = currentStartCP.filter(c => !startCalibrationPointsToCheck.exists(sc => sc.linkId == c.linkId && (sc.startCalibrationPoint.isDefined && c.startOrEnd == CalibrationPointLocation.StartOfLink)))
    val endCPsToBeExpired = currentEndCP.filter(c => !endCalibrationPointsToCheck.exists(sc => sc.linkId == c.linkId && (sc.endCalibrationPoint.isDefined && c.startOrEnd == CalibrationPointLocation.EndOfLink)))

    // Expire calibration points not applied by project road changes logic
    startCPsToBeExpired.foreach {
      ll =>
        val cal = CalibrationPointDAO.fetch(ll.linkId, CalibrationPointLocation.StartOfLink.value)
        if (cal.isDefined) {
          CalibrationPointDAO.expireById(Set(cal.get.id))
          logger.info(s"Expiring calibration point id:" + cal.get.id)
        } else {
          logger.error(s"Failed to expire start calibration point for link id: ${ll.linkId}")
        }
    }
    endCPsToBeExpired.foreach {
      ll =>
        val cal = CalibrationPointDAO.fetch(ll.linkId, CalibrationPointLocation.EndOfLink.value)
        if (cal.isDefined) {
          CalibrationPointDAO.expireById(Set(cal.get.id))
          logger.info(s"Expiring calibration point id:" + cal.get.id)
        } else {
          logger.error(s"Failed to expire end calibration point for link id: ${ll.linkId}")
        }
    }

    // Check other calibration points
    startCalibrationPointsToCheck.foreach {
      cal =>
        val roadwayPointId =
          roadwayPointDAO.fetch(cal.roadwayNumber, cal.startCalibrationPoint.addrM.get) match {
            case Some(roadwayPoint) =>
              roadwayPoint.id
            case _ =>
              logger.info(s"Creating roadway point for start calibration point: roadway number: ${cal.roadwayNumber}, address: ${cal.startCalibrationPoint.addrM.get}")
              roadwayPointDAO.create(cal.roadwayNumber, cal.startCalibrationPoint.addrM.get, username)
          }
        CalibrationPointsUtils.createCalibrationPointIfNeeded(roadwayPointId, cal.linkId, CalibrationPointLocation.StartOfLink, cal.startCalibrationPointType, username)
    }
    endCalibrationPointsToCheck.foreach {
      cal =>
        val roadwayPointId =
          roadwayPointDAO.fetch(cal.roadwayNumber, cal.endCalibrationPoint.addrM.get) match {
            case Some(roadwayPoint) =>
              roadwayPoint.id
            case _ =>
              logger.info(s"Creating roadway point for end calibration point: roadway number: ${cal.roadwayNumber}, address: ${cal.endCalibrationPoint.addrM.get}")
              roadwayPointDAO.create(cal.roadwayNumber, cal.endCalibrationPoint.addrM.get, username)
          }
        CalibrationPointsUtils.createCalibrationPointIfNeeded(roadwayPointId, cal.linkId, CalibrationPointLocation.EndOfLink, cal.endCalibrationPointType, username)
    }
  }

  /**
    * Should only expire junction calibration points
    *
    * @param junctionPoints
    */
  def expireObsoleteCalibrationPointsInJunctions(junctionPoints: Seq[JunctionPoint]): Unit = {
    val obsoleteCalibrationPointsIds = CalibrationPointDAO.fetchByRoadwayPointIds(junctionPoints.map(_.roadwayPointId)).filter(_.typeCode == CalibrationPointType.JunctionPointCP).map(_.id)
    if (obsoleteCalibrationPointsIds.nonEmpty) {
      logger.info(s"Expiring calibration point ids: ${obsoleteCalibrationPointsIds.mkString(", ")}")
      CalibrationPointDAO.expireById(obsoleteCalibrationPointsIds)
    }
  }

  def handleRoadwayPointsUpdate(roadwayChanges: List[ProjectRoadwayChange], projectLinkChanges: Seq[ProjectRoadLinkChange], username: String = "-"): Unit = {
    def handleDualRoadwayPoint(oldRoadwayPointId: Long, point: ProjectRoadLinkChange): Unit = {
      // get new address for roadway point, new beforeAfter value for node point and junction point and new startOrEnd for calibration point
      val (newAddr, beforeAfter, startOrEnd) = if (point.reversed) {
        (point.newEndAddr, BeforeAfter.Before, CalibrationPointLocation.EndOfLink)
      } else {
        (point.newStartAddr, BeforeAfter.After, CalibrationPointLocation.StartOfLink)
      }

      val existingRoadwayPoint = roadwayPointDAO.fetch(point.newRoadwayNumber, newAddr)
      if (existingRoadwayPoint.isEmpty) {
        logger.info(s"Handled dual roadway point for roadway number: ${point.newRoadwayNumber}, address: $newAddr:")
        val newRoadwayPointId = roadwayPointDAO.create(point.newRoadwayNumber, newAddr, username)

        val nodePoint = nodePointDAO.fetchByRoadwayPointId(oldRoadwayPointId).filter(_.beforeAfter == BeforeAfter.After)
        logger.info(s"Update node point (${nodePoint.map(_.id)}) for after roadway point id: $oldRoadwayPointId to $newRoadwayPointId, beforeAfter: $beforeAfter")
        nodePointDAO.expireById(nodePoint.map(_.id))
        nodePointDAO.create(nodePoint.map(_.copy(id = NewIdValue, roadwayPointId = newRoadwayPointId, beforeAfter = beforeAfter, createdBy = username)))

        val junctionPoint = junctionPointDAO.fetchByRoadwayPointId(oldRoadwayPointId).filter(_.beforeAfter == BeforeAfter.After)
        logger.info(s"Update junction point (${junctionPoint.map(_.id)}) for after roadway point id: $oldRoadwayPointId to $newRoadwayPointId, beforeAfter: $beforeAfter")
        junctionPointDAO.expireById(junctionPoint.map(_.id))
        junctionPointDAO.create(junctionPoint.map(_.copy(id = NewIdValue, roadwayPointId = newRoadwayPointId, beforeAfter = beforeAfter, createdBy = username)))

        val calibrationPoint = CalibrationPointDAO.fetchByRoadwayPointId(oldRoadwayPointId).filter(_.startOrEnd == CalibrationPointLocation.StartOfLink)
        logger.info(s"Update calibration point (${calibrationPoint.map(_.id)}) for after roadway point id: $oldRoadwayPointId to $newRoadwayPointId, startOrEnd: $startOrEnd")
        CalibrationPointDAO.expireById(calibrationPoint.map(_.id))
        CalibrationPointDAO.create(calibrationPoint.map(_.copy(id = NewIdValue, roadwayPointId = newRoadwayPointId, startOrEnd = startOrEnd, typeCode = CalibrationPointType.RoadAddressCP, createdBy = username)))
      }
    }

    def getNewRoadwayNumberInPoint(roadwayPoint: RoadwayPoint): Option[Long] = {
      projectLinkChanges.filter(mrw => roadwayPoint.roadwayNumber == mrw.originalRoadwayNumber
        && roadwayPoint.addrMValue >= mrw.originalStartAddr && roadwayPoint.addrMValue <= mrw.originalEndAddr) match {
        case linkChanges: Seq[ProjectRoadLinkChange] if linkChanges.size == 2 && linkChanges.map(_.newRoadwayNumber).distinct.size > 1 =>
          val sortedLinkChanges = linkChanges.sortBy(_.originalStartAddr)
          val beforePoint = sortedLinkChanges.head
          val afterPoint: ProjectRoadLinkChange = sortedLinkChanges.last
          handleDualRoadwayPoint(roadwayPoint.id, afterPoint)
          Some(beforePoint.newRoadwayNumber)
        case linkChanges if linkChanges.nonEmpty => Some(linkChanges.head.newRoadwayNumber)
        case linkChanges if linkChanges.isEmpty => None
      }
    }

    def updateRoadwayPoint(rwp: RoadwayPoint, newAddrM: Long): Seq[RoadwayPoint] = {
      val roadwayNumberInPoint = getNewRoadwayNumberInPoint(rwp)
      if (roadwayNumberInPoint.isDefined) {
        if (rwp.roadwayNumber != roadwayNumberInPoint.get || rwp.addrMValue != newAddrM) {
          val newRwp = rwp.copy(roadwayNumber = roadwayNumberInPoint.get, addrMValue = newAddrM, modifiedBy = Some(username))
          logger.info(s"Updating roadway_point ${rwp.id}: (roadwayNumber: ${rwp.roadwayNumber} -> ${newRwp.roadwayNumber}, addr: ${rwp.addrMValue} -> ${newRwp.addrMValue})")
          roadwayPointDAO.update(newRwp)
          Seq(newRwp)
        } else {
          Seq()
        }
      } else {
        Seq()
      }
    }

    try {

      val projectRoadwayChanges = roadwayChanges.filter(rw => List(Transfer, ReNumeration, Unchanged, Termination).contains(rw.changeInfo.changeType))
      val updatedRoadwayPoints: Seq[RoadwayPoint] = projectRoadwayChanges.sortBy(_.changeInfo.target.startAddressM).foldLeft(
        Seq.empty[RoadwayPoint]) { (list, rwc) =>
        val change = rwc.changeInfo
        val source = change.source
        val target = change.target
        val terminatedRoadwayNumbersChanges = projectLinkChanges.filter { entry =>
          entry.roadNumber == source.roadNumber.get &&
            (source.startRoadPartNumber.get to source.endRoadPartNumber.get contains entry.roadPartNumber) &&
            entry.originalStartAddr >= source.startAddressM.get && entry.originalEndAddr <= source.endAddressM.get
        }
        val roadwayNumbers = if (change.changeType == Termination) {
          terminatedRoadwayNumbersChanges.map(_.newRoadwayNumber).distinct
        } else {
          val roadwayNumbersInOriginalRoadPart = projectLinkChanges.filter(lc => lc.originalRoadNumber == source.roadNumber.get && lc.originalRoadPartNumber == source.startRoadPartNumber.get && lc.status.value == change.changeType.value)
          roadwayDAO.fetchAllBySectionAndTracks(target.roadNumber.get, target.startRoadPartNumber.get, Set(Track.apply(target.trackCode.get.toInt))).map(_.roadwayNumber).filter(roadwayNumbersInOriginalRoadPart.map(_.newRoadwayNumber).contains(_)).distinct
        }

        val roadwayPoints = roadwayNumbers.flatMap { rwn =>
          val roadwayNumberInPoint = projectLinkChanges.filter(mrw => mrw.newRoadwayNumber == rwn
            && mrw.originalStartAddr >= source.startAddressM.get && mrw.originalEndAddr <= source.endAddressM.get)
          if (roadwayNumberInPoint.nonEmpty) {
            roadwayPointDAO.fetchByRoadwayNumberAndAddresses(roadwayNumberInPoint.head.originalRoadwayNumber, source.startAddressM.get, source.endAddressM.get)
          } else {
            Seq()
          }
        }.distinct

        if (roadwayPoints.nonEmpty) {
          if (change.changeType == Transfer || change.changeType == Unchanged) {
            if (!change.reversed) {
              val rwPoints: Seq[RoadwayPoint] = roadwayPoints.flatMap { rwp =>
                val newAddrM = target.startAddressM.get + (rwp.addrMValue - source.startAddressM.get)
                updateRoadwayPoint(rwp, newAddrM)
              }
              list ++ rwPoints
            } else {
              val rwPoints: Seq[RoadwayPoint] = roadwayPoints.flatMap { rwp =>
                val newAddrM = target.endAddressM.get - (rwp.addrMValue - source.startAddressM.get)
                updateRoadwayPoint(rwp, newAddrM)
              }
              list ++ rwPoints
            }
          } else if (change.changeType == ReNumeration) {
            if (change.reversed) {
              val rwPoints: Seq[RoadwayPoint] = roadwayPoints.flatMap { rwp =>
                val newAddrM = Seq(source.endAddressM.get, target.endAddressM.get).max - rwp.addrMValue
                updateRoadwayPoint(rwp, newAddrM)
              }
              list ++ rwPoints
            } else {
              list
            }
          } else if (change.changeType == Termination) {
            val rwPoints: Seq[RoadwayPoint] = roadwayPoints.flatMap { rwp =>
              val terminatedRoadAddress = terminatedRoadwayNumbersChanges.find(change => change.originalRoadwayNumber == rwp.roadwayNumber &&
                change.originalStartAddr >= source.startAddressM.get && change.originalEndAddr <= source.endAddressM.get
              )
              if (terminatedRoadAddress.isDefined) {
                updateRoadwayPoint(rwp, rwp.addrMValue)
              } else Seq()
            }
            list ++ rwPoints
          } else list
        } else list
      }

      if (updatedRoadwayPoints.nonEmpty) {
        logger.info(s"Updated ${updatedRoadwayPoints.length} roadway points: ${updatedRoadwayPoints.map(rp => s"(id: ${rp.id}, roadwayNumber: ${rp.roadwayNumber}, addrM: ${rp.addrMValue})").mkString(" and ")}")
      }
    } catch {
      case ex: Exception =>
        logger.error("Failed to update roadway points.", ex)
        throw ex
    }
  }

  val roadwayChangesDAO = new RoadwayChangesDAO

  def fetchUpdatedRoadwayChanges(since: DateTime, until: Option[DateTime]): Seq[RoadwayChangesInfo] = {
    withDynSession {
      roadwayChangesDAO.fetchRoadwayChangesInfo(since, until)
    }
  }

}

sealed trait RoadClass {
  def value: Int

  def roads: Seq[Int]
}

object RoadClass {
  val values: Set[RoadClass] = Set(HighwayClass, MainRoadClass, RegionalClass, ConnectingClass, MinorConnectingClass, StreetClass,
    RampsAndRoundaboutsClass, PedestrianAndBicyclesClassA, PedestrianAndBicyclesClassB, WinterRoadsClass, PathsClass, ConstructionSiteTemporaryClass,
    PrivateRoadClass, NoClass)

  val forNodes: Set[Int] = Set(HighwayClass, MainRoadClass, RegionalClass, ConnectingClass, MinorConnectingClass,
    StreetClass, PrivateRoadClass, WinterRoadsClass, PathsClass).flatMap(_.roads)

  val forJunctions: Range = 0 until 69999

  def get(roadNumber: Int): Int = {
    values.find(_.roads contains roadNumber).getOrElse(NoClass).value
  }

  case object HighwayClass extends RoadClass {
    def value = 1

    def roads: Range.Inclusive = 1 to 39
  }

  case object MainRoadClass extends RoadClass {
    def value = 2

    def roads: Range.Inclusive = 40 to 99
  }

  case object RegionalClass extends RoadClass {
    def value = 3

    def roads: Range.Inclusive = 100 to 999
  }

  case object ConnectingClass extends RoadClass {
    def value = 4

    def roads: Range.Inclusive = 1000 to 9999
  }

  case object MinorConnectingClass extends RoadClass {
    def value = 5

    def roads: Range.Inclusive = 10000 to 19999
  }

  case object RampsAndRoundaboutsClass extends RoadClass {
    def value = 7

    def roads: Range.Inclusive = 20001 to 39999
  }

  case object StreetClass extends RoadClass {
    def value = 6

    def roads: Range.Inclusive = 40000 to 49999
  }


  case object PedestrianAndBicyclesClassA extends RoadClass {
    def value = 8

    def roads: Range.Inclusive = 70001 to 89999
  }

  case object PedestrianAndBicyclesClassB extends RoadClass {
    def value = 8

    def roads: Range.Inclusive = 90001 to 99999
  }

  case object WinterRoadsClass extends RoadClass {
    def value = 9

    def roads: Range.Inclusive = 60001 to 61999
  }

  case object PathsClass extends RoadClass {
    def value = 10

    def roads: Range.Inclusive = 62001 to 62999
  }

  case object ConstructionSiteTemporaryClass extends RoadClass {
    def value = 11

    def roads: Range.Inclusive = 9900 to 9999
  }

  case object PrivateRoadClass extends RoadClass {
    def value = 12

    def roads: Range.Inclusive = 50001 to 59999
  }

  case object NoClass extends RoadClass {
    def value = 99

    def roads: Range.Inclusive = 0 to 0
  }

}

//TODO check if this is needed
class Contains(r: Range) {
  def unapply(i: Int): Boolean = r contains i
}

case class RoadAddressMerge(merged: Set[Long], created: Seq[RoadAddress])

case class LinearLocationResult(current: Seq[LinearLocation])

case class BoundingBoxResult(changeInfoF: Future[Seq[ChangeInfo]], roadAddressResultF: Future[(Seq[LinearLocation], Seq[VVHHistoryRoadLink])],
                             roadLinkF: Future[Seq[RoadLink]], complementaryF: Future[Seq[RoadLink]])

case class RoadAddressResult(roadAddressResultF: Future[Seq[LinearLocation]], roadLinkF: Future[Seq[RoadLink]], complementaryF: Future[Seq[RoadLink]])

case class LinkRoadAddressHistory(v: (Seq[RoadAddress], Seq[RoadAddress])) {
  val currentSegments: Seq[RoadAddress] = v._1
  val historySegments: Seq[RoadAddress] = v._2
  val allSegments: Seq[RoadAddress] = currentSegments ++ historySegments
}

case class ChangedRoadAddress(roadAddress: RoadAddress, link: RoadLink)

object AddressConsistencyValidator {

  sealed trait AddressError {
    def value: Int

    def message: String
  }

  object AddressError {
    val values: Set[AddressError] = Set(OverlappingRoadAddresses, InconsistentTopology, InconsistentLrmHistory, Inconsistent2TrackCalibrationPoints, InconsistentContinuityCalibrationPoints, MissingEdgeCalibrationPoints,
      InconsistentAddressValues, MissingStartingLink)

    case object OverlappingRoadAddresses extends AddressError {
      def value = 1

      def message: String = ErrorOverlappingRoadAddress
    }

    case object InconsistentTopology extends AddressError {
      def value = 2

      def message: String = ErrorInconsistentTopology
    }

    case object InconsistentLrmHistory extends AddressError {
      def value = 3

      def message: String = ErrorInconsistentLrmHistory
    }

    case object Inconsistent2TrackCalibrationPoints extends AddressError {
      def value = 4

      def message: String = ErrorInconsistent2TrackCalibrationPoints
    }

    case object InconsistentContinuityCalibrationPoints extends AddressError {
      def value = 5

      def message: String = ErrorInconsistentContinuityCalibrationPoints
    }

    case object MissingEdgeCalibrationPoints extends AddressError {
      def value = 6

      def message: String = ErrorMissingEdgeCalibrationPoints
    }

    case object InconsistentAddressValues extends AddressError {
      def value = 7

      def message: String = ErrorInconsistentAddressValues
    }

    case object MissingStartingLink extends AddressError {
      def value = 8

      def message: String = ErrorMissingStartingLink
    }

    def apply(intValue: Int): AddressError = {
      values.find(_.value == intValue).get
    }
  }

  case class AddressErrorDetails(linearLocationId: Long, linkId: Long, roadNumber: Long, roadPartNumber: Long, addressError: AddressError, ely: Long)

}

object RoadAddressFilters {
  def sameRoad(curr: BaseRoadAddress)(next: BaseRoadAddress): Boolean = {
    curr.roadNumber == next.roadNumber
  }

  def samePart(curr: BaseRoadAddress)(next: BaseRoadAddress): Boolean = {
    curr.roadPartNumber == next.roadPartNumber
  }

  def sameRoadPart(curr: BaseRoadAddress)(next: BaseRoadAddress): Boolean = {
    curr.roadNumber == next.roadNumber && curr.roadPartNumber == next.roadPartNumber
  }

  def sameTrack(curr: BaseRoadAddress)(next: BaseRoadAddress): Boolean = {
    curr.track == next.track
  }

  def continuousRoad(curr: BaseRoadAddress)(next: BaseRoadAddress): Boolean = {
    sameRoad(curr)(next) && Track.isTrackContinuous(curr.track, next.track) && continuousAddress(curr)(next)
  }

  def discontinuousRoad(curr: BaseRoadAddress)(next: BaseRoadAddress): Boolean = {
    !continuousRoad(curr)(next)
  }

  def continuousRoadPart(curr: BaseRoadAddress)(next: BaseRoadAddress): Boolean = {
    continuousRoad(curr)(next) && sameRoadPart(curr)(next)
  }

  def continuousRoadPartTrack(curr: BaseRoadAddress)(next: BaseRoadAddress): Boolean = {
    continuousRoadPart(curr)(next) && sameTrack(curr)(next)
  }

  def discontinuousRoadPart(curr: BaseRoadAddress)(next: BaseRoadAddress): Boolean = {
    !continuousRoad(curr)(next) && sameRoadPart(curr)(next)
  }

  def continuousAddress(curr: BaseRoadAddress)(next: BaseRoadAddress): Boolean = {
    curr.endAddrMValue == next.startAddrMValue
  }

  def discontinuousAddress(curr: BaseRoadAddress)(next: BaseRoadAddress): Boolean = {
    !continuousAddress(curr)(next)
  }

  def discontinuousAddressInSamePart(curr: BaseRoadAddress)(next: BaseRoadAddress): Boolean = {
    !continuousAddress(curr)(next) && sameRoadPart(curr)(next)
  }

  def continuousTopology(curr: BaseRoadAddress)(next: BaseRoadAddress): Boolean = {
    curr.endPoint.connected(next.startingPoint)
  }

  def discontinuousTopology(curr: BaseRoadAddress)(next: BaseRoadAddress): Boolean = {
    !continuousTopology(curr)(next)
  }

  def connectingBothTails(curr: BaseRoadAddress)(next: BaseRoadAddress): Boolean = {
    curr.endPoint.connected(next.endPoint)
  }

  def connectingBothHeads(curr: BaseRoadAddress)(next: BaseRoadAddress): Boolean = {
    curr.startingPoint.connected(next.startingPoint)
  }

  def endingOfRoad(curr: BaseRoadAddress)(next: BaseRoadAddress): Boolean = {
    (continuousTopology(curr)(next) || connectingBothTails(curr)(next)) && curr.discontinuity == Discontinuity.EndOfRoad
  }

  def discontinuousPartTailIntersection(curr: BaseRoadAddress)(next: BaseRoadAddress): Boolean = {
    !samePart(curr)(next) && curr.discontinuity == Discontinuity.Discontinuous && (continuousTopology(curr)(next) || connectingBothTails(curr)(next))
  }

  def discontinuousPartHeadIntersection(prev: BaseRoadAddress)(curr: Seq[BaseRoadAddress]): Boolean = {
    !curr.forall(ra => samePart(prev)(ra)) && curr.exists(ra => sameRoadPart(prev)(ra))
  }

  def afterDiscontinuousJump(curr: BaseRoadAddress)(next: BaseRoadAddress): Boolean = {
    curr.discontinuity == Discontinuity.MinorDiscontinuity || curr.discontinuity == Discontinuity.Discontinuous && sameRoadPart(curr)(next)
  }

  def halfContinuousHalfDiscontinuous(curr: BaseRoadAddress)(next: BaseRoadAddress): Boolean = {
    (RoadAddressFilters.continuousRoadPart(curr)(next) && RoadAddressFilters.discontinuousTopology(curr)(next)) ||
      (RoadAddressFilters.discontinuousRoadPart(curr)(next) && RoadAddressFilters.continuousTopology(curr)(next))
  }

  // IN REVERSE CASES

  def reversedContinuousTopology(curr: BaseRoadAddress)(next: BaseRoadAddress): Boolean = {
    curr.newEndPoint.connected(next.newStartingPoint)
  }

  def reversedDiscontinuousTopology(curr: BaseRoadAddress)(next: BaseRoadAddress): Boolean = {
    !reversedContinuousTopology(curr)(next)
  }

  def reversedConnectingBothTails(curr: BaseRoadAddress)(next: BaseRoadAddress): Boolean = {
    curr.newEndPoint.connected(next.newEndPoint)
  }

  def reversedConnectingBothHeads(curr: BaseRoadAddress)(next: BaseRoadAddress): Boolean = {
    curr.newStartingPoint.connected(next.newStartingPoint)
  }

  def reversedEndingOfRoad(curr: BaseRoadAddress)(next: BaseRoadAddress): Boolean = {
    (reversedContinuousTopology(curr)(next) || reversedConnectingBothTails(curr)(next)) && curr.discontinuity == Discontinuity.EndOfRoad
  }

  def reversedDiscontinuousPartTailIntersection(curr: BaseRoadAddress)(next: BaseRoadAddress): Boolean = {
    !samePart(curr)(next) && curr.discontinuity == Discontinuity.Discontinuous && (reversedContinuousTopology(curr)(next) || reversedConnectingBothTails(curr)(next))
  }


  def reversedHalfContinuousHalfDiscontinuous(curr: BaseRoadAddress)(next: BaseRoadAddress): Boolean = {
    (RoadAddressFilters.continuousRoadPart(curr)(next) && RoadAddressFilters.reversedDiscontinuousTopology(curr)(next)) ||
      (RoadAddressFilters.discontinuousRoadPart(curr)(next) && RoadAddressFilters.reversedContinuousTopology(curr)(next))
  }
}

package fi.liikennevirasto.viite

import fi.liikennevirasto.digiroad2._
import fi.liikennevirasto.digiroad2.client.kgv._
import fi.liikennevirasto.digiroad2.service.RoadLinkService
import fi.liikennevirasto.digiroad2.util.LogUtils.time
import fi.liikennevirasto.viite.dao.{RoadwayPointDAO, _}
import fi.liikennevirasto.viite.model.RoadAddressLink
import fi.liikennevirasto.viite.process._
import fi.liikennevirasto.viite.process.RoadAddressFiller.ChangeSet
import fi.liikennevirasto.viite.util.CalibrationPointsUtils
import fi.vaylavirasto.viite.dao.MunicipalityDAO
import fi.vaylavirasto.viite.geometry.{BoundingRectangle, GeometryUtils, Point}
import fi.vaylavirasto.viite.model.{AddrMRange, BeforeAfter, CalibrationPointLocation, CalibrationPointType, Discontinuity, RoadAddressChangeType, RoadLink, RoadPart, SideCode, Track}
import fi.vaylavirasto.viite.postgis.PostGISDatabaseScalikeJDBC
import org.joda.time.DateTime
import org.slf4j.LoggerFactory

import scala.concurrent.{Await, Future}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.Duration
import scala.util.control.NonFatal

class RoadAddressService(
                          roadLinkService     : RoadLinkService,
                          roadwayDAO          : RoadwayDAO,
                          linearLocationDAO   : LinearLocationDAO,
                          roadNetworkDAO      : RoadNetworkDAO,
                          roadwayPointDAO     : RoadwayPointDAO,
                          nodePointDAO        : NodePointDAO,
                          junctionPointDAO    : JunctionPointDAO,
                          roadwayAddressMapper: RoadwayAddressMapper,
                          eventbus            : DigiroadEventBus,
                          frozenKGV           : Boolean
                        ) {

  def runWithTransaction[T](f: => T): T = PostGISDatabaseScalikeJDBC.runWithTransaction(f)
  def runWithReadOnlySession[T](f: => T): T = PostGISDatabaseScalikeJDBC.runWithReadOnlySession(f)

  private val logger = LoggerFactory.getLogger(getClass)

  private def roadAddressLinkBuilder = new RoadAddressLinkBuilder(roadwayDAO, linearLocationDAO)

  val viiteVkmClient = new ViiteVkmClient
  class VkmException(response: String) extends RuntimeException(response)

  /**
    * Smallest mvalue difference we can tolerate to be "equal to zero". One micrometer.
    * See https://en.wikipedia.org/wiki/Floating_point#Accuracy_problems
    */
  val Epsilon = 1

  val defaultStreetNumber = 1

  private def fetchLinearLocationsByBoundingBox(boundingRectangle: BoundingRectangle, roadNumberLimits: Seq[(Int, Int)] = Seq()): (Seq[LinearLocation], Seq[HistoryRoadLink]) = {
    val linearLocations = runWithReadOnlySession {
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
  def fetchLinearLocationByBoundingBox(boundingRectangle: BoundingRectangle, roadNumberLimits: Seq[(Int, Int)] = Seq()): Seq[LinearLocation] = {
    runWithReadOnlySession {
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
  def getCurrentRoadAddresses(linearLocations: Seq[LinearLocation]): Seq[RoadAddress] = {
    roadwayAddressMapper.getCurrentRoadAddressesByLinearLocation(linearLocations)
  }

  /**
    * Returns all linear locations of the given roadPart
    *
    * @param roadPart The road part, whose linear locations are to be returned
    */
  def getLinearLocationsInRoadPart(roadPart: RoadPart): Seq[LinearLocation] = {

    // get roadways of the road part
    val roadwaysOnPart = roadwayDAO.fetchAllByRoadPart(roadPart)

    // get all the roadwayNumbers from the roadways
    val allRoadwayNumbersInRoadPart = roadwaysOnPart.map(rw => rw.roadwayNumber)

    // get all the linear locations on the roadway numbers
    val allLinearLocations =  {
      linearLocationDAO.fetchByRoadwayNumber(allRoadwayNumbersInRoadPart)
    }

    allLinearLocations
  }


  private def getRoadAddressLinks(boundingBoxResult: BoundingBoxResult): Seq[RoadAddressLink] = {
    val boundingBoxResultF =
      for {
        changeInfoF <- boundingBoxResult.changeInfoF
        roadLinksF <- boundingBoxResult.roadLinkF
        complementaryRoadLinksF <- boundingBoxResult.complementaryF
        linearLocationsAndHistoryRoadLinksF <- boundingBoxResult.roadAddressResultF
      } yield (changeInfoF, roadLinksF, complementaryRoadLinksF, linearLocationsAndHistoryRoadLinksF)

    val (_, roadLinks, complementaryRoadLinks, (linearLocations, _)) =
      time(logger, "Fetch KGV bounding box data") {
        Await.result(boundingBoxResultF, Duration.Inf)
      }

    val allRoadLinks = roadLinks ++ complementaryRoadLinks

    //removed apply changes before adjusting topology since in future NLS will give perfect geometry and supposedly, we will not need any changes
    val (adjustedLinearLocations, changeSet) = if (frozenKGV) (linearLocations, Seq()) else RoadAddressFiller.adjustToTopology(allRoadLinks, linearLocations)
    if (!frozenKGV)
      eventbus.publish("roadAddress:persistChangeSet", changeSet)

    val roadAddresses = runWithReadOnlySession {
      roadwayAddressMapper.getRoadAddressesByLinearLocation(adjustedLinearLocations)
    }
    RoadAddressFiller.fillTopology(allRoadLinks, roadAddresses)
  }

  def getRoadAddressWithRoadNumberAddress(road: Long): Seq[RoadAddress] = {
    runWithReadOnlySession {
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

    val linearLocations = runWithReadOnlySession {
      time(logger, "Fetch addresses") {
        linearLocationDAO.fetchLinearLocationByBoundingBox(boundingRectangle, roadNumberLimits)
      }
    }
    val linearLocationsLinkIds = linearLocations.map(_.linkId).toSet

    val boundingBoxResult = BoundingBoxResult(
      roadLinkService.getChangeInfoFromVVHF(linearLocationsLinkIds),
      Future((linearLocations, roadLinkService.getRoadLinksHistoryFromVVH(linearLocationsLinkIds))),
      Future(roadLinkService.getRoadLinksByLinkIds(linearLocationsLinkIds)),
      Future(Seq())
    )

    getRoadAddressLinks(boundingBoxResult)
  }

  /**
    * Returns all road address links (combination between our roadway, linear location and KGV information) based on the given road part
    *
    * @param roadPart The road part, whose linear locations are to be returned
    * @return
    */
  def getRoadAddressLinksOfWholeRoadPart(roadPart: RoadPart): Seq[RoadAddressLink] = {

    val allLinearLocations = runWithReadOnlySession {
      time(logger, s"Fetch addresses in road part: ${roadPart} ") {
        getLinearLocationsInRoadPart(roadPart)
      }
    }

    val linearLocationsLinkIds = allLinearLocations.map(_.linkId).toSet

    // mimic bounding box result even though we didnt get these linear locations from the bounding box view like we normally would
    val boundingBoxResult = BoundingBoxResult(
      roadLinkService.getChangeInfoFromVVHF(linearLocationsLinkIds),
      Future((allLinearLocations, roadLinkService.getRoadLinksHistoryFromVVH(linearLocationsLinkIds))),
      Future(roadLinkService.getRoadLinksByLinkIds(linearLocationsLinkIds)),
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
    * Gets all the road addresses in the given bounding box, without KGV geometry. Also floating road addresses are filtered out.
    * Indicated to high zoom levels. If the road number limits are given it will also filter all road addresses by those limits.
    *
    * @param boundingRectangle The bounding box
    * @param roadNumberLimits  The road number limits
    * @return Returns all the filtered road addresses
    */
  def getRoadAddressesWithLinearGeometry(boundingRectangle: BoundingRectangle, roadNumberLimits: Seq[(Int, Int)]): Seq[RoadAddressLink] = {
    val roadAddresses = runWithTransaction {
      val linearLocations = linearLocationDAO.fetchLinearLocationByBoundingBox(boundingRectangle, roadNumberLimits)
      roadwayAddressMapper.getRoadAddressesByLinearLocation(linearLocations)
    }

    roadAddresses.map(roadAddressLinkBuilder.build)
  }

  // maximum allowed length difference for VIITE-3083 rounding inconsistency
  val maxAllowedLengthDifference = Point(0,0).distance2DTo(Point(0.001,0.001)) // 1mm to each direction

  /**
    * Gets all the road addresses in the given municipality code.
    *
    * @param municipality The municipality code
    * @return Returns all the filtered road addresses
    */
  def getAllByMunicipality(municipality: Int, searchDate: Option[DateTime] = None): Seq[RoadAddressLink] = {
    val (roadLinks, _) = roadLinkService.getRoadLinksWithComplementaryAndChangesFromVVH(municipality)

    val linearLocations = runWithReadOnlySession {
      time(logger, "Fetch addresses") {
        linearLocationDAO.fetchRoadwayByLinkId(roadLinks.map(_.linkId).toSet)
      }
    }
    val (adjustedLinearLocations, changeSet) = if (frozenKGV) (linearLocations, Seq()) else RoadAddressFiller.adjustToTopology(roadLinks, linearLocations)
    if (!frozenKGV) {
      //TODO we should think to update both servers with cache at the same time, and before the apply change batch that way we will not need to do any kind of changes here
      eventbus.publish("roadAddress:persistChangeSet", changeSet)
    }


    val roadAddresses = runWithReadOnlySession {
      roadwayAddressMapper.getCurrentRoadAddressesByLinearLocation(adjustedLinearLocations, searchDate)
    }

    roadAddresses.flatMap { ra =>
      val roadLink = roadLinks.find(rl => rl.linkId == ra.linkId)
      roadLink.map(rl => {
        val ral: RoadAddressLink = roadAddressLinkBuilder.build(rl, ra)

        // ---- Start of VIITE-3083 hack ---
        // Instead of returning just the RoadAddressLink returned by the roadAddressLinkBuilder.build function retain the linear location with "correct" 3 decimals in the last geometry point
        if(ral.geometry.last.distance2DTo(rl.geometry.last) <= maxAllowedLengthDifference)
          // VIITE-3083 if the calculated last point is off by just a minimal amount, use the end point from the linear location, instead. ---
          // --- This corrects slight rounding inconsistencies between the last points of roadAddressLinkBuilder.build output, and the linear location geometry.
          ral.copy(geometry = (ral.geometry.dropRight(1):+rl.geometry.last.with3decimals) ) // VIITE-3083 Hack to retain already correctly rounded 3 decimals from linear location
        else
        // ---- End of VIITE-3083 hack ----
          ral     // Default value. VIITE-3083: Keep this whole calculated geometry, if other than slight difference between the linear location, and calculated end point values.
      })
    }
  }

  /**
   * @param  date OPTIONAL: Fetches summary data for the road address information
   *         of the whole road network at a specific time.
   * @return Returns summary data for the road address information
   *         of the whole road network.
   */
  def getRoadwayNetworkSummary(date: Option[DateTime] = None): Seq[RoadwayNetworkSummaryRow] = {
    runWithReadOnlySession {
      roadNetworkDAO.fetchRoadwayNetworkSummary(date)
    }
  }

  /**
    * Gets all the existing road numbers at the current road network.
    *
    * @return Returns all the road numbers
    */
  def getRoadNumbers: Seq[Long] = {
    runWithReadOnlySession {
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
    logger.debug(s"getSearchResults, searchString: $searchString")
    val parsedInput = locationInputParser(searchString)
    val searchType = parsedInput.head._1
    val params = parsedInput.head._2
    var searchResult: Seq[Any] = null
    searchType match {
      case "linkId" =>
        val searchResultPoint = roadLinkService.getMidPointByLinkId(searchString.get)
        collectResult("linkId", Seq(searchResultPoint))
      case "road" => params.size match {
        case 1 => {
          // The params with type long can be MTKID or roadNumber
          val searchResultPoint = roadLinkService.getRoadLinkMiddlePointBySourceId(params.head)
          val partialResultSeq = collectResult("mtkId", Seq(searchResultPoint))
          val searchResult = getFirstOrEmpty(getRoadAddressWithRoadNumberAddress(params.head).sortBy(address => (address.roadPart.partNumber, address.addrMRange.start)))
          collectResult("road", searchResult, partialResultSeq)
        }
        case 2 =>
          collectResult("road", getFirstOrEmpty(getRoadAddressWithRoadNumberParts(params.head, Set(params(1)), Set(Track.Combined, Track.LeftSide, Track.RightSide))
          .sortBy(address => (address.roadPart.partNumber, address.addrMRange.start))
        ))
        case 3 | 4 =>
          val roadNumber = params(0)
          val roadPart = params(1)
          val addressM = params(2)
          val track = if (params.size == 4) Some(params(3)) else None
          val ralOption = getRoadAddressLink(RoadPart(roadNumber, roadPart), addressM, track)
          ralOption.foldLeft(Seq.empty[Map[String, Seq[Any]]])((partialResultSeq, ral) => {
            val roadAddressLinkMValueLengthPercentageFactor = (addressM - ral.addrMRange.start.toDouble) / ral.addrMRange.length.toDouble
            val geometryLength = ral.endMValue - ral.startMValue
            val geometryMeasure = roadAddressLinkMValueLengthPercentageFactor * geometryLength
            val point = ral match {
              case r if (r.addrMRange.start.toDouble == addressM && r.sideCode == SideCode.TowardsDigitizing) || (r.addrMRange.endsAt(addressM) && r.sideCode == SideCode.AgainstDigitizing) =>
                r.geometry.headOption
              case r if (r.addrMRange.start.toDouble == addressM && r.sideCode == SideCode.AgainstDigitizing) || (r.addrMRange.endsAt(addressM) && r.sideCode == SideCode.TowardsDigitizing) =>
                r.geometry.lastOption
              case r =>
                val mValue: Double = r.sideCode match {
                  case SideCode.AgainstDigitizing => geometryLength - geometryMeasure
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
        val municipalityId = runWithReadOnlySession {
          if (address.size > 1) MunicipalityDAO.getMunicipalityIdByName(address.last.trim).headOption.map(_._1) else None
        }
        val (streetName, streetNumber) = address.head.split(" ").partition(_.matches("\\D+"))

        // Append '*' wildcard to street name. Keeps Viite search functionality similar to VKM's earlier version (upto 2020).
        // Pad with plain ' '. Strings expected to be (html-)unescaped, @see viiteVkmClient.get.
        val street = streetName.mkString(" ") + "*"

        searchResult = viiteVkmClient.get("/viitekehysmuunnin/muunna", Map(
          ("kuntakoodi", municipalityId.getOrElse("").toString),
          ("katunimi", street),
          ("katunumero", streetNumber.headOption.getOrElse(defaultStreetNumber.toString)))) match {
          case Right(result) => Seq(result)
          case Left(error) => throw new VkmException(error.toString)
        }
        collectResult("street", searchResult)
      case _ => Seq.empty[Map[String, Seq[Any]]]
    }
  }

  def locationInputParser(searchStringOption: Option[String]): Map[String, Seq[Long]] = {
    val searchString = searchStringOption.getOrElse("").trim

    if (searchString.isEmpty) { Map() } // nothing to parse -> no results.

    val linkIdRegex  = """(\w+-\w+-\w+-\w+-\w+:\d+)""".r // Link UUID
    val linkIds      = linkIdRegex.findFirstIn(searchString)

    if (linkIds.nonEmpty)  // We found a linkId; interpret as a linkId.
      Map(("linkId", Seq(-1L)))
    else {
      val numRegex = """(\d+)""".r
      val nums     = numRegex.findAllIn(searchString).map(_.toLong).toSeq
      val letterRegex = """([A-Za-zÀ-ÿ])""".r
      val letters = letterRegex.findFirstIn(searchString)
      if (letters.isEmpty) // There are no letters to be found. Not a street, so it must be a road .
        Map(("road", nums))
      else // We hade at least one letter (but not a linkId): this must be a street name
        Map(("street", nums))
    }
  }

  /**
    * Gets road address link in the same road part and addressM.
    *
    * @param roadPart  The road part
    * @param addressM  The addressM that is in between the returned RoadAddress
    * @return Returns RoadAddressLink in track 0 or 1 or given track which contains dynamically calculated addrMRange
    *         and includes detailed geometry in that link fetched dynamically from KGV
    */
  def getRoadAddressLink(roadPart: RoadPart, addressM: Long, track: Option[Long] = None): Option[RoadAddressLink] = {
    val linearLocations = runWithReadOnlySession {
      time(logger, "Fetch addresses") {
        linearLocationDAO.fetchByRoadAddress(roadPart, addressM, track)
      }
    }
    val linearLocationsLinkIds = linearLocations.map(_.linkId).toSet
    val roadAddresses = getRoadAddressForSearch(roadPart, addressM, track)
    val roadLinks = roadLinkService.getRoadLinksByLinkIds(linearLocationsLinkIds)
    val rals = RoadAddressFiller.fillTopology(roadLinks, roadAddresses)
    val filteredRals = rals.filter(al => al.addrMRange.start <= addressM && al.addrMRange.end >= addressM && (al.addrMRange.start != 0 || al.addrMRange.end != 0))
    val ral = filteredRals.filter(al => (track.nonEmpty && track.contains(al.trackCode)) || al.trackCode != Track.LeftSide.value)
    ral.headOption
  }

  /**
    * Gets road address in the same road number, roadPart and addressM.
    *
    * @param roadPart The road part
    * @param addressM The addressM that is in between the returned RoadAddress start and end addresses
    * @return Returns all the filtered road addresses. Note. RoadAddress has normally shorter length than roadway
    */
  def getRoadAddressForSearch(roadPart: RoadPart, addressM: Long, track: Option[Long] = None): Seq[RoadAddress] = {
    runWithReadOnlySession {
      val roadways = roadwayDAO.fetchAllBySectionAndAddresses(roadPart, Some(addressM), Some(addressM), track)
      val roadAddresses = roadwayAddressMapper.getRoadAddressesByRoadway(roadways).sortBy(_.addrMRange.start)
      roadAddresses.filter(ra => (track.isEmpty || track.contains(ra.track.value)) && ra.addrMRange.start <= addressM && ra.addrMRange.end >= addressM)
    }
  }

  /**
    * Gets all the road addresses in the same road part with start address less than
    * the given address measure. If trackOption parameter is given it will also filter by track code.
    *
    * @param roadPart    The road part
    * @param addressM    The road address at road number and road part
    * @param trackOption Optional track code
    * @return Returns all the filtered road addresses
    */
  def getRoadAddress(roadPart: RoadPart, addressM: Long, trackOption: Option[Track]): Seq[RoadAddress] = {
    runWithReadOnlySession {
      val roadways = trackOption match {
        case Some(track) =>
          if (addressM != 0)
            roadwayDAO.fetchAllBySectionTrackAndAddresses(roadPart, track, None, Some(addressM))
          else
            roadwayDAO.fetchAllBySectionTrackAndAddresses(roadPart, track, None, None)
        case _ =>
          if (addressM != 0)
            roadwayDAO.fetchAllBySectionAndAddresses(roadPart, None, Some(addressM))
          else
            roadwayDAO.fetchAllBySectionAndAddresses(roadPart, None, None)
      }

      val roadAddresses = roadwayAddressMapper.getRoadAddressesByRoadway(roadways).sortBy(_.addrMRange.start)
      if (addressM > 0) {
        roadAddresses.filter(ra => ra.addrMRange.start >= addressM || ra.addrMRange.endsAt(addressM))
      }
      else if(roadAddresses.nonEmpty) Seq(roadAddresses.head) else Seq()
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
    runWithReadOnlySession {
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
    runWithReadOnlySession {
      val roadways = roadwayDAO.fetchAllBySectionsAndTracks(road, roadParts, tracks)
      roadwayAddressMapper.getRoadAddressesByRoadway(roadways)
    }
  }

  /**
    * Gets all the road addresses in the same road number, road parts and track codes.
    * If the road part number sequence or track codes sequence is empty
    *
    * @param roadPart     The road part
    * @param withHistory  The optional parameter that allows the search to also look for historic links
    * @param fetchOnlyEnd The optional parameter that allows the search for the link with bigger endAddrM value
    * @return Returns all the filtered road addresses
    */
  def getRoadAddressWithRoadPart(roadPart: RoadPart, withHistory: Boolean = false, fetchOnlyEnd: Boolean = false, newTransaction: Boolean = true): Seq[RoadAddress] = {
    if (newTransaction)
      runWithReadOnlySession {
        val roadways = roadwayDAO.fetchAllByRoadPart(roadPart, withHistory, fetchOnlyEnd)
        roadwayAddressMapper.getRoadAddressesByRoadway(roadways)
      }
    else {
      val roadways = roadwayDAO.fetchAllByRoadPart(roadPart, withHistory, fetchOnlyEnd)
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
  def getRoadAddressWithLinkIdAndMeasure(linkId: String, startMOption: Option[Double], endMOption: Option[Double]): Seq[RoadAddress] = {
    runWithReadOnlySession {
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
    * Gets all the road address in the given road part
    *
    * @param roadPart The road part
    * @return Returns road addresses filtered given section
    */
  def getRoadAddressesFiltered(roadPart: RoadPart): Seq[RoadAddress] = {
    /*if (PostGISDatabase.isWithinSession) {
      val roadwayAddresses = roadwayDAO.fetchAllBySection(roadPart)
      roadwayAddressMapper.getRoadAddressesByRoadway(roadwayAddresses)
    } else {*/
    // Should not be needed with scalikeJDBC
      runWithReadOnlySession {
        val roadwayAddresses = roadwayDAO.fetchAllBySection(roadPart)
        roadwayAddressMapper.getRoadAddressesByRoadway(roadwayAddresses)
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
    runWithReadOnlySession {
      roadwayDAO.getValidRoadParts(roadNumber, startDate)
    }
  }

  /**
    * Gets the previous road address part in the given road number and road part number
    *
    * @param roadPart   The road part
    * @return Returns previous road part of the same road, if it exists
    */
  def getPreviousRoadPartNumber(roadPart: RoadPart): Option[Long] = {
    runWithReadOnlySession {
      roadwayDAO.fetchPreviousRoadPartNumber(roadPart)
    }
  }

  /**
    * Gets all the road addresses in given road number, road part number and between given address measures.
    * The road address measures should be in [addrMRange.start, addrMRange.end]
    *
    * @param roadPart       The road part
    * @param addrMRange     The address measure range to query
    * @return Returns road addresses filtered by road section and address measures
    */
  def getRoadAddressesFiltered(roadPart: RoadPart, addrMRange: AddrMRange): Seq[RoadAddress] = {
    runWithReadOnlySession {
      val roadwayAddresses = roadwayDAO.fetchAllBySectionAndAddresses(roadPart, Some(addrMRange.start), Some(addrMRange.end))
      val roadAddresses = roadwayAddressMapper.getRoadAddressesByRoadway(roadwayAddresses)
      roadAddresses.filter(ra => ra.isBetweenAddresses(addrMRange))
    }
  }

  /**
    * Gets all the road addresses on top of given road links.
    *
    * @param linkIds The set of road link identifiers
    * @return Returns all filtered the road addresses
    */
  def getRoadAddressByLinkIds(linkIds: Set[String]): Seq[RoadAddress] = {
    runWithReadOnlySession {
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

  def getTracksForRoadAddressBrowser(situationDate: Option[String], ely: Option[Long], roadNumber: Option[Long], minRoadPartNumber: Option[Long], maxRoadPartNumber: Option[Long]): Seq[TrackForRoadAddressBrowser] = {
    runWithReadOnlySession {
      roadwayDAO.fetchTracksForRoadAddressBrowser(situationDate, ely, roadNumber, minRoadPartNumber, maxRoadPartNumber)
    }
  }

  def getRoadPartsForRoadAddressBrowser(situationDate: Option[String], ely: Option[Long], roadNumber: Option[Long], minRoadPartNumber: Option[Long], maxRoadPartNumber: Option[Long]): Seq[RoadPartForRoadAddressBrowser] = {
    runWithReadOnlySession {
      roadwayDAO.fetchRoadPartsForRoadAddressBrowser(situationDate, ely, roadNumber, minRoadPartNumber, maxRoadPartNumber)
    }
  }

  def getChangeInfosForRoadAddressChangesBrowser(startDate: Option[String], endDate: Option[String], dateTarget: Option[String],
                                                 ely: Option[Long], roadNumber: Option[Long], minRoadPartNumber: Option[Long],
                                                 maxRoadPartNumber: Option[Long]): Seq[ChangeInfoForRoadAddressChangesBrowser] = {
    runWithReadOnlySession {
      roadwayChangesDAO.fetchChangeInfosForRoadAddressChangesBrowser(startDate, endDate, dateTarget, ely, roadNumber, minRoadPartNumber, maxRoadPartNumber)
    }
  }

  def getChanged(sinceDate: DateTime, untilDate: DateTime): Seq[ChangedRoadAddress] = {
    runWithReadOnlySession {
      val roadwayAddresses = roadwayDAO.fetchAllByDateRange(sinceDate, untilDate)
      val roadAddresses = roadwayAddressMapper.getRoadAddressesByRoadway(roadwayAddresses)

      val roadLinks = roadLinkService.getRoadLinksAndComplementary(roadAddresses.map(_.linkId).toSet)
// TODO: fix this
      //      val roadLinksWithoutWalkways = roadLinks.filterNot(_.linkType == CycleOrPedestrianPath).filterNot(_.linkType == TractorRoad)

      roadAddresses.flatMap { roadAddress =>
        roadLinks.find(_.linkId == roadAddress.linkId).map { roadLink =>
          ChangedRoadAddress(
            roadAddress = roadAddress.copyWithGeometry(GeometryUtils.truncateGeometry3D(roadLink.geometry, roadAddress.startMValue, roadAddress.endMValue)),
            link = roadLink
          )
        }
      }
    }
  }

  def getUpdatedRoadways(sinceDate: DateTime): Either[String, Seq[Roadway]] = {
    runWithReadOnlySession {
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
    runWithReadOnlySession {
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
    * returns road addresses with link-id currently does not include terminated links which it cannot build roadaddress with out geometry
    *
    * @param linkId link-id
    * @return roadaddress[]
    */
  def getRoadAddressLink(linkId: String): Seq[RoadAddressLink] = {

    val roadlinks = roadLinkService.getAllVisibleRoadLinks(Set(linkId))

    val roadAddresses = runWithReadOnlySession {
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

    runWithTransaction {
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
    try {
      val boundingBoxResult = BoundingBoxResult(
        Future(Seq[ChangeInfo]()),
        Future(fetchLinearLocationsByBoundingBox(boundingRectangle)),
        Future(roadLinkService.getRoadLinks(boundingRectangle, roadNumberLimits, Set(), everything, publicRoads)),
        roadLinkService.getComplementaryRoadLinks(boundingRectangle, Set())
      )
      getRoadAddressLinks(boundingBoxResult)
    } catch {
      case e: Throwable =>
        logger.error(s"$e ")
        Seq[RoadAddressLink]()
    }
  }


  def handleProjectCalibrationPointChanges(linearLocations: Iterable[LinearLocation], username: String = "-", terminated: Seq[ProjectRoadLinkChange] = Seq()): Unit = {
    def handleTerminatedCalibrationPointRoads(pls: Seq[ProjectRoadLinkChange]) = {
      val ids: Set[Long] = pls.flatMap(p => CalibrationPointDAO.fetchIdByRoadwayNumberSection(p.originalRoadwayNumber, p.originalAddrMRange)).toSet
      if (ids.nonEmpty) {
        logger.info(s"Expiring calibration point ids: " + ids.mkString(", "))
        CalibrationPointDAO.expireById(ids)
      }
    }

    handleTerminatedCalibrationPointRoads(terminated)
    handleCalibrationPoints(linearLocations, username)
  }

  def handleCalibrationPoints(linearLocations: Iterable[LinearLocation], username: String = "-"): Unit = {

    val linearLocationsWithStartCP = linearLocations.filter(_.startCalibrationPoint.isDefined)
    val linearLocationsWithEndCP = linearLocations.filter(_.endCalibrationPoint.isDefined)

    // Fetch current linear locations and check which calibration points should be expired
    val currentCPs = CalibrationPointDAO.fetchByLinkId(linearLocations.map(l => l.linkId))
    val currentStartCP = currentCPs.filter(_.startOrEnd == CalibrationPointLocation.StartOfLink)
    val currentEndCP = currentCPs.filter(_.startOrEnd == CalibrationPointLocation.EndOfLink)
    val startCPsToBeExpired = currentStartCP.filter(c => !linearLocationsWithStartCP.exists(sc => sc.linkId == c.linkId && (sc.startCalibrationPoint.isDefined && c.startOrEnd == CalibrationPointLocation.StartOfLink)))
    val endCPsToBeExpired = currentEndCP.filter(c => !linearLocationsWithEndCP.exists(sc => sc.linkId == c.linkId && (sc.endCalibrationPoint.isDefined && c.startOrEnd == CalibrationPointLocation.EndOfLink)))

    // Expire calibration points not applied by project road changes logic
    startCPsToBeExpired.foreach {
      cp =>
        val cal = CalibrationPointDAO.fetch(cp.linkId, CalibrationPointLocation.StartOfLink.value)
        if (cal.isDefined) {
          CalibrationPointDAO.expireById(Set(cal.get.id))
          logger.info(s"Expiring calibration point id:" + cal.get.id)
          // Check if the expired calibrationpoint needs to be replaced with a JunctionCP
          CalibrationPointsUtils.createCalibrationPointIfNeeded(cp.roadwayPointId, cp.linkId, CalibrationPointLocation.StartOfLink, CalibrationPointType.JunctionPointCP, username)
        } else {
          logger.error(s"Failed to expire start calibration point for link id: ${cp.linkId}")
        }
    }
    endCPsToBeExpired.foreach {
      cp =>
        val cal = CalibrationPointDAO.fetch(cp.linkId, CalibrationPointLocation.EndOfLink.value)
        if (cal.isDefined) {
          CalibrationPointDAO.expireById(Set(cal.get.id))
          logger.info(s"Expiring calibration point id:" + cal.get.id)
          // Check if the expired calibrationpoint needs to be replaced with a JunctionCP
          CalibrationPointsUtils.createCalibrationPointIfNeeded(cp.roadwayPointId, cp.linkId, CalibrationPointLocation.EndOfLink, CalibrationPointType.JunctionPointCP, username)
        } else {
          logger.error(s"Failed to expire end calibration point for link id: ${cp.linkId}")
        }
    }

    // Check other calibration points
    linearLocationsWithStartCP.foreach {
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
    linearLocationsWithEndCP.foreach {
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
    def handleDualRoadwayPoint(oldRoadwayPointId: Long, projectRoadLinkChangeAfter: ProjectRoadLinkChange): Unit = {
      // get new address for roadway point, new beforeAfter value for node point and junction point and new startOrEnd for calibration point
      val (newAddr, beforeAfter, startOrEnd) = {
        if (projectRoadLinkChangeAfter.reversed)
          (projectRoadLinkChangeAfter.newAddrMRange.end, BeforeAfter.Before, CalibrationPointLocation.EndOfLink)
        else
          (projectRoadLinkChangeAfter.newAddrMRange.start, BeforeAfter.After, CalibrationPointLocation.StartOfLink)
      }

      val existingRoadwayPoint = roadwayPointDAO.fetch(projectRoadLinkChangeAfter.newRoadwayNumber, newAddr)
      if (existingRoadwayPoint.isEmpty) {
        logger.info(s"Handled dual roadway point for roadway number: ${projectRoadLinkChangeAfter.newRoadwayNumber}, address: $newAddr:")
        val newRoadwayPointId = roadwayPointDAO.create(projectRoadLinkChangeAfter.newRoadwayNumber, newAddr, username)

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
        calibrationPoint.foreach(cp => CalibrationPointDAO.
          create(newRoadwayPointId, cp.linkId, startOrEnd, CalibrationPointType.RoadAddressCP, username)
        )
      }
    }

    def getNewRoadwayNumberInPoint(roadwayPoint: RoadwayPoint): Option[Long] = {
      projectLinkChanges.filter(plc => roadwayPoint.roadwayNumber == plc.originalRoadwayNumber && roadwayPoint.addrMValue >= plc.originalAddrMRange.start && roadwayPoint.addrMValue <= plc.originalAddrMRange.end) match {
        case linkChanges: Seq[ProjectRoadLinkChange] if linkChanges.size == 2 && linkChanges.map(_.newRoadwayNumber).distinct.size > 1 =>
          val sortedProjectLinkChanges = linkChanges.sortBy(_.originalAddrMRange.start)
          val projectLinkChangeBefore = sortedProjectLinkChanges.head
          val projectLinkChangeAfter: ProjectRoadLinkChange = sortedProjectLinkChanges.last
          handleDualRoadwayPoint(roadwayPoint.id, projectLinkChangeAfter)
          Some(projectLinkChangeBefore.newRoadwayNumber)
        case linkChanges if linkChanges.nonEmpty => Some(linkChanges.head.newRoadwayNumber)
        case linkChanges if linkChanges.isEmpty => None
      }
    }

    def updateRoadwayPoint(rwp: RoadwayPoint, newAddrM: Long): Seq[RoadwayPoint] = {
      val roadwayNumberInPoint = getNewRoadwayNumberInPoint(rwp)
      if (roadwayNumberInPoint.isDefined) {
        if (rwp.roadwayNumber != roadwayNumberInPoint.get || rwp.addrMValue != newAddrM) {
          val newRwp = rwp.copy(roadwayNumber = roadwayNumberInPoint.get, addrMValue = newAddrM, modifiedBy = Some(username))
          val existingRoadwayPoint = roadwayPointDAO.fetch(newRwp.roadwayNumber, newRwp.addrMValue)
          if (existingRoadwayPoint.isEmpty) {
            logger.info(s"Updating roadway_point ${rwp.id}: (roadwayNumber: ${rwp.roadwayNumber} -> ${newRwp.roadwayNumber}, addr: ${rwp.addrMValue} -> ${newRwp.addrMValue})")
            roadwayPointDAO.update(newRwp)
            Seq(newRwp)
          }
          else {
            logger.info(s"Skipping roadway points' roadwayNumber (${rwp.roadwayNumber} -> ${newRwp.roadwayNumber}) and addrM (${rwp.addrMValue} -> ${newRwp.addrMValue}) update " +
              s"because a similar roadway point already exists in the database with these values (id: ${existingRoadwayPoint.get.id}, roadwayNumber: ${existingRoadwayPoint.get.roadwayNumber}, addrMValue: ${existingRoadwayPoint.get.addrMValue})")
            Seq()
          }
        } else {
          Seq()
        }
      } else {
        Seq()
      }
    }

    try {

      val projectRoadwayChanges = roadwayChanges.filter(rw => List(
        RoadAddressChangeType.Transfer, RoadAddressChangeType.Renumeration,
        RoadAddressChangeType.Unchanged, RoadAddressChangeType.Termination
      ).contains(rw.changeInfo.changeType))
      val updatedRoadwayPoints: Seq[RoadwayPoint] = projectRoadwayChanges.sortBy(_.changeInfo.target.getStartOption).foldLeft(
        Seq.empty[RoadwayPoint]) { (list, rwc) =>
        val change = rwc.changeInfo
        val source = change.source
        val target = change.target
        val terminatedRoadwayNumbersChanges = projectLinkChanges.filter { entry => // TODO "terminatedRoadwayNumbersChanges" - what is it with this name?
          entry.roadPart.roadNumber == source.roadNumber.get &&
          (source.startRoadPartNumber.get to source.endRoadPartNumber.get contains entry.roadPart.partNumber) &&
          entry.status!=RoadAddressChangeType.New && source.addrMRange.get.contains(entry.originalAddrMRange)
        }
        val roadwayNumbers = if (change.changeType == RoadAddressChangeType.Termination) {
          terminatedRoadwayNumbersChanges.map(_.newRoadwayNumber).distinct
        } else {
          val sourceRoadPart = RoadPart(source.roadNumber.get, source.startRoadPartNumber.get)
          val targetRoadPart = RoadPart(target.roadNumber.get, target.startRoadPartNumber.get)
          val roadwayNumbersInOriginalRoadPart = projectLinkChanges.filter(lc => lc.originalRoadPart == sourceRoadPart && lc.status.value == change.changeType.value)
          roadwayDAO.fetchAllBySectionAndTracks(targetRoadPart, Set(Track.apply(target.trackCode.get.toInt))).map(_.roadwayNumber).filter(roadwayNumbersInOriginalRoadPart.map(_.newRoadwayNumber).contains(_)).distinct
        }

        val roadwayPoints = roadwayNumbers.flatMap { rwn =>
          val filteredProjectLinkChanges = projectLinkChanges.filter(plc => plc.newRoadwayNumber == rwn
            && source.addrMRange.get.contains(plc.originalAddrMRange))
          if (filteredProjectLinkChanges.nonEmpty) {
            roadwayPointDAO.fetchByRoadwayNumberAndAddresses(filteredProjectLinkChanges.head.originalRoadwayNumber, AddrMRange(source.addrMRange.get.start, source.addrMRange.get.end))
          } else {
            Seq()
          }
        }.distinct

        if (roadwayPoints.nonEmpty) {
          if (change.changeType == RoadAddressChangeType.Transfer || change.changeType == RoadAddressChangeType.Unchanged) {
            if (!change.reversed) {
              val rwPoints: Seq[RoadwayPoint] = roadwayPoints.flatMap { rwp =>
                if (!list.exists(_.id == rwp.id)) { // Check if the point is already in the updated list
                  val dualRoadwayPointNewAddrM = projectLinkChanges.filter(plc => rwp.roadwayNumber == plc.originalRoadwayNumber && rwp.addrMValue >= plc.originalAddrMRange.start && rwp.addrMValue <= plc.originalAddrMRange.end) match {
                    case linkChanges: Seq[ProjectRoadLinkChange] if linkChanges.size == 2 && linkChanges.map(_.newRoadwayNumber).distinct.size > 1 =>
                      val sortedProjectLinkChanges = linkChanges.sortBy(_.originalAddrMRange.start)
                      val projectRoadLinkChangeBefore = sortedProjectLinkChanges.head
                      Some(projectRoadLinkChangeBefore.newAddrMRange.end)
                    case _ => None
                  }

                  val newAddrM = {
                    if (dualRoadwayPointNewAddrM.isDefined)
                      dualRoadwayPointNewAddrM.get
                    else
                      target.addrMRange.get.start + (rwp.addrMValue - source.addrMRange.get.start)
                  }
                  updateRoadwayPoint(rwp, newAddrM)
                } else { // Skip the point if it is already in the updated list
                  Seq.empty[RoadwayPoint]
                }
              }
              list ++ rwPoints
            } else {
              val rwPoints: Seq[RoadwayPoint] = roadwayPoints.flatMap { rwp =>
                if (!list.exists(_.id == rwp.id)) { // Check if the point is already in the updated list
                  val newAddrM = target.addrMRange.get.end - (rwp.addrMValue - source.addrMRange.get.start)
                  updateRoadwayPoint(rwp, newAddrM)
                } else {
                  Seq.empty[RoadwayPoint]
                }
              }
              list ++ rwPoints
            }
          } else if (change.changeType == RoadAddressChangeType.Renumeration) {
            if (change.reversed) {
              val rwPoints: Seq[RoadwayPoint] = roadwayPoints.flatMap { rwp =>
                if (!list.exists(_.id == rwp.id)) { // Check if the point is already in the updated list
                  val newAddrM = Seq(source.addrMRange.get.end, target.addrMRange.get.end).max - rwp.addrMValue
                  updateRoadwayPoint(rwp, newAddrM)
                } else { // Skip the point if it is already in the updated list
                  Seq.empty[RoadwayPoint]
                }
              }
              list ++ rwPoints
            } else {
              list
            }
          } else if (change.changeType == RoadAddressChangeType.Termination) {
            val rwPoints: Seq[RoadwayPoint] = roadwayPoints.flatMap { rwp =>
              if (!list.exists(_.id == rwp.id)) { // Check if the point is already in the updated list
                val terminatedRoadAddress = terminatedRoadwayNumbersChanges.find(change => change.originalRoadwayNumber == rwp.roadwayNumber &&
                  source.addrMRange.get.contains(change.originalAddrMRange)
                )
                if (terminatedRoadAddress.isDefined) {
                  updateRoadwayPoint(rwp, rwp.addrMValue)
                } else Seq.empty[RoadwayPoint]
              } else { // Skip the point if it is already in the updated list
                Seq.empty[RoadwayPoint]
              }
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
    runWithReadOnlySession {
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
    RampsAndRoundaboutsClass, PedestrianAndBicyclesClassA, PedestrianAndBicyclesClassB, WinterRoadsClass, PathsClass,
    PrivateRoadClass, NoClass)

  val forNodes: Set[Int] = Set(HighwayClass, MainRoadClass, RegionalClass, ConnectingClass, MinorConnectingClass,
    StreetClass, PrivateRoadClass, WinterRoadsClass, PathsClass).flatMap(_.roads)

  val forJunctions: Range = 0 until 69999

  def get(roadNumber: Long): Int = {
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

    def roads: Range.Inclusive = 20000 to 39999
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

  case object PrivateRoadClass extends RoadClass {
    def value = 12

    def roads: Range.Inclusive = 50000 to 59999
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

case class BoundingBoxResult(changeInfoF: Future[Seq[ChangeInfo]], roadAddressResultF: Future[(Seq[LinearLocation], Seq[HistoryRoadLink])],
                             roadLinkF: Future[Seq[RoadLink]], complementaryF: Future[Seq[RoadLink]])

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

}

object RoadAddressFilters {
  def connectedToEndOrDifferentRoad(curr: BaseRoadAddress)(next: BaseRoadAddress): Boolean = {
    next.startingPoint.connected(curr.endPoint) || next.endPoint.connected(curr.endPoint) || next.roadPart.roadNumber != curr.roadPart.roadNumber
  }

  def sameRoad(curr: BaseRoadAddress)(next: BaseRoadAddress): Boolean = {
    curr.roadPart.roadNumber == next.roadPart.roadNumber
  }

  def samePart(curr: BaseRoadAddress)(next: BaseRoadAddress): Boolean = {
    curr.roadPart.partNumber == next.roadPart.partNumber
  }

  def sameRoadPart(curr: BaseRoadAddress)(next: BaseRoadAddress): Boolean = {
    curr.roadPart == next.roadPart
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
    curr.addrMRange.continuesTo(next.addrMRange)
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

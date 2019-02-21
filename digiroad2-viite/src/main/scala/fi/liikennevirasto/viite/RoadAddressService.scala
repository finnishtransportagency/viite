package fi.liikennevirasto.viite

import java.net.ConnectException
import java.util.concurrent.TimeUnit

import fi.liikennevirasto.digiroad2._
import fi.liikennevirasto.digiroad2.asset._
import fi.liikennevirasto.digiroad2.client.vvh._
import fi.liikennevirasto.digiroad2.linearasset.RoadLink
import fi.liikennevirasto.digiroad2.oracle.OracleDatabase
import fi.liikennevirasto.digiroad2.service.RoadLinkService
import fi.liikennevirasto.digiroad2.user.User
import fi.liikennevirasto.digiroad2.util.LogUtils.time
import fi.liikennevirasto.digiroad2.util.Track
import fi.liikennevirasto.viite.dao.TerminationCode.{NoTermination, Subsequent}
import fi.liikennevirasto.viite.dao._
import fi.liikennevirasto.viite.model.{Anomaly, RoadAddressLink}
import fi.liikennevirasto.viite.process.RoadAddressFiller.{ChangeSet, LinearLocationAdjustment}
import fi.liikennevirasto.viite.process._
import org.joda.time.DateTime
import org.slf4j.LoggerFactory

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future}

class RoadAddressService(roadLinkService: RoadLinkService, roadwayDAO: RoadwayDAO, linearLocationDAO: LinearLocationDAO, roadNetworkDAO: RoadNetworkDAO, unaddressedRoadLinkDAO: UnaddressedRoadLinkDAO, roadwayAddressMapper: RoadwayAddressMapper, eventbus: DigiroadEventBus, frozenTimeVVHAPIServiceEnabled: Boolean = false) {

  def withDynTransaction[T](f: => T): T = OracleDatabase.withDynTransaction(f)

  def withDynSession[T](f: => T): T = OracleDatabase.withDynSession(f)

  private val logger = LoggerFactory.getLogger(getClass)

  private def roadAddressLinkBuilder = new RoadAddressLinkBuilder(roadwayDAO, linearLocationDAO, new ProjectLinkDAO)

  /**
    * Smallest mvalue difference we can tolerate to be "equal to zero". One micrometer.
    * See https://en.wikipedia.org/wiki/Floating_point#Accuracy_problems
    */
  val Epsilon = 1

  private def fetchLinearLocationsByBoundingBox(boundingRectangle: BoundingRectangle, roadNumberLimits: Seq[(Int, Int)] = Seq()) = {
    val linearLocations = withDynSession {
      time(logger, "Fetch floating and non-floating addresses") {
        linearLocationDAO.fetchRoadwayByBoundingBox(boundingRectangle, roadNumberLimits)
      }
    }

    val historyRoadLinks = roadLinkService.getRoadLinksHistoryFromVVH(linearLocations.filter(_.isFloating).map(_.linkId).toSet)

    (linearLocations, historyRoadLinks)
    //TODO will be implemented at VIITE-1538
//    val floatingHistoryRoadLinks = withDynTransaction {
//      time(logger, "Fetch floating history links") {
//        roadLinkService.getRoadLinksHistoryFromVVH(floatingAddresses.map(_.linkId).toSet)
//      }
//    }
//    val historyLinkAddresses = time(logger, "Build history link addresses") {
//      floatingHistoryRoadLinks.flatMap(fh => {
//        buildFloatingRoadAddressLink(fh, floatingAddresses.filter(_.linkId == fh.linkId))
//      })
//    }
//    LinearLocationResult(nonFloatingAddresses, floatingAddresses)
  }

  /**
    * Fetches linear locations based on a bounding box and, if defined, within the road number limits supplied.
    * @param boundingRectangle: BoundingRectangle - The search box
    * @param roadNumberLimits: Seq[(Int, Int) - A sequence of upper and lower limits of road numbers
    * @return
    */
  def fetchLinearLocationByBoundingBox(boundingRectangle: BoundingRectangle, roadNumberLimits: Seq[(Int, Int)] = Seq()) = {
    withDynSession {
      time(logger, "Fetch floating and non-floating addresses") {
        linearLocationDAO.fetchRoadwayByBoundingBox(boundingRectangle, roadNumberLimits)
      }
    }
  }

  /**
    * Returns the roadways that match the supplied linear locations.
    * @param linearLocations: Seq[LinearLocation] - The linear locations to search
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
        suravageRoadLinksF <- boundingBoxResult.suravageF
      } yield (changeInfoF, roadLinksF, complementaryRoadLinksF, linearLocationsAndHistoryRoadLinksF, suravageRoadLinksF)

    val (changeInfos, roadLinks, complementaryRoadLinks, (linearLocations, historyRoadLinks), suravageRoadLinks) =
      time(logger, "Fetch VVH bounding box data") {
        Await.result(boundingBoxResultF, Duration.Inf)
      }

    val allRoadLinks = roadLinks ++ complementaryRoadLinks ++ suravageRoadLinks

    //removed apply changes before adjusting topology since in future NLS will give perfect geometry and supposedly, we will not need any changes
    val (adjustedLinearLocations, changeSet) = if (frozenTimeVVHAPIServiceEnabled) (linearLocations, Seq()) else RoadAddressFiller.adjustToTopology(allRoadLinks, linearLocations)
    if (!frozenTimeVVHAPIServiceEnabled)
      eventbus.publish("roadAddress:persistChangeSet", changeSet)

    val roadAddresses = withDynSession {
      roadwayAddressMapper.getRoadAddressesByLinearLocation(adjustedLinearLocations)
    }

    RoadAddressFiller.fillTopologyWithFloating(allRoadLinks, historyRoadLinks, roadAddresses)
  }

  def getRoadAddressWithRoadNumberAddress(road: Long): Seq[RoadAddress] = {
    withDynSession {
      roadwayAddressMapper.getRoadAddressesByRoadway(roadwayDAO.fetchAllByRoad(road))
    }
  }

  /**
    * Returns all road address links (combination between our roadway, linear location and vvh information) based on the limits imposed by the boundingRectangle and the roadNumberLimits.
    * @param boundingRectangle: BoundingRectangle - The search box
    * @param roadNumberLimits: Seq[(Int, Int) - A sequence of upper and lower limits of road numbers
    * @return
    */
  def getRoadAddressLinksByBoundingBox(boundingRectangle: BoundingRectangle, roadNumberLimits: Seq[(Int, Int)]): Seq[RoadAddressLink] = {

    val linearLocations = withDynSession {
      time(logger, "Fetch floating and non-floating addresses") {
        linearLocationDAO.fetchRoadwayByBoundingBox(boundingRectangle, roadNumberLimits)
      }
    }

    val (floating, nonFloating) = linearLocations.partition(_.isFloating)

    val floatingLinkIds = floating.map(_.linkId).toSet
    val nonFloatingLinkIds = nonFloating.map(_.linkId).toSet

    val boundingBoxResult = BoundingBoxResult(
      roadLinkService.getChangeInfoFromVVHF(nonFloatingLinkIds),
      Future((linearLocations, roadLinkService.getRoadLinksHistoryFromVVH(floatingLinkIds))),
      Future(roadLinkService.getRoadLinksByLinkIdsFromVVH(nonFloatingLinkIds, frozenTimeVVHAPIServiceEnabled)),
      Future(Seq()),
      Future(Seq())
    )

    getRoadAddressLinks(boundingBoxResult)
  }

  /**
    * Returns all of our road addresses (combination of roadway + linear location information) based on the limits imposed by the boundingRectangle and the roadNumberLimits.
    * @param boundingRectangle: BoundingRectangle - The search box
    * @param roadNumberLimits: Seq[(Int, Int) - A sequence of upper and lower limits of road numbers
    * @return
    */
  def getRoadAddressesByBoundingBox(boundingRectangle: BoundingRectangle, roadNumberLimits: Seq[(Int, Int)]): Seq[RoadAddress] = {
    val linearLocations =
      time(logger, "Fetch floating and non-floating linear locations by bounding box") {
        linearLocationDAO.fetchRoadwayByBoundingBox(boundingRectangle, roadNumberLimits)
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
    val nonFloatingRoadAddresses = withDynTransaction {
      val linearLocations = linearLocationDAO.fetchRoadwayByBoundingBox(boundingRectangle, roadNumberLimits)
      roadwayAddressMapper.getRoadAddressesByLinearLocation(linearLocations).filterNot(_.isFloating)
    }

    nonFloatingRoadAddresses.map(roadAddressLinkBuilder.build)
  }

  /**
    * Returns all of our road addresses (combination of roadway + linear location information) that share the same linkIds as those supplied.
    * @param linkIds: Seq[Long] - The linkId's to fetch information
    * @return
    */
  def getRoadAddressesByLinkIds(linkIds: Seq[Long]): Seq[RoadAddress] = {
    val linearLocations = linearLocationDAO.fetchByLinkId(linkIds.toSet)
    roadwayAddressMapper.getRoadAddressesByLinearLocation(linearLocations).filterNot(_.isFloating)
  }

  /**
    * Gets all the road addresses in the given municipality code.
    *
    * @param municipality The municipality code
    * @return Returns all the filtered road addresses
    */
  def getAllByMunicipality(municipality: Int): Seq[RoadAddressLink] = {

    val suravageRoadLinksF = Future(roadLinkService.getSuravageRoadLinks(municipality))

    val (roadLinks, _) = roadLinkService.getRoadLinksWithComplementaryAndChangesFromVVH(municipality, frozenTimeVVHAPIServiceEnabled)

    val allRoadLinks = roadLinks ++ Await.result(suravageRoadLinksF, atMost = Duration.create(1, TimeUnit.HOURS))

    val linearLocations = withDynTransaction {
      time(logger, "Fetch floating and non-floating addresses") {
        linearLocationDAO.fetchRoadwayByLinkId(allRoadLinks.map(_.linkId).toSet)
      }
    }
    val (adjustedLinearLocations, changeSet) = if (frozenTimeVVHAPIServiceEnabled) (linearLocations, Seq()) else RoadAddressFiller.adjustToTopology(allRoadLinks, linearLocations)
    if (!frozenTimeVVHAPIServiceEnabled) {
      //TODO we should think to update both servers with cache at the same time, and before the apply change batch that way we will not need to do any kind of changes here
      eventbus.publish("roadAddress:persistChangeSet", changeSet)
    }


    val roadAddresses = withDynSession {
      roadNetworkDAO.getLatestRoadNetworkVersionId match {
        case Some(roadNetworkId) => roadwayAddressMapper.getNetworkVersionRoadAddressesByLinearLocation(adjustedLinearLocations, roadNetworkId)
        case _ => roadwayAddressMapper.getCurrentRoadAddressesByLinearLocation(adjustedLinearLocations)
      }
    }

    roadAddresses.flatMap { ra =>
      //TODO check if the floating are needed
      val roadLink = allRoadLinks.find(rl => rl.linkId == ra.linkId)
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
      if (addressM > 0)
        roadAddresses.filter(ra => ra.startAddrMValue < addressM)
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
    * @param withFloating The optional parameter that allows the search to also look for floating links
    * @param fetchOnlyEnd The optional parameter that allows the search for the link with bigger endAddrM value
    * @return Returns all the filtered road addresses
    */
  // TODO Implement fetching with floating
  def getRoadAddressWithRoadAndPart(road: Long, part: Long, withHistory: Boolean = false, withFloating: Boolean = false, fetchOnlyEnd: Boolean = false): Seq[RoadAddress] = {
    withDynSession {
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
    withDynSession {
      val roadwayAddresses = roadwayDAO.fetchAllBySection(roadNumber, roadPartNumber)
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
    * All the floating road address are filterd out from the result.
    *
    * @param linkIds The set of road link identifiers
    * @return Returns all filtered the road addresses
    */
  def getRoadAddressByLinkIds(linkIds: Set[Long]): Seq[RoadAddress] = {
    withDynTransaction {
      val linearLocations = linearLocationDAO.fetchRoadwayByLinkId(linkIds)
      val roadAddresses = roadwayAddressMapper.getRoadAddressesByLinearLocation(linearLocations)
      roadAddresses.filter(ra => linkIds.contains(ra.linkId)).filterNot(_.isFloating)
    }
  }

  /**
    * Returns all of our road addresses (combination of roadway + linear location information) that share the same roadwayId as those supplied.
    * @param roadwayIds: Seq[Long] - The roadway Id's to fetch
    * @param includeFloating: Boolean - Signals if we fetch the floatings or not
    * @return
    */
  def getRoadAddressesByRoadwayIds(roadwayIds: Seq[Long], includeFloating: Boolean = false): Seq[RoadAddress] = {
      val roadways = roadwayDAO.fetchAllByRoadwayId(roadwayIds)
      val roadAddresses = roadwayAddressMapper.getRoadAddressesByRoadway(roadways)
      roadAddresses.filterNot(_.isFloating)
  }

  def getChanged(sinceDate: DateTime, untilDate: DateTime): Seq[ChangedRoadAddress] = {
    val roadwayAddresses =
      withDynSession {
        roadwayDAO.fetchAllByDateRange(sinceDate, untilDate)
      }

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

      existingLinearLocationsGrouped.foreach{
        case (roadwayNumber, existingLinearLocations) =>
          val roadwayLinearLocations = orderedLinearLocations.getOrElse(roadwayNumber, Seq())
          if(roadwayCheckSum.getOrElse(roadwayNumber, -1) != roadwayLinearLocations.map(_.orderNumber).sum)
          {
            linearLocationDAO.expireByIds(existingLinearLocations.map(_.id).toSet)
            linearLocationDAO.create(roadwayLinearLocations)
          }
      }

      linearLocationDAO.create(changeSet.newLinearLocations.map(l => l.copy(id = NewLinearLocation)))

      //TODO Implement the missing at user story VIITE-1596
    }

  }

  /**
    * Returns all floating road addresses that are represented on ROADWAY table and are valid (excluding history)
    *
    * @param includesHistory - default value = false to exclude history values
    * @return Seq[RoadAddress]
    */
  def getFloatingAdresses(includesHistory: Boolean = false): List[RoadAddress] = {
    throw new NotImplementedError("Will be implementd at VIITE-1537")
    //    withDynSession {
    //      RoadAddressDAO.fetchAllFloatingRoadAddresses(includesHistory)
    //    }
  }

  /**
    * Returns roadways with ID currently does not include terminated links which it cannot build roadaway without geometry
    *
    * @param id id
    * @return roadaddress[]
    */
  def getRoadAddressLinkById(id: Long): Seq[RoadAddressLink] = {
    throw new NotImplementedError("Will be implementd at VIITE-1537")
    //    val (addresses, missedRL) = withDynTransaction {
    //      val addr = RoadAddressDAO.fetchByIdMassQuery(Set(id), includeFloating = true, includeHistory = false)
    //      (addr, RoadAddressDAO.getUnaddressedRoadLinks(addr.map(_.linkId).toSet))
    //    }
    //    processRoadAddresses(addresses, missedRL)
  }

  // TODO
  /**
    * Gets the Unaddressed roads that match the link ids from the list supplied.
    * @param linkIds: Set[Long] - The link ids to fetch
    * @return
    */
  def fetchUnaddressedRoadLinksByLinkIds(linkIds: Set[Long]): Seq[UnaddressedRoadLink] = {
    time(logger, "RoadAddressDAO.getUnaddressedRoadLinks by linkIds") {
      unaddressedRoadLinkDAO.getUnaddressedRoadLinks(linkIds)
    }
  }

  private def processRoadAddresses(addresses: Seq[RoadAddress], missedRL: Seq[UnaddressedRoadLink]): Seq[RoadAddressLink] = {
    throw new NotImplementedError("Will be implementd at VIITE-1537")
    //    val linkIds = addresses.map(_.linkId).toSet
    //    val anomaly = missedRL.headOption.map(_.anomaly).getOrElse(Anomaly.None)
    //    val (roadLinks, vvhHistoryLinks) = roadLinkService.getCurrentAndHistoryRoadLinksFromVVH(linkIds, frozenTimeVVHAPIServiceEnabled)
    //    (anomaly, addresses.size, roadLinks.size, vvhHistoryLinks.size) match {
    //      case (_, 0, 0, _) => List() // No road link currently exists and no addresses on this link id => ignore
    //      case (Anomaly.GeometryChanged, _, _, 0) => addresses.flatMap(a => roadLinks.map(rl => RoadAddressLinkBuilder.build(rl, a)))
    //      case (Anomaly.GeometryChanged, _, _, _) => addresses.flatMap(a => vvhHistoryLinks.map(rl => RoadAddressLinkBuilder.build(rl, a)))
    //      case (_, _, 0, _) => addresses.flatMap(a => vvhHistoryLinks.map(rl => RoadAddressLinkBuilder.build(rl, a)))
    //      case (Anomaly.NoAddressGiven, 0, _, _) => missedRL.flatMap(a => roadLinks.map(rl => RoadAddressLinkBuilder.build(rl, a)))
    //      case (_, _, _, _) => addresses.flatMap(a => roadLinks.map(rl => RoadAddressLinkBuilder.build(rl, a)))
    //    }
  }

  //  //Only used outside the class for test propose
  //  def buildFloatingAddresses(allRoadLinks: Seq[RoadLink], suravageLinks: Seq[VVHRoadlink], floating: Seq[RoadAddress]): Seq[RoadAddressLink] = {
  //    //    val combinedRoadLinks = (allRoadLinks ++ suravageLinks).filter(crl => floating.map(_.linkId).contains(crl.linkId))
  //    //    combinedRoadLinks.flatMap(fh => {
  //    //      val actualFloatings = floating.filter(_.linkId == fh.linkId)
  //    //      buildFloatingRoadAddressLink(toHistoryRoadLink(fh), actualFloatings)
  //    //    })
  //  }

  private def buildFloatingRoadAddressLink(rl: VVHHistoryRoadLink, roadAddrSeq: Seq[RoadAddress]): Seq[RoadAddressLink] = {
    val fusedRoadAddresses = roadAddressLinkBuilder.fuseRoadAddressWithTransaction(roadAddrSeq)
    fusedRoadAddresses.map(ra => {
      roadAddressLinkBuilder.build(rl, ra)
    })
  }

  private def fetchUnaddressedRoadLinksByBoundingBox(boundingRectangle: BoundingRectangle, fetchOnlyFloating: Boolean = false) = {
    throw new NotImplementedError("Will be implemented at VIITE-1542")
    //    withDynTransaction {
    //      time(logger, "RoadAddressDAO.fetchUnaddressedRoadLinksByBoundingBox") {
    //        RoadAddressDAO.fetchUnaddressedRoadLinksByBoundingBox(boundingRectangle).groupBy(_.linkId)
    //      }
    //    }
  }

  private def getSuravageRoadLinkAddresses(boundingRectangle: BoundingRectangle, boundingBoxResult: BoundingBoxResult): Seq[RoadAddressLink] = {
//    throw new NotImplementedError("Will be implemented at VIITE-1540")
    withDynSession {
      Await.result(boundingBoxResult.suravageF, Duration.Inf).map(x => (x, None)).map(roadAddressLinkBuilder.buildSuravageRoadAddressLink)
    }
  }

  def getSuravageRoadLinkAddressesByLinkIds(linkIdsToGet: Set[Long]): Seq[RoadAddressLink] = {
//    throw new NotImplementedError("Will be implemented at VIITE-1540")
    Seq()
    val suravageLinks = roadLinkService.getSuravageVVHRoadLinksByLinkIdsFromVVH(linkIdsToGet)
    withDynSession {
      suravageLinks.map(x => (x, None)).map(roadAddressLinkBuilder.buildSuravageRoadAddressLink)
    }
  }

  def getRoadAddressLinksWithSuravage(boundingRectangle: BoundingRectangle, roadNumberLimits: Seq[(Int, Int)],
                                      everything: Boolean = false, publicRoads: Boolean = false): Seq[RoadAddressLink] = {

    val boundingBoxResult = BoundingBoxResult(
      roadLinkService.getChangeInfoFromVVHF(boundingRectangle, Set()),
      //Should fetch all the road types
      Future(fetchLinearLocationsByBoundingBox(boundingRectangle)),
      Future(roadLinkService.getRoadLinksFromVVH(boundingRectangle, roadNumberLimits, Set(), everything, publicRoads, frozenTimeVVHAPIServiceEnabled)),
      Future(roadLinkService.getComplementaryRoadLinksFromVVH(boundingRectangle, Set())),
      Future(roadLinkService.getSuravageLinksFromVVHF(boundingRectangle, Set()))
    )

    val suravageAddresses = getSuravageRoadLinkAddresses(boundingRectangle, boundingBoxResult)
    suravageAddresses ++ getRoadAddressLinks(boundingBoxResult)

  }

  // For the purpose of the use of this conversion we do not need a accurate start date and end date since it comes from the Road address on the builder
//  private def toHistoryRoadLink(roadLinkLike: RoadLinkLike): VVHHistoryRoadLink = {
//    val featureClassCode = roadLinkLike.attributes.getOrElse("MTKCLASS", BigInt(0)).asInstanceOf[BigInt].intValue()
//    VVHHistoryRoadLink(roadLinkLike.linkId, roadLinkLike.municipalityCode, roadLinkLike.geometry, roadLinkLike.administrativeClass, roadLinkLike.trafficDirection,  VVHClient.featureClassCodeToFeatureClass.getOrElse(featureClassCode, AllOthers),
//      roadLinkLike.vvhTimeStamp, roadLinkLike.vvhTimeStamp, roadLinkLike.attributes, roadLinkLike.constructionType, roadLinkLike.linkSource, roadLinkLike.length)
//  }

//  private def publishChangeSet(changeSet: ChangeSet): Unit = {
//    time(logger, "Publish change set") {
//      //Temporary filter for unaddressed road links QA
////      if (!frozenTimeVVHAPIServiceEnabled) {
////
////      }
//      eventbus.publish("roadAddress:persistUnaddressedRoadLink", changeSet.unaddressedRoadLinks)
//      eventbus.publish("roadAddress:persistAdjustments", changeSet.adjustedMValues)
//      eventbus.publish("roadAddress:floatRoadAddress", changeSet.floatingLinearLocationIds)
//    }
//  }

  private def createRoadAddressLinkMap(roadLinks: Seq[RoadLink], suravageLinks: Seq[VVHRoadlink], toFloating: Seq[RoadAddressLink],
                                       addresses: Seq[RoadAddress],
                                       missedRL: Map[Long, List[UnaddressedRoadLink]]): Map[Long, Seq[RoadAddressLink]] = {
    throw new NotImplementedError("Will be implemented at VIITE-1536")
//    time(logger, "Create road address link map") {
//      val (suravageRA, _) = addresses.partition(ad => ad.linkGeomSource == LinkGeomSource.SuravageLinkInterface)
//      logger.info(s"Creation of RoadAddressLinks started.")
//      val mappedRegular = roadLinks.map { rl =>
//        val floaters = toFloating.filter(_.linkId == rl.linkId)
//        val ra = addresses.filter(_.linkId == rl.linkId)
//        val missed = missedRL.getOrElse(rl.linkId, Seq())
//        rl.linkId -> buildRoadAddressLink(rl, ra, missed, floaters)
//      }.toMap
//      val filteredSuravage = suravageLinks.filter(sl => suravageRA.map(_.linkId).contains(sl.linkId))
//      val mappedSuravage = filteredSuravage.map(sur => {
//        val ra = suravageRA.filter(_.linkId == sur.linkId)
//        sur.linkId -> buildSuravageRoadAddressLink(sur, ra)
//      }).toMap
//      logger.info(s"Finished creation of roadAddressLinks, final result: ")
//      logger.info(s"Regular Roads: ${mappedRegular.size} || Suravage Roads: ${mappedSuravage.size}")
//      mappedRegular ++ mappedSuravage
//    }
  }

  /**
    * Checks that  length is same after change  (used in type 1 and type 2)
    *
    * @param change change case class
    * @return true if stays with in epsilon
    */
  //TODO during apply change development this method should be moved to VVHChangeInfoClient
  private def changedLengthStaySame(change: ChangeInfo): Boolean = {
    val difference = Math.abs(change.oldEndMeasure.getOrElse(0D) - change.oldStartMeasure.getOrElse(0D)) -
      Math.abs(change.newEndMeasure.getOrElse(0D) - change.newStartMeasure.getOrElse(0D))
    if (difference.abs < Epsilon) {
      true
    } else {
      logger.error("Change message for change " + change.toString + "failed due to length not being same before and after change")
      false
    }
  }

  /**
    * Sanity checks for changes. We don't want to solely trust VVH messages, thus we do some sanity checks and drop insane ones
    *
    * @param changes Change infos
    * @return sane changetypes
    */
  def changesSanityCheck(changes: Seq[ChangeInfo]): Seq[ChangeInfo] = {
    //TODO during apply change development this method should be moved to VVHChangeInfoClient
    val (combinedParts, nonCheckedChangeTypes) = changes.partition(x => x.changeType == ChangeType.CombinedModifiedPart.value
      || x.changeType == ChangeType.CombinedRemovedPart.value)
    val sanityCheckedTypeOneTwo = combinedParts.filter(x => changedLengthStaySame(x))
    sanityCheckedTypeOneTwo ++ nonCheckedChangeTypes
  }

  def filterRelevantChanges(roadAddresses: Seq[RoadAddress], allChanges: Seq[ChangeInfo]): Seq[ChangeInfo] = {
    throw new NotImplementedError("Will be implemented at VIITE-1569, and probably this method should be moved to VVHChangeInfoClient")
//    val groupedAddresses = roadAddresses.groupBy(_.linkId)
//    val timestamps = groupedAddresses.mapValues(_.map(_.adjustedTimestamp).min)
//    allChanges.filter(ci => timestamps.get(ci.oldId.getOrElse(ci.newId.get)).nonEmpty && ci.vvhTimeStamp >= timestamps.getOrElse(ci.oldId.getOrElse(ci.newId.get), 0L))
  }

  def applyChanges(roadLinks: Seq[RoadLink], allChanges: Seq[ChangeInfo], roadAddresses: Seq[RoadAddress]): Seq[LinkRoadAddressHistory] = {
    throw new NotImplementedError("Will be implemented at VIITE-1569")
//    time(logger, "Apply changes") {
//      val addresses = roadAddresses.groupBy(ad => (ad.linkId, ad.roadwayNumber)).mapValues(v => LinkRoadAddressHistory(v.partition(_.endDate.isEmpty)))
//      val changes = filterRelevantChanges(roadAddresses, allChanges)
//      val changedRoadLinks = changesSanityCheck(changes)
//      if (changedRoadLinks.isEmpty) {
//        addresses.values.toSeq
//      } else {
//        withDynTransaction {
//          val newRoadAddresses = RoadAddressChangeInfoMapper.resolveChangesToMap(addresses, changedRoadLinks)
//          val roadLinkMap = roadLinks.map(rl => rl.linkId -> rl).toMap
//
//          val (addressesToCreate, addressesExceptNew) = newRoadAddresses.flatMap(_._2.allSegments).toSeq.partition(_.id == NewRoadAddress)
//          val savedRoadAddresses = addressesToCreate.filter(r => roadLinkMap.contains(r.linkId)).map(r =>
//            r.copy(geometry = GeometryUtils.truncateGeometry3D(roadLinkMap(r.linkId).geometry,
//              r.startMValue, r.endMValue), linkGeomSource = roadLinkMap(r.linkId).linkSource))
//          val removedIds = addresses.values.flatMap(_.allSegments).map(_.id).toSet -- (savedRoadAddresses ++ addressesExceptNew).map(x => x.id)
//          removedIds.grouped(500).foreach(s => {
//            RoadAddressDAO.expireById(s)
//            logger.debug("Expired: " + s.mkString(","))
//          })
//          val toFloating = addressesExceptNew.filter(ra => ra.isFloating)
//          logger.info(s"Found ${toFloating.size} road addresses that were left floating after changes, saving them.")
//          toFloating.foreach {
//            ra => RoadAddressDAO.changeRoadAddressFloatingWithHistory(ra.id, None, FloatingReason.ApplyChanges)
//          }
//
//          checkRoadAddressFloatingWithoutTX(addressesExceptNew.map(_.linkId).toSet, float = true)
//          val ids = RoadAddressDAO.create(savedRoadAddresses).toSet ++ addressesExceptNew.map(_.id).toSet
//          val changedRoadParts = addressesToCreate.map(a => (a.roadNumber, a.roadPartNumber)).toSet
//
//          val adjustedRoadParts = changedRoadParts.filter { x => recalculateRoadAddresses(x._1, x._2) }
//          // re-fetch after recalculation
//          val adjustedAddresses = adjustedRoadParts.flatMap { case (road, part) => RoadAddressDAO.fetchByRoadPart(road, part) }
//
//          val changedRoadAddresses = adjustedAddresses ++ RoadAddressDAO.fetchByIdMassQuery(ids -- adjustedAddresses.map(_.id), includeFloating = true)
//          changedRoadAddresses.groupBy(cra => (cra.linkId, cra.roadwayNumber)).map(s => LinkRoadAddressHistory(s._2.toSeq.partition(_.endDate.isEmpty))).toSeq
//        }
//      }
//    }
  }

  /**
    * Returns unaddressed road links for links that did not already exist in database
    *
    * @param roadNumberLimits
    * @param municipality
    * @return
    */
  def getUnaddressedRoadLink(roadNumberLimits: Seq[(Int, Int)], municipality: Int): Seq[UnaddressedRoadLink] = {
    throw new NotImplementedError("Will be implemented at VIITE-1542")
//    val (addresses, missedRL, roadLinks) =
//      withDynTransaction {
//        val roadLinks = roadLinkService.getCurrentAndComplementaryRoadLinksFromVVH(municipality, roadNumberLimits, frozenTimeVVHAPIServiceEnabled)
//        val linkIds = roadLinks.map(_.linkId).toSet
//        val addr = RoadAddressDAO.fetchByLinkId(linkIds).groupBy(_.linkId)
//        val missingLinkIds = linkIds -- addr.keySet
//        (addr, RoadAddressDAO.getUnaddressedRoadLinks(missingLinkIds).groupBy(_.linkId), roadLinks)
//      }
//    val viiteRoadLinks = roadLinks.map { rl =>
//      val ra = addresses.getOrElse(rl.linkId, Seq())
//      val missed = missedRL.getOrElse(rl.linkId, Seq())
//      rl.linkId -> buildRoadAddressLink(rl, ra, missed)
//    }.toMap
//
//    val (_, changeSet) = RoadAddressFiller.fillTopology(roadLinks, viiteRoadLinks)
//
//    changeSet.unaddressedRoadLinks
  }

  //TODO this method is almost duplicated from buildRoadAddressLink if this method is needed in the future it should be refactored
  private def buildSuravageRoadAddressLink(rl: VVHRoadlink, roadAddrSeq: Seq[RoadAddress]): Seq[RoadAddressLink] = {
    throw new NotImplementedError("Will be implemented at VIITE-1540")
//    val fusedRoadAddresses = RoadAddressLinkBuilder.fuseRoadAddressWithTransaction(roadAddrSeq)
//    val kept = fusedRoadAddresses.map(_.id).toSet
//    val removed = roadAddrSeq.map(_.id).toSet.diff(kept)
//    val roadAddressesToRegister = fusedRoadAddresses.filter(_.id == fi.liikennevirasto.viite.NewRoadAddress)
//    if (roadAddressesToRegister.nonEmpty)
//      eventbus.publish("roadAddress:mergeRoadAddress", RoadAddressMerge(removed, roadAddressesToRegister))
//    fusedRoadAddresses.map(ra => {
//      RoadAddressLinkBuilder.build(rl, ra)
//    })
  }

  private def getTargetRoadLink(linkId: Long): RoadAddressLink = {
    val (roadLinks, _) = roadLinkService.getCurrentAndHistoryRoadLinksFromVVH(Set(linkId))
    if (roadLinks.isEmpty) {
      throw new InvalidAddressDataException(s"Can't find road link for target link id $linkId")
    } else {
      roadAddressLinkBuilder.build(roadLinks.head, UnaddressedRoadLink(linkId = linkId, None, None, RoadType.Unknown, None, None, None, None, anomaly = Anomaly.NoAddressGiven, Seq.empty[Point]))
    }
  }

  def createUnaddressedRoadLink(unaddressedRoadLink: Seq[UnaddressedRoadLink]): Unit = {
    withDynTransaction {
      unaddressedRoadLink.foreach(createSingleUnaddressedRoadLink)
    }
  }

  private def createSingleUnaddressedRoadLink(unaddressedRoadLink: UnaddressedRoadLink): Unit = {
    throw new NotImplementedError("Will be implemented at VIITE-1542 and VIITE-1538")
//    RoadAddressDAO.createUnaddressedRoadLink(unaddressedRoadLink)
  }

  def mergeRoadAddress(data: RoadAddressMerge): Unit = {
    try {
      withDynTransaction {
        mergeRoadAddressInTX(data)
      }
    } catch {
      case ex: InvalidAddressDataException => logger.error("Duplicate merging(s) found, skipped.", ex)
      case ex: ConnectException => logger.error("A connection problem has occurred.", ex)
      case ex: Exception => logger.error("An unexpected error occurred.", ex)
    }
  }

  def mergeRoadAddressHistory(data: RoadAddressMerge): Unit = {
    try {
      withDynTransaction {
        mergeRoadAddressHistoryInTX(data)
      }
    } catch {
      case ex: InvalidAddressDataException => logger.error("Duplicate merging(s) found, skipped.", ex)
      case ex: ConnectException => logger.error("A connection problem has occurred.", ex)
      case ex: Exception => logger.error("An unexpected error occurred.", ex)
    }
  }

  def mergeRoadAddressInTX(data: RoadAddressMerge): Unit = {
    throw new NotImplementedError("This method probably will not be needed anymore, the fuse process can only be applied to linear location")
//    val unMergedCount = RoadAddressDAO.queryById(data.merged).size
//    if (unMergedCount != data.merged.size)
//      throw new InvalidAddressDataException("Data modified while updating, rolling back transaction: some source rows no longer valid")
//    val mergedCount = expireRoadAddresses(data.merged)
//    if (mergedCount == data.merged.size)
//      createMergedSegments(data.created)
//    else
//      throw new InvalidAddressDataException("Data modified while updating, rolling back transaction: some source rows not updated")
  }

  def mergeRoadAddressHistoryInTX(data: RoadAddressMerge): Unit = {
    throw new NotImplementedError("This method probably will not be needed anymore, the fuse process can only be applied to linear location")
//    val unMergedCount = RoadAddressDAO.queryById(data.merged).size
//    if (unMergedCount != data.merged.size)
//      throw new InvalidAddressDataException("Data modified while updating, rolling back transaction: some source rows no longer valid")
//    val mergedCount = expireRoadAddresses(data.merged)
//    if (mergedCount == data.merged.size)
//      createMergedSegments(data.created)
//    else
//      throw new InvalidAddressDataException("Data modified while updating, rolling back transaction: some source rows not updated")
  }

  private def createMergedSegments(mergedRoadAddress: Seq[RoadAddress]): Unit = {
    throw new NotImplementedError("This method probably will not be needed anymore, and if it's needed can be done a batch execution")
//    mergedRoadAddress.grouped(500).foreach(group => RoadAddressDAO.create(group, Some("Automatic_merged")))
  }

  def expireRoadAddresses(expiredIds: Set[Long]): Int = {
    throw new NotImplementedError("This method probably will not be needed anymore, and if it's needed can be done a batch execution")
    //expiredIds.grouped(500).map(group => RoadAddressDAO.expireById(group)).sum
  }

  //TODO check if this is needed in VIITE-1538
//  /**
//    * Checks that if the geometry is found and updates the geometry to match or sets it floating if not found
//    *
//    * @param ids
//    */
//  def checkRoadAddressFloating(ids: Set[Long]): Unit = {
//    withDynTransaction {
//      checkRoadAddressFloatingWithoutTX(ids)
//    }
//  }

  /**
    * For easier unit testing and use
    *
    * @param ids
    */
  def checkRoadAddressFloatingWithoutTX(ids: Set[Long], float: Boolean = false): Unit = {
    throw new NotImplementedError("Will be implemented at VIITE-1538")
    //    def hasTargetRoadLink(roadLinkOpt: Option[RoadLinkLike], geometryOpt: Option[Seq[Point]]) = {
//      !(roadLinkOpt.isEmpty || geometryOpt.isEmpty || GeometryUtils.geometryLength(geometryOpt.get) == 0.0)
//    }
//
//    val addresses = RoadAddressDAO.queryById(ids)
//    val linkIdMap = addresses.groupBy(_.linkId).mapValues(_.map(_.id))
//    val roadLinks = roadLinkService.getCurrentAndComplementaryVVHRoadLinks(linkIdMap.keySet, frozenTimeVVHAPIServiceEnabled)
//    addresses.foreach { address =>
//      val roadLink = roadLinks.find(_.linkId == address.linkId)
//      val addressGeometry = roadLink.map(rl =>
//        GeometryUtils.truncateGeometry3D(rl.geometry, address.startMValue, address.endMValue))
//      if (float && hasTargetRoadLink(roadLink, addressGeometry)) {
//        logger.info(s"Floating and update geometry id ${address.id} (link id ${address.linkId})")
//        RoadAddressDAO.changeRoadAddressFloatingWithHistory(address.id, addressGeometry, FloatingReason.GeometryChanged)
//        val missing = UnaddressedRoadLink(address.linkId, Some(address.startAddrMValue), Some(address.endAddrMValue), RoadAddressLinkBuilder.getRoadType(roadLink.get.administrativeClass, UnknownLinkType), None, None, Some(address.startMValue), Some(address.endMValue), Anomaly.GeometryChanged, Seq.empty[Point])
//        RoadAddressDAO.createUnaddressedRoadLink(missing.linkId, missing.startAddrMValue.getOrElse(0), missing.endAddrMValue.getOrElse(0), missing.anomaly.value, missing.startMValue.get, missing.endMValue.get)
//      } else if (!hasTargetRoadLink(roadLink, addressGeometry)) {
//        logger.info(s"Floating id ${address.id}")
//        RoadAddressDAO.changeRoadAddressFloatingWithHistory(address.id, None, FloatingReason.NewAddressGiven)
//      } else {
//        if (!GeometryUtils.areAdjacent(addressGeometry.get, address.geometry)) {
//          logger.info(s"Updating geometry for id ${address.id} (link id ${address.linkId})")
//          RoadAddressDAO.changeRoadAddressFloatingWithHistory(address.id, addressGeometry, FloatingReason.GapInGeometry)}
//      }
//    }
  }

  def convertRoadAddressToFloating(linkId: Long): Unit = {
    throw new NotImplementedError("Will be implemented at VIITE-1537")
    //    withDynTransaction {
//      val addresses = RoadAddressDAO.fetchByLinkId(Set(linkId), includeHistory = false, includeTerminated = false)
//      addresses.foreach { address =>
//        logger.info(s"Floating and update geometry id ${address.id} (link id ${address.linkId})")
//        RoadAddressDAO.changeRoadAddressFloatingWithHistory(address.id, None, floatingReason = FloatingReason.ManualFloating)
//        RoadAddressDAO.createUnaddressedRoadLink(address.linkId, address.startAddrMValue, address.endAddrMValue, Anomaly.GeometryChanged.value, address.startMValue, address.endMValue)
//      }
//    }
  }

  def saveAdjustments(addresses: Seq[LinearLocationAdjustment]): Unit = {
    throw new NotImplementedError("Can be fixed at VIITE-1552")
//    withDynTransaction {
//      addresses.foreach(RoadAddressDAO.updateLinearLocation)
//    }
  }

//  def getAdjacentAddresses(chainLinks: Set[Long], chainIds: Set[Long], linkId: Long,
//                           id: Long, roadNumber: Long, roadPartNumber: Long, track: Track) = {
//    withDynSession {
//      getAdjacentAddressesInTX(chainLinks, chainIds, linkId, id, roadNumber, roadPartNumber, track)
//    }
//  }
//
//  def getAdjacentAddressesInTX(chainLinks: Set[Long], chainIds: Set[Long], linkId: Long, id: Long, roadNumber: Long, roadPartNumber: Long, track: Track) = {
//    val roadAddresses = (if (chainIds.nonEmpty)
//      RoadAddressDAO.queryById(chainIds)
//    else if (chainLinks.nonEmpty)
//      RoadAddressDAO.fetchByLinkId(chainLinks, includeFloating = true, includeHistory = false)
//    else Seq.empty[RoadAddress]
//      ).sortBy(_.startAddrMValue)
//    assert(roadAddresses.forall(r => r.roadNumber == roadNumber && r.roadPartNumber == roadPartNumber && r.track == track),
//      s"Mixed floating addresses selected ($roadNumber/$roadPartNumber/$track): " + roadAddresses.map(r =>
//        s"${r.linkId} = ${r.roadNumber}/${r.roadPartNumber}/${r.track.value}").mkString(", "))
//    val startValues = roadAddresses.map(_.startAddrMValue)
//    val endValues = roadAddresses.map(_.endAddrMValue)
//    val orphanStarts = startValues.filterNot(st => endValues.contains(st))
//    val orphanEnds = endValues.filterNot(st => startValues.contains(st))
//    (orphanStarts.flatMap(st => RoadAddressDAO.fetchByAddressEnd(roadNumber, roadPartNumber, track, st))
//      ++ orphanEnds.flatMap(end => RoadAddressDAO.fetchByAddressStart(roadNumber, roadPartNumber, track, end)))
//      .distinct.filterNot(fo => chainIds.contains(fo.id) || chainLinks.contains(fo.linkId))
//  }

  def getFloatingAdjacent(chainLinks: Set[Long], chainIds: Set[Long], linkId: Long, id: Long, roadNumber: Long, roadPartNumber: Long, trackCode: Int): Seq[RoadAddressLink] = {
    throw new NotImplementedError("Will be implemented at VIITE-1537")
//    val (floatings, _) = withDynTransaction {
//      RoadAddressDAO.fetchByRoadPart(roadNumber, roadPartNumber, includeFloating = true).partition(_.isFloating)
//    }
//    val historyLinks = time(logger, "Fetch floating history links") {
//      roadLinkService.getRoadLinksHistoryFromVVH(floatings.map(_.linkId).toSet)
//    }
//    if (historyLinks.nonEmpty) {
//      val historyLinkAddresses = time(logger, "Build history link addresses") {
//        historyLinks.flatMap(fh => {
//          buildFloatingRoadAddressLink(fh, floatings.filter(_.linkId == fh.linkId))
//        })
//      }
//      historyLinkAddresses.find(_.id == id).orElse(historyLinkAddresses.find(_.linkId == linkId).orElse(Option.empty[RoadAddressLink])) match {
//        case Some(sel) => {
//          historyLinkAddresses.filter(ra => {
//            ra.id != id && GeometryUtils.areAdjacent(ra.geometry, sel.geometry) && !chainIds.contains(ra.id)
//          })
//        }
//        case _ => Seq.empty[RoadAddressLink]
//      }
//    } else {
//      Seq.empty[RoadAddressLink]
//    }
  }

  def getAdjacent(chainLinks: Set[Long], linkId: Long, newSession: Boolean = true): Seq[RoadAddressLink] = {
    throw new NotImplementedError("Will be implemented at VIITE-1537")
    //TODO this method will need to check the adjacency between missing road address geometry
//    val chainRoadLinks = roadLinkService.getRoadLinksByLinkIdsFromVVH(chainLinks, frozenTimeVVHAPIServiceEnabled)
//    val pointCloud = chainRoadLinks.map(_.geometry).map(GeometryUtils.geometryEndpoints).flatMap(x => Seq(x._1, x._2))
//    val boundingPoints = GeometryUtils.boundingRectangleCorners(pointCloud)
//    val boundingRectangle = BoundingRectangle(boundingPoints._1 + Vector3d(-.1, .1, 0.0), boundingPoints._2 + Vector3d(.1, -.1, 0.0))
//    val connectedLinks = roadLinkService.getRoadLinksAndChangesFromVVHWithFrozenAPI(boundingRectangle, frozenTimeVVHAPIServiceEnabled)._1
//      .filterNot(rl => chainLinks.contains(rl.linkId))
//      .filter { rl =>
//        val endPoints = GeometryUtils.geometryEndpoints(rl.geometry)
//        pointCloud.exists(p => GeometryUtils.areAdjacent(p, endPoints._1) || GeometryUtils.areAdjacent(p, endPoints._2))
//      }.map(rl => rl.linkId -> rl).toMap
//    val missingLinks = if (newSession) {
//        withDynSession {
//          RoadAddressDAO.getUnaddressedRoadLinks(connectedLinks.keySet)
//        }
//      } else {
//        RoadAddressDAO.getUnaddressedRoadLinks(connectedLinks.keySet)
//      }
//    missingLinks.map(ml => RoadAddressLinkBuilder.build(connectedLinks(ml.linkId), ml))
  }

  def getRoadAddressLinksAfterCalculation(sources: Seq[String], targets: Seq[String], user: User): Seq[RoadAddressLink] = {
    throw new NotImplementedError("Will be implemented at VIITE-1537")
//    val transferredRoadAddresses = getRoadAddressesAfterCalculation(sources, targets, user)
//    val target = roadLinkService.getRoadLinksByLinkIdsFromVVH(targets.map(rd => rd.toLong).toSet, frozenTimeVVHAPIServiceEnabled)
//    transferredRoadAddresses.filter(_.endDate.isEmpty).map(ra => RoadAddressLinkBuilder.build(target.find(_.linkId == ra.linkId).get, ra))
  }

  def getRoadAddressesAfterCalculation(sources: Seq[String], targets: Seq[String], user: User): Seq[RoadAddress] = {
    throw new NotImplementedError("Will be implemented at VIITE-1537")
//    def adjustGeometry(ra: RoadAddress, link: RoadAddressLinkLike): RoadAddress = {
//      val geom = GeometryUtils.truncateGeometry3D(link.geometry, ra.startMValue, ra.endMValue)
//      ra.copy(geometry = geom, linkGeomSource = link.roadLinkSource)
//    }
//
//    val sourceRoadAddressLinks = sources.flatMap(rd => {
//      getRoadAddressLink(rd.toLong)
//    })
//    val targetIds = targets.map(rd => rd.toLong).toSet
//    val targetRoadAddressLinks = targetIds.toSeq.map(getTargetRoadLink)
//    val targetLinkMap: Map[Long, RoadAddressLinkLike] = targetRoadAddressLinks.map(l => l.linkId -> l).toMap
//    transferRoadAddress(sourceRoadAddressLinks, targetRoadAddressLinks, user).map(ra => adjustGeometry(ra, targetLinkMap(ra.linkId)))
  }

  def transferFloatingToGap(sourceIds: Set[Long], targetIds: Set[Long], roadAddresses: Seq[RoadAddress], username: String): Unit = {
    throw new NotImplementedError("Will be implemented at VIITE-1537")
//    val hasFloatings = withDynTransaction {
//      val currentRoadAddresses = RoadAddressDAO.fetchByLinkId(sourceIds, includeFloating = true, includeTerminated = false)
//      RoadAddressDAO.expireById(currentRoadAddresses.map(_.id).toSet)
//      RoadAddressDAO.create(roadAddresses, Some(username))
//      val roadNumber = roadAddresses.head.roadNumber.toInt
//      val roadPartNumber = roadAddresses.head.roadPartNumber.toInt
//      if(RoadAddressDAO.fetchFloatingRoadAddressesBySegment(roadNumber, roadPartNumber).filterNot(address => sourceIds.contains(address.linkId)).isEmpty) {
//        if (!recalculateRoadAddresses(roadNumber, roadPartNumber))
//          throw new RoadAddressException(s"Road address recalculation failed for $roadNumber / $roadPartNumber")
//      }
//      RoadAddressDAO.fetchAllFloatingRoadAddresses().nonEmpty
//    }
//    if (!hasFloatings)
//      eventbus.publish("roadAddress:RoadNetworkChecker", RoadCheckOptions(Seq()))
  }
}

sealed trait RoadClass {
  def value: Int

  def roads: Seq[Int]
}

object RoadClass {
  val values = Set(HighwayClass, MainRoadClass, RegionalClass, ConnectingClass, MinorConnectingClass, StreetClass
    , RampsAndRoundAboutsClass, PedestrianAndBicyclesClassA, PedestrianAndBicyclesClassB, WinterRoadsClass, PathsClass, ConstructionSiteTemporaryClass, PrivateRoadClass, NoClass)

  def get(roadNumber: Int): Int = {
    values.find(_.roads contains roadNumber).getOrElse(NoClass).value
  }

  case object HighwayClass extends RoadClass { def value = 1; def roads: Range.Inclusive = 1 to 39;}
  case object MainRoadClass extends RoadClass { def value = 2; def roads: Range.Inclusive = 40 to 99;}
  case object RegionalClass extends RoadClass { def value = 3; def roads: Range.Inclusive = 100 to 999;}
  case object ConnectingClass extends RoadClass { def value = 4; def roads: Range.Inclusive = 1000 to 9999;}
  case object MinorConnectingClass extends RoadClass { def value = 5; def roads: Range.Inclusive = 10000 to 19999;}
  case object StreetClass extends RoadClass { def value = 6; def roads: Range.Inclusive = 40000 to 49999;}
  case object RampsAndRoundAboutsClass extends RoadClass { def value = 7; def roads: Range.Inclusive = 20001 to 39999;}
  case object PedestrianAndBicyclesClassA extends RoadClass { def value = 8; def roads: Range.Inclusive = 70001 to 89999;}
  case object PedestrianAndBicyclesClassB extends RoadClass { def value = 8; def roads: Range.Inclusive = 90001 to 99999;}
  case object WinterRoadsClass extends RoadClass { def value = 9; def roads: Range.Inclusive = 60001 to 61999;}
  case object PathsClass extends RoadClass { def value = 10; def roads: Range.Inclusive = 62001 to 62999;}
  case object ConstructionSiteTemporaryClass extends RoadClass { def value = 11; def roads: Range.Inclusive = 9900 to 9999;}
  case object PrivateRoadClass extends RoadClass { def value = 12; def roads: Range.Inclusive = 50001 to 59999;}
  case object NoClass extends RoadClass { def value = 99; def roads: Range.Inclusive = 0 to 0;}
}
//TODO check if this is needed
class Contains(r: Range) {
  def unapply(i: Int): Boolean = r contains i
}

case class RoadAddressMerge(merged: Set[Long], created: Seq[RoadAddress])

case class LinearLocationResult(current: Seq[LinearLocation], floating: Seq[LinearLocation])

case class BoundingBoxResult(changeInfoF: Future[Seq[ChangeInfo]], roadAddressResultF: Future[(Seq[LinearLocation], Seq[VVHHistoryRoadLink])],
                             roadLinkF: Future[Seq[RoadLink]], complementaryF: Future[Seq[RoadLink]], suravageF: Future[Seq[VVHRoadlink]])

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
    val values = Set(OverlappingRoadAddresses, InconsistentTopology, InconsistentLrmHistory)

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

    def apply(intValue: Int): AddressError = {
      values.find(_.value == intValue).get
    }
  }

  case class AddressErrorDetails(linearLocationId: Long, linkId: Long, roadNumber: Long, roadPartNumber: Long, addressError: AddressError, ely: Long)

}

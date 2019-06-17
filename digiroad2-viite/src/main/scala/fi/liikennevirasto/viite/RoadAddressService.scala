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
import fi.liikennevirasto.viite.dao.CalibrationPointDAO.CalibrationPointType
import fi.liikennevirasto.viite.dao.RoadwayPoint
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
import scala.util.control.NonFatal

class RoadAddressService(roadLinkService: RoadLinkService, roadwayDAO: RoadwayDAO, linearLocationDAO: LinearLocationDAO, roadNetworkDAO: RoadNetworkDAO, roadwayAddressMapper: RoadwayAddressMapper, eventbus: DigiroadEventBus, frozenTimeVVHAPIServiceEnabled: Boolean = false) {

  def withDynTransaction[T](f: => T): T = OracleDatabase.withDynTransaction(f)

  def withDynSession[T](f: => T): T = OracleDatabase.withDynSession(f)

  private val logger = LoggerFactory.getLogger(getClass)

  private def roadAddressLinkBuilder = new RoadAddressLinkBuilder(roadwayDAO, linearLocationDAO, new ProjectLinkDAO)

  /**
    * Smallest mvalue difference we can tolerate to be "equal to zero". One micrometer.
    * See https://en.wikipedia.org/wiki/Floating_point#Accuracy_problems
    */
  val Epsilon = 1

  val roadwayPointDAO = new RoadwayPointDAO

  private def fetchLinearLocationsByBoundingBox(boundingRectangle: BoundingRectangle, roadNumberLimits: Seq[(Int, Int)] = Seq()) = {
    val linearLocations = withDynSession {
      time(logger, "Fetch addresses") {
        linearLocationDAO.fetchRoadwayByBoundingBox(boundingRectangle, roadNumberLimits)
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
        linearLocationDAO.fetchRoadwayByBoundingBox(boundingRectangle, roadNumberLimits)
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
        linearLocationDAO.fetchRoadwayByBoundingBox(boundingRectangle, roadNumberLimits)
      }
    }
    val linearLocationsLinkIds = linearLocations.map(_.linkId).toSet

    val boundingBoxResult = BoundingBoxResult(
      roadLinkService.getChangeInfoFromVVHF(linearLocationsLinkIds),
      Future((linearLocations, roadLinkService.getRoadLinksHistoryFromVVH(linearLocationsLinkIds))),
      Future(roadLinkService.getRoadLinksByLinkIdsFromVVH(linearLocationsLinkIds, frozenTimeVVHAPIServiceEnabled)),
      Future(Seq()),
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
    val roadAddresses = withDynTransaction {
      val linearLocations = linearLocationDAO.fetchRoadwayByBoundingBox(boundingRectangle, roadNumberLimits)
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
  def getAllByMunicipality(municipality: Int): Seq[RoadAddressLink] = {

    val suravageRoadLinksF = Future(roadLinkService.getSuravageRoadLinks(municipality))

    val (roadLinks, _) = roadLinkService.getRoadLinksWithComplementaryAndChangesFromVVH(municipality, frozenTimeVVHAPIServiceEnabled)

    val allRoadLinks = roadLinks ++ Await.result(suravageRoadLinksF, atMost = Duration.create(1, TimeUnit.HOURS))

    val linearLocations = withDynTransaction {
      time(logger, "Fetch addresses") {
        linearLocationDAO.fetchRoadwayByLinkId(allRoadLinks.map(_.linkId).toSet)
      }
    }
    val (adjustedLinearLocations, changeSet) = if (frozenTimeVVHAPIServiceEnabled) (linearLocations, Seq()) else RoadAddressFiller.adjustToTopology(allRoadLinks, linearLocations)
    if (!frozenTimeVVHAPIServiceEnabled) {
      //TODO we should think to update both servers with cache at the same time, and before the apply change batch that way we will not need to do any kind of changes here
      eventbus.publish("roadAddress:persistChangeSet", changeSet)
    }


    val roadAddresses = withDynTransaction {
      roadNetworkDAO.getLatestRoadNetworkVersionId match {
        case Some(roadNetworkId) => roadwayAddressMapper.getNetworkVersionRoadAddressesByLinearLocation(adjustedLinearLocations, roadNetworkId)
        case _ => roadwayAddressMapper.getCurrentRoadAddressesByLinearLocation(adjustedLinearLocations)
      }
    }

    roadAddresses.flatMap { ra =>
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
    * @param fetchOnlyEnd The optional parameter that allows the search for the link with bigger endAddrM value
    * @return Returns all the filtered road addresses
    */
  def getRoadAddressWithRoadAndPart(road: Long, part: Long, withHistory: Boolean = false, fetchOnlyEnd: Boolean = false): Seq[RoadAddress] = {
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
    if (OracleDatabase.isWithinSession) {
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
    val roadAddresses = roadwayAddressMapper.getRoadAddressesByRoadway(roadways)
    roadAddresses
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
            handleCalibrationPoints(roadwayLinearLocations, createdBy = "applyChanges")
          }
      }

      linearLocationDAO.create(changeSet.newLinearLocations.map(l => l.copy(id = NewIdValue)))
      handleCalibrationPoints(changeSet.newLinearLocations, createdBy = "applyChanges")
      //TODO Implement the missing at user story VIITE-1596
    }

  }

  private def getSuravageRoadLinkAddresses(boundingRectangle: BoundingRectangle, boundingBoxResult: BoundingBoxResult): Seq[RoadAddressLink] = {
    withDynSession {
      Await.result(boundingBoxResult.suravageF, Duration.Inf).map(x => (x, None)).map(roadAddressLinkBuilder.buildSuravageRoadAddressLink)
    }
  }

  def getSuravageRoadLinkAddressesByLinkIds(linkIdsToGet: Set[Long]): Seq[RoadAddressLink] = {
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

  def handleCalibrationPoints(linearLocations: Iterable[LinearLocation], createdBy: String = "-"): Unit = {
    val startCalibrationPointsToCheck = linearLocations.filter(_.startCalibrationPoint.isDefined)
    val endCalibrationPointsToCheck = linearLocations.filter(_.endCalibrationPoint.isDefined)

    // Fetch current linear locations and check which calibration points should be expired
    val currentCPs = CalibrationPointDAO.fetchByLinkId(linearLocations.map(l => l.linkId))
    val currentStartCP = currentCPs.filter(_.startOrEnd == 0)
    val currentEndCP = currentCPs.filter(_.startOrEnd == 1)
    val startCPsToBeExpired = currentStartCP.filter(c => startCalibrationPointsToCheck.filter(sc => sc.linkId == c.linkId).isEmpty)
    val endCPsToBeExpired = currentEndCP.filter(c => endCalibrationPointsToCheck.filter(sc => sc.linkId == c.linkId).isEmpty)

    // Expire calibration points
    startCPsToBeExpired.foreach {
      ll =>
        val cal = CalibrationPointDAO.fetch(ll.linkId, startOrEnd = 0)
        if (cal.isDefined) {
          CalibrationPointDAO.expireById(Set(cal.get.id))
        } else {
          logger.error(s"Failed to expire start calibration point for link id: ${ll.linkId}")
        }
    }
    endCPsToBeExpired.foreach {
      ll =>
        val cal = CalibrationPointDAO.fetch(ll.linkId, startOrEnd = 1)
        if (cal.isDefined) {
          CalibrationPointDAO.expireById(Set(cal.get.id))
        } else {
          logger.error(s"Failed to expire end calibration point for link id: ${ll.linkId}")
        }
    }

    // Check other calibration points
    startCalibrationPointsToCheck.foreach {
      cal =>
        val calibrationPoint = CalibrationPointDAO.fetch(cal.linkId, startOrEnd = 0)
        if (calibrationPoint.isDefined)
          roadwayPointDAO.update(calibrationPoint.get.roadwayPointId, cal.roadwayNumber, cal.startCalibrationPoint.get, createdBy)
        else {
          val roadwayPointId =
            roadwayPointDAO.fetch(cal.roadwayNumber, cal.startCalibrationPoint.get) match {
              case Some(roadwayPoint) =>
                roadwayPoint.id
              case _ => roadwayPointDAO.create(cal.roadwayNumber, cal.startCalibrationPoint.get, createdBy)
            }
          CalibrationPointDAO.create(roadwayPointId, cal.linkId, startOrEnd = 0, calType = CalibrationPointType.Mandatory, createdBy = createdBy)
        }
    }
    endCalibrationPointsToCheck.foreach {
      cal =>
        val calibrationPoint = CalibrationPointDAO.fetch(cal.linkId, startOrEnd = 1)
        if (calibrationPoint.isDefined)
          roadwayPointDAO.update(calibrationPoint.get.roadwayPointId, cal.roadwayNumber, cal.endCalibrationPoint.get, createdBy)
        else {
          val roadwayPointId =
            roadwayPointDAO.fetch(cal.roadwayNumber, cal.endCalibrationPoint.get) match {
              case Some(roadwayPoint) =>
                roadwayPoint.id
              case _ => roadwayPointDAO.create(cal.roadwayNumber, cal.endCalibrationPoint.get, createdBy)
            }
          CalibrationPointDAO.create(roadwayPointId, cal.linkId, startOrEnd = 1, calType = CalibrationPointType.Mandatory, createdBy = createdBy)
        }
    }
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

  case object HighwayClass extends RoadClass {
    def value = 1;

    def roads: Range.Inclusive = 1 to 39;
  }

  case object MainRoadClass extends RoadClass {
    def value = 2;

    def roads: Range.Inclusive = 40 to 99;
  }

  case object RegionalClass extends RoadClass {
    def value = 3;

    def roads: Range.Inclusive = 100 to 999;
  }

  case object ConnectingClass extends RoadClass {
    def value = 4;

    def roads: Range.Inclusive = 1000 to 9999;
  }

  case object MinorConnectingClass extends RoadClass {
    def value = 5;

    def roads: Range.Inclusive = 10000 to 19999;
  }

  case object StreetClass extends RoadClass {
    def value = 6;

    def roads: Range.Inclusive = 40000 to 49999;
  }

  case object RampsAndRoundAboutsClass extends RoadClass {
    def value = 7;

    def roads: Range.Inclusive = 20001 to 39999;
  }

  case object PedestrianAndBicyclesClassA extends RoadClass {
    def value = 8;

    def roads: Range.Inclusive = 70001 to 89999;
  }

  case object PedestrianAndBicyclesClassB extends RoadClass {
    def value = 8;

    def roads: Range.Inclusive = 90001 to 99999;
  }

  case object WinterRoadsClass extends RoadClass {
    def value = 9;

    def roads: Range.Inclusive = 60001 to 61999;
  }

  case object PathsClass extends RoadClass {
    def value = 10;

    def roads: Range.Inclusive = 62001 to 62999;
  }

  case object ConstructionSiteTemporaryClass extends RoadClass {
    def value = 11;

    def roads: Range.Inclusive = 9900 to 9999;
  }

  case object PrivateRoadClass extends RoadClass {
    def value = 12;

    def roads: Range.Inclusive = 50001 to 59999;
  }

  case object NoClass extends RoadClass {
    def value = 99;

    def roads: Range.Inclusive = 0 to 0;
  }

}

//TODO check if this is needed
class Contains(r: Range) {
  def unapply(i: Int): Boolean = r contains i
}

case class RoadAddressMerge(merged: Set[Long], created: Seq[RoadAddress])

case class LinearLocationResult(current: Seq[LinearLocation])

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
    val values = Set(OverlappingRoadAddresses, InconsistentTopology, InconsistentLrmHistory, Inconsistent2TrackCalibrationPoints, InconsistentContinuityCalibrationPoints, MissingEdgeCalibrationPoints,
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

package fi.liikennevirasto.viite

import java.net.ConnectException

import fi.liikennevirasto.digiroad2._
import fi.liikennevirasto.digiroad2.asset._
import fi.liikennevirasto.digiroad2.client.vvh.FeatureClass.AllOthers
import fi.liikennevirasto.digiroad2.client.vvh._
import fi.liikennevirasto.digiroad2.linearasset.{RoadLink, RoadLinkLike}
import fi.liikennevirasto.digiroad2.oracle.OracleDatabase
import fi.liikennevirasto.digiroad2.service.{RoadLinkService, RoadLinkType}
import fi.liikennevirasto.digiroad2.user.User
import fi.liikennevirasto.digiroad2.util.LogUtils.time
import fi.liikennevirasto.digiroad2.util.Track
import fi.liikennevirasto.viite.dao._
import fi.liikennevirasto.viite.model.{Anomaly, RoadAddressLink, RoadAddressLinkLike}
import fi.liikennevirasto.viite.process.RoadAddressFiller.{AddressChangeSet, LRMValueAdjustment}
import fi.liikennevirasto.viite.process._
import org.joda.time.DateTime
import org.slf4j.LoggerFactory

import scala.collection.immutable.SortedMap
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future}

class RoadAddressService(roadLinkService: RoadLinkService, eventbus: DigiroadEventBus, frozenTimeVVHAPIServiceEnabled: Boolean = false) {

  def withDynTransaction[T](f: => T): T = OracleDatabase.withDynTransaction(f)

  def withDynSession[T](f: => T): T = OracleDatabase.withDynSession(f)

  private val logger = LoggerFactory.getLogger(getClass)

  val HighwayClass = 1
  val MainRoadClass = 2
  val RegionalClass = 3
  val ConnectingClass = 4
  val MinorConnectingClass = 5
  val StreetClass = 6
  val RampsAndRoundAboutsClass = 7
  val PedestrianAndBicyclesClass = 8
  val WinterRoadsClass = 9
  val PathsClass = 10
  val ConstructionSiteTemporaryClass = 11
  val NoClass = 99

  val MaxAllowedMValueError = 0.001
  val Epsilon = 1
  /* Smallest mvalue difference we can tolerate to be "equal to zero". One micrometer.
                                See https://en.wikipedia.org/wiki/Floating_point#Accuracy_problems
                             */
  val MaxDistanceDiffAllowed = 1.0
  /*Temporary restriction from PO: Filler limit on modifications
                                            (LRM adjustments) is limited to 1 meter. If there is a need to fill /
                                            cut more than that then nothing is done to the road address LRM data.
                                            */
  val MinAllowedRoadAddressLength = 0.1
  val newTransaction = true

  class Contains(r: Range) {
    def unapply(i: Int): Boolean = r contains i
  }

  private def fetchRoadLinksWithComplementary(boundingRectangle: BoundingRectangle, roadNumberLimits: Seq[(Int, Int)], municipalities: Set[Int],
                                              everything: Boolean = false, publicRoads: Boolean = false): (Seq[RoadLink], Seq[RoadLink]) = {
    val roadLinksF = Future(roadLinkService.getRoadLinksFromVVH(boundingRectangle, roadNumberLimits, municipalities, everything, publicRoads, frozenTimeVVHAPIServiceEnabled))
    val complementaryLinksF = Future(roadLinkService.getComplementaryRoadLinksFromVVH(boundingRectangle, municipalities))
    val (roadLinks, complementaryLinks) = Await.result(roadLinksF.zip(complementaryLinksF), Duration.Inf)
    (roadLinks, complementaryLinks)
  }

  private def fetchRoadAddressesByBoundingBox(boundingRectangle: BoundingRectangle, fetchOnlyFloating: Boolean = false,
                                              onlyNormalRoads: Boolean = false, roadNumberLimits: Seq[(Int, Int)] = Seq()) = {
    val (floatingAddresses, nonFloatingAddresses) = withDynTransaction {
      time(logger, "Fetch floating and non-floating addresses") {
        RoadAddressDAO.fetchRoadAddressesByBoundingBox(boundingRectangle, fetchOnlyFloating, onlyNormalRoads, roadNumberLimits).partition(_.floating)
      }
    }
    val floatingHistoryRoadLinks = withDynTransaction {
      time(logger, "Fetch floating history links") {
        roadLinkService.getRoadLinksHistoryFromVVH(floatingAddresses.map(_.linkId).toSet)
      }
    }
    val historyLinkAddresses = time(logger, "Build history link addresses") {
      floatingHistoryRoadLinks.flatMap(fh => {
        buildFloatingRoadAddressLink(fh, floatingAddresses.filter(_.linkId == fh.linkId))
      })
    }

    RoadAddressResult(historyLinkAddresses, nonFloatingAddresses, floatingAddresses)
  }

  private def fetchMissingRoadAddressesByBoundingBox(boundingRectangle: BoundingRectangle, fetchOnlyFloating: Boolean = false) = {
    withDynTransaction {
      time(logger, "RoadAddressDAO.fetchMissingRoadAddressesByBoundingBox") {
        RoadAddressDAO.fetchMissingRoadAddressesByBoundingBox(boundingRectangle).groupBy(_.linkId)
      }
    }
  }

  def buildFloatingRoadAddressLink(rl: VVHHistoryRoadLink, roadAddrSeq: Seq[RoadAddress]): Seq[RoadAddressLink] = {
    val fusedRoadAddresses = RoadAddressLinkBuilder.fuseRoadAddressWithTransaction(roadAddrSeq)
    fusedRoadAddresses.map(ra => {
      RoadAddressLinkBuilder.build(rl, ra)
    })
  }

  def getSuravageRoadLinkAddresses(boundingRectangle: BoundingRectangle, boundingBoxResult: BoundingBoxResult): Seq[RoadAddressLink] = {
    withDynSession {
      Await.result(boundingBoxResult.suravageF, Duration.Inf).map(x => (x, None)).map(RoadAddressLinkBuilder.buildSuravageRoadAddressLink)
    }
  }

  def getSuravageRoadLinkAddressesByLinkIds(linkIdsToGet: Set[Long]): Seq[RoadAddressLink] = {
    val suravageLinks = roadLinkService.getSuravageRoadLinksFromVVH(linkIdsToGet)
    withDynSession {
      suravageLinks.map(x => (x, None)).map(RoadAddressLinkBuilder.buildSuravageRoadAddressLink)
    }
  }

  def fetchBoundingBoxF(boundingRectangle: BoundingRectangle, roadNumberLimits: Seq[(Int, Int)],
                        everything: Boolean = false, publicRoads: Boolean = false): BoundingBoxResult = {
    BoundingBoxResult(
      roadLinkService.getChangeInfoFromVVHF(boundingRectangle, Set()),
      Future(fetchRoadAddressesByBoundingBox(boundingRectangle)),
      Future(roadLinkService.getRoadLinksFromVVH(boundingRectangle, roadNumberLimits, Set(), everything, publicRoads, frozenTimeVVHAPIServiceEnabled)),
      Future(roadLinkService.getComplementaryRoadLinksFromVVH(boundingRectangle, Set())),
      roadLinkService.getSuravageLinksFromVVHF(boundingRectangle, Set())
    )
  }

  def getRoadAddressLinksWithSuravage(boundingRectangle: BoundingRectangle, roadNumberLimits: Seq[(Int, Int)],
                                      everything: Boolean = false, publicRoads: Boolean = false): Seq[RoadAddressLink] = {
    val combinedFuture = fetchBoundingBoxF(boundingRectangle, roadNumberLimits, everything, publicRoads)
    val roadAddressLinks = getRoadAddressLinks(combinedFuture, boundingRectangle, Seq(), everything)
    val suravageAddresses = getSuravageRoadLinkAddresses(boundingRectangle, combinedFuture)
    setBlackUnderline(suravageAddresses ++ roadAddressLinks)
  }

  def buildFloatingAddresses(allRoadLinks: Seq[RoadLink], suravageLinks: Seq[VVHRoadlink], floating: Seq[RoadAddress]): Seq[RoadAddressLink] = {
    // For the purpose of the use of this conversion we do not need a accurate start date and end date since it comes from the Road address on the builder
    def toHistoryRoadLink(roadLinkLike: RoadLinkLike): VVHHistoryRoadLink = {
      val featureClassCode = roadLinkLike.attributes.getOrElse("MTKCLASS", BigInt(0)).asInstanceOf[BigInt].intValue()
      VVHHistoryRoadLink(roadLinkLike.linkId, roadLinkLike.municipalityCode, roadLinkLike.geometry, roadLinkLike.administrativeClass, roadLinkLike.trafficDirection, AllOthers,
        roadLinkLike.vvhTimeStamp, roadLinkLike.vvhTimeStamp, roadLinkLike.attributes, roadLinkLike.constructionType, roadLinkLike.linkSource, roadLinkLike.length)
    }

    val combinedRoadLinks = (allRoadLinks ++ suravageLinks).filter(crl => floating.map(_.linkId).contains(crl.linkId))
    combinedRoadLinks.flatMap(fh => {
      val actualFloatings = floating.filter(_.linkId == fh.linkId)
      buildFloatingRoadAddressLink(toHistoryRoadLink(fh), actualFloatings)
    })
  }

  def getRoadAddressLinks(boundingBoxResult: BoundingBoxResult,
                          boundingRectangle: BoundingRectangle, roadNumberLimits: Seq[(Int, Int)],
                          everything: Boolean = false, publicRoads: Boolean = false): Seq[RoadAddressLink] = {
    def complementaryLinkFilter(roadAddressLink: RoadAddressLink) = {
      everything || publicRoads || roadNumberLimits.exists {
        case (start, stop) => roadAddressLink.roadNumber >= start && roadAddressLink.roadNumber <= stop
      }
    }

    // For the purpose of the use of this conversion we do not need a accurate start date and end date since it comes from the Road address on the builder
    def toHistoryRoadLink(roadLinkLike: RoadLinkLike): VVHHistoryRoadLink = {
      val featureClassCode = roadLinkLike.attributes.getOrElse("MTKCLASS", BigInt(0)).asInstanceOf[BigInt].intValue()
      VVHHistoryRoadLink(roadLinkLike.linkId, roadLinkLike.municipalityCode, roadLinkLike.geometry, roadLinkLike.administrativeClass, roadLinkLike.trafficDirection,  VVHClient.featureClassCodeToFeatureClass.getOrElse(featureClassCode, AllOthers),
        roadLinkLike.vvhTimeStamp, roadLinkLike.vvhTimeStamp, roadLinkLike.attributes, roadLinkLike.constructionType, roadLinkLike.linkSource, roadLinkLike.length)
    }

    //TODO use complementedIds instead of only roadLinkIds below. There is no complementary ids for changeInfo dealing (for now)
    val combinedFuture =
      for {
        changedRoadLinksF <- boundingBoxResult.changeInfoF
        roadLinkFuture <- boundingBoxResult.roadLinkF
        complementaryFuture <- boundingBoxResult.complementaryF
        fetchRoadAddressesByBoundingBoxF <- boundingBoxResult.roadAddressResultF
        suravageLinksF <- boundingBoxResult.suravageF
      } yield (changedRoadLinksF, (roadLinkFuture, complementaryFuture), fetchRoadAddressesByBoundingBoxF, suravageLinksF)

    val (changedRoadLinks, (roadLinks, complementaryLinks), roadAddressResults, suravageLinks) =
      time(logger, "Fetch VVH road links and address data") {
        Await.result(combinedFuture, Duration.Inf)
      }

    val (missingFloating, addresses, floating) = (roadAddressResults.historyFloatingLinkAddresses, roadAddressResults.current, roadAddressResults.floating)
    // We should not have any road address history for links that do not have current address (they should be terminated)
    val complementaryLinkIds = complementaryLinks.map(_.linkId).toSet
    val normalRoadLinkIds = roadLinks.map(_.linkId).toSet
    val suravageLinkIds = suravageLinks.map(_.linkId).toSet
    val allRoadLinks = roadLinks ++ complementaryLinks
    val linkIds = complementaryLinkIds ++ normalRoadLinkIds ++ suravageLinkIds

    val allRoadAddressesAfterChangeTable = applyChanges(allRoadLinks, if (!frozenTimeVVHAPIServiceEnabled) changedRoadLinks else Seq(), addresses)
    val missedRL = time(logger, "Find missing road addresses") {
      withDynTransaction {
        if (everything || !frozenTimeVVHAPIServiceEnabled) {
          RoadAddressDAO.getMissingRoadAddresses(linkIds -- floating.map(_.linkId).toSet -- allRoadAddressesAfterChangeTable.flatMap(_.allSegments).map(_.linkId).toSet)
        } else {
          List[MissingRoadAddress]()
        }
      }.groupBy(_.linkId)
    }

    val roadAddressLinkMap = createRoadAddressLinkMap(allRoadLinks, suravageLinks, buildFloatingAddresses(allRoadLinks, suravageLinks, floating),
      allRoadAddressesAfterChangeTable.flatMap(_.currentSegments), missedRL)

    val inUseSuravageLinks = suravageLinks.filter(sl => roadAddressLinkMap.keySet.contains(sl.linkId))

    val (filledTopology, changeSet) = RoadAddressFiller.fillTopology(allRoadLinks ++ inUseSuravageLinks, roadAddressLinkMap)

    publishChangeSet(changeSet)
    val returningTopology = filledTopology.filter(link => !complementaryLinkIds.contains(link.linkId) ||
      complementaryLinkFilter(link))

    returningTopology ++ missingFloating.filterNot(link => returningTopology.map(_.linkId).contains(link.linkId)).map(floating => floating.copy(roadLinkType = RoadLinkType.FloatingRoadLinkType))

  }

  private def publishChangeSet(changeSet: AddressChangeSet): Unit = {
    time(logger, "Publish change set") {
      //Temporary filter for missing road addresses QA
      if (!frozenTimeVVHAPIServiceEnabled) {
        eventbus.publish("roadAddress:persistMissingRoadAddress", changeSet.missingRoadAddresses)
      }
      eventbus.publish("roadAddress:persistAdjustments", changeSet.adjustedMValues)
      eventbus.publish("roadAddress:floatRoadAddress", changeSet.toFloatingAddressIds)
    }
  }

  private def createRoadAddressLinkMap(roadLinks: Seq[RoadLink], suravageLinks: Seq[VVHRoadlink], toFloating: Seq[RoadAddressLink],
                                       addresses: Seq[RoadAddress],
                                       missedRL: Map[Long, List[MissingRoadAddress]]): Map[Long, Seq[RoadAddressLink]] = {
    time(logger, "Create road address link map") {
      val (suravageRA, _) = addresses.partition(ad => ad.linkGeomSource == LinkGeomSource.SuravageLinkInterface)
      logger.info(s"Creation of RoadAddressLinks started.")
      val mappedRegular = roadLinks.map { rl =>
        val floaters = toFloating.filter(_.linkId == rl.linkId)
        val ra = addresses.filter(_.linkId == rl.linkId)
        val missed = missedRL.getOrElse(rl.linkId, Seq())
        rl.linkId -> buildRoadAddressLink(rl, ra, missed, floaters)
      }.toMap
      val filteredSuravage = suravageLinks.filter(sl => suravageRA.contains(sl.linkId))
      val mappedSuravage = filteredSuravage.map(sur => {
        val ra = suravageRA.filter(_.linkId == sur.linkId)
        sur.linkId -> buildSuravageRoadAddressLink(sur, ra)
      }).toMap
      logger.info(s"Finished creation of roadAddressLinks, final result: ")
      logger.info(s"Regular Roads: ${mappedRegular.size} || Suravage Roads: ${mappedSuravage.size}")
      mappedRegular ++ mappedSuravage
    }
  }

  def getRoadAddressLinksByLinkId(boundingRectangle: BoundingRectangle, roadNumberLimits: Seq[(Int, Int)]): Seq[RoadAddressLink] = {

    val fetchAddrStartTime = System.currentTimeMillis()
    val fetchRoadAddressesByBoundingBoxF = Future(fetchRoadAddressesByBoundingBox(boundingRectangle, fetchOnlyFloating = false, onlyNormalRoads = true, roadNumberLimits))
    val fetchMissingRoadAddressesByBoundingBoxF = Future(fetchMissingRoadAddressesByBoundingBox(boundingRectangle))

    val fetchResult = Await.result(fetchRoadAddressesByBoundingBoxF, Duration.Inf)
    val (historyLinkAddresses, addresses) = (fetchResult.historyFloatingLinkAddresses, fetchResult.current)

    val missingViiteRoadAddress = if (!frozenTimeVVHAPIServiceEnabled) Await.result(fetchMissingRoadAddressesByBoundingBoxF, Duration.Inf) else Map[Long, Seq[MissingRoadAddress]]()
    logger.info("Fetch addresses completed in %d ms".format(System.currentTimeMillis() - fetchAddrStartTime))

    val addressLinkIds = addresses.map(_.linkId).toSet ++ missingViiteRoadAddress.keySet
    val fetchVVHStartTime = System.currentTimeMillis()
    val changedRoadLinksF = if (!frozenTimeVVHAPIServiceEnabled) roadLinkService.getChangeInfoFromVVHF(addressLinkIds) else Future(Seq())

    val roadLinks = roadLinkService.getRoadLinksByLinkIdsFromVVH(addressLinkIds, newTransaction, frozenTimeVVHAPIServiceEnabled)

    val fetchVVHEndTime = System.currentTimeMillis()
    logger.info("Fetch VVH road links completed in %d ms".format(fetchVVHEndTime - fetchVVHStartTime))

    val linkIds = roadLinks.map(_.linkId).toSet

    val changedRoadLinks = Await.result(changedRoadLinksF, Duration.Inf)
    logger.info("Fetch change info completed in %d ms".format(System.currentTimeMillis() - fetchVVHEndTime))

    val complementedWithChangeAddresses = time(logger, "Complemented with change addresses") {
      applyChanges(roadLinks, if (!frozenTimeVVHAPIServiceEnabled) changedRoadLinks else Seq(), addresses)
    }

    val (changedFloating, missingFloating) = historyLinkAddresses.partition(ral => linkIds.contains(ral.linkId))

    val buildStartTime = System.currentTimeMillis()
    val viiteRoadLinks = roadLinks.map { rl =>
      val floaters = changedFloating.filter(_.linkId == rl.linkId)
      val ra = complementedWithChangeAddresses.flatMap(_.currentSegments).filter(_.linkId == rl.linkId)
      val missed = missingViiteRoadAddress.getOrElse(rl.linkId, Seq())
      rl.linkId -> buildRoadAddressLink(rl, ra, missed, floaters)
    }.toMap
    val buildEndTime = System.currentTimeMillis()
    logger.info("Build road addresses completed in %d ms".format(buildEndTime - buildStartTime))

    val (filledTopology, changeSet) = RoadAddressFiller.fillTopology(roadLinks, viiteRoadLinks)

    publishChangeSet(changeSet)

    setBlackUnderline(filledTopology ++ missingFloating)
  }

  def getRoadAddressesWithLinearGeometry(boundingRectangle: BoundingRectangle, roadNumberLimits: Seq[(Int, Int)]): Seq[RoadAddressLink] = {

    val fetchRoadAddressesByBoundingBoxF = Future(fetchRoadAddressesByBoundingBox(boundingRectangle, fetchOnlyFloating = false, onlyNormalRoads = true, roadNumberLimits))

    val fetchResult = time(logger, "Fetch road addresses by bounding box") {
      Await.result(fetchRoadAddressesByBoundingBoxF, Duration.Inf)
    }
    val addresses = fetchResult.current

    val viiteRoadLinks = time(logger, "Build road addresses") {
      addresses.map { address =>
        address.linkId -> RoadAddressLinkBuilder.buildSimpleLink(address)
      }.toMap
    }

    setBlackUnderline(viiteRoadLinks.values.toSeq)
  }

  /**
    * Checks that  length is same after change  (used in type 1 and type 2)
    *
    * @param change change case class
    * @return true if stays with in epsilon
    */
  private def changedLengthStaySame(change: ChangeInfo): Boolean = {
    val difference = Math.abs(change.oldEndMeasure.getOrElse(0D) - change.oldStartMeasure.getOrElse(0D)) -
      Math.abs(change.newEndMeasure.getOrElse(0D) - change.newStartMeasure.getOrElse(0D))
    if (difference.abs < Epsilon) {
      return true
    } else
      logger.error("Change message for change " + change.toString + "failed due to length not being same before and after change")
    false
  }

  /**
    * Sanity checks for changes. We don't want to solely trust VVH messages, thus we do some sanity checks and drop insane ones
    *
    * @param changes Change infos
    * @return sane changetypes
    */

  def changesSanityCheck(changes: Seq[ChangeInfo]): Seq[ChangeInfo] = {
    val (combinedParts, nonCheckedChangeTypes) = changes.partition(x => x.changeType == ChangeType.CombinedModifiedPart.value
      || x.changeType == ChangeType.CombinedRemovedPart.value)
    val sanityCheckedTypeOneTwo = combinedParts.filter(x => changedLengthStaySame(x))
    sanityCheckedTypeOneTwo ++ nonCheckedChangeTypes
  }

  def filterRelevantChanges(roadAddresses: Seq[RoadAddress], allChanges: Seq[ChangeInfo]): Seq[ChangeInfo] = {
    val groupedAddresses = roadAddresses.groupBy(_.linkId)
    val timestamps = groupedAddresses.mapValues(_.map(_.adjustedTimestamp).min)
    allChanges.filter(ci => timestamps.get(ci.oldId.getOrElse(ci.newId.get)).nonEmpty && ci.vvhTimeStamp >= timestamps.getOrElse(ci.oldId.getOrElse(ci.newId.get), 0L))
  }

  def applyChanges(roadLinks: Seq[RoadLink], allChanges: Seq[ChangeInfo], roadAddresses: Seq[RoadAddress]): Seq[LinkRoadAddressHistory] = {
    time(logger, "Apply changes") {
      val addresses = roadAddresses.groupBy(ad => (ad.linkId, ad.commonHistoryId)).mapValues(v => LinkRoadAddressHistory(v.partition(_.endDate.isEmpty)))
      val changes = filterRelevantChanges(roadAddresses, allChanges)
      val changedRoadLinks = changesSanityCheck(changes)
      if (changedRoadLinks.isEmpty) {
        addresses.values.toSeq
      } else {
        withDynTransaction {
          val newRoadAddresses = RoadAddressChangeInfoMapper.resolveChangesToMap(addresses, changedRoadLinks)
          val roadLinkMap = roadLinks.map(rl => rl.linkId -> rl).toMap

          val (addressesToCreate, unchanged) = newRoadAddresses.flatMap(_._2.allSegments).toSeq.partition(_.id == NewRoadAddress)
          val savedRoadAddresses = addressesToCreate.filter(r => roadLinkMap.contains(r.linkId)).map(r =>
            r.copy(geometry = GeometryUtils.truncateGeometry3D(roadLinkMap(r.linkId).geometry,
              r.startMValue, r.endMValue), linkGeomSource = roadLinkMap(r.linkId).linkSource))
          val removedIds = addresses.values.flatMap(_.allSegments).map(_.id).toSet -- (savedRoadAddresses ++ unchanged).map(x => x.id)
          removedIds.grouped(500).foreach(s => {
            RoadAddressDAO.expireById(s)
            logger.debug("Expired: " + s.mkString(","))
          })
          unchanged.filter(ra => ra.floating).foreach {
            ra => RoadAddressDAO.changeRoadAddressFloating(1, ra.id, None)
          }
          val ids = RoadAddressDAO.create(savedRoadAddresses).toSet ++ unchanged.map(_.id).toSet
          val changedRoadParts = addressesToCreate.map(a => (a.roadNumber, a.roadPartNumber)).toSet

          val adjustedRoadParts = changedRoadParts.filter { x => recalculateRoadAddresses(x._1, x._2) }
          // re-fetch after recalculation
          val adjustedAddresses = adjustedRoadParts.flatMap { case (road, part) => RoadAddressDAO.fetchByRoadPart(road, part) }

          val changedRoadAddresses = adjustedAddresses ++ RoadAddressDAO.fetchByIdMassQuery(ids -- adjustedAddresses.map(_.id), includeFloating = true)
          changedRoadAddresses.groupBy(cra => (cra.linkId, cra.commonHistoryId)).map(s => LinkRoadAddressHistory(s._2.toSeq.partition(_.endDate.isEmpty))).toSeq
        }
      }
    }
  }

  /**
    * Returns missing road addresses for links that did not already exist in database
    *
    * @param roadNumberLimits
    * @param municipality
    * @return
    */
  def getMissingRoadAddresses(roadNumberLimits: Seq[(Int, Int)], municipality: Int): Seq[MissingRoadAddress] = {
    val (addresses, missedRL, roadLinks) =
      withDynTransaction {
        val roadLinks = roadLinkService.getCurrentAndComplementaryRoadLinksFromVVH(municipality, roadNumberLimits, frozenTimeVVHAPIServiceEnabled)
        val linkIds = roadLinks.map(_.linkId).toSet
        val addr = RoadAddressDAO.fetchByLinkId(linkIds).groupBy(_.linkId)
        val missingLinkIds = linkIds -- addr.keySet
        (addr, RoadAddressDAO.getMissingRoadAddresses(missingLinkIds).groupBy(_.linkId), roadLinks)
      }
    val viiteRoadLinks = roadLinks.map { rl =>
      val ra = addresses.getOrElse(rl.linkId, Seq())
      val missed = missedRL.getOrElse(rl.linkId, Seq())
      rl.linkId -> buildRoadAddressLink(rl, ra, missed)
    }.toMap

    val (_, changeSet) = RoadAddressFiller.fillTopology(roadLinks, viiteRoadLinks)

    changeSet.missingRoadAddresses
  }

  def buildSuravageRoadAddressLink(rl: VVHRoadlink, roadAddrSeq: Seq[RoadAddress]): Seq[RoadAddressLink] = {
    val fusedRoadAddresses = RoadAddressLinkBuilder.fuseRoadAddressWithTransaction(roadAddrSeq)
    val kept = fusedRoadAddresses.map(_.id).toSet
    val removed = roadAddrSeq.map(_.id).toSet.diff(kept)
    val roadAddressesToRegister = fusedRoadAddresses.filter(_.id == fi.liikennevirasto.viite.NewRoadAddress)
    if (roadAddressesToRegister.nonEmpty)
      eventbus.publish("roadAddress:mergeRoadAddress", RoadAddressMerge(removed, roadAddressesToRegister))
    fusedRoadAddresses.map(ra => {
      RoadAddressLinkBuilder.build(rl, ra)
    })
  }

  def buildRoadAddressLink(rl: RoadLink, roadAddrSeq: Seq[RoadAddress], missing: Seq[MissingRoadAddress], floaters: Seq[RoadAddressLink] = Seq.empty): Seq[RoadAddressLink] = {
    val fusedRoadAddresses = RoadAddressLinkBuilder.fuseRoadAddressWithTransaction(roadAddrSeq)
    val kept = fusedRoadAddresses.map(_.id).toSet
    val removed = roadAddrSeq.map(_.id).toSet.diff(kept)
    val roadAddressesToRegister = fusedRoadAddresses.filter(_.id == fi.liikennevirasto.viite.NewRoadAddress)
    if (roadAddressesToRegister.nonEmpty)
      eventbus.publish("roadAddress:mergeRoadAddress", RoadAddressMerge(removed, roadAddressesToRegister))
    if (floaters.nonEmpty) {
      floaters.map(_.copy(anomaly = Anomaly.GeometryChanged, newGeometry = Option(rl.geometry)))
    } else {
      fusedRoadAddresses.map(ra => {
        RoadAddressLinkBuilder.build(rl, ra)
      }) ++
        missing.map(m => RoadAddressLinkBuilder.build(rl, m)).filter(_.length > 0.0)
    }
  }

  private def combineGeom(roadAddresses: Seq[RoadAddress]) = {
    if (roadAddresses.length == 1) {
      roadAddresses.head
    } else {
      val max = roadAddresses.maxBy(ra => ra.endMValue)
      val min = roadAddresses.minBy(ra => ra.startMValue)
      min.copy(startAddrMValue = Math.min(min.startAddrMValue, max.startAddrMValue),
        endAddrMValue = Math.max(min.endAddrMValue, max.endAddrMValue), startMValue = min.startMValue,
        endMValue = max.endMValue, geometry = Seq(min.geometry.head, max.geometry.last))
    }
  }

  def getRoadParts(boundingRectangle: BoundingRectangle, roadNumberLimits: Seq[(Int, Int)]): Seq[RoadAddressLink] = {
    val addresses = withDynTransaction {
      RoadAddressDAO.fetchPartsByRoadNumbers(boundingRectangle, roadNumberLimits).groupBy(_.linkId)
    }

    val vvhRoadLinks = roadLinkService.getRoadLinksByLinkIdsFromVVH(addresses.keySet, newTransaction, frozenTimeVVHAPIServiceEnabled)
    val combined = addresses.mapValues(combineGeom)
    val roadLinks = vvhRoadLinks.map(rl => rl -> combined(rl.linkId)).toMap

    roadLinks.flatMap { case (rl, ra) =>
      buildRoadAddressLink(rl, Seq(ra), Seq())
    }.toSeq
  }

  // Boolean  (frozenTimeVVHAPIServiceEnabled)  should be added if we start using this method  for disabling changeAPI and switch to frozentime VVH API)
  def getCoarseRoadParts(boundingRectangle: BoundingRectangle, roadNumberLimits: Seq[(Int, Int)]): Seq[RoadAddressLink] = {
    val addresses = withDynTransaction {
      RoadAddressDAO.fetchPartsByRoadNumbers(boundingRectangle, roadNumberLimits, coarse = true).groupBy(_.linkId)
    }
    val roadLinks = roadLinkService.getRoadPartsFromVVH(addresses.keySet, Set())
    val groupedLinks = roadLinks.flatMap { rl =>
      val ra = addresses.getOrElse(rl.linkId, List())
      buildRoadAddressLink(rl, ra, Seq())
    }.groupBy(_.roadNumber)

    val retval = groupedLinks.mapValues {
      case viiteRoadLinks =>
        val sorted = viiteRoadLinks.sortWith({
          case (ral1, ral2) =>
            if (ral1.roadNumber != ral2.roadNumber)
              ral1.roadNumber < ral2.roadNumber
            else if (ral1.roadPartNumber != ral2.roadPartNumber)
              ral1.roadPartNumber < ral2.roadPartNumber
            else
              ral1.startAddressM < ral2.startAddressM
        })
        sorted.zip(sorted.tail).map {
          case (st1, st2) =>
            st1.copy(geometry = Seq(st1.geometry.head, st2.geometry.head))
        }
    }
    retval.flatMap(x => x._2).toSeq
  }

  /**
    * returns road addresses with link-id currently does not include terminated links which it cannot build roadaddress with out geometry
    *
    * @param id link-id, boolean if we want
    * @return roadaddress[]
    */
  def getRoadAddressLink(id: Long): List[RoadAddressLink] = {

    val (addresses, missedRL) = withDynTransaction {
      (RoadAddressDAO.fetchByLinkId(Set(id), includeFloating = true, includeHistory = true, includeTerminated = false), // cannot builld terminated link because missing geometry
        RoadAddressDAO.getMissingRoadAddresses(Set(id)))
    }
    val anomaly = missedRL.headOption.map(_.anomaly).getOrElse(Anomaly.None)
    val (roadLinks, vvhHistoryLinks) = roadLinkService.getCurrentAndHistoryRoadLinksFromVVH(Set(id), frozenTimeVVHAPIServiceEnabled)
    (anomaly, addresses.size, roadLinks.size) match {
      case (_, 0, 0) => List() // No road link currently exists and no addresses on this link id => ignore
      case (Anomaly.GeometryChanged, _, _) => addresses.flatMap(a => vvhHistoryLinks.map(rl => RoadAddressLinkBuilder.build(rl, a)))
      case (_, _, 0) => addresses.flatMap(a => vvhHistoryLinks.map(rl => RoadAddressLinkBuilder.build(rl, a)))
      case (Anomaly.NoAddressGiven, 0, _) => missedRL.flatMap(a => roadLinks.map(rl => RoadAddressLinkBuilder.build(rl, a)))
      case (_, _, _) => addresses.flatMap(a => roadLinks.map(rl => RoadAddressLinkBuilder.build(rl, a)))
    }
  }

  /**
    * Returns all floating road addresses that are represented on ROAD_ADDRESS table and are valid (excluding history)
    *
    * @param includesHistory - default value = false to exclude history values
    * @return Seq[RoadAddress]
    */
  def getFloatingAdresses(includesHistory: Boolean = false): List[RoadAddress] = {
    withDynSession {
      RoadAddressDAO.fetchAllFloatingRoadAddresses(includesHistory)
    }
  }

  /**
    * Returns all road address errors that are represented on ROAD_ADDRESS table and are valid (excluding history)
    *
    * @param includesHistory - default value = false to exclude history values
    * @return Seq[RoadAddress]
    */
  def getRoadAddressErrors(includesHistory: Boolean = false): List[AddressConsistencyValidator.AddressErrorDetails] = {
    withDynSession {
      RoadAddressDAO.fetchAllRoadAddressErrors(includesHistory)
    }
  }

  def getTargetRoadLink(linkId: Long): RoadAddressLink = {
    val (roadLinks, _) = roadLinkService.getCurrentAndHistoryRoadLinksFromVVH(Set(linkId), frozenTimeVVHAPIServiceEnabled)
    if (roadLinks.isEmpty) {
      throw new InvalidAddressDataException(s"Can't find road link for target link id $linkId")
    } else {
      RoadAddressLinkBuilder.build(roadLinks.head, MissingRoadAddress(linkId = linkId, None, None, RoadType.Unknown, None, None, None, None, anomaly = Anomaly.NoAddressGiven, Seq.empty[Point]))
    }
  }

  def getUniqueRoadAddressLink(id: Long): List[RoadAddressLink] = getRoadAddressLink(id)

  def roadClass(roadNumber: Long): Int = {
    val C1 = new Contains(1 to 39)
    val C2 = new Contains(40 to 99)
    val C3 = new Contains(100 to 999)
    val C4 = new Contains(1000 to 9999)
    val C5 = new Contains(10000 to 19999)
    val C6 = new Contains(40000 to 49999)
    val C7 = new Contains(20001 to 39999)
    val C8a = new Contains(70001 to 89999)
    val C8b = new Contains(90001 to 99999)
    val C9 = new Contains(60001 to 61999)
    val C10 = new Contains(62001 to 62999)
    val C11 = new Contains(9900 to 9999)
    try {
      val roadNum: Int = roadNumber.toInt
      roadNum match {
        case C1() => HighwayClass
        case C2() => MainRoadClass
        case C3() => RegionalClass
        case C4() => ConnectingClass
        case C5() => MinorConnectingClass
        case C6() => StreetClass
        case C7() => RampsAndRoundAboutsClass
        case C8a() => PedestrianAndBicyclesClass
        case C8b() => PedestrianAndBicyclesClass
        case C9() => WinterRoadsClass
        case C10() => PathsClass
        case C11() => ConstructionSiteTemporaryClass
        case _ => NoClass
      }
    } catch {
      case ex: NumberFormatException => NoClass
    }
  }

  def createMissingRoadAddress(missingRoadLinks: Seq[MissingRoadAddress]): Unit = {
    withDynTransaction {
      missingRoadLinks.foreach(createSingleMissingRoadAddress)
    }
  }

  def createSingleMissingRoadAddress(missingAddress: MissingRoadAddress): Unit = {
    RoadAddressDAO.createMissingRoadAddress(missingAddress)
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
    val unMergedCount = RoadAddressDAO.queryById(data.merged).size
    if (unMergedCount != data.merged.size)
      throw new InvalidAddressDataException("Data modified while updating, rolling back transaction: some source rows no longer valid")
    val mergedCount = expireRoadAddresses(data.merged)
    if (mergedCount == data.merged.size)
      createMergedSegments(data.created)
    else
      throw new InvalidAddressDataException("Data modified while updating, rolling back transaction: some source rows not updated")
  }

  def mergeRoadAddressHistoryInTX(data: RoadAddressMerge): Unit = {
    val unMergedCount = RoadAddressDAO.queryById(data.merged).size
    if (unMergedCount != data.merged.size)
      throw new InvalidAddressDataException("Data modified while updating, rolling back transaction: some source rows no longer valid")
    val mergedCount = expireRoadAddresses(data.merged)
    if (mergedCount == data.merged.size)
      createMergedSegments(data.created)
    else
      throw new InvalidAddressDataException("Data modified while updating, rolling back transaction: some source rows not updated")
  }

  def createMergedSegments(mergedRoadAddress: Seq[RoadAddress]): Unit = {
    mergedRoadAddress.grouped(500).foreach(group => RoadAddressDAO.create(group, Some("Automatic_merged")))
  }

  def expireRoadAddresses(expiredIds: Set[Long]): Int = {
    expiredIds.grouped(500).map(group => RoadAddressDAO.expireById(group)).sum
  }

  /**
    * Checks that if the geometry is found and updates the geometry to match or sets it floating if not found
    *
    * @param ids
    */
  def checkRoadAddressFloating(ids: Set[Long]): Unit = {
    withDynTransaction {
      checkRoadAddressFloatingWithoutTX(ids)
    }
  }

  /**
    * For easier unit testing and use
    *
    * @param ids
    */
  def checkRoadAddressFloatingWithoutTX(ids: Set[Long], float: Boolean = false): Unit = {
    def nonEmptyTargetLinkGeometry(roadLinkOpt: Option[RoadLinkLike], geometryOpt: Option[Seq[Point]]) = {
      !(roadLinkOpt.isEmpty || geometryOpt.isEmpty || GeometryUtils.geometryLength(geometryOpt.get) == 0.0)
    }

    val addresses = RoadAddressDAO.queryById(ids)
    val linkIdMap = addresses.groupBy(_.linkId).mapValues(_.map(_.id))
    val roadLinks = roadLinkService.getCurrentAndComplementaryVVHRoadLinks(linkIdMap.keySet, frozenTimeVVHAPIServiceEnabled)
    addresses.foreach { address =>
      val roadLink = roadLinks.find(_.linkId == address.linkId)
      val addressGeometry = roadLink.map(rl =>
        GeometryUtils.truncateGeometry3D(rl.geometry, address.startMValue, address.endMValue))
      if (float && nonEmptyTargetLinkGeometry(roadLink, addressGeometry)) {
        println("Floating and update geometry id %d (link id %d)".format(address.id, address.linkId))
        RoadAddressDAO.changeRoadAddressFloating(float = true, address.id, addressGeometry)
        val missing = MissingRoadAddress(address.linkId, Some(address.startAddrMValue), Some(address.endAddrMValue), RoadAddressLinkBuilder.getRoadType(roadLink.get.administrativeClass, UnknownLinkType), None, None, Some(address.startMValue), Some(address.endMValue), Anomaly.GeometryChanged, Seq.empty[Point])
        RoadAddressDAO.createMissingRoadAddress(missing.linkId, missing.startAddrMValue.getOrElse(0), missing.endAddrMValue.getOrElse(0), missing.anomaly.value, missing.startMValue.get, missing.endMValue.get)
      } else if (!nonEmptyTargetLinkGeometry(roadLink, addressGeometry)) {
        println("Floating id %d (link id %d)".format(address.id, address.linkId))
        RoadAddressDAO.changeRoadAddressFloating(float = true, address.id, None)
      } else {
        if (!GeometryUtils.areAdjacent(addressGeometry.get, address.geometry)) {
          println("Updating geometry for id %d (link id %d)".format(address.id, address.linkId))
          RoadAddressDAO.changeRoadAddressFloating(float = false, address.id, addressGeometry)
        }
      }
    }
  }

  /*
    Kalpa-API methods
  */
  def getRoadAddressesLinkByMunicipality(municipality: Int, roadLinkDataTempAPI: Boolean = false): Seq[RoadAddressLink] = {

    val (roadLinksWithComplementary, _) =
    // TODO This if statement will be removed after the frozen links are no longer needed and jut use the cache
      if (frozenTimeVVHAPIServiceEnabled) {
        val roadLinks = {
          val tempRoadLinks = roadLinkService.getRoadLinksFromVVHByMunicipality(municipality, frozenTimeVVHAPIServiceEnabled)
          if (tempRoadLinks == null)
            Seq.empty[RoadLink]
          else tempRoadLinks
        }
        val complimentaryLinks = {
          val tempComplimentary = roadLinkService.getComplementaryRoadLinksFromVVH(municipality)
          if (tempComplimentary == null)
            Seq.empty[RoadLink]
          else tempComplimentary
        }
        (roadLinks ++ complimentaryLinks, Seq())
      } else {
        //TODO Add on the cache the all the complementary links, and then filter on the methods used by OTH
        val (roadlinks, changes) = roadLinkService.getRoadLinksWithComplementaryAndChangesFromVVH(municipality)
        (roadlinks.filterNot(r => r.linkSource == LinkGeomSource.ComplimentaryLinkInterface) ++ roadLinkService.getComplementaryRoadLinksFromVVH(municipality), changes)
      }
    val suravageLinks = roadLinkService.getSuravageRoadLinks(municipality)
    val allRoadLinks = roadLinksWithComplementary ++ suravageLinks

    val addresses =
      withDynTransaction {
        RoadAddressDAO.fetchByLinkIdToApi(allRoadLinks.map(_.linkId).toSet, RoadNetworkDAO.getLatestRoadNetworkVersion.nonEmpty).groupBy(_.linkId)
      }
    // In order to avoid sending roadAddressLinks that have no road address
    // we remove the road links that have no known address
    val knownRoadLinks = allRoadLinks.filter(rl => {
      addresses.contains(rl.linkId)
    })

    val viiteRoadLinks = knownRoadLinks.map { rl =>
      val ra = addresses.getOrElse(rl.linkId, Seq())
      rl.linkId -> buildRoadAddressLink(rl, ra, Seq())
    }.toMap

    val (filledTopology, changeSet) = RoadAddressFiller.fillTopology(allRoadLinks, viiteRoadLinks)
    publishChangeSet(changeSet)
    filledTopology
  }

  def saveAdjustments(addresses: Seq[LRMValueAdjustment]): Unit = {
    withDynTransaction {
      addresses.foreach(RoadAddressDAO.updateLRM)
    }
  }

  private def getAdjacentAddresses(chainLinks: Set[Long], linkId: Long, roadNumber: Long, roadPartNumber: Long, track: Track) = {
    withDynSession {
      val ra = RoadAddressDAO.fetchByLinkId(chainLinks, includeFloating = true, includeHistory = false).sortBy(_.startAddrMValue)
      assert(ra.forall(r => r.roadNumber == roadNumber && r.roadPartNumber == roadPartNumber && r.track == track),
        s"Mixed floating addresses selected ($roadNumber/$roadPartNumber/$track): " + ra.map(r =>
          s"${r.linkId} = ${r.roadNumber}/${r.roadPartNumber}/${r.track.value}").mkString(", "))
      val startValues = ra.map(_.startAddrMValue)
      val endValues = ra.map(_.endAddrMValue)
      val orphanStarts = startValues.filterNot(st => endValues.contains(st))
      val orphanEnds = endValues.filterNot(st => startValues.contains(st))
      (orphanStarts.flatMap(st => RoadAddressDAO.fetchByAddressEnd(roadNumber, roadPartNumber, track, st))
        ++ orphanEnds.flatMap(end => RoadAddressDAO.fetchByAddressStart(roadNumber, roadPartNumber, track, end)))
        .distinct
    }
  }

  def getFloatingAdjacent(chainLinks: Set[Long], linkId: Long, roadNumber: Long, roadPartNumber: Long, trackCode: Int): Seq[RoadAddressLink] = {
    val adjacentAddresses = getAdjacentAddresses(chainLinks, linkId, roadNumber, roadPartNumber, Track.apply(trackCode))
    val adjacentLinkIds = adjacentAddresses.map(_.linkId).toSet
    val roadLinks = roadLinkService.getCurrentAndHistoryRoadLinksFromVVH(adjacentLinkIds, frozenTimeVVHAPIServiceEnabled)
    val adjacentAddressLinks = roadLinks._1.map(rl => rl.linkId -> rl).toMap
    val historyLinks = roadLinks._2.groupBy(rl => rl.linkId)

    val anomaly2List = withDynSession {
      RoadAddressDAO.getMissingRoadAddresses(adjacentLinkIds).filter(_.anomaly == Anomaly.GeometryChanged)
    }

    val floatingAdjacents = adjacentAddresses.filter(_.floating).map(ra =>
      if (anomaly2List.exists(_.linkId == ra.linkId)) {
        val rl = adjacentAddressLinks(ra.linkId)
        RoadAddressLinkBuilder.build(rl, ra, floating = true, Some(rl.geometry))
      } else if (roadLinks._2.exists(_.linkId == ra.linkId)) {
        RoadAddressLinkBuilder.build(historyLinks(ra.linkId).head, ra)
      } else {
        RoadAddressLinkBuilder.build(adjacentAddressLinks(ra.linkId), ra)
      }
    )
    floatingAdjacents
  }

  def getAdjacent(chainLinks: Set[Long], linkId: Long): Seq[RoadAddressLink] = {
    val chainRoadLinks = roadLinkService.getRoadLinksByLinkIdsFromVVH(chainLinks, newTransaction, frozenTimeVVHAPIServiceEnabled)
    val pointCloud = chainRoadLinks.map(_.geometry).map(GeometryUtils.geometryEndpoints).flatMap(x => Seq(x._1, x._2))
    val boundingPoints = GeometryUtils.boundingRectangleCorners(pointCloud)
    val boundingRectangle = BoundingRectangle(boundingPoints._1 + Vector3d(-.1, .1, 0.0), boundingPoints._2 + Vector3d(.1, -.1, 0.0))
    val connectedLinks = roadLinkService.getRoadLinksAndChangesFromVVHWithFrozenAPI(boundingRectangle, frozenTimeVVHAPIServiceEnabled)._1
      .filterNot(rl => chainLinks.contains(rl.linkId))
      .filter { rl =>
        val endPoints = GeometryUtils.geometryEndpoints(rl.geometry)
        pointCloud.exists(p => GeometryUtils.areAdjacent(p, endPoints._1) || GeometryUtils.areAdjacent(p, endPoints._2))
      }.map(rl => rl.linkId -> rl).toMap
    val missingLinks = withDynSession {
      RoadAddressDAO.getMissingRoadAddresses(connectedLinks.keySet)
    }
    missingLinks.map(ml => RoadAddressLinkBuilder.build(connectedLinks(ml.linkId), ml))
  }

  def getRoadAddressLinksAfterCalculation(sources: Seq[String], targets: Seq[String], user: User): Seq[RoadAddressLink] = {
    val transferredRoadAddresses = getRoadAddressesAfterCalculation(sources, targets, user)
    val target = roadLinkService.getRoadLinksByLinkIdsFromVVH(targets.map(rd => rd.toLong).toSet, newTransaction, frozenTimeVVHAPIServiceEnabled)
    transferredRoadAddresses.filter(_.endDate.isEmpty).map(ra => RoadAddressLinkBuilder.build(target.find(_.linkId == ra.linkId).get, ra))
  }

  def getRoadAddressesAfterCalculation(sources: Seq[String], targets: Seq[String], user: User): Seq[RoadAddress] = {
    def adjustGeometry(ra: RoadAddress, link: RoadAddressLinkLike): RoadAddress = {
      val geom = GeometryUtils.truncateGeometry3D(link.geometry, ra.startMValue, ra.endMValue)
      ra.copy(geometry = geom, linkGeomSource = link.roadLinkSource)
    }

    val sourceRoadAddressLinks = sources.flatMap(rd => {
      getRoadAddressLink(rd.toLong)
    })
    val targetIds = targets.map(rd => rd.toLong).toSet
    val targetRoadAddressLinks = targetIds.toSeq.map(getTargetRoadLink)
    val targetLinkMap: Map[Long, RoadAddressLinkLike] = targetRoadAddressLinks.map(l => l.linkId -> l).toMap
    transferRoadAddress(sourceRoadAddressLinks, targetRoadAddressLinks, user).map(ra => adjustGeometry(ra, targetLinkMap(ra.linkId)))
  }

  def transferFloatingToGap(sourceIds: Set[Long], targetIds: Set[Long], roadAddresses: Seq[RoadAddress], username: String): Unit = {
    val hasFloatings = withDynTransaction {
      val currentRoadAddresses = RoadAddressDAO.fetchByLinkId(sourceIds, includeFloating = true, includeHistory = true,
        includeTerminated = false)
      RoadAddressDAO.expireById(currentRoadAddresses.map(_.id).toSet)
      RoadAddressDAO.create(roadAddresses, Some(username))
      recalculateRoadAddresses(roadAddresses.head.roadNumber.toInt, roadAddresses.head.roadPartNumber.toInt)
      RoadAddressDAO.fetchAllFloatingRoadAddresses().nonEmpty
    }
    if (!hasFloatings)
      eventbus.publish("roadAddress:RoadNetworkChecker", RoadCheckOptions(Seq()))
  }

  def transferRoadAddress(sources: Seq[RoadAddressLink], targets: Seq[RoadAddressLink], user: User): Seq[RoadAddress] = {
    def latestSegments(segments: Seq[RoadAddressLink]): Seq[RoadAddressLink] = {
      if (segments.exists(_.endDate == ""))
        segments.filter(_.endDate == "")
      else {
        val max = RoadAddressLinkBuilder.formatter.print(segments.map(s =>
          RoadAddressLinkBuilder.formatter.parseDateTime(s.endDate)).maxBy(_.toDate))
        segments.filter(_.endDate == max)
      }
    }

    val mapping = DefloatMapper.createAddressMap(latestSegments(sources), targets)
    if (mapping.exists(DefloatMapper.invalidMapping)) {
      throw new InvalidAddressDataException("Mapping failed to map following items: " +
        mapping.filter(DefloatMapper.invalidMapping).map(
          r => s"${r.sourceLinkId}: ${r.sourceStartM}-${r.sourceEndM} -> ${r.targetLinkId}: ${r.targetStartM}-${r.targetEndM}").mkString(", ")
      )
    }
    val sourceRoadAddresses = withDynSession {
      RoadAddressDAO.fetchByLinkId(sources.map(_.linkId).toSet, includeFloating = true,
        includeHistory = true, includeTerminated = false)
    }

    val (currentSourceRoadAddresses, historySourceRoadAddresses) = sourceRoadAddresses.partition(ra => ra.endDate.isEmpty)

    DefloatMapper.preTransferChecks(currentSourceRoadAddresses)
    val currentTargetRoadAddresses = RoadAddressLinkBuilder.fuseRoadAddressWithTransaction(currentSourceRoadAddresses.flatMap(DefloatMapper.mapRoadAddresses(mapping)))
    DefloatMapper.postTransferChecks(currentTargetRoadAddresses.filter(_.endDate.isEmpty), currentSourceRoadAddresses)

    val historyTargetRoadAddresses = historySourceRoadAddresses.groupBy(_.endDate).flatMap(group => {
      DefloatMapper.preTransferChecks(group._2)
      val targetHistory = RoadAddressLinkBuilder.fuseRoadAddressWithTransaction(group._2.flatMap(DefloatMapper.mapRoadAddresses(mapping)))
      targetHistory
    })

    currentTargetRoadAddresses ++ historyTargetRoadAddresses
  }

  def recalculateRoadAddresses(roadNumber: Long, roadPartNumber: Long): Boolean = {
    try {
      val roads = RoadAddressDAO.fetchByRoadPart(roadNumber, roadPartNumber, includeFloating = true,
        includeExpired = false, includeHistory = false)
      if (!roads.exists(_.floating)) {
        try {
          val adjusted = LinkRoadAddressCalculator.recalculate(roads)
          assert(adjusted.size == roads.size)
          // Must not lose any
          val (changed, unchanged) = adjusted.partition(ra =>
            roads.exists(oldra => ra.id == oldra.id && (oldra.startAddrMValue != ra.startAddrMValue || oldra.endAddrMValue != ra.endAddrMValue))
          )
          logger.info(s"Road $roadNumber, part $roadPartNumber: ${changed.size} updated, ${unchanged.size} kept unchanged")
          changed.foreach(addr => RoadAddressDAO.update(addr, None))
          changed.nonEmpty
        } catch {
          case ex: InvalidAddressDataException => logger.error(s"!!! Road $roadNumber, part $roadPartNumber contains invalid address data - part skipped !!!", ex)
        }
      } else {
        logger.info(s"Not recalculating $roadNumber / $roadPartNumber because floating segments were found")
      }
    } catch {
      case a: Exception => logger.error(a.getMessage, a)
    }
    false
  }

  def prettyPrint(changes: Seq[ChangeInfo]): Unit = {
    def setPrecision(d: Double) = {
      BigDecimal(d).setScale(3, BigDecimal.RoundingMode.HALF_UP).toDouble
    }

    def concatenate(c: ChangeInfo, s: String): String = {
      val newS =
        s"""old id: ${c.oldId.getOrElse("MISS!")} new id: ${c.newId.getOrElse("MISS!")} old length: ${setPrecision(c.oldStartMeasure.getOrElse(0.0))}-${setPrecision(c.oldEndMeasure.getOrElse(0.0))} new length: ${setPrecision(c.newStartMeasure.getOrElse(0.0))}-${setPrecision(c.newEndMeasure.getOrElse(0.0))} mml id: ${c.mmlId} vvhTimeStamp ${c.vvhTimeStamp}
     """
      s + "\n" + newS
    }

    val groupedChanges = SortedMap(changes.groupBy(_.changeType).toSeq: _*)
    groupedChanges.foreach { group =>
      println(s"""changeType: ${group._1}""" + "\n" + group._2.foldLeft("")((stream, nextChange) => concatenate(nextChange, stream)) + "\n")
    }
  }

  def getRoadNumbers(): Seq[Long] = {
    withDynSession {
      RoadAddressDAO.getRoadNumbers()
    }
  }

  def getRoadAddress(road: Long, roadPart: Long, track: Option[Int], mValue: Option[Double]): Seq[RoadAddress] = {
    withDynSession {
      RoadAddressDAO.getRoadAddressByFilter(RoadAddressDAO.withRoadAddress(road, roadPart, track, mValue))
    }
  }

  def getRoadAddressWithRoadNumber(road: Long, tracks: Seq[Int]): Seq[RoadAddress] = {
    withDynSession {
      RoadAddressDAO.getRoadAddressByFilter(RoadAddressDAO.withRoadNumber(road, tracks))
    }
  }

  def getRoadAddressWithLinkIdAndMeasure(linkId: Long, startM: Option[Long], endM: Option[Long]): Seq[RoadAddress] = {
    withDynSession {
      RoadAddressDAO.getRoadAddressByFilter(RoadAddressDAO.withLinkIdAndMeasure(linkId, startM, endM))
    }
  }

  def getRoadAddressesFiltered(roadNumber: Long, roadPartNumber: Long, startM: Option[Double], endM: Option[Double]): Seq[RoadAddress] = {
    withDynSession {
      RoadAddressDAO.getRoadAddressesFiltered(roadNumber, roadPartNumber, startM, endM)
    }
  }

  def getRoadAddressByLinkIds(linkIds: Set[Long], withFloating: Boolean): Seq[RoadAddress] = {
    withDynSession {
      RoadAddressDAO.fetchByLinkId(linkIds, withFloating, includeHistory = false, includeTerminated = false)
    }
  }

  def getChanged(sinceDate: DateTime, untilDate: DateTime): Seq[ChangedRoadAddress] = {

    val roadAddresses =
      withDynTransaction {
        RoadAddressDAO.getRoadAddressByFilter(RoadAddressDAO.withBetweenDates(sinceDate, untilDate))
      }

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
    * This will define what road_addresses should have a black outline according to the following rule:
    * Address must have road type = 3 (MunicipalityStreetRoad)
    * The length of all addresses in the same road number and road part number that posses road type = 3  must be
    * bigger than the combined length of ALL the road numbers that have the same road number and road part number divided by 2.
    *
    * @param addresses Sequence of all road addresses that were fetched
    * @return Sequence of road addresses properly tagged in order to get the
    */
  private def setBlackUnderline(addresses: Seq[RoadAddressLink]): Seq[RoadAddressLink] = {
    time(logger, "Set the black underline") {
      val (streetRoads, othersRoads) = addresses.partition(_.roadType == RoadType.MunicipalityStreetRoad)
      streetRoads.map(_.copy(blackUnderline = true)) ++ othersRoads
    }
  }
}

case class RoadAddressMerge(merged: Set[Long], created: Seq[RoadAddress])

case class ReservedRoadPart(id: Long, roadNumber: Long, roadPartNumber: Long, addressLength: Option[Long] = None,
                            discontinuity: Option[Discontinuity] = None, ely: Option[Long] = None,
                            newLength: Option[Long] = None, newDiscontinuity: Option[Discontinuity] = None,
                            newEly: Option[Long] = None, startingLinkId: Option[Long] = None, isDirty: Boolean = false) {
  def holds(baseRoadAddress: BaseRoadAddress): Boolean = {
    roadNumber == baseRoadAddress.roadNumber && roadPartNumber == baseRoadAddress.roadPartNumber
  }
}

case class RoadAddressResult(historyFloatingLinkAddresses: Seq[RoadAddressLink], current: Seq[RoadAddress],
                             floating: Seq[RoadAddress])

case class BoundingBoxResult(changeInfoF: Future[Seq[ChangeInfo]], roadAddressResultF: Future[RoadAddressResult],
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
    val values = Set(OverlappingRoadAddresses, InconsistentTopology)

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

    def apply(intValue: Int): AddressError = {
      values.find(_.value == intValue).get
    }
  }

  case class AddressErrorDetails(id: Long, linkId: Long, roadNumber: Long, roadPartNumber: Long, addressError: AddressError, ely: Long)

}
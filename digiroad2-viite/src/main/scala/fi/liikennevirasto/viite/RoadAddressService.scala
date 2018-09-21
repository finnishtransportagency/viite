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
import fi.liikennevirasto.digiroad2.util.{RoadAddressException, Track}
import fi.liikennevirasto.viite.dao.RoadAddressDAO.logger
import fi.liikennevirasto.viite.dao._
import fi.liikennevirasto.viite.model.{Anomaly, RoadAddressLink, RoadAddressLinkLike}
import fi.liikennevirasto.viite.process.RoadAddressFiller.{AddressChangeSet, LinearLocationAdjustment}
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

  val Epsilon = 1
  /* Smallest mvalue difference we can tolerate to be "equal to zero". One micrometer.
                                See https://en.wikipedia.org/wiki/Floating_point#Accuracy_problems
                             */

  val newTransaction = true

  class Contains(r: Range) {
    def unapply(i: Int): Boolean = r contains i
  }

  private def fetchRoadAddressesByBoundingBox(boundingRectangle: BoundingRectangle, fetchOnlyFloating: Boolean = false,
                                              onlyNormalRoads: Boolean = false, roadNumberLimits: Seq[(Int, Int)] = Seq()) = {
    val (floatingAddresses, nonFloatingAddresses) = withDynTransaction {
      time(logger, "Fetch floating and non-floating addresses") {
        RoadAddressDAO.fetchRoadAddressesByBoundingBox(boundingRectangle, fetchOnlyFloating, onlyNormalRoads, roadNumberLimits).partition(_.isFloating)
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

  // For the purpose of the use of this conversion we do not need a accurate start date and end date since it comes from the Road address on the builder
  def toHistoryRoadLink(roadLinkLike: RoadLinkLike): VVHHistoryRoadLink = {
    val featureClassCode = roadLinkLike.attributes.getOrElse("MTKCLASS", BigInt(0)).asInstanceOf[BigInt].intValue()
    VVHHistoryRoadLink(roadLinkLike.linkId, roadLinkLike.municipalityCode, roadLinkLike.geometry, roadLinkLike.administrativeClass, roadLinkLike.trafficDirection,  VVHClient.featureClassCodeToFeatureClass.getOrElse(featureClassCode, AllOthers),
      roadLinkLike.vvhTimeStamp, roadLinkLike.vvhTimeStamp, roadLinkLike.attributes, roadLinkLike.constructionType, roadLinkLike.linkSource, roadLinkLike.length)
  }

  def buildFloatingAddresses(allRoadLinks: Seq[RoadLink], suravageLinks: Seq[VVHRoadlink], floating: Seq[RoadAddress]): Seq[RoadAddressLink] = {

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

    val (missingFloating, addresses, existingFloating) = (roadAddressResults.historyFloatingLinkAddresses, roadAddressResults.current, roadAddressResults.floating)
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
          RoadAddressDAO.getMissingRoadAddresses(linkIds -- existingFloating.map(_.linkId).toSet -- allRoadAddressesAfterChangeTable.flatMap(_.allSegments).map(_.linkId).toSet)
        } else {
          List[MissingRoadAddress]()
        }
      }.groupBy(_.linkId)
    }

    val (floating, changedRoadAddresses) = allRoadAddressesAfterChangeTable.flatMap(_.currentSegments).partition(_.isFloating)
    val roadAddressLinkMap = createRoadAddressLinkMap(allRoadLinks, suravageLinks, buildFloatingAddresses(allRoadLinks, suravageLinks, existingFloating ++ floating),
      changedRoadAddresses, missedRL)

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
      val filteredSuravage = suravageLinks.filter(sl => suravageRA.map(_.linkId).contains(sl.linkId))
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

    val roadLinks = roadLinkService.getRoadLinksByLinkIdsFromVVH(addressLinkIds, frozenTimeVVHAPIServiceEnabled)

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
      val addresses = roadAddresses.groupBy(ad => (ad.linkId, ad.roadwayId)).mapValues(v => LinkRoadAddressHistory(v.partition(_.endDate.isEmpty)))
      val changes = filterRelevantChanges(roadAddresses, allChanges)
      val changedRoadLinks = changesSanityCheck(changes)
      if (changedRoadLinks.isEmpty) {
        addresses.values.toSeq
      } else {
        withDynTransaction {
          val newRoadAddresses = RoadAddressChangeInfoMapper.resolveChangesToMap(addresses, changedRoadLinks)
          val roadLinkMap = roadLinks.map(rl => rl.linkId -> rl).toMap

          val (addressesToCreate, addressesExceptNew) = newRoadAddresses.flatMap(_._2.allSegments).toSeq.partition(_.id == NewRoadAddress)
          val savedRoadAddresses = addressesToCreate.filter(r => roadLinkMap.contains(r.linkId)).map(r =>
            r.copy(geometry = GeometryUtils.truncateGeometry3D(roadLinkMap(r.linkId).geometry,
              r.startMValue, r.endMValue), linkGeomSource = roadLinkMap(r.linkId).linkSource))
          val removedIds = addresses.values.flatMap(_.allSegments).map(_.id).toSet -- (savedRoadAddresses ++ addressesExceptNew).map(x => x.id)
          removedIds.grouped(500).foreach(s => {
            RoadAddressDAO.expireById(s)
            logger.debug("Expired: " + s.mkString(","))
          })
          val toFloating = addressesExceptNew.filter(ra => ra.isFloating)
          logger.info(s"Found ${toFloating.size} road addresses that were left floating after changes, saving them.")
          toFloating.foreach {
            ra => RoadAddressDAO.changeRoadAddressFloatingWithHistory(ra.id, None, FloatingReason.ApplyChanges)
          }

          checkRoadAddressFloatingWithoutTX(addressesExceptNew.map(_.linkId).toSet, float = true)
          val ids = RoadAddressDAO.create(savedRoadAddresses).toSet ++ addressesExceptNew.map(_.id).toSet
          val changedRoadParts = addressesToCreate.map(a => (a.roadNumber, a.roadPartNumber)).toSet

          val adjustedRoadParts = changedRoadParts.filter { x => recalculateRoadAddresses(x._1, x._2) }
          // re-fetch after recalculation
          val adjustedAddresses = adjustedRoadParts.flatMap { case (road, part) => RoadAddressDAO.fetchByRoadPart(road, part) }

          val changedRoadAddresses = adjustedAddresses ++ RoadAddressDAO.fetchByIdMassQuery(ids -- adjustedAddresses.map(_.id), includeFloating = true)
          changedRoadAddresses.groupBy(cra => (cra.linkId, cra.roadwayId)).map(s => LinkRoadAddressHistory(s._2.toSeq.partition(_.endDate.isEmpty))).toSeq
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

    val vvhRoadLinks = roadLinkService.getRoadLinksByLinkIdsFromVVH(addresses.keySet, frozenTimeVVHAPIServiceEnabled)
    val combined = addresses.mapValues(combineGeom)
    val roadLinks = vvhRoadLinks.map(rl => rl -> combined(rl.linkId)).toMap

    roadLinks.flatMap { case (rl, ra) =>
      buildRoadAddressLink(rl, Seq(ra), Seq())
    }.toSeq
  }

  private def processRoadAddresses(addresses: Seq[RoadAddress], missedRL: Seq[MissingRoadAddress]): Seq[RoadAddressLink] = {
    val linkIds = addresses.map(_.linkId).toSet
    val anomaly = missedRL.headOption.map(_.anomaly).getOrElse(Anomaly.None)
    val (roadLinks, vvhHistoryLinks) = roadLinkService.getCurrentAndHistoryRoadLinksFromVVH(linkIds, frozenTimeVVHAPIServiceEnabled)
    (anomaly, addresses.size, roadLinks.size, vvhHistoryLinks.size) match {
      case (_, 0, 0, _) => List() // No road link currently exists and no addresses on this link id => ignore
      case (Anomaly.GeometryChanged, _, _, 0) => addresses.flatMap(a => roadLinks.map(rl => RoadAddressLinkBuilder.build(rl, a)))
      case (Anomaly.GeometryChanged, _, _, _) => addresses.flatMap(a => vvhHistoryLinks.map(rl => RoadAddressLinkBuilder.build(rl, a)))
      case (_, _, 0, _) => addresses.flatMap(a => vvhHistoryLinks.map(rl => RoadAddressLinkBuilder.build(rl, a)))
      case (Anomaly.NoAddressGiven, 0, _, _) => missedRL.flatMap(a => roadLinks.map(rl => RoadAddressLinkBuilder.build(rl, a)))
      case (_, _, _, _) => addresses.flatMap(a => roadLinks.map(rl => RoadAddressLinkBuilder.build(rl, a)))
    }
  }

  /**
    * returns road addresses with ID currently does not include terminated links which it cannot build roadaddress with out geometry
    *
    * @param id id
    * @return roadaddress[]
    */
  def getRoadAddressLinkById(id: Long): Seq[RoadAddressLink] = {
    val (addresses, missedRL) = withDynTransaction {
      val addr = RoadAddressDAO.fetchByIdMassQuery(Set(id), includeFloating = true, includeHistory = false)
      (addr, RoadAddressDAO.getMissingRoadAddresses(addr.map(_.linkId).toSet))
    }
    processRoadAddresses(addresses, missedRL)
  }

  /**
    * returns road addresses with link-id currently does not include terminated links which it cannot build roadaddress with out geometry
    *
    * @param linkId link-id
    * @return roadaddress[]
    */
  def getRoadAddressLink(linkId: Long): Seq[RoadAddressLink] = {
    val (addresses, missedRL) = withDynTransaction {
      (RoadAddressDAO.fetchByLinkId(Set(linkId), includeFloating = true, includeHistory = false, includeTerminated = false), // cannot builld terminated link because missing geometry
        RoadAddressDAO.getMissingRoadAddresses(Set(linkId)))
    }
    processRoadAddresses(addresses, missedRL)
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

  def getUniqueRoadAddressLink(id: Long): List[RoadAddressLink] = getRoadAddressLink(id).toList

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
    def hasTargetRoadLink(roadLinkOpt: Option[RoadLinkLike], geometryOpt: Option[Seq[Point]]) = {
      !(roadLinkOpt.isEmpty || geometryOpt.isEmpty || GeometryUtils.geometryLength(geometryOpt.get) == 0.0)
    }

    val addresses = RoadAddressDAO.queryById(ids)
    val linkIdMap = addresses.groupBy(_.linkId).mapValues(_.map(_.id))
    val roadLinks = roadLinkService.getCurrentAndComplementaryVVHRoadLinks(linkIdMap.keySet, frozenTimeVVHAPIServiceEnabled)
    addresses.foreach { address =>
      val roadLink = roadLinks.find(_.linkId == address.linkId)
      val addressGeometry = roadLink.map(rl =>
        GeometryUtils.truncateGeometry3D(rl.geometry, address.startMValue, address.endMValue))
      if (float && hasTargetRoadLink(roadLink, addressGeometry)) {
        logger.info(s"Floating and update geometry id ${address.id} (link id ${address.linkId})")
        RoadAddressDAO.changeRoadAddressFloatingWithHistory(address.id, addressGeometry, FloatingReason.GeometryChanged)
        val missing = MissingRoadAddress(address.linkId, Some(address.startAddrMValue), Some(address.endAddrMValue), RoadAddressLinkBuilder.getRoadType(roadLink.get.administrativeClass, UnknownLinkType), None, None, Some(address.startMValue), Some(address.endMValue), Anomaly.GeometryChanged, Seq.empty[Point])
        RoadAddressDAO.createMissingRoadAddress(missing.linkId, missing.startAddrMValue.getOrElse(0), missing.endAddrMValue.getOrElse(0), missing.anomaly.value, missing.startMValue.get, missing.endMValue.get)
      } else if (!hasTargetRoadLink(roadLink, addressGeometry)) {
        logger.info(s"Floating id ${address.id}")
        RoadAddressDAO.changeRoadAddressFloatingWithHistory(address.id, None, FloatingReason.NewAddressGiven)
      } else {
        if (!GeometryUtils.areAdjacent(addressGeometry.get, address.geometry)) {
          logger.info(s"Updating geometry for id ${address.id} (link id ${address.linkId})")
          RoadAddressDAO.changeRoadAddressFloatingWithHistory(address.id, addressGeometry, FloatingReason.GapInGeometry)}
      }
    }
  }

  def convertRoadAddressToFloating(linkId: Long): Unit = {
    withDynTransaction {
      val addresses = RoadAddressDAO.fetchByLinkId(Set(linkId), includeHistory = false, includeTerminated = false)
      addresses.foreach { address =>
        logger.info(s"Floating and update geometry id ${address.id} (link id ${address.linkId})")
        RoadAddressDAO.changeRoadAddressFloatingWithHistory(address.id, None, floatingReason = FloatingReason.ManualFloating)
        RoadAddressDAO.createMissingRoadAddress(address.linkId, address.startAddrMValue, address.endAddrMValue, Anomaly.GeometryChanged.value, address.startMValue, address.endMValue)
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
        val (roadLinks, changes) = roadLinkService.getRoadLinksWithComplementaryAndChangesFromVVH(municipality)
        (roadLinks.filterNot(r => r.linkSource == LinkGeomSource.ComplimentaryLinkInterface) ++ roadLinkService.getComplementaryRoadLinksFromVVH(municipality), changes)
      }
    val suravageLinks = roadLinkService.getSuravageRoadLinks(municipality)
    val allRoadLinks: Seq[RoadLink] = roadLinksWithComplementary ++ suravageLinks

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

  def saveAdjustments(addresses: Seq[LinearLocationAdjustment]): Unit = {
    withDynTransaction {
      addresses.foreach(RoadAddressDAO.updateLinearLocation)
    }
  }

  def getAdjacentAddresses(chainLinks: Set[Long], chainIds: Set[Long], linkId: Long,
                           id: Long, roadNumber: Long, roadPartNumber: Long, track: Track) = {
    withDynSession {
      getAdjacentAddressesInTX(chainLinks, chainIds, linkId, id, roadNumber, roadPartNumber, track)
    }
  }

  def getAdjacentAddressesInTX(chainLinks: Set[Long], chainIds: Set[Long], linkId: Long, id: Long, roadNumber: Long, roadPartNumber: Long, track: Track) = {
    val roadAddresses = (if (chainIds.nonEmpty)
      RoadAddressDAO.queryById(chainIds)
    else if (chainLinks.nonEmpty)
      RoadAddressDAO.fetchByLinkId(chainLinks, includeFloating = true, includeHistory = false)
    else Seq.empty[RoadAddress]
      ).sortBy(_.startAddrMValue)
    assert(roadAddresses.forall(r => r.roadNumber == roadNumber && r.roadPartNumber == roadPartNumber && r.track == track),
      s"Mixed floating addresses selected ($roadNumber/$roadPartNumber/$track): " + roadAddresses.map(r =>
        s"${r.linkId} = ${r.roadNumber}/${r.roadPartNumber}/${r.track.value}").mkString(", "))
    val startValues = roadAddresses.map(_.startAddrMValue)
    val endValues = roadAddresses.map(_.endAddrMValue)
    val orphanStarts = startValues.filterNot(st => endValues.contains(st))
    val orphanEnds = endValues.filterNot(st => startValues.contains(st))
    (orphanStarts.flatMap(st => RoadAddressDAO.fetchByAddressEnd(roadNumber, roadPartNumber, track, st))
      ++ orphanEnds.flatMap(end => RoadAddressDAO.fetchByAddressStart(roadNumber, roadPartNumber, track, end)))
      .distinct.filterNot(fo => chainIds.contains(fo.id) || chainLinks.contains(fo.linkId))
  }

  def getFloatingAdjacent(chainLinks: Set[Long], chainIds: Set[Long], linkId: Long, id: Long, roadNumber: Long, roadPartNumber: Long, trackCode: Int): Seq[RoadAddressLink] = {
    val (floatings, _) = withDynTransaction {
      RoadAddressDAO.fetchByRoadPart(roadNumber, roadPartNumber, includeFloating = true).partition(_.isFloating)
    }
    val historyLinks = time(logger, "Fetch floating history links") {
      roadLinkService.getRoadLinksHistoryFromVVH(floatings.map(_.linkId).toSet)
    }
    if (historyLinks.nonEmpty) {
      val historyLinkAddresses = time(logger, "Build history link addresses") {
        historyLinks.flatMap(fh => {
          buildFloatingRoadAddressLink(fh, floatings.filter(_.linkId == fh.linkId))
        })
      }
      historyLinkAddresses.find(_.id == id).orElse(historyLinkAddresses.find(_.linkId == linkId).orElse(Option.empty[RoadAddressLink])) match {
        case Some(sel) => {
          historyLinkAddresses.filter(ra => {
            ra.id != id && GeometryUtils.areAdjacent(ra.geometry, sel.geometry) && !chainIds.contains(ra.id)
          })
        }
        case _ => Seq.empty[RoadAddressLink]
      }
    } else {
      Seq.empty[RoadAddressLink]
    }
  }

  def getAdjacent(chainLinks: Set[Long], linkId: Long, newSession: Boolean = true): Seq[RoadAddressLink] = {
    val chainRoadLinks = roadLinkService.getRoadLinksByLinkIdsFromVVH(chainLinks, frozenTimeVVHAPIServiceEnabled)
    val pointCloud = chainRoadLinks.map(_.geometry).map(GeometryUtils.geometryEndpoints).flatMap(x => Seq(x._1, x._2))
    val boundingPoints = GeometryUtils.boundingRectangleCorners(pointCloud)
    val boundingRectangle = BoundingRectangle(boundingPoints._1 + Vector3d(-.1, .1, 0.0), boundingPoints._2 + Vector3d(.1, -.1, 0.0))
    val connectedLinks = roadLinkService.getRoadLinksAndChangesFromVVHWithFrozenAPI(boundingRectangle, frozenTimeVVHAPIServiceEnabled)._1
      .filterNot(rl => chainLinks.contains(rl.linkId))
      .filter { rl =>
        val endPoints = GeometryUtils.geometryEndpoints(rl.geometry)
        pointCloud.exists(p => GeometryUtils.areAdjacent(p, endPoints._1) || GeometryUtils.areAdjacent(p, endPoints._2))
      }.map(rl => rl.linkId -> rl).toMap
    val missingLinks = if (newSession) {
        withDynSession {
          RoadAddressDAO.getMissingRoadAddresses(connectedLinks.keySet)
        }
      } else {
        RoadAddressDAO.getMissingRoadAddresses(connectedLinks.keySet)
      }
    missingLinks.map(ml => RoadAddressLinkBuilder.build(connectedLinks(ml.linkId), ml))
  }

  def getRoadAddressLinksAfterCalculation(sources: Seq[String], targets: Seq[String], user: User): Seq[RoadAddressLink] = {
    val transferredRoadAddresses = getRoadAddressesAfterCalculation(sources, targets, user)
    val target = roadLinkService.getRoadLinksByLinkIdsFromVVH(targets.map(rd => rd.toLong).toSet, frozenTimeVVHAPIServiceEnabled)
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
      val currentRoadAddresses = RoadAddressDAO.fetchByLinkId(sourceIds, includeFloating = true, includeTerminated = false)
      RoadAddressDAO.expireById(currentRoadAddresses.map(_.id).toSet)
      RoadAddressDAO.create(roadAddresses, Some(username))
      val roadNumber = roadAddresses.head.roadNumber.toInt
      val roadPartNumber = roadAddresses.head.roadPartNumber.toInt
      if(RoadAddressDAO.fetchFloatingRoadAddressesBySegment(roadNumber, roadPartNumber).filterNot(address => sourceIds.contains(address.linkId)).isEmpty) {
        if (!recalculateRoadAddresses(roadNumber, roadPartNumber))
          throw new RoadAddressException(s"Road address recalculation failed for $roadNumber / $roadPartNumber")
      }
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

    val mapping = DefloatMapper.createAddressMap(latestSegments(sources.sortBy(_.startMValue)), targets).filter(_.sourceLen > MinAllowedRoadAddressLength)
    if (mapping.exists(DefloatMapper.invalidMapping)) {
      throw new InvalidAddressDataException("Mapping failed to map following items: " +
        mapping.filter(DefloatMapper.invalidMapping).map(
          r => s"${r.sourceLinkId}: ${r.sourceStartM}-${r.sourceEndM} -> ${r.targetLinkId}: ${r.targetStartM}-${r.targetEndM}").mkString(", ")
      )
    }
    val sourceRoadAddresses = withDynSession {
      RoadAddressDAO.fetchByLinkId(sources.map(_.linkId).toSet, includeFloating = true, includeTerminated = false)
    }

    val (currentSourceRoadAddresses, historySourceRoadAddresses) = sourceRoadAddresses.partition(ra => ra.endDate.isEmpty)

    DefloatMapper.preTransferChecks(currentSourceRoadAddresses)
    val currentTargetRoadAddresses = DefloatMapper.adjustRoadAddresses(RoadAddressLinkBuilder.fuseRoadAddressWithTransaction(currentSourceRoadAddresses.sortBy(_.startAddrMValue).flatMap(DefloatMapper.mapRoadAddresses(mapping, currentSourceRoadAddresses))), currentSourceRoadAddresses)
    DefloatMapper.postTransferChecks(currentTargetRoadAddresses.filter(_.endDate.isEmpty), currentSourceRoadAddresses)

    val historyTargetRoadAddresses = historySourceRoadAddresses.groupBy(_.endDate).flatMap(group => {
      DefloatMapper.preTransferChecks(group._2)
      val targetHistory = DefloatMapper.adjustRoadAddresses(RoadAddressLinkBuilder.fuseRoadAddressWithTransaction(group._2.flatMap(DefloatMapper.mapRoadAddresses(mapping, group._2))),group._2)
      targetHistory
    })

    currentTargetRoadAddresses ++ historyTargetRoadAddresses
  }

  def recalculateRoadAddresses(roadNumber: Long, roadPartNumber: Long): Boolean = {
    try {
      val roads = RoadAddressDAO.fetchByRoadPart(roadNumber, roadPartNumber, includeFloating = true)
      if (!roads.exists(_.isFloating)) {
        try {
          val adjusted = LinkRoadAddressCalculator.recalculate(roads)
          assert(adjusted.size == roads.size)
          // Must not lose any
          val (changed, unchanged) = adjusted.partition(ra =>
            roads.exists(oldra => ra.id == oldra.id && (oldra.startAddrMValue != ra.startAddrMValue || oldra.endAddrMValue != ra.endAddrMValue))
          )
          logger.info(s"Road $roadNumber, part $roadPartNumber: ${changed.size} updated, ${unchanged.size} kept unchanged")
          changed.foreach(addr => RoadAddressDAO.update(addr, None))
          return true
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

  def getRoadNumbers: Seq[Long] = {
    withDynSession {
      RoadAddressDAO.getRoadNumbers()
    }
  }

  def getRoadAddress(road: Long, roadPart: Long, track: Option[Int], addrMValue: Option[Long]): Seq[RoadAddress] = {
    withDynSession {
      RoadAddressDAO.getRoadAddressByFilter(RoadAddressDAO.withRoadAddress(road, roadPart, track, addrMValue))
    }
  }

  def getRoadAddressWithRoadNumber(road: Long, tracks: Seq[Int]): Seq[RoadAddress] = {
    withDynSession {
      RoadAddressDAO.getRoadAddressByFilter(RoadAddressDAO.withRoadNumber(road, tracks))
    }
  }

  def getRoadAddressWithRoadNumberAddress(road: Long, tracks: Seq[Int], addrMValue: Option[Long]): Seq[RoadAddress] = {
    withDynSession {
      RoadAddressDAO.getRoadAddressByFilter(RoadAddressDAO.withRoadNumberAddress(road, tracks, addrMValue))
    }
  }

  def getRoadAddressWithRoadNumberParts(road: Long, roadParts: Seq[Long], tracks: Seq[Int]): Seq[RoadAddress] = {
    withDynSession {
      RoadAddressDAO.getRoadAddressByFilter(RoadAddressDAO.withRoadNumberParts(road, roadParts, tracks))
    }
  }

  def getRoadAddressWithLinkIdAndMeasure(linkId: Long, startM: Option[Double], endM: Option[Double]): Seq[RoadAddress] = {
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
    withDynTransaction {
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
      val typesForBlackUnderline = Set(RoadType.MunicipalityStreetRoad.value, RoadType.PrivateRoadType.value)
      addresses.map(a => a.copy(blackUnderline = typesForBlackUnderline.contains(a.roadType.value)))
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

    def apply(intValue: Int): AddressError = {
      values.find(_.value == intValue).get
    }
  }

  case class AddressErrorDetails(id: Long, linkId: Long, roadNumber: Long, roadPartNumber: Long, addressError: AddressError, ely: Long)

}

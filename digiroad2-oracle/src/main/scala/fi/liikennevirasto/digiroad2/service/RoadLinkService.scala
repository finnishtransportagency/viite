package fi.liikennevirasto.digiroad2.service

import java.io.{File, FilenameFilter, IOException}
import java.util.concurrent.TimeUnit

import fi.liikennevirasto.GeometryUtils
import fi.liikennevirasto.digiroad2.asset.Asset._
import fi.liikennevirasto.digiroad2.asset._
import fi.liikennevirasto.digiroad2.client.vvh._
import fi.liikennevirasto.digiroad2.dao.ComplementaryFilterDAO
import fi.liikennevirasto.digiroad2.linearasset.RoadLink
import fi.liikennevirasto.digiroad2.oracle.OracleDatabase
import fi.liikennevirasto.digiroad2.util.{VVHSerializer, ViiteProperties}
import fi.liikennevirasto.digiroad2.{DigiroadEventBus, Point}
import org.joda.time.DateTime
import org.slf4j.{Logger, LoggerFactory}
import slick.jdbc.{GetResult, PositionedResult}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future}

case class IncompleteLink(linkId: Long, municipalityCode: Int, administrativeClass: AdministrativeClass)
case class RoadLinkChangeSet(adjustedRoadLinks: Seq[RoadLink], incompleteLinks: Seq[IncompleteLink])
case class ChangedVVHRoadlink(link: RoadLink, value: String, createdAt: Option[DateTime], changeType: String /*TODO create and use ChangeType case object*/)

//TODO delete all the references to frozen interface
/**
  * This class performs operations related to road links. It uses VVHClient to get data from VVH Rest API.
  *
  * @param vvhClient
  * @param eventbus
  * @param vvhSerializer
  * @param useFrozenLinkInterface
  */
class RoadLinkService(val vvhClient: VVHClient, val eventbus: DigiroadEventBus, val vvhSerializer: VVHSerializer, val useFrozenLinkInterface: Boolean) {
  val logger: Logger = LoggerFactory.getLogger(getClass)

  val complementaryFilterDAO = new ComplementaryFilterDAO

  def withDynTransaction[T](f: => T): T = OracleDatabase.withDynTransaction(f)

  def withDynSession[T](f: => T): T = OracleDatabase.withDynSession(f)

  implicit val getDateTime = new GetResult[DateTime] {
    def apply(r: PositionedResult): DateTime = {
      new DateTime(r.nextTimestamp())
    }
  }

  def getRoadLinksAndComplementaryFromVVH(linkIds: Set[Long]): Seq[RoadLink] = {
    val vvhRoadLinks = fetchVVHRoadLinksAndComplementaryFromVVH(linkIds)
    enrichRoadLinksFromVVH(vvhRoadLinks)
  }

  /**
    * This method returns "real" road links and "complementary" road links by bounding box and municipalities.
    *
    * @param bounds
    * @param municipalities
    * @return Road links
    */
  def getRoadLinksAndComplementaryFromVVH(bounds: BoundingRectangle, municipalities: Set[Int] = Set()): Seq[RoadLink] =
    getRoadLinksWithComplementaryAndChangesFromVVH(bounds, municipalities)._1


  def fetchVVHRoadLinksAndComplementaryFromVVH(linkIds: Set[Long]): Seq[VVHRoadlink] = {
    if (linkIds.nonEmpty) vvhClient.roadLinkData.fetchByLinkIds(linkIds) ++ vvhClient.complementaryData.fetchByLinkIds(linkIds)
    else Seq.empty[VVHRoadlink]
  }

  private def fetchVVHFrozenRoadLinksAndComplementaryFromVVH(linkIds: Set[Long]): Seq[VVHRoadlink] = {
    if (linkIds.nonEmpty) vvhClient.frozenTimeRoadLinkData.fetchByLinkIds(linkIds) ++ vvhClient.complementaryData.fetchByLinkIds(linkIds)
    else Seq.empty[VVHRoadlink]
  }

  def getMidPointByLinkId(linkId: Long): Option[Point] = {
    val roadLinkOption = vvhClient.roadLinkData.fetchByLinkId(linkId).orElse(vvhClient.complementaryData.fetchByLinkId(linkId))
    roadLinkOption.map{
      roadLink =>
        GeometryUtils.calculatePointFromLinearReference(roadLink.geometry, roadLink.length / 2.0).getOrElse(Point(roadLink.geometry.head.x, roadLink.geometry.head.y))
    }
  }
  /**
    * Returns road link middle point by mtk id. Used to select a road link by url to be shown on map (for example: index.html#linkProperty/mtkid/12345).
    *
    *
    */
  def getRoadLinkMiddlePointByMtkId(mtkId: Long):  Option[Point] = {
    vvhClient.roadLinkData.fetchByMmlId(mtkId).flatMap { vvhRoadLink =>
      Option(GeometryUtils.midPointGeometry(vvhRoadLink.geometry))
    }
  }

  def getRoadLinkByLinkIdFromVVH(linkId: Long): Option[RoadLink] = getRoadLinksByLinkIdsFromVVH(Set(linkId)).headOption

  def getRoadLinksByLinkIdsFromVVH(linkIds: Set[Long]): Seq[RoadLink] = {
    val vvhRoadLinks = getVVHRoadlinks(linkIds)
    enrichRoadLinksFromVVH(vvhRoadLinks)
  }

  /**
    * This method returns VVH road links by link ids.
    *
    * @param linkIds
    * @return VVHRoadLinks
    */
  def getVVHRoadlinks(linkIds: Set[Long]): Seq[VVHRoadlink] = {
    if (linkIds.nonEmpty) {
      if (useFrozenLinkInterface) {
        fetchVVHFrozenRoadLinksAndComplementaryFromVVH(linkIds)
      } else {
        fetchVVHRoadLinksAndComplementaryFromVVH(linkIds)
      }
    }
    else Seq.empty[VVHRoadlink]
  }

  /**
    * Returns road links and change data from VVH by bounding box and road numbers and municipalities. Used by RoadLinkService.getRoadLinksFromVVH and SpeedLimitService.get.
    */
  private def getRoadLinksAndChangesFromVVH(bounds: BoundingRectangle, roadNumbers: Seq[(Int, Int)],
                                            municipalities: Set[Int] = Set(), everything: Boolean,
                                            publicRoads: Boolean): (Seq[RoadLink], Seq[ChangeInfo]) = {
    val (changes, links) = Await.result(vvhClient.roadLinkChangeInfo.fetchByBoundsAndMunicipalitiesF(bounds, municipalities)
      .zip(if (everything) {
        vvhClient.roadLinkData.fetchByBoundsAndMunicipalitiesF(bounds, municipalities)
      } else {
        vvhClient.roadLinkData.fetchByRoadNumbersBoundsAndMunicipalitiesF(bounds, municipalities, roadNumbers, publicRoads)
      }), atMost = Duration.Inf)

    (enrichRoadLinksFromVVH(links), changes)
  }

  /**
    * This method returns "real" road links, "complementary" road links and change data by bounding box and municipalities.
    *
    * @param bounds
    * @param municipalities
    * @return Road links and change data
    */
  private def getRoadLinksWithComplementaryAndChangesFromVVH(bounds: BoundingRectangle, municipalities: Set[Int] = Set()): (Seq[RoadLink], Seq[ChangeInfo]) = {
    val fut = for {
      f1Result <- vvhClient.complementaryData.fetchWalkwaysByBoundsAndMunicipalitiesF(bounds, municipalities)
      f2Result <- vvhClient.roadLinkChangeInfo.fetchByBoundsAndMunicipalitiesF(bounds, municipalities)
      f3Result <- if (useFrozenLinkInterface)
                  vvhClient.frozenTimeRoadLinkData.fetchByBoundsAndMunicipalitiesF(bounds, municipalities)
                else
                  vvhClient.roadLinkData.fetchByBoundsAndMunicipalitiesF(bounds, municipalities)
    } yield (f1Result, f2Result, f3Result)
    val (complementaryLinks, changes, links) = Await.result(fut, Duration.Inf)
    (enrichRoadLinksFromVVH(links ++ filterComplementaryLinks(complementaryLinks)), changes)
  }

  def reloadRoadLinksWithComplementaryAndChangesFromVVH(municipalities: Int): (Seq[RoadLink], Seq[ChangeInfo], Seq[RoadLink]) = {
    val fut = for {
      f1Result <- vvhClient.complementaryData.fetchComplementaryByMunicipalitiesF(municipalities)
      f2Result <- vvhClient.roadLinkChangeInfo.fetchByMunicipalityF(municipalities)
      f3Result <- if (useFrozenLinkInterface)
                    vvhClient.frozenTimeRoadLinkData.fetchByMunicipalityF(municipalities)
                  else
                    vvhClient.roadLinkData.fetchByMunicipalityF(municipalities)
    } yield (f1Result, f2Result, f3Result)

    val (complementaryLinks, changes, links) = Await.result(fut, Duration.Inf)

    (enrichRoadLinksFromVVH(links), changes, enrichRoadLinksFromVVH(filterComplementaryLinks(complementaryLinks)))
  }

  def getRoadLinksAndChangesFromVVH(municipality: Int): (Seq[RoadLink], Seq[ChangeInfo]) = {
    val (roadLinks, changes, _) = reloadRoadLinksWithComplementaryAndChangesFromVVH(municipality)
    (roadLinks, changes)
  }

  def getRoadLinksWithComplementaryAndChangesFromVVH(municipality: Int): (Seq[RoadLink], Seq[ChangeInfo]) = {
    val (roadLinks, changes, complementaries) = reloadRoadLinksWithComplementaryAndChangesFromVVH(municipality)
    (roadLinks ++ complementaries, changes)
  }

  def getRoadLinksHistoryFromVVH(roadAddressesLinkIds: Set[Long]): Seq[VVHHistoryRoadLink] = {
    if (roadAddressesLinkIds.nonEmpty) {
      val historyData = Await.result(vvhClient.historyData.fetchVVHRoadLinkByLinkIdsF(roadAddressesLinkIds), atMost = Duration.Inf)
      val groupedData = historyData.groupBy(_.linkId)
      groupedData.mapValues(_.maxBy(_.endDate)).values.toSeq
    } else
      Nil
  }

  def getCurrentAndHistoryRoadLinksFromVVH(linkIds: Set[Long]): (Seq[RoadLink], Seq[VVHHistoryRoadLink]) = {
    val fut = for {
      f1Result <- vvhClient.historyData.fetchVVHRoadLinkByLinkIdsF(linkIds)
      f2Result <- if (useFrozenLinkInterface) vvhClient.frozenTimeRoadLinkData.fetchByLinkIdsF(linkIds) else {
        vvhClient.roadLinkData.fetchByLinkIdsF(linkIds)
      }
      f3Result <- vvhClient.complementaryData.fetchByLinkIdsF(linkIds)
    } yield (f1Result, f2Result, f3Result)

    val (historyData, currentData, complementaryData) = Await.result(fut, Duration.Inf)
    val uniqueHistoryData = historyData.groupBy(_.linkId).mapValues(_.maxBy(_.endDate)).values.toSeq

    (enrichRoadLinksFromVVH(currentData ++ complementaryData), uniqueHistoryData)
  }

  def getAllRoadLinksFromVVH(linkIds: Set[Long]): (Seq[RoadLink], Seq[VVHHistoryRoadLink]) = {
    val fut = for {
      f1Result <- vvhClient.historyData.fetchVVHRoadLinkByLinkIdsF(linkIds)
      f2Result <- vvhClient.roadLinkData.fetchByLinkIdsF(linkIds)
      f3Result <- vvhClient.complementaryData.fetchByLinkIdsF(linkIds)
    } yield (f1Result, f2Result, f3Result)

    val (historyData, currentData, complementaryData) = Await.result(fut, Duration.Inf)
    val uniqueHistoryData = historyData.groupBy(_.linkId).mapValues(_.maxBy(_.endDate)).values.toSeq

    (enrichRoadLinksFromVVH(currentData ++ complementaryData), uniqueHistoryData)
  }

  def getAllVisibleRoadLinksFromVVH(linkIds: Set[Long]): Seq[RoadLink] = {
    val fut = for {
      f1Result <- if (useFrozenLinkInterface) {
        vvhClient.frozenTimeRoadLinkData.fetchByLinkIdsF(linkIds)
      } else {
        vvhClient.roadLinkData.fetchByLinkIdsF(linkIds)
      }
      f2Result <- vvhClient.complementaryData.fetchByLinkIdsF(linkIds)
    } yield (f1Result, f2Result)

    val (currentData, complementaryData) = Await.result(fut, Duration.Inf)

    enrichRoadLinksFromVVH(currentData ++ complementaryData)
  }

  /**
    * Returns road links without change data from VVH by bounding box and road numbers and municipalities.
    */
  private def getRoadLinksFromVVH(bounds: BoundingRectangle, roadNumbers: Seq[(Int, Int)],
                                  municipalities: Set[Int] = Set(),
                                  publicRoads: Boolean): Seq[RoadLink] = {
    val links = Await.result(

      if (useFrozenLinkInterface)
        vvhClient.frozenTimeRoadLinkData.fetchByRoadNumbersBoundsAndMunicipalitiesF(bounds, municipalities, roadNumbers, publicRoads)
      else
        vvhClient.roadLinkData.fetchByRoadNumbersBoundsAndMunicipalitiesF(bounds, municipalities, roadNumbers, publicRoads),
      atMost = Duration.Inf)

    (enrichRoadLinksFromVVH(links), Seq())._1
  }

  def getRoadLinksFromVVH(bounds: BoundingRectangle, roadNumbers: Seq[(Int, Int)], municipalities: Set[Int],
                          everything: Boolean, publicRoads: Boolean): Seq[RoadLink] =
    if (bounds.area >= 1E6 || useFrozenLinkInterface)
      getRoadLinksFromVVH(bounds, roadNumbers, municipalities, publicRoads)
    else
      getRoadLinksAndChangesFromVVH(bounds, roadNumbers, municipalities, everything, publicRoads)._1

  /**
    * Returns the road links from VVH by municipality.
    *
    * @param municipality A integer, representative of the municipality Id.
    */
  def getRoadLinksFromVVHByMunicipality(municipality: Int): Seq[RoadLink] = {
    val links = if (useFrozenLinkInterface) {
      vvhClient.frozenTimeRoadLinkData.fetchByMunicipality(municipality)
    } else vvhClient.roadLinkData.fetchByMunicipality(municipality)
    (enrichRoadLinksFromVVH(links), Seq())._1
  }

  def getChangeInfoFromVVHF(bounds: BoundingRectangle, municipalities: Set[Int]): Future[Seq[ChangeInfo]] = {
    vvhClient.roadLinkChangeInfo.fetchByBoundsAndMunicipalitiesF(bounds, municipalities)
  }

  def getChangeInfoFromVVHF(linkIds: Set[Long]): Future[Seq[ChangeInfo]] = {
    vvhClient.roadLinkChangeInfo.fetchByLinkIdsF(linkIds)
  }

  /**
    * This method performs formatting operations to given vvh road links:
    * - auto-generation of functional class and link type by feature class
    * - information transfer from old link to new link from change data
    * It also passes updated links and incomplete links to be saved to db by actor.
    *
    * @param vvhRoadLinks
    * @return Road links
    */
  protected def enrichRoadLinksFromVVH(vvhRoadLinks: Seq[VVHRoadlink]): Seq[RoadLink] = {
    val groupedLinks = vvhRoadLinks.groupBy(_.linkId).mapValues(_.head)

    def autoGenerateProperties(roadLink: RoadLink): RoadLink = {
      val vvhRoadLink = groupedLinks.get(roadLink.linkId)
      vvhRoadLink.get.featureClass match {
        case FeatureClass.TractorRoad => roadLink.copy(functionalClass = 7, linkType = TractorRoad)
        case FeatureClass.DrivePath => roadLink.copy(functionalClass = 6, linkType = SingleCarriageway)
        case FeatureClass.CycleOrPedestrianPath => roadLink.copy(functionalClass = 8, linkType = CycleOrPedestrianPath)
        case _ => roadLink //similar logic used in RoadAddressBuilder
      }
    }

    getRoadLinkDataByLinkIds(vvhRoadLinks).map(autoGenerateProperties)
  }

  /**
    * Passes VVH road links to adjustedRoadLinks to get road links. Used by RoadLinkService.enrichRoadLinksFromVVH.
    */
  private def getRoadLinkDataByLinkIds(vvhRoadLinks: Seq[VVHRoadlink]): Seq[RoadLink] = {
    vvhRoadLinks.map { link =>
      RoadLink(link.linkId, link.geometry,
        GeometryUtils.geometryLength(link.geometry),
        link.administrativeClass,
        99,
        link.trafficDirection,
        UnknownLinkType,
        link.modifiedAt.map(DateTimePropertyFormat.print),
        None, link.attributes, link.constructionType, link.linkSource)
    }
  }

  def getComplementaryRoadLinksFromVVH(bounds: BoundingRectangle, municipalities: Set[Int] = Set()): Seq[RoadLink] = {
    val vvhRoadLinks = Await.result(vvhClient.complementaryData.fetchByBoundsAndMunicipalitiesF(bounds, municipalities), atMost = Duration.create(1, TimeUnit.HOURS))
    (enrichRoadLinksFromVVH(filterComplementaryLinks(vvhRoadLinks)), Seq.empty[ChangeInfo])._1
  }

  def getComplementaryRoadLinksFromVVH(municipality: Int): Seq[RoadLink] = {
    val vvhRoadLinks = Await.result(vvhClient.complementaryData.fetchByMunicipalityF(municipality), Duration.create(1, TimeUnit.HOURS))
    (enrichRoadLinksFromVVH(filterComplementaryLinks(vvhRoadLinks)), Seq.empty[ChangeInfo])._1
  }

  def getCurrentAndComplementaryRoadLinksFromVVHByMunicipality(municipality: Int, roadNumbers: Seq[(Int, Int)]): Seq[RoadLink] = {
    val complementaryF = vvhClient.complementaryData.fetchByMunicipalityAndRoadNumbersF(municipality, roadNumbers)
    val currentF = if (useFrozenLinkInterface) vvhClient.frozenTimeRoadLinkData.fetchByMunicipalityAndRoadNumbersF(municipality, roadNumbers) else vvhClient.roadLinkData.fetchByMunicipalityAndRoadNumbersF(municipality, roadNumbers)
    val (compLinks, vvhRoadLinks) = Await.result(complementaryF.zip(currentF), atMost = Duration.create(1, TimeUnit.HOURS))
    (enrichRoadLinksFromVVH(filterComplementaryLinks(compLinks) ++ vvhRoadLinks), Seq.empty[ChangeInfo])._1
  }

  def getCurrentAndComplementaryVVHRoadLinks(linkIds: Set[Long]): Seq[VVHRoadlink] = {
    val roadLinks = if (useFrozenLinkInterface) vvhClient.frozenTimeRoadLinkData.fetchByLinkIds(linkIds) else vvhClient.roadLinkData.fetchByLinkIds(linkIds)
    vvhClient.complementaryData.fetchByLinkIds(linkIds) ++ roadLinks
  }

  def getCurrentAndComplementaryRoadLinksFromVVH(linkIds: Set[Long]): Seq[RoadLink] = {
    val roadLinks = if (useFrozenLinkInterface) vvhClient.frozenTimeRoadLinkData.fetchByLinkIds(linkIds) else vvhClient.roadLinkData.fetchByLinkIds(linkIds)
    val roadLinksVVH = vvhClient.complementaryData.fetchByLinkIds(linkIds) ++ roadLinks
    enrichRoadLinksFromVVH(roadLinksVVH)
  }

  def filterComplementaryLinks(links: Seq[VVHRoadlink]): Seq[VVHRoadlink] = {
    val includedLinks = withDynSession {
      complementaryFilterDAO.fetchAll()
    }
    links.filter(l => includedLinks.contains(l.linkId))
  }
}

package fi.liikennevirasto.digiroad2.service

import java.io.{File, FilenameFilter, IOException}
import java.util.concurrent.TimeUnit

import fi.liikennevirasto.digiroad2.GeometryUtils
import fi.liikennevirasto.digiroad2.asset.Asset._
import fi.liikennevirasto.digiroad2.asset._
import fi.liikennevirasto.digiroad2.client.vvh._
import fi.liikennevirasto.digiroad2.dao.ComplementaryFilterDAO
import fi.liikennevirasto.digiroad2.linearasset.RoadLink
import fi.liikennevirasto.digiroad2.postgis.PostGISDatabase
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
case class ChangedRoadLinkFetched(link: RoadLink, value: String, createdAt: Option[DateTime], changeType: String /*TODO create and use ChangeType case object*/)

//TODO delete all the references to frozen interface
/**
  * This class performs operations related to road links. It uses VVHClient to get data from VVH Rest API.
  *
  * @param vvhClient
  * @param eventbus
  * @param vvhSerializer
  * @param useFrozenLinkInterface
  */
class RoadLinkService(val vvhClient: KgvRoadLink, val eventbus: DigiroadEventBus, val vvhSerializer: VVHSerializer, val useFrozenLinkInterface: Boolean) {
  val logger: Logger = LoggerFactory.getLogger(getClass)

  val complementaryFilterDAO = new ComplementaryFilterDAO

  def withDynTransaction[T](f: => T): T = PostGISDatabase.withDynTransaction(f)

  def withDynSession[T](f: => T): T = PostGISDatabase.withDynSession(f)

  implicit val getDateTime = new GetResult[DateTime] {
    def apply(r: PositionedResult): DateTime = {
      new DateTime(r.nextTimestamp())
    }
  }

  def getRoadLinksAndComplementaryFromVVH(linkIds: Set[String]): Seq[RoadLink] = {
    val RoadLinkFetcheds = fetchRoadLinkFetchedsAndComplementaryFromVVH(linkIds)
    enrichRoadLinksFromVVH(RoadLinkFetcheds)
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


  def fetchRoadLinkFetchedsAndComplementaryFromVVH(linkIds: Set[String]): Seq[RoadLinkFetched] = {
    if (linkIds.nonEmpty) vvhClient.roadLinkData.fetchByLinkIds(linkIds) ++ vvhClient.complementaryData.fetchByLinkIds(linkIds)
    else Seq.empty[RoadLinkFetched]
  }

  private def fetchVVHFrozenRoadLinksAndComplementaryFromVVH(linkIds: Set[String]): Seq[RoadLinkFetched] = {
    if (linkIds.nonEmpty) vvhClient.frozenTimeRoadLinkData.fetchByLinkIds(linkIds) ++ vvhClient.complementaryData.fetchByLinkIds(linkIds)
    else Seq.empty[RoadLinkFetched]
  }

  def getMidPointByLinkId(linkId: String): Option[Point] = {
    val client = if (useFrozenLinkInterface) vvhClient.frozenTimeRoadLinkData else vvhClient.roadLinkData
    val roadLinkOption = client.fetchByLinkId(linkId).orElse(vvhClient.complementaryData.fetchByLinkId(linkId))
    roadLinkOption.map {
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
    val client = if (useFrozenLinkInterface) vvhClient.frozenTimeRoadLinkData else vvhClient.roadLinkData
    client.fetchByMmlId(mtkId).flatMap { RoadLinkFetched =>
      Option(GeometryUtils.midPointGeometry(RoadLinkFetched.geometry))
    }
  }

  def getRoadLinkByLinkIdFromVVH(linkId: String): Option[RoadLink] = getRoadLinksByLinkIdsFromVVH(Set(linkId)).headOption

  def getRoadLinksByLinkIdsFromVVH(linkIds: Set[String]): Seq[RoadLink] = {
    val RoadLinkFetcheds = getRoadLinkFetcheds(linkIds)
    enrichRoadLinksFromVVH(RoadLinkFetcheds)
  }

  /**
    * This method returns VVH road links by link ids.
    *
    * @param linkIds
    * @return RoadLinkFetcheds
    */
  def getRoadLinkFetcheds(linkIds: Set[String]): Seq[RoadLinkFetched] = {
    if (linkIds.nonEmpty) {
      if (useFrozenLinkInterface) {
        fetchVVHFrozenRoadLinksAndComplementaryFromVVH(linkIds)
      } else {
        fetchRoadLinkFetchedsAndComplementaryFromVVH(linkIds)
      }
    }
    else Seq.empty[RoadLinkFetched]
  }

  /**
    * Returns road links and change data from VVH by bounding box and road numbers and municipalities. Used by RoadLinkService.getRoadLinksFromVVH and SpeedLimitService.get.
    */
  private def getRoadLinksAndChangesFromVVH(bounds: BoundingRectangle, roadNumbers: Seq[(Int, Int)],
                                            municipalities: Set[Int] = Set(), everything: Boolean,
                                            publicRoads: Boolean): (Seq[RoadLink], Seq[ChangeInfo]) = {
    val (changes, links) = Await.result(Future(Seq()) //vvhClient.roadLinkChangeInfo.fetchByBoundsAndMunicipalitiesF(bounds, municipalities) VIITE-2789
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
      f2Result <- Future(Seq()) //vvhClient.roadLinkChangeInfo.fetchByBoundsAndMunicipalitiesF(bounds, municipalities) VIITE-2789
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
      f2Result <- Future(Seq()) //vvhClient.roadLinkChangeInfo.fetchByMunicipalityF(municipalities) VIITE-2789
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

  def getRoadLinksHistoryFromVVH(roadAddressesLinkIds: Set[String]): Seq[HistoryRoadLink] = {
    if (roadAddressesLinkIds.nonEmpty) {
      val historyData = Await.result(vvhClient.historyData.fetchRoadLinkFetchedByLinkIdsF(roadAddressesLinkIds), atMost = Duration.Inf)
      val groupedData = historyData.groupBy(_.linkId)
      groupedData.mapValues(_.maxBy(_.endDate)).values.toSeq
    } else
      Nil
  }

  def getCurrentAndHistoryRoadLinksFromVVH(linkIds: Set[String]): (Seq[RoadLink], Seq[HistoryRoadLink]) = {
    val fut = for {
      f1Result <- vvhClient.historyData.fetchRoadLinkFetchedByLinkIdsF(linkIds)
      f2Result <- if (useFrozenLinkInterface) vvhClient.frozenTimeRoadLinkData.fetchByLinkIdsF(linkIds) else {
        vvhClient.roadLinkData.fetchByLinkIdsF(linkIds)
      }
      f3Result <- vvhClient.complementaryData.fetchByLinkIdsF(linkIds)
    } yield (f1Result, f2Result, f3Result)

    val (historyData, currentData, complementaryData) = Await.result(fut, Duration.Inf)
    val uniqueHistoryData = historyData.groupBy(_.linkId).mapValues(_.maxBy(_.endDate)).values.toSeq

    (enrichRoadLinksFromVVH(currentData ++ complementaryData), uniqueHistoryData)
  }

  def getAllRoadLinksFromVVH(linkIds: Set[String]): (Seq[RoadLink], Seq[HistoryRoadLink]) = {
    val fut = for {
      f1Result <- vvhClient.historyData.fetchRoadLinkFetchedByLinkIdsF(linkIds)
      f2Result <- vvhClient.roadLinkData.fetchByLinkIdsF(linkIds)
      f3Result <- vvhClient.complementaryData.fetchByLinkIdsF(linkIds)
    } yield (f1Result, f2Result, f3Result)

    val (historyData, currentData, complementaryData) = Await.result(fut, Duration.Inf)
    val uniqueHistoryData = historyData.groupBy(_.linkId).mapValues(_.maxBy(_.endDate)).values.toSeq

    (enrichRoadLinksFromVVH(currentData ++ complementaryData), uniqueHistoryData)
  }

  def getAllVisibleRoadLinksFromVVH(linkIds: Set[String]): Seq[RoadLink] = {
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
    Future(Seq()) //vvhClient.roadLinkChangeInfo.fetchByBoundsAndMunicipalitiesF(bounds, municipalities) VIITE-2789
  }

  def getChangeInfoFromVVHF(linkIds: Set[String]): Future[Seq[ChangeInfo]] = {
    Future(Seq()) //vvhClient.roadLinkChangeInfo.fetchByLinkIdsF(linkIds) VIITE-2789
  }

  /**
    * This method performs formatting operations to given vvh road links:
    * - auto-generation of functional class and link type by feature class
    * - information transfer from old link to new link from change data
    * It also passes updated links and incomplete links to be saved to db by actor.
    *
    * @param RoadLinkFetcheds
    * @return Road links
    */
  protected def enrichRoadLinksFromVVH(RoadLinkFetcheds: Seq[RoadLinkFetched]): Seq[RoadLink] = {
    val groupedLinks = RoadLinkFetcheds.groupBy(_.linkId).mapValues(_.head)

    def autoGenerateProperties(roadLink: RoadLink): RoadLink = {
      val RoadLinkFetched = groupedLinks.get(roadLink.linkId)
      RoadLinkFetched.get.featureClass match {
        case FeatureClass.TractorRoad => roadLink.copy(functionalClass = 7, linkType = TractorRoad)
        case FeatureClass.DrivePath => roadLink.copy(functionalClass = 6, linkType = SingleCarriageway)
        case FeatureClass.CycleOrPedestrianPath => roadLink.copy(functionalClass = 8, linkType = CycleOrPedestrianPath)
        case _ => roadLink //similar logic used in RoadAddressBuilder
      }
    }

    getRoadLinkDataByLinkIds(RoadLinkFetcheds).map(autoGenerateProperties)
  }

  /**
    * Passes VVH road links to adjustedRoadLinks to get road links. Used by RoadLinkService.enrichRoadLinksFromVVH.
    */
  private def getRoadLinkDataByLinkIds(RoadLinkFetcheds: Seq[RoadLinkFetched]): Seq[RoadLink] = {
    RoadLinkFetcheds.map { link =>
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
    val RoadLinkFetcheds = Await.result(vvhClient.complementaryData.fetchByBoundsAndMunicipalitiesF(bounds, municipalities), atMost = Duration.create(1, TimeUnit.HOURS))
    (enrichRoadLinksFromVVH(filterComplementaryLinks(RoadLinkFetcheds)), Seq.empty[ChangeInfo])._1
  }

  def getComplementaryRoadLinksFromVVH(municipality: Int): Seq[RoadLink] = {
    val RoadLinkFetcheds = Await.result(vvhClient.complementaryData.fetchByMunicipalityF(municipality), Duration.create(1, TimeUnit.HOURS))
    (enrichRoadLinksFromVVH(filterComplementaryLinks(RoadLinkFetcheds)), Seq.empty[ChangeInfo])._1
  }

  def getCurrentAndComplementaryRoadLinksFromVVHByMunicipality(municipality: Int, roadNumbers: Seq[(Int, Int)]): Seq[RoadLink] = {
    val complementaryF: Future[Seq[RoadLinkFetched]] = vvhClient.complementaryData.fetchByMunicipalityAndRoadNumbersF(municipality, roadNumbers)
    val currentF      : Future[Seq[RoadLinkFetched]] = if (useFrozenLinkInterface) vvhClient.frozenTimeRoadLinkData.fetchByMunicipalityAndRoadNumbersF(municipality, roadNumbers) else vvhClient.roadLinkData.fetchByMunicipalityAndRoadNumbersF(municipality, roadNumbers)
    val (compLinks, roadLinkFetched): (Seq[RoadLinkFetched],Seq[RoadLinkFetched]) = Await.result(complementaryF.zip(currentF), atMost = Duration.create(1, TimeUnit.HOURS))
    (enrichRoadLinksFromVVH(filterComplementaryLinks(compLinks) ++ roadLinkFetched), Seq.empty[ChangeInfo])._1
  }

  def getCurrentAndComplementaryRoadLinkFetcheds(linkIds: Set[String]): Seq[RoadLinkFetched] = {
    val roadLinks = if (useFrozenLinkInterface) vvhClient.frozenTimeRoadLinkData.fetchByLinkIds(linkIds) else vvhClient.roadLinkData.fetchByLinkIds(linkIds)
    vvhClient.complementaryData.fetchByLinkIds(linkIds) ++ roadLinks
  }

  def getCurrentAndComplementaryRoadLinksFromVVH(linkIds: Set[String]): Seq[RoadLink] = {
    val roadLinks = if (useFrozenLinkInterface) vvhClient.frozenTimeRoadLinkData.fetchByLinkIds(linkIds) else vvhClient.roadLinkData.fetchByLinkIds(linkIds)
    val roadLinksVVH = vvhClient.complementaryData.fetchByLinkIds(linkIds) ++ roadLinks
    enrichRoadLinksFromVVH(roadLinksVVH)
  }

  def filterComplementaryLinks(links: Seq[RoadLinkFetched]): Seq[RoadLinkFetched] = {
    val includedLinks = withDynSession {
      complementaryFilterDAO.fetchAll()
    }
    links.filter(l => includedLinks.contains(l.linkId))
  }
}

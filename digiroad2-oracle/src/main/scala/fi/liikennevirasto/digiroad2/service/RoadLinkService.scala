package fi.liikennevirasto.digiroad2.service

import java.util.concurrent.TimeUnit

import fi.liikennevirasto.digiroad2.{DigiroadEventBus, GeometryUtils, Point}
import fi.liikennevirasto.digiroad2.asset._
import fi.liikennevirasto.digiroad2.client.kgv._
import fi.liikennevirasto.digiroad2.linearasset.RoadLink
import fi.liikennevirasto.digiroad2.postgis.PostGISDatabase
import fi.liikennevirasto.digiroad2.util.KGVSerializer
import org.joda.time.DateTime
import org.slf4j.{Logger, LoggerFactory}
import slick.jdbc.{GetResult, PositionedResult}

import scala.concurrent.{Await, Future}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.Duration

case class IncompleteLink(linkId: Long, municipalityCode: Int, administrativeClass: AdministrativeClass)
case class RoadLinkChangeSet(adjustedRoadLinks: Seq[RoadLink], incompleteLinks: Seq[IncompleteLink])
case class ChangedRoadLinkFetched(link: RoadLink, value: String, createdAt: Option[DateTime], changeType: String /*TODO create and use ChangeType case object*/)

//TODO delete all the references to frozen interface
/**
  * This class performs operations related to road links. It uses KGVClient to get data from KGV Rest API.
  *
  * @param kgvClient
  * @param eventbus
  * @param kgvSerializer
  * @param useFrozenLinkInterface
  */
class RoadLinkService(val kgvClient: KgvRoadLink, val eventbus: DigiroadEventBus, val kgvSerializer: KGVSerializer, val useFrozenLinkInterface: Boolean) {
  val logger: Logger = LoggerFactory.getLogger(getClass)

  def withDynTransaction[T](f: => T): T = PostGISDatabase.withDynTransaction(f)

  def withDynSession[T](f: => T): T = PostGISDatabase.withDynSession(f)

  implicit val getDateTime = new GetResult[DateTime] {
    def apply(r: PositionedResult): DateTime = {
      new DateTime(r.nextTimestamp())
    }
  }

  def getRoadLinksAndComplementary(linkIds: Set[String]): Seq[RoadLink] = {
    fetchRoadLinkAndComplementaryData(linkIds)
  }

  /**
    * This method returns "real" road links and "complementary" road links by bounding box and municipalities.
    *
    * @param bounds
    * @param municipalities
    * @return Road links
    */
  def getRoadLinksAndComplementary(bounds: BoundingRectangle, municipalities: Set[Int] = Set()): Seq[RoadLink] =
    getRoadLinksWithComplementaryAndChangesFromVVH(bounds, municipalities)._1


  def fetchRoadLinkAndComplementaryData(linkIds: Set[String]): Seq[RoadLink] = {
    if (linkIds.nonEmpty) kgvClient.roadLinkData.fetchByLinkIds(linkIds) ++ kgvClient.complementaryData.fetchByLinkIds(linkIds)
    else Seq.empty[RoadLink]
  }

  private def fetchFrozenAndComplementaryRoadLinks(linkIds: Set[String]) = {
    if (linkIds.nonEmpty) kgvClient.frozenTimeRoadLinkData.fetchByLinkIds(linkIds) ++ kgvClient.complementaryData.fetchByLinkIds(linkIds)
    else Seq.empty[RoadLink]
  }

  def getMidPointByLinkId(linkId: String): Option[Point] = {
    val client = if (useFrozenLinkInterface) kgvClient.frozenTimeRoadLinkData else kgvClient.roadLinkData
    val roadLinkOption = client.fetchByLinkId(linkId).orElse(kgvClient.complementaryData.fetchByLinkId(linkId))
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
  def getRoadLinkMiddlePointBySourceId(sourceId: Long):  Option[Point] = {
    val client = if (useFrozenLinkInterface) kgvClient.frozenTimeRoadLinkData else kgvClient.roadLinkData
    client.fetchBySourceId(sourceId).flatMap { RoadLink =>
      Option(GeometryUtils.midPointGeometry(RoadLink.geometry))
    }
  }

  def getRoadLinkByLinkId(linkId: String): Option[RoadLink] = getRoadLinksByLinkIds(Set(linkId)).headOption

  def getRoadLinksByLinkIds(linkIds: Set[String]): Seq[RoadLink] = {
    getRoadLinks(linkIds)
  }

  /**
    * This method returns road links by link ids.
    *
    * @param linkIds
    * @return RoadLinkFetcheds
    */
  def getRoadLinks(linkIds: Set[String]): Seq[RoadLink] = {
    if (linkIds.nonEmpty) {
      if (useFrozenLinkInterface) {
        fetchFrozenAndComplementaryRoadLinks(linkIds)
      } else {
        fetchRoadLinkAndComplementaryData(linkIds)
      }
    }
    else Seq.empty[RoadLink]
  }

  /**
    * Returns road links and change data from VVH by bounding box and road numbers and municipalities. Used by RoadLinkService.getRoadLinksFromVVH and SpeedLimitService.get.
    */
  private def getRoadLinksAndChangesFromVVH(bounds: BoundingRectangle, roadNumbers: Seq[(Int, Int)],
                                            municipalities: Set[Int] = Set(), everything: Boolean,
                                            publicRoads: Boolean): (Seq[RoadLink], Seq[ChangeInfo]) = {
    val (changes, links) = Await.result(Future(Seq()) //vvhClient.roadLinkChangeInfo.fetchByBoundsAndMunicipalitiesF(bounds, municipalities) VIITE-2789
      .zip(if (everything) {
        kgvClient.roadLinkData.fetchByBoundsAndMunicipalitiesF(bounds, municipalities)
      } else {
        kgvClient.roadLinkData.fetchByRoadNumbersBoundsAndMunicipalitiesF(bounds, municipalities, roadNumbers, publicRoads)
      }), atMost = Duration.Inf)

    (links, changes)
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
      f1Result <- kgvClient.complementaryData.fetchWalkwaysByBoundsAndMunicipalitiesF(bounds, municipalities)
      f2Result <- Future(Seq()) //vvhClient.roadLinkChangeInfo.fetchByBoundsAndMunicipalitiesF(bounds, municipalities) VIITE-2789
      f3Result <- if (useFrozenLinkInterface)
        kgvClient.frozenTimeRoadLinkData.fetchByBoundsAndMunicipalitiesF(bounds, municipalities)
                else
                  kgvClient.roadLinkData.fetchByBoundsAndMunicipalitiesF(bounds, municipalities)
    } yield (f1Result, f2Result, f3Result)
    val (complementaryLinks, changes, links) = Await.result(fut, Duration.Inf)
    (links ++ complementaryLinks, changes)
  }

  def reloadRoadLinksWithComplementaryAndChangesFromVVH(municipalities: Int): (Seq[RoadLink], Seq[ChangeInfo], Seq[RoadLink]) = {
    val fut = for {
      f1Result <- kgvClient.complementaryData.fetchComplementaryByMunicipalitiesF(municipalities)
      f2Result <- Future(Seq()) //vvhClient.roadLinkChangeInfo.fetchByMunicipalityF(municipalities) VIITE-2789
      f3Result <- if (useFrozenLinkInterface)
        kgvClient.frozenTimeRoadLinkData.fetchByMunicipalityF(municipalities)
                  else
                    kgvClient.roadLinkData.fetchByMunicipalityF(municipalities)
    } yield (f1Result, f2Result, f3Result)

    val (complementaryLinks, changes, links) = Await.result(fut, Duration.Inf)

    (links, changes, complementaryLinks)
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
      Nil
  }

  def getCurrentAndHistoryRoadLinks(linkIds: Set[String]): (Seq[RoadLink], Seq[HistoryRoadLink]) = {
    val fut = for {
      f2Result <- if (useFrozenLinkInterface) kgvClient.frozenTimeRoadLinkData.fetchByLinkIdsF(linkIds) else {
        kgvClient.roadLinkData.fetchByLinkIdsF(linkIds)
      }
      f3Result <- kgvClient.complementaryData.fetchByLinkIdsF(linkIds)
    } yield (f2Result, f3Result)

    val (currentData, complementaryData) = Await.result(fut, Duration.Inf)
    val uniqueHistoryData = Seq[HistoryRoadLink]()

    (currentData ++ complementaryData, uniqueHistoryData)
  }

  def getAllRoadLinks(linkIds: Set[String]): (Seq[RoadLink], Seq[HistoryRoadLink]) = {
    val fut = for {
      f2Result <- kgvClient.roadLinkData.fetchByLinkIdsF(linkIds)
      f3Result <- kgvClient.complementaryData.fetchByLinkIdsF(linkIds)
    } yield (f2Result, f3Result)

    val (currentData, complementaryData) = Await.result(fut, Duration.Inf)
    val uniqueHistoryData = Seq[HistoryRoadLink]()

    (currentData ++ complementaryData, uniqueHistoryData)
  }

  def getAllVisibleRoadLinks(linkIds: Set[String]): Seq[RoadLink] = {
    val fut = for {
      f1Result <- if (useFrozenLinkInterface) {
        kgvClient.frozenTimeRoadLinkData.fetchByLinkIdsF(linkIds)
      } else {
        kgvClient.roadLinkData.fetchByLinkIdsF(linkIds)
      }
      f2Result <- kgvClient.complementaryData.fetchByLinkIdsF(linkIds)
    } yield (f1Result, f2Result)

    val (currentData, complementaryData) = Await.result(fut, Duration.Inf)
    currentData ++ complementaryData
  }

  /**
    * Returns road links without change data from VVH by bounding box and road numbers and municipalities.
    */
  private def getRoadLinks(bounds        : BoundingRectangle,
                           roadNumbers   : Seq[(Int, Int)],
                           municipalities: Set[Int] = Set(),
                           publicRoads   : Boolean): Seq[RoadLink] = {
     Await.result(
      if (useFrozenLinkInterface)
        kgvClient.frozenTimeRoadLinkData.fetchByRoadNumbersBoundsAndMunicipalitiesF(bounds, municipalities, roadNumbers, publicRoads)
      else
        kgvClient.roadLinkData.fetchByRoadNumbersBoundsAndMunicipalitiesF(bounds, municipalities, roadNumbers, publicRoads),
      atMost = Duration.Inf)
  }

  def getRoadLinks(bounds    : BoundingRectangle, roadNumbers: Seq[(Int, Int)], municipalities: Set[Int],
                   everything: Boolean, publicRoads: Boolean): Seq[RoadLink] =
    if (bounds.area >= 1E6 || useFrozenLinkInterface)
      getRoadLinks(bounds, roadNumbers, municipalities, publicRoads)
    else
      getRoadLinksAndChangesFromVVH(bounds, roadNumbers, municipalities, everything, publicRoads)._1

  /**
    * Returns the road links from VVH by municipality.
    *
    * @param municipality A integer, representative of the municipality Id.
    */
  def getRoadLinksByMunicipality(municipality: Int): Seq[RoadLink] = {
    if (useFrozenLinkInterface) {
      kgvClient.frozenTimeRoadLinkData.fetchByMunicipality(municipality)
    } else kgvClient.roadLinkData.fetchByMunicipality(municipality)
  }

  def getChangeInfoFromVVHF(bounds: BoundingRectangle, municipalities: Set[Int]): Future[Seq[ChangeInfo]] = {
    Future(Seq()) //vvhClient.roadLinkChangeInfo.fetchByBoundsAndMunicipalitiesF(bounds, municipalities) VIITE-2789
  }

  def getChangeInfoFromVVHF(linkIds: Set[String]): Future[Seq[ChangeInfo]] = {
    Future(Seq()) //vvhClient.roadLinkChangeInfo.fetchByLinkIdsF(linkIds) VIITE-2789
  }

  def getComplementaryRoadLinks(bounds: BoundingRectangle, municipalities: Set[Int] = Set()): Future[Seq[RoadLink]] = {
    kgvClient.complementaryData.fetchByBoundsAndMunicipalitiesF(bounds, municipalities)
  }

  def getCurrentAndComplementaryRoadLinksByMunicipality(municipality: Int, roadNumbers: Seq[(Int, Int)]): Seq[RoadLink] = {
    val complementaryF = kgvClient.complementaryData.fetchByMunicipalityAndRoadNumbersF(municipality, roadNumbers)
    val currentF       = if (useFrozenLinkInterface) kgvClient.frozenTimeRoadLinkData.fetchByMunicipalityAndRoadNumbersF(municipality, roadNumbers) else kgvClient.roadLinkData.fetchByMunicipalityAndRoadNumbersF(municipality, roadNumbers)
    val (compLinks, roadLink) = Await.result(complementaryF.zip(currentF), atMost = Duration.create(1, TimeUnit.HOURS))
    compLinks ++ roadLink
  }

  def getCurrentAndComplementaryRoadLinkFetcheds(linkIds: Set[String]): Seq[RoadLink] = {
    val roadLinks = if (useFrozenLinkInterface) kgvClient.frozenTimeRoadLinkData.fetchByLinkIds(linkIds) else kgvClient.roadLinkData.fetchByLinkIds(linkIds)
    kgvClient.complementaryData.fetchByLinkIds(linkIds) ++ roadLinks
  }

  def getCurrentAndComplementaryRoadLinks(linkIds: Set[String]): Seq[RoadLink] = {
    val roadLinks = if (useFrozenLinkInterface) kgvClient.frozenTimeRoadLinkData.fetchByLinkIds(linkIds) else kgvClient.roadLinkData.fetchByLinkIds(linkIds)
    kgvClient.complementaryData.fetchByLinkIds(linkIds) ++ roadLinks
  }
}

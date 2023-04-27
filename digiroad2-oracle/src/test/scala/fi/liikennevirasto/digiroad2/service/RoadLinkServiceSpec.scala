package fi.liikennevirasto.digiroad2.service

import fi.liikennevirasto.digiroad2.{DigiroadEventBus, DummyEventBus, DummySerializer, Point}
import fi.liikennevirasto.digiroad2.asset._
import fi.liikennevirasto.digiroad2.client.kgv._
import fi.liikennevirasto.digiroad2.dao.ComplementaryLinkDAO
import fi.liikennevirasto.digiroad2.linearasset.RoadLink
import fi.liikennevirasto.digiroad2.postgis.PostGISDatabase
import fi.liikennevirasto.digiroad2.util.KGVSerializer
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito._
import org.scalatest.{BeforeAndAfter, FunSuite, Matchers}
import org.scalatest.mockito.MockitoSugar

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Await, Future}
import scala.concurrent.duration.Duration

class RoadLinkServiceSpec extends FunSuite with Matchers with BeforeAndAfter {

  class TestService(KGVClient: KgvRoadLink, eventBus: DigiroadEventBus = new DummyEventBus, kgvSerializer: KGVSerializer = new DummySerializer)
    extends RoadLinkService(KGVClient, eventBus, kgvSerializer, false) {
    override def withDynTransaction[T](f: => T): T = f
    override def withDynSession[T](f: => T): T = f
  }

  class RoadLinkTestService(KGVClient: KgvRoadLink, eventBus: DigiroadEventBus = new DummyEventBus, kgvSerializer: KGVSerializer = new DummySerializer)
    extends RoadLinkService(KGVClient, eventBus, kgvSerializer, false) {
    override def withDynTransaction[T](f: => T): T = f
    override def withDynSession[T](f: => T): T = f
  }

  test("Test getRoadLinksByMunicipality() When supplying a specific municipality Id Then return the correct return of a ViiteRoadLink of that Municipality") {
    val municipalityId = 235
    val linkId = 2l.toString
    val roadLink = RoadLink(linkId, Nil, 0, AdministrativeClass.Municipality, TrafficDirection.TowardsDigitizing, None, None, LifecycleStatus.UnknownLifecycleStatus, municipalityCode = municipalityId, sourceId = "")

    val mockKGVClient = MockitoSugar.mock[KgvRoadLink]
    val mockKGVRoadLinkClient = MockitoSugar.mock[KgvRoadLinkClient[RoadLink]]
    val service = new TestService(mockKGVClient)

    PostGISDatabase.withDynTransaction {
      when(mockKGVClient.roadLinkData).thenReturn(mockKGVRoadLinkClient)
      when(mockKGVRoadLinkClient.fetchByMunicipality(municipalityId)).thenReturn(Seq(roadLink))

      val roadLinks = service.getRoadLinksByMunicipality(municipalityId)

      roadLinks.nonEmpty should be (true)
      roadLinks.head.isInstanceOf[RoadLink] should be (true)
      roadLinks.head.linkId should be(linkId)
      roadLinks.head.municipalityCode should be (municipalityId)
    }
  }

  ignore("Test getComplementaryRoadLinks() When submitting a bounding box as a search area Then return any complimentary geometry that are inside said area") {
    /*val municipalityId = 235
    val linkId = 2l.toString
    val roadLink: RoadLink = RoadLink(linkId, Seq(), 0, AdministrativeClass.Municipality, TrafficDirection.TowardsDigitizing, None,None, municipalityCode = municipalityId, sourceId = "")
    val boundingBox = BoundingRectangle(Point(123, 345), Point(567, 678))

    val mockKGVClient = MockitoSugar.mock[KgvRoadLink]
    val mockComplementaryClient = MockitoSugar.mock[ComplementaryLinkDAO]
    val service = new TestService(mockKGVClient)

    PostGISDatabase.withDynTransaction {
      when(mockKGVClient.complementaryData).thenReturn(mockComplementaryClient)
      when(mockComplementaryClient.fetchByBoundsAndMunicipalitiesF(any[BoundingRectangle], any[Set[Int]])).thenReturn(Future(Seq(roadLink)))

      val roadLinksList = Await.result(service.getComplementaryRoadLinks(boundingBox, Set.empty), Duration.Zero)

      roadLinksList.length > 0 should be(true)
      roadLinksList.head.isInstanceOf[RoadLink] should be(true)
      roadLinksList.head.linkId should be(linkId)
      roadLinksList.head.municipalityCode should be(municipalityId)
    }*/
  }

  test("Test getCurrentAndComplementaryRoadLinksByMunicipality() When asking for info for a specific municipality Then return full municipality info, that includes both complementary and ordinary geometries") {
    val municipalityId = 235
    val linkId = Seq(1l, 2l).map(_.toString)
    val roadLinks = linkId.map(id =>
      RoadLink(id, Seq(), 0, AdministrativeClass.Municipality, TrafficDirection.TowardsDigitizing, None,None, municipalityCode = municipalityId, sourceId = "RoadLink")
    )
    val linkIdComp = Seq(3l, 4l).map(_.toString)
    val roadLinksComp = linkIdComp.map(id =>
      RoadLink(id, Seq(), 0, AdministrativeClass.Municipality, TrafficDirection.TowardsDigitizing, None,None, municipalityCode = municipalityId, sourceId = "Complementary")
    )

    val mockKGVClient = MockitoSugar.mock[KgvRoadLink]
    val mockKGVRoadLinkClient = MockitoSugar.mock[KgvRoadLinkClient[RoadLink]]
    val mockKGVComplementaryClient = MockitoSugar.mock[ComplementaryLinkDAO]
    val service = new TestService(mockKGVClient)

    PostGISDatabase.withDynTransaction {
      when(mockKGVClient.complementaryData).thenReturn(mockKGVComplementaryClient)
      when(mockKGVClient.roadLinkData).thenReturn(mockKGVRoadLinkClient)
      when(mockKGVComplementaryClient.fetchByMunicipalityAndRoadNumbersF(any[Int], any[Seq[(Int,Int)]])).thenReturn(Future(roadLinksComp))
      when(mockKGVRoadLinkClient.fetchByMunicipalityAndRoadNumbersF(any[Int], any[Seq[(Int,Int)]])).thenReturn(Future(roadLinks))

      val roadLinksList = service.getCurrentAndComplementaryRoadLinksByMunicipality(235, Seq())

      roadLinksList should have length 4
      roadLinksList.groupBy(_.sourceId) should have size 2
    }
  }

  ignore("Test getRoadLinksHistoryFromVVH() When supplying a single linkId to query Then return the VVHHistoryRoadLink for the queried linkId "){
//    val municipalityId = 235
//    val linkId = 1234.toString
//    val firstRoadLink = new HistoryRoadLink(linkId, municipalityId, Seq.empty, AdministrativeClass.Municipality, TrafficDirection.TowardsDigitizing, FeatureClass.AllOthers, 0, 100, attributes = Map("MUNICIPALITYCODE" -> BigInt(235), "SUBTYPE" -> BigInt(3)))
//    val secondRoadLink = new HistoryRoadLink(linkId, municipalityId, Seq.empty, AdministrativeClass.Municipality, TrafficDirection.TowardsDigitizing, FeatureClass.AllOthers, 0, 1000, attributes = Map("MUNICIPALITYCODE" -> BigInt(235), "SUBTYPE" -> BigInt(3)))
//
//    val mockVVHClient = MockitoSugar.mock[KgvRoadLink]
//    val mockVVHHistoryClient = MockitoSugar.mock[HistoryClient]
//    val service = new TestService(mockVVHClient)
//
//    PostGISDatabase.withDynTransaction {
//      when(mockVVHClient.historyData).thenReturn(mockVVHHistoryClient)
//      when(mockVVHHistoryClient.fetchVVHRoadLinkByLinkIdsF(any[Set[Long]])).thenReturn(Future(Seq(firstRoadLink, secondRoadLink)))
//      val serviceResult = service.getRoadLinksHistoryFromVVH(Set[String](linkId))
//      serviceResult.length should be (1)
//      serviceResult.head.linkId should be (linkId)
//      serviceResult.head.endDate should be (1000)
//    }
  }

  ignore("Test getRoadLinksAndComplementaryFromVVH() When submitting a specific bounding box and a municipality Then return regular roadlinks and complementary roadlinks that are in the bounding box and belong to said municipality.") {

//    val boundingBox = BoundingRectangle(Point(123, 345), Point(567, 678))
//    val mockVVHClient = MockitoSugar.mock[KgvRoadLink]
//    val mockVVHRoadLinkClient = MockitoSugar.mock[KgvRoadLinkClient]
//    val mockVVHChangeInfoClient = MockitoSugar.mock[VVHChangeInfoClient]
//    val mockVVHComplementaryClient = MockitoSugar.mock[VVHComplementaryClient]
//    val service = new TestService(mockVVHClient)
//
//    val complRoadLink1 = RoadLinkFetched(1.toString, 91, Nil, AdministrativeClass.Municipality, TrafficDirection.TowardsDigitizing, FeatureClass.AllOthers, constructionType = ConstructionType.InUse)
//    val complRoadLink2 = RoadLinkFetched(2.toString, 91, Nil, AdministrativeClass.Municipality, TrafficDirection.TowardsDigitizing, FeatureClass.AllOthers, constructionType = ConstructionType.UnderConstruction)
//    val complRoadLink3 = RoadLinkFetched(3.toString, 91, Nil, AdministrativeClass.Municipality, TrafficDirection.TowardsDigitizing, FeatureClass.AllOthers, constructionType = ConstructionType.Planned)
//    val complRoadLink4 = RoadLinkFetched(4.toString, 91, Nil, AdministrativeClass.Municipality, TrafficDirection.TowardsDigitizing, FeatureClass.AllOthers, constructionType = ConstructionType.InUse)
//
//    val vvhRoadLink1 = RoadLinkFetched(5.toString, 91, Nil, AdministrativeClass.Municipality, TrafficDirection.TowardsDigitizing, FeatureClass.AllOthers, constructionType = ConstructionType.InUse)
//    val vvhRoadLink2 = RoadLinkFetched(6.toString, 91, Nil, AdministrativeClass.Municipality, TrafficDirection.TowardsDigitizing, FeatureClass.AllOthers, constructionType = ConstructionType.UnderConstruction)
//    val vvhRoadLink3 = RoadLinkFetched(7.toString, 91, Nil, AdministrativeClass.Municipality, TrafficDirection.TowardsDigitizing, FeatureClass.AllOthers, constructionType = ConstructionType.Planned)
//    val vvhRoadLink4 = RoadLinkFetched(8.toString, 91, Nil, AdministrativeClass.Municipality, TrafficDirection.TowardsDigitizing, FeatureClass.AllOthers, constructionType = ConstructionType.InUse)
//
//    PostGISDatabase.withDynTransaction {
//      when(mockVVHClient.roadLinkData).thenReturn(mockVVHRoadLinkClient)
//      when(mockVVHClient.roadLinkChangeInfo).thenReturn(mockVVHChangeInfoClient)
//      when(mockVVHClient.complementaryData).thenReturn(mockVVHComplementaryClient)
//      when(mockVVHComplementaryClient.fetchWalkwaysByBoundsAndMunicipalitiesF(any[BoundingRectangle], any[Set[Int]])).thenReturn(Future(Seq(complRoadLink1, complRoadLink2, complRoadLink3, complRoadLink4)))
//      when(mockVVHChangeInfoClient.fetchByBoundsAndMunicipalitiesF(any[BoundingRectangle], any[Set[Int]])).thenReturn(Future(Seq()))
//      when(mockVVHRoadLinkClient.fetchByBoundsAndMunicipalitiesF(any[BoundingRectangle], any[Set[Int]])).thenReturn(Future(Seq(vvhRoadLink1, vvhRoadLink2, vvhRoadLink3, vvhRoadLink4)))
//
//      val roadlinks = service.getRoadLinksAndComplementaryFromVVH(boundingBox, Set(91))
//
//      roadlinks.length should be(8)
//      roadlinks.map(r => r.linkId).sorted should be (Seq(1,2,3,4,5,6,7,8))
//
//    }
  }

  ignore("Test getChangeInfoFromVVHF() When defining a bounding box as a search area Then return the change infos for the links in the search area") {
//    val oldLinkId = 1l.toString
//    val newLinkId = 2l.toString
//    val changeInfo = ChangeInfo(Some(oldLinkId), Some(newLinkId), 123l, ChangeType.DividedModifiedPart, Some(0), Some(1), Some(0), Some(1), 144000000)
//    val boundingBox = BoundingRectangle(Point(123, 345), Point(567, 678))
//    val mockVVHClient = MockitoSugar.mock[KgvRoadLink]
//    val mockVVHRoadLinkClient = MockitoSugar.mock[KgvRoadLinkClient]
//    val mockVVHChangeInfoClient = MockitoSugar.mock[VVHChangeInfoClient]
//    val service = new TestService(mockVVHClient)
//    PostGISDatabase.withDynTransaction {
//      when(mockVVHClient.roadLinkData).thenReturn(mockVVHRoadLinkClient)
//      when(mockVVHClient.roadLinkChangeInfo).thenReturn(mockVVHChangeInfoClient)
//      when(mockVVHChangeInfoClient.fetchByBoundsAndMunicipalitiesF(boundingBox, Set())).thenReturn(Promise.successful(Seq(changeInfo)).future)
//      val returnedChangeInfo = Await.result(service.getChangeInfoFromVVHF(boundingBox, Set()), atMost = Duration.Inf)
//
//      returnedChangeInfo.size should be (1)
//      returnedChangeInfo.head.oldId.get should be(oldLinkId)
//      returnedChangeInfo.head.newId.get should be(newLinkId)
//      returnedChangeInfo.head.mmlId should be(123l)
//    }
  }
}

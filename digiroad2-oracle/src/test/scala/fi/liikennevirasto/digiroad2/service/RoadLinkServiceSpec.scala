package fi.liikennevirasto.digiroad2.service

import fi.liikennevirasto.digiroad2.asset._
import fi.liikennevirasto.digiroad2.client.kmtk._
import fi.liikennevirasto.digiroad2.client.vvh.FeatureClass.AllOthers
import fi.liikennevirasto.digiroad2.client.vvh._
import fi.liikennevirasto.digiroad2.linearasset.{KMTKID, RoadLink}
import fi.liikennevirasto.digiroad2.oracle.OracleDatabase
import fi.liikennevirasto.digiroad2.util.RoadLinkSerializer
import fi.liikennevirasto.digiroad2.{DigiroadEventBus, DummyEventBus, DummySerializer, Point}
import org.mockito.Mockito._
import org.mockito.ArgumentMatchers._
import org.joda.time.DateTime
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{BeforeAndAfter, FunSuite, Matchers}
import slick.driver.JdbcDriver.backend.Database.dynamicSession
import slick.jdbc.StaticQuery.interpolation

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future, Promise}

class RoadLinkServiceSpec extends FunSuite with Matchers with BeforeAndAfter {

  class TestService(vvhClient: VVHClient, kmtkClient: KMTKClient, eventBus: DigiroadEventBus = new DummyEventBus, vvhSerializer: RoadLinkSerializer = new DummySerializer) extends RoadLinkService(vvhClient, kmtkClient, eventBus, vvhSerializer) {
    override def withDynTransaction[T](f: => T): T = f
    override def withDynSession[T](f: => T): T = f
  }

  class RoadLinkTestService(vvhClient: VVHClient, kmtkClient: KMTKClient, eventBus: DigiroadEventBus = new DummyEventBus, vvhSerializer: RoadLinkSerializer = new DummySerializer) extends RoadLinkService(vvhClient, kmtkClient, eventBus, vvhSerializer) {
    override def withDynTransaction[T](f: => T): T = f
    override def withDynSession[T](f: => T): T = f
  }


  private def simulateQuery[T](f: => T): T = {
    val result = f
    sqlu"""delete from temp_id""".execute
    result
  }

  test("Test getRoadLinksByMunicipality When supplying a specific municipality Id Then return the correct return of a ViiteRoadLink of that Municipality") {
    val municipalityId = 235
    val linkId = 2l
    val kmtkId = KMTKID("") // TODO
    val roadLink = KMTKRoadLink(linkId, kmtkId, municipalityId, Nil, Municipality, TrafficDirection.TowardsDigitizing, FeatureClass.AllOthers, attributes = Map("MUNICIPALITYCODE" -> BigInt(235)))

    val mockVVHClient = MockitoSugar.mock[VVHClient]
    val mockKMTKClient = MockitoSugar.mock[KMTKClient]
    val mockKMTKRoadLinkClient = MockitoSugar.mock[KMTKRoadLinkClient]
    val service = new TestService(mockVVHClient, mockKMTKClient)

    OracleDatabase.withDynTransaction {
      when(mockKMTKClient.roadLinkData).thenReturn(mockKMTKRoadLinkClient)
      when(mockKMTKRoadLinkClient.fetchByMunicipality(municipalityId)).thenReturn(Seq(roadLink))

      val roadLinks = service.getRoadLinksByMunicipality(municipalityId)

      roadLinks.nonEmpty should be (true)
      roadLinks.head.isInstanceOf[RoadLink] should be (true)
      roadLinks.head.linkId should be(linkId)
      roadLinks.head.municipalityCode should be (municipalityId)
    }
  }

  ignore("Test getComplementaryRoadLinksFromVVH() When submitting a bounding box as a search area Then return any complimentary geometry that are inside said area") {
    val municipalityId = 235
    val linkId = 2l
    val kmtkId = KMTKID("")
    val roadLink: VVHRoadlink = VVHRoadlink(linkId, kmtkId, municipalityId, Nil, Municipality, TrafficDirection.TowardsDigitizing, FeatureClass.AllOthers, attributes = Map("MUNICIPALITYCODE" -> BigInt(235)))
    val boundingBox = BoundingRectangle(Point(123, 345), Point(567, 678))

    val mockVVHClient = MockitoSugar.mock[VVHClient]
    val mockVVHComplementaryClient = MockitoSugar.mock[VVHComplementaryClient]
    val mockKMTKClient = MockitoSugar.mock[KMTKClient]
    val service = new TestService(mockVVHClient, mockKMTKClient)

    OracleDatabase.withDynTransaction {
      when(mockVVHClient.complementaryData).thenReturn(mockVVHComplementaryClient)
      when(mockVVHComplementaryClient.fetchByBoundsAndMunicipalitiesF(any[BoundingRectangle], any[Set[Int]])).thenReturn(Future(Seq(roadLink)))

      val roadLinksList = service.getComplementaryRoadLinksFromVVH(boundingBox, Set.empty)

      roadLinksList.length > 0 should be(true)
      roadLinksList.head.isInstanceOf[RoadLink] should be(true)
      roadLinksList.head.linkId should be(linkId)
      roadLinksList.head.municipalityCode should be(municipalityId)
    }
  }

  ignore("Test getCurrentAndComplementaryRoadLinksByMunicipality() When asking for info for a specific municipality Then return full municipality info, that includes both complementary and ordinary geometries") {
    val municipalityId = 235
    val linkId = Seq(1l, 2l)
    val kmtkId = KMTKID("") // TODO
    val roadLinks = linkId.map(id =>
      new KMTKRoadLink(id, kmtkId, municipalityId, Nil, Municipality, TrafficDirection.TowardsDigitizing, FeatureClass.AllOthers, attributes = Map("MUNICIPALITYCODE" -> BigInt(235)))
    )
    val linkIdComp = Seq(3l, 4l)
    val roadLinksComp = linkIdComp.map(id =>
      new VVHRoadlink(id, kmtkId, municipalityId, Nil, Municipality, TrafficDirection.TowardsDigitizing, FeatureClass.AllOthers, attributes = Map("MUNICIPALITYCODE" -> BigInt(235), "SUBTYPE" -> BigInt(3)))
    )

    val mockVVHClient = MockitoSugar.mock[VVHClient]
    val mockVVHComplementaryClient = MockitoSugar.mock[VVHComplementaryClient]
    val mockKMTKClient = MockitoSugar.mock[KMTKClient]
    val mockKMTKRoadLinkClient = MockitoSugar.mock[KMTKRoadLinkClient]
    val service = new TestService(mockVVHClient, mockKMTKClient)

    OracleDatabase.withDynTransaction {
      when(mockVVHClient.complementaryData).thenReturn(mockVVHComplementaryClient)
      when(mockKMTKClient.roadLinkData).thenReturn(mockKMTKRoadLinkClient)
      when(mockVVHComplementaryClient.fetchByMunicipalityAndRoadNumbersF(any[Int], any[Seq[(Int,Int)]])).thenReturn(Future(roadLinksComp))
      when(mockKMTKRoadLinkClient.fetchByMunicipalityAndRoadNumbersF(any[Int], any[Seq[(Int,Int)]])).thenReturn(Future(roadLinks))

      val roadLinksList = service.getCurrentAndComplementaryRoadLinksByMunicipality(235, Seq())

      roadLinksList should have length (4)
      roadLinksList.filter(_.attributes.contains("SUBTYPE")) should have length(2)
    }
  }

  test("Test getRoadLinksAndComplementaryByBounds() When submitting a specific bounding box and a municipality Then return regular roadlinks and complementary roadlinks that are in the bounding box and belong to said municipality.") {

    val boundingBox = BoundingRectangle(Point(123, 345), Point(567, 678))
    val mockVVHClient = MockitoSugar.mock[VVHClient]
    val mockVVHComplementaryClient = MockitoSugar.mock[VVHComplementaryClient]
    val mockKMTKClient = MockitoSugar.mock[KMTKClient]
    val mockKMTKRoadLinkClient = MockitoSugar.mock[KMTKRoadLinkClient]
    val mockKMTKChangeInfoClient = MockitoSugar.mock[KMTKChangeInfoClient]
    val service = new TestService(mockVVHClient, mockKMTKClient)

    val complRoadLink1 = VVHRoadlink(1, KMTKID("1"), 91, Nil, Municipality, TrafficDirection.TowardsDigitizing, FeatureClass.AllOthers, constructionType = ConstructionType.InUse)
    val complRoadLink2 = VVHRoadlink(2, KMTKID("2"), 91, Nil, Municipality, TrafficDirection.TowardsDigitizing, FeatureClass.AllOthers, constructionType = ConstructionType.UnderConstruction)
    val complRoadLink3 = VVHRoadlink(3, KMTKID("3"), 91, Nil, Municipality, TrafficDirection.TowardsDigitizing, FeatureClass.AllOthers, constructionType = ConstructionType.Planned)
    val complRoadLink4 = VVHRoadlink(4, KMTKID("4"), 91, Nil, Municipality, TrafficDirection.TowardsDigitizing, FeatureClass.AllOthers, constructionType = ConstructionType.InUse)

    val roadLink1 = KMTKRoadLink(5, KMTKID("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", 1), 91, Nil, Municipality, TrafficDirection.TowardsDigitizing, FeatureClass.AllOthers, constructionType = ConstructionType.InUse)
    val roadLink2 = KMTKRoadLink(6, KMTKID("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", 1), 91, Nil, Municipality, TrafficDirection.TowardsDigitizing, FeatureClass.AllOthers, constructionType = ConstructionType.UnderConstruction)
    val roadLink3 = KMTKRoadLink(7, KMTKID("cccccccccccccccccccccccccccccccc", 1), 91, Nil, Municipality, TrafficDirection.TowardsDigitizing, FeatureClass.AllOthers, constructionType = ConstructionType.Planned)
    val roadLink4 = KMTKRoadLink(8, KMTKID("dddddddddddddddddddddddddddddddd", 1), 91, Nil, Municipality, TrafficDirection.TowardsDigitizing, FeatureClass.AllOthers, constructionType = ConstructionType.InUse)

    OracleDatabase.withDynTransaction {
      when(mockKMTKClient.roadLinkData).thenReturn(mockKMTKRoadLinkClient)
      when(mockKMTKClient.roadLinkChangeInfo).thenReturn(mockKMTKChangeInfoClient)
      when(mockVVHClient.complementaryData).thenReturn(mockVVHComplementaryClient)
      when(mockVVHComplementaryClient.fetchWalkwaysByBoundsAndMunicipalities(any[BoundingRectangle], any[Set[Int]])).thenReturn(Seq(complRoadLink1, complRoadLink2, complRoadLink3, complRoadLink4))
      when(mockKMTKChangeInfoClient.fetchByBoundsAndMunicipalitiesF(any[BoundingRectangle], any[Set[Int]])).thenReturn(Future(Seq()))
      when(mockKMTKRoadLinkClient.fetchByBoundsAndMunicipalities(any[BoundingRectangle], any[Set[Int]])).thenReturn(Seq(roadLink1, roadLink2, roadLink3, roadLink4))

      val roadlinks = service.getRoadLinksAndComplementaryByBounds(boundingBox, Set(91))

      roadlinks.length should be(8)
      roadlinks.map(r => r.linkId).sorted should be (Seq(1,2,3,4,5,6,7,8))

    }
  }

  test("Test getChangeInfoFromKMTKF() When defining a bounding box as a search area Then return the change infos for the links in the search area") {
    val oldLinkId = 1l
    val newLinkId = 2l
    val changeInfo = ChangeInfo(Some(oldLinkId), Some(newLinkId), 123l, ChangeType.DividedModifiedPart, Some(0), Some(1), Some(0), Some(1), 144000000)
    val boundingBox = BoundingRectangle(Point(123, 345), Point(567, 678))
    val mockVVHClient = MockitoSugar.mock[VVHClient]
    val mockKMTKClient = MockitoSugar.mock[KMTKClient]
    val mockKMTKRoadLinkClient = MockitoSugar.mock[KMTKRoadLinkClient]
    val mockKMTKChangeInfoClient = MockitoSugar.mock[KMTKChangeInfoClient]
    val service = new TestService(mockVVHClient, mockKMTKClient)
    OracleDatabase.withDynTransaction {
      when(mockKMTKClient.roadLinkData).thenReturn(mockKMTKRoadLinkClient)
      when(mockKMTKClient.roadLinkChangeInfo).thenReturn(mockKMTKChangeInfoClient)
      when(mockKMTKChangeInfoClient.fetchByBoundsAndMunicipalitiesF(boundingBox, Set())).thenReturn(Promise.successful(Seq(changeInfo)).future)
      val returnedChangeInfo = Await.result(service.getChangeInfoF(boundingBox, Set()), atMost = Duration.Inf)

      returnedChangeInfo.size should be (1)
      returnedChangeInfo.head.oldId.get should be(oldLinkId)
      returnedChangeInfo.head.newId.get should be(newLinkId)
      returnedChangeInfo.head.mmlId should be(123l)
    }
  }
}

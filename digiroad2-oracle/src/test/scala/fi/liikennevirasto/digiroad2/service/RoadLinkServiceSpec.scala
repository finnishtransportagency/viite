package fi.liikennevirasto.digiroad2.service

import fi.liikennevirasto.digiroad2.asset.Asset._
import fi.liikennevirasto.digiroad2.asset._
import fi.liikennevirasto.digiroad2.client.vvh.FeatureClass.AllOthers
import fi.liikennevirasto.digiroad2.client.vvh._
import fi.liikennevirasto.digiroad2.linearasset.RoadLink
import fi.liikennevirasto.digiroad2.oracle.OracleDatabase
import fi.liikennevirasto.digiroad2.util.VVHSerializer
import fi.liikennevirasto.digiroad2.{DigiroadEventBus, DummyEventBus, DummySerializer, Point}
import org.joda.time.DateTime
import org.mockito.Matchers._
import org.mockito.Mockito._
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{BeforeAndAfter, FunSuite, Matchers}
import slick.driver.JdbcDriver.backend.Database.dynamicSession
import slick.jdbc.StaticQuery.interpolation
import slick.jdbc.{StaticQuery => Q}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future, Promise}

class RoadLinkServiceSpec extends FunSuite with Matchers with BeforeAndAfter {

  class TestService(vvhClient: VVHClient, eventBus: DigiroadEventBus = new DummyEventBus, vvhSerializer: VVHSerializer = new DummySerializer) extends RoadLinkService(vvhClient, eventBus, vvhSerializer) {
    override def withDynTransaction[T](f: => T): T = f
    override def withDynSession[T](f: => T): T = f
  }

  class RoadLinkTestService(vvhClient: VVHClient, eventBus: DigiroadEventBus = new DummyEventBus, vvhSerializer: VVHSerializer = new DummySerializer) extends RoadLinkService(vvhClient, eventBus, vvhSerializer) {
    override def withDynTransaction[T](f: => T): T = f
    override def withDynSession[T](f: => T): T = f
  }


  private def simulateQuery[T](f: => T): T = {
    val result = f
    sqlu"""delete from temp_id""".execute
    result
  }

  test("Provide last edited date from VVH on road link modification date if there are no overrides") {
    OracleDatabase.withDynTransaction {
      val mockVVHClient = MockitoSugar.mock[VVHClient]
      val mockVVHRoadLinkClient = MockitoSugar.mock[VVHRoadLinkClient]
      val mockVVHChangeInfoClient = MockitoSugar.mock[VVHChangeInfoClient]

      val lastEditedDate = DateTime.now()
      val roadLinks = Seq(VVHRoadlink(1l, 0, Nil, Municipality, TrafficDirection.TowardsDigitizing, AllOthers, Some(lastEditedDate)))
      when(mockVVHClient.roadLinkData).thenReturn(mockVVHRoadLinkClient)
      when(mockVVHClient.roadLinkChangeInfo).thenReturn(mockVVHChangeInfoClient)
      when(mockVVHRoadLinkClient.fetchByMunicipalitiesAndBoundsF(any[BoundingRectangle], any[Set[Int]])).thenReturn(Promise.successful(roadLinks).future)
      when(mockVVHChangeInfoClient.fetchByBoundsAndMunicipalitiesF(any[BoundingRectangle], any[Set[Int]])).thenReturn(Promise.successful(Nil).future)

      val service = new TestService(mockVVHClient)
      val results = service.getRoadLinksFromVVH(BoundingRectangle(Point(0.0, 0.0), Point(1.0, 1.0)))
      results.head.modifiedAt should be(Some(DateTimePropertyFormat.print(lastEditedDate)))
      dynamicSession.rollback()
    }
  }

  test("Check the correct return of a ViiteRoadLink By Municipality") {
    val municipalityId = 235
    val linkId = 2l
    val roadLink = VVHRoadlink(linkId, municipalityId, Nil, Municipality, TrafficDirection.TowardsDigitizing, FeatureClass.AllOthers, attributes = Map("MUNICIPALITYCODE" -> BigInt(235)))

    val mockVVHClient = MockitoSugar.mock[VVHClient]
    val mockVVHRoadLinkClient = MockitoSugar.mock[VVHRoadLinkClient]
    val service = new TestService(mockVVHClient)

    OracleDatabase.withDynTransaction {
      when(mockVVHClient.roadLinkData).thenReturn(mockVVHRoadLinkClient)
      when(mockVVHRoadLinkClient.fetchByMunicipality(municipalityId)).thenReturn(Seq(roadLink))

      val roadLinks = service.getRoadLinksFromVVHByMunicipality(municipalityId)

      roadLinks.nonEmpty should be (true)
      roadLinks.head.isInstanceOf[RoadLink] should be (true)
      roadLinks.head.linkId should be(linkId)
      roadLinks.head.municipalityCode should be (municipalityId)
    }
  }

  test("Verify if there are roadlinks from the complimentary geometry") {
    val municipalityId = 235
    val linkId = 2l
    val roadLink: VVHRoadlink = VVHRoadlink(linkId, municipalityId, Nil, Municipality, TrafficDirection.TowardsDigitizing, FeatureClass.AllOthers, attributes = Map("MUNICIPALITYCODE" -> BigInt(235)))
    val boundingBox = BoundingRectangle(Point(123, 345), Point(567, 678))

    val mockVVHClient = MockitoSugar.mock[VVHClient]
    val mockVVHComplementaryClient = MockitoSugar.mock[VVHComplementaryClient]
    val service = new TestService(mockVVHClient)

    OracleDatabase.withDynTransaction {
      when(mockVVHClient.complementaryData).thenReturn(mockVVHComplementaryClient)
      when(mockVVHComplementaryClient.fetchByMunicipalitiesAndBoundsF(any[BoundingRectangle], any[Set[Int]])).thenReturn(Future(Seq(roadLink)))

      val roadLinksList = service.getComplementaryRoadLinksFromVVH(boundingBox, Set.empty)

      roadLinksList.length > 0 should be(true)
      roadLinksList.head.isInstanceOf[RoadLink] should be(true)
      roadLinksList.head.linkId should be(linkId)
      roadLinksList.head.municipalityCode should be(municipalityId)
    }
  }

  test("Full municipality request includes both complementary and ordinary geometries") {
    val municipalityId = 235
    val linkId = Seq(1l, 2l)
    val roadLinks = linkId.map(id =>
      new VVHRoadlink(id, municipalityId, Nil, Municipality, TrafficDirection.TowardsDigitizing, FeatureClass.AllOthers, attributes = Map("MUNICIPALITYCODE" -> BigInt(235)))
    )
    val linkIdComp = Seq(3l, 4l)
    val roadLinksComp = linkIdComp.map(id =>
      new VVHRoadlink(id, municipalityId, Nil, Municipality, TrafficDirection.TowardsDigitizing, FeatureClass.AllOthers, attributes = Map("MUNICIPALITYCODE" -> BigInt(235), "SUBTYPE" -> BigInt(3)))
    )

    val mockVVHClient = MockitoSugar.mock[VVHClient]
    val mockVVHRoadLinkClient = MockitoSugar.mock[VVHRoadLinkClient]
    val mockVVHComplementaryClient = MockitoSugar.mock[VVHComplementaryClient]
    val service = new TestService(mockVVHClient)

    OracleDatabase.withDynTransaction {
      when(mockVVHClient.complementaryData).thenReturn(mockVVHComplementaryClient)
      when(mockVVHClient.roadLinkData).thenReturn(mockVVHRoadLinkClient)
      when(mockVVHComplementaryClient.fetchByMunicipalityAndRoadNumbersF(any[Int], any[Seq[(Int,Int)]])).thenReturn(Future(roadLinksComp))
      when(mockVVHRoadLinkClient.fetchByMunicipalityAndRoadNumbersF(any[Int], any[Seq[(Int,Int)]])).thenReturn(Future(roadLinks))

      val roadLinksList = service.getCurrentAndComplementaryRoadLinksFromVVH(235, Seq())

      roadLinksList should have length (4)
      roadLinksList.filter(_.attributes.contains("SUBTYPE")) should have length(2)
    }
  }

  test("Verify the returning of the correct VVHHistoryRoadLink"){
    val municipalityId = 235
    val linkId = 1234
    val firstRoadLink = new VVHHistoryRoadLink(linkId, municipalityId, Seq.empty, Municipality, TrafficDirection.TowardsDigitizing, FeatureClass.AllOthers, 0, 100, attributes = Map("MUNICIPALITYCODE" -> BigInt(235), "SUBTYPE" -> BigInt(3)))
    val secondRoadLink = new VVHHistoryRoadLink(linkId, municipalityId, Seq.empty, Municipality, TrafficDirection.TowardsDigitizing, FeatureClass.AllOthers, 0, 1000, attributes = Map("MUNICIPALITYCODE" -> BigInt(235), "SUBTYPE" -> BigInt(3)))

    val mockVVHClient = MockitoSugar.mock[VVHClient]
    val mockVVHHistoryClient = MockitoSugar.mock[VVHHistoryClient]
    val service = new TestService(mockVVHClient)

    OracleDatabase.withDynTransaction {
      when(mockVVHClient.historyData).thenReturn(mockVVHHistoryClient)
      when(mockVVHHistoryClient.fetchVVHRoadLinkByLinkIdsF(any[Set[Long]])).thenReturn(Future(Seq(firstRoadLink, secondRoadLink)))
      val serviceResult = service.getRoadLinksHistoryFromVVH(Set[Long](linkId))
      serviceResult.length should be (1)
      serviceResult.head.linkId should be (linkId)
      serviceResult.head.endDate should be (1000)
    }
  }

  test("Should return roadlinks and complementary roadlinks") {

    val boundingBox = BoundingRectangle(Point(123, 345), Point(567, 678))
    val mockVVHClient = MockitoSugar.mock[VVHClient]
    val mockVVHRoadLinkClient = MockitoSugar.mock[VVHRoadLinkClient]
    val mockVVHChangeInfoClient = MockitoSugar.mock[VVHChangeInfoClient]
    val mockVVHComplementaryClient = MockitoSugar.mock[VVHComplementaryClient]
    val service = new TestService(mockVVHClient)

    val complRoadLink1 = VVHRoadlink(1, 91, Nil, Municipality, TrafficDirection.TowardsDigitizing, FeatureClass.AllOthers, constructionType = ConstructionType.InUse)
    val complRoadLink2 = VVHRoadlink(2, 91, Nil, Municipality, TrafficDirection.TowardsDigitizing, FeatureClass.AllOthers, constructionType = ConstructionType.UnderConstruction)
    val complRoadLink3 = VVHRoadlink(3, 91, Nil, Municipality, TrafficDirection.TowardsDigitizing, FeatureClass.AllOthers, constructionType = ConstructionType.Planned)
    val complRoadLink4 = VVHRoadlink(4, 91, Nil, Municipality, TrafficDirection.TowardsDigitizing, FeatureClass.AllOthers, constructionType = ConstructionType.InUse)

    val vvhRoadLink1 = VVHRoadlink(5, 91, Nil, Municipality, TrafficDirection.TowardsDigitizing, FeatureClass.AllOthers, constructionType = ConstructionType.InUse)
    val vvhRoadLink2 = VVHRoadlink(6, 91, Nil, Municipality, TrafficDirection.TowardsDigitizing, FeatureClass.AllOthers, constructionType = ConstructionType.UnderConstruction)
    val vvhRoadLink3 = VVHRoadlink(7, 91, Nil, Municipality, TrafficDirection.TowardsDigitizing, FeatureClass.AllOthers, constructionType = ConstructionType.Planned)
    val vvhRoadLink4 = VVHRoadlink(8, 91, Nil, Municipality, TrafficDirection.TowardsDigitizing, FeatureClass.AllOthers, constructionType = ConstructionType.InUse)

    OracleDatabase.withDynTransaction {
      when(mockVVHClient.roadLinkData).thenReturn(mockVVHRoadLinkClient)
      when(mockVVHClient.roadLinkChangeInfo).thenReturn(mockVVHChangeInfoClient)
      when(mockVVHClient.complementaryData).thenReturn(mockVVHComplementaryClient)
      when(mockVVHComplementaryClient.fetchWalkwaysByBoundsAndMunicipalitiesF(any[BoundingRectangle], any[Set[Int]])).thenReturn(Future(Seq(complRoadLink1, complRoadLink2, complRoadLink3, complRoadLink4)))
      when(mockVVHChangeInfoClient.fetchByBoundsAndMunicipalitiesF(any[BoundingRectangle], any[Set[Int]])).thenReturn(Future(Seq()))
      when(mockVVHRoadLinkClient.fetchByMunicipalitiesAndBoundsF(any[BoundingRectangle], any[Set[Int]])).thenReturn(Future(Seq(vvhRoadLink1, vvhRoadLink2, vvhRoadLink3, vvhRoadLink4)))

      val roadlinks = service.getRoadLinksAndComplementaryFromVVH(boundingBox, Set(91))

      roadlinks.length should be(8)
      roadlinks.map(r => r.linkId).sorted should be (Seq(1,2,3,4,5,6,7,8))

    }
  }

  test("verify the output of change info") {
    val oldLinkId = 1l
    val newLinkId = 2l
    val changeInfo = ChangeInfo(Some(oldLinkId), Some(newLinkId), 123l, ChangeType.DividedModifiedPart, Some(0), Some(1), Some(0), Some(1), 144000000)
    val boundingBox = BoundingRectangle(Point(123, 345), Point(567, 678))
    val mockVVHClient = MockitoSugar.mock[VVHClient]
    val mockVVHRoadLinkClient = MockitoSugar.mock[VVHRoadLinkClient]
    val mockVVHChangeInfoClient = MockitoSugar.mock[VVHChangeInfoClient]
    val service = new TestService(mockVVHClient)
    OracleDatabase.withDynTransaction {
      when(mockVVHClient.roadLinkData).thenReturn(mockVVHRoadLinkClient)
      when(mockVVHClient.roadLinkChangeInfo).thenReturn(mockVVHChangeInfoClient)
      when(mockVVHChangeInfoClient.fetchByBoundsAndMunicipalitiesF(boundingBox, Set())).thenReturn(Promise.successful(Seq(changeInfo)).future)
      val returnedChangeInfo = Await.result(service.getChangeInfoFromVVHF(boundingBox, Set()), atMost = Duration.Inf)

      returnedChangeInfo.size should be (1)
      returnedChangeInfo.head.oldId.get should be(oldLinkId)
      returnedChangeInfo.head.newId.get should be(newLinkId)
      returnedChangeInfo.head.mmlId should be(123l)
    }
  }
}

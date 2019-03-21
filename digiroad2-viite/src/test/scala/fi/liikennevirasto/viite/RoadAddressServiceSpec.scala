package fi.liikennevirasto.viite

import java.util.{Date, Properties}

import fi.liikennevirasto.digiroad2._
import fi.liikennevirasto.digiroad2.asset.ConstructionType.{InUse, UnknownConstructionType}
import fi.liikennevirasto.digiroad2.asset.LinkGeomSource.{HistoryLinkInterface, NormalLinkInterface, SuravageLinkInterface}
import fi.liikennevirasto.digiroad2.asset.SideCode.{AgainstDigitizing, TowardsDigitizing}
import fi.liikennevirasto.digiroad2.asset.TrafficDirection.BothDirections
import fi.liikennevirasto.digiroad2.asset._
import fi.liikennevirasto.digiroad2.client.vvh.ChangeType._
import fi.liikennevirasto.digiroad2.client.vvh.FeatureClass.AllOthers
import fi.liikennevirasto.digiroad2.client.vvh._
import fi.liikennevirasto.digiroad2.dao.Sequences
import fi.liikennevirasto.digiroad2.linearasset.RoadLink
import fi.liikennevirasto.digiroad2.oracle.OracleDatabase
import fi.liikennevirasto.digiroad2.service.RoadLinkService
import fi.liikennevirasto.digiroad2.user.{Configuration, User}
import fi.liikennevirasto.digiroad2.util.Track
import fi.liikennevirasto.viite.RoadType._
import fi.liikennevirasto.viite.dao.Discontinuity._
import fi.liikennevirasto.viite.dao.TerminationCode._
import fi.liikennevirasto.viite.dao._
import fi.liikennevirasto.viite.dao.FloatingReason.NoFloating
import fi.liikennevirasto.viite.model.Anomaly.NoAddressGiven
import fi.liikennevirasto.viite.model.{Anomaly, RoadAddressLink, RoadAddressLinkPartitioner}
import fi.liikennevirasto.viite.process.RoadAddressFiller.{ChangeSet, LinearLocationAdjustment}
import fi.liikennevirasto.viite.process.{DefloatMapper, LinkRoadAddressCalculator, RoadAddressFiller, RoadwayAddressMapper}
import fi.liikennevirasto.viite.util.StaticTestData
import org.joda.time.DateTime
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito._
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{FunSuite, Matchers}
import slick.driver.JdbcDriver.backend.Database
import slick.driver.JdbcDriver.backend.Database.dynamicSession
import slick.jdbc.StaticQuery
import slick.jdbc.StaticQuery.interpolation
import fi.liikennevirasto.viite.Dummies._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class RoadAddressServiceSpec extends FunSuite with Matchers{
  val mockRoadLinkService = MockitoSugar.mock[RoadLinkService]
  val mockEventBus = MockitoSugar.mock[DigiroadEventBus]
  val mockRoadwayAddressMapper = MockitoSugar.mock[RoadwayAddressMapper]
  val mockLinearLocationDAO = MockitoSugar.mock[LinearLocationDAO]
  val mockRoadwayDAO = MockitoSugar.mock[RoadwayDAO]
  val mockRoadNetworkDAO = MockitoSugar.mock[RoadNetworkDAO]
  val mockUnaddressedRoadLinkDAO = MockitoSugar.mock[UnaddressedRoadLinkDAO]

  val roadwayAddressMappper = new RoadwayAddressMapper(mockRoadwayDAO, mockLinearLocationDAO)

  val roadAddressService = new RoadAddressService(mockRoadLinkService, mockRoadwayDAO, mockLinearLocationDAO, mockRoadNetworkDAO, mockUnaddressedRoadLinkDAO, roadwayAddressMappper, mockEventBus) {
    override def withDynSession[T](f: => T): T = f
    override def withDynTransaction[T](f: => T): T = f
  }

    def runWithRollback[T](f: => T): T = {
      Database.forDataSource(OracleDatabase.ds).withDynTransaction {
        val t = f
        dynamicSession.rollback()
        t
      }
    }



  test("Test getRoadAddressLinksByLinkId When called by any bounding box and any road number limits Then should return road addresses on normal and history road links") {
    val linearLocations = Seq(
      dummyLinearLocation(roadwayNumber = 1L, orderNumber = 1L, linkId = 123L, startMValue = 0.0, endMValue = 10.0),
      dummyLinearLocation(roadwayNumber = 1L, orderNumber = 2L, linkId = 123L, startMValue = 10.0, endMValue = 20.0, FloatingReason.ManualFloating),
      dummyLinearLocation(roadwayNumber = 1L, orderNumber = 3L, linkId = 124L, startMValue = 0.0, endMValue = 10.0),
      dummyLinearLocation(roadwayNumber = 1L, orderNumber = 4L, linkId = 125L, startMValue = 0.0, endMValue = 10.0, FloatingReason.ManualFloating)
    )

    val vvhHistoryRoadLinks = Seq(
      dummyVvhHistoryRoadLink(linkId = 123L, Seq(0.0, 10.0, 20.0)),
      dummyVvhHistoryRoadLink(linkId = 125L, Seq(0.0, 10.0))
    )

    val roadways = Seq(
      dummyRoadway(roadwayNumber = 1L, roadNumber = 1L, roadPartNumber = 1L, startAddrM = 0L, endAddrM = 400L, DateTime.now(), None)
    )

    val roadLinks = Seq(
      dummyRoadLink(linkId = 123L, Seq(0.0, 10.0, 20.0), NormalLinkInterface),
      dummyRoadLink(linkId = 124L, Seq(0.0, 10.0), NormalLinkInterface)
    )

    when(mockLinearLocationDAO.fetchRoadwayByBoundingBox(any[BoundingRectangle], any[Seq[(Int,Int)]])).thenReturn(linearLocations)

    when(mockRoadwayDAO.fetchAllByRoadwayNumbers(any[Set[Long]], any[Boolean])).thenReturn(roadways)

    //Road Link service mocks
    when(mockRoadLinkService.getChangeInfoFromVVHF(any[Set[Long]])).thenReturn(Future(Seq.empty))
    when(mockRoadLinkService.getRoadLinksHistoryFromVVH(any[Set[Long]])).thenReturn(vvhHistoryRoadLinks)
    when(mockRoadLinkService.getRoadLinksByLinkIdsFromVVH(any[Set[Long]], any[Boolean])).thenReturn(roadLinks)

    val result = roadAddressService.getRoadAddressLinksByBoundingBox(BoundingRectangle(Point(0.0, 0.0), Point(0.0, 20.0)), Seq())

    verify(mockRoadLinkService, times(1)).getChangeInfoFromVVHF(Set(123L, 124L))
    verify(mockRoadLinkService, times(1)).getRoadLinksHistoryFromVVH(Set(123L, 125L))
    verify(mockRoadLinkService, times(1)).getRoadLinksByLinkIdsFromVVH(Set(123L, 124L), false)

    result.size should be (4)
  }

  test("Test getRoadAddressLinksByLinkId When called by any bounding box and any road number limits Then should not filter out floatings") {
    val linearLocations = Seq(
      dummyLinearLocation(roadwayNumber = 1L, orderNumber = 1L, linkId = 123L, startMValue = 0.0, endMValue = 10.0),
      dummyLinearLocation(roadwayNumber = 1L, orderNumber = 2L, linkId = 123L, startMValue = 10.0, endMValue = 20.0, FloatingReason.ManualFloating),
      dummyLinearLocation(roadwayNumber = 1L, orderNumber = 3L, linkId = 124L, startMValue = 0.0, endMValue = 10.0),
      dummyLinearLocation(roadwayNumber = 1L, orderNumber = 4L, linkId = 125L, startMValue = 0.0, endMValue = 10.0, FloatingReason.ManualFloating)
    )

    val roadways = Seq(
      dummyRoadway(roadwayNumber = 1L, roadNumber = 1L, roadPartNumber = 1L, startAddrM = 0L, endAddrM = 400L, DateTime.now(), None)
    )

    val vvhHistoryRoadLinks = Seq(
      dummyVvhHistoryRoadLink(linkId = 123L, Seq(0.0, 10.0, 20.0)),
      dummyVvhHistoryRoadLink(linkId = 125L, Seq(0.0, 10.0))
    )

    val roadLinks = Seq(
      dummyRoadLink(linkId = 123L, Seq(0.0, 10.0, 20.0), NormalLinkInterface),
      dummyRoadLink(linkId = 124L, Seq(0.0, 10.0), NormalLinkInterface)
    )

    when(mockLinearLocationDAO.fetchRoadwayByBoundingBox(any[BoundingRectangle], any[Seq[(Int,Int)]])).thenReturn(linearLocations)

    when(mockRoadwayDAO.fetchAllByRoadwayNumbers(any[Set[Long]], any[Boolean])).thenReturn(roadways)

    //Road Link service mocks
    when(mockRoadLinkService.getChangeInfoFromVVHF(any[Set[Long]])).thenReturn(Future(Seq.empty))
    when(mockRoadLinkService.getRoadLinksHistoryFromVVH(any[Set[Long]])).thenReturn(vvhHistoryRoadLinks)
    when(mockRoadLinkService.getRoadLinksByLinkIdsFromVVH(any[Set[Long]], any[Boolean])).thenReturn(roadLinks)

    val (floating, nonFloating) = roadAddressService.getRoadAddressLinksByBoundingBox(BoundingRectangle(Point(0.0, 0.0), Point(0.0, 20.0)), Seq()).partition(_.floating)

    floating.size should be (2)
    nonFloating.size should be (2)

    floating.map(_.linkId) should contain allOf (123L,125L)
    nonFloating.map(_.linkId) should contain allOf (123L,124L)
  }

  test("Test getRoadAddressesWithLinearGeometry When municipality has road addresses on top of suravage and complementary road links Then should not return floatings") {
    val linearLocations = Seq(
      dummyLinearLocation(roadwayNumber = 1L, orderNumber = 1L, linkId = 123L, startMValue = 0.0, endMValue = 10.0),
      dummyLinearLocation(roadwayNumber = 1L, orderNumber = 2L, linkId = 123L, startMValue = 10.0, endMValue = 20.0, FloatingReason.ManualFloating),
      dummyLinearLocation(roadwayNumber = 1L, orderNumber = 3L, linkId = 124L, startMValue = 0.0, endMValue = 10.0),
      dummyLinearLocation(roadwayNumber = 1L, orderNumber = 4L, linkId = 125L, startMValue = 0.0, endMValue = 10.0, FloatingReason.ManualFloating)
    )

    val roadways = Seq(
      dummyRoadway(roadwayNumber = 1L, roadNumber = 1L, roadPartNumber = 1L, startAddrM = 0L, endAddrM = 400L, DateTime.now(), None)
    )

    when(mockLinearLocationDAO.fetchRoadwayByBoundingBox(any[BoundingRectangle], any[Seq[(Int,Int)]])).thenReturn(linearLocations)

    when(mockRoadwayDAO.fetchAllByRoadwayNumbers(any[Set[Long]], any[Boolean])).thenReturn(roadways)

    val (floating, nonFloating) = roadAddressService.getRoadAddressesWithLinearGeometry(BoundingRectangle(Point(0.0, 0.0), Point(0.0, 20.0)), Seq()).partition(_.floating)

    floating.size should be (0)
    nonFloating.size should be (2)

    nonFloating.map(_.linkId) should contain allOf (123L,124L)
  }

  test("Test getAllByMunicipality When exists road network version Then returns road addresses of that version") {

    val linearLocations = List(
      dummyLinearLocation(roadwayNumber = 1L, orderNumber = 1L, linkId = 123L, startMValue = 0.0, endMValue = 10.0),
      dummyLinearLocation(roadwayNumber = 1L, orderNumber = 2L, linkId = 123L, startMValue = 10.0, endMValue = 20.0, FloatingReason.ManualFloating),
      dummyLinearLocation(roadwayNumber = 1L, orderNumber = 3L, linkId = 124L, startMValue = 0.0, endMValue = 10.0),
      dummyLinearLocation(roadwayNumber = 1L, orderNumber = 4L, linkId = 125L, startMValue = 0.0, endMValue = 10.0, FloatingReason.ManualFloating),
      dummyLinearLocation(roadwayNumber = 1L, orderNumber = 5L, linkId = 126L, startMValue = 0.0, endMValue = 10.0)
    )

    val roadways = Seq(
      dummyRoadway(roadwayNumber = 1L, roadNumber = 1L, roadPartNumber = 1L, startAddrM = 0L, endAddrM = 400L, DateTime.now(), None)
    )

    val roadLinks = Seq(
      dummyRoadLink(linkId = 123L, Seq(0.0, 10.0, 20.0), NormalLinkInterface),
      dummyRoadLink(linkId = 124L, Seq(0.0, 10.0), NormalLinkInterface)
    )

    val suravageRoadLinks = Seq(
      dummyRoadLink(linkId = 126L, Seq(0.0, 10.0, 20.0), SuravageLinkInterface)
    )

    when(mockLinearLocationDAO.fetchRoadwayByLinkId(any[Set[Long]])).thenReturn(linearLocations)

    when(mockRoadwayDAO.fetchAllByRoadwayNumbers(any[Set[Long]], any[Int])).thenReturn(roadways)
    when(mockRoadNetworkDAO.getLatestRoadNetworkVersionId).thenReturn(Some(1L))


    //Road Link service mocks
    when(mockRoadLinkService.getSuravageRoadLinks(any[Int])).thenReturn(suravageRoadLinks)
    when(mockRoadLinkService.getRoadLinksWithComplementaryAndChangesFromVVH(any[Int], any[Boolean])).thenReturn((roadLinks, Seq()))

    val (floating, nonFloating) = roadAddressService.getAllByMunicipality(municipality = 100).partition(_.floating)

    verify(mockRoadwayDAO, times(1)).fetchAllByRoadwayNumbers(Set(1L), 1)
  }

  test("Test getAllByMunicipality When does not exists road network version Then returns current road addresses") {
    implicit def dateTimeOrdering: Ordering[DateTime] = Ordering.fromLessThan(_ isBefore _)
    val linearLocations = List(
      dummyLinearLocation(roadwayNumber = 1L, orderNumber = 1L, linkId = 123L, startMValue = 0.0, endMValue = 10.0),
      dummyLinearLocation(roadwayNumber = 1L, orderNumber = 2L, linkId = 123L, startMValue = 10.0, endMValue = 20.0, FloatingReason.ManualFloating),
      dummyLinearLocation(roadwayNumber = 1L, orderNumber = 3L, linkId = 124L, startMValue = 0.0, endMValue = 10.0),
      dummyLinearLocation(roadwayNumber = 1L, orderNumber = 4L, linkId = 125L, startMValue = 0.0, endMValue = 10.0, FloatingReason.ManualFloating),
      dummyLinearLocation(roadwayNumber = 1L, orderNumber = 5L, linkId = 126L, startMValue = 0.0, endMValue = 10.0)
    )

    val roadways = Seq(
      dummyRoadway(roadwayNumber = 1L, roadNumber = 1L, roadPartNumber = 1L, startAddrM = 0L, endAddrM = 400L, DateTime.now(), None)
    )

    val roadLinks = Seq(
      dummyRoadLink(linkId = 123L, Seq(0.0, 10.0, 20.0), NormalLinkInterface),
      dummyRoadLink(linkId = 124L, Seq(0.0, 10.0), NormalLinkInterface)
    )

    val suravageRoadLinks = Seq(
      dummyRoadLink(linkId = 126L, Seq(0.0, 10.0, 20.0), SuravageLinkInterface)
    )

    when(mockLinearLocationDAO.fetchRoadwayByLinkId(any[Set[Long]])).thenReturn(linearLocations)

    when(mockRoadwayDAO.fetchAllByRoadwayNumbers(any[Set[Long]], any[DateTime])).thenReturn(roadways)
    when(mockRoadNetworkDAO.getLatestRoadNetworkVersionId).thenReturn(None)


    //Road Link service mocks
    when(mockRoadLinkService.getSuravageRoadLinks(any[Int])).thenReturn(suravageRoadLinks)
    when(mockRoadLinkService.getRoadLinksWithComplementaryAndChangesFromVVH(any[Int], any[Boolean])).thenReturn((roadLinks, Seq()))

    val now = DateTime.now
    roadAddressService.getAllByMunicipality(municipality = 100).partition(_.floating)

    val dateTimeCaptor: ArgumentCaptor[DateTime] = ArgumentCaptor.forClass(classOf[DateTime])
    val roadwayCaptor: ArgumentCaptor[Set[Long]] = ArgumentCaptor.forClass(classOf[Set[Long]])

    verify(mockRoadwayDAO, times(1)).fetchAllByRoadwayNumbers(roadwayCaptor.capture, dateTimeCaptor.capture)

    val capturedDateTime = dateTimeCaptor.getAllValues
    val capturedRoadways = roadwayCaptor.getAllValues
    val dateTimeDate = capturedDateTime.get(0)
    val roadwaysSet = capturedRoadways.get(0)

    roadwaysSet.size should be (1)
    roadwaysSet.head should be (1L)

    dateTimeDate should be >= now
  }

  test("Test getRoadAddress When the biggest address in section is greater than the parameter $addressM Then should filter out all the road addresses with start address less than $addressM") {
    val linearLocations = Seq(
      dummyLinearLocation(roadwayNumber = 1L, orderNumber = 1L, linkId = 123L, startMValue = 0.0, endMValue = 10.0),
      dummyLinearLocation(roadwayNumber = 1L, orderNumber = 2L, linkId = 123L, startMValue = 10.0, endMValue = 20.0, FloatingReason.ManualFloating),
      dummyLinearLocation(roadwayNumber = 1L, orderNumber = 3L, linkId = 124L, startMValue = 0.0, endMValue = 10.0),
      dummyLinearLocation(roadwayNumber = 1L, orderNumber = 4L, linkId = 125L, startMValue = 0.0, endMValue = 10.0, FloatingReason.ManualFloating)
    )

    val roadways = Seq(
      dummyRoadway(roadwayNumber = 1L, roadNumber = 1L, roadPartNumber = 1L, startAddrM = 0L, endAddrM = 400L, DateTime.now(), None)
    )

    when(mockRoadwayDAO.fetchAllBySectionAndAddresses(any[Long], any[Long], any[Option[Long]], any[Option[Long]])).thenReturn(roadways)
    when(mockLinearLocationDAO.fetchByRoadways(any[Set[Long]])).thenReturn(linearLocations)

    val result = roadAddressService.getRoadAddress(road = 1, roadPart = 1, addressM = 200, None)

    result.size should be (2)
    result.count(_.startAddrMValue < 200) should be (2)
  }

  test("Test getRoadAddressWithLinkIdAndMeasure When link id, start measure and end measure is given Then returns all the road address in the given link id and in between given measures") {
    val linearLocations = List(
      dummyLinearLocation(roadwayNumber = 1L, orderNumber = 1L, linkId = 123L, startMValue = 0.0, endMValue = 10.0),
      dummyLinearLocation(roadwayNumber = 1L, orderNumber = 2L, linkId = 123L, startMValue = 10.0, endMValue = 20.0, FloatingReason.ManualFloating),
      dummyLinearLocation(roadwayNumber = 1L, orderNumber = 3L, linkId = 124L, startMValue = 0.0, endMValue = 10.0),
      dummyLinearLocation(roadwayNumber = 1L, orderNumber = 4L, linkId = 125L, startMValue = 0.0, endMValue = 10.0, FloatingReason.ManualFloating)
    )

    val roadways = Seq(
      dummyRoadway(roadwayNumber = 1L, roadNumber = 1L, roadPartNumber = 1L, startAddrM = 0L, endAddrM = 400L, DateTime.now(), None)
    )

    when(mockRoadwayDAO.fetchAllByRoadwayNumbers(any[Set[Long]], any[Boolean])).thenReturn(roadways)
    when(mockLinearLocationDAO.fetchRoadwayByLinkId(any[Set[Long]])).thenReturn(linearLocations)

    val result = roadAddressService.getRoadAddressWithLinkIdAndMeasure(linkId = 123L, Some(0.0), Some(9.999))

    result.size should be (1)
    result.head.linkId should be (123L)
    result.head.startMValue should be (0.0)
    result.head.endMValue should be (10.0)
  }

  test("Test getRoadAddressWithLinkIdAndMeasure When only link id is given Then return all the road addresses in the link id") {
    val linearLocations = List(
      dummyLinearLocation(roadwayNumber = 1L, orderNumber = 1L, linkId = 123L, startMValue = 0.0, endMValue = 10.0),
      dummyLinearLocation(roadwayNumber = 1L, orderNumber = 2L, linkId = 123L, startMValue = 10.0, endMValue = 20.0, FloatingReason.ManualFloating),
      dummyLinearLocation(roadwayNumber = 1L, orderNumber = 3L, linkId = 124L, startMValue = 0.0, endMValue = 10.0),
      dummyLinearLocation(roadwayNumber = 1L, orderNumber = 4L, linkId = 125L, startMValue = 0.0, endMValue = 10.0, FloatingReason.ManualFloating)
    )

    val roadways = Seq(
      dummyRoadway(roadwayNumber = 1L, roadNumber = 1L, roadPartNumber = 1L, startAddrM = 0L, endAddrM = 400L, DateTime.now(), None)
    )

    when(mockRoadwayDAO.fetchAllByRoadwayNumbers(any[Set[Long]], any[Boolean])).thenReturn(roadways)
    when(mockLinearLocationDAO.fetchRoadwayByLinkId(any[Set[Long]])).thenReturn(linearLocations)

    val result = roadAddressService.getRoadAddressWithLinkIdAndMeasure(linkId = 123L, None, None)

    result.size should be (2)
    val linkIds = result.map(_.linkId).distinct
    linkIds.size should be (1)
    linkIds.head should be (123L)
  }

  test("Test getRoadAddressWithLinkIdAndMeasure When only link id and start measure is given Then return all road addresses in link id and with start measure greater or equal than $startM") {
    val linearLocations = List(
      dummyLinearLocation(roadwayNumber = 1L, orderNumber = 1L, linkId = 123L, startMValue = 0.0, endMValue = 10.0),
      dummyLinearLocation(roadwayNumber = 1L, orderNumber = 2L, linkId = 123L, startMValue = 10.0, endMValue = 20.0, FloatingReason.ManualFloating),
      dummyLinearLocation(roadwayNumber = 1L, orderNumber = 3L, linkId = 124L, startMValue = 0.0, endMValue = 10.0),
      dummyLinearLocation(roadwayNumber = 1L, orderNumber = 4L, linkId = 125L, startMValue = 0.0, endMValue = 10.0, FloatingReason.ManualFloating)
    )

    val roadways = Seq(
      dummyRoadway(roadwayNumber = 1L, roadNumber = 1L, roadPartNumber = 1L, startAddrM = 0L, endAddrM = 400L, DateTime.now(), None)
    )

    when(mockRoadwayDAO.fetchAllByRoadwayNumbers(any[Set[Long]], any[Boolean])).thenReturn(roadways)
    when(mockLinearLocationDAO.fetchRoadwayByLinkId(any[Set[Long]])).thenReturn(linearLocations)

    val result = roadAddressService.getRoadAddressWithLinkIdAndMeasure(linkId = 123L, Some(10.0), None)

    result.size should be (1)
    result.head.linkId should be (123L)
    result.head.startMValue should be (10.0)
    result.head.endMValue should be (20.0)
  }

  test("Test getRoadAddressWithLinkIdAndMeasure When only link id and end measure is given Then return all road addresses in link id and with end measure less or equal than $endM") {
    val linearLocations = List(
      dummyLinearLocation(roadwayNumber = 1L, orderNumber = 1L, linkId = 123L, startMValue = 0.0, endMValue = 10.0),
      dummyLinearLocation(roadwayNumber = 1L, orderNumber = 2L, linkId = 123L, startMValue = 10.0, endMValue = 20.0, FloatingReason.ManualFloating),
      dummyLinearLocation(roadwayNumber = 1L, orderNumber = 3L, linkId = 124L, startMValue = 0.0, endMValue = 10.0),
      dummyLinearLocation(roadwayNumber = 1L, orderNumber = 4L, linkId = 125L, startMValue = 0.0, endMValue = 10.0, FloatingReason.ManualFloating)
    )

    val roadways = Seq(
      dummyRoadway(roadwayNumber = 1L, roadNumber = 1L, roadPartNumber = 1L, startAddrM = 0L, endAddrM = 400L, DateTime.now(), None)
    )

    when(mockRoadwayDAO.fetchAllByRoadwayNumbers(any[Set[Long]], any[Boolean])).thenReturn(roadways)
    when(mockLinearLocationDAO.fetchRoadwayByLinkId(any[Set[Long]])).thenReturn(linearLocations)

    val result = roadAddressService.getRoadAddressWithLinkIdAndMeasure(linkId = 123L, None, Some(10.0))

    result.size should be (1)
    result.head.linkId should be (123L)
    result.head.startMValue should be (0.0)
    result.head.endMValue should be (10.0)
  }

  test("Test getRoadAddressesFiltered When roadway have less and greater addresses than given measures Then returns only road addresses in between given address measures") {
    val linearLocations = List(
      dummyLinearLocation(roadwayNumber = 1L, orderNumber = 1L, linkId = 123L, startMValue = 0.0, endMValue = 10.0),
      dummyLinearLocation(roadwayNumber = 1L, orderNumber = 2L, linkId = 123L, startMValue = 10.0, endMValue = 20.0, FloatingReason.ManualFloating),
      dummyLinearLocation(roadwayNumber = 1L, orderNumber = 3L, linkId = 124L, startMValue = 0.0, endMValue = 10.0),
      dummyLinearLocation(roadwayNumber = 1L, orderNumber = 4L, linkId = 125L, startMValue = 0.0, endMValue = 10.0, FloatingReason.ManualFloating)
    )

    val roadways = Seq(
      dummyRoadway(roadwayNumber = 1L, roadNumber = 1L, roadPartNumber = 1L, startAddrM = 0L, endAddrM = 400L, DateTime.now(), None)
    )

    when(mockRoadwayDAO.fetchAllBySection(any[Long], any[Long])).thenReturn(roadways)
    when(mockLinearLocationDAO.fetchByRoadways(any[Set[Long]])).thenReturn(linearLocations)

    val result = roadAddressService.getRoadAddressesFiltered(roadNumber = 1L, roadPartNumber = 1L)

    verify(mockRoadwayDAO, times(1)).fetchAllBySection(roadNumber = 1L, roadPartNumber = 1L)

    result.size should be (4)
  }

  test("Test getRoadAddressByLinkIds When exists floating road addresses Then floatings should be filtered out") {
    val linearLocations = List(
      dummyLinearLocation(roadwayNumber = 1L, orderNumber = 1L, linkId = 123L, startMValue = 0.0, endMValue = 10.0),
      dummyLinearLocation(roadwayNumber = 1L, orderNumber = 2L, linkId = 123L, startMValue = 10.0, endMValue = 20.0, FloatingReason.ManualFloating),
      dummyLinearLocation(roadwayNumber = 1L, orderNumber = 3L, linkId = 124L, startMValue = 0.0, endMValue = 10.0),
      dummyLinearLocation(roadwayNumber = 1L, orderNumber = 4L, linkId = 125L, startMValue = 0.0, endMValue = 10.0, FloatingReason.ManualFloating)
    )

    val roadways = Seq(
      dummyRoadway(roadwayNumber = 1L, roadNumber = 1L, roadPartNumber = 1L, startAddrM = 0L, endAddrM = 400L, DateTime.now(), None)
    )

    when(mockRoadwayDAO.fetchAllByRoadwayNumbers(any[Set[Long]], any[Boolean])).thenReturn(roadways)
    when(mockLinearLocationDAO.fetchRoadwayByLinkId(any[Set[Long]])).thenReturn(linearLocations)

    val result = roadAddressService.getRoadAddressByLinkIds(Set(123L))

    result.size should be (1)
    result.head.linkId should be (123L)
    result.head.startMValue should be (0.0)
    result.head.endMValue should be (10.0)
  }


  test("Test sortRoadWayWithNewRoads When changeSet has new links Then update the order of all the roadway") {
    val linearLocations = List(
      dummyLinearLocation(roadwayNumber = 1L, orderNumber = 1L, linkId = 123L, startMValue = 0.0, endMValue = 10.0),
      dummyLinearLocation(roadwayNumber = 1L, orderNumber = 2L, linkId = 124L, startMValue = 0.0, endMValue = 20.0),
      dummyLinearLocation(roadwayNumber = 1L, orderNumber = 3L, linkId = 125L, startMValue = 0.0, endMValue = 10.0),
      dummyLinearLocation(roadwayNumber = 1L, orderNumber = 4L, linkId = 126L, startMValue = 0.0, endMValue = 10.0)
    )
    val newLinearLocations = List(
      dummyLinearLocation(roadwayNumber = 1L, orderNumber = 2.1, linkId = 1267L, startMValue = 0.0, endMValue = 10.0),
      dummyLinearLocation(roadwayNumber = 1L, orderNumber = 3.1, linkId = 1268L, startMValue = 0.0, endMValue = 20.0)
    )

    val adjustedLinearLocations = roadAddressService.sortRoadWayWithNewRoads(linearLocations.groupBy(_.roadwayNumber), newLinearLocations)
    adjustedLinearLocations(1L).size should be (linearLocations.size + newLinearLocations.size)
    adjustedLinearLocations(1L).find(_.linkId == 123L).get.orderNumber should be (1)
    adjustedLinearLocations(1L).find(_.linkId == 124L).get.orderNumber should be (2)
    adjustedLinearLocations(1L).find(_.linkId == 1267L).get.orderNumber should be (3)
    adjustedLinearLocations(1L).find(_.linkId == 125L).get.orderNumber should be (4)
    adjustedLinearLocations(1L).find(_.linkId == 1268L).get.orderNumber should be (5)
    adjustedLinearLocations(1L).find(_.linkId == 126L).get.orderNumber should be (6)
  }

  test("Test sortRoadWayWithNewRoads When there are no linear locations to create Then should return empty list") {
    val linearLocations = List(
      dummyLinearLocation(roadwayNumber = 1L, orderNumber = 1L, linkId = 123L, startMValue = 0.0, endMValue = 10.0),
      dummyLinearLocation(roadwayNumber = 1L, orderNumber = 2L, linkId = 124L, startMValue = 0.0, endMValue = 20.0),
      dummyLinearLocation(roadwayNumber = 1L, orderNumber = 3L, linkId = 125L, startMValue = 0.0, endMValue = 10.0),
      dummyLinearLocation(roadwayNumber = 1L, orderNumber = 4L, linkId = 126L, startMValue = 0.0, endMValue = 10.0)
    )
    val newLinearLocations = List()
    val adjustedLinearLocations = roadAddressService.sortRoadWayWithNewRoads(linearLocations.groupBy(_.roadwayNumber), newLinearLocations)
    adjustedLinearLocations.size should be (0)
  }

//  TODO VIITE-1550
//  test("Linear location modifications are published") {
//    val localMockRoadLinkService = MockitoSugar.mock[RoadLinkService]
//    val localMockEventBus = MockitoSugar.mock[DigiroadEventBus]
//    val mockRoadwayAddressMapper = MockitoSugar.mock[RoadwayAddressMapper]
//    val localRoadAddressService = new RoadAddressService(localMockRoadLinkService, new RoadAddressDAO, mockRoadwayAddressMapper,localMockEventBus)
//    val boundingRectangle = BoundingRectangle(Point(533341.472,6988382.846), Point(533333.28,6988419.385))
//    val filter = OracleDatabase.boundingBoxFilter(boundingRectangle, "geometry")
//    runWithRollback {
//      val modificationDate = "1455274504000l"
//      val modificationUser = "testUser"
//      val query =
//        s"""select ra.LINK_ID, ra.end_measure
//        from ROADWAY ra
//        where $filter and ra.valid_to is null order by ra.id asc"""
//      val (linkId, endM) = StaticQuery.queryNA[(Long, Double)](query).firstOption.get
//      val roadLink = RoadLink(linkId, Seq(Point(0.0, 0.0), Point(endM + .5, 0.0)), endM + .5, Municipality, 1, TrafficDirection.TowardsDigitizing, Freeway, Some(modificationDate), Some(modificationUser), attributes = Map("MUNICIPALITYCODE" -> BigInt(235)))
//      when(localMockRoadLinkService.getRoadLinksFromVVH(any[BoundingRectangle], any[Seq[(Int,Int)]], any[Set[Int]], any[Boolean], any[Boolean],any[Boolean])).thenReturn(Seq(roadLink))
//      when(localMockRoadLinkService.getComplementaryRoadLinksFromVVH(any[BoundingRectangle], any[Set[Int]])).thenReturn(Seq.empty)
//      when(localMockRoadLinkService.getRoadLinksHistoryFromVVH(any[Set[Long]])).thenReturn(Seq.empty)
//      when(localMockRoadLinkService.getChangeInfoFromVVHF(any[BoundingRectangle], any[Set[Int]])).thenReturn(Future(Seq.empty))
//      when(localMockRoadLinkService.getSuravageLinksFromVVHF(any[BoundingRectangle], any[Set[Int]])).thenReturn(Future(Seq.empty))
//      val captor: ArgumentCaptor[Iterable[Any]] = ArgumentCaptor.forClass(classOf[Iterable[Any]])
//      reset(localMockEventBus)
//      val links = localRoadAddressService.getRoadAddressLinksWithSuravage(boundingRectangle, Seq())
//      links.size should be (1)
//      verify(localMockEventBus, times(3)).publish(any[String], captor.capture)
//      val capturedAdjustments = captor.getAllValues
//      val missing = capturedAdjustments.get(0)
//      val adjusting = capturedAdjustments.get(1)
//      val floating = capturedAdjustments.get(2)
//      missing.size should be (0)
//      adjusting.size should be (1)
//      floating.size should be (0)
//      adjusting.head.asInstanceOf[LinearLocationAdjustment].endMeasure should be(Some(endM + .5))
//    }
//  }

  test("Test roadAddressService.getAllByMunicipality() When asking for data for a specific municipality Then return all the Road addresses for that municipality."){
    val linearLocations = List(
      dummyLinearLocation(roadwayNumber = 1L, orderNumber = 1L, linkId = 123L, startMValue = 0.0, endMValue = 10.0),
      dummyLinearLocation(roadwayNumber = 1L, orderNumber = 2L, linkId = 123L, startMValue = 0.0, endMValue = 20.0, FloatingReason.ManualFloating),
      dummyLinearLocation(roadwayNumber = 1L, orderNumber = 3L, linkId = 124L, startMValue = 0.0, endMValue = 10.0),
      dummyLinearLocation(roadwayNumber = 1L, orderNumber = 4L, linkId = 125L, startMValue = 0.0, endMValue = 10.0, FloatingReason.ManualFloating)
    )

    val roadLinks = (Seq(
      RoadLink(123L, Seq(), 17, AdministrativeClass.apply(2), 1, TrafficDirection.TowardsDigitizing, LinkType.apply(1), None, None, Map("MUNICIPALITYCODE" -> BigInt.apply(99999), "MTKCLASS" -> BigInt.apply(12316))),
      RoadLink(123L, Seq(), 17, AdministrativeClass.apply(2), 1, TrafficDirection.TowardsDigitizing, LinkType.apply(1), None, None, Map("MUNICIPALITYCODE" -> BigInt.apply(99999), "MTKCLASS" -> BigInt.apply(12141))),
      RoadLink(124L, Seq(), 17, AdministrativeClass.apply(2), 1, TrafficDirection.TowardsDigitizing, LinkType.apply(1), None, None, Map("MUNICIPALITYCODE" -> BigInt.apply(99999), "MTKCLASS" -> BigInt.apply(12314))),
      RoadLink(125L, Seq(), 17, AdministrativeClass.apply(2), 1, TrafficDirection.TowardsDigitizing, LinkType.apply(1), None, None, Map("MUNICIPALITYCODE" -> BigInt.apply(99999), "MTKCLASS" -> BigInt.apply(12312)))
    ), Seq.empty[ChangeInfo])

    val roadways = Seq(
      dummyRoadway(roadwayNumber = 1L, roadNumber = 1L, roadPartNumber = 1L, startAddrM = 0L, endAddrM = 400L, DateTime.now(), None)
    )

    when(mockRoadwayDAO.fetchAllByRoadwayNumbers(any[Set[Long]], any[DateTime])).thenReturn(roadways)
    when(mockLinearLocationDAO.fetchRoadwayByLinkId(any[Set[Long]])).thenReturn(linearLocations)


    when(mockRoadLinkService.getRoadLinksWithComplementaryAndChangesFromVVH(99999, false)).thenReturn(roadLinks)
    when(mockRoadLinkService.getSuravageRoadLinks(99999)).thenReturn(Seq())
    when(mockRoadLinkService.getComplementaryRoadLinksFromVVH(99999)).thenReturn(Seq())
    val roadAddresses = roadAddressService.getAllByMunicipality(99999)
    roadAddresses.size should be (3)
  }

  private def getSpecificUnaddressedRoadLinks(linkId :Long): List[(Long, Long, Long, Long, Long, Int)] = {
    sql"""
          select link_id, start_addr_m, end_addr_m, road_number, road_part_number, anomaly_code
            from UNADDRESSED_ROAD_LINK where link_id = $linkId
      """.as[(Long, Long, Long, Long, Long, Int)].list
  }

  private def getFloatingCount(): Long = {
    sql"""
       select count(*)
       from ROADWAY where floating > 0
       and valid_to is null and END_DATE is null
    """.as[Long].first
  }

  //TODO this should be removed when we remove UnaddressedRoadLink logic
//  test("check UnaddressedRoadLink geometry is created correctly") {
//    runWithRollback {
//      val geom = Seq(Point(374668.195, 6676884.282, 0.0),Point(374643.384, 6676882.176, 0.0))
//      val raLink = RoadAddressLink(0, 1611616, geom, 297.7533188814259, State, SingleCarriageway, NormalRoadLinkType,
//        InUse, NormalLinkInterface, RoadType.PrivateRoadType, Some("Vt5"), None, BigInt(0), Some("22.09.2016 14:51:28"), Some("dr1_conversion"),
//                    Map("linkId" -> 1611605, "segmentId" -> 63298), 1, 3, 0, 0, 0, 0, 0, "", "", 0.0, 0.0, SideCode.Unknown,
//        None, None, Anomaly.None)
//
//      RoadAddressDAO.createUnaddressedRoadLink(
//      UnaddressedRoadLink(raLink.linkId, Some(raLink.startAddressM), Some(raLink.endAddressM), RoadType.PublicRoad,
//        Some(raLink.roadNumber), Some(raLink.roadPartNumber), None, None, Anomaly.NoAddressGiven, geom))
//
//      RoadAddressDAO.getUnaddressedRoadLinks(Set(raLink.linkId)).foreach { mra =>
//        mra.geom should be(geom)
//      }
//    }
//  }

  //TODO this should be removed when we remove floatings logic
//  test("Floating check gets floating flag updated, not geometry") {
//    val linkId = 5171359L
//    when(mockRoadLinkService.getCurrentAndComplementaryVVHRoadLinks(Set(linkId))).thenReturn(Nil)
//    runWithRollback {
//      val addressList = RoadAddressDAO.fetchByLinkId(Set(linkId))
//      addressList should have size (1)
//      val address = addressList.head
//      address.isFloating should be (false)
//      roadAddressService.checkRoadAddressFloatingWithoutTX(Set(address.id))
//      dynamicSession.rollback()
//      val addressUpdated = RoadAddressDAO.queryById(Set(address.id)).head
//      addressUpdated.geometry should be (address.geometry)
//      addressUpdated.isFloating should be (true)
//      addressUpdated.roadwayNumber should be (addressList.head.roadwayNumber)
//    }
//  }

  //TODO this should be removed when we remove floating logic
//  test("transferRoadAddress should keep calibration points") {
//    val linkId1 = 15171208
//    val linkId2 = 15171209
//    val segmentId1 = 63298
//    val segmentId2 = 63299
//    val roadwayNumber = 1015171208
//    runWithRollback {
//      val floatGeom = Seq(Point(532837.14110884, 6993543.6296834), Point(533388.14110884, 6994014.1296834))
//      val floatGeomLength = GeometryUtils.geometryLength(floatGeom)
//      val floatingLinks = Seq(
//        RoadAddressLink(linkId1, linkId1, floatGeom, floatGeomLength, Municipality, SingleCarriageway,
//          NormalRoadLinkType, InUse, HistoryLinkInterface, RoadType.MunicipalityStreetRoad, Some("Vt5"), None, BigInt(0),
//          None, None, Map("linkId" -> linkId1, "segmentId" -> segmentId1), 5, 205, 1, 0, 0, 0, 500, "01.01.2015", "", 0.0, floatGeomLength,
//          SideCode.TowardsDigitizing, Option(CalibrationPoint(linkId1, 0.0, 0)), Option(CalibrationPoint(linkId1, floatGeomLength, 500)), Anomaly.None, roadwayNumber))
//      RoadAddressDAO.create(floatingLinks.map(roadAddressLinkToRoadAddress(FloatingReason.NewAddressGiven)))
//
//      val cutPoint = GeometryUtils.calculatePointFromLinearReference(floatGeom, 230.0).get
//      val geom1 = Seq(floatGeom.head, cutPoint)
//      val geom2 = Seq(cutPoint, floatGeom.last)
//      val targetLinks = Seq(
//        RoadAddressLink(0, linkId1, geom1, GeometryUtils.geometryLength(geom1), Municipality, SingleCarriageway,
//          NormalRoadLinkType, InUse, HistoryLinkInterface, RoadType.MunicipalityStreetRoad, Some("Vt5"), None, BigInt(0),
//          None, None, Map("linkId" -> linkId1, "segmentId" -> segmentId1), 5, 205, 1, 0, 0, 0, 1, "01.01.2015", "", 0.0, GeometryUtils.geometryLength(geom1),
//          SideCode.Unknown, None, None, Anomaly.None, roadwayNumber),
//        RoadAddressLink(0, linkId2, geom2, GeometryUtils.geometryLength(geom2), Municipality, SingleCarriageway,
//          NormalRoadLinkType, InUse, HistoryLinkInterface, RoadType.MunicipalityStreetRoad, Some("Vt5"), None, BigInt(0),
//          None, None, Map("linkId" -> linkId2, "segmentId" -> segmentId2), 5, 205, 1, 0, 0, 1, 2, "01.01.2015", "", 0.0, GeometryUtils.geometryLength(geom2),
//          SideCode.Unknown, None, None, Anomaly.None, roadwayNumber))
//      when(mockRoadLinkService.getCurrentAndHistoryRoadLinksFromVVH(any[Set[Long]], any[Boolean])).thenReturn((targetLinks.map(roadAddressLinkToRoadLink), floatingLinks.map(roadAddressLinkToHistoryLink)))
//      when(mockRoadLinkService.getRoadLinksHistoryFromVVH(any[Set[Long]])).thenReturn(floatingLinks.map(roadAddressLinkToHistoryLink))
//      val newLinks = roadAddressService.transferRoadAddress(floatingLinks, targetLinks, User(1L, "foo", new Configuration()))
//      newLinks should have size (2)
//      newLinks.filter(_.linkId == linkId1).head.endCalibrationPoint should be(None)
//      newLinks.filter(_.linkId == linkId2).head.startCalibrationPoint should be(None)
//      newLinks.filter(_.linkId == linkId1).head.startCalibrationPoint.isEmpty should be(false)
//      newLinks.filter(_.linkId == linkId2).head.endCalibrationPoint.isEmpty should be(false)
//      newLinks.filter(_.linkId == linkId1).head.roadwayNumber should be(roadwayNumber)
//      newLinks.filter(_.linkId == linkId2).head.roadwayNumber should be(roadwayNumber)
//      val startCP = newLinks.filter(_.linkId == linkId1).head.startCalibrationPoint.get
//      val endCP = newLinks.filter(_.linkId == linkId2).head.endCalibrationPoint.get
//      startCP.segmentMValue should be(0.0)
//      endCP.segmentMValue should be(GeometryUtils.geometryLength(geom2) +- 0.1)
//      startCP.addressMValue should be(0L)
//      endCP.addressMValue should be(500L)
//    }
//  }

//  private def roadAddressLinkToRoadLink(roadAddressLink: RoadAddressLink) = {
//    RoadLink(roadAddressLink.linkId, roadAddressLink.geometry, GeometryUtils.geometryLength(roadAddressLink.geometry),
//      roadAddressLink.administrativeClass, 99, TrafficDirection.AgainstDigitizing, SingleCarriageway,
//      Some("25.06.2015 03:00:00"), Some("vvh_modified"), Map("MUNICIPALITYCODE" -> BigInt.apply(749)),
//      InUse, NormalLinkInterface)
//  }
//
//  private def roadAddressLinkToHistoryLink(roadAddressLink: RoadAddressLink) = {
//    VVHHistoryRoadLink(roadAddressLink.linkId, 749, roadAddressLink.geometry, roadAddressLink.administrativeClass,
//      TrafficDirection.AgainstDigitizing, FeatureClass.AllOthers, 123, 123, Map("MUNICIPALITYCODE" -> BigInt.apply(749)))
//  }

  //TODO this should be removed when we remove floating logic
//  test("Defloating road links on road 1130 part 4") {
//    val links = StaticTestData.road1130Links.filter(_.roadNumber.getOrElse("") == "1130").filter(_.attributes("ROADPARTNUMBER").asInstanceOf[BigInt].intValue == 4)
//    val history = StaticTestData.road1130HistoryLinks
//    val roadAddressService = new RoadAddressService(mockRoadLinkService, new RoadAddressDAO, mockRoadwayAddressMapper,mockEventBus)
//    when(mockRoadLinkService.getCurrentAndHistoryRoadLinksFromVVH(any[Set[Long]],any[Boolean])).thenReturn((StaticTestData.road1130Links, StaticTestData.road1130HistoryLinks))
//    when(mockRoadLinkService.getRoadLinksFromVVH(BoundingRectangle(Point(351714,6674367),Point(361946,6681967)), Seq((1,50000)), Set(), false, true, false)).thenReturn(links)
//    when(mockRoadLinkService.getComplementaryRoadLinksFromVVH(any[BoundingRectangle], any[Set[Int]])).thenReturn(Seq())
//    when(mockRoadLinkService.getRoadLinksHistoryFromVVH(any[Set[Long]])).thenReturn(history)
//    when(mockRoadLinkService.getChangeInfoFromVVHF(any[BoundingRectangle], any[Set[Int]])).thenReturn(Future(Seq.empty))
//    when(mockRoadLinkService.getSuravageLinksFromVVHF(any[BoundingRectangle], any[Set[Int]])).thenReturn(Future(Seq.empty))
//    when(mockRoadLinkService.getRoadLinksHistoryFromVVH(any[Set[Long]])).thenReturn(StaticTestData.road1130HistoryLinks)
//    runWithRollback {
//      val addressLinks = roadAddressService.getRoadAddressLinksWithSuravage(BoundingRectangle(Point(351714, 6674367), Point(361946, 6681967)), Seq((1, 50000)), false, true)
//      addressLinks.count(_.id == 0L) should be(2) // >There should be 2 unknown address links
//      addressLinks.forall(_.id == 0L) should be(false)
//      addressLinks.count(_.roadLinkSource == LinkGeomSource.HistoryLinkInterface) should be(4)
//      // >There should be 4 floating links
//      val replacement1s = addressLinks.filter(l => l.linkId == 1717639 || l.linkId == 499897217)
//      val replacement1t = addressLinks.filter(l => l.linkId == 500130192)
//      replacement1s.size should be(2)
//      replacement1t.size should be(1)
//      val result1 = roadAddressService.transferRoadAddress(replacement1s, replacement1t, User(0L, "foo", Configuration())).sortBy(_.startAddrMValue)
//      sanityCheck(result1)
//      result1.head.startMValue should be(0.0)
//      result1.head.startAddrMValue should be(replacement1s.map(_.startAddressM).min)
//      result1.last.endAddrMValue should be(replacement1s.map(_.endAddressM).max)
//
//      val replacement2s = addressLinks.filter(l => l.linkId == 1718096 || l.linkId == 1718097)
//      val replacement2t = addressLinks.filter(l => l.linkId == 500130201)
//      replacement2s.size should be(2)
//      replacement2t.size should be(1)
//      val result2 = roadAddressService.transferRoadAddress(replacement2s, replacement2t, User(0L, "foo", Configuration())).sortBy(_.startAddrMValue)
//      sanityCheck(result2)
//
//      result2.head.startMValue should be(0.0)
//      result2.head.startAddrMValue should be(replacement2s.map(_.startAddressM).min)
//      result2.last.endAddrMValue should be(replacement2s.map(_.endAddressM).max)
//    }
//  }

//TODO this should be removed when we remove Floating logic
//  test("GetFloatingAdjacents road links on road 75 part 2 sourceLinkId 5176142") {
//    val roadAddressService = new RoadAddressService(mockRoadLinkService,mockEventBus)
//    val road75FloatingAddresses = RoadAddress(367,75,2,RoadType.Unknown, Track.Combined,Discontinuity.Continuous,3532,3598,None,None,Some("tr"),
//      5176142,0.0,65.259,SideCode.TowardsDigitizing,0,(None,None),FloatingReason.ApplyChanges,List(Point(538889.668,6999800.979,0.0), Point(538912.266,6999862.199,0.0)),
//      LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0)
//
//    when(mockRoadLinkService.getRoadLinksHistoryFromVVH(any[Set[Long]])).thenReturn(
//      Seq.empty[VVHHistoryRoadLink]
//    )
//
//    val result = roadAddressService.getFloatingAdjacent(Set(road75FloatingAddresses.linkId), Set(road75FloatingAddresses.id), road75FloatingAddresses.linkId, road75FloatingAddresses.id, road75FloatingAddresses.roadNumber, road75FloatingAddresses.roadPartNumber, road75FloatingAddresses.track.value)
//    result.size should be (0)
//  }

  //TODO this should be removed when we remove floating logic
//  test("Defloating road links from three links to two links") {
//    val roadwayNumber = 123
//    val sources = Seq(
//      createRoadAddressLink(8000001L, 123L, Seq(Point(0.0, 0.0), Point(10.0, 10.0)), 1L, 1L, 0, 100, 114, SideCode.TowardsDigitizing, Anomaly.None, roadwayNumber = roadwayNumber),
//      createRoadAddressLink(8000002L, 124L, Seq(Point(10.0, 10.0), Point(20.0, 20.0)), 1L, 1L, 0, 114, 128, SideCode.TowardsDigitizing, Anomaly.None, roadwayNumber = roadwayNumber),
//      createRoadAddressLink(8000003L, 125L, Seq(Point(20.0, 20.0), Point(30.0, 30.0)), 1L, 1L, 0, 128, 142, SideCode.TowardsDigitizing, Anomaly.None, roadwayNumber = roadwayNumber)
//    )
//    val targets = Seq(
//      createRoadAddressLink(0L, 457L, Seq(Point(15.0, 15.0), Point(30.0, 30.0)), 0, 0, 0, 0, 0, SideCode.Unknown, Anomaly.NoAddressGiven, roadwayNumber = roadwayNumber),
//      createRoadAddressLink(0L, 456L, Seq(Point(0.0, 0.0), Point(15.0, 15.0)), 0, 0, 0, 0, 0, SideCode.Unknown, Anomaly.NoAddressGiven, roadwayNumber = roadwayNumber)
//    )
//    when(mockRoadLinkService.getCurrentAndHistoryRoadLinksFromVVH(any[Set[Long]], any[Boolean])).thenReturn(
//      (targets.map(roadAddressLinkToRoadLink), sources.map(roadAddressLinkToHistoryLink)))
//    when(mockRoadLinkService.getRoadLinksHistoryFromVVH(any[Set[Long]])).thenReturn(Seq())
//    val targetLinks = targets.map(roadAddressLinkToRoadLink)
//    val result = runWithRollback {
//      RoadAddressDAO.create(sources.map(roadAddressLinkToRoadAddress(FloatingReason.NewAddressGiven)))
//      roadAddressService.transferRoadAddress(sources, targets, User(0L, "foo", Configuration()))
//    }
//    sanityCheck(result)
//    val linkResult = result.map(ra => RoadAddressLinkBuilder.build(targetLinks.find(_.linkId == ra.linkId).get, ra))
//    val link456 = linkResult.find(_.linkId == 456L)
//    val link457 = linkResult.find(_.linkId == 457L)
//    link456.nonEmpty should be(true)
//    link457.nonEmpty should be(true)
//    link456.get.startAddressM should be(100)
//    link457.get.startAddressM should be(121)
//    link456.get.endAddressM should be(121)
//    link457.get.endAddressM should be(142)
//    result.forall(l => l.startCalibrationPoint.isEmpty && l.endCalibrationPoint.isEmpty) should be(true)
//    result.forall(l => l.roadwayNumber == roadwayNumber) should be(true)
//  }

  //TODO this should be removed when we remove floating logic
//  test("Defloating road links from three links to two links with against digitizing direction") {
//    val roadwayNumber = 123
//    val sources = Seq(
//      createRoadAddressLink(800001L, 123L, Seq(Point(0.0, 0.0), Point(10.0, 10.0)), 1L, 1L, 0, 128, 142, SideCode.AgainstDigitizing, Anomaly.None, roadwayNumber = roadwayNumber),
//      createRoadAddressLink(800002L, 124L, Seq(Point(10.0, 10.0), Point(20.0, 20.0)), 1L, 1L, 0, 114, 128, SideCode.AgainstDigitizing, Anomaly.None, roadwayNumber = roadwayNumber),
//      createRoadAddressLink(800003L, 125L, Seq(Point(20.0, 20.0), Point(30.0, 30.0)), 1L, 1L, 0, 100, 114, SideCode.AgainstDigitizing, Anomaly.None, roadwayNumber = roadwayNumber)
//    )
//    val targets = Seq(
//      createRoadAddressLink(0L, 457L, Seq(Point(15.0, 15.0), Point(30.0, 30.0)), 0, 0, 0, 0, 0, SideCode.Unknown, Anomaly.NoAddressGiven, roadwayNumber = roadwayNumber),
//      createRoadAddressLink(0L, 456L, Seq(Point(0.0, 0.0), Point(15.0, 15.0)), 0, 0, 0, 0, 0, SideCode.Unknown, Anomaly.NoAddressGiven, roadwayNumber = roadwayNumber)
//    )
//    when(mockRoadLinkService.getCurrentAndHistoryRoadLinksFromVVH(any[Set[Long]], any[Boolean])).thenReturn(
//      (targets.map(roadAddressLinkToRoadLink), sources.map(roadAddressLinkToHistoryLink)))
//    when(mockRoadLinkService.getRoadLinksHistoryFromVVH(any[Set[Long]])).thenReturn(Seq())
//    val result = runWithRollback {
//      RoadAddressDAO.create(sources.map(roadAddressLinkToRoadAddress(FloatingReason.ApplyChanges)))
//      roadAddressService.transferRoadAddress(sources, targets, User(0L, "foo", Configuration()))
//    }
//    sanityCheck(result)
//    val link456 = result.find(_.linkId == 456L)
//    val link457 = result.find(_.linkId == 457L)
//    link456.nonEmpty should be(true)
//    link457.nonEmpty should be(true)
//    link456.get.startAddrMValue should be(121)
//    link457.get.startAddrMValue should be(100)
//    link456.get.endAddrMValue should be(142)
//    link457.get.endAddrMValue should be(121)
//    result.forall(l => l.startCalibrationPoint.isEmpty && l.endCalibrationPoint.isEmpty) should be(true)
//    result.forall(l => l.sideCode == SideCode.AgainstDigitizing) should be(true)
//    result.forall(l => l.roadwayNumber == roadwayNumber) should be(true)
//  }

  //TODO this should be removed when we remove floating logic
//  test("Defloating road links from three links to two links with one calibration point in beginning") {
//    val roadwayNumber = 123
//    val sources = Seq(
//      createRoadAddressLink(800001L, 123L, Seq(Point(0.0, 0.0), Point(10.0, 10.0)), 1L, 1L, 0, 0, 14, SideCode.TowardsDigitizing, Anomaly.None, startCalibrationPoint = true, endCalibrationPoint = false, roadwayNumber = roadwayNumber),
//      createRoadAddressLink(800003L, 125L, Seq(Point(20.0, 20.0), Point(30.0, 30.0)), 1L, 1L, 0, 28, 42, SideCode.TowardsDigitizing, Anomaly.None, startCalibrationPoint = false, endCalibrationPoint = false, roadwayNumber = roadwayNumber),
//      createRoadAddressLink(800002L, 124L, Seq(Point(10.0, 10.0), Point(20.0, 20.0)), 1L, 1L, 0, 14, 28, SideCode.TowardsDigitizing, Anomaly.None, startCalibrationPoint = false, endCalibrationPoint = false, roadwayNumber = roadwayNumber)
//    )
//    val targets = Seq(
//      createRoadAddressLink(0L, 456L, Seq(Point(0.0, 0.0), Point(15.0, 15.0)), 0, 0, 0, 0, 0, SideCode.Unknown, Anomaly.NoAddressGiven, startCalibrationPoint = false, endCalibrationPoint = false, roadwayNumber = roadwayNumber),
//      createRoadAddressLink(0L, 457L, Seq(Point(15.0, 15.0), Point(30.0, 30.0)), 0, 0, 0, 0, 0, SideCode.Unknown, Anomaly.NoAddressGiven, startCalibrationPoint = false, endCalibrationPoint = false, roadwayNumber = roadwayNumber)
//    )
//    when(mockRoadLinkService.getCurrentAndHistoryRoadLinksFromVVH(any[Set[Long]], any[Boolean])).thenReturn(
//      (targets.map(roadAddressLinkToRoadLink), sources.map(roadAddressLinkToHistoryLink)))
//    when(mockRoadLinkService.getRoadLinksHistoryFromVVH(any[Set[Long]])).thenReturn(Seq())
//    val result = runWithRollback {
//      RoadAddressDAO.create(sources.map(roadAddressLinkToRoadAddress(FloatingReason.ApplyChanges)))
//      roadAddressService.transferRoadAddress(sources, targets, User(0L, "foo", Configuration()))
//    }
//    sanityCheck(result)
//    val link456 = result.find(_.linkId == 456L)
//    val link457 = result.find(_.linkId == 457L)
//    link456.nonEmpty should be(true)
//    link457.nonEmpty should be(true)
//    link456.get.startAddrMValue should be(0)
//    link457.get.startAddrMValue should be(21)
//    link456.get.endAddrMValue should be(21)
//    link457.get.endAddrMValue should be(42)
//    result.forall(l => l.endCalibrationPoint.isEmpty) should be(true)
//    link456.get.startCalibrationPoint.nonEmpty should be(true)
//    link457.get.startCalibrationPoint.nonEmpty should be(false)
//    result.forall(l => l.roadwayNumber == roadwayNumber) should be(true)
//  }

  //TODO this should be removed when we remove floating logic
//  test("Defloating road links from three links to two links with one calibration point in the end") {
//    val roadwayNumber = 123
//    val sources = Seq(
//      createRoadAddressLink(800001L, 123L, Seq(Point(0.0,0.0), Point(10.0, 10.0)), 1L, 1L, 0, 0, 14, SideCode.TowardsDigitizing, Anomaly.None, roadwayNumber = roadwayNumber),
//      createRoadAddressLink(800003L, 125L, Seq(Point(20.0,20.0), Point(30.0, 30.0)), 1L, 1L, 0, 28, 42, SideCode.TowardsDigitizing, Anomaly.None, endCalibrationPoint = true, roadwayNumber = roadwayNumber),
//      createRoadAddressLink(800002L, 124L, Seq(Point(10.0,10.0), Point(20.0, 20.0)), 1L, 1L, 0, 14, 28, SideCode.TowardsDigitizing, Anomaly.None, roadwayNumber = roadwayNumber)
//    )
//    val targets = Seq(
//      createRoadAddressLink(0L, 456L, Seq(Point(0.0,0.0), Point(15.0, 15.0)), 0, 0, 0, 0, 0, SideCode.Unknown, Anomaly.NoAddressGiven, roadwayNumber = roadwayNumber),
//      createRoadAddressLink(0L, 457L, Seq(Point(15.0,15.0), Point(30.0, 30.0)), 0, 0, 0, 0, 0, SideCode.Unknown, Anomaly.NoAddressGiven, roadwayNumber = roadwayNumber)
//    )
//    when(mockRoadLinkService.getCurrentAndHistoryRoadLinksFromVVH(any[Set[Long]],any[Boolean])).thenReturn(
//      (targets.map(roadAddressLinkToRoadLink), sources.map(roadAddressLinkToHistoryLink)))
//    when(mockRoadLinkService.getRoadLinksHistoryFromVVH(any[Set[Long]])).thenReturn(Seq())
//    val result = runWithRollback {
//      RoadAddressDAO.create(sources.map(roadAddressLinkToRoadAddress(FloatingReason.ApplyChanges)))
//      roadAddressService.transferRoadAddress(sources, targets, User(0L, "foo", Configuration()))
//    }
//    sanityCheck(result)
//    val link456 = result.find(_.linkId == 456L)
//    val link457 = result.find(_.linkId == 457L)
//    link456.nonEmpty should be (true)
//    link457.nonEmpty should be (true)
//    link456.get.startAddrMValue should be (0)
//    link457.get.startAddrMValue should be (21)
//    link456.get.endAddrMValue should be (21)
//    link457.get.endAddrMValue should be (42)
//    result.forall(l => l.startCalibrationPoint.isEmpty) should be (true)
//    link456.get.endCalibrationPoint.isEmpty should be (true)
//    link457.get.endCalibrationPoint.isEmpty should be (false)
//    result.forall(l => l.roadwayNumber == roadwayNumber) should be(true)
//  }

  //TODO this should be removed when we remove floating logic
//  test("Zigzag geometry defloating") {
//
//    /*
//
//   (10,10) x                     x (30,10)           ,                     x (30,10)
//          / \                   /                   / \                   /
//         /   \                 /                   /   \                 /
//        /     \               /                   /     \               /
//       /       \             /                   /       \             /
//      x (5,5)   \           /                   x (5,5)   \           /
//                 \         /                               \         /
//                  \       /                                 \       /
//                   \     /                                   \     /
//                    \   /                                     \   /
//                     \ /                                (19,1) x /
//                      x (20,0)                                  v
//
//     */
//
//    val roadwayNumber = 123
//    val sources = Seq(
//      createRoadAddressLink(800001L, 123L, Seq(Point(5.0, 5.0), Point(10.0, 10.0)), 1L, 1L, 0, 100, 107, SideCode.TowardsDigitizing, Anomaly.None, false, false, roadwayNumber),
//      createRoadAddressLink(800003L, 125L, Seq(Point(20.0, 0.0), Point(30.0, 10.0)), 1L, 1L, 0, 121, 135, SideCode.TowardsDigitizing, Anomaly.None, false, false, roadwayNumber),
//      createRoadAddressLink(800002L, 124L, Seq(Point(20.0, 0.0), Point(10.0, 10.0)), 1L, 1L, 0, 107, 121, SideCode.AgainstDigitizing, Anomaly.None, false, false, roadwayNumber)
//    )
//    val targets = Seq(
//      createRoadAddressLink(0L, 456L, Seq(Point(19.0, 1.0), Point(10.0, 10.0), Point(5.0, 5.0)), 0, 0, 0, 0, 0, SideCode.Unknown, Anomaly.NoAddressGiven, false, false, roadwayNumber),
//      createRoadAddressLink(0L, 457L, Seq(Point(19.0, 1.0), Point(20.0, 0.0), Point(30.0, 10.0)), 0, 0, 0, 0, 0, SideCode.Unknown, Anomaly.NoAddressGiven, false, false, roadwayNumber)
//    )
//    when(mockRoadLinkService.getCurrentAndHistoryRoadLinksFromVVH(any[Set[Long]], any[Boolean])).thenReturn(
//      (targets.map(roadAddressLinkToRoadLink), sources.map(roadAddressLinkToHistoryLink)))
//    when(mockRoadLinkService.getRoadLinksHistoryFromVVH(any[Set[Long]])).thenReturn(Seq())
//    val result = runWithRollback {
//      RoadAddressDAO.create(sources.map(roadAddressLinkToRoadAddress(FloatingReason.ApplyChanges)))
//      roadAddressService.transferRoadAddress(sources, targets, User(0L, "foo", Configuration()))
//    }
//    sanityCheck(result)
//    val link456 = result.find(_.linkId == 456L)
//    val link457 = result.find(_.linkId == 457L)
//    link456.nonEmpty should be(true)
//    link457.nonEmpty should be(true)
//    link456.get.sideCode should be(SideCode.AgainstDigitizing)
//    link457.get.sideCode should be(SideCode.TowardsDigitizing)
//    link456.get.startAddrMValue should be(100)
//    link457.get.startAddrMValue should be(120)
//    link456.get.endAddrMValue should be(120)
//    link457.get.endAddrMValue should be(135)
//    link456.get.startCalibrationPoint.nonEmpty should be(false)
//    link457.get.startCalibrationPoint.nonEmpty should be(false)
//    link457.get.endCalibrationPoint.nonEmpty should be(false)
//    link456.get.endCalibrationPoint.nonEmpty should be(false)
//    result.forall(l => l.roadwayNumber == roadwayNumber) should be(true)
//  }

  //TODO this will be implemented at VIITE-1536
//  test("Kokkolantie 2 + 1 segments to 2 segments mapping (2 links to 1 link)") {
//    val roadwayNumber = 123
//    runWithRollback {
//      val targetLinkData = createRoadAddressLink(0L, 1392315L, Seq(Point(336973.635, 7108605.965), Point(336994.491, 7108726.504)), 0, 0, 0, 0, 0, SideCode.Unknown,
//        Anomaly.NoAddressGiven, roadwayNumber = roadwayNumber)
//      val geom = Seq(Point(336991.162, 7108706.098), Point(336994.491, 7108726.504))
//      val sourceLinkData0 = createRoadAddressLink(Sequences.nextRoadwayId, 1392315L, Seq(Point(336973.635, 7108605.965), Point(336991.633, 7108709.155)), 8, 412, 2, 3045, 3148, SideCode.TowardsDigitizing,
//        Anomaly.GeometryChanged, true, false, roadwayNumber)
//      val sourceLinkData1 = createRoadAddressLink(Sequences.nextRoadwayId, 1392326L, GeometryUtils.truncateGeometry2D(geom, 0.0, 15.753), 8, 412, 2, 3148, 3164, SideCode.TowardsDigitizing,
//        Anomaly.None, roadwayNumber = roadwayNumber)
//      val sourceLinkData2 = createRoadAddressLink(Sequences.nextRoadwayId, 1392326L, GeometryUtils.truncateGeometry2D(geom, 15.753, 20.676), 8, 412, 2, 3164, 3169, SideCode.TowardsDigitizing,
//        Anomaly.None, false, true, roadwayNumber)
//      val sourceLinkDataC = createRoadAddressLink(Sequences.nextRoadwayId, 1392326L, geom, 0, 0, 0, 0, 0, SideCode.Unknown,
//        Anomaly.NoAddressGiven, roadwayNumber = roadwayNumber)
//      val sourceLinks = Seq(sourceLinkData0, sourceLinkData1, sourceLinkData2).map(_.copy(roadLinkType = FloatingRoadLinkType))
//      val historyLinks = Seq(sourceLinkData0, sourceLinkDataC).map(roadAddressLinkToHistoryLink)
//      val targetLinks = Seq(targetLinkData)
//      val roadAddressSeq = sourceLinks.map(roadAddressLinkToRoadAddress(FloatingReason.ApplyChanges)).map { ra =>
//        if (ra.startAddrMValue == 3164)
//          ra.copy(startMValue = 15.753, endMValue = 20.676,
//            calibrationPoints = (None, ra.calibrationPoints._2.map(_.copy(segmentMValue = 20.676))))
//        else
//          ra
//      }
//      RoadAddressDAO.create(roadAddressSeq)
//      RoadAddressDAO.createUnaddressedRoadLink(1392315, 0, 0, 2)
//      // pre-checks
//      RoadAddressDAO.fetchByLinkId(Set(1392315L, 1392326L), true) should have size (3)
//      val mapping = DefloatMapper.createAddressMap(sourceLinks, targetLinks)
//      mapping should have size (3)
//
//      val roadLinks = targetLinks.map(roadAddressLinkToRoadLink)
//      when(mockRoadLinkService.getCurrentAndHistoryRoadLinksFromVVH(Set(1392315L))).thenReturn((roadLinks, historyLinks.filter(_.linkId == 1392315L)))
//      when(mockRoadLinkService.getCurrentAndHistoryRoadLinksFromVVH(Set(1392326L))).thenReturn((Seq(), historyLinks.filter(_.linkId == 1392326L)))
//      val roadAddresses = roadAddressService.getRoadAddressesAfterCalculation(Seq("1392326", "1392315"), Seq("1392315"), User(0L, "Teppo", Configuration()))
//      roadAddressService.transferFloatingToGap(Set(1392326, 1392315), Set(1392315), roadAddresses, "Teppo")
//
//      val transferred = RoadAddressDAO.fetchByLinkId(Set(1392315L, 1392326L), false)
//      transferred should have size (1)
//      transferred.head.linkId should be(1392315L)
//      transferred.head.roadNumber should be(8)
//      transferred.head.roadPartNumber should be(412)
//      transferred.head.track.value should be(2)
//      transferred.head.endCalibrationPoint.isEmpty should be(false)
//      transferred.head.startCalibrationPoint.isEmpty should be(false)
//      transferred.head.startAddrMValue should be(3045)
//      transferred.head.endAddrMValue should be(3169)
//      GeometryUtils.areAdjacent(transferred.head.geometry, Seq(targetLinkData.geometry.head, targetLinkData.geometry.last)) should be(true)
//      transferred.forall(l => l.roadwayNumber == roadwayNumber) should be(true)
//    }
//  }

  //TODO this will be implemented at VIITE-1536
//  test("Filtering not relevant changes to be applied") {
//    def DummyRoadAddress(id: Long, linkId: Long, timestamp: Long): RoadAddress = {
//      RoadAddress(1, 199, 199, PublicRoad, Track.Combined, Continuous, 100L, 105L,
//        Some(DateTime.now().minusYears(15)), Some(DateTime.now().minusYears(10)), None, linkId, 0.0, 4.61, TowardsDigitizing,
//        timestamp, (None, None), FloatingReason.ApplyChanges, Seq(Point(0, 0), Point(1.0, 4.5)), NormalLinkInterface, 20L, NoTermination, 0)
//    }
//
//    def DummyChangeInfo(oldId: Option[Long], newId: Option[Long], timestamp: Long): ChangeInfo ={
//      ChangeInfo(oldId, newId, 1L, 1, Some(0),Some(10),Some(0),Some(10), timestamp)
//    }
//
//    runWithRollback {
//      val roadAddresses = Seq(
//        DummyRoadAddress(id = 1L, linkId = 222L, timestamp = 10),
//        DummyRoadAddress(id = 2L, linkId = 333L, timestamp = 20),
//        DummyRoadAddress(id = 3L, linkId = 444l, timestamp = 30)
//      )
//
//      val changesToBeApplied = Seq(
//        DummyChangeInfo(Some(222L), Some(555L), 20),
//        DummyChangeInfo(Some(222L), Some(222L), 15),
//        DummyChangeInfo(None, Some(222L), 15),
//        DummyChangeInfo(Some(222L), None, 20)
//      )
//
//      val changesNotApplied = Seq(
//        DummyChangeInfo(Some(666L), Some(555L), 20),
//        DummyChangeInfo(Some(222L), Some(222L), 0),
//        DummyChangeInfo(Some(222L), Some(222L), 9)
//      )
//
//     val result = roadAddressService.filterRelevantChanges(roadAddresses, changesNotApplied ++ changesToBeApplied)
//
//      result.size should be (4)
//      result.forall(changesToBeApplied.contains) should be (true)
//    }
//  }

  //TODO this will be implemented at VIITE-1536
//  test("Test change info on links 5622931, 5622953, 499914628 and 499914643 (will refuse transfer)") {
//    val geom6 = Seq(Point(6733893, 332453), Point(6733990, 332420))
//    val geom8 = Seq(Point(6733990, 332420), Point(6734010, 332412))
//    val geom7 = Seq(Point(6734010, 332412), Point(6734148, 332339))
//    val geom9 = Seq(Point(6734148, 332339), Point(6734173, 332309))
//
//    val geom1 = GeometryUtils.truncateGeometry3D(geom6, 0.0349106, 93.90506222)
//    val geom2 = Seq(Point(6734008.707,332412.780), Point(6734010.761,332411.959))
//    val geom3 = GeometryUtils.truncateGeometry3D(geom6, 93.90506222, 103.78471484)
//    val geom4 = GeometryUtils.truncateGeometry3D(geom7, 1.31962463, 157.72241408)
//    val geom5 = geom9
//
//    val linkId1 = 5622927
//    val linkId2 = 5622931
//    val linkId3 = 5622932
//    val linkId4 = 5622950
//    val linkId5 = 5622953
//    val linkId6 = 499914628
//    val linkId7 = 499914643
//
//    val roadwayNumber = 123
//
//    runWithRollback {
//      val oldAddressLinks = Seq(
//        createRoadAddressLink(Sequences.nextRoadwayId, linkId1, geom1, 2825, 3, 0, 0, 101, SideCode.TowardsDigitizing, Anomaly.None, true, false, roadwayNumber),
//        createRoadAddressLink(Sequences.nextRoadwayId, linkId2, geom2, 2825, 3, 0, 101, 103, SideCode.TowardsDigitizing, Anomaly.None, false, false, roadwayNumber),
//        createRoadAddressLink(Sequences.nextRoadwayId, linkId3, geom3, 2825, 3, 0, 103, 113, SideCode.TowardsDigitizing, Anomaly.None, false, false, roadwayNumber),
//        createRoadAddressLink(Sequences.nextRoadwayId, linkId4, geom4, 2825, 3, 0, 113, 279, SideCode.TowardsDigitizing, Anomaly.None, false, false, roadwayNumber),
//        createRoadAddressLink(Sequences.nextRoadwayId, linkId5, geom5, 2825, 3, 0, 279, 321, SideCode.TowardsDigitizing, Anomaly.None, false, true, roadwayNumber) // end calibration point for testing
//      )
//
//      val addresses = oldAddressLinks.map(roadAddressLinkToRoadAddress(NoFloating))
//
//      val newLinks = Seq(
//        createRoadAddressLink(NewRoadAddress, linkId6, geom6, 0, 0, 0, 0, 0, SideCode.TowardsDigitizing, Anomaly.None, false, false, roadwayNumber),
//        createRoadAddressLink(NewRoadAddress, linkId7, geom7, 0, 0, 0, 0, 0, SideCode.TowardsDigitizing, Anomaly.None, false, false, roadwayNumber),
//        createRoadAddressLink(NewRoadAddress, linkId2, geom8, 0, 0, 0, 0, 0, SideCode.TowardsDigitizing, Anomaly.None, false, false, roadwayNumber),
//        createRoadAddressLink(NewRoadAddress, linkId5, geom9, 0, 0, 0, 0, 0, SideCode.TowardsDigitizing, Anomaly.None, false, false, roadwayNumber)
//      ).map(roadAddressLinkToRoadLink)
//      val changeTable = Seq(
//        createChangeTable(linkId2, linkId2, ChangeType.ReplacedCommonPart, 0, 2.21200769, 19.59840118, 20.49478145, 1476478965000L),
//        createChangeTable(0, linkId2, ChangeType.ReplacedNewPart, 0, 0, 0, 23.59840118, 1476478965000L),
//        createChangeTable(linkId1, linkId6, ChangeType.CombinedModifiedPart, 0, 93.90293074, 0.0349106, 95.90506222, 1476478965000L),
//        createChangeTable(linkId3, linkId6, ChangeType.CombinedRemovedPart, 0, 19.46021513, 98.90506222, 103.78471484, 1476478965000L),
//        createChangeTable(linkId4, linkId7, ChangeType.CombinedModifiedPart, 0, 156.4126127, 1.31962463, 159.72241408, 1476478965000L),
//        createChangeTable(linkId2, linkId7, ChangeType.CombinedModifiedPart, 0, 3.21200769, 0.0, 1.31962463, 1476478965000L)
//      )
//
//      RoadAddressDAO.create(addresses)
//      val newAddresses = roadAddressService.applyChanges(newLinks, changeTable, addresses)
//
//      // Test that this is not accepted as 101-103 is moved to locate after 103-113
//      newAddresses.flatMap(_.allSegments).map(_.id).toSet should be (addresses.map(_.id).toSet)
//
//      newAddresses.flatMap(_.allSegments).map(_.roadwayNumber).toSet.size should be (1)
//      newAddresses.flatMap(_.allSegments).map(_.roadwayNumber).toSet.head should be (roadwayNumber)
//    }
//  }

  //TODO this will be implemented at VIITE-1536
//  test("drop changes that have different old and new lengths"){
//    val changeTable = Seq(
//      createChangeTable(5622927, 499914628, ChangeType.CombinedModifiedPart, 0, 93.90293074, 0.0349106, 93.93784134, 1476478965000L),
//      createChangeTable(5622931, 499914628, ChangeType.CombinedRemovedPart, 0, 2.21200293, 93.90506222, 96.11706515, 1476478965000L),
//      createChangeTable(5622950, 499914643, ChangeType.CombinedModifiedPart, 0, 156.4126127, 1.31962463, 157.73223733, 1476478965000L),
//      createChangeTable(5622932, 499914643, ChangeType.CombinedRemovedPart, 0,8.554685974199694, 0.0, 8.554685974199694, 1476478965000L)
//    )
//    roadAddressService.changesSanityCheck(changeTable).size should be (4)
//
//    val changeTable2 = Seq(
//      createChangeTable(5622927, 499914628, ChangeType.CombinedModifiedPart, 0, 13.90293074, 0.0349106, 91.93784134, 1476478965000L),
//      createChangeTable(5622931, 499914628, ChangeType.CombinedRemovedPart, 0, 2.21200293, 93.90506222, 15.11706515, 1476478965000L),
//      createChangeTable(5622950, 499914643, ChangeType.CombinedModifiedPart, 0, 156.4126127, 1.31962463, 146.73223733, 1476478965000L),
//      createChangeTable(5622932, 499914643, ChangeType.CombinedRemovedPart, 0,8.554685974199694, 0.0, 6.554685974199694, 1476478965000L)
//    )
//  roadAddressService.changesSanityCheck(changeTable2).size should be (0)
//  }

  //TODO this will be implemented at VIITE-1536
//  test("Test change info on links 5622931, 5622953, 499914628 and 499914643 with only handled transitions") {
//    val n499914628Geom = Seq(Point(6733893, 332453), Point(6733990, 332420))
//    val n5622931Geom = Seq(Point(6733990, 332420), Point(6734010, 332412))
//    val n499914643Geom = Seq(Point(6734010, 332412), Point(6734148, 332339))
//    val n5622953Geom = Seq(Point(6734148, 332339), Point(6734173, 332309))
//
//    val o5622927Geom = GeometryUtils.truncateGeometry3D(n499914628Geom, 0.0349106, 93.90506222)
//    val o5622931Geom = Seq(Point(6734008.707,332412.780), Point(6734010.761,332411.959))
//    val o5622932Geom = GeometryUtils.truncateGeometry3D(n499914628Geom, 93.90506222, 103.78471484)
//    val o5622950Geom = GeometryUtils.truncateGeometry3D(n499914643Geom, 1.31962463, 157.72241408)
//    val o5622953Geom = n5622953Geom
//
//    val roadwayNumber = 123
//
//    runWithRollback {
//      val oldAddressLinks = Seq(
//        createRoadAddressLink(Sequences.nextRoadwayId, 5622927, o5622927Geom, 92825, 3, 0, 0, 101, SideCode.TowardsDigitizing, Anomaly.None, true, false, roadwayNumber),
//        createRoadAddressLink(Sequences.nextRoadwayId, 5622931, o5622931Geom, 92825, 3, 0, 101, 103, SideCode.TowardsDigitizing, Anomaly.None, false, false, roadwayNumber),
//        createRoadAddressLink(Sequences.nextRoadwayId, 5622932, o5622932Geom, 92825, 3, 0, 103, 113, SideCode.TowardsDigitizing, Anomaly.None, false, false, roadwayNumber),
//        createRoadAddressLink(Sequences.nextRoadwayId, 5622950, o5622950Geom, 92825, 3, 0, 113, 279, SideCode.TowardsDigitizing, Anomaly.None, false, false, roadwayNumber),
//        createRoadAddressLink(Sequences.nextRoadwayId, 5622953, o5622953Geom, 92825, 3, 0, 279, 321, SideCode.TowardsDigitizing, Anomaly.None, false, true, roadwayNumber) // end calibration point for testing
//      )
//
//      val addresses = oldAddressLinks.map(roadAddressLinkToRoadAddress(NoFloating))
//
//      val newLinks = Seq(
//        createRoadAddressLink(0, 499914628, n499914628Geom, 15, 1, 0, 1, 2, SideCode.TowardsDigitizing, Anomaly.None, false, false, roadwayNumber),
//        createRoadAddressLink(0, 499914643, n499914643Geom, 15, 1, 0, 2, 3, SideCode.TowardsDigitizing, Anomaly.None, false, false, roadwayNumber),
//        createRoadAddressLink(0, 5622931, n5622931Geom, 15, 1, 0, 3, 4, SideCode.TowardsDigitizing, Anomaly.None, false, false, roadwayNumber),
//        createRoadAddressLink(0, 5622953, n5622953Geom, 15, 1, 0, 5, 6, SideCode.TowardsDigitizing, Anomaly.None, false, false, roadwayNumber)
//      ).map(roadAddressLinkToRoadLink)
//      val changeTable = Seq(
//        createChangeTable(5622927, 499914628, ChangeType.CombinedModifiedPart, 0, 93.90293074, 0.0349106, 93.93784134, 1476478965000L),
//        createChangeTable(5622931, 499914628, ChangeType.CombinedRemovedPart, 0, 2.21200293, 93.90506222, 96.11706515, 1476478965000L),
//        createChangeTable(5622950, 499914643, ChangeType.CombinedModifiedPart, 0, 156.4126127, 1.31962463, 157.73223733, 1476478965000L),
//        createChangeTable(5622932, 499914643, ChangeType.CombinedRemovedPart, 0,8.554685974199694, 0.0, 8.554685974199694, 1476478965000L)
//      )
//
//      RoadAddressDAO.create(addresses)
//      val newAddresses = roadAddressService.applyChanges(newLinks, changeTable, addresses)
//      // should contain just the 5622953
//      newAddresses.flatMap(_.allSegments).map(_.id).toSet.intersect(addresses.map(_.id).toSet) should have size 1
//      newAddresses.flatMap(_.allSegments).exists(_.linkId == 5622953) should be (true)
//      newAddresses.flatMap(_.allSegments).map(_.roadwayNumber).toSet.size should be (1)
//      newAddresses.flatMap(_.allSegments).forall(_.roadwayNumber == roadwayNumber) should be (true)
//    }
//  }

  //TODO this will be implemented at VIITE-1536
//  test("Test if changes applied for both current and history valid addresses") {
//    val linkGeom = Seq(Point(0, 0), Point(30, 0))
//
//    val address1Geom = GeometryUtils.truncateGeometry3D(linkGeom, 0.0, 5.0)
//    val address2Geom = GeometryUtils.truncateGeometry3D(linkGeom, 0.0, 10.0)
//    val address3Geom = GeometryUtils.truncateGeometry3D(linkGeom, 0.0, 15.0)
//
//    val roadwayNumber = 123
//
//    runWithRollback {
//      val currentAddressLinks = Seq(
//        createRoadAddressLink(Sequences.nextRoadwayId, 11111, address1Geom, 12345, 1, 0, 0, 5, SideCode.TowardsDigitizing, Anomaly.None, false, false, roadwayNumber),
//        createRoadAddressLink(Sequences.nextRoadwayId, 11112, address2Geom, 12345, 1, 0, 5, 15, SideCode.TowardsDigitizing, Anomaly.None, false, false, roadwayNumber),
//        createRoadAddressLink(Sequences.nextRoadwayId, 11113, address3Geom, 12345, 1, 0, 15, 30, SideCode.TowardsDigitizing, Anomaly.None, false, false, roadwayNumber)
//        )
//      val historyAddressLinks = Seq(
//        createRoadAddressLink(Sequences.nextRoadwayId, 11111, address1Geom, 12345, 1, 0, 0, 5, SideCode.TowardsDigitizing, Anomaly.None, false, false, roadwayNumber),
//        createRoadAddressLink(Sequences.nextRoadwayId, 11112, address2Geom, 12345, 1, 0, 5, 15, SideCode.TowardsDigitizing, Anomaly.None, false, false, roadwayNumber),
//        createRoadAddressLink(Sequences.nextRoadwayId, 11113, address3Geom, 12345, 1, 0, 15, 30, SideCode.TowardsDigitizing, Anomaly.None, false, false, roadwayNumber)
//      )
//
//      val currentAddresses = currentAddressLinks.map(roadAddressLinkToRoadAddress(NoFloating))
//      val historicAddresses = historyAddressLinks.map(roadAddressLinkToRoadAddress(NoFloating)).map(_.copy(endDate = Option(new DateTime(new Date()))))
//
//      val roadLinks = Seq(
//        RoadLink(90000, linkGeom, GeometryUtils.geometryLength(linkGeom),
//          AdministrativeClass.apply(1), 99, TrafficDirection.BothDirections, UnknownLinkType,
//          Some("25.06.2015 03:00:00"), Some("vvh_modified"), Map("MUNICIPALITYCODE" -> BigInt.apply(749)),
//          InUse, NormalLinkInterface)
//      )
//
//      val changeTable = Seq(
//        createChangeTable(11111, 90000, ChangeType.CombinedModifiedPart, 0, 5.0, 0, 5.0, 1476478965000L),
//        createChangeTable(11112, 90000, ChangeType.CombinedRemovedPart, 0, 10.0, 5.0, 15.0, 1476478965000L),
//        createChangeTable(11113, 90000, ChangeType.CombinedRemovedPart, 0, 15.0, 15.0, 30.0, 1476478965000L)
//      )
//
//      RoadAddressDAO.create(currentAddresses++historicAddresses)
//      val newAddresses = roadAddressService.applyChanges(roadLinks, changeTable, currentAddresses++historicAddresses)
//
//      newAddresses.flatMap(_.allSegments).size should be (currentAddresses.size+historicAddresses.size)
//      newAddresses.flatMap(_.allSegments).foreach{a => a.linkId should be (roadLinks.head.linkId)}
//    }
//  }

  //TODO this will be implemented at VIITE-1536
//  test("Test if changes applied for current valid addresses w/ history addresses non valid") {
//    val linkGeom = Seq(Point(0, 0), Point(30, 0))
//
//    val address1Geom = GeometryUtils.truncateGeometry3D(linkGeom, 0.0, 5.0)
//    val address2Geom = GeometryUtils.truncateGeometry3D(linkGeom, 0.0, 10.0)
//    val address3Geom = GeometryUtils.truncateGeometry3D(linkGeom, 0.0, 15.0)
//
//    val roadwayNumber = 123
//
//    runWithRollback {
//      val currentAddressLinks = Seq(
//        createRoadAddressLink(Sequences.nextRoadwayId, 11111, address1Geom, 12345, 1, 0, 0, 5, SideCode.TowardsDigitizing, Anomaly.None, false, false, roadwayNumber),
//        createRoadAddressLink(Sequences.nextRoadwayId, 11112, address2Geom, 12345, 1, 0, 5, 15, SideCode.TowardsDigitizing, Anomaly.None, false, false, roadwayNumber),
//        createRoadAddressLink(Sequences.nextRoadwayId, 11113, address3Geom, 12345, 1, 0, 15, 30, SideCode.TowardsDigitizing, Anomaly.None, false, false, roadwayNumber)
//      )
//      val historyAddressLinks = Seq(
//        createRoadAddressLink(Sequences.nextRoadwayId, 11111, address1Geom, 12345, 1, 0, 0, 5, SideCode.TowardsDigitizing, Anomaly.None, false, false, roadwayNumber),
//        createRoadAddressLink(Sequences.nextRoadwayId, 11112, address2Geom, 12345, 1, 0, 5, 15, SideCode.TowardsDigitizing, Anomaly.None, false, false, roadwayNumber),
//        createRoadAddressLink(Sequences.nextRoadwayId, 11113, address3Geom, 12345, 1, 0, 15, 30, SideCode.TowardsDigitizing, Anomaly.None, false, false, roadwayNumber)
//      )
//
//      val currentAddresses = currentAddressLinks.map(roadAddressLinkToRoadAddress(NoFloating))
//      val historicAddresses = historyAddressLinks.map(roadAddressLinkToRoadAddress(NoFloating)).map(_.copy(endDate = Option(new DateTime(new Date())), validTo = Option(new DateTime(new Date()))))
//
//      val roadLinks = Seq(
//        RoadLink(90000, linkGeom, GeometryUtils.geometryLength(linkGeom),
//          AdministrativeClass.apply(1), 99, TrafficDirection.BothDirections, UnknownLinkType,
//          Some("25.06.2015 03:00:00"), Some("vvh_modified"), Map("MUNICIPALITYCODE" -> BigInt.apply(749)),
//          InUse, NormalLinkInterface)
//      )
//
//      val changeTable = Seq(
//        createChangeTable(11111, 90000, ChangeType.CombinedModifiedPart, 0, 5.0, 0, 5.0, 1476478965000L),
//        createChangeTable(11112, 90000, ChangeType.CombinedRemovedPart, 0, 10.0, 5.0, 15.0, 1476478965000L),
//        createChangeTable(11113, 90000, ChangeType.CombinedRemovedPart, 0, 15.0, 15.0, 30.0, 1476478965000L)
//      )
//
//      RoadAddressDAO.create(currentAddresses++historicAddresses)
//      val newAddresses = roadAddressService.applyChanges(roadLinks, changeTable, currentAddresses)
//
//      newAddresses.flatMap(_.allSegments).size should be (currentAddresses.size)
//      newAddresses.flatMap(_.allSegments).foreach{a => a.linkId should be (roadLinks.head.linkId)}
//    }
//  }

  //TODO this will be implemented at VIITE-1536
//  test("Test if changes applied for history valid addresses w/ current addresses non valid") {
//    val linkGeom = Seq(Point(0, 0), Point(30, 0))
//
//    val address1Geom = GeometryUtils.truncateGeometry3D(linkGeom, 0.0, 5.0)
//    val address2Geom = GeometryUtils.truncateGeometry3D(linkGeom, 0.0, 10.0)
//    val address3Geom = GeometryUtils.truncateGeometry3D(linkGeom, 0.0, 15.0)
//
//    val roadwayNumber = 123
//
//    runWithRollback {
//      val currentAddressLinks = Seq(
//        createRoadAddressLink(Sequences.nextRoadwayId, 11111, address1Geom, 12345, 1, 0, 0, 5, SideCode.TowardsDigitizing, Anomaly.None, false, false, roadwayNumber),
//        createRoadAddressLink(Sequences.nextRoadwayId, 11112, address2Geom, 12345, 1, 0, 5, 15, SideCode.TowardsDigitizing, Anomaly.None, false, false, roadwayNumber),
//        createRoadAddressLink(Sequences.nextRoadwayId, 11113, address3Geom, 12345, 1, 0, 15, 30, SideCode.TowardsDigitizing, Anomaly.None, false, false, roadwayNumber)
//      )
//      val historyAddressLinks = Seq(
//        createRoadAddressLink(Sequences.nextRoadwayId, 11111, address1Geom, 12345, 1, 0, 0, 5, SideCode.TowardsDigitizing, Anomaly.None, false, false, roadwayNumber),
//        createRoadAddressLink(Sequences.nextRoadwayId, 11112, address2Geom, 12345, 1, 0, 5, 15, SideCode.TowardsDigitizing, Anomaly.None, false, false, roadwayNumber),
//        createRoadAddressLink(Sequences.nextRoadwayId, 11113, address3Geom, 12345, 1, 0, 15, 30, SideCode.TowardsDigitizing, Anomaly.None, false, false, roadwayNumber)
//      )
//
//      val currentAddresses = currentAddressLinks.map(roadAddressLinkToRoadAddress(NoFloating)).map(_.copy(validTo = Option(new DateTime(new Date()))))
//      val historicAddresses = historyAddressLinks.map(roadAddressLinkToRoadAddress(NoFloating)).map(_.copy(endDate = Option(new DateTime(new Date()))))
//
//      val roadLinks = Seq(
//        RoadLink(90000, linkGeom, GeometryUtils.geometryLength(linkGeom),
//          AdministrativeClass.apply(1), 99, TrafficDirection.BothDirections, UnknownLinkType,
//          Some("25.06.2015 03:00:00"), Some("vvh_modified"), Map("MUNICIPALITYCODE" -> BigInt.apply(749)),
//          InUse, NormalLinkInterface)
//      )
//
//      val changeTable = Seq(
//        createChangeTable(11111, 90000, ChangeType.CombinedModifiedPart, 0, 5.0, 0, 5.0, 1476478965000L),
//        createChangeTable(11112, 90000, ChangeType.CombinedRemovedPart, 0, 10.0, 5.0, 15.0, 1476478965000L),
//        createChangeTable(11113, 90000, ChangeType.CombinedRemovedPart, 0, 15.0, 15.0, 30.0, 1476478965000L)
//      )
//
//      RoadAddressDAO.create(currentAddresses++historicAddresses)
//      val newAddresses = roadAddressService.applyChanges(roadLinks, changeTable, historicAddresses)
//
//      newAddresses.flatMap(_.allSegments).size should be (historicAddresses.size)
//      newAddresses.flatMap(_.allSegments).foreach{a => a.linkId should be (roadLinks.head.linkId)}
//    }
//  }

  //TODO this will be implemented at VIITE-1536
//  test("Test change info on link 5622931 divided to 5622931, 5622953, 499914628 and 499914643") {
//    val n5622953Geom = Seq(Point(6734148, 332339), Point(6734173, 332309))
//    val n499914643Geom = Seq(Point(6734010, 332412), Point(6734148, 332339))
//    val n5622931Geom = Seq(Point(6733990, 332420), Point(6734010, 332412))
//    val n499914628Geom = Seq(Point(6733893, 332453), Point(6733990, 332420))
//
//    val o5622931Geom = n499914628Geom  ++ n5622931Geom ++ n499914643Geom ++ n5622953Geom
//    val o1Geom = Seq(Point(6734173, 332309-1984), Point(6734173,332309))
//
//    val roadwayNumber = 123
//
//    runWithRollback {
//      val oldAddressLinks = Seq(
//        createRoadAddressLink(Sequences.nextRoadwayId, 5622931, o5622931Geom, 92826, 3, 0, 1984, 2304, SideCode.AgainstDigitizing, Anomaly.None, false, true, roadwayNumber),
//        createRoadAddressLink(Sequences.nextRoadwayId, 1, o1Geom, 92826, 3, 0, 0, 1984, SideCode.TowardsDigitizing, Anomaly.None, true, false, roadwayNumber)
//      )
//
//      val addresses = oldAddressLinks.map(roadAddressLinkToRoadAddress(NoFloating))
//
//      val newLinks = Seq(
//        createRoadAddressLink(0, 1, o1Geom, 100, 1, 1, 1, 2, SideCode.TowardsDigitizing, Anomaly.None, false, false, roadwayNumber),
//        createRoadAddressLink(0, 499914628, n499914628Geom, 100, 1, 0, 2, 3, SideCode.TowardsDigitizing, Anomaly.None, false, false, roadwayNumber),
//        createRoadAddressLink(0, 499914643, n499914643Geom, 100, 1, 0, 3, 4, SideCode.TowardsDigitizing, Anomaly.None, false, false, roadwayNumber),
//        createRoadAddressLink(0, 5622931, n5622931Geom, 100, 1, 4, 4, 5, SideCode.TowardsDigitizing, Anomaly.None, false, false, roadwayNumber),
//        createRoadAddressLink(0, 5622953, n5622953Geom, 100, 1, 5, 5, 6, SideCode.TowardsDigitizing, Anomaly.None, false, false, roadwayNumber)
//      ).map(roadAddressLinkToRoadLink)
//      val changeTable = Seq(
//        createChangeTable(5622931, 5622931, ChangeType.DividedModifiedPart, 195.170, 216.710, 0.0, 21.541, 1476478965000L),
//        createChangeTable(5622931, 5622953, ChangeType.DividedNewPart, 0, 39.051, 0, 39.051, 1476478965000L),
//        createChangeTable(5622931, 499914628, ChangeType.DividedNewPart, 216.710, 319.170, 93.90506222, 103.78471484, 1476478965000L),
//        createChangeTable(5622931, 499914643, ChangeType.DividedNewPart, 39.051, 195.170, 0.0, 21.541, 1476478965000L)
//      )
//
//
//      RoadAddressDAO.create(addresses)
//      val newAddresses = roadAddressService.applyChanges(newLinks, changeTable, addresses).map(_.allSegments)
//      newAddresses should have size 5
//      newAddresses.flatten.find(_.linkId == 5622953).exists(_.calibrationPoints._2.nonEmpty) should be (true)
//      val flatList = newAddresses.flatten
//      flatList.count(_.calibrationPoints._2.nonEmpty) should be (1)
//      flatList.count(_.calibrationPoints._1.nonEmpty) should be (1)
//      flatList.count(_.startAddrMValue == 0) should be (1)
//      flatList.count(_.endAddrMValue == 2304) should be (1)
//
//      // Test that the range is continuous
//      flatList.flatMap(r => Seq(r.startAddrMValue, r.endAddrMValue)).filterNot(l => l == 0 || l == 2304).groupBy(l => l)
//        .values.forall(_.size == 2) should be (true)
//
//      // Test that the roadway_number is inherited correctly in split
//      flatList.forall(_.roadwayNumber == roadwayNumber) should be (true)
//    }
//  }

  //TODO this will be implemented at VIITE-1538
//  test("Fetch floating road addresses and validate list")  {
//    runWithRollback{
//      val existingFloatings = getFloatingCount()
//      val fetchedFloatings = roadAddressService.getFloatingAdresses()
//      existingFloatings should be (fetchedFloatings.size)
//    }
//  }


  //TODO this will be implemented at VIITE-1538
//  test("Mapping floating road addresses back to link work for historic addresses too") {
//    /*
//       Test that road address history is placed on links properly: history is moved, terminated address is not
//       Current address checks calculation isn't affected by historic. Now we need to also recalculate for floating history
//     */
//    val roadwayNumber = 123
//    runWithRollback {
//      val linkGeom1 = Seq(Point(0, 0), Point(1.0, 4.5))
//      val linkGeom2 = Seq(Point(1.0, 4.5), Point(12.5, 7.15))
//      val newGeom = Seq(Point(0, 0), Point(1.0, 5.0), Point(13.0, 7.0))
//
//      val history1Address = RoadAddress(NewRoadAddress, 199, 199, PublicRoad, Track.Combined, Continuous, 100L, 105L,
//        Some(DateTime.now().minusYears(15)), Some(DateTime.now().minusYears(10)), None, 123L, 0.0, 4.61, TowardsDigitizing,
//        84600L, (None, None), FloatingReason.ApplyChanges, linkGeom1, NormalLinkInterface, 20L, NoTermination, roadwayNumber)
//      val history2Address = RoadAddress(NewRoadAddress, 199, 199, PublicRoad, Track.Combined, Continuous, 105L, 116L,
//        Some(DateTime.now().minusYears(15)), Some(DateTime.now().minusYears(10)), None, 124L, 0.0, 11.801, TowardsDigitizing,
//        84600L, (None, None), FloatingReason.ApplyChanges, linkGeom2, NormalLinkInterface, 20L, NoTermination, roadwayNumber)
//      val current1Address = RoadAddress(NewRoadAddress, 199, 199, PublicRoad, Track.Combined, Continuous, 15L, 21L,
//        Some(DateTime.now().minusYears(10)), None, None, 123L, 0.0, 4.61, TowardsDigitizing,
//        84600L, (None, None), FloatingReason.ApplyChanges, linkGeom1, NormalLinkInterface, 20L, NoTermination, roadwayNumber)
//      val current2Address = RoadAddress(NewRoadAddress, 199, 199, PublicRoad, Track.Combined, Continuous, 21L, 35L,
//        Some(DateTime.now().minusYears(10)), None, None, 124L, 0.0, 11.801, TowardsDigitizing,
//        84600L, (None, None), FloatingReason.ApplyChanges, linkGeom2, NormalLinkInterface, 20L, NoTermination, roadwayNumber)
//      val terminatedAddress = RoadAddress(NewRoadAddress, 198, 201, PublicRoad, Track.Combined, Continuous, 10L, 15L,
//        Some(DateTime.now().minusYears(20)), Some(DateTime.now().minusYears(17)), None, 123L, 0.0, 4.61, AgainstDigitizing,
//        84600L, (None, None), FloatingReason.ApplyChanges, linkGeom1, NormalLinkInterface, 20L, Termination, roadwayNumber)
//
//      val surrounding1 = RoadAddress(NewRoadAddress, 199, 199, PublicRoad, Track.Combined, Continuous, 0L, 15L,
//        Some(DateTime.now().minusYears(10)), None, None, 121L, 0.0, 15.0, AgainstDigitizing,
//        84600L, (Some(CalibrationPoint(121L, 15.0, 0L)), None), NoFloating, Seq(Point(0, 0), Point(-14, 5.385)), NormalLinkInterface, 20L, NoTermination, roadwayNumber)
//      val surrounding2 = RoadAddress(NewRoadAddress, 199, 199, PublicRoad, Track.Combined, Continuous, 35L, 50L,
//        Some(DateTime.now().minusYears(10)), None, None, 125L, 0.0, 15.0, TowardsDigitizing,
//        84600L, (None, Some(CalibrationPoint(125L, 15.0, 50L))), NoFloating, Seq(Point(13.0, 7.0), Point(13.0, 22.0)), NormalLinkInterface, 20L, NoTermination, roadwayNumber)
//      RoadAddressDAO.create(Seq(history1Address, history2Address, current1Address, current2Address, terminatedAddress)) should have size (5)
//
//      val roadLinksSeq = Seq(RoadLink(123L, linkGeom1, 4.61, State, 99, BothDirections, UnknownLinkType,
//        Some("25.11.2013 02:00:00"), Some("vvh_modified"), Map("MUNICIPALITYCODE" -> BigInt(235))),
//        RoadLink(124L, linkGeom2, 11.801, State, 99, BothDirections, UnknownLinkType, Some("25.11.2013 02:00:00"),
//          Some("vvh_modified"), Map("MUNICIPALITYCODE" -> BigInt(235))),
//        RoadLink(456L, newGeom, 17.265, State, 99, BothDirections, UnknownLinkType, Some("25.11.2013 02:00:00"),
//          Some("vvh_modified"), Map("MUNICIPALITYCODE" -> BigInt(235))))
//      when(mockRoadLinkService.getCurrentAndHistoryRoadLinksFromVVH(Set(123L), false)).thenReturn(
//        (Seq[RoadLink](), roadLinksSeq.filter(_.linkId == 123L).map(toHistoryLink)))
//      when(mockRoadLinkService.getCurrentAndHistoryRoadLinksFromVVH(Set(124L), false)).thenReturn(
//        (Seq[RoadLink](), roadLinksSeq.filter(_.linkId == 124L).map(toHistoryLink)))
//      when(mockRoadLinkService.getCurrentAndHistoryRoadLinksFromVVH(Set(456L), false)).thenReturn(
//        (roadLinksSeq.filter(_.linkId == 456L), Seq[VVHHistoryRoadLink]()))
//      val postTransfer = roadAddressService.getRoadAddressesAfterCalculation(Seq("123", "124"), Seq("456"), User(1L, "k", Configuration()))
//      postTransfer should have size (2)
//      postTransfer.foreach { ra =>
//        ra.roadNumber should be(199L)
//        ra.roadPartNumber should be(199L)
//        (ra.track == Track.Combined || ra.startAddrMValue == 105) should be(true)
//        ra.terminated should be(NoTermination)
//      }
//      postTransfer.count(_.endDate.isEmpty) should be(1)
//      postTransfer.exists(ra => ra.startAddrMValue == 15L && ra.endAddrMValue == 35 && ra.endDate.isEmpty) should be(true)
//      postTransfer.forall(_.roadwayNumber == roadwayNumber) should be (true)
//      RoadAddressDAO.create(Seq(surrounding1, surrounding2)) should have size (2)
//      roadAddressService.transferFloatingToGap(Set(123L, 124L), Set(456L), postTransfer, "-")
//      val termRA = RoadAddressDAO.fetchByLinkId(Set(123L, 124L), true, true, true)
//      termRA should have size (1)
//      termRA.head.terminated should be(Termination)
//      val current = RoadAddressDAO.fetchByLinkId(Set(456L), true, true, true)
//      current should have size (2)
//      current.exists(ra => ra.startAddrMValue == 16L && ra.endAddrMValue == 34 && ra.endDate.isEmpty) should be(true)
//      current.forall(_.roadwayNumber == roadwayNumber) should be (true)
//    }
//  }

  //TODO this will be implemented at VIITE-1537
//  test("Check if roundabout is properly transferred") {
//    runWithRollback {
//      //Create of missing roundabout
//      sqlu"""INSERT INTO UNADDRESSED_ROAD_LINK VALUES(10473181, NULL, NULL, NULL, NULL, 1, 0, 19.58730362428591, MDSYS.SDO_GEOMETRY(4002, 3067, NULL, MDSYS.SDO_ELEM_INFO_ARRAY(1, 2, 1), MDSYS.SDO_ORDINATE_ARRAY(482196.119,6748045.455,0,0,482177.695,6748047.786,0,0)))""".execute
//      sqlu"""INSERT INTO UNADDRESSED_ROAD_LINK VALUES(10473188, NULL, NULL, NULL, NULL, 1, 0, 21.58614152331046, MDSYS.SDO_GEOMETRY(4002, 3067, NULL, MDSYS.SDO_ELEM_INFO_ARRAY(1, 2, 1), MDSYS.SDO_ORDINATE_ARRAY(482173.031,6748029.022,0,0,482177.695,6748047.786,0,0)))""".execute
//      sqlu"""INSERT INTO UNADDRESSED_ROAD_LINK VALUES(10473189, NULL, NULL, NULL, NULL, 1, 0, 21.882026111679927, MDSYS.SDO_GEOMETRY(4002, 3067, NULL, MDSYS.SDO_ELEM_INFO_ARRAY(1, 2, 1), MDSYS.SDO_ORDINATE_ARRAY(482194.821,6748025.538,0,0,482196.119,6748045.455,0,0)))""".execute
//      sqlu"""INSERT INTO UNADDRESSED_ROAD_LINK VALUES(10473190, NULL, NULL, NULL, NULL, 1, 0, 24.50478592842528, MDSYS.SDO_GEOMETRY(4002, 3067, NULL, MDSYS.SDO_ELEM_INFO_ARRAY(1, 2, 1), MDSYS.SDO_ORDINATE_ARRAY(482194.821,6748025.538,0,0,482173.031,6748029.022,0,0)))""".execute
//
//      //Create of existing roundabout
//      sqlu"""INSERT INTO ROADWAY VALUES(viite_general_seq.nextval, 24962, 1, 0, 5, 23, 50, TIMESTAMP '2017-10-01 00:00:00.000000', NULL, 'u001498', TIMESTAMP '2018-01-05 00:00:00.000000', 0, '1', MDSYS.SDO_GEOMETRY(4002, 3067, NULL, MDSYS.SDO_ELEM_INFO_ARRAY(1, 2, 1), MDSYS.SDO_ORDINATE_ARRAY(482194.039,6748026.255,0,0,482201.757,6748049.556,0,27)), NULL, 3, 3, 0, 190864630, 2, 0, 27.748, 10455861, 1514851200000, TIMESTAMP '2018-03-27 14:30:01.244275', 1, NULL)""".execute
//      sqlu"""INSERT INTO ROADWAY VALUES(viite_general_seq.nextval, 24962, 1, 0, 5, 0, 23, TIMESTAMP '2017-10-01 00:00:00.000000', NULL, 'u001498', TIMESTAMP '2018-01-05 00:00:00.000000', 2, '1', MDSYS.SDO_GEOMETRY(4002, 3067, NULL, MDSYS.SDO_ELEM_INFO_ARRAY(1, 2, 1), MDSYS.SDO_ORDINATE_ARRAY(482172.597,6748028.685,0,0,482194.039,6748026.255,0,23)), NULL, 3, 3, 0, 190864630, 3, 0, 22.525, 10455862, 1514851200000, TIMESTAMP '2018-03-27 14:30:01.244275', 1, NULL)""".execute
//      sqlu"""INSERT INTO ROADWAY VALUES(viite_general_seq.nextval, 24962, 1, 0, 1, 81, 107, TIMESTAMP '2017-10-01 00:00:00.000000', NULL, 'u001498', TIMESTAMP '2018-01-05 00:00:00.000000', 1, '1', MDSYS.SDO_GEOMETRY(4002, 3067, NULL, MDSYS.SDO_ELEM_INFO_ARRAY(1, 2, 1), MDSYS.SDO_ORDINATE_ARRAY(482174.312,6748050.985,0,0,482172.597,6748028.685,0,26)), NULL, 3, 3, 0, 190864630, 3, 0, 25.257, 10455860, 1514851200000, TIMESTAMP '2018-03-27 14:30:01.244275', 1,NULL)""".execute
//      sqlu"""INSERT INTO ROADWAY VALUES(viite_general_seq.nextval, 24962, 1, 0, 5, 50, 81, TIMESTAMP '2017-10-01 00:00:00.000000', NULL, 'u001498', TIMESTAMP '2018-01-05 00:00:00.000000', 0, '1', MDSYS.SDO_GEOMETRY(4002, 3067, NULL, MDSYS.SDO_ELEM_INFO_ARRAY(1, 2, 1), MDSYS.SDO_ORDINATE_ARRAY(482201.757,6748049.556,0,0,482174.312,6748050.985,0,31)), NULL, 3, 3, 0, 190864630, 2, 0, 31.002, 10455853, 1514851200000, TIMESTAMP '2018-03-27 14:30:01.244275', 1,NULL)""".execute
//
//      val sources = Seq(
//        RoadAddressLink(9033715, 10455853, Seq(Point(482201.757, 6748049.556, 71.82000000000698), Point(482197.326, 6748053.701, 71.80999999999767), Point(482188.388, 6748056.716, 71.5570000000007), Point(482178.981, 6748054.482, 71.3859999999986), Point(482174.3122952117, 6748050.985221108, 71.4389966489121)), 31.00200000023773, AdministrativeClass.apply(2), LinkType.apply(99), RoadLinkType.FloatingRoadLinkType, ConstructionType.UnknownConstructionType, LinkGeomSource.HistoryLinkInterface, MunicipalityStreetRoad, Some("Test"), Some("Test"), BigInt.apply(286), Some("10.04.2018"), Some("test"), Map(), 24962, 1, 0, 3, 5, 50, 81, "01.10.2017", "", 0.0, 31.002, SideCode.TowardsDigitizing, None, None, Anomaly.None, 190864630, None, false, floating = true),
//        RoadAddressLink(9033712, 10455861, Seq(Point(482194.039, 6748026.255, 71.43499999999767), Point(482199.613, 6748030.258, 71.1469999999972), Point(482203.33, 6748034.975, 71.18499999999767), Point(482203.759, 6748041.121, 71.45900000000256), Point(482203.187, 6748045.554, 71.62300000000687), Point(482201.7571678914, 6748049.555530138, 71.81997687090617)), 27.74799999969292, AdministrativeClass.apply(2), LinkType.apply(99), RoadLinkType.FloatingRoadLinkType, ConstructionType.UnknownConstructionType, LinkGeomSource.HistoryLinkInterface, MunicipalityStreetRoad, Some("test"), Some("test"), 286, Some("10.04.2018 23:00:14"), Some("test"), Map(), 24962, 1, 0, 3, 5, 23, 50, "01.10.2017", "", 0.0, 27.748, SideCode.TowardsDigitizing, None, None, Anomaly.None, 190864630, None, false, floating = true),
//        RoadAddressLink(9033714, 10455860, Seq(Point(482172.597, 6748028.685, 71.50900000000547), Point(482169.222, 6748034.962, 71.32099999999627), Point(482168.339, 6748039.959, 71.2390000000014), Point(482169.809, 6748046.486, 71.33599999999569), Point(482174.3119397538, 6748050.984939808, 71.4389986219502)), 25.257000000200556, Municipality, UnknownLinkType, FloatingRoadLinkType, UnknownConstructionType, HistoryLinkInterface, MunicipalityStreetRoad, Some("test"), Some("test"), 286, Some("10.04.2018 23:00:14"), Some("test"), Map(), 24962, 1, 0, 3, 1, 81, 107, "01.10.2017", "", 0.0, 25.257, AgainstDigitizing, None, Some(CalibrationPoint(10455860, 0.0, 107)), Anomaly.None, 190864630, None, false, floating = true),
//        RoadAddressLink(9033713, 10455862, Seq(Point(482194.039, 6748026.255, 71.43499999999767), Point(482187.606, 6748024.54, 71.08999999999651), Point(482179.458, 6748025.255, 71.23500000000058), Point(482175.456, 6748026.97, 71.3179999999993), Point(482172.597, 6748028.685, 71.50900000000547)), 22.524914383777997, Municipality, UnknownLinkType, FloatingRoadLinkType, UnknownConstructionType, HistoryLinkInterface, MunicipalityStreetRoad, Some("test"), Some("test"), BigInt.apply(286), Some("10.04.2018 23:00:14"), Some("test"), Map(), 24962, 1, 0, 3, 5, 0, 23, "01.10.2017", "", 0.0, 22.525, AgainstDigitizing, Some(CalibrationPoint(10455862, 22.525, 0)), None, Anomaly.None, 190864630, None, false, floating = true))
//
//      val target = Seq(
//        RoadAddressLink(0, 10473188, Seq(Point(482173.031, 6748029.022, 71.51399999999558), Point(482170.778, 6748037.771, 71.35599999999977), Point(482173.542, 6748044.438, 71.42699999999604), Point(482177.695, 6748047.786, 71.49099999999453)), 21.58614152331046, AdministrativeClass.apply(2), LinkType.apply(99), UnknownRoadLinkType, InUse, NormalLinkInterface, MunicipalityStreetRoad, Some("test"), Some(""), 286, Some("10.04.2018 23:00:14"), Some("test"), Map(), 0, 0, 99, 3, 5, 0, 0, "", "", 0.0, 21.58614152331046, SideCode.Unknown, None, None, NoAddressGiven, 0, None, false),
//        RoadAddressLink(0, 10473190, Seq(Point(482194.821, 6748025.538, 71.5), Point(482193.71, 6748024.668, 71.32700000000477), Point(482186.134, 6748022.469, 71.02700000000186), Point(482180.138, 6748023.264, 71.12799999999697), Point(482173.118, 6748028.88, 71.50199999999313), Point(482173.031, 6748029.022, 71.51399999999558)), 24.50478592842528, AdministrativeClass.apply(2), LinkType.apply(99), UnknownRoadLinkType, InUse, NormalLinkInterface, MunicipalityStreetRoad, Some("test"), Some(""), 286, Some("10.04.2018 23:00:14"), Some("test"), Map(), 0, 0, 99, 3, 5, 0, 0, "", "", 0.0, 24.50478592842528, SideCode.Unknown, None, None, NoAddressGiven, 0, None, false),
//        RoadAddressLink(0, 10473189, Seq(Point(482194.821, 6748025.538, 71.5), Point(482199.325, 6748033.093, 71.2100000000064), Point(482197.655, 6748043.768, 71.61000000000058), Point(482196.119, 6748045.455, 71.69199999999546)), 21.882026111679927, AdministrativeClass.apply(2), LinkType.apply(99), UnknownRoadLinkType, InUse, NormalLinkInterface, MunicipalityStreetRoad, Some("test"), Some(""), 286, Some("10.04.2018 23:00:14"), Some("test"), Map(), 0, 0, 99, 3, 5, 0, 0, "", "", 0.0, 21.882026111679927, SideCode.Unknown, None, None, NoAddressGiven, 0, None, false),
//        RoadAddressLink(0, 10473181, Seq(Point(482196.119, 6748045.455, 71.69199999999546), Point(482192.43, 6748047.921, 71.72599999999511), Point(482187.626, 6748049.472, 71.67399999999907), Point(482180.138, 6748048.536, 71.53800000000047), Point(482177.695, 6748047.786, 71.49099999999453)), 19.58730362428591, AdministrativeClass.apply(2), UnknownLinkType, UnknownRoadLinkType, InUse, NormalLinkInterface, MunicipalityStreetRoad, Some("test"), Some(""), 286, Some("10.04.2018 23:00:14"), Some("test"), Map(), 0, 0, 99, 3, 5, 0, 0, "", "", 0.0, 19.58730362428591, SideCode.Unknown, None, None, NoAddressGiven, 0, None, false)
//      )
//
//      val transferred = roadAddressService.transferRoadAddress(sources, target, User(1, "Test User", Configuration())).sortBy(_.startAddrMValue)
//      transferred.size should be(4)
//      transferred.head.startAddrMValue should be(0)
//      transferred.last.endAddrMValue should be (107)
//    }
//  }

  //TODO this will be implemented at VIITE-1538
//  test("Check correct construction of floating links") {
//    val attributesMap = Map("MUNICIPALITYCODE" -> BigInt.apply(99999))
//    val mockRoadLink = RoadLink(123456789, Seq(Point(0.0, 0.0), Point(10.0, 10.0)), 14.1, AdministrativeClass.apply(2), 1, TrafficDirection.TowardsDigitizing, LinkType.apply(1), None, None, attributesMap)
//    val floatingAddress = RoadAddress(9988, 75, 2, RoadType.Unknown, Track.Combined, Discontinuity.Continuous, 3532, 3598, None, None, Some("tr"),
//      123456789, 0.0, 65.259, SideCode.TowardsDigitizing, 0, (None, None), FloatingReason.ApplyChanges, Seq(Point(0.0, 0.0), Point(11.0, 11.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0)
//
//    runWithRollback {
//      val returnedFloatingAddressees = roadAddressService.buildFloatingAddresses(Seq(mockRoadLink), Seq.empty[VVHRoadlink], Seq(floatingAddress))
//      returnedFloatingAddressees.size should be(1)
//      returnedFloatingAddressees.head.id should be(floatingAddress.id)
//      returnedFloatingAddressees.head.linkId should be(mockRoadLink.linkId)
//      returnedFloatingAddressees.head.anomaly should be(Anomaly.None)
//      returnedFloatingAddressees.head.roadLinkType should be(RoadLinkType.FloatingRoadLinkType)
//      returnedFloatingAddressees.head.administrativeClass should be(mockRoadLink.administrativeClass)
//      returnedFloatingAddressees.head.geometry should be(mockRoadLink.geometry)
//    }
//  }

  //TODO this will be implemented at VIITE-1538
//  test("getAdjacents should return correct adjacents in fork geometry based on the existing of missing") {
//    val baseLinkId = 12345L
//    val roadAddressService = new RoadAddressService(mockRoadLinkService, mockEventBus)
//
//    val ra = RoadAddress(-1000, 75, 2, RoadType.Unknown, Track.Combined, Discontinuity.Continuous, 3532, 3598, Some(DateTime.now.minusDays(5)), None, Some("tr"),
//      baseLinkId, 0.0, 65.259, SideCode.TowardsDigitizing, 0, (None, None), FloatingReason.ApplyChanges, Seq(Point(0.0, 0.0, 0.0), Point(5.0, 5.0, 0.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0)
//
//    val ra2 = RoadAddress(-1000, 75, 2, RoadType.Unknown, Track.Combined, Discontinuity.Continuous, 3533, 3599, Some(DateTime.now.minusDays(2)), None, Some("tr"),
//      baseLinkId+2L, 0.0, 60.259, SideCode.TowardsDigitizing, 0, (None, None), FloatingReason.ApplyChanges, Seq(Point(0.0, 0.0, 0.0), Point(5.0, 25.0, 0.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0)
//
//    val roadLink1 = RoadLink(baseLinkId, Seq(Point(0.0, 0.0, 0.0), Point(5.0, 5.0, 0.0))
//      , 540.3960283713503, State, 99, TrafficDirection.AgainstDigitizing, UnknownLinkType, Some("25.06.2015 03:00:00"), Some("vvh_modified"), Map("MUNICIPALITYCODE" -> BigInt.apply(749)),
//      InUse, NormalLinkInterface)
//
//    val roadLink2 = RoadLink(baseLinkId + 1L, Seq(Point(5.0, 5.0, 0.0), Point(10.0, 10.0, 0.0))
//      , 540.3960283713503, State, 99, TrafficDirection.AgainstDigitizing, UnknownLinkType, Some("25.06.2015 03:00:00"), Some("vvh_modified"), Map("MUNICIPALITYCODE" -> BigInt.apply(749)),
//      InUse, NormalLinkInterface)
//
//    val roadLink3 = RoadLink(baseLinkId + 2L, Seq(Point(5.0, 5.0, 0.0), Point(5.0, 15.0, 0.0))
//      , 540.3960283713503, State, 99, TrafficDirection.AgainstDigitizing, UnknownLinkType, Some("25.06.2015 03:00:00"), Some("vvh_modified"), Map("MUNICIPALITYCODE" -> BigInt.apply(749)),
//      InUse, NormalLinkInterface)
//
//
//    when(mockRoadLinkService.getRoadLinksByLinkIdsFromVVH(any[Set[Long]], any[Boolean])).thenReturn(Seq(roadLink1))
//    when(mockRoadLinkService.getRoadLinksAndChangesFromVVHWithFrozenAPI(any[BoundingRectangle], any[Boolean])).thenReturn((Seq(roadLink2, roadLink3), Seq.empty[ChangeInfo]))
//
//    val returnedAdjacents = runWithRollback {
//      RoadAddressDAO.create(Seq(ra))
//      RoadAddressDAO.createUnaddressedRoadLink(
//        UnaddressedRoadLink(baseLinkId+1L, None, None, RoadType.PublicRoad, None, None, None, Some(7.1), Anomaly.NoAddressGiven, Seq(Point(5.0, 5.0, 0.0), Point(10.0, 10.0, 0.0)))
//      )
//      RoadAddressDAO.createUnaddressedRoadLink(
//        UnaddressedRoadLink(baseLinkId+2L, None, None, RoadType.PublicRoad, None, None, None, Some(10.0), Anomaly.GeometryChanged, Seq(Point(5.0, 5.0, 0.0), Point(5.0, 15.0, 0.0)))
//      )
//
//      roadAddressService.getAdjacent(Set(baseLinkId), baseLinkId, false)
//    }
//    returnedAdjacents.size should be (2)
//    returnedAdjacents.map(_.linkId) should contain allOf (baseLinkId+1L, baseLinkId+2L)
//  }
//
//  test("getAdjacents should return correct adjacents in chain geometry (each one adjacent to next one) based on the existing of missing") {
//    val baseLinkId = 12345L
//    val roadAddressService = new RoadAddressService(mockRoadLinkService, mockEventBus)
//
//    val ra = RoadAddress(-1000, 75, 2, RoadType.Unknown, Track.Combined, Discontinuity.Continuous, 3532, 3598, Some(DateTime.now.minusDays(5)), None, Some("tr"),
//      baseLinkId, 0.0, 65.259, SideCode.TowardsDigitizing, 0, (None, None), FloatingReason.ApplyChanges, Seq(Point(0.0, 0.0, 0.0), Point(5.0, 5.0, 0.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0)
//
//    val ra2 = RoadAddress(-1000, 75, 2, RoadType.Unknown, Track.Combined, Discontinuity.Continuous, 3533, 3599, Some(DateTime.now.minusDays(2)), None, Some("tr"),
//      baseLinkId+2L, 0.0, 60.259, SideCode.TowardsDigitizing, 0, (None, None), FloatingReason.ApplyChanges, Seq(Point(0.0, 0.0, 0.0), Point(5.0, 25.0, 0.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0)
//
//    val roadLink1 = RoadLink(baseLinkId, Seq(Point(0.0, 0.0, 0.0), Point(5.0, 5.0, 0.0))
//      , 540.3960283713503, State, 99, TrafficDirection.AgainstDigitizing, UnknownLinkType, Some("25.06.2015 03:00:00"), Some("vvh_modified"), Map("MUNICIPALITYCODE" -> BigInt.apply(749)),
//      InUse, NormalLinkInterface)
//
//    val roadLink2 = RoadLink(baseLinkId + 1L, Seq(Point(5.0, 5.0, 0.0), Point(10.0, 10.0, 0.0))
//      , 540.3960283713503, State, 99, TrafficDirection.AgainstDigitizing, UnknownLinkType, Some("25.06.2015 03:00:00"), Some("vvh_modified"), Map("MUNICIPALITYCODE" -> BigInt.apply(749)),
//      InUse, NormalLinkInterface)
//
//    val roadLink3 = RoadLink(baseLinkId + 2L, Seq(Point(10, 10, 0.0), Point(15.0, 15.0, 0.0))
//      , 540.3960283713503, State, 99, TrafficDirection.AgainstDigitizing, UnknownLinkType, Some("25.06.2015 03:00:00"), Some("vvh_modified"), Map("MUNICIPALITYCODE" -> BigInt.apply(749)),
//      InUse, NormalLinkInterface)
//
//
//    when(mockRoadLinkService.getRoadLinksByLinkIdsFromVVH(any[Set[Long]], any[Boolean])).thenReturn(Seq(roadLink1))
//    when(mockRoadLinkService.getRoadLinksAndChangesFromVVHWithFrozenAPI(any[BoundingRectangle], any[Boolean])).thenReturn((Seq(roadLink1, roadLink2), Seq.empty[ChangeInfo]))
//
//    runWithRollback {
//      RoadAddressDAO.create(Seq(ra))
//      RoadAddressDAO.createUnaddressedRoadLink(
//        UnaddressedRoadLink(baseLinkId+1L, None, None, RoadType.PublicRoad, None, None, None, Some(7.1), Anomaly.NoAddressGiven, Seq(Point(5.0, 5.0, 0.0), Point(10.0, 10.0, 0.0)))
//      )
//      RoadAddressDAO.createUnaddressedRoadLink(
//        UnaddressedRoadLink(baseLinkId+2L, None, None, RoadType.PublicRoad, None, None, None, Some(7.1), Anomaly.GeometryChanged, Seq(Point(10.0, 10.0, 0.0), Point(15.0, 15.0, 0.0)))
//      )
//      val returnedAdjacents1 = roadAddressService.getAdjacent(Set(baseLinkId), baseLinkId, false)
//      returnedAdjacents1.size should be (1)
//      returnedAdjacents1.map(_.linkId).head should be (baseLinkId+1L)
//      when(mockRoadLinkService.getRoadLinksByLinkIdsFromVVH(any[Set[Long]], any[Boolean])).thenReturn(Seq(roadLink2))
//      when(mockRoadLinkService.getRoadLinksAndChangesFromVVHWithFrozenAPI(any[BoundingRectangle], any[Boolean])).thenReturn((Seq(roadLink2, roadLink3), Seq.empty[ChangeInfo]))
//      val returnedAdjacents2 = roadAddressService.getAdjacent(Set(baseLinkId+1), baseLinkId+1, false)
//      returnedAdjacents2.size should be (1)
//      returnedAdjacents2.map(_.linkId).head should be (baseLinkId+2L)
//    }
//
//  }

//  test("getAdjacentAddressesWithoutTX should not return addresses that are already selected by ") {
//    val selectedId = 741L
//    val selectedLinkId = 852L
//    val selectedGeom = List(Point(10.0, 10.0, 0.0), Point(10.0, 10.0, 0.0))
//    val (id1, id2) = (456L, 789L)
//    val (linkId1, linkId2) = (987L, 654L)
//    val (geom1, geom2) = (List(Point(0.0, 0.0, 0.0), Point(10.0, 10.0, 0.0)), List(Point(10.0, 10.0, 0.0), Point(20.0, 20.0, 0.0)))
//    val (roadNumber, roadPartNumber) = (99, 1)
//
//    val selectedRoadAddress = RoadAddress(selectedId, roadNumber, roadPartNumber, RoadType.Unknown, Track.Combined, Discontinuity.Continuous, 10, 20, Some(DateTime.now()), None, Some("tr"),
//      selectedLinkId, 0.0, 65.259, SideCode.TowardsDigitizing, 0, (None, None), FloatingReason.ApplyChanges, selectedGeom, LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0)
//
//    val roadAddress1 = RoadAddress(id1, roadNumber, roadPartNumber, RoadType.Unknown, Track.Combined, Discontinuity.Continuous, 0, 10, Some(DateTime.now()), None, Some("tr"),
//      linkId1, 0.0, 65.259, SideCode.TowardsDigitizing, 0, (None, None), FloatingReason.ApplyChanges, geom1, LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0)
//
//    val roadAddress2 = RoadAddress(id2, roadNumber, roadPartNumber, RoadType.Unknown, Track.Combined, Discontinuity.Continuous, 20, 30, Some(DateTime.now()), None, Some("tr"),
//      linkId2, 0.0, 65.259, SideCode.TowardsDigitizing, 0, (None, None), FloatingReason.ApplyChanges, geom1, LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0)
//
//    runWithRollback{
//      RoadAddressDAO.create(Seq(selectedRoadAddress, roadAddress1, roadAddress2))
//      val returnedAdjacents = roadAddressService.getAdjacentAddressesInTX(Set.empty[Long], Set(selectedId, id1), selectedLinkId, selectedId, roadNumber, roadPartNumber, Track.Combined)
//      returnedAdjacents.size should be (1)
//      returnedAdjacents.map(ra => (ra.id, ra.linkId, ra.roadNumber, ra.roadPartNumber)).head should be (Seq(roadAddress2).map(ra => (ra.id, ra.linkId, ra.roadNumber, ra.roadPartNumber)).head)
//    }
//  }

//  test("getAdjacents should return correct adjacents in chain geometry (each one adjacent to next one) based on the existing of missing") {
//    val baseLinkId = 12345L
//    val roadAddressService = new RoadAddressService(mockRoadLinkService, mockEventBus)
//
//    val ra = RoadAddress(-1000, 75, 2, RoadType.Unknown, Track.Combined, Discontinuity.Continuous, 3532, 3598, Some(DateTime.now.minusDays(5)), None, Some("tr"),
//      baseLinkId, 0.0, 65.259, SideCode.TowardsDigitizing, 0, (None, None), FloatingReason.ApplyChanges, Seq(Point(0.0, 0.0, 0.0), Point(5.0, 5.0, 0.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0)
//
//    val ra2 = RoadAddress(-1000, 75, 2, RoadType.Unknown, Track.Combined, Discontinuity.Continuous, 3533, 3599, Some(DateTime.now.minusDays(2)), None, Some("tr"),
//      baseLinkId+2L, 0.0, 60.259, SideCode.TowardsDigitizing, 0, (None, None), FloatingReason.ApplyChanges, Seq(Point(0.0, 0.0, 0.0), Point(5.0, 25.0, 0.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0)
//
//    val roadLink1 = RoadLink(baseLinkId, Seq(Point(0.0, 0.0, 0.0), Point(5.0, 5.0, 0.0))
//      , 540.3960283713503, State, 99, TrafficDirection.AgainstDigitizing, UnknownLinkType, Some("25.06.2015 03:00:00"), Some("vvh_modified"), Map("MUNICIPALITYCODE" -> BigInt.apply(749)),
//      InUse, NormalLinkInterface)
//
//    val roadLink2 = RoadLink(baseLinkId + 1L, Seq(Point(5.0, 5.0, 0.0), Point(10.0, 10.0, 0.0))
//      , 540.3960283713503, State, 99, TrafficDirection.AgainstDigitizing, UnknownLinkType, Some("25.06.2015 03:00:00"), Some("vvh_modified"), Map("MUNICIPALITYCODE" -> BigInt.apply(749)),
//      InUse, NormalLinkInterface)
//
//    val roadLink3 = RoadLink(baseLinkId + 2L, Seq(Point(10, 10, 0.0), Point(15.0, 15.0, 0.0))
//      , 540.3960283713503, State, 99, TrafficDirection.AgainstDigitizing, UnknownLinkType, Some("25.06.2015 03:00:00"), Some("vvh_modified"), Map("MUNICIPALITYCODE" -> BigInt.apply(749)),
//      InUse, NormalLinkInterface)
//
//
//    when(mockRoadLinkService.getRoadLinksByLinkIdsFromVVH(any[Set[Long]], any[Boolean])).thenReturn(Seq(roadLink1))
//    when(mockRoadLinkService.getRoadLinksAndChangesFromVVHWithFrozenAPI(any[BoundingRectangle], any[Boolean])).thenReturn((Seq(roadLink1, roadLink2), Seq.empty[ChangeInfo]))
//
//    runWithRollback {
//      RoadAddressDAO.create(Seq(ra,ra2))
//      RoadAddressDAO.createUnaddressedRoadLink(
//        UnaddressedRoadLink(baseLinkId+1L, None, None, RoadType.PublicRoad, None, None, None, Some(7.1), Anomaly.NoAddressGiven, Seq(Point(5.0, 5.0, 0.0), Point(10.0, 10.0, 0.0)))
//      )
//      RoadAddressDAO.createUnaddressedRoadLink(
//        UnaddressedRoadLink(baseLinkId+2L, None, None, RoadType.PublicRoad, None, None, None, Some(7.1), Anomaly.GeometryChanged, Seq(Point(10.0, 10.0, 0.0), Point(15.0, 15.0, 0.0)))
//      )
//    val returnedAdjacents1 = roadAddressService.getAdjacent(Set(baseLinkId), baseLinkId, false)
//      returnedAdjacents1.size should be (1)
//      returnedAdjacents1.map(_.linkId).head should be (baseLinkId+1L)
//      when(mockRoadLinkService.getRoadLinksByLinkIdsFromVVH(any[Set[Long]], any[Boolean])).thenReturn(Seq(roadLink2))
//      when(mockRoadLinkService.getRoadLinksAndChangesFromVVHWithFrozenAPI(any[BoundingRectangle], any[Boolean])).thenReturn((Seq(roadLink2, roadLink3), Seq.empty[ChangeInfo]))
//      val returnedAdjacents2 = roadAddressService.getAdjacent(Set(baseLinkId+1), baseLinkId+1, false)
//      returnedAdjacents2.size should be (1)
//      returnedAdjacents2.map(_.linkId).head should be (baseLinkId+2L)
//    }
//
//  }
//
//  test("getAdjacentAddressesWithoutTX should not return addresses that are already selected by ") {
//  val selectedId = 741L
//  val selectedLinkId = 852L
//  val selectedGeom = List(Point(10.0, 10.0, 0.0), Point(10.0, 10.0, 0.0))
//  val (id1, id2) = (456L, 789L)
//  val (linkId1, linkId2) = (987L, 654L)
//  val (geom1, geom2) = (List(Point(0.0, 0.0, 0.0), Point(10.0, 10.0, 0.0)), List(Point(10.0, 10.0, 0.0), Point(20.0, 20.0, 0.0)))
//  val (roadNumber, roadPartNumber) = (99, 1)
//
//  val selectedRoadAddress = RoadAddress(selectedId, roadNumber, roadPartNumber, RoadType.Unknown, Track.Combined, Discontinuity.Continuous, 10, 20, Some(DateTime.now()), None, Some("tr"),
//  selectedLinkId, 0.0, 65.259, SideCode.TowardsDigitizing, 0, (None, None), FloatingReason.ApplyChanges, selectedGeom, LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0)
//
//  val roadAddress1 = RoadAddress(id1, roadNumber, roadPartNumber, RoadType.Unknown, Track.Combined, Discontinuity.Continuous, 0, 10, Some(DateTime.now()), None, Some("tr"),
//  linkId1, 0.0, 65.259, SideCode.TowardsDigitizing, 0, (None, None), FloatingReason.ApplyChanges, geom1, LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0)
//
//  val roadAddress2 = RoadAddress(id2, roadNumber, roadPartNumber, RoadType.Unknown, Track.Combined, Discontinuity.Continuous, 20, 30, Some(DateTime.now()), None, Some("tr"),
//  linkId2, 0.0, 65.259, SideCode.TowardsDigitizing, 0, (None, None), FloatingReason.ApplyChanges, geom1, LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0)
//
//  runWithRollback{
//  RoadAddressDAO.create(Seq(selectedRoadAddress, roadAddress1, roadAddress2))
//  val returnedAdjacents = roadAddressService.getAdjacentAddressesInTX(Set.empty[Long], Set(selectedId, id1), selectedLinkId, selectedId, roadNumber, roadPartNumber, Track.Combined)
//  returnedAdjacents.size should be (1)
//  returnedAdjacents.map(ra => (ra.id, ra.linkId, ra.roadNumber, ra.roadPartNumber)).head should be (Seq(roadAddress2).map(ra => (ra.id, ra.linkId, ra.roadNumber, ra.roadPartNumber)).head)
//}
//}
//
//  test("check correct fetching of road address via ID") {
//  val baseLinkId = 12345L
//  val ra = RoadAddress(-1000, 75, 2, RoadType.Unknown, Track.Combined, Discontinuity.Continuous, 3532, 3598, Some(DateTime.now.minusDays(5)), None, Some("tr"),
//  baseLinkId, 0.0, 65.259, SideCode.TowardsDigitizing, 0, (None, None), FloatingReason.ApplyChanges, Seq(Point(0.0, 0.0, 0.0), Point(5.0, 5.0, 0.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0)
//
//  val roadLink1 = RoadLink(baseLinkId, Seq(Point(0.0, 0.0, 0.0), Point(5.0, 5.0, 0.0))
//  , 540.3960283713503, State, 99, TrafficDirection.AgainstDigitizing, UnknownLinkType, Some("25.06.2015 03:00:00"), Some("vvh_modified"), Map("MUNICIPALITYCODE" -> BigInt.apply(749)),
//  InUse, NormalLinkInterface)
//
//  when(mockRoadLinkService.getCurrentAndHistoryRoadLinksFromVVH(any[Set[Long]], any[Boolean])).thenReturn((Seq(roadLink1), Seq(toHistoryLink(ra))))
//
//  runWithRollback {
//  val createdId =  RoadAddressDAO.create(Seq(ra)).head
//  val returnedAddresses = roadAddressService.getRoadAddressLinkById(createdId)
//  returnedAddresses.size should be (1)
//  returnedAddresses.head.id should be (createdId)
//  returnedAddresses.head.linkId should be (baseLinkId)
//}
//}

//  TODO this will be implemented at VIITE-1538
//  test("getFloatingAdjacents should return adjacent floatings based on the adjacency of it's history links") {
//  val baseLinkId = 12345L
//  val roadNumber = 9999L
//  val roadPartNumber = 1L
//
//  val geom1 = Seq(Point(0.0, 0.0, 0.0), Point(5.0, 5.0, 0.0))
//  val geom2 = Seq(Point(5.0, 5.0, 0.0), Point(10.0, 10.0, 0.0))
//  val geom3 = Seq(Point(25.0, 25.0, 0.0), Point(30.0, 30.0, 0.0))
//
//  val ra1 = RoadAddress(-1000, roadNumber, roadPartNumber, RoadType.Unknown, Track.Combined, Discontinuity.Continuous, 0, 5, Some(DateTime.now.minusDays(5)), None, Some("tr"),
//  baseLinkId, 0.0, 65.259, SideCode.TowardsDigitizing, 0, (None, None), FloatingReason.ApplyChanges, geom1, LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0)
//  val ra2 = RoadAddress(-1000, roadNumber, roadPartNumber, RoadType.Unknown, Track.Combined, Discontinuity.Continuous, 5, 10, Some(DateTime.now.minusDays(5)), None, Some("tr"),
//  baseLinkId+1, 0.0, 65.259, SideCode.TowardsDigitizing, 0, (None, None), FloatingReason.ApplyChanges, geom2, LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0)
//  val ra3 = RoadAddress(-1000, roadNumber, roadPartNumber, RoadType.Unknown, Track.Combined, Discontinuity.Continuous, 25, 30, Some(DateTime.now.minusDays(5)), None, Some("tr"),
//  baseLinkId+2, 0.0, 65.259, SideCode.TowardsDigitizing, 0, (None, None), FloatingReason.ApplyChanges, geom3, LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0)
//
//  val roadLink1 = RoadLink(baseLinkId, geom1, 540.3960283713503,
//  State, 99, TrafficDirection.AgainstDigitizing, UnknownLinkType, Some("25.06.2015 03:00:00"), Some("vvh_modified"), Map("MUNICIPALITYCODE" -> BigInt.apply(749)),
//  InUse, NormalLinkInterface)
//
//  val roadLink2 = RoadLink(baseLinkId+1, geom2, 540.3960283713503,
//  State, 99, TrafficDirection.AgainstDigitizing, UnknownLinkType, Some("25.06.2015 03:00:00"), Some("vvh_modified"), Map("MUNICIPALITYCODE" -> BigInt.apply(749)),
//  InUse, NormalLinkInterface)
//
//  val roadLink3 = RoadLink(baseLinkId+2, geom3, 540.3960283713503,
//  State, 99, TrafficDirection.AgainstDigitizing, UnknownLinkType, Some("25.06.2015 03:00:00"), Some("vvh_modified"), Map("MUNICIPALITYCODE" -> BigInt.apply(749)),
//  InUse, NormalLinkInterface)
//
//  when(mockRoadLinkService.getRoadLinksHistoryFromVVH(any[Set[Long]])).thenReturn(
//  (Seq(roadLink1, roadLink2, roadLink3).map(toHistoryLink))
//  )
//  runWithRollback{
//  RoadAddressDAO.create(Seq(ra1, ra2, ra3))
//  val created = RoadAddressDAO.fetchByRoadPart(roadNumber, roadPartNumber, includeFloating = true)
//  val mySelected = created.find(_.linkId == baseLinkId+1).get
//  val result = roadAddressService.getFloatingAdjacent(Set(mySelected.linkId), Set(mySelected.id), mySelected.linkId, mySelected.id, roadNumber, roadPartNumber, Track.Combined.value)
//  result.size should be (1)
//  result.head.linkId should be(baseLinkId)
//  result.head.geometry should be (geom1)
//  GeometryUtils.areAdjacent(mySelected.geometry, result.head.geometry) should be (true)
//
//}
//}

//  private def createRoadAddressLink(roadwayId: Long, linearLocationId: Long, linkId: Long, geom: Seq[Point], roadNumber: Long, roadPartNumber: Long, trackCode: Long,
//                                    startAddressM: Long, endAddressM: Long, sideCode: SideCode, anomaly: Anomaly, startCalibrationPoint: Boolean = false,
//                                    endCalibrationPoint: Boolean = false, roadwayNumber: Long = 0) = {
//    val length = GeometryUtils.geometryLength(geom)
//    RoadAddressLink(roadwayId, linearLocationId, linkId, geom, length, State, LinkType.apply(1),
//      ConstructionType.InUse, NormalLinkInterface, RoadType.PublicRoad, Some("Vt5"), None, BigInt(0), None, None, Map(), roadNumber, roadPartNumber,
//      trackCode, 1, 5, startAddressM, endAddressM, "2016-01-01", "", 0.0, GeometryUtils.geometryLength(geom), sideCode,
//      if (startCalibrationPoint) { Option(CalibrationPoint(linkId, if (sideCode == SideCode.TowardsDigitizing) 0.0 else length, startAddressM))} else None,
//      if (endCalibrationPoint) { Option(CalibrationPoint(linkId, if (sideCode == SideCode.AgainstDigitizing) 0.0 else length, endAddressM))} else None,
//      anomaly, roadwayNumber)
//
//  }

//  private def toHistoryLink(rl: RoadLink): VVHHistoryRoadLink = {
//    VVHHistoryRoadLink(rl.linkId, rl.municipalityCode, rl.geometry, rl.administrativeClass, rl.trafficDirection,
//      FeatureClass.AllOthers, 84600, 86400, rl.attributes)
//  }

//  private def toHistoryLink(ra: RoadAddress): VVHHistoryRoadLink = {
//    VVHHistoryRoadLink(ra.linkId, 0, ra.geometry, State, TrafficDirection.TowardsDigitizing,
//      FeatureClass.AllOthers, 84600, 86400, Map.empty[String, Any])
//  }
}

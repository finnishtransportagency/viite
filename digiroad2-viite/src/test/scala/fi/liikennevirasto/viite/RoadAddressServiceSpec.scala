package fi.liikennevirasto.viite

import fi.liikennevirasto.digiroad2.asset.ConstructionType.UnknownConstructionType
import fi.liikennevirasto.digiroad2.asset.LinkGeomSource.NormalLinkInterface
import fi.liikennevirasto.digiroad2.asset.SideCode.{AgainstDigitizing, TowardsDigitizing}
import fi.liikennevirasto.digiroad2.asset._
import fi.liikennevirasto.digiroad2.client.vvh._
import fi.liikennevirasto.digiroad2.dao.Sequences
import fi.liikennevirasto.digiroad2.linearasset.RoadLink
import fi.liikennevirasto.digiroad2.postgis.PostGISDatabase
import fi.liikennevirasto.digiroad2.service.RoadLinkService
import fi.liikennevirasto.digiroad2.util.Track
import fi.liikennevirasto.digiroad2.{DigiroadEventBus, Point}
import fi.liikennevirasto.viite.Dummies._
import fi.liikennevirasto.viite.dao.TerminationCode.NoTermination
import fi.liikennevirasto.viite.dao.{ProjectReservedPartDAO, _}
import fi.liikennevirasto.viite.process._
import fi.liikennevirasto.viite.util.CalibrationPointsUtils
import org.joda.time.DateTime
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito._
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{FunSuite, Matchers}
import slick.driver.JdbcDriver.backend.Database
import slick.driver.JdbcDriver.backend.Database.dynamicSession

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class RoadAddressServiceSpec extends FunSuite with Matchers{
  val mockRoadLinkService: RoadLinkService = MockitoSugar.mock[RoadLinkService]
  val mockEventBus: DigiroadEventBus = MockitoSugar.mock[DigiroadEventBus]
  val mockLinearLocationDAO: LinearLocationDAO = MockitoSugar.mock[LinearLocationDAO]
  val mockRoadwayDAO: RoadwayDAO = MockitoSugar.mock[RoadwayDAO]
  val mockRoadNetworkDAO: RoadNetworkDAO = MockitoSugar.mock[RoadNetworkDAO]
  val mockRoadAddressService = MockitoSugar.mock[RoadAddressService]
  val mockRoadwayPointDAO: RoadwayPointDAO = MockitoSugar.mock[RoadwayPointDAO]
  val mockNodePointDAO = MockitoSugar.mock[NodePointDAO]
  val mockJunctionPointDAO = MockitoSugar.mock[JunctionPointDAO]

  val roadwayAddressMappper = new RoadwayAddressMapper(mockRoadwayDAO, mockLinearLocationDAO)
  val projectDAO = new ProjectDAO
  val projectReservedPartDAO = new ProjectReservedPartDAO
  val projectLinkDAO = new ProjectLinkDAO
  val roadwayPointDAO = new RoadwayPointDAO
  val nodeDAO = new NodeDAO
  val nodePointDAO = new NodePointDAO
  val junctionDAO = new JunctionDAO
  val junctionPointDAO = new JunctionPointDAO
  val roadwayChangesDAO = new RoadwayChangesDAO
  val roadwayDAO = new RoadwayDAO
  val linearLocationDAO = new LinearLocationDAO
  val roadwayAddressMapper = new RoadwayAddressMapper(roadwayDAO, linearLocationDAO)
  val mockViiteVkmClient: ViiteVkmClient = MockitoSugar.mock[ViiteVkmClient]
  val roadAddressService: RoadAddressService = new RoadAddressService(mockRoadLinkService, mockRoadwayDAO, mockLinearLocationDAO,
    mockRoadNetworkDAO, roadwayPointDAO, nodePointDAO, junctionPointDAO, roadwayAddressMappper, mockEventBus, frozenVVH = false) {

    override def withDynSession[T](f: => T): T = f
    override def withDynTransaction[T](f: => T): T = f
    override val viiteVkmClient = mockViiteVkmClient
  }

  val nodesAndJunctionsService = new NodesAndJunctionsService(mockRoadwayDAO, roadwayPointDAO, mockLinearLocationDAO, nodeDAO, nodePointDAO, junctionDAO, junctionPointDAO, roadwayChangesDAO)

  val projectService = new ProjectService(roadAddressService, mockRoadLinkService, nodesAndJunctionsService, roadwayDAO,
    roadwayPointDAO, linearLocationDAO, projectDAO, projectLinkDAO,
    nodeDAO, nodePointDAO, junctionPointDAO, projectReservedPartDAO, roadwayChangesDAO,
    roadwayAddressMapper, mockEventBus) {
    override def withDynSession[T](f: => T): T = f
    override def withDynTransaction[T](f: => T): T = f
  }
  def runWithRollback[T](f: => T): T = {
    Database.forDataSource(PostGISDatabase.ds).withDynTransaction {
      val t = f
      dynamicSession.rollback()
      t
    }
  }

  private def dummyProject(id: Long, status: ProjectState, reservedParts: Seq[ProjectReservedPart] = List.empty[ProjectReservedPart], coordinates: Option[ProjectCoordinates] = None): Project ={
    Project(id, status, "testProject", "testUser", DateTime.parse("1901-01-01"), "testUser", DateTime.parse("1901-01-01"), DateTime.now(), "additional info here", reservedParts, Seq(), Some("current status info"), coordinates)
  }

  private def extractTrafficDirection(sideCode: SideCode, track: Track): TrafficDirection = {
    (sideCode, track) match {
      case (_, Track.Combined) => TrafficDirection.BothDirections
      case (TowardsDigitizing, Track.RightSide) => TrafficDirection.TowardsDigitizing
      case (TowardsDigitizing, Track.LeftSide) => TrafficDirection.AgainstDigitizing
      case (AgainstDigitizing, Track.RightSide) => TrafficDirection.AgainstDigitizing
      case (AgainstDigitizing, Track.LeftSide) => TrafficDirection.TowardsDigitizing
      case (_, _) => TrafficDirection.UnknownDirection
    }
  }

    private def toRoadLink(ral: ProjectLink): RoadLink = {
      RoadLink(ral.linkId, ral.geometry, ral.geometryLength, State, 1,
        extractTrafficDirection(ral.sideCode, ral.track), Motorway, None, None, Map(
          "MUNICIPALITYCODE" -> BigInt(749), "VERTICALLEVEL" -> BigInt(1), "SURFACETYPE" -> BigInt(1),
          "ROADNUMBER" -> BigInt(ral.roadNumber), "ROADPARTNUMBER" -> BigInt(ral.roadPartNumber)),
        ConstructionType.InUse, LinkGeomSource.NormalLinkInterface)
    }

  test("Test getRoadAddressLinksByLinkId When called by any bounding box and any road number limits Then should return road addresses on normal and history road links") {
    val linearLocations = Seq(
      dummyLinearLocation(roadwayNumber = 1L, orderNumber = 1L, linkId = 123L, startMValue = 0.0, endMValue = 10.0),
      dummyLinearLocation(roadwayNumber = 1L, orderNumber = 2L, linkId = 123L, startMValue = 10.0, endMValue = 20.0),
      dummyLinearLocation(roadwayNumber = 1L, orderNumber = 3L, linkId = 124L, startMValue = 0.0, endMValue = 10.0),
      dummyLinearLocation(roadwayNumber = 1L, orderNumber = 4L, linkId = 125L, startMValue = 0.0, endMValue = 10.0)
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

    when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(any[BoundingRectangle], any[Seq[(Int,Int)]])).thenReturn(linearLocations)

    when(mockRoadwayDAO.fetchAllByRoadwayNumbers(any[Set[Long]], any[Boolean])).thenReturn(roadways)

    //Road Link service mocks
    when(mockRoadLinkService.getChangeInfoFromVVHF(any[Set[Long]])).thenReturn(Future(Seq.empty))
    when(mockRoadLinkService.getRoadLinksHistoryFromVVH(any[Set[Long]])).thenReturn(vvhHistoryRoadLinks)
    when(mockRoadLinkService.getRoadLinksByLinkIdsFromVVH(any[Set[Long]])).thenReturn(roadLinks)

    val result = roadAddressService.getRoadAddressLinksByBoundingBox(BoundingRectangle(Point(0.0, 0.0), Point(0.0, 20.0)), Seq())

    verify(mockRoadLinkService, times(1)).getChangeInfoFromVVHF(Set(123L, 124L, 125L))
    verify(mockRoadLinkService, times(1)).getRoadLinksHistoryFromVVH(Set(123L, 124L, 125L))
    verify(mockRoadLinkService, times(1)).getRoadLinksByLinkIdsFromVVH(Set(123L, 124L, 125L))

    result.size should be (3)
  }

  test("Test getRoadAddressLinksByLinkId When called by any bounding box and any road number limits Then should not filter out floatings") {
    val linearLocations = Seq(
      dummyLinearLocation(roadwayNumber = 1L, orderNumber = 1L, linkId = 123L, startMValue = 0.0, endMValue = 10.0),
      dummyLinearLocation(roadwayNumber = 1L, orderNumber = 2L, linkId = 123L, startMValue = 10.0, endMValue = 20.0),
      dummyLinearLocation(roadwayNumber = 1L, orderNumber = 3L, linkId = 124L, startMValue = 0.0, endMValue = 10.0),
      dummyLinearLocation(roadwayNumber = 1L, orderNumber = 4L, linkId = 125L, startMValue = 0.0, endMValue = 10.0)
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

    when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(any[BoundingRectangle], any[Seq[(Int,Int)]])).thenReturn(linearLocations)

    when(mockRoadwayDAO.fetchAllByRoadwayNumbers(any[Set[Long]], any[Boolean])).thenReturn(roadways)

    //Road Link service mocks
    when(mockRoadLinkService.getChangeInfoFromVVHF(any[Set[Long]])).thenReturn(Future(Seq.empty))
    when(mockRoadLinkService.getRoadLinksHistoryFromVVH(any[Set[Long]])).thenReturn(vvhHistoryRoadLinks)
    when(mockRoadLinkService.getRoadLinksByLinkIdsFromVVH(any[Set[Long]])).thenReturn(roadLinks)

    val roadAddressLinks = roadAddressService.getRoadAddressLinksByBoundingBox(BoundingRectangle(Point(0.0, 0.0), Point(0.0, 20.0)), Seq())

    roadAddressLinks.size should be (3)
    roadAddressLinks.map(_.linkId).distinct should contain allOf (123L,124L)
  }

  test("Test getRoadAddressesWithLinearGeometry When municipality has road addresses on top of suravage and complementary road links Then should not return floatings") {
    val linearLocations = Seq(
      dummyLinearLocation(roadwayNumber = 1L, orderNumber = 1L, linkId = 123L, startMValue = 0.0, endMValue = 10.0),
      dummyLinearLocation(roadwayNumber = 1L, orderNumber = 2L, linkId = 123L, startMValue = 10.0, endMValue = 20.0),
      dummyLinearLocation(roadwayNumber = 1L, orderNumber = 3L, linkId = 124L, startMValue = 0.0, endMValue = 10.0),
      dummyLinearLocation(roadwayNumber = 1L, orderNumber = 4L, linkId = 125L, startMValue = 0.0, endMValue = 10.0)
    )

    val roadways = Seq(
      dummyRoadway(roadwayNumber = 1L, roadNumber = 1L, roadPartNumber = 1L, startAddrM = 0L, endAddrM = 400L, DateTime.now(), None)
    )

    when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(any[BoundingRectangle], any[Seq[(Int,Int)]])).thenReturn(linearLocations)

    when(mockRoadwayDAO.fetchAllByRoadwayNumbers(any[Set[Long]], any[Boolean])).thenReturn(roadways)

    val roadAddressesWithLinearGeometry = roadAddressService.getRoadAddressesWithLinearGeometry(BoundingRectangle(Point(0.0, 0.0), Point(0.0, 20.0)), Seq())

    roadAddressesWithLinearGeometry.size should be (linearLocations.size)

    roadAddressesWithLinearGeometry.map(_.linkId).distinct should be (linearLocations.map(_.linkId).distinct)
  }

  test("Test getAllByMunicipality When exists road network version Then returns road addresses of that version") {

    val linearLocations = List(
      dummyLinearLocation(roadwayNumber = 1L, orderNumber = 1L, linkId = 123L, startMValue = 0.0, endMValue = 10.0),
      dummyLinearLocation(roadwayNumber = 1L, orderNumber = 2L, linkId = 123L, startMValue = 10.0, endMValue = 20.0),
      dummyLinearLocation(roadwayNumber = 1L, orderNumber = 3L, linkId = 124L, startMValue = 0.0, endMValue = 10.0),
      dummyLinearLocation(roadwayNumber = 1L, orderNumber = 4L, linkId = 125L, startMValue = 0.0, endMValue = 10.0),
      dummyLinearLocation(roadwayNumber = 1L, orderNumber = 5L, linkId = 126L, startMValue = 0.0, endMValue = 10.0)
    )

    val roadways = Seq(
      dummyRoadway(roadwayNumber = 1L, roadNumber = 1L, roadPartNumber = 1L, startAddrM = 0L, endAddrM = 400L, DateTime.now(), None)
    )

    val roadLinks = Seq(
      dummyRoadLink(linkId = 123L, Seq(0.0, 10.0, 20.0), NormalLinkInterface),
      dummyRoadLink(linkId = 124L, Seq(0.0, 10.0), NormalLinkInterface)
    )

    when(mockLinearLocationDAO.fetchRoadwayByLinkId(any[Set[Long]])).thenReturn(linearLocations)

    when(mockRoadwayDAO.fetchAllByRoadwayNumbers(any[Set[Long]], any[Int], Some(any[DateTime]))).thenReturn(roadways)
    when(mockRoadNetworkDAO.getLatestRoadNetworkVersionId).thenReturn(Some(1L))


    //Road Link service mocks
    when(mockRoadLinkService.getRoadLinksWithComplementaryAndChangesFromVVH(any[Int])).thenReturn((roadLinks, Seq()))

    val roads = roadAddressService.getAllByMunicipality(municipality = 100)

    verify(mockRoadwayDAO, times(1)).fetchAllByRoadwayNumbers(Set(1L), 1, None)
  }

  def toRoadwayAndLinearLocation(p: ProjectLink):(LinearLocation, Roadway) = {
    val startDate = p.startDate.getOrElse(DateTime.now()).minusDays(1)

    (LinearLocation(-1000, 1, p.linkId, p.startMValue, p.endMValue, p.sideCode, p.linkGeometryTimeStamp,
      (CalibrationPointsUtils.toCalibrationPointReference(p.startCalibrationPoint),
        CalibrationPointsUtils.toCalibrationPointReference(p.endCalibrationPoint)),
      p.geometry, p.linkGeomSource,
      p.roadwayNumber, Some(startDate), p.endDate),
      Roadway(-1000, p.roadwayNumber, p.roadNumber, p.roadPartNumber, p.roadType, p.track, p.discontinuity, p.startAddrMValue, p.endAddrMValue, p.reversed, startDate, p.endDate,
        p.createdBy.getOrElse("-"), p.roadName, p.ely, TerminationCode.NoTermination, DateTime.now(), None))
  }

  test("Test getAllByMunicipality When does not exists road network version Then returns current road addresses") {
    implicit def dateTimeOrdering: Ordering[DateTime] = Ordering.fromLessThan(_ isBefore _)
    val linearLocations = List(
      dummyLinearLocation(roadwayNumber = 1L, orderNumber = 1L, linkId = 123L, startMValue = 0.0, endMValue = 10.0),
      dummyLinearLocation(roadwayNumber = 1L, orderNumber = 2L, linkId = 123L, startMValue = 10.0, endMValue = 20.0),
      dummyLinearLocation(roadwayNumber = 1L, orderNumber = 3L, linkId = 124L, startMValue = 0.0, endMValue = 10.0),
      dummyLinearLocation(roadwayNumber = 1L, orderNumber = 4L, linkId = 125L, startMValue = 0.0, endMValue = 10.0),
      dummyLinearLocation(roadwayNumber = 1L, orderNumber = 5L, linkId = 126L, startMValue = 0.0, endMValue = 10.0)
    )

    val roadways = Seq(
      dummyRoadway(roadwayNumber = 1L, roadNumber = 1L, roadPartNumber = 1L, startAddrM = 0L, endAddrM = 400L, DateTime.now(), None)
    )

    val roadLinks = Seq(
      dummyRoadLink(linkId = 123L, Seq(0.0, 10.0, 20.0), NormalLinkInterface),
      dummyRoadLink(linkId = 124L, Seq(0.0, 10.0), NormalLinkInterface)
    )

    when(mockLinearLocationDAO.fetchRoadwayByLinkId(any[Set[Long]])).thenReturn(linearLocations)

    when(mockRoadwayDAO.fetchAllByRoadwayNumbers(any[Set[Long]], any[DateTime])).thenReturn(roadways)
    when(mockRoadNetworkDAO.getLatestRoadNetworkVersionId).thenReturn(None)


    //Road Link service mocks
    when(mockRoadLinkService.getRoadLinksWithComplementaryAndChangesFromVVH(any[Int])).thenReturn((roadLinks, Seq()))

    val now = DateTime.now
    roadAddressService.getAllByMunicipality(municipality = 100)

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
      dummyLinearLocation(roadwayNumber = 1L, orderNumber = 2L, linkId = 123L, startMValue = 10.0, endMValue = 20.0),
      dummyLinearLocation(roadwayNumber = 1L, orderNumber = 3L, linkId = 124L, startMValue = 0.0, endMValue = 10.0),
      dummyLinearLocation(roadwayNumber = 1L, orderNumber = 4L, linkId = 125L, startMValue = 0.0, endMValue = 10.0)
    )

    val roadways = Seq(
      dummyRoadway(roadwayNumber = 1L, roadNumber = 1L, roadPartNumber = 1L, startAddrM = 0L, endAddrM = 400L, DateTime.now(), None)
    )

    when(mockRoadwayDAO.fetchAllBySectionAndAddresses(any[Long], any[Long], any[Option[Long]], any[Option[Long]], any[Option[Long]])).thenReturn(roadways)
    when(mockLinearLocationDAO.fetchByRoadways(any[Set[Long]])).thenReturn(linearLocations)

    val result = roadAddressService.getRoadAddress(road = 1, roadPart = 1, addressM = 200, None)

    result.size should be (3)
    result.count(_.startAddrMValue < 200) should be (1)
  }

  test("Test getRoadAddressWithLinkIdAndMeasure When link id, start measure and end measure is given Then returns all the road address in the given link id and in between given measures") {
    val linearLocations = List(
      dummyLinearLocation(roadwayNumber = 1L, orderNumber = 1L, linkId = 123L, startMValue = 0.0, endMValue = 10.0),
      dummyLinearLocation(roadwayNumber = 1L, orderNumber = 2L, linkId = 123L, startMValue = 10.0, endMValue = 20.0),
      dummyLinearLocation(roadwayNumber = 1L, orderNumber = 3L, linkId = 124L, startMValue = 0.0, endMValue = 10.0),
      dummyLinearLocation(roadwayNumber = 1L, orderNumber = 4L, linkId = 125L, startMValue = 0.0, endMValue = 10.0)
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
      dummyLinearLocation(roadwayNumber = 1L, orderNumber = 2L, linkId = 123L, startMValue = 10.0, endMValue = 20.0),
      dummyLinearLocation(roadwayNumber = 1L, orderNumber = 3L, linkId = 124L, startMValue = 0.0, endMValue = 10.0),
      dummyLinearLocation(roadwayNumber = 1L, orderNumber = 4L, linkId = 125L, startMValue = 0.0, endMValue = 10.0)
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
      dummyLinearLocation(roadwayNumber = 1L, orderNumber = 2L, linkId = 123L, startMValue = 10.0, endMValue = 20.0),
      dummyLinearLocation(roadwayNumber = 1L, orderNumber = 3L, linkId = 124L, startMValue = 0.0, endMValue = 10.0),
      dummyLinearLocation(roadwayNumber = 1L, orderNumber = 4L, linkId = 125L, startMValue = 0.0, endMValue = 10.0)
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
      dummyLinearLocation(roadwayNumber = 1L, orderNumber = 2L, linkId = 123L, startMValue = 10.0, endMValue = 20.0),
      dummyLinearLocation(roadwayNumber = 1L, orderNumber = 3L, linkId = 124L, startMValue = 0.0, endMValue = 10.0),
      dummyLinearLocation(roadwayNumber = 1L, orderNumber = 4L, linkId = 125L, startMValue = 0.0, endMValue = 10.0)
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
      dummyLinearLocation(roadwayNumber = 1L, orderNumber = 2L, linkId = 123L, startMValue = 10.0, endMValue = 20.0),
      dummyLinearLocation(roadwayNumber = 1L, orderNumber = 3L, linkId = 124L, startMValue = 0.0, endMValue = 10.0),
      dummyLinearLocation(roadwayNumber = 1L, orderNumber = 4L, linkId = 125L, startMValue = 0.0, endMValue = 10.0)
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
      dummyLinearLocation(roadwayNumber = 1L, orderNumber = 2L, linkId = 123L, startMValue = 10.0, endMValue = 20.0),
      dummyLinearLocation(roadwayNumber = 1L, orderNumber = 3L, linkId = 124L, startMValue = 0.0, endMValue = 10.0),
      dummyLinearLocation(roadwayNumber = 1L, orderNumber = 4L, linkId = 125L, startMValue = 0.0, endMValue = 10.0)
    )

    val roadways = Seq(
      dummyRoadway(roadwayNumber = 1L, roadNumber = 1L, roadPartNumber = 1L, startAddrM = 0L, endAddrM = 400L, DateTime.now(), None)
    )

    when(mockRoadwayDAO.fetchAllByRoadwayNumbers(any[Set[Long]], any[Boolean])).thenReturn(roadways)
    when(mockLinearLocationDAO.fetchRoadwayByLinkId(any[Set[Long]])).thenReturn(linearLocations)

    val result = roadAddressService.getRoadAddressByLinkIds(Set(123L))

    result.size should be (2)
    result.map(_.linkId).distinct.size should be (1)
    result.map(_.linkId).distinct should be (List(123L))
    result.head.startMValue should be (0.0)
    result.head.endMValue should be (10.0)
    result.last.startMValue should be (10.0)
    result.last.endMValue should be (20.0)
  }

  test("Test SearchService") {
    val point = Point(1D, 1D, 1D)
    val linkId = 123L

    val towardsDigitizingLinearLocation = List(
      dummyLinearLocationWithGeometry(-1000L, roadwayNumber = 1L, orderNumber = 1L, linkId = linkId, startMValue = 0.0, endMValue = 10.0, SideCode.TowardsDigitizing , Seq(Point(0.0, 0.0), Point(1L + .5, 0.0)))
    )

    val againstDigitizingLinearLocation = List(
      dummyLinearLocationWithGeometry(-1000L, roadwayNumber = 1L, orderNumber = 1L, linkId = linkId, startMValue = 0.0, endMValue = 10.0, SideCode.AgainstDigitizing , Seq(Point(0.0, 0.0), Point(1L + .5, 0.0)))
    )

    val roadways = Seq(
      dummyRoadway(roadwayNumber = 1L, roadNumber = 1L, roadPartNumber = 1L, startAddrM = 0L, endAddrM = 10L, DateTime.now(), None)
    )
    val searchResults: Any = """Map(("results" -> List(Map(("urakka_alue" -> 1247, "x" -> 380833.379, "kuntakoodi" -> 977, "maakunta_nimi" -> "Pohjois-Pohjanmaa", "y" -> 7107501.999, "kunta_nimi" -> "Ylivieska, maakunta" -> 17, "ely" -> 12, "urakka_alue_nimi -> Raahe/Ylivieska 16-21", "address" -> "Nuolitie, Ylivieska", "ely_nimi" -> "Pohjois-Pohjanmaa ja Kainuu")))))"""
    when(mockRoadwayDAO.fetchAllByRoad(any[Long])).thenReturn(roadways)
    when(mockLinearLocationDAO.fetchByRoadways(any[Set[Long]])).thenReturn(towardsDigitizingLinearLocation)
    when(mockRoadwayDAO.fetchAllBySectionsAndTracks(any[Long], any[Set[Long]], any[Set[Track]])).thenReturn(roadways)
    when(mockRoadwayDAO.fetchAllBySectionAndAddresses(any[Long], any[Long], any[Option[Long]], any[Option[Long]], any[Option[Long]])).thenReturn(roadways)
    when(mockViiteVkmClient.postFormUrlEncoded(any[String], any[Map[String, String]])).thenReturn(searchResults, Seq.empty: _*)
    when(mockViiteVkmClient.get(any[String], any[Map[String, String]])).thenReturn(Left(searchResults))
    when(mockLinearLocationDAO.fetchByRoadAddress(any[Long],any[Long],any[Long], any[Option[Long]])).thenReturn(towardsDigitizingLinearLocation)

    val towardsDigitizingRoadLink = Seq(
      RoadLink(linkId, Seq(Point(0.0, 10.0), Point(0.0, 15.0)), 10.0, Municipality, 0, TrafficDirection.TowardsDigitizing, UnknownLinkType, None, None, attributes = Map("MUNICIPALITYCODE" -> BigInt(235)), UnknownConstructionType, NormalLinkInterface)
    )


    // Test search by street name
    var result = roadAddressService.getSearchResults(Option("nuolirinne"))
    result.size should be(1)
    result(0).contains("street") should be(true)

    // Test search by road number
    result = roadAddressService.getSearchResults(Option("1"))
    result.size should be(3)
    result(2).get("road").get(0).asInstanceOf[RoadAddress].roadNumber should be(1)

    // Test search by linkId
    when(mockRoadLinkService.getMidPointByLinkId(any[Long])).thenReturn(Option(point))
    result = roadAddressService.getSearchResults(Option("1"))
    result.size should be(3)
    result(0).get("linkId").get(0).asInstanceOf[Some[Point]].x should be(point)
    result(2).get("road").get(0).asInstanceOf[RoadAddress].roadNumber should be(1)
    reset(mockRoadLinkService)

    // Test search by mtkId
    when(mockRoadLinkService.getRoadLinkMiddlePointByMtkId(any[Long])).thenReturn(Option(point))
    result = roadAddressService.getSearchResults(Option("1"))
    result.size should be(3)
    result(1).get("mtkId").get(0).asInstanceOf[Some[Point]].x should be(point)
    result(2).get("road").get(0).asInstanceOf[RoadAddress].roadNumber should be(1)

    // Test search by road number, road part number
    result = roadAddressService.getSearchResults(Option("1 1"))
    result.size should be(1)
    result(0).get("road").get(0).asInstanceOf[RoadAddress].roadNumber should be(1)

    // Test search by road number, road part number and M number TowardsDigitizing
    when(mockRoadLinkService.getRoadLinksByLinkIdsFromVVH(any[Set[Long]])).thenReturn(towardsDigitizingRoadLink)
    result = roadAddressService.getSearchResults(Option("1 1 1"))
    result.size should be(1)
    result(0).contains("roadM") should be(true)
    result(0).get("roadM").get(0).asInstanceOf[Some[Point]].x should be(Point(0.0, 11, 0.0))

    // Test search by road number, road part number and M number AgainstDigitizing
    when(mockLinearLocationDAO.fetchByRoadways(any[Set[Long]])).thenReturn(againstDigitizingLinearLocation)
    result = roadAddressService.getSearchResults(Option("1 1 9"))
    result.size should be(1)
    result(0).contains("roadM") should be(true)
    result(0).get("roadM").get(0).asInstanceOf[Some[Point]].x should be(Point(0.0, 11, 0.0))

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
//    val filter = PostGISDatabase.boundingBoxFilter(boundingRectangle, "geometry")
//    runWithRollback {
//      val modificationDate = "1455274504000l"
//      val modificationUser = "testUser"
//      val query =
//        s"""select ra.LINK_ID, ra.end_measure
//        from ROADWAY ra
//        where $filter and ra.valid_to is null order by ra.id asc"""
//      val (linkId, endM) = StaticQuery.queryNA[(Long, Double)](query).firstOption.get
//      val roadLink = RoadLink(linkId, Seq(Point(0.0, 0.0), Point(endM + .5, 0.0)), endM + .5, Municipality, 1, TrafficDirection.TowardsDigitizing, Freeway, Some(modificationDate), Some(modificationUser), attributes = Map("MUNICIPALITYCODE" -> BigInt(235)))
//      when(localMockRoadLinkService.getRoadLinksFromVVH(any[BoundingRectangle], any[Seq[(Int,Int)]], any[Set[Int]], any[Boolean],any[Boolean])).thenReturn(Seq(roadLink))
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
    val newGeom0010 = Seq(Point(0.0, 0.0), Point(10.0, 10.0))
    val newGeom1020 = Seq(Point(10.0, 10.0), Point(30.0, 30.0))
    val newGeom3040 = Seq(Point(30.0, 30.0), Point(40.0, 40.0))
    val newGeom4050 = Seq(Point(40.0, 40.0), Point(50.0, 50.0))
    val linearLocations = List(
      dummyLinearLocation(roadwayNumber = 1L, orderNumber = 1L, linkId = 123L, startMValue = 0.0, endMValue = 10.0),
      dummyLinearLocation(roadwayNumber = 1L, orderNumber = 2L, linkId = 123L, startMValue = 0.0, endMValue = 20.0),
      dummyLinearLocation(roadwayNumber = 1L, orderNumber = 3L, linkId = 124L, startMValue = 0.0, endMValue = 10.0),
      dummyLinearLocation(roadwayNumber = 1L, orderNumber = 4L, linkId = 125L, startMValue = 0.0, endMValue = 10.0)
    )

    val roadLinks = (Seq(
      RoadLink(123L, newGeom0010, 17, AdministrativeClass.apply(2), 1, TrafficDirection.TowardsDigitizing, LinkType.apply(1), None, None, Map("MUNICIPALITYCODE" -> BigInt.apply(99999), "MTKCLASS" -> BigInt.apply(12316))),
      RoadLink(123L, newGeom1020, 17, AdministrativeClass.apply(2), 1, TrafficDirection.TowardsDigitizing, LinkType.apply(1), None, None, Map("MUNICIPALITYCODE" -> BigInt.apply(99999), "MTKCLASS" -> BigInt.apply(12141))),
      RoadLink(124L, newGeom3040, 17, AdministrativeClass.apply(2), 1, TrafficDirection.TowardsDigitizing, LinkType.apply(1), None, None, Map("MUNICIPALITYCODE" -> BigInt.apply(99999), "MTKCLASS" -> BigInt.apply(12314))),
      RoadLink(125L, newGeom4050, 17, AdministrativeClass.apply(2), 1, TrafficDirection.TowardsDigitizing, LinkType.apply(1), None, None, Map("MUNICIPALITYCODE" -> BigInt.apply(99999), "MTKCLASS" -> BigInt.apply(12312)))
    ), Seq.empty[ChangeInfo])

    val roadways = Seq(
      dummyRoadway(roadwayNumber = 1L, roadNumber = 1L, roadPartNumber = 1L, startAddrM = 0L, endAddrM = 400L, DateTime.now(), None)
    )

    when(mockRoadwayDAO.fetchAllByRoadwayNumbers(any[Set[Long]], any[DateTime])).thenReturn(roadways)
    when(mockLinearLocationDAO.fetchRoadwayByLinkId(any[Set[Long]])).thenReturn(linearLocations)


    when(mockRoadLinkService.getRoadLinksWithComplementaryAndChangesFromVVH(99999)).thenReturn(roadLinks)
    when(mockRoadLinkService.getComplementaryRoadLinksFromVVH(99999)).thenReturn(Seq())
    val roadAddresses = roadAddressService.getAllByMunicipality(99999)
    roadAddresses.size should be (4)
  }

  test("Test handleRoadwayPoints When Terminating one link and Transfer the rest Then roadway points should be handled/created properly") {
    runWithRollback {
      val geom1 = Seq(Point(0.0, 0.0), Point(5.0, 0.0))
      val geom2 = Seq(Point(5.0, 0.0), Point(20.0, 0.0))
      val roadwayNumber1 = Sequences.nextRoadwayNumber
      val roadwayNumber2 = roadwayNumber1+1

      val projectId = Sequences.nextViiteProjectId
      val rap =  dummyProject(projectId, ProjectState.Incomplete, Seq(), None).copy(startDate = DateTime.parse("2019-10-10"))
      val id1 = Sequences.nextRoadwayId
      val id2 = id1+1
      val linearLocationId = Sequences.nextLinearLocationId
      val link1 = dummyProjectLink(99, 2, Track.Combined, Discontinuity.Continuous, 0 , 5, Some(DateTime.now()), None, 12345, 0, 5, SideCode.TowardsDigitizing, LinkStatus.Terminated, projectId, RoadType.PublicRoad, geom1, roadwayNumber1)
      val link2 = dummyProjectLink(99, 2, Track.Combined, Discontinuity.EndOfRoad, 5 , 20, Some(DateTime.now()), None, 12346, 0, 15, SideCode.TowardsDigitizing, LinkStatus.Transfer, projectId, RoadType.FerryRoad, geom2, roadwayNumber2)

      //Roadways and linear location generated AFTER changes
      val (lc1, rw1): (LinearLocation, Roadway) = Seq(link1).map(toRoadwayAndLinearLocation).head
      val (lc2, rw2): (LinearLocation, Roadway) = Seq(link2).map(toRoadwayAndLinearLocation).head
      val rw1WithId = rw1.copy(id = id1, ely = 8L)
      val rw2WithId = rw2.copy(id = id2, ely = 8L)
      val lc2WithId = lc2
      roadwayDAO.create(Seq(rw1WithId))
      linearLocationDAO.create(Seq(lc1.copy(id = linearLocationId)))
      roadwayDAO.create(Seq(rw2WithId))
      linearLocationDAO.create(Seq(lc2WithId.copy(id = linearLocationId+1)))
      val pls = Seq(link1.copy(roadwayId = id1, linearLocationId = linearLocationId), link2.copy(roadwayId = id2, linearLocationId = linearLocationId+1))

      when(mockRoadwayDAO.fetchAllByRoadwayId(any[Seq[Long]])).thenReturn(Seq(rw1WithId, rw2WithId))
      when(mockLinearLocationDAO.fetchByRoadways(any[Set[Long]])).thenReturn(Seq(lc1, lc2WithId))
      when(mockRoadLinkService.getRoadLinksByLinkIdsFromVVH(any[Set[Long]])).thenReturn(pls.map(toRoadLink))

      projectDAO.create(rap)

      val ra1 = RoadAddress(0, lc1.id, 99, 2, rw1.roadType,rw1.track, rw1.discontinuity, rw1.startAddrMValue, rw1.endAddrMValue, Some(rw1.startDate), rw1.endDate, Some(rw1.createdBy), lc1.linkId, lc1.startMValue, lc1.endMValue, lc1.sideCode, 1000000, (None, None),lc1.geometry, lc1.linkGeomSource, rw1.ely, NoTermination, rw1.roadwayNumber,None, None, None)
      val ra2 = RoadAddress(0, lc2.id, 99, 2, rw2.roadType,rw2.track, rw2.discontinuity, rw2.startAddrMValue, rw2.endAddrMValue, Some(rw2.startDate), rw2.endDate, Some(rw2.createdBy), lc2.linkId, lc2.startMValue, lc2.endMValue, lc2.sideCode, 1000000, (None, None),lc2.geometry, lc2.linkGeomSource, rw2.ely, NoTermination, rw2.roadwayNumber,None, None, None)

      when(mockRoadAddressService.getRoadAddressWithRoadAndPart(any[Long], any[Long], any[Boolean], any[Boolean], any[Boolean])).thenReturn(Seq(ra1, ra2))
      when(mockLinearLocationDAO.fetchByRoadways(Set(rw1.roadwayNumber))).thenReturn(Seq(lc1))
      when(mockLinearLocationDAO.fetchByRoadways(Set(rw2.roadwayNumber))).thenReturn(Seq(lc2))
      when(mockRoadwayDAO.fetchAllByRoadAndPart(99, 2)).thenReturn(Seq(rw2WithId))

      projectReservedPartDAO.reserveRoadPart(projectId, 99, 2, "u")
      projectLinkDAO.create(pls.map(_.copy(id = NewIdValue)))
      val projectLinks = projectLinkDAO.fetchByProjectRoad(99, projectId).sortBy(_.startAddrMValue)

      projectService.updateProjectLinks(projectId, Set(), Seq(projectLinks.head.linkId), LinkStatus.Terminated, "-", 99, 2, 0, Option.empty[Int])
      projectService.updateProjectLinks(projectId, Set(), Seq(projectLinks.last.linkId), LinkStatus.Transfer, "-", 99, 2, 0, Option.empty[Int])

      roadwayPointDAO.create(link1.roadwayNumber, link1.startAddrMValue, link1.createdBy.getOrElse("test"))
      roadwayPointDAO.create(link1.roadwayNumber, link1.endAddrMValue, link2.createdBy.getOrElse("test"))
      roadwayPointDAO.create(link2.roadwayNumber, link2.startAddrMValue, link2.createdBy.getOrElse("test"))
      roadwayPointDAO.create(link2.roadwayNumber, link2.endAddrMValue, link2.createdBy.getOrElse("test"))

      val afterUpdateProjectLinks = projectLinkDAO.fetchByProjectRoad(99, projectId).sortBy(_.startAddrMValue)
      val (afterChangesTerminated, afterChangesTransfer): (Seq[ProjectLink], Seq[ProjectLink]) = afterUpdateProjectLinks.partition(_.status == LinkStatus.Terminated)
      val beforeChangesTransfer = afterChangesTransfer.head.copy(roadwayNumber = roadwayNumber2)
      val generatedProperRoadwayNumbersAfterChanges = Seq(afterChangesTerminated.head, afterChangesTransfer.head)
      val mappedRoadwayChanges = projectLinkDAO.fetchProjectLinksChange(projectId)

      val newRoads = Seq()
      val terminated = Termination(Seq(
        (
          dummyRoadAddress(roadwayNumber1, 99, 2, 0, 5, Some(DateTime.now()),None, 12345, 0 , 5, LinkGeomSource.NormalLinkInterface, geom1),
          afterUpdateProjectLinks.head
        )
      )
      )
      val unchanged = Unchanged(Seq())
      val transferred = Transferred(Seq(
        (
          dummyRoadAddress(roadwayNumber1, 99, 2, 5, 20, Some(DateTime.now()),None, 12346, 0 , 15, LinkGeomSource.NormalLinkInterface, geom2),
          beforeChangesTransfer
        )
      )
      )
      val renumbered = ReNumeration(Seq())

      val roadwayPointsBeforeTerminatedLink = afterUpdateProjectLinks.filter(_.status == LinkStatus.Terminated).map(_.roadwayNumber).distinct.flatMap{ rwp=>
      roadwayPointDAO.fetchByRoadwayNumber(rwp)
      }
      val roadwayPointsBeforeTransferLink = Seq(beforeChangesTransfer).filter(_.status == LinkStatus.Transfer).map(_.roadwayNumber).distinct.flatMap{ rwp=>
      roadwayPointDAO.fetchByRoadwayNumber(rwp)
      }
      val delta = Delta(DateTime.now, newRoads , terminated, unchanged, transferred, renumbered)

      val reservedParts = Seq(ProjectReservedPart(0, 99, 2, Some(20), Some(Discontinuity.Continuous), Some(8L), None, None, None, Some(12345L)))

      val project = projectDAO.fetchById(projectId).get
      roadwayChangesDAO.insertDeltaToRoadChangeTable(delta, projectId, Some(project.copy(reservedParts = reservedParts)))

      val roadwayChanges = roadwayChangesDAO.fetchRoadwayChanges(Set(projectId))

      when(mockRoadwayDAO.fetchAllBySectionAndTracks(any[Long], any[Long], any[Set[Track]])).thenReturn(Seq(rw1WithId, rw2WithId))
      roadAddressService.handleRoadwayPointsUpdate(roadwayChanges, mappedRoadwayChanges, "user")

      val roadwayPointsAfterChangesTerminatedLink = roadwayPointDAO.fetchByRoadwayNumber(afterChangesTerminated.head.roadwayNumber).sortBy(_.addrMValue)
      roadwayPointsAfterChangesTerminatedLink.head.addrMValue should be (roadwayPointsBeforeTerminatedLink.head.addrMValue)
      roadwayPointsAfterChangesTerminatedLink.last.addrMValue should be (roadwayPointsBeforeTerminatedLink.last.addrMValue)

      val roadwayPointsAfterChangesTransferLink = roadwayPointDAO.fetchByRoadwayNumber(generatedProperRoadwayNumbersAfterChanges.last.roadwayNumber).sortBy(_.addrMValue)
      roadwayPointsBeforeTransferLink.head.id should be (roadwayPointsAfterChangesTransferLink.head.id)
      roadwayPointsBeforeTransferLink.last.id should be (roadwayPointsAfterChangesTransferLink.last.id)
      roadwayPointsAfterChangesTransferLink.head.roadwayNumber should be (generatedProperRoadwayNumbersAfterChanges.last.roadwayNumber)
      roadwayPointsAfterChangesTransferLink.head.addrMValue should be (generatedProperRoadwayNumbersAfterChanges.last.startAddrMValue)
      roadwayPointsAfterChangesTransferLink.last.addrMValue should be (generatedProperRoadwayNumbersAfterChanges.last.endAddrMValue)
    }
  }

  test("Test handleRoadwayPoints When Terminating one link and Transfer the rest and reverse it Then roadway points should be handled/created properly") {
    runWithRollback {
      val geom1 = Seq(Point(0.0, 0.0), Point(5.0, 0.0))
      val geom2 = Seq(Point(5.0, 0.0), Point(20.0, 0.0))
      val roadwayNumber1 = Sequences.nextRoadwayNumber
      val roadwayNumber2 = roadwayNumber1+1
      val roadwayNumber2AfterChanges = roadwayNumber2+1

      val projectId = Sequences.nextViiteProjectId
      val rap =  dummyProject(projectId, ProjectState.Incomplete, Seq(), None).copy(startDate = DateTime.parse("2019-10-10"))
      val id1 = Sequences.nextRoadwayId
      val id2 = id1+1
      val linearLocationId = Sequences.nextLinearLocationId
      val link1 = dummyProjectLink(99, 2, Track.Combined, Discontinuity.Continuous, 0 , 5, Some(DateTime.now()), None, 12345, 0, 5, SideCode.TowardsDigitizing, LinkStatus.Terminated, projectId, RoadType.PublicRoad, geom1, roadwayNumber1)
      val link2 = dummyProjectLink(99, 2, Track.Combined, Discontinuity.EndOfRoad, 5 , 20, Some(DateTime.now()), None, 12346, 0, 15, SideCode.AgainstDigitizing, LinkStatus.Transfer, projectId, RoadType.FerryRoad, geom2, roadwayNumber2)

      //Roadways and linear location generated AFTER changes
      val (lc1, rw1): (LinearLocation, Roadway) = Seq(link1).map(toRoadwayAndLinearLocation).head
      val (lc2, rw2): (LinearLocation, Roadway) = Seq(link2).map(toRoadwayAndLinearLocation).head
      val rw1WithId = rw1.copy(id = id1, ely = 8L)
      val rw2WithId = rw2.copy(id = id2, ely = 8L)
      val lc2WithId = lc2
      val pls = Seq(link1.copy(roadwayId = id1, linearLocationId = linearLocationId), link2.copy(roadwayId = id2, linearLocationId = linearLocationId+1))
      roadwayDAO.create(Seq(rw1WithId))
      linearLocationDAO.create(Seq(lc1.copy(id = linearLocationId)))
      roadwayDAO.create(Seq(rw2WithId))
      linearLocationDAO.create(Seq(lc2WithId.copy(id = linearLocationId+1)))

      when(mockRoadwayDAO.fetchAllByRoadwayId(any[Seq[Long]])).thenReturn(Seq(rw1WithId, rw2WithId))
      when(mockLinearLocationDAO.fetchByRoadways(any[Set[Long]])).thenReturn(Seq(lc1, lc2WithId))
      when(mockRoadLinkService.getRoadLinksByLinkIdsFromVVH(any[Set[Long]])).thenReturn(pls.map(toRoadLink))

      projectDAO.create(rap)

      val ra1 = RoadAddress(0, lc1.id, 99, 2, rw1.roadType,rw1.track, rw1.discontinuity, rw1.startAddrMValue, rw1.endAddrMValue, Some(rw1.startDate), rw1.endDate, Some(rw1.createdBy), lc1.linkId, lc1.startMValue, lc1.endMValue, lc1.sideCode, 1000000, (None, None),lc1.geometry, lc1.linkGeomSource, rw1.ely, NoTermination, rw1.roadwayNumber,None, None, None)
      val ra2 = RoadAddress(0, lc2.id, 99, 2, rw2.roadType,rw2.track, rw2.discontinuity, rw2.startAddrMValue, rw2.endAddrMValue, Some(rw2.startDate), rw2.endDate, Some(rw2.createdBy), lc2.linkId, lc2.startMValue, lc2.endMValue, lc2.sideCode, 1000000, (None, None),lc2.geometry, lc2.linkGeomSource, rw2.ely, NoTermination, rw2.roadwayNumber,None, None, None)

      when(mockRoadAddressService.getRoadAddressWithRoadAndPart(any[Long], any[Long], any[Boolean], any[Boolean], any[Boolean])).thenReturn(Seq(ra1, ra2))
      when(mockLinearLocationDAO.fetchByRoadways(Set(rw1.roadwayNumber))).thenReturn(Seq(lc1))
      when(mockLinearLocationDAO.fetchByRoadways(Set(rw2.roadwayNumber))).thenReturn(Seq(lc2))
      when(mockRoadwayDAO.fetchAllByRoadAndPart(99, 2)).thenReturn(Seq(rw2WithId))

      projectReservedPartDAO.reserveRoadPart(projectId, 99, 2, "u")
      projectLinkDAO.create(pls.map(_.copy(id = NewIdValue)))
      val projectLinks = projectLinkDAO.fetchByProjectRoad(99, projectId).sortBy(_.startAddrMValue)

      projectService.updateProjectLinks(projectId, Set(), Seq(projectLinks.head.linkId), LinkStatus.Terminated, "-", 99, 2, 0, Option.empty[Int])
      projectService.updateProjectLinks(projectId, Set(), Seq(projectLinks.last.linkId), LinkStatus.Transfer, "-", 99, 2, 0, Option.empty[Int])

      projectService.changeDirection(projectId, 99L, 2L, Seq(LinkToRevert(link2.id, link2.linkId, LinkStatus.Transfer.value, Seq())), ProjectCoordinates(link2.geometry.head.x, link2.geometry.head.y), "testuser")

      roadwayPointDAO.create(link1.roadwayNumber, link1.startAddrMValue, link1.createdBy.getOrElse("test"))
      roadwayPointDAO.create(link1.roadwayNumber, link1.endAddrMValue, link2.createdBy.getOrElse("test"))
      roadwayPointDAO.create(link2.roadwayNumber, link2.startAddrMValue, link2.createdBy.getOrElse("test"))
      roadwayPointDAO.create(link2.roadwayNumber, link2.endAddrMValue, link2.createdBy.getOrElse("test"))

      val afterUpdateProjectLinks = projectLinkDAO.fetchByProjectRoad(99, projectId).sortBy(_.startAddrMValue)
      val (afterChangesTerminated, afterChangesTransfer): (Seq[ProjectLink], Seq[ProjectLink]) = afterUpdateProjectLinks.partition(_.status == LinkStatus.Terminated)
      val beforeChangesTransfer = afterChangesTransfer.head.copy(roadwayNumber = roadwayNumber2)
      val generatedProperRoadwayNumbersAfterChanges = Seq(afterChangesTerminated.head, afterChangesTransfer.head)
      val mappedRoadwayChanges = projectLinkDAO.fetchProjectLinksChange(projectId)
      val newRoads = Seq()
      val terminated = Termination(Seq(
        (
          dummyRoadAddress(roadwayNumber1, 99, 2, 0, 5, Some(DateTime.now()),None, 12345, 0 , 5, LinkGeomSource.NormalLinkInterface, geom1),
          afterUpdateProjectLinks.head
        )
      )
      )
      val unchanged = Unchanged(Seq())
      val transferred = Transferred(Seq(
        (
          dummyRoadAddress(roadwayNumber1, 99, 2, 5, 20, Some(DateTime.now()),None, 12346, 0 , 15, LinkGeomSource.NormalLinkInterface, geom2),
          beforeChangesTransfer
        )
      )
      )
      val renumbered = ReNumeration(Seq())

      val roadwayPointsBeforeTerminatedLink = afterUpdateProjectLinks.filter(_.status == LinkStatus.Terminated).map(_.roadwayNumber).distinct.flatMap{ rwp=>
        roadwayPointDAO.fetchByRoadwayNumber(rwp)
      }
      val roadwayPointsBeforeTransferLink = Seq(beforeChangesTransfer).filter(_.status == LinkStatus.Transfer).map(_.roadwayNumber).distinct.flatMap{ rwp=>
        roadwayPointDAO.fetchByRoadwayNumber(rwp)
      }
      val delta = Delta(DateTime.now, newRoads , terminated, unchanged, transferred, renumbered)
      val reservedParts = Seq(ProjectReservedPart(0, 99, 2, Some(20), Some(Discontinuity.Continuous), Some(8L), None, None, None, Some(12345L)))
      val formedParts = Seq(ProjectReservedPart(0, 99, 2, None, None, None, Some(15), Some(Discontinuity.Continuous), Some(8L), Some(12345L)))

      val project = projectDAO.fetchById(projectId).get
      roadwayChangesDAO.insertDeltaToRoadChangeTable(delta, projectId, Some(project.copy(reservedParts = reservedParts, formedParts = formedParts)))
      val roadwayChanges = roadwayChangesDAO.fetchRoadwayChanges(Set(projectId))

      when(mockRoadwayDAO.fetchAllBySectionAndTracks(any[Long], any[Long], any[Set[Track]])).thenReturn(Seq(rw1WithId, rw2WithId))
      roadAddressService.handleRoadwayPointsUpdate(roadwayChanges, mappedRoadwayChanges, "user")

      val roadwayPointsAfterChangesTerminatedLink = roadwayPointDAO.fetchByRoadwayNumber(afterChangesTerminated.head.roadwayNumber).sortBy(_.addrMValue)
      roadwayPointsAfterChangesTerminatedLink.head.addrMValue should be (roadwayPointsBeforeTerminatedLink.head.addrMValue)
      roadwayPointsAfterChangesTerminatedLink.last.addrMValue should be (roadwayPointsBeforeTerminatedLink.last.addrMValue)

      val roadwayPointsAfterChangesTransferLink = roadwayPointDAO.fetchByRoadwayNumber(generatedProperRoadwayNumbersAfterChanges.last.roadwayNumber).sortBy(_.addrMValue)
      roadwayPointsBeforeTransferLink.head.id should be (roadwayPointsAfterChangesTransferLink.last.id)
      roadwayPointsBeforeTransferLink.last.id should be (roadwayPointsAfterChangesTransferLink.head.id)
      roadwayPointsAfterChangesTransferLink.head.roadwayNumber should be (generatedProperRoadwayNumbersAfterChanges.last.roadwayNumber)
      roadwayPointsAfterChangesTransferLink.head.addrMValue should be (generatedProperRoadwayNumbersAfterChanges.last.startAddrMValue)
      roadwayPointsAfterChangesTransferLink.last.addrMValue should be (generatedProperRoadwayNumbersAfterChanges.last.endAddrMValue)
    }
  }

  test("Test handleRoadwayPoints When dual roadway point is handled") {
    runWithRollback {
      val geom1 = Seq(Point(0.0, 0.0), Point(5.0, 0.0))
      val geom2 = Seq(Point(5.0, 0.0), Point(20.0, 0.0))
      val roadwayNumber1 = Sequences.nextRoadwayNumber
      val roadwayNumber2 = Sequences.nextRoadwayNumber
      val roadwayNumber3 = Sequences.nextRoadwayNumber

      val projectId = Sequences.nextViiteProjectId
      val rap =  dummyProject(projectId, ProjectState.Incomplete, Seq(), None).copy(startDate = DateTime.parse("2019-10-10"))
      val link1 = dummyProjectLink(99, 1, Track.Combined, Discontinuity.EndOfRoad, 0 , 5, Some(DateTime.now()), None, 12345, 0, 5, SideCode.TowardsDigitizing, LinkStatus.UnChanged, projectId, RoadType.PublicRoad, geom1, roadwayNumber2)
      val link2 = dummyProjectLink(99, 2, Track.Combined, Discontinuity.EndOfRoad, 0 , 15, Some(DateTime.now()), None, 12346, 0, 15, SideCode.TowardsDigitizing, LinkStatus.Transfer, projectId, RoadType.FerryRoad, geom2, roadwayNumber3)

      //Roadways and linear location generated AFTER changes
      val (lc1, rw1): (LinearLocation, Roadway) = Seq(link1).map(toRoadwayAndLinearLocation).head
      val (lc2, rw2): (LinearLocation, Roadway) = Seq(link2).map(toRoadwayAndLinearLocation).head
      val rw1WithId = rw1.copy(id = Sequences.nextRoadwayId, ely = 8L)
      val rw2WithId = rw2.copy(id = Sequences.nextRoadwayId, ely = 8L)
      val lc1WithId = lc1.copy(id = Sequences.nextLinearLocationId)
      val lc2WithId = lc2.copy(id = Sequences.nextLinearLocationId)
      roadwayDAO.create(Seq(rw1WithId))
      linearLocationDAO.create(Seq(lc1WithId))
      roadwayDAO.create(Seq(rw2WithId))
      linearLocationDAO.create(Seq(lc2WithId))
      val pls = Seq(link1.copy(roadwayId = rw1WithId.id, linearLocationId = lc1WithId.id), link2.copy(roadwayId = rw2WithId.id, linearLocationId = lc2WithId.id))

      when(mockRoadwayDAO.fetchAllByRoadwayId(any[Seq[Long]])).thenReturn(Seq(rw1WithId, rw2WithId))
      when(mockLinearLocationDAO.fetchByRoadways(any[Set[Long]])).thenReturn(Seq(lc1WithId, lc2WithId))
      when(mockRoadLinkService.getRoadLinksByLinkIdsFromVVH(any[Set[Long]])).thenReturn(pls.map(toRoadLink))

      projectDAO.create(rap)

      val ra1 = RoadAddress(0, lc1WithId.id, rw1WithId.roadNumber, rw1WithId.roadPartNumber, rw1WithId.roadType, rw1WithId.track, rw1WithId.discontinuity,
        rw1WithId.startAddrMValue, rw1WithId.endAddrMValue, Some(rw1WithId.startDate), rw1WithId.endDate, Some(rw1WithId.createdBy),
        lc1WithId.linkId, lc1WithId.startMValue, lc1WithId.endMValue, lc1WithId.sideCode, 1000000, (None, None),
        lc1WithId.geometry, lc1WithId.linkGeomSource, rw1WithId.ely, NoTermination, rw1WithId.roadwayNumber, None, None, None)

      val ra2 = RoadAddress(0, lc2WithId.id, rw2WithId.roadNumber, rw2WithId.roadPartNumber, rw2WithId.roadType, rw2WithId.track, rw2WithId.discontinuity,
        rw2WithId.startAddrMValue, rw2WithId.endAddrMValue, Some(rw2WithId.startDate), rw2.endDate, Some(rw2.createdBy),
        lc2WithId.linkId, lc2WithId.startMValue, lc2WithId.endMValue, lc2WithId.sideCode, 1000000, (None, None),
        lc2WithId.geometry, lc2WithId.linkGeomSource, rw2WithId.ely, NoTermination, rw2WithId.roadwayNumber, None, None, None)

      when(mockRoadAddressService.getRoadAddressWithRoadAndPart(any[Long], any[Long], any[Boolean], any[Boolean], any[Boolean])).thenReturn(Seq(ra1, ra2))
      when(mockLinearLocationDAO.fetchByRoadways(Set(rw1.roadwayNumber))).thenReturn(Seq(lc1))
      when(mockLinearLocationDAO.fetchByRoadways(Set(rw2.roadwayNumber))).thenReturn(Seq(lc2))
      when(mockRoadwayDAO.fetchAllByRoadAndPart(99, 2)).thenReturn(Seq(rw2WithId))

      projectReservedPartDAO.reserveRoadPart(projectId, 99, 1, "u")
      projectReservedPartDAO.reserveRoadPart(projectId, 99, 2, "u")
      projectLinkDAO.create(pls.map(_.copy(id = NewIdValue)))
      val projectLinks = projectLinkDAO.fetchByProjectRoad(99, projectId).sortBy(_.startAddrMValue)

      projectService.updateProjectLinks(projectId, Set(), Seq(projectLinks.head.linkId), LinkStatus.UnChanged, "-", 99, 1, 0, Option.empty[Int])
      projectService.updateProjectLinks(projectId, Set(), Seq(projectLinks.last.linkId), LinkStatus.Transfer, "-", 99, 2, 0, Option.empty[Int])

      roadwayPointDAO.create(roadwayNumber1, 0, link1.createdBy.getOrElse("test"))
      roadwayPointDAO.create(roadwayNumber1, 5, link2.createdBy.getOrElse("test"))
      roadwayPointDAO.create(roadwayNumber1, 20, link2.createdBy.getOrElse("test"))

      val afterUpdateProjectLinks = projectLinkDAO.fetchByProjectRoad(99, projectId).sortBy(_.startAddrMValue)
      val beforeDualPoint = afterUpdateProjectLinks.head.copy(roadwayNumber = roadwayNumber2)
      val afterDualPoint = afterUpdateProjectLinks.last.copy(roadwayNumber = roadwayNumber3)
      val mappedRoadwayChanges = projectLinkDAO.fetchProjectLinksChange(projectId)

      val newRoads = Seq()
      val terminated = Termination(Seq())
      val unchanged = Unchanged(Seq(
        (
          dummyRoadAddress(roadwayNumber1, 99, 1, 0, 5, Some(DateTime.now()),None, 12345, 0 , 5, LinkGeomSource.NormalLinkInterface, geom1),
          beforeDualPoint
        )
      )
      )
      val transferred = Transferred(Seq(
        (
          dummyRoadAddress(roadwayNumber1, 99, 1, 5, 20, Some(DateTime.now()),None, 12346, 0 , 15, LinkGeomSource.NormalLinkInterface, geom2),
          afterDualPoint
        )
      )
      )
      val renumbered = ReNumeration(Seq())

      val delta = Delta(DateTime.now, newRoads , terminated, unchanged, transferred, renumbered)

      val reservedParts = Seq(ProjectReservedPart(0, 99, 2, Some(20), Some(Discontinuity.Continuous), Some(8L), None, None, None, Some(12345L)))

      val project = projectDAO.fetchById(projectId).get
      roadwayChangesDAO.insertDeltaToRoadChangeTable(delta, projectId, Some(project.copy(reservedParts = reservedParts)))

      val roadwayChanges = roadwayChangesDAO.fetchRoadwayChanges(Set(projectId))

      when(mockRoadwayDAO.fetchAllBySectionAndTracks(any[Long], any[Long], any[Set[Track]])).thenReturn(Seq(rw1WithId, rw2WithId))
      roadAddressService.handleRoadwayPointsUpdate(roadwayChanges, mappedRoadwayChanges.map { rwc =>
        rwc.originalRoadPartNumber match {
          case 1 => rwc.copy(originalRoadwayNumber = roadwayNumber1)
          case 2 => rwc.copy(originalRoadPartNumber = 1, originalStartAddr = 5, originalEndAddr = 20, originalRoadwayNumber = roadwayNumber1)
          case _ => rwc
        }
      }, "user")

      val roadwayPointsForExpiredRoadwayNumber = roadwayPointDAO.fetchByRoadwayNumber(roadwayNumber1).sortBy(_.addrMValue)
      roadwayPointsForExpiredRoadwayNumber.size should be (0)

      val roadwayPointsBeforeDual = roadwayPointDAO.fetchByRoadwayNumber(beforeDualPoint.roadwayNumber).sortBy(_.addrMValue)
      roadwayPointsBeforeDual.size should be (2)
      roadwayPointsBeforeDual.head.addrMValue should be (beforeDualPoint.startAddrMValue)
      roadwayPointsBeforeDual.last.addrMValue should be (beforeDualPoint.endAddrMValue)

      val roadwayPointsAfterDual = roadwayPointDAO.fetchByRoadwayNumber(afterDualPoint.roadwayNumber).sortBy(_.addrMValue)
      roadwayPointsAfterDual.size should be (2)
      roadwayPointsAfterDual.head.addrMValue should be (afterDualPoint.startAddrMValue)
      roadwayPointsAfterDual.last.addrMValue should be (afterDualPoint.endAddrMValue)
    }
  }

  /*
  test("Test handleRoadwayPoints When Transfer one road part to another and Transfer the rest Then roadway points should be handled/created properly") {
    runWithRollback {

      val roadwayChangesDAO = new RoadwayChangesDAO
      val roadwayPointDAO = new RoadwayPointDAO
      val roadwayDAO = new RoadwayDAO
      val linearLocationDAO = new LinearLocationDAO
      val projectService = new ProjectService(roadAddressService, mockRoadLinkService, mockEventBus) {
        override def withDynSession[T](f: => T): T = f
        override def withDynTransaction[T](f: => T): T = f
      }

      val geom1 = Seq(Point(0.0, 0.0), Point(5.0, 0.0))
      val geom2 = Seq(Point(5.0, 0.0), Point(20.0, 0.0))
      val roadwayNumber = Sequences.nextRoadwayNumber
      val projectId = Sequences.nextViitePrimaryKeySeqValue
      val rap =  dummyProject(projectId, ProjectState.Incomplete, Seq(), None).copy(startDate = DateTime.parse("2019-10-10"))
      val id1 = Sequences.nextRoadwayId
      val id2 = id1+1
      val link1 = dummyProjectLink(99, 1, Track.Combined, Discontinuity.Continuous, 0 , 5, Some(DateTime.now()), None, 12345, 0, 5, SideCode.TowardsDigitizing, LinkStatus.Transfer, projectId, RoadType.PublicRoad, geom1, roadwayNumber)
      val link2 = dummyProjectLink(99, 2, Track.Combined, Discontinuity.EndOfRoad, 0 , 15, Some(DateTime.now()), None, 12346, 0, 15, SideCode.TowardsDigitizing, LinkStatus.Transfer, projectId, RoadType.PublicRoad, geom2, roadwayNumber+1)
      val pls = Seq(link1, link2)

      val (lc1, rw1): (LinearLocation, Roadway) = Seq(link1).map(toRoadwayAndLinearLocation).head
      val (lc2, rw2): (LinearLocation, Roadway) = Seq(link2).map(toRoadwayAndLinearLocation).head
      val rw1WithId = rw1.copy(id = id1, ely = 8L)
      val rw2WithId = rw2.copy(id = id2, ely = 8L)
      roadwayDAO.create(Seq(rw1WithId))
      linearLocationDAO.create(Seq(lc1))
      roadwayDAO.create(Seq(rw2WithId))
      linearLocationDAO.create(Seq(lc2))

      when(mockRoadwayDAO.fetchAllByRoadwayId(any[Seq[Long]])).thenReturn(Seq(rw1WithId, rw2WithId))
      when(mockLinearLocationDAO.fetchByRoadways(any[Set[Long]])).thenReturn(Seq(lc1, lc2))
      when(mockRoadLinkService.getSuravageRoadLinksByLinkIdsFromVVH(any[Set[Long]])).thenReturn(Seq())
      when(mockRoadLinkService.getRoadLinksByLinkIdsFromVVH(any[Set[Long]])).thenReturn(pls.map(toRoadLink))

      projectDAO.create(rap)

      val addresses = List(ProjectReservedPart(Sequences.nextViitePrimaryKeySeqValue: Long, 99: Long, 1: Long, Some(5L), Some(Discontinuity.apply("jatkuva")), Some(8L), newLength = None, newDiscontinuity = None, newEly = None),
        ProjectReservedPart(Sequences.nextViitePrimaryKeySeqValue: Long, 99: Long, 2: Long, Some(15L), Some(Discontinuity.apply("jatkuva")), Some(8L), newLength = None, newDiscontinuity = None, newEly = None))
      projectService.saveProject(rap.copy(reservedParts = addresses))

      val projectLinks = projectLinkDAO.fetchByProjectRoad(99, projectId).sortBy(_.startAddrMValue)
      projectService.updateProjectLinks(projectId, Set(), Seq(projectLinks.head.linkId), LinkStatus.Transfer, "-", 99, 2, 0, Option.empty[Int])
      projectService.updateProjectLinks(projectId, Set(), Seq(projectLinks.last.linkId), LinkStatus.Transfer, "-", 99, 2, 0, Option.empty[Int])

      val afterUpdateProjectLinks = projectLinkDAO.fetchByProjectRoad(99, projectId).sortBy(_.startAddrMValue)

      val mappedRoadwayChanges = projectService.mapChangedRoadwayNumbers(projectLinks, afterUpdateProjectLinks)

      val newRoads = Seq()
      val terminated = Termination(Seq()
      )
      val unchanged = Unchanged(Seq())
      val transferred = Transferred(Seq(
        (
          dummyRoadAddress(roadwayNumber, 99, 1, 0, 5, Some(DateTime.now()),None, 12345, 0 , 5, LinkGeomSource.NormalLinkInterface, geom1),
          afterUpdateProjectLinks.head
        ),
        (
          dummyRoadAddress(roadwayNumber, 99, 2, 0, 15, Some(DateTime.now()),None, 12346, 0 , 15, LinkGeomSource.NormalLinkInterface, geom2),
          afterUpdateProjectLinks.last
        )
      )
      )
      val renumbered = ReNumeration(Seq())

      roadwayPointDAO.create(link1.roadwayNumber, link1.startAddrMValue, link1.createdBy.getOrElse("test"))
      roadwayPointDAO.create(link1.roadwayNumber, link1.endAddrMValue, link1.createdBy.getOrElse("test"))
      roadwayPointDAO.create(link2.roadwayNumber, link2.startAddrMValue, link2.createdBy.getOrElse("test"))
      roadwayPointDAO.create(link2.roadwayNumber, link2.endAddrMValue, link2.createdBy.getOrElse("test"))

      val delta = Delta(DateTime.now, newRoads , terminated, unchanged, transferred, renumbered)
      roadwayChangesDAO.insertDeltaToRoadChangeTable(delta, projectId)

      val roadwayChanges = roadwayChangesDAO.fetchRoadwayChanges(Set(projectId))

      when(mockRoadwayDAO.fetchAllBySectionAndTracks(any[Long], any[Long], any[Set[Track]])).thenReturn(Seq(rw1WithId, rw2WithId))
      roadAddressService.handleRoadwayPointsUpdate(roadwayChanges, mappedRoadwayChanges, "user")

      val newStartRoadwayPoint = roadwayPointDAO.fetchByRoadwayNumberAndAddresses(afterUpdateProjectLinks.last.roadwayNumber, afterUpdateProjectLinks.last.startAddrMValue, afterUpdateProjectLinks.last.startAddrMValue)
      val newEndRoadwayPoint = roadwayPointDAO.fetchByRoadwayNumberAndAddresses(afterUpdateProjectLinks.last.roadwayNumber, afterUpdateProjectLinks.last.endAddrMValue, afterUpdateProjectLinks.last.endAddrMValue)

      newStartRoadwayPoint.head.addrMValue should be (link1.endAddrMValue)
      newEndRoadwayPoint.head.addrMValue should be (link1.endAddrMValue+link2.endAddrMValue)
    }
  }
  */

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
//    when(mockRoadLinkService.getRoadLinksByLinkIdsFromVVH(any[Set[Long]])).thenReturn(Seq(roadLink1))
//    when(mockRoadLinkService.getRoadLinksAndChangesFromVVHWithFrozenAPI(any[BoundingRectangle])).thenReturn((Seq(roadLink1, roadLink2), Seq.empty[ChangeInfo]))
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
//      when(mockRoadLinkService.getRoadLinksByLinkIdsFromVVH(any[Set[Long]])).thenReturn(Seq(roadLink2))
//      when(mockRoadLinkService.getRoadLinksAndChangesFromVVHWithFrozenAPI(any[BoundingRectangle])).thenReturn((Seq(roadLink2, roadLink3), Seq.empty[ChangeInfo]))
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
//    when(mockRoadLinkService.getRoadLinksByLinkIdsFromVVH(any[Set[Long]])).thenReturn(Seq(roadLink1))
//    when(mockRoadLinkService.getRoadLinksAndChangesFromVVHWithFrozenAPI(any[BoundingRectangle])).thenReturn((Seq(roadLink1, roadLink2), Seq.empty[ChangeInfo]))
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
//      when(mockRoadLinkService.getRoadLinksByLinkIdsFromVVH(any[Set[Long]])).thenReturn(Seq(roadLink2))
//      when(mockRoadLinkService.getRoadLinksAndChangesFromVVHWithFrozenAPI(any[BoundingRectangle])).thenReturn((Seq(roadLink2, roadLink3), Seq.empty[ChangeInfo]))
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
//  when(mockRoadLinkService.getCurrentAndHistoryRoadLinksFromVVH(any[Set[Long]])).thenReturn((Seq(roadLink1), Seq(toHistoryLink(ra))))
//
//  runWithRollback {
//  val createdId =  RoadAddressDAO.create(Seq(ra)).head
//  val returnedAddresses = roadAddressService.getRoadAddressLinkById(createdId)
//  returnedAddresses.size should be (1)
//  returnedAddresses.head.id should be (createdId)
//  returnedAddresses.head.linkId should be (baseLinkId)
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

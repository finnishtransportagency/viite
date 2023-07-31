package fi.liikennevirasto.viite

import fi.liikennevirasto.digiroad2.DigiroadEventBus
import fi.liikennevirasto.digiroad2.client.kgv._
import fi.liikennevirasto.digiroad2.postgis.PostGISDatabase.runWithRollback
import fi.liikennevirasto.digiroad2.service.RoadLinkService
import fi.liikennevirasto.viite.Dummies._
import fi.liikennevirasto.viite.dao._
import fi.liikennevirasto.viite.dao.TerminationCode.NoTermination
import fi.liikennevirasto.viite.process._
import fi.liikennevirasto.viite.util.CalibrationPointsUtils
import fi.vaylavirasto.viite.dao.Sequences
import fi.vaylavirasto.viite.geometry.{BoundingRectangle, Point}
import fi.vaylavirasto.viite.model.{AdministrativeClass, Discontinuity, LifecycleStatus, LinkGeomSource, RoadAddressChangeType, RoadLink, SideCode, Track, TrafficDirection}
import org.joda.time.DateTime
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito._
import org.scalatest.{FunSuite, Matchers}
import org.scalatest.mockito.MockitoSugar

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class RoadAddressServiceSpec extends FunSuite with Matchers{
  val mockRoadLinkService: RoadLinkService = MockitoSugar.mock[RoadLinkService]
  val mockEventBus: DigiroadEventBus = MockitoSugar.mock[DigiroadEventBus]
  val mockLinearLocationDAO: LinearLocationDAO = MockitoSugar.mock[LinearLocationDAO]
  val mockRoadwayDAO: RoadwayDAO = MockitoSugar.mock[RoadwayDAO]
  val mockRoadNetworkDAO: RoadNetworkDAO = MockitoSugar.mock[RoadNetworkDAO]
  val mockRoadAddressService = MockitoSugar.mock[RoadAddressService]

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
  val roadAddressService: RoadAddressService = new RoadAddressService(mockRoadLinkService,
                                                                      mockRoadwayDAO,
                                                                      mockLinearLocationDAO,
                                                                      mockRoadNetworkDAO,
                                                                      roadwayPointDAO,
                                                                      nodePointDAO,
                                                                      junctionPointDAO,
                                                                      roadwayAddressMappper,
                                                                      mockEventBus,
                                                                      frozenKGV = false) {

    override def withDynSession[T](f: => T): T = f
    override def withDynTransaction[T](f: => T): T = f
    override val viiteVkmClient: ViiteVkmClient = mockViiteVkmClient
  }

  val nodesAndJunctionsService = new NodesAndJunctionsService(mockRoadwayDAO,
                                                              roadwayPointDAO,
                                                              mockLinearLocationDAO,
                                                              nodeDAO,
                                                              nodePointDAO,
                                                              junctionDAO,
                                                              junctionPointDAO,
                                                              roadwayChangesDAO,
                                                              projectReservedPartDAO)

  val projectService = new ProjectService(roadAddressService,
                                          mockRoadLinkService,
                                          nodesAndJunctionsService,
                                          roadwayDAO,
                                          roadwayPointDAO,
                                          linearLocationDAO,
                                          projectDAO,
                                          projectLinkDAO,
                                          nodeDAO,
                                          nodePointDAO,
                                          junctionPointDAO,
                                          projectReservedPartDAO,
                                          roadwayChangesDAO,
                                          roadwayAddressMapper,
                                          mockEventBus) {
    override def withDynSession[T](f: => T): T = f

    override def withDynTransaction[T](f: => T): T = f
  }

  private def dummyProject(id: Long, status: ProjectState, reservedParts: Seq[ProjectReservedPart] = List.empty[ProjectReservedPart], coordinates: Option[ProjectCoordinates] = None): Project ={
    Project(id, status, "testProject", "testUser", DateTime.parse("1901-01-01"), "testUser", DateTime.parse("1901-01-01"), DateTime.now(), "additional info here", reservedParts, Seq(), Some("current status info"), coordinates)
  }

  private def extractTrafficDirection(sideCode: SideCode, track: Track): TrafficDirection = {
    (sideCode, track) match {
      case (_, Track.Combined) => TrafficDirection.BothDirections
      case (SideCode.TowardsDigitizing, Track.RightSide) => TrafficDirection.TowardsDigitizing
      case (SideCode.TowardsDigitizing, Track.LeftSide) => TrafficDirection.AgainstDigitizing
      case (SideCode.AgainstDigitizing, Track.RightSide) => TrafficDirection.AgainstDigitizing
      case (SideCode.AgainstDigitizing, Track.LeftSide) => TrafficDirection.TowardsDigitizing
      case (_, _) => TrafficDirection.UnknownDirection
    }
  }

    private def toRoadLink(ral: ProjectLink): RoadLink = {
      RoadLink(ral.linkId, ral.geometry, ral.geometryLength, AdministrativeClass.State, extractTrafficDirection(ral.sideCode, ral.track), None, None, LifecycleStatus.InUse, LinkGeomSource.NormalLinkInterface, 749, "")
    }

  test("Test getRoadAddressLinksByLinkId When called by any bounding box and any road number limits Then should return road addresses on normal and history road links") {
    val linearLocations = Seq(
      dummyLinearLocation(roadwayNumber = 1L, orderNumber = 1L, linkId = 123L.toString, startMValue = 0.0, endMValue = 10.0),
      dummyLinearLocation(roadwayNumber = 1L, orderNumber = 2L, linkId = 123L.toString, startMValue = 10.0, endMValue = 20.0),
      dummyLinearLocation(roadwayNumber = 1L, orderNumber = 3L, linkId = 124L.toString, startMValue = 0.0, endMValue = 10.0),
      dummyLinearLocation(roadwayNumber = 1L, orderNumber = 4L, linkId = 125L.toString, startMValue = 0.0, endMValue = 10.0)
    )

    val vvhHistoryRoadLinks = Seq(
      dummyHistoryRoadLink(linkId = 123L.toString, Seq(0.0, 10.0, 20.0)),
      dummyHistoryRoadLink(linkId = 125L.toString, Seq(0.0, 10.0))
    )

    val roadways = Seq(
      dummyRoadway(roadwayNumber = 1L, roadNumber = 1L, roadPartNumber = 1L, startAddrM = 0L, endAddrM = 400L, DateTime.now(), None)
    )

    val roadLinks = Seq(
      dummyRoadLink(linkId = 123L.toString, Seq(0.0, 10.0, 20.0), LinkGeomSource.NormalLinkInterface),
      dummyRoadLink(linkId = 124L.toString, Seq(0.0, 10.0), LinkGeomSource.NormalLinkInterface)
    )

    when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(any[BoundingRectangle], any[Seq[(Int,Int)]])).thenReturn(linearLocations)

    when(mockRoadwayDAO.fetchAllByRoadwayNumbers(any[Set[Long]], any[Boolean])).thenReturn(roadways)

    //Road Link service mocks
    when(mockRoadLinkService.getChangeInfoFromVVHF(any[Set[String]])).thenReturn(Future(Seq.empty))
    when(mockRoadLinkService.getRoadLinksHistoryFromVVH(any[Set[String]])).thenReturn(vvhHistoryRoadLinks)
    when(mockRoadLinkService.getRoadLinksByLinkIds(any[Set[String]])).thenReturn(roadLinks)

    val result = roadAddressService.getRoadAddressLinksByBoundingBox(BoundingRectangle(Point(0.0, 0.0), Point(0.0, 20.0)), Seq())

    verify(mockRoadLinkService, times(1)).getChangeInfoFromVVHF(Set(123L.toString, 124L.toString, 125L.toString))
    verify(mockRoadLinkService, times(1)).getRoadLinksHistoryFromVVH(Set(123L.toString, 124L.toString, 125L.toString))
    verify(mockRoadLinkService, times(1)).getRoadLinksByLinkIds(Set(123L.toString, 124L.toString, 125L.toString))

    result.size should be (3)
  }

  test("Test getRoadAddressLinksByLinkId When called by any bounding box and any road number limits Then should not filter out floatings") {
    val linearLocations = Seq(
      dummyLinearLocation(roadwayNumber = 1L, orderNumber = 1L, linkId = 123L.toString, startMValue = 0.0, endMValue = 10.0),
      dummyLinearLocation(roadwayNumber = 1L, orderNumber = 2L, linkId = 123L.toString, startMValue = 10.0, endMValue = 20.0),
      dummyLinearLocation(roadwayNumber = 1L, orderNumber = 3L, linkId = 124L.toString, startMValue = 0.0, endMValue = 10.0),
      dummyLinearLocation(roadwayNumber = 1L, orderNumber = 4L, linkId = 125L.toString, startMValue = 0.0, endMValue = 10.0)
    )

    val roadways = Seq(
      dummyRoadway(roadwayNumber = 1L, roadNumber = 1L, roadPartNumber = 1L, startAddrM = 0L, endAddrM = 400L, DateTime.now(), None)
    )

    val vvhHistoryRoadLinks = Seq(
      dummyHistoryRoadLink(linkId = 123L.toString, Seq(0.0, 10.0, 20.0)),
      dummyHistoryRoadLink(linkId = 125L.toString, Seq(0.0, 10.0))
    )

    val roadLinks = Seq(
      dummyRoadLink(linkId = 123L.toString, Seq(0.0, 10.0, 20.0), LinkGeomSource.NormalLinkInterface),
      dummyRoadLink(linkId = 124L.toString, Seq(0.0, 10.0), LinkGeomSource.NormalLinkInterface)
    )

    when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(any[BoundingRectangle], any[Seq[(Int,Int)]])).thenReturn(linearLocations)

    when(mockRoadwayDAO.fetchAllByRoadwayNumbers(any[Set[Long]], any[Boolean])).thenReturn(roadways)

    //Road Link service mocks
    when(mockRoadLinkService.getChangeInfoFromVVHF(any[Set[String]])).thenReturn(Future(Seq.empty))
    when(mockRoadLinkService.getRoadLinksHistoryFromVVH(any[Set[String]])).thenReturn(vvhHistoryRoadLinks)
    when(mockRoadLinkService.getRoadLinksByLinkIds(any[Set[String]])).thenReturn(roadLinks)

    val roadAddressLinks = roadAddressService.getRoadAddressLinksByBoundingBox(BoundingRectangle(Point(0.0, 0.0), Point(0.0, 20.0)), Seq())

    roadAddressLinks.size should be (3)
    roadAddressLinks.map(_.linkId).distinct should contain allOf ("123","124")
  }

  test("Test getRoadAddressesWithLinearGeometry When municipality has road addresses on top of suravage and complementary road links Then should not return floatings") {
    val linearLocations = Seq(
      dummyLinearLocation(roadwayNumber = 1L, orderNumber = 1L, linkId = 123L.toString, startMValue = 0.0, endMValue = 10.0),
      dummyLinearLocation(roadwayNumber = 1L, orderNumber = 2L, linkId = 123L.toString, startMValue = 10.0, endMValue = 20.0),
      dummyLinearLocation(roadwayNumber = 1L, orderNumber = 3L, linkId = 124L.toString, startMValue = 0.0, endMValue = 10.0),
      dummyLinearLocation(roadwayNumber = 1L, orderNumber = 4L, linkId = 125L.toString, startMValue = 0.0, endMValue = 10.0)
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

  def toRoadwayAndLinearLocation(p: ProjectLink):(LinearLocation, Roadway) = {
    val startDate = p.startDate.getOrElse(DateTime.now()).minusDays(1)

    (LinearLocation(-1000, 1, p.linkId, p.startMValue, p.endMValue, p.sideCode, p.linkGeometryTimeStamp,
      (CalibrationPointsUtils.toCalibrationPointReference(p.startCalibrationPoint),
        CalibrationPointsUtils.toCalibrationPointReference(p.endCalibrationPoint)),
      p.geometry, p.linkGeomSource,
      p.roadwayNumber, Some(startDate), p.endDate),
      Roadway(-1000, p.roadwayNumber, p.roadNumber, p.roadPartNumber, p.administrativeClass, p.track, p.discontinuity, p.startAddrMValue, p.endAddrMValue, p.reversed, startDate, p.endDate,
        p.createdBy.getOrElse("-"), p.roadName, p.ely, TerminationCode.NoTermination, DateTime.now(), None))
  }

  test("Test getRoadAddress When the biggest address in section is greater than the parameter $addressM Then should filter out all the road addresses with start address less than $addressM") {
    val linearLocations = Seq(
      dummyLinearLocation(roadwayNumber = 1L, orderNumber = 1L, linkId = 123L.toString, startMValue = 0.0, endMValue = 10.0),
      dummyLinearLocation(roadwayNumber = 1L, orderNumber = 2L, linkId = 123L.toString, startMValue = 10.0, endMValue = 20.0),
      dummyLinearLocation(roadwayNumber = 1L, orderNumber = 3L, linkId = 124L.toString, startMValue = 0.0, endMValue = 10.0),
      dummyLinearLocation(roadwayNumber = 1L, orderNumber = 4L, linkId = 125L.toString, startMValue = 0.0, endMValue = 10.0)
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
      dummyLinearLocation(roadwayNumber = 1L, orderNumber = 1L, linkId = 123L.toString, startMValue = 0.0, endMValue = 10.0),
      dummyLinearLocation(roadwayNumber = 1L, orderNumber = 2L, linkId = 123L.toString, startMValue = 10.0, endMValue = 20.0),
      dummyLinearLocation(roadwayNumber = 1L, orderNumber = 3L, linkId = 124L.toString, startMValue = 0.0, endMValue = 10.0),
      dummyLinearLocation(roadwayNumber = 1L, orderNumber = 4L, linkId = 125L.toString, startMValue = 0.0, endMValue = 10.0)
    )

    val roadways = Seq(
      dummyRoadway(roadwayNumber = 1L, roadNumber = 1L, roadPartNumber = 1L, startAddrM = 0L, endAddrM = 400L, DateTime.now(), None)
    )

    when(mockRoadwayDAO.fetchAllByRoadwayNumbers(any[Set[Long]], any[Boolean])).thenReturn(roadways)
    when(mockLinearLocationDAO.fetchRoadwayByLinkId(any[Set[String]])).thenReturn(linearLocations)

    val result = roadAddressService.getRoadAddressWithLinkIdAndMeasure(linkId = 123L.toString, Some(0.0), Some(9.999))

    result.size should be (1)
    result.head.linkId should be (123L.toString)
    result.head.startMValue should be (0.0)
    result.head.endMValue should be (10.0)
  }

  test("Test getRoadAddressWithLinkIdAndMeasure When only link id is given Then return all the road addresses in the link id") {
    val linearLocations = List(
      dummyLinearLocation(roadwayNumber = 1L, orderNumber = 1L, linkId = 123L.toString, startMValue = 0.0, endMValue = 10.0),
      dummyLinearLocation(roadwayNumber = 1L, orderNumber = 2L, linkId = 123L.toString, startMValue = 10.0, endMValue = 20.0),
      dummyLinearLocation(roadwayNumber = 1L, orderNumber = 3L, linkId = 124L.toString, startMValue = 0.0, endMValue = 10.0),
      dummyLinearLocation(roadwayNumber = 1L, orderNumber = 4L, linkId = 125L.toString, startMValue = 0.0, endMValue = 10.0)
    )

    val roadways = Seq(
      dummyRoadway(roadwayNumber = 1L, roadNumber = 1L, roadPartNumber = 1L, startAddrM = 0L, endAddrM = 400L, DateTime.now(), None)
    )

    when(mockRoadwayDAO.fetchAllByRoadwayNumbers(any[Set[Long]], any[Boolean])).thenReturn(roadways)
    when(mockLinearLocationDAO.fetchRoadwayByLinkId(any[Set[String]])).thenReturn(linearLocations)

    val result = roadAddressService.getRoadAddressWithLinkIdAndMeasure(linkId = 123L.toString, None, None)

    result.size should be (2)
    val linkIds = result.map(_.linkId).distinct
    linkIds.size should be (1)
    linkIds.head should be (123L.toString)
  }

  test("Test getRoadAddressWithLinkIdAndMeasure When only link id and start measure is given Then return all road addresses in link id and with start measure greater or equal than $startM") {
    val linearLocations = List(
      dummyLinearLocation(roadwayNumber = 1L, orderNumber = 1L, linkId = 123L.toString, startMValue = 0.0, endMValue = 10.0),
      dummyLinearLocation(roadwayNumber = 1L, orderNumber = 2L, linkId = 123L.toString, startMValue = 10.0, endMValue = 20.0),
      dummyLinearLocation(roadwayNumber = 1L, orderNumber = 3L, linkId = 124L.toString, startMValue = 0.0, endMValue = 10.0),
      dummyLinearLocation(roadwayNumber = 1L, orderNumber = 4L, linkId = 125L.toString, startMValue = 0.0, endMValue = 10.0)
    )

    val roadways = Seq(
      dummyRoadway(roadwayNumber = 1L, roadNumber = 1L, roadPartNumber = 1L, startAddrM = 0L, endAddrM = 400L, DateTime.now(), None)
    )

    when(mockRoadwayDAO.fetchAllByRoadwayNumbers(any[Set[Long]], any[Boolean])).thenReturn(roadways)
    when(mockLinearLocationDAO.fetchRoadwayByLinkId(any[Set[String]])).thenReturn(linearLocations)

    val result = roadAddressService.getRoadAddressWithLinkIdAndMeasure(linkId = 123L.toString, Some(10.0), None)

    result.size should be (1)
    result.head.linkId should be (123L.toString)
    result.head.startMValue should be (10.0)
    result.head.endMValue should be (20.0)
  }

  test("Test getRoadAddressWithLinkIdAndMeasure When only link id and end measure is given Then return all road addresses in link id and with end measure less or equal than $endM") {
    val linearLocations = List(
      dummyLinearLocation(roadwayNumber = 1L, orderNumber = 1L, linkId = 123L.toString, startMValue = 0.0, endMValue = 10.0),
      dummyLinearLocation(roadwayNumber = 1L, orderNumber = 2L, linkId = 123L.toString, startMValue = 10.0, endMValue = 20.0),
      dummyLinearLocation(roadwayNumber = 1L, orderNumber = 3L, linkId = 124L.toString, startMValue = 0.0, endMValue = 10.0),
      dummyLinearLocation(roadwayNumber = 1L, orderNumber = 4L, linkId = 125L.toString, startMValue = 0.0, endMValue = 10.0)
    )

    val roadways = Seq(
      dummyRoadway(roadwayNumber = 1L, roadNumber = 1L, roadPartNumber = 1L, startAddrM = 0L, endAddrM = 400L, DateTime.now(), None)
    )

    when(mockRoadwayDAO.fetchAllByRoadwayNumbers(any[Set[Long]], any[Boolean])).thenReturn(roadways)
    when(mockLinearLocationDAO.fetchRoadwayByLinkId(any[Set[String]])).thenReturn(linearLocations)

    val result = roadAddressService.getRoadAddressWithLinkIdAndMeasure(linkId = 123L.toString, None, Some(10.0))

    result.size should be (1)
    result.head.linkId should be (123L.toString)
    result.head.startMValue should be (0.0)
    result.head.endMValue should be (10.0)
  }

  test("Test getRoadAddressesFiltered When roadway have less and greater addresses than given measures Then returns only road addresses in between given address measures") {
    val linearLocations = List(
      dummyLinearLocation(roadwayNumber = 1L, orderNumber = 1L, linkId = 123L.toString, startMValue = 0.0, endMValue = 10.0),
      dummyLinearLocation(roadwayNumber = 1L, orderNumber = 2L, linkId = 123L.toString, startMValue = 10.0, endMValue = 20.0),
      dummyLinearLocation(roadwayNumber = 1L, orderNumber = 3L, linkId = 124L.toString, startMValue = 0.0, endMValue = 10.0),
      dummyLinearLocation(roadwayNumber = 1L, orderNumber = 4L, linkId = 125L.toString, startMValue = 0.0, endMValue = 10.0)
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
      dummyLinearLocation(roadwayNumber = 1L, orderNumber = 1L, linkId = 123L.toString, startMValue = 0.0, endMValue = 10.0),
      dummyLinearLocation(roadwayNumber = 1L, orderNumber = 2L, linkId = 123L.toString, startMValue = 10.0, endMValue = 20.0),
      dummyLinearLocation(roadwayNumber = 1L, orderNumber = 3L, linkId = 124L.toString, startMValue = 0.0, endMValue = 10.0),
      dummyLinearLocation(roadwayNumber = 1L, orderNumber = 4L, linkId = 125L.toString, startMValue = 0.0, endMValue = 10.0)
    )

    val roadways = Seq(
      dummyRoadway(roadwayNumber = 1L, roadNumber = 1L, roadPartNumber = 1L, startAddrM = 0L, endAddrM = 400L, DateTime.now(), None)
    )

    when(mockRoadwayDAO.fetchAllByRoadwayNumbers(any[Set[Long]], any[Boolean])).thenReturn(roadways)
    when(mockLinearLocationDAO.fetchRoadwayByLinkId(any[Set[String]])).thenReturn(linearLocations)

    val result = roadAddressService.getRoadAddressByLinkIds(Set(123L.toString))

    result.size should be (2)
    result.map(_.linkId).distinct.size should be (1)
    result.map(_.linkId).distinct should be (List(123L.toString))
    result.head.startMValue should be (0.0)
    result.head.endMValue should be (10.0)
    result.last.startMValue should be (10.0)
    result.last.endMValue should be (20.0)
  }

  test("Test SearchService") {
    val point = Point(1D, 1D, 1D)
    val linkId = 123L.toString

    val towardsDigitizingLinearLocation = List(
      dummyLinearLocationWithGeometry(-1000L, roadwayNumber = 1L, orderNumber = 1L, linkId = linkId, startMValue = 0.0, endMValue = 10.0, SideCode.TowardsDigitizing, Seq(Point(0.0, 0.0), Point(1L + .5, 0.0)))
    )

    val againstDigitizingLinearLocation = List(
      dummyLinearLocationWithGeometry(-1000L, roadwayNumber = 1L, orderNumber = 1L, linkId = linkId, startMValue = 0.0, endMValue = 10.0, SideCode.AgainstDigitizing, Seq(Point(0.0, 0.0), Point(1L + .5, 0.0)))
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
      RoadLink(linkId, Seq(Point(0.0, 10.0), Point(0.0, 15.0)), 10.0, AdministrativeClass.Municipality, TrafficDirection.TowardsDigitizing, None, None, LifecycleStatus.UnknownLifecycleStatus, LinkGeomSource.NormalLinkInterface, 235, "")
    )


    // Test search by street name
    when(mockRoadLinkService.getRoadLinkMiddlePointBySourceId(any[Long])).thenReturn(None)
    var result = roadAddressService.getSearchResults(Option("nuolirinne"))
    result.size should be(1)
    result.head.contains("street") should be(true)

    // Test search by road number
    result = roadAddressService.getSearchResults(Option("1"))
    result.size should be(2)
    result(1)("road").head.asInstanceOf[RoadAddress].roadNumber should be(1)

    // Test search by linkId
    when(mockRoadLinkService.getMidPointByLinkId(any[String])).thenReturn(Option(point))
    result = roadAddressService.getSearchResults(Option("00121516-ca15-4254-8251-cf5092b6ddca:1"))
    result.size should be(1)
    result.head("linkId").head.asInstanceOf[Some[Point]].x should be(point)
    reset(mockRoadLinkService)

    // Test search by mtkId
    when(mockRoadLinkService.getRoadLinkMiddlePointBySourceId(any[Long])).thenReturn(Option(point))
    result = roadAddressService.getSearchResults(Option("1"))
    result.size should be(2)
    result.head("mtkId").head.asInstanceOf[Some[Point]].x should be(point)
    result(1)("road").head.asInstanceOf[RoadAddress].roadNumber should be(1)

    // Test search by road number, road part number
    result = roadAddressService.getSearchResults(Option("1 1"))
    result.size should be(1)
    result.head("road").head.asInstanceOf[RoadAddress].roadNumber should be(1)

    // Test search by road number, road part number and M number TowardsDigitizing
    when(mockRoadLinkService.getRoadLinksByLinkIds(any[Set[String]])).thenReturn(towardsDigitizingRoadLink)
    result = roadAddressService.getSearchResults(Option("1 1 1"))
    result.size should be(1)
    result.head.contains("roadM") should be(true)
    result.head("roadM").head.asInstanceOf[Some[Point]].x should be(Point(0.0, 11, 0.0))

    // Test search by road number, road part number and M number AgainstDigitizing
    when(mockLinearLocationDAO.fetchByRoadways(any[Set[Long]])).thenReturn(againstDigitizingLinearLocation)
    result = roadAddressService.getSearchResults(Option("1 1 9"))
    result.size should be(1)
    result.head.contains("roadM") should be(true)
    result.head("roadM").head.asInstanceOf[Some[Point]].x should be(Point(0.0, 11, 0.0))

  }

  test("Test sortRoadWayWithNewRoads When changeSet has new links Then update the order of all the roadway") {
    val linearLocations = List(
      dummyLinearLocation(roadwayNumber = 1L, orderNumber = 1L, linkId = 123L.toString, startMValue = 0.0, endMValue = 10.0),
      dummyLinearLocation(roadwayNumber = 1L, orderNumber = 2L, linkId = 124L.toString, startMValue = 0.0, endMValue = 20.0),
      dummyLinearLocation(roadwayNumber = 1L, orderNumber = 3L, linkId = 125L.toString, startMValue = 0.0, endMValue = 10.0),
      dummyLinearLocation(roadwayNumber = 1L, orderNumber = 4L, linkId = 126L.toString, startMValue = 0.0, endMValue = 10.0)
    )
    val newLinearLocations = List(
      dummyLinearLocation(roadwayNumber = 1L, orderNumber = 2.1, linkId = 1267L.toString, startMValue = 0.0, endMValue = 10.0),
      dummyLinearLocation(roadwayNumber = 1L, orderNumber = 3.1, linkId = 1268L.toString, startMValue = 0.0, endMValue = 20.0)
    )

    val adjustedLinearLocations = roadAddressService.sortRoadWayWithNewRoads(linearLocations.groupBy(_.roadwayNumber), newLinearLocations)
    adjustedLinearLocations(1L).size should be (linearLocations.size + newLinearLocations.size)
    adjustedLinearLocations(1L).find(_.linkId == 123L.toString).get.orderNumber should be (1)
    adjustedLinearLocations(1L).find(_.linkId == 124L.toString).get.orderNumber should be (2)
    adjustedLinearLocations(1L).find(_.linkId == 1267L.toString).get.orderNumber should be (3)
    adjustedLinearLocations(1L).find(_.linkId == 125L.toString).get.orderNumber should be (4)
    adjustedLinearLocations(1L).find(_.linkId == 1268L.toString).get.orderNumber should be (5)
    adjustedLinearLocations(1L).find(_.linkId == 126L.toString).get.orderNumber should be (6)
  }

  test("Test sortRoadWayWithNewRoads When there are no linear locations to create Then should return empty list") {
    val linearLocations = List(
      dummyLinearLocation(roadwayNumber = 1L, orderNumber = 1L, linkId = 123L.toString, startMValue = 0.0, endMValue = 10.0),
      dummyLinearLocation(roadwayNumber = 1L, orderNumber = 2L, linkId = 124L.toString, startMValue = 0.0, endMValue = 20.0),
      dummyLinearLocation(roadwayNumber = 1L, orderNumber = 3L, linkId = 125L.toString, startMValue = 0.0, endMValue = 10.0),
      dummyLinearLocation(roadwayNumber = 1L, orderNumber = 4L, linkId = 126L.toString, startMValue = 0.0, endMValue = 10.0)
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
      dummyLinearLocation(roadwayNumber = 1L, orderNumber = 1L, linkId = 123L.toString, startMValue = 0.0, endMValue = 10.0),
      dummyLinearLocation(roadwayNumber = 1L, orderNumber = 2L, linkId = 123L.toString, startMValue = 0.0, endMValue = 20.0),
      dummyLinearLocation(roadwayNumber = 1L, orderNumber = 3L, linkId = 124L.toString, startMValue = 0.0, endMValue = 10.0),
      dummyLinearLocation(roadwayNumber = 1L, orderNumber = 4L, linkId = 125L.toString, startMValue = 0.0, endMValue = 10.0)
    )

    val roadLinks = (Seq(
      RoadLink(123L.toString, newGeom0010, 17, AdministrativeClass.apply(2), TrafficDirection.TowardsDigitizing, None, None, municipalityCode = 99999, sourceId = ""),
      RoadLink(123L.toString, newGeom1020, 17, AdministrativeClass.apply(2), TrafficDirection.TowardsDigitizing, None, None, municipalityCode = 99999, sourceId = ""),
      RoadLink(124L.toString, newGeom3040, 17, AdministrativeClass.apply(2), TrafficDirection.TowardsDigitizing, None, None, municipalityCode = 99999, sourceId = ""),
      RoadLink(125L.toString, newGeom4050, 17, AdministrativeClass.apply(2), TrafficDirection.TowardsDigitizing, None, None, municipalityCode = 99999, sourceId = "")
    ), Seq.empty[ChangeInfo])

    val roadways = Seq(
      dummyRoadway(roadwayNumber = 1L, roadNumber = 1L, roadPartNumber = 1L, startAddrM = 0L, endAddrM = 400L, DateTime.now(), None)
    )

    when(mockRoadwayDAO.fetchAllByRoadwayNumbers(any[Set[Long]], any[DateTime])).thenReturn(roadways)
    when(mockLinearLocationDAO.fetchRoadwayByLinkId(any[Set[String]])).thenReturn(linearLocations)


    when(mockRoadLinkService.getRoadLinksWithComplementaryAndChangesFromVVH(99999)).thenReturn(roadLinks)
//    when(mockRoadLinkService.getComplementaryRoadLinksFromVVH(99999)).thenReturn(Seq())
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
      val link1 = dummyProjectLink(99, 2, Track.Combined, Discontinuity.Continuous, 0, 5, Some(DateTime.now()), None, 12345.toString, 0,  5, SideCode.TowardsDigitizing, RoadAddressChangeType.Termination, projectId, AdministrativeClass.State, geom1, roadwayNumber1)
      val link2 = dummyProjectLink(99, 2, Track.Combined, Discontinuity.EndOfRoad, 5, 20, Some(DateTime.now()), None, 12346.toString, 0, 15, SideCode.TowardsDigitizing, RoadAddressChangeType.Transfer, projectId, AdministrativeClass.State, geom2, roadwayNumber2)

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
      when(mockRoadLinkService.getRoadLinksByLinkIds(any[Set[String]])).thenReturn(pls.map(toRoadLink))

      projectDAO.create(rap)

      val ra1 = RoadAddress(0, lc1.id, 99, 2, rw1.administrativeClass, rw1.track, rw1.discontinuity, rw1.startAddrMValue, rw1.endAddrMValue, Some(rw1.startDate), rw1.endDate, Some(rw1.createdBy), lc1.linkId, lc1.startMValue, lc1.endMValue, lc1.sideCode, 1000000, (None, None), lc1.geometry, lc1.linkGeomSource, rw1.ely, NoTermination, rw1.roadwayNumber, None, None, None)
      val ra2 = RoadAddress(0, lc2.id, 99, 2, rw2.administrativeClass, rw2.track, rw2.discontinuity, rw2.startAddrMValue, rw2.endAddrMValue, Some(rw2.startDate), rw2.endDate, Some(rw2.createdBy), lc2.linkId, lc2.startMValue, lc2.endMValue, lc2.sideCode, 1000000, (None, None), lc2.geometry, lc2.linkGeomSource, rw2.ely, NoTermination, rw2.roadwayNumber, None, None, None)

      when(mockRoadAddressService.getRoadAddressWithRoadAndPart(any[Long], any[Long], any[Boolean], any[Boolean], any[Boolean])).thenReturn(Seq(ra1, ra2))
      when(mockLinearLocationDAO.fetchByRoadways(Set(rw1.roadwayNumber))).thenReturn(Seq(lc1))
      when(mockLinearLocationDAO.fetchByRoadways(Set(rw2.roadwayNumber))).thenReturn(Seq(lc2))
      when(mockRoadwayDAO.fetchAllByRoadAndPart(99, 2)).thenReturn(Seq(rw2WithId))

      projectReservedPartDAO.reserveRoadPart(projectId, 99, 2, "u")
      projectLinkDAO.create(pls.map(_.copy(id = NewIdValue)))
      val projectLinks = projectLinkDAO.fetchByProjectRoad(99, projectId).sortBy(_.startAddrMValue)

      projectService.updateProjectLinks(projectId, Set(), Seq(projectLinks.head.linkId), RoadAddressChangeType.Termination, "-", 99, 2, 0, Option.empty[Int])
      projectService.updateProjectLinks(projectId, Set(), Seq(projectLinks.last.linkId), RoadAddressChangeType.Transfer,    "-", 99, 2, 0, Option.empty[Int])

      roadwayPointDAO.create(link1.roadwayNumber, link1.startAddrMValue, link1.createdBy.getOrElse("test"))
      roadwayPointDAO.create(link1.roadwayNumber, link1.endAddrMValue, link2.createdBy.getOrElse("test"))
      roadwayPointDAO.create(link2.roadwayNumber, link2.startAddrMValue, link2.createdBy.getOrElse("test"))
      roadwayPointDAO.create(link2.roadwayNumber, link2.endAddrMValue, link2.createdBy.getOrElse("test"))

      val afterUpdateProjectLinks = projectLinkDAO.fetchByProjectRoad(99, projectId).sortBy(_.startAddrMValue)
      val (afterChangesTerminated, afterChangesTransfer): (Seq[ProjectLink], Seq[ProjectLink]) = afterUpdateProjectLinks.partition(_.status == RoadAddressChangeType.Termination)
      val beforeChangesTransfer = afterChangesTransfer.head.copy(roadwayNumber = roadwayNumber2)
      val generatedProperRoadwayNumbersAfterChanges = Seq(afterChangesTerminated.head, afterChangesTransfer.head)
      val mappedRoadwayChanges = projectLinkDAO.fetchProjectLinksChange(projectId)


      val roadwayPointsBeforeTerminatedLink = afterUpdateProjectLinks.filter(_.status == RoadAddressChangeType.Termination).map(_.roadwayNumber).distinct.flatMap{ rwp=>
      roadwayPointDAO.fetchByRoadwayNumber(rwp)
      }
      val roadwayPointsBeforeTransferLink = Seq(beforeChangesTransfer).filter(_.status == RoadAddressChangeType.Transfer).map(_.roadwayNumber).distinct.flatMap{ rwp=>
      roadwayPointDAO.fetchByRoadwayNumber(rwp)
      }

      val reservedParts = Seq(ProjectReservedPart(0, 99, 2, Some(20), Some(Discontinuity.Continuous), Some(8L), None, None, None, Some(12345L.toString)))

      val project = projectDAO.fetchById(projectId).get
      roadwayChangesDAO.insertDeltaToRoadChangeTable(projectId, Some(project.copy(reservedParts = reservedParts)))

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

      val projectId = Sequences.nextViiteProjectId
      val rap =  dummyProject(projectId, ProjectState.Incomplete, Seq(), None).copy(startDate = DateTime.parse("2019-10-10"))
      val id1 = Sequences.nextRoadwayId
      val id2 = id1+1
      val linearLocationId = Sequences.nextLinearLocationId
      val link1 = dummyProjectLink(99, 2, Track.Combined, Discontinuity.Continuous, 0, 5, Some(DateTime.now()), None, 12345.toString, 0,  5, SideCode.TowardsDigitizing, RoadAddressChangeType.Termination, projectId, AdministrativeClass.State, geom1, roadwayNumber1)
      val link2 = dummyProjectLink(99, 2, Track.Combined, Discontinuity.EndOfRoad, 5, 20, Some(DateTime.now()), None, 12346.toString, 0, 15, SideCode.AgainstDigitizing, RoadAddressChangeType.Transfer, projectId, AdministrativeClass.State, geom2, roadwayNumber2)

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
      when(mockRoadLinkService.getRoadLinksByLinkIds(any[Set[String]])).thenReturn(pls.map(toRoadLink))

      projectDAO.create(rap)

      val ra1 = RoadAddress(0, lc1.id, 99, 2, rw1.administrativeClass, rw1.track, rw1.discontinuity, rw1.startAddrMValue, rw1.endAddrMValue, Some(rw1.startDate), rw1.endDate, Some(rw1.createdBy), lc1.linkId, lc1.startMValue, lc1.endMValue, lc1.sideCode, 1000000, (None, None), lc1.geometry, lc1.linkGeomSource, rw1.ely, NoTermination, rw1.roadwayNumber, None, None, None)
      val ra2 = RoadAddress(0, lc2.id, 99, 2, rw2.administrativeClass, rw2.track, rw2.discontinuity, rw2.startAddrMValue, rw2.endAddrMValue, Some(rw2.startDate), rw2.endDate, Some(rw2.createdBy), lc2.linkId, lc2.startMValue, lc2.endMValue, lc2.sideCode, 1000000, (None, None), lc2.geometry, lc2.linkGeomSource, rw2.ely, NoTermination, rw2.roadwayNumber, None, None, None)

      when(mockRoadAddressService.getRoadAddressWithRoadAndPart(any[Long], any[Long], any[Boolean], any[Boolean], any[Boolean])).thenReturn(Seq(ra1, ra2))
      when(mockLinearLocationDAO.fetchByRoadways(Set(rw1.roadwayNumber))).thenReturn(Seq(lc1))
      when(mockLinearLocationDAO.fetchByRoadways(Set(rw2.roadwayNumber))).thenReturn(Seq(lc2))
      when(mockRoadwayDAO.fetchAllByRoadAndPart(99, 2)).thenReturn(Seq(rw2WithId))

      projectReservedPartDAO.reserveRoadPart(projectId, 99, 2, "u")
      projectLinkDAO.create(pls.map(_.copy(id = NewIdValue)))
      val projectLinks = projectLinkDAO.fetchByProjectRoad(99, projectId).sortBy(_.startAddrMValue)

      projectService.updateProjectLinks(projectId, Set(), Seq(projectLinks.head.linkId), RoadAddressChangeType.Termination, "-", 99, 2, 0, Option.empty[Int])
      projectService.updateProjectLinks(projectId, Set(), Seq(projectLinks.last.linkId), RoadAddressChangeType.Transfer,    "-", 99, 2, 0, Option.empty[Int])

      projectService.changeDirection(projectId, 99L, 2L, Seq(LinkToRevert(link2.id, link2.linkId, RoadAddressChangeType.Transfer.value, Seq())), ProjectCoordinates(link2.geometry.head.x, link2.geometry.head.y), "testuser")

      roadwayPointDAO.create(link1.roadwayNumber, link1.startAddrMValue, link1.createdBy.getOrElse("test"))
      roadwayPointDAO.create(link1.roadwayNumber, link1.endAddrMValue, link2.createdBy.getOrElse("test"))
      roadwayPointDAO.create(link2.roadwayNumber, link2.startAddrMValue, link2.createdBy.getOrElse("test"))
      roadwayPointDAO.create(link2.roadwayNumber, link2.endAddrMValue, link2.createdBy.getOrElse("test"))

      val afterUpdateProjectLinks = projectLinkDAO.fetchByProjectRoad(99, projectId).sortBy(_.startAddrMValue)
      val (afterChangesTerminated, afterChangesTransfer): (Seq[ProjectLink], Seq[ProjectLink]) = afterUpdateProjectLinks.partition(_.status == RoadAddressChangeType.Termination)
      val beforeChangesTransfer = afterChangesTransfer.head.copy(roadwayNumber = roadwayNumber2)
      val generatedProperRoadwayNumbersAfterChanges = Seq(afterChangesTerminated.head, afterChangesTransfer.head)
      val mappedRoadwayChanges = projectLinkDAO.fetchProjectLinksChange(projectId)
      val newRoads = Seq()
      val terminated = Termination(Seq(
        (
          dummyRoadAddress(roadwayNumber1, 99, 2, 0, 5, Some(DateTime.now()), None, 12345.toString, 0, 5, LinkGeomSource.NormalLinkInterface, geom1),
          afterUpdateProjectLinks.head
        )
      )
      )
      val unchanged = Unchanged(Seq())
      val transferred = Transferred(Seq(
        (
          dummyRoadAddress(roadwayNumber1, 99, 2, 5, 20, Some(DateTime.now()), None, 12346.toString, 0, 15, LinkGeomSource.NormalLinkInterface, geom2),
          beforeChangesTransfer
        )
      )
      )
      val renumbered = ReNumeration(Seq())

      val roadwayPointsBeforeTerminatedLink = afterUpdateProjectLinks.filter(_.status == RoadAddressChangeType.Termination).map(_.roadwayNumber).distinct.flatMap{ rwp=>
        roadwayPointDAO.fetchByRoadwayNumber(rwp)
      }
      val roadwayPointsBeforeTransferLink = Seq(beforeChangesTransfer).filter(_.status == RoadAddressChangeType.Transfer).map(_.roadwayNumber).distinct.flatMap{ rwp=>
        roadwayPointDAO.fetchByRoadwayNumber(rwp)
      }
      val reservedParts = Seq(ProjectReservedPart(0, 99, 2, Some(20), Some(Discontinuity.Continuous), Some(8L), None, None, None, Some(12345L.toString)))
      val formedParts = Seq(ProjectReservedPart(0, 99, 2, None, None, None, Some(15), Some(Discontinuity.Continuous), Some(8L), Some(12345L.toString)))

      val project = projectDAO.fetchById(projectId).get
      roadwayChangesDAO.insertDeltaToRoadChangeTable(projectId, Some(project.copy(reservedParts = reservedParts, formedParts = formedParts)))
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

  test("Test handleRoadwayPoints When dual roadway point is no longer dual roadway point then addrM's and roadway number in that roadway point should change correctly and new roadway point should be created NEW + TRANSFER + TRANSFER") {
    runWithRollback {
      /*

          BEFORE PROJECT                                     AFTER PROJECT
    <---R3-----O*------R1-----                         <---R3-----O<-----R1--------------R1-------
               |                                                  |
               | R1                                               | R2
               |                                                  |
               |                                                  |
               v                                                  v
        * Note
          * 0*: Dual roadway point and Node
          * 0: Node
          * R1: Road 1 reserved to project (road number 19510)
          * R2: Road 2 new road number (road number 19527)
          * R3: Road 3 outside of project
       */

      val projectId = Sequences.nextViiteProjectId
      val projectName = "dualRoadwayPoint"
      val ely = 14
      val user = "silari"
      val date = DateTime.parse("2022-05-09")
      val originalRoadwayNumber1 = Sequences.nextRoadwayNumber
      val newRoadwayId1 = Sequences.nextRoadwayId
      val newRoadwayId2 = Sequences.nextRoadwayId
      val newRoadwayId3 = Sequences.nextRoadwayId
      val newRoadwayNumber1 = Sequences.nextRoadwayNumber
      val newRoadwayNumber2 = Sequences.nextRoadwayNumber
      val newRoadwayNumber3 = Sequences.nextRoadwayNumber
      val llId = Sequences.nextLinearLocationId

      // create roadway points (before project situation)
      val rwpId1 = roadwayPointDAO.create(originalRoadwayNumber1, 0, "rw1originalFirst")
      val rwpIdDual = roadwayPointDAO.create(originalRoadwayNumber1, 10, "dualRWP") // THIS IS THE DUAL ROADWAY POINT
      val rwpId3 = roadwayPointDAO.create(originalRoadwayNumber1, 20, "rw1originalLast")

      // create roadways (after projects changes situation)
      val newRoadwaysFor19510 = Seq(
        Roadway(Sequences.nextRoadwayId, newRoadwayNumber1, 19510, 1, AdministrativeClass.State, Track.Combined, Discontinuity.Continuous, 0, 5, reversed=false, DateTime.now(), None, "silari", Some("testRoadName"), 14, TerminationCode.NoTermination, DateTime.now(), None),
        Roadway(Sequences.nextRoadwayId, newRoadwayNumber2, 19510, 1, AdministrativeClass.State, Track.Combined, Discontinuity.EndOfRoad, 5, 15, reversed=false, DateTime.now(), None, "silari", Some("testRoadName"), 14, TerminationCode.NoTermination, DateTime.now(), None)
      )

      val newRoadwaysFor19527 = Seq(
        Roadway(Sequences.nextRoadwayId, newRoadwayNumber3, 19527, 1, AdministrativeClass.State, Track.Combined, Discontinuity.EndOfRoad, 0, 10, reversed=false, DateTime.now(), None, "silari", Some("testRoadName"), 14, TerminationCode.NoTermination, DateTime.now(), None)
      )

      val newRoadways = newRoadwaysFor19510.union(newRoadwaysFor19527)
      roadwayDAO.create(newRoadways)

      // create project link changes that will be handed to the handleRoadwayPointsUpdate function
      val projectLinkChanges = Seq(
        ProjectRoadLinkChange(Sequences.nextProjectLinkId, newRoadwayId1,llId,     llId,         0, 0, 19510, 1,  0,  0, 0,  5, RoadAddressChangeType.New,      reversed=false, newRoadwayNumber1, newRoadwayNumber1),
        ProjectRoadLinkChange(Sequences.nextProjectLinkId, newRoadwayId2,llId + 1, llId + 1, 19510, 1, 19510, 1,  0, 10, 5, 15, RoadAddressChangeType.Transfer, reversed=false, originalRoadwayNumber1, newRoadwayNumber2),
        ProjectRoadLinkChange(Sequences.nextProjectLinkId, newRoadwayId3,llId + 2, llId + 2, 19510, 1, 19527, 1, 10, 20, 0, 10, RoadAddressChangeType.Transfer, reversed=false, originalRoadwayNumber1, newRoadwayNumber3)
      )

      // create roadway changes that will be handed to the handleRoadwayPointsUpdate function
      val roadwayChanges = List(
        ProjectRoadwayChange(
          projectId, Some(projectName), ely, user, DateTime.now(),
          RoadwayChangeInfo(
            RoadAddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, None, Some(AdministrativeClass.State), Some(Discontinuity.Continuous), Some(14L)),
            RoadwayChangeSection(Some(19510), Some(0), Some(1), Some(1), Some(0), Some(5), Some(AdministrativeClass.State), Some(Discontinuity.Continuous), Some(14)),
            Discontinuity.Continuous, AdministrativeClass.State, reversed=false, 4481L, 14),
          date),
        ProjectRoadwayChange(
          projectId, Some(projectName), ely, user, DateTime.now(),
          RoadwayChangeInfo(
            RoadAddressChangeType.Transfer,
            RoadwayChangeSection(Some(19510), Some(0), Some(1), Some(1), Some(0), Some(10), Some(AdministrativeClass.State), Some(Discontinuity.Continuous), Some(14)),
            RoadwayChangeSection(Some(19510), Some(0), Some(1), Some(1), Some(5), Some(15), Some(AdministrativeClass.State), Some(Discontinuity.EndOfRoad), Some(14)),
            Discontinuity.EndOfRoad, AdministrativeClass.State, reversed=false, 4481L, 14),
          date),
        ProjectRoadwayChange(
          projectId, Some(projectName), ely, user, DateTime.now(),
          RoadwayChangeInfo(
            RoadAddressChangeType.Transfer,
            RoadwayChangeSection(Some(19510), Some(0), Some(1), Some(1), Some(10), Some(20), Some(AdministrativeClass.State), Some(Discontinuity.EndOfRoad), Some(14)),
            RoadwayChangeSection(Some(19527), Some(0), Some(1), Some(1), Some(0), Some(10), Some(AdministrativeClass.State), Some(Discontinuity.EndOfRoad), Some(14)),
            Discontinuity.EndOfRoad, AdministrativeClass.State, reversed=false, 4481L, 14),
          date)
      )

      when(mockRoadwayDAO.fetchAllBySectionAndTracks(19510, 1, Set(Track.Combined))).thenReturn(newRoadwaysFor19510)
      when(mockRoadwayDAO.fetchAllBySectionAndTracks(19527, 1, Set(Track.Combined))).thenReturn(newRoadwaysFor19527)

      // this is the function that's being tested
      roadAddressService.handleRoadwayPointsUpdate(roadwayChanges, projectLinkChanges, "user")

      val rwPointsOnNewRoadwayNumber2 = roadwayPointDAO.fetchByRoadwayNumber(newRoadwayNumber2)
      val rwPointsOnNewRoadwayNumber3 = roadwayPointDAO.fetchByRoadwayNumber(newRoadwayNumber3)

      rwPointsOnNewRoadwayNumber2.size should be (2)
      val oldDualRwp = rwPointsOnNewRoadwayNumber2.find(rwp => rwp.id == rwpIdDual)
      val oldFirstRwp = rwPointsOnNewRoadwayNumber2.find(rwp => rwp.id == rwpId1)
      // check that the addrM's are correct
      oldDualRwp.get.addrMValue should be (15)
      oldFirstRwp.get.addrMValue should be (5)


      rwPointsOnNewRoadwayNumber3.size should be (2)
      val (oldLastRwp, newRwp) = rwPointsOnNewRoadwayNumber3.partition(rwp => rwp.id == rwpId3)
      // check that the addrM's are correct
      newRwp.head.addrMValue should be (0)
      oldLastRwp.head.addrMValue should be (10)

    }
  }

  test("Test handleRoadwayPoints When dual roadway point is no longer dual roadway point then addrM's and roadway number in that roadway point should change correctly and new roadway point should be created UNCHANGED + TRANSFER") {
    runWithRollback {
      /*

               ^    BEFORE PROJECT              AFTER PROJECT     ^
               |                                                  |
               |R2                                                | R2
               |                                                  |
               ^                                                  |
               | R1                                               |
    <---R3-----O*------R1-----                         <---R3-----O<-----R1-----

        * Note
          * 0*: Dual roadway point and Node
          * 0: Node
          * R1: Road 1 reserved to project (road number 19510)
          * R2: Road 2 reserved to project (road number 19527)
          * R3: Road 3 outside of project
       */

      val projectId = Sequences.nextViiteProjectId
      val projectName = "dualRoadwayPoint"
      val ely = 14
      val user = "silari"
      val date = DateTime.parse("2022-05-09")
      val originalRoadwayNumber0 = Sequences.nextRoadwayNumber
      val originalRoadwayNumber1 = Sequences.nextRoadwayNumber
      val originalRoadwayNumber2 = Sequences.nextRoadwayNumber
      val originalRoadwayId0 = Sequences.nextRoadwayId
      val originalRoadwayId1 = Sequences.nextRoadwayId
      val originalRoadwayId2 = Sequences.nextRoadwayId
      val newRoadwayNumber1 = Sequences.nextRoadwayNumber
      val newRoadwayNumber2 = Sequences.nextRoadwayNumber
      val llId = Sequences.nextLinearLocationId

      // create roadway points (before project situation)
      roadwayPointDAO.create(originalRoadwayNumber0, 0, "rw1originalStart")
      roadwayPointDAO.create(originalRoadwayNumber1, 10, "dualRWP") // THIS IS THE DUAL ROADWAY POINT
      roadwayPointDAO.create(originalRoadwayNumber1, 13, "rw1originalLast")
      roadwayPointDAO.create(originalRoadwayNumber2, 0, "rw2originalStart")
      roadwayPointDAO.create(originalRoadwayNumber2, 10, "rw2originalEnd")

      // create new roadways (after projects changes situation)
      val newRoadwaysFor19510 = Seq(
        Roadway(Sequences.nextRoadwayId, originalRoadwayNumber0, 19510, 1, AdministrativeClass.State, Track.Combined, Discontinuity.Continuous, 0, 5, reversed=false, DateTime.now(), None, "silari", Some("testRoadName"), 14, TerminationCode.NoTermination, DateTime.now(), None),
        Roadway(Sequences.nextRoadwayId, newRoadwayNumber1,      19510, 1, AdministrativeClass.State, Track.Combined, Discontinuity.EndOfRoad, 5, 10, reversed=false, DateTime.now(), None, "silari", Some("testRoadName"), 14, TerminationCode.NoTermination, DateTime.now(), None)
      )

      val newRoadwaysFor19527 = Seq(
        Roadway(Sequences.nextRoadwayId, newRoadwayNumber2,      19527, 1, AdministrativeClass.State, Track.Combined, Discontinuity.Continuous, 0, 3, reversed=false, DateTime.now(), None, "silari", Some("testRoadName"), 14, TerminationCode.NoTermination, DateTime.now(), None),
        Roadway(Sequences.nextRoadwayId, originalRoadwayNumber2, 19527, 1, AdministrativeClass.State, Track.Combined, Discontinuity.EndOfRoad, 3, 13, reversed=false, DateTime.now(), None, "silari", Some("testRoadName2"), 14, TerminationCode.NoTermination, DateTime.now(), None)
      )

      val newRoadways = newRoadwaysFor19510.union(newRoadwaysFor19527)
      roadwayDAO.create(newRoadways)

      // create project link changes that will be handed to the handleRoadwayPointsUpdate function
      val projectLinkChanges = Seq(
        ProjectRoadLinkChange(Sequences.nextProjectLinkId, originalRoadwayId0,llId,     llId,     19510, 1, 19510, 1,  0,  5, 0,  5, RoadAddressChangeType.Unchanged, reversed=false, originalRoadwayNumber0, originalRoadwayNumber0),
        ProjectRoadLinkChange(Sequences.nextProjectLinkId, originalRoadwayId1,llId + 1, llId + 1, 19510, 1, 19510, 1,  5, 10, 5, 10, RoadAddressChangeType.Unchanged, reversed=false, originalRoadwayNumber1, newRoadwayNumber1),
        ProjectRoadLinkChange(Sequences.nextProjectLinkId, originalRoadwayId1,llId + 2, llId + 2, 19510, 1, 19527, 1, 10, 13, 0,  3, RoadAddressChangeType.Transfer,  reversed=false, originalRoadwayNumber1, newRoadwayNumber2),
        ProjectRoadLinkChange(Sequences.nextProjectLinkId, originalRoadwayId2,llId + 3, llId + 3, 19510, 1, 19510, 1,  0, 10, 3, 13, RoadAddressChangeType.Transfer,  reversed=false, originalRoadwayNumber2, originalRoadwayNumber2)
      )

      // create roadway changes that will be handed to the handleRoadwayPointsUpdate function
      val roadwayChanges = List(
        ProjectRoadwayChange(
          projectId, Some(projectName), ely, user, DateTime.now(),
          RoadwayChangeInfo(
            RoadAddressChangeType.Unchanged,
            RoadwayChangeSection(Some(19510), Some(0), Some(1), Some(1), Some(0), Some(5), Some(AdministrativeClass.State), Some(Discontinuity.Continuous), Some(14)),
            RoadwayChangeSection(Some(19510), Some(0), Some(1), Some(1), Some(0), Some(5), Some(AdministrativeClass.State), Some(Discontinuity.Continuous), Some(14)),
            Discontinuity.EndOfRoad, AdministrativeClass.State, reversed=false, 4481L, 14),
          date),
        ProjectRoadwayChange(
          projectId, Some(projectName), ely, user, DateTime.now(),
          RoadwayChangeInfo(
            RoadAddressChangeType.Unchanged,
            RoadwayChangeSection(Some(19510), Some(0), Some(1), Some(1), Some(5), Some(10), Some(AdministrativeClass.State), Some(Discontinuity.Continuous), Some(14)),
            RoadwayChangeSection(Some(19510), Some(0), Some(1), Some(1), Some(5), Some(10), Some(AdministrativeClass.State), Some(Discontinuity.EndOfRoad), Some(14)),
            Discontinuity.EndOfRoad, AdministrativeClass.State, reversed=false, 4481L, 14),
          date),
        ProjectRoadwayChange(
          projectId, Some(projectName), ely, user, DateTime.now(),
          RoadwayChangeInfo(
            RoadAddressChangeType.Transfer,
            RoadwayChangeSection(Some(19510), Some(0), Some(1), Some(1), Some(10), Some(13), Some(AdministrativeClass.State), Some(Discontinuity.EndOfRoad), Some(14)),
            RoadwayChangeSection(Some(19527), Some(0), Some(1), Some(1), Some(0), Some(3), Some(AdministrativeClass.State), Some(Discontinuity.Continuous), Some(14)),
            Discontinuity.Continuous, AdministrativeClass.State, reversed=false, 4481L, 14),
          date),
        ProjectRoadwayChange(
          projectId, Some(projectName), ely, user, DateTime.now(),
          RoadwayChangeInfo(
            RoadAddressChangeType.Transfer,
            RoadwayChangeSection(Some(19527), Some(0), Some(1), Some(1), Some(0), Some(10), Some(AdministrativeClass.State), Some(Discontinuity.EndOfRoad), Some(14)),
            RoadwayChangeSection(Some(19527), Some(0), Some(1), Some(1), Some(3), Some(13), Some(AdministrativeClass.State), Some(Discontinuity.EndOfRoad), Some(14)),
            Discontinuity.EndOfRoad, AdministrativeClass.State, reversed=false, 4481L, 14),
          date)
      )

      when(mockRoadwayDAO.fetchAllBySectionAndTracks(19510, 1, Set(Track.Combined))).thenReturn(newRoadwaysFor19510)
      when(mockRoadwayDAO.fetchAllBySectionAndTracks(19527, 1, Set(Track.Combined))).thenReturn(newRoadwaysFor19527)

      // this is the function that's being tested
      roadAddressService.handleRoadwayPointsUpdate(roadwayChanges, projectLinkChanges, "user")

      val dualRwp = roadwayPointDAO.fetchByRoadwayNumber(newRoadwayNumber1)
      dualRwp.size should be (1)
      dualRwp.head.addrMValue should be (10)

      val rwPointsOnNewRoadwayNumber2 = roadwayPointDAO.fetchByRoadwayNumber(newRoadwayNumber2)
      rwPointsOnNewRoadwayNumber2.size should be (2)
      rwPointsOnNewRoadwayNumber2.head.addrMValue should be (0)
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
      val link1 = dummyProjectLink(99, 1, Track.Combined, Discontinuity.EndOfRoad, 0,  5, Some(DateTime.now()), None, 12345.toString, 0,  5, SideCode.TowardsDigitizing, RoadAddressChangeType.Unchanged, projectId, AdministrativeClass.State, geom1, roadwayNumber2)
      val link2 = dummyProjectLink(99, 2, Track.Combined, Discontinuity.EndOfRoad, 0, 15, Some(DateTime.now()), None, 12346.toString, 0, 15, SideCode.TowardsDigitizing, RoadAddressChangeType.Transfer,  projectId, AdministrativeClass.State, geom2, roadwayNumber3)

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
      when(mockRoadLinkService.getRoadLinksByLinkIds(any[Set[String]])).thenReturn(pls.map(toRoadLink))

      projectDAO.create(rap)

      val ra1 = RoadAddress(0, lc1WithId.id, rw1WithId.roadNumber, rw1WithId.roadPartNumber, rw1WithId.administrativeClass, rw1WithId.track, rw1WithId.discontinuity, rw1WithId.startAddrMValue, rw1WithId.endAddrMValue, Some(rw1WithId.startDate), rw1WithId.endDate, Some(rw1WithId.createdBy), lc1WithId.linkId, lc1WithId.startMValue, lc1WithId.endMValue, lc1WithId.sideCode, 1000000, (None, None), lc1WithId.geometry, lc1WithId.linkGeomSource, rw1WithId.ely, NoTermination, rw1WithId.roadwayNumber, None, None, None)

      val ra2 = RoadAddress(0, lc2WithId.id, rw2WithId.roadNumber, rw2WithId.roadPartNumber, rw2WithId.administrativeClass, rw2WithId.track, rw2WithId.discontinuity, rw2WithId.startAddrMValue, rw2WithId.endAddrMValue, Some(rw2WithId.startDate), rw2.endDate, Some(rw2.createdBy), lc2WithId.linkId, lc2WithId.startMValue, lc2WithId.endMValue, lc2WithId.sideCode, 1000000, (None, None), lc2WithId.geometry, lc2WithId.linkGeomSource, rw2WithId.ely, NoTermination, rw2WithId.roadwayNumber, None, None, None)

      when(mockRoadAddressService.getRoadAddressWithRoadAndPart(any[Long], any[Long], any[Boolean], any[Boolean], any[Boolean])).thenReturn(Seq(ra1, ra2))
      when(mockLinearLocationDAO.fetchByRoadways(Set(rw1.roadwayNumber))).thenReturn(Seq(lc1))
      when(mockLinearLocationDAO.fetchByRoadways(Set(rw2.roadwayNumber))).thenReturn(Seq(lc2))
      when(mockRoadwayDAO.fetchAllByRoadAndPart(99, 2)).thenReturn(Seq(rw2WithId))

      projectReservedPartDAO.reserveRoadPart(projectId, 99, 1, "u")
      projectReservedPartDAO.reserveRoadPart(projectId, 99, 2, "u")
      projectLinkDAO.create(pls.map(_.copy(id = NewIdValue)))

      roadwayPointDAO.create(roadwayNumber1, 0, link1.createdBy.getOrElse("test"))
      roadwayPointDAO.create(roadwayNumber1, 5, link2.createdBy.getOrElse("test"))
      roadwayPointDAO.create(roadwayNumber1, 20, link2.createdBy.getOrElse("test"))

      val projectLinks = projectLinkDAO.fetchByProjectRoad(99, projectId).sortBy(_.startAddrMValue)

      projectService.updateProjectLinks(projectId, Set(), Seq(projectLinks.head.linkId), RoadAddressChangeType.Unchanged, "-", 99, 1, 0, Option.empty[Int])
      projectService.updateProjectLinks(projectId, Set(), Seq(projectLinks.last.linkId), RoadAddressChangeType.Transfer,  "-", 99, 2, 0, Option.empty[Int])

      val updatedProjectLinks_ = projectLinkDAO.fetchProjectLinks(projectId).toList
      val updatedProjectLinks2 = ProjectSectionCalculator.assignMValues(updatedProjectLinks_)

      val afterUpdateProjectLinks = updatedProjectLinks2.sortBy(pl => (pl.startAddrMValue, pl.roadPartNumber))
      val beforeDualPoint = afterUpdateProjectLinks.head.copy(roadwayNumber = roadwayNumber2)
      val afterDualPoint = afterUpdateProjectLinks.last.copy(roadwayNumber = roadwayNumber3)
      val mappedRoadwayChanges = projectLinkDAO.fetchProjectLinksChange(projectId)

      val reservedParts = Seq(ProjectReservedPart(0, 99, 2, Some(20), Some(Discontinuity.Continuous), Some(8L), None, None, None, Some(12345L.toString)))

      val project = projectDAO.fetchById(projectId).get
      roadwayChangesDAO.insertDeltaToRoadChangeTable(projectId, Some(project.copy(reservedParts = reservedParts)))

      val roadwayChanges = roadwayChangesDAO.fetchRoadwayChanges(Set(projectId))

      when(mockRoadwayDAO.fetchAllBySectionAndTracks(any[Long], any[Long], any[Set[Track]])).thenReturn(Seq(rw1WithId, rw2WithId))
      val t1 = roadwayChanges.last.changeInfo.source.copy(startAddressM = Some(5), endAddressM = Some(20), startRoadPartNumber = Some(1), endRoadPartNumber = Some(1))
      val t2 = roadwayChanges.last.changeInfo.copy(source = t1)
      val updatedRoadwayChanges = roadwayChanges.last.copy(changeInfo = t2)

      roadAddressService.handleRoadwayPointsUpdate(List(roadwayChanges.head, updatedRoadwayChanges), mappedRoadwayChanges.map { rwc =>
        rwc.originalRoadPartNumber match {
          case 1 => rwc.copy(originalRoadwayNumber = roadwayNumber1)
          case 2 => rwc.copy(originalRoadPartNumber = 1, originalStartAddr = 5, originalEndAddr = 20, originalRoadwayNumber = roadwayNumber1)
          case _ => rwc
        }
      }, "user")

      val roadwayPointsForExpiredRoadwayNumber = roadwayPointDAO.fetchByRoadwayNumber(roadwayNumber1).sortBy(_.addrMValue)
      roadwayPointsForExpiredRoadwayNumber.size should be (0)

      val roadwayPointsBeforeDual = roadwayPointDAO.fetchByRoadwayNumber(beforeDualPoint.roadwayNumber).sortBy(_.addrMValue)
      val roadwayPointsAfterDual = roadwayPointDAO.fetchByRoadwayNumber(afterDualPoint.roadwayNumber).sortBy(_.addrMValue)

      roadwayPointsBeforeDual.size should be (2)
      roadwayPointsBeforeDual.head.addrMValue should be (beforeDualPoint.startAddrMValue)
      roadwayPointsBeforeDual.last.addrMValue should be (beforeDualPoint.endAddrMValue)

      roadwayPointsAfterDual.size should be (2)
      roadwayPointsAfterDual.head.addrMValue should be (afterDualPoint.startAddrMValue)
      roadwayPointsAfterDual.last.addrMValue should be (afterDualPoint.endAddrMValue)
    }
  }
}

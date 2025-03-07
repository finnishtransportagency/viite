package fi.liikennevirasto.viite

import java.sql.Date
import fi.liikennevirasto.digiroad2.DigiroadEventBus
import fi.liikennevirasto.digiroad2.service.RoadLinkService
import fi.liikennevirasto.viite.dao._
import fi.liikennevirasto.viite.process.RoadwayAddressMapper
import fi.liikennevirasto.viite.util.CalibrationPointsUtils
import fi.vaylavirasto.viite.dao.{BaseDAO, Sequences}
import fi.vaylavirasto.viite.geometry.{BoundingRectangle, Point}
import fi.vaylavirasto.viite.model.CalibrationPointType.{NoCP, RoadAddressCP}
import fi.vaylavirasto.viite.model.{AddrMRange, AdministrativeClass, BeforeAfter, CalibrationPointLocation, Discontinuity, LinkGeomSource, NodePointType, NodeType, RoadAddressChangeType, RoadPart, SideCode, Track}
import fi.vaylavirasto.viite.postgis.PostGISDatabaseScalikeJDBC.runWithRollback
import org.joda.time.DateTime
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.when
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.scalatest.BeforeAndAfter
import org.scalatestplus.mockito.MockitoSugar
import scalikejdbc._


import scala.concurrent.Future
import scala.util.{Left, Right}

class NodesAndJunctionsServiceSpec extends AnyFunSuite with Matchers with BeforeAndAfter with BaseDAO {

  private val roadNumber1 = 990
  private val roadwayNumber1 = 1000000000L
  private val roadwayNumber2 = 1000000002L
  private val roadPartNumber1 = 1
  private val roadNumberLimits = Seq((RoadClass.forJunctions.start, RoadClass.forJunctions.end))
  private val mockLinearLocationDAO = MockitoSugar.mock[LinearLocationDAO]
  private val mockRoadwayDAO = MockitoSugar.mock[RoadwayDAO]
  val mockRoadLinkService: RoadLinkService = MockitoSugar.mock[RoadLinkService]
  val mockRoadwayAddressMapper: RoadwayAddressMapper = MockitoSugar.mock[RoadwayAddressMapper]
  val mockEventBus: DigiroadEventBus = MockitoSugar.mock[DigiroadEventBus]
  val roadwayDAO = new RoadwayDAO
  val roadwayPointDAO = new RoadwayPointDAO
  val nodeDAO = new NodeDAO
  val nodePointDAO = new NodePointDAO
  val junctionDAO = new JunctionDAO
  val junctionPointDAO = new JunctionPointDAO
  val linearLocationDAO = new LinearLocationDAO
  val roadwayChangesDAO = new RoadwayChangesDAO
  val projectDAO = new ProjectDAO
  val projectLinkDAO = new ProjectLinkDAO
  val projectReservedPartDAO = new ProjectReservedPartDAO
  val roadwayAddressMapper = new RoadwayAddressMapper(roadwayDAO, linearLocationDAO)
  val roadAddressService: RoadAddressService = new RoadAddressService(mockRoadLinkService, new RoadwayDAO, new LinearLocationDAO, new RoadNetworkDAO, roadwayPointDAO, nodePointDAO, junctionPointDAO, mockRoadwayAddressMapper, mockEventBus, frozenKGV = false) {

    override def runWithReadOnlySession[T](f: => T): T = f
    override def runWithTransaction[T](f: => T): T = f
  }

  val nodesAndJunctionsService: NodesAndJunctionsService =
    new NodesAndJunctionsService(
      mockRoadwayDAO,
      roadwayPointDAO,
      mockLinearLocationDAO,
      nodeDAO,
      nodePointDAO,
      junctionDAO,
      junctionPointDAO,
      roadwayChangesDAO,
      projectReservedPartDAO
    ) {
      override def runWithReadOnlySession[T](f: => T): T = f
      override def runWithTransaction[T](f: => T): T = f
    }

  val projectService: ProjectService =
    new ProjectService(
      roadAddressService,
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
      mockEventBus
    ) {
      override def runWithReadOnlySession[T](f: => T): T = f
      override def runWithTransaction[T](f: => T): T = f
    }

  private val testRoadway1 = Roadway(NewIdValue, roadwayNumber1, RoadPart(roadNumber1, roadPartNumber1), AdministrativeClass.State, Track.Combined, Discontinuity.Continuous, AddrMRange(0, 100), reversed = false, DateTime.parse("2000-01-01"), None, "test", Some("TEST ROAD 1"), 1, TerminationCode.NoTermination)

  private val testNode1 = Node(NewIdValue, NewIdValue, Point(100, 100), Some("Test node"), NodeType.NormalIntersection,
    DateTime.parse("2019-01-01"), None, DateTime.parse("2019-01-01"), None, "Test", None, registrationDate = new DateTime())

  private val testRoadwayPoint1 = RoadwayPoint(NewIdValue, roadwayNumber1, 0, "Test", None, None, None)
  private val testRoadwayPoint2 = RoadwayPoint(NewIdValue, roadwayNumber2, 0, "Test", None, None, None)

  private val testNodePoint1 = NodePoint(NewIdValue, BeforeAfter.Before, -1, None, NodePointType.UnknownNodePointType, Some(testNode1.startDate), testNode1.endDate,
    DateTime.parse("2019-01-01"), None, "Test", None, 0, 0, RoadPart(0, 0), Track.Combined, 0)

  private val testJunction1 = Junction(NewIdValue, None, None, DateTime.parse("2019-01-01"), None,
    DateTime.parse("2019-01-01"), None, "Test", None)

  private val testJunctionPoint1 = JunctionPoint(NewIdValue, BeforeAfter.Before, -1, -1, Some(testJunction1.startDate), testJunction1.endDate,
    DateTime.parse("2019-01-01"), None, "Test", None, -1, 10, RoadPart(0, 0), Track.Combined, Discontinuity.Continuous)

  private val testLinearLocation1 = LinearLocation(NewIdValue, 1, 1000L.toString, 0.0, 2.8, SideCode.TowardsDigitizing, 10000000000L, (CalibrationPointReference.None, CalibrationPointReference.None), Seq(Point(99.0, 99.0), Point(101.0, 101.0)), LinkGeomSource.NormalLinkInterface, -1)

  def dummyProjectLink(roadPart: RoadPart, trackCode: Track, discontinuityType: Discontinuity, addrMRange: AddrMRange, originalAddrMRange: AddrMRange = AddrMRange(0,0), startDate: Option[DateTime], endDate: Option[DateTime] = None, linkId: String = "0", startMValue: Double = 0, endMValue: Double = 0, sideCode: SideCode = SideCode.Unknown, status: RoadAddressChangeType, projectId: Long = 0, administrativeClass: AdministrativeClass = AdministrativeClass.State, geometry: Seq[Point] = Seq(), roadwayNumber: Long): ProjectLink = {
    ProjectLink(0L, roadPart, trackCode, discontinuityType, addrMRange, originalAddrMRange, startDate, endDate, Some("user"), linkId, startMValue, endMValue, sideCode, (NoCP, NoCP), (NoCP, NoCP), geometry, projectId, status, administrativeClass, LinkGeomSource.NormalLinkInterface, geometryLength = 0.0, roadwayId = 0, linearLocationId = 0, ely = 8, reversed = false, None, linkGeometryTimeStamp = 0, roadwayNumber)
  }

  def buildTestDataForProject(project: Option[Project], rws: Option[Seq[Roadway]], lil: Option[Seq[LinearLocation]],
                              pls: Option[Seq[ProjectLink]]): Unit = {
    if (rws.nonEmpty)
      roadwayDAO.create(rws.get)
    if (lil.nonEmpty)
      linearLocationDAO.create(lil.get, "user")
    if (project.nonEmpty)
      projectDAO.create(project.get)
    if (pls.nonEmpty) {
      if (project.nonEmpty) {
        val roadParts = pls.get.groupBy(pl => (pl.roadPart)).keys
        roadParts.foreach(rp => projectReservedPartDAO.reserveRoadPart(project.get.id, rp, "user"))
        projectLinkDAO.create(pls.get.map(_.copy(projectId = project.get.id)))
      } else {
        projectLinkDAO.create(pls.get)
      }
    }
  }

  def toRoadwayAndLinearLocation(p: ProjectLink): (LinearLocation, Roadway) = {
    val startDate = p.startDate.getOrElse(DateTime.now()).minusDays(1)

    (LinearLocation(p.linearLocationId, 1, p.linkId, p.startMValue, p.endMValue, p.sideCode, p.linkGeometryTimeStamp,
      (CalibrationPointsUtils.toCalibrationPointReference(p.startCalibrationPoint),
        CalibrationPointsUtils.toCalibrationPointReference(p.endCalibrationPoint)),
      p.geometry, p.linkGeomSource, p.roadwayNumber, Some(startDate), p.endDate),
      Roadway(p.roadwayId, p.roadwayNumber, p.roadPart, p.administrativeClass, p.track, p.discontinuity, p.addrMRange, p.reversed, startDate, p.endDate,
        p.createdBy.getOrElse("-"), p.roadName, p.ely, TerminationCode.NoTermination, DateTime.now(), None))
  }

  before {
    // Forward all calls to mock.fetchAllBySectionAndTracks to actual implementation
    when(mockRoadwayDAO.fetchAllBySectionAndTracks(any(), any()))
      .thenAnswer(
        new Answer[Seq[Roadway]] {
          override def answer(i: InvocationOnMock): Seq[Roadway] = {
            roadwayDAO.fetchAllBySectionAndTracks(i.getArgument(0), i.getArgument(1))
          }
        })
  }

  test("Test nodesAndJunctionsService.getNodesByRoadAttributes When there are less than 50 nodes in the given road Then should return the list of those nodes") {
    runWithRollback {
      roadwayDAO.create(Seq(testRoadway1))
      val nodeNumber = nodeDAO.create(Seq(testNode1)).head
      val roadwayPointId = Sequences.nextRoadwayPointId
      roadwayPointDAO.create(testRoadwayPoint1.copy(id = roadwayPointId))
      nodePointDAO.create(Seq(testNodePoint1.copy(nodeNumber = Some(nodeNumber), roadwayPointId = roadwayPointId)))

      val nodesAndRoadAttributes = nodesAndJunctionsService.getNodesByRoadAttributes(roadNumber1, None, None)
      nodesAndRoadAttributes.isRight should be(true)
      nodesAndRoadAttributes match {
        case Right(nodes) =>
          nodes.size should be(1)
          val node = nodes.head
          node._1.name should be(Some("Test node"))
          node._1.coordinates should be(Point(100, 100))
          node._2.roadPart should be(RoadPart(roadNumber1, roadPartNumber1))
        case _ => fail("Should not get here")
      }
    }
  }

  test("Test nodesAndJunctionsService.getNodesByRoadAttributes When there are more than 50 nodes in the given road for a single road part Then should return the list of those nodes") {
    runWithRollback {
      roadwayDAO.create(Seq(testRoadway1))
      for (index <- 0 to MaxAllowedNodes) {
        val nodeId = Sequences.nextNodeId
        val nodeNumber = nodeDAO.create(Seq(testNode1.copy(id = nodeId))).head
        val roadwayPointId = Sequences.nextRoadwayPointId
        roadwayPointDAO.create(testRoadwayPoint1.copy(id = roadwayPointId, addrMValue = index))
        nodePointDAO.create(Seq(testNodePoint1.copy(nodeNumber = Some(nodeNumber), roadwayPointId = roadwayPointId)))
      }

      val nodesAndRoadAttributes = nodesAndJunctionsService.getNodesByRoadAttributes(roadNumber1, Some(roadPartNumber1), None)
      nodesAndRoadAttributes.isRight should be(true)
      nodesAndRoadAttributes match {
        case Right(nodes) => nodes.size should be(51)
        case _ => fail()
      }
    }
  }

  test("Test nodesAndJunctionsService.getNodesByRoadAttributes When there are more than 50 nodes Then should return error message") {
    runWithRollback {
      roadwayDAO.create(Seq(testRoadway1))
      for (index <- 0 to MaxAllowedNodes) {
        val nodeId = Sequences.nextNodeId
        val nodeNumber = nodeDAO.create(Seq(testNode1.copy(id = nodeId))).head
        val roadwayPointId = Sequences.nextRoadwayPointId
        roadwayPointDAO.create(testRoadwayPoint1.copy(id = roadwayPointId, addrMValue = index))
        nodePointDAO.create(Seq(testNodePoint1.copy(nodeNumber = Some(nodeNumber), roadwayPointId = roadwayPointId)))
      }

      val nodesAndRoadAttributes = nodesAndJunctionsService.getNodesByRoadAttributes(roadNumber1, None, None)
      nodesAndRoadAttributes.isLeft should be(true)
      nodesAndRoadAttributes match {
        case Left(errorMessage) => errorMessage should be(ReturnedTooManyNodesErrorMessage)
        case _ => fail()
      }
    }
  }

  test("Test getTemplatesByBoundingBox When matching templates Then return them") {
    runWithRollback {
      val roadwayNumber = Sequences.nextRoadwayNumber
      roadwayDAO.create(Seq(testRoadway1.copy(roadwayNumber = roadwayNumber)))
      linearLocationDAO.create(Seq(testLinearLocation1.copy(roadwayNumber = roadwayNumber)))
      val roadwayPointId = roadwayPointDAO.create(testRoadwayPoint1.copy(roadwayNumber = roadwayNumber))
      nodePointDAO.create(Seq(testNodePoint1.copy(roadwayPointId = roadwayPointId)))
      val junctionId = junctionDAO.create(Seq(testJunction1)).head
      junctionPointDAO.create(Seq(testJunctionPoint1.copy(junctionId = junctionId, roadwayPointId = roadwayPointId)))

      val templates = nodesAndJunctionsService.getTemplatesByBoundingBox(BoundingRectangle(Point(98, 98), Point(102, 102)))
      templates._1.size should be(1)
      templates._2.size should be(1)
      templates._2.head._2.size should be(1)
      templates._1.head.roadwayNumber should be(roadwayNumber)
      templates._2.head._1.id should be(junctionId)
      templates._2.head._1.nodeNumber should be(None)
      templates._2.head._2.head.junctionId should be(junctionId)
    }
  }

  test("Test nodesAndJunctionsService.handleJunctionTemplates roadsToHead case When creating projectlinks Then junction template and junctions points should be handled/created properly") {
    runWithRollback {
      /*
     v0---R---|
     |0--PL-->|--PL-->|


     PL: Project link
     R: Existing road
       */
      val geom1 = Seq(Point(10.0, 0.0), Point(0.0, 0.0))
      val geom2 = Seq(Point(0.0, 0.0), Point(11.0, 0.0))
      val geom3 = Seq(Point(11.0, 0.0), Point(20.0, 0.0))
      val road999 = 999L
      val road1000 = 1000L
      val part = 1L
      val roadwayNumber = Sequences.nextRoadwayNumber
      val rwId = Sequences.nextRoadwayId
      val roadway  = Roadway(rwId,     roadwayNumber,     RoadPart(road999,  part), AdministrativeClass.State, Track.Combined, Discontinuity.Discontinuous, AddrMRange(0L, 10L), reversed = false, DateTime.now, None, "user", None, 8L, TerminationCode.NoTermination, DateTime.now, None)
      val roadway2 = Roadway(rwId + 1, roadwayNumber + 1, RoadPart(road1000, part), AdministrativeClass.State, Track.Combined, Discontinuity.Continuous,    AddrMRange(0L, 20L), reversed = false, DateTime.now, None, "user", None, 8L, TerminationCode.NoTermination, DateTime.now, None)
      val linearLocationId = Sequences.nextLinearLocationId
      val linearLocation = LinearLocation(linearLocationId, 1, 12345.toString, 0L, 10L, SideCode.TowardsDigitizing, 0L, calibrationPoints = (CalibrationPointReference(Some(0)), CalibrationPointReference(Some(10))), geom1, LinkGeomSource.NormalLinkInterface, roadwayNumber, None, None)
      val projectId = Sequences.nextViiteProjectId
      val plId1 = Sequences.nextProjectLinkId

      val project = Project(projectId, ProjectState.Incomplete, "f", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
        "", Seq(), Seq(), None, None)

      val link1 = dummyProjectLink(RoadPart(road1000, part), Track.Combined, Discontinuity.Continuous, AddrMRange( 0, 11), AddrMRange( 0, 11), Some(DateTime.now()), None, 12346.toString, 0, 10, SideCode.TowardsDigitizing, RoadAddressChangeType.New, projectId, AdministrativeClass.State, geom2, roadwayNumber + 1).copy(id = plId1 + 1, roadwayId = rwId + 1, linearLocationId = linearLocationId + 1)
      val link2 = dummyProjectLink(RoadPart(road1000, part), Track.Combined, Discontinuity.Continuous, AddrMRange(11, 20), AddrMRange(11, 20), Some(DateTime.now()), None, 12347.toString, 0, 10, SideCode.TowardsDigitizing, RoadAddressChangeType.New, projectId, AdministrativeClass.State, geom3, roadwayNumber + 1).copy(id = plId1 + 2, roadwayId = rwId + 1, linearLocationId = linearLocationId + 2)

      val linearLocations = Seq(
        LinearLocation(linearLocationId,     1, 12345.toString, 0.0, 10.0, SideCode.TowardsDigitizing, 10000000000L, (CalibrationPointReference(Some(0L)), CalibrationPointReference.None), Seq(Point( 0.0, 0.0), Point(10.0, 0.0)), LinkGeomSource.ComplementaryLinkInterface, roadwayNumber,     Some(DateTime.parse("2000-01-01")), None),
        LinearLocation(linearLocationId + 1, 1, 12346.toString, 0.0, 11.0, SideCode.TowardsDigitizing, 10000000000L, (CalibrationPointReference.None,      CalibrationPointReference.None), Seq(Point( 0.0, 0.0), Point(11.0, 0.0)), LinkGeomSource.ComplementaryLinkInterface, roadwayNumber + 1, Some(DateTime.parse("2000-01-01")), None),
        LinearLocation(linearLocationId + 2, 2, 12347.toString, 0.0,  9.0, SideCode.TowardsDigitizing, 10000000000L, (CalibrationPointReference.None,      CalibrationPointReference.None), Seq(Point(11.0, 0.0), Point(20.0, 0.0)), LinkGeomSource.ComplementaryLinkInterface, roadwayNumber + 1, Some(DateTime.parse("2000-01-01")), None)
      )
      val pls = Seq(link1, link2)
      roadwayDAO.create(Seq(roadway, roadway2))
      buildTestDataForProject(Some(project), None, Some(linearLocations), Some(pls))
      val projectChanges = List(
        ProjectRoadwayChange(0, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(RoadAddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, Some(AdministrativeClass.State), Some(Discontinuity.Continuous), Some(8L)),
            RoadwayChangeSection(Some(link1.roadPart.roadNumber), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(link1.roadPart.partNumber), endRoadPartNumber = Some(link1.roadPart.partNumber), addrMRange = Some(AddrMRange(link1.addrMRange.start,link2.addrMRange.end)), Some(AdministrativeClass.State), Some(Discontinuity.Continuous), Some(8L)),
            Discontinuity.Continuous, AdministrativeClass.State, reversed = false, 1, 8), DateTime.now)

      )

      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(any[BoundingRectangle], any[Seq[(Int, Int)]])).thenReturn(Seq(linearLocation))
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(any[Set[Long]], any[Boolean])).thenReturn(Seq(roadway))

      val mappedRoadwayNumbers = projectLinkDAO.fetchProjectLinksChange(projectId)
      nodesAndJunctionsService.handleJunctionAndJunctionPoints(projectChanges, pls, mappedRoadwayNumbers)

      val roadJunctionPoints = junctionPointDAO.fetchByRoadwayPoint(roadway.roadwayNumber, roadway.addrMRange.end, BeforeAfter.Before)
      val junction1 = junctionDAO.fetchByIds(Seq(roadJunctionPoints.head.junctionId))

      val link1JunctionPoints = junctionPointDAO.fetchByRoadwayPoint(link1.roadwayNumber, link1.addrMRange.start, BeforeAfter.After)
      val link2JunctionPoints = junctionPointDAO.fetchByRoadwayPoint(link2.roadwayNumber, link2.addrMRange.start, BeforeAfter.After)
      val junction2 = junctionDAO.fetchByIds(Seq(link1JunctionPoints.head.junctionId))

      junction1 should be(junction2)

      val roadwayPoint1 = roadwayPointDAO.fetch(roadway.roadwayNumber, roadway.addrMRange.end)
      val roadwayPoint2 = roadwayPointDAO.fetch(link1.roadwayNumber, link1.addrMRange.start)

      roadwayPoint1.head.addrMValue should be(roadway.addrMRange.end)
      roadwayPoint2.head.addrMValue should be(link1.addrMRange.start)

      roadJunctionPoints.isDefined should be(true)
      roadJunctionPoints.head.beforeAfter should be(BeforeAfter.Before)
      roadJunctionPoints.head.roadwayNumber should be(roadway.roadwayNumber)
      link1JunctionPoints.isDefined should be(true)
      link1JunctionPoints.head.beforeAfter should be(BeforeAfter.After)
      link1JunctionPoints.head.roadwayNumber should be(link1.roadwayNumber)
      link2JunctionPoints.isDefined should be(false)

    }
  }

  test("Test nodesAndJunctionsService.handleJunctionTemplates roadsToHead case When creating projectlinks Then junction template and junctions points should be handled/created properly and" +
    " When reverse, the junction points BeforeAfter should be reversed") {
    runWithRollback {
      /*
     |--R-->0|0--L-->
       */
      val geom1 = Seq(Point(0.0, 0.0), Point(10.0, 0.0))
      val geom2 = Seq(Point(10.0, 0.0), Point(20.0, 0.0))
      val road999 = 999L
      val road1000 = 1000L
      val part = 1L
      val roadwayNumber1 = Sequences.nextRoadwayNumber
      val roadwayNumber2 = Sequences.nextRoadwayNumber
      val rwId1 = Sequences.nextRoadwayId
      val rwId2 = Sequences.nextRoadwayId
      val roadway1 = Roadway(rwId1, roadwayNumber1, RoadPart(road999,  part), AdministrativeClass.State, Track.Combined, Discontinuity.Continuous, AddrMRange(0L, 10L), reversed = false, DateTime.now, None, "user", None, 8L, TerminationCode.NoTermination, DateTime.now, None)
      val roadway2 = Roadway(rwId2, roadwayNumber2, RoadPart(road1000, part), AdministrativeClass.State, Track.Combined, Discontinuity.Continuous, AddrMRange(0L, 10L), reversed = false, DateTime.now, None, "user", None, 8L, TerminationCode.NoTermination, DateTime.now, None)
      val linearLocationId1 = Sequences.nextLinearLocationId
      val linearLocationId2 = Sequences.nextLinearLocationId
      val linearLocation = LinearLocation(linearLocationId1, 1, 12345.toString, 0L, 10L, SideCode.TowardsDigitizing, 0L, calibrationPoints = (CalibrationPointReference(Some(0)), CalibrationPointReference(Some(10))), geom1, LinkGeomSource.NormalLinkInterface, roadwayNumber1, None, None)
      val projectId = Sequences.nextViiteProjectId
      val plId2 = Sequences.nextProjectLinkId

      val project = Project(projectId, ProjectState.Incomplete, "f", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
        "", Seq(), Seq(), None, None)

      val link1 = dummyProjectLink(RoadPart(road1000, part), Track.Combined, Discontinuity.EndOfRoad, AddrMRange(0, 10), AddrMRange(0, 10), Some(DateTime.now()), None, 12346.toString, 0, 10, SideCode.TowardsDigitizing, RoadAddressChangeType.Transfer, projectId, AdministrativeClass.State, geom2, roadwayNumber2).copy(id = plId2, roadwayId = rwId2, linearLocationId = linearLocationId2)


      val linearLocations = Seq(
        LinearLocation(linearLocationId1, 1, 1000L.toString, 0.0, 50.0, SideCode.TowardsDigitizing, 10000000000L, (CalibrationPointReference(Some(0L)), CalibrationPointReference.None), Seq(Point( 0.0, 0.0), Point(10.0, 0.0)), LinkGeomSource.ComplementaryLinkInterface, roadwayNumber1, Some(DateTime.parse("2000-01-01")), None),
        LinearLocation(linearLocationId2, 1, 2000L.toString, 0.0, 10.0, SideCode.TowardsDigitizing, 10000000000L, (CalibrationPointReference.None,      CalibrationPointReference.None), Seq(Point(10.0, 0.0), Point(20.0, 0.0)), LinkGeomSource.ComplementaryLinkInterface, roadwayNumber2, Some(DateTime.parse("2000-01-01")), None)
      )
      val pls = Seq(link1)
      val reversedPls = pls.map(_.copy(sideCode = SideCode.switch(link1.sideCode), reversed = true))
      roadwayDAO.create(Seq(roadway1, roadway2))
      buildTestDataForProject(Some(project), None, Some(linearLocations), Some(reversedPls))
      val projectChanges = List(
        //combined
        ProjectRoadwayChange(0, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(RoadAddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, Some(AdministrativeClass.State), Some(Discontinuity.Continuous), Some(8L)),
            RoadwayChangeSection(Some(link1.roadPart.roadNumber), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(link1.roadPart.partNumber), endRoadPartNumber = Some(link1.roadPart.partNumber), addrMRange = Some(link1.addrMRange), Some(AdministrativeClass.State), Some(Discontinuity.Continuous), Some(8L)),
            Discontinuity.Continuous, AdministrativeClass.State, reversed = false, 1, 8), DateTime.now)
      )

      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(any[BoundingRectangle], any[Seq[(Int, Int)]])).thenReturn(Seq(linearLocation))
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(any[Set[Long]], any[Boolean])).thenReturn(Seq(roadway1))

      val reversedProjectChanges = projectChanges.map(p => p.copy(changeInfo = p.changeInfo.copy(changeType = RoadAddressChangeType.Transfer, source = p.changeInfo.target, reversed = true)))

      val mappedRoadwayNumbers = projectLinkDAO.fetchProjectLinksChange(projectId)
      runUpdateToDb(sql"""INSERT INTO LINK (ID) VALUES (12345)""")
      runUpdateToDb(sql"""INSERT INTO LINK (ID) VALUES (12346)""")
      nodesAndJunctionsService.handleJunctionAndJunctionPoints(projectChanges, pls, mappedRoadwayNumbers)

      val roadJunctionPoints = junctionPointDAO.fetchByRoadwayPoint(roadway1.roadwayNumber, roadway1.addrMRange.end, BeforeAfter.Before)
      val junction1 = junctionDAO.fetchByIds(Seq(roadJunctionPoints.head.junctionId))

      val linkJunctionPoints = junctionPointDAO.fetchByRoadwayPoint(link1.roadwayNumber, link1.addrMRange.start, BeforeAfter.After)
      val junction2 = junctionDAO.fetchByIds(Seq(linkJunctionPoints.head.junctionId))

      junction1 should be(junction2)

      val roadwayPoint1 = roadwayPointDAO.fetch(roadway1.roadwayNumber, roadway1.addrMRange.end)
      val roadwayPoint2 = roadwayPointDAO.fetch(link1.roadwayNumber, link1.addrMRange.start)

      roadwayPoint1.head.addrMValue should be(roadway1.addrMRange.end)
      roadwayPoint2.head.addrMValue should be(link1.addrMRange.start)

      roadJunctionPoints.isDefined should be(true)
      roadJunctionPoints.head.beforeAfter should be(BeforeAfter.Before)
      roadJunctionPoints.head.roadwayNumber should be(roadway1.roadwayNumber)
      linkJunctionPoints.isDefined should be(true)
      linkJunctionPoints.head.beforeAfter should be(BeforeAfter.After)
      linkJunctionPoints.head.roadwayNumber should be(link1.roadwayNumber)

      val reversedRoadways = Seq(Roadway(NewIdValue, link1.roadwayNumber, link1.roadPart, AdministrativeClass.State, Track.Combined, Discontinuity.Continuous, link1.addrMRange, reversed = true, DateTime.parse("2000-01-01"), None, "test", Some("TEST ROAD 1"), 1, TerminationCode.NoTermination)
      )
      roadwayDAO.expireById(Seq(roadway1, roadway2).map(_.id).toSet)
      roadwayDAO.create(reversedRoadways)
      val mappedReservedRoadwayNumbers = projectLinkDAO.fetchProjectLinksChange(projectId)
      roadAddressService.handleRoadwayPointsUpdate(reversedProjectChanges, mappedReservedRoadwayNumbers)
      val roadwayPointsAfterUpdate = roadwayPointDAO.fetchByRoadwayNumber(link1.roadwayNumber)
      nodesAndJunctionsService.handleJunctionAndJunctionPoints(reversedProjectChanges, reversedPls, mappedReservedRoadwayNumbers)
      val reversedLinkJunctionPoints = junctionPointDAO.fetchByRoadwayPoint(link1.roadwayNumber, roadwayPointsAfterUpdate.head.addrMValue, BeforeAfter.Before)
      linkJunctionPoints.head.id should be(reversedLinkJunctionPoints.head.id)
      reversedLinkJunctionPoints.head.beforeAfter should be(BeforeAfter.switch(linkJunctionPoints.head.beforeAfter))
    }
  }

  test("Test nodesAndJunctionsService.handleJunctionTemplates roadsFromHead case When creating projectLinks Then junction template and junctions points should be handled/created properly and " +
    "When reverse, the junction points BeforeAfter should be reversed") {
    runWithRollback {
      /*
     <--R--0|0--L-->
       */
      val geom1 = Seq(Point(0.0, 0.0), Point(10.0, 0.0))
      val geom2 = Seq(Point(10.0, 0.0), Point(20.0, 0.0))
      val road999 = 999L
      val road1000 = 1000L
      val part = 1L
      val roadwayNumber1 = Sequences.nextRoadwayNumber
      val roadwayNumber2 = Sequences.nextRoadwayNumber
      val rwId1 = Sequences.nextRoadwayId
      val rwId2 = Sequences.nextRoadwayId
      val linearLocationId1 = Sequences.nextLinearLocationId
      val linearLocationId2 = Sequences.nextLinearLocationId
      val roadway1 = Roadway(rwId1, roadwayNumber1, RoadPart(road999,  part), AdministrativeClass.State, Track.Combined, Discontinuity.Continuous, AddrMRange(0L, 10L), reversed = false, DateTime.now, None, "user", None, 8L, TerminationCode.NoTermination, DateTime.now, None)
      val roadway2 = Roadway(rwId2, roadwayNumber2, RoadPart(road1000, part), AdministrativeClass.State, Track.Combined, Discontinuity.Continuous, AddrMRange(0L, 10L), reversed = false, DateTime.now, None, "user", None, 8L, TerminationCode.NoTermination, DateTime.now, None)
      val linearLocation = LinearLocation(linearLocationId1, 1, 12345.toString, 0L, 10L, SideCode.AgainstDigitizing, 0L, calibrationPoints = (CalibrationPointReference.None, CalibrationPointReference(Some(10))), geom1, LinkGeomSource.NormalLinkInterface, roadwayNumber1, None, None)
      val linearLocation2 = LinearLocation(linearLocationId2, 1, 12346.toString, 0L, 10L, SideCode.TowardsDigitizing, 0L, calibrationPoints = (CalibrationPointReference.None, CalibrationPointReference(Some(10))), geom1, LinkGeomSource.NormalLinkInterface, roadwayNumber2, None, None)

      val projectId = Sequences.nextViiteProjectId

      val link1 = dummyProjectLink(RoadPart(road1000, part), Track.Combined, Discontinuity.EndOfRoad, AddrMRange(0, 10), AddrMRange(0, 10), Some(DateTime.now()), None, 12346.toString, 0, 10, SideCode.TowardsDigitizing, RoadAddressChangeType.Transfer, projectId, AdministrativeClass.State, geom2, roadwayNumber2).copy(id = Sequences.nextProjectLinkId, roadwayId = rwId2, linearLocationId = linearLocationId2)

      val project = Project(projectId, ProjectState.Incomplete, "f", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
        "", Seq(), Seq(), None, None)
      val pls = Seq(link1)

      buildTestDataForProject(Some(project), Some(Seq(roadway1, roadway2)), Some(Seq(linearLocation, linearLocation2)), Some(pls))

      val projectChanges = List(
        //combined
        ProjectRoadwayChange(0, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(RoadAddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, Some(AdministrativeClass.State), Some(Discontinuity.Continuous), Some(8L)),
            RoadwayChangeSection(Some(link1.roadPart.roadNumber), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(link1.roadPart.partNumber), endRoadPartNumber = Some(link1.roadPart.partNumber), addrMRange = Some(link1.addrMRange), Some(AdministrativeClass.State), Some(Discontinuity.Continuous), Some(8L)),
            Discontinuity.Continuous, AdministrativeClass.State, reversed = false, 1, 8)
          , DateTime.now)
      )

      val reversedPls = pls.map(_.copy(sideCode = SideCode.switch(link1.sideCode), reversed = true))

      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(any[BoundingRectangle], any[Seq[(Int, Int)]])).thenReturn(Seq(linearLocation))
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(any[Set[Long]], any[Boolean])).thenReturn(Seq(roadway1))

      val reversedProjectChanges = projectChanges.map(p => p.copy(changeInfo = p.changeInfo.copy(changeType = RoadAddressChangeType.Transfer, source = p.changeInfo.target, reversed = true)))
      val mappedRoadwayNumbers = projectLinkDAO.fetchProjectLinksChange(projectId)
      nodesAndJunctionsService.handleJunctionAndJunctionPoints(projectChanges, pls, mappedRoadwayNumbers)

      val roadJunctionPoints = junctionPointDAO.fetchByRoadwayPoint(roadway1.roadwayNumber, roadway1.addrMRange.start, BeforeAfter.After)
      val junction1 = junctionDAO.fetchByIds(Seq(roadJunctionPoints.head.junctionId))

      val linkJunctionPoints = junctionPointDAO.fetchByRoadwayPoint(link1.roadwayNumber, link1.addrMRange.start, BeforeAfter.After)
      val junction2 = junctionDAO.fetchByIds(Seq(linkJunctionPoints.head.junctionId))

      junction1 should be(junction2)

      val roadwayPoint1 = roadwayPointDAO.fetch(roadway1.roadwayNumber, roadway1.addrMRange.start)
      val roadwayPoint2 = roadwayPointDAO.fetch(link1.roadwayNumber, link1.addrMRange.start)

      roadwayPoint1.head.addrMValue should be(roadway1.addrMRange.start)
      roadwayPoint2.head.addrMValue should be(link1.addrMRange.start)

      roadJunctionPoints.isDefined should be(true)
      roadJunctionPoints.head.beforeAfter should be(BeforeAfter.After)
      roadJunctionPoints.head.roadwayNumber should be(roadway1.roadwayNumber)
      linkJunctionPoints.isDefined should be(true)
      linkJunctionPoints.head.beforeAfter should be(BeforeAfter.After)
      linkJunctionPoints.head.roadwayNumber should be(link1.roadwayNumber)

      val reversedRoadways = Seq(Roadway(NewIdValue, link1.roadwayNumber, link1.roadPart, AdministrativeClass.State, Track.Combined, Discontinuity.Continuous, link1.addrMRange, reversed = true, DateTime.parse("2000-01-01"), None, "test", Some("TEST ROAD 1"), 1, TerminationCode.NoTermination)
      )
      roadwayDAO.create(reversedRoadways)
      val mappedReversedRoadwayNumbers = projectLinkDAO.fetchProjectLinksChange(projectId)
      roadAddressService.handleRoadwayPointsUpdate(reversedProjectChanges, mappedReversedRoadwayNumbers)
      val roadwayPointsAfterUpdate = roadwayPointDAO.fetchByRoadwayNumber(link1.roadwayNumber)
      nodesAndJunctionsService.handleJunctionAndJunctionPoints(reversedProjectChanges, reversedPls, mappedReversedRoadwayNumbers)
      val reversedLinkJunctionPoints = junctionPointDAO.fetchByRoadwayPoint(link1.roadwayNumber, roadwayPointsAfterUpdate.head.addrMValue, BeforeAfter.Before)
      linkJunctionPoints.head.id should be(reversedLinkJunctionPoints.head.id)
      reversedLinkJunctionPoints.head.beforeAfter should be(BeforeAfter.switch(linkJunctionPoints.head.beforeAfter))
    }
  }

  test("Test nodesAndJunctionsService.handleJunctionTemplates roadsToTail case When creating projectLinks Then junction template and junctions points should be handled/created properly and " +
    "When reverse, the junction points BeforeAfter should be reversed") {
    runWithRollback {
      /*
      |--R--0>|<0--L--|
       */
      val geom1 = Seq(Point(0.0, 0.0), Point(10.0, 0.0))
      val geom2 = Seq(Point(10.0, 0.0), Point(20.0, 0.0))
      val road999 = 999L
      val road1000 = 1000L
      val part = 1L
      val roadwayNumber1 = Sequences.nextRoadwayNumber
      val roadwayNumber2 = Sequences.nextRoadwayNumber
      val rwId1 = Sequences.nextRoadwayId
      val rwId2 = Sequences.nextRoadwayId
      val linearLocationId1 = Sequences.nextLinearLocationId
      val linearLocationId2 = Sequences.nextLinearLocationId
      val roadway1 = Roadway(rwId1, roadwayNumber1, RoadPart(road999,  part), AdministrativeClass.State, Track.Combined, Discontinuity.Continuous, AddrMRange(0L, 10L), reversed = false, DateTime.now, None, "user", None, 8L, TerminationCode.NoTermination, DateTime.now, None)
      val roadway2 = Roadway(rwId2, roadwayNumber2, RoadPart(road1000, part), AdministrativeClass.State, Track.Combined, Discontinuity.Continuous, AddrMRange(0L, 10L), reversed = false, DateTime.now, None, "user", None, 8L, TerminationCode.NoTermination, DateTime.now, None)
      val linearLocation = LinearLocation(linearLocationId1, 1, 12345.toString, 0L, 10L, SideCode.TowardsDigitizing, 0L, calibrationPoints = (CalibrationPointReference(Some(0L)), CalibrationPointReference(Some(10L))), geom1, LinkGeomSource.NormalLinkInterface, roadwayNumber1, None, None)
      val linearLocation2 = LinearLocation(linearLocationId2, 1, 12346.toString, 0L, 10L, SideCode.TowardsDigitizing, 0L, calibrationPoints = (CalibrationPointReference(Some(0L)), CalibrationPointReference(Some(10L))), geom2, LinkGeomSource.NormalLinkInterface, roadwayNumber2, None, None)
      val projectId = Sequences.nextViiteProjectId

      val link1 = dummyProjectLink(RoadPart(road1000, part), Track.Combined, Discontinuity.EndOfRoad, AddrMRange(0, 10), AddrMRange(0, 10), Some(DateTime.now()), None, 12346.toString, 0, 10, SideCode.AgainstDigitizing, RoadAddressChangeType.Transfer, projectId, AdministrativeClass.State, geom2, roadwayNumber2).copy(id = Sequences.nextProjectLinkId, roadwayId = rwId2, linearLocationId = linearLocationId2)

      val project = Project(projectId, ProjectState.Incomplete, "f", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
        "", Seq(), Seq(), None, None)
      val pls = Seq(link1).map(_.copy(id = Sequences.nextProjectLinkId))

      buildTestDataForProject(Some(project), Some(Seq(roadway1, roadway2)), Some(Seq(linearLocation, linearLocation2)), Some(pls))

      val projectChanges = List(
        //combined
        ProjectRoadwayChange(0, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(RoadAddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, Some(AdministrativeClass.State), Some(Discontinuity.Continuous), Some(8L)),
            RoadwayChangeSection(Some(link1.roadPart.roadNumber), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(link1.roadPart.partNumber), endRoadPartNumber = Some(link1.roadPart.partNumber), addrMRange = Some(link1.addrMRange), Some(AdministrativeClass.State), Some(Discontinuity.Continuous), Some(8L)),
            Discontinuity.Continuous, AdministrativeClass.State, reversed = false, 1, 8)
          , DateTime.now)
      )

      val reversedPls = pls.map(_.copy(sideCode = SideCode.switch(link1.sideCode), reversed = true))

      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(any[BoundingRectangle], any[Seq[(Int, Int)]])).thenReturn(Seq(linearLocation))
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(any[Set[Long]], any[Boolean])).thenReturn(Seq(roadway1))

      val reversedProjectChanges = projectChanges.map(p => p.copy(changeInfo = p.changeInfo.copy(changeType = RoadAddressChangeType.Transfer, source = p.changeInfo.target, reversed = true)))
      val mappedRoadwayNumbers = projectLinkDAO.fetchProjectLinksChange(projectId)
      nodesAndJunctionsService.handleJunctionAndJunctionPoints(projectChanges, pls, mappedRoadwayNumbers)

      val roadJunctionPoints = junctionPointDAO.fetchByRoadwayPoint(roadway1.roadwayNumber, roadway1.addrMRange.end, BeforeAfter.Before)
      val junction1 = junctionDAO.fetchByIds(Seq(roadJunctionPoints.head.junctionId))

      val linkJunctionPoints = junctionPointDAO.fetchByRoadwayPoint(link1.roadwayNumber, link1.addrMRange.end, BeforeAfter.Before)
      val junction2 = junctionDAO.fetchByIds(Seq(linkJunctionPoints.head.junctionId))

      junction1 should be(junction2)

      val roadwayPoint1 = roadwayPointDAO.fetch(roadway1.roadwayNumber, roadway1.addrMRange.end)
      val roadwayPoint2 = roadwayPointDAO.fetch(link1.roadwayNumber, link1.addrMRange.end)

      roadwayPoint1.head.addrMValue should be(roadway1.addrMRange.end)
      roadwayPoint2.head.addrMValue should be(link1.addrMRange.end)

      roadJunctionPoints.isDefined should be(true)
      roadJunctionPoints.head.beforeAfter should be(BeforeAfter.Before)
      roadJunctionPoints.head.roadwayNumber should be(roadway1.roadwayNumber)
      linkJunctionPoints.isDefined should be(true)
      linkJunctionPoints.head.beforeAfter should be(BeforeAfter.Before)
      linkJunctionPoints.head.roadwayNumber should be(link1.roadwayNumber)

      val reversedRoadways = Seq(Roadway(NewIdValue, link1.roadwayNumber, link1.roadPart, AdministrativeClass.State, Track.Combined, Discontinuity.Continuous, link1.addrMRange, reversed = true, DateTime.parse("2000-01-01"), None, "test", Some("TEST ROAD 1"), 1, TerminationCode.NoTermination)
      )
      roadwayDAO.create(reversedRoadways)
      val mappedReversedRoadwayNumbers = projectLinkDAO.fetchProjectLinksChange(projectId)
      roadAddressService.handleRoadwayPointsUpdate(reversedProjectChanges, mappedReversedRoadwayNumbers)
      val roadwayPointsAfterUpdate = roadwayPointDAO.fetchByRoadwayNumber(link1.roadwayNumber)
      nodesAndJunctionsService.handleJunctionAndJunctionPoints(reversedProjectChanges, reversedPls, mappedReversedRoadwayNumbers)
      val reversedLinkJunctionPoints = junctionPointDAO.fetchByRoadwayPoint(link1.roadwayNumber, roadwayPointsAfterUpdate.head.addrMValue, BeforeAfter.After)
      linkJunctionPoints.head.id should be(reversedLinkJunctionPoints.head.id)
      reversedLinkJunctionPoints.head.beforeAfter should be(BeforeAfter.switch(linkJunctionPoints.head.beforeAfter))
    }
  }

  test("Test nodesAndJunctionsService.handleJunctionTemplates roadsFromTail case When creating projectLinks Then junction template and junctions points should be handled/created properly and" +
    " When reverse, the junction points BeforeAfter should be reversed") {
    runWithRollback {
      /*
      <--R--0|<0--L--|
       */
      val geom1 = Seq(Point(0.0, 0.0), Point(10.0, 0.0))
      val geom2 = Seq(Point(10.0, 0.0), Point(20.0, 0.0))
      val road999 = 999L
      val road1000 = 1000L
      val part = 1L
      val roadwayNumber1 = Sequences.nextRoadwayNumber
      val roadwayNumber2 = Sequences.nextRoadwayNumber
      val rwId1 = Sequences.nextRoadwayId
      val rwId2 = Sequences.nextRoadwayId
      val linearLocationId1 = Sequences.nextLinearLocationId
      val linearLocationId2 = Sequences.nextLinearLocationId
      val roadway1 = Roadway(rwId1, roadwayNumber1, RoadPart(road999,  part), AdministrativeClass.State, Track.Combined, Discontinuity.Continuous, AddrMRange(0L, 10L), reversed = false, DateTime.now, None, "user", None, 8L, TerminationCode.NoTermination, DateTime.now, None)
      val roadway2 = Roadway(rwId2, roadwayNumber2, RoadPart(road1000, part), AdministrativeClass.State, Track.Combined, Discontinuity.Continuous, AddrMRange(0L, 10L), reversed = false, DateTime.now, None, "user", None, 8L, TerminationCode.NoTermination, DateTime.now, None)
      val linearLocation = LinearLocation(linearLocationId1, 1, 12345.toString, 0L, 10L, SideCode.AgainstDigitizing, 0L, calibrationPoints = (CalibrationPointReference(Some(0)), CalibrationPointReference(Some(10))), geom1, LinkGeomSource.NormalLinkInterface, roadwayNumber1, None, None)
      val linearLocation2 = LinearLocation(linearLocationId2, 1, 12346.toString, 0L, 10L, SideCode.AgainstDigitizing, 0L, calibrationPoints = (CalibrationPointReference(Some(0)), CalibrationPointReference(Some(10))), geom2, LinkGeomSource.NormalLinkInterface, roadwayNumber2, None, None)

      val projectId = Sequences.nextViiteProjectId

      val link1 = dummyProjectLink(RoadPart(road1000, part), Track.Combined, Discontinuity.EndOfRoad, AddrMRange(0, 10), AddrMRange(0, 10), Some(DateTime.now()), None, 12346.toString, 0, 10, SideCode.AgainstDigitizing, RoadAddressChangeType.Transfer, projectId, AdministrativeClass.State, geom2, roadwayNumber1 + 1).copy(id = Sequences.nextProjectLinkId, roadwayId = rwId2, linearLocationId = linearLocationId2)

      val project = Project(projectId, ProjectState.Incomplete, "f", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
        "", Seq(), Seq(), None, None)
      val pls = Seq(link1)

      buildTestDataForProject(Some(project), Some(Seq(roadway1, roadway2)), Some(Seq(linearLocation, linearLocation2)), Some(pls))

      val projectChanges = List(
        //combined
        ProjectRoadwayChange(0, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(RoadAddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, Some(AdministrativeClass.State), Some(Discontinuity.Continuous), Some(8L)),
            RoadwayChangeSection(Some(link1.roadPart.roadNumber), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(link1.roadPart.partNumber), endRoadPartNumber = Some(link1.roadPart.partNumber), addrMRange = Some(link1.addrMRange), Some(AdministrativeClass.State), Some(Discontinuity.Continuous), Some(8L)),
            Discontinuity.Continuous, AdministrativeClass.State, reversed = false, 1, 8), DateTime.now)
      )

      val reversedPls = pls.map(_.copy(sideCode = SideCode.switch(link1.sideCode), reversed = true))

      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(any[BoundingRectangle], any[Seq[(Int, Int)]])).thenReturn(Seq(linearLocation))
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(any[Set[Long]], any[Boolean])).thenReturn(Seq(roadway1))

      val reversedProjectChanges = projectChanges.map(p => p.copy(changeInfo = p.changeInfo.copy(changeType = RoadAddressChangeType.Transfer, source = p.changeInfo.target, reversed = true)))
      val mappedRoadwayNumbers = projectLinkDAO.fetchProjectLinksChange(projectId)
      nodesAndJunctionsService.handleJunctionAndJunctionPoints(projectChanges, pls, mappedRoadwayNumbers)

      val roadJunctionPoints = junctionPointDAO.fetchByRoadwayPoint(roadway1.roadwayNumber, roadway1.addrMRange.start, BeforeAfter.After)
      val junction1 = junctionDAO.fetchByIds(Seq(roadJunctionPoints.head.junctionId))

      val linkJunctionPoints = junctionPointDAO.fetchByRoadwayPoint(link1.roadwayNumber, link1.addrMRange.end, BeforeAfter.Before)
      val junction2 = junctionDAO.fetchByIds(Seq(linkJunctionPoints.head.junctionId))

      junction1 should be(junction2)

      val roadwayPoint1 = roadwayPointDAO.fetch(roadway1.roadwayNumber, roadway1.addrMRange.start)
      val roadwayPoint2 = roadwayPointDAO.fetch(link1.roadwayNumber, link1.addrMRange.end)

      roadwayPoint1.head.addrMValue should be(roadway1.addrMRange.start)
      roadwayPoint2.head.addrMValue should be(link1.addrMRange.end)

      roadJunctionPoints.isDefined should be(true)
      roadJunctionPoints.head.beforeAfter should be(BeforeAfter.After)
      roadJunctionPoints.head.roadwayNumber should be(roadway1.roadwayNumber)
      linkJunctionPoints.isDefined should be(true)
      linkJunctionPoints.head.beforeAfter should be(BeforeAfter.Before)
      linkJunctionPoints.head.roadwayNumber should be(link1.roadwayNumber)

      val reversedRoadways = Seq(Roadway(NewIdValue, link1.roadwayNumber, link1.roadPart, AdministrativeClass.State, Track.Combined, Discontinuity.Continuous, link1.addrMRange, reversed = true, DateTime.parse("2000-01-01"), None, "test", Some("TEST ROAD 1"), 1, TerminationCode.NoTermination)
      )
      roadwayDAO.create(reversedRoadways)
      val mappedReversedRoadwayNumbers = projectLinkDAO.fetchProjectLinksChange(projectId)
      roadAddressService.handleRoadwayPointsUpdate(reversedProjectChanges, mappedReversedRoadwayNumbers)
      val roadwayPointsAfterUpdate = roadwayPointDAO.fetchByRoadwayNumber(link1.roadwayNumber)
      nodesAndJunctionsService.handleJunctionAndJunctionPoints(reversedProjectChanges, reversedPls, mappedReversedRoadwayNumbers)
      val reversedLinkJunctionPoints = junctionPointDAO.fetchByRoadwayPoint(link1.roadwayNumber, roadwayPointsAfterUpdate.head.addrMValue, BeforeAfter.After)
      linkJunctionPoints.head.id should be(reversedLinkJunctionPoints.head.id)
      reversedLinkJunctionPoints.head.beforeAfter should be(BeforeAfter.switch(linkJunctionPoints.head.beforeAfter))
    }
  }

  test("Test nodesAndJunctionsService.handleJunctionTemplates When creating one road that intersects itself with discontinuous address values Then junction template and junctions points should be handled/created properly") {
    runWithRollback {
      /*
     |--L1-->|
              0
             | \
             |   L2
             |    \
             R3    >0|
             |    / |
             |  R2* |
             V /    |
     |--R1-->|0     |
             |      |
             R4     L3
             |      |
             v      v
            Note:
            0: Illustration where junction points should be created
            L: Left track
            R: Right Track
            R*: Minor Discontinuity Right track
       */

      val road = 999L
      val part = 1L
      val projectId = Sequences.nextViiteProjectId
      val rwId = Sequences.nextRoadwayId
      val llId = Sequences.nextLinearLocationId
      val rwNumber = Sequences.nextRoadwayNumber
      val plId = Sequences.nextProjectLinkId


      val leftGeom1 = Seq(Point(0.0, 50.0), Point(5.0, 50.0))
      val leftGeom2 = Seq(Point(5.0, 50.0), Point(10.0, 45.0))
      val leftGeom3 = Seq(Point(10.0, 45.0), Point(10.0, 35.0))
      val rightGeom1 = Seq(Point(0.0, 40.0), Point(5.0, 40.0))
      val rightGeom2 = Seq(Point(5.0, 40.0), Point(10.0, 45.0))
      val rightGeom3 = Seq(Point(5.0, 50.0), Point(5.0, 40.0))
      val rightGeom4 = Seq(Point(5.0, 40.0), Point(5.0, 35.0))

      val leftLink1 = dummyProjectLink(RoadPart(road, part), Track.LeftSide, Discontinuity.Continuous,          AddrMRange( 0,  5), AddrMRange( 0,  5), Some(DateTime.now()), None, 12345.toString, 0,  5, SideCode.TowardsDigitizing, RoadAddressChangeType.Transfer, projectId, AdministrativeClass.State, leftGeom1,  rwNumber    ).copy(id = plId,     projectId = projectId, roadwayId = rwId,     linearLocationId = llId)
      val leftLink2 = dummyProjectLink(RoadPart(road, part), Track.LeftSide, Discontinuity.Continuous,          AddrMRange( 5, 10), AddrMRange( 5, 10), Some(DateTime.now()), None, 12346.toString, 0,  5, SideCode.TowardsDigitizing, RoadAddressChangeType.Transfer, projectId, AdministrativeClass.State, leftGeom2,  rwNumber    ).copy(id = plId + 1, projectId = projectId, roadwayId = rwId,     linearLocationId = llId + 1)
      val leftLink3 = dummyProjectLink(RoadPart(road, part), Track.LeftSide, Discontinuity.EndOfRoad,           AddrMRange(10, 20), AddrMRange(10, 20), Some(DateTime.now()), None, 12347.toString, 0, 10, SideCode.TowardsDigitizing, RoadAddressChangeType.Transfer, projectId, AdministrativeClass.State, leftGeom3,  rwNumber + 1).copy(id = plId + 2, projectId = projectId, roadwayId = rwId + 1, linearLocationId = llId + 2)
      val rightLink1 = dummyProjectLink(RoadPart(road, part), Track.RightSide, Discontinuity.Continuous,        AddrMRange( 0,  5), AddrMRange( 0,  5), Some(DateTime.now()), None, 12348.toString, 0,  5, SideCode.TowardsDigitizing, RoadAddressChangeType.Transfer, projectId, AdministrativeClass.State, rightGeom1, rwNumber + 2).copy(id = plId + 3, projectId = projectId, roadwayId = rwId + 2, linearLocationId = llId + 3)
      val rightLink2 = dummyProjectLink(RoadPart(road, part), Track.RightSide, Discontinuity.MinorDiscontinuity,AddrMRange( 5, 10), AddrMRange( 5, 10), Some(DateTime.now()), None, 12349.toString, 0,  5, SideCode.TowardsDigitizing, RoadAddressChangeType.Transfer, projectId, AdministrativeClass.State, rightGeom2, rwNumber + 2).copy(id = plId + 4, projectId = projectId, roadwayId = rwId + 2, linearLocationId = llId + 4)
      val rightLink3 = dummyProjectLink(RoadPart(road, part), Track.RightSide, Discontinuity.Continuous,        AddrMRange(10, 15), AddrMRange(10, 15), Some(DateTime.now()), None, 12350.toString, 0,  5, SideCode.TowardsDigitizing, RoadAddressChangeType.Transfer, projectId, AdministrativeClass.State, rightGeom3, rwNumber + 3).copy(id = plId + 5, projectId = projectId, roadwayId = rwId + 3, linearLocationId = llId + 5)
      val rightLink4 = dummyProjectLink(RoadPart(road, part), Track.RightSide, Discontinuity.EndOfRoad,         AddrMRange(15, 20), AddrMRange(15, 20), Some(DateTime.now()), None, 12351.toString, 0,  5, SideCode.TowardsDigitizing, RoadAddressChangeType.Transfer, projectId, AdministrativeClass.State, rightGeom4, rwNumber + 3).copy(id = plId + 6, projectId = projectId, roadwayId = rwId + 3, linearLocationId = llId + 6)

      val project = Project(projectId, ProjectState.Incomplete, "f", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
        "", Seq(), Seq(), None, None)

      val leftPLinks = Seq(leftLink1, leftLink2, leftLink3)
      val rightPLinks = Seq(rightLink1, rightLink2, rightLink3, rightLink4)

      val (lll1, _/*rw1*/): (LinearLocation, Roadway) = Seq(leftLink1).map(toRoadwayAndLinearLocation).head
      val (lll2,    rw2  ): (LinearLocation, Roadway) = Seq(leftLink2).map(toRoadwayAndLinearLocation).head
      val (lll3,    rw3  ): (LinearLocation, Roadway) = Seq(leftLink3).map(toRoadwayAndLinearLocation).head
      val (rll1, _/*rw4*/): (LinearLocation, Roadway) = Seq(rightLink1).map(toRoadwayAndLinearLocation).head
      val (rll2,    rw5  ): (LinearLocation, Roadway) = Seq(rightLink2).map(toRoadwayAndLinearLocation).head
      val (rll3, _/*rw6*/): (LinearLocation, Roadway) = Seq(rightLink3).map(toRoadwayAndLinearLocation).head
      val (rll4,    rw7  ): (LinearLocation, Roadway) = Seq(rightLink4).map(toRoadwayAndLinearLocation).head
      val rw1WithId = rw2.copy(addrMRange = AddrMRange( 0, 10), ely = 8L)
      val rw2WithId = rw5.copy(addrMRange = AddrMRange( 0, 10), ely = 8L)
      val rw3WithId = rw3.copy(addrMRange = AddrMRange(10, 20), ely = 8L)
      val rw4WithId = rw7.copy(addrMRange = AddrMRange(10, 20), ely = 8L)
      val orderedlll1 = lll1.copy(orderNumber = 1)
      val orderedlll2 = lll2.copy(orderNumber = 2)
      val orderedlll3 = lll3.copy(orderNumber = 3)

      val orderedrll1 = rll1.copy(orderNumber = 1)
      val orderedrll2 = rll2.copy(orderNumber = 2)
      val orderedrll3 = rll3.copy(orderNumber = 3)
      val orderedrll4 = rll4.copy(orderNumber = 4)

      buildTestDataForProject(Some(project),
        Some(Seq(rw1WithId, rw2WithId, rw3WithId, rw4WithId)), Some(Seq(orderedlll1, orderedlll2, orderedlll3, orderedrll1, orderedrll2, orderedrll3, orderedrll4)), Some(leftPLinks ++ rightPLinks))

      val projectChanges = List(
        // left - continuous
        ProjectRoadwayChange(projectId, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(RoadAddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, Some(AdministrativeClass.State), Some(Discontinuity.Continuous), Some(8L)),
            RoadwayChangeSection(Some(road), Some(Track.LeftSide.value.toLong), startRoadPartNumber = Some(part), endRoadPartNumber = Some(part), addrMRange = Some(AddrMRange(0L, 10L)), Some(AdministrativeClass.State), Some(Discontinuity.Continuous), Some(8L)),
            Discontinuity.Continuous, AdministrativeClass.State, reversed = false, 1, 8)
          , DateTime.now),
        // right - discontinuous
        ProjectRoadwayChange(projectId, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(RoadAddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, Some(AdministrativeClass.State), Some(Discontinuity.Continuous), Some(8L)),
            RoadwayChangeSection(Some(road), Some(Track.RightSide.value.toLong), startRoadPartNumber = Some(part), endRoadPartNumber = Some(part), addrMRange = Some(AddrMRange(0L, 10L)), Some(AdministrativeClass.State), Some(Discontinuity.Discontinuous), Some(8L)),
            Discontinuity.MinorDiscontinuity, AdministrativeClass.State, reversed = false, 2, 8)
          , DateTime.now),
        //  left - end of road
        ProjectRoadwayChange(projectId, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(RoadAddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, Some(AdministrativeClass.State), Some(Discontinuity.Continuous), Some(8L)),
            RoadwayChangeSection(Some(road), Some(Track.LeftSide.value.toLong), startRoadPartNumber = Some(part), endRoadPartNumber = Some(part), addrMRange = Some(AddrMRange(10L, 20L)), Some(AdministrativeClass.State), Some(Discontinuity.EndOfRoad), Some(8L)),
            Discontinuity.EndOfRoad, AdministrativeClass.State, reversed = false, 3, 8)
          , DateTime.now),
        //  right - end of road
        ProjectRoadwayChange(projectId, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(RoadAddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, Some(AdministrativeClass.State), Some(Discontinuity.Continuous), Some(8L)),
            RoadwayChangeSection(Some(road), Some(Track.RightSide.value.toLong), startRoadPartNumber = Some(part), endRoadPartNumber = Some(part), addrMRange = Some(AddrMRange(10L, 20L)), Some(AdministrativeClass.State), Some(Discontinuity.EndOfRoad), Some(8L)),
            Discontinuity.EndOfRoad, AdministrativeClass.State, reversed = false, 4, 8)
          , DateTime.now)
      )

      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(any[BoundingRectangle], any[Seq[(Int, Int)]])).thenReturn(Seq(orderedlll1, orderedlll2, orderedlll3, orderedrll1, orderedrll2, orderedrll3, orderedrll4))
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(any[Set[Long]], any[Boolean])).thenReturn(Seq(rw1WithId, rw2WithId, rw3WithId, rw4WithId))

      val mappedReservedRoadwayNumbers = projectLinkDAO.fetchProjectLinksChange(projectId)
      roadAddressService.handleRoadwayPointsUpdate(projectChanges, mappedReservedRoadwayNumbers)
      nodesAndJunctionsService.handleJunctionAndJunctionPoints(projectChanges, leftPLinks ++ rightPLinks, mappedReservedRoadwayNumbers)

      val roadwayPoints = roadwayPointDAO.fetchByRoadwayNumbers((leftPLinks ++ rightPLinks).map(_.roadwayNumber)).map(_.id)
      val junctionPoints = junctionPointDAO.fetchByRoadwayPointIds(roadwayPoints)
      val junctionTemplates = junctionDAO.fetchTemplatesByRoadwayNumbers(junctionPoints.map(_.roadwayNumber).distinct)

      junctionPoints.count(_.beforeAfter == BeforeAfter.Before) should be(5)
      junctionPoints.count(_.beforeAfter == BeforeAfter.After) should be(5)
      junctionPoints.length should be(10)
      junctionTemplates.size should be(3)

      nodesAndJunctionsService.expireObsoleteNodesAndJunctions(leftPLinks ++ rightPLinks, Some(project.startDate.minusDays(1)), username = project.createdBy)

      val shouldExistJunctionPoints = junctionPointDAO.fetchByRoadwayPointIds(roadwayPoints)
      shouldExistJunctionPoints.length should be(10)
      shouldExistJunctionPoints.count(_.beforeAfter == BeforeAfter.Before) should be(5)
      shouldExistJunctionPoints.count(_.beforeAfter == BeforeAfter.After) should be(5)

      val shouldExistJunctionTemplates = junctionDAO.fetchTemplatesByRoadwayNumbers(shouldExistJunctionPoints.map(_.roadwayNumber).distinct)
      shouldExistJunctionTemplates.size should be(3)
      val calibrationPointsInJunctionPointsPlace = CalibrationPointDAO.fetchByLinkId((leftPLinks ++ rightPLinks).map(_.linkId))
      calibrationPointsInJunctionPointsPlace.size should be (10)
      val (start, end) = calibrationPointsInJunctionPointsPlace.partition(_.startOrEnd == CalibrationPointLocation.StartOfLink)
      val startCalibrationPointsLinks = start.map(_.linkId).distinct
      val endCalibrationPointsLinks = end.map(_.linkId).distinct
      startCalibrationPointsLinks.size should be (5)
      endCalibrationPointsLinks.size should be (5)

      startCalibrationPointsLinks.forall(List(leftLink2.linkId, leftLink3.linkId, rightLink2.linkId, rightLink3.linkId, rightLink4.linkId).contains(_)) should be (true)
      endCalibrationPointsLinks.forall(List(leftLink1.linkId, leftLink2.linkId, rightLink1.linkId, rightLink2.linkId, rightLink3.linkId).contains(_)) should be (true)

    }
  }

  test("Test nodesAndJunctionsService.handleJunctionTemplates When reversing one road that intersects itself with discontinuous address values Then junction template and junctions points should be handled/created properly") {
    runWithRollback {

      /*
           <---R3---0                   |--L1--->
                    ^^                           0
                    | \                          | \
                    |   R2                       |   L2
                    |    \    Reversing          |    \
                   L2*    0^   ====>             R3    >0|
                    |    / |                     |    / |
                    |  L3  |                     |  R2* |
                    | /    |                     V /    |
           <---L4--0^<     |             |--R1-->|0     |
                    |      |                     |      |
                    L1     R1                    R4     L3
                    |      |                     |      |
                    _      _                     v      v
      Note:
            0: Illustration where junction points should be created
            L: Left track
            R: Right Track
            *: Minor Discontinuity
       */
      // defining road, part, ids etc..
      val road = 999L
      val part = 1L
      val projectId = Sequences.nextViiteProjectId
      val rwId = Sequences.nextRoadwayId
      val llId = Sequences.nextLinearLocationId
      val rwNumber = Sequences.nextRoadwayNumber
      val plId = Sequences.nextProjectLinkId

      //Geometry
      val leftGeom1 = Seq(Point(0.0, 50.0), Point(5.0, 50.0))
      val leftGeom2 = Seq(Point(5.0, 50.0), Point(10.0, 45.0))
      val leftGeom3 = Seq(Point(10.0, 45.0), Point(10.0, 35.0))
      val rightGeom1 = Seq(Point(0.0, 40.0), Point(5.0, 40.0))
      val rightGeom2 = Seq(Point(5.0, 40.0), Point(10.0, 45.0))
      val rightGeom3 = Seq(Point(5.0, 50.0), Point(5.0, 40.0))
      val rightGeom4 = Seq(Point(5.0, 40.0), Point(5.0, 35.0))

      // ProjectLinks
      val leftLink1  = dummyProjectLink(RoadPart(road, part), Track.LeftSide,  Discontinuity.Continuous,        AddrMRange( 0,  5), AddrMRange( 0,  5), Some(DateTime.now()), None, 12345.toString, 0,  5, SideCode.TowardsDigitizing, RoadAddressChangeType.Transfer, projectId, AdministrativeClass.State, leftGeom1,  rwNumber    ).copy(id = plId,     projectId = projectId, roadwayId = rwId,     linearLocationId = llId,     reversed = true)
      val leftLink2  = dummyProjectLink(RoadPart(road, part), Track.LeftSide,  Discontinuity.Continuous,        AddrMRange( 5, 10), AddrMRange( 5, 10), Some(DateTime.now()), None, 12346.toString, 0,  5, SideCode.TowardsDigitizing, RoadAddressChangeType.Transfer, projectId, AdministrativeClass.State, leftGeom2,  rwNumber    ).copy(id = plId + 1, projectId = projectId, roadwayId = rwId,     linearLocationId = llId + 1, reversed = true)
      val leftLink3  = dummyProjectLink(RoadPart(road, part), Track.LeftSide,  Discontinuity.EndOfRoad,         AddrMRange(10, 20), AddrMRange(10, 20), Some(DateTime.now()), None, 12347.toString, 0, 10, SideCode.TowardsDigitizing, RoadAddressChangeType.Transfer, projectId, AdministrativeClass.State, leftGeom3,  rwNumber + 1).copy(id = plId + 2, projectId = projectId, roadwayId = rwId + 1, linearLocationId = llId + 2, reversed = true)
      val rightLink1 = dummyProjectLink(RoadPart(road, part), Track.RightSide, Discontinuity.Continuous,        AddrMRange( 0,  5), AddrMRange( 0,  5), Some(DateTime.now()), None, 12348.toString, 0,  5, SideCode.TowardsDigitizing, RoadAddressChangeType.Transfer, projectId, AdministrativeClass.State, rightGeom1, rwNumber + 2).copy(id = plId + 3, projectId = projectId, roadwayId = rwId + 2, linearLocationId = llId + 3, reversed = true)
      val rightLink2 = dummyProjectLink(RoadPart(road, part), Track.RightSide, Discontinuity.MinorDiscontinuity,AddrMRange( 5, 10), AddrMRange( 5, 10), Some(DateTime.now()), None, 12349.toString, 0,  5, SideCode.TowardsDigitizing, RoadAddressChangeType.Transfer, projectId, AdministrativeClass.State, rightGeom2, rwNumber + 2).copy(id = plId + 4, projectId = projectId, roadwayId = rwId + 2, linearLocationId = llId + 4, reversed = true)
      val rightLink3 = dummyProjectLink(RoadPart(road, part), Track.RightSide, Discontinuity.Continuous,        AddrMRange(10, 15), AddrMRange(10, 15), Some(DateTime.now()), None, 12350.toString, 0,  5, SideCode.TowardsDigitizing, RoadAddressChangeType.Transfer, projectId, AdministrativeClass.State, rightGeom3, rwNumber + 3).copy(id = plId + 5, projectId = projectId, roadwayId = rwId + 3, linearLocationId = llId + 5, reversed = true)
      val rightLink4 = dummyProjectLink(RoadPart(road, part), Track.RightSide, Discontinuity.EndOfRoad,         AddrMRange(15, 20), AddrMRange(15, 20), Some(DateTime.now()), None, 12351.toString, 0,  5, SideCode.TowardsDigitizing, RoadAddressChangeType.Transfer, projectId, AdministrativeClass.State, rightGeom4, rwNumber + 3).copy(id = plId + 6, projectId = projectId, roadwayId = rwId + 3, linearLocationId = llId + 6, reversed = true)

      // Creating Project
      val project = Project(projectId, ProjectState.Incomplete, "f", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
        "", Seq(), Seq(), None, None)

      // Grouping project links by track
      val leftPLinks = Seq(leftLink1, leftLink2, leftLink3)
      val rightPLinks = Seq(rightLink1, rightLink2, rightLink3, rightLink4)

      // Grouping linear locations with roadways
      val (lll1, _/*rw1*/): (LinearLocation, Roadway) = Seq(leftLink1).map(toRoadwayAndLinearLocation).head
      val (lll2,    rw2  ): (LinearLocation, Roadway) = Seq(leftLink2).map(toRoadwayAndLinearLocation).head
      val (lll3,    rw3  ): (LinearLocation, Roadway) = Seq(leftLink3).map(toRoadwayAndLinearLocation).head
      val (rll1, _/*rw4*/): (LinearLocation, Roadway) = Seq(rightLink1).map(toRoadwayAndLinearLocation).head
      val (rll2,    rw5  ): (LinearLocation, Roadway) = Seq(rightLink2).map(toRoadwayAndLinearLocation).head
      val (rll3, _/*rw6*/): (LinearLocation, Roadway) = Seq(rightLink3).map(toRoadwayAndLinearLocation).head
      val (rll4,    rw7  ): (LinearLocation, Roadway) = Seq(rightLink4).map(toRoadwayAndLinearLocation).head
      // Defining roadways and their M values
      val rw1WithId = rw2.copy(ely = 8L, addrMRange = AddrMRange( 0, 10))
      val rw2WithId = rw5.copy(ely = 8L, addrMRange = AddrMRange( 0, 10))
      val rw3WithId = rw3.copy(ely = 8L, addrMRange = AddrMRange(10, 20))
      val rw4WithId = rw7.copy(ely = 8L, addrMRange = AddrMRange(10, 20))

      // Defining linear location order numbering
      val orderedlll1 = lll1.copy(orderNumber = 1)
      val orderedlll2 = lll2.copy(orderNumber = 2)
      val orderedlll3 = lll3.copy(orderNumber = 3)

      val orderedrll1 = rll1.copy(orderNumber = 1)
      val orderedrll2 = rll2.copy(orderNumber = 2)
      val orderedrll3 = rll3.copy(orderNumber = 3)
      val orderedrll4 = rll4.copy(orderNumber = 4)

      // Building data for project
      buildTestDataForProject(Some(project),
        Some(Seq(rw1WithId, rw2WithId, rw3WithId, rw4WithId)), Some(Seq(orderedlll1, orderedlll2, orderedlll3, orderedrll1, orderedrll2, orderedrll3, orderedrll4)), Some(leftPLinks ++ rightPLinks))

      // Changes that happened in project
      val projectChanges = List(
        // right -> left & end of road -> continuous (i.e. parallel)
        ProjectRoadwayChange(projectId, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(RoadAddressChangeType.Transfer,
            RoadwayChangeSection(Some(road), Some(Track.RightSide.value.toLong), startRoadPartNumber = Some(part), endRoadPartNumber = Some(part), addrMRange = Some(AddrMRange(10L,20L)), Some(AdministrativeClass.State), Some(Discontinuity.EndOfRoad),  Some(8L)),
            RoadwayChangeSection(Some(road), Some(Track.LeftSide.value.toLong),  startRoadPartNumber = Some(part), endRoadPartNumber = Some(part), addrMRange = Some(AddrMRange( 0L,10L)), Some(AdministrativeClass.State), Some(Discontinuity.Continuous), Some(8L)),
            Discontinuity.Continuous, AdministrativeClass.State, reversed = true, 1, 8)
          , DateTime.now),
        // left -> right & end of road -> minor discontinuity
        ProjectRoadwayChange(projectId, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(RoadAddressChangeType.Transfer,
            RoadwayChangeSection(Some(road), Some(Track.LeftSide.value.toLong),  startRoadPartNumber = Some(part), endRoadPartNumber = Some(part), addrMRange = Some(AddrMRange(10L,20L)), Some(AdministrativeClass.State), Some(Discontinuity.EndOfRoad),          Some(8L)),
            RoadwayChangeSection(Some(road), Some(Track.RightSide.value.toLong), startRoadPartNumber = Some(part), endRoadPartNumber = Some(part), addrMRange = Some(AddrMRange( 0L,10L)), Some(AdministrativeClass.State), Some(Discontinuity.MinorDiscontinuity), Some(8L)),
            Discontinuity.MinorDiscontinuity, AdministrativeClass.State, reversed = true, 2, 8)
          , DateTime.now),
        //  right -> left & continuous -> end of road
        ProjectRoadwayChange(projectId, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(RoadAddressChangeType.Transfer,
            RoadwayChangeSection(Some(road), Some(Track.RightSide.value.toLong), startRoadPartNumber = Some(part), endRoadPartNumber = Some(part), addrMRange = Some(AddrMRange( 0L,10L)), Some(AdministrativeClass.State), Some(Discontinuity.Continuous), Some(8L)),
            RoadwayChangeSection(Some(road), Some(Track.LeftSide.value.toLong),  startRoadPartNumber = Some(part), endRoadPartNumber = Some(part), addrMRange = Some(AddrMRange(10L,20L)), Some(AdministrativeClass.State), Some(Discontinuity.EndOfRoad),  Some(8L)),
            Discontinuity.EndOfRoad, AdministrativeClass.State, reversed = true, 3, 8)
          , DateTime.now),
        //  left -> right & minor discontinuity -> end of road
        ProjectRoadwayChange(projectId, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(RoadAddressChangeType.Transfer,
            RoadwayChangeSection(Some(road), Some(Track.LeftSide.value.toLong),  startRoadPartNumber = Some(part), endRoadPartNumber = Some(part), addrMRange = Some(AddrMRange( 0L,10L)), Some(AdministrativeClass.State), Some(Discontinuity.MinorDiscontinuity), Some(8L)),
            RoadwayChangeSection(Some(road), Some(Track.RightSide.value.toLong), startRoadPartNumber = Some(part), endRoadPartNumber = Some(part), addrMRange = Some(AddrMRange(10L,20L)), Some(AdministrativeClass.State), Some(Discontinuity.EndOfRoad),          Some(8L)),
            Discontinuity.EndOfRoad, AdministrativeClass.State, reversed = true, 4, 8)
          , DateTime.now)
      )
      // Get linear locations and roadway numbers
      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(any[BoundingRectangle], any[Seq[(Int, Int)]])).thenReturn(Seq(orderedlll1, orderedlll2, orderedlll3, orderedrll1, orderedrll2, orderedrll3, orderedrll4))
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(any[Set[Long]], any[Boolean])).thenReturn(Seq(rw1WithId, rw2WithId, rw3WithId, rw4WithId))

      val mappedReservedRoadwayNumbers = projectLinkDAO.fetchProjectLinksChange(projectId)
      roadAddressService.handleRoadwayPointsUpdate(projectChanges, mappedReservedRoadwayNumbers)
      nodesAndJunctionsService.handleJunctionAndJunctionPoints(projectChanges, leftPLinks ++ rightPLinks, mappedReservedRoadwayNumbers)

      val roadwayPoints = roadwayPointDAO.fetchByRoadwayNumbers((leftPLinks ++ rightPLinks).map(_.roadwayNumber)).map(_.id)
      val junctionPoints = junctionPointDAO.fetchByRoadwayPointIds(roadwayPoints)
      val junctionTemplates = junctionDAO.fetchTemplatesByRoadwayNumbers(junctionPoints.map(_.roadwayNumber).distinct)

      junctionPoints.count(_.beforeAfter == BeforeAfter.Before) should be(5)
      junctionPoints.count(_.beforeAfter == BeforeAfter.After) should be(5)
      junctionPoints.length should be(10)
      junctionTemplates.size should be(3)

      nodesAndJunctionsService.expireObsoleteNodesAndJunctions(leftPLinks ++ rightPLinks, Some(project.startDate.minusDays(1)), username = project.createdBy)

      val shouldExistJunctionPoints = junctionPointDAO.fetchByRoadwayPointIds(roadwayPoints)
      shouldExistJunctionPoints.length should be(10)
      shouldExistJunctionPoints.count(_.beforeAfter == BeforeAfter.Before) should be(5)
      shouldExistJunctionPoints.count(_.beforeAfter == BeforeAfter.After) should be(5)

      val shouldExistJunctionTemplates = junctionDAO.fetchTemplatesByRoadwayNumbers(shouldExistJunctionPoints.map(_.roadwayNumber).distinct)
      shouldExistJunctionTemplates.size should be(3)
      val calibrationPointsInJunctionPointsPlace = CalibrationPointDAO.fetchByLinkId((leftPLinks ++ rightPLinks).map(_.linkId))
      calibrationPointsInJunctionPointsPlace.size should be (10)
      val (start, end) = calibrationPointsInJunctionPointsPlace.partition(_.startOrEnd == CalibrationPointLocation.StartOfLink)
      val startCalibrationPointsLinks = start.map(_.linkId).distinct
      val endCalibrationPointsLinks = end.map(_.linkId).distinct
      startCalibrationPointsLinks.size should be (5)
      endCalibrationPointsLinks.size should be (5)

      startCalibrationPointsLinks.forall(List(leftLink2.linkId, leftLink3.linkId, rightLink2.linkId, rightLink3.linkId, rightLink4.linkId).contains(_)) should be (true)
      endCalibrationPointsLinks.forall(List(leftLink1.linkId, leftLink2.linkId, rightLink1.linkId, rightLink2.linkId, rightLink3.linkId).contains(_)) should be (true)
    }
  }

  // TODO Mock RoadwayDAO.fetchAllByRoadwayNumbers should return something when fetching with four roadway numbers
  test("Test nodesAndJunctionsService.handleJunctionTemplates When creating one road that connects itself with the tail point of one link being discontinuous Then junction template and junctions points should be handled/created properly." +
    "Test nodesAndJunctionsService.expireObsoleteNodesAndJunctions When terminating one link that had junction point Then only the terminated junction point should be expired, not all of them, neither his Junction.") {
    runWithRollback {
      /*  |--L-->|
                 |
                 C1
                 |
                 V
          |-R*->0|
                 |
                 C2
                 |
                 v

           Notes:
            0: Illustration where junction points should be created
            C: Combined track
            L: Left track
            R: Right Track
            R*: Discontinuous Right track
       */
      val road = 999L
      val part = 1L
      val projectId1 = Sequences.nextViiteProjectId
      val projectId2 = Sequences.nextViiteProjectId
      val rwId1 = Sequences.nextRoadwayId
      val rwId2 = Sequences.nextRoadwayId
      val rwId3 = Sequences.nextRoadwayId
      val rwId4 = Sequences.nextRoadwayId
      val rwId5 = Sequences.nextRoadwayId
      val llId1 = Sequences.nextLinearLocationId
      val llId2 = Sequences.nextLinearLocationId
      val llId3 = Sequences.nextLinearLocationId
      val llId4 = Sequences.nextLinearLocationId
//    val llId5 = Sequences.nextLinearLocationId
//    val llId6 = Sequences.nextLinearLocationId
      val llId7 = Sequences.nextLinearLocationId
      val llId8 = Sequences.nextLinearLocationId
      val rwNumber1 = Sequences.nextRoadwayNumber
      val rwNumber2 = Sequences.nextRoadwayNumber
      val rwNumber3 = Sequences.nextRoadwayNumber
      val rwNumber4 = Sequences.nextRoadwayNumber
      val rwNumber5 = Sequences.nextRoadwayNumber
      val plId1 = Sequences.nextProjectLinkId
      val plId2 = Sequences.nextProjectLinkId
      val plId3 = Sequences.nextProjectLinkId
      val plId4 = Sequences.nextProjectLinkId
      val plId5 = Sequences.nextProjectLinkId
      val plId6 = Sequences.nextProjectLinkId
      val plId7 = Sequences.nextProjectLinkId
      val plId8 = Sequences.nextProjectLinkId

      val leftGeom1 = Seq(Point(0.0, 50.0), Point(5.0, 50.0))
      val rightGeom1 = Seq(Point(0.0, 40.0), Point(5.0, 40.0))
      val combGeom1 = Seq(Point(5.0, 50.0), Point(5.0, 40.0))
      val combGeom2 = Seq(Point(5.0, 40.0), Point(5.0, 35.0))

      val leftLink1  = dummyProjectLink(RoadPart(road, part), Track.LeftSide,  Discontinuity.Continuous,   AddrMRange( 0,  5), AddrMRange( 0,  5), Some(DateTime.now()), None, 12345.toString, 0,  5, SideCode.TowardsDigitizing, RoadAddressChangeType.Transfer, projectId1, AdministrativeClass.State, leftGeom1,  rwNumber1).copy(id = plId1, projectId = projectId1, roadwayId = rwId1, linearLocationId = llId1)
      val rightLink1 = dummyProjectLink(RoadPart(road, part), Track.RightSide, Discontinuity.Discontinuous,AddrMRange( 0,  5), AddrMRange( 0,  5), Some(DateTime.now()), None, 12346.toString, 0,  5, SideCode.TowardsDigitizing, RoadAddressChangeType.Transfer, projectId1, AdministrativeClass.State, rightGeom1, rwNumber2).copy(id = plId2, projectId = projectId1, roadwayId = rwId2, linearLocationId = llId2)
      val combLink1  = dummyProjectLink(RoadPart(road, part), Track.Combined,  Discontinuity.Continuous,   AddrMRange( 5, 15), AddrMRange( 5, 15), Some(DateTime.now()), None, 12347.toString, 0, 10, SideCode.TowardsDigitizing, RoadAddressChangeType.Transfer, projectId1, AdministrativeClass.State, combGeom1,  rwNumber3).copy(id = plId3, projectId = projectId1, roadwayId = rwId3, linearLocationId = llId3)
      val combLink2  = dummyProjectLink(RoadPart(road, part), Track.Combined,  Discontinuity.EndOfRoad,    AddrMRange(15, 20), AddrMRange(15, 20), Some(DateTime.now()), None, 12348.toString, 0,  5, SideCode.TowardsDigitizing, RoadAddressChangeType.Transfer, projectId1, AdministrativeClass.State, combGeom2,  rwNumber3).copy(id = plId4, projectId = projectId1, roadwayId = rwId3, linearLocationId = llId4)

      val project = Project(projectId1, ProjectState.Incomplete, "f", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
        "", Seq(), Seq(), None, None)

      val leftPLinks = Seq(leftLink1)
      val rightPLinks = Seq(rightLink1)
      val combPLinks = Seq(combLink1, combLink2)

      val (lll1, rw1): (LinearLocation, Roadway) = Seq(leftLink1).map(toRoadwayAndLinearLocation).head
      val (rll1, rw2): (LinearLocation, Roadway) = Seq(rightLink1).map(toRoadwayAndLinearLocation).head
      val (cll1, rw3): (LinearLocation, Roadway) = Seq(combLink1).map(toRoadwayAndLinearLocation).head
      val (cll2, _): (LinearLocation, Roadway) = Seq(combLink2).map(toRoadwayAndLinearLocation).head
      val rw1WithId = rw1.copy(addrMRange = AddrMRange(0, 5), ely = 8L)
      val rw2WithId = rw2.copy(addrMRange = AddrMRange(0, 5), ely = 8L)
      val rw3WithId = rw3.copy(addrMRange = AddrMRange(5,20), ely = 8L)

      val orderedcll1 = cll1.copy(orderNumber = 1)
      val orderedcll2 = cll2.copy(orderNumber = 2)

      buildTestDataForProject(Some(project), Some(Seq(rw1WithId, rw2WithId, rw3WithId)), Some(Seq(lll1, rll1, orderedcll1, orderedcll2)), Some(leftPLinks ++ rightPLinks ++ combPLinks))

      val projectChanges = List(
        //  left
        ProjectRoadwayChange(projectId1, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(RoadAddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, Some(AdministrativeClass.State), Some(Discontinuity.Continuous), Some(8L)),
            RoadwayChangeSection(Some(road), Some(Track.LeftSide.value.toLong), startRoadPartNumber = Some(part), endRoadPartNumber = Some(part), Some(AddrMRange(0L,5L)), Some(AdministrativeClass.State), Some(Discontinuity.Continuous), Some(8L)),
            Discontinuity.Continuous, AdministrativeClass.State, reversed = false, 1, 8)
          , DateTime.now),
        //  right
        ProjectRoadwayChange(projectId1, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(RoadAddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, Some(AdministrativeClass.State), Some(Discontinuity.Continuous), Some(8L)),
            RoadwayChangeSection(Some(road), Some(Track.RightSide.value.toLong), startRoadPartNumber = Some(part), endRoadPartNumber = Some(part), Some(AddrMRange(0L,5L)), Some(AdministrativeClass.State), Some(Discontinuity.Discontinuous), Some(8L)),
            Discontinuity.Discontinuous, AdministrativeClass.State, reversed = false, 2, 8)
          , DateTime.now),
        //  combined
        ProjectRoadwayChange(projectId1, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(RoadAddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, Some(AdministrativeClass.State), Some(Discontinuity.Continuous), Some(8L)),
            RoadwayChangeSection(Some(road), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(part), endRoadPartNumber = Some(part), Some(AddrMRange(5L,15L)), Some(AdministrativeClass.State), Some(Discontinuity.Continuous), Some(8L)),
            Discontinuity.Continuous, AdministrativeClass.State, reversed = false, 3, 8)
          , DateTime.now),
        ProjectRoadwayChange(projectId1, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(RoadAddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, Some(AdministrativeClass.State), Some(Discontinuity.Continuous), Some(8L)),
            RoadwayChangeSection(Some(road), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(part), endRoadPartNumber = Some(part), Some(AddrMRange(15L,20L)), Some(AdministrativeClass.State), Some(Discontinuity.EndOfRoad), Some(8L)),
            Discontinuity.EndOfRoad, AdministrativeClass.State, reversed = false, 4, 8)
          , DateTime.now)
      )

      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(leftGeom1.head, leftGeom1.head), roadNumberLimits)).thenReturn(Seq())
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(Set.empty[Long], withHistory=false)).thenReturn(Seq())

      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(leftGeom1.last, leftGeom1.last), roadNumberLimits)).thenReturn(Seq(lll1, orderedcll1, orderedcll2))
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(Set(lll1.roadwayNumber, orderedcll1.roadwayNumber), withHistory=false)).thenReturn(Seq(rw1WithId, rw3WithId))

      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(rightGeom1.head, rightGeom1.head), roadNumberLimits)).thenReturn(Seq())
      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(rightGeom1.last, rightGeom1.last), roadNumberLimits)).thenReturn(Seq(rll1, orderedcll1, orderedcll2))
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(Set(rll1.roadwayNumber, orderedcll1.roadwayNumber), withHistory=false)).thenReturn(Seq(rw2WithId, rw3WithId))

      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(combGeom1.head, combGeom1.head), roadNumberLimits)).thenReturn(Seq(orderedcll1, orderedcll2, lll1))
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(Set(lll1.roadwayNumber, orderedcll1.roadwayNumber), withHistory=false)).thenReturn(Seq(rw1WithId, rw3WithId))
      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(combGeom1.last, combGeom1.last), roadNumberLimits)).thenReturn(Seq(rll1, orderedcll1, orderedcll2))
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(Set(rll1.roadwayNumber, orderedcll1.roadwayNumber), withHistory=false)).thenReturn(Seq(rw2WithId, rw3WithId))
      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(combGeom2.head, combGeom2.head), roadNumberLimits)).thenReturn(Seq(rll1, orderedcll1, orderedcll2))
      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(combGeom2.last, combGeom2.last), roadNumberLimits)).thenReturn(Seq())

      val mappedReservedRoadwayNumbers = projectLinkDAO.fetchProjectLinksChange(projectId1)
      roadAddressService.handleRoadwayPointsUpdate(projectChanges, mappedReservedRoadwayNumbers)
      nodesAndJunctionsService.handleNodePoints(projectChanges, leftPLinks ++ rightPLinks ++ combPLinks, mappedReservedRoadwayNumbers)
      nodesAndJunctionsService.handleJunctionAndJunctionPoints(projectChanges, leftPLinks ++ rightPLinks ++ combPLinks, mappedReservedRoadwayNumbers)

      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(Set(lll1.roadwayNumber, rll1.roadwayNumber, orderedcll1.roadwayNumber), withHistory=false)).thenReturn(Seq(rw1WithId, rw2WithId, rw3WithId))
      nodesAndJunctionsService.expireObsoleteNodesAndJunctions(leftPLinks ++ rightPLinks ++ combPLinks, Some(project.startDate.minusDays(1)))

      val roadwayPoints = roadwayPointDAO.fetchByRoadwayNumbers((leftPLinks ++ rightPLinks ++ combPLinks).map(_.roadwayNumber)).map(_.id)
      val junctionPointTemplates = junctionPointDAO.fetchByRoadwayPointIds(roadwayPoints)
      val templateRoadwayNumbers = junctionPointTemplates.map(_.roadwayNumber).distinct
      val junctions = junctionDAO.fetchTemplatesByRoadwayNumbers(templateRoadwayNumbers)

      junctionPointTemplates.length should be(3)
      junctionPointTemplates.count(_.beforeAfter == BeforeAfter.Before) should be(2)
      junctionPointTemplates.count(_.beforeAfter == BeforeAfter.After) should be(1)
      junctions.size should be(1)

      /*  Preparing expiring data */
      val project2 = Project(projectId2, ProjectState.Incomplete, "ProjectTerminatedLinks", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
        "", Seq(), Seq(), None, None)
      val terminatingProjectChanges = List(
        //  left
        ProjectRoadwayChange(projectId1, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(RoadAddressChangeType.Unchanged,
            RoadwayChangeSection(Some(road), Some(Track.LeftSide.value.toLong), startRoadPartNumber = Some(part), endRoadPartNumber = Some(part), Some(AddrMRange(0L,5L)), Some(AdministrativeClass.State), Some(Discontinuity.Continuous), Some(8L)),
            RoadwayChangeSection(Some(road), Some(Track.LeftSide.value.toLong), startRoadPartNumber = Some(part), endRoadPartNumber = Some(part), Some(AddrMRange(0L,5L)), Some(AdministrativeClass.State), Some(Discontinuity.Continuous), Some(8L)),
            Discontinuity.Continuous, AdministrativeClass.State, reversed = false, 1, 8)
          , DateTime.now),
        // right
        ProjectRoadwayChange(projectId1, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(RoadAddressChangeType.Unchanged,
            RoadwayChangeSection(Some(road), Some(Track.RightSide.value.toLong), startRoadPartNumber = Some(part), endRoadPartNumber = Some(part), Some(AddrMRange(0L,5L)), Some(AdministrativeClass.State), Some(Discontinuity.Discontinuous), Some(8L)),
            RoadwayChangeSection(Some(road), Some(Track.RightSide.value.toLong), startRoadPartNumber = Some(part), endRoadPartNumber = Some(part), Some(AddrMRange(0L,5L)), Some(AdministrativeClass.State), Some(Discontinuity.Discontinuous), Some(8L)),
            Discontinuity.Discontinuous, AdministrativeClass.State, reversed = false, 2, 8)
          , DateTime.now),
        // combined
        ProjectRoadwayChange(projectId1, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(RoadAddressChangeType.Transfer,
            RoadwayChangeSection(Some(road), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(part), endRoadPartNumber = Some(part), Some(AddrMRange(5L,15L)), Some(AdministrativeClass.State), Some(Discontinuity.Continuous), Some(8L)),
            RoadwayChangeSection(Some(road), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(part), endRoadPartNumber = Some(part), Some(AddrMRange(5L,15L)), Some(AdministrativeClass.State), Some(Discontinuity.EndOfRoad), Some(8L)),
            Discontinuity.EndOfRoad, AdministrativeClass.State, reversed = false, 3, 8)
          , DateTime.now),
        ProjectRoadwayChange(projectId1, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(RoadAddressChangeType.Termination,
            RoadwayChangeSection(Some(road), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(part), endRoadPartNumber = Some(part), Some(AddrMRange(15L,20L)), Some(AdministrativeClass.State), Some(Discontinuity.EndOfRoad), Some(8L)),
            RoadwayChangeSection(None, None, None, None, None, Some(AdministrativeClass.State), Some(Discontinuity.Continuous), Some(8L)),
            Discontinuity.EndOfRoad, AdministrativeClass.State, reversed = false, 4, 8)
          , DateTime.now)
      )

      linearLocationDAO.expireByRoadwayNumbers(Set(combLink1.roadwayNumber, combLink2.roadwayNumber))
      roadwayDAO.expireHistory(Set(combLink1.roadwayId, combLink2.roadwayId))
      projectLinkDAO.moveProjectLinksToHistory(projectId1)
      val unchangedLeftLink1  = dummyProjectLink(RoadPart(road, part), Track.LeftSide,  Discontinuity.Continuous,   AddrMRange( 0,  5), AddrMRange( 0,  5), Some(DateTime.now()), None, 12345.toString, 0,  5, SideCode.TowardsDigitizing, RoadAddressChangeType.Unchanged,   projectId2, AdministrativeClass.State, leftGeom1,  rwNumber1).copy(id = plId5, roadwayId = rwId1, linearLocationId = llId1)
      val unchangedRightLink1 = dummyProjectLink(RoadPart(road, part), Track.RightSide, Discontinuity.Discontinuous,AddrMRange( 0,  5), AddrMRange( 0,  5), Some(DateTime.now()), None, 12346.toString, 0,  5, SideCode.TowardsDigitizing, RoadAddressChangeType.Unchanged,   projectId2, AdministrativeClass.State, rightGeom1, rwNumber2).copy(id = plId6, roadwayId = rwId2, linearLocationId = llId2)
      val transferCombLink1   = dummyProjectLink(RoadPart(road, part), Track.Combined,  Discontinuity.EndOfRoad,    AddrMRange( 5, 15), AddrMRange( 5, 15), Some(DateTime.now()), None, 12347.toString, 0, 10, SideCode.TowardsDigitizing, RoadAddressChangeType.Transfer,    projectId2, AdministrativeClass.State, combGeom1,  rwNumber4).copy(id = plId7, roadwayId = rwId4, linearLocationId = llId7)
      val terminatedCombLink2 = dummyProjectLink(RoadPart(road, part), Track.Combined,  Discontinuity.EndOfRoad,    AddrMRange(15, 20), AddrMRange(15, 20), Some(DateTime.now()), None, 12348.toString, 0,  5, SideCode.TowardsDigitizing, RoadAddressChangeType.Termination, projectId2, AdministrativeClass.State, combGeom2,  rwNumber5).copy(id = plId8, roadwayId = rwId5, linearLocationId = llId8)

      val rwTransfer   = rw3WithId.copy(id =   transferCombLink1.roadwayId, roadwayNumber =   transferCombLink1.roadwayNumber, addrMRange = AddrMRange(  transferCombLink1.addrMRange.start, 15))
      val rwTerminated = rw3WithId.copy(id = terminatedCombLink2.roadwayId, roadwayNumber = terminatedCombLink2.roadwayNumber, addrMRange = AddrMRange(terminatedCombLink2.addrMRange.start, 20))
      val transferLinearLocation = orderedcll1.copy(id = transferCombLink1.linearLocationId, roadwayNumber = transferCombLink1.roadwayNumber)
      val terminatedLinearLocation = orderedcll2.copy(id = terminatedCombLink2.linearLocationId, roadwayNumber = terminatedCombLink2.roadwayNumber)

      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(Set(unchangedLeftLink1.roadwayNumber, transferCombLink1.roadwayNumber), withHistory=false)).thenReturn(Seq(rw1WithId, rwTransfer))
      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(transferCombLink1.geometry.last, transferCombLink1.geometry.last), roadNumberLimits)).thenReturn(Seq(rll1, transferLinearLocation, terminatedLinearLocation))
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(Set(terminatedCombLink2.roadwayNumber), withHistory=false)).thenReturn(Seq(rwTerminated))
      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(unchangedRightLink1.geometry.last, transferCombLink1.geometry.last), roadNumberLimits)).thenReturn(Seq(rll1, transferLinearLocation))
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(Set(unchangedRightLink1.roadwayNumber, transferCombLink1.roadwayNumber), withHistory=false)).thenReturn(Seq(rw2WithId, rwTransfer))
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(Set(unchangedLeftLink1.roadwayNumber, unchangedRightLink1.roadwayNumber, transferCombLink1.roadwayNumber), withHistory=false)).thenReturn(Seq(rw1WithId, rw2WithId, rwTransfer))

      buildTestDataForProject(Some(project2),
        Some(Seq(rwTransfer, rwTerminated)),
        Some(Seq(
          transferLinearLocation,
          terminatedLinearLocation)),
        Some(Seq(unchangedLeftLink1, unchangedRightLink1, transferCombLink1, terminatedCombLink2)))

      val mappedAfterChanges: Seq[ProjectRoadLinkChange] = projectLinkDAO.fetchProjectLinksChange(projectId2)
        .map { l =>
          if (l.newRoadwayNumber == transferCombLink1.roadwayNumber) {
            l.copy(originalLinearLocationId = combLink1.linearLocationId, linearLocationId = transferCombLink1.linearLocationId, originalRoadwayNumber = combLink1.roadwayNumber)
          }
          else if (l.newRoadwayNumber == terminatedCombLink2.roadwayNumber) {
            l.copy(originalLinearLocationId = combLink2.linearLocationId, linearLocationId = terminatedCombLink2.linearLocationId, originalRoadwayNumber = combLink2.roadwayNumber)
          }
          else l
        }

      projectLinkDAO.moveProjectLinksToHistory(projectId2)
      roadAddressService.handleRoadwayPointsUpdate(terminatingProjectChanges, mappedAfterChanges)
      nodesAndJunctionsService.handleNodePoints(terminatingProjectChanges, Seq(leftLink1) ++ Seq(rightLink1) ++ Seq(transferCombLink1, terminatedCombLink2), mappedAfterChanges)
      nodesAndJunctionsService.handleJunctionAndJunctionPoints(terminatingProjectChanges, Seq(leftLink1) ++ Seq(rightLink1) ++ Seq(transferCombLink1, terminatedCombLink2), mappedAfterChanges)

      /*  Ending expiring data  */
      val endDate = Some(project2.startDate.minusDays(1))
      nodesAndJunctionsService.expireObsoleteNodesAndJunctions(Seq(unchangedLeftLink1, unchangedRightLink1, transferCombLink1, terminatedCombLink2), endDate)

      val junctionPointsAfterTermination: Seq[JunctionPoint] = junctionPointDAO.fetchByRoadwayPointIds(roadwayPoints)
      junctionPointsAfterTermination.length should be(2)

      val junctionsAfterExpire = junctionDAO.fetchByIds(junctionPointsAfterTermination.map(_.junctionId))
      junctionsAfterExpire.length should be(1)
    }
  }

  test("Test nodesAndJunctionsService.handleJunctionTemplates When creating two track Discontinuous links and one combined EndOfRoad that are not connected by geometry, only by address Then junction template and junctions points should not be created") {
    runWithRollback {
      /*
     |--L*-->|
                  |--C-->|
     |--R*-->|

            Note:
            0: Illustration where junction points should be created
            C: Combined track EndOfRoad
            L*: Discontinuous Left track
            R*: Discontinuous Right track
       */

      val road = 999L
      val part = 1L
      val projectId = Sequences.nextViiteProjectId
      val rwId1 = Sequences.nextRoadwayId
      val rwId2 = Sequences.nextRoadwayId
      val rwId3 = Sequences.nextRoadwayId
      val llId1 = Sequences.nextLinearLocationId
      val llId2 = Sequences.nextLinearLocationId
      val llId3 = Sequences.nextLinearLocationId
      val rwNumber1 = Sequences.nextRoadwayNumber
      val rwNumber2 = Sequences.nextRoadwayNumber
      val rwNumber3 = Sequences.nextRoadwayNumber
      val plId1 = Sequences.nextProjectLinkId
      val plId2 = Sequences.nextProjectLinkId
      val plId3 = Sequences.nextProjectLinkId

      val leftGeom1 = Seq(Point(0.0, 50.0), Point(5.0, 50.0))
      val rightGeom1 = Seq(Point(0.0, 40.0), Point(5.0, 40.0))
      val combGeom1 = Seq(Point(7.0, 45.0), Point(12.0, 45.0))

      val leftLink1  = dummyProjectLink(RoadPart(road, part), Track.LeftSide,  Discontinuity.MinorDiscontinuity, AddrMRange(0,  5), AddrMRange(0,  5), Some(DateTime.now()), None, 12345.toString, 0, 5, SideCode.TowardsDigitizing, RoadAddressChangeType.Transfer, projectId, AdministrativeClass.State, leftGeom1,  rwNumber1).copy(id = plId1, projectId = projectId, roadwayId = rwId1, linearLocationId = llId1)
      val rightLink1 = dummyProjectLink(RoadPart(road, part), Track.RightSide, Discontinuity.MinorDiscontinuity, AddrMRange(0,  5), AddrMRange(0,  5), Some(DateTime.now()), None, 12346.toString, 0, 5, SideCode.TowardsDigitizing, RoadAddressChangeType.Transfer, projectId, AdministrativeClass.State, rightGeom1, rwNumber2).copy(id = plId2, projectId = projectId, roadwayId = rwId2, linearLocationId = llId2)
      val combLink1  = dummyProjectLink(RoadPart(road, part), Track.Combined,  Discontinuity.EndOfRoad,          AddrMRange(5, 10), AddrMRange(5, 10), Some(DateTime.now()), None, 12347.toString, 0, 5, SideCode.TowardsDigitizing, RoadAddressChangeType.Transfer, projectId, AdministrativeClass.State, combGeom1,  rwNumber3).copy(id = plId3, projectId = projectId, roadwayId = rwId3, linearLocationId = llId3)

      val project = Project(projectId, ProjectState.Incomplete, "f", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
        "", Seq(), Seq(), None, None)

      val leftPLinks = Seq(leftLink1)
      val rightPLinks = Seq(rightLink1)
      val combPLinks = Seq(combLink1)

      val (lll1, rw1): (LinearLocation, Roadway) = Seq(leftLink1).map(toRoadwayAndLinearLocation).head
      val (rll1, rw2): (LinearLocation, Roadway) = Seq(rightLink1).map(toRoadwayAndLinearLocation).head
      val (cll1, rw3): (LinearLocation, Roadway) = Seq(combLink1).map(toRoadwayAndLinearLocation).head
      val rw1WithId = rw1.copy(addrMRange = AddrMRange(0,  5), ely = 8L)
      val rw2WithId = rw2.copy(addrMRange = AddrMRange(0,  5), ely = 8L)
      val rw3WithId = rw3.copy(addrMRange = AddrMRange(5, 10), ely = 8L)

      buildTestDataForProject(Some(project), Some(Seq(rw1WithId, rw2WithId, rw3WithId)), Some(Seq(lll1, rll1, cll1)), Some(leftPLinks ++ rightPLinks ++ combPLinks))

      val projectChanges = List(
        //left
        ProjectRoadwayChange(projectId, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(RoadAddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, Some(AdministrativeClass.State), Some(Discontinuity.Continuous), Some(8L)),
            RoadwayChangeSection(Some(road), Some(Track.LeftSide.value.toLong), startRoadPartNumber = Some(part), endRoadPartNumber = Some(part), addrMRange = Some(AddrMRange(0L,5L)), Some(AdministrativeClass.State), Some(Discontinuity.MinorDiscontinuity), Some(8L)),
            Discontinuity.MinorDiscontinuity, AdministrativeClass.State, reversed = false, 1, 8)
          , DateTime.now),
        //right
        ProjectRoadwayChange(projectId, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(RoadAddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, Some(AdministrativeClass.State), Some(Discontinuity.Continuous), Some(8L)),
            RoadwayChangeSection(Some(road), Some(Track.RightSide.value.toLong), startRoadPartNumber = Some(part), endRoadPartNumber = Some(part), addrMRange = Some(AddrMRange(0L,5L)), Some(AdministrativeClass.State), Some(Discontinuity.MinorDiscontinuity), Some(8L)),
            Discontinuity.MinorDiscontinuity, AdministrativeClass.State, reversed = false, 2, 8)
          , DateTime.now),
        //combined
        ProjectRoadwayChange(projectId, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(RoadAddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, Some(AdministrativeClass.State), Some(Discontinuity.Continuous), Some(8L)),
            RoadwayChangeSection(Some(road), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(part), endRoadPartNumber = Some(part), addrMRange = Some(AddrMRange(5L,10L)), Some(AdministrativeClass.State), Some(Discontinuity.EndOfRoad), Some(8L)),
            Discontinuity.EndOfRoad, AdministrativeClass.State, reversed = false, 3, 8)
          , DateTime.now)
      )

      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(leftGeom1.head, leftGeom1.head), roadNumberLimits)).thenReturn(Seq())
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(Set.empty[Long], withHistory=false)).thenReturn(Seq())

      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(leftGeom1.last, leftGeom1.last), roadNumberLimits)).thenReturn(Seq())
      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(rightGeom1.head, rightGeom1.head), roadNumberLimits)).thenReturn(Seq())
      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(rightGeom1.last, rightGeom1.last), roadNumberLimits)).thenReturn(Seq())
      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(combGeom1.head, combGeom1.head), roadNumberLimits)).thenReturn(Seq())
      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(combGeom1.last, combGeom1.last), roadNumberLimits)).thenReturn(Seq())

      val mappedReservedRoadwayNumbers = projectLinkDAO.fetchProjectLinksChange(projectId)
      roadAddressService.handleRoadwayPointsUpdate(projectChanges, mappedReservedRoadwayNumbers)
      nodesAndJunctionsService.handleJunctionAndJunctionPoints(projectChanges, leftPLinks ++ rightPLinks ++ combPLinks, mappedReservedRoadwayNumbers)

      val roadwayPoints = roadwayPointDAO.fetchByRoadwayNumbers((leftPLinks ++ rightPLinks ++ combPLinks).map(_.roadwayNumber)).map(_.id)
      val junctionPointTemplates = junctionPointDAO.fetchByRoadwayPointIds(roadwayPoints)
      junctionPointTemplates.length should be(0)
    }
  }

  test("Test nodesAndJunctionsService.handleJunctionTemplates When creating two track MinorDiscontinuous links and one combined MinorDiscontinuous and one combined EndOfRoad that are not connected by geometry, only by address Then junction template and junctions points should not be created") {
    runWithRollback {
      /*
     |--L*-->|
                  |--C1-->|   |--C2-->|
     |--R*-->|

            Note:
            0: Illustration where junction points should be created
            C1: Combined track Discontinuous
            C2: Combined track EndOfRoad
            L*: Discontinuous Left track
            R*: Discontinuous Right track
       */

      val road = 999L
      val part = 1L
      val projectId = Sequences.nextViiteProjectId
      val rwId1 = Sequences.nextRoadwayId
      val rwId2 = Sequences.nextRoadwayId
      val rwId3 = Sequences.nextRoadwayId
      val llId1 = Sequences.nextLinearLocationId
      val llId2 = Sequences.nextLinearLocationId
      val llId3 = Sequences.nextLinearLocationId
      val llId4 = Sequences.nextLinearLocationId
      val rwNumber1 = Sequences.nextRoadwayNumber
      val rwNumber2 = Sequences.nextRoadwayNumber
      val rwNumber3 = Sequences.nextRoadwayNumber
      val plId1 = Sequences.nextProjectLinkId
      val plId2 = Sequences.nextProjectLinkId
      val plId3 = Sequences.nextProjectLinkId
      val plId4 = Sequences.nextProjectLinkId


      val leftGeom1 = Seq(Point(0.0, 50.0), Point(5.0, 50.0))
      val rightGeom1 = Seq(Point(0.0, 40.0), Point(5.0, 40.0))
      val combGeom1 = Seq(Point(7.0, 45.0), Point(12.0, 45.0))
      val combGeom2 = Seq(Point(14.0, 45.0), Point(19.0, 45.0))

      val leftLink1  = dummyProjectLink(RoadPart(road, part), Track.LeftSide,  Discontinuity.MinorDiscontinuity,AddrMRange( 0,  5), AddrMRange( 0,  5), Some(DateTime.now()), None, 12345.toString, 0, 5, SideCode.TowardsDigitizing, RoadAddressChangeType.Transfer, projectId, AdministrativeClass.State, leftGeom1,  rwNumber1).copy(id = plId1, projectId = projectId, roadwayId = rwId1, linearLocationId = llId1)
      val rightLink1 = dummyProjectLink(RoadPart(road, part), Track.RightSide, Discontinuity.MinorDiscontinuity,AddrMRange( 0,  5), AddrMRange( 0,  5), Some(DateTime.now()), None, 12346.toString, 0, 5, SideCode.TowardsDigitizing, RoadAddressChangeType.Transfer, projectId, AdministrativeClass.State, rightGeom1, rwNumber2).copy(id = plId2, projectId = projectId, roadwayId = rwId2, linearLocationId = llId2)
      val combLink1  = dummyProjectLink(RoadPart(road, part), Track.Combined,  Discontinuity.MinorDiscontinuity,AddrMRange( 5, 10), AddrMRange( 5, 10), Some(DateTime.now()), None, 12347.toString, 0, 5, SideCode.TowardsDigitizing, RoadAddressChangeType.Transfer, projectId, AdministrativeClass.State, combGeom1,  rwNumber3).copy(id = plId3, projectId = projectId, roadwayId = rwId3, linearLocationId = llId3)
      val combLink2  = dummyProjectLink(RoadPart(road, part), Track.Combined,  Discontinuity.EndOfRoad,         AddrMRange(10, 15), AddrMRange(10, 15), Some(DateTime.now()), None, 12348.toString, 0, 5, SideCode.TowardsDigitizing, RoadAddressChangeType.Transfer, projectId, AdministrativeClass.State, combGeom2,  rwNumber3).copy(id = plId4, projectId = projectId, roadwayId = rwId3, linearLocationId = llId4)

      val project = Project(projectId, ProjectState.Incomplete, "f", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
        "", Seq(), Seq(), None, None)

      val leftPLinks = Seq(leftLink1)
      val rightPLinks = Seq(rightLink1)
      val combPLinks = Seq(combLink1, combLink2)

      val (lll1,   rw1  ): (LinearLocation, Roadway) = Seq(leftLink1).map(toRoadwayAndLinearLocation).head
      val (rll1,   rw2  ): (LinearLocation, Roadway) = Seq(rightLink1).map(toRoadwayAndLinearLocation).head
      val (cll1,   rw3  ): (LinearLocation, Roadway) = Seq(combLink1).map(toRoadwayAndLinearLocation).head
      val (cll2,_/*rw4*/): (LinearLocation, Roadway) = Seq(combLink2).map(toRoadwayAndLinearLocation).head
      val rw1WithId = rw1.copy(addrMRange = AddrMRange(0, 5), ely = 8L)
      val rw2WithId = rw2.copy(addrMRange = AddrMRange(0, 5), ely = 8L)
      val rw3WithId = rw3.copy(addrMRange = AddrMRange(5,15), ely = 8L)
      val orderedcll1 = cll1.copy(orderNumber = 1)
      val orderedcll2 = cll2.copy(orderNumber = 2)

      buildTestDataForProject(Some(project), Some(Seq(rw1WithId, rw2WithId, rw3WithId)), Some(Seq(lll1, rll1, orderedcll1, orderedcll2)), Some(leftPLinks ++ rightPLinks ++ combPLinks))

      val projectChanges = List(
        //left
        ProjectRoadwayChange(projectId, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(RoadAddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, Some(AdministrativeClass.State), Some(Discontinuity.MinorDiscontinuity), Some(8L)),
            RoadwayChangeSection(Some(road), Some(Track.LeftSide.value.toLong), startRoadPartNumber = Some(part), endRoadPartNumber = Some(part), addrMRange = Some(AddrMRange(0L,5L)), Some(AdministrativeClass.State), Some(Discontinuity.MinorDiscontinuity), Some(8L)),
            Discontinuity.MinorDiscontinuity, AdministrativeClass.State, reversed = false, 1, 8)
          , DateTime.now),
        //right
        ProjectRoadwayChange(projectId, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(RoadAddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, Some(AdministrativeClass.State), Some(Discontinuity.MinorDiscontinuity), Some(8L)),
            RoadwayChangeSection(Some(road), Some(Track.RightSide.value.toLong), startRoadPartNumber = Some(part), endRoadPartNumber = Some(part), addrMRange = Some(AddrMRange(0L,5L)), Some(AdministrativeClass.State), Some(Discontinuity.MinorDiscontinuity), Some(8L)),
            Discontinuity.MinorDiscontinuity, AdministrativeClass.State, reversed = false, 2, 8)
          , DateTime.now),
        //combined
        ProjectRoadwayChange(projectId, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(RoadAddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, Some(AdministrativeClass.State), Some(Discontinuity.MinorDiscontinuity), Some(8L)),
            RoadwayChangeSection(Some(road), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(part), endRoadPartNumber = Some(part), addrMRange = Some(AddrMRange(5L,10L)), Some(AdministrativeClass.State), Some(Discontinuity.MinorDiscontinuity), Some(8L)),
            Discontinuity.MinorDiscontinuity, AdministrativeClass.State, reversed = false, 3, 8)
          , DateTime.now),
        ProjectRoadwayChange(projectId, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(RoadAddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, Some(AdministrativeClass.State), Some(Discontinuity.Continuous), Some(8L)),
            RoadwayChangeSection(Some(road), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(part), endRoadPartNumber = Some(part), addrMRange = Some(AddrMRange(10L,15L)), Some(AdministrativeClass.State), Some(Discontinuity.EndOfRoad), Some(8L)),
            Discontinuity.EndOfRoad, AdministrativeClass.State, reversed = false, 4, 8)
          , DateTime.now)
      )

      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(leftGeom1.head, leftGeom1.head), roadNumberLimits)).thenReturn(Seq())
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(Set.empty[Long], withHistory=false)).thenReturn(Seq())

      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(leftGeom1.last, leftGeom1.last), roadNumberLimits)).thenReturn(Seq())
      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(rightGeom1.head, rightGeom1.head), roadNumberLimits)).thenReturn(Seq())
      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(rightGeom1.last, rightGeom1.last), roadNumberLimits)).thenReturn(Seq())
      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(combGeom1.head, combGeom1.head), roadNumberLimits)).thenReturn(Seq())
      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(combGeom1.last, combGeom1.last), roadNumberLimits)).thenReturn(Seq())
      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(combGeom2.head, combGeom2.head), roadNumberLimits)).thenReturn(Seq())
      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(combGeom2.last, combGeom2.last), roadNumberLimits)).thenReturn(Seq())

      val mappedReservedRoadwayNumbers = projectLinkDAO.fetchProjectLinksChange(projectId)
      roadAddressService.handleRoadwayPointsUpdate(projectChanges, mappedReservedRoadwayNumbers)
      nodesAndJunctionsService.handleJunctionAndJunctionPoints(projectChanges, leftPLinks ++ rightPLinks ++ combPLinks, mappedReservedRoadwayNumbers)

      val roadwayPoints = roadwayPointDAO.fetchByRoadwayNumbers((leftPLinks ++ rightPLinks ++ combPLinks).map(_.roadwayNumber)).map(_.id)
      val junctionPointTemplates = junctionPointDAO.fetchByRoadwayPointIds(roadwayPoints)
      junctionPointTemplates.length should be(0)
    }
  }
  test(s"Test nodesAndJunctionsService.handleJunctionsAndJunctionPoints When creating a loop road with a ramp (roadNumber in range of 20000 - 39000) then junction and junction points should be created. ") {
    loopRoadTest(25000)
  }

  test(s"Test nodesAndJunctionsService.handleJunctionsAndJunctionPoints When creating a loop road then junction and junction points should be created. ") {
    loopRoadTest(46001)
  }

  def loopRoadTest(roadNumber: Long):Unit = {
      runWithRollback {
        /*
        * |--C1-->|O|---------|
        *          ^          |
        *          |    C2    |
        *          |          |
        *          ------------
        * C1: Combined link 1
        * C2: looping link that ends on itself
        * 0: Illustration where junction points should be created
        *
        * */

        val roadPartNumber = 1L
        val roadwayNumber = Sequences.nextRoadwayNumber
        val roadwayId = Sequences.nextRoadwayId
        val llId1 = Sequences.nextLinearLocationId
        val llId2 = Sequences.nextLinearLocationId
        val plId1 = Sequences.nextProjectLinkId
        val plId2 = Sequences.nextProjectLinkId
        val projectId = Sequences.nextViiteProjectId
        val linkId1 = "12345"
        val linkId2 = "12346"

        val geom1 = Seq(Point(0.0, 30.0), Point(30.0, 30.0))
        val geom2 = Seq(Point(30.0, 30.0), Point(30.0, 30.0)) // NOTE: these are the starting and ending points, the link does make a loop like in the illustration above

        val normalProjectLink = dummyProjectLink(RoadPart(roadNumber, roadPartNumber), Track.Combined, Discontinuity.Continuous,AddrMRange( 0,  30), AddrMRange( 0,  30), Some(DateTime.now()), None, linkId1, 0, 30, SideCode.TowardsDigitizing, RoadAddressChangeType.New, projectId, AdministrativeClass.State, geom1, roadwayNumber).copy(id = plId1, projectId = projectId, roadwayId = roadwayId, linearLocationId = llId1)
        val loopProjectLink   = dummyProjectLink(RoadPart(roadNumber, roadPartNumber), Track.Combined, Discontinuity.EndOfRoad, AddrMRange(30, 120), AddrMRange(30, 120), Some(DateTime.now()), None, linkId2, 0, 120, SideCode.TowardsDigitizing, RoadAddressChangeType.New, projectId, AdministrativeClass.State, geom2, roadwayNumber).copy(id = plId2, projectId = projectId, roadwayId = roadwayId, linearLocationId = llId2)
        val projectLinks = Seq(normalProjectLink, loopProjectLink)

        roadwayDAO.create(Seq(Roadway(roadwayId, roadwayNumber, RoadPart(roadNumber, roadPartNumber), AdministrativeClass.State, Track.Combined, Discontinuity.EndOfRoad, AddrMRange(0L, 120L), false, DateTime.now, None, "test", Some("test"), 14L)))
        val roadway = roadwayDAO.fetchByRoadwayNumber(roadwayNumber).get

        linearLocationDAO.create(
          Seq(
            LinearLocation(llId1, 1.0, linkId1, 0.0, 30.0, SideCode.TowardsDigitizing, 1691387623L, (CalibrationPointReference(None, None), CalibrationPointReference(None, None)), geom1, LinkGeomSource.NormalLinkInterface, roadwayNumber, Some(DateTime.now), None),
            LinearLocation(llId2, 2.0, linkId2, 0.0, 90.0, SideCode.TowardsDigitizing, 1691387623L, (CalibrationPointReference(None, None), CalibrationPointReference(None, None)), geom2, LinkGeomSource.NormalLinkInterface, roadwayNumber, Some(DateTime.now), None)
          )
        )
        val linearLocations = linearLocationDAO.fetchByIdMassQuery(Seq(llId1, llId2))

        roadwayPointDAO.create(roadwayNumber, 30, "test")
        roadwayPointDAO.create(roadwayNumber, 120, "test")

        val roadwayChanges = Seq(
          ProjectRoadwayChange(projectId, Some("test prject"), 14L, "test", DateTime.now,
            RoadwayChangeInfo(RoadAddressChangeType.New,
              RoadwayChangeSection(None, None, None, None, None, Some(AdministrativeClass.State), Some(Discontinuity.EndOfRoad), Some(14)),
              RoadwayChangeSection(Some(46001), Some(0), Some(1), Some(1), Some(AddrMRange(0, 106)), Some(AdministrativeClass.State), Some(Discontinuity.EndOfRoad), Some(14)),
              Discontinuity.EndOfRoad, AdministrativeClass.State, false, 1010664L, 14L),
            DateTime.now)).toList

        val mappedRoadwayNumbers = Seq(
          ProjectRoadLinkChange(plId1, 0, 0, llId1, RoadPart(0, 0), RoadPart(46001, 1), AddrMRange(0, 0), AddrMRange( 0,  30), RoadAddressChangeType.New, false, 0, roadwayNumber),
          ProjectRoadLinkChange(plId2, 0, 0, llId2, RoadPart(0, 0), RoadPart(46001, 1), AddrMRange(0, 0), AddrMRange(30, 120), RoadAddressChangeType.New, false, 0, roadwayNumber)
        )

        when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(geom1.head, geom1.head), roadNumberLimits)).thenReturn(linearLocations)
        when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(geom1.last, geom1.last), roadNumberLimits)).thenReturn(linearLocations)
        when(mockRoadwayDAO.fetchAllByRoadwayNumbers(Set(roadwayNumber))).thenReturn(Seq(roadway))

        nodesAndJunctionsService.handleJunctionAndJunctionPoints(roadwayChanges, projectLinks, mappedRoadwayNumbers)

        val roadwayPointIds = roadwayPointDAO.fetchByRoadwayNumber(roadwayNumber).map(_.id)

        val junctionPointTemplates = junctionPointDAO.fetchByRoadwayPointIds(roadwayPointIds)
        val junctionIds = junctionPointTemplates.map(jp => jp.junctionId).toSet

        junctionPointTemplates.size should be(3)
        junctionIds.size should be (1)

        val (junctionPointsAtMiddleOfTheRoad, junctionPointsAtTheEndOfRoad) = junctionPointTemplates.partition(jp => jp.addrM == 30)

        junctionPointsAtMiddleOfTheRoad.size should be(2)
        junctionPointsAtTheEndOfRoad.size should be(1)
        junctionPointsAtTheEndOfRoad.head.addrM should be(120)
      }
    }

  test("Test nodesAndJunctionsService.handleJunctionTemplates When creating two track Discontinuous links and one combined Continuous and one combined EndOfRoad that are not connected by geometry, only by address Then junction template and junctions points should not be created.") {
    runWithRollback {
      /*
     |--L*-->|
                  |--C1-->|--C2-->|
     |--R*-->|

            Note:
            0: Illustration where junction points should be created
            C1: Combined track Continuous
            C2: Combined track EndOfRoad
            L*: Discontinuous Left track
            R*: Discontinuous Right track
       */

      val road = 999L
      val part = 1L
      val projectId = Sequences.nextViiteProjectId
      val rwId1 = Sequences.nextRoadwayId
      val rwId2 = Sequences.nextRoadwayId
      val rwId3 = Sequences.nextRoadwayId
      val llId1 = Sequences.nextLinearLocationId
      val llId2 = Sequences.nextLinearLocationId
      val llId3 = Sequences.nextLinearLocationId
      val llId4 = Sequences.nextLinearLocationId
      val rwNumber1 = Sequences.nextRoadwayNumber
      val rwNumber2 = Sequences.nextRoadwayNumber
      val rwNumber3 = Sequences.nextRoadwayNumber
      val plId1 = Sequences.nextProjectLinkId
      val plId2 = Sequences.nextProjectLinkId
      val plId3 = Sequences.nextProjectLinkId
      val plId4 = Sequences.nextProjectLinkId

      val leftGeom1 = Seq(Point(0.0, 50.0), Point(5.0, 50.0))
      val rightGeom1 = Seq(Point(0.0, 40.0), Point(5.0, 40.0))
      val combGeom1 = Seq(Point(7.0, 45.0), Point(12.0, 45.0))
      val combGeom2 = Seq(Point(12.0, 45.0), Point(17.0, 45.0))

      val leftLink1  = dummyProjectLink(RoadPart(road, part), Track.LeftSide,  Discontinuity.MinorDiscontinuity,AddrMRange( 0,  5), AddrMRange( 0,  5), Some(DateTime.now()), None, 12345.toString, 0, 5, SideCode.TowardsDigitizing, RoadAddressChangeType.Transfer, projectId, AdministrativeClass.State, leftGeom1,  rwNumber1).copy(id = plId1, projectId = projectId, roadwayId = rwId1, linearLocationId = llId1)
      val rightLink1 = dummyProjectLink(RoadPart(road, part), Track.RightSide, Discontinuity.MinorDiscontinuity,AddrMRange( 0,  5), AddrMRange( 0,  5), Some(DateTime.now()), None, 12346.toString, 0, 5, SideCode.TowardsDigitizing, RoadAddressChangeType.Transfer, projectId, AdministrativeClass.State, rightGeom1, rwNumber2).copy(id = plId2, projectId = projectId, roadwayId = rwId2, linearLocationId = llId2)
      val combLink1  = dummyProjectLink(RoadPart(road, part), Track.Combined,  Discontinuity.Continuous,        AddrMRange( 5, 10), AddrMRange( 5, 10), Some(DateTime.now()), None, 12347.toString, 0, 5, SideCode.TowardsDigitizing, RoadAddressChangeType.Transfer, projectId, AdministrativeClass.State, combGeom1,  rwNumber3).copy(id = plId3, projectId = projectId, roadwayId = rwId3, linearLocationId = llId3)
      val combLink2  = dummyProjectLink(RoadPart(road, part), Track.Combined,  Discontinuity.EndOfRoad,         AddrMRange(10, 15), AddrMRange(10, 15), Some(DateTime.now()), None, 12348.toString, 0, 5, SideCode.TowardsDigitizing, RoadAddressChangeType.Transfer, projectId, AdministrativeClass.State, combGeom2,  rwNumber3).copy(id = plId4, projectId = projectId, roadwayId = rwId3, linearLocationId = llId4)

      val project = Project(projectId, ProjectState.Incomplete, "f", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
        "", Seq(), Seq(), None, None)

      val leftPLinks = Seq(leftLink1)
      val rightPLinks = Seq(rightLink1)
      val combPLinks = Seq(combLink1, combLink2)

      val (lll1,   rw1  ): (LinearLocation, Roadway) = Seq(leftLink1).map(toRoadwayAndLinearLocation).head
      val (rll1,   rw2  ): (LinearLocation, Roadway) = Seq(rightLink1).map(toRoadwayAndLinearLocation).head
      val (cll1,   rw3  ): (LinearLocation, Roadway) = Seq(combLink1).map(toRoadwayAndLinearLocation).head
      val (cll2,_/*rw4*/): (LinearLocation, Roadway) = Seq(combLink2).map(toRoadwayAndLinearLocation).head
      val rw1WithId = rw1.copy(addrMRange = AddrMRange(0, 5), ely = 8L)
      val rw2WithId = rw2.copy(addrMRange = AddrMRange(0, 5), ely = 8L)
      val rw3WithId = rw3.copy(addrMRange = AddrMRange(5,15), ely = 8L)
      val orderedcll1 = cll1.copy(orderNumber = 1)
      val orderedcll2 = cll2.copy(orderNumber = 2)

      buildTestDataForProject(Some(project), Some(Seq(rw1WithId, rw2WithId, rw3WithId)), Some(Seq(lll1, rll1, orderedcll1, orderedcll2)), Some(leftPLinks ++ rightPLinks ++ combPLinks))

      val projectChanges = List(
        //left
        ProjectRoadwayChange(projectId, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(RoadAddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, Some(AdministrativeClass.State), Some(Discontinuity.MinorDiscontinuity), Some(8L)),
            RoadwayChangeSection(Some(road), Some(Track.LeftSide.value.toLong), startRoadPartNumber = Some(part), endRoadPartNumber = Some(part), Some(AddrMRange(0L,5L)), Some(AdministrativeClass.State), Some(Discontinuity.MinorDiscontinuity), Some(8L)),
            Discontinuity.MinorDiscontinuity, AdministrativeClass.State, reversed = false, 1, 8)
          , DateTime.now),
        //right
        ProjectRoadwayChange(projectId, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(RoadAddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, Some(AdministrativeClass.State), Some(Discontinuity.MinorDiscontinuity), Some(8L)),
            RoadwayChangeSection(Some(road), Some(Track.RightSide.value.toLong), startRoadPartNumber = Some(part), endRoadPartNumber = Some(part), Some(AddrMRange(0L,5L)), Some(AdministrativeClass.State), Some(Discontinuity.MinorDiscontinuity), Some(8L)),
            Discontinuity.MinorDiscontinuity, AdministrativeClass.State, reversed = false, 2, 8)
          , DateTime.now),
        //combined
        ProjectRoadwayChange(projectId, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(RoadAddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, Some(AdministrativeClass.State), Some(Discontinuity.Continuous), Some(8L)),
            RoadwayChangeSection(Some(road), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(part), endRoadPartNumber = Some(part), Some(AddrMRange(5L,15L)), Some(AdministrativeClass.State), Some(Discontinuity.Continuous), Some(8L)),
            Discontinuity.Continuous, AdministrativeClass.State, reversed = false, 3, 8)
          , DateTime.now),
        ProjectRoadwayChange(projectId, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(RoadAddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, Some(AdministrativeClass.State), Some(Discontinuity.EndOfRoad), Some(8L)),
            RoadwayChangeSection(Some(road), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(part), endRoadPartNumber = Some(part), Some(AddrMRange(10L,15L)), Some(AdministrativeClass.State), Some(Discontinuity.EndOfRoad), Some(8L)),
            Discontinuity.EndOfRoad, AdministrativeClass.State, reversed = false, 4, 8)
          , DateTime.now)
      )

      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(leftGeom1.head, leftGeom1.head), roadNumberLimits)).thenReturn(Seq())
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(Set.empty[Long], withHistory=false)).thenReturn(Seq())

      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(leftGeom1.last, leftGeom1.last), roadNumberLimits)).thenReturn(Seq())
      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(rightGeom1.head, rightGeom1.head), roadNumberLimits)).thenReturn(Seq())
      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(rightGeom1.last, rightGeom1.last), roadNumberLimits)).thenReturn(Seq())
      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(combGeom1.head, combGeom1.head), roadNumberLimits)).thenReturn(Seq())
      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(combGeom1.last, combGeom1.last), roadNumberLimits)).thenReturn(Seq(orderedcll1, orderedcll2))
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(Set(orderedcll1.roadwayNumber, orderedcll2.roadwayNumber), withHistory=false)).thenReturn(Seq(rw3WithId))
      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(combGeom2.last, combGeom2.last), roadNumberLimits)).thenReturn(Seq())

      val mappedReservedRoadwayNumbers = projectLinkDAO.fetchProjectLinksChange(projectId)
      roadAddressService.handleRoadwayPointsUpdate(projectChanges, mappedReservedRoadwayNumbers)
      nodesAndJunctionsService.handleJunctionAndJunctionPoints(projectChanges, leftPLinks ++ rightPLinks ++ combPLinks, mappedReservedRoadwayNumbers)

      val roadwayPoints = roadwayPointDAO.fetchByRoadwayNumbers((leftPLinks ++ rightPLinks ++ combPLinks).map(_.roadwayNumber)).map(_.id)
      val junctionPointTemplates = junctionPointDAO.fetchByRoadwayPointIds(roadwayPoints)
      junctionPointTemplates.length should be(0)
    }
  }

  test("Test nodesAndJunctionsService.handleJunctionTemplates When creating two track where one of them is Discontinuous and the other is not Then one junction and its junctions points should be created.") {
    runWithRollback {
      /*
             ^
             | C2
     |--L*-->|
             | C1
     |--R*-->|

            Note:
            0: Illustration where junction points should be created
            C1: Combined track Continuous
            C2: Combined track EndOfRoad
            L*: Discontinuous Left track
            R: Continuous Right track
       */

      val road = 999L
      val part = 1L
      val projectId = Sequences.nextViiteProjectId
      val rwId = Sequences.nextRoadwayId
      val llId = Sequences.nextLinearLocationId
      val rwNumber = Sequences.nextRoadwayNumber
      val plId = Sequences.nextProjectLinkId


      val leftGeom1 = Seq(Point(0.0, 50.0), Point(5.0, 50.0))
      val rightGeom1 = Seq(Point(0.0, 40.0), Point(5.0, 40.0))
      val combGeom1 = Seq(Point(5.0, 40), Point(5.0, 50.0))
      val combGeom2 = Seq(Point(5.0, 50.0), Point(5.0, 55.0))

      val leftLink1  = dummyProjectLink(RoadPart(road, part), Track.LeftSide,  Discontinuity.MinorDiscontinuity,AddrMRange( 0,  5), AddrMRange( 0,  5), Some(DateTime.now()), None, 12345.toString, 0,  5, SideCode.TowardsDigitizing, RoadAddressChangeType.New, projectId, AdministrativeClass.State, leftGeom1,  rwNumber    ).copy(id = plId,     projectId = projectId, roadwayId = rwId,     linearLocationId = llId)
      val rightLink1 = dummyProjectLink(RoadPart(road, part), Track.RightSide, Discontinuity.MinorDiscontinuity,AddrMRange( 0,  5), AddrMRange( 0,  5), Some(DateTime.now()), None, 12346.toString, 0,  5, SideCode.TowardsDigitizing, RoadAddressChangeType.New, projectId, AdministrativeClass.State, rightGeom1, rwNumber + 1).copy(id = plId + 1, projectId = projectId, roadwayId = rwId + 1, linearLocationId = llId + 1)
      val combLink1  = dummyProjectLink(RoadPart(road, part), Track.Combined,  Discontinuity.Continuous,        AddrMRange( 5, 15), AddrMRange( 5, 15), Some(DateTime.now()), None, 12347.toString, 0, 10, SideCode.TowardsDigitizing, RoadAddressChangeType.New, projectId, AdministrativeClass.State, combGeom1,  rwNumber + 2).copy(id = plId + 2, projectId = projectId, roadwayId = rwId + 2, linearLocationId = llId + 2)
      val combLink2  = dummyProjectLink(RoadPart(road, part), Track.Combined,  Discontinuity.EndOfRoad,         AddrMRange(15, 25), AddrMRange(15, 25), Some(DateTime.now()), None, 12348.toString, 0, 10, SideCode.TowardsDigitizing, RoadAddressChangeType.New, projectId, AdministrativeClass.State, combGeom2,  rwNumber + 2).copy(id = plId + 3, projectId = projectId, roadwayId = rwId + 2, linearLocationId = llId + 3)

      val project = Project(projectId, ProjectState.Incomplete, "f", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
        "", Seq(), Seq(), None, None)

      val leftPLinks = Seq(leftLink1)
      val rightPLinks = Seq(rightLink1)
      val combPLinks = Seq(combLink1, combLink2)

      val (lll1,   rw1  ): (LinearLocation, Roadway) = Seq(leftLink1).map(toRoadwayAndLinearLocation).head
      val (rll1,   rw2  ): (LinearLocation, Roadway) = Seq(rightLink1).map(toRoadwayAndLinearLocation).head
      val (cll1,   rw3  ): (LinearLocation, Roadway) = Seq(combLink1).map(toRoadwayAndLinearLocation).head
      val (cll2,_/*rw4*/): (LinearLocation, Roadway) = Seq(combLink2).map(toRoadwayAndLinearLocation).head
      val rw1WithId = rw1.copy(addrMRange = AddrMRange(0, 5), ely = 8L)
      val rw2WithId = rw2.copy(addrMRange = AddrMRange(0, 5), ely = 8L)
      val rw3WithId = rw3.copy(addrMRange = AddrMRange(5,25), ely = 8L)
      val orderedcll1 = cll1.copy(orderNumber = 1)
      val orderedcll2 = cll2.copy(orderNumber = 2)

      buildTestDataForProject(Some(project), Some(Seq(rw1WithId, rw2WithId, rw3WithId)), Some(Seq(lll1, rll1, orderedcll1, orderedcll2)), Some(leftPLinks ++ rightPLinks ++ combPLinks))

      val projectChanges = List(
        //left
        ProjectRoadwayChange(projectId, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(RoadAddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, Some(AdministrativeClass.State), Some(Discontinuity.Discontinuous), Some(8L)),
            RoadwayChangeSection(Some(road), Some(Track.LeftSide.value.toLong), startRoadPartNumber = Some(part), endRoadPartNumber = Some(part), addrMRange = Some(AddrMRange(0L,5L)), Some(AdministrativeClass.State), Some(Discontinuity.Discontinuous), Some(8L)),
            Discontinuity.Discontinuous, AdministrativeClass.State, reversed = false, 1, 8)
          , DateTime.now),
        //right
        ProjectRoadwayChange(projectId, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(RoadAddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, Some(AdministrativeClass.State), Some(Discontinuity.Continuous), Some(8L)),
            RoadwayChangeSection(Some(road), Some(Track.RightSide.value.toLong), startRoadPartNumber = Some(part), endRoadPartNumber = Some(part), addrMRange = Some(AddrMRange(0L,5L)), Some(AdministrativeClass.State), Some(Discontinuity.Continuous), Some(8L)),
            Discontinuity.Continuous, AdministrativeClass.State, reversed = false, 2, 8)
          , DateTime.now),
        //combined
        ProjectRoadwayChange(projectId, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(RoadAddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, Some(AdministrativeClass.State), Some(Discontinuity.Continuous), Some(8L)),
            RoadwayChangeSection(Some(road), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(part), endRoadPartNumber = Some(part), addrMRange = Some(AddrMRange(5L,15L)), Some(AdministrativeClass.State), Some(Discontinuity.Continuous), Some(8L)),
            Discontinuity.Continuous, AdministrativeClass.State, reversed = false, 3, 8)
          , DateTime.now),
        ProjectRoadwayChange(projectId, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(RoadAddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, Some(AdministrativeClass.State), Some(Discontinuity.EndOfRoad), Some(8L)),
            RoadwayChangeSection(Some(road), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(part), endRoadPartNumber = Some(part), addrMRange = Some(AddrMRange(15L,25L)), Some(AdministrativeClass.State), Some(Discontinuity.EndOfRoad), Some(8L)),
            Discontinuity.EndOfRoad, AdministrativeClass.State, reversed = false, 4, 8)
          , DateTime.now)
      )

      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(leftGeom1.head, leftGeom1.head), roadNumberLimits)).thenReturn(Seq())
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(Set.empty[Long], withHistory=false)).thenReturn(Seq())

      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(leftGeom1.last, leftGeom1.last), roadNumberLimits)).thenReturn(Seq())
      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(rightGeom1.head, rightGeom1.head), roadNumberLimits)).thenReturn(Seq())
      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(rightGeom1.last, rightGeom1.last), roadNumberLimits)).thenReturn(Seq())
      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(combGeom1.head, combGeom1.head), roadNumberLimits)).thenReturn(Seq())
      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(combGeom1.last, combGeom1.last), roadNumberLimits)).thenReturn(Seq(orderedcll1, orderedcll2))
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(Set(orderedcll1.roadwayNumber, orderedcll2.roadwayNumber), withHistory=false)).thenReturn(Seq(rw3WithId))
      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(combGeom2.last, combGeom2.last), roadNumberLimits)).thenReturn(Seq())

      val mappedReservedRoadwayNumbers = projectLinkDAO.fetchProjectLinksChange(projectId)
      roadAddressService.handleRoadwayPointsUpdate(projectChanges, mappedReservedRoadwayNumbers)
      nodesAndJunctionsService.handleJunctionAndJunctionPoints(projectChanges, leftPLinks ++ rightPLinks ++ combPLinks, mappedReservedRoadwayNumbers)
      nodesAndJunctionsService.expireObsoleteNodesAndJunctions(leftPLinks ++ rightPLinks ++ combPLinks, Some(project.startDate.minusDays(1)))

      val roadwayPoints = roadwayPointDAO.fetchByRoadwayNumbers((leftPLinks ++ rightPLinks ++ combPLinks).map(_.roadwayNumber)).map(_.id)
      val junctionPointTemplates = junctionPointDAO.fetchByRoadwayPointIds(roadwayPoints)
      junctionPointTemplates.length should be(3)
    }
  }

  test("Test nodesAndJunctionsService.handleJunctionTemplates When both part and track changes") {
    runWithRollback {
      /*
                                                      ^   ^
                                                P3 T2 |   | P3 T1
                                                      |   |
                                P2 T0 (Continuous)    |   |
                            --->0------>------>------>0--->
                            ^   ^
          (Continuous)  P1  |   | P1 (Discontinuous)
                        T2  |   | T1
                            |   |

        * Note
          * 0: Illustration where junction points should be created
        */

      val projectId: Long = Sequences.nextViiteProjectId
      val project = Project(projectId, ProjectState.Incomplete, "f", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
        "", Seq(), Seq(), None, None)

      val plId = Sequences.nextProjectLinkId
      val rwId = Sequences.nextRoadwayId
      val llId = Sequences.nextLinearLocationId

      // road part 1
      val (pl1_1, ll1_1, rw1_1) = dummyVerticalRoad(projectId, 1, RoadPart(999, 1), Sequences.nextRoadwayNumber, Track.RightSide, discontinuity = Discontinuity.Discontinuous, firstPointAt = Point(5.0, 0.0), linkId = 12345, plId = plId, rw = (rwId, None), llId = llId)
      val (pl1_2, ll1_2, rw1_2) = dummyVerticalRoad(projectId, 1, RoadPart(999, 1), Sequences.nextRoadwayNumber, Track.LeftSide, discontinuity = Discontinuity.Continuous, linkId = 12346, plId = plId + 1, rw = (rwId + 1, None), llId = llId + 1)

      // road part 2
      val (pl2_1, ll2_1, rw2_a) = dummyHorizontalRoad(projectId, 1, RoadPart(999, 2), Sequences.nextRoadwayNumber, firstPointAt = Point(0.0, 10.0), linkId = 12347, plId = plId + 2, rw = (rwId + 2, None), llId = llId + 2, size = 5)
      val (pl2_2, ll2_2, rw2_b) = dummyHorizontalRoad(projectId, 3, RoadPart(999, 2), rw2_a.roadwayNumber, startAddrAt = 5, firstPointAt = Point(5.0, 10.0), orderNumber = 2.0, linkId = 12348, plId = plId + 3, rw = (rwId + 2, Some(rw2_a)), llId = llId + 3, size = 7)
      val (pl2_3, ll2_3, rw2)   = dummyHorizontalRoad(projectId, 1, RoadPart(999, 2), rw2_a.roadwayNumber, startAddrAt = 26, firstPointAt = Point(26.0, 10.0), orderNumber = 5.0, linkId = 12351, plId = plId + 6, rw = (rwId + 2, Some(rw2_b)), llId = llId + 6, size = 5)

      // road part 3
      val (pl3_1, ll3_1, rw3_1) = dummyVerticalRoad(projectId, 2, RoadPart(999, 3), Sequences.nextRoadwayNumber, Track.RightSide, discontinuity = Discontinuity.EndOfRoad, firstPointAt = Point(31.0, 10.0), linkId = 12352, plId = plId + 7, rw = (rwId + 3, None), llId = llId + 7)
      val (pl3_2, ll3_2, rw3_2) = dummyVerticalRoad(projectId, 2, RoadPart(999, 3), Sequences.nextRoadwayNumber, Track.LeftSide, discontinuity = Discontinuity.EndOfRoad, firstPointAt = Point(26.0, 10.0), linkId = 12354, plId = plId + 9, rw = (rwId + 4, None), llId = llId + 9)

      val roadways = Seq(rw1_1, rw1_2, rw2, rw3_1, rw3_2)
      val projectLinks = pl1_1 ++ pl1_2 ++ pl2_1 ++ pl2_2 ++ pl2_3 ++ pl3_1 ++ pl3_2
      val linearLocations = ll1_1 ++ ll1_2 ++ ll2_1 ++ ll2_2 ++ ll2_3 ++ ll3_1 ++ ll3_2

      buildTestDataForProject(Some(project), Some(roadways), Some(linearLocations), Some(projectLinks))

      val projectChanges = List(
        // road part 1
        ProjectRoadwayChange(projectId, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(RoadAddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, Some(AdministrativeClass.State), Some(Discontinuity.MinorDiscontinuity), Some(8L)),
            RoadwayChangeSection(Some(rw1_1.roadPart.roadNumber), Some(rw1_1.track.value), startRoadPartNumber = Some(rw1_1.roadPart.partNumber), endRoadPartNumber = Some(rw1_1.roadPart.partNumber), addrMRange = Some(rw1_1.addrMRange), Some(rw1_1.administrativeClass), Some(rw1_1.discontinuity), Some(rw1_1.ely)),
            rw1_1.discontinuity, rw1_1.administrativeClass, reversed = false, 1, rw1_1.ely)
          , DateTime.now),
        ProjectRoadwayChange(projectId, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(RoadAddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, Some(AdministrativeClass.State), Some(Discontinuity.MinorDiscontinuity), Some(8L)),
            RoadwayChangeSection(Some(rw1_2.roadPart.roadNumber), Some(rw1_2.track.value), startRoadPartNumber = Some(rw1_2.roadPart.partNumber), endRoadPartNumber = Some(rw1_2.roadPart.partNumber), addrMRange = Some(rw1_2.addrMRange), Some(rw1_2.administrativeClass), Some(rw1_2.discontinuity), Some(rw1_2.ely)),
            rw1_2.discontinuity, rw1_2.administrativeClass, reversed = false, 2, rw1_2.ely)
          , DateTime.now),
        // road part 2
        ProjectRoadwayChange(projectId, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(RoadAddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, Some(AdministrativeClass.State), Some(Discontinuity.MinorDiscontinuity), Some(8L)),
            RoadwayChangeSection(Some(rw2.roadPart.roadNumber), Some(rw2.track.value), startRoadPartNumber = Some(rw2.roadPart.partNumber), endRoadPartNumber = Some(rw2.roadPart.partNumber), addrMRange = Some(rw2.addrMRange), Some(rw2.administrativeClass), Some(rw2.discontinuity), Some(rw2.ely)),
            rw2.discontinuity, rw2.administrativeClass, reversed = false, 3, rw2.ely)
          , DateTime.now),
        // road part 3
        ProjectRoadwayChange(projectId, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(RoadAddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, Some(AdministrativeClass.State), Some(Discontinuity.MinorDiscontinuity), Some(8L)),
            RoadwayChangeSection(Some(rw3_1.roadPart.roadNumber), Some(rw1_1.track.value), startRoadPartNumber = Some(rw3_1.roadPart.partNumber), endRoadPartNumber = Some(rw3_1.roadPart.partNumber), addrMRange = Some(rw3_1.addrMRange), Some(rw3_1.administrativeClass), Some(rw3_1.discontinuity), Some(rw3_1.ely)),
            rw3_1.discontinuity, rw3_1.administrativeClass, reversed = false, 4, rw1_1.ely)
          , DateTime.now),
        ProjectRoadwayChange(projectId, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(RoadAddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, Some(AdministrativeClass.State), Some(Discontinuity.MinorDiscontinuity), Some(8L)),
            RoadwayChangeSection(Some(rw3_2.roadPart.roadNumber), Some(rw3_2.track.value), startRoadPartNumber = Some(rw3_2.roadPart.partNumber), endRoadPartNumber = Some(rw3_2.roadPart.partNumber), addrMRange = Some(rw3_2.addrMRange), Some(rw3_2.administrativeClass), Some(rw3_2.discontinuity), Some(rw3_2.ely)),
            rw3_2.discontinuity, rw3_2.administrativeClass, reversed = false, 5, rw3_2.ely)
          , DateTime.now)
      )

      val mappedReservedRoadwayNumbers = projectLinkDAO.fetchProjectLinksChange(projectId)
      roadAddressService.handleRoadwayPointsUpdate(projectChanges, mappedReservedRoadwayNumbers)
      nodesAndJunctionsService.handleNodePoints(projectChanges, projectLinks, mappedReservedRoadwayNumbers)

      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(pl1_1.head.geometry.head, pl1_1.head.geometry.head), roadNumberLimits)).thenReturn(ll1_1)
      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(pl1_2.head.geometry.head, pl1_2.head.geometry.head), roadNumberLimits)).thenReturn(ll1_2)
      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(pl2_1.head.geometry.head, pl2_1.head.geometry.head), roadNumberLimits)).thenReturn(ll1_2 ++ ll2_1 ++ ll2_2 ++ ll2_3)

      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(pl2_1.head.geometry.last, pl2_1.head.geometry.last), roadNumberLimits)).thenReturn(ll1_1 ++ ll2_1 ++ ll2_2 ++ ll2_3)
      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(pl2_3.head.geometry.head, pl2_3.head.geometry.head), roadNumberLimits)).thenReturn(ll2_1 ++ ll2_2 ++ ll2_3 ++ ll3_2)
      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(pl2_3.head.geometry.last, pl2_3.head.geometry.last), roadNumberLimits)).thenReturn(ll2_1 ++ ll2_2 ++ ll2_3 ++ ll3_1)

      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(pl3_1.head.geometry.last, pl3_1.head.geometry.last), roadNumberLimits)).thenReturn(ll3_1)
      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(pl3_1.last.geometry.last, pl3_1.last.geometry.last), roadNumberLimits)).thenReturn(ll3_1)
      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(pl3_2.head.geometry.last, pl3_2.head.geometry.last), roadNumberLimits)).thenReturn(ll3_2)
      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(pl3_2.last.geometry.last, pl3_2.last.geometry.last), roadNumberLimits)).thenReturn(ll3_2)

      pl2_2.tail.foreach { pl =>
        when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(pl.geometry.head, pl.geometry.head), roadNumberLimits)).thenReturn(ll2_1 ++ ll2_2 ++ ll2_3)
        when(mockRoadwayDAO.fetchAllByRoadwayNumbers((ll2_1 ++ ll2_2 ++ ll2_3).map(_.roadwayNumber).toSet, withHistory=false)).thenReturn(Seq(rw2))
      }

      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(ll1_1.map(_.roadwayNumber).toSet, withHistory=false)).thenReturn(Seq(rw1_1))
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(ll1_2.map(_.roadwayNumber).toSet, withHistory=false)).thenReturn(Seq(rw1_2))
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers((ll1_1 ++ ll1_2).map(_.roadwayNumber).toSet, withHistory=false)).thenReturn(Seq(rw1_1, rw1_2))
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(ll3_1.map(_.roadwayNumber).toSet, withHistory=false)).thenReturn(Seq(rw3_1))
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(ll3_2.map(_.roadwayNumber).toSet, withHistory=false)).thenReturn(Seq(rw3_2))
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers((ll3_1 ++ ll3_2).map(_.roadwayNumber).toSet, withHistory=false)).thenReturn(Seq(rw3_1, rw3_2))

      when(mockRoadwayDAO.fetchAllByRoadwayNumbers((ll1_2 ++ ll2_1 ++ ll2_2 ++ ll2_3).map(_.roadwayNumber).toSet, withHistory=false)).thenReturn(Seq(rw1_2, rw2))
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers((ll1_1 ++ ll2_1 ++ ll2_2 ++ ll2_3).map(_.roadwayNumber).toSet, withHistory=false)).thenReturn(Seq(rw1_1, rw2))
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers((ll2_1 ++ ll2_2 ++ ll2_3 ++ ll3_2).map(_.roadwayNumber).toSet, withHistory=false)).thenReturn(Seq(rw3_2, rw2))
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers((ll2_1 ++ ll2_2 ++ ll2_3 ++ ll3_1).map(_.roadwayNumber).toSet, withHistory=false)).thenReturn(Seq(rw3_1, rw2))

      nodesAndJunctionsService.handleJunctionAndJunctionPoints(projectChanges, projectLinks, mappedReservedRoadwayNumbers)

      val roadwayPoints = roadwayPointDAO.fetchByRoadwayNumbers(roadways.map(_.roadwayNumber))
      val junctionPointTemplates = junctionPointDAO.fetchByRoadwayPointIds(roadwayPoints.map(_.id))
      junctionPointTemplates.size should be(6)
      junctionPointTemplates.map(_.junctionId).distinct.size should be(2)

      nodesAndJunctionsService.expireObsoleteNodesAndJunctions(projectLinks, Some(project.startDate.minusDays(1)))

      val roadwayPoints2 = roadwayPointDAO.fetchByRoadwayNumbers(roadways.map(_.roadwayNumber))
      val junctionPointTemplates2 = junctionPointDAO.fetchByRoadwayPointIds(roadwayPoints2.map(_.id))
      junctionPointTemplates2.size should be(6)
      junctionPointTemplates2.map(_.junctionId).distinct.size should be(2)
    }
  }

  test("Test nodesAndJunctionsService.handleJunctionTemplates When exists discontinuity by addresses") {
    runWithRollback {
      /*
                ^
                |   P2 C3
                |
                0 --P3--> ---P4--->
                ^
                |   P2 C2
                |
        --P1--> 0
                ^   P2 C1
                |

        * Note
          * 0: Illustration where junction points should be created
          * P1: Road part 1:
            * from 0 to 5       (Discontinuous)
          * P2: Road part 2:
            * C1: from 0 to 5
            * C2: and 5 to 15
            * C3: and 15 to 30  (Discontinuous)
          * P3: Road part 3:
            * from 0 to 5
          * P4: Road part 4:
            * from 0 to 5       (End of road)
       */

      val projectId: Long = Sequences.nextViiteProjectId
      val project = Project(projectId, ProjectState.Incomplete, "f", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
        "", Seq(), Seq(), None, None)

      val rwNumber = Sequences.nextRoadwayNumber
      val rwId = Sequences.nextRoadwayId
      val plId = Sequences.nextProjectLinkId
      val llId = Sequences.nextLinearLocationId

      val (pl1,   ll1,   rw1)   = dummyHorizontalRoad(projectId, 1, RoadPart(999, 1), rwNumber,                      discontinuity = Discontinuity.Discontinuous, firstPointAt = Point(0.0, 5.0), linkId = 12345, plId = plId, rw = (rwId, None), llId = llId, size = 5)
      val (pl2_1, ll2_1, rw2_1) = dummyVerticalRoad  (projectId, 1, RoadPart(999, 2), rwNumber + 1,                  discontinuity = Discontinuity.Continuous,    firstPointAt = Point(5.0, 0.0), linkId = 12346, plId = plId + 1, rw = (rwId + 1, None), llId = llId + 1, size = 5)
      val (pl2_2, ll2_2, rw2_2) = dummyVerticalRoad  (projectId, 2, RoadPart(999, 2), rwNumber + 2, startAddrAt = 5, discontinuity = Discontinuity.Discontinuous, firstPointAt = Point(5.0, 5.0), linkId = 12347, plId = plId + 2, rw = (rwId + 2, None), llId = llId + 2, size = 15)
      val (pl3,   ll3,   rw3)   = dummyHorizontalRoad(projectId, 1, RoadPart(999, 3), rwNumber + 3,                  discontinuity = Discontinuity.Continuous,    firstPointAt = Point(5.0, 20.0), linkId = 12349, plId = plId + 4, rw = (rwId + 3, None), llId = llId + 4, size = 5)
      val (pl4,   ll4,   rw4)   = dummyHorizontalRoad(projectId, 1, RoadPart(999, 4), rwNumber + 4,                  discontinuity = Discontinuity.EndOfRoad,     firstPointAt = Point(10.0, 20.0), linkId = 12350, plId = plId + 5, rw = (rwId + 4, None), llId = llId + 5, size = 5)

      val roadways = Seq(rw1, rw2_1, rw2_2, rw3, rw4)
      val projectLinks = pl1 ++ pl2_1 ++ pl2_2 ++ pl3 ++ pl4
      val linearLocations = ll1 ++ ll2_1 ++ ll2_2 ++ ll3 ++ ll4

      buildTestDataForProject(Some(project), Some(roadways), Some(linearLocations), Some(projectLinks))

      val projectChanges = List(
        ProjectRoadwayChange(projectId, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(RoadAddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, Some(AdministrativeClass.State), Some(Discontinuity.MinorDiscontinuity), Some(8L)),
            RoadwayChangeSection(Some(rw1.roadPart.roadNumber), Some(rw1.track.value), startRoadPartNumber = Some(rw1.roadPart.partNumber), endRoadPartNumber = Some(rw1.roadPart.partNumber), addrMRange = Some(rw1.addrMRange), Some(rw1.administrativeClass), Some(rw1.discontinuity), Some(rw1.ely)),
            rw1.discontinuity, rw1.administrativeClass, reversed = false, 1, rw1.ely)
          , DateTime.now),

        ProjectRoadwayChange(projectId, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(RoadAddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, Some(AdministrativeClass.State), Some(Discontinuity.MinorDiscontinuity), Some(8L)),
            RoadwayChangeSection(Some(rw2_1.roadPart.roadNumber), Some(rw2_1.track.value), startRoadPartNumber = Some(rw2_1.roadPart.partNumber), endRoadPartNumber = Some(rw2_1.roadPart.partNumber), addrMRange = Some(rw2_1.addrMRange), Some(rw2_1.administrativeClass), Some(rw2_1.discontinuity), Some(rw2_1.ely)),
            rw2_1.discontinuity, rw2_1.administrativeClass, reversed = false, 1, rw2_1.ely)
          , DateTime.now),

        ProjectRoadwayChange(projectId, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(RoadAddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, Some(AdministrativeClass.State), Some(Discontinuity.MinorDiscontinuity), Some(8L)),
            RoadwayChangeSection(Some(rw2_2.roadPart.roadNumber), Some(rw2_2.track.value), startRoadPartNumber = Some(rw2_2.roadPart.partNumber), endRoadPartNumber = Some(rw2_2.roadPart.partNumber), addrMRange = Some(rw2_2.addrMRange), Some(rw2_2.administrativeClass), Some(rw2_2.discontinuity), Some(rw2_2.ely)),
            rw2_2.discontinuity, rw2_2.administrativeClass, reversed = false, 1, rw2_2.ely)
          , DateTime.now),

        ProjectRoadwayChange(projectId, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(RoadAddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, Some(AdministrativeClass.State), Some(Discontinuity.MinorDiscontinuity), Some(8L)),
            RoadwayChangeSection(Some(rw3.roadPart.roadNumber), Some(rw3.track.value), startRoadPartNumber = Some(rw3.roadPart.partNumber), endRoadPartNumber = Some(rw3.roadPart.partNumber), addrMRange = Some(rw3.addrMRange), Some(rw3.administrativeClass), Some(rw3.discontinuity), Some(rw3.ely)),
            rw3.discontinuity, rw3.administrativeClass, reversed = false, 1, rw3.ely)
          , DateTime.now),

        ProjectRoadwayChange(projectId, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(RoadAddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, Some(AdministrativeClass.State), Some(Discontinuity.MinorDiscontinuity), Some(8L)),
            RoadwayChangeSection(Some(rw4.roadPart.roadNumber), Some(rw4.track.value), startRoadPartNumber = Some(rw4.roadPart.partNumber), endRoadPartNumber = Some(rw4.roadPart.partNumber), addrMRange = Some(rw4.addrMRange), Some(rw4.administrativeClass), Some(rw4.discontinuity), Some(rw4.ely)),
            rw4.discontinuity, rw4.administrativeClass, reversed = false, 1, rw4.ely)
          , DateTime.now)
      )

      val mappedReservedRoadwayNumbers = projectLinkDAO.fetchProjectLinksChange(projectId)
      roadAddressService.handleRoadwayPointsUpdate(projectChanges, mappedReservedRoadwayNumbers)
      nodesAndJunctionsService.handleNodePoints(projectChanges, projectLinks, mappedReservedRoadwayNumbers)

      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(Point(0.0, 5.0), Point(0.0, 5.0)), roadNumberLimits)).thenReturn(ll1)
      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(Point(5.0, 5.0), Point(5.0, 5.0)), roadNumberLimits)).thenReturn(ll1 ++ ll2_1 :+ ll2_2.head)
      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(Point(5.0, 0.0), Point(5.0, 0.0)), roadNumberLimits)).thenReturn(ll2_1)
      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(Point(5.0, 20.0), Point(5.0, 20.0)), roadNumberLimits)).thenReturn(ll2_2 ++ ll3)
      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(Point(5.0, 35.0), Point(5.0, 35.0)), roadNumberLimits)).thenReturn(ll2_2.tail)
      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(Point(10.0, 20.0), Point(10.0, 20.0)), roadNumberLimits)).thenReturn(ll3 ++ ll4)
      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(Point(15.0, 20.0), Point(15.0, 20.0)), roadNumberLimits)).thenReturn(ll4)

      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(ll1  .map(_.roadwayNumber).toSet, withHistory=false)).thenReturn(Seq(rw1))
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(ll2_1.map(_.roadwayNumber).toSet, withHistory=false)).thenReturn(Seq(rw2_1))
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(ll2_2.map(_.roadwayNumber).toSet, withHistory=false)).thenReturn(Seq(rw2_2))
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(ll3  .map(_.roadwayNumber).toSet, withHistory=false)).thenReturn(Seq(rw3))
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(ll4  .map(_.roadwayNumber).toSet, withHistory=false)).thenReturn(Seq(rw4))

      when(mockRoadwayDAO.fetchAllByRoadwayNumbers((ll1   ++ ll2_1 :+ ll2_2.head).map(_.roadwayNumber).toSet, withHistory=false)).thenReturn(Seq(rw1, rw2_1, rw2_2))
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers((ll2_1 ++ ll2_2).map(_.roadwayNumber).toSet, withHistory=false)).thenReturn(Seq(rw2_1, rw2_2))
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers((ll2_2 ++ ll3  ).map(_.roadwayNumber).toSet, withHistory=false)).thenReturn(Seq(rw2_2, rw3))
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(   ll2_2.tail   .map(_.roadwayNumber).toSet, withHistory=false)).thenReturn(Seq(rw2_2))
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers((ll3   ++ ll4  ).map(_.roadwayNumber).toSet, withHistory=false)).thenReturn(Seq(rw3, rw4))

      nodesAndJunctionsService.handleJunctionAndJunctionPoints(projectChanges, projectLinks, mappedReservedRoadwayNumbers)
      nodesAndJunctionsService.expireObsoleteNodesAndJunctions(projectLinks, Some(project.startDate.minusDays(1)))

      val roadwayPoints = roadwayPointDAO.fetchByRoadwayNumbers(roadways.map(_.roadwayNumber))
      val junctionPointTemplates = junctionPointDAO.fetchByRoadwayPointIds(roadwayPoints.map(_.id))
      junctionPointTemplates.map(_.junctionId).distinct.size should be(2)
      junctionPointTemplates.size should be(6)
    }
  }

  test("Test nodesAndJunctionsService.handleJunctionTemplates When creating one track Discontinuous links and one opposite track Continuous and one combined EndOfRoad that are not connected by geometry with the discontinuous link, only by address, Then junction template and junctions points should be not be created.") {
    runWithRollback {
      /*
     |--L-->|-
              \
                L
                  \
                    >|--C1-->|--C2-->|

     |----R*---->|

            Note:
            0: Illustration where junction points should be created
            C1: Combined track Continuous
            C2: Combined track EndOfRoad
            L: Continuous Left track
            R*: Discontinuous Right track
       */

      val road = 999L
      val part = 1L
      val projectId = Sequences.nextViiteProjectId
      val rwId = Sequences.nextRoadwayId
      val llId = Sequences.nextLinearLocationId
      val rwNumber = Sequences.nextRoadwayNumber
      val plId = Sequences.nextProjectLinkId


      val leftGeom1 = Seq(Point(0.0, 50.0), Point(5.0, 50.0))
      val leftGeom2 = Seq(Point(5.0, 50.0), Point(12.0, 45.0))
      val rightGeom1 = Seq(Point(0.0, 40.0), Point(10.0, 40.0))
      val combGeom1 = Seq(Point(12.0, 45.0), Point(17.0, 45.0))
      val combGeom2 = Seq(Point(17.0, 45.0), Point(23.0, 45.0))

      val leftLink1  = dummyProjectLink(RoadPart(road, part), Track.LeftSide,  Discontinuity.Continuous,        AddrMRange( 0,  5), AddrMRange( 0,  5), Some(DateTime.now()), None, 12345.toString, 0, 5, SideCode.TowardsDigitizing, RoadAddressChangeType.Transfer, projectId, AdministrativeClass.State, leftGeom1,  rwNumber    ).copy(id = plId,     projectId = projectId, roadwayId = rwId,     linearLocationId = llId)
      val leftLink2  = dummyProjectLink(RoadPart(road, part), Track.LeftSide,  Discontinuity.Continuous,        AddrMRange( 5, 10), AddrMRange( 5, 10), Some(DateTime.now()), None, 12346.toString, 0, 5, SideCode.TowardsDigitizing, RoadAddressChangeType.Transfer, projectId, AdministrativeClass.State, leftGeom2,  rwNumber    ).copy(id = plId + 1, projectId = projectId, roadwayId = rwId,     linearLocationId = llId + 1)
      val rightLink1 = dummyProjectLink(RoadPart(road, part), Track.RightSide, Discontinuity.MinorDiscontinuity,AddrMRange( 0, 10), AddrMRange( 0, 10), Some(DateTime.now()), None, 12347.toString, 0, 5, SideCode.TowardsDigitizing, RoadAddressChangeType.Transfer, projectId, AdministrativeClass.State, rightGeom1, rwNumber + 1).copy(id = plId + 2, projectId = projectId, roadwayId = rwId + 1, linearLocationId = llId + 2)
      val combLink1  = dummyProjectLink(RoadPart(road, part), Track.Combined,  Discontinuity.Continuous,        AddrMRange(10, 15), AddrMRange(10, 15), Some(DateTime.now()), None, 12348.toString, 0, 5, SideCode.TowardsDigitizing, RoadAddressChangeType.Transfer, projectId, AdministrativeClass.State, combGeom1,  rwNumber + 2).copy(id = plId + 3, projectId = projectId, roadwayId = rwId + 2, linearLocationId = llId + 3)
      val combLink2  = dummyProjectLink(RoadPart(road, part), Track.Combined,  Discontinuity.EndOfRoad,         AddrMRange(15, 20), AddrMRange(15, 20), Some(DateTime.now()), None, 12349.toString, 0, 5, SideCode.TowardsDigitizing, RoadAddressChangeType.Transfer, projectId, AdministrativeClass.State, combGeom2,  rwNumber + 2).copy(id = plId + 4, projectId = projectId, roadwayId = rwId + 2, linearLocationId = llId + 4)

      val project = Project(projectId, ProjectState.Incomplete, "f", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
        "", Seq(), Seq(), None, None)

      val leftPLinks = Seq(leftLink1, leftLink2)
      val rightPLinks = Seq(rightLink1)
      val combPLinks = Seq(combLink1, combLink2)

      val (lll1,   rw1   ): (LinearLocation, Roadway) = Seq(leftLink1).map(toRoadwayAndLinearLocation).head
      val (lll2,_/*rw11*/): (LinearLocation, Roadway) = Seq(leftLink2).map(toRoadwayAndLinearLocation).head
      val (rll1,   rw2   ): (LinearLocation, Roadway) = Seq(rightLink1).map(toRoadwayAndLinearLocation).head
      val (cll1,   rw3   ): (LinearLocation, Roadway) = Seq(combLink1).map(toRoadwayAndLinearLocation).head
      val (cll2,_/*rw4*/ ): (LinearLocation, Roadway) = Seq(combLink2).map(toRoadwayAndLinearLocation).head
      val rw1WithId = rw1.copy(addrMRange = AddrMRange( 0,10), ely = 8L)
      val rw2WithId = rw2.copy(addrMRange = AddrMRange( 0,10), ely = 8L)
      val rw3WithId = rw3.copy(addrMRange = AddrMRange(10,20), ely = 8L)
      val orderedlll1 = lll1.copy(orderNumber = 1)
      val orderedlll2 = lll2.copy(orderNumber = 2)
      val orderedcll1 = cll1.copy(orderNumber = 1)
      val orderedcll2 = cll2.copy(orderNumber = 2)

      buildTestDataForProject(Some(project), Some(Seq(rw1WithId, rw2WithId, rw3WithId)), Some(Seq(orderedlll1, orderedlll2, rll1, orderedcll1, orderedcll2)), Some(leftPLinks ++ rightPLinks ++ combPLinks))

      val projectChanges = List(
        //left
        ProjectRoadwayChange(projectId, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(RoadAddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, Some(AdministrativeClass.State), Some(Discontinuity.Continuous), Some(8L)),
            RoadwayChangeSection(Some(road), Some(Track.LeftSide.value.toLong), startRoadPartNumber = Some(part), endRoadPartNumber = Some(part), addrMRange = Some(AddrMRange(0L,10L)), Some(AdministrativeClass.State), Some(Discontinuity.Continuous), Some(8L)),
            Discontinuity.Continuous, AdministrativeClass.State, reversed = false, 1, 8)
          , DateTime.now),
        //right
        ProjectRoadwayChange(projectId, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(RoadAddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, Some(AdministrativeClass.State), Some(Discontinuity.MinorDiscontinuity), Some(8L)),
            RoadwayChangeSection(Some(road), Some(Track.RightSide.value.toLong), startRoadPartNumber = Some(part), endRoadPartNumber = Some(part), addrMRange = Some(AddrMRange(0L,10L)), Some(AdministrativeClass.State), Some(Discontinuity.MinorDiscontinuity), Some(8L)),
            Discontinuity.MinorDiscontinuity, AdministrativeClass.State, reversed = false, 2, 8)
          , DateTime.now),
        //combined
        ProjectRoadwayChange(projectId, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(RoadAddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, Some(AdministrativeClass.State), Some(Discontinuity.Continuous), Some(8L)),
            RoadwayChangeSection(Some(road), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(part), endRoadPartNumber = Some(part), addrMRange = Some(AddrMRange(10L,15L)), Some(AdministrativeClass.State), Some(Discontinuity.Continuous), Some(8L)),
            Discontinuity.Continuous, AdministrativeClass.State, reversed = false, 3, 8)
          , DateTime.now),
        ProjectRoadwayChange(projectId, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(RoadAddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, Some(AdministrativeClass.State), Some(Discontinuity.EndOfRoad), Some(8L)),
            RoadwayChangeSection(Some(road), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(part), endRoadPartNumber = Some(part), addrMRange = Some(AddrMRange(15L,20L)), Some(AdministrativeClass.State), Some(Discontinuity.EndOfRoad), Some(8L)),
            Discontinuity.EndOfRoad, AdministrativeClass.State, reversed = false, 4, 8)
          , DateTime.now)
      )

      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(leftGeom1.head, leftGeom1.head), roadNumberLimits)).thenReturn(Seq())
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(Set.empty[Long], withHistory=false)).thenReturn(Seq())

      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(leftGeom1.last, leftGeom1.last), roadNumberLimits)).thenReturn(Seq(orderedlll1, orderedlll2))
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(Set(orderedlll1.roadwayNumber), withHistory=false)).thenReturn(Seq(rw1WithId))
      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(leftGeom2.last, leftGeom2.last), roadNumberLimits)).thenReturn(Seq(orderedlll1, orderedlll2, orderedcll1, orderedcll2))
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(Set(rw1WithId.roadwayNumber, orderedcll1.roadwayNumber), withHistory=false)).thenReturn(Seq(rw1WithId, rw3WithId))
      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(rightGeom1.head, rightGeom1.head), roadNumberLimits)).thenReturn(Seq())
      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(rightGeom1.last, rightGeom1.last), roadNumberLimits)).thenReturn(Seq())
      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(combGeom1.head, combGeom1.head), roadNumberLimits)).thenReturn(Seq())
      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(combGeom1.last, combGeom1.last), roadNumberLimits)).thenReturn(Seq(orderedcll1, orderedcll2))
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(Set(orderedcll1.roadwayNumber, orderedcll2.roadwayNumber), withHistory=false)).thenReturn(Seq(rw3WithId))
      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(combGeom2.last, combGeom2.last), roadNumberLimits)).thenReturn(Seq())

      val mappedReservedRoadwayNumbers = projectLinkDAO.fetchProjectLinksChange(projectId)
      roadAddressService.handleRoadwayPointsUpdate(projectChanges, mappedReservedRoadwayNumbers)
      nodesAndJunctionsService.handleJunctionAndJunctionPoints(projectChanges, leftPLinks ++ rightPLinks ++ combPLinks, mappedReservedRoadwayNumbers)

      val roadwayPoints = roadwayPointDAO.fetchByRoadwayNumbers((leftPLinks ++ rightPLinks ++ combPLinks).map(_.roadwayNumber)).map(_.id)
      val junctionPointTemplates = junctionPointDAO.fetchByRoadwayPointIds(roadwayPoints)
      junctionPointTemplates.length should be(0)
    }
  }

  test("Test nodesAndJunctionsService.handleJunctionTemplates When creating new road parts where one of them ends in the same road and which link is EndOfRoad Then junction template and junctions points should be handled/created properly") {
    runWithRollback {
      /*
              |
              |
              C3
              |
              v
     |--C1-->|0|--C2-->|

            Note:
            0: Illustration where junction points should be created
            C: Combined track
       */

      val road = 999L
      val part1 = 1L
      val part2 = 2L
      val part3 = 3L
      val projectId = Sequences.nextViiteProjectId
      val rwId = Sequences.nextRoadwayId
      val llId = Sequences.nextLinearLocationId
      val rwNumber = Sequences.nextRoadwayNumber
      val plId = Sequences.nextProjectLinkId


      val combGeom1 = Seq(Point(0.0, 0.0), Point(10.0, 0.0))
      val combGeom2 = Seq(Point(10.0, 0.0), Point(20.0, 0.0))
      val combGeom3 = Seq(Point(10.0, 10.0), Point(10.0, 0.0))

      val combLink1 = dummyProjectLink(RoadPart(road, part1), Track.Combined, Discontinuity.Continuous,   AddrMRange( 0, 10), AddrMRange(0, 10), Some(DateTime.now()), None, 12345.toString, 0, 10, SideCode.TowardsDigitizing, RoadAddressChangeType.Transfer, projectId, AdministrativeClass.State, combGeom1, rwNumber    ).copy(id = plId,     projectId = projectId, roadwayId = rwId,     linearLocationId = llId)
      val combLink2 = dummyProjectLink(RoadPart(road, part2), Track.Combined, Discontinuity.Discontinuous,AddrMRange( 0, 10), AddrMRange(0, 10), Some(DateTime.now()), None, 12346.toString, 0, 10, SideCode.TowardsDigitizing, RoadAddressChangeType.Transfer, projectId, AdministrativeClass.State, combGeom2, rwNumber + 1).copy(id = plId + 1, projectId = projectId, roadwayId = rwId + 1, linearLocationId = llId + 1)
      val combLink3 = dummyProjectLink(RoadPart(road, part3), Track.Combined, Discontinuity.EndOfRoad,    AddrMRange( 0, 10), AddrMRange(0, 10), Some(DateTime.now()), None, 12347.toString, 0, 10, SideCode.TowardsDigitizing, RoadAddressChangeType.Transfer, projectId, AdministrativeClass.State, combGeom3, rwNumber + 2).copy(id = plId + 2, projectId = projectId, roadwayId = rwId + 2, linearLocationId = llId + 2)

      val project = Project(projectId, ProjectState.Incomplete, "f", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
        "", Seq(), Seq(), None, None)

      val combPLinks = Seq(combLink1, combLink2, combLink3)

      val (cll1, rw1): (LinearLocation, Roadway) = Seq(combLink1).map(toRoadwayAndLinearLocation).head
      val (cll2, rw2): (LinearLocation, Roadway) = Seq(combLink2).map(toRoadwayAndLinearLocation).head
      val (cll3, rw3): (LinearLocation, Roadway) = Seq(combLink3).map(toRoadwayAndLinearLocation).head
      val rw1WithId = rw1.copy(addrMRange = AddrMRange(0,10), ely = 8L)
      val rw2WithId = rw2.copy(addrMRange = AddrMRange(0,10), ely = 8L)
      val rw3WithId = rw3.copy(addrMRange = AddrMRange(0,10), ely = 8L)

      buildTestDataForProject(Some(project), Some(Seq(rw1WithId, rw2WithId, rw3WithId)), Some(Seq(cll1, cll2, cll3)), Some(combPLinks))

      val projectChanges = List(
        //Combined
        ProjectRoadwayChange(projectId, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(RoadAddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, Some(AdministrativeClass.State), Some(Discontinuity.Continuous), Some(8L)),
            RoadwayChangeSection(Some(road), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(part1), endRoadPartNumber = Some(part1), addrMRange = Some(AddrMRange(0L,10L)), Some(AdministrativeClass.State), Some(Discontinuity.Continuous), Some(8L)),
            Discontinuity.Continuous, AdministrativeClass.State, reversed = false, 1, 8)
          , DateTime.now),
        ProjectRoadwayChange(projectId, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(RoadAddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, Some(AdministrativeClass.State), Some(Discontinuity.Continuous), Some(8L)),
            RoadwayChangeSection(Some(road), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(part2), endRoadPartNumber = Some(part2), addrMRange = Some(AddrMRange(0L,10L)), Some(AdministrativeClass.State), Some(Discontinuity.Discontinuous), Some(8L)),
            Discontinuity.Discontinuous, AdministrativeClass.State, reversed = false, 2, 8)
          , DateTime.now),
        ProjectRoadwayChange(projectId, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(RoadAddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, Some(AdministrativeClass.State), Some(Discontinuity.Continuous), Some(8L)),
            RoadwayChangeSection(Some(road), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(part3), endRoadPartNumber = Some(part3), addrMRange = Some(AddrMRange(0L,10L)), Some(AdministrativeClass.State), Some(Discontinuity.EndOfRoad), Some(8L)),
            Discontinuity.EndOfRoad, AdministrativeClass.State, reversed = false, 3, 8)
          , DateTime.now)
      )

      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(combGeom1.head, combGeom1.head), roadNumberLimits)).thenReturn(Seq())
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(Set.empty[Long], withHistory=false)).thenReturn(Seq())
      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(combGeom1.last, combGeom1.last), roadNumberLimits)).thenReturn(Seq(cll1, cll2, cll3))
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(Set(cll1.roadwayNumber, cll2.roadwayNumber, cll3.roadwayNumber), withHistory=false)).thenReturn(Seq(rw1WithId, rw2WithId, rw3WithId))
      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(combGeom2.last, combGeom2.last), roadNumberLimits)).thenReturn(Seq())
      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(combGeom3.head, combGeom3.head), roadNumberLimits)).thenReturn(Seq())

      val mappedReservedRoadwayNumbers = projectLinkDAO.fetchProjectLinksChange(projectId)
      roadAddressService.handleRoadwayPointsUpdate(projectChanges, mappedReservedRoadwayNumbers)
      nodesAndJunctionsService.handleJunctionAndJunctionPoints(projectChanges, combPLinks, mappedReservedRoadwayNumbers)

      val roadwayPoints = roadwayPointDAO.fetchByRoadwayNumbers(combPLinks.map(_.roadwayNumber)).map(_.id)
      val junctionPointTemplates = junctionPointDAO.fetchByRoadwayPointIds(roadwayPoints)

      junctionPointTemplates.length should be(3)
      junctionPointTemplates.count(_.beforeAfter == BeforeAfter.Before) should be(2)
      junctionPointTemplates.count(_.beforeAfter == BeforeAfter.After) should be(1)

      val junctions = junctionDAO.fetchTemplatesByRoadwayNumbers(junctionPointTemplates.map(_.roadwayNumber).distinct)
      junctions.size should be(1)
    }
  }

  test("Test nodesAndJunctionsService.handleJunctionTemplates When creating new road parts where one of them ends in the beginning geometry point of same road and which link is EndOfRoad Then junction template and junctions points should be handled/created properly") {
    runWithRollback {
    /*
            |
        C3  |
            |
            v
            0--C1-->--C2-->

      * Note:
          0: Illustration where junction points should be created
          C: Combined track
      */

      val road = 999L
      val part1 = 1L
      val part2 = 2L
      val part3 = 3L
      val projectId = Sequences.nextViiteProjectId
      val rwId = Sequences.nextRoadwayId
      val llId = Sequences.nextLinearLocationId
      val rwNumber = Sequences.nextRoadwayNumber
      val plId = Sequences.nextProjectLinkId


      val combGeom1 = Seq(Point(0.0, 0.0), Point(10.0, 0.0))
      val combGeom2 = Seq(Point(10.0, 0.0), Point(20.0, 0.0))
      val combGeom3 = Seq(Point(0.0, 10.0), Point(0.0, 0.0))

      val combLink1 = dummyProjectLink(RoadPart(road, part1), Track.Combined, Discontinuity.Continuous,   AddrMRange( 0, 10), AddrMRange(0, 10), Some(DateTime.now()), None, 12345.toString, 0, 10, SideCode.TowardsDigitizing, RoadAddressChangeType.Transfer, projectId, AdministrativeClass.State, combGeom1, rwNumber    ).copy(id = plId,     projectId = projectId, roadwayId = rwId,     linearLocationId = llId)
      val combLink2 = dummyProjectLink(RoadPart(road, part2), Track.Combined, Discontinuity.Discontinuous,AddrMRange( 0, 10), AddrMRange(0, 10), Some(DateTime.now()), None, 12346.toString, 0, 10, SideCode.TowardsDigitizing, RoadAddressChangeType.Transfer, projectId, AdministrativeClass.State, combGeom2, rwNumber + 1).copy(id = plId + 1, projectId = projectId, roadwayId = rwId + 1, linearLocationId = llId + 1)
      val combLink3 = dummyProjectLink(RoadPart(road, part3), Track.Combined, Discontinuity.EndOfRoad,    AddrMRange( 0, 10), AddrMRange(0, 10), Some(DateTime.now()), None, 12347.toString, 0, 10, SideCode.TowardsDigitizing, RoadAddressChangeType.Transfer, projectId, AdministrativeClass.State, combGeom3, rwNumber + 2).copy(id = plId + 2, projectId = projectId, roadwayId = rwId + 2, linearLocationId = llId + 2)

      val project = Project(projectId, ProjectState.Incomplete, "f", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
        "", Seq(), Seq(), None, None)

      val combPLinks = Seq(combLink1, combLink2, combLink3)

      val (cll1, rw1): (LinearLocation, Roadway) = Seq(combLink1).map(toRoadwayAndLinearLocation).head
      val (cll2, rw2): (LinearLocation, Roadway) = Seq(combLink2).map(toRoadwayAndLinearLocation).head
      val (cll3, rw3): (LinearLocation, Roadway) = Seq(combLink3).map(toRoadwayAndLinearLocation).head
      val rw1WithId = rw1.copy(addrMRange = AddrMRange(0,10), ely = 8L)
      val rw2WithId = rw2.copy(addrMRange = AddrMRange(0,10), ely = 8L)
      val rw3WithId = rw3.copy(addrMRange = AddrMRange(0,10), ely = 8L)

      buildTestDataForProject(Some(project), Some(Seq(rw1WithId, rw2WithId, rw3WithId)), Some(Seq(cll1, cll2, cll3)), Some(combPLinks))

      val projectChanges = List(
        //  Combined
        ProjectRoadwayChange(projectId, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(RoadAddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, Some(AdministrativeClass.State), Some(Discontinuity.Continuous), Some(8L)),
            RoadwayChangeSection(Some(road), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(part1), endRoadPartNumber = Some(part1), addrMRange = Some(AddrMRange(0L,10L)), Some(AdministrativeClass.State), Some(Discontinuity.Continuous), Some(8L)),
            Discontinuity.Continuous, AdministrativeClass.State, reversed = false, 1, 8)
          , DateTime.now),
        ProjectRoadwayChange(projectId, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(RoadAddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, Some(AdministrativeClass.State), Some(Discontinuity.Discontinuous), Some(8L)),
            RoadwayChangeSection(Some(road), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(part2), endRoadPartNumber = Some(part2), addrMRange = Some(AddrMRange(0L,10L)), Some(AdministrativeClass.State), Some(Discontinuity.Discontinuous), Some(8L)),
            Discontinuity.Discontinuous, AdministrativeClass.State, reversed = false, 2, 8)
          , DateTime.now),
        ProjectRoadwayChange(projectId, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(RoadAddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, Some(AdministrativeClass.State), Some(Discontinuity.EndOfRoad), Some(8L)),
            RoadwayChangeSection(Some(road), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(part3), endRoadPartNumber = Some(part3), addrMRange = Some(AddrMRange(0L,10L)), Some(AdministrativeClass.State), Some(Discontinuity.EndOfRoad), Some(8L)),
            Discontinuity.EndOfRoad, AdministrativeClass.State, reversed = false, 3, 8)
          , DateTime.now)
      )

      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(combGeom1.head, combGeom1.head), roadNumberLimits)).thenReturn(Seq(cll1, cll3))
      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(combGeom1.last, combGeom1.last), roadNumberLimits)).thenReturn(Seq(cll1, cll2))
      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(combGeom2.last, combGeom2.last), roadNumberLimits)).thenReturn(Seq(cll2))
      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(combGeom3.head, combGeom3.head), roadNumberLimits)).thenReturn(Seq(cll3))

      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(Set(cll1.roadwayNumber), withHistory=false)).thenReturn(Seq(rw1WithId))
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(Set(cll1.roadwayNumber, cll3.roadwayNumber), withHistory=false)).thenReturn(Seq(rw1WithId, rw3WithId))
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(Set(cll1.roadwayNumber, cll2.roadwayNumber), withHistory=false)).thenReturn(Seq(rw1WithId, rw2WithId))
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(Set(cll2.roadwayNumber), withHistory=false)).thenReturn(Seq(rw2WithId))
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(Set(cll3.roadwayNumber), withHistory=false)).thenReturn(Seq(rw3WithId))

      val mappedReservedRoadwayNumbers = projectLinkDAO.fetchProjectLinksChange(projectId)
      roadAddressService.handleRoadwayPointsUpdate(projectChanges, mappedReservedRoadwayNumbers)
      nodesAndJunctionsService.handleNodePoints(projectChanges, combPLinks, mappedReservedRoadwayNumbers)
      nodesAndJunctionsService.handleJunctionAndJunctionPoints(projectChanges, combPLinks, mappedReservedRoadwayNumbers)
      nodesAndJunctionsService.expireObsoleteNodesAndJunctions(combPLinks, Some(project.startDate.minusDays(1)))

      val roadwayPoints = roadwayPointDAO.fetchByRoadwayNumbers(combPLinks.map(_.roadwayNumber)).map(_.id)
      val junctionPointTemplates = junctionPointDAO.fetchByRoadwayPointIds(roadwayPoints)

      junctionPointTemplates.length should be(2)
      junctionPointTemplates.count(_.beforeAfter == BeforeAfter.Before) should be(1)
      junctionPointTemplates.count(_.beforeAfter == BeforeAfter.After) should be(1)

      val junctions = junctionDAO.fetchTemplatesByRoadwayNumbers(junctionPointTemplates.map(_.roadwayNumber).distinct)
      junctions.size should be(1)
    }
  }

  test("Test nodesAndJunctionsService.handleJunctionTemplates When creating new road parts where one of them ends in the ending geometry point of same road and which link is EndOfRoad Then junction template and junctions points should be handled/created properly") {
    runWithRollback {
      /*
                        |
                        | C3
                        |
                        v
          --C1-->--C2-->0

        * Note:
            0: Illustration where junction points should be created
            C: Combined track
        */

      val road = 999L
      val part1 = 1L
      val part2 = 2L
      val part3 = 3L
      val projectId = Sequences.nextViiteProjectId
      val rwId = Sequences.nextRoadwayId
      val llId = Sequences.nextLinearLocationId
      val rwNumber = Sequences.nextRoadwayNumber
      val plId = Sequences.nextProjectLinkId


      val combGeom1 = Seq(Point(0.0, 0.0), Point(10.0, 0.0))
      val combGeom2 = Seq(Point(10.0, 0.0), Point(20.0, 0.0))
      val combGeom3 = Seq(Point(20.0, 10.0), Point(20.0, 0.0))

      val combLink1 = dummyProjectLink(RoadPart(road, part1), Track.Combined, Discontinuity.Continuous,    AddrMRange(0, 10), AddrMRange(0, 10), Some(DateTime.now()), None, 12345.toString, 0, 10, SideCode.TowardsDigitizing, RoadAddressChangeType.Transfer, projectId, AdministrativeClass.State, combGeom1, rwNumber    ).copy(id = plId,     projectId = projectId, roadwayId = rwId,     linearLocationId = llId)
      val combLink2 = dummyProjectLink(RoadPart(road, part2), Track.Combined, Discontinuity.Discontinuous, AddrMRange(0, 10), AddrMRange(0, 10), Some(DateTime.now()), None, 12346.toString, 0, 10, SideCode.TowardsDigitizing, RoadAddressChangeType.Transfer, projectId, AdministrativeClass.State, combGeom2, rwNumber + 1).copy(id = plId + 1, projectId = projectId, roadwayId = rwId + 1, linearLocationId = llId + 1)
      val combLink3 = dummyProjectLink(RoadPart(road, part3), Track.Combined, Discontinuity.EndOfRoad,     AddrMRange(0, 10), AddrMRange(0, 10), Some(DateTime.now()), None, 12347.toString, 0, 10, SideCode.TowardsDigitizing, RoadAddressChangeType.Transfer, projectId, AdministrativeClass.State, combGeom3, rwNumber + 2).copy(id = plId + 2, projectId = projectId, roadwayId = rwId + 2, linearLocationId = llId + 2)

      val project = Project(projectId, ProjectState.Incomplete, "f", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
        "", Seq(), Seq(), None, None)

      val combPLinks = Seq(combLink1, combLink2, combLink3)

      val (cll1, rw1): (LinearLocation, Roadway) = Seq(combLink1).map(toRoadwayAndLinearLocation).head
      val (cll2, rw2): (LinearLocation, Roadway) = Seq(combLink2).map(toRoadwayAndLinearLocation).head
      val (cll3, rw3): (LinearLocation, Roadway) = Seq(combLink3).map(toRoadwayAndLinearLocation).head
      val rw1WithId = rw1.copy(addrMRange = AddrMRange(0,10), ely = 8L)
      val rw2WithId = rw2.copy(addrMRange = AddrMRange(0,10), ely = 8L)
      val rw3WithId = rw3.copy(addrMRange = AddrMRange(0,10), ely = 8L)

      buildTestDataForProject(Some(project), Some(Seq(rw1WithId, rw2WithId, rw3WithId)), Some(Seq(cll1, cll2, cll3)), Some(combPLinks))

      val projectChanges = List(
        //Combined
        ProjectRoadwayChange(projectId, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(RoadAddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, Some(AdministrativeClass.State), Some(Discontinuity.Continuous), Some(8L)),
            RoadwayChangeSection(Some(road), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(part1), endRoadPartNumber = Some(part1), addrMRange = Some(AddrMRange(0L,10L)), Some(AdministrativeClass.State), Some(Discontinuity.Continuous), Some(8L)),
            Discontinuity.Continuous, AdministrativeClass.State, reversed = false, 1, 8)
          , DateTime.now),
        ProjectRoadwayChange(projectId, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(RoadAddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, Some(AdministrativeClass.State), Some(Discontinuity.Discontinuous), Some(8L)),
            RoadwayChangeSection(Some(road), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(part2), endRoadPartNumber = Some(part2), addrMRange = Some(AddrMRange(0L,10L)), Some(AdministrativeClass.State), Some(Discontinuity.Discontinuous), Some(8L)),
            Discontinuity.Discontinuous, AdministrativeClass.State, reversed = false, 2, 8)
          , DateTime.now),
        ProjectRoadwayChange(projectId, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(RoadAddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, Some(AdministrativeClass.State), Some(Discontinuity.EndOfRoad), Some(8L)),
            RoadwayChangeSection(Some(road), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(part3), endRoadPartNumber = Some(part3), addrMRange = Some(AddrMRange(0L,10L)), Some(AdministrativeClass.State), Some(Discontinuity.EndOfRoad), Some(8L)),
            Discontinuity.EndOfRoad, AdministrativeClass.State, reversed = false, 3, 8)
          , DateTime.now)
      )

      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(combGeom1.head, combGeom1.head), roadNumberLimits)).thenReturn(Seq(cll1))
      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(combGeom1.last, combGeom1.last), roadNumberLimits)).thenReturn(Seq(cll1, cll2))
      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(combGeom2.last, combGeom2.last), roadNumberLimits)).thenReturn(Seq(cll2, cll3))
      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(combGeom3.head, combGeom3.head), roadNumberLimits)).thenReturn(Seq(cll3))

      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(Set(cll1.roadwayNumber), withHistory=false)).thenReturn(Seq(rw1WithId))
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(Set(cll2.roadwayNumber), withHistory=false)).thenReturn(Seq(rw2WithId))
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(Set(cll1.roadwayNumber, cll2.roadwayNumber), withHistory=false)).thenReturn(Seq(rw1WithId, rw2WithId))
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(Set(cll2.roadwayNumber, cll3.roadwayNumber), withHistory=false)).thenReturn(Seq(rw2WithId, rw3WithId))
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(Set(cll3.roadwayNumber), withHistory=false)).thenReturn(Seq(rw3WithId))

      val mappedReservedRoadwayNumbers = projectLinkDAO.fetchProjectLinksChange(projectId)
      roadAddressService.handleRoadwayPointsUpdate(projectChanges, mappedReservedRoadwayNumbers)
      nodesAndJunctionsService.handleNodePoints(projectChanges, combPLinks, mappedReservedRoadwayNumbers)
      nodesAndJunctionsService.handleJunctionAndJunctionPoints(projectChanges, combPLinks, mappedReservedRoadwayNumbers)
      nodesAndJunctionsService.expireObsoleteNodesAndJunctions(combPLinks, Some(project.startDate.minusDays(1)))

      val roadwayPoints = roadwayPointDAO.fetchByRoadwayNumbers(combPLinks.map(_.roadwayNumber)).map(_.id)
      val junctionPointTemplates = junctionPointDAO.fetchByRoadwayPointIds(roadwayPoints)

      junctionPointTemplates.length should be(2)
      junctionPointTemplates.count(_.beforeAfter == BeforeAfter.Before) should be(2)

      val junctions = junctionDAO.fetchTemplatesByRoadwayNumbers(junctionPointTemplates.map(_.roadwayNumber).distinct)
      junctions.size should be(1)
    }
  }


  test("Test nodesAndJunctionsService.handleJunctionTemplates When creating new road parts that connects to other existing part which link is EndOfRoad and ends in some link of this new roads in same road number Then junction template and junctions points should be handled/created properly.") {
    runWithRollback {
      /*
               |
               |  C3
               |
               v
        --C1-->0--C2-->

        * Note:
            0: Illustration where junction points should be created
            C: Combined track
        */

      val road = 999L
      val part1 = 1L
      val part2 = 2L
      val part3 = 3L
      val projectId = Sequences.nextViiteProjectId
      val rwId = Sequences.nextRoadwayId
      val llId = Sequences.nextLinearLocationId
      val rwNumber = Sequences.nextRoadwayNumber
      val plId = Sequences.nextProjectLinkId


      val combGeom1 = Seq(Point(0.0, 0.0), Point(10.0, 0.0))
      val combGeom2 = Seq(Point(10.0, 0.0), Point(20.0, 0.0))
      val combGeom3 = Seq(Point(10.0, 10.0), Point(10.0, 0.0))

      val combLink1 = dummyProjectLink(RoadPart(road, part1), Track.Combined, Discontinuity.Continuous,    AddrMRange(0, 10), AddrMRange(0, 10), Some(DateTime.now()), None, 12345.toString, 0, 10, SideCode.TowardsDigitizing, RoadAddressChangeType.Transfer, projectId, AdministrativeClass.State, combGeom1, rwNumber    ).copy(id = plId,     projectId = projectId, roadwayId = rwId,     linearLocationId = llId)
      val combLink2 = dummyProjectLink(RoadPart(road, part2), Track.Combined, Discontinuity.Discontinuous, AddrMRange(0, 10), AddrMRange(0, 10), Some(DateTime.now()), None, 12346.toString, 0, 10, SideCode.TowardsDigitizing, RoadAddressChangeType.Transfer, projectId, AdministrativeClass.State, combGeom2, rwNumber + 1).copy(id = plId + 1, projectId = projectId, roadwayId = rwId + 1, linearLocationId = llId + 1)
      val combLink3 = dummyProjectLink(RoadPart(road, part3), Track.Combined, Discontinuity.EndOfRoad,     AddrMRange(0, 10), AddrMRange(0, 10), Some(DateTime.now()), None, 12347.toString, 0, 10, SideCode.TowardsDigitizing, RoadAddressChangeType.Transfer, projectId, AdministrativeClass.State, combGeom3, rwNumber + 2).copy(id = plId + 2, projectId = projectId, roadwayId = rwId + 2, linearLocationId = llId + 2)

      val project = Project(projectId, ProjectState.Incomplete, "f", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
        "", Seq(), Seq(), None, None)

      val combPLinks = Seq(combLink1, combLink2, combLink3)

      val (lc1, rw1): (LinearLocation, Roadway) = Seq(combLink1).map(toRoadwayAndLinearLocation).head
      val (lc2, rw2): (LinearLocation, Roadway) = Seq(combLink2).map(toRoadwayAndLinearLocation).head
      val (lc3, rw3): (LinearLocation, Roadway) = Seq(combLink3).map(toRoadwayAndLinearLocation).head
      val rw1WithId = rw1.copy(addrMRange = AddrMRange(0,10), ely = 8L)
      val rw2WithId = rw2.copy(addrMRange = AddrMRange(0,10), ely = 8L)
      val rw3WithId = rw3.copy(addrMRange = AddrMRange(0,10), ely = 8L)

      buildTestDataForProject(Some(project), Some(Seq(rw1WithId, rw2WithId, rw3WithId)), Some(Seq(lc1, lc2, lc3)), Some(Seq(combLink1, combLink2)))

      val projectChanges = List(
        //  combined
        ProjectRoadwayChange(projectId, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(RoadAddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, Some(AdministrativeClass.State), Some(Discontinuity.Continuous), Some(8L)),
            RoadwayChangeSection(Some(road), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(part1), endRoadPartNumber = Some(part1), addrMRange = Some(AddrMRange(0L,10L)), Some(AdministrativeClass.State), Some(Discontinuity.Continuous), Some(8L)),
            Discontinuity.Continuous, AdministrativeClass.State, reversed = false, 1, 8)
          , DateTime.now),
        ProjectRoadwayChange(projectId, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(RoadAddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, Some(AdministrativeClass.State), Some(Discontinuity.Continuous), Some(8L)),
            RoadwayChangeSection(Some(road), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(part2), endRoadPartNumber = Some(part2), addrMRange = Some(AddrMRange(0L,10L)), Some(AdministrativeClass.State), Some(Discontinuity.Discontinuous), Some(8L)),
            Discontinuity.Discontinuous, AdministrativeClass.State, reversed = false, 2, 8)
          , DateTime.now),
        ProjectRoadwayChange(projectId, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(RoadAddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, Some(AdministrativeClass.State), Some(Discontinuity.EndOfRoad), Some(8L)),
            RoadwayChangeSection(Some(road), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(part3), endRoadPartNumber = Some(part3), addrMRange = Some(AddrMRange(0L,10L)), Some(AdministrativeClass.State), Some(Discontinuity.EndOfRoad), Some(8L)),
            Discontinuity.EndOfRoad, AdministrativeClass.State, reversed = false, 3, 8)
          , DateTime.now)
      )

      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(any[BoundingRectangle], any[Seq[(Int, Int)]])).thenReturn(Seq(lc1, lc2, lc3))
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(any[Set[Long]], any[Boolean])).thenReturn(Seq(rw1WithId, rw2WithId, rw3WithId))

      val mappedReservedRoadwayNumbers = projectLinkDAO.fetchProjectLinksChange(projectId)
      roadAddressService.handleRoadwayPointsUpdate(projectChanges, mappedReservedRoadwayNumbers)
      nodesAndJunctionsService.handleNodePoints(projectChanges, combPLinks, mappedReservedRoadwayNumbers)
      nodesAndJunctionsService.handleJunctionAndJunctionPoints(projectChanges, combPLinks, mappedReservedRoadwayNumbers)
      nodesAndJunctionsService.expireObsoleteNodesAndJunctions(combPLinks, Some(project.startDate.minusDays(1)))

      val roadwayPoints = roadwayPointDAO.fetchByRoadwayNumbers(combPLinks.map(_.roadwayNumber)).map(_.id)
      val junctionPointTemplates = junctionPointDAO.fetchByRoadwayPointIds(roadwayPoints)

      junctionPointTemplates.length should be(3)
      junctionPointTemplates.count(_.beforeAfter == BeforeAfter.Before) should be(2)
      junctionPointTemplates.count(_.beforeAfter == BeforeAfter.After) should be(1)
      val templateRoadwayNumbers = junctionPointTemplates.map(_.roadwayNumber).distinct
      val junctions = junctionDAO.fetchTemplatesByRoadwayNumbers(templateRoadwayNumbers)
      junctions.size should be(1)
    }
  }

  test("Test nodesAndJunctionsService.expireObsoleteNodesAndJunctions When changing the EndOfRoad link of last part to Discontinuous and creating a new one with EndOfRoad that does not connect to the same road Then the existing Junction and his points should not be expired.") {
    runWithRollback {
      /*
               |
               |  C3
               |
               v
        --C1-->0--C2-->0--C4-->

        * Note:
            0: Illustration where junction points should be created
            C: Combined track
        */

      val road = 999L
      val part1 = 1L
      val part2 = 2L
      val part3 = 3L
      val part4 = 4L
      val projectId = Sequences.nextViiteProjectId

      val combGeom1 = Seq(Point(0.0, 0.0), Point(10.0, 0.0))
      val combGeom2 = Seq(Point(10.0, 0.0), Point(20.0, 0.0))
      val combGeom3 = Seq(Point(10.0, 10.0), Point(10.0, 0.0))
      val combGeom4 = Seq(Point(20.0, 0.0), Point(30.0, 0.0))

      val combLink1 = dummyProjectLink(RoadPart(road, part1), Track.Combined, Discontinuity.Continuous,    AddrMRange(0, 10), AddrMRange(0, 10), Some(DateTime.now()), None, 12345.toString, 0, 10, SideCode.TowardsDigitizing, RoadAddressChangeType.New, projectId, AdministrativeClass.State, combGeom1, Sequences.nextRoadwayNumber).copy(id = Sequences.nextProjectLinkId, projectId = projectId, roadwayId = Sequences.nextRoadwayId, linearLocationId = Sequences.nextLinearLocationId)
      val combLink2 = dummyProjectLink(RoadPart(road, part2), Track.Combined, Discontinuity.Discontinuous, AddrMRange(0, 10), AddrMRange(0, 10), Some(DateTime.now()), None, 12346.toString, 0, 10, SideCode.TowardsDigitizing, RoadAddressChangeType.New, projectId, AdministrativeClass.State, combGeom2, Sequences.nextRoadwayNumber).copy(id = Sequences.nextProjectLinkId, projectId = projectId, roadwayId = Sequences.nextRoadwayId, linearLocationId = Sequences.nextLinearLocationId)
      val combLink3 = dummyProjectLink(RoadPart(road, part3), Track.Combined, Discontinuity.Discontinuous, AddrMRange(0, 10), AddrMRange(0, 10), Some(DateTime.now()), None, 12347.toString, 0, 10, SideCode.TowardsDigitizing, RoadAddressChangeType.New, projectId, AdministrativeClass.State, combGeom3, Sequences.nextRoadwayNumber).copy(id = Sequences.nextProjectLinkId, projectId = projectId, roadwayId = Sequences.nextRoadwayId, linearLocationId = Sequences.nextLinearLocationId)
      val combLink4 = dummyProjectLink(RoadPart(road, part4), Track.Combined, Discontinuity.EndOfRoad,     AddrMRange(0, 10), AddrMRange(0, 10), Some(DateTime.now()), None, 12346.toString, 0, 10, SideCode.TowardsDigitizing, RoadAddressChangeType.New, projectId, AdministrativeClass.State, combGeom4, Sequences.nextRoadwayNumber).copy(id = Sequences.nextProjectLinkId, projectId = projectId, roadwayId = Sequences.nextRoadwayId, linearLocationId = Sequences.nextLinearLocationId)

      val project = Project(projectId, ProjectState.Incomplete, "f", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
        "", Seq(), Seq(), None, None)

      val combPLinks = Seq(combLink1, combLink2, combLink3, combLink4)

      val (lc1, rw1): (LinearLocation, Roadway) = Seq(combLink1).map(toRoadwayAndLinearLocation).head
      val (lc2, rw2): (LinearLocation, Roadway) = Seq(combLink2).map(toRoadwayAndLinearLocation).head
      val (lc3, rw3): (LinearLocation, Roadway) = Seq(combLink3).map(toRoadwayAndLinearLocation).head
      val (lc4, rw4): (LinearLocation, Roadway) = Seq(combLink4).map(toRoadwayAndLinearLocation).head

      buildTestDataForProject(Some(project), Some(Seq(rw1, rw2, rw3, rw4)), Some(Seq(lc1, lc2, lc3, lc4)), Some(Seq(combLink1, combLink2, combLink3, combLink4)))

      val projectChanges = List(
        //  combined
        ProjectRoadwayChange(projectId, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(RoadAddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, Some(AdministrativeClass.State), Some(Discontinuity.Continuous), Some(8L)),
            RoadwayChangeSection(Some(road), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(part1), endRoadPartNumber = Some(part1), addrMRange = Some(AddrMRange(0L,10L)), Some(AdministrativeClass.State), Some(Discontinuity.Continuous), Some(8L)),
            Discontinuity.Continuous, AdministrativeClass.State, reversed = false, 1, 8)
          , DateTime.now),
        ProjectRoadwayChange(projectId, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(RoadAddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, Some(AdministrativeClass.State), Some(Discontinuity.Continuous), Some(8L)),
            RoadwayChangeSection(Some(road), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(part2), endRoadPartNumber = Some(part2), addrMRange = Some(AddrMRange(0L,10L)), Some(AdministrativeClass.State), Some(Discontinuity.Discontinuous), Some(8L)),
            Discontinuity.Discontinuous, AdministrativeClass.State, reversed = false, 2, 8)
          , DateTime.now),
        ProjectRoadwayChange(projectId, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(RoadAddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, Some(AdministrativeClass.State), Some(Discontinuity.EndOfRoad), Some(8L)),
            RoadwayChangeSection(Some(road), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(part3), endRoadPartNumber = Some(part3), addrMRange = Some(AddrMRange(0L,10L)), Some(AdministrativeClass.State), Some(Discontinuity.Discontinuous), Some(8L)),
            Discontinuity.Discontinuous, AdministrativeClass.State, reversed = false, 3, 8)
          , DateTime.now),
        ProjectRoadwayChange(projectId, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(RoadAddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, Some(AdministrativeClass.State), Some(Discontinuity.Continuous), Some(8L)),
            RoadwayChangeSection(Some(road), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(part4), endRoadPartNumber = Some(part4), addrMRange = Some(AddrMRange(0L,10L)), Some(AdministrativeClass.State), Some(Discontinuity.EndOfRoad), Some(8L)),
            Discontinuity.EndOfRoad, AdministrativeClass.State, reversed = false, 4, 8)
          , DateTime.now)
      )

      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(any[BoundingRectangle], any[Seq[(Int, Int)]])).thenReturn(Seq(lc1, lc2, lc3, lc4))
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(any[Set[Long]], any[Boolean])).thenReturn(Seq(rw1, rw2, rw3, rw4))

      val mappedReservedRoadwayNumbers = projectLinkDAO.fetchProjectLinksChange(projectId)
      roadAddressService.handleRoadwayPointsUpdate(projectChanges, mappedReservedRoadwayNumbers)
      nodesAndJunctionsService.handleNodePoints(projectChanges, combPLinks, mappedReservedRoadwayNumbers)
      nodesAndJunctionsService.handleJunctionAndJunctionPoints(projectChanges, combPLinks, mappedReservedRoadwayNumbers)
      nodesAndJunctionsService.expireObsoleteNodesAndJunctions(combPLinks, Some(project.startDate.minusDays(1)))

      val roadwayPoints = roadwayPointDAO.fetchByRoadwayNumbers(combPLinks.map(_.roadwayNumber)).map(_.id)
      val junctionPointTemplates = junctionPointDAO.fetchByRoadwayPointIds(roadwayPoints)

      junctionPointTemplates.length should be(5)
      junctionPointTemplates.count(_.beforeAfter == BeforeAfter.Before) should be(3)
      junctionPointTemplates.count(_.beforeAfter == BeforeAfter.After) should be(2)
      junctionPointTemplates.map(_.junctionId).distinct.size should be (2)
    }
  }

  test("Test nodesAndJunctionsService.handleJunctionTemplates When creating new road parts that connects to other existing part in the beginning point of its geometry which link is EndOfRoad and ends in some link of this new roads in same road number Then junction template and junctions points should be handled/created properly." +
    "Test nodesAndJunctionsService.expireObsoleteNodesAndJunctions When changing the EndOfRoad link of last part to Discontinuous and creating a new one with EndOfRoad that does not connect to the same road Then the existing Junction and his points should be expired.") {
    runWithRollback {
      /*
              |
          C3  |
              |
              v
              0--C1-->--C2-->

        * Notes:
            0: Illustration where junction points should be created
            C: Combined track
        */

      val road = 999L
      val part1 = 1L
      val part2 = 2L
      val part3 = 3L
      val projectId = Sequences.nextViiteProjectId
      val rwId = Sequences.nextRoadwayId
      val llId = Sequences.nextLinearLocationId
      val rwNumber = Sequences.nextRoadwayNumber
      val plId = Sequences.nextProjectLinkId

      val combGeom1 = Seq(Point(0.0, 0.0), Point(10.0, 0.0))
      val combGeom2 = Seq(Point(10.0, 0.0), Point(20.0, 0.0))
      val combGeom3 = Seq(Point(0.0, 10.0), Point(0.0, 0.0))

      val combLink1 = dummyProjectLink(RoadPart(road, part1), Track.Combined, Discontinuity.Continuous,    AddrMRange(0, 10), AddrMRange(0, 10), Some(DateTime.now()), None, 12345.toString, 0, 10, SideCode.TowardsDigitizing, RoadAddressChangeType.Transfer, projectId, AdministrativeClass.State, combGeom1, rwNumber    ).copy(id = plId,     projectId = projectId, roadwayId = rwId,     linearLocationId = llId)
      val combLink2 = dummyProjectLink(RoadPart(road, part2), Track.Combined, Discontinuity.Discontinuous, AddrMRange(0, 10), AddrMRange(0, 10), Some(DateTime.now()), None, 12346.toString, 0, 10, SideCode.TowardsDigitizing, RoadAddressChangeType.Transfer, projectId, AdministrativeClass.State, combGeom2, rwNumber + 1).copy(id = plId + 1, projectId = projectId, roadwayId = rwId + 1, linearLocationId = llId + 1)
      val combLink3 = dummyProjectLink(RoadPart(road, part3), Track.Combined, Discontinuity.EndOfRoad,     AddrMRange(0, 10), AddrMRange(0, 10), Some(DateTime.now()), None, 12347.toString, 0, 10, SideCode.TowardsDigitizing, RoadAddressChangeType.Transfer, projectId, AdministrativeClass.State, combGeom3, rwNumber + 2).copy(id = plId + 2, projectId = projectId, roadwayId = rwId + 2, linearLocationId = llId + 2)

      val project = Project(projectId, ProjectState.Incomplete, "f", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
        "", Seq(), Seq(), None, None)

      val combPLinks = Seq(combLink1, combLink2, combLink3)

      val (lc1, rw1): (LinearLocation, Roadway) = Seq(combLink1).map(toRoadwayAndLinearLocation).head
      val (lc2, rw2): (LinearLocation, Roadway) = Seq(combLink2).map(toRoadwayAndLinearLocation).head
      val (lc3, rw3): (LinearLocation, Roadway) = Seq(combLink3).map(toRoadwayAndLinearLocation).head
      val rw1WithId = rw1.copy(addrMRange = AddrMRange(0,10), ely = 8L)
      val rw2WithId = rw2.copy(addrMRange = AddrMRange(0,10), ely = 8L)
      val rw3WithId = rw3.copy(addrMRange = AddrMRange(0,10), ely = 8L)

      buildTestDataForProject(Some(project), Some(Seq(rw1WithId, rw2WithId, rw3WithId)), Some(Seq(lc1, lc2, lc3)), Some(combPLinks))

      val projectChanges = List(
        ProjectRoadwayChange(projectId, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(RoadAddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, Some(AdministrativeClass.State), Some(Discontinuity.Continuous), Some(8L)),
            RoadwayChangeSection(Some(road), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(part1), endRoadPartNumber = Some(part1), addrMRange = Some(AddrMRange(0L,10L)), Some(AdministrativeClass.State), Some(Discontinuity.Continuous), Some(8L)),
            Discontinuity.Continuous, AdministrativeClass.State, reversed = false, 1, 8)
          , DateTime.now),
        ProjectRoadwayChange(projectId, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(RoadAddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, Some(AdministrativeClass.State), Some(Discontinuity.Continuous), Some(8L)),
            RoadwayChangeSection(Some(road), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(part2), endRoadPartNumber = Some(part2), addrMRange = Some(AddrMRange(0L,10L)), Some(AdministrativeClass.State), Some(Discontinuity.Discontinuous), Some(8L)),
            Discontinuity.Discontinuous, AdministrativeClass.State, reversed = false, 2, 8)
          , DateTime.now),
        ProjectRoadwayChange(projectId, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(RoadAddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, Some(AdministrativeClass.State), Some(Discontinuity.Continuous), Some(8L)),
            RoadwayChangeSection(Some(road), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(part3), endRoadPartNumber = Some(part3), addrMRange = Some(AddrMRange(0L,10L)), Some(AdministrativeClass.State), Some(Discontinuity.EndOfRoad), Some(8L)),
            Discontinuity.EndOfRoad, AdministrativeClass.State, reversed = false, 3, 8)
          , DateTime.now)
      )

      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(combGeom1.head, combGeom1.head), roadNumberLimits)).thenReturn(Seq(lc1, lc3))
      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(combGeom1.last, combGeom1.last), roadNumberLimits)).thenReturn(Seq(lc1, lc2))
      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(combGeom2.last, combGeom2.last), roadNumberLimits)).thenReturn(Seq(lc2))
      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(combGeom3.head, combGeom3.head), roadNumberLimits)).thenReturn(Seq(lc3))

      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(Set(lc1.roadwayNumber), withHistory=false)).thenReturn(Seq(rw1WithId))
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(Set(lc1.roadwayNumber, lc3.roadwayNumber), withHistory=false)).thenReturn(Seq(rw1WithId, rw3WithId))
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(Set(lc1.roadwayNumber, lc2.roadwayNumber), withHistory=false)).thenReturn(Seq(rw1WithId, rw2WithId))
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(Set(lc2.roadwayNumber), withHistory=false)).thenReturn(Seq(rw2WithId))
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(Set(lc3.roadwayNumber), withHistory=false)).thenReturn(Seq(rw3WithId))

      val mappedReservedRoadwayNumbers = projectLinkDAO.fetchProjectLinksChange(projectId)
      roadAddressService.handleRoadwayPointsUpdate(projectChanges, mappedReservedRoadwayNumbers)
      nodesAndJunctionsService.handleNodePoints(projectChanges, combPLinks, mappedReservedRoadwayNumbers)
      nodesAndJunctionsService.handleJunctionAndJunctionPoints(projectChanges, combPLinks, mappedReservedRoadwayNumbers)
      nodesAndJunctionsService.expireObsoleteNodesAndJunctions(combPLinks, Some(project.startDate.minusDays(1)))

      val roadwayPoints = roadwayPointDAO.fetchByRoadwayNumbers(combPLinks.map(_.roadwayNumber)).map(_.id)
      val junctionPointTemplates = junctionPointDAO.fetchByRoadwayPointIds(roadwayPoints)

      junctionPointTemplates.length should be(2)
      junctionPointTemplates.count(_.beforeAfter == BeforeAfter.Before) should be(1)
      junctionPointTemplates.count(_.beforeAfter == BeforeAfter.After) should be(1)
      val junctions = junctionDAO.fetchTemplatesByRoadwayNumbers(combPLinks.map(_.roadwayNumber))
      junctions.size should be(1)

      /* Preparing project changes:
              |
          C3  |
              |
              v
              0--C1-->--C2-->0--C4-->

        * Notes:
            0: Illustration where junction points should be created
            C: Combined track
        */
      val project2 = Project(projectId + 1, ProjectState.Incomplete, "ProjectNewEndOfRoadLinks", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
        "", Seq(), Seq(), None, None)
      val newProjectChanges = List(
        ProjectRoadwayChange(projectId + 1, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(RoadAddressChangeType.Unchanged,
            RoadwayChangeSection(Some(road), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(part3), endRoadPartNumber = Some(part3), addrMRange = Some(AddrMRange(0L,10L)), Some(AdministrativeClass.State), Some(Discontinuity.EndOfRoad), Some(8L)),
            RoadwayChangeSection(Some(road), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(part3), endRoadPartNumber = Some(part3), addrMRange = Some(AddrMRange(0L,10L)), Some(AdministrativeClass.State), Some(Discontinuity.Discontinuous), Some(8L)),
            Discontinuity.Discontinuous, AdministrativeClass.State, reversed = false, 1, 8)
          , DateTime.now),
        ProjectRoadwayChange(projectId + 1, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(RoadAddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, Some(AdministrativeClass.State), Some(Discontinuity.Continuous), Some(8L)),
            RoadwayChangeSection(Some(road), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(4L), endRoadPartNumber = Some(4L), addrMRange = Some(AddrMRange(0L,10L)), Some(AdministrativeClass.State), Some(Discontinuity.EndOfRoad), Some(8L)),
            Discontinuity.EndOfRoad, AdministrativeClass.State, reversed = false, 2, 8)
          , DateTime.now)
      )

      linearLocationDAO.expireByRoadwayNumbers(Set(combLink3.roadwayNumber))
      roadwayDAO.expireHistory(Set(combLink3.roadwayId))
      projectLinkDAO.moveProjectLinksToHistory(projectId)
      val combGeom4 = Seq(Point(20.0, 0.0), Point(30.0, 0.0))

      val transferLink = dummyProjectLink(RoadPart(road, part3), Track.Combined, Discontinuity.Discontinuous, AddrMRange(0, 10), AddrMRange(0, 10), Some(DateTime.now()), None, 12347.toString, 0, 10, SideCode.TowardsDigitizing, RoadAddressChangeType.Unchanged, projectId + 1, AdministrativeClass.State, combGeom3, rwNumber + 2).copy(id = plId + 3, roadwayId = rwId + 3, linearLocationId = llId + 3)
      val newLink      = dummyProjectLink(RoadPart(road,     4), Track.Combined, Discontinuity.EndOfRoad,     AddrMRange(0, 10), AddrMRange(0, 10), Some(DateTime.now()), None, 12348.toString, 0, 10, SideCode.TowardsDigitizing, RoadAddressChangeType.New,       projectId + 1, AdministrativeClass.State, combGeom4, rwNumber + 3).copy(id = plId + 4, roadwayId = rwId + 4, linearLocationId = llId + 4)

      val (lc4, rw4): (LinearLocation, Roadway) = Seq(newLink).map(toRoadwayAndLinearLocation).head
      val rw3WithDiscontinuity = rw3WithId.copy(id = transferLink.roadwayId, roadwayNumber = transferLink.roadwayNumber, discontinuity = Discontinuity.Discontinuous)
      val rw4WithId = rw4.copy(id = newLink.roadwayId, roadwayNumber = newLink.roadwayNumber, addrMRange = AddrMRange(0,10), ely = 8L)

      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(combGeom2.last, combGeom2.last), roadNumberLimits)).thenReturn(Seq(lc2, lc4))
      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(combGeom4.last, combGeom4.last), roadNumberLimits)).thenReturn(Seq(lc4))

      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(Set(transferLink.roadwayNumber), withHistory=false)).thenReturn(Seq(rw3WithDiscontinuity))
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(Set(lc2.roadwayNumber, newLink.roadwayNumber), withHistory=false)).thenReturn(Seq(rw2WithId, rw4WithId))
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(Set(newLink.roadwayNumber), withHistory=false)).thenReturn(Seq(rw4WithId))

      buildTestDataForProject(Some(project2),
        Some(Seq(rw3WithDiscontinuity, rw4WithId)),
        Some(Seq(lc3.copy(id = transferLink.linearLocationId, roadwayNumber = transferLink.roadwayNumber),
          lc4.copy(id = newLink.linearLocationId, roadwayNumber = newLink.roadwayNumber))),
        Some(Seq(transferLink, newLink)))

      val mappedAfterNewRoadwayNumber = projectLinkDAO.fetchProjectLinksChange(projectId + 1)
      projectLinkDAO.moveProjectLinksToHistory(projectId + 1)

      val combPLinks2 = Seq(combLink1, combLink2, transferLink, newLink)
      roadAddressService.handleRoadwayPointsUpdate(newProjectChanges, mappedAfterNewRoadwayNumber)
      nodesAndJunctionsService.handleNodePoints(newProjectChanges, combPLinks2, mappedAfterNewRoadwayNumber)
      nodesAndJunctionsService.handleJunctionAndJunctionPoints(newProjectChanges, combPLinks2, mappedAfterNewRoadwayNumber)
      nodesAndJunctionsService.expireObsoleteNodesAndJunctions(combPLinks2, Some(project2.startDate.minusDays(1)))

      val roadwayPointsAfterChanges = roadwayPointDAO.fetchByRoadwayNumbers(combPLinks2.map(_.roadwayNumber)).map(_.id)
      val junctionPointTemplates2 = junctionPointDAO.fetchByRoadwayPointIds(roadwayPointsAfterChanges)

      junctionPointTemplates2.length should be(4)
      junctionPointTemplates2.count(_.beforeAfter == BeforeAfter.Before) should be(2)
      junctionPointTemplates2.count(_.beforeAfter == BeforeAfter.After) should be(2)
      val junctions2 = junctionDAO.fetchTemplatesByRoadwayNumbers(combPLinks2.map(_.roadwayNumber))
      junctions2.size should be(2)
    }
  }

  test("Test nodesAndJunctionsService.handleJunctionTemplates When creating new road parts that connects to other existing part in the ending point of its geometry which link is EndOfRoad and ends in some link of this new roads in same road number Then junction template and junctions points should be handled/created properly." +
    "Test nodesAndJunctionsService.expireObsoleteNodesAndJunctions When changing the EndOfRoad link of last part to Continuous and creating a new one with EndOfRoad that does not connect to the same road Then the existing Junction and his points should be expired.") {
    runWithRollback {
      /*
                        |
                        | C3
                        |
                        v
          --C1-->--C2-->0

        * Notes:
            0: Illustration where junction points should be created
            C: Combined track
        */
      val road = 999L
      val part1 = 1L
      val part2 = 2L
      val part3 = 3L
      val projectId = Sequences.nextViiteProjectId
      val rwId = Sequences.nextRoadwayId
      val llId = Sequences.nextLinearLocationId
      val rwNumber = Sequences.nextRoadwayNumber
      val plId = Sequences.nextProjectLinkId

      val combGeom1 = Seq(Point(0.0, 0.0), Point(10.0, 0.0))
      val combGeom2 = Seq(Point(10.0, 0.0), Point(20.0, 0.0))
      val combGeom3 = Seq(Point(20.0, 10.0), Point(20.0, 0.0))

      val combLink1 = dummyProjectLink(RoadPart(road, part1), Track.Combined, Discontinuity.Continuous,    AddrMRange(0, 10), AddrMRange(0, 10), Some(DateTime.now()), None, 12345.toString, 0, 10, SideCode.TowardsDigitizing, RoadAddressChangeType.Transfer, projectId, AdministrativeClass.State, combGeom1, rwNumber    ).copy(id = plId,     projectId = projectId, roadwayId = rwId,     linearLocationId = llId)
      val combLink2 = dummyProjectLink(RoadPart(road, part2), Track.Combined, Discontinuity.Discontinuous, AddrMRange(0, 10), AddrMRange(0, 10), Some(DateTime.now()), None, 12346.toString, 0, 10, SideCode.TowardsDigitizing, RoadAddressChangeType.Transfer, projectId, AdministrativeClass.State, combGeom2, rwNumber + 1).copy(id = plId + 1, projectId = projectId, roadwayId = rwId + 1, linearLocationId = llId + 1)
      val combLink3 = dummyProjectLink(RoadPart(road, part3), Track.Combined, Discontinuity.EndOfRoad,     AddrMRange(0, 10), AddrMRange(0, 10), Some(DateTime.now()), None, 12347.toString, 0, 10, SideCode.TowardsDigitizing, RoadAddressChangeType.Transfer, projectId, AdministrativeClass.State, combGeom3, rwNumber + 2).copy(id = plId + 2, projectId = projectId, roadwayId = rwId + 2, linearLocationId = llId + 2)

      val project = Project(projectId, ProjectState.Incomplete, "f", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
        "", Seq(), Seq(), None, None)

      val combPLinks = Seq(combLink1, combLink2, combLink3)

      val (lc1, rw1): (LinearLocation, Roadway) = Seq(combLink1).map(toRoadwayAndLinearLocation).head
      val (lc2, rw2): (LinearLocation, Roadway) = Seq(combLink2).map(toRoadwayAndLinearLocation).head
      val (lc3, rw3): (LinearLocation, Roadway) = Seq(combLink3).map(toRoadwayAndLinearLocation).head
      val rw1WithId = rw1.copy(addrMRange = AddrMRange(0,10), ely = 8L)
      val rw2WithId = rw2.copy(addrMRange = AddrMRange(0,10), ely = 8L)
      val rw3WithId = rw3.copy(addrMRange = AddrMRange(0,10), ely = 8L)

      buildTestDataForProject(Some(project), Some(Seq(rw1WithId, rw2WithId, rw3WithId)), Some(Seq(lc1, lc2, lc3)), Some(Seq(combLink1, combLink2)))

      val projectChanges = List(
        //  Combined
        ProjectRoadwayChange(projectId, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(RoadAddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, Some(AdministrativeClass.State), Some(Discontinuity.Continuous), Some(8L)),
            RoadwayChangeSection(Some(road), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(part1), endRoadPartNumber = Some(part1), addrMRange = Some(AddrMRange(0L,10L)), Some(AdministrativeClass.State), Some(Discontinuity.Continuous), Some(8L)),
            Discontinuity.Continuous, AdministrativeClass.State, reversed = false, 1, 8)
          , DateTime.now),
        ProjectRoadwayChange(projectId, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(RoadAddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, Some(AdministrativeClass.State), Some(Discontinuity.Continuous), Some(8L)),
            RoadwayChangeSection(Some(road), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(part2), endRoadPartNumber = Some(part2), addrMRange = Some(AddrMRange(0L,10L)), Some(AdministrativeClass.State), Some(Discontinuity.Discontinuous), Some(8L)),
            Discontinuity.Discontinuous, AdministrativeClass.State, reversed = false, 2, 8)
          , DateTime.now),
        ProjectRoadwayChange(projectId, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(RoadAddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, Some(AdministrativeClass.State), Some(Discontinuity.Continuous), Some(8L)),
            RoadwayChangeSection(Some(road), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(part3), endRoadPartNumber = Some(part3), addrMRange = Some(AddrMRange(0L,10L)), Some(AdministrativeClass.State), Some(Discontinuity.EndOfRoad), Some(8L)),
            Discontinuity.EndOfRoad, AdministrativeClass.State, reversed = false, 3, 8)
          , DateTime.now)
      )

      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(combGeom1.head, combGeom1.head), roadNumberLimits)).thenReturn(Seq(lc1))
      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(combGeom1.last, combGeom1.last), roadNumberLimits)).thenReturn(Seq(lc1, lc2))
      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(combGeom2.last, combGeom2.last), roadNumberLimits)).thenReturn(Seq(lc2, lc3))
      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(combGeom3.head, combGeom3.head), roadNumberLimits)).thenReturn(Seq(lc3))

      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(Set(lc1.roadwayNumber), withHistory=false)).thenReturn(Seq(rw1WithId))
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(Set(lc3.roadwayNumber), withHistory=false)).thenReturn(Seq(rw3WithId))
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(Set(lc1.roadwayNumber, lc2.roadwayNumber), withHistory=false)).thenReturn(Seq(rw1WithId, rw2WithId))
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(Set(lc2.roadwayNumber, lc3.roadwayNumber), withHistory=false)).thenReturn(Seq(rw2WithId, rw3WithId))

      val mappedReservedRoadwayNumbers = projectLinkDAO.fetchProjectLinksChange(projectId)
      roadAddressService.handleRoadwayPointsUpdate(projectChanges, mappedReservedRoadwayNumbers)
      nodesAndJunctionsService.handleNodePoints(projectChanges, combPLinks, mappedReservedRoadwayNumbers)
      nodesAndJunctionsService.handleJunctionAndJunctionPoints(projectChanges, combPLinks, mappedReservedRoadwayNumbers)

      val roadwayPoints = roadwayPointDAO.fetchByRoadwayNumbers(combPLinks.map(_.roadwayNumber)).map(_.id)
      val junctionPointTemplates = junctionPointDAO.fetchByRoadwayPointIds(roadwayPoints)

      junctionPointTemplates.length should be(2)
      junctionPointTemplates.count(_.beforeAfter == BeforeAfter.Before) should be(2)

      val junctions = junctionDAO.fetchTemplatesByRoadwayNumbers(combPLinks.map(_.roadwayNumber))
      junctions.size should be(1)

      /* Preparing project changes:
                        |
                        | C3
                        |
                        v
          --C1-->--C2-->0--C4-->

        * Notes:
            0: Illustration where junction points should be created
            C: Combined track
        */
      val project2 = Project(projectId + 1, ProjectState.Incomplete, "ProjectNewEndOfRoadLinks", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
        "", Seq(), Seq(), None, None)
      val projectChanges2 = List(
        ProjectRoadwayChange(projectId + 1, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(RoadAddressChangeType.Unchanged,
            RoadwayChangeSection(Some(road), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(3L), endRoadPartNumber = Some(3L), addrMRange = Some(AddrMRange(0L,10L)), Some(AdministrativeClass.State), Some(Discontinuity.EndOfRoad), Some(8L)),
            RoadwayChangeSection(Some(road), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(3L), endRoadPartNumber = Some(3L), addrMRange = Some(AddrMRange(0L,10L)), Some(AdministrativeClass.State), Some(Discontinuity.Discontinuous), Some(8L)),
            Discontinuity.Discontinuous, AdministrativeClass.State, reversed = false, 1, 8)
          , DateTime.now),
        ProjectRoadwayChange(projectId + 1, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(RoadAddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, Some(AdministrativeClass.State), Some(Discontinuity.Continuous), Some(8L)),
            RoadwayChangeSection(Some(road), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(4L), endRoadPartNumber = Some(4L), addrMRange = Some(AddrMRange(0L,10L)), Some(AdministrativeClass.State), Some(Discontinuity.EndOfRoad), Some(8L)),
            Discontinuity.EndOfRoad, AdministrativeClass.State, reversed = false, 2, 8)
          , DateTime.now)
      )

      linearLocationDAO.expireByRoadwayNumbers(Set(combLink3.roadwayNumber))
      roadwayDAO.expireHistory(Set(combLink3.roadwayId))
      projectLinkDAO.moveProjectLinksToHistory(projectId)
      val combGeom4 = Seq(Point(20.0, 0.0), Point(30.0, 0.0))

      val transferLink = dummyProjectLink(RoadPart(road, 3), Track.Combined, Discontinuity.Discontinuous, AddrMRange(0, 10), AddrMRange(0, 10), Some(DateTime.now()), None, 12347.toString, 0, 10, SideCode.TowardsDigitizing, RoadAddressChangeType.Unchanged, projectId + 1, AdministrativeClass.State, combGeom3, rwNumber + 2).copy(id = plId + 3, roadwayId = rwId + 3, linearLocationId = llId + 3)
      val newLink      = dummyProjectLink(RoadPart(road, 4), Track.Combined, Discontinuity.EndOfRoad,     AddrMRange(0, 10), AddrMRange(0, 10), Some(DateTime.now()), None, 12348.toString, 0, 10, SideCode.TowardsDigitizing, RoadAddressChangeType.New, projectId + 1, AdministrativeClass.State, combGeom4, rwNumber + 3).copy(id = plId + 4, roadwayId = rwId + 4, linearLocationId = llId + 4)

      val (lc4, rw4): (LinearLocation, Roadway) = Seq(newLink).map(toRoadwayAndLinearLocation).head
      val rw3WithDiscontinuity = rw3WithId.copy(id = transferLink.roadwayId, roadwayNumber = transferLink.roadwayNumber, discontinuity = Discontinuity.Discontinuous)
      val rw4WithId = rw4.copy(id = newLink.roadwayId, roadwayNumber = newLink.roadwayNumber, addrMRange = AddrMRange(0,10), ely = 8L)


      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(combGeom2.last, combGeom2.last), roadNumberLimits)).thenReturn(Seq(lc2, lc3, lc4))
      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(combGeom4.last, combGeom4.last), roadNumberLimits)).thenReturn(Seq(lc4))

      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(Set(transferLink.roadwayNumber), withHistory=false)).thenReturn(Seq(rw3WithDiscontinuity))
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(Set(newLink.roadwayNumber), withHistory=false)).thenReturn(Seq(rw4WithId))
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(Set(lc2.roadwayNumber, transferLink.roadwayNumber, newLink.roadwayNumber), withHistory=false)).thenReturn(Seq(rw2WithId, rw3WithId, rw4WithId))

      buildTestDataForProject(Some(project2),
        Some(Seq(rw3WithDiscontinuity, rw4WithId)),
        Some(Seq(lc3.copy(id = transferLink.linearLocationId, roadwayNumber = transferLink.roadwayNumber),
          lc4.copy(id = newLink.linearLocationId, roadwayNumber = newLink.roadwayNumber))),
        Some(Seq(transferLink, newLink)))

      projectLinkDAO.moveProjectLinksToHistory(projectId + 1)

      val combPLinks2 = Seq(transferLink, newLink)
      val mappedAfterNewRoadwayNumber = projectLinkDAO.fetchProjectLinksChange(projectId + 1)
      roadAddressService.handleRoadwayPointsUpdate(projectChanges2, mappedAfterNewRoadwayNumber)
      nodesAndJunctionsService.handleNodePoints(projectChanges2, combPLinks2, mappedAfterNewRoadwayNumber)
      nodesAndJunctionsService.handleJunctionAndJunctionPoints(projectChanges2, combPLinks2, mappedAfterNewRoadwayNumber)
      nodesAndJunctionsService.expireObsoleteNodesAndJunctions(combPLinks2, Some(project2.startDate.minusDays(1)))

      val rwPoints = roadwayPointDAO.fetchByRoadwayNumbers((combPLinks ++ combPLinks2).map(_.roadwayNumber)).map(_.id)
      val junctionPointsAfterChanges = junctionPointDAO.fetchByRoadwayPointIds(rwPoints)
      junctionPointsAfterChanges.length should be(3)
      junctionPointsAfterChanges.count(_.beforeAfter == BeforeAfter.Before) should be(2)
      junctionPointsAfterChanges.count(_.beforeAfter == BeforeAfter.After) should be(1)

      val junctions2 = junctionDAO.fetchTemplatesByRoadwayNumbers(combPLinks.map(_.roadwayNumber))
      junctions2.size should be(1)
    }
  }

  test("Test nodesAndJunctionsService.handleJunctionTemplates When creating one road part that intersects itself and still all links are continuous Then junction template and junctions points should be handled/created properly." +
    "Test nodesAndJunctionsService.expireObsoleteNodesAndJunctions When expiring the 2 last links that will make the road not intersecting itself Then the existing Junction and its Junction points should be expired.") {
    runWithRollback {
      /*
                   ^
                   |
                   | C5
                   |
          ---C1--> 0
                  ^|
                /  |
          C4  /    | C2
            /      v
          /<--C3---|

        * Note:
            0: Illustration where junction points should be created
            C: Combined track
        */

      val road = 999L
      val part = 1L
      val projectId = Sequences.nextViiteProjectId
      val rwId = Sequences.nextRoadwayId
      val llId = Sequences.nextLinearLocationId
      val rwNumber = Sequences.nextRoadwayNumber
      val plId = Sequences.nextProjectLinkId


      val combGeom1 = Seq(Point(0.0, 5.0), Point(5.0, 5.0))
      val combGeom2 = Seq(Point(5.0, 5.0), Point(5.0, 0.0))
      val combGeom3 = Seq(Point(5.0, 0.0), Point(0.0, 0.0))
      val combGeom4 = Seq(Point(0.0, 0.0), Point(5.0, 5.0))
      val combGeom5 = Seq(Point(5.0, 5.0), Point(5.0, 10.0))

      val combLink1 = dummyProjectLink(RoadPart(road, part), Track.Combined, Discontinuity.Continuous, AddrMRange( 0,  5), AddrMRange( 0,  5), Some(DateTime.now()), None, 12345.toString, 0, 5, SideCode.TowardsDigitizing, RoadAddressChangeType.New, projectId, AdministrativeClass.State, combGeom1, rwNumber).copy(id = plId,     projectId = projectId, roadwayId = rwId, linearLocationId = llId)
      val combLink2 = dummyProjectLink(RoadPart(road, part), Track.Combined, Discontinuity.Continuous, AddrMRange( 5, 10), AddrMRange( 5, 10), Some(DateTime.now()), None, 12346.toString, 0, 5, SideCode.TowardsDigitizing, RoadAddressChangeType.New, projectId, AdministrativeClass.State, combGeom2, rwNumber).copy(id = plId + 1, projectId = projectId, roadwayId = rwId, linearLocationId = llId + 1)
      val combLink3 = dummyProjectLink(RoadPart(road, part), Track.Combined, Discontinuity.Continuous, AddrMRange(10, 15), AddrMRange(10, 15), Some(DateTime.now()), None, 12347.toString, 0, 5, SideCode.TowardsDigitizing, RoadAddressChangeType.New, projectId, AdministrativeClass.State, combGeom3, rwNumber).copy(id = plId + 2, projectId = projectId, roadwayId = rwId, linearLocationId = llId + 2)
      val combLink4 = dummyProjectLink(RoadPart(road, part), Track.Combined, Discontinuity.Continuous, AddrMRange(15, 20), AddrMRange(15, 20), Some(DateTime.now()), None, 12348.toString, 0, 5, SideCode.TowardsDigitizing, RoadAddressChangeType.New, projectId, AdministrativeClass.State, combGeom4, rwNumber).copy(id = plId + 3, projectId = projectId, roadwayId = rwId, linearLocationId = llId + 3)
      val combLink5 = dummyProjectLink(RoadPart(road, part), Track.Combined, Discontinuity.EndOfRoad,  AddrMRange(20, 25), AddrMRange(20, 25), Some(DateTime.now()), None, 12349.toString, 0, 5, SideCode.TowardsDigitizing, RoadAddressChangeType.New, projectId, AdministrativeClass.State, combGeom5, rwNumber).copy(id = plId + 4, projectId = projectId, roadwayId = rwId, linearLocationId = llId + 4)

      val project = Project(projectId, ProjectState.Incomplete, "f", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
        "", Seq(), Seq(), None, None)

      val combPLinks = Seq(combLink1, combLink2, combLink3, combLink4, combLink5)

      val (lc1,   rw1  ): (LinearLocation, Roadway) = Seq(combLink1).map(toRoadwayAndLinearLocation).head
      val (lc2,_/*rw2*/): (LinearLocation, Roadway) = Seq(combLink2).map(toRoadwayAndLinearLocation).head
      val (lc3,_/*rw3*/): (LinearLocation, Roadway) = Seq(combLink3).map(toRoadwayAndLinearLocation).head
      val (lc4,_/*rw4*/): (LinearLocation, Roadway) = Seq(combLink4).map(toRoadwayAndLinearLocation).head
      val (lc5,_/*rw5*/): (LinearLocation, Roadway) = Seq(combLink5).map(toRoadwayAndLinearLocation).head
      val rw1WithId = rw1.copy(addrMRange = AddrMRange(0, 25), ely = 8L)
      val orderedcll1 = lc1.copy(orderNumber = 1)
      val orderedcll2 = lc2.copy(orderNumber = 2)
      val orderedcll3 = lc3.copy(orderNumber = 3)
      val orderedcll4 = lc4.copy(orderNumber = 4)
      val orderedcll5 = lc5.copy(orderNumber = 5)

      buildTestDataForProject(Some(project), Some(Seq(rw1WithId)), Some(Seq(orderedcll1, orderedcll2, orderedcll3, orderedcll4, orderedcll5)), Some(combPLinks))

      val projectChanges = List(
        ProjectRoadwayChange(projectId, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(RoadAddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, Some(AdministrativeClass.State), Some(Discontinuity.Continuous), Some(8L)),
            RoadwayChangeSection(Some(road), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(part), endRoadPartNumber = Some(part), addrMRange = Some(AddrMRange(0L,25L)), Some(AdministrativeClass.State), Some(Discontinuity.EndOfRoad), Some(8L)),
            Discontinuity.EndOfRoad, AdministrativeClass.State, reversed = false, 1, 8)
          , DateTime.now)
      )

      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(any[BoundingRectangle], any[Seq[(Int, Int)]])).thenReturn(Seq(orderedcll1, orderedcll2, orderedcll3, orderedcll4, orderedcll5))
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(any[Set[Long]], any[Boolean])).thenReturn(Seq(rw1WithId))

      val mappedReservedRoadwayNumbers = projectLinkDAO.fetchProjectLinksChange(projectId)
      roadAddressService.handleRoadwayPointsUpdate(projectChanges, mappedReservedRoadwayNumbers)
      nodesAndJunctionsService.handleNodePoints(projectChanges, combPLinks, mappedReservedRoadwayNumbers)
      nodesAndJunctionsService.handleJunctionAndJunctionPoints(projectChanges, combPLinks, mappedReservedRoadwayNumbers)

      val roadwayPoints = roadwayPointDAO.fetchByRoadwayNumbers(combPLinks.map(_.roadwayNumber)).map(_.id)

      val junctionPointTemplates = junctionPointDAO.fetchByRoadwayPointIds(roadwayPoints)
      junctionPointTemplates.length should be(4)
      junctionPointTemplates.count(_.beforeAfter == BeforeAfter.Before) should be(2)
      junctionPointTemplates.count(_.beforeAfter == BeforeAfter.After) should be(2)

      val templateRoadwayNumbers = junctionPointTemplates.map(_.roadwayNumber).distinct

      val junctions = junctionDAO.fetchTemplatesByRoadwayNumbers(templateRoadwayNumbers)
      junctions.size should be(1)

      nodesAndJunctionsService.expireObsoleteNodesAndJunctions(combPLinks, Some(project.startDate.minusDays(1)), username = project.createdBy)

      val shouldExistJunctionPoints = junctionPointDAO.fetchByRoadwayPointIds(roadwayPoints)
      shouldExistJunctionPoints.length should be(4)
      shouldExistJunctionPoints.count(_.beforeAfter == BeforeAfter.Before) should be(2)
      shouldExistJunctionPoints.count(_.beforeAfter == BeforeAfter.After) should be(2)

      val shouldExistJunctionTemplate = junctionDAO.fetchTemplatesByRoadwayNumbers(shouldExistJunctionPoints.map(_.roadwayNumber).distinct)
      shouldExistJunctionTemplate.size should be(1)

      //  Preparing expiring data
      val project2 = Project(projectId + 1, ProjectState.Incomplete, "ProjectTerminatedLinks", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
        "", Seq(), Seq(), None, None)
      val TerminatingProjectChanges = List(
        ProjectRoadwayChange(projectId + 1, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(RoadAddressChangeType.Unchanged,
            RoadwayChangeSection(Some(road), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(part), endRoadPartNumber = Some(part), addrMRange = Some(AddrMRange(0L,10L)), Some(AdministrativeClass.State), Some(Discontinuity.Continuous), Some(8L)),
            RoadwayChangeSection(Some(road), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(part), endRoadPartNumber = Some(part), addrMRange = Some(AddrMRange(0L,10L)), Some(AdministrativeClass.State), Some(Discontinuity.Continuous), Some(8L)),
            Discontinuity.Continuous, AdministrativeClass.State, reversed = false, 1, 8)
          , DateTime.now),
        ProjectRoadwayChange(projectId + 1, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(RoadAddressChangeType.Unchanged,
            RoadwayChangeSection(Some(road), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(part), endRoadPartNumber = Some(part), addrMRange = Some(AddrMRange(10L,15L)), Some(AdministrativeClass.State), Some(Discontinuity.EndOfRoad), Some(8L)),
            RoadwayChangeSection(Some(road), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(part), endRoadPartNumber = Some(part), addrMRange = Some(AddrMRange(10L,15L)), Some(AdministrativeClass.State), Some(Discontinuity.EndOfRoad), Some(8L)),
            Discontinuity.EndOfRoad, AdministrativeClass.State, reversed = false, 2, 8)
          , DateTime.now),
        ProjectRoadwayChange(projectId, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(RoadAddressChangeType.Termination,
            RoadwayChangeSection(Some(road), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(part), endRoadPartNumber = Some(part), addrMRange = Some(AddrMRange(15L,20L)), Some(AdministrativeClass.State), Some(Discontinuity.Continuous), Some(8L)),
            RoadwayChangeSection(None, None, None, None, None, Some(AdministrativeClass.State), Some(Discontinuity.Continuous), Some(8L)),
            Discontinuity.Continuous, AdministrativeClass.State, reversed = false, 3, 8)
          , DateTime.now),
        ProjectRoadwayChange(projectId, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(RoadAddressChangeType.Termination,
            RoadwayChangeSection(Some(road), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(part), endRoadPartNumber = Some(part), addrMRange = Some(AddrMRange(20L,25L)), Some(AdministrativeClass.State), Some(Discontinuity.EndOfRoad), Some(8L)),
            RoadwayChangeSection(None, None, None, None, None, Some(AdministrativeClass.State), Some(Discontinuity.EndOfRoad), Some(8L)),
            Discontinuity.EndOfRoad, AdministrativeClass.State, reversed = false, 4, 8)
          , DateTime.now)
      )

      linearLocationDAO.expireByRoadwayNumbers(Set(combLink4.roadwayNumber, combLink5.roadwayNumber))
      roadwayDAO.expireHistory(Set(combLink4.roadwayId, combLink5.roadwayId))
      projectLinkDAO.moveProjectLinksToHistory(projectId)
      val unchangedLink1  = dummyProjectLink(RoadPart(road, part), Track.Combined, Discontinuity.Continuous, AddrMRange( 0,  5), AddrMRange( 0,  5), Some(DateTime.now()), None, 12345.toString, 0, 5, SideCode.TowardsDigitizing, RoadAddressChangeType.Unchanged,   projectId + 1, AdministrativeClass.State, combGeom1, rwNumber + 1).copy(id = plId + 5, roadwayId = rwId + 1, linearLocationId = llId + 5)
      val unchangedLink2  = dummyProjectLink(RoadPart(road, part), Track.Combined, Discontinuity.Continuous, AddrMRange( 5, 10), AddrMRange( 5, 10), Some(DateTime.now()), None, 12346.toString, 0, 5, SideCode.TowardsDigitizing, RoadAddressChangeType.Unchanged,   projectId + 1, AdministrativeClass.State, combGeom2, rwNumber + 1).copy(id = plId + 6, roadwayId = rwId + 1, linearLocationId = llId + 6)
      val unchangedLink3  = dummyProjectLink(RoadPart(road, part), Track.Combined, Discontinuity.EndOfRoad,  AddrMRange(10, 15), AddrMRange(10, 15), Some(DateTime.now()), None, 12347.toString, 0, 5, SideCode.TowardsDigitizing, RoadAddressChangeType.Unchanged,   projectId + 1, AdministrativeClass.State, combGeom3, rwNumber + 1).copy(id = plId + 7, roadwayId = rwId + 1, linearLocationId = llId + 7)
      val terminatedLink4 = dummyProjectLink(RoadPart(road, part), Track.Combined, Discontinuity.Continuous, AddrMRange(15, 20), AddrMRange(15, 20), Some(DateTime.now()), None, 12348.toString, 0, 5, SideCode.TowardsDigitizing, RoadAddressChangeType.Termination, projectId + 1, AdministrativeClass.State, combGeom4, rwNumber + 2).copy(id = plId + 8, roadwayId = rwId + 2, linearLocationId = llId + 8)
      val terminatedLink5 = dummyProjectLink(RoadPart(road, part), Track.Combined, Discontinuity.EndOfRoad,  AddrMRange(20, 25), AddrMRange(20, 25), Some(DateTime.now()), None, 12349.toString, 0, 5, SideCode.TowardsDigitizing, RoadAddressChangeType.Termination, projectId + 1, AdministrativeClass.State, combGeom5, rwNumber + 2).copy(id = plId + 9, roadwayId = rwId + 2, linearLocationId = llId + 9)

      buildTestDataForProject(Some(project2),
        Some(Seq(rw1WithId.copy(id = unchangedLink1.roadwayId, addrMRange = AddrMRange(rw1WithId.addrMRange.start, 15)),
          rw1WithId.copy(id = terminatedLink4.roadwayId, addrMRange = AddrMRange(rw1WithId.addrMRange.start, 10), endDate = Some(DateTime.now())))),
        Some(Seq(orderedcll1.copy(id = unchangedLink1.linearLocationId, roadwayNumber = unchangedLink1.roadwayNumber), orderedcll2.copy(id = unchangedLink2.linearLocationId, roadwayNumber = unchangedLink2.roadwayNumber),
          orderedcll3.copy(id = unchangedLink3.linearLocationId, roadwayNumber = unchangedLink3.roadwayNumber),
          orderedcll4.copy(id = terminatedLink4.linearLocationId, roadwayNumber = terminatedLink4.roadwayNumber),
          orderedcll5.copy(id = terminatedLink5.linearLocationId, roadwayNumber = terminatedLink5.roadwayNumber))),
        Some(Seq(unchangedLink1, unchangedLink2, unchangedLink3, terminatedLink4, terminatedLink5)))

      projectLinkDAO.moveProjectLinksToHistory(projectId + 1)

      val mappedAfterTerminationRoadwayNumbers = projectLinkDAO.fetchProjectLinksChange(projectId + 1)
      roadAddressService.handleRoadwayPointsUpdate(TerminatingProjectChanges, mappedAfterTerminationRoadwayNumbers)
      nodesAndJunctionsService.handleNodePoints(TerminatingProjectChanges, combPLinks, mappedAfterTerminationRoadwayNumbers)
      nodesAndJunctionsService.handleJunctionAndJunctionPoints(TerminatingProjectChanges, combPLinks, mappedAfterTerminationRoadwayNumbers)

      /*  Ending expiring data  */
      val terminatedJunctionsBeforeExpire = junctionDAO.fetchExpiredByRoadwayNumbers(templateRoadwayNumbers)
      terminatedJunctionsBeforeExpire count (_.endDate.isDefined) should be(0)
      terminatedJunctionsBeforeExpire count (_.validTo.isDefined) should be(0)
      val endDate = Some(project2.startDate.minusDays(1))
      val terminatedLink1 = combLink4.copy(endDate = endDate, status = RoadAddressChangeType.Termination)
      val terminatedLink2 = combLink5.copy(endDate = endDate, status = RoadAddressChangeType.Termination)

      nodesAndJunctionsService.expireObsoleteNodesAndJunctions(Seq(combLink1, combLink2, combLink3, terminatedLink1, terminatedLink2), endDate)

      val rwPoints = roadwayPointDAO.fetchByRoadwayNumbers(Seq(combLink1, combLink2, combLink3, terminatedLink1, terminatedLink2).map(_.roadwayNumber)).map(_.id)
      val junctionPointsAfterTerminating = junctionPointDAO.fetchByRoadwayPointIds(rwPoints)
      junctionPointsAfterTerminating.length should be(0)

      //  Check that junctions for roadways were expired
      val junctionTemplatesAfterExpire = junctionDAO.fetchTemplatesByRoadwayNumbers(templateRoadwayNumbers)
      junctionTemplatesAfterExpire.length should be(0)

      //  Check that terminated junction was created
      val terminatedJunctionsAfterExpire = junctionDAO.fetchExpiredByRoadwayNumbers(templateRoadwayNumbers)
      terminatedJunctionsAfterExpire.length should be(2)
      terminatedJunctionsAfterExpire count (_.endDate.isDefined) should be(1)
      terminatedJunctionsAfterExpire count (_.validTo.isDefined) should be(1)
    }
  }

  test("Test nodesAndJunctionsService.handleJunctionTemplates When creating new road that connects to other road Then junction template and junctions points should be handled/created properly." +
    "Test nodesAndJunctionsService.expireObsoleteNodesAndJunctions When terminating the road that connects in the middle of the other one Then the existing Junction and his points should be expired.") {
    runWithRollback {
      /*
                        |
                        |
                      road1000
                        |
                        v
          |--road999-->|0|--road999-->|

        * Note:
          0: Illustration where junction points should be created
          C: Combined track
        */
      val road999 = 999L
      val road1000 = 1000L
      val part1 = 1L
      val projectId = Sequences.nextViiteProjectId
      val rwId = Sequences.nextRoadwayId
      val llId = Sequences.nextLinearLocationId
      val rwNumber = Sequences.nextRoadwayNumber
      val plId = Sequences.nextProjectLinkId


      val combGeom1 = Seq(Point(0.0, 0.0), Point(10.0, 0.0))
      val combGeom2 = Seq(Point(10.0, 0.0), Point(20.0, 0.0))
      val combGeom3 = Seq(Point(10.0, 10.0), Point(10.0, 0.0))

      val combLink1 = dummyProjectLink(RoadPart(road999,  part1), Track.Combined, Discontinuity.Continuous,    AddrMRange( 0, 10), AddrMRange( 0, 10), Some(DateTime.now()), None, 12345.toString, 0, 10, SideCode.TowardsDigitizing, RoadAddressChangeType.New, projectId, AdministrativeClass.State, combGeom1, rwNumber    ).copy(id = plId,     projectId = projectId, roadwayId = rwId,     linearLocationId = llId)
      val combLink2 = dummyProjectLink(RoadPart(road999,  part1), Track.Combined, Discontinuity.Discontinuous, AddrMRange(10, 20), AddrMRange(10, 20), Some(DateTime.now()), None, 12346.toString, 0, 10, SideCode.TowardsDigitizing, RoadAddressChangeType.New, projectId, AdministrativeClass.State, combGeom2, rwNumber    ).copy(id = plId + 1, projectId = projectId, roadwayId = rwId,     linearLocationId = llId + 1)
      val combLink3 = dummyProjectLink(RoadPart(road1000, part1), Track.Combined, Discontinuity.EndOfRoad,     AddrMRange( 0, 10), AddrMRange( 0, 10), Some(DateTime.now()), None, 12347.toString, 0, 10, SideCode.TowardsDigitizing, RoadAddressChangeType.New, projectId, AdministrativeClass.State, combGeom3, rwNumber + 1).copy(id = plId + 2, projectId = projectId, roadwayId = rwId + 1, linearLocationId = llId + 2)

      val project = Project(projectId, ProjectState.Incomplete, "f", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
        "", Seq(), Seq(), None, None)

      val combPLinks = Seq(combLink1, combLink2)

      val (lc1,   rw1  ): (LinearLocation, Roadway) = Seq(combLink1).map(toRoadwayAndLinearLocation).head
      val (lc2,_/*rw2*/): (LinearLocation, Roadway) = Seq(combLink2).map(toRoadwayAndLinearLocation).head
      val (lc3,   rw3  ): (LinearLocation, Roadway) = Seq(combLink3).map(toRoadwayAndLinearLocation).head
      val rw1WithId = rw1.copy(addrMRange = AddrMRange(0,20), ely = 8L)
      val rw2WithId = rw3.copy(addrMRange = AddrMRange(0,10), ely = 8L)
      val orderedlc1 = lc1.copy(orderNumber = 1)
      val orderedlc2 = lc2.copy(orderNumber = 2)
      val orderedlc3 = lc3.copy(orderNumber = 1)

      buildTestDataForProject(Some(project), Some(Seq(rw1WithId, rw2WithId)), Some(Seq(orderedlc1, orderedlc2, orderedlc3)), Some(Seq(combLink1, combLink2, combLink3)))

      val projectChanges = List(
        //Combined
        ProjectRoadwayChange(projectId, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(RoadAddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, Some(AdministrativeClass.State), Some(Discontinuity.Continuous), Some(8L)),
            RoadwayChangeSection(Some(road999), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(part1), endRoadPartNumber = Some(part1), addrMRange = Some(AddrMRange(0L,20L)), Some(AdministrativeClass.State), Some(Discontinuity.EndOfRoad), Some(8L)),
            Discontinuity.EndOfRoad, AdministrativeClass.State, reversed = false, 1, 8)
          , DateTime.now),
        ProjectRoadwayChange(projectId, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(RoadAddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, Some(AdministrativeClass.State), Some(Discontinuity.EndOfRoad), Some(8L)),
            RoadwayChangeSection(Some(road1000), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(part1), endRoadPartNumber = Some(part1), addrMRange = Some(AddrMRange(0L,10L)), Some(AdministrativeClass.State), Some(Discontinuity.EndOfRoad), Some(8L)),
            Discontinuity.EndOfRoad, AdministrativeClass.State, reversed = false, 2, 8)
          , DateTime.now)
      )

      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(any[BoundingRectangle], any[Seq[(Int, Int)]])).thenReturn(Seq(orderedlc1, orderedlc2, orderedlc3))
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(any[Set[Long]], any[Boolean])).thenReturn(Seq(rw1WithId, rw2WithId))

      val mappedReservedRoadwayNumbers = projectLinkDAO.fetchProjectLinksChange(projectId)
      roadAddressService.handleRoadwayPointsUpdate(projectChanges, mappedReservedRoadwayNumbers)
      nodesAndJunctionsService.handleNodePoints(projectChanges, combPLinks, mappedReservedRoadwayNumbers)
      nodesAndJunctionsService.handleJunctionAndJunctionPoints(projectChanges, combPLinks, mappedReservedRoadwayNumbers)

      val roadwayPoints = roadwayPointDAO.fetchByRoadwayNumbers((combPLinks :+ combLink3).map(_.roadwayNumber)).map(_.id)
      val junctionPointTemplates = junctionPointDAO.fetchByRoadwayPointIds(roadwayPoints)

      junctionPointTemplates.length should be(3)
      junctionPointTemplates.count(_.beforeAfter == BeforeAfter.Before) should be(2)
      junctionPointTemplates.count(_.beforeAfter == BeforeAfter.After) should be(1)
      val templateRoadwayNumbers = junctionPointTemplates.map(_.roadwayNumber).distinct
      val junctions = junctionDAO.fetchTemplatesByRoadwayNumbers(templateRoadwayNumbers)
      junctions.size should be(1)

      /*
      preparing expiring data
      /*

           |--C1-->|--C2-->|

      * Note:
        C: Combined track
      */

    */
      val project2 = Project(projectId + 1, ProjectState.Incomplete, "ProjectDeleteRoadOfRoadLinks", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
        "", Seq(), Seq(), None, None)
      val terminatingProjectChanges = List(
        ProjectRoadwayChange(projectId + 1, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(RoadAddressChangeType.Termination,
            RoadwayChangeSection(Some(road1000), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(part1), endRoadPartNumber = Some(part1), addrMRange = Some(AddrMRange(0L,10L)), Some(AdministrativeClass.State), Some(Discontinuity.EndOfRoad), Some(8L)),
            RoadwayChangeSection(None, None, None, None, None, Some(AdministrativeClass.State), Some(Discontinuity.Continuous), Some(8L)),
            Discontinuity.EndOfRoad, AdministrativeClass.State, reversed = false, 1, 8)
          , DateTime.now)
      )

      linearLocationDAO.expireByRoadwayNumbers(Set(combLink3.roadwayNumber))
      roadwayDAO.expireHistory(Set(combLink3.roadwayId))
      projectLinkDAO.moveProjectLinksToHistory(projectId)
      val combGeom4 = Seq(Point(20.0, 0.0), Point(30.0, 0.0))

      val terminatedLink = dummyProjectLink(RoadPart(road1000, part1), Track.Combined, Discontinuity.EndOfRoad, AddrMRange(0, 10), AddrMRange(0, 10), Some(DateTime.now()), None, 12347.toString, 0, 10, SideCode.TowardsDigitizing, RoadAddressChangeType.Termination, projectId + 1, AdministrativeClass.State, combGeom4, rwNumber + 1).copy(id = plId + 3, roadwayId = rwId + 2, linearLocationId = llId + 3)

      val (lc4, rw4): (LinearLocation, Roadway) = Seq(terminatedLink).map(toRoadwayAndLinearLocation).head
      val rw4WithId = rw4.copy(id = terminatedLink.roadwayId, ely = 8L)

      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(Set(combLink1.roadwayNumber, combLink2.roadwayNumber, terminatedLink.roadwayNumber), withHistory=false)).thenReturn(Seq(rw1WithId, rw2WithId, rw4WithId))
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(Set(combLink1.roadwayNumber), withHistory=false)).thenReturn(Seq(rw1WithId))
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(Set(combLink2.roadwayNumber), withHistory=false)).thenReturn(Seq(rw2WithId))
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(Set(terminatedLink.roadwayNumber), withHistory=false)).thenReturn(Seq(rw4WithId))

      buildTestDataForProject(Some(project2),
        Some(Seq(rw4WithId)),
        Some(Seq(lc4)),
        Some(Seq(terminatedLink)))

      val mappedAfterNewRoadwayNumber = projectLinkDAO.fetchProjectLinksChange(projectId + 1).map(_.copy(linearLocationId = terminatedLink.linearLocationId))
      projectLinkDAO.moveProjectLinksToHistory(projectId + 1)

      roadAddressService.handleRoadwayPointsUpdate(terminatingProjectChanges, mappedAfterNewRoadwayNumber)
      nodesAndJunctionsService.handleNodePoints(terminatingProjectChanges, combPLinks, mappedAfterNewRoadwayNumber)
      nodesAndJunctionsService.handleJunctionAndJunctionPoints(terminatingProjectChanges, combPLinks, mappedAfterNewRoadwayNumber)
      /*
      ending expiring data
       */
      val terminatedJunctionsBeforeExpire = junctionDAO.fetchExpiredByRoadwayNumbers(templateRoadwayNumbers)
      terminatedJunctionsBeforeExpire count (_.endDate.isDefined) should be(0)
      terminatedJunctionsBeforeExpire count (_.validTo.isDefined) should be(0)
      val endDate = Some(project2.startDate.minusDays(1))

      nodesAndJunctionsService.expireObsoleteNodesAndJunctions(Seq(terminatedLink), endDate)

      val rwPoints = roadwayPointDAO.fetchByRoadwayNumbers(Seq(combLink1, combLink2, terminatedLink).map(_.roadwayNumber)).map(_.id)
      val junctionPointsAfterTerminating = junctionPointDAO.fetchByRoadwayPointIds(rwPoints)
      junctionPointsAfterTerminating.length should be(0)
      // Check that junctions for roadways were expired
      val junctionTemplatesAfterExpire = junctionDAO.fetchTemplatesByRoadwayNumbers(templateRoadwayNumbers)
      junctionTemplatesAfterExpire.length should be(0)

      // Check that terminated junction was created
      val terminatedJunctionsAfterExpire = junctionDAO.fetchExpiredByRoadwayNumbers(templateRoadwayNumbers)
      terminatedJunctionsAfterExpire.length should be(2)
      terminatedJunctionsAfterExpire count (_.endDate.isDefined) should be(1)
      terminatedJunctionsAfterExpire count (_.validTo.isDefined) should be(1)
    }
  }

  // <editor-fold desc="Ramps and roundabouts">
  test("Test nodesAndJunctionsService.handleJunctionTemplates When creating new different road numbers that connects each other Then junction template and junctions points should be handled/created properly." +
    "Test nodesAndJunctionsService.expireObsoleteNodesAndJunctions When expiring the one part that will make the ramp road parts not intersecting itself Then the existing Junction and its Junction points should be expired.") {
    runWithRollback {
      /*
                            ^
                            |
                            | R2
          ---R1--->---R1--->0


       * Note:
          0: Illustration where junction points should be created
      */

      val road = 20001L
      val part1 = 1L
      val part2 = 2L
      val projectId = Sequences.nextViiteProjectId
      val rwId = Sequences.nextRoadwayId
      val llId = Sequences.nextLinearLocationId
      val rwNumber = Sequences.nextRoadwayNumber
      val plId = Sequences.nextProjectLinkId


      val combGeom1 = Seq(Point(0.0, 0.0), Point(10.0, 0.0))
      val combGeom2 = Seq(Point(10.0, 0.0), Point(20.0, 0.0))
      val combGeom3 = Seq(Point(20.0, 0.0), Point(20.0, 15.0))

      val combLink1 = dummyProjectLink(RoadPart(road, part1), Track.Combined, Discontinuity.Continuous, AddrMRange( 0, 10), AddrMRange( 0, 10), Some(DateTime.now()), None, 12345.toString, 0, 10, SideCode.TowardsDigitizing, RoadAddressChangeType.Transfer, projectId, AdministrativeClass.State, combGeom1, rwNumber    ).copy(id = plId,     calibrationPointTypes = (RoadAddressCP, NoCP         ), projectId = projectId, roadwayId = rwId,     linearLocationId = llId)
      val combLink2 = dummyProjectLink(RoadPart(road, part1), Track.Combined, Discontinuity.Continuous, AddrMRange(10, 20), AddrMRange(10, 20), Some(DateTime.now()), None, 12346.toString, 0, 10, SideCode.TowardsDigitizing, RoadAddressChangeType.Transfer, projectId, AdministrativeClass.State, combGeom2, rwNumber    ).copy(id = plId + 1, calibrationPointTypes = (NoCP,          RoadAddressCP), projectId = projectId, roadwayId = rwId,     linearLocationId = llId + 1)
      val combLink3 = dummyProjectLink(RoadPart(road, part2), Track.Combined, Discontinuity.EndOfRoad,  AddrMRange( 0, 15), AddrMRange( 0, 15), Some(DateTime.now()), None, 12347.toString, 0, 15, SideCode.TowardsDigitizing, RoadAddressChangeType.Transfer, projectId, AdministrativeClass.State, combGeom3, rwNumber + 1).copy(id = plId + 2, calibrationPointTypes = (RoadAddressCP, RoadAddressCP), projectId = projectId, roadwayId = rwId + 1, linearLocationId = llId + 2)

      val project = Project(projectId, ProjectState.Incomplete, "f", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
        "", Seq(), Seq(), None, None)

      val combPLinks = Seq(combLink1, combLink2, combLink3)

      val (lc1,   rw1  ): (LinearLocation, Roadway) = Seq(combLink1).map(toRoadwayAndLinearLocation).head
      val (lc2,_/*rw2*/): (LinearLocation, Roadway) = Seq(combLink2).map(toRoadwayAndLinearLocation).head
      val (lc3,   rw3  ): (LinearLocation, Roadway) = Seq(combLink3).map(toRoadwayAndLinearLocation).head
      val rw1WithId = rw1.copy(addrMRange = AddrMRange(0,20), ely = 8L)
      val rw2WithId = rw3.copy(addrMRange = AddrMRange(0,15), ely = 8L)

      buildTestDataForProject(Some(project), Some(Seq(rw1WithId, rw2WithId)), Some(Seq(lc1, lc2, lc3)), Some(combPLinks))

      val projectChanges = List(
        //  Combined
        ProjectRoadwayChange(projectId, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(RoadAddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, Some(AdministrativeClass.State), Some(Discontinuity.Continuous), Some(8L)),
            RoadwayChangeSection(Some(road), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(part1), endRoadPartNumber = Some(part1), addrMRange = Some(AddrMRange(0L,10L)), Some(AdministrativeClass.State), Some(Discontinuity.Continuous), Some(8L)),
            Discontinuity.Continuous, AdministrativeClass.State, reversed = false, 1, 8)
          , DateTime.now),
        ProjectRoadwayChange(projectId, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(RoadAddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, Some(AdministrativeClass.State), Some(Discontinuity.Continuous), Some(8L)),
            RoadwayChangeSection(Some(road), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(part1), endRoadPartNumber = Some(part1), addrMRange = Some(AddrMRange(10L,20L)), Some(AdministrativeClass.State), Some(Discontinuity.EndOfRoad), Some(8L)),
            Discontinuity.EndOfRoad, AdministrativeClass.State, reversed = false, 2, 8)
          , DateTime.now),
        ProjectRoadwayChange(projectId, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(RoadAddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, Some(AdministrativeClass.State), Some(Discontinuity.Continuous), Some(8L)),
            RoadwayChangeSection(Some(road), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(part2), endRoadPartNumber = Some(part1), addrMRange = Some(AddrMRange(0L,15L)), Some(AdministrativeClass.State), Some(Discontinuity.EndOfRoad), Some(8L)),
            Discontinuity.EndOfRoad, AdministrativeClass.State, reversed = false, 3, 8)
          , DateTime.now)
      )

      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(any[BoundingRectangle], any[Seq[(Int, Int)]])).thenReturn(Seq(lc1, lc2, lc3))
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(any[Set[Long]], any[Boolean])).thenReturn(Seq(rw1WithId, rw2WithId))

      val mappedReservedRoadwayNumbers = projectLinkDAO.fetchProjectLinksChange(projectId)
      roadAddressService.handleRoadwayPointsUpdate(projectChanges, mappedReservedRoadwayNumbers)
      roadAddressService.handleProjectCalibrationPointChanges(Seq(lc1, lc2, lc3), username = project.createdBy)
      val createdCalibrationPoints = CalibrationPointDAO.fetchByLinkId(combPLinks.map(_.linkId))
      createdCalibrationPoints.size should be (4)

      createdCalibrationPoints.filter(cp => cp.linkId == combLink1.linkId && cp.startOrEnd == CalibrationPointLocation.StartOfLink).head.addrM should be (0)
      createdCalibrationPoints.filter(cp => cp.linkId == combLink2.linkId && cp.startOrEnd == CalibrationPointLocation.EndOfLink).head.addrM should be (20)
      createdCalibrationPoints.filter(cp => cp.linkId == combLink3.linkId && cp.startOrEnd == CalibrationPointLocation.StartOfLink).head.addrM should be (0)
      createdCalibrationPoints.filter(cp => cp.linkId == combLink3.linkId && cp.startOrEnd == CalibrationPointLocation.EndOfLink).head.addrM should be (15)
      nodesAndJunctionsService.handleNodePoints(projectChanges, combPLinks, mappedReservedRoadwayNumbers)
      nodesAndJunctionsService.handleJunctionAndJunctionPoints(projectChanges, combPLinks, mappedReservedRoadwayNumbers)
      val expiredJunctions = nodesAndJunctionsService.expireObsoleteNodesAndJunctions(combPLinks, None)
      roadAddressService.expireObsoleteCalibrationPointsInJunctions(expiredJunctions)
      val createdCalibrationPointsAfterJunctionPointHandler = CalibrationPointDAO.fetchByLinkId(combPLinks.map(_.linkId))
      createdCalibrationPointsAfterJunctionPointHandler.size should be (4)
      val roadwayPoints = roadwayPointDAO.fetchByRoadwayNumbers(combPLinks.map(_.roadwayNumber))
      val roadwayPointIds = roadwayPoints.map(_.id)
      val junctionPointTemplates = junctionPointDAO.fetchByRoadwayPointIds(roadwayPointIds)
      val templateRoadwayNumbers = junctionPointTemplates.map(_.roadwayNumber).distinct
      val junctions = junctionDAO.fetchTemplatesByRoadwayNumbers(templateRoadwayNumbers)
      junctionPointTemplates.length should be(2)
      junctionPointTemplates.count(_.beforeAfter == BeforeAfter.Before) should be(1)
      junctionPointTemplates.count(_.beforeAfter == BeforeAfter.After) should be(1)
      junctions.size should be(1)

      //  Preparing expiring data
      val project2 = Project(projectId + 1, ProjectState.Incomplete, "ProjectTerminatedLinks", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
        "", Seq(), Seq(), None, None)

      val terminatingProjectChanges = List(
        ProjectRoadwayChange(project2.id, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(RoadAddressChangeType.Termination,
            RoadwayChangeSection(Some(road), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(part2), endRoadPartNumber = Some(part1), addrMRange = Some(AddrMRange(0L,15L)), Some(AdministrativeClass.State), Some(Discontinuity.EndOfRoad), Some(8L)),
            RoadwayChangeSection(None, None, None, None, None, Some(AdministrativeClass.State), Some(Discontinuity.Continuous), Some(8L)),
            Discontinuity.EndOfRoad, AdministrativeClass.State, reversed = false, 3, 8)
          , DateTime.now)
      )

      linearLocationDAO.expireByRoadwayNumbers(Set(combLink3.roadwayNumber))
      roadwayDAO.expireHistory(Set(combLink3.roadwayId))
      projectLinkDAO.moveProjectLinksToHistory(projectId)
      val terminatingCombLink3 = dummyProjectLink(RoadPart(road, part2), Track.Combined, Discontinuity.EndOfRoad, AddrMRange(0, 15), AddrMRange(0, 15), Some(DateTime.now()), None, 12347.toString, 0, 15, SideCode.TowardsDigitizing, RoadAddressChangeType.Termination, projectId + 1, AdministrativeClass.State, combGeom3, rwNumber + 1).copy(id = plId + 3, calibrationPointTypes = (RoadAddressCP, RoadAddressCP), roadwayId = rwId + 1, linearLocationId = llId + 2)

      buildTestDataForProject(Some(project2), None, None, Some(Seq(terminatingCombLink3)))

      val mappedAfterTerminationRoadwayNumbers = projectLinkDAO.fetchProjectLinksChange(project2.id)
      projectLinkDAO.moveProjectLinksToHistory(projectId + 1)

      roadAddressService.handleRoadwayPointsUpdate(terminatingProjectChanges, mappedAfterTerminationRoadwayNumbers)
      roadAddressService.handleProjectCalibrationPointChanges(Seq.empty[LinearLocation], username = project.createdBy, mappedAfterTerminationRoadwayNumbers)
      val existingCalibrationPointsForTerminatingRoad = CalibrationPointDAO.fetchByLinkId(Seq(terminatingCombLink3.linkId))
      existingCalibrationPointsForTerminatingRoad.size should be (0)
      nodesAndJunctionsService.handleNodePoints(terminatingProjectChanges, combPLinks, mappedAfterTerminationRoadwayNumbers)
      nodesAndJunctionsService.handleJunctionAndJunctionPoints(terminatingProjectChanges, combPLinks, mappedAfterTerminationRoadwayNumbers)

      //  Ending expiring data
      val terminatedJunctionsBeforeExpire = junctionDAO.fetchExpiredByRoadwayNumbers(templateRoadwayNumbers)
      terminatedJunctionsBeforeExpire count (_.endDate.isDefined) should be(0)
      terminatedJunctionsBeforeExpire count (_.validTo.isDefined) should be(0)
      val endDate = Some(project2.startDate.minusDays(1))
      val terminatedLink1 = terminatingCombLink3.copy(endDate = endDate, status = RoadAddressChangeType.Termination)
      val yetAnotherExpiredJunctions = nodesAndJunctionsService.expireObsoleteNodesAndJunctions(Seq(combLink1, combLink2, terminatedLink1), endDate)

      //  Check that junctions for roadways were expired
      yetAnotherExpiredJunctions.size should be (2)

      when(mockRoadwayAddressMapper.getCurrentRoadAddressesBySection(RoadPart(road, part2))).thenReturn(Seq())
      val roadAddresses = roadwayAddressMapper.mapRoadAddresses(rw1WithId, Seq(lc1, lc2))
      when(mockRoadwayAddressMapper.getCurrentRoadAddressesBySection(RoadPart(road, part1))).thenReturn(roadAddresses)
      roadAddressService.expireObsoleteCalibrationPointsInJunctions(yetAnotherExpiredJunctions)

      val rwPoints = roadwayPointDAO.fetchByRoadwayNumbers(Seq(combLink1, combLink2, terminatedLink1).map(_.roadwayNumber)).map(_.id)
      val junctionPointsAfterTerminating = junctionPointDAO.fetchByRoadwayPointIds(rwPoints)
      junctionPointsAfterTerminating.length should be(0)

      // Check that terminated junction was created
      val terminatedJunctionsAfterExpire = junctionDAO.fetchExpiredByRoadwayNumbers(templateRoadwayNumbers)
      terminatedJunctionsAfterExpire.length should be(2)
      terminatedJunctionsAfterExpire count (_.endDate.isDefined) should be(1)
      terminatedJunctionsAfterExpire count (_.validTo.isDefined) should be(1)

      val calibrationPointsAfterTermination = CalibrationPointDAO.fetchByLinkId(combPLinks.map(_.linkId))
      calibrationPointsAfterTermination.size should be (2)
      calibrationPointsAfterTermination.filter(cp => cp.linkId == combLink1.linkId && cp.startOrEnd == CalibrationPointLocation.StartOfLink).head.addrM should be (0)
      calibrationPointsAfterTermination.filter(cp => cp.linkId == combLink2.linkId && cp.startOrEnd == CalibrationPointLocation.EndOfLink).head.addrM should be (20)
    }
  }

  test("Test nodesAndJunctionsService.handleJunctionTemplates When creating new ramps road part that connects to other part in same road number Then junction template and junctions points should be handled/created properly." +
    "Test nodesAndJunctionsService.expireObsoleteNodesAndJunctions When expiring the one part that will make the ramp road parts not intersecting itself Then the existing Junction and its Junction points should be expired.") {
    runWithRollback {
      /*
                          >|
                        /
                      C3
                    /
          |--C1-->|0|--C2-->|

        * Note:
            0: Illustration where junction points should be created
            C: Combined track
      */

      val road = 20001L
      val part1 = 1L
      val part2 = 2L
      val projectId = Sequences.nextViiteProjectId
      val rwId = Sequences.nextRoadwayId
      val llId = Sequences.nextLinearLocationId
      val rwNumber = Sequences.nextRoadwayNumber
      val plId = Sequences.nextProjectLinkId


      val combGeom1 = Seq(Point(0.0, 0.0), Point(10.0, 0.0))
      val combGeom2 = Seq(Point(10.0, 0.0), Point(20.0, 0.0))
      val combGeom3 = Seq(Point(10.0, 0.0), Point(20.0, 1.0))

      val combLink1 = dummyProjectLink(RoadPart(road, part1), Track.Combined, Discontinuity.Continuous,    AddrMRange( 0, 10), AddrMRange( 0, 10), Some(DateTime.now()), None, 12345.toString, 0, 10, SideCode.TowardsDigitizing, RoadAddressChangeType.Transfer, projectId, AdministrativeClass.State, combGeom1, rwNumber    ).copy(id = plId,     calibrationPointTypes = (RoadAddressCP, NoCP         ), projectId = projectId, roadwayId = rwId,     linearLocationId = llId)
      val combLink2 = dummyProjectLink(RoadPart(road, part1), Track.Combined, Discontinuity.Discontinuous, AddrMRange(10, 20), AddrMRange(10, 20), Some(DateTime.now()), None, 12346.toString, 0, 10, SideCode.TowardsDigitizing, RoadAddressChangeType.Transfer, projectId, AdministrativeClass.State, combGeom2, rwNumber    ).copy(id = plId + 1, calibrationPointTypes = (NoCP,          RoadAddressCP), projectId = projectId, roadwayId = rwId,     linearLocationId = llId + 1)
      val combLink3 = dummyProjectLink(RoadPart(road, part2), Track.Combined, Discontinuity.EndOfRoad,     AddrMRange( 0, 15), AddrMRange( 0, 15), Some(DateTime.now()), None, 12347.toString, 0, 15, SideCode.TowardsDigitizing, RoadAddressChangeType.Transfer, projectId, AdministrativeClass.State, combGeom3, rwNumber + 1).copy(id = plId + 2, calibrationPointTypes = (RoadAddressCP, RoadAddressCP), projectId = projectId, roadwayId = rwId + 1, linearLocationId = llId + 2)

      val project = Project(projectId, ProjectState.Incomplete, "f", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
        "", Seq(), Seq(), None, None)

      val combPLinks = Seq(combLink1, combLink2, combLink3)

      val (lc1,   rw1  ): (LinearLocation, Roadway) = Seq(combLink1).map(toRoadwayAndLinearLocation).head
      val (lc2,_/*rw2*/): (LinearLocation, Roadway) = Seq(combLink2).map(toRoadwayAndLinearLocation).head
      val (lc3,   rw3  ): (LinearLocation, Roadway) = Seq(combLink3).map(toRoadwayAndLinearLocation).head
      val rw1WithId = rw1.copy(addrMRange = AddrMRange(0,20), ely = 8L)
      val rw2WithId = rw3.copy(addrMRange = AddrMRange(0,15), ely = 8L)

      buildTestDataForProject(Some(project), Some(Seq(rw1WithId, rw2WithId)), Some(Seq(lc1, lc2, lc3)), Some(combPLinks))

      val projectChanges = List(
        //Combined
        ProjectRoadwayChange(projectId, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(RoadAddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, Some(AdministrativeClass.State), Some(Discontinuity.Continuous), Some(8L)),
            RoadwayChangeSection(Some(road), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(part1), endRoadPartNumber = Some(part1), addrMRange = Some(AddrMRange(0L,10L)), Some(AdministrativeClass.State), Some(Discontinuity.Continuous), Some(8L)),
            Discontinuity.Continuous, AdministrativeClass.State, reversed = false, 1, 8)
          , DateTime.now),
        ProjectRoadwayChange(projectId, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(RoadAddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, Some(AdministrativeClass.State), Some(Discontinuity.Continuous), Some(8L)),
            RoadwayChangeSection(Some(road), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(part1), endRoadPartNumber = Some(part1), addrMRange = Some(AddrMRange(10L,20L)), Some(AdministrativeClass.State), Some(Discontinuity.Discontinuous), Some(8L)),
            Discontinuity.Discontinuous, AdministrativeClass.State, reversed = false, 2, 8)
          , DateTime.now),
        ProjectRoadwayChange(projectId, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(RoadAddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, Some(AdministrativeClass.State), Some(Discontinuity.Continuous), Some(8L)),
            RoadwayChangeSection(Some(road), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(part2), endRoadPartNumber = Some(part2), addrMRange = Some(AddrMRange(0L,15L)), Some(AdministrativeClass.State), Some(Discontinuity.EndOfRoad), Some(8L)),
            Discontinuity.EndOfRoad, AdministrativeClass.State, reversed = false, 3, 8)
          , DateTime.now)
      )

      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(any[BoundingRectangle], any[Seq[(Int, Int)]])).thenReturn(Seq(lc1, lc2, lc3))
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(any[Set[Long]], any[Boolean])).thenReturn(Seq(rw1WithId, rw2WithId))

      val mappedReservedRoadwayNumbers = projectLinkDAO.fetchProjectLinksChange(projectId)
      roadAddressService.handleRoadwayPointsUpdate(projectChanges, mappedReservedRoadwayNumbers)
      roadAddressService.handleProjectCalibrationPointChanges(Seq(lc1, lc2, lc3), username = project.createdBy)
      val createdCalibrationPoints = CalibrationPointDAO.fetchByLinkId(combPLinks.map(_.linkId))
      createdCalibrationPoints.size should be (4)

      createdCalibrationPoints.filter(cp => cp.linkId == combLink1.linkId && cp.startOrEnd == CalibrationPointLocation.StartOfLink).head.addrM should be (0)
      createdCalibrationPoints.filter(cp => cp.linkId == combLink2.linkId && cp.startOrEnd == CalibrationPointLocation.EndOfLink).head.addrM should be (20)
      createdCalibrationPoints.filter(cp => cp.linkId == combLink3.linkId && cp.startOrEnd == CalibrationPointLocation.StartOfLink).head.addrM should be (0)
      createdCalibrationPoints.filter(cp => cp.linkId == combLink3.linkId && cp.startOrEnd == CalibrationPointLocation.EndOfLink).head.addrM should be (15)
      nodesAndJunctionsService.handleNodePoints(projectChanges, combPLinks, mappedReservedRoadwayNumbers)
      nodesAndJunctionsService.handleJunctionAndJunctionPoints(projectChanges, combPLinks, mappedReservedRoadwayNumbers)
      val expiredJunctionPoints = nodesAndJunctionsService.expireObsoleteNodesAndJunctions(combPLinks, None)
      roadAddressService.expireObsoleteCalibrationPointsInJunctions(expiredJunctionPoints)
      val createdCalibrationPointsAfterJunctionPointHandler = CalibrationPointDAO.fetchByLinkId(combPLinks.map(_.linkId))
      createdCalibrationPointsAfterJunctionPointHandler.size should be (6)
      val roadwayPoints = roadwayPointDAO.fetchByRoadwayNumbers(combPLinks.map(_.roadwayNumber))
      val roadwayPointIds = roadwayPoints.map(_.id)
      val junctionPointTemplates = junctionPointDAO.fetchByRoadwayPointIds(roadwayPointIds)
      val templateRoadwayNumbers = junctionPointTemplates.map(_.roadwayNumber).distinct
      val junctions = junctionDAO.fetchTemplatesByRoadwayNumbers(templateRoadwayNumbers)
      junctionPointTemplates.length should be(3)
      junctionPointTemplates.count(_.beforeAfter == BeforeAfter.Before) should be(1)
      junctionPointTemplates.count(_.beforeAfter == BeforeAfter.After) should be(2)
      junctions.size should be(1)

      val calibrationPointsInJunctionPointsPlace = createdCalibrationPointsAfterJunctionPointHandler.filterNot(jcp => createdCalibrationPoints.map(_.id).contains(jcp.id))
      calibrationPointsInJunctionPointsPlace.size should be (2)
      calibrationPointsInJunctionPointsPlace.filter(cp => cp.linkId == combLink1.linkId && cp.startOrEnd == CalibrationPointLocation.EndOfLink).head.addrM should be (10)
      calibrationPointsInJunctionPointsPlace.filter(cp => cp.linkId == combLink2.linkId && cp.startOrEnd == CalibrationPointLocation.StartOfLink).head.addrM should be (10)

      //  Preparing expiring data
      val project2 = Project(projectId + 1, ProjectState.Incomplete, "ProjectTerminatedLinks", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
        "", Seq(), Seq(), None, None)

      val terminatingProjectChanges = List(
        ProjectRoadwayChange(project2.id, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(RoadAddressChangeType.Termination,
            RoadwayChangeSection(Some(road), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(part2), endRoadPartNumber = Some(part2), addrMRange = Some(AddrMRange(0L,15L)), Some(AdministrativeClass.State), Some(Discontinuity.EndOfRoad), Some(8L)),
            RoadwayChangeSection(None, None, None, None, None, Some(AdministrativeClass.State), Some(Discontinuity.Continuous), Some(8L)),
            Discontinuity.EndOfRoad, AdministrativeClass.State, reversed = false, 3, 8)
          , DateTime.now)
      )

      linearLocationDAO.expireByRoadwayNumbers(Set(combLink3.roadwayNumber))
      roadwayDAO.expireHistory(Set(combLink3.roadwayId))
      projectLinkDAO.moveProjectLinksToHistory(projectId)
      val terminatingCombLink3 = dummyProjectLink(RoadPart(road, part2), Track.Combined, Discontinuity.EndOfRoad, AddrMRange(0, 15), AddrMRange(0, 15), Some(DateTime.now()), None, 12347.toString, 0, 15, SideCode.TowardsDigitizing, RoadAddressChangeType.Termination, projectId + 1, AdministrativeClass.State, combGeom3, rwNumber + 1).copy(id = plId + 3, calibrationPointTypes = (RoadAddressCP, RoadAddressCP), roadwayId = rwId + 1, linearLocationId = llId + 2)

      buildTestDataForProject(Some(project2),
        None,
        None,
        Some(Seq(terminatingCombLink3)))

      val mappedAfterTerminationRoadwayNumbers = projectLinkDAO.fetchProjectLinksChange(project2.id)
      projectLinkDAO.moveProjectLinksToHistory(projectId + 1)

      roadAddressService.handleRoadwayPointsUpdate(terminatingProjectChanges, mappedAfterTerminationRoadwayNumbers)
      roadAddressService.handleProjectCalibrationPointChanges(Seq.empty[LinearLocation], username = project.createdBy, mappedAfterTerminationRoadwayNumbers)
      val existingCalibrationPointsForTerminatingRoad = CalibrationPointDAO.fetchByLinkId(Seq(terminatingCombLink3.linkId))
      existingCalibrationPointsForTerminatingRoad.size should be (0)
      nodesAndJunctionsService.handleNodePoints(terminatingProjectChanges, combPLinks, mappedAfterTerminationRoadwayNumbers)
      nodesAndJunctionsService.handleJunctionAndJunctionPoints(terminatingProjectChanges, combPLinks, mappedAfterTerminationRoadwayNumbers)

      //  Ending expiring data
      val terminatedJunctionsBeforeExpire = junctionDAO.fetchExpiredByRoadwayNumbers(templateRoadwayNumbers)
      terminatedJunctionsBeforeExpire count (_.endDate.isDefined) should be(0)
      terminatedJunctionsBeforeExpire count (_.validTo.isDefined) should be(0)
      val endDate = Some(project2.startDate.minusDays(1))
      val terminatedLink1 = terminatingCombLink3.copy(endDate = endDate, status = RoadAddressChangeType.Termination)
      val yetAnotherExpiredJunctions = nodesAndJunctionsService.expireObsoleteNodesAndJunctions(Seq(combLink1, combLink2, terminatedLink1), endDate)
      yetAnotherExpiredJunctions.size should be (3)

      when(mockRoadwayAddressMapper.getCurrentRoadAddressesBySection(RoadPart(road, part2))).thenReturn(Seq())
      val roadAddresses = roadwayAddressMapper.mapRoadAddresses(rw1WithId, Seq(lc1, lc2))
      when(mockRoadwayAddressMapper.getCurrentRoadAddressesBySection(RoadPart(road, part1))).thenReturn(roadAddresses)
      roadAddressService.expireObsoleteCalibrationPointsInJunctions(yetAnotherExpiredJunctions)

      val rwPoints = roadwayPointDAO.fetchByRoadwayNumbers(Seq(combLink1, combLink2, terminatedLink1).map(_.roadwayNumber)).map(_.id)
      val junctionPointsAfterTerminating = junctionPointDAO.fetchByRoadwayPointIds(rwPoints)
      junctionPointsAfterTerminating.length should be(0)

      //  Check that junctions for roadways were expired
      val junctionsAfterExpire = junctionDAO.fetchTemplatesByRoadwayNumbers(templateRoadwayNumbers)
      junctionsAfterExpire.length should be(0)

      //  Check that terminated junction was created
      val terminatedJunctionsAfterExpire = junctionDAO.fetchExpiredByRoadwayNumbers(templateRoadwayNumbers)
      terminatedJunctionsAfterExpire.length should be(2)
      terminatedJunctionsAfterExpire count (_.endDate.isDefined) should be(1)
      terminatedJunctionsAfterExpire count (_.validTo.isDefined) should be(1)

      val calibrationPointsAfterTermination = CalibrationPointDAO.fetchByLinkId(combPLinks.map(_.linkId))
      calibrationPointsAfterTermination.size should be (2)
      calibrationPointsAfterTermination.filter(cp => cp.linkId == combLink1.linkId && cp.startOrEnd == CalibrationPointLocation.StartOfLink).head.addrM should be (0)
      calibrationPointsAfterTermination.filter(cp => cp.linkId == combLink2.linkId && cp.startOrEnd == CalibrationPointLocation.EndOfLink).head.addrM should be (20)
    }
  }

  test("Test nodesAndJunctionsService.handleJunctionTemplates When creating one roundabout road part that connects to same part in same road number and the connecting link is EndOfRoad Then junction template and junctions points should be handled/created properly") {
    runWithRollback {
      /*

              0---C1-->|
              ^        |
          C4  |        |   C2
              |        V
              |<---C3--|


        * Note:
            0: Illustration where junction points should be created
            C: Combined track
        */

      val road = 20001
      val part = 1L
      val projectId = Sequences.nextViiteProjectId
      val rwId = Sequences.nextRoadwayId
      val llId = Sequences.nextLinearLocationId
      val rwNumber = Sequences.nextRoadwayNumber
      val plId = Sequences.nextProjectLinkId


      val combGeom1 = Seq(Point(0.0, 5.0), Point(5.0, 5.0))
      val combGeom2 = Seq(Point(5.0, 5.0), Point(5.0, 0.0))
      val combGeom3 = Seq(Point(5.0, 0.0), Point(0.0, 0.0))
      val combGeom4 = Seq(Point(0.0, 0.0), Point(0.0, 5.0))

      val combLink1 = dummyProjectLink(RoadPart(road, part), Track.Combined, Discontinuity.Continuous, AddrMRange( 0,  5), AddrMRange( 0,  5), Some(DateTime.now()), None, 12345.toString, 0, 5, SideCode.TowardsDigitizing, RoadAddressChangeType.Transfer, projectId, AdministrativeClass.State, combGeom1, rwNumber).copy(id = plId,     projectId = projectId, roadwayId = rwId, linearLocationId = llId)
      val combLink2 = dummyProjectLink(RoadPart(road, part), Track.Combined, Discontinuity.Continuous, AddrMRange( 5, 10), AddrMRange( 5, 10), Some(DateTime.now()), None, 12346.toString, 0, 5, SideCode.TowardsDigitizing, RoadAddressChangeType.Transfer, projectId, AdministrativeClass.State, combGeom2, rwNumber).copy(id = plId + 1, projectId = projectId, roadwayId = rwId, linearLocationId = llId + 1)
      val combLink3 = dummyProjectLink(RoadPart(road, part), Track.Combined, Discontinuity.Continuous, AddrMRange(10, 15), AddrMRange(10, 15), Some(DateTime.now()), None, 12347.toString, 0, 5, SideCode.TowardsDigitizing, RoadAddressChangeType.Transfer, projectId, AdministrativeClass.State, combGeom3, rwNumber).copy(id = plId + 2, projectId = projectId, roadwayId = rwId, linearLocationId = llId + 2)
      val combLink4 = dummyProjectLink(RoadPart(road, part), Track.Combined, Discontinuity.EndOfRoad,  AddrMRange(15, 20), AddrMRange(15, 20), Some(DateTime.now()), None, 12348.toString, 0, 5, SideCode.TowardsDigitizing, RoadAddressChangeType.Transfer, projectId, AdministrativeClass.State, combGeom4, rwNumber).copy(id = plId + 3, projectId = projectId, roadwayId = rwId, linearLocationId = llId + 3)

      val project = Project(projectId, ProjectState.Incomplete, "f", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
        "", Seq(), Seq(), None, None)

      val combPLinks = Seq(combLink1, combLink2, combLink3, combLink4)

      val (lc1,   rw1  ): (LinearLocation, Roadway) = Seq(combLink1).map(toRoadwayAndLinearLocation).head
      val (lc2,_/*rw2*/): (LinearLocation, Roadway) = Seq(combLink2).map(toRoadwayAndLinearLocation).head
      val (lc3,_/*rw3*/): (LinearLocation, Roadway) = Seq(combLink3).map(toRoadwayAndLinearLocation).head
      val (lc4,_/*rw4*/): (LinearLocation, Roadway) = Seq(combLink4).map(toRoadwayAndLinearLocation).head
      val rw1WithId = rw1.copy(addrMRange = AddrMRange(0,20), ely = 8L)

      buildTestDataForProject(Some(project), Some(Seq(rw1WithId)), Some(Seq(lc1, lc2, lc3, lc4)), Some(combPLinks))

      val projectChanges = List(
        //  Combined
        ProjectRoadwayChange(projectId, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(RoadAddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, Some(AdministrativeClass.State), Some(Discontinuity.Continuous), Some(8L)),
            RoadwayChangeSection(Some(road), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(part), endRoadPartNumber = Some(part), addrMRange = Some(AddrMRange(0L,15L)), Some(AdministrativeClass.State), Some(Discontinuity.Continuous), Some(8L)),
            Discontinuity.Continuous, AdministrativeClass.State, reversed = false, 1, 8)
          , DateTime.now),
        ProjectRoadwayChange(projectId, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(RoadAddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, Some(AdministrativeClass.State), Some(Discontinuity.Continuous), Some(8L)),
            RoadwayChangeSection(Some(road), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(part), endRoadPartNumber = Some(part), addrMRange = Some(AddrMRange(15L,20L)), Some(AdministrativeClass.State), Some(Discontinuity.EndOfRoad), Some(8L)),
            Discontinuity.EndOfRoad, AdministrativeClass.State, reversed = false, 2, 8)
          , DateTime.now)
      )

      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(combGeom1.head, combGeom1.head), roadNumberLimits)).thenReturn(Seq(lc1, lc4))
      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(combGeom1.last, combGeom1.last), roadNumberLimits)).thenReturn(Seq(lc1, lc2))
      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(combGeom2.last, combGeom2.last), roadNumberLimits)).thenReturn(Seq(lc2, lc3))
      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(combGeom3.last, combGeom3.last), roadNumberLimits)).thenReturn(Seq(lc3, lc4))
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(any[Set[Long]], any[Boolean])).thenReturn(Seq(rw1WithId))

      val mappedReservedRoadwayNumbers = projectLinkDAO.fetchProjectLinksChange(projectId)
      roadAddressService.handleRoadwayPointsUpdate(projectChanges, mappedReservedRoadwayNumbers)
      nodesAndJunctionsService.handleNodePoints(projectChanges, combPLinks, mappedReservedRoadwayNumbers)
      nodesAndJunctionsService.handleJunctionAndJunctionPoints(projectChanges, combPLinks, mappedReservedRoadwayNumbers)

      val roadwayPoints = roadwayPointDAO.fetchByRoadwayNumbers(combPLinks.map(_.roadwayNumber)).map(_.id)

      val junctionPointTemplates = junctionPointDAO.fetchByRoadwayPointIds(roadwayPoints)
      junctionPointTemplates.length should be(2)
      junctionPointTemplates.count(_.beforeAfter == BeforeAfter.Before) should be(1)
      junctionPointTemplates.count(_.beforeAfter == BeforeAfter.After) should be(1)

      val junctions = junctionDAO.fetchTemplatesByRoadwayNumbers(junctionPointTemplates.map(_.roadwayNumber).distinct)
      junctions.size should be(1)
    }
  }

  test("Test nodesAndJunctionsService.handleJunctionTemplates When creating one roundabout road part that connects to same part in same road number and the connecting link is Discontinuous Then junction template and junctions points should be handled/created properly") {
    runWithRollback {
      /*

              0---C1-->|
              ^        |
          C4  |        |   C2
              |        V
              |<---C3--|


        * Note:
            0: Illustration where junction points should be created
            C: Combined track
        */

      val road = 20001
      val part = 1L
      val projectId = Sequences.nextViiteProjectId
      val rwId = Sequences.nextRoadwayId
      val llId = Sequences.nextLinearLocationId
      val rwNumber = Sequences.nextRoadwayNumber
      val plId = Sequences.nextProjectLinkId


      val combGeom1 = Seq(Point(0.0, 5.0), Point(5.0, 5.0))
      val combGeom2 = Seq(Point(5.0, 5.0), Point(5.0, 0.0))
      val combGeom3 = Seq(Point(5.0, 0.0), Point(0.0, 0.0))
      val combGeom4 = Seq(Point(0.0, 0.0), Point(0.0, 5.0))

      val combLink1 = dummyProjectLink(RoadPart(road, part), Track.Combined, Discontinuity.Continuous,    AddrMRange( 0,  5), AddrMRange( 0,  5), Some(DateTime.now()), None, 12345.toString, 0, 5, SideCode.TowardsDigitizing, RoadAddressChangeType.Transfer, projectId, AdministrativeClass.State, combGeom1, rwNumber).copy(id = plId,     projectId = projectId, roadwayId = rwId, linearLocationId = llId)
      val combLink2 = dummyProjectLink(RoadPart(road, part), Track.Combined, Discontinuity.Continuous,    AddrMRange( 5, 10), AddrMRange( 5, 10), Some(DateTime.now()), None, 12346.toString, 0, 5, SideCode.TowardsDigitizing, RoadAddressChangeType.Transfer, projectId, AdministrativeClass.State, combGeom2, rwNumber).copy(id = plId + 1, projectId = projectId, roadwayId = rwId, linearLocationId = llId + 1)
      val combLink3 = dummyProjectLink(RoadPart(road, part), Track.Combined, Discontinuity.Continuous,    AddrMRange(10, 15), AddrMRange(10, 15), Some(DateTime.now()), None, 12347.toString, 0, 5, SideCode.TowardsDigitizing, RoadAddressChangeType.Transfer, projectId, AdministrativeClass.State, combGeom3, rwNumber).copy(id = plId + 2, projectId = projectId, roadwayId = rwId, linearLocationId = llId + 2)
      val combLink4 = dummyProjectLink(RoadPart(road, part), Track.Combined, Discontinuity.Discontinuous, AddrMRange(15, 20), AddrMRange(15, 20), Some(DateTime.now()), None, 12348.toString, 0, 5, SideCode.TowardsDigitizing, RoadAddressChangeType.Transfer, projectId, AdministrativeClass.State, combGeom4, rwNumber).copy(id = plId + 3, projectId = projectId, roadwayId = rwId, linearLocationId = llId + 3)

      val project = Project(projectId, ProjectState.Incomplete, "f", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
        "", Seq(), Seq(), None, None)

      val combPLinks = Seq(combLink1, combLink2, combLink3, combLink4)

      val (lc1,   rw1  ): (LinearLocation, Roadway) = Seq(combLink1).map(toRoadwayAndLinearLocation).head
      val (lc2,_/*rw2*/): (LinearLocation, Roadway) = Seq(combLink2).map(toRoadwayAndLinearLocation).head
      val (lc3,_/*rw3*/): (LinearLocation, Roadway) = Seq(combLink3).map(toRoadwayAndLinearLocation).head
      val (lc4,_/*rw4*/): (LinearLocation, Roadway) = Seq(combLink4).map(toRoadwayAndLinearLocation).head
      val rw1WithId = rw1.copy(addrMRange = AddrMRange(0,20), ely = 8L)
      val orderedlc1 = lc1.copy(orderNumber = 1)
      val orderedlc2 = lc2.copy(orderNumber = 2)
      val orderedlc3 = lc3.copy(orderNumber = 3)
      val orderedlc4 = lc4.copy(orderNumber = 4)

      buildTestDataForProject(Some(project), Some(Seq(rw1WithId)), Some(Seq(orderedlc1, orderedlc2, orderedlc3, orderedlc4)), Some(combPLinks))

      val projectChanges = List(
        //Combined
        ProjectRoadwayChange(projectId, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(RoadAddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, Some(AdministrativeClass.State), Some(Discontinuity.Continuous), Some(8L)),
            RoadwayChangeSection(Some(road), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(part), endRoadPartNumber = Some(part), addrMRange = Some(AddrMRange(0L,15L)), Some(AdministrativeClass.State), Some(Discontinuity.Continuous), Some(8L)),
            Discontinuity.Continuous, AdministrativeClass.State, reversed = false, 1, 8)
          , DateTime.now),
        ProjectRoadwayChange(projectId, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(RoadAddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, Some(AdministrativeClass.State), Some(Discontinuity.Discontinuous), Some(8L)),
            RoadwayChangeSection(Some(road), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(part), endRoadPartNumber = Some(part), addrMRange = Some(AddrMRange(15L,20L)), Some(AdministrativeClass.State), Some(Discontinuity.Discontinuous), Some(8L)),
            Discontinuity.Discontinuous, AdministrativeClass.State, reversed = false, 2, 8)
          , DateTime.now)
      )

      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(combGeom1.head, combGeom1.head), roadNumberLimits)).thenReturn(Seq(orderedlc1, orderedlc2, orderedlc3, orderedlc4))
      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(combGeom1.last, combGeom1.last), roadNumberLimits)).thenReturn(Seq(orderedlc1, orderedlc2, orderedlc3, orderedlc4))
      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(combGeom2.last, combGeom2.last), roadNumberLimits)).thenReturn(Seq(orderedlc1, orderedlc2, orderedlc3, orderedlc4))
      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(combGeom3.last, combGeom3.last), roadNumberLimits)).thenReturn(Seq(orderedlc1, orderedlc2, orderedlc3, orderedlc4))
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(any[Set[Long]], any[Boolean])).thenReturn(Seq(rw1WithId))

      val mappedReservedRoadwayNumbers = projectLinkDAO.fetchProjectLinksChange(projectId)
      roadAddressService.handleRoadwayPointsUpdate(projectChanges, mappedReservedRoadwayNumbers)
      nodesAndJunctionsService.handleNodePoints(projectChanges, combPLinks, mappedReservedRoadwayNumbers)
      nodesAndJunctionsService.handleJunctionAndJunctionPoints(projectChanges, combPLinks, mappedReservedRoadwayNumbers)

      val roadwayPoints = roadwayPointDAO.fetchByRoadwayNumbers(combPLinks.map(_.roadwayNumber)).map(_.id)

      val junctionPointTemplates = junctionPointDAO.fetchByRoadwayPointIds(roadwayPoints)
      junctionPointTemplates.length should be(2)
      junctionPointTemplates.count(_.beforeAfter == BeforeAfter.Before) should be(1)
      junctionPointTemplates.count(_.beforeAfter == BeforeAfter.After) should be(1)

      val junctions = junctionDAO.fetchTemplatesByRoadwayNumbers(junctionPointTemplates.map(_.roadwayNumber).distinct)
      junctions.size should be(1)
    }
  }

  test("Test nodesAndJunctionsService.handleJunctionTemplates When creating one roundabout road part that connects to same part in same road number and the connecting link is MinorDiscontinuity Then junction template and junctions points should be handled/created properly") {
    runWithRollback {
      /*

              0---C1-->|
              ^        |
          C4  |        |   C2
              |        V
              |<---C3--|


        * Note:
            0: Illustration where junction points should be created
            C: Combined track
        */

      val road = 20001
      val part = 1L
      val projectId = Sequences.nextViiteProjectId
      val rwId = Sequences.nextRoadwayId
      val llId = Sequences.nextLinearLocationId
      val rwNumber = Sequences.nextRoadwayNumber
      val plId = Sequences.nextProjectLinkId


      val combGeom1 = Seq(Point(0.0, 5.0), Point(5.0, 5.0))
      val combGeom2 = Seq(Point(5.0, 5.0), Point(5.0, 0.0))
      val combGeom3 = Seq(Point(5.0, 0.0), Point(0.0, 0.0))
      val combGeom4 = Seq(Point(0.0, 0.0), Point(0.0, 5.0))

      val combLink1 = dummyProjectLink(RoadPart(road, part), Track.Combined, Discontinuity.Continuous,         AddrMRange( 0,  5), AddrMRange( 0,  5), Some(DateTime.now()), None, 12345.toString, 0, 5, SideCode.TowardsDigitizing, RoadAddressChangeType.Transfer, projectId, AdministrativeClass.State, combGeom1, rwNumber).copy(id = plId,     projectId = projectId, roadwayId = rwId, linearLocationId = llId)
      val combLink2 = dummyProjectLink(RoadPart(road, part), Track.Combined, Discontinuity.Continuous,         AddrMRange( 5, 10), AddrMRange( 5, 10), Some(DateTime.now()), None, 12346.toString, 0, 5, SideCode.TowardsDigitizing, RoadAddressChangeType.Transfer, projectId, AdministrativeClass.State, combGeom2, rwNumber).copy(id = plId + 1, projectId = projectId, roadwayId = rwId, linearLocationId = llId + 1)
      val combLink3 = dummyProjectLink(RoadPart(road, part), Track.Combined, Discontinuity.Continuous,         AddrMRange(10, 15), AddrMRange(10, 15), Some(DateTime.now()), None, 12347.toString, 0, 5, SideCode.TowardsDigitizing, RoadAddressChangeType.Transfer, projectId, AdministrativeClass.State, combGeom3, rwNumber).copy(id = plId + 2, projectId = projectId, roadwayId = rwId, linearLocationId = llId + 2)
      val combLink4 = dummyProjectLink(RoadPart(road, part), Track.Combined, Discontinuity.MinorDiscontinuity, AddrMRange(15, 20), AddrMRange(15, 20), Some(DateTime.now()), None, 12348.toString, 0, 5, SideCode.TowardsDigitizing, RoadAddressChangeType.Transfer, projectId, AdministrativeClass.State, combGeom4, rwNumber).copy(id = plId + 3, projectId = projectId, roadwayId = rwId, linearLocationId = llId + 3)

      val project = Project(projectId, ProjectState.Incomplete, "f", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
        "", Seq(), Seq(), None, None)

      val combPLinks = Seq(combLink1, combLink2, combLink3, combLink4)

      val (lc1,   rw1  ): (LinearLocation, Roadway) = Seq(combLink1).map(toRoadwayAndLinearLocation).head
      val (lc2,_/*rw2*/): (LinearLocation, Roadway) = Seq(combLink2).map(toRoadwayAndLinearLocation).head
      val (lc3,_/*rw3*/): (LinearLocation, Roadway) = Seq(combLink3).map(toRoadwayAndLinearLocation).head
      val (lc4,_/*rw4*/): (LinearLocation, Roadway) = Seq(combLink4).map(toRoadwayAndLinearLocation).head
      val rw1WithId = rw1.copy(addrMRange = AddrMRange(0,20), ely = 8L)
      val orderedlc1 = lc1.copy(orderNumber = 1)
      val orderedlc2 = lc2.copy(orderNumber = 2)
      val orderedlc3 = lc3.copy(orderNumber = 3)
      val orderedlc4 = lc4.copy(orderNumber = 4)

      buildTestDataForProject(Some(project), Some(Seq(rw1WithId)), Some(Seq(orderedlc1, orderedlc2, orderedlc3, orderedlc4)), Some(combPLinks))

      val projectChanges = List(
        //Combined
        ProjectRoadwayChange(projectId, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(RoadAddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, Some(AdministrativeClass.State), Some(Discontinuity.Continuous), Some(8L)),
            RoadwayChangeSection(Some(road), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(part), endRoadPartNumber = Some(part), addrMRange = Some(AddrMRange(0L,15L)), Some(AdministrativeClass.State), Some(Discontinuity.Continuous), Some(8L)),
            Discontinuity.Continuous, AdministrativeClass.State, reversed = false, 1, 8)
          , DateTime.now),
        ProjectRoadwayChange(projectId, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(RoadAddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, Some(AdministrativeClass.State), Some(Discontinuity.MinorDiscontinuity), Some(8L)),
            RoadwayChangeSection(Some(road), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(part), endRoadPartNumber = Some(part), addrMRange = Some(AddrMRange(15L,20L)), Some(AdministrativeClass.State), Some(Discontinuity.MinorDiscontinuity), Some(8L)),
            Discontinuity.MinorDiscontinuity, AdministrativeClass.State, reversed = false, 2, 8)
          , DateTime.now)
      )

      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(combGeom1.head, combGeom1.head), roadNumberLimits)).thenReturn(Seq(orderedlc1, orderedlc2, orderedlc3, orderedlc4))
      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(combGeom1.last, combGeom1.last), roadNumberLimits)).thenReturn(Seq(orderedlc1, orderedlc2, orderedlc3, orderedlc4))
      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(combGeom2.last, combGeom2.last), roadNumberLimits)).thenReturn(Seq(orderedlc1, orderedlc2, orderedlc3, orderedlc4))
      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(combGeom3.last, combGeom3.last), roadNumberLimits)).thenReturn(Seq(orderedlc1, orderedlc2, orderedlc3, orderedlc4))
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(any[Set[Long]], any[Boolean])).thenReturn(Seq(rw1WithId))

      val mappedReservedRoadwayNumbers = projectLinkDAO.fetchProjectLinksChange(projectId)
      roadAddressService.handleRoadwayPointsUpdate(projectChanges, mappedReservedRoadwayNumbers)
      nodesAndJunctionsService.handleNodePoints(projectChanges, combPLinks, mappedReservedRoadwayNumbers)
      nodesAndJunctionsService.handleJunctionAndJunctionPoints(projectChanges, combPLinks, mappedReservedRoadwayNumbers)

      val roadwayPoints = roadwayPointDAO.fetchByRoadwayNumbers(combPLinks.map(_.roadwayNumber)).map(_.id)

      val junctionPointTemplates = junctionPointDAO.fetchByRoadwayPointIds(roadwayPoints)
      junctionPointTemplates.count(_.beforeAfter == BeforeAfter.Before) should be(1)
      junctionPointTemplates.count(_.beforeAfter == BeforeAfter.After) should be(1)

      val junctions = junctionDAO.fetchTemplatesByRoadwayNumbers(junctionPointTemplates.map(_.roadwayNumber).distinct)
      junctions.size should be(1)
    }
  }

  test("Test nodesAndJunctionsService.handleJunctionTemplates When creating a roundabout road part that connects to the same part of the same road number with an EndOfRoad connecting link, " +
    "and another road part with a different road number connected to it, then junction templates and junction points should be created at the start and endpoints of the roundabout and at the connection points of the connected roads."){
    runWithRollback {
      /*
                0---RL1-->|
                ^         |
           RL4  |         |   RL2
                |         V
                |<---RL3--0---CR-->

          * Note:
              0: Illustration where junction points should be created
              RL: Roundabout Links
              CR: Connected Road
               - ConnectedRoad starts from the 10 addrM of the roundabout
      */

      val roadForRoundabout = 20001
      val connectedRoad = 46999
      val part = 1L
      val projectId = Sequences.nextViiteProjectId
      val rwId = Sequences.nextRoadwayId
      val rwId2 = Sequences.nextRoadwayId
      val llId = Sequences.nextLinearLocationId
      val rwNumber = Sequences.nextRoadwayNumber
      val rwNumber2 = Sequences.nextRoadwayNumber
      val plId = Sequences.nextProjectLinkId

      // geometries for "roundabout" and connected road
      val roundaboutGeom1 = Seq(Point(5.0, 5.0), Point(10.0, 5.0))
      val roundaboutGeom2 = Seq(Point(10.0, 5.0), Point(10.0, 0.0))
      val roundaboutGeom3 = Seq(Point(10.0, 0.0), Point(5.0, 0.0))
      val roundaboutGeom4 = Seq(Point(5.0, 0.0), Point(5.0, 5.0))
      val connectingRoadGeom = Seq(Point(10.0, 0.0), Point(15.0, 0.0))

      // project links
      val roundaboutPLink1 = dummyProjectLink(RoadPart(roadForRoundabout, part), Track.Combined, Discontinuity.Continuous, AddrMRange( 0,  5), AddrMRange( 0,  5), Some(DateTime.now()), None, 12345.toString, 0, 5, SideCode.TowardsDigitizing, RoadAddressChangeType.New, projectId, AdministrativeClass.State, roundaboutGeom1, rwNumber).copy(id = plId, projectId = projectId, roadwayId = rwId, linearLocationId = llId)
      val roundaboutPLink2 = dummyProjectLink(RoadPart(roadForRoundabout, part), Track.Combined, Discontinuity.Continuous, AddrMRange( 5, 10), AddrMRange( 5, 10), Some(DateTime.now()), None, 12346.toString, 0, 5, SideCode.TowardsDigitizing, RoadAddressChangeType.New, projectId, AdministrativeClass.State, roundaboutGeom2, rwNumber).copy(id = plId + 1, projectId = projectId, roadwayId = rwId, linearLocationId = llId + 1)
      val roundaboutPLink3 = dummyProjectLink(RoadPart(roadForRoundabout, part), Track.Combined, Discontinuity.Continuous, AddrMRange(10, 15), AddrMRange(10, 15), Some(DateTime.now()), None, 12347.toString, 0, 5, SideCode.TowardsDigitizing, RoadAddressChangeType.New, projectId, AdministrativeClass.State, roundaboutGeom3, rwNumber).copy(id = plId + 2, projectId = projectId, roadwayId = rwId, linearLocationId = llId + 2)
      val roundaboutPLink4 = dummyProjectLink(RoadPart(roadForRoundabout, part), Track.Combined, Discontinuity.EndOfRoad,  AddrMRange(15, 20), AddrMRange(15, 20), Some(DateTime.now()), None, 12348.toString, 0, 5, SideCode.TowardsDigitizing, RoadAddressChangeType.New, projectId, AdministrativeClass.State, roundaboutGeom4, rwNumber).copy(id = plId + 3, projectId = projectId, roadwayId = rwId, linearLocationId = llId + 3)
      val connectedPLink   = dummyProjectLink(RoadPart(connectedRoad,     part), Track.Combined, Discontinuity.EndOfRoad,  AddrMRange( 0,  5), AddrMRange( 0,  5), Some(DateTime.now()), None, 12350.toString, 0, 5, SideCode.TowardsDigitizing, RoadAddressChangeType.New, projectId, AdministrativeClass.State, connectingRoadGeom, rwNumber2).copy(id = plId + 4, projectId = projectId, roadwayId = rwId2, linearLocationId = llId + 4)

      // project
      val project = Project(projectId, ProjectState.Incomplete, "f", "s", DateTime.now(), "", DateTime.now(), DateTime.now(), "", Seq(), Seq(), None, None)

      val projectLinks = Seq(roundaboutPLink1, roundaboutPLink2, roundaboutPLink3, roundaboutPLink4, connectedPLink)

      // linear locations
      val (lc1, rw1): (LinearLocation, Roadway) = Seq(roundaboutPLink1).map(toRoadwayAndLinearLocation).head
      val (lc2, _): (LinearLocation, Roadway) = Seq(roundaboutPLink2).map(toRoadwayAndLinearLocation).head
      val (lc3, _): (LinearLocation, Roadway) = Seq(roundaboutPLink3).map(toRoadwayAndLinearLocation).head
      val (lc4, _): (LinearLocation, Roadway) = Seq(roundaboutPLink4).map(toRoadwayAndLinearLocation).head
      //connecting roadway
      val (lc5, rw5): (LinearLocation, Roadway) = Seq(connectedPLink).map(toRoadwayAndLinearLocation).head

      // roadways
      val rw1WithId = rw1.copy(addrMRange = AddrMRange(0, 20), ely = 8L)
      val rw2WithId = rw5.copy(addrMRange = AddrMRange(0,  5), ely = 8L)

      // linear locations with order number
      val roundaboutLinearLocation1 = lc1.copy(orderNumber = 1)
      val roundaboutLinearLocation2 = lc2.copy(orderNumber = 2)
      val roundaboutLinearLocation3 = lc3.copy(orderNumber = 3)
      val roundaboutLinearLocation4 = lc4.copy(orderNumber = 4)
      val connectingLinearLocation = lc5.copy(orderNumber = 1)

      buildTestDataForProject(Some(project), Some(Seq(rw1WithId, rw2WithId)), Some(Seq(roundaboutLinearLocation1, roundaboutLinearLocation2, roundaboutLinearLocation3, roundaboutLinearLocation4, connectingLinearLocation)), Some(projectLinks))

      val projectChanges = List(
        // Roundabout
        ProjectRoadwayChange(projectId, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(RoadAddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, Some(AdministrativeClass.State), Some(Discontinuity.Continuous), Some(8L)),
            RoadwayChangeSection(Some(roadForRoundabout), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(part), endRoadPartNumber = Some(part), addrMRange = Some(AddrMRange(0L,15L)), Some(AdministrativeClass.State), Some(Discontinuity.Continuous), Some(8L)),
            Discontinuity.Continuous, AdministrativeClass.State, reversed = false, 1, 8)
          , DateTime.now),
        ProjectRoadwayChange(projectId, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(RoadAddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, Some(AdministrativeClass.State), Some(Discontinuity.EndOfRoad), Some(8L)),
            RoadwayChangeSection(Some(roadForRoundabout), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(part), endRoadPartNumber = Some(part), addrMRange = Some(AddrMRange(15L,20L)), Some(AdministrativeClass.State), Some(Discontinuity.EndOfRoad), Some(8L)),
            Discontinuity.EndOfRoad, AdministrativeClass.State, reversed = false, 2, 8)
          , DateTime.now),
        //Connected road
        ProjectRoadwayChange(projectId, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(RoadAddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, Some(AdministrativeClass.State), Some(Discontinuity.EndOfRoad), Some(8L)),
            RoadwayChangeSection(Some(connectedRoad), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(part), endRoadPartNumber = Some(part), addrMRange = Some(AddrMRange(0L,5L)), Some(AdministrativeClass.State), Some(Discontinuity.EndOfRoad), Some(8L)),
            Discontinuity.EndOfRoad, AdministrativeClass.State, reversed = false, 3, 8)
          , DateTime.now)
      )

      // mock fetches
      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(any[BoundingRectangle], any[Seq[(Int, Int)]])).thenReturn(Seq(roundaboutLinearLocation1, roundaboutLinearLocation2, roundaboutLinearLocation3, roundaboutLinearLocation4, connectingLinearLocation))
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(any[Set[Long]], any[Boolean])).thenReturn(Seq(rw1WithId, rw2WithId))

      // roadway changes
      val mappedProjectRoadwayChanges = projectLinkDAO.fetchProjectLinksChange(projectId)

      // handle roadway points, nodes and junctions
      roadAddressService.handleRoadwayPointsUpdate(projectChanges, mappedProjectRoadwayChanges)
      nodesAndJunctionsService.handleNodePoints(projectChanges, projectLinks, mappedProjectRoadwayChanges)
      nodesAndJunctionsService.handleJunctionAndJunctionPoints(projectChanges, projectLinks, mappedProjectRoadwayChanges)

      val roadwayPoints = roadwayPointDAO.fetchByRoadwayNumbers(projectLinks.map(_.roadwayNumber))

      val junctionPointTemplates = junctionPointDAO.fetchByRoadwayPointIds(roadwayPoints.map(_.id))

      /* There should be 5 junction point templates:
       - 4 on the roundabout:
         - 1 before and 1 after at the start/end of the roundabout
         - 1 before and 1 after at the point of connection of the connected road
       - 1 after junction point on the start of the connected road
       */
      junctionPointTemplates.length should be(5)
      junctionPointTemplates.count(_.beforeAfter == BeforeAfter.Before) should be(2)
      junctionPointTemplates.count(_.beforeAfter == BeforeAfter.After) should be(3)

      val junctions = junctionDAO.fetchTemplatesByRoadwayNumbers(junctionPointTemplates.map(_.roadwayNumber).distinct)
      junctions.size should be(2) // 1 junction for roundabout start/end and 1 for the connected road
    }
  }

  test("Test nodesAndJunctionsService.handleJunctionTemplates When terminating a road part connected to a roundabout, and its junctions and junction points are handled, " + "" +
    "then only the roundabout's start and endpoint junction and junction point templates should remain.") {
    runWithRollback {

      /*   Creating roundabout and connecting road:

                0---RL1-->|
                ^         |
           RL4  |         |   RL2
                |         V
                |<---RL3--0---CR-->

          * Note:
              0: Illustration where junction points are created
              RL: Roundabout Links
              CR: Connected Road
               - ConnectedRoad starts from the 10 addrM of the roundabout
      */

      val roadForRoundabout = 20001
      val connectedRoad = 46999
      val part = 1L
      val projectId = Sequences.nextViiteProjectId
      val rwId = Sequences.nextRoadwayId
      val rwId2 = Sequences.nextRoadwayId
      val llId = Sequences.nextLinearLocationId
      val rwNumber = Sequences.nextRoadwayNumber
      val rwNumber2 = Sequences.nextRoadwayNumber
      val plId = Sequences.nextProjectLinkId

      // geometries for "roundabout" and connected road
      val roundaboutGeom1 = Seq(Point(5.0, 5.0), Point(10.0, 5.0))
      val roundaboutGeom2 = Seq(Point(10.0, 5.0), Point(10.0, 0.0))
      val roundaboutGeom3 = Seq(Point(10.0, 0.0), Point(5.0, 0.0))
      val roundaboutGeom4 = Seq(Point(5.0, 0.0), Point(5.0, 5.0))
      val connectingRoadGeom = Seq(Point(10.0, 0.0), Point(15.0, 0.0))

      // project links
      val roundaboutPLink1 = dummyProjectLink(RoadPart(roadForRoundabout, part), Track.Combined, Discontinuity.Continuous, AddrMRange( 0,  5), AddrMRange( 0,  5), Some(DateTime.now()), None, 12345.toString, 0, 5, SideCode.TowardsDigitizing, RoadAddressChangeType.New, projectId, AdministrativeClass.State, roundaboutGeom1, rwNumber).copy(id = plId, projectId = projectId, roadwayId = rwId, linearLocationId = llId)
      val roundaboutPLink2 = dummyProjectLink(RoadPart(roadForRoundabout, part), Track.Combined, Discontinuity.Continuous, AddrMRange( 5, 10), AddrMRange( 5, 10), Some(DateTime.now()), None, 12346.toString, 0, 5, SideCode.TowardsDigitizing, RoadAddressChangeType.New, projectId, AdministrativeClass.State, roundaboutGeom2, rwNumber).copy(id = plId + 1, projectId = projectId, roadwayId = rwId, linearLocationId = llId + 1)
      val roundaboutPLink3 = dummyProjectLink(RoadPart(roadForRoundabout, part), Track.Combined, Discontinuity.Continuous, AddrMRange(10, 15), AddrMRange(10, 15), Some(DateTime.now()), None, 12347.toString, 0, 5, SideCode.TowardsDigitizing, RoadAddressChangeType.New, projectId, AdministrativeClass.State, roundaboutGeom3, rwNumber).copy(id = plId + 2, projectId = projectId, roadwayId = rwId, linearLocationId = llId + 2)
      val roundaboutPLink4 = dummyProjectLink(RoadPart(roadForRoundabout, part), Track.Combined, Discontinuity.EndOfRoad,  AddrMRange(15, 20), AddrMRange(15, 20), Some(DateTime.now()), None, 12348.toString, 0, 5, SideCode.TowardsDigitizing, RoadAddressChangeType.New, projectId, AdministrativeClass.State, roundaboutGeom4, rwNumber).copy(id = plId + 3, projectId = projectId, roadwayId = rwId, linearLocationId = llId + 3)
      val connectedPLink   = dummyProjectLink(RoadPart(connectedRoad,     part), Track.Combined, Discontinuity.EndOfRoad,  AddrMRange( 0,  5), AddrMRange( 0,  5), Some(DateTime.now()), None, 12350.toString, 0, 5, SideCode.TowardsDigitizing, RoadAddressChangeType.New, projectId, AdministrativeClass.State, connectingRoadGeom, rwNumber2).copy(id = plId + 4, projectId = projectId, roadwayId = rwId2, linearLocationId = llId + 4)

      // project
      val project = Project(projectId, ProjectState.Incomplete, "f", "s", DateTime.now(), "", DateTime.now(), DateTime.now(), "", Seq(), Seq(), None, None)

      val projectLinks = Seq(roundaboutPLink1, roundaboutPLink2, roundaboutPLink3, roundaboutPLink4, connectedPLink)

      // linear locations
      val (lc1, rw1): (LinearLocation, Roadway) = Seq(roundaboutPLink1).map(toRoadwayAndLinearLocation).head
      val (lc2, _): (LinearLocation, Roadway) = Seq(roundaboutPLink2).map(toRoadwayAndLinearLocation).head
      val (lc3, _): (LinearLocation, Roadway) = Seq(roundaboutPLink3).map(toRoadwayAndLinearLocation).head
      val (lc4, _): (LinearLocation, Roadway) = Seq(roundaboutPLink4).map(toRoadwayAndLinearLocation).head
      //connecting roadway
      val (lc5, rw5): (LinearLocation, Roadway) = Seq(connectedPLink).map(toRoadwayAndLinearLocation).head

      // roadways
      val rw1WithId = rw1.copy(addrMRange = AddrMRange(0, 20), ely = 8L)
      val rw2WithId = rw5.copy(addrMRange = AddrMRange(0,  5), ely = 8L)

      // linear locations with order number
      val roundaboutLinearLocation1 = lc1.copy(orderNumber = 1)
      val roundaboutLinearLocation2 = lc2.copy(orderNumber = 2)
      val roundaboutLinearLocation3 = lc3.copy(orderNumber = 3)
      val roundaboutLinearLocation4 = lc4.copy(orderNumber = 4)
      val connectingLinearLocation = lc5.copy(orderNumber = 1)

      buildTestDataForProject(Some(project), Some(Seq(rw1WithId, rw2WithId)), Some(Seq(roundaboutLinearLocation1, roundaboutLinearLocation2, roundaboutLinearLocation3, roundaboutLinearLocation4, connectingLinearLocation)), Some(projectLinks))

      val projectChanges = List(
        // Roundabout
        ProjectRoadwayChange(projectId, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(RoadAddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, Some(AdministrativeClass.State), Some(Discontinuity.Continuous), Some(8L)),
            RoadwayChangeSection(Some(roadForRoundabout), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(part), endRoadPartNumber = Some(part), addrMRange = Some(AddrMRange(0L,15L)), Some(AdministrativeClass.State), Some(Discontinuity.Continuous), Some(8L)),
            Discontinuity.Continuous, AdministrativeClass.State, reversed = false, 1, 8)
          , DateTime.now),
        ProjectRoadwayChange(projectId, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(RoadAddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, Some(AdministrativeClass.State), Some(Discontinuity.EndOfRoad), Some(8L)),
            RoadwayChangeSection(Some(roadForRoundabout), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(part), endRoadPartNumber = Some(part), addrMRange = Some(AddrMRange(15L,20L)), Some(AdministrativeClass.State), Some(Discontinuity.EndOfRoad), Some(8L)),
            Discontinuity.EndOfRoad, AdministrativeClass.State, reversed = false, 2, 8)
          , DateTime.now),
        //Connected road
        ProjectRoadwayChange(projectId, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(RoadAddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, Some(AdministrativeClass.State), Some(Discontinuity.EndOfRoad), Some(8L)),
            RoadwayChangeSection(Some(connectedRoad), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(part), endRoadPartNumber = Some(part), addrMRange = Some(AddrMRange(0L,5L)), Some(AdministrativeClass.State), Some(Discontinuity.EndOfRoad), Some(8L)),
            Discontinuity.EndOfRoad, AdministrativeClass.State, reversed = false, 3, 8)
          , DateTime.now)
      )

      // mock fetches
      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(any[BoundingRectangle], any[Seq[(Int, Int)]])).thenReturn(Seq(roundaboutLinearLocation1, roundaboutLinearLocation2, roundaboutLinearLocation3, roundaboutLinearLocation4, connectingLinearLocation))
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(any[Set[Long]], any[Boolean])).thenReturn(Seq(rw1WithId, rw2WithId))

      // roadway changes
      val mappedProjectRoadwayChanges = projectLinkDAO.fetchProjectLinksChange(projectId)

      // handle roadway points, nodes and junctions
      roadAddressService.handleRoadwayPointsUpdate(projectChanges, mappedProjectRoadwayChanges)
      nodesAndJunctionsService.handleNodePoints(projectChanges, projectLinks, mappedProjectRoadwayChanges)
      nodesAndJunctionsService.handleJunctionAndJunctionPoints(projectChanges, projectLinks, mappedProjectRoadwayChanges)


      /*  Terminating connected road:

                0---RL1-->|
                ^         |
           RL4  |         |   RL2
                |         V
                |<---RL3--- -(CR to be terminated)-

          * Note:
              0: Only one junction should remain after termination of CR
              RL: Roundabout Links
              CR: Connected Road

      */
      val projectId2 = Sequences.nextViiteProjectId
      val project2 = Project(projectId2, ProjectState.Incomplete, "ProjectTerminatedLinks", "s", DateTime.now(), "", DateTime.now(), DateTime.now(), "", Seq(), Seq(), None, None)

      // project link
      val terminatedLink = dummyProjectLink(RoadPart(connectedRoad, part), Track.Combined, Discontinuity.EndOfRoad, AddrMRange(0, 5), AddrMRange(0, 5), Some(DateTime.now()), None, 12350.toString, 0, 5, SideCode.TowardsDigitizing, RoadAddressChangeType.Termination, projectId2, AdministrativeClass.State, connectingRoadGeom, rwNumber2).copy(id = plId + 5, projectId = projectId2, roadwayId = rwId2, linearLocationId = llId + 4)

      // project changes
      val terminatingProjectChanges = List(
        ProjectRoadwayChange(projectId2, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(RoadAddressChangeType.Termination,
            RoadwayChangeSection(Some(connectedRoad), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(part), endRoadPartNumber = Some(part), addrMRange = Some(AddrMRange(0L,5L)), Some(AdministrativeClass.State), Some(Discontinuity.MinorDiscontinuity), Some(8L)),
            RoadwayChangeSection(None, None, None, None, None, Some(AdministrativeClass.State), Some(Discontinuity.MinorDiscontinuity), Some(8L)),
            Discontinuity.EndOfRoad, AdministrativeClass.State, reversed = false, 1, 8), DateTime.now)
      )

      buildTestDataForProject(Some(project2), None, None, Some(Seq(terminatedLink)))

      // expire linear location and roadway
      linearLocationDAO.expireByRoadwayNumbers(Set(terminatedLink.roadwayNumber))
      roadwayDAO.expireHistory(Set(terminatedLink.roadwayId))

      val mappedAfterChanges: Seq[ProjectRoadLinkChange] = projectLinkDAO.fetchProjectLinksChange(projectId2)

      projectLinkDAO.moveProjectLinksToHistory(projectId2)
      // handle roadway points, nodes and junctions
      roadAddressService.handleRoadwayPointsUpdate(terminatingProjectChanges, mappedAfterChanges)
      nodesAndJunctionsService.handleNodePoints(terminatingProjectChanges, Seq(terminatedLink), mappedAfterChanges)
      nodesAndJunctionsService.handleJunctionAndJunctionPoints(terminatingProjectChanges, Seq(terminatedLink), mappedAfterChanges)

      val endDate = Some(project2.startDate.minusDays(1))
      nodesAndJunctionsService.expireObsoleteNodesAndJunctions(Seq(terminatedLink), endDate)

      // check that the junction points are removed from connected road link's roadway points
      val connectingLinkRoadwayPointsAfterTermination = roadwayPointDAO.fetchByRoadwayNumbers(Seq(terminatedLink.roadwayNumber))
      val connectingLinkJunctionPointsAfterTermination: Seq[JunctionPoint] = junctionPointDAO.fetchByRoadwayPointIds(connectingLinkRoadwayPointsAfterTermination.map(_.id))
      connectingLinkJunctionPointsAfterTermination.length should be(0)

      // check that only roundabout junction points should remain
      val allRoadwayPointsAfterTermination = roadwayPointDAO.fetchByRoadwayNumbers(projectLinks.map(_.roadwayNumber))
      val allJunctionPointsAfterTermination: Seq[JunctionPoint] = junctionPointDAO.fetchByRoadwayPointIds(allRoadwayPointsAfterTermination.map(_.id))

      // there should be only 2 junction points:
      // 1 before and 1 after on the start/end of the roundabout
      allJunctionPointsAfterTermination.length should be(2)
      allJunctionPointsAfterTermination.count(_.beforeAfter == BeforeAfter.Before) should be(1)
      allJunctionPointsAfterTermination.count(_.beforeAfter == BeforeAfter.After) should be(1)

      // only one junction should remain after the termination
      val allJunctionsAfterTermination = junctionDAO.fetchTemplatesByRoadwayNumbers(allRoadwayPointsAfterTermination.map(_.roadwayNumber).distinct)
      allJunctionsAfterTermination.size should be(1)
    }
  }

  test("Test nodesAndJunctionsService.handleNodePointTemplates When creating projectLinks Then node points template should be handled/created properly and" +
    " When reverse, the node points BeforeAfter should be reversed") {
    runWithRollback {
      /*
      |--L-->|
              |0--C1-->0|0--C2-->0|
      |0--R-->0|
       */

      val leftGeom = Seq(Point(0.0, 10.0), Point(50.0, 5.0))
      val rightGeom = Seq(Point(0.0, 0.0), Point(50.0, 5.0))
      val combGeom1 = Seq(Point(50.0, 5.0), Point(100.0, 5.0))
      val combGeom2 = Seq(Point(100.0, 5.0), Point(150.0, 5.0))

      val roadwayNumber = Sequences.nextRoadwayNumber
      val projectId = Sequences.nextViiteProjectId
      val linearLocationId = Sequences.nextLinearLocationId
      val roadwayId = Sequences.nextRoadwayId

      val project = Project(projectId, ProjectState.Incomplete, "f", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
        "", Seq(), Seq(), None, None)
      val id = Sequences.nextProjectLinkId


      val roadways = Seq(
        Roadway(roadwayId,     roadwayNumber,     RoadPart(999, 999), AdministrativeClass.State, Track.LeftSide,  Discontinuity.Continuous,AddrMRange(  0,  50), reversed = false, DateTime.parse("2000-01-01"), None, "test", Some("TEST ROAD 1"), 1, TerminationCode.NoTermination),
        Roadway(roadwayId + 1, roadwayNumber + 1, RoadPart(999, 999), AdministrativeClass.State, Track.RightSide, Discontinuity.Continuous,AddrMRange(  0,  50), reversed = false, DateTime.parse("2000-01-01"), None, "test", Some("TEST ROAD 1"), 1, TerminationCode.NoTermination),
        Roadway(roadwayId + 2, roadwayNumber + 2, RoadPart(999, 999), AdministrativeClass.State, Track.Combined,  Discontinuity.Continuous,AddrMRange( 50, 100), reversed = false, DateTime.parse("2000-01-01"), None, "test", Some("TEST ROAD 1"), 1, TerminationCode.NoTermination),
        Roadway(roadwayId + 3, roadwayNumber + 3, RoadPart(999, 999), AdministrativeClass.State, Track.Combined,  Discontinuity.EndOfRoad, AddrMRange(100, 150), reversed = false, DateTime.parse("2000-01-01"), None, "test", Some("TEST ROAD 1"), 1, TerminationCode.NoTermination)
      )

      val linearLocations = Seq(
        LinearLocation(linearLocationId,     1, 1000L.toString, 0.0, 50.0, SideCode.TowardsDigitizing, 10000000000L, (CalibrationPointReference(Some(0L)), CalibrationPointReference.None), Seq(Point(0.0, 0.0), Point(50.0, 0.0)), LinkGeomSource.ComplementaryLinkInterface, roadwayNumber, Some(DateTime.parse("2000-01-01")), None),
        LinearLocation(linearLocationId + 1, 1, 2000L.toString, 0.0, 10.0, SideCode.TowardsDigitizing, 10000000000L, (CalibrationPointReference.None, CalibrationPointReference.None), Seq(Point(0.0, 10.0), Point(50.0, 5.0)), LinkGeomSource.ComplementaryLinkInterface, roadwayNumber + 1, Some(DateTime.parse("2000-01-01")), None),
        LinearLocation(linearLocationId + 2, 1, 3000L.toString, 0.0, 10.0, SideCode.TowardsDigitizing, 10000000000L, (CalibrationPointReference.None, CalibrationPointReference.None), Seq(Point(50.0, 5.0), Point(100.0, 5.0)), LinkGeomSource.ComplementaryLinkInterface, roadwayNumber + 2, Some(DateTime.parse("2000-01-01")), None),
        LinearLocation(linearLocationId + 3, 1, 3000L.toString, 0.0, 10.0, SideCode.TowardsDigitizing, 10000000000L, (CalibrationPointReference.None, CalibrationPointReference(Some(150L))), Seq(Point(100.0, 5.0), Point(150.0, 5.0)), LinkGeomSource.ComplementaryLinkInterface, roadwayNumber + 3, Some(DateTime.parse("2000-01-01")), None)
      )
      val roadwayIds = roadwayDAO.create(roadways)

      val left      = dummyProjectLink(RoadPart(999, 999), Track.LeftSide,  Discontinuity.Continuous,AddrMRange(  0,  50), AddrMRange(  0,  50), Some(DateTime.now()), None, 12345.toString, 0, 50, SideCode.TowardsDigitizing, RoadAddressChangeType.Transfer, 0L, AdministrativeClass.State,        leftGeom,  roadwayNumber    ).copy(id = id,     projectId = projectId, roadwayId = roadwayId,     linearLocationId = linearLocationId)
      val right     = dummyProjectLink(RoadPart(999, 999), Track.RightSide, Discontinuity.Continuous,AddrMRange(  0,  50), AddrMRange(  0,  50), Some(DateTime.now()), None, 12346.toString, 0, 50, SideCode.TowardsDigitizing, RoadAddressChangeType.Transfer, 0L, AdministrativeClass.State,        rightGeom, roadwayNumber + 1).copy(id = id + 1, projectId = projectId, roadwayId = roadwayId + 1, linearLocationId = linearLocationId + 1)
      val combined1 = dummyProjectLink(RoadPart(999, 999), Track.Combined,  Discontinuity.Continuous,AddrMRange( 50, 100), AddrMRange( 50, 100), Some(DateTime.now()), None, 12347.toString, 0, 50, SideCode.TowardsDigitizing, RoadAddressChangeType.Transfer, 0L, AdministrativeClass.Municipality, combGeom1, roadwayNumber + 2).copy(id = id + 2, projectId = projectId, roadwayId = roadwayId + 2, linearLocationId = linearLocationId + 2)
      val combined2 = dummyProjectLink(RoadPart(999, 999), Track.Combined,  Discontinuity.EndOfRoad, AddrMRange(100, 150), AddrMRange(100, 150), Some(DateTime.now()), None, 12348.toString, 0, 50, SideCode.TowardsDigitizing, RoadAddressChangeType.Transfer, 0L, AdministrativeClass.State,        combGeom2, roadwayNumber + 3).copy(id = id + 3, projectId = projectId, roadwayId = roadwayId + 3, linearLocationId = linearLocationId + 3)
      val pls = Seq(left, right, combined1, combined2)
      buildTestDataForProject(Some(project), None, Some(linearLocations), Some(pls))

      val projectChanges = List(
        //left
        ProjectRoadwayChange(0, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(RoadAddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, Some(AdministrativeClass.State), Some(Discontinuity.Continuous), Some(8L)),
            RoadwayChangeSection(Some(999), Some(Track.LeftSide.value.toLong), startRoadPartNumber = Some(999L), endRoadPartNumber = Some(999L), addrMRange = Some(AddrMRange(0L,50L)), Some(AdministrativeClass.State), Some(Discontinuity.Continuous), Some(8L)),
            Discontinuity.Continuous, AdministrativeClass.State, reversed = false, 1, 8)
          , DateTime.now),
        //right
        ProjectRoadwayChange(0, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(RoadAddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, Some(AdministrativeClass.State), Some(Discontinuity.Continuous), Some(8L)),
            RoadwayChangeSection(Some(999), Some(Track.RightSide.value.toLong), startRoadPartNumber = Some(999L), endRoadPartNumber = Some(999L), addrMRange = Some(AddrMRange(0L,50L)), Some(AdministrativeClass.State), Some(Discontinuity.Continuous), Some(8L)),
            Discontinuity.Continuous, AdministrativeClass.State, reversed = false, 2, 8)
          , DateTime.now),
        //combined
        ProjectRoadwayChange(0, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(RoadAddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, Some(AdministrativeClass.State), Some(Discontinuity.Continuous), Some(8L)),
            RoadwayChangeSection(Some(999), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(999L), endRoadPartNumber = Some(999L), addrMRange = Some(AddrMRange(50L,100L)), Some(AdministrativeClass.Municipality), Some(Discontinuity.Continuous), Some(8L)),
            Discontinuity.Continuous, AdministrativeClass.Municipality, reversed = false, 3, 8)
          , DateTime.now),
        ProjectRoadwayChange(0, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(RoadAddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, Some(AdministrativeClass.State), Some(Discontinuity.Continuous), Some(8L)),
            RoadwayChangeSection(Some(999), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(999L), endRoadPartNumber = Some(999L), addrMRange = Some(AddrMRange(100L,150L)), Some(AdministrativeClass.State), Some(Discontinuity.EndOfRoad), Some(8L)),
            Discontinuity.Continuous, AdministrativeClass.State, reversed = false, 3, 8)
          , DateTime.now)
      )
      val mappedChanges = projectLinkDAO.fetchProjectLinksChange(projectId)
      nodesAndJunctionsService.handleNodePoints(projectChanges, pls, mappedChanges)

      val fetchedNodesPoints = pls.flatMap(pl => nodePointDAO.fetchTemplatesByRoadwayNumber(pl.roadwayNumber)).sortBy(_.id)
      val np1 = fetchedNodesPoints.find(n => n.roadwayNumber == left.roadwayNumber && n.beforeAfter == BeforeAfter.After)
      np1.isEmpty should be(true)
      val np2 = fetchedNodesPoints.find(n => n.roadwayNumber == left.roadwayNumber && n.beforeAfter == BeforeAfter.Before)
      np2.isEmpty should be(true)
      val np3 = fetchedNodesPoints.find(n => n.roadwayNumber == right.roadwayNumber && n.beforeAfter == BeforeAfter.After)
      np3.nonEmpty should be(true)
      val np4 = fetchedNodesPoints.find(n => n.roadwayNumber == right.roadwayNumber && n.beforeAfter == BeforeAfter.Before)
      np4.nonEmpty should be(true)
      val np5 = fetchedNodesPoints.find(n => n.roadwayNumber == combined1.roadwayNumber && n.beforeAfter == BeforeAfter.After)
      np5.nonEmpty should be(true)
      val np6 = fetchedNodesPoints.find(n => n.roadwayNumber == combined2.roadwayNumber && n.beforeAfter == BeforeAfter.Before)
      np6.nonEmpty should be(true)

      //testing reverse
      /*
     |<--L--|
             |0<--C1--0|0<--C2--0|
     |0<--R--0|
      */

      val reversedLeft      =      left.copy(discontinuity = Discontinuity.EndOfRoad,  addrMRange = AddrMRange(100, 150), sideCode = SideCode.switch(left.sideCode), status = RoadAddressChangeType.Transfer, reversed = true)
      val reversedRight     =     right.copy(discontinuity = Discontinuity.EndOfRoad,  addrMRange = AddrMRange(100, 150), sideCode = SideCode.switch(left.sideCode), status = RoadAddressChangeType.Transfer, reversed = true)
      val reversedCombined1 = combined1.copy(discontinuity = Discontinuity.Continuous, addrMRange = AddrMRange( 50, 100), sideCode = SideCode.switch(left.sideCode), status = RoadAddressChangeType.Transfer, reversed = true)
      val reversedCombined2 = combined2.copy(discontinuity = Discontinuity.Continuous, addrMRange = AddrMRange(  0,  50), sideCode = SideCode.switch(left.sideCode), status = RoadAddressChangeType.Transfer, reversed = true)

      val reversedProjectChanges = List(
        //left
        ProjectRoadwayChange(0, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(RoadAddressChangeType.Transfer,
            RoadwayChangeSection(Some(999), Some(Track.LeftSide.value.toLong), startRoadPartNumber = Some(999L), endRoadPartNumber = Some(999L), addrMRange = Some(AddrMRange( 0L, 50L)), Some(AdministrativeClass.State), Some(Discontinuity.Continuous), Some(8L)),
            RoadwayChangeSection(Some(999), Some(Track.LeftSide.value.toLong), startRoadPartNumber = Some(999L), endRoadPartNumber = Some(999L), addrMRange = Some(AddrMRange(100,150L)), Some(AdministrativeClass.State), Some(Discontinuity.EndOfRoad), Some(8L)),
            Discontinuity.Continuous, AdministrativeClass.State, reversed = true, 1, 8)
          , DateTime.now),
        //right
        ProjectRoadwayChange(0, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(RoadAddressChangeType.Transfer,
            RoadwayChangeSection(Some(999), Some(Track.RightSide.value.toLong), startRoadPartNumber = Some(999L), endRoadPartNumber = Some(999L), addrMRange = Some(AddrMRange( 0L, 50L)), Some(AdministrativeClass.State), Some(Discontinuity.Continuous), Some(8L)),
            RoadwayChangeSection(Some(999), Some(Track.RightSide.value.toLong), startRoadPartNumber = Some(999L), endRoadPartNumber = Some(999L), addrMRange = Some(AddrMRange(100,150L)), Some(AdministrativeClass.State), Some(Discontinuity.EndOfRoad), Some(8L)),
            Discontinuity.Continuous, AdministrativeClass.State, reversed = true, 2, 8)
          , DateTime.now),
        ProjectRoadwayChange(0, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(RoadAddressChangeType.Transfer,
            RoadwayChangeSection(Some(999), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(999L), endRoadPartNumber = Some(999L), addrMRange = Some(AddrMRange(50L,100L)), Some(AdministrativeClass.State), Some(Discontinuity.Continuous), Some(8L)),
            RoadwayChangeSection(Some(999), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(999L), endRoadPartNumber = Some(999L), addrMRange = Some(AddrMRange(50L,100L)), Some(AdministrativeClass.State), Some(Discontinuity.Continuous), Some(8L)),
            Discontinuity.Continuous, AdministrativeClass.State, reversed = true, 2, 8)
          , DateTime.now),
        //combined
        ProjectRoadwayChange(0, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(RoadAddressChangeType.Transfer,
            RoadwayChangeSection(Some(999), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(999L), endRoadPartNumber = Some(999L), addrMRange = Some(AddrMRange(100,150L)), Some(AdministrativeClass.State), Some(Discontinuity.EndOfRoad), Some(8L)),
            RoadwayChangeSection(Some(999), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(999L), endRoadPartNumber = Some(999L), addrMRange = Some(AddrMRange( 0L, 50L)), Some(AdministrativeClass.State), Some(Discontinuity.Continuous), Some(8L)),
            Discontinuity.Continuous, AdministrativeClass.State, reversed = true, 3, 8)
          , DateTime.now)
      )

      val reversedPls = Seq(reversedCombined1, reversedCombined2, reversedRight, reversedLeft)


      val leftReversedRoadway      = Roadway(roadwayId + 4, left.roadwayNumber,      RoadPart(999, 999), AdministrativeClass.State, Track.LeftSide,  Discontinuity.EndOfRoad, AddrMRange(100, 150), reversed = true, DateTime.parse("2000-01-01"), None, "test", Some("TEST ROAD 1"), 1, TerminationCode.NoTermination)
      val rightReversedRoadway     = Roadway(roadwayId + 5, right.roadwayNumber,     RoadPart(999, 999), AdministrativeClass.State, Track.RightSide, Discontinuity.EndOfRoad, AddrMRange(100, 150), reversed = true, DateTime.parse("2000-01-01"), None, "test", Some("TEST ROAD 1"), 1, TerminationCode.NoTermination)
      val combined1ReversedRoadway = Roadway(roadwayId + 6, combined1.roadwayNumber, RoadPart(999, 999), AdministrativeClass.State, Track.Combined,  Discontinuity.Continuous,AddrMRange( 50, 100), reversed = true, DateTime.parse("2000-01-01"), None, "test", Some("TEST ROAD 1"), 1, TerminationCode.NoTermination)
      val combined2ReversedRoadway = Roadway(roadwayId + 7, combined2.roadwayNumber, RoadPart(999, 999), AdministrativeClass.State, Track.Combined,  Discontinuity.Continuous,AddrMRange(  0,  50), reversed = true, DateTime.parse("2000-01-01"), None, "test", Some("TEST ROAD 1"), 1, TerminationCode.NoTermination)

      val reversedRoadways = Seq(leftReversedRoadway, rightReversedRoadway, combined1ReversedRoadway, combined2ReversedRoadway)


      roadwayDAO.expireById(roadwayIds.toSet)

      val reversedRoadwayIds = roadwayDAO.create(reversedRoadways)

      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(Set(leftReversedRoadway.roadwayNumber))).thenReturn(Seq(leftReversedRoadway))
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(Set(rightReversedRoadway.roadwayNumber))).thenReturn(Seq(rightReversedRoadway))
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(Set(combined1ReversedRoadway.roadwayNumber))).thenReturn(Seq(combined1ReversedRoadway))
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(Set(combined2ReversedRoadway.roadwayNumber))).thenReturn(Seq(combined2ReversedRoadway))
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(Set(leftReversedRoadway.roadwayNumber,rightReversedRoadway.roadwayNumber))).thenReturn(Seq(leftReversedRoadway,rightReversedRoadway))

      //projectlinks are now reversed
      projectLinkDAO.updateProjectLinks(reversedPls, "user", Seq())
      val mappedRoadwayNumbers = projectLinkDAO.fetchProjectLinksChange(projectId)
      roadAddressService.handleRoadwayPointsUpdate(reversedProjectChanges, mappedRoadwayNumbers)
      nodesAndJunctionsService.handleNodePoints(reversedProjectChanges, reversedPls, mappedRoadwayNumbers)
      nodesAndJunctionsService.expireObsoleteNodesAndJunctions(reversedPls, Some(DateTime.now()), "test")
      val fetchedReversedNodesPoints = reversedPls.flatMap(pl => nodePointDAO.fetchTemplatesByRoadwayNumber(pl.roadwayNumber)).sortBy(_.id)
      fetchedNodesPoints.size should be(fetchedReversedNodesPoints.size)
      fetchedNodesPoints.zip(fetchedReversedNodesPoints.reverse).foreach { case (before, after) =>
        before.beforeAfter should be(BeforeAfter.switch(after.beforeAfter))
      }
    }
  }

  /**
    * Test case for Termination:
    * Reserve road number 1 and road part 2
    * * Assume that road number 1 road part 1, is just before it.
    * Terminate road number 1 and road part 2
    *
    * Expected:
    * Node Point at the end of road part 2 should be expired.
    * Node at the end of road part 2 should expire conditionally:
    * * If there are no more node points nor junctions referenced by this nodeId, then expire the node.
    */
  test("Test expireObsoleteNodesAndJunctions case When road part is terminated Then also node points for terminated road should be expired") {
    runWithRollback {
      val roadGeom1 = Seq(Point(0.0, 0.0), Point(100.0, 0.0))
      val roadGeom2 = Seq(Point(100.0, 0.0), Point(250.0, 0.0))

      val roadwayNumber = Sequences.nextRoadwayNumber
      val projectId = Sequences.nextViiteProjectId
      val plId1 = projectId + 1
      val plId2 = projectId + 2
      val roadLink1 = dummyProjectLink(RoadPart(1, 1), Track.Combined, Discontinuity.Continuous, AddrMRange(0, 100), AddrMRange(0, 100), Some(DateTime.now()), None, 12345.toString, 0, 100.0, SideCode.TowardsDigitizing, RoadAddressChangeType.New, projectId, AdministrativeClass.State, roadGeom1 , roadwayNumber    ).copy(id = plId1)
      val roadLink2 = dummyProjectLink(RoadPart(1, 2), Track.Combined, Discontinuity.Continuous, AddrMRange(0, 150), AddrMRange(0, 150), Some(DateTime.now()), None, 12346.toString, 0, 150.0, SideCode.TowardsDigitizing, RoadAddressChangeType.New, projectId, AdministrativeClass.State, roadGeom2, roadwayNumber + 1).copy(id = plId2)

      val project = Project(projectId, ProjectState.Incomplete, "f", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
        "", Seq(), Seq(), None, None)


      val roadways = Seq(
        Roadway(NewIdValue, roadLink1.roadwayNumber, roadLink1.roadPart, roadLink1.administrativeClass, roadLink1.track, roadLink1.discontinuity, roadLink1.addrMRange, reversed = false, roadLink1.startDate.get, roadLink1.endDate, "user", None, 8L, TerminationCode.NoTermination, DateTime.now, None),
        Roadway(NewIdValue, roadLink2.roadwayNumber, roadLink2.roadPart, roadLink2.administrativeClass, roadLink2.track, roadLink2.discontinuity, roadLink2.addrMRange, reversed = false, roadLink2.startDate.get, roadLink2.endDate, "user", None, 8L, TerminationCode.NoTermination, DateTime.now, None))
      val linearLocations = Seq(
        LinearLocation(NewIdValue, 1, roadLink1.linkId, roadLink1.addrMRange.start, roadLink1.addrMRange.end, roadLink1.sideCode, 0L, calibrationPoints = (CalibrationPointReference(Some(roadLink1.addrMRange.start)), CalibrationPointReference(Some(roadLink1.addrMRange.end))), roadGeom1, LinkGeomSource.NormalLinkInterface, roadLink1.roadwayNumber, Some(DateTime.now), None),
        LinearLocation(NewIdValue, 1, roadLink2.linkId, roadLink2.addrMRange.start, roadLink2.addrMRange.end, roadLink2.sideCode, 0L, calibrationPoints = (CalibrationPointReference(Some(roadLink2.addrMRange.start)), CalibrationPointReference(Some(roadLink2.addrMRange.end))), roadGeom2, LinkGeomSource.NormalLinkInterface, roadLink2.roadwayNumber, Some(DateTime.now), None))

      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(any[BoundingRectangle], any[Seq[(Int, Int)]])).thenReturn(linearLocations)
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(any[Set[Long]], any[Boolean])).thenReturn(roadways)

      val projectLinks = Seq(roadLink1, roadLink2)
      buildTestDataForProject(Some(project), None, Some(linearLocations), Some(projectLinks))

      roadwayDAO.create(roadways)

      val projectChanges = List(
        //combined
        ProjectRoadwayChange(0, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(RoadAddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, Some(AdministrativeClass.State), Some(Discontinuity.Continuous), Some(8L)),
            RoadwayChangeSection(Some(roadLink1.roadPart.roadNumber), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(roadLink1.roadPart.partNumber), endRoadPartNumber = Some(roadLink1.roadPart.partNumber), addrMRange = Some(AddrMRange(0L,100L)), Some(AdministrativeClass.State), Some(Discontinuity.Continuous), Some(8L)),
            Discontinuity.Continuous, AdministrativeClass.State, reversed = false, 1, 8)
          , DateTime.now),
        ProjectRoadwayChange(0, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(RoadAddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, Some(AdministrativeClass.State), Some(Discontinuity.Continuous), Some(8L)),
            RoadwayChangeSection(Some(roadLink2.roadPart.roadNumber), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(roadLink2.roadPart.partNumber), endRoadPartNumber = Some(roadLink2.roadPart.partNumber), addrMRange = Some(AddrMRange(0L,150L)), Some(AdministrativeClass.State), Some(Discontinuity.Continuous), Some(8L)),
            Discontinuity.Continuous, AdministrativeClass.State, reversed = false, 2, 8)
          , DateTime.now)
      )
      val mappedRoadwayNumbers = projectLinkDAO.fetchProjectLinksChange(projectId)
      // Creation of nodes and node points
      nodesAndJunctionsService.handleNodePoints(projectChanges, projectLinks, mappedRoadwayNumbers)

      val nodeNumber1 = Sequences.nextNodeNumber
      val nodeNumber2 = Sequences.nextNodeNumber
      val nodeNumber3 = Sequences.nextNodeNumber
      val node1 = Node(NewIdValue, nodeNumber1, roadLink1.geometry.head, None, NodeType.EndOfRoad, DateTime.now(), None, DateTime.now(), None, "user", Some(DateTime.now()), registrationDate = new DateTime())
      val node2 = Node(NewIdValue, nodeNumber2, roadLink2.geometry.head, None, NodeType.EndOfRoad, DateTime.now(), None, DateTime.now(), None, "user", Some(DateTime.now()), registrationDate = new DateTime())
      val node3 = Node(NewIdValue, nodeNumber3, roadLink2.geometry.last, None, NodeType.EndOfRoad, DateTime.now(), None, DateTime.now(), None, "user", Some(DateTime.now()), registrationDate = new DateTime())

      nodeDAO.create(Seq(node1, node2, node3))

      val nodePointTemplates = projectLinks.flatMap(pl => nodePointDAO.fetchTemplatesByRoadwayNumber(pl.roadwayNumber))
      nodePointTemplates.length should be(4)
      nodePointTemplates.foreach(
        np => {
          if (np.roadwayNumber == roadLink1.roadwayNumber) {
            if (roadLink1.addrMRange.startsAt(np.addrM))
              runUpdateToDb(sql"""UPDATE NODE_POINT SET NODE_NUMBER = $nodeNumber1 WHERE ID = ${np.id}""")
            else if (roadLink1.addrMRange.endsAt(np.addrM))
              runUpdateToDb(sql"""UPDATE NODE_POINT SET NODE_NUMBER = $nodeNumber2 WHERE ID = ${np.id}""")
          } else if (np.roadwayNumber == roadLink2.roadwayNumber) {
            if (roadLink2.addrMRange.startsAt(np.addrM))
              runUpdateToDb(sql"""UPDATE NODE_POINT SET NODE_NUMBER = $nodeNumber2 WHERE ID = ${np.id}""")
            else if (roadLink2.addrMRange.endsAt(np.addrM))
              runUpdateToDb(sql"""UPDATE NODE_POINT SET NODE_NUMBER = $nodeNumber3 WHERE ID = ${np.id}""")
          }
        }
      )

      val nodePoints = nodePointDAO.fetchByNodeNumbers(Seq(nodeNumber1, nodeNumber2, nodeNumber3))
      nodePoints.length should be(4)
      nodePoints.exists(node => node.roadwayNumber == roadLink1.roadwayNumber && node.beforeAfter == BeforeAfter.After  && roadLink1.addrMRange.startsAt(node.addrM)) should be(true)
      nodePoints.exists(node => node.roadwayNumber == roadLink1.roadwayNumber && node.beforeAfter == BeforeAfter.Before && roadLink1.addrMRange.endsAt  (node.addrM)) should be(true)
      nodePoints.exists(node => node.roadwayNumber == roadLink2.roadwayNumber && node.beforeAfter == BeforeAfter.After  && roadLink2.addrMRange.startsAt(node.addrM)) should be(true)
      nodePoints.exists(node => node.roadwayNumber == roadLink2.roadwayNumber && node.beforeAfter == BeforeAfter.Before && roadLink2.addrMRange.endsAt  (node.addrM)) should be(true)

      val terminatedRoadLink = roadLink2.copy(endDate = Some(DateTime.now().withTimeAtStartOfDay()), projectId = 1, status = RoadAddressChangeType.Termination)

      nodesAndJunctionsService.expireObsoleteNodesAndJunctions(Seq(terminatedRoadLink), Some(terminatedRoadLink.endDate.get))

      // Test expired node and node points
      val nodePointsAfterExpiration = nodePointDAO.fetchByNodeNumbers(Seq(nodeNumber1, nodeNumber2, nodeNumber3))
      nodePointsAfterExpiration.length should be(2)
      nodePointsAfterExpiration.exists(node => node.roadwayNumber == roadLink1.roadwayNumber && node.beforeAfter == BeforeAfter.After  && roadLink1.addrMRange.startsAt(node.addrM)) should be(true)
      nodePointsAfterExpiration.exists(node => node.roadwayNumber == roadLink1.roadwayNumber && node.beforeAfter == BeforeAfter.Before && roadLink1.addrMRange.endsAt  (node.addrM)) should be(true)
      nodePointsAfterExpiration.exists(node => node.roadwayNumber == terminatedRoadLink.roadwayNumber) should be(false)
      nodePointsAfterExpiration.exists(node => node.roadwayNumber == terminatedRoadLink.roadwayNumber) should be(false)
    }
  }

  /**
    * Test case for New:
    * Create road number 1 part 1
    * * Assume road number 1 part 1 before new road
    *
    * Expected (the road was “extended”)
    * Node Points should be expired conditionally :
    * * If the remaining node points referenced by this nodeId are all present in the same road number, road part, track and administrative class
    * then all of those node points and the node itself should expire.
    */
  test("Test expireObsoleteNodesAndJunctions case When road is extended after the existing road") {
    runWithRollback {
      val roadGeom1 = Seq(Point(0.0, 0.0), Point(100.0, 0.0))

      val roadwayNumber = Sequences.nextRoadwayNumber
      val projectId = Sequences.nextViiteProjectId
      val roadLink = dummyProjectLink(RoadPart(1, 1), Track.Combined, Discontinuity.Continuous, AddrMRange(0, 100), AddrMRange(0, 100), Some(DateTime.now()), None, 12345.toString, 0, 100.0, SideCode.TowardsDigitizing, RoadAddressChangeType.New, projectId, AdministrativeClass.State, roadGeom1, roadwayNumber)

      val roadways = Seq(
        Roadway(NewIdValue, roadLink.roadwayNumber, roadLink.roadPart, roadLink.administrativeClass, roadLink.track, roadLink.discontinuity, roadLink.addrMRange, reversed = false, roadLink.startDate.get, roadLink.endDate, "user", None, 8L, TerminationCode.NoTermination, DateTime.now, None))
      val linearLocations = Seq(
        LinearLocation(NewIdValue, 1, roadLink.linkId, roadLink.addrMRange.start, roadLink.addrMRange.end, roadLink.sideCode, 0L, calibrationPoints = (CalibrationPointReference(Some(roadLink.addrMRange.start)), CalibrationPointReference(Some(roadLink.addrMRange.end))), roadGeom1, LinkGeomSource.NormalLinkInterface, roadLink.roadwayNumber, Some(DateTime.now), None))

      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(any[BoundingRectangle], any[Seq[(Int, Int)]])).thenReturn(linearLocations)
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(any[Set[Long]], any[Boolean])).thenReturn(roadways)

      val projectLinks = Seq(roadLink)

      val project = Project(projectId, ProjectState.Incomplete, "f", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
        "", Seq(), Seq(), None, None)

      buildTestDataForProject(Some(project), None, Some(linearLocations), Some(projectLinks))

      roadwayDAO.create(roadways)
      // Creation of nodes and node points
      val projectChanges = List(
        //combined
        ProjectRoadwayChange(0, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(RoadAddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, Some(AdministrativeClass.State), Some(Discontinuity.Continuous), Some(8L)),
            RoadwayChangeSection(Some(roadLink.roadPart.roadNumber), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(roadLink.roadPart.partNumber), endRoadPartNumber = Some(roadLink.roadPart.partNumber), addrMRange = Some(AddrMRange(0L,100L)), Some(AdministrativeClass.State), Some(Discontinuity.Continuous), Some(8L)),
            Discontinuity.Continuous, AdministrativeClass.State, reversed = false, 1, 8)
          , DateTime.now)
      )
      val mappedRoadwayNumbers = projectLinkDAO.fetchProjectLinksChange(projectId)
      nodesAndJunctionsService.handleNodePoints(projectChanges, projectLinks, mappedRoadwayNumbers)

      val node1 = Node(Sequences.nextNodeId, Sequences.nextNodeNumber, roadLink.geometry.head, None, NodeType.EndOfRoad, DateTime.now(), None, DateTime.now(), None, "user", Some(DateTime.now()), registrationDate = new DateTime())
      val node2 = Node(Sequences.nextNodeId, Sequences.nextNodeNumber, roadLink.geometry.last, None, NodeType.EndOfRoad, DateTime.now(), None, DateTime.now(), None, "user", Some(DateTime.now()), registrationDate = new DateTime())

      nodeDAO.create(Seq(node1, node2))

      val nodePointTemplates = projectLinks.flatMap(pl => nodePointDAO.fetchTemplatesByRoadwayNumber(pl.roadwayNumber))
      nodePointTemplates.length should be(2)

      nodePointTemplates.foreach(
        np => {
          if (np.addrM == roadLink.addrMRange.start)
            runUpdateToDb(sql"""UPDATE NODE_POINT SET NODE_NUMBER = ${node1.nodeNumber} WHERE ID = ${np.id}""")
          else if (np.addrM == roadLink.addrMRange.end)
            runUpdateToDb(sql"""UPDATE NODE_POINT SET NODE_NUMBER = ${node2.nodeNumber} WHERE ID = ${np.id}""")
        }
      )

      val nodePoints = nodePointDAO.fetchByNodeNumbers(Seq(node1.nodeNumber, node2.nodeNumber))
      nodePoints.length should be(2)
      nodePoints.exists(node => node.roadwayNumber == roadLink.roadwayNumber && node.beforeAfter == BeforeAfter.After  && roadLink.addrMRange.startsAt(node.addrM)) should be(true)
      nodePoints.exists(node => node.roadwayNumber == roadLink.roadwayNumber && node.beforeAfter == BeforeAfter.Before && roadLink.addrMRange.endsAt  (node.addrM)) should be(true)

      val roadGeom2 = Seq(Point(100.0, 0.0), Point(250.0, 0.0))
      val unchangedRoadLink = roadLink.copy(status = RoadAddressChangeType.Unchanged)
      val newRoadLink = dummyProjectLink(RoadPart(1, 1), Track.Combined, Discontinuity.Continuous, AddrMRange(100, 250), AddrMRange(100, 250), Some(DateTime.now()), None, 12346.toString, 0, 150.0, SideCode.TowardsDigitizing, RoadAddressChangeType.New, 0, AdministrativeClass.State, roadGeom2, roadwayNumber + 1)

      val newRoadways = Seq(
        Roadway(NewIdValue,    roadLink.roadwayNumber,    roadLink.roadPart,    roadLink.administrativeClass,    roadLink.track,    roadLink.discontinuity,    roadLink.addrMRange, reversed = false,    roadLink.startDate.get,    roadLink.endDate, "user", None, 8L, TerminationCode.NoTermination, DateTime.now, None),
        Roadway(NewIdValue, newRoadLink.roadwayNumber, newRoadLink.roadPart, newRoadLink.administrativeClass, newRoadLink.track, newRoadLink.discontinuity, newRoadLink.addrMRange, reversed = false, newRoadLink.startDate.get, newRoadLink.endDate, "user", None, 8L, TerminationCode.NoTermination, DateTime.now, None))
      val newLinearLocations = Seq(
        LinearLocation(NewIdValue, 1, roadLink.linkId, roadLink.addrMRange.start, roadLink.addrMRange.end, roadLink.sideCode, 0L, calibrationPoints = (CalibrationPointReference(Some(roadLink.addrMRange.start)), CalibrationPointReference(Some(roadLink.addrMRange.end))), roadGeom1, LinkGeomSource.NormalLinkInterface, roadLink.roadwayNumber, Some(DateTime.now), None),
        LinearLocation(NewIdValue, 2, newRoadLink.linkId, newRoadLink.addrMRange.start, newRoadLink.addrMRange.end, newRoadLink.sideCode, 0L, calibrationPoints = (CalibrationPointReference(Some(newRoadLink.addrMRange.start)), CalibrationPointReference(Some(newRoadLink.addrMRange.end))), roadGeom2, LinkGeomSource.NormalLinkInterface, newRoadLink.roadwayNumber, Some(DateTime.now), None))

      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(any[BoundingRectangle], any[Seq[(Int, Int)]])).thenReturn(newLinearLocations)
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(any[Set[Long]], any[Boolean])).thenReturn(newRoadways)

      nodesAndJunctionsService.expireObsoleteNodesAndJunctions(Seq(unchangedRoadLink, newRoadLink), Some(DateTime.now().minusDays(1)))

      // Test expired node and node points
      val nodePointsAfterExpiration = nodePointDAO.fetchByNodeNumbers(Seq(node1.nodeNumber, node2.nodeNumber))
      nodePointsAfterExpiration.length should be(1)
      nodePointsAfterExpiration.exists(node => node.roadwayNumber == roadLink.roadwayNumber && node.beforeAfter == BeforeAfter.After  && roadLink.addrMRange.startsAt(node.addrM)) should be(true)
      nodePointsAfterExpiration.exists(node => node.roadwayNumber == roadLink.roadwayNumber && node.beforeAfter == BeforeAfter.Before && roadLink.addrMRange.endsAt  (node.addrM)) should be(false)
    }
  }

  /**
    * Test case for New:
    * Create road number 1 part 1
    * * Assume road number 1 part 1 after new road
    *
    * Expected (the road was “extended”)
    * Node Points should be expired conditionally :
    * * If the remaining node points referenced by this nodeId are all present in the same road number, road part, track and administrative class
    * then all of those node points and the node itself should expire.
    */
  test("Test expireObsoleteNodesAndJunctions case When road is extended before the existing road") {
    runWithRollback {
      val roadGeom1 = Seq(Point(100.0, 0.0), Point(250.0, 0.0))

      val roadwayNumber = Sequences.nextRoadwayNumber
      val projectId = Sequences.nextViiteProjectId

      val roadLink = dummyProjectLink(RoadPart(1, 1), Track.Combined, Discontinuity.Continuous, AddrMRange(100, 250), AddrMRange(100, 250), Some(DateTime.now()), None, 12345.toString, 100, 250.0, SideCode.TowardsDigitizing, RoadAddressChangeType.New, projectId, AdministrativeClass.State, roadGeom1, roadwayNumber)

      val project = Project(projectId, ProjectState.Incomplete, "f", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
        "", Seq(), Seq(), None, None)


      val roadways = Seq(
        Roadway(NewIdValue, roadLink.roadwayNumber, roadLink.roadPart, roadLink.administrativeClass, roadLink.track, roadLink.discontinuity, roadLink.addrMRange, reversed = false, roadLink.startDate.get, roadLink.endDate, "user", None, 8L, TerminationCode.NoTermination, DateTime.now, None))
      val linearLocations = Seq(
        LinearLocation(NewIdValue, 1, roadLink.linkId, roadLink.addrMRange.start, roadLink.addrMRange.end, roadLink.sideCode, 0L, calibrationPoints = (CalibrationPointReference(Some(roadLink.addrMRange.start)), CalibrationPointReference(Some(roadLink.addrMRange.end))), roadGeom1, LinkGeomSource.NormalLinkInterface, roadLink.roadwayNumber, Some(DateTime.now), None))

      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(any[BoundingRectangle], any[Seq[(Int, Int)]])).thenReturn(linearLocations)
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(any[Set[Long]], any[Boolean])).thenReturn(roadways)

      val projectLinks = Seq(roadLink)
      buildTestDataForProject(Some(project), None, Some(linearLocations), Some(projectLinks))

      roadwayDAO.create(roadways)
      val projectChanges = List(
        //combined
        ProjectRoadwayChange(0, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(RoadAddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, Some(AdministrativeClass.State), Some(Discontinuity.Continuous), Some(8L)),
            RoadwayChangeSection(Some(roadLink.roadPart.roadNumber), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(roadLink.roadPart.partNumber), endRoadPartNumber = Some(roadLink.roadPart.partNumber), addrMRange = Some(roadLink.addrMRange), Some(AdministrativeClass.State), Some(Discontinuity.Continuous), Some(8L)),
            Discontinuity.Continuous, AdministrativeClass.State, reversed = false, 1, 8)
          , DateTime.now)
      )
      val mappedRoadwayNumbers = projectLinkDAO.fetchProjectLinksChange(projectId)
      // Creation of nodes and node points
      nodesAndJunctionsService.handleNodePoints(projectChanges, projectLinks, mappedRoadwayNumbers)

      val node1 = Node(Sequences.nextNodeId, Sequences.nextNodeNumber, roadLink.geometry.head, None, NodeType.EndOfRoad, DateTime.now(), None, DateTime.now(), None, "user", Some(DateTime.now()), registrationDate = new DateTime())
      val node2 = Node(Sequences.nextNodeId, Sequences.nextNodeNumber, roadLink.geometry.last, None, NodeType.EndOfRoad, DateTime.now(), None, DateTime.now(), None, "user", Some(DateTime.now()), registrationDate = new DateTime())

      nodeDAO.create(Seq(node1, node2))

      val nodePointTemplates = projectLinks.flatMap(pl => nodePointDAO.fetchTemplatesByRoadwayNumber(pl.roadwayNumber))
      nodePointTemplates.length should be(2)

      nodePointTemplates.foreach(
        np => {
          if (np.addrM == roadLink.addrMRange.start)
            runUpdateToDb(sql"""UPDATE NODE_POINT SET NODE_NUMBER = ${node1.nodeNumber} WHERE ID = ${np.id}""")
          else if (np.addrM == roadLink.addrMRange.end)
            runUpdateToDb(sql"""UPDATE NODE_POINT SET NODE_NUMBER = ${node2.nodeNumber} WHERE ID = ${np.id}""")
        }
      )

      val nodePoints = nodePointDAO.fetchByNodeNumbers(Seq(node1.nodeNumber, node2.nodeNumber))
      nodePoints.length should be(2)
      nodePoints.exists(node => node.roadwayNumber == roadLink.roadwayNumber && node.beforeAfter == BeforeAfter.After  && roadLink.addrMRange.startsAt(node.addrM)) should be(true)
      nodePoints.exists(node => node.roadwayNumber == roadLink.roadwayNumber && node.beforeAfter == BeforeAfter.Before && roadLink.addrMRange.endsAt  (node.addrM)) should be(true)

      val roadGeom2 = Seq(Point(0.0, 0.0), Point(100.0, 0.0))
      val newRoadLink = dummyProjectLink(RoadPart(1, 1), Track.Combined, Discontinuity.Continuous, AddrMRange(0, 100), AddrMRange(0, 100), Some(DateTime.now()), None, 12346.toString, 0, 100.0, SideCode.TowardsDigitizing, RoadAddressChangeType.New, 0, AdministrativeClass.State, roadGeom2, roadwayNumber + 1)

      val newRoadways = Seq(
        Roadway(NewIdValue,    roadLink.roadwayNumber,    roadLink.roadPart,    roadLink.administrativeClass,    roadLink.track,    roadLink.discontinuity,    roadLink.addrMRange, reversed = false,    roadLink.startDate.get,    roadLink.endDate, "user", None, 8L, TerminationCode.NoTermination, DateTime.now, None),
        Roadway(NewIdValue, newRoadLink.roadwayNumber, newRoadLink.roadPart, newRoadLink.administrativeClass, newRoadLink.track, newRoadLink.discontinuity, newRoadLink.addrMRange, reversed = false, newRoadLink.startDate.get, newRoadLink.endDate, "user", None, 8L, TerminationCode.NoTermination, DateTime.now, None))
      val newLinearLocations = Seq(
        LinearLocation(NewIdValue, 1, roadLink.linkId, roadLink.addrMRange.start, roadLink.addrMRange.end, roadLink.sideCode, 0L, calibrationPoints = (CalibrationPointReference(Some(roadLink.addrMRange.start)), CalibrationPointReference(Some(roadLink.addrMRange.end))), roadGeom1, LinkGeomSource.NormalLinkInterface, roadLink.roadwayNumber, Some(DateTime.now), None),
        LinearLocation(NewIdValue, 2, newRoadLink.linkId, newRoadLink.addrMRange.start, newRoadLink.addrMRange.end, newRoadLink.sideCode, 0L, calibrationPoints = (CalibrationPointReference(Some(newRoadLink.addrMRange.start)), CalibrationPointReference(Some(newRoadLink.addrMRange.end))), roadGeom2, LinkGeomSource.NormalLinkInterface, newRoadLink.roadwayNumber, Some(DateTime.now), None))

      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(any[BoundingRectangle], any[Seq[(Int, Int)]])).thenReturn(newLinearLocations)
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(any[Set[Long]], any[Boolean])).thenReturn(newRoadways)

      val projectChangesAfterChanges = List(
        //combined
        ProjectRoadwayChange(0, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(RoadAddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, Some(AdministrativeClass.State), Some(Discontinuity.Continuous), Some(8L)),
            RoadwayChangeSection(Some(newRoadLink.roadPart.roadNumber), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(newRoadLink.roadPart.partNumber), endRoadPartNumber = Some(newRoadLink.roadPart.partNumber), addrMRange = Some(newRoadLink.addrMRange), Some(AdministrativeClass.State), Some(Discontinuity.Continuous), Some(8L)),
            Discontinuity.Continuous, AdministrativeClass.State, reversed = false, 1, 8)
          , DateTime.now),
        ProjectRoadwayChange(0, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(RoadAddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, Some(AdministrativeClass.State), Some(Discontinuity.Continuous), Some(8L)),
            RoadwayChangeSection(Some(roadLink.roadPart.roadNumber), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(roadLink.roadPart.partNumber), endRoadPartNumber = Some(roadLink.roadPart.partNumber), addrMRange = Some(roadLink.addrMRange), Some(AdministrativeClass.State), Some(Discontinuity.Continuous), Some(8L)),
            Discontinuity.Continuous, AdministrativeClass.State, reversed = false, 1, 8)
          , DateTime.now)
      )
      val mappedRoadwayNumbers2 = projectLinkDAO.fetchProjectLinksChange(projectId)
      nodesAndJunctionsService.handleNodePoints(projectChangesAfterChanges, Seq(roadLink, newRoadLink), mappedRoadwayNumbers2)
      nodesAndJunctionsService.expireObsoleteNodesAndJunctions(Seq(roadLink, newRoadLink), Some(DateTime.now().minusDays(1)))

      // Test expired node and node points
      val nodePointsAfterExpiration = nodePointDAO.fetchByNodeNumbers(Seq(node1.nodeNumber, node2.nodeNumber))
      nodePointsAfterExpiration.length should be(1)
      nodePointsAfterExpiration.exists(node => node.roadwayNumber == roadLink.roadwayNumber && node.beforeAfter == BeforeAfter.After &&  roadLink.addrMRange.startsAt(node.addrM)) should be(false)
      nodePointsAfterExpiration.exists(node => node.roadwayNumber == roadLink.roadwayNumber && node.beforeAfter == BeforeAfter.Before && roadLink.addrMRange.endsAt  (node.addrM)) should be(true)
    }
  }

  /**
    * Test case for Termination:
    * Reserve road number 2 part 1
    * * Assume that road number 1 road part 1, is just before it.
    * Terminate road number 2 part 1
    *
    * Expected:
    * Junction Point at the start of road 2 should be expire.
    * Junction at the start of road 2 should expire conditionally:
    * * If there are no more junction points referenced by this junctionId, then expire the both junction and the junction point at end of road number 1.
    */
  test("Test expireObsoleteNodesAndJunctions case When road is terminated Then also junction points should be expired") {
    runWithRollback {
      val roadGeom1 = Seq(Point(0.0, 0.0), Point(100.0, 0.0))
      val roadGeom2 = Seq(Point(100.0, 0.0), Point(250.0, 0.0))

      val roadwayNumber1 = Sequences.nextRoadwayNumber
      val roadwayNumber2 = Sequences.nextRoadwayNumber
      val projectId = Sequences.nextViiteProjectId
      val plId1 = projectId + 1
      val plId2 = projectId + 2
      val road1Link = dummyProjectLink(RoadPart(1, 1), Track.Combined, Discontinuity.Continuous, AddrMRange(0, 100), AddrMRange(0, 100), Some(DateTime.now()), None, 12345.toString, 0, 100.0, SideCode.TowardsDigitizing, RoadAddressChangeType.New, projectId, AdministrativeClass.State, roadGeom1, roadwayNumber1).copy(id = plId1)
      val road2Link = dummyProjectLink(RoadPart(2, 1), Track.Combined, Discontinuity.Continuous, AddrMRange(0, 150), AddrMRange(0, 150), Some(DateTime.now()), None, 12346.toString, 0, 150.0, SideCode.TowardsDigitizing, RoadAddressChangeType.New, projectId, AdministrativeClass.State, roadGeom2, roadwayNumber2).copy(id = plId2)

      val project = Project(projectId, ProjectState.Incomplete, "f", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
        "", Seq(), Seq(), None, None)

      val projectLinks = Seq(road1Link, road2Link)

      val roadways = Seq(
        Roadway(NewIdValue, road1Link.roadwayNumber, road1Link.roadPart, road1Link.administrativeClass, road1Link.track, road1Link.discontinuity, road1Link.addrMRange, reversed = false, road1Link.startDate.get, road1Link.endDate, "user", None, 8L, TerminationCode.NoTermination, DateTime.now, None),
        Roadway(NewIdValue, road2Link.roadwayNumber, road2Link.roadPart, road2Link.administrativeClass, road2Link.track, road2Link.discontinuity, road2Link.addrMRange, reversed = false, road2Link.startDate.get, road2Link.endDate, "user", None, 8L, TerminationCode.NoTermination, DateTime.now, None))
      val linearLocations = Seq(
        LinearLocation(NewIdValue, 1, road1Link.linkId, road1Link.addrMRange.start, road1Link.addrMRange.end, road1Link.sideCode, 0L, calibrationPoints = (CalibrationPointReference(Some(road1Link.addrMRange.start)), CalibrationPointReference(Some(road1Link.addrMRange.end))), roadGeom1, LinkGeomSource.NormalLinkInterface, road1Link.roadwayNumber, Some(DateTime.now), None),
        LinearLocation(NewIdValue, 1, road2Link.linkId, road2Link.addrMRange.start, road2Link.addrMRange.end, road2Link.sideCode, 0L, calibrationPoints = (CalibrationPointReference(Some(road2Link.addrMRange.start)), CalibrationPointReference(Some(road2Link.addrMRange.end))), roadGeom2, LinkGeomSource.NormalLinkInterface, road2Link.roadwayNumber, Some(DateTime.now), None))

      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(any[BoundingRectangle], any[Seq[(Int, Int)]])).thenReturn(linearLocations)
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(any[Set[Long]], any[Boolean])).thenReturn(roadways)

      roadwayDAO.create(roadways)
      buildTestDataForProject(Some(project), None, Some(linearLocations), Some(projectLinks))
      val projectChanges = List(
        //combined
        ProjectRoadwayChange(0, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(RoadAddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, Some(AdministrativeClass.State), Some(Discontinuity.Continuous), Some(8L)),
            RoadwayChangeSection(Some(road1Link.roadPart.roadNumber), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(road1Link.roadPart.partNumber), endRoadPartNumber = Some(road1Link.roadPart.partNumber), addrMRange = Some(road1Link.addrMRange), Some(AdministrativeClass.State), Some(Discontinuity.Continuous), Some(8L)),
            Discontinuity.Continuous, AdministrativeClass.State, reversed = false, 1, 8)
          , DateTime.now),
        ProjectRoadwayChange(0, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(RoadAddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, Some(AdministrativeClass.State), Some(Discontinuity.Continuous), Some(8L)),
            RoadwayChangeSection(Some(road2Link.roadPart.roadNumber), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(road2Link.roadPart.partNumber), endRoadPartNumber = Some(road2Link.roadPart.partNumber), addrMRange = Some(road2Link.addrMRange), Some(AdministrativeClass.State), Some(Discontinuity.Continuous), Some(8L)),
            Discontinuity.Continuous, AdministrativeClass.State, reversed = false, 1, 8)
          , DateTime.now)
      )
      val mappedRoadwayNumbers = projectLinkDAO.fetchProjectLinksChange(projectId)
      // Creation of junction points template
      nodesAndJunctionsService.handleJunctionAndJunctionPoints(projectChanges, projectLinks, mappedRoadwayNumbers)
      val junctionPointTemplates = junctionPointDAO.fetchByRoadwayPointIds(
        roadwayPointDAO.fetchByRoadwayNumbers(projectLinks.map(_.roadwayNumber)).map(_.id))
      junctionPointTemplates.length should be(2)

      val junctions = junctionDAO.fetchByIds(junctionPointTemplates.map(_.junctionId))
      junctions.length should be(1)

      val junctionsBeforeExpire = junctionDAO.fetchTemplatesByRoadwayNumbers(Seq(roadwayNumber1, roadwayNumber2))
      junctionsBeforeExpire.length should be(1)

      val terminatedJunctionsBeforeExpire = junctionDAO.fetchExpiredByRoadwayNumbers(Seq(roadwayNumber1, roadwayNumber2))
      terminatedJunctionsBeforeExpire count (_.endDate.isDefined) should be(0)
      terminatedJunctionsBeforeExpire count (_.validTo.isDefined) should be(0)

      val terminatedRoadLink = road2Link.copy(endDate = Some(DateTime.now()), projectId = 1, status = RoadAddressChangeType.Termination)

      nodesAndJunctionsService.expireObsoleteNodesAndJunctions(Seq(terminatedRoadLink), Some(terminatedRoadLink.endDate.get))

      // Check that junctions for roadways were expired
      val junctionsAfterExpire = junctionDAO.fetchTemplatesByRoadwayNumbers(Seq(roadwayNumber1, roadwayNumber2))
      junctionsAfterExpire.length should be(0)

      // Check that terminated junction was created
      val terminatedJunctionsAfterExpire = junctionDAO.fetchExpiredByRoadwayNumbers(Seq(roadwayNumber1, roadwayNumber2))
      terminatedJunctionsAfterExpire.length should be(2)
      terminatedJunctionsAfterExpire count (_.endDate.isDefined) should be(1)
      terminatedJunctionsAfterExpire count (_.validTo.isDefined) should be(1)

      // Check that original junction was expired
      val originalJunctions = junctionDAO.fetchByIds(junctionPointTemplates.map(_.junctionId))
      originalJunctions.length should be(0)

      // Check that junction points for the original junction were expired
      val originalJunctionPoints = junctionPointDAO.fetchByJunctionIds(junctionPointTemplates.map(_.junctionId))
      originalJunctionPoints.length should be(0)
    }
  }

  /**
    * Test case for Termination:
    * Reserve road number 2 part 1
    * * Assume road number 1 part 1
    * * The road number 2 is connected to the middle of two links of the road number 1
    * Terminate road number 2 part 1
    *
    * Expected:
    * Junction Point in the road 2 at the intersection with the road 1 should be expire.
    * Junction in the intersection should be expire conditionally:
    * * If the remaining junction points referenced by this junctionId are all present in the same road number, then all of those junction points and the junction itself should expire.
    */
  test("Test expireObsoleteNodesAndJunctions case When road is terminated Then also junction and junction points should be expired if they are in the same road") {
    runWithRollback {
      val roadGeom1Link1 = Seq(Point(0.0, 5.0), Point(5.0, 5.0))
      val roadGeom1Link2 = Seq(Point(5.0, 5.0), Point(5.0, 10.0))
      val roadGeom2 = Seq(Point(5.0, 0.0), Point(5.0, 5.0))

      val roadwayNumber = Sequences.nextRoadwayNumber
      val projectId = Sequences.nextViiteProjectId
      val plId1 = projectId + 1
      val plId2 = projectId + 2
      val plId3 = projectId + 3
      val project = Project(projectId, ProjectState.Incomplete, "f", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
        "", Seq(), Seq(), None, None)
      val road1Link1 = dummyProjectLink(RoadPart(1, 1), Track.Combined, Discontinuity.Continuous, AddrMRange(0,  5), AddrMRange(0,  5), Some(DateTime.now()), None, 12345.toString,   0,  5.0, SideCode.TowardsDigitizing, RoadAddressChangeType.New, projectId, AdministrativeClass.State, roadGeom1Link1, roadwayNumber    ).copy(id = plId1)
      val road1Link2 = dummyProjectLink(RoadPart(1, 1), Track.Combined, Discontinuity.Continuous, AddrMRange(5, 10), AddrMRange(5, 10), Some(DateTime.now()), None, 12346.toString, 5.0, 10.0, SideCode.TowardsDigitizing, RoadAddressChangeType.New, projectId, AdministrativeClass.State, roadGeom1Link2, roadwayNumber    ).copy(id = plId2)
      val road2Link  = dummyProjectLink(RoadPart(2, 1), Track.Combined, Discontinuity.Continuous, AddrMRange(0,  5), AddrMRange(0,  5), Some(DateTime.now()), None, 12347.toString,   0,  5.0, SideCode.TowardsDigitizing, RoadAddressChangeType.New, projectId, AdministrativeClass.State, roadGeom2,      roadwayNumber + 1).copy(id = plId3)

      val roadways = Seq(
        Roadway(NewIdValue, road1Link1.roadwayNumber, road1Link1.roadPart, road1Link1.administrativeClass, road1Link1.track, road1Link1.discontinuity, AddrMRange(road1Link1.addrMRange.start, road1Link2.addrMRange.end), reversed = false, road1Link1.startDate.get, road1Link1.endDate, "user", None, 8L, TerminationCode.NoTermination, DateTime.now, None),
        Roadway(NewIdValue,  road2Link.roadwayNumber,  road2Link.roadPart, road2Link.administrativeClass,  road2Link.track,  road2Link.discontinuity,  road2Link.addrMRange, reversed = false, road2Link.startDate.get, road2Link.endDate, "user", None, 8L, TerminationCode.NoTermination, DateTime.now, None))
      val linearLocations = Seq(
        LinearLocation(NewIdValue, 1, road1Link1.linkId, road1Link1.addrMRange.start, road1Link1.addrMRange.end, road1Link1.sideCode, 0L, calibrationPoints = (CalibrationPointReference(Some(road1Link1.addrMRange.start)), CalibrationPointReference(Some(road1Link1.addrMRange.end))), roadGeom1Link1, LinkGeomSource.NormalLinkInterface, road1Link1.roadwayNumber, Some(DateTime.now), None),
        LinearLocation(NewIdValue, 2, road1Link2.linkId, road1Link2.addrMRange.start, road1Link2.addrMRange.end, road1Link2.sideCode, 0L, calibrationPoints = (CalibrationPointReference(Some(road1Link2.addrMRange.start)), CalibrationPointReference(Some(road1Link2.addrMRange.end))), roadGeom1Link2, LinkGeomSource.NormalLinkInterface, road1Link2.roadwayNumber, Some(DateTime.now), None),
        LinearLocation(NewIdValue, 1, road2Link.linkId, road2Link.addrMRange.start, road2Link.addrMRange.end, road2Link.sideCode, 0L, calibrationPoints = (CalibrationPointReference(Some(road2Link.addrMRange.start)), CalibrationPointReference(Some(road2Link.addrMRange.end))), roadGeom2, LinkGeomSource.NormalLinkInterface, road2Link.roadwayNumber, Some(DateTime.now), None))

      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(any[BoundingRectangle], any[Seq[(Int, Int)]])).thenReturn(linearLocations)
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(any[Set[Long]], any[Boolean])).thenReturn(roadways)

      val projectLinks = Seq(road1Link1, road1Link2, road2Link)

      roadwayDAO.create(roadways)
      buildTestDataForProject(Some(project), None, Some(linearLocations), Some(projectLinks))
      val projectChanges = List(
        //combined
        ProjectRoadwayChange(0, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(RoadAddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, Some(AdministrativeClass.State), Some(Discontinuity.Continuous), Some(8L)),
            RoadwayChangeSection(Some(road1Link1.roadPart.roadNumber), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(road1Link1.roadPart.partNumber), endRoadPartNumber = Some(road1Link1.roadPart.partNumber), addrMRange = Some(road1Link1.addrMRange), Some(AdministrativeClass.State), Some(Discontinuity.Continuous), Some(8L)),
            Discontinuity.Continuous, AdministrativeClass.State, reversed = false, 1, 8)
          , DateTime.now),
        ProjectRoadwayChange(0, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(RoadAddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, Some(AdministrativeClass.State), Some(Discontinuity.Continuous), Some(8L)),
            RoadwayChangeSection(Some(road2Link.roadPart.roadNumber), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(road2Link.roadPart.partNumber), endRoadPartNumber = Some(road2Link.roadPart.partNumber), addrMRange = Some(road2Link.addrMRange), Some(AdministrativeClass.State), Some(Discontinuity.Continuous), Some(8L)),
            Discontinuity.Continuous, AdministrativeClass.State, reversed = false, 1, 8)
          , DateTime.now)
      )
      val mappedRoadwayNumbers = projectLinkDAO.fetchProjectLinksChange(projectId)
      // Creation of junction points template
      nodesAndJunctionsService.handleJunctionAndJunctionPoints(projectChanges, projectLinks, mappedRoadwayNumbers)
      val junctionPointTemplates = junctionPointDAO.fetchByRoadwayPointIds(
        roadwayPointDAO.fetchByRoadwayNumbers(projectLinks.map(_.roadwayNumber)).map(_.id))
      junctionPointTemplates.length should be(3)

      val junctions = junctionDAO.fetchByIds(junctionPointTemplates.map(_.junctionId))
      junctions.length should be(1)

      val terminatedRoadLink = road2Link.copy(endDate = Some(DateTime.now().minusDays(1)), projectId = 1, status = RoadAddressChangeType.Termination)

      nodesAndJunctionsService.expireObsoleteNodesAndJunctions(Seq(terminatedRoadLink), Some(terminatedRoadLink.endDate.get))

      // Old junction should be expired
      junctionDAO.fetchByIds(junctionPointTemplates.map(_.junctionId)).length should be(0)

      // New junction should be created with end date
      val terminatedJunctions = junctionDAO.fetchExpiredByRoadwayNumbers(Seq(terminatedRoadLink.roadwayNumber))
      terminatedJunctions.length should be(2)
      terminatedJunctions.count(_.endDate.isDefined) should be(1)
      terminatedJunctions.count(_.validTo.isDefined) should be(1)

      // Old junction points should be expired
      junctionPointDAO.fetchByJunctionIds(junctionPointTemplates.map(_.junctionId)).length should be(0)

      // New junction points should be created for terminated junction
      val terminatedJunctionPoints = junctionPointDAO.fetchAllByJunctionIds(terminatedJunctions.map(_.id))
      terminatedJunctionPoints.length should be(6)
      terminatedJunctionPoints.count(_.validTo.isDefined) should be (6)
      terminatedJunctionPoints.count(_.endDate.isDefined) should be (3)

      junctionPointDAO.fetchByJunctionIds(terminatedJunctions.map(_.id)).length should be(0)
    }
  }

  test("Test expireObsoleteNodesAndJunctions When there is complex changes in the project Then Junction and its Junction points should be properly expired") {
    runWithRollback {
      /*
                  ^
                  |
                  | C2 R1
            C3 R2 |
          <-------0
                  ^
                  | C1 R1
                  |

          Note:
            0: Junction
        */

      val road999 = 999L
      val road1000 = 1000L
      val part1 = 1L
      val projectId = Sequences.nextViiteProjectId
      val rwId = Sequences.nextRoadwayId
      val llId = Sequences.nextLinearLocationId
      val rwNumber = Sequences.nextRoadwayNumber
      val plId = Sequences.nextProjectLinkId

      val combGeom1 = Seq(Point(10.0, 0.0), Point(10.0, 10.0))
      val combGeom2 = Seq(Point(10.0, 10.0), Point(10.0, 20.0))
      val combGeom3 = Seq(Point(10.0, 10.0), Point(1.0, 10.0))

      val combLink1 = dummyProjectLink(RoadPart(road999,  part1), Track.Combined, Discontinuity.Continuous,    AddrMRange( 0, 10), AddrMRange( 0, 10), Some(DateTime.now()), None, 12345.toString, 0, 10, SideCode.TowardsDigitizing, RoadAddressChangeType.New, projectId, AdministrativeClass.State, combGeom1, rwNumber    ).copy(id = plId,     projectId = projectId, roadwayId = rwId,     linearLocationId = llId)
      val combLink2 = dummyProjectLink(RoadPart(road999,  part1), Track.Combined, Discontinuity.Discontinuous, AddrMRange(10, 20), AddrMRange(10, 20), Some(DateTime.now()), None, 12346.toString, 0, 10, SideCode.TowardsDigitizing, RoadAddressChangeType.New, projectId, AdministrativeClass.State, combGeom2, rwNumber    ).copy(id = plId + 1, projectId = projectId, roadwayId = rwId,     linearLocationId = llId + 1)
      val combLink3 = dummyProjectLink(RoadPart(road1000, part1), Track.Combined, Discontinuity.Discontinuous, AddrMRange( 0,  9), AddrMRange( 0,  9), Some(DateTime.now()), None, 12347.toString, 0, 10, SideCode.TowardsDigitizing, RoadAddressChangeType.New, projectId, AdministrativeClass.State, combGeom3, rwNumber + 1).copy(id = plId + 2, projectId = projectId, roadwayId = rwId + 1, linearLocationId = llId + 2)

      val project = Project(projectId, ProjectState.Incomplete, "f", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
        "", Seq(), Seq(), None, None)

      val combPLinks = Seq(combLink1, combLink2, combLink3)

      val (lc1,   rw1  ): (LinearLocation, Roadway) = Seq(combLink1).map(toRoadwayAndLinearLocation).head
      val (lc2,_/*rw2*/): (LinearLocation, Roadway) = Seq(combLink2).map(toRoadwayAndLinearLocation).head
      val (lc3,   rw3  ): (LinearLocation, Roadway) = Seq(combLink3).map(toRoadwayAndLinearLocation).head
      val rw1WithId = rw1.copy(addrMRange = AddrMRange(0,20), ely = 8L)
      val rw2WithId = rw3.copy(addrMRange = AddrMRange(0, 9), ely = 8L)
      val orderedlll1 = lc1.copy(orderNumber = 1)
      val orderedlll2 = lc2.copy(orderNumber = 2)
      val orderedlll3 = lc3.copy(orderNumber = 1)

      buildTestDataForProject(Some(project), Some(Seq(rw1WithId, rw2WithId)), Some(Seq(orderedlll1, orderedlll2, orderedlll3)), Some(Seq(combLink1, combLink2, combLink3)))

      val projectChanges = List(
        //  Combined
        ProjectRoadwayChange(projectId, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(RoadAddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, Some(AdministrativeClass.State), Some(Discontinuity.Continuous), Some(8L)),
            RoadwayChangeSection(Some(road999), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(part1), endRoadPartNumber = Some(part1), addrMRange = Some(AddrMRange(0L,20L)), Some(AdministrativeClass.State), Some(Discontinuity.Discontinuous), Some(8L)),
            Discontinuity.Discontinuous, AdministrativeClass.State, reversed = false, 1, 8)
          , DateTime.now),
        ProjectRoadwayChange(projectId, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(RoadAddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, Some(AdministrativeClass.State), Some(Discontinuity.Continuous), Some(8L)),
            RoadwayChangeSection(Some(road1000), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(part1), endRoadPartNumber = Some(part1), addrMRange = Some(AddrMRange(0L,9L)), Some(AdministrativeClass.State), Some(Discontinuity.Discontinuous), Some(8L)),
            Discontinuity.Discontinuous, AdministrativeClass.State, reversed = false, 2, 8)
          , DateTime.now)
      )

      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(any[BoundingRectangle], any[Seq[(Int, Int)]])).thenReturn(Seq(orderedlll1, orderedlll2, orderedlll3))
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(any[Set[Long]], any[Boolean])).thenReturn(Seq(rw1WithId, rw2WithId))

      val mappedReservedRoadwayNumbers = projectLinkDAO.fetchProjectLinksChange(projectId)
      roadAddressService.handleRoadwayPointsUpdate(projectChanges, mappedReservedRoadwayNumbers)
      nodesAndJunctionsService.handleNodePoints(projectChanges, combPLinks, mappedReservedRoadwayNumbers)
      nodesAndJunctionsService.handleJunctionAndJunctionPoints(projectChanges, combPLinks, mappedReservedRoadwayNumbers)
      nodesAndJunctionsService.expireObsoleteNodesAndJunctions(combPLinks, Some(project.startDate.minusDays(1)))

      val roadwayPoints = roadwayPointDAO.fetchByRoadwayNumbers(combPLinks.map(_.roadwayNumber))
      val junctionPointTemplates = junctionPointDAO.fetchByRoadwayPointIds(roadwayPoints.map(_.id))

      junctionPointTemplates.length should be(3)
      junctionPointTemplates.count(_.beforeAfter == BeforeAfter.Before) should be(1)
      junctionPointTemplates.count(_.beforeAfter == BeforeAfter.After) should be(2)
      val templateRoadwayNumbers = junctionPointTemplates.map(_.roadwayNumber).distinct
      val junctions = junctionDAO.fetchTemplatesByRoadwayNumbers(templateRoadwayNumbers)
      junctions.size should be(1)

      /*
            C3 R1
          <-------X
                  |
                  | C1 R1
                  |
        */

      val project2 = Project(projectId + 1, ProjectState.Incomplete, "ProjectDeleteRoadOfRoadLinks", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
        "", Seq(), Seq(), None, None)
      val terminateAndTransferProjectChanges = List(
        ProjectRoadwayChange(projectId, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(RoadAddressChangeType.Termination,
            RoadwayChangeSection(Some(road999), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(part1), endRoadPartNumber = Some(part1), addrMRange = Some(AddrMRange(10L,20L)), Some(AdministrativeClass.State), Some(Discontinuity.Discontinuous), Some(8L)),
            RoadwayChangeSection(None, None, None, None, None, Some(AdministrativeClass.State), Some(Discontinuity.Continuous), Some(8L)),
            Discontinuity.Discontinuous, AdministrativeClass.State, reversed = false, 1, 8)
          , DateTime.now),
        ProjectRoadwayChange(projectId, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(RoadAddressChangeType.Transfer,
            RoadwayChangeSection(Some(road999), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(part1), endRoadPartNumber = Some(part1), addrMRange = Some(AddrMRange(0L,10L)), Some(AdministrativeClass.State), Some(Discontinuity.Continuous), Some(8L)),
            RoadwayChangeSection(Some(road999), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(part1), endRoadPartNumber = Some(part1), addrMRange = Some(AddrMRange(0L,10L)), Some(AdministrativeClass.State), Some(Discontinuity.Continuous), Some(8L)),
            Discontinuity.Continuous, AdministrativeClass.State, reversed = false, 2, 8)
          , DateTime.now),
        ProjectRoadwayChange(projectId, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(RoadAddressChangeType.Transfer,
            RoadwayChangeSection(Some(road1000), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(part1), endRoadPartNumber = Some(part1), addrMRange = Some(AddrMRange(0L,9L)), Some(AdministrativeClass.State), Some(Discontinuity.Discontinuous), Some(8L)),
            RoadwayChangeSection(Some(road999),  Some(Track.Combined.value.toLong), startRoadPartNumber = Some(part1), endRoadPartNumber = Some(part1), addrMRange = Some(AddrMRange(10L,19L)), Some(AdministrativeClass.State), Some(Discontinuity.Discontinuous), Some(8L)),
            Discontinuity.Discontinuous, AdministrativeClass.State, reversed = false, 3, 8)
          , DateTime.now)
      )

      linearLocationDAO.expireByRoadwayNumbers(Set(rw1WithId.roadwayNumber, rw2WithId.roadwayNumber))
      roadwayDAO.expireHistory(Set(rw1WithId.id, rw2WithId.id))

      val transferLinkRoad999        = dummyProjectLink(RoadPart(road999, part1), Track.Combined, Discontinuity.Continuous,    AddrMRange( 0, 10), AddrMRange( 0, 10), Some(DateTime.now()), None, 12345.toString, 0, 10, SideCode.TowardsDigitizing, RoadAddressChangeType.Transfer,    projectId + 1, AdministrativeClass.State, combGeom1, rwNumber + 3).copy(id = plId + 4, roadwayId = rwId + 3, linearLocationId = llId + 7)
      val terminatedLinkRoad999      = dummyProjectLink(RoadPart(road999, part1), Track.Combined, Discontinuity.Discontinuous, AddrMRange(10, 20), AddrMRange(10, 20), Some(DateTime.now()), None, 12346.toString, 0, 10, SideCode.TowardsDigitizing, RoadAddressChangeType.Termination, projectId + 1, AdministrativeClass.State, combGeom2, rwNumber + 2).copy(id = plId + 3, roadwayId = rwId + 2, linearLocationId = llId + 6)
      val transferLinkRoadExRoad1000 = dummyProjectLink(RoadPart(road999, part1), Track.Combined, Discontinuity.Discontinuous, AddrMRange(10, 19), AddrMRange( 0,  9), Some(DateTime.now()), None, 12347.toString, 0,  9, SideCode.TowardsDigitizing, RoadAddressChangeType.Transfer,    projectId + 1, AdministrativeClass.State, combGeom3, rwNumber + 1).copy(id = plId + 5, roadwayId = rwId + 4, linearLocationId = llId + 8)
      val changedProjectLinks = Seq(transferLinkRoad999, terminatedLinkRoad999, transferLinkRoadExRoad1000)

      val (lc4, rw4): (LinearLocation, Roadway) = Seq(terminatedLinkRoad999).map(toRoadwayAndLinearLocation).head
      val (lc5, rw5): (LinearLocation, Roadway) = Seq(transferLinkRoad999).map(toRoadwayAndLinearLocation).head
      val (lc6, rw6): (LinearLocation, Roadway) = Seq(transferLinkRoadExRoad1000).map(toRoadwayAndLinearLocation).head
      val rw4WithId = rw4.copy(id = terminatedLinkRoad999.roadwayId, endDate = Some(DateTime.now()), ely = 8L)
      val rw5WithId = rw5.copy(id = transferLinkRoad999.roadwayId, ely = 8L)
      val rw6WithId = rw6.copy(id = transferLinkRoadExRoad1000.roadwayId, ely = 8L)
      val orderedlll4 = lc4.copy(orderNumber = 2, validTo = Some(DateTime.now()))
      val orderedlll5 = lc5.copy(orderNumber = 1)
      val orderedlll6 = lc6.copy(orderNumber = 1)

      buildTestDataForProject(Some(project2),
        Some(Seq(rw4WithId, rw5WithId, rw6WithId)),
        Some(Seq(orderedlll4, orderedlll5, orderedlll6)), Some(changedProjectLinks))


      val mappedAfterRoadwayInserts = projectLinkDAO.fetchProjectLinksChange(projectId + 1)
      val projectRoadLinkChangesAfterInserts = mappedAfterRoadwayInserts.map { rlc =>
        val pl = changedProjectLinks.find(_.id == rlc.id)
        val oldPl = combPLinks.find(_.linkId == pl.get.linkId)
        rlc.copy(linearLocationId = pl.get.linearLocationId, originalRoadwayNumber = oldPl.get.roadwayNumber, originalRoadPart = RoadPart(oldPl.get.roadPart.roadNumber,rlc.originalRoadPart.partNumber))
      }

      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(any[BoundingRectangle], any[Seq[(Int, Int)]])).thenReturn(Seq(orderedlll5, orderedlll6))
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(any[Set[Long]], any[Boolean])).thenReturn(Seq(rw5WithId, rw6WithId))

      projectLinkDAO.moveProjectLinksToHistory(projectId + 1)

      roadAddressService.handleRoadwayPointsUpdate(terminateAndTransferProjectChanges, projectRoadLinkChangesAfterInserts)
      nodesAndJunctionsService.handleNodePoints(terminateAndTransferProjectChanges, changedProjectLinks, projectRoadLinkChangesAfterInserts)
      nodesAndJunctionsService.handleJunctionAndJunctionPoints(terminateAndTransferProjectChanges, changedProjectLinks, projectRoadLinkChangesAfterInserts)

      //  Ending expired data
      val terminatedJunctionsBeforeExpire = junctionDAO.fetchExpiredByRoadwayNumbers(templateRoadwayNumbers)
      terminatedJunctionsBeforeExpire count (_.endDate.isDefined) should be(0)
      terminatedJunctionsBeforeExpire count (_.validTo.isDefined) should be(0)

      nodesAndJunctionsService.expireObsoleteNodesAndJunctions(changedProjectLinks, Some(project2.startDate.minusDays(1)))

      val oldValidJunctionsPoints = junctionPointDAO.fetchByIds(junctionPointTemplates.map(_.id))
      oldValidJunctionsPoints.isEmpty should be (true)

      val oldValidJunction = junctionDAO.fetchByIds(junctions.map(_.id))
      oldValidJunction.isEmpty should be (true)

      val rwPoints = roadwayPointDAO.fetchByRoadwayNumbers(Seq(terminatedLinkRoad999, transferLinkRoad999, transferLinkRoadExRoad1000).map(_.roadwayNumber)).map(_.id)
      val junctionPointsAfterTerminating = junctionPointDAO.fetchByRoadwayPointIds(rwPoints)
      junctionPointsAfterTerminating.length should be(0)
      //  Check that junctions for roadways were expired
      val junctionTemplatesAfterExpire = junctionDAO.fetchTemplatesByRoadwayNumbers(templateRoadwayNumbers)
      junctionTemplatesAfterExpire.length should be(0)

      //  Check that terminated junction was created
      val terminatedJunctionsAfterExpire = junctionDAO.fetchExpiredByRoadwayNumbers(templateRoadwayNumbers)
      terminatedJunctionsAfterExpire.length should be(2)
      terminatedJunctionsAfterExpire count (_.endDate.isDefined) should be(1)
      terminatedJunctionsAfterExpire count (_.validTo.isDefined) should be(1)
    }
  }

  def dummyHorizontalRoad(projectId: Long, numberOfLinearLocations: Int, roadPart: RoadPart, rwNumber: Long, track: Track = Track.Combined, startAddrAt: Long = 0, administrativeClass: AdministrativeClass = AdministrativeClass.State, discontinuity: Discontinuity = Discontinuity.Continuous, firstPointAt: Point = Point(0.0, 0.0), orderNumber: Double = 1.0, linkId: Long = 0, plId: Long = 0, rw: (Long, Option[Roadway]) = (0, None), llId: Long = 0, size: Int = 10): (Seq[ProjectLink], Seq[LinearLocation], Roadway) = {
    val (rwId, roadway) = rw
    val projectLinks = for (i: Int <- 0 until numberOfLinearLocations) yield {
      val start = (i * size + startAddrAt)
      val addrMRange = AddrMRange(start, start + size)
      val startPoint = firstPointAt.x + (i * size)
      val endPoint = startPoint + size
      val geom = Seq(Point(startPoint, firstPointAt.y), Point(endPoint, firstPointAt.y))
      val projectLink = dummyProjectLink(roadPart, track, if (i == numberOfLinearLocations-1) discontinuity else Discontinuity.Continuous, addrMRange, addrMRange, Some(DateTime.now()), None, (linkId + i).toString, 0, size, SideCode.TowardsDigitizing, RoadAddressChangeType.New, projectId, administrativeClass, geom, rwNumber).copy(id = plId + i, roadwayId = rwId, linearLocationId = llId + i)
      projectLink
    }
    val (linearLocations, roadways) = projectLinks.map(toRoadwayAndLinearLocation).unzip
    val orderedLinearLocations: Seq[LinearLocation] = linearLocations.zipWithIndex.map { case (ll, i) => ll.copy(orderNumber = orderNumber + i) }

    (projectLinks, orderedLinearLocations, roadway.getOrElse(roadways.head).copy(addrMRange = AddrMRange(roadway.getOrElse(roadways.head).addrMRange.start, roadways.last.addrMRange.end)))
  }

  def dummyVerticalRoad(projectId: Long, numberOfLinearLocations: Int, roadPart: RoadPart, rwNumber: Long, track: Track = Track.Combined, startAddrAt: Long = 0, administrativeClass: AdministrativeClass = AdministrativeClass.State, discontinuity: Discontinuity = Discontinuity.Continuous, firstPointAt: Point = Point(0.0, 0.0), orderNumber: Double = 1.0, linkId: Long = 0, plId: Long = 0, rw: (Long, Option[Roadway]) = (0, None), llId: Long = 0, size: Int = 10): (Seq[ProjectLink], Seq[LinearLocation], Roadway) = {
    val (rwId, roadway) = rw
    val projectLinks = for (i: Int <- 0 until numberOfLinearLocations) yield {
      val start = i * size + startAddrAt
      val addrMRange = AddrMRange(start, start + size)
      val startPoint = firstPointAt.y + (i * size)
      val endPoint = startPoint + size
      val geom = Seq(Point(firstPointAt.x, startPoint), Point(firstPointAt.x, endPoint))
      val discontinuityCode = if ( i === (numberOfLinearLocations - 1) ) { discontinuity } else { Discontinuity.Continuous }
      dummyProjectLink(roadPart, track, discontinuityCode, addrMRange, addrMRange, Some(DateTime.now()), None, (linkId + i).toString, 0, size, SideCode.TowardsDigitizing, RoadAddressChangeType.New, projectId, administrativeClass, geom, rwNumber).copy(id = plId + i, roadwayId = rwId, linearLocationId = llId + i)
    }
    val (linearLocations, roadways) = projectLinks.map(toRoadwayAndLinearLocation).unzip
    val orderedLinearLocations: Seq[LinearLocation] = linearLocations.zipWithIndex.map { case (ll, i) => ll.copy(orderNumber = orderNumber + i) }

    (projectLinks, orderedLinearLocations, roadway.getOrElse(roadways.head).copy(discontinuity = discontinuity, addrMRange = AddrMRange(roadway.getOrElse(roadways.head).addrMRange.start, roadways.last.addrMRange.end)))
  }

  /**
    * Test case for node points when administrative class changes
    */
  test("Test handleNodePointTemplates and expireObsoleteNodesAndJunctions when administrative class changes") {
    runWithRollback {
      /*  |--RT-1-->|--RT-3-->|--RT-1--|  */

      val projectId: Long = Sequences.nextViiteProjectId
      val project = Project(projectId, ProjectState.Incomplete, "f", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
        "", Seq(), Seq(), None, None)

      val (pl1, ll1, rw1) = dummyHorizontalRoad(projectId, 1, RoadPart(999, 1), Sequences.nextRoadwayNumber, linkId = 12345, plId = Sequences.nextProjectLinkId, rw = (Sequences.nextRoadwayId, None), llId = Sequences.nextLinearLocationId)
      val (pl2, ll2, rw2) = dummyHorizontalRoad(projectId, 1, RoadPart(999, 1), Sequences.nextRoadwayNumber, startAddrAt = 10, administrativeClass = AdministrativeClass.Municipality, firstPointAt = Point(10.0, 0.0), linkId = 12346, plId = Sequences.nextProjectLinkId, rw = (Sequences.nextRoadwayId, None), llId = Sequences.nextLinearLocationId)
      val (pl3, ll3, rw3) = dummyHorizontalRoad(projectId, 1, RoadPart(999, 1), Sequences.nextRoadwayNumber, startAddrAt = 20, firstPointAt = Point(20.0, 0.0), linkId = 12347, plId = Sequences.nextProjectLinkId, rw = (Sequences.nextRoadwayId, None), llId = Sequences.nextLinearLocationId)

      val roadways = Seq(rw1, rw2, rw3)
      val projectLinks = pl1 ++ pl2 ++ pl3
      val linearLocations = ll1 ++ ll2 ++ ll3

      buildTestDataForProject(Some(project), Some(roadways), Some(linearLocations), Some(projectLinks))

      val projectChanges = List(
        ProjectRoadwayChange(projectId, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(RoadAddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, Some(AdministrativeClass.State), Some(Discontinuity.MinorDiscontinuity), Some(8L)),
            RoadwayChangeSection(Some(rw1.roadPart.roadNumber), Some(rw1.track.value), startRoadPartNumber = Some(rw1.roadPart.partNumber), endRoadPartNumber = Some(rw1.roadPart.partNumber), addrMRange = Some(rw1.addrMRange), Some(rw1.administrativeClass), Some(rw1.discontinuity), Some(rw1.ely)),
            rw1.discontinuity, rw1.administrativeClass, reversed = false, 1, rw1.ely)
          , DateTime.now),

        ProjectRoadwayChange(projectId, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(RoadAddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, Some(AdministrativeClass.State), Some(Discontinuity.MinorDiscontinuity), Some(8L)),
            RoadwayChangeSection(Some(rw2.roadPart.roadNumber), Some(rw2.track.value), startRoadPartNumber = Some(rw2.roadPart.partNumber), endRoadPartNumber = Some(rw2.roadPart.partNumber), addrMRange = Some(rw2.addrMRange), Some(rw2.administrativeClass), Some(rw2.discontinuity), Some(rw2.ely)),
            rw2.discontinuity, rw2.administrativeClass, reversed = false, 2, rw2.ely)
          , DateTime.now),

        ProjectRoadwayChange(projectId, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(RoadAddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, Some(AdministrativeClass.State), Some(Discontinuity.MinorDiscontinuity), Some(8L)),
            RoadwayChangeSection(Some(rw3.roadPart.roadNumber), Some(rw3.track.value), startRoadPartNumber = Some(rw3.roadPart.partNumber), endRoadPartNumber = Some(rw3.roadPart.partNumber), addrMRange = Some(rw3.addrMRange), Some(rw3.administrativeClass), Some(rw3.discontinuity), Some(rw3.ely)),
            rw3.discontinuity, rw3.administrativeClass, reversed = false, 3, rw3.ely)
          , DateTime.now)
      )

      val mappedReservedRoadwayNumbers = projectLinkDAO.fetchProjectLinksChange(projectId)
      roadAddressService.handleRoadwayPointsUpdate(projectChanges, mappedReservedRoadwayNumbers)
      nodesAndJunctionsService.handleNodePoints(projectChanges, projectLinks, mappedReservedRoadwayNumbers)

      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(Point(0.0, 0.0), Point(0.0, 0.0)), roadNumberLimits)).thenReturn(ll1)
      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(Point(10.0, 0.0), Point(10.0, 0.0)), roadNumberLimits)).thenReturn(ll1 ++ ll2)
      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(Point(20.0, 0.0), Point(20.0, 0.0)), roadNumberLimits)).thenReturn(ll2 ++ ll3)
      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(Point(30.0, 0.0), Point(30.0, 0.0)), roadNumberLimits)).thenReturn(ll3)

      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(    ll1     .map(_.roadwayNumber).toSet, withHistory=false)).thenReturn(Seq(rw1))
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(    ll2     .map(_.roadwayNumber).toSet, withHistory=false)).thenReturn(Seq(rw2))
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers((ll1 ++ ll2).map(_.roadwayNumber).toSet, withHistory=false)).thenReturn(Seq(rw1, rw2))
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers((ll2 ++ ll3).map(_.roadwayNumber).toSet, withHistory=false)).thenReturn(Seq(rw2, rw3))
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(    ll3     .map(_.roadwayNumber).toSet, withHistory=false)).thenReturn(Seq(rw3))

      nodesAndJunctionsService.handleJunctionAndJunctionPoints(projectChanges, projectLinks, mappedReservedRoadwayNumbers)
      nodesAndJunctionsService.expireObsoleteNodesAndJunctions(pl2++ pl1 ++ pl3, Some(project.startDate.minusDays(1)))

      val nodePoints = projectLinks.flatMap(pl => nodePointDAO.fetchTemplatesByRoadwayNumber(pl.roadwayNumber))
      nodePoints.size should be(6)
    }
  }

  /**
    * Test case for node points when track changes from track zero to track two
    */
  test("Test handleNodePointTemplates and expireObsoleteNodesAndJunctions when track zero becomes track 2") {
    runWithRollback {
      /*
        X-----X-----X----X> 997, 1, track = 0
        X-----X-----X----X> 998, 1, track = 0

       * Note:
          X: Existence node points in project

       */
      val project1Id: Long = Sequences.nextViiteProjectId
      val project1 = Project(project1Id, ProjectState.Incomplete, "f", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
        "", Seq(), Seq(), None, None)

      val (pl1, ll1, rw1) = dummyHorizontalRoad(project1Id, 1, RoadPart(997, 1), Sequences.nextRoadwayNumber, firstPointAt = Point(0.0, 10.0), linkId = 12345, plId = Sequences.nextProjectLinkId, rw = (Sequences.nextRoadwayId, None), llId = Sequences.nextLinearLocationId)
      val (pl2, ll2, rw2) = dummyHorizontalRoad(project1Id, 1, RoadPart(997, 1), Sequences.nextRoadwayNumber, startAddrAt = 10, administrativeClass = AdministrativeClass.Municipality, firstPointAt = Point(10.0, 10.0), linkId = 12346, plId = Sequences.nextProjectLinkId, rw = (Sequences.nextRoadwayId, None), llId = Sequences.nextLinearLocationId)
      val (pl3, ll3, rw3) = dummyHorizontalRoad(project1Id, 1, RoadPart(997, 1), Sequences.nextRoadwayNumber, startAddrAt = 20, discontinuity = Discontinuity.EndOfRoad, firstPointAt = Point(20.0, 10.0), linkId = 12347, plId = Sequences.nextProjectLinkId, rw = (Sequences.nextRoadwayId, None), llId = Sequences.nextLinearLocationId)
      val (pl4, ll4, rw4) = dummyHorizontalRoad(project1Id, 1, RoadPart(998, 1), Sequences.nextRoadwayNumber, firstPointAt = Point(0.0, 0.0), linkId = 12355, plId = Sequences.nextProjectLinkId, rw = (Sequences.nextRoadwayId, None), llId = Sequences.nextLinearLocationId)
      val (pl5, ll5, rw5) = dummyHorizontalRoad(project1Id, 1, RoadPart(998, 1), Sequences.nextRoadwayNumber, startAddrAt = 10, administrativeClass = AdministrativeClass.Municipality, firstPointAt = Point(10.0, 0.0), linkId = 12356, plId = Sequences.nextProjectLinkId, rw = (Sequences.nextRoadwayId, None), llId = Sequences.nextLinearLocationId)
      val (pl6, ll6, rw6) = dummyHorizontalRoad(project1Id, 1, RoadPart(998, 1), Sequences.nextRoadwayNumber, startAddrAt = 20, discontinuity = Discontinuity.EndOfRoad, firstPointAt = Point(20.0, 0.0), linkId = 12357, plId = Sequences.nextProjectLinkId, rw = (Sequences.nextRoadwayId, None), llId = Sequences.nextLinearLocationId)

      val roadways = Seq(rw1, rw2, rw3, rw4, rw5, rw6)
      val projectLinks = pl1 ++ pl2 ++ pl3 ++ pl4 ++ pl5 ++ pl6
      val linearLocations = ll1 ++ ll2 ++ ll3 ++ ll4 ++ ll5 ++ ll6

      buildTestDataForProject(Some(project1), Some(roadways), Some(linearLocations), Some(projectLinks))

      val projectChanges = List(
        ProjectRoadwayChange(project1Id, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(RoadAddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, Some(AdministrativeClass.State), Some(Discontinuity.MinorDiscontinuity), Some(8L)),
            RoadwayChangeSection(Some(rw1.roadPart.roadNumber), Some(rw1.track.value), startRoadPartNumber = Some(rw1.roadPart.partNumber), endRoadPartNumber = Some(rw1.roadPart.partNumber), addrMRange = Some(rw1.addrMRange), Some(rw1.administrativeClass), Some(rw1.discontinuity), Some(rw1.ely)),
            rw1.discontinuity, rw1.administrativeClass, reversed = false, 1, rw1.ely)
          , DateTime.now),

        ProjectRoadwayChange(project1Id, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(RoadAddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, Some(AdministrativeClass.State), Some(Discontinuity.MinorDiscontinuity), Some(8L)),
            RoadwayChangeSection(Some(rw2.roadPart.roadNumber), Some(rw2.track.value), startRoadPartNumber = Some(rw2.roadPart.partNumber), endRoadPartNumber = Some(rw2.roadPart.partNumber), addrMRange = Some(rw2.addrMRange), Some(rw2.administrativeClass), Some(rw2.discontinuity), Some(rw2.ely)),
            rw2.discontinuity, rw2.administrativeClass, reversed = false, 2, rw2.ely)
          , DateTime.now),

        ProjectRoadwayChange(project1Id, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(RoadAddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, Some(AdministrativeClass.State), Some(Discontinuity.MinorDiscontinuity), Some(8L)),
            RoadwayChangeSection(Some(rw3.roadPart.roadNumber), Some(rw3.track.value), startRoadPartNumber = Some(rw3.roadPart.partNumber), endRoadPartNumber = Some(rw3.roadPart.partNumber), addrMRange = Some(rw3.addrMRange), Some(rw3.administrativeClass), Some(rw3.discontinuity), Some(rw3.ely)),
            rw3.discontinuity, rw3.administrativeClass, reversed = false, 3, rw3.ely)
          , DateTime.now),

        ProjectRoadwayChange(project1Id, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(RoadAddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, Some(AdministrativeClass.State), Some(Discontinuity.MinorDiscontinuity), Some(8L)),
            RoadwayChangeSection(Some(rw4.roadPart.roadNumber), Some(rw4.track.value), startRoadPartNumber = Some(rw4.roadPart.partNumber), endRoadPartNumber = Some(rw4.roadPart.partNumber), addrMRange = Some(rw4.addrMRange), Some(rw4.administrativeClass), Some(rw4.discontinuity), Some(rw4.ely)),
            rw4.discontinuity, rw4.administrativeClass, reversed = false, 4, rw4.ely)
          , DateTime.now),

        ProjectRoadwayChange(project1Id, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(RoadAddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, Some(AdministrativeClass.State), Some(Discontinuity.MinorDiscontinuity), Some(8L)),
            RoadwayChangeSection(Some(rw5.roadPart.roadNumber), Some(rw5.track.value), startRoadPartNumber = Some(rw5.roadPart.partNumber), endRoadPartNumber = Some(rw5.roadPart.partNumber), addrMRange = Some(rw5.addrMRange), Some(rw5.administrativeClass), Some(rw5.discontinuity), Some(rw5.ely)),
            rw5.discontinuity, rw5.administrativeClass, reversed = false, 5, rw5.ely)
          , DateTime.now),

        ProjectRoadwayChange(project1Id, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(RoadAddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, Some(AdministrativeClass.State), Some(Discontinuity.MinorDiscontinuity), Some(8L)),
            RoadwayChangeSection(Some(rw6.roadPart.roadNumber), Some(rw6.track.value), startRoadPartNumber = Some(rw6.roadPart.partNumber), endRoadPartNumber = Some(rw6.roadPart.partNumber), addrMRange = Some(rw6.addrMRange), Some(rw6.administrativeClass), Some(rw6.discontinuity), Some(rw6.ely)),
            rw6.discontinuity, rw6.administrativeClass, reversed = false, 6, rw6.ely)
          , DateTime.now)
      )

      val mappedReservedRoadwayNumbers = projectLinkDAO.fetchProjectLinksChange(project1Id)
      roadAddressService.handleRoadwayPointsUpdate(projectChanges, mappedReservedRoadwayNumbers)
      nodesAndJunctionsService.handleNodePoints(projectChanges, projectLinks, mappedReservedRoadwayNumbers)

      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(Point( 0.0, 10.0), Point( 0.0, 10.0)), roadNumberLimits)).thenReturn(ll1)
      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(Point(10.0, 10.0), Point(10.0, 10.0)), roadNumberLimits)).thenReturn(ll1 ++ ll2)
      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(Point(20.0, 10.0), Point(20.0, 10.0)), roadNumberLimits)).thenReturn(ll2 ++ ll3)
      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(Point(30.0, 10.0), Point(30.0, 10.0)), roadNumberLimits)).thenReturn(ll3)

      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(Point( 0.0, 0.0), Point( 0.0, 0.0)), roadNumberLimits)).thenReturn(ll4)
      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(Point(10.0, 0.0), Point(10.0, 0.0)), roadNumberLimits)).thenReturn(ll4 ++ ll5)
      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(Point(20.0, 0.0), Point(20.0, 0.0)), roadNumberLimits)).thenReturn(ll5 ++ ll6)
      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(Point(30.0, 0.0), Point(30.0, 0.0)), roadNumberLimits)).thenReturn(ll6)

      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(    ll1     .map(_.roadwayNumber).toSet, withHistory=false)).thenReturn(Seq(rw1))
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(    ll2     .map(_.roadwayNumber).toSet, withHistory=false)).thenReturn(Seq(rw2))
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(    ll3     .map(_.roadwayNumber).toSet, withHistory=false)).thenReturn(Seq(rw3))
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers((ll1 ++ ll2).map(_.roadwayNumber).toSet, withHistory=false)).thenReturn(Seq(rw1, rw2))
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers((ll2 ++ ll3).map(_.roadwayNumber).toSet, withHistory=false)).thenReturn(Seq(rw2, rw3))

      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(    ll4     .map(_.roadwayNumber).toSet, withHistory=false)).thenReturn(Seq(rw4))
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(    ll5     .map(_.roadwayNumber).toSet, withHistory=false)).thenReturn(Seq(rw5))
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(    ll6     .map(_.roadwayNumber).toSet, withHistory=false)).thenReturn(Seq(rw6))
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers((ll4 ++ ll5).map(_.roadwayNumber).toSet, withHistory=false)).thenReturn(Seq(rw4, rw5))
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers((ll5 ++ ll6).map(_.roadwayNumber).toSet, withHistory=false)).thenReturn(Seq(rw5, rw6))

      nodesAndJunctionsService.handleJunctionAndJunctionPoints(projectChanges, projectLinks, mappedReservedRoadwayNumbers)
      nodesAndJunctionsService.expireObsoleteNodesAndJunctions(projectLinks, Some(project1.startDate.minusDays(1)))

      val nodePoints = projectLinks.flatMap(pl => nodePointDAO.fetchTemplatesByRoadwayNumber(pl.roadwayNumber))
      nodePoints.size should be(12)
      /*
        ------>----->-----> 999, 1, track = 2
        X-----X-----X----X> 998, 1, track = 1

       * Note:
          X: Existence node points in project

       */
      val project2Id: Long = Sequences.nextViiteProjectId
      val project2 = Project(project2Id, ProjectState.Incomplete, "n", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
        "", Seq(), Seq(), None, None)

      val (pl1t2, ll1t2, rw1t2) = dummyHorizontalRoad(project1Id, 1, RoadPart(999, 1), rw1.roadwayNumber, track = Track.LeftSide, firstPointAt = Point(0.0, 10.0), linkId = 12345, plId = Sequences.nextProjectLinkId, rw = (Sequences.nextRoadwayId, None), llId = Sequences.nextLinearLocationId)
      val (pl2t2, ll2t2, rw2t2) = dummyHorizontalRoad(project1Id, 1, RoadPart(999, 1), rw2.roadwayNumber, track = Track.LeftSide, startAddrAt = 10, administrativeClass = AdministrativeClass.Municipality, firstPointAt = Point(10.0, 10.0), linkId = 12346, plId = Sequences.nextProjectLinkId, rw = (Sequences.nextRoadwayId, None), llId = Sequences.nextLinearLocationId)
      val (pl3t2, ll3t2, rw3t2) = dummyHorizontalRoad(project1Id, 1, RoadPart(999, 1), rw3.roadwayNumber, track = Track.LeftSide, startAddrAt = 20, discontinuity = Discontinuity.EndOfRoad, firstPointAt = Point(20.0, 10.0), linkId = 12347, plId = Sequences.nextProjectLinkId, rw = (Sequences.nextRoadwayId, None), llId = Sequences.nextLinearLocationId)
      val (pl1t1, ll1t1, rw1t1) = dummyHorizontalRoad(project1Id, 1, RoadPart(999, 1), rw4.roadwayNumber, track = Track.RightSide, firstPointAt = Point(0.0, 0.0), linkId = 12355, plId = Sequences.nextProjectLinkId, rw = (Sequences.nextRoadwayId, None), llId = Sequences.nextLinearLocationId)
      val (pl2t1, ll2t1, rw2t1) = dummyHorizontalRoad(project1Id, 1, RoadPart(999, 1), rw5.roadwayNumber, track = Track.RightSide, startAddrAt = 10, administrativeClass = AdministrativeClass.Municipality, firstPointAt = Point(10.0, 0.0), linkId = 12356, plId = Sequences.nextProjectLinkId, rw = (Sequences.nextRoadwayId, None), llId = Sequences.nextLinearLocationId)
      val (pl3t1, ll3t1, rw3t1) = dummyHorizontalRoad(project1Id, 1, RoadPart(999, 1), rw6.roadwayNumber, track = Track.RightSide, startAddrAt = 20, discontinuity = Discontinuity.EndOfRoad, firstPointAt = Point(20.0, 0.0), linkId = 12357, plId = Sequences.nextProjectLinkId, rw = (Sequences.nextRoadwayId, None), llId = Sequences.nextLinearLocationId)

      val rwt2 = Seq(rw1t2, rw2t2, rw3t2)
      val rwt1 = Seq(rw1t1, rw2t1, rw3t1)

      val plt2 = pl1t2 ++ pl2t2 ++ pl3t2
      val plt1 = pl1t1 ++ pl2t1 ++ pl3t1

      val llt2 = ll1t2 ++ ll2t2 ++ ll3t2
      val llt1 = ll1t1 ++ ll2t1 ++ ll3t1

      val projectLinksAfterTrackChange = plt1 ++ plt2

      linearLocationDAO.expireByRoadwayNumbers(roadways.map(_.roadwayNumber).toSet)
      roadwayDAO.expireHistory(roadways.map(_.id).toSet)

      buildTestDataForProject(Some(project2), Some(rwt1 ++ rwt2), Some(llt1 ++ llt2), Some(projectLinksAfterTrackChange))

      val projectTrackChanges = List(
        ProjectRoadwayChange(project2Id, Some("project another name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(RoadAddressChangeType.Transfer,
            RoadwayChangeSection(Some(  rw4.roadPart.roadNumber), Some(  rw4.track.value), startRoadPartNumber = Some(  rw4.roadPart.partNumber), endRoadPartNumber = Some(  rw4.roadPart.partNumber), addrMRange = Some(  rw4.addrMRange), Some(  rw4.administrativeClass), Some(  rw4.discontinuity), Some(  rw4.ely)),
            RoadwayChangeSection(Some(rw1t1.roadPart.roadNumber), Some(rw1t1.track.value), startRoadPartNumber = Some(rw1t1.roadPart.partNumber), endRoadPartNumber = Some(rw1t1.roadPart.partNumber), addrMRange = Some(rw1t1.addrMRange), Some(rw1t1.administrativeClass), Some(rw1t1.discontinuity), Some(rw1t1.ely)),
            rw1t1.discontinuity, rw1t1.administrativeClass, reversed = false, 1, rw1t1.ely)
          , DateTime.now),

        ProjectRoadwayChange(project2Id, Some("project another name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(RoadAddressChangeType.Transfer,
            RoadwayChangeSection(Some(  rw1.roadPart.roadNumber), Some(  rw1.track.value), startRoadPartNumber = Some(  rw1.roadPart.partNumber), endRoadPartNumber = Some(  rw1.roadPart.partNumber), addrMRange = Some(  rw1.addrMRange), Some(  rw1.administrativeClass), Some(  rw1.discontinuity), Some(  rw1.ely)),
            RoadwayChangeSection(Some(rw1t2.roadPart.roadNumber), Some(rw1t2.track.value), startRoadPartNumber = Some(rw1t2.roadPart.partNumber), endRoadPartNumber = Some(rw1t2.roadPart.partNumber), addrMRange = Some(rw1t2.addrMRange), Some(rw1t2.administrativeClass), Some(rw1t2.discontinuity), Some(rw1t2.ely)),
            rw1t2.discontinuity, rw1t2.administrativeClass, reversed = false, 2, rw1t2.ely)
          , DateTime.now),

        ProjectRoadwayChange(project2Id, Some("project another name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(RoadAddressChangeType.Transfer,
            RoadwayChangeSection(Some(  rw5.roadPart.roadNumber), Some(  rw5.track.value), startRoadPartNumber = Some(  rw5.roadPart.partNumber), endRoadPartNumber = Some(  rw5.roadPart.partNumber), addrMRange = Some(  rw5.addrMRange), Some(  rw5.administrativeClass), Some(  rw5.discontinuity), Some(  rw5.ely)),
            RoadwayChangeSection(Some(rw2t1.roadPart.roadNumber), Some(rw2t1.track.value), startRoadPartNumber = Some(rw2t1.roadPart.partNumber), endRoadPartNumber = Some(rw2t1.roadPart.partNumber), addrMRange = Some(rw2t1.addrMRange), Some(rw2t1.administrativeClass), Some(rw2t1.discontinuity), Some(rw2t1.ely)),
            rw2t1.discontinuity, rw2t1.administrativeClass, reversed = false, 3, rw2t1.ely)
          , DateTime.now),

        ProjectRoadwayChange(project2Id, Some("project another name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(RoadAddressChangeType.Transfer,
            RoadwayChangeSection(Some(  rw2.roadPart.roadNumber), Some(  rw2.track.value), startRoadPartNumber = Some(  rw2.roadPart.partNumber), endRoadPartNumber = Some(  rw2.roadPart.partNumber), addrMRange = Some(  rw2.addrMRange), Some(  rw2.administrativeClass), Some(  rw2.discontinuity), Some(  rw2.ely)),
            RoadwayChangeSection(Some(rw2t2.roadPart.roadNumber), Some(rw2t2.track.value), startRoadPartNumber = Some(rw2t2.roadPart.partNumber), endRoadPartNumber = Some(rw2t2.roadPart.partNumber), addrMRange = Some(rw2t2.addrMRange), Some(rw2t2.administrativeClass), Some(rw2t2.discontinuity), Some(rw2t2.ely)),
            rw2t2.discontinuity, rw2t2.administrativeClass, reversed = false, 4, rw2t2.ely)
          , DateTime.now),

        ProjectRoadwayChange(project2Id, Some("project another name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(RoadAddressChangeType.Transfer,
            RoadwayChangeSection(Some(  rw6.roadPart.roadNumber), Some(  rw6.track.value), startRoadPartNumber = Some(  rw6.roadPart.partNumber), endRoadPartNumber = Some(  rw6.roadPart.partNumber), addrMRange = Some(  rw6.addrMRange), Some(  rw6.administrativeClass), Some(  rw6.discontinuity), Some(  rw6.ely)),
            RoadwayChangeSection(Some(rw3t1.roadPart.roadNumber), Some(rw3t1.track.value), startRoadPartNumber = Some(rw3t1.roadPart.partNumber), endRoadPartNumber = Some(rw3t1.roadPart.partNumber), addrMRange = Some(rw3t1.addrMRange), Some(rw3t1.administrativeClass), Some(rw3t1.discontinuity), Some(rw3t1.ely)),
            rw3t1.discontinuity, rw3t1.administrativeClass, reversed = false, 5, rw3t1.ely)
          , DateTime.now),

        ProjectRoadwayChange(project2Id, Some("project another name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(RoadAddressChangeType.Transfer,
            RoadwayChangeSection(Some(  rw3.roadPart.roadNumber), Some(  rw3.track.value), startRoadPartNumber = Some(  rw3.roadPart.partNumber), endRoadPartNumber = Some(  rw3.roadPart.partNumber), addrMRange = Some(  rw3.addrMRange), Some(  rw3.administrativeClass), Some(  rw3.discontinuity), Some(  rw3.ely)),
            RoadwayChangeSection(Some(rw3t2.roadPart.roadNumber), Some(rw3t2.track.value), startRoadPartNumber = Some(rw3t2.roadPart.partNumber), endRoadPartNumber = Some(rw3t2.roadPart.partNumber), addrMRange = Some(rw3t2.addrMRange), Some(rw3t2.administrativeClass), Some(rw3t2.discontinuity), Some(rw3t2.ely)),
            rw3t2.discontinuity, rw3t2.administrativeClass, reversed = false, 6, rw3t2.ely)
          , DateTime.now)
      )

      val mappedReservedRoadwayNumbersAfterTrackChange = projectLinkDAO.fetchProjectLinksChange(project2Id)
      roadAddressService.handleRoadwayPointsUpdate(projectTrackChanges, mappedReservedRoadwayNumbersAfterTrackChange)
      nodesAndJunctionsService.handleNodePoints(projectTrackChanges, projectLinksAfterTrackChange, mappedReservedRoadwayNumbersAfterTrackChange)

      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(Point(0.0, 10.0), Point(0.0, 10.0)), roadNumberLimits)).thenReturn(ll1t2)
      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(Point(10.0, 10.0), Point(10.0, 10.0)), roadNumberLimits)).thenReturn(ll1t2 ++ ll2t2)
      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(Point(20.0, 10.0), Point(20.0, 10.0)), roadNumberLimits)).thenReturn(ll2t2 ++ ll3t2)
      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(Point(30.0, 10.0), Point(30.0, 10.0)), roadNumberLimits)).thenReturn(ll3t2)

      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(Point(0.0, 0.0), Point(0.0, 0.0)), roadNumberLimits)).thenReturn(ll1t1)
      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(Point(10.0, 0.0), Point(10.0, 0.0)), roadNumberLimits)).thenReturn(ll1t1 ++ ll2t1)
      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(Point(20.0, 0.0), Point(20.0, 0.0)), roadNumberLimits)).thenReturn(ll2t1 ++ ll3t1)
      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(Point(30.0, 0.0), Point(30.0, 0.0)), roadNumberLimits)).thenReturn(ll3t1)

      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(     ll1t1      .map(_.roadwayNumber).toSet, withHistory=false)).thenReturn(Seq(rw1t1))
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(     ll2t1      .map(_.roadwayNumber).toSet, withHistory=false)).thenReturn(Seq(rw2t1))
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(     ll3t1      .map(_.roadwayNumber).toSet, withHistory=false)).thenReturn(Seq(rw3t1))
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers((ll1t1 ++ ll2t1).map(_.roadwayNumber).toSet, withHistory=false)).thenReturn(Seq(rw1t1, rw2t1))
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers((ll2t1 ++ ll3t1).map(_.roadwayNumber).toSet, withHistory=false)).thenReturn(Seq(rw2t1, rw3t1))

      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(     ll1t2      .map(_.roadwayNumber).toSet, withHistory=false)).thenReturn(Seq(rw1t2))
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(     ll2t2      .map(_.roadwayNumber).toSet, withHistory=false)).thenReturn(Seq(rw2t2))
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(     ll3t2      .map(_.roadwayNumber).toSet, withHistory=false)).thenReturn(Seq(rw3t2))
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers((ll1t2 ++ ll2t2).map(_.roadwayNumber).toSet, withHistory=false)).thenReturn(Seq(rw1t2, rw2t2))
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers((ll2t2 ++ ll3t2).map(_.roadwayNumber).toSet, withHistory=false)).thenReturn(Seq(rw2t2, rw3t2))

      when(mockRoadwayDAO.fetchAllByRoadwayNumbers((ll1t1 ++ ll1t2).map(_.roadwayNumber).toSet, withHistory=false)).thenReturn(Seq(rw1t1, rw1t2))
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers((ll2t1 ++ ll2t2).map(_.roadwayNumber).toSet, withHistory=false)).thenReturn(Seq(rw2t1, rw2t2))
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers((ll3t1 ++ ll3t2).map(_.roadwayNumber).toSet, withHistory=false)).thenReturn(Seq(rw3t1, rw3t2))

      nodesAndJunctionsService.handleJunctionAndJunctionPoints(projectTrackChanges, projectLinksAfterTrackChange, mappedReservedRoadwayNumbersAfterTrackChange)
      nodesAndJunctionsService.expireObsoleteNodesAndJunctions(projectLinksAfterTrackChange, Some(project2.startDate.minusDays(1)))

      val nodePointsAfterTrackChange = projectLinksAfterTrackChange.flatMap(pl => nodePointDAO.fetchTemplatesByRoadwayNumber(pl.roadwayNumber))
      nodePointsAfterTrackChange.size should be(6)
    }
  }

  test("Test addOrUpdateNode When creating new Then new is created successfully") {
    runWithRollback {
      val node = Node(NewIdValue, Sequences.nextNodeNumber, Point(0, 0), Some("Node name"), NodeType.EndOfRoad,
        DateTime.now().withTimeAtStartOfDay(), None, DateTime.now(), None, "user", Some(DateTime.now()), registrationDate = new DateTime())
      nodesAndJunctionsService.addOrUpdateNode(node, isObsoleteNode=false, node.createdBy) should be (node.nodeNumber)
      val fetched = nodeDAO.fetchByNodeNumber(node.nodeNumber).getOrElse(fail("No node found"))
      fetched.startDate should be(node.startDate)
      fetched.nodeType should be(node.nodeType)
      fetched.nodeNumber should be(node.nodeNumber)
      fetched.coordinates should be(node.coordinates)
      fetched.endDate should be(node.endDate)
      fetched.createdBy should be(node.createdBy)
      fetched.name should be(node.name)
      fetched.editor should be(node.editor)
      fetched.publishedTime should be(node.publishedTime)
      val historyRowEndDate = runSelectSingleFirstOptionWithType[Date](
        sql"""
              SELECT END_DATE
              FROM NODE
              WHERE NODE_NUMBER = ${node.nodeNumber} and valid_to is null and end_date is not null
        """
      )
      historyRowEndDate should be(None)
    }
  }

  test("Test addOrUpdateNode When update existing Then existing is expired and new created") {
    runWithRollback {
      val node = Node(Sequences.nextNodeId, Sequences.nextNodeNumber, Point(0, 0), Some("Node name"), NodeType.EndOfRoad,
        DateTime.now().withTimeAtStartOfDay(), None, DateTime.now(), None, "user", Some(DateTime.now().minusDays(1)), registrationDate = new DateTime())
      nodeDAO.create(Seq(node), node.createdBy)
      nodeDAO.fetchById(node.id) should not be None
      nodesAndJunctionsService.addOrUpdateNode(node.copy(coordinates = Point(1, 1)), isObsoleteNode=false, node.createdBy) should be (node.nodeNumber)
      nodeDAO.fetchById(node.id) should be(None)
      val updated = nodeDAO.fetchByNodeNumber(node.nodeNumber).getOrElse(fail("Node not found"))
      updated.id should not be node.id
      updated.createdBy should be(node.createdBy)
      updated.createdTime should not be node.createdTime
      updated.publishedTime should be(None)
      updated.editor should be(None)
      updated.coordinates should be(Point(1, 1))
      val historyRowEndDate = runSelectSingleFirstOptionWithType[Date](
        sql"""
              SELECT END_DATE
              FROM NODE
              WHERE NODE_NUMBER = ${node.nodeNumber} and valid_to is null and end_date is not null
              """
      )
      historyRowEndDate should be(None)
    }
  }

  test("Test addOrUpdateNode When update existing and change type but not start date Then existing is expired, new created") {
    runWithRollback {
      val node = Node(Sequences.nextNodeId, Sequences.nextNodeNumber, Point(0, 0), Some("Node name"), NodeType.EndOfRoad,
        DateTime.now().withTimeAtStartOfDay(), None, DateTime.now(), None, "user", Some(DateTime.now().minusDays(1)), registrationDate = new DateTime())
      nodeDAO.create(Seq(node), node.createdBy)
      nodeDAO.fetchById(node.id) should not be None
      nodesAndJunctionsService.addOrUpdateNode(node.copy(coordinates = Point(1, 1), nodeType = NodeType.Bridge), isObsoleteNode=false, node.createdBy) should be (node.nodeNumber)
      nodeDAO.fetchById(node.id) should be(None)
      val updated = nodeDAO.fetchByNodeNumber(node.nodeNumber).getOrElse(fail("Node not found"))
      updated.id should not be node.id
      updated.createdBy should be(node.createdBy)
      updated.createdTime should not be node.createdTime
      updated.publishedTime should be(None)
      updated.editor should be(None)
      updated.coordinates should be(Point(1, 1))
      updated.nodeType should be(NodeType.Bridge)
      updated.startDate should be(node.startDate)
      val historyRowEndDate = runSelectSingleFirstOptionWithType[Date](
        sql"""SELECT END_DATE
              from NODE
              WHERE NODE_NUMBER = ${node.nodeNumber}
              and valid_to is null
              and end_date is not null
              """
      )
      historyRowEndDate should be(None)
    }
  }

  test("Test addOrUpdateNode When update existing and change type and start date Then existing is expired, history and new created") {
    runWithRollback {
      val node = Node(Sequences.nextNodeId, Sequences.nextNodeNumber, Point(0, 0), None, NodeType.EndOfRoad,
        DateTime.now().minusDays(1).withTimeAtStartOfDay(), None, DateTime.now(), None, "user", Some(DateTime.now().minusDays(1)), registrationDate = new DateTime())
      nodeDAO.create(Seq(node), node.createdBy)
      nodeDAO.fetchById(node.id) should not be None
      nodesAndJunctionsService.addOrUpdateNode(node.copy(startDate = DateTime.now().plusDays(1).withTimeAtStartOfDay(),
        nodeType = NodeType.Bridge), isObsoleteNode=false, node.createdBy) should be (node.nodeNumber)
      nodeDAO.fetchById(node.id) should be(None)
      val updated = nodeDAO.fetchByNodeNumber(node.nodeNumber).getOrElse(fail("Node not found"))
      updated.id should not be node.id
      updated.createdBy should be(node.createdBy)
      updated.createdTime should not be node.createdTime
      updated.publishedTime should be(None)
      updated.editor should be(None)
      updated.nodeType should be(NodeType.Bridge)
      updated.startDate should not be node.startDate
      val historyRowEndDate = runSelectSingleFirstOptionWithType[Date](
        sql"""SELECT END_DATE
              from NODE
              WHERE NODE_NUMBER = ${node.nodeNumber}
              and valid_to is null
              and end_date is not null
              """
      )
      historyRowEndDate should not be None
    }
  }

  test("Test addOrUpdate When detached all junctions and nodePoints Then existing node is expired and new created new to history") {
    runWithRollback {
      val node = Node(Sequences.nextNodeId, Sequences.nextNodeNumber, Point(0, 0), Some("Node name"), NodeType.EndOfRoad,
        DateTime.now().withTimeAtStartOfDay(), None, DateTime.now(), None, "user", Some(DateTime.now()), registrationDate = new DateTime())
      nodeDAO.create(Seq(node), node.createdBy)
      val junctions = Seq()
      val nodePoints = Seq()
      nodeDAO.fetchById(node.id) should not be None
      nodesAndJunctionsService.addOrUpdate(node.copy(coordinates = Point(1, 1)), junctions, nodePoints, node.createdBy)
      nodeDAO.fetchById(node.id) should be(None)
    }
  }

  test("Test calculateNodePointsForNode When node not found Then not fail") {
    runWithRollback {
      nodesAndJunctionsService.calculateNodePointsForNode(-1, "TestUser")
    }
  }

  test("Test calculateNodePointsForNodes When nodes not found Then not fail") {
    runWithRollback {
      nodesAndJunctionsService.calculateNodePointsForNodes(Seq.empty[Long], "TestUser")
    }
  }

  test("Test calculateNodePointsForNode When node is empty Then do nothing") {
    runWithRollback {
      val nodeNumber = nodeDAO.create(Seq(testNode1)).head
      nodesAndJunctionsService.calculateNodePointsForNode(nodeNumber, "TestUser")
      nodePointDAO.fetchByNodeNumbers(Seq(nodeNumber))
      val newNodes = nodePointDAO.fetchCalculatedNodePointsForNodeNumber(nodeNumber)
      newNodes.size should be(0)
    }
  }

  test("Test calculateNodePointsForNode most simple case Then calculate node point") {
    runWithRollback {
      val nodeNumber = nodeDAO.create(Seq(testNode1)).head
      val roadwayNumber1 = Sequences.nextRoadwayNumber
      roadwayDAO.create(Seq(testRoadway1.copy(roadwayNumber = roadwayNumber1)))
      val roadwayPointId1 = roadwayPointDAO.create(testRoadwayPoint1.copy(roadwayNumber = roadwayNumber1))
      val junctionId = junctionDAO.create(Seq(testJunction1.copy(nodeNumber = Option(nodeNumber)))).head
      junctionPointDAO.create(Seq(testJunctionPoint1.copy(junctionId = junctionId, roadwayPointId = roadwayPointId1)))

      val before = nodePointDAO.fetchByNodeNumbers(Seq(nodeNumber))
      nodesAndJunctionsService.calculateNodePointsForNode(nodeNumber, "TestUser")
      val after = nodePointDAO.fetchByNodeNumbers(Seq(nodeNumber))
      before.map(_.id).toSet should not be after.map(_.id).toSet
      val newNodes = nodePointDAO.fetchCalculatedNodePointsForNodeNumber(nodeNumber)
      newNodes.size should be(1)
      newNodes(0).addrM should be(0)
    }
  }

  test("Test calculateNodePointsForNode When all the road parts of node already contain 'road node points' Then do nothing") {
    runWithRollback {
      val nodeNumber = nodeDAO.create(Seq(testNode1)).head
      val roadwayNumber1 = Sequences.nextRoadwayNumber
      val roadwayNumber2 = Sequences.nextRoadwayNumber
      roadwayDAO.create(Seq(testRoadway1.copy(roadwayNumber = roadwayNumber1)))
      roadwayDAO.create(Seq(testRoadway1.copy(roadwayNumber = roadwayNumber2, roadPart = RoadPart(991,testRoadway1.roadPart.partNumber))))
      val roadwayPointId1 = roadwayPointDAO.create(testRoadwayPoint1.copy(roadwayNumber = roadwayNumber1))
      val roadwayPointId2 = roadwayPointDAO.create(testRoadwayPoint1.copy(roadwayNumber = roadwayNumber2))
      val junctionId = junctionDAO.create(Seq(testJunction1.copy(nodeNumber = Option(nodeNumber)))).head
      val junctionId2 = junctionDAO.create(Seq(testJunction1.copy(nodeNumber = Option(nodeNumber)))).head
      junctionPointDAO.create(Seq(testJunctionPoint1.copy(junctionId = junctionId, roadwayPointId = roadwayPointId1)))
      junctionPointDAO.create(Seq(testJunctionPoint1.copy(junctionId = junctionId2, roadwayPointId = roadwayPointId2)))
      nodePointDAO.create(Seq(testNodePoint1.copy(nodeNumber = Some(nodeNumber), roadwayPointId = roadwayPointId1, nodePointType = NodePointType.RoadNodePoint)))
      nodePointDAO.create(Seq(testNodePoint1.copy(nodeNumber = Some(nodeNumber), roadwayPointId = roadwayPointId2, nodePointType = NodePointType.RoadNodePoint)))

      val before = nodePointDAO.fetchByNodeNumbers(Seq(nodeNumber))
      nodesAndJunctionsService.calculateNodePointsForNode(nodeNumber, "TestUser")
      val after = nodePointDAO.fetchByNodeNumbers(Seq(nodeNumber))
      before.map(_.id).toSet should be(after.map(_.id).toSet)
      val newNodes = nodePointDAO.fetchCalculatedNodePointsForNodeNumber(nodeNumber)
      newNodes.size should be(0)
    }
  }

  test("Test calculateNodePointsForNode When one road part doesn't contain 'road node point' and second road contains 'road node point'. Then calculate node point") {
    runWithRollback {
      val nodeNumber = nodeDAO.create(Seq(testNode1)).head
      val roadwayNumber1 = Sequences.nextRoadwayNumber
      val roadwayNumber2 = Sequences.nextRoadwayNumber
      roadwayDAO.create(Seq(testRoadway1.copy(roadwayNumber = roadwayNumber1)))
      roadwayDAO.create(Seq(testRoadway1.copy(roadwayNumber = roadwayNumber2, roadPart = RoadPart(991,testRoadway1.roadPart.partNumber))))
      val roadwayPointId1 = roadwayPointDAO.create(testRoadwayPoint1.copy(roadwayNumber = roadwayNumber1))
      val roadwayPointId2 = roadwayPointDAO.create(testRoadwayPoint1.copy(roadwayNumber = roadwayNumber2))
      val junctionId = junctionDAO.create(Seq(testJunction1.copy(nodeNumber = Option(nodeNumber)))).head
      val junctionId2 = junctionDAO.create(Seq(testJunction1.copy(nodeNumber = Option(nodeNumber)))).head
      junctionPointDAO.create(Seq(testJunctionPoint1.copy(junctionId = junctionId, roadwayPointId = roadwayPointId1)))
      junctionPointDAO.create(Seq(testJunctionPoint1.copy(junctionId = junctionId2, roadwayPointId = roadwayPointId2)))
      nodePointDAO.create(Seq(testNodePoint1.copy(nodeNumber = Some(nodeNumber), roadwayPointId = roadwayPointId2, nodePointType = NodePointType.RoadNodePoint)))

      val before = nodePointDAO.fetchByNodeNumbers(Seq(nodeNumber))
      nodesAndJunctionsService.calculateNodePointsForNode(nodeNumber, "TestUser")
      val after = nodePointDAO.fetchByNodeNumbers(Seq(nodeNumber))
      before.map(_.id).toSet should not be after.map(_.id).toSet
      val newNodes = nodePointDAO.fetchCalculatedNodePointsForNodeNumber(nodeNumber)
      newNodes.size should be(1)
      newNodes(0).addrM should be(0)
    }
  }

  test("Test calculateNodePointsForNode When two track road part doesn't contain 'road node point'. Junction in Left Track. Then calculate node point") {
    runWithRollback {
      val nodeNumber = nodeDAO.create(Seq(testNode1)).head
      val roadwayNumber1 = Sequences.nextRoadwayNumber
      val roadwayNumber2 = Sequences.nextRoadwayNumber
      roadwayDAO.create(Seq(testRoadway1.copy(roadwayNumber = roadwayNumber1, track = Track.LeftSide)))
      roadwayDAO.create(Seq(testRoadway1.copy(roadwayNumber = roadwayNumber2, track = Track.RightSide)))
      val roadwayPointId1 = roadwayPointDAO.create(testRoadwayPoint1.copy(roadwayNumber = roadwayNumber1, addrMValue = 10))
//    val roadwayPointId2 = roadwayPointDAO.create(testRoadwayPoint2.copy(roadwayNumber = roadwayNumber2, addrMValue = 20))
      val junctionId1 = junctionDAO.create(Seq(testJunction1.copy(nodeNumber = Option(nodeNumber)))).head
      junctionPointDAO.create(Seq(testJunctionPoint1.copy(junctionId = junctionId1, roadwayPointId = roadwayPointId1)))

      val before = nodePointDAO.fetchByNodeNumbers(Seq(nodeNumber))
      nodesAndJunctionsService.calculateNodePointsForNode(nodeNumber, "TestUser")
      val after = nodePointDAO.fetchByNodeNumbers(Seq(nodeNumber))
      before.map(_.id).toSet should not be after.map(_.id).toSet
      val newNodePoints = nodePointDAO.fetchCalculatedNodePointsForNodeNumber(nodeNumber)
      newNodePoints.size should be(2)
      newNodePoints(0).addrM should be(10)
      newNodePoints(1).addrM should be(10)
    }
  }

  test("Test calculateNodePointsForNode When road has both two track and one track part Then calculate node point based on the actual address") {
    runWithRollback {
      val nodeNumber = nodeDAO.create(Seq(testNode1)).head
      val roadwayNumber1 = Sequences.nextRoadwayNumber
      val roadwayNumber2 = Sequences.nextRoadwayNumber
      val roadwayNumber3 = Sequences.nextRoadwayNumber
      roadwayDAO.create(Seq(testRoadway1.copy(roadwayNumber = roadwayNumber1, track = Track.LeftSide,  addrMRange = AddrMRange( 0, 50))))
      roadwayDAO.create(Seq(testRoadway1.copy(roadwayNumber = roadwayNumber2, track = Track.RightSide, addrMRange = AddrMRange( 0, 50))))
      roadwayDAO.create(Seq(testRoadway1.copy(roadwayNumber = roadwayNumber3, track = Track.Combined,  addrMRange = AddrMRange(50,100))))
      val roadwayPointId1 = roadwayPointDAO.create(testRoadwayPoint1.copy(roadwayNumber = roadwayNumber1, addrMValue = 10))
      val roadwayPointId2 = roadwayPointDAO.create(testRoadwayPoint2.copy(roadwayNumber = roadwayNumber2, addrMValue = 20))
      val roadwayPointId3 = roadwayPointDAO.create(testRoadwayPoint2.copy(roadwayNumber = roadwayNumber3, addrMValue = 60))
      val junctionId1 = junctionDAO.create(Seq(testJunction1.copy(nodeNumber = Option(nodeNumber)))).head
      val junctionId2 = junctionDAO.create(Seq(testJunction1.copy(nodeNumber = Option(nodeNumber)))).head
      val junctionId3 = junctionDAO.create(Seq(testJunction1.copy(nodeNumber = Option(nodeNumber)))).head
      junctionPointDAO.create(Seq(testJunctionPoint1.copy(junctionId = junctionId1, roadwayPointId = roadwayPointId1)))
      junctionPointDAO.create(Seq(testJunctionPoint1.copy(junctionId = junctionId2, roadwayPointId = roadwayPointId2)))
      junctionPointDAO.create(Seq(testJunctionPoint1.copy(junctionId = junctionId3, roadwayPointId = roadwayPointId3)))

      val before = nodePointDAO.fetchByNodeNumbers(Seq(nodeNumber))
      nodesAndJunctionsService.calculateNodePointsForNode(nodeNumber, "TestUser")

      val after = nodePointDAO.fetchByNodeNumbers(Seq(nodeNumber))

      before.map(_.id).toSet should not be after.map(_.id).toSet
      after.size should be(2)
      after(0).addrM should be (30)
      after(0).track should be (Track.RightSide)
      after(1).addrM should be (30)
      after(1).track should be (Track.RightSide)
    }
  }

  test("Test calculateNodePointsForNode When average calculates on a roadway without junction Then the roadway is selected correctly") {
    runWithRollback {
      val nodeNumber = nodeDAO.create(Seq(testNode1)).head
      val roadwayNumber1 = Sequences.nextRoadwayNumber
      val roadwayNumber2 = Sequences.nextRoadwayNumber
      val roadwayNumber3 = Sequences.nextRoadwayNumber
      val roadwayNumber4 = Sequences.nextRoadwayNumber
      val roadwayNumber5 = Sequences.nextRoadwayNumber
      roadwayDAO.create(Seq(testRoadway1.copy(roadwayNumber = roadwayNumber1, track = Track.LeftSide,  addrMRange = AddrMRange(  0, 50))))
      roadwayDAO.create(Seq(testRoadway1.copy(roadwayNumber = roadwayNumber2, track = Track.RightSide, addrMRange = AddrMRange(  0, 50))))
      roadwayDAO.create(Seq(testRoadway1.copy(roadwayNumber = roadwayNumber3, track = Track.Combined,  addrMRange = AddrMRange( 50,100))))
      roadwayDAO.create(Seq(testRoadway1.copy(roadwayNumber = roadwayNumber4, track = Track.LeftSide,  addrMRange = AddrMRange(100,150))))
      roadwayDAO.create(Seq(testRoadway1.copy(roadwayNumber = roadwayNumber5, track = Track.RightSide, addrMRange = AddrMRange(100,150))))
      val roadwayPointId1 = roadwayPointDAO.create(testRoadwayPoint1.copy(roadwayNumber = roadwayNumber1, addrMValue = 10))
      val roadwayPointId2 = roadwayPointDAO.create(testRoadwayPoint2.copy(roadwayNumber = roadwayNumber4, addrMValue = 110))
      val junctionId1 = junctionDAO.create(Seq(testJunction1.copy(nodeNumber = Option(nodeNumber)))).head
      val junctionId2 = junctionDAO.create(Seq(testJunction1.copy(nodeNumber = Option(nodeNumber)))).head
      junctionPointDAO.create(Seq(testJunctionPoint1.copy(junctionId = junctionId1, roadwayPointId = roadwayPointId1)))
      junctionPointDAO.create(Seq(testJunctionPoint1.copy(junctionId = junctionId2, roadwayPointId = roadwayPointId2)))

      val before = nodePointDAO.fetchByNodeNumbers(Seq(nodeNumber))
      nodesAndJunctionsService.calculateNodePointsForNode(nodeNumber, "TestUser")

      val after = nodePointDAO.fetchByNodeNumbers(Seq(nodeNumber))

      before.map(_.id).toSet should not be after.map(_.id).toSet
      after.size should be(2)
      after(0).addrM should be (60)
      after(0).track should be (Track.Combined)
      after(0).roadwayNumber should be (roadwayNumber3)
      after(1).addrM should be (60)
      after(1).track should be (Track.Combined)
      after(1).roadwayNumber should be (roadwayNumber3)
    }
  }

  test("Test calculateNodePointsForNode When two track road part doesn't contain 'road node point'. Junction in Right Track. Then calculate node point") {
    runWithRollback {
      val nodeNumber = nodeDAO.create(Seq(testNode1)).head
      val roadwayNumber1 = Sequences.nextRoadwayNumber
      val roadwayNumber2 = Sequences.nextRoadwayNumber
      roadwayDAO.create(Seq(testRoadway1.copy(roadwayNumber = roadwayNumber1, track = Track.LeftSide)))
      roadwayDAO.create(Seq(testRoadway1.copy(roadwayNumber = roadwayNumber2, track = Track.RightSide)))
      val roadwayPointId2 = roadwayPointDAO.create(testRoadwayPoint2.copy(roadwayNumber = roadwayNumber2, addrMValue = 20))
      val junctionId2 = junctionDAO.create(Seq(testJunction1.copy(nodeNumber = Option(nodeNumber)))).head
      junctionPointDAO.create(Seq(testJunctionPoint1.copy(junctionId = junctionId2, roadwayPointId = roadwayPointId2)))

      val before = nodePointDAO.fetchByNodeNumbers(Seq(nodeNumber))
      nodesAndJunctionsService.calculateNodePointsForNode(nodeNumber, "TestUser")
      val after = nodePointDAO.fetchByNodeNumbers(Seq(nodeNumber))
      before.map(_.id).toSet should not be after.map(_.id).toSet
      val newNodePoints = nodePointDAO.fetchCalculatedNodePointsForNodeNumber(nodeNumber)
      newNodePoints.size should be(2)
      newNodePoints(0).addrM should be(20)
      newNodePoints(1).addrM should be(20)
    }
  }

  test("Test calculateNodePointsForNode When two track road part has junction points on both tracks and no 'road node points' Then calculate node point") {
    runWithRollback {
      val nodeNumber = nodeDAO.create(Seq(testNode1)).head
      val roadwayNumber1 = Sequences.nextRoadwayNumber
      val roadwayNumber2 = Sequences.nextRoadwayNumber
      roadwayDAO.create(Seq(testRoadway1.copy(roadwayNumber = roadwayNumber1, track = Track.LeftSide)))
      roadwayDAO.create(Seq(testRoadway1.copy(roadwayNumber = roadwayNumber2, track = Track.RightSide)))
      val roadwayPointId1 = roadwayPointDAO.create(testRoadwayPoint1.copy(roadwayNumber = roadwayNumber1, addrMValue = 10))
      val roadwayPointId2 = roadwayPointDAO.create(testRoadwayPoint2.copy(roadwayNumber = roadwayNumber2, addrMValue = 20))
      val junctionId1 = junctionDAO.create(Seq(testJunction1.copy(nodeNumber = Option(nodeNumber)))).head
      junctionPointDAO.create(Seq(testJunctionPoint1.copy(junctionId = junctionId1, roadwayPointId = roadwayPointId1)))
      val junctionId2 = junctionDAO.create(Seq(testJunction1.copy(nodeNumber = Option(nodeNumber)))).head
      junctionPointDAO.create(Seq(testJunctionPoint1.copy(junctionId = junctionId2, roadwayPointId = roadwayPointId2)))

      val before = nodePointDAO.fetchByNodeNumbers(Seq(nodeNumber))
      nodesAndJunctionsService.calculateNodePointsForNode(nodeNumber, "TestUser")
      val after = nodePointDAO.fetchByNodeNumbers(Seq(nodeNumber))
      before.map(_.id).toSet should not be after.map(_.id).toSet
      val newNodePoints = nodePointDAO.fetchCalculatedNodePointsForNodeNumber(nodeNumber)
      newNodePoints.size should be(2)
      newNodePoints(0).addrM should be(15)
      newNodePoints(1).addrM should be(15)
    }
  }

  test("Test calculateNodePointsForNode When two track road part has junction points on both tracks and crossing road. Powerpoint slide 4 Then calculate node point") {
    runWithRollback {
      val nodeNumber = nodeDAO.create(Seq(testNode1)).head
      val roadwayNumber1 = Sequences.nextRoadwayNumber
      val roadwayNumber2 = Sequences.nextRoadwayNumber
      val roadwayNumber3 = Sequences.nextRoadwayNumber
      roadwayDAO.create(Seq(testRoadway1.copy(roadwayNumber = roadwayNumber1, track = Track.LeftSide)))
      roadwayDAO.create(Seq(testRoadway1.copy(roadwayNumber = roadwayNumber2, track = Track.RightSide)))
      roadwayDAO.create(Seq(testRoadway1.copy(roadwayNumber = roadwayNumber3, roadPart = RoadPart(991,testRoadway1.roadPart.partNumber), track = Track.Combined)))
      val roadwayPointId1 = roadwayPointDAO.create(testRoadwayPoint1.copy(roadwayNumber = roadwayNumber1, addrMValue = 10))
      val roadwayPointId2 = roadwayPointDAO.create(testRoadwayPoint2.copy(roadwayNumber = roadwayNumber2, addrMValue = 20))
      val roadwayPointId3 = roadwayPointDAO.create(testRoadwayPoint2.copy(roadwayNumber = roadwayNumber1, addrMValue = 20))
      val roadwayPointId4 = roadwayPointDAO.create(testRoadwayPoint2.copy(roadwayNumber = roadwayNumber3, addrMValue = 20))
      val roadwayPointId5 = roadwayPointDAO.create(testRoadwayPoint2.copy(roadwayNumber = roadwayNumber3, addrMValue = 30))
      val roadwayPointId6 = roadwayPointDAO.create(testRoadwayPoint2.copy(roadwayNumber = roadwayNumber3, addrMValue = 50))
      val junctionId1 = junctionDAO.create(Seq(testJunction1.copy(nodeNumber = Option(nodeNumber)))).head
      junctionPointDAO.create(Seq(testJunctionPoint1.copy(junctionId = junctionId1, roadwayPointId = roadwayPointId1)))
      val junctionId2 = junctionDAO.create(Seq(testJunction1.copy(nodeNumber = Option(nodeNumber)))).head
      junctionPointDAO.create(Seq(testJunctionPoint1.copy(junctionId = junctionId2, roadwayPointId = roadwayPointId2)))
      val junctionId3 = junctionDAO.create(Seq(testJunction1.copy(nodeNumber = Option(nodeNumber)))).head
      junctionPointDAO.create(Seq(testJunctionPoint1.copy(junctionId = junctionId3, roadwayPointId = roadwayPointId3)))
      val junctionId4 = junctionDAO.create(Seq(testJunction1.copy(nodeNumber = Option(nodeNumber)))).head
      junctionPointDAO.create(Seq(testJunctionPoint1.copy(junctionId = junctionId4, roadwayPointId = roadwayPointId4)))
      junctionPointDAO.create(Seq(testJunctionPoint1.copy(junctionId = junctionId4, roadwayPointId = roadwayPointId5)))
      junctionPointDAO.create(Seq(testJunctionPoint1.copy(junctionId = junctionId4, roadwayPointId = roadwayPointId6)))

      val before = nodePointDAO.fetchByNodeNumbers(Seq(nodeNumber))
      nodesAndJunctionsService.calculateNodePointsForNode(nodeNumber, "TestUser")
      val after = nodePointDAO.fetchByNodeNumbers(Seq(nodeNumber))
      before.map(_.id).toSet should not be after.map(_.id).toSet
      val newNodePoints = nodePointDAO.fetchCalculatedNodePointsForNodeNumber(nodeNumber)
      newNodePoints.size should be(4)
      newNodePoints(0).addrM should be(16) // (10 + 20 + 20) / 3 from roadwayNumber 1 and roadwayNumber 2 and from roadNumber 990
      newNodePoints(1).addrM should be(16) // (10 + 20 + 20) / 3 from roadwayNumber 1 and roadwayNumber 2 and from roadNumber 990
      newNodePoints(2).addrM should be(33) // (20 + 30 + 50) / 3 from roadwayNumber 3 and from roadNumber 991
      newNodePoints(3).addrM should be(33) // (20 + 30 + 50) / 3 from roadwayNumber 3 and from roadNumber 991
    }
  }

  test("Test calculateNodePointsForNode When two track road part has junction points on both tracks. Powerpoint slide 4 and two sequential roadways 'road node points' Then calculate node point") {
    runWithRollback {
      val nodeNumber = nodeDAO.create(Seq(testNode1)).head
      val roadwayNumber1 = Sequences.nextRoadwayNumber
      val roadwayNumber2 = Sequences.nextRoadwayNumber
      val roadwayNumber3 = Sequences.nextRoadwayNumber
      val roadwayNumber4 = Sequences.nextRoadwayNumber
      roadwayDAO.create(Seq(testRoadway1.copy(roadwayNumber = roadwayNumber1, track = Track.LeftSide)))
      roadwayDAO.create(Seq(testRoadway1.copy(roadwayNumber = roadwayNumber2, track = Track.RightSide)))
      roadwayDAO.create(Seq(testRoadway1.copy(roadwayNumber = roadwayNumber3, roadPart = RoadPart(991,testRoadway1.roadPart.partNumber), track = Track.Combined)))
      roadwayDAO.create(Seq(testRoadway1.copy(roadwayNumber = roadwayNumber4, roadPart = RoadPart(991,testRoadway1.roadPart.partNumber), track = Track.Combined, addrMRange = AddrMRange(100,200))))
      val roadwayPointId1 = roadwayPointDAO.create(testRoadwayPoint1.copy(roadwayNumber = roadwayNumber1, addrMValue = 10))
      val roadwayPointId2 = roadwayPointDAO.create(testRoadwayPoint2.copy(roadwayNumber = roadwayNumber2, addrMValue = 20))
      val roadwayPointId3 = roadwayPointDAO.create(testRoadwayPoint2.copy(roadwayNumber = roadwayNumber1, addrMValue = 20))
      val roadwayPointId4 = roadwayPointDAO.create(testRoadwayPoint2.copy(roadwayNumber = roadwayNumber3, addrMValue = 20))
      val roadwayPointId5 = roadwayPointDAO.create(testRoadwayPoint2.copy(roadwayNumber = roadwayNumber4, addrMValue = 130))
      val roadwayPointId6 = roadwayPointDAO.create(testRoadwayPoint2.copy(roadwayNumber = roadwayNumber4, addrMValue = 150))
      val junctionId1 = junctionDAO.create(Seq(testJunction1.copy(nodeNumber = Option(nodeNumber)))).head
      junctionPointDAO.create(Seq(testJunctionPoint1.copy(junctionId = junctionId1, roadwayPointId = roadwayPointId1)))
      val junctionId2 = junctionDAO.create(Seq(testJunction1.copy(nodeNumber = Option(nodeNumber)))).head
      junctionPointDAO.create(Seq(testJunctionPoint1.copy(junctionId = junctionId2, roadwayPointId = roadwayPointId2)))
      val junctionId3 = junctionDAO.create(Seq(testJunction1.copy(nodeNumber = Option(nodeNumber)))).head
      junctionPointDAO.create(Seq(testJunctionPoint1.copy(junctionId = junctionId3, roadwayPointId = roadwayPointId3)))
      val junctionId4 = junctionDAO.create(Seq(testJunction1.copy(nodeNumber = Option(nodeNumber)))).head
      junctionPointDAO.create(Seq(testJunctionPoint1.copy(junctionId = junctionId4, roadwayPointId = roadwayPointId4)))
      junctionPointDAO.create(Seq(testJunctionPoint1.copy(junctionId = junctionId4, roadwayPointId = roadwayPointId5)))
      junctionPointDAO.create(Seq(testJunctionPoint1.copy(junctionId = junctionId4, roadwayPointId = roadwayPointId6)))

      val before = nodePointDAO.fetchByNodeNumbers(Seq(nodeNumber))
      nodesAndJunctionsService.calculateNodePointsForNode(nodeNumber, "TestUser")
      val after = nodePointDAO.fetchByNodeNumbers(Seq(nodeNumber))
      before.map(_.id).toSet should not be after.map(_.id).toSet
      val newNodePoints = nodePointDAO.fetchCalculatedNodePointsForNodeNumber(nodeNumber)
      newNodePoints.size should be(4)
      newNodePoints(0).addrM should be(16) // (10 + 20 + 20) / 3 from roadwayNumber 1 and roadwayNumber 2 and from roadNumber 990
      newNodePoints(1).addrM should be(16) // (10 + 20 + 20) / 3 from roadwayNumber 1 and roadwayNumber 2 and from roadNumber 990
      newNodePoints(2).addrM should be(100) // (20 + 130 + 150) / 3 from roadwayNumber 3 and from roadNumber 991
      newNodePoints(3).addrM should be(100) // (20 + 130 + 150) / 3 from roadwayNumber 4 and from roadNumber 991
    }
  }

  test("Test calculateNodePointsForNode When two track road part has junction points on both tracks. Powerpoint slide 6 Then calculate node point") {
    runWithRollback {
      val nodeNumber = nodeDAO.create(Seq(testNode1)).head
      val roadwayNumber1 = Sequences.nextRoadwayNumber
      val roadwayNumber2 = Sequences.nextRoadwayNumber
      val roadwayNumber3 = Sequences.nextRoadwayNumber
      val roadwayNumber4 = Sequences.nextRoadwayNumber
      roadwayDAO.create(Seq(testRoadway1.copy(roadwayNumber = roadwayNumber1, track = Track.LeftSide)))
      roadwayDAO.create(Seq(testRoadway1.copy(roadwayNumber = roadwayNumber2, track = Track.RightSide)))
      roadwayDAO.create(Seq(testRoadway1.copy(roadwayNumber = roadwayNumber3, roadPart = RoadPart(991, testRoadway1.roadPart.partNumber), track = Track.Combined)))
      roadwayDAO.create(Seq(testRoadway1.copy(roadwayNumber = roadwayNumber4, roadPart = RoadPart(991, 2), track = Track.Combined, addrMRange = AddrMRange(100,200))))
      val roadwayPointId1 = roadwayPointDAO.create(testRoadwayPoint1.copy(roadwayNumber = roadwayNumber1, addrMValue = 10))
      val roadwayPointId2 = roadwayPointDAO.create(testRoadwayPoint2.copy(roadwayNumber = roadwayNumber2, addrMValue = 20))
      val roadwayPointId3 = roadwayPointDAO.create(testRoadwayPoint2.copy(roadwayNumber = roadwayNumber1, addrMValue = 20))
      val roadwayPointId4 = roadwayPointDAO.create(testRoadwayPoint2.copy(roadwayNumber = roadwayNumber3, addrMValue = 20))
      val roadwayPointId5 = roadwayPointDAO.create(testRoadwayPoint2.copy(roadwayNumber = roadwayNumber4, addrMValue = 130))
      val roadwayPointId6 = roadwayPointDAO.create(testRoadwayPoint2.copy(roadwayNumber = roadwayNumber4, addrMValue = 150))
      val junctionId1 = junctionDAO.create(Seq(testJunction1.copy(nodeNumber = Option(nodeNumber)))).head
      junctionPointDAO.create(Seq(testJunctionPoint1.copy(junctionId = junctionId1, roadwayPointId = roadwayPointId1)))
      val junctionId2 = junctionDAO.create(Seq(testJunction1.copy(nodeNumber = Option(nodeNumber)))).head
      junctionPointDAO.create(Seq(testJunctionPoint1.copy(junctionId = junctionId2, roadwayPointId = roadwayPointId2)))
      val junctionId3 = junctionDAO.create(Seq(testJunction1.copy(nodeNumber = Option(nodeNumber)))).head
      junctionPointDAO.create(Seq(testJunctionPoint1.copy(junctionId = junctionId3, roadwayPointId = roadwayPointId3)))
      val junctionId4 = junctionDAO.create(Seq(testJunction1.copy(nodeNumber = Option(nodeNumber)))).head
      junctionPointDAO.create(Seq(testJunctionPoint1.copy(junctionId = junctionId4, roadwayPointId = roadwayPointId4)))
      junctionPointDAO.create(Seq(testJunctionPoint1.copy(junctionId = junctionId4, roadwayPointId = roadwayPointId5)))
      junctionPointDAO.create(Seq(testJunctionPoint1.copy(junctionId = junctionId4, roadwayPointId = roadwayPointId6)))

      val before = nodePointDAO.fetchByNodeNumbers(Seq(nodeNumber))
      nodesAndJunctionsService.calculateNodePointsForNode(nodeNumber, "TestUser")
      val after = nodePointDAO.fetchByNodeNumbers(Seq(nodeNumber))
      before.map(_.id).toSet should not be after.map(_.id).toSet
      val newNodePoints = nodePointDAO.fetchCalculatedNodePointsForNodeNumber(nodeNumber)
      newNodePoints.size should be(6)
      newNodePoints(0).addrM should be(16) // (10 + 20 + 20) / 3 from roadwayNumber 1 and roadwayNumber 2 and from roadNumber 990
      newNodePoints(1).addrM should be(16) // (10 + 20 + 20) / 3 from roadwayNumber 1 and roadwayNumber 2 and from roadNumber 990
      newNodePoints(2).addrM should be(20) // (20 ) / 1 from roadwayNumber 3 and from roadNumber 991 and from roadPart 1
      newNodePoints(3).addrM should be(20) // (20) / 1 from roadwayNumber 3 and from roadNumber 991 and from roadPart 1
      newNodePoints(4).addrM should be(140) // (130 + 150) / 2 from roadwayNumber 4 and from roadNumber 991 and from roadPart 1
      newNodePoints(5).addrM should be(140) // (130 + 150) / 2 from roadwayNumber 4 and from roadNumber 991 and from roadPart 1
    }
  }

  test("Test calculateNodePointsForNode When two track road part has junction points on both tracks. Powerpoint slide 6 and roadwaypoint at end of roadway Then calculate node point. Include expired data") {
    runWithRollback {
      val nodeNumber = nodeDAO.create(Seq(testNode1)).head
      val roadwayNumber3 = Sequences.nextRoadwayNumber
      val roadwayNumber5 = Sequences.nextRoadwayNumber
      roadwayDAO.create(Seq(testRoadway1.copy(roadwayNumber = roadwayNumber3, roadPart = RoadPart(991,testRoadway1.roadPart.partNumber), track = Track.Combined)))
      val roadwayNumber4 = roadwayDAO.create(Seq(testRoadway1.copy(roadwayNumber = roadwayNumber5, roadPart = RoadPart(991,testRoadway1.roadPart.partNumber), track = Track.Combined, addrMRange = AddrMRange(100,200))))
      roadwayDAO.expireById(roadwayNumber4.toSet)
      roadwayDAO.create(Seq(testRoadway1.copy(roadwayNumber = roadwayNumber5, roadPart = RoadPart(991,testRoadway1.roadPart.partNumber), track = Track.Combined, addrMRange = AddrMRange(100,200))))
      val roadwayPointId4 = roadwayPointDAO.create(testRoadwayPoint2.copy(roadwayNumber = roadwayNumber3, addrMValue = 20))
      val roadwayPointId5 = roadwayPointDAO.create(testRoadwayPoint2.copy(roadwayNumber = roadwayNumber5, addrMValue = 180))
      val junctionId4 = junctionDAO.create(Seq(testJunction1.copy(nodeNumber = Option(nodeNumber)))).head
      val junctionId5 = junctionDAO.create(Seq(testJunction1.copy(nodeNumber = Option(nodeNumber)))).head
      val junctionId6 = junctionDAO.create(Seq(testJunction1.copy(nodeNumber = Option(nodeNumber), validTo = Option(DateTime.now())))).head
      junctionDAO.expireById(Seq(junctionId6))

      junctionPointDAO.create(Seq(testJunctionPoint1.copy(junctionId = junctionId4, roadwayPointId = roadwayPointId4)))
      junctionPointDAO.create(Seq(testJunctionPoint1.copy(junctionId = junctionId5, roadwayPointId = roadwayPointId5)))
      val junctionPointDAO3 = junctionPointDAO.create(Seq(testJunctionPoint1.copy(junctionId = junctionId4, roadwayPointId = roadwayPointId4)))
      junctionPointDAO.expireById(junctionPointDAO3)

      val before = nodePointDAO.fetchByNodeNumbers(Seq(nodeNumber))
      nodesAndJunctionsService.calculateNodePointsForNode(nodeNumber, "TestUser")
      val after = nodePointDAO.fetchByNodeNumbers(Seq(nodeNumber))
      before.map(_.id).toSet should not be after.map(_.id).toSet
      val newNodePoints = nodePointDAO.fetchCalculatedNodePointsForNodeNumber(nodeNumber)
      newNodePoints.size should be(2)
      newNodePoints(0).addrM should be(100) // (20 + 180) / 2
      newNodePoints(1).addrM should be(100) // (20 + 180) / 2
      newNodePoints(0).roadwayPointId should not be newNodePoints(1).roadwayPointId
    }
  }

  test("Test calculateNodePointsForNode When there is a road with 2 roadparts, 2 junctions and the roadpart changes on a junction. The section between the junctions is transfered and NodePoints should update accordingly without dublicate NodePoints or templates") {
    runWithRollback {

      /*
       * In this test there are 3 roads created:
       *  1. The mainroad (vertical road in the picture below)
       *  2. 2 crossing roads to create 2 junctions on the mainroad
       * Each of the 3 sections of the mainroad consists of 1 linearlocation, 1 link, but before the projectchanges the 2 sections of roadpartNumber: 2 have the same roadwayNumber
       *
       * In the project the middle section of the mainroad is transfered from roadpart 2 to roadpart 1.
       *
       * Illustration of the node points before and after the project
       * R = RoadNodePoint
       * C = CalculatedNodePoint
       * o = junction
       *
       *       1    |      2    |    2
       *            R           R
       *    R-----R|o|R-------C|o|C------>R
       *
       *
       *      1     |    1     |    2
       *            R          R
       *    R-----C|o|C------R|o|R------>R
       *
       * Illustration of the roadway points before and after the project
       * RoadawayPoints
       * x = RoadwayPoint
       * o = junction
       *
       * Before changes:
       *       1   |     2     |     2
       *           x           x
       *    -----x|o|x--------|o|x------>x
       * (Junction in the middle of roadpart-2 has only 1 roadwayPoint since roadpart does not change)
       *
       * After changes:
       *        1   |     1     |   2
       *            x           x
       *    x-----x|o|x-------x|o|x------>x
       */

      val mainroad = 1L
      val crossingroad1 = 2L
      val crossingroad2 = 3L
      val projectId = Sequences.nextViiteProjectId

      val rwId1 = Sequences.nextRoadwayId
      val rwId2 = Sequences.nextRoadwayId
      val rwId3 = Sequences.nextRoadwayId
      val rwId4 = Sequences.nextRoadwayId
      val rwId5 = Sequences.nextRoadwayId

      val rwNumber1 = Sequences.nextRoadwayNumber //Mainroad
      val rwNumber2 = Sequences.nextRoadwayNumber //Mainroad
      val rwNumber3 = Sequences.nextRoadwayNumber //Crossing road
      val rwNumber4 = Sequences.nextRoadwayNumber //Crossing road
      val newRwNumber1 = Sequences.nextRoadwayNumber // Mainroad after projectchange
      val newRwNumber2 = Sequences.nextRoadwayNumber // Mainroad after projectchange
      val newRwNumber3 = Sequences.nextRoadwayNumber // Mainroad after projectchange

      val llId1 = Sequences.nextLinearLocationId
      val llId2 = Sequences.nextLinearLocationId
      val llId3 = Sequences.nextLinearLocationId
      val llId4 = Sequences.nextLinearLocationId
      val llId5 = Sequences.nextLinearLocationId
      val plId1 = Sequences.nextProjectLinkId
      val plId2 = Sequences.nextProjectLinkId
      val plId3 = Sequences.nextProjectLinkId
      val plId4 = Sequences.nextProjectLinkId
      val plId5 = Sequences.nextProjectLinkId

      val geom1 = Seq(Point(0.0,0.0,0.0), Point(100.0,0.0,0.0))
      val geom2 = Seq(Point(100.0, 0.0, 0.0), Point(200.0, 0.0, 0.0))
      val geom3 = Seq(Point(200.0, 0.0, 0.0), Point(300.0, 0.0, 0.0))
      val geom4 = Seq(Point(100.0, 0.0, 0.0), Point(100.0, 100.0, 0.0))
      val geom5 = Seq(Point(200.0, 0.0, 0.0), Point(200.0, 100.0, 0.0))

      val rwpId1 = Sequences.nextRoadwayPointId
      val rwpId2 = Sequences.nextRoadwayPointId
      val rwpId3 = Sequences.nextRoadwayPointId
      val rwpId4 = Sequences.nextRoadwayPointId
      val rwpId5 = Sequences.nextRoadwayPointId
      val rwpId6 = Sequences.nextRoadwayPointId
      val rwpId7 = Sequences.nextRoadwayPointId

      val nodeId1 = Sequences.nextNodeId
      val nodeId2 = Sequences.nextNodeId
      val nodeId3 = Sequences.nextNodeId
      val nodeNumber1 = Sequences.nextNodeNumber
      val nodeNumber2 = Sequences.nextNodeNumber
      val nodeNumber3 = Sequences.nextNodeNumber

      val jId1 = Sequences.nextJunctionId
      val jId2 = Sequences.nextJunctionId

      val jpId = Sequences.nextJunctionPointId
      val jpId1 = Sequences.nextJunctionPointId
      val jpId2 = Sequences.nextJunctionPointId
      val jpId3 = Sequences.nextJunctionPointId
      val jpId4 = Sequences.nextJunctionPointId
      val jpId5 = Sequences.nextJunctionPointId

      val npId1 = Sequences.nextNodePointId
      val npId2 = Sequences.nextNodePointId
      val npId3 = Sequences.nextNodePointId
      val npId4 = Sequences.nextNodePointId
      val npId5 = Sequences.nextNodePointId
      val npId6 = Sequences.nextNodePointId
      val npId7 = Sequences.nextNodePointId

      //Create roadwayPoints
      roadwayPointDAO.create(RoadwayPoint(rwpId6, rwNumber1, 0, "TestUser", Some(DateTime.parse("2019-01-01")), None, None))
      roadwayPointDAO.create(RoadwayPoint(rwpId1, rwNumber1, 100, "TestUser", Some(DateTime.parse("2019-01-01")), None, None))
      roadwayPointDAO.create(RoadwayPoint(rwpId2, rwNumber2, 0, "TestUser1", Some(DateTime.parse("2019-01-01")), None, None))
      roadwayPointDAO.create(RoadwayPoint(rwpId3, rwNumber2, 100, "TestUser", Some(DateTime.parse("2019-01-01")), None, None))
      roadwayPointDAO.create(RoadwayPoint(rwpId7, rwNumber2, 200, "TestUser", Some(DateTime.parse("2019-01-01")), None, None))
      roadwayPointDAO.create(RoadwayPoint(rwpId4, rwNumber3, 0, "TestUser", Some(DateTime.parse("2019-01-01")), None, None))
      roadwayPointDAO.create(RoadwayPoint(rwpId5, rwNumber4, 0, "TestUser", Some(DateTime.parse("2019-01-01")), None, None))

      //Create nodes
      val node1 = Node(nodeId1, nodeNumber1, Point(100.0, 0.0), Some("Solmu1"), NodeType.NormalIntersection, DateTime.parse("2019-01-01"), None, DateTime.parse("2019-01-01"), None, "TestUser", None, None, None, DateTime.parse("2019-01-01"))
      val node2 = Node(nodeId2, nodeNumber2, Point(200.0, 0.0), Some("Solmu2"), NodeType.NormalIntersection, DateTime.parse("2019-01-01"), None, DateTime.parse("2019-01-01"), None, "TestUser", None, None, None, DateTime.parse("2019-01-01"))
      val node3 = Node(nodeId3, nodeNumber3, Point(300.0, 0.0), Some("Solmu2"), NodeType.EndOfRoad, DateTime.parse("2019-01-01"), None, DateTime.parse("2019-01-01"), None, "TestUser", None, None, None, DateTime.parse("2019-01-01"))
      nodeDAO.create(Seq(node1, node2, node3))

      //Create NodePoints
      val np1 = NodePoint(npId1, BeforeAfter.Before, rwpId1, Some(nodeNumber1), NodePointType.RoadNodePoint,       Some(DateTime.parse("2019-01-01")), None, DateTime.parse("2019-01-01"), None, "TestUser", Some(DateTime.parse("2019-01-01")), rwNumber1, 100, RoadPart(1, 1), Track.Combined, 1, Point(100.0, 0.0))
      val np2 = NodePoint(npId2, BeforeAfter.After,  rwpId2, Some(nodeNumber1), NodePointType.RoadNodePoint,       Some(DateTime.parse("2019-01-01")), None, DateTime.parse("2019-01-01"), None, "TestUser", Some(DateTime.parse("2019-01-01")), rwNumber2,   0, RoadPart(1, 2), Track.Combined, 1, Point(100.0, 0.0))
      val np3 = NodePoint(npId3, BeforeAfter.Before, rwpId3, Some(nodeNumber2), NodePointType.CalculatedNodePoint, Some(DateTime.parse("2019-01-01")), None, DateTime.parse("2019-01-01"), None, "TestUser", Some(DateTime.parse("2019-01-01")), rwNumber2, 100, RoadPart(1, 2), Track.Combined, 1, Point(200.0, 0.0))
      val np4 = NodePoint(npId4, BeforeAfter.After,  rwpId3, Some(nodeNumber2), NodePointType.CalculatedNodePoint, Some(DateTime.parse("2019-01-01")), None, DateTime.parse("2019-01-01"), None, "TestUser", Some(DateTime.parse("2019-01-01")), rwNumber2, 100, RoadPart(1, 2), Track.Combined, 1, Point(200.0, 0.0))
      val np5 = NodePoint(npId5, BeforeAfter.After,  rwpId4, Some(nodeNumber1), NodePointType.RoadNodePoint,       Some(DateTime.parse("2019-01-01")), None, DateTime.parse("2019-01-01"), None, "TestUser", Some(DateTime.parse("2019-01-01")), rwNumber3,   0, RoadPart(3, 1), Track.Combined, 1, Point(100.0, 0.0)) //Tämä puuttuu
      val np6 = NodePoint(npId6, BeforeAfter.After,  rwpId5, Some(nodeNumber2), NodePointType.RoadNodePoint,       Some(DateTime.parse("2019-01-01")), None, DateTime.parse("2019-01-01"), None, "TestUser", Some(DateTime.parse("2019-01-01")), rwNumber4,   0, RoadPart(4, 1), Track.Combined, 1, Point(200.0, 0.0))
      val np7 = NodePoint(npId7, BeforeAfter.Before, rwpId7, Some(nodeNumber3), NodePointType.RoadNodePoint,       Some(DateTime.parse("2019-01-01")), None, DateTime.parse("2019-01-01"), None, "TestUser", Some(DateTime.parse("2019-01-01")), rwNumber2, 200, RoadPart(1, 2), Track.Combined, 1, Point(300.0, 0.0)) // 2. Päätien loppu
      nodePointDAO.create(List(np1, np2, np3, np4, np5, np6, np7))

      //Create junction
      val j1 = Junction(jId1, Some(1), Some(nodeNumber1), DateTime.parse("2019-01-01"), None, DateTime.parse("2019-01-01"), None, "Test", None, None)
      val j2 = Junction(jId2, Some(2), Some(nodeNumber2), DateTime.parse("2019-01-01"), None, DateTime.parse("2019-01-01"), None, "Test", None, None)
      junctionDAO.create(List(j1, j2))

      //Create junctionpoints
      val jp1 = JunctionPoint(jpId, BeforeAfter.Before,  rwpId1, jId1, Some(DateTime.parse("2019-01-01")), None, DateTime.parse("2019-01-01"), None, "TestUser", None, rwNumber1, 100, RoadPart(1, 1), Track.Combined, Discontinuity.Continuous, Point(100.0, 0.0))
      val jp2 = JunctionPoint(jpId1, BeforeAfter.After,  rwpId2, jId1, Some(DateTime.parse("2019-01-01")), None, DateTime.parse("2019-01-01"), None, "TestUser", None, rwNumber2,   0, RoadPart(1, 2), Track.Combined, Discontinuity.Continuous, Point(100.0, 0.0))
      val jp3 = JunctionPoint(jpId2, BeforeAfter.After,  rwpId4, jId1, Some(DateTime.parse("2019-01-01")), None, DateTime.parse("2019-01-01"), None, "TestUser", None, rwNumber3,   0, RoadPart(2, 1), Track.Combined, Discontinuity.EndOfRoad, Point(100.0, 0.0))
      val jp4 = JunctionPoint(jpId3, BeforeAfter.Before, rwpId3, jId2, Some(DateTime.parse("2019-01-01")), None, DateTime.parse("2019-01-01"), None, "TestUser", None, rwNumber2, 100, RoadPart(1, 2), Track.Combined, Discontinuity.Continuous, Point(200.0, 0.0))
      val jp5 = JunctionPoint(jpId4, BeforeAfter.After,  rwpId3, jId2, Some(DateTime.parse("2019-01-01")), None, DateTime.parse("2019-01-01"), None, "TestUser", None, rwNumber2, 100, RoadPart(1, 2), Track.Combined, Discontinuity.EndOfRoad, Point(200.0, 0.0))
      val jp6 = JunctionPoint(jpId5, BeforeAfter.After,  rwpId5, jId2, Some(DateTime.parse("2019-01-01")), None, DateTime.parse("2019-01-01"), None, "TestUser", None, rwNumber4,   0, RoadPart(3, 1), Track.Combined, Discontinuity.EndOfRoad, Point(200.0, 0.0))
      junctionPointDAO.create(List(jp1, jp2, jp3, jp4, jp5, jp6))

      //Mainroad
      val pl1 = dummyProjectLink(RoadPart(mainroad, 1), Track.Combined,  Discontinuity.Continuous,       AddrMRange(  0,  100), AddrMRange(  0,  100), Some(DateTime.parse("2019-01-01")), None, 12345.toString,   0,  100, SideCode.TowardsDigitizing, RoadAddressChangeType.New, projectId, AdministrativeClass.State, geom1,  rwNumber1   ).copy(id = plId1,     projectId = projectId, roadwayId = rwId1,     linearLocationId = llId1,     reversed = false)
      val pl2 = dummyProjectLink(RoadPart(mainroad, 2), Track.Combined,  Discontinuity.Continuous,       AddrMRange(  0,  100), AddrMRange(  0,  100), Some(DateTime.parse("2019-01-01")), None, 12346.toString, 100,  200, SideCode.TowardsDigitizing, RoadAddressChangeType.New, projectId, AdministrativeClass.State, geom2,  rwNumber2   ).copy(id = plId2,     projectId = projectId, roadwayId = rwId2,     linearLocationId = llId2,     reversed = false)
      val pl3 = dummyProjectLink(RoadPart(mainroad, 2), Track.Combined,  Discontinuity.EndOfRoad,        AddrMRange(100,  200), AddrMRange(100,  200), Some(DateTime.parse("2019-01-01")), None, 12347.toString, 200,  300, SideCode.TowardsDigitizing, RoadAddressChangeType.New, projectId, AdministrativeClass.State, geom3,  rwNumber2   ).copy(id = plId3,     projectId = projectId, roadwayId = rwId3,     linearLocationId = llId3,     reversed = false)

      //Crossing road
      val pl4 = dummyProjectLink(RoadPart(crossingroad1, 1), Track.Combined, Discontinuity.EndOfRoad, AddrMRange(0, 100), AddrMRange(0, 100), Some(DateTime.parse("2019-01-01")), None, 12348.toString, 0, 100, SideCode.TowardsDigitizing, RoadAddressChangeType.New, projectId, AdministrativeClass.State, geom4, rwNumber3).copy(id = plId4, projectId = projectId, roadwayId = rwId4, linearLocationId = llId4, reversed = false)
      val pl5 = dummyProjectLink(RoadPart(crossingroad2, 1), Track.Combined, Discontinuity.EndOfRoad, AddrMRange(0, 100), AddrMRange(0, 100), Some(DateTime.parse("2019-01-01")), None, 12349.toString, 0, 100, SideCode.TowardsDigitizing, RoadAddressChangeType.New, projectId, AdministrativeClass.State, geom5, rwNumber4).copy(id = plId5, projectId = projectId, roadwayId = rwId5, linearLocationId = llId5, reversed = false)
      val pLinks = Seq(pl1, pl2, pl3, pl4, pl5)

      // Creating Project
      val project = Project(projectId, ProjectState.Incomplete, "f", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
        "", Seq(), Seq(), None, None)

      // Grouping linear locations with roadways
      val (ll1, rw1): (LinearLocation, Roadway) = Seq(pl1).map(toRoadwayAndLinearLocation).head
      val (ll2, rw2): (LinearLocation, Roadway) = Seq(pl2).map(toRoadwayAndLinearLocation).head
      val (ll3, rw3): (LinearLocation, Roadway) = Seq(pl3).map(toRoadwayAndLinearLocation).head
      val (ll4, rw4): (LinearLocation, Roadway) = Seq(pl4).map(toRoadwayAndLinearLocation).head
      val (ll5, rw5): (LinearLocation, Roadway) = Seq(pl5).map(toRoadwayAndLinearLocation).head
      val mainroadRws = Seq(rw1, rw2, rw3)

      // Defining roadways and their M values
      val rw1WithId = rw1.copy(ely = 1L, addrMRange = AddrMRange(  0,100)) //1,1
      val rw2WithId = rw2.copy(ely = 1L, addrMRange = AddrMRange(  0,200)) //1,2
      val rw3WithId = rw3.copy(ely = 1L, addrMRange = AddrMRange(100,200)) //1,2
      val rw4WithId = rw4.copy(ely = 1L, addrMRange = AddrMRange(  0,100)) //2,1
      val rw5WithId = rw5.copy(ely = 1L, addrMRange = AddrMRange(  0,100)) //3,1

      // Defining linear location order numbering
      val orderedll1 = ll1.copy(orderNumber = 1)
      val orderedll2 = ll2.copy(orderNumber = 2)
      val orderedll3 = ll3.copy(orderNumber = 3)
      val orderedll4 = ll4.copy(orderNumber = 4)
      val orderedll5 = ll5.copy(orderNumber = 5)

      // Building data for project
      buildTestDataForProject(Some(project),
        Some(Seq(rw1WithId, rw2WithId, rw3WithId, rw4WithId, rw5WithId)), Some(Seq(orderedll1, orderedll2, orderedll3, orderedll4, orderedll5)), Some(pLinks))

      //Check that the nodePoints are correct before the changes
      val nodePointsBeforeChanges = nodePointDAO.fetchByNodeNumbers(Seq(nodeNumber1, nodeNumber2))
      nodePointsBeforeChanges.count(npB => npB.nodePointType == NodePointType.RoadNodePoint) should be(4)
      nodePointsBeforeChanges.count(npB => npB.nodePointType == NodePointType.CalculatedNodePoint) should be(2)

      //Creating project and project links for the changes
      val projectId2 = Sequences.nextViiteProjectId

      val newPl1 = dummyProjectLink(RoadPart(mainroad, 1), Track.Combined, Discontinuity.Continuous, AddrMRange(  0, 100), AddrMRange(  0, 100), Some(DateTime.now()), None, 12345.toString, 0, 100, SideCode.TowardsDigitizing, RoadAddressChangeType.Unchanged, projectId2, AdministrativeClass.State, geom1, newRwNumber1).copy(id = plId1 +10, projectId = projectId2, roadwayId = rwId1 +10, linearLocationId = llId1 +10, reversed = false)
      val newPl2 = dummyProjectLink(RoadPart(mainroad, 1), Track.Combined, Discontinuity.Continuous, AddrMRange(100, 200), AddrMRange(  0, 100), Some(DateTime.now()), None, 12346.toString, 100, 200, SideCode.TowardsDigitizing, RoadAddressChangeType.Transfer, projectId2, AdministrativeClass.State, geom2, newRwNumber2).copy(id = plId1 + 11, projectId = projectId2, roadwayId = rwId1 + 11, linearLocationId = llId1 + 11, reversed = false)
      val newPl3 = dummyProjectLink(RoadPart(mainroad, 2), Track.Combined, Discontinuity.EndOfRoad,  AddrMRange(  0, 100), AddrMRange(100, 200), Some(DateTime.now()), None, 12347.toString, 200, 300, SideCode.TowardsDigitizing, RoadAddressChangeType.Transfer, projectId2, AdministrativeClass.State, geom3, newRwNumber3).copy(id = plId1 + 12, projectId = projectId2, roadwayId = rwId1 + 12, linearLocationId = llId1 + 12, reversed = false)
      val pLinks2 = Seq(newPl1, newPl2, newPl3)

      // Creating Project
      val project2 = Project(projectId2, ProjectState.Incomplete, "f2", "s2", DateTime.now(), "", DateTime.now(), DateTime.now(),
        "", Seq(), Seq(), None, None)

      // Grouping linear locations with roadways
      val (newll1, newRw1): (LinearLocation, Roadway) = Seq(newPl1).map(toRoadwayAndLinearLocation).head
      val (newll2, newRw2): (LinearLocation, Roadway) = Seq(newPl2).map(toRoadwayAndLinearLocation).head
      val (newll3, newRw3): (LinearLocation, Roadway) = Seq(newPl3).map(toRoadwayAndLinearLocation).head

      // Defining roadways and their M values
      val newRoadway1WithId = newRw1.copy(ely = 1L, addrMRange = AddrMRange(  0, 100)) //1,1
      val newRoadway2WithId = newRw2.copy(ely = 1L, addrMRange = AddrMRange(100, 200)) //1,1
      val newRoadway3WithId = newRw3.copy(ely = 1L, addrMRange = AddrMRange(  0, 100)) //1,2
      val newRwNumbers = Seq(newRoadway1WithId.roadwayNumber, newRoadway2WithId.roadwayNumber, newRoadway3WithId.roadwayNumber)
      // Defining linear location order numbering
      val newOrderedll1 = newll1.copy(orderNumber = 1)
      val newOrderedll2 = newll2.copy(orderNumber = 2)
      val newOrderedll3 = newll3.copy(orderNumber = 3)

      linearLocationDAO.expireByRoadwayNumbers(mainroadRws.map(_.roadwayNumber).toSet)
      roadwayDAO.expireHistory(mainroadRws.map(_.id).toSet)

      buildTestDataForProject(Some(project2),
        Some(Seq(newRoadway1WithId, newRoadway2WithId, newRoadway3WithId)), Some(Seq(newOrderedll1, newOrderedll2, newOrderedll3)), Some(pLinks2))

      val roadAddress1 = RoadAddress(newRoadway1WithId.id, newOrderedll1.id, newRoadway1WithId.roadPart, newRoadway1WithId.administrativeClass, newRoadway1WithId.track, newRoadway1WithId.discontinuity, AddrMRange(newRoadway1WithId.addrMRange.start, newRoadway1WithId.addrMRange.end), Some(newRoadway1WithId.startDate), Some(newRoadway1WithId.startDate), Some(newRoadway1WithId.createdBy), newPl1.linkId, newPl1.startMValue, newPl1.endMValue, newPl1.sideCode, newll1.adjustedTimestamp, (None, None), newPl1.geometry, LinkGeomSource.NormalLinkInterface, newRoadway1WithId.ely, newRoadway1WithId.terminated, newRoadway1WithId.roadwayNumber, Some(newRoadway1WithId.validFrom), newRoadway1WithId.validTo, newRoadway1WithId.roadName)
      val roadAddress2 = RoadAddress(newRoadway2WithId.id, newOrderedll2.id, newRoadway2WithId.roadPart, newRoadway2WithId.administrativeClass, newRoadway2WithId.track, newRoadway2WithId.discontinuity, AddrMRange(newRoadway2WithId.addrMRange.start, newRoadway2WithId.addrMRange.end), Some(newRoadway2WithId.startDate), Some(newRoadway2WithId.startDate), Some(newRoadway2WithId.createdBy), newPl2.linkId, newPl2.startMValue, newPl2.endMValue, newPl2.sideCode, newll2.adjustedTimestamp, (None, None), newPl2.geometry, LinkGeomSource.NormalLinkInterface, newRoadway2WithId.ely, newRoadway2WithId.terminated, newRoadway2WithId.roadwayNumber, Some(newRoadway2WithId.validFrom), newRoadway2WithId.validTo, newRoadway2WithId.roadName)
      val roadAddress3 = RoadAddress(newRoadway3WithId.id, newOrderedll3.id, newRoadway3WithId.roadPart, newRoadway3WithId.administrativeClass, newRoadway3WithId.track, newRoadway3WithId.discontinuity, AddrMRange(newRoadway3WithId.addrMRange.start, newRoadway3WithId.addrMRange.end), Some(newRoadway3WithId.startDate), Some(newRoadway3WithId.startDate), Some(newRoadway3WithId.createdBy), newPl3.linkId, newPl3.startMValue, newPl3.endMValue, newPl3.sideCode, newll3.adjustedTimestamp, (None, None), newPl3.geometry, LinkGeomSource.NormalLinkInterface, newRoadway3WithId.ely, newRoadway3WithId.terminated, newRoadway3WithId.roadwayNumber, Some(newRoadway3WithId.validFrom), newRoadway3WithId.validTo, newRoadway3WithId.roadName)
      val rwaddressCross1 = RoadAddress(rw4WithId.id, orderedll4.id, rw4WithId.roadPart, rw4WithId.administrativeClass, rw4WithId.track, rw4WithId.discontinuity, AddrMRange(rw4WithId.addrMRange.start, rw4WithId.addrMRange.end), Some(rw4WithId.startDate), Some(rw4WithId.startDate), Some(rw4WithId.createdBy), pl4.linkId, pl4.startMValue, pl4.endMValue, pl4.sideCode, orderedll4.adjustedTimestamp, (None, None), pl4.geometry, LinkGeomSource.NormalLinkInterface, rw4WithId.ely, rw4WithId.terminated, rw4WithId.roadwayNumber, Some(rw4WithId.validFrom), rw4WithId.validTo, rw4WithId.roadName)
      val rwaddressCross2 = RoadAddress(rw5WithId.id, orderedll5.id, rw5WithId.roadPart, rw5WithId.administrativeClass, rw5WithId.track, rw5WithId.discontinuity, AddrMRange(rw5WithId.addrMRange.start, rw5WithId.addrMRange.end), Some(rw5WithId.startDate), Some(rw5WithId.startDate), Some(rw5WithId.createdBy), pl5.linkId, pl5.startMValue, pl5.endMValue, pl5.sideCode, orderedll5.adjustedTimestamp, (None, None), pl5.geometry, LinkGeomSource.NormalLinkInterface, rw5WithId.ely, rw5WithId.terminated, rw5WithId.roadwayNumber, Some(rw5WithId.validFrom), rw5WithId.validTo, rw5WithId.roadName)

      val projectChanges = List(
        ProjectRoadwayChange(projectId2, Some("test project"), 1L, "TestUser", DateTime.now,
          RoadwayChangeInfo(RoadAddressChangeType.Unchanged,
            RoadwayChangeSection(Some(1), Some(0), Some(1), Some(1), Some(AddrMRange(0,100)), Some(AdministrativeClass.State), Some(Discontinuity.Continuous), Some(1)),
            RoadwayChangeSection(Some(1), Some(0), Some(1), Some(1), Some(AddrMRange(0,100)), Some(AdministrativeClass.State), Some(Discontinuity.Continuous), Some(1)),
            Discontinuity.Continuous, AdministrativeClass.State, reversed = false, 1, 1)
          , DateTime.now),

        ProjectRoadwayChange(projectId2, Some("test project"), 1L, "TestUser", DateTime.now,
          RoadwayChangeInfo(RoadAddressChangeType.Transfer,
            RoadwayChangeSection(Some(1), Some(0), Some(2), Some(2), Some(AddrMRange(  0,100)), Some(AdministrativeClass.State), Some(Discontinuity.Continuous), Some(1)),
            RoadwayChangeSection(Some(1), Some(0), Some(1), Some(1), Some(AddrMRange(100,200)), Some(AdministrativeClass.State), Some(Discontinuity.Continuous), Some(1)),
            Discontinuity.Continuous, AdministrativeClass.State, reversed = false, 2, 1)
          , DateTime.now),

        ProjectRoadwayChange(projectId2, Some("test project"), 1L, "TestUser", DateTime.now,
          RoadwayChangeInfo(RoadAddressChangeType.Transfer,
            RoadwayChangeSection(Some(1), Some(0), Some(2), Some(2), Some(AddrMRange(100, 200)), Some(AdministrativeClass.State), Some(Discontinuity.EndOfRoad), Some(1)),
            RoadwayChangeSection(Some(1), Some(0), Some(2), Some(2), Some(AddrMRange(  0, 100)), Some(AdministrativeClass.State), Some(Discontinuity.EndOfRoad), Some(1)),
            Discontinuity.EndOfRoad, AdministrativeClass.State, reversed = false, 3, 1)
          , DateTime.now)
      )

      val mappedReservedRoadwayNumbers = projectLinkDAO.fetchProjectLinksChange(projectId2)

      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(newPl1.startingPoint, newPl1.startingPoint), roadNumberLimits)).thenReturn(Seq(newll1))
      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(newPl1.endPoint, newPl1.endPoint), roadNumberLimits)).thenReturn(Seq(newll1, newll2, ll4))
      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(newPl2.endPoint, newPl2.endPoint), roadNumberLimits)).thenReturn(Seq(newll2, newll3, ll5))
      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(newPl3.endPoint, newPl3.endPoint), roadNumberLimits)).thenReturn(Seq(newll3))

      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(Set(roadAddress1.roadwayNumber))).thenReturn(Seq(newRoadway1WithId))
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(Set(roadAddress1.roadwayNumber, roadAddress2.roadwayNumber, rwaddressCross1.roadwayNumber))).thenReturn(Seq(newRoadway1WithId, newRoadway2WithId, rw4WithId))
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(Set(roadAddress2.roadwayNumber, roadAddress3.roadwayNumber, rwaddressCross2.roadwayNumber))).thenReturn(Seq(newRoadway2WithId, newRoadway3WithId, rw5WithId))
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(Set(roadAddress3.roadwayNumber))).thenReturn(Seq(newRoadway3WithId))

      roadAddressService.handleRoadwayPointsUpdate(projectChanges, mappedReservedRoadwayNumbers, "TestUser")
      nodesAndJunctionsService.handleJunctionAndJunctionPoints(projectChanges, pLinks2, mappedReservedRoadwayNumbers, "TestUser")
      nodesAndJunctionsService.handleNodePoints(projectChanges, pLinks2, mappedReservedRoadwayNumbers)
      nodesAndJunctionsService.calculateNodePointsForNodes(Seq(nodeNumber1, nodeNumber2), "TestUser")

      val newRoadwayPoints = roadwayPointDAO.fetchByRoadwayNumbers(newRwNumbers)
      val newRoadwayPointIds: Seq[Long] = newRoadwayPoints.map(_.id)
      val nodePointsInNotRoadPartChange = nodePointDAO.fetchByNodeNumber(nodeNumber1)
      val nodePointsInRoadPartChange = nodePointDAO.fetchByNodeNumber(nodeNumber2)
      val templatesInMiddleNodes = nodePointDAO.fetchTemplatesByRoadwayNumber(newRw2.roadwayNumber)

      nodePointsInNotRoadPartChange.count(np => np.nodePointType == NodePointType.RoadNodePoint) should be(1) // The first node should have only crossing road's RoadNodePoint
      nodePointsInNotRoadPartChange.count(np => np.nodePointType == NodePointType.CalculatedNodePoint) should be(2) // The first node should have 2 CalculatedNodePoints on the mainroad
      nodePointsInRoadPartChange.count(np => np.nodePointType == NodePointType.RoadNodePoint) should be(3) // The second node should have 3 RoadNodePoints on the mainroad where the roadparts change
      templatesInMiddleNodes.size should be(0) // There shouldn't be any templates in the 2 nodes
    }
  }
}

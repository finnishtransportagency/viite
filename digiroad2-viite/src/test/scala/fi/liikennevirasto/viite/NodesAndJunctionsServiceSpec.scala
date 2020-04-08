package fi.liikennevirasto.viite

import java.sql.Date

import fi.liikennevirasto.digiroad2.asset.{BoundingRectangle, LinkGeomSource, SideCode}
import fi.liikennevirasto.digiroad2.dao.Sequences
import fi.liikennevirasto.digiroad2.oracle.OracleDatabase
import fi.liikennevirasto.digiroad2.service.RoadLinkService
import fi.liikennevirasto.digiroad2.util.Track
import fi.liikennevirasto.digiroad2.{DigiroadEventBus, Point}
import fi.liikennevirasto.viite.RoadType.PublicRoad
import fi.liikennevirasto.viite.dao.CalibrationPointDAO.CalibrationPointLocation
import fi.liikennevirasto.viite.dao.{BeforeAfter, _}
import fi.liikennevirasto.viite.process.RoadwayAddressMapper
import fi.liikennevirasto.viite.util.CalibrationPointsUtils
import org.joda.time.DateTime
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.when
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{BeforeAndAfter, FunSuite, Matchers}
import slick.driver.JdbcDriver.backend.Database
import slick.driver.JdbcDriver.backend.Database.dynamicSession
import slick.jdbc.StaticQuery.interpolation

import scala.util.{Left, Right}

class NodesAndJunctionsServiceSpec extends FunSuite with Matchers with BeforeAndAfter {

  val mockNodesAndJunctionsService = MockitoSugar.mock[NodesAndJunctionsService]
  private val roadNumber1 = 990
  private val roadwayNumber1 = 1000000000l
  private val roadwayNumber2 = 1000000002l
  private val roadPartNumber1 = 1
  val roadNumberLimits = Seq((RoadClass.forJunctions.start, RoadClass.forJunctions.end))
  val mockLinearLocationDAO = MockitoSugar.mock[LinearLocationDAO]
  val mockRoadwayDAO = MockitoSugar.mock[RoadwayDAO]
  val mockRoadwayChangesDAO = MockitoSugar.mock[RoadwayChangesDAO]
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
  val roadAddressService: RoadAddressService = new RoadAddressService(mockRoadLinkService, new RoadwayDAO, new LinearLocationDAO, new RoadNetworkDAO, roadwayPointDAO, nodePointDAO, junctionPointDAO, mockRoadwayAddressMapper, mockEventBus) {
    override def withDynSession[T](f: => T): T = f

    override def withDynTransaction[T](f: => T): T = f
  }

  val nodesAndJunctionsService = new NodesAndJunctionsService(mockRoadwayDAO, roadwayPointDAO, mockLinearLocationDAO, nodeDAO, nodePointDAO, junctionDAO, junctionPointDAO, roadwayChangesDAO) {
    override def withDynSession[T](f: => T): T = f

    override def withDynTransaction[T](f: => T): T = f

    override def withDynTransactionNewOrExisting[T](f: => T): T = f
  }

  val projectService: ProjectService = new ProjectService(roadAddressService, mockRoadLinkService, nodesAndJunctionsService, roadwayDAO,
    roadwayPointDAO, linearLocationDAO, projectDAO, projectLinkDAO,
    nodeDAO, nodePointDAO, junctionPointDAO, projectReservedPartDAO, roadwayChangesDAO,
    roadwayAddressMapper, mockEventBus) {
    override def withDynSession[T](f: => T): T = f

    override def withDynTransaction[T](f: => T): T = f
  }

  def runWithRollback(f: => Unit): Unit = {
    Database.forDataSource(OracleDatabase.ds).withDynTransaction {
      f
      dynamicSession.rollback()
    }
  }

  def withDynTransaction[T](f: => T): T = OracleDatabase.withDynTransaction(f)

  val testRoadway1 = Roadway(NewIdValue, roadwayNumber1, roadNumber1, roadPartNumber1, RoadType.PublicRoad, Track.Combined, Discontinuity.Continuous,
    0, 100, reversed = false, DateTime.parse("2000-01-01"), None, "test", Some("TEST ROAD 1"), 1, TerminationCode.NoTermination)

  val testNode1 = Node(NewIdValue, NewIdValue, Point(100, 100), Some("Test node"), NodeType.NormalIntersection,
    DateTime.parse("2019-01-01"), None, DateTime.parse("2019-01-01"), None, "Test", None)

  val testRoadwayPoint1 = RoadwayPoint(NewIdValue, roadwayNumber1, 0, "Test", None, None, None)
  val testRoadwayPoint2 = RoadwayPoint(NewIdValue, roadwayNumber2, 0, "Test", None, None, None)

  val testNodePoint1 = NodePoint(NewIdValue, BeforeAfter.Before, -1, None, NodePointType.UnknownNodePointType, Some(testNode1.startDate), testNode1.endDate,
    DateTime.parse("2019-01-01"), None, "Test", None, 0, 0, 0, 0, Track.Combined, 0)

  val testJunction1 = Junction(NewIdValue, None, None, DateTime.parse("2019-01-01"), None,
    DateTime.parse("2019-01-01"), None, "Test", None)

  val testJunctionPoint1 = JunctionPoint(NewIdValue, BeforeAfter.Before, -1, -1, Some(testJunction1.startDate), testJunction1.endDate,
    DateTime.parse("2019-01-01"), None, "Test", None, -1, 10, 0, 0, Track.Combined, Discontinuity.Continuous)

  val testLinearLocation1 = LinearLocation(NewIdValue, 1, 1000l, 0.0, 2.8, SideCode.TowardsDigitizing, 10000000000l,
    (CalibrationPointReference.None, CalibrationPointReference.None), Seq(Point(99.0, 99.0), Point(101.0, 101.0)), LinkGeomSource.NormalLinkInterface, -1)

  def dummyProjectLink(roadNumber: Long, roadPartNumber: Long, trackCode: Track, discontinuityType: Discontinuity, startAddrM: Long, endAddrM: Long, originalStartAddr: Long = 0, originalEndAddr: Long = 0, startDate: Option[DateTime], endDate: Option[DateTime] = None, linkId: Long = 0, startMValue: Double = 0,
                       endMValue: Double = 0, sideCode: SideCode = SideCode.Unknown, status: LinkStatus, projectId: Long = 0, roadType: RoadType = RoadType.PublicRoad, geometry: Seq[Point] = Seq(), roadwayNumber: Long) = {
    ProjectLink(0L, roadNumber, roadPartNumber, trackCode, discontinuityType, startAddrM, endAddrM, originalStartAddr, originalEndAddr, startDate, endDate,
      Some("user"), linkId, startMValue, endMValue, sideCode, (None, None), geometry, projectId,
      status, roadType, LinkGeomSource.NormalLinkInterface, geometryLength = 0.0, roadwayId = 0, linearLocationId = 0, ely = 8, reversed = false, None, linkGeometryTimeStamp = 0, roadwayNumber)
  }

  def buildTestDataForProject(project: Option[Project], rws: Option[Seq[Roadway]], lil: Option[Seq[LinearLocation]], pls: Option[Seq[ProjectLink]]): Unit = {
    if (rws.nonEmpty)
      roadwayDAO.create(rws.get)
    if (lil.nonEmpty)
      linearLocationDAO.create(lil.get, "user")
    if (project.nonEmpty)
      projectDAO.create(project.get)
    if (pls.nonEmpty) {
      if (project.nonEmpty) {
        val roadParts = pls.get.groupBy(pl => (pl.roadNumber, pl.roadPartNumber)).keys
        roadParts.foreach(rp => projectReservedPartDAO.reserveRoadPart(project.get.id, rp._1, rp._2, "user"))
        projectLinkDAO.create(pls.get.map(_.copy(projectId = project.get.id)))
      } else {
        projectLinkDAO.create(pls.get)
      }
    }
  }

  def toRoadwayAndLinearLocation(p: ProjectLink): (LinearLocation, Roadway) = {
    def calibrationPoint(cp: Option[ProjectLinkCalibrationPoint]): Option[Long] = {
      cp match {
        case Some(x) =>
          Some(x.addressMValue)
        case _ => Option.empty[Long]
      }
    }

    val startDate = p.startDate.getOrElse(DateTime.now()).minusDays(1)

    (LinearLocation(p.linearLocationId, 1, p.linkId, p.startMValue, p.endMValue, p.sideCode, p.linkGeometryTimeStamp,
      (CalibrationPointsUtils.toCalibrationPointReference(p.startCalibrationPoint),
        CalibrationPointsUtils.toCalibrationPointReference(p.endCalibrationPoint)),
      p.geometry, p.linkGeomSource, p.roadwayNumber, Some(startDate), p.endDate),
      Roadway(p.roadwayId, p.roadwayNumber, p.roadNumber, p.roadPartNumber, p.roadType, p.track, p.discontinuity, p.startAddrMValue, p.endAddrMValue, p.reversed, startDate, p.endDate,
        p.createdBy.getOrElse("-"), p.roadName, p.ely, TerminationCode.NoTermination, DateTime.now(), None))
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
        case Right(nodes) => {
          nodes.size should be(1)
          val node = nodes.head
          node._1.name should be(Some("Test node"))
          node._1.coordinates should be(Point(100, 100))
          node._2.roadNumber should be(roadNumber1)
          node._2.roadPartNumber should be(roadPartNumber1)
        }
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
      val roadway = Roadway(rwId, roadwayNumber, road999, part, RoadType.PublicRoad, Track.Combined, Discontinuity.Discontinuous, 0L, 10L, reversed = false, DateTime.now, None, "user", None, 8L, TerminationCode.NoTermination, DateTime.now, None)
      val roadway2 = Roadway(rwId + 1, roadwayNumber + 1, road1000, part, RoadType.PublicRoad, Track.Combined, Discontinuity.Continuous, 0L, 20L, reversed = false, DateTime.now, None, "user", None, 8L, TerminationCode.NoTermination, DateTime.now, None)
      val linearLocationId = Sequences.nextLinearLocationId
      val linearLocation = LinearLocation(linearLocationId, 1, 12345, 0L, 10L, SideCode.TowardsDigitizing, 0L,
        calibrationPoints = (CalibrationPointReference(Some(0)), CalibrationPointReference(Some(10))),
        geom1, LinkGeomSource.NormalLinkInterface, roadwayNumber, None, None)
      val projectId = Sequences.nextViiteProjectId
      val plId1 = projectId + 1

      val project = Project(projectId, ProjectState.Incomplete, "f", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
        "", Seq(), Seq(), None, None)

      val link1 = dummyProjectLink(road1000, part, Track.Combined, Discontinuity.Continuous, 0, 11, 0, 11, Some(DateTime.now()), None, 12346, 0, 10, SideCode.TowardsDigitizing, LinkStatus.New, projectId, RoadType.PublicRoad, geom2, roadwayNumber + 1).copy(id = plId1 + 1, roadwayId = rwId + 1, linearLocationId = linearLocationId + 1)
      val link2 = dummyProjectLink(road1000, part, Track.Combined, Discontinuity.Continuous, 11, 20, 11, 20, Some(DateTime.now()), None, 12347, 0, 10, SideCode.TowardsDigitizing, LinkStatus.New, projectId, RoadType.PublicRoad, geom3, roadwayNumber + 1).copy(id = plId1 + 2, roadwayId = rwId + 1, linearLocationId = linearLocationId + 2)

      val linearLocations = Seq(
        LinearLocation(linearLocationId, 1, 12345, 0.0, 10.0, SideCode.TowardsDigitizing, 10000000000l,
          (CalibrationPointReference(Some(0l)), CalibrationPointReference.None),
          Seq(Point(0.0, 0.0), Point(10.0, 0.0)), LinkGeomSource.ComplementaryLinkInterface,
          roadwayNumber, Some(DateTime.parse("2000-01-01")), None),
        LinearLocation(linearLocationId + 1, 1, 12346, 0.0, 11.0, SideCode.TowardsDigitizing, 10000000000l,
          (CalibrationPointReference.None, CalibrationPointReference.None),
          Seq(Point(0.0, 0.0), Point(11.0, 0.0)), LinkGeomSource.ComplementaryLinkInterface,
          roadwayNumber + 1, Some(DateTime.parse("2000-01-01")), None),
        LinearLocation(linearLocationId + 2, 2, 12347, 0.0, 9.0, SideCode.TowardsDigitizing, 10000000000l,
          (CalibrationPointReference.None, CalibrationPointReference.None),
          Seq(Point(11.0, 0.0), Point(20.0, 0.0)), LinkGeomSource.ComplementaryLinkInterface,
          roadwayNumber + 1, Some(DateTime.parse("2000-01-01")), None)
      )
      val pls = Seq(link1, link2)
      roadwayDAO.create(Seq(roadway, roadway2))
      buildTestDataForProject(Some(project), None, Some(linearLocations), Some(pls))
      val projectChanges = List(
        ProjectRoadwayChange(0, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(AddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, None, Some(PublicRoad), Some(Discontinuity.Continuous), Some(8L)),
            RoadwayChangeSection(Some(link1.roadNumber), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(link1.roadPartNumber), endRoadPartNumber = Some(link1.roadPartNumber), startAddressM = Some(link1.startAddrMValue), endAddressM = Some(link2.endAddrMValue), Some(RoadType.PublicRoad), Some(Discontinuity.Continuous), Some(8L)),
            Discontinuity.Continuous, RoadType.PublicRoad, reversed = false, 1, 8)
          , DateTime.now, Some(0L))
      )

      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(any[BoundingRectangle], any[Seq[(Int, Int)]])).thenReturn(Seq(linearLocation))
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(any[Set[Long]], any[Boolean])).thenReturn(Seq(roadway))

      val mappedRoadwayNumbers = projectLinkDAO.fetchProjectLinksChange(projectId)
      nodesAndJunctionsService.handleJunctionTemplates(projectChanges, pls, mappedRoadwayNumbers)

      val roadJunctionPoints = junctionPointDAO.fetchByRoadwayPoint(roadway.roadwayNumber, roadway.endAddrMValue, BeforeAfter.Before)
      val junction1 = junctionDAO.fetchByIds(Seq(roadJunctionPoints.head.junctionId))

      val link1JunctionPoints = junctionPointDAO.fetchByRoadwayPoint(link1.roadwayNumber, link1.startAddrMValue, BeforeAfter.After)
      val link2JunctionPoints = junctionPointDAO.fetchByRoadwayPoint(link2.roadwayNumber, link2.startAddrMValue, BeforeAfter.After)
      val junction2 = junctionDAO.fetchByIds(Seq(link1JunctionPoints.head.junctionId))

      junction1 should be(junction2)

      val roadwayPoint1 = roadwayPointDAO.fetch(roadway.roadwayNumber, roadway.endAddrMValue)
      val roadwayPoint2 = roadwayPointDAO.fetch(link1.roadwayNumber, link1.startAddrMValue)

      roadwayPoint1.head.addrMValue should be(roadway.endAddrMValue)
      roadwayPoint2.head.addrMValue should be(link1.startAddrMValue)

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
      val roadwayNumber = Sequences.nextRoadwayNumber
      val rwId = Sequences.nextRoadwayId
      val roadway = Roadway(rwId, roadwayNumber, road999, part, RoadType.PublicRoad, Track.Combined, Discontinuity.Continuous, 0L, 10L, reversed = false, DateTime.now, None, "user", None, 8L, TerminationCode.NoTermination, DateTime.now, None)
      val roadway2 = Roadway(rwId + 1, roadwayNumber + 1, road1000, part, RoadType.PublicRoad, Track.Combined, Discontinuity.Continuous, 0L, 10L, reversed = false, DateTime.now, None, "user", None, 8L, TerminationCode.NoTermination, DateTime.now, None)
      val linearLocationId = Sequences.nextLinearLocationId
      val linearLocation = LinearLocation(linearLocationId, 1, 12345, 0L, 10L, SideCode.TowardsDigitizing, 0L,
        calibrationPoints = (CalibrationPointReference(Some(0)), CalibrationPointReference(Some(10))),
        geom1, LinkGeomSource.NormalLinkInterface, roadwayNumber, None, None)
      val projectId = Sequences.nextViiteProjectId
      val plId1 = projectId + 1

      val project = Project(projectId, ProjectState.Incomplete, "f", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
        "", Seq(), Seq(), None, None)

      val link1 = dummyProjectLink(road1000, part, Track.Combined, Discontinuity.EndOfRoad, 0, 10, 0, 10, Some(DateTime.now()), None, 12346, 0, 10, SideCode.TowardsDigitizing, LinkStatus.Transfer, projectId, RoadType.PublicRoad, geom2, roadwayNumber + 1).copy(id = plId1 + 1, roadwayId = rwId + 1, linearLocationId = linearLocationId + 1)


      val linearLocations = Seq(
        LinearLocation(linearLocationId, 1, 1000l, 0.0, 50.0, SideCode.TowardsDigitizing, 10000000000l,
          (CalibrationPointReference(Some(0l)), CalibrationPointReference.None),
          Seq(Point(0.0, 0.0), Point(10.0, 0.0)), LinkGeomSource.ComplementaryLinkInterface,
          roadwayNumber, Some(DateTime.parse("2000-01-01")), None),
        LinearLocation(linearLocationId + 1, 1, 2000l, 0.0, 10.0, SideCode.TowardsDigitizing, 10000000000l,
          (CalibrationPointReference.None, CalibrationPointReference.None),
          Seq(Point(10.0, 0.0), Point(20.0, 0.0)), LinkGeomSource.ComplementaryLinkInterface,
          roadwayNumber + 1, Some(DateTime.parse("2000-01-01")), None)
      )
      val pls = Seq(link1)
      val reversedPls = pls.map(_.copy(sideCode = SideCode.switch(link1.sideCode), reversed = true))
      roadwayDAO.create(Seq(roadway, roadway2))
      buildTestDataForProject(Some(project), None, Some(linearLocations), Some(reversedPls))
      val projectChanges = List(
        //combined
        ProjectRoadwayChange(0, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(AddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, None, Some(PublicRoad), Some(Discontinuity.Continuous), Some(8L)),
            RoadwayChangeSection(Some(link1.roadNumber), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(link1.roadPartNumber), endRoadPartNumber = Some(link1.roadPartNumber), startAddressM = Some(link1.startAddrMValue), endAddressM = Some(link1.endAddrMValue), Some(RoadType.PublicRoad), Some(Discontinuity.Continuous), Some(8L)),
            Discontinuity.Continuous, RoadType.PublicRoad, reversed = false, 1, 8)
          , DateTime.now, Some(0L))
      )

      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(any[BoundingRectangle], any[Seq[(Int, Int)]])).thenReturn(Seq(linearLocation))
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(any[Set[Long]], any[Boolean])).thenReturn(Seq(roadway))

      val reversedProjectChanges = projectChanges.map(p => p.copy(changeInfo = p.changeInfo.copy(changeType = AddressChangeType.Transfer, source = p.changeInfo.target, reversed = true)))

      val mappedRoadwayNumbers = projectLinkDAO.fetchProjectLinksChange(projectId)
      sqlu"""INSERT INTO LINK (ID) VALUES (12345)""".execute
      sqlu"""INSERT INTO LINK (ID) VALUES (12346)""".execute
      nodesAndJunctionsService.handleJunctionTemplates(projectChanges, pls, mappedRoadwayNumbers)

      val roadJunctionPoints = junctionPointDAO.fetchByRoadwayPoint(roadway.roadwayNumber, roadway.endAddrMValue, BeforeAfter.Before)
      val junction1 = junctionDAO.fetchByIds(Seq(roadJunctionPoints.head.junctionId))

      val linkJunctionPoints = junctionPointDAO.fetchByRoadwayPoint(link1.roadwayNumber, link1.startAddrMValue, BeforeAfter.After)
      val junction2 = junctionDAO.fetchByIds(Seq(linkJunctionPoints.head.junctionId))

      junction1 should be(junction2)

      val roadwayPoint1 = roadwayPointDAO.fetch(roadway.roadwayNumber, roadway.endAddrMValue)
      val roadwayPoint2 = roadwayPointDAO.fetch(link1.roadwayNumber, link1.startAddrMValue)

      roadwayPoint1.head.addrMValue should be(roadway.endAddrMValue)
      roadwayPoint2.head.addrMValue should be(link1.startAddrMValue)

      roadJunctionPoints.isDefined should be(true)
      roadJunctionPoints.head.beforeAfter should be(BeforeAfter.Before)
      roadJunctionPoints.head.roadwayNumber should be(roadway.roadwayNumber)
      linkJunctionPoints.isDefined should be(true)
      linkJunctionPoints.head.beforeAfter should be(BeforeAfter.After)
      linkJunctionPoints.head.roadwayNumber should be(link1.roadwayNumber)

      val reversedRoadways = Seq(Roadway(NewIdValue, link1.roadwayNumber, link1.roadNumber, link1.roadPartNumber, RoadType.PublicRoad, Track.Combined, Discontinuity.Continuous,
        link1.startAddrMValue, link1.endAddrMValue, reversed = true, DateTime.parse("2000-01-01"), None, "test", Some("TEST ROAD 1"), 1, TerminationCode.NoTermination)
      )
      roadwayDAO.expireById(Seq(roadway, roadway2).map(_.id).toSet)
      roadwayDAO.create(reversedRoadways)
      val mappedReservedRoadwayNumbers = projectLinkDAO.fetchProjectLinksChange(projectId)
      roadAddressService.handleRoadwayPointsUpdate(reversedProjectChanges, mappedReservedRoadwayNumbers)
      val roadwayPointsAfterUpdate = roadwayPointDAO.fetchByRoadwayNumber(link1.roadwayNumber)
      nodesAndJunctionsService.handleJunctionTemplates(reversedProjectChanges, reversedPls, mappedReservedRoadwayNumbers)
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
      val roadwayNumber = Sequences.nextRoadwayNumber
      val rwId = Sequences.nextRoadwayId
      val linearLocationId = Sequences.nextLinearLocationId
      val roadway = Roadway(rwId, roadwayNumber, road999, part, RoadType.PublicRoad, Track.Combined, Discontinuity.Continuous, 0L, 10L, reversed = false, DateTime.now, None, "user", None, 8L, TerminationCode.NoTermination, DateTime.now, None)
      val roadway2 = Roadway(rwId + 1, roadwayNumber + 1, road1000, part, RoadType.PublicRoad, Track.Combined, Discontinuity.Continuous, 0L, 10L, reversed = false, DateTime.now, None, "user", None, 8L, TerminationCode.NoTermination, DateTime.now, None)
      val linearLocation = LinearLocation(linearLocationId, 1, 12345, 0L, 10L, SideCode.AgainstDigitizing, 0L,
        calibrationPoints = (CalibrationPointReference.None, CalibrationPointReference(Some(10))),
        geom1, LinkGeomSource.NormalLinkInterface, roadwayNumber, None, None)
      val linearLocation2 = LinearLocation(linearLocationId + 1, 1, 12346, 0L, 10L, SideCode.TowardsDigitizing, 0L,
        calibrationPoints = (CalibrationPointReference.None, CalibrationPointReference(Some(10))),
        geom1, LinkGeomSource.NormalLinkInterface, roadwayNumber + 1, None, None)

      val projectId = Sequences.nextViiteProjectId

      val link1 = dummyProjectLink(road1000, part, Track.Combined, Discontinuity.EndOfRoad, 0, 10, 0, 10, Some(DateTime.now()), None, 12346, 0, 10, SideCode.TowardsDigitizing, LinkStatus.Transfer, projectId, RoadType.PublicRoad, geom2, roadwayNumber + 1).copy(id = projectId + 1, roadwayId = rwId + 1, linearLocationId = linearLocationId + 1)

      val project = Project(projectId, ProjectState.Incomplete, "f", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
        "", Seq(), Seq(), None, None)
      val pls = Seq(link1)

      buildTestDataForProject(Some(project), Some(Seq(roadway, roadway2)), Some(Seq(linearLocation, linearLocation2)), Some(pls))

      val projectChanges = List(
        //combined
        ProjectRoadwayChange(0, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(AddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, None, Some(PublicRoad), Some(Discontinuity.Continuous), Some(8L)),
            RoadwayChangeSection(Some(link1.roadNumber), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(link1.roadPartNumber), endRoadPartNumber = Some(link1.roadPartNumber), startAddressM = Some(link1.startAddrMValue), endAddressM = Some(link1.endAddrMValue), Some(RoadType.PublicRoad), Some(Discontinuity.Continuous), Some(8L)),
            Discontinuity.Continuous, RoadType.PublicRoad, reversed = false, 1, 8)
          , DateTime.now, Some(0L))
      )

      val reversedPls = pls.map(_.copy(sideCode = SideCode.switch(link1.sideCode), reversed = true))

      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(any[BoundingRectangle], any[Seq[(Int, Int)]])).thenReturn(Seq(linearLocation))
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(any[Set[Long]], any[Boolean])).thenReturn(Seq(roadway))

      val reversedProjectChanges = projectChanges.map(p => p.copy(changeInfo = p.changeInfo.copy(changeType = AddressChangeType.Transfer, source = p.changeInfo.target, reversed = true)))
      val mappedRoadwayNumbers = projectLinkDAO.fetchProjectLinksChange(projectId)
      nodesAndJunctionsService.handleJunctionTemplates(projectChanges, pls, mappedRoadwayNumbers)

      val roadJunctionPoints = junctionPointDAO.fetchByRoadwayPoint(roadway.roadwayNumber, roadway.startAddrMValue, BeforeAfter.After)
      val junction1 = junctionDAO.fetchByIds(Seq(roadJunctionPoints.head.junctionId))

      val linkJunctionPoints = junctionPointDAO.fetchByRoadwayPoint(link1.roadwayNumber, link1.startAddrMValue, BeforeAfter.After)
      val junction2 = junctionDAO.fetchByIds(Seq(linkJunctionPoints.head.junctionId))

      junction1 should be(junction2)

      val roadwayPoint1 = roadwayPointDAO.fetch(roadway.roadwayNumber, roadway.startAddrMValue)
      val roadwayPoint2 = roadwayPointDAO.fetch(link1.roadwayNumber, link1.startAddrMValue)

      roadwayPoint1.head.addrMValue should be(roadway.startAddrMValue)
      roadwayPoint2.head.addrMValue should be(link1.startAddrMValue)

      roadJunctionPoints.isDefined should be(true)
      roadJunctionPoints.head.beforeAfter should be(BeforeAfter.After)
      roadJunctionPoints.head.roadwayNumber should be(roadway.roadwayNumber)
      linkJunctionPoints.isDefined should be(true)
      linkJunctionPoints.head.beforeAfter should be(BeforeAfter.After)
      linkJunctionPoints.head.roadwayNumber should be(link1.roadwayNumber)

      val reversedRoadways = Seq(Roadway(NewIdValue, link1.roadwayNumber, link1.roadNumber, link1.roadPartNumber, RoadType.PublicRoad, Track.Combined, Discontinuity.Continuous,
        link1.startAddrMValue, link1.endAddrMValue, reversed = true, DateTime.parse("2000-01-01"), None, "test", Some("TEST ROAD 1"), 1, TerminationCode.NoTermination)
      )
      roadwayDAO.create(reversedRoadways)
      val mappedReversedRoadwayNumbers = projectLinkDAO.fetchProjectLinksChange(projectId)
      roadAddressService.handleRoadwayPointsUpdate(reversedProjectChanges, mappedReversedRoadwayNumbers)
      val roadwayPointsAfterUpdate = roadwayPointDAO.fetchByRoadwayNumber(link1.roadwayNumber)
      nodesAndJunctionsService.handleJunctionTemplates(reversedProjectChanges, reversedPls, mappedReversedRoadwayNumbers)
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
      val roadwayNumber = Sequences.nextRoadwayNumber
      val rwId = Sequences.nextRoadwayId
      val linearLocationId = Sequences.nextLinearLocationId
      val roadway = Roadway(rwId, roadwayNumber, road999, part, RoadType.PublicRoad, Track.Combined, Discontinuity.Continuous, 0L, 10L, reversed = false, DateTime.now, None, "user", None, 8L, TerminationCode.NoTermination, DateTime.now, None)
      val roadway2 = Roadway(rwId + 1, roadwayNumber + 1, road1000, part, RoadType.PublicRoad, Track.Combined, Discontinuity.Continuous, 0L, 10L, reversed = false, DateTime.now, None, "user", None, 8L, TerminationCode.NoTermination, DateTime.now, None)
      val linearLocation = LinearLocation(linearLocationId, 1, 12345, 0L, 10L, SideCode.TowardsDigitizing, 0L,
        calibrationPoints = (CalibrationPointReference(Some(0L)), CalibrationPointReference(Some(10L))),
        geom1, LinkGeomSource.NormalLinkInterface, roadwayNumber, None, None)
      val linearLocation2 = LinearLocation(linearLocationId + 1, 1, 12346, 0L, 10L, SideCode.TowardsDigitizing, 0L,
        calibrationPoints = (CalibrationPointReference(Some(0L)), CalibrationPointReference(Some(10L))),
        geom2, LinkGeomSource.NormalLinkInterface, roadwayNumber + 1, None, None)
      val projectId = Sequences.nextViiteProjectId

      val link1 = dummyProjectLink(road1000, part, Track.Combined, Discontinuity.EndOfRoad, 0, 10, 0, 10, Some(DateTime.now()), None, 12346, 0, 10, SideCode.AgainstDigitizing, LinkStatus.Transfer, projectId, RoadType.PublicRoad, geom2, roadwayNumber + 1).copy(id = projectId + 1, roadwayId = rwId + 1, linearLocationId = linearLocationId + 1)

      val project = Project(projectId, ProjectState.Incomplete, "f", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
        "", Seq(), Seq(), None, None)
      val pls = Seq(link1).map(_.copy(id = Sequences.nextProjectLinkId))

      buildTestDataForProject(Some(project), Some(Seq(roadway, roadway2)), Some(Seq(linearLocation, linearLocation2)), Some(pls))

      val projectChanges = List(
        //combined
        ProjectRoadwayChange(0, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(AddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, None, Some(PublicRoad), Some(Discontinuity.Continuous), Some(8L)),
            RoadwayChangeSection(Some(link1.roadNumber), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(link1.roadPartNumber), endRoadPartNumber = Some(link1.roadPartNumber), startAddressM = Some(link1.startAddrMValue), endAddressM = Some(link1.endAddrMValue), Some(RoadType.PublicRoad), Some(Discontinuity.Continuous), Some(8L)),
            Discontinuity.Continuous, RoadType.PublicRoad, reversed = false, 1, 8)
          , DateTime.now, Some(0L))
      )

      val reversedPls = pls.map(_.copy(sideCode = SideCode.switch(link1.sideCode), reversed = true))

      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(any[BoundingRectangle], any[Seq[(Int, Int)]])).thenReturn(Seq(linearLocation))
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(any[Set[Long]], any[Boolean])).thenReturn(Seq(roadway))

      val reversedProjectChanges = projectChanges.map(p => p.copy(changeInfo = p.changeInfo.copy(changeType = AddressChangeType.Transfer, source = p.changeInfo.target, reversed = true)))
      val mappedRoadwayNumbers = projectLinkDAO.fetchProjectLinksChange(projectId)
      nodesAndJunctionsService.handleJunctionTemplates(projectChanges, pls, mappedRoadwayNumbers)

      val roadJunctionPoints = junctionPointDAO.fetchByRoadwayPoint(roadway.roadwayNumber, roadway.endAddrMValue, BeforeAfter.Before)
      val junction1 = junctionDAO.fetchByIds(Seq(roadJunctionPoints.head.junctionId))

      val linkJunctionPoints = junctionPointDAO.fetchByRoadwayPoint(link1.roadwayNumber, link1.endAddrMValue, BeforeAfter.Before)
      val junction2 = junctionDAO.fetchByIds(Seq(linkJunctionPoints.head.junctionId))

      junction1 should be(junction2)

      val roadwayPoint1 = roadwayPointDAO.fetch(roadway.roadwayNumber, roadway.endAddrMValue)
      val roadwayPoint2 = roadwayPointDAO.fetch(link1.roadwayNumber, link1.endAddrMValue)

      roadwayPoint1.head.addrMValue should be(roadway.endAddrMValue)
      roadwayPoint2.head.addrMValue should be(link1.endAddrMValue)

      roadJunctionPoints.isDefined should be(true)
      roadJunctionPoints.head.beforeAfter should be(BeforeAfter.Before)
      roadJunctionPoints.head.roadwayNumber should be(roadway.roadwayNumber)
      linkJunctionPoints.isDefined should be(true)
      linkJunctionPoints.head.beforeAfter should be(BeforeAfter.Before)
      linkJunctionPoints.head.roadwayNumber should be(link1.roadwayNumber)

      val reversedRoadways = Seq(Roadway(NewIdValue, link1.roadwayNumber, link1.roadNumber, link1.roadPartNumber, RoadType.PublicRoad, Track.Combined, Discontinuity.Continuous,
        link1.startAddrMValue, link1.endAddrMValue, reversed = true, DateTime.parse("2000-01-01"), None, "test", Some("TEST ROAD 1"), 1, TerminationCode.NoTermination)
      )
      roadwayDAO.create(reversedRoadways)
      val mappedReversedRoadwayNumbers = projectLinkDAO.fetchProjectLinksChange(projectId)
      roadAddressService.handleRoadwayPointsUpdate(reversedProjectChanges, mappedReversedRoadwayNumbers)
      val roadwayPointsAfterUpdate = roadwayPointDAO.fetchByRoadwayNumber(link1.roadwayNumber)
      nodesAndJunctionsService.handleJunctionTemplates(reversedProjectChanges, reversedPls, mappedReversedRoadwayNumbers)
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
      val roadwayNumber = Sequences.nextRoadwayNumber
      val rwId = Sequences.nextRoadwayId
      val linearLocationId = Sequences.nextLinearLocationId
      val roadway = Roadway(rwId, roadwayNumber, road999, part, RoadType.PublicRoad, Track.Combined, Discontinuity.Continuous, 0L, 10L, reversed = false, DateTime.now, None, "user", None, 8L, TerminationCode.NoTermination, DateTime.now, None)
      val roadway2 = Roadway(rwId + 1, roadwayNumber + 1, road1000, part, RoadType.PublicRoad, Track.Combined, Discontinuity.Continuous, 0L, 10L, reversed = false, DateTime.now, None, "user", None, 8L, TerminationCode.NoTermination, DateTime.now, None)
      val linearLocation = LinearLocation(linearLocationId, 1, 12345, 0L, 10L, SideCode.AgainstDigitizing, 0L, calibrationPoints = (CalibrationPointReference(Some(0)), CalibrationPointReference(Some(10))), geom1, LinkGeomSource.NormalLinkInterface, roadwayNumber, None, None)
      val linearLocation2 = LinearLocation(linearLocationId + 1, 1, 12346, 0L, 10L, SideCode.AgainstDigitizing, 0L, calibrationPoints = (CalibrationPointReference(Some(0)), CalibrationPointReference(Some(10))), geom2, LinkGeomSource.NormalLinkInterface, roadwayNumber + 1, None, None)

      val projectId = Sequences.nextViiteProjectId

      val link1 = dummyProjectLink(road1000, part, Track.Combined, Discontinuity.EndOfRoad, 0, 10, 0, 10, Some(DateTime.now()), None, 12346, 0, 10, SideCode.AgainstDigitizing, LinkStatus.Transfer, projectId, RoadType.PublicRoad, geom2, roadwayNumber + 1).copy(id = projectId + 1, roadwayId = rwId + 1, linearLocationId = linearLocationId + 1)

      val project = Project(projectId, ProjectState.Incomplete, "f", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
        "", Seq(), Seq(), None, None)
      val pls = Seq(link1)

      buildTestDataForProject(Some(project), Some(Seq(roadway, roadway2)), Some(Seq(linearLocation, linearLocation2)), Some(pls))

      val projectChanges = List(
        //combined
        ProjectRoadwayChange(0, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(AddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, None, Some(PublicRoad), Some(Discontinuity.Continuous), Some(8L)),
            RoadwayChangeSection(Some(link1.roadNumber), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(link1.roadPartNumber), endRoadPartNumber = Some(link1.roadPartNumber), startAddressM = Some(link1.startAddrMValue), endAddressM = Some(link1.endAddrMValue), Some(RoadType.PublicRoad), Some(Discontinuity.Continuous), Some(8L)),
            Discontinuity.Continuous, RoadType.PublicRoad, reversed = false, 1, 8)
          , DateTime.now, Some(0L))
      )

      val reversedPls = pls.map(_.copy(sideCode = SideCode.switch(link1.sideCode), reversed = true))

      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(any[BoundingRectangle], any[Seq[(Int, Int)]])).thenReturn(Seq(linearLocation))
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(any[Set[Long]], any[Boolean])).thenReturn(Seq(roadway))

      val reversedProjectChanges = projectChanges.map(p => p.copy(changeInfo = p.changeInfo.copy(changeType = AddressChangeType.Transfer, source = p.changeInfo.target, reversed = true)))
      val mappedRoadwayNumbers = projectLinkDAO.fetchProjectLinksChange(projectId)
      nodesAndJunctionsService.handleJunctionTemplates(projectChanges, pls, mappedRoadwayNumbers)

      val roadJunctionPoints = junctionPointDAO.fetchByRoadwayPoint(roadway.roadwayNumber, roadway.startAddrMValue, BeforeAfter.After)
      val junction1 = junctionDAO.fetchByIds(Seq(roadJunctionPoints.head.junctionId))

      val linkJunctionPoints = junctionPointDAO.fetchByRoadwayPoint(link1.roadwayNumber, link1.endAddrMValue, BeforeAfter.Before)
      val junction2 = junctionDAO.fetchByIds(Seq(linkJunctionPoints.head.junctionId))

      junction1 should be(junction2)

      val roadwayPoint1 = roadwayPointDAO.fetch(roadway.roadwayNumber, roadway.startAddrMValue)
      val roadwayPoint2 = roadwayPointDAO.fetch(link1.roadwayNumber, link1.endAddrMValue)

      roadwayPoint1.head.addrMValue should be(roadway.startAddrMValue)
      roadwayPoint2.head.addrMValue should be(link1.endAddrMValue)

      roadJunctionPoints.isDefined should be(true)
      roadJunctionPoints.head.beforeAfter should be(BeforeAfter.After)
      roadJunctionPoints.head.roadwayNumber should be(roadway.roadwayNumber)
      linkJunctionPoints.isDefined should be(true)
      linkJunctionPoints.head.beforeAfter should be(BeforeAfter.Before)
      linkJunctionPoints.head.roadwayNumber should be(link1.roadwayNumber)

      val reversedRoadways = Seq(Roadway(NewIdValue, link1.roadwayNumber, link1.roadNumber, link1.roadPartNumber, RoadType.PublicRoad, Track.Combined, Discontinuity.Continuous,
        link1.startAddrMValue, link1.endAddrMValue, reversed = true, DateTime.parse("2000-01-01"), None, "test", Some("TEST ROAD 1"), 1, TerminationCode.NoTermination)
      )
      roadwayDAO.create(reversedRoadways)
      val mappedReversedRoadwayNumbers = projectLinkDAO.fetchProjectLinksChange(projectId)
      roadAddressService.handleRoadwayPointsUpdate(reversedProjectChanges, mappedReversedRoadwayNumbers)
      val roadwayPointsAfterUpdate = roadwayPointDAO.fetchByRoadwayNumber(link1.roadwayNumber)
      nodesAndJunctionsService.handleJunctionTemplates(reversedProjectChanges, reversedPls, mappedReversedRoadwayNumbers)
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
      val leftGeom2 = Seq(Point(5.0, 50.0), Point(10.0, 45.0))
      val leftGeom3 = Seq(Point(10.0, 45.0), Point(10.0, 35.0))
      val rightGeom1 = Seq(Point(0.0, 40.0), Point(5.0, 40.0))
      val rightGeom2 = Seq(Point(5.0, 40.0), Point(10.0, 45.0))
      val rightGeom3 = Seq(Point(5.0, 50.0), Point(5.0, 40.0))
      val rightGeom4 = Seq(Point(5.0, 40.0), Point(5.0, 35.0))

      val leftLink1 = dummyProjectLink(road, part, Track.LeftSide, Discontinuity.Continuous, 0, 5, 0, 5, Some(DateTime.now()), None, 12345, 0, 5, SideCode.TowardsDigitizing, LinkStatus.Transfer, projectId, RoadType.PublicRoad, leftGeom1, rwNumber).copy(id = plId, projectId = projectId, roadwayId = rwId, linearLocationId = llId)
      val leftLink2 = dummyProjectLink(road, part, Track.LeftSide, Discontinuity.ParallelLink, 5, 10, 5, 10, Some(DateTime.now()), None, 12346, 0, 5, SideCode.TowardsDigitizing, LinkStatus.Transfer, projectId, RoadType.PublicRoad, leftGeom2, rwNumber).copy(id = plId + 1, projectId = projectId, roadwayId = rwId, linearLocationId = llId + 1)
      val leftLink3 = dummyProjectLink(road, part, Track.LeftSide, Discontinuity.EndOfRoad, 10, 20, 10, 20, Some(DateTime.now()), None, 12347, 0, 10, SideCode.TowardsDigitizing, LinkStatus.Transfer, projectId, RoadType.PublicRoad, leftGeom3, rwNumber + 1).copy(id = plId + 2, projectId = projectId, roadwayId = rwId + 1, linearLocationId = llId + 2)
      val rightLink1 = dummyProjectLink(road, part, Track.RightSide, Discontinuity.Continuous, 0, 5, 0, 5, Some(DateTime.now()), None, 12348, 0, 5, SideCode.TowardsDigitizing, LinkStatus.Transfer, projectId, RoadType.PublicRoad, rightGeom1, rwNumber + 2).copy(id = plId + 3, projectId = projectId, roadwayId = rwId + 2, linearLocationId = llId + 3)
      val rightLink2 = dummyProjectLink(road, part, Track.RightSide, Discontinuity.Discontinuous, 5, 10, 5, 10, Some(DateTime.now()), None, 12349, 0, 5, SideCode.TowardsDigitizing, LinkStatus.Transfer, projectId, RoadType.PublicRoad, rightGeom2, rwNumber + 2).copy(id = plId + 4, projectId = projectId, roadwayId = rwId + 2, linearLocationId = llId + 4)
      val rightLink3 = dummyProjectLink(road, part, Track.RightSide, Discontinuity.Continuous, 10, 15, 10, 15, Some(DateTime.now()), None, 12350, 0, 5, SideCode.TowardsDigitizing, LinkStatus.Transfer, projectId, RoadType.PublicRoad, rightGeom3, rwNumber + 3).copy(id = plId + 5, projectId = projectId, roadwayId = rwId + 3, linearLocationId = llId + 5)
      val rightLink4 = dummyProjectLink(road, part, Track.RightSide, Discontinuity.EndOfRoad, 15, 20, 15, 20, Some(DateTime.now()), None, 12351, 0, 5, SideCode.TowardsDigitizing, LinkStatus.Transfer, projectId, RoadType.PublicRoad, rightGeom4, rwNumber + 3).copy(id = plId + 6, projectId = projectId, roadwayId = rwId + 3, linearLocationId = llId + 6)

      val project = Project(projectId, ProjectState.Incomplete, "f", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
        "", Seq(), Seq(), None, None)

      val leftPLinks = Seq(leftLink1, leftLink2, leftLink3)
      val rightPLinks = Seq(rightLink1, rightLink2, rightLink3, rightLink4)

      val (lll1, rw1): (LinearLocation, Roadway) = Seq(leftLink1).map(toRoadwayAndLinearLocation).head
      val (lll2, rw2): (LinearLocation, Roadway) = Seq(leftLink2).map(toRoadwayAndLinearLocation).head
      val (lll3, rw3): (LinearLocation, Roadway) = Seq(leftLink3).map(toRoadwayAndLinearLocation).head
      val (rll1, rw4): (LinearLocation, Roadway) = Seq(rightLink1).map(toRoadwayAndLinearLocation).head
      val (rll2, rw5): (LinearLocation, Roadway) = Seq(rightLink2).map(toRoadwayAndLinearLocation).head
      val (rll3, rw6): (LinearLocation, Roadway) = Seq(rightLink3).map(toRoadwayAndLinearLocation).head
      val (rll4, rw7): (LinearLocation, Roadway) = Seq(rightLink4).map(toRoadwayAndLinearLocation).head
      val rw1WithId = rw2.copy(ely = 8L, startAddrMValue = 0, endAddrMValue = 10)
      val rw2WithId = rw5.copy(ely = 8L, startAddrMValue = 0, endAddrMValue = 10)
      val rw3WithId = rw3.copy(ely = 8L, startAddrMValue = 10, endAddrMValue = 20)
      val rw4WithId = rw7.copy(ely = 8L, startAddrMValue = 10, endAddrMValue = 20)
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
        // left - parallel link
        ProjectRoadwayChange(projectId, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(AddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, None, Some(PublicRoad), Some(Discontinuity.Continuous), Some(8L)),
            RoadwayChangeSection(Some(road), Some(Track.LeftSide.value.toLong), startRoadPartNumber = Some(part), endRoadPartNumber = Some(part), startAddressM = Some(0L), endAddressM = Some(10L), Some(RoadType.PublicRoad), Some(Discontinuity.ParallelLink), Some(8L)),
            Discontinuity.ParallelLink, RoadType.PublicRoad, reversed = false, 1, 8)
          , DateTime.now, Some(0L)),
        // right - discontinuous
        ProjectRoadwayChange(projectId, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(AddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, None, Some(PublicRoad), Some(Discontinuity.Continuous), Some(8L)),
            RoadwayChangeSection(Some(road), Some(Track.RightSide.value.toLong), startRoadPartNumber = Some(part), endRoadPartNumber = Some(part), startAddressM = Some(0L), endAddressM = Some(10L), Some(RoadType.PublicRoad), Some(Discontinuity.Discontinuous), Some(8L)),
            Discontinuity.Discontinuous, RoadType.PublicRoad, reversed = false, 2, 8)
          , DateTime.now, Some(0L)),
        //  left - end of road
        ProjectRoadwayChange(projectId, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(AddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, None, Some(PublicRoad), Some(Discontinuity.Continuous), Some(8L)),
            RoadwayChangeSection(Some(road), Some(Track.LeftSide.value.toLong), startRoadPartNumber = Some(part), endRoadPartNumber = Some(part), startAddressM = Some(10L), endAddressM = Some(20L), Some(RoadType.PublicRoad), Some(Discontinuity.EndOfRoad), Some(8L)),
            Discontinuity.EndOfRoad, RoadType.PublicRoad, reversed = false, 3, 8)
          , DateTime.now, Some(0L)),
        //  right - end of road
        ProjectRoadwayChange(projectId, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(AddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, None, Some(PublicRoad), Some(Discontinuity.Continuous), Some(8L)),
            RoadwayChangeSection(Some(road), Some(Track.RightSide.value.toLong), startRoadPartNumber = Some(part), endRoadPartNumber = Some(part), startAddressM = Some(10L), endAddressM = Some(20L), Some(RoadType.PublicRoad), Some(Discontinuity.EndOfRoad), Some(8L)),
            Discontinuity.EndOfRoad, RoadType.PublicRoad, reversed = false, 4, 8)
          , DateTime.now, Some(0L))
      )

      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(any[BoundingRectangle], any[Seq[(Int, Int)]])).thenReturn(Seq(orderedlll1, orderedlll2, orderedlll3, orderedrll1, orderedrll2, orderedrll3, orderedrll4))
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(any[Set[Long]], any[Boolean])).thenReturn(Seq(rw1WithId, rw2WithId, rw3WithId, rw4WithId))

      val mappedReservedRoadwayNumbers = projectLinkDAO.fetchProjectLinksChange(projectId)
      roadAddressService.handleRoadwayPointsUpdate(projectChanges, mappedReservedRoadwayNumbers)
      nodesAndJunctionsService.handleJunctionTemplates(projectChanges, leftPLinks ++ rightPLinks, mappedReservedRoadwayNumbers)

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
      val projectId = Sequences.nextViiteProjectId
      val rwId = Sequences.nextRoadwayId
      val llId = Sequences.nextLinearLocationId
      val rwNumber = Sequences.nextRoadwayNumber
      val plId = Sequences.nextProjectLinkId

      val leftGeom1 = Seq(Point(0.0, 50.0), Point(5.0, 50.0))
      val rightGeom1 = Seq(Point(0.0, 40.0), Point(5.0, 40.0))
      val combGeom1 = Seq(Point(5.0, 50.0), Point(5.0, 40.0))
      val combGeom2 = Seq(Point(5.0, 40.0), Point(5.0, 35.0))

      val leftLink1 = dummyProjectLink(road, part, Track.LeftSide, Discontinuity.Continuous, 0, 5, 0, 5, Some(DateTime.now()), None, 12345, 0, 5, SideCode.TowardsDigitizing, LinkStatus.Transfer, projectId, RoadType.PublicRoad, leftGeom1, rwNumber).copy(id = plId, projectId = projectId, roadwayId = rwId, linearLocationId = llId)
      val rightLink1 = dummyProjectLink(road, part, Track.RightSide, Discontinuity.Discontinuous, 0, 5, 0, 5, Some(DateTime.now()), None, 12346, 0, 5, SideCode.TowardsDigitizing, LinkStatus.Transfer, projectId, RoadType.PublicRoad, rightGeom1, rwNumber + 1).copy(id = plId + 1, projectId = projectId, roadwayId = rwId + 1, linearLocationId = llId + 1)
      val combLink1 = dummyProjectLink(road, part, Track.Combined, Discontinuity.Continuous, 5, 15, 5, 15, Some(DateTime.now()), None, 12347, 0, 10, SideCode.TowardsDigitizing, LinkStatus.Transfer, projectId, RoadType.PublicRoad, combGeom1, rwNumber + 2).copy(id = plId + 2, projectId = projectId, roadwayId = rwId + 2, linearLocationId = llId + 2)
      val combLink2 = dummyProjectLink(road, part, Track.Combined, Discontinuity.EndOfRoad, 15, 20, 15, 20, Some(DateTime.now()), None, 12348, 0, 5, SideCode.TowardsDigitizing, LinkStatus.Transfer, projectId, RoadType.PublicRoad, combGeom2, rwNumber + 2).copy(id = plId + 3, projectId = projectId, roadwayId = rwId + 2, linearLocationId = llId + 3)

      val project = Project(projectId, ProjectState.Incomplete, "f", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
        "", Seq(), Seq(), None, None)

      val leftPLinks = Seq(leftLink1)
      val rightPLinks = Seq(rightLink1)
      val combPLinks = Seq(combLink1, combLink2)

      val (lll1, rw1): (LinearLocation, Roadway) = Seq(leftLink1).map(toRoadwayAndLinearLocation).head
      val (rll1, rw2): (LinearLocation, Roadway) = Seq(rightLink1).map(toRoadwayAndLinearLocation).head
      val (cll1, rw3): (LinearLocation, Roadway) = Seq(combLink1).map(toRoadwayAndLinearLocation).head
      val (cll2, _): (LinearLocation, Roadway) = Seq(combLink2).map(toRoadwayAndLinearLocation).head
      val rw1WithId = rw1.copy(ely = 8L, startAddrMValue = 0, endAddrMValue = 5)
      val rw2WithId = rw2.copy(ely = 8L, startAddrMValue = 0, endAddrMValue = 5)
      val rw3WithId = rw3.copy(ely = 8L, startAddrMValue = 5, endAddrMValue = 20)

      val orderedcll1 = cll1.copy(orderNumber = 1)
      val orderedcll2 = cll2.copy(orderNumber = 2)

      buildTestDataForProject(Some(project), Some(Seq(rw1WithId, rw2WithId, rw3WithId)), Some(Seq(lll1, rll1, orderedcll1, orderedcll2)), Some(leftPLinks ++ rightPLinks ++ combPLinks))

      val projectChanges = List(
        //  left
        ProjectRoadwayChange(projectId, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(AddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, None, Some(PublicRoad), Some(Discontinuity.Continuous), Some(8L)),
            RoadwayChangeSection(Some(road), Some(Track.LeftSide.value.toLong), startRoadPartNumber = Some(part), endRoadPartNumber = Some(part), startAddressM = Some(0L), endAddressM = Some(5L), Some(RoadType.PublicRoad), Some(Discontinuity.Continuous), Some(8L)),
            Discontinuity.Continuous, RoadType.PublicRoad, reversed = false, 1, 8)
          , DateTime.now, Some(0L)),
        //  right
        ProjectRoadwayChange(projectId, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(AddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, None, Some(PublicRoad), Some(Discontinuity.Continuous), Some(8L)),
            RoadwayChangeSection(Some(road), Some(Track.RightSide.value.toLong), startRoadPartNumber = Some(part), endRoadPartNumber = Some(part), startAddressM = Some(0L), endAddressM = Some(5L), Some(RoadType.PublicRoad), Some(Discontinuity.Discontinuous), Some(8L)),
            Discontinuity.Discontinuous, RoadType.PublicRoad, reversed = false, 2, 8)
          , DateTime.now, Some(0L)),
        //  combined
        ProjectRoadwayChange(projectId, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(AddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, None, Some(PublicRoad), Some(Discontinuity.Continuous), Some(8L)),
            RoadwayChangeSection(Some(road), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(part), endRoadPartNumber = Some(part), startAddressM = Some(5L), endAddressM = Some(15L), Some(RoadType.PublicRoad), Some(Discontinuity.Continuous), Some(8L)),
            Discontinuity.Continuous, RoadType.PublicRoad, reversed = false, 3, 8)
          , DateTime.now, Some(0L)),
        ProjectRoadwayChange(projectId, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(AddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, None, Some(PublicRoad), Some(Discontinuity.Continuous), Some(8L)),
            RoadwayChangeSection(Some(road), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(part), endRoadPartNumber = Some(part), startAddressM = Some(15L), endAddressM = Some(20L), Some(RoadType.PublicRoad), Some(Discontinuity.EndOfRoad), Some(8L)),
            Discontinuity.EndOfRoad, RoadType.PublicRoad, reversed = false, 4, 8)
          , DateTime.now, Some(0L))
      )

      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(leftGeom1.head, leftGeom1.head), roadNumberLimits)).thenReturn(Seq())
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(Set.empty[Long], false)).thenReturn(Seq())

      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(leftGeom1.last, leftGeom1.last), roadNumberLimits)).thenReturn(Seq(lll1, orderedcll1, orderedcll2))
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(Set(lll1.roadwayNumber, orderedcll1.roadwayNumber), false)).thenReturn(Seq(rw1WithId, rw3WithId))

      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(rightGeom1.head, rightGeom1.head), roadNumberLimits)).thenReturn(Seq())
      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(rightGeom1.last, rightGeom1.last), roadNumberLimits)).thenReturn(Seq(rll1, orderedcll1, orderedcll2))
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(Set(rll1.roadwayNumber, orderedcll1.roadwayNumber), false)).thenReturn(Seq(rw2WithId, rw3WithId))

      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(combGeom1.head, combGeom1.head), roadNumberLimits)).thenReturn(Seq(orderedcll1, orderedcll2, lll1))
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(Set(lll1.roadwayNumber, orderedcll1.roadwayNumber), false)).thenReturn(Seq(rw1WithId, rw3WithId))
      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(combGeom1.last, combGeom1.last), roadNumberLimits)).thenReturn(Seq(rll1, orderedcll1, orderedcll2))
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(Set(rll1.roadwayNumber, orderedcll1.roadwayNumber), false)).thenReturn(Seq(rw2WithId, rw3WithId))
      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(combGeom2.head, combGeom2.head), roadNumberLimits)).thenReturn(Seq(rll1, orderedcll1, orderedcll2))
      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(combGeom2.last, combGeom2.last), roadNumberLimits)).thenReturn(Seq())

      val mappedReservedRoadwayNumbers = projectLinkDAO.fetchProjectLinksChange(projectId)
      roadAddressService.handleRoadwayPointsUpdate(projectChanges, mappedReservedRoadwayNumbers)
      nodesAndJunctionsService.handleNodePointTemplates(projectChanges, leftPLinks ++ rightPLinks ++ combPLinks, mappedReservedRoadwayNumbers)
      nodesAndJunctionsService.handleJunctionTemplates(projectChanges, leftPLinks ++ rightPLinks ++ combPLinks, mappedReservedRoadwayNumbers)

      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(Set(lll1.roadwayNumber, rll1.roadwayNumber, orderedcll1.roadwayNumber), false)).thenReturn(Seq(rw1WithId, rw2WithId, rw3WithId))
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
      val project2 = Project(projectId + 1, ProjectState.Incomplete, "ProjectTerminatedLinks", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
        "", Seq(), Seq(), None, None)
      val terminatingProjectChanges = List(
        //  left
        ProjectRoadwayChange(projectId, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(AddressChangeType.Unchanged,
            RoadwayChangeSection(Some(road), Some(Track.LeftSide.value.toLong), startRoadPartNumber = Some(part), endRoadPartNumber = Some(part), startAddressM = Some(0L), endAddressM = Some(5L), Some(RoadType.PublicRoad), Some(Discontinuity.Continuous), Some(8L)),
            RoadwayChangeSection(Some(road), Some(Track.LeftSide.value.toLong), startRoadPartNumber = Some(part), endRoadPartNumber = Some(part), startAddressM = Some(0L), endAddressM = Some(5L), Some(RoadType.PublicRoad), Some(Discontinuity.Continuous), Some(8L)),
            Discontinuity.Continuous, RoadType.PublicRoad, reversed = false, 1, 8)
          , DateTime.now, Some(0L)),
        // right
        ProjectRoadwayChange(projectId, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(AddressChangeType.Unchanged,
            RoadwayChangeSection(Some(road), Some(Track.RightSide.value.toLong), startRoadPartNumber = Some(part), endRoadPartNumber = Some(part), startAddressM = Some(0L), endAddressM = Some(5L), Some(RoadType.PublicRoad), Some(Discontinuity.Discontinuous), Some(8L)),
            RoadwayChangeSection(Some(road), Some(Track.RightSide.value.toLong), startRoadPartNumber = Some(part), endRoadPartNumber = Some(part), startAddressM = Some(0L), endAddressM = Some(5L), Some(RoadType.PublicRoad), Some(Discontinuity.Discontinuous), Some(8L)),
            Discontinuity.Discontinuous, RoadType.PublicRoad, reversed = false, 2, 8)
          , DateTime.now, Some(0L)),
        // combined
        ProjectRoadwayChange(projectId, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(AddressChangeType.Transfer,
            RoadwayChangeSection(Some(road), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(part), endRoadPartNumber = Some(part), startAddressM = Some(5L), endAddressM = Some(15L), Some(RoadType.PublicRoad), Some(Discontinuity.Continuous), Some(8L)),
            RoadwayChangeSection(Some(road), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(part), endRoadPartNumber = Some(part), startAddressM = Some(5L), endAddressM = Some(15L), Some(RoadType.PublicRoad), Some(Discontinuity.EndOfRoad), Some(8L)),
            Discontinuity.EndOfRoad, RoadType.PublicRoad, reversed = false, 3, 8)
          , DateTime.now, Some(0L)),
        ProjectRoadwayChange(projectId, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(AddressChangeType.Termination,
            RoadwayChangeSection(Some(road), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(part), endRoadPartNumber = Some(part), startAddressM = Some(15L), endAddressM = Some(20L), Some(RoadType.PublicRoad), Some(Discontinuity.EndOfRoad), Some(8L)),
            RoadwayChangeSection(None, None, None, None, None, None, Some(PublicRoad), Some(Discontinuity.Continuous), Some(8L)),
            Discontinuity.EndOfRoad, RoadType.PublicRoad, reversed = false, 4, 8)
          , DateTime.now, Some(0L))
      )

      linearLocationDAO.expireByRoadwayNumbers(Set(combLink1.roadwayNumber, combLink2.roadwayNumber))
      roadwayDAO.expireHistory(Set(combLink1.roadwayId, combLink2.roadwayId))
      projectLinkDAO.moveProjectLinksToHistory(projectId)
      val unchangedLeftLink1 = dummyProjectLink(road, part, Track.LeftSide, Discontinuity.Continuous, 0, 5, 0, 5, Some(DateTime.now()), None, 12345, 0, 5, SideCode.TowardsDigitizing, LinkStatus.UnChanged, projectId + 1, RoadType.PublicRoad, leftGeom1, rwNumber).copy(id = plId + 4, roadwayId = rwId, linearLocationId = llId)
      val unchangedRightLink1 = dummyProjectLink(road, part, Track.RightSide, Discontinuity.Discontinuous, 0, 5, 0, 5, Some(DateTime.now()), None, 12346, 0, 5, SideCode.TowardsDigitizing, LinkStatus.UnChanged, projectId + 1, RoadType.PublicRoad, rightGeom1, rwNumber + 1).copy(id = plId + 5, roadwayId = rwId + 1, linearLocationId = llId + 1)
      val transferCombLink1 = dummyProjectLink(road, part, Track.Combined, Discontinuity.EndOfRoad, 5, 15, 5, 15, Some(DateTime.now()), None, 12347, 0, 10, SideCode.TowardsDigitizing, LinkStatus.Transfer, projectId + 1, RoadType.PublicRoad, combGeom1, rwNumber + 3).copy(id = plId + 6, roadwayId = rwId + 3, linearLocationId = llId + 6)
      val terminatedCombLink2 = dummyProjectLink(road, part, Track.Combined, Discontinuity.EndOfRoad, 15, 20, 15, 20, Some(DateTime.now()), None, 12348, 0, 5, SideCode.TowardsDigitizing, LinkStatus.Terminated, projectId + 1, RoadType.PublicRoad, combGeom2, rwNumber + 4).copy(id = plId + 7, roadwayId = rwId + 4, linearLocationId = llId + 7)

      val rwTransfer = rw3WithId.copy(id = transferCombLink1.roadwayId, roadwayNumber = transferCombLink1.roadwayNumber, endAddrMValue = 15)
      val rwTerminated = rw3WithId.copy(id = terminatedCombLink2.roadwayId, roadwayNumber = terminatedCombLink2.roadwayNumber, endAddrMValue = 20)
      val transferLinearLocation = orderedcll1.copy(id = transferCombLink1.linearLocationId, roadwayNumber = transferCombLink1.roadwayNumber)
      val terminatedLinearLocation = orderedcll2.copy(id = terminatedCombLink2.linearLocationId, roadwayNumber = terminatedCombLink2.roadwayNumber)

      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(Set(unchangedLeftLink1.roadwayNumber, transferCombLink1.roadwayNumber), false)).thenReturn(Seq(rw1WithId, rwTransfer))
      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(transferCombLink1.geometry.last, transferCombLink1.geometry.last), roadNumberLimits)).thenReturn(Seq(rll1, transferLinearLocation, terminatedLinearLocation))
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(Set(terminatedCombLink2.roadwayNumber), false)).thenReturn(Seq(rwTerminated))
      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(unchangedRightLink1.geometry.last, transferCombLink1.geometry.last), roadNumberLimits)).thenReturn(Seq(rll1, transferLinearLocation))
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(Set(unchangedRightLink1.roadwayNumber, transferCombLink1.roadwayNumber), false)).thenReturn(Seq(rw2WithId, rwTransfer))
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(Set(unchangedLeftLink1.roadwayNumber, unchangedRightLink1.roadwayNumber, transferCombLink1.roadwayNumber), false)).thenReturn(Seq(rw1WithId, rw2WithId, rwTransfer))

      buildTestDataForProject(Some(project2),
        Some(Seq(rwTransfer, rwTerminated)),
        Some(Seq(
          transferLinearLocation,
          terminatedLinearLocation)),
        Some(Seq(unchangedLeftLink1, unchangedRightLink1, transferCombLink1, terminatedCombLink2)))

      val mappedAfterChanges: Seq[ProjectRoadLinkChange] = projectLinkDAO.fetchProjectLinksChange(projectId + 1)
        .map { l =>
          if (l.newRoadwayNumber == transferCombLink1.roadwayNumber) {
            l.copy(originalLinearLocationId = combLink1.linearLocationId, linearLocationId = transferCombLink1.linearLocationId, originalRoadwayNumber = combLink1.roadwayNumber)
          }
          else if (l.newRoadwayNumber == terminatedCombLink2.roadwayNumber) {
            l.copy(originalLinearLocationId = combLink2.linearLocationId, linearLocationId = terminatedCombLink2.linearLocationId, originalRoadwayNumber = combLink2.roadwayNumber)
          }
          else l
        }

      projectLinkDAO.moveProjectLinksToHistory(projectId + 1)
      roadAddressService.handleRoadwayPointsUpdate(terminatingProjectChanges, mappedAfterChanges)
      nodesAndJunctionsService.handleNodePointTemplates(terminatingProjectChanges, Seq(leftLink1) ++ Seq(rightLink1) ++ Seq(transferCombLink1, terminatedCombLink2), mappedAfterChanges)
      nodesAndJunctionsService.handleJunctionTemplates(terminatingProjectChanges, Seq(leftLink1) ++ Seq(rightLink1) ++ Seq(transferCombLink1, terminatedCombLink2), mappedAfterChanges)

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
      val rwId = Sequences.nextRoadwayId
      val llId = Sequences.nextLinearLocationId
      val rwNumber = Sequences.nextRoadwayNumber
      val plId = Sequences.nextProjectLinkId

      val leftGeom1 = Seq(Point(0.0, 50.0), Point(5.0, 50.0))
      val rightGeom1 = Seq(Point(0.0, 40.0), Point(5.0, 40.0))
      val combGeom1 = Seq(Point(7.0, 45.0), Point(12.0, 45.0))

      val leftLink1 = dummyProjectLink(road, part, Track.LeftSide, Discontinuity.MinorDiscontinuity, 0, 5, 0, 5, Some(DateTime.now()), None, 12345, 0, 5, SideCode.TowardsDigitizing, LinkStatus.Transfer, projectId, RoadType.PublicRoad, leftGeom1, rwNumber).copy(id = plId, projectId = projectId, roadwayId = rwId, linearLocationId = llId)
      val rightLink1 = dummyProjectLink(road, part, Track.RightSide, Discontinuity.MinorDiscontinuity, 0, 5, 0, 5, Some(DateTime.now()), None, 12346, 0, 5, SideCode.TowardsDigitizing, LinkStatus.Transfer, projectId, RoadType.PublicRoad, rightGeom1, rwNumber + 1).copy(id = plId + 1, projectId = projectId, roadwayId = rwId + 1, linearLocationId = llId + 1)
      val combLink1 = dummyProjectLink(road, part, Track.Combined, Discontinuity.EndOfRoad, 5, 10, 5, 10, Some(DateTime.now()), None, 12347, 0, 5, SideCode.TowardsDigitizing, LinkStatus.Transfer, projectId, RoadType.PublicRoad, combGeom1, rwNumber + 2).copy(id = plId + 2, projectId = projectId, roadwayId = rwId + 2, linearLocationId = llId + 2)

      val project = Project(projectId, ProjectState.Incomplete, "f", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
        "", Seq(), Seq(), None, None)

      val leftPLinks = Seq(leftLink1)
      val rightPLinks = Seq(rightLink1)
      val combPLinks = Seq(combLink1)

      val (lll1, rw1): (LinearLocation, Roadway) = Seq(leftLink1).map(toRoadwayAndLinearLocation).head
      val (rll1, rw2): (LinearLocation, Roadway) = Seq(rightLink1).map(toRoadwayAndLinearLocation).head
      val (cll1, rw3): (LinearLocation, Roadway) = Seq(combLink1).map(toRoadwayAndLinearLocation).head
      val rw1WithId = rw1.copy(ely = 8L, startAddrMValue = 0, endAddrMValue = 5)
      val rw2WithId = rw2.copy(ely = 8L, startAddrMValue = 0, endAddrMValue = 5)
      val rw3WithId = rw3.copy(ely = 8L, startAddrMValue = 5, endAddrMValue = 10)

      buildTestDataForProject(Some(project), Some(Seq(rw1WithId, rw2WithId, rw3WithId)), Some(Seq(lll1, rll1, cll1)), Some(leftPLinks ++ rightPLinks ++ combPLinks))

      val projectChanges = List(
        //left
        ProjectRoadwayChange(projectId, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(AddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, None, Some(PublicRoad), Some(Discontinuity.Continuous), Some(8L)),
            RoadwayChangeSection(Some(road), Some(Track.LeftSide.value.toLong), startRoadPartNumber = Some(part), endRoadPartNumber = Some(part), startAddressM = Some(0L), endAddressM = Some(5L), Some(RoadType.PublicRoad), Some(Discontinuity.MinorDiscontinuity), Some(8L)),
            Discontinuity.MinorDiscontinuity, RoadType.PublicRoad, reversed = false, 1, 8)
          , DateTime.now, Some(0L)),
        //right
        ProjectRoadwayChange(projectId, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(AddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, None, Some(PublicRoad), Some(Discontinuity.Continuous), Some(8L)),
            RoadwayChangeSection(Some(road), Some(Track.RightSide.value.toLong), startRoadPartNumber = Some(part), endRoadPartNumber = Some(part), startAddressM = Some(0L), endAddressM = Some(5L), Some(RoadType.PublicRoad), Some(Discontinuity.MinorDiscontinuity), Some(8L)),
            Discontinuity.MinorDiscontinuity, RoadType.PublicRoad, reversed = false, 2, 8)
          , DateTime.now, Some(0L)),
        //combined
        ProjectRoadwayChange(projectId, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(AddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, None, Some(PublicRoad), Some(Discontinuity.Continuous), Some(8L)),
            RoadwayChangeSection(Some(road), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(part), endRoadPartNumber = Some(part), startAddressM = Some(5L), endAddressM = Some(10L), Some(RoadType.PublicRoad), Some(Discontinuity.EndOfRoad), Some(8L)),
            Discontinuity.EndOfRoad, RoadType.PublicRoad, reversed = false, 3, 8)
          , DateTime.now, Some(0L))
      )

      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(leftGeom1.head, leftGeom1.head), roadNumberLimits)).thenReturn(Seq())
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(Set.empty[Long], false)).thenReturn(Seq())

      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(leftGeom1.last, leftGeom1.last), roadNumberLimits)).thenReturn(Seq())
      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(rightGeom1.head, rightGeom1.head), roadNumberLimits)).thenReturn(Seq())
      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(rightGeom1.last, rightGeom1.last), roadNumberLimits)).thenReturn(Seq())
      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(combGeom1.head, combGeom1.head), roadNumberLimits)).thenReturn(Seq())
      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(combGeom1.last, combGeom1.last), roadNumberLimits)).thenReturn(Seq())

      val mappedReservedRoadwayNumbers = projectLinkDAO.fetchProjectLinksChange(projectId)
      roadAddressService.handleRoadwayPointsUpdate(projectChanges, mappedReservedRoadwayNumbers)
      nodesAndJunctionsService.handleJunctionTemplates(projectChanges, leftPLinks ++ rightPLinks ++ combPLinks, mappedReservedRoadwayNumbers)

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
      val rwId = Sequences.nextRoadwayId
      val llId = Sequences.nextLinearLocationId
      val rwNumber = Sequences.nextRoadwayNumber
      val plId = Sequences.nextProjectLinkId


      val leftGeom1 = Seq(Point(0.0, 50.0), Point(5.0, 50.0))
      val rightGeom1 = Seq(Point(0.0, 40.0), Point(5.0, 40.0))
      val combGeom1 = Seq(Point(7.0, 45.0), Point(12.0, 45.0))
      val combGeom2 = Seq(Point(14.0, 45.0), Point(19.0, 45.0))

      val leftLink1 = dummyProjectLink(road, part, Track.LeftSide, Discontinuity.MinorDiscontinuity, 0, 5, 0, 5, Some(DateTime.now()), None, 12345, 0, 5, SideCode.TowardsDigitizing, LinkStatus.Transfer, projectId, RoadType.PublicRoad, leftGeom1, rwNumber).copy(id = plId, projectId = projectId, roadwayId = rwId, linearLocationId = llId)
      val rightLink1 = dummyProjectLink(road, part, Track.RightSide, Discontinuity.MinorDiscontinuity, 0, 5, 0, 5, Some(DateTime.now()), None, 12346, 0, 5, SideCode.TowardsDigitizing, LinkStatus.Transfer, projectId, RoadType.PublicRoad, rightGeom1, rwNumber + 1).copy(id = plId + 1, projectId = projectId, roadwayId = rwId + 1, linearLocationId = llId + 1)
      val combLink1 = dummyProjectLink(road, part, Track.Combined, Discontinuity.MinorDiscontinuity, 5, 10, 5, 10, Some(DateTime.now()), None, 12347, 0, 5, SideCode.TowardsDigitizing, LinkStatus.Transfer, projectId, RoadType.PublicRoad, combGeom1, rwNumber + 2).copy(id = plId + 2, projectId = projectId, roadwayId = rwId + 2, linearLocationId = llId + 2)
      val combLink2 = dummyProjectLink(road, part, Track.Combined, Discontinuity.EndOfRoad, 10, 15, 10, 15, Some(DateTime.now()), None, 12348, 0, 5, SideCode.TowardsDigitizing, LinkStatus.Transfer, projectId, RoadType.PublicRoad, combGeom2, rwNumber + 2).copy(id = plId + 3, projectId = projectId, roadwayId = rwId + 2, linearLocationId = llId + 3)

      val project = Project(projectId, ProjectState.Incomplete, "f", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
        "", Seq(), Seq(), None, None)

      val leftPLinks = Seq(leftLink1)
      val rightPLinks = Seq(rightLink1)
      val combPLinks = Seq(combLink1, combLink2)

      val (lll1, rw1): (LinearLocation, Roadway) = Seq(leftLink1).map(toRoadwayAndLinearLocation).head
      val (rll1, rw2): (LinearLocation, Roadway) = Seq(rightLink1).map(toRoadwayAndLinearLocation).head
      val (cll1, rw3): (LinearLocation, Roadway) = Seq(combLink1).map(toRoadwayAndLinearLocation).head
      val (cll2, rw4): (LinearLocation, Roadway) = Seq(combLink2).map(toRoadwayAndLinearLocation).head
      val rw1WithId = rw1.copy(ely = 8L, startAddrMValue = 0, endAddrMValue = 5)
      val rw2WithId = rw2.copy(ely = 8L, startAddrMValue = 0, endAddrMValue = 5)
      val rw3WithId = rw3.copy(ely = 8L, startAddrMValue = 5, endAddrMValue = 15)
      val orderedcll1 = cll1.copy(orderNumber = 1)
      val orderedcll2 = cll2.copy(orderNumber = 2)

      buildTestDataForProject(Some(project), Some(Seq(rw1WithId, rw2WithId, rw3WithId)), Some(Seq(lll1, rll1, orderedcll1, orderedcll2)), Some(leftPLinks ++ rightPLinks ++ combPLinks))

      val projectChanges = List(
        //left
        ProjectRoadwayChange(projectId, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(AddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, None, Some(PublicRoad), Some(Discontinuity.MinorDiscontinuity), Some(8L)),
            RoadwayChangeSection(Some(road), Some(Track.LeftSide.value.toLong), startRoadPartNumber = Some(part), endRoadPartNumber = Some(part), startAddressM = Some(0L), endAddressM = Some(5L), Some(RoadType.PublicRoad), Some(Discontinuity.MinorDiscontinuity), Some(8L)),
            Discontinuity.MinorDiscontinuity, RoadType.PublicRoad, reversed = false, 1, 8)
          , DateTime.now, Some(0L)),
        //right
        ProjectRoadwayChange(projectId, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(AddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, None, Some(PublicRoad), Some(Discontinuity.MinorDiscontinuity), Some(8L)),
            RoadwayChangeSection(Some(road), Some(Track.RightSide.value.toLong), startRoadPartNumber = Some(part), endRoadPartNumber = Some(part), startAddressM = Some(0L), endAddressM = Some(5L), Some(RoadType.PublicRoad), Some(Discontinuity.MinorDiscontinuity), Some(8L)),
            Discontinuity.MinorDiscontinuity, RoadType.PublicRoad, reversed = false, 2, 8)
          , DateTime.now, Some(0L)),
        //combined
        ProjectRoadwayChange(projectId, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(AddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, None, Some(PublicRoad), Some(Discontinuity.MinorDiscontinuity), Some(8L)),
            RoadwayChangeSection(Some(road), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(part), endRoadPartNumber = Some(part), startAddressM = Some(5L), endAddressM = Some(10L), Some(RoadType.PublicRoad), Some(Discontinuity.MinorDiscontinuity), Some(8L)),
            Discontinuity.MinorDiscontinuity, RoadType.PublicRoad, reversed = false, 3, 8)
          , DateTime.now, Some(0L)),
        ProjectRoadwayChange(projectId, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(AddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, None, Some(PublicRoad), Some(Discontinuity.Continuous), Some(8L)),
            RoadwayChangeSection(Some(road), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(part), endRoadPartNumber = Some(part), startAddressM = Some(10L), endAddressM = Some(15L), Some(RoadType.PublicRoad), Some(Discontinuity.EndOfRoad), Some(8L)),
            Discontinuity.EndOfRoad, RoadType.PublicRoad, reversed = false, 4, 8)
          , DateTime.now, Some(0L))
      )

      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(leftGeom1.head, leftGeom1.head), roadNumberLimits)).thenReturn(Seq())
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(Set.empty[Long], false)).thenReturn(Seq())

      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(leftGeom1.last, leftGeom1.last), roadNumberLimits)).thenReturn(Seq())
      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(rightGeom1.head, rightGeom1.head), roadNumberLimits)).thenReturn(Seq())
      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(rightGeom1.last, rightGeom1.last), roadNumberLimits)).thenReturn(Seq())
      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(combGeom1.head, combGeom1.head), roadNumberLimits)).thenReturn(Seq())
      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(combGeom1.last, combGeom1.last), roadNumberLimits)).thenReturn(Seq())
      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(combGeom2.head, combGeom2.head), roadNumberLimits)).thenReturn(Seq())
      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(combGeom2.last, combGeom2.last), roadNumberLimits)).thenReturn(Seq())

      val mappedReservedRoadwayNumbers = projectLinkDAO.fetchProjectLinksChange(projectId)
      roadAddressService.handleRoadwayPointsUpdate(projectChanges, mappedReservedRoadwayNumbers)
      nodesAndJunctionsService.handleJunctionTemplates(projectChanges, leftPLinks ++ rightPLinks ++ combPLinks, mappedReservedRoadwayNumbers)

      val roadwayPoints = roadwayPointDAO.fetchByRoadwayNumbers((leftPLinks ++ rightPLinks ++ combPLinks).map(_.roadwayNumber)).map(_.id)
      val junctionPointTemplates = junctionPointDAO.fetchByRoadwayPointIds(roadwayPoints)
      junctionPointTemplates.length should be(0)
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
      val rwId = Sequences.nextRoadwayId
      val llId = Sequences.nextLinearLocationId
      val rwNumber = Sequences.nextRoadwayNumber
      val plId = Sequences.nextProjectLinkId


      val leftGeom1 = Seq(Point(0.0, 50.0), Point(5.0, 50.0))
      val rightGeom1 = Seq(Point(0.0, 40.0), Point(5.0, 40.0))
      val combGeom1 = Seq(Point(7.0, 45.0), Point(12.0, 45.0))
      val combGeom2 = Seq(Point(12.0, 45.0), Point(17.0, 45.0))

      val leftLink1 = dummyProjectLink(road, part, Track.LeftSide, Discontinuity.MinorDiscontinuity, 0, 5, 0, 5, Some(DateTime.now()), None, 12345, 0, 5, SideCode.TowardsDigitizing, LinkStatus.Transfer, projectId, RoadType.PublicRoad, leftGeom1, rwNumber).copy(id = plId, projectId = projectId, roadwayId = rwId, linearLocationId = llId)
      val rightLink1 = dummyProjectLink(road, part, Track.RightSide, Discontinuity.MinorDiscontinuity, 0, 5, 0, 5, Some(DateTime.now()), None, 12346, 0, 5, SideCode.TowardsDigitizing, LinkStatus.Transfer, projectId, RoadType.PublicRoad, rightGeom1, rwNumber + 1).copy(id = plId + 1, projectId = projectId, roadwayId = rwId + 1, linearLocationId = llId + 1)
      val combLink1 = dummyProjectLink(road, part, Track.Combined, Discontinuity.Continuous, 5, 10, 5, 10, Some(DateTime.now()), None, 12347, 0, 5, SideCode.TowardsDigitizing, LinkStatus.Transfer, projectId, RoadType.PublicRoad, combGeom1, rwNumber + 2).copy(id = plId + 2, projectId = projectId, roadwayId = rwId + 2, linearLocationId = llId + 2)
      val combLink2 = dummyProjectLink(road, part, Track.Combined, Discontinuity.EndOfRoad, 10, 15, 10, 15, Some(DateTime.now()), None, 12348, 0, 5, SideCode.TowardsDigitizing, LinkStatus.Transfer, projectId, RoadType.PublicRoad, combGeom2, rwNumber + 2).copy(id = plId + 3, projectId = projectId, roadwayId = rwId + 2, linearLocationId = llId + 3)

      val project = Project(projectId, ProjectState.Incomplete, "f", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
        "", Seq(), Seq(), None, None)

      val leftPLinks = Seq(leftLink1)
      val rightPLinks = Seq(rightLink1)
      val combPLinks = Seq(combLink1, combLink2)

      val (lll1, rw1): (LinearLocation, Roadway) = Seq(leftLink1).map(toRoadwayAndLinearLocation).head
      val (rll1, rw2): (LinearLocation, Roadway) = Seq(rightLink1).map(toRoadwayAndLinearLocation).head
      val (cll1, rw3): (LinearLocation, Roadway) = Seq(combLink1).map(toRoadwayAndLinearLocation).head
      val (cll2, rw4): (LinearLocation, Roadway) = Seq(combLink2).map(toRoadwayAndLinearLocation).head
      val rw1WithId = rw1.copy(ely = 8L, startAddrMValue = 0, endAddrMValue = 5)
      val rw2WithId = rw2.copy(ely = 8L, startAddrMValue = 0, endAddrMValue = 5)
      val rw3WithId = rw3.copy(ely = 8L, startAddrMValue = 5, endAddrMValue = 15)
      val orderedcll1 = cll1.copy(orderNumber = 1)
      val orderedcll2 = cll2.copy(orderNumber = 2)

      buildTestDataForProject(Some(project), Some(Seq(rw1WithId, rw2WithId, rw3WithId)), Some(Seq(lll1, rll1, orderedcll1, orderedcll2)), Some(leftPLinks ++ rightPLinks ++ combPLinks))

      val projectChanges = List(
        //left
        ProjectRoadwayChange(projectId, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(AddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, None, Some(PublicRoad), Some(Discontinuity.MinorDiscontinuity), Some(8L)),
            RoadwayChangeSection(Some(road), Some(Track.LeftSide.value.toLong), startRoadPartNumber = Some(part), endRoadPartNumber = Some(part), startAddressM = Some(0L), endAddressM = Some(5L), Some(RoadType.PublicRoad), Some(Discontinuity.MinorDiscontinuity), Some(8L)),
            Discontinuity.MinorDiscontinuity, RoadType.PublicRoad, reversed = false, 1, 8)
          , DateTime.now, Some(0L)),
        //right
        ProjectRoadwayChange(projectId, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(AddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, None, Some(PublicRoad), Some(Discontinuity.MinorDiscontinuity), Some(8L)),
            RoadwayChangeSection(Some(road), Some(Track.RightSide.value.toLong), startRoadPartNumber = Some(part), endRoadPartNumber = Some(part), startAddressM = Some(0L), endAddressM = Some(5L), Some(RoadType.PublicRoad), Some(Discontinuity.MinorDiscontinuity), Some(8L)),
            Discontinuity.MinorDiscontinuity, RoadType.PublicRoad, reversed = false, 2, 8)
          , DateTime.now, Some(0L)),
        //combined
        ProjectRoadwayChange(projectId, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(AddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, None, Some(PublicRoad), Some(Discontinuity.Continuous), Some(8L)),
            RoadwayChangeSection(Some(road), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(part), endRoadPartNumber = Some(part), startAddressM = Some(5L), endAddressM = Some(15L), Some(RoadType.PublicRoad), Some(Discontinuity.Continuous), Some(8L)),
            Discontinuity.Continuous, RoadType.PublicRoad, reversed = false, 3, 8)
          , DateTime.now, Some(0L)),
        ProjectRoadwayChange(projectId, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(AddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, None, Some(PublicRoad), Some(Discontinuity.EndOfRoad), Some(8L)),
            RoadwayChangeSection(Some(road), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(part), endRoadPartNumber = Some(part), startAddressM = Some(10L), endAddressM = Some(15L), Some(RoadType.PublicRoad), Some(Discontinuity.EndOfRoad), Some(8L)),
            Discontinuity.EndOfRoad, RoadType.PublicRoad, reversed = false, 4, 8)
          , DateTime.now, Some(0L))
      )

      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(leftGeom1.head, leftGeom1.head), roadNumberLimits)).thenReturn(Seq())
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(Set.empty[Long], false)).thenReturn(Seq())

      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(leftGeom1.last, leftGeom1.last), roadNumberLimits)).thenReturn(Seq())
      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(rightGeom1.head, rightGeom1.head), roadNumberLimits)).thenReturn(Seq())
      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(rightGeom1.last, rightGeom1.last), roadNumberLimits)).thenReturn(Seq())
      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(combGeom1.head, combGeom1.head), roadNumberLimits)).thenReturn(Seq())
      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(combGeom1.last, combGeom1.last), roadNumberLimits)).thenReturn(Seq(orderedcll1, orderedcll2))
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(Set(orderedcll1.roadwayNumber, orderedcll2.roadwayNumber), false)).thenReturn(Seq(rw3WithId))
      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(combGeom2.last, combGeom2.last), roadNumberLimits)).thenReturn(Seq())

      val mappedReservedRoadwayNumbers = projectLinkDAO.fetchProjectLinksChange(projectId)
      roadAddressService.handleRoadwayPointsUpdate(projectChanges, mappedReservedRoadwayNumbers)
      nodesAndJunctionsService.handleJunctionTemplates(projectChanges, leftPLinks ++ rightPLinks ++ combPLinks, mappedReservedRoadwayNumbers)

      val roadwayPoints = roadwayPointDAO.fetchByRoadwayNumbers((leftPLinks ++ rightPLinks ++ combPLinks).map(_.roadwayNumber)).map(_.id)
      val junctionPointTemplates = junctionPointDAO.fetchByRoadwayPointIds(roadwayPoints)
      junctionPointTemplates.length should be(0)
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

      val leftLink1 = dummyProjectLink(road, part, Track.LeftSide, Discontinuity.Continuous, 0, 5, 0, 5, Some(DateTime.now()), None, 12345, 0, 5, SideCode.TowardsDigitizing, LinkStatus.Transfer, projectId, RoadType.PublicRoad, leftGeom1, rwNumber).copy(id = plId, projectId = projectId, roadwayId = rwId, linearLocationId = llId)
      val leftLink2 = dummyProjectLink(road, part, Track.LeftSide, Discontinuity.Continuous, 5, 10, 5, 10, Some(DateTime.now()), None, 12346, 0, 5, SideCode.TowardsDigitizing, LinkStatus.Transfer, projectId, RoadType.PublicRoad, leftGeom2, rwNumber).copy(id = plId + 1, projectId = projectId, roadwayId = rwId, linearLocationId = llId + 1)
      val rightLink1 = dummyProjectLink(road, part, Track.RightSide, Discontinuity.MinorDiscontinuity, 0, 10, 0, 10, Some(DateTime.now()), None, 12347, 0, 5, SideCode.TowardsDigitizing, LinkStatus.Transfer, projectId, RoadType.PublicRoad, rightGeom1, rwNumber + 1).copy(id = plId + 2, projectId = projectId, roadwayId = rwId + 1, linearLocationId = llId + 2)
      val combLink1 = dummyProjectLink(road, part, Track.Combined, Discontinuity.Continuous, 10, 15, 10, 15, Some(DateTime.now()), None, 12348, 0, 5, SideCode.TowardsDigitizing, LinkStatus.Transfer, projectId, RoadType.PublicRoad, combGeom1, rwNumber + 2).copy(id = plId + 3, projectId = projectId, roadwayId = rwId + 2, linearLocationId = llId + 3)
      val combLink2 = dummyProjectLink(road, part, Track.Combined, Discontinuity.EndOfRoad, 15, 20, 15, 20, Some(DateTime.now()), None, 12349, 0, 5, SideCode.TowardsDigitizing, LinkStatus.Transfer, projectId, RoadType.PublicRoad, combGeom2, rwNumber + 2).copy(id = plId + 4, projectId = projectId, roadwayId = rwId + 2, linearLocationId = llId + 4)

      val project = Project(projectId, ProjectState.Incomplete, "f", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
        "", Seq(), Seq(), None, None)

      val leftPLinks = Seq(leftLink1, leftLink2)
      val rightPLinks = Seq(rightLink1)
      val combPLinks = Seq(combLink1, combLink2)

      val (lll1, rw1): (LinearLocation, Roadway) = Seq(leftLink1).map(toRoadwayAndLinearLocation).head
      val (lll2, rw11): (LinearLocation, Roadway) = Seq(leftLink2).map(toRoadwayAndLinearLocation).head
      val (rll1, rw2): (LinearLocation, Roadway) = Seq(rightLink1).map(toRoadwayAndLinearLocation).head
      val (cll1, rw3): (LinearLocation, Roadway) = Seq(combLink1).map(toRoadwayAndLinearLocation).head
      val (cll2, rw4): (LinearLocation, Roadway) = Seq(combLink2).map(toRoadwayAndLinearLocation).head
      val rw1WithId = rw1.copy(ely = 8L, startAddrMValue = 0, endAddrMValue = 10)
      val rw2WithId = rw2.copy(ely = 8L, startAddrMValue = 0, endAddrMValue = 10)
      val rw3WithId = rw3.copy(ely = 8L, startAddrMValue = 10, endAddrMValue = 20)
      val orderedlll1 = lll1.copy(orderNumber = 1)
      val orderedlll2 = lll2.copy(orderNumber = 2)
      val orderedcll1 = cll1.copy(orderNumber = 1)
      val orderedcll2 = cll2.copy(orderNumber = 2)

      buildTestDataForProject(Some(project), Some(Seq(rw1WithId, rw2WithId, rw3WithId)), Some(Seq(orderedlll1, orderedlll2, rll1, orderedcll1, orderedcll2)), Some(leftPLinks ++ rightPLinks ++ combPLinks))

      val projectChanges = List(
        //left
        ProjectRoadwayChange(projectId, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(AddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, None, Some(PublicRoad), Some(Discontinuity.Continuous), Some(8L)),
            RoadwayChangeSection(Some(road), Some(Track.LeftSide.value.toLong), startRoadPartNumber = Some(part), endRoadPartNumber = Some(part), startAddressM = Some(0L), endAddressM = Some(10L), Some(RoadType.PublicRoad), Some(Discontinuity.Continuous), Some(8L)),
            Discontinuity.Continuous, RoadType.PublicRoad, reversed = false, 1, 8)
          , DateTime.now, Some(0L)),
        //right
        ProjectRoadwayChange(projectId, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(AddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, None, Some(PublicRoad), Some(Discontinuity.MinorDiscontinuity), Some(8L)),
            RoadwayChangeSection(Some(road), Some(Track.RightSide.value.toLong), startRoadPartNumber = Some(part), endRoadPartNumber = Some(part), startAddressM = Some(0L), endAddressM = Some(10L), Some(RoadType.PublicRoad), Some(Discontinuity.MinorDiscontinuity), Some(8L)),
            Discontinuity.MinorDiscontinuity, RoadType.PublicRoad, reversed = false, 2, 8)
          , DateTime.now, Some(0L)),
        //combined
        ProjectRoadwayChange(projectId, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(AddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, None, Some(PublicRoad), Some(Discontinuity.Continuous), Some(8L)),
            RoadwayChangeSection(Some(road), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(part), endRoadPartNumber = Some(part), startAddressM = Some(10L), endAddressM = Some(15L), Some(RoadType.PublicRoad), Some(Discontinuity.Continuous), Some(8L)),
            Discontinuity.Continuous, RoadType.PublicRoad, reversed = false, 3, 8)
          , DateTime.now, Some(0L)),
        ProjectRoadwayChange(projectId, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(AddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, None, Some(PublicRoad), Some(Discontinuity.EndOfRoad), Some(8L)),
            RoadwayChangeSection(Some(road), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(part), endRoadPartNumber = Some(part), startAddressM = Some(15L), endAddressM = Some(20L), Some(RoadType.PublicRoad), Some(Discontinuity.EndOfRoad), Some(8L)),
            Discontinuity.EndOfRoad, RoadType.PublicRoad, reversed = false, 4, 8)
          , DateTime.now, Some(0L))
      )

      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(leftGeom1.head, leftGeom1.head), roadNumberLimits)).thenReturn(Seq())
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(Set.empty[Long], false)).thenReturn(Seq())

      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(leftGeom1.last, leftGeom1.last), roadNumberLimits)).thenReturn(Seq(orderedlll1, orderedlll2))
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(Set(orderedlll1.roadwayNumber), false)).thenReturn(Seq(rw1WithId))
      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(leftGeom2.last, leftGeom2.last), roadNumberLimits)).thenReturn(Seq(orderedlll1, orderedlll2, orderedcll1, orderedcll2))
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(Set(rw1WithId.roadwayNumber, orderedcll1.roadwayNumber), false)).thenReturn(Seq(rw1WithId, rw3WithId))
      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(rightGeom1.head, rightGeom1.head), roadNumberLimits)).thenReturn(Seq())
      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(rightGeom1.last, rightGeom1.last), roadNumberLimits)).thenReturn(Seq())
      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(combGeom1.head, combGeom1.head), roadNumberLimits)).thenReturn(Seq())
      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(combGeom1.last, combGeom1.last), roadNumberLimits)).thenReturn(Seq(orderedcll1, orderedcll2))
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(Set(orderedcll1.roadwayNumber, orderedcll2.roadwayNumber), false)).thenReturn(Seq(rw3WithId))
      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(combGeom2.last, combGeom2.last), roadNumberLimits)).thenReturn(Seq())

      val mappedReservedRoadwayNumbers = projectLinkDAO.fetchProjectLinksChange(projectId)
      roadAddressService.handleRoadwayPointsUpdate(projectChanges, mappedReservedRoadwayNumbers)
      nodesAndJunctionsService.handleJunctionTemplates(projectChanges, leftPLinks ++ rightPLinks ++ combPLinks, mappedReservedRoadwayNumbers)

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

      val combLink1 = dummyProjectLink(road, part1, Track.Combined, Discontinuity.Continuous, 0, 10, 0, 10, Some(DateTime.now()), None, 12345, 0, 10, SideCode.TowardsDigitizing, LinkStatus.Transfer, projectId, RoadType.PublicRoad, combGeom1, rwNumber).copy(id = plId, projectId = projectId, roadwayId = rwId, linearLocationId = llId)
      val combLink2 = dummyProjectLink(road, part2, Track.Combined, Discontinuity.Discontinuous, 0, 10, 0, 10, Some(DateTime.now()), None, 12346, 0, 10, SideCode.TowardsDigitizing, LinkStatus.Transfer, projectId, RoadType.PublicRoad, combGeom2, rwNumber + 1).copy(id = plId + 1, projectId = projectId, roadwayId = rwId + 1, linearLocationId = llId + 1)
      val combLink3 = dummyProjectLink(road, part3, Track.Combined, Discontinuity.EndOfRoad, 0, 10, 0, 10, Some(DateTime.now()), None, 12347, 0, 10, SideCode.TowardsDigitizing, LinkStatus.Transfer, projectId, RoadType.PublicRoad, combGeom3, rwNumber + 2).copy(id = plId + 2, projectId = projectId, roadwayId = rwId + 2, linearLocationId = llId + 2)

      val project = Project(projectId, ProjectState.Incomplete, "f", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
        "", Seq(), Seq(), None, None)

      val combPLinks = Seq(combLink1, combLink2, combLink3)

      val (cll1, rw1): (LinearLocation, Roadway) = Seq(combLink1).map(toRoadwayAndLinearLocation).head
      val (cll2, rw2): (LinearLocation, Roadway) = Seq(combLink2).map(toRoadwayAndLinearLocation).head
      val (cll3, rw3): (LinearLocation, Roadway) = Seq(combLink3).map(toRoadwayAndLinearLocation).head
      val rw1WithId = rw1.copy(ely = 8L, startAddrMValue = 0, endAddrMValue = 10)
      val rw2WithId = rw2.copy(ely = 8L, startAddrMValue = 0, endAddrMValue = 10)
      val rw3WithId = rw3.copy(ely = 8L, startAddrMValue = 0, endAddrMValue = 10)

      buildTestDataForProject(Some(project), Some(Seq(rw1WithId, rw2WithId, rw3WithId)), Some(Seq(cll1, cll2, cll3)), Some(combPLinks))

      val projectChanges = List(
        //Combined
        ProjectRoadwayChange(projectId, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(AddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, None, Some(PublicRoad), Some(Discontinuity.Continuous), Some(8L)),
            RoadwayChangeSection(Some(road), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(part1), endRoadPartNumber = Some(part1), startAddressM = Some(0L), endAddressM = Some(10L), Some(RoadType.PublicRoad), Some(Discontinuity.Continuous), Some(8L)),
            Discontinuity.Continuous, RoadType.PublicRoad, reversed = false, 1, 8)
          , DateTime.now, Some(0L)),
        ProjectRoadwayChange(projectId, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(AddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, None, Some(PublicRoad), Some(Discontinuity.Continuous), Some(8L)),
            RoadwayChangeSection(Some(road), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(part2), endRoadPartNumber = Some(part2), startAddressM = Some(0L), endAddressM = Some(10L), Some(RoadType.PublicRoad), Some(Discontinuity.Discontinuous), Some(8L)),
            Discontinuity.Discontinuous, RoadType.PublicRoad, reversed = false, 2, 8)
          , DateTime.now, Some(0L)),
        ProjectRoadwayChange(projectId, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(AddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, None, Some(PublicRoad), Some(Discontinuity.Continuous), Some(8L)),
            RoadwayChangeSection(Some(road), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(part3), endRoadPartNumber = Some(part3), startAddressM = Some(0L), endAddressM = Some(10L), Some(RoadType.PublicRoad), Some(Discontinuity.EndOfRoad), Some(8L)),
            Discontinuity.EndOfRoad, RoadType.PublicRoad, reversed = false, 3, 8)
          , DateTime.now, Some(0L))
      )

      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(combGeom1.head, combGeom1.head), roadNumberLimits)).thenReturn(Seq())
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(Set.empty[Long], false)).thenReturn(Seq())
      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(combGeom1.last, combGeom1.last), roadNumberLimits)).thenReturn(Seq(cll1, cll2, cll3))
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(Set(cll1.roadwayNumber, cll2.roadwayNumber, cll3.roadwayNumber), false)).thenReturn(Seq(rw1WithId, rw2WithId, rw3WithId))
      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(combGeom2.last, combGeom2.last), roadNumberLimits)).thenReturn(Seq())
      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(combGeom3.head, combGeom3.head), roadNumberLimits)).thenReturn(Seq())

      val mappedReservedRoadwayNumbers = projectLinkDAO.fetchProjectLinksChange(projectId)
      roadAddressService.handleRoadwayPointsUpdate(projectChanges, mappedReservedRoadwayNumbers)
      nodesAndJunctionsService.handleJunctionTemplates(projectChanges, combPLinks, mappedReservedRoadwayNumbers)

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
      |
     C3
      |
      v
      0|--C1-->|--C2-->|

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
      val combGeom3 = Seq(Point(0.0, 10.0), Point(0.0, 0.0))

      val combLink1 = dummyProjectLink(road, part1, Track.Combined, Discontinuity.Continuous, 0, 10, 0, 10, Some(DateTime.now()), None, 12345, 0, 10, SideCode.TowardsDigitizing, LinkStatus.Transfer, projectId, RoadType.PublicRoad, combGeom1, rwNumber).copy(id = plId, projectId = projectId, roadwayId = rwId, linearLocationId = llId)
      val combLink2 = dummyProjectLink(road, part2, Track.Combined, Discontinuity.Discontinuous, 0, 10, 0, 10, Some(DateTime.now()), None, 12346, 0, 10, SideCode.TowardsDigitizing, LinkStatus.Transfer, projectId, RoadType.PublicRoad, combGeom2, rwNumber + 1).copy(id = plId + 1, projectId = projectId, roadwayId = rwId + 1, linearLocationId = llId + 1)
      val combLink3 = dummyProjectLink(road, part3, Track.Combined, Discontinuity.EndOfRoad, 0, 10, 0, 10, Some(DateTime.now()), None, 12347, 0, 10, SideCode.TowardsDigitizing, LinkStatus.Transfer, projectId, RoadType.PublicRoad, combGeom3, rwNumber + 2).copy(id = plId + 2, projectId = projectId, roadwayId = rwId + 2, linearLocationId = llId + 2)

      val project = Project(projectId, ProjectState.Incomplete, "f", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
        "", Seq(), Seq(), None, None)

      val combPLinks = Seq(combLink1, combLink2, combLink3)

      val (cll1, rw1): (LinearLocation, Roadway) = Seq(combLink1).map(toRoadwayAndLinearLocation).head
      val (cll2, rw2): (LinearLocation, Roadway) = Seq(combLink2).map(toRoadwayAndLinearLocation).head
      val (cll3, rw3): (LinearLocation, Roadway) = Seq(combLink3).map(toRoadwayAndLinearLocation).head
      val rw1WithId = rw1.copy(ely = 8L, startAddrMValue = 0, endAddrMValue = 10)
      val rw2WithId = rw2.copy(ely = 8L, startAddrMValue = 0, endAddrMValue = 10)
      val rw3WithId = rw3.copy(ely = 8L, startAddrMValue = 0, endAddrMValue = 10)

      buildTestDataForProject(Some(project), Some(Seq(rw1WithId, rw2WithId, rw3WithId)), Some(Seq(cll1, cll2, cll3)), Some(combPLinks))

      val projectChanges = List(
        //Combined
        ProjectRoadwayChange(projectId, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(AddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, None, Some(PublicRoad), Some(Discontinuity.Continuous), Some(8L)),
            RoadwayChangeSection(Some(road), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(part1), endRoadPartNumber = Some(part1), startAddressM = Some(0L), endAddressM = Some(10L), Some(RoadType.PublicRoad), Some(Discontinuity.Continuous), Some(8L)),
            Discontinuity.Continuous, RoadType.PublicRoad, reversed = false, 1, 8)
          , DateTime.now, Some(0L)),
        ProjectRoadwayChange(projectId, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(AddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, None, Some(PublicRoad), Some(Discontinuity.Discontinuous), Some(8L)),
            RoadwayChangeSection(Some(road), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(part2), endRoadPartNumber = Some(part2), startAddressM = Some(0L), endAddressM = Some(10L), Some(RoadType.PublicRoad), Some(Discontinuity.Discontinuous), Some(8L)),
            Discontinuity.Discontinuous, RoadType.PublicRoad, reversed = false, 2, 8)
          , DateTime.now, Some(0L)),
        ProjectRoadwayChange(projectId, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(AddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, None, Some(PublicRoad), Some(Discontinuity.EndOfRoad), Some(8L)),
            RoadwayChangeSection(Some(road), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(part3), endRoadPartNumber = Some(part3), startAddressM = Some(0L), endAddressM = Some(10L), Some(RoadType.PublicRoad), Some(Discontinuity.EndOfRoad), Some(8L)),
            Discontinuity.EndOfRoad, RoadType.PublicRoad, reversed = false, 3, 8)
          , DateTime.now, Some(0L))
      )

      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(combGeom1.head, combGeom1.head), roadNumberLimits)).thenReturn(Seq(cll1, cll3))
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(Set(cll1.roadwayNumber, cll3.roadwayNumber), false)).thenReturn(Seq(rw1WithId, rw3WithId))
      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(combGeom1.last, combGeom1.last), roadNumberLimits)).thenReturn(Seq(cll1, cll2))
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(Set(cll1.roadwayNumber, cll2.roadwayNumber), false)).thenReturn(Seq(rw1WithId, rw2WithId))
      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(combGeom2.last, combGeom2.last), roadNumberLimits)).thenReturn(Seq())
      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(combGeom3.head, combGeom3.head), roadNumberLimits)).thenReturn(Seq())
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(Set.empty[Long], false)).thenReturn(Seq())

      val mappedReservedRoadwayNumbers = projectLinkDAO.fetchProjectLinksChange(projectId)
      roadAddressService.handleRoadwayPointsUpdate(projectChanges, mappedReservedRoadwayNumbers)
      nodesAndJunctionsService.handleNodePointTemplates(projectChanges, combPLinks, mappedReservedRoadwayNumbers)
      nodesAndJunctionsService.handleJunctionTemplates(projectChanges, combPLinks, mappedReservedRoadwayNumbers)

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
                        |
                        C3
                        |
                        v
      |--C1-->|--C2-->|0

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
      val combGeom3 = Seq(Point(20.0, 10.0), Point(20.0, 0.0))

      val combLink1 = dummyProjectLink(road, part1, Track.Combined, Discontinuity.Continuous, 0, 10, 0, 10, Some(DateTime.now()), None, 12345, 0, 10, SideCode.TowardsDigitizing, LinkStatus.Transfer, projectId, RoadType.PublicRoad, combGeom1, rwNumber).copy(id = plId, projectId = projectId, roadwayId = rwId, linearLocationId = llId)
      val combLink2 = dummyProjectLink(road, part2, Track.Combined, Discontinuity.Discontinuous, 0, 10, 0, 10, Some(DateTime.now()), None, 12346, 0, 10, SideCode.TowardsDigitizing, LinkStatus.Transfer, projectId, RoadType.PublicRoad, combGeom2, rwNumber + 1).copy(id = plId + 1, projectId = projectId, roadwayId = rwId + 1, linearLocationId = llId + 1)
      val combLink3 = dummyProjectLink(road, part3, Track.Combined, Discontinuity.EndOfRoad, 0, 10, 0, 10, Some(DateTime.now()), None, 12347, 0, 10, SideCode.TowardsDigitizing, LinkStatus.Transfer, projectId, RoadType.PublicRoad, combGeom3, rwNumber + 2).copy(id = plId + 2, projectId = projectId, roadwayId = rwId + 2, linearLocationId = llId + 2)

      val project = Project(projectId, ProjectState.Incomplete, "f", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
        "", Seq(), Seq(), None, None)

      val combPLinks = Seq(combLink1, combLink2, combLink3)

      val (cll1, rw1): (LinearLocation, Roadway) = Seq(combLink1).map(toRoadwayAndLinearLocation).head
      val (cll2, rw2): (LinearLocation, Roadway) = Seq(combLink2).map(toRoadwayAndLinearLocation).head
      val (cll3, rw3): (LinearLocation, Roadway) = Seq(combLink3).map(toRoadwayAndLinearLocation).head
      val rw1WithId = rw1.copy(ely = 8L, startAddrMValue = 0, endAddrMValue = 10)
      val rw2WithId = rw2.copy(ely = 8L, startAddrMValue = 0, endAddrMValue = 10)
      val rw3WithId = rw3.copy(ely = 8L, startAddrMValue = 0, endAddrMValue = 10)

      buildTestDataForProject(Some(project), Some(Seq(rw1WithId, rw2WithId, rw3WithId)), Some(Seq(cll1, cll2, cll3)), Some(combPLinks))

      val projectChanges = List(
        //Combined
        ProjectRoadwayChange(projectId, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(AddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, None, Some(PublicRoad), Some(Discontinuity.Continuous), Some(8L)),
            RoadwayChangeSection(Some(road), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(part1), endRoadPartNumber = Some(part1), startAddressM = Some(0L), endAddressM = Some(10L), Some(RoadType.PublicRoad), Some(Discontinuity.Continuous), Some(8L)),
            Discontinuity.Continuous, RoadType.PublicRoad, reversed = false, 1, 8)
          , DateTime.now, Some(0L)),
        ProjectRoadwayChange(projectId, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(AddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, None, Some(PublicRoad), Some(Discontinuity.Discontinuous), Some(8L)),
            RoadwayChangeSection(Some(road), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(part2), endRoadPartNumber = Some(part2), startAddressM = Some(0L), endAddressM = Some(10L), Some(RoadType.PublicRoad), Some(Discontinuity.Discontinuous), Some(8L)),
            Discontinuity.Discontinuous, RoadType.PublicRoad, reversed = false, 2, 8)
          , DateTime.now, Some(0L)),
        ProjectRoadwayChange(projectId, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(AddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, None, Some(PublicRoad), Some(Discontinuity.EndOfRoad), Some(8L)),
            RoadwayChangeSection(Some(road), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(part3), endRoadPartNumber = Some(part3), startAddressM = Some(0L), endAddressM = Some(10L), Some(RoadType.PublicRoad), Some(Discontinuity.EndOfRoad), Some(8L)),
            Discontinuity.EndOfRoad, RoadType.PublicRoad, reversed = false, 3, 8)
          , DateTime.now, Some(0L))
      )

      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(combGeom1.head, combGeom1.head), roadNumberLimits)).thenReturn(Seq())
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(Set.empty[Long], false)).thenReturn(Seq())
      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(combGeom1.last, combGeom1.last), roadNumberLimits)).thenReturn(Seq(cll1, cll2))
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(Set(cll1.roadwayNumber, cll2.roadwayNumber), false)).thenReturn(Seq(rw1WithId, rw2WithId))
      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(combGeom2.last, combGeom2.last), roadNumberLimits)).thenReturn(Seq(cll2, cll3))
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(Set(cll2.roadwayNumber, cll3.roadwayNumber), false)).thenReturn(Seq(rw2WithId, rw3WithId))
      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(combGeom3.head, combGeom3.head), roadNumberLimits)).thenReturn(Seq())

      val mappedReservedRoadwayNumbers = projectLinkDAO.fetchProjectLinksChange(projectId)
      roadAddressService.handleRoadwayPointsUpdate(projectChanges, mappedReservedRoadwayNumbers)
      nodesAndJunctionsService.handleNodePointTemplates(projectChanges, combPLinks, mappedReservedRoadwayNumbers)
      nodesAndJunctionsService.handleJunctionTemplates(projectChanges, combPLinks, mappedReservedRoadwayNumbers)

      val roadwayPoints = roadwayPointDAO.fetchByRoadwayNumbers(combPLinks.map(_.roadwayNumber)).map(_.id)
      val junctionPointTemplates = junctionPointDAO.fetchByRoadwayPointIds(roadwayPoints)

      junctionPointTemplates.length should be(2)
      junctionPointTemplates.count(_.beforeAfter == BeforeAfter.Before) should be(2)

      val junctions = junctionDAO.fetchTemplatesByRoadwayNumbers(junctionPointTemplates.map(_.roadwayNumber).distinct)
      junctions.size should be(1)
    }
  }


  test("Test nodesAndJunctionsService.handleJunctionTemplates When creating new road parts that connects to other existing part which link is EndOfRoad and ends in some link of this new roads in same road number Then junction template and junctions points should be handled/created properly." +
    "Test nodesAndJunctionsService.expireObsoleteNodesAndJunctions When changing the EndOfRoad link of last part to Discontinuous and creating a new one with EndOfRoad that does not connect to the same road Then the existing Junction and his points should be expired.") {
    runWithRollback {
      /*            |
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

      val combLink1 = dummyProjectLink(road, part1, Track.Combined, Discontinuity.Continuous, 0, 10, 0, 10, Some(DateTime.now()), None, 12345, 0, 10, SideCode.TowardsDigitizing, LinkStatus.Transfer, projectId, RoadType.PublicRoad, combGeom1, rwNumber).copy(id = plId, projectId = projectId, roadwayId = rwId, linearLocationId = llId)
      val combLink2 = dummyProjectLink(road, part2, Track.Combined, Discontinuity.Discontinuous, 0, 10, 0, 10, Some(DateTime.now()), None, 12346, 0, 10, SideCode.TowardsDigitizing, LinkStatus.Transfer, projectId, RoadType.PublicRoad, combGeom2, rwNumber + 1).copy(id = plId + 1, projectId = projectId, roadwayId = rwId + 1, linearLocationId = llId + 1)
      val combLink3 = dummyProjectLink(road, part3, Track.Combined, Discontinuity.EndOfRoad, 0, 10, 0, 10, Some(DateTime.now()), None, 12347, 0, 10, SideCode.TowardsDigitizing, LinkStatus.Transfer, projectId, RoadType.PublicRoad, combGeom3, rwNumber + 2).copy(id = plId + 2, projectId = projectId, roadwayId = rwId + 2, linearLocationId = llId + 2)

      val project = Project(projectId, ProjectState.Incomplete, "f", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
        "", Seq(), Seq(), None, None)

      val combPLinks = Seq(combLink1, combLink2)

      val (lc1, rw1): (LinearLocation, Roadway) = Seq(combLink1).map(toRoadwayAndLinearLocation).head
      val (lc2, rw2): (LinearLocation, Roadway) = Seq(combLink2).map(toRoadwayAndLinearLocation).head
      val (lc3, rw3): (LinearLocation, Roadway) = Seq(combLink3).map(toRoadwayAndLinearLocation).head
      val rw1WithId = rw1.copy(ely = 8L, startAddrMValue = 0, endAddrMValue = 10)
      val rw2WithId = rw2.copy(ely = 8L, startAddrMValue = 0, endAddrMValue = 10)
      val rw3WithId = rw3.copy(ely = 8L, startAddrMValue = 0, endAddrMValue = 10)

      buildTestDataForProject(Some(project), Some(Seq(rw1WithId, rw2WithId, rw3WithId)), Some(Seq(lc1, lc2, lc3)), Some(Seq(combLink1, combLink2)))

      val projectChanges = List(
        //  combined
        ProjectRoadwayChange(projectId, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(AddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, None, Some(PublicRoad), Some(Discontinuity.Continuous), Some(8L)),
            RoadwayChangeSection(Some(road), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(part1), endRoadPartNumber = Some(part1), startAddressM = Some(0L), endAddressM = Some(10L), Some(RoadType.PublicRoad), Some(Discontinuity.Continuous), Some(8L)),
            Discontinuity.Continuous, RoadType.PublicRoad, reversed = false, 1, 8)
          , DateTime.now, Some(0L)),
        ProjectRoadwayChange(projectId, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(AddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, None, Some(PublicRoad), Some(Discontinuity.Continuous), Some(8L)),
            RoadwayChangeSection(Some(road), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(part2), endRoadPartNumber = Some(part2), startAddressM = Some(0L), endAddressM = Some(10L), Some(RoadType.PublicRoad), Some(Discontinuity.Discontinuous), Some(8L)),
            Discontinuity.Discontinuous, RoadType.PublicRoad, reversed = false, 2, 8)
          , DateTime.now, Some(0L)),
        ProjectRoadwayChange(projectId, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(AddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, None, Some(PublicRoad), Some(Discontinuity.EndOfRoad), Some(8L)),
            RoadwayChangeSection(Some(road), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(part3), endRoadPartNumber = Some(part3), startAddressM = Some(0L), endAddressM = Some(10L), Some(RoadType.PublicRoad), Some(Discontinuity.EndOfRoad), Some(8L)),
            Discontinuity.EndOfRoad, RoadType.PublicRoad, reversed = false, 3, 8)
          , DateTime.now, Some(0L))
      )

      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(any[BoundingRectangle], any[Seq[(Int, Int)]])).thenReturn(Seq(lc1, lc2, lc3))
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(any[Set[Long]], any[Boolean])).thenReturn(Seq(rw1WithId, rw2WithId, rw3WithId))

      val mappedReservedRoadwayNumbers = projectLinkDAO.fetchProjectLinksChange(projectId)
      roadAddressService.handleRoadwayPointsUpdate(projectChanges, mappedReservedRoadwayNumbers)
      nodesAndJunctionsService.handleNodePointTemplates(projectChanges, combPLinks, mappedReservedRoadwayNumbers)
      nodesAndJunctionsService.handleJunctionTemplates(projectChanges, combPLinks, mappedReservedRoadwayNumbers)

      val roadwayPoints = roadwayPointDAO.fetchByRoadwayNumbers((combPLinks :+ combLink3).map(_.roadwayNumber)).map(_.id)
      val junctionPointTemplates = junctionPointDAO.fetchByRoadwayPointIds(roadwayPoints)

      junctionPointTemplates.length should be(3)
      junctionPointTemplates.count(_.beforeAfter == BeforeAfter.Before) should be(2)
      junctionPointTemplates.count(_.beforeAfter == BeforeAfter.After) should be(1)
      val templateRoadwayNumbers = junctionPointTemplates.map(_.roadwayNumber).distinct
      val junctions = junctionDAO.fetchTemplatesByRoadwayNumbers(templateRoadwayNumbers)
      junctions.size should be(1)

      /*  Preparing expiring data

                  |
                  C3
                  |
                  v
          |--C1-->|--C2-->|--C4-->|

          Note:
            C: Combined track
       */
      val project2 = Project(projectId + 1, ProjectState.Incomplete, "ProjectNewEndOfRoadLinks", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
        "", Seq(), Seq(), None, None)
      val TerminatingProjectChanges = List(
        ProjectRoadwayChange(projectId + 1, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(AddressChangeType.Unchanged,
            RoadwayChangeSection(Some(road), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(3L), endRoadPartNumber = Some(3L), startAddressM = Some(0L), endAddressM = Some(10L), Some(RoadType.PublicRoad), Some(Discontinuity.EndOfRoad), Some(8L)),
            RoadwayChangeSection(Some(road), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(3L), endRoadPartNumber = Some(3L), startAddressM = Some(0L), endAddressM = Some(10L), Some(RoadType.PublicRoad), Some(Discontinuity.Discontinuous), Some(8L)),
            Discontinuity.Discontinuous, RoadType.PublicRoad, reversed = false, 1, 8)
          , DateTime.now, Some(0L)),
        ProjectRoadwayChange(projectId + 1, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(AddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, None, Some(PublicRoad), Some(Discontinuity.Continuous), Some(8L)),
            RoadwayChangeSection(Some(road), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(4L), endRoadPartNumber = Some(4L), startAddressM = Some(0L), endAddressM = Some(10L), Some(RoadType.PublicRoad), Some(Discontinuity.EndOfRoad), Some(8L)),
            Discontinuity.EndOfRoad, RoadType.PublicRoad, reversed = false, 2, 8)
          , DateTime.now, Some(0L))
      )

      linearLocationDAO.expireByRoadwayNumbers(Set(combLink3.roadwayNumber))
      roadwayDAO.expireHistory(Set(combLink3.roadwayId))
      projectLinkDAO.moveProjectLinksToHistory(projectId)
      val combGeom4 = Seq(Point(20.0, 0.0), Point(30.0, 0.0))

      val transferLink = dummyProjectLink(road, 3L, Track.Combined, Discontinuity.Discontinuous, 0, 10, 0, 10, Some(DateTime.now()), None, 12347, 0, 10, SideCode.TowardsDigitizing, LinkStatus.UnChanged, projectId + 1, RoadType.PublicRoad, combGeom3, rwNumber + 2).copy(id = plId + 3, roadwayId = rwId + 3, linearLocationId = llId + 3)
      val newLink = dummyProjectLink(road, 4L, Track.Combined, Discontinuity.EndOfRoad, 0, 10, 0, 10, Some(DateTime.now()), None, 12348, 0, 10, SideCode.TowardsDigitizing, LinkStatus.New, projectId + 1, RoadType.PublicRoad, combGeom4, rwNumber + 3).copy(id = plId + 4, roadwayId = rwId + 4, linearLocationId = llId + 4)

      val (lc4, rw4): (LinearLocation, Roadway) = Seq(newLink).map(toRoadwayAndLinearLocation).head
      val rw3WithDiscontinuity = rw3WithId.copy(discontinuity = Discontinuity.Discontinuous, id = transferLink.roadwayId)
      val rw4WithId = rw4.copy(ely = 8L, startAddrMValue = 0, endAddrMValue = 10, id = newLink.roadwayId)

      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(any[BoundingRectangle], any[Seq[(Int, Int)]])).thenReturn(Seq(lc1, lc2, lc4))
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(any[Set[Long]], any[Boolean])).thenReturn(Seq(rw1WithId, rw2WithId, rw4WithId))

      buildTestDataForProject(Some(project2),
        Some(Seq(rw3WithDiscontinuity, rw4WithId)),
        Some(Seq(lc3.copy(id = transferLink.linearLocationId, roadwayNumber = transferLink.roadwayNumber),
          lc4.copy(id = newLink.linearLocationId, roadwayNumber = newLink.roadwayNumber))),
        Some(Seq(transferLink, newLink)))

      projectLinkDAO.moveProjectLinksToHistory(projectId + 1)

      val mappedAfterNewRoadwayNumber = projectLinkDAO.fetchProjectLinksChange(projectId + 1)
      roadAddressService.handleRoadwayPointsUpdate(TerminatingProjectChanges, mappedAfterNewRoadwayNumber)
      nodesAndJunctionsService.handleNodePointTemplates(TerminatingProjectChanges, combPLinks, mappedAfterNewRoadwayNumber)
      nodesAndJunctionsService.handleJunctionTemplates(TerminatingProjectChanges, combPLinks, mappedAfterNewRoadwayNumber)

      /*  Ending expiring data  */
      val terminatedJunctionsBeforeExpire = junctionDAO.fetchExpiredByRoadwayNumbers(templateRoadwayNumbers)
      terminatedJunctionsBeforeExpire count (_.endDate.isDefined) should be(0)
      terminatedJunctionsBeforeExpire count (_.validTo.isDefined) should be(0)

      val endDate = Some(project2.startDate.minusDays(1))

      nodesAndJunctionsService.expireObsoleteNodesAndJunctions(Seq(transferLink, newLink), endDate)

      val rwPoints = roadwayPointDAO.fetchByRoadwayNumbers(Seq(combLink1, combLink2, transferLink, newLink).map(_.roadwayNumber)).map(_.id)
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

  test("Test nodesAndJunctionsService.handleJunctionTemplates When creating new road parts that connects to other existing part in the beginning point of its geometry which link is EndOfRoad and ends in some link of this new roads in same road number Then junction template and junctions points should be handled/created properly." +
    "Test nodesAndJunctionsService.expireObsoleteNodesAndJunctions When changing the EndOfRoad link of last part to Discontinuous and creating a new one with EndOfRoad that does not connect to the same road Then the existing Junction and his points should be expired.") {
    runWithRollback {
      /*  |
          C3
          |
          v
          0|--C1-->|--C2-->|

          Notes:
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

      val combLink1 = dummyProjectLink(road, part1, Track.Combined, Discontinuity.Continuous, 0, 10, 0, 10, Some(DateTime.now()), None, 12345, 0, 10, SideCode.TowardsDigitizing, LinkStatus.Transfer, projectId, RoadType.PublicRoad, combGeom1, rwNumber).copy(id = plId, projectId = projectId, roadwayId = rwId, linearLocationId = llId)
      val combLink2 = dummyProjectLink(road, part2, Track.Combined, Discontinuity.Discontinuous, 0, 10, 0, 10, Some(DateTime.now()), None, 12346, 0, 10, SideCode.TowardsDigitizing, LinkStatus.Transfer, projectId, RoadType.PublicRoad, combGeom2, rwNumber + 1).copy(id = plId + 1, projectId = projectId, roadwayId = rwId + 1, linearLocationId = llId + 1)
      val combLink3 = dummyProjectLink(road, part3, Track.Combined, Discontinuity.EndOfRoad, 0, 10, 0, 10, Some(DateTime.now()), None, 12347, 0, 10, SideCode.TowardsDigitizing, LinkStatus.Transfer, projectId, RoadType.PublicRoad, combGeom3, rwNumber + 2).copy(id = plId + 2, projectId = projectId, roadwayId = rwId + 2, linearLocationId = llId + 2)

      val project = Project(projectId, ProjectState.Incomplete, "f", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
        "", Seq(), Seq(), None, None)

      val combPLinks = Seq(combLink1, combLink2)

      val (lc1, rw1): (LinearLocation, Roadway) = Seq(combLink1).map(toRoadwayAndLinearLocation).head
      val (lc2, rw2): (LinearLocation, Roadway) = Seq(combLink2).map(toRoadwayAndLinearLocation).head
      val (lc3, rw3): (LinearLocation, Roadway) = Seq(combLink3).map(toRoadwayAndLinearLocation).head
      val rw1WithId = rw1.copy(ely = 8L, startAddrMValue = 0, endAddrMValue = 10)
      val rw2WithId = rw2.copy(ely = 8L, startAddrMValue = 0, endAddrMValue = 10)
      val rw3WithId = rw3.copy(ely = 8L, startAddrMValue = 0, endAddrMValue = 10)

      buildTestDataForProject(Some(project), Some(Seq(rw1WithId, rw2WithId, rw3WithId)), Some(Seq(lc1, lc2, lc3)), Some(Seq(combLink1, combLink2)))

      val projectChanges = List(
        ProjectRoadwayChange(projectId, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(AddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, None, Some(PublicRoad), Some(Discontinuity.Continuous), Some(8L)),
            RoadwayChangeSection(Some(road), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(part1), endRoadPartNumber = Some(part1), startAddressM = Some(0L), endAddressM = Some(10L), Some(RoadType.PublicRoad), Some(Discontinuity.Continuous), Some(8L)),
            Discontinuity.Continuous, RoadType.PublicRoad, reversed = false, 1, 8)
          , DateTime.now, Some(0L)),
        ProjectRoadwayChange(projectId, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(AddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, None, Some(PublicRoad), Some(Discontinuity.Continuous), Some(8L)),
            RoadwayChangeSection(Some(road), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(part2), endRoadPartNumber = Some(part2), startAddressM = Some(0L), endAddressM = Some(10L), Some(RoadType.PublicRoad), Some(Discontinuity.Discontinuous), Some(8L)),
            Discontinuity.Discontinuous, RoadType.PublicRoad, reversed = false, 2, 8)
          , DateTime.now, Some(0L))
      )

      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(combGeom1.head, combGeom1.head), roadNumberLimits)).thenReturn(Seq(lc1, lc3))
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(Set(lc1.roadwayNumber, lc3.roadwayNumber), false)).thenReturn(Seq(rw1WithId, rw3WithId))
      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(combGeom1.last, combGeom1.last), roadNumberLimits)).thenReturn(Seq(lc1, lc2))
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(Set(lc1.roadwayNumber, lc2.roadwayNumber), false)).thenReturn(Seq(rw1WithId, rw2WithId))
      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(combGeom2.last, combGeom2.last), roadNumberLimits)).thenReturn(Seq())
      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(combGeom3.head, combGeom3.head), roadNumberLimits)).thenReturn(Seq())
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(Set.empty[Long], false)).thenReturn(Seq())

      val mappedReservedRoadwayNumbers = projectLinkDAO.fetchProjectLinksChange(projectId)
      roadAddressService.handleRoadwayPointsUpdate(projectChanges, mappedReservedRoadwayNumbers)
      nodesAndJunctionsService.handleNodePointTemplates(projectChanges, combPLinks, mappedReservedRoadwayNumbers)
      nodesAndJunctionsService.handleJunctionTemplates(projectChanges, combPLinks, mappedReservedRoadwayNumbers)

      val roadwayPoints = roadwayPointDAO.fetchByRoadwayNumbers((combPLinks :+ combLink3).map(_.roadwayNumber)).map(_.id)
      val junctionPointTemplates = junctionPointDAO.fetchByRoadwayPointIds(roadwayPoints)

      junctionPointTemplates.length should be(2)
      junctionPointTemplates.count(_.beforeAfter == BeforeAfter.Before) should be(1)
      junctionPointTemplates.count(_.beforeAfter == BeforeAfter.After) should be(1)
      val templateRoadwayNumbers = junctionPointTemplates.map(_.roadwayNumber).distinct
      val junctions = junctionDAO.fetchTemplatesByRoadwayNumbers(templateRoadwayNumbers)
      junctions.size should be(1)

      /*  Preparing expiring data

          |
          C3
          |
          v
          |--C1-->|--C2-->|--C4-->|

          Note:
            C: Combined track
       */
      val project2 = Project(projectId + 1, ProjectState.Incomplete, "ProjectNewEndOfRoadLinks", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
        "", Seq(), Seq(), None, None)
      val NewProjectChanges = List(
        ProjectRoadwayChange(projectId + 1, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(AddressChangeType.Unchanged,
            RoadwayChangeSection(Some(road), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(3l), endRoadPartNumber = Some(3l), startAddressM = Some(0L), endAddressM = Some(10L), Some(RoadType.PublicRoad), Some(Discontinuity.EndOfRoad), Some(8L)),
            RoadwayChangeSection(Some(road), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(3l), endRoadPartNumber = Some(3l), startAddressM = Some(0L), endAddressM = Some(10L), Some(RoadType.PublicRoad), Some(Discontinuity.Discontinuous), Some(8L)),
            Discontinuity.Discontinuous, RoadType.PublicRoad, reversed = false, 1, 8)
          , DateTime.now, Some(0L)),
        ProjectRoadwayChange(projectId + 1, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(AddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, None, Some(PublicRoad), Some(Discontinuity.Continuous), Some(8L)),
            RoadwayChangeSection(Some(road), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(4l), endRoadPartNumber = Some(4l), startAddressM = Some(0L), endAddressM = Some(10L), Some(RoadType.PublicRoad), Some(Discontinuity.EndOfRoad), Some(8L)),
            Discontinuity.EndOfRoad, RoadType.PublicRoad, reversed = false, 2, 8)
          , DateTime.now, Some(0L))
      )

      linearLocationDAO.expireByRoadwayNumbers(Set(combLink3.roadwayNumber))
      roadwayDAO.expireHistory(Set(combLink3.roadwayId))
      projectLinkDAO.moveProjectLinksToHistory(projectId)
      val combGeom4 = Seq(Point(20.0, 0.0), Point(30.0, 0.0))

      val transferLink = dummyProjectLink(road, 3l, Track.Combined, Discontinuity.Discontinuous, 0, 10, 0, 10, Some(DateTime.now()), None, 12347, 0, 10, SideCode.TowardsDigitizing, LinkStatus.UnChanged, projectId + 1, RoadType.PublicRoad, combGeom3, rwNumber + 2).copy(id = plId + 3, roadwayId = rwId + 3, linearLocationId = llId + 3)
      val newLink = dummyProjectLink(road, 4l, Track.Combined, Discontinuity.EndOfRoad, 0, 10, 0, 10, Some(DateTime.now()), None, 12348, 0, 10, SideCode.TowardsDigitizing, LinkStatus.New, projectId + 1, RoadType.PublicRoad, combGeom4, rwNumber + 3).copy(id = plId + 4, roadwayId = rwId + 4, linearLocationId = llId + 4)

      val (lc4, rw4): (LinearLocation, Roadway) = Seq(newLink).map(toRoadwayAndLinearLocation).head
      val rw3WithDiscontinuity = rw3WithId.copy(discontinuity = Discontinuity.Discontinuous, id = transferLink.roadwayId, roadwayNumber = transferLink.roadwayNumber)
      val rw4WithId = rw4.copy(ely = 8L, startAddrMValue = 0, endAddrMValue = 10, id = newLink.roadwayId, roadwayNumber = newLink.roadwayNumber)

      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(Set(combLink1.roadwayNumber, combLink2.roadwayNumber, transferLink.roadwayNumber, newLink.roadwayNumber), false)).thenReturn(Seq(rw1WithId, rw2WithId, rw3WithDiscontinuity, rw4WithId))
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(Set(combLink1.roadwayNumber), false)).thenReturn(Seq(rw1WithId))
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(Set(combLink2.roadwayNumber), false)).thenReturn(Seq(rw2WithId))
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(Set(transferLink.roadwayNumber), false)).thenReturn(Seq(rw3WithDiscontinuity))
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(Set(newLink.roadwayNumber), false)).thenReturn(Seq(rw4WithId))

      buildTestDataForProject(Some(project2),
        Some(Seq(rw3WithDiscontinuity, rw4WithId)),
        Some(Seq(lc3.copy(id = transferLink.linearLocationId, roadwayNumber = transferLink.roadwayNumber),
          lc4.copy(id = newLink.linearLocationId, roadwayNumber = newLink.roadwayNumber))),
        Some(Seq(transferLink, newLink)))

      val mappedAfterNewRoadwayNumber = projectLinkDAO.fetchProjectLinksChange(projectId + 1)
      projectLinkDAO.moveProjectLinksToHistory(projectId + 1)

      roadAddressService.handleRoadwayPointsUpdate(NewProjectChanges, mappedAfterNewRoadwayNumber)
      nodesAndJunctionsService.handleNodePointTemplates(NewProjectChanges, combPLinks, mappedAfterNewRoadwayNumber)
      nodesAndJunctionsService.handleJunctionTemplates(NewProjectChanges, combPLinks, mappedAfterNewRoadwayNumber)

      /*  Ending expiring data  */
      val terminatedJunctionsBeforeExpire = junctionDAO.fetchExpiredByRoadwayNumbers(templateRoadwayNumbers)
      terminatedJunctionsBeforeExpire.length should be(1)
      terminatedJunctionsBeforeExpire count (_.endDate.isDefined) should be(0)
      terminatedJunctionsBeforeExpire count (_.validTo.isDefined) should be(0)
      val endDate = Some(project2.startDate.minusDays(1))

      nodesAndJunctionsService.expireObsoleteNodesAndJunctions(Seq(combLink1, combLink2, transferLink, newLink), endDate)

      val rwPoints = roadwayPointDAO.fetchByRoadwayNumbers(Seq(combLink1, combLink2, transferLink, newLink).map(_.roadwayNumber)).map(_.id)
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

  test("Test nodesAndJunctionsService.handleJunctionTemplates When creating new road parts that connects to other existing part in the ending point of its geometry which link is EndOfRoad and ends in some link of this new roads in same road number Then junction template and junctions points should be handled/created properly." +
    "Test nodesAndJunctionsService.expireObsoleteNodesAndJunctions When changing the EndOfRoad link of last part to Continuous and creating a new one with EndOfRoad that does not connect to the same road Then the existing Junction and his points should be expired.") {
    runWithRollback {
      /*                    |
                            C3
                            |
                            v
           |--C1-->|--C2-->|0

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
      val combGeom3 = Seq(Point(20.0, 10.0), Point(20.0, 0.0))

      val combLink1 = dummyProjectLink(road, part1, Track.Combined, Discontinuity.Continuous, 0, 10, 0, 10, Some(DateTime.now()), None, 12345, 0, 10, SideCode.TowardsDigitizing, LinkStatus.Transfer, projectId, RoadType.PublicRoad, combGeom1, rwNumber).copy(id = plId, projectId = projectId, roadwayId = rwId, linearLocationId = llId)
      val combLink2 = dummyProjectLink(road, part2, Track.Combined, Discontinuity.Discontinuous, 0, 10, 0, 10, Some(DateTime.now()), None, 12346, 0, 10, SideCode.TowardsDigitizing, LinkStatus.Transfer, projectId, RoadType.PublicRoad, combGeom2, rwNumber + 1).copy(id = plId + 1, projectId = projectId, roadwayId = rwId + 1, linearLocationId = llId + 1)
      val combLink3 = dummyProjectLink(road, part3, Track.Combined, Discontinuity.EndOfRoad, 0, 10, 0, 10, Some(DateTime.now()), None, 12347, 0, 10, SideCode.TowardsDigitizing, LinkStatus.Transfer, projectId, RoadType.PublicRoad, combGeom3, rwNumber + 2).copy(id = plId + 2, projectId = projectId, roadwayId = rwId + 2, linearLocationId = llId + 2)

      val project = Project(projectId, ProjectState.Incomplete, "f", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
        "", Seq(), Seq(), None, None)

      val combPLinks = Seq(combLink1, combLink2)

      val (lc1, rw1): (LinearLocation, Roadway) = Seq(combLink1).map(toRoadwayAndLinearLocation).head
      val (lc2, rw2): (LinearLocation, Roadway) = Seq(combLink2).map(toRoadwayAndLinearLocation).head
      val (lc3, rw3): (LinearLocation, Roadway) = Seq(combLink3).map(toRoadwayAndLinearLocation).head
      val rw1WithId = rw1.copy(ely = 8L, startAddrMValue = 0, endAddrMValue = 10)
      val rw2WithId = rw2.copy(ely = 8L, startAddrMValue = 0, endAddrMValue = 10)
      val rw3WithId = rw3.copy(ely = 8L, startAddrMValue = 0, endAddrMValue = 10)

      buildTestDataForProject(Some(project), Some(Seq(rw1WithId, rw2WithId, rw3WithId)), Some(Seq(lc1, lc2, lc3)), Some(Seq(combLink1, combLink2)))

      val projectChanges = List(
        //  combined
        ProjectRoadwayChange(projectId, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(AddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, None, Some(PublicRoad), Some(Discontinuity.Continuous), Some(8L)),
            RoadwayChangeSection(Some(road), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(part1), endRoadPartNumber = Some(part1), startAddressM = Some(0L), endAddressM = Some(10L), Some(RoadType.PublicRoad), Some(Discontinuity.Continuous), Some(8L)),
            Discontinuity.Continuous, RoadType.PublicRoad, reversed = false, 1, 8)
          , DateTime.now, Some(0L)),
        ProjectRoadwayChange(projectId, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(AddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, None, Some(PublicRoad), Some(Discontinuity.Continuous), Some(8L)),
            RoadwayChangeSection(Some(road), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(part2), endRoadPartNumber = Some(part2), startAddressM = Some(0L), endAddressM = Some(10L), Some(RoadType.PublicRoad), Some(Discontinuity.Discontinuous), Some(8L)),
            Discontinuity.Discontinuous, RoadType.PublicRoad, reversed = false, 2, 8)
          , DateTime.now, Some(0L))
      )

      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(combGeom1.head, combGeom1.head), roadNumberLimits)).thenReturn(Seq())
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(Set.empty[Long], false)).thenReturn(Seq())
      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(combGeom1.last, combGeom1.last), roadNumberLimits)).thenReturn(Seq(lc1, lc2))
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(Set(lc1.roadwayNumber, lc2.roadwayNumber), false)).thenReturn(Seq(rw1WithId, rw2WithId))
      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(combGeom2.last, combGeom2.last), roadNumberLimits)).thenReturn(Seq(lc2, lc3))
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(Set(lc2.roadwayNumber, lc3.roadwayNumber), false)).thenReturn(Seq(rw2WithId, rw3WithId))
      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(combGeom3.head, combGeom3.head), roadNumberLimits)).thenReturn(Seq())

      val mappedReservedRoadwayNumbers = projectLinkDAO.fetchProjectLinksChange(projectId)
      roadAddressService.handleRoadwayPointsUpdate(projectChanges, mappedReservedRoadwayNumbers)
      nodesAndJunctionsService.handleNodePointTemplates(projectChanges, combPLinks, mappedReservedRoadwayNumbers)
      nodesAndJunctionsService.handleJunctionTemplates(projectChanges, combPLinks, mappedReservedRoadwayNumbers)

      val roadwayPoints = roadwayPointDAO.fetchByRoadwayNumbers((combPLinks :+ combLink3).map(_.roadwayNumber)).map(_.id)
      val junctionPointTemplates = junctionPointDAO.fetchByRoadwayPointIds(roadwayPoints)

      junctionPointTemplates.length should be(2)
      junctionPointTemplates.count(_.beforeAfter == BeforeAfter.Before) should be(2)

      val templateRoadwayNumbers = junctionPointTemplates.map(_.roadwayNumber).distinct
      val junctions = junctionDAO.fetchTemplatesByRoadwayNumbers(templateRoadwayNumbers)
      junctions.size should be(1)

      /*  Preparing expiring data

                                  |
                                  C3
                                  |
                                  v
          |--C1-->|--C2-->|--C4-->|

          Note:
            C: Combined track
       */
      val project2 = Project(projectId + 1, ProjectState.Incomplete, "ProjectNewEndOfRoadLinks", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
        "", Seq(), Seq(), None, None)
      val TerminatingProjectChanges = List(
        ProjectRoadwayChange(projectId + 1, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(AddressChangeType.Unchanged,
            RoadwayChangeSection(Some(road), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(3l), endRoadPartNumber = Some(3l), startAddressM = Some(0L), endAddressM = Some(10L), Some(RoadType.PublicRoad), Some(Discontinuity.EndOfRoad), Some(8L)),
            RoadwayChangeSection(Some(road), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(3l), endRoadPartNumber = Some(3l), startAddressM = Some(0L), endAddressM = Some(10L), Some(RoadType.PublicRoad), Some(Discontinuity.Discontinuous), Some(8L)),
            Discontinuity.Discontinuous, RoadType.PublicRoad, reversed = false, 1, 8)
          , DateTime.now, Some(0L)),
        ProjectRoadwayChange(projectId + 1, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(AddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, None, Some(PublicRoad), Some(Discontinuity.Continuous), Some(8L)),
            RoadwayChangeSection(Some(road), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(4l), endRoadPartNumber = Some(4l), startAddressM = Some(0L), endAddressM = Some(10L), Some(RoadType.PublicRoad), Some(Discontinuity.EndOfRoad), Some(8L)),
            Discontinuity.EndOfRoad, RoadType.PublicRoad, reversed = false, 2, 8)
          , DateTime.now, Some(0L))
      )

      linearLocationDAO.expireByRoadwayNumbers(Set(combLink3.roadwayNumber))
      roadwayDAO.expireHistory(Set(combLink3.roadwayId))
      projectLinkDAO.moveProjectLinksToHistory(projectId)
      val combGeom4 = Seq(Point(20.0, 0.0), Point(30.0, 0.0))

      val transferLink = dummyProjectLink(road, 3l, Track.Combined, Discontinuity.Discontinuous, 0, 10, 0, 10, Some(DateTime.now()), None, 12347, 0, 10, SideCode.TowardsDigitizing, LinkStatus.UnChanged, projectId + 1, RoadType.PublicRoad, combGeom3, rwNumber + 2).copy(id = plId + 3, roadwayId = rwId + 3, linearLocationId = llId + 3)
      val newLink = dummyProjectLink(road, 4l, Track.Combined, Discontinuity.EndOfRoad, 0, 10, 0, 10, Some(DateTime.now()), None, 12348, 0, 10, SideCode.TowardsDigitizing, LinkStatus.New, projectId + 1, RoadType.PublicRoad, combGeom4, rwNumber + 3).copy(id = plId + 4, roadwayId = rwId + 4, linearLocationId = llId + 4)

      val (lc4, rw4): (LinearLocation, Roadway) = Seq(newLink).map(toRoadwayAndLinearLocation).head
      val rw3WithDiscontinuity = rw3WithId.copy(discontinuity = Discontinuity.Discontinuous, id = transferLink.roadwayId, roadwayNumber = transferLink.roadwayNumber)
      val rw4WithId = rw4.copy(ely = 8L, startAddrMValue = 0, endAddrMValue = 10, id = newLink.roadwayId, roadwayNumber = newLink.roadwayNumber)

      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(Set(combLink1.roadwayNumber, combLink2.roadwayNumber, transferLink.roadwayNumber, newLink.roadwayNumber), false)).thenReturn(Seq(rw1WithId, rw2WithId, rw3WithDiscontinuity, rw4WithId))
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(Set(combLink1.roadwayNumber), false)).thenReturn(Seq(rw1WithId))
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(Set(combLink2.roadwayNumber), false)).thenReturn(Seq(rw2WithId))
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(Set(transferLink.roadwayNumber), false)).thenReturn(Seq(rw3WithDiscontinuity))
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(Set(newLink.roadwayNumber), false)).thenReturn(Seq(rw4WithId))

      buildTestDataForProject(Some(project2),
        Some(Seq(rw3WithDiscontinuity, rw4WithId)),
        Some(Seq(lc3.copy(id = transferLink.linearLocationId, roadwayNumber = transferLink.roadwayNumber),
          lc4.copy(id = newLink.linearLocationId, roadwayNumber = newLink.roadwayNumber))),
        Some(Seq(transferLink, newLink)))

      projectLinkDAO.moveProjectLinksToHistory(projectId + 1)

      val mappedAfterNewRoadwayNumber = projectLinkDAO.fetchProjectLinksChange(projectId + 1)
      roadAddressService.handleRoadwayPointsUpdate(TerminatingProjectChanges, mappedAfterNewRoadwayNumber)
      nodesAndJunctionsService.handleNodePointTemplates(TerminatingProjectChanges, combPLinks, mappedAfterNewRoadwayNumber)
      nodesAndJunctionsService.handleJunctionTemplates(TerminatingProjectChanges, combPLinks, mappedAfterNewRoadwayNumber)

      /*  Ending expiring data  */
      val terminatedJunctionsBeforeExpire = junctionDAO.fetchExpiredByRoadwayNumbers(templateRoadwayNumbers)
      terminatedJunctionsBeforeExpire count (_.endDate.isDefined) should be(0)
      terminatedJunctionsBeforeExpire count (_.validTo.isDefined) should be(0)
      val endDate = Some(project2.startDate.minusDays(1))

      nodesAndJunctionsService.expireObsoleteNodesAndJunctions(Seq(transferLink, newLink), endDate)

      val rwPoints = roadwayPointDAO.fetchByRoadwayNumbers(Seq(combLink1, combLink2, transferLink, newLink).map(_.roadwayNumber)).map(_.id)
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

  test("Test nodesAndJunctionsService.handleJunctionTemplates When creating one road part that intersects itself and still all links are continuous Then junction template and junctions points should be handled/created properly." +
    "Test nodesAndJunctionsService.expireObsoleteNodesAndJunctions When expiring the 2 last links that will make the road not intersecting itself Then the existing Junction and its Junction points should be expired.") {
    runWithRollback {
      /*
             ^
             |
             |
             C5
             |
     |--C1-->0|
           ^ |
          | C2
        C4   |
       |     v
     |<--C3--|

            Note:
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

      val combLink1 = dummyProjectLink(road, part, Track.Combined, Discontinuity.Continuous, 0, 5, 0, 5, Some(DateTime.now()), None, 12345, 0, 5, SideCode.TowardsDigitizing, LinkStatus.New, projectId, RoadType.PublicRoad, combGeom1, rwNumber).copy(id = plId, projectId = projectId, roadwayId = rwId, linearLocationId = llId)
      val combLink2 = dummyProjectLink(road, part, Track.Combined, Discontinuity.Continuous, 5, 10, 5, 10, Some(DateTime.now()), None, 12346, 0, 5, SideCode.TowardsDigitizing, LinkStatus.New, projectId, RoadType.PublicRoad, combGeom2, rwNumber).copy(id = plId + 1, projectId = projectId, roadwayId = rwId, linearLocationId = llId + 1)
      val combLink3 = dummyProjectLink(road, part, Track.Combined, Discontinuity.Continuous, 10, 15, 10, 15, Some(DateTime.now()), None, 12347, 0, 5, SideCode.TowardsDigitizing, LinkStatus.New, projectId, RoadType.PublicRoad, combGeom3, rwNumber).copy(id = plId + 2, projectId = projectId, roadwayId = rwId, linearLocationId = llId + 2)
      val combLink4 = dummyProjectLink(road, part, Track.Combined, Discontinuity.Continuous, 15, 20, 15, 20, Some(DateTime.now()), None, 12348, 0, 5, SideCode.TowardsDigitizing, LinkStatus.New, projectId, RoadType.PublicRoad, combGeom4, rwNumber).copy(id = plId + 3, projectId = projectId, roadwayId = rwId, linearLocationId = llId + 3)
      val combLink5 = dummyProjectLink(road, part, Track.Combined, Discontinuity.EndOfRoad, 20, 25, 20, 25, Some(DateTime.now()), None, 12349, 0, 5, SideCode.TowardsDigitizing, LinkStatus.New, projectId, RoadType.PublicRoad, combGeom5, rwNumber).copy(id = plId + 4, projectId = projectId, roadwayId = rwId, linearLocationId = llId + 4)

      val project = Project(projectId, ProjectState.Incomplete, "f", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
        "", Seq(), Seq(), None, None)

      val combPLinks = Seq(combLink1, combLink2, combLink3, combLink4, combLink5)

      val (lc1, rw1): (LinearLocation, Roadway) = Seq(combLink1).map(toRoadwayAndLinearLocation).head
      val (lc2, rw2): (LinearLocation, Roadway) = Seq(combLink2).map(toRoadwayAndLinearLocation).head
      val (lc3, rw3): (LinearLocation, Roadway) = Seq(combLink3).map(toRoadwayAndLinearLocation).head
      val (lc4, rw4): (LinearLocation, Roadway) = Seq(combLink4).map(toRoadwayAndLinearLocation).head
      val (lc5, rw5): (LinearLocation, Roadway) = Seq(combLink5).map(toRoadwayAndLinearLocation).head
      val rw1WithId = rw1.copy(ely = 8L, startAddrMValue = 0, endAddrMValue = 25)
      val orderedcll1 = lc1.copy(orderNumber = 1)
      val orderedcll2 = lc2.copy(orderNumber = 2)
      val orderedcll3 = lc3.copy(orderNumber = 3)
      val orderedcll4 = lc4.copy(orderNumber = 4)
      val orderedcll5 = lc5.copy(orderNumber = 5)

      buildTestDataForProject(Some(project), Some(Seq(rw1WithId)), Some(Seq(orderedcll1, orderedcll2, orderedcll3, orderedcll4, orderedcll5)), Some(combPLinks))

      val projectChanges = List(
        ProjectRoadwayChange(projectId, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(AddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, None, Some(PublicRoad), Some(Discontinuity.Continuous), Some(8L)),
            RoadwayChangeSection(Some(road), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(part), endRoadPartNumber = Some(part), startAddressM = Some(0L), endAddressM = Some(20L), Some(RoadType.PublicRoad), Some(Discontinuity.Continuous), Some(8L)),
            Discontinuity.Continuous, RoadType.PublicRoad, reversed = false, 1, 8)
          , DateTime.now, Some(0L)),
        ProjectRoadwayChange(projectId, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(AddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, None, Some(PublicRoad), Some(Discontinuity.Continuous), Some(8L)),
            RoadwayChangeSection(Some(road), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(part), endRoadPartNumber = Some(part), startAddressM = Some(20L), endAddressM = Some(25L), Some(RoadType.PublicRoad), Some(Discontinuity.EndOfRoad), Some(8L)),
            Discontinuity.EndOfRoad, RoadType.PublicRoad, reversed = false, 2, 8)
          , DateTime.now, Some(0L))
      )

      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(any[BoundingRectangle], any[Seq[(Int, Int)]])).thenReturn(Seq(orderedcll1, orderedcll2, orderedcll3, orderedcll4, orderedcll5))
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(any[Set[Long]], any[Boolean])).thenReturn(Seq(rw1WithId))

      val mappedReservedRoadwayNumbers = projectLinkDAO.fetchProjectLinksChange(projectId)
      roadAddressService.handleRoadwayPointsUpdate(projectChanges, mappedReservedRoadwayNumbers)
      nodesAndJunctionsService.handleNodePointTemplates(projectChanges, combPLinks, mappedReservedRoadwayNumbers)
      nodesAndJunctionsService.handleJunctionTemplates(projectChanges, combPLinks, mappedReservedRoadwayNumbers)

      val roadwayPoints = roadwayPointDAO.fetchByRoadwayNumbers(combPLinks.map(_.roadwayNumber)).map(_.id)

      val junctionPointTemplates = junctionPointDAO.fetchByRoadwayPointIds(roadwayPoints)
      junctionPointTemplates.length should be(4)
      junctionPointTemplates.count(_.beforeAfter == BeforeAfter.Before) should be(2)
      junctionPointTemplates.count(_.beforeAfter == BeforeAfter.After) should be(2)

      val templateRoadwayNumbers = junctionPointTemplates.map(_.roadwayNumber).distinct

      val junctions = junctionDAO.fetchTemplatesByRoadwayNumbers(templateRoadwayNumbers)
      junctions.size should be(1)

      /*  VIITE-2068  Expiring process was expiring those valid junctions that were previously created  */
      nodesAndJunctionsService.expireObsoleteNodesAndJunctions(combPLinks, Some(project.startDate.minusDays(1)), username = project.createdBy)

      val shouldExistJunctionPoints = junctionPointDAO.fetchByRoadwayPointIds(roadwayPoints)
      shouldExistJunctionPoints.length should be(4)
      shouldExistJunctionPoints.count(_.beforeAfter == BeforeAfter.Before) should be(2)
      shouldExistJunctionPoints.count(_.beforeAfter == BeforeAfter.After) should be(2)

      val shouldExistJunctionTemplate = junctionDAO.fetchTemplatesByRoadwayNumbers(shouldExistJunctionPoints.map(_.roadwayNumber).distinct)
      shouldExistJunctionTemplate.size should be(1)

      /*  Preparing expiring data */
      val project2 = Project(projectId + 1, ProjectState.Incomplete, "ProjectTerminatedLinks", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
        "", Seq(), Seq(), None, None)
      val TerminatingProjectChanges = List(
        ProjectRoadwayChange(projectId + 1, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(AddressChangeType.Unchanged,
            RoadwayChangeSection(Some(road), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(part), endRoadPartNumber = Some(part), startAddressM = Some(0L), endAddressM = Some(10L), Some(RoadType.PublicRoad), Some(Discontinuity.Continuous), Some(8L)),
            RoadwayChangeSection(Some(road), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(part), endRoadPartNumber = Some(part), startAddressM = Some(0L), endAddressM = Some(10L), Some(RoadType.PublicRoad), Some(Discontinuity.Continuous), Some(8L)),
            Discontinuity.Continuous, RoadType.PublicRoad, reversed = false, 1, 8)
          , DateTime.now, Some(0L)),
        ProjectRoadwayChange(projectId + 1, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(AddressChangeType.Unchanged,
            RoadwayChangeSection(Some(road), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(part), endRoadPartNumber = Some(part), startAddressM = Some(10L), endAddressM = Some(15L), Some(RoadType.PublicRoad), Some(Discontinuity.EndOfRoad), Some(8L)),
            RoadwayChangeSection(Some(road), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(part), endRoadPartNumber = Some(part), startAddressM = Some(10L), endAddressM = Some(15L), Some(RoadType.PublicRoad), Some(Discontinuity.EndOfRoad), Some(8L)),
            Discontinuity.EndOfRoad, RoadType.PublicRoad, reversed = false, 2, 8)
          , DateTime.now, Some(0L)),
        ProjectRoadwayChange(projectId, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(AddressChangeType.Termination,
            RoadwayChangeSection(Some(road), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(part), endRoadPartNumber = Some(part), startAddressM = Some(15L), endAddressM = Some(20L), Some(RoadType.PublicRoad), Some(Discontinuity.Continuous), Some(8L)),
            RoadwayChangeSection(None, None, None, None, None, None, Some(PublicRoad), Some(Discontinuity.Continuous), Some(8L)),
            Discontinuity.Continuous, RoadType.PublicRoad, reversed = false, 3, 8)
          , DateTime.now, Some(0L)),
        ProjectRoadwayChange(projectId, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(AddressChangeType.Termination,
            RoadwayChangeSection(Some(road), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(part), endRoadPartNumber = Some(part), startAddressM = Some(20L), endAddressM = Some(25L), Some(RoadType.PublicRoad), Some(Discontinuity.EndOfRoad), Some(8L)),
            RoadwayChangeSection(None, None, None, None, None, None, Some(PublicRoad), Some(Discontinuity.EndOfRoad), Some(8L)),
            Discontinuity.EndOfRoad, RoadType.PublicRoad, reversed = false, 4, 8)
          , DateTime.now, Some(0L))

      )

      linearLocationDAO.expireByRoadwayNumbers(Set(combLink4.roadwayNumber, combLink5.roadwayNumber))
      roadwayDAO.expireHistory(Set(combLink4.roadwayId, combLink5.roadwayId))
      projectLinkDAO.moveProjectLinksToHistory(projectId)
      val unchangedLink1 = dummyProjectLink(road, part, Track.Combined, Discontinuity.Continuous, 0, 5, 0, 5, Some(DateTime.now()), None, 12345, 0, 5, SideCode.TowardsDigitizing, LinkStatus.UnChanged, projectId + 1, RoadType.PublicRoad, combGeom1, rwNumber + 1).copy(id = plId + 5, roadwayId = rwId + 1, linearLocationId = llId + 5)
      val unchangedLink2 = dummyProjectLink(road, part, Track.Combined, Discontinuity.Continuous, 5, 10, 5, 10, Some(DateTime.now()), None, 12346, 0, 5, SideCode.TowardsDigitizing, LinkStatus.UnChanged, projectId + 1, RoadType.PublicRoad, combGeom2, rwNumber + 1).copy(id = plId + 6, roadwayId = rwId + 1, linearLocationId = llId + 6)
      val unchangedLink3 = dummyProjectLink(road, part, Track.Combined, Discontinuity.EndOfRoad, 10, 15, 10, 15, Some(DateTime.now()), None, 12347, 0, 5, SideCode.TowardsDigitizing, LinkStatus.UnChanged, projectId + 1, RoadType.PublicRoad, combGeom3, rwNumber + 1).copy(id = plId + 7, roadwayId = rwId + 1, linearLocationId = llId + 7)
      val terminatedLink4 = dummyProjectLink(road, part, Track.Combined, Discontinuity.Continuous, 15, 20, 15, 20, Some(DateTime.now()), None, 12348, 0, 5, SideCode.TowardsDigitizing, LinkStatus.Terminated, projectId + 1, RoadType.PublicRoad, combGeom4, rwNumber + 2).copy(id = plId + 8, roadwayId = rwId + 2, linearLocationId = llId + 8)
      val terminatedLink5 = dummyProjectLink(road, part, Track.Combined, Discontinuity.EndOfRoad, 20, 25, 20, 25, Some(DateTime.now()), None, 12349, 0, 5, SideCode.TowardsDigitizing, LinkStatus.Terminated, projectId + 1, RoadType.PublicRoad, combGeom5, rwNumber + 2).copy(id = plId + 9, roadwayId = rwId + 2, linearLocationId = llId + 9)

      buildTestDataForProject(Some(project2),
        Some(Seq(rw1WithId.copy(id = unchangedLink1.roadwayId, endAddrMValue = 15),
          rw1WithId.copy(id = terminatedLink4.roadwayId, endAddrMValue = 10, endDate = Some(DateTime.now())))),
        Some(Seq(orderedcll1.copy(id = unchangedLink1.linearLocationId, roadwayNumber = unchangedLink1.roadwayNumber), orderedcll2.copy(id = unchangedLink2.linearLocationId, roadwayNumber = unchangedLink2.roadwayNumber),
          orderedcll3.copy(id = unchangedLink3.linearLocationId, roadwayNumber = unchangedLink3.roadwayNumber),
          orderedcll4.copy(id = terminatedLink4.linearLocationId, roadwayNumber = terminatedLink4.roadwayNumber),
          orderedcll5.copy(id = terminatedLink5.linearLocationId, roadwayNumber = terminatedLink5.roadwayNumber))),
        Some(Seq(unchangedLink1, unchangedLink2, unchangedLink3, terminatedLink4, terminatedLink5)))

      projectLinkDAO.moveProjectLinksToHistory(projectId + 1)

      val mappedAfterTerminationRoadwayNumbers = projectLinkDAO.fetchProjectLinksChange(projectId + 1)
      roadAddressService.handleRoadwayPointsUpdate(TerminatingProjectChanges, mappedAfterTerminationRoadwayNumbers)
      nodesAndJunctionsService.handleNodePointTemplates(TerminatingProjectChanges, combPLinks, mappedAfterTerminationRoadwayNumbers)
      nodesAndJunctionsService.handleJunctionTemplates(TerminatingProjectChanges, combPLinks, mappedAfterTerminationRoadwayNumbers)

      /*  Ending expiring data  */
      val terminatedJunctionsBeforeExpire = junctionDAO.fetchExpiredByRoadwayNumbers(templateRoadwayNumbers)
      terminatedJunctionsBeforeExpire count (_.endDate.isDefined) should be(0)
      terminatedJunctionsBeforeExpire count (_.validTo.isDefined) should be(0)
      val endDate = Some(project2.startDate.minusDays(1))
      val terminatedLink1 = combLink4.copy(endDate = endDate, status = LinkStatus.Terminated)
      val terminatedLink2 = combLink5.copy(endDate = endDate, status = LinkStatus.Terminated)

      nodesAndJunctionsService.expireObsoleteNodesAndJunctions(Seq(combLink1, combLink2, combLink3, terminatedLink1, terminatedLink2), endDate)

      val rwPoints = roadwayPointDAO.fetchByRoadwayNumbers(Seq(combLink1, combLink2, combLink3, terminatedLink1, terminatedLink2).map(_.roadwayNumber)).map(_.id)
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

      val combLink1 = dummyProjectLink(road999, part1, Track.Combined, Discontinuity.Continuous, 0, 10, 0, 10, Some(DateTime.now()), None, 12345, 0, 10, SideCode.TowardsDigitizing, LinkStatus.New, projectId, RoadType.PublicRoad, combGeom1, rwNumber).copy(id = plId, projectId = projectId, roadwayId = rwId, linearLocationId = llId)
      val combLink2 = dummyProjectLink(road999, part1, Track.Combined, Discontinuity.Discontinuous, 10, 20, 10, 20, Some(DateTime.now()), None, 12346, 0, 10, SideCode.TowardsDigitizing, LinkStatus.New, projectId, RoadType.PublicRoad, combGeom2, rwNumber).copy(id = plId + 1, projectId = projectId, roadwayId = rwId, linearLocationId = llId + 1)
      val combLink3 = dummyProjectLink(road1000, part1, Track.Combined, Discontinuity.EndOfRoad, 0, 10, 0, 10, Some(DateTime.now()), None, 12347, 0, 10, SideCode.TowardsDigitizing, LinkStatus.New, projectId, RoadType.PublicRoad, combGeom3, rwNumber + 1).copy(id = plId + 2, projectId = projectId, roadwayId = rwId + 1, linearLocationId = llId + 2)

      val project = Project(projectId, ProjectState.Incomplete, "f", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
        "", Seq(), Seq(), None, None)

      val combPLinks = Seq(combLink1, combLink2)

      val (lc1, rw1): (LinearLocation, Roadway) = Seq(combLink1).map(toRoadwayAndLinearLocation).head
      val (lc2, rw2): (LinearLocation, Roadway) = Seq(combLink2).map(toRoadwayAndLinearLocation).head
      val (lc3, rw3): (LinearLocation, Roadway) = Seq(combLink3).map(toRoadwayAndLinearLocation).head
      val rw1WithId = rw1.copy(ely = 8L, startAddrMValue = 0, endAddrMValue = 20)
      val rw2WithId = rw3.copy(ely = 8L, startAddrMValue = 0, endAddrMValue = 10)
      val orderedlc1 = lc1.copy(orderNumber = 1)
      val orderedlc2 = lc2.copy(orderNumber = 2)
      val orderedlc3 = lc3.copy(orderNumber = 1)

      buildTestDataForProject(Some(project), Some(Seq(rw1WithId, rw2WithId)), Some(Seq(orderedlc1, orderedlc2, orderedlc3)), Some(Seq(combLink1, combLink2, combLink3)))

      val projectChanges = List(
        //Combined
        ProjectRoadwayChange(projectId, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(AddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, None, Some(PublicRoad), Some(Discontinuity.Continuous), Some(8L)),
            RoadwayChangeSection(Some(road999), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(part1), endRoadPartNumber = Some(part1), startAddressM = Some(0L), endAddressM = Some(20L), Some(RoadType.PublicRoad), Some(Discontinuity.EndOfRoad), Some(8L)),
            Discontinuity.EndOfRoad, RoadType.PublicRoad, reversed = false, 1, 8)
          , DateTime.now, Some(0L)),
        ProjectRoadwayChange(projectId, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(AddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, None, Some(PublicRoad), Some(Discontinuity.EndOfRoad), Some(8L)),
            RoadwayChangeSection(Some(road1000), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(part1), endRoadPartNumber = Some(part1), startAddressM = Some(0L), endAddressM = Some(10L), Some(RoadType.PublicRoad), Some(Discontinuity.EndOfRoad), Some(8L)),
            Discontinuity.EndOfRoad, RoadType.PublicRoad, reversed = false, 2, 8)
          , DateTime.now, Some(0L))
      )

      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(any[BoundingRectangle], any[Seq[(Int, Int)]])).thenReturn(Seq(orderedlc1, orderedlc2, orderedlc3))
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(any[Set[Long]], any[Boolean])).thenReturn(Seq(rw1WithId, rw2WithId))

      val mappedReservedRoadwayNumbers = projectLinkDAO.fetchProjectLinksChange(projectId)
      roadAddressService.handleRoadwayPointsUpdate(projectChanges, mappedReservedRoadwayNumbers)
      nodesAndJunctionsService.handleNodePointTemplates(projectChanges, combPLinks, mappedReservedRoadwayNumbers)
      nodesAndJunctionsService.handleJunctionTemplates(projectChanges, combPLinks, mappedReservedRoadwayNumbers)

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
          RoadwayChangeInfo(AddressChangeType.Termination,
            RoadwayChangeSection(Some(road1000), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(part1), endRoadPartNumber = Some(part1), startAddressM = Some(0L), endAddressM = Some(10L), Some(RoadType.PublicRoad), Some(Discontinuity.EndOfRoad), Some(8L)),
            RoadwayChangeSection(None, None, None, None, None, None, Some(PublicRoad), Some(Discontinuity.Continuous), Some(8L)),
            Discontinuity.EndOfRoad, RoadType.PublicRoad, reversed = false, 1, 8)
          , DateTime.now, Some(0L))
      )

      linearLocationDAO.expireByRoadwayNumbers(Set(combLink3.roadwayNumber))
      roadwayDAO.expireHistory(Set(combLink3.roadwayId))
      projectLinkDAO.moveProjectLinksToHistory(projectId)
      val combGeom4 = Seq(Point(20.0, 0.0), Point(30.0, 0.0))

      val terminatedLink = dummyProjectLink(road1000, part1, Track.Combined, Discontinuity.EndOfRoad, 0, 10, 0, 10, Some(DateTime.now()), None, 12347, 0, 10, SideCode.TowardsDigitizing, LinkStatus.Terminated, projectId + 1, RoadType.PublicRoad, combGeom4, rwNumber + 1).copy(id = plId + 3, roadwayId = rwId + 2, linearLocationId = llId + 3)

      val (lc4, rw4): (LinearLocation, Roadway) = Seq(terminatedLink).map(toRoadwayAndLinearLocation).head
      val rw4WithId = rw4.copy(ely = 8L, id = terminatedLink.roadwayId)

      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(Set(combLink1.roadwayNumber, combLink2.roadwayNumber, terminatedLink.roadwayNumber), false)).thenReturn(Seq(rw1WithId, rw2WithId, rw4WithId))
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(Set(combLink1.roadwayNumber), false)).thenReturn(Seq(rw1WithId))
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(Set(combLink2.roadwayNumber), false)).thenReturn(Seq(rw2WithId))
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(Set(terminatedLink.roadwayNumber), false)).thenReturn(Seq(rw4WithId))

      buildTestDataForProject(Some(project2),
        Some(Seq(rw4WithId)),
        Some(Seq(lc4)),
        Some(Seq(terminatedLink)))

      val mappedAfterNewRoadwayNumber = projectLinkDAO.fetchProjectLinksChange(projectId + 1).map(_.copy(linearLocationId = terminatedLink.linearLocationId))
      projectLinkDAO.moveProjectLinksToHistory(projectId + 1)

      roadAddressService.handleRoadwayPointsUpdate(terminatingProjectChanges, mappedAfterNewRoadwayNumber)
      nodesAndJunctionsService.handleNodePointTemplates(terminatingProjectChanges, combPLinks, mappedAfterNewRoadwayNumber)
      nodesAndJunctionsService.handleJunctionTemplates(terminatingProjectChanges, combPLinks, mappedAfterNewRoadwayNumber)
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

  test("Test nodesAndJunctionsService.handleJunctionTemplates When creating new different road numbers that connects each other Then junction template and junctions points should be handled/created properly." +
    "Test nodesAndJunctionsService.expireObsoleteNodesAndJunctions When expiring the one part that will make the ramp road parts not intersecting itself Then the existing Junction and its Junction points should be expired.") {
    runWithRollback {
      /*
                                   ^
                                   |
                                  road2
                                   |
          |--road1-->|0|--road1-->|

        * Note:
            0: Illustration where junction points should be created
            C: Combined track
      */

      val road1 = 9999L
      val road2 = 9999L
      val part1 = 1L
      val projectId = Sequences.nextViiteProjectId
      val rwId = Sequences.nextRoadwayId
      val llId = Sequences.nextLinearLocationId
      val rwNumber = Sequences.nextRoadwayNumber
      val plId = Sequences.nextProjectLinkId


      val combGeom1 = Seq(Point(0.0, 0.0), Point(10.0, 0.0))
      val combGeom2 = Seq(Point(10.0, 0.0), Point(20.0, 0.0))
      val combGeom3 = Seq(Point(20.0, 0.0), Point(20.0, 15.0))

      val combLink1 = dummyProjectLink(road1, part1, Track.Combined, Discontinuity.Continuous, 0, 10, 0, 10, Some(DateTime.now()), None, 12345, 0, 10, SideCode.TowardsDigitizing, LinkStatus.Transfer, projectId, RoadType.PublicRoad, combGeom1, rwNumber).copy(id = plId, projectId = projectId, roadwayId = rwId, linearLocationId = llId,
        calibrationPoints = (Some(ProjectLinkCalibrationPoint(12345, 0.0, 0, CalibrationPointSource.ProjectLinkSource)), None))
      val combLink2 = dummyProjectLink(road1, part1, Track.Combined, Discontinuity.Discontinuous, 10, 20, 10, 20, Some(DateTime.now()), None, 12346, 0, 10, SideCode.TowardsDigitizing, LinkStatus.Transfer, projectId, RoadType.PublicRoad, combGeom2, rwNumber).copy(id = plId + 1, projectId = projectId, roadwayId = rwId, linearLocationId = llId + 1,
        calibrationPoints = (None, Some(ProjectLinkCalibrationPoint(12346, 10.0, 20, CalibrationPointSource.ProjectLinkSource))))
      val combLink3 = dummyProjectLink(road2, part1, Track.Combined, Discontinuity.EndOfRoad, 0, 15, 0, 15, Some(DateTime.now()), None, 12347, 0, 15, SideCode.TowardsDigitizing, LinkStatus.Transfer, projectId, RoadType.PublicRoad, combGeom3, rwNumber + 1).copy(id = plId + 2, projectId = projectId, roadwayId = rwId + 1, linearLocationId = llId + 2,
        calibrationPoints = (Some(ProjectLinkCalibrationPoint(12347, 0.0, 0, CalibrationPointSource.ProjectLinkSource)), Some(ProjectLinkCalibrationPoint(12347, 15.0, 15, CalibrationPointSource.ProjectLinkSource))))

      val project = Project(projectId, ProjectState.Incomplete, "f", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
        "", Seq(), Seq(), None, None)

      val combPLinks = Seq(combLink1, combLink2, combLink3)

      val (lc1, rw1): (LinearLocation, Roadway) = Seq(combLink1).map(toRoadwayAndLinearLocation).head
      val (lc2, rw2): (LinearLocation, Roadway) = Seq(combLink2).map(toRoadwayAndLinearLocation).head
      val (lc3, rw3): (LinearLocation, Roadway) = Seq(combLink3).map(toRoadwayAndLinearLocation).head
      val rw1WithId = rw1.copy(ely = 8L, startAddrMValue = 0, endAddrMValue = 20)
      val rw2WithId = rw3.copy(ely = 8L, startAddrMValue = 0, endAddrMValue = 15)

      buildTestDataForProject(Some(project), Some(Seq(rw1WithId, rw2WithId)), Some(Seq(lc1, lc2, lc3)), Some(combPLinks))

      val projectChanges = List(
        //Combined
        ProjectRoadwayChange(projectId, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(AddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, None, Some(PublicRoad), Some(Discontinuity.Continuous), Some(8L)),
            RoadwayChangeSection(Some(road1), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(part1), endRoadPartNumber = Some(part1), startAddressM = Some(0L), endAddressM = Some(10L), Some(RoadType.PublicRoad), Some(Discontinuity.Continuous), Some(8L)),
            Discontinuity.Continuous, RoadType.PublicRoad, reversed = false, 1, 8)
          , DateTime.now, Some(0L)),
        ProjectRoadwayChange(projectId, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(AddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, None, Some(PublicRoad), Some(Discontinuity.Continuous), Some(8L)),
            RoadwayChangeSection(Some(road1), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(part1), endRoadPartNumber = Some(part1), startAddressM = Some(10L), endAddressM = Some(20L), Some(RoadType.PublicRoad), Some(Discontinuity.EndOfRoad), Some(8L)),
            Discontinuity.EndOfRoad, RoadType.PublicRoad, reversed = false, 2, 8)
          , DateTime.now, Some(0L)),
        ProjectRoadwayChange(projectId, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(AddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, None, Some(PublicRoad), Some(Discontinuity.Continuous), Some(8L)),
            RoadwayChangeSection(Some(road2), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(part1), endRoadPartNumber = Some(part1), startAddressM = Some(0L), endAddressM = Some(15L), Some(RoadType.PublicRoad), Some(Discontinuity.EndOfRoad), Some(8L)),
            Discontinuity.EndOfRoad, RoadType.PublicRoad, reversed = false, 3, 8)
          , DateTime.now, Some(0L))
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
      nodesAndJunctionsService.handleNodePointTemplates(projectChanges, combPLinks, mappedReservedRoadwayNumbers)
      nodesAndJunctionsService.handleJunctionTemplates(projectChanges, combPLinks, mappedReservedRoadwayNumbers)
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

      /*
      preparing expiring data

          |--C1-->|0|--C2-->|
       */


      val project2 = Project(projectId + 1, ProjectState.Incomplete, "ProjectTerminatedLinks", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
        "", Seq(), Seq(), None, None)

      val terminatingProjectChanges = List(
        ProjectRoadwayChange(project2.id, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(AddressChangeType.Termination,
            RoadwayChangeSection(Some(road2), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(part1), endRoadPartNumber = Some(part1), startAddressM = Some(0L), endAddressM = Some(15L), Some(RoadType.PublicRoad), Some(Discontinuity.EndOfRoad), Some(8L)),
            RoadwayChangeSection(None, None, None, None, None, None, Some(PublicRoad), Some(Discontinuity.Continuous), Some(8L)),
            Discontinuity.EndOfRoad, RoadType.PublicRoad, reversed = false, 3, 8)
          , DateTime.now, Some(0L))
      )

      linearLocationDAO.expireByRoadwayNumbers(Set(combLink3.roadwayNumber))
      roadwayDAO.expireHistory(Set(combLink3.roadwayId))
      projectLinkDAO.moveProjectLinksToHistory(projectId)
      val terminatingCombLink3 = dummyProjectLink(road2, part1, Track.Combined, Discontinuity.EndOfRoad, 0, 15, 0, 15, Some(DateTime.now()), None, 12347, 0, 15, SideCode.TowardsDigitizing, LinkStatus.Terminated, projectId + 1, RoadType.PublicRoad, combGeom3, rwNumber + 1).copy(id = plId + 3, roadwayId = rwId + 1, linearLocationId = llId + 2, calibrationPoints = (Some(ProjectLinkCalibrationPoint(12347, 0.0, 0, CalibrationPointSource.ProjectLinkSource)), Some(ProjectLinkCalibrationPoint(12347, 15.0, 15, CalibrationPointSource.ProjectLinkSource))))

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
      nodesAndJunctionsService.handleNodePointTemplates(terminatingProjectChanges, combPLinks, mappedAfterTerminationRoadwayNumbers)
      nodesAndJunctionsService.handleJunctionTemplates(terminatingProjectChanges, combPLinks, mappedAfterTerminationRoadwayNumbers)
      /*
      ending expiring data
       */
      val terminatedJunctionsBeforeExpire = junctionDAO.fetchExpiredByRoadwayNumbers(templateRoadwayNumbers)
      terminatedJunctionsBeforeExpire count (_.endDate.isDefined) should be(0)
      terminatedJunctionsBeforeExpire count (_.validTo.isDefined) should be(0)
      val endDate = Some(project2.startDate.minusDays(1))
      val terminatedLink1 = terminatingCombLink3.copy(endDate = endDate, status = LinkStatus.Terminated)
      val yetAnotherExpiredJunctions = nodesAndJunctionsService.expireObsoleteNodesAndJunctions(Seq(combLink1, combLink2, terminatedLink1), endDate)
      yetAnotherExpiredJunctions.size should be (2)

      when(mockRoadwayAddressMapper.getCurrentRoadAddressesBySection(road2, part1)).thenReturn(Seq())
      val roadAddresses = roadwayAddressMapper.mapRoadAddresses(rw1WithId, Seq(lc1, lc2))
      when(mockRoadwayAddressMapper.getCurrentRoadAddressesBySection(road1, part1)).thenReturn(roadAddresses)
      roadAddressService.expireObsoleteCalibrationPointsInJunctions(yetAnotherExpiredJunctions)

      val rwPoints = roadwayPointDAO.fetchByRoadwayNumbers(Seq(combLink1, combLink2, terminatedLink1).map(_.roadwayNumber)).map(_.id)
      val junctionPointsAfterTerminating = junctionPointDAO.fetchByRoadwayPointIds(rwPoints)
      junctionPointsAfterTerminating.length should be(0)
      // Check that junctions for roadways were expired
      val junctionsAfterExpire = junctionDAO.fetchAllByIds(templateRoadwayNumbers)
      junctionsAfterExpire.length should be(0)

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

  // <editor-fold desc="Ramps and roundabouts">
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

      val road = 20001
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

      val combLink1 = dummyProjectLink(road, part1, Track.Combined, Discontinuity.Continuous, 0, 10, 0, 10, Some(DateTime.now()), None, 12345, 0, 10, SideCode.TowardsDigitizing, LinkStatus.Transfer, projectId, RoadType.PublicRoad, combGeom1, rwNumber).copy(id = plId, projectId = projectId, roadwayId = rwId, linearLocationId = llId,
        calibrationPoints = (Some(ProjectLinkCalibrationPoint(12345, 0.0, 0, CalibrationPointSource.ProjectLinkSource)), None))
      val combLink2 = dummyProjectLink(road, part1, Track.Combined, Discontinuity.Discontinuous, 10, 20, 10, 20, Some(DateTime.now()), None, 12346, 0, 10, SideCode.TowardsDigitizing, LinkStatus.Transfer, projectId, RoadType.PublicRoad, combGeom2, rwNumber).copy(id = plId + 1, projectId = projectId, roadwayId = rwId, linearLocationId = llId + 1,
        calibrationPoints = (None, Some(ProjectLinkCalibrationPoint(12346, 10.0, 20, CalibrationPointSource.ProjectLinkSource))))
      val combLink3 = dummyProjectLink(road, part2, Track.Combined, Discontinuity.EndOfRoad, 0, 15, 0, 15, Some(DateTime.now()), None, 12347, 0, 15, SideCode.TowardsDigitizing, LinkStatus.Transfer, projectId, RoadType.PublicRoad, combGeom3, rwNumber + 1).copy(id = plId + 2, projectId = projectId, roadwayId = rwId + 1, linearLocationId = llId + 2,
        calibrationPoints = (Some(ProjectLinkCalibrationPoint(12347, 0.0, 0, CalibrationPointSource.ProjectLinkSource)), Some(ProjectLinkCalibrationPoint(12347, 15.0, 15, CalibrationPointSource.ProjectLinkSource))))

      val project = Project(projectId, ProjectState.Incomplete, "f", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
        "", Seq(), Seq(), None, None)

      val combPLinks = Seq(combLink1, combLink2, combLink3)

      val (lc1, rw1): (LinearLocation, Roadway) = Seq(combLink1).map(toRoadwayAndLinearLocation).head
      val (lc2, rw2): (LinearLocation, Roadway) = Seq(combLink2).map(toRoadwayAndLinearLocation).head
      val (lc3, rw3): (LinearLocation, Roadway) = Seq(combLink3).map(toRoadwayAndLinearLocation).head
      val rw1WithId = rw1.copy(ely = 8L, startAddrMValue = 0, endAddrMValue = 20)
      val rw2WithId = rw3.copy(ely = 8L, startAddrMValue = 0, endAddrMValue = 15)

      buildTestDataForProject(Some(project), Some(Seq(rw1WithId, rw2WithId)), Some(Seq(lc1, lc2, lc3)), Some(combPLinks))

      val projectChanges = List(
        //Combined
        ProjectRoadwayChange(projectId, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(AddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, None, Some(PublicRoad), Some(Discontinuity.Continuous), Some(8L)),
            RoadwayChangeSection(Some(road), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(part1), endRoadPartNumber = Some(part1), startAddressM = Some(0L), endAddressM = Some(10L), Some(RoadType.PublicRoad), Some(Discontinuity.Continuous), Some(8L)),
            Discontinuity.Continuous, RoadType.PublicRoad, reversed = false, 1, 8)
          , DateTime.now, Some(0L)),
        ProjectRoadwayChange(projectId, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(AddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, None, Some(PublicRoad), Some(Discontinuity.Continuous), Some(8L)),
            RoadwayChangeSection(Some(road), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(part1), endRoadPartNumber = Some(part1), startAddressM = Some(10L), endAddressM = Some(20L), Some(RoadType.PublicRoad), Some(Discontinuity.Discontinuous), Some(8L)),
            Discontinuity.Discontinuous, RoadType.PublicRoad, reversed = false, 2, 8)
          , DateTime.now, Some(0L)),
        ProjectRoadwayChange(projectId, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(AddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, None, Some(PublicRoad), Some(Discontinuity.Continuous), Some(8L)),
            RoadwayChangeSection(Some(road), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(part2), endRoadPartNumber = Some(part2), startAddressM = Some(0L), endAddressM = Some(15L), Some(RoadType.PublicRoad), Some(Discontinuity.EndOfRoad), Some(8L)),
            Discontinuity.EndOfRoad, RoadType.PublicRoad, reversed = false, 3, 8)
          , DateTime.now, Some(0L))
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
      nodesAndJunctionsService.handleNodePointTemplates(projectChanges, combPLinks, mappedReservedRoadwayNumbers)
      nodesAndJunctionsService.handleJunctionTemplates(projectChanges, combPLinks, mappedReservedRoadwayNumbers)
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

      /*
      preparing expiring data

          |--C1-->|0|--C2-->|
       */


      val project2 = Project(projectId + 1, ProjectState.Incomplete, "ProjectTerminatedLinks", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
        "", Seq(), Seq(), None, None)

      val terminatingProjectChanges = List(
        ProjectRoadwayChange(project2.id, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(AddressChangeType.Termination,
            RoadwayChangeSection(Some(road), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(part2), endRoadPartNumber = Some(part2), startAddressM = Some(0L), endAddressM = Some(15L), Some(RoadType.PublicRoad), Some(Discontinuity.EndOfRoad), Some(8L)),
            RoadwayChangeSection(None, None, None, None, None, None, Some(PublicRoad), Some(Discontinuity.Continuous), Some(8L)),
            Discontinuity.EndOfRoad, RoadType.PublicRoad, reversed = false, 3, 8)
          , DateTime.now, Some(0L))
      )

      linearLocationDAO.expireByRoadwayNumbers(Set(combLink3.roadwayNumber))
      roadwayDAO.expireHistory(Set(combLink3.roadwayId))
      projectLinkDAO.moveProjectLinksToHistory(projectId)
      val terminatingCombLink3 = dummyProjectLink(road, part2, Track.Combined, Discontinuity.EndOfRoad, 0, 15, 0, 15, Some(DateTime.now()), None, 12347, 0, 15, SideCode.TowardsDigitizing, LinkStatus.Terminated, projectId + 1, RoadType.PublicRoad, combGeom3, rwNumber + 1).copy(id = plId + 3, roadwayId = rwId + 1, linearLocationId = llId + 2, calibrationPoints = (Some(ProjectLinkCalibrationPoint(12347, 0.0, 0, CalibrationPointSource.ProjectLinkSource)), Some(ProjectLinkCalibrationPoint(12347, 15.0, 15, CalibrationPointSource.ProjectLinkSource))))

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
      nodesAndJunctionsService.handleNodePointTemplates(terminatingProjectChanges, combPLinks, mappedAfterTerminationRoadwayNumbers)
      nodesAndJunctionsService.handleJunctionTemplates(terminatingProjectChanges, combPLinks, mappedAfterTerminationRoadwayNumbers)
      /*
      ending expiring data
       */
      val terminatedJunctionsBeforeExpire = junctionDAO.fetchExpiredByRoadwayNumbers(templateRoadwayNumbers)
      terminatedJunctionsBeforeExpire count (_.endDate.isDefined) should be(0)
      terminatedJunctionsBeforeExpire count (_.validTo.isDefined) should be(0)
      val endDate = Some(project2.startDate.minusDays(1))
      val terminatedLink1 = terminatingCombLink3.copy(endDate = endDate, status = LinkStatus.Terminated)
      val yetAnotherExpiredJunctions = nodesAndJunctionsService.expireObsoleteNodesAndJunctions(Seq(combLink1, combLink2, terminatedLink1), endDate)
      yetAnotherExpiredJunctions.size should be (3)

      when(mockRoadwayAddressMapper.getCurrentRoadAddressesBySection(road, part2)).thenReturn(Seq())
      val roadAddresses = roadwayAddressMapper.mapRoadAddresses(rw1WithId, Seq(lc1, lc2))
      when(mockRoadwayAddressMapper.getCurrentRoadAddressesBySection(road, part1)).thenReturn(roadAddresses)
      roadAddressService.expireObsoleteCalibrationPointsInJunctions(yetAnotherExpiredJunctions)

      val rwPoints = roadwayPointDAO.fetchByRoadwayNumbers(Seq(combLink1, combLink2, terminatedLink1).map(_.roadwayNumber)).map(_.id)
      val junctionPointsAfterTerminating = junctionPointDAO.fetchByRoadwayPointIds(rwPoints)
      junctionPointsAfterTerminating.length should be(0)
      // Check that junctions for roadways were expired
      val junctionsAfterExpire = junctionDAO.fetchTemplatesByRoadwayNumbers(templateRoadwayNumbers)
      junctionsAfterExpire.length should be(0)

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

  test("Test nodesAndJunctionsService.handleJunctionTemplates When creating one roundabout road part that connects to same part in same road number and the connecting link is EndOfRoad Then junction template and junctions points should be handled/created properly") {
    runWithRollback {
      /*

     0|--C1-->|
     ^        |
     |       C2
     C4       |
     |        v
     |<--C3---|

            Note:
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

      val combLink1 = dummyProjectLink(road, part, Track.Combined, Discontinuity.Continuous, 0, 5, 0, 5, Some(DateTime.now()), None, 12345, 0, 5, SideCode.TowardsDigitizing, LinkStatus.Transfer, projectId, RoadType.PublicRoad, combGeom1, rwNumber).copy(id = plId, projectId = projectId, roadwayId = rwId, linearLocationId = llId)
      val combLink2 = dummyProjectLink(road, part, Track.Combined, Discontinuity.Continuous, 5, 10, 5, 10, Some(DateTime.now()), None, 12346, 0, 5, SideCode.TowardsDigitizing, LinkStatus.Transfer, projectId, RoadType.PublicRoad, combGeom2, rwNumber).copy(id = plId + 1, projectId = projectId, roadwayId = rwId, linearLocationId = llId + 1)
      val combLink3 = dummyProjectLink(road, part, Track.Combined, Discontinuity.Continuous, 10, 15, 10, 15, Some(DateTime.now()), None, 12347, 0, 5, SideCode.TowardsDigitizing, LinkStatus.Transfer, projectId, RoadType.PublicRoad, combGeom3, rwNumber).copy(id = plId + 2, projectId = projectId, roadwayId = rwId, linearLocationId = llId + 2)
      val combLink4 = dummyProjectLink(road, part, Track.Combined, Discontinuity.EndOfRoad, 15, 20, 15, 20, Some(DateTime.now()), None, 12348, 0, 5, SideCode.TowardsDigitizing, LinkStatus.Transfer, projectId, RoadType.PublicRoad, combGeom4, rwNumber).copy(id = plId + 3, projectId = projectId, roadwayId = rwId, linearLocationId = llId + 3)

      val project = Project(projectId, ProjectState.Incomplete, "f", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
        "", Seq(), Seq(), None, None)

      val combPLinks = Seq(combLink1, combLink2, combLink3, combLink4)

      val (lc1, rw1): (LinearLocation, Roadway) = Seq(combLink1).map(toRoadwayAndLinearLocation).head
      val (lc2, rw2): (LinearLocation, Roadway) = Seq(combLink2).map(toRoadwayAndLinearLocation).head
      val (lc3, rw3): (LinearLocation, Roadway) = Seq(combLink3).map(toRoadwayAndLinearLocation).head
      val (lc4, rw4): (LinearLocation, Roadway) = Seq(combLink4).map(toRoadwayAndLinearLocation).head
      val rw1WithId = rw1.copy(ely = 8L, startAddrMValue = 0, endAddrMValue = 20)

      buildTestDataForProject(Some(project), Some(Seq(rw1WithId)), Some(Seq(lc1, lc2, lc3, lc4)), Some(combPLinks))

      val projectChanges = List(
        //Combined
        ProjectRoadwayChange(projectId, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(AddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, None, Some(PublicRoad), Some(Discontinuity.Continuous), Some(8L)),
            RoadwayChangeSection(Some(road), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(part), endRoadPartNumber = Some(part), startAddressM = Some(0L), endAddressM = Some(15L), Some(RoadType.PublicRoad), Some(Discontinuity.Continuous), Some(8L)),
            Discontinuity.Continuous, RoadType.PublicRoad, reversed = false, 1, 8)
          , DateTime.now, Some(0L)),
        ProjectRoadwayChange(projectId, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(AddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, None, Some(PublicRoad), Some(Discontinuity.Continuous), Some(8L)),
            RoadwayChangeSection(Some(road), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(part), endRoadPartNumber = Some(part), startAddressM = Some(15L), endAddressM = Some(20L), Some(RoadType.PublicRoad), Some(Discontinuity.EndOfRoad), Some(8L)),
            Discontinuity.EndOfRoad, RoadType.PublicRoad, reversed = false, 2, 8)
          , DateTime.now, Some(0L))
      )

      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(combGeom1.head, combGeom1.head), roadNumberLimits)).thenReturn(Seq(lc1, lc4))
      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(combGeom1.last, combGeom1.last), roadNumberLimits)).thenReturn(Seq(lc1, lc2))
      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(combGeom2.last, combGeom2.last), roadNumberLimits)).thenReturn(Seq(lc2, lc3))
      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(combGeom3.last, combGeom3.last), roadNumberLimits)).thenReturn(Seq(lc3, lc4))
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(any[Set[Long]], any[Boolean])).thenReturn(Seq(rw1WithId))

      val mappedReservedRoadwayNumbers = projectLinkDAO.fetchProjectLinksChange(projectId)
      roadAddressService.handleRoadwayPointsUpdate(projectChanges, mappedReservedRoadwayNumbers)
      nodesAndJunctionsService.handleNodePointTemplates(projectChanges, combPLinks, mappedReservedRoadwayNumbers)
      nodesAndJunctionsService.handleJunctionTemplates(projectChanges, combPLinks, mappedReservedRoadwayNumbers)

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

     0--C1-->|
     ^       |
     |       C2
     C4      |
     |       v
     |<--C3--|

            Note:
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

      val combLink1 = dummyProjectLink(road, part, Track.Combined, Discontinuity.Continuous, 0, 5, 0, 5, Some(DateTime.now()), None, 12345, 0, 5, SideCode.TowardsDigitizing, LinkStatus.Transfer, projectId, RoadType.PublicRoad, combGeom1, rwNumber).copy(id = plId, projectId = projectId, roadwayId = rwId, linearLocationId = llId)
      val combLink2 = dummyProjectLink(road, part, Track.Combined, Discontinuity.Continuous, 5, 10, 5, 10, Some(DateTime.now()), None, 12346, 0, 5, SideCode.TowardsDigitizing, LinkStatus.Transfer, projectId, RoadType.PublicRoad, combGeom2, rwNumber).copy(id = plId + 1, projectId = projectId, roadwayId = rwId, linearLocationId = llId + 1)
      val combLink3 = dummyProjectLink(road, part, Track.Combined, Discontinuity.Continuous, 10, 15, 10, 15, Some(DateTime.now()), None, 12347, 0, 5, SideCode.TowardsDigitizing, LinkStatus.Transfer, projectId, RoadType.PublicRoad, combGeom3, rwNumber).copy(id = plId + 2, projectId = projectId, roadwayId = rwId, linearLocationId = llId + 2)
      val combLink4 = dummyProjectLink(road, part, Track.Combined, Discontinuity.Discontinuous, 15, 20, 15, 20, Some(DateTime.now()), None, 12348, 0, 5, SideCode.TowardsDigitizing, LinkStatus.Transfer, projectId, RoadType.PublicRoad, combGeom4, rwNumber).copy(id = plId + 3, projectId = projectId, roadwayId = rwId, linearLocationId = llId + 3)

      val project = Project(projectId, ProjectState.Incomplete, "f", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
        "", Seq(), Seq(), None, None)

      val combPLinks = Seq(combLink1, combLink2, combLink3, combLink4)

      val (lc1, rw1): (LinearLocation, Roadway) = Seq(combLink1).map(toRoadwayAndLinearLocation).head
      val (lc2, rw2): (LinearLocation, Roadway) = Seq(combLink2).map(toRoadwayAndLinearLocation).head
      val (lc3, rw3): (LinearLocation, Roadway) = Seq(combLink3).map(toRoadwayAndLinearLocation).head
      val (lc4, rw4): (LinearLocation, Roadway) = Seq(combLink4).map(toRoadwayAndLinearLocation).head
      val rw1WithId = rw1.copy(ely = 8L, startAddrMValue = 0, endAddrMValue = 20)
      val orderedlc1 = lc1.copy(orderNumber = 1)
      val orderedlc2 = lc2.copy(orderNumber = 2)
      val orderedlc3 = lc3.copy(orderNumber = 3)
      val orderedlc4 = lc4.copy(orderNumber = 4)

      buildTestDataForProject(Some(project), Some(Seq(rw1WithId)), Some(Seq(orderedlc1, orderedlc2, orderedlc3, orderedlc4)), Some(combPLinks))

      val projectChanges = List(
        //Combined
        ProjectRoadwayChange(projectId, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(AddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, None, Some(PublicRoad), Some(Discontinuity.Continuous), Some(8L)),
            RoadwayChangeSection(Some(road), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(part), endRoadPartNumber = Some(part), startAddressM = Some(0L), endAddressM = Some(15L), Some(RoadType.PublicRoad), Some(Discontinuity.Continuous), Some(8L)),
            Discontinuity.Continuous, RoadType.PublicRoad, reversed = false, 1, 8)
          , DateTime.now, Some(0L)),
        ProjectRoadwayChange(projectId, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(AddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, None, Some(PublicRoad), Some(Discontinuity.Discontinuous), Some(8L)),
            RoadwayChangeSection(Some(road), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(part), endRoadPartNumber = Some(part), startAddressM = Some(15L), endAddressM = Some(20L), Some(RoadType.PublicRoad), Some(Discontinuity.Discontinuous), Some(8L)),
            Discontinuity.Discontinuous, RoadType.PublicRoad, reversed = false, 2, 8)
          , DateTime.now, Some(0L))
      )

      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(combGeom1.head, combGeom1.head), roadNumberLimits)).thenReturn(Seq(orderedlc1, orderedlc2, orderedlc3, orderedlc4))
      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(combGeom1.last, combGeom1.last), roadNumberLimits)).thenReturn(Seq(orderedlc1, orderedlc2, orderedlc3, orderedlc4))
      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(combGeom2.last, combGeom2.last), roadNumberLimits)).thenReturn(Seq(orderedlc1, orderedlc2, orderedlc3, orderedlc4))
      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(combGeom3.last, combGeom3.last), roadNumberLimits)).thenReturn(Seq(orderedlc1, orderedlc2, orderedlc3, orderedlc4))
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(any[Set[Long]], any[Boolean])).thenReturn(Seq(rw1WithId))

      val mappedReservedRoadwayNumbers = projectLinkDAO.fetchProjectLinksChange(projectId)
      roadAddressService.handleRoadwayPointsUpdate(projectChanges, mappedReservedRoadwayNumbers)
      nodesAndJunctionsService.handleNodePointTemplates(projectChanges, combPLinks, mappedReservedRoadwayNumbers)
      nodesAndJunctionsService.handleJunctionTemplates(projectChanges, combPLinks, mappedReservedRoadwayNumbers)

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

     0--C1-->|
     ^       |
     |       C2
     C4      |
     |       v
     |<--C3--|

            Note:
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

      val combLink1 = dummyProjectLink(road, part, Track.Combined, Discontinuity.Continuous, 0, 5, 0, 5, Some(DateTime.now()), None, 12345, 0, 5, SideCode.TowardsDigitizing, LinkStatus.Transfer, projectId, RoadType.PublicRoad, combGeom1, rwNumber).copy(id = plId, projectId = projectId, roadwayId = rwId, linearLocationId = llId)
      val combLink2 = dummyProjectLink(road, part, Track.Combined, Discontinuity.Continuous, 5, 10, 5, 10, Some(DateTime.now()), None, 12346, 0, 5, SideCode.TowardsDigitizing, LinkStatus.Transfer, projectId, RoadType.PublicRoad, combGeom2, rwNumber).copy(id = plId + 1, projectId = projectId, roadwayId = rwId, linearLocationId = llId + 1)
      val combLink3 = dummyProjectLink(road, part, Track.Combined, Discontinuity.Continuous, 10, 15, 10, 15, Some(DateTime.now()), None, 12347, 0, 5, SideCode.TowardsDigitizing, LinkStatus.Transfer, projectId, RoadType.PublicRoad, combGeom3, rwNumber).copy(id = plId + 2, projectId = projectId, roadwayId = rwId, linearLocationId = llId + 2)
      val combLink4 = dummyProjectLink(road, part, Track.Combined, Discontinuity.MinorDiscontinuity, 15, 20, 15, 20, Some(DateTime.now()), None, 12348, 0, 5, SideCode.TowardsDigitizing, LinkStatus.Transfer, projectId, RoadType.PublicRoad, combGeom4, rwNumber).copy(id = plId + 3, projectId = projectId, roadwayId = rwId, linearLocationId = llId + 3)

      val project = Project(projectId, ProjectState.Incomplete, "f", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
        "", Seq(), Seq(), None, None)

      val combPLinks = Seq(combLink1, combLink2, combLink3, combLink4)

      val (lc1, rw1): (LinearLocation, Roadway) = Seq(combLink1).map(toRoadwayAndLinearLocation).head
      val (lc2, rw2): (LinearLocation, Roadway) = Seq(combLink2).map(toRoadwayAndLinearLocation).head
      val (lc3, rw3): (LinearLocation, Roadway) = Seq(combLink3).map(toRoadwayAndLinearLocation).head
      val (lc4, rw4): (LinearLocation, Roadway) = Seq(combLink4).map(toRoadwayAndLinearLocation).head
      val rw1WithId = rw1.copy(ely = 8L, startAddrMValue = 0, endAddrMValue = 20)
      val orderedlc1 = lc1.copy(orderNumber = 1)
      val orderedlc2 = lc2.copy(orderNumber = 2)
      val orderedlc3 = lc3.copy(orderNumber = 3)
      val orderedlc4 = lc4.copy(orderNumber = 4)

      buildTestDataForProject(Some(project), Some(Seq(rw1WithId)), Some(Seq(orderedlc1, orderedlc2, orderedlc3, orderedlc4)), Some(combPLinks))

      val projectChanges = List(
        //Combined
        ProjectRoadwayChange(projectId, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(AddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, None, Some(PublicRoad), Some(Discontinuity.Continuous), Some(8L)),
            RoadwayChangeSection(Some(road), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(part), endRoadPartNumber = Some(part), startAddressM = Some(0L), endAddressM = Some(15L), Some(RoadType.PublicRoad), Some(Discontinuity.Continuous), Some(8L)),
            Discontinuity.Continuous, RoadType.PublicRoad, reversed = false, 1, 8)
          , DateTime.now, Some(0L)),
        ProjectRoadwayChange(projectId, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(AddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, None, Some(PublicRoad), Some(Discontinuity.MinorDiscontinuity), Some(8L)),
            RoadwayChangeSection(Some(road), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(part), endRoadPartNumber = Some(part), startAddressM = Some(15L), endAddressM = Some(20L), Some(RoadType.PublicRoad), Some(Discontinuity.MinorDiscontinuity), Some(8L)),
            Discontinuity.MinorDiscontinuity, RoadType.PublicRoad, reversed = false, 2, 8)
          , DateTime.now, Some(0L))
      )

      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(combGeom1.head, combGeom1.head), roadNumberLimits)).thenReturn(Seq(orderedlc1, orderedlc2, orderedlc3, orderedlc4))
      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(combGeom1.last, combGeom1.last), roadNumberLimits)).thenReturn(Seq(orderedlc1, orderedlc2, orderedlc3, orderedlc4))
      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(combGeom2.last, combGeom2.last), roadNumberLimits)).thenReturn(Seq(orderedlc1, orderedlc2, orderedlc3, orderedlc4))
      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(combGeom3.last, combGeom3.last), roadNumberLimits)).thenReturn(Seq(orderedlc1, orderedlc2, orderedlc3, orderedlc4))
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(any[Set[Long]], any[Boolean])).thenReturn(Seq(rw1WithId))

      val mappedReservedRoadwayNumbers = projectLinkDAO.fetchProjectLinksChange(projectId)
      roadAddressService.handleRoadwayPointsUpdate(projectChanges, mappedReservedRoadwayNumbers)
      nodesAndJunctionsService.handleNodePointTemplates(projectChanges, combPLinks, mappedReservedRoadwayNumbers)
      nodesAndJunctionsService.handleJunctionTemplates(projectChanges, combPLinks, mappedReservedRoadwayNumbers)

      val roadwayPoints = roadwayPointDAO.fetchByRoadwayNumbers(combPLinks.map(_.roadwayNumber)).map(_.id)

      val junctionPointTemplates = junctionPointDAO.fetchByRoadwayPointIds(roadwayPoints)
      junctionPointTemplates.length should be(2)
      junctionPointTemplates.count(_.beforeAfter == BeforeAfter.Before) should be(1)
      junctionPointTemplates.count(_.beforeAfter == BeforeAfter.After) should be(1)

      val junctions = junctionDAO.fetchTemplatesByRoadwayNumbers(junctionPointTemplates.map(_.roadwayNumber).distinct)
      junctions.size should be(1)
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


      val roadways = Seq(Roadway(roadwayId, roadwayNumber, 999, 999, RoadType.PublicRoad, Track.LeftSide, Discontinuity.Continuous,
        0, 50, reversed = false, DateTime.parse("2000-01-01"), None, "test", Some("TEST ROAD 1"), 1, TerminationCode.NoTermination),
        Roadway(roadwayId + 1, roadwayNumber + 1, 999, 999, RoadType.PublicRoad, Track.RightSide, Discontinuity.Continuous,
          0, 50, reversed = false, DateTime.parse("2000-01-01"), None, "test", Some("TEST ROAD 1"), 1, TerminationCode.NoTermination),
        Roadway(roadwayId + 2, roadwayNumber + 2, 999, 999, RoadType.PublicRoad, Track.Combined, Discontinuity.Continuous,
          50, 100, reversed = false, DateTime.parse("2000-01-01"), None, "test", Some("TEST ROAD 1"), 1, TerminationCode.NoTermination),
        Roadway(roadwayId + 3, roadwayNumber + 3, 999, 999, RoadType.PublicRoad, Track.Combined, Discontinuity.EndOfRoad,
          100, 150, reversed = false, DateTime.parse("2000-01-01"), None, "test", Some("TEST ROAD 1"), 1, TerminationCode.NoTermination)
      )

      val linearLocations = Seq(
        LinearLocation(linearLocationId, 1, 1000l, 0.0, 50.0, SideCode.TowardsDigitizing, 10000000000l,
          (CalibrationPointReference(Some(0l)), CalibrationPointReference.None), Seq(Point(0.0, 0.0), Point(50.0, 0.0)), LinkGeomSource.ComplementaryLinkInterface,
          roadwayNumber, Some(DateTime.parse("2000-01-01")), None),
        LinearLocation(linearLocationId + 1, 1, 2000l, 0.0, 10.0, SideCode.TowardsDigitizing, 10000000000l,
          (CalibrationPointReference.None, CalibrationPointReference.None), Seq(Point(0.0, 10.0), Point(50.0, 5.0)), LinkGeomSource.ComplementaryLinkInterface,
          roadwayNumber + 1, Some(DateTime.parse("2000-01-01")), None),
        LinearLocation(linearLocationId + 2, 1, 3000l, 0.0, 10.0, SideCode.TowardsDigitizing, 10000000000l,
          (CalibrationPointReference.None, CalibrationPointReference.None), Seq(Point(50.0, 5.0), Point(100.0, 5.0)), LinkGeomSource.ComplementaryLinkInterface,
          roadwayNumber + 2, Some(DateTime.parse("2000-01-01")), None),
        LinearLocation(linearLocationId + 3, 1, 3000l, 0.0, 10.0, SideCode.TowardsDigitizing, 10000000000l,
          (CalibrationPointReference.None, CalibrationPointReference(Some(150l))), Seq(Point(100.0, 5.0), Point(150.0, 5.0)), LinkGeomSource.ComplementaryLinkInterface,
          roadwayNumber + 3, Some(DateTime.parse("2000-01-01")), None)
      )
      val roadwayIds = roadwayDAO.create(roadways)

      val left = dummyProjectLink(999, 999, Track.LeftSide, Discontinuity.Continuous, 0, 50, 0, 50, Some(DateTime.now()), None, 12345, 0, 50, SideCode.TowardsDigitizing, LinkStatus.Transfer, 0L, RoadType.PublicRoad, leftGeom, roadwayNumber).copy(id = id, projectId = projectId, roadwayId = roadwayId, linearLocationId = linearLocationId)
      val right = dummyProjectLink(999, 999, Track.RightSide, Discontinuity.Continuous, 0, 50, 0, 50, Some(DateTime.now()), None, 12346, 0, 50, SideCode.TowardsDigitizing, LinkStatus.Transfer, 0L, RoadType.PublicRoad, rightGeom, roadwayNumber + 1).copy(id = id + 1, projectId = projectId, roadwayId = roadwayId + 1, linearLocationId = linearLocationId + 1)
      val combined1 = dummyProjectLink(999, 999, Track.Combined, Discontinuity.Continuous, 50, 100, 50, 100, Some(DateTime.now()), None, 12347, 0, 50, SideCode.TowardsDigitizing, LinkStatus.Transfer, 0L, RoadType.FerryRoad, combGeom1, roadwayNumber + 2).copy(id = id + 2, projectId = projectId, roadwayId = roadwayId + 2, linearLocationId = linearLocationId + 2)
      val combined2 = dummyProjectLink(999, 999, Track.Combined, Discontinuity.EndOfRoad, 100, 150, 100, 150, Some(DateTime.now()), None, 12348, 0, 50, SideCode.TowardsDigitizing, LinkStatus.Transfer, 0L, RoadType.PublicRoad, combGeom2, roadwayNumber + 3).copy(id = id + 3, projectId = projectId, roadwayId = roadwayId + 3, linearLocationId = linearLocationId + 3)
      val pls = Seq(left, right, combined1, combined2)
      buildTestDataForProject(Some(project), None, Some(linearLocations), Some(pls))

      val projectChanges = List(
        //left
        ProjectRoadwayChange(0, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(AddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, None, Some(PublicRoad), Some(Discontinuity.Continuous), Some(8L)),
            RoadwayChangeSection(Some(999), Some(Track.LeftSide.value.toLong), startRoadPartNumber = Some(999L), endRoadPartNumber = Some(999L), startAddressM = Some(0L), endAddressM = Some(50L), Some(RoadType.PublicRoad), Some(Discontinuity.Continuous), Some(8L)),
            Discontinuity.Continuous, RoadType.PublicRoad, reversed = false, 1, 8)
          , DateTime.now, Some(0L)),
        //right
        ProjectRoadwayChange(0, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(AddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, None, Some(PublicRoad), Some(Discontinuity.Continuous), Some(8L)),
            RoadwayChangeSection(Some(999), Some(Track.RightSide.value.toLong), startRoadPartNumber = Some(999L), endRoadPartNumber = Some(999L), startAddressM = Some(0L), endAddressM = Some(50L), Some(RoadType.PublicRoad), Some(Discontinuity.Continuous), Some(8L)),
            Discontinuity.Continuous, RoadType.PublicRoad, reversed = false, 2, 8)
          , DateTime.now, Some(0L)),
        //combined
        ProjectRoadwayChange(0, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(AddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, None, Some(PublicRoad), Some(Discontinuity.Continuous), Some(8L)),
            RoadwayChangeSection(Some(999), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(999L), endRoadPartNumber = Some(999L), startAddressM = Some(50L), endAddressM = Some(100L), Some(RoadType.FerryRoad), Some(Discontinuity.Continuous), Some(8L)),
            Discontinuity.Continuous, RoadType.FerryRoad, reversed = false, 3, 8)
          , DateTime.now, Some(0L)),
        ProjectRoadwayChange(0, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(AddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, None, Some(PublicRoad), Some(Discontinuity.Continuous), Some(8L)),
            RoadwayChangeSection(Some(999), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(999L), endRoadPartNumber = Some(999L), startAddressM = Some(50L), endAddressM = Some(150L), Some(RoadType.PublicRoad), Some(Discontinuity.EndOfRoad), Some(8L)),
            Discontinuity.Continuous, RoadType.PublicRoad, reversed = false, 3, 8)
          , DateTime.now, Some(0L))
      )
      val mappedChanges = projectLinkDAO.fetchProjectLinksChange(projectId)
      nodesAndJunctionsService.handleNodePointTemplates(projectChanges, pls, mappedChanges)

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

      val reversedLeft = left.copy(status = LinkStatus.Transfer, discontinuity = Discontinuity.EndOfRoad, sideCode = SideCode.switch(left.sideCode), reversed = true, startAddrMValue = 100, endAddrMValue = 150)
      val reversedRight = right.copy(status = LinkStatus.Transfer, discontinuity = Discontinuity.EndOfRoad, sideCode = SideCode.switch(left.sideCode), reversed = true, startAddrMValue = 100, endAddrMValue = 150)
      val reversedCombined1 = combined1.copy(status = LinkStatus.Transfer, discontinuity = Discontinuity.Continuous, sideCode = SideCode.switch(left.sideCode), reversed = true, startAddrMValue = 50, endAddrMValue = 100)
      val reversedCombined2 = combined2.copy(status = LinkStatus.Transfer, discontinuity = Discontinuity.Continuous, sideCode = SideCode.switch(left.sideCode), reversed = true, startAddrMValue = 0, endAddrMValue = 50)

      val reversedProjectChanges = List(
        //left
        ProjectRoadwayChange(0, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(AddressChangeType.Transfer,
            RoadwayChangeSection(Some(999), Some(Track.LeftSide.value.toLong), startRoadPartNumber = Some(999L), endRoadPartNumber = Some(999L), startAddressM = Some(0L), endAddressM = Some(50L), Some(RoadType.PublicRoad), Some(Discontinuity.Continuous), Some(8L)),
            RoadwayChangeSection(Some(999), Some(Track.LeftSide.value.toLong), startRoadPartNumber = Some(999L), endRoadPartNumber = Some(999L), startAddressM = Some(100), endAddressM = Some(150L), Some(RoadType.PublicRoad), Some(Discontinuity.EndOfRoad), Some(8L)),
            Discontinuity.Continuous, RoadType.PublicRoad, reversed = true, 1, 8)
          , DateTime.now, Some(0L)),
        //right
        ProjectRoadwayChange(0, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(AddressChangeType.Transfer,
            RoadwayChangeSection(Some(999), Some(Track.RightSide.value.toLong), startRoadPartNumber = Some(999L), endRoadPartNumber = Some(999L), startAddressM = Some(0L), endAddressM = Some(50L), Some(RoadType.PublicRoad), Some(Discontinuity.Continuous), Some(8L)),
            RoadwayChangeSection(Some(999), Some(Track.RightSide.value.toLong), startRoadPartNumber = Some(999L), endRoadPartNumber = Some(999L), startAddressM = Some(100), endAddressM = Some(150L), Some(RoadType.PublicRoad), Some(Discontinuity.EndOfRoad), Some(8L)),
            Discontinuity.Continuous, RoadType.PublicRoad, reversed = true, 2, 8)
          , DateTime.now, Some(0L)),
        ProjectRoadwayChange(0, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(AddressChangeType.Transfer,
            RoadwayChangeSection(Some(999), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(999L), endRoadPartNumber = Some(999L), startAddressM = Some(50L), endAddressM = Some(100L), Some(RoadType.FerryRoad), Some(Discontinuity.Continuous), Some(8L)),
            RoadwayChangeSection(Some(999), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(999L), endRoadPartNumber = Some(999L), startAddressM = Some(50L), endAddressM = Some(100L), Some(RoadType.FerryRoad), Some(Discontinuity.Continuous), Some(8L)),
            Discontinuity.Continuous, RoadType.PublicRoad, reversed = true, 2, 8)
          , DateTime.now, Some(0L)),
        //combined
        ProjectRoadwayChange(0, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(AddressChangeType.Transfer,
            RoadwayChangeSection(Some(999), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(999L), endRoadPartNumber = Some(999L), startAddressM = Some(100), endAddressM = Some(150L), Some(RoadType.PublicRoad), Some(Discontinuity.EndOfRoad), Some(8L)),
            RoadwayChangeSection(Some(999), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(999L), endRoadPartNumber = Some(999L), startAddressM = Some(0L), endAddressM = Some(50L), Some(RoadType.PublicRoad), Some(Discontinuity.Continuous), Some(8L)),
            Discontinuity.Continuous, RoadType.PublicRoad, reversed = true, 3, 8)
          , DateTime.now, Some(0L))
      )

      val reversedPls = Seq(reversedCombined1, reversedCombined2, reversedRight, reversedLeft)

      val reversedRoadways = Seq(Roadway(roadwayId + 4, left.roadwayNumber, 999, 999, RoadType.PublicRoad, Track.LeftSide, Discontinuity.EndOfRoad,
        100, 150, reversed = true, DateTime.parse("2000-01-01"), None, "test", Some("TEST ROAD 1"), 1, TerminationCode.NoTermination),
        Roadway(roadwayId + 5, right.roadwayNumber, 999, 999, RoadType.PublicRoad, Track.RightSide, Discontinuity.EndOfRoad,
          100, 150, reversed = true, DateTime.parse("2000-01-01"), None, "test", Some("TEST ROAD 1"), 1, TerminationCode.NoTermination),
        Roadway(roadwayId + 6, combined1.roadwayNumber, 999, 999, RoadType.PublicRoad, Track.Combined, Discontinuity.Continuous,
          50, 100, reversed = true, DateTime.parse("2000-01-01"), None, "test", Some("TEST ROAD 1"), 1, TerminationCode.NoTermination),
        Roadway(roadwayId + 7, combined2.roadwayNumber, 999, 999, RoadType.PublicRoad, Track.Combined, Discontinuity.Continuous,
          0, 50, reversed = true, DateTime.parse("2000-01-01"), None, "test", Some("TEST ROAD 1"), 1, TerminationCode.NoTermination)
      )

      roadwayDAO.expireById(roadwayIds.toSet)
      roadwayDAO.create(reversedRoadways)
      //projectlinks are now reversed
      projectLinkDAO.updateProjectLinks(reversedPls, "user", Seq())
      val mappedRoadwayNumbers = projectLinkDAO.fetchProjectLinksChange(projectId)
      roadAddressService.handleRoadwayPointsUpdate(reversedProjectChanges, mappedRoadwayNumbers)
      nodesAndJunctionsService.handleNodePointTemplates(reversedProjectChanges, reversedPls, mappedRoadwayNumbers)
      val fetchedReversedNodesPoints = reversedPls.flatMap(pl => nodePointDAO.fetchTemplatesByRoadwayNumber(pl.roadwayNumber)).sortBy(_.id)
      fetchedNodesPoints.size should be(fetchedReversedNodesPoints.size)
      fetchedNodesPoints.zip(fetchedReversedNodesPoints).foreach { case (before, after) =>
        before.id should be(after.id)
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
      val roadLink1 = dummyProjectLink(1, 1, Track.Combined, Discontinuity.Continuous, 0, 100, 0, 100, Some(DateTime.now()), None, 12345, 0, 100.0, SideCode.TowardsDigitizing, LinkStatus.New, projectId, RoadType.PublicRoad, roadGeom1, roadwayNumber).copy(id = plId1)
      val roadLink2 = dummyProjectLink(1, 2, Track.Combined, Discontinuity.Continuous, 0, 150, 0, 150, Some(DateTime.now()), None, 12346, 0, 150.0, SideCode.TowardsDigitizing, LinkStatus.New, projectId, RoadType.PublicRoad, roadGeom2, roadwayNumber + 1).copy(id = plId2)

      val project = Project(projectId, ProjectState.Incomplete, "f", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
        "", Seq(), Seq(), None, None)


      val roadways = Seq(
        Roadway(NewIdValue, roadLink1.roadwayNumber, roadLink1.roadNumber, roadLink1.roadPartNumber, roadLink1.roadType, roadLink1.track, roadLink1.discontinuity, roadLink1.startAddrMValue, roadLink1.endAddrMValue, reversed = false, roadLink1.startDate.get, roadLink1.endDate, "user", None, 8L, TerminationCode.NoTermination, DateTime.now, None),
        Roadway(NewIdValue, roadLink2.roadwayNumber, roadLink2.roadNumber, roadLink2.roadPartNumber, roadLink2.roadType, roadLink2.track, roadLink2.discontinuity, roadLink2.startAddrMValue, roadLink2.endAddrMValue, reversed = false, roadLink2.startDate.get, roadLink2.endDate, "user", None, 8L, TerminationCode.NoTermination, DateTime.now, None))
      val linearLocations = Seq(
        LinearLocation(NewIdValue, 1, roadLink1.linkId, roadLink1.startAddrMValue, roadLink1.endAddrMValue, roadLink1.sideCode, 0L, calibrationPoints = (CalibrationPointReference(Some(roadLink1.startAddrMValue)), CalibrationPointReference(Some(roadLink1.endAddrMValue))), roadGeom1, LinkGeomSource.NormalLinkInterface, roadLink1.roadwayNumber, Some(DateTime.now), None),
        LinearLocation(NewIdValue, 1, roadLink2.linkId, roadLink2.startAddrMValue, roadLink2.endAddrMValue, roadLink2.sideCode, 0L, calibrationPoints = (CalibrationPointReference(Some(roadLink2.startAddrMValue)), CalibrationPointReference(Some(roadLink2.endAddrMValue))), roadGeom2, LinkGeomSource.NormalLinkInterface, roadLink2.roadwayNumber, Some(DateTime.now), None))

      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(any[BoundingRectangle], any[Seq[(Int, Int)]])).thenReturn(linearLocations)
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(any[Set[Long]], any[Boolean])).thenReturn(roadways)

      val projectLinks = Seq(roadLink1, roadLink2)
      buildTestDataForProject(Some(project), None, Some(linearLocations), Some(projectLinks))

      roadwayDAO.create(roadways)

      val projectChanges = List(
        //combined
        ProjectRoadwayChange(0, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(AddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, None, Some(PublicRoad), Some(Discontinuity.Continuous), Some(8L)),
            RoadwayChangeSection(Some(roadLink1.roadNumber), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(roadLink1.roadPartNumber), endRoadPartNumber = Some(roadLink1.roadPartNumber), startAddressM = Some(0L), endAddressM = Some(100L), Some(RoadType.PublicRoad), Some(Discontinuity.Continuous), Some(8L)),
            Discontinuity.Continuous, RoadType.PublicRoad, reversed = false, 1, 8)
          , DateTime.now, Some(0L)),
        ProjectRoadwayChange(0, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(AddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, None, Some(PublicRoad), Some(Discontinuity.Continuous), Some(8L)),
            RoadwayChangeSection(Some(roadLink2.roadNumber), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(roadLink2.roadPartNumber), endRoadPartNumber = Some(roadLink2.roadPartNumber), startAddressM = Some(0L), endAddressM = Some(150L), Some(RoadType.PublicRoad), Some(Discontinuity.Continuous), Some(8L)),
            Discontinuity.Continuous, RoadType.PublicRoad, reversed = false, 2, 8)
          , DateTime.now, Some(0L))
      )
      val mappedRoadwayNumbers = projectLinkDAO.fetchProjectLinksChange(projectId)
      // Creation of nodes and node points
      nodesAndJunctionsService.handleNodePointTemplates(projectChanges, projectLinks, mappedRoadwayNumbers)

      val nodeNumber1 = Sequences.nextNodeNumber
      val nodeNumber2 = Sequences.nextNodeNumber
      val nodeNumber3 = Sequences.nextNodeNumber
      val node1 = Node(NewIdValue, nodeNumber1, roadLink1.geometry.head, None, NodeType.EndOfRoad, DateTime.now(), None, DateTime.now(), None, "user", Some(DateTime.now()))
      val node2 = Node(NewIdValue, nodeNumber2, roadLink2.geometry.head, None, NodeType.EndOfRoad, DateTime.now(), None, DateTime.now(), None, "user", Some(DateTime.now()))
      val node3 = Node(NewIdValue, nodeNumber3, roadLink2.geometry.last, None, NodeType.EndOfRoad, DateTime.now(), None, DateTime.now(), None, "user", Some(DateTime.now()))

      nodeDAO.create(Seq(node1, node2, node3))

      val nodePointTemplates = projectLinks.flatMap(pl => nodePointDAO.fetchTemplatesByRoadwayNumber(pl.roadwayNumber))
      nodePointTemplates.length should be(4)
      nodePointTemplates.foreach(
        np => {
          if (np.roadwayNumber == roadLink1.roadwayNumber) {
            if (np.addrM == roadLink1.startAddrMValue)
              sqlu"""UPDATE NODE_POINT SET NODE_NUMBER = $nodeNumber1 WHERE ID = ${np.id}""".execute
            else if (np.addrM == roadLink1.endAddrMValue)
              sqlu"""UPDATE NODE_POINT SET NODE_NUMBER = $nodeNumber2 WHERE ID = ${np.id}""".execute
          } else if (np.roadwayNumber == roadLink2.roadwayNumber) {
            if (np.addrM == roadLink2.startAddrMValue)
              sqlu"""UPDATE NODE_POINT SET NODE_NUMBER = $nodeNumber2 WHERE ID = ${np.id}""".execute
            else if (np.addrM == roadLink2.endAddrMValue)
              sqlu"""UPDATE NODE_POINT SET NODE_NUMBER = $nodeNumber3 WHERE ID = ${np.id}""".execute
          }
        }
      )

      val nodePoints = nodePointDAO.fetchByNodeNumbers(Seq(nodeNumber1, nodeNumber2, nodeNumber3))
      nodePoints.length should be(4)
      nodePoints.exists(node => node.roadwayNumber == roadLink1.roadwayNumber && node.beforeAfter == BeforeAfter.After && node.addrM == roadLink1.startAddrMValue) should be(true)
      nodePoints.exists(node => node.roadwayNumber == roadLink1.roadwayNumber && node.beforeAfter == BeforeAfter.Before && node.addrM == roadLink1.endAddrMValue) should be(true)
      nodePoints.exists(node => node.roadwayNumber == roadLink2.roadwayNumber && node.beforeAfter == BeforeAfter.After && node.addrM == roadLink2.startAddrMValue) should be(true)
      nodePoints.exists(node => node.roadwayNumber == roadLink2.roadwayNumber && node.beforeAfter == BeforeAfter.Before && node.addrM == roadLink2.endAddrMValue) should be(true)

      val terminatedRoadLink = roadLink2.copy(endDate = Some(DateTime.now().withTimeAtStartOfDay()), status = LinkStatus.Terminated, projectId = 1)

      nodesAndJunctionsService.expireObsoleteNodesAndJunctions(Seq(terminatedRoadLink), Some(terminatedRoadLink.endDate.get))

      // Test expired node and node points
      val nodePointsAfterExpiration = nodePointDAO.fetchByNodeNumbers(Seq(nodeNumber1, nodeNumber2, nodeNumber3))
      nodePointsAfterExpiration.length should be(2)
      nodePointsAfterExpiration.exists(node => node.roadwayNumber == roadLink1.roadwayNumber && node.beforeAfter == BeforeAfter.After && node.addrM == roadLink1.startAddrMValue) should be(true)
      nodePointsAfterExpiration.exists(node => node.roadwayNumber == roadLink1.roadwayNumber && node.beforeAfter == BeforeAfter.Before && node.addrM == roadLink1.endAddrMValue) should be(true)
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
    * * If the remaining node points referenced by this nodeId are all present in the same road number, road part, track and road type
    * then all of those node points and the node itself should expire.
    */
  test("Test expireObsoleteNodesAndJunctions case When road is extended after the existing road") {
    runWithRollback {
      val roadGeom1 = Seq(Point(0.0, 0.0), Point(100.0, 0.0))

      val roadwayNumber = Sequences.nextRoadwayNumber
      val projectId = Sequences.nextViiteProjectId
      val roadLink = dummyProjectLink(1, 1, Track.Combined, Discontinuity.Continuous, 0, 100, 0, 100, Some(DateTime.now()), None, 12345, 0, 100.0, SideCode.TowardsDigitizing, LinkStatus.New, projectId, RoadType.PublicRoad, roadGeom1, roadwayNumber)

      val roadways = Seq(
        Roadway(NewIdValue, roadLink.roadwayNumber, roadLink.roadNumber, roadLink.roadPartNumber, roadLink.roadType, roadLink.track, roadLink.discontinuity, roadLink.startAddrMValue, roadLink.endAddrMValue, reversed = false, roadLink.startDate.get, roadLink.endDate, "user", None, 8L, TerminationCode.NoTermination, DateTime.now, None))
      val linearLocations = Seq(
        LinearLocation(NewIdValue, 1, roadLink.linkId, roadLink.startAddrMValue, roadLink.endAddrMValue, roadLink.sideCode, 0L, calibrationPoints = (CalibrationPointReference(Some(roadLink.startAddrMValue)), CalibrationPointReference(Some(roadLink.endAddrMValue))), roadGeom1, LinkGeomSource.NormalLinkInterface, roadLink.roadwayNumber, Some(DateTime.now), None))

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
          RoadwayChangeInfo(AddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, None, Some(PublicRoad), Some(Discontinuity.Continuous), Some(8L)),
            RoadwayChangeSection(Some(roadLink.roadNumber), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(roadLink.roadPartNumber), endRoadPartNumber = Some(roadLink.roadPartNumber), startAddressM = Some(0L), endAddressM = Some(100L), Some(RoadType.PublicRoad), Some(Discontinuity.Continuous), Some(8L)),
            Discontinuity.Continuous, RoadType.PublicRoad, reversed = false, 1, 8)
          , DateTime.now, Some(0L))
      )
      val mappedRoadwayNumbers = projectLinkDAO.fetchProjectLinksChange(projectId)
      nodesAndJunctionsService.handleNodePointTemplates(projectChanges, projectLinks, mappedRoadwayNumbers)

      val node1 = Node(Sequences.nextNodeId, Sequences.nextNodeNumber, roadLink.geometry.head, None, NodeType.EndOfRoad, DateTime.now(), None, DateTime.now(), None, "user", Some(DateTime.now()))
      val node2 = Node(Sequences.nextNodeId, Sequences.nextNodeNumber, roadLink.geometry.last, None, NodeType.EndOfRoad, DateTime.now(), None, DateTime.now(), None, "user", Some(DateTime.now()))

      nodeDAO.create(Seq(node1, node2))

      val nodePointTemplates = projectLinks.flatMap(pl => nodePointDAO.fetchTemplatesByRoadwayNumber(pl.roadwayNumber))
      nodePointTemplates.length should be(2)

      nodePointTemplates.foreach(
        np => {
          if (np.addrM == roadLink.startAddrMValue)
            sqlu"""UPDATE NODE_POINT SET NODE_NUMBER = ${node1.nodeNumber} WHERE ID = ${np.id}""".execute
          else if (np.addrM == roadLink.endAddrMValue)
            sqlu"""UPDATE NODE_POINT SET NODE_NUMBER = ${node2.nodeNumber} WHERE ID = ${np.id}""".execute
        }
      )

      val nodePoints = nodePointDAO.fetchByNodeNumbers(Seq(node1.nodeNumber, node2.nodeNumber))
      nodePoints.length should be(2)
      nodePoints.exists(node => node.roadwayNumber == roadLink.roadwayNumber && node.beforeAfter == BeforeAfter.After && node.addrM == roadLink.startAddrMValue) should be(true)
      nodePoints.exists(node => node.roadwayNumber == roadLink.roadwayNumber && node.beforeAfter == BeforeAfter.Before && node.addrM == roadLink.endAddrMValue) should be(true)

      val roadGeom2 = Seq(Point(100.0, 0.0), Point(250.0, 0.0))
      val unchangedRoadLink = roadLink.copy(status = LinkStatus.UnChanged)
      val newRoadLink = dummyProjectLink(1, 1, Track.Combined, Discontinuity.Continuous, 100, 250, 100, 250, Some(DateTime.now()), None, 12346, 0, 150.0, SideCode.TowardsDigitizing, LinkStatus.New, 0, RoadType.PublicRoad, roadGeom2, roadwayNumber + 1)

      val newRoadways = Seq(
        Roadway(NewIdValue, roadLink.roadwayNumber, roadLink.roadNumber, roadLink.roadPartNumber, roadLink.roadType, roadLink.track, roadLink.discontinuity, roadLink.startAddrMValue, roadLink.endAddrMValue, reversed = false, roadLink.startDate.get, roadLink.endDate, "user", None, 8L, TerminationCode.NoTermination, DateTime.now, None),
        Roadway(NewIdValue, newRoadLink.roadwayNumber, newRoadLink.roadNumber, newRoadLink.roadPartNumber, newRoadLink.roadType, newRoadLink.track, newRoadLink.discontinuity, newRoadLink.startAddrMValue, newRoadLink.endAddrMValue, reversed = false, newRoadLink.startDate.get, newRoadLink.endDate, "user", None, 8L, TerminationCode.NoTermination, DateTime.now, None))
      val newLinearLocations = Seq(
        LinearLocation(NewIdValue, 1, roadLink.linkId, roadLink.startAddrMValue, roadLink.endAddrMValue, roadLink.sideCode, 0L, calibrationPoints = (CalibrationPointReference(Some(roadLink.startAddrMValue)), CalibrationPointReference(Some(roadLink.endAddrMValue))), roadGeom1, LinkGeomSource.NormalLinkInterface, roadLink.roadwayNumber, Some(DateTime.now), None),
        LinearLocation(NewIdValue, 2, newRoadLink.linkId, newRoadLink.startAddrMValue, newRoadLink.endAddrMValue, newRoadLink.sideCode, 0L, calibrationPoints = (CalibrationPointReference(Some(newRoadLink.startAddrMValue)), CalibrationPointReference(Some(newRoadLink.endAddrMValue))), roadGeom2, LinkGeomSource.NormalLinkInterface, newRoadLink.roadwayNumber, Some(DateTime.now), None))

      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(any[BoundingRectangle], any[Seq[(Int, Int)]])).thenReturn(newLinearLocations)
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(any[Set[Long]], any[Boolean])).thenReturn(newRoadways)

      nodesAndJunctionsService.expireObsoleteNodesAndJunctions(Seq(unchangedRoadLink, newRoadLink), Some(DateTime.now().minusDays(1)))

      // Test expired node and node points
      val nodePointsAfterExpiration = nodePointDAO.fetchByNodeNumbers(Seq(node1.nodeNumber, node2.nodeNumber))
      nodePointsAfterExpiration.length should be(1)
      nodePointsAfterExpiration.exists(node => node.roadwayNumber == roadLink.roadwayNumber && node.beforeAfter == BeforeAfter.After && node.addrM == roadLink.startAddrMValue) should be(true)
      nodePointsAfterExpiration.exists(node => node.roadwayNumber == roadLink.roadwayNumber && node.beforeAfter == BeforeAfter.Before && node.addrM == roadLink.endAddrMValue) should be(false)
    }
  }

  /**
    * Test case for New:
    * Create road number 1 part 1
    * * Assume road number 1 part 1 after new road
    *
    * Expected (the road was “extended”)
    * Node Points should be expired conditionally :
    * * If the remaining node points referenced by this nodeId are all present in the same road number, road part, track and road type
    * then all of those node points and the node itself should expire.
    */
  test("Test expireObsoleteNodesAndJunctions case When road is extended before the existing road") {
    runWithRollback {
      val roadGeom1 = Seq(Point(100.0, 0.0), Point(250.0, 0.0))

      val roadwayNumber = Sequences.nextRoadwayNumber
      val projectId = Sequences.nextViiteProjectId

      val roadLink = dummyProjectLink(1, 1, Track.Combined, Discontinuity.Continuous, 100, 250, 100, 250, Some(DateTime.now()), None, 12345, 100, 250.0, SideCode.TowardsDigitizing, LinkStatus.New, projectId, RoadType.PublicRoad, roadGeom1, roadwayNumber)

      val project = Project(projectId, ProjectState.Incomplete, "f", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
        "", Seq(), Seq(), None, None)


      val roadways = Seq(
        Roadway(NewIdValue, roadLink.roadwayNumber, roadLink.roadNumber, roadLink.roadPartNumber, roadLink.roadType, roadLink.track, roadLink.discontinuity, roadLink.startAddrMValue, roadLink.endAddrMValue, reversed = false, roadLink.startDate.get, roadLink.endDate, "user", None, 8L, TerminationCode.NoTermination, DateTime.now, None))
      val linearLocations = Seq(
        LinearLocation(NewIdValue, 1, roadLink.linkId, roadLink.startAddrMValue, roadLink.endAddrMValue, roadLink.sideCode, 0L, calibrationPoints = (CalibrationPointReference(Some(roadLink.startAddrMValue)), CalibrationPointReference(Some(roadLink.endAddrMValue))), roadGeom1, LinkGeomSource.NormalLinkInterface, roadLink.roadwayNumber, Some(DateTime.now), None))

      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(any[BoundingRectangle], any[Seq[(Int, Int)]])).thenReturn(linearLocations)
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(any[Set[Long]], any[Boolean])).thenReturn(roadways)

      val projectLinks = Seq(roadLink)
      buildTestDataForProject(Some(project), None, Some(linearLocations), Some(projectLinks))

      roadwayDAO.create(roadways)
      val projectChanges = List(
        //combined
        ProjectRoadwayChange(0, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(AddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, None, Some(PublicRoad), Some(Discontinuity.Continuous), Some(8L)),
            RoadwayChangeSection(Some(roadLink.roadNumber), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(roadLink.roadPartNumber), endRoadPartNumber = Some(roadLink.roadPartNumber), startAddressM = Some(roadLink.startAddrMValue), endAddressM = Some(roadLink.endAddrMValue), Some(RoadType.PublicRoad), Some(Discontinuity.Continuous), Some(8L)),
            Discontinuity.Continuous, RoadType.PublicRoad, reversed = false, 1, 8)
          , DateTime.now, Some(0L))
      )
      val mappedRoadwayNumbers = projectLinkDAO.fetchProjectLinksChange(projectId)
      // Creation of nodes and node points
      nodesAndJunctionsService.handleNodePointTemplates(projectChanges, projectLinks, mappedRoadwayNumbers)

      val node1 = Node(Sequences.nextNodeId, Sequences.nextNodeNumber, roadLink.geometry.head, None, NodeType.EndOfRoad, DateTime.now(), None, DateTime.now(), None, "user", Some(DateTime.now()))
      val node2 = Node(Sequences.nextNodeId, Sequences.nextNodeNumber, roadLink.geometry.last, None, NodeType.EndOfRoad, DateTime.now(), None, DateTime.now(), None, "user", Some(DateTime.now()))

      nodeDAO.create(Seq(node1, node2))

      val nodePointTemplates = projectLinks.flatMap(pl => nodePointDAO.fetchTemplatesByRoadwayNumber(pl.roadwayNumber))
      nodePointTemplates.length should be(2)

      nodePointTemplates.foreach(
        np => {
          if (np.addrM == roadLink.startAddrMValue)
            sqlu"""UPDATE NODE_POINT SET NODE_NUMBER = ${node1.nodeNumber} WHERE ID = ${np.id}""".execute
          else if (np.addrM == roadLink.endAddrMValue)
            sqlu"""UPDATE NODE_POINT SET NODE_NUMBER = ${node2.nodeNumber} WHERE ID = ${np.id}""".execute
        }
      )

      val nodePoints = nodePointDAO.fetchByNodeNumbers(Seq(node1.nodeNumber, node2.nodeNumber))
      nodePoints.length should be(2)
      nodePoints.exists(node => node.roadwayNumber == roadLink.roadwayNumber && node.beforeAfter == BeforeAfter.After && node.addrM == roadLink.startAddrMValue) should be(true)
      nodePoints.exists(node => node.roadwayNumber == roadLink.roadwayNumber && node.beforeAfter == BeforeAfter.Before && node.addrM == roadLink.endAddrMValue) should be(true)

      val roadGeom2 = Seq(Point(0.0, 0.0), Point(100.0, 0.0))
      val newRoadLink = dummyProjectLink(1, 1, Track.Combined, Discontinuity.Continuous, 0, 100, 0, 100, Some(DateTime.now()), None, 12346, 0, 100.0, SideCode.TowardsDigitizing, LinkStatus.New, 0, RoadType.PublicRoad, roadGeom2, roadwayNumber + 1)

      val newRoadways = Seq(
        Roadway(NewIdValue, roadLink.roadwayNumber, roadLink.roadNumber, roadLink.roadPartNumber, roadLink.roadType, roadLink.track, roadLink.discontinuity, roadLink.startAddrMValue, roadLink.endAddrMValue, reversed = false, roadLink.startDate.get, roadLink.endDate, "user", None, 8L, TerminationCode.NoTermination, DateTime.now, None),
        Roadway(NewIdValue, newRoadLink.roadwayNumber, newRoadLink.roadNumber, newRoadLink.roadPartNumber, newRoadLink.roadType, newRoadLink.track, newRoadLink.discontinuity, newRoadLink.startAddrMValue, newRoadLink.endAddrMValue, reversed = false, newRoadLink.startDate.get, newRoadLink.endDate, "user", None, 8L, TerminationCode.NoTermination, DateTime.now, None))
      val newLinearLocations = Seq(
        LinearLocation(NewIdValue, 1, roadLink.linkId, roadLink.startAddrMValue, roadLink.endAddrMValue, roadLink.sideCode, 0L, calibrationPoints = (CalibrationPointReference(Some(roadLink.startAddrMValue)), CalibrationPointReference(Some(roadLink.endAddrMValue))), roadGeom1, LinkGeomSource.NormalLinkInterface, roadLink.roadwayNumber, Some(DateTime.now), None),
        LinearLocation(NewIdValue, 2, newRoadLink.linkId, newRoadLink.startAddrMValue, newRoadLink.endAddrMValue, newRoadLink.sideCode, 0L, calibrationPoints = (CalibrationPointReference(Some(newRoadLink.startAddrMValue)), CalibrationPointReference(Some(newRoadLink.endAddrMValue))), roadGeom2, LinkGeomSource.NormalLinkInterface, newRoadLink.roadwayNumber, Some(DateTime.now), None))

      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(any[BoundingRectangle], any[Seq[(Int, Int)]])).thenReturn(newLinearLocations)
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(any[Set[Long]], any[Boolean])).thenReturn(newRoadways)

      val projectChangesAfterChanges = List(
        //combined
        ProjectRoadwayChange(0, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(AddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, None, Some(PublicRoad), Some(Discontinuity.Continuous), Some(8L)),
            RoadwayChangeSection(Some(newRoadLink.roadNumber), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(newRoadLink.roadPartNumber), endRoadPartNumber = Some(newRoadLink.roadPartNumber), startAddressM = Some(newRoadLink.startAddrMValue), endAddressM = Some(newRoadLink.endAddrMValue), Some(RoadType.PublicRoad), Some(Discontinuity.Continuous), Some(8L)),
            Discontinuity.Continuous, RoadType.PublicRoad, reversed = false, 1, 8)
          , DateTime.now, Some(0L)),
        ProjectRoadwayChange(0, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(AddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, None, Some(PublicRoad), Some(Discontinuity.Continuous), Some(8L)),
            RoadwayChangeSection(Some(roadLink.roadNumber), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(roadLink.roadPartNumber), endRoadPartNumber = Some(roadLink.roadPartNumber), startAddressM = Some(roadLink.startAddrMValue), endAddressM = Some(roadLink.endAddrMValue), Some(RoadType.PublicRoad), Some(Discontinuity.Continuous), Some(8L)),
            Discontinuity.Continuous, RoadType.PublicRoad, reversed = false, 1, 8)
          , DateTime.now, Some(0L))
      )
      val mappedRoadwayNumbers2 = projectLinkDAO.fetchProjectLinksChange(projectId)
      nodesAndJunctionsService.handleNodePointTemplates(projectChangesAfterChanges, Seq(roadLink, newRoadLink), mappedRoadwayNumbers2)
      nodesAndJunctionsService.expireObsoleteNodesAndJunctions(Seq(roadLink, newRoadLink), Some(DateTime.now().minusDays(1)))

      // Test expired node and node points
      val nodePointsAfterExpiration = nodePointDAO.fetchByNodeNumbers(Seq(node1.nodeNumber, node2.nodeNumber))
      nodePointsAfterExpiration.length should be(1)
      nodePointsAfterExpiration.exists(node => node.roadwayNumber == roadLink.roadwayNumber && node.beforeAfter == BeforeAfter.After && node.addrM == roadLink.startAddrMValue) should be(false)
      nodePointsAfterExpiration.exists(node => node.roadwayNumber == roadLink.roadwayNumber && node.beforeAfter == BeforeAfter.Before && node.addrM == roadLink.endAddrMValue) should be(true)
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
      val road1Link = dummyProjectLink(1, 1, Track.Combined, Discontinuity.Continuous, 0, 100, 0, 100, Some(DateTime.now()), None, 12345, 0, 100.0, SideCode.TowardsDigitizing, LinkStatus.New, projectId, RoadType.PublicRoad, roadGeom1, roadwayNumber1).copy(id = plId1)
      val road2Link = dummyProjectLink(2, 1, Track.Combined, Discontinuity.Continuous, 0, 150, 0, 150, Some(DateTime.now()), None, 12346, 0, 150.0, SideCode.TowardsDigitizing, LinkStatus.New, projectId, RoadType.PublicRoad, roadGeom2, roadwayNumber2).copy(id = plId2)

      val project = Project(projectId, ProjectState.Incomplete, "f", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
        "", Seq(), Seq(), None, None)

      val projectLinks = Seq(road1Link, road2Link)

      val roadways = Seq(
        Roadway(NewIdValue, road1Link.roadwayNumber, road1Link.roadNumber, road1Link.roadPartNumber, road1Link.roadType, road1Link.track, road1Link.discontinuity, road1Link.startAddrMValue, road1Link.endAddrMValue, reversed = false, road1Link.startDate.get, road1Link.endDate, "user", None, 8L, TerminationCode.NoTermination, DateTime.now, None),
        Roadway(NewIdValue, road2Link.roadwayNumber, road2Link.roadNumber, road2Link.roadPartNumber, road2Link.roadType, road2Link.track, road2Link.discontinuity, road2Link.startAddrMValue, road2Link.endAddrMValue, reversed = false, road2Link.startDate.get, road2Link.endDate, "user", None, 8L, TerminationCode.NoTermination, DateTime.now, None))
      val linearLocations = Seq(
        LinearLocation(NewIdValue, 1, road1Link.linkId, road1Link.startAddrMValue, road1Link.endAddrMValue, road1Link.sideCode, 0L, calibrationPoints = (CalibrationPointReference(Some(road1Link.startAddrMValue)), CalibrationPointReference(Some(road1Link.endAddrMValue))), roadGeom1, LinkGeomSource.NormalLinkInterface, road1Link.roadwayNumber, Some(DateTime.now), None),
        LinearLocation(NewIdValue, 1, road2Link.linkId, road2Link.startAddrMValue, road2Link.endAddrMValue, road2Link.sideCode, 0L, calibrationPoints = (CalibrationPointReference(Some(road2Link.startAddrMValue)), CalibrationPointReference(Some(road2Link.endAddrMValue))), roadGeom2, LinkGeomSource.NormalLinkInterface, road2Link.roadwayNumber, Some(DateTime.now), None))

      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(any[BoundingRectangle], any[Seq[(Int, Int)]])).thenReturn(linearLocations)
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(any[Set[Long]], any[Boolean])).thenReturn(roadways)

      roadwayDAO.create(roadways)
      buildTestDataForProject(Some(project), None, Some(linearLocations), Some(projectLinks))
      val projectChanges = List(
        //combined
        ProjectRoadwayChange(0, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(AddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, None, Some(PublicRoad), Some(Discontinuity.Continuous), Some(8L)),
            RoadwayChangeSection(Some(road1Link.roadNumber), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(road1Link.roadPartNumber), endRoadPartNumber = Some(road1Link.roadPartNumber), startAddressM = Some(road1Link.startAddrMValue), endAddressM = Some(road1Link.endAddrMValue), Some(RoadType.PublicRoad), Some(Discontinuity.Continuous), Some(8L)),
            Discontinuity.Continuous, RoadType.PublicRoad, reversed = false, 1, 8)
          , DateTime.now, Some(0L)),
        ProjectRoadwayChange(0, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(AddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, None, Some(PublicRoad), Some(Discontinuity.Continuous), Some(8L)),
            RoadwayChangeSection(Some(road2Link.roadNumber), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(road2Link.roadPartNumber), endRoadPartNumber = Some(road2Link.roadPartNumber), startAddressM = Some(road2Link.startAddrMValue), endAddressM = Some(road2Link.endAddrMValue), Some(RoadType.PublicRoad), Some(Discontinuity.Continuous), Some(8L)),
            Discontinuity.Continuous, RoadType.PublicRoad, reversed = false, 1, 8)
          , DateTime.now, Some(0L))
      )
      val mappedRoadwayNumbers = projectLinkDAO.fetchProjectLinksChange(projectId)
      // Creation of junction points template
      nodesAndJunctionsService.handleJunctionTemplates(projectChanges, projectLinks, mappedRoadwayNumbers)
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

      val terminatedRoadLink = road2Link.copy(endDate = Some(DateTime.now()), status = LinkStatus.Terminated, projectId = 1)

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
      val road1Link1 = dummyProjectLink(1, 1, Track.Combined, Discontinuity.Continuous, 0, 5, 0, 5, Some(DateTime.now()), None, 12345, 0, 5.0, SideCode.TowardsDigitizing, LinkStatus.New, projectId, RoadType.PublicRoad, roadGeom1Link1, roadwayNumber).copy(id = plId1)
      val road1Link2 = dummyProjectLink(1, 1, Track.Combined, Discontinuity.Continuous, 5, 10, 5, 10, Some(DateTime.now()), None, 12346, 5.0, 10.0, SideCode.TowardsDigitizing, LinkStatus.New, projectId, RoadType.PublicRoad, roadGeom1Link2, roadwayNumber).copy(id = plId2)
      val road2Link = dummyProjectLink(2, 1, Track.Combined, Discontinuity.Continuous, 0, 5, 0, 5, Some(DateTime.now()), None, 12347, 0, 5.0, SideCode.TowardsDigitizing, LinkStatus.New, projectId, RoadType.PublicRoad, roadGeom2, roadwayNumber + 1).copy(id = plId3)

      val roadways = Seq(
        Roadway(NewIdValue, road1Link1.roadwayNumber, road1Link1.roadNumber, road1Link1.roadPartNumber, road1Link1.roadType, road1Link1.track, road1Link1.discontinuity, road1Link1.startAddrMValue, road1Link2.endAddrMValue, reversed = false, road1Link1.startDate.get, road1Link1.endDate, "user", None, 8L, TerminationCode.NoTermination, DateTime.now, None),
        Roadway(NewIdValue, road2Link.roadwayNumber, road2Link.roadNumber, road2Link.roadPartNumber, road2Link.roadType, road2Link.track, road2Link.discontinuity, road2Link.startAddrMValue, road2Link.endAddrMValue, reversed = false, road2Link.startDate.get, road2Link.endDate, "user", None, 8L, TerminationCode.NoTermination, DateTime.now, None))
      val linearLocations = Seq(
        LinearLocation(NewIdValue, 1, road1Link1.linkId, road1Link1.startAddrMValue, road1Link1.endAddrMValue, road1Link1.sideCode, 0L, calibrationPoints = (CalibrationPointReference(Some(road1Link1.startAddrMValue)), CalibrationPointReference(Some(road1Link1.endAddrMValue))), roadGeom1Link1, LinkGeomSource.NormalLinkInterface, road1Link1.roadwayNumber, Some(DateTime.now), None),
        LinearLocation(NewIdValue, 2, road1Link2.linkId, road1Link2.startAddrMValue, road1Link2.endAddrMValue, road1Link2.sideCode, 0L, calibrationPoints = (CalibrationPointReference(Some(road1Link2.startAddrMValue)), CalibrationPointReference(Some(road1Link2.endAddrMValue))), roadGeom1Link2, LinkGeomSource.NormalLinkInterface, road1Link2.roadwayNumber, Some(DateTime.now), None),
        LinearLocation(NewIdValue, 1, road2Link.linkId, road2Link.startAddrMValue, road2Link.endAddrMValue, road2Link.sideCode, 0L, calibrationPoints = (CalibrationPointReference(Some(road2Link.startAddrMValue)), CalibrationPointReference(Some(road2Link.endAddrMValue))), roadGeom2, LinkGeomSource.NormalLinkInterface, road2Link.roadwayNumber, Some(DateTime.now), None))

      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(any[BoundingRectangle], any[Seq[(Int, Int)]])).thenReturn(linearLocations)
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(any[Set[Long]], any[Boolean])).thenReturn(roadways)

      val projectLinks = Seq(road1Link1, road1Link2, road2Link)

      roadwayDAO.create(roadways)
      buildTestDataForProject(Some(project), None, Some(linearLocations), Some(projectLinks))
      val projectChanges = List(
        //combined
        ProjectRoadwayChange(0, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(AddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, None, Some(PublicRoad), Some(Discontinuity.Continuous), Some(8L)),
            RoadwayChangeSection(Some(road1Link1.roadNumber), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(road1Link1.roadPartNumber), endRoadPartNumber = Some(road1Link1.roadPartNumber), startAddressM = Some(road1Link1.startAddrMValue), endAddressM = Some(road1Link1.endAddrMValue), Some(RoadType.PublicRoad), Some(Discontinuity.Continuous), Some(8L)),
            Discontinuity.Continuous, RoadType.PublicRoad, reversed = false, 1, 8)
          , DateTime.now, Some(0L)),
        ProjectRoadwayChange(0, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(AddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, None, Some(PublicRoad), Some(Discontinuity.Continuous), Some(8L)),
            RoadwayChangeSection(Some(road2Link.roadNumber), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(road2Link.roadPartNumber), endRoadPartNumber = Some(road2Link.roadPartNumber), startAddressM = Some(road2Link.startAddrMValue), endAddressM = Some(road2Link.endAddrMValue), Some(RoadType.PublicRoad), Some(Discontinuity.Continuous), Some(8L)),
            Discontinuity.Continuous, RoadType.PublicRoad, reversed = false, 1, 8)
          , DateTime.now, Some(0L))
      )
      val mappedRoadwayNumbers = projectLinkDAO.fetchProjectLinksChange(projectId)
      // Creation of junction points template
      nodesAndJunctionsService.handleJunctionTemplates(projectChanges, projectLinks, mappedRoadwayNumbers)
      val junctionPointTemplates = junctionPointDAO.fetchByRoadwayPointIds(
        roadwayPointDAO.fetchByRoadwayNumbers(projectLinks.map(_.roadwayNumber)).map(_.id))
      junctionPointTemplates.length should be(3)

      val junctions = junctionDAO.fetchByIds(junctionPointTemplates.map(_.junctionId))
      junctions.length should be(1)

      val terminatedRoadLink = road2Link.copy(endDate = Some(DateTime.now().minusDays(1)), status = LinkStatus.Terminated, projectId = 1)

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
      terminatedJunctionPoints.count(_.validTo.isDefined) should be (3)
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
     C3 R2  0
      <----0^
            0
            | C1 R1
            |

     0 => junction point

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

      val combLink1 = dummyProjectLink(road999, part1, Track.Combined, Discontinuity.Continuous, 0, 10, 0, 10, Some(DateTime.now()), None, 12345, 0, 10, SideCode.TowardsDigitizing, LinkStatus.New, projectId, RoadType.PublicRoad, combGeom1, rwNumber).copy(id = plId, projectId = projectId, roadwayId = rwId, linearLocationId = llId)
      val combLink2 = dummyProjectLink(road999, part1, Track.Combined, Discontinuity.Discontinuous, 10, 20, 10, 20, Some(DateTime.now()), None, 12346, 0, 10, SideCode.TowardsDigitizing, LinkStatus.New, projectId, RoadType.PublicRoad, combGeom2, rwNumber).copy(id = plId + 1, projectId = projectId, roadwayId = rwId, linearLocationId = llId + 1)
      val combLink3 = dummyProjectLink(road1000, part1, Track.Combined, Discontinuity.Discontinuous, 0, 9, 0, 9, Some(DateTime.now()), None, 12347, 0, 10, SideCode.TowardsDigitizing, LinkStatus.New, projectId, RoadType.PublicRoad, combGeom3, rwNumber + 1).copy(id = plId + 2, projectId = projectId, roadwayId = rwId + 1, linearLocationId = llId + 2)

      val project = Project(projectId, ProjectState.Incomplete, "f", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
        "", Seq(), Seq(), None, None)

      val combPLinks = Seq(combLink1, combLink2, combLink3)

      val (lc1, rw1): (LinearLocation, Roadway) = Seq(combLink1).map(toRoadwayAndLinearLocation).head
      val (lc2, rw2): (LinearLocation, Roadway) = Seq(combLink2).map(toRoadwayAndLinearLocation).head
      val (lc3, rw3): (LinearLocation, Roadway) = Seq(combLink3).map(toRoadwayAndLinearLocation).head
      val rw1WithId = rw1.copy(ely = 8L, startAddrMValue = 0, endAddrMValue = 20)
      val rw2WithId = rw3.copy(ely = 8L, startAddrMValue = 0, endAddrMValue = 9)
      val orderedlll1 = lc1.copy(orderNumber = 1)
      val orderedlll2 = lc2.copy(orderNumber = 2)
      val orderedlll3 = lc3.copy(orderNumber = 1)

      buildTestDataForProject(Some(project), Some(Seq(rw1WithId, rw2WithId)), Some(Seq(orderedlll1, orderedlll2, orderedlll3)), Some(Seq(combLink1, combLink2, combLink3)))

      val projectChanges = List(
        //Combined
        ProjectRoadwayChange(projectId, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(AddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, None, Some(PublicRoad), Some(Discontinuity.Continuous), Some(8L)),
            RoadwayChangeSection(Some(road999), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(part1), endRoadPartNumber = Some(part1), startAddressM = Some(0L), endAddressM = Some(20L), Some(RoadType.PublicRoad), Some(Discontinuity.Discontinuous), Some(8L)),
            Discontinuity.Discontinuous, RoadType.PublicRoad, reversed = false, 1, 8)
          , DateTime.now, Some(0L)),
        ProjectRoadwayChange(projectId, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(AddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, None, Some(PublicRoad), Some(Discontinuity.Continuous), Some(8L)),
            RoadwayChangeSection(Some(road1000), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(part1), endRoadPartNumber = Some(part1), startAddressM = Some(0L), endAddressM = Some(9L), Some(RoadType.PublicRoad), Some(Discontinuity.Discontinuous), Some(8L)),
            Discontinuity.Discontinuous, RoadType.PublicRoad, reversed = false, 2, 8)
          , DateTime.now, Some(0L))
      )

      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(any[BoundingRectangle], any[Seq[(Int, Int)]])).thenReturn(Seq(orderedlll1, orderedlll2, orderedlll3))
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(any[Set[Long]], any[Boolean])).thenReturn(Seq(rw1WithId, rw2WithId))

      val mappedReservedRoadwayNumbers = projectLinkDAO.fetchProjectLinksChange(projectId)
      roadAddressService.handleRoadwayPointsUpdate(projectChanges, mappedReservedRoadwayNumbers)
      nodesAndJunctionsService.handleNodePointTemplates(projectChanges, combPLinks, mappedReservedRoadwayNumbers)
      nodesAndJunctionsService.handleJunctionTemplates(projectChanges, combPLinks, mappedReservedRoadwayNumbers)

      val roadwayPoints = roadwayPointDAO.fetchByRoadwayNumbers(combPLinks.map(_.roadwayNumber))
      val junctionPointTemplates = junctionPointDAO.fetchByRoadwayPointIds(roadwayPoints.map(_.id))

      junctionPointTemplates.length should be(3)
      junctionPointTemplates.count(_.beforeAfter == BeforeAfter.Before) should be(1)
      junctionPointTemplates.count(_.beforeAfter == BeforeAfter.After) should be(2)
      val templateRoadwayNumbers = junctionPointTemplates.map(_.roadwayNumber).distinct
      val junctions = junctionDAO.fetchTemplatesByRoadwayNumbers(templateRoadwayNumbers)
      junctions.size should be(1)


      /*
      preparing expiring data
      /*

        R1  0
      <----0^
            0
            | R1
            |

      * Note:
        C: Combined track
      */
    */

      val project2 = Project(projectId + 1, ProjectState.Incomplete, "ProjectDeleteRoadOfRoadLinks", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
        "", Seq(), Seq(), None, None)
      val terminateAndTransferProjectChanges = List(
        ProjectRoadwayChange(projectId, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(AddressChangeType.Termination,
            RoadwayChangeSection(Some(road999), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(part1), endRoadPartNumber = Some(part1), startAddressM = Some(10L), endAddressM = Some(20L), Some(RoadType.PublicRoad), Some(Discontinuity.Discontinuous), Some(8L)),
            RoadwayChangeSection(None, None, None, None, None, None, Some(PublicRoad), Some(Discontinuity.Continuous), Some(8L)),
            Discontinuity.Discontinuous, RoadType.PublicRoad, reversed = false, 1, 8)
          , DateTime.now, Some(0L)),
        ProjectRoadwayChange(projectId, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(AddressChangeType.Transfer,
            RoadwayChangeSection(Some(road999), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(part1), endRoadPartNumber = Some(part1), startAddressM = Some(0L), endAddressM = Some(10L), Some(RoadType.PublicRoad), Some(Discontinuity.Continuous), Some(8L)),
            RoadwayChangeSection(Some(road999), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(part1), endRoadPartNumber = Some(part1), startAddressM = Some(0L), endAddressM = Some(10L), Some(RoadType.PublicRoad), Some(Discontinuity.Continuous), Some(8L)),
            Discontinuity.Continuous, RoadType.PublicRoad, reversed = false, 2, 8)
          , DateTime.now, Some(0L)),
        ProjectRoadwayChange(projectId, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(AddressChangeType.Transfer,
            RoadwayChangeSection(Some(road1000), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(part1), endRoadPartNumber = Some(part1), startAddressM = Some(0L), endAddressM = Some(9L), Some(RoadType.PublicRoad), Some(Discontinuity.Discontinuous), Some(8L)),
            RoadwayChangeSection(Some(road999), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(part1), endRoadPartNumber = Some(part1), startAddressM = Some(10L), endAddressM = Some(19L), Some(RoadType.PublicRoad), Some(Discontinuity.Discontinuous), Some(8L)),
            Discontinuity.Discontinuous, RoadType.PublicRoad, reversed = false, 3, 8)
          , DateTime.now, Some(0L))
      )

      linearLocationDAO.expireByRoadwayNumbers(Set(rw1WithId.roadwayNumber, rw2WithId.roadwayNumber))
      roadwayDAO.expireHistory(Set(rw1WithId.id, rw2WithId.id))

      val transferLinkRoad999 = dummyProjectLink(road999, part1, Track.Combined, Discontinuity.Continuous, 0, 10, 0, 10, Some(DateTime.now()), None, 12345, 0, 10, SideCode.TowardsDigitizing, LinkStatus.Transfer, projectId + 1, RoadType.PublicRoad, combGeom1, rwNumber + 3).copy(id = plId + 4, roadwayId = rwId + 3, linearLocationId = llId + 7)
      val terminatedLinkRoad999 = dummyProjectLink(road999, part1, Track.Combined, Discontinuity.Discontinuous, 10, 20, 10, 20, Some(DateTime.now()), None, 12346, 0, 10, SideCode.TowardsDigitizing, LinkStatus.Terminated, projectId + 1, RoadType.PublicRoad, combGeom2, rwNumber + 2).copy(id = plId + 3, roadwayId = rwId + 2, linearLocationId = llId + 6)
      val transferLinkRoadExRoad1000 = dummyProjectLink(road999, part1, Track.Combined, Discontinuity.Discontinuous, 10, 19, 0, 9, Some(DateTime.now()), None, 12347, 0, 9, SideCode.TowardsDigitizing, LinkStatus.Transfer, projectId + 1, RoadType.PublicRoad, combGeom3, rwNumber + 1).copy(id = plId + 5, roadwayId = rwId + 4, linearLocationId = llId + 8)
      val changedProjectLinks = Seq(transferLinkRoad999, terminatedLinkRoad999, transferLinkRoadExRoad1000)

      val (lc4, rw4): (LinearLocation, Roadway) = Seq(terminatedLinkRoad999).map(toRoadwayAndLinearLocation).head
      val (lc5, rw5): (LinearLocation, Roadway) = Seq(transferLinkRoad999).map(toRoadwayAndLinearLocation).head
      val (lc6, rw6): (LinearLocation, Roadway) = Seq(transferLinkRoadExRoad1000).map(toRoadwayAndLinearLocation).head
      val rw4WithId = rw4.copy(ely = 8L, id = terminatedLinkRoad999.roadwayId, endDate = Some(DateTime.now()))
      val rw5WithId = rw5.copy(ely = 8L, id = transferLinkRoad999.roadwayId)
      val rw6WithId = rw6.copy(ely = 8L, id = transferLinkRoadExRoad1000.roadwayId)
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
        rlc.copy(linearLocationId = pl.get.linearLocationId, originalRoadwayNumber = oldPl.get.roadwayNumber, originalRoadNumber = oldPl.get.roadNumber)
      }

      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(any[BoundingRectangle], any[Seq[(Int, Int)]])).thenReturn(Seq(orderedlll5, orderedlll6))
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(any[Set[Long]], any[Boolean])).thenReturn(Seq(rw5WithId, rw6WithId))

      projectLinkDAO.moveProjectLinksToHistory(projectId + 1)

      roadAddressService.handleRoadwayPointsUpdate(terminateAndTransferProjectChanges, projectRoadLinkChangesAfterInserts)
      nodesAndJunctionsService.handleNodePointTemplates(terminateAndTransferProjectChanges, changedProjectLinks, projectRoadLinkChangesAfterInserts)
      nodesAndJunctionsService.handleJunctionTemplates(terminateAndTransferProjectChanges, changedProjectLinks, projectRoadLinkChangesAfterInserts)

      /*
      ending expired data
       */
      val terminatedJunctionsBeforeExpire = junctionDAO.fetchExpiredByRoadwayNumbers(templateRoadwayNumbers)
      terminatedJunctionsBeforeExpire count (_.endDate.isDefined) should be(0)
      terminatedJunctionsBeforeExpire count (_.validTo.isDefined) should be(0)
      val endDate = Some(project2.startDate.minusDays(1))

      nodesAndJunctionsService.expireObsoleteNodesAndJunctions(Seq(terminatedLinkRoad999, transferLinkRoad999, transferLinkRoadExRoad1000), endDate)

      val oldValidJunctionsPoints = junctionPointDAO.fetchByIds(junctionPointTemplates.map(_.id))
      oldValidJunctionsPoints.isEmpty should be (true)

      val oldValidJunction = junctionDAO.fetchByIds(junctions.map(_.id))
      oldValidJunction.isEmpty should be (true)

      val rwPoints = roadwayPointDAO.fetchByRoadwayNumbers(Seq(terminatedLinkRoad999, transferLinkRoad999, transferLinkRoadExRoad1000).map(_.roadwayNumber)).map(_.id)
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

  def dummyHorizontalRoad(projectId: Long, numberOfLinearLocations: Int, roadNumber: Long, roadPartNumber: Long, rwNumber: Long, track: Track = Track.Combined, roadType: RoadType = RoadType.PublicRoad,
                          discontinuity: Discontinuity = Discontinuity.Continuous, firstPointAt: Point = Point(0.0, 0.0),
                          linkId: Long = 0, plId: Long = 0, rwId: Long = 0, llId: Long = 0, size: Int = 10): (Seq[ProjectLink], Seq[LinearLocation], Seq[Roadway]) = {
    val projectLinks = for (i: Int <- 0 until numberOfLinearLocations) yield {
      val startAddrM = i * size + firstPointAt.x.toLong
      val endAddrM = startAddrM + size
      val startPoint = firstPointAt.x + (i * size)
      val endPoint = startPoint + size
      val geom = Seq(Point(startPoint, 0.0), Point(endPoint, 0.0))
      val projectLink = dummyProjectLink(roadNumber, roadPartNumber, track, Discontinuity.Continuous, startAddrM, endAddrM, startAddrM, endAddrM, Some(DateTime.now()), None, linkId + i, 0, size,
        SideCode.TowardsDigitizing, LinkStatus.New, projectId, roadType, geom, rwNumber).copy(id = plId + i, roadwayId = rwId, linearLocationId = llId + i)
      projectLink
    }
    val (linearLocations, roadways) = projectLinks.map(toRoadwayAndLinearLocation).unzip
    (projectLinks,
        linearLocations,
        roadways.map(rw => rw.copy(ely = 8L, id = projectLinks.find(_.roadwayNumber == rw.roadwayNumber).get.roadwayId)))
  }

  /**
    * Test case for node points when road type changes
    */
  test("Test handleNodePointTemplates and expireObsoleteNodesAndJunctions when road type changes") {
    runWithRollback {
      /*  |--RT-1-->|--RT-3-->|--RT-1--|  */

      val projectId: Long = Sequences.nextViiteProjectId
      val project = Project(projectId, ProjectState.Incomplete, "f", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
        "", Seq(), Seq(), None, None)

      val rwNumber = Sequences.nextRoadwayNumber
      val rwId = Sequences.nextRoadwayId
      val plId = Sequences.nextProjectLinkId
      val llId = Sequences.nextLinearLocationId

      val (pl1, ll1, rw1) = dummyHorizontalRoad(projectId, 1,
        999L, 1L, rwNumber,
        firstPointAt = Point(0.0, 0.0), linkId = 12345, plId = plId, rwId = rwId, llId = llId)
      val (pl2, ll2, rw2) = dummyHorizontalRoad(projectId, 1, 999L, 1L, rwNumber + 1, roadType = RoadType.MunicipalityStreetRoad,
        firstPointAt = Point(10.0, 0.0), linkId = 12346, plId = plId + 1, rwId = rwId + 1, llId = llId + 1)
      val (pl3, ll3, rw3) = dummyHorizontalRoad(projectId, 1, 999L, 1L, rwNumber + 2, discontinuity = Discontinuity.EndOfRoad,
        firstPointAt = Point(20.0, 0.0), linkId = 12347, plId = plId + 2, rwId = rwId + 2, llId = llId + 2)

      val roadways = rw1 ++ rw2 ++ rw3
      val projectLinks = pl1 ++ pl2 ++ pl3
      val linearLocations = ll1 ++ ll2 ++ ll3

      buildTestDataForProject(Some(project), Some(roadways), Some(linearLocations), Some(projectLinks))

      val projectChanges = List(
        ProjectRoadwayChange(projectId, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(AddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, None, Some(PublicRoad), Some(Discontinuity.MinorDiscontinuity), Some(8L)),
            RoadwayChangeSection(Some(rw1.head.roadNumber), Some(rw1.head.track.value), startRoadPartNumber = Some(rw1.head.roadPartNumber), endRoadPartNumber = Some(rw1.last.roadPartNumber), startAddressM = Some(rw1.head.startAddrMValue), endAddressM = Some(rw1.head.endAddrMValue), Some(rw1.head.roadType), Some(rw1.last.discontinuity), Some(rw1.head.ely)),
            rw1.last.discontinuity, rw1.head.roadType, reversed = false, 1, rw1.head.ely)
          , DateTime.now, Some(0L)),

        ProjectRoadwayChange(projectId, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(AddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, None, Some(PublicRoad), Some(Discontinuity.MinorDiscontinuity), Some(8L)),
            RoadwayChangeSection(Some(rw2.head.roadNumber), Some(rw2.head.track.value), startRoadPartNumber = Some(rw2.head.roadPartNumber), endRoadPartNumber = Some(rw2.last.roadPartNumber), startAddressM = Some(rw2.head.startAddrMValue), endAddressM = Some(rw2.head.endAddrMValue), Some(rw2.head.roadType), Some(rw2.last.discontinuity), Some(rw2.head.ely)),
            rw2.last.discontinuity, rw2.head.roadType, reversed = false, 2, rw2.head.ely)
          , DateTime.now, Some(0L)),

        ProjectRoadwayChange(projectId, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(AddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, None, Some(PublicRoad), Some(Discontinuity.MinorDiscontinuity), Some(8L)),
            RoadwayChangeSection(Some(rw3.head.roadNumber), Some(rw3.head.track.value), startRoadPartNumber = Some(rw3.head.roadPartNumber), endRoadPartNumber = Some(rw3.last.roadPartNumber), startAddressM = Some(rw3.head.startAddrMValue), endAddressM = Some(rw3.head.endAddrMValue), Some(rw3.head.roadType), Some(rw3.last.discontinuity), Some(rw3.head.ely)),
            rw3.last.discontinuity, rw3.head.roadType, reversed = false, 3, rw3.head.ely)
          , DateTime.now, Some(0L))
      )

      val mappedReservedRoadwayNumbers = projectLinkDAO.fetchProjectLinksChange(projectId)
      roadAddressService.handleRoadwayPointsUpdate(projectChanges, mappedReservedRoadwayNumbers)
      nodesAndJunctionsService.handleNodePointTemplates(projectChanges, projectLinks, mappedReservedRoadwayNumbers)

      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(Point(0.0, 0.0), Point(0.0, 0.0)), roadNumberLimits)).thenReturn(ll1)
      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(Point(10.0, 0.0), Point(10.0, 0.0)), roadNumberLimits)).thenReturn(ll1 ++ ll2)
      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(Point(20.0, 0.0), Point(20.0, 0.0)), roadNumberLimits)).thenReturn(ll2 ++ ll3)
      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(Point(30.0, 0.0), Point(30.0, 0.0)), roadNumberLimits)).thenReturn(ll3)

      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(ll1.map(_.roadwayNumber).toSet, false)).thenReturn(rw1)
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(ll2.map(_.roadwayNumber).toSet, false)).thenReturn(rw2)
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers((ll1 ++ ll2).map(_.roadwayNumber).toSet, false)).thenReturn(rw1 ++ rw2)
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers((ll2 ++ ll3).map(_.roadwayNumber).toSet, false)).thenReturn(rw2 ++ rw3)
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(ll3.map(_.roadwayNumber).toSet, false)).thenReturn(rw3)

      nodesAndJunctionsService.handleJunctionTemplates(projectChanges, projectLinks, mappedReservedRoadwayNumbers)
      nodesAndJunctionsService.expireObsoleteNodesAndJunctions(pl2++ pl1 ++ pl3, Some(project.startDate.minusDays(1)))

      val nodePoints = projectLinks.flatMap(pl => nodePointDAO.fetchTemplatesByRoadwayNumber(pl.roadwayNumber))
      nodePoints.size should be(6)
    }
  }

  test("Test addOrUpdateNode When creating new Then new is created successfully") {
    runWithRollback {
      val node = Node(NewIdValue, Sequences.nextNodeNumber, Point(0, 0), Some("Node name"), NodeType.EndOfRoad,
        DateTime.now().withTimeAtStartOfDay(), None, DateTime.now(), None, "user", Some(DateTime.now()))
      nodesAndJunctionsService.addOrUpdateNode(node, false, node.createdBy) should be (node.nodeNumber)
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
      val historyRowEndDate = sql"""SELECT END_DATE from NODE
        WHERE NODE_NUMBER = ${node.nodeNumber} and valid_to is null and end_date is not null""".as[Date].firstOption
      historyRowEndDate should be(None)
    }
  }

  test("Test addOrUpdateNode When update existing Then existing is expired and new created") {
    runWithRollback {
      val node = Node(Sequences.nextNodeId, Sequences.nextNodeNumber, Point(0, 0), Some("Node name"), NodeType.EndOfRoad,
        DateTime.now().withTimeAtStartOfDay(), None, DateTime.now(), None, "user", Some(DateTime.now()))
      nodeDAO.create(Seq(node), node.createdBy)
      nodeDAO.fetchById(node.id) should not be None
      nodesAndJunctionsService.addOrUpdateNode(node.copy(coordinates = Point(1, 1)), false, node.createdBy) should be (node.nodeNumber)
      nodeDAO.fetchById(node.id) should be(None)
      val updated = nodeDAO.fetchByNodeNumber(node.nodeNumber).getOrElse(fail("Node not found"))
      updated.id should not be node.id
      updated.createdBy should be(node.createdBy)
      updated.createdTime should not be node.createdTime
      updated.publishedTime should be(None)
      updated.editor should be(None)
      updated.coordinates should be(Point(1, 1))
      val historyRowEndDate = sql"""SELECT END_DATE from NODE
        WHERE NODE_NUMBER = ${node.nodeNumber} and valid_to is null and end_date is not null""".as[Date].firstOption
      historyRowEndDate should be(None)
    }
  }

  test("Test addOrUpdateNode When update existing and change type but not start date Then existing is expired, new created") {
    runWithRollback {
      val node = Node(Sequences.nextNodeId, Sequences.nextNodeNumber, Point(0, 0), Some("Node name"), NodeType.EndOfRoad,
        DateTime.now().withTimeAtStartOfDay(), None, DateTime.now(), None, "user", Some(DateTime.now()))
      nodeDAO.create(Seq(node), node.createdBy)
      nodeDAO.fetchById(node.id) should not be None
      nodesAndJunctionsService.addOrUpdateNode(node.copy(coordinates = Point(1, 1), nodeType = NodeType.Bridge), false, node.createdBy) should be (node.nodeNumber)
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
      val historyRowEndDate = sql"""SELECT END_DATE from NODE
        WHERE NODE_NUMBER = ${node.nodeNumber} and valid_to is null and end_date is not null""".as[Date].firstOption
      historyRowEndDate should be(None)
    }
  }

  test("Test addOrUpdateNode When update existing and change type and start date Then existing is expired, history and new created") {
    runWithRollback {
      val node = Node(Sequences.nextNodeId, Sequences.nextNodeNumber, Point(0, 0), None, NodeType.EndOfRoad,
        DateTime.now().minusDays(1).withTimeAtStartOfDay(), None, DateTime.now(), None, "user", Some(DateTime.now()))
      nodeDAO.create(Seq(node), node.createdBy)
      nodeDAO.fetchById(node.id) should not be None
      nodesAndJunctionsService.addOrUpdateNode(node.copy(startDate = DateTime.now().plusDays(1).withTimeAtStartOfDay(),
        nodeType = NodeType.Bridge), false, node.createdBy) should be (node.nodeNumber)
      nodeDAO.fetchById(node.id) should be(None)
      val updated = nodeDAO.fetchByNodeNumber(node.nodeNumber).getOrElse(fail("Node not found"))
      updated.id should not be node.id
      updated.createdBy should be(node.createdBy)
      updated.createdTime should not be node.createdTime
      updated.publishedTime should be(None)
      updated.editor should be(None)
      updated.nodeType should be(NodeType.Bridge)
      updated.startDate should not be node.startDate
      val historyRowEndDate = sql"""SELECT END_DATE from NODE
        WHERE NODE_NUMBER = ${node.nodeNumber} and valid_to is null and end_date is not null""".as[Date].firstOption
      historyRowEndDate should not be None
    }
  }

  test("Test addOrUpdate When detached all junctions and nodePoints Then existing node is expired and new created new to history") {
    runWithRollback {
      val node = Node(Sequences.nextNodeId, Sequences.nextNodeNumber, Point(0, 0), Some("Node name"), NodeType.EndOfRoad,
        DateTime.now().withTimeAtStartOfDay(), None, DateTime.now(), None, "user", Some(DateTime.now()))
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
      before.map(_.id).toSet should not be (after.map(_.id).toSet)
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
      roadwayDAO.create(Seq(testRoadway1.copy(roadwayNumber = roadwayNumber2, roadNumber = 991)))
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
      roadwayDAO.create(Seq(testRoadway1.copy(roadwayNumber = roadwayNumber2, roadNumber = 991)))
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
      before.map(_.id).toSet should not be (after.map(_.id).toSet)
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
      val roadwayPointId2 = roadwayPointDAO.create(testRoadwayPoint2.copy(roadwayNumber = roadwayNumber2, addrMValue = 20))
      val junctionId1 = junctionDAO.create(Seq(testJunction1.copy(nodeNumber = Option(nodeNumber)))).head
      junctionPointDAO.create(Seq(testJunctionPoint1.copy(junctionId = junctionId1, roadwayPointId = roadwayPointId1)))

      val before = nodePointDAO.fetchByNodeNumbers(Seq(nodeNumber))
      nodesAndJunctionsService.calculateNodePointsForNode(nodeNumber, "TestUser")
      val after = nodePointDAO.fetchByNodeNumbers(Seq(nodeNumber))
      before.map(_.id).toSet should not be (after.map(_.id).toSet)
      val newNodePoints = nodePointDAO.fetchCalculatedNodePointsForNodeNumber(nodeNumber)
      newNodePoints.size should be(2)
      newNodePoints(0).addrM should be(10)
      newNodePoints(1).addrM should be(10)
    }
  }

  test("Test calculateNodePointsForNode When two track road part doesn't contain 'road node point'. Junction in Right Track. Then calculate node point") {
    runWithRollback {
      val nodeNumber = nodeDAO.create(Seq(testNode1)).head
      val roadwayNumber1 = Sequences.nextRoadwayNumber
      val roadwayNumber2 = Sequences.nextRoadwayNumber
      roadwayDAO.create(Seq(testRoadway1.copy(roadwayNumber = roadwayNumber1, track = Track.LeftSide)))
      roadwayDAO.create(Seq(testRoadway1.copy(roadwayNumber = roadwayNumber2, track = Track.RightSide)))
      val roadwayPointId1 = roadwayPointDAO.create(testRoadwayPoint1.copy(roadwayNumber = roadwayNumber1, addrMValue = 10))
      val roadwayPointId2 = roadwayPointDAO.create(testRoadwayPoint2.copy(roadwayNumber = roadwayNumber2, addrMValue = 20))
      val junctionId2 = junctionDAO.create(Seq(testJunction1.copy(nodeNumber = Option(nodeNumber)))).head
      junctionPointDAO.create(Seq(testJunctionPoint1.copy(junctionId = junctionId2, roadwayPointId = roadwayPointId2)))

      val before = nodePointDAO.fetchByNodeNumbers(Seq(nodeNumber))
      nodesAndJunctionsService.calculateNodePointsForNode(nodeNumber, "TestUser")
      val after = nodePointDAO.fetchByNodeNumbers(Seq(nodeNumber))
      before.map(_.id).toSet should not be (after.map(_.id).toSet)
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
      before.map(_.id).toSet should not be (after.map(_.id).toSet)
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
      roadwayDAO.create(Seq(testRoadway1.copy(roadwayNumber = roadwayNumber3, track = Track.Combined, roadNumber = 991)))
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
      before.map(_.id).toSet should not be (after.map(_.id).toSet)
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
      roadwayDAO.create(Seq(testRoadway1.copy(roadwayNumber = roadwayNumber3, track = Track.Combined, roadNumber = 991)))
      roadwayDAO.create(Seq(testRoadway1.copy(roadwayNumber = roadwayNumber4, startAddrMValue = 100, endAddrMValue = 200, track = Track.Combined, roadNumber = 991)))
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
      before.map(_.id).toSet should not be (after.map(_.id).toSet)
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
      roadwayDAO.create(Seq(testRoadway1.copy(roadwayNumber = roadwayNumber3, track = Track.Combined, roadNumber = 991)))
      roadwayDAO.create(Seq(testRoadway1.copy(roadwayNumber = roadwayNumber4, roadPartNumber = 2, startAddrMValue = 100, endAddrMValue = 200, track = Track.Combined, roadNumber = 991)))
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
      before.map(_.id).toSet should not be (after.map(_.id).toSet)
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
      roadwayDAO.create(Seq(testRoadway1.copy(roadwayNumber = roadwayNumber3, track = Track.Combined, roadNumber = 991)))
      val roadwayNumber4 = roadwayDAO.create(Seq(testRoadway1.copy(roadwayNumber = roadwayNumber5, startAddrMValue = 100, endAddrMValue = 200, track = Track.Combined, roadNumber = 991)))
      roadwayDAO.expireById(roadwayNumber4.toSet)
      roadwayDAO.create(Seq(testRoadway1.copy(roadwayNumber = roadwayNumber5, startAddrMValue = 100, endAddrMValue = 200, track = Track.Combined, roadNumber = 991)))
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
      before.map(_.id).toSet should not be (after.map(_.id).toSet)
      val newNodePoints = nodePointDAO.fetchCalculatedNodePointsForNodeNumber(nodeNumber)
      newNodePoints.size should be(2)
      newNodePoints(0).addrM should be(100) // (20 + 180) / 2
      newNodePoints(1).addrM should be(100) // (20 + 180) / 2
      newNodePoints(0).roadwayPointId should not be (newNodePoints(1).roadwayPointId)
    }
  }

}

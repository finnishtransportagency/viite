package fi.liikennevirasto.viite

import java.sql.Date

import fi.liikennevirasto.digiroad2.asset.{BoundingRectangle, LinkGeomSource, SideCode}
import fi.liikennevirasto.digiroad2.dao.Sequences
import fi.liikennevirasto.digiroad2.oracle.OracleDatabase
import fi.liikennevirasto.digiroad2.service.RoadLinkService
import fi.liikennevirasto.digiroad2.util.Track
import fi.liikennevirasto.digiroad2.{DigiroadEventBus, Point}
import fi.liikennevirasto.viite.RoadType.PublicRoad
import fi.liikennevirasto.viite.dao.{BeforeAfter, _}
import fi.liikennevirasto.viite.process.RoadwayAddressMapper
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
  private val roadPartNumber1 = 1
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
    DateTime.parse("2019-01-01"), None, DateTime.parse("2019-01-01"), None, Some("Test"), None)

  val testRoadwayPoint1 = RoadwayPoint(NewIdValue, roadwayNumber1, 0, "Test", None, None, None)

  val testNodePoint1 = NodePoint(NewIdValue, BeforeAfter.Before, -1, None, NodePointType.UnknownNodePointType,
    DateTime.parse("2019-01-01"), None, Some("Test"), None, 0, 0, 0, 0, Track.Combined, 0)

  val testJunction1 = Junction(NewIdValue, None, None, DateTime.parse("2019-01-01"), None,
    DateTime.parse("2019-01-01"), None, None, None)

  val testJunctionPoint1 = JunctionPoint(NewIdValue, BeforeAfter.Before, -1, -1,
    DateTime.parse("2019-01-01"), None, None, None, -1, 10, 0, 0, Track.Combined)

  val testLinearLocation1 = LinearLocation(NewIdValue, 1, 1000l, 0.0, 2.8, SideCode.TowardsDigitizing, 10000000000l,
    (None, None), Seq(Point(99.0, 99.0), Point(101.0, 101.0)), LinkGeomSource.NormalLinkInterface, -1)

  def dummyProjectLink(roadNumber: Long, roadPartNumber: Long, trackCode: Track, discontinuityType: Discontinuity, startAddrM: Long, endAddrM: Long, startDate: Option[DateTime], endDate: Option[DateTime] = None, linkId: Long = 0, startMValue: Double = 0,
                       endMValue: Double = 0, sideCode: SideCode = SideCode.Unknown, status: LinkStatus, projectId: Long = 0, roadType: RoadType = RoadType.PublicRoad, geometry: Seq[Point] = Seq(), roadwayNumber: Long) = {
    ProjectLink(0L, roadNumber, roadPartNumber, trackCode, discontinuityType, startAddrM, endAddrM, startAddrM, endAddrM, startDate, endDate,
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

  def toRoadwayAndLinearLocation(p: ProjectLink):(LinearLocation, Roadway) = {
    def calibrationPoint(cp: Option[ProjectLinkCalibrationPoint]): Option[Long] = {
      cp match {
        case Some(x) =>
          Some(x.addressMValue)
        case _ => Option.empty[Long]
      }
    }

    val startDate = p.startDate.getOrElse(DateTime.now()).minusDays(1)

    (LinearLocation(p.linearLocationId, 1, p.linkId, p.startMValue, p.endMValue, p.sideCode, p.linkGeometryTimeStamp,
      (calibrationPoint(p.calibrationPoints._1), calibrationPoint(p.calibrationPoints._2)), p.geometry, p.linkGeomSource,
      p.roadwayNumber, Some(startDate), p.endDate),
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
      val projectId = Sequences.nextViitePrimaryKeySeqValue
      val linearLocationId = Sequences.nextLinearLocationId
      val roadwayId = Sequences.nextRoadwayId

      val project = Project(projectId, ProjectState.Incomplete, "f", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
        "", Seq(), Seq(), None, None)
      val id = Sequences.nextViitePrimaryKeySeqValue


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
          (Some(0l), None), Seq(Point(0.0, 0.0), Point(50.0, 0.0)), LinkGeomSource.ComplementaryLinkInterface,
          roadwayNumber, Some(DateTime.parse("2000-01-01")), None),
        LinearLocation(linearLocationId + 1, 1, 2000l, 0.0, 10.0, SideCode.TowardsDigitizing, 10000000000l,
          (None, None), Seq(Point(0.0, 10.0), Point(50.0, 5.0)), LinkGeomSource.ComplementaryLinkInterface,
          roadwayNumber + 1, Some(DateTime.parse("2000-01-01")), None),
        LinearLocation(linearLocationId + 2, 1, 3000l, 0.0, 10.0, SideCode.TowardsDigitizing, 10000000000l,
          (None, None), Seq(Point(50.0, 5.0), Point(100.0, 5.0)), LinkGeomSource.ComplementaryLinkInterface,
          roadwayNumber + 2, Some(DateTime.parse("2000-01-01")), None),
        LinearLocation(linearLocationId + 3, 1, 3000l, 0.0, 10.0, SideCode.TowardsDigitizing, 10000000000l,
          (None, Some(150l)), Seq(Point(100.0, 5.0), Point(150.0, 5.0)), LinkGeomSource.ComplementaryLinkInterface,
          roadwayNumber + 3, Some(DateTime.parse("2000-01-01")), None)
      )
      val roadwayIds = roadwayDAO.create(roadways)

      val left = dummyProjectLink(999, 999, Track.LeftSide, Discontinuity.Continuous, 0, 50, Some(DateTime.now()), None, 12345, 0, 50, SideCode.TowardsDigitizing, LinkStatus.Transfer, 0L, RoadType.PublicRoad, leftGeom, roadwayNumber).copy(id = id, projectId = projectId, roadwayId = roadwayId, linearLocationId = linearLocationId)
      val right = dummyProjectLink(999, 999, Track.RightSide, Discontinuity.Continuous, 0, 50, Some(DateTime.now()), None, 12346, 0, 50, SideCode.TowardsDigitizing, LinkStatus.Transfer, 0L, RoadType.PublicRoad, rightGeom, roadwayNumber + 1).copy(id = id + 1, projectId = projectId, roadwayId = roadwayId + 1, linearLocationId = linearLocationId + 1)
      val combined1 = dummyProjectLink(999, 999, Track.Combined, Discontinuity.Continuous, 50, 100, Some(DateTime.now()), None, 12347, 0, 50, SideCode.TowardsDigitizing, LinkStatus.Transfer, 0L, RoadType.FerryRoad, combGeom1, roadwayNumber + 2).copy(id = id + 2, projectId = projectId, roadwayId = roadwayId + 2, linearLocationId = linearLocationId + 2)
      val combined2 = dummyProjectLink(999, 999, Track.Combined, Discontinuity.EndOfRoad, 100, 150, Some(DateTime.now()), None, 12348, 0, 50, SideCode.TowardsDigitizing, LinkStatus.Transfer, 0L, RoadType.PublicRoad, combGeom2, roadwayNumber + 3).copy(id = id + 3, projectId = projectId, roadwayId = roadwayId + 3, linearLocationId = linearLocationId + 3)
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
      val node1 = fetchedNodesPoints.find(n => n.roadwayNumber == left.roadwayNumber && n.beforeAfter == BeforeAfter.After)
      node1.isEmpty should be(true)
      val node2 = fetchedNodesPoints.find(n => n.roadwayNumber == left.roadwayNumber && n.beforeAfter == BeforeAfter.Before)
      node2.isEmpty should be(true)
      val node3 = fetchedNodesPoints.find(n => n.roadwayNumber == right.roadwayNumber && n.beforeAfter == BeforeAfter.After)
      node3.nonEmpty should be(true)
      val node4 = fetchedNodesPoints.find(n => n.roadwayNumber == right.roadwayNumber && n.beforeAfter == BeforeAfter.Before)
      node4.nonEmpty should be(true)
      val node5 = fetchedNodesPoints.find(n => n.roadwayNumber == combined1.roadwayNumber && n.beforeAfter == BeforeAfter.After)
      node5.nonEmpty should be(true)
      val node6 = fetchedNodesPoints.find(n => n.roadwayNumber == combined2.roadwayNumber && n.beforeAfter == BeforeAfter.Before)
      node6.nonEmpty should be(true)

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

  test("Test nodesAndJunctionsService.handleJunctionPointTemplates roadsToHead case When creating projectlinks Then junction template and junctions points should be handled/created properly and" +
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
      val linearLocation = LinearLocation(linearLocationId, 1, 12345, 0L, 10L, SideCode.TowardsDigitizing, 0L, calibrationPoints = (Some(0), Some(10)), geom1, LinkGeomSource.NormalLinkInterface, roadwayNumber, None, None)
      val projectId = Sequences.nextViitePrimaryKeySeqValue
      val plId1 = projectId + 1

      val project = Project(projectId, ProjectState.Incomplete, "f", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
        "", Seq(), Seq(), None, None)

      val link1 = dummyProjectLink(road1000, part, Track.Combined, Discontinuity.EndOfRoad, 0, 10, Some(DateTime.now()), None, 12346, 0, 10, SideCode.TowardsDigitizing, LinkStatus.Transfer, projectId, RoadType.PublicRoad, geom2, roadwayNumber + 1).copy(id = plId1 + 1, roadwayId = rwId + 1, linearLocationId = linearLocationId + 1)


      val linearLocations = Seq(
        LinearLocation(linearLocationId, 1, 1000l, 0.0, 50.0, SideCode.TowardsDigitizing, 10000000000l,
          (Some(0l), None), Seq(Point(0.0, 0.0), Point(10.0, 0.0)), LinkGeomSource.ComplementaryLinkInterface,
          roadwayNumber, Some(DateTime.parse("2000-01-01")), None),
        LinearLocation(linearLocationId + 1, 1, 2000l, 0.0, 10.0, SideCode.TowardsDigitizing, 10000000000l,
          (None, None), Seq(Point(10.0, 0.0), Point(20.0, 0.0)), LinkGeomSource.ComplementaryLinkInterface,
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
      nodesAndJunctionsService.handleJunctionPointTemplates(projectChanges, pls, mappedRoadwayNumbers)

      val roadJunctionPoints = junctionPointDAO.fetchJunctionPointsByRoadwayPoints(roadway.roadwayNumber, roadway.endAddrMValue, BeforeAfter.Before)
      val junction1 = junctionDAO.fetchByIds(Seq(roadJunctionPoints.head.junctionId))

      val linkJunctionPoints = junctionPointDAO.fetchJunctionPointsByRoadwayPoints(link1.roadwayNumber, link1.startAddrMValue, BeforeAfter.After)
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
      nodesAndJunctionsService.handleJunctionPointTemplates(reversedProjectChanges, reversedPls, mappedReservedRoadwayNumbers)
      val reversedLinkJunctionPoints = junctionPointDAO.fetchJunctionPointsByRoadwayPoints(link1.roadwayNumber, roadwayPointsAfterUpdate.head.addrMValue, BeforeAfter.Before)
      linkJunctionPoints.head.id should be(reversedLinkJunctionPoints.head.id)
      reversedLinkJunctionPoints.head.beforeAfter should be(BeforeAfter.switch(linkJunctionPoints.head.beforeAfter))
    }
  }

  test("Test nodesAndJunctionsService.handleJunctionPointTemplates roadsFromHead case When creating projectLinks Then junction template and junctions points should be handled/created properly and " +
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
      val linearLocation = LinearLocation(linearLocationId, 1, 12345, 0L, 10L, SideCode.AgainstDigitizing, 0L, calibrationPoints = (None, Some(10)), geom1, LinkGeomSource.NormalLinkInterface, roadwayNumber, None, None)
      val linearLocation2 = LinearLocation(linearLocationId + 1, 1, 12346, 0L, 10L, SideCode.TowardsDigitizing, 0L, calibrationPoints = (None, Some(10)), geom1, LinkGeomSource.NormalLinkInterface, roadwayNumber + 1, None, None)

      val projectId = Sequences.nextViitePrimaryKeySeqValue

      val link1 = dummyProjectLink(road1000, part, Track.Combined, Discontinuity.EndOfRoad, 0, 10, Some(DateTime.now()), None, 12346, 0, 10, SideCode.TowardsDigitizing, LinkStatus.Transfer, projectId, RoadType.PublicRoad, geom2, roadwayNumber + 1).copy(id = projectId + 1, roadwayId = rwId + 1, linearLocationId = linearLocationId + 1)

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
      nodesAndJunctionsService.handleJunctionPointTemplates(projectChanges, pls, mappedRoadwayNumbers)

      val roadJunctionPoints = junctionPointDAO.fetchJunctionPointsByRoadwayPoints(roadway.roadwayNumber, roadway.startAddrMValue, BeforeAfter.After)
      val junction1 = junctionDAO.fetchByIds(Seq(roadJunctionPoints.head.junctionId))

      val linkJunctionPoints = junctionPointDAO.fetchJunctionPointsByRoadwayPoints(link1.roadwayNumber, link1.startAddrMValue, BeforeAfter.After)
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
      nodesAndJunctionsService.handleJunctionPointTemplates(reversedProjectChanges, reversedPls, mappedReversedRoadwayNumbers)
      val reversedLinkJunctionPoints = junctionPointDAO.fetchJunctionPointsByRoadwayPoints(link1.roadwayNumber, roadwayPointsAfterUpdate.head.addrMValue, BeforeAfter.Before)
      linkJunctionPoints.head.id should be(reversedLinkJunctionPoints.head.id)
      reversedLinkJunctionPoints.head.beforeAfter should be(BeforeAfter.switch(linkJunctionPoints.head.beforeAfter))
    }
  }

  test("Test nodesAndJunctionsService.handleJunctionPointTemplates roadsToTail case When creating projectLinks Then junction template and junctions points should be handled/created properly and " +
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
      val linearLocation = LinearLocation(linearLocationId, 1, 12345, 0L, 10L, SideCode.TowardsDigitizing, 0L, calibrationPoints = (Some(0), Some(10)), geom1, LinkGeomSource.NormalLinkInterface, roadwayNumber, None, None)
      val linearLocation2 = LinearLocation(linearLocationId + 1, 1, 12346, 0L, 10L, SideCode.TowardsDigitizing, 0L, calibrationPoints = (Some(0), Some(10)), geom2, LinkGeomSource.NormalLinkInterface, roadwayNumber + 1, None, None)
      val projectId = Sequences.nextViitePrimaryKeySeqValue

      val link1 = dummyProjectLink(road1000, part, Track.Combined, Discontinuity.EndOfRoad, 0, 10, Some(DateTime.now()), None, 12346, 0, 10, SideCode.AgainstDigitizing, LinkStatus.Transfer, projectId, RoadType.PublicRoad, geom2, roadwayNumber + 1).copy(id = projectId + 1, roadwayId = rwId + 1, linearLocationId = linearLocationId + 1)

      val project = Project(projectId, ProjectState.Incomplete, "f", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
        "", Seq(), Seq(), None, None)
      val pls = Seq(link1).map(_.copy(id = Sequences.nextViitePrimaryKeySeqValue))

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
      nodesAndJunctionsService.handleJunctionPointTemplates(projectChanges, pls, mappedRoadwayNumbers)

      val roadJunctionPoints = junctionPointDAO.fetchJunctionPointsByRoadwayPoints(roadway.roadwayNumber, roadway.endAddrMValue, BeforeAfter.Before)
      val junction1 = junctionDAO.fetchByIds(Seq(roadJunctionPoints.head.junctionId))

      val linkJunctionPoints = junctionPointDAO.fetchJunctionPointsByRoadwayPoints(link1.roadwayNumber, link1.endAddrMValue, BeforeAfter.Before)
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
      nodesAndJunctionsService.handleJunctionPointTemplates(reversedProjectChanges, reversedPls, mappedReversedRoadwayNumbers)
      val reversedLinkJunctionPoints = junctionPointDAO.fetchJunctionPointsByRoadwayPoints(link1.roadwayNumber, roadwayPointsAfterUpdate.head.addrMValue, BeforeAfter.After)
      linkJunctionPoints.head.id should be(reversedLinkJunctionPoints.head.id)
      reversedLinkJunctionPoints.head.beforeAfter should be(BeforeAfter.switch(linkJunctionPoints.head.beforeAfter))
    }
  }

  test("Test nodesAndJunctionsService.handleJunctionPointTemplates roadsFromTail case When creating projectLinks Then junction template and junctions points should be handled/created properly and" +
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
      val linearLocation = LinearLocation(linearLocationId, 1, 12345, 0L, 10L, SideCode.AgainstDigitizing, 0L, calibrationPoints = (Some(0), Some(10)), geom1, LinkGeomSource.NormalLinkInterface, roadwayNumber, None, None)
      val linearLocation2 = LinearLocation(linearLocationId + 1, 1, 12346, 0L, 10L, SideCode.AgainstDigitizing, 0L, calibrationPoints = (Some(0), Some(10)), geom2, LinkGeomSource.NormalLinkInterface, roadwayNumber + 1, None, None)

      val projectId = Sequences.nextViitePrimaryKeySeqValue

      val link1 = dummyProjectLink(road1000, part, Track.Combined, Discontinuity.EndOfRoad, 0, 10, Some(DateTime.now()), None, 12346, 0, 10, SideCode.AgainstDigitizing, LinkStatus.Transfer, projectId, RoadType.PublicRoad, geom2, roadwayNumber + 1).copy(id = projectId + 1, roadwayId = rwId + 1, linearLocationId = linearLocationId + 1)

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
      nodesAndJunctionsService.handleJunctionPointTemplates(projectChanges, pls, mappedRoadwayNumbers)

      val roadJunctionPoints = junctionPointDAO.fetchJunctionPointsByRoadwayPoints(roadway.roadwayNumber, roadway.startAddrMValue, BeforeAfter.After)
      val junction1 = junctionDAO.fetchByIds(Seq(roadJunctionPoints.head.junctionId))

      val linkJunctionPoints = junctionPointDAO.fetchJunctionPointsByRoadwayPoints(link1.roadwayNumber, link1.endAddrMValue, BeforeAfter.Before)
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
      nodesAndJunctionsService.handleJunctionPointTemplates(reversedProjectChanges, reversedPls, mappedReversedRoadwayNumbers)
      val reversedLinkJunctionPoints = junctionPointDAO.fetchJunctionPointsByRoadwayPoints(link1.roadwayNumber, roadwayPointsAfterUpdate.head.addrMValue, BeforeAfter.After)
      linkJunctionPoints.head.id should be(reversedLinkJunctionPoints.head.id)
      reversedLinkJunctionPoints.head.beforeAfter should be(BeforeAfter.switch(linkJunctionPoints.head.beforeAfter))
    }
  }

  test("Test nodesAndJunctionsService.handleJunctionPointTemplates When creating one road that connects itself with the head point of one link being discontinuous Then junction template and junctions points should be handled/created properly") {
    runWithRollback {
      /*
     |--L1-->|
             | \
             |   L2
             |    \
             R3    >|
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
      val plId = Sequences.nextViitePrimaryKeySeqValue


      val leftGeom1 = Seq(Point(0.0, 50.0), Point(5.0, 50.0))
      val leftGeom2 = Seq(Point(5.0, 50.0), Point(10.0, 45.0))
      val leftGeom3 = Seq(Point(10.0, 45.0), Point(10.0, 35.0))
      val rightGeom1 = Seq(Point(0.0, 40.0), Point(5.0, 40.0))
      val rightGeom2 = Seq(Point(5.0, 40.0), Point(10.0, 45.0))
      val rightGeom3 = Seq(Point(5.0, 50.0), Point(5.0, 40.0))
      val rightGeom4 = Seq(Point(5.0, 40.0), Point(50.0, 35.0))

      val leftLink1 = dummyProjectLink(road, part, Track.LeftSide, Discontinuity.Continuous, 0, 5, Some(DateTime.now()), None, 12345, 0, 5, SideCode.TowardsDigitizing, LinkStatus.Transfer, projectId, RoadType.PublicRoad, leftGeom1, rwNumber).copy(id = plId, projectId = projectId, roadwayId = rwId, linearLocationId = llId)
      val leftLink2 = dummyProjectLink(road, part, Track.LeftSide, Discontinuity.Continuous, 5, 10, Some(DateTime.now()), None, 12346, 0, 5, SideCode.TowardsDigitizing, LinkStatus.Transfer, projectId, RoadType.PublicRoad, leftGeom2, rwNumber).copy(id = plId+1, projectId = projectId, roadwayId = rwId, linearLocationId = llId+1)
      val leftLink3 = dummyProjectLink(road, part, Track.LeftSide, Discontinuity.EndOfRoad, 10, 20, Some(DateTime.now()), None, 12347, 0, 10, SideCode.TowardsDigitizing, LinkStatus.Transfer, projectId, RoadType.PublicRoad, leftGeom3, rwNumber).copy(id = plId+2, projectId = projectId, roadwayId = rwId, linearLocationId = llId+2)
      val rightLink1 = dummyProjectLink(road, part, Track.RightSide, Discontinuity.Continuous, 0, 5, Some(DateTime.now()), None, 12348, 0, 5, SideCode.TowardsDigitizing, LinkStatus.Transfer, projectId, RoadType.PublicRoad, rightGeom1, rwNumber+1).copy(id = plId+3, projectId = projectId, roadwayId = rwId+1, linearLocationId = llId+3)
      val rightLink2 = dummyProjectLink(road, part, Track.RightSide, Discontinuity.Discontinuous, 5, 10, Some(DateTime.now()), None, 12349, 0, 5, SideCode.TowardsDigitizing, LinkStatus.Transfer, projectId, RoadType.PublicRoad, rightGeom2, rwNumber+1).copy(id = plId+4, projectId = projectId, roadwayId = rwId+1, linearLocationId = llId+4)
      val rightLink3 = dummyProjectLink(road, part, Track.RightSide, Discontinuity.Continuous, 10, 15, Some(DateTime.now()), None, 12350, 0, 5, SideCode.TowardsDigitizing, LinkStatus.Transfer, projectId, RoadType.PublicRoad, rightGeom3, rwNumber+1).copy(id = plId+5, projectId = projectId, roadwayId = rwId+1, linearLocationId = llId+5)
      val rightLink4 = dummyProjectLink(road, part, Track.RightSide, Discontinuity.Continuous, 15, 20, Some(DateTime.now()), None, 12351, 0, 5, SideCode.TowardsDigitizing, LinkStatus.Transfer, projectId, RoadType.PublicRoad, rightGeom4, rwNumber+1).copy(id = plId+6, projectId = projectId, roadwayId = rwId+1, linearLocationId = llId+6)

      val project = Project(projectId, ProjectState.Incomplete, "f", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
        "", Seq(), Seq(), None, None)

      val leftPLinks = Seq(leftLink1, leftLink2, leftLink3)
      val rightPLinks = Seq(rightLink1, rightLink2, rightLink3, rightLink4)

      val (lc1, rw1): (LinearLocation, Roadway) = Seq(leftLink1).map(toRoadwayAndLinearLocation).head
      val (lc2, rw2): (LinearLocation, Roadway) = Seq(leftLink2).map(toRoadwayAndLinearLocation).head
      val (lc3, rw3): (LinearLocation, Roadway) = Seq(leftLink3).map(toRoadwayAndLinearLocation).head
      val (lc4, rw4): (LinearLocation, Roadway) = Seq(rightLink1).map(toRoadwayAndLinearLocation).head
      val (lc5, rw5): (LinearLocation, Roadway) = Seq(rightLink2).map(toRoadwayAndLinearLocation).head
      val (lc6, rw6): (LinearLocation, Roadway) = Seq(rightLink3).map(toRoadwayAndLinearLocation).head
      val (lc7, rw7): (LinearLocation, Roadway) = Seq(rightLink4).map(toRoadwayAndLinearLocation).head
      val rw1WithId = rw1.copy(ely = 8L, startAddrMValue = 0, endAddrMValue = 20)
      val rw2WithId = rw4.copy(ely = 8L, startAddrMValue = 0, endAddrMValue = 20)

      buildTestDataForProject(Some(project), Some(Seq(rw1WithId, rw2WithId)), Some(Seq(lc1, lc2, lc3, lc4, lc5, lc6, lc7)), Some(leftPLinks++rightPLinks))

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
            RoadwayChangeSection(None, None, None, None, None, None, Some(PublicRoad), Some(Discontinuity.Continuous), Some(8L)),
            RoadwayChangeSection(Some(road), Some(Track.RightSide.value.toLong), startRoadPartNumber = Some(part), endRoadPartNumber = Some(part), startAddressM = Some(0L), endAddressM = Some(10L), Some(RoadType.PublicRoad), Some(Discontinuity.Discontinuous), Some(8L)),
            Discontinuity.Discontinuous, RoadType.PublicRoad, reversed = false, 2, 8)
          , DateTime.now, Some(0L)),
        //combined
        ProjectRoadwayChange(projectId, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(AddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, None, Some(PublicRoad), Some(Discontinuity.Continuous), Some(8L)),
            RoadwayChangeSection(Some(road), Some(Track.LeftSide.value.toLong), startRoadPartNumber = Some(part), endRoadPartNumber = Some(part), startAddressM = Some(10L), endAddressM = Some(20L), Some(RoadType.PublicRoad), Some(Discontinuity.EndOfRoad), Some(8L)),
            Discontinuity.EndOfRoad, RoadType.PublicRoad, reversed = false, 3, 8)
          , DateTime.now, Some(0L)),
        //combined
        ProjectRoadwayChange(projectId, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(AddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, None, Some(PublicRoad), Some(Discontinuity.Continuous), Some(8L)),
            RoadwayChangeSection(Some(road), Some(Track.RightSide.value.toLong), startRoadPartNumber = Some(part), endRoadPartNumber = Some(part), startAddressM = Some(10L), endAddressM = Some(20L), Some(RoadType.PublicRoad), Some(Discontinuity.EndOfRoad), Some(8L)),
            Discontinuity.EndOfRoad, RoadType.PublicRoad, reversed = false, 4, 8)
          , DateTime.now, Some(0L))
      )

      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(any[BoundingRectangle], any[Seq[(Int, Int)]])).thenReturn(Seq(lc1, lc2, lc3, lc4, lc5, lc6, lc7))
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(any[Set[Long]], any[Boolean])).thenReturn(Seq(rw1WithId, rw2WithId))

      val mappedReservedRoadwayNumbers = projectLinkDAO.fetchProjectLinksChange(projectId)
      roadAddressService.handleRoadwayPointsUpdate(projectChanges, mappedReservedRoadwayNumbers)
      nodesAndJunctionsService.handleNodePointTemplates(projectChanges, leftPLinks++rightPLinks, mappedReservedRoadwayNumbers)
      nodesAndJunctionsService.handleJunctionPointTemplates(projectChanges, leftPLinks++rightPLinks, mappedReservedRoadwayNumbers)

      val roadwayPoints = roadwayPointDAO.fetchByRoadwayNumbers((leftPLinks++rightPLinks).map(_.roadwayNumber)).map(_.id)

      val junctionPointTemplates = junctionPointDAO.fetchByRoadwayPointIds(roadwayPoints)
      junctionPointTemplates.length should be(7)
    }
  }

  test("Test nodesAndJunctionsService.handleJunctionPointTemplates When creating one road that connects itself with the tail point of one link being discontinuous Then junction template and junctions points should be handled/created properly") {
    runWithRollback {
      /*
     |--L-->|
             |
             |
             |
             C1
             |
             |
             V
     |--R*-->0|
             |
             C2
             |
             v
            Note:
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
      val plId = Sequences.nextViitePrimaryKeySeqValue


      val leftGeom1 = Seq(Point(0.0, 50.0), Point(5.0, 50.0))
      val rightGeom1 = Seq(Point(0.0, 40.0), Point(5.0, 40.0))
      val combGeom1 = Seq(Point(5.0, 50.0), Point(5.0, 40.0))
      val combGeom2 = Seq(Point(5.0, 40.0), Point(50.0, 35.0))

      val leftLink1 = dummyProjectLink(road, part, Track.LeftSide, Discontinuity.Continuous, 0, 5, Some(DateTime.now()), None, 12345, 0, 5, SideCode.TowardsDigitizing, LinkStatus.Transfer, projectId, RoadType.PublicRoad, leftGeom1, rwNumber).copy(id = plId, projectId = projectId, roadwayId = rwId, linearLocationId = llId)
      val rightLink1 = dummyProjectLink(road, part, Track.RightSide, Discontinuity.Continuous, 0, 5, Some(DateTime.now()), None, 12346, 0, 5, SideCode.TowardsDigitizing, LinkStatus.Transfer, projectId, RoadType.PublicRoad, rightGeom1, rwNumber+1).copy(id = plId+1, projectId = projectId, roadwayId = rwId+1, linearLocationId = llId+1)
      val combLink1 = dummyProjectLink(road, part, Track.Combined, Discontinuity.Continuous, 10, 15, Some(DateTime.now()), None, 12347, 0, 5, SideCode.TowardsDigitizing, LinkStatus.Transfer, projectId, RoadType.PublicRoad, combGeom1, rwNumber+2).copy(id = plId+2, projectId = projectId, roadwayId = rwId+2, linearLocationId = llId+2)
      val combLink2 = dummyProjectLink(road, part, Track.Combined, Discontinuity.Continuous, 15, 20, Some(DateTime.now()), None, 12348, 0, 5, SideCode.TowardsDigitizing, LinkStatus.Transfer, projectId, RoadType.PublicRoad, combGeom2, rwNumber+2).copy(id = plId+3, projectId = projectId, roadwayId = rwId+2, linearLocationId = llId+3)

      val project = Project(projectId, ProjectState.Incomplete, "f", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
        "", Seq(), Seq(), None, None)

      val leftPLinks = Seq(leftLink1)
      val rightPLinks = Seq(rightLink1)
      val combPLinks = Seq(combLink1, combLink2)

      val (lc1, rw1): (LinearLocation, Roadway) = Seq(leftLink1).map(toRoadwayAndLinearLocation).head
      val (lc2, rw2): (LinearLocation, Roadway) = Seq(rightLink1).map(toRoadwayAndLinearLocation).head
      val (lc3, rw3): (LinearLocation, Roadway) = Seq(combLink1).map(toRoadwayAndLinearLocation).head
      val (lc4, rw4): (LinearLocation, Roadway) = Seq(combLink2).map(toRoadwayAndLinearLocation).head
      val rw1WithId = rw1.copy(ely = 8L, startAddrMValue = 0, endAddrMValue = 5)
      val rw2WithId = rw2.copy(ely = 8L, startAddrMValue = 0, endAddrMValue = 5)
      val rw3WithId = rw3.copy(ely = 8L, startAddrMValue = 5, endAddrMValue = 20)

      buildTestDataForProject(Some(project), Some(Seq(rw1WithId, rw2WithId, rw3WithId)), Some(Seq(lc1, lc2, lc3, lc4)), Some(leftPLinks++rightPLinks++combPLinks))

      val projectChanges = List(
        //left
        ProjectRoadwayChange(projectId, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(AddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, None, Some(PublicRoad), Some(Discontinuity.Continuous), Some(8L)),
            RoadwayChangeSection(Some(road), Some(Track.LeftSide.value.toLong), startRoadPartNumber = Some(part), endRoadPartNumber = Some(part), startAddressM = Some(0L), endAddressM = Some(5L), Some(RoadType.PublicRoad), Some(Discontinuity.Continuous), Some(8L)),
            Discontinuity.Continuous, RoadType.PublicRoad, reversed = false, 1, 8)
          , DateTime.now, Some(0L)),
        //right
        ProjectRoadwayChange(projectId, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(AddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, None, Some(PublicRoad), Some(Discontinuity.Continuous), Some(8L)),
            RoadwayChangeSection(Some(road), Some(Track.RightSide.value.toLong), startRoadPartNumber = Some(part), endRoadPartNumber = Some(part), startAddressM = Some(0L), endAddressM = Some(5L), Some(RoadType.PublicRoad), Some(Discontinuity.Discontinuous), Some(8L)),
            Discontinuity.Discontinuous, RoadType.PublicRoad, reversed = false, 2, 8)
          , DateTime.now, Some(0L)),
        //left
        ProjectRoadwayChange(projectId, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(AddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, None, Some(PublicRoad), Some(Discontinuity.Continuous), Some(8L)),
            RoadwayChangeSection(Some(road), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(part), endRoadPartNumber = Some(part), startAddressM = Some(5L), endAddressM = Some(15L), Some(RoadType.PublicRoad), Some(Discontinuity.Continuous), Some(8L)),
            Discontinuity.Continuous, RoadType.PublicRoad, reversed = false, 3, 8)
          , DateTime.now, Some(0L)),
        //right
        ProjectRoadwayChange(projectId, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(AddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, None, Some(PublicRoad), Some(Discontinuity.Continuous), Some(8L)),
            RoadwayChangeSection(Some(road), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(part), endRoadPartNumber = Some(part), startAddressM = Some(15L), endAddressM = Some(20L), Some(RoadType.PublicRoad), Some(Discontinuity.EndOfRoad), Some(8L)),
            Discontinuity.EndOfRoad, RoadType.PublicRoad, reversed = false, 4, 8)
          , DateTime.now, Some(0L))
      )

      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(any[BoundingRectangle], any[Seq[(Int, Int)]])).thenReturn(Seq(lc1, lc2, lc3, lc4))
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(any[Set[Long]], any[Boolean])).thenReturn(Seq(rw1WithId, rw2WithId, rw3WithId))

      val mappedReservedRoadwayNumbers = projectLinkDAO.fetchProjectLinksChange(projectId)
      roadAddressService.handleRoadwayPointsUpdate(projectChanges, mappedReservedRoadwayNumbers)
      nodesAndJunctionsService.handleNodePointTemplates(projectChanges, leftPLinks++rightPLinks++combPLinks, mappedReservedRoadwayNumbers)
      nodesAndJunctionsService.handleJunctionPointTemplates(projectChanges, leftPLinks++rightPLinks++combPLinks, mappedReservedRoadwayNumbers)

      val roadwayPoints = roadwayPointDAO.fetchByRoadwayNumbers((leftPLinks++rightPLinks++combPLinks).map(_.roadwayNumber)).map(_.id)

      val junctionPointTemplates = junctionPointDAO.fetchByRoadwayPointIds(roadwayPoints)
      //3 new junctions points template, 2 before 1 after
      junctionPointTemplates.length should be(3)
      //TODO check why test fails
    }
  }

  test("Test nodesAndJunctionsService.handleJunctionPointTemplates When creating new road parts that connects to other part in same road number Then junction template and junctions points should be handled/created properly") {
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
      val plId = Sequences.nextViitePrimaryKeySeqValue


      val combGeom1 = Seq(Point(0.0, 0.0), Point(10.0, 0.0))
      val combGeom2 = Seq(Point(10.0, 0.0), Point(20.0, 0.0))
      val combGeom3 = Seq(Point(10.0, 10.0), Point(10.0, 0.0))

      val combLink1 = dummyProjectLink(road, part1, Track.Combined, Discontinuity.Continuous, 0, 10, Some(DateTime.now()), None, 12345, 0, 10, SideCode.TowardsDigitizing, LinkStatus.Transfer, projectId, RoadType.PublicRoad, combGeom1, rwNumber).copy(id = plId, projectId = projectId, roadwayId = rwId, linearLocationId = llId)
      val combLink2 = dummyProjectLink(road, part2, Track.Combined, Discontinuity.Discontinuous, 10, 20, Some(DateTime.now()), None, 12346, 0, 10, SideCode.TowardsDigitizing, LinkStatus.Transfer, projectId, RoadType.PublicRoad, combGeom2, rwNumber+1).copy(id = plId+1, projectId = projectId, roadwayId = rwId+1, linearLocationId = llId+1)
      val combLink3 = dummyProjectLink(road, part3, Track.Combined, Discontinuity.EndOfRoad, 0, 10, Some(DateTime.now()), None, 12347, 0, 10, SideCode.TowardsDigitizing, LinkStatus.Transfer, projectId, RoadType.PublicRoad, combGeom3, rwNumber+2).copy(id = plId+2, projectId = projectId, roadwayId = rwId+2, linearLocationId = llId+2)

      val project = Project(projectId, ProjectState.Incomplete, "f", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
        "", Seq(), Seq(), None, None)

      val combPLinks = Seq(combLink1, combLink2, combLink3)

      val (lc1, rw1): (LinearLocation, Roadway) = Seq(combLink1).map(toRoadwayAndLinearLocation).head
      val (lc2, rw2): (LinearLocation, Roadway) = Seq(combLink2).map(toRoadwayAndLinearLocation).head
      val (lc3, rw3): (LinearLocation, Roadway) = Seq(combLink3).map(toRoadwayAndLinearLocation).head
      val rw1WithId = rw1.copy(ely = 8L, startAddrMValue = 0, endAddrMValue = 10)
      val rw2WithId = rw2.copy(ely = 8L, startAddrMValue = 0, endAddrMValue = 10)
      val rw3WithId = rw3.copy(ely = 8L, startAddrMValue = 0, endAddrMValue = 10)

      buildTestDataForProject(Some(project), Some(Seq(rw1WithId, rw2WithId, rw3WithId)), Some(Seq(lc1, lc2, lc3)), Some(combPLinks))

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

      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(any[BoundingRectangle], any[Seq[(Int, Int)]])).thenReturn(Seq(lc1, lc2, lc3))
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(any[Set[Long]], any[Boolean])).thenReturn(Seq(rw1WithId, rw2WithId))

      val mappedReservedRoadwayNumbers = projectLinkDAO.fetchProjectLinksChange(projectId)
      roadAddressService.handleRoadwayPointsUpdate(projectChanges, mappedReservedRoadwayNumbers)
      nodesAndJunctionsService.handleNodePointTemplates(projectChanges, combPLinks, mappedReservedRoadwayNumbers)
      nodesAndJunctionsService.handleJunctionPointTemplates(projectChanges, combPLinks, mappedReservedRoadwayNumbers)

      val roadwayPoints = roadwayPointDAO.fetchByRoadwayNumbers(combPLinks.map(_.roadwayNumber)).map(_.id)

      val junctionPointTemplates = junctionPointDAO.fetchByRoadwayPointIds(roadwayPoints)
      //3 new junctions points template, 2 before 1 after (because of EndOfRoad in part3 creates 3)
      junctionPointTemplates.length should be(3)
    }
  }

  test("Test nodesAndJunctionsService.handleJunctionPointTemplates When creating new ramps road part that connects to other part in same road number Then junction template and junctions points should be handled/created properly") {
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
      val plId = Sequences.nextViitePrimaryKeySeqValue


      val combGeom1 = Seq(Point(0.0, 0.0), Point(10.0, 0.0))
      val combGeom2 = Seq(Point(10.0, 0.0), Point(20.0, 0.0))
      val combGeom3 = Seq(Point(10.0, 0.0), Point(20.0, 1.0))

      val combLink1 = dummyProjectLink(road, part1, Track.Combined, Discontinuity.Continuous, 0, 10, Some(DateTime.now()), None, 12345, 0, 10, SideCode.TowardsDigitizing, LinkStatus.Transfer, projectId, RoadType.PublicRoad, combGeom1, rwNumber).copy(id = plId, projectId = projectId, roadwayId = rwId, linearLocationId = llId)
      val combLink2 = dummyProjectLink(road, part1, Track.Combined, Discontinuity.Discontinuous, 10, 20, Some(DateTime.now()), None, 12346, 0, 10, SideCode.TowardsDigitizing, LinkStatus.Transfer, projectId, RoadType.PublicRoad, combGeom2, rwNumber).copy(id = plId+1, projectId = projectId, roadwayId = rwId, linearLocationId = llId+1)
      val combLink3 = dummyProjectLink(road, part2, Track.Combined, Discontinuity.EndOfRoad, 0, 15, Some(DateTime.now()), None, 12347, 0, 15, SideCode.TowardsDigitizing, LinkStatus.Transfer, projectId, RoadType.PublicRoad, combGeom3, rwNumber+1).copy(id = plId+2, projectId = projectId, roadwayId = rwId+1, linearLocationId = llId+2)

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
      nodesAndJunctionsService.handleNodePointTemplates(projectChanges, combPLinks, mappedReservedRoadwayNumbers)
      nodesAndJunctionsService.handleJunctionPointTemplates(projectChanges, combPLinks, mappedReservedRoadwayNumbers)

      val roadwayPoints = roadwayPointDAO.fetchByRoadwayNumbers(combPLinks.map(_.roadwayNumber)).map(_.id)

      val junctionPointTemplates = junctionPointDAO.fetchByRoadwayPointIds(roadwayPoints)
      //3 new junctions points template, 1 before 2 after
      junctionPointTemplates.length should be(3)
      //TODO check why test fails
    }
  }

  test("Test nodesAndJunctionsService.handleJunctionPointTemplates When creating one roundabout road part that connects to same part in same road number and the connecting link is EndOfRoad Then junction template and junctions points should be handled/created properly") {
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
      val plId = Sequences.nextViitePrimaryKeySeqValue


      val combGeom1 = Seq(Point(0.0, 5.0), Point(5.0, 5.0))
      val combGeom2 = Seq(Point(5.0, 5.0), Point(5.0, 0.0))
      val combGeom3 = Seq(Point(5.0, 0.0), Point(0.0, 0.0))
      val combGeom4 = Seq(Point(0.0, 0.0), Point(0.0, 5.0))

      val combLink1 = dummyProjectLink(road, part, Track.Combined, Discontinuity.Continuous, 0, 5, Some(DateTime.now()), None, 12345, 0, 5, SideCode.TowardsDigitizing, LinkStatus.Transfer, projectId, RoadType.PublicRoad, combGeom1, rwNumber).copy(id = plId, projectId = projectId, roadwayId = rwId, linearLocationId = llId)
      val combLink2 = dummyProjectLink(road, part, Track.Combined, Discontinuity.Continuous, 5, 10, Some(DateTime.now()), None, 12346, 0, 5, SideCode.TowardsDigitizing, LinkStatus.Transfer, projectId, RoadType.PublicRoad, combGeom2, rwNumber).copy(id = plId+1, projectId = projectId, roadwayId = rwId, linearLocationId = llId+1)
      val combLink3 = dummyProjectLink(road, part, Track.Combined, Discontinuity.Continuous, 10, 15, Some(DateTime.now()), None, 12347, 0, 5, SideCode.TowardsDigitizing, LinkStatus.Transfer, projectId, RoadType.PublicRoad, combGeom3, rwNumber).copy(id = plId+2, projectId = projectId, roadwayId = rwId, linearLocationId = llId+2)
      val combLink4 = dummyProjectLink(road, part, Track.Combined, Discontinuity.EndOfRoad, 15, 20, Some(DateTime.now()), None, 12348, 0, 5, SideCode.TowardsDigitizing, LinkStatus.Transfer, projectId, RoadType.PublicRoad, combGeom4, rwNumber).copy(id = plId+3, projectId = projectId, roadwayId = rwId, linearLocationId = llId+3)

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
            RoadwayChangeSection(Some(road), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(part), endRoadPartNumber = Some(part), startAddressM = Some(5L), endAddressM = Some(20L), Some(RoadType.PublicRoad), Some(Discontinuity.EndOfRoad), Some(8L)),
            Discontinuity.EndOfRoad, RoadType.PublicRoad, reversed = false, 2, 8)
          , DateTime.now, Some(0L))
      )

      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(any[BoundingRectangle], any[Seq[(Int, Int)]])).thenReturn(Seq(lc1, lc2, lc3, lc4))
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(any[Set[Long]], any[Boolean])).thenReturn(Seq(rw1WithId))

      val mappedReservedRoadwayNumbers = projectLinkDAO.fetchProjectLinksChange(projectId)
      roadAddressService.handleRoadwayPointsUpdate(projectChanges, mappedReservedRoadwayNumbers)
      nodesAndJunctionsService.handleNodePointTemplates(projectChanges, combPLinks, mappedReservedRoadwayNumbers)
      nodesAndJunctionsService.handleJunctionPointTemplates(projectChanges, combPLinks, mappedReservedRoadwayNumbers)

      val roadwayPoints = roadwayPointDAO.fetchByRoadwayNumbers(combPLinks.map(_.roadwayNumber)).map(_.id)

      val junctionPointTemplates = junctionPointDAO.fetchByRoadwayPointIds(roadwayPoints)
      //2 new junctions points template, 1 before 1 after
      junctionPointTemplates.length should be(2)
    }
  }

  test("Test nodesAndJunctionsService.handleJunctionPointTemplates When creating one roundabout road part that connects to same part in same road number and the connecting link is Discontinuous Then junction template and junctions points should be handled/created properly") {
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
      val plId = Sequences.nextViitePrimaryKeySeqValue


      val combGeom1 = Seq(Point(0.0, 5.0), Point(5.0, 5.0))
      val combGeom2 = Seq(Point(5.0, 5.0), Point(5.0, 0.0))
      val combGeom3 = Seq(Point(5.0, 0.0), Point(0.0, 0.0))
      val combGeom4 = Seq(Point(0.0, 0.0), Point(0.0, 5.0))

      val combLink1 = dummyProjectLink(road, part, Track.Combined, Discontinuity.Continuous, 0, 5, Some(DateTime.now()), None, 12345, 0, 5, SideCode.TowardsDigitizing, LinkStatus.Transfer, projectId, RoadType.PublicRoad, combGeom1, rwNumber).copy(id = plId, projectId = projectId, roadwayId = rwId, linearLocationId = llId)
      val combLink2 = dummyProjectLink(road, part, Track.Combined, Discontinuity.Continuous, 5, 10, Some(DateTime.now()), None, 12346, 0, 5, SideCode.TowardsDigitizing, LinkStatus.Transfer, projectId, RoadType.PublicRoad, combGeom2, rwNumber).copy(id = plId+1, projectId = projectId, roadwayId = rwId, linearLocationId = llId+1)
      val combLink3 = dummyProjectLink(road, part, Track.Combined, Discontinuity.Continuous, 10, 15, Some(DateTime.now()), None, 12347, 0, 5, SideCode.TowardsDigitizing, LinkStatus.Transfer, projectId, RoadType.PublicRoad, combGeom3, rwNumber).copy(id = plId+2, projectId = projectId, roadwayId = rwId, linearLocationId = llId+2)
      val combLink4 = dummyProjectLink(road, part, Track.Combined, Discontinuity.Discontinuous, 15, 20, Some(DateTime.now()), None, 12348, 0, 5, SideCode.TowardsDigitizing, LinkStatus.Transfer, projectId, RoadType.PublicRoad, combGeom4, rwNumber).copy(id = plId+3, projectId = projectId, roadwayId = rwId, linearLocationId = llId+3)

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
            RoadwayChangeSection(Some(road), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(part), endRoadPartNumber = Some(part), startAddressM = Some(5L), endAddressM = Some(20L), Some(RoadType.PublicRoad), Some(Discontinuity.Discontinuous), Some(8L)),
            Discontinuity.Discontinuous, RoadType.PublicRoad, reversed = false, 2, 8)
          , DateTime.now, Some(0L))
      )

      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(any[BoundingRectangle], any[Seq[(Int, Int)]])).thenReturn(Seq(lc1, lc2, lc3, lc4))
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(any[Set[Long]], any[Boolean])).thenReturn(Seq(rw1WithId))

      val mappedReservedRoadwayNumbers = projectLinkDAO.fetchProjectLinksChange(projectId)
      roadAddressService.handleRoadwayPointsUpdate(projectChanges, mappedReservedRoadwayNumbers)
      nodesAndJunctionsService.handleNodePointTemplates(projectChanges, combPLinks, mappedReservedRoadwayNumbers)
      nodesAndJunctionsService.handleJunctionPointTemplates(projectChanges, combPLinks, mappedReservedRoadwayNumbers)

      val roadwayPoints = roadwayPointDAO.fetchByRoadwayNumbers(combPLinks.map(_.roadwayNumber)).map(_.id)

      val junctionPointTemplates = junctionPointDAO.fetchByRoadwayPointIds(roadwayPoints)
      //2 new junctions points template, 1 before 1 after
      junctionPointTemplates.length should be(2)
    }
  }

  /*
  //TODO
  There can be the possibility that the created/modified Projectlink is not ramp or roundabout and is not EndOfRoad but this added link can still create one connection between the already existing EndOfRoad link,
     so we need to check that if there is some road EndOfRoad in same roadNumber ending in the head of this projectLink
  */



   /*
   //TODO
   There can be the possibility that the created/modified Projectlink is ramp or roundabout and is not EndOfRoad but this added link can still create one connection between the already existing EndOfRoad link,
      so we need to check that if there is some road EndOfRoad in same roadNumber ending in the head of this projectLink
   */

  //TODO add test to check handleJunctionPointTemplates creation between new different roadpart links that intersect between them in project

  test("Test getTemplatesByBoundingBox When no matching templates Then return nothing") {
    runWithRollback {
      val roadwayNumber = Sequences.nextRoadwayNumber
      roadwayDAO.create(Seq(testRoadway1.copy(roadwayNumber = roadwayNumber)))
      linearLocationDAO.create(Seq(testLinearLocation1.copy(roadwayNumber = roadwayNumber)))
      val roadwayPointId = roadwayPointDAO.create(testRoadwayPoint1.copy(roadwayNumber = roadwayNumber))
      nodePointDAO.create(Seq(testNodePoint1.copy(roadwayPointId = roadwayPointId)))
      val junctionId = junctionDAO.create(Seq(testJunction1)).head
      junctionPointDAO.create(Seq(testJunctionPoint1.copy(junctionId = junctionId, roadwayPointId = roadwayPointId)))

      val templates = nodesAndJunctionsService.getTemplatesByBoundingBox(BoundingRectangle(Point(96, 96), Point(98, 98)))
      templates._1.size should be(0)
      templates._2.size should be(0)
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

  // <editor-fold desc="Nodes">
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
      val projectId = Sequences.nextViitePrimaryKeySeqValue
      val plId1 = projectId + 1
      val plId2 = projectId + 2
      val roadLink1 = dummyProjectLink(1, 1, Track.Combined, Discontinuity.Continuous, 0, 100, Some(DateTime.now()), None, 12345, 0, 100.0, SideCode.TowardsDigitizing, LinkStatus.New, projectId, RoadType.PublicRoad, roadGeom1, roadwayNumber).copy(id = plId1)
      val roadLink2 = dummyProjectLink(1, 2, Track.Combined, Discontinuity.Continuous, 0, 150, Some(DateTime.now()), None, 12346, 0, 150.0, SideCode.TowardsDigitizing, LinkStatus.New, projectId, RoadType.PublicRoad, roadGeom2, roadwayNumber + 1).copy(id = plId2)

      val project = Project(projectId, ProjectState.Incomplete, "f", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
        "", Seq(), Seq(), None, None)


      val roadways = Seq(
        Roadway(NewIdValue, roadLink1.roadwayNumber, roadLink1.roadNumber, roadLink1.roadPartNumber, roadLink1.roadType, roadLink1.track, roadLink1.discontinuity, roadLink1.startAddrMValue, roadLink1.endAddrMValue, reversed = false, roadLink1.startDate.get, roadLink1.endDate, "user", None, 8L, TerminationCode.NoTermination, DateTime.now, None),
        Roadway(NewIdValue, roadLink2.roadwayNumber, roadLink2.roadNumber, roadLink2.roadPartNumber, roadLink2.roadType, roadLink2.track, roadLink2.discontinuity, roadLink2.startAddrMValue, roadLink2.endAddrMValue, reversed = false, roadLink2.startDate.get, roadLink2.endDate, "user", None, 8L, TerminationCode.NoTermination, DateTime.now, None))
      val linearLocations = Seq(
        LinearLocation(NewIdValue, 1, roadLink1.linkId, roadLink1.startAddrMValue, roadLink1.endAddrMValue, roadLink1.sideCode, 0L, calibrationPoints = (Some(roadLink1.startAddrMValue), Some(roadLink1.endAddrMValue)), roadGeom1, LinkGeomSource.NormalLinkInterface, roadLink1.roadwayNumber, Some(DateTime.now), None),
        LinearLocation(NewIdValue, 1, roadLink2.linkId, roadLink2.startAddrMValue, roadLink2.endAddrMValue, roadLink2.sideCode, 0L, calibrationPoints = (Some(roadLink2.startAddrMValue), Some(roadLink2.endAddrMValue)), roadGeom2, LinkGeomSource.NormalLinkInterface, roadLink2.roadwayNumber, Some(DateTime.now), None))

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
      val node1 = Node(NewIdValue, nodeNumber1, roadLink1.geometry.head, None, NodeType.EndOfRoad, DateTime.now(), None, DateTime.now(), None, Some("user"), Some(DateTime.now()))
      val node2 = Node(NewIdValue, nodeNumber2, roadLink2.geometry.head, None, NodeType.EndOfRoad, DateTime.now(), None, DateTime.now(), None, Some("user"), Some(DateTime.now()))
      val node3 = Node(NewIdValue, nodeNumber3, roadLink2.geometry.last, None, NodeType.EndOfRoad, DateTime.now(), None, DateTime.now(), None, Some("user"), Some(DateTime.now()))

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

      val nodePoints = nodePointDAO.fetchNodePointsByNodeNumber(Seq(nodeNumber1, nodeNumber2, nodeNumber3))
      nodePoints.length should be(4)
      nodePoints.exists(node => node.roadwayNumber == roadLink1.roadwayNumber && node.beforeAfter == BeforeAfter.After && node.addrM == roadLink1.startAddrMValue) should be(true)
      nodePoints.exists(node => node.roadwayNumber == roadLink1.roadwayNumber && node.beforeAfter == BeforeAfter.Before && node.addrM == roadLink1.endAddrMValue) should be(true)
      nodePoints.exists(node => node.roadwayNumber == roadLink2.roadwayNumber && node.beforeAfter == BeforeAfter.After && node.addrM == roadLink2.startAddrMValue) should be(true)
      nodePoints.exists(node => node.roadwayNumber == roadLink2.roadwayNumber && node.beforeAfter == BeforeAfter.Before && node.addrM == roadLink2.endAddrMValue) should be(true)

      val terminatedRoadLink = roadLink2.copy(endDate = Some(DateTime.now().withTimeAtStartOfDay()), status = LinkStatus.Terminated, projectId = 1)

      nodesAndJunctionsService.expireObsoleteNodesAndJunctions(Seq(terminatedRoadLink), Some(terminatedRoadLink.endDate.get))

      // Test expired node and node points
      val nodePointsAfterExpiration = nodePointDAO.fetchNodePointsByNodeNumber(Seq(nodeNumber1, nodeNumber2, nodeNumber3))
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
      val projectId = Sequences.nextViitePrimaryKeySeqValue
      val roadLink = dummyProjectLink(1, 1, Track.Combined, Discontinuity.Continuous, 0, 100, Some(DateTime.now()), None, 12345, 0, 100.0, SideCode.TowardsDigitizing, LinkStatus.New, projectId, RoadType.PublicRoad, roadGeom1, roadwayNumber)

      val roadways = Seq(
        Roadway(NewIdValue, roadLink.roadwayNumber, roadLink.roadNumber, roadLink.roadPartNumber, roadLink.roadType, roadLink.track, roadLink.discontinuity, roadLink.startAddrMValue, roadLink.endAddrMValue, reversed = false, roadLink.startDate.get, roadLink.endDate, "user", None, 8L, TerminationCode.NoTermination, DateTime.now, None))
      val linearLocations = Seq(
        LinearLocation(NewIdValue, 1, roadLink.linkId, roadLink.startAddrMValue, roadLink.endAddrMValue, roadLink.sideCode, 0L, calibrationPoints = (Some(roadLink.startAddrMValue), Some(roadLink.endAddrMValue)), roadGeom1, LinkGeomSource.NormalLinkInterface, roadLink.roadwayNumber, Some(DateTime.now), None))

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

      val node1 = Node(Sequences.nextNodeId, Sequences.nextNodeNumber, roadLink.geometry.head, None, NodeType.EndOfRoad, DateTime.now(), None, DateTime.now(), None, Some("user"), Some(DateTime.now()))
      val node2 = Node(Sequences.nextNodeId, Sequences.nextNodeNumber, roadLink.geometry.last, None, NodeType.EndOfRoad, DateTime.now(), None, DateTime.now(), None, Some("user"), Some(DateTime.now()))

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

      val nodePoints = nodePointDAO.fetchNodePointsByNodeNumber(Seq(node1.nodeNumber, node2.nodeNumber))
      nodePoints.length should be(2)
      nodePoints.exists(node => node.roadwayNumber == roadLink.roadwayNumber && node.beforeAfter == BeforeAfter.After && node.addrM == roadLink.startAddrMValue) should be(true)
      nodePoints.exists(node => node.roadwayNumber == roadLink.roadwayNumber && node.beforeAfter == BeforeAfter.Before && node.addrM == roadLink.endAddrMValue) should be(true)

      val roadGeom2 = Seq(Point(100.0, 0.0), Point(250.0, 0.0))
      val unchangedRoadLink = roadLink.copy(status = LinkStatus.UnChanged)
      val newRoadLink = dummyProjectLink(1, 1, Track.Combined, Discontinuity.Continuous, 100, 250, Some(DateTime.now()), None, 12346, 0, 150.0, SideCode.TowardsDigitizing, LinkStatus.New, 0, RoadType.PublicRoad, roadGeom2, roadwayNumber + 1)

      val newRoadways = Seq(
        Roadway(NewIdValue, roadLink.roadwayNumber, roadLink.roadNumber, roadLink.roadPartNumber, roadLink.roadType, roadLink.track, roadLink.discontinuity, roadLink.startAddrMValue, roadLink.endAddrMValue, reversed = false, roadLink.startDate.get, roadLink.endDate, "user", None, 8L, TerminationCode.NoTermination, DateTime.now, None),
        Roadway(NewIdValue, newRoadLink.roadwayNumber, newRoadLink.roadNumber, newRoadLink.roadPartNumber, newRoadLink.roadType, newRoadLink.track, newRoadLink.discontinuity, newRoadLink.startAddrMValue, newRoadLink.endAddrMValue, reversed = false, newRoadLink.startDate.get, newRoadLink.endDate, "user", None, 8L, TerminationCode.NoTermination, DateTime.now, None))
      val newLinearLocations = Seq(
        LinearLocation(NewIdValue, 1, roadLink.linkId, roadLink.startAddrMValue, roadLink.endAddrMValue, roadLink.sideCode, 0L, calibrationPoints = (Some(roadLink.startAddrMValue), Some(roadLink.endAddrMValue)), roadGeom1, LinkGeomSource.NormalLinkInterface, roadLink.roadwayNumber, Some(DateTime.now), None),
        LinearLocation(NewIdValue, 2, newRoadLink.linkId, newRoadLink.startAddrMValue, newRoadLink.endAddrMValue, newRoadLink.sideCode, 0L, calibrationPoints = (Some(newRoadLink.startAddrMValue), Some(newRoadLink.endAddrMValue)), roadGeom2, LinkGeomSource.NormalLinkInterface, newRoadLink.roadwayNumber, Some(DateTime.now), None))

      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(any[BoundingRectangle], any[Seq[(Int, Int)]])).thenReturn(newLinearLocations)
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(any[Set[Long]], any[Boolean])).thenReturn(newRoadways)

      nodesAndJunctionsService.expireObsoleteNodesAndJunctions(Seq(unchangedRoadLink, newRoadLink), Some(DateTime.now().minusDays(1)))

      // Test expired node and node points
      val nodePointsAfterExpiration = nodePointDAO.fetchNodePointsByNodeNumber(Seq(node1.nodeNumber, node2.nodeNumber))
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
      val projectId = Sequences.nextViitePrimaryKeySeqValue

      val roadLink = dummyProjectLink(1, 1, Track.Combined, Discontinuity.Continuous, 100, 250, Some(DateTime.now()), None, 12345, 100, 250.0, SideCode.TowardsDigitizing, LinkStatus.New, projectId, RoadType.PublicRoad, roadGeom1, roadwayNumber)

      val project = Project(projectId, ProjectState.Incomplete, "f", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
        "", Seq(), Seq(), None, None)


      val roadways = Seq(
        Roadway(NewIdValue, roadLink.roadwayNumber, roadLink.roadNumber, roadLink.roadPartNumber, roadLink.roadType, roadLink.track, roadLink.discontinuity, roadLink.startAddrMValue, roadLink.endAddrMValue, reversed = false, roadLink.startDate.get, roadLink.endDate, "user", None, 8L, TerminationCode.NoTermination, DateTime.now, None))
      val linearLocations = Seq(
        LinearLocation(NewIdValue, 1, roadLink.linkId, roadLink.startAddrMValue, roadLink.endAddrMValue, roadLink.sideCode, 0L, calibrationPoints = (Some(roadLink.startAddrMValue), Some(roadLink.endAddrMValue)), roadGeom1, LinkGeomSource.NormalLinkInterface, roadLink.roadwayNumber, Some(DateTime.now), None))

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

      val node1 = Node(Sequences.nextNodeId, Sequences.nextNodeNumber, roadLink.geometry.head, None, NodeType.EndOfRoad, DateTime.now(), None, DateTime.now(), None, Some("user"), Some(DateTime.now()))
      val node2 = Node(Sequences.nextNodeId, Sequences.nextNodeNumber, roadLink.geometry.last, None, NodeType.EndOfRoad, DateTime.now(), None, DateTime.now(), None, Some("user"), Some(DateTime.now()))

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

      val nodePoints = nodePointDAO.fetchNodePointsByNodeNumber(Seq(node1.nodeNumber, node2.nodeNumber))
      nodePoints.length should be(2)
      nodePoints.exists(node => node.roadwayNumber == roadLink.roadwayNumber && node.beforeAfter == BeforeAfter.After && node.addrM == roadLink.startAddrMValue) should be(true)
      nodePoints.exists(node => node.roadwayNumber == roadLink.roadwayNumber && node.beforeAfter == BeforeAfter.Before && node.addrM == roadLink.endAddrMValue) should be(true)

      val roadGeom2 = Seq(Point(0.0, 0.0), Point(100.0, 0.0))
      val newRoadLink = dummyProjectLink(1, 1, Track.Combined, Discontinuity.Continuous, 0, 100, Some(DateTime.now()), None, 12346, 0, 100.0, SideCode.TowardsDigitizing, LinkStatus.New, 0, RoadType.PublicRoad, roadGeom2, roadwayNumber + 1)

      val newRoadways = Seq(
        Roadway(NewIdValue, roadLink.roadwayNumber, roadLink.roadNumber, roadLink.roadPartNumber, roadLink.roadType, roadLink.track, roadLink.discontinuity, roadLink.startAddrMValue, roadLink.endAddrMValue, reversed = false, roadLink.startDate.get, roadLink.endDate, "user", None, 8L, TerminationCode.NoTermination, DateTime.now, None),
        Roadway(NewIdValue, newRoadLink.roadwayNumber, newRoadLink.roadNumber, newRoadLink.roadPartNumber, newRoadLink.roadType, newRoadLink.track, newRoadLink.discontinuity, newRoadLink.startAddrMValue, newRoadLink.endAddrMValue, reversed = false, newRoadLink.startDate.get, newRoadLink.endDate, "user", None, 8L, TerminationCode.NoTermination, DateTime.now, None))
      val newLinearLocations = Seq(
        LinearLocation(NewIdValue, 1, roadLink.linkId, roadLink.startAddrMValue, roadLink.endAddrMValue, roadLink.sideCode, 0L, calibrationPoints = (Some(roadLink.startAddrMValue), Some(roadLink.endAddrMValue)), roadGeom1, LinkGeomSource.NormalLinkInterface, roadLink.roadwayNumber, Some(DateTime.now), None),
        LinearLocation(NewIdValue, 2, newRoadLink.linkId, newRoadLink.startAddrMValue, newRoadLink.endAddrMValue, newRoadLink.sideCode, 0L, calibrationPoints = (Some(newRoadLink.startAddrMValue), Some(newRoadLink.endAddrMValue)), roadGeom2, LinkGeomSource.NormalLinkInterface, newRoadLink.roadwayNumber, Some(DateTime.now), None))

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
      val nodePointsAfterExpiration = nodePointDAO.fetchNodePointsByNodeNumber(Seq(node1.nodeNumber, node2.nodeNumber))
      nodePointsAfterExpiration.length should be(1)
      nodePointsAfterExpiration.exists(node => node.roadwayNumber == roadLink.roadwayNumber && node.beforeAfter == BeforeAfter.After && node.addrM == roadLink.startAddrMValue) should be(false)
      nodePointsAfterExpiration.exists(node => node.roadwayNumber == roadLink.roadwayNumber && node.beforeAfter == BeforeAfter.Before && node.addrM == roadLink.endAddrMValue) should be(true)
    }
  }

  test("Test addOrUpdateNode When creating new Then new is created successfully") {
    runWithRollback {
      val node = Node(NewIdValue, Sequences.nextNodeNumber, Point(0, 0), None, NodeType.EndOfRoad,
        DateTime.now().withTimeAtStartOfDay(), None, DateTime.now(), None, Some("user"), Some(DateTime.now()))
      nodesAndJunctionsService.addOrUpdateNode(node, node.createdBy.get) should be(None)
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

  test("Test addOrUpdateNode When update non-existing Then should return error") {
    runWithRollback {
      val node = Node(-1, Sequences.nextNodeNumber, Point(0, 0), None, NodeType.EndOfRoad,
        DateTime.now().withTimeAtStartOfDay(), None, DateTime.now(), None, Some("user"), Some(DateTime.now()))
      nodesAndJunctionsService.addOrUpdateNode(node, node.createdBy.get) should not be (None)
    }
  }

  test("Test addOrUpdateNode When update existing Then existing is expired and new created") {
    runWithRollback {
      val node = Node(Sequences.nextNodeId, Sequences.nextNodeNumber, Point(0, 0), None, NodeType.EndOfRoad,
        DateTime.now().withTimeAtStartOfDay(), None, DateTime.now(), None, Some("user"), Some(DateTime.now()))
      nodeDAO.create(Seq(node), node.createdBy.get)
      nodeDAO.fetchById(node.id) should not be None
      nodesAndJunctionsService.addOrUpdateNode(node.copy(coordinates = Point(1, 1)), node.createdBy.get) should be(None)
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
      val node = Node(Sequences.nextNodeId, Sequences.nextNodeNumber, Point(0, 0), None, NodeType.EndOfRoad,
        DateTime.now().withTimeAtStartOfDay(), None, DateTime.now(), None, Some("user"), Some(DateTime.now()))
      nodeDAO.create(Seq(node), node.createdBy.get)
      nodeDAO.fetchById(node.id) should not be None
      nodesAndJunctionsService.addOrUpdateNode(node.copy(coordinates = Point(1, 1), nodeType = NodeType.Bridge), node.createdBy.get) should be(None)
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
        DateTime.now().minusDays(1).withTimeAtStartOfDay(), None, DateTime.now(), None, Some("user"), Some(DateTime.now()))
      nodeDAO.create(Seq(node), node.createdBy.get)
      nodeDAO.fetchById(node.id) should not be None
      nodesAndJunctionsService.addOrUpdateNode(node.copy(startDate = DateTime.now().plusDays(1).withTimeAtStartOfDay(),
        nodeType = NodeType.Bridge), node.createdBy.get) should be(None)
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

  // </editor-fold>
  // <editor-fold desc="Junctions">
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
      val projectId = Sequences.nextViitePrimaryKeySeqValue
      val plId1 = projectId + 1
      val plId2 = projectId + 2
      val road1Link = dummyProjectLink(1, 1, Track.Combined, Discontinuity.Continuous, 0, 100, Some(DateTime.now()), None, 12345, 0, 100.0, SideCode.TowardsDigitizing, LinkStatus.New, projectId, RoadType.PublicRoad, roadGeom1, roadwayNumber1).copy(id = plId1)
      val road2Link = dummyProjectLink(2, 1, Track.Combined, Discontinuity.Continuous, 0, 150, Some(DateTime.now()), None, 12346, 0, 150.0, SideCode.TowardsDigitizing, LinkStatus.New, projectId, RoadType.PublicRoad, roadGeom2, roadwayNumber2).copy(id = plId2)

      val project = Project(projectId, ProjectState.Incomplete, "f", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
        "", Seq(), Seq(), None, None)

      val projectLinks = Seq(road1Link, road2Link)

      val roadways = Seq(
        Roadway(NewIdValue, road1Link.roadwayNumber, road1Link.roadNumber, road1Link.roadPartNumber, road1Link.roadType, road1Link.track, road1Link.discontinuity, road1Link.startAddrMValue, road1Link.endAddrMValue, reversed = false, road1Link.startDate.get, road1Link.endDate, "user", None, 8L, TerminationCode.NoTermination, DateTime.now, None),
        Roadway(NewIdValue, road2Link.roadwayNumber, road2Link.roadNumber, road2Link.roadPartNumber, road2Link.roadType, road2Link.track, road2Link.discontinuity, road2Link.startAddrMValue, road2Link.endAddrMValue, reversed = false, road2Link.startDate.get, road2Link.endDate, "user", None, 8L, TerminationCode.NoTermination, DateTime.now, None))
      val linearLocations = Seq(
        LinearLocation(NewIdValue, 1, road1Link.linkId, road1Link.startAddrMValue, road1Link.endAddrMValue, road1Link.sideCode, 0L, calibrationPoints = (Some(road1Link.startAddrMValue), Some(road1Link.endAddrMValue)), roadGeom1, LinkGeomSource.NormalLinkInterface, road1Link.roadwayNumber, Some(DateTime.now), None),
        LinearLocation(NewIdValue, 1, road2Link.linkId, road2Link.startAddrMValue, road2Link.endAddrMValue, road2Link.sideCode, 0L, calibrationPoints = (Some(road2Link.startAddrMValue), Some(road2Link.endAddrMValue)), roadGeom2, LinkGeomSource.NormalLinkInterface, road2Link.roadwayNumber, Some(DateTime.now), None))

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
      nodesAndJunctionsService.handleJunctionPointTemplates(projectChanges, projectLinks, mappedRoadwayNumbers)
      val junctionPointTemplates = junctionPointDAO.fetchByRoadwayPointIds(
        roadwayPointDAO.fetchByRoadwayNumbers(projectLinks.map(_.roadwayNumber)).map(_.id))
      junctionPointTemplates.length should be(2)

      val junctions = junctionDAO.fetchByIds(junctionPointTemplates.map(_.junctionId))
      junctions.length should be(1)

      val junctionsBeforeExpire = junctionDAO.fetchTemplatesByRoadwayNumbers(Seq(roadwayNumber1, roadwayNumber2))
      junctionsBeforeExpire.length should be(1)

      val terminatedJunctionsBeforeExpire = junctionDAO.fetchTerminatedByRoadwayNumbers(Seq(roadwayNumber1, roadwayNumber2))
      terminatedJunctionsBeforeExpire.length should be(0)

      val terminatedRoadLink = road2Link.copy(endDate = Some(DateTime.now()), status = LinkStatus.Terminated, projectId = 1)

      nodesAndJunctionsService.expireObsoleteNodesAndJunctions(Seq(terminatedRoadLink), Some(terminatedRoadLink.endDate.get))

      // Check that junctions for roadways were expired
      val junctionsAfterExpire = junctionDAO.fetchTemplatesByRoadwayNumbers(Seq(roadwayNumber1, roadwayNumber2))
      junctionsAfterExpire.length should be(0)

      // Check that terminated junction was created
      val terminatedJunctionsAfterExpire = junctionDAO.fetchTerminatedByRoadwayNumbers(Seq(roadwayNumber1, roadwayNumber2))
      terminatedJunctionsAfterExpire.length should be(1)

      // Check that original junction was expired
      val originalJunctions = junctionDAO.fetchByIds(junctionPointTemplates.map(_.junctionId))
      originalJunctions.length should be(0)

      // Check that junction points for the terminated junction exist
      val terminatedJunctionPoints = junctionPointDAO.fetchJunctionPointsByJunctionIds(terminatedJunctionsAfterExpire.map(_.id))
      terminatedJunctionPoints.length should be(2)

      // Check that junction points for the original junction were expired
      val originalJunctionPoints = junctionPointDAO.fetchJunctionPointsByJunctionIds(junctionPointTemplates.map(_.junctionId))
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
      val projectId = Sequences.nextViitePrimaryKeySeqValue
      val plId1 = projectId + 1
      val plId2 = projectId + 2
      val plId3 = projectId + 3
      val project = Project(projectId, ProjectState.Incomplete, "f", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
        "", Seq(), Seq(), None, None)
      val road1Link1 = dummyProjectLink(1, 1, Track.Combined, Discontinuity.Continuous, 0, 5, Some(DateTime.now()), None, 12345, 0, 5.0, SideCode.TowardsDigitizing, LinkStatus.New, projectId, RoadType.PublicRoad, roadGeom1Link1, roadwayNumber).copy(id = plId1)
      val road1Link2 = dummyProjectLink(1, 1, Track.Combined, Discontinuity.Continuous, 5, 10, Some(DateTime.now()), None, 12346, 5.0, 10.0, SideCode.TowardsDigitizing, LinkStatus.New, projectId, RoadType.PublicRoad, roadGeom1Link2, roadwayNumber).copy(id = plId2)
      val road2Link = dummyProjectLink(2, 1, Track.Combined, Discontinuity.Continuous, 0, 5, Some(DateTime.now()), None, 12347, 0, 5.0, SideCode.TowardsDigitizing, LinkStatus.New, projectId, RoadType.PublicRoad, roadGeom2, roadwayNumber + 1).copy(id = plId3)

      val roadways = Seq(
        Roadway(NewIdValue, road1Link1.roadwayNumber, road1Link1.roadNumber, road1Link1.roadPartNumber, road1Link1.roadType, road1Link1.track, road1Link1.discontinuity, road1Link1.startAddrMValue, road1Link2.endAddrMValue, reversed = false, road1Link1.startDate.get, road1Link1.endDate, "user", None, 8L, TerminationCode.NoTermination, DateTime.now, None),
        Roadway(NewIdValue, road2Link.roadwayNumber, road2Link.roadNumber, road2Link.roadPartNumber, road2Link.roadType, road2Link.track, road2Link.discontinuity, road2Link.startAddrMValue, road2Link.endAddrMValue, reversed = false, road2Link.startDate.get, road2Link.endDate, "user", None, 8L, TerminationCode.NoTermination, DateTime.now, None))
      val linearLocations = Seq(
        LinearLocation(NewIdValue, 1, road1Link1.linkId, road1Link1.startAddrMValue, road1Link1.endAddrMValue, road1Link1.sideCode, 0L, calibrationPoints = (Some(road1Link1.startAddrMValue), Some(road1Link1.endAddrMValue)), roadGeom1Link1, LinkGeomSource.NormalLinkInterface, road1Link1.roadwayNumber, Some(DateTime.now), None),
        LinearLocation(NewIdValue, 2, road1Link2.linkId, road1Link2.startAddrMValue, road1Link2.endAddrMValue, road1Link2.sideCode, 0L, calibrationPoints = (Some(road1Link2.startAddrMValue), Some(road1Link2.endAddrMValue)), roadGeom1Link2, LinkGeomSource.NormalLinkInterface, road1Link2.roadwayNumber, Some(DateTime.now), None),
        LinearLocation(NewIdValue, 1, road2Link.linkId, road2Link.startAddrMValue, road2Link.endAddrMValue, road2Link.sideCode, 0L, calibrationPoints = (Some(road2Link.startAddrMValue), Some(road2Link.endAddrMValue)), roadGeom2, LinkGeomSource.NormalLinkInterface, road2Link.roadwayNumber, Some(DateTime.now), None))

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
      nodesAndJunctionsService.handleJunctionPointTemplates(projectChanges, projectLinks, mappedRoadwayNumbers)
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
      val terminatedJunctions = junctionDAO.fetchTerminatedByRoadwayNumbers(Seq(terminatedRoadLink.roadwayNumber))
      terminatedJunctions.length should be(1)
      terminatedJunctions.head.endDate.isDefined should be(true)

      // Old junction points should be expired
      junctionPointDAO.fetchJunctionPointsByJunctionIds(junctionPointTemplates.map(_.junctionId)).length should be(0)

      // New junction points should be created for terminated junction
      junctionPointDAO.fetchJunctionPointsByJunctionIds(terminatedJunctions.map(_.id)).length should be(3)

    }
  }
  // </editor-fold>
}

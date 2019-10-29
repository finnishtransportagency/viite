package fi.liikennevirasto.viite

import fi.liikennevirasto.digiroad2.{DigiroadEventBus, Point}
import fi.liikennevirasto.digiroad2.asset.{BoundingRectangle, LinkGeomSource, SideCode}
import fi.liikennevirasto.digiroad2.dao.Sequences
import fi.liikennevirasto.digiroad2.oracle.OracleDatabase
import fi.liikennevirasto.digiroad2.service.RoadLinkService
import fi.liikennevirasto.digiroad2.util.Track
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

  val testJunction1 = Junction(NewIdValue, -1, None, DateTime.parse("2019-01-01"), None,
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

  test("Test nodesAndJunctionsService.getNodesByRoadAttributes When there are less than 50 nodes in the given road Then should return the list of those nodes") {
    runWithRollback {
      roadwayDAO.create(Seq(testRoadway1))
      val nodeId = Sequences.nextNodeId
      val nodeNumber = nodeDAO.create(Seq(testNode1.copy(id = nodeId))).head
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
        case _ => println("should not get here")
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
      val id = Sequences.nextViitePrimaryKeySeqValue

      val roadways = Seq(Roadway(NewIdValue, roadwayNumber, 999, 999, RoadType.PublicRoad, Track.LeftSide, Discontinuity.Continuous,
        0, 50, reversed = false, DateTime.parse("2000-01-01"), None, "test", Some("TEST ROAD 1"), 1, TerminationCode.NoTermination),
        Roadway(NewIdValue, roadwayNumber+1, 999, 999, RoadType.PublicRoad, Track.RightSide, Discontinuity.Continuous,
          0, 50, reversed = false, DateTime.parse("2000-01-01"), None, "test", Some("TEST ROAD 1"), 1, TerminationCode.NoTermination),
        Roadway(NewIdValue, roadwayNumber+2, 999, 999, RoadType.PublicRoad, Track.Combined, Discontinuity.Continuous,
          50, 100, reversed = false, DateTime.parse("2000-01-01"), None, "test", Some("TEST ROAD 1"), 1, TerminationCode.NoTermination),
        Roadway(NewIdValue, roadwayNumber+3, 999, 999, RoadType.PublicRoad, Track.Combined, Discontinuity.EndOfRoad,
          100, 150, reversed = false, DateTime.parse("2000-01-01"), None, "test", Some("TEST ROAD 1"), 1, TerminationCode.NoTermination)
      )

      val left = dummyProjectLink(999, 999, Track.LeftSide, Discontinuity.Continuous, 0 , 50, Some(DateTime.now()), None, 12345, 0, 50, SideCode.TowardsDigitizing, LinkStatus.Transfer, 0L, RoadType.PublicRoad, leftGeom, roadwayNumber).copy(id = id)
      val right = dummyProjectLink(999, 999, Track.RightSide, Discontinuity.Continuous, 0 , 50, Some(DateTime.now()), None, 12346, 0, 50, SideCode.TowardsDigitizing, LinkStatus.Transfer, 0L, RoadType.PublicRoad, rightGeom,roadwayNumber+1).copy(id = id+1)
      val combined1 = dummyProjectLink(999, 999, Track.Combined, Discontinuity.Continuous, 50 , 100, Some(DateTime.now()), None, 12347, 0, 50, SideCode.TowardsDigitizing, LinkStatus.Transfer, 0L, RoadType.FerryRoad, combGeom1, roadwayNumber+2).copy(id = id+2)
      val combined2 = dummyProjectLink(999, 999, Track.Combined, Discontinuity.EndOfRoad, 100 , 150, Some(DateTime.now()), None, 12348, 0, 50, SideCode.TowardsDigitizing, LinkStatus.Transfer, 0L, RoadType.PublicRoad, combGeom2, roadwayNumber+3).copy(id = id+3)

      val projectChanges = List(
        //left
        ProjectRoadwayChange(0, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(AddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, None, Some(PublicRoad), Some(Discontinuity.Continuous), Some(8L)),
            RoadwayChangeSection(Some(999), Some(Track.LeftSide.value.toLong), startRoadPartNumber = Some(999L), endRoadPartNumber = Some(999L), startAddressM = Some(0L), endAddressM = Some(50L), Some(RoadType.PublicRoad), Some(Discontinuity.Continuous), Some(8L)),
            Discontinuity.Continuous,RoadType.PublicRoad,  reversed = false, 1, 8)
          ,DateTime.now,Some(0L)),
        //right
        ProjectRoadwayChange(0, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(AddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, None, Some(PublicRoad), Some(Discontinuity.Continuous), Some(8L)),
            RoadwayChangeSection(Some(999), Some(Track.RightSide.value.toLong), startRoadPartNumber = Some(999L), endRoadPartNumber = Some(999L), startAddressM = Some(0L), endAddressM = Some(50L), Some(RoadType.PublicRoad), Some(Discontinuity.Continuous), Some(8L)),
            Discontinuity.Continuous,RoadType.PublicRoad,  reversed = false, 2, 8)
          ,DateTime.now,Some(0L)),
        //combined
        ProjectRoadwayChange(0, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(AddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, None, Some(PublicRoad), Some(Discontinuity.Continuous), Some(8L)),
            RoadwayChangeSection(Some(999), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(999L), endRoadPartNumber = Some(999L), startAddressM = Some(50L), endAddressM = Some(100L), Some(RoadType.FerryRoad), Some(Discontinuity.Continuous), Some(8L)),
            Discontinuity.Continuous,RoadType.FerryRoad,  reversed = false, 3, 8)
          ,DateTime.now,Some(0L)),
        ProjectRoadwayChange(0, Some("project name"), 8L, "test user", DateTime.now,
        RoadwayChangeInfo(AddressChangeType.New,
          RoadwayChangeSection(None, None, None, None, None, None, Some(PublicRoad), Some(Discontinuity.Continuous), Some(8L)),
          RoadwayChangeSection(Some(999), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(999L), endRoadPartNumber = Some(999L), startAddressM = Some(50L), endAddressM = Some(150L), Some(RoadType.PublicRoad), Some(Discontinuity.EndOfRoad), Some(8L)),
          Discontinuity.Continuous,RoadType.PublicRoad,  reversed = false, 3, 8)
        ,DateTime.now,Some(0L))
      )

      val pls = Seq(left, right, combined1, combined2)
      nodesAndJunctionsService.handleNodePointTemplates(projectChanges, pls, projectService.mapChangedRoadwayNumbers(pls, pls))

      val roadwayIds = roadwayDAO.create(roadways)

      val fetchedNodesPoints = pls.flatMap(pl => nodePointDAO.fetchTemplatesByRoadwayNumber(pl.roadwayNumber)).sortBy(_.id)
      val node1 = fetchedNodesPoints.find(n => n.roadwayNumber == left.roadwayNumber && n.beforeAfter == BeforeAfter.After)
      node1.isEmpty  should be (true)
      val node2 = fetchedNodesPoints.find(n => n.roadwayNumber == left.roadwayNumber && n.beforeAfter == BeforeAfter.Before)
      node2.isEmpty  should be (true)
      val node3 = fetchedNodesPoints.find(n => n.roadwayNumber == right.roadwayNumber && n.beforeAfter == BeforeAfter.After)
      node3.nonEmpty should be (true)
      val node4 = fetchedNodesPoints.find(n => n.roadwayNumber == right.roadwayNumber && n.beforeAfter == BeforeAfter.Before)
      node4.nonEmpty should be (true)
      val node5 = fetchedNodesPoints.find(n => n.roadwayNumber == combined1.roadwayNumber && n.beforeAfter == BeforeAfter.After)
      node5.nonEmpty should be (true)
      val node6 = fetchedNodesPoints.find(n => n.roadwayNumber == combined2.roadwayNumber && n.beforeAfter == BeforeAfter.Before)
      node6.nonEmpty should be (true)

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
            Discontinuity.Continuous,RoadType.PublicRoad,  reversed = true, 1, 8)
          ,DateTime.now,Some(0L)),
        //right
        ProjectRoadwayChange(0, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(AddressChangeType.Transfer,
            RoadwayChangeSection(Some(999), Some(Track.RightSide.value.toLong), startRoadPartNumber = Some(999L), endRoadPartNumber = Some(999L), startAddressM = Some(0L), endAddressM = Some(50L), Some(RoadType.PublicRoad), Some(Discontinuity.Continuous), Some(8L)),
            RoadwayChangeSection(Some(999), Some(Track.RightSide.value.toLong), startRoadPartNumber = Some(999L), endRoadPartNumber = Some(999L), startAddressM = Some(100), endAddressM = Some(150L), Some(RoadType.PublicRoad), Some(Discontinuity.EndOfRoad), Some(8L)),
            Discontinuity.Continuous,RoadType.PublicRoad,  reversed = true, 2, 8)
          ,DateTime.now,Some(0L)),
        ProjectRoadwayChange(0, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(AddressChangeType.Transfer,
            RoadwayChangeSection(Some(999), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(999L), endRoadPartNumber = Some(999L), startAddressM = Some(50L), endAddressM = Some(100L), Some(RoadType.FerryRoad), Some(Discontinuity.Continuous), Some(8L)),
            RoadwayChangeSection(Some(999), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(999L), endRoadPartNumber = Some(999L), startAddressM = Some(50L), endAddressM = Some(100L), Some(RoadType.FerryRoad), Some(Discontinuity.Continuous), Some(8L)),
            Discontinuity.Continuous,RoadType.PublicRoad,  reversed = true, 2, 8)
          ,DateTime.now,Some(0L)),
        //combined
        ProjectRoadwayChange(0, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(AddressChangeType.Transfer,
            RoadwayChangeSection(Some(999), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(999L), endRoadPartNumber = Some(999L), startAddressM = Some(100), endAddressM = Some(150L), Some(RoadType.PublicRoad), Some(Discontinuity.EndOfRoad), Some(8L)),
            RoadwayChangeSection(Some(999), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(999L), endRoadPartNumber = Some(999L), startAddressM = Some(0L), endAddressM = Some(50L), Some(RoadType.PublicRoad), Some(Discontinuity.Continuous), Some(8L)),
            Discontinuity.Continuous,RoadType.PublicRoad,  reversed = true, 3, 8)
          ,DateTime.now,Some(0L))
      )

      val reversedPls = Seq(reversedCombined1, reversedCombined2, reversedRight, reversedLeft)

      val reversedRoadways = Seq(Roadway(NewIdValue, left.roadwayNumber, 999, 999, RoadType.PublicRoad, Track.LeftSide, Discontinuity.EndOfRoad,
        100, 150, reversed = true, DateTime.parse("2000-01-01"), None, "test", Some("TEST ROAD 1"), 1, TerminationCode.NoTermination),
        Roadway(NewIdValue, right.roadwayNumber, 999, 999, RoadType.PublicRoad, Track.RightSide, Discontinuity.EndOfRoad,
          100, 150, reversed = true, DateTime.parse("2000-01-01"), None, "test", Some("TEST ROAD 1"), 1, TerminationCode.NoTermination),
        Roadway(NewIdValue, combined1.roadwayNumber, 999, 999, RoadType.PublicRoad, Track.Combined, Discontinuity.Continuous,
          50, 100, reversed = true, DateTime.parse("2000-01-01"), None, "test", Some("TEST ROAD 1"), 1, TerminationCode.NoTermination),
        Roadway(NewIdValue, combined2.roadwayNumber, 999, 999, RoadType.PublicRoad, Track.Combined, Discontinuity.Continuous,
          0, 50, reversed = true, DateTime.parse("2000-01-01"), None, "test", Some("TEST ROAD 1"), 1, TerminationCode.NoTermination)
      )

      roadwayDAO.expireById(roadwayIds.toSet)
      roadwayDAO.create(reversedRoadways)

      val mappedRoadwayNumbers = projectService.mapChangedRoadwayNumbers(reversedPls, reversedPls)
      roadAddressService.handleRoadwayPointsUpdate(reversedProjectChanges, mappedRoadwayNumbers)
      nodesAndJunctionsService.handleNodePointTemplates(reversedProjectChanges, reversedPls, mappedRoadwayNumbers)
      val fetchedReversedNodesPoints = reversedPls.flatMap(pl => nodePointDAO.fetchTemplatesByRoadwayNumber(pl.roadwayNumber)).sortBy(_.id)
      fetchedNodesPoints.size should be (fetchedReversedNodesPoints.size)
      fetchedNodesPoints.zip(fetchedReversedNodesPoints).foreach{ case (before, after) =>
          before.id should be (after.id)
          before.beforeAfter should be (BeforeAfter.switch(after.beforeAfter))
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
      val roadway = Roadway(NewIdValue, roadwayNumber, road999, part, RoadType.PublicRoad, Track.Combined, Discontinuity.Continuous,0L, 10L, reversed = false, DateTime.now,None, "user", None, 8L, TerminationCode.NoTermination, DateTime.now, None)
      val roadway2 = Roadway(NewIdValue, roadwayNumber+1, road1000, part, RoadType.PublicRoad, Track.Combined, Discontinuity.Continuous,0L, 10L, reversed = false, DateTime.now,None, "user", None, 8L, TerminationCode.NoTermination, DateTime.now, None)
      val linearLocation = LinearLocation(NewIdValue, 1, 12345, 0L, 10L, SideCode.TowardsDigitizing, 0L, calibrationPoints = (None, Some(10)), geom1, LinkGeomSource.NormalLinkInterface, roadwayNumber, None, None)
      val link1 = dummyProjectLink(road1000, part, Track.Combined, Discontinuity.EndOfRoad, 0 , 10, Some(DateTime.now()), None, 12346, 0, 10, SideCode.TowardsDigitizing, LinkStatus.Transfer, 0L, RoadType.PublicRoad, geom2, roadwayNumber+1)

      val projectChanges = List(
        //combined
        ProjectRoadwayChange(0, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(AddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, None, Some(PublicRoad), Some(Discontinuity.Continuous), Some(8L)),
            RoadwayChangeSection(Some(link1.roadNumber), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(link1.roadPartNumber), endRoadPartNumber = Some(link1.roadPartNumber), startAddressM = Some(link1.startAddrMValue), endAddressM = Some(link1.endAddrMValue), Some(RoadType.PublicRoad), Some(Discontinuity.Continuous), Some(8L)),
            Discontinuity.Continuous,RoadType.PublicRoad,  reversed = false, 1, 8)
          ,DateTime.now,Some(0L))
      )

      val pls = Seq(link1).map(_.copy(id = Sequences.nextViitePrimaryKeySeqValue))
      val reversedPls = pls.map(_.copy(sideCode = SideCode.switch(link1.sideCode), reversed = true))
      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(any[BoundingRectangle], any[Seq[(Int, Int)]])).thenReturn(Seq(linearLocation))
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(any[Set[Long]], any[Boolean])).thenReturn(Seq(roadway))

      val reversedProjectChanges = projectChanges.map(p => p.copy(changeInfo = p.changeInfo.copy(changeType = AddressChangeType.Transfer, source = p.changeInfo.target, reversed = true)))

      nodesAndJunctionsService.handleJunctionPointTemplates(projectChanges, pls, projectService.mapChangedRoadwayNumbers(pls, pls))

      roadwayDAO.create(Seq(roadway, roadway2))

      val roadJunctionPoints = junctionPointDAO.fetchJunctionPointsByRoadwayPoints(roadway.roadwayNumber, roadway.endAddrMValue, BeforeAfter.Before)
      val junction1 = junctionDAO.fetchByIds(Seq(roadJunctionPoints.head.junctionId))

      val linkJunctionPoints = junctionPointDAO.fetchJunctionPointsByRoadwayPoints(link1.roadwayNumber, link1.startAddrMValue, BeforeAfter.After)
      val junction2 = junctionDAO.fetchByIds(Seq(linkJunctionPoints.head.junctionId))

      junction1 should be(junction2)

      val roadwayPoint1 = roadwayPointDAO.fetch(roadway.roadwayNumber, roadway.endAddrMValue)
      val roadwayPoint2 = roadwayPointDAO.fetch(link1.roadwayNumber, link1.startAddrMValue)

      roadwayPoint1.head.addrMValue should be(roadway.endAddrMValue)
      roadwayPoint2.head.addrMValue should be(link1.startAddrMValue)

      roadJunctionPoints.isDefined should be (true)
      roadJunctionPoints.head.beforeAfter should be (BeforeAfter.Before)
      roadJunctionPoints.head.roadwayNumber should be (roadway.roadwayNumber)
      linkJunctionPoints.isDefined should be (true)
      linkJunctionPoints.head.beforeAfter should be (BeforeAfter.After)
      linkJunctionPoints.head.roadwayNumber should be (link1.roadwayNumber)

      val reversedRoadways = Seq(Roadway(NewIdValue, link1.roadwayNumber, link1.roadNumber, link1.roadPartNumber, RoadType.PublicRoad, Track.Combined, Discontinuity.Continuous,
        link1.startAddrMValue, link1.endAddrMValue, reversed = true, DateTime.parse("2000-01-01"), None, "test", Some("TEST ROAD 1"), 1, TerminationCode.NoTermination)
      )
      roadwayDAO.create(reversedRoadways)

      val mappedRoadwayNumbers = projectService.mapChangedRoadwayNumbers(reversedPls, reversedPls)
      roadAddressService.handleRoadwayPointsUpdate(reversedProjectChanges, mappedRoadwayNumbers)
      val roadwayPointsAfterUpdate = roadwayPointDAO.fetchByRoadwayNumber(link1.roadwayNumber)
      nodesAndJunctionsService.handleJunctionPointTemplates(reversedProjectChanges, reversedPls, mappedRoadwayNumbers)
      val reversedLinkJunctionPoints = junctionPointDAO.fetchJunctionPointsByRoadwayPoints(link1.roadwayNumber, roadwayPointsAfterUpdate.head.addrMValue, BeforeAfter.Before)
      linkJunctionPoints.head.id should be (reversedLinkJunctionPoints.head.id)
      reversedLinkJunctionPoints.head.beforeAfter should be (BeforeAfter.switch(linkJunctionPoints.head.beforeAfter))
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
      val roadway = Roadway(NewIdValue, roadwayNumber, road999, part, RoadType.PublicRoad, Track.Combined, Discontinuity.Continuous,0L, 10L, reversed = false, DateTime.now,None, "user", None, 8L, TerminationCode.NoTermination, DateTime.now, None)
      val roadway2 = Roadway(NewIdValue, roadwayNumber+1, road1000, part, RoadType.PublicRoad, Track.Combined, Discontinuity.Continuous,0L, 10L, reversed = false, DateTime.now,None, "user", None, 8L, TerminationCode.NoTermination, DateTime.now, None)
      val linearLocation = LinearLocation(NewIdValue, 1, 12345, 0L, 10L, SideCode.AgainstDigitizing, 0L, calibrationPoints = (None, Some(10)), geom1, LinkGeomSource.NormalLinkInterface, roadwayNumber, None, None)
      val link1 = dummyProjectLink(road1000, part, Track.Combined, Discontinuity.EndOfRoad, 0 , 10, Some(DateTime.now()), None, 12346, 0, 10, SideCode.TowardsDigitizing, LinkStatus.Transfer, 0L, RoadType.PublicRoad, geom2, roadwayNumber+1)

      val projectChanges = List(
        //combined
        ProjectRoadwayChange(0, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(AddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, None, Some(PublicRoad), Some(Discontinuity.Continuous), Some(8L)),
            RoadwayChangeSection(Some(link1.roadNumber), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(link1.roadPartNumber), endRoadPartNumber = Some(link1.roadPartNumber), startAddressM = Some(link1.startAddrMValue), endAddressM = Some(link1.endAddrMValue), Some(RoadType.PublicRoad), Some(Discontinuity.Continuous), Some(8L)),
            Discontinuity.Continuous,RoadType.PublicRoad,  reversed = false, 1, 8)
          ,DateTime.now,Some(0L))
      )

      val pls = Seq(link1).map(_.copy(id = Sequences.nextViitePrimaryKeySeqValue))
      val reversedPls = pls.map(_.copy(sideCode = SideCode.switch(link1.sideCode), reversed = true))

      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(any[BoundingRectangle], any[Seq[(Int, Int)]])).thenReturn(Seq(linearLocation))
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(any[Set[Long]], any[Boolean])).thenReturn(Seq(roadway))

      val reversedProjectChanges = projectChanges.map(p => p.copy(changeInfo = p.changeInfo.copy(changeType = AddressChangeType.Transfer, source = p.changeInfo.target, reversed = true)))

      nodesAndJunctionsService.handleJunctionPointTemplates(projectChanges, pls, projectService.mapChangedRoadwayNumbers(pls, pls))

      roadwayDAO.create(Seq(roadway, roadway2))

      val roadJunctionPoints = junctionPointDAO.fetchJunctionPointsByRoadwayPoints(roadway.roadwayNumber, roadway.startAddrMValue, BeforeAfter.After)
      val junction1 = junctionDAO.fetchByIds(Seq(roadJunctionPoints.head.junctionId))

      val linkJunctionPoints = junctionPointDAO.fetchJunctionPointsByRoadwayPoints(link1.roadwayNumber, link1.startAddrMValue, BeforeAfter.After)
      val junction2 = junctionDAO.fetchByIds(Seq(linkJunctionPoints.head.junctionId))

      junction1 should be(junction2)

      val roadwayPoint1 = roadwayPointDAO.fetch(roadway.roadwayNumber, roadway.startAddrMValue)
      val roadwayPoint2 = roadwayPointDAO.fetch(link1.roadwayNumber, link1.startAddrMValue)

      roadwayPoint1.head.addrMValue should be(roadway.startAddrMValue)
      roadwayPoint2.head.addrMValue should be(link1.startAddrMValue)

      roadJunctionPoints.isDefined should be (true)
      roadJunctionPoints.head.beforeAfter should be (BeforeAfter.After)
      roadJunctionPoints.head.roadwayNumber should be (roadway.roadwayNumber)
      linkJunctionPoints.isDefined should be (true)
      linkJunctionPoints.head.beforeAfter should be (BeforeAfter.After)
      linkJunctionPoints.head.roadwayNumber should be (link1.roadwayNumber)

      val reversedRoadways = Seq(Roadway(NewIdValue, link1.roadwayNumber, link1.roadNumber, link1.roadPartNumber, RoadType.PublicRoad, Track.Combined, Discontinuity.Continuous,
        link1.startAddrMValue, link1.endAddrMValue, reversed = true, DateTime.parse("2000-01-01"), None, "test", Some("TEST ROAD 1"), 1, TerminationCode.NoTermination)
      )
      roadwayDAO.create(reversedRoadways)

      val mappedRoadwayNumbers = projectService.mapChangedRoadwayNumbers(reversedPls, reversedPls)
      roadAddressService.handleRoadwayPointsUpdate(reversedProjectChanges, mappedRoadwayNumbers)
      val roadwayPointsAfterUpdate = roadwayPointDAO.fetchByRoadwayNumber(link1.roadwayNumber)
      nodesAndJunctionsService.handleJunctionPointTemplates(reversedProjectChanges, reversedPls, mappedRoadwayNumbers)
      val reversedLinkJunctionPoints = junctionPointDAO.fetchJunctionPointsByRoadwayPoints(link1.roadwayNumber, roadwayPointsAfterUpdate.head.addrMValue, BeforeAfter.Before)
      linkJunctionPoints.head.id should be (reversedLinkJunctionPoints.head.id)
      reversedLinkJunctionPoints.head.beforeAfter should be (BeforeAfter.switch(linkJunctionPoints.head.beforeAfter))
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
      val roadway = Roadway(NewIdValue, roadwayNumber, road999, part, RoadType.PublicRoad, Track.Combined, Discontinuity.Continuous,0L, 10L, reversed = false, DateTime.now,None, "user", None, 8L, TerminationCode.NoTermination, DateTime.now, None)
      val roadway2 = Roadway(NewIdValue, roadwayNumber+1, road1000, part, RoadType.PublicRoad, Track.Combined, Discontinuity.Continuous,0L, 10L, reversed = false, DateTime.now,None, "user", None, 8L, TerminationCode.NoTermination, DateTime.now, None)
      val linearLocation = LinearLocation(NewIdValue, 1, 12345, 0L, 10L, SideCode.TowardsDigitizing, 0L, calibrationPoints = (None, Some(10)), geom1, LinkGeomSource.NormalLinkInterface, roadwayNumber, None, None)
      val link1 = dummyProjectLink(road1000, part, Track.Combined, Discontinuity.EndOfRoad, 0 , 10, Some(DateTime.now()), None, 12346, 0, 10, SideCode.AgainstDigitizing, LinkStatus.Transfer, 0L, RoadType.PublicRoad, geom2, roadwayNumber+1)

      val projectChanges = List(
        //combined
        ProjectRoadwayChange(0, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(AddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, None, Some(PublicRoad), Some(Discontinuity.Continuous), Some(8L)),
            RoadwayChangeSection(Some(link1.roadNumber), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(link1.roadPartNumber), endRoadPartNumber = Some(link1.roadPartNumber), startAddressM = Some(link1.startAddrMValue), endAddressM = Some(link1.endAddrMValue), Some(RoadType.PublicRoad), Some(Discontinuity.Continuous), Some(8L)),
            Discontinuity.Continuous,RoadType.PublicRoad,  reversed = false, 1, 8)
          ,DateTime.now,Some(0L))
      )

      val pls = Seq(link1).map(_.copy(id = Sequences.nextViitePrimaryKeySeqValue))
      val reversedPls = pls.map(_.copy(sideCode = SideCode.switch(link1.sideCode), reversed = true))

      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(any[BoundingRectangle], any[Seq[(Int, Int)]])).thenReturn(Seq(linearLocation))
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(any[Set[Long]], any[Boolean])).thenReturn(Seq(roadway))

      val reversedProjectChanges = projectChanges.map(p => p.copy(changeInfo = p.changeInfo.copy(changeType = AddressChangeType.Transfer, source = p.changeInfo.target, reversed = true)))

      nodesAndJunctionsService.handleJunctionPointTemplates(projectChanges, pls, projectService.mapChangedRoadwayNumbers(pls, pls))

      roadwayDAO.create(Seq(roadway, roadway2))

      val roadJunctionPoints = junctionPointDAO.fetchJunctionPointsByRoadwayPoints(roadway.roadwayNumber, roadway.endAddrMValue, BeforeAfter.Before)
      val junction1 = junctionDAO.fetchByIds(Seq(roadJunctionPoints.head.junctionId))

      val linkJunctionPoints = junctionPointDAO.fetchJunctionPointsByRoadwayPoints(link1.roadwayNumber, link1.endAddrMValue, BeforeAfter.Before)
      val junction2 = junctionDAO.fetchByIds(Seq(linkJunctionPoints.head.junctionId))

      junction1 should be(junction2)

      val roadwayPoint1 = roadwayPointDAO.fetch(roadway.roadwayNumber, roadway.endAddrMValue)
      val roadwayPoint2 = roadwayPointDAO.fetch(link1.roadwayNumber, link1.endAddrMValue)

      roadwayPoint1.head.addrMValue should be(roadway.endAddrMValue)
      roadwayPoint2.head.addrMValue should be(link1.endAddrMValue)

      roadJunctionPoints.isDefined should be (true)
      roadJunctionPoints.head.beforeAfter should be (BeforeAfter.Before)
      roadJunctionPoints.head.roadwayNumber should be (roadway.roadwayNumber)
      linkJunctionPoints.isDefined should be (true)
      linkJunctionPoints.head.beforeAfter should be (BeforeAfter.Before)
      linkJunctionPoints.head.roadwayNumber should be (link1.roadwayNumber)

      val reversedRoadways = Seq(Roadway(NewIdValue, link1.roadwayNumber, link1.roadNumber, link1.roadPartNumber, RoadType.PublicRoad, Track.Combined, Discontinuity.Continuous,
        link1.startAddrMValue, link1.endAddrMValue, reversed = true, DateTime.parse("2000-01-01"), None, "test", Some("TEST ROAD 1"), 1, TerminationCode.NoTermination)
      )
      roadwayDAO.create(reversedRoadways)

      val mappedRoadwayNumbers = projectService.mapChangedRoadwayNumbers(reversedPls, reversedPls)
      roadAddressService.handleRoadwayPointsUpdate(reversedProjectChanges, mappedRoadwayNumbers)
      val roadwayPointsAfterUpdate = roadwayPointDAO.fetchByRoadwayNumber(link1.roadwayNumber)
      nodesAndJunctionsService.handleJunctionPointTemplates(reversedProjectChanges, reversedPls, mappedRoadwayNumbers)
      val reversedLinkJunctionPoints = junctionPointDAO.fetchJunctionPointsByRoadwayPoints(link1.roadwayNumber, roadwayPointsAfterUpdate.head.addrMValue, BeforeAfter.After)
      linkJunctionPoints.head.id should be (reversedLinkJunctionPoints.head.id)
      reversedLinkJunctionPoints.head.beforeAfter should be (BeforeAfter.switch(linkJunctionPoints.head.beforeAfter))
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
      val roadway = Roadway(NewIdValue, roadwayNumber, road999, part, RoadType.PublicRoad, Track.Combined, Discontinuity.Continuous,0L, 10L, reversed = false, DateTime.now,None, "user", None, 8L, TerminationCode.NoTermination, DateTime.now, None)
      val roadway2 = Roadway(NewIdValue, roadwayNumber+1, road1000, part, RoadType.PublicRoad, Track.Combined, Discontinuity.Continuous,0L, 10L, reversed = false, DateTime.now,None, "user", None, 8L, TerminationCode.NoTermination, DateTime.now, None)
      val linearLocation = LinearLocation(NewIdValue, 1, 12345, 0L, 10L, SideCode.AgainstDigitizing, 0L, calibrationPoints = (None, Some(10)), geom1, LinkGeomSource.NormalLinkInterface, roadwayNumber, None, None)
      val link1 = dummyProjectLink(road1000, part, Track.Combined, Discontinuity.EndOfRoad, 0 , 10, Some(DateTime.now()), None, 12346, 0, 10, SideCode.AgainstDigitizing, LinkStatus.Transfer, 0L, RoadType.PublicRoad, geom2, roadwayNumber+1)

      val projectChanges = List(
        //combined
        ProjectRoadwayChange(0, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(AddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, None, Some(PublicRoad), Some(Discontinuity.Continuous), Some(8L)),
            RoadwayChangeSection(Some(link1.roadNumber), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(link1.roadPartNumber), endRoadPartNumber = Some(link1.roadPartNumber), startAddressM = Some(link1.startAddrMValue), endAddressM = Some(link1.endAddrMValue), Some(RoadType.PublicRoad), Some(Discontinuity.Continuous), Some(8L)),
            Discontinuity.Continuous,RoadType.PublicRoad,  reversed = false, 1, 8)
          ,DateTime.now,Some(0L))
      )

      val pls = Seq(link1).map(_.copy(id = Sequences.nextViitePrimaryKeySeqValue))
      val reversedPls = pls.map(_.copy(sideCode = SideCode.switch(link1.sideCode), reversed = true))

      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(any[BoundingRectangle], any[Seq[(Int, Int)]])).thenReturn(Seq(linearLocation))
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(any[Set[Long]], any[Boolean])).thenReturn(Seq(roadway))

      val reversedProjectChanges = projectChanges.map(p => p.copy(changeInfo = p.changeInfo.copy(changeType = AddressChangeType.Transfer, source = p.changeInfo.target, reversed = true)))

      nodesAndJunctionsService.handleJunctionPointTemplates(projectChanges, pls, projectService.mapChangedRoadwayNumbers(pls, pls))

      roadwayDAO.create(Seq(roadway, roadway2))

      val roadJunctionPoints = junctionPointDAO.fetchJunctionPointsByRoadwayPoints(roadway.roadwayNumber, roadway.startAddrMValue, BeforeAfter.After)
      val junction1 = junctionDAO.fetchByIds(Seq(roadJunctionPoints.head.junctionId))

      val linkJunctionPoints = junctionPointDAO.fetchJunctionPointsByRoadwayPoints(link1.roadwayNumber, link1.endAddrMValue, BeforeAfter.Before)
      val junction2 = junctionDAO.fetchByIds(Seq(linkJunctionPoints.head.junctionId))

      junction1 should be(junction2)

      val roadwayPoint1 = roadwayPointDAO.fetch(roadway.roadwayNumber, roadway.startAddrMValue)
      val roadwayPoint2 = roadwayPointDAO.fetch(link1.roadwayNumber, link1.endAddrMValue)

      roadwayPoint1.head.addrMValue should be(roadway.startAddrMValue)
      roadwayPoint2.head.addrMValue should be(link1.endAddrMValue)

      roadJunctionPoints.isDefined should be (true)
      roadJunctionPoints.head.beforeAfter should be (BeforeAfter.After)
      roadJunctionPoints.head.roadwayNumber should be (roadway.roadwayNumber)
      linkJunctionPoints.isDefined should be (true)
      linkJunctionPoints.head.beforeAfter should be (BeforeAfter.Before)
      linkJunctionPoints.head.roadwayNumber should be (link1.roadwayNumber)

      val reversedRoadways = Seq(Roadway(NewIdValue, link1.roadwayNumber, link1.roadNumber, link1.roadPartNumber, RoadType.PublicRoad, Track.Combined, Discontinuity.Continuous,
        link1.startAddrMValue, link1.endAddrMValue, reversed = true, DateTime.parse("2000-01-01"), None, "test", Some("TEST ROAD 1"), 1, TerminationCode.NoTermination)
      )
      roadwayDAO.create(reversedRoadways)

      val mappedRoadwayNumbers = projectService.mapChangedRoadwayNumbers(reversedPls, reversedPls)
      roadAddressService.handleRoadwayPointsUpdate(reversedProjectChanges, mappedRoadwayNumbers)
      val roadwayPointsAfterUpdate = roadwayPointDAO.fetchByRoadwayNumber(link1.roadwayNumber)
      nodesAndJunctionsService.handleJunctionPointTemplates(reversedProjectChanges, reversedPls, mappedRoadwayNumbers)
      val reversedLinkJunctionPoints = junctionPointDAO.fetchJunctionPointsByRoadwayPoints(link1.roadwayNumber, roadwayPointsAfterUpdate.head.addrMValue, BeforeAfter.After)
      linkJunctionPoints.head.id should be (reversedLinkJunctionPoints.head.id)
      reversedLinkJunctionPoints.head.beforeAfter should be (BeforeAfter.switch(linkJunctionPoints.head.beforeAfter))
    }
  }

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
    * Node Point at the end of road part 2 should be expire.
    * Node at the end of road part 2 should expire conditionally:
    * * If there are no more node points nor junctions referenced by this nodeId, then expire the node.
    */
  test("Test expireObsoleteNodesAndJunctions case When road part is terminated Then also node points for terminated road should be expired") {
    runWithRollback {
      val roadGeom1 = Seq(Point(0.0, 0.0), Point(100.0, 0.0))
      val roadGeom2 = Seq(Point(100.0, 0.0), Point(250.0, 0.0))

      val roadwayNumber = Sequences.nextRoadwayNumber

      val roadLink1 = dummyProjectLink(1, 1, Track.Combined, Discontinuity.Continuous, 0, 100, Some(DateTime.now()), None, 12345, 0, 100.0, SideCode.TowardsDigitizing, LinkStatus.New, 0, RoadType.PublicRoad, roadGeom1, roadwayNumber)
      val roadLink2 = dummyProjectLink(1, 2, Track.Combined, Discontinuity.Continuous, 0, 150, Some(DateTime.now()), None, 12346, 0, 150.0, SideCode.TowardsDigitizing, LinkStatus.New, 0, RoadType.PublicRoad, roadGeom2, roadwayNumber + 1)

      val roadways = Seq(
        Roadway(NewIdValue, roadLink1.roadwayNumber, roadLink1.roadNumber, roadLink1.roadPartNumber, roadLink1.roadType, roadLink1.track, roadLink1.discontinuity, roadLink1.startAddrMValue, roadLink1.endAddrMValue, reversed = false, roadLink1.startDate.get, roadLink1.endDate, "user", None, 8L, TerminationCode.NoTermination, DateTime.now, None),
        Roadway(NewIdValue, roadLink2.roadwayNumber, roadLink2.roadNumber, roadLink2.roadPartNumber, roadLink2.roadType, roadLink2.track, roadLink2.discontinuity, roadLink2.startAddrMValue, roadLink2.endAddrMValue, reversed = false, roadLink2.startDate.get, roadLink2.endDate, "user", None, 8L, TerminationCode.NoTermination, DateTime.now, None))
      val linearLocations = Seq(
        LinearLocation(NewIdValue, 1, roadLink1.linkId, roadLink1.startAddrMValue, roadLink1.endAddrMValue, roadLink1.sideCode, 0L, calibrationPoints = (Some(roadLink1.startAddrMValue), Some(roadLink1.endAddrMValue)), roadGeom1, LinkGeomSource.NormalLinkInterface, roadLink1.roadwayNumber, Some(DateTime.now), None),
        LinearLocation(NewIdValue, 1, roadLink2.linkId, roadLink2.startAddrMValue, roadLink2.endAddrMValue, roadLink2.sideCode, 0L, calibrationPoints = (Some(roadLink2.startAddrMValue), Some(roadLink2.endAddrMValue)), roadGeom2, LinkGeomSource.NormalLinkInterface, roadLink2.roadwayNumber, Some(DateTime.now), None))

      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(any[BoundingRectangle], any[Seq[(Int, Int)]])).thenReturn(linearLocations)
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(any[Set[Long]], any[Boolean])).thenReturn(roadways)

      val projectLinks = Seq(roadLink1, roadLink2)
      roadwayDAO.create(roadways)

      val projectChanges = List(
        //combined
        ProjectRoadwayChange(0, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(AddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, None, Some(PublicRoad), Some(Discontinuity.Continuous), Some(8L)),
            RoadwayChangeSection(Some(roadLink1.roadNumber), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(roadLink1.roadPartNumber), endRoadPartNumber = Some(roadLink1.roadPartNumber), startAddressM = Some(0L), endAddressM = Some(100L), Some(RoadType.PublicRoad), Some(Discontinuity.Continuous), Some(8L)),
            Discontinuity.Continuous,RoadType.PublicRoad,  reversed = false, 1, 8)
          ,DateTime.now,Some(0L)),
        ProjectRoadwayChange(0, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(AddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, None, Some(PublicRoad), Some(Discontinuity.Continuous), Some(8L)),
            RoadwayChangeSection(Some(roadLink2.roadNumber), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(roadLink2.roadPartNumber), endRoadPartNumber = Some(roadLink2.roadPartNumber), startAddressM = Some(0L), endAddressM = Some(150L), Some(RoadType.PublicRoad), Some(Discontinuity.Continuous), Some(8L)),
            Discontinuity.Continuous,RoadType.PublicRoad,  reversed = false, 2, 8)
          ,DateTime.now,Some(0L))
      )

      // Creation of nodes and node points
      nodesAndJunctionsService.handleNodePointTemplates(projectChanges, projectLinks, projectService.mapChangedRoadwayNumbers(projectLinks, projectLinks))

      val node1 = Node(Sequences.nextNodeId, Sequences.nextNodeNumber, roadLink1.geometry.head, None, NodeType.EndOfRoad, DateTime.now(), None, DateTime.now(), None, Some("user"), Some(DateTime.now()))
      val node2 = Node(Sequences.nextNodeId, Sequences.nextNodeNumber, roadLink2.geometry.head, None, NodeType.EndOfRoad, DateTime.now(), None, DateTime.now(), None, Some("user"), Some(DateTime.now()))
      val node3 = Node(Sequences.nextNodeId, Sequences.nextNodeNumber, roadLink2.geometry.last, None, NodeType.EndOfRoad, DateTime.now(), None, DateTime.now(), None, Some("user"), Some(DateTime.now()))

      nodeDAO.create(Seq(node1, node2, node3))

      val nodePointTemplates = projectLinks.flatMap(pl => nodePointDAO.fetchTemplatesByRoadwayNumber(pl.roadwayNumber))
      nodePointTemplates.length should be(4)
      nodePointTemplates.foreach(
        np => {
          if (np.roadwayNumber == roadLink1.roadwayNumber) {
            if (np.addrM == roadLink1.startAddrMValue)
              sqlu"""UPDATE NODE_POINT SET NODE_NUMBER = ${node1.nodeNumber} WHERE ID = ${np.id}""".execute
            else if (np.addrM == roadLink1.endAddrMValue)
              sqlu"""UPDATE NODE_POINT SET NODE_NUMBER = ${node2.nodeNumber} WHERE ID = ${np.id}""".execute
          } else if (np.roadwayNumber == roadLink2.roadwayNumber) {
            if (np.addrM == roadLink2.startAddrMValue)
              sqlu"""UPDATE NODE_POINT SET NODE_NUMBER = ${node2.nodeNumber} WHERE ID = ${np.id}""".execute
            else if (np.addrM == roadLink2.endAddrMValue)
              sqlu"""UPDATE NODE_POINT SET NODE_NUMBER = ${node3.nodeNumber} WHERE ID = ${np.id}""".execute
          }
        }
      )

      val nodePoints = nodePointDAO.fetchNodePointsByNodeNumber(Seq(node1.nodeNumber, node2.nodeNumber, node3.nodeNumber))
      nodePoints.length should be(4)
      nodePoints.exists(node => node.roadwayNumber == roadLink1.roadwayNumber && node.beforeAfter == BeforeAfter.After && node.addrM == roadLink1.startAddrMValue) should be(true)
      nodePoints.exists(node => node.roadwayNumber == roadLink1.roadwayNumber && node.beforeAfter == BeforeAfter.Before && node.addrM == roadLink1.endAddrMValue) should be(true)
      nodePoints.exists(node => node.roadwayNumber == roadLink2.roadwayNumber && node.beforeAfter == BeforeAfter.After && node.addrM == roadLink2.startAddrMValue) should be(true)
      nodePoints.exists(node => node.roadwayNumber == roadLink2.roadwayNumber && node.beforeAfter == BeforeAfter.Before && node.addrM == roadLink2.endAddrMValue) should be(true)

      val terminatedRoadLink = roadLink2.copy(endDate = Some(DateTime.now()), status = LinkStatus.Terminated, projectId = 1)

      nodesAndJunctionsService.expireObsoleteNodesAndJunctions(Seq(terminatedRoadLink), Some(terminatedRoadLink.endDate.get.minusDays(1)))

      // Test expired node and node points
      val nodePointsAfterExpiration = nodePointDAO.fetchNodePointsByNodeNumber(Seq(node1.nodeNumber, node2.nodeNumber, node3.nodeNumber))
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

      val roadLink = dummyProjectLink(1, 1, Track.Combined, Discontinuity.Continuous, 0, 100, Some(DateTime.now()), None, 12345, 0, 100.0, SideCode.TowardsDigitizing, LinkStatus.New, 0, RoadType.PublicRoad, roadGeom1, roadwayNumber)

      val roadways = Seq(
        Roadway(NewIdValue, roadLink.roadwayNumber, roadLink.roadNumber, roadLink.roadPartNumber, roadLink.roadType, roadLink.track, roadLink.discontinuity, roadLink.startAddrMValue, roadLink.endAddrMValue, reversed = false, roadLink.startDate.get, roadLink.endDate, "user", None, 8L, TerminationCode.NoTermination, DateTime.now, None))
      val linearLocations = Seq(
        LinearLocation(NewIdValue, 1, roadLink.linkId, roadLink.startAddrMValue, roadLink.endAddrMValue, roadLink.sideCode, 0L, calibrationPoints = (Some(roadLink.startAddrMValue), Some(roadLink.endAddrMValue)), roadGeom1, LinkGeomSource.NormalLinkInterface, roadLink.roadwayNumber, Some(DateTime.now), None))

      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(any[BoundingRectangle], any[Seq[(Int, Int)]])).thenReturn(linearLocations)
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(any[Set[Long]], any[Boolean])).thenReturn(roadways)

      val projectLinks = Seq(roadLink)
      roadwayDAO.create(roadways)
      // Creation of nodes and node points
      val projectChanges = List(
        //combined
        ProjectRoadwayChange(0, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(AddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, None, Some(PublicRoad), Some(Discontinuity.Continuous), Some(8L)),
            RoadwayChangeSection(Some(roadLink.roadNumber), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(roadLink.roadPartNumber), endRoadPartNumber = Some(roadLink.roadPartNumber), startAddressM = Some(0L), endAddressM = Some(100L), Some(RoadType.PublicRoad), Some(Discontinuity.Continuous), Some(8L)),
            Discontinuity.Continuous,RoadType.PublicRoad,  reversed = false, 1, 8)
          ,DateTime.now,Some(0L))
      )

      nodesAndJunctionsService.handleNodePointTemplates(projectChanges, projectLinks, projectService.mapChangedRoadwayNumbers(projectLinks, projectLinks))

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

      val roadLink = dummyProjectLink(1, 1, Track.Combined, Discontinuity.Continuous, 100, 250, Some(DateTime.now()), None, 12345, 100, 250.0, SideCode.TowardsDigitizing, LinkStatus.New, 0, RoadType.PublicRoad, roadGeom1, roadwayNumber)

      val roadways = Seq(
        Roadway(NewIdValue, roadLink.roadwayNumber, roadLink.roadNumber, roadLink.roadPartNumber, roadLink.roadType, roadLink.track, roadLink.discontinuity, roadLink.startAddrMValue, roadLink.endAddrMValue, reversed = false, roadLink.startDate.get, roadLink.endDate, "user", None, 8L, TerminationCode.NoTermination, DateTime.now, None))
      val linearLocations = Seq(
        LinearLocation(NewIdValue, 1, roadLink.linkId, roadLink.startAddrMValue, roadLink.endAddrMValue, roadLink.sideCode, 0L, calibrationPoints = (Some(roadLink.startAddrMValue), Some(roadLink.endAddrMValue)), roadGeom1, LinkGeomSource.NormalLinkInterface, roadLink.roadwayNumber, Some(DateTime.now), None))

      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(any[BoundingRectangle], any[Seq[(Int, Int)]])).thenReturn(linearLocations)
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(any[Set[Long]], any[Boolean])).thenReturn(roadways)

      val projectLinks = Seq(roadLink)
      roadwayDAO.create(roadways)
      val projectChanges = List(
        //combined
        ProjectRoadwayChange(0, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(AddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, None, Some(PublicRoad), Some(Discontinuity.Continuous), Some(8L)),
            RoadwayChangeSection(Some(roadLink.roadNumber), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(roadLink.roadPartNumber), endRoadPartNumber = Some(roadLink.roadPartNumber), startAddressM = Some(roadLink.startAddrMValue), endAddressM = Some(roadLink.endAddrMValue), Some(RoadType.PublicRoad), Some(Discontinuity.Continuous), Some(8L)),
            Discontinuity.Continuous,RoadType.PublicRoad,  reversed = false, 1, 8)
          ,DateTime.now,Some(0L))
      )

      // Creation of nodes and node points
      nodesAndJunctionsService.handleNodePointTemplates(projectChanges, projectLinks, projectService.mapChangedRoadwayNumbers(projectLinks, projectLinks))

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
            Discontinuity.Continuous,RoadType.PublicRoad,  reversed = false, 1, 8)
          ,DateTime.now,Some(0L)),
        ProjectRoadwayChange(0, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(AddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, None, Some(PublicRoad), Some(Discontinuity.Continuous), Some(8L)),
            RoadwayChangeSection(Some(roadLink.roadNumber), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(roadLink.roadPartNumber), endRoadPartNumber = Some(roadLink.roadPartNumber), startAddressM = Some(roadLink.startAddrMValue), endAddressM = Some(roadLink.endAddrMValue), Some(RoadType.PublicRoad), Some(Discontinuity.Continuous), Some(8L)),
            Discontinuity.Continuous,RoadType.PublicRoad,  reversed = false, 1, 8)
          ,DateTime.now,Some(0L))
      )

          nodesAndJunctionsService.handleNodePointTemplates(projectChangesAfterChanges, Seq(roadLink, newRoadLink), projectService.mapChangedRoadwayNumbers(Seq(roadLink, newRoadLink.copy(roadwayNumber = NewIdValue)), (Seq(roadLink, newRoadLink))))
      nodesAndJunctionsService.expireObsoleteNodesAndJunctions(Seq(roadLink, newRoadLink), Some(DateTime.now().minusDays(1)))

      // Test expired node and node points
      val nodePointsAfterExpiration = nodePointDAO.fetchNodePointsByNodeNumber(Seq(node1.nodeNumber, node2.nodeNumber))
      nodePointsAfterExpiration.length should be(1)
      nodePointsAfterExpiration.exists(node => node.roadwayNumber == roadLink.roadwayNumber && node.beforeAfter == BeforeAfter.After && node.addrM == roadLink.startAddrMValue) should be(false)
      nodePointsAfterExpiration.exists(node => node.roadwayNumber == roadLink.roadwayNumber && node.beforeAfter == BeforeAfter.Before && node.addrM == roadLink.endAddrMValue) should be(true)
    }
  }

  test("Test addOrUpdateNode When creating new Then new is created") {
    runWithRollback {
      val node = Node(NewIdValue, Sequences.nextNodeNumber, Point(0, 0), None, NodeType.EndOfRoad,
        DateTime.now().withTimeAtStartOfDay(), None, DateTime.now(), None, Some("user"), Some(DateTime.now()))
      nodesAndJunctionsService.addOrUpdateNode(node, node.createdBy.get)
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
    }
  }

  test("Test addOrUpdateNode When update existing Then existing is expired and new created") {
    runWithRollback {
      // TODO
    }
  }

  test("Test addOrUpdateNode When update existing and change type Then existing is expired, history and new created") {
    runWithRollback {
      // TODO
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

      val roadwayNumber = Sequences.nextRoadwayNumber

      val road1Link = dummyProjectLink(1, 1, Track.Combined, Discontinuity.Continuous, 0, 100, Some(DateTime.now()), None, 12345, 0, 100.0, SideCode.TowardsDigitizing, LinkStatus.New, 0, RoadType.PublicRoad, roadGeom1, roadwayNumber)
      val road2Link = dummyProjectLink(2, 1, Track.Combined, Discontinuity.Continuous, 0, 150, Some(DateTime.now()), None, 12346, 0, 150.0, SideCode.TowardsDigitizing, LinkStatus.New, 0, RoadType.PublicRoad, roadGeom2, roadwayNumber + 1)

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

      val projectChanges = List(
        //combined
        ProjectRoadwayChange(0, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(AddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, None, Some(PublicRoad), Some(Discontinuity.Continuous), Some(8L)),
            RoadwayChangeSection(Some(road1Link.roadNumber), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(road1Link.roadPartNumber), endRoadPartNumber = Some(road1Link.roadPartNumber), startAddressM = Some(road1Link.startAddrMValue), endAddressM = Some(road1Link.endAddrMValue), Some(RoadType.PublicRoad), Some(Discontinuity.Continuous), Some(8L)),
            Discontinuity.Continuous,RoadType.PublicRoad,  reversed = false, 1, 8)
          ,DateTime.now,Some(0L)),
        ProjectRoadwayChange(0, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(AddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, None, Some(PublicRoad), Some(Discontinuity.Continuous), Some(8L)),
            RoadwayChangeSection(Some(road2Link.roadNumber), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(road2Link.roadPartNumber), endRoadPartNumber = Some(road2Link.roadPartNumber), startAddressM = Some(road2Link.startAddrMValue), endAddressM = Some(road2Link.endAddrMValue), Some(RoadType.PublicRoad), Some(Discontinuity.Continuous), Some(8L)),
            Discontinuity.Continuous,RoadType.PublicRoad,  reversed = false, 1, 8)
          ,DateTime.now,Some(0L))
      )

      // Creation of junction points template
      nodesAndJunctionsService.handleJunctionPointTemplates(projectChanges, projectLinks, projectService.mapChangedRoadwayNumbers(projectLinks.map(_.copy(roadwayNumber = NewIdValue)), projectLinks))
      val junctionPointTemplates = junctionPointDAO.fetchByRoadwayPointIds(
        roadwayPointDAO.fetchByRoadwayNumbers(projectLinks.map(_.roadwayNumber)).map(_.id))
      junctionPointTemplates.length should be(2)

      val junctions = junctionDAO.fetchByIds(junctionPointTemplates.map(_.junctionId))
      junctions.length should be(1)

      val terminatedRoadLink = road2Link.copy(endDate = Some(DateTime.now()), status = LinkStatus.Terminated, projectId = 1)

      nodesAndJunctionsService.expireObsoleteNodesAndJunctions(Seq(terminatedRoadLink), Some(terminatedRoadLink.endDate.get.minusDays(1)))

      // Test expired junction points
      val junctionPointTemplatesAfterTermination = junctionPointDAO.fetchByRoadwayPointIds(
        roadwayPointDAO.fetchByRoadwayNumbers(Seq(road1Link, terminatedRoadLink).map(_.roadwayNumber)).map(_.id))
      junctionPointTemplatesAfterTermination.length should be(0)

      // Test expired junction
      val expiredJunctions = junctionDAO.fetchByIds(junctionPointTemplates.map(_.junctionId))
      expiredJunctions.length should be(0)
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

      val road1Link1 = dummyProjectLink(1, 1, Track.Combined, Discontinuity.Continuous, 0, 5, Some(DateTime.now()), None, 12345, 0, 5.0, SideCode.TowardsDigitizing, LinkStatus.New, 0, RoadType.PublicRoad, roadGeom1Link1, roadwayNumber)
      val road1Link2 = dummyProjectLink(1, 1, Track.Combined, Discontinuity.Continuous, 5, 10, Some(DateTime.now()), None, 12346, 5.0, 10.0, SideCode.TowardsDigitizing, LinkStatus.New, 0, RoadType.PublicRoad, roadGeom1Link2, roadwayNumber)
      val road2Link = dummyProjectLink(2, 1, Track.Combined, Discontinuity.Continuous, 0, 5, Some(DateTime.now()), None, 12347, 0, 5.0, SideCode.TowardsDigitizing, LinkStatus.New, 0, RoadType.PublicRoad, roadGeom2, roadwayNumber + 1)

      val roadways = Seq(
        Roadway(NewIdValue, road1Link1.roadwayNumber, road1Link1.roadNumber, road1Link1.roadPartNumber, road1Link1.roadType, road1Link1.track, road1Link1.discontinuity, road1Link1.startAddrMValue, road1Link2.endAddrMValue, reversed = false, road1Link1.startDate.get, road1Link1.endDate, "user", None, 8L, TerminationCode.NoTermination, DateTime.now, None),
        Roadway(NewIdValue, road2Link.roadwayNumber, road2Link.roadNumber, road2Link.roadPartNumber, road2Link.roadType, road2Link.track, road2Link.discontinuity, road2Link.startAddrMValue, road2Link.endAddrMValue, reversed = false, road2Link.startDate.get, road2Link.endDate, "user", None, 8L, TerminationCode.NoTermination, DateTime.now, None))
      val linearLocations = Seq(
        LinearLocation(NewIdValue, 1, road1Link1.linkId, road1Link1.startAddrMValue, road1Link1.endAddrMValue, road1Link1.sideCode, 0L, calibrationPoints = (Some(road1Link1.startAddrMValue), Some(road1Link1.endAddrMValue)), roadGeom1Link1, LinkGeomSource.NormalLinkInterface, road1Link1.roadwayNumber, Some(DateTime.now), None),
        LinearLocation(NewIdValue, 2, road1Link2.linkId, road1Link2.startAddrMValue, road1Link2.endAddrMValue, road1Link2.sideCode, 0L, calibrationPoints = (Some(road1Link2.startAddrMValue), Some(road1Link2.endAddrMValue)), roadGeom1Link2, LinkGeomSource.NormalLinkInterface, road1Link2.roadwayNumber, Some(DateTime.now), None),
        LinearLocation(NewIdValue, 1, road2Link.linkId, road2Link.startAddrMValue, road2Link.endAddrMValue, road2Link.sideCode, 0L, calibrationPoints = (Some(road2Link.startAddrMValue), Some(road2Link.endAddrMValue)), roadGeom2, LinkGeomSource.NormalLinkInterface, road2Link.roadwayNumber, Some(DateTime.now), None))

      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(any[BoundingRectangle], any[Seq[(Int, Int)]])).thenReturn(linearLocations)
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(any[Set[Long]], any[Boolean])).thenReturn(roadways)

      val projectLinks = Seq(road1Link1, road1Link1, road2Link)

      roadwayDAO.create(roadways)

      val projectChanges = List(
        //combined
        ProjectRoadwayChange(0, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(AddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, None, Some(PublicRoad), Some(Discontinuity.Continuous), Some(8L)),
            RoadwayChangeSection(Some(road1Link1.roadNumber), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(road1Link1.roadPartNumber), endRoadPartNumber = Some(road1Link1.roadPartNumber), startAddressM = Some(road1Link1.startAddrMValue), endAddressM = Some(road1Link1.endAddrMValue), Some(RoadType.PublicRoad), Some(Discontinuity.Continuous), Some(8L)),
            Discontinuity.Continuous,RoadType.PublicRoad,  reversed = false, 1, 8)
          ,DateTime.now,Some(0L)),
        ProjectRoadwayChange(0, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(AddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, None, Some(PublicRoad), Some(Discontinuity.Continuous), Some(8L)),
            RoadwayChangeSection(Some(road2Link.roadNumber), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(road2Link.roadPartNumber), endRoadPartNumber = Some(road2Link.roadPartNumber), startAddressM = Some(road2Link.startAddrMValue), endAddressM = Some(road2Link.endAddrMValue), Some(RoadType.PublicRoad), Some(Discontinuity.Continuous), Some(8L)),
            Discontinuity.Continuous,RoadType.PublicRoad,  reversed = false, 1, 8)
          ,DateTime.now,Some(0L))
      )

      // Creation of junction points template
      nodesAndJunctionsService.handleJunctionPointTemplates(projectChanges, projectLinks, projectService.mapChangedRoadwayNumbers(projectLinks, projectLinks))
      val junctionPointTemplates = junctionPointDAO.fetchByRoadwayPointIds(
        roadwayPointDAO.fetchByRoadwayNumbers(projectLinks.map(_.roadwayNumber)).map(_.id))
      junctionPointTemplates.length should be(3)

      val junctions = junctionDAO.fetchByIds(junctionPointTemplates.map(_.junctionId))
      junctions.length should be(1)

      val terminatedRoadLink = road2Link.copy(endDate = Some(DateTime.now().minusDays(1)), status = LinkStatus.Terminated, projectId = 1)

      nodesAndJunctionsService.expireObsoleteNodesAndJunctions(Seq(terminatedRoadLink), Some(terminatedRoadLink.endDate.get.minusDays(1)))

      // Test expired junction points
      val junctionPointTemplatesAfterTermination = junctionPointDAO.fetchByRoadwayPointIds(
        roadwayPointDAO.fetchByRoadwayNumbers(Seq(road1Link1, road1Link2, terminatedRoadLink).map(_.roadwayNumber)).map(_.id))
      junctionPointTemplatesAfterTermination.length should be(0)

      // Test expired junction
      val expiredJunctions = junctionDAO.fetchByIds(junctionPointTemplates.map(_.junctionId))
      expiredJunctions.length should be(0)
    }
  }
  // </editor-fold>
}

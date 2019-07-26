package fi.liikennevirasto.viite

import fi.liikennevirasto.digiroad2.{DigiroadEventBus, Point}
import fi.liikennevirasto.digiroad2.asset.{BoundingRectangle, LinkGeomSource, SideCode}
import fi.liikennevirasto.digiroad2.dao.Sequences
import fi.liikennevirasto.digiroad2.oracle.OracleDatabase
import fi.liikennevirasto.digiroad2.service.RoadLinkService
import fi.liikennevirasto.digiroad2.util.Track
import fi.liikennevirasto.viite.RoadType.PublicRoad
import fi.liikennevirasto.viite.dao.BeforeAfter.Before
import fi.liikennevirasto.viite.dao.{BeforeAfter, _}
import fi.liikennevirasto.viite.process.RoadwayAddressMapper
import org.joda.time.DateTime
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.when
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{BeforeAndAfter, FunSuite, Matchers}
import slick.driver.JdbcDriver.backend.Database
import slick.driver.JdbcDriver.backend.Database.dynamicSession

import scala.util.{Left, Right}

class NodesAndJunctionsServiceSpec extends FunSuite with Matchers with BeforeAndAfter {

  val mockNodesAndJunctionsService = MockitoSugar.mock[NodesAndJunctionsService]
  private val roadNumber1 = 990
  private val roadwayNumber1 = 1000000000l
  private val roadPartNumber1 = 1
  val mockLinearLocationDAO = MockitoSugar.mock[LinearLocationDAO]
  val mockRoadwayDAO = MockitoSugar.mock[RoadwayDAO]
  val mockRoadLinkService: RoadLinkService = MockitoSugar.mock[RoadLinkService]
  val mockRoadwayAddressMapper: RoadwayAddressMapper = MockitoSugar.mock[RoadwayAddressMapper]
  val mockEventBus: DigiroadEventBus = MockitoSugar.mock[DigiroadEventBus]
  val roadwayDAO = new RoadwayDAO
  val roadwayPointDAO = new RoadwayPointDAO
  val nodeDAO = new NodeDAO
  val nodePointDAO = new NodePointDAO
  val junctionDAO = new JunctionDAO
  val junctionPointDAO = new JunctionPointDAO
  val roadAddressService: RoadAddressService = new RoadAddressService(mockRoadLinkService, new RoadwayDAO, new LinearLocationDAO, new RoadNetworkDAO, mockRoadwayAddressMapper, mockEventBus) {
    override def withDynSession[T](f: => T): T = f

    override def withDynTransaction[T](f: => T): T = f
  }
  val projectService: ProjectService = new ProjectService(roadAddressService, mockRoadLinkService, mockEventBus) {
    override def withDynSession[T](f: => T): T = f

    override def withDynTransaction[T](f: => T): T = f
  }

  val nodesAndJunctionsService = new NodesAndJunctionsService(mockRoadwayDAO, roadwayPointDAO, mockLinearLocationDAO, nodeDAO, nodePointDAO, junctionDAO, junctionPointDAO) {
    override def withDynSession[T](f: => T): T = f

    override def withDynTransaction[T](f: => T): T = f
  }
  val testRoadway1 = Roadway(NewIdValue, roadwayNumber1, roadNumber1, roadPartNumber1, RoadType.PublicRoad, Track.Combined, Discontinuity.Continuous,
    0, 100, reversed = false, DateTime.parse("2000-01-01"), None, "test", Some("TEST ROAD 1"), 1, TerminationCode.NoTermination)

  val testNode1 = Node(NewIdValue, NewIdValue, Point(100, 100), Some("Test node"), NodeType.NormalIntersection,
    DateTime.parse("2019-01-01"), None, DateTime.parse("2019-01-01"), None, Some("Test"), None)

  val testRoadwayPoint1 = RoadwayPoint(NewIdValue, roadwayNumber1, 0, "Test", None, None, None)

  val testNodePoint1 = NodePoint(NewIdValue, BeforeAfter.Before, -1, None,
    DateTime.parse("2019-01-01"), None, DateTime.parse("2019-01-01"), None, Some("Test"), None, 0, 0)

  def withDynTransaction[T](f: => T): T = OracleDatabase.withDynTransaction(f)

  def runWithRollback(f: => Unit): Unit = {
    Database.forDataSource(OracleDatabase.ds).withDynTransaction {
      f
      dynamicSession.rollback()
    }
  }

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
      nodeDAO.create(Seq(testNode1.copy(id = nodeId)))
      val roadwayPointId = Sequences.nextRoadwayPointId
      roadwayPointDAO.create(testRoadwayPoint1.copy(id = roadwayPointId))
      nodePointDAO.create(Seq(testNodePoint1.copy(nodeId = Some(nodeId), roadwayPointId = roadwayPointId)))

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
        nodeDAO.create(Seq(testNode1.copy(id = nodeId)))
        val roadwayPointId = Sequences.nextRoadwayPointId
        roadwayPointDAO.create(testRoadwayPoint1.copy(id = roadwayPointId, addrMValue = index))
        nodePointDAO.create(Seq(testNodePoint1.copy(nodeId = Some(nodeId), roadwayPointId = roadwayPointId)))
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
        nodeDAO.create(Seq(testNode1.copy(id = nodeId)))
        val roadwayPointId = Sequences.nextRoadwayPointId
        roadwayPointDAO.create(testRoadwayPoint1.copy(id = roadwayPointId, addrMValue = index))
        nodePointDAO.create(Seq(testNodePoint1.copy(nodeId = Some(nodeId), roadwayPointId = roadwayPointId)))
      }

      val nodesAndRoadAttributes = nodesAndJunctionsService.getNodesByRoadAttributes(roadNumber1, None, None)
      nodesAndRoadAttributes.isLeft should be(true)
      nodesAndRoadAttributes match {
        case Left(errorMessage) => errorMessage should be(ReturnedTooManyNodesErrorMessage)
        case _ => fail()
      }
    }
  }

  test("Test nodesAndJunctionsService.handleNodePointTemplates When creating projectlinks Then node points template should be handled/created properly and" +
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

      val left = dummyProjectLink(999, 999, Track.LeftSide, Discontinuity.Continuous, 0 , 50, Some(DateTime.now()), None, 12345, 0, 50, SideCode.TowardsDigitizing, LinkStatus.Transfer, 0L, RoadType.PublicRoad, leftGeom, roadwayNumber)
      val right = dummyProjectLink(999, 999, Track.RightSide, Discontinuity.Continuous, 0 , 50, Some(DateTime.now()), None, 12346, 0, 50, SideCode.TowardsDigitizing, LinkStatus.Transfer, 0L, RoadType.PublicRoad, rightGeom,roadwayNumber+1)
      val combined1 = dummyProjectLink(999, 999, Track.Combined, Discontinuity.Continuous, 50 , 100, Some(DateTime.now()), None, 12347, 0, 50, SideCode.TowardsDigitizing, LinkStatus.Transfer, 0L, RoadType.FerryRoad, combGeom1, roadwayNumber+2)
      val combined2 = dummyProjectLink(999, 999, Track.Combined, Discontinuity.EndOfRoad, 100 , 150, Some(DateTime.now()), None, 12348, 0, 50, SideCode.TowardsDigitizing, LinkStatus.Transfer, 0L, RoadType.PublicRoad, combGeom2, roadwayNumber+3)

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
            RoadwayChangeSection(Some(999), Some(Track.RightSide.value.toLong), startRoadPartNumber = Some(999L), endRoadPartNumber = Some(999L), startAddressM = Some(0L), endAddressM = Some(50L), Some(RoadType.FerryRoad), Some(Discontinuity.Continuous), Some(8L)),
            Discontinuity.Continuous,RoadType.PublicRoad,  reversed = false, 2, 8)
          ,DateTime.now,Some(0L)),
        //combined
        ProjectRoadwayChange(0, Some("project name"), 8L, "test user", DateTime.now,
          RoadwayChangeInfo(AddressChangeType.New,
            RoadwayChangeSection(None, None, None, None, None, None, Some(PublicRoad), Some(Discontinuity.Continuous), Some(8L)),
            RoadwayChangeSection(Some(999), Some(Track.Combined.value.toLong), startRoadPartNumber = Some(999L), endRoadPartNumber = Some(999L), startAddressM = Some(50L), endAddressM = Some(150L), Some(RoadType.PublicRoad), Some(Discontinuity.EndOfRoad), Some(8L)),
            Discontinuity.Continuous,RoadType.PublicRoad,  reversed = false, 3, 8)
          ,DateTime.now,Some(0L))
      )

      val pls = Seq(left, right, combined1, combined2)
      nodesAndJunctionsService.handleNodePointTemplates(projectChanges, pls, projectService.mapChangedRoadwayNumbers(pls, pls))

      val fetchedNodesPoints = pls.flatMap(pl => nodePointDAO.fetchNodePointTemplate(pl.roadwayNumber))
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

      val reversedLeft = left.copy(reversed = true, startAddrMValue = 100, endAddrMValue = 150)
      val reversedRight = right.copy(reversed = true, startAddrMValue = 100, endAddrMValue = 150)
      val reversedCombined1 = combined1.copy(reversed = true, startAddrMValue = 50, endAddrMValue = 100)
      val reversedCombined2 = combined2.copy(reversed = true, startAddrMValue = 50, endAddrMValue = 100)

      val reversedProjectChanges = projectChanges.map(p => p.copy(changeInfo = p.changeInfo.copy(source = p.changeInfo.target, reversed = true)))
      val reversedPls = Seq(reversedCombined1, reversedCombined2, reversedRight, reversedLeft)
      nodesAndJunctionsService.handleNodePointTemplates(reversedProjectChanges, reversedPls, projectService.mapChangedRoadwayNumbers(reversedPls, reversedPls))
      val fetchedReversedNodesPoints = reversedPls.flatMap(pl => nodePointDAO.fetchNodePointTemplate(pl.roadwayNumber))
      val revnode1 = fetchedReversedNodesPoints.find(n => n.roadwayNumber == left.roadwayNumber && n.beforeAfter == BeforeAfter.After)
      revnode1.isEmpty  should be (true)
      val revnode2 = fetchedReversedNodesPoints.find(n => n.roadwayNumber == left.roadwayNumber && n.beforeAfter == BeforeAfter.Before)
      revnode2.isEmpty  should be (true)
      val revnode3 = fetchedReversedNodesPoints.find(n => n.roadwayNumber == right.roadwayNumber && n.beforeAfter == BeforeAfter.After)
      revnode3.nonEmpty should be (true)
      val revnode4 = fetchedReversedNodesPoints.find(n => n.roadwayNumber == right.roadwayNumber && n.beforeAfter == BeforeAfter.Before)
      revnode4.nonEmpty should be (true)
      val revnode5 = fetchedReversedNodesPoints.find(n => n.roadwayNumber == combined1.roadwayNumber && n.beforeAfter == BeforeAfter.After)
      revnode5.nonEmpty should be (true)
      val revnode6 = fetchedReversedNodesPoints.find(n => n.roadwayNumber == combined2.roadwayNumber && n.beforeAfter == BeforeAfter.Before)
      revnode6.nonEmpty should be (true)

      node3.get.beforeAfter should be (BeforeAfter.switch(revnode3.get.beforeAfter))
      node4.get.beforeAfter should be (BeforeAfter.switch(revnode4.get.beforeAfter))
      node5.get.beforeAfter should be (BeforeAfter.switch(revnode5.get.beforeAfter))
      node6.get.beforeAfter should be (BeforeAfter.switch(revnode6.get.beforeAfter))
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

      val pls = Seq(link1)

      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(any[BoundingRectangle], any[Seq[(Int, Int)]])).thenReturn(Seq(linearLocation))
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(any[Set[Long]], any[Boolean])).thenReturn(Seq(roadway))

      val reversedLink = link1.copy(reversed = true)

      val reversedProjectChanges = projectChanges.map(p => p.copy(changeInfo = p.changeInfo.copy(source = p.changeInfo.target, reversed = true)))
      val reversedPls = Seq(reversedLink)

      nodesAndJunctionsService.handleJunctionPointTemplates(projectChanges, pls, projectService.mapChangedRoadwayNumbers(pls, pls))

      val roadJunctionPoints = junctionPointDAO.fetchJunctionPointsByRoadwayPoints(roadway.roadwayNumber, roadway.endAddrMValue, BeforeAfter.Before)
      val junction1 = junctionDAO.fetchByIds(Seq(roadJunctionPoints.head.junctionId))

      val linkJunctionPoints = junctionPointDAO.fetchJunctionPointsByRoadwayPoints(link1.roadwayNumber, link1.startAddrMValue, BeforeAfter.After)
      val junction2 = junctionDAO.fetchByIds(Seq(linkJunctionPoints.head.junctionId))

      junction1 should be (junction2)

      val roadwayPoint1 = roadwayPointDAO.fetch(roadway.roadwayNumber, roadway.endAddrMValue)
      val roadwayPoint2 = roadwayPointDAO.fetch(link1.roadwayNumber, link1.startAddrMValue)

      roadwayPoint1.head.addrMValue should be (roadway.endAddrMValue)
      roadwayPoint2.head.addrMValue should be (link1.startAddrMValue)

      roadJunctionPoints.isDefined should be (true)
      roadJunctionPoints.head.beforeAfter should be (BeforeAfter.Before)
      roadJunctionPoints.head.roadwayNumber should be (roadway.roadwayNumber)
      linkJunctionPoints.isDefined should be (true)
      linkJunctionPoints.head.beforeAfter should be (BeforeAfter.After)
      linkJunctionPoints.head.roadwayNumber should be (link1.roadwayNumber)

      nodesAndJunctionsService.handleJunctionPointTemplates(reversedProjectChanges, reversedPls, projectService.mapChangedRoadwayNumbers(reversedPls, reversedPls))
      val reversedLinkJunctionPoints = junctionPointDAO.fetchJunctionPointsByRoadwayPoints(link1.roadwayNumber, link1.startAddrMValue, BeforeAfter.Before)
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

      val pls = Seq(link1)

      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(any[BoundingRectangle], any[Seq[(Int, Int)]])).thenReturn(Seq(linearLocation))
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(any[Set[Long]], any[Boolean])).thenReturn(Seq(roadway))

      val reversedLink = link1.copy(reversed = true)

      val reversedProjectChanges = projectChanges.map(p => p.copy(changeInfo = p.changeInfo.copy(source = p.changeInfo.target, reversed = true)))
      val reversedPls = Seq(reversedLink)

      nodesAndJunctionsService.handleJunctionPointTemplates(projectChanges, pls, projectService.mapChangedRoadwayNumbers(pls, pls))

      val roadJunctionPoints = junctionPointDAO.fetchJunctionPointsByRoadwayPoints(roadway.roadwayNumber, roadway.startAddrMValue, BeforeAfter.After)
      val junction1 = junctionDAO.fetchByIds(Seq(roadJunctionPoints.head.junctionId))

      val linkJunctionPoints = junctionPointDAO.fetchJunctionPointsByRoadwayPoints(link1.roadwayNumber, link1.startAddrMValue, BeforeAfter.After)
      val junction2 = junctionDAO.fetchByIds(Seq(linkJunctionPoints.head.junctionId))

      junction1 should be (junction2)

      val roadwayPoint1 = roadwayPointDAO.fetch(roadway.roadwayNumber, roadway.startAddrMValue)
      val roadwayPoint2 = roadwayPointDAO.fetch(link1.roadwayNumber, link1.startAddrMValue)

      roadwayPoint1.head.addrMValue should be (roadway.startAddrMValue)
      roadwayPoint2.head.addrMValue should be (link1.startAddrMValue)

      roadJunctionPoints.isDefined should be (true)
      roadJunctionPoints.head.beforeAfter should be (BeforeAfter.After)
      roadJunctionPoints.head.roadwayNumber should be (roadway.roadwayNumber)
      linkJunctionPoints.isDefined should be (true)
      linkJunctionPoints.head.beforeAfter should be (BeforeAfter.After)
      linkJunctionPoints.head.roadwayNumber should be (link1.roadwayNumber)

      nodesAndJunctionsService.handleJunctionPointTemplates(reversedProjectChanges, reversedPls, projectService.mapChangedRoadwayNumbers(reversedPls, reversedPls))
      val reversedLinkJunctionPoints = junctionPointDAO.fetchJunctionPointsByRoadwayPoints(link1.roadwayNumber, link1.startAddrMValue, BeforeAfter.Before)
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

      val pls = Seq(link1)

      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(any[BoundingRectangle], any[Seq[(Int, Int)]])).thenReturn(Seq(linearLocation))
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(any[Set[Long]], any[Boolean])).thenReturn(Seq(roadway))

      val reversedLink = link1.copy(reversed = true)

      val reversedProjectChanges = projectChanges.map(p => p.copy(changeInfo = p.changeInfo.copy(source = p.changeInfo.target, reversed = true)))
      val reversedPls = Seq(reversedLink)

      nodesAndJunctionsService.handleJunctionPointTemplates(projectChanges, pls, projectService.mapChangedRoadwayNumbers(pls, pls))

      val roadJunctionPoints = junctionPointDAO.fetchJunctionPointsByRoadwayPoints(roadway.roadwayNumber, roadway.endAddrMValue, BeforeAfter.Before)
      val junction1 = junctionDAO.fetchByIds(Seq(roadJunctionPoints.head.junctionId))

      val linkJunctionPoints = junctionPointDAO.fetchJunctionPointsByRoadwayPoints(link1.roadwayNumber, link1.endAddrMValue, BeforeAfter.Before)
      val junction2 = junctionDAO.fetchByIds(Seq(linkJunctionPoints.head.junctionId))

      junction1 should be (junction2)

      val roadwayPoint1 = roadwayPointDAO.fetch(roadway.roadwayNumber, roadway.endAddrMValue)
      val roadwayPoint2 = roadwayPointDAO.fetch(link1.roadwayNumber, link1.endAddrMValue)

      roadwayPoint1.head.addrMValue should be (roadway.endAddrMValue)
      roadwayPoint2.head.addrMValue should be (link1.endAddrMValue)

      roadJunctionPoints.isDefined should be (true)
      roadJunctionPoints.head.beforeAfter should be (BeforeAfter.Before)
      roadJunctionPoints.head.roadwayNumber should be (roadway.roadwayNumber)
      linkJunctionPoints.isDefined should be (true)
      linkJunctionPoints.head.beforeAfter should be (BeforeAfter.Before)
      linkJunctionPoints.head.roadwayNumber should be (link1.roadwayNumber)

      nodesAndJunctionsService.handleJunctionPointTemplates(reversedProjectChanges, reversedPls, projectService.mapChangedRoadwayNumbers(reversedPls, reversedPls))
      val reversedLinkJunctionPoints = junctionPointDAO.fetchJunctionPointsByRoadwayPoints(link1.roadwayNumber, link1.endAddrMValue, BeforeAfter.After)
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

      val pls = Seq(link1)

      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(any[BoundingRectangle], any[Seq[(Int, Int)]])).thenReturn(Seq(linearLocation))
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(any[Set[Long]], any[Boolean])).thenReturn(Seq(roadway))

      val reversedLink = link1.copy(reversed = true)

      val reversedProjectChanges = projectChanges.map(p => p.copy(changeInfo = p.changeInfo.copy(source = p.changeInfo.target, reversed = true)))
      val reversedPls = Seq(reversedLink)


      nodesAndJunctionsService.handleJunctionPointTemplates(projectChanges, pls, projectService.mapChangedRoadwayNumbers(pls, pls))

      val roadJunctionPoints = junctionPointDAO.fetchJunctionPointsByRoadwayPoints(roadway.roadwayNumber, roadway.startAddrMValue, BeforeAfter.After)
      val junction1 = junctionDAO.fetchByIds(Seq(roadJunctionPoints.head.junctionId))

      val linkJunctionPoints = junctionPointDAO.fetchJunctionPointsByRoadwayPoints(link1.roadwayNumber, link1.endAddrMValue, BeforeAfter.Before)
      val junction2 = junctionDAO.fetchByIds(Seq(linkJunctionPoints.head.junctionId))

      junction1 should be (junction2)

      val roadwayPoint1 = roadwayPointDAO.fetch(roadway.roadwayNumber, roadway.startAddrMValue)
      val roadwayPoint2 = roadwayPointDAO.fetch(link1.roadwayNumber, link1.endAddrMValue)

      roadwayPoint1.head.addrMValue should be (roadway.startAddrMValue)
      roadwayPoint2.head.addrMValue should be (link1.endAddrMValue)

      roadJunctionPoints.isDefined should be (true)
      roadJunctionPoints.head.beforeAfter should be (BeforeAfter.After)
      roadJunctionPoints.head.roadwayNumber should be (roadway.roadwayNumber)
      linkJunctionPoints.isDefined should be (true)
      linkJunctionPoints.head.beforeAfter should be (BeforeAfter.Before)
      linkJunctionPoints.head.roadwayNumber should be (link1.roadwayNumber)

      nodesAndJunctionsService.handleJunctionPointTemplates(reversedProjectChanges, reversedPls, projectService.mapChangedRoadwayNumbers(reversedPls, reversedPls))
      val reversedLinkJunctionPoints = junctionPointDAO.fetchJunctionPointsByRoadwayPoints(link1.roadwayNumber, link1.endAddrMValue, BeforeAfter.After)
      linkJunctionPoints.head.id should be (reversedLinkJunctionPoints.head.id)
      reversedLinkJunctionPoints.head.beforeAfter should be (BeforeAfter.switch(linkJunctionPoints.head.beforeAfter))
    }
  }

  //TODO add test to check handleJunctionPointTemplates creation between new different roadpart links that intersect between them in project

}

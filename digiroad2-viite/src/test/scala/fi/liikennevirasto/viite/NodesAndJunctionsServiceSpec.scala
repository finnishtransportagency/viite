package fi.liikennevirasto.viite

import fi.liikennevirasto.digiroad2.Point
import fi.liikennevirasto.digiroad2.asset.{BoundingRectangle, LinkGeomSource, SideCode}
import fi.liikennevirasto.digiroad2.dao.Sequences
import fi.liikennevirasto.digiroad2.oracle.OracleDatabase
import fi.liikennevirasto.digiroad2.util.Track
import fi.liikennevirasto.viite.dao._
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
  val roadwayDAO = new RoadwayDAO
  val roadwayPointDAO = new RoadwayPointDAO
  val nodeDAO = new NodeDAO
  val nodePointDAO = new NodePointDAO
  val junctionDAO = new JunctionDAO
  val junctionPointDAO = new JunctionPointDAO
  val linearLocationDAO = new LinearLocationDAO
  val roadwayChangesDAO = new RoadwayChangesDAO

  val nodesAndJunctionsService = new NodesAndJunctionsService(mockRoadwayDAO, roadwayPointDAO, mockLinearLocationDAO, nodeDAO, nodePointDAO, junctionDAO, junctionPointDAO) {
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

  val testNodePoint1 = NodePoint(NewIdValue, BeforeAfter.Before, -1, None,
    DateTime.parse("2019-01-01"), None, DateTime.parse("2019-01-01"), None, Some("Test"), None, 0, 0)

  val testJunction1 = Junction(NewIdValue, -1, None, DateTime.parse("2019-01-01"), None,
    DateTime.parse("2019-01-01"), None, None, None)

  val testJunctionPoint1 = JunctionPoint(NewIdValue, BeforeAfter.Before, -1, -1,
    DateTime.parse("2019-01-01"), None, DateTime.parse("2019-01-01"), None, None, None, -1, 10)

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

  test("Test nodesAndJunctionsService.handleNodePointTemplates When creating projectlinks Then node points template should be handled/created properly") {
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

      val left = dummyProjectLink(999, 999, Track.LeftSide, Discontinuity.Continuous, 0, 50, Some(DateTime.now()), None, 12345, 0, 50, SideCode.TowardsDigitizing, LinkStatus.Transfer, 0L, RoadType.PublicRoad, leftGeom, roadwayNumber)
      val right = dummyProjectLink(999, 999, Track.RightSide, Discontinuity.Continuous, 0, 50, Some(DateTime.now()), None, 12346, 0, 50, SideCode.TowardsDigitizing, LinkStatus.Transfer, 0L, RoadType.PublicRoad, rightGeom, roadwayNumber + 1)
      val combined1 = dummyProjectLink(999, 999, Track.Combined, Discontinuity.Continuous, 50, 100, Some(DateTime.now()), None, 12347, 0, 50, SideCode.TowardsDigitizing, LinkStatus.Transfer, 0L, RoadType.FerryRoad, combGeom1, roadwayNumber + 2)
      val combined2 = dummyProjectLink(999, 999, Track.Combined, Discontinuity.EndOfRoad, 100, 150, Some(DateTime.now()), None, 12348, 0, 50, SideCode.TowardsDigitizing, LinkStatus.Transfer, 0L, RoadType.PublicRoad, combGeom2, roadwayNumber + 3)

      val pls = Seq(left, right, combined1, combined2)
      nodesAndJunctionsService.handleNodePointTemplates(pls)

      val fetchedNodesPoints = pls.flatMap(pl => nodePointDAO.fetchNodePointTemplate(pl.roadwayNumber))
      fetchedNodesPoints.exists(n => n.roadwayNumber == left.roadwayNumber && n.beforeAfter == BeforeAfter.After) should be(false)
      fetchedNodesPoints.exists(n => n.roadwayNumber == left.roadwayNumber && n.beforeAfter == BeforeAfter.Before) should be(false)
      fetchedNodesPoints.exists(n => n.roadwayNumber == right.roadwayNumber && n.beforeAfter == BeforeAfter.After) should be(true)
      fetchedNodesPoints.exists(n => n.roadwayNumber == right.roadwayNumber && n.beforeAfter == BeforeAfter.Before) should be(true)
      fetchedNodesPoints.exists(n => n.roadwayNumber == combined1.roadwayNumber && n.beforeAfter == BeforeAfter.After) should be(true)
      fetchedNodesPoints.exists(n => n.roadwayNumber == combined2.roadwayNumber && n.beforeAfter == BeforeAfter.Before) should be(true)
    }
  }

  test("Test nodesAndJunctionsService.handleJunctionPointTemplates roadsToHead case When creating projectlinks Then junction template and junctions points should be handled/created properly") {
    runWithRollback {
      /*
     |--R-->0|0--L-->
       */
      val geom1 = Seq(Point(0.0, 0.0), Point(10.0, 0.0))
      val geom2 = Seq(Point(10.0, 0.0), Point(20.0, 0.0))
      val roadwayNumber = Sequences.nextRoadwayNumber
      val roadway = Roadway(NewIdValue, roadwayNumber, 1, 1, RoadType.PublicRoad, Track.Combined, Discontinuity.Continuous, 0L, 10L, reversed = false, DateTime.now, None, "user", None, 8L, TerminationCode.NoTermination, DateTime.now, None)
      val linearLocation = LinearLocation(NewIdValue, 1, 12345, 0L, 10L, SideCode.TowardsDigitizing, 0L, calibrationPoints = (None, Some(10)), geom1, LinkGeomSource.NormalLinkInterface, roadwayNumber, None, None)
      val link1 = dummyProjectLink(1, 2, Track.Combined, Discontinuity.EndOfRoad, 0, 10, Some(DateTime.now()), None, 12346, 0, 10, SideCode.TowardsDigitizing, LinkStatus.Transfer, 0L, RoadType.PublicRoad, geom2, roadwayNumber + 1)

      val pls = Seq(link1)

      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(any[BoundingRectangle], any[Seq[(Int, Int)]])).thenReturn(Seq(linearLocation))
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(any[Set[Long]], any[Boolean])).thenReturn(Seq(roadway))

      nodesAndJunctionsService.handleJunctionPointTemplates(pls)

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
      roadJunctionPoints.head.beforeAfter should be(dao.BeforeAfter.Before)
      roadJunctionPoints.head.roadwayNumber should be(roadway.roadwayNumber)
      linkJunctionPoints.isDefined should be(true)
      linkJunctionPoints.head.beforeAfter should be(dao.BeforeAfter.After)
      linkJunctionPoints.head.roadwayNumber should be(link1.roadwayNumber)
    }
  }

  test("Test nodesAndJunctionsService.handleJunctionPointTemplates roadsFromHead case When creating projectLinks Then junction template and junctions points should be handled/created properly") {
    runWithRollback {
      /*
     <--R--0|0--L-->
       */
      val geom1 = Seq(Point(0.0, 0.0), Point(10.0, 0.0))
      val geom2 = Seq(Point(10.0, 0.0), Point(20.0, 0.0))
      val roadwayNumber = Sequences.nextRoadwayNumber
      val roadway = Roadway(NewIdValue, roadwayNumber, 1, 1, RoadType.PublicRoad, Track.Combined, Discontinuity.Continuous, 0L, 10L, reversed = false, DateTime.now, None, "user", None, 8L, TerminationCode.NoTermination, DateTime.now, None)
      val linearLocation = LinearLocation(NewIdValue, 1, 12345, 0L, 10L, SideCode.AgainstDigitizing, 0L, calibrationPoints = (None, Some(10)), geom1, LinkGeomSource.NormalLinkInterface, roadwayNumber, None, None)
      val link1 = dummyProjectLink(1, 2, Track.Combined, Discontinuity.EndOfRoad, 0, 10, Some(DateTime.now()), None, 12346, 0, 10, SideCode.TowardsDigitizing, LinkStatus.Transfer, 0L, RoadType.PublicRoad, geom2, roadwayNumber + 1)

      val pls = Seq(link1)

      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(any[BoundingRectangle], any[Seq[(Int, Int)]])).thenReturn(Seq(linearLocation))
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(any[Set[Long]], any[Boolean])).thenReturn(Seq(roadway))

      nodesAndJunctionsService.handleJunctionPointTemplates(pls)

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
      roadJunctionPoints.head.beforeAfter should be(dao.BeforeAfter.After)
      roadJunctionPoints.head.roadwayNumber should be(roadway.roadwayNumber)
      linkJunctionPoints.isDefined should be(true)
      linkJunctionPoints.head.beforeAfter should be(dao.BeforeAfter.After)
      linkJunctionPoints.head.roadwayNumber should be(link1.roadwayNumber)
    }
  }

  test("Test nodesAndJunctionsService.handleJunctionPointTemplates roadsToTail case When creating projectLinks Then junction template and junctions points should be handled/created properly") {
    runWithRollback {
      /*
      |--R--0>|<0--L--|
       */
      val geom1 = Seq(Point(0.0, 0.0), Point(10.0, 0.0))
      val geom2 = Seq(Point(10.0, 0.0), Point(20.0, 0.0))
      val roadwayNumber = Sequences.nextRoadwayNumber
      val roadway = Roadway(NewIdValue, roadwayNumber, 1, 1, RoadType.PublicRoad, Track.Combined, Discontinuity.Continuous, 0L, 10L, reversed = false, DateTime.now, None, "user", None, 8L, TerminationCode.NoTermination, DateTime.now, None)
      val linearLocation = LinearLocation(NewIdValue, 1, 12345, 0L, 10L, SideCode.TowardsDigitizing, 0L, calibrationPoints = (None, Some(10)), geom1, LinkGeomSource.NormalLinkInterface, roadwayNumber, None, None)
      val link1 = dummyProjectLink(1, 2, Track.Combined, Discontinuity.EndOfRoad, 0, 10, Some(DateTime.now()), None, 12346, 0, 10, SideCode.AgainstDigitizing, LinkStatus.Transfer, 0L, RoadType.PublicRoad, geom2, roadwayNumber + 1)

      val pls = Seq(link1)

      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(any[BoundingRectangle], any[Seq[(Int, Int)]])).thenReturn(Seq(linearLocation))
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(any[Set[Long]], any[Boolean])).thenReturn(Seq(roadway))

      nodesAndJunctionsService.handleJunctionPointTemplates(pls)

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
      roadJunctionPoints.head.beforeAfter should be(dao.BeforeAfter.Before)
      roadJunctionPoints.head.roadwayNumber should be(roadway.roadwayNumber)
      linkJunctionPoints.isDefined should be(true)
      linkJunctionPoints.head.beforeAfter should be(dao.BeforeAfter.Before)
      linkJunctionPoints.head.roadwayNumber should be(link1.roadwayNumber)
    }
  }

  test("Test nodesAndJunctionsService.handleJunctionPointTemplates roadsFromTail case When creating projectLinks Then junction template and junctions points should be handled/created properly") {
    runWithRollback {
      /*
      <--R--0|<0--L--|
       */
      val geom1 = Seq(Point(0.0, 0.0), Point(10.0, 0.0))
      val geom2 = Seq(Point(10.0, 0.0), Point(20.0, 0.0))
      val roadwayNumber = Sequences.nextRoadwayNumber
      val roadway = Roadway(NewIdValue, roadwayNumber, 1, 1, RoadType.PublicRoad, Track.Combined, Discontinuity.Continuous, 0L, 10L, reversed = false, DateTime.now, None, "user", None, 8L, TerminationCode.NoTermination, DateTime.now, None)
      val linearLocation = LinearLocation(NewIdValue, 1, 12345, 0L, 10L, SideCode.AgainstDigitizing, 0L, calibrationPoints = (None, Some(10)), geom1, LinkGeomSource.NormalLinkInterface, roadwayNumber, None, None)
      val link1 = dummyProjectLink(1, 2, Track.Combined, Discontinuity.EndOfRoad, 0, 10, Some(DateTime.now()), None, 12346, 0, 10, SideCode.AgainstDigitizing, LinkStatus.Transfer, 0L, RoadType.PublicRoad, geom2, roadwayNumber + 1)

      val pls = Seq(link1)

      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(any[BoundingRectangle], any[Seq[(Int, Int)]])).thenReturn(Seq(linearLocation))
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(any[Set[Long]], any[Boolean])).thenReturn(Seq(roadway))

      nodesAndJunctionsService.handleJunctionPointTemplates(pls)

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
      roadJunctionPoints.head.beforeAfter should be(dao.BeforeAfter.After)
      roadJunctionPoints.head.roadwayNumber should be(roadway.roadwayNumber)
      linkJunctionPoints.isDefined should be(true)
      linkJunctionPoints.head.beforeAfter should be(dao.BeforeAfter.Before)
      linkJunctionPoints.head.roadwayNumber should be(link1.roadwayNumber)
    }
  }

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
      templates._2.head._1.nodeId should be(None)
      templates._2.head._2.head.junctionId should be(junctionId)
    }
  }

  test("Test getNodesWithJunctionByBoundingBox When matching templates Then return them") {
    runWithRollback {
      val roadwayNumber = Sequences.nextRoadwayNumber
      roadwayDAO.create(Seq(testRoadway1.copy(roadwayNumber = roadwayNumber)))
      linearLocationDAO.create(Seq(testLinearLocation1.copy(roadwayNumber = roadwayNumber)))
      val roadwayPointId = roadwayPointDAO.create(testRoadwayPoint1.copy(roadwayNumber = roadwayNumber))
      nodePointDAO.create(Seq(testNodePoint1.copy(roadwayPointId = roadwayPointId)))
      val junctionId = junctionDAO.create(Seq(testJunction1)).head
      junctionPointDAO.create(Seq(testJunctionPoint1.copy(junctionId = junctionId, roadwayPointId = roadwayPointId)))

      val nodes = nodesAndJunctionsService.getNodesWithJunctionByBoundingBox(BoundingRectangle(Point(98, 98), Point(102, 102)))
      nodes(None)._1.size should be(1)
      nodes(None)._2.size should be(1)
      nodes(None)._2.head._2.size should be(1)
      nodes(None)._1.head.roadwayNumber should be(roadwayNumber)
      nodes(None)._2.head._1.id should be(junctionId)
      nodes(None)._2.head._1.nodeId should be(None)
      nodes(None)._2.head._2.head.junctionId should be(junctionId)
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

      // Creation of nodes and node points
      nodesAndJunctionsService.handleNodePointTemplates(projectLinks)

      val node1 = Node(Sequences.nextNodeId, Sequences.nextNodeNumber, roadLink1.geometry.head, None, NodeType.EndOfRoad, DateTime.now(), None, DateTime.now(), None, Some("user"), Some(DateTime.now()))
      val node2 = Node(Sequences.nextNodeId, Sequences.nextNodeNumber, roadLink2.geometry.head, None, NodeType.EndOfRoad, DateTime.now(), None, DateTime.now(), None, Some("user"), Some(DateTime.now()))
      val node3 = Node(Sequences.nextNodeId, Sequences.nextNodeNumber, roadLink2.geometry.last, None, NodeType.EndOfRoad, DateTime.now(), None, DateTime.now(), None, Some("user"), Some(DateTime.now()))

      nodeDAO.create(Seq(node1, node2, node3))

      val nodePointTemplates = projectLinks.flatMap(pl => nodePointDAO.fetchNodePointTemplate(pl.roadwayNumber))
      nodePointTemplates.length should be(4)
      nodePointTemplates.foreach(
        np => {
          if (np.roadwayNumber == roadLink1.roadwayNumber) {
            if (np.addrM == roadLink1.startAddrMValue)
              sqlu"""UPDATE NODE_POINT SET NODE_ID = ${node1.id} WHERE ID = ${np.id}""".execute
            else if (np.addrM == roadLink1.endAddrMValue)
              sqlu"""UPDATE NODE_POINT SET NODE_ID = ${node2.id} WHERE ID = ${np.id}""".execute
          } else if (np.roadwayNumber == roadLink2.roadwayNumber) {
            if (np.addrM == roadLink2.startAddrMValue)
              sqlu"""UPDATE NODE_POINT SET NODE_ID = ${node2.id} WHERE ID = ${np.id}""".execute
            else if (np.addrM == roadLink2.endAddrMValue)
              sqlu"""UPDATE NODE_POINT SET NODE_ID = ${node3.id} WHERE ID = ${np.id}""".execute
          }
        }
      )

      val nodePoints = nodePointDAO.fetchNodePointsByNodeId(Seq(node1.id, node2.id, node3.id))
      nodePoints.length should be(4)
      nodePoints.exists(node => node.roadwayNumber == roadLink1.roadwayNumber && node.beforeAfter == BeforeAfter.After && node.addrM == roadLink1.startAddrMValue) should be(true)
      nodePoints.exists(node => node.roadwayNumber == roadLink1.roadwayNumber && node.beforeAfter == BeforeAfter.Before && node.addrM == roadLink1.endAddrMValue) should be(true)
      nodePoints.exists(node => node.roadwayNumber == roadLink2.roadwayNumber && node.beforeAfter == BeforeAfter.After && node.addrM == roadLink2.startAddrMValue) should be(true)
      nodePoints.exists(node => node.roadwayNumber == roadLink2.roadwayNumber && node.beforeAfter == BeforeAfter.Before && node.addrM == roadLink2.endAddrMValue) should be(true)

      val terminatedRoadLink = roadLink2.copy(endDate = Some(DateTime.now()), status = LinkStatus.Terminated, projectId = 1)

      nodesAndJunctionsService.expireObsoleteNodesAndJunctions(Seq(terminatedRoadLink), Some(terminatedRoadLink.endDate.get.minusDays(1)))

      // Test expired node and node points
      val nodePointsAfterExpiration = nodePointDAO.fetchNodePointsByNodeId(Seq(node1.id, node2.id, node3.id))
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

      // Creation of nodes and node points
      nodesAndJunctionsService.handleNodePointTemplates(projectLinks)

      val node1 = Node(Sequences.nextNodeId, Sequences.nextNodeNumber, roadLink.geometry.head, None, NodeType.EndOfRoad, DateTime.now(), None, DateTime.now(), None, Some("user"), Some(DateTime.now()))
      val node2 = Node(Sequences.nextNodeId, Sequences.nextNodeNumber, roadLink.geometry.last, None, NodeType.EndOfRoad, DateTime.now(), None, DateTime.now(), None, Some("user"), Some(DateTime.now()))

      nodeDAO.create(Seq(node1, node2))

      val nodePointTemplates = projectLinks.flatMap(pl => nodePointDAO.fetchNodePointTemplate(pl.roadwayNumber))
      nodePointTemplates.length should be(2)

      nodePointTemplates.foreach(
        np => {
          if (np.addrM == roadLink.startAddrMValue)
            sqlu"""UPDATE NODE_POINT SET NODE_ID = ${node1.id} WHERE ID = ${np.id}""".execute
          else if (np.addrM == roadLink.endAddrMValue)
            sqlu"""UPDATE NODE_POINT SET NODE_ID = ${node2.id} WHERE ID = ${np.id}""".execute
        }
      )

      val nodePoints = nodePointDAO.fetchNodePointsByNodeId(Seq(node1.id, node2.id))
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
      val nodePointsAfterExpiration = nodePointDAO.fetchNodePointsByNodeId(Seq(node1.id, node2.id))
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

      // Creation of nodes and node points
      nodesAndJunctionsService.handleNodePointTemplates(projectLinks)

      val node1 = Node(Sequences.nextNodeId, Sequences.nextNodeNumber, roadLink.geometry.head, None, NodeType.EndOfRoad, DateTime.now(), None, DateTime.now(), None, Some("user"), Some(DateTime.now()))
      val node2 = Node(Sequences.nextNodeId, Sequences.nextNodeNumber, roadLink.geometry.last, None, NodeType.EndOfRoad, DateTime.now(), None, DateTime.now(), None, Some("user"), Some(DateTime.now()))

      nodeDAO.create(Seq(node1, node2))

      val nodePointTemplates = projectLinks.flatMap(pl => nodePointDAO.fetchNodePointTemplate(pl.roadwayNumber))
      nodePointTemplates.length should be(2)

      nodePointTemplates.foreach(
        np => {
          if (np.addrM == roadLink.startAddrMValue)
            sqlu"""UPDATE NODE_POINT SET NODE_ID = ${node1.id} WHERE ID = ${np.id}""".execute
          else if (np.addrM == roadLink.endAddrMValue)
            sqlu"""UPDATE NODE_POINT SET NODE_ID = ${node2.id} WHERE ID = ${np.id}""".execute
        }
      )

      val nodePoints = nodePointDAO.fetchNodePointsByNodeId(Seq(node1.id, node2.id))
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

      nodesAndJunctionsService.handleNodePointTemplates(Seq(roadLink, newRoadLink))
      nodesAndJunctionsService.expireObsoleteNodesAndJunctions(Seq(roadLink, newRoadLink), Some(DateTime.now().minusDays(1)))

      // Test expired node and node points
      val nodePointsAfterExpiration = nodePointDAO.fetchNodePointsByNodeId(Seq(node1.id, node2.id))
      nodePointsAfterExpiration.length should be(1)
      nodePointsAfterExpiration.exists(node => node.roadwayNumber == roadLink.roadwayNumber && node.beforeAfter == BeforeAfter.After && node.addrM == roadLink.startAddrMValue) should be(false)
      nodePointsAfterExpiration.exists(node => node.roadwayNumber == roadLink.roadwayNumber && node.beforeAfter == BeforeAfter.Before && node.addrM == roadLink.endAddrMValue) should be(true)
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

      // Creation of junction points template
      nodesAndJunctionsService.handleJunctionPointTemplates(projectLinks)
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

      // Creation of junction points template
      nodesAndJunctionsService.handleJunctionPointTemplates(projectLinks)
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

  /**
    * Test case for Numbering:
    * Reserve road number 2 part 1
    * * Assume road number 1 part 1
    * Renumber road number 2 part 1
    * * to road number 1 part 2
    *
    * Expected (now, we have the same road number)
    * Junction Points should be expired conditionally:
    * * If the remaining junction points referenced by this junctionId are all present in the same road number
    * then all of those junction points and the junction itself should expire.
    */
  test("Test expireObsoleteNodesAndJunctions case when Numbering a road with different road number to the same road number of the road before Then the junction and the junction points should be expired if they are in the same road") {
    runWithRollback {
      val roadGeom1 = Seq(Point(0.0, 0.0), Point(100.0, 0.0))
      val roadGeom2 = Seq(Point(100.0, 0.0), Point(250.0, 0.0))

      val roadwayNumber = Sequences.nextRoadwayNumber

      val roadLink1 = dummyProjectLink(1, 1, Track.Combined, Discontinuity.Continuous, 0, 100, Some(DateTime.now()), None, 12345, 0, 100.0, SideCode.TowardsDigitizing, LinkStatus.New, 0, RoadType.PublicRoad, roadGeom1, roadwayNumber)
      val roadLink2 = dummyProjectLink(2, 1, Track.Combined, Discontinuity.Continuous, 0, 150, Some(DateTime.now()), None, 12346, 0, 150.0, SideCode.TowardsDigitizing, LinkStatus.New, 0, RoadType.PublicRoad, roadGeom2, roadwayNumber + 1)

      val projectLinks = Seq(roadLink1, roadLink2)

      val roadways = Seq(
        Roadway(NewIdValue, roadLink1.roadwayNumber, roadLink1.roadNumber, roadLink1.roadPartNumber, roadLink1.roadType, roadLink1.track, roadLink1.discontinuity, roadLink1.startAddrMValue, roadLink1.endAddrMValue, reversed = false, roadLink1.startDate.get, roadLink1.endDate, "user", None, 8L, TerminationCode.NoTermination, DateTime.now, None),
        Roadway(NewIdValue, roadLink2.roadwayNumber, roadLink2.roadNumber, roadLink2.roadPartNumber, roadLink2.roadType, roadLink2.track, roadLink2.discontinuity, roadLink2.startAddrMValue, roadLink2.endAddrMValue, reversed = false, roadLink2.startDate.get, roadLink2.endDate, "user", None, 8L, TerminationCode.NoTermination, DateTime.now, None))
      val linearLocations = Seq(
        LinearLocation(NewIdValue, 1, roadLink1.linkId, roadLink1.startAddrMValue, roadLink1.endAddrMValue, roadLink1.sideCode, 0L, calibrationPoints = (Some(roadLink1.startAddrMValue), Some(roadLink1.endAddrMValue)), roadGeom1, LinkGeomSource.NormalLinkInterface, roadLink1.roadwayNumber, Some(DateTime.now), None),
        LinearLocation(NewIdValue, 1, roadLink2.linkId, roadLink2.startAddrMValue, roadLink2.endAddrMValue, roadLink2.sideCode, 0L, calibrationPoints = (Some(roadLink2.startAddrMValue), Some(roadLink2.endAddrMValue)), roadGeom2, LinkGeomSource.NormalLinkInterface, roadLink2.roadwayNumber, Some(DateTime.now), None))

      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(any[BoundingRectangle], any[Seq[(Int, Int)]])).thenReturn(linearLocations)
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(any[Set[Long]], any[Boolean])).thenReturn(roadways)

      // Creation of junction points template
      nodesAndJunctionsService.handleJunctionPointTemplates(projectLinks)
      val junctionPointTemplates = junctionPointDAO.fetchByRoadwayPointIds(
        roadwayPointDAO.fetchByRoadwayNumbers(projectLinks.map(_.roadwayNumber)).map(_.id))
      junctionPointTemplates.length should be(2)

      val junctions = junctionDAO.fetchByIds(junctionPointTemplates.map(_.junctionId))
      junctions.length should be(1)

      val numberedRoadLink = roadLink2.copy(roadNumber = 1, roadPartNumber = 2, status = LinkStatus.Numbering, projectId = 1)

      nodesAndJunctionsService.expireObsoleteNodesAndJunctions(Seq(numberedRoadLink), Some(DateTime.now().minusDays(1)))

      // Test expired junction points
      val junctionPointTemplatesAfterNumbering = junctionPointDAO.fetchByRoadwayPointIds(
        roadwayPointDAO.fetchByRoadwayNumbers(Seq(roadLink1, numberedRoadLink).map(_.roadwayNumber)).map(_.id))
      junctionPointTemplatesAfterNumbering.length should be(0)

      // Test expired junction
      val expiredJunctions = junctionDAO.fetchByIds(junctionPointTemplates.map(_.junctionId))
      expiredJunctions.length should be(0)
    }
  }
  // </editor-fold>
  // <editor-fold desc="Combined">
  /**
    * Test case for Transferring:
    * Reserve road number 1 part 1
    * Reserve road number 2 part 1
    * Transfer last link of road 1 to road 2
    *
    * Expected
    * Node Points should be expired conditionally :
    * * If the remaining node points referenced by this nodeId are all present in the same road number, road part, track and road type
    * then all of those node points and the node itself should expire.
    * Junction Points should be expired conditionally:
    * * If the remaining junction points referenced by this junctionId are all present in the same road number
    * then all of those junction points and the junction itself should expire.
    */
  test("Test expireObsoleteNodesAndJunctions case when Transferring a link for a different road number Then the nodes/node points and junctions/junction points should be expired if they are in the same road") {
    runWithRollback {
      val road1Geom1 = Seq(Point(0.0, 0.0), Point(100.0, 0.0))
      val road1Geom2 = Seq(Point(100.0, 0.0), Point(150.0, 0.0))
      val road2Geom = Seq(Point(150.0, 0.0), Point(200.0, 0.0))

      val roadwayNumber = Sequences.nextRoadwayNumber

      val road1Link1 = dummyProjectLink(1, 1, Track.Combined, Discontinuity.Continuous, 0, 100, Some(DateTime.now()), None, 12345, 0, 100.0, SideCode.TowardsDigitizing, LinkStatus.New, 0, RoadType.PublicRoad, road1Geom1, roadwayNumber)
      val road1Link2 = dummyProjectLink(1, 1, Track.Combined, Discontinuity.Continuous, 100, 150, Some(DateTime.now()), None, 12346, 100.0, 150.0, SideCode.TowardsDigitizing, LinkStatus.New, 0, RoadType.PublicRoad, road1Geom2, roadwayNumber)
      val road2 = dummyProjectLink(2, 1, Track.Combined, Discontinuity.Continuous, 0, 50, Some(DateTime.now()), None, 12347, 0, 50.0, SideCode.TowardsDigitizing, LinkStatus.New, 0, RoadType.PublicRoad, road2Geom, roadwayNumber + 1)

      val roadways = Seq(
        Roadway(NewIdValue, road1Link1.roadwayNumber, road1Link1.roadNumber, road1Link1.roadPartNumber, road1Link1.roadType, road1Link1.track, road1Link1.discontinuity, road1Link1.startAddrMValue, road1Link2.endAddrMValue, reversed = false, road1Link1.startDate.get, road1Link1.endDate, "user", None, 8L, TerminationCode.NoTermination, DateTime.now, None),
        Roadway(NewIdValue, road2.roadwayNumber, road2.roadNumber, road2.roadPartNumber, road2.roadType, road2.track, road2.discontinuity, road2.startAddrMValue, road2.endAddrMValue, reversed = false, road2.startDate.get, road2.endDate, "user", None, 8L, TerminationCode.NoTermination, DateTime.now, None))
      val linearLocations = Seq(
        LinearLocation(NewIdValue, 1, road1Link1.linkId, road1Link1.startAddrMValue, road1Link1.endAddrMValue, road1Link1.sideCode, 0L, calibrationPoints = (Some(road1Link1.startAddrMValue), Some(road1Link1.endAddrMValue)), road1Link1.geometry, LinkGeomSource.NormalLinkInterface, road1Link1.roadwayNumber, Some(DateTime.now), None),
        LinearLocation(NewIdValue, 2, road1Link2.linkId, road1Link2.startAddrMValue, road1Link2.endAddrMValue, road1Link2.sideCode, 0L, calibrationPoints = (Some(road1Link2.startAddrMValue), Some(road1Link2.endAddrMValue)), road1Link2.geometry, LinkGeomSource.NormalLinkInterface, road1Link2.roadwayNumber, Some(DateTime.now), None),
        LinearLocation(NewIdValue, 1, road2.linkId, road2.startAddrMValue, road2.endAddrMValue, road2.sideCode, 0L, calibrationPoints = (Some(road2.startAddrMValue), Some(road2.endAddrMValue)), road2.geometry, LinkGeomSource.NormalLinkInterface, road2.roadwayNumber, Some(DateTime.now), None))

      when(mockLinearLocationDAO.fetchLinearLocationByBoundingBox(any[BoundingRectangle], any[Seq[(Int, Int)]])).thenReturn(linearLocations)
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(any[Set[Long]], any[Boolean])).thenReturn(roadways)

      val projectLinks = Seq(road1Link1, road1Link2, road2)

      // Creation of junction points template
      nodesAndJunctionsService.handleJunctionPointTemplates(projectLinks)
      val junctionPointTemplates = junctionPointDAO.fetchByRoadwayPointIds(
        roadwayPointDAO.fetchByRoadwayNumbers(projectLinks.map(_.roadwayNumber)).map(_.id))
      junctionPointTemplates.length should be(2)

      val junctions = junctionDAO.fetchByIds(junctionPointTemplates.map(_.junctionId))
      junctions.length should be(1)

      // Creation of node points template
      nodesAndJunctionsService.handleNodePointTemplates(projectLinks)

      val node1 = Node(Sequences.nextNodeId, Sequences.nextNodeNumber, road1Link1.geometry.head, None, NodeType.EndOfRoad, DateTime.now(), None, DateTime.now(), None, Some("user"), Some(DateTime.now()))
      val node2 = Node(Sequences.nextNodeId, Sequences.nextNodeNumber, road2.geometry.head, None, NodeType.EndOfRoad, DateTime.now(), None, DateTime.now(), None, Some("user"), Some(DateTime.now()))
      val node3 = Node(Sequences.nextNodeId, Sequences.nextNodeNumber, road2.geometry.last, None, NodeType.EndOfRoad, DateTime.now(), None, DateTime.now(), None, Some("user"), Some(DateTime.now()))

      nodeDAO.create(Seq(node1, node2, node3))

      val nodePointTemplates = projectLinks.flatMap(pl => nodePointDAO.fetchNodePointTemplate(pl.roadwayNumber)).distinct
      nodePointTemplates.length should be(4)
      nodePointTemplates.foreach(
        np => {
          if (np.roadwayNumber == road1Link1.roadwayNumber || np.roadwayNumber == road1Link2.roadwayNumber) {
            if (np.addrM == road1Link1.startAddrMValue)
              sqlu"""UPDATE NODE_POINT SET NODE_ID = ${node1.id} WHERE ID = ${np.id}""".execute
            else if (np.addrM == road1Link2.endAddrMValue)
              sqlu"""UPDATE NODE_POINT SET NODE_ID = ${node2.id} WHERE ID = ${np.id}""".execute
          } else if (np.roadwayNumber == road2.roadwayNumber) {
            if (np.addrM == road2.startAddrMValue)
              sqlu"""UPDATE NODE_POINT SET NODE_ID = ${node2.id} WHERE ID = ${np.id}""".execute
            else if (np.addrM == road2.endAddrMValue)
              sqlu"""UPDATE NODE_POINT SET NODE_ID = ${node3.id} WHERE ID = ${np.id}""".execute
          }
        }
      )

      val nodePoints = nodePointDAO.fetchNodePointsByNodeId(Seq(node1.id, node2.id, node3.id))
      nodePoints.length should be(4)

      nodePoints.exists(node => node.roadwayNumber == road1Link1.roadwayNumber && node.beforeAfter == BeforeAfter.After && node.addrM == road1Link1.startAddrMValue) should be(true)
      nodePoints.exists(node => node.roadwayNumber == road1Link2.roadwayNumber && node.beforeAfter == BeforeAfter.Before && node.addrM == road1Link2.endAddrMValue) should be(true)
      nodePoints.exists(node => node.roadwayNumber == road2.roadwayNumber && node.beforeAfter == BeforeAfter.After && node.addrM == road2.startAddrMValue) should be(true)
      nodePoints.exists(node => node.roadwayNumber == road2.roadwayNumber && node.beforeAfter == BeforeAfter.Before && node.addrM == road2.endAddrMValue) should be(true)

      val transferredRoadLink = road1Link2.copy(roadNumber = road2.roadNumber, roadPartNumber = road2.roadPartNumber,
        startAddrMValue = 0, endAddrMValue = 50, originalStartAddrMValue = road1Link2.startAddrMValue, originalEndAddrMValue = road1Link2.endAddrMValue,
        status = LinkStatus.Transfer, projectId = 1)

      nodesAndJunctionsService.expireObsoleteNodesAndJunctions(Seq(transferredRoadLink), Some(DateTime.now().minusDays(1)))

      // Test expired node and node points
      val nodePointsAfterExpiration = nodePointDAO.fetchNodePointsByNodeId(Seq(node1.id, node2.id, node3.id))
      nodePointsAfterExpiration.length should be(2)
      nodePointsAfterExpiration.exists(node => node.roadwayNumber == road1Link1.roadwayNumber && node.beforeAfter == BeforeAfter.After && node.nodeId.getOrElse(0L) == node1.id) should be(true)
      nodePointsAfterExpiration.exists(node => node.nodeId.getOrElse(0L) == node2.id) should be(false) // expired
      nodePointsAfterExpiration.exists(node => node.roadwayNumber == road2.roadwayNumber && node.beforeAfter == BeforeAfter.Before && node.nodeId.getOrElse(0L) == node3.id) should be(true)

      // Test expired junction points
      val junctionPointTemplatesAfterNumbering = junctionPointDAO.fetchByRoadwayPointIds(
        roadwayPointDAO.fetchByRoadwayNumbers(projectLinks.map(_.roadwayNumber)).map(_.id))
      junctionPointTemplatesAfterNumbering.length should be(0)

      // Test expired junction
      val expiredJunctions = junctionDAO.fetchByIds(junctionPointTemplates.map(_.junctionId))
      expiredJunctions.length should be(0)
    }
  }
  // </editor-fold>
}

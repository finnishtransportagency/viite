package fi.liikennevirasto.viite

import fi.liikennevirasto.digiroad2.Point
import fi.liikennevirasto.digiroad2.asset.{BoundingRectangle, LinkGeomSource, SideCode}
import fi.liikennevirasto.digiroad2.dao.Sequences
import fi.liikennevirasto.digiroad2.oracle.OracleDatabase
import fi.liikennevirasto.digiroad2.util.Track
import fi.liikennevirasto.viite.dao._
import org.joda.time.DateTime
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{BeforeAndAfter, FunSuite, Matchers}
import slick.driver.JdbcDriver.backend.Database
import slick.driver.JdbcDriver.backend.Database.dynamicSession

import scala.util.{Left, Right}

class NodesAndJunctionsServiceSpec extends FunSuite with Matchers with BeforeAndAfter {

  val mockNodesAndJunctionsService = MockitoSugar.mock[NodesAndJunctionsService]

  val nodeDAO = new NodeDAO
  val roadwayDAO = new RoadwayDAO
  val roadwayPointDAO = new RoadwayPointDAO
  val nodePointDAO = new NodePointDAO
  val junctionDAO = new JunctionDAO
  val junctionPointDAO = new JunctionPointDAO
  val linearLocationDAO = new LinearLocationDAO

  val nodesAndJunctionsService = new NodesAndJunctionsService() {
    override def withDynSession[T](f: => T): T = f

    override def withDynTransaction[T](f: => T): T = f
  }

  private val roadNumber1 = 990
  private val roadwayNumber1 = 1000000000l
  private val roadPartNumber1 = 1
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

  def runWithRollback(f: => Unit): Unit = {
    Database.forDataSource(OracleDatabase.ds).withDynTransaction {
      f
      dynamicSession.rollback()
    }
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

}

package fi.liikennevirasto.viite.dao

import fi.liikennevirasto.digiroad2.Point
import fi.liikennevirasto.digiroad2.asset.BoundingRectangle
import fi.liikennevirasto.digiroad2.dao.Sequences
import fi.liikennevirasto.digiroad2.oracle.OracleDatabase
import fi.liikennevirasto.digiroad2.util.Track
import fi.liikennevirasto.viite.{NewIdValue, RoadType}
import org.joda.time.DateTime
import org.scalatest.{FunSuite, Matchers}
import slick.driver.JdbcDriver.backend.Database
import slick.driver.JdbcDriver.backend.Database.dynamicSession

class NodeDAOSpec extends FunSuite with Matchers {

  def runWithRollback(f: => Unit): Unit = {
    Database.forDataSource(OracleDatabase.ds).withDynTransaction {
      f
      dynamicSession.rollback()
    }
  }

  private val nonExistingRoadNumber = -1
  private val existingRoadNumber = 10

  private val nonExistingRoadPartNumber = -1

  val dao = new NodeDAO
  val nodePointDAO = new NodePointDAO
  val roadwayDAO = new RoadwayDAO
  val roadwayPointDAO = new RoadwayPointDAO

  private val roadNumber1 = 990
  private val roadwayNumber1 = 1000000000l
  private val roadPartNumber1 = 1
  val testRoadway1 = Roadway(NewIdValue, roadwayNumber1, roadNumber1, roadPartNumber1, RoadType.PublicRoad, Track.Combined, Discontinuity.Continuous,
    0, 100, reversed = false, DateTime.parse("2000-01-01"), None, "test", Some("TEST ROAD 1"), 1, TerminationCode.NoTermination)

  val testRoadwayPoint1 = RoadwayPoint(NewIdValue, roadwayNumber1, 0, "Test", None, None, None)

  val testNode1 = Node(NewIdValue, NewIdValue, Point(100, 100), Some("Test node 1"), NodeType.NormalIntersection,
    DateTime.parse("2019-01-01"), None, DateTime.parse("2019-01-01"), None, "Test", None)

  val testRoadAttributes1 = RoadAttributes(roadNumber1, roadPartNumber1, testRoadway1.track.value, 0)

  val testNodePoint1 = NodePoint(NewIdValue, BeforeAfter.Before, -1, None, NodePointType.UnknownNodePointType, Some(testNode1.startDate), None,
    DateTime.parse("2019-01-01"), None, "Test", None, 0, 0, 0, 0, Track.Combined, 0)

  test("Test fetchByRoadAttributes When non-existing road number Then return None") {
    runWithRollback {
      val nodesAndRoadAttributes = dao.fetchByRoadAttributes(nonExistingRoadNumber, None, None)
      nodesAndRoadAttributes.isEmpty should be(true)
    }
  }

  test("Test fetchByRoadAttributes When existing road number but no nodes Then return None") {
    runWithRollback {
      roadwayDAO.create(Seq(testRoadway1))
      val nodesAndRoadAttributes = dao.fetchByRoadAttributes(roadNumber1, None, None)
      nodesAndRoadAttributes.isEmpty should be(true)
    }
  }

  test("Test fetchByRoadAttributes When existing road number but invalid road part number range Then return None") {
    runWithRollback {
      val nodesAndRoadAttributes = dao.fetchByRoadAttributes(existingRoadNumber, Some(2), Some(1))
      nodesAndRoadAttributes.isEmpty should be(true)
    }
  }

  test("Test fetchByRoadAttributes When existing road number but non existing road part number range Then return None") {
    runWithRollback {
      val nodeAndRoadAttr = dao.fetchByRoadAttributes(existingRoadNumber, Some(nonExistingRoadPartNumber), Some(nonExistingRoadPartNumber))
      nodeAndRoadAttr.isEmpty should be(true)
    }
  }

  test("Test fetchByRoadAttributes When existing road number with related nodes Then return nodes") {
    runWithRollback {
      roadwayDAO.create(Seq(testRoadway1))
      val nodeId = Sequences.nextNodeId
      val nodeNumber = dao.create(Seq(testNode1.copy(id = nodeId))).head
      val roadwayPointId = Sequences.nextRoadwayPointId
      roadwayPointDAO.create(testRoadwayPoint1.copy(id = roadwayPointId))
      nodePointDAO.create(Seq(testNodePoint1.copy(nodeNumber = Some(nodeNumber), roadwayPointId = roadwayPointId)))
      val nodesAndRoadAttributes = dao.fetchByRoadAttributes(roadNumber1, None, None)
      nodesAndRoadAttributes.isEmpty should be(false)
      nodesAndRoadAttributes.size should be(1)
      val (node, roadAttribute) = nodesAndRoadAttributes.head
      node.id should be(nodeId)
      roadAttribute.roadNumber should be(roadNumber1)
    }
  }

  test("Test fetchEmptyNodes When one empty Then return one") {
    runWithRollback {
      val nodeNumbers = dao.create(Seq(testNode1))
      val nodes = dao.fetchEmptyNodes(nodeNumbers)
      nodes.size should be(1)
      nodes.head.nodeNumber should be(nodeNumbers.head)
      nodes.head.coordinates.x should be(testNode1.coordinates.x)
      nodes.head.coordinates.y should be(testNode1.coordinates.y)
    }
  }

  test("Test fetchByBoundingBox When matching Then return them") {
    runWithRollback {
      val nodeNumbers = dao.create(Seq(testNode1))
      val nodes = dao.fetchByBoundingBox(BoundingRectangle(Point(50, 50), Point(150, 150)))
      nodes.size should be(1)
      nodes.head.nodeNumber should be(nodeNumbers.head)
      nodes.head.coordinates.x should be(testNode1.coordinates.x)
      nodes.head.coordinates.y should be(testNode1.coordinates.y)
    }
  }

  test("Test fetchByNodeNumber When matching Then return them") {
    runWithRollback {
      val nodeNumber = Sequences.nextNodeNumber
      val nodeNumbers = dao.create(Seq(testNode1.copy(nodeNumber = nodeNumber)))
      val nodes = dao.fetchByNodeNumber(nodeNumber)
      nodes.size should be(1)
      nodes.head.nodeNumber should be(nodeNumbers.head)
      nodes.head.coordinates.x should be(testNode1.coordinates.x)
      nodes.head.coordinates.y should be(testNode1.coordinates.y)
    }
  }

}

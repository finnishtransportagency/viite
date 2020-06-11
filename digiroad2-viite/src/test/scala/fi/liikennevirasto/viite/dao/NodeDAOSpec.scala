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
import slick.jdbc.StaticQuery.interpolation

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
  val junctionDAO = new JunctionDAO
  val junctionPointDAO = new JunctionPointDAO

  private val roadNumber1 = 990
  private val roadwayNumber1 = 1000000000l
  private val roadPartNumber1 = 1
  val testRoadway1 = Roadway(NewIdValue, roadwayNumber1, roadNumber1, roadPartNumber1, RoadType.PublicRoad, Track.Combined, Discontinuity.Continuous,
    0, 100, reversed = false, DateTime.parse("2000-01-01"), None, "test", Some("TEST ROAD 1"), 1, TerminationCode.NoTermination)

  val testRoadwayPoint1 = RoadwayPoint(NewIdValue, roadwayNumber1, 0, "Test", None, None, None)

  val testNode1 = Node(NewIdValue, NewIdValue, Point(100, 100), Some("Test node 1"), NodeType.NormalIntersection,
    DateTime.parse("2019-01-01"), None, DateTime.parse("2019-01-01"), None, "Test", None, registrationDate = new DateTime())

  val testRoadAttributes1 = RoadAttributes(roadNumber1, roadPartNumber1, 0)

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

  test("Test fetchNodeNumbersByProject for changed road When matching Then return them") {
    runWithRollback {
      roadwayDAO.create(Seq(testRoadway1))
      val nodeId = Sequences.nextNodeId
      val nodeNumber = dao.create(Seq(testNode1.copy(id = nodeId))).head
      val roadwayPointId = Sequences.nextRoadwayPointId
      roadwayPointDAO.create(testRoadwayPoint1.copy(id = roadwayPointId))
      val testJunction1 = Junction(NewIdValue, None, Option(nodeNumber), DateTime.parse("2019-01-01"), None,
        DateTime.parse("2019-01-01"), None, "Test", None)
      val testJunctionPoint1 = JunctionPoint(NewIdValue, BeforeAfter.Before, -1, -1, None, None,
        DateTime.parse("2019-01-01"), None, "Test", None, -1, 10, 0, 0, Track.Combined, Discontinuity.Continuous)

      val junctionId = junctionDAO.create(Seq(testJunction1)).head
      val ids = junctionPointDAO.create(Seq(testJunctionPoint1.copy(junctionId = junctionId, roadwayPointId = roadwayPointId)))

      sqlu""" insert into ROADWAY_CHANGES(project_id,change_type,new_road_number,new_road_part_number,new_TRACK,new_start_addr_m,new_end_addr_m,new_discontinuity,new_road_type,new_ely, ROADWAY_CHANGE_ID) Values(100,1,$roadNumber1,$roadPartNumber1,1,0,10.5,1,1,8, 1) """.execute
      val projectId = sql"""Select rac.project_id From ROADWAY_CHANGES rac where new_road_number = $roadNumber1 and new_road_part_number = $roadPartNumber1""".as[Long].first

      val nodeNumbers = dao.fetchNodeNumbersByProject(projectId)

      nodeNumbers.size should be(1)
      nodeNumbers(0) should be(nodeNumber)
    }
  }

  test("Test fetchNodeNumbersByProject for terminated road When matching Then return them") {
    runWithRollback {
      roadwayDAO.create(Seq(testRoadway1))
      val nodeId = Sequences.nextNodeId
      val nodeNumber = dao.create(Seq(testNode1.copy(id = nodeId))).head
      val roadwayPointId = Sequences.nextRoadwayPointId
      roadwayPointDAO.create(testRoadwayPoint1.copy(id = roadwayPointId))
      val testJunction1 = Junction(NewIdValue, None, Option(nodeNumber), DateTime.parse("2019-01-01"), None,
        DateTime.parse("2019-01-01"), None, "Test", None)
      val testJunctionPoint1 = JunctionPoint(NewIdValue, BeforeAfter.Before, -1, -1, None, None,
        DateTime.parse("2019-01-01"), None, "Test", None, -1, 10, 0, 0, Track.Combined, Discontinuity.Continuous)

      val junctionId = junctionDAO.create(Seq(testJunction1)).head
      val ids = junctionPointDAO.create(Seq(testJunctionPoint1.copy(junctionId = junctionId, roadwayPointId = roadwayPointId)))

      sqlu""" insert into ROADWAY_CHANGES(project_id,change_type,old_road_number,old_road_part_number,old_TRACK,old_start_addr_m,old_end_addr_m,old_discontinuity,new_discontinuity,old_road_type,new_road_type,old_ely,new_ely, ROADWAY_CHANGE_ID)
                                   Values(100,2,$roadNumber1,$roadPartNumber1,1,0,10.5,1,1,1,1,8,8, 1) """.execute
      val projectId = sql"""Select rac.project_id From ROADWAY_CHANGES rac where old_road_number = $roadNumber1 and old_road_part_number = $roadPartNumber1""".as[Long].first

      val nodeNumbers = dao.fetchNodeNumbersByProject(projectId)

      nodeNumbers.size should be(1)
      nodeNumbers(0) should be(nodeNumber)
    }
  }

  test("Test Node publish") {
    runWithRollback {
      val nodeId = Sequences.nextNodeId
      val nodeNumber = dao.create(Seq(testNode1.copy(id = nodeId))).head
      dao.publish(nodeId,"testuser")
      val node = dao.fetchById(nodeId)
      node.get.editor should be(Some("testuser"))
    }
  }

}

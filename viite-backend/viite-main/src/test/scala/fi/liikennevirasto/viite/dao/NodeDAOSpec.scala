package fi.liikennevirasto.viite.dao

import fi.liikennevirasto.viite.NewIdValue
import fi.vaylavirasto.viite.dao.{BaseDAO, Sequences}
import fi.vaylavirasto.viite.geometry.{BoundingRectangle, Point}
import fi.vaylavirasto.viite.model.{AddrMRange, AdministrativeClass, BeforeAfter, Discontinuity, NodePointType, NodeType, RoadPart, Track}
import fi.vaylavirasto.viite.postgis.PostGISDatabaseScalikeJDBC.runWithRollback
import org.joda.time.DateTime
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalikejdbc._

class NodeDAOSpec extends AnyFunSuite with Matchers with BaseDAO {

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
  private val roadwayNumber1 = 1000000000L
  private val roadPartNumber1 = 1
  private val testRoadway1 = Roadway(NewIdValue, roadwayNumber1, RoadPart(roadNumber1, roadPartNumber1), AdministrativeClass.State, Track.Combined, Discontinuity.Continuous, AddrMRange(0, 100), reversed = false, DateTime.parse("2000-01-01"), None, "test", Some("TEST ROAD 1"), 1, TerminationCode.NoTermination)
  private val testRoadwayPoint1 = RoadwayPoint(NewIdValue, roadwayNumber1, 0, "Test", None, None, None)
  private val testNode1 = Node(NewIdValue, NewIdValue, Point(100, 100), Some("Test node 1"), NodeType.NormalIntersection,
    DateTime.parse("2019-01-01"), None, DateTime.parse("2019-01-01"), None, "Test", None, registrationDate = new DateTime())
  private val testNodePoint1 = NodePoint(NewIdValue, BeforeAfter.Before, -1, None, NodePointType.UnknownNodePointType, Some(testNode1.startDate), None,
    DateTime.parse("2019-01-01"), None, "Test", None, 0, 0, RoadPart(0, 0), Track.Combined, 0)

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
      roadAttribute.roadPart.roadNumber should be(roadNumber1)
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
        DateTime.parse("2019-01-01"), None, "Test", None, -1, 10, RoadPart(0, 0), Track.Combined, Discontinuity.Continuous)

      val junctionId = junctionDAO.create(Seq(testJunction1)).head
      val ids = junctionPointDAO.create(Seq(testJunctionPoint1.copy(junctionId = junctionId, roadwayPointId = roadwayPointId)))

      runUpdateToDb(
        sql"""
             INSERT INTO ROADWAY_CHANGES(project_id,change_type,new_road_number,new_road_part_number,new_TRACK,new_start_addr_m,new_end_addr_m,new_discontinuity,NEW_ADMINISTRATIVE_CLASS,new_ely, ROADWAY_CHANGE_ID)
             Values(100,1,$roadNumber1,$roadPartNumber1,1,0,10.5,1,1,8, 1)
             """
      )
      val projectId =
        runSelectSingleFirstWithType[Long](sql"""
            SELECT rac.project_id
            FROM ROADWAY_CHANGES rac
            WHERE new_road_number = $roadNumber1
            AND new_road_part_number = $roadPartNumber1
            """
        )

      val nodeNumbers = dao.fetchNodeNumbersByProject(projectId)

      nodeNumbers.size should be(1)
      nodeNumbers.head should be(nodeNumber)
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
        DateTime.parse("2019-01-01"), None, "Test", None, -1, 10, RoadPart(0, 0), Track.Combined, Discontinuity.Continuous)

      val junctionId = junctionDAO.create(Seq(testJunction1)).head
      val ids = junctionPointDAO.create(Seq(testJunctionPoint1.copy(junctionId = junctionId, roadwayPointId = roadwayPointId)))

      runUpdateToDb(sql"""
                    INSERT INTO ROADWAY_CHANGES(project_id,change_type,old_road_number,old_road_part_number,old_TRACK,old_start_addr_m,old_end_addr_m,old_discontinuity,new_discontinuity,OLD_ADMINISTRATIVE_CLASS,NEW_ADMINISTRATIVE_CLASS,old_ely,new_ely, ROADWAY_CHANGE_ID)
                    VALUES (100,2,$roadNumber1,$roadPartNumber1,1,0,10.5,1,1,1,1,8,8, 1)
                    """)
      val projectId = runSelectSingleFirstWithType[Long](sql"""
             SELECT rac.project_id
             FROM ROADWAY_CHANGES rac
             WHERE old_road_number = $roadNumber1 AND old_road_part_number = $roadPartNumber1
          """)

      val nodeNumbers = dao.fetchNodeNumbersByProject(projectId)

      nodeNumbers.size should be(1)
      nodeNumbers.head should be(nodeNumber)
    }
  }

  test("Test Node publish") {
    runWithRollback {
      val nodeId = Sequences.nextNodeId
      /*val nodeNumber =*/ dao.create(Seq(testNode1.copy(id = nodeId))).head
      dao.publish(nodeId,"testuser")
      val node = dao.fetchById(nodeId)
      node.get.editor should be(Some("testuser"))
    }
  }

  test("Test fetchNodesForRoadAddressBrowser then return nodes based on the query") {
    runWithRollback {
      val roadPart = RoadPart(76,1)
      val junctionAddrM = 250
      val endAddrM = 500
      // create nodes
      val nodeNumber1 = Sequences.nextNodeNumber
      val nodeNumber2 = Sequences.nextNodeNumber
      dao.create(
        Seq(
          Node(Sequences.nextNodeId, nodeNumber1, Point(100, 100) ,Some("TestNode"), NodeType.NormalIntersection, DateTime.parse("2019-01-01"), None, DateTime.parse("2019-01-01"), None, "test", Some(DateTime.parse("2019-01-01")), None, Some(DateTime.parse("2019-01-01")), DateTime.parse("2019-01-01")),
          Node(Sequences.nextNodeId, nodeNumber2, Point(100, 200) ,Some("TestNode"), NodeType.EndOfRoad, DateTime.parse("2021-01-01"), None, DateTime.parse("2021-01-01"), None, "test", Some(DateTime.parse("2021-01-01")), None, Some(DateTime.parse("2021-01-01")), DateTime.parse("2021-01-01"))
        )
      )
      // create roadways
      val roadwayNumber1 = Sequences.nextRoadwayNumber
      val roadwayNumber2 = Sequences.nextRoadwayNumber
      roadwayDAO.create(
        Seq(
          Roadway(Sequences.nextRoadwayId, roadwayNumber1, roadPart, AdministrativeClass.State, Track.Combined, Discontinuity.EndOfRoad, AddrMRange(  0, 300), reversed = false, DateTime.parse("1992-10-08"), None, "test", Some("TEST ROAD 1"), 8, TerminationCode.NoTermination),
          Roadway(Sequences.nextRoadwayId, roadwayNumber2, roadPart, AdministrativeClass.State, Track.Combined, Discontinuity.EndOfRoad, AddrMRange(300, 500), reversed = false, DateTime.parse("2021-01-01"), None, "test", Some("TEST ROAD 1"), 8, TerminationCode.NoTermination)

        )
      )
      // create roadway points
      val rwpId = roadwayPointDAO.create(
        RoadwayPoint(Sequences.nextRoadwayPointId, roadwayNumber1, junctionAddrM, "test", Some(DateTime.parse("1992-10-08")), None, None)
      )
      val rwpId2 = roadwayPointDAO.create(
        RoadwayPoint(Sequences.nextRoadwayPointId, roadwayNumber2, endAddrM, "test", Some(DateTime.parse("2021-01-01")), None, None)
      )
      // create nodepoints
      nodePointDAO.create(
        Seq(
          NodePoint(Sequences.nextNodePointId, BeforeAfter.Before, rwpId,  Some(nodeNumber1), NodePointType.RoadNodePoint, Some(DateTime.parse("1992-10-08")), None, DateTime.parse("1992-10-08"), None, "test", Some(DateTime.parse("1992-10-08")), roadwayNumber1, junctionAddrM, roadPart, Track.Combined, 1, Point(100, 100)),
          NodePoint(Sequences.nextNodePointId, BeforeAfter.Before, rwpId2, Some(nodeNumber2), NodePointType.RoadNodePoint, Some(DateTime.parse("2021-01-01")), None, DateTime.parse("2021-01-01"), None, "test", Some(DateTime.parse("2021-01-01")), roadwayNumber2, endAddrM,      roadPart, Track.Combined, 1, Point(100, 200))

        )
      )
      // create junction
      val junctionId = Sequences.nextJunctionId
      junctionDAO.create(
        Seq(
          Junction(junctionId, Some(1), Some(nodeNumber1), DateTime.parse("1992-10-08"), None, DateTime.parse("1992-10-08"), None, "test", Some(DateTime.parse("1992-10-08")), None)
        )
      )

      // create junction point
      junctionPointDAO.create(
        Seq(
          JunctionPoint(Sequences.nextJunctionPointId, BeforeAfter.Before, rwpId, junctionId, Some(DateTime.parse("1992-10-08")), None, DateTime.parse("1992-10-08"), None, "test", Some(DateTime.parse("1992-10-08")), roadwayNumber1, junctionAddrM, roadPart, Track.Combined, Discontinuity.Continuous, Point(100,100)),
          JunctionPoint(Sequences.nextJunctionPointId, BeforeAfter.After,  rwpId, junctionId, Some(DateTime.parse("1992-10-08")), None, DateTime.parse("1992-10-08"), None, "test", Some(DateTime.parse("1992-10-08")), roadwayNumber1, junctionAddrM, roadPart, Track.Combined, Discontinuity.Continuous, Point(100,100))
        )
      )

      // fetch
      val resultForQuery1 = dao.fetchNodesForRoadAddressBrowser(Some("2022-01-01"), None, Some(roadPart.roadNumber), None, None)
      resultForQuery1.size should be (2)
      resultForQuery1.head shouldBe a [NodeForRoadAddressBrowser]

      val resultForQuery2 = dao.fetchNodesForRoadAddressBrowser(Some("1992-01-01"), None, Some(roadPart.roadNumber), None, None)
      resultForQuery2.size should be (0)

      val resultForQuery3 = dao.fetchNodesForRoadAddressBrowser(Some("2020-12-12"), None, Some(roadPart.roadNumber), None, None)
      resultForQuery3.size should be (1)


    }
  }

}

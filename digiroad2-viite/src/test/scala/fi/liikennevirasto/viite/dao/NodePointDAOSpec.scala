package fi.liikennevirasto.viite.dao

import fi.liikennevirasto.digiroad2.Point
import fi.liikennevirasto.digiroad2.asset.{BoundingRectangle, LinkGeomSource, SideCode}
import fi.liikennevirasto.digiroad2.oracle.OracleDatabase
import fi.liikennevirasto.viite.{NewIdValue, RoadType}
import org.joda.time.DateTime
import org.scalatest.{FunSuite, Matchers}
import slick.driver.JdbcDriver.backend.Database
import slick.driver.JdbcDriver.backend.Database.dynamicSession
import fi.liikennevirasto.digiroad2.dao.Sequences
import fi.liikennevirasto.digiroad2.util.Track
import fi.liikennevirasto.viite.dao.TerminationCode.NoTermination

class NodePointDAOSpec extends FunSuite with Matchers {

  def runWithRollback(f: => Unit): Unit = {
    Database.forDataSource(OracleDatabase.ds).withDynTransaction {
      f
      dynamicSession.rollback()
    }
  }

  val dao = new NodePointDAO
  val nodeDAO = new NodeDAO
  val roadwayDAO = new RoadwayDAO
  val roadwayPointDAO = new RoadwayPointDAO
  val linearLocationDAO = new LinearLocationDAO

  val testRoadwayPoint1 = RoadwayPoint(NewIdValue, -1, 10, "Test", None, None, None)

  val testNodePoint1 = NodePoint(NewIdValue, BeforeAfter.Before, -1, None, NodePointType.UnknownNodePointType,
    DateTime.parse("2019-01-01"), None, DateTime.parse("2019-01-01"), None, None, None, -1, 10, 0, 0, Track.Combined, 0)
  val testNodePoint2 = NodePoint(NewIdValue, BeforeAfter.After, -1, None, NodePointType.UnknownNodePointType,
    DateTime.parse("2019-01-01"), None, DateTime.parse("2019-01-01"), None, None, None, -1, 10, 0, 0, Track.Combined, 0)

  val testNode1 = Node(NewIdValue, NewIdValue, Point(100, 100), Some("Test node 1"), NodeType.NormalIntersection,
    DateTime.parse("2019-01-01"), None, DateTime.parse("2019-01-01"), None, None, None)

  val testLinearLocation1 = LinearLocation(NewIdValue, 1, 1000l, 0.0, 2.8, SideCode.TowardsDigitizing, 10000000000l,
    (None, None), Seq(Point(99.0, 99.0), Point(101.0, 101.0)), LinkGeomSource.NormalLinkInterface, -1)


  test("Test create When nothing to create Then return empty Seq") {
    runWithRollback {
      val ids = dao.create(Seq())
      ids.isEmpty should be(true)
    }
  }

  test("Test create When one created Then return Seq with one id") {
    runWithRollback {
      val roadwayPointId = roadwayPointDAO.create(testRoadwayPoint1.copy(roadwayNumber = Sequences.nextRoadwayNumber))
      val ids = dao.create(Seq(testNodePoint1.copy(roadwayPointId = roadwayPointId)))
      ids.size should be(1)
    }
  }

  test("Test create When two created Then return Seq with two ids") {
    runWithRollback {
      val roadwayPointId1 = roadwayPointDAO.create(testRoadwayPoint1.copy(roadwayNumber = Sequences.nextRoadwayNumber))
      val ids = dao.create(Seq(testNodePoint1.copy(roadwayPointId = roadwayPointId1),
        testNodePoint2.copy(roadwayPointId = roadwayPointId1)))
      ids.size should be(2)
    }
  }

  test("Test fetchNodePointsByNodeId When non-existing nodeId Then return empty Seq") {
    runWithRollback {
      val roadwayPointId1 = roadwayPointDAO.create(testRoadwayPoint1.copy(roadwayNumber = Sequences.nextRoadwayNumber))
      val nodeNumber = nodeDAO.create(Seq(testNode1)).head
      dao.create(Seq(testNodePoint1.copy(roadwayPointId = roadwayPointId1, nodeNumber = Some(nodeNumber)),
        testNodePoint2.copy(roadwayPointId = roadwayPointId1, nodeNumber = Some(nodeNumber))))
      val nodePoints = dao.fetchNodePointsByNodeNumber(Seq(-1))
      nodePoints.isEmpty should be(true)
    }
  }

  test("Test fetchNodePointsByNodeId When existing nodeId Then return node points") {
    runWithRollback {
      val newRoadwayNumber = Sequences.nextRoadwayNumber
      val roadway = Roadway(NewIdValue, newRoadwayNumber, 1999L, 1L, RoadType.PublicRoad, Track.Combined, Discontinuity.Continuous,
        0L, 10L, reversed = false, DateTime.now(), None, "test_user", None, 8, NoTermination, DateTime.now(), None)
      roadwayDAO.create(Seq(roadway))
      val roadwayPointId1 = roadwayPointDAO.create(testRoadwayPoint1.copy(roadwayNumber = newRoadwayNumber ))
      val nodeNumber = nodeDAO.create(Seq(testNode1)).head
      dao.create(Seq(testNodePoint1.copy(roadwayPointId = roadwayPointId1, nodeNumber = Some(nodeNumber)),
        testNodePoint2.copy(roadwayPointId = roadwayPointId1, nodeNumber = Some(nodeNumber))))
      val nodePoints = dao.fetchNodePointsByNodeNumber(Seq(nodeNumber))
      nodePoints.size should be(2)
      nodePoints.count(n => n.nodeNumber.contains(nodeNumber)) should be(2)
    }
  }

  test("Test fetchTemplatesByBoundingBox When no matches Then return empty Seq") {
    runWithRollback {
      val roadwayNumber = Sequences.nextRoadwayNumber
      val roadwayPointId1 = roadwayPointDAO.create(testRoadwayPoint1.copy(roadwayNumber = roadwayNumber))
      dao.create(Seq(testNodePoint1.copy(roadwayPointId = roadwayPointId1, nodeNumber = None),
        testNodePoint2.copy(roadwayPointId = roadwayPointId1, nodeNumber = None)))
      linearLocationDAO.create(Seq(testLinearLocation1.copy(roadwayNumber = roadwayNumber)))
      val nodePoints = dao.fetchTemplatesByBoundingBox(BoundingRectangle(Point(0, 0), Point(1, 1)))
      nodePoints.isEmpty should be(true)
    }
  }

  test("Test fetchTemplatesByBoundingBox When matches Then return node points") {
    runWithRollback {
      val roadwayNumber = Sequences.nextRoadwayNumber
      val roadwayPointId1 = roadwayPointDAO.create(testRoadwayPoint1.copy(roadwayNumber = roadwayNumber))
      dao.create(Seq(testNodePoint1.copy(roadwayPointId = roadwayPointId1, nodeNumber = None),
        testNodePoint2.copy(roadwayPointId = roadwayPointId1, nodeNumber = None)), "Test")
      linearLocationDAO.create(Seq(testLinearLocation1.copy(roadwayNumber = roadwayNumber)))
      val nodePoints = dao.fetchTemplatesByBoundingBox(BoundingRectangle(Point(98, 98), Point(102, 102)))
      nodePoints.size should be(2)
      nodePoints.count(n => n.roadwayNumber == roadwayNumber) should be(2)
      nodePoints.count(n => n.addrM == testRoadwayPoint1.addrMValue) should be(2)
      nodePoints.count(n => n.createdBy.contains("Test")) should be(2)
    }
  }

  test("Test expireById When two templates created and one expired Then expire one and keep the other") {
    runWithRollback {
      val newRoadwayNumber = Sequences.nextRoadwayNumber
      val roadway = Roadway(NewIdValue, newRoadwayNumber, 1, 2, RoadType.PublicRoad, Track.Combined, Discontinuity.Continuous, 0L, 10L, reversed = false, DateTime.now, None, "user", None, 8L, TerminationCode.NoTermination, DateTime.now, None)
      roadwayDAO.create(Seq(roadway))
      val roadwayPointId1 = roadwayPointDAO.create(testRoadwayPoint1.copy(roadwayNumber = newRoadwayNumber))
      val ids = dao.create(Seq(testNodePoint1.copy(roadwayPointId = roadwayPointId1),
        testNodePoint2.copy(roadwayPointId = roadwayPointId1)))
      val fetchedBefore = dao.fetchByIds(ids)
      fetchedBefore.size should be(2)
      dao.expireById(Seq(ids.head))
      val fetched = dao.fetchByIds(ids)
      fetched.size should be(1)
      fetched.head.id should be(ids.last)
      fetched.head.nodeNumber should be(None)
    }
  }

  test("Test expireById When two created and one expired Then expire one and keep the other") {
    runWithRollback {
      val newRoadwayNumber = Sequences.nextRoadwayNumber
      val roadway = Roadway(NewIdValue, newRoadwayNumber, 1, 2, RoadType.PublicRoad, Track.Combined, Discontinuity.Continuous, 0L, 10L, reversed = false, DateTime.now, None, "user", None, 8L, TerminationCode.NoTermination, DateTime.now, None)
      roadwayDAO.create(Seq(roadway))
      val nodeNumber = nodeDAO.create(Seq(testNode1)).head
      val roadwayPointId1 = roadwayPointDAO.create(testRoadwayPoint1.copy(roadwayNumber = newRoadwayNumber))
      val ids = dao.create(Seq(testNodePoint1.copy(roadwayPointId = roadwayPointId1, nodeNumber = Some(nodeNumber)),
        testNodePoint2.copy(roadwayPointId = roadwayPointId1, nodeNumber = Some(nodeNumber))))
      val fetchedBefore = dao.fetchByIds(ids)
      fetchedBefore.size should be(2)
      dao.expireById(Seq(ids.head))
      val fetched = dao.fetchByIds(ids)
      fetched.size should be(1)
      fetched.head.id should be(ids.last)
    }
  }
}

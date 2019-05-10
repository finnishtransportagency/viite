package fi.liikennevirasto.viite.dao

import fi.liikennevirasto.digiroad2.Point
import fi.liikennevirasto.digiroad2.oracle.OracleDatabase
import fi.liikennevirasto.viite.NewIdValue
import org.joda.time.DateTime
import org.scalatest.{FunSuite, Matchers}
import slick.driver.JdbcDriver.backend.Database
import slick.driver.JdbcDriver.backend.Database.dynamicSession
import fi.liikennevirasto.digiroad2.dao.Sequences

class NodePointDAOSpec extends FunSuite with Matchers {

  def runWithRollback(f: => Unit): Unit = {
    Database.forDataSource(OracleDatabase.ds).withDynTransaction {
      f
      dynamicSession.rollback()
    }
  }

  val dao = new NodePointDAO
  val nodeDAO = new NodeDAO
  val roadwayPointDAO = new RoadwayPointDAO

  val testRoadwayPoint1 = RoadwayPoint(NewIdValue, -1, 10, "Test", None, None, None)

  val testNodePoint1 = NodePoint(NewIdValue, BeforeAfter.Before, -1, None,
    DateTime.parse("2019-01-01"), None, DateTime.parse("2019-01-01"), None, Some("Test"), None, -1, 10)
  val testNodePoint2 = NodePoint(NewIdValue, BeforeAfter.After, -1, None,
    DateTime.parse("2019-01-01"), None, DateTime.parse("2019-01-01"), None, Some("Test"), None, -1, 10)

  val testNode1 = Node(NewIdValue, NewIdValue, Point(100, 100), Some("Test node 1"), NodeType.NormalIntersection,
    DateTime.parse("2019-01-01"), None, DateTime.parse("2019-01-01"), None, Some("Test"), None)

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
      val nodeId = nodeDAO.create(Seq(testNode1)).head
      dao.create(Seq(testNodePoint1.copy(roadwayPointId = roadwayPointId1, nodeId = Some(nodeId)),
        testNodePoint2.copy(roadwayPointId = roadwayPointId1, nodeId = Some(nodeId))))
      val nodePoints = dao.fetchNodePointsByNodeId(Seq(-1))
      nodePoints.isEmpty should be(true)
    }
  }

  test("Test fetchNodePointsByNodeId When existing nodeId Then return node points") {
    runWithRollback {
      val roadwayPointId1 = roadwayPointDAO.create(testRoadwayPoint1.copy(roadwayNumber = Sequences.nextRoadwayNumber))
      val nodeId = nodeDAO.create(Seq(testNode1)).head
      dao.create(Seq(testNodePoint1.copy(roadwayPointId = roadwayPointId1, nodeId = Some(nodeId)),
        testNodePoint2.copy(roadwayPointId = roadwayPointId1, nodeId = Some(nodeId))))
      val nodePoints = dao.fetchNodePointsByNodeId(Seq(nodeId))
      nodePoints.size should be(2)
    }
  }

}

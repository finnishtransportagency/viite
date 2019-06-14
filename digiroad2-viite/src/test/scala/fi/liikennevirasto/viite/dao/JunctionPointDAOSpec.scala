package fi.liikennevirasto.viite.dao

import fi.liikennevirasto.digiroad2.dao.Sequences
import fi.liikennevirasto.digiroad2.oracle.OracleDatabase
import fi.liikennevirasto.viite.NewIdValue
import org.joda.time.DateTime
import org.scalatest.{FunSuite, Matchers}
import slick.driver.JdbcDriver.backend.Database
import slick.driver.JdbcDriver.backend.Database.dynamicSession

class JunctionPointDAOSpec extends FunSuite with Matchers {

  def runWithRollback(f: => Unit): Unit = {
    Database.forDataSource(OracleDatabase.ds).withDynTransaction {
      f
      dynamicSession.rollback()
    }
  }

  val dao = new JunctionPointDAO
  val junctionDAO = new JunctionDAO
  val roadwayPointDAO = new RoadwayPointDAO

  val testRoadwayPoint1 = RoadwayPoint(NewIdValue, -1, 10, "Test", None, None, None)

  val testJunctionPoint1 = JunctionPoint(NewIdValue, BeforeAfter.Before, -1, -1,
    DateTime.parse("2019-01-01"), None, DateTime.parse("2019-01-01"), None, None, None, -1, 10)
  val testJunctionPoint2 = JunctionPoint(NewIdValue, BeforeAfter.After, -1, -1,
    DateTime.parse("2019-01-01"), None, DateTime.parse("2019-01-01"), None, None, None, -1, 10)

  val testJunction1 = Junction(NewIdValue, -1, None, DateTime.parse("2019-01-01"), None,
    DateTime.parse("2019-01-01"), None, None, None)

  test("Test create When nothing to create Then return empty Seq") {
    runWithRollback {
      val ids = dao.create(Seq())
      ids.isEmpty should be(true)
    }
  }

  test("Test create When one created Then return Seq with one id") {
    runWithRollback {
      val roadwayPointId = roadwayPointDAO.create(testRoadwayPoint1.copy(roadwayNumber = Sequences.nextRoadwayNumber))
      val junctionId = junctionDAO.create(Seq(testJunction1)).head
      val ids = dao.create(Seq(testJunctionPoint1.copy(junctionId = junctionId, roadwayPointId = roadwayPointId)))
      ids.size should be(1)
    }
  }

  test("Test create When two created Then return Seq with two ids") {
    runWithRollback {
      val roadwayPointId1 = roadwayPointDAO.create(testRoadwayPoint1.copy(roadwayNumber = Sequences.nextRoadwayNumber))
      val junctionId = junctionDAO.create(Seq(testJunction1)).head
      val ids = dao.create(Seq(testJunctionPoint1.copy(junctionId = junctionId, roadwayPointId = roadwayPointId1),
        testJunctionPoint2.copy(junctionId = junctionId, roadwayPointId = roadwayPointId1)))
      ids.size should be(2)
    }
  }

  test("Test fetchJunctionPointsByJunctionIds When non-existing junctionId Then return empty Seq") {
    runWithRollback {
      val roadwayPointId1 = roadwayPointDAO.create(testRoadwayPoint1.copy(roadwayNumber = Sequences.nextRoadwayNumber))
      val junctionId = junctionDAO.create(Seq(testJunction1)).head
      dao.create(Seq(testJunctionPoint1.copy(roadwayPointId = roadwayPointId1, junctionId = junctionId),
        testJunctionPoint2.copy(roadwayPointId = roadwayPointId1, junctionId = junctionId)))
      val junctionPoints = dao.fetchJunctionPointsByJunctionIds(Seq(-1))
      junctionPoints.isEmpty should be(true)
    }
  }

  test("Test fetchJunctionPointsByJunctionIds When existing junctionId Then return junction points") {
    runWithRollback {
      val roadwayPointId1 = roadwayPointDAO.create(testRoadwayPoint1.copy(roadwayNumber = Sequences.nextRoadwayNumber))
      val junctionId = junctionDAO.create(Seq(testJunction1)).head
      dao.create(Seq(testJunctionPoint1.copy(roadwayPointId = roadwayPointId1, junctionId = junctionId),
        testJunctionPoint2.copy(roadwayPointId = roadwayPointId1, junctionId = junctionId)))
      val junctionPoints = dao.fetchJunctionPointsByJunctionIds(Seq(junctionId))
      junctionPoints.size should be(2)
      junctionPoints.filter(jp => jp.beforeAfter == testJunctionPoint1.beforeAfter).head.addrM should be(testJunctionPoint1.addrM)
      junctionPoints.filter(jp => jp.beforeAfter == testJunctionPoint2.beforeAfter).head.addrM should be(testJunctionPoint2.addrM)
    }
  }

}

package fi.liikennevirasto.viite.dao

import fi.liikennevirasto.viite.NewIdValue
import fi.vaylavirasto.viite.dao.Sequences
import fi.vaylavirasto.viite.model.{AdministrativeClass, BeforeAfter, Discontinuity, Track}
import fi.vaylavirasto.viite.postgis.PostGISDatabase.runWithRollback
import org.joda.time.DateTime
import org.scalatest.{FunSuite, Matchers}

class JunctionPointDAOSpec extends FunSuite with Matchers {

  val dao = new JunctionPointDAO
  val junctionDAO = new JunctionDAO
  val roadwayDAO = new RoadwayDAO
  val roadwayPointDAO = new RoadwayPointDAO
  val nodeDAO = new NodeDAO

  private val testRoadwayPoint1 = RoadwayPoint(NewIdValue, -1, 10, "Test", None, None, None)

  private val testJunctionPoint1 = JunctionPoint(NewIdValue, BeforeAfter.Before, -1, -1, None, None,
    DateTime.parse("2019-01-01"), None, "Test", None, -1, 10, 0, 0, Track.Combined, Discontinuity.Continuous)
  private val testJunctionPoint2 = JunctionPoint(NewIdValue, BeforeAfter.After, -1, -1, None, None,
    DateTime.parse("2019-01-01"), None, "Test", None, -1, 10, 0, 0, Track.Combined, Discontinuity.Continuous)

  private val testJunction1 = Junction(NewIdValue, None, None, DateTime.parse("2019-01-01"), None,
    DateTime.parse("2019-01-01"), None, "Test", None)

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
      val junctionPoints = dao.fetchByJunctionIds(Seq(-1))
      junctionPoints.isEmpty should be(true)
    }
  }

  test("Test fetchJunctionPointsByJunctionIds When existing junctionId Then return junction points") {
    runWithRollback {
      val newRoadwayNumber = Sequences.nextRoadwayNumber
      val roadway = Roadway(NewIdValue, newRoadwayNumber, 1, 2, AdministrativeClass.State, Track.Combined, Discontinuity.Continuous, 0L, 10L, reversed = false, DateTime.now, None, "user", None, 8L, TerminationCode.NoTermination, DateTime.now, None)
      roadwayDAO.create(Seq(roadway))
      val roadwayPointId1 = roadwayPointDAO.create(testRoadwayPoint1.copy(roadwayNumber = newRoadwayNumber))
      val junctionId = junctionDAO.create(Seq(testJunction1)).head
      dao.create(Seq(testJunctionPoint1.copy(roadwayPointId = roadwayPointId1, junctionId = junctionId),
        testJunctionPoint2.copy(roadwayPointId = roadwayPointId1, junctionId = junctionId)))
      val junctionPoints = dao.fetchByJunctionIds(Seq(junctionId))
      junctionPoints.size should be(2)
      junctionPoints.filter(jp => jp.beforeAfter == testJunctionPoint1.beforeAfter).head.addrM should be(testJunctionPoint1.addrM)
      junctionPoints.filter(jp => jp.beforeAfter == testJunctionPoint2.beforeAfter).head.addrM should be(testJunctionPoint2.addrM)
    }
  }

  test("Test expireById When two created and one expired Then expire one and keep the other") {
    runWithRollback {
      val newRoadwayNumber = Sequences.nextRoadwayNumber
      val roadway = Roadway(NewIdValue, newRoadwayNumber, 1, 2, AdministrativeClass.State, Track.Combined, Discontinuity.Continuous, 0L, 10L, reversed = false, DateTime.now, None, "user", None, 8L, TerminationCode.NoTermination, DateTime.now, None)
      roadwayDAO.create(Seq(roadway))
      val roadwayPointId1 = roadwayPointDAO.create(testRoadwayPoint1.copy(roadwayNumber = newRoadwayNumber))
      val junctionId = junctionDAO.create(Seq(testJunction1)).head
      val ids = dao.create(Seq(testJunctionPoint1.copy(roadwayPointId = roadwayPointId1, junctionId = junctionId),
        testJunctionPoint2.copy(roadwayPointId = roadwayPointId1, junctionId = junctionId)))
      dao.expireById(Seq(ids.head))
      val fetched = dao.fetchByIds(ids)
      fetched.size should be(1)
      fetched.head.id should be(ids.last)
    }
  }

}

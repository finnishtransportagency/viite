package fi.liikennevirasto.viite.dao

import fi.liikennevirasto.digiroad2.Point
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
  val roadwayDAO = new RoadwayDAO

  private val roadNumber1 = 990
  private val roadwayNumber1 = 1000000000l
  private val roadPartNumber1 = 1
  val testRoadway1 = Roadway(NewIdValue, roadwayNumber1, roadNumber1, roadPartNumber1, RoadType.PublicRoad, Track.Combined, Discontinuity.Continuous,
    0, 100, reversed = false, DateTime.parse("2000-01-01"), None, "test", Some("TEST ROAD 1"), 1, TerminationCode.NoTermination)

  val testNode1 = Node(NewIdValue, NewIdValue, Point(100, 100), Some("Test node 1"), NodeType.NormalIntersection,
    Some(DateTime.parse("2019-01-01")), None, Some(DateTime.parse("2019-01-01")), None, Some("Test"), None,
    Some(roadNumber1), Some(roadPartNumber1), Some(testRoadway1.track.value), Some(testRoadway1.startAddrMValue))


  test("Test fetchByRoadAttributes When non-existing road number Then return None") {
    runWithRollback {
      val nodes = dao.fetchByRoadAttributes(nonExistingRoadNumber, None, None)
      nodes.isEmpty should be(true)
    }
  }

  test("Test fetchByRoadAttributes When existing road number but no nodes Then return None") {
    runWithRollback {
      roadwayDAO.create(Seq(testRoadway1))
      val nodes = dao.fetchByRoadAttributes(roadNumber1, None, None)
      nodes.isEmpty should be(true)
    }
  }

  test("Test fetchByRoadAttributes When existing road number and valid min road part number but missing max road part number Then throw exception") {
    runWithRollback {
      intercept[IllegalArgumentException] {
        dao.fetchByRoadAttributes(existingRoadNumber, Some(1), None)
      }
    }
  }

  test("Test fetchByRoadAttributes When existing road number but invalid road part number range Then return None") {
    runWithRollback {
      val nodes = dao.fetchByRoadAttributes(existingRoadNumber, Some(2), Some(1))
      nodes.isEmpty should be(true)
    }
  }

  test("Test fetchByRoadAttributes When existing road number but non existing road part number range Then return None") {
    runWithRollback {
      val nodes = dao.fetchByRoadAttributes(existingRoadNumber, Some(nonExistingRoadPartNumber), Some(nonExistingRoadPartNumber))
      nodes.isEmpty should be(true)
    }
  }

  test("Test fetchByRoadAttributes When existing road number with related nodes Then return nodes") {
    runWithRollback {
      roadwayDAO.create(Seq(testRoadway1))
      dao.create(Seq(testNode1))
      val nodes = dao.fetchByRoadAttributes(roadNumber1, None, None)
      nodes.isEmpty should be(false)
    }
  }


}

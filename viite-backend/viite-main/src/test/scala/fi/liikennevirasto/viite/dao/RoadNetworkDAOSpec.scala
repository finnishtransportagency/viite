package fi.liikennevirasto.viite.dao

import fi.liikennevirasto.digiroad2.postgis.PostGISDatabase
import fi.liikennevirasto.viite._
import fi.vaylavirasto.viite.dao.Sequences
import fi.vaylavirasto.viite.geometry.Point
import fi.vaylavirasto.viite.model.{AdministrativeClass, Discontinuity, LinkGeomSource, SideCode, Track}
import org.joda.time.DateTime
import org.scalatest.{FunSuite, Matchers}
import slick.driver.JdbcDriver.backend.Database
import slick.driver.JdbcDriver.backend.Database.dynamicSession


class RoadNetworkDAOSpec extends FunSuite with Matchers {

  def runWithRollback(f: => Unit): Unit = {
    Database.forDataSource(PostGISDatabase.ds).withDynTransaction {
      f
      dynamicSession.rollback()
    }
  }

  val dao = new RoadNetworkDAO
  val roadwayDAO = new RoadwayDAO
  val roadwayPointDAO = new RoadwayPointDAO
  val linearLocationDAO = new LinearLocationDAO

  val roadNetworkValidator = new RoadNetworkValidator

  private val roadNumber1 = 990

  private val roadwayNumber1 = 1000000000L

  private val testRoadway1 = Roadway(NewIdValue, roadwayNumber1, roadNumber1, 1, AdministrativeClass.State, Track.Combined, Discontinuity.Continuous,   0, 100, reversed = false, DateTime.parse("2000-01-01"), None, "test", Some("TEST ROAD 1"), 1, TerminationCode.NoTermination)

  test("Test existence of roadway points from the start and end of the roadway by fetchMissingRoadwayPointsFromStart. Correctly assess both existing, and missing roadway points.") {
    runWithRollback {
      // create roadway without roadway points
      roadwayDAO.create(Seq(testRoadway1))
      // test
      val missingRoadwayPointsFromStart  = dao.fetchMissingRoadwayPointsFromStart(roadNumber1, 1)
      val missingRoadwayPointsFromEnd = dao.fetchMissingRoadwayPointsFromEnd(roadNumber1, 1)
      missingRoadwayPointsFromStart.size should be (1)
      missingRoadwayPointsFromEnd.size should be (1)

      // create roadway points for the roadway
      val roadwayPointStart = RoadwayPoint(Sequences.nextRoadwayPointId, roadwayNumber1, 0, "test", Some(DateTime.now), None, None)
      val roadwayPointEnd = RoadwayPoint(Sequences.nextRoadwayPointId, roadwayNumber1, 100, "test", Some(DateTime.now), None, None)
      roadwayPointDAO.create(roadwayPointStart)
      roadwayPointDAO.create(roadwayPointEnd)

      // test again
      val missingRoadwayPointsFromStart2 = dao.fetchMissingRoadwayPointsFromStart(roadNumber1, 1)
      missingRoadwayPointsFromStart2.size should be (0)
      val missingRoadwayPointsFromEnd2 = dao.fetchMissingRoadwayPointsFromEnd(roadNumber1, 1)
      missingRoadwayPointsFromEnd2.size should be (0)
    }
  }

  test("Test When there are two rows of roadways with same roadway number at the same time period with different address values Then identify them") {
    runWithRollback {
      val roadwayNumber =  Sequences.nextRoadwayNumber
      val roadNumber = 10
      val roadPartNumber = 1
      val roadway1 = Roadway(NewIdValue, roadwayNumber, roadNumber, roadPartNumber,  AdministrativeClass.State, Track.Combined, Discontinuity.Continuous, 0, 100, reversed = false, DateTime.parse("2000-01-01"), None, "test", Some("TEST ROAD 1"), 1, TerminationCode.NoTermination)
      val roadway2 = Roadway(NewIdValue, roadwayNumber, roadNumber, roadPartNumber,  AdministrativeClass.State, Track.Combined, Discontinuity.Continuous, 150, 200, reversed = false, DateTime.parse("2000-01-01"), None, "test", Some("TEST ROAD 1"), 1, TerminationCode.NoTermination)

      roadwayDAO.create(Seq(roadway1, roadway2))

      val res = dao.fetchInvalidRoadwayLengths(roadNumber, roadPartNumber)
      res.length should be (2)
    }
  }

  test("Test When there are overlapping roadway rows Then Identify them") {
    runWithRollback {
      val roadNumber = 10
      val roadPartNumber = 1
      val roadway1 = Roadway(NewIdValue, Sequences.nextRoadwayNumber, roadNumber, roadPartNumber, AdministrativeClass.State, Track.Combined, Discontinuity.Continuous, 1069, 6890, reversed = false, DateTime.parse("1965-01-01"), Some(DateTime.parse("2008-11-14")), "test", Some("TEST ROAD 1"), 1, TerminationCode.NoTermination)
      val roadway2 = Roadway(NewIdValue, Sequences.nextRoadwayNumber, roadNumber, roadPartNumber, AdministrativeClass.State, Track.RightSide, Discontinuity.Continuous, 5390, 6265, reversed = false, DateTime.parse("2008-01-28"), Some(DateTime.parse("2009-01-27")), "test", Some("TEST ROAD 1"), 1, TerminationCode.NoTermination)

      roadwayDAO.create(Seq(roadway1, roadway2))

      val res = dao.fetchOverlappingRoadwaysInHistory(roadNumber, roadPartNumber)
      res.size should be (2)
    }
  }

  //TODO better name for this test (when the case class and query gets better name)
  test("Test When there are overlapping roadways on linear locations Then identify them") {
    runWithRollback {
      val roadNumber = 10
      val roadPartNumber = 1
      val roadNumber2 = 40000
      val roadwayNumber = Sequences.nextRoadwayNumber
      val roadwayNumber3 = Sequences.nextRoadwayNumber

      val linearLocation1 = LinearLocation(Sequences.nextLinearLocationId, 4, 1000L.toString, 0.0, 288.0,SideCode.TowardsDigitizing,10000000000L,(CalibrationPointReference(Some(0L)), CalibrationPointReference.None),Seq(Point(0.0, 0.0), Point(0.0, 288.0)), LinkGeomSource.NormalLinkInterface, roadwayNumber)
      val linearLocation2 = LinearLocation(Sequences.nextLinearLocationId, 1, 1000L.toString, 0.0, 288.0,SideCode.TowardsDigitizing,10000000000L,(CalibrationPointReference(Some(0L)), CalibrationPointReference.None),Seq(Point(0.0, 0.0), Point(0.0, 288.0)), LinkGeomSource.NormalLinkInterface, roadwayNumber3)

      linearLocationDAO.create(Seq(linearLocation1, linearLocation2))

      val roadway1 = Roadway(Sequences.nextRoadwayId, roadwayNumber, roadNumber, roadPartNumber, AdministrativeClass.State, Track.Combined, Discontinuity.Continuous, 1000, 1288, reversed = false, DateTime.parse("1965-01-01"), Some(DateTime.parse("2008-11-14")), "test", Some("TEST ROAD 1"), 1, TerminationCode.NoTermination)
      val roadway2 = Roadway(Sequences.nextRoadwayId, roadwayNumber3, roadNumber2, roadPartNumber, AdministrativeClass.State, Track.Combined, Discontinuity.Continuous, 0, 288, reversed = false, DateTime.parse("2022-01-01"), None, "test", Some("TEST ROAD 2"), 1, TerminationCode.NoTermination)

      roadwayDAO.create(Seq(roadway1, roadway2))

      val res = dao.fetchOverlappingRoadwaysOnLinearLocations()
      res.size should be (2)
    }
  }
}

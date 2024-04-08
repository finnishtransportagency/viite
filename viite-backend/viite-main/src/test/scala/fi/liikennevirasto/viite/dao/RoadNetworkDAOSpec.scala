package fi.liikennevirasto.viite.dao

import fi.liikennevirasto.viite._
import fi.vaylavirasto.viite.dao.Sequences
import fi.vaylavirasto.viite.geometry.Point
import fi.vaylavirasto.viite.model.{AddrMRange, AdministrativeClass, CalibrationPoint, CalibrationPointLocation, CalibrationPointType, Discontinuity, LinkGeomSource, RoadPart, SideCode, Track}
import fi.vaylavirasto.viite.postgis.PostGISDatabase.runWithRollback
import org.joda.time.DateTime
import org.scalatest.{FunSuite, Matchers}


class RoadNetworkDAOSpec extends FunSuite with Matchers {

  val dao = new RoadNetworkDAO
  val roadwayDAO = new RoadwayDAO
  val roadwayPointDAO = new RoadwayPointDAO
  val linearLocationDAO = new LinearLocationDAO

  val roadNetworkValidator = new RoadNetworkValidator

  private val roadPart = RoadPart(990, 1)

  private val roadwayNumber1 = 1000000000L

  private val testRoadway1 = Roadway(NewIdValue, roadwayNumber1, roadPart, AdministrativeClass.State, Track.Combined, Discontinuity.Continuous,   AddrMRange(0, 100), reversed = false, DateTime.parse("2000-01-01"), None, "test", Some("TEST ROAD 1"), 1, TerminationCode.NoTermination)

  test("Test existence of roadway points from the start and end of the roadway by fetchMissingRoadwayPointsFromStart. Correctly assess both existing, and missing roadway points.") {
    runWithRollback {
      // create roadway without roadway points
      roadwayDAO.create(Seq(testRoadway1))
      // test
      val missingRoadwayPointsFromStart  = dao.fetchMissingRoadwayPointsFromStart(roadPart)
      val missingRoadwayPointsFromEnd    = dao.fetchMissingRoadwayPointsFromEnd  (roadPart)
      missingRoadwayPointsFromStart.size should be (1)
      missingRoadwayPointsFromEnd.size should be (1)

      // create roadway points for the roadway
      val roadwayPointStart = RoadwayPoint(Sequences.nextRoadwayPointId, roadwayNumber1, 0, "test", Some(DateTime.now), None, None)
      val roadwayPointEnd = RoadwayPoint(Sequences.nextRoadwayPointId, roadwayNumber1, 100, "test", Some(DateTime.now), None, None)
      roadwayPointDAO.create(roadwayPointStart)
      roadwayPointDAO.create(roadwayPointEnd)

      // test again
      val missingRoadwayPointsFromStart2 = dao.fetchMissingRoadwayPointsFromStart(roadPart)
      missingRoadwayPointsFromStart2.size should be (0)
      val missingRoadwayPointsFromEnd2   = dao.fetchMissingRoadwayPointsFromEnd(roadPart)
      missingRoadwayPointsFromEnd2.size should be (0)
    }
  }

  test("Test When there are two rows of roadways with same roadway number at the same time period with different address values Then identify them") {
    runWithRollback {
      val roadwayNumber =  Sequences.nextRoadwayNumber
      val roadPart = RoadPart(10, 1)
      val roadway1 = Roadway(NewIdValue, roadwayNumber, roadPart,  AdministrativeClass.State, Track.Combined, Discontinuity.Continuous, AddrMRange(  0, 100), reversed = false, DateTime.parse("2000-01-01"), None, "test", Some("TEST ROAD 1"), 1, TerminationCode.NoTermination)
      val roadway2 = Roadway(NewIdValue, roadwayNumber, roadPart,  AdministrativeClass.State, Track.Combined, Discontinuity.Continuous, AddrMRange(150, 200), reversed = false, DateTime.parse("2000-01-01"), None, "test", Some("TEST ROAD 1"), 1, TerminationCode.NoTermination)

      roadwayDAO.create(Seq(roadway1, roadway2))

      val res = dao.fetchInvalidRoadwayLengths(roadPart)
      res.length should be (2)
    }
  }

  test("Test When there are overlapping roadway rows Then Identify them") {
    runWithRollback {
      val roadPart = RoadPart(10, 1)
      val roadway1 = Roadway(NewIdValue, Sequences.nextRoadwayNumber, roadPart, AdministrativeClass.State, Track.Combined,  Discontinuity.Continuous, AddrMRange(1069, 6890), reversed = false, DateTime.parse("1965-01-01"), Some(DateTime.parse("2008-11-14")), "test", Some("TEST ROAD 1"), 1, TerminationCode.NoTermination)
      val roadway2 = Roadway(NewIdValue, Sequences.nextRoadwayNumber, roadPart, AdministrativeClass.State, Track.RightSide, Discontinuity.Continuous, AddrMRange(5390, 6265), reversed = false, DateTime.parse("2008-01-28"), Some(DateTime.parse("2009-01-27")), "test", Some("TEST ROAD 1"), 1, TerminationCode.NoTermination)

      roadwayDAO.create(Seq(roadway1, roadway2))

      val res = dao.fetchOverlappingRoadwaysInHistory(roadPart)
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

      val roadway1 = Roadway(Sequences.nextRoadwayId, roadwayNumber,  RoadPart(roadNumber,  roadPartNumber), AdministrativeClass.State, Track.Combined, Discontinuity.Continuous, AddrMRange(1000, 1288), reversed = false, DateTime.parse("1965-01-01"), Some(DateTime.parse("2008-11-14")), "test", Some("TEST ROAD 1"), 1, TerminationCode.NoTermination)
      val roadway2 = Roadway(Sequences.nextRoadwayId, roadwayNumber3, RoadPart(roadNumber2, roadPartNumber), AdministrativeClass.State, Track.Combined, Discontinuity.Continuous, AddrMRange(   0,  288), reversed = false, DateTime.parse("2022-01-01"), None, "test", Some("TEST ROAD 2"), 1, TerminationCode.NoTermination)

      roadwayDAO.create(Seq(roadway1, roadway2))

      val res = dao.fetchOverlappingRoadwaysOnLinearLocations()
      res.size should be (2)
    }
  }

  test("Test When there are extra calibration points Then identify them") {
    runWithRollback {
      val linkId1 = "testtest-test-test-test-test:1"
      val linkId2 = "testtest-test-test-test-test:2"

      val geometry1 = Seq(Point(0.0, 0.0), Point(0.0,50.0))
      val geometry2 = Seq(Point(0.0, 50.0), Point(0.0, 100.0))
      val geometry3 = Seq(Point(0.0, 100.0), Point(0.0, 150.0))
      val geometry4 = Seq(Point(0.0, 150.0), Point(0.0, 200.0))

      val roadwayNumber1 = Sequences.nextRoadwayNumber
      val roadwayNumber2 = Sequences.nextRoadwayNumber
      val roadwayNumber3 = Sequences.nextRoadwayNumber

      val roadPart = RoadPart(18344, 1)

      // Create Roadways with different roadway numbers
      roadwayDAO.create(Seq(
        Roadway(Sequences.nextRoadwayId, roadwayNumber1, roadPart, AdministrativeClass.State, Track.Combined,Discontinuity.Continuous, AddrMRange(  0, 100), false, DateTime.now().minusDays(1), None, "test", Some("Test road"), 9,TerminationCode.NoTermination, DateTime.now().minusDays(1),None),
        Roadway(Sequences.nextRoadwayId, roadwayNumber2, roadPart, AdministrativeClass.State, Track.Combined,Discontinuity.Continuous, AddrMRange(100, 150), false, DateTime.now().minusDays(1), None, "test", Some("Test road"), 9,TerminationCode.NoTermination, DateTime.now().minusDays(1),None),
        Roadway(Sequences.nextRoadwayId, roadwayNumber3, roadPart, AdministrativeClass.State, Track.Combined,Discontinuity.EndOfRoad,  AddrMRange(150, 200), false, DateTime.now().minusDays(1), None, "test", Some("Test road"), 9,TerminationCode.NoTermination, DateTime.now().minusDays(1),None)
      ))

      // Create LinearLocations
      val linearLocationIds = linearLocationDAO.create(Seq(
        // First 2 with same roadway number and linkId
        LinearLocation(Sequences.nextLinearLocationId, 1.0,
          linkId1, 0.0, 50.0,
          SideCode.TowardsDigitizing, 10000000000L,
          (CalibrationPointReference(Some(0), Some(CalibrationPointType.RoadAddressCP)), CalibrationPointReference(Some(50), Some(CalibrationPointType.RoadAddressCP))),
          geometry1,LinkGeomSource.NormalLinkInterface,
          roadwayNumber1,Some(DateTime.now().minusDays(1)), None),
        LinearLocation(Sequences.nextLinearLocationId, 2.0,
          linkId1, 50.0, 100.0,
          SideCode.TowardsDigitizing, 10000000000L,
          (CalibrationPointReference(Some(50), Some(CalibrationPointType.RoadAddressCP)), CalibrationPointReference(Some(100), Some(CalibrationPointType.RoadAddressCP))),
          geometry2, LinkGeomSource.NormalLinkInterface,
          roadwayNumber1, Some(DateTime.now().minusDays(1)), None),

        // Next 2 with different roadway number but same linkId
        LinearLocation(Sequences.nextLinearLocationId, 3.0,
          linkId2, 100.0, 150.0,
          SideCode.TowardsDigitizing, 10000000000L,
          (CalibrationPointReference(None, None), CalibrationPointReference(Some(150), Some(CalibrationPointType.RoadAddressCP))),
          geometry3,LinkGeomSource.NormalLinkInterface,
          roadwayNumber2,Some(DateTime.now().minusDays(1)), None),
        LinearLocation(Sequences.nextLinearLocationId, 4.0,
          linkId2, 150.0, 200.0,
          SideCode.TowardsDigitizing, 10000000000L,
          (CalibrationPointReference(None, None), CalibrationPointReference(Some(200), Some(CalibrationPointType.RoadAddressCP))),
          geometry4, LinkGeomSource.NormalLinkInterface,
          roadwayNumber3, Some(DateTime.now().minusDays(1)), None)
      ))

      // Create RoadwayPoints
      val roadwayPointId1 = roadwayPointDAO.create(roadwayNumber1, 0, "test")
      val roadwayPointId2 = roadwayPointDAO.create(roadwayNumber1, 50, "test")
      val roadwayPointId3 = roadwayPointDAO.create(roadwayNumber1, 50, "test")
      val roadwayPointId4 = roadwayPointDAO.create(roadwayNumber1, 100, "test")
      val roadwayPointId5 = roadwayPointDAO.create(roadwayNumber2, 100, "test")
      val roadwayPointId6 = roadwayPointDAO.create(roadwayNumber2, 150, "test")
      val roadwayPointId7 = roadwayPointDAO.create(roadwayNumber3, 200, "test")

      // Create CalibrationPoints
      CalibrationPointDAO.create(CalibrationPoint(Sequences.nextCalibrationPointId, roadwayPointId1, linkId1, roadwayNumber1, 0,
        CalibrationPointLocation.StartOfLink, CalibrationPointType.RoadAddressCP,
        Some(DateTime.now().minusDays(1)), None, "Test", Some(DateTime.now())))
      CalibrationPointDAO.create(CalibrationPoint(Sequences.nextCalibrationPointId, roadwayPointId2, linkId1, roadwayNumber1, 50,
        CalibrationPointLocation.EndOfLink, CalibrationPointType.RoadAddressCP,
        Some(DateTime.now().minusDays(1)), None, "Test", Some(DateTime.now())))
      CalibrationPointDAO.create(CalibrationPoint(Sequences.nextCalibrationPointId, roadwayPointId3, linkId1, roadwayNumber1, 50,
        CalibrationPointLocation.StartOfLink, CalibrationPointType.RoadAddressCP,
        Some(DateTime.now().minusDays(1)), None, "Test", Some(DateTime.now())))
      CalibrationPointDAO.create(CalibrationPoint(Sequences.nextCalibrationPointId, roadwayPointId4, linkId1, roadwayNumber1, 100,
        CalibrationPointLocation.EndOfLink, CalibrationPointType.RoadAddressCP,
        Some(DateTime.now().minusDays(1)), None, "Test", Some(DateTime.now())))
      CalibrationPointDAO.create(CalibrationPoint(Sequences.nextCalibrationPointId, roadwayPointId5, linkId2, roadwayNumber1, 100,
        CalibrationPointLocation.StartOfLink, CalibrationPointType.RoadAddressCP,
        Some(DateTime.now().minusDays(1)), None, "Test", Some(DateTime.now())))
      CalibrationPointDAO.create(CalibrationPoint(Sequences.nextCalibrationPointId, roadwayPointId6, linkId2, roadwayNumber2, 150,
        CalibrationPointLocation.EndOfLink, CalibrationPointType.RoadAddressCP,
        Some(DateTime.now().minusDays(1)), None, "Test", Some(DateTime.now())))
      CalibrationPointDAO.create(CalibrationPoint(Sequences.nextCalibrationPointId, roadwayPointId7, linkId2, roadwayNumber2, 200,
        CalibrationPointLocation.EndOfLink, CalibrationPointType.RoadAddressCP,
        Some(DateTime.now().minusDays(1)), None, "Test", Some(DateTime.now())))

      // Test all three queries for links with extra calibration points
      val linksWithExtraCalPointsOnSameRoadway = dao.fetchLinksWithExtraCalibrationPointsWithSameRoadwayNumber()
      linksWithExtraCalPointsOnSameRoadway.exists(_.linkId == linkId1) shouldBe true
      linksWithExtraCalPointsOnSameRoadway.map(_.linkId) should not contain (linkId2)

      val allLinksWithExtraCalPoints = dao.fetchLinksWithExtraCalibrationPoints()
      allLinksWithExtraCalPoints.map(_.linkId) should contain allOf (linkId1, linkId2)

      val linksWithExtraCalibrationPointsByRoadPart = dao.fetchLinksWithExtraCalibrationPointsByRoadPart(roadPart)
      linksWithExtraCalibrationPointsByRoadPart.map(_.linkId) should contain allOf (linkId1, linkId2)

    }
  }

}

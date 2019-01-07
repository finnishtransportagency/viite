package fi.liikennevirasto.viite.dao

import java.sql.SQLException

import fi.liikennevirasto.digiroad2.Point
import fi.liikennevirasto.digiroad2.asset.{LinkGeomSource, SideCode}
import fi.liikennevirasto.digiroad2.dao.Sequences
import fi.liikennevirasto.digiroad2.oracle.OracleDatabase
import fi.liikennevirasto.digiroad2.util.Track
import fi.liikennevirasto.viite.AddressConsistencyValidator.AddressError
import fi.liikennevirasto.viite.{RoadType, _}
import org.joda.time.DateTime
import org.scalatest.{FunSuite, Matchers}
import slick.driver.JdbcDriver.backend.Database
import slick.driver.JdbcDriver.backend.Database.dynamicSession


class RoadwayDAOSpec extends FunSuite with Matchers {

  def runWithRollback(f: => Unit): Unit = {
    Database.forDataSource(OracleDatabase.ds).withDynTransaction {
      f
      dynamicSession.rollback()
    }
  }

  val dao = new RoadwayDAO
  val linearLocationDAO = new LinearLocationDAO

  private val nonExistingRoadNumber = -9999
  private val nonExistingRoadPartNumber = -9999
  private val roadNumber1 = 990
  private val roadNumber2 = 993
  private val roadPartNumber1 = 1
  private val roadPartNumber2 = 2

  private val nonExistingRoadwayId = -9999l
  private val nonExistingRoadwayNumber = -9999l
  private val roadwayNumber1 = 1000000000l
  private val roadwayNumber2 = 2000000000l
  private val roadwayNumber3 = 3000000000l

  val testRoadway1 = Roadway(NewRoadway, roadwayNumber1, roadNumber1, roadPartNumber1, RoadType.PublicRoad, Track.Combined, Discontinuity.Continuous,
    0, 100, false, DateTime.parse("2000-01-01"), None, "test", Some("TEST ROAD 1"), 1, TerminationCode.NoTermination)

  val testRoadway2 = Roadway(NewRoadway, roadwayNumber2, roadNumber1, roadPartNumber2, RoadType.PublicRoad, Track.Combined, Discontinuity.Continuous,
    100, 200, false, DateTime.parse("2000-01-01"), None, "test", Some("TEST ROAD 1"), 1, TerminationCode.NoTermination)

  val testRoadway3 = Roadway(NewRoadway, roadwayNumber3, roadNumber2, roadPartNumber1, RoadType.PublicRoad, Track.Combined, Discontinuity.Continuous,
    0, 100, false, DateTime.parse("2000-01-01"), None, "test", Some("TEST ROAD 2"), 1, TerminationCode.NoTermination)

  val testLinearLocation1 = LinearLocation(NewLinearLocation, 1, 1000l, 0.0, 100.0, SideCode.TowardsDigitizing, 10000000000l,
    (Some(0l), None), FloatingReason.NoFloating, Seq(Point(0.0, 0.0), Point(0.0, 100.0)), LinkGeomSource.NormalLinkInterface,
    roadwayNumber1)

  // fetchByRoadwayNumber

  test("Test fetchByRoadwayNumber When non-existing roadway number Then return None") {
    runWithRollback {
      dao.create(List(testRoadway1, testRoadway2))
      dao.fetchByRoadwayNumber(nonExistingRoadwayNumber) should be(None)
    }
  }

  test("Test fetchByRoadwayNumber When existing roadway number Then return the current roadway") {
    runWithRollback {
      val roadways = List(testRoadway1, testRoadway2, testRoadway3)
      dao.create(roadways)
      roadways.foreach(r =>
        dao.fetchByRoadwayNumber(r.roadwayNumber).getOrElse(fail()).roadwayNumber should be(r.roadwayNumber))
    }
  }

  test("Test fetchByRoadwayNumber When existing roadway number Then return only the current roadway") {
    runWithRollback {
      dao.create(List(
        testRoadway1,
        testRoadway2.copy(roadwayNumber = roadwayNumber1, endDate = Some(DateTime.parse("2000-12-31")))
      ))
      val roadway = dao.fetchByRoadwayNumber(roadwayNumber1).getOrElse(fail())
      roadway.endDate should be(None)
      roadway.validTo should be(None)
    }
  }

  // fetchAllBySection

  test("Test fetchAllBySection When non-existing road number Then return None") {
    runWithRollback {
      dao.create(List(testRoadway1, testRoadway2))
      dao.fetchAllBySection(nonExistingRoadNumber, 1).size should be(0)
    }
  }

  test("Test fetchAllBySection When existing road number non-existing road part number Then return None") {
    runWithRollback {
      dao.create(List(testRoadway1, testRoadway2))
      dao.fetchAllBySection(roadNumber1, -9999).size should be(0)
    }
  }

  test("Test fetchAllBySection When existing road number and road part number Then return roadways") {
    runWithRollback {
      dao.create(List(testRoadway1, testRoadway2.copy(roadPartNumber = 1), testRoadway3))
      val roadways = dao.fetchAllBySection(roadNumber1, 1)
      roadways.filter(r => r.roadwayNumber == roadwayNumber1).size should be(1)
      roadways.filter(r => r.roadwayNumber == roadwayNumber2).size should be(1)
      roadways.size should be(2)
    }
  }

  // fetchAllBySectionAndTracks

  test("Test fetchAllBySectionAndTracks When empty tracks Then return None") {
    runWithRollback {
      dao.create(List(testRoadway1))
      dao.fetchAllBySectionAndTracks(roadNumber1, 1, Set()).size should be(0)
    }
  }

  test("Test fetchAllBySectionAndTracks When non-existing road number Then return None") {
    runWithRollback {
      dao.create(List(testRoadway1, testRoadway2))
      dao.fetchAllBySectionAndTracks(nonExistingRoadNumber, 1, Set(Track.Combined)).size should be(0)
    }
  }

  test("Test fetchAllBySectionAndTracks When existing road number non-existing road part number Then return None") {
    runWithRollback {
      dao.create(List(testRoadway1, testRoadway2))
      dao.fetchAllBySectionAndTracks(roadNumber1, -9999, Set(Track.Combined)).size should be(0)
    }
  }

  test("Test fetchAllBySectionAndTracks When existing road number and road part number Then return roadways") {
    runWithRollback {
      dao.create(List(testRoadway1, testRoadway2.copy(roadPartNumber = 1), testRoadway3))
      val roadways = dao.fetchAllBySectionAndTracks(roadNumber1, 1, Set(Track.Combined))
      roadways.filter(r => r.roadwayNumber == roadwayNumber1).size should be(1)
      roadways.filter(r => r.roadwayNumber == roadwayNumber2).size should be(1)
      roadways.size should be(2)
    }
  }

  // fetchAllBySectionsAndTracks

  test("Test fetchAllBySectionsAndTracks When non-existing road number Then return None") {
    runWithRollback {
      dao.create(List(testRoadway1, testRoadway2))
      dao.fetchAllBySectionsAndTracks(nonExistingRoadNumber, Set(1l, 2l, 3l), Set(Track.Combined)).size should be(0)
    }
  }

  test("Test fetchAllBySectionsAndTracks When existing road number empty road part numbers Then return None") {
    runWithRollback {
      dao.create(List(testRoadway1, testRoadway2))
      dao.fetchAllBySectionsAndTracks(roadNumber1, Set(), Set(Track.Combined)).size should be(0)
    }
  }

  test("Test fetchAllBySectionsAndTracks When existing road number non-existing road part numbers Then return None") {
    runWithRollback {
      dao.create(List(testRoadway1, testRoadway2))
      dao.fetchAllBySectionsAndTracks(roadNumber1, Set(-9999l), Set(Track.Combined)).size should be(0)
    }
  }

  test("Test fetchAllBySectionsAndTracks When existing road number and road part numbers Then return roadways") {
    runWithRollback {
      dao.create(List(testRoadway1, testRoadway2, testRoadway3))
      val roadways = dao.fetchAllBySectionsAndTracks(roadNumber1, Set(1l, 2l), Set(Track.Combined))
      roadways.filter(r => r.roadwayNumber == roadwayNumber1).size should be(1)
      roadways.filter(r => r.roadwayNumber == roadwayNumber2).size should be(1)
      roadways.size should be(2)
    }
  }

  test("Test fetchAllBySectionsAndTracks When existing road number and existing and non-existing road part numbers Then return roadways") {
    runWithRollback {
      dao.create(List(testRoadway1, testRoadway2, testRoadway3))
      val roadways = dao.fetchAllBySectionsAndTracks(roadNumber1, Set(1l, 2l, -1l, -2l), Set(Track.Combined))
      roadways.filter(r => r.roadwayNumber == roadwayNumber1).size should be(1)
      roadways.filter(r => r.roadwayNumber == roadwayNumber2).size should be(1)
      roadways.size should be(2)
    }
  }

  test("Test fetchAllBySectionsAndTracks When existing road number and road part numbers and multiple tracks Then return roadways") {
    runWithRollback {
      dao.create(List(testRoadway1.copy(track = Track.LeftSide), testRoadway2.copy(track = Track.RightSide), testRoadway3))
      val roadways = dao.fetchAllBySectionsAndTracks(roadNumber1, Set(1l, 2l), Set(Track.LeftSide, Track.RightSide))
      roadways.filter(r => r.roadwayNumber == roadwayNumber1).size should be(1)
      roadways.filter(r => r.roadwayNumber == roadwayNumber2).size should be(1)
      roadways.size should be(2)
    }
  }

  // fetchAllByRoadAndPart

  test("Test fetchAllByRoadAndPart When non-existing road number Then return None") {
    runWithRollback {
      dao.create(List(testRoadway1))
      dao.fetchAllByRoadAndPart(nonExistingRoadNumber, testRoadway1.roadPartNumber).size should be(0)
    }
  }

  test("Test fetchAllByRoadAndPart When non-existing road part number Then return None") {
    runWithRollback {
      dao.create(List(testRoadway1))
      dao.fetchAllByRoadAndPart(roadNumber1, nonExistingRoadPartNumber).size should be(0)
    }
  }

  test("Test fetchAllByRoadAndPart When existing road and road part number Then return roadways") {
    runWithRollback {
      dao.create(List(testRoadway1, testRoadway2.copy(roadPartNumber = roadPartNumber1), testRoadway3))
      val roadways = dao.fetchAllByRoadAndPart(roadNumber1, roadPartNumber1)
      roadways.filter(r => r.roadwayNumber == roadwayNumber1).size should be(1)
      roadways.filter(r => r.roadwayNumber == roadwayNumber2).size should be(1)
      roadways.size should be(2)
    }
  }

  test("Test fetchAllByRoadAndPart When existing road and road part number with history Then return roadways") {
    runWithRollback {
      dao.create(List(testRoadway1, testRoadway2.copy(roadPartNumber = roadPartNumber1, endDate = Some(DateTime.now())), testRoadway3))
      val roadwaysWithoutHistory = dao.fetchAllByRoadAndPart(roadNumber1, roadPartNumber1, withHistory = false)
      roadwaysWithoutHistory.filter(r => r.roadwayNumber == roadwayNumber1).size should be(1)
      roadwaysWithoutHistory.size should be(1)
      val roadways = dao.fetchAllByRoadAndPart(roadNumber1, roadPartNumber1, withHistory = true)
      roadways.filter(r => r.roadwayNumber == roadwayNumber1).size should be(1)
      roadways.filter(r => r.roadwayNumber == roadwayNumber2).size should be(1)
      roadways.size should be(2)
    }
  }

  test("Test fetchAllByRoadAndPart When existing road and road part number and terminated Then return roadways") {
    runWithRollback {
      dao.create(List(testRoadway1, testRoadway2.copy(roadPartNumber = roadPartNumber1, endDate = Some(DateTime.now()),
        terminated = TerminationCode.Termination), testRoadway3))
      val roadwaysWithoutHistory = dao.fetchAllByRoadAndPart(roadNumber1, roadPartNumber1, withHistory = false)
      roadwaysWithoutHistory.filter(r => r.roadwayNumber == roadwayNumber1).size should be(1)
      roadwaysWithoutHistory.size should be(1)
      val roadways = dao.fetchAllByRoadAndPart(roadNumber1, roadPartNumber1, withHistory = true)
      roadways.filter(r => r.roadwayNumber == roadwayNumber1).size should be(1)
      roadways.filter(r => r.roadwayNumber == roadwayNumber2).size should be(1)
      roadways.size should be(2)
    }
  }

  // fetchAllByRoadAndTracks

  test("Test fetchAllByRoadAndTracks When non-existing road number Then return None") {
    runWithRollback {
      dao.create(List(testRoadway1))
      dao.fetchAllByRoadAndTracks(nonExistingRoadNumber, Set(Track.Combined)).size should be(0)
    }
  }

  test("Test fetchAllByRoadAndTracks When existing road number and empty track Then return None") {
    runWithRollback {
      dao.create(List(testRoadway1))
      dao.fetchAllByRoadAndTracks(roadNumber1, Set()).size should be(0)
    }
  }

  test("Test fetchAllByRoadAndTracks When existing road number and non-existing track Then return None") {
    runWithRollback {
      dao.create(List(testRoadway1))
      dao.fetchAllByRoadAndTracks(roadNumber1, Set(Track.Unknown)).size should be(0)
    }
  }

  test("Test fetchAllByRoadAndTracks When existing road number and track Then return roadways") {
    runWithRollback {
      dao.create(List(testRoadway1, testRoadway2.copy(roadPartNumber = 1), testRoadway3))
      val roadways = dao.fetchAllByRoadAndTracks(roadNumber1, Set(Track.Combined))
      roadways.filter(r => r.roadwayNumber == roadwayNumber1).size should be(1)
      roadways.filter(r => r.roadwayNumber == roadwayNumber2).size should be(1)
      roadways.size should be(2)
    }
  }

  // fetchAllByRoadwayId

  test("Test fetchAllByRoadwayId When empty roadway ids Then return None") {
    runWithRollback {
      dao.create(List(testRoadway1))
      dao.fetchAllByRoadwayId(Seq()).size should be(0)
    }
  }

  test("Test fetchAllByRoadwayId When non-existing roadway ids Then return None") {
    runWithRollback {
      dao.create(List(testRoadway1, testRoadway2))
      dao.fetchAllByRoadwayId(Seq(nonExistingRoadwayId)).size should be(0)
    }
  }

  test("Test fetchAllByRoadwayId When existing roadway ids Then return the current roadways") {
    runWithRollback {
      val roadwayId1 = dao.getNextRoadwayId
      val roadwayId2 = dao.getNextRoadwayId
      dao.create(List(testRoadway1.copy(id = roadwayId1), testRoadway2.copy(id = roadwayId2), testRoadway2.copy(endDate = Some(DateTime.parse("2001-12-31"))), testRoadway3))
      val roadways = dao.fetchAllByRoadwayId(Seq(roadwayId1, roadwayId2))
      roadways.filter(r => r.roadwayNumber == roadwayNumber1).size should be(1)
      roadways.filter(r => r.roadwayNumber == roadwayNumber2).size should be(1)
      roadways.filter(r => r.roadwayNumber == roadwayNumber2).head.endDate should be(None)
      roadways.size should be(2)
    }
  }

  // fetchAllByRoadwayNumbers

  test("Test fetchAllByRoadwayNumbers When empty roadway numbers Then return None") {
    runWithRollback {
      dao.create(List(testRoadway1))
      dao.fetchAllByRoadwayNumbers(Set()).size should be(0)
    }
  }

  test("Test fetchAllByRoadwayNumbers When non-existing roadway numbers Then return None") {
    runWithRollback {
      dao.create(List(testRoadway1, testRoadway2))
      dao.fetchAllByRoadwayNumbers(Set(nonExistingRoadwayNumber)).size should be(0)
    }
  }

  test("Test fetchAllByRoadwayNumbers When existing roadway numbers Then return the current roadways") {
    runWithRollback {
      dao.create(List(testRoadway1, testRoadway2, testRoadway2.copy(endDate = Some(DateTime.parse("2001-12-31"))), testRoadway3))
      val roadways = dao.fetchAllByRoadwayNumbers(Set(roadwayNumber1, roadwayNumber2))
      roadways.filter(r => r.roadwayNumber == roadwayNumber1).size should be(1)
      roadways.filter(r => r.roadwayNumber == roadwayNumber2).size should be(1)
      roadways.filter(r => r.roadwayNumber == roadwayNumber2).head.endDate should be(None)
      roadways.size should be(2)
    }
  }

  test("Test fetchAllByRoadwayNumbers When existing roadway numbers Then return only the current roadways") {
    runWithRollback {
      dao.create(List(
        testRoadway1,
        testRoadway1.copy(startAddrMValue = 100, endAddrMValue = 200, endDate = Some(DateTime.parse("2000-12-31"))),
        testRoadway2
      ))
      val roadways = dao.fetchAllByRoadwayNumbers(Set(roadwayNumber1))
      roadways.size should be(1)
      roadways.head.endDate should be(None)
      roadways.head.validTo should be(None)
    }
  }

  // fetchAllByBetweenRoadNumbers

  test("Test fetchAllByBetweenRoadNumbers When non-existing road numbers Then return None") {
    runWithRollback {
      dao.create(List(testRoadway1, testRoadway2))
      dao.fetchAllByBetweenRoadNumbers((nonExistingRoadNumber - 1, nonExistingRoadNumber)).size should be(0)
    }
  }

  test("Test fetchAllByBetweenRoadNumbers When existing road numbers but bigger number first Then return None") {
    runWithRollback {
      dao.create(List(testRoadway1, testRoadway2, testRoadway3))
      dao.fetchAllByBetweenRoadNumbers(roadNumber2, roadNumber1).size should be(0)
    }
  }

  test("Test fetchAllByBetweenRoadNumbers When existing road numbers Then return roadways") {
    runWithRollback {
      dao.create(List(testRoadway1, testRoadway2, testRoadway3))
      val roadways = dao.fetchAllByBetweenRoadNumbers(roadNumber1, roadNumber2)
      roadways.filter(r => r.roadwayNumber == roadwayNumber1).size should be(1)
      roadways.filter(r => r.roadwayNumber == roadwayNumber2).size should be(1)
      roadways.filter(r => r.roadwayNumber == roadwayNumber3).size should be(1)
      roadways.size should be(3)
    }
  }

  // fetchAllBySectionTrackAndAddresses

  test("Test fetchAllBySectionTrackAndAddresses When non-existing road number Then return None") {
    runWithRollback {
      dao.create(List(testRoadway1, testRoadway2))
      dao.fetchAllBySectionTrackAndAddresses(nonExistingRoadNumber, 1l, Track.Combined, Some(0l), Some(100l)).size should be(0)
    }
  }

  test("Test fetchAllBySectionTrackAndAddresses When non-existing road part numbers Then return None") {
    runWithRollback {
      dao.create(List(testRoadway1, testRoadway2))
      dao.fetchAllBySectionTrackAndAddresses(roadNumber1, -9999l, Track.Combined, Some(0l), Some(100l)).size should be(0)
    }
  }

  test("Test fetchAllBySectionTrackAndAddresses When non-existing track Then return None") {
    runWithRollback {
      dao.create(List(testRoadway1, testRoadway2))
      dao.fetchAllBySectionTrackAndAddresses(roadNumber1, 1l, Track.Unknown, Some(0l), Some(100l)).size should be(0)
    }
  }

  test("Test fetchAllBySectionTrackAndAddresses When non-existing road address Then return None") {
    runWithRollback {
      dao.create(List(testRoadway1, testRoadway2, testRoadway3))
      dao.fetchAllBySectionTrackAndAddresses(roadNumber1, 1l, Track.Combined, Some(1000l), Some(1100l)).size should be(0)
    }
  }

  test("Test fetchAllBySectionTrackAndAddresses When valid values of existing roadways Then return roadways") {
    runWithRollback {
      dao.create(List(testRoadway1, testRoadway2.copy(roadPartNumber = 1l), testRoadway3))
      val roadways = dao.fetchAllBySectionTrackAndAddresses(roadNumber1, 1l, Track.Combined, Some(0l), Some(200l))
      roadways.filter(r => r.roadwayNumber == roadwayNumber1).size should be(1)
      roadways.filter(r => r.roadwayNumber == roadwayNumber2).size should be(1)
      roadways.size should be(2)
    }
  }

  test("Test fetchAllBySectionTrackAndAddresses When valid values of existing roadways no startAddrM Then return roadways") {
    runWithRollback {
      dao.create(List(testRoadway1, testRoadway2.copy(roadPartNumber = 1l), testRoadway3))
      val roadways = dao.fetchAllBySectionTrackAndAddresses(roadNumber1, 1l, Track.Combined, None, Some(100l))
      roadways.filter(r => r.roadwayNumber == roadwayNumber1).size should be(1)
      roadways.filter(r => r.roadwayNumber == roadwayNumber2).size should be(0)
      roadways.size should be(1)
    }
  }

  test("Test fetchAllBySectionTrackAndAddresses When valid values of existing roadways no endAddrM Then return roadways") {
    runWithRollback {
      dao.create(List(testRoadway1, testRoadway2.copy(roadPartNumber = 1l), testRoadway3))
      val roadways = dao.fetchAllBySectionTrackAndAddresses(roadNumber1, 1l, Track.Combined, Some(100l), None)
      roadways.filter(r => r.roadwayNumber == roadwayNumber1).size should be(0)
      roadways.filter(r => r.roadwayNumber == roadwayNumber2).size should be(1)
      roadways.size should be(1)
    }
  }

  test("Test fetchAllBySectionTrackAndAddresses When valid values of existing roadways no startAddrM and no endAddrM Then return roadways") {
    runWithRollback {
      dao.create(List(testRoadway1, testRoadway2.copy(roadPartNumber = 1l), testRoadway3))
      val roadways = dao.fetchAllBySectionTrackAndAddresses(roadNumber1, 1l, Track.Combined, None, None)
      roadways.filter(r => r.roadwayNumber == roadwayNumber1).size should be(1)
      roadways.filter(r => r.roadwayNumber == roadwayNumber2).size should be(1)
      roadways.size should be(2)
    }
  }

  test("Test fetchAllBySectionTrackAndAddresses When existing road number and road part numbers and multiple tracks Then return roadways") {
    runWithRollback {
      dao.create(List(testRoadway1.copy(track = Track.LeftSide), testRoadway2.copy(track = Track.RightSide, roadPartNumber = 1l), testRoadway3))
      val roadways = dao.fetchAllBySectionTrackAndAddresses(roadNumber1, 1l, Track.LeftSide, Some(0l), Some(100l))
      roadways.filter(r => r.roadwayNumber == roadwayNumber1).size should be(1)
      roadways.size should be(1)
    }
  }

  // fetchAllBySectionAndAddresses

  test("Test fetchAllBySectionAndAddresses When non-existing road number Then return None") {
    runWithRollback {
      dao.create(List(testRoadway1, testRoadway2))
      dao.fetchAllBySectionAndAddresses(nonExistingRoadNumber, 1l, Some(0l), Some(100l)).size should be(0)
    }
  }

  test("Test fetchAllBySectionAndAddresses When non-existing road part numbers Then return None") {
    runWithRollback {
      dao.create(List(testRoadway1, testRoadway2))
      dao.fetchAllBySectionAndAddresses(roadNumber1, -9999l, Some(0l), Some(100l)).size should be(0)
    }
  }

  test("Test fetchAllBySectionAndAddresses When non-existing road address Then return None") {
    runWithRollback {
      dao.create(List(testRoadway1, testRoadway2, testRoadway3))
      dao.fetchAllBySectionAndAddresses(roadNumber1, 1l, Some(1000l), Some(1100l)).size should be(0)
    }
  }

  test("Test fetchAllBySectionAndAddresses When valid values of existing roadways Then return roadways") {
    runWithRollback {
      dao.create(List(testRoadway1, testRoadway2.copy(roadPartNumber = 1l), testRoadway3))
      val roadways = dao.fetchAllBySectionAndAddresses(roadNumber1, 1l, Some(0l), Some(200l))
      roadways.filter(r => r.roadwayNumber == roadwayNumber1).size should be(1)
      roadways.filter(r => r.roadwayNumber == roadwayNumber2).size should be(1)
      roadways.size should be(2)
    }
  }

  test("Test fetchAllBySectionAndAddresses When valid values of existing roadways no startAddrM Then return roadways") {
    runWithRollback {
      dao.create(List(testRoadway1, testRoadway2.copy(roadPartNumber = 1l), testRoadway3))
      val roadways = dao.fetchAllBySectionAndAddresses(roadNumber1, 1l, None, Some(100l))
      roadways.filter(r => r.roadwayNumber == roadwayNumber1).size should be(1)
      roadways.filter(r => r.roadwayNumber == roadwayNumber2).size should be(0)
      roadways.size should be(1)
    }
  }

  test("Test fetchAllBySectionAndAddresses When valid values of existing roadways no endAddrM Then return roadways") {
    runWithRollback {
      dao.create(List(testRoadway1, testRoadway2.copy(roadPartNumber = 1l), testRoadway3))
      val roadways = dao.fetchAllBySectionAndAddresses(roadNumber1, 1l, Some(100l), None)
      roadways.filter(r => r.roadwayNumber == roadwayNumber1).size should be(0)
      roadways.filter(r => r.roadwayNumber == roadwayNumber2).size should be(1)
      roadways.size should be(1)
    }
  }

  test("Test fetchAllBySectionAndAddresses When valid values of existing roadways no startAddrM and no endAddrM Then return roadways") {
    runWithRollback {
      dao.create(List(testRoadway1, testRoadway2.copy(roadPartNumber = 1l), testRoadway3))
      val roadways = dao.fetchAllBySectionAndAddresses(roadNumber1, 1l, None, None)
      roadways.filter(r => r.roadwayNumber == roadwayNumber1).size should be(1)
      roadways.filter(r => r.roadwayNumber == roadwayNumber2).size should be(1)
      roadways.size should be(2)
    }
  }

  test("Test fetchAllBySectionAndAddresses When existing road number and road part numbers and multiple tracks Then return roadways") {
    runWithRollback {
      dao.create(List(testRoadway1.copy(track = Track.LeftSide), testRoadway2.copy(track = Track.RightSide, roadPartNumber = 1l), testRoadway3))
      val roadways = dao.fetchAllBySectionAndAddresses(roadNumber1, 1l, Some(0l), Some(100l))
      roadways.filter(r => r.roadwayNumber == roadwayNumber1).size should be(1)
      roadways.size should be(1)
    }
  }

  // fetchAllByBetweenDates

  test("Test fetchAllByBetweenDates When non-existing dates Then return None") {
    runWithRollback {
      dao.create(List(testRoadway1, testRoadway2))
      dao.fetchAllByDateRange(DateTime.parse("1800-01-01"), DateTime.parse("1800-02-01")).size should be(0)
    }
  }

  test("Test fetchAllByBetweenDates When existing dates but later date first Then return None") {
    runWithRollback {
      dao.create(List(testRoadway1, testRoadway2, testRoadway3))
      dao.fetchAllByDateRange(DateTime.parse("2000-01-02"), DateTime.parse("2000-01-01")).size should be(0)
    }
  }

  test("Test fetchAllByBetweenDates When existing dates (start date same than end date) Then return roadways") {
    runWithRollback {
      dao.create(List(testRoadway1, testRoadway2, testRoadway3))
      val roadways = dao.fetchAllByDateRange(DateTime.parse("2000-01-01"), DateTime.parse("2000-01-01"))
      roadways.filter(r => r.roadwayNumber == roadwayNumber1).size should be(1)
      roadways.filter(r => r.roadwayNumber == roadwayNumber2).size should be(1)
      roadways.filter(r => r.roadwayNumber == roadwayNumber3).size should be(1)
    }
  }

  test("Test fetchAllByBetweenDates When existing dates Then return roadways") {
    runWithRollback {
      dao.create(List(testRoadway1, testRoadway2, testRoadway3.copy(startDate = DateTime.parse("2000-01-02"))))
      val roadways = dao.fetchAllByDateRange(DateTime.parse("2000-01-01"), DateTime.parse("2000-01-02"))
      roadways.filter(r => r.roadwayNumber == roadwayNumber1).size should be(1)
      roadways.filter(r => r.roadwayNumber == roadwayNumber2).size should be(1)
      roadways.filter(r => r.roadwayNumber == roadwayNumber3).size should be(1)
    }
  }

  test("Test fetchAllByBetweenDates When existing dates and one too early Then return roadways") {
    runWithRollback {
      dao.create(List(testRoadway1, testRoadway2, testRoadway3.copy(startDate = DateTime.parse("1999-12-31"))))
      val roadways = dao.fetchAllByDateRange(DateTime.parse("2000-01-01"), DateTime.parse("2000-01-01"))
      roadways.filter(r => r.roadwayNumber == roadwayNumber1).size should be(1)
      roadways.filter(r => r.roadwayNumber == roadwayNumber2).size should be(1)
      roadways.filter(r => r.roadwayNumber == roadwayNumber3).size should be(0)
    }
  }

  test("Test fetchAllByBetweenDates When existing dates and one too late Then return roadways") {
    runWithRollback {
      dao.create(List(testRoadway1, testRoadway2, testRoadway3.copy(startDate = DateTime.parse("2000-01-02"))))
      val roadways = dao.fetchAllByDateRange(DateTime.parse("2000-01-01"), DateTime.parse("2000-01-01"))
      roadways.filter(r => r.roadwayNumber == roadwayNumber1).size should be(1)
      roadways.filter(r => r.roadwayNumber == roadwayNumber2).size should be(1)
      roadways.filter(r => r.roadwayNumber == roadwayNumber3).size should be(0)
    }
  }

  // TODO Should the end date of the roadway be taken in account in fetchAllByBetweenDates query as well?

  // fetchAllCurrentRoadNumbers

  test("Test fetchAllCurrentRoadNumbers When search Then return all current road numbers") {
    runWithRollback {
      dao.create(List(testRoadway1, testRoadway2, testRoadway3))
      val numbers = dao.fetchAllCurrentRoadNumbers()
      numbers should contain(roadNumber1)
      numbers should contain(roadNumber2)
    }
  }

  // fetchAllRoadAddressErrors

  test("Test fetchAllRoadAddressErrors When fetch excluding history Then return addresses with errors") {
    runWithRollback {
      val roadwayId = dao.getNextRoadwayId
      dao.create(List(testRoadway1.copy(id = roadwayId), testRoadway2, testRoadway3))
      val linearLocationId1 = linearLocationDAO.getNextLinearLocationId
      linearLocationDAO.create(List(testLinearLocation1.copy(id = linearLocationId1)))
      val roadNetworkDAO = new RoadNetworkDAO
      roadNetworkDAO.addRoadNetworkError(roadwayId, linearLocationId1, AddressError.InconsistentLrmHistory, roadNetworkDAO.getLatestRoadNetworkVersionId)
      val errors = dao.fetchAllRoadAddressErrors()
      errors.size should be > 0
    }
  }

  test("Test fetchAllRoadAddressErrors When fetch including history Then return addresses with errors") {
    runWithRollback {
      val roadwayId = dao.getNextRoadwayId
      dao.create(List(testRoadway1.copy(id = roadwayId, endDate = Some(DateTime.parse("2010-01-01"))), testRoadway2, testRoadway3))
      val linearLocationId1 = linearLocationDAO.getNextLinearLocationId
      linearLocationDAO.create(List(testLinearLocation1.copy(id = linearLocationId1)))
      val roadNetworkDAO = new RoadNetworkDAO
      roadNetworkDAO.addRoadNetworkError(roadwayId, linearLocationId1, AddressError.InconsistentLrmHistory, roadNetworkDAO.getLatestRoadNetworkVersionId)
      val errors = dao.fetchAllRoadAddressErrors(includesHistory = true)
      errors.size should be > 0
    }
  }

  // create

  test("Test create When insert duplicate roadway Then give error") {
    runWithRollback {
      val error = intercept[SQLException] {
        dao.create(Seq(testRoadway1, testRoadway1))
      }
      error.getErrorCode should be(1)
    }
  }

  test("Test create When insert duplicate roadway with different roadway number Then give error") {
    runWithRollback {
      val error = intercept[SQLException] {
        dao.create(Seq(testRoadway1, testRoadway1.copy(roadwayNumber = roadwayNumber2)))
      }
      error.getErrorCode should be(1)
    }
  }

  test("Test create When insert roadway with termination code 1 but no end date Then give error") {
    runWithRollback {
      val error = intercept[SQLException] {
        dao.create(Seq(testRoadway1.copy(terminated = TerminationCode.Termination)))
      }
      error.getErrorCode should be(2290)
    }
  }

  test("Test create When insert roadway with termination code 2 but no end date Then give error") {
    runWithRollback {
      val error = intercept[SQLException] {
        dao.create(Seq(testRoadway1.copy(terminated = TerminationCode.Subsequent)))
      }
      error.getErrorCode should be(2290)
    }
  }

  test("Test create When insert roadway with termination code 1 with end date Then roadway should be inserted") {
    runWithRollback {
      val endDate = Some(DateTime.parse("2001-12-31"))
      dao.create(Seq(testRoadway1.copy(terminated = TerminationCode.Termination, endDate = endDate)))
      val roadway = dao.fetchByRoadwayNumber(roadwayNumber1, includeHistory = true).getOrElse(fail())
      roadway.roadwayNumber should be(roadwayNumber1)
      roadway.terminated should be(TerminationCode.Termination)
      roadway.endDate should be(endDate)
    }
  }

  test("Test create When insert roadway with new roadway number Then roadway should be inserted") {
    runWithRollback {
      dao.create(Seq(testRoadway1.copy(roadwayNumber = NewRoadwayNumber)))
      val roadwayNumber = Sequences.nextRoadwayNumber - 1
      val roadway = dao.fetchByRoadwayNumber(roadwayNumber).getOrElse(fail())
      roadway.roadwayNumber should be(roadwayNumber)
    }
  }

  test("Test create When insert roadway Then all values are saved correctly") {
    runWithRollback {
      dao.create(Seq(testRoadway1))
      val roadway = dao.fetchByRoadwayNumber(roadwayNumber1).getOrElse(fail())
      roadway.id should be > 0l
      roadway.roadwayNumber should be(testRoadway1.roadwayNumber)
      roadway.roadNumber should be(testRoadway1.roadNumber)
      roadway.roadPartNumber should be(testRoadway1.roadPartNumber)
      roadway.track should be(testRoadway1.track)
      roadway.startAddrMValue should be(testRoadway1.startAddrMValue)
      roadway.endAddrMValue should be(testRoadway1.endAddrMValue)
      roadway.reversed should be(testRoadway1.reversed)
      roadway.discontinuity should be(testRoadway1.discontinuity)
      roadway.startDate should be(testRoadway1.startDate)
      roadway.endDate should be(testRoadway1.endDate)
      roadway.createdBy should be(testRoadway1.createdBy)
      roadway.roadType should be(testRoadway1.roadType)
      roadway.ely should be(testRoadway1.ely)
      roadway.terminated should be(testRoadway1.terminated)
      roadway.validFrom should not be (None)
      roadway.validTo should be(None)
    }
  }

  // fetchAllByRoad

  test("Test fetchAllByRoad When non-existing road number Then return None") {
    runWithRollback {
      dao.create(List(testRoadway1, testRoadway2))
      dao.fetchAllByRoad(nonExistingRoadNumber).size should be(0)
    }
  }

  test("Test fetchAllByRoad When existing road number Then return roadways") {
    runWithRollback {
      dao.create(List(testRoadway1, testRoadway2, testRoadway3))
      val roadways = dao.fetchAllByRoad(roadNumber1)
      roadways.filter(r => r.roadwayNumber == roadwayNumber1).size should be(1)
      roadways.filter(r => r.roadwayNumber == roadwayNumber2).size should be(1)
      roadways.size should be(2)
    }
  }

  // fetchAllByRoadwayNumbers and date

  test("Test fetchAllByRoadwayNumbers and date When empty roadway numbers Then return None") {
    runWithRollback {
      dao.create(List(testRoadway1))
      dao.fetchAllByRoadwayNumbers(roadwayNumbers = Set[Long](), DateTime.parse("2018-10-01")).size should be(0)
    }
  }

  test("Test fetchAllByRoadwayNumbers and date When non-existing roadway numbers Then return None") {
    runWithRollback {
      dao.create(List(testRoadway1, testRoadway2))
      dao.fetchAllByRoadwayNumbers(Set(nonExistingRoadwayNumber), DateTime.parse("2018-10-01")).size should be(0)
    }
  }

  test("Test fetchAllByRoadwayNumbers and date When existing roadway numbers Then return the current roadways") {
    runWithRollback {
      dao.create(List(testRoadway1, testRoadway2, testRoadway2.copy(endDate = Some(DateTime.parse("2001-12-31"))), testRoadway3))
      val roadways = dao.fetchAllByRoadwayNumbers(Set(roadwayNumber1, roadwayNumber2), DateTime.parse("2018-10-01"))
      roadways.filter(r => r.roadwayNumber == roadwayNumber1).size should be(1)
      roadways.filter(r => r.roadwayNumber == roadwayNumber2).size should be(1)
      roadways.filter(r => r.roadwayNumber == roadwayNumber2).head.endDate should be(None)
      roadways.size should be(2)
    }
  }

  test("Test fetchAllByRoadwayNumbers and date When existing roadway numbers and old date Then return None") {
    runWithRollback {
      dao.create(List(
        testRoadway1,
        testRoadway1.copy(startAddrMValue = 100, endAddrMValue = 200, endDate = Some(DateTime.parse("2000-12-31"))),
        testRoadway2
      ))
      val roadways = dao.fetchAllByRoadwayNumbers(Set(roadwayNumber1), DateTime.parse("1800-01-01"))
      roadways.size should be(0)
    }
  }

  test("Test fetchAllByRoadwayNumbers and date When existing roadway numbers Then return only the current roadways") {
    runWithRollback {
      dao.create(List(
        testRoadway1,
        testRoadway1.copy(startAddrMValue = 100, endAddrMValue = 200, endDate = Some(DateTime.parse("2000-12-31"))),
        testRoadway2
      ))
      val roadways = dao.fetchAllByRoadwayNumbers(Set(roadwayNumber1), DateTime.parse("2018-10-01"))
      roadways.size should be(1)
      roadways.head.endDate should be(None)
      roadways.head.validTo should be(None)
    }
  }

  // fetchAllByRoadwayNumbers and road network id


  test("Test fetchAllByRoadwayNumbers and road network id When empty roadway numbers Then return None") {
    runWithRollback {
      val roadNetworkDAO = new RoadNetworkDAO
      roadNetworkDAO.createPublishedRoadNetwork
      val roadNetworkId = roadNetworkDAO.getLatestRoadNetworkVersionId.getOrElse(fail())
      dao.create(List(testRoadway1))
      dao.fetchAllByRoadwayNumbers(Set[Long](), roadNetworkId).size should be(0)
    }
  }

  test("Test fetchAllByRoadwayNumbers and road network id When non-existing roadway numbers Then return None") {
    runWithRollback {
      val roadNetworkDAO = new RoadNetworkDAO
      roadNetworkDAO.createPublishedRoadNetwork
      val roadNetworkId = roadNetworkDAO.getLatestRoadNetworkVersionId.getOrElse(fail())
      dao.create(List(testRoadway1, testRoadway2))
      dao.fetchAllByRoadwayNumbers(Set(nonExistingRoadwayNumber), roadNetworkId).size should be(0)
    }
  }

  test("Test fetchAllByRoadwayNumbers and road network id When existing roadway numbers and non-existing network id Then return None") {
    runWithRollback {
      val nonExistingRoadNetworkId = -9999l
      dao.create(List(testRoadway1, testRoadway2, testRoadway2.copy(endDate = Some(DateTime.parse("2001-12-31"))), testRoadway3))
      val roadways = dao.fetchAllByRoadwayNumbers(Set(roadwayNumber1, roadwayNumber2), nonExistingRoadNetworkId)
      roadways.size should be(0)
    }
  }

  test("Test fetchAllByRoadwayNumbers and road network id When existing roadway numbers Then return the current roadways") {
    runWithRollback {
      val roadNetworkDAO = new RoadNetworkDAO
      roadNetworkDAO.createPublishedRoadNetwork
      val roadNetworkVersionId = roadNetworkDAO.getLatestRoadNetworkVersionId.getOrElse(fail())
      val roadwayId1 = dao.getNextRoadwayId
      val roadwayId2 = dao.getNextRoadwayId
      val roadwayId3 = dao.getNextRoadwayId
      dao.create(List(testRoadway1.copy(id = roadwayId1), testRoadway2.copy(id = roadwayId2), testRoadway3.copy(id = roadwayId3)))
      roadNetworkDAO.createPublishedRoadway(roadNetworkVersionId, roadwayId1)
      roadNetworkDAO.createPublishedRoadway(roadNetworkVersionId, roadwayId2)
      roadNetworkDAO.createPublishedRoadway(roadNetworkVersionId, roadwayId3)
      val roadways = dao.fetchAllByRoadwayNumbers(Set(roadwayNumber1, roadwayNumber2), roadNetworkVersionId)
      roadways.filter(r => r.roadwayNumber == roadwayNumber1).size should be(1)
      roadways.filter(r => r.roadwayNumber == roadwayNumber2).size should be(1)
      roadways.filter(r => r.roadwayNumber == roadwayNumber2).head.endDate should be(None)
      roadways.size should be(2)
    }
  }

  // fetchPreviousRoadPartNumber

  test("Test fetchPreviousRoadPartNumber When non-existing road number Then return None") {
    runWithRollback {
      dao.create(List(testRoadway1, testRoadway2))
      dao.fetchPreviousRoadPartNumber(nonExistingRoadNumber, roadPartNumber2) should be(None)
    }
  }

  test("Test fetchPreviousRoadPartNumber When non-existing road part number Then return None") {
    runWithRollback {
      dao.create(List(testRoadway1, testRoadway2))
      dao.fetchPreviousRoadPartNumber(roadNumber1, nonExistingRoadPartNumber) should be(None)
    }
  }

  test("Test fetchPreviousRoadPartNumber When same road part number as first Then return None") {
    runWithRollback {
      dao.create(List(testRoadway1, testRoadway2))
      dao.fetchPreviousRoadPartNumber(roadNumber1, roadPartNumber1) should be(None)
    }
  }

  test("Test fetchPreviousRoadPartNumber When next road part number Then return previous road part number") {
    runWithRollback {
      dao.create(List(testRoadway1, testRoadway2))
      dao.fetchPreviousRoadPartNumber(roadNumber1, roadPartNumber2).get should be(roadPartNumber1)
    }
  }

  test("Test fetchPreviousRoadPartNumber When gap in road part numbers Then return previous road part number") {
    runWithRollback {
      dao.create(List(testRoadway1, testRoadway2.copy(roadPartNumber = 3)))
      dao.fetchPreviousRoadPartNumber(roadNumber1, 3).get should be(roadPartNumber1)
    }
  }

  test("Test fetchPreviousRoadPartNumber When next road part number with history Then return previous road part number") {
    runWithRollback {
      dao.create(List(testRoadway1, testRoadway2.copy(roadPartNumber = 3),
        testRoadway1.copy(roadPartNumber = 2, endDate = Some(DateTime.now().minusYears(1)))))
      dao.fetchPreviousRoadPartNumber(roadNumber1, 3).get should be(roadPartNumber1)
    }
  }

  // getRoadPartInfo

  test("Test getRoadPartInfo When non-existing road number Then return None") {
    runWithRollback {
      dao.create(List(testRoadway1))
      dao.getRoadPartInfo(nonExistingRoadNumber, roadPartNumber1) should be(None)
    }
  }

  test("Test getRoadPartInfo When non-existing road part number Then return None") {
    runWithRollback {
      dao.create(List(testRoadway1))
      dao.getRoadPartInfo(roadNumber1, nonExistingRoadPartNumber) should be(None)
    }
  }

  test("Test getRoadPartInfo When existing road and road part number Then return info") {
    runWithRollback {
      val roadwayId = dao.getNextRoadwayId
      dao.create(List(testRoadway1.copy(id = roadwayId)))
      val linearLocationId1 = linearLocationDAO.getNextLinearLocationId
      linearLocationDAO.create(List(testLinearLocation1.copy(id = linearLocationId1)))

      val info = dao.getRoadPartInfo(roadNumber1, roadPartNumber1).getOrElse(fail)
      info._1 should be(roadwayId)
      info._2 should be(testLinearLocation1.linkId)
      info._3 should be(testRoadway1.endAddrMValue)
      info._4 should be(testRoadway1.discontinuity.value)
      info._5 should be(testRoadway1.ely)
      info._6.getOrElse(fail) should be(testRoadway1.startDate)
      info._7 should be(None)
    }
  }

  // getValidRoadParts

  test("Test getValidRoadParts When non-existing road number Then return empty list") {
    runWithRollback {
      dao.create(List(testRoadway1))
      dao.getValidRoadParts(nonExistingRoadNumber, testRoadway1.startDate).size should be(0)
    }
  }

  test("Test getValidRoadParts When too early start date Then return empty list") {
    runWithRollback {
      dao.create(List(testRoadway1))
      dao.getValidRoadParts(roadNumber1, testRoadway1.startDate.minusDays(1)).size should be(0)
    }
  }

  test("Test getValidRoadParts When existing road number and exactly same start date Then return road part numbers") {
    runWithRollback {
      dao.create(List(testRoadway1))
      val roadPartNumbers = dao.getValidRoadParts(roadNumber1, testRoadway1.startDate)
      roadPartNumbers.size should be(1)
      roadPartNumbers.head should be(testRoadway1.roadPartNumber)
    }
  }

  test("Test getValidRoadParts When existing road number and later start date Then return road part numbers") {
    runWithRollback {
      dao.create(List(testRoadway1, testRoadway2))
      val roadPartNumbers = dao.getValidRoadParts(roadNumber1, testRoadway1.startDate.plusDays(1))
      roadPartNumbers.size should be(2)
      roadPartNumbers.contains(roadPartNumber1) should be(true)
      roadPartNumbers.contains(roadPartNumber2) should be(true)
    }
  }

  // expireById

  test("Test expireById When empty ids Then return 0") {
    runWithRollback {
      dao.create(List(testRoadway1))
      dao.expireById(Set()) should be(0)
    }
  }

  test("Test expireById When non-existing ids Then return 0") {
    runWithRollback {
      dao.create(List(testRoadway1, testRoadway2))
      dao.expireById(Set(nonExistingRoadwayId)) should be(0)
    }
  }

  test("Test expireById When existing roadway ids Then return 2 and roadways are expired") {
    runWithRollback {
      val roadwayId1 = dao.getNextRoadwayId
      val roadwayId2 = dao.getNextRoadwayId
      dao.create(List(testRoadway1.copy(id = roadwayId1), testRoadway2.copy(id = roadwayId2), testRoadway2.copy(endDate = Some(DateTime.parse("2001-12-31"))), testRoadway3))
      val roadways = dao.fetchAllByRoadwayId(Seq(roadwayId1, roadwayId2))
      roadways.count(r => r.roadwayNumber == roadwayNumber1) should be(1)
      roadways.count(r => r.roadwayNumber == roadwayNumber2) should be(1)
      roadways.size should be(2)
      dao.expireById(Set(roadwayId1, roadwayId2)) should be(2)
      dao.fetchAllByRoadwayId(Seq(roadwayId1, roadwayId2)).size should be(0)
    }
  }

  test("Test () When filtering only by road number Then return the correct roadways withing the filter boundaries") {
    runWithRollback {
      val roadwayId1 = dao.getNextRoadwayId
      val roadwayId2 = dao.getNextRoadwayId
      val firstRoadway = testRoadway1.copy(id = roadwayId1)
      val secondRoadway = testRoadway1.copy(id = roadwayId2, roadPartNumber = testRoadway1.roadPartNumber + 1)
      dao.create(List(firstRoadway, secondRoadway))
      val nonExistingRoadNumber = dao.fetchAllByRoad(99999999L)
      nonExistingRoadNumber.size should be (0)
      val recentlyCreatedRoadNumber = dao.fetchAllByRoad(testRoadway1.roadNumber)
      recentlyCreatedRoadNumber.size should be (2)
      Seq(roadwayId1, roadwayId2).sorted should be (recentlyCreatedRoadNumber.map(_.id).sorted)
      val secondRoadPart = dao.fetchAllByRoadAndPart(testRoadway1.roadNumber, secondRoadway.roadPartNumber)
      secondRoadPart.size should be (1)
      secondRoadPart.head.id should be (roadwayId2)
    }
  }

}

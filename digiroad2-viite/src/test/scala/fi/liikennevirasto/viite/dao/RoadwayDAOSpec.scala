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
      val roadwayId1 = Sequences.nextRoadwayId
      val roadwayId2 = Sequences.nextRoadwayId
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
      dao.fetchAllByBetweenDates(DateTime.parse("1800-01-01"), DateTime.parse("1800-02-01")).size should be(0)
    }
  }

  test("Test fetchAllByBetweenDates When existing dates but later date first Then return None") {
    runWithRollback {
      dao.create(List(testRoadway1, testRoadway2, testRoadway3))
      dao.fetchAllByBetweenDates(DateTime.parse("2000-01-02"), DateTime.parse("2000-01-01")).size should be(0)
    }
  }

  test("Test fetchAllByBetweenDates When existing dates (start date same than end date) Then return roadways") {
    runWithRollback {
      dao.create(List(testRoadway1, testRoadway2, testRoadway3))
      val roadways = dao.fetchAllByBetweenDates(DateTime.parse("2000-01-01"), DateTime.parse("2000-01-01"))
      roadways.filter(r => r.roadwayNumber == roadwayNumber1).size should be(1)
      roadways.filter(r => r.roadwayNumber == roadwayNumber2).size should be(1)
      roadways.filter(r => r.roadwayNumber == roadwayNumber3).size should be(1)
    }
  }

  test("Test fetchAllByBetweenDates When existing dates Then return roadways") {
    runWithRollback {
      dao.create(List(testRoadway1, testRoadway2, testRoadway3.copy(startDate = DateTime.parse("2000-01-02"))))
      val roadways = dao.fetchAllByBetweenDates(DateTime.parse("2000-01-01"), DateTime.parse("2000-01-02"))
      roadways.filter(r => r.roadwayNumber == roadwayNumber1).size should be(1)
      roadways.filter(r => r.roadwayNumber == roadwayNumber2).size should be(1)
      roadways.filter(r => r.roadwayNumber == roadwayNumber3).size should be(1)
    }
  }

  test("Test fetchAllByBetweenDates When existing dates and one too early Then return roadways") {
    runWithRollback {
      dao.create(List(testRoadway1, testRoadway2, testRoadway3.copy(startDate = DateTime.parse("1999-12-31"))))
      val roadways = dao.fetchAllByBetweenDates(DateTime.parse("2000-01-01"), DateTime.parse("2000-01-01"))
      roadways.filter(r => r.roadwayNumber == roadwayNumber1).size should be(1)
      roadways.filter(r => r.roadwayNumber == roadwayNumber2).size should be(1)
      roadways.filter(r => r.roadwayNumber == roadwayNumber3).size should be(0)
    }
  }

  test("Test fetchAllByBetweenDates When existing dates and one too late Then return roadways") {
    runWithRollback {
      dao.create(List(testRoadway1, testRoadway2, testRoadway3.copy(startDate = DateTime.parse("2000-01-02"))))
      val roadways = dao.fetchAllByBetweenDates(DateTime.parse("2000-01-01"), DateTime.parse("2000-01-01"))
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
      val roadwayId = Sequences.nextRoadwayId
      dao.create(List(testRoadway1.copy(id = roadwayId), testRoadway2, testRoadway3))
      val linearLocationDAO = new LinearLocationDAO
      val linearLocationId = Sequences.nextLinearLocationId
      val linearLocation = LinearLocation(linearLocationId, 1, 1000l, 0.0, 100.0, SideCode.TowardsDigitizing, 10000000000l,
        (Some(0l), None), FloatingReason.NoFloating, Seq(Point(0.0, 0.0), Point(0.0, 100.0)), LinkGeomSource.NormalLinkInterface,
        testRoadway1.roadwayNumber)
      linearLocationDAO.create(List(linearLocation))
      val roadNetworkDAO = new RoadNetworkDAO
      roadNetworkDAO.addRoadNetworkError(roadwayId, linearLocationId, AddressError.InconsistentLrmHistory)
      val errors = dao.fetchAllRoadAddressErrors()
      errors.size should be > 0
    }
  }

  test("Test fetchAllRoadAddressErrors When fetch including history Then return addresses with errors") {
    runWithRollback {
      val roadwayId = Sequences.nextRoadwayId
      dao.create(List(testRoadway1.copy(id = roadwayId, endDate = Some(DateTime.parse("2010-01-01"))), testRoadway2, testRoadway3))
      val linearLocationDAO = new LinearLocationDAO
      val linearLocationId = Sequences.nextLinearLocationId
      val linearLocation = LinearLocation(linearLocationId, 1, 1000l, 0.0, 100.0, SideCode.TowardsDigitizing, 10000000000l,
        (Some(0l), None), FloatingReason.NoFloating, Seq(Point(0.0, 0.0), Point(0.0, 100.0)), LinkGeomSource.NormalLinkInterface,
        testRoadway1.roadwayNumber)
      linearLocationDAO.create(List(linearLocation))
      val roadNetworkDAO = new RoadNetworkDAO
      roadNetworkDAO.addRoadNetworkError(roadwayId, linearLocationId, AddressError.InconsistentLrmHistory)
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
      roadway.validFrom should not be(None)
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
      val roadwayId1 = Sequences.nextRoadwayId
      val roadwayId2 = Sequences.nextRoadwayId
      val roadwayId3 = Sequences.nextRoadwayId
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

  //TODO will be implemented at VIITE-1552
  //  test("insert road address m-values overlap") {
  //    runWithRollback {
  //      val error = intercept[SQLException] {
  //        sqlu""" Insert into ROADWAY (ID,ROAD_NUMBER,ROAD_PART_NUMBER,TRACK,DISCONTINUITY,START_ADDR_M,END_ADDR_M,START_DATE,END_DATE,CREATED_BY,VALID_FROM,CALIBRATION_POINTS,FLOATING,GEOMETRY,VALID_TO, ROAD_TYPE, ELY, SIDE,START_MEASURE,END_MEASURE,LINK_ID,ADJUSTED_TIMESTAMP,MODIFIED_DATE,LINK_SOURCE) values (viite_general_seq.nextval,1010,1,0,5,627,648,to_date('63.01.01','RR.MM.DD'),null,'tr',to_date('98.10.16','RR.MM.DD'),0,0,MDSYS.SDO_GEOMETRY(4002,3067,NULL,MDSYS.SDO_ELEM_INFO_ARRAY(1,2,1),MDSYS.SDO_ORDINATE_ARRAY(288781.428,6825565.909,0,0,288763.118,6825576.235,0,21)),null, 1, 4,2,0,21.021,1111102483,1476392565000,to_timestamp('17.09.15 19:39:30','RR.MM.DD HH24:MI:SS,FF'),1)""".execute
  //        sqlu""" Insert into ROADWAY (ID,ROAD_NUMBER,ROAD_PART_NUMBER,TRACK,DISCONTINUITY,START_ADDR_M,END_ADDR_M,START_DATE,END_DATE,CREATED_BY,VALID_FROM,CALIBRATION_POINTS,FLOATING,GEOMETRY,VALID_TO, ROAD_TYPE, ELY, SIDE,START_MEASURE,END_MEASURE,LINK_ID,ADJUSTED_TIMESTAMP,MODIFIED_DATE,LINK_SOURCE) values (viite_general_seq.nextval,1010,1,0,5,627,648,to_date('63.01.01','RR.MM.DD'),null,'tr',to_date('98.10.16','RR.MM.DD'),0,0,MDSYS.SDO_GEOMETRY(4012,3057,NULL,MDSYS.SDO_ELEM_INFO_ARRAY(1,2,1),MDSYS.SDO_ORDINATE_ARRAY(288781.428,6825565.909,0,0,288763.118,6825576.235,0,21)),null, 1, 4,2,0,21.021,1111102483,1476392565000,to_timestamp('17.09.15 19:39:30','RR.MM.DD HH24:MI:SS,FF'),1)""".execute
  //      }
  //      error.getErrorCode should be(29875)
  //    }
  //  }

  //TODO will be implemented at VIITE-1553
//  test("Returning of a terminated road") {
//    runWithRollback {
//      createTerminatedRoadAddress7777(Option.apply(DateTime.parse("1975-11-18")))
//      val roadAddresses = roadwayDAO.fetchByLinkId(Set(7777777))
//      roadAddresses.size should be (1)
//      roadAddresses.head.terminated.value should be (1)
//    }
//  }

  //TODO will be implemented at VIITE-1553
  //  test("testFetchByRoadPart") {
  //    runWithRollback {
  //      RoadAddressDAO.fetchByRoadPart(5L, 201L).isEmpty should be(false)
  //    }
  //  }

  //TODO will be implemented at VIITE-1553
  //  test("testFetchByLinkId") {
  //    runWithRollback {
  //      val sets = RoadAddressDAO.fetchByLinkId(Set(5170942, 5170947))
  //      sets.size should be (2)
  //      sets.forall(_.isFloating == false) should be (true)
  //    }
  //  }

  //TODO will be implemented at VIITE-1553
  //  test("Get valid road numbers") {
  //    runWithRollback {
  //      val numbers = RoadAddressDAO.getAllValidRoadNumbers()
  //      numbers.isEmpty should be(false)
  //      numbers should contain(5L)
  //    }
  //  }

  //TODO will be implemented at VIITE-1553
  //  test("Get valid road part numbers") {
  //    runWithRollback {
  //      val numbers = RoadAddressDAO.getValidRoadParts(5L)
  //      numbers.isEmpty should be(false)
  //      numbers should contain(201L)
  //    }
  //  }

  //TODO will be implemented at VIITE-1552
  //  test("Update without geometry") {
  //    runWithRollback {
  //      val address = RoadAddressDAO.fetchByLinkId(Set(5170942)).head
  //      RoadAddressDAO.update(address)
  //    }
  //  }

  //TODO will be implemented at VIITE-1552
  //  test("Updating a geometry is executed in SQL server") {
  //    runWithRollback {
  //      val address = RoadAddressDAO.fetchByLinkId(Set(5170942)).head
  //      RoadAddressDAO.update(address, Some(Seq(Point(50200, 7630000.0, 0.0), Point(50210, 7630000.0, 10.0))))
  //      RoadAddressDAO.fetchRoadAddressesByBoundingBox(BoundingRectangle(Point(50202, 7620000), Point(50205, 7640000)), false).exists(_.id == address.id) should be (true)
  //      RoadAddressDAO.fetchRoadAddressesByBoundingBox(BoundingRectangle(Point(50212, 7620000), Point(50215, 7640000)), false).exists(_.id == address.id) should be (false)
  //    }
  //  }

  //TODO will be implemented at VIITE-1542
  //  test("Fetch unaddressed road links by boundingBox"){
  //    runWithRollback {
  //      val boundingBox = BoundingRectangle(Point(6699381, 396898), Point(6699382, 396898))
  //      sqlu"""
  //           insert into UNADDRESSED_ROAD_LINK (link_id, start_addr_m, end_addr_m,anomaly_code, start_m, end_m, geometry)
  //           values (1943845, 0, 1, 1, 0, 34.944, MDSYS.SDO_GEOMETRY(4002, 3067, NULL, MDSYS.SDO_ELEM_INFO_ARRAY(1,2,1), MDSYS.SDO_ORDINATE_ARRAY(6699381,396898,0,0.0,6699382,396898,0,2)))
  //           """.execute
  //
  //      val unaddressedRoadLinks = RoadAddressDAO.fetchUnaddressedRoadLinksByBoundingBox(boundingBox)
  //      val addedValue = unaddressedRoadLinks.find(p => p.linkId == 1943845).get
  //      addedValue should not be None
  //      addedValue.geom.nonEmpty should be (true)
  //      addedValue.startAddrMValue.get should be (0)
  //      addedValue.endAddrMValue.get should be (1)
  //    }
  //  }

  //TODO will be implemented at VIITE-1537
  //  test("Set road address to floating and update the geometry as well") {
  //    runWithRollback {
  //      val address = RoadAddressDAO.fetchByLinkId(Set(5170942)).head
  //      RoadAddressDAO.changeRoadAddressFloatingWithHistory(address.id, Some(Seq(Point(50200, 7630000.0, 0.0), Point(50210, 7630000.0, 10.0))), floatingReason = GeometryChanged)
  //    }
  //  }

  //TODO will be implemented at VIITE-153
  //  test("Create Road Address") {
  //    runWithRollback {
  //      val id = RoadAddressDAO.getNextRoadwayId
  //      val ra = Seq(RoadAddress(id, 1943845, 1, RoadType.Unknown, Track.Combined, Discontinuous, 0L, 10L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), 12345L, 0.0, 9.8, SideCode.TowardsDigitizing, 0, (None, None), NoFloating,
  //        Seq(Point(0.0, 0.0), Point(0.0, 9.8)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
  //      val currentSize = RoadAddressDAO.fetchByRoadPart(ra.head.roadNumber, ra.head.roadPartNumber).size
  //      val returning = RoadAddressDAO.create(ra)
  //      returning.nonEmpty should be (true)
  //      returning.head should be (id)
  //      val newSize = currentSize + 1
  //      RoadAddressDAO.fetchByRoadPart(ra.head.roadNumber, ra.head.roadPartNumber) should have size(newSize)
  //    }
  //  }

  //TODO will be implemented at VIITE-1542
  //  test("Adding geometry to unaddressed road link") {
  //    runWithRollback {
  //      val id = 1943845
  //      sqlu"""
  //           insert into UNADDRESSED_ROAD_LINK (link_id, start_addr_m, end_addr_m,anomaly_code, start_m)
  //           values ($id, 0, 1, 1, 1)
  //           """.execute
  //      sqlu"""UPDATE UNADDRESSED_ROAD_LINK
  //        SET geometry= MDSYS.SDO_GEOMETRY(4002, 3067, NULL, MDSYS.SDO_ELEM_INFO_ARRAY(1,2,1), MDSYS.SDO_ORDINATE_ARRAY(
  //             6699381,396898,0,0.0,6699382,396898,0,2))
  //        WHERE link_id = ${id}""".execute
  //      val query= s"""select Count(geometry)
  //                 from UNADDRESSED_ROAD_LINK ra
  //                 WHERE ra.link_id=$id AND geometry IS NOT NULL
  //      """
  //      Q.queryNA[Int](query).firstOption should be (Some(1))
  //    }
  //  }

  //TODO will be implemented at VIITE-1553
  //  test("Create Road Address with username") {
  //    runWithRollback {
  //      val username = "testUser"
  //      val id = RoadAddressDAO.getNextRoadwayId
  //      val ra = Seq(RoadAddress(id, 1943845, 1, RoadType.Unknown, Track.Combined, Discontinuous, 0L, 10L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), 12345L, 0.0, 9.8, SideCode.TowardsDigitizing, 0, (None, None), NoFloating,
  //        Seq(Point(0.0, 0.0), Point(0.0, 9.8)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
  //      val currentSize = RoadAddressDAO.fetchByRoadPart(ra.head.roadNumber, ra.head.roadPartNumber).size
  //      val returning = RoadAddressDAO.create(ra, Some(username))
  //      returning.nonEmpty should be (true)
  //      returning.head should be (id)
  //      val newSize = currentSize + 1
  //      val roadAddress = RoadAddressDAO.fetchByRoadPart(ra.head.roadNumber, ra.head.roadPartNumber)
  //      roadAddress should have size(newSize)
  //      roadAddress.head.createdBy.get should be (username)
  //    }
  //  }

  //TODO will be implemented at VIITE-1552
  //  test("Create Road Address With Calibration Point") {
  //    runWithRollback {
  //      val id = RoadAddressDAO.getNextRoadwayId
  //      val ra = Seq(RoadAddress(id, 1943845, 1, RoadType.Unknown, Track.Combined, Discontinuous, 0L, 10L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), 12345L, 0.0, 9.8, SideCode.TowardsDigitizing, 0,
  //        (Some(CalibrationPoint(12345L, 0.0, 0L)), None), NoFloating,
  //        Seq(Point(0.0, 0.0), Point(0.0, 9.8)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
  //      val returning = RoadAddressDAO.create(ra)
  //      returning.nonEmpty should be (true)
  //      returning.head should be (id)
  //      val fetch = sql"""select calibration_points from ROADWAY where id = $id""".as[Int].list
  //      fetch.head should be (2)
  //    }
  //    runWithRollback {
  //      val id = RoadAddressDAO.getNextRoadwayId
  //      val ra = Seq(RoadAddress(id, 1943845, 1, RoadType.Unknown, Track.Combined, Discontinuous, 0L, 10L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), 12345L, 0.0, 9.8, SideCode.TowardsDigitizing, 0,
  //        (Some(CalibrationPoint(12345L, 0.0, 0L)), Some(CalibrationPoint(12345L, 9.8, 10L))), NoFloating,
  //        Seq(Point(0.0, 0.0), Point(0.0, 9.8)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
  //      val returning = RoadAddressDAO.create(ra)
  //      returning.nonEmpty should be (true)
  //      returning.head should be (id)
  //      val fetch = sql"""select calibration_points from ROADWAY where id = $id""".as[Int].list
  //      fetch.head should be (3)
  //    }
  //  }

  //TODO will be implemented at VIITE-1552
  //  test("Create Road Address with complementary source") {
  //    runWithRollback {
  //      val id = RoadAddressDAO.getNextRoadwayId
  //      val ra = Seq(RoadAddress(id, 1943845, 1, RoadType.Unknown, Track.Combined, Discontinuous, 0L, 10L, Some(DateTime.parse("1901-01-01")),
  //        None, Option("tester"), 12345L, 0.0, 9.8, SideCode.TowardsDigitizing, 0, (None, None), NoFloating,
  //        Seq(Point(0.0, 0.0), Point(0.0, 9.8)), LinkGeomSource.ComplimentaryLinkInterface, 8, NoTermination, 0))
  //      val returning = RoadAddressDAO.create(ra)
  //      returning.nonEmpty should be (true)
  //      returning.head should be (id)
  //      sql"""SELECT link_source FROM ROADWAY ra WHERE ra.id = $id"""
  //        .as[Int].first should be (ComplimentaryLinkInterface.value)
  //    }
  //  }


  //TODO will be implemented at VIITE-1553
  //  test("Delete Road Addresses") {
  //    runWithRollback {
  //      val addresses = RoadAddressDAO.fetchByRoadPart(5, 206)
  //      addresses.nonEmpty should be (true)
  //      RoadAddressDAO.remove(addresses) should be (addresses.size)
  //      sql"""SELECT COUNT(*) FROM ROADWAY WHERE ROAD_NUMBER = 5 AND ROAD_PART_NUMBER = 206 AND VALID_TO IS NULL""".as[Long].first should be (0L)
  //    }
  //  }

  //TODO probably this test will not be needed
  //  test("test update for merged Road Addresses") {
  //    val localMockRoadLinkService = MockitoSugar.mock[RoadLinkService]
  //    val localMockEventBus = MockitoSugar.mock[DigiroadEventBus]
  //    val localRoadAddressService = new RoadAddressService(localMockRoadLinkService,localMockEventBus)
  //    runWithRollback {
  //      val id1 = RoadAddressDAO.getNextRoadwayId
  //      val ra = Seq(RoadAddress(id1, 1943845, 1, RoadType.Unknown, Track.Combined, Discontinuous, 0L, 10L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), 12345L, 0.0, 9.8, SideCode.TowardsDigitizing, 0, (None, None), NoFloating,
  //        Seq(Point(0.0, 0.0), Point(0.0, 9.8)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
  //      RoadAddressDAO.create(ra, Some("user"))
  //      val id = RoadAddressDAO.getNextRoadwayId
  //      val toBeMergedRoadAddresses = Seq(RoadAddress(id, 1943845, 1, RoadType.Unknown, Track.Combined, Discontinuous, 0L, 10L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), 6556558L, 0.0, 9.8, SideCode.TowardsDigitizing, 0, (None, None), NoFloating,
  //        Seq(Point(0.0, 0.0), Point(0.0, 9.8)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
  //      localRoadAddressService.mergeRoadAddressInTX(RoadAddressMerge(Set(id1), toBeMergedRoadAddresses))
  //    }
  //  }

  //  ignore("test if road addresses are expired") {
  //    def now(): DateTime = {
  //      OracleDatabase.withDynSession {
  //        return sql"""select sysdate FROM dual""".as[DateTime].list.head
  //      }
  //    }
  //
  //    val beforeCallMethodDatetime = now()
  //    runWithRollback {
  //      val linkIds: Set[Long] = Set(4147081)
  //      RoadAddressDAO.expireRoadAddresses(linkIds)
  //      val dbResult = sql"""select valid_to FROM ROADWAY where link_id in (4147081)""".as[DateTime].list
  //      dbResult.size should be (1)
  //      dbResult.foreach{ date =>
  //        date.getMillis should be >= beforeCallMethodDatetime.getMillis
  //      }
  //    }
  //  }

  //TODO will be implemented at VIITE-1553
  //  test("find road address by start or end address value") {
  //    OracleDatabase.withDynSession {
  //      val s = RoadAddressDAO.fetchByAddressStart(75, 1, Track.apply(2), 875)
  //      val e = RoadAddressDAO.fetchByAddressEnd(75, 1, Track.apply(2), 995)
  //      s.isEmpty should be(false)
  //      e.isEmpty should be(false)
  //      s should be(e)
  //    }
  //  }

  //TODO will be implemented at VIITE-1550
  //  test("Fetching road addresses by bounding box should get only the latest ones (end_date is null)") {
  //    runWithRollback {
  //      val addressId1 = RoadAddressDAO.getNextRoadwayId
  //      val addressId2 = RoadAddressDAO.getNextRoadwayId
  //      val startDate1 = Some(DateTime.now.minusDays(5))
  //      val startDate2 = Some(DateTime.now.plusDays(5))
  //      val EndDate1 = startDate2
  //      val EndDate2 = None
  //      val ra = Seq(RoadAddress(addressId1, 1943844, 1, RoadType.Unknown, Track.Combined, Discontinuous, 0L, 10L, startDate1, EndDate1, Option("tester"), 12345L, 0.0, 9.8, SideCode.TowardsDigitizing, 0, (None, None), NoFloating,
  //        Seq(Point(1.0, 1.0), Point(1.0, 9.8)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0),
  //        RoadAddress(addressId2, 1943845, 1, RoadType.Unknown, Track.Combined, Discontinuous, 0L, 10L, startDate2, EndDate2, Option("tester"), 12345L, 0.0, 9.8, SideCode.TowardsDigitizing, 0, (None, None), NoFloating,
  //          Seq(Point(1.0, 1.0), Point(1.0, 9.8)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
  //      RoadAddressDAO.create(ra)
  //      val bounding = BoundingRectangle(Point(0.0, 0.0), Point(10, 10))
  //      val fetchedAddresses = RoadAddressDAO.fetchRoadAddressesByBoundingBox(bounding, false)
  //      fetchedAddresses.exists(_.id == addressId1) should be(false)
  //      fetchedAddresses.exists(_.id == addressId2) should be(true)
  //    }
  //  }

  //TODO will be implemented at VIITE-1550
  //  test("Bounding box search should return the latest road address even if it's start date is in the future.") {
  //    runWithRollback {
  //      val id1 = RoadAddressDAO.getNextRoadwayId
  //      val id2 = RoadAddressDAO.getNextRoadwayId
  //      val ra = Seq(
  //
  //        RoadAddress(id1, 1943845, 1, RoadType.Unknown, Track.Combined, Discontinuous, 0L, 10L,
  //          Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("2100-01-01")), Option("tester"), 12345L, 0.0, 9.8,
  //          SideCode.TowardsDigitizing, 0, (None, None), NoFloating, Seq(Point(0.0, 0.0), Point(0.0, 9.8)),
  //          LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0),
  //
  //        RoadAddress(id2, 1943845, 1, RoadType.Unknown, Track.Combined, Discontinuous, 0L, 10L,
  //          Some(DateTime.parse("2100-01-01")), None, Option("tester"), 12345L, 0.0, 9.8,
  //          SideCode.TowardsDigitizing, 0, (None, None), NoFloating, Seq(Point(0.0, 0.0), Point(0.0, 9.8)),
  //          LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0)
  //
  //      )
  //      RoadAddressDAO.create(ra)
  //      val results = RoadAddressDAO.fetchRoadAddressesByBoundingBox(BoundingRectangle(Point(0, 0), Point(10, 10)), false)
  //      results.exists(_.id == id1) should be (false)
  //      results.exists(_.id == id2) should be (true)
  //    }
  //  }

  //TODO will be implemented at VIITE-1550
  //  test("Bounding box search should not return the road address even if it is currently not terminated but in the future.") {
  //    runWithRollback {
  //      val id1 = RoadAddressDAO.getNextRoadwayId
  //      val id2 = RoadAddressDAO.getNextRoadwayId
  //      val ra = Seq(
  //
  //        RoadAddress(id1, 1943845, 1, RoadType.Unknown, Track.Combined, Discontinuous, 0L, 10L,
  //          Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("2100-01-01")), Option("tester"), 12345L, 0.0, 9.8,
  //          SideCode.TowardsDigitizing, 0, (None, None), NoFloating, Seq(Point(0.0, 0.0), Point(0.0, 9.8)),
  //          LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0),
  //
  //        RoadAddress(id2, 1943845, 1, RoadType.Unknown, Track.Combined, Discontinuous, 0L, 10L,
  //          Some(DateTime.parse("2100-01-01")), Some(DateTime.parse("2120-01-01")), Option("tester"), 12345L, 0.0, 9.8,
  //          SideCode.TowardsDigitizing, 0, (None, None), NoFloating, Seq(Point(0.0, 0.0), Point(0.0, 9.8)),
  //          LinkGeomSource.NormalLinkInterface, 8, TerminationCode.Termination, 0)
  //
  //      )
  //      RoadAddressDAO.create(ra)
  //      val results = RoadAddressDAO.fetchRoadAddressesByBoundingBox(BoundingRectangle(Point(0, 0), Point(10, 10)), false)
  //      results.exists(_.id == id1) should be (false)
  //      results.exists(_.id == id2) should be (false)
  //    }
  //  }

  //  private def createRoadAddress8888(startDate: Option[DateTime], endDate: Option[DateTime] = None): Unit = {
  //    RoadAddressDAO.create(
  //      Seq(
  //        RoadAddress(Sequences.nextRoadwayId, 8888, 1, RoadType.PublicRoad, Track.Combined,
  //          Discontinuity.Continuous, 0, 35, startDate, endDate,
  //          Option("TestUser"), 8888888, 0, 35, SideCode.TowardsDigitizing,
  //          0, (None, None), NoFloating, Seq(Point(24.24477,987.456)), LinkGeomSource.Unknown, 8, NoTermination, 0)))
  //  }
  //
  //  private def createTerminatedRoadAddress7777(startDate: Option[DateTime]): Unit = {
  //    val roadwayId = Sequences.nextRoadwayId
  //    RoadAddressDAO.create(
  //      Seq(
  //        RoadAddress(roadwayId, 7777, 1, RoadType.PublicRoad, Track.Combined,
  //          Discontinuity.Continuous, 0, 35, startDate, Option.apply(DateTime.parse("2000-01-01")),
  //          Option("TestUser"), 7777777, 0, 35, SideCode.TowardsDigitizing,
  //          0, (None, None), NoFloating, Seq(Point(24.24477,987.456)), LinkGeomSource.Unknown, 8, NoTermination, 0)))
  //    sqlu"""UPDATE ROADWAY SET Terminated = 1 Where ID = ${roadwayId}""".execute
  //  }

}

package fi.liikennevirasto.viite.dao

import java.sql.SQLException
import fi.liikennevirasto.digiroad2.dao.Sequences
import fi.liikennevirasto.digiroad2.postgis.PostGISDatabase
import fi.liikennevirasto.viite.AddressConsistencyValidator.AddressError
import fi.liikennevirasto.viite._
import fi.vaylavirasto.viite.geometry.Point
import fi.vaylavirasto.viite.model.{AdministrativeClass, LinkGeomSource, SideCode, Track}
import org.joda.time.DateTime
import org.scalatest.{FunSuite, Matchers}
import slick.driver.JdbcDriver.backend.Database
import slick.driver.JdbcDriver.backend.Database.dynamicSession


class RoadwayDAOSpec extends FunSuite with Matchers {

  def runWithRollback(f: => Unit): Unit = {
    Database.forDataSource(PostGISDatabase.ds).withDynTransaction {
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

  val testRoadway1 = Roadway(NewIdValue, roadwayNumber1, roadNumber1, roadPartNumber1, AdministrativeClass.State, Track.Combined, Discontinuity.Continuous, 0, 100, reversed = false, DateTime.parse("2000-01-01"), None, "test", Some("TEST ROAD 1"), 1, TerminationCode.NoTermination)

  val testRoadway2 = Roadway(NewIdValue, roadwayNumber2, roadNumber1, roadPartNumber2, AdministrativeClass.State, Track.Combined, Discontinuity.Continuous, 100, 200, reversed = false, DateTime.parse("2000-01-01"), None, "test", Some("TEST ROAD 1"), 1, TerminationCode.NoTermination)

  val testRoadway3 = Roadway(NewIdValue, roadwayNumber3, roadNumber2, roadPartNumber1, AdministrativeClass.State, Track.Combined, Discontinuity.Continuous, 0, 100, reversed = false, DateTime.parse("2000-01-01"), None, "test", Some("TEST ROAD 2"), 1, TerminationCode.NoTermination)

  val testLinearLocation1 = LinearLocation(NewIdValue, 1, 1000l.toString, 0.0, 100.0, SideCode.TowardsDigitizing, 10000000000l, (CalibrationPointReference(Some(0l)), CalibrationPointReference.None), Seq(Point(0.0, 0.0), Point(0.0, 100.0)), LinkGeomSource.NormalLinkInterface, roadwayNumber1)

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
      roadways.count(r => r.roadwayNumber == roadwayNumber1) should be(1)
      roadways.count(r => r.roadwayNumber == roadwayNumber2) should be(1)
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
      roadways.count(r => r.roadwayNumber == roadwayNumber1) should be(1)
      roadways.count(r => r.roadwayNumber == roadwayNumber2) should be(1)
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
      roadways.count(r => r.roadwayNumber == roadwayNumber1) should be(1)
      roadways.count(r => r.roadwayNumber == roadwayNumber2) should be(1)
      roadways.size should be(2)
    }
  }

  test("Test fetchAllBySectionsAndTracks When existing road number and existing and non-existing road part numbers Then return roadways") {
    runWithRollback {
      dao.create(List(testRoadway1, testRoadway2, testRoadway3))
      val roadways = dao.fetchAllBySectionsAndTracks(roadNumber1, Set(1l, 2l, -1l, -2l), Set(Track.Combined))
      roadways.count(r => r.roadwayNumber == roadwayNumber1) should be(1)
      roadways.count(r => r.roadwayNumber == roadwayNumber2) should be(1)
      roadways.size should be(2)
    }
  }

  test("Test fetchAllBySectionsAndTracks When existing road number and road part numbers and multiple tracks Then return roadways") {
    runWithRollback {
      dao.create(List(testRoadway1.copy(track = Track.LeftSide), testRoadway2.copy(track = Track.RightSide), testRoadway3))
      val roadways = dao.fetchAllBySectionsAndTracks(roadNumber1, Set(1l, 2l), Set(Track.LeftSide, Track.RightSide))
      roadways.count(r => r.roadwayNumber == roadwayNumber1) should be(1)
      roadways.count(r => r.roadwayNumber == roadwayNumber2) should be(1)
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
      roadways.count(r => r.roadwayNumber == roadwayNumber1) should be(1)
      roadways.count(r => r.roadwayNumber == roadwayNumber2) should be(1)
      roadways.size should be(2)
    }
  }

  test("Test fetchAllByRoadAndPart When existing road and road part number with history Then return roadways") {
    runWithRollback {
      dao.create(List(testRoadway1, testRoadway2.copy(roadPartNumber = roadPartNumber1, endDate = Some(DateTime.now())), testRoadway3))
      val roadwaysWithoutHistory = dao.fetchAllByRoadAndPart(roadNumber1, roadPartNumber1)
      roadwaysWithoutHistory.count(r => r.roadwayNumber == roadwayNumber1) should be(1)
      roadwaysWithoutHistory.size should be(1)
      val roadways = dao.fetchAllByRoadAndPart(roadNumber1, roadPartNumber1, withHistory = true)
      roadways.count(r => r.roadwayNumber == roadwayNumber1) should be(1)
      roadways.count(r => r.roadwayNumber == roadwayNumber2) should be(1)
      roadways.size should be(2)
    }
  }

  test("Test fetchAllByRoadAndPart When existing road and road part number and terminated Then return roadways") {
    runWithRollback {
      dao.create(List(testRoadway1, testRoadway2.copy(roadPartNumber = roadPartNumber1, endDate = Some(DateTime.now()), terminated = TerminationCode.Termination), testRoadway3))
      val roadwaysWithoutHistory = dao.fetchAllByRoadAndPart(roadNumber1, roadPartNumber1)
      roadwaysWithoutHistory.count(r => r.roadwayNumber == roadwayNumber1) should be(1)
      roadwaysWithoutHistory.size should be(1)
      val roadways = dao.fetchAllByRoadAndPart(roadNumber1, roadPartNumber1, withHistory = true)
      roadways.count(r => r.roadwayNumber == roadwayNumber1) should be(1)
      roadways.count(r => r.roadwayNumber == roadwayNumber2) should be(1)
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
      roadways.count(r => r.roadwayNumber == roadwayNumber1) should be(1)
      roadways.count(r => r.roadwayNumber == roadwayNumber2) should be(1)
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
      roadways.count(r => r.roadwayNumber == roadwayNumber1) should be(1)
      roadways.count(r => r.roadwayNumber == roadwayNumber2) should be(1)
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
      roadways.count(r => r.roadwayNumber == roadwayNumber1) should be(1)
      roadways.count(r => r.roadwayNumber == roadwayNumber2) should be(1)
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
      roadways.count(r => r.roadwayNumber == roadwayNumber1) should be(1)
      roadways.count(r => r.roadwayNumber == roadwayNumber2) should be(1)
      roadways.count(r => r.roadwayNumber == roadwayNumber3) should be(1)
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
      roadways.count(r => r.roadwayNumber == roadwayNumber1) should be(1)
      roadways.count(r => r.roadwayNumber == roadwayNumber2) should be(1)
      roadways.size should be(2)
    }
  }

  test("Test fetchAllBySectionTrackAndAddresses When valid values of existing roadways no startAddrM Then return roadways") {
    runWithRollback {
      dao.create(List(testRoadway1, testRoadway2.copy(roadPartNumber = 1l), testRoadway3))
      val roadways = dao.fetchAllBySectionTrackAndAddresses(roadNumber1, 1l, Track.Combined, None, Some(100l))
      roadways.count(r => r.roadwayNumber == roadwayNumber1) should be(1)
      roadways.count(r => r.roadwayNumber == roadwayNumber2) should be(0)
      roadways.size should be(1)
    }
  }

  test("Test fetchAllBySectionTrackAndAddresses When valid values of existing roadways no endAddrM Then return roadways") {
    runWithRollback {
      dao.create(List(testRoadway1, testRoadway2.copy(roadPartNumber = 1l), testRoadway3))
      val roadways = dao.fetchAllBySectionTrackAndAddresses(roadNumber1, 1l, Track.Combined, Some(100l), None)
      roadways.count(r => r.roadwayNumber == roadwayNumber1) should be(0)
      roadways.count(r => r.roadwayNumber == roadwayNumber2) should be(1)
      roadways.size should be(1)
    }
  }

  test("Test fetchAllBySectionTrackAndAddresses When valid values of existing roadways no startAddrM and no endAddrM Then return roadways") {
    runWithRollback {
      dao.create(List(testRoadway1, testRoadway2.copy(roadPartNumber = 1l), testRoadway3))
      val roadways = dao.fetchAllBySectionTrackAndAddresses(roadNumber1, 1l, Track.Combined, None, None)
      roadways.count(r => r.roadwayNumber == roadwayNumber1) should be(1)
      roadways.count(r => r.roadwayNumber == roadwayNumber2) should be(1)
      roadways.size should be(2)
    }
  }

  test("Test fetchAllBySectionTrackAndAddresses When existing road number and road part numbers and multiple tracks Then return roadways") {
    runWithRollback {
      dao.create(List(testRoadway1.copy(track = Track.LeftSide), testRoadway2.copy(roadPartNumber = 1l, track = Track.RightSide), testRoadway3))
      val roadways = dao.fetchAllBySectionTrackAndAddresses(roadNumber1, 1l, Track.LeftSide, Some(0l), Some(100l))
      roadways.count(r => r.roadwayNumber == roadwayNumber1) should be(1)
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
      roadways.count(r => r.roadwayNumber == roadwayNumber1) should be(1)
      roadways.count(r => r.roadwayNumber == roadwayNumber2) should be(1)
      roadways.size should be(2)
    }
  }

  test("Test fetchAllBySectionAndAddresses When valid values of existing roadways no startAddrM Then return roadways") {
    runWithRollback {
      dao.create(List(testRoadway1, testRoadway2.copy(roadPartNumber = 1l), testRoadway3))
      val roadways = dao.fetchAllBySectionAndAddresses(roadNumber1, 1l, None, Some(100l))
      roadways.count(r => r.roadwayNumber == roadwayNumber1) should be(1)
      roadways.count(r => r.roadwayNumber == roadwayNumber2) should be(0)
      roadways.size should be(1)
    }
  }

  test("Test fetchAllBySectionAndAddresses When valid values of existing roadways no endAddrM Then return roadways") {
    runWithRollback {
      dao.create(List(testRoadway1, testRoadway2.copy(roadPartNumber = 1l), testRoadway3))
      val roadways = dao.fetchAllBySectionAndAddresses(roadNumber1, 1l, Some(100l), None)
      roadways.count(r => r.roadwayNumber == roadwayNumber1) should be(0)
      roadways.count(r => r.roadwayNumber == roadwayNumber2) should be(1)
      roadways.size should be(1)
    }
  }

  test("Test fetchAllBySectionAndAddresses When valid values of existing roadways no startAddrM and no endAddrM Then return roadways") {
    runWithRollback {
      dao.create(List(testRoadway1, testRoadway2.copy(roadPartNumber = 1l), testRoadway3))
      val roadways = dao.fetchAllBySectionAndAddresses(roadNumber1, 1l, None, None)
      roadways.count(r => r.roadwayNumber == roadwayNumber1) should be(1)
      roadways.count(r => r.roadwayNumber == roadwayNumber2) should be(1)
      roadways.size should be(2)
    }
  }

  test("Test fetchAllBySectionAndAddresses When existing road number and road part numbers and multiple tracks Then return roadways") {
    runWithRollback {
      dao.create(List(testRoadway1.copy(track = Track.LeftSide), testRoadway2.copy(roadPartNumber = 1l, track = Track.RightSide), testRoadway3))
      val roadways = dao.fetchAllBySectionAndAddresses(roadNumber1, 1l, Some(0l), Some(100l))
      roadways.count(r => r.roadwayNumber == roadwayNumber1) should be(1)
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
      roadways.count(r => r.roadwayNumber == roadwayNumber1) should be(1)
      roadways.count(r => r.roadwayNumber == roadwayNumber2) should be(1)
      roadways.count(r => r.roadwayNumber == roadwayNumber3) should be(1)
    }
  }

  test("Test fetchAllByBetweenDates When existing dates Then return roadways") {
    runWithRollback {
      dao.create(List(testRoadway1, testRoadway2, testRoadway3.copy(startDate = DateTime.parse("2000-01-02"))))
      val roadways = dao.fetchAllByDateRange(DateTime.parse("2000-01-01"), DateTime.parse("2000-01-02"))
      roadways.count(r => r.roadwayNumber == roadwayNumber1) should be(1)
      roadways.count(r => r.roadwayNumber == roadwayNumber2) should be(1)
      roadways.count(r => r.roadwayNumber == roadwayNumber3) should be(1)
    }
  }

  test("Test fetchAllByBetweenDates When existing dates and one too early Then return roadways") {
    runWithRollback {
      dao.create(List(testRoadway1, testRoadway2, testRoadway3.copy(startDate = DateTime.parse("1999-12-31"))))
      val roadways = dao.fetchAllByDateRange(DateTime.parse("2000-01-01"), DateTime.parse("2000-01-01"))
      roadways.count(r => r.roadwayNumber == roadwayNumber1) should be(1)
      roadways.count(r => r.roadwayNumber == roadwayNumber2) should be(1)
      roadways.count(r => r.roadwayNumber == roadwayNumber3) should be(0)
    }
  }

  test("Test fetchAllByBetweenDates When existing dates and one too late Then return roadways") {
    runWithRollback {
      dao.create(List(testRoadway1, testRoadway2, testRoadway3.copy(startDate = DateTime.parse("2000-01-02"))))
      val roadways = dao.fetchAllByDateRange(DateTime.parse("2000-01-01"), DateTime.parse("2000-01-01"))
      roadways.count(r => r.roadwayNumber == roadwayNumber1) should be(1)
      roadways.count(r => r.roadwayNumber == roadwayNumber2) should be(1)
      roadways.count(r => r.roadwayNumber == roadwayNumber3) should be(0)
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

  // create

  test("Test create When insert duplicate roadway Then give error") {
    runWithRollback {
      val error = intercept[SQLException] {
        dao.create(Seq(testRoadway1, testRoadway1))
      }
      error.getMessage should include("""duplicate key value violates unique constraint "roadway_history_i"""")
    }
  }

  test("Test create When insert duplicate roadway with different roadway number Then give error") {
    runWithRollback {
      val error = intercept[SQLException] {
        dao.create(Seq(testRoadway1, testRoadway1.copy(roadwayNumber = roadwayNumber2)))
      }
      error.getMessage should include("""duplicate key value violates unique constraint "roadway_history_i"""")
    }
  }

  test("Test create When insert roadway with termination code 1 but no end date Then give error") {
    runWithRollback {
      val error = intercept[SQLException] {
        dao.create(Seq(testRoadway1.copy(terminated = TerminationCode.Termination)))
      }
      error.getMessage should include("""new row for relation "roadway" violates check constraint "termination_end_date_chk"""")
    }
  }

  test("Test create When insert roadway with termination code 2 but no end date Then give error") {
    runWithRollback {
      val error = intercept[SQLException] {
        dao.create(Seq(testRoadway1.copy(terminated = TerminationCode.Subsequent)))
      }
      error.getMessage should include("""new row for relation "roadway" violates check constraint "termination_end_date_chk"""")
    }
  }

  test("Test create When insert roadway with termination code 1 with end date Then roadway should be inserted") {
    runWithRollback {
      val endDate = Some(DateTime.parse("2001-12-31"))
      dao.create(Seq(testRoadway1.copy(endDate = endDate, terminated = TerminationCode.Termination)))
      val roadway = dao.fetchByRoadwayNumber(roadwayNumber1, includeHistory = true).getOrElse(fail())
      roadway.roadwayNumber should be(roadwayNumber1)
      roadway.terminated should be(TerminationCode.Termination)
      roadway.endDate should be(endDate)
    }
  }

  test("Test create When insert roadway with new roadway number Then roadway should be inserted") {
    runWithRollback {
      dao.create(Seq(testRoadway1.copy(roadwayNumber = NewIdValue)))
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
      roadway.administrativeClass should be(testRoadway1.administrativeClass)
      roadway.ely should be(testRoadway1.ely)
      roadway.terminated should be(testRoadway1.terminated)
      roadway.validFrom should not be None
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
      roadways.count(r => r.roadwayNumber == roadwayNumber1) should be(1)
      roadways.count(r => r.roadwayNumber == roadwayNumber2) should be(1)
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
      roadways.count(r => r.roadwayNumber == roadwayNumber1) should be(1)
      roadways.count(r => r.roadwayNumber == roadwayNumber2) should be(1)
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

  test("Fetching Roadways by number and situation date ignores roadways outside the given date") {
    runWithRollback {
      dao.create(List(testRoadway1.copy(endDate = Some(DateTime.now.plusDays(-1))), testRoadway2.copy(endDate = Some(DateTime.now.plusDays(1)))))
      val roadways = dao.fetchAllByRoadwayNumbers(Set(roadwayNumber1, roadwayNumber2), DateTime.now())
      roadways.size should be(1)
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

  test("Test When fetching track sections for road address browser then form homogenous sections from roadways based on road number, road part number, track, start date, administrative class and ely") {
    /**
      *         |   | 2-tracks
      *         \  /
      *          \/
      *          |
      *          |    1-track
      *         /\
      *        | |    2-tracks
      *        \/
      *        |
      *        |      1-track
      *       /\
      *      /  \
      *     |   |     2-tracks
      */



    runWithRollback {
      val roadNumber = 76
      val roadPartNumber = 1
      val date = "2022-01-01"

      val roadways = Seq(
        Roadway(dao.getNextRoadwayId,	Sequences.nextRoadwayNumber, roadNumber, roadPartNumber, AdministrativeClass.State, Track.LeftSide, Discontinuity.Continuous, 0, 190, reversed = false, DateTime.parse("1992-10-08"), None, "test", Some("TEST ROAD 1"), 8, TerminationCode.NoTermination),
        Roadway(dao.getNextRoadwayId,	Sequences.nextRoadwayNumber, roadNumber, roadPartNumber, AdministrativeClass.State, Track.RightSide, Discontinuity.Continuous, 0, 190, reversed = false, DateTime.parse("1992-10-08"), None, "test", Some("TEST ROAD 1"), 8, TerminationCode.NoTermination),
        Roadway(dao.getNextRoadwayId,	Sequences.nextRoadwayNumber, roadNumber, roadPartNumber, AdministrativeClass.State, Track.LeftSide, Discontinuity.Continuous, 190, 1260, reversed = false, DateTime.parse("1992-10-08"), None, "test", Some("TEST ROAD 1"), 8, TerminationCode.NoTermination),
        Roadway(dao.getNextRoadwayId,	Sequences.nextRoadwayNumber, roadNumber, roadPartNumber, AdministrativeClass.State, Track.RightSide, Discontinuity.Continuous, 190, 1260, reversed = false, DateTime.parse("1992-10-08"), None, "test", Some("TEST ROAD 1"), 8, TerminationCode.NoTermination),
        Roadway(dao.getNextRoadwayId,	Sequences.nextRoadwayNumber, roadNumber, roadPartNumber, AdministrativeClass.State, Track.LeftSide, Discontinuity.Continuous, 1260, 1545, reversed = false, DateTime.parse("1992-10-08"), None, "test", Some("TEST ROAD 1"), 8, TerminationCode.NoTermination),
        Roadway(dao.getNextRoadwayId,	Sequences.nextRoadwayNumber, roadNumber, roadPartNumber, AdministrativeClass.State, Track.RightSide, Discontinuity.Continuous, 1260, 1545, reversed = false, DateTime.parse("1992-10-08"), None, "test", Some("TEST ROAD 1"), 8, TerminationCode.NoTermination),
        Roadway(dao.getNextRoadwayId,	Sequences.nextRoadwayNumber, roadNumber, roadPartNumber, AdministrativeClass.State, Track.RightSide, Discontinuity.Continuous, 1545, 1701, reversed = false, DateTime.parse("2017-01-01"), None, "test", Some("TEST ROAD 1"), 8, TerminationCode.NoTermination),
        Roadway(dao.getNextRoadwayId,	Sequences.nextRoadwayNumber, roadNumber, roadPartNumber, AdministrativeClass.State, Track.LeftSide, Discontinuity.Continuous, 1545, 1701, reversed = false, DateTime.parse("2017-01-01"), None, "test", Some("TEST ROAD 1"), 8, TerminationCode.NoTermination),
        Roadway(dao.getNextRoadwayId,	Sequences.nextRoadwayNumber, roadNumber, roadPartNumber, AdministrativeClass.State, Track.Combined, Discontinuity.Continuous, 1701, 1815, reversed = false, DateTime.parse("2017-01-01"), None, "test", Some("TEST ROAD 1"), 8, TerminationCode.NoTermination),
        Roadway(dao.getNextRoadwayId,	Sequences.nextRoadwayNumber, roadNumber, roadPartNumber, AdministrativeClass.State, Track.Combined, Discontinuity.Continuous, 1815, 2022, reversed = false, DateTime.parse("1992-10-08"), None, "test", Some("TEST ROAD 1"), 8, TerminationCode.NoTermination),
        Roadway(dao.getNextRoadwayId,	Sequences.nextRoadwayNumber, roadNumber, roadPartNumber, AdministrativeClass.State, Track.RightSide, Discontinuity.Continuous, 2022, 2333, reversed = false, DateTime.parse("2017-12-15"), None, "test", Some("TEST ROAD 1"), 8, TerminationCode.NoTermination),
        Roadway(dao.getNextRoadwayId,	Sequences.nextRoadwayNumber, roadNumber, roadPartNumber, AdministrativeClass.State, Track.LeftSide, Discontinuity.Continuous, 2022, 2333, reversed = false, DateTime.parse("2017-12-15"), None, "test", Some("TEST ROAD 1"), 8, TerminationCode.NoTermination),
        Roadway(dao.getNextRoadwayId,	Sequences.nextRoadwayNumber, roadNumber, roadPartNumber, AdministrativeClass.State, Track.Combined, Discontinuity.Continuous, 2333, 2990, reversed = false, DateTime.parse("1992-10-08"), None, "test", Some("TEST ROAD 1"), 8, TerminationCode.NoTermination),
        Roadway(dao.getNextRoadwayId,	Sequences.nextRoadwayNumber, roadNumber, roadPartNumber, AdministrativeClass.State, Track.Combined, Discontinuity.Continuous, 2990, 5061, reversed = false, DateTime.parse("1992-10-08"), None, "test", Some("TEST ROAD 1"), 8, TerminationCode.NoTermination),
        Roadway(dao.getNextRoadwayId,	Sequences.nextRoadwayNumber, roadNumber, roadPartNumber, AdministrativeClass.State, Track.RightSide, Discontinuity.Continuous, 5061, 5239, reversed = false, DateTime.parse("2017-12-01"), None, "test", Some("TEST ROAD 1"), 8, TerminationCode.NoTermination),
        Roadway(dao.getNextRoadwayId,	Sequences.nextRoadwayNumber, roadNumber, roadPartNumber, AdministrativeClass.State, Track.LeftSide, Discontinuity.Continuous, 5061, 5239, reversed = false, DateTime.parse("2017-12-01"), None, "test", Some("TEST ROAD 1"), 8, TerminationCode.NoTermination)
      )

      dao.create(roadways)

      val result = dao.fetchTracksForRoadAddressBrowser(Some(date), None, Some(roadNumber), Some(roadPartNumber), Some(roadPartNumber))

      result.size should be (11)
      val (combinedTrack, twoTrack) = result.partition(row => row.track == 0)
      combinedTrack.size should be (3)
      twoTrack.size should be (8)
      val startAddrMs = result.map(row => row.startAddrM).distinct
      startAddrMs should be (Seq(0, 1545, 1701, 1815, 2022, 2333, 5061))
      val endAddrMs = result.map(row => row.endAddrM).distinct
      endAddrMs should be (Seq(1545, 1701, 1815, 2022, 2333, 5061, 5239))

    }
  }

  test("Test When fetching road parts for road address browser then form road part sections from roadways (i.e. one row for one road part)") {
    /**
      *         |   | 2-tracks
      *         \  /
      *          \/
      *          |
      *          |    1-track
      *         /\
      *        | |    2-tracks
      *        \/
      *        |
      *        |      1-track
      *       /\
      *      /  \
      *     |   |     2-tracks
      */



    runWithRollback {
      val roadNumber = 76
      val roadPartNumber = 1
      val date = "2022-01-01"

      val roadways = Seq(
        Roadway(dao.getNextRoadwayId,	Sequences.nextRoadwayNumber, roadNumber, roadPartNumber, AdministrativeClass.State, Track.LeftSide, Discontinuity.Continuous, 0, 190, reversed = false, DateTime.parse("1992-10-08"), None, "test", Some("TEST ROAD 1"), 8, TerminationCode.NoTermination),
        Roadway(dao.getNextRoadwayId,	Sequences.nextRoadwayNumber, roadNumber, roadPartNumber, AdministrativeClass.State, Track.RightSide, Discontinuity.Continuous, 0, 190, reversed = false, DateTime.parse("1992-10-08"), None, "test", Some("TEST ROAD 1"), 8, TerminationCode.NoTermination),
        Roadway(dao.getNextRoadwayId,	Sequences.nextRoadwayNumber, roadNumber, roadPartNumber, AdministrativeClass.State, Track.LeftSide, Discontinuity.Continuous, 190, 1260, reversed = false, DateTime.parse("1992-10-08"), None, "test", Some("TEST ROAD 1"), 8, TerminationCode.NoTermination),
        Roadway(dao.getNextRoadwayId,	Sequences.nextRoadwayNumber, roadNumber, roadPartNumber, AdministrativeClass.State, Track.RightSide, Discontinuity.Continuous, 190, 1260, reversed = false, DateTime.parse("1992-10-08"), None, "test", Some("TEST ROAD 1"), 8, TerminationCode.NoTermination),
        Roadway(dao.getNextRoadwayId,	Sequences.nextRoadwayNumber, roadNumber, roadPartNumber, AdministrativeClass.State, Track.LeftSide, Discontinuity.Continuous, 1260, 1545, reversed = false, DateTime.parse("1992-10-08"), None, "test", Some("TEST ROAD 1"), 8, TerminationCode.NoTermination),
        Roadway(dao.getNextRoadwayId,	Sequences.nextRoadwayNumber, roadNumber, roadPartNumber, AdministrativeClass.State, Track.RightSide, Discontinuity.Continuous, 1260, 1545, reversed = false, DateTime.parse("1992-10-08"), None, "test", Some("TEST ROAD 1"), 8, TerminationCode.NoTermination),
        Roadway(dao.getNextRoadwayId,	Sequences.nextRoadwayNumber, roadNumber, roadPartNumber, AdministrativeClass.State, Track.RightSide, Discontinuity.Continuous, 1545, 1701, reversed = false, DateTime.parse("2017-01-01"), None, "test", Some("TEST ROAD 1"), 8, TerminationCode.NoTermination),
        Roadway(dao.getNextRoadwayId,	Sequences.nextRoadwayNumber, roadNumber, roadPartNumber, AdministrativeClass.State, Track.LeftSide, Discontinuity.Continuous, 1545, 1701, reversed = false, DateTime.parse("2017-01-01"), None, "test", Some("TEST ROAD 1"), 8, TerminationCode.NoTermination),
        Roadway(dao.getNextRoadwayId,	Sequences.nextRoadwayNumber, roadNumber, roadPartNumber, AdministrativeClass.State, Track.Combined, Discontinuity.Continuous, 1701, 1815, reversed = false, DateTime.parse("2017-01-01"), None, "test", Some("TEST ROAD 1"), 8, TerminationCode.NoTermination),
        Roadway(dao.getNextRoadwayId,	Sequences.nextRoadwayNumber, roadNumber, roadPartNumber, AdministrativeClass.State, Track.Combined, Discontinuity.Continuous, 1815, 2022, reversed = false, DateTime.parse("1992-10-08"), None, "test", Some("TEST ROAD 1"), 8, TerminationCode.NoTermination),
        Roadway(dao.getNextRoadwayId,	Sequences.nextRoadwayNumber, roadNumber, roadPartNumber, AdministrativeClass.State, Track.RightSide, Discontinuity.Continuous, 2022, 2333, reversed = false, DateTime.parse("2017-12-15"), None, "test", Some("TEST ROAD 1"), 8, TerminationCode.NoTermination),
        Roadway(dao.getNextRoadwayId,	Sequences.nextRoadwayNumber, roadNumber, roadPartNumber, AdministrativeClass.State, Track.LeftSide, Discontinuity.Continuous, 2022, 2333, reversed = false, DateTime.parse("2017-12-15"), None, "test", Some("TEST ROAD 1"), 8, TerminationCode.NoTermination),
        Roadway(dao.getNextRoadwayId,	Sequences.nextRoadwayNumber, roadNumber, roadPartNumber, AdministrativeClass.State, Track.Combined, Discontinuity.Continuous, 2333, 2990, reversed = false, DateTime.parse("1992-10-08"), None, "test", Some("TEST ROAD 1"), 8, TerminationCode.NoTermination),
        Roadway(dao.getNextRoadwayId,	Sequences.nextRoadwayNumber, roadNumber, roadPartNumber, AdministrativeClass.State, Track.Combined, Discontinuity.Continuous, 2990, 5061, reversed = false, DateTime.parse("1992-10-08"), None, "test", Some("TEST ROAD 1"), 8, TerminationCode.NoTermination),
        Roadway(dao.getNextRoadwayId,	Sequences.nextRoadwayNumber, roadNumber, roadPartNumber, AdministrativeClass.State, Track.RightSide, Discontinuity.Continuous, 5061, 5239, reversed = false, DateTime.parse("2017-12-01"), None, "test", Some("TEST ROAD 1"), 8, TerminationCode.NoTermination),
        Roadway(dao.getNextRoadwayId,	Sequences.nextRoadwayNumber, roadNumber, roadPartNumber, AdministrativeClass.State, Track.LeftSide, Discontinuity.Continuous, 5061, 5239, reversed = false, DateTime.parse("2017-12-01"), None, "test", Some("TEST ROAD 1"), 8, TerminationCode.NoTermination)
      )

      dao.create(roadways)

      val result = dao.fetchRoadPartsForRoadAddressBrowser(Some(date), None, Some(roadNumber), Some(roadPartNumber), Some(roadPartNumber))
      result.size should be (1)                               // one road part should always return one result row
      result.head shouldBe a [RoadPartForRoadAddressBrowser]
      val startAddrM = result.head.startAddrM
      startAddrM should be (0)                                // max endAddrM - min startAddrM
      val endAddrM = result.head.endAddrM
      endAddrM should be (5239)                               // max endAddrM
      val startDate = result.head.startDate
      startDate should be (DateTime.parse("2017-12-15")) // latest date of all of the roadways on the road part

    }
  }
  
  test("Test When fetching road part history or track history for road address browser then return history information") {
    runWithRollback {
      val roadNumber = 76
      val roadPartNumber = 1
      val roadwayNumber = Sequences.nextRoadwayNumber
      val roadwayNumber2 = Sequences.nextRoadwayNumber
      val rwHistoryRowStartDate = "1992-10-08"
      val rwHistoryRowEndDate = "2022-11-14"
      val rwCurrentRowStartDate = "2022-11-15"
      val afterChangesSituationDate = "2022-11-15"
      val historyChangesSituationDate = "2022-11-14"

      /**
        *                   Before changes
        *
        *          roadway1 history
        * -------------------------------->
        * 0                             2080
        *
        *                   After changes
        *
        *          roadway1                       roadway2
        * -------------------------------->----------------------->
        * 0                             2080                    2200
        * */

      // history road part that is 2080 meters long
      val roadway1HistoryRow = Roadway(dao.getNextRoadwayId,	roadwayNumber, roadNumber, roadPartNumber, AdministrativeClass.State, Track.Combined, Discontinuity.Discontinuous, 0, 2080, reversed = false, DateTime.parse(rwHistoryRowStartDate), Some(DateTime.parse(rwHistoryRowEndDate)), "test", Some("TEST ROAD 1"), 8, TerminationCode.NoTermination)

      // current road part that is 2200 meters long
      val roadway1 = Roadway(dao.getNextRoadwayId,	roadwayNumber, roadNumber, roadPartNumber, AdministrativeClass.State, Track.Combined, Discontinuity.Continuous, 0, 2080, reversed = false, DateTime.parse(rwCurrentRowStartDate), None, "test", Some("TEST ROAD 1"), 8, TerminationCode.NoTermination)
      val roadway2 = Roadway(dao.getNextRoadwayId,	roadwayNumber2, roadNumber, roadPartNumber, AdministrativeClass.State, Track.Combined, Discontinuity.Discontinuous, 2080, 2200, reversed = false, DateTime.parse(rwCurrentRowStartDate), None, "test", Some("TEST ROAD 1"), 8, TerminationCode.NoTermination)

      dao.create(Seq(roadway1HistoryRow,roadway1,roadway2))

      // situation date after changes
      val resultForRoadParts = dao.fetchRoadPartsForRoadAddressBrowser(Some(afterChangesSituationDate), None, Some(roadNumber), Some(roadPartNumber), Some(roadPartNumber))
      resultForRoadParts.size should be (1) // a single line per (the whole) road part
      resultForRoadParts.head shouldBe a [RoadPartForRoadAddressBrowser]
      resultForRoadParts.head.endAddrM should be (2200)

      // situation date before changes
      val historyResultForRoadPart = dao.fetchRoadPartsForRoadAddressBrowser(Some(historyChangesSituationDate), None, Some(roadNumber), Some(roadPartNumber), Some(roadPartNumber))
      historyResultForRoadPart.size should be (1)
      historyResultForRoadPart.head shouldBe a [RoadPartForRoadAddressBrowser]
      historyResultForRoadPart.head.endAddrM should be (2080)

      // situation date after changes
      val resultForTrack = dao.fetchTracksForRoadAddressBrowser(Some(afterChangesSituationDate), None, Some(roadNumber), Some(roadPartNumber), Some(roadPartNumber))
      resultForTrack.size should be (1)
      resultForTrack.head shouldBe a [TrackForRoadAddressBrowser]
      resultForTrack.head.endAddrM should be (2200)

      // situation date before changes
      val historyResultTrack = dao.fetchTracksForRoadAddressBrowser(Some(historyChangesSituationDate), None, Some(roadNumber), Some(roadPartNumber), Some(roadPartNumber))
      historyResultTrack.size should be (1)
      historyResultTrack.head shouldBe a [TrackForRoadAddressBrowser]
      historyResultTrack.head.endAddrM should be (2080)
    }
  }

}

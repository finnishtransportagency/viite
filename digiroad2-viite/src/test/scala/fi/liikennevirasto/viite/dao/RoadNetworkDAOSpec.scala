package fi.liikennevirasto.viite.dao

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


class RoadNetworkDAOSpec extends FunSuite with Matchers {

  def runWithRollback(f: => Unit): Unit = {
    Database.forDataSource(OracleDatabase.ds).withDynTransaction {
      f
      dynamicSession.rollback()
    }
  }

  val dao = new RoadNetworkDAO
  val roadwayDAO = new RoadwayDAO

  private val roadNumber1 = 990
  private val roadNumber2 = 993

  private val roadwayNumber1 = 1000000000l
  private val roadwayNumber2 = 2000000000l
  private val roadwayNumber3 = 3000000000l

  val testRoadway1 = Roadway(NewRoadway, roadwayNumber1, roadNumber1, 1, RoadType.PublicRoad, Track.Combined, Discontinuity.Continuous,
    0, 100, false, DateTime.parse("2000-01-01"), None, "test", Some("TEST ROAD 1"), 1, TerminationCode.NoTermination)

  val testRoadway2 = Roadway(NewRoadway, roadwayNumber2, roadNumber1, 2, RoadType.PublicRoad, Track.Combined, Discontinuity.Continuous,
    100, 200, false, DateTime.parse("2000-01-01"), None, "test", Some("TEST ROAD 1"), 1, TerminationCode.NoTermination)

  val testRoadway3 = Roadway(NewRoadway, roadwayNumber3, roadNumber2, 1, RoadType.PublicRoad, Track.Combined, Discontinuity.Continuous,
    0, 100, false, DateTime.parse("2000-01-01"), None, "test", Some("TEST ROAD 2"), 1, TerminationCode.NoTermination)

  // createPublishedRoadNetwork

  test("Test createPublishedRoadNetwork When creating new Then the latest values change") {
    runWithRollback {
      val previousRoadNetworkId = dao.getLatestRoadNetworkVersionId.getOrElse(-1l)
      val previousPublishedNetworkDate = dao.getLatestPublishedNetworkDate.getOrElse(DateTime.parse("2000-01-01"))
      dao.createPublishedRoadNetwork
      dao.getLatestRoadNetworkVersionId.getOrElse(fail) should not be previousRoadNetworkId
      dao.getLatestPublishedNetworkDate.getOrElse(fail) should not be previousPublishedNetworkDate
    }
  }

  // expireRoadNetwork

  test("Test expireRoadNetwork When expiring Then the latest id changes") {
    runWithRollback {
      dao.expireRoadNetwork
      dao.createPublishedRoadNetwork
      val idBefore = dao.getLatestRoadNetworkVersionId.getOrElse(fail)
      dao.expireRoadNetwork
      dao.getLatestRoadNetworkVersionId.getOrElse(-1) should not be idBefore
    }
  }

  // createPublishedRoadway

  test("Test createPublishedRoadway When create two published roadways Then RoadwayDAO.fetchAllByRoadwayNumbers should return only these two") {
    runWithRollback {
      dao.expireRoadNetwork
      dao.createPublishedRoadNetwork
      val networkId = dao.getLatestRoadNetworkVersionId.getOrElse(fail)
      val roadwayId1 = Sequences.nextRoadwayId
      val roadwayId2 = Sequences.nextRoadwayId
      val roadwayId3 = Sequences.nextRoadwayId
      roadwayDAO.create(List(testRoadway1.copy(id = roadwayId1), testRoadway2.copy(id = roadwayId2), testRoadway3.copy(id = roadwayId3)))
      dao.createPublishedRoadway(networkId, roadwayId1)
      dao.createPublishedRoadway(networkId, roadwayId2)
      val roadways = roadwayDAO.fetchAllByRoadwayNumbers(Set(roadwayNumber1, roadwayNumber2), networkId)
      roadways.filter(r => r.roadwayNumber == roadwayNumber1).size should be(1)
      roadways.filter(r => r.roadwayNumber == roadwayNumber2).size should be(1)
      roadways.size should be(2)
    }
  }

  // addRoadNetworkError

  private def addLinearLocationAndRoadNetworkError(roadwayId: Long, linearLocationId: Long, linkId: Long, addressError: AddressError) = {
    val linearLocationDAO = new LinearLocationDAO
    val roadnetworkDAO = new RoadNetworkDAO
    val linearLocation = LinearLocation(linearLocationId, 1, linkId, 0.0, 100.0, SideCode.TowardsDigitizing, 10000000000l,
      (Some(0l), None), FloatingReason.NoFloating, Seq(Point(0.0, 0.0), Point(0.0, 100.0)), LinkGeomSource.NormalLinkInterface,
      roadwayId)
    linearLocationDAO.create(List(linearLocation))
    dao.addRoadNetworkError(roadwayId, linearLocationId, addressError, roadnetworkDAO.getLatestRoadNetworkVersionId)
  }

  private def expireAndAddRoadNetworkError(roadwayId: Long, linearLocationId: Long, linkId: Long, addressError: AddressError) = {
    dao.expireRoadNetwork
    dao.createPublishedRoadNetwork
    roadwayDAO.create(List(testRoadway1.copy(id = roadwayId)))
    addLinearLocationAndRoadNetworkError(roadwayId, linearLocationId, linkId, addressError)
  }

  test("Test addRoadNetworkError When add one error Then getRoadNetworkErrors should return one error with the correct values") {
    runWithRollback {
      val roadwayId = Sequences.nextRoadwayId
      val linearLocationId = Sequences.nextLinearLocationId
      val linkId = 1000l
      val addressError = AddressError.InconsistentTopology
      expireAndAddRoadNetworkError(roadwayId, linearLocationId, linkId, addressError)
      val errors = dao.getRoadNetworkErrors(roadwayId, addressError)
      errors.size should be(1)
      errors.head.roadwayId should be(roadwayId)
      errors.head.network_version.getOrElse(fail) should be > 0l
      errors.head.linearLocationId should be(linearLocationId)
      errors.head.error should be(addressError)
    }
  }

  // removeNetworkErrors

  test("Test removeNetworkErrors When one error Then should be removed") {
    runWithRollback {
      val error = AddressError.InconsistentTopology
      expireAndAddRoadNetworkError(Sequences.nextRoadwayId, Sequences.nextLinearLocationId, 1000l, error)
      val errors = dao.getRoadNetworkErrors(error)
      errors.size should be(1)
      dao.removeNetworkErrors
      val errors2 = dao.getRoadNetworkErrors(error)
      errors2.size should be(0)
    }
  }

  // hasRoadNetworkErrors

  test("Test hasRoadNetworkErrors When no errors Then should return false") {
    runWithRollback {
      dao.removeNetworkErrors
      dao.hasRoadNetworkErrors should be(false)
    }
  }

  test("Test hasRoadNetworkErrors When there are errors Then should return true") {
    runWithRollback {
      expireAndAddRoadNetworkError(Sequences.nextRoadwayId, Sequences.nextLinearLocationId, 1000l, AddressError.InconsistentTopology)
      dao.hasRoadNetworkErrors should be(true)
    }
  }

  // getLatestRoadNetworkVersionId

  test("Test getLatestRoadNetworkVersionId When there is no Then should return None") {
    runWithRollback {
      dao.expireRoadNetwork
      dao.getLatestRoadNetworkVersionId should be(None)
    }
  }

  test("Test getLatestRoadNetworkVersionId When there is Then should return id") {
    runWithRollback {
      dao.createPublishedRoadNetwork
      dao.getLatestRoadNetworkVersionId.getOrElse(fail) should be > 0l
    }
  }

  test("Test getLatestRoadNetworkVersionId When there is only expired Then should return None") {
    runWithRollback {
      dao.createPublishedRoadNetwork
      dao.expireRoadNetwork
      dao.getLatestRoadNetworkVersionId should be(None)
    }
  }

  // getLatestPublishedNetworkDate

  test("Test getLatestPublishedNetworkDate When no published network Then return None") {
    runWithRollback {
      dao.expireRoadNetwork
      dao.getLatestPublishedNetworkDate.isEmpty should be(true)
    }
  }

  test("Test getLatestPublishedNetworkDate When there is published network Then return date") {
    runWithRollback {
      dao.createPublishedRoadNetwork
      val date = dao.getLatestPublishedNetworkDate
      date.isEmpty should be(false)
      date.get.isAfter(DateTime.now().minusHours(24))
    }
  }

  // getRoadNetworkErrors

  test("Test getRoadNetworkErrors When searching with non-existing id Then should return None") {
    runWithRollback {
      val roadwayId = Sequences.nextRoadwayId
      val linearLocationId = Sequences.nextLinearLocationId
      val linkId = 1000l
      val addressError = AddressError.InconsistentTopology
      expireAndAddRoadNetworkError(roadwayId, linearLocationId, linkId, addressError)
      val errors = dao.getRoadNetworkErrors(-9999l, addressError)
      errors.size should be(0)
    }
  }

  test("Test getRoadNetworkErrors When searching with non-existing error Then should return None") {
    runWithRollback {
      val roadwayId = Sequences.nextRoadwayId
      val linearLocationId = Sequences.nextLinearLocationId
      val linkId = 1000l
      val addressError = AddressError.InconsistentTopology
      expireAndAddRoadNetworkError(roadwayId, linearLocationId, linkId, addressError)
      val errors = dao.getRoadNetworkErrors(roadwayId, AddressError.InconsistentLrmHistory)
      errors.size should be(0)
    }
  }

  test("Test getRoadNetworkErrors When searching with existing id and error Then should return network error") {
    runWithRollback {
      val roadwayId = Sequences.nextRoadwayId
      val linearLocationId = Sequences.nextLinearLocationId
      val linkId = 1000l
      val addressError = AddressError.InconsistentTopology
      expireAndAddRoadNetworkError(roadwayId, linearLocationId, linkId, addressError)
      val errors = dao.getRoadNetworkErrors(roadwayId, addressError)
      errors.size should be(1)
      val error = errors.head
      error.error should be(addressError)
      error.error_timestamp should be > 0l
      error.id should be > 0l
      error.network_version.getOrElse(fail) should be > 0l
      error.roadwayId should be(roadwayId)
    }
  }

  test("Test getRoadNetworkErrors When searching error in roadway that has multiple linear locations Then should return multiple network errors") {
    runWithRollback {
      val roadwayId = Sequences.nextRoadwayId
      val linearLocationId1 = Sequences.nextLinearLocationId
      val linearLocationId2 = Sequences.nextLinearLocationId
      val linkId1 = 1000l
      val linkId2 = 2000l
      val addressError = AddressError.InconsistentTopology
      expireAndAddRoadNetworkError(roadwayId, linearLocationId1, linkId1, addressError)
      addLinearLocationAndRoadNetworkError(roadwayId, linearLocationId2, linkId2, addressError)
      val errors = dao.getRoadNetworkErrors(roadwayId, addressError)
      errors.size should be(2)
      val error1 = errors.filter(e => e.linearLocationId == linearLocationId1).head
      error1.error should be(addressError)
      error1.error_timestamp should be > 0l
      error1.id should be > 0l
      error1.network_version.getOrElse(fail) should be > 0l
      error1.roadwayId should be(roadwayId)
      error1.linearLocationId should be(linearLocationId1)
      val error2 = errors.filter(e => e.linearLocationId == linearLocationId2).head
      error2.error should be(addressError)
      error2.error_timestamp should be > 0l
      error2.id should be > 0l
      error2.id should not be (error1.id)
      error2.network_version.getOrElse(fail) should be(error1.network_version.getOrElse(fail))
      error2.roadwayId should be(roadwayId)
      error2.linearLocationId should be(linearLocationId2)
    }
  }

}

package fi.vaylavirasto.viite.dao

import fi.vaylavirasto.viite.geometry.{BoundingRectangle, Point}
import fi.vaylavirasto.viite.model.{AdministrativeClass, LifecycleStatus, LinkGeomSource}
import fi.vaylavirasto.viite.postgis.PostGISDatabaseScalikeJDBC
import fi.vaylavirasto.viite.postgis.PostGISDatabaseScalikeJDBC.runWithRollback
import org.joda.time.DateTime
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalikejdbc._
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext}


class ComplementaryLinkDAOSpec extends AnyFunSuite with Matchers with BaseDAO {

  def runWithTransaction[T](f: => T): T = PostGISDatabaseScalikeJDBC.runWithTransaction(f)

  implicit val ec: ExecutionContext = ExecutionContext.global

  val complementaryLinkDAO = new ComplementaryLinkDAO

  private val testLinkId1 = "test-link-1"
  private val testLinkId2 = "test-link-2"
  private val testLinkId3 = "test-link-3"
  private val testMunicipality1 = 123
  private val testMunicipality2 = 456
  private val testRoadNumber1 = 10
  private val testPartNumber1 = 1

  private def createTestGeometry(startX: Double = 100.0, startY: Double = 200.0): Seq[Point] = {
    Seq(
      Point(startX, startY),
      Point(startX + 50.0, startY + 50.0),
      Point(startX + 100.0, startY + 100.0)
    )
  }

  private def createTestComplementaryLink(
                                           id: String,
                                           municipalityCode: Int = testMunicipality1,
                                           roadNumber: Int = testRoadNumber1,
                                           roadPartNumber: Int = testPartNumber1,
                                           adminClass: Int = 1,
                                           lifecycleStatus: Int = 1,
                                           roadClass: Int = 12314, // walkway
                                           geometry: Seq[Point] = createTestGeometry()
                                         ): ComplementaryLink = {
    val now = DateTime.now()
    ComplementaryLink(
      id = id,
      datasource = 1,
      adminclass = adminClass,
      municipalitycode = municipalityCode,
      featureclass = 1,
      roadclass = roadClass,
      roadnamefin = Some("Test Road"),
      roadnameswe = Some("Test Väg"),
      roadnamesme = None,
      roadnamesmn = None,
      roadnamesms = None,
      roadnumber = roadNumber,
      roadpartnumber = roadPartNumber,
      surfacetype = 1,
      lifecyclestatus = lifecycleStatus,
      directiontype = 1,
      surfacerelation = 1,
      xyaccuracy = 1.0,
      zaccuracy = 1.0,
      horizontallength = 150.0, // approximate length of test geometry
      addressfromleft = 0,
      addresstoleft = 100,
      addressfromright = 0,
      addresstoright = 100,
      starttime = now,
      versionstarttime = now,
      sourcemodificationtime = now,
      geometry = geometry,
      ajorata = 0,
      vvh_id = s"vvh-$id"
    )
  }

  test("Test create When creating a complementary link Then should successfully insert into database") {
    runWithRollback {
      val testLink = createTestComplementaryLink(testLinkId1)

      val result = complementaryLinkDAO.create(testLink)
      result should be > 0L

      // Verify the link was created by fetching it
      val fetchedLink = complementaryLinkDAO.fetchByLinkId(testLinkId1)
      fetchedLink should be(defined)
      fetchedLink.get.linkId should be(testLinkId1)
      fetchedLink.get.municipalityCode should be(testMunicipality1)
    }
  }

  test("Test fetchByLinkId When fetching existing link Then should return the correct RoadLink") {
    runWithRollback {
      val testLink = createTestComplementaryLink(testLinkId1)
      complementaryLinkDAO.create(testLink)

      val result = complementaryLinkDAO.fetchByLinkId(testLinkId1)

      result should be(defined)
      result.get.linkId should be(testLinkId1)
      result.get.administrativeClass should be(AdministrativeClass.State)
      result.get.lifecycleStatus should be(LifecycleStatus.Planned)
      result.get.linkSource should be(LinkGeomSource.ComplementaryLinkInterface)
      result.get.geometry should have size 3
    }
  }

  test("Test fetchByLinkId When fetching non-existing link Then should return None") {
    runWithRollback {
      val result = complementaryLinkDAO.fetchByLinkId("non-existing-link")
      result should be(None)
    }
  }

  test("Test fetchByLinkIds When fetching multiple existing links Then should return correct RoadLinks") {
    runWithRollback {
      val testLink1 = createTestComplementaryLink(testLinkId1, geometry = createTestGeometry())
      val testLink2 = createTestComplementaryLink(testLinkId2, geometry = createTestGeometry(300.0, 400.0))
      val testLink3 = createTestComplementaryLink(testLinkId3, geometry = createTestGeometry(500.0, 600.0))

      complementaryLinkDAO.create(testLink1)
      complementaryLinkDAO.create(testLink2)
      complementaryLinkDAO.create(testLink3)

      val result = complementaryLinkDAO.fetchByLinkIds(Set(testLinkId1, testLinkId2))

      result should have size 2
      result.map(_.linkId) should contain allOf(testLinkId1, testLinkId2)
      result.map(_.linkId) should not contain testLinkId3
    }
  }

  test("Test fetchByLinkIds When fetching with empty set Then should return empty list") {
    runWithRollback {
      val result = complementaryLinkDAO.fetchByLinkIds(Set.empty)
      result should be(empty)
    }
  }

  test("Test fetchByLinkIdsF When using Future version Then should return correct results asynchronously") {
    // Note: Future methods need to be tested outside of rollback transaction
    // as they execute in different transaction contexts

    val testLink1 = createTestComplementaryLink(testLinkId1 + "-future")
    val testLink2 = createTestComplementaryLink(testLinkId2 + "-future")

    try {
      runWithTransaction {
        complementaryLinkDAO.create(testLink1)
        complementaryLinkDAO.create(testLink2)
      }

      val futureResult = complementaryLinkDAO.fetchByLinkIdsF(Set(testLink1.id, testLink2.id))
      val result = Await.result(futureResult, 10.seconds)

      result should have size 2
      result.map(_.linkId) should contain allOf(testLink1.id, testLink2.id)

    } finally {
      // Clean up test data
      runWithTransaction {
        runUpdateToDb(sql"DELETE FROM complementary_link_table WHERE id = ${testLink1.id}")
        runUpdateToDb(sql"DELETE FROM complementary_link_table WHERE id = ${testLink2.id}")
      }
    }
  }

  test("Test queryByMunicipality When querying by municipality Then should return only links from that municipality") {
    runWithRollback {
      val testLink1 = createTestComplementaryLink(testLinkId1, testMunicipality1)
      val testLink2 = createTestComplementaryLink(testLinkId2, testMunicipality1)
      val testLink3 = createTestComplementaryLink(testLinkId3, testMunicipality2)

      complementaryLinkDAO.create(testLink1)
      complementaryLinkDAO.create(testLink2)
      complementaryLinkDAO.create(testLink3)

      val result = complementaryLinkDAO.queryByMunicipality(testMunicipality1)

      result should have size 2
      result.map(_.linkId) should contain allOf(testLinkId1, testLinkId2)
      result.map(_.linkId) should not contain testLinkId3
      result.foreach(_.municipalityCode should be(testMunicipality1))
    }
  }

  test("Test fetchComplementaryByMunicipalitiesF When using Future version Then should return correct results asynchronously") {
    // Note: Future methods need to be tested outside of rollback transaction
    val testLink1 = createTestComplementaryLink(testLinkId1 + "-future-muni1", testMunicipality1)
    val testLink2 = createTestComplementaryLink(testLinkId2 + "-future-muni2", testMunicipality2)

    try {
      runWithTransaction {
        complementaryLinkDAO.create(testLink1)
        complementaryLinkDAO.create(testLink2)
      }

      val futureResult = complementaryLinkDAO.fetchComplementaryByMunicipalitiesF(testMunicipality1)
      val result = Await.result(futureResult, 10.seconds)

      result.size should be >= 1
      result.exists(_.linkId == testLink1.id) should be(true)
      result.foreach(_.municipalityCode should be(testMunicipality1))
    } finally {
      // Clean up test data
      runWithTransaction {
        runUpdateToDb(sql"DELETE FROM complementary_link_table WHERE id = ${testLink1.id}")
        runUpdateToDb(sql"DELETE FROM complementary_link_table WHERE id = ${testLink2.id}")
      }
    }
  }

  test("Test queryByMunicipalitiesAndBounds When querying by bounding box and municipalities Then should return links within bounds") {
    runWithRollback {
      // Create links with different geometries
      val geometry1 = createTestGeometry() // Inside bounds
      val geometry2 = createTestGeometry(150.0, 250.0) // Inside bounds
      val geometry3 = createTestGeometry(500.0, 600.0) // Outside bounds

      val testLink1 = createTestComplementaryLink(testLinkId1, testMunicipality1, geometry = geometry1)
      val testLink2 = createTestComplementaryLink(testLinkId2, testMunicipality1, geometry = geometry2)
      val testLink3 = createTestComplementaryLink(testLinkId3, testMunicipality1, geometry = geometry3)

      complementaryLinkDAO.create(testLink1)
      complementaryLinkDAO.create(testLink2)
      complementaryLinkDAO.create(testLink3)

      val bounds = BoundingRectangle(Point(50.0, 150.0), Point(300.0, 350.0))
      val municipalities = Set(testMunicipality1)
      val result = complementaryLinkDAO.queryByMunicipalitiesAndBounds(bounds, municipalities, None)

      result should have size 2
      result.map(_.linkId) should contain allOf(testLinkId1, testLinkId2)
      result.map(_.linkId) should not contain testLinkId3
    }
  }

  test("Test fetchByBoundsAndMunicipalitiesF When using Future version Then should return correct results asynchronously") {
    // Note: Future methods need to be tested outside of rollback transaction
    val geometry1 = createTestGeometry()
    val testLink1 = createTestComplementaryLink(testLinkId1 + "-future-bounds", testMunicipality1, geometry = geometry1)

    try {
      runWithTransaction(complementaryLinkDAO.create(testLink1))

      val bounds = BoundingRectangle(Point(50.0, 150.0), Point(300.0, 350.0))
      val municipalities = Set(testMunicipality1)
      val futureResult = complementaryLinkDAO.fetchByBoundsAndMunicipalitiesF(bounds, municipalities)
      val result = Await.result(futureResult, 10.seconds)

      result.size should be >= 1
      result.exists(_.linkId == testLink1.id) should be(true)

    } finally {
      // Clean up test data
      runWithTransaction(runUpdateToDb(sql"DELETE FROM complementary_link_table WHERE id = ${testLink1.id}"))
    }
  }

  test("Test extractModifiedAt When all timestamps are provided Then should return the latest timestamp") {
    runWithRollback {
      val startTime = DateTime.parse("2020-01-01")
      val versionTime = DateTime.parse("2021-01-01")
      val modificationTime = DateTime.parse("2022-01-01")

      val attributes = Map(
        "starttime" -> Some(startTime),
        "versionstarttime" -> Some(versionTime),
        "sourcemodificationtime" -> Some(modificationTime)
      )

      val result = complementaryLinkDAO.extractModifiedAt(attributes)

      result should be(defined)
      result.get should be(modificationTime)
    }
  }

  test("Test extractModifiedAt When only starttime is provided Then should return starttime") {
    runWithRollback {
      val startTime = DateTime.parse("2020-01-01")

      val attributes = Map(
        "starttime" -> Some(startTime),
        "versionstarttime" -> None,
        "sourcemodificationtime" -> None
      )

      val result = complementaryLinkDAO.extractModifiedAt(attributes)

      result should be(defined)
      result.get should be(startTime)
    }
  }

}

package fi.liikennevirasto.viite.dao

import java.sql.SQLIntegrityConstraintViolationException

import fi.liikennevirasto.digiroad2.asset.LinkGeomSource
import fi.liikennevirasto.digiroad2.asset.SideCode.TowardsDigitizing
import fi.liikennevirasto.digiroad2.{DigiroadEventBus, GeometryUtils, Point}
import fi.liikennevirasto.digiroad2.dao.Sequences
import fi.liikennevirasto.digiroad2.oracle.OracleDatabase
import fi.liikennevirasto.digiroad2.service.RoadLinkService
import fi.liikennevirasto.digiroad2.util.Track
import fi.liikennevirasto.viite.{NewRoadway, RoadType}
import fi.liikennevirasto.viite.dao.FloatingReason.NoFloating
import fi.liikennevirasto.viite.process.RoadwayAddressMapper
import org.joda.time.DateTime
import org.scalatest.mock.MockitoSugar
import org.scalatest.{FunSuite, Matchers}
import slick.driver.JdbcDriver.backend.Database
import slick.driver.JdbcDriver.backend.Database.dynamicSession
import slick.jdbc.StaticQuery.interpolation

/**
  * Class to test DB trigger that does not allow reserving already reserved parts to project
  */
class ProjectReservedPartDAOSpec extends FunSuite with Matchers {
  val mockRoadLinkService = MockitoSugar.mock[RoadLinkService]
  val mockEventBus = MockitoSugar.mock[DigiroadEventBus]
  val mockRoadwayAddressMapper = MockitoSugar.mock[RoadwayAddressMapper]
  val mockLinearLocationDAO = MockitoSugar.mock[LinearLocationDAO]
  val mockRoadwayDAO = MockitoSugar.mock[RoadwayDAO]
  val mockRoadNetworkDAO = MockitoSugar.mock[RoadNetworkDAO]

  def runWithRollback(f: => Unit): Unit = {
    // Prevent deadlocks in DB because we create and delete links in tests and don't handle the project ids properly
    // TODO: create projects with unique ids so we don't get into transaction deadlocks in tests
    Database.forDataSource(OracleDatabase.ds).withDynTransaction {
      f
      dynamicSession.rollback()
    }
  }
  val roadwayDAO = new RoadwayDAO
  val projectDAO = new ProjectDAO
  val projectLinkDAO = new ProjectLinkDAO
  val projectReservedPartDAO = new ProjectReservedPartDAO

  private val roadNumber1 = 5
  private val roadNumber2 = 6

  private val roadPartNumber1 = 1
  private val roadPartNumber2 = 2

  private val roadwayNumber1 = 1000000000l
  private val roadwayNumber2 = 2000000000l
  private val roadwayNumber3 = 3000000000l

  private val linkId1 = 1000l
  private val linkId2 = 2000l
  private val linkId3 = 3000l

  private val linearLocationId = 0

  private def dummyRoadAddressProject(id: Long, status: ProjectState, reservedParts: Seq[ProjectReservedPart] = List.empty[ProjectReservedPart], ely: Option[Long] = None, coordinates: Option[ProjectCoordinates] = None): Project ={
    Project(id, status, "testProject", "testUser", DateTime.parse("1901-01-01"), "testUser", DateTime.parse("1901-01-01"), DateTime.now(), "additional info here", reservedParts, Some("current status info"), ely, coordinates)
  }

  def dummyProjectLink(id: Long, projectId: Long, linkId : Long, roadwayId: Long = 0, roadwayNumber: Long = roadwayNumber1, roadNumber: Long = roadNumber1, roadPartNumber: Long =roadPartNumber1, startAddrMValue: Long, endAddrMValue: Long,
                       startMValue: Double, endMValue: Double, endDate: Option[DateTime] = None, calibrationPoints: (Option[ProjectLinkCalibrationPoint], Option[ProjectLinkCalibrationPoint]) = (None, None),
                       floating: FloatingReason = NoFloating, geometry: Seq[Point] = Seq(), status: LinkStatus, roadType: RoadType, reversed: Boolean): ProjectLink =
    ProjectLink(id, roadNumber, roadPartNumber, Track.Combined,
      Discontinuity.Continuous, startAddrMValue, endAddrMValue, startAddrMValue, endAddrMValue, Some(DateTime.parse("1901-01-01")),
      endDate, Some("testUser"), linkId, startMValue, endMValue,
      TowardsDigitizing, calibrationPoints, floating, geometry, projectId, status, roadType,
      LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geometry), roadwayId, linearLocationId, 0, reversed,
      connectedLinkId = None, 631152000, roadwayNumber, roadAddressLength = Some(endAddrMValue - startAddrMValue))

  private def dummyRoadways: Seq[Roadway] = {
    Seq(Roadway(NewRoadway, roadwayNumber1, roadNumber1, roadPartNumber1, RoadType.PublicRoad, Track.Combined, Discontinuity.Continuous,
      0, 100, false, DateTime.parse("2000-01-01"), None, "testUser", Some("Test Rd. 1"), 1, TerminationCode.NoTermination),
      Roadway(NewRoadway, roadwayNumber2, roadNumber1, roadPartNumber2, RoadType.PublicRoad, Track.Combined, Discontinuity.Continuous,
        0, 100, false, DateTime.parse("2000-01-01"), None, "testUser", Some("Test Rd. 1"), 1, TerminationCode.NoTermination)
    )
  }


  /*
  1.  RA has START_DATE < PROJ_DATE, END_DATE = null
  2.a START_DATE > PROJ_DATE, END_DATE = null
  2.b START_DATE == PROJ_DATE, END_DATE = null
  3.a START_DATE < PROJ_DATE, END_DATE < PROJ_DATE
  3.b START_DATE < PROJ_DATE, END_DATE == PROJ_DATE
  4.a START_DATE < PROJ_DATE, END_DATE > PROJ_DATE
  4.b START_DATE == PROJ_DATE, END_DATE > PROJ_DATE
  5.a START_DATE > PROJ_DATE, END_DATE > PROJ_DATE
  5.b START_DATE == PROJ_DATE, END_DATE > PROJ_DATE
  1 and 3 are acceptable scenarios
  6. Combination 1+3a(+3a+3a+3a+...)
  7. Expired rows are not checked
   */
    test("Test isNotAvailableForProject case (1) When START_DATE > PROJ_DATE, END_DATE = null Then should be reservable") {
      runWithRollback {
        val id1 = Sequences.nextViitePrimaryKeySeqValue
        val rap1 = Project(id1, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("2700-01-01"), "TestUser", DateTime.parse("2700-01-01"), DateTime.now(), "Some additional info", List.empty[ProjectReservedPart], None)
        projectDAO.create(rap1)
        // Check that the DB contains only null values in end dates
        roadwayDAO.fetchAllByRoadAndPart(5, 205).map(_.endDate).forall(ed => ed.isEmpty) should be (true)
        val reserveNotAvailable = projectReservedPartDAO.isNotAvailableForProject(5, 205, id1)
        reserveNotAvailable should be (false)
      }
    }

    test("Test isNotAvailableForProject case (2a) When START_DATE > PROJ_DATE, END_DATE = null Then should NOT be reservable") {
      runWithRollback {
        val id = Sequences.nextViitePrimaryKeySeqValue
        val rap = Project(id, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("1901-01-01"), "TestUser", DateTime.parse("1901-01-01"), DateTime.now(), "Some additional info", List.empty[ProjectReservedPart], None)
        projectDAO.create(rap)
        val reserveNotAvailable = projectReservedPartDAO.isNotAvailableForProject(5,205,id)
        reserveNotAvailable should be (true)
      }
    }

    test("Test isNotAvailableForProject case (2b) When START_DATE == PROJ_DATE, END_DATE = null Then should be reservable") {
      // Update: after VIITE-1411 we can have start date equal to project date
      runWithRollback {
        val id3 = Sequences.nextViitePrimaryKeySeqValue
        val rap3 = Project(id3, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("1962-11-01"),
          "TestUser", DateTime.parse("1962-11-01"), DateTime.now(), "Some additional info", List.empty[ProjectReservedPart], None)
        projectDAO.create(rap3)
        roadwayDAO.fetchAllByRoadAndPart(5, 207).map(r => r.startDate.toDate).min should be (DateTime.parse("1962-11-01").toDate)
        val reserveNotAvailable = projectReservedPartDAO.isNotAvailableForProject(5,207, id3)
        reserveNotAvailable should be (false)
      }
    }

    test("Test isNotAvailableForProject case (3a) When START_DATE < PROJ_DATE, END_DATE < PROJ_DATE Then should be reservable") {
      runWithRollback {
        roadwayDAO.create(Seq(dummyRoadways.head.copy(startDate = DateTime.parse("1975-11-18"), endDate = Some(DateTime.parse("2000-01-01")))))
        val id4 = Sequences.nextViitePrimaryKeySeqValue
        val rap4 = Project(id4, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("2700-01-01"), "TestUser", DateTime.parse("2700-01-01"), DateTime.now(), "Some additional info", List.empty[ProjectReservedPart], None)
        projectDAO.create(rap4)
        val reserveNotAvailable = projectReservedPartDAO.isNotAvailableForProject(roadNumber1, roadPartNumber1, id4)
        reserveNotAvailable should be (false)
      }
    }

    test("Test isNotAvailableForProject case (3b) When START_DATE < PROJ_DATE, END_DATE == PROJ_DATE Then should be reservable") {
      runWithRollback {
        roadwayDAO.create(Seq(dummyRoadways.head.copy(startDate = DateTime.parse("1975-11-18"), endDate = Some(DateTime.parse("2700-01-01")))))
        val id5 = Sequences.nextViitePrimaryKeySeqValue
        val rap5 = Project(id5, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("2700-01-01"), "TestUser", DateTime.parse("2700-01-01"), DateTime.now(), "Some additional info", List.empty[ProjectReservedPart], None)
        projectDAO.create(rap5)
        val reserveNotAvailable = projectReservedPartDAO.isNotAvailableForProject(roadNumber1, roadPartNumber1, id5)
        reserveNotAvailable should be (false)
      }
    }

    test("Test isNotAvailableForProject case (4a) When START_DATE < PROJ_DATE, END_DATE > PROJ_DATE Then should NOT be reservable") {
      runWithRollback {
        roadwayDAO.create(Seq(dummyRoadways.head.copy(startDate = DateTime.parse("1975-11-18"), endDate = Some(DateTime.parse("2800-01-01")))))
        val id6 = Sequences.nextViitePrimaryKeySeqValue
        val rap6 = Project(id6, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("2700-01-01"), "TestUser", DateTime.parse("2700-01-01"), DateTime.now(), "Some additional info", List.empty[ProjectReservedPart], None)
        projectDAO.create(rap6)
        val reserveNotAvailable = projectReservedPartDAO.isNotAvailableForProject(roadNumber1, roadPartNumber1, id6)
        reserveNotAvailable should be (true)
      }
    }

    test("Test isNotAvailableForProject case (4b) When START_DATE == PROJ_DATE, END_DATE > PROJ_DATE Then should NOT be reservable") {
      runWithRollback {
        roadwayDAO.create(Seq(dummyRoadways.head.copy(startDate = DateTime.parse("2700-01-01"), endDate = Some(DateTime.parse("2800-01-01")))))
        val id7 = Sequences.nextViitePrimaryKeySeqValue
        val rap7 = Project(id7, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("2700-01-01"), "TestUser", DateTime.parse("2700-01-01"), DateTime.now(), "Some additional info", List.empty[ProjectReservedPart], None)
        projectDAO.create(rap7)
        val reserveNotAvailable = projectReservedPartDAO.isNotAvailableForProject(roadNumber1, roadPartNumber1, id7)
        reserveNotAvailable should be (true)
      }
    }

    test("Test isNotAvailableForProject case (5a) When START_DATE > PROJ_DATE, END_DATE > PROJ_DATE Then should NOT be reservable") {
      runWithRollback {
        roadwayDAO.create(Seq(dummyRoadways.head.copy(startDate = DateTime.parse("1975-11-18"), endDate = Some(DateTime.parse("1990-01-01")))))
        val id8 = Sequences.nextViitePrimaryKeySeqValue
        val rap8 = Project(id8, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("1975-01-01"),
          "TestUser", DateTime.parse("1975-01-01"), DateTime.now(), "Some additional info", List.empty[ProjectReservedPart], None)
        projectDAO.create(rap8)
        val reserveNotAvailable = projectReservedPartDAO.isNotAvailableForProject(roadNumber1, roadPartNumber1, id8)
        reserveNotAvailable should be (true)
      }
    }

    test("Test isNotAvailableForProject case (5b) When START_DATE > PROJ_DATE, END_DATE > PROJ_DATE Then should NOT be reservable") {
      runWithRollback {
        roadwayDAO.create(Seq(dummyRoadways.head.copy(startDate = DateTime.parse("1975-01-01"))))
        val id9 = Sequences.nextViitePrimaryKeySeqValue
        val rap9 = Project(id9, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("1975-01-01"),
          "TestUser", DateTime.parse("1975-01-01"), DateTime.now(), "Some additional info", List.empty[ProjectReservedPart], None)
        projectDAO.create(rap9)
        val reserveNotAvailable = projectReservedPartDAO.isNotAvailableForProject(roadNumber1, roadPartNumber1, id9)
        reserveNotAvailable should be(false)
      }
    }

    test("Test isNotAvailableForProject case (6) When START_DATE > PROJ_DATE, END_DATE > PROJ_DATE Then should be reservable") {
      runWithRollback {
        roadwayDAO.create(Seq(dummyRoadways.head.copy(startDate = DateTime.parse("1975-11-18"), endDate = Some(DateTime.parse("2000-01-01")))))
        roadwayDAO.create(Seq(dummyRoadways.head.copy(startDate = DateTime.parse("2000-01-01"), endDate = Some(DateTime.parse("2001-01-01")))))
        roadwayDAO.create(Seq(dummyRoadways.head.copy(startDate = DateTime.parse("2001-01-01"))))
        val id = Sequences.nextViitePrimaryKeySeqValue
        val rap = Project(id, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("2017-01-01"),
          "TestUser", DateTime.parse("2017-01-01"), DateTime.now(), "Some additional info", List.empty[ProjectReservedPart], None)
        projectDAO.create(rap)
        val reserveNotAvailable = projectReservedPartDAO.isNotAvailableForProject(roadNumber1, roadPartNumber1, id)
        reserveNotAvailable should be (false)
      }
    }

    test("Test isNotAvailableForProject case (7) When START_DATE > PROJ_DATE, END_DATE > PROJ_DATE Then invalidated rows don't affect reservation") {
      runWithRollback {
        roadwayDAO.create(Seq(dummyRoadways.head.copy(startDate = DateTime.parse("1975-11-18"), endDate = Some(DateTime.parse("2000-01-01")))))
        val id = Sequences.nextViitePrimaryKeySeqValue
        val rap = Project(id, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("1997-01-01"),
          "TestUser", DateTime.parse("1997-01-01"), DateTime.now(), "Some additional info", List.empty[ProjectReservedPart], None)
        projectDAO.create(rap)
        projectReservedPartDAO.isNotAvailableForProject(roadNumber1, roadPartNumber1, id) should be (true)
        sqlu"""update ROADWAY set valid_to = sysdate WHERE road_number = $roadNumber1""".execute
        roadwayDAO.create(Seq(dummyRoadways.head.copy(startDate = DateTime.parse("1975-11-18"))))
        projectReservedPartDAO.isNotAvailableForProject(roadNumber1, roadPartNumber1, id) should be (false)
      }
    }

    test("Test isNotAvailableForProject When there is no reserved part for project Then road part can be reserved") {
      runWithRollback {
        val id = Sequences.nextViitePrimaryKeySeqValue
        val rap = Project(id, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("2700-01-01"), "TestUser", DateTime.parse("2700-01-01"), DateTime.now(), "Some additional info", List.empty[ProjectReservedPart], None)
        projectDAO.create(rap)
        val reserveNotAvailable = projectReservedPartDAO.isNotAvailableForProject(123456789,1,id)
        reserveNotAvailable should be (false)
      }
    }

    test("Test isNotAvailableForProject When road is not terminated Then road part can be reserved") {
      runWithRollback {
        val idr = roadwayDAO.getNextRoadwayId
        roadwayDAO.create(Seq(dummyRoadways.head.copy(startDate = DateTime.parse("1901-01-01"), endDate = Some(DateTime.parse("1902-01-01")))))
        val id = Sequences.nextViitePrimaryKeySeqValue
        val rap = Project(id, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("2700-01-01"), "TestUser", DateTime.parse("2700-01-01"), DateTime.now(), "Some additional info", List.empty[ProjectReservedPart], None)
        projectDAO.create(rap)
        val reserveNotAvailable = projectReservedPartDAO.isNotAvailableForProject(roadNumber1, roadPartNumber1, id)
        reserveNotAvailable should be (false)
      }
    }

  test("Test reserveRoadPart When having reserved one project with that part Then should fetch it without any problems") {
    runWithRollback {
      val id = Sequences.nextViitePrimaryKeySeqValue
      val reservedParts = Seq(ProjectReservedPart(id: Long, roadNumber1: Long, roadPartNumber1: Long, Some(6L), Some(Discontinuity.apply("jatkuva")), Some(8L), newLength = None, newDiscontinuity = None, newEly = None))
      val rap = dummyRoadAddressProject(id, ProjectState.Incomplete, reservedParts, Some(8L), None)
      projectDAO.create(rap)
      projectReservedPartDAO.reserveRoadPart(id, roadNumber1, roadPartNumber1, "TestUser")
      val fetchedPart = projectReservedPartDAO.fetchReservedRoadPart(roadNumber1, roadPartNumber1)
      fetchedPart.nonEmpty should be (true)
      fetchedPart.get.roadNumber should be (reservedParts.head.roadNumber)
      fetchedPart.get.roadPartNumber should be (reservedParts.head.roadPartNumber)
    }
  }

  test("Test roadPartReservedByProject When removeReservedRoadPart is done in that same road part Then should not be returning that removed part") {
    runWithRollback {
      val roadwayIds = roadwayDAO.create(dummyRoadways)

      val id = Sequences.nextViitePrimaryKeySeqValue
      val projectLinkId = id + 1
      val rap = Project(id, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("1901-01-01"), "TestUser", DateTime.parse("1901-01-01"), DateTime.now(), "Some additional info", List.empty, None)
      projectDAO.create(rap)
      projectReservedPartDAO.reserveRoadPart(id, roadNumber1, roadPartNumber1, rap.createdBy)
      val projectLinks = Seq(dummyProjectLink(projectLinkId, id, linkId1, roadwayIds.head, roadwayNumber1, roadNumber1, roadPartNumber1,  0, 100, 0.0, 100.0, None, (None, None), FloatingReason.NoFloating, Seq(),LinkStatus.Transfer, RoadType.PublicRoad, reversed = false)
      )
      projectLinkDAO.create(projectLinks)
      val project = projectReservedPartDAO.roadPartReservedByProject(roadNumber1, roadPartNumber1)
      project should be(Some("TestProject"))
      val reserved = projectReservedPartDAO.fetchReservedRoadPart(roadNumber1, roadPartNumber1)
      reserved.nonEmpty should be(true)
      projectReservedPartDAO.fetchReservedRoadParts(id) should have size 1
      projectReservedPartDAO.removeReservedRoadPartAndChanges(id, reserved.get.roadNumber, reserved.get.roadPartNumber)
      val projectAfter = projectReservedPartDAO.roadPartReservedByProject(roadNumber1, roadPartNumber1)
      projectAfter should be(None)
      projectReservedPartDAO.fetchReservedRoadPart(roadNumber1, roadPartNumber1).isEmpty should be(true)
    }
  }


  test("Test removeReservedRoadPart When removing a existing reserved part Then should remove the road parts") {
    runWithRollback {
      val projectId = 123
      val roadNumber = 99999
      val roadPartNumber = 1
      sqlu"""INSERT INTO PROJECT VALUES ($projectId, 1, 'Test Project', 1, 'Test', to_date('01.01.2018','DD.MM.RRRR'),'-', to_date('01.01.2018','DD.MM.RRRR'), null, to_date('01.01.2018','DD.MM.RRRR'), null, null, 0, 0, 0)""".execute
      sqlu"""INSERT INTO PROJECT_RESERVED_ROAD_PART VALUES (11111, $roadNumber, $roadPartNumber, $projectId, 'Test')""".execute
      projectReservedPartDAO.removeReservedRoadPartAndChanges(projectId, roadNumber, roadPartNumber)
      projectReservedPartDAO.fetchReservedRoadParts(projectId).isEmpty should be (true)
    }
  }

  test("Test roadPartReservedTo When getting the road parts reserved Then should return the project that has the roads") {
    runWithRollback {
      val projectId = 123
      val roadNumber = 99999
      val roadPartNumber = 1
      sqlu"""INSERT INTO PROJECT VALUES ($projectId, 1, 'Test Project', 1, 'Test', to_date('01.01.2018','DD.MM.RRRR'),'-', to_date('01.01.2018','DD.MM.RRRR'), null, to_date('01.01.2018','DD.MM.RRRR'), null, null, 0, 0, 0)""".execute
      sqlu"""INSERT INTO PROJECT_RESERVED_ROAD_PART VALUES (11111, $roadNumber, $roadPartNumber, $projectId, 'Test')""".execute
      projectReservedPartDAO.roadPartReservedTo(roadNumber, roadPartNumber).get._1 should be (projectId)
    }
  }

  test("Test roadPartReservedByProject When road parts are reserved by project Then it should return the project name") {
    runWithRollback {
      val projectId = 123
      val roadNumber = 99999
      val roadPartNumber = 1
      sqlu"""INSERT INTO PROJECT VALUES ($projectId, 1, 'Test Project', 1, 'Test', to_date('01.01.2018','DD.MM.RRRR'),'-', to_date('01.01.2018','DD.MM.RRRR'), null, to_date('01.01.2018','DD.MM.RRRR'), null, null, 0, 0, 0)""".execute
      sqlu"""INSERT INTO PROJECT_RESERVED_ROAD_PART VALUES (11111, $roadNumber, $roadPartNumber, $projectId, 'Test')""".execute
      projectReservedPartDAO.roadPartReservedByProject(roadNumber, roadPartNumber).get should be("Test Project")
    }
  }

  test("Test fetchHistoryRoadParts When fetching road parts history Then it should return the reserved history") {
    runWithRollback {
      val projectId = 123
      val roadNumber = 99999
      val roadPartNumber = 1
      sqlu"""INSERT INTO PROJECT VALUES ($projectId, 1, 'Test Project', 1, 'Test', to_date('01.01.2018','DD.MM.RRRR'),'-',to_date('01.01.2018','DD.MM.RRRR'), null, to_date('01.01.2018','DD.MM.RRRR'), null, null, 0, 0, 0)""".execute
      sqlu"""INSERT INTO PROJECT_LINK_HISTORY VALUES (11111111, $projectId, 0, 1, $roadNumber, $roadPartNumber, 0, 10, 'Test', 'Test', to_date('01.01.2018','DD.MM.RRRR'), to_date('01.01.2018','DD.MM.RRRR'), 2, 3, 3, 123456,123458, 8, 0, null, 2, 0, 10, 99999, 1533576206000, 1, 2, null, 0, 10)""".execute
      val fetched = projectReservedPartDAO.fetchHistoryRoadParts(projectId)
      fetched.size should be (1)
      fetched.head.roadNumber should be (roadNumber)
      fetched.head.roadPartNumber should be (roadPartNumber)
    }
  }

  test("Test fetchReservedRoadParts When finding reserved parts by project Then it should return the project reserved parts") {
    runWithRollback {
      val projectId = 123
      val roadNumber = 99999
      val roadPartNumber = 1
      sqlu"""INSERT INTO PROJECT VALUES ($projectId, 1, 'Test Project', 1, 'Test', to_date('01.01.2018','DD.MM.RRRR'),'-',to_date('01.01.2018','DD.MM.RRRR'), null, to_date('01.01.2018','DD.MM.RRRR'), null, null, 0, 0, 0)""".execute
      sqlu"""INSERT INTO PROJECT_RESERVED_ROAD_PART VALUES (11111, $roadNumber, $roadPartNumber, $projectId, 'Test')""".execute
      val fetched = projectReservedPartDAO.fetchReservedRoadParts(projectId)
      fetched.size should be (1)
      fetched.head.roadNumber should be (roadNumber)
      fetched.head.roadPartNumber should be (roadPartNumber)
    }
  }

  test("Test fetchReservedRoadParts When finding reserved parts by road number and part Then it should return the project reserved parts") {
    runWithRollback {
      val projectId = 123
      val roadNumber = 99999
      val roadPartNumber = 1
      sqlu"""INSERT INTO PROJECT VALUES ($projectId, 1, 'Test Project', 1, 'Test', to_date('01.01.2018','DD.MM.RRRR'),'-',to_date('01.01.2018','DD.MM.RRRR'), null, to_date('01.01.2018','DD.MM.RRRR'), null, null, 0, 0, 0)""".execute
      sqlu"""INSERT INTO PROJECT_RESERVED_ROAD_PART VALUES (11111, $roadNumber, $roadPartNumber, $projectId, 'Test')""".execute
      val fetched = projectReservedPartDAO.fetchReservedRoadPart(roadNumber, roadPartNumber)
      fetched.size should be (1)
      fetched.head.roadNumber should be (roadNumber)
      fetched.head.roadPartNumber should be (roadPartNumber)
    }
  }

  test("Tetst reserveRoadPart When trying to reserve same road in two diferent projects Then should throw a SQLIntegrityConstraintViolationException exception") {
    runWithRollback {
      val error = intercept[SQLIntegrityConstraintViolationException] {
        val projectId = 123
        val projectId2 = 124
        val roadNumber = 99999
        val roadPartNumber = 1
        sqlu"""INSERT INTO PROJECT VALUES ($projectId, 1, 'Test Project', 1, 'Test', to_date('01.01.2018','DD.MM.RRRR'),'-', to_date('01.01.2018','DD.MM.RRRR'), null, to_date('01.01.2018','DD.MM.RRRR'), null, null, 0, 0, 0)""".execute
        sqlu"""INSERT INTO PROJECT_RESERVED_ROAD_PART VALUES (11111, $roadNumber, $roadPartNumber, $projectId, 'Test')""".execute
        sqlu"""INSERT INTO PROJECT VALUES ($projectId2, 1, 'Test Project', 1, 'Test', to_date('01.01.2018','DD.MM.RRRR'),'-',to_date('01.01.2018','DD.MM.RRRR'), null, to_date('01.01.2018','DD.MM.RRRR'), null, null, 0, 0, 0)""".execute
        sqlu"""INSERT INTO PROJECT_RESERVED_ROAD_PART VALUES (11111, $roadNumber, $roadPartNumber, $projectId2, 'Test')""".execute
      }
      error.getMessage.contains("PROJECT_RESERVED_ROAD_PART_PK") should be (true)
    }
  }

}
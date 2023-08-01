package fi.liikennevirasto.viite.dao

import fi.liikennevirasto.viite.Dummies.dummyLinearLocation
import fi.liikennevirasto.viite.NewIdValue
import fi.vaylavirasto.viite.dao.Sequences
import fi.vaylavirasto.viite.geometry.{GeometryUtils, Point}
import fi.vaylavirasto.viite.model.CalibrationPointType.NoCP
import fi.vaylavirasto.viite.model.{AdministrativeClass, BeforeAfter, CalibrationPointType, Discontinuity, LinkGeomSource, RoadAddressChangeType, SideCode, Track}
import fi.vaylavirasto.viite.postgis.DbUtils.runUpdateToDb
import fi.vaylavirasto.viite.postgis.PostGISDatabase.runWithRollback
import org.joda.time.DateTime
import org.postgresql.util.PSQLException
import org.scalatest.{FunSuite, Matchers}

/**
  * Class to test DB trigger that does not allow reserving already reserved parts to project
  */
class ProjectReservedPartDAOSpec extends FunSuite with Matchers {

  val roadwayDAO = new RoadwayDAO
  val linearLocationDAO = new LinearLocationDAO
  val projectDAO = new ProjectDAO
  val projectLinkDAO = new ProjectLinkDAO
  val projectReservedPartDAO = new ProjectReservedPartDAO
  val roadwayPointDAO = new RoadwayPointDAO
  val junctionDAO = new JunctionDAO
  val junctionPointDAO = new JunctionPointDAO

  private val roadNumber1 = 5
  private val roadNumber2 = 6
  private val roadNumber3 = 7
  private val roadNumber4 = 8

  private val roadPartNumber1 = 1
  private val roadPartNumber2 = 2

  private val roadwayNumber1 = 1000000000L
  private val roadwayNumber2 = 2000000000L

  private val linkId1 = 1000L.toString
  private val linkId2 = 2000L.toString
  private val linkId3 = 3000L.toString
  private val linkId4 = 4000L.toString

  private val linearLocationId = 0

  private def dummyRoadAddressProject(id: Long, status: ProjectState, reservedParts: Seq[ProjectReservedPart] = List.empty[ProjectReservedPart], coordinates: Option[ProjectCoordinates] = None): Project ={
    Project(id, status, "testProject", "testUser", DateTime.parse("1901-01-01"), "testUser", DateTime.parse("1901-01-01"), DateTime.now(), "additional info here", reservedParts, Seq(), Some("current status info"), coordinates)
  }

  def dummyProjectLink(id: Long, projectId: Long, linkId: String, roadwayId: Long = 0, roadwayNumber: Long = roadwayNumber1, roadNumber: Long = roadNumber1, roadPartNumber: Long = roadPartNumber1, startAddrMValue: Long, endAddrMValue: Long, startMValue: Double, endMValue: Double, endDate: Option[DateTime] = None, calibrationPointTypes: (CalibrationPointType, CalibrationPointType) = (NoCP, NoCP), geometry: Seq[Point] = Seq(), status: RoadAddressChangeType, administrativeClass: AdministrativeClass, reversed: Boolean): ProjectLink =
    ProjectLink(id, roadNumber, roadPartNumber, Track.Combined, Discontinuity.Continuous, startAddrMValue, endAddrMValue, startAddrMValue, endAddrMValue, Some(DateTime.parse("1901-01-01")), endDate, Some("testUser"), linkId, startMValue, endMValue, SideCode.TowardsDigitizing, calibrationPointTypes, (NoCP, NoCP), geometry, projectId, status, administrativeClass, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geometry), roadwayId, linearLocationId, 0, reversed, connectedLinkId = None, 631152000, roadwayNumber, roadAddressLength = Some(endAddrMValue - startAddrMValue))

  private def dummyRoadways: Seq[Roadway] = {
    Seq(Roadway(NewIdValue, roadwayNumber1, roadNumber1, roadPartNumber1, AdministrativeClass.State, Track.Combined, Discontinuity.Continuous, 0, 100, reversed = false, DateTime.parse("2000-01-01"), None, "testUser", Some("Test Rd. 1"), 1, TerminationCode.NoTermination),
      Roadway(NewIdValue, roadwayNumber2, roadNumber1, roadPartNumber2, AdministrativeClass.State, Track.Combined, Discontinuity.Continuous, 0, 100, reversed = false, DateTime.parse("2000-01-01"), None, "testUser", Some("Test Rd. 1"), 1, TerminationCode.NoTermination)
    )
  }
  private def dummyLinearLocations = Seq(
    dummyLinearLocation(roadwayNumber = roadwayNumber1, orderNumber = 1L, linkId = linkId1.toString, startMValue = 0.0, endMValue = 10.0),
    dummyLinearLocation(roadwayNumber = roadwayNumber2, orderNumber = 1L, linkId = linkId2.toString, startMValue = 0.0, endMValue = 10.0))



  /*
  1.  RA has START_DATE < PROJ_DATE, END_DATE = null
  2.a START_DATE > PROJ_DATE, END_DATE = null
  2.b START_DATE == PROJ_DATE, END_DATE = null
  3.a START_DATE < PROJ_DATE, END_DATE < PROJ_DATE - 1
  3.b START_DATE < PROJ_DATE, END_DATE == PROJ_DATE - 1
  4.a START_DATE < PROJ_DATE, END_DATE > PROJ_DATE - 1
  4.b START_DATE == PROJ_DATE, END_DATE > PROJ_DATE - 1
  5.a START_DATE > PROJ_DATE, END_DATE > PROJ_DATE - 1
  5.b START_DATE == PROJ_DATE, END_DATE > PROJ_DATE - 1
  1 and 3 are acceptable scenarios
  6. Combination 1+3a(+3a+3a+3a+...)
  7. Expired rows are not checked
   */
    test("Test isNotAvailableForProject case (1) When START_DATE > PROJ_DATE, END_DATE = null Then should be reservable") {
      runWithRollback {
        val id1 = Sequences.nextViiteProjectId
        val rap1 = Project(id1, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("2700-01-01"), "TestUser", DateTime.parse("2700-01-01"), DateTime.now(), "Some additional info", List.empty[ProjectReservedPart], Seq(), None)
        projectDAO.create(rap1)
        // Check that the DB contains only null values in end dates
        roadwayDAO.fetchAllByRoadAndPart(5, 205).map(_.endDate).forall(ed => ed.isEmpty) should be (true)
        val reserveNotAvailable = projectReservedPartDAO.isNotAvailableForProject(5, 205, id1)
        reserveNotAvailable should be (false)
      }
    }

    test("Test isNotAvailableForProject case (2a) When START_DATE > PROJ_DATE, END_DATE = null Then should NOT be reservable") {
      runWithRollback {
        val id = Sequences.nextViiteProjectId
        val rap = Project(id, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("1901-01-01"), "TestUser", DateTime.parse("1901-01-01"), DateTime.now(), "Some additional info", List.empty[ProjectReservedPart], Seq(), None)
        projectDAO.create(rap)
        val reserveNotAvailable = projectReservedPartDAO.isNotAvailableForProject(5,205,id)
        reserveNotAvailable should be (true)
      }
    }

    test("Test isNotAvailableForProject case (2b) When START_DATE == PROJ_DATE, END_DATE = null Then should be reservable") {
      // Update: after VIITE-1411 we can have start date equal to project date
      runWithRollback {
        val id3 = Sequences.nextViiteProjectId
        val rap3 = Project(id3, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("1962-11-01"),
          "TestUser", DateTime.parse("1962-11-01"), DateTime.now(), "Some additional info", List.empty[ProjectReservedPart], Seq(), None)
        projectDAO.create(rap3)
        roadwayDAO.fetchAllByRoadAndPart(5, 207).map(r => r.startDate.toDate).min should be (DateTime.parse("1962-11-01").toDate)
        val reserveNotAvailable = projectReservedPartDAO.isNotAvailableForProject(5,207, id3)
        reserveNotAvailable should be (false)
      }
    }

    test("Test isNotAvailableForProject case (3a) When START_DATE < PROJ_DATE, END_DATE < PROJ_DATE - 1 Then should be reservable") {
      runWithRollback {
        roadwayDAO.create(Seq(dummyRoadways.head.copy(startDate = DateTime.parse("1975-11-18"), endDate = Some(DateTime.parse("1999-12-31")))))
        val id4 = Sequences.nextViiteProjectId
        val rap4 = Project(id4, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("2700-01-01"), "TestUser", DateTime.parse("2700-01-01"), DateTime.now(), "Some additional info", List.empty[ProjectReservedPart], Seq(), None)
        projectDAO.create(rap4)
        val reserveNotAvailable = projectReservedPartDAO.isNotAvailableForProject(roadNumber1, roadPartNumber1, id4)
        reserveNotAvailable should be (false)
      }
    }

    test("Test isNotAvailableForProject case (3b) When START_DATE < PROJ_DATE, END_DATE == PROJ_DATE - 1 Then should be reservable") {
      runWithRollback {
        roadwayDAO.create(Seq(dummyRoadways.head.copy(startDate = DateTime.parse("1975-11-18"), endDate = Some(DateTime.parse("2699-12-31")))))
        val id5 = Sequences.nextViiteProjectId
        val rap5 = Project(id5, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("2700-01-01"), "TestUser", DateTime.parse("2700-01-01"), DateTime.now(), "Some additional info", List.empty[ProjectReservedPart], Seq(), None)
        projectDAO.create(rap5)
        val reserveNotAvailable = projectReservedPartDAO.isNotAvailableForProject(roadNumber1, roadPartNumber1, id5)
        reserveNotAvailable should be (false)
      }
    }

    test("Test isNotAvailableForProject case (4a) When START_DATE < PROJ_DATE, END_DATE > PROJ_DATE - 1 Then should NOT be reservable") {
      runWithRollback {
        roadwayDAO.create(Seq(dummyRoadways.head.copy(startDate = DateTime.parse("1975-11-18"), endDate = Some(DateTime.parse("2799-12-31")))))
        val id6 = Sequences.nextViiteProjectId
        val rap6 = Project(id6, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("2700-01-01"), "TestUser", DateTime.parse("2700-01-01"), DateTime.now(), "Some additional info", List.empty[ProjectReservedPart], Seq(), None)
        projectDAO.create(rap6)
        val reserveNotAvailable = projectReservedPartDAO.isNotAvailableForProject(roadNumber1, roadPartNumber1, id6)
        reserveNotAvailable should be (true)
      }
    }

    test("Test isNotAvailableForProject case (4b) When START_DATE == PROJ_DATE, END_DATE > PROJ_DATE - 1 Then should NOT be reservable") {
      runWithRollback {
        roadwayDAO.create(Seq(dummyRoadways.head.copy(startDate = DateTime.parse("2700-01-01"), endDate = Some(DateTime.parse("2799-12-31")))))
        val id7 = Sequences.nextViiteProjectId
        val rap7 = Project(id7, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("2700-01-01"), "TestUser", DateTime.parse("2700-01-01"), DateTime.now(), "Some additional info", List.empty[ProjectReservedPart], Seq(), None)
        projectDAO.create(rap7)
        val reserveNotAvailable = projectReservedPartDAO.isNotAvailableForProject(roadNumber1, roadPartNumber1, id7)
        reserveNotAvailable should be (true)
      }
    }

    test("Test isNotAvailableForProject case (5a) When START_DATE > PROJ_DATE, END_DATE > PROJ_DATE - 1 Then should NOT be reservable") {
      runWithRollback {
        roadwayDAO.create(Seq(dummyRoadways.head.copy(startDate = DateTime.parse("1975-11-18"), endDate = Some(DateTime.parse("1989-12-31")))))
        val id8 = Sequences.nextViiteProjectId
        val rap8 = Project(id8, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("1975-01-01"),
          "TestUser", DateTime.parse("1975-01-01"), DateTime.now(), "Some additional info", List.empty[ProjectReservedPart], Seq(), None)
        projectDAO.create(rap8)
        val reserveNotAvailable = projectReservedPartDAO.isNotAvailableForProject(roadNumber1, roadPartNumber1, id8)
        reserveNotAvailable should be (true)
      }
    }

    test("Test isNotAvailableForProject case (5b) When START_DATE > PROJ_DATE, END_DATE > PROJ_DATE - 1 Then should NOT be reservable") {
      runWithRollback {
        roadwayDAO.create(Seq(dummyRoadways.head.copy(startDate = DateTime.parse("1974-12-31"))))
        val id9 = Sequences.nextViiteProjectId
        val rap9 = Project(id9, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("1975-01-01"),
          "TestUser", DateTime.parse("1975-01-01"), DateTime.now(), "Some additional info", List.empty[ProjectReservedPart], Seq(), None)
        projectDAO.create(rap9)
        val reserveNotAvailable = projectReservedPartDAO.isNotAvailableForProject(roadNumber1, roadPartNumber1, id9)
        reserveNotAvailable should be(false)
      }
    }

    test("Test isNotAvailableForProject case (6) When START_DATE > PROJ_DATE, END_DATE > PROJ_DATE - 1 Then should be reservable") {
      runWithRollback {
        roadwayDAO.create(Seq(dummyRoadways.head.copy(startDate = DateTime.parse("1975-11-18"), endDate = Some(DateTime.parse("1999-12-31")))))
        roadwayDAO.create(Seq(dummyRoadways.head.copy(startDate = DateTime.parse("2000-01-01"), endDate = Some(DateTime.parse("2000-12-31")))))
        roadwayDAO.create(Seq(dummyRoadways.head.copy(startDate = DateTime.parse("2001-01-01"))))
        val id = Sequences.nextViiteProjectId
        val rap = Project(id, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("2017-01-01"),
          "TestUser", DateTime.parse("2017-01-01"), DateTime.now(), "Some additional info", List.empty[ProjectReservedPart], Seq(), None)
        projectDAO.create(rap)
        val reserveNotAvailable = projectReservedPartDAO.isNotAvailableForProject(roadNumber1, roadPartNumber1, id)
        reserveNotAvailable should be (false)
      }
    }

    test("Test isNotAvailableForProject case (7) When START_DATE > PROJ_DATE, END_DATE > PROJ_DATE - 1 Then invalidated rows don't affect reservation") {
      runWithRollback {
        roadwayDAO.create(Seq(dummyRoadways.head.copy(startDate = DateTime.parse("1975-11-18"), endDate = Some(DateTime.parse("1999-12-31")))))
        val id = Sequences.nextViiteProjectId
        val rap = Project(id, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("1997-01-01"),
          "TestUser", DateTime.parse("1997-01-01"), DateTime.now(), "Some additional info", List.empty[ProjectReservedPart], Seq(), None)
        projectDAO.create(rap)
        projectReservedPartDAO.isNotAvailableForProject(roadNumber1, roadPartNumber1, id) should be (true)
        runUpdateToDb(s"""update ROADWAY set valid_to = current_timestamp WHERE road_number = $roadNumber1""")
        roadwayDAO.create(Seq(dummyRoadways.head.copy(startDate = DateTime.parse("1975-11-18"))))
        projectReservedPartDAO.isNotAvailableForProject(roadNumber1, roadPartNumber1, id) should be (false)
      }
    }

    test("Test isNotAvailableForProject When there is no reserved part for project Then road part can be reserved") {
      runWithRollback {
        val id = Sequences.nextViiteProjectId
        val rap = Project(id, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("2700-01-01"), "TestUser", DateTime.parse("2700-01-01"), DateTime.now(), "Some additional info", List.empty[ProjectReservedPart], Seq(), None)
        projectDAO.create(rap)
        val reserveNotAvailable = projectReservedPartDAO.isNotAvailableForProject(123456789,1,id)
        reserveNotAvailable should be (false)
      }
    }

    test("Test isNotAvailableForProject When road is not terminated Then road part can be reserved") {
      runWithRollback {
        roadwayDAO.create(Seq(dummyRoadways.head.copy(startDate = DateTime.parse("1901-01-01"), endDate = Some(DateTime.parse("1901-12-31")))))
        val id = Sequences.nextViiteProjectId
        val rap = Project(id, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("2700-01-01"), "TestUser", DateTime.parse("2700-01-01"), DateTime.now(), "Some additional info", List.empty[ProjectReservedPart], Seq(), None)
        projectDAO.create(rap)
        val reserveNotAvailable = projectReservedPartDAO.isNotAvailableForProject(roadNumber1, roadPartNumber1, id)
        reserveNotAvailable should be (false)
      }
    }

  test("Test reserveRoadPart When having reserved one project with that part Then should fetch it without any problems") {
    runWithRollback {
      val roadwayIds = roadwayDAO.create(dummyRoadways)
      val id = Sequences.nextViiteProjectId
      val projectLinkId = Sequences.nextProjectLinkId

      val reservedParts = Seq(ProjectReservedPart(id: Long, roadNumber1: Long, roadPartNumber1: Long, Some(6L), Some(Discontinuity.apply("jatkuva")), Some(8L), newLength = None, newDiscontinuity = None, newEly = None))
      val rap = dummyRoadAddressProject(id, ProjectState.Incomplete, reservedParts, None)
      projectDAO.create(rap)
      projectReservedPartDAO.reserveRoadPart(id, roadNumber1, roadPartNumber1, "TestUser")
      val projectLinks = Seq(dummyProjectLink(projectLinkId, id, linkId1, roadwayIds.head, roadwayNumber1, roadNumber1, roadPartNumber1, 0, 100, 0.0, 100.0, None, (NoCP, NoCP), Seq(), RoadAddressChangeType.Transfer, AdministrativeClass.State, reversed = false)
      )
      projectLinkDAO.create(projectLinks)
      val fetchedPart = projectReservedPartDAO.fetchReservedRoadPart(roadNumber1, roadPartNumber1)
      fetchedPart.nonEmpty should be (true)
      fetchedPart.get.roadNumber should be (reservedParts.head.roadNumber)
      fetchedPart.get.roadPartNumber should be (reservedParts.head.roadPartNumber)
    }
  }

  test("Test roadPartReservedByProject When removeReservedRoadPart is done in that same road part Then should not be returning that removed part") {
    runWithRollback {
      val roadwayIds = roadwayDAO.create(dummyRoadways)
      linearLocationDAO.create(dummyLinearLocations)

      val id = Sequences.nextViiteProjectId
      val projectLinkId = Sequences.nextProjectLinkId
      val rap = Project(id, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("1901-01-01"), "TestUser", DateTime.parse("1901-01-01"), DateTime.now(), "Some additional info", List.empty, Seq(), None)
      projectDAO.create(rap)
      projectReservedPartDAO.reserveRoadPart(id, roadNumber1, roadPartNumber1, rap.createdBy)
      val projectLinks = Seq(dummyProjectLink(projectLinkId, id, linkId1, roadwayIds.head, roadwayNumber1, roadNumber1, roadPartNumber1, 0, 100, 0.0, 100.0, None, (NoCP, NoCP), Seq(), RoadAddressChangeType.Transfer, AdministrativeClass.State, reversed = false)
      )
      projectLinkDAO.create(projectLinks)
      val project = projectReservedPartDAO.fetchProjectReservedPart(roadNumber1, roadPartNumber1)
      project should be(Some(id, "TestProject"))
      val reserved = projectReservedPartDAO.fetchReservedRoadPart(roadNumber1, roadPartNumber1)
      reserved.nonEmpty should be(true)
      projectReservedPartDAO.fetchReservedRoadParts(id) should have size 1
      projectReservedPartDAO.removeReservedRoadPartAndChanges(id, reserved.get.roadNumber, reserved.get.roadPartNumber)
      val projectAfter = projectReservedPartDAO.fetchProjectReservedPart(roadNumber1, roadPartNumber1)
      projectAfter should be(None)
      projectReservedPartDAO.fetchReservedRoadPart(roadNumber1, roadPartNumber1).isEmpty should be(true)
    }
  }


  test("Test removeReservedRoadPart When removing a existing reserved part Then should remove the road parts") {
    runWithRollback {
      val projectId = 123
      val roadNumber = 99999
      val roadPartNumber = 1
      runUpdateToDb(s"""INSERT INTO PROJECT VALUES ($projectId, 1, 'Test Project', 'Test', to_date('01.01.2018','DD.MM.YYYY'),'-', to_date('01.01.2018','DD.MM.YYYY'), null, to_date('01.01.2018','DD.MM.YYYY'), null, 0, 0, 0)""")
      runUpdateToDb(s"""INSERT INTO PROJECT_RESERVED_ROAD_PART VALUES (11111, $roadNumber, $roadPartNumber, $projectId, 'Test')""")
      projectReservedPartDAO.removeReservedRoadPartAndChanges(projectId, roadNumber, roadPartNumber)
      projectReservedPartDAO.fetchReservedRoadParts(projectId).isEmpty should be (true)
    }
  }

  test("Test roadPartReservedTo When getting the road parts reserved Then should return the project that has the roads") {
      runWithRollback {
        val roadwayIds = roadwayDAO.create(dummyRoadways)

        val id = Sequences.nextViiteProjectId
        val projectLinkId = Sequences.nextProjectLinkId
        val rap = Project(id, ProjectState.apply(1), "Test Project", "TestUser", DateTime.parse("1901-01-01"), "TestUser", DateTime.parse("1901-01-01"), DateTime.now(), "Some additional info", List.empty, Seq(), None)
        projectDAO.create(rap)
        projectReservedPartDAO.reserveRoadPart(id, roadNumber1, roadPartNumber1, rap.createdBy)
        val projectLinks = Seq(dummyProjectLink(projectLinkId, id, linkId1, roadwayIds.head, roadwayNumber1, roadNumber1, roadPartNumber1, 0, 100, 0.0, 100.0, None, (NoCP, NoCP), Seq(), RoadAddressChangeType.Transfer, AdministrativeClass.State, reversed = false)
        )
        projectLinkDAO.create(projectLinks)
        projectReservedPartDAO.roadPartReservedTo(roadNumber1, roadPartNumber1).get._1 should be (id)
      }
  }

  test("Test roadPartReservedByProject When road parts are reserved by project Then it should return the project name") {
    runWithRollback {
      val roadwayIds = roadwayDAO.create(dummyRoadways)
      linearLocationDAO.create(dummyLinearLocations)

      val id = Sequences.nextViiteProjectId
      val projectLinkId = Sequences.nextProjectLinkId
      val rap = Project(id, ProjectState.apply(1), "Test Project", "TestUser", DateTime.parse("1901-01-01"), "TestUser", DateTime.parse("1901-01-01"), DateTime.now(), "Some additional info", List.empty, Seq(), None)
      projectDAO.create(rap)
      projectReservedPartDAO.reserveRoadPart(id, roadNumber1, roadPartNumber1, rap.createdBy)
      val projectLinks = Seq(dummyProjectLink(projectLinkId, id, linkId1, roadwayIds.head, roadwayNumber1, roadNumber1, roadPartNumber1, 0, 100, 0.0, 100.0, None, (NoCP, NoCP), Seq(), RoadAddressChangeType.Transfer, AdministrativeClass.State, reversed = false)
      )
      projectLinkDAO.create(projectLinks)
      val project = projectReservedPartDAO.fetchProjectReservedPart(roadNumber1, roadPartNumber1)
      project should be(Some(id, "Test Project"))
    }
  }

  test("Test fetchHistoryRoadParts When fetching road parts history Then it should return the reserved history") {
    runWithRollback {
      val projectId = 123
      val roadNumber = 99999
      val roadPartNumber = 1
      runUpdateToDb(s"""INSERT INTO PROJECT VALUES ($projectId, 1, 'Test Project', 'Test', to_date('01.01.2018','DD.MM.YYYY'),'-',to_date('01.01.2018','DD.MM.YYYY'), null, to_date('01.01.2018','DD.MM.YYYY'), null, 0, 0, 0)""")
        runUpdateToDb(s"""
        INSERT INTO PROJECT_LINK_HISTORY
          (ID, PROJECT_ID, TRACK, DISCONTINUITY_TYPE, ROAD_NUMBER, ROAD_PART_NUMBER, START_ADDR_M, END_ADDR_M,
          CREATED_BY, MODIFIED_BY, CREATED_DATE, MODIFIED_DATE, STATUS, ADMINISTRATIVE_CLASS, ROADWAY_ID, LINEAR_LOCATION_ID, CONNECTED_LINK_ID,
          ELY, REVERSED, SIDE, START_MEASURE, END_MEASURE, LINK_ID, ADJUSTED_TIMESTAMP, LINK_SOURCE,
          GEOMETRY, ORIGINAL_START_ADDR_M, ORIGINAL_END_ADDR_M, ROADWAY_NUMBER,
          START_CALIBRATION_POINT, END_CALIBRATION_POINT, ORIG_START_CALIBRATION_POINT, ORIG_END_CALIBRATION_POINT)
        VALUES (11111111, $projectId, 0, 1, $roadNumber, $roadPartNumber, 0, 10,
          'Test', 'Test', to_date('01.01.2018','DD.MM.YYYY'), to_date('01.01.2018','DD.MM.YYYY'), 2, 3, 123456, 123458,
          8, 0, null, 2, 0, 10, 99999, 1533576206000, 1,
          null, 0, 10, NULL,
          3, 3, 3, 3)
      """)
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
      runUpdateToDb(s"""INSERT INTO PROJECT VALUES ($projectId, 1, 'Test Project', 'Test', to_date('01.01.2018','DD.MM.YYYY'),'-',to_date('01.01.2018','DD.MM.YYYY'), null, to_date('01.01.2018','DD.MM.YYYY'), null, 0, 0, 0)""")
      runUpdateToDb(s"""INSERT INTO PROJECT_RESERVED_ROAD_PART VALUES (11111, $roadNumber, $roadPartNumber, $projectId, 'Test')""")
      val fetched = projectReservedPartDAO.fetchReservedRoadParts(projectId)
      fetched.size should be (1)
      fetched.head.roadNumber should be (roadNumber)
      fetched.head.roadPartNumber should be (roadPartNumber)
    }
  }

  test("Test fetchReservedRoadParts When finding reserved parts by road number and part Then it should return the project reserved parts") {
    runWithRollback {
      val roadwayIds = roadwayDAO.create(dummyRoadways)

      val id = Sequences.nextViiteProjectId
      val projectLinkId = Sequences.nextProjectLinkId
      val rap = Project(id, ProjectState.apply(1), "Test Project", "TestUser", DateTime.parse("1901-01-01"), "TestUser", DateTime.parse("1901-01-01"), DateTime.now(), "Some additional info", List.empty, Seq(), None)
      projectDAO.create(rap)
      projectReservedPartDAO.reserveRoadPart(id, roadNumber1, roadPartNumber1, rap.createdBy)
      val projectLinks = Seq(dummyProjectLink(projectLinkId, id, linkId1, roadwayIds.head, roadwayNumber1, roadNumber1, roadPartNumber1, 0, 100, 0.0, 100.0, None, (NoCP, NoCP), Seq(), RoadAddressChangeType.Transfer, AdministrativeClass.State, reversed = false)
      )
      projectLinkDAO.create(projectLinks)
      val fetched = projectReservedPartDAO.fetchReservedRoadPart(roadNumber1, roadPartNumber1)
      fetched.size should be (1)
      fetched.head.roadNumber should be (roadNumber1)
      fetched.head.roadPartNumber should be (roadPartNumber1)
    }
  }

  test("Test reserveRoadPart When trying to reserve same road in two different projects Then should throw a SQLIntegrityConstraintViolationException exception") {
    runWithRollback {
      val error = intercept[PSQLException] {
        val projectId = 123
        val projectId2 = 124
        val roadNumber = 99999
        val roadPartNumber = 1
        runUpdateToDb(s"""INSERT INTO PROJECT VALUES ($projectId, 1, 'Test Project', 'Test', to_date('01.01.2018','DD.MM.YYYY'),'-', to_date('01.01.2018','DD.MM.YYYY'), null, to_date('01.01.2018','DD.MM.YYYY'), null, 0, 0, 0)""")
        runUpdateToDb(s"""INSERT INTO PROJECT_RESERVED_ROAD_PART VALUES (11111, $roadNumber, $roadPartNumber, $projectId, 'Test')""")
        runUpdateToDb(s"""INSERT INTO PROJECT VALUES ($projectId2, 1, 'Test Project', 'Test', to_date('01.01.2018','DD.MM.YYYY'),'-',to_date('01.01.2018','DD.MM.YYYY'), null, to_date('01.01.2018','DD.MM.YYYY'), null, 0, 0, 0)""")
        runUpdateToDb(s"""INSERT INTO PROJECT_RESERVED_ROAD_PART VALUES (11111, $roadNumber, $roadPartNumber, $projectId2, 'Test')""")
      }
      error.getMessage should include("project_reserved_road_part_pk")
    }
  }

  test("Roadways with junction points that share junctions belonging to reserved roadways can not be reserved.") {
    runWithRollback {

      val junctionStartDate = DateTime.parse("2019-01-01")
      val roadwayStartDate = DateTime.parse("2000-01-01")

      val intersectingRoadway = Roadway(NewIdValue, NewIdValue, roadNumber1, roadPartNumber1, AdministrativeClass.State, Track.Combined, Discontinuity.Continuous,
        0, 100, reversed = false, roadwayStartDate, None, "test", Some("Test road 1"), 1, TerminationCode.NoTermination)
      val reservedRoadway1 = Roadway(NewIdValue, NewIdValue, roadNumber2 , roadPartNumber1, AdministrativeClass.State, Track.Combined, Discontinuity.Continuous,
        0, 100, reversed = false, roadwayStartDate, None, "test", Some("Test road 2"), 1, TerminationCode.NoTermination)
      val reservedRoadway2 = Roadway(NewIdValue, NewIdValue, roadNumber2 + 1, roadPartNumber1, AdministrativeClass.State, Track.Combined, Discontinuity.Continuous,
        0, 100, reversed = false, roadwayStartDate, None, "test", Some("Test road 3"), 1, TerminationCode.NoTermination)

      val outsideRoadway1 = Roadway(NewIdValue, NewIdValue, roadNumber2, roadPartNumber2, AdministrativeClass.State, Track.Combined, Discontinuity.Continuous,
        0, 100, reversed = false, roadwayStartDate, None, "test", Some("Test road, no junctions"), 1, TerminationCode.NoTermination)
      val outsideRoadway2 = Roadway(NewIdValue, NewIdValue, roadNumber1, roadPartNumber2, AdministrativeClass.State, Track.Combined, Discontinuity.Continuous,
        0, 100, reversed = false, roadwayStartDate, None, "test", Some("Test road, no junctions"), 1, TerminationCode.NoTermination)

      val connectedRoadways: Seq[Roadway] = Seq(intersectingRoadway, reservedRoadway1, reservedRoadway2)
      val notConnectedroadways: Seq[Roadway] = Seq(outsideRoadway1, outsideRoadway2)

      roadwayDAO.create(connectedRoadways)
      roadwayDAO.create(notConnectedroadways)

      val roadways = connectedRoadways.flatMap(roadway =>
        roadwayDAO.fetchAllBySection(roadway.roadNumber, roadway.roadPartNumber)
      )
      val outsideRoadways = notConnectedroadways.flatMap(roadway =>
        roadwayDAO.fetchAllBySection(roadway.roadNumber, roadway.roadPartNumber)
      )

      val linearLocations = (roadways ++ outsideRoadways).zipWithIndex.map {
        case (roadway, i) => LinearLocation(NewIdValue, 1, linkId1 + i, 0.0, 2.8, SideCode.TowardsDigitizing, 10000000000L, (CalibrationPointReference.None, CalibrationPointReference.None), Seq(Point(99.0, 99.0), Point(101.0, 101.0)), LinkGeomSource.NormalLinkInterface, roadway.roadwayNumber)
      }

      linearLocationDAO.create(linearLocations)

      val connectedRoadwayPoints = roadways.map( roadway =>
        RoadwayPoint(NewIdValue, roadway.roadwayNumber, 0, "Test", None, None, None)
      )

      val connectedRoadwayPointIds = connectedRoadwayPoints.map(roadway =>
        roadwayPointDAO.create(roadway)
      )

      val junction = Junction(NewIdValue, None, None, junctionStartDate, None, junctionStartDate, None, "Test", None)
      val junctionId = junctionDAO.create(Seq(junction)).head
      val junctionPoint = JunctionPoint(NewIdValue, BeforeAfter.Before, -1, -1, Some(junction.startDate), junction.endDate, junctionStartDate, None, "Test", None, -1, 10, 0, 0, Track.Combined, Discontinuity.Continuous)

      (connectedRoadwayPointIds, roadways).zipped.flatMap((roadwayPointId, roadway) =>
        junctionPointDAO.create(Seq(junctionPoint.copy(junctionId = junctionId, roadwayPointId = roadwayPointId, roadwayNumber = roadway.roadwayNumber)))
      )

      val id = Sequences.nextViiteProjectId
      val projectLinkId = Sequences.nextProjectLinkId

      val rap = Project(id, ProjectState.apply(1), "Test Project", "TestUser", DateTime.parse("1901-01-01"), "TestUser", DateTime.parse("1901-01-01"), DateTime.now(), "Some additional info", List.empty, Seq(), None)
      projectDAO.create(rap)
      projectReservedPartDAO.reserveRoadPart(id, roadNumber1, roadPartNumber1, rap.createdBy)

      val projectLinks = Seq(dummyProjectLink(projectLinkId, id, linkId1 + 0, roadways.head.id, roadways.head.roadwayNumber, roadNumber1, roadPartNumber1, 0, 100, 0.0, 100.0, None, (NoCP, NoCP), Seq(), RoadAddressChangeType.Transfer, AdministrativeClass.State, reversed = false)
      )
      projectLinkDAO.create(projectLinks)

      connectedRoadways.flatMap(roadway => projectReservedPartDAO.fetchProjectReservedJunctions(roadway.roadNumber, roadway.roadPartNumber, 0L)).size should be (connectedRoadways.size)
      notConnectedroadways.flatMap(roadway => projectReservedPartDAO.fetchProjectReservedJunctions(roadway.roadNumber, roadway.roadPartNumber, 0L)).size should be (0)
    }
  }

  test("Check for reserved junctions take road part numbers into consideration") {
    runWithRollback {

      val junctionStartDate = DateTime.parse("2019-01-01")
      val roadwayStartDate = DateTime.parse("2000-01-01")

      val newIntersectingRoadway = Roadway(NewIdValue, NewIdValue, roadNumber1, roadPartNumber1, AdministrativeClass.State, Track.Combined, Discontinuity.Continuous,
        0, 100, reversed = false, roadwayStartDate, None, "test", Some("Test road 1"), 1, TerminationCode.NoTermination)
      val newIntersectingRoadway2 = Roadway(NewIdValue, NewIdValue, roadNumber1, roadPartNumber2, AdministrativeClass.State, Track.Combined, Discontinuity.Continuous,
        0, 100, reversed = false, roadwayStartDate, None, "test", Some("Test road 1"), 1, TerminationCode.NoTermination)
      val newReservedRoadway = Roadway(NewIdValue, NewIdValue, roadNumber2 , roadPartNumber1, AdministrativeClass.State, Track.Combined, Discontinuity.Continuous,
        0, 100, reversed = false, roadwayStartDate, None, "test", Some("Test road 2"), 1, TerminationCode.NoTermination)
      val newNotReservedRoadway = Roadway(NewIdValue, NewIdValue, roadNumber3, roadPartNumber1, AdministrativeClass.State, Track.Combined, Discontinuity.Continuous,
        0, 100, reversed = false, roadwayStartDate, None, "test", Some("Test road 3"), 1, TerminationCode.NoTermination)
      val newReservedRoadway2 = Roadway(NewIdValue, NewIdValue, roadNumber4, roadPartNumber1, AdministrativeClass.State, Track.Combined, Discontinuity.Continuous,
        0, 100, reversed = false, roadwayStartDate, None, "test", Some("Test road 4"), 1, TerminationCode.NoTermination)


      val connectedRoadways: Seq[Roadway] = Seq(newIntersectingRoadway, newIntersectingRoadway2, newReservedRoadway, newNotReservedRoadway, newReservedRoadway2)

      roadwayDAO.create(connectedRoadways)

      val intersectingRoadway = roadwayDAO.fetchAllBySection(newIntersectingRoadway.roadNumber, newIntersectingRoadway.roadPartNumber).head
      val intersectingRoadway2 = roadwayDAO.fetchAllBySection(newIntersectingRoadway2.roadNumber, newIntersectingRoadway2.roadPartNumber).head
      val reservedRoadway = roadwayDAO.fetchAllBySection(newReservedRoadway.roadNumber, newReservedRoadway.roadPartNumber).head
      val notReservedRoadway = roadwayDAO.fetchAllBySection(newNotReservedRoadway.roadNumber, newNotReservedRoadway.roadPartNumber).head
      val reservedRoadway2 = roadwayDAO.fetchAllBySection(newReservedRoadway2.roadNumber, newReservedRoadway2.roadPartNumber).head

      val intersectingRoadwayLocation  = LinearLocation(NewIdValue, 1, linkId1, 0.0, 2.8, SideCode.TowardsDigitizing, 10000000000L, (CalibrationPointReference.None, CalibrationPointReference.None), Seq(Point(99.0, 99.0), Point(101.0, 101.0)), LinkGeomSource.NormalLinkInterface, intersectingRoadway.roadwayNumber)
      val intersectingRoadwayLocation2 = LinearLocation(NewIdValue, 1, linkId1, 0.0, 2.8, SideCode.TowardsDigitizing, 10000000000L, (CalibrationPointReference.None, CalibrationPointReference.None), Seq(Point(99.0, 99.0), Point(101.0, 101.0)), LinkGeomSource.NormalLinkInterface, intersectingRoadway2.roadwayNumber)
      val reservedRoadwayLocation      = LinearLocation(NewIdValue, 1, linkId2, 0.0, 2.8, SideCode.TowardsDigitizing, 10000000000L, (CalibrationPointReference.None, CalibrationPointReference.None), Seq(Point(99.0, 99.0), Point(101.0, 101.0)), LinkGeomSource.NormalLinkInterface, reservedRoadway.roadwayNumber)
      val notReservedRoadwayLocation   = LinearLocation(NewIdValue, 1, linkId3, 0.0, 2.8, SideCode.TowardsDigitizing, 10000000000L, (CalibrationPointReference.None, CalibrationPointReference.None), Seq(Point(99.0, 99.0), Point(101.0, 101.0)), LinkGeomSource.NormalLinkInterface, notReservedRoadway.roadwayNumber)
      val reservedRoadway2Location     = LinearLocation(NewIdValue, 1, linkId4, 0.0, 2.8, SideCode.TowardsDigitizing, 10000000000L, (CalibrationPointReference.None, CalibrationPointReference.None), Seq(Point(99.0, 99.0), Point(101.0, 101.0)), LinkGeomSource.NormalLinkInterface, reservedRoadway2.roadwayNumber)

      linearLocationDAO.create(Seq(intersectingRoadwayLocation, intersectingRoadwayLocation2, reservedRoadwayLocation, notReservedRoadwayLocation, reservedRoadway2Location))

      //intersection with roadwayPoint1, roadwayPoint1 - roadways intersectingRoadway and reservedRoadway
      val roadwayPoint1 = roadwayPointDAO.fetch(roadwayPointDAO.create(RoadwayPoint(NewIdValue, intersectingRoadway.roadwayNumber, 0, "Test", None, None, None))) //in the same intersection as roadwayPoint2
      val roadwayPoint2 = roadwayPointDAO.fetch(roadwayPointDAO.create(RoadwayPoint(NewIdValue, reservedRoadway.roadwayNumber, 0, "Test", None, None, None))) //in the same intersection as roadwayPoint1

      //intersection with roadwayPoint3, roadwayPoint4 - roadways intersectingRoadway and notReservedRoadwayLocation
      val roadwayPoint3 = roadwayPointDAO.fetch(roadwayPointDAO.create(RoadwayPoint(NewIdValue, intersectingRoadway.roadwayNumber, 0, "Test", None, None, None))) //in the same intersection as roadwayPoint4
      val roadwayPoint4 = roadwayPointDAO.fetch(roadwayPointDAO.create(RoadwayPoint(NewIdValue, notReservedRoadwayLocation.roadwayNumber, 0, "Test", None, None, None))) //in the same intersection as roadwayPoint3

      //intersection with roadwayPoint5, roadwayPoint6 - roadways intersectingRoadway2 and reservedRoadway2
      val roadwayPoint5 = roadwayPointDAO.fetch(roadwayPointDAO.create(RoadwayPoint(NewIdValue, intersectingRoadway2.roadwayNumber, 0, "Test", None, None, None))) //in the same intersection as roadwayPoint6
      val roadwayPoint6 = roadwayPointDAO.fetch( roadwayPointDAO.create(RoadwayPoint(NewIdValue, reservedRoadway2.roadwayNumber, 0, "Test", None, None, None))) //in the same intersection as roadwayPoint5

      val junction1 = Junction(NewIdValue, None, None, junctionStartDate, None, junctionStartDate, None, "Test", None)
      val junction2 = Junction(NewIdValue, None, None, junctionStartDate, None, junctionStartDate, None, "Test", None)
      val junction3 = Junction(NewIdValue, None, None, junctionStartDate, None, junctionStartDate, None, "Test", None)
      val junctionId1 = junctionDAO.create(Seq(junction1)).head
      val junctionId2 = junctionDAO.create(Seq(junction2)).head
      val junctionId3 = junctionDAO.create(Seq(junction3)).head

      //both junction points (for roadwayPoint1, roadwayPoint2) share the same junction
      val junctionPoint1 = JunctionPoint(NewIdValue, BeforeAfter.Before, -1, -1, Some(junction1.startDate), junction1.endDate,
        DateTime.parse("2019-01-01"), None, "Test", None, -1, 10, 0, 0, Track.Combined, Discontinuity.Continuous)
      val junctionPoint2 = JunctionPoint(NewIdValue, BeforeAfter.Before, -1, -1, Some(junction1.startDate), junction1.endDate,
        DateTime.parse("2019-01-01"), None, "Test", None, -1, 10, 0, 0, Track.Combined, Discontinuity.Continuous)
      junctionPointDAO.create(Seq(junctionPoint1.copy(junctionId = junctionId1, roadwayPointId = roadwayPoint1.id, roadwayNumber = roadwayPoint1.roadwayNumber)))
      junctionPointDAO.create(Seq(junctionPoint2.copy(junctionId = junctionId1, roadwayPointId = roadwayPoint2.id, roadwayNumber = roadwayPoint2.roadwayNumber)))

      //both junction points (for roadwayPoint3, roadwayPoint4) share the same junction
      val junctionPoint3 = JunctionPoint(NewIdValue, BeforeAfter.Before, -1, -1, Some(junction2.startDate), junction2.endDate,
        DateTime.parse("2019-01-01"), None, "Test", None, -1, 10, 0, 0, Track.Combined, Discontinuity.Continuous)
      val junctionPoint4 = JunctionPoint(NewIdValue, BeforeAfter.Before, -1, -1, Some(junction2.startDate), junction2.endDate,
        DateTime.parse("2019-01-01"), None, "Test", None, -1, 10, 0, 0, Track.Combined, Discontinuity.Continuous)
      junctionPointDAO.create(Seq(junctionPoint3.copy(junctionId = junctionId2, roadwayPointId = roadwayPoint3.id, roadwayNumber = roadwayPoint3.roadwayNumber)))
      junctionPointDAO.create(Seq(junctionPoint4.copy(junctionId = junctionId2, roadwayPointId = roadwayPoint4.id, roadwayNumber = roadwayPoint4.roadwayNumber)))

      //both junction points (for roadwayPoint5, roadwayPoint6) share the same junction
      val junctionPoint5 = JunctionPoint(NewIdValue, BeforeAfter.Before, -1, -1, Some(junction3.startDate), junction3.endDate,
        DateTime.parse("2019-01-01"), None, "Test", None, -1, 10, 0, 0, Track.Combined, Discontinuity.Continuous)
      val junctionPoint6 = JunctionPoint(NewIdValue, BeforeAfter.Before, -1, -1, Some(junction3.startDate), junction3.endDate,
        DateTime.parse("2019-01-01"), None, "Test", None, -1, 10, 0, 0, Track.Combined, Discontinuity.Continuous)
      junctionPointDAO.create(Seq(junctionPoint5.copy(junctionId = junctionId3, roadwayPointId = roadwayPoint5.id, roadwayNumber = roadwayPoint5.roadwayNumber)))
      junctionPointDAO.create(Seq(junctionPoint6.copy(junctionId = junctionId3, roadwayPointId = roadwayPoint6.id, roadwayNumber = roadwayPoint6.roadwayNumber)))


      val id1 = Sequences.nextViiteProjectId
      val projectLinkId1 = Sequences.nextProjectLinkId
      val projectName1 = "Test Project 1"

      val id2 = Sequences.nextViiteProjectId
      val projectLinkId2 = Sequences.nextProjectLinkId
      val projectName2 = "Test Project 2"

      val rap1 = Project(id1, ProjectState.apply(1), projectName1, "TestUser", DateTime.parse("1901-01-01"), "TestUser", DateTime.parse("1901-01-01"), DateTime.now(), "Some additional info", List.empty, Seq(), None)
      val rap2 = Project(id2, ProjectState.apply(1), projectName2, "TestUser", DateTime.parse("1901-01-01"), "TestUser", DateTime.parse("1901-01-01"), DateTime.now(), "Some additional info", List.empty, Seq(), None)

      projectDAO.create(rap1)
      projectDAO.create(rap2)

      projectReservedPartDAO.reserveRoadPart(id1, reservedRoadway.roadNumber, reservedRoadway.roadPartNumber, rap1.createdBy)
      projectReservedPartDAO.reserveRoadPart(id2, reservedRoadway2.roadNumber, reservedRoadway2.roadPartNumber, rap2.createdBy)

      val projectLinks1 = Seq(dummyProjectLink(projectLinkId1, id1, reservedRoadwayLocation.linkId,  reservedRoadway.id,  reservedRoadway.roadwayNumber,  reservedRoadway.roadNumber,  reservedRoadway.roadPartNumber,  0, 100, 0.0, 100.0, None, (NoCP, NoCP), Seq(), RoadAddressChangeType.Transfer, AdministrativeClass.State, reversed = false))
      val projectLinks2 = Seq(dummyProjectLink(projectLinkId2, id2, reservedRoadway2Location.linkId, reservedRoadway2.id, reservedRoadway2.roadwayNumber, reservedRoadway2.roadNumber, reservedRoadway2.roadPartNumber, 0, 100, 0.0, 100.0, None, (NoCP, NoCP), Seq(), RoadAddressChangeType.Transfer, AdministrativeClass.State, reversed = false))

      projectLinkDAO.create(projectLinks1 ++ projectLinks2)

      projectReservedPartDAO.fetchProjectReservedJunctions(intersectingRoadway.roadNumber, intersectingRoadway.roadPartNumber, 0L).head should be (projectName1)
      projectReservedPartDAO.fetchProjectReservedJunctions(intersectingRoadway2.roadNumber, intersectingRoadway2.roadPartNumber, 0L).head should be (projectName2)
    }
  }
}

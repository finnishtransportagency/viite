package fi.liikennevirasto.viite.dao

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

  private val linearLocationId = 1

  private def dummyRoadAddressProject(id: Long, status: ProjectState, reservedParts: Seq[ProjectReservedPart] = List.empty[ProjectReservedPart], ely: Option[Long] = None, coordinates: Option[ProjectCoordinates] = None): RoadAddressProject ={
    RoadAddressProject(id, status, "testProject", "testUser", DateTime.parse("1901-01-01"), "testUser", DateTime.parse("1901-01-01"), DateTime.now(), "additional info here", reservedParts, Some("current status info"), ely, coordinates)
  }

  def dummyProjectLink(id: Long, projectId: Long, linkId : Long, roadwayId: Long = 0, roadwayNumber: Long = roadwayNumber1, roadNumber: Long = roadNumber1, roadPartNumber: Long =roadPartNumber1, startAddrMValue: Long, endAddrMValue: Long,
                       startMValue: Double, endMValue: Double, endDate: Option[DateTime] = None, calibrationPoints: (Option[ProjectLinkCalibrationPoint], Option[ProjectLinkCalibrationPoint]) = (None, None),
                       floating: FloatingReason = NoFloating, geometry: Seq[Point] = Seq(), status: LinkStatus, roadType: RoadType, reversed: Boolean): ProjectLink =
    ProjectLink(id, roadNumber, roadPartNumber, Track.Combined,
      Discontinuity.Continuous, startAddrMValue, endAddrMValue, Some(DateTime.parse("1901-01-01")),
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
  //TODO will be implemented at VIITE-1539
    test("Test isNotAvailableForProject case (1) When START_DATE > PROJ_DATE, END_DATE = null Then should be reservable") {
      runWithRollback {
        val id1 = Sequences.nextViitePrimaryKeySeqValue
        val rap1 = RoadAddressProject(id1, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("2700-01-01"), "TestUser", DateTime.parse("2700-01-01"), DateTime.now(), "Some additional info", List.empty[ProjectReservedPart], None)
        projectDAO.createRoadAddressProject(rap1)
        // Check that the DB contains only null values in end dates
        roadwayDAO.fetchAllByRoadAndPart(5, 205).map(_.endDate).forall(ed => ed.isEmpty) should be (true)
        val reserveNotAvailable = projectReservedPartDAO.isNotAvailableForProject(5, 205, id1)
        reserveNotAvailable should be (false)
      }
    }

    test("Test isNotAvailableForProject case (2a) When START_DATE > PROJ_DATE, END_DATE = null Then should NOT be reservable") {
      runWithRollback {
        val id = Sequences.nextViitePrimaryKeySeqValue
        val rap = RoadAddressProject(id, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("1901-01-01"), "TestUser", DateTime.parse("1901-01-01"), DateTime.now(), "Some additional info", List.empty[ProjectReservedPart], None)
        projectDAO.createRoadAddressProject(rap)
        val reserveNotAvailable = projectReservedPartDAO.isNotAvailableForProject(5,205,id)
        reserveNotAvailable should be (true)
      }
    }

    test("Test isNotAvailableForProject case (2b) When START_DATE == PROJ_DATE, END_DATE = null Then should NOT be reservable") {
      // Update: after VIITE-1411 we can have start date equal to project date
      runWithRollback {
        val id3 = Sequences.nextViitePrimaryKeySeqValue
        val rap3 = RoadAddressProject(id3, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("1962-11-01"),
          "TestUser", DateTime.parse("1962-11-01"), DateTime.now(), "Some additional info", List.empty[ProjectReservedPart], None)
        projectDAO.createRoadAddressProject(rap3)
        roadwayDAO.fetchAllByRoadAndPart(5, 207).map(r => r.startDate.toDate).min should be (DateTime.parse("1962-11-01").toDate)
        val reserved3 = projectReservedPartDAO.isNotAvailableForProject(5,207, id3)
        reserved3 should be (false)
      }
    }

//    test("New roadnumber and roadpart available because start date and end date before project date (3a)") {
//      runWithRollback {
//        createRoadAddress8888(Option.apply(DateTime.parse("1975-11-18")), Option.apply(DateTime.parse("2000-01-01")))
//        val id4 = Sequences.nextViitePrimaryKeySeqValue
//        val rap4 = RoadAddressProject(id4, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("2700-01-01"), "TestUser", DateTime.parse("2700-01-01"), DateTime.now(), "Some additional info", List.empty[ReservedRoadPart], None)
//        ProjectDAO.createRoadAddressProject(rap4)
//        val reserved4 = RoadAddressDAO.isNotAvailableForProject(8888,1,id4)
//        reserved4 should be (false)
//      }
//    }

  //TODO will be implemented at VIITE-1539
  //  test("New roadnumber and roadpart available because end date equals project date (3b)") {
  //    runWithRollback {
  //      createRoadAddress8888(Option.apply(DateTime.parse("1975-11-18")), Option.apply(DateTime.parse("2700-01-01")))
  //      val id5 = Sequences.nextViitePrimaryKeySeqValue
  //      val rap5 = RoadAddressProject(id5, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("2700-01-01"), "TestUser", DateTime.parse("2700-01-01"), DateTime.now(), "Some additional info", List.empty[ReservedRoadPart], None)
  //      ProjectDAO.createRoadAddressProject(rap5)
  //      val reserved5 = RoadAddressDAO.isNotAvailableForProject(8888,1,id5)
  //      reserved5 should be (false)
  //
  //    }
  //  }
  //TODO will be implemented at VIITE-1539
  //  test("New roadnumber and roadpart not available because project date between start and end date (4a)") {
  //    runWithRollback {
  //      createRoadAddress8888(Option.apply(DateTime.parse("1975-11-18")), Option.apply(DateTime.parse("2800-01-01")))
  //      val id6 = Sequences.nextViitePrimaryKeySeqValue
  //      val rap6 = RoadAddressProject(id6, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("2700-01-01"), "TestUser", DateTime.parse("2700-01-01"), DateTime.now(), "Some additional info", List.empty[ReservedRoadPart], None)
  //      ProjectDAO.createRoadAddressProject(rap6)
  //      val reserved6 = RoadAddressDAO.isNotAvailableForProject(8888,1,id6)
  //      reserved6 should be (true)
  //    }
  //  }

  //TODO will be implemented at VIITE-1539
  //  test("New roadnumber and roadpart not available because project date between start and end date (4b)") {
  //    runWithRollback {
  //      createRoadAddress8888(Option.apply(DateTime.parse("2700-01-01")), Option.apply(DateTime.parse("2800-01-01")))
  //      val id6 = Sequences.nextViitePrimaryKeySeqValue
  //      val rap6 = RoadAddressProject(id6, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("2700-01-01"), "TestUser", DateTime.parse("2700-01-01"), DateTime.now(), "Some additional info", List.empty[ReservedRoadPart], None)
  //      ProjectDAO.createRoadAddressProject(rap6)
  //      val reserved6 = RoadAddressDAO.isNotAvailableForProject(8888,1,id6)
  //      reserved6 should be (true)
  //
  //    }
  //  }

  //TODO will be implemented at VIITE-1539
  //  test("New roadnumber and roadpart number not reservable if it's going to exist in the future (5a)") {
  //    runWithRollback {
  //      val id7 = Sequences.nextViitePrimaryKeySeqValue
  //      val rap7 = RoadAddressProject(id7, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("1975-01-01"),
  //        "TestUser", DateTime.parse("1975-01-01"), DateTime.now(), "Some additional info", List.empty[ReservedRoadPart], None)
  //      ProjectDAO.createRoadAddressProject(rap7)
  //      createRoadAddress8888(Option.apply(DateTime.parse("1975-11-18")), Option.apply(DateTime.parse("1990-01-01")))
  //      val reserved7 = RoadAddressDAO.isNotAvailableForProject(8888,1,id7)
  //      reserved7 should be (true)
  //    }
  //  }

  //TODO will be implemented at VIITE-1539
  //  test("New roadnumber and roadpart number reservable if it's going to exist in the future (5b)") {
  //    runWithRollback {
  //      createRoadAddress8888(Option.apply(DateTime.parse("1975-01-01")))
  //      val id9 = Sequences.nextViitePrimaryKeySeqValue
  //      val rap9 = RoadAddressProject(id9, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("1975-01-01"),
  //        "TestUser", DateTime.parse("1975-01-01"), DateTime.now(), "Some additional info", List.empty[ReservedRoadPart], None)
  //      ProjectDAO.createRoadAddressProject(rap9)
  //      val reserved9 = RoadAddressDAO.isNotAvailableForProject(8888, 1, id9)
  //      reserved9 should be(false)
  //    }
  //  }

  //TODO will be implemented at VIITE-1539
  //  test("New roadnumber and roadpart available because last end date is open ended (6)") {
  //    runWithRollback {
  //      createRoadAddress8888(Option.apply(DateTime.parse("1975-11-18")), Option.apply(DateTime.parse("2000-01-01")))
  //      createRoadAddress8888(Option.apply(DateTime.parse("2000-01-01")), Option.apply(DateTime.parse("2001-01-01")))
  //      createRoadAddress8888(Option.apply(DateTime.parse("2001-01-01")))
  //      val id = Sequences.nextViitePrimaryKeySeqValue
  //      val rap = RoadAddressProject(id, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("2017-01-01"),
  //        "TestUser", DateTime.parse("2017-01-01"), DateTime.now(), "Some additional info", List.empty[ReservedRoadPart], None)
  //      ProjectDAO.createRoadAddressProject(rap)
  //      val reserved = RoadAddressDAO.isNotAvailableForProject(8888,1,id)
  //      reserved should be (false)
  //    }
  //  }

  //TODO will be implemented at VIITE-1539
  //  test("invalidated rows don't affect reservation (7)") {
  //    runWithRollback {
  //      createRoadAddress8888(Option.apply(DateTime.parse("1975-11-18")), Option.apply(DateTime.parse("2000-01-01")))
  //      val id = Sequences.nextViitePrimaryKeySeqValue
  //      val rap = RoadAddressProject(id, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("1997-01-01"),
  //        "TestUser", DateTime.parse("1997-01-01"), DateTime.now(), "Some additional info", List.empty[ReservedRoadPart], None)
  //      ProjectDAO.createRoadAddressProject(rap)
  //      RoadAddressDAO.isNotAvailableForProject(8888,1,id) should be (true)
  //      sqlu"""update ROADWAY set valid_to = sysdate WHERE road_number = 8888""".execute
  //      createRoadAddress8888(Option.apply(DateTime.parse("1975-11-18")), None)
  //      RoadAddressDAO.isNotAvailableForProject(8888,1,id) should be (false)
  //    }
  //  }

  //TODO will be implemented at VIITE-1539
  //  test("New roadnumber and roadpart number  reserved") {
  //    runWithRollback {
  //      val id = Sequences.nextViitePrimaryKeySeqValue
  //      val rap = RoadAddressProject(id, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("2700-01-01"), "TestUser", DateTime.parse("2700-01-01"), DateTime.now(), "Some additional info", List.empty[ReservedRoadPart], None)
  //      ProjectDAO.createRoadAddressProject(rap)
  //      val reserved=   RoadAddressDAO.isNotAvailableForProject(1234567899,1,id)
  //      reserved should be (false)
  //    }
  //  }

  //TODO will be implemented at VIITE-1539
  //  test("Terminated road reservation") {
  //    runWithRollback {
  //      val idr = RoadAddressDAO.getNextRoadwayId
  //      val ra = Seq(RoadAddress(idr, 1943845, 1, RoadType.Unknown, Track.Combined, Discontinuous, 0L, 10L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), 12345L, 0.0, 9.8, SideCode.TowardsDigitizing, 0, (None, None), NoFloating,
  //        Seq(Point(0.0, 0.0), Point(0.0, 9.8)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
  //      RoadAddressDAO.create(ra)
  //      val id = Sequences.nextViitePrimaryKeySeqValue
  //      val rap = RoadAddressProject(id, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("2700-01-01"), "TestUser", DateTime.parse("2700-01-01"), DateTime.now(), "Some additional info", List.empty[ReservedRoadPart], None)
  //      ProjectDAO.createRoadAddressProject(rap)
  //      val reserved=   RoadAddressDAO.isNotAvailableForProject(1943845,1,id)
  //      reserved should be (false)
  //    }
  //  }

  //TODO will be implemented at VIITE-1539
  //  test("Returning of a terminated road") {
  //    runWithRollback {
  //      createTerminatedRoadAddress7777(Option.apply(DateTime.parse("1975-11-18")))
  //      val roadAddresses = RoadAddressDAO.fetchByLinkId(Set(7777777))
  //      roadAddresses.size should be (1)
  //      roadAddresses.head.terminated.value should be (1)
  //    }
  //  }

  //TODO will be implemented at VIITE-1550
  //  test("Fetching road addresses by bounding box should ignore start dates") {
  //    runWithRollback {
  //      val addressId = RoadAddressDAO.getNextRoadwayId
  //      val futureDate = DateTime.now.plusDays(5)
  //      val ra = Seq(RoadAddress(addressId, 1943845, 1, RoadType.Unknown, Track.Combined, Discontinuous, 0L, 10L, Some(futureDate), None, Option("tester"), 12345L, 0.0, 9.8, SideCode.TowardsDigitizing, 0, (None, None), NoFloating,
  //        Seq(Point(1.0, 1.0), Point(1.0, 9.8)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
  //      val returning = RoadAddressDAO.create(ra)
  //      val currentSize = RoadAddressDAO.fetchByRoadPart(ra.head.roadNumber, ra.head.roadPartNumber).size
  //      currentSize > 0 should be(true)
  //      val bounding = BoundingRectangle(Point(0.0, 0.0), Point(10, 10))
  //      val fetchedAddresses = RoadAddressDAO.fetchRoadAddressesByBoundingBox(bounding, false)
  //      fetchedAddresses.exists(_.id == addressId) should be(true)
  //    }
  //  }

  test("Test reserveRoadPart When having reserved one project with that part Then should fetch it without any problems") {
    runWithRollback {
      val id = Sequences.nextViitePrimaryKeySeqValue
      val reservedParts = Seq(ProjectReservedPart(id: Long, roadNumber1: Long, roadPartNumber1: Long, Some(6L), Some(Discontinuity.apply("jatkuva")), Some(8L), newLength = None, newDiscontinuity = None, newEly = None))
      val rap = dummyRoadAddressProject(id, ProjectState.Incomplete, reservedParts, Some(8L), None)
      projectDAO.createRoadAddressProject(rap)
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
      val rap = RoadAddressProject(id, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("1901-01-01"), "TestUser", DateTime.parse("1901-01-01"), DateTime.now(), "Some additional info", List.empty, None)
      projectDAO.createRoadAddressProject(rap)
      projectReservedPartDAO.reserveRoadPart(id, roadNumber1, roadPartNumber1, rap.createdBy)
      val projectLinks = Seq(dummyProjectLink(projectLinkId, id, linkId1, roadwayIds.head, roadwayNumber1, roadNumber1, roadPartNumber1,  0, 100, 0.0, 100.0, None, (None, None), FloatingReason.NoFloating, Seq(),LinkStatus.Transfer, RoadType.PublicRoad, reversed = false)
      )
      projectLinkDAO.create(projectLinks)
      val project = projectReservedPartDAO.roadPartReservedByProject(roadNumber1, roadPartNumber1)
      project should be(Some("TestProject"))
      val reserved = projectReservedPartDAO.fetchReservedRoadPart(roadNumber1, roadPartNumber1)
      reserved.nonEmpty should be(true)
      projectReservedPartDAO.fetchReservedRoadParts(id) should have size 1
      projectReservedPartDAO.removeReservedRoadPart(id, reserved.get)
      val projectAfter = projectReservedPartDAO.roadPartReservedByProject(roadNumber1, roadPartNumber1)
      projectAfter should be(None)
      projectReservedPartDAO.fetchReservedRoadPart(roadNumber1, roadPartNumber1).isEmpty should be(true)
    }
  }

  test("Test fetchByProjectRoadParts When using fetchByRoadPart for two different parts should return same amount of project link ids ") {
    runWithRollback {
      val roadwayIds = roadwayDAO.create(dummyRoadways)
      val id = Sequences.nextViitePrimaryKeySeqValue
      val projectLinkId1 = id + 1
      val projectLinkId2 = id + 2
      val rap = RoadAddressProject(id, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("1901-01-01"), "TestUser", DateTime.parse("1901-01-01"), DateTime.now(), "Some additional info", List.empty, None)
      projectDAO.createRoadAddressProject(rap)
      projectReservedPartDAO.reserveRoadPart(id, roadNumber1, roadPartNumber1, rap.createdBy)
      projectReservedPartDAO.reserveRoadPart(id, roadNumber1, roadPartNumber2, rap.createdBy)
      val projectLinks = Seq(
      dummyProjectLink(projectLinkId1, id, linkId1, roadwayIds.head, roadwayNumber1, roadNumber1, roadPartNumber1,  0, 100, 0.0, 100.0, None, (None, None), FloatingReason.NoFloating, Seq(),LinkStatus.Transfer, RoadType.PublicRoad, reversed = false),
      dummyProjectLink(projectLinkId2, id, linkId1, roadwayIds.last, roadwayNumber1, roadNumber1, roadPartNumber2,  0, 100, 0.0, 100.0, None, (None, None), FloatingReason.NoFloating, Seq(),LinkStatus.Transfer, RoadType.PublicRoad, reversed = false)
      )
      projectLinkDAO.create(projectLinks)
      projectReservedPartDAO.roadPartReservedByProject(roadNumber1, roadPartNumber1) should be(Some("TestProject"))
      projectReservedPartDAO.roadPartReservedByProject(roadNumber1, roadPartNumber2) should be(Some("TestProject"))
      val reserved203 = projectLinkDAO.fetchByProjectRoadParts(Set((roadNumber1, roadPartNumber1)), id)
      reserved203.nonEmpty should be (true)
      val reserved205 = projectLinkDAO.fetchByProjectRoadParts(Set((roadNumber1, roadPartNumber2)), id)
      reserved205.nonEmpty should be (true)
      reserved203 shouldNot be (reserved205)
      reserved203.toSet.intersect(reserved205.toSet) should have size 0
      val reserved = projectLinkDAO.fetchByProjectRoadParts(Set((roadNumber1,roadPartNumber1), (roadNumber1, roadPartNumber2)), id)
      reserved.map(_.id).toSet should be (reserved203.map(_.id).toSet ++ reserved205.map(_.id).toSet)
      reserved should have size projectLinks.size
    }
  }

  //  test("roadpart reserved, fetched with and without filtering, and released by project test") {
  //    //Creation of Test road
  //    runWithRollback {
  //      val id = Sequences.nextViitePrimaryKeySeqValue
  //      val rap = RoadAddressProject(id, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("1901-01-01"), "TestUser", DateTime.parse("1901-01-01"), DateTime.now(), "Some additional info", List.empty, None)
  //      ProjectDAO.createRoadAddressProject(rap)
  //      ProjectDAO.reserveRoadPart(id, 5, 203, rap.createdBy)
  //      val addresses = RoadAddressDAO.fetchByRoadPart(5, 203).map(toProjectLink(rap))
  //      ProjectDAO.create(addresses)
  //      val project = ProjectDAO.roadPartReservedByProject(5, 203)
  //      project should be(Some("TestProject"))
  //      val reserved = ProjectDAO.fetchReservedRoadPart(5, 203)
  //      reserved.nonEmpty should be(true)
  //      ProjectDAO.fetchReservedRoadParts(id) should have size (1)
  //      ProjectDAO.removeReservedRoadPart(id, reserved.get)
  //      val projectAfter = ProjectDAO.roadPartReservedByProject(5, 203)
  //      projectAfter should be(None)
  //      ProjectDAO.fetchReservedRoadPart(5, 203).isEmpty should be(true)
  //    }
  //  }

  //  test("fetch by road parts") {
  //    //Creation of Test road
  //    runWithRollback {
  //      val id = Sequences.nextViitePrimaryKeySeqValue
  //      val rap = RoadAddressProject(id, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("1901-01-01"), "TestUser", DateTime.parse("1901-01-01"), DateTime.now(), "Some additional info", List.empty, None)
  //      ProjectDAO.createRoadAddressProject(rap)
  //      ProjectDAO.reserveRoadPart(id, 5, 203, rap.createdBy)
  //      ProjectDAO.reserveRoadPart(id, 5, 205, rap.createdBy)
  //      val addresses = (RoadAddressDAO.fetchByRoadPart(5, 203) ++ RoadAddressDAO.fetchByRoadPart(5, 205)).map(toProjectLink(rap))
  //      ProjectDAO.create(addresses)
  //      ProjectDAO.roadPartReservedByProject(5, 203) should be(Some("TestProject"))
  //      ProjectDAO.roadPartReservedByProject(5, 205) should be(Some("TestProject"))
  //      val reserved203 = ProjectDAO.fetchByProjectRoadParts(Set((5L, 203L)), id)
  //      reserved203.nonEmpty should be (true)
  //      val reserved205 = ProjectDAO.fetchByProjectRoadParts(Set((5L, 205L)), id)
  //      reserved205.nonEmpty should be (true)
  //      reserved203 shouldNot be (reserved205)
  //      reserved203.toSet.intersect(reserved205.toSet) should have size (0)
  //      val reserved = ProjectDAO.fetchByProjectRoadParts(Set((5L,203L), (5L, 205L)), id)
  //      reserved.map(_.id).toSet should be (reserved203.map(_.id).toSet ++ reserved205.map(_.id).toSet)
  //      reserved should have size (addresses.size)
  //    }
  //  }

}
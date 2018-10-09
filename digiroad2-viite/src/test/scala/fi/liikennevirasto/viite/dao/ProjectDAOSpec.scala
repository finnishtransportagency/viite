package fi.liikennevirasto.viite.dao

import fi.liikennevirasto.digiroad2.asset.{LinkGeomSource, SideCode}
import fi.liikennevirasto.digiroad2.{DigiroadEventBus, Point}
import fi.liikennevirasto.digiroad2.dao.Sequences
import fi.liikennevirasto.digiroad2.oracle.OracleDatabase
import fi.liikennevirasto.digiroad2.service.RoadLinkService
import fi.liikennevirasto.digiroad2.util.Track
import fi.liikennevirasto.viite._
import fi.liikennevirasto.viite.dao.LinkStatus.Transfer
import fi.liikennevirasto.viite.process.RoadwayAddressMapper
import org.joda.time.DateTime
import org.scalatest.mock.MockitoSugar
import org.scalatest.{FunSuite, Matchers}
import slick.driver.JdbcDriver.backend.Database
import slick.driver.JdbcDriver.backend.Database.dynamicSession
import slick.jdbc.StaticQuery.interpolation

/**
  * Class to test DB trigger that does not allow reserving already reserved links to project
  */
class ProjectDAOSpec extends FunSuite with Matchers {
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
  private def dummyRoadAddressProject(id: Long, status: ProjectState, reservedParts: Seq[ProjectReservedPart] = List.empty[ProjectReservedPart], ely: Option[Long] = None, coordinates: Option[ProjectCoordinates] = None): RoadAddressProject ={
    RoadAddressProject(id, status, "testProject", "testUser", DateTime.parse("1901-01-01"), "testUser", DateTime.parse("1901-01-01"), DateTime.now(), "additional info here", reservedParts, Some("status info"), ely, coordinates)
  }

  private def dummyRoadways(): Seq[Roadway] = {
    Seq(Roadway(NewRoadway, roadwayNumber1, roadNumber1, roadPartNumber1, RoadType.PublicRoad, Track.Combined, Discontinuity.Continuous,
      0, 100, false, DateTime.parse("2000-01-01"), None, "testUser", Some("Test Rd. 1"), 1, TerminationCode.NoTermination),
    Roadway(NewRoadway, roadwayNumber2, roadNumber1, roadPartNumber2, RoadType.PublicRoad, Track.Combined, Discontinuity.Continuous,
      0, 100, false, DateTime.parse("2000-01-01"), None, "testUser", Some("Test Rd. 1"), 1, TerminationCode.NoTermination)
    )
  }

  private def dummyLinearLocations(): Seq[LinearLocation] = {
    Seq(LinearLocation(NewLinearLocation, 1, linkId1, 0.0, 100.0, SideCode.TowardsDigitizing, 10000000000l,
      (Some(0l), None), FloatingReason.NoFloating, Seq(Point(0.0, 0.0), Point(0.0, 100.0)), LinkGeomSource.NormalLinkInterface, roadwayNumber1),
      LinearLocation(NewLinearLocation, 1, linkId2, 0.0, 100.0, SideCode.TowardsDigitizing, 10000000000l,
      (Some(100l), None), FloatingReason.NoFloating, Seq(Point(0.0, 0.0), Point(0.0, 100.0)), LinkGeomSource.NormalLinkInterface, roadwayNumber2)
    )
  }

  test("Test createRoadAddressProject When having valid data and empty parts Then should create project without any reserved part") {
    val id = Sequences.nextViitePrimaryKeySeqValue
    val rap = dummyRoadAddressProject(id, ProjectState.Incomplete, Seq(), Some(8L), None)
    projectDAO.createRoadAddressProject(rap)
    projectDAO.getRoadAddressProjectById(id).nonEmpty should be(true)
    projectDAO.getRoadAddressProjectById(id).head.reservedParts.isEmpty should be(true)
  }

  test("Test getProjectsWithWaitingTRStatus When project is sent to TR Then projects waiting TR response should be increased") {
    val reservedPart = ProjectReservedPart(5: Long, 203: Long, 203: Long, Some(6L), Some(Discontinuity.apply("jatkuva")), Some(8L), newLength = None, newDiscontinuity = None, newEly = None)
    runWithRollback {
      val waitingCountP = projectDAO.getProjectsWithWaitingTRStatus().length
      val id = Sequences.nextViitePrimaryKeySeqValue
      val rap =  dummyRoadAddressProject(id, ProjectState.Sent2TR, List(reservedPart), None, None)
        projectDAO.createRoadAddressProject(rap)
      val waitingCountNow = projectDAO.getProjectsWithWaitingTRStatus().length
      waitingCountNow - waitingCountP should be(1)
    }
  }

  test("Test updateProjectStatus When Update project status Then project status should be updated") {
    runWithRollback {
      val id = Sequences.nextViitePrimaryKeySeqValue
      val rap =  dummyRoadAddressProject(id, ProjectState.Sent2TR, List(), None, None)
      projectDAO.createRoadAddressProject(rap)
      projectDAO.updateProjectStatus(id, ProjectState.Saved2TR)
      projectDAO.getProjectStatus(id) should be(Some(ProjectState.Saved2TR))
    }
  }

  test("Test updateRoadAddressProject When Update project info Then should update the project infos such as project name, additional info, startDate") {
    val reservedPart = ProjectReservedPart(5: Long, 203: Long, 203: Long, Some(6L), Some(Discontinuity.apply("jatkuva")), Some(8L), newLength = None, newDiscontinuity = None, newEly = None)
    runWithRollback {
      val id = Sequences.nextViitePrimaryKeySeqValue
      val rap = dummyRoadAddressProject(id, ProjectState.Incomplete, List(), None, None)
      val updatedRap = RoadAddressProject(id, ProjectState.apply(1), "newname", "TestUser", DateTime.parse("1901-01-02"), "TestUser", DateTime.parse("1901-01-02"), DateTime.now(), "updated info", List(reservedPart), None)
      projectDAO.createRoadAddressProject(rap)
      projectDAO.updateRoadAddressProject(updatedRap)
      projectDAO.getRoadAddressProjectById(id) match {
        case Some(project) =>
          project.name should be("newname")
          project.additionalInfo should be("updated info")
          project.startDate should be(DateTime.parse("1901-01-02"))
          project.dateModified.getMillis should be > DateTime.parse("1901-01-03").getMillis + 100000000
        case None => None should be(RoadAddressProject)
      }
    }
  }

  test("Test getRoadAddressProjects When get adding one new project Then outcome size of projects should be bigger than before") {
    runWithRollback {
      val projectListSize = projectDAO.getRoadAddressProjects().length
      val id = Sequences.nextViitePrimaryKeySeqValue
      val rap = dummyRoadAddressProject(id, ProjectState.Incomplete, List.empty[ProjectReservedPart], None, None)
      projectDAO.createRoadAddressProject(rap)
      val projectList = projectDAO.getRoadAddressProjects()
      projectList.length - projectListSize should be(1)
    }
  }

  //TODO Will be implemented at VIITE-1539
  test("Test updateProjectEly When updating Ely for project Then ely should change") {
    runWithRollback {
      val id = Sequences.nextViitePrimaryKeySeqValue
      val rap = dummyRoadAddressProject(id, ProjectState.Incomplete, List.empty[ProjectReservedPart], None, None)
      projectDAO.createRoadAddressProject(rap)
      projectDAO.getRoadAddressProjectById(id).nonEmpty should be(true)
      projectDAO.updateProjectEly(id, 100)
      projectDAO.getProjectEly(id).get should be(100)
    }
  }

  test("Test getProjectsWithSendingToTRStatus When there is one project in SendingToTR status Then should return that one project") {
    val address = ProjectReservedPart(5: Long, 203: Long, 203: Long, Some(6L), Some(Discontinuity.apply("jatkuva")), Some(8L), newLength = None, newDiscontinuity = None, newEly = None)
    runWithRollback {
      val waitingCountP = projectDAO.getProjectsWithSendingToTRStatus().length
      val id = Sequences.nextViitePrimaryKeySeqValue
      val rap = dummyRoadAddressProject(id, ProjectState.SendingToTR, List.empty[ProjectReservedPart], None, None)
      projectDAO.createRoadAddressProject(rap)
      val waitingCountNow = projectDAO.getProjectsWithSendingToTRStatus().length
      waitingCountNow - waitingCountP should be(1)
    }
  }

  //  test("Test createRoadAddressProject When having reserved part and links Then should return project links with geometry") {
  //    val reservedParts = Seq(ProjectReservedPart(5: Long, 203: Long, 203: Long, Some(6L), Some(Discontinuity.apply("jatkuva")), Some(8L), newLength = None, newDiscontinuity = None, newEly = None))
  //    runWithRollback {
  //      val id = Sequences.nextViitePrimaryKeySeqValue
  //      val rap = dummyRoadAddressProject(id, ProjectState.Incomplete, reservedParts, Some(8L), None)
  //      projectDAO.createRoadAddressProject(rap)
  //      val roadways = dummyRoadways()
  //      roadwayDAO.create(roadways)
  //      roadwayDAO.create(roadways)
  //      val addresses = roadwayDAO.fetchAllByRoadAndPart(5, 203).map(toProjectLink(rap))
  //      projectReservedPartDAO.reserveRoadPart(id, 5, 203, "TestUser")
  //      projectLinkDAO.create(addresses)
  //      projectDAO.getRoadAddressProjectById(id).nonEmpty should be(true)
  //      val projectlinks = projectLinkDAO.getProjectLinks(id)
  //      projectlinks.length should be > 0
  //      projectlinks.forall(_.status == LinkStatus.NotHandled) should be(true)
  //      projectlinks.forall(_.geometry.size == 2) should be (true)
  //      projectlinks.sortBy(_.endAddrMValue).map(_.geometry).zip(addresses.sortBy(_.endAddrMValue).map(_.geometry)).forall {case (x, y) => x == y}
  //      projectLinkDAO.fetchFirstLink(id, 5, 203) should be(Some(projectlinks.minBy(_.startAddrMValue)))
  //    }
  //  }

//  test("Test Create project with no reversed links") {
//    runWithRollback {
//      val id = Sequences.nextViitePrimaryKeySeqValue
//      val rap = RoadAddressProject(id, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("1901-01-01"), "TestUser", DateTime.parse("1901-01-01"), DateTime.now(), "Some additional info", List.empty, None)
//      ProjectDAO.createRoadAddressProject(rap)
//      ProjectDAO.reserveRoadPart(id, 5, 203, rap.createdBy)
//      val addresses = RoadAddressDAO.fetchByRoadPart(5, 203).map(toProjectLink(rap))
//      ProjectDAO.create(addresses.map(x => x.copy(reversed = false)))
//      val projectLinks = ProjectDAO.getProjectLinks(id)
//      projectLinks.count(x => x.reversed) should be(0)
//    }
//  }
}
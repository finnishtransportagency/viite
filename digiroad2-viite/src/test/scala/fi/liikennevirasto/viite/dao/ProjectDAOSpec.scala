package fi.liikennevirasto.viite.dao

import fi.liikennevirasto.digiroad2.asset.SideCode.TowardsDigitizing
import fi.liikennevirasto.digiroad2.asset.{LinkGeomSource, SideCode}
import fi.liikennevirasto.digiroad2.{DigiroadEventBus, GeometryUtils, Point}
import fi.liikennevirasto.digiroad2.dao.Sequences
import fi.liikennevirasto.digiroad2.oracle.OracleDatabase
import fi.liikennevirasto.digiroad2.service.RoadLinkService
import fi.liikennevirasto.digiroad2.util.Track
import fi.liikennevirasto.viite._
import fi.liikennevirasto.viite.dao.FloatingReason.NoFloating
import fi.liikennevirasto.viite.process.RoadwayAddressMapper
import org.joda.time.DateTime
import org.scalatest.mock.MockitoSugar
import org.scalatest.{FunSuite, Matchers}
import slick.driver.JdbcDriver.backend.Database
import slick.driver.JdbcDriver.backend.Database.dynamicSession

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

  private val roadwayNumber1 = 1000l
  private val roadwayNumber2 = 2000l
  private val roadwayNumber3 = 3000l

  private val linkId1 = 1000l
  private val linkId2 = 2000l

  private val linearLocationId = 0

  private def dummyRoadAddressProject(id: Long, status: ProjectState, reservedParts: Seq[ProjectReservedPart] = List.empty[ProjectReservedPart], ely: Option[Long] = None, coordinates: Option[ProjectCoordinates] = None): Project ={
    Project(id, status, "testProject", "testUser", DateTime.parse("1901-01-01"), "testUser", DateTime.parse("1901-01-01"), DateTime.now(), "additional info here", reservedParts, Some("current status info"), ely, coordinates)
  }

  private def dummyRoadways: Seq[Roadway] = {
    Seq(Roadway(NewRoadway, roadwayNumber1, roadNumber1, roadPartNumber1, RoadType.PublicRoad, Track.Combined, Discontinuity.Continuous,
      0, 100, false, DateTime.parse("2000-01-01"), None, "testUser", Some("Test Rd. 1"), 1, TerminationCode.NoTermination),
    Roadway(NewRoadway, roadwayNumber2, roadNumber1, roadPartNumber2, RoadType.PublicRoad, Track.Combined, Discontinuity.Continuous,
      0, 100, false, DateTime.parse("2000-01-01"), None, "testUser", Some("Test Rd. 1"), 1, TerminationCode.NoTermination)
    )
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

  //TODO test coverage missing for ProjectDAO methods:
  /**
    * addRotatingTRProjectId
    * getRotatingTRProjectId
    * removeRotatingTRProjectId
    * updateProjectStateInfo
    * updateProjectCoordinates
    * uniqueName
    */

  test("Test fetchAllIdsByLinkId When adding some project links for two existing projects Then outcome size of projects for that given linkId should be equal in number") {
    runWithRollback {
      val roadwayIds = roadwayDAO.create(dummyRoadways)

      val projId1 = Sequences.nextViitePrimaryKeySeqValue
      val rap =  dummyRoadAddressProject(projId1, ProjectState.Incomplete, List(), None, None)
      projectDAO.create(rap)

      val projId2 = Sequences.nextViitePrimaryKeySeqValue
      val rap2 =  dummyRoadAddressProject(projId2, ProjectState.Incomplete, List(), None, None)
      projectDAO.create(rap2)

      projectReservedPartDAO.reserveRoadPart(projId1, roadNumber1, roadPartNumber1, "TestUser")
      projectReservedPartDAO.reserveRoadPart(projId2, roadNumber2, roadPartNumber1, "TestUser")

      val waitingCountP1 = projectDAO.fetchAllIdsByLinkId(linkId1).length
      val waitingCountP2 = projectDAO.fetchAllIdsByLinkId(linkId2).length

      val projectLinkId1 = projId1 + 3
      val projectLinkId2 = projId1 + 4
      val projectLinks = Seq(
        dummyProjectLink(projectLinkId1, projId1, linkId1, roadwayIds.head, roadwayNumber1, roadNumber1, roadPartNumber1, 0, 100, 0.0, 100.0, None, (None, None), FloatingReason.NoFloating, Seq(),LinkStatus.Transfer, RoadType.PublicRoad, reversed = true),
        dummyProjectLink(projectLinkId2, projId2, linkId2, roadwayIds.last, roadwayNumber1, roadNumber2, roadPartNumber1, 0, 100, 0.0, 100.0, None, (None, None), FloatingReason.NoFloating, Seq(),LinkStatus.Transfer, RoadType.PublicRoad, reversed = true)
      )
      projectLinkDAO.create(projectLinks)
      val waitingCountNow1 = projectDAO.fetchAllIdsByLinkId(linkId1).length
      val waitingCountNow2 = projectDAO.fetchAllIdsByLinkId(linkId2).length
      waitingCountNow1 - waitingCountP1 should be(1)
      waitingCountNow2 - waitingCountP2 should be(1)
    }
  }

  //TODO when there is the need to have TR_ID in RoadAddressProject
  test("Test getRotatingTRProjectId When  Then ") {
  }
    //TODO when there is the need to have TR_ID in RoadAddressProject
  test("Test addRotatingTRProjectId When  Then ") {
  }

  //TODO when there is the need to have TR_ID in RoadAddressProject
  test("Test removeRotatingTRProjectId When Then ") {
  }

  test("Test updateProjectStateInfo When project info is updated Then project info should change") {
    runWithRollback {
      val projectListSize = projectDAO.fetchAll().length
      val id = Sequences.nextViitePrimaryKeySeqValue
      val rap = dummyRoadAddressProject(id, ProjectState.Incomplete, List.empty[ProjectReservedPart], ely = None, coordinates = None)
      projectDAO.create(rap)
      projectDAO.fetchById(id) match {
        case Some(project) =>
        project.statusInfo should be (Some("current status info"))
        case None => None should be(Project)
      }
      projectDAO.updateProjectStateInfo("updated info", id)
      projectDAO.fetchById(id) match {
        case Some(project) =>
        project.statusInfo should be (Some("updated info"))
        case None => None should be(Project)
      }
    }
  }

  test("Test updateProjectCoordinates When  Then ") {
    runWithRollback {
      val id = Sequences.nextViitePrimaryKeySeqValue
      val rap =  dummyRoadAddressProject(id, ProjectState.Incomplete, List(), None, None)
      projectDAO.create(rap)
      projectDAO.fetchById(id).get.coordinates.get.zoom should be(0)
      val coordinates = ProjectCoordinates(0.0, 1.0, 4)
      projectDAO.updateProjectCoordinates(id, coordinates)
      projectDAO.fetchById(id).get.coordinates.get.zoom should be(4)
    }
  }

  test("Test uniqueName When  Then ") {
    runWithRollback {
      val id = Sequences.nextViitePrimaryKeySeqValue
      val rap1 =  dummyRoadAddressProject(id, ProjectState.Incomplete, List(), None, None)
      val rap2 =  dummyRoadAddressProject(id+1, ProjectState.Incomplete, List(), None, None)
      projectDAO.create(rap1)
      projectDAO.create(rap2)
      rap1.name should be (rap2.name)
      projectDAO.uniqueName(rap1.id, rap1.name) should be (true)
    }
  }

  test("Test create When having valid data and empty parts Then should create project without any reserved part") {
    runWithRollback {
      val id = Sequences.nextViitePrimaryKeySeqValue
      val rap = dummyRoadAddressProject(id, ProjectState.Incomplete, Seq(), Some(8L), None)
      projectDAO.create(rap)
      projectDAO.fetchById(id).nonEmpty should be(true)
      projectDAO.fetchById(id).head.reservedParts.isEmpty should be(true)
    }
  }

  test("Test getProjectsWithWaitingTRStatus When project is sent to TR Then projects waiting TR response should be increased") {
    val reservedPart = ProjectReservedPart(5: Long, 203: Long, 203: Long, Some(6L), Some(Discontinuity.apply("jatkuva")), Some(8L), newLength = None, newDiscontinuity = None, newEly = None)
    runWithRollback {
      val waitingCountP = projectDAO.getProjectsWithWaitingTRStatus.length
      val id = Sequences.nextViitePrimaryKeySeqValue
      val rap =  dummyRoadAddressProject(id, ProjectState.Sent2TR, List(reservedPart), None, None)
        projectDAO.create(rap)
      val waitingCountNow = projectDAO.getProjectsWithWaitingTRStatus.length
      waitingCountNow - waitingCountP should be(1)
    }
  }

  test("Test updateProjectStatus When Update project status Then project status should be updated") {
    runWithRollback {
      val id = Sequences.nextViitePrimaryKeySeqValue
      val rap =  dummyRoadAddressProject(id, ProjectState.Sent2TR, List(), None, None)
      projectDAO.create(rap)
      projectDAO.updateProjectStatus(id, ProjectState.Saved2TR)
      projectDAO.getProjectStatus(id) should be(Some(ProjectState.Saved2TR))
    }
  }

  test("Test update When Update project info Then should update the project infos such as project name, additional info, startDate") {
    val reservedPart = ProjectReservedPart(5: Long, 203: Long, 203: Long, Some(6L), Some(Discontinuity.apply("jatkuva")), Some(8L), newLength = None, newDiscontinuity = None, newEly = None)
    runWithRollback {
      val id = Sequences.nextViitePrimaryKeySeqValue
      val rap = dummyRoadAddressProject(id, ProjectState.Incomplete, List(), None, None)
      val updatedRap = Project(id, ProjectState.apply(1), "newname", "TestUser", DateTime.parse("1901-01-02"), "TestUser", DateTime.parse("1901-01-02"), DateTime.now(), "updated info", List(reservedPart), None)
      projectDAO.create(rap)
      projectDAO.update(updatedRap)
      projectDAO.fetchById(id) match {
        case Some(project) =>
          project.name should be("newname")
          project.additionalInfo should be("updated info")
          project.startDate should be(DateTime.parse("1901-01-02"))
          project.dateModified.getMillis should be > DateTime.parse("1901-01-03").getMillis + 100000000
        case None => None should be(Project)
      }
    }
  }

  test("Test getRoadAddressProjects When adding one new project Then outcome size of projects should be bigger than before") {
    runWithRollback {
      val projectListSize = projectDAO.fetchAll().length
      val id = Sequences.nextViitePrimaryKeySeqValue
      val rap = dummyRoadAddressProject(id, ProjectState.Incomplete, List.empty[ProjectReservedPart], None, None)
      projectDAO.create(rap)
      val projectList = projectDAO.fetchAll()
      projectList.length - projectListSize should be(1)
    }
  }

  test("Test updateProjectEly When updating Ely for project Then ely should change") {
    runWithRollback {
      val id = Sequences.nextViitePrimaryKeySeqValue
      val rap = dummyRoadAddressProject(id, ProjectState.Incomplete, List.empty[ProjectReservedPart], None, None)
      projectDAO.create(rap)
      projectDAO.fetchById(id).nonEmpty should be(true)
      projectDAO.updateProjectEly(id, 99)
      projectDAO.fetchProjectElyById(id).get should be(99)
    }
  }

  test("Test getProjectsWithSendingToTRStatus When there is one project in SendingToTR status Then should return that one project") {
    val address = ProjectReservedPart(5: Long, 203: Long, 203: Long, Some(6L), Some(Discontinuity.apply("jatkuva")), Some(8L), newLength = None, newDiscontinuity = None, newEly = None)
    runWithRollback {
      val waitingCountP = projectDAO.getProjectsWithSendingToTRStatus.length
      val id = Sequences.nextViitePrimaryKeySeqValue
      val rap = dummyRoadAddressProject(id, ProjectState.SendingToTR, List.empty[ProjectReservedPart], None, None)
      projectDAO.create(rap)
      val waitingCountNow = projectDAO.getProjectsWithSendingToTRStatus.length
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
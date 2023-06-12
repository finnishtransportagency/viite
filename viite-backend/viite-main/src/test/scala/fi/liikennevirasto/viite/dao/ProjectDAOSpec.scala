package fi.liikennevirasto.viite.dao

import fi.liikennevirasto.digiroad2.DigiroadEventBus
import fi.liikennevirasto.digiroad2.dao.Sequences
import fi.liikennevirasto.digiroad2.postgis.PostGISDatabase
import fi.liikennevirasto.digiroad2.service.RoadLinkService
import fi.liikennevirasto.viite._
import fi.liikennevirasto.viite.dao.CalibrationPointDAO.CalibrationPointType
import fi.liikennevirasto.viite.dao.CalibrationPointDAO.CalibrationPointType.NoCP
import fi.liikennevirasto.viite.process.RoadwayAddressMapper
import fi.vaylavirasto.viite.geometry.{GeometryUtils, Point}
import fi.vaylavirasto.viite.model.{AdministrativeClass, Discontinuity, LinkGeomSource, LinkStatus, SideCode, Track}
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
    Database.forDataSource(PostGISDatabase.ds).withDynTransaction {
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

  private val linkId1 = 1000l.toString
  private val linkId2 = 2000l.toString

  private val linearLocationId = 0

  private def dummyProject(id: Long, status: ProjectState, reservedParts: Seq[ProjectReservedPart] = List.empty[ProjectReservedPart], coordinates: Option[ProjectCoordinates] = None): Project ={
    Project(id, status, "testProject", "testUser", DateTime.parse("1901-01-01"), "testUser", DateTime.parse("1901-01-01"),
      DateTime.now(), "additional info here", reservedParts, Seq(), Some("current status info"), coordinates)
  }

  private def dummyRoadways: Seq[Roadway] = {
    Seq(Roadway(NewIdValue, roadwayNumber1, roadNumber1, roadPartNumber1, AdministrativeClass.State, Track.Combined, Discontinuity.Continuous, 0, 100, reversed = false, DateTime.parse("2000-01-01"), None, "testUser", Some("Test Rd. 1"), 1, TerminationCode.NoTermination),
    Roadway(NewIdValue, roadwayNumber2, roadNumber1, roadPartNumber2, AdministrativeClass.State, Track.Combined, Discontinuity.Continuous, 0, 100, reversed = false, DateTime.parse("2000-01-01"), None, "testUser", Some("Test Rd. 1"), 1, TerminationCode.NoTermination)
    )
  }

  def dummyProjectLink(id: Long, projectId: Long, linkId: String, roadwayId: Long = 0, roadwayNumber: Long = roadwayNumber1, roadNumber: Long = roadNumber1, roadPartNumber: Long = roadPartNumber1, startAddrMValue: Long, endAddrMValue: Long, startMValue: Double, endMValue: Double, endDate: Option[DateTime] = None, calibrationPointTypes: (CalibrationPointType, CalibrationPointType), geometry: Seq[Point] = Seq(), status: LinkStatus, administrativeClass: AdministrativeClass, reversed: Boolean): ProjectLink =
    ProjectLink(id, roadNumber, roadPartNumber, Track.Combined, Discontinuity.Continuous, startAddrMValue, endAddrMValue, startAddrMValue, endAddrMValue,
      Some(DateTime.parse("1901-01-01")), endDate, Some("testUser"), linkId, startMValue, endMValue, SideCode.TowardsDigitizing,
      calibrationPointTypes, (NoCP, NoCP), geometry, projectId, status, administrativeClass, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geometry),
      roadwayId, linearLocationId, 0, reversed, connectedLinkId = None, 631152000, roadwayNumber, roadAddressLength = Some(endAddrMValue - startAddrMValue))

  test("Test fetchAllIdsByLinkId When adding some project links for two existing projects Then outcome size of projects for that given linkId should be equal in number") {
    runWithRollback {
      val roadwayIds = roadwayDAO.create(dummyRoadways)

      val projId1 = Sequences.nextViiteProjectId
      val rap =  dummyProject(projId1, ProjectState.Incomplete, List(), None)
      projectDAO.create(rap)

      val projId2 = Sequences.nextViiteProjectId
      val rap2 =  dummyProject(projId2, ProjectState.Incomplete, List(), None)
      projectDAO.create(rap2)

      projectReservedPartDAO.reserveRoadPart(projId1, roadNumber1, roadPartNumber1, "TestUser")
      projectReservedPartDAO.reserveRoadPart(projId2, roadNumber2, roadPartNumber1, "TestUser")

      val waitingCountP1 = projectDAO.fetchAllIdsByLinkId(linkId1).length
      val waitingCountP2 = projectDAO.fetchAllIdsByLinkId(linkId2).length

      val projectLinkId1 = Sequences.nextProjectLinkId
      val projectLinkId2 = Sequences.nextProjectLinkId
      val projectLinks = Seq(
        dummyProjectLink(projectLinkId1, projId1, linkId1, roadwayIds.head, roadwayNumber1, roadNumber1, roadPartNumber1, 0, 100, 0.0, 100.0, None, (NoCP, NoCP), Seq(), LinkStatus.Transfer, AdministrativeClass.State, reversed = true),
        dummyProjectLink(projectLinkId2, projId2, linkId2, roadwayIds.last, roadwayNumber1, roadNumber2, roadPartNumber1, 0, 100, 0.0, 100.0, None, (NoCP, NoCP), Seq(), LinkStatus.Transfer, AdministrativeClass.State, reversed = true)
      )
      projectLinkDAO.create(projectLinks)
      val waitingCountNow1 = projectDAO.fetchAllIdsByLinkId(linkId1).length
      val waitingCountNow2 = projectDAO.fetchAllIdsByLinkId(linkId2).length
      waitingCountNow1 - waitingCountP1 should be(1)
      waitingCountNow2 - waitingCountP2 should be(1)
    }
  }

  /** @deprecated Tierekisteri connection has been removed from Viite. TRId to be removed, too. */
  test("Test fetchTRIdByProjectId When the project has Tierekisteri identifier Then should return Tierekisteri identifier") {
    runWithRollback {
      val projectId = Sequences.nextViiteProjectId
      val project =  dummyProject(projectId, ProjectState.Incomplete, List(), None)
      projectDAO.create(project)

      val trId = Sequences.nextViiteProjectId
      sqlu"""update project set tr_id = $trId where id = $projectId""".execute

      val assignedTrId = projectDAO.fetchTRIdByProjectId(projectId)

      assignedTrId should be (Some(trId))
    }
  }

  /** @deprecated Tierekisteri connection has been removed from Viite. TRId to be removed, too. */
  test("Test assignNewProjectTRId When the project has Tierekisteri identifier Then should assign a new Tierekisteri identifier to the project") {
    runWithRollback {
      val projectId = Sequences.nextViiteProjectId
      val project =  dummyProject(projectId, ProjectState.Incomplete, List(), None)
      projectDAO.create(project)

      val oldTrId = projectDAO.fetchTRIdByProjectId(projectId)

      projectDAO.assignNewProjectTRId(projectId)

      val newTrId = projectDAO.fetchTRIdByProjectId(projectId)

      oldTrId should not be newTrId
    }
  }

  /** @deprecated Tierekisteri connection has been removed from Viite. TRId to be removed, too. */
  test("Test removeProjectTRId When the project has Tierekisteri identifier Then should be removed") {
    runWithRollback {
      val projectId = Sequences.nextViiteProjectId
      val project =  dummyProject(projectId, ProjectState.Incomplete, List(), None)
      projectDAO.create(project)

      projectDAO.assignNewProjectTRId(projectId)

      projectDAO.removeProjectTRId(projectId)

      val trId = projectDAO.fetchTRIdByProjectId(projectId)

      trId should be (None)
    }
  }

  test("Test updateProjectStateInfo When project info is updated Then project info should change") {
    runWithRollback {
      val id = Sequences.nextViiteProjectId
      val rap = dummyProject(id, ProjectState.Incomplete, List.empty[ProjectReservedPart], coordinates = None)
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

  test("Test updateProjectCoordinates When using a recently created project Then the zoom level of the project should be the updated one.") {
    runWithRollback {
      val id = Sequences.nextViiteProjectId
      val rap =  dummyProject(id, ProjectState.Incomplete, List(), None)
      projectDAO.create(rap)
      projectDAO.fetchById(id).get.coordinates.get.zoom should be(0)
      val coordinates = ProjectCoordinates(0.0, 1.0, 4)
      projectDAO.updateProjectCoordinates(id, coordinates)
      projectDAO.fetchById(id).get.coordinates.get.zoom should be(4)
    }
  }

  test("Test isUniqueName When creating two different project with different names Then  the check for the uniqueness should return true.") {
    runWithRollback {
      val id = Sequences.nextViiteProjectId
      val rap1 =  dummyProject(id, ProjectState.Incomplete, List(), None)
      val rap2 =  dummyProject(id+1, ProjectState.Incomplete, List(), None)
      projectDAO.create(rap1)
      projectDAO.create(rap2)
      rap1.name should be (rap2.name)
      projectDAO.isUniqueName(rap1.id, rap1.name) should be (true)
    }
  }

  test("Test create When having valid data and empty parts Then should create project without any reserved part") {
    runWithRollback {
      val id = Sequences.nextViiteProjectId
      val rap = dummyProject(id, ProjectState.Incomplete, Seq(), None)
      projectDAO.create(rap)
      projectDAO.fetchById(id).nonEmpty should be(true)
      projectDAO.fetchById(id).head.reservedParts.isEmpty should be(true)
    }
  }


  test("Test update When Update project info Then should update the project infos such as project name, additional info, startDate") {
    val reservedPart = ProjectReservedPart(5: Long, 203: Long, 203: Long, Some(6L), Some(Discontinuity.apply("jatkuva")), Some(8L), newLength = None, newDiscontinuity = None, newEly = None)
    runWithRollback {
      val id = Sequences.nextViiteProjectId
      val rap = dummyProject(id, ProjectState.Incomplete, List(), None)
      val updatedRap = Project(id, ProjectState.apply(1), "newname", "TestUser", DateTime.parse("1901-01-02"), "TestUser",
        DateTime.parse("1901-01-02"), DateTime.now(), "updated info", List(reservedPart), Seq(), None)
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
      val projectListSize = projectDAO.fetchAllWithoutDeletedFilter().length
      val id = Sequences.nextViiteProjectId
      val rap = dummyProject(id, ProjectState.Incomplete, List.empty[ProjectReservedPart], None)
      projectDAO.create(rap)
      val projectList = projectDAO.fetchAllWithoutDeletedFilter()
      projectList.length - projectListSize should be(1)
    }
  }

  test("Test updateProjectEly When updating Ely for project Then ely should change") {
    runWithRollback {
      val id = Sequences.nextViiteProjectId
      val rap = dummyProject(id, ProjectState.Incomplete, List.empty[ProjectReservedPart], None)
      projectDAO.create(rap)
      projectDAO.fetchById(id).nonEmpty should be(true)
      projectDAO.fetchProjectElyById(id).isEmpty should be (true)
    }
  }

  test("Test fetchProjectIdsWithToBePreservedStatus " +
    "When project is accepted, but yet waiting to be preserved, or at preserving to Viite DB, " +
    "Then fetchProjectIdsWithToBePreservedStatus should be increased") {
    val reservedPart = ProjectReservedPart(5: Long, 203: Long, 203: Long, Some(6L), Some(Discontinuity.apply("jatkuva")), Some(8L), newLength = None, newDiscontinuity = None, newEly = None)
    runWithRollback {
      val startWaitingCount = projectDAO.fetchProjectIdsWithToBePreservedStatus.length

      val id = Sequences.nextViiteProjectId
      val project1 =  dummyProject(id, ProjectState.InUpdateQueue, List(reservedPart), None)
      projectDAO.create(project1) // Project waiting to be picked for preserving
      val id2 = Sequences.nextViiteProjectId
      val project2 =  dummyProject(id2, ProjectState.UpdatingToRoadNetwork, List(reservedPart), None)
      projectDAO.create(project2) // Project currently being preserved

      val waitingCountNow = projectDAO.fetchProjectIdsWithToBePreservedStatus.length
      waitingCountNow - startWaitingCount should be(2) // There should be the waiting, and the currently preserved one
    }
  }

  test("Test updateProjectStatus " +
    "When given a status to update to " +
    "Then project status should be updated to the given status") {
    runWithRollback {
      val id = Sequences.nextViiteProjectId
      val project = dummyProject(id, ProjectState.InUpdateQueue, List(), None)
      projectDAO.create(project)
      projectDAO.updateProjectStatus(id, ProjectState.UpdatingToRoadNetwork)
      projectDAO.fetchProjectStatus(id) should be(Some(ProjectState.UpdatingToRoadNetwork))
    }
  }
}

package fi.liikennevirasto.viite

import fi.liikennevirasto.digiroad2.DigiroadEventBus
import fi.liikennevirasto.digiroad2.client.kgv.{KgvRoadLink, KgvRoadLinkClient}
import fi.liikennevirasto.digiroad2.service.RoadLinkService
import fi.liikennevirasto.viite.dao._
import fi.liikennevirasto.viite.process.RoadwayAddressMapper
import fi.vaylavirasto.viite.dao.ProjectLinkNameDAO
import fi.vaylavirasto.viite.model.RoadLink
import fi.vaylavirasto.viite.postgis.DbUtils.runUpdateToDb
import fi.vaylavirasto.viite.postgis.PostGISDatabaseScalikeJDBC.runWithRollback
import org.joda.time.DateTime
import org.scalatest.BeforeAndAfter
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar
import scalikejdbc.scalikejdbcSQLInterpolationImplicitDef

import scala.concurrent.Future


class ProjectLinkNameDAOSpec extends AnyFunSuite with Matchers with BeforeAndAfter {

  val mockProjectService: ProjectService = MockitoSugar.mock[ProjectService]
  val mockRoadLinkService: RoadLinkService = MockitoSugar.mock[RoadLinkService]
  val mockRoadAddressService: RoadAddressService = MockitoSugar.mock[RoadAddressService]
  val mockNodesAndJunctionsService: NodesAndJunctionsService = MockitoSugar.mock[NodesAndJunctionsService]
  val mockEventBus: DigiroadEventBus = MockitoSugar.mock[DigiroadEventBus]
  val mockKGVClient: KgvRoadLink = MockitoSugar.mock[KgvRoadLink]
  val mockKGVRoadLinkClient: KgvRoadLinkClient[RoadLink] = MockitoSugar.mock[KgvRoadLinkClient[RoadLink]]
  val projectValidator = new ProjectValidator
  val projectDAO = new ProjectDAO
  val projectLinkDAO = new ProjectLinkDAO
  val roadwayDAO = new RoadwayDAO
  val roadNetworkDAO = new RoadNetworkDAO
  val linearLocationDAO = new LinearLocationDAO
  val projectReservedPartDAO = new ProjectReservedPartDAO
  val roadwayPointDAO = new RoadwayPointDAO
  val nodeDAO = new NodeDAO
  val nodePointDAO = new NodePointDAO
  val junctionPointDAO = new JunctionPointDAO
  val roadwayChangesDAO = new RoadwayChangesDAO
  val roadwayAddressMapper = new RoadwayAddressMapper(roadwayDAO, linearLocationDAO)
  val mockwayChangesDAO = MockitoSugar.mock[RoadwayChangesDAO]
  val mockProjectLinkDAO = MockitoSugar.mock[ProjectLinkDAO]
  val mockRoadwayDAO = MockitoSugar.mock[RoadwayDAO]
  val mockLinearLocationDAO = MockitoSugar.mock[LinearLocationDAO]
  val mockRoadwayChangesDAO = MockitoSugar.mock[RoadwayChangesDAO]

  val roadAddressServiceRealRoadwayAddressMapper = new RoadAddressService(
    mockRoadLinkService,
    roadwayDAO,
    linearLocationDAO,
    roadNetworkDAO,
    roadwayPointDAO,
    nodePointDAO,
    junctionPointDAO,
    roadwayAddressMapper,
    mockEventBus,
    frozenKGV = false
  ) {
    override def runWithReadOnlySession[T](f: => T): T = f

    override def runWithTransaction[T](f: => T): T = f
  }

  val projectService = new ProjectService(
    roadAddressServiceRealRoadwayAddressMapper,
    mockRoadLinkService,
    mockNodesAndJunctionsService,
    roadwayDAO,
    roadwayPointDAO,
    linearLocationDAO,
    projectDAO,
    projectLinkDAO,
    nodeDAO,
    nodePointDAO,
    junctionPointDAO,
    projectReservedPartDAO,
    roadwayChangesDAO,
    roadwayAddressMapper,
    mockEventBus) {
    override def runWithReadOnlySession[T](f: => T): T = f

    override def runWithTransaction[T](f: => T): T = f
  }

  test("Test setProjectRoadName When there is no road/projectlink name and given one new road name Then save should be successful") {
    runWithRollback {
      val projectId = 12345L
      val rap = Project(projectId, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("2700-01-01"), "TestUser", DateTime.parse("2700-01-01"), DateTime.now(), "Some additional info", List.empty[ProjectReservedPart], Seq(), None)
      projectDAO.create(rap)

      val result = projectService.setProjectRoadName(projectId, MaxRoadNumberDemandingRoadName, "any name")
      result.isEmpty should be (true)
    }
  }

  test("Test setProjectRoadName When given one new EMPTY road name and road number <= 70000 Then one error should be thrown") {
    runWithRollback {
      val projectId = 12345L
      val rap = Project(projectId, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("2700-01-01"), "TestUser", DateTime.parse("2700-01-01"), DateTime.now(), "Some additional info", List.empty[ProjectReservedPart], Seq(), None)
      projectDAO.create(rap)

      val result = projectService.setProjectRoadName(projectId, MaxRoadNumberDemandingRoadName, "")
      result.get should be (ErrorMaxRoadNumberDemandingRoadNameMessage)
    }
  }

  test("Test setProjectRoadName When given one new EMPTY road name and road number > 70000 Then NO error should be thrown") {
    runWithRollback {
      val projectId = 12345L
      val rap = Project(projectId, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("2700-01-01"), "TestUser", DateTime.parse("2700-01-01"), DateTime.now(), "Some additional info", List.empty[ProjectReservedPart], Seq(), None)
      projectDAO.create(rap)

      val result = projectService.setProjectRoadName(projectId, MaxRoadNumberDemandingRoadName+1, "")
      result.isEmpty should be (true)
    }
  }

  test("Test setProjectRoadName When there is one project link name and given one new non empty road name Then the project link name should be updated") {
    runWithRollback {
      val projectId = 12345L
      val rap = Project(projectId, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("2700-01-01"), "TestUser", DateTime.parse("2700-01-01"), DateTime.now(), "Some additional info", List.empty[ProjectReservedPart], Seq(), None)
      projectDAO.create(rap)

      projectService.setProjectRoadName(projectId, 99999, "test name")
      val after1stInsert = ProjectLinkNameDAO.get(99999, projectId)
      after1stInsert.get.roadName should be ("test name")
      projectService.setProjectRoadName(projectId, 99999, "test name2")
      val after2ndInsert = ProjectLinkNameDAO.get(99999, projectId)
      after2ndInsert.get.roadName should be ("test name2")
    }
  }

  test("Test setProjectRoadName When there is one project link name and given one new EMPTY road name Then the project link name should be reverted") {
    runWithRollback {
      val projectId = 12345L
      val rap = Project(projectId, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("2700-01-01"), "TestUser", DateTime.parse("2700-01-01"), DateTime.now(), "Some additional info", List.empty[ProjectReservedPart], Seq(), None)
      projectDAO.create(rap)

      projectService.setProjectRoadName(projectId, 99999, "test name")
      val after1stInsert = ProjectLinkNameDAO.get(99999, projectId)
      after1stInsert.get.roadName should be ("test name")
      projectService.setProjectRoadName(projectId, 99999, "")
      val after2ndInsert = ProjectLinkNameDAO.get(99999, projectId)
      after2ndInsert.isEmpty should be (true)
    }
  }

  test("Test setProjectRoadName When there is road name, but no project link name Then one new project link name should be created") {
    runWithRollback {
      val projectId = 12345L
      val rap = Project(projectId, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("2700-01-01"), "TestUser", DateTime.parse("2700-01-01"), DateTime.now(), "Some additional info", List.empty[ProjectReservedPart], Seq(), None)
      projectDAO.create(rap)

      runUpdateToDb(
        sql"""
            INSERT INTO ROAD_NAME
            VALUES (nextval('ROAD_NAME_SEQ'), 99999, 'test name',
            current_date, null, current_date, null, 'test user', current_date)
           """)

      val beforeInsert = ProjectLinkNameDAO.get(99999, projectId)
      projectService.setProjectRoadName(projectId, 99999, "test name 2")
      val afterInsert = ProjectLinkNameDAO.get(99999, projectId)
      beforeInsert.isEmpty should be (true)
      afterInsert.nonEmpty should be (true)
    }
  }




}

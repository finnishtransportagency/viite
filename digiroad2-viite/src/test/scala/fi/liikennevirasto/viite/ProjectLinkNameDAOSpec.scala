package fi.liikennevirasto.viite

import fi.liikennevirasto.digiroad2.DigiroadEventBus
import fi.liikennevirasto.digiroad2.client.vvh.{VVHClient, VVHComplementaryClient, VVHRoadLinkClient, VVHSuravageClient}
import fi.liikennevirasto.digiroad2.oracle.OracleDatabase
import fi.liikennevirasto.digiroad2.service.RoadLinkService
import fi.liikennevirasto.viite.dao._
import fi.liikennevirasto.viite.process.RoadwayAddressMapper
import org.joda.time.DateTime
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.when
import org.scalatest.mock.MockitoSugar
import org.scalatest.{BeforeAndAfter, FunSuite, Matchers}
import slick.driver.JdbcDriver.backend.Database
import slick.driver.JdbcDriver.backend.Database.dynamicSession
import slick.jdbc.StaticQuery.interpolation


class ProjectLinkNameDAOSpec extends FunSuite with Matchers with BeforeAndAfter {

  val mockProjectService = MockitoSugar.mock[ProjectService]
  val mockRoadLinkService = MockitoSugar.mock[RoadLinkService]
  val mockRoadAddressService = MockitoSugar.mock[RoadAddressService]
  val mockEventBus = MockitoSugar.mock[DigiroadEventBus]
  val mockVVHClient = MockitoSugar.mock[VVHClient]
  val mockVVHRoadLinkClient = MockitoSugar.mock[VVHRoadLinkClient]
  val mockVVHSuravageClient = MockitoSugar.mock[VVHSuravageClient]
  val mockVVHComplementaryClient = MockitoSugar.mock[VVHComplementaryClient]
  val projectValidator = new ProjectValidator
  val projectDAO = new ProjectDAO
  val projectLinkDAO = new ProjectLinkDAO
  val roadwayDAO = new RoadwayDAO
  val roadNetworkDAO = new RoadNetworkDAO
  val linearLocationDAO = new LinearLocationDAO
  val projectReservedPartDAO = new ProjectReservedPartDAO
  val roadwayAddressMapper = new RoadwayAddressMapper(roadwayDAO, linearLocationDAO)
  val mockwayChangesDAO = MockitoSugar.mock[RoadwayChangesDAO]
  val mockProjectLinkDAO = MockitoSugar.mock[ProjectLinkDAO]
  val mockRoadwayDAO = MockitoSugar.mock[RoadwayDAO]
  val mockLinearLocationDAO = MockitoSugar.mock[LinearLocationDAO]
  val mockRoadwayChangesDAO = MockitoSugar.mock[RoadwayChangesDAO]

  val roadAddressServiceRealRoadwayAddressMapper = new RoadAddressService(mockRoadLinkService, roadwayDAO, linearLocationDAO, roadNetworkDAO, roadwayAddressMapper, mockEventBus) {
    override def withDynSession[T](f: => T): T = f

    override def withDynTransaction[T](f: => T): T = f
  }

  val projectService = new ProjectService(roadAddressServiceRealRoadwayAddressMapper, mockRoadLinkService, mockEventBus) {
    override def withDynSession[T](f: => T): T = f

    override def withDynTransaction[T](f: => T): T = f
  }

  def withDynTransaction[T](f: => T): T = OracleDatabase.withDynTransaction(f)

  def runWithRollback[T](f: => T): T = {
    Database.forDataSource(OracleDatabase.ds).withDynTransaction {
      val t = f
      dynamicSession.rollback()
      t
    }
  }

  test("Test setProjectRoadName When there is no road/projectlink name and given one new road name Then save should be successful") {
    runWithRollback {
      val projectId = 12345L
      val rap = Project(projectId, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("2700-01-01"), "TestUser", DateTime.parse("2700-01-01"), DateTime.now(), "Some additional info", List.empty[ProjectReservedPart], None)
      projectDAO.create(rap)

      val result = projectService.setProjectRoadName(projectId, MaxRoadNumberDemandingRoadName, "any name")
      result.isEmpty should be (true)
    }
  }

  test("Test setProjectRoadName When given one new EMPTY road name and road number <= 70000 Then one error should be thrown") {
    runWithRollback {
      val projectId = 12345L
      val rap = Project(projectId, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("2700-01-01"), "TestUser", DateTime.parse("2700-01-01"), DateTime.now(), "Some additional info", List.empty[ProjectReservedPart], None)
      projectDAO.create(rap)

      val result = projectService.setProjectRoadName(projectId, MaxRoadNumberDemandingRoadName, "")
      result.get should be (ErrorMaxRoadNumberDemandingRoadNameMessage)
    }
  }

  test("Test setProjectRoadName When given one new EMPTY road name and road number > 70000 Then NO error should be thrown") {
    runWithRollback {
      val projectId = 12345L
      val rap = Project(projectId, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("2700-01-01"), "TestUser", DateTime.parse("2700-01-01"), DateTime.now(), "Some additional info", List.empty[ProjectReservedPart], None)
      projectDAO.create(rap)

      val result = projectService.setProjectRoadName(projectId, MaxRoadNumberDemandingRoadName+1, "")
      result.isEmpty should be (true)
    }
  }

  test("Test setProjectRoadName When there is one project link name and given one new non empty road name Then the project link name should be updated") {
    runWithRollback {
      val projectId = 12345L
      val rap = Project(projectId, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("2700-01-01"), "TestUser", DateTime.parse("2700-01-01"), DateTime.now(), "Some additional info", List.empty[ProjectReservedPart], None)
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
      val rap = Project(projectId, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("2700-01-01"), "TestUser", DateTime.parse("2700-01-01"), DateTime.now(), "Some additional info", List.empty[ProjectReservedPart], None)
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
      val rap = Project(projectId, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("2700-01-01"), "TestUser", DateTime.parse("2700-01-01"), DateTime.now(), "Some additional info", List.empty[ProjectReservedPart], None)
      projectDAO.create(rap)

      sqlu"""INSERT INTO ROAD_NAME VALUES (ROAD_NAME_SEQ.NEXTVAL, 99999, 'test name', sysdate, null, sysdate, null, 'test user', sysdate)""".execute

      val beforeInsert = ProjectLinkNameDAO.get(99999, projectId)
      projectService.setProjectRoadName(projectId, 99999, "test name 2")
      val afterInsert = ProjectLinkNameDAO.get(99999, projectId)
      beforeInsert.isEmpty should be (true)
      afterInsert.nonEmpty should be (true)
    }
  }




}

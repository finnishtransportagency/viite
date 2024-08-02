package fi.liikennevirasto.viite

import fi.liikennevirasto.digiroad2.DigiroadEventBus
import fi.liikennevirasto.digiroad2.client.kgv._
import fi.liikennevirasto.digiroad2.service.RoadLinkService
import fi.liikennevirasto.viite.Dummies._
import fi.liikennevirasto.viite.dao._
import fi.liikennevirasto.viite.dao.ProjectCalibrationPointDAO.UserDefinedCalibrationPoint
import fi.liikennevirasto.viite.dao.ProjectState.{Incomplete, UpdatingToRoadNetwork}
import fi.liikennevirasto.viite.dao.TerminationCode.NoTermination
import fi.liikennevirasto.viite.model.{ProjectAddressLink, RoadAddressLinkLike}
import fi.liikennevirasto.viite.process.{ProjectSectionCalculator, RoadwayAddressMapper}
import fi.liikennevirasto.viite.process.strategy.DefaultSectionCalculatorStrategy
import fi.liikennevirasto.viite.util.CalibrationPointsUtils
import fi.vaylavirasto.viite.postgis.DbUtils.runUpdateToDb  // TODO extend BaseDAO instead?
import fi.vaylavirasto.viite.dao.{ProjectLinkNameDAO, RoadName, RoadNameDAO, Sequences}
import fi.vaylavirasto.viite.geometry.{GeometryUtils, Point, PolyLine}
import fi.vaylavirasto.viite.model.CalibrationPointType.{JunctionPointCP, NoCP, RoadAddressCP, UserDefinedCP}
import fi.vaylavirasto.viite.model.LinkGeomSource.FrozenLinkInterface
import fi.vaylavirasto.viite.model.{AddrMRange, AdministrativeClass, Discontinuity, LifecycleStatus, LinkGeomSource, RoadAddressChangeType, RoadLink, RoadPart, SideCode, Track, TrafficDirection}
import fi.vaylavirasto.viite.postgis.PostGISDatabase.runWithRollback
import org.joda.time.DateTime
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito._
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import org.scalatest.{BeforeAndAfter, FunSuite, Matchers}
import org.scalatest.mock.MockitoSugar
import slick.driver.JdbcDriver.backend.Database.dynamicSession   // JdbcBackend#sessionDef
import slick.jdbc.StaticQuery.interpolation

import java.sql.BatchUpdateException

class ProjectServiceSpec extends FunSuite with Matchers with BeforeAndAfter {
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
  val nodePointDAO = new NodePointDAO
  val junctionPointDAO = new JunctionPointDAO
  val nodeDAO = new NodeDAO
  val roadwayChangesDAO = new RoadwayChangesDAO
  val roadwayAddressMapper = new RoadwayAddressMapper(roadwayDAO, linearLocationDAO)
  val mockProjectLinkDAO: ProjectLinkDAO = MockitoSugar.mock[ProjectLinkDAO]
  val mockRoadwayDAO: RoadwayDAO = MockitoSugar.mock[RoadwayDAO]
  val mockLinearLocationDAO: LinearLocationDAO = MockitoSugar.mock[LinearLocationDAO]
  val mockRoadwayChangesDAO: RoadwayChangesDAO = MockitoSugar.mock[RoadwayChangesDAO]

  private val roadwayNumber1   = 1000000000L
  private val roadwayNumber2   = 2000000000L
  private val roadwayNumber3   = 3000000000L
  private val roadwayNumber4   = 4000000000L
  private val linearLocationId = 1
  private val testProjectId    = 7081807

  val mockRoadwayAddressMapper: RoadwayAddressMapper = MockitoSugar.mock[RoadwayAddressMapper]

  val roadAddressService: RoadAddressService = new RoadAddressService(mockRoadLinkService, roadwayDAO, linearLocationDAO, roadNetworkDAO, roadwayPointDAO, nodePointDAO, junctionPointDAO, mockRoadwayAddressMapper, mockEventBus, frozenKGV = false) {

    override def withDynSession[T](f: => T): T = f

    override def withDynTransaction[T](f: => T): T = f
  }
  val roadAddressServiceRealRoadwayAddressMapper: RoadAddressService = new RoadAddressService(mockRoadLinkService, roadwayDAO, linearLocationDAO, roadNetworkDAO, roadwayPointDAO, nodePointDAO, junctionPointDAO, roadwayAddressMapper, mockEventBus, frozenKGV = false) {

    override def withDynSession[T](f: => T): T = f

    override def withDynTransaction[T](f: => T): T = f
  }
  val projectService: ProjectService =
    new ProjectService(
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
                        mockEventBus
                        ) {
                            override def withDynSession[T](f: => T): T = f
                            override def withDynTransaction[T](f: => T): T = f
                          }

  val projectServiceWithRoadAddressMock: ProjectService =
    new ProjectService(
                        mockRoadAddressService,
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
                        mockEventBus
                        ) {
                            override def withDynSession[T](f: => T): T = f
                            override def withDynTransaction[T](f: => T): T = f
                          }

  after {
    reset(mockRoadLinkService)
  }

  val linearLocations = Seq(
    dummyLinearLocation(roadwayNumber = 1L, orderNumber = 1L, linkId = 123L.toString, startMValue = 0.0, endMValue = 10.0),
    dummyLinearLocation(roadwayNumber = 1L, orderNumber = 2L, linkId = 123L.toString, startMValue = 10.0, endMValue = 20.0),
    dummyLinearLocation(roadwayNumber = 1L, orderNumber = 3L, linkId = 124L.toString, startMValue = 0.0, endMValue = 10.0),
    dummyLinearLocation(roadwayNumber = 1L, orderNumber = 4L, linkId = 125L.toString, startMValue = 0.0, endMValue = 10.0)
  )

  val roadways = Seq(
    dummyRoadway(roadwayNumber = 1L, roadPart = RoadPart(1, 1), addrMRange = AddrMRange(0L, 400L), DateTime.now(), None)
  )

  val historyRoadLinks = Seq(
    dummyHistoryRoadLink(linkId = 123L.toString, Seq(0.0, 10.0, 20.0)),
    dummyHistoryRoadLink(linkId = 125L.toString, Seq(0.0, 10.0))
  )

  val roadLinks = Seq(
    dummyRoadLink(linkId = 123L.toString, Seq(0.0, 10.0, 20.0), LinkGeomSource.NormalLinkInterface),
    dummyRoadLink(linkId = 124L.toString, Seq(0.0, 10.0), LinkGeomSource.NormalLinkInterface)
  )

  // note, functionality moved here from former ViiteTierekisteriClient at Tierekisteri removal (2021-09)
  val defaultChangeInfo = RoadwayChangeInfo(
    RoadAddressChangeType.apply(2),
    RoadwayChangeSection(None, None, None, None, None, None, None, None, None),
    RoadwayChangeSection(Option(403), Option(0), Option(8), Option(0), Option(8), Option(1001),
      Option(AdministrativeClass.State), Option(Discontinuity.Continuous), Option(5)),
    Discontinuity.apply(1),
    AdministrativeClass.apply(1),
    reversed = false,
    1)

  private def createProjectLinks(linkIds: Seq[String], projectId: Long, roadPart: RoadPart, track: Int, discontinuity: Int, administrativeClass: Int, roadLinkSource: Int, roadEly: Long, user: String, roadName: String) = {
    projectService.createProjectLinks(linkIds, projectId, roadPart, Track.apply(track), Discontinuity.apply(discontinuity), AdministrativeClass.apply(administrativeClass), LinkGeomSource.apply(roadLinkSource), roadEly, user, roadName)
  }

  private def toProjectLink(project: Project, status: RoadAddressChangeType)(roadAddress: RoadAddress): ProjectLink = {
    ProjectLink(id = NewIdValue, roadAddress.roadPart, roadAddress.track, roadAddress.discontinuity, AddrMRange(roadAddress.addrMRange.start, roadAddress.addrMRange.end), AddrMRange(roadAddress.addrMRange.start, roadAddress.addrMRange.end), roadAddress.startDate, roadAddress.endDate, createdBy = Option(project.createdBy), roadAddress.linkId, roadAddress.startMValue, roadAddress.endMValue, roadAddress.sideCode, roadAddress.calibrationPointTypes, (roadAddress.startCalibrationPointType, roadAddress.endCalibrationPointType), roadAddress.geometry, project.id, status, AdministrativeClass.State, roadAddress.linkGeomSource, GeometryUtils.geometryLength(roadAddress.geometry), if (status == RoadAddressChangeType.New) 0 else roadAddress.id, if (status == RoadAddressChangeType.New) 0 else roadAddress.linearLocationId, roadAddress.ely, reversed = false, None, roadAddress.adjustedTimestamp)
  }

  private def toProjectAddressLink(ral: RoadAddressLinkLike): ProjectAddressLink = {
    ProjectAddressLink(ral.id, ral.linkId, ral.geometry, ral.length, ral.administrativeClassMML, ral.lifecycleStatus, ral.roadLinkSource, ral.administrativeClass, ral.roadName, ral.municipalityCode, ral.municipalityName, ral.modifiedAt, ral.modifiedBy, ral.roadPart, ral.trackCode, ral.elyCode, ral.discontinuity, ral.addrMRange, ral.startMValue, ral.endMValue, ral.sideCode, ral.startCalibrationPoint, ral.endCalibrationPoint, RoadAddressChangeType.Unknown, ral.id, ral.linearLocationId, sourceId = ral.sourceId)
  }

  private def extractTrafficDirection(sideCode: SideCode, track: Track): TrafficDirection = {
    (sideCode, track) match {
      case (_, Track.Combined) => TrafficDirection.BothDirections
      case (SideCode.TowardsDigitizing, Track.RightSide) => TrafficDirection.TowardsDigitizing
      case (SideCode.TowardsDigitizing, Track.LeftSide) => TrafficDirection.AgainstDigitizing
      case (SideCode.AgainstDigitizing, Track.RightSide) => TrafficDirection.AgainstDigitizing
      case (SideCode.AgainstDigitizing, Track.LeftSide) => TrafficDirection.TowardsDigitizing
      case (_, _) => TrafficDirection.UnknownDirection
    }
  }

  private def toRoadLink(ral: ProjectLink): RoadLink = {
    RoadLink(ral.linkId, ral.geometry, ral.geometryLength, AdministrativeClass.State, extractTrafficDirection(ral.sideCode, ral.track), Some(DateTime.now().toString), None, LifecycleStatus.InUse, LinkGeomSource.NormalLinkInterface, 749, "")
  }

  private def toRoadLink(ral: RoadAddressLinkLike): RoadLink = {
    RoadLink(ral.linkId, ral.geometry, ral.length, ral.administrativeClass, extractTrafficDirection(ral.sideCode, Track.apply(ral.trackCode.toInt)), ral.modifiedAt, ral.modifiedBy, ral.lifecycleStatus, ral.roadLinkSource, 749, "")
  }

  private def toMockAnswer(projectLinks: Seq[ProjectLink], roadLink: RoadLink, seq: Seq[RoadLink] = Seq()) = {
    new Answer[Seq[RoadLink]]() {
      override def answer(invocation: InvocationOnMock): Seq[RoadLink] = {
        val ids = if (invocation.getArguments.apply(0) == null)
          Set[String]()
        else invocation.getArguments.apply(0).asInstanceOf[Set[String]]
        projectLinks.groupBy(_.linkId).filterKeys(l => ids.contains(l)).mapValues { pl =>
          val startP = Point(pl.map(_.addrMRange.start).min, 0.0)
          val endP = Point(pl.map(_.addrMRange.end).max, 0.0)
          val maxLen = pl.map(_.endMValue).max
          val midP = Point((startP.x + endP.x) * .5,
            if (endP.x - startP.x < maxLen) {
              Math.sqrt(maxLen * maxLen - (startP.x - endP.x) * (startP.x - endP.x)) / 2
            }
            else 0.0)
          val forcedGeom = pl.filter(l => l.id == -1000L && l.geometry.nonEmpty).sortBy(_.addrMRange.start)
          val (startFG, endFG) = (forcedGeom.headOption.map(_.startingPoint), forcedGeom.lastOption.map(_.endPoint))
          if (pl.head.id == -1000L) {
            roadLink.copy(linkId = pl.head.linkId, geometry = Seq(startFG.get, endFG.get), sourceId = "")
          } else
            roadLink.copy(linkId = pl.head.linkId, geometry = Seq(startP, midP, endP), sourceId = "")
        }.values.toSeq ++ seq
      }
    }
  }

  private def toMockAnswer(roadLinks: Seq[RoadLink]) = {
    new Answer[Seq[RoadLink]]() {
      override def answer(invocation: InvocationOnMock): Seq[RoadLink] = {
        val ids = invocation.getArguments.apply(0).asInstanceOf[Set[String]]
        roadLinks.filter(rl => ids.contains(rl.linkId))
      }
    }
  }


  private val modifiedBy = "kgv_modified"

  private def mockForProject[T <: PolyLine](id: Long, l: Seq[T] = Seq()) = {
    val roadLink = RoadLink(1.toString, Seq(Point(535602.222, 6982200.25, 89.9999), Point(535605.272, 6982204.22, 85.90899999999965)), 540.3960283713503, AdministrativeClass.State, TrafficDirection.AgainstDigitizing, Some("25.06.2015 03:00:00"), Some(modifiedBy), LifecycleStatus.InUse, LinkGeomSource.NormalLinkInterface, 749, "")
    val (projectLinks, palinks) = l.partition(_.isInstanceOf[ProjectLink])
    val dbLinks = projectLinkDAO.fetchProjectLinks(id)
    when(mockRoadLinkService.getRoadLinksHistoryFromVVH(any[Set[String]])).thenReturn(Seq())
    when(mockRoadLinkService.getRoadLinksByLinkIds(any[Set[String]])).thenAnswer(
      toMockAnswer(dbLinks ++ projectLinks.asInstanceOf[Seq[ProjectLink]].filterNot(l => dbLinks.map(_.linkId).contains(l.linkId)),
        roadLink, palinks.asInstanceOf[Seq[ProjectAddressLink]].map(toRoadLink)
      ))
  }

  private def setUpProjectWithLinks(roadAddressChangeType: RoadAddressChangeType, addrM: Seq[Long], changeTrack: Boolean = false, roadPart: RoadPart = RoadPart(19999, 1),
                                    discontinuity: Discontinuity = Discontinuity.Continuous, ely: Long = 8L, roadwayId: Long = 0L, startDate: Option[DateTime] = None) = {
    val id = Sequences.nextViiteProjectId

    def projectLink(addrMRange: AddrMRange, track: Track, projectId: Long, status: RoadAddressChangeType = RoadAddressChangeType.NotHandled, roadPart: RoadPart = RoadPart(19999, 1), discontinuity: Discontinuity = Discontinuity.Continuous, ely: Long = 8L, linkId: String = 0L.toString, roadwayId: Long = 0L, linearLocationId: Long = 0L, startDate: Option[DateTime] = None) = {
      ProjectLink(NewIdValue, roadPart, track, discontinuity, addrMRange, addrMRange, startDate, None, Some("User"), linkId, 0.0, (addrMRange.end - addrMRange.start).toDouble, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), Seq(Point(0.0, addrMRange.start), Point(0.0, addrMRange.end)), projectId, status, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, (addrMRange.end - addrMRange.start).toDouble, roadwayId, linearLocationId, ely, reversed = false, None, 0L)
    }

    def withTrack(t: Track): Seq[ProjectLink] = {
      addrM.init.zip(addrM.tail).map { case (st, en) =>
        projectLink(AddrMRange(st, en), t, id, roadAddressChangeType, roadPart, discontinuity, ely, roadwayId = roadwayId, startDate = startDate)
      }
    }

    val projectStartDate = if (startDate.isEmpty) DateTime.now() else startDate.get
    val project = Project(id, ProjectState.Incomplete, "f", "s", projectStartDate, "", projectStartDate, projectStartDate, "", Seq(), Seq(), None, None, Set())
    projectDAO.create(project)
    val links =
      if (changeTrack) {
        withTrack(Track.RightSide) ++ withTrack(Track.LeftSide)
      } else {
        withTrack(Track.Combined)
      }
    projectReservedPartDAO.reserveRoadPart(id, roadPart, "u")
    projectLinkDAO.create(links)
    project
  }
  private def toProjectLink(project: Project)(roadAddress: RoadAddress): ProjectLink = {
    ProjectLink(id = NewIdValue, roadAddress.roadPart, roadAddress.track, roadAddress.discontinuity, roadAddress.addrMRange, roadAddress.addrMRange, roadAddress.startDate, roadAddress.endDate, createdBy = Option(project.createdBy), roadAddress.linkId, roadAddress.startMValue, roadAddress.endMValue, roadAddress.sideCode, roadAddress.calibrationPointTypes, (roadAddress.startCalibrationPointType, roadAddress.endCalibrationPointType), roadAddress.geometry, project.id, RoadAddressChangeType.NotHandled, AdministrativeClass.State, roadAddress.linkGeomSource, GeometryUtils.geometryLength(roadAddress.geometry), 0, 0, roadAddress.ely, reversed = false, None, roadAddress.adjustedTimestamp)
  }

  test("Test createRoadLinkProject When no road parts reserved Then return 0 reserved parts project") {
    runWithRollback {
      val roadAddressProject = Project(0, ProjectState.apply(1), "TestProject", "TestUser", DateTime.now(), "TestUser", DateTime.parse("1901-01-01"), DateTime.now(), "Some additional info", List.empty[ProjectReservedPart], Seq(), None, elys = Set())
      val project = projectService.createRoadLinkProject(roadAddressProject)
      project.reservedParts should have size 0
    }
  }

  test("Test createRoadLinkProject When creating road link project without valid roadParts Then return project without the invalid parts") {
    val roadlink = RoadLink(5175306.toString, Seq(Point(535605.272, 6982204.22, 85.90899999999965)), 540.3960283713503, AdministrativeClass.State, TrafficDirection.AgainstDigitizing, Some("25.06.2015 03:00:00"), Some(modifiedBy), LifecycleStatus.InUse, LinkGeomSource.NormalLinkInterface, 749, "")
    when(mockRoadLinkService.getRoadLinksByLinkIds(Set(5175306L.toString))).thenReturn(Seq(roadlink))
    runWithRollback {
      val roadAddressProject = Project(0, ProjectState.apply(1), "TestProject", "TestUser", DateTime.now(), "TestUser", DateTime.parse("1901-01-01"), DateTime.now(), "Some additional info", List.empty[ProjectReservedPart], Seq(), None, elys = Set())
      val project = projectService.createRoadLinkProject(roadAddressProject)
      project.reservedParts should have size 0
    }
  }

  test("Test createRoadLinkProject When creating a road link project with same name as an existing project Then return error") {
    runWithRollback {
      val roadAddressProject1 = Project(0, ProjectState.apply(1), "TestProject", "TestUser1", DateTime.now(), "TestUser1", DateTime.parse("1901-01-01"), DateTime.now(), "Some additional info", List.empty[ProjectReservedPart], Seq(), None, elys = Set())
      projectService.createRoadLinkProject(roadAddressProject1)

      val roadAddressProject2 = Project(0, ProjectState.apply(1), "TESTPROJECT", "TestUser2", DateTime.now(), "TestUser2", DateTime.parse("1902-03-03"), DateTime.now(), "Some other info", List.empty[ProjectReservedPart], Seq(), None, elys = Set())
      val error = intercept[NameExistsException] {
        projectService.createRoadLinkProject(roadAddressProject2)
      }
      error.getMessage should be("Nimellä TESTPROJECT on jo olemassa projekti. Muuta nimeä.")

      val roadAddressProject3 = Project(0, ProjectState.apply(1), "testproject", "TestUser3", DateTime.now(), "TestUser3", DateTime.parse("1903-03-03"), DateTime.now(), "Some other info", List.empty[ProjectReservedPart], Seq(), None, elys = Set())
      val error2 = intercept[NameExistsException] {
        projectService.createRoadLinkProject(roadAddressProject3)
      }
      error2.getMessage should be("Nimellä testproject on jo olemassa projekti. Muuta nimeä.")
    }
  }

  test("Test saveProject When two projects with same road part Then throw exception on unique key constraint violation") {
    runWithRollback {
      var project2: Project = null
      val rap1 = Project(0L, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("1901-01-01"), "TestUser", DateTime.parse("1963-01-01"), DateTime.now(), "Some additional info", Seq(), Seq(), None, elys = Set())
      val rap2 = Project(0L, ProjectState.apply(1), "TestProject2", "TestUser", DateTime.parse("1901-01-01"), "TestUser", DateTime.parse("1963-01-01"), DateTime.now(), "Some additional info", Seq(), Seq(), None, elys = Set())
      val addr1 = List(ProjectReservedPart(Sequences.nextViitePrimaryKeySeqValue, RoadPart(5, 207), Some(0L), Some(Discontinuity.Continuous), Some(8L), None, None, None, None))
      val project1 = projectService.createRoadLinkProject(rap1)
      mockForProject(project1.id, roadwayAddressMapper.getRoadAddressesByRoadway(roadwayDAO.fetchAllByRoadPart(RoadPart(5, 207))).map(toProjectLink(project1)))
      projectService.saveProject(project1.copy(reservedParts = addr1, elys = Set()))
      project2 = projectService.createRoadLinkProject(rap2)
      mockForProject(project2.id, roadwayAddressMapper.getRoadAddressesByRoadway(roadwayDAO.fetchAllByRoadPart(RoadPart(5, 207))).map(toProjectLink(project2)))

      val exception = intercept[BatchUpdateException] {
        projectService.saveProject(project2.copy(reservedParts = addr1, elys = Set()))
      }
      exception.getMessage should include(s"""Key (project_id, road_number, road_part_number)=(${project2.id}, 5, 207) is not present in table "project_reserved_road_part"""")
    }
  }

  test("Test saveProject When new part is added Then return project with new reservation") {
    var count = 0
    runWithRollback {
      reset(mockRoadLinkService)
      val roadlink = RoadLink(12345L.toString, Seq(Point(535605.272, 6982204.22, 85.90899999999965)), 540.3960283713503, AdministrativeClass.State, TrafficDirection.AgainstDigitizing, Some("25.06.2015 03:00:00"), Some(modifiedBy), LifecycleStatus.InUse, LinkGeomSource.NormalLinkInterface, 749, "")
      val countCurrentProjects = projectService.getAllProjects
      val addresses: List[ProjectReservedPart] = List(ProjectReservedPart(Sequences.nextViitePrimaryKeySeqValue: Long, RoadPart(5, 207), Some(5L), Some(Discontinuity.Continuous), Some(8L), newLength = None, newDiscontinuity = None, newEly = None))
      val roadAddressProject = Project(0, ProjectState.apply(1), "TestProject", "TestUser", DateTime.now(), "TestUser", DateTime.parse("1963-01-01"), DateTime.now(), "Some additional info", Seq(), Seq(), None, elys = Set())
      val saved = projectService.createRoadLinkProject(roadAddressProject)
      mockForProject(saved.id, roadwayAddressMapper.getRoadAddressesByRoadway(roadwayDAO.fetchAllByRoadPart(RoadPart(5, 207))).map(toProjectLink(saved)))
      projectService.saveProject(saved.copy(reservedParts = addresses, elys = Set()))
      val countAfterInsertProjects = projectService.getAllProjects
      count = countCurrentProjects.size + 1
      countAfterInsertProjects.size should be(count)
      val project = projectService.getSingleProjectById(saved.id)
      project.size should be(1)
      project.head.name should be("TestProject")
    }
  }

  test("Test checkRoadPartsExist When roadway exist Then return None") {
    runWithRollback {
      val roadNumber = 19438
      val roadStartPart = 1
      val roadwayNumber = 8000
      val id1 = Sequences.nextRoadwayId
      val ra = Seq(Roadway(id1, roadwayNumber, RoadPart(roadNumber, roadStartPart), AdministrativeClass.Unknown, Track.Combined, Discontinuity.Discontinuous, AddrMRange(0L, 1000L), reversed = false, DateTime.parse("1901-01-01"), None, "tester", Some("test road"), 1L))
      roadwayDAO.create(ra)
      val check = projectService.checkRoadPartsExist(roadNumber, roadStartPart, roadStartPart)
      check should be(None)
    }
  }

  test("Test checkRoadPartsExist When roadway does not exist Then return error message") {
    runWithRollback {
      val roadNumber = 19438
      val roadStartPart = 1
      val roadEndPart = 2
      val check = projectService.checkRoadPartsExist(roadNumber, roadStartPart, roadEndPart)
      check should be(Some(ErrorStartingRoadPartNotFound))
    }
  }

  test("Test checkRoadPartsExist When start road way exists and end doesn't then return error message") {
    runWithRollback {
      val roadNumber = 19438
      val roadStartPart = 1
      val roadEndPart = 2
      val roadwayNumber = 8000
      val id1 = Sequences.nextRoadwayId
      val ra = Seq(Roadway(id1, roadwayNumber, RoadPart(roadNumber, roadStartPart), AdministrativeClass.Unknown, Track.Combined, Discontinuity.Discontinuous, AddrMRange(0L, 1000L), reversed = false, DateTime.parse("1901-01-01"), None, "tester", Some("test road"), 1L))
      roadwayDAO.create(ra)
      val check = projectService.checkRoadPartsExist(roadNumber, roadStartPart, roadEndPart)
      check should be(Some(ErrorEndingRoadPartNotFound))
    }
  }

  test("Test checkRoadPartsExist When start road way does not exist and end exists then return error message") {
    runWithRollback {
      val roadNumber = 19438
      val roadStartPart = 1
      val roadEndPart = 2
      val roadwayNumber = 8000
      val id1 = Sequences.nextRoadwayId
      val ra = Seq(Roadway(id1, roadwayNumber, RoadPart(roadNumber, roadEndPart), AdministrativeClass.Unknown, Track.Combined, Discontinuity.Discontinuous, AddrMRange(0L, 1000L), reversed = false, DateTime.parse("1901-01-01"), None, "tester", Some("test road"), 1L))
      roadwayDAO.create(ra)
      val check = projectService.checkRoadPartsExist(roadNumber, roadStartPart, roadEndPart)
      check should be(Some(ErrorStartingRoadPartNotFound))
    }
  }

  test("Test validateReservations When road parts don't exist Then return error") {
    runWithRollback {
      val roadPart = RoadPart(19438,1)
      val projectEly = 8L
      val errorRoad = s"TIEOSA: $roadPart"
      val reservedPart = ProjectReservedPart(0L, roadPart, Some(1000L), Some(Discontinuity.Continuous), Some(projectEly))
      val roadWays = roadwayDAO.fetchAllByRoadPart(roadPart)
      projectService.validateReservations(reservedPart, Seq(), roadWays) should be(Some(s"$ErrorFollowingRoadPartsNotFoundInDB $errorRoad"))
    }
  }

  test("Test checkRoadPartsReservable When road does not exist Then return on right should be 0") {
    val roadNumber = 19438
    val roadStartPart = 1
    val roadEndPart = 2
    val roadLink = RoadLink(12345L.toString, Seq(Point(535605.272, 6982204.22, 85.90899999999965)), 540.3960283713503, AdministrativeClass.State, TrafficDirection.AgainstDigitizing, Some("25.06.2015 03:00:00"), Some(modifiedBy), LifecycleStatus.InUse, LinkGeomSource.NormalLinkInterface, 749, "")
    when(mockRoadLinkService.getRoadLinksByLinkIds(any[Set[String]])).thenReturn(Seq(roadLink))
    runWithRollback {
      val reservation = projectService.checkRoadPartsReservable(roadNumber, roadStartPart, roadEndPart, 0L)
      reservation.right.get._1.size should be(0)
    }
  }

  test("Test checkRoadPartsReservable When road can Then return on right part 2") {
    val roadNumber = 19438
    val roadStartPart = 1
    val roadEndPart = 2
    val roadwayNumber = 8000
    val roadLink = RoadLink(12345L.toString, Seq(Point(535605.272, 6982204.22, 85.90899999999965)), 540.3960283713503, AdministrativeClass.State, TrafficDirection.AgainstDigitizing, Some("25.06.2015 03:00:00"), Some(modifiedBy), LifecycleStatus.InUse, LinkGeomSource.NormalLinkInterface, 749, "")
    when(mockRoadLinkService.getRoadLinksByLinkIds(any[Set[String]])).thenReturn(Seq(roadLink))
    runWithRollback {
      val id1 = Sequences.nextRoadwayId
      val ra = Seq(Roadway(id1, roadwayNumber, RoadPart(roadNumber, roadStartPart), AdministrativeClass.Unknown, Track.Combined, Discontinuity.Discontinuous, AddrMRange(0L, 1000L), reversed = false, DateTime.parse("1901-01-01"), None, "tester", Some("test road"), 8L))
      val ll = LinearLocation(0L, 1, 123456.toString, 0, 1000L, SideCode.TowardsDigitizing, 123456, (CalibrationPointReference.None, CalibrationPointReference.None), Seq(Point(535605.272, 6982204.22, 85.90899999999965)), LinkGeomSource.NormalLinkInterface, roadwayNumber)
      roadwayDAO.create(ra)
      linearLocationDAO.create(Seq(ll))
      val id2 = Sequences.nextRoadwayId
      val rb = Seq(Roadway(id2, roadwayNumber, RoadPart(roadNumber, roadEndPart), AdministrativeClass.Unknown, Track.Combined, Discontinuity.Discontinuous, AddrMRange(0L, 1000L), reversed = false, DateTime.parse("1901-01-01"), None, "tester", Some("Test road 2"), 8L))
      roadwayDAO.create(rb)
      val reservationAfterB = projectService.checkRoadPartsReservable(roadNumber, roadStartPart, roadEndPart, 0L)
      reservationAfterB.right.get._1.size should be(2)
      reservationAfterB.right.get._1.map(_.roadPart.roadNumber).distinct.size should be(1)
      reservationAfterB.right.get._1.map(_.roadPart.roadNumber).distinct.head should be(roadNumber)
    }
  }

  test("Test checkRoadPartsReservable When road part is already reserved to the current project Then should return reserved info.") {
    val roadNumber    = 19438
    val roadStartPart = 1
    val roadEndPart   = 2
    val roadwayNumber = 8000
    val testUser      = "tester"
    val roadLink      = RoadLink(123456L.toString, Seq(Point(535605.272, 6982204.22, 85.90899999999965), Point(536605.272, 6982204.22, 85.90899999999965)), 540.3960283713503, AdministrativeClass.State, TrafficDirection.AgainstDigitizing, Some("25.06.2015 03:00:00"), Some("modified"), LifecycleStatus.InUse, LinkGeomSource.NormalLinkInterface, 749, "")
    when(mockRoadLinkService.getRoadLinksByLinkIds(any[Set[String]])).thenReturn(Seq(roadLink))
    runWithRollback {
      roadwayDAO.create(
                        Seq(Roadway(Sequences.nextRoadwayId, roadwayNumber, RoadPart(roadNumber, roadStartPart), AdministrativeClass.Unknown, Track.Combined, Discontinuity.Discontinuous, AddrMRange(0L, 1000L), reversed = false, DateTime.parse("1901-01-01"), None, testUser, Some("test road"), 8L),
                            Roadway(Sequences.nextRoadwayId, roadwayNumber, RoadPart(roadNumber, roadEndPart),   AdministrativeClass.Unknown, Track.Combined, Discontinuity.Discontinuous, AddrMRange(0L, 1000L), reversed = false, DateTime.parse("1901-01-01"), None, testUser, Some("Test road 2"), 8L))
                       )
      linearLocationDAO.create(
                               Seq(LinearLocation(0L, 1, 123456.toString, 0, 1000L, SideCode.TowardsDigitizing, 123456, (CalibrationPointReference.None, CalibrationPointReference.None), Seq(Point(535605.272, 6982204.22, 85.90899999999965)), LinkGeomSource.NormalLinkInterface, roadwayNumber))
                              )

      val reservedPart1 = ProjectReservedPart(Sequences.nextViitePrimaryKeySeqValue, RoadPart(roadNumber, roadStartPart), None, None, None)
      val reservedPart2 = ProjectReservedPart(Sequences.nextViitePrimaryKeySeqValue, RoadPart(roadNumber, roadEndPart),   None, None, None)

      val roadAddressProject = Project(0, ProjectState.apply(1), "TestProject", "TestUser", DateTime.now(), "TestUser", DateTime.parse("1963-01-01"), DateTime.now(), "Some additional info", Seq(reservedPart1, reservedPart2), Seq(), None, elys = Set())
      val project            = projectService.createRoadLinkProject(roadAddressProject)
      val projectId          = project.id

      val reservedAndFormed: Either[String, (Seq[ProjectReservedPart], Seq[ProjectReservedPart])] =
        projectService.checkRoadPartsReservable(roadNumber, roadStartPart, roadEndPart, projectId)

      reservedAndFormed should be ('right)
      reservedAndFormed.right.get._1 should have size 2 // ProjectReservedParts
      reservedAndFormed.right.get._1.map(_.roadPart.partNumber) should contain allOf (roadStartPart,roadEndPart)
    }
  }

  test("Test getRoadAddressAllProjects When project is created Then return project") {
    var count = 0
    runWithRollback {
      val countCurrentProjects = projectService.getAllProjects
      val roadAddressProject = Project(0, ProjectState.apply(1), "TestProject", "TestUser", DateTime.now(), "TestUser", DateTime.parse("1963-01-01"), DateTime.now(), "Some additional info", Seq(), Seq(), None, elys = Set())
      val project = projectService.createRoadLinkProject(roadAddressProject)
      mockForProject(project.id, roadwayAddressMapper.getRoadAddressesByRoadway(roadwayDAO.fetchAllByRoadPart(RoadPart(5, 207))).map(toProjectLink(roadAddressProject)))
      projectService.saveProject(project.copy(reservedParts = Seq(ProjectReservedPart(0L, RoadPart(5, 207), Some(0L), Some(Discontinuity.Continuous), Some(8L), None, None, None, None)), elys = Set()))
      val countAfterInsertProjects = projectService.getAllProjects
      count = countCurrentProjects.size + 1
      countAfterInsertProjects.size should be(count)
    }
  }

  test("Test projectService.changeDirection When project links have a defined direction Then return project_links with adjusted M addresses and side codes due to the reversing ") {
    runWithRollback {
      val rap = Project(0L, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("1963-01-01"), "TestUser", DateTime.parse("1963-01-01"), DateTime.now(), "Some additional info", Seq(), Seq(), None, elys = Set())
      val project = projectService.createRoadLinkProject(rap)
      val id = project.id
      mockForProject(id, roadwayAddressMapper.getRoadAddressesByLinearLocation(linearLocationDAO.fetchByRoadways(roadwayDAO.fetchAllBySection(RoadPart(5, 207)).map(_.roadwayNumber).toSet)).map(toProjectLink(project)))
      projectService.saveProject(project.copy(reservedParts = Seq(ProjectReservedPart(0L, RoadPart(5, 207), Some(0L), Some(Discontinuity.Continuous), Some(8L), None, None, None, None)), elys = Set()))
      val projectLinks = projectLinkDAO.fetchProjectLinks(id)
      projectLinkDAO.updateProjectLinksStatus(projectLinks.map(x => x.id).toSet, RoadAddressChangeType.Transfer, "test")
      mockForProject(id)
      val fetchedProjectLinks = projectLinkDAO.fetchProjectLinks(id)
      val calculatedProjectLinks = ProjectSectionCalculator.assignAddrMValues(fetchedProjectLinks)

      projectService.changeDirection(id, RoadPart(5, 207), projectLinks.map(l => LinkToRevert(l.id, l.linkId, l.status.value, l.geometry)), ProjectCoordinates(0, 0, 0), "test") should be(None)
      val updatedProjectLinks = ProjectSectionCalculator.assignAddrMValues(projectLinkDAO.fetchProjectLinks(id))
      val maxBefore = if (projectLinks.nonEmpty) calculatedProjectLinks.maxBy(_.addrMRange.end).addrMRange.end else 0
      val maxAfter = if (updatedProjectLinks.nonEmpty) updatedProjectLinks.maxBy(_.addrMRange.end).addrMRange.end else 0
      maxBefore should be(maxAfter)
      val combined = updatedProjectLinks.filter(_.track == Track.Combined)
      val right = updatedProjectLinks.filter(_.track == Track.RightSide)
      val left = updatedProjectLinks.filter(_.track == Track.LeftSide)

      (combined ++ right).sortBy(_.addrMRange.start).foldLeft(Seq.empty[ProjectLink]) { case (seq, plink) =>
        if (seq.nonEmpty)
          seq.last.addrMRange.end should be(plink.addrMRange.start)
        seq ++ Seq(plink)
      }

      (combined ++ left).sortBy(_.addrMRange.start).foldLeft(Seq.empty[ProjectLink]) { case (seq, plink) =>
        if (seq.nonEmpty)
          seq.last.addrMRange.end should be(plink.addrMRange.start)
        seq ++ Seq(plink)
      }
      projectService.changeDirection(id, RoadPart(5, 207), projectLinks.map(l => LinkToRevert(l.id, l.linkId, l.status.value, l.geometry)), ProjectCoordinates(0, 0, 0), "test")
      val secondUpdatedProjectLinks = projectLinkDAO.fetchProjectLinks(id)
      projectLinks.sortBy(_.addrMRange.end).map(_.geometry).zip(secondUpdatedProjectLinks.sortBy(_.addrMRange.end).map(_.geometry)).forall { case (x, y) => x == y }
      secondUpdatedProjectLinks.foreach(x => x.reversed should be(false))
    }
  }

  test("Test projectService.preserveSingleProjectToBeTakenToRoadNetwork() " +
    "When project has been created with no reserved parts nor project links " +
    "Then return project status info should be \"\" ") {
    runWithRollback {
      val projectId = Sequences.nextViiteProjectId
      val rap = Project(projectId, ProjectState.apply(ProjectState.InUpdateQueue.value), "TestProject", "TestUser", DateTime.parse("2700-01-01"), "TestUser", DateTime.parse("2700-01-01"), DateTime.now(), "Some additional info", List.empty[ProjectReservedPart], Seq(), None, elys = Set())
      runWithRollback {
        projectDAO.create(rap)
        projectService.preserveSingleProjectToBeTakenToRoadNetwork()
        val project = projectService.fetchProjectById(projectId).head
        project.statusInfo.getOrElse("").length should be(0)
      }
    }
  }

  test("Test projectService.saveProject When after project creation we reserve roads to it Then return the count of created project should be increased AND Test projectService.getAllProjects When not specifying a projectId Then return the count of returned projects should be 0 ") {
    var count = 0
    runWithRollback {
      val countCurrentProjects = projectService.getAllProjects
      val id = 0
      val roadAddressProject = Project(id, ProjectState.apply(1), "TestProject", "TestUser", DateTime.now(), "TestUser", DateTime.parse("1997-01-01"), DateTime.now(), "Some additional info", List(), Seq(), None, elys = Set())
      val project = projectService.createRoadLinkProject(roadAddressProject)
      mockForProject(project.id, roadwayAddressMapper.getRoadAddressesByLinearLocation(linearLocationDAO.fetchByRoadways(roadwayDAO.fetchAllBySection(RoadPart(1130, 4)).map(_.roadwayNumber).toSet)).map(toProjectLink(roadAddressProject)))
      projectService.saveProject(project.copy(reservedParts = List(ProjectReservedPart(Sequences.nextViitePrimaryKeySeqValue: Long, RoadPart(1130, 4), Some(5L), Some(Discontinuity.Continuous), Some(1L), newLength = None, newDiscontinuity = None, newEly = None)), elys = Set()))
      val countAfterInsertProjects = projectService.getAllProjects
      count = countCurrentProjects.size + 1
      countAfterInsertProjects.size should be(count)
    }
    runWithRollback {
      projectService.getAllProjects
    } should have size (count - 1)
  }

  test("Test projectService.createRoadLinkProject(), projectService.deleteProject When a user wants to create a new, simple project and then delete it Then return the number of existing projects should increase by 1 and then decrease by 1 as well") {
    var numberOfProjects = 0
    runWithRollback {
      val countCurrentProjects = projectService.getAllProjects
      val roadAddressProject = Project(0, ProjectState.apply(1), "TestProject", "TestUser", DateTime.now(), "TestUser", DateTime.parse("2012-01-01"), DateTime.now(), "Some additional info", Seq(), Seq(), None, elys = Set())
      val project = projectService.createRoadLinkProject(roadAddressProject)
      mockForProject(project.id, roadwayAddressMapper.getRoadAddressesByLinearLocation(linearLocationDAO.fetchByRoadways(roadwayDAO.fetchAllBySection(RoadPart(5, 203)).map(_.roadwayNumber).toSet)).map(toProjectLink(roadAddressProject)))
      projectService.saveProject(project.copy(reservedParts = Seq(ProjectReservedPart(0L, RoadPart(5, 203), Some(100L), Some(Discontinuity.Continuous), Some(8L), None, None, None, None)), elys = Set()))
      val countAfterInsertProjects = projectService.getAllProjects
      numberOfProjects = countCurrentProjects.size + 1
      countAfterInsertProjects.size should be(numberOfProjects)
      projectService.deleteProject(project.id)
      numberOfProjects -= 1
      val projectsAfterOperations = projectService.getAllProjects
      projectsAfterOperations.size should be(numberOfProjects)
      projectsAfterOperations.exists(_.get("id").get == project.id) should be(false)
      val stateCodeForProject = sql"""SELECT state from project where id =  ${project.id}""".as[Int].list
      stateCodeForProject should have size 1
      ProjectState(stateCodeForProject.head) should be(ProjectState.Deleted)
    }
  }

  test("Test projectService.validateProjectDate() When evaluating project links with start date are equal as the project start date Then return no error message") {
    runWithRollback {
      val projDate = DateTime.parse("1990-01-01")
      val addresses = List(ProjectReservedPart(5: Long, RoadPart(5, 205), Some(5L), Some(Discontinuity.Continuous), Some(8L), newLength = None, newDiscontinuity = None, newEly = None))
      val errorMsg = projectService.validateProjectDate(addresses, projDate)
      errorMsg should not be None
    }
  }

  test("Test projectService.validateProjectDate() When evaluating  project links whose start date is a valid one Then return no error message") {
    runWithRollback {
      val projDate = DateTime.parse("2015-01-01")
      val addresses = List(ProjectReservedPart(5: Long, RoadPart(5, 205), Some(5L), Some(Discontinuity.Continuous), Some(8L), newLength = None, newDiscontinuity = None, newEly = None))
      val errorMsg = projectService.validateProjectDate(addresses, projDate)
      errorMsg should be(None)
    }
  }

  test("Test projectService.validateProjectDate() When evaluating  project links whose start date and end dates are valid Then return no error message") {
    runWithRollback {
      val projDate = DateTime.parse("2018-01-01")
      val addresses = List(ProjectReservedPart(5: Long, RoadPart(5, 205), Some(5L), Some(Discontinuity.Continuous), Some(8L), newLength = None, newDiscontinuity = None, newEly = None))
      val errorMsg = projectService.validateProjectDate(addresses, projDate)
      errorMsg should be(None)
    }
  }

  test("Test projectService.getSingleProjectById() When after creation of a new project, with reserved parts Then return only 1 project whose name is the same that was defined. ") {
    var count = 0
    runWithRollback {
      val countCurrentProjects = projectService.getAllProjects
      val addresses: List[ProjectReservedPart] = List(ProjectReservedPart(Sequences.nextViitePrimaryKeySeqValue: Long, RoadPart(5, 206), Some(5L), Some(Discontinuity.Continuous), Some(8L), newLength = None, newDiscontinuity = None, newEly = None))
      val roadAddressProject = Project(0, ProjectState.apply(1), "TestProject", "TestUser", DateTime.now(), "TestUser", DateTime.parse("2017-01-01"), DateTime.now(), "Some additional info", Seq(), Seq(), None, elys = Set())
      val saved = projectService.createRoadLinkProject(roadAddressProject)
      mockForProject(saved.id, roadwayAddressMapper.getRoadAddressesByLinearLocation(linearLocationDAO.fetchByRoadways(roadwayDAO.fetchAllBySection(RoadPart(5, 206)).map(_.roadwayNumber).toSet)).map(toProjectLink(saved)))
      projectService.saveProject(saved.copy(reservedParts = addresses, elys = Set()))
      when(mockRoadLinkService.getRoadLinksHistoryFromVVH(any[Set[String]])).thenReturn(Seq())
      val countAfterInsertProjects = projectService.getAllProjects
      count = countCurrentProjects.size + 1
      countAfterInsertProjects.size should be(count)
      val project = projectService.getSingleProjectById(saved.id)
      project.size should be(1)
      project.head.name should be("TestProject")
    }
    runWithRollback {
      projectService.getAllProjects.size should be(count - 1)
    }
  }

  test("Test getRoadAddressSingleProject When project with reserved parts is created Then return the created project") {
    val projectId = 0L
    val roadPart = RoadPart(19438,1)
    val linkId = 12345L.toString
    val roadwayNumber = 8000
    runWithRollback {
      //Creation of Test road
      val id = Sequences.nextRoadwayId
      val ra = Seq(Roadway(id, roadwayNumber, roadPart, AdministrativeClass.Unknown, Track.Combined, Discontinuity.Discontinuous, AddrMRange(0L, 1000L), reversed = false, DateTime.parse("1901-01-01"), None, "Tester", Option("test name"), 8L))
      val ll = LinearLocation(0L, 1, linkId, 0, 1000L, SideCode.TowardsDigitizing, 123456, (CalibrationPointReference.None, CalibrationPointReference.None), Seq(Point(535605.272, 6982204.22, 85.90899999999965)), LinkGeomSource.NormalLinkInterface, roadwayNumber)
      val rl = RoadLink(linkId, Seq(Point(0.0, 0.0), Point(0.0, 9.8)), 9.8, AdministrativeClass.State, TrafficDirection.BothDirections, None, None, municipalityCode = 167, sourceId = "")
      when(mockRoadLinkService.getRoadLinksHistoryFromVVH(any[Set[String]])).thenReturn(Seq())
      when(mockRoadLinkService.getRoadLinksByLinkIds(any[Set[String]])).thenReturn(Seq(rl))
      roadwayDAO.create(ra)
      linearLocationDAO.create(Seq(ll))

      //Creation of test project with test links
      val project = Project(projectId, ProjectState.Incomplete, "testiprojekti", "Test", DateTime.now(), "Test", DateTime.now(), DateTime.now(), "info", List(ProjectReservedPart(Sequences.nextViitePrimaryKeySeqValue: Long, roadPart: RoadPart, Some(5L), Some(Discontinuity.Continuous), Some(8L), newLength = None, newDiscontinuity = None, newEly = None)), Seq(), None, elys = Set())
      val proj = projectService.createRoadLinkProject(project)
      val returnedProject = projectService.getSingleProjectById(proj.id).get
      returnedProject.name should be("testiprojekti")
      returnedProject.reservedParts.size should be(1)
      returnedProject.reservedParts.head.roadPart should be(roadPart)
    }
  }

  test("Test addNewLinksToProject When reserving part connects to other project road part by same junction Then return error message") {
//    Project1 projectlink is connected to Project2 projectlink at point 1000, 10 and there is no junction which means that Project2 projectlink cannot be created with new action
    runWithRollback {
      val id = Sequences.nextViiteProjectId
      val ra1 = Seq(
        RoadAddress(12345, linearLocationId,     RoadPart(19999, 1), AdministrativeClass.State, Track.Combined, Discontinuity.Continuous, AddrMRange(0L,  5L), Some(DateTime.parse("1901-01-01")), None, Some("User"), 1000.toString, 0, 5, SideCode.TowardsDigitizing,  DateTime.now().getMillis, (None, None), Seq(Point(1000.0,  0.0), Point(1000.0,  5.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, roadwayNumber1, Some(DateTime.parse("1901-01-01")), None, None),
        RoadAddress(12346, linearLocationId + 1, RoadPart(19999, 1), AdministrativeClass.State, Track.Combined, Discontinuity.EndOfRoad,  AddrMRange(5L, 10L), Some(DateTime.parse("1901-01-01")), None, Some("User"), 1001.toString, 0, 5, SideCode.TowardsDigitizing,  DateTime.now().getMillis, (None, None), Seq(Point(1000.0,  5.0), Point(1000.0, 10.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, roadwayNumber2, Some(DateTime.parse("1901-01-01")), None, None)
      )
      val ra2 = Seq(
        RoadAddress(12347, linearLocationId + 2, RoadPart(12345, 1), AdministrativeClass.State, Track.Combined, Discontinuity.Continuous, AddrMRange(10L, 15L), Some(DateTime.parse("1901-01-01")), None, Some("User"), 1002.toString, 0, 5, SideCode.TowardsDigitizing, DateTime.now().getMillis, (None, None), Seq(Point(1000.0, 10.0), Point(1000.0, 25.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, roadwayNumber3, Some(DateTime.parse("1901-01-01")), None, None),
        RoadAddress(12348, linearLocationId + 3, RoadPart(12345, 1), AdministrativeClass.State, Track.Combined, Discontinuity.EndOfRoad,  AddrMRange(15L, 20L), Some(DateTime.parse("1901-01-01")), None, Some("User"), 1003.toString, 0, 5, SideCode.TowardsDigitizing, DateTime.now().getMillis, (None, None), Seq(Point(1000.0, 25.0), Point(1000.0, 30.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, roadwayNumber4, Some(DateTime.parse("1901-01-01")), None, None)
      )

      val rap1 = Project(id, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("2700-01-01"), "TestUser", DateTime.parse("1972-03-03"), DateTime.parse("2700-01-01"), "Some additional info", List.empty[ProjectReservedPart], Seq(), None, elys = Set())
      val projectLinks1 = ra1.map {
        toProjectLink(rap1)(_)
      }
      projectDAO.create(rap1)
      projectService.saveProject(rap1)

      val rap2 = Project(id + 1, ProjectState.apply(1), "TestProject2", "TestUser", DateTime.parse("2700-01-01"), "TestUser", DateTime.now(), DateTime.parse("2700-01-01"), "Some additional info", List.empty[ProjectReservedPart], Seq(), None, elys = Set())
      val projectLinks2 = ra2.map {
        toProjectLink(rap2)(_)
      }
      projectDAO.create(rap2)
      projectService.saveProject(rap2)

      when(mockRoadLinkService.getRoadLinksByLinkIds(any[Set[String]])).thenReturn(projectLinks1.map(toRoadLink))
      val response1 = projectService.createProjectLinks(Seq(1000L.toString, 1001L.toString), rap1.id, RoadPart(56, 207), Track.Combined, Discontinuity.EndOfRoad, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, 8L, "test", "road name")
      response1("success").asInstanceOf[Boolean] should be(true)

      when(mockRoadLinkService.getRoadLinksByLinkIds(any[Set[String]])).thenReturn(projectLinks2.map(toRoadLink))
      val response2 = projectService.createProjectLinks(Seq(1002L.toString,1003L.toString), rap2.id, RoadPart(55, 206), Track.Combined, Discontinuity.EndOfRoad, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, 8L, "test", "road name")
      response2("success").asInstanceOf[Boolean] should be(false)
      response2("errorMessage").asInstanceOf[String] should be(ErrorWithNewAction)

    }
  }

  test("Test addNewLinksToProject When reserving part already used in other project Then return error message") {
    runWithRollback {
      val idr = Sequences.nextRoadwayId
      val id = Sequences.nextViiteProjectId
      val rap = Project(id, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("2700-01-01"), "TestUser", DateTime.parse("1972-03-03"), DateTime.parse("2700-01-01"), "Some additional info", List.empty[ProjectReservedPart], Seq(), None, elys = Set())
      val projectLink = toProjectLink(rap, RoadAddressChangeType.New)(RoadAddress(idr, 123, RoadPart(5, 207), AdministrativeClass.Unknown, Track.Combined, Discontinuity.Discontinuous, AddrMRange(0L, 10L), Some(DateTime.parse("1963-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), 12345L.toString, 0.0, 9.8, SideCode.TowardsDigitizing, 0, (None, None), Seq(Point(0.0, 0.0), Point(0.0, 9.8)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      projectDAO.create(rap)
      projectService.saveProject(rap)


      val rap2 = Project(id + 1, ProjectState.apply(1), "TestProject2", "TestUser", DateTime.parse("2700-01-01"), "TestUser", DateTime.now(), DateTime.parse("2700-01-01"), "Some additional info", List.empty[ProjectReservedPart], Seq(), None, elys = Set())
      val projectLink2 = toProjectLink(rap2, RoadAddressChangeType.New)(RoadAddress(idr, 1234, RoadPart(5, 999), AdministrativeClass.Unknown, Track.Combined, Discontinuity.Discontinuous, AddrMRange(0L, 10L), Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), 12345L.toString, 0.0, 9.8, SideCode.TowardsDigitizing, 0, (None, None), Seq(Point(0.0, 0.0), Point(0.0, 9.8)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      projectDAO.create(rap2)
      projectService.saveProject(rap2)
      val roadwayN = 100000L
      addProjectLinksToProject(RoadAddressChangeType.Transfer, Seq(0L, 10L), discontinuity = Discontinuity.Continuous, lastLinkDiscontinuity = Discontinuity.EndOfRoad, project = rap2, roadPart = RoadPart(5, 999), roadwayNumber = roadwayN, withRoadInfo = true)

      val projectLink3 = toProjectLink(rap, RoadAddressChangeType.New)(RoadAddress(idr, 12345, RoadPart(5, 999), AdministrativeClass.Unknown, Track.Combined, Discontinuity.Discontinuous, AddrMRange(0L, 10L), Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), 12345L.toString, 0.0, 9.8, SideCode.TowardsDigitizing, 0, (None, None), Seq(Point(0.0, 0.0), Point(0.0, 9.8)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))

      val calibrationPoints = projectLink.calibrationPoints
      val p = ProjectAddressLink(idr, projectLink.linkId, projectLink.geometry, 1, AdministrativeClass.apply(1), LifecycleStatus.apply(1), projectLink.linkGeomSource, AdministrativeClass.State, None, 111, "Heinola", Some(""), Some(modifiedBy), projectLink.roadPart, 2, -1, projectLink.discontinuity.value, projectLink.addrMRange, projectLink.startMValue, projectLink.endMValue, projectLink.sideCode, calibrationPoints._1, calibrationPoints._2, projectLink.status, 12345, 123456, sourceId = "")

      mockForProject(id, Seq(p))

      val message1project1 = projectService.addNewLinksToProject(Seq(projectLink), id, "U", p.linkId, newTransaction = true, Discontinuity.Discontinuous).getOrElse("")
      val links = projectLinkDAO.fetchProjectLinks(id)
      links.size should be(0)
      message1project1 should be(RoadNotAvailableMessage) //check that it is reserved in roadaddress table

      val message1project2 = projectService.addNewLinksToProject(Seq(projectLink2), id + 1, "U", p.linkId, newTransaction = true, Discontinuity.Discontinuous)
      val links2 = projectLinkDAO.fetchProjectLinks(id + 1)
      links2.size should be(2)
      message1project2 should be(None)

      val message2project1 = projectService.addNewLinksToProject(Seq(projectLink3), id, "U", p.linkId, newTransaction = true, Discontinuity.Discontinuous).getOrElse("")
      val links3 = projectLinkDAO.fetchProjectLinks(id)
      links3.size should be(0)
      message2project1 should be("Antamasi tienumero ja tieosanumero ovat jo käytössä. Tarkista syöttämäsi tiedot.")
    }
  }

  test("Test createProjectLinks When adding new links to a new road and roadpart Then discontinuity should be on correct link.") {
    def runTest(reversed: Boolean) = {
      val projectId      = Sequences.nextViiteProjectId
      val roadPart       = RoadPart(5,207)
      val user           = "TestUser"
      val continuous     = Discontinuity.Continuous
      val discontinuity  = Discontinuity.EndOfRoad
      val rap            = Project(projectId, ProjectState.apply(1), "TestProject", user, DateTime.parse("2700-01-01"), user, DateTime.parse("1972-03-03"), DateTime.parse("2700-01-01"), "Some additional info", List.empty[ProjectReservedPart], Seq(), None, elys = Set())
      val projectLinks   = Seq(
        toProjectLink(rap, RoadAddressChangeType.New)(RoadAddress(Sequences.nextRoadwayId, 123, roadPart, AdministrativeClass.Unknown, Track.Combined, continuous, AddrMRange(0L,  0L), Some(DateTime.parse("1963-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), 12345L.toString, 0.0,  9.8, SideCode.Unknown, 0, (None, None), Seq(Point(0.0, 0.0), Point(0.0,  9.8)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0)),
        toProjectLink(rap, RoadAddressChangeType.New)(RoadAddress(Sequences.nextRoadwayId, 124, roadPart, AdministrativeClass.Unknown, Track.Combined, continuous, AddrMRange(0L,  0L), Some(DateTime.parse("1963-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), 12346L.toString, 0.0, 19.8, SideCode.Unknown, 0, (None, None), Seq(Point(0.0, 9.8), Point(9.8, 29.6)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      )
      projectDAO.create(rap)
      projectService.saveProject(rap)
      projectReservedPartDAO.reserveRoadPart(projectId, roadPart, user)
      when(mockRoadLinkService.getRoadLinksByLinkIds(any[Set[String]])).thenReturn(projectLinks.map(toRoadLink))

      val longs            = if (reversed) projectLinks.map(_.linkId).reverse else projectLinks.map(_.linkId)
      val message1project1 = projectService.createProjectLinks(longs, rap.id, roadPart, Track.Combined, discontinuity, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, 8, user, "new road")
      val links            = projectLinkDAO.fetchProjectLinks(projectId).toList
      message1project1("success") shouldBe true
      val startLink = links.find(_.linkId == longs.head).get
      val endLink   = links.find(_.linkId == longs.last).get

      startLink.discontinuity should be(continuous)
      endLink.discontinuity should be(discontinuity)
    }

    runWithRollback {runTest(false)}
    runWithRollback {runTest(true)}
  }

  test("Test addNewLinksToProject When adding new links to a new road and roadpart Then values should be correct.") {
    runWithRollback {
      val projectId      = Sequences.nextViiteProjectId
      val roadPart       = RoadPart(5,207)
      val user           = "TestUser"
      val rap            = Project(projectId, ProjectState.apply(1), "TestProject", user, DateTime.parse("2700-01-01"), user, DateTime.parse("1972-03-03"), DateTime.parse("2700-01-01"), "Some additional info", List.empty[ProjectReservedPart], Seq(), None, elys = Set())
      val projectLink1   = toProjectLink(rap, RoadAddressChangeType.New)(RoadAddress(Sequences.nextRoadwayId, 123, roadPart, AdministrativeClass.Unknown, Track.Combined, Discontinuity.Continuous, AddrMRange(0L,  0L), Some(DateTime.parse("1963-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), 12345L.toString, 0.0,  9.8, SideCode.Unknown, 0, (None, None), Seq(Point(0.0, 0.0), Point(0.0,  9.8)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink2   = toProjectLink(rap, RoadAddressChangeType.New)(RoadAddress(Sequences.nextRoadwayId, 124, roadPart, AdministrativeClass.Unknown, Track.Combined, Discontinuity.Continuous, AddrMRange(0L,  0L), Some(DateTime.parse("1963-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), 12346L.toString, 0.0, 19.8, SideCode.Unknown, 0, (None, None), Seq(Point(0.0, 9.8), Point(9.8, 29.6)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      projectDAO.create(rap)
      projectService.saveProject(rap)
      projectReservedPartDAO.reserveRoadPart(projectId, roadPart, user)

      val message1project1 = projectService.addNewLinksToProject(Seq(projectLink1, projectLink2), projectId, user, projectLink1.linkId, newTransaction = true, Discontinuity.Discontinuous).getOrElse("")
      val links            = projectLinkDAO.fetchProjectLinks(projectId).toList
      message1project1 should be("")
      links.size should be(2)
      val sortedLinks = links.sortBy(_.linkId)
      sortedLinks.map(_.discontinuity) should be(Seq(Discontinuity.Continuous, Discontinuity.Discontinuous))
    }
  }

  test("Test addNewLinksToProject When adding new links to existing road and new roadpart Then values should be correct.") {
    runWithRollback {
      val projectId = Sequences.nextViiteProjectId
      val roadPart    = RoadPart(5, 206)
      val newRoadPart = RoadPart(5, 207)
      val user = "TestUser"
      val rap = Project(projectId, ProjectState.apply(1), "TestProject", user, DateTime.parse("2700-01-01"), user, DateTime.parse("1972-03-03"), DateTime.parse("2700-01-01"), "Some additional info", List.empty[ProjectReservedPart], Seq(), None, elys = Set())
      val projectLinks = Seq(
       toProjectLink(rap, RoadAddressChangeType.New)(RoadAddress(Sequences.nextRoadwayId, 125, newRoadPart, AdministrativeClass.Unknown, Track.Combined, Discontinuity.Continuous, AddrMRange(0L, 0L), Some(DateTime.parse("1963-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), 12345L.toString, 0.0, 10.4, SideCode.Unknown, 0, (None, None), Seq(Point(532427.945,6998488.475,0.0),Point(532395.164,6998549.616,0.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0)),
       toProjectLink(rap, RoadAddressChangeType.New)(RoadAddress(Sequences.nextRoadwayId, 126, newRoadPart, AdministrativeClass.Unknown, Track.Combined, Discontinuity.Continuous, AddrMRange(0L, 0L), Some(DateTime.parse("1963-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), 12346L.toString, 0.0, 10.0, SideCode.Unknown, 0, (None, None), Seq(Point(532427.945,6998488.475,0.0),Point(532495.0,  6998649.0,  0.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      )
      projectDAO.create(rap)
      projectService.saveProject(rap)
      projectReservedPartDAO.reserveRoadPart(projectId, roadPart,    user)
      projectReservedPartDAO.reserveRoadPart(projectId, newRoadPart, user)
      val roadwayN = 100000L
      addProjectLinksToProject(RoadAddressChangeType.Unchanged, Seq(0L, 10L, 20L), discontinuity = Discontinuity.Continuous, lastLinkDiscontinuity = Discontinuity.EndOfRoad, project = rap, roadPart = roadPart, withRoadInfo = true)

      val message1project1 = projectService.addNewLinksToProject(projectLinks, projectId, user, projectLinks.head.linkId, newTransaction = true, Discontinuity.Discontinuous).getOrElse("")
      val links = projectLinkDAO.fetchProjectLinks(projectId).filter(_.status == RoadAddressChangeType.New)
      message1project1 should be("")
      links.size should be(2)
      val sortedLinks = links.sortBy(_.linkId)
      sortedLinks.map(_.discontinuity) should be(Seq(Discontinuity.Continuous, Discontinuity.Discontinuous))
    }
  }

  test("Test addNewLinksToProject When adding new links to existing road and existing roadpart Then values should be correct.") {
    runWithRollback {
      val projectId = Sequences.nextViiteProjectId
      val roadPart = RoadPart(5,206)
      val user = "TestUser"
      val rap = Project(projectId, ProjectState.apply(1), "TestProject", user, DateTime.parse("2700-01-01"), user, DateTime.parse("2700-01-01"), DateTime.parse("2700-01-01"), "Some additional info", List.empty[ProjectReservedPart], Seq(), None, elys = Set())
      val projectLinks = Seq(
        toProjectLink(rap, RoadAddressChangeType.New)(RoadAddress(Sequences.nextRoadwayId, 125, roadPart, AdministrativeClass.Unknown, Track.Combined, Discontinuity.Continuous, AddrMRange(0L, 0L), Some(DateTime.parse("1963-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), 12345L.toString, 0.0, 10.4, SideCode.Unknown, 0, (None, None), Seq(Point(0,20),Point(0,30,0.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0)),
        toProjectLink(rap, RoadAddressChangeType.New)(RoadAddress(Sequences.nextRoadwayId, 126, roadPart, AdministrativeClass.Unknown, Track.Combined, Discontinuity.Continuous, AddrMRange(0L, 0L), Some(DateTime.parse("1963-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), 12346L.toString, 0.0, 10.0, SideCode.Unknown, 0, (None, None), Seq(Point(0.0,30.0),Point(0.0,40.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      )
      projectDAO.create(rap)
      val savedp = projectService.saveProject(rap)
      projectReservedPartDAO.reserveRoadPart(projectId, roadPart, user)
      val roadwayN = 100000L
      addProjectLinksToProject(RoadAddressChangeType.Unchanged, Seq(0L, 10L, 20L), discontinuity = Discontinuity.Continuous, lastLinkDiscontinuity = Discontinuity.EndOfRoad, project = rap, roadPart = roadPart, roadwayNumber = roadwayN, withRoadInfo = true)
      val x = projectReservedPartDAO.fetchReservedRoadPart(roadPart)
      val y = projectReservedPartDAO.fetchReservedRoadParts(projectId)

      val message1project1 = projectService.addNewLinksToProject(projectLinks, projectId, user, projectLinks.head.linkId, newTransaction = true, Discontinuity.Discontinuous).getOrElse("")
      val links = projectLinkDAO.fetchProjectLinks(projectId).filter(_.status == RoadAddressChangeType.New)
      message1project1 should be("")
      links.size should be(2)
      val sortedLinks = links.sortBy(_.linkId)
      sortedLinks.map(_.discontinuity) should be(Seq(Discontinuity.Continuous, Discontinuity.Discontinuous))
    }
  }

  test("Test defaultSectionCalculatorStrategy.assignAddressMValues() " +
       "When a new road and part has the end of road set " +
       "Then calculated addresses should increase towards the end of the road.") {

    def runTest(reversed: Boolean) = {
      val user           = "TestUser"
      val roadPart       = RoadPart(10000,1)
      val project_id     = Sequences.nextViiteProjectId

      val headLink    = ProjectLink(1000, roadPart, Track.Combined, Discontinuity.Continuous, AddrMRange(0, 0), AddrMRange(0, 0), None, None, Some(user), 5174997.toString, 0.0, 152.337, SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP), List(Point(536938.0, 6984394.0, 0.0), Point(536926.0, 6984546.0, 0.0)), project_id, RoadAddressChangeType.New, AdministrativeClass.Municipality, LinkGeomSource.FrozenLinkInterface, 152.337, 0, 0, 8, false, None, 1548802841000L, 0, Some("testroad"), None, None, None, None, None)
      val lastLink    = ProjectLink(1009, roadPart, Track.Combined, Discontinuity.Continuous, AddrMRange(0, 0), AddrMRange(0, 0), None, None, Some(user), 5174584.toString, 0.0,  45.762, SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP), List(Point(536528.0, 6984439.0, 0.0), Point(536522.0, 6984484.0, 0.0)), project_id, RoadAddressChangeType.New, AdministrativeClass.Municipality, LinkGeomSource.FrozenLinkInterface,  45.762, 0, 0, 8, false, None, 1551999616000L, 0, Some("testroad"), None, None, None, None, None)
      val middleLinks = Seq(
        ProjectLink(1001,roadPart,Track.Combined,Discontinuity.Continuous,AddrMRange(0,0),AddrMRange(0,0),None,None,Some(user),5175001.toString,0.0, 72.789,SideCode.Unknown,(NoCP,NoCP),(NoCP,NoCP),List(Point(536938.0,6984394.0,0.0), Point(536865.0,6984398.0,0.0)),project_id,RoadAddressChangeType.New,AdministrativeClass.Municipality, LinkGeomSource.FrozenLinkInterface, 72.789,0,0,8,false,None,1500418814000L,0,Some("testroad"),None,None,None,None,None),
        ProjectLink(1002,roadPart,Track.Combined,Discontinuity.Continuous,AddrMRange(0,0),AddrMRange(0,0),None,None,Some(user),5174998.toString,0.0, 84.091,SideCode.Unknown,(NoCP,NoCP),(NoCP,NoCP),List(Point(536781.0,6984396.0,0.0), Point(536865.0,6984398.0,0.0)),project_id,RoadAddressChangeType.New,AdministrativeClass.Municipality, LinkGeomSource.FrozenLinkInterface, 84.091,0,0,8,false,None,1500418814000L,0,Some("testroad"),None,None,None,None,None),
        ProjectLink(1003,roadPart,Track.Combined,Discontinuity.Continuous,AddrMRange(0,0),AddrMRange(0,0),None,None,Some(user),5174994.toString,0.0,129.02 ,SideCode.Unknown,(NoCP,NoCP),(NoCP,NoCP),List(Point(536781.0,6984396.0,0.0), Point(536773.0,6984525.0,0.0)),project_id,RoadAddressChangeType.New,AdministrativeClass.Municipality, LinkGeomSource.FrozenLinkInterface,129.02 ,0,0,8,false,None,1500418814000L,0,Some("testroad"),None,None,None,None,None),
        ProjectLink(1004,roadPart,Track.Combined,Discontinuity.Continuous,AddrMRange(0,0),AddrMRange(0,0),None,None,Some(user),5174545.toString,0.0, 89.803,SideCode.Unknown,(NoCP,NoCP),(NoCP,NoCP),List(Point(536684.0,6984513.0,0.0), Point(536773.0,6984525.0,0.0)),project_id,RoadAddressChangeType.New,AdministrativeClass.Municipality, LinkGeomSource.FrozenLinkInterface, 89.803,0,0,8,false,None,1500418814000L,0,Some("testroad"),None,None,None,None,None),
        ProjectLink(1005,roadPart,Track.Combined,Discontinuity.Continuous,AddrMRange(0,0),AddrMRange(0,0),None,None,Some(user),5174996.toString,0.0,138.959,SideCode.Unknown,(NoCP,NoCP),(NoCP,NoCP),List(Point(536694.0,6984375.0,0.0), Point(536684.0,6984513.0,0.0)),project_id,RoadAddressChangeType.New,AdministrativeClass.Municipality, LinkGeomSource.FrozenLinkInterface,138.959,0,0,8,false,None,1551999616000L,0,Some("testroad"),None,None,None,None,None),
        ProjectLink(1006,roadPart,Track.Combined,Discontinuity.Continuous,AddrMRange(0,0),AddrMRange(0,0),None,None,Some(user),5175004.toString,0.0, 41.233,SideCode.Unknown,(NoCP,NoCP),(NoCP,NoCP),List(Point(536653.0,6984373.0,0.0), Point(536694.0,6984375.0,0.0)),project_id,RoadAddressChangeType.New,AdministrativeClass.Municipality, LinkGeomSource.FrozenLinkInterface, 41.233,0,0,8,false,None,1551999616000L,0,Some("testroad"),None,None,None,None,None),
        ProjectLink(1007,roadPart,Track.Combined,Discontinuity.Continuous,AddrMRange(0,0),AddrMRange(0,0),None,None,Some(user),5174936.toString,0.0,117.582,SideCode.Unknown,(NoCP,NoCP),(NoCP,NoCP),List(Point(536535.0,6984364.0,0.0), Point(536653.0,6984373.0,0.0)),project_id,RoadAddressChangeType.New,AdministrativeClass.Municipality, LinkGeomSource.FrozenLinkInterface,117.582,0,0,8,false,None,1500418814000L,0,Some("testroad"),None,None,None,None,None),
        ProjectLink(1008,roadPart,Track.Combined,Discontinuity.Continuous,AddrMRange(0,0),AddrMRange(0,0),None,None,Some(user),5174956.toString,0.0, 75.055,SideCode.Unknown,(NoCP,NoCP),(NoCP,NoCP),List(Point(536535.0,6984364.0,0.0), Point(536528.0,6984439.0,0.0)),project_id,RoadAddressChangeType.New,AdministrativeClass.Municipality, LinkGeomSource.FrozenLinkInterface, 75.055,0,0,8,false,None,1500418814000L,0,Some("testroad"),None,None,None,None,None)
      )

      val projectLinks = if (reversed)
        headLink +: middleLinks :+ lastLink.copy(discontinuity = Discontinuity.EndOfRoad)
      else
        headLink.copy(discontinuity = Discontinuity.EndOfRoad) +: middleLinks :+ lastLink

      val calculatedProjectLinks = ProjectSectionCalculator.assignAddrMValues(projectLinks)
      val maxLink                = calculatedProjectLinks.maxBy(_.addrMRange.end)
      if (reversed)
        maxLink.id should be(lastLink.id)
      else
        maxLink.id should be(headLink.id)
      maxLink.addrMRange.end should be > 0L
      maxLink.discontinuity should be(Discontinuity.EndOfRoad)
    }

    runWithRollback {runTest(false)}
    runWithRollback {runTest(true) }
  }

  test("Test projectService.parsePreFillData When supplied a empty sequence of VVHRoadLinks Then return a error message.") {
    projectService.parsePreFillData(Seq.empty[ProjectLink]) should be(Left("Link could not be found from project: -1000"))
  }


  test("Test projectService.fetchPreFillData " +
       "When supplied a sequence of one valid RoadLink " +
       "Then return the correct pre-fill data for that link.") {
    runWithRollback {
      val roadPart = RoadPart(170,10)
      val user     = "test user"
      val roadAddressProject = Project(testProjectId, Incomplete, "preFill", user, DateTime.now(), user, DateTime.now().plusMonths(2), DateTime.now(), "", Seq(), Seq(), None, None, Set())

      val projectLinks = roadwayAddressMapper.getRoadAddressesByRoadway(roadwayDAO.fetchAllByRoadPart(roadPart)).map(toProjectLink(roadAddressProject))
      projectReservedPartDAO.reserveRoadPart(testProjectId, roadPart, user)
      projectLinkDAO.create(projectLinks)
      projectService.fetchPreFillData(projectLinks.head.linkId, roadAddressProject.id) should be(Right(PreFillInfo(roadPart.roadNumber, roadPart.partNumber, "", RoadNameSource.UnknownSource, projectLinks.head.ely)))
    }
  }

  test("Test projectService.fetchPreFillData " +
       "When supplied a sequence of one valid RoadLink " +
       "Then return the correct pre-fill data for that link and it's correct name.") {
    runWithRollback {
      val roadPart = RoadPart(170,10)
      val user     = "test user"
      val roadAddressProject = Project(testProjectId, Incomplete, "preFill", user, DateTime.now(), user, DateTime.now().plusMonths(2), DateTime.now(), "", Seq(), Seq(), None, None, Set())

      runUpdateToDb(s"""INSERT INTO ROAD_NAME VALUES (nextval('ROAD_NAME_SEQ'), ${roadPart.roadNumber}, 'road name test', TIMESTAMP '2018-03-23 12:26:36.000000', null, TIMESTAMP '2018-03-23 12:26:36.000000', null, 'test user', TIMESTAMP '2018-03-23 12:26:36.000000')""")
      projectReservedPartDAO.reserveRoadPart(testProjectId, roadPart, user)
      val projectLinks = roadwayAddressMapper.getRoadAddressesByRoadway(roadwayDAO.fetchAllByRoadPart(roadPart)).map(toProjectLink(roadAddressProject))
      projectLinkDAO.create(projectLinks)
      projectService.fetchPreFillData(projectLinks.head.linkId, roadAddressProject.id) should be(Right(PreFillInfo(roadPart.roadNumber, roadPart.partNumber, "road name test", RoadNameSource.RoadAddressSource, projectLinks.head.ely)))
    }
  }

  test("Test projectService.fetchPreFillData() " +
       "When getting road name data from the Project Link Name table " +
       "Then return the correct info with road name pre filled, and the correct sources") {
    runWithRollback{
      val roadPart = RoadPart(170,10)
      val user     = "test user"
      val roadAddressProject = Project(testProjectId, Incomplete, "preFill", user, DateTime.now(), user, DateTime.now().plusMonths(2), DateTime.now(), "", Seq(), Seq(), None, None, Set())

      runUpdateToDb(s""" Insert into project_link_name values (nextval('viite_general_seq'), ${roadAddressProject.id}, ${roadPart.roadNumber}, 'TestRoadName_Project_Link')""")
      projectReservedPartDAO.reserveRoadPart(testProjectId, roadPart, user)
      val projectLinks = roadwayAddressMapper.getRoadAddressesByRoadway(roadwayDAO.fetchAllByRoadPart(roadPart)).map(toProjectLink(roadAddressProject))
      projectLinkDAO.create(projectLinks)
      projectService.fetchPreFillData(projectLinks.head.linkId, roadAddressProject.id) should be(Right(PreFillInfo(roadPart.roadNumber, roadPart.partNumber, "TestRoadName_Project_Link", RoadNameSource.ProjectLinkSource, projectLinks.head.ely)))
    }
  }

  test("Test projectService.fetchPreFillData() " +
       "When road name data exists both in Project Link Name table and Road_Name table " +
       "Then return the correct info with road name pre filled and the correct sources, in this case RoadAddressSource") {
    runWithRollback{

      val roadPart = RoadPart(170,10)
      val user     = "test user"

      val roadAddressProject = Project(testProjectId, Incomplete, "preFill", user, DateTime.now(), user, DateTime.now().plusMonths(2), DateTime.now(), "", Seq(), Seq(), None, None, Set())

      runUpdateToDb(s"""INSERT INTO ROAD_NAME VALUES (nextval('ROAD_NAME_SEQ'), ${roadPart.roadNumber}, 'road name test', TIMESTAMP '2018-03-23 12:26:36.000000', null, TIMESTAMP '2018-03-23 12:26:36.000000', null, 'test user', TIMESTAMP '2018-03-23 12:26:36.000000')""")
      runUpdateToDb(s"""INSERT INTO project_link_name values (nextval('viite_general_seq'), ${roadAddressProject.id}, ${roadPart.roadNumber}, 'TestRoadName_Project_Link')""")
      projectReservedPartDAO.reserveRoadPart(testProjectId, roadPart, user)

      val projectLinks = roadwayAddressMapper.getRoadAddressesByRoadway(roadwayDAO.fetchAllByRoadPart(roadPart)).map(toProjectLink(roadAddressProject))
      projectLinkDAO.create(projectLinks)
      projectService.fetchPreFillData(projectLinks.head.linkId, roadAddressProject.id) should be(Right(PreFillInfo(roadPart.roadNumber, roadPart.partNumber, "road name test", RoadNameSource.RoadAddressSource, projectLinks.head.ely)))
    }
  }

  test("Test createProjectLinks When project link is created for part not reserved Then should return error message") {
    runWithRollback {
      val rap = Project(0L, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("1901-01-01"), "TestUser", DateTime.parse("1901-01-01"), DateTime.now(), "Some additional info", Seq(), Seq(), None, elys = Set())
      val newLink = Seq(ProjectLink(-1000L, RoadPart(5, 206), Track.apply(99), Discontinuity.Continuous, AddrMRange(0L, 50L), AddrMRange(0L, 50L), None, None, None, 12345L.toString, 0.0, 43.1, SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP), Seq(Point(468.5, 0.5), Point(512.0, 0.0)), 0L, RoadAddressChangeType.Unknown, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, 43.1, 49704009, 1000570, 8L, reversed = false, None, 123456L, 12345L))
      val project = projectService.createRoadLinkProject(rap)
      when(mockRoadLinkService.getRoadLinksByLinkIds(any[Set[String]])).thenReturn(newLink.map(toRoadLink))
      val response = projectService.createProjectLinks(Seq(12345L.toString), project.id, RoadPart(5, 206), Track.Combined, Discontinuity.Continuous, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, 8L, "test", "road name")
      response("success").asInstanceOf[Boolean] should be(false)
      response("errorMessage").asInstanceOf[String] should be(RoadNotAvailableMessage)
    }
  }

  test("Test projectService.updateProjectLinks When applying the operation \"Numerointi\" to a road part that is ALREADY reserved in a different project Then return an error message") {
    runWithRollback {
      val rap1 = Project(0L, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("1963-01-01"), "TestUser", DateTime.parse("1963-01-01"), DateTime.now(), "Some additional info", Seq(), Seq(), None, elys = Set())
      val rap2 = Project(0L, ProjectState.apply(1), "TestProject2", "TestUser", DateTime.parse("2012-01-01"), "TestUser", DateTime.parse("2017-01-01"), DateTime.now(), "Some additional info", Seq(), Seq(), None, elys = Set())
      val addr1 = List(ProjectReservedPart(Sequences.nextViitePrimaryKeySeqValue, RoadPart(5, 207), Some(0L), Some(Discontinuity.Continuous), Some(8L), None, None, None, None))
      val addr2 = List(ProjectReservedPart(Sequences.nextViitePrimaryKeySeqValue, RoadPart(5, 206), Some(5L), Some(Discontinuity.Continuous), Some(8L), None, None, None, None))
      val project1 = projectService.createRoadLinkProject(rap1)

      mockForProject(project1.id, roadAddressServiceRealRoadwayAddressMapper.getRoadAddressWithRoadPart(RoadPart(5, 207)).map(toProjectLink(project1)))
      projectService.saveProject(project1.copy(reservedParts = addr1, elys = Set()))

      val project2 = projectService.createRoadLinkProject(rap2)
      mockForProject(project2.id, roadAddressServiceRealRoadwayAddressMapper.getRoadAddressWithRoadPart(RoadPart(5, 206)).map(toProjectLink(project2)))
      projectService.saveProject(project2.copy(reservedParts = addr2, elys = Set()))

      val response = projectService.updateProjectLinks(project1.id, Set(), projectLinkDAO.fetchProjectLinks(project1.id).map(_.linkId), RoadAddressChangeType.Renumeration, "TestUser", RoadPart(5, 206), 0, None, AdministrativeClass.State.value, Discontinuity.Continuous.value, Some(8))
      response.get should be("Antamasi tienumero ja tieosanumero ovat jo käytössä. Tarkista syöttämäsi tiedot.")
    }
  }

  test("Test projectService.updateProjectLinks When applying the operation \"Numerointi\" to a road part that is ALREADY reserved in a different project Then return an error message" +
    "renumber a project link to a road part not reserved with end date null") {
    runWithRollback {
      val rap1 = Project(0L, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("1963-01-01"), "TestUser", DateTime.parse("1963-01-01"), DateTime.now(), "Some additional info", Seq(), Seq(), None, elys = Set())
      val addr1 = List(ProjectReservedPart(Sequences.nextViitePrimaryKeySeqValue, RoadPart(5, 207), Some(0L), Some(Discontinuity.Continuous), Some(8L), None, None, None, None))
      val project1 = projectService.createRoadLinkProject(rap1)
      mockForProject(project1.id, roadAddressServiceRealRoadwayAddressMapper.getRoadAddressWithRoadPart(RoadPart(5, 207)).map(toProjectLink(project1)))
      projectService.saveProject(project1.copy(reservedParts = addr1, elys = Set()))
      val response = projectService.updateProjectLinks(project1.id, Set(), projectLinkDAO.fetchProjectLinks(project1.id).map(_.linkId), RoadAddressChangeType.Renumeration, "TestUser", RoadPart(5, 203), 0, None, AdministrativeClass.State.value, Discontinuity.Continuous.value, Some(8))
      response.get should be("Antamasi tienumero ja tieosanumero ovat jo käytössä. Tarkista syöttämäsi tiedot.")
    }
  }

  test("Test projectService.updateProjectLinks When applying the operation \"Numerointi\" to a road part that already has some other action applied Then return an error message") {
    runWithRollback {
      val rap1 = Project(0L, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("1963-01-01"), "TestUser", DateTime.parse("1963-01-01"), DateTime.now(), "Some additional info", Seq(), Seq(), None, elys = Set())
      val addr1 = List(ProjectReservedPart(Sequences.nextViitePrimaryKeySeqValue, RoadPart(5, 207), Some(0L), Some(Discontinuity.Continuous), Some(8L), None, None, None, None))
      val project1 = projectService.createRoadLinkProject(rap1)
      mockForProject(project1.id, roadAddressServiceRealRoadwayAddressMapper.getRoadAddressWithRoadPart(RoadPart(5, 207)).map(toProjectLink(project1)))
      projectService.saveProject(project1.copy(reservedParts = addr1, elys = Set()))
      projectService.updateProjectLinks(project1.id, Set(), projectLinkDAO.fetchProjectLinks(project1.id).map(_.linkId), RoadAddressChangeType.Termination, "TestUser", RoadPart(5, 207), 0, None, AdministrativeClass.State.value, Discontinuity.Continuous.value, Some(8))
      val response = projectService.updateProjectLinks(project1.id, Set(), projectLinkDAO.fetchProjectLinks(project1.id).map(_.linkId), RoadAddressChangeType.Renumeration, "TestUser", RoadPart(5, 308), 0, None, AdministrativeClass.State.value, Discontinuity.Continuous.value, Some(8))
      response.get should be(ErrorOtherActionWithNumbering)
    }
  }

  test("Test projectLinkDAO.getProjectLinks() When after the \"Numerointi\" operation on project links of a newly created project Then the last returned link should have a discontinuity of \"EndOfRoad\", all the others should be \"Continuous\" ") {
    runWithRollback {
      val rap1 = Project(0L, ProjectState.apply(1), "TestProject", "TestUser", DateTime.now(), "TestUser", DateTime.now().plusDays(1), DateTime.now(), "Some additional info", Seq(), Seq(), None, elys = Set())
      val addr1 = List(ProjectReservedPart(Sequences.nextViitePrimaryKeySeqValue, RoadPart(5, 207), Some(0L), Some(Discontinuity.Continuous), Some(8L), None, None, None, None))
      val project1 = projectService.createRoadLinkProject(rap1)
      mockForProject(project1.id, roadAddressServiceRealRoadwayAddressMapper.getRoadAddressWithRoadPart(RoadPart(5, 207)).map(toProjectLink(project1)))
      projectService.saveProject(project1.copy(reservedParts = addr1, elys = Set()))
      val fetchedProjectLinks = projectLinkDAO.fetchProjectLinks(project1.id)
      projectService.updateProjectLinks(project1.id, Set(fetchedProjectLinks.maxBy(_.addrMRange.end).id), fetchedProjectLinks.map(_.linkId), RoadAddressChangeType.Renumeration, "TestUser", RoadPart(6, 207), 0, None, AdministrativeClass.State.value, Discontinuity.EndOfRoad.value, Some(8))

      //Descending order by end address
      val projectLinks = projectLinkDAO.fetchProjectLinks(project1.id).sortBy(-_.addrMRange.end)
      projectLinks.tail.forall(_.discontinuity == Discontinuity.Continuous) should be(true)
      projectLinks.head.discontinuity should be(Discontinuity.EndOfRoad)
    }
  }

  test("Test revertLinksByRoadPart When new roads have name Then the revert should remove the road name") {
    runWithRollback {
      val testRoad: (RoadPart, String) = {
        (RoadPart(99999, 1), "Test name")
      }

      val (project, links) = util.setUpProjectWithLinks(RoadAddressChangeType.New, Seq(0L, 10L, 20L), roads = Seq(testRoad), discontinuity = Discontinuity.Continuous)
      val roadLinks = links.map(toRoadLink)
      ProjectLinkNameDAO.get(99999L, project.id).get.roadName should be("Test name")
      val linksToRevert = links.map(l => {
        LinkToRevert(l.id, l.linkId, l.status.value, l.geometry)
      })
      projectService.revertLinksByRoadPart(project.id, RoadPart(99999, 1), linksToRevert, "Test User")
      ProjectLinkNameDAO.get(99999L, project.id) should be(None)
    }
  }

  test("Test projectService.saveProject() When updating a project to remove ALL it's reserved roads Then the project should lose it's associated road names.") {
    runWithRollback {
      val testRoad: (RoadPart, String) = {
        (RoadPart(99999, 1), "Test name")
      }
      val (project, _) = util.setUpProjectWithLinks(RoadAddressChangeType.Transfer, Seq(0L, 10L, 20L), roads = Seq(testRoad), discontinuity = Discontinuity.Continuous)
      ProjectLinkNameDAO.get(99999L, project.id).get.roadName should be("Test name")
      projectService.saveProject(project.copy(reservedParts = Seq(), elys = Set()))
      ProjectLinkNameDAO.get(99999L, project.id) should be(None)
    }
  }

  test("Test projectService.deleteProject() When deleting a project Then if no other project makes use of a road name then said name should be removed as well.") {
    runWithRollback {
      val testRoad: (RoadPart, String) = {
        (RoadPart(99999, 1), "Test name")
      }
      val (project, _) = util.setUpProjectWithLinks(RoadAddressChangeType.Transfer, Seq(0L, 10L, 20L), roads = Seq(testRoad), discontinuity = Discontinuity.Continuous)
      ProjectLinkNameDAO.get(99999L, project.id).get.roadName should be("Test name")
      projectService.deleteProject(project.id)
      ProjectLinkNameDAO.get(99999L, project.id) should be(None)
    }
  }

  test("Test revertLinks When road exists in another project Then the new road name should not be removed") {
    runWithRollback {
      val testRoads: Seq[(RoadPart, String)] = Seq((RoadPart(99999, 1), "Test name"), (RoadPart(99999, 2), "Test name"))
      val (project, links) = util.setUpProjectWithLinks(RoadAddressChangeType.Transfer, Seq(0L, 10L, 20L), roads = testRoads, discontinuity = Discontinuity.Continuous)
      ProjectLinkNameDAO.get(99999L, project.id).get.roadName should be("Test name")
      val linksToRevert = links.map(l => {
        LinkToRevert(l.id, l.linkId, l.status.value, l.geometry)
      })
      val roadLinks = links.map(toRoadLink)
      when(mockRoadLinkService.getCurrentAndComplementaryRoadLinks(any[Set[String]])).thenReturn(roadLinks)
      projectService.revertLinksByRoadPart(project.id, RoadPart(99999, 1), linksToRevert, "Test User")
      projectLinkDAO.fetchProjectLinks(project.id).count(_.roadPart == RoadPart(99999, 2)) should be(2)
      ProjectLinkNameDAO.get(99999L, project.id).get.roadName should be("Test name")
    }
  }

  test("Test revertLinks When road name exists Then the revert should put the original name in the project link name if no other exists in project") {
    runWithRollback {
      val testRoad: (RoadPart, String) = {
        (RoadPart(99999, 1), "new name")
      }
      val (project, links) = util.setUpProjectWithLinks(RoadAddressChangeType.Transfer, Seq(0L, 10L, 20L), roads = Seq(testRoad), discontinuity = Discontinuity.Continuous)
      ProjectLinkNameDAO.get(99999L, project.id).get.roadName should be("new name")

      val roadLinks = links.map(toRoadLink)
      runUpdateToDb("""INSERT INTO ROAD_NAME VALUES (nextval('ROAD_NAME_SEQ'), 99999, 'test name', current_date, null, current_date, null, 'test user', current_date)""")
      val linksToRevert = links.map(l => {
        LinkToRevert(l.id, l.linkId, l.status.value, l.geometry)
      })
      when(mockRoadLinkService.getCurrentAndComplementaryRoadLinks(any[Set[String]])).thenReturn(roadLinks)
      projectService.revertLinksByRoadPart(project.id, RoadPart(99999, 1), linksToRevert, "Test User")
      ProjectLinkNameDAO.get(99999L, project.id).get.roadName should be("test name")
    }
  }

  test("Test projectService.updateProjectLinks() When new user given addressMValues, even on Left/Right tracks Then all links should keep continuous and incremented address values (calibration ones included).") {
    /**
      * This test checks:
      * 1.result of addressMValues for new given address value for one Track.Combined link
      * 2.result of MValues (segment and address) in recalculated calibration points for new given address value for one link
      * 3.result of addressMValues for yet another new given address value in other link, being that link Track.LeftSide or Track.RightSide
      * 4.result of MValues (segment and address) in recalculated calibration points for second new given address value for Track.LeftSide or Track.RightSide link
      */
    val coeff = 1.0

    def liesInBetween(measure: Double, interval: (Double, Double)): Boolean = {
      measure >= interval._1 && measure <= interval._2
    }

    runWithRollback {
      /**
        * Illustrative picture
        *
        * |--Left--||--Left--|
        * |--combined--|                    |--combined--|
        * |-------Right------|
        */

      /**
        * Test data
        */

      val rap = Project(0L, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("1901-01-01"), "TestUser", DateTime.parse("1901-01-01"), DateTime.now(), "Some additional info", Seq(), Seq(), None, elys = Set())

      val pl1 = ProjectLink(-1000L, RoadPart(9999, 1), Track.apply(0), Discontinuity.Continuous, AddrMRange(0L,  0L), AddrMRange(0L,  0L), None, None, None, 12345L.toString, 0.0, 0.0, SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP), Seq(Point(10.0, 10.0), Point(20.0, 10.0)), 0L, RoadAddressChangeType.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(Seq(Point(10.0, 10.0), Point(20.0, 10.0))), 0L, 0, 0, reversed = false, None, 86400L)
      val pl2 = ProjectLink(-1000L, RoadPart(9999, 1), Track.apply(2), Discontinuity.Continuous, AddrMRange(0L,  0L), AddrMRange(0L,  0L), None, None, None, 12346L.toString, 0.0, 0.0, SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP), Seq(Point(20.0, 10.0), Point(30.0, 15.0)), 0L, RoadAddressChangeType.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(Seq(Point(20.0, 10.0), Point(30.0, 15.0))), 0L, 0, 0, reversed = false, None, 86400L)
      val pl3 = ProjectLink(-1000L, RoadPart(9999, 1), Track.apply(2), Discontinuity.Continuous, AddrMRange(0L,  0L), AddrMRange(0L,  0L), None, None, None, 12347L.toString, 0.0, 0.0, SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP), Seq(Point(30.0, 15.0), Point(45.0, 10.0)), 0L, RoadAddressChangeType.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(Seq(Point(30.0, 15.0), Point(45.0, 10.0))), 0L, 0, 0, reversed = false, None, 86400L)
      val pl4 = ProjectLink(-1000L, RoadPart(9999, 1), Track.apply(1), Discontinuity.Continuous, AddrMRange(0L,  0L), AddrMRange(0L,  0L), None, None, None, 12348L.toString, 0.0, 0.0, SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP), Seq(Point(20.0, 10.0), Point(25.0, 5.0), Point(45.0, 10.0)), 0L, RoadAddressChangeType.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(Seq(Point(20.0, 10.0), Point(25.0, 5.0), Point(45.0, 10.0))), 0L, 0, 0, reversed = false, None, 86400L)
      val pl5 = ProjectLink(-1000L, RoadPart(9999, 1), Track.apply(0), Discontinuity.Continuous, AddrMRange(0L,  0L), AddrMRange(0L,  0L), None, None, None, 12349L.toString, 0.0, 0.0, SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP), Seq(Point(45.0, 10.0), Point(60.0, 10.0)), 0L, RoadAddressChangeType.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(Seq(Point(45.0, 10.0), Point(60.0, 10.0))), 0L, 0, 0, reversed = false, None, 86400L)

      val project = projectService.createRoadLinkProject(rap)
      when(mockRoadLinkService.getRoadLinksByLinkIds(any[Set[String]])).thenReturn(Seq(pl1).map(toRoadLink))
      projectService.createProjectLinks(Seq(12345L.toString), project.id, RoadPart(9999, 1), Track.Combined, Discontinuity.Continuous, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, 8L, "test", "road name")
      when(mockRoadLinkService.getRoadLinksByLinkIds(any[Set[String]])).thenReturn(Seq(pl4).map(toRoadLink))
      projectService.createProjectLinks(Seq(12348L.toString), project.id, RoadPart(9999, 1), Track.RightSide, Discontinuity.Continuous, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, 8L, "test", "road name")
      when(mockRoadLinkService.getRoadLinksByLinkIds(any[Set[String]])).thenReturn(Seq(pl2, pl3).map(toRoadLink))
      projectService.createProjectLinks(Seq(12346L.toString, 12347L.toString), project.id, RoadPart(9999, 1), Track.LeftSide, Discontinuity.Continuous, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, 8L, "test", "road name")
      when(mockRoadLinkService.getRoadLinksByLinkIds(any[Set[String]])).thenReturn(Seq(pl5).map(toRoadLink))
      projectService.createProjectLinks(Seq(12349L.toString), project.id, RoadPart(9999, 1), Track.Combined, Discontinuity.Continuous, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, 8L, "test", "road name")
      val links = projectLinkDAO.fetchProjectLinks(project.id).sortBy(_.addrMRange.start)

      links.filterNot(_.track == Track.RightSide).sortBy(_.addrMRange.end).scanLeft(0.0) { case (m, pl) =>
        m should be(pl.addrMRange.start)
        pl.addrMRange.end
      }
      links.filterNot(_.track == Track.LeftSide).sortBy(_.addrMRange.end).scanLeft(0.0) { case (m, pl) =>
        m should be(pl.addrMRange.start)
        pl.addrMRange.end
      }

      links.sortBy(_.addrMRange.end).scanLeft(0.0) { case (m, pl) =>
        if (pl.calibrationPoints._1.nonEmpty) {
          pl.calibrationPoints._1.get.addressMValue should be(pl.addrMRange.start)
          pl.calibrationPoints._1.get.segmentMValue should be(pl.endMValue - pl.startMValue)
        }
        if (pl.calibrationPoints._2.nonEmpty) {
          pl.calibrationPoints._2.get.addressMValue should be(pl.addrMRange.end)
          pl.calibrationPoints._2.get.addressMValue should be(pl.endMValue)
        }
        0.0 //any double val, needed for expected type value in recursive scan
      }


      val linkidToIncrement = pl1.linkId
      val idsToIncrement = links.filter(_.linkId == linkidToIncrement).head.id
      val valueToIncrement = 2.0
      val newEndAddressValue = Seq(links.filter(_.linkId == linkidToIncrement).head.addrMRange.end.toInt, valueToIncrement.toInt).sum
      projectService.updateProjectLinks(project.id, Set(idsToIncrement), Seq(), RoadAddressChangeType.New, "TestUserTwo", RoadPart(9999, 1), 0, Some(newEndAddressValue), 1L, 5) should be(None)
      val linksAfterGivenAddrMValue = projectLinkDAO.fetchProjectLinks(project.id)

      /**
        * Test 1.
        */
      linksAfterGivenAddrMValue.filterNot(_.track == Track.RightSide).sortBy(_.addrMRange.end).scanLeft(0.0) { case (m, pl) =>
        m should be(pl.addrMRange.start)
        pl.addrMRange.end
      }
      linksAfterGivenAddrMValue.filterNot(_.track == Track.LeftSide).sortBy(_.addrMRange.end).scanLeft(0.0) { case (m, pl) =>
        m should be(pl.addrMRange.start)
        pl.addrMRange.end
      }

      /**
        * Test 2.
        */
      linksAfterGivenAddrMValue.sortBy(_.addrMRange.end).scanLeft(0.0) { case (m, pl) =>
        if (pl.calibrationPoints._1.nonEmpty) {
          pl.calibrationPoints._1.get.addressMValue should be(pl.addrMRange.start)
          pl.calibrationPoints._1.get.segmentMValue should be(pl.endMValue - pl.startMValue)
        }
        if (pl.calibrationPoints._2.nonEmpty) {
          pl.calibrationPoints._2.get.addressMValue should be(pl.addrMRange.end)
          pl.calibrationPoints._2.get.addressMValue should be(pl.endMValue)
        }
        0.0 //any double val, needed for expected type value in recursive scan
      }

      //only link and links after linkidToIncrement should be extended
      val extendedLink = links.filter(_.linkId == linkidToIncrement).head
      val linksBefore = links.filter(_.addrMRange.end >= extendedLink.addrMRange.end).sortBy(_.addrMRange.end)
      val linksAfter = linksAfterGivenAddrMValue.filter(_.addrMRange.end >= extendedLink.addrMRange.end).sortBy(_.addrMRange.end)
      linksBefore.zip(linksAfter).foreach { case (st, en) =>
        liesInBetween(en.addrMRange.end, (st.addrMRange.end + valueToIncrement - coeff, st.addrMRange.end + valueToIncrement + coeff))
      }


      val secondLinkidToIncrement = pl4.linkId
      val secondIdToIncrement = linksAfterGivenAddrMValue.filter(_.linkId == secondLinkidToIncrement).head.id
      val secondValueToIncrement = 3.0
      val secondNewEndAddressValue = Seq(links.filter(_.linkId == secondLinkidToIncrement).head.addrMRange.end.toInt, secondValueToIncrement.toInt).sum
      projectService.updateProjectLinks(project.id, Set(secondIdToIncrement), Seq(), RoadAddressChangeType.New, "TestUserTwo", RoadPart(9999, 1), 1, Some(secondNewEndAddressValue), 1L, 5) should be(None)
      val linksAfterSecondGivenAddrMValue = projectLinkDAO.fetchProjectLinks(project.id)

      /**
        * Test 3.
        */
      linksAfterSecondGivenAddrMValue.filterNot(_.track == Track.RightSide).sortBy(_.addrMRange.end).scanLeft(0.0) { case (m, pl) =>
        m should be(pl.addrMRange.start)
        pl.addrMRange.end
      }
      linksAfterSecondGivenAddrMValue.filterNot(_.track == Track.LeftSide).sortBy(_.addrMRange.end).scanLeft(0.0) { case (m, pl) =>
        m should be(pl.addrMRange.start)
        pl.addrMRange.end
      }

      /**
        * Test 4.
        */
      linksAfterSecondGivenAddrMValue.sortBy(_.addrMRange.end).scanLeft(0.0) { case (m, pl) =>
        if (pl.calibrationPoints._1.nonEmpty) {
          pl.calibrationPoints._1.get.addressMValue should be(pl.addrMRange.start)
          pl.calibrationPoints._1.get.segmentMValue should be(pl.endMValue - pl.startMValue)
        }
        if (pl.calibrationPoints._2.nonEmpty) {
          pl.calibrationPoints._2.get.addressMValue should be(pl.addrMRange.end)
          pl.calibrationPoints._2.get.addressMValue should be(pl.endMValue)
        }
        0.0 //any double val, needed for expected type value in recursive scan
      }

      //only link and links after secondLinkidToIncrement should be extended
      val secondExtendedLink = linksAfterGivenAddrMValue.filter(_.linkId == secondLinkidToIncrement).head
      val secondLinksBefore = linksAfterGivenAddrMValue.filter(_.addrMRange.end >= secondExtendedLink.addrMRange.end).sortBy(_.addrMRange.end)
      val secondLinksAfter = linksAfterSecondGivenAddrMValue.filter(_.addrMRange.end >= secondExtendedLink.addrMRange.end).sortBy(_.addrMRange.end)
      secondLinksBefore.zip(secondLinksAfter).foreach { case (st, en) =>
        liesInBetween(en.addrMRange.end, (st.addrMRange.end + valueToIncrement + secondValueToIncrement - coeff, st.addrMRange.end + valueToIncrement + secondValueToIncrement + coeff))
      }

    }
  }

  test("Test projectService.updateProjectLinks() and projectService.changeDirection() When Re-Reversing direction of project links Then all links should revert to the previous given addressMValues.") {
    /**
      * This test checks:
      * 1.result of addressMValues for new given address value for one Track.Combined link
      * 2.result of reversing direction
      */
    val coeff = 1.0

//    def liesInBetween(measure: Double, interval: (Double, Double)): Boolean = {
//      measure >= interval._1 && measure <= interval._2
//    }

    runWithRollback {
      /**
        * Illustrative picture
        *
        * |--Left--||--Left--|
        * |--combined--|                    |--combined--|
        * |-------Right------|
        */

      /**
        * Test data
        */

      val rap = Project(0L, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("1901-01-01"), "TestUser", DateTime.parse("1901-01-01"), DateTime.now(), "Some additional info", Seq(), Seq(), None, elys = Set())

      val pl1 = ProjectLink(-1000L, RoadPart(9999, 1), Track.apply(0), Discontinuity.Continuous, AddrMRange(0L,  0L), AddrMRange(0L,  0L), None, None, None, 12345L.toString, 0.0, 0.0, SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP), Seq(Point(10.0, 10.0), Point(20.0, 10.0)), 0L, RoadAddressChangeType.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(Seq(Point(10.0, 10.0), Point(20.0, 10.0))), 0L, 0, 0, reversed = false, None, 86400L)
      val pl2 = ProjectLink(-1000L, RoadPart(9999, 1), Track.apply(2), Discontinuity.Continuous, AddrMRange(0L,  0L), AddrMRange(0L,  0L), None, None, None, 12346L.toString, 0.0, 0.0, SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP), Seq(Point(20.0, 10.0), Point(30.0, 15.0)), 0L, RoadAddressChangeType.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(Seq(Point(20.0, 10.0), Point(30.0, 15.0))), 0L, 0, 0, reversed = false, None, 86400L)
      val pl3 = ProjectLink(-1000L, RoadPart(9999, 1), Track.apply(2), Discontinuity.Continuous, AddrMRange(0L,  0L), AddrMRange(0L,  0L), None, None, None, 12347L.toString, 0.0, 0.0, SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP), Seq(Point(30.0, 15.0), Point(45.0, 10.0)), 0L, RoadAddressChangeType.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(Seq(Point(30.0, 15.0), Point(45.0, 10.0))), 0L, 0, 0, reversed = false, None, 86400L)
      val pl4 = ProjectLink(-1000L, RoadPart(9999, 1), Track.apply(1), Discontinuity.Continuous, AddrMRange(0L,  0L), AddrMRange(0L,  0L), None, None, None, 12348L.toString, 0.0, 0.0, SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP), Seq(Point(20.0, 10.0), Point(25.0, 5.0), Point(45.0, 10.0)), 0L, RoadAddressChangeType.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(Seq(Point(20.0, 10.0), Point(25.0, 5.0), Point(45.0, 10.0))), 0L, 0, 0, reversed = false, None, 86400L)
      val pl5 = ProjectLink(-1000L, RoadPart(9999, 1), Track.apply(0), Discontinuity.Continuous, AddrMRange(0L,  0L), AddrMRange(0L,  0L), None, None, None, 12349L.toString, 0.0, 0.0, SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP), Seq(Point(45.0, 10.0), Point(60.0, 10.0)), 0L, RoadAddressChangeType.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(Seq(Point(45.0, 10.0), Point(60.0, 10.0))), 0L, 0, 0, reversed = false, None, 86400L)

      val project = projectService.createRoadLinkProject(rap)
      when(mockRoadLinkService.getRoadLinksByLinkIds(any[Set[String]])).thenReturn(Seq(pl1).map(toRoadLink))
      projectService.createProjectLinks(Seq(12345L.toString), project.id, RoadPart(9999, 1), Track.Combined, Discontinuity.Continuous, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, 8L, "test", "road name")
      when(mockRoadLinkService.getRoadLinksByLinkIds(any[Set[String]])).thenReturn(Seq(pl4).map(toRoadLink))
      projectService.createProjectLinks(Seq(12348L.toString), project.id, RoadPart(9999, 1), Track.RightSide, Discontinuity.Continuous, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, 8L, "test", "road name")
      when(mockRoadLinkService.getRoadLinksByLinkIds(any[Set[String]])).thenReturn(Seq(pl2, pl3).map(toRoadLink))
      projectService.createProjectLinks(Seq(12346L.toString, 12347L.toString), project.id, RoadPart(9999, 1), Track.LeftSide, Discontinuity.Continuous, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, 8L, "test", "road name")
      when(mockRoadLinkService.getRoadLinksByLinkIds(any[Set[String]])).thenReturn(Seq(pl5).map(toRoadLink))
      projectService.createProjectLinks(Seq(12349L.toString), project.id, RoadPart(9999, 1), Track.Combined, Discontinuity.Continuous, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, 8L, "test", "road name")
      val links = projectLinkDAO.fetchProjectLinks(project.id).sortBy(_.addrMRange.start)

      val linkidToIncrement = pl1.linkId
      val idsToIncrement = links.filter(_.linkId == linkidToIncrement).head.id
      val valueToIncrement = 2.0
      val newEndAddressValue = Seq(links.filter(_.linkId == linkidToIncrement).head.addrMRange.end.toInt, valueToIncrement.toInt).sum
      projectService.updateProjectLinks(project.id, Set(idsToIncrement), Seq(linkidToIncrement), RoadAddressChangeType.New, "TestUserTwo", RoadPart(9999, 1), 0, Some(newEndAddressValue), 1L, 5) should be(None)

      val links_ = projectLinkDAO.fetchProjectLinks(project.id).sortBy(_.addrMRange.start)
      val linksAfterGivenAddrMValue = ProjectSectionCalculator.assignAddrMValues(links_).sortBy(_.addrMRange.end)

      //only link and links after linkidToIncrement should be extended
      val extendedLink = links.filter(_.linkId == linkidToIncrement).head
      val linksBefore = links.filter(_.addrMRange.end >= extendedLink.addrMRange.end).sortBy(_.addrMRange.end)
      val linksAfter = linksAfterGivenAddrMValue.filter(_.addrMRange.end >= extendedLink.addrMRange.end).sortBy(_.addrMRange.end)

      projectService.changeDirection(project.id, RoadPart(9999, 1), Seq(LinkToRevert(pl1.id, pl1.linkId, pl1.status.value, pl1.geometry)), ProjectCoordinates(0, 0, 0), "TestUserTwo")
      val linksAfterFirstReverse = ProjectSectionCalculator.assignAddrMValues(links_).sortBy(_.addrMRange.end)
      projectService.changeDirection(project.id, RoadPart(9999, 1), Seq(LinkToRevert(pl1.id, pl1.linkId, pl1.status.value, pl1.geometry)), ProjectCoordinates(0, 0, 0), "TestUserTwo")
      val linksAfterSecondReverse = ProjectSectionCalculator.assignAddrMValues(links_).sortBy(_.addrMRange.end)

      linksAfterGivenAddrMValue.sortBy(pl => (pl.addrMRange.end, pl.addrMRange.start)).zip(linksAfterSecondReverse.sortBy(pl => (pl.addrMRange.end, pl.addrMRange.start))).foreach { case (st, en) =>
        (st.addrMRange.start, st.addrMRange.end) should be(en.addrMRange.start, en.addrMRange.end)
        (st.startMValue, st.endMValue) should be(en.startMValue, en.endMValue)
      }
    }
  }

  test("Test projectService.changeDirection() When after the creation of valid project links on a project Then the discontinuity of road addresses that are not the same road number and road part number should not be altered.") {
    runWithRollback {

      val rap = Project(0L, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("1901-01-01"), "TestUser", DateTime.parse("1901-01-01"), DateTime.now(), "Some additional info", Seq(), Seq(), None, elys = Set())

      val pl1 = ProjectLink(-1000L, RoadPart(9999, 1), Track.apply(0), Discontinuity.Continuous, AddrMRange(0L,  0L), AddrMRange(0L,  0L), None, None, None, 12345L.toString, 0.0, 0.0, SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP), Seq(Point(10.0, 10.0), Point(20.0, 10.0)), 0L, RoadAddressChangeType.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(Seq(Point(10.0, 10.0), Point(20.0, 10.0))), 0L, 0, 0, reversed = false, None, 86400L)
      val pl2 = ProjectLink(-1000L, RoadPart(9999, 1), Track.apply(2), Discontinuity.Continuous, AddrMRange(0L,  0L), AddrMRange(0L,  0L), None, None, None, 12346L.toString, 0.0, 0.0, SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP), Seq(Point(20.0, 10.0), Point(30.0, 15.0)), 0L, RoadAddressChangeType.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(Seq(Point(20.0, 10.0), Point(30.0, 15.0))), 0L, 0, 0, reversed = false, None, 86400L)
      val pl3 = ProjectLink(-1000L, RoadPart(9999, 1), Track.apply(2), Discontinuity.Continuous, AddrMRange(0L,  0L), AddrMRange(0L,  0L), None, None, None, 12347L.toString, 0.0, 0.0, SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP), Seq(Point(30.0, 15.0), Point(45.0, 10.0)), 0L, RoadAddressChangeType.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(Seq(Point(30.0, 15.0), Point(45.0, 10.0))), 0L, 0, 0, reversed = false, None, 86400L)
      val pl4 = ProjectLink(-1000L, RoadPart(9999, 1), Track.apply(1), Discontinuity.Continuous, AddrMRange(0L,  0L), AddrMRange(0L,  0L), None, None, None, 12348L.toString, 0.0, 0.0, SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP), Seq(Point(20.0, 10.0), Point(25.0, 5.0), Point(45.0, 10.0)), 0L, RoadAddressChangeType.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(Seq(Point(20.0, 10.0), Point(25.0, 5.0), Point(45.0, 10.0))), 0L, 0, 0, reversed = false, None, 86400L)
      val pl5 = ProjectLink(-1000L, RoadPart(9998, 1), Track.apply(0), Discontinuity.EndOfRoad,  AddrMRange(0L,  0L), AddrMRange(0L,  0L), None, None, None, 12349L.toString, 0.0, 0.0, SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP), Seq(Point(45.0, 10.0), Point(60.0, 10.0)), 0L, RoadAddressChangeType.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(Seq(Point(45.0, 10.0), Point(60.0, 10.0))), 0L, 0, 0, reversed = false, None, 86400L)

      val project = projectService.createRoadLinkProject(rap)
      when(mockRoadLinkService.getRoadLinksByLinkIds(any[Set[String]])).thenReturn(Seq(pl1).map(toRoadLink))
      projectService.createProjectLinks(Seq(12345L.toString), project.id, RoadPart(9999, 1), Track.Combined, Discontinuity.Continuous, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, 8L, "test", "road name")
      when(mockRoadLinkService.getRoadLinksByLinkIds(any[Set[String]])).thenReturn(Seq(pl4).map(toRoadLink))
      projectService.createProjectLinks(Seq(12348L.toString), project.id, RoadPart(9999, 1), Track.RightSide, Discontinuity.Continuous, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, 8L, "test", "road name")
      when(mockRoadLinkService.getRoadLinksByLinkIds(any[Set[String]])).thenReturn(Seq(pl2, pl3).map(toRoadLink))
      projectService.createProjectLinks(Seq(12346L.toString, 12347L.toString), project.id, RoadPart(9999, 1), Track.LeftSide, Discontinuity.Continuous, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, 8L, "test", "road name")
      when(mockRoadLinkService.getRoadLinksByLinkIds(any[Set[String]])).thenReturn(Seq(pl5).map(toRoadLink))
      projectService.createProjectLinks(Seq(12349L.toString), project.id, RoadPart(9998, 1), Track.Combined, Discontinuity.EndOfRoad, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, 8L, "test", "road name")
      val linksBeforeChange = projectLinkDAO.fetchProjectLinks(project.id).sortBy(_.addrMRange.start)
      val linkBC = linksBeforeChange.filter(_.roadPart == RoadPart(9998, 1))
      linkBC.size should be(1)
      linkBC.head.discontinuity.value should be(Discontinuity.EndOfRoad.value)
      projectService.changeDirection(project.id, RoadPart(9999, 1), Seq(LinkToRevert(pl1.id, pl1.linkId, pl1.status.value, pl1.geometry)), ProjectCoordinates(0, 0, 0), "TestUserTwo")
      val linksAfterChange = projectLinkDAO.fetchProjectLinks(project.id).sortBy(_.addrMRange.start)
      val linkAC = linksAfterChange.filter(_.roadPart == RoadPart(9998, 1))
      linkAC.size should be(1)
      linkAC.head.discontinuity.value should be(linkBC.head.discontinuity.value)
    }
  }

  test("Test defaultSectionCalculatorStrategy.assignAddrMValues() " +
       "When a (complex) road has three roundabouts with two tracks and the road starts from south east toward north" +
       "Then assignAddrMValues() should calculate succesfully. No changes are expected to the projectlink values. Addressses issues with ProjectLink ordering and discontinuities. Reversed case included.") {
    runWithRollback {
      val projectId = Sequences.nextViiteProjectId
      val roadPart = RoadPart(42810, 1)
      val createdBy = "test"
      val roadName = None
      val projectLinks = Seq(
        ProjectLink(1000,roadPart,Track.RightSide,Discontinuity.Continuous,        AddrMRange(  0,  81),AddrMRange(  0,  81),None,None,Some(createdBy),4107344.toString, 0.0,  76.993,SideCode.TowardsDigitizing,(RoadAddressCP,NoCP         ),(RoadAddressCP,RoadAddressCP),List(Point(221253.0,6827429.0,0.0), Point(221226.0,6827501.0,0.0)),projectId,RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, LinkGeomSource.FrozenLinkInterface, 76.993,63197,0L,2,reversed = false,None,                  1551489610000L, 52347051,roadName,None,None,None,None,None),
        ProjectLink(1001,roadPart,Track.LeftSide, Discontinuity.MinorDiscontinuity,AddrMRange(  0,  81),AddrMRange(  0,  81),None,None,Some(createdBy),4107372.toString, 0.0,  73.976,SideCode.TowardsDigitizing,(RoadAddressCP,RoadAddressCP),(RoadAddressCP,RoadAddressCP),List(Point(221242.0,6827426.0,0.0), Point(221210.0,6827491.0,0.0)),projectId,RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, LinkGeomSource.FrozenLinkInterface, 73.976,62737,0L,2,reversed = false,None,                  1551489610000L, 52347054,roadName,None,None,None,None,None),
        ProjectLink(1002,roadPart,Track.LeftSide, Discontinuity.Continuous,        AddrMRange( 81,  92),AddrMRange( 81,  92),None,None,Some(createdBy),4107358.toString, 0.0,   9.759,SideCode.TowardsDigitizing,(RoadAddressCP,NoCP         ),(RoadAddressCP,NoCP         ),List(Point(221213.0,6827520.0,0.0), Point(221218.0,6827528.0,0.0)),projectId,RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, LinkGeomSource.FrozenLinkInterface,  9.759,80870,0L,2,reversed = false,None,                  1551489610000L,203081345,roadName,None,None,None,None,None),
        ProjectLink(1003,roadPart,Track.RightSide,Discontinuity.Continuous,        AddrMRange( 81, 102),AddrMRange( 81, 102),None,None,Some(createdBy),4107356.toString, 0.0,  19.586,SideCode.TowardsDigitizing,(NoCP,         NoCP         ),(RoadAddressCP,NoCP         ),List(Point(221226.0,6827501.0,0.0), Point(221231.0,6827520.0,0.0)),projectId,RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, LinkGeomSource.FrozenLinkInterface, 19.586,80639,0L,2,reversed = false,None,                  1551489610000L,203081355,roadName,None,None,None,None,None),
        ProjectLink(1004,roadPart,Track.LeftSide, Discontinuity.Continuous,        AddrMRange( 92, 125),AddrMRange( 92, 125),None,None,Some(createdBy),4107352.toString, 0.0,  29.251,SideCode.TowardsDigitizing,(NoCP,         NoCP         ),(NoCP,         NoCP         ),List(Point(221218.0,6827528.0,0.0), Point(221228.0,6827555.0,0.0)),projectId,RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, LinkGeomSource.FrozenLinkInterface, 29.251,80870,0L,2,reversed = false,None,                  1551489610000L,203081345,roadName,None,None,None,None,None),
        ProjectLink(1005,roadPart,Track.RightSide,Discontinuity.Continuous,        AddrMRange(102, 136),AddrMRange(102, 136),None,None,Some(createdBy),4107353.toString, 0.0,  32.426,SideCode.TowardsDigitizing,(NoCP,         NoCP         ),(NoCP,         NoCP         ),List(Point(221231.0,6827520.0,0.0), Point(221243.0,6827550.0,0.0)),projectId,RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, LinkGeomSource.FrozenLinkInterface, 32.426,80639,0L,2,reversed = false,None,                  1551489610000L,203081355,roadName,None,None,None,None,None),
        ProjectLink(1006,roadPart,Track.LeftSide, Discontinuity.Continuous,        AddrMRange(125, 208),AddrMRange(125, 208),None,None,Some(createdBy),4107348.toString, 0.0,  74.727,SideCode.TowardsDigitizing,(NoCP,         NoCP         ),(NoCP,         NoCP         ),List(Point(221228.0,6827555.0,0.0), Point(221251.0,6827626.0,0.0)),projectId,RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, LinkGeomSource.FrozenLinkInterface, 74.727,80870,0L,2,reversed = false,None,                  1551489610000L,203081345,roadName,None,None,None,None,None),
        ProjectLink(1007,roadPart,Track.RightSide,Discontinuity.Continuous,        AddrMRange(136, 215),AddrMRange(136, 215),None,None,Some(createdBy),4107349.toString, 0.0,  74.673,SideCode.TowardsDigitizing,(NoCP,         NoCP         ),(NoCP,         NoCP         ),List(Point(221243.0,6827550.0,0.0), Point(221266.0,6827621.0,0.0)),projectId,RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, LinkGeomSource.FrozenLinkInterface, 74.673,80639,0L,2,reversed = false,None,                  1551489610000L,203081355,roadName,None,None,None,None,None),
        ProjectLink(1008,roadPart,Track.LeftSide, Discontinuity.Continuous,        AddrMRange(208, 291),AddrMRange(208, 291),None,None,Some(createdBy),4107302.toString, 0.0,  73.948,SideCode.TowardsDigitizing,(NoCP,         NoCP         ),(NoCP,         NoCP         ),List(Point(221251.0,6827626.0,0.0), Point(221273.0,6827697.0,0.0)),projectId,RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, LinkGeomSource.FrozenLinkInterface, 73.948,80870,0L,2,reversed = false,None,                  1551489610000L,203081345,roadName,None,None,None,None,None),
        ProjectLink(1009,roadPart,Track.RightSide,Discontinuity.Continuous,        AddrMRange(215, 293),AddrMRange(215, 293),None,None,Some(createdBy),4107303.toString, 0.0,  73.938,SideCode.TowardsDigitizing,(NoCP,         NoCP         ),(NoCP,         NoCP         ),List(Point(221266.0,6827621.0,0.0), Point(221288.0,6827692.0,0.0)),projectId,RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, LinkGeomSource.FrozenLinkInterface, 73.938,80639,0L,2,reversed = false,None,                  1551489610000L,203081355,roadName,None,None,None,None,None),
        ProjectLink(1010,roadPart,Track.LeftSide, Discontinuity.MinorDiscontinuity,AddrMRange(291, 372),AddrMRange(291, 372),None,None,Some(createdBy),6860514.toString, 0.0,  72.734,SideCode.TowardsDigitizing,(NoCP,         RoadAddressCP),(NoCP,         RoadAddressCP),List(Point(221273.0,6827697.0,0.0), Point(221294.0,6827766.0,0.0)),projectId,RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, LinkGeomSource.FrozenLinkInterface, 72.734,80870,0L,2,reversed = false,None,                  1551489610000L,203081345,roadName,None,None,None,None,None),
        ProjectLink(1011,roadPart,Track.RightSide,Discontinuity.MinorDiscontinuity,AddrMRange(293, 372),AddrMRange(293, 372),None,None,Some(createdBy),4107324.toString, 0.0,  74.737,SideCode.TowardsDigitizing,(NoCP,         RoadAddressCP),(NoCP,         RoadAddressCP),List(Point(221288.0,6827692.0,0.0), Point(221308.0,6827764.0,0.0)),projectId,RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, LinkGeomSource.FrozenLinkInterface, 74.737,80639,0L,2,reversed = false,None,                  1551489610000L,203081355,roadName,None,None,None,None,None),
        ProjectLink(1012,roadPart,Track.LeftSide, Discontinuity.Continuous,        AddrMRange(372, 549),AddrMRange(372, 549),None,None,Some(createdBy),6860510.toString, 0.0, 162.111,SideCode.TowardsDigitizing,(RoadAddressCP,NoCP         ),(RoadAddressCP,NoCP         ),List(Point(221304.0,6827787.0,0.0), Point(221353.0,6827942.0,0.0)),projectId,RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, LinkGeomSource.FrozenLinkInterface,162.111,79143,0L,2,reversed = false,None,                  1551489610000L,190895362,roadName,None,None,None,None,None),
        ProjectLink(1013,roadPart,Track.RightSide,Discontinuity.Continuous,        AddrMRange(372, 553),AddrMRange(372, 553),None,None,Some(createdBy),6860512.toString, 0.0, 166.163,SideCode.TowardsDigitizing,(RoadAddressCP,NoCP         ),(RoadAddressCP,NoCP         ),List(Point(221314.0,6827780.0,0.0), Point(221367.0,6827937.0,0.0)),projectId,RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, LinkGeomSource.FrozenLinkInterface,166.163,79417,0L,2,reversed = false,None,                  1551489610000L,190895359,roadName,None,None,None,None,None),
        ProjectLink(1014,roadPart,Track.RightSide,Discontinuity.MinorDiscontinuity,AddrMRange(553, 716),AddrMRange(553, 716),None,None,Some(createdBy),4107261.toString, 0.0, 149.998,SideCode.TowardsDigitizing,(NoCP,         RoadAddressCP),(NoCP,         RoadAddressCP),List(Point(221367.0,6827937.0,0.0), Point(221418.0,6828077.0,0.0)),projectId,RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, LinkGeomSource.FrozenLinkInterface,149.998,79417,0L,2,reversed = false,None,                  1551489610000L,190895359,roadName,None,None,None,None,None),
        ProjectLink(1015,roadPart,Track.LeftSide, Discontinuity.MinorDiscontinuity,AddrMRange(549, 716),AddrMRange(549, 716),None,None,Some(createdBy),4107262.toString, 0.0, 153.454,SideCode.TowardsDigitizing,(NoCP,         RoadAddressCP),(NoCP,         RoadAddressCP),List(Point(221353.0,6827942.0,0.0), Point(221402.0,6828086.0,0.0)),projectId,RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, LinkGeomSource.FrozenLinkInterface,153.454,79143,0L,2,reversed = false,None,                  1551489610000L,190895362,roadName,None,None,None,None,None),
        ProjectLink(1016,roadPart,Track.RightSide,Discontinuity.Continuous,        AddrMRange(716, 732),AddrMRange(716, 732),None,None,Some(createdBy),4107220.toString, 0.0,  15.094,SideCode.TowardsDigitizing,(RoadAddressCP,NoCP         ),(RoadAddressCP,NoCP         ),List(Point(221431.0,6828103.0,0.0), Point(221430.0,6828118.0,0.0)),projectId,RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, LinkGeomSource.FrozenLinkInterface, 15.094,80905,0L,2,reversed = false,Some(4107220.toString),1548889228000L,202230394,roadName,None,None,None,None,None),
        ProjectLink(1017,roadPart,Track.LeftSide, Discontinuity.Continuous,        AddrMRange(716, 732),AddrMRange(716, 732),None,None,Some(createdBy),4107221.toString, 0.0,  14.31 ,SideCode.TowardsDigitizing,(RoadAddressCP,NoCP         ),(RoadAddressCP,NoCP         ),List(Point(221413.0,6828110.0,0.0), Point(221418.0,6828123.0,0.0)),projectId,RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, LinkGeomSource.FrozenLinkInterface, 14.31 ,81270,0L,2,reversed = false,None,                  1548889228000L,202230385,roadName,None,None,None,None,None),
        ProjectLink(1018,roadPart,Track.LeftSide, Discontinuity.Continuous,        AddrMRange(732, 734),AddrMRange(732, 734),None,None,Some(createdBy),4107217.toString, 0.0,   1.851,SideCode.TowardsDigitizing,(NoCP,         NoCP         ),(NoCP,         RoadAddressCP),List(Point(221418.0,6828123.0,0.0), Point(221419.0,6828125.0,0.0)),projectId,RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, LinkGeomSource.FrozenLinkInterface,  1.851,81270,0L,2,reversed = false,Some(4107217.toString),1548889228000L,202230385,roadName,None,None,None,None,None),
        ProjectLink(1019,roadPart,Track.RightSide,Discontinuity.Continuous,        AddrMRange(732, 734),AddrMRange(732, 734),None,None,Some(createdBy),4107220.toString,15.094,16.981,SideCode.TowardsDigitizing,(NoCP,         NoCP         ),(RoadAddressCP,NoCP         ),List(Point(221430.0,6828118.0,0.0), Point(221430.0,6828120.0,0.0)),projectId,RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, LinkGeomSource.FrozenLinkInterface,  1.887,80905,0L,2,reversed = false,Some(4107220.toString),1548889228000L,202230394,roadName,None,None,None,None,None),
        ProjectLink(1020,roadPart,Track.RightSide,Discontinuity.Continuous,        AddrMRange(734, 806),AddrMRange(734, 806),None,None,Some(createdBy),4107218.toString, 0.0,  67.157,SideCode.TowardsDigitizing,(NoCP,         RoadAddressCP),(NoCP,         RoadAddressCP),List(Point(221430.0,6828120.0,0.0), Point(221445.0,6828185.0,0.0)),projectId,RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, LinkGeomSource.FrozenLinkInterface, 67.157,80905,0L,2,reversed = false,None,                  1548889228000L,202230394,roadName,None,None,None,None,None),
        ProjectLink(1021,roadPart,Track.LeftSide, Discontinuity.Continuous,        AddrMRange(734, 806),AddrMRange(734, 806),None,None,Some(createdBy),4107217.toString, 1.851,68.478,SideCode.TowardsDigitizing,(NoCP,         RoadAddressCP),(NoCP,         RoadAddressCP),List(Point(221419.0,6828125.0,0.0), Point(221445.0,6828185.0,0.0)),projectId,RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, LinkGeomSource.FrozenLinkInterface, 66.627,81270,0L,2,reversed = false,Some(4107217.toString),1548889228000L,202230385,roadName,None,None,None,None,None),
        ProjectLink(1022,roadPart,Track.Combined, Discontinuity.Continuous,        AddrMRange(806, 940),AddrMRange(806, 940),None,None,Some(createdBy),4107187.toString, 0.0, 138.512,SideCode.TowardsDigitizing,(RoadAddressCP,NoCP         ),(RoadAddressCP,NoCP         ),List(Point(221445.0,6828185.0,0.0), Point(221487.0,6828317.0,0.0)),projectId,RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, LinkGeomSource.FrozenLinkInterface,138.512,63151,0L,2,reversed = false,None,                  1548889228000L, 52347057,roadName,None,None,None,None,None),
        ProjectLink(1023,roadPart,Track.Combined, Discontinuity.Continuous,        AddrMRange(940, 954),AddrMRange(940, 954),None,None,Some(createdBy),4107210.toString, 0.0,  14.811,SideCode.TowardsDigitizing,(NoCP,         NoCP         ),(NoCP,         NoCP         ),List(Point(221487.0,6828317.0,0.0), Point(221492.0,6828331.0,0.0)),projectId,RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, LinkGeomSource.FrozenLinkInterface, 14.811,63151,0L,2,reversed = false,None,                  1548889228000L, 52347057,roadName,None,None,None,None,None),
        ProjectLink(1024,roadPart,Track.Combined, Discontinuity.Continuous,        AddrMRange(954, 980),AddrMRange(954, 980),None,None,Some(createdBy),4107209.toString, 0.0,  27.378,SideCode.TowardsDigitizing,(NoCP,         NoCP         ),(NoCP,         NoCP         ),List(Point(221492.0,6828331.0,0.0), Point(221501.0,6828357.0,0.0)),projectId,RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, LinkGeomSource.FrozenLinkInterface, 27.378,63151,0L,2,reversed = false,None,                  1548889228000L, 52347057,roadName,None,None,None,None,None),
        ProjectLink(1025,roadPart,Track.Combined, Discontinuity.EndOfRoad,         AddrMRange(980,1066),AddrMRange(980,1066),None,None,Some(createdBy),4107203.toString, 0.0,  88.942,SideCode.TowardsDigitizing,(NoCP,         RoadAddressCP),(NoCP,         RoadAddressCP),List(Point(221501.0,6828357.0,0.0), Point(221531.0,6828440.0,0.0)),projectId,RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, LinkGeomSource.FrozenLinkInterface, 88.942,63151,0L,2,reversed = false,None,                  1548889228000L, 52347057,roadName,None,None,None,None,None)
      )

      val roadways = Seq(
        Roadway(62737, 52347054,roadPart,AdministrativeClass.Municipality,Track.LeftSide, Discontinuity.MinorDiscontinuity,AddrMRange(  0,  81),reversed = false,DateTime.parse("2018-07-01T00:00:00.000+03:00"),None,createdBy,roadName,2,TerminationCode.NoTermination,DateTime.parse("2018-07-09T00:00:00.000+03:00"),None),
        Roadway(63151, 52347057,roadPart,AdministrativeClass.Municipality,Track.Combined, Discontinuity.EndOfRoad,         AddrMRange(806,1066),reversed = false,DateTime.parse("2013-10-01T00:00:00.000+03:00"),None,createdBy,roadName,2,TerminationCode.NoTermination,DateTime.parse("2013-10-29T00:00:00.000+02:00"),None),
        Roadway(63197, 52347051,roadPart,AdministrativeClass.Municipality,Track.RightSide,Discontinuity.Continuous,        AddrMRange(  0,  81),reversed = false,DateTime.parse("2018-07-01T00:00:00.000+03:00"),None,createdBy,roadName,2,TerminationCode.NoTermination,DateTime.parse("2018-07-09T00:00:00.000+03:00"),None),
        Roadway(79143,190895362,roadPart,AdministrativeClass.Municipality,Track.LeftSide, Discontinuity.MinorDiscontinuity,AddrMRange(372, 716),reversed = false,DateTime.parse("2018-07-01T00:00:00.000+03:00"),None,createdBy,roadName,2,TerminationCode.NoTermination,DateTime.parse("2018-07-05T00:00:00.000+03:00"),None),
        Roadway(79417,190895359,roadPart,AdministrativeClass.Municipality,Track.RightSide,Discontinuity.MinorDiscontinuity,AddrMRange(372, 716),reversed = false,DateTime.parse("2018-07-01T00:00:00.000+03:00"),None,createdBy,roadName,2,TerminationCode.NoTermination,DateTime.parse("2018-07-05T00:00:00.000+03:00"),None),
        Roadway(80639,203081355,roadPart,AdministrativeClass.Municipality,Track.RightSide,Discontinuity.MinorDiscontinuity,AddrMRange( 81, 372),reversed = false,DateTime.parse("2017-10-01T00:00:00.000+03:00"),None,createdBy,roadName,2,TerminationCode.NoTermination,DateTime.parse("2018-07-09T00:00:00.000+03:00"),None),
        Roadway(80870,203081345,roadPart,AdministrativeClass.Municipality,Track.LeftSide, Discontinuity.MinorDiscontinuity,AddrMRange( 81, 372),reversed = false,DateTime.parse("2017-10-01T00:00:00.000+03:00"),None,createdBy,roadName,2,TerminationCode.NoTermination,DateTime.parse("2018-07-09T00:00:00.000+03:00"),None),
        Roadway(80905,202230394,roadPart,AdministrativeClass.Municipality,Track.RightSide,Discontinuity.Continuous,        AddrMRange(716, 806),reversed = false,DateTime.parse("2013-10-01T00:00:00.000+03:00"),None,createdBy,roadName,2,TerminationCode.NoTermination,DateTime.parse("2018-07-05T00:00:00.000+03:00"),None),
        Roadway(81270,202230385,roadPart,AdministrativeClass.Municipality,Track.LeftSide, Discontinuity.Continuous,        AddrMRange(716, 806),reversed = false,DateTime.parse("2013-10-01T00:00:00.000+03:00"),None,createdBy,roadName,2,TerminationCode.NoTermination,DateTime.parse("2018-07-05T00:00:00.000+03:00"),None)
      )

      val linearLocations = Seq(
        LinearLocation(459379, 4.0, 4107303.toString, 0.0,  73.938, SideCode.TowardsDigitizing, 1551489610000L, (CalibrationPointReference(None,      None),               CalibrationPointReference(None,      None)               ), List(Point(221266.096,6827621.936,0.0), Point(221288.799,6827692.302,0.0)), LinkGeomSource.FrozenLinkInterface, 203081355, Some(DateTime.parse("2018-07-09T00:00:00.000+03:00")), None),
        LinearLocation(459377, 2.0, 4107353.toString, 0.0,  32.426, SideCode.TowardsDigitizing, 1551489610000L, (CalibrationPointReference(None,      None),               CalibrationPointReference(None,      None)               ), List(Point(221231.221,6827520.725,0.0), Point(221243.168,6827550.87 ,0.0)), LinkGeomSource.FrozenLinkInterface, 203081355, Some(DateTime.parse("2018-07-09T00:00:00.000+03:00")), None),
        LinearLocation(456058, 1.0, 6860512.toString, 0.0, 166.163, SideCode.TowardsDigitizing, 1551489610000L, (CalibrationPointReference(Some(372), Some(RoadAddressCP)),CalibrationPointReference(None,      None)               ), List(Point(221314.806,6827780.408,0.0), Point(221367.901,6827937.473,0.0)), LinkGeomSource.FrozenLinkInterface, 190895359, Some(DateTime.parse("2018-07-05T00:00:00.000+03:00")), None),
        LinearLocation(460645, 2.0, 4107352.toString, 0.0,  29.251, SideCode.TowardsDigitizing, 1551489610000L, (CalibrationPointReference(None,      None),               CalibrationPointReference(None,      None)               ), List(Point(221218.934,6827528.001,0.0), Point(221228.307,6827555.71 ,0.0)), LinkGeomSource.FrozenLinkInterface, 203081345, Some(DateTime.parse("2018-07-09T00:00:00.000+03:00")), None),
        LinearLocation(459378, 3.0, 4107349.toString, 0.0,  74.673, SideCode.TowardsDigitizing, 1551489610000L, (CalibrationPointReference(None,      None),               CalibrationPointReference(None,      None)               ), List(Point(221243.168,6827550.87 ,0.0), Point(221266.096,6827621.936,0.0)), LinkGeomSource.FrozenLinkInterface, 203081355, Some(DateTime.parse("2018-07-09T00:00:00.000+03:00")), None),
        LinearLocation(413435, 1.0, 4107187.toString, 0.0, 138.512, SideCode.TowardsDigitizing, 1548889228000L, (CalibrationPointReference(Some(806), Some(RoadAddressCP)),CalibrationPointReference(None,      None)               ), List(Point(221445.08 ,6828185.443,0.0), Point(221487.681,6828317.241,0.0)), LinkGeomSource.FrozenLinkInterface,  52347057, Some(DateTime.parse("2013-10-29T00:00:00.000+02:00")), None),
        LinearLocation(460644, 1.0, 4107358.toString, 0.0,   9.759, SideCode.TowardsDigitizing, 1551489610000L, (CalibrationPointReference(Some( 81), Some(RoadAddressCP)),CalibrationPointReference(None,      None)               ), List(Point(221213.111,6827520.172,0.0), Point(221218.934,6827528.001,0.0)), LinkGeomSource.FrozenLinkInterface, 203081345, Some(DateTime.parse("2018-07-09T00:00:00.000+03:00")), None),
        LinearLocation(410705, 1.0, 4107372.toString, 0.0,  73.976, SideCode.TowardsDigitizing, 1551489610000L, (CalibrationPointReference(Some(  0), Some(RoadAddressCP)),CalibrationPointReference(Some(  81),Some(RoadAddressCP))), List(Point(221242.391,6827426.168,0.0), Point(221210.462,6827491.36 ,0.0)), LinkGeomSource.FrozenLinkInterface,  52347054, Some(DateTime.parse("2018-07-09T00:00:00.000+03:00")), None),
        LinearLocation(462815, 2.0, 4107217.toString, 0.0,  68.478, SideCode.TowardsDigitizing, 1548889228000L, (CalibrationPointReference(None,      None),               CalibrationPointReference(Some( 806),Some(RoadAddressCP))), List(Point(221418.947,6828123.782,0.0), Point(221445.08 ,6828185.443,0.0)), LinkGeomSource.FrozenLinkInterface, 202230385, Some(DateTime.parse("2018-07-05T00:00:00.000+03:00")), None),
        LinearLocation(454475, 1.0, 6860510.toString, 0.0, 162.111, SideCode.TowardsDigitizing, 1551489610000L, (CalibrationPointReference(Some(372), Some(RoadAddressCP)),CalibrationPointReference(None,      None)               ), List(Point(221304.851,6827787.534,0.0), Point(221353.127,6827942.257,0.0)), LinkGeomSource.FrozenLinkInterface, 190895362, Some(DateTime.parse("2018-07-05T00:00:00.000+03:00")), None),
        LinearLocation(459380, 5.0, 4107324.toString, 0.0,  74.737, SideCode.TowardsDigitizing, 1551489610000L, (CalibrationPointReference(None,      None),               CalibrationPointReference(Some( 372),Some(RoadAddressCP))), List(Point(221288.799,6827692.302,0.0), Point(221308.716,6827764.201,0.0)), LinkGeomSource.FrozenLinkInterface, 203081355, Some(DateTime.parse("2018-07-09T00:00:00.000+03:00")), None),
        LinearLocation(459376, 1.0, 4107356.toString, 0.0,  19.586, SideCode.TowardsDigitizing, 1551489610000L, (CalibrationPointReference(Some( 81), Some(RoadAddressCP)),CalibrationPointReference(None,      None)               ), List(Point(221226.385,6827501.747,0.0), Point(221231.221,6827520.725,0.0)), LinkGeomSource.FrozenLinkInterface, 203081355, Some(DateTime.parse("2018-07-09T00:00:00.000+03:00")), None),
        LinearLocation(454476, 2.0, 4107262.toString, 0.0, 153.454, SideCode.TowardsDigitizing, 1551489610000L, (CalibrationPointReference(None,      None),               CalibrationPointReference(Some( 716),Some(RoadAddressCP))), List(Point(221353.127,6827942.257,0.0), Point(221402.889,6828086.474,0.0)), LinkGeomSource.FrozenLinkInterface, 190895362, Some(DateTime.parse("2018-07-05T00:00:00.000+03:00")), None),
        LinearLocation(460647, 4.0, 4107302.toString, 0.0,  73.948, SideCode.TowardsDigitizing, 1551489610000L, (CalibrationPointReference(None,      None),               CalibrationPointReference(None,      None)               ), List(Point(221251.269,6827626.822,0.0), Point(221273.993,6827697.192,0.0)), LinkGeomSource.FrozenLinkInterface, 203081345, Some(DateTime.parse("2018-07-09T00:00:00.000+03:00")), None),
        LinearLocation(462814, 1.0, 4107221.toString, 0.0,  14.31,  SideCode.TowardsDigitizing, 1548889228000L, (CalibrationPointReference(Some(716), Some(RoadAddressCP)),CalibrationPointReference(None,      None)               ), List(Point(221413.108,6828110.724,0.0), Point(221418.947,6828123.782,0.0)), LinkGeomSource.FrozenLinkInterface, 202230385, Some(DateTime.parse("2018-07-05T00:00:00.000+03:00")), None),
        LinearLocation(460646, 3.0, 4107348.toString, 0.0,  74.727, SideCode.TowardsDigitizing, 1551489610000L, (CalibrationPointReference(None,      None),               CalibrationPointReference(None,      None)               ), List(Point(221228.307,6827555.71 ,0.0), Point(221251.269,6827626.822,0.0)), LinkGeomSource.FrozenLinkInterface, 203081345, Some(DateTime.parse("2018-07-09T00:00:00.000+03:00")), None),
        LinearLocation(413436, 2.0, 4107210.toString, 0.0,  14.811, SideCode.TowardsDigitizing, 1548889228000L, (CalibrationPointReference(None     , None),               CalibrationPointReference(None,      None)               ), List(Point(221487.681,6828317.241,0.0), Point(221492.486,6828331.251,0.0)), LinkGeomSource.FrozenLinkInterface,  52347057, Some(DateTime.parse("2013-10-29T00:00:00.000+02:00")), None),
        LinearLocation(413842, 1.0, 4107344.toString, 0.0,  76.993, SideCode.TowardsDigitizing, 1551489610000L, (CalibrationPointReference(Some(  0), Some(RoadAddressCP)),CalibrationPointReference(Some(  81),Some(RoadAddressCP))), List(Point(221253.419,6827429.827,0.0), Point(221226.385,6827501.747,0.0)), LinkGeomSource.FrozenLinkInterface,  52347051, Some(DateTime.parse("2018-07-09T00:00:00.000+03:00")), None),
        LinearLocation(456059, 2.0, 4107261.toString, 0.0, 149.998, SideCode.TowardsDigitizing, 1551489610000L, (CalibrationPointReference(None,      None),               CalibrationPointReference(Some( 716),Some(RoadAddressCP))), List(Point(221367.901,6827937.473,0.0), Point(221418.453,6828077.621,0.0)), LinkGeomSource.FrozenLinkInterface, 190895359, Some(DateTime.parse("2018-07-05T00:00:00.000+03:00")), None),
        LinearLocation(413437, 3.0, 4107209.toString, 0.0,  27.378, SideCode.TowardsDigitizing, 1548889228000L, (CalibrationPointReference(None,      None),               CalibrationPointReference(None,      None)               ), List(Point(221492.486,6828331.251,0.0), Point(221501.544,6828357.087,0.0)), LinkGeomSource.FrozenLinkInterface,  52347057, Some(DateTime.parse("2013-10-29T00:00:00.000+02:00")), None),
        LinearLocation(460855, 1.0, 4107220.toString, 0.0,  16.981, SideCode.TowardsDigitizing, 1548889228000L, (CalibrationPointReference(Some(716), Some(RoadAddressCP)),CalibrationPointReference(None,      None)               ), List(Point(221431.728,6828103.593,0.0), Point(221430.626,6828120.538,0.0)), LinkGeomSource.FrozenLinkInterface, 202230394, Some(DateTime.parse("2018-07-05T00:00:00.000+03:00")), None),
        LinearLocation(460856, 2.0, 4107218.toString, 0.0,  67.157, SideCode.TowardsDigitizing, 1548889228000L, (CalibrationPointReference(None,      None),               CalibrationPointReference(Some( 806),Some(RoadAddressCP))), List(Point(221430.626,6828120.538,0.0), Point(221445.08 ,6828185.443,0.0)), LinkGeomSource.FrozenLinkInterface, 202230394, Some(DateTime.parse("2018-07-05T00:00:00.000+03:00")), None),
        LinearLocation(413438, 4.0, 4107203.toString, 0.0,  88.942, SideCode.TowardsDigitizing, 1548889228000L, (CalibrationPointReference(None,      None),               CalibrationPointReference(Some(1066),Some(RoadAddressCP))), List(Point(221501.544,6828357.087,0.0), Point(221531.267,6828440.914,0.0)), LinkGeomSource.FrozenLinkInterface,  52347057, Some(DateTime.parse("2013-10-29T00:00:00.000+02:00")), None),
        LinearLocation(460648, 5.0, 6860514.toString, 0.0,  72.734, SideCode.TowardsDigitizing, 1551489610000L, (CalibrationPointReference(None,      None),               CalibrationPointReference(Some( 372),Some(RoadAddressCP))), List(Point(221273.993,6827697.192,0.0), Point(221294.912,6827766.783,0.0)), LinkGeomSource.FrozenLinkInterface, 203081345, Some(DateTime.parse("2018-07-09T00:00:00.000+03:00")), None)
      )

      val project = Project(projectId, ProjectState.Incomplete, "f", "s", DateTime.now(), "", DateTime.now(), DateTime.now(), "", Seq(), Seq(), None, None, Set())

      buildTestDataForProject(Some(project), Some(roadways), Some(linearLocations), Some(projectLinks))
      val defaultSectionCalculatorStrategy = new DefaultSectionCalculatorStrategy

      val projectLinksWithAssignedValues = defaultSectionCalculatorStrategy.assignAddrMValues(Seq(), projectLinks, Seq.empty[UserDefinedCalibrationPoint])

      val assignedTrackGrouped = projectLinksWithAssignedValues.groupBy(_.track)
      val originalTrackGrouped = projectLinks.groupBy(_.track)

      Track.values.foreach(track => {
        assignedTrackGrouped.getOrElse(track, Seq()).sortBy(_.addrMRange.start).zip(originalTrackGrouped.getOrElse(track, Seq()).sortBy(_.addrMRange.start)).foreach { case (calculated, original) => {
          calculated.addrMRange.start should be(original.addrMRange.start)
          calculated.addrMRange.end should be(original.addrMRange.end)
          calculated.linkId should be(original.linkId)
          calculated.roadwayId should be(original.roadwayId)
          calculated.roadwayNumber should be(original.roadwayNumber)
        }}
      })

      // Check reversed case
      projectLinkDAO.updateProjectLinksStatus(projectLinks.map(_.id).toSet,RoadAddressChangeType.Transfer, createdBy)
      projectService.changeDirection(projectId, roadPart, Seq(), ProjectCoordinates(0, 0, 0),createdBy) should be(None)

      val updatedProjectLinks = defaultSectionCalculatorStrategy.assignAddrMValues(Seq(), projectLinkDAO.fetchProjectLinks(projectId), Seq.empty[UserDefinedCalibrationPoint])
      // There are continuity validations in defaultSectionCalculatorStrategy
      val minAddress = updatedProjectLinks.minBy(_.addrMRange.start)
      val maxAddress = updatedProjectLinks.maxBy(_.addrMRange.end)
      minAddress.originalAddrMRange.end   should be(projectLinks.maxBy(_.addrMRange.end).addrMRange.end)
      maxAddress.originalAddrMRange.start should be(0)
      updatedProjectLinks.filter(_.track == Track.Combined ).foreach(pl => pl.originalTrack should be(Track.switch(pl.track)))
      updatedProjectLinks.filter(_.track == Track.RightSide).foreach(pl => pl.originalTrack should be(Track.switch(pl.track)))
      updatedProjectLinks.filter(_.track == Track.LeftSide ).foreach(pl => pl.originalTrack should be(Track.switch(pl.track)))
    }
  }

  test("Test createRoadLinkProject When project in writable state Then  Service should identify states (Incomplete, and ErrorInViite)") {
    runWithRollback {
      val incomplete = Project(0L, ProjectState.apply(1), "I am Incomplete", "TestUser", DateTime.parse("1901-01-01"), "TestUser", DateTime.parse("1901-01-01"), DateTime.now(), "Some additional info", Seq(), Seq(), None, elys = Set())
      val incompleteProject = projectService.createRoadLinkProject(incomplete)
      val errorInViite = Project(0L, ProjectState.apply(1), "I am ErrorInViite", "TestUser", DateTime.parse("1901-01-01"), "TestUser", DateTime.parse("1901-01-01"), DateTime.now(), "Some additional info", Seq(), Seq(), None, elys = Set())
      val errorInViiteProject = projectService.createRoadLinkProject(errorInViite)
      projectService.isWritableState(incompleteProject.id) should be(true)
      projectService.isWritableState(errorInViiteProject.id) should be(true)
    }
  }

  test("Test projectService.updateProjectLinks() When transferring last ajr 1 & 2 links from part 1 to part 2 and adjust endAddrMValues for last links from transferred part and transfer the rest of the part 2 " +
    "Then the mAddressValues of the last links should be equal in both sides of the tracks for part 1.")
  {
    runWithRollback {
      /**
        * Test data
        */
      val rap = Project(0L, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("1901-01-01"), "TestUser", DateTime.parse("1995-01-01"), DateTime.now(), "Some additional info", Seq(), Seq(), None, elys = Set())

      // part1
      // track1

      runUpdateToDb("""INSERT INTO LINK (ID) VALUES ('12345')""")
      runUpdateToDb("""INSERT INTO LINK (ID) VALUES ('12346')""")
      runUpdateToDb("""INSERT INTO LINK (ID) VALUES ('12347')""")
      runUpdateToDb("""INSERT INTO LINEAR_LOCATION (ID,ROADWAY_NUMBER,ORDER_NUMBER,LINK_ID,START_MEASURE,END_MEASURE,SIDE,GEOMETRY,VALID_FROM,VALID_TO,CREATED_BY,CREATED_TIME)
            VALUES(nextval('LINEAR_LOCATION_SEQ'), 123, 1, '12345', 0, 9, 2, ST_GeomFromText('LINESTRING(5.0 0.0 0 0, 5.0 9.0 0 9)', 3067), TIMESTAMP '2015-12-30 00:00:00.000000', NULL, 'TR', TIMESTAMP '2015-12-30 00:00:00.000000')""")
      runUpdateToDb("""INSERT INTO LINEAR_LOCATION (ID,ROADWAY_NUMBER,ORDER_NUMBER,LINK_ID,START_MEASURE,END_MEASURE,SIDE,GEOMETRY,VALID_FROM,VALID_TO,CREATED_BY,CREATED_TIME)
            VALUES(nextval('LINEAR_LOCATION_SEQ'), 123, 2, '12346', 0, 12, 2, ST_GeomFromText('LINESTRING(5.0 9.0 0 9, 5.0 21.0 0 21)', 3067), TIMESTAMP '2015-12-30 00:00:00.000000', NULL, 'TR', TIMESTAMP '2015-12-30 00:00:00.000000')""")
      runUpdateToDb("""INSERT INTO LINEAR_LOCATION (ID,ROADWAY_NUMBER,ORDER_NUMBER,LINK_ID,START_MEASURE,END_MEASURE,SIDE,GEOMETRY,VALID_FROM,VALID_TO,CREATED_BY,CREATED_TIME)
            VALUES(nextval('LINEAR_LOCATION_SEQ'), 123, 3, '12347', 0, 5, 2, ST_GeomFromText('LINESTRING(5.0 21.0 0 21, 5.0 26.0 0 26)', 3067), TIMESTAMP '2015-12-30 00:00:00.000000', NULL, 'TR', TIMESTAMP '2015-12-30 00:00:00.000000')""")

      runUpdateToDb("""Insert into ROADWAY (ID,ROADWAY_NUMBER,ROAD_NUMBER,ROAD_PART_NUMBER,TRACK,START_ADDR_M,END_ADDR_M,REVERSED,DISCONTINUITY,START_DATE,END_DATE,CREATED_BY,CREATED_TIME,ADMINISTRATIVE_CLASS,ELY,TERMINATED,VALID_FROM,VALID_TO)
        values (nextval('ROADWAY_SEQ'), 123,9999,1,1,0,26,0,1,to_date('22-10-90','DD-MM-YY'),null,'TR',to_timestamp('21-09-18 12.04.42.970245000','DD-MM-YY HH24.MI.SSXFF'),1,8,0,to_date('16-10-98','DD-MM-YY'),null)""")



      // track2
      runUpdateToDb("""INSERT INTO LINK (ID) VALUES ('12348')""")
      runUpdateToDb("""INSERT INTO LINK (ID) VALUES ('12349')""")
      runUpdateToDb("""INSERT INTO LINK (ID) VALUES ('12350')""")
      runUpdateToDb("""INSERT INTO LINK (ID) VALUES ('12351')""")
      runUpdateToDb("""INSERT INTO LINEAR_LOCATION (ID,ROADWAY_NUMBER,ORDER_NUMBER,LINK_ID,START_MEASURE,END_MEASURE,SIDE,GEOMETRY,VALID_FROM,VALID_TO,CREATED_BY,CREATED_TIME)
            VALUES(nextval('LINEAR_LOCATION_SEQ'), 124, 1, '12348', 0, 10, 2, ST_GeomFromText('LINESTRING(0.0 0.0 0 0, 0.0 10.0 0 10)', 3067), TIMESTAMP '2015-12-30 00:00:00.000000', NULL, 'TR', TIMESTAMP '2015-12-30 00:00:00.000000')""")
      runUpdateToDb("""INSERT INTO LINEAR_LOCATION (ID,ROADWAY_NUMBER,ORDER_NUMBER,LINK_ID,START_MEASURE,END_MEASURE,SIDE,GEOMETRY,VALID_FROM,VALID_TO,CREATED_BY,CREATED_TIME)
            VALUES(nextval('LINEAR_LOCATION_SEQ'), 124, 2, '12349', 0, 8, 2, ST_GeomFromText('LINESTRING(0.0 10.0 0 10, 0.0 18.0 0 18)', 3067), TIMESTAMP '2015-12-30 00:00:00.000000', NULL, 'TR', TIMESTAMP '2015-12-30 00:00:00.000000')""")
      runUpdateToDb("""INSERT INTO LINEAR_LOCATION (ID,ROADWAY_NUMBER,ORDER_NUMBER,LINK_ID,START_MEASURE,END_MEASURE,SIDE,GEOMETRY,VALID_FROM,VALID_TO,CREATED_BY,CREATED_TIME)
            VALUES(nextval('LINEAR_LOCATION_SEQ'), 124, 3, '12350', 0, 5, 2, ST_GeomFromText('LINESTRING(0.0 18.0 0 18, 0.0 23.0 0 23)', 3067), TIMESTAMP '2015-12-30 00:00:00.000000', NULL, 'TR', TIMESTAMP '2015-12-30 00:00:00.000000')""")
      runUpdateToDb("""INSERT INTO LINEAR_LOCATION (ID,ROADWAY_NUMBER,ORDER_NUMBER,LINK_ID,START_MEASURE,END_MEASURE,SIDE,GEOMETRY,VALID_FROM,VALID_TO,CREATED_BY,CREATED_TIME)
            VALUES(nextval('LINEAR_LOCATION_SEQ'), 124, 4, '12351', 0, 3, 2, ST_GeomFromText('LINESTRING(0.0 23.0 0 23, 0.0 26.0 0 26)', 3067), TIMESTAMP '2015-12-30 00:00:00.000000', NULL, 'TR', TIMESTAMP '2015-12-30 00:00:00.000000')""")

      runUpdateToDb("""Insert into ROADWAY (ID,ROADWAY_NUMBER,ROAD_NUMBER,ROAD_PART_NUMBER,TRACK,START_ADDR_M,END_ADDR_M,REVERSED,DISCONTINUITY,START_DATE,END_DATE,CREATED_BY,CREATED_TIME,ADMINISTRATIVE_CLASS,ELY,TERMINATED,VALID_FROM,VALID_TO)
        values (nextval('ROADWAY_SEQ'), 124,9999,1,2,0,26,0,1,to_date('22-10-90','DD-MM-YY'),null,'TR',to_timestamp('21-09-18 12.04.42.970245000','DD-MM-YY HH24.MI.SSXFF'),1,8,0,to_date('16-10-98','DD-MM-YY'),null)""")

      // part2
      // track1
      runUpdateToDb("""INSERT INTO LINK (ID) VALUES ('12352')""")
      runUpdateToDb("""INSERT INTO LINK (ID) VALUES ('12353')""")
      runUpdateToDb("""INSERT INTO LINEAR_LOCATION (ID,ROADWAY_NUMBER,ORDER_NUMBER,LINK_ID,START_MEASURE,END_MEASURE,SIDE,GEOMETRY,VALID_FROM,VALID_TO,CREATED_BY,CREATED_TIME)
            VALUES(nextval('LINEAR_LOCATION_SEQ'), 125, 1, '12352', 0, 2, 2, ST_GeomFromText('LINESTRING(5.0 26.0 0 0, 5.0 28.0 0 2)', 3067), TIMESTAMP '2015-12-30 00:00:00.000000', NULL, 'TR', TIMESTAMP '2015-12-30 00:00:00.000000')""")
      runUpdateToDb("""INSERT INTO LINEAR_LOCATION (ID,ROADWAY_NUMBER,ORDER_NUMBER,LINK_ID,START_MEASURE,END_MEASURE,SIDE,GEOMETRY,VALID_FROM,VALID_TO,CREATED_BY,CREATED_TIME)
            VALUES(nextval('LINEAR_LOCATION_SEQ'), 125, 2, '12353', 0, 7, 2, ST_GeomFromText('LINESTRING(5.0 28.0 0 2, 5.0 35.0 0 7)', 3067), TIMESTAMP '2015-12-30 00:00:00.000000', NULL, 'TR', TIMESTAMP '2015-12-30 00:00:00.000000')""")

      runUpdateToDb("""Insert into ROADWAY (ID,ROADWAY_NUMBER,ROAD_NUMBER,ROAD_PART_NUMBER,TRACK,START_ADDR_M,END_ADDR_M,REVERSED,DISCONTINUITY,START_DATE,END_DATE,CREATED_BY,CREATED_TIME,ADMINISTRATIVE_CLASS,ELY,TERMINATED,VALID_FROM,VALID_TO)
        values (nextval('ROADWAY_SEQ'), 125,9999,2,1,0,7,0,1,to_date('22-10-90','DD-MM-YY'),null,'TR',to_timestamp('21-09-18 12.04.42.970245000','DD-MM-YY HH24.MI.SSXFF'),1,8,0,to_date('16-10-98','DD-MM-YY'),null)""")

      // track2
      runUpdateToDb("""INSERT INTO LINK (ID) VALUES ('12354')""")
      runUpdateToDb("""INSERT INTO LINK (ID) VALUES ('12355')""")
      runUpdateToDb("""INSERT INTO LINEAR_LOCATION (ID,ROADWAY_NUMBER,ORDER_NUMBER,LINK_ID,START_MEASURE,END_MEASURE,SIDE,GEOMETRY,VALID_FROM,VALID_TO,CREATED_BY,CREATED_TIME)
            VALUES(nextval('LINEAR_LOCATION_SEQ'), 126, 1, '12354', 0, 3, 2, ST_GeomFromText('LINESTRING(0.0 26.0 0 0, 0.0 29.0 0 3)', 3067), TIMESTAMP '2015-12-30 00:00:00.000000', NULL, 'TR', TIMESTAMP '2015-12-30 00:00:00.000000')""")
      runUpdateToDb("""INSERT INTO LINEAR_LOCATION (ID,ROADWAY_NUMBER,ORDER_NUMBER,LINK_ID,START_MEASURE,END_MEASURE,SIDE,GEOMETRY,VALID_FROM,VALID_TO,CREATED_BY,CREATED_TIME)
            VALUES(nextval('LINEAR_LOCATION_SEQ'), 126, 2, '12355', 0, 8, 2, ST_GeomFromText('LINESTRING(0.0 29.0 0 3, 0.0 37.0 0 11)', 3067), TIMESTAMP '2015-12-30 00:00:00.000000', NULL, 'TR', TIMESTAMP '2015-12-30 00:00:00.000000')""")
      runUpdateToDb("""Insert into ROADWAY (ID,ROADWAY_NUMBER,ROAD_NUMBER,ROAD_PART_NUMBER,TRACK,START_ADDR_M,END_ADDR_M,REVERSED,DISCONTINUITY,START_DATE,END_DATE,CREATED_BY,CREATED_TIME,ADMINISTRATIVE_CLASS,ELY,TERMINATED,VALID_FROM,VALID_TO)
        values (nextval('ROADWAY_SEQ'), 126,9999,2,2,0,11,0,1,to_date('22-10-90','DD-MM-YY'),null,'TR',to_timestamp('21-09-18 12.04.42.970245000','DD-MM-YY HH24.MI.SSXFF'),1,8,0,to_date('16-10-98','DD-MM-YY'),null)""")

      val project = projectService.createRoadLinkProject(rap)
      val id = project.id
      val part1 = roadwayAddressMapper.getRoadAddressesByLinearLocation(linearLocationDAO.fetchByRoadways(roadwayDAO.fetchAllBySection(RoadPart(9999, 1)).map(_.roadwayNumber).toSet))
      val part2 = roadwayAddressMapper.getRoadAddressesByLinearLocation(linearLocationDAO.fetchByRoadways(roadwayDAO.fetchAllBySection(RoadPart(9999, 2)).map(_.roadwayNumber).toSet))
      val toProjectLinks = (part1 ++ part2).map(toProjectLink(rap))
      val roadLinks = toProjectLinks.map(toRoadLink)

      when(mockRoadLinkService.getRoadLinksHistoryFromVVH(any[Set[String]])).thenReturn(Seq())
      when(mockRoadLinkService.getRoadLinksByLinkIds(any[Set[String]])).thenReturn(roadLinks)
      projectService.saveProject(project.copy(reservedParts = Seq(
        ProjectReservedPart(0L, RoadPart(9999, 1), null, Some(Discontinuity.Continuous), Some(8L), None, None, None, None),
        ProjectReservedPart(0L, RoadPart(9999, 2), null, Some(Discontinuity.Continuous), Some(8L), None, None, None, None)), elys = Set())
      )

      val projectLinks = projectLinkDAO.fetchProjectLinks(id)
      val part1track1 = Set(12345L, 12346L, 12347L).map(_.toString)
      val part1track2 = Set(12348L, 12349L, 12350L, 12351L).map(_.toString)
      val part1track1Links = projectLinks.filter(pl => part1track1.contains(pl.linkId)).map(_.id).toSet
      val part1Track2Links = projectLinks.filter(pl => part1track2.contains(pl.linkId)).map(_.id).toSet

      projectLinkDAO.updateProjectLinksStatus(part1track1Links, RoadAddressChangeType.Unchanged, "test")
      projectLinkDAO.updateProjectLinksStatus(part1Track2Links, RoadAddressChangeType.Unchanged, "test")
//
//      /**
//        * Tranfering adjacents of part1 to part2
//        */
      val part1AdjacentToPart2IdRightSide = Set(12347L.toString)
      val part1AdjacentToPart2IdLeftSide = Set(12351L.toString)
      val part1AdjacentToPart2LinkRightSide = projectLinks.filter(pl => part1AdjacentToPart2IdRightSide.contains(pl.linkId)).map(_.id).toSet
      val part1AdjacentToPart2LinkLeftSide = projectLinks.filter(pl => part1AdjacentToPart2IdLeftSide.contains(pl.linkId)).map(_.id).toSet

      projectService.updateProjectLinks(id, part1AdjacentToPart2LinkRightSide, Seq(), RoadAddressChangeType.Transfer, "test", RoadPart(9999, 2), 1, None, 1, 5, Some(1L), reversed = false, None)
      projectService.updateProjectLinks(id, part1AdjacentToPart2LinkLeftSide,  Seq(), RoadAddressChangeType.Transfer, "test", RoadPart(9999, 2), 2, None, 1, 5, Some(1L), reversed = false, None)

      val part2track1 = Set(12352L.toString, 12353L.toString)
      val part2track2 = Set(12354L.toString, 12355L.toString)
      val part2track1Links = projectLinks.filter(pl => part2track1.contains(pl.linkId)).map(_.id).toSet
      val part2Track2Links = projectLinks.filter(pl => part2track2.contains(pl.linkId)).map(_.id).toSet
      projectService.updateProjectLinks(id, part2track1Links, Seq(), RoadAddressChangeType.Transfer, "test", RoadPart(9999, 2), 1, None, 1, 5, Some(1L), reversed = false, None)
      projectService.updateProjectLinks(id, part2Track2Links, Seq(), RoadAddressChangeType.Transfer, "test", RoadPart(9999, 2), 2, None, 1, 5, Some(1L), reversed = false, None)

      val projectLinks2 = projectLinkDAO.fetchProjectLinks(id)
      val projectLinks3 = ProjectSectionCalculator.assignAddrMValues(projectLinks2).sortBy(_.addrMRange.end)

      val parts = projectLinks3.partition(_.roadPart === RoadPart(9999, 1))
      val part1tracks = parts._1.partition(_.track === Track.RightSide)
      part1tracks._1.maxBy(_.addrMRange.end).addrMRange.end should be(part1tracks._2.maxBy(_.addrMRange.end).addrMRange.end)
      val part2tracks = parts._2.partition(_.track === Track.RightSide)
      part2tracks._1.maxBy(_.addrMRange.end).addrMRange.end should be(part2tracks._2.maxBy(_.addrMRange.end).addrMRange.end)
    }
  }

  test("Test projectService.updateProjectLinks When transferring the rest of the part 2 and then the last ajr 1 & 2 links from part 1 to part 2 and adjust endAddrMValues for last links from transferred part " +
    "Then the mAddressValues of the last links should be equal in both sides of the tracks for part 1." )
  {
    runWithRollback {
      /**
        * Test data
        */
      val rap = Project(0L, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("1901-01-01"), "TestUser", DateTime.parse("1991-01-01"), DateTime.now(), "Some additional info", Seq(), Seq(), None, elys = Set())

      // part1
      // track1
      runUpdateToDb("""INSERT INTO LINK (ID) VALUES ('12345')""")
      runUpdateToDb("""INSERT INTO LINK (ID) VALUES ('12346')""")
      runUpdateToDb("""INSERT INTO LINK (ID) VALUES ('12347')""")
      runUpdateToDb("""INSERT INTO LINEAR_LOCATION (ID,ROADWAY_NUMBER,ORDER_NUMBER,LINK_ID,START_MEASURE,END_MEASURE,SIDE,GEOMETRY,VALID_FROM,VALID_TO,CREATED_BY,CREATED_TIME)
            VALUES(nextval('LINEAR_LOCATION_SEQ'), 1234567, 1, '12345', 0, 9, 2, ST_GeomFromText('LINESTRING(5.0 0.0 0 0, 5.0 9.0 0 9)', 3067), TIMESTAMP '2015-12-30 00:00:00.000000', NULL, 'TR', TIMESTAMP '2015-12-30 00:00:00.000000')""")
      runUpdateToDb("""INSERT INTO LINEAR_LOCATION (ID,ROADWAY_NUMBER,ORDER_NUMBER,LINK_ID,START_MEASURE,END_MEASURE,SIDE,GEOMETRY,VALID_FROM,VALID_TO,CREATED_BY,CREATED_TIME)
            VALUES(nextval('LINEAR_LOCATION_SEQ'), 1234567, 2, '12346', 0, 12, 2, ST_GeomFromText('LINESTRING(5.0 9.0 0 9, 5.0 21.0 0 21)', 3067), TIMESTAMP '2015-12-30 00:00:00.000000', NULL, 'TR', TIMESTAMP '2015-12-30 00:00:00.000000')""")
      runUpdateToDb("""INSERT INTO LINEAR_LOCATION (ID,ROADWAY_NUMBER,ORDER_NUMBER,LINK_ID,START_MEASURE,END_MEASURE,SIDE,GEOMETRY,VALID_FROM,VALID_TO,CREATED_BY,CREATED_TIME)
            VALUES(nextval('LINEAR_LOCATION_SEQ'), 1234567, 3, '12347', 0, 5, 2, ST_GeomFromText('LINESTRING(5.0 21.0 0 21, 5.0 26.0 0 26)', 3067), TIMESTAMP '2015-12-30 00:00:00.000000', NULL, 'TR', TIMESTAMP '2015-12-30 00:00:00.000000')""")

      runUpdateToDb("""Insert into ROADWAY (ID,ROADWAY_NUMBER,ROAD_NUMBER,ROAD_PART_NUMBER,TRACK,START_ADDR_M,END_ADDR_M,REVERSED,DISCONTINUITY,START_DATE,END_DATE,CREATED_BY,CREATED_TIME,ADMINISTRATIVE_CLASS,ELY,TERMINATED,VALID_FROM,VALID_TO)
        values (nextval('ROADWAY_SEQ'), 1234567,9999,1,1,0,26,0,1,to_date('22-10-90','DD-MM-YY'),null,'TR',to_timestamp('21-09-18 12.04.42.970245000','DD-MM-YY HH24.MI.SSXFF'),1,8,0,to_date('16-10-98','DD-MM-YY'),null)""")



      // track2
      runUpdateToDb("""INSERT INTO LINK (ID) VALUES ('12348')""")
      runUpdateToDb("""INSERT INTO LINK (ID) VALUES ('12349')""")
      runUpdateToDb("""INSERT INTO LINK (ID) VALUES ('12350')""")
      runUpdateToDb("""INSERT INTO LINK (ID) VALUES ('12351')""")
      runUpdateToDb("""INSERT INTO LINEAR_LOCATION (ID,ROADWAY_NUMBER,ORDER_NUMBER,LINK_ID,START_MEASURE,END_MEASURE,SIDE,GEOMETRY,VALID_FROM,VALID_TO,CREATED_BY,CREATED_TIME)
            VALUES(nextval('LINEAR_LOCATION_SEQ'), 1234568, 1, '12348', 0, 10, 2, ST_GeomFromText('LINESTRING(0.0 0.0 0 0, 0.0 10.0 0 10)', 3067), TIMESTAMP '2015-12-30 00:00:00.000000', NULL, 'TR', TIMESTAMP '2015-12-30 00:00:00.000000')""")
      runUpdateToDb("""INSERT INTO LINEAR_LOCATION (ID,ROADWAY_NUMBER,ORDER_NUMBER,LINK_ID,START_MEASURE,END_MEASURE,SIDE,GEOMETRY,VALID_FROM,VALID_TO,CREATED_BY,CREATED_TIME)
            VALUES(nextval('LINEAR_LOCATION_SEQ'), 1234568, 2, '12349', 0, 8, 2, ST_GeomFromText('LINESTRING(0.0 10.0 0 10, 0.0 18.0 0 18)', 3067), TIMESTAMP '2015-12-30 00:00:00.000000', NULL, 'TR', TIMESTAMP '2015-12-30 00:00:00.000000')""")
      runUpdateToDb("""INSERT INTO LINEAR_LOCATION (ID,ROADWAY_NUMBER,ORDER_NUMBER,LINK_ID,START_MEASURE,END_MEASURE,SIDE,GEOMETRY,VALID_FROM,VALID_TO,CREATED_BY,CREATED_TIME)
            VALUES(nextval('LINEAR_LOCATION_SEQ'), 1234568, 3, '12350', 0, 5, 2, ST_GeomFromText('LINESTRING(0.0 18.0 0 18, 0.0 23.0 0 23)', 3067), TIMESTAMP '2015-12-30 00:00:00.000000', NULL, 'TR', TIMESTAMP '2015-12-30 00:00:00.000000')""")
      runUpdateToDb("""INSERT INTO LINEAR_LOCATION (ID,ROADWAY_NUMBER,ORDER_NUMBER,LINK_ID,START_MEASURE,END_MEASURE,SIDE,GEOMETRY,VALID_FROM,VALID_TO,CREATED_BY,CREATED_TIME)
            VALUES(nextval('LINEAR_LOCATION_SEQ'), 1234568, 4, '12351', 0, 3, 2, ST_GeomFromText('LINESTRING(0.0 23.0 0 23, 0.0 26.0 0 26)', 3067), TIMESTAMP '2015-12-30 00:00:00.000000', NULL, 'TR', TIMESTAMP '2015-12-30 00:00:00.000000')""")

      runUpdateToDb("""Insert into ROADWAY (ID,ROADWAY_NUMBER,ROAD_NUMBER,ROAD_PART_NUMBER,TRACK,START_ADDR_M,END_ADDR_M,REVERSED,DISCONTINUITY,START_DATE,END_DATE,CREATED_BY,CREATED_TIME,ADMINISTRATIVE_CLASS,ELY,TERMINATED,VALID_FROM,VALID_TO)
        values (nextval('ROADWAY_SEQ'), 1234568,9999,1,2,0,26,0,1,to_date('22-10-90','DD-MM-YY'),null,'TR',to_timestamp('21-09-18 12.04.42.970245000','DD-MM-YY HH24.MI.SSXFF'),1,8,0,to_date('16-10-98','DD-MM-YY'),null)""")

      // part2
      // track1
      runUpdateToDb("""INSERT INTO LINK (ID) VALUES ('12352')""")
      runUpdateToDb("""INSERT INTO LINK (ID) VALUES ('12353')""")
      runUpdateToDb("""INSERT INTO LINEAR_LOCATION (ID,ROADWAY_NUMBER,ORDER_NUMBER,LINK_ID,START_MEASURE,END_MEASURE,SIDE,GEOMETRY,VALID_FROM,VALID_TO,CREATED_BY,CREATED_TIME)
            VALUES(nextval('LINEAR_LOCATION_SEQ'), 1234569, 1, '12352', 0, 2, 2,ST_GeomFromText('LINESTRING(5.0 26.0 0 0, 5.0 28.0 0 2)', 3067), TIMESTAMP '2015-12-30 00:00:00.000000', NULL, 'TR', TIMESTAMP '2015-12-30 00:00:00.000000')""")
      runUpdateToDb("""INSERT INTO LINEAR_LOCATION (ID,ROADWAY_NUMBER,ORDER_NUMBER,LINK_ID,START_MEASURE,END_MEASURE,SIDE,GEOMETRY,VALID_FROM,VALID_TO,CREATED_BY,CREATED_TIME)
            VALUES(nextval('LINEAR_LOCATION_SEQ'), 1234569, 2, '12353', 0, 7, 2, ST_GeomFromText('LINESTRING(5.0 28.0 0 2, 5.0 35.0 0 7)', 3067), TIMESTAMP '2015-12-30 00:00:00.000000', NULL, 'TR', TIMESTAMP '2015-12-30 00:00:00.000000')""")

      runUpdateToDb("""Insert into ROADWAY (ID,ROADWAY_NUMBER,ROAD_NUMBER,ROAD_PART_NUMBER,TRACK,START_ADDR_M,END_ADDR_M,REVERSED,DISCONTINUITY,START_DATE,END_DATE,CREATED_BY,CREATED_TIME,ADMINISTRATIVE_CLASS,ELY,TERMINATED,VALID_FROM,VALID_TO)
        values (nextval('ROADWAY_SEQ'), 1234569,9999,2,1,0,7,0,1,to_date('22-10-90','DD-MM-YY'),null,'TR',to_timestamp('21-09-18 12.04.42.970245000','DD-MM-YY HH24.MI.SSXFF'),1,8,0,to_date('16-10-98','DD-MM-YY'),null)""")

      // track2
      runUpdateToDb("""INSERT INTO LINK (ID) VALUES ('12354')""")
      runUpdateToDb("""INSERT INTO LINK (ID) VALUES ('12355')""")
      runUpdateToDb("""INSERT INTO LINEAR_LOCATION (ID,ROADWAY_NUMBER,ORDER_NUMBER,LINK_ID,START_MEASURE,END_MEASURE,SIDE,GEOMETRY,VALID_FROM,VALID_TO,CREATED_BY,CREATED_TIME)
            VALUES(nextval('LINEAR_LOCATION_SEQ'), 1234570, 1, '12354', 0, 3, 2, ST_GeomFromText('LINESTRING(0.0 26.0 0 0, 0.0 29.0 0 3)', 3067), TIMESTAMP '2015-12-30 00:00:00.000000', NULL, 'TR', TIMESTAMP '2015-12-30 00:00:00.000000')""")
      runUpdateToDb("""INSERT INTO LINEAR_LOCATION (ID,ROADWAY_NUMBER,ORDER_NUMBER,LINK_ID,START_MEASURE,END_MEASURE,SIDE,GEOMETRY,VALID_FROM,VALID_TO,CREATED_BY,CREATED_TIME)
            VALUES(nextval('LINEAR_LOCATION_SEQ'), 1234570, 2, '12355', 0, 8, 2, ST_GeomFromText('LINESTRING(0.0 29.0 0 3, 0.0 37.0 0 11)', 3067), TIMESTAMP '2015-12-30 00:00:00.000000', NULL, 'TR', TIMESTAMP '2015-12-30 00:00:00.000000')""")

      runUpdateToDb("""Insert into ROADWAY (ID,ROADWAY_NUMBER,ROAD_NUMBER,ROAD_PART_NUMBER,TRACK,START_ADDR_M,END_ADDR_M,REVERSED,DISCONTINUITY,START_DATE,END_DATE,CREATED_BY,CREATED_TIME,ADMINISTRATIVE_CLASS,ELY,TERMINATED,VALID_FROM,VALID_TO)
        values (nextval('ROADWAY_SEQ'), 1234570,9999,2,2,0,11,0,1,to_date('22-10-90','DD-MM-YY'),null,'TR',to_timestamp('21-09-18 12.04.42.970245000','DD-MM-YY HH24.MI.SSXFF'),1,8,0,to_date('16-10-98','DD-MM-YY'),null)""")

      val project = projectService.createRoadLinkProject(rap)
      val id = project.id
      val part1 = roadwayAddressMapper.getRoadAddressesByLinearLocation(linearLocationDAO.fetchByRoadways(roadwayDAO.fetchAllBySection(RoadPart(9999, 1)).map(_.roadwayNumber).toSet))
      val part2 = roadwayAddressMapper.getRoadAddressesByLinearLocation(linearLocationDAO.fetchByRoadways(roadwayDAO.fetchAllBySection(RoadPart(9999, 2)).map(_.roadwayNumber).toSet))
      val toProjectLinks = (part1 ++ part2).map(toProjectLink(rap))
      val roadLinks = toProjectLinks.map(toRoadLink)
      when(mockRoadLinkService.getRoadLinksHistoryFromVVH(any[Set[String]])).thenReturn(Seq())
      when(mockRoadLinkService.getRoadLinksByLinkIds(any[Set[String]])).thenReturn(roadLinks)
      projectService.saveProject(project.copy(reservedParts = Seq(ProjectReservedPart(0L, RoadPart(9999, 1), null, Some(Discontinuity.Continuous), Some(8L), None, None, None, None), ProjectReservedPart(0L, RoadPart(9999, 2), null, Some(Discontinuity.Continuous), Some(8L), None, None, None, None)), elys = Set()))

      val projectLinks = projectLinkDAO.fetchProjectLinks(id)
      val part1track1 = Set(12345L, 12346L, 12347L).map(_.toString)
      val part1track2 = Set(12348L, 12349L, 12350L, 12351L).map(_.toString)
      val part1track1Links = projectLinks.filter(pl => part1track1.contains(pl.linkId)).map(_.id).toSet
      val part1Track2Links = projectLinks.filter(pl => part1track2.contains(pl.linkId)).map(_.id).toSet

      projectLinkDAO.updateProjectLinksStatus(part1track1Links, RoadAddressChangeType.Unchanged, "test")
      projectLinkDAO.updateProjectLinksStatus(part1Track2Links, RoadAddressChangeType.Unchanged, "test")

      val part2track1 = Set(12352L, 12353L).map(_.toString)
      val part2track2 = Set(12354L, 12355L).map(_.toString)
      val part2track1Links = projectLinks.filter(pl => part2track1.contains(pl.linkId)).map(_.id).toSet
      val part2Track2Links = projectLinks.filter(pl => part2track2.contains(pl.linkId)).map(_.id).toSet

      projectService.updateProjectLinks(id, part2track1Links, Seq(), RoadAddressChangeType.Transfer, "test",
        newRoadPart = RoadPart(9999, 2), newTrackCode = 1, userDefinedEndAddressM = None, administrativeClass = 1, discontinuity = 5, ely = Some(1L), roadName = None)
      projectService.updateProjectLinks(id, part2Track2Links, Seq(), RoadAddressChangeType.Transfer, "test",
        newRoadPart = RoadPart(9999, 2), newTrackCode = 2, userDefinedEndAddressM = None, administrativeClass = 1, discontinuity = 5, ely = Some(1L), roadName = None)
      /**
        * Tranfering adjacents of part1 to part2
        */
      val part1AdjacentToPart2IdRightSide = Set(12347L.toString)
      val part1AdjacentToPart2IdLeftSide = Set(12351L.toString)
      val part1AdjacentToPart2LinkRightSide = projectLinks.filter(pl => part1AdjacentToPart2IdRightSide.contains(pl.linkId)).map(_.id).toSet
      val part1AdjacentToPart2LinkLeftSide = projectLinks.filter(pl => part1AdjacentToPart2IdLeftSide.contains(pl.linkId)).map(_.id).toSet


      projectService.updateProjectLinks(id, part1AdjacentToPart2LinkRightSide, Seq(), RoadAddressChangeType.Transfer, "test", newRoadPart = RoadPart(9999, 2), newTrackCode = 1, userDefinedEndAddressM = None, administrativeClass = 1, discontinuity = 5, ely = Some(1L), roadName = None)
      projectService.updateProjectLinks(id, part1AdjacentToPart2LinkLeftSide,  Seq(), RoadAddressChangeType.Transfer, "test", newRoadPart = RoadPart(9999, 2), newTrackCode = 2, userDefinedEndAddressM = None, administrativeClass = 1, discontinuity = 5, ely = Some(1L), roadName = None)

      val projectLinks_ = projectLinkDAO.fetchProjectLinks(id)
      val projectLinks2 = ProjectSectionCalculator.assignAddrMValues(projectLinks_)

      val parts = projectLinks2.partition(_.roadPart === RoadPart(9999, 1))
      val part1tracks = parts._1.partition(_.track === Track.RightSide)
      part1tracks._1.maxBy(_.addrMRange.end).addrMRange.end should be(part1tracks._2.maxBy(_.addrMRange.end).addrMRange.end)
      val part2tracks = parts._2.partition(_.track === Track.RightSide)
      part2tracks._1.maxBy(_.addrMRange.end).addrMRange.end should be(part2tracks._2.maxBy(_.addrMRange.end).addrMRange.end)
    }
  }

  test("Test expireHistoryRows When link is renumbered Then set end date to old roadway") {
    runWithRollback {

      // Create roadway
      val linkId = 10000.toString
      val oldEndAddress = 100
      val roadway = Roadway(Sequences.nextRoadwayId, Sequences.nextRoadwayNumber, RoadPart(9999, 1), AdministrativeClass.State, Track.Combined, Discontinuity.Continuous, AddrMRange(0, oldEndAddress), reversed = false, DateTime.now().minusYears(10), None, "test", Some("Test Road"), 1, TerminationCode.NoTermination, DateTime.now().minusYears(10), None)

      roadwayDAO.create(Seq(roadway))

      // Create project link
      val roadAddressLength = roadway.addrMRange.end - roadway.addrMRange.start
      val projectId = Sequences.nextViiteProjectId
      val newLength = 110.123
      val newEndAddr = 110
      val projectLink = ProjectLink(Sequences.nextProjectLinkId, roadway.roadPart, roadway.track, roadway.discontinuity, AddrMRange(roadway.addrMRange.start, roadway.addrMRange.end + 10), roadway.addrMRange, Some(DateTime.now().plusMonths(1)), None, Some("test"), linkId, 0.0, newLength, SideCode.TowardsDigitizing, (RoadAddressCP, RoadAddressCP), (NoCP, NoCP), Seq(Point(0.0, 0.0), Point(0.0, newLength)), projectId, RoadAddressChangeType.Renumeration, roadway.administrativeClass, LinkGeomSource.NormalLinkInterface, newLength, roadway.id, 1234, roadway.ely, reversed = false, None, DateTime.now().minusMonths(10).getMillis, roadway.roadwayNumber, roadway.roadName, Some(roadAddressLength), Some(0), Some(newEndAddr), Some(roadway.track), Some(roadway.roadPart))

      // Check before change
      roadwayDAO.fetchAllByRoadwayId(Seq(roadway.id)).head.validTo should be(None)

      // Call the method to be tested
      projectService.expireHistoryRows(roadway.id)

      // Check results
      roadwayDAO.fetchAllByRoadwayId(Seq(roadway.id)).isEmpty should be(true)

      //createHistoryRows method expires the history rows for the roadway
      roadwayDAO.fetchAllByRoadwayNumbers(Set(roadway.roadwayNumber), withHistory = true).isEmpty should be (true)

    }
  }

  test("Test expireHistoryRows " +
       "When link is transfered and reversed " +
       "Then set end date to old roadway and set reverse flag relative to the current roadway.") {
    runWithRollback {
      // Create roadway
      val linkId          = 10000.toString
      val oldEndAddress   = 100
      val roadwayNumber   = Sequences.nextRoadwayNumber
      val currentRoadway  = Roadway(Sequences.nextRoadwayId, roadwayNumber, RoadPart(9999, 1), AdministrativeClass.State, Track.Combined, Discontinuity.Continuous, AddrMRange(0, oldEndAddress), reversed = false, DateTime.now().minusYears(10), None, "test", Some("Test Road"), 1, TerminationCode.NoTermination, DateTime.now().minusYears(10), None)
      val history_roadway = Seq(
                                Roadway(Sequences.nextRoadwayId, roadwayNumber, RoadPart(9999, 1), AdministrativeClass.Municipality, Track.Combined, Discontinuity.Continuous, AddrMRange(0, oldEndAddress), reversed = false, DateTime.now().minusYears(15), Some(DateTime.now().minusYears(10)), "test", Some("Test Road"), 1, TerminationCode.NoTermination, DateTime.now().minusYears(15), None),
                                Roadway(Sequences.nextRoadwayId, roadwayNumber, RoadPart(9999, 1), AdministrativeClass.State,        Track.Combined, Discontinuity.Continuous, AddrMRange(0, oldEndAddress), reversed = true, DateTime.now().minusYears(20), Some(DateTime.now().minusYears(15)), "test", Some("Test Road"), 1, TerminationCode.NoTermination, DateTime.now().minusYears(20), None)
                               )
      roadwayDAO.create(Seq(currentRoadway) ++ history_roadway)

      // Create project
      val rap = Project(0L, ProjectState.UpdatingToRoadNetwork, "TestProject", "TestUser", DateTime.parse("1901-01-01"), "TestUser", currentRoadway.startDate.plusYears(1), DateTime.now(), "Some additional info", Seq(), Seq(), None, elys = Set())
      val project = projectService.createRoadLinkProject(rap)
      val projectId = project.id
      projectReservedPartDAO.reserveRoadPart(projectId, currentRoadway.roadPart, "TestUser")

      val linearLocation = dummyLinearLocation(Sequences.nextLinearLocationId, currentRoadway.roadwayNumber, 0, linkId, 0.0, currentRoadway.addrMRange.end,0L)
      val projectLink = ProjectLink(Sequences.nextProjectLinkId, currentRoadway.roadPart, currentRoadway.track, Discontinuity.EndOfRoad, currentRoadway.addrMRange, currentRoadway.addrMRange, Some(DateTime.now().plusMonths(1)), None, Some("test"), linkId, 0.0, currentRoadway.addrMRange.end, SideCode.TowardsDigitizing, (RoadAddressCP, RoadAddressCP), (NoCP, NoCP), Seq(Point(0.0, 0.0), Point(0.0, currentRoadway.addrMRange.end)), projectId, RoadAddressChangeType.Transfer, currentRoadway.administrativeClass, LinkGeomSource.NormalLinkInterface, currentRoadway.addrMRange.end, currentRoadway.id, linearLocation.id, currentRoadway.ely, reversed = true, None, DateTime.now().minusMonths(10).getMillis, currentRoadway.roadwayNumber, currentRoadway.roadName, None, None, None, None, None)

      linearLocationDAO.create(Seq(linearLocation))
      projectLinkDAO.create(Seq(projectLink))

      // Check before change
      roadwayDAO.fetchAllByRoadwayId(Seq(currentRoadway.id)).head.validTo should be(None)

      when(mockNodesAndJunctionsService.expireObsoleteNodesAndJunctions(any[Seq[ProjectLink]], any[Option[DateTime]], any[String])).thenReturn(Seq())

      projectService.updateRoadwaysAndLinearLocationsWithProjectLinks(projectId)

      // Current roadway was is expired
      roadwayDAO.fetchAllByRoadwayId(Seq(currentRoadway.id)).isEmpty should be(true)

      val AllByRoadPart = roadwayDAO.fetchAllByRoadPart(currentRoadway.roadPart, withHistory = true)
      AllByRoadPart should have size 4

      val (historyRows, newCurrent) = AllByRoadPart.partition(_.endDate.isDefined)
      newCurrent should have size 1
      historyRows should have size 3

      newCurrent.foreach(_.addrMRange.end should be(currentRoadway.addrMRange.end))
      historyRows.foreach(_.addrMRange.end should be(currentRoadway.addrMRange.end))

      newCurrent.foreach(_.reversed should be(false))
      val (reversedHistory, notReversedHistory): (Seq[Roadway], Seq[Roadway]) = historyRows.partition(_.reversed)

      reversedHistory should have size 2
      notReversedHistory should have size 1

      notReversedHistory.head.startDate.getYear should be(DateTime.now().minusYears(20).getYear)

      val (municipalityHistory, stateHistory) = reversedHistory.partition(_.administrativeClass == AdministrativeClass.Municipality)
      municipalityHistory should have size 1
      municipalityHistory.head.startDate.getYear should be(DateTime.now().minusYears(15).getYear)
      stateHistory.head.startDate.getYear should be(DateTime.now().minusYears(10).getYear)
    }
  }

  test("Test expireHistoryRows " +
       "When an unchanged roadway with histories has administrative class change in the middle " +
       "Then current roadway and histories are expired and three new current roadways are created with the same length history rows.") {
    runWithRollback {
      /* The case "Administrative class change in the Middle of the Roadway" in https://extranet.vayla.fi/wiki/display/VIITE/Roadway+Number
      *  with history rows. */
      val linkId          = 10000.toString
      val oldEndAddress   = 300
      val roadwayNumber   = Sequences.nextRoadwayNumber
      val currentRoadway  = Roadway(Sequences.nextRoadwayId, roadwayNumber, RoadPart(9999, 1), AdministrativeClass.State, Track.Combined, Discontinuity.Continuous, AddrMRange(0, oldEndAddress), reversed = false, DateTime.now().minusYears(10).withTime(0, 0, 0, 0), None, "test", Some("Test Road"), 1, TerminationCode.NoTermination, DateTime.now().minusYears(10), None)
      val history_roadway = Seq(
        Roadway(Sequences.nextRoadwayId, roadwayNumber, RoadPart(9999, 1), AdministrativeClass.Municipality, Track.Combined, Discontinuity.Continuous, AddrMRange(0, oldEndAddress), reversed = false, DateTime.now().minusYears(15).withTime(0, 0, 0, 0), Some(DateTime.now().minusYears(10).withTime(0, 0, 0, 0)), "test", Some("Test Road"), 1, TerminationCode.NoTermination, DateTime.now().minusYears(15), None),
        Roadway(Sequences.nextRoadwayId, roadwayNumber, RoadPart(9999, 1), AdministrativeClass.State,        Track.Combined, Discontinuity.Continuous, AddrMRange(0, oldEndAddress), reversed = false, DateTime.now().minusYears(20).withTime(0, 0, 0, 0), Some(DateTime.now().minusYears(15).withTime(0, 0, 0, 0)), "test", Some("Test Road"), 1, TerminationCode.NoTermination, DateTime.now().minusYears(20), None)
      )
      roadwayDAO.create(Seq(currentRoadway) ++ history_roadway)

      // Create project link
      val rap = Project(0L, ProjectState.UpdatingToRoadNetwork, "TestProject", "TestUser", DateTime.parse("1901-01-01"), "TestUser", currentRoadway.startDate.plusYears(1), DateTime.now(), "Some additional info", Seq(), Seq(), None, elys = Set())
      val project = projectService.createRoadLinkProject(rap)
      val projectId = project.id

      val linearLocation = Seq(
        dummyLinearLocation(currentRoadway.roadwayNumber, 0, linkId,   0.0, currentRoadway.addrMRange.end).copy(id = linkId.toLong),
        dummyLinearLocation(currentRoadway.roadwayNumber, 0, linkId+1, 0.0, currentRoadway.addrMRange.end).copy(id = (linkId+1).toLong),
        dummyLinearLocation(currentRoadway.roadwayNumber, 0, linkId+2, 0.0, currentRoadway.addrMRange.end).copy(id = (linkId+2).toLong)
      )

      val projectLinks = Seq(
        ProjectLink(Sequences.nextProjectLinkId, currentRoadway.roadPart, currentRoadway.track, Discontinuity.Continuous, AddrMRange(  0, 100), AddrMRange(  0, 100), Some(DateTime.now().plusMonths(1)), None, Some("test"), linkId,   0.0, 100, SideCode.TowardsDigitizing, (RoadAddressCP, RoadAddressCP), (NoCP, NoCP), Seq(Point(0.0, 0.0), Point(0.0, currentRoadway.addrMRange.end)), projectId, RoadAddressChangeType.Transfer, currentRoadway.administrativeClass, LinkGeomSource.NormalLinkInterface, 100, currentRoadway.id, linearLocation(0).id, currentRoadway.ely, reversed = false, None, DateTime.now().minusMonths(10).getMillis, currentRoadway.roadwayNumber+1, currentRoadway.roadName, None, None, None, None, None),
        ProjectLink(Sequences.nextProjectLinkId, currentRoadway.roadPart, currentRoadway.track, Discontinuity.Continuous, AddrMRange(100, 200), AddrMRange(100, 200), Some(DateTime.now().plusMonths(1)), None, Some("test"), linkId+1, 0.0, 100, SideCode.TowardsDigitizing, (RoadAddressCP, RoadAddressCP), (NoCP, NoCP), Seq(Point(0.0, 0.0), Point(0.0, currentRoadway.addrMRange.end)), projectId, RoadAddressChangeType.Transfer, AdministrativeClass.Municipality,   LinkGeomSource.NormalLinkInterface, 100, currentRoadway.id, linearLocation(1).id, currentRoadway.ely, reversed = false, None, DateTime.now().minusMonths(10).getMillis, currentRoadway.roadwayNumber+2, currentRoadway.roadName, None, None, None, None, None),
        ProjectLink(Sequences.nextProjectLinkId, currentRoadway.roadPart, currentRoadway.track, Discontinuity.Continuous, AddrMRange(200, 300), AddrMRange(200, 300), Some(DateTime.now().plusMonths(1)), None, Some("test"), linkId+2, 0.0, 100, SideCode.TowardsDigitizing, (RoadAddressCP, RoadAddressCP), (NoCP, NoCP), Seq(Point(0.0, 0.0), Point(0.0, currentRoadway.addrMRange.end)), projectId, RoadAddressChangeType.Transfer, currentRoadway.administrativeClass, LinkGeomSource.NormalLinkInterface, 100, currentRoadway.id, linearLocation(2).id, currentRoadway.ely, reversed = false, None, DateTime.now().minusMonths(10).getMillis, currentRoadway.roadwayNumber+3, currentRoadway.roadName, None, None, None, None, None)
      )

      linearLocationDAO.create(linearLocation)
      projectReservedPartDAO.reserveRoadPart(projectId, currentRoadway.roadPart, "TestUser")

      projectLinkDAO.create(projectLinks)

      // Check before change
      roadwayDAO.fetchAllByRoadwayId(Seq(currentRoadway.id) ++ history_roadway.map(_.id)) foreach {_.validTo should be(None)}

      when(mockNodesAndJunctionsService.expireObsoleteNodesAndJunctions(any[Seq[ProjectLink]], any[Option[DateTime]], any[String])).thenReturn(Seq())

      projectService.updateRoadwaysAndLinearLocationsWithProjectLinks(projectId)

      // Current roadway and history rows are expired.
      roadwayDAO.fetchAllByRoadwayId(Seq(currentRoadway.id) ++ history_roadway.map(_.id)).toArray shouldBe empty

      val AllByRoadPart = roadwayDAO.fetchAllByRoadPart(currentRoadway.roadPart, withHistory = true)
      val (historyRows, newCurrent) = AllByRoadPart.partition(_.endDate.isDefined)

      newCurrent should have size 3
      historyRows should have size 7

      // Check newly created current addresses
      newCurrent.filter(hr => hr.addrMRange.start ==   0 && hr.addrMRange.end == 100) should have size 1
      newCurrent.filter(hr => hr.addrMRange.start == 100 && hr.addrMRange.end == 200) should have size 1
      newCurrent.filter(hr => hr.addrMRange.start == 200 && hr.addrMRange.end == 300) should have size 1

      // The same validFrom date for all
      AllByRoadPart.map(_.validFrom).distinct should have size 1

      val historiesByAddrM = historyRows.groupBy(_.addrMRange.start)

      // Check dates
      historiesByAddrM.values.foreach(hrs => {
        hrs.filter(hr => hr.startDate == history_roadway.head.startDate && hr.endDate == history_roadway.head.endDate) should have size 1
        hrs.filter(hr => hr.startDate == history_roadway.last.startDate && hr.endDate == history_roadway.last.endDate) should have size 1
      })

      // Check history addresses
      historyRows.filter(hr => hr.addrMRange.start ==   0 && hr.addrMRange.end == 100) should have size 2
      historyRows.filter(hr => hr.addrMRange.start == 100 && hr.addrMRange.end == 200) should have size 3
      historyRows.filter(hr => hr.addrMRange.start == 200 && hr.addrMRange.end == 300) should have size 2
    }
  }

  test("Test updateRoadwaysAndLinearLocationsWithProjectLinks " +
    "When ProjectLink is terminated " +
    "Then linear location with same link id should be expired.") {
    runWithRollback {
      // Create roadway
      val linkId          = 10000.toString
      val linkId2         = 10001.toString
      val roadwayNumber   = Sequences.nextRoadwayNumber
      val roadway  = Roadway(Sequences.nextRoadwayId, roadwayNumber, RoadPart(9999, 1), AdministrativeClass.State, Track.Combined, Discontinuity.Continuous, AddrMRange(0, 100), reversed = false, DateTime.now().minusYears(10), None, "test", Some("Test Road"), 1, TerminationCode.NoTermination, DateTime.now().minusYears(10), None)

      roadwayDAO.create(Seq(roadway))

      // Create project
      val rap = Project(0L, ProjectState.UpdatingToRoadNetwork, "TestProject", "TestUser", DateTime.parse("1901-01-01"), "TestUser", roadway.startDate.plusYears(1), DateTime.now(), "Some additional info", Seq(), Seq(), None, elys = Set())
      val project = projectService.createRoadLinkProject(rap)
      val projectId = project.id
      projectReservedPartDAO.reserveRoadPart(projectId, roadway.roadPart, "TestUser")

      // Create linear locations and project links
      val linearLocation = dummyLinearLocation(Sequences.nextLinearLocationId, roadway.roadwayNumber, 0, linkId, 0.0, 50.0,0L)
      val linearLocation2 = dummyLinearLocation(Sequences.nextLinearLocationId, roadway.roadwayNumber, 1, linkId2, 50.0, roadway.addrMRange.end,0L)
      val terminatedProjectLink  = ProjectLink(Sequences.nextProjectLinkId, roadway.roadPart, roadway.track, Discontinuity.Continuous, AddrMRange(roadway.addrMRange.start, 50), AddrMRange(roadway.addrMRange.start, 50), Some(DateTime.now().plusMonths(1)), None, Some("test"), linkId,   0.0,      50.0,              SideCode.TowardsDigitizing, (RoadAddressCP, RoadAddressCP), (NoCP, NoCP), Seq(Point(0.0, 0.0), Point(0.0, roadway.addrMRange.end)), projectId, RoadAddressChangeType.Termination, roadway.administrativeClass, LinkGeomSource.NormalLinkInterface, roadway.addrMRange.end, roadway.id, linearLocation.id,  roadway.ely, reversed = true, None, DateTime.now().minusMonths(10).getMillis, roadway.roadwayNumber, roadway.roadName, None, None, None, None, None)
      val transferredProjectLink = ProjectLink(Sequences.nextProjectLinkId, roadway.roadPart, roadway.track, Discontinuity.EndOfRoad,  AddrMRange(50,   roadway.addrMRange.end), AddrMRange(50,   roadway.addrMRange.end), Some(DateTime.now().plusMonths(1)), None, Some("test"), linkId2, 50.0, roadway.addrMRange.end, SideCode.TowardsDigitizing, (RoadAddressCP, RoadAddressCP), (NoCP, NoCP), Seq(Point(0.0, 0.0), Point(0.0, roadway.addrMRange.end)), projectId, RoadAddressChangeType.Transfer,    roadway.administrativeClass, LinkGeomSource.NormalLinkInterface, roadway.addrMRange.end, roadway.id, linearLocation2.id, roadway.ely, reversed = true, None, DateTime.now().minusMonths(10).getMillis, roadway.roadwayNumber, roadway.roadName, None, None, None, None, None)

      linearLocationDAO.create(Seq(linearLocation, linearLocation2))
      projectLinkDAO.create(Seq(terminatedProjectLink, transferredProjectLink))

      when(mockNodesAndJunctionsService.expireObsoleteNodesAndJunctions(any[Seq[ProjectLink]], any[Option[DateTime]], any[String])).thenReturn(Seq())

      // Run the method to update the project
      val updatedRoadParts = projectService.updateRoadwaysAndLinearLocationsWithProjectLinks(projectId)

      // Check that the terminated linear location is expired and the transferred linear location is not expired
      val linearLocationToExpire = linearLocationDAO.fetchByLinkId(Set(linkId))
      linearLocationToExpire.foreach { ll =>
        ll.validTo should not be None // Not "null"
      }
      val linearLocationToBeValid = linearLocationDAO.fetchByLinkId(Set(linkId2))
      linearLocationToBeValid.foreach { ll =>
        ll.validTo should be(None) // Should be "null"
      }
    }
  }

  test("Test updateRoadwaysAndLinearLocationsWithProjectLinks " +
       "When ProjectLinks have a split " +
       "Then split should be merged and geometry points in the same order as before the split.") {
    runWithRollback {

      val roadPart       = RoadPart(19511, 1)
      val createdBy      = "UnitTest"
      val roadName       = None
      val projectId      = Sequences.nextViiteProjectId
      val linkIdToTest   = 12179260.toString

      val projectLinks = Seq(
        ProjectLink(1025,roadPart,Track.RightSide,Discontinuity.Continuous,AddrMRange(3689,3699),AddrMRange(3825,3835),None,None,Some(createdBy),linkIdToTest, 0.0  ,10.117,SideCode.TowardsDigitizing,(NoCP,NoCP         ),(NoCP,JunctionPointCP),List(Point(393383.0,7288225.0,0.0), Point(393376.0,7288232.0,0.0)),projectId,RoadAddressChangeType.Transfer,AdministrativeClass.State, LinkGeomSource.FrozenLinkInterface,10.117,57052,393536,14,reversed = false,Some(linkIdToTest),1615244419000L,13659596,roadName,None,None,None,None,None),
        ProjectLink(1028,roadPart,Track.RightSide,Discontinuity.Continuous,AddrMRange(3699,3719),AddrMRange(3835,3855),None,None,Some(createdBy),linkIdToTest,10.117,30.351,SideCode.TowardsDigitizing,(NoCP,RoadAddressCP),(NoCP,JunctionPointCP),List(Point(393376.0,7288232.0,0.0), Point(393372.0,7288235.0,0.0), Point(393361.0,7288245.0,0.0)),projectId,RoadAddressChangeType.Transfer,AdministrativeClass.State, LinkGeomSource.FrozenLinkInterface,20.234,57052,393536,14,reversed = false,Some(linkIdToTest),1615244419000L,13659596,roadName,None,None,None,None,None)
      )

      val roadways = Seq(
        Roadway(57052,13659596,RoadPart(19511,1),AdministrativeClass.State,Track.RightSide,Discontinuity.Continuous,AddrMRange(3659,3855),reversed = false,DateTime.parse("2016-03-01T00:00:00.000+02:00"),None,createdBy,roadName,14,TerminationCode.NoTermination,DateTime.parse("2016-03-30T00:00:00.000+03:00"),None)
      )

      val linearLocations = Seq(
        LinearLocation(393536, 2.0, linkIdToTest, 0.0, 30.351, SideCode.TowardsDigitizing, 1615244419000L, (CalibrationPointReference(None,None),CalibrationPointReference(Some(3855),Some(JunctionPointCP))), List(Point(393383.0,7288225.0,0.0), Point(393361.0,7288245.0,0.0)), LinkGeomSource.FrozenLinkInterface, 13659596, Some(DateTime.parse("2016-03-30T00:00:00.000+03:00")), None)
      )

      // Create project
      val rap = Project(projectId, ProjectState.UpdatingToRoadNetwork, "TestProject", createdBy, DateTime.parse("1901-01-01"), createdBy, DateTime.parse("1971-01-01"), DateTime.now(), "Some additional info", Seq(), Seq(), None, elys = Set())
      val project = projectDAO.create(rap)
      projectReservedPartDAO.reserveRoadPart(projectId, roadPart, "UnitTest")

      roadwayDAO.create(roadways)
      linearLocationDAO.create(linearLocations)
      projectLinkDAO.create(projectLinks)

      when(mockNodesAndJunctionsService.expireObsoleteNodesAndJunctions(any[Seq[ProjectLink]], any[Option[DateTime]], any[String])).thenReturn(Seq())

      projectService.updateRoadwaysAndLinearLocationsWithProjectLinks(projectId)

      val linearLocationsTotest = linearLocationDAO.fetchByLinkId(Set(linkIdToTest))

      // Geometries should be in address order after projectlink split is merged.
      linearLocationsTotest should have size 1
      linearLocationsTotest.head.getFirstPoint shouldBe linearLocations.head.getFirstPoint
      linearLocationsTotest.head.getLastPoint  shouldBe linearLocations.head.getLastPoint
    }
  }

  test("Test expireHistoryRows When expiring one roadway by id Then it should be expired by validTo date") {
    runWithRollback {

      val roadLink = RoadLink(5170939L.toString, Seq(Point(535605.272, 6982204.22, 85.90899999999965)), 540.3960283713503, AdministrativeClass.State, TrafficDirection.AgainstDigitizing, Some("25.06.2015 03:00:00"), Some(modifiedBy), LifecycleStatus.InUse, LinkGeomSource.NormalLinkInterface, 749, "")

      val projectId = Sequences.nextViiteProjectId

      val roadwayId = Sequences.nextRoadwayId

      val roadAddressProject = Project(projectId, ProjectState.apply(1), "TestProject", "TestUser", DateTime.now(), "TestUser", DateTime.parse("1970-01-01"), DateTime.now(), "Some additional info", Seq(), Seq(), None, elys = Set())

      val projectLink = dummyProjectLink(RoadPart(1, 1), Track.Combined, Discontinuity.EndOfRoad, AddrMRange(0, 100), Some(DateTime.parse("1970-01-01")), None, 12345.toString, 0, 100, SideCode.TowardsDigitizing, RoadAddressChangeType.Unchanged, projectId, AdministrativeClass.State, Seq(Point(0.0, 0.0), Point(0.0, 100.0)))

      val roadway = dummyRoadway(roadwayNumber =1234l, roadPart = RoadPart(1, 1), addrMRange = AddrMRange(0, 100), startDate = DateTime.now(), endDate = None, roadwayId = roadwayId)

      roadwayDAO.create(Seq(roadway))

      when(mockRoadLinkService.getRoadLinksHistoryFromVVH(any[Set[String]])).thenReturn(Seq())
      when(mockRoadLinkService.getRoadLinksByLinkIds(any[Set[String]])).thenAnswer(
        toMockAnswer(Seq(projectLink), roadLink)
      )
      val historicRoadsUpdated = projectService.expireHistoryRows(roadwayId)
      historicRoadsUpdated should be (1)
    }
  }

  test("Test Road names should not have valid road name for any roadnumber after TR response") {
    runWithRollback {
      val projectId = Sequences.nextViiteProjectId
      runUpdateToDb("""INSERT INTO ROAD_NAME VALUES (nextval('ROAD_NAME_SEQ'), 66666, 'ROAD TEST', TIMESTAMP '2018-03-23 12:26:36.000000', null, TIMESTAMP '2018-03-23 12:26:36.000000', null, 'test user', TIMESTAMP '2018-03-23 12:26:36.000000')""")

      runUpdateToDb(s"""INSERT INTO PROJECT VALUES($projectId, 2, 'test project', 'silari', TIMESTAMP '2018-03-23 11:36:15.000000', '-', TIMESTAMP '2018-03-23 12:26:33.000000', NULL, NULL, NULL, 533406.572, 6994060.048, 12)""")
      runUpdateToDb(s"""INSERT INTO PROJECT_RESERVED_ROAD_PART VALUES (${Sequences.nextViitePrimaryKeySeqValue}, 66666, 1, $projectId, '-')""")

      runUpdateToDb(s"""INSERT INTO PROJECT_LINK (ID, PROJECT_ID, TRACK, DISCONTINUITY_TYPE, ROAD_NUMBER, ROAD_PART_NUMBER,
          START_ADDR_M, END_ADDR_M, CREATED_BY, MODIFIED_BY, CREATED_DATE, MODIFIED_DATE, STATUS,
          ADMINISTRATIVE_CLASS, ROADWAY_ID, LINEAR_LOCATION_ID, CONNECTED_LINK_ID, ELY, REVERSED, SIDE, START_MEASURE, END_MEASURE,
          LINK_ID, ADJUSTED_TIMESTAMP, LINK_SOURCE, GEOMETRY, ORIGINAL_START_ADDR_M, ORIGINAL_END_ADDR_M, ROADWAY_NUMBER,
          START_CALIBRATION_POINT, END_CALIBRATION_POINT, ORIG_START_CALIBRATION_POINT, ORIG_END_CALIBRATION_POINT)
        VALUES (${Sequences.nextProjectLinkId}, $projectId, 0, 5, 66666, 1,
          0, 86, 'test user', 'test user', TIMESTAMP '2018-03-23 12:26:36.000000', TIMESTAMP '2018-03-23 00:00:00.000000', 2,
          1, NULL, NULL, NULL, 8, 0, NULL, 0, 85.617,
          NULL, 1543328166000, 1, NULL, 0, 0, NULL,
          3, 3, 0, 0)""")

      runUpdateToDb(s"""INSERT INTO PROJECT_LINK_NAME VALUES (nextval('PROJECT_LINK_NAME_SEQ'), $projectId, 66666, 'ROAD TEST')""")
      val namesBeforeUpdate = RoadNameDAO.getLatestRoadName(66666)
      val changeInfos = List(
        RoadwayChangeInfo(RoadAddressChangeType.New,
          source = dummyRoadwayChangeSection(Some(RoadPart(66666, 1)), Some(0L), Some(0L), Some(100L), Some(AdministrativeClass.apply(1)), Some(Discontinuity.Continuous), Some(8L)),
          target = dummyRoadwayChangeSection(Some(RoadPart(66666, 1)), Some(0L), Some(0L), Some(100L), Some(AdministrativeClass.apply(1)), Some(Discontinuity.Continuous), Some(8L)),
          Discontinuity.Continuous, AdministrativeClass.apply(1), reversed = false, 1)
      )

      val changes = List(
        ProjectRoadwayChange(projectId, Some("test project"), 8, "Test", DateTime.now(), changeInfos.head, DateTime.now())
      )

      val projectBefore = projectService.getSingleProjectById(projectId)
      projectService.handleNewRoadNames(changes)
      val project = projectService.getSingleProjectById(projectId)
      val validNamesAfterUpdate = RoadNameDAO.getCurrentRoadNamesByRoadNumber(66666)
      validNamesAfterUpdate.size should be(1)
      validNamesAfterUpdate.head.roadName should be(namesBeforeUpdate.get.roadName)
    }
  }

  test("Test fillRoadNames When creating one project link for one new road name Then method should get the road name of the created project links") {
    val roadPart = RoadPart(5, 207)
    val testRoadName = "forTestingPurposes"
    runWithRollback {
      val rap = Project(0L, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("1901-01-01"), "TestUser", DateTime.parse("1971-01-01"), DateTime.now(), "Some additional info", Seq(), Seq(), None, elys = Set())
      val project = projectService.createRoadLinkProject(rap)
      val projectId = project.id

      mockForProject(projectId, roadwayAddressMapper.getRoadAddressesByRoadway(roadwayDAO.fetchAllByRoadPart(roadPart)).map(toProjectLink(project)))
      projectService.saveProject(project.copy(reservedParts = Seq(ProjectReservedPart(0L, roadPart, Some(0L), Some(Discontinuity.Continuous), Some(8L), None, None, None, None)), elys = Set()))
      val projectLinks = projectLinkDAO.fetchProjectLinks(projectId)
      ProjectLinkNameDAO.create(projectId, roadPart.roadNumber, testRoadName)

      projectService.fillRoadNames(projectLinks.head).roadName.get should be(testRoadName)
    }
  }

  test("Test fillRoadNames When creating one project link for one new road name Then through RoadNameDao the road name of the created project links should be same as the latest one") {
    val roadPart = RoadPart(5, 207)
    runWithRollback {
      val rap = Project(0L, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("1901-01-01"), "TestUser", DateTime.parse("1970-01-01"), DateTime.now(), "Some additional info", Seq(), Seq(), None, elys = Set())
      val project = projectService.createRoadLinkProject(rap)
      val id = project.id
      mockForProject(project.id, roadwayAddressMapper.getRoadAddressesByRoadway(roadwayDAO.fetchAllByRoadPart(roadPart)).map(toProjectLink(project)))
      projectService.saveProject(project.copy(reservedParts = Seq(ProjectReservedPart(0L, roadPart, Some(0L), Some(Discontinuity.Continuous), Some(8L), None, None, None, None)), elys = Set()))
      val projectLinks = projectLinkDAO.fetchProjectLinks(id)

      projectService.fillRoadNames(projectLinks.head).roadName.get should be(RoadNameDAO.getLatestRoadName(roadPart.roadNumber).get.roadName)
    }
  }

  test("Test getLatestRoadName When having no road name for given road number Then road name should not be saved on TR success response if road number > 70.000 (even though it has no name)") {
    runWithRollback {
      val projectId = Sequences.nextViiteProjectId
      runUpdateToDb(s"""INSERT INTO PROJECT VALUES($projectId, 2, 'test project', 'silari', TIMESTAMP '2018-03-23 11:36:15.000000', '-', TIMESTAMP '2018-03-23 12:26:33.000000', NULL, TIMESTAMP '2018-03-23 00:00:00.000000', NULL, 533406.572, 6994060.048, 12)""")
      runUpdateToDb(s"""INSERT INTO PROJECT_RESERVED_ROAD_PART VALUES (${Sequences.nextViitePrimaryKeySeqValue}, 70001, 1, $projectId, '-')""")

      runUpdateToDb(s"""INSERT INTO PROJECT_LINK (ID, PROJECT_ID, TRACK, DISCONTINUITY_TYPE, ROAD_NUMBER, ROAD_PART_NUMBER,
          START_ADDR_M, END_ADDR_M, CREATED_BY, MODIFIED_BY, CREATED_DATE, MODIFIED_DATE, STATUS,
          ADMINISTRATIVE_CLASS, ROADWAY_ID, LINEAR_LOCATION_ID, CONNECTED_LINK_ID, ELY, REVERSED, SIDE, START_MEASURE, END_MEASURE,
          LINK_ID, ADJUSTED_TIMESTAMP, LINK_SOURCE, GEOMETRY, ORIGINAL_START_ADDR_M, ORIGINAL_END_ADDR_M, ROADWAY_NUMBER,
          START_CALIBRATION_POINT, END_CALIBRATION_POINT, ORIG_START_CALIBRATION_POINT, ORIG_END_CALIBRATION_POINT)
        VALUES (${Sequences.nextProjectLinkId}, $projectId, 0, 5, 70001, 1,
          0, 86, 'test user', 'test user', TIMESTAMP '2018-03-23 12:26:36.000000', TIMESTAMP '2018-03-23 00:00:00.000000', 5,
          1, NULL, NULL, NULL, 8, 0, 2, 0, 85.617,
          5170979, 1500079296000, 1, NULL, 0, 86, NULL,
          3, 3, 3, 3)""")

      runUpdateToDb(s"""INSERT INTO PROJECT_LINK_NAME VALUES (nextval('PROJECT_LINK_NAME_SEQ'), $projectId, 70001, NULL)""")
      val namesBeforeUpdate = RoadNameDAO.getLatestRoadName(70001)
      namesBeforeUpdate.isEmpty should be(true)
      when(mockNodesAndJunctionsService.expireObsoleteNodesAndJunctions(any[Seq[ProjectLink]], any[Option[DateTime]], any[String])).thenReturn(Seq())
      projectDAO.updateProjectStatus(projectId, UpdatingToRoadNetwork)
      projectService.updateRoadwaysAndLinearLocationsWithProjectLinks(projectId)

      val project = projectService.getSingleProjectById(projectId)
      val namesAfterUpdate = RoadNameDAO.getLatestRoadName(70001)
      project.get.statusInfo should be(None)
      namesAfterUpdate.isEmpty should be(true)
    }
  }

  test("Test getLatestRoadName road name exists on TR success response") {
    runWithRollback {
      val projectId = Sequences.nextViiteProjectId
      runUpdateToDb("""INSERT INTO ROAD_NAME VALUES (nextval('ROAD_NAME_SEQ'), 66666, 'road name test', TIMESTAMP '2018-03-23 12:26:36.000000', null, TIMESTAMP '2018-03-23 12:26:36.000000', null, 'test user', TIMESTAMP '2018-03-23 12:26:36.000000')""")

      runUpdateToDb(s"""INSERT INTO PROJECT VALUES($projectId, 2, 'test project', 'silari', TIMESTAMP '2018-03-23 11:36:15.000000', '-', TIMESTAMP '2018-03-23 12:26:33.000000', NULL, TIMESTAMP '2018-03-23 00:00:00.000000', NULL, 533406.572, 6994060.048, 12)""")
      runUpdateToDb(s"""INSERT INTO PROJECT_RESERVED_ROAD_PART VALUES (${Sequences.nextViitePrimaryKeySeqValue}, 66666, 1, $projectId, '-')""")

      runUpdateToDb(s"""INSERT INTO PROJECT_LINK (ID, PROJECT_ID, TRACK, DISCONTINUITY_TYPE, ROAD_NUMBER, ROAD_PART_NUMBER,
          START_ADDR_M, END_ADDR_M, CREATED_BY, MODIFIED_BY, CREATED_DATE, MODIFIED_DATE, STATUS,
          ADMINISTRATIVE_CLASS, ROADWAY_ID, LINEAR_LOCATION_ID, CONNECTED_LINK_ID, ELY, REVERSED, SIDE, START_MEASURE, END_MEASURE,
          LINK_ID, ADJUSTED_TIMESTAMP, LINK_SOURCE, GEOMETRY, ORIGINAL_START_ADDR_M, ORIGINAL_END_ADDR_M, ROADWAY_NUMBER,
          START_CALIBRATION_POINT, END_CALIBRATION_POINT, ORIG_START_CALIBRATION_POINT, ORIG_END_CALIBRATION_POINT)
        VALUES (${Sequences.nextProjectLinkId}, $projectId, 0, 5, 66666, 1,
          0, 86, 'test user', 'test user', TIMESTAMP '2018-03-23 12:26:36.000000', TIMESTAMP '2018-03-23 00:00:00.000000', 2,
          1, NULL, NULL, NULL, 8, 0, 2, 0, 85.617,
          5170979, 1500079296000, 1, NULL, 0, 86, NULL,
          3, 3, 3, 3)""")

      runUpdateToDb(s"""INSERT INTO PROJECT_LINK_NAME VALUES (nextval('PROJECT_LINK_NAME_SEQ'), $projectId, 66666, 'another road name test')""")

      val changeInfos = List(
        RoadwayChangeInfo(RoadAddressChangeType.New,
          source = dummyRoadwayChangeSection(Some(RoadPart(66666, 1)), Some(0L), Some(0L), Some(100L), Some(AdministrativeClass.apply(1)), Some(Discontinuity.Continuous), Some(8L)),
          target = dummyRoadwayChangeSection(Some(RoadPart(66666, 1)), Some(0L), Some(0L), Some(100L), Some(AdministrativeClass.apply(1)), Some(Discontinuity.Continuous), Some(8L)),
          Discontinuity.Continuous, AdministrativeClass.apply(1), reversed = false, 1)
      )

      val changes = List(
        ProjectRoadwayChange(projectId, Some("test project"), 8, "Test", DateTime.now(), changeInfos.head, DateTime.now())
      )

      val namesBeforeUpdate = RoadNameDAO.getLatestRoadName(66666)
      val projectBefore = projectService.getSingleProjectById(projectId)
      projectService.handleNewRoadNames(changes)
      val namesAfterUpdate = RoadNameDAO.getLatestRoadName(66666)
      val project = projectService.getSingleProjectById(projectId)
      namesAfterUpdate.get.roadName should be(namesBeforeUpdate.get.roadName)
    }
  }

  test("Test getLatestRoadName When existing TR success response Then the road name should be saved") {
    runWithRollback {
      val projectId = Sequences.nextViiteProjectId
      runUpdateToDb(s"""INSERT INTO PROJECT VALUES($projectId, 2, 'test project', 'silari', TIMESTAMP '2018-03-23 11:36:15.000000', '-', TIMESTAMP '2018-03-23 12:26:33.000000', NULL, TIMESTAMP '2018-03-23 00:00:00.000000', NULL, 533406.572, 6994060.048, 12)""")
      runUpdateToDb(s"""INSERT INTO PROJECT_RESERVED_ROAD_PART VALUES (${Sequences.nextViitePrimaryKeySeqValue}, 66666, 1, $projectId, '-')""")

      runUpdateToDb(s"""INSERT INTO PROJECT_LINK (ID, PROJECT_ID, TRACK, DISCONTINUITY_TYPE, ROAD_NUMBER, ROAD_PART_NUMBER,
          START_ADDR_M, END_ADDR_M, CREATED_BY, MODIFIED_BY, CREATED_DATE, MODIFIED_DATE, STATUS,
          ADMINISTRATIVE_CLASS, ROADWAY_ID, LINEAR_LOCATION_ID, CONNECTED_LINK_ID, ELY, REVERSED, SIDE, START_MEASURE, END_MEASURE,
          LINK_ID, ADJUSTED_TIMESTAMP, LINK_SOURCE, GEOMETRY, ORIGINAL_START_ADDR_M, ORIGINAL_END_ADDR_M, ROADWAY_NUMBER,
          START_CALIBRATION_POINT, END_CALIBRATION_POINT, ORIG_START_CALIBRATION_POINT, ORIG_END_CALIBRATION_POINT)
        VALUES (${Sequences.nextProjectLinkId}, $projectId, 0, 5, 66666, 1,
          0, 86, 'test user', 'test user', TIMESTAMP '2018-03-23 12:26:36.000000', TIMESTAMP '2018-03-23 00:00:00.000000', 2,
          1, NULL, NULL, NULL, 8, 0, 2, 0, 85.617,
          5170979, 1500079296000, 1, NULL, 0, 86, NULL,
          3, 3, 3, 3)""")

      runUpdateToDb(s"""INSERT INTO PROJECT_LINK_NAME VALUES (nextval('PROJECT_LINK_NAME_SEQ'), $projectId, 66666, 'road name test')""")
      val changeInfos = List(
        RoadwayChangeInfo(RoadAddressChangeType.New,
          source = dummyRoadwayChangeSection(Some(RoadPart(66666, 1)), Some(0L), Some(0L), Some(100L), Some(AdministrativeClass.apply(1)), Some(Discontinuity.Continuous), Some(8L)),
          target = dummyRoadwayChangeSection(Some(RoadPart(66666, 1)), Some(0L), Some(0L), Some(100L), Some(AdministrativeClass.apply(1)), Some(Discontinuity.Continuous), Some(8L)),
          Discontinuity.Continuous, AdministrativeClass.apply(1), reversed = false, 1)
      )

      val changes = List(
        ProjectRoadwayChange(projectId, Some("test project"), 8, "Test", DateTime.now(), changeInfos.head, DateTime.now())
      )

      val namesBeforeUpdate = RoadNameDAO.getLatestRoadName(66666)
      val projectBefore = projectService.getSingleProjectById(projectId)
      projectService.handleNewRoadNames(changes)
      val namesAfterUpdate = RoadNameDAO.getLatestRoadName(66666)
      val project = projectService.getSingleProjectById(projectId)
      project.get.statusInfo should be(None)
      namesAfterUpdate.get.roadName should be("road name test")
    }
  }

  test("Test getLatestRoadName When existing TR success response Then multiple names should be saved") {
    runWithRollback {
      val projectId = Sequences.nextViiteProjectId
      runUpdateToDb(s"""INSERT INTO PROJECT VALUES($projectId, 2, 'test project', 'silari', TIMESTAMP '2018-03-23 11:36:15.000000', '-', TIMESTAMP '2018-03-23 12:26:33.000000', NULL, TIMESTAMP '2018-03-23 00:00:00.000000', NULL, 533406.572, 6994060.048, 12)""")
      runUpdateToDb(s"""INSERT INTO PROJECT_RESERVED_ROAD_PART VALUES (${Sequences.nextViitePrimaryKeySeqValue}, 66666, 1, $projectId, '-')""")
      runUpdateToDb(s"""INSERT INTO PROJECT_RESERVED_ROAD_PART VALUES (${Sequences.nextViitePrimaryKeySeqValue}, 55555, 1, $projectId, '-')""")

      runUpdateToDb(s"""INSERT INTO PROJECT_LINK (ID, PROJECT_ID, TRACK, DISCONTINUITY_TYPE, ROAD_NUMBER, ROAD_PART_NUMBER,
          START_ADDR_M, END_ADDR_M, CREATED_BY, MODIFIED_BY, CREATED_DATE, MODIFIED_DATE, STATUS,
          ADMINISTRATIVE_CLASS, ROADWAY_ID, LINEAR_LOCATION_ID, CONNECTED_LINK_ID, ELY, REVERSED, SIDE, START_MEASURE, END_MEASURE,
          LINK_ID, ADJUSTED_TIMESTAMP, LINK_SOURCE, GEOMETRY, ORIGINAL_START_ADDR_M, ORIGINAL_END_ADDR_M, ROADWAY_NUMBER,
          START_CALIBRATION_POINT, END_CALIBRATION_POINT, ORIG_START_CALIBRATION_POINT, ORIG_END_CALIBRATION_POINT)
        VALUES (${Sequences.nextProjectLinkId}, $projectId, 0, 5, 66666, 1,
          0, 86, 'test user', 'test user', TIMESTAMP '2018-03-23 12:26:36.000000', TIMESTAMP '2018-03-23 00:00:00.000000', 2,
          1, NULL, NULL, NULL, 8, 0, 2, 0, 85.617,
          5170979, 1500079296000, 1, NULL, 0, 86, NULL,
          3, 3, 3, 3)""")

      runUpdateToDb(s"""INSERT INTO PROJECT_LINK (ID, PROJECT_ID, TRACK, DISCONTINUITY_TYPE, ROAD_NUMBER, ROAD_PART_NUMBER,
          START_ADDR_M, END_ADDR_M, CREATED_BY, MODIFIED_BY, CREATED_DATE, MODIFIED_DATE, STATUS,
          ADMINISTRATIVE_CLASS, ROADWAY_ID, LINEAR_LOCATION_ID, CONNECTED_LINK_ID, ELY, REVERSED, SIDE, START_MEASURE, END_MEASURE,
          LINK_ID, ADJUSTED_TIMESTAMP, LINK_SOURCE, GEOMETRY, ORIGINAL_START_ADDR_M, ORIGINAL_END_ADDR_M, ROADWAY_NUMBER,
          START_CALIBRATION_POINT, END_CALIBRATION_POINT, ORIG_START_CALIBRATION_POINT, ORIG_END_CALIBRATION_POINT)
        VALUES (${Sequences.nextProjectLinkId}, $projectId, 0, 5, 55555, 1,
          0, 86, 'test user', 'test user', TIMESTAMP '2018-03-23 12:26:36.000000', TIMESTAMP '2018-03-23 00:00:00.000000', 2,
          1, NULL, NULL, NULL, 8, 0, 2, 0, 85.617,
          5170980, 1500079296000, 1, NULL, 0, 86, NULL,
          3, 3, 3, 3)""")

      runUpdateToDb(s"""INSERT INTO PROJECT_LINK_NAME VALUES (nextval('PROJECT_LINK_NAME_SEQ'), $projectId, 66666, 'road name test')""")
      runUpdateToDb(s"""INSERT INTO PROJECT_LINK_NAME VALUES (nextval('PROJECT_LINK_NAME_SEQ'), $projectId, 55555, 'road name test2')""")
      RoadNameDAO.getLatestRoadName(55555).isEmpty should be (true)
      RoadNameDAO.getLatestRoadName(66666).isEmpty should be (true)
      val changeInfos = List(
        RoadwayChangeInfo(RoadAddressChangeType.New,
          source = dummyRoadwayChangeSection(Some(RoadPart(66666, 1)), Some(0L), Some(0L), Some(100L), Some(AdministrativeClass.apply(1)), Some(Discontinuity.Continuous), Some(8L)),
          target = dummyRoadwayChangeSection(Some(RoadPart(66666, 1)), Some(0L), Some(0L), Some(100L), Some(AdministrativeClass.apply(1)), Some(Discontinuity.Continuous), Some(8L)),
          Discontinuity.Continuous, AdministrativeClass.apply(1), reversed = false, 1),
        RoadwayChangeInfo(RoadAddressChangeType.New,
          source = dummyRoadwayChangeSection(Some(RoadPart(55555, 1)), Some(0L), Some(0L), Some(100L), Some(AdministrativeClass.apply(1)), Some(Discontinuity.Continuous), Some(8L)),
          target = dummyRoadwayChangeSection(Some(RoadPart(55555, 1)), Some(0L), Some(0L), Some(100L), Some(AdministrativeClass.apply(1)), Some(Discontinuity.Continuous), Some(8L)),
          Discontinuity.Continuous, AdministrativeClass.apply(1), reversed = false, 1)
      )

      val changes = List(
        ProjectRoadwayChange(projectId, Some("test project"), 8, "Test", DateTime.now(), changeInfos.head, DateTime.now()),
        ProjectRoadwayChange(projectId, Some("test project"), 8, "Test", DateTime.now(), changeInfos(1), DateTime.now())
      )

      val projectBefore = projectService.getSingleProjectById(projectId)
      projectService.handleNewRoadNames(changes)
      val project = projectService.getSingleProjectById(projectId)
      val namesAfterUpdate55555 = RoadNameDAO.getLatestRoadName(55555)
      val namesAfterUpdate66666 = RoadNameDAO.getLatestRoadName(66666)
      project.get.statusInfo should be(None)
      namesAfterUpdate55555.get.roadName should be("road name test2")
      namesAfterUpdate66666.get.roadName should be("road name test")
    }
  }

  test("Test getProjectLinks When doing some operations (Unchanged with termination test, repeats termination update), Then the calibration points are cleared and moved to correct positions") {
    var count = 0
    val roadLink = RoadLink(5170939L.toString, Seq(Point(535605.272, 6982204.22, 85.90899999999965)), 540.3960283713503, AdministrativeClass.State, TrafficDirection.AgainstDigitizing, Some("25.06.2015 03:00:00"), Some(modifiedBy), LifecycleStatus.InUse, LinkGeomSource.NormalLinkInterface, 749, "")
    runWithRollback {
      val RP205 = RoadPart(5, 205)
      val RP206 = RoadPart(5, 206)
      val countCurrentProjects = projectService.getAllProjects
      val id = 0
      val addresses = Seq(ProjectReservedPart(5: Long, RP205, Some(5L), Some(Discontinuity.Continuous), Some(8L), newLength = None, newDiscontinuity = None, newEly = None),
        ProjectReservedPart(5: Long, RP206, Some(5L), Some(Discontinuity.Continuous), Some(8L), newLength = None, newDiscontinuity = None, newEly = None))
      val project = Project(id, ProjectState.apply(1), "TestProject", "TestUser", DateTime.now(), "TestUser", DateTime.parse("2017-01-01"), DateTime.now(), "Some additional info", Seq(), Seq(), None, elys = Set())
      val savedProject = projectService.createRoadLinkProject(project)
      mockForProject(savedProject.id, (roadwayAddressMapper.getRoadAddressesByLinearLocation(linearLocationDAO.fetchByRoadways(roadwayDAO.fetchAllBySection(RP205).map(_.roadwayNumber).toSet)) ++ roadwayAddressMapper.getRoadAddressesByLinearLocation(linearLocationDAO.fetchByRoadways(roadwayDAO.fetchAllBySection(RP206).map(_.roadwayNumber).toSet))).map(toProjectLink(savedProject)))
      projectService.saveProject(savedProject.copy(reservedParts = addresses, elys = Set()))
      val countAfterInsertProjects = projectService.getAllProjects
      count = countCurrentProjects.size + 1
      countAfterInsertProjects.size should be(count)
      projectService.allLinksHandled(savedProject.id) should be(false)
      projectService.getSingleProjectById(savedProject.id).nonEmpty should be(true)
      projectService.getSingleProjectById(savedProject.id).get.reservedParts.nonEmpty should be(true)
      val projectLinks = projectLinkDAO.fetchProjectLinks(savedProject.id)
      val partitioned = projectLinks.partition(_.roadPart == RP205)
      val linkIds205 = partitioned._1.map(_.linkId).toSet
      val linkIds206 = partitioned._2.map(_.linkId).toSet
      reset(mockRoadLinkService)
      when(mockRoadLinkService.getRoadLinksHistoryFromVVH(any[Set[String]])).thenReturn(Seq())
      when(mockRoadLinkService.getRoadLinksByLinkIds(any[Set[String]])).thenAnswer(
        toMockAnswer(projectLinks, roadLink)
      )
      projectService.updateProjectLinks(savedProject.id, Set(), linkIds205.toSeq,      RoadAddressChangeType.Unchanged,   "-", RP205, 0, Option.empty[Int]) should be(None)
      projectService.allLinksHandled(savedProject.id) should be(false)
      projectService.updateProjectLinks(savedProject.id, Set(), linkIds206.toSeq,      RoadAddressChangeType.Unchanged,   "-", RP206, 0, Option.empty[Int]) should be(None)
      projectService.allLinksHandled(savedProject.id) should be(true)
      projectService.updateProjectLinks(savedProject.id, Set(), Seq(5168573.toString), RoadAddressChangeType.Termination, "-", RP206, 0, Option.empty[Int]) should be(None)
      projectService.allLinksHandled(savedProject.id) should be(true)
      val updatedProjectLinks = projectLinkDAO.fetchProjectLinks(savedProject.id)
      updatedProjectLinks.exists { x => x.status == RoadAddressChangeType.Unchanged } should be(true)
      updatedProjectLinks.exists { x => x.status == RoadAddressChangeType.Termination } should be(true)
      val calculatedProjectLinks = ProjectSectionCalculator.assignAddrMValues(updatedProjectLinks)
      calculatedProjectLinks.filter(pl => pl.linkId == 5168579.toString).head.calibrationPoints should be((None, Some(ProjectCalibrationPoint(5168579.toString, 15.173, 4681, RoadAddressCP))))

      projectService.updateProjectLinks(savedProject.id, Set(), Seq(5168579.toString), RoadAddressChangeType.Termination, "-", RoadPart(0, 0), 0, Option.empty[Int])
      val updatedProjectLinks_ = projectLinkDAO.fetchProjectLinks(savedProject.id).toList
      val updatedProjectLinks2 = ProjectSectionCalculator.assignAddrMValues(updatedProjectLinks_)
      val sortedRoad206AfterTermination = updatedProjectLinks2.filter(_.roadPart == RP206).sortBy(_.addrMRange.start)
      val updatedProjectLinks2_ = projectLinkDAO.fetchProjectLinks(savedProject.id).toList
      updatedProjectLinks2_.filter(pl => pl.linkId == 5168579.toString).head.calibrationPoints should be((None, None))
      val lastValid = sortedRoad206AfterTermination.filter(_.status != RoadAddressChangeType.Termination).last
      sortedRoad206AfterTermination.filter(_.status != RoadAddressChangeType.Termination).last.calibrationPoints should be((None, Some(ProjectCalibrationPoint(lastValid.linkId, lastValid.endMValue, lastValid.addrMRange.end, RoadAddressCP))))
      updatedProjectLinks2.filter(pl => pl.roadPart == RP205).exists { x => x.status == RoadAddressChangeType.Termination } should be(false)
    }
    runWithRollback {
      projectService.getAllProjects
    } should have size (count - 1)
  }

  test("Test getProjectLinks When doing some operations (Transfer and then Terminate), Then the calibration points are cleared and moved to correct positions") {
    var count = 0
    val roadLink = RoadLink(5170939L.toString, Seq(Point(535605.272, 6982204.22, 85.90899999999965)), 540.3960283713503, AdministrativeClass.State, TrafficDirection.AgainstDigitizing, Some("25.06.2015 03:00:00"), Some(modifiedBy), LifecycleStatus.InUse, LinkGeomSource.NormalLinkInterface, 749, "")
    val RP207 = RoadPart(5, 207)
    runWithRollback {
      val countCurrentProjects = projectService.getAllProjects
      val id = 0
      val addresses = List(ProjectReservedPart(5: Long, RP207, Some(5L), Some(Discontinuity.Continuous), Some(8L), newLength = None, newDiscontinuity = None, newEly = None))
      val project = Project(id, ProjectState.apply(1), "TestProject", "TestUser", DateTime.now(), "TestUser", DateTime.parse("1970-01-01"), DateTime.now(), "Some additional info", Seq(), Seq(), None, elys = Set())
      val savedProject = projectService.createRoadLinkProject(project)
      mockForProject(savedProject.id, (roadwayAddressMapper.getRoadAddressesByLinearLocation(
        linearLocationDAO.fetchByRoadways(roadwayDAO.fetchAllBySection(RP207).map(_.roadwayNumber).toSet)
      ) ++ roadwayAddressMapper.getRoadAddressesByLinearLocation(
        linearLocationDAO.fetchByRoadways(roadwayDAO.fetchAllBySection(RP207).map(_.roadwayNumber).toSet)
      )).map(toProjectLink(savedProject)))

      projectService.saveProject(savedProject.copy(reservedParts = addresses, elys = Set()))
      val countAfterInsertProjects = projectService.getAllProjects
      count = countCurrentProjects.size + 1
      countAfterInsertProjects.size should be(count)
      projectService.allLinksHandled(savedProject.id) should be(false)
      val projectLinks = projectLinkDAO.fetchProjectLinks(savedProject.id)
      val partitioned = projectLinks.partition(_.roadPart == RP207)
      val highestDistanceEnd = projectLinks.map(p => p.addrMRange.end).max
      val linkIds207 = partitioned._1.map(_.linkId).toSet
      reset(mockRoadLinkService)
      when(mockRoadLinkService.getRoadLinksHistoryFromVVH(any[Set[String]])).thenReturn(Seq())
      when(mockRoadLinkService.getRoadLinksByLinkIds(any[Set[String]])).thenAnswer(
        toMockAnswer(projectLinks, roadLink)
      )
      projectService.updateProjectLinks(savedProject.id, Set(), linkIds207.toSeq, RoadAddressChangeType.Transfer,    "-", RP207, 0, Option.empty[Int]) should be(None)
      val linkId1 = 5168510.toString
      val linkId2 = 5168540.toString
      projectService.updateProjectLinks(savedProject.id, Set(), Seq(linkId1),     RoadAddressChangeType.Termination, "-", RP207, 0, Option.empty[Int]) should be(None)
      projectService.allLinksHandled(savedProject.id) should be(true)
      val links_ = projectLinkDAO.fetchProjectLinks(savedProject.id)
      val updatedProjectLinks = ProjectSectionCalculator.assignAddrMValues(links_).sortBy(_.addrMRange.end) ++ projectLinkDAO.fetchProjectLinks(savedProject.id).filter(_.status == RoadAddressChangeType.Termination)
        updatedProjectLinks.exists { x => x.status == RoadAddressChangeType.Transfer } should be(true)
      updatedProjectLinks.exists { x => x.status == RoadAddressChangeType.Termination } should be(true)
      val sortedProjectLinks = updatedProjectLinks.sortBy(_.addrMRange.start)
      sortedProjectLinks.head.calibrationPoints._1.nonEmpty should be (true)
      sortedProjectLinks.head.calibrationPoints._1.get.segmentMValue should be (0.0)
      sortedProjectLinks.head.calibrationPoints._1.get.addressMValue should be (0)
      sortedProjectLinks.head.startCalibrationPointType should be (RoadAddressCP)
      sortedProjectLinks.head.calibrationPoints._2.isEmpty should be (true)

      sortedProjectLinks.last.calibrationPoints._1.isEmpty should be (true)
      sortedProjectLinks.last.calibrationPoints._2.nonEmpty should be (true)
      sortedProjectLinks.last.calibrationPoints._2.get.segmentMValue should be (442.89)
      sortedProjectLinks.last.calibrationPoints._2.get.addressMValue should be (updatedProjectLinks.map(p => p.addrMRange.end).max)
      sortedProjectLinks.last.endCalibrationPointType should be (RoadAddressCP)

      projectService.updateProjectLinks(savedProject.id, Set(), Seq(linkId2), RoadAddressChangeType.Termination, "-", RP207, 0, Option.empty[Int]) should be(None)
      val updatedProjectLinks2 = ProjectSectionCalculator.assignAddrMValues(projectLinkDAO.fetchProjectLinks(savedProject.id)).sortBy(_.addrMRange.end) ++ projectLinkDAO.fetchProjectLinks(savedProject.id).filter(_.status == RoadAddressChangeType.Termination)
      val sortedProjectLinks2 = updatedProjectLinks2.sortBy(_.addrMRange.start)

      sortedProjectLinks2.last.calibrationPoints._1.isEmpty should be (true)
      sortedProjectLinks2.last.calibrationPoints._2.nonEmpty should be (true)
      sortedProjectLinks2.last.calibrationPoints._2.get.segmentMValue should be (442.89)
      sortedProjectLinks2.last.calibrationPoints._2.get.addressMValue should be (updatedProjectLinks2.map(p => p.addrMRange.end).max)
      sortedProjectLinks2.last.endCalibrationPointType should be (RoadAddressCP)
    }

  }

  test("Test getProjectLinks When doing some operations (Terminate then transfer), Then the calibration points are cleared and moved to correct positions") {
    var count = 0
    val roadLink = RoadLink(5170939L.toString, Seq(Point(535605.272, 6982204.22, 85.90899999999965)), 540.3960283713503, AdministrativeClass.State, TrafficDirection.AgainstDigitizing, Some("25.06.2015 03:00:00"), Some(modifiedBy), LifecycleStatus.InUse, LinkGeomSource.NormalLinkInterface, 749, "")
    runWithRollback {
      val countCurrentProjects = projectService.getAllProjects
      val id = 0
      val addresses = List(ProjectReservedPart(5: Long, RoadPart(5, 207), Some(5L), Some(Discontinuity.Continuous), Some(8L), newLength = None, newDiscontinuity = None, newEly = None))
      val project = Project(id, ProjectState.apply(1), "TestProject", "TestUser", DateTime.now(), "TestUser", DateTime.parse("1970-01-01"), DateTime.now(), "Some additional info", Seq(), Seq(), None, elys = Set())
      val savedProject = projectService.createRoadLinkProject(project)
      mockForProject(savedProject.id, (
        roadwayAddressMapper.getRoadAddressesByLinearLocation(linearLocationDAO.fetchByRoadways(roadwayDAO.fetchAllBySection(RoadPart(5, 207)).map(_.roadwayNumber).toSet)) ++
        roadwayAddressMapper.getRoadAddressesByLinearLocation(linearLocationDAO.fetchByRoadways(roadwayDAO.fetchAllBySection(RoadPart(5, 207)).map(_.roadwayNumber).toSet))
        ).map(toProjectLink(savedProject))
      )

      projectService.saveProject(savedProject.copy(reservedParts = addresses, elys = Set()))
      val countAfterInsertProjects = projectService.getAllProjects
      count = countCurrentProjects.size + 1
      countAfterInsertProjects.size should be(count)
      projectService.allLinksHandled(savedProject.id) should be(false)
      val projectLinks = projectLinkDAO.fetchProjectLinks(savedProject.id)
      val partitioned = projectLinks.partition(_.roadPart == RoadPart(5, 207))
      val highestDistanceEnd = projectLinks.map(p => p.addrMRange.end).max
      val linkIds207 = partitioned._1.map(_.linkId).toSet
      reset(mockRoadLinkService)
      when(mockRoadLinkService.getRoadLinksHistoryFromVVH(any[Set[String]])).thenReturn(Seq())
      when(mockRoadLinkService.getRoadLinksByLinkIds(any[Set[String]])).thenAnswer(
        toMockAnswer(projectLinks, roadLink)
      )
      projectService.updateProjectLinks(savedProject.id, Set(), Seq(5168510.toString), RoadAddressChangeType.Termination, "-", RoadPart(5, 207), 0, Option.empty[Int])
      projectService.updateProjectLinks(savedProject.id, Set(), linkIds207.filterNot(_ == 5168510L.toString).toSeq, RoadAddressChangeType.Transfer, "-", RoadPart(5, 207), 0, Option.empty[Int])
      projectService.allLinksHandled(savedProject.id) should be(true)
      projectService.updateProjectLinks(savedProject.id, Set(), Seq(6460794.toString), RoadAddressChangeType.Transfer, "-", RoadPart(5, 207), 0, Option.empty[Int], discontinuity = Discontinuity.Discontinuous.value)

      val links_ = projectLinkDAO.fetchProjectLinks(savedProject.id)
      val updatedProjectLinks = ProjectSectionCalculator.assignAddrMValues(links_).sortBy(_.addrMRange.end) ++ projectLinkDAO.fetchProjectLinks(savedProject.id).filter(_.status == RoadAddressChangeType.Termination)
      updatedProjectLinks.exists { x => x.status == RoadAddressChangeType.Transfer } should be(true)
      updatedProjectLinks.exists { x => x.status == RoadAddressChangeType.Termination } should be(true)
      val sortedProjectLinks = updatedProjectLinks.sortBy(_.addrMRange.start)
      sortedProjectLinks.head.calibrationPoints._1.nonEmpty should be (true)
      sortedProjectLinks.head.calibrationPoints._1.get.segmentMValue should be (0.0)
      sortedProjectLinks.head.calibrationPoints._1.get.addressMValue should be (0)
      sortedProjectLinks.head.startCalibrationPointType should be (RoadAddressCP)
      sortedProjectLinks.head.calibrationPoints._2.isEmpty should be (true)

      sortedProjectLinks.last.calibrationPoints._1.isEmpty should be (true)
      sortedProjectLinks.last.calibrationPoints._2.nonEmpty should be (true)
      sortedProjectLinks.last.calibrationPoints._2.get.segmentMValue should be (442.89)
      val highestDistanceEnd2 = updatedProjectLinks.map(p => p.addrMRange.end).max
      sortedProjectLinks.last.calibrationPoints._2.get.addressMValue should be (updatedProjectLinks.map(p => p.addrMRange.end).max)
      sortedProjectLinks.last.endCalibrationPointType should be (RoadAddressCP)

      projectService.updateProjectLinks(savedProject.id, Set(), Seq(5168540.toString), RoadAddressChangeType.Termination, "-", RoadPart(5, 207), 0, Option.empty[Int])
      val updatedProjectLinks2 = ProjectSectionCalculator.assignAddrMValues(links_).sortBy(_.addrMRange.end) ++ projectLinkDAO.fetchProjectLinks(savedProject.id).filter(_.status == RoadAddressChangeType.Termination)
      val sortedProjectLinks2 = updatedProjectLinks2.sortBy(_.addrMRange.start)

      sortedProjectLinks2.last.calibrationPoints._1.isEmpty should be (true)
      sortedProjectLinks2.last.calibrationPoints._2.nonEmpty should be (true)
      sortedProjectLinks2.last.calibrationPoints._2.get.segmentMValue should be (442.89)
      sortedProjectLinks2.last.calibrationPoints._2.get.addressMValue should be (updatedProjectLinks2.map(p => p.addrMRange.end).max)
      sortedProjectLinks2.last.endCalibrationPointType should be (RoadAddressCP)
    }

  }

  test("Test revertLinksByRoadPart When reverting the road Then the road address geometry after reverting should be the same as KGV") {
    val projectId = 0L
    val user = "TestUser"
    val roadPart = RoadPart(26020, 12)
    val newRoadPart = RoadPart(9999, 1)
    val smallerRoadGeom = Seq(Point(0.0, 0.0), Point(0.0, 5.0))
    val roadGeom = Seq(Point(0.0, 0.0), Point(0.0, 10.0))
    runWithRollback {
      val roadwayNumber = 1
      val roadwayId = Sequences.nextRoadwayId
      val roadAddresses = roadwayAddressMapper.getRoadAddressesByRoadway(roadwayDAO.fetchAllBySection(roadPart))

      val rap = Project(projectId, ProjectState.apply(1), "TestProject", user, DateTime.parse("1901-01-01"), "TestUser", DateTime.parse("1901-01-01"), DateTime.now(), "Some additional info", Seq(), Seq(), None, elys = Set())
      projectDAO.create(rap)
      val projectLinksFromRoadAddresses = roadAddresses.map(ra => toProjectLink(rap)(ra))

      val linearLocation = dummyLinearLocation(roadwayNumber, 1, projectLinksFromRoadAddresses.head.linkId, 0.0, 10.0)
      val roadway = dummyRoadway(roadwayNumber, newRoadPart, AddrMRange(0, 10), DateTime.now(), None, roadwayId)

      linearLocationDAO.create(Seq(linearLocation))
      roadwayDAO.create(Seq(roadway))

      projectReservedPartDAO.reserveRoadPart(projectId, roadPart, "Test")
      projectLinkDAO.create(projectLinksFromRoadAddresses)

      val numberingLink = Seq(ProjectLink(-1000L, newRoadPart, Track.apply(0), Discontinuity.Continuous, AddrMRange(0L,  5L), AddrMRange(0L, 10L), None, None, Option(user), projectLinksFromRoadAddresses.head.linkId, 0.0, 10.0, SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP), smallerRoadGeom, rap.id, RoadAddressChangeType.Renumeration, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, 10.0, roadAddresses.head.id, roadwayNumber+Math.round(linearLocation.orderNumber), 0, reversed = false, None, 86400L))
      projectReservedPartDAO.reserveRoadPart(projectId, newRoadPart, "Test")
      projectLinkDAO.create(numberingLink)
      val numberingLinks = projectLinkDAO.fetchProjectLinks(projectId, Option(RoadAddressChangeType.Renumeration))
      numberingLinks.head.geometry should be equals smallerRoadGeom

     val projectLinks = projectLinkDAO.fetchProjectLinks(projectId)
     val linksToRevert = projectLinks.filter(_.status != RoadAddressChangeType.NotHandled).map(pl => {
       LinkToRevert(pl.id, pl.linkId, pl.status.value, pl.geometry)
     })
     val roadLinks = projectLinks.updated(0, projectLinks.head.copy(geometry = roadGeom)).map(toRoadLink)
      when(mockRoadLinkService.getCurrentAndComplementaryRoadLinks(any[Set[String]])).thenReturn(roadLinks)
      projectService.revertLinksByRoadPart(projectId, newRoadPart, linksToRevert, user)
      val geomAfterRevert = GeometryUtils.truncateGeometry3D(roadGeom, projectLinksFromRoadAddresses.head.startMValue, projectLinksFromRoadAddresses.head.endMValue)
      val linksAfterRevert = projectLinkDAO.fetchProjectLinks(projectId)
      linksAfterRevert.map(_.geometry).contains(geomAfterRevert) should be(true)
    }
  }

  test("Test changeDirection() When projectLinks are reversed the track codes must switch and start_addr_m and end_addr_m should be the same for the first and last links") {
    runWithRollback {
      val roadPart = RoadPart(9999, 1)
      val project = setUpProjectWithLinks(RoadAddressChangeType.New, Seq(0, 100, 150, 300), changeTrack = true, roadPart)
      val projectLinksBefore = projectLinkDAO.fetchProjectLinks(project.id).sortBy(pl => (pl.addrMRange.start, pl.track.value))

      val linksToRevert = projectLinksBefore.map(pl => LinkToRevert(pl.id, pl.id.toString, RoadAddressChangeType.Transfer.value, pl.geometry))
      projectService.changeDirection(project.id, roadPart, linksToRevert, ProjectCoordinates(0, 0, 5), "testUser")

      val projectLinksAfter = projectLinkDAO.fetchProjectLinks(project.id).sortBy(pl => (pl.addrMRange.start, -pl.track.value))
      projectLinksAfter.size should be(projectLinksBefore.size)
      projectLinksAfter.head.addrMRange.start should be(projectLinksBefore.head.addrMRange.start)
      projectLinksAfter.last.addrMRange.end should be(projectLinksBefore.last.addrMRange.end)
      var i = 0
      projectLinksAfter.foreach(pl => {
        pl.track match {
          case Track.RightSide => projectLinksBefore(i).track should be(Track.LeftSide)
          case Track.LeftSide => projectLinksBefore(i).track should be(Track.RightSide)
          case _ => "ignore"
        }
        i += 1
      })
    }
  }

  test("Test handleNewRoadNames - Test if a new RoadName is created from a project link and without duplicates") {
    runWithRollback {

      val testRoadNumber1 = 9999
      val testRoadNumber2 = 9998
      val testRoad9999 = RoadPart(testRoadNumber1, 1)
      val testRoad9998 = RoadPart(testRoadNumber2, 1)
      val testName = "TEST ROAD NAME"

      val rap = Project(0L, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("1901-01-01"), "TestUser", DateTime.parse("1901-01-01"), DateTime.now(), "Some additional info", Seq(), Seq(), None, elys = Set())
      val project = projectService.createRoadLinkProject(rap)

      val changeInfos = List(
        RoadwayChangeInfo(RoadAddressChangeType.New,
          source = dummyRoadwayChangeSection(Some(testRoad9999), Some(0L), Some(  0L), Some(100L), Some(AdministrativeClass.apply(1)), Some(Discontinuity.Continuous), Some(8L)),
          target = dummyRoadwayChangeSection(Some(testRoad9998), Some(0L), Some(100L), Some(200L), Some(AdministrativeClass.apply(3)), Some(Discontinuity.Continuous), Some(8L)),
          Discontinuity.Continuous, AdministrativeClass.apply(1), reversed = false, 1),

        RoadwayChangeInfo(RoadAddressChangeType.New,
          source = dummyRoadwayChangeSection(Some(testRoad9999), Some(0L), Some(100L), Some(200L), Some(AdministrativeClass.apply(1)), Some(Discontinuity.Continuous), Some(8L)),
          target = dummyRoadwayChangeSection(Some(testRoad9998), Some(0L), Some(100L), Some(200L), Some(AdministrativeClass.apply(3)), Some(Discontinuity.Continuous), Some(8L)),
          Discontinuity.Continuous, AdministrativeClass.apply(3), reversed = false, 2),

        RoadwayChangeInfo(RoadAddressChangeType.New,
          source = dummyRoadwayChangeSection(Some(testRoad9999), Some(0L), Some(200L), Some(400L), Some(AdministrativeClass.apply(1)), Some(Discontinuity.Continuous), Some(8L)),
          target = dummyRoadwayChangeSection(Some(testRoad9998), Some(0L), Some(200L), Some(400L), Some(AdministrativeClass.apply(1)), Some(Discontinuity.Continuous), Some(8L)),
          Discontinuity.Continuous, AdministrativeClass.apply(3), reversed = false, 3)
      )

      val projectStartTime = DateTime.now()

      val changes = List(
        ProjectRoadwayChange(project.id, Some("projectName"), 8, "Test", DateTime.now(), changeInfos.head, projectStartTime),
        ProjectRoadwayChange(project.id, Some("projectName"), 8, "Test", DateTime.now(), changeInfos(1), projectStartTime),
        ProjectRoadwayChange(project.id, Some("projectName"), 8, "Test", DateTime.now(), changeInfos(2), projectStartTime)
      )

      ProjectLinkNameDAO.create(project.id, testRoadNumber1, testName)
      ProjectLinkNameDAO.create(project.id, testRoadNumber2, testName)

      // Method to be tested
      projectService.handleNewRoadNames(changes)

      // Test if the new roadnames have the test road name & number
      val roadnames1 = RoadNameDAO.getAllByRoadNumber(testRoadNumber1)
      roadnames1.foreach(rn => {
        rn.roadName should be (testName)
        rn.roadNumber should be (testRoadNumber1)
      })

      val roadnames2 = RoadNameDAO.getAllByRoadNumber(testRoadNumber2)
      roadnames2.foreach(rn => {
        rn.roadName should be (testName)
        rn.roadNumber should be (testRoadNumber2)
      })
    }
  }

  test("Test handleTerminatedRoadwayChanges - When a project has a Termination RoadWayChange then the RoadName(s) for that RoadNumber have to be expired") {
    runWithRollback {

      val roadNumber = 9999
      val name = "TEST ROAD NAME"

      val roadnames = Seq(
        RoadName(99999, roadNumber, name, startDate = Some(DateTime.now()), createdBy = "Test")
      )
      RoadNameDAO.create(roadnames)

      val changeInfos = List(
        RoadwayChangeInfo(RoadAddressChangeType.Termination,
          source = dummyRoadwayChangeSection(Some(RoadPart(roadNumber, 1)), Some(0L), Some(0L),   Some(100L), Some(AdministrativeClass.apply(1)), Some(Discontinuity.Continuous), Some(8L)),
          target = dummyRoadwayChangeSection(Some(RoadPart(roadNumber, 1)), Some(0L), Some(100L), Some(200L), Some(AdministrativeClass.apply(3)), Some(Discontinuity.Continuous), Some(8L)),
          Discontinuity.Continuous, AdministrativeClass.apply(1), reversed = false, 1),

        RoadwayChangeInfo(RoadAddressChangeType.Unchanged,
          source = dummyRoadwayChangeSection(Some(RoadPart(roadNumber, 1)), Some(0L), Some(100L), Some(200L), Some(AdministrativeClass.apply(1)), Some(Discontinuity.Continuous), Some(8L)),
          target = dummyRoadwayChangeSection(Some(RoadPart(roadNumber, 1)), Some(0L), Some(100L), Some(200L), Some(AdministrativeClass.apply(3)), Some(Discontinuity.Continuous), Some(8L)),
          Discontinuity.Continuous, AdministrativeClass.apply(3), reversed = false, 2),

        RoadwayChangeInfo(RoadAddressChangeType.Unchanged,
          source = dummyRoadwayChangeSection(Some(RoadPart(roadNumber, 1)), Some(0L), Some(200L), Some(400L), Some(AdministrativeClass.apply(1)), Some(Discontinuity.Continuous), Some(8L)),
          target = dummyRoadwayChangeSection(Some(RoadPart(roadNumber, 1)), Some(0L), Some(200L), Some(400L), Some(AdministrativeClass.apply(1)), Some(Discontinuity.Continuous), Some(8L)),
          Discontinuity.Continuous, AdministrativeClass.apply(3), reversed = false, 3)
      )

      val projectStartTime = DateTime.now()

      val changes = List(
        ProjectRoadwayChange(0L, Some("projectName"), 8, "Test", DateTime.now(), changeInfos.head, projectStartTime),
        ProjectRoadwayChange(0L, Some("projectName"), 8, "Test", DateTime.now(), changeInfos(1), projectStartTime),
        ProjectRoadwayChange(0L, Some("projectName"), 8, "Test", DateTime.now(), changeInfos(2), projectStartTime)
      )

      // Method to be tested
      projectService.handleTerminatedRoadwayChanges(changes)

      val roadNames = RoadNameDAO.getAllByRoadNumber(roadNumber)
      roadNames.foreach(rn => {
        rn.endDate.isDefined should be (true)
        rn.endDate.get.toLocalDate should be (projectStartTime.toLocalDate.minusDays(1))
      })

    }
  }

  test("Test handleTransferAndRenumeration: Transfer to new road - If source road exists expire its road name and create new road name for target road number") {
    runWithRollback {

      val srcRoadNumber = 99998
      val targetRoadNumber = 99999
      val testRoadName = "TEST ROAD NAME"

      val rap = Project(0L, ProjectState.apply(1), "TestProject", "TestUser", DateTime.now(), "TestUser", DateTime.now(), DateTime.now(), "Some additional info", Seq(), Seq(), None, elys = Set())
      val project = projectService.createRoadLinkProject(rap)
      ProjectLinkNameDAO.create(project.id, targetRoadNumber, testRoadName)

      val roadnames = Seq(RoadName(srcRoadNumber, srcRoadNumber, testRoadName, startDate = Some(DateTime.now()), createdBy = "Test"))
      RoadNameDAO.create(roadnames)

      val roadways = List(dummyRoadway(0L, RoadPart(srcRoadNumber, 1), AddrMRange(0L, 100L), DateTime.now, Some(DateTime.now)))
      roadwayDAO.create(roadways)

      val changeInfos = List(
        RoadwayChangeInfo(RoadAddressChangeType.Transfer,
          source = dummyRoadwayChangeSection(Some(RoadPart(srcRoadNumber,    1)), Some(0L), Some(0L),   Some(100L), Some(AdministrativeClass.apply(1)), Some(Discontinuity.Continuous), Some(8L)),
          target = dummyRoadwayChangeSection(Some(RoadPart(targetRoadNumber, 1)), Some(0L), Some(100L), Some(200L), Some(AdministrativeClass.apply(3)), Some(Discontinuity.Continuous), Some(8L)),
          Discontinuity.Continuous, AdministrativeClass.apply(1), reversed = false, 1)
      )

      val changes = List(
        ProjectRoadwayChange(project.id, Some("projectName"), 8, "Test", DateTime.now(), changeInfos.head, project.startDate)
      )

      RoadNameDAO.getLatestRoadName(srcRoadNumber).size should be(1)    // There should be a name for the original
      RoadNameDAO.getLatestRoadName(targetRoadNumber)   should be(None) // but no name for the target road at this point

      ProjectLinkNameDAO.get(srcRoadNumber, project.id) should be(None)
      ProjectLinkNameDAO.get(targetRoadNumber, project.id).size should be(1)

      projectService.handleRoadNames(changes) // Make the road name changes

      val srcRoadNames = RoadNameDAO.getAllByRoadNumber(srcRoadNumber)
      srcRoadNames.foreach(rn => {
        if(rn.endDate.isDefined) {
          rn.endDate.get.toLocalDate should be(project.startDate.toLocalDate.minusDays(1))
        }
      })
      val targetRoadNames = RoadNameDAO.getAllByRoadNumber(targetRoadNumber)
      targetRoadNames.foreach(rn => {
        rn.roadNumber should be(targetRoadNumber)
        rn.roadName   should be(testRoadName)
      })
    }
  }

  test("Test handleTransferAndRenumeration: Transfer to existing roadway - If source roadway exists expire its roadname") {
    runWithRollback {

      val srcRoadNumber = 99998
      val targetRoadNumber = 99999
      val testName = "TEST ROAD NAME"

      val rap = Project(0L, ProjectState.apply(1), "TestProject", "TestUser", DateTime.now(), "TestUser", DateTime.now(), DateTime.now(), "Some additional info", Seq(), Seq(), None, elys = Set())
      val project = projectService.createRoadLinkProject(rap)
      ProjectLinkNameDAO.create(project.id, targetRoadNumber, testName)

      val roadnames = Seq(RoadName(99999, srcRoadNumber, testName, startDate = Some(DateTime.now()), createdBy = "Test"))
      RoadNameDAO.create(roadnames)

      val roadways = List(
        Roadway( 0L, 0L, RoadPart(srcRoadNumber,    0), AdministrativeClass.State, Track.Combined, Discontinuity.Continuous, AddrMRange(0L, 0L), reversed = false, DateTime.now, Some(DateTime.now), "dummy", None, 0L, NoTermination),
        Roadway(-1L, 0L, RoadPart(targetRoadNumber, 0), AdministrativeClass.State, Track.Combined, Discontinuity.Continuous, AddrMRange(0L, 0L), reversed = false, DateTime.now, Some(DateTime.now), "dummy", None, 0L, NoTermination)
      )
      roadwayDAO.create(roadways)

      val changeInfos = List(
        RoadwayChangeInfo(RoadAddressChangeType.Transfer,
          source = dummyRoadwayChangeSection(Some(RoadPart(srcRoadNumber,    1)), Some(0L), Some(0L), Some(100L), Some(AdministrativeClass.apply(1)), Some(Discontinuity.Continuous), Some(8L)),
          target = dummyRoadwayChangeSection(Some(RoadPart(targetRoadNumber, 1)), Some(0L), Some(100L), Some(200L), Some(AdministrativeClass.apply(3)), Some(Discontinuity.Continuous), Some(8L)),
          Discontinuity.Continuous, AdministrativeClass.apply(1), reversed = false, 1)
      )

      val changes = List(
        ProjectRoadwayChange(project.id, Some("projectName"), 8, "Test", DateTime.now(), changeInfos.head, project.startDate)
      )
      projectService.handleRoadNames(changes)

      val srcRoadNames = RoadNameDAO.getAllByRoadNumber(srcRoadNumber)
      srcRoadNames.foreach(rn => {
        if(rn.endDate.isDefined) {
          rn.endDate.get.toLocalDate should be(project.startDate.toLocalDate.minusDays(1))
        }
      })
    }
  }

  test("Test handleTransferAndRenumeration: Renumeration to new road - If source road exists expire its road name and create new road name for targer road number") {
    runWithRollback {

      val srcRoadNumber = 99998
      val targetRoadNumber = 99999
      val testName = "TEST ROAD NAME"

      val rap = Project(0L, ProjectState.apply(1), "TestProject", "TestUser", DateTime.now(), "TestUser", DateTime.now(), DateTime.now(), "Some additional info", Seq(), Seq(), None, elys = Set())
      val project = projectService.createRoadLinkProject(rap)
      ProjectLinkNameDAO.create(project.id, targetRoadNumber, testName)

      val roadnames = Seq(RoadName(srcRoadNumber, srcRoadNumber, testName, startDate = Some(DateTime.now()), createdBy = "Test"))
      RoadNameDAO.create(roadnames)

      val roadways = List(dummyRoadway(0L, RoadPart(srcRoadNumber, 1), AddrMRange(0L, 0L), DateTime.now, Some(DateTime.now)))
      roadwayDAO.create(roadways)

      val changeInfos = List(
        RoadwayChangeInfo(RoadAddressChangeType.Renumeration,
          source = dummyRoadwayChangeSection(Some(RoadPart(srcRoadNumber,    1)), Some(0L), Some(0L), Some(100L), Some(AdministrativeClass.apply(1)), Some(Discontinuity.Continuous), Some(8L)),
          target = dummyRoadwayChangeSection(Some(RoadPart(targetRoadNumber, 1)), Some(0L), Some(100L), Some(200L), Some(AdministrativeClass.apply(3)), Some(Discontinuity.Continuous), Some(8L)),
          Discontinuity.Continuous, AdministrativeClass.apply(1), reversed = false, 1)
      )

      val changes = List(
        ProjectRoadwayChange(project.id, Some("projectName"), 8, "Test", DateTime.now(), changeInfos.head, project.startDate)
      )

      projectService.handleRoadNames(changes)

      ProjectLinkNameDAO.get(srcRoadNumber, project.id) should be(None)

      val srcRoadNames = RoadNameDAO.getAllByRoadNumber(srcRoadNumber)
      srcRoadNames.foreach(rn => {
        if(rn.endDate.isDefined) {
          rn.endDate.get.toLocalDate should be(project.startDate.toLocalDate.minusDays(1))
        }
      })

      val targetRoadNames = RoadNameDAO.getAllByRoadNumber(targetRoadNumber)
      targetRoadNames.foreach(rn => {
        rn.roadNumber should be(targetRoadNumber)
        rn.roadName should be(testName)
      })
    }
  }

  test("Test handleTransferAndRenumeration: Renumeration to existing roadway - If source roadway exists expire its roadname") {
    runWithRollback {

      val srcRoadNumber = 99998
      val targetRoadNumber = 99999
      val testName = "TEST ROAD NAME"

      val rap = Project(0L, ProjectState.apply(1), "TestProject", "TestUser", DateTime.now(), "TestUser", DateTime.now(), DateTime.now(), "Some additional info", Seq(), Seq(), None, elys = Set())
      val project = projectService.createRoadLinkProject(rap)
      ProjectLinkNameDAO.create(project.id, targetRoadNumber, testName)

      val roadNames = Seq(RoadName(99999, srcRoadNumber, testName, startDate = Some(DateTime.now()), createdBy = "Test"))
      RoadNameDAO.create(roadNames)

      val roadways = List(
        Roadway( 0L, 0L, RoadPart(srcRoadNumber,    0), AdministrativeClass.State, Track.Combined, Discontinuity.Continuous, AddrMRange(0L, 0L), startDate = DateTime.now, endDate = Some(DateTime.now), createdBy = "dummy", roadName = None, ely = 0L, terminated = NoTermination),
        Roadway(-1L, 0L, RoadPart(targetRoadNumber, 0), AdministrativeClass.State, Track.Combined, Discontinuity.Continuous, AddrMRange(0L, 0L), reversed = false, DateTime.now, Some(DateTime.now), "dummy", None, 0L, NoTermination)
      )
      roadwayDAO.create(roadways)

      val changeInfos = List(
        RoadwayChangeInfo(RoadAddressChangeType.Renumeration,
          source = dummyRoadwayChangeSection(Some(RoadPart(srcRoadNumber,    1)), Some(0L), Some(0L), Some(100L), Some(AdministrativeClass.apply(1)), Some(Discontinuity.Continuous), Some(8L)),
          target = dummyRoadwayChangeSection(Some(RoadPart(targetRoadNumber, 1)), Some(0L), Some(100L), Some(200L), Some(AdministrativeClass.apply(3)), Some(Discontinuity.Continuous), Some(8L)),
          Discontinuity.Continuous, AdministrativeClass.apply(1), reversed = false, 1)
      )

      val changes = List(
        ProjectRoadwayChange(project.id, Some("projectName"), 8, "Test", DateTime.now(), changeInfos.head, project.startDate)
      )
      projectService.handleRoadNames(changes)

      val srcRoadNames = RoadNameDAO.getAllByRoadNumber(srcRoadNumber)
      srcRoadNames.foreach(rn => {
        if(rn.endDate.isDefined) {
          rn.endDate.get.toLocalDate should be(project.startDate.toLocalDate.minusDays(1))
        }
      })
    }
  }

  test("Test save project with reserved road parts having different ELY codes should not update them. Formed road parts on other side," +
    " should get ely from project links") {
    runWithRollback {

      val roadNumber = 26020
      val part1 = 12
      val part2 = 34
      val ely1 = Some(2L)
      val ely2 = Some(1L)

      val rap = Project(0L, ProjectState.apply(1), "TestProject", "TestUser", DateTime.now(), "TestUser", DateTime.now(), DateTime.now(), "Some additional info", Seq(), Seq(), None, elys = Set())
      val reservations = List(
        ProjectReservedPart(Sequences.nextViitePrimaryKeySeqValue, RoadPart(roadNumber, part1), Some(0L), Some(Discontinuity.Continuous), ely1, None, None, None, None),
        ProjectReservedPart(Sequences.nextViitePrimaryKeySeqValue, RoadPart(roadNumber, part2), Some(0L), Some(Discontinuity.Continuous), ely2, None, None, None, None)
      )
      val project = projectService.createRoadLinkProject(rap)

      val address1 = roadwayAddressMapper.getRoadAddressesByLinearLocation(linearLocationDAO.fetchByRoadways(roadwayDAO.fetchAllBySection(RoadPart(roadNumber, part1)).map(_.roadwayNumber).toSet))
      val address2 = roadwayAddressMapper.getRoadAddressesByLinearLocation(linearLocationDAO.fetchByRoadways(roadwayDAO.fetchAllBySection(RoadPart(roadNumber, part2)).map(_.roadwayNumber).toSet))
      mockForProject(project.id, (address1 ++ address2).map(toProjectLink(rap)))
      val savedProject = projectService.saveProject(project.copy(reservedParts = reservations, formedParts = reservations, elys = Set()))
      val originalElyPart1 = roadwayDAO.fetchAllByRoadPart(RoadPart(roadNumber, part1)).map(_.ely).toSet
      val originalElyPart2 = roadwayDAO.fetchAllByRoadPart(RoadPart(roadNumber, part2)).map(_.ely).toSet
      val reservedPart1 = savedProject.reservedParts.find(rp => rp.roadPart == RoadPart(roadNumber, part1))
      val reservedPart2 = savedProject.reservedParts.find(rp => rp.roadPart == RoadPart(roadNumber, part2))
      val formedPart1   = savedProject.reservedParts.find(rp => rp.roadPart == RoadPart(roadNumber, part1))
      val formedPart2   = savedProject.reservedParts.find(rp => rp.roadPart == RoadPart(roadNumber, part2))
      reservedPart1.nonEmpty should be (true)
      reservedPart1.get.ely.get should be (originalElyPart1.head)
      reservedPart2.nonEmpty should be (true)
      reservedPart2.get.ely.get should be (originalElyPart2.head)

    }
  }

  test("Test changeDirection When after the creation of valid project links on a project Then the side code of road addresses should be successfully reversed.") {
    runWithRollback {

      val rap = Project(0L, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("1901-01-01"), "TestUser", DateTime.parse("1901-01-01"), DateTime.now(), "Some additional info", Seq(), Seq(), None, elys = Set())

      val geom1 = Seq(Point(419.26, 5896.197), Point(420.115, 5911.262))
      val geom2 = Seq(Point(420.115, 5911.262), Point(420.289, 5929.439))
      val geom3 = Seq(Point(420.289, 5929.439), Point(420.80, 5951.574))
      val geom4 = Seq(Point(420.802, 5951.574), Point(434.144, 5957.563))
      val geom5 = Seq(Point(445.712, 5957.31), Point(434.144, 5957.563))
      val geom6 = Seq(Point(445.712, 5957.31), Point(454.689, 5959.367))
      val geom7 = Seq(Point(454.689, 5959.367), Point(554.849, 6005.186))
      val geom8 = Seq(Point(430.743, 5896.673), Point(430.719, 5910.37))
      val geom9 = Seq(Point(430.719, 5910.37), Point(433.033, 5945.855))
      val geom10 = Seq(Point(433.033, 5945.855), Point(420.802, 5951.574))
      val geom11 = Seq(Point(443.483, 5945.637), Point(433.033, 5945.855))
      val geom12 = Seq(Point(443.483, 5945.637), Point(451.695, 5947.231))
      val geom13 = Seq(Point(451.695, 5947.231), Point(580.822, 5990.441))

      val pl1  = ProjectLink(-1000L, RoadPart(9999, 1), Track.RightSide, Discontinuity.Continuous, AddrMRange(0L,  0L), AddrMRange(0L,  0L), None, None, None, 12345L.toString, 0.0, 0.0, SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP), geom1,  0L, RoadAddressChangeType.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geom1),  0L, 0, 0, reversed = false, None, NewIdValue)
      val pl2  = ProjectLink(-1000L, RoadPart(9999, 1), Track.RightSide, Discontinuity.Continuous, AddrMRange(0L,  0L), AddrMRange(0L,  0L), None, None, None, 12346L.toString, 0.0, 0.0, SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP), geom2,  0L, RoadAddressChangeType.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geom2),  0L, 0, 0, reversed = false, None, NewIdValue)
      val pl3  = ProjectLink(-1000L, RoadPart(9999, 1), Track.RightSide, Discontinuity.Continuous, AddrMRange(0L,  0L), AddrMRange(0L,  0L), None, None, None, 12347L.toString, 0.0, 0.0, SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP), geom3,  0L, RoadAddressChangeType.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geom3),  0L, 0, 0, reversed = false, None, NewIdValue)
      val pl4  = ProjectLink(-1000L, RoadPart(9999, 1), Track.RightSide, Discontinuity.Continuous, AddrMRange(0L,  0L), AddrMRange(0L,  0L), None, None, None, 12348L.toString, 0.0, 0.0, SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP), geom4,  0L, RoadAddressChangeType.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geom4),  0L, 0, 0, reversed = false, None, NewIdValue)
      val pl5  = ProjectLink(-1000L, RoadPart(9999, 1), Track.RightSide, Discontinuity.Continuous, AddrMRange(0L,  0L), AddrMRange(0L,  0L), None, None, None, 12349L.toString, 0.0, 0.0, SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP), geom5,  0L, RoadAddressChangeType.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geom5),  0L, 0, 0, reversed = false, None, NewIdValue)
      val pl6  = ProjectLink(-1000L, RoadPart(9999, 1), Track.RightSide, Discontinuity.Continuous, AddrMRange(0L,  0L), AddrMRange(0L,  0L), None, None, None, 12350L.toString, 0.0, 0.0, SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP), geom6,  0L, RoadAddressChangeType.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geom6),  0L, 0, 0, reversed = false, None, NewIdValue)
      val pl7  = ProjectLink(-1000L, RoadPart(9999, 1), Track.RightSide, Discontinuity.Continuous, AddrMRange(0L,  0L), AddrMRange(0L,  0L), None, None, None, 12351L.toString, 0.0, 0.0, SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP), geom7,  0L, RoadAddressChangeType.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geom7),  0L, 0, 0, reversed = false, None, NewIdValue)
      val pl8  = ProjectLink(-1000L, RoadPart(9999, 1), Track.RightSide, Discontinuity.Continuous, AddrMRange(0L,  0L), AddrMRange(0L,  0L), None, None, None, 12352L.toString, 0.0, 0.0, SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP), geom8,  0L, RoadAddressChangeType.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geom8),  0L, 0, 0, reversed = false, None, NewIdValue)
      val pl9  = ProjectLink(-1000L, RoadPart(9999, 1), Track.RightSide, Discontinuity.Continuous, AddrMRange(0L,  0L), AddrMRange(0L,  0L), None, None, None, 12353L.toString, 0.0, 0.0, SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP), geom9,  0L, RoadAddressChangeType.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geom9),  0L, 0, 0, reversed = false, None, NewIdValue)
      val pl10 = ProjectLink(-1000L, RoadPart(9999, 1), Track.RightSide, Discontinuity.Continuous, AddrMRange(0L,  0L), AddrMRange(0L,  0L), None, None, None, 12354L.toString, 0.0, 0.0, SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP), geom10, 0L, RoadAddressChangeType.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geom10), 0L, 0, 0, reversed = false, None, NewIdValue)
      val pl11 = ProjectLink(-1000L, RoadPart(9999, 1), Track.RightSide, Discontinuity.Continuous, AddrMRange(0L,  0L), AddrMRange(0L,  0L), None, None, None, 12355L.toString, 0.0, 0.0, SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP), geom11, 0L, RoadAddressChangeType.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geom11), 0L, 0, 0, reversed = false, None, NewIdValue)
      val pl12 = ProjectLink(-1000L, RoadPart(9999, 1), Track.RightSide, Discontinuity.Continuous, AddrMRange(0L,  0L), AddrMRange(0L,  0L), None, None, None, 12356L.toString, 0.0, 0.0, SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP), geom12, 0L, RoadAddressChangeType.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geom12), 0L, 0, 0, reversed = false, None, NewIdValue)
      val pl13 = ProjectLink(-1000L, RoadPart(9999, 1), Track.RightSide, Discontinuity.Continuous, AddrMRange(0L,  0L), AddrMRange(0L,  0L), None, None, None, 12357L.toString, 0.0, 0.0, SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP), geom13, 0L, RoadAddressChangeType.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geom13), 0L, 0, 0, reversed = false, None, NewIdValue)

      val project = projectService.createRoadLinkProject(rap)
      when(mockRoadLinkService.getRoadLinksByLinkIds(any[Set[String]])).thenReturn(Seq(pl1, pl2, pl3, pl4, pl5, pl6, pl7).map(toRoadLink))

      projectService.createProjectLinks(Seq(12345L, 12346L, 12347L, 12348L, 12349L, 12350L, 12351L).map(_.toString), project.id, roadPart = RoadPart(9999, 1), track = Track.RightSide, userGivenDiscontinuity = Discontinuity.Continuous, administrativeClass = AdministrativeClass.State, roadLinkSource = LinkGeomSource.NormalLinkInterface, roadEly = 8L, user = "test", roadName = "road name")

      when(mockRoadLinkService.getRoadLinksByLinkIds(Set(12352L, 12353L, 12354L).map(_.toString))).thenReturn(Seq(pl8, pl9, pl10).map(toRoadLink))
      projectService.createProjectLinks(Seq(12352L, 12353L, 12354L).map(_.toString), project.id, roadPart = RoadPart(9999, 1), track = Track.LeftSide, userGivenDiscontinuity = Discontinuity.Continuous, administrativeClass = AdministrativeClass.State, roadLinkSource = LinkGeomSource.NormalLinkInterface, roadEly = 8L, user = "test", roadName = "road name")
      when(mockRoadLinkService.getRoadLinksByLinkIds(Set(12355L, 12356L, 12357L).map(_.toString))).thenReturn(Seq(pl11, pl12, pl13).map(toRoadLink))
      projectService.createProjectLinks(Seq(12355L, 12356L, 12357L).map(_.toString), project.id, RoadPart(9999, 1), Track.LeftSide, Discontinuity.Continuous, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, 8L, "test", "road name")

      val linksBeforeChange = projectLinkDAO.fetchProjectLinks(project.id).sortBy(_.addrMRange.start)
      projectService.changeDirection(project.id, RoadPart(9999, 1), Seq(LinkToRevert(pl1.id, pl1.linkId, pl1.status.value, pl1.geometry)), ProjectCoordinates(0, 0, 0), "TestUserTwo")
      val linksAfterChange = projectLinkDAO.fetchProjectLinks(project.id).sortBy(_.addrMRange.start)

      linksBeforeChange.forall{bc =>
        val changedLink = linksAfterChange.find(_.linkId == bc.linkId)
        SideCode.switch(changedLink.get.sideCode) == bc.sideCode
      }
    }
  }

  test("Test checkRoadPartsReservable When there are some transferred road links to that same road part.") {
    runWithRollback {
      val raId = Sequences.nextRoadwayId
      val startDate = DateTime.now()
      val linearLocationId = Sequences.nextLinearLocationId
      val roadPart    = RoadPart(20000,1)
      val newRoadPart = RoadPart(20000,2)

      val ra = Seq(
        //Combined
        Roadway(raId,     roadwayNumber1, roadPart,    AdministrativeClass.State, Track.Combined, Discontinuity.EndOfRoad, AddrMRange(0L, 20L), reversed = false, DateTime.now(), None, "test_user", None, 8, NoTermination, startDate, None),
        //RightSide
        Roadway(raId + 1, roadwayNumber2, newRoadPart, AdministrativeClass.State, Track.Combined, Discontinuity.EndOfRoad, AddrMRange(0L, 50L), reversed = false, DateTime.now(), None, "test_user", None, 8, NoTermination, startDate, None))

      val linearLocations = Seq(
        //        part1
        LinearLocation(linearLocationId, 1, 1000l.toString, 0.0, 15.0, SideCode.TowardsDigitizing, 10000000000l, (CalibrationPointReference.None, CalibrationPointReference.None), Seq(Point(0.0, 0.0), Point(0.0, 15.0)), LinkGeomSource.ComplementaryLinkInterface, roadwayNumber1, Some(startDate), None),
        LinearLocation(linearLocationId + 1, 2, 2000l.toString, 0.0, 5.0, SideCode.TowardsDigitizing, 10000000000l, (CalibrationPointReference.None, CalibrationPointReference.None), Seq(Point(0.0, 15.0), Point(0.0, 20.0)), LinkGeomSource.ComplementaryLinkInterface, roadwayNumber1, Some(startDate), None),
        //part2
        LinearLocation(linearLocationId + 2, 1, 3000l.toString, 0.0, 5.0, SideCode.TowardsDigitizing, 10000000000l, (CalibrationPointReference.None, CalibrationPointReference.None), Seq(Point(0.0, 20.0), Point(0.0, 25.0)), LinkGeomSource.ComplementaryLinkInterface, roadwayNumber2, Some(startDate), None),
        LinearLocation(linearLocationId + 3, 2, 4000l.toString, 0.0, 45.0, SideCode.TowardsDigitizing, 10000000000l, (CalibrationPointReference.None, CalibrationPointReference.None), Seq(Point(0.0, 25.0), Point(0.0, 70.0)), LinkGeomSource.ComplementaryLinkInterface, roadwayNumber2, Some(startDate), None)
      )
      roadwayDAO.create(ra)
      linearLocationDAO.create(linearLocations)

      val rap = Project(0L, ProjectState.apply(1), "TestProject", "TestUser", DateTime.now(), "TestUser", DateTime.now().plusDays(5), DateTime.now(), "Some additional info", Seq(), Seq(), None, elys = Set())
      val project = projectService.createRoadLinkProject(rap)
      val project_id = project.id
      val roadway1 = roadwayDAO.fetchAllBySection(roadPart)
      val roadway2 = roadwayDAO.fetchAllBySection(newRoadPart)
      mockForProject(project_id, roadwayAddressMapper.getRoadAddressesByLinearLocation(linearLocationDAO.fetchByRoadways(roadway1.map(_.roadwayNumber).toSet++roadway2.map(_.roadwayNumber).toSet)).map(toProjectLink(project)))
      val reservedPart1 = ProjectReservedPart(0L, roadPart,    None, None, None, None, None, None, None)
      val reservedPart2 = ProjectReservedPart(0L, newRoadPart, None, None, None, None, None, None, None)
      projectService.saveProject(project.copy(reservedParts = Seq(reservedPart1, reservedPart2), elys = Set()))
      val projectLinks = projectLinkDAO.fetchProjectLinks(project_id)
      val lastLink = Set(projectLinks.filter(_.roadPart == roadPart).maxBy(_.addrMRange.end).id)

      projectService.updateProjectLinks(project_id, lastLink, Seq(), RoadAddressChangeType.Transfer, "test", newRoadPart, 0, None, 1, 5, Some(8L), reversed = false, None)

      val projectLinks_ = projectLinkDAO.fetchProjectLinks(project_id)
      val calculatedProjectLinks = ProjectSectionCalculator.assignAddrMValues(projectLinks_)
      calculatedProjectLinks.foreach(pl => projectLinkDAO.updateAddrMValues(pl))

      val lengthOfTheTransferredPart = 5
      val lengthPart1 = linearLocations.filter(_.roadwayNumber == roadwayNumber1).map(_.endMValue).sum - linearLocations.filter(_.roadwayNumber == roadwayNumber1).map(_.startMValue).sum
      val lengthPart2 = linearLocations.filter(_.roadwayNumber == roadwayNumber2).map(_.endMValue).sum - linearLocations.filter(_.roadwayNumber == roadwayNumber2).map(_.startMValue).sum
      val newLengthOfTheRoadPart1 = lengthPart1 - lengthOfTheTransferredPart
      val newLengthOfTheRoadPart2 = lengthPart2 + lengthOfTheTransferredPart

      val reservation = projectReservedPartDAO.fetchReservedRoadParts(project_id)
      val formed      = projectReservedPartDAO.fetchFormedRoadParts(project_id)

      reservation.filter(_.roadPart == roadPart   ).head.addressLength should be(Some(ra.head.addrMRange.end))
      reservation.filter(_.roadPart == newRoadPart).head.addressLength should be(Some(ra.last.addrMRange.end))

      formed.filter(_.roadPart == roadPart   ).head.newLength should be(Some(newLengthOfTheRoadPart1))
      formed.filter(_.roadPart == newRoadPart).head.newLength should be(Some(lengthOfTheTransferredPart))

      val roadWay_2 = roadwayDAO.fetchAllBySection(newRoadPart)
      mockForProject(project_id, roadwayAddressMapper.getRoadAddressesByLinearLocation(linearLocationDAO.fetchByRoadways(roadWay_2.map(_.roadwayNumber).toSet)).map(toProjectLink(project)))

      val projectLinksSet = projectLinkDAO.fetchProjectLinks(project_id).filter(_.roadPart == newRoadPart).map(_.id).toSet
      projectService.updateProjectLinks(project_id, projectLinksSet, Seq(), RoadAddressChangeType.Transfer, "test", newRoadPart, 0, None, 1, 5, Some(1L), reversed = false, None)

      val reservation2 = projectReservedPartDAO.fetchReservedRoadParts(project_id)
      val formed2 = projectReservedPartDAO.fetchFormedRoadParts(project_id)
      reservation2.filter(_.roadPart == roadPart   ).head.addressLength should be(Some(ra.head.addrMRange.end))
      reservation.filter (_.roadPart == newRoadPart).head.addressLength should be(Some(ra.last.addrMRange.end))

      formed2.filter(_.roadPart == roadPart   ).head.newLength should be(Some(newLengthOfTheRoadPart1))
      formed2.filter(_.roadPart == newRoadPart).head.newLength should be(Some(ra.last.addrMRange.end+lengthOfTheTransferredPart))

      projectService.validateProjectById(project_id).exists(
        _.validationError.message == s"Toimenpidettä ei saa tehdä tieosalle, jota ei ole varattu projektiin. Varaa tieosa $roadPart."
      ) should be(false)
    }
  }

  test("Test projectService.createProjectLinks() discontinuity assignment When creating some links Then " +
       "the Discontinuity of the last (with bigger endAddrM) new created links should be the one the user " +
       "gave and should not change any other link discontinuity that was previosly given.") {
    runWithRollback {

      val rap = Project(0L, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("1901-01-01"), "TestUser", DateTime.parse("1901-01-01"), DateTime.now(), "Some additional info", Seq(), Seq(), None, elys = Set())

      val linkAfterRoundabout                    = ProjectLink(-1000L, RoadPart(9999, 1), Track.apply(0), Discontinuity.EndOfRoad,          AddrMRange(0L,  0L), AddrMRange(0L,  0L), None, None, None, 12345L.toString, 0.0, 5.0, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), Seq(Point(15.0, 0.0), Point(20.0, 0.0)), 0L, RoadAddressChangeType.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(Seq(Point(15.0, 0.0), Point(20.0, 0.0))), 0L, 0, 0, reversed = false, None, 86400L)
      val linkBeforeRoundaboutMinorDiscontinuous = ProjectLink(-1000L, RoadPart(9999, 1), Track.apply(0), Discontinuity.MinorDiscontinuity, AddrMRange(0L,  0L), AddrMRange(0L,  0L), None, None, None, 12346L.toString, 0.0, 5.0, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), Seq(Point( 5.0, 0.0), Point(15.0, 0.0)), 0L, RoadAddressChangeType.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(Seq(Point( 5.0, 0.0), Point(15.0, 0.0))), 0L, 0, 0, reversed = false, None, 86400L)
      val linkBeforeRoundaboutContinuous         = ProjectLink(-1000L, RoadPart(9999, 1), Track.apply(0), Discontinuity.Continuous,         AddrMRange(0L,  0L), AddrMRange(0L,  0L), None, None, None, 12347L.toString, 0.0, 5.0, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), Seq(Point( 0.0, 1.0), Point( 5.0, 0.0)), 0L, RoadAddressChangeType.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(Seq(Point( 0.0, 1.0), Point( 5.0, 0.0))), 0L, 0, 0, reversed = false, None, 86400L)


      val project = projectService.createRoadLinkProject(rap)
      when(mockRoadLinkService.getRoadLinksByLinkIds(any[Set[String]])).thenReturn(Seq(linkAfterRoundabout).map(toRoadLink))
      projectService.createProjectLinks(Seq(12345L.toString), project.id, RoadPart(9999, 1), Track.Combined, Discontinuity.EndOfRoad, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, 8L, "test", "road name")
      when(mockRoadLinkService.getRoadLinksByLinkIds(any[Set[String]])).thenReturn(Seq(linkBeforeRoundaboutMinorDiscontinuous, linkBeforeRoundaboutContinuous).map(toRoadLink))
      projectService.createProjectLinks(Seq(12347L.toString, 12346L.toString), project.id, RoadPart(9999, 1), Track.Combined, Discontinuity.MinorDiscontinuity, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, 8L, "test", "road name")

      val afterAllProjectLinksCreation = projectLinkDAO.fetchProjectLinks(project.id).sortBy(_.addrMRange.start)

      afterAllProjectLinksCreation.filter(_.linkId == 12345L.toString).head.discontinuity should be (Discontinuity.EndOfRoad)
      afterAllProjectLinksCreation.filter(_.linkId == 12346L.toString).head.discontinuity should be (Discontinuity.Continuous)
      afterAllProjectLinksCreation.filter(_.linkId == 12347L.toString).head.discontinuity should be (Discontinuity.MinorDiscontinuity)
    }
  }

  test("Test updateRoadwaysAndLinearLocationsWithProjectLinks When VIITE-2236 situation Then don't violate unique constraint on roadway_point table.") {
    runWithRollback {
      runUpdateToDb("""Insert into LINK (ID,SOURCE,ADJUSTED_TIMESTAMP,CREATED_TIME) values ('568121','4','1446398762000',to_timestamp('27.12.2019 13:12:50','DD.MM.YYYY HH24:MI:SS'))""")
      runUpdateToDb("""Insert into LINK (ID,SOURCE,ADJUSTED_TIMESTAMP,CREATED_TIME) values ('7256596','4','1498959782000',to_timestamp('27.12.2019 13:12:50','DD.MM.YYYY HH24:MI:SS'))""")
      runUpdateToDb("""Insert into LINK (ID,SOURCE,ADJUSTED_TIMESTAMP,CREATED_TIME) values ('7256590','4','1498959782000',to_timestamp('27.12.2019 13:12:50','DD.MM.YYYY HH24:MI:SS'))""")
      runUpdateToDb("""Insert into LINK (ID,SOURCE,ADJUSTED_TIMESTAMP,CREATED_TIME) values ('7256584','4','1498959782000',to_timestamp('27.12.2019 13:12:50','DD.MM.YYYY HH24:MI:SS'))""")
      runUpdateToDb("""Insert into LINK (ID,SOURCE,ADJUSTED_TIMESTAMP,CREATED_TIME) values ('7256586','4','1503961971000',to_timestamp('27.12.2019 13:12:50','DD.MM.YYYY HH24:MI:SS'))""")
      runUpdateToDb("""Insert into LINK (ID,SOURCE,ADJUSTED_TIMESTAMP,CREATED_TIME) values ('7256594','4','1533681057000',to_timestamp('27.12.2019 13:12:50','DD.MM.YYYY HH24:MI:SS'))""")
      runUpdateToDb("""Insert into LINK (ID,SOURCE,ADJUSTED_TIMESTAMP,CREATED_TIME) values ('568164','4','1449097206000',to_timestamp('27.12.2019 13:12:50','DD.MM.YYYY HH24:MI:SS'))""")
      runUpdateToDb("""Insert into LINK (ID,SOURCE,ADJUSTED_TIMESTAMP,CREATED_TIME) values ('568122','4','1449097206000',to_timestamp('27.12.2019 13:12:50','DD.MM.YYYY HH24:MI:SS'))""")

      runUpdateToDb("""Insert into ROADWAY (ID,ROADWAY_NUMBER,ROAD_NUMBER,ROAD_PART_NUMBER,TRACK,START_ADDR_M,END_ADDR_M,REVERSED,DISCONTINUITY,START_DATE,END_DATE,CREATED_BY,CREATED_TIME,ADMINISTRATIVE_CLASS,ELY,TERMINATED,VALID_FROM,VALID_TO) values ('1052907','40998','22006','12','0','0','215','0','5',to_date('01.01.2017','DD.MM.YYYY'),null,'import',to_timestamp('27.12.2019 13:12:51','DD.MM.YYYY HH24:MI:SS'),'1','2','0',to_date('28.12.2016','DD.MM.YYYY'),null)""")
      runUpdateToDb("""Insert into ROADWAY (ID,ROADWAY_NUMBER,ROAD_NUMBER,ROAD_PART_NUMBER,TRACK,START_ADDR_M,END_ADDR_M,REVERSED,DISCONTINUITY,START_DATE,END_DATE,CREATED_BY,CREATED_TIME,ADMINISTRATIVE_CLASS,ELY,TERMINATED,VALID_FROM,VALID_TO) values ('1052911','40998','22006','12','0','0','215','0','2',to_date('01.01.1989','DD.MM.YYYY'),to_date('31.12.2016','DD.MM.YYYY'),'import',to_timestamp('27.12.2019 13:12:51','DD.MM.YYYY HH24:MI:SS'),'1','2','0',to_date('28.12.2016','DD.MM.YYYY'),null)""")
      runUpdateToDb("""Insert into ROADWAY (ID,ROADWAY_NUMBER,ROAD_NUMBER,ROAD_PART_NUMBER,TRACK,START_ADDR_M,END_ADDR_M,REVERSED,DISCONTINUITY,START_DATE,END_DATE,CREATED_BY,CREATED_TIME,ADMINISTRATIVE_CLASS,ELY,TERMINATED,VALID_FROM,VALID_TO) values ('1052912','40999','22006','12','0','215','216','0','2',to_date('01.01.1989','DD.MM.YYYY'),to_date('30.12.1997','DD.MM.YYYY'),'import',to_timestamp('27.12.2019 13:12:51','DD.MM.YYYY HH24:MI:SS'),'1','2','1',to_date('31.01.2013','DD.MM.YYYY'),null)""")
      runUpdateToDb("""Insert into ROADWAY (ID,ROADWAY_NUMBER,ROAD_NUMBER,ROAD_PART_NUMBER,TRACK,START_ADDR_M,END_ADDR_M,REVERSED,DISCONTINUITY,START_DATE,END_DATE,CREATED_BY,CREATED_TIME,ADMINISTRATIVE_CLASS,ELY,TERMINATED,VALID_FROM,VALID_TO) values ('1052910','41000','22006','34','0','0','310','0','2',to_date('01.01.1989','DD.MM.YYYY'),null,'import',to_timestamp('27.12.2019 13:12:51','DD.MM.YYYY HH24:MI:SS'),'1','2','0',to_date('14.06.2016','DD.MM.YYYY'),null)""")
      runUpdateToDb("""Insert into ROADWAY (ID,ROADWAY_NUMBER,ROAD_NUMBER,ROAD_PART_NUMBER,TRACK,START_ADDR_M,END_ADDR_M,REVERSED,DISCONTINUITY,START_DATE,END_DATE,CREATED_BY,CREATED_TIME,ADMINISTRATIVE_CLASS,ELY,TERMINATED,VALID_FROM,VALID_TO) values ('1052909','6446223','22006','23','0','0','45','0','2',to_date('20.05.2007','DD.MM.YYYY'),null,'import',to_timestamp('27.12.2019 13:12:51','DD.MM.YYYY HH24:MI:SS'),'1','2','0',to_date('14.06.2016','DD.MM.YYYY'),null)""")
      runUpdateToDb("""Insert into ROADWAY (ID,ROADWAY_NUMBER,ROAD_NUMBER,ROAD_PART_NUMBER,TRACK,START_ADDR_M,END_ADDR_M,REVERSED,DISCONTINUITY,START_DATE,END_DATE,CREATED_BY,CREATED_TIME,ADMINISTRATIVE_CLASS,ELY,TERMINATED,VALID_FROM,VALID_TO) values ('1052906','6446225','22006','45','0','0','248','0','1',to_date('20.05.2007','DD.MM.YYYY'),null,'import',to_timestamp('27.12.2019 13:12:51','DD.MM.YYYY HH24:MI:SS'),'1','2','0',to_date('25.01.2017','DD.MM.YYYY'),null)""")
      runUpdateToDb("""Insert into ROADWAY (ID,ROADWAY_NUMBER,ROAD_NUMBER,ROAD_PART_NUMBER,TRACK,START_ADDR_M,END_ADDR_M,REVERSED,DISCONTINUITY,START_DATE,END_DATE,CREATED_BY,CREATED_TIME,ADMINISTRATIVE_CLASS,ELY,TERMINATED,VALID_FROM,VALID_TO) values ('1052908','166883589','22006','12','0','215','295','0','2',to_date('01.01.2017','DD.MM.YYYY'),null,'import',to_timestamp('27.12.2019 13:12:51','DD.MM.YYYY HH24:MI:SS'),'1','2','0',to_date('28.12.2016','DD.MM.YYYY'),null)""")

      runUpdateToDb("""Insert into LINEAR_LOCATION (ID,ROADWAY_NUMBER,ORDER_NUMBER,LINK_ID,START_MEASURE,END_MEASURE,SIDE,GEOMETRY,VALID_FROM,VALID_TO,CREATED_BY,CREATED_TIME) values ('1039046',  '6446225','1','7256596',      0, 248.793,'3', ST_GeomFromText('LINESTRING(267357.266 6789458.693 0 248.793, 267131.966 6789520.79 0 248.793)', 3067),  to_date('25.01.2017','DD.MM.YYYY'),null,'import',to_timestamp('27.12.2019 13:12:51','DD.MM.YYYY HH24:MI:SS'))""")
      runUpdateToDb("""Insert into LINEAR_LOCATION (ID,ROADWAY_NUMBER,ORDER_NUMBER,LINK_ID,START_MEASURE,END_MEASURE,SIDE,GEOMETRY,VALID_FROM,VALID_TO,CREATED_BY,CREATED_TIME) values ('1039047',    '40998','1', '568164',      0,   7.685,'3', ST_GeomFromText('LINESTRING(267444.126 6789234.9 0 7.685, 267437.206 6789238.158 0 7.685)', 3067),       to_date('28.12.2016','DD.MM.YYYY'),null,'import',to_timestamp('27.12.2019 13:12:51','DD.MM.YYYY HH24:MI:SS'))""")
      runUpdateToDb("""Insert into LINEAR_LOCATION (ID,ROADWAY_NUMBER,ORDER_NUMBER,LINK_ID,START_MEASURE,END_MEASURE,SIDE,GEOMETRY,VALID_FROM,VALID_TO,CREATED_BY,CREATED_TIME) values ('1039048',    '40998','2','7256584',      0, 171.504,'2', ST_GeomFromText('LINESTRING(267444.126 6789234.9 0 171.504, 267597.461 6789260.633 0 171.504)', 3067),   to_date('28.12.2016','DD.MM.YYYY'),null,'import',to_timestamp('27.12.2019 13:12:51','DD.MM.YYYY HH24:MI:SS'))""")
      runUpdateToDb("""Insert into LINEAR_LOCATION (ID,ROADWAY_NUMBER,ORDER_NUMBER,LINK_ID,START_MEASURE,END_MEASURE,SIDE,GEOMETRY,VALID_FROM,VALID_TO,CREATED_BY,CREATED_TIME) values ('1039049',    '40998','3','7256586',      0,  38.688,'2', ST_GeomFromText('LINESTRING(267597.461 6789260.633 0 38.688, 267518.603 6789333.458 0 38.688)', 3067),   to_date('28.12.2016','DD.MM.YYYY'),null,'import',to_timestamp('27.12.2019 13:12:51','DD.MM.YYYY HH24:MI:SS'))""")
      runUpdateToDb("""Insert into LINEAR_LOCATION (ID,ROADWAY_NUMBER,ORDER_NUMBER,LINK_ID,START_MEASURE,END_MEASURE,SIDE,GEOMETRY,VALID_FROM,VALID_TO,CREATED_BY,CREATED_TIME) values ('1039050','166883589','1','7256586', 38.688, 120.135,'2', ST_GeomFromText('LINESTRING(267597.461 6789260.633 0 120.135, 267518.603 6789333.458 0 120.135)', 3067), to_date('28.12.2016','DD.MM.YYYY'),null,'import',to_timestamp('27.12.2019 13:12:51','DD.MM.YYYY HH24:MI:SS'))""")
      runUpdateToDb("""Insert into LINEAR_LOCATION (ID,ROADWAY_NUMBER,ORDER_NUMBER,LINK_ID,START_MEASURE,END_MEASURE,SIDE,GEOMETRY,VALID_FROM,VALID_TO,CREATED_BY,CREATED_TIME) values ('1039051',  '6446223','1','7256594',      0,  44.211,'3', ST_GeomFromText('LINESTRING(267597.461 6789260.633 0 44.211, 267633.898 6789283.726 0 44.211)', 3067),   to_date('14.06.2016','DD.MM.YYYY'),null,'import',to_timestamp('27.12.2019 13:12:51','DD.MM.YYYY HH24:MI:SS'))""")
      runUpdateToDb("""Insert into LINEAR_LOCATION (ID,ROADWAY_NUMBER,ORDER_NUMBER,LINK_ID,START_MEASURE,END_MEASURE,SIDE,GEOMETRY,VALID_FROM,VALID_TO,CREATED_BY,CREATED_TIME) values ('1039052',    '41000','1','7256590',      0, 130.538,'2', ST_GeomFromText('LINESTRING(267431.685 6789375.9 0 130.538, 267357.266 6789458.693 0 130.538)', 3067),   to_date('14.06.2016','DD.MM.YYYY'),null,'import',to_timestamp('27.12.2019 13:12:51','DD.MM.YYYY HH24:MI:SS'))""")
      runUpdateToDb("""Insert into LINEAR_LOCATION (ID,ROADWAY_NUMBER,ORDER_NUMBER,LINK_ID,START_MEASURE,END_MEASURE,SIDE,GEOMETRY,VALID_FROM,VALID_TO,CREATED_BY,CREATED_TIME) values ('1039053',    '41000','2', '568121',      0, 177.547,'3', ST_GeomFromText('LINESTRING(267525.375 6789455.936 0 177.547, 267357.266 6789458.693 0 177.547)', 3067), to_date('14.06.2016','DD.MM.YYYY'),null,'import',to_timestamp('27.12.2019 13:12:51','DD.MM.YYYY HH24:MI:SS'))""")
      runUpdateToDb("""Insert into LINEAR_LOCATION (ID,ROADWAY_NUMBER,ORDER_NUMBER,LINK_ID,START_MEASURE,END_MEASURE,SIDE,GEOMETRY,VALID_FROM,VALID_TO,CREATED_BY,CREATED_TIME) values ('1039054',    '41000','3', '568122',      0,   9.514,'3', ST_GeomFromText('LINESTRING(267534.612 6789453.659 0 9.514, 267525.375 6789455.936 0 9.514)', 3067),     to_date('14.06.2016','DD.MM.YYYY'),null,'import',to_timestamp('27.12.2019 13:12:51','DD.MM.YYYY HH24:MI:SS'))""")

      runUpdateToDb("""Insert into ROADWAY_POINT (ID,ROADWAY_NUMBER,ADDR_M,CREATED_BY,CREATED_TIME,MODIFIED_BY,MODIFIED_TIME) values ('1019231','6446225','0','import',to_timestamp('27.12.2019 13:12:50','DD.MM.YYYY HH24:MI:SSXFF'),'import',to_timestamp('27.12.2019 13:12:50','DD.MM.YYYY HH24:MI:SS'))""")
      runUpdateToDb("""Insert into ROADWAY_POINT (ID,ROADWAY_NUMBER,ADDR_M,CREATED_BY,CREATED_TIME,MODIFIED_BY,MODIFIED_TIME) values ('1019232','6446225','248','import',to_timestamp('27.12.2019 13:12:50','DD.MM.YYYY HH24:MI:SSXFF'),'import',to_timestamp('27.12.2019 13:12:50','DD.MM.YYYY HH24:MI:SS'))""")
      runUpdateToDb("""Insert into ROADWAY_POINT (ID,ROADWAY_NUMBER,ADDR_M,CREATED_BY,CREATED_TIME,MODIFIED_BY,MODIFIED_TIME) values ('1019233','40998','0','import',to_timestamp('27.12.2019 13:12:51','DD.MM.YYYY HH24:MI:SSXFF'),'import',to_timestamp('27.12.2019 13:12:51','DD.MM.YYYY HH24:MI:SS'))""")
      runUpdateToDb("""Insert into ROADWAY_POINT (ID,ROADWAY_NUMBER,ADDR_M,CREATED_BY,CREATED_TIME,MODIFIED_BY,MODIFIED_TIME) values ('1019234','166883589','295','import',to_timestamp('27.12.2019 13:12:51','DD.MM.YYYY HH24:MI:SSXFF'),'import',to_timestamp('27.12.2019 13:12:51','DD.MM.YYYY HH24:MI:SS'))""")
      runUpdateToDb("""Insert into ROADWAY_POINT (ID,ROADWAY_NUMBER,ADDR_M,CREATED_BY,CREATED_TIME,MODIFIED_BY,MODIFIED_TIME) values ('1019235','6446223','0','import',to_timestamp('27.12.2019 13:12:51','DD.MM.YYYY HH24:MI:SSXFF'),'import',to_timestamp('27.12.2019 13:12:51','DD.MM.YYYY HH24:MI:SS'))""")
      runUpdateToDb("""Insert into ROADWAY_POINT (ID,ROADWAY_NUMBER,ADDR_M,CREATED_BY,CREATED_TIME,MODIFIED_BY,MODIFIED_TIME) values ('1019236','6446223','45','import',to_timestamp('27.12.2019 13:12:51','DD.MM.YYYY HH24:MI:SSXFF'),'import',to_timestamp('27.12.2019 13:12:51','DD.MM.YYYY HH24:MI:SS'))""")
      runUpdateToDb("""Insert into ROADWAY_POINT (ID,ROADWAY_NUMBER,ADDR_M,CREATED_BY,CREATED_TIME,MODIFIED_BY,MODIFIED_TIME) values ('1019237','41000','0','import',to_timestamp('27.12.2019 13:12:51','DD.MM.YYYY HH24:MI:SSXFF'),'import',to_timestamp('27.12.2019 13:12:51','DD.MM.YYYY HH24:MI:SS'))""")
      runUpdateToDb("""Insert into ROADWAY_POINT (ID,ROADWAY_NUMBER,ADDR_M,CREATED_BY,CREATED_TIME,MODIFIED_BY,MODIFIED_TIME) values ('1019238','41000','310','import',to_timestamp('27.12.2019 13:12:51','DD.MM.YYYY HH24:MI:SSXFF'),'import',to_timestamp('27.12.2019 13:12:51','DD.MM.YYYY HH24:MI:SS'))""")
      runUpdateToDb("""Insert into ROADWAY_POINT (ID,ROADWAY_NUMBER,ADDR_M,CREATED_BY,CREATED_TIME,MODIFIED_BY,MODIFIED_TIME) values ('1019239','40998','215','k567997',to_timestamp('27.12.2019 13:26:55','DD.MM.YYYY HH24:MI:SSXFF'),'k567997',to_timestamp('27.12.2019 13:26:55','DD.MM.YYYY HH24:MI:SS'))""")
      runUpdateToDb("""Insert into ROADWAY_POINT (ID,ROADWAY_NUMBER,ADDR_M,CREATED_BY,CREATED_TIME,MODIFIED_BY,MODIFIED_TIME) values ('1019240','40998','177','k567997',to_timestamp('27.12.2019 13:26:55','DD.MM.YYYY HH24:MI:SSXFF'),'k567997',to_timestamp('27.12.2019 13:26:55','DD.MM.YYYY HH24:MI:SS'))""")
      runUpdateToDb("""Insert into ROADWAY_POINT (ID,ROADWAY_NUMBER,ADDR_M,CREATED_BY,CREATED_TIME,MODIFIED_BY,MODIFIED_TIME) values ('1019241','41000','275','k567997',to_timestamp('27.12.2019 13:26:55','DD.MM.YYYY HH24:MI:SSXFF'),'k567997',to_timestamp('27.12.2019 13:26:55','DD.MM.YYYY HH24:MI:SS'))""")
      runUpdateToDb("""Insert into ROADWAY_POINT (ID,ROADWAY_NUMBER,ADDR_M,CREATED_BY,CREATED_TIME,MODIFIED_BY,MODIFIED_TIME) values ('1019242','40998','8','k567997',to_timestamp('27.12.2019 13:26:55','DD.MM.YYYY HH24:MI:SSXFF'),'k567997',to_timestamp('27.12.2019 13:26:55','DD.MM.YYYY HH24:MI:SS'))""")
      runUpdateToDb("""Insert into ROADWAY_POINT (ID,ROADWAY_NUMBER,ADDR_M,CREATED_BY,CREATED_TIME,MODIFIED_BY,MODIFIED_TIME) values ('1019243','41000','300','k567997',to_timestamp('27.12.2019 13:26:55','DD.MM.YYYY HH24:MI:SSXFF'),'k567997',to_timestamp('27.12.2019 13:26:55','DD.MM.YYYY HH24:MI:SS'))""")
      runUpdateToDb("""Insert into ROADWAY_POINT (ID,ROADWAY_NUMBER,ADDR_M,CREATED_BY,CREATED_TIME,MODIFIED_BY,MODIFIED_TIME) values ('1019244','41000','127','k567997',to_timestamp('27.12.2019 13:26:55','DD.MM.YYYY HH24:MI:SSXFF'),'k567997',to_timestamp('27.12.2019 13:26:55','DD.MM.YYYY HH24:MI:SS'))""")

      runUpdateToDb("""Insert into CALIBRATION_POINT (ID,ROADWAY_POINT_ID,LINK_ID,START_END,TYPE,VALID_FROM,VALID_TO,CREATED_BY,CREATED_TIME) values ('1020345','1019231','7256596','0','3',to_date('27.12.2019','DD.MM.YYYY'),null,'import',to_timestamp('27.12.2019 13:12:51','DD.MM.YYYY HH24:MI:SS'))""")
      runUpdateToDb("""Insert into CALIBRATION_POINT (ID,ROADWAY_POINT_ID,LINK_ID,START_END,TYPE,VALID_FROM,VALID_TO,CREATED_BY,CREATED_TIME) values ('1020346','1019232','7256596','1','3',to_date('27.12.2019','DD.MM.YYYY'),null,'import',to_timestamp('27.12.2019 13:12:51','DD.MM.YYYY HH24:MI:SS'))""")
      runUpdateToDb("""Insert into CALIBRATION_POINT (ID,ROADWAY_POINT_ID,LINK_ID,START_END,TYPE,VALID_FROM,VALID_TO,CREATED_BY,CREATED_TIME) values ('1020347','1019233','568164','0','3',to_date('27.12.2019','DD.MM.YYYY'),null,'import',to_timestamp('27.12.2019 13:12:51','DD.MM.YYYY HH24:MI:SS'))""")
      runUpdateToDb("""Insert into CALIBRATION_POINT (ID,ROADWAY_POINT_ID,LINK_ID,START_END,TYPE,VALID_FROM,VALID_TO,CREATED_BY,CREATED_TIME) values ('1020348','1019234','7256586','1','3',to_date('27.12.2019','DD.MM.YYYY'),null,'import',to_timestamp('27.12.2019 13:12:51','DD.MM.YYYY HH24:MI:SS'))""")
      runUpdateToDb("""Insert into CALIBRATION_POINT (ID,ROADWAY_POINT_ID,LINK_ID,START_END,TYPE,VALID_FROM,VALID_TO,CREATED_BY,CREATED_TIME) values ('1020349','1019235','7256594','0','3',to_date('27.12.2019','DD.MM.YYYY'),null,'import',to_timestamp('27.12.2019 13:12:51','DD.MM.YYYY HH24:MI:SS'))""")
      runUpdateToDb("""Insert into CALIBRATION_POINT (ID,ROADWAY_POINT_ID,LINK_ID,START_END,TYPE,VALID_FROM,VALID_TO,CREATED_BY,CREATED_TIME) values ('1020350','1019236','7256594','1','3',to_date('27.12.2019','DD.MM.YYYY'),null,'import',to_timestamp('27.12.2019 13:12:51','DD.MM.YYYY HH24:MI:SS'))""")
      runUpdateToDb("""Insert into CALIBRATION_POINT (ID,ROADWAY_POINT_ID,LINK_ID,START_END,TYPE,VALID_FROM,VALID_TO,CREATED_BY,CREATED_TIME) values ('1020351','1019237','7256590','0','3',to_date('27.12.2019','DD.MM.YYYY'),null,'import',to_timestamp('27.12.2019 13:12:51','DD.MM.YYYY HH24:MI:SS'))""")
      runUpdateToDb("""Insert into CALIBRATION_POINT (ID,ROADWAY_POINT_ID,LINK_ID,START_END,TYPE,VALID_FROM,VALID_TO,CREATED_BY,CREATED_TIME) values ('1020352','1019238','568122','1','3',to_date('27.12.2019','DD.MM.YYYY'),null,'import',to_timestamp('27.12.2019 13:12:51','DD.MM.YYYY HH24:MI:SS'))""")

      runUpdateToDb("""Insert into PROJECT (ID,STATE,NAME,CREATED_BY,CREATED_DATE,MODIFIED_BY,MODIFIED_DATE,ADD_INFO,START_DATE,STATUS_INFO,COORD_X,COORD_Y,ZOOM) values ('1000351','2','aa','silari',to_date('27.12.2019','DD.MM.YYYY'),'-',to_date('27.12.2019','DD.MM.YYYY'),null,to_date('01.01.2020','DD.MM.YYYY'),null,267287.82,6789454.18,'12')""")

      runUpdateToDb("""Insert into PROJECT_RESERVED_ROAD_PART (ID,ROAD_NUMBER,ROAD_PART_NUMBER,PROJECT_ID,CREATED_BY) values ('1000366','22006','56','1000351','-')""")
      runUpdateToDb("""Insert into PROJECT_RESERVED_ROAD_PART (ID,ROAD_NUMBER,ROAD_PART_NUMBER,PROJECT_ID,CREATED_BY) values ('1000365','22006','68','1000351','-')""")
      runUpdateToDb("""Insert into PROJECT_RESERVED_ROAD_PART (ID,ROAD_NUMBER,ROAD_PART_NUMBER,PROJECT_ID,CREATED_BY) values ('1000352','22006','12','1000351','silari')""")
      runUpdateToDb("""Insert into PROJECT_RESERVED_ROAD_PART (ID,ROAD_NUMBER,ROAD_PART_NUMBER,PROJECT_ID,CREATED_BY) values ('1000353','22006','23','1000351','silari')""")
      runUpdateToDb("""Insert into PROJECT_RESERVED_ROAD_PART (ID,ROAD_NUMBER,ROAD_PART_NUMBER,PROJECT_ID,CREATED_BY) values ('1000354','22006','34','1000351','silari')""")
      runUpdateToDb("""Insert into PROJECT_RESERVED_ROAD_PART (ID,ROAD_NUMBER,ROAD_PART_NUMBER,PROJECT_ID,CREATED_BY) values ('1000369','22006','24','1000351','-')""")
      runUpdateToDb("""Insert into PROJECT_RESERVED_ROAD_PART (ID,ROAD_NUMBER,ROAD_PART_NUMBER,PROJECT_ID,CREATED_BY) values ('1000355','22006','45','1000351','silari')""")
      runUpdateToDb("""Insert into PROJECT_RESERVED_ROAD_PART (ID,ROAD_NUMBER,ROAD_PART_NUMBER,PROJECT_ID,CREATED_BY) values ('1000368','22006','67','1000351','-')""")

      //                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                  ID,PROJECT_ID,TRACK,DISCONTINUITY_TYPE,ROAD_NUMBER,ROAD_PART_NUMBER,START_ADDR_M,END_ADDR_M,CREATED_BY,MODIFIED_BY,CREATED_DATE,MODIFIED_DATE,STATUS,ROAD_TYPE,ROADWAY_ID,LINEAR_LOCATION_ID,CONNECTED_LINK_ID,ELY,REVERSED,SIDE,START_MEASURE,END_MEASURE,LINK_ID,ADJUSTED_TIMESTAMP,LINK_SOURCE,GEOMETRY,ORIGINAL_START_ADDR_M,ORIGINAL_END_ADDR_M,ROADWAY_NUMBER,START_CALIBRATION_POINT,END_CALIBRATION_POINT,ORIG_START_CALIBRATION_POINT,ORIG_END_CALIBRATION_POINT
      runUpdateToDb("""Insert into PROJECT_LINK (ID,PROJECT_ID,TRACK,DISCONTINUITY_TYPE,ROAD_NUMBER,ROAD_PART_NUMBER,START_ADDR_M,END_ADDR_M,CREATED_BY,MODIFIED_BY,CREATED_DATE,MODIFIED_DATE,STATUS,ADMINISTRATIVE_CLASS,ROADWAY_ID,LINEAR_LOCATION_ID,CONNECTED_LINK_ID,ELY,REVERSED,SIDE,START_MEASURE,END_MEASURE,LINK_ID,ADJUSTED_TIMESTAMP,LINK_SOURCE,GEOMETRY,ORIGINAL_START_ADDR_M,ORIGINAL_END_ADDR_M,ROADWAY_NUMBER,START_CALIBRATION_POINT,END_CALIBRATION_POINT,ORIG_START_CALIBRATION_POINT,ORIG_END_CALIBRATION_POINT) values ('1000356','1000351','0','2','22006','67','169','177','silari','silari',to_date('27.12.2019','DD.MM.YYYY'),to_date('27.12.2019','DD.MM.YYYY'),'3','1','1052907','1039047',null,'2','1','2',0,7.685,'568164','1449097206000','4',      ST_GeomFromText('LINESTRING(267444.126 6789234.9 53.176999999996, 267440.935 6789235.99 53.2259999999951, 267437.206395653 6789238.15776997 53.2499974535623)', 3067),'0','8','166883765',0,3,0,0)""")
      runUpdateToDb("""Insert into PROJECT_LINK (ID,PROJECT_ID,TRACK,DISCONTINUITY_TYPE,ROAD_NUMBER,ROAD_PART_NUMBER,START_ADDR_M,END_ADDR_M,CREATED_BY,MODIFIED_BY,CREATED_DATE,MODIFIED_DATE,STATUS,ADMINISTRATIVE_CLASS,ROADWAY_ID,LINEAR_LOCATION_ID,CONNECTED_LINK_ID,ELY,REVERSED,SIDE,START_MEASURE,END_MEASURE,LINK_ID,ADJUSTED_TIMESTAMP,LINK_SOURCE,GEOMETRY,ORIGINAL_START_ADDR_M,ORIGINAL_END_ADDR_M,ROADWAY_NUMBER,START_CALIBRATION_POINT,END_CALIBRATION_POINT,ORIG_START_CALIBRATION_POINT,ORIG_END_CALIBRATION_POINT) values ('1000357','1000351','0','5','22006','67','0','169','silari','silari',to_date('27.12.2019','DD.MM.YYYY'),to_date('27.12.2019','DD.MM.YYYY'),'3','1','1052907','1039048',null,'2','1','3',0,171.504,'7256584','1498959782000','4',     ST_GeomFromText('LINESTRING(267444.126 6789234.9 53.176999999996, 267464.548 6789227.526 52.8959999999934, 267496.884 6789219.216 52.2939999999944, 267515.938 6789216.916 51.7979999999952, 267535.906 6789218.028 51.1319999999978, 267556.73 6789224.333 50.304999999993, 267574.187 6789234.039 49.5789999999979, 267588.124 6789247.565 49.0240000000049, 267597.460867063 6789260.63281394 48.7730035736591)', 3067),'8','177','166883765',3,3,0,0)""")
      runUpdateToDb("""Insert into PROJECT_LINK (ID,PROJECT_ID,TRACK,DISCONTINUITY_TYPE,ROAD_NUMBER,ROAD_PART_NUMBER,START_ADDR_M,END_ADDR_M,CREATED_BY,MODIFIED_BY,CREATED_DATE,MODIFIED_DATE,STATUS,ADMINISTRATIVE_CLASS,ROADWAY_ID,LINEAR_LOCATION_ID,CONNECTED_LINK_ID,ELY,REVERSED,SIDE,START_MEASURE,END_MEASURE,LINK_ID,ADJUSTED_TIMESTAMP,LINK_SOURCE,GEOMETRY,ORIGINAL_START_ADDR_M,ORIGINAL_END_ADDR_M,ROADWAY_NUMBER,START_CALIBRATION_POINT,END_CALIBRATION_POINT,ORIG_START_CALIBRATION_POINT,ORIG_END_CALIBRATION_POINT) values ('1000358','1000351','0','5','22006','56','80','118','silari','silari',to_date('27.12.2019','DD.MM.YYYY'),to_date('27.12.2019','DD.MM.YYYY'),'3','1','1052907','1039049',null,'2','1','3',0,38.688,'7256586','1503961971000','4',     ST_GeomFromText('LINESTRING(267597.461 6789260.633 48.773000000001, 267597.534 6789260.792 48.7719999999972, 267600.106 6789269.768 48.6059999999998, 267600.106 6789280.257 48.4780000000028, 267597.713 6789287.648 48.4360000000015, 267591.642 6789293.381 48.4370000000054, 267589.345139337 6789294.52943033 48.4244527667907)', 3067),'177','215','166883761',3,3,0,0)""")
      runUpdateToDb("""Insert into PROJECT_LINK (ID,PROJECT_ID,TRACK,DISCONTINUITY_TYPE,ROAD_NUMBER,ROAD_PART_NUMBER,START_ADDR_M,END_ADDR_M,CREATED_BY,MODIFIED_BY,CREATED_DATE,MODIFIED_DATE,STATUS,ADMINISTRATIVE_CLASS,ROADWAY_ID,LINEAR_LOCATION_ID,CONNECTED_LINK_ID,ELY,REVERSED,SIDE,START_MEASURE,END_MEASURE,LINK_ID,ADJUSTED_TIMESTAMP,LINK_SOURCE,GEOMETRY,ORIGINAL_START_ADDR_M,ORIGINAL_END_ADDR_M,ROADWAY_NUMBER,START_CALIBRATION_POINT,END_CALIBRATION_POINT,ORIG_START_CALIBRATION_POINT,ORIG_END_CALIBRATION_POINT) values ('1000359','1000351','0','5','22006','56','0','80','silari','silari',to_date('27.12.2019','DD.MM.YYYY'),to_date('27.12.2019','DD.MM.YYYY'),'3','1','1052908','1039050',null,'2','1','3',38.688,120.135,'7256586','1503961971000','4', ST_GeomFromText('LINESTRING(267589.345139337 6789294.52943033 48.4244527667907, 267578.828 6789299.788 48.3669999999984, 267559.269 6789308.892 48.2510000000038, 267546.792 6789314.963 48.2390000000014, 267533.979 6789321.707 48.2140000000072, 267524.2 6789327.103 48.226999999999, 267521.164 6789329.463 48.247000000003, 267518.603162863 6789333.45774594 48.2559994276524)', 3067),'215','295','166883589',3,0,0,0)""")
      runUpdateToDb("""Insert into PROJECT_LINK (ID,PROJECT_ID,TRACK,DISCONTINUITY_TYPE,ROAD_NUMBER,ROAD_PART_NUMBER,START_ADDR_M,END_ADDR_M,CREATED_BY,MODIFIED_BY,CREATED_DATE,MODIFIED_DATE,STATUS,ADMINISTRATIVE_CLASS,ROADWAY_ID,LINEAR_LOCATION_ID,CONNECTED_LINK_ID,ELY,REVERSED,SIDE,START_MEASURE,END_MEASURE,LINK_ID,ADJUSTED_TIMESTAMP,LINK_SOURCE,GEOMETRY,ORIGINAL_START_ADDR_M,ORIGINAL_END_ADDR_M,ROADWAY_NUMBER,START_CALIBRATION_POINT,END_CALIBRATION_POINT,ORIG_START_CALIBRATION_POINT,ORIG_END_CALIBRATION_POINT) values ('1000360','1000351','0','1','22006','68','0','45','silari','silari',to_date('27.12.2019','DD.MM.YYYY'),to_date('27.12.2019','DD.MM.YYYY'),'3','1','1052909','1039051',null,'2','1','2',0,44.211,'7256594','1533681057000','4',       ST_GeomFromText('LINESTRING(267597.461 6789260.633 48.773000000001, 267603.782 6789267.752 48.6589999999997, 267610.19 6789273.485 48.5580000000045, 267616.597 6789277.869 48.4689999999973, 267623.131 6789280.868 48.4400000000023, 267633.897953407 6789283.72598763 48.4409999956788)', 3067),'0','45','6446223',3,3,0,0)""")
      runUpdateToDb("""Insert into PROJECT_LINK (ID,PROJECT_ID,TRACK,DISCONTINUITY_TYPE,ROAD_NUMBER,ROAD_PART_NUMBER,START_ADDR_M,END_ADDR_M,CREATED_BY,MODIFIED_BY,CREATED_DATE,MODIFIED_DATE,STATUS,ADMINISTRATIVE_CLASS,ROADWAY_ID,LINEAR_LOCATION_ID,CONNECTED_LINK_ID,ELY,REVERSED,SIDE,START_MEASURE,END_MEASURE,LINK_ID,ADJUSTED_TIMESTAMP,LINK_SOURCE,GEOMETRY,ORIGINAL_START_ADDR_M,ORIGINAL_END_ADDR_M,ROADWAY_NUMBER,START_CALIBRATION_POINT,END_CALIBRATION_POINT,ORIG_START_CALIBRATION_POINT,ORIG_END_CALIBRATION_POINT) values ('1000361','1000351','0','5','22006','12','0','127','silari','silari',to_date('27.12.2019','DD.MM.YYYY'),to_date('27.12.2019','DD.MM.YYYY'),'3','1','1052910','1039052',null,'2','0','2',0,130.538,'7256590','1498959782000','4',     ST_GeomFromText('LINESTRING(267431.685 6789375.9 48.4470000000001, 267424.673 6789383.39 48.4180000000051, 267415.075 6789389.356 48.448000000004, 267401.067 6789396.879 48.5190000000002, 267384.985 6789404.661 48.6150000000052, 267366.307 6789414.259 48.6889999999985, 267356.079 6789421.259 48.6999999999971, 267351.522 6789425.931 48.7119999999995, 267349.187 6789432.416 48.7799999999988, 267348.928 6789442.533 48.9700000000012, 267352.041 6789450.574 49.1230000000069, 267357.266 6789458.693 49.3099999999977)', 3067),'0','127','166883763',3,3,0,0)""")
      runUpdateToDb("""Insert into PROJECT_LINK (ID,PROJECT_ID,TRACK,DISCONTINUITY_TYPE,ROAD_NUMBER,ROAD_PART_NUMBER,START_ADDR_M,END_ADDR_M,CREATED_BY,MODIFIED_BY,CREATED_DATE,MODIFIED_DATE,STATUS,ADMINISTRATIVE_CLASS,ROADWAY_ID,LINEAR_LOCATION_ID,CONNECTED_LINK_ID,ELY,REVERSED,SIDE,START_MEASURE,END_MEASURE,LINK_ID,ADJUSTED_TIMESTAMP,LINK_SOURCE,GEOMETRY,ORIGINAL_START_ADDR_M,ORIGINAL_END_ADDR_M,ROADWAY_NUMBER,START_CALIBRATION_POINT,END_CALIBRATION_POINT,ORIG_START_CALIBRATION_POINT,ORIG_END_CALIBRATION_POINT) values ('1000362','1000351','0','5','22006','23','0','174','silari','silari',to_date('27.12.2019','DD.MM.YYYY'),to_date('27.12.2019','DD.MM.YYYY'),'3','1','1052910','1039053',null,'2','0','3',0,177.547,'568121','1446398762000','4',      ST_GeomFromText('LINESTRING(267525.375 6789455.936 52.7309999999998, 267499.516 6789463.571 52.426999999996, 267458.911 6789473.392 52.0339999999997, 267426.281 6789480.881 51.4360000000015, 267403.97 6789481.494 50.7459999999992, 267378.849 6789475.013 49.9879999999976, 267357.54 6789459.007 49.3179999999993, 267357.266194778 6789458.69322321 49.3100056869441)', 3067),'127','301','166883762',3,0,0,0)""")
      runUpdateToDb("""Insert into PROJECT_LINK (ID,PROJECT_ID,TRACK,DISCONTINUITY_TYPE,ROAD_NUMBER,ROAD_PART_NUMBER,START_ADDR_M,END_ADDR_M,CREATED_BY,MODIFIED_BY,CREATED_DATE,MODIFIED_DATE,STATUS,ADMINISTRATIVE_CLASS,ROADWAY_ID,LINEAR_LOCATION_ID,CONNECTED_LINK_ID,ELY,REVERSED,SIDE,START_MEASURE,END_MEASURE,LINK_ID,ADJUSTED_TIMESTAMP,LINK_SOURCE,GEOMETRY,ORIGINAL_START_ADDR_M,ORIGINAL_END_ADDR_M,ROADWAY_NUMBER,START_CALIBRATION_POINT,END_CALIBRATION_POINT,ORIG_START_CALIBRATION_POINT,ORIG_END_CALIBRATION_POINT) values ('1000363','1000351','0','2','22006','23','174','183','silari','silari',to_date('27.12.2019','DD.MM.YYYY'),to_date('27.12.2019','DD.MM.YYYY'),'3','1','1052910','1039054',null,'2','0','3',0,9.514,'568122','1449097206000','4',      ST_GeomFromText('LINESTRING(267534.612 6789453.659 52.7939999999944, 267529.741 6789454.905 52.8300000000017, 267525.375 6789455.936 52.7309999999998)', 3067),'301','310','166883762',0,3,0,0)""")
      runUpdateToDb("""Insert into PROJECT_LINK (ID,PROJECT_ID,TRACK,DISCONTINUITY_TYPE,ROAD_NUMBER,ROAD_PART_NUMBER,START_ADDR_M,END_ADDR_M,CREATED_BY,MODIFIED_BY,CREATED_DATE,MODIFIED_DATE,STATUS,ADMINISTRATIVE_CLASS,ROADWAY_ID,LINEAR_LOCATION_ID,CONNECTED_LINK_ID,ELY,REVERSED,SIDE,START_MEASURE,END_MEASURE,LINK_ID,ADJUSTED_TIMESTAMP,LINK_SOURCE,GEOMETRY,ORIGINAL_START_ADDR_M,ORIGINAL_END_ADDR_M,ROADWAY_NUMBER,START_CALIBRATION_POINT,END_CALIBRATION_POINT,ORIG_START_CALIBRATION_POINT,ORIG_END_CALIBRATION_POINT) values ('1000364','1000351','0','2','22006','24','0','248','silari','silari',to_date('27.12.2019','DD.MM.YYYY'),to_date('27.12.2019','DD.MM.YYYY'),'3','1','1052906','1039046',null,'2','1','2',0,248.793,'7256596','1498959782000','4',     ST_GeomFromText('LINESTRING(267357.266 6789458.693 49.3099999999977, 267343.965 6789448.141 49.0489999999991, 267334.962 6789442.665 48.8600000000006, 267328.617 6789440.381 48.7949999999983, 267322.527 6789439.365 48.8000000000029, 267314.914 6789441.141 48.8439999999973, 267303.749 6789445.455 48.976999999999, 267287.508 6789453.576 49.0160000000033, 267268.999 6789462.419 49.2119999999995, 267228.658 6789482.321 49.3870000000024, 267201.225 6789496.844 49.4649999999965, 267167.876 6789511.367 49.7329999999929, 267147.437 6789519.436 49.9670000000042, 267131.966200167 6789520.78998248 50.2599962090907)', 3067),'0','248','6446225',3,3,0,0)""")

      runUpdateToDb("""Insert into PROJECT_LINK_NAME (ID,PROJECT_ID,ROAD_NUMBER,ROAD_NAME) values ('17','1000351','22006','MOMMOLAN RAMPIT')""")

      runUpdateToDb("""Insert into ROADWAY_CHANGES (PROJECT_ID,CHANGE_TYPE,OLD_ROAD_NUMBER,NEW_ROAD_NUMBER,OLD_ROAD_PART_NUMBER,NEW_ROAD_PART_NUMBER,OLD_TRACK,NEW_TRACK,OLD_START_ADDR_M,NEW_START_ADDR_M,OLD_END_ADDR_M,NEW_END_ADDR_M,NEW_DISCONTINUITY,NEW_ADMINISTRATIVE_CLASS,NEW_ELY,OLD_ADMINISTRATIVE_CLASS,OLD_DISCONTINUITY,OLD_ELY,REVERSED,ROADWAY_CHANGE_ID) values ('1000351','3','22006','22006','12','67','0','0','0','0','177','177','2','1','2','1','5','2','1','1000753')""")
      runUpdateToDb("""Insert into ROADWAY_CHANGES (PROJECT_ID,CHANGE_TYPE,OLD_ROAD_NUMBER,NEW_ROAD_NUMBER,OLD_ROAD_PART_NUMBER,NEW_ROAD_PART_NUMBER,OLD_TRACK,NEW_TRACK,OLD_START_ADDR_M,NEW_START_ADDR_M,OLD_END_ADDR_M,NEW_END_ADDR_M,NEW_DISCONTINUITY,NEW_ADMINISTRATIVE_CLASS,NEW_ELY,OLD_ADMINISTRATIVE_CLASS,OLD_DISCONTINUITY,OLD_ELY,REVERSED,ROADWAY_CHANGE_ID) values ('1000351','3','22006','22006','34','12','0','0','0','0','127','127','5','1','2','1','5','2','0','1000754')""")
      runUpdateToDb("""Insert into ROADWAY_CHANGES (PROJECT_ID,CHANGE_TYPE,OLD_ROAD_NUMBER,NEW_ROAD_NUMBER,OLD_ROAD_PART_NUMBER,NEW_ROAD_PART_NUMBER,OLD_TRACK,NEW_TRACK,OLD_START_ADDR_M,NEW_START_ADDR_M,OLD_END_ADDR_M,NEW_END_ADDR_M,NEW_DISCONTINUITY,NEW_ADMINISTRATIVE_CLASS,NEW_ELY,OLD_ADMINISTRATIVE_CLASS,OLD_DISCONTINUITY,OLD_ELY,REVERSED,ROADWAY_CHANGE_ID) values ('1000351','3','22006','22006','12','56','0','0','215','0','295','80','5','1','2','1','2','2','1','1000755')""")
      runUpdateToDb("""Insert into ROADWAY_CHANGES (PROJECT_ID,CHANGE_TYPE,OLD_ROAD_NUMBER,NEW_ROAD_NUMBER,OLD_ROAD_PART_NUMBER,NEW_ROAD_PART_NUMBER,OLD_TRACK,NEW_TRACK,OLD_START_ADDR_M,NEW_START_ADDR_M,OLD_END_ADDR_M,NEW_END_ADDR_M,NEW_DISCONTINUITY,NEW_ADMINISTRATIVE_CLASS,NEW_ELY,OLD_ADMINISTRATIVE_CLASS,OLD_DISCONTINUITY,OLD_ELY,REVERSED,ROADWAY_CHANGE_ID) values ('1000351','3','22006','22006','34','23','0','0','127','0','310','183','2','1','2','1','2','2','0','1000756')""")
      runUpdateToDb("""Insert into ROADWAY_CHANGES (PROJECT_ID,CHANGE_TYPE,OLD_ROAD_NUMBER,NEW_ROAD_NUMBER,OLD_ROAD_PART_NUMBER,NEW_ROAD_PART_NUMBER,OLD_TRACK,NEW_TRACK,OLD_START_ADDR_M,NEW_START_ADDR_M,OLD_END_ADDR_M,NEW_END_ADDR_M,NEW_DISCONTINUITY,NEW_ADMINISTRATIVE_CLASS,NEW_ELY,OLD_ADMINISTRATIVE_CLASS,OLD_DISCONTINUITY,OLD_ELY,REVERSED,ROADWAY_CHANGE_ID) values ('1000351','3','22006','22006','23','68','0','0','0','0','45','45','1','1','2','1','2','2','1','1000757')""")
      runUpdateToDb("""Insert into ROADWAY_CHANGES (PROJECT_ID,CHANGE_TYPE,OLD_ROAD_NUMBER,NEW_ROAD_NUMBER,OLD_ROAD_PART_NUMBER,NEW_ROAD_PART_NUMBER,OLD_TRACK,NEW_TRACK,OLD_START_ADDR_M,NEW_START_ADDR_M,OLD_END_ADDR_M,NEW_END_ADDR_M,NEW_DISCONTINUITY,NEW_ADMINISTRATIVE_CLASS,NEW_ELY,OLD_ADMINISTRATIVE_CLASS,OLD_DISCONTINUITY,OLD_ELY,REVERSED,ROADWAY_CHANGE_ID) values ('1000351','3','22006','22006','45','24','0','0','0','0','248','248','2','1','2','1','1','2','1','1000758')""")
      runUpdateToDb("""Insert into ROADWAY_CHANGES (PROJECT_ID,CHANGE_TYPE,OLD_ROAD_NUMBER,NEW_ROAD_NUMBER,OLD_ROAD_PART_NUMBER,NEW_ROAD_PART_NUMBER,OLD_TRACK,NEW_TRACK,OLD_START_ADDR_M,NEW_START_ADDR_M,OLD_END_ADDR_M,NEW_END_ADDR_M,NEW_DISCONTINUITY,NEW_ADMINISTRATIVE_CLASS,NEW_ELY,OLD_ADMINISTRATIVE_CLASS,OLD_DISCONTINUITY,OLD_ELY,REVERSED,ROADWAY_CHANGE_ID) values ('1000351','3','22006','22006','12','56','0','0','177','80','215','118','5','1','2','1','5','2','1','1000759')""")

      runUpdateToDb("""Insert into ROADWAY_CHANGES_LINK (ROADWAY_CHANGE_ID,PROJECT_ID,PROJECT_LINK_ID) values ('1000753','1000351','1000357')""")
      runUpdateToDb("""Insert into ROADWAY_CHANGES_LINK (ROADWAY_CHANGE_ID,PROJECT_ID,PROJECT_LINK_ID) values ('1000753','1000351','1000356')""")
      runUpdateToDb("""Insert into ROADWAY_CHANGES_LINK (ROADWAY_CHANGE_ID,PROJECT_ID,PROJECT_LINK_ID) values ('1000754','1000351','1000361')""")
      runUpdateToDb("""Insert into ROADWAY_CHANGES_LINK (ROADWAY_CHANGE_ID,PROJECT_ID,PROJECT_LINK_ID) values ('1000755','1000351','1000359')""")
      runUpdateToDb("""Insert into ROADWAY_CHANGES_LINK (ROADWAY_CHANGE_ID,PROJECT_ID,PROJECT_LINK_ID) values ('1000756','1000351','1000362')""")
      runUpdateToDb("""Insert into ROADWAY_CHANGES_LINK (ROADWAY_CHANGE_ID,PROJECT_ID,PROJECT_LINK_ID) values ('1000756','1000351','1000363')""")
      runUpdateToDb("""Insert into ROADWAY_CHANGES_LINK (ROADWAY_CHANGE_ID,PROJECT_ID,PROJECT_LINK_ID) values ('1000757','1000351','1000360')""")
      runUpdateToDb("""Insert into ROADWAY_CHANGES_LINK (ROADWAY_CHANGE_ID,PROJECT_ID,PROJECT_LINK_ID) values ('1000758','1000351','1000364')""")
      runUpdateToDb("""Insert into ROADWAY_CHANGES_LINK (ROADWAY_CHANGE_ID,PROJECT_ID,PROJECT_LINK_ID) values ('1000759','1000351','1000358')""")
      when(mockNodesAndJunctionsService.expireObsoleteNodesAndJunctions(any[Seq[ProjectLink]], any[Option[DateTime]], any[String])).thenReturn(Seq())
      projectDAO.updateProjectStatus(1000351, UpdatingToRoadNetwork)
      projectService.updateRoadwaysAndLinearLocationsWithProjectLinks(1000351)

      val roadwayPoints = roadwayPointDAO.fetch(Seq((166883763L, 0l)))

      roadwayPoints.size should be(1)

    }
  }
  def buildTestDataForProject(project: Option[Project], rws: Option[Seq[Roadway]], lil: Option[Seq[LinearLocation]], pls: Option[Seq[ProjectLink]]): Unit = {
    if (rws.nonEmpty)
      roadwayDAO.create(rws.get)
    if (lil.nonEmpty)
      linearLocationDAO.create(lil.get, "user")
    if (project.nonEmpty)
      projectDAO.create(project.get)
    if (pls.nonEmpty) {
      if (project.nonEmpty) {
        val roadParts = pls.get.groupBy(pl => (pl.roadPart)).keys
        roadParts.foreach(rp => projectReservedPartDAO.reserveRoadPart(project.get.id, rp, "user"))
        projectLinkDAO.create(pls.get.map(_.copy(projectId = project.get.id)))
      } else {
        projectLinkDAO.create(pls.get)
      }
    }
  }

  test("Test defaultSectionCalculatorStrategy.assignAddrMValues() " +
       "When two track road is extended on both tracks with new links " +
       "Then addresses are calculate properly.") {
    runWithRollback {
      val endValue         = 60L
      val newEndValue      = 80L
      val geomLeft1        = Seq(Point(0.0, 0.0), Point(533697, 6974615))
      val geomRight1       = Seq(Point(10.0, 0.0), Point(533684, 6974616))
      val projId           = Sequences.nextViiteProjectId
      val roadwayId        = Sequences.nextRoadwayId
      val linearLocationId = Sequences.nextLinearLocationId
      val projectLinkId    = Sequences.nextProjectLinkId + 100
      val linkId           = 12345.toString
      val project          = Project(projId, ProjectState.Incomplete, "f", "s", DateTime.now(), "", DateTime.now(), DateTime.now(), "", Seq(), Seq(), None, None, Set())

      val projectLinkLeft1  = ProjectLink(projectLinkId,     RoadPart(9999, 1), Track.apply(2), Discontinuity.Continuous, AddrMRange(0L, endValue), AddrMRange(0L, endValue), None, None, Some("user"), linkId,   0.0, endValue, SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP), geomLeft1,  0L, RoadAddressChangeType.Unchanged, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomLeft1),  roadwayId,     linearLocationId,     0, reversed = false, None, 86400L, roadwayNumber = 12345L)
      val projectLinkRight1 = ProjectLink(projectLinkId + 1, RoadPart(9999, 1), Track.apply(1), Discontinuity.Continuous, AddrMRange(0L, endValue), AddrMRange(0L, endValue), None, None, Some("user"), linkId+1, 0.0, endValue, SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP), geomRight1, 0L, RoadAddressChangeType.Unchanged, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomRight1), roadwayId + 1, linearLocationId + 1, 0, reversed = false, None, 86400L, roadwayNumber = 12346L)

      val geomNewRight = Seq(Seq(Point(534019, 6974278), Point(533959, 6974330)), Seq(Point(533959, 6974330), Point(533755, 6974509)), Seq(Point(533755, 6974509), Point(533747, 6974517)), Seq(Point(533747, 6974517), Point(533736, 6974530)), Seq(Point(533736, 6974530), Point(533684, 6974616)))
      val geomNewLeft  = Seq(Seq(Point(533747, 6974537), Point(533697, 6974615)), Seq(Point(533758, 6974524), Point(533747, 6974537)), Seq(Point(533765, 6974516), Point(533758, 6974524)), Seq(Point(533987, 6974323), Point(533765, 6974516)), Seq(Point(534032, 6974280), Point(533987, 6974323)))

      val rl1 = Seq(RoadLink(linkId+2, geomNewLeft.head, GeometryUtils.geometryLength(geomNewLeft.head), AdministrativeClass.State, extractTrafficDirection(SideCode.Unknown, Track.LeftSide), None, None, municipalityCode = 0, sourceId = ""),
        RoadLink(linkId+3, geomNewLeft(1), GeometryUtils.geometryLength(geomNewLeft(1)), AdministrativeClass.State, extractTrafficDirection(SideCode.Unknown, Track.LeftSide), None, None, municipalityCode = 0, sourceId = ""),
        RoadLink(linkId+4, geomNewLeft(2), GeometryUtils.geometryLength(geomNewLeft(2)), AdministrativeClass.State, extractTrafficDirection(SideCode.Unknown, Track.LeftSide), None, None, municipalityCode = 0, sourceId = ""),
        RoadLink(linkId+5, geomNewLeft(3), GeometryUtils.geometryLength(geomNewLeft(3)), AdministrativeClass.State, extractTrafficDirection(SideCode.Unknown, Track.LeftSide), None, None, municipalityCode = 0, sourceId = ""),
        RoadLink(linkId+6, geomNewLeft(4), GeometryUtils.geometryLength(geomNewLeft(4)), AdministrativeClass.State, extractTrafficDirection(SideCode.Unknown, Track.LeftSide), None, None, municipalityCode = 0, sourceId = "")
      )

      val rl2 = Seq(RoadLink(linkId+7, geomNewRight.head, GeometryUtils.geometryLength(geomNewRight.head), AdministrativeClass.State, extractTrafficDirection(SideCode.Unknown, Track.RightSide), None, None, municipalityCode = 0, sourceId = ""),
        RoadLink(linkId+8, geomNewRight(1), GeometryUtils.geometryLength(geomNewRight(1)), AdministrativeClass.State, extractTrafficDirection(SideCode.Unknown, Track.RightSide), None, None, municipalityCode = 0, sourceId = ""),
        RoadLink(linkId+9, geomNewRight(2), GeometryUtils.geometryLength(geomNewRight(2)), AdministrativeClass.State, extractTrafficDirection(SideCode.Unknown, Track.RightSide), None, None, municipalityCode = 0, sourceId = ""),
        RoadLink(linkId+10, geomNewRight(3), GeometryUtils.geometryLength(geomNewRight(3)), AdministrativeClass.State, extractTrafficDirection(SideCode.Unknown, Track.RightSide), None, None, municipalityCode = 0, sourceId = ""),
        RoadLink(linkId+11, geomNewRight(4), GeometryUtils.geometryLength(geomNewRight(4)), AdministrativeClass.State, extractTrafficDirection(SideCode.Unknown, Track.RightSide), None, None, municipalityCode = 0, sourceId = "")
      )

      val unChangedProjectLinks = Seq(projectLinkLeft1, projectLinkRight1)

      val (linearLocation1, roadway1) = Seq(projectLinkLeft1).map(toRoadwayAndLinearLocation).head
      val (linearLocation2, roadway2) = Seq(projectLinkRight1).map(toRoadwayAndLinearLocation).head

      val updatedRoadway1 = roadway1.copy(addrMRange = AddrMRange(roadway1.addrMRange.start, endValue), discontinuity = Discontinuity.EndOfRoad, id = projectLinkLeft1.roadwayId)
      val updatedRoadway2 = roadway2.copy(addrMRange = AddrMRange(roadway2.addrMRange.start, endValue), discontinuity = Discontinuity.EndOfRoad, id = projectLinkRight1.roadwayId)

      val updatedLinearLocationLeft  = linearLocation1.copy(id = projectLinkLeft1.linearLocationId)
      val updatedLinearLocationRight = linearLocation2.copy(id = projectLinkRight1.linearLocationId)

      buildTestDataForProject(Some(project), Some(Seq(updatedRoadway1, updatedRoadway2)), Some(Seq(updatedLinearLocationLeft, updatedLinearLocationRight)), Some(unChangedProjectLinks))

      when(mockRoadLinkService.getRoadLinksHistoryFromVVH(any[Set[String]])).thenReturn(Seq())
      when(mockRoadLinkService.getRoadLinksByLinkIds(any[Set[String]])).thenReturn(rl1)
      val createSuccessLeft  = projectService.createProjectLinks(rl1.map(_.linkId), project.id, RoadPart(9999, 1), Track.LeftSide,  Discontinuity.EndOfRoad, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, 0, project.createdBy, "9999_1")
      when(mockRoadLinkService.getRoadLinksByLinkIds(any[Set[String]])).thenReturn(rl2)
      val createSuccessRight = projectService.createProjectLinks(rl2.map(_.linkId), project.id, RoadPart(9999, 1), Track.RightSide, Discontinuity.EndOfRoad, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, 0, project.createdBy, "9999_1")

      createSuccessLeft should (contain key ("success") and contain value (true))
      createSuccessRight should (contain key ("success") and contain value (true))

      projectService.recalculateProjectLinks(project.id, project.createdBy)
      val projectLinksWithAssignedValues: List[ProjectLink] = projectService.getProjectLinks(project.id).toList

      projectLinksWithAssignedValues.filter(pl => pl.status == RoadAddressChangeType.Unchanged).foreach(pl => {
        pl.addrMRange.start should be(0)
        pl.addrMRange.end should be(endValue)
        pl.discontinuity should be(Discontinuity.Continuous)
      })

      val calculatedNewLinks = projectLinksWithAssignedValues.filter(pl => pl.status == RoadAddressChangeType.New)
      val calculatedNewLinkLefts = calculatedNewLinks.filter(_.track == Track.LeftSide)
      val calculatedNewLinkRights = calculatedNewLinks.filter(_.track == Track.RightSide)
      calculatedNewLinkLefts.minBy(_.addrMRange.start).addrMRange.start should be (endValue)
      calculatedNewLinkRights.minBy(_.addrMRange.start).addrMRange.start should be (endValue)

      val maxNewLeft  = calculatedNewLinkLefts.maxBy(_.addrMRange.end)
      val maxNewRight = calculatedNewLinkRights.maxBy(_.addrMRange.end)
      maxNewLeft.addrMRange.end should be(maxNewRight.addrMRange.end)
      maxNewLeft.discontinuity should be(Discontinuity.EndOfRoad)
      maxNewRight.discontinuity should be(Discontinuity.EndOfRoad)
    }
  }

  test("Test defaultSectionCalculatorStrategy.assignAddrMValues() " +
       "When two track road is extended by a new road part with new links " +
       "Then addresses are calculate properly.") {
    runWithRollback {
      val endValue          = 60L
      val newEndValue       = 80L
      val roadPartNumber    = 1L
      val newRoadPartNumber = 2L
      val geomLeft1         = Seq(Point(0.0, 0.0), Point(533697, 6974615))
      val geomRight1        = Seq(Point(10.0, 0.0), Point(533684, 6974616))
      val projId            = Sequences.nextViiteProjectId
      val roadwayId         = Sequences.nextRoadwayId
      val linearLocationId  = Sequences.nextLinearLocationId
      val projectLinkId     = Sequences.nextProjectLinkId + 100
      val linkId            = 12345L.toString
      val project           = Project(projId, ProjectState.Incomplete, "f", "s", DateTime.now(), "", DateTime.now(), DateTime.now(), "", Seq(), Seq(), None, None, Set())

      val projectLinkLeft1  = ProjectLink(projectLinkId,     RoadPart(9999, roadPartNumber), Track.apply(2), Discontinuity.Continuous, AddrMRange(0L, endValue), AddrMRange(0L, endValue), None, None, Some("user"), 12345L.toString, 0.0, endValue, SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP), geomLeft1,  0L, RoadAddressChangeType.Unchanged, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomLeft1),  roadwayId,     linearLocationId,     0, reversed = false, None, 86400L, roadwayNumber = 12345L)
      val projectLinkRight1 = ProjectLink(projectLinkId + 1, RoadPart(9999, roadPartNumber), Track.apply(1), Discontinuity.Continuous, AddrMRange(0L, endValue), AddrMRange(0L, endValue), None, None, Some("user"), 12346L.toString, 0.0, endValue, SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP), geomRight1, 0L, RoadAddressChangeType.Unchanged, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomRight1), roadwayId + 1, linearLocationId + 1, 0, reversed = false, None, 86400L, roadwayNumber = 12346L)

      val geomNewRight = Seq(Seq(Point(534019, 6974278), Point(533959, 6974330)), Seq(Point(533959, 6974330), Point(533755, 6974509)), Seq(Point(533755, 6974509), Point(533747, 6974517)), Seq(Point(533747, 6974517), Point(533736, 6974530)), Seq(Point(533736, 6974530), Point(533684, 6974616)))
      val geomNewLef2  = Seq(Seq(Point(533747, 6974537), Point(533697, 6974615)), Seq(Point(533758, 6974524), Point(533747, 6974537)), Seq(Point(533765, 6974516), Point(533758, 6974524)), Seq(Point(533987, 6974323), Point(533765, 6974516)), Seq(Point(534032, 6974280), Point(533987, 6974323)))

      val rl1 = Seq(RoadLink(linkId+2, geomNewLef2.head, GeometryUtils.geometryLength(geomNewLef2.head), AdministrativeClass.State, extractTrafficDirection(SideCode.Unknown, Track.LeftSide), None, None, municipalityCode = 0, sourceId = ""),
        RoadLink(linkId+3, geomNewLef2(1), GeometryUtils.geometryLength(geomNewLef2(1)), AdministrativeClass.State, extractTrafficDirection(SideCode.Unknown, Track.LeftSide), None, None, municipalityCode = 0, sourceId = ""),
        RoadLink(linkId+4, geomNewLef2(2), GeometryUtils.geometryLength(geomNewLef2(2)), AdministrativeClass.State, extractTrafficDirection(SideCode.Unknown, Track.LeftSide), None, None, municipalityCode = 0, sourceId = ""),
        RoadLink(linkId+5, geomNewLef2(3), GeometryUtils.geometryLength(geomNewLef2(3)), AdministrativeClass.State, extractTrafficDirection(SideCode.Unknown, Track.LeftSide), None, None, municipalityCode = 0, sourceId = ""),
        RoadLink(linkId+6, geomNewLef2(4), GeometryUtils.geometryLength(geomNewLef2(4)), AdministrativeClass.State, extractTrafficDirection(SideCode.Unknown, Track.LeftSide), None, None, municipalityCode = 0, sourceId = "")
      )

      val rl2 = Seq(RoadLink(linkId+7, geomNewRight.head, GeometryUtils.geometryLength(geomNewRight.head), AdministrativeClass.State, extractTrafficDirection(SideCode.Unknown, Track.RightSide), None, None, municipalityCode = 0, sourceId = ""),
        RoadLink(linkId+8, geomNewRight(1), GeometryUtils.geometryLength(geomNewRight(1)), AdministrativeClass.State, extractTrafficDirection(SideCode.Unknown, Track.RightSide), None, None, municipalityCode = 0, sourceId = ""),
        RoadLink(linkId+9, geomNewRight(2), GeometryUtils.geometryLength(geomNewRight(2)), AdministrativeClass.State, extractTrafficDirection(SideCode.Unknown, Track.RightSide), None, None, municipalityCode = 0, sourceId = ""),
        RoadLink(linkId+10, geomNewRight(3), GeometryUtils.geometryLength(geomNewRight(3)), AdministrativeClass.State, extractTrafficDirection(SideCode.Unknown, Track.RightSide), None, None, municipalityCode = 0, sourceId = ""),
        RoadLink(linkId+11, geomNewRight(4), GeometryUtils.geometryLength(geomNewRight(4)), AdministrativeClass.State, extractTrafficDirection(SideCode.Unknown, Track.RightSide), None, None, municipalityCode = 0, sourceId = "")
      )

      val unChangedProjectLinks = Seq(projectLinkLeft1, projectLinkRight1)

      val (linearLocation1, roadway1) = Seq(projectLinkLeft1).map(toRoadwayAndLinearLocation).head
      val (linearLocation2, roadway2) = Seq(projectLinkRight1).map(toRoadwayAndLinearLocation).head

      val updatedRoadway1 = roadway1.copy(addrMRange = AddrMRange(roadway1.addrMRange.start, endValue), discontinuity = Discontinuity.EndOfRoad, id =  projectLinkLeft1.roadwayId)
      val updatedRoadway2 = roadway2.copy(addrMRange = AddrMRange(roadway2.addrMRange.start, endValue), discontinuity = Discontinuity.EndOfRoad, id = projectLinkRight1.roadwayId)

      val updatedLinearLocationLeft  = linearLocation1.copy(id = projectLinkLeft1.linearLocationId)
      val updatedLinearLocationRight = linearLocation2.copy(id = projectLinkRight1.linearLocationId)

      buildTestDataForProject(Some(project), Some(Seq(updatedRoadway1, updatedRoadway2)), Some(Seq(updatedLinearLocationLeft, updatedLinearLocationRight)), Some(unChangedProjectLinks))

      when(mockRoadLinkService.getRoadLinksHistoryFromVVH(any[Set[String]])).thenReturn(Seq())
      when(mockRoadLinkService.getRoadLinksByLinkIds(any[Set[String]])).thenReturn(rl1)
      val createSuccessLeft  = projectService.createProjectLinks(rl1.map(_.linkId), project.id, RoadPart(9999, newRoadPartNumber), Track.LeftSide,  Discontinuity.EndOfRoad, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, 0, project.createdBy, "9999_1")
      when(mockRoadLinkService.getRoadLinksByLinkIds(any[Set[String]])).thenReturn(rl2)
      val createSuccessRight = projectService.createProjectLinks(rl2.map(_.linkId), project.id, RoadPart(9999, newRoadPartNumber), Track.RightSide, Discontinuity.EndOfRoad, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, 0, project.createdBy, "9999_1")

      createSuccessLeft should (contain key ("success") and contain value (true))
      createSuccessRight should (contain key ("success") and contain value (true))

      projectService.recalculateProjectLinks(project.id, project.createdBy)
      val projectLinksWithAssignedValues: List[ProjectLink] = projectService.getProjectLinks(project.id).toList

      projectLinksWithAssignedValues.filter(pl => pl.status == RoadAddressChangeType.Unchanged).foreach(pl => {
        pl.addrMRange should be(AddrMRange(0,endValue))
        pl.discontinuity should be(Discontinuity.Continuous)
      })

      val calculatedNewLinks = projectLinksWithAssignedValues.filter(pl => pl.status == RoadAddressChangeType.New)
      calculatedNewLinks should have (size (10))

      val calculatedNewLinkLefts = calculatedNewLinks.filter(_.track == Track.LeftSide)
      val calculatedNewLinkRights = calculatedNewLinks.filter(_.track == Track.RightSide)
      calculatedNewLinkLefts.minBy(_.addrMRange.start).addrMRange.start should be (0)
      calculatedNewLinkRights.minBy(_.addrMRange.start).addrMRange.start should be (0)

      val maxNewLeft  = calculatedNewLinkLefts.maxBy(_.addrMRange.end)
      val maxNewRight = calculatedNewLinkRights.maxBy(_.addrMRange.end)
      maxNewLeft.addrMRange.end should be(maxNewRight.addrMRange.end)
      maxNewLeft.discontinuity should be(Discontinuity.EndOfRoad)
      maxNewRight.discontinuity should be(Discontinuity.EndOfRoad)
    }
  }

  test("Test defaultSectionCalculatorStrategy.updateRoadwaysAndLinearLocationsWithProjectLinks() " +
       "When two track road with a new track 2 and partially new track 1 having new track at the end " +
       "Then formed roadways should be continuous.") {
    runWithRollback {
      val createdBy = Some("test")
      val user = createdBy.get
      val roadPart = RoadPart(46020, 1)
      val roadName = None
      val project_id = Sequences.nextViiteProjectId

      /* Check the project layout from the ticket 2699. */
      runUpdateToDb(s"""INSERT INTO PROJECT VALUES($project_id, 11, 'test project', '$user', TIMESTAMP '2018-03-23 11:36:15.000000', '-', TIMESTAMP '2018-03-23 12:26:33.000000', NULL, TIMESTAMP '2018-03-23 00:00:00.000000', NULL, 564987.0, 6769633.0, 12)""")
      runUpdateToDb(s"""INSERT INTO PROJECT_RESERVED_ROAD_PART VALUES (${Sequences.nextViitePrimaryKeySeqValue}, 46020, 1, $project_id, '-')""")
      runUpdateToDb(s"""INSERT INTO roadway (id, roadway_number, road_number, road_part_number, track, start_addr_m, end_addr_m, reversed, discontinuity, start_date, end_date, created_by, created_time, administrative_class, ely, terminated, valid_from, valid_to) VALUES(107964, 335560416, 46020, 1, 0, 0, 785, 0, 1, '2022-01-01', NULL, '$user', '2022-02-17 09:48:15.355', 2, 3, 0, '2022-02-17 09:48:15.355', NULL)""")
      runUpdateToDb("""INSERT INTO link (id, "source", adjusted_timestamp, created_time) VALUES(2621644, 4, 1634598047000, '2022-02-17 09:48:15.355'),(2621698, 4, 1533863903000, '2022-02-17 09:48:15.355'),(2621642, 4, 1634598047000, '2022-02-17 09:48:15.355'),(2621640, 4, 1634598047000, '2022-02-17 09:48:15.355'),(2621632, 4, 1634598047000, '2022-02-17 09:48:15.355'),(2621636, 4, 1533863903000, '2022-02-17 09:48:15.355'),(2621288, 4, 1533863903000, '2022-02-17 09:48:15.355'),(2621287, 4, 1533863903000, '2022-02-17 09:48:15.355')""")

      runUpdateToDb(s"""INSERT INTO linear_location (id, roadway_number, order_number, link_id, start_measure, end_measure, side, geometry, valid_from, valid_to, created_by, created_time) VALUES(490283, 335560416, 7, 2621644, 0.000,  94.008, 3, 'SRID=3067;LINESTRING ZM(565110.998 6769336.795  90.40499999999884 0, 565028.813 6769382.427  91.38099999999395  94.008)'::public.geometry, '2022-02-17 09:48:15.355', NULL, '$createdBy', '2022-02-17 09:48:15.355')""")
      runUpdateToDb(s"""INSERT INTO linear_location (id, roadway_number, order_number, link_id, start_measure, end_measure, side, geometry, valid_from, valid_to, created_by, created_time) VALUES(490284, 335560416, 8, 2621698, 0.000, 165.951, 3, 'SRID=3067;LINESTRING ZM(565258.278 6769260.328  82.68899999999849 0, 565110.998 6769336.795  90.40499999999884 165.951)'::public.geometry, '2022-02-17 09:48:15.355', NULL, '$createdBy', '2022-02-17 09:48:15.355')""")
      runUpdateToDb(s"""INSERT INTO linear_location (id, roadway_number, order_number, link_id, start_measure, end_measure, side, geometry, valid_from, valid_to, created_by, created_time) VALUES(490217, 335560416, 1, 2621642, 0.000, 167.250, 3, 'SRID=3067;LINESTRING ZM(564987.238 6769633.328 102.99000000000524 0, 565123.382 6769720.891 104.16400000000431 167.25)'::public.geometry,  '2022-02-17 09:48:15.355', NULL, '$createdBy', '2022-02-17 09:48:15.355')""")
      runUpdateToDb(s"""INSERT INTO linear_location (id, roadway_number, order_number, link_id, start_measure, end_measure, side, geometry, valid_from, valid_to, created_by, created_time) VALUES(490282, 335560416, 6, 2621640, 0.000, 155.349, 3, 'SRID=3067;LINESTRING ZM(565028.813 6769382.427  91.38099999999395 0, 564891.659 6769455.379  99.18700000000536 155.349)'::public.geometry, '2022-02-17 09:48:15.355', NULL, '$createdBy', '2022-02-17 09:48:15.355')""")
      runUpdateToDb(s"""INSERT INTO linear_location (id, roadway_number, order_number, link_id, start_measure, end_measure, side, geometry, valid_from, valid_to, created_by, created_time) VALUES(490218, 335560416, 2, 2621632, 0.000,  81.498, 3, 'SRID=3067;LINESTRING ZM(564948.879 6769561.432 100.8859999999986  0, 564987.238 6769633.328 102.99000000000524  81.498)'::public.geometry, '2022-02-17 09:48:15.355', NULL, '$createdBy', '2022-02-17 09:48:15.355')""")
      runUpdateToDb(s"""INSERT INTO linear_location (id, roadway_number, order_number, link_id, start_measure, end_measure, side, geometry, valid_from, valid_to, created_by, created_time) VALUES(490219, 335560416, 3, 2621636, 0.000, 101.665, 3, 'SRID=3067;LINESTRING ZM(564900.791 6769471.859  99.14599999999336 0, 564948.879 6769561.432 100.8859999999986  101.665)'::public.geometry, '2022-02-17 09:48:15.355', NULL, '$createdBy', '2022-02-17 09:48:15.355')""")
      runUpdateToDb(s"""INSERT INTO linear_location (id, roadway_number, order_number, link_id, start_measure, end_measure, side, geometry, valid_from, valid_to, created_by, created_time) VALUES(490220, 335560416, 4, 2621288, 0.000,   7.843, 3, 'SRID=3067;LINESTRING ZM(564896.99  6769464.999  99.18300000000454 0, 564900.791 6769471.859  99.14599999999336   7.843)'::public.geometry, '2022-02-17 09:48:15.355', NULL, '$createdBy', '2022-02-17 09:48:15.355')""")
      runUpdateToDb(s"""INSERT INTO linear_location (id, roadway_number, order_number, link_id, start_measure, end_measure, side, geometry, valid_from, valid_to, created_by, created_time) VALUES(490281, 335560416, 5, 2621287, 0.000,  10.998, 3, 'SRID=3067;LINESTRING ZM(564891.659 6769455.379  99.18700000000536 0, 564896.99  6769464.999  99.18300000000454  10.998)'::public.geometry, '2022-02-17 09:48:15.355', NULL, '$createdBy', '2022-02-17 09:48:15.355')""")

      val projecLinks = Seq(
          ProjectLink(1000,roadPart,Track.Combined, Discontinuity.Continuous,AddrMRange(  0, 167),AddrMRange(  0,167),None,None,createdBy,2621642.toString,  0.0,  167.25, SideCode.AgainstDigitizing,(RoadAddressCP,NoCP),(RoadAddressCP,NoCP),List(Point(564987.0,6769633.0,0.0), Point(565123.0,6769720.0,0.0)),project_id,RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, LinkGeomSource.FrozenLinkInterface,167.25 ,107964,490217,3,false,None,1634598047000L,335562039,roadName),
          ProjectLink(1001,roadPart,Track.Combined, Discontinuity.Continuous,AddrMRange(167, 249),AddrMRange(167,249),None,None,createdBy,2621632.toString,  0.0,   81.498,SideCode.AgainstDigitizing,(NoCP,NoCP         ),(NoCP,         NoCP),List(Point(564948.0,6769561.0,0.0), Point(564987.0,6769633.0,0.0)),project_id,RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, LinkGeomSource.FrozenLinkInterface, 81.498,107964,490218,3,false,None,1634598047000L,335562039,roadName),
          ProjectLink(1002,roadPart,Track.Combined, Discontinuity.Continuous,AddrMRange(249, 351),AddrMRange(249,351),None,None,createdBy,2621636.toString,  0.0,  101.665,SideCode.AgainstDigitizing,(NoCP,NoCP         ),(NoCP,         NoCP),List(Point(564900.0,6769471.0,0.0), Point(564948.0,6769561.0,0.0)),project_id,RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, LinkGeomSource.FrozenLinkInterface,101.665,107964,490219,3,false,None,1533863903000L,335562039,roadName),
          ProjectLink(1003,roadPart,Track.Combined, Discontinuity.Continuous,AddrMRange(351, 358),AddrMRange(351,358),None,None,createdBy,2621288.toString,  0.0,    7.843,SideCode.AgainstDigitizing,(NoCP,NoCP         ),(NoCP,         NoCP),List(Point(564896.0,6769464.0,0.0), Point(564900.0,6769471.0,0.0)),project_id,RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, LinkGeomSource.FrozenLinkInterface,  7.843,107964,490220,3,false,None,1533863903000L,335562039,roadName),
          ProjectLink(1004,roadPart,Track.Combined, Discontinuity.Continuous,AddrMRange(358, 369),AddrMRange(358,369),None,None,createdBy,2621287.toString,  0.0,   10.998,SideCode.AgainstDigitizing,(NoCP,RoadAddressCP),(NoCP,         NoCP),List(Point(564891.0,6769455.0,0.0), Point(564896.0,6769464.0,0.0)),project_id,RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, LinkGeomSource.FrozenLinkInterface, 10.998,107964,490281,3,false,None,1533863903000L,335562039,roadName),
          ProjectLink(1005,roadPart,Track.LeftSide, Discontinuity.Continuous,AddrMRange(369, 458),AddrMRange(  0,  0),None,None,createdBy,2621639.toString,  0.0,   87.736,SideCode.AgainstDigitizing,(RoadAddressCP,NoCP),(NoCP,         NoCP),List(Point(564974.0,6769424.0,0.0), Point(564896.0,6769464.0,0.0)),project_id,RoadAddressChangeType.New,      AdministrativeClass.Municipality, LinkGeomSource.FrozenLinkInterface, 87.736,     0,     0,3,false,None,1533863903000L,335562043,roadName),
          ProjectLink(1006,roadPart,Track.RightSide,Discontinuity.Continuous,AddrMRange(369, 525),AddrMRange(369,525),None,None,createdBy,2621640.toString,  0.0,  155.349,SideCode.AgainstDigitizing,(RoadAddressCP,NoCP),(NoCP,         NoCP),List(Point(565028.0,6769382.0,0.0), Point(564891.0,6769455.0,0.0)),project_id,RoadAddressChangeType.Transfer, AdministrativeClass.Municipality, LinkGeomSource.FrozenLinkInterface,155.349,107964,490282,3,false,None,1634598047000L,335562042,roadName),
          ProjectLink(1007,roadPart,Track.LeftSide, Discontinuity.Continuous,AddrMRange(458, 526),AddrMRange(  0,  0),None,None,createdBy,2621652.toString,  0.0,   67.894,SideCode.AgainstDigitizing,(NoCP,NoCP         ),(NoCP,         NoCP),List(Point(565034.0,6769391.0,0.0), Point(564974.0,6769424.0,0.0)),project_id,RoadAddressChangeType.New,      AdministrativeClass.Municipality, LinkGeomSource.FrozenLinkInterface, 67.894,     0,     0,3,false,None,1533863903000L,335562043,roadName),
          ProjectLink(1008,roadPart,Track.RightSide,Discontinuity.Continuous,AddrMRange(525, 619),AddrMRange(525,619),None,None,createdBy,2621644.toString,  0.0,   94.008,SideCode.AgainstDigitizing,(NoCP,NoCP         ),(NoCP,         NoCP),List(Point(565110.0,6769336.0,0.0), Point(565028.0,6769382.0,0.0)),project_id,RoadAddressChangeType.Transfer, AdministrativeClass.Municipality, LinkGeomSource.FrozenLinkInterface, 94.008,107964,490283,3,false,None,1634598047000L,335562042,roadName),
          ProjectLink(1009,roadPart,Track.LeftSide, Discontinuity.Continuous,AddrMRange(526, 621),AddrMRange(  0,  0),None,None,createdBy,2621646.toString,  0.0,   94.565,SideCode.AgainstDigitizing,(NoCP,NoCP         ),(NoCP,         NoCP),List(Point(565117.0,6769346.0,0.0), Point(565034.0,6769391.0,0.0)),project_id,RoadAddressChangeType.New,      AdministrativeClass.Municipality, LinkGeomSource.FrozenLinkInterface, 94.565,     0,     0,3,false,None,1533863903000L,335562043,roadName),
          ProjectLink(1010,roadPart,Track.RightSide,Discontinuity.Continuous,AddrMRange(619, 785),AddrMRange(619,785),None,None,createdBy,2621698.toString,  0.0,  165.951,SideCode.AgainstDigitizing,(NoCP,UserDefinedCP),(NoCP,RoadAddressCP),List(Point(565258.0,6769260.0,0.0), Point(565110.0,6769336.0,0.0)),project_id,RoadAddressChangeType.Transfer, AdministrativeClass.Municipality, LinkGeomSource.FrozenLinkInterface,165.951,107964,490284,3,false,None,1533863903000L,335562042,roadName),
          ProjectLink(1011,roadPart,Track.LeftSide, Discontinuity.Continuous,AddrMRange(621, 785),AddrMRange(  0,164),None,None,createdBy,2621704.toString,  0.0,  161.799,SideCode.AgainstDigitizing,(NoCP,UserDefinedCP),(NoCP,         NoCP),List(Point(565259.0,6769270.0,0.0), Point(565117.0,6769346.0,0.0)),project_id,RoadAddressChangeType.New,      AdministrativeClass.Municipality, LinkGeomSource.FrozenLinkInterface,161.799,     0,     0,3,false,Some(2621704.toString),1533863903000L,335562043,roadName),
          ProjectLink(1012,roadPart,Track.RightSide,Discontinuity.Continuous,AddrMRange(785, 794),AddrMRange(  0,  0),None,None,createdBy,2621718.toString,  0.0,    9.277,SideCode.AgainstDigitizing,(NoCP,NoCP         ),(NoCP,         NoCP),List(Point(565266.0,6769255.0,0.0), Point(565258.0,6769260.0,0.0)),project_id,RoadAddressChangeType.New,      AdministrativeClass.Municipality, LinkGeomSource.FrozenLinkInterface,  9.277,     0,     0,3,false,None,1533863903000L,335562044,roadName),
          ProjectLink(1013,roadPart,Track.LeftSide, Discontinuity.Continuous,AddrMRange(785, 796),AddrMRange(164,164),None,None,createdBy,2621704.toString,161.799,172.651,SideCode.AgainstDigitizing,(NoCP,NoCP         ),(NoCP,         NoCP),List(Point(565269.0,6769265.0,0.0), Point(565259.0,6769270.0,0.0)),project_id,RoadAddressChangeType.New,      AdministrativeClass.Municipality, LinkGeomSource.FrozenLinkInterface, 10.852,     0,     0,3,false,Some(2621704.toString),1533863903000L,335562043,roadName),
          ProjectLink(1014,roadPart,Track.LeftSide, Discontinuity.Continuous,AddrMRange(796, 804),AddrMRange(  0,  0),None,None,createdBy,2621721.toString,  0.0,    7.956,SideCode.AgainstDigitizing,(NoCP,NoCP         ),(NoCP,         NoCP),List(Point(565276.0,6769261.0,0.0), Point(565269.0,6769265.0,0.0)),project_id,RoadAddressChangeType.New,      AdministrativeClass.Municipality, LinkGeomSource.FrozenLinkInterface,  7.956,     0,     0,3,false,None,1533863903000L,335562043,roadName),
          ProjectLink(1015,roadPart,Track.LeftSide, Discontinuity.Continuous,AddrMRange(804, 936),AddrMRange(  0,  0),None,None,createdBy,2621712.toString,  0.0,  131.415,SideCode.AgainstDigitizing,(NoCP,NoCP         ),(NoCP,         NoCP),List(Point(565402.0,6769253.0,0.0), Point(565276.0,6769261.0,0.0)),project_id,RoadAddressChangeType.New,      AdministrativeClass.Municipality, LinkGeomSource.FrozenLinkInterface,131.415,     0,     0,3,false,None,1634598047000L,335562043,roadName),
          ProjectLink(1016,roadPart,Track.RightSide,Discontinuity.Continuous,AddrMRange(794, 938),AddrMRange(  0,  0),None,None,createdBy,2621709.toString,  0.0,  146.366,SideCode.AgainstDigitizing,(NoCP,NoCP         ),(NoCP,         NoCP),List(Point(565406.0,6769245.0,0.0), Point(565266.0,6769255.0,0.0)),project_id,RoadAddressChangeType.New,      AdministrativeClass.Municipality, LinkGeomSource.FrozenLinkInterface,146.366,     0,     0,3,false,None,1634598047000L,335562044,roadName),
          ProjectLink(1017,roadPart,Track.RightSide,Discontinuity.EndOfRoad, AddrMRange(938,1035),AddrMRange(  0,  0),None,None,createdBy,2621724.toString,  0.0,   99.195,SideCode.TowardsDigitizing,(NoCP,RoadAddressCP),(NoCP,         NoCP),List(Point(565406.0,6769245.0,0.0), Point(565485.0,6769303.0,0.0)),project_id,RoadAddressChangeType.New,      AdministrativeClass.Municipality, LinkGeomSource.FrozenLinkInterface, 99.195,     0,     0,3,false,None,1634598047000L,335562044,roadName),
          ProjectLink(1018,roadPart,Track.LeftSide, Discontinuity.EndOfRoad, AddrMRange(936,1035),AddrMRange(  0,  0),None,None,createdBy,2621723.toString,  0.0,   97.837,SideCode.TowardsDigitizing,(NoCP,RoadAddressCP),(NoCP,         NoCP),List(Point(565402.0,6769253.0,0.0), Point(565485.0,6769303.0,0.0)),project_id,RoadAddressChangeType.New,      AdministrativeClass.Municipality, LinkGeomSource.FrozenLinkInterface, 97.837,     0,     0,3,false,None,1634598047000L,335562043,roadName)
        )
      when(mockNodesAndJunctionsService.expireObsoleteNodesAndJunctions(any[Seq[ProjectLink]], any[Option[DateTime]], any[String])).thenReturn(Seq())
      projectLinkDAO.create(projecLinks)
      projectService.updateRoadwaysAndLinearLocationsWithProjectLinks(project_id)
      val roadways = roadwayDAO.fetchAllByRoadPart(roadPart)
      val firstRw = roadways.find(r => r.addrMRange.start == 0 && r.track == Track.Combined)
      firstRw should be ('defined)
      val secondRw = roadways.find(r => r.addrMRange.end == 785 && r.track == Track.RightSide)
      secondRw should be ('defined)
      val thirdRw = roadways.find(r => r.addrMRange.start == 369 && r.addrMRange.end == 1035 && r.track == Track.LeftSide)
      thirdRw should be ('defined)
      val fourthRw = roadways.find(r => r.addrMRange.start == 785 && r.addrMRange.end == 1035 && r.track == Track.RightSide)
      fourthRw should be ('defined)
    }
  }

    test("Test defaultSectionCalculatorStrategy.updateRoadwaysAndLinearLocationsWithProjectLinks() " +
         "When two track road with a new track 2 and partially new track 1 having new track in the middle " +
         "Then formed roadways should be continuous.") {
    runWithRollback {
      val createdBy = Some("test")
      val user = createdBy.get
      val roadPart = RoadPart(46020, 1)
      val roadName = None
      val project_id = Sequences.nextViiteProjectId

      /* Check the project layout from the ticket 2699. */
      runUpdateToDb(s"""INSERT INTO PROJECT VALUES($project_id, 11, 'test project', '$user', TIMESTAMP '2018-03-23 11:36:15.000000', '-', TIMESTAMP '2018-03-23 12:26:33.000000', NULL, TIMESTAMP '2018-03-23 00:00:00.000000', NULL, 564987.0, 6769633.0, 12)""")
      runUpdateToDb(s"""INSERT INTO PROJECT_RESERVED_ROAD_PART VALUES (${Sequences.nextViitePrimaryKeySeqValue}, 46020, 1, $project_id, '-')""")
      runUpdateToDb(s"""INSERT INTO roadway (id, roadway_number, road_number, road_part_number, track, start_addr_m, end_addr_m, reversed, discontinuity, start_date, end_date, created_by, created_time, administrative_class, ely, terminated, valid_from, valid_to) VALUES(107964, 335560416, 46020, 1, 0, 0, 369, 0, 4, '2022-01-01', NULL, '$user', '2022-02-17 09:48:15.355', 2, 3, 0, '2022-02-17 09:48:15.355', NULL)""")
      runUpdateToDb(s"""INSERT INTO roadway (id, roadway_number, road_number, road_part_number, track, start_addr_m, end_addr_m, reversed, discontinuity, start_date, end_date, created_by, created_time, administrative_class, ely, terminated, valid_from, valid_to) VALUES(1111, 335560417, 46020, 1, 0, 369, 624, 0, 1, '2022-01-01', NULL, '$user', '2022-02-17 09:48:15.355', 2, 3, 0, '2022-02-17 09:48:15.355', NULL)""")
      runUpdateToDb("""INSERT INTO link (id, "source", adjusted_timestamp, created_time) VALUES(2621644, 4, 1634598047000, '2022-02-17 09:48:15.355'),(2621698, 4, 1533863903000, '2022-02-17 09:48:15.355'),(2621642, 4, 1634598047000, '2022-02-17 09:48:15.355'),(2621640, 4, 1634598047000, '2022-02-17 09:48:15.355'),(2621632, 4, 1634598047000, '2022-02-17 09:48:15.355'),(2621636, 4, 1533863903000, '2022-02-17 09:48:15.355'),(2621288, 4, 1533863903000, '2022-02-17 09:48:15.355'),(2621287, 4, 1533863903000, '2022-02-17 09:48:15.355')""")

      runUpdateToDb(s"""INSERT INTO linear_location (id, roadway_number, order_number, link_id, start_measure, end_measure, side, geometry, valid_from, valid_to, created_by, created_time) VALUES(490283, 335560416, 7, 2621644, 0.000,  94.008, 3, 'SRID=3067;LINESTRING ZM(565110.998 6769336.795  90.40499999999884 0, 565028.813 6769382.427  91.38099999999395  94.008)'::public.geometry, '2022-02-17 09:48:15.355', NULL, '$createdBy', '2022-02-17 09:48:15.355')""")
      runUpdateToDb(s"""INSERT INTO linear_location (id, roadway_number, order_number, link_id, start_measure, end_measure, side, geometry, valid_from, valid_to, created_by, created_time) VALUES(490284, 335560416, 8, 2621698, 0.000, 165.951, 3, 'SRID=3067;LINESTRING ZM(565258.278 6769260.328  82.68899999999849 0, 565110.998 6769336.795  90.40499999999884 165.951)'::public.geometry, '2022-02-17 09:48:15.355', NULL, '$createdBy', '2022-02-17 09:48:15.355')""")
      runUpdateToDb(s"""INSERT INTO linear_location (id, roadway_number, order_number, link_id, start_measure, end_measure, side, geometry, valid_from, valid_to, created_by, created_time) VALUES(490217, 335560416, 1, 2621642, 0.000, 167.250, 3, 'SRID=3067;LINESTRING ZM(564987.238 6769633.328 102.99000000000524 0, 565123.382 6769720.891 104.16400000000431 167.25 )'::public.geometry, '2022-02-17 09:48:15.355', NULL, '$createdBy', '2022-02-17 09:48:15.355')""")
      runUpdateToDb(s"""INSERT INTO linear_location (id, roadway_number, order_number, link_id, start_measure, end_measure, side, geometry, valid_from, valid_to, created_by, created_time) VALUES(490282, 335560416, 6, 2621640, 0.000, 155.349, 3, 'SRID=3067;LINESTRING ZM(565028.813 6769382.427  91.38099999999395 0, 564891.659 6769455.379  99.18700000000536 155.349)'::public.geometry, '2022-02-17 09:48:15.355', NULL, '$createdBy', '2022-02-17 09:48:15.355')""")
      runUpdateToDb(s"""INSERT INTO linear_location (id, roadway_number, order_number, link_id, start_measure, end_measure, side, geometry, valid_from, valid_to, created_by, created_time) VALUES(490218, 335560416, 2, 2621632, 0.000,  81.498, 3, 'SRID=3067;LINESTRING ZM(564948.879 6769561.432 100.8859999999986  0, 564987.238 6769633.328 102.99000000000524  81.498)'::public.geometry, '2022-02-17 09:48:15.355', NULL, '$createdBy', '2022-02-17 09:48:15.355')""")
      runUpdateToDb(s"""INSERT INTO linear_location (id, roadway_number, order_number, link_id, start_measure, end_measure, side, geometry, valid_from, valid_to, created_by, created_time) VALUES(490219, 335560416, 3, 2621636, 0.000, 101.665, 3, 'SRID=3067;LINESTRING ZM(564900.791 6769471.859  99.14599999999336 0, 564948.879 6769561.432 100.8859999999986  101.665)'::public.geometry, '2022-02-17 09:48:15.355', NULL, '$createdBy', '2022-02-17 09:48:15.355')""")
      runUpdateToDb(s"""INSERT INTO linear_location (id, roadway_number, order_number, link_id, start_measure, end_measure, side, geometry, valid_from, valid_to, created_by, created_time) VALUES(490220, 335560416, 4, 2621288, 0.000,   7.843, 3, 'SRID=3067;LINESTRING ZM(564896.99  6769464.999  99.18300000000454 0, 564900.791 6769471.859  99.14599999999336   7.843)'::public.geometry, '2022-02-17 09:48:15.355', NULL, '$createdBy', '2022-02-17 09:48:15.355')""")
      runUpdateToDb(s"""INSERT INTO linear_location (id, roadway_number, order_number, link_id, start_measure, end_measure, side, geometry, valid_from, valid_to, created_by, created_time) VALUES(490281, 335560416, 5, 2621287, 0.000,  10.998, 3, 'SRID=3067;LINESTRING ZM(564891.659 6769455.379  99.18700000000536 0, 564896.99  6769464.999  99.18300000000454  10.998)'::public.geometry, '2022-02-17 09:48:15.355', NULL, '$createdBy', '2022-02-17 09:48:15.355')""")

      val projecLinks = Seq(
        ProjectLink(1000,roadPart,Track.Combined, Discontinuity.Continuous,AddrMRange(  0, 167),AddrMRange(  0, 167),None,None,createdBy,2621642.toString,  0.0,  167.25 ,SideCode.AgainstDigitizing,(RoadAddressCP,NoCP),(RoadAddressCP,NoCP),List(Point(564987.0,6769633.0,0.0), Point(565123.0,6769720.0,0.0)),project_id,RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, LinkGeomSource.FrozenLinkInterface,167.25 ,107964,490217,3,false,None,1634598047000L,335562039,roadName),
        ProjectLink(1001,roadPart,Track.Combined, Discontinuity.Continuous,AddrMRange(167, 249),AddrMRange(167, 249),None,None,createdBy,2621632.toString,  0.0,   81.498,SideCode.AgainstDigitizing,(NoCP,NoCP         ),(NoCP,NoCP         ),List(Point(564948.0,6769561.0,0.0), Point(564987.0,6769633.0,0.0)),project_id,RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, LinkGeomSource.FrozenLinkInterface, 81.498,107964,490218,3,false,None,1634598047000L,335562039,roadName),
        ProjectLink(1002,roadPart,Track.Combined, Discontinuity.Continuous,AddrMRange(249, 351),AddrMRange(249, 351),None,None,createdBy,2621636.toString,  0.0,  101.665,SideCode.AgainstDigitizing,(NoCP,NoCP         ),(NoCP,NoCP         ),List(Point(564900.0,6769471.0,0.0), Point(564948.0,6769561.0,0.0)),project_id,RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, LinkGeomSource.FrozenLinkInterface,101.665,107964,490219,3,false,None,1533863903000L,335562039,roadName),
        ProjectLink(1003,roadPart,Track.Combined, Discontinuity.Continuous,AddrMRange(351, 358),AddrMRange(351, 358),None,None,createdBy,2621288.toString,  0.0,    7.843,SideCode.AgainstDigitizing,(NoCP,NoCP         ),(NoCP,NoCP         ),List(Point(564896.0,6769464.0,0.0), Point(564900.0,6769471.0,0.0)),project_id,RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, LinkGeomSource.FrozenLinkInterface,  7.843,107964,490220,3,false,None,1533863903000L,335562039,roadName),
        ProjectLink(1004,roadPart,Track.Combined, Discontinuity.Continuous,AddrMRange(358, 369),AddrMRange(358, 369),None,None,createdBy,2621287.toString,  0.0,   10.998,SideCode.AgainstDigitizing,(NoCP,RoadAddressCP),(NoCP,NoCP         ),List(Point(564891.0,6769455.0,0.0), Point(564896.0,6769464.0,0.0)),project_id,RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, LinkGeomSource.FrozenLinkInterface, 10.998,107964,490281,3,false,None,1533863903000L,335562039,roadName),
        ProjectLink(1005,roadPart,Track.LeftSide, Discontinuity.Continuous,AddrMRange(369, 458),AddrMRange(  0,   0),None,None,createdBy,2621639.toString,  0.0,   87.736,SideCode.AgainstDigitizing,(RoadAddressCP,NoCP),(NoCP,NoCP         ),List(Point(564974.0,6769424.0,0.0), Point(564896.0,6769464.0,0.0)),project_id,RoadAddressChangeType.New,      AdministrativeClass.Municipality, LinkGeomSource.FrozenLinkInterface, 87.736,     0,     0,3,false,None,1533863903000L,335562043,roadName),
        ProjectLink(1007,roadPart,Track.LeftSide, Discontinuity.Continuous,AddrMRange(458, 526),AddrMRange(  0,   0),None,None,createdBy,2621652.toString,  0.0,   67.894,SideCode.AgainstDigitizing,(NoCP,NoCP         ),(NoCP,NoCP         ),List(Point(565034.0,6769391.0,0.0), Point(564974.0,6769424.0,0.0)),project_id,RoadAddressChangeType.New,      AdministrativeClass.Municipality, LinkGeomSource.FrozenLinkInterface, 67.894,     0,     0,3,false,None,1533863903000L,335562043,roadName),
        ProjectLink(1009,roadPart,Track.LeftSide, Discontinuity.Continuous,AddrMRange(526, 621),AddrMRange(  0,   0),None,None,createdBy,2621646.toString,  0.0,   94.565,SideCode.AgainstDigitizing,(NoCP,NoCP         ),(NoCP,NoCP         ),List(Point(565117.0,6769346.0,0.0), Point(565034.0,6769391.0,0.0)),project_id,RoadAddressChangeType.New,      AdministrativeClass.Municipality, LinkGeomSource.FrozenLinkInterface, 94.565,     0,     0,3,false,None,1533863903000L,335562043,roadName),
        ProjectLink(1011,roadPart,Track.LeftSide, Discontinuity.Continuous,AddrMRange(621, 785),AddrMRange(  0, 164),None,None,createdBy,2621704.toString,  0.0,  161.799,SideCode.AgainstDigitizing,(NoCP,UserDefinedCP),(NoCP,NoCP         ),List(Point(565259.0,6769270.0,0.0), Point(565117.0,6769346.0,0.0)),project_id,RoadAddressChangeType.New,      AdministrativeClass.Municipality, LinkGeomSource.FrozenLinkInterface,161.799,     0,     0,3,false,Some(2621704.toString),1533863903000L,335562043,roadName),
        ProjectLink(1013,roadPart,Track.LeftSide, Discontinuity.Continuous,AddrMRange(785, 796),AddrMRange(164, 164),None,None,createdBy,2621704.toString,161.799,172.651,SideCode.AgainstDigitizing,(NoCP,NoCP         ),(NoCP,NoCP         ),List(Point(565269.0,6769265.0,0.0), Point(565259.0,6769270.0,0.0)),project_id,RoadAddressChangeType.New,      AdministrativeClass.Municipality, LinkGeomSource.FrozenLinkInterface, 10.852,     0,     0,3,false,Some(2621704.toString),1533863903000L,335562043,roadName),
        ProjectLink(1014,roadPart,Track.LeftSide, Discontinuity.Continuous,AddrMRange(796, 804),AddrMRange(  0,   0),None,None,createdBy,2621721.toString,  0.0,    7.956,SideCode.AgainstDigitizing,(NoCP,NoCP         ),(NoCP,NoCP         ),List(Point(565276.0,6769261.0,0.0), Point(565269.0,6769265.0,0.0)),project_id,RoadAddressChangeType.New,      AdministrativeClass.Municipality, LinkGeomSource.FrozenLinkInterface,  7.956,     0,     0,3,false,None,1533863903000L,335562043,roadName),
        ProjectLink(1015,roadPart,Track.LeftSide, Discontinuity.Continuous,AddrMRange(804, 936),AddrMRange(  0,   0),None,None,createdBy,2621712.toString,  0.0,  131.415,SideCode.AgainstDigitizing,(NoCP,NoCP         ),(NoCP,NoCP         ),List(Point(565402.0,6769253.0,0.0), Point(565276.0,6769261.0,0.0)),project_id,RoadAddressChangeType.New,      AdministrativeClass.Municipality, LinkGeomSource.FrozenLinkInterface,131.415,     0,     0,3,false,None,1634598047000L,335562043,roadName),
        ProjectLink(1018,roadPart,Track.LeftSide, Discontinuity.EndOfRoad, AddrMRange(936,1035),AddrMRange(  0,   0),None,None,createdBy,2621723.toString,  0.0,   97.837,SideCode.TowardsDigitizing,(NoCP,RoadAddressCP),(NoCP,NoCP         ),List(Point(565402.0,6769253.0,0.0), Point(565485.0,6769303.0,0.0)),project_id,RoadAddressChangeType.New,      AdministrativeClass.Municipality, LinkGeomSource.FrozenLinkInterface, 97.837,     0,     0,3,false,None,1634598047000L,335562043,roadName),
        ProjectLink(1006,roadPart,Track.RightSide,Discontinuity.Continuous,AddrMRange(369, 525),AddrMRange(  0,   0),None,None,createdBy,2621640.toString,  0.0,  155.349,SideCode.AgainstDigitizing,(RoadAddressCP,NoCP),(NoCP,NoCP         ),List(Point(565028.0,6769382.0,0.0), Point(564891.0,6769455.0,0.0)),project_id,RoadAddressChangeType.New,      AdministrativeClass.Municipality, LinkGeomSource.FrozenLinkInterface,155.349,     0,     0,3,false,None,1634598047000L,335562042,roadName),
        ProjectLink(1008,roadPart,Track.RightSide,Discontinuity.Continuous,AddrMRange(525, 619),AddrMRange(  0,   0),None,None,createdBy,2621644.toString,  0.0,   94.008,SideCode.AgainstDigitizing,(NoCP,NoCP         ),(NoCP,NoCP         ),List(Point(565110.0,6769336.0,0.0), Point(565028.0,6769382.0,0.0)),project_id,RoadAddressChangeType.New,      AdministrativeClass.Municipality, LinkGeomSource.FrozenLinkInterface, 94.008,     0,     0,3,false,None,1634598047000L,335562042,roadName),
        ProjectLink(1010,roadPart,Track.RightSide,Discontinuity.Continuous,AddrMRange(619, 785),AddrMRange(  0,   0),None,None,createdBy,2621698.toString,  0.0,  165.951,SideCode.AgainstDigitizing,(NoCP,UserDefinedCP),(NoCP,RoadAddressCP),List(Point(565258.0,6769260.0,0.0), Point(565110.0,6769336.0,0.0)),project_id,RoadAddressChangeType.New,      AdministrativeClass.Municipality, LinkGeomSource.FrozenLinkInterface,165.951,     0,     0,3,false,None,1533863903000L,335562042,roadName),
        ProjectLink(1012,roadPart,Track.RightSide,Discontinuity.Continuous,AddrMRange(785, 794),AddrMRange(785, 794),None,None,createdBy,2621718.toString,  0.0,    9.277,SideCode.AgainstDigitizing,(NoCP,NoCP         ),(NoCP,NoCP         ),List(Point(565266.0,6769255.0,0.0), Point(565258.0,6769260.0,0.0)),project_id,RoadAddressChangeType.Transfer, AdministrativeClass.Municipality, LinkGeomSource.FrozenLinkInterface,  9.277,  1111,490282,3,false,None,1533863903000L,335560417,roadName),
        ProjectLink(1016,roadPart,Track.RightSide,Discontinuity.Continuous,AddrMRange(794, 938),AddrMRange(794, 938),None,None,createdBy,2621709.toString,  0.0,  146.366,SideCode.AgainstDigitizing,(NoCP,NoCP         ),(NoCP,NoCP         ),List(Point(565406.0,6769245.0,0.0), Point(565266.0,6769255.0,0.0)),project_id,RoadAddressChangeType.Transfer, AdministrativeClass.Municipality, LinkGeomSource.FrozenLinkInterface,146.366,  1111,490283,3,false,None,1634598047000L,335560417,roadName),
        ProjectLink(1017,roadPart,Track.RightSide,Discontinuity.EndOfRoad, AddrMRange(938,1035),AddrMRange(938,1035),None,None,createdBy,2621724.toString,  0.0,   99.195,SideCode.TowardsDigitizing,(NoCP,RoadAddressCP),(NoCP,NoCP         ),List(Point(565406.0,6769245.0,0.0), Point(565485.0,6769303.0,0.0)),project_id,RoadAddressChangeType.Transfer, AdministrativeClass.Municipality, LinkGeomSource.FrozenLinkInterface, 99.195,  1111,490284,3,false,None,1634598047000L,335560417,roadName)
      )
      when(mockNodesAndJunctionsService.expireObsoleteNodesAndJunctions(any[Seq[ProjectLink]], any[Option[DateTime]], any[String])).thenReturn(Seq())
      projectLinkDAO.create(projecLinks)
      projectService.updateRoadwaysAndLinearLocationsWithProjectLinks(project_id)
      val roadways = roadwayDAO.fetchAllByRoadPart(roadPart)
      val firstRw  = roadways.find(r => r.addrMRange.start == 0 && r.track == Track.Combined)
      firstRw should be ('defined)
      val secondRw = roadways.find(r => r.addrMRange.end == 785 && r.track == Track.RightSide)
      secondRw should be ('defined)
      val thirdRw  = roadways.find(r => r.addrMRange.start == 369 && r.addrMRange.end == 1035 && r.track == Track.LeftSide)
      thirdRw should be ('defined)
      val fourthRw = roadways.find(r => r.addrMRange.start == 785 && r.addrMRange.end == 1035 && r.track == Track.RightSide)
      fourthRw should be ('defined)
    }
  }

  test("Test defaultSectionCalculatorStrategy.updateRoadwaysAndLinearLocationsWithProjectLinks() " +
       "When project has splitted projectLinks with sidecode againstDigitizing " +
       "Then linearLocations should have correct ordering and geometries.") {
          /*
                     / \
                    / \/\
                   /  /\ \
                  /  /  \ \  / T0
                 /  /    \ \/
             T1 /  / T2   \/
          */
    runWithRollback {
      val roadPart = RoadPart(46002,1)
      val createdBy = "Test"
      val roadName = None
      val projectId = Sequences.nextViiteProjectId

      val project = Project(projectId, ProjectState.UpdatingToRoadNetwork, "f", createdBy, DateTime.now(), "", DateTime.now(), DateTime.now(), "", Seq(), Seq(), None, None, Set())

      val projectLinks = Seq(
        ProjectLink(1000,roadPart,Track.RightSide,Discontinuity.Continuous,        AddrMRange(  0,148),AddrMRange(  0,148),None,None,Some(createdBy),1633068.toString,0.0  ,147.618,SideCode.TowardsDigitizing,(RoadAddressCP,  JunctionPointCP),(RoadAddressCP,JunctionPointCP),List(Point(388372.0,7292385.0,0.0), Point(388487.0,7292477.0,0.0)),projectId,RoadAddressChangeType.Termination,AdministrativeClass.Municipality, LinkGeomSource.FrozenLinkInterface,147.618,107929,501533,14,false,None,1614640323000L,335718837,roadName,None,None,None,None,None),
        ProjectLink(1001,roadPart,Track.Combined, Discontinuity.Continuous,        AddrMRange(  0,148),AddrMRange(  0,148),None,None,Some(createdBy),1633067.toString,0.0  ,147.652,SideCode.TowardsDigitizing,(RoadAddressCP,  JunctionPointCP),(RoadAddressCP,JunctionPointCP),List(Point(388354.0,7292405.0,0.0), Point(388471.0,7292496.0,0.0)),projectId,RoadAddressChangeType.Transfer,   AdministrativeClass.Municipality, LinkGeomSource.FrozenLinkInterface,147.652,107926,501523,14,false,None,1614640323000L,335718838,roadName,None,None,None,None,None),
        ProjectLink(1002,roadPart,Track.Combined, Discontinuity.Continuous,        AddrMRange(148,169),AddrMRange(148,169),None,None,Some(createdBy),1633072.toString,0.0  , 21.437,SideCode.TowardsDigitizing,(JunctionPointCP,NoCP           ),(JunctionPointCP,NoCP         ),List(Point(388471.0,7292496.0,0.0), Point(388488.0,7292509.0,0.0)),projectId,RoadAddressChangeType.Transfer,   AdministrativeClass.Municipality, LinkGeomSource.FrozenLinkInterface, 21.437,107926,501524,14,false,None,1614640323000L,335718838,roadName,None,None,None,None,None),
        ProjectLink(1003,roadPart,Track.RightSide,Discontinuity.MinorDiscontinuity,AddrMRange(148,169),AddrMRange(148,169),None,None,Some(createdBy),1633074.toString,0.0  , 21.327,SideCode.TowardsDigitizing,(JunctionPointCP,RoadAddressCP  ),(JunctionPointCP,RoadAddressCP),List(Point(388487.0,7292477.0,0.0), Point(388504.0,7292490.0,0.0)),projectId,RoadAddressChangeType.Termination,AdministrativeClass.Municipality, LinkGeomSource.FrozenLinkInterface, 21.327,107929,501534,14,false,None,1614640323000L,335718837,roadName,None,None,None,None,None),
        ProjectLink(1004,roadPart,Track.Combined, Discontinuity.Continuous,        AddrMRange(169,193),AddrMRange(169,193),None,None,Some(createdBy),7400060.toString,0.0  , 24.427,SideCode.AgainstDigitizing,(NoCP,           JunctionPointCP),(NoCP,         JunctionPointCP),List(Point(388504.0,7292490.0,0.0), Point(388488.0,7292509.0,0.0)),projectId,RoadAddressChangeType.Transfer,   AdministrativeClass.Municipality, LinkGeomSource.FrozenLinkInterface, 24.427,107927,501525,14,false,None,1614640323000L,335718842,roadName,None,None,None,None,None),
        ProjectLink(1005,roadPart,Track.RightSide,Discontinuity.Continuous,        AddrMRange(169,194),AddrMRange(169,194),None,None,Some(createdBy),7400059.toString,0.0  , 24.644,SideCode.AgainstDigitizing,(RoadAddressCP,  JunctionPointCP),(RoadAddressCP,JunctionPointCP),List(Point(388487.0,7292477.0,0.0), Point(388471.0,7292496.0,0.0)),projectId,RoadAddressChangeType.Termination,AdministrativeClass.Municipality, LinkGeomSource.FrozenLinkInterface, 24.644,107928,501528,14,false,None,1614640323000L,335718841,roadName,None,None,None,None,None),
        ProjectLink(1006,roadPart,Track.Combined, Discontinuity.Continuous,        AddrMRange(193,307),AddrMRange(193,307),None,None,Some(createdBy),1633065.toString,0.0  ,113.892,SideCode.AgainstDigitizing,(JunctionPointCP,NoCP           ),(JunctionPointCP,NoCP         ),List(Point(388576.0,7292402.0,0.0), Point(388504.0,7292490.0,0.0)),projectId,RoadAddressChangeType.Transfer,   AdministrativeClass.Municipality, LinkGeomSource.FrozenLinkInterface,113.892,107927,501526,14,false,None,1614640323000L,335718842,roadName,None,None,None,None,None),
        ProjectLink(1007,roadPart,Track.RightSide,Discontinuity.Continuous,        AddrMRange(194,308),AddrMRange(194,308),None,None,Some(createdBy),1633045.toString,0.0  ,114.201,SideCode.AgainstDigitizing,(JunctionPointCP,NoCP           ),(JunctionPointCP,NoCP         ),List(Point(388560.0,7292389.0,0.0), Point(388487.0,7292477.0,0.0)),projectId,RoadAddressChangeType.Termination,AdministrativeClass.Municipality, LinkGeomSource.FrozenLinkInterface,114.201,107928,501529,14,false,None,1614640323000L,335718841,roadName,None,None,None,None,None),
        ProjectLink(1008,roadPart,Track.Combined, Discontinuity.Continuous,        AddrMRange(307,308),AddrMRange(307,308),None,None,Some(createdBy),1633239.toString,0.0  ,  0.997,SideCode.AgainstDigitizing,(NoCP,           NoCP           ),(NoCP,           RoadAddressCP),List(Point(388577.0,7292401.0,0.0), Point(388576.0,7292402.0,0.0)),projectId,RoadAddressChangeType.Transfer,   AdministrativeClass.Municipality, LinkGeomSource.FrozenLinkInterface,  0.997,107927,501527,14,false,Some(1633239.toString),1614640323000L,335718842,roadName,None,None,None,None,None),
        ProjectLink(1009,roadPart,Track.Combined, Discontinuity.Continuous,        AddrMRange(308,429),AddrMRange(308,429),None,None,Some(createdBy),1633239.toString,0.997,121.649,SideCode.AgainstDigitizing,(NoCP,           NoCP           ),(NoCP,           RoadAddressCP),List(Point(388654.0,7292308.0,0.0), Point(388577.0,7292401.0,0.0)),projectId,RoadAddressChangeType.Transfer,   AdministrativeClass.Municipality, LinkGeomSource.FrozenLinkInterface,120.652,107927,501527,14,false,Some(1633239.toString),1614640323000L,335718842,roadName,None,None,None,None,None),
        ProjectLink(1010,roadPart,Track.RightSide,Discontinuity.Continuous,        AddrMRange(308,429),AddrMRange(308,429),None,None,Some(createdBy),1633240.toString,0.0  ,121.571,SideCode.AgainstDigitizing,(NoCP,           RoadAddressCP  ),(NoCP,           RoadAddressCP),List(Point(388638.0,7292295.0,0.0), Point(388560.0,7292389.0,0.0)),projectId,RoadAddressChangeType.Termination,AdministrativeClass.Municipality, LinkGeomSource.FrozenLinkInterface,121.571,107928,501530,14,false,None,1614640323000L,335718841,roadName,None,None,None,None,None),
        ProjectLink(1011,roadPart,Track.Combined, Discontinuity.Continuous,        AddrMRange(429,450),AddrMRange(429,450),None,None,Some(createdBy),1633241.toString,0.0  , 20.66 ,SideCode.TowardsDigitizing,(RoadAddressCP,  JunctionPointCP),(RoadAddressCP,JunctionPointCP),List(Point(388638.0,7292295.0,0.0), Point(388654.0,7292308.0,0.0)),projectId,RoadAddressChangeType.Termination,AdministrativeClass.Municipality, LinkGeomSource.FrozenLinkInterface, 20.66 ,107930,501531,14,false,None,1614640323000L,335718862,roadName,None,None,None,None,None),
        ProjectLink(1012,roadPart,Track.Combined, Discontinuity.EndOfRoad,         AddrMRange(429,534),AddrMRange(450,555),None,None,Some(createdBy),1633224.toString,0.0  ,105.224,SideCode.TowardsDigitizing,(NoCP,           RoadAddressCP  ),(JunctionPointCP,RoadAddressCP),List(Point(388654.0,7292308.0,0.0), Point(388736.0,7292374.0,0.0)),projectId,RoadAddressChangeType.Transfer,   AdministrativeClass.Municipality, LinkGeomSource.FrozenLinkInterface,105.224,107930,501532,14,false,None,1614640323000L,335718857,roadName,None,None,None,None,None)
      )

      val roadways = Seq(
        Roadway(107926,335718838,RoadPart(46002,1),AdministrativeClass.Municipality,Track.LeftSide,Discontinuity.Continuous,         AddrMRange(  0,169),reversed = false,DateTime.parse("2022-06-05T00:00:00.000+03:00"),None,createdBy,roadName,14,TerminationCode.NoTermination,DateTime.parse("2022-06-03T00:00:00.000+03:00"),None),
        Roadway(107927,335718842,RoadPart(46002,1),AdministrativeClass.Municipality,Track.LeftSide,Discontinuity.MinorDiscontinuity, AddrMRange(169,429),reversed = false,DateTime.parse("2022-06-05T00:00:00.000+03:00"),None,createdBy,roadName,14,TerminationCode.NoTermination,DateTime.parse("2022-06-03T00:00:00.000+03:00"),None),
        Roadway(107928,335718841,RoadPart(46002,1),AdministrativeClass.Municipality,Track.RightSide,Discontinuity.Continuous,        AddrMRange(169,429),reversed = false,DateTime.parse("2022-06-05T00:00:00.000+03:00"),None,createdBy,roadName,14,TerminationCode.NoTermination,DateTime.parse("2022-06-03T00:00:00.000+03:00"),None),
        Roadway(107929,335718837,RoadPart(46002,1),AdministrativeClass.Municipality,Track.RightSide,Discontinuity.MinorDiscontinuity,AddrMRange(  0,169),reversed = false,DateTime.parse("2022-06-05T00:00:00.000+03:00"),None,createdBy,roadName,14,TerminationCode.NoTermination,DateTime.parse("2022-06-03T00:00:00.000+03:00"),None),
        Roadway(107930,335718845,RoadPart(46002,1),AdministrativeClass.Municipality,Track.Combined,Discontinuity.EndOfRoad,          AddrMRange(429,555),reversed = false,DateTime.parse("2022-06-05T00:00:00.000+03:00"),None,createdBy,roadName,14,TerminationCode.NoTermination,DateTime.parse("2022-06-03T00:00:00.000+03:00"),None)
      )

      val linearLocations = Seq(
        LinearLocation(501528, 1.0, 7400059.toString, 0.0,  24.644, SideCode.AgainstDigitizing, 1614640323000L, (CalibrationPointReference(Some(169),Some(RoadAddressCP)),CalibrationPointReference(Some(194),Some(JunctionPointCP))), List(Point(388487.493,7292477.245,0.0), Point(388471.717,7292496.178,0.0)), LinkGeomSource.FrozenLinkInterface, 335718841, Some(DateTime.parse("2022-06-03T00:00:00.000+03:00")), None),
        LinearLocation(501529, 2.0, 1633045.toString, 0.0, 114.201, SideCode.AgainstDigitizing, 1614640323000L, (CalibrationPointReference(Some(194),Some(JunctionPointCP)),CalibrationPointReference(None,None)), List(Point(388560.601,7292389.512,0.0), Point(388487.493,7292477.245,0.0)), LinkGeomSource.FrozenLinkInterface, 335718841, Some(DateTime.parse("2022-06-03T00:00:00.000+03:00")), None),
        LinearLocation(501533, 1.0, 1633068.toString, 0.0, 147.618, SideCode.TowardsDigitizing, 1614640323000L, (CalibrationPointReference(Some(0),Some(RoadAddressCP)),CalibrationPointReference(Some(148),Some(JunctionPointCP))), List(Point(388372.084,7292385.202,0.0), Point(388487.493,7292477.245,0.0)), LinkGeomSource.FrozenLinkInterface, 335718837, Some(DateTime.parse("2022-06-03T00:00:00.000+03:00")), None),
        LinearLocation(501523, 1.0, 1633067.toString, 0.0, 147.652, SideCode.TowardsDigitizing, 1614640323000L, (CalibrationPointReference(Some(0),Some(RoadAddressCP)),CalibrationPointReference(Some(148),Some(JunctionPointCP))), List(Point(388354.971,7292405.782,0.0), Point(388471.717,7292496.178,0.0)), LinkGeomSource.FrozenLinkInterface, 335718838, Some(DateTime.parse("2022-06-03T00:00:00.000+03:00")), None),
        LinearLocation(501526, 2.0, 1633065.toString, 0.0, 113.892, SideCode.AgainstDigitizing, 1614640323000L, (CalibrationPointReference(Some(193),Some(JunctionPointCP)),CalibrationPointReference(None,None)), List(Point(388576.757,7292402.664,0.0), Point(388504.223,7292490.472,0.0)), LinkGeomSource.FrozenLinkInterface, 335718842, Some(DateTime.parse("2022-06-03T00:00:00.000+03:00")), None),
        LinearLocation(501534, 2.0, 1633074.toString, 0.0,  21.327, SideCode.TowardsDigitizing, 1614640323000L, (CalibrationPointReference(Some(148),Some(JunctionPointCP)),CalibrationPointReference(Some(169),Some(RoadAddressCP))), List(Point(388487.493,7292477.245,0.0), Point(388504.223,7292490.472,0.0)), LinkGeomSource.FrozenLinkInterface, 335718837, Some(DateTime.parse("2022-06-03T00:00:00.000+03:00")), None),
        LinearLocation(501524, 2.0, 1633072.toString, 0.0,  21.437, SideCode.TowardsDigitizing, 1614640323000L, (CalibrationPointReference(Some(148),Some(JunctionPointCP)),CalibrationPointReference(None,None)), List(Point(388471.717,7292496.178,0.0), Point(388488.666,7292509.304,0.0)), LinkGeomSource.FrozenLinkInterface, 335718838, Some(DateTime.parse("2022-06-03T00:00:00.000+03:00")), None),
        LinearLocation(501525, 1.0, 7400060.toString, 0.0,  24.427, SideCode.AgainstDigitizing, 1614640323000L, (CalibrationPointReference(None,None),CalibrationPointReference(Some(193),Some(JunctionPointCP))), List(Point(388504.223,7292490.472,0.0), Point(388488.666,7292509.304,0.0)), LinkGeomSource.FrozenLinkInterface, 335718842, Some(DateTime.parse("2022-06-03T00:00:00.000+03:00")), None),
        LinearLocation(501532, 2.0, 1633224.toString, 0.0, 105.224, SideCode.TowardsDigitizing, 1614640323000L, (CalibrationPointReference(Some(450),Some(JunctionPointCP)),CalibrationPointReference(Some(555),Some(RoadAddressCP))), List(Point(388654.231,7292308.876,0.0), Point(388736.089,7292374.992,0.0)), LinkGeomSource.FrozenLinkInterface, 335718845, Some(DateTime.parse("2022-06-03T00:00:00.000+03:00")), None),
        LinearLocation(501531, 1.0, 1633241.toString, 0.0,  20.66 , SideCode.TowardsDigitizing, 1614640323000L, (CalibrationPointReference(Some(429),Some(RoadAddressCP)),CalibrationPointReference(Some(450),Some(JunctionPointCP))), List(Point(388638.159,7292295.894,0.0), Point(388654.231,7292308.876,0.0)), LinkGeomSource.FrozenLinkInterface, 335718845, Some(DateTime.parse("2022-06-03T00:00:00.000+03:00")), None),
        LinearLocation(501527, 3.0, 1633239.toString, 0.0, 121.649, SideCode.AgainstDigitizing, 1614640323000L, (CalibrationPointReference(None,None),CalibrationPointReference(Some(429),Some(RoadAddressCP))), List(Point(388654.231,7292308.876,0.0), Point(388576.757,7292402.664,0.0)), LinkGeomSource.FrozenLinkInterface, 335718842, Some(DateTime.parse("2022-06-03T00:00:00.000+03:00")), None),
        LinearLocation(501530, 3.0, 1633240.toString, 0.0, 121.571, SideCode.AgainstDigitizing, 1614640323000L, (CalibrationPointReference(None,None),CalibrationPointReference(Some(429),Some(RoadAddressCP))), List(Point(388638.159,7292295.894,0.0), Point(388560.601,7292389.512,0.0)), LinkGeomSource.FrozenLinkInterface, 335718841, Some(DateTime.parse("2022-06-03T00:00:00.000+03:00")), None)
      )

      when(mockNodesAndJunctionsService.expireObsoleteNodesAndJunctions(any[Seq[ProjectLink]], any[Option[DateTime]], any[String])).thenReturn(Seq())
      roadwayDAO.create(roadways)
      linearLocationDAO.create(linearLocations, createdBy)
      projectDAO.create(project)
      projectReservedPartDAO.reserveRoadPart(projectId, roadPart, createdBy)
      projectLinkDAO.create(projectLinks)
      projectService.updateRoadwaysAndLinearLocationsWithProjectLinks(projectId)

      val newRoadway    = roadwayDAO.fetchAllByRoadPart(roadPart).filter(r => r.endDate.isEmpty && r.validTo.isEmpty)
      val newLinearLocs = linearLocationDAO.fetchByLinkId(projectLinks.filter(_.status != RoadAddressChangeType.Termination).map(_.linkId).toSet).filter(_.validTo.isEmpty)

      newLinearLocs.foreach(ll => (ll.endMValue - ll.startMValue) should be (GeometryUtils.geometryLength(ll.geometry) +- 1))

      // Check geometry
      val linkGroups = newLinearLocs.groupBy(_.linkId).values
      linkGroups.foreach(lls => lls.maxBy(_.endMValue).endMValue should be (GeometryUtils.geometryLength(lls.map(_.geometry).flatten.toSeq) +- 1))
      linkGroups.foreach(lls => lls.minBy(_.startMValue).startMValue should be (0))

      val roadwayNumbers = newRoadway.map(_.roadwayNumber)
      val rwnGroupedLls  = newLinearLocs.groupBy(_.roadwayNumber)
      val sortedLls: Map[Long, List[LinearLocation]] = roadwayNumbers.zip(roadwayNumbers.map(rwnGroupedLls)).toMap.mapValues(_.sortBy(_.orderNumber))

      sortedLls.foreach(_._2.map(_.orderNumber) shouldBe sorted)
      sortedLls.foreach(x => all(x._2.map(_.orderNumber.toInt)) should (be >= 1 and be <= 3))

      // Check geometry is continuous
      val it = sortedLls.values.flatten.toSeq.sliding(2)
      while (it.hasNext) {
        it.next() match {
          case Seq(first, next) => first.getLastPoint should be(next.getFirstPoint)
        }
      }
    }
  }

  test("Test projectService.recalculateProjectLinks() and the way roadway numbers are handled in the process") {
    /**
     *  0                    Road address change types on project links                   700
     *  |---------->|==========>|---------->|==========>|---------->|---------->|----------> End of road
     *    Unchanged  Terminated   Transfer    Terminated  Transfer    Transfer    Transfer
     *
     *
     *         Original addr m values and roadway on project links before recalculation
     *  0           100         200         300         400       500          600        700
     *  |---------->|==========>|---------->|==========>|---------->|---------->|----------> End of Road
     *    Roadway1    Roadway1    Roadway1    Roadway1    Roadway1    Roadway1    Roadway1
     *
     *
     *         New addr m values and roadways on project links after recalculation
     *  0           100         100         200         200       300          400        500
     *  |---------->|==========>|---------->|==========>|---------->|---------->|----------> End of Road
     *    Roadway2    Roadway3    Roadway4    Roadway5    Roadway6    Roadway6    Roadway6
     *
     */

    runWithRollback {
      val elyNumber = 9
      val roadPart = RoadPart(18001, 1)
      val reversedBoolean = false
      val track = Track.Combined
      val terminatedStatus = RoadAddressChangeType.Termination
      val transferStatus = RoadAddressChangeType.Transfer
      val unchangedStatus = RoadAddressChangeType.Unchanged
      val projectId = 999
      val originalRoadwayId = 10
      val originalRoadwayNumber = 1000

      // create the original roadway
      roadwayDAO.create(Seq(
        Roadway(originalRoadwayId, originalRoadwayNumber, roadPart, AdministrativeClass.State, Track.Combined, Discontinuity.EndOfRoad, AddrMRange(0, 700), reversedBoolean, DateTime.now(),None,"Test", Some("Test"), elyNumber, TerminationCode.NoTermination, DateTime.now(),None)
      ))

      val linearLocationId1 = 1
      val linearLocationId2 = 2
      val linearLocationId3 = 3
      val linearLocationId4 = 4
      val linearLocationId5 = 5
      val linearLocationId6 = 6
      val linearLocationId7 = 7

      val linkId1 = "testtest-test-test-test-testtest:1"
      val linkId2 = "testtest-test-test-test-testtest:2"
      val linkId3 = "testtest-test-test-test-testtest:3"
      val linkId4 = "testtest-test-test-test-testtest:4"
      val linkId5 = "testtest-test-test-test-testtest:5"
      val linkId6 = "testtest-test-test-test-testtest:6"
      val linkId7 = "testtest-test-test-test-testtest:7"

      // create linear locations
      val linearLocations = Seq(
        dummyLinearLocation(id = linearLocationId1, roadwayNumber = originalRoadwayNumber, orderNumber = 1L, linkId = linkId1, startMValue = 0.0, endMValue = 100.0, SideCode.TowardsDigitizing, LinkGeomSource.FrozenLinkInterface, 0L, Seq(Point(0.0, 100.0))),
        dummyLinearLocation(id = linearLocationId2, roadwayNumber = originalRoadwayNumber, orderNumber = 2L, linkId = linkId2, startMValue = 0.0, endMValue = 100.0, SideCode.TowardsDigitizing, LinkGeomSource.FrozenLinkInterface, 0L, Seq(Point(100.0, 200.0))),
        dummyLinearLocation(id = linearLocationId3, roadwayNumber = originalRoadwayNumber, orderNumber = 3L, linkId = linkId3, startMValue = 0.0, endMValue = 100.0, SideCode.TowardsDigitizing, LinkGeomSource.FrozenLinkInterface, 0L, Seq(Point(200.0, 300.0))),
        dummyLinearLocation(id = linearLocationId4, roadwayNumber = originalRoadwayNumber, orderNumber = 4L, linkId = linkId4, startMValue = 0.0, endMValue = 100.0, SideCode.TowardsDigitizing, LinkGeomSource.FrozenLinkInterface, 0L, Seq(Point(300.0, 400.0))),
        dummyLinearLocation(id = linearLocationId5, roadwayNumber = originalRoadwayNumber, orderNumber = 5L, linkId = linkId5, startMValue = 0.0, endMValue = 100.0, SideCode.TowardsDigitizing, LinkGeomSource.FrozenLinkInterface, 0L, Seq(Point(400.0, 500.0))),
        dummyLinearLocation(id = linearLocationId6, roadwayNumber = originalRoadwayNumber, orderNumber = 6L, linkId = linkId6, startMValue = 0.0, endMValue = 100.0, SideCode.TowardsDigitizing, LinkGeomSource.FrozenLinkInterface, 0L, Seq(Point(500.0, 600.0))),
        dummyLinearLocation(id = linearLocationId7, roadwayNumber = originalRoadwayNumber, orderNumber = 7L, linkId = linkId7, startMValue = 0.0, endMValue = 100.0, SideCode.TowardsDigitizing, LinkGeomSource.FrozenLinkInterface, 0L, Seq(Point(600.0, 700.0)))
      )
      linearLocationDAO.create(linearLocations)

      // create project
      val project = Project(projectId, ProjectState.Incomplete, "testProject", "test", DateTime.now, "", startDate = DateTime.now().plusDays(10), DateTime.now(),"", Seq(),Seq(),None, None, Set())
      projectDAO.create(project)

      // reserve the road part for the project
      projectReservedPartDAO.reserveRoadPart(projectId, roadPart, "test")

      // The project links are in a state in which they have changes applied to them but not yet recalculated.
      val projectLinks = Seq(
        ProjectLink(1000, roadPart, track, Discontinuity.MinorDiscontinuity, AddrMRange(  0, 100), AddrMRange(  0, 100), None, None, Some("test"), "testtest-test-test-test-testtest:1",0 , 100, SideCode.TowardsDigitizing, (RoadAddressCP, NoCP), (RoadAddressCP, NoCP), List(Point(0.0, 0.0, 0.0), Point(100.0, 0.0, 0.0)), projectId, unchangedStatus, AdministrativeClass.State, LinkGeomSource.FrozenLinkInterface, 100.0, originalRoadwayId, linearLocationId1, elyNumber, reversedBoolean,None, 1634598047000L, originalRoadwayNumber, Some("testRoadName")),
        ProjectLink(1001, roadPart, track, Discontinuity.Continuous,         AddrMRange(100, 200), AddrMRange(100, 200), None, None, Some("test"), "testtest-test-test-test-testtest:2",0 , 100, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), List(Point(100.0, 0.0, 0.0), Point(200.0, 0.0, 0.0)), projectId, terminatedStatus, AdministrativeClass.State, LinkGeomSource.FrozenLinkInterface, 100.0, originalRoadwayId, linearLocationId2, elyNumber, reversedBoolean,None, 1634598047000L, originalRoadwayNumber, Some("testRoadName")),
        ProjectLink(1002, roadPart, track, Discontinuity.MinorDiscontinuity, AddrMRange(200, 300), AddrMRange(200, 300), None, None, Some("test"), "testtest-test-test-test-testtest:3",0 , 100, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), List(Point(200.0, 0.0, 0.0), Point(300.0, 0.0, 0.0)), projectId, transferStatus, AdministrativeClass.State, LinkGeomSource.FrozenLinkInterface, 100.0, originalRoadwayId, linearLocationId3, elyNumber, reversedBoolean,None, 1634598047000L, originalRoadwayNumber, Some("testRoadName")),
        ProjectLink(1003, roadPart, track, Discontinuity.Continuous,         AddrMRange(300, 400), AddrMRange(300, 400), None, None, Some("test"), "testtest-test-test-test-testtest:4",0 , 100, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), List(Point(300.0, 0.0, 0.0), Point(400.0, 0.0, 0.0)), projectId, terminatedStatus, AdministrativeClass.State, LinkGeomSource.FrozenLinkInterface, 100.0, originalRoadwayId, linearLocationId4, elyNumber, reversedBoolean,None, 1634598047000L, originalRoadwayNumber, Some("testRoadName")),
        ProjectLink(1004, roadPart, track, Discontinuity.Continuous,         AddrMRange(400, 500), AddrMRange(400, 500), None, None, Some("test"), "testtest-test-test-test-testtest:5",0 , 100, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), List(Point(400.0, 0.0, 0.0), Point(500.0, 0.0, 0.0)), projectId, transferStatus, AdministrativeClass.State, LinkGeomSource.FrozenLinkInterface, 100.0, originalRoadwayId, linearLocationId5, elyNumber, reversedBoolean,None, 1634598047000L, originalRoadwayNumber, Some("testRoadName")),
        ProjectLink(1005, roadPart, track, Discontinuity.Continuous,         AddrMRange(500, 600), AddrMRange(500, 600), None, None, Some("test"), "testtest-test-test-test-testtest:6",0 , 100, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), List(Point(500.0, 0.0, 0.0), Point(600.0, 0.0, 0.0)), projectId, transferStatus, AdministrativeClass.State, LinkGeomSource.FrozenLinkInterface, 100.0, originalRoadwayId, linearLocationId6, elyNumber, reversedBoolean,None, 1634598047000L, originalRoadwayNumber, Some("testRoadName")),
        ProjectLink(1006, roadPart, track, Discontinuity.EndOfRoad,          AddrMRange(600, 700), AddrMRange(600, 700), None, None, Some("test"), "testtest-test-test-test-testtest:7",0 , 100, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, RoadAddressCP), List(Point(600.0, 0.0, 0.0), Point(700.0, 0.0, 0.0)), projectId, transferStatus, AdministrativeClass.State, LinkGeomSource.FrozenLinkInterface, 100.0, originalRoadwayId, linearLocationId7, elyNumber, reversedBoolean,None, 1634598047000L, originalRoadwayNumber, Some("testRoadName"))
      )
      projectLinkDAO.create(projectLinks)

      // fetch project links from database and check that they indeed were created
      val createdProjectLinks = projectLinkDAO.fetchProjectLinks(projectId)
      createdProjectLinks.size should be (7)

      // recalculate the project links (this will assign new roadway numbers and update the addr M values)
      projectService.recalculateProjectLinks(projectId, "test")

      // fetch the project links from the database
      val recalculatedProjectLinks = projectLinkDAO.fetchProjectLinks(projectId).sortBy(_.originalAddrMRange.start)

      recalculatedProjectLinks.foreach(pl => println(pl.addrMRange.start, pl.addrMRange.end, pl.status, pl.roadwayNumber))

      // none of the project links should have the original roadway number anymore
      recalculatedProjectLinks.map(_.roadwayNumber) should not contain originalRoadwayNumber

      val terminatedProjectLinks = recalculatedProjectLinks.filter(_.status == RoadAddressChangeType.Termination)
      val newRoadwayNumbersForTerminatedProjectLinks = terminatedProjectLinks.map(_.roadwayNumber).toSet
      newRoadwayNumbersForTerminatedProjectLinks.size should be (2) // The terminated project links should have different roadway numbers

      val firstFourProjectLinks = recalculatedProjectLinks.take(4)
      val firstFourPlRoadwayNumbers = firstFourProjectLinks.map(_.roadwayNumber).toSet
      firstFourPlRoadwayNumbers.size should be (4) // the first four project links should all have different roadway numbers

      val lastThreeProjectLinks = recalculatedProjectLinks.takeRight(3)
      val lastThreePlRoadwayNumber = lastThreeProjectLinks.map(_.roadwayNumber).toSet

      lastThreePlRoadwayNumber.size should be (1) // the last three project links should have same roadway number
    }
  }

  test("Test projectService.recalculateProjectLinks() When creating two track road from single track road" +
    "Then recalculation should not change addresses when there is no reason to change them.") {
    /**
     * Based on a real life project on road parts 5/212 and 5/213
     *
     *                          Before calculation
     *
     *                          Direction -->
     *                                      v Road part changing spot
     *           5112        5525       6008 0          160
     *               _________          ____ ___________
     *  ...._______/          \_______/                 \_____________....
     *
     *
     *                          After calculation
     *
     *                          Direction -->
     *                                       v Road part changing spot
     *            5112       5525       6008 0           160
     *               __________          ___ ____________
     *  ....________/          \_______/                 \____________....
     *              \_________/        \____ ___________/
     *            5112       5525       6008 0
     */

    runWithRollback {

      val roadPart1 = RoadPart(49529,1)
      val roadPart2 = RoadPart(49529,2)
      val projectId = Sequences.nextViiteProjectId
      val roadName = Some("testRoad")

      val roadways = Seq(
        Roadway(19743,54807,roadPart1,AdministrativeClass.State,Track.Combined,Discontinuity.Continuous,AddrMRange(0, 6008),false,DateTime.now().minusDays(2),None,"test",None,14,TerminationCode.NoTermination,DateTime.now().minusDays(2),None),
        Roadway(19619,54808,roadPart2,AdministrativeClass.State,Track.Combined,Discontinuity.Continuous,AddrMRange(0, 2459),false,DateTime.now().minusDays(2),None,"test",None,14,TerminationCode.NoTermination,DateTime.now().minusDays(2),None),
        Roadway(19180,54809,roadPart2,AdministrativeClass.State,Track.Combined,Discontinuity.Continuous,AddrMRange(2459, 7292),false,DateTime.now().minusDays(2),None,"test",None,14,TerminationCode.NoTermination,DateTime.now().minusDays(2),None)
      )

      val linearLocations = Seq(
        LinearLocation(166980, 5.0,"97617a95-d87c-4690-a514-77277d392f0d:1",0.0,609.401,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(522502.441,7020069.825,0.0), Point(522371.838,7020665.037,0.0)),FrozenLinkInterface,54807,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(163410, 8.0,"c3941d24-5369-4387-b997-1e63f03273c7:1",0.0,74.192,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(519541.575,7028128.956,0.0), Point(519507.039,7028194.57,0.0)),FrozenLinkInterface,54809,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(166998, 23.0,"ae9e5d46-e235-475a-a79b-e887d9f81001:1",0.0,175.103,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(520737.456,7024870.276,0.0), Point(520746.183,7025044.795,0.0)),FrozenLinkInterface,54807,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(166991, 16.0,"1f01def2-99fb-4ebb-8ca9-18039e59efcc:1",0.0,10.182,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(521152.798,7023620.099,0.0), Point(521147.724,7023628.927,0.0)),FrozenLinkInterface,54807,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(161720, 9.0,"d6a5c957-81a1-459b-ac62-3b91f8277437:1",0.0,7.228,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(520450.132,7026076.14,0.0), Point(520448.397,7026083.157,0.0)),FrozenLinkInterface,54808,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(166987, 12.0,"581e2063-fb34-4b3c-a603-705c4752bc9f:1",0.0,284.246,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(522156.749,7021704.883,0.0), Point(522089.251,7021980.766,0.0)),FrozenLinkInterface,54807,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(161728, 17.0,"86e02fff-a34a-4d20-a820-641e046199cb:1",0.0,31.982,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(519899.377,7027281.085,0.0), Point(519886.492,7027310.356,0.0)),FrozenLinkInterface,54808,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(163425, 23.0,"e14a6af8-536a-4d31-aa38-e0fe01be7347:1",0.0,207.012,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(518762.377,7030165.967,0.0), Point(518712.975,7030366.995,0.0)),FrozenLinkInterface,54809,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(163424, 22.0,"8b91ca65-f805-4767-a95d-d7c108a547cf:1",0.0,239.625,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(518819.195,7029933.185,0.0), Point(518762.377,7030165.967,0.0)),FrozenLinkInterface,54809,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(161724, 13.0,"25d2f258-dc27-4591-925b-55337db61d5a:1",0.0,225.996,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(520298.767,7026618.145,0.0), Point(520196.983,7026819.66,0.0)),FrozenLinkInterface,54808,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(163429, 27.0,"ebe17118-9b19-48fd-a6b9-01c3ef48cce9:1",0.0,283.552,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(518470.142,7030910.213,0.0), Point(518243.282,7031078.407,0.0)),FrozenLinkInterface,54809,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(161723, 12.0,"a4ab658b-71ea-4fcd-a242-dd5ed5caae78:1",0.0,115.291,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(520337.368,7026509.518,0.0), Point(520298.767,7026618.145,0.0)),FrozenLinkInterface,54808,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(163428, 26.0,"eafe4318-fe63-45f4-9b0c-3fb22c402d33:1",0.0,56.131,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(518508.217,7030868.97,0.0), Point(518470.142,7030910.213,0.0)),FrozenLinkInterface,54809,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(163423, 21.0,"4fb9e3ae-1f36-479f-b676-33cd79cd4b1d:1",0.0,124.223,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(518849.394,7029812.69,0.0), Point(518819.195,7029933.185,0.0)),FrozenLinkInterface,54809,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(166984, 9.0,"c65c30d8-011a-4c89-a17d-33084cdc9b23:1",0.0,101.156,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(522277.263,7021122.781,0.0), Point(522256.631,7021221.811,0.0)),FrozenLinkInterface,54807,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(166994, 19.0,"47f9e320-b838-4f20-a315-5d1b767110c8:1",0.0,35.448,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(520749.693,7024391.763,0.0), Point(520743.967,7024426.742,0.0)),FrozenLinkInterface,54807,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(163406, 4.0,"f3b2e7a4-5343-4fb6-8c5d-2d20a96e3e68:1",0.0,71.16,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(519821.641,7027467.404,0.0), Point(519794.658,7027533.249,0.0)),FrozenLinkInterface,54809,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(161716, 5.0,"4437edb2-18bc-4091-85ee-c01b2dddcd42:1",0.0,419.29,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(520724.38,7025201.987,0.0), Point(520585.172,7025596.671,0.0)),FrozenLinkInterface,54808,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(163419, 17.0,"7231a9a4-8137-498c-91ac-c3f02148913b:1",0.0,32.534,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(518967.495,7029329.348,0.0), Point(518960.19,7029361.049,0.0)),FrozenLinkInterface,54809,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(166977, 2.0,"2d8f890d-cef7-4fe3-8d3c-7f33d150e114:1",0.0,3.048,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(522527.843,7019923.035,0.0), Point(522527.342,7019926.042,0.0)),FrozenLinkInterface,54807,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(163426, 24.0,"97be4fd2-1ecb-409e-b949-88228fd48049:1",0.0,449.361,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(518712.975,7030366.995,0.0), Point(518565.806,7030788.8,0.0)),FrozenLinkInterface,54809,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(163405, 3.0,"938b993f-6161-4b15-8fea-765f4eb37f79:1",0.0,10.46,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(519825.549,7027457.702,0.0), Point(519821.641,7027467.404,0.0)),FrozenLinkInterface,54809,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(166992, 17.0,"05a95125-8477-41c0-960b-c8c82df87bf3:1",0.0,625.112,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(521147.724,7023628.927,0.0), Point(520835.836,7024170.629,0.0)),FrozenLinkInterface,54807,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(161715, 4.0,"ead13298-6a76-4832-b19c-481db469ae7e:1",0.0,69.013,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(520738.606,7025134.935,0.0), Point(520724.38,7025201.987,0.0)),FrozenLinkInterface,54808,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(166997, 22.0,"87ddfa9a-b7c5-4661-b38e-afdd34fbbc44:1",0.0,307.573,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(520730.01,7024562.949,0.0), Point(520737.456,7024870.276,0.0)),FrozenLinkInterface,54807,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(163414, 12.0,"8e6890c3-d20e-46f9-b432-78dbe4628bd6:1",0.0,633.976,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(519386.68,7028401.931,0.0), Point(519086.926,7028959.401,0.0)),FrozenLinkInterface,54809,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(166988, 13.0,"497ec443-aa9b-4283-872f-a6f960cf07d4:1",0.0,337.228,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(522089.251,7021980.766,0.0), Point(521932.104,7022278.623,0.0)),FrozenLinkInterface,54807,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(161725, 14.0,"d03ca2a9-a81b-4339-9547-aac130640154:1",0.0,326.92,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(520196.983,7026819.66,0.0), Point(520011.571,7027088.738,0.0)),FrozenLinkInterface,54808,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(161719, 8.0,"525fd0ed-b95b-433b-9851-040c7d0b5f15:1",0.0,29.085,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(520457.303,7026047.953,0.0), Point(520450.132,7026076.14,0.0)),FrozenLinkInterface,54808,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(163411, 9.0,"e0ecacea-1ad3-4e66-82bb-9a98eedd8ad6:1",0.0,24.416,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(519507.039,7028194.57,0.0), Point(519495.406,7028216.037,0.0)),FrozenLinkInterface,54809,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(163418, 16.0,"9093782b-0434-4f24-9082-7b122263580b:1",0.0,107.05,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(518995.075,7029225.92,0.0), Point(518967.495,7029329.348,0.0)),FrozenLinkInterface,54809,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(166983, 8.0,"732804ba-a6a9-4aaa-b0b1-d3b5c1380705:1",0.0,16.475,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(522280.818,7021106.694,0.0), Point(522277.263,7021122.781,0.0)),FrozenLinkInterface,54807,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(163408, 6.0,"48e3633f-bd9d-49e6-a1cd-555e2ea8040a:1",0.0,43.96,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(519729.485,7027691.871,0.0), Point(519712.602,7027732.454,0.0)),FrozenLinkInterface,54809,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(163422, 20.0,"9a345526-c2c5-4e2b-926e-e12b89a50175:1",0.0,181.227,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(518892.71,7029636.716,0.0), Point(518849.394,7029812.69,0.0)),FrozenLinkInterface,54809,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(166981, 6.0,"4343a0fd-73bd-41bd-a952-672732616cbd:1",0.0,16.517,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(522371.838,7020665.037,0.0), Point(522368.335,7020681.178,0.0)),FrozenLinkInterface,54807,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(166993, 18.0,"88d0cd7e-4854-470f-9396-267cf3a17efa:1",0.0,238.658,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(520835.836,7024170.629,0.0), Point(520749.693,7024391.763,0.0)),FrozenLinkInterface,54807,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(161718, 7.0,"91a9ca86-b74a-4b16-8b72-5a9b9dfe8ae2:1",0.0,62.079,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(520472.962,7025987.883,0.0), Point(520457.303,7026047.953,0.0)),FrozenLinkInterface,54808,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(163404, 2.0,"7c6eb302-c93c-4c87-b9bc-94da64c13c3e:1",0.0,107.579,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(519866.178,7027358.091,0.0), Point(519825.549,7027457.702,0.0)),FrozenLinkInterface,54809,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(163421, 19.0,"073a89dd-de6d-450a-89e5-80632c9356de:1",0.0,270.235,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(518957.112,7029374.28,0.0), Point(518892.71,7029636.716,0.0)),FrozenLinkInterface,54809,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(163416, 14.0,"e01b1d46-0280-40eb-a5b3-958b0a3a41dd:1",0.0,56.924,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(519014.016,7029165.737,0.0), Point(518996.895,7029220.024,0.0)),FrozenLinkInterface,54809,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(166978, 3.0,"7e6c2e98-fa6c-4bfe-83d5-0abae810376a:1",0.0,26.842,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(522527.342,7019926.042,0.0), Point(522523.022,7019952.534,0.0)),FrozenLinkInterface,54807,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(163415, 13.0,"16be38e1-3e65-43bd-95e5-f492504a8ff4:1",0.0,218.94,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(519086.926,7028959.401,0.0), Point(519014.016,7029165.737,0.0)),FrozenLinkInterface,54809,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(163427, 25.0,"3f8c5837-b2a2-4f34-9c1e-8a47a84d5538:1",0.0,98.744,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(518565.806,7030788.8,0.0), Point(518508.217,7030868.97,0.0)),FrozenLinkInterface,54809,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(163430, 28.0,"a193b050-f164-4a77-a6f4-0531c37c9ec0:1",0.0,20.933,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(518243.282,7031078.407,0.0), Point(518224.69,7031088.026,0.0)),FrozenLinkInterface,54809,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(161714, 3.0,"fd05bea5-4af3-40bf-a5a9-56256ccb6f71:1",0.0,47.238,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(520743.008,7025087.903,0.0), Point(520738.606,7025134.935,0.0)),FrozenLinkInterface,54808,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(163412, 10.0,"7ee2e2eb-bdb2-41aa-89fe-e1753b0a00b9:1",0.0,154.143,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(519495.406,7028216.037,0.0), Point(519418.608,7028349.499,0.0)),FrozenLinkInterface,54809,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(166985, 10.0,"18e013f1-cb02-47a4-9934-3ada0c8c03ab:1",0.0,125.088,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(522256.631,7021221.811,0.0), Point(522230.483,7021344.133,0.0)),FrozenLinkInterface,54807,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(161726, 15.0,"5368e027-e8bd-4841-bd76-f6207b82d17a:1",0.0,25.174,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(520011.571,7027088.738,0.0), Point(519997.81,7027109.818,0.0)),FrozenLinkInterface,54808,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(166989, 14.0,"cb76d85a-68d6-4db1-b85f-2c68599271cc:1",0.0,256.464,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(521932.104,7022278.623,0.0), Point(521804.431,7022501.037,0.0)),FrozenLinkInterface,54807,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(161721, 10.0,"c91052da-cec2-46c5-8e24-269253782067:1",0.0,421.286,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(520448.397,7026083.157,0.0), Point(520342.871,7026490.983,0.0)),FrozenLinkInterface,54808,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(163417, 15.0,"440768aa-2d66-43f6-87da-ad430c2824cc:1",0.0,6.171,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(518996.895,7029220.024,0.0), Point(518995.075,7029225.92,0.0)),FrozenLinkInterface,54809,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(166982, 7.0,"9c45cb15-bc24-4ca0-93c1-5adb39b18c61:1",0.0,434.453,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(522368.335,7020681.178,0.0), Point(522280.818,7021106.694,0.0)),FrozenLinkInterface,54807,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(166996, 21.0,"9f347f05-a02b-49d8-9aef-34a8476ed680:1",0.0,129.939,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(520742.599,7024434.563,0.0), Point(520730.01,7024562.949,0.0)),FrozenLinkInterface,54807,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(161729, 18.0,"a987d1fe-1452-49e3-8170-b4d0589b5523:1",0.0,5.986,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(519886.492,7027310.356,0.0), Point(519884.148,7027315.864,0.0)),FrozenLinkInterface,54808,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(163431, 29.0,"81242218-7ac7-4151-8097-3e107b7ad021:1",0.0,249.4,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(518224.69,7031088.026,0.0), Point(518007.336,7031210.326,0.0)),FrozenLinkInterface,54809,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(161727, 16.0,"0774eee0-de5a-41e0-a0d0-f68ed7d37b9e:1",0.0,197.807,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(519997.81,7027109.818,0.0), Point(519899.377,7027281.085,0.0)),FrozenLinkInterface,54808,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(161717, 6.0,"e533d3f9-2be3-4943-bb16-32136783f5df:1",0.0,407.288,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(520585.172,7025596.671,0.0), Point(520472.962,7025987.883,0.0)),FrozenLinkInterface,54808,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(166979, 4.0,"f3195aa2-ac34-4d4c-8647-a743e8cef6dd:1",0.0,119.083,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(522523.022,7019952.534,0.0), Point(522502.441,7020069.825,0.0)),FrozenLinkInterface,54807,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(166986, 11.0,"04fef3b0-eaf0-4bb9-89c7-5973c9cd4246:1",0.0,368.214,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(522230.483,7021344.133,0.0), Point(522156.749,7021704.883,0.0)),FrozenLinkInterface,54807,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(161713, 2.0,"d439c33a-26e0-4516-a8b8-d98ac993dbbb:1",0.0,7.768,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(520743.705,7025080.166,0.0), Point(520743.008,7025087.903,0.0)),FrozenLinkInterface,54808,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(163409, 7.0,"28cd5b29-afc0-40d5-bf5b-ba27316b22de:1",0.0,431.878,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(519712.602,7027732.454,0.0), Point(519541.575,7028128.956,0.0)),FrozenLinkInterface,54809,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(163432, 30.0,"79f2c6a1-4d68-41ad-bf95-62e0ead9c027:1",0.0,377.043,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(518007.336,7031210.326,0.0), Point(517678.659,7031395.074,0.0)),FrozenLinkInterface,54809,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(166990, 15.0,"ef7cf3ea-e7a4-41d4-99a9-820cc0e2d5a3:1",0.0,1295.076,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(521804.431,7022501.037,0.0), Point(521152.798,7023620.099,0.0)),FrozenLinkInterface,54807,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(163413, 11.0,"8b77e807-0261-4df5-8414-c7b83506be6f:1",0.0,61.395,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(519418.608,7028349.499,0.0), Point(519386.68,7028401.931,0.0)),FrozenLinkInterface,54809,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(163407, 5.0,"da1a294d-19c9-4986-ad9b-38b562b5b7dd:1",0.0,171.501,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(519794.658,7027533.249,0.0), Point(519729.485,7027691.871,0.0)),FrozenLinkInterface,54809,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(161712, 1.0,"a5d680aa-88d7-48dc-be21-aea91d2ad11e:1",0.0,35.458,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(520746.183,7025044.795,0.0), Point(520743.705,7025080.166,0.0)),FrozenLinkInterface,54808,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(161722, 11.0,"0a58c6e5-7f5b-4658-bfb0-fbbfe7892742:1",0.0,19.336,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(520342.871,7026490.983,0.0), Point(520337.368,7026509.518,0.0)),FrozenLinkInterface,54808,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(166995, 20.0,"9e0c17f9-68bd-4880-9e03-4b13f1992ec1:1",0.0,7.94,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(520743.967,7024426.742,0.0), Point(520742.599,7024434.563,0.0)),FrozenLinkInterface,54807,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(163420, 18.0,"28cd30d3-2f7d-4bad-99b2-6b127bef077c:1",0.0,13.584,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(518960.19,7029361.049,0.0), Point(518957.112,7029374.28,0.0)),FrozenLinkInterface,54809,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(166976, 1.0,"3ecfe7a0-d4b5-4732-8374-218e9db4a4f1:1",0.0,478.909,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(522603.402,7019450.126,0.0), Point(522527.843,7019923.035,0.0)),FrozenLinkInterface,54807,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(163403, 1.0,"a987d1fe-1452-49e3-8170-b4d0589b5523:1",5.986,51.878,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(519884.148,7027315.864,0.0), Point(519866.178,7027358.091,0.0)),FrozenLinkInterface,54809,Some(DateTime.now().minusDays(2)),None)
      )

      val projectLinks = Seq(
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.RightSide,Discontinuity.Continuous,AddrMRange(0,0),AddrMRange(0,0),None,None,Some("test"),"f23535b9-a3cd-4f91-80a5-3bdb2593759c:1", 0.0,38.361,SideCode.Unknown,(NoCP, NoCP), (NoCP, NoCP),List(Point(520760.2580000002,7024391.0910002785,105.044), Point(520756.921,7024409.351000278,105.11), Point(520753.868,7024428.913000276,104.985)),projectId,  RoadAddressChangeType.New,AdministrativeClass.State, FrozenLinkInterface,38.361,0,0,8,reversed = false,None,1652179948783L,0,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.RightSide,Discontinuity.Continuous,AddrMRange(0,0),AddrMRange(0,0),None,None,Some("test"),"225cc60f-e8d6-4ba9-af4f-3f7ef2f8b608:1", 0.0,233.89,SideCode.Unknown,(NoCP, NoCP), (NoCP, NoCP),List(Point(520835.836,7024170.629000277,105.002), Point(520836.372,7024175.682000278,104.943), Point(520834.8,7024181.706000278,104.873), Point(520831.657,7024190.872000278,104.84), Point(520821.4420000002,7024214.967000278,104.828), Point(520809.9190000002,7024241.942000277,104.867), Point(520793.157,7024282.27400028,104.902), Point(520786.086,7024304.536000279,104.906), Point(520777.1030000002,7024331.154000279,104.973), Point(520771.701,7024350.060000278,104.957), Point(520766.749,7024366.941000276,104.972), Point(520760.2580000002,7024391.0910002785,105.044)),projectId,  RoadAddressChangeType.New,AdministrativeClass.State, FrozenLinkInterface,233.89,0,0,8,reversed = false,None,1652179948783L,0,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.LeftSide,Discontinuity.Continuous,AddrMRange(0,0),AddrMRange(0,0),None,None,Some("test"),"09656524-27a6-46c3-b3d7-4200d68687c9:1", 0.0,175.402,SideCode.Unknown,(NoCP, NoCP), (NoCP, NoCP),List(Point(520737.4560000002,7024870.276000278,101.546), Point(520734.6800000002,7024884.377000278,101.414), Point(520734.68,7024887.306000277,101.377), Point(520734.8560000002,7024903.7440002775,101.246), Point(520735.459,7024939.899000278,101.186), Point(520736.3620000002,7024973.945000279,101.288), Point(520737.2670000002,7025002.5680002775,101.492), Point(520736.965,7025025.4670002805,101.733), Point(520737.0410000002,7025045.373000276,102.031)),projectId,  RoadAddressChangeType.New,AdministrativeClass.State, FrozenLinkInterface,175.402,0,0,8,reversed = false,None,1652179948783L,0,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.RightSide,Discontinuity.Continuous,AddrMRange(0,0),AddrMRange(0,0),None,None,Some("test"),"e60ed058-be33-4da9-b67c-085194e5ec6a:1", 0.0,81.469,SideCode.Unknown,(NoCP, NoCP), (NoCP, NoCP),List(Point(520744.822,7024483.246000279,105.026), Point(520742.062,7024500.066000278,105.061), Point(520739.129,7024522.939000281,105.12), Point(520735.806,7024546.596000279,105.214), Point(520734.476,7024553.399000279,105.235), Point(520730.0100000002,7024562.949000278,105.399)),projectId,  RoadAddressChangeType.New,AdministrativeClass.State, FrozenLinkInterface,81.469,0,0,8,reversed = false,None,1652179948783L,0,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.RightSide,Discontinuity.Continuous,AddrMRange(0,0),AddrMRange(0,0),None,None,Some("test"),"68479bca-99f9-4465-87c3-2a453ce895a0:1", 0.0,8.101,SideCode.Unknown,(NoCP, NoCP), (NoCP, NoCP),List(Point(520753.868,7024428.913000276,104.985), Point(520752.6200000002,7024436.917000279,105.005)),projectId,  RoadAddressChangeType.New,AdministrativeClass.State, FrozenLinkInterface,8.101,0,0,8,reversed = false,None,1652179948783L,0,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.RightSide,Discontinuity.Continuous,AddrMRange(0,0),AddrMRange(0,0),None,None,Some("test"),"a587beed-2cb3-4c61-bafa-264e836c1914:1", 0.0,46.982,SideCode.Unknown,(NoCP, NoCP), (NoCP, NoCP),List(Point(520752.6200000002,7024436.917000279,105.005), Point(520748.9050000002,7024458.032000278,105.035), Point(520744.822,7024483.246000279,105.026)),projectId,  RoadAddressChangeType.New,AdministrativeClass.State, FrozenLinkInterface,46.982,0,0,8,reversed = false,None,1652179948783L,0,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.Combined,Discontinuity.Continuous,AddrMRange(0,479),AddrMRange(0,479),None,None,Some("test"),"3ecfe7a0-d4b5-4732-8374-218e9db4a4f1:1", 0.0,478.909,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(522603.402,7019450.126,106.027), Point(522557.888,7019739.374000276,105.739), Point(522527.843,7019923.035,101.09000701187496)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.State, FrozenLinkInterface,478.909,19743,166976,8,reversed = false,None,1652179948783L,54807,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.Combined,Discontinuity.Continuous,AddrMRange(479,482),AddrMRange(479,482),None,None,Some("test"),"2d8f890d-cef7-4fe3-8d3c-7f33d150e114:1", 0.0,3.048,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(522527.843,7019923.035,101.09), Point(522527.342,7019926.042,100.96801802617034)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.State, FrozenLinkInterface,3.048,19743,166977,8,reversed = false,None,1652179948783L,54807,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.Combined,Discontinuity.Continuous,AddrMRange(482,509),AddrMRange(482,509),None,None,Some("test"),"7e6c2e98-fa6c-4bfe-83d5-0abae810376a:1", 0.0,26.842,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(522527.342,7019926.042,100.968), Point(522523.0220000002,7019952.5340002775,100.214)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.State, FrozenLinkInterface,26.842,19743,166978,8,reversed = false,None,1652179948783L,54807,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.Combined,Discontinuity.Continuous,AddrMRange(509,628),AddrMRange(509,628),None,None,Some("test"),"f3195aa2-ac34-4d4c-8647-a743e8cef6dd:1", 0.0,119.083,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(522523.022,7019952.534,100.214), Point(522522.861,7019953.518000277,100.188), Point(522502.441,7020069.825,96.78900135898019)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.State, FrozenLinkInterface,119.083,19743,166979,8,reversed = false,None,1652179948783L,54807,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.Combined,Discontinuity.Continuous,AddrMRange(628,1238),AddrMRange(628,1238),None,None,Some("test"),"97617a95-d87c-4690-a514-77277d392f0d:1", 0.0,609.401,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(522502.441,7020069.825,96.789), Point(522482.3510000002,7020169.735000277,94.766), Point(522429.535,7020399.175000277,94.515), Point(522371.838,7020665.037,97.64599991957827)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.State, FrozenLinkInterface,609.401,19743,166980,8,reversed = false,None,1652179948783L,54807,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.Combined,Discontinuity.Continuous,AddrMRange(1238,1255),AddrMRange(1238,1255),None,None,Some("test"),"4343a0fd-73bd-41bd-a952-672732616cbd:1", 0.0,16.517,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(522371.838,7020665.037,97.646), Point(522368.383,7020680.956000277,97.837), Point(522368.3350000002,7020681.178000278,97.838)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.State, FrozenLinkInterface,16.517,19743,166981,8,reversed = false,None,1652179948783L,54807,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.Combined,Discontinuity.Continuous,AddrMRange(1255,1690),AddrMRange(1255,1690),None,None,Some("test"),"9c45cb15-bc24-4ca0-93c1-5adb39b18c61:1", 0.0,434.453,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(522368.335,7020681.178,97.838), Point(522360.697,7020725.0390002765,98.321), Point(522343.303,7020811.478000278,99.32), Point(522329.382,7020879.203000278,99.607), Point(522313.706,7020952.280000278,98.82), Point(522297.153,7021028.031000279,96.678), Point(522280.818,7021106.694000278,94.383)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.State, FrozenLinkInterface,434.453,19743,166982,8,reversed = false,None,1652179948783L,54807,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.Combined,Discontinuity.Continuous,AddrMRange(1690,1706),AddrMRange(1690,1706),None,None,Some("test"),"732804ba-a6a9-4aaa-b0b1-d3b5c1380705:1", 0.0,16.475,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(522280.818,7021106.694,94.383), Point(522278.742,7021116.089000277,94.368), Point(522277.263,7021122.781,94.35700019333811)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.State, FrozenLinkInterface,16.475,19743,166983,8,reversed = false,None,1652179948783L,54807,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.Combined,Discontinuity.Continuous,AddrMRange(1706,1807),AddrMRange(1706,1807),None,None,Some("test"),"c65c30d8-011a-4c89-a17d-33084cdc9b23:1", 0.0,101.156,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(522277.263,7021122.781,94.357), Point(522256.631,7021221.811,96.25799219843559)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.State, FrozenLinkInterface,101.156,19743,166984,8,reversed = false,None,1652179948783L,54807,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.Combined,Discontinuity.Continuous,AddrMRange(1807,1933),AddrMRange(1807,1933),None,None,Some("test"),"18e013f1-cb02-47a4-9934-3ada0c8c03ab:1", 0.0,125.088,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(522256.631,7021221.811,96.258), Point(522250.383,7021250.939000277,96.708), Point(522241.203,7021295.609000277,96.902), Point(522230.483,7021344.133,97.037999661759)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.State, FrozenLinkInterface,125.088,19743,166985,8,reversed = false,None,1652179948783L,54807,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.Combined,Discontinuity.Continuous,AddrMRange(1933,2301),AddrMRange(1933,2301),None,None,Some("test"),"04fef3b0-eaf0-4bb9-89c7-5973c9cd4246:1", 0.0,368.214,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(522230.483,7021344.133,97.038), Point(522222.228,7021381.829000277,97.124), Point(522217.338,7021405.413000278,97.187), Point(522195.799,7021510.570000278,96.235), Point(522179.0250000002,7021592.845000278,96.73), Point(522156.7490000002,7021704.883000277,101.075)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.State, FrozenLinkInterface,368.214,19743,166986,8,reversed = false,None,1652179948783L,54807,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.Combined,Discontinuity.Continuous,AddrMRange(2301,2586),AddrMRange(2301,2586),None,None,Some("test"),"581e2063-fb34-4b3c-a603-705c4752bc9f:1", 0.0,284.246,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(522156.749,7021704.883,101.075), Point(522151.5030000002,7021728.532000278,101.866), Point(522136.0930000002,7021802.1080002785,103.396), Point(522120.351,7021870.758000277,103.594), Point(522108.871,7021920.030000279,102.814), Point(522089.251,7021980.766,100.90600438543999)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.State, FrozenLinkInterface,284.246,19743,166987,8,reversed = false,None,1652179948783L,54807,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.Combined,Discontinuity.Continuous,AddrMRange(2586,2923),AddrMRange(2586,2923),None,None,Some("test"),"497ec443-aa9b-4283-872f-a6f960cf07d4:1", 0.0,337.228,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(522089.251,7021980.766,100.906), Point(522072.525,7022021.935000277,99.037), Point(522041.619,7022088.204000277,96.243), Point(521981.8650000002,7022193.48500028,97.941), Point(521932.104,7022278.623,102.14598239835041)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.State, FrozenLinkInterface,337.228,19743,166988,8,reversed = false,None,1652179948783L,54807,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.Combined,Discontinuity.Continuous,AddrMRange(2923,3180),AddrMRange(2923,3180),None,None,Some("test"),"cb76d85a-68d6-4db1-b85f-2c68599271cc:1", 0.0,256.464,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(521932.104,7022278.623,102.146), Point(521909.116,7022320.296000278,103.282), Point(521871.534,7022384.302000278,103.888), Point(521804.431,7022501.037000277,104.287)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.State, FrozenLinkInterface,256.464,19743,166989,8,reversed = false,None,1652179948783L,54807,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.Combined,Discontinuity.Continuous,AddrMRange(3180,4476),AddrMRange(3180,4476),None,None,Some("test"),"ef7cf3ea-e7a4-41d4-99a9-820cc0e2d5a3:1", 0.0,1295.076,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(521804.431,7022501.037,104.287), Point(521760.228,7022575.7900002785,107.028), Point(521646.914,7022770.039000278,113.747), Point(521585.593,7022875.133000277,114.191), Point(521536.285,7022959.637000279,114.548), Point(521526.441,7022976.177000277,114.142), Point(521503.574,7023014.595000276,113.197), Point(521500.7410000002,7023019.356000276,113.079), Point(521467.501,7023077.072000278,111.517), Point(521435.153,7023134.793000277,109.963), Point(521405.5160000002,7023184.51300028,108.709), Point(521377.6720000002,7023231.571000277,107.849), Point(521355.765,7023271.155000279,107.487), Point(521317.755,7023332.809000278,107.104), Point(521314.102,7023338.901000277,107.084), Point(521312.1690000002,7023342.122000278,107.067), Point(521306.9730000002,7023352.653000277,107.037), Point(521297.0930000002,7023369.523000277,106.946), Point(521290.794,7023381.140000277,106.885), Point(521283.6090000002,7023394.390000275,106.815), Point(521276.061,7023406.489000278,106.744), Point(521253.598,7023446.133000277,106.445), Point(521230.2430000002,7023486.088000279,106.246), Point(521199.702,7023538.475000277,106.262), Point(521170.9610000002,7023587.309000278,106.751), Point(521152.798,7023620.09900028,106.892)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.State, FrozenLinkInterface,1295.076,19743,166990,8,reversed = false,None,1652179948783L,54807,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.Combined,Discontinuity.Continuous,AddrMRange(4476,4486),AddrMRange(4476,4486),None,None,Some("test"),"1f01def2-99fb-4ebb-8ca9-18039e59efcc:1", 0.0,10.182,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(521152.798,7023620.099,106.892), Point(521150.656,7023623.843000278,106.895), Point(521147.724,7023628.927,106.88000078294807)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.State, FrozenLinkInterface,10.182,19743,166991,8,reversed = false,None,1652179948783L,54807,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.Combined,Discontinuity.Continuous,AddrMRange(4486,5112),AddrMRange(4486,5112),None,None,Some("test"),"05a95125-8477-41c0-960b-c8c82df87bf3:1", 0.0,625.112,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(521147.724,7023628.927,106.88), Point(521136.2780000002,7023649.5970002785,106.811), Point(521112.928,7023688.662000277,106.49), Point(521086.885,7023732.166000279,106.312), Point(521059.0330000002,7023781.004000278,106.452), Point(521031.1850000002,7023828.952000278,106.358), Point(521006.023,7023874.242000276,105.82), Point(520978.222,7023922.708000278,105.302), Point(520948.8020000004,7023973.388000278,105.12), Point(520921.7830000002,7024020.446000278,105.068), Point(520897.764,7024060.871000277,105.015), Point(520870.145,7024108.534000279,105.032), Point(520846.732,7024150.764000279,104.996), Point(520835.836,7024170.629,105.00199998408385)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.State, FrozenLinkInterface,625.112,19743,166992,8,reversed = false,None,1652179948783L,54807,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.LeftSide,Discontinuity.Continuous,AddrMRange(5112,5351),AddrMRange(5112,5351),None,None,Some("test"),"88d0cd7e-4854-470f-9396-267cf3a17efa:1", 0.0,238.658,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(520835.836,7024170.629,105.002), Point(520833.3570000002,7024172.317000278,104.996), Point(520830.168,7024175.880000278,105.041), Point(520826.605,7024181.883000278,105.08), Point(520823.041,7024188.447000277,105.085), Point(520816.1010000002,7024204.390000277,105.09), Point(520801.096,7024238.902000277,105.122), Point(520787.778,7024273.040000278,105.153), Point(520778.9620000002,7024296.298000278,105.163), Point(520770.145,7024321.4320002785,105.136), Point(520761.143,7024350.5050002765,105.162), Point(520754.9520000002,7024372.075000278,105.219), Point(520749.693,7024391.76300028,105.239)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.State, FrozenLinkInterface,238.658,19743,166993,8,reversed = false,None,1652179948783L,54807,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.LeftSide,Discontinuity.Continuous,AddrMRange(5351,5387),AddrMRange(5351,5387),None,None,Some("test"),"47f9e320-b838-4f20-a315-5d1b767110c8:1", 0.0,35.448,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(520749.693,7024391.763,105.239), Point(520748.2390000002,7024401.765000276,105.286), Point(520744.736,7024421.614000277,105.289), Point(520743.967,7024426.742,105.28999995941493)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.State, FrozenLinkInterface,35.448,19743,166994,8,reversed = false,None,1652179948783L,54807,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.LeftSide,Discontinuity.Continuous,AddrMRange(5387,5394),AddrMRange(5387,5394),None,None,Some("test"),"9e0c17f9-68bd-4880-9e03-4b13f1992ec1:1", 0.0,7.94,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(520743.967,7024426.742,105.29), Point(520742.599,7024434.563000278,105.295)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.State, FrozenLinkInterface,7.94,19743,166995,8,reversed = false,None,1652179948783L,54807,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.LeftSide,Discontinuity.Continuous,AddrMRange(5394,5525),AddrMRange(5394,5525),None,None,Some("test"),"9f347f05-a02b-49d8-9aef-34a8476ed680:1", 0.0,129.939,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(520742.599,7024434.563,105.295), Point(520740.5470000002,7024447.0750002805,105.327), Point(520737.7310000002,7024461.310000277,105.337), Point(520733.3520000002,7024487.872000279,105.36), Point(520730.4330000002,7024509.472000277,105.405), Point(520728.682,7024526.694000279,105.406), Point(520727.806,7024541.872000279,105.438), Point(520727.2220000002,7024549.461000277,105.433), Point(520727.514,7024555.590000278,105.415), Point(520730.01,7024562.949,105.39900020271045)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.State, FrozenLinkInterface,129.939,19743,166996,8,reversed = false,None,1652179948783L,54807,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.Combined,Discontinuity.Continuous,AddrMRange(5525,5833),AddrMRange(5525,5833),None,None,Some("test"),"87ddfa9a-b7c5-4661-b38e-afdd34fbbc44:1", 0.0,307.573,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(520730.01,7024562.949,105.399), Point(520729.3740000002,7024577.088000281,105.4), Point(520728.263,7024596.470000276,105.421), Point(520727.7860000004,7024627.448000276,105.315), Point(520728.899,7024656.995000277,105.125), Point(520729.581,7024672.66300028,104.908), Point(520731.131,7024708.256000278,104.413), Point(520733.65,7024772.885000278,103.094), Point(520735.697,7024833.185000277,101.972), Point(520736.527,7024853.374000278,101.7), Point(520737.4560000002,7024870.276000278,101.546)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.State, FrozenLinkInterface,307.573,19743,166997,8,reversed = false,None,1652179948783L,54807,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.RightSide,Discontinuity.Continuous,AddrMRange(5833,6008),AddrMRange(5833,6008),None,None,Some("test"),"ae9e5d46-e235-475a-a79b-e887d9f81001:1", 0.0,175.103,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(520737.456,7024870.276,101.546), Point(520740.291,7024879.179000278,101.473), Point(520741.7810000002,7024894.8580002785,101.343), Point(520742.4270000002,7024912.066000278,101.278), Point(520743.1950000002,7024943.56400028,101.313), Point(520744.539,7024975.061000277,101.434), Point(520745.691,7025009.247000278,101.763), Point(520746.183,7025044.795,102.29699889000827)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.State, FrozenLinkInterface,175.103,19743,166998,8,reversed = false,None,1652179948783L,54807,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart2,Track.LeftSide,Discontinuity.Continuous,AddrMRange(0,0),AddrMRange(0,0),None,None,Some("test"),"1db94fef-e5c5-48b6-aa64-474169789542:1", 0.0,35.193,SideCode.Unknown,(NoCP, NoCP), (NoCP, NoCP),List(Point(520737.0410000002,7025045.373000276,102.031), Point(520736.1320000002,7025064.7730002785,102.268), Point(520735.4770000002,7025080.531000279,101.49)),projectId,  RoadAddressChangeType.New,AdministrativeClass.State, FrozenLinkInterface,35.193,0,0,8,reversed = false,None,1652179948783L,0,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart2,Track.LeftSide,Discontinuity.Continuous,AddrMRange(0,0),AddrMRange(0,0),None,None,Some("test"),"8cf04822-612a-4fdb-a8c6-99a2ce1c1cb9:1", 0.0,7.488,SideCode.Unknown,(NoCP, NoCP), (NoCP, NoCP),List(Point(520735.4770000002,7025080.531000279,101.49), Point(520735.1010000002,7025088.010000277,102.657)),projectId,  RoadAddressChangeType.New,AdministrativeClass.State, FrozenLinkInterface,7.488,0,0,8,reversed = false,None,1652179948783L,0,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart2,Track.LeftSide,Discontinuity.Continuous,AddrMRange(0,0),AddrMRange(0,0),None,None,Some("test"),"9a55f49f-5eba-4ea3-a50f-771ee09f6fe8:1", 0.0,114.811,SideCode.Unknown,(NoCP, NoCP), (NoCP, NoCP),List(Point(520735.1010000002,7025088.010000277,102.657), Point(520731.9620000002,7025124.625000278,103.184), Point(520730.474,7025138.62100028,103.4), Point(520727.7940000002,7025157.380000278,103.664), Point(520725.708,7025170.185000278,103.808), Point(520723.6240000002,7025188.349000281,104.114), Point(520723.028,7025193.709000278,104.207), Point(520724.38,7025201.987000278,104.414)),projectId,  RoadAddressChangeType.New,AdministrativeClass.State, FrozenLinkInterface,114.811,0,0,8,reversed = false,None,1652179948783L,0,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart2,Track.RightSide,Discontinuity.Continuous,AddrMRange(0,36),AddrMRange(0,36),None,None,Some("test"),"a5d680aa-88d7-48dc-be21-aea91d2ad11e:1", 0.0,35.458,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(520746.183,7025044.795,102.297), Point(520745.338,7025058.448000277,102.459), Point(520744.8480000002,7025064.654000277,102.536), Point(520743.705,7025080.166,102.14701230623473)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.State, FrozenLinkInterface,35.458,19619,161712,8,reversed = false,None,1652179948783L,54808,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart2,Track.RightSide,Discontinuity.Continuous,AddrMRange(36,43),AddrMRange(36,43),None,None,Some("test"),"d439c33a-26e0-4516-a8b8-d98ac993dbbb:1", 0.0,7.768,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(520743.705,7025080.166,102.147), Point(520743.008,7025087.903,102.87396895424315)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.State, FrozenLinkInterface,7.768,19619,161713,8,reversed = false,None,1652179948783L,54808,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart2,Track.RightSide,Discontinuity.Continuous,AddrMRange(43,91),AddrMRange(43,91),None,None,Some("test"),"fd05bea5-4af3-40bf-a5a9-56256ccb6f71:1", 0.0,47.238,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(520743.008,7025087.903,102.874), Point(520740.6310000002,7025114.6820002785,103.246), Point(520738.606,7025134.935,103.52499627458486)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.State, FrozenLinkInterface,47.238,19619,161714,8,reversed = false,None,1652179948783L,54808,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart2,Track.RightSide,Discontinuity.Continuous,AddrMRange(91,160),AddrMRange(91,160),None,None,Some("test"),"ead13298-6a76-4832-b19c-481db469ae7e:1", 0.0,69.013,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(520738.606,7025134.935,103.525), Point(520736.2540000002,7025151.630000278,103.8), Point(520732.962,7025172.79100028,104.096), Point(520729.6710000002,7025189.250000279,104.295), Point(520728.9650000002,7025193.4830002785,104.387), Point(520724.38,7025201.987000278,104.414)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.State, FrozenLinkInterface,69.013,19619,161715,8,reversed = false,None,1652179948783L,54808,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart2,Track.Combined,Discontinuity.Continuous,AddrMRange(160,580),AddrMRange(160,580),None,None,Some("test"),"4437edb2-18bc-4091-85ee-c01b2dddcd42:1", 0.0,419.29,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(520724.38,7025201.987,104.414), Point(520720.125,7025221.187000279,104.687), Point(520712.4550000002,7025256.41700028,105.19), Point(520703.355,7025290.4770002775,105.661), Point(520691.165,7025327.38900028,106.164), Point(520677.496,7025362.665000277,106.621), Point(520651.3270000002,7025434.9720002785,107.656), Point(520626.0780000002,7025497.181000279,108.428), Point(520592.854,7025577.027000279,109.161), Point(520585.172,7025596.6710002795,109.305)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.State, FrozenLinkInterface,419.29,19619,161716,8,reversed = false,None,1652179948783L,54808,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart2,Track.Combined,Discontinuity.Continuous,AddrMRange(580,988),AddrMRange(580,988),None,None,Some("test"),"e533d3f9-2be3-4943-bb16-32136783f5df:1", 0.0,407.288,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(520585.172,7025596.671,109.305), Point(520575.283,7025623.186000278,109.362), Point(520549.9810000002,7025696.083000279,109.451), Point(520527.3190000002,7025775.225000277,109.168), Point(520511.878,7025834.813000279,108.732), Point(520497.848,7025890.421000278,108.05), Point(520484.0250000002,7025946.996000278,107.341), Point(520472.962,7025987.883,106.78300182239434)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.State, FrozenLinkInterface,407.288,19619,161717,8,reversed = false,None,1652179948783L,54808,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart2,Track.Combined,Discontinuity.Continuous,AddrMRange(988,1050),AddrMRange(988,1050),None,None,Some("test"),"91a9ca86-b74a-4b16-8b72-5a9b9dfe8ae2:1", 0.0,62.079,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(520472.962,7025987.883,106.783), Point(520467.901,7026006.628000278,106.51), Point(520457.303,7026047.953000278,105.989)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.State, FrozenLinkInterface,62.079,19619,161718,8,reversed = false,None,1652179948783L,54808,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart2,Track.Combined,Discontinuity.Continuous,AddrMRange(1050,1079),AddrMRange(1050,1079),None,None,Some("test"),"525fd0ed-b95b-433b-9851-040c7d0b5f15:1", 0.0,29.085,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(520457.303,7026047.953,105.989), Point(520451.6350000002,7026070.07000028,105.713), Point(520450.132,7026076.14,103.46101521228222)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.State, FrozenLinkInterface,29.085,19619,161719,8,reversed = false,None,1652179948783L,54808,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart2,Track.Combined,Discontinuity.Continuous,AddrMRange(1079,1086),AddrMRange(1079,1086),None,None,Some("test"),"d6a5c957-81a1-459b-ac62-3b91f8277437:1", 0.0,7.228,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(520450.132,7026076.14,103.461), Point(520448.397,7026083.157,105.07393007391327)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.State, FrozenLinkInterface,7.228,19619,161720,8,reversed = false,None,1652179948783L,54808,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart2,Track.Combined,Discontinuity.Continuous,AddrMRange(1086,1509),AddrMRange(1086,1509),None,None,Some("test"),"c91052da-cec2-46c5-8e24-269253782067:1", 0.0,421.286,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(520448.397,7026083.157,105.074), Point(520443.111,7026104.53600028,105.242), Point(520435.6270000002,7026134.403000278,104.874), Point(520425.433,7026174.249000278,104.348), Point(520414.313,7026218.586000277,103.763), Point(520404.333,7026258.361000278,103.217), Point(520392.644,7026301.700000278,102.702), Point(520381.2260000002,7026344.335000279,102.12), Point(520370.278,7026389.768000279,101.485), Point(520362.062,7026421.605000278,101.072), Point(520355.607,7026444.93400028,100.775), Point(520349.0040000002,7026469.876000279,100.437), Point(520342.871,7026490.983,100.14400023210378)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.State, FrozenLinkInterface,421.286,19619,161721,8,reversed = false,None,1652179948783L,54808,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart2,Track.Combined,Discontinuity.Continuous,AddrMRange(1509,1528),AddrMRange(1509,1528),None,None,Some("test"),"0a58c6e5-7f5b-4658-bfb0-fbbfe7892742:1", 0.0,19.336,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(520342.871,7026490.983,100.144), Point(520337.608,7026508.84200028,99.923), Point(520337.368,7026509.518000279,99.914)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.State, FrozenLinkInterface,19.336,19619,161722,8,reversed = false,None,1652179948783L,54808,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart2,Track.Combined,Discontinuity.Continuous,AddrMRange(1528,1644),AddrMRange(1528,1644),None,None,Some("test"),"a4ab658b-71ea-4fcd-a242-dd5ed5caae78:1", 0.0,115.291,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(520337.368,7026509.518,99.914), Point(520323.6130000002,7026548.326000279,99.397), Point(520314.616,7026574.815000279,99.057), Point(520305.6200000002,7026600.30500028,98.706), Point(520299.1130000002,7026617.340000277,98.456), Point(520298.767,7026618.145,98.46299745425885)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.State, FrozenLinkInterface,115.291,19619,161723,8,reversed = false,None,1652179948783L,54808,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart2,Track.Combined,Discontinuity.Continuous,AddrMRange(1644,1870),AddrMRange(1644,1870),None,None,Some("test"),"25d2f258-dc27-4591-925b-55337db61d5a:1", 0.0,225.996,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(520298.767,7026618.145,98.463), Point(520288.591,7026641.718000279,98.131), Point(520278.91,7026664.475000278,97.776), Point(520269.55,7026684.8860002775,97.43), Point(520260.0220000002,7026704.161000279,97.038), Point(520249.27,7026726.485000278,96.552), Point(520232.136,7026758.563000278,95.75), Point(520217.38,7026784.352000279,95.092), Point(520198.22,7026817.540000279,94.141), Point(520196.983,7026819.66,94.07300624392387)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.State, FrozenLinkInterface,225.996,19619,161724,8,reversed = false,None,1652179948783L,54808,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart2,Track.Combined,Discontinuity.Continuous,AddrMRange(1870,2198),AddrMRange(1870,2198),None,None,Some("test"),"d03ca2a9-a81b-4339-9547-aac130640154:1", 0.0,326.92,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(520196.983,7026819.66,94.073), Point(520186.9290000002,7026836.872000279,93.59), Point(520180.4280000002,7026847.136000279,93.283), Point(520172.045,7026860.822000277,92.867), Point(520153.179,7026890.187000277,91.973), Point(520133.2100000002,7026917.790000279,91.082), Point(520114.906,7026944.306000278,90.193), Point(520097.093,7026968.634000278,89.36), Point(520074.7320000002,7026999.456000277,88.58), Point(520052.5810000002,7027030.90500028,87.877), Point(520035.446,7027054.937000277,87.473), Point(520021.549,7027074.684000279,87.351), Point(520011.571,7027088.738,87.26300177626025)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.State, FrozenLinkInterface,326.92,19619,161725,8,reversed = false,None,1652179948783L,54808,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart2,Track.Combined,Discontinuity.Continuous,AddrMRange(2198,2223),AddrMRange(2198,2223),None,None,Some("test"),"5368e027-e8bd-4841-bd76-f6207b82d17a:1", 0.0,25.174,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(520011.571,7027088.738,87.263), Point(519997.81,7027109.818,87.25700000589332)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.State, FrozenLinkInterface,25.174,19619,161726,8,reversed = false,None,1652179948783L,54808,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart2,Track.Combined,Discontinuity.Continuous,AddrMRange(2223,2421),AddrMRange(2223,2421),None,None,Some("test"),"0774eee0-de5a-41e0-a0d0-f68ed7d37b9e:1", 0.0,197.807,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(519997.81,7027109.818,87.257), Point(519978.76,7027138.766000278,87.44), Point(519974.105,7027145.859000279,87.506), Point(519956.142,7027173.94400028,87.747), Point(519932.504,7027215.258000278,88.054), Point(519915.026,7027247.553000278,88.316), Point(519906.5640000002,7027264.793000278,88.427), Point(519899.377,7027281.085,88.50999875939692)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.State, FrozenLinkInterface,197.807,19619,161727,8,reversed = false,None,1652179948783L,54808,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart2,Track.Combined,Discontinuity.Continuous,AddrMRange(2421,2453),AddrMRange(2421,2453),None,None,Some("test"),"86e02fff-a34a-4d20-a820-641e046199cb:1", 0.0,31.982,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(519899.377,7027281.085,88.51), Point(519892.749,7027295.94900028,88.503), Point(519888.32,7027306.027000279,88.558), Point(519886.492,7027310.356,88.51900164589388)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.State, FrozenLinkInterface,31.982,19619,161728,8,reversed = false,None,1652179948783L,54808,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart2,Track.Combined,Discontinuity.Continuous,AddrMRange(2453,2459),AddrMRange(2453,2459),None,None,Some("test"),"a987d1fe-1452-49e3-8170-b4d0589b5523:1", 0.0,5.986,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(519886.492,7027310.356,88.519), Point(519884.148,7027315.864,88.52453857187722)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.State, FrozenLinkInterface,5.986,19619,161729,8,reversed = false,None,1652179948783L,54808,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart2,Track.Combined,Discontinuity.Continuous,AddrMRange(2459,2505),AddrMRange(2459,2505),None,None,Some("test"),"a987d1fe-1452-49e3-8170-b4d0589b5523:1", 5.986,51.878,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(519884.148,7027315.864,88.52453857187722), Point(519866.178,7027358.0910002785,88.567)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.State, FrozenLinkInterface,45.892,19180,163403,8,reversed = false,None,1652179948783L,54809,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart2,Track.Combined,Discontinuity.Continuous,AddrMRange(2505,2613),AddrMRange(2505,2613),None,None,Some("test"),"7c6eb302-c93c-4c87-b9bc-94da64c13c3e:1", 0.0,107.579,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(519866.178,7027358.091,88.567), Point(519851.5720000002,7027393.62700028,88.52), Point(519838.365,7027425.6370002795,88.551), Point(519825.549,7027457.702,88.63899881032035)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.State, FrozenLinkInterface,107.579,19180,163404,8,reversed = false,None,1652179948783L,54809,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart2,Track.Combined,Discontinuity.Continuous,AddrMRange(2613,2624),AddrMRange(2613,2624),None,None,Some("test"),"938b993f-6161-4b15-8fea-765f4eb37f79:1", 0.0,10.46,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(519825.549,7027457.702,88.639), Point(519821.641,7027467.4040002795,88.672)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.State, FrozenLinkInterface,10.46,19180,163405,8,reversed = false,None,1652179948783L,54809,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart2,Track.Combined,Discontinuity.Continuous,AddrMRange(2624,2695),AddrMRange(2624,2695),None,None,Some("test"),"f3b2e7a4-5343-4fb6-8c5d-2d20a96e3e68:1", 0.0,71.16,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(519821.641,7027467.404,88.672), Point(519813.9090000002,7027486.578000279,88.71), Point(519801.581,7027516.147000279,88.798), Point(519794.658,7027533.249,88.78300031755902)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.State, FrozenLinkInterface,71.16,19180,163406,8,reversed = false,None,1652179948783L,54809,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart2,Track.Combined,Discontinuity.Continuous,AddrMRange(2695,2867),AddrMRange(2695,2867),None,None,Some("test"),"da1a294d-19c9-4986-ad9b-38b562b5b7dd:1", 0.0,171.501,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(519794.658,7027533.249,88.783), Point(519787.4950000002,7027549.702000278,88.827), Point(519778.0930000002,7027571.862000278,88.843), Point(519766.677,7027598.947000277,88.934), Point(519755.0930000002,7027627.320000281,88.979), Point(519729.485,7027691.871000279,89.156)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.State, FrozenLinkInterface,171.501,19180,163407,8,reversed = false,None,1652179948783L,54809,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart2,Track.Combined,Discontinuity.Continuous,AddrMRange(2867,2911),AddrMRange(2867,2911),None,None,Some("test"),"48e3633f-bd9d-49e6-a1cd-555e2ea8040a:1", 0.0,43.96,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(519729.485,7027691.871,89.156), Point(519723.076,7027708.065000279,89.168), Point(519715.317,7027726.3530002795,89.193), Point(519712.602,7027732.454000278,89.202)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.State, FrozenLinkInterface,43.96,19180,163408,8,reversed = false,None,1652179948783L,54809,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart2,Track.Combined,Discontinuity.Continuous,AddrMRange(2911,3344),AddrMRange(2911,3344),None,None,Some("test"),"28cd5b29-afc0-40d5-bf5b-ba27316b22de:1", 0.0,431.878,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(519712.602,7027732.454,89.202), Point(519680.5050000002,7027808.511000278,89.408), Point(519651.5270000002,7027877.417000279,89.564), Point(519623.9390000002,7027944.29200028,89.663), Point(519596.6340000002,7028009.746000279,89.843), Point(519574.905,7028056.212000279,89.951), Point(519553.414,7028103.193000278,90.391), Point(519541.575,7028128.956,90.73899532268088)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.State, FrozenLinkInterface,431.878,19180,163409,8,reversed = false,None,1652179948783L,54809,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart2,Track.Combined,Discontinuity.Continuous,AddrMRange(3344,3418),AddrMRange(3344,3418),None,None,Some("test"),"c3941d24-5369-4387-b997-1e63f03273c7:1", 0.0,74.192,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(519541.575,7028128.956,90.739), Point(519526.762,7028158.583000279,91.263), Point(519512.43,7028186.159000279,91.812), Point(519507.039,7028194.57,91.89499872466686)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.State, FrozenLinkInterface,74.192,19180,163410,8,reversed = false,None,1652179948783L,54809,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart2,Track.Combined,Discontinuity.Continuous,AddrMRange(3418,3442),AddrMRange(3418,3442),None,None,Some("test"),"e0ecacea-1ad3-4e66-82bb-9a98eedd8ad6:1", 0.0,24.416,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(519507.039,7028194.57,91.895), Point(519495.406,7028216.037,92.49799103726562)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.State, FrozenLinkInterface,24.416,19180,163411,8,reversed = false,None,1652179948783L,54809,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart2,Track.Combined,Discontinuity.Continuous,AddrMRange(3442,3597),AddrMRange(3442,3597),None,None,Some("test"),"7ee2e2eb-bdb2-41aa-89fe-e1753b0a00b9:1", 0.0,154.143,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(519495.406,7028216.037,92.498), Point(519481.442,7028244.1360002775,93.19), Point(519468.447,7028269.126000277,93.781), Point(519452.953,7028294.809000279,94.362), Point(519439.356,7028318.093000279,94.907), Point(519418.6080000002,7028349.499000279,95.629)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.State, FrozenLinkInterface,154.143,19180,163412,8,reversed = false,None,1652179948783L,54809,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart2,Track.Combined,Discontinuity.Continuous,AddrMRange(3597,3658),AddrMRange(3597,3658),None,None,Some("test"),"8b77e807-0261-4df5-8414-c7b83506be6f:1", 0.0,61.395,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(519418.608,7028349.499,95.629), Point(519407.996,7028367.4670002805,96.007), Point(519395.97,7028386.387000281,96.475), Point(519386.6800000002,7028401.931000278,96.843)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.State, FrozenLinkInterface,61.395,19180,163413,8,reversed = false,None,1652179948783L,54809,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart2,Track.Combined,Discontinuity.Continuous,AddrMRange(3658,4294),AddrMRange(3658,4294),None,None,Some("test"),"8e6890c3-d20e-46f9-b432-78dbe4628bd6:1", 0.0,633.976,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(519386.68,7028401.931,96.843), Point(519336.899,7028480.930000278,98.715), Point(519304.2660000002,7028533.815000279,99.92), Point(519276.716,7028579.499000279,100.965), Point(519249.629,7028625.816000277,101.995), Point(519232.1720000002,7028657.096000279,102.72), Point(519214.714,7028688.37700028,103.378), Point(519200.5520000002,7028714.94400028,103.88), Point(519176.5610000002,7028761.925000278,104.64), Point(519162.067,7028793.91200028,105.041), Point(519142.575,7028832.89600028,105.498), Point(519117.115,7028886.850000277,105.919), Point(519098.729,7028929.717000278,106.093), Point(519086.926,7028959.401000278,106.096)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.State, FrozenLinkInterface,633.976,19180,163414,8,reversed = false,None,1652179948783L,54809,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart2,Track.Combined,Discontinuity.Continuous,AddrMRange(4294,4513),AddrMRange(4294,4513),None,None,Some("test"),"16be38e1-3e65-43bd-95e5-f492504a8ff4:1", 0.0,218.94,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(519086.926,7028959.401,106.096), Point(519069.941,7029002.19500028,106.108), Point(519050.389,7029052.918000279,106.077), Point(519030.63,7029114.63700028,105.923), Point(519014.016,7029165.737,105.87700004895365)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.State, FrozenLinkInterface,218.94,19180,163415,8,reversed = false,None,1652179948783L,54809,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart2,Track.Combined,Discontinuity.Continuous,AddrMRange(4513,4570),AddrMRange(4513,4570),None,None,Some("test"),"e01b1d46-0280-40eb-a5b3-958b0a3a41dd:1", 0.0,56.924,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(519014.016,7029165.737,105.877), Point(519006.9230000002,7029187.54800028,105.869), Point(518996.895,7029220.024,104.64901223180107)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.State, FrozenLinkInterface,56.924,19180,163416,8,reversed = false,None,1652179948783L,54809,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart2,Track.Combined,Discontinuity.Continuous,AddrMRange(4570,4576),AddrMRange(4570,4576),None,None,Some("test"),"440768aa-2d66-43f6-87da-ad430c2824cc:1", 0.0,6.171,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(518996.895,7029220.024,104.649), Point(518995.0750000002,7029225.920000279,105.097)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.State, FrozenLinkInterface,6.171,19180,163417,8,reversed = false,None,1652179948783L,54809,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart2,Track.Combined,Discontinuity.Continuous,AddrMRange(4576,4683),AddrMRange(4576,4683),None,None,Some("test"),"9093782b-0434-4f24-9082-7b122263580b:1", 0.0,107.05,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(518995.075,7029225.92,105.097), Point(518992.4710000002,7029234.471000278,105.821), Point(518984.642,7029264.54900028,105.776), Point(518975.4780000002,7029298.29000028,105.738), Point(518967.4950000002,7029329.348000281,105.59)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.State, FrozenLinkInterface,107.05,19180,163418,8,reversed = false,None,1652179948783L,54809,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart2,Track.Combined,Discontinuity.Continuous,AddrMRange(4683,4716),AddrMRange(4683,4716),None,None,Some("test"),"7231a9a4-8137-498c-91ac-c3f02148913b:1", 0.0,32.534,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(518967.495,7029329.348,105.59), Point(518963.202,7029347.213000281,105.506), Point(518960.19,7029361.049000278,105.403)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.State, FrozenLinkInterface,32.534,19180,163419,8,reversed = false,None,1652179948783L,54809,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart2,Track.Combined,Discontinuity.Continuous,AddrMRange(4716,4730),AddrMRange(4716,4730),None,None,Some("test"),"28cd30d3-2f7d-4bad-99b2-6b127bef077c:1", 0.0,13.584,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(518960.19,7029361.049,105.403), Point(518957.112,7029374.28,105.32500177298671)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.State, FrozenLinkInterface,13.584,19180,163420,8,reversed = false,None,1652179948783L,54809,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart2,Track.Combined,Discontinuity.Continuous,AddrMRange(4730,5000),AddrMRange(4730,5000),None,None,Some("test"),"073a89dd-de6d-450a-89e5-80632c9356de:1", 0.0,270.235,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(518957.112,7029374.28,105.325), Point(518954.758,7029384.37300028,105.269), Point(518951.7830000002,7029396.850000278,105.196), Point(518947.641,7029414.814000279,105.085), Point(518940.5220000002,7029444.36000028,104.915), Point(518932.502,7029475.5690002795,104.725), Point(518924.937,7029507.912000279,104.495), Point(518907.635,7029575.00500028,103.909), Point(518894.554,7029629.249000278,103.371), Point(518892.71,7029636.716000281,103.317)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.State, FrozenLinkInterface,270.235,19180,163421,8,reversed = false,None,1652179948783L,54809,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart2,Track.Combined,Discontinuity.Continuous,AddrMRange(5000,5182),AddrMRange(5000,5182),None,None,Some("test"),"9a345526-c2c5-4e2b-926e-e12b89a50175:1", 0.0,181.227,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(518892.71,7029636.716,103.317), Point(518864.3990000002,7029751.379000278,102.154), Point(518849.394,7029812.690000281,101.706)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.State, FrozenLinkInterface,181.227,19180,163422,8,reversed = false,None,1652179948783L,54809,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart2,Track.Combined,Discontinuity.Continuous,AddrMRange(5182,5306),AddrMRange(5182,5306),None,None,Some("test"),"4fb9e3ae-1f36-479f-b676-33cd79cd4b1d:1", 0.0,124.223,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(518849.394,7029812.69,101.706), Point(518849.23,7029813.361000277,101.692), Point(518834.7880000002,7029871.943000278,101.215), Point(518819.5120000002,7029931.908000279,100.785), Point(518819.1950000002,7029933.185000278,100.761)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.State, FrozenLinkInterface,124.223,19180,163423,8,reversed = false,None,1652179948783L,54809,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart2,Track.Combined,Discontinuity.Continuous,AddrMRange(5306,5546),AddrMRange(5306,5546),None,None,Some("test"),"8b91ca65-f805-4767-a95d-d7c108a547cf:1", 0.0,239.625,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(518819.195,7029933.185,100.761), Point(518808.612,7029975.954000279,100.344), Point(518796.8630000002,7030026.16500028,99.707), Point(518784.898,7030073.884000279,99.411), Point(518771.701,7030125.906000279,99.986), Point(518770.982,7030128.740000281,100.0), Point(518762.377,7030165.967000279,100.898)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.State, FrozenLinkInterface,239.625,19180,163424,8,reversed = false,None,1652179948783L,54809,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart2,Track.Combined,Discontinuity.Continuous,AddrMRange(5546,5754),AddrMRange(5546,5754),None,None,Some("test"),"e14a6af8-536a-4d31-aa38-e0fe01be7347:1", 0.0,207.012,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(518762.377,7030165.967,100.898), Point(518757.356,7030186.86500028,101.456), Point(518743.6460000002,7030242.743000279,102.441), Point(518727.665,7030305.878000279,102.913), Point(518712.9750000002,7030366.99500028,103.227)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.State, FrozenLinkInterface,207.012,19180,163425,8,reversed = false,None,1652179948783L,54809,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart2,Track.Combined,Discontinuity.Continuous,AddrMRange(5754,6204),AddrMRange(5754,6204),None,None,Some("test"),"97be4fd2-1ecb-409e-b949-88228fd48049:1", 0.0,449.361,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(518712.975,7030366.995,103.227), Point(518703.3600000002,7030406.605000279,103.476), Point(518691.203,7030456.23500028,103.93), Point(518679.2830000002,7030502.727000279,105.086), Point(518678.923,7030504.288000279,105.157), Point(518670.6750000002,7030540.057000279,106.319), Point(518670.506,7030540.692000279,106.354), Point(518663.123,7030568.391000279,107.239), Point(518653.38,7030600.902000278,108.165), Point(518650.5050000002,7030609.648000279,108.403), Point(518644.713,7030627.270000279,108.815), Point(518636.654,7030649.91900028,109.258), Point(518626.2010000002,7030674.874000278,109.528), Point(518615.024,7030700.141000279,109.592), Point(518601.6070000002,7030726.959000278,109.585), Point(518585.2880000002,7030755.801000278,109.188), Point(518569.32,7030783.442000281,108.683), Point(518565.806,7030788.8,108.5480016969678)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.State, FrozenLinkInterface,449.361,19180,163426,8,reversed = false,None,1652179948783L,54809,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart2,Track.Combined,Discontinuity.Continuous,AddrMRange(6204,6303),AddrMRange(6204,6303),None,None,Some("test"),"3f8c5837-b2a2-4f34-9c1e-8a47a84d5538:1", 0.0,98.744,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(518565.806,7030788.8,108.548), Point(518553.5760000002,7030807.326000281,108.191), Point(518536.126,7030832.041000282,107.58), Point(518535.3190000002,7030833.133000281,107.545), Point(518523.6310000002,7030848.92100028,107.211), Point(518508.586,7030868.517000279,106.815), Point(518508.217,7030868.97,106.81000264987483)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.State, FrozenLinkInterface,98.744,19180,163427,8,reversed = false,None,1652179948783L,54809,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart2,Track.Combined,Discontinuity.Continuous,AddrMRange(6303,6359),AddrMRange(6303,6359),None,None,Some("test"),"eafe4318-fe63-45f4-9b0c-3fb22c402d33:1", 0.0,56.131,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(518508.217,7030868.97,106.81), Point(518489.5690000002,7030889.253000279,106.296), Point(518470.142,7030910.213,105.90800173200554)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.State, FrozenLinkInterface,56.131,19180,163428,8,reversed = false,None,1652179948783L,54809,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart2,Track.Combined,Discontinuity.Continuous,AddrMRange(6359,6643),AddrMRange(6359,6643),None,None,Some("test"),"ebe17118-9b19-48fd-a6b9-01c3ef48cce9:1", 0.0,283.552,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(518470.142,7030910.213,105.908), Point(518464.044,7030916.786000278,105.841), Point(518434.6300000002,7030944.62100028,105.688), Point(518413.425,7030963.71300028,105.601), Point(518407.132,7030969.05500028,105.566), Point(518389.452,7030983.453000278,105.489), Point(518358.872,7031006.775000281,105.308), Point(518336.154,7031022.47300028,105.293), Point(518304.848,7031042.24900028,105.313), Point(518271.884,7031061.628000279,105.332), Point(518243.8420000002,7031078.104000279,105.33), Point(518243.282,7031078.407,105.33399802192335)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.State, FrozenLinkInterface,283.552,19180,163429,8,reversed = false,None,1652179948783L,54809,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart2,Track.Combined,Discontinuity.Continuous,AddrMRange(6643,6664),AddrMRange(6643,6664),None,None,Some("test"),"a193b050-f164-4a77-a6f4-0531c37c9ec0:1", 0.0,20.933,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(518243.282,7031078.407,105.334), Point(518225.611,7031087.540000279,105.337), Point(518224.6900000002,7031088.026000281,105.341)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.State, FrozenLinkInterface,20.933,19180,163430,8,reversed = false,None,1652179948783L,54809,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart2,Track.Combined,Discontinuity.Continuous,AddrMRange(6664,6914),AddrMRange(6664,6914),None,None,Some("test"),"81242218-7ac7-4151-8097-3e107b7ad021:1", 0.0,249.4,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(518224.69,7031088.026,105.341), Point(518163.787,7031122.309000281,105.39), Point(518089.334,7031164.532000279,105.175), Point(518008.9810000002,7031209.402000278,104.925), Point(518007.336,7031210.326,104.92300037190256)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.State, FrozenLinkInterface,249.4,19180,163431,8,reversed = false,None,1652179948783L,54809,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart2,Track.Combined,Discontinuity.Continuous,AddrMRange(6914,7292),AddrMRange(6914,7292),None,None,Some("test"),"79f2c6a1-4d68-41ad-bf95-62e0ead9c027:1", 0.0,377.043,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(518007.336,7031210.326,104.923), Point(517873.1560000002,7031285.744000278,104.938), Point(517850.379,7031298.593000282,104.988), Point(517812.3030000002,7031320.07100028,105.072), Point(517736.2900000002,7031362.4440002795,105.286), Point(517680.486,7031394.076000281,104.877), Point(517678.659,7031395.074000279,104.883)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.State, FrozenLinkInterface,377.043,19180,163432,8,reversed = false,None,1652179948783L,54809,roadName,None,None,None,None)
      )

      linearLocationDAO.create(linearLocations)
      roadwayDAO.create(roadways)
      projectDAO.create(Project(projectId, ProjectState.Incomplete, "test", "test", DateTime.now(),"test", DateTime.now(), DateTime.now(),"",Seq(ProjectReservedPart(Sequences.nextViitePrimaryKeySeqValue,roadPart1), ProjectReservedPart(Sequences.nextViitePrimaryKeySeqValue,roadPart2)),Seq(ProjectReservedPart(Sequences.nextViitePrimaryKeySeqValue,roadPart1), ProjectReservedPart(Sequences.nextViitePrimaryKeySeqValue,roadPart2)),None,None,Set(14)))
      projectReservedPartDAO.reserveRoadPart(projectId, roadPart1, "test")
      projectReservedPartDAO.reserveRoadPart(projectId, roadPart2, "test")
      projectLinkDAO.create(projectLinks)
      projectService.recalculateProjectLinks(projectId, "test", Set(roadPart1, roadPart2))

      val recalculated = projectLinkDAO.fetchProjectLinks(projectId)
      recalculated.size should be (80)
      val (roadPart1Links, roadPart2Links) = recalculated.partition(_.roadPart == roadPart1)
      val roadPart1LeftSideLinks = roadPart1Links.filter(_.track == Track.LeftSide)
      val roadPart1RightSideLinks = roadPart1Links.filter(_.track == Track.RightSide)
      roadPart1LeftSideLinks.map(_.addrMRange.end).max should be (6008)
      roadPart1RightSideLinks.map(_.addrMRange.end).max should be (6008)
      val roadPart2LeftSideLinks = roadPart2Links.filter(_.track == Track.LeftSide)
      val roadPart2RightSideLinks = roadPart2Links.filter(_.track == Track.RightSide)
      roadPart2LeftSideLinks.map(_.addrMRange.end).max should be (160)
      roadPart2RightSideLinks.map(_.addrMRange.end).max should be (160)
      roadPart2Links.map(_.addrMRange.end).max should be (7292)
    }
  }

  def toRoadwayAndLinearLocation(p: ProjectLink):(LinearLocation, Roadway) = {
    val startDate = p.startDate.getOrElse(DateTime.now()).minusDays(1)

    (LinearLocation(-1000, 1, p.linkId, p.startMValue, p.endMValue, p.sideCode, p.linkGeometryTimeStamp,
      (CalibrationPointsUtils.toCalibrationPointReference(p.startCalibrationPoint),
        CalibrationPointsUtils.toCalibrationPointReference(p.endCalibrationPoint)),
      p.geometry, p.linkGeomSource,
      p.roadwayNumber, Some(startDate), p.endDate),
      Roadway(-1000, p.roadwayNumber, p.roadPart, p.administrativeClass, p.track, p.discontinuity, p.addrMRange, p.reversed, startDate, p.endDate,
        p.createdBy.getOrElse("-"), p.roadName, p.ely, TerminationCode.NoTermination, DateTime.now(), None))
  }

  private def addProjectLinksToProject(roadAddressChangeType: RoadAddressChangeType, addrM: Seq[Long], changeTrack: Boolean = false, roadPart: RoadPart = RoadPart(19999, 1),
                                       discontinuity: Discontinuity = Discontinuity.Continuous, ely: Long = 8L, roadwayId: Long = 0L,
                                       lastLinkDiscontinuity: Discontinuity = Discontinuity.Continuous, project: Project, roadwayNumber: Long = 0L, withRoadInfo: Boolean = false): Project = {

    def withTrack(t: Track): Seq[ProjectLink] = {
      addrM.init.zip(addrM.tail).map { case (st, en) =>
        projectLink(AddrMRange(st, en), t, project.id, roadAddressChangeType, roadPart, discontinuity, ely, roadwayId)
      }
    }

    val links =
      if (changeTrack) {
        withTrack(Track.RightSide) ++ withTrack(Track.LeftSide)
      } else {
        withTrack(Track.Combined)
      }
    if(projectReservedPartDAO.fetchReservedRoadPart(roadPart).isEmpty)
      projectReservedPartDAO.reserveRoadPart(project.id, roadPart, "u")
    val newLinks = links.dropRight(1) ++ Seq(links.last.copy(discontinuity = lastLinkDiscontinuity))
    val newLinksWithRoadwayInfo = if(withRoadInfo){
      val (ll, rw) = newLinks.map(_.copy(roadwayNumber = roadwayNumber)).map(toRoadwayAndLinearLocation).unzip
      linearLocationDAO.create(ll)
      roadwayDAO.create(rw)
      val roadways = newLinks.map(p => (p.roadPart)).distinct.flatMap(p => roadwayDAO.fetchAllByRoadPart(p))
      newLinks.map(nl => {
        val roadway = roadways.find(r => r.roadPart == nl.roadPart && r.addrMRange.start == nl.addrMRange.start && r.addrMRange.end == nl.addrMRange.end)
        if (roadway.nonEmpty) {
          nl.copy(roadwayId = roadway.get.id, roadwayNumber = roadway.get.roadwayNumber)
        }
        else nl
      })
    } else {
      newLinks
    }
    projectLinkDAO.create(newLinksWithRoadwayInfo)
    project
  }

  private def projectLink(addrMRange: AddrMRange, track: Track, projectId: Long, status: RoadAddressChangeType = RoadAddressChangeType.NotHandled,
                          roadPart: RoadPart = RoadPart(19999, 1), discontinuity: Discontinuity = Discontinuity.Continuous, ely: Long = 8L, roadwayId: Long = 0L, linearLocationId: Long = 0L) = {
    val startDate = if (status !== RoadAddressChangeType.New) Some(DateTime.now()) else None
    ProjectLink(NewIdValue, roadPart, track, discontinuity, addrMRange, addrMRange, startDate, None, Some("User"), addrMRange.start.toString, 0.0, (addrMRange.end - addrMRange.start).toDouble, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), Seq(Point(0.0, addrMRange.start), Point(0.0, addrMRange.end)), projectId, status, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, (addrMRange.end - addrMRange.start).toDouble, roadwayId, linearLocationId, ely, reversed = false, None, 0L)
  }
}

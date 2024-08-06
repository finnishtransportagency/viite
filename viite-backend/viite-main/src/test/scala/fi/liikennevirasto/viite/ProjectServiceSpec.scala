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
  test("Test projectService.recalculateProjectLinks() Real life project on road part 110/1, recalculation should complete successfully") {
    /**
     * There is more info of the project available on the Jira ticket VIITE-3177
     */

    runWithRollback {
      val roadPart = RoadPart(49529,1) // random road part
      val projectId = Sequences.nextViiteProjectId
      val roadName = Some("testRoad")
      
      val roadways = Seq(
        Roadway(65784,90509805,roadPart,AdministrativeClass.Municipality,Track.Combined,Discontinuity.Continuous,AddrMRange(1800, 3940),false,DateTime.now().minusDays(2),None,"test",None,14,TerminationCode.NoTermination,DateTime.now().minusDays(2),None),
        Roadway(65512,90509888,roadPart,AdministrativeClass.Municipality,Track.RightSide,Discontinuity.Continuous,AddrMRange(3940, 4960),false,DateTime.now().minusDays(2),None,"test",None,14,TerminationCode.NoTermination,DateTime.now().minusDays(2),None),
        Roadway(65618,90510413,roadPart,AdministrativeClass.Municipality,Track.LeftSide,Discontinuity.Continuous,AddrMRange(3940, 4960),false,DateTime.now().minusDays(2),None,"test",None,14,TerminationCode.NoTermination,DateTime.now().minusDays(2),None),
        Roadway(75083,148600148,roadPart,AdministrativeClass.Municipality,Track.RightSide,Discontinuity.MinorDiscontinuity,AddrMRange(0, 1800),false,DateTime.now().minusDays(2),None,"test",None,14,TerminationCode.NoTermination,DateTime.now().minusDays(2),None),
        Roadway(75344,148694049,roadPart,AdministrativeClass.Municipality,Track.LeftSide,Discontinuity.MinorDiscontinuity,AddrMRange(0, 1800),false,DateTime.now().minusDays(2),None,"test",None,14,TerminationCode.NoTermination,DateTime.now().minusDays(2),None)
      )

      val linearLocations = Seq(
        LinearLocation(418441, 12.0,"115f3ee4-a61b-4707-961d-163844c836d5:1",0.0,17.915,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(378901.539,6677890.667,0.0), Point(378884.133,6677894.908,0.0)),FrozenLinkInterface,90510413,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(418430, 1.0,"15200976-e270-4533-b6fb-3ce8c81ef5d6:1",0.0,115.526,SideCode.AgainstDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(379607.786,6677831.772,0.0), Point(379715.908,6677871.82,0.0)),FrozenLinkInterface,90510413,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(443473, 6.0,"524d6d96-a880-4665-b2a9-175d6ff43c34:1",0.0,34.629,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(381615.191,6678142.628,0.0), Point(381626.521,6678175.351,0.0)),FrozenLinkInterface,148600148,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(418881, 8.0,"fe8b0cd6-e62e-4806-9365-e6bac21e0fbc:1",0.0,42.62,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(379193.327,6677855.063,0.0), Point(379150.859,6677858.631,0.0)),FrozenLinkInterface,90509888,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(440367, 6.0,"72f4f661-a83e-4c6d-8d61-ef5e015bce5d:1",0.0,38.656,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(381615.564,6678180.111,0.0), Point(381623.597,6678217.759,0.0)),FrozenLinkInterface,148694049,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(418517, 18.0,"fff4acae-4f85-4755-8e6d-a9eb72531b4b:1",0.0,10.957,SideCode.AgainstDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(380278.804,6677951.588,0.0), Point(380289.592,6677953.504,0.0)),FrozenLinkInterface,90509805,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(418504, 5.0,"eda9af9e-1e85-4e9e-a721-1f7f35aa256e:1",0.0,120.098,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(381396.734,6678305.23,0.0), Point(381281.915,6678340.431,0.0)),FrozenLinkInterface,90509805,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(418885, 12.0,"3e17fa70-8656-4de1-839f-3c9f1027e5e1:1",0.0,30.116,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(378934.802,6677896.475,0.0), Point(378905.455,6677903.225,0.0)),FrozenLinkInterface,90509888,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(418442, 13.0,"b62597f8-fdd3-4f1c-ac55-b2a343605e8e:1",0.0,32.093,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(378884.133,6677894.908,0.0), Point(378852.601,6677900.88,0.0)),FrozenLinkInterface,90510413,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(418500, 1.0,"4aabed3b-c8b5-4dbf-bd2d-e423c0379b6d:1",0.0,89.152,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(381619.214,6678234.151,0.0), Point(381534.334,6678261.416,0.0)),FrozenLinkInterface,90509805,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(418886, 13.0,"6fcc7a63-8a30-4c9c-8616-e9444e33998f:1",0.0,27.759,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(378905.455,6677903.225,0.0), Point(378878.416,6677909.482,0.0)),FrozenLinkInterface,90509888,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(418436, 7.0,"04c4dfef-0af3-4843-b8fe-d249759a6200:1",0.0,18.501,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(379223.865,6677838.378,0.0), Point(379205.546,6677840.967,0.0)),FrozenLinkInterface,90510413,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(418880, 7.0,"ebc93de6-5bd1-447e-a6ef-9036113f0fce:1",0.0,13.133,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(379206.426,6677854.113,0.0), Point(379193.327,6677855.063,0.0)),FrozenLinkInterface,90509888,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(418505, 6.0,"bcdd3b13-fff4-4d02-b657-a1d38d343f9f:1",0.0,29.56,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(381281.915,6678340.431,0.0), Point(381253.919,6678349.92,0.0)),FrozenLinkInterface,90509805,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(440364, 3.0,"d1aa75ff-14ca-4b22-822b-a445ee3d3a1d:1",0.0,1461.494,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(382618.795,6677102.392,0.0), Point(381549.987,6677992.937,0.0)),FrozenLinkInterface,148694049,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(418440, 11.0,"53280c47-a344-4184-8c83-adc55c6d8d52:1",0.0,102.098,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(379000.678,6677866.264,0.0), Point(378901.539,6677890.667,0.0)),FrozenLinkInterface,90510413,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(418876, 3.0,"6dc68172-2641-43c7-9409-c978f3df1f82:1",0.0,214.351,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(379587.619,6677837.517,0.0), Point(379374.062,6677840.456,0.0)),FrozenLinkInterface,90509888,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(418509, 10.0,"cacda57d-9130-4436-abc8-b68e0f15c3dc:1",0.0,10.144,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(381059.11,6678396.684,0.0), Point(381049.01,6678397.629,0.0)),FrozenLinkInterface,90509805,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(418882, 9.0,"e60df406-cc78-4cf1-a872-3da317f66371:1",0.0,92.219,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(379150.859,6677858.631,0.0), Point(379059.11,6677867.792,0.0)),FrozenLinkInterface,90509888,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(440363, 2.0,"777942ca-80e4-4452-b255-46f72aa3f4c7:1",0.0,14.56,SideCode.AgainstDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(382618.795,6677102.392,0.0), Point(382633.355,6677102.392,0.0)),FrozenLinkInterface,148694049,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(418508, 9.0,"62d12731-9c13-4eca-a734-51eab45dc450:1",0.0,59.009,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(381117.412,6678388.17,0.0), Point(381059.11,6678396.684,0.0)),FrozenLinkInterface,90509805,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(418518, 19.0,"60eaef28-4fc7-4f6f-ab94-49df9f69602a:1",0.0,255.068,SideCode.AgainstDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(380024.326,6677935.779,0.0), Point(380278.804,6677951.588,0.0)),FrozenLinkInterface,90509805,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(418883, 10.0,"3ad18227-afa3-424a-9030-5e7a5731b6c5:1",0.0,58.735,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(379059.11,6677867.792,0.0), Point(379002.021,6677881.599,0.0)),FrozenLinkInterface,90509888,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(418437, 8.0,"e413b3ba-23a1-4e19-9216-2af9f4fded49:1",0.0,13.276,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(379205.546,6677840.967,0.0), Point(379192.377,6677842.649,0.0)),FrozenLinkInterface,90510413,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(440366, 5.0,"a145983c-7f11-4c8d-a4a0-bee2f923c2c0:1",0.0,36.139,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(381603.895,6678145.908,0.0), Point(381615.564,6678180.111,0.0)),FrozenLinkInterface,148694049,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(418501, 2.0,"f80c23ce-1eba-40c1-9c57-32e15031ae28:1",0.0,57.642,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(381534.334,6678261.416,0.0), Point(381479.449,6678279.03,0.0)),FrozenLinkInterface,90509805,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(443471, 4.0,"eb65840d-f279-481c-9d8e-79a5fbcb2eff:1",0.0,103.299,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(381559.378,6677989.503,0.0), Point(381595.696,6678086.155,0.0)),FrozenLinkInterface,148600148,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(418874, 1.0,"c31d99c9-d3b2-44e5-aa44-a047e44ce902:1",0.0,114.574,SideCode.AgainstDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(379605.889,6677840.998,0.0), Point(379715.908,6677871.82,0.0)),FrozenLinkInterface,90509888,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(418879, 6.0,"b1235380-a215-4491-a6ab-7eca41f402b9:1",0.0,20.205,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(379226.51,6677851.902,0.0), Point(379206.426,6677854.113,0.0)),FrozenLinkInterface,90509888,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(418875, 2.0,"78af4d4e-a7f2-44e3-896b-909c5fd37bca:1",0.0,18.599,SideCode.AgainstDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(379587.619,6677837.517,0.0), Point(379605.889,6677840.998,0.0)),FrozenLinkInterface,90509888,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(418512, 13.0,"0f3f961c-22db-4a05-a615-0b2c36468cef:1",0.0,57.57,SideCode.AgainstDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(380809.276,6678336.175,0.0), Point(380854.368,6678371.961,0.0)),FrozenLinkInterface,90509805,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(418514, 15.0,"df57fa88-27bb-4bbc-8443-b794defaf66e:1",0.0,331.582,SideCode.AgainstDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(380359.659,6677970.062,0.0), Point(380628.055,6678154.748,0.0)),FrozenLinkInterface,90509805,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(418502, 3.0,"f4cad4c3-f8dd-49e3-aad1-9ad3f3f68c2b:1",0.0,22.501,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(381479.449,6678279.03,0.0), Point(381458.024,6678285.905,0.0)),FrozenLinkInterface,90509805,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(418887, 14.0,"33f3f362-5926-4516-b788-2dfcc54a7962:1",0.0,25.319,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(378878.416,6677909.482,0.0), Point(378853.646,6677914.728,0.0)),FrozenLinkInterface,90509888,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(418519, 20.0,"4c43f260-90c2-4490-8a28-10f8860fef3f:1",0.0,19.868,SideCode.AgainstDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(380004.483,6677934.779,0.0), Point(380024.326,6677935.779,0.0)),FrozenLinkInterface,90509805,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(418884, 11.0,"e8e3d332-2498-4e73-b8d0-6f22628ebeb6:1",0.0,68.845,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(379002.021,6677881.599,0.0), Point(378934.802,6677896.475,0.0)),FrozenLinkInterface,90509888,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(440365, 4.0,"758d151d-7d16-4436-a87f-dd2a0918feac:1",0.0,162.22,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(381549.987,6677992.937,0.0), Point(381603.895,6678145.908,0.0)),FrozenLinkInterface,148694049,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(418515, 16.0,"7509b2a2-a7b6-43da-8d98-f5b4f36e265c:1",0.0,23.705,SideCode.AgainstDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(380336.895,6677963.451,0.0), Point(380359.659,6677970.062,0.0)),FrozenLinkInterface,90509805,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(418432, 3.0,"561fc10c-4b54-4e7d-9cc7-a52351868c8b:1",0.0,185.748,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(379588.567,6677827.89,0.0), Point(379403.396,6677830.418,0.0)),FrozenLinkInterface,90510413,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(443469, 2.0,"bce3456c-0791-46c4-b1ea-683fc4114c9f:1",0.0,13.9,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(382634.915,6677115.653,0.0), Point(382621.135,6677117.472,0.0)),FrozenLinkInterface,148600148,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(443468, 1.0,"0076d05b-abd9-47ed-a745-9dd96a835ea3:1",0.0,97.865,SideCode.AgainstDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(382634.915,6677115.653,0.0), Point(382727.735,6677141.652,0.0)),FrozenLinkInterface,148600148,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(418438, 9.0,"b25844ce-d250-4d39-bf95-b0276dafe826:1",0.0,133.513,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(379192.377,6677842.649,0.0), Point(379059.34,6677853.77,0.0)),FrozenLinkInterface,90510413,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(418511, 12.0,"5769ba0b-be36-47c5-bd84-63d469212a6c:1",0.0,20.01,SideCode.AgainstDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(380854.368,6678371.961,0.0), Point(380870.831,6678383.33,0.0)),FrozenLinkInterface,90509805,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(418433, 4.0,"ca116376-62c3-48d7-8264-355a093b14bf:1",0.0,29.574,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(379403.396,6677830.418,0.0), Point(379373.879,6677832.255,0.0)),FrozenLinkInterface,90510413,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(440362, 1.0,"0f81aa70-571c-4fbb-a860-223f381e516e:1",0.0,79.473,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(382704.709,6677078.547,0.0), Point(382633.355,6677102.392,0.0)),FrozenLinkInterface,148694049,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(443472, 5.0,"225866de-b3f3-45fb-a7ca-c73d5e6a04fa:1",0.0,59.746,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(381595.696,6678086.155,0.0), Point(381615.191,6678142.628,0.0)),FrozenLinkInterface,148600148,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(418434, 5.0,"9a8f1573-b95c-46cb-a05c-06c39d3329af:1",0.0,17.976,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(379373.879,6677832.255,0.0), Point(379355.911,6677832.794,0.0)),FrozenLinkInterface,90510413,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(418878, 5.0,"85436c48-78cd-49b7-83a6-8e2c94be9a6e:1",0.0,130.037,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(379356.16,6677841.883,0.0), Point(379226.51,6677851.902,0.0)),FrozenLinkInterface,90509888,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(418888, 15.0,"4720d3d2-1b9e-4c99-aa23-7368e4b62558:1",0.0,141.188,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(378853.646,6677914.728,0.0), Point(378713.913,6677934.299,0.0)),FrozenLinkInterface,90509888,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(443474, 7.0,"edac1707-e255-4fe0-a332-66ca0c5e779d:1",0.0,44.41,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(381626.521,6678175.351,0.0), Point(381639.582,6678217.793,0.0)),FrozenLinkInterface,148600148,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(418503, 4.0,"5f4946f2-4565-46b8-8c21-2f0460c5f8ea:1",0.0,64.264,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(381458.024,6678285.905,0.0), Point(381396.734,6678305.23,0.0)),FrozenLinkInterface,90509805,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(418443, 14.0,"e6298d9b-1e7d-4f90-89d6-3347f0ed11da:1",0.0,142.063,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(378852.601,6677900.88,0.0), Point(378711.909,6677920.398,0.0)),FrozenLinkInterface,90510413,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(418513, 14.0,"9cbd5766-e785-4087-9700-cf5c942e6f7e:1",0.0,256.682,SideCode.AgainstDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(380628.055,6678154.748,0.0), Point(380809.276,6678336.175,0.0)),FrozenLinkInterface,90509805,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(418506, 7.0,"0243816e-c18c-4531-95a8-8d8dec17d76d:1",0.0,84.045,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(381253.919,6678349.92,0.0), Point(381174.009,6678375.824,0.0)),FrozenLinkInterface,90509805,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(418520, 21.0,"4d42a932-a706-49bd-a12f-f8213982889e:1",0.0,296.868,SideCode.AgainstDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(379715.908,6677871.82,0.0), Point(380004.483,6677934.779,0.0)),FrozenLinkInterface,90509805,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(443470, 3.0,"0681b62b-54b8-4684-a954-7b6c18818cbe:1",0.0,1443.955,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(382621.135,6677117.472,0.0), Point(381559.378,6677989.503,0.0)),FrozenLinkInterface,148600148,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(418431, 2.0,"22371d58-d4a2-4989-9b80-b6b200b2f7d1:1",0.0,19.607,SideCode.AgainstDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(379588.567,6677827.89,0.0), Point(379607.786,6677831.772,0.0)),FrozenLinkInterface,90510413,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(418510, 11.0,"7c13f134-06e5-4d8a-b6ef-c918103738d9:1",0.0,181.486,SideCode.AgainstDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(380870.831,6678383.33,0.0), Point(381049.01,6678397.629,0.0)),FrozenLinkInterface,90509805,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(418516, 17.0,"2c438c73-a547-4f65-82d0-921e153b4939:1",0.0,48.386,SideCode.AgainstDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(380289.592,6677953.504,0.0), Point(380336.895,6677963.451,0.0)),FrozenLinkInterface,90509805,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(418435, 6.0,"7d684b95-fb49-4fcf-a963-25922d72a5b8:1",0.0,132.182,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(379355.911,6677832.794,0.0), Point(379223.865,6677838.378,0.0)),FrozenLinkInterface,90510413,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(418507, 8.0,"0c93c1e2-7074-4be4-b60f-ef2ab63cc286:1",0.0,57.935,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(381174.009,6678375.824,0.0), Point(381117.412,6678388.17,0.0)),FrozenLinkInterface,90509805,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(418439, 10.0,"7ad45753-10ea-4c23-a83c-8cd2072694e5:1",0.0,59.978,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(379059.34,6677853.77,0.0), Point(379000.678,6677866.264,0.0)),FrozenLinkInterface,90510413,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(418877, 4.0,"5288d4ec-ca4b-415c-9d04-3101f114f4fc:1",0.0,17.959,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(379374.062,6677840.456,0.0), Point(379356.16,6677841.883,0.0)),FrozenLinkInterface,90509888,Some(DateTime.now().minusDays(2)),None)
      )

      val projectLinks = Seq(
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.LeftSide,Discontinuity.Continuous,AddrMRange(0,0),AddrMRange(0,0),None,None,Some("test"),"54b87939-37ff-4fae-b075-c661028413cd:1", 0.0,20.337,SideCode.Unknown,(NoCP, NoCP), (NoCP, NoCP),List(Point(382284.6949999696,6677273.729999827,11.151), Point(382267.4369999698,6677284.488999825,11.354)),projectId,  RoadAddressChangeType.New,AdministrativeClass.Municipality, FrozenLinkInterface,20.337,0,0,1,reversed = false,None,1652179948783L,0,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.RightSide,Discontinuity.Continuous,AddrMRange(0,0),AddrMRange(0,0),None,None,Some("test"),"3f83bbfe-eb81-4283-a866-1c870a6b8bed:1", 0.0,109.48,SideCode.Unknown,(NoCP, NoCP), (NoCP, NoCP),List(Point(381904.0539999696,6677584.167999816,13.56), Point(381817.7339999694,6677651.507999815,11.945)),projectId,  RoadAddressChangeType.New,AdministrativeClass.Municipality, FrozenLinkInterface,109.48,0,0,1,reversed = false,None,1652179948783L,0,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.RightSide,Discontinuity.Continuous,AddrMRange(0,0),AddrMRange(0,0),None,None,Some("test"),"66423808-c1ea-4436-a51f-144d800f5c18:1", 0.0,226.234,SideCode.Unknown,(NoCP, NoCP), (NoCP, NoCP),List(Point(381817.7339999694,6677651.507999815,11.945), Point(381810.7139999694,6677656.967999818,11.806), Point(381802.3939999693,6677662.946999813,12.378), Point(381785.3639999695,6677679.3279998135,11.828), Point(381658.7439999694,6677779.945999811,11.986), Point(381650.1629999695,6677786.4469998125,12.219), Point(381641.8429999694,6677793.596999811,11.919)),projectId,  RoadAddressChangeType.New,AdministrativeClass.Municipality, FrozenLinkInterface,226.234,0,0,1,reversed = false,None,1652179948783L,0,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.RightSide,Discontinuity.Continuous,AddrMRange(0,0),AddrMRange(0,0),None,None,Some("test"),"784356c9-6f00-4c51-a42b-5097627ade3b:1", 0.0,82.537,SideCode.Unknown,(NoCP, NoCP), (NoCP, NoCP),List(Point(381641.8429999694,6677793.596999811,11.919), Point(381613.8939999695,6677815.306999811,13.207), Point(381585.8139999692,6677837.926999808,13.544), Point(381577.1449999693,6677844.841999809,13.746)),projectId,  RoadAddressChangeType.New,AdministrativeClass.Municipality, FrozenLinkInterface,82.537,0,0,1,reversed = false,None,1652179948783L,0,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.LeftSide,Discontinuity.Continuous,AddrMRange(0,0),AddrMRange(0,0),None,None,Some("test"),"d8c9795a-6060-42a3-9cb6-139b3698e7ad:1", 0.0,107.291,SideCode.Unknown,(NoCP, NoCP), (NoCP, NoCP),List(Point(381891.0549999693,6677570.387999817,13.72), Point(381806.2939999693,6677636.166999813,12.666)),projectId,  RoadAddressChangeType.New,AdministrativeClass.Municipality, FrozenLinkInterface,107.291,0,0,1,reversed = false,None,1652179948783L,0,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.RightSide,Discontinuity.Continuous,AddrMRange(0,0),AddrMRange(0,0),None,None,Some("test"),"8e3e7b55-a191-4253-9181-033cc7a5fbf6:1", 0.0,164.125,SideCode.Unknown,(NoCP, NoCP), (NoCP, NoCP),List(Point(382621.1349999698,6677117.471999834,17.05), Point(382612.2949999697,6677118.771999832,17.546), Point(382589.4149999698,6677125.011999834,16.92), Point(382566.5349999697,6677130.471999834,16.578), Point(382544.1749999698,6677138.011999833,16.192), Point(382525.9749999696,6677145.81199983,15.923), Point(382501.0149999699,6677157.7719998285,15.643), Point(382469.5549999698,6677177.01099983,15.222)),projectId,  RoadAddressChangeType.New,AdministrativeClass.Municipality, FrozenLinkInterface,164.125,0,0,1,reversed = false,None,1652179948783L,0,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.LeftSide,Discontinuity.Continuous,AddrMRange(0,0),AddrMRange(0,0),None,None,Some("test"),"07173271-d917-4fd3-94d1-cf9c8199f686:1", 0.0,68.332,SideCode.Unknown,(NoCP, NoCP), (NoCP, NoCP),List(Point(382460.7149999698,6677160.890999829,15.502), Point(382447.1949999697,6677170.251999832,15.265), Point(382425.3549999697,6677184.8119998295,14.945), Point(382403.2549999699,6677197.810999827,14.528)),projectId,  RoadAddressChangeType.New,AdministrativeClass.Municipality, FrozenLinkInterface,68.332,0,0,1,reversed = false,None,1652179948783L,0,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.LeftSide,Discontinuity.Continuous,AddrMRange(0,0),AddrMRange(0,0),None,None,Some("test"),"be0c661c-6335-4efc-b0fc-8b616818d709:1", 0.0,140.803,SideCode.Unknown,(NoCP, NoCP), (NoCP, NoCP),List(Point(382403.2549999699,6677197.810999827,14.528), Point(382356.4549999698,6677226.669999828,13.309), Point(382324.4749999696,6677248.249999827,12.069), Point(382284.6949999696,6677273.729999827,11.151)),projectId,  RoadAddressChangeType.New,AdministrativeClass.Municipality, FrozenLinkInterface,140.803,0,0,1,reversed = false,None,1652179948783L,0,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.LeftSide,Discontinuity.Continuous,AddrMRange(0,0),AddrMRange(0,0),None,None,Some("test"),"0eabde27-15ca-46a3-87ee-13b29f211520:1", 0.0,225.879,SideCode.Unknown,(NoCP, NoCP), (NoCP, NoCP),List(Point(381806.2939999693,6677636.166999813,12.666), Point(381791.7349999694,6677649.167999814,12.605), Point(381763.0039999693,6677671.527999814,12.466), Point(381648.2139999693,6677761.486999811,12.277), Point(381638.7099999694,6677768.76499981,12.354), Point(381629.0899999693,6677776.174999811,12.41)),projectId,  RoadAddressChangeType.New,AdministrativeClass.Municipality, FrozenLinkInterface,225.879,0,0,1,reversed = false,None,1652179948783L,0,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.LeftSide,Discontinuity.Continuous,AddrMRange(0,0),AddrMRange(0,0),None,None,Some("test"),"7ee50e81-ca05-406f-b0de-af9b0a84a5c6:1", 0.0,377.963,SideCode.Unknown,(NoCP, NoCP), (NoCP, NoCP),List(Point(382267.4369999698,6677284.488999825,11.354), Point(382264.6749999696,6677286.209999826,11.459), Point(382224.8949999699,6677313.249999824,12.811), Point(382187.7149999696,6677338.989999824,14.054), Point(382154.9549999696,6677363.689999823,14.759), Point(382112.3149999698,6677394.888999821,15.199), Point(382071.4939999696,6677427.388999821,15.119), Point(382013.2549999696,6677473.408999819,14.702), Point(381966.1949999695,6677512.408999819,14.287)),projectId,  RoadAddressChangeType.New,AdministrativeClass.Municipality, FrozenLinkInterface,377.963,0,0,1,reversed = false,None,1652179948783L,0,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.LeftSide,Discontinuity.Continuous,AddrMRange(0,0),AddrMRange(0,0),None,None,Some("test"),"fdba4fb2-d99f-4463-992a-8df0abe4ccf5:1", 0.0,74.186,SideCode.Unknown,(NoCP, NoCP), (NoCP, NoCP),List(Point(381966.1949999695,6677512.408999819,14.287), Point(381908.2139999696,6677558.687999818,13.848)),projectId,  RoadAddressChangeType.New,AdministrativeClass.Municipality, FrozenLinkInterface,74.186,0,0,1,reversed = false,None,1652179948783L,0,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.LeftSide,Discontinuity.Continuous,AddrMRange(0,0),AddrMRange(0,0),None,None,Some("test"),"ca37f590-9473-44bf-8872-53804a12c53c:1", 0.0,20.768,SideCode.Unknown,(NoCP, NoCP), (NoCP, NoCP),List(Point(381908.2139999696,6677558.687999818,13.848), Point(381891.0549999693,6677570.387999817,13.72)),projectId,  RoadAddressChangeType.New,AdministrativeClass.Municipality, FrozenLinkInterface,20.768,0,0,1,reversed = false,None,1652179948783L,0,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.RightSide,Discontinuity.Continuous,AddrMRange(0,0),AddrMRange(0,0),None,None,Some("test"),"2c495e28-4b2b-41dd-b4a8-76727b23c6ae:1", 0.0,146.082,SideCode.Unknown,(NoCP, NoCP), (NoCP, NoCP),List(Point(381565.2729999694,6677853.525999808,14.193), Point(381549.2329999693,6677870.850999808,14.56), Point(381545.6189999692,6677881.664999806,14.061), Point(381542.6149999693,6677895.844999809,14.011), Point(381540.6479999694,6677911.828999808,16.129), Point(381541.1469999695,6677927.692999808,16.936), Point(381544.5489999695,6677946.463999807,16.688), Point(381548.1799999692,6677957.001999809,16.792), Point(381559.3779999693,6677989.502999809,17.804)),projectId,  RoadAddressChangeType.New,AdministrativeClass.Municipality, FrozenLinkInterface,146.082,0,0,1,reversed = false,None,1652179948783L,0,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.LeftSide,Discontinuity.Continuous,AddrMRange(0,0),AddrMRange(0,0),None,None,Some("test"),"80eb2e2b-b1d6-454b-9869-f33940feb653:1", 0.0,43.987,SideCode.Unknown,(NoCP, NoCP), (NoCP, NoCP),List(Point(381533.4079999695,6677952.193999807,17.409), Point(381549.9869999693,6677992.936999809,17.671)),projectId,  RoadAddressChangeType.New,AdministrativeClass.Municipality, FrozenLinkInterface,43.987,0,0,1,reversed = false,None,1652179948783L,0,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.LeftSide,Discontinuity.Continuous,AddrMRange(0,0),AddrMRange(0,0),None,None,Some("test"),"54b53ed5-65f5-4b33-aca4-ec9a365033f2:1", 0.0,128.312,SideCode.Unknown,(NoCP, NoCP), (NoCP, NoCP),List(Point(381550.9609999694,6677834.587999809,14.972), Point(381542.2489999694,6677842.214999806,15.156), Point(381528.9889999692,6677862.494999806,15.929), Point(381522.4889999694,6677886.414999807,16.57), Point(381520.6689999692,6677907.473999808,17.164), Point(381527.6889999694,6677936.853999808,17.334), Point(381533.4079999695,6677952.193999807,17.409)),projectId,  RoadAddressChangeType.New,AdministrativeClass.Municipality, FrozenLinkInterface,128.312,0,0,1,reversed = false,None,1652179948783L,0,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.LeftSide,Discontinuity.Continuous,AddrMRange(0,0),AddrMRange(0,0),None,None,Some("test"),"12156cae-90bb-4ea9-ae08-71f40c7eb2ef:1", 0.0,13.41,SideCode.Unknown,(NoCP, NoCP), (NoCP, NoCP),List(Point(381561.9449999693,6677826.895999808,14.599), Point(381550.9609999694,6677834.587999809,14.972)),projectId,  RoadAddressChangeType.New,AdministrativeClass.Municipality, FrozenLinkInterface,13.41,0,0,1,reversed = false,None,1652179948783L,0,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.LeftSide,Discontinuity.Continuous,AddrMRange(0,0),AddrMRange(0,0),None,None,Some("test"),"5f11df30-9bc0-4b36-913d-9a680c5de8f6:1", 0.0,84.16,SideCode.Unknown,(NoCP, NoCP), (NoCP, NoCP),List(Point(381629.0899999693,6677776.174999811,12.41), Point(381605.6889999693,6677793.594999811,12.756), Point(381566.9489999693,6677823.493999809,14.384), Point(381561.9449999693,6677826.895999808,14.599)),projectId,  RoadAddressChangeType.New,AdministrativeClass.Municipality, FrozenLinkInterface,84.16,0,0,1,reversed = false,None,1652179948783L,0,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.RightSide,Discontinuity.Continuous,AddrMRange(0,0),AddrMRange(0,0),None,None,Some("test"),"84572dbf-4dcb-4308-b1ed-492fe83b2e50:1", 0.0,14.712,SideCode.Unknown,(NoCP, NoCP), (NoCP, NoCP),List(Point(381577.1449999693,6677844.841999809,13.746), Point(381573.3339999692,6677847.806999808,13.598), Point(381565.2729999694,6677853.525999808,14.193)),projectId,  RoadAddressChangeType.New,AdministrativeClass.Municipality, FrozenLinkInterface,14.712,0,0,1,reversed = false,None,1652179948783L,0,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.RightSide,Discontinuity.Continuous,AddrMRange(0,0),AddrMRange(0,0),None,None,Some("test"),"899f0f3d-329d-4bea-bb68-c6ba2bed1712:1", 0.0,143.776,SideCode.Unknown,(NoCP, NoCP), (NoCP, NoCP),List(Point(382415.4749999697,6677215.490999829,14.492), Point(382383.4949999698,6677235.250999829,13.644), Point(382341.3749999696,6677264.370999829,12.407), Point(382296.6549999699,6677296.3499998255,11.288)),projectId,  RoadAddressChangeType.New,AdministrativeClass.Municipality, FrozenLinkInterface,143.776,0,0,1,reversed = false,None,1652179948783L,0,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.RightSide,Discontinuity.Continuous,AddrMRange(0,0),AddrMRange(0,0),None,None,Some("test"),"bd0703c6-8988-4588-a54f-f9bfbc71c5bd:1", 0.0,66.39,SideCode.Unknown,(NoCP, NoCP), (NoCP, NoCP),List(Point(382469.5549999698,6677177.01099983,15.222), Point(382456.8149999698,6677185.330999831,15.08), Point(382434.7149999697,6677201.190999828,14.828), Point(382415.4749999697,6677215.490999829,14.492)),projectId,  RoadAddressChangeType.New,AdministrativeClass.Municipality, FrozenLinkInterface,66.39,0,0,1,reversed = false,None,1652179948783L,0,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.LeftSide,Discontinuity.Continuous,AddrMRange(0,0),AddrMRange(0,0),None,None,Some("test"),"d06a1c2a-bc40-47c4-b99a-4d8661c81cfd:1", 0.0,170.451,SideCode.Unknown,(NoCP, NoCP), (NoCP, NoCP),List(Point(382618.7949999698,6677102.391999834,17.816), Point(382589.6749999699,6677105.511999834,17.566), Point(382564.9749999697,6677111.491999831,17.129), Point(382543.6559999697,6677118.771999831,16.774), Point(382521.0349999697,6677127.35099983,16.57), Point(382501.5359999698,6677137.490999832,16.222), Point(382460.7149999698,6677160.890999829,15.502)),projectId,  RoadAddressChangeType.New,AdministrativeClass.Municipality, FrozenLinkInterface,170.451,0,0,1,reversed = false,None,1652179948783L,0,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.RightSide,Discontinuity.Continuous,AddrMRange(0,0),AddrMRange(0,0),None,None,Some("test"),"9d6d654a-3cf3-40be-8cbd-70840b12224a:1", 0.0,17.772,SideCode.Unknown,(NoCP, NoCP), (NoCP, NoCP),List(Point(382296.6549999699,6677296.3499998255,11.288), Point(382281.1189999697,6677304.980999825,11.597)),projectId,  RoadAddressChangeType.New,AdministrativeClass.Municipality, FrozenLinkInterface,17.772,0,0,1,reversed = false,None,1652179948783L,0,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.RightSide,Discontinuity.Continuous,AddrMRange(0,0),AddrMRange(0,0),None,None,Some("test"),"87e55345-c1b8-4fca-9c09-574730f43256:1", 0.0,376.486,SideCode.Unknown,(NoCP, NoCP), (NoCP, NoCP),List(Point(382281.1189999697,6677304.980999825,11.597), Point(382270.9149999696,6677310.649999826,11.914), Point(382225.4139999695,6677339.509999825,13.491), Point(382189.0149999695,6677363.168999822,14.558), Point(382157.0349999694,6677384.748999822,15.127), Point(382121.1539999695,6677411.009999822,15.407), Point(382084.2339999698,6677439.868999822,15.136), Point(382053.8139999695,6677464.048999821,14.812), Point(382019.4939999695,6677492.127999819,14.595), Point(381976.8539999694,6677525.92799982,12.091)),projectId,  RoadAddressChangeType.New,AdministrativeClass.Municipality, FrozenLinkInterface,376.486,0,0,1,reversed = false,None,1652179948783L,0,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.RightSide,Discontinuity.Continuous,AddrMRange(0,0),AddrMRange(0,0),None,None,Some("test"),"f2246b5d-b093-41bd-929b-238177008c04:1", 0.0,72.563,SideCode.Unknown,(NoCP, NoCP), (NoCP, NoCP),List(Point(381976.8539999694,6677525.92799982,12.091), Point(381919.9139999694,6677570.907999818,13.624)),projectId,  RoadAddressChangeType.New,AdministrativeClass.Municipality, FrozenLinkInterface,72.563,0,0,1,reversed = false,None,1652179948783L,0,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.RightSide,Discontinuity.Continuous,AddrMRange(0,0),AddrMRange(0,0),None,None,Some("test"),"a8149805-ff5b-42a5-8a14-cd6e7f5ee759:1", 0.0,20.673,SideCode.Unknown,(NoCP, NoCP), (NoCP, NoCP),List(Point(381919.9139999694,6677570.907999818,13.624), Point(381904.0539999696,6677584.167999816,13.56)),projectId,  RoadAddressChangeType.New,AdministrativeClass.Municipality, FrozenLinkInterface,20.673,0,0,1,reversed = false,None,1652179948783L,0,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.LeftSide,Discontinuity.Continuous,AddrMRange(0,80),AddrMRange(0,80),None,None,Some("test"),"0f81aa70-571c-4fbb-a860-223f381e516e:1", 0.0,79.473,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(382704.709,6677078.547,19.348), Point(382694.9749999698,6677090.171999835,19.05), Point(382682.7549999699,6677097.972999835,18.79), Point(382670.2749999698,6677101.092999835,18.566), Point(382642.8299999698,6677102.362999833,18.248), Point(382633.355,6677102.392,18.148004177679812)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,79.473,75344,440362,1,reversed = false,None,1652179948783L,148694049,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.LeftSide,Discontinuity.Continuous,AddrMRange(80,94),AddrMRange(80,94),None,None,Some("test"),"777942ca-80e4-4452-b255-46f72aa3f4c7:1", 0.0,14.56,SideCode.AgainstDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(382618.795,6677102.392,17.816), Point(382633.3549999697,6677102.391999833,18.148)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,14.56,75344,440363,1,reversed = false,None,1652179948783L,148694049,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.RightSide,Discontinuity.Continuous,AddrMRange(0,98),AddrMRange(0,98),None,None,Some("test"),"0076d05b-abd9-47ed-a745-9dd96a835ea3:1", 0.0,97.865,SideCode.AgainstDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(382634.915,6677115.653,18.08), Point(382655.1949999698,6677115.652999835,18.381), Point(382683.2749999699,6677121.371999836,18.523), Point(382714.2149999698,6677136.7119998345,18.679), Point(382727.7349999699,6677141.651999837,18.623)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,97.865,75083,443468,1,reversed = false,None,1652179948783L,148600148,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.RightSide,Discontinuity.Continuous,AddrMRange(98,112),AddrMRange(98,112),None,None,Some("test"),"bce3456c-0791-46c4-b1ea-683fc4114c9f:1", 0.0,13.9,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(382634.915,6677115.653,18.08), Point(382621.1349999698,6677117.471999834,17.05)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,13.9,75083,443469,1,reversed = false,None,1652179948783L,148600148,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.RightSide,Discontinuity.Continuous,AddrMRange(112,1558),AddrMRange(112,1558),None,None,Some("test"),"0681b62b-54b8-4684-a954-7b6c18818cbe:1", 0.0,1443.955,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(382621.135,6677117.472,0.0), Point(382606.318,6677116.087,0.0), Point(382582.453,6677121.519,0.0), Point(382560.993,6677128.578,0.0), Point(382520.736,6677145.971,0.0), Point(382495.843,6677158.765,0.0), Point(382476.314,6677169.825,0.0), Point(382430.993,6677198.542,0.0), Point(382370.819,6677237.209,0.0), Point(382325.951,6677265.326,0.0), Point(382270.649,6677300.514,0.0), Point(382223.968,6677331.387,0.0), Point(382194.338,6677352.465,0.0), Point(382172.454,6677368.158,0.0), Point(382147.961,6677386.28,0.0), Point(382094.788,6677427.404,0.0), Point(382061.236,6677454.009,0.0), Point(382009.568,6677494.451,0.0), Point(381968.283,6677526.83,0.0), Point(381919.445,6677565.739,0.0), Point(381877.615,6677599.019,0.0), Point(381834.037,6677633.584,0.0), Point(381795.68,6677663.786,0.0), Point(381740.895,6677706.94,0.0), Point(381705.961,6677734.383,0.0), Point(381650.285,6677778.158,0.0), Point(381618.185,6677803.417,0.0), Point(381585.801,6677828.181,0.0), Point(381573.894,6677837.245,0.0), Point(381563.632,6677848.21,0.0), Point(381555.61,6677859.132,0.0), Point(381549.132,6677871.155,0.0), Point(381543.686,6677890.79,0.0), Point(381540.935,6677909.495,0.0), Point(381541.132,6677927.216,0.0), Point(381541.132,6677927.216,0.0), Point(381544.842,6677947.313,0.0), Point(381550.914,6677964.937,0.0), Point(381559.378,6677989.503,0.0)),projectId,  RoadAddressChangeType.Termination,AdministrativeClass.Municipality, FrozenLinkInterface,1443.955,75083,443470,1,reversed = false,None,1652179948783L,148600148,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.LeftSide,Discontinuity.Continuous,AddrMRange(94,1562),AddrMRange(94,1562),None,None,Some("test"),"d1aa75ff-14ca-4b22-822b-a445ee3d3a1d:1", 0.0,1461.494,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(382618.795,6677102.392,0.0), Point(382599.245,6677107.408,0.0), Point(382576.147,6677112.618,0.0), Point(382550.352,6677121.166,0.0), Point(382523.618,6677132.606,0.0), Point(382498.638,6677144.902,0.0), Point(382479.393,6677155.868,0.0), Point(382467.86,6677162.475,0.0), Point(382427.692,6677188.154,0.0), Point(382393.335,6677210.51,0.0), Point(382327.43,6677252.165,0.0), Point(382273.889,6677286.635,0.0), Point(382190.429,6677342.5,0.0), Point(382168.723,6677358.123,0.0), Point(382129.651,6677387.692,0.0), Point(382110.576,6677402.446,0.0), Point(382072.523,6677432.379,0.0), Point(382050.923,6677449.197,0.0), Point(382025.665,6677468.858,0.0), Point(381970.874,6677511.995,0.0), Point(381936.436,6677539.072,0.0), Point(381875.808,6677586.521,0.0), Point(381854.655,6677603.081,0.0), Point(381814.912,6677634.196,0.0), Point(381759.801,6677679.197,0.0), Point(381718.43,6677711.763,0.0), Point(381652.039,6677764.023,0.0), Point(381620.881,6677788.548,0.0), Point(381594.385,6677809.081,0.0), Point(381579.406,6677820.484,0.0), Point(381568.768,6677828.582,0.0), Point(381568.768,6677828.582,0.0), Point(381558.971,6677837.57,0.0), Point(381547.903,6677852.125,0.0), Point(381539.121,6677869.425,0.0), Point(381535.108,6677882.363,0.0), Point(381531.939,6677900.74,0.0), Point(381530.73,6677914.577,0.0), Point(381533.598,6677942.096,0.0), Point(381538.731,6677960.267,0.0), Point(381544.645,6677977.432,0.0), Point(381549.987,6677992.937,0.0)),projectId,  RoadAddressChangeType.Termination,AdministrativeClass.Municipality, FrozenLinkInterface,1461.494,75344,440364,1,reversed = false,None,1652179948783L,148694049,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.RightSide,Discontinuity.Continuous,AddrMRange(1558,1661),AddrMRange(1558,1661),None,None,Some("test"),"eb65840d-f279-481c-9d8e-79a5fbcb2eff:1", 0.0,103.299,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(381559.378,6677989.503,17.804), Point(381568.8709999693,6678013.807999811,17.721), Point(381576.5879999693,6678033.566999809,17.59), Point(381585.5179999694,6678056.426999809,17.051), Point(381592.3379999694,6678078.199999809,16.193), Point(381595.6959999694,6678086.154999809,15.823)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.Municipality, FrozenLinkInterface,103.299,75083,443471,1,reversed = false,None,1652179948783L,148600148,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.RightSide,Discontinuity.Continuous,AddrMRange(1661,1721),AddrMRange(1661,1721),None,None,Some("test"),"225866de-b3f3-45fb-a7ca-c73d5e6a04fa:1", 0.0,59.746,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(381595.696,6678086.155,15.823), Point(381601.8769999694,6678104.90299981,15.054), Point(381613.5089999694,6678137.770999809,14.288), Point(381615.191,6678142.628,14.240001911422103)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.Municipality, FrozenLinkInterface,59.746,75083,443472,1,reversed = false,None,1652179948783L,148600148,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.LeftSide,Discontinuity.Continuous,AddrMRange(1562,1725),AddrMRange(1562,1725),None,None,Some("test"),"758d151d-7d16-4436-a87f-dd2a0918feac:1", 0.0,162.22,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(381549.987,6677992.937,17.671), Point(381555.6009999694,6678008.78699981,17.679), Point(381569.1359999693,6678044.722999808,17.454), Point(381575.2599999692,6678060.7859998075,17.044), Point(381587.3779999693,6678098.18599981,15.461), Point(381601.8049999692,6678139.7809998095,14.296), Point(381603.8949999694,6678145.907999812,14.327)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.Municipality, FrozenLinkInterface,162.22,75344,440365,1,reversed = false,None,1652179948783L,148694049,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.RightSide,Discontinuity.Continuous,AddrMRange(1721,1756),AddrMRange(1721,1756),None,None,Some("test"),"524d6d96-a880-4665-b2a9-175d6ff43c34:1", 0.0,34.629,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(381615.191,6678142.628,14.24), Point(381626.5209999692,6678175.350999809,14.523)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.Municipality, FrozenLinkInterface,34.629,75083,443473,1,reversed = false,None,1652179948783L,148600148,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.LeftSide,Discontinuity.Continuous,AddrMRange(1725,1761),AddrMRange(1725,1761),None,None,Some("test"),"a145983c-7f11-4c8d-a4a0-bee2f923c2c0:1", 0.0,36.139,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(381603.895,6678145.908,14.327), Point(381615.5639999694,6678180.110999809,14.514)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.Municipality, FrozenLinkInterface,36.139,75344,440366,1,reversed = false,None,1652179948783L,148694049,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.RightSide,Discontinuity.MinorDiscontinuity,AddrMRange(1756,1800),AddrMRange(1756,1800),None,None,Some("test"),"edac1707-e255-4fe0-a332-66ca0c5e779d:1", 0.0,44.41,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(381626.521,6678175.351,14.523), Point(381627.9809999695,6678179.56599981,14.661), Point(381634.0459999693,6678199.035999809,15.461), Point(381639.582,6678217.793,16.106987798151717)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.Municipality, FrozenLinkInterface,44.41,75083,443474,1,reversed = false,None,1652179948783L,148600148,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.LeftSide,Discontinuity.MinorDiscontinuity,AddrMRange(1761,1800),AddrMRange(1761,1800),None,None,Some("test"),"72f4f661-a83e-4c6d-8d61-ef5e015bce5d:1", 0.0,38.656,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(381615.564,6678180.111,14.514), Point(381616.8749999694,6678183.97199981,14.678), Point(381622.2099999694,6678203.221999812,15.741), Point(381623.597,6678217.759,16.253995582707027)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.Municipality, FrozenLinkInterface,38.656,75344,440367,1,reversed = false,None,1652179948783L,148694049,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.Combined,Discontinuity.Continuous,AddrMRange(1800,1891),AddrMRange(1800,1891),None,None,Some("test"),"4aabed3b-c8b5-4dbf-bd2d-e423c0379b6d:1", 0.0,89.152,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(381619.214,6678234.151,16.919), Point(381599.4389999694,6678240.3379998095,17.728), Point(381534.334,6678261.416,20.83098626554424)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.Municipality, FrozenLinkInterface,89.152,65784,418500,1,reversed = false,None,1652179948783L,90509805,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.Combined,Discontinuity.Continuous,AddrMRange(1891,1950),AddrMRange(1891,1950),None,None,Some("test"),"f80c23ce-1eba-40c1-9c57-32e15031ae28:1", 0.0,57.642,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(381534.334,6678261.416,20.831), Point(381479.449,6678279.03,22.033997093179924)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.Municipality, FrozenLinkInterface,57.642,65784,418501,1,reversed = false,None,1652179948783L,90509805,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.Combined,Discontinuity.Continuous,AddrMRange(1950,1973),AddrMRange(1950,1973),None,None,Some("test"),"f4cad4c3-f8dd-49e3-aad1-9ad3f3f68c2b:1", 0.0,22.501,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(381479.449,6678279.03,22.034), Point(381458.024,6678285.905,22.571999336387503)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.Municipality, FrozenLinkInterface,22.501,65784,418502,1,reversed = false,None,1652179948783L,90509805,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.Combined,Discontinuity.Continuous,AddrMRange(1973,2038),AddrMRange(1973,2038),None,None,Some("test"),"5f4946f2-4565-46b8-8c21-2f0460c5f8ea:1", 0.0,64.264,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(381458.024,6678285.905,22.572), Point(381396.734,6678305.23,25.189981607338577)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.Municipality, FrozenLinkInterface,64.264,65784,418503,1,reversed = false,None,1652179948783L,90509805,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.Combined,Discontinuity.Continuous,AddrMRange(2038,2161),AddrMRange(2038,2161),None,None,Some("test"),"eda9af9e-1e85-4e9e-a721-1f7f35aa256e:1", 0.0,120.098,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(381396.734,6678305.23,25.19), Point(381354.2689999692,6678318.732999804,26.242), Point(381281.9149999693,6678340.4309998015,26.406)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.Municipality, FrozenLinkInterface,120.098,65784,418504,1,reversed = false,None,1652179948783L,90509805,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.Combined,Discontinuity.Continuous,AddrMRange(2161,2191),AddrMRange(2161,2191),None,None,Some("test"),"bcdd3b13-fff4-4d02-b657-a1d38d343f9f:1", 0.0,29.56,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(381281.915,6678340.431,26.406), Point(381253.919,6678349.92,26.3930001750842)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.Municipality, FrozenLinkInterface,29.56,65784,418505,1,reversed = false,None,1652179948783L,90509805,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.Combined,Discontinuity.Continuous,AddrMRange(2191,2277),AddrMRange(2191,2277),None,None,Some("test"),"0243816e-c18c-4531-95a8-8d8dec17d76d:1", 0.0,84.045,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(381253.919,6678349.92,26.393), Point(381233.359999969,6678356.888999802,25.912), Point(381187.1099999694,6678372.5649998,25.382), Point(381186.6319999692,6678372.726999798,25.358), Point(381174.0089999691,6678375.8239998,25.171)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.Municipality, FrozenLinkInterface,84.045,65784,418506,1,reversed = false,None,1652179948783L,90509805,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.Combined,Discontinuity.Continuous,AddrMRange(2277,2336),AddrMRange(2277,2336),None,None,Some("test"),"0c93c1e2-7074-4be4-b60f-ef2ab63cc286:1", 0.0,57.935,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(381174.009,6678375.824,25.171), Point(381158.479999969,6678379.6339997975,25.133), Point(381156.5529999691,6678380.033999801,25.159), Point(381117.412,6678388.17,24.72900304614566)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.Municipality, FrozenLinkInterface,57.935,65784,418507,1,reversed = false,None,1652179948783L,90509805,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.Combined,Discontinuity.Continuous,AddrMRange(2336,2396),AddrMRange(2336,2396),None,None,Some("test"),"62d12731-9c13-4eca-a734-51eab45dc450:1", 0.0,59.009,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(381117.412,6678388.17,24.729), Point(381108.8399999693,6678389.951999797,24.595), Point(381091.1019999691,6678393.638999799,24.208), Point(381067.0709999693,6678395.937999797,23.433), Point(381059.109999969,6678396.6839997955,23.151)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.Municipality, FrozenLinkInterface,59.009,65784,418508,1,reversed = false,None,1652179948783L,90509805,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.Combined,Discontinuity.Continuous,AddrMRange(2396,2406),AddrMRange(2396,2406),None,None,Some("test"),"cacda57d-9130-4436-abc8-b68e0f15c3dc:1", 0.0,10.144,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(381059.11,6678396.684,23.151), Point(381049.01,6678397.629,22.861003225426835)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.Municipality, FrozenLinkInterface,10.144,65784,418509,1,reversed = false,None,1652179948783L,90509805,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.Combined,Discontinuity.Continuous,AddrMRange(2406,2592),AddrMRange(2406,2592),None,None,Some("test"),"7c13f134-06e5-4d8a-b6ef-c918103738d9:1", 0.0,181.486,SideCode.AgainstDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(380870.831,6678383.33,20.073), Point(380880.420999969,6678387.849999792,20.258), Point(380894.633999969,6678392.936999791,20.41), Point(380915.6789999691,6678398.685999792,20.433), Point(380937.9649999693,6678402.299999795,20.497), Point(380957.0349999691,6678403.824999792,20.639), Point(380973.836999969,6678403.997999794,20.869), Point(380983.772999969,6678403.735999796,20.985), Point(381049.01,6678397.629,22.860987566878414)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.Municipality, FrozenLinkInterface,181.486,65784,418510,1,reversed = false,None,1652179948783L,90509805,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.Combined,Discontinuity.Continuous,AddrMRange(2592,2612),AddrMRange(2592,2612),None,None,Some("test"),"5769ba0b-be36-47c5-bd84-63d469212a6c:1", 0.0,20.01,SideCode.AgainstDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(380854.368,6678371.961,19.901), Point(380856.086999969,6678373.28099979,19.942), Point(380870.831,6678383.33,20.072998476039952)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.Municipality, FrozenLinkInterface,20.01,65784,418511,1,reversed = false,None,1652179948783L,90509805,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.Combined,Discontinuity.Continuous,AddrMRange(2612,2671),AddrMRange(2612,2671),None,None,Some("test"),"0f3f961c-22db-4a05-a615-0b2c36468cef:1", 0.0,57.57,SideCode.AgainstDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(380809.276,6678336.175,18.996), Point(380837.881999969,6678359.277999789,19.563), Point(380854.368,6678371.961,19.900993202485605)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.Municipality, FrozenLinkInterface,57.57,65784,418512,1,reversed = false,None,1652179948783L,90509805,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.Combined,Discontinuity.Continuous,AddrMRange(2671,2933),AddrMRange(2671,2933),None,None,Some("test"),"9cbd5766-e785-4087-9700-cf5c942e6f7e:1", 0.0,256.682,SideCode.AgainstDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(380628.055,6678154.748,18.671), Point(380641.9559999688,6678169.690999785,18.776), Point(380665.5559999688,6678195.0269997865,18.939), Point(380691.2219999689,6678222.249999788,18.949), Point(380717.922999969,6678250.415999789,18.872), Point(380744.965999969,6678278.89699979,18.849), Point(380762.5769999689,6678295.921999787,18.792), Point(380784.5739999688,6678315.058999791,18.777), Point(380809.275999969,6678336.17499979,18.996)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.Municipality, FrozenLinkInterface,256.682,65784,418513,1,reversed = false,None,1652179948783L,90509805,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.Combined,Discontinuity.Continuous,AddrMRange(2933,3272),AddrMRange(2933,3272),None,None,Some("test"),"df57fa88-27bb-4bbc-8443-b794defaf66e:1", 0.0,331.582,SideCode.AgainstDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(380359.659,6677970.062,16.089), Point(380382.3669999687,6677976.654999778,16.214), Point(380401.6149999687,6677983.0479997825,16.316), Point(380424.3719999687,6677990.810999782,16.439), Point(380446.1569999687,6678000.510999784,16.7), Point(380485.259999969,6678023.311999782,17.234), Point(380531.5919999688,6678058.507999784,17.778), Point(380543.8049999688,6678069.526999783,17.871), Point(380575.696999969,6678100.057999785,18.213), Point(380596.5439999688,6678122.073999785,18.409), Point(380624.2549999688,6678150.688999786,18.648), Point(380628.0549999689,6678154.747999786,18.671)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.Municipality, FrozenLinkInterface,331.582,65784,418514,1,reversed = false,None,1652179948783L,90509805,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.Combined,Discontinuity.Continuous,AddrMRange(3272,3296),AddrMRange(3272,3296),None,None,Some("test"),"7509b2a2-a7b6-43da-8d98-f5b4f36e265c:1", 0.0,23.705,SideCode.AgainstDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(380336.895,6677963.451,15.992), Point(380359.6589999686,6677970.061999777,16.089)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.Municipality, FrozenLinkInterface,23.705,65784,418515,1,reversed = false,None,1652179948783L,90509805,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.Combined,Discontinuity.Continuous,AddrMRange(3296,3345),AddrMRange(3296,3345),None,None,Some("test"),"2c438c73-a547-4f65-82d0-921e153b4939:1", 0.0,48.386,SideCode.AgainstDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(380289.592,6677953.504,16.055), Point(380311.7999999686,6677957.064999778,15.975), Point(380336.895,6677963.451,15.991999688459133)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.Municipality, FrozenLinkInterface,48.386,65784,418516,1,reversed = false,None,1652179948783L,90509805,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.Combined,Discontinuity.Continuous,AddrMRange(3345,3356),AddrMRange(3345,3356),None,None,Some("test"),"fff4acae-4f85-4755-8e6d-a9eb72531b4b:1", 0.0,10.957,SideCode.AgainstDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(380278.804,6677951.588,16.086), Point(380282.4019999686,6677952.190999776,16.09), Point(380289.592,6677953.504,16.05500039657419)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.Municipality, FrozenLinkInterface,10.957,65784,418517,1,reversed = false,None,1652179948783L,90509805,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.Combined,Discontinuity.Continuous,AddrMRange(3356,3617),AddrMRange(3356,3617),None,None,Some("test"),"60eaef28-4fc7-4f6f-ab94-49df9f69602a:1", 0.0,255.068,SideCode.AgainstDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(380024.326,6677935.779,19.957), Point(380026.7469999686,6677935.90099977,19.885), Point(380061.1649999685,6677937.757999771,19.053), Point(380094.5449999688,6677939.249999774,18.334), Point(380133.7329999685,6677940.097999772,17.289), Point(380179.4739999688,6677942.247999773,16.493), Point(380215.7779999687,6677945.685999776,16.463), Point(380254.6109999686,6677948.909999776,16.236), Point(380278.8039999687,6677951.587999778,16.086)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.Municipality, FrozenLinkInterface,255.068,65784,418518,1,reversed = false,None,1652179948783L,90509805,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.Combined,Discontinuity.Continuous,AddrMRange(3617,3637),AddrMRange(3617,3637),None,None,Some("test"),"4c43f260-90c2-4490-8a28-10f8860fef3f:1", 0.0,19.868,SideCode.AgainstDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(380004.483,6677934.779,20.451), Point(380024.326,6677935.779,19.957004520848947)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.Municipality, FrozenLinkInterface,19.868,65784,418519,1,reversed = false,None,1652179948783L,90509805,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.Combined,Discontinuity.Continuous,AddrMRange(3637,3940),AddrMRange(3637,3940),None,None,Some("test"),"4d42a932-a706-49bd-a12f-f8213982889e:1", 0.0,296.868,SideCode.AgainstDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(379715.908,6677871.82,15.394), Point(379716.2199999684,6677871.924999761,15.404), Point(379750.1579999684,6677882.901999762,16.677), Point(379782.1449999684,6677893.460999765,17.755), Point(379813.4599999685,6677903.233999765,18.798), Point(379845.7589999686,6677913.458999766,20.06), Point(379879.6119999684,6677921.643999767,21.311), Point(379914.5369999685,6677927.329999768,22.364), Point(379948.0399999684,6677931.213999769,22.015), Point(379975.7979999686,6677933.35799977,21.237), Point(380004.4829999687,6677934.778999771,20.451)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.Municipality, FrozenLinkInterface,296.868,65784,418520,1,reversed = false,None,1652179948783L,90509805,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.RightSide,Discontinuity.Continuous,AddrMRange(3940,4055),AddrMRange(3940,4055),None,None,Some("test"),"c31d99c9-d3b2-44e5-aa44-a047e44ce902:1", 0.0,114.574,SideCode.AgainstDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(379605.889,6677840.998,11.622), Point(379636.5979999684,6677849.89799976,12.598), Point(379667.9799999683,6677859.63699976,13.681), Point(379701.7969999684,6677870.728999762,14.864), Point(379715.9079999684,6677871.819999762,15.394)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.Municipality, FrozenLinkInterface,114.574,65512,418874,1,reversed = false,None,1652179948783L,90509888,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.LeftSide,Discontinuity.Continuous,AddrMRange(3940,4056),AddrMRange(3940,4056),None,None,Some("test"),"15200976-e270-4533-b6fb-3ce8c81ef5d6:1", 0.0,115.526,SideCode.AgainstDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(379607.786,6677831.772,11.756), Point(379639.7089999683,6677842.592999761,12.758), Point(379702.4729999686,6677864.236999761,14.771), Point(379715.9079999684,6677871.819999762,15.394)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.Municipality, FrozenLinkInterface,115.526,65618,418430,1,reversed = false,None,1652179948783L,90510413,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.RightSide,Discontinuity.Continuous,AddrMRange(4055,4073),AddrMRange(4055,4073),None,None,Some("test"),"78af4d4e-a7f2-44e3-896b-909c5fd37bca:1", 0.0,18.599,SideCode.AgainstDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(379587.619,6677837.517,10.957), Point(379605.8889999683,6677840.997999759,11.622)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.Municipality, FrozenLinkInterface,18.599,65512,418875,1,reversed = false,None,1652179948783L,90509888,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.LeftSide,Discontinuity.Continuous,AddrMRange(4056,4075),AddrMRange(4056,4075),None,None,Some("test"),"22371d58-d4a2-4989-9b80-b6b200b2f7d1:1", 0.0,19.607,SideCode.AgainstDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(379588.567,6677827.89,11.159), Point(379591.8239999684,6677828.525999757,11.272), Point(379607.786,6677831.772,11.755993403569459)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.Municipality, FrozenLinkInterface,19.607,65618,418431,1,reversed = false,None,1652179948783L,90510413,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.LeftSide,Discontinuity.Continuous,AddrMRange(4075,4261),AddrMRange(4075,4261),None,None,Some("test"),"561fc10c-4b54-4e7d-9cc7-a52351868c8b:1", 0.0,185.748,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(379588.567,6677827.89,11.159), Point(379576.1339999684,6677825.549999759,10.827), Point(379557.3309999684,6677824.060999759,10.374), Point(379531.4949999683,6677822.437999759,9.82), Point(379496.0549999682,6677824.737999756,9.055), Point(379444.5179999682,6677827.983999756,7.94), Point(379403.3959999683,6677830.417999755,7.314)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.Municipality, FrozenLinkInterface,185.748,65618,418432,1,reversed = false,None,1652179948783L,90510413,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.RightSide,Discontinuity.Continuous,AddrMRange(4073,4288),AddrMRange(4073,4288),None,None,Some("test"),"6dc68172-2641-43c7-9409-c978f3df1f82:1", 0.0,214.351,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(379587.619,6677837.517,10.957), Point(379569.2339999684,6677834.20599976,10.464), Point(379537.3109999682,6677831.229999759,9.76), Point(379512.9629999683,6677831.229999757,9.271), Point(379480.4989999683,6677832.852999757,8.627), Point(379444.7889999682,6677836.911999756,7.858), Point(379399.6089999685,6677839.345999754,7.253), Point(379374.062,6677840.456,6.874004297615221)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.Municipality, FrozenLinkInterface,214.351,65512,418876,1,reversed = false,None,1652179948783L,90509888,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.LeftSide,Discontinuity.Continuous,AddrMRange(4261,4291),AddrMRange(4261,4291),None,None,Some("test"),"ca116376-62c3-48d7-8264-355a093b14bf:1", 0.0,29.574,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(379403.396,6677830.418,7.314), Point(379373.879,6677832.255,6.726002145266216)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.Municipality, FrozenLinkInterface,29.574,65618,418433,1,reversed = false,None,1652179948783L,90510413,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.RightSide,Discontinuity.Continuous,AddrMRange(4288,4306),AddrMRange(4288,4306),None,None,Some("test"),"5288d4ec-ca4b-415c-9d04-3101f114f4fc:1", 0.0,17.959,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(379374.062,6677840.456,6.874), Point(379356.1599999682,6677841.882999754,6.614)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.Municipality, FrozenLinkInterface,17.959,65512,418877,1,reversed = false,None,1652179948783L,90509888,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.LeftSide,Discontinuity.Continuous,AddrMRange(4291,4309),AddrMRange(4291,4309),None,None,Some("test"),"9a8f1573-b95c-46cb-a05c-06c39d3329af:1", 0.0,17.976,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(379373.879,6677832.255,6.726), Point(379355.911,6677832.794,6.480001130125329)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.Municipality, FrozenLinkInterface,17.976,65618,418434,1,reversed = false,None,1652179948783L,90510413,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.RightSide,Discontinuity.Continuous,AddrMRange(4306,4437),AddrMRange(4306,4437),None,None,Some("test"),"85436c48-78cd-49b7-83a6-8e2c94be9a6e:1", 0.0,130.037,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(379356.16,6677841.883,6.614), Point(379226.5099999683,6677851.901999748,8.099)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.Municipality, FrozenLinkInterface,130.037,65512,418878,1,reversed = false,None,1652179948783L,90509888,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.LeftSide,Discontinuity.Continuous,AddrMRange(4309,4441),AddrMRange(4309,4441),None,None,Some("test"),"7d684b95-fb49-4fcf-a963-25922d72a5b8:1", 0.0,132.182,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(379355.911,6677832.794,6.48), Point(379334.1769999683,6677833.121999755,6.44), Point(379281.7819999683,6677834.921999752,7.265), Point(379223.865,6677838.378,8.112994071362062)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.Municipality, FrozenLinkInterface,132.182,65618,418435,1,reversed = false,None,1652179948783L,90510413,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.RightSide,Discontinuity.Continuous,AddrMRange(4437,4457),AddrMRange(4437,4457),None,None,Some("test"),"b1235380-a215-4491-a6ab-7eca41f402b9:1", 0.0,20.205,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(379226.51,6677851.902,8.099), Point(379206.426,6677854.113,8.168998838168008)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.Municipality, FrozenLinkInterface,20.205,65512,418879,1,reversed = false,None,1652179948783L,90509888,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.LeftSide,Discontinuity.Continuous,AddrMRange(4441,4460),AddrMRange(4441,4460),None,None,Some("test"),"04c4dfef-0af3-4843-b8fe-d249759a6200:1", 0.0,18.501,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(379223.865,6677838.378,8.113), Point(379205.546,6677840.967,8.139999933700826)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.Municipality, FrozenLinkInterface,18.501,65618,418436,1,reversed = false,None,1652179948783L,90510413,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.RightSide,Discontinuity.Continuous,AddrMRange(4457,4470),AddrMRange(4457,4470),None,None,Some("test"),"ebc93de6-5bd1-447e-a6ef-9036113f0fce:1", 0.0,13.133,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(379206.426,6677854.113,8.169), Point(379200.5459999682,6677854.515999749,8.365), Point(379193.327,6677855.063,8.363000134857419)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.Municipality, FrozenLinkInterface,13.133,65512,418880,1,reversed = false,None,1652179948783L,90509888,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.LeftSide,Discontinuity.Continuous,AddrMRange(4460,4473),AddrMRange(4460,4473),None,None,Some("test"),"e413b3ba-23a1-4e19-9216-2af9f4fded49:1", 0.0,13.276,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(379205.546,6677840.967,8.14), Point(379192.3769999682,6677842.648999748,8.392)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.Municipality, FrozenLinkInterface,13.276,65618,418437,1,reversed = false,None,1652179948783L,90510413,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.RightSide,Discontinuity.Continuous,AddrMRange(4470,4514),AddrMRange(4470,4514),None,None,Some("test"),"fe8b0cd6-e62e-4806-9365-e6bac21e0fbc:1", 0.0,42.62,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(379193.327,6677855.063,8.363), Point(379187.0649999681,6677855.536999747,8.425), Point(379185.1339999682,6677855.6299997475,8.445), Point(379172.8839999683,6677856.587999747,8.575), Point(379150.859,6677858.631,8.811998880642651)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.Municipality, FrozenLinkInterface,42.62,65512,418881,1,reversed = false,None,1652179948783L,90509888,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.RightSide,Discontinuity.Continuous,AddrMRange(4514,4606),AddrMRange(4514,4606),None,None,Some("test"),"e60df406-cc78-4cf1-a872-3da317f66371:1", 0.0,92.219,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(379150.859,6677858.631,8.812), Point(379121.0559999681,6677861.286999746,9.049), Point(379093.5799999683,6677863.576999744,9.308), Point(379070.2919999681,6677866.457999744,9.477), Point(379059.11,6677867.792,9.628997271568428)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.Municipality, FrozenLinkInterface,92.219,65512,418882,1,reversed = false,None,1652179948783L,90509888,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.LeftSide,Discontinuity.Continuous,AddrMRange(4473,4606),AddrMRange(4473,4606),None,None,Some("test"),"b25844ce-d250-4d39-bf95-b0276dafe826:1", 0.0,133.513,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(379192.377,6677842.649,8.392), Point(379156.3559999685,6677845.848999748,8.783), Point(379114.3179999682,6677848.5279997485,9.167), Point(379059.34,6677853.77,9.881993826073371)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.Municipality, FrozenLinkInterface,133.513,65618,418438,1,reversed = false,None,1652179948783L,90510413,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.RightSide,Discontinuity.Continuous,AddrMRange(4606,4665),AddrMRange(4606,4665),None,None,Some("test"),"3ad18227-afa3-424a-9030-5e7a5731b6c5:1", 0.0,58.735,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(379059.11,6677867.792,9.629), Point(379002.0209999682,6677881.598999744,9.488)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.Municipality, FrozenLinkInterface,58.735,65512,418883,1,reversed = false,None,1652179948783L,90509888,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.LeftSide,Discontinuity.Continuous,AddrMRange(4606,4666),AddrMRange(4606,4666),None,None,Some("test"),"7ad45753-10ea-4c23-a83c-8cd2072694e5:1", 0.0,59.978,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(379059.34,6677853.77,9.882), Point(379000.6779999681,6677866.2639997415,9.837)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.Municipality, FrozenLinkInterface,59.978,65618,418439,1,reversed = false,None,1652179948783L,90510413,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.RightSide,Discontinuity.Continuous,AddrMRange(4665,4734),AddrMRange(4665,4734),None,None,Some("test"),"e8e3d332-2498-4e73-b8d0-6f22628ebeb6:1", 0.0,68.845,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(379002.021,6677881.599,9.488), Point(378934.802,6677896.475,9.421000390944252)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.Municipality, FrozenLinkInterface,68.845,65512,418884,1,reversed = false,None,1652179948783L,90509888,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.RightSide,Discontinuity.Continuous,AddrMRange(4734,4765),AddrMRange(4734,4765),None,None,Some("test"),"3e17fa70-8656-4de1-839f-3c9f1027e5e1:1", 0.0,30.116,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(378934.802,6677896.475,9.421), Point(378913.6999999682,6677901.144999741,9.368), Point(378905.4549999681,6677903.224999741,9.366)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.Municipality, FrozenLinkInterface,30.116,65512,418885,1,reversed = false,None,1652179948783L,90509888,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.LeftSide,Discontinuity.Continuous,AddrMRange(4666,4768),AddrMRange(4666,4768),None,None,Some("test"),"53280c47-a344-4184-8c83-adc55c6d8d52:1", 0.0,102.098,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(379000.678,6677866.264,9.837), Point(378944.7679999681,6677880.137999741,9.437), Point(378901.539,6677890.667,9.185002608653955)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.Municipality, FrozenLinkInterface,102.098,65618,418440,1,reversed = false,None,1652179948783L,90510413,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.LeftSide,Discontinuity.Continuous,AddrMRange(4768,4786),AddrMRange(4768,4786),None,None,Some("test"),"115f3ee4-a61b-4707-961d-163844c836d5:1", 0.0,17.915,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(378901.539,6677890.667,9.185), Point(378884.133,6677894.908,9.106000946660773)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.Municipality, FrozenLinkInterface,17.915,65618,418441,1,reversed = false,None,1652179948783L,90510413,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.RightSide,Discontinuity.Continuous,AddrMRange(4765,4793),AddrMRange(4765,4793),None,None,Some("test"),"6fcc7a63-8a30-4c9c-8616-e9444e33998f:1", 0.0,27.759,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(378905.455,6677903.225,9.366), Point(378891.7389999682,6677906.68699974,9.363), Point(378878.416,6677909.482,9.34300027999582)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.Municipality, FrozenLinkInterface,27.759,65512,418886,1,reversed = false,None,1652179948783L,90509888,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.LeftSide,Discontinuity.Continuous,AddrMRange(4786,4818),AddrMRange(4786,4818),None,None,Some("test"),"b62597f8-fdd3-4f1c-ac55-b2a343605e8e:1", 0.0,32.093,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(378884.133,6677894.908,9.106), Point(378852.6009999682,6677900.879999737,9.068)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.Municipality, FrozenLinkInterface,32.093,65618,418442,1,reversed = false,None,1652179948783L,90510413,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.RightSide,Discontinuity.Continuous,AddrMRange(4793,4818),AddrMRange(4793,4818),None,None,Some("test"),"33f3f362-5926-4516-b788-2dfcc54a7962:1", 0.0,25.319,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(378878.416,6677909.482,9.343), Point(378873.927999968,6677910.436999738,9.338), Point(378853.646,6677914.728,9.31500047729873)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.Municipality, FrozenLinkInterface,25.319,65512,418887,1,reversed = false,None,1652179948783L,90509888,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.RightSide,Discontinuity.Continuous,AddrMRange(4818,4960),AddrMRange(4818,4960),None,None,Some("test"),"4720d3d2-1b9e-4c99-aa23-7368e4b62558:1", 0.0,141.188,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(378853.646,6677914.728,9.315), Point(378830.7009999682,6677919.374999738,9.287), Point(378809.489999968,6677923.032999737,9.068), Point(378788.3179999681,6677926.1819997355,8.772), Point(378768.571999968,6677928.481999737,8.658), Point(378749.4739999681,6677930.657999736,8.585), Point(378713.9129999682,6677934.298999735,8.429)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.Municipality, FrozenLinkInterface,141.188,65512,418888,1,reversed = false,None,1652179948783L,90509888,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.LeftSide,Discontinuity.Continuous,AddrMRange(4818,4960),AddrMRange(4818,4960),None,None,Some("test"),"e6298d9b-1e7d-4f90-89d6-3347f0ed11da:1", 0.0,142.063,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(378852.601,6677900.88,9.068), Point(378796.9829999681,6677909.826999736,8.625), Point(378749.8749999681,6677915.952999736,8.404), Point(378711.909,6677920.398,8.240000012367387)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.Municipality, FrozenLinkInterface,142.063,65618,418443,1,reversed = false,None,1652179948783L,90510413,roadName,None,None,None,None)
      )

      linearLocationDAO.create(linearLocations)
      roadwayDAO.create(roadways)
      projectDAO.create(Project(projectId, ProjectState.Incomplete, "test", "test", DateTime.now(),"test", DateTime.now(), DateTime.now(),"",Seq(ProjectReservedPart(Sequences.nextViitePrimaryKeySeqValue,roadPart)),Seq(ProjectReservedPart(Sequences.nextViitePrimaryKeySeqValue,roadPart)),None,None,Set(14)))
      projectReservedPartDAO.reserveRoadPart(projectId, roadPart, "test")
      projectLinkDAO.create(projectLinks)
      projectService.recalculateProjectLinks(projectId, "test", Set(roadPart))
      val recalculated = projectLinkDAO.fetchProjectLinks(projectId)

      recalculated.size should be (92)
      val (terminated, others) = recalculated.partition(_.status == RoadAddressChangeType.Termination)
      val left = others.filter(_.track == Track.LeftSide).sortBy(_.addrMRange.start)
      val right = others.filter(_.track == Track.RightSide).sortBy(_.addrMRange.start)
      val combined = others.filter(_.track == Track.Combined).sortBy(_.addrMRange.start)
      val sortedTerminated = terminated.sortBy(_.addrMRange.start)

      val terminatedAddresses = sortedTerminated.map(pl => (pl.addrMRange.start.toInt, pl.addrMRange.end.toInt)).toList

      val addresses = (left ++ right ++ combined).map(pl => (pl.addrMRange.start.toInt, pl.addrMRange.end.toInt)).toList

      val precalculatedTerminatedAddresses = List(
        (94,1562),
        (112,1558)
      )

      val precalculatedAddresses = List(
        (0,79),
        (79,94),
        (94,112),
        (112,264),
        (264,332),
        (332,472),
        (472,492),
        (492,868),
        (868,942),
        (942,963),
        (963,1070),
        (1070,1294),
        (1294,1378),
        (1378,1391),
        (1391,1519),
        (1519,1559),
        (1559,1563),
        (1563,1726),
        (1726,1762),
        (1762,1801),
        (3941,4057),
        (4057,4076),
        (4076,4262),
        (4262,4291),
        (4291,4309),
        (4309,4442),
        (4442,4460),
        (4460,4473),
        (4473,4607),
        (4607,4667),
        (4667,4769),
        (4769,4787),
        (4787,4819),
        (4819,4961),
        (0,94),
        (94,98),
        (98,112),
        (112,277),
        (277,344),
        (344,488),
        (488,506),
        (506,884),
        (884,957),
        (957,977),
        (977,1087),
        (1087,1315),
        (1315,1398),
        (1398,1413),
        (1413,1559),
        (1559,1563),
        (1563,1662),
        (1662,1722),
        (1722,1757),
        (1757,1801),
        (3941,4056),
        (4056,4075),
        (4075,4290),
        (4290,4308),
        (4308,4439),
        (4439,4459),
        (4459,4472),
        (4472,4515),
        (4515,4608),
        (4608,4667),
        (4667,4736),
        (4736,4766),
        (4766,4794),
        (4794,4819),
        (4819,4961),
        (1801,1892),
        (1892,1951),
        (1951,1974),
        (1974,2039),
        (2039,2162),
        (2162,2192),
        (2192,2278),
        (2278,2337),
        (2337,2397),
        (2397,2408),
        (2408,2593),
        (2593,2613),
        (2613,2672),
        (2672,2934),
        (2934,3273),
        (3273,3297),
        (3297,3346),
        (3346,3357),
        (3357,3618),
        (3618,3638),
        (3638,3941)
      )
      terminatedAddresses should be (precalculatedTerminatedAddresses)
      addresses should be (precalculatedAddresses)
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

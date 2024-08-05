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
  test("Test projectService.recalculateProjectLinks() Real life project on road parts 4/218, 4/219 and 11960/1, recalculation should complete successfully") {
    /**
     * There is more info of the project available on the Jira ticket VIITE-3172
     * */
    runWithRollback {
      // random road part numbers
      val roadPart1 = RoadPart(49529,1)
      val roadPart2 = RoadPart(49529,2)
      val roadPart3 = RoadPart(11000,1)
      val projectId = Sequences.nextViiteProjectId
      val roadName = Some("testRoad")

      val roadways = Seq(
        Roadway(7609,45461,roadPart1,AdministrativeClass.State,Track.Combined,Discontinuity.Continuous,AddrMRange(0, 6104),false,DateTime.now().minusDays(2),None,"test",None,14,TerminationCode.NoTermination,DateTime.now().minusDays(2),None),
        Roadway(7691,45462,roadPart2,AdministrativeClass.State,Track.Combined,Discontinuity.Continuous,AddrMRange(0, 5624),false,DateTime.now().minusDays(2),None,"test",None,14,TerminationCode.NoTermination,DateTime.now().minusDays(2),None),
        Roadway(7506,45463,roadPart2,AdministrativeClass.State,Track.Combined,Discontinuity.ChangingELYCode,AddrMRange(5624, 6565),false,DateTime.now().minusDays(2),None,"test",None,14,TerminationCode.NoTermination,DateTime.now().minusDays(2),None)
      )

      val linearLocations = Seq(
        LinearLocation(64599, 15.0,"ea6dfc06-c165-4af4-b2cc-5c131e0a94fd:2",0.0,92.812,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(448357.061,6834772.29,0.0), Point(448404.082,6834852.292,0.0)),FrozenLinkInterface,45461,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(63155, 5.0,"9936c53b-7f27-4318-9764-e99c1f3bc247:2",0.0,17.54,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(450628.419,6842574.262,0.0), Point(450640.28,6842587.183,0.0)),FrozenLinkInterface,45463,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(69471, 8.0,"463b6067-7332-4e74-8b09-caa03a5fdc48:2",0.0,82.804,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(450220.873,6840188.085,0.0), Point(450225.52,6840270.738,0.0)),FrozenLinkInterface,45462,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(64603, 19.0,"22ae11c6-9100-486c-9f9e-34c627d78503:2",0.0,141.458,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(448489.174,6835078.099,0.0), Point(448515.679,6835217.016,0.0)),FrozenLinkInterface,45461,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(69473, 10.0,"495d363a-ff22-4454-99de-c901d015765c:2",0.0,560.618,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(450225.37,6840287.178,0.0), Point(450128.11,6840837.962,0.0)),FrozenLinkInterface,45462,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(69467, 4.0,"32ab31f5-72cb-40b1-9084-7fa6d2e767df:3",0.0,690.929,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(449417.707,6838214.883,0.0), Point(449825.128,6838769.591,0.0)),FrozenLinkInterface,45462,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(69476, 13.0,"38009a7e-cf41-4c74-8b34-8d02b17c1ba6:2",0.0,337.782,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(450156.362,6841166.55,0.0), Point(450219.836,6841498.31,0.0)),FrozenLinkInterface,45462,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(69477, 14.0,"c94130a2-8e1f-469d-b260-2cd075681fd3:2",0.0,247.817,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(450219.836,6841498.31,0.0), Point(450266.716,6841741.65,0.0)),FrozenLinkInterface,45462,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(64595, 11.0,"0cc15300-e429-49c1-b8a6-bc80bacab94e:2",0.0,134.225,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(447653.485,6833212.373,0.0), Point(447692.612,6833340.762,0.0)),FrozenLinkInterface,45461,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(63152, 2.0,"7e41bae0-0eb2-48e4-afe7-6c430754fa11:2",0.0,316.293,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(450298.444,6841903.44,0.0), Point(450392.653,6842204.595,0.0)),FrozenLinkInterface,45463,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(69472, 9.0,"062e7ea8-8db0-4f9c-8a5d-a5ae9c607135:2",0.0,16.441,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(450225.52,6840270.738,0.0), Point(450225.37,6840287.178,0.0)),FrozenLinkInterface,45462,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(69470, 7.0,"2aaa2a30-9254-49aa-96e5-2c7bca77db20:1",0.0,904.273,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(450005.511,6839312.048,0.0), Point(450220.873,6840188.085,0.0)),FrozenLinkInterface,45462,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(64600, 16.0,"793c341e-3b1c-455c-b273-f6a75bfd60e1:2",0.0,87.95,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(448404.082,6834852.292,0.0), Point(448442.423,6834931.382,0.0)),FrozenLinkInterface,45461,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(63154, 4.0,"abb561d6-17f3-437b-adfc-71b29de5a831:2",0.0,141.5,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(450539.994,6842463.853,0.0), Point(450628.419,6842574.262,0.0)),FrozenLinkInterface,45463,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(69474, 11.0,"415fb07d-93be-49bf-8bc4-a3cc9a399655:2",0.0,12.009,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(450128.11,6840837.962,0.0), Point(450126.862,6840849.906,0.0)),FrozenLinkInterface,45462,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(64590, 6.0,"891806b2-0ff8-4d77-a993-a035a7c9436e:2",0.0,638.814,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(447359.915,6831635.529,0.0), Point(447481.261,6832262.548,0.0)),FrozenLinkInterface,45461,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(64606, 22.0,"cd96b2ea-e4ee-464e-b035-c37f92e36755:2",0.0,48.17,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(448521.832,6835272.25,0.0), Point(448529.028,6835319.879,0.0)),FrozenLinkInterface,45461,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(64608, 24.0,"1602fe80-9c00-4b24-908b-05ac935b52cb:2",0.0,27.54,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(448478.354,6836423.344,0.0), Point(448480.859,6836450.765,0.0)),FrozenLinkInterface,45461,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(64589, 5.0,"039e9137-c5e2-4a44-a8eb-e386db4f59ca:3",0.0,226.156,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(447338.275,6831410.64,0.0), Point(447359.915,6831635.529,0.0)),FrozenLinkInterface,45461,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(64586, 2.0,"59950e35-d026-4e0b-9cf0-8840501348a6:2",0.0,421.346,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(447414.936,6830836.845,0.0), Point(447344.657,6831251.931,0.0)),FrozenLinkInterface,45461,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(64596, 12.0,"3b8cc608-7ec6-4da6-8e54-4ba40d320f55:2",0.0,350.643,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(447692.612,6833340.762,0.0), Point(447797.029,6833675.494,0.0)),FrozenLinkInterface,45461,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(69466, 3.0,"e9d243b3-2791-441f-a9e8-851e4d643c78:2",0.0,1074.646,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(448872.114,6837289.287,0.0), Point(449417.707,6838214.883,0.0)),FrozenLinkInterface,45462,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(69475, 12.0,"0e89c739-4df6-4ff3-a896-a5d33d5b7301:2",0.0,319.237,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(450126.862,6840849.906,0.0), Point(450156.362,6841166.55,0.0)),FrozenLinkInterface,45462,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(64597, 13.0,"e8a19080-d21f-4a1e-9621-d177e823d7c0:2",0.0,123.634,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(447797.029,6833675.494,0.0), Point(447835.152,6833793.104,0.0)),FrozenLinkInterface,45461,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(63153, 3.0,"d68baa70-21e7-42c1-ad75-ceb734be907d:2",0.0,298.875,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(450392.653,6842204.595,0.0), Point(450539.994,6842463.853,0.0)),FrozenLinkInterface,45463,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(69469, 6.0,"6344c2d6-e3fa-4e1f-86c6-ed6be3696669:2",0.0,36.298,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(449992.736,6839278.077,0.0), Point(450005.511,6839312.048,0.0)),FrozenLinkInterface,45462,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(64588, 4.0,"25cec492-f1f3-46f6-9b75-2a3671373410:2",0.0,106.503,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(447339.431,6831304.161,0.0), Point(447338.275,6831410.64,0.0)),FrozenLinkInterface,45461,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(64593, 9.0,"4d422287-661b-4a1e-a51e-bb97acdb6c1f:2",0.0,388.83,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(447542.088,6832579.833,0.0), Point(447599.525,6832964.395,0.0)),FrozenLinkInterface,45461,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(69464, 1.0,"32b46611-878d-4bf7-949e-7ca6499edca2:2",0.0,532.574,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(448508.427,6836588.119,0.0), Point(448740.951,6837064.619,0.0)),FrozenLinkInterface,45462,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(69465, 2.0,"2ffae796-a6e5-472e-89e1-8c47d70db815:2",0.0,260.159,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(448740.951,6837064.619,0.0), Point(448872.114,6837289.287,0.0)),FrozenLinkInterface,45462,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(64592, 8.0,"f07829d3-7c4c-4013-8f36-25cc5e85fb3c:2",0.0,319.057,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(447482.15,6832266.545,0.0), Point(447542.088,6832579.833,0.0)),FrozenLinkInterface,45461,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(64607, 23.0,"11e6ed2b-0f76-4120-8193-c8b3cfe75828:1",0.0,1108.889,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(448529.028,6835319.879,0.0), Point(448478.354,6836423.344,0.0)),FrozenLinkInterface,45461,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(64602, 18.0,"d0f88407-5ab9-4107-8668-dffc22c3b035:2",0.0,108.633,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(448459.047,6834973.82,0.0), Point(448489.174,6835078.099,0.0)),FrozenLinkInterface,45461,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(64605, 21.0,"fc4f1111-5e0e-4d18-ae28-f7fc54c8c241:2",0.0,26.831,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(448519.333,6835245.536,0.0), Point(448521.832,6835272.25,0.0)),FrozenLinkInterface,45461,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(64585, 1.0,"79203f00-9824-4b55-afec-c10cba758793:2",0.0,102.658,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(447426.942,6830734.897,0.0), Point(447414.936,6830836.845,0.0)),FrozenLinkInterface,45461,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(64594, 10.0,"4925df77-09d4-4576-b669-546c86a98848:2",0.0,254.026,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(447599.525,6832964.395,0.0), Point(447653.485,6833212.373,0.0)),FrozenLinkInterface,45461,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(69468, 5.0,"bb10c163-a155-4472-8998-6c57a7519276:2",0.0,535.631,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(449825.128,6838769.591,0.0), Point(449992.736,6839278.077,0.0)),FrozenLinkInterface,45462,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(64609, 25.0,"99d4b5b4-ccf7-43a8-99b5-31e00e72dd41:2",0.0,140.236,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(448480.859,6836450.765,0.0), Point(448508.427,6836588.119,0.0)),FrozenLinkInterface,45461,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(64587, 3.0,"9a161e1e-f8bb-4ee8-b0f9-d429fbbf3d88:2",0.0,52.491,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(447344.657,6831251.931,0.0), Point(447339.431,6831304.161,0.0)),FrozenLinkInterface,45461,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(64604, 20.0,"2026d878-c0af-4580-b3e0-dd0525d788bb:2",0.0,28.753,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(448515.679,6835217.016,0.0), Point(448519.333,6835245.536,0.0)),FrozenLinkInterface,45461,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(63151, 1.0,"c94130a2-8e1f-469d-b260-2cd075681fd3:2",247.817,412.695,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(450266.716,6841741.65,0.0), Point(450298.444,6841903.44,0.0)),FrozenLinkInterface,45463,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(64591, 7.0,"77b09a30-9766-4044-968e-5bcc56bcbe38:2",0.0,4.095,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(447481.261,6832262.548,0.0), Point(447482.15,6832266.545,0.0)),FrozenLinkInterface,45461,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(64598, 14.0,"438b6014-34ae-42c7-af77-2bbc6b84b04b:3",0.0,1119.359,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(447835.152,6833793.104,0.0), Point(448357.061,6834772.29,0.0)),FrozenLinkInterface,45461,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(64601, 17.0,"cf2f5e32-9f38-43fe-b178-78004b8e931e:2",0.0,45.586,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(448442.423,6834931.382,0.0), Point(448459.047,6834973.82,0.0)),FrozenLinkInterface,45461,Some(DateTime.now().minusDays(2)),None)
      )

      val projectLinks = Seq(
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.Combined,Discontinuity.Continuous,AddrMRange(0,0),AddrMRange(0,0),None,None,Some("test"),"3c7ae9a6-8e0b-4ef3-85df-21c850145727:2", 0.0,97.605,SideCode.Unknown,(NoCP, NoCP), (NoCP, NoCP),List(Point(448357.0609999991,6834772.290000221,98.351), Point(448395.7529999993,6834828.65200022,97.767), Point(448411.3929999992,6834853.358000221,97.687)),projectId,  RoadAddressChangeType.New,AdministrativeClass.State, FrozenLinkInterface,97.605,0,0,1,reversed = false,None,1652179948783L,0,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.LeftSide,Discontinuity.Continuous,AddrMRange(0,0),AddrMRange(0,0),None,None,Some("test"),"d4263e14-b1b9-4bcf-a467-6fc703378d08:2", 0.0,46.63,SideCode.Unknown,(NoCP, NoCP), (NoCP, NoCP),List(Point(449737.0359999993,6838622.117000224,103.723), Point(449755.7109999994,6838653.135000222,102.59), Point(449763.3259999993,6838660.254000222,101.576)),projectId,  RoadAddressChangeType.New,AdministrativeClass.State, FrozenLinkInterface,46.63,0,0,1,reversed = false,None,1652179948783L,0,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.RightSide,Discontinuity.Continuous,AddrMRange(0,0),AddrMRange(0,0),None,None,Some("test"),"25f9bbe3-f001-4cd2-8f9a-457e962a7b8a:2", 0.0,2777.718,SideCode.Unknown,(NoCP, NoCP), (NoCP, NoCP),List(Point(448760.1459999993,6836136.792000221,113.326), Point(448765.1979999992,6836149.638000224,108.409), Point(448770.5159999994,6836189.371000221,108.745), Point(448774.2569999992,6836222.591000221,109.165), Point(448780.4079999992,6836277.66300022,111.43), Point(448783.9389999993,6836309.269000221,110.429), Point(448788.4499999993,6836349.6520002205,110.933), Point(448792.3489999993,6836384.557000223,111.347), Point(448801.6429999992,6836467.762000224,115.766), Point(448807.3089999993,6836518.491000222,112.952), Point(448815.3799999993,6836590.743000221,113.849), Point(448820.3669999993,6836635.388000221,114.404), Point(448831.1599999992,6836732.011000221,115.242), Point(448834.7129999992,6836763.816000223,116.092), Point(448844.7149999995,6836853.230000221,117.183), Point(448849.2579999992,6836892.208000223,117.64), Point(448856.2739999992,6836946.073000222,118.288), Point(448860.9189999993,6836977.130000221,118.656), Point(448864.9109999992,6837001.438000222,118.975), Point(448867.5129999994,6837016.351000222,119.131), Point(448875.2589999992,6837057.329000222,119.654), Point(448880.7559999993,6837083.884000221,119.787), Point(448885.4559999993,6837105.268000223,120.037), Point(448895.8659999993,6837149.103000222,120.183), Point(448902.6279999993,6837175.4530002205,120.258), Point(448913.9609999993,6837216.58400022,120.771), Point(448923.0329999993,6837247.258000222,124.723), Point(448938.9279999994,6837297.114000223,120.606), Point(448947.7699999992,6837323.04200022,120.626), Point(448956.2029999994,6837346.748000223,120.464), Point(448965.7709999993,6837372.555000222,120.36), Point(448974.9409999991,6837396.322000222,122.285), Point(448985.4799999994,6837422.570000221,121.554), Point(449005.8689999995,6837470.537000221,120.604), Point(449014.4519999993,6837489.745000222,122.998), Point(449024.9899999992,6837512.609000221,119.432), Point(449043.6399999995,6837551.323000222,119.811), Point(449058.5899999994,6837580.905000221,119.607), Point(449069.5979999993,6837601.935000221,119.35), Point(449084.3439999993,6837629.190000224,118.993), Point(449100.0789999993,6837657.258000222,118.61), Point(449114.2679999993,6837681.917000222,118.277), Point(449133.3879999992,6837714.554000223,117.816), Point(449156.6989999993,6837754.006000221,117.305), Point(449167.5599999995,6837772.378000223,117.052), Point(449200.7179999993,6837829.0280002225,116.298), Point(449238.2839999994,6837896.685000223,115.337), Point(449266.7329999994,6837943.64100022,113.823), Point(449296.1199999993,6837993.359000224,112.509), Point(449316.2239999993,6838026.86700022,111.408), Point(449345.2489999993,6838075.906000224,110.116), Point(449376.9459999993,6838127.888000222,107.135), Point(449390.8919999992,6838149.080000222,106.568), Point(449416.6569999995,6838188.338000221,105.324), Point(449444.9119999993,6838228.910000224,103.687), Point(449470.2699999995,6838262.961000222,102.523), Point(449486.6159999992,6838284.0620002225,101.989), Point(449516.8629999995,6838323.003000221,101.21), Point(449543.8509999994,6838356.148000222,100.835), Point(449585.7809999994,6838406.818000223,100.667), Point(449647.5439999993,6838481.440000223,100.649), Point(449670.9539999994,6838512.0050002225,100.453), Point(449695.9489999995,6838547.143000222,101.233), Point(449720.7629999994,6838583.005000223,101.603), Point(449734.8899999996,6838604.830000224,101.56), Point(449762.4209999993,6838651.017000222,102.003), Point(449763.3259999993,6838660.254000222,101.576)),projectId,  RoadAddressChangeType.New,AdministrativeClass.State, FrozenLinkInterface,2777.718,0,0,1,reversed = false,None,1652179948783L,0,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.Combined,Discontinuity.Continuous,AddrMRange(0,0),AddrMRange(0,0),None,None,Some("test"),"a99406dc-fb7b-4450-a9d5-3dcecd7a3024:2", 0.0,981.89,SideCode.Unknown,(NoCP, NoCP), (NoCP, NoCP),List(Point(448575.0659999993,6835176.474000222,100.074), Point(448607.3979999994,6835261.935000223,100.536), Point(448632.7979999993,6835338.855000223,101.124), Point(448646.9839999993,6835386.390000221,101.457), Point(448657.4869999993,6835424.705000221,101.745), Point(448666.7629999993,6835461.240000223,102.049), Point(448680.9479999993,6835523.593000221,102.505), Point(448687.3309999993,6835554.75300022,102.878), Point(448695.6879999994,6835599.809000222,107.31), Point(448701.6259999993,6835635.72200022,108.511), Point(448706.7439999995,6835669.6360002225,103.834), Point(448713.1969999992,6835719.048000219,104.337), Point(448717.9689999993,6835759.511000221,104.7), Point(448729.2759999993,6835860.432000221,105.381), Point(448732.5199999994,6835889.474000222,105.673), Point(448742.8839999995,6835982.261000221,106.637), Point(448752.3409999993,6836066.036000221,107.465), Point(448760.1459999993,6836136.792000221,113.326)),projectId,  RoadAddressChangeType.New,AdministrativeClass.State, FrozenLinkInterface,981.89,0,0,1,reversed = false,None,1652179948783L,0,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.Combined,Discontinuity.Continuous,AddrMRange(0,0),AddrMRange(0,0),None,None,Some("test"),"3c76d4c7-2378-48e9-b662-8483e3a484c4:2", 0.0,306.161,SideCode.Unknown,(NoCP, NoCP), (NoCP, NoCP),List(Point(448440.5249999993,6834901.699000222,97.39), Point(448492.4349999993,6834998.74300022,106.694), Point(448528.2639999992,6835068.96600022,98.836), Point(448544.1249999992,6835103.41500022,99.224), Point(448564.1719999994,6835149.734000222,99.695), Point(448575.0659999993,6835176.474000222,100.074)),projectId,  RoadAddressChangeType.New,AdministrativeClass.State, FrozenLinkInterface,306.161,0,0,1,reversed = false,None,1652179948783L,0,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.Combined,Discontinuity.Continuous,AddrMRange(0,0),AddrMRange(0,0),None,None,Some("test"),"465f7b35-a50d-4b53-89ac-e01d192b3ba3:2", 0.0,56.44,SideCode.Unknown,(NoCP, NoCP), (NoCP, NoCP),List(Point(448411.3929999992,6834853.358000221,97.687), Point(448421.0869999993,6834869.443000219,97.589), Point(448440.5249999993,6834901.699000222,97.39)),projectId,  RoadAddressChangeType.New,AdministrativeClass.State, FrozenLinkInterface,56.44,0,0,1,reversed = false,None,1652179948783L,0,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.Combined,Discontinuity.Continuous,AddrMRange(0,0),AddrMRange(0,0),None,None,Some("test"),"f80f78d8-e785-4c27-aa0b-b2f1d514b0c3:2", 0.0,409.076,SideCode.Unknown,(NoCP, NoCP), (NoCP, NoCP),List(Point(449773.7749999993,6838679.340000223,100.098), Point(449808.8589999996,6838754.029000222,99.188), Point(449823.2899999994,6838785.410000224,100.079), Point(449850.7839999994,6838851.742000221,99.917), Point(449861.7839999993,6838881.330000223,100.014), Point(449888.9609999993,6838962.2050002245,100.596), Point(449918.5649999994,6839061.200000223,101.057)),projectId,  RoadAddressChangeType.New,AdministrativeClass.State, FrozenLinkInterface,409.076,0,0,1,reversed = false,None,1652179948783L,0,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.Combined,Discontinuity.Continuous,AddrMRange(0,0),AddrMRange(0,0),None,None,Some("test"),"ed016285-9512-48a7-a204-c095000c6cb6:2", 0.0,21.759,SideCode.Unknown,(NoCP, NoCP), (NoCP, NoCP),List(Point(449763.3259999993,6838660.254000222,101.576), Point(449773.7749999993,6838679.340000223,100.098)),projectId,  RoadAddressChangeType.New,AdministrativeClass.State, FrozenLinkInterface,21.759,0,0,1,reversed = false,None,1652179948783L,0,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.LeftSide,Discontinuity.Continuous,AddrMRange(0,0),AddrMRange(0,0),None,None,Some("test"),"be50fd46-325d-4edc-8c3c-4838dd8afffe:2", 0.0,2734.104,SideCode.Unknown,(NoCP, NoCP), (NoCP, NoCP),List(Point(448760.1459999993,6836136.792000221,113.326), Point(448759.2259999995,6836148.44100022,111.655), Point(448763.3309999993,6836192.114000223,109.534), Point(448768.5289999992,6836252.357000221,110.768), Point(448775.0729999992,6836310.938000222,110.457), Point(448783.6319999993,6836387.568000221,111.398), Point(448789.0859999992,6836436.389000221,112.001), Point(448800.9509999993,6836542.613000221,113.286), Point(448811.0139999994,6836632.695000222,114.63), Point(448815.6409999993,6836674.120000224,118.906), Point(448827.4559999992,6836779.893000223,116.399), Point(448829.7269999993,6836800.226000222,116.575), Point(448835.3889999995,6836850.8170002205,117.229), Point(448842.4239999992,6836910.280000222,118.122), Point(448849.3579999993,6836961.069000221,118.729), Point(448855.1039999995,6836997.400000222,119.182), Point(448860.4799999992,6837027.525000222,119.52), Point(448862.5819999993,6837039.306000219,119.652), Point(448874.5339999993,6837097.618000221,120.204), Point(448876.8279999993,6837107.818000221,120.331), Point(448878.2899999992,6837114.321000222,120.412), Point(448890.5429999993,6837164.729000221,120.739), Point(448907.1169999995,6837225.309000223,122.236), Point(448920.9619999993,6837270.942000221,121.685), Point(448932.8429999993,6837307.267000221,120.658), Point(448951.8709999992,6837361.081000221,120.641), Point(448968.5959999995,6837404.756000222,120.415), Point(448979.7209999993,6837432.220000222,120.289), Point(448997.5279999994,6837473.924000223,123.111), Point(449014.8379999993,6837512.1470002225,119.725), Point(449031.3479999993,6837546.725000221,119.735), Point(449056.1189999993,6837595.660000222,118.481), Point(449083.3569999994,6837645.939000223,117.627), Point(449112.5729999992,6837696.878000221,117.209), Point(449150.5999999993,6837761.3680002205,116.579), Point(449174.8069999992,6837802.315000222,116.096), Point(449201.0349999992,6837846.680000222,115.658), Point(449220.6249999993,6837879.8170002205,115.274), Point(449246.0549999996,6837922.832000222,114.659), Point(449273.5099999994,6837969.273000222,113.74), Point(449300.6209999993,6838015.131000222,112.272), Point(449328.7069999994,6838062.560000222,110.218), Point(449348.9609999993,6838096.267000223,109.139), Point(449374.9209999993,6838138.092000223,107.083), Point(449407.0629999994,6838187.041000221,104.944), Point(449422.7699999995,6838209.868000221,103.718), Point(449447.4249999993,6838244.399000222,101.651), Point(449466.9329999993,6838270.666000223,100.355), Point(449501.6389999994,6838315.314000222,100.007), Point(449531.3399999996,6838351.998000223,100.021), Point(449585.7439999994,6838417.904000222,99.834), Point(449622.2649999996,6838462.232000223,100.099), Point(449671.7949999993,6838525.813000224,100.985), Point(449710.4309999994,6838581.051000224,100.945), Point(449737.0359999993,6838622.117000224,103.723)),projectId,  RoadAddressChangeType.New,AdministrativeClass.State, FrozenLinkInterface,2734.104,0,0,1,reversed = false,None,1652179948783L,0,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.Combined,Discontinuity.Continuous,AddrMRange(0,103),AddrMRange(0,103),None,None,Some("test"),"79203f00-9824-4b55-afec-c10cba758793:2", 0.0,102.658,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(447426.942,6830734.897,101.498), Point(447415.5339999992,6830833.463000217,101.253), Point(447414.936,6830836.845,101.24700077725485)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.State, FrozenLinkInterface,102.658,7609,64585,1,reversed = false,None,1652179948783L,45461,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.Combined,Discontinuity.Continuous,AddrMRange(103,524),AddrMRange(103,524),None,None,Some("test"),"59950e35-d026-4e0b-9cf0-8840501348a6:2", 0.0,421.346,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(447414.936,6830836.845,101.247), Point(447399.7059999991,6830922.748000219,100.928), Point(447380.2059999991,6831011.854000219,100.501), Point(447367.4509999994,6831078.50700022,100.435), Point(447357.064999999,6831137.020000218,100.711), Point(447348.1969999991,6831209.587000219,101.15), Point(447344.657,6831251.931,101.33899999319503)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.State, FrozenLinkInterface,421.346,7609,64586,1,reversed = false,None,1652179948783L,45461,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.Combined,Discontinuity.Continuous,AddrMRange(524,577),AddrMRange(524,577),None,None,Some("test"),"9a161e1e-f8bb-4ee8-b0f9-d429fbbf3d88:2", 0.0,52.491,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(447344.657,6831251.931,101.339), Point(447339.4309999991,6831304.161000219,101.65)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.State, FrozenLinkInterface,52.491,7609,64587,1,reversed = false,None,1652179948783L,45461,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.Combined,Discontinuity.Continuous,AddrMRange(577,684),AddrMRange(577,684),None,None,Some("test"),"25cec492-f1f3-46f6-9b75-2a3671373410:2", 0.0,106.503,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(447339.431,6831304.161,101.65), Point(447338.648999999,6831330.011000219,101.791), Point(447338.1899999992,6831355.224000221,101.928), Point(447338.3359999992,6831386.504000219,102.109), Point(447338.5619999991,6831403.42200022,102.198), Point(447338.2749999991,6831410.640000219,102.224)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.State, FrozenLinkInterface,106.503,7609,64588,1,reversed = false,None,1652179948783L,45461,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.Combined,Discontinuity.Continuous,AddrMRange(684,910),AddrMRange(684,910),None,None,Some("test"),"039e9137-c5e2-4a44-a8eb-e386db4f59ca:3", 0.0,226.156,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(447338.275,6831410.64,102.224), Point(447339.3309999994,6831450.543000219,102.404), Point(447341.9999999992,6831487.9020002205,102.553), Point(447345.5939999992,6831531.01000022,102.664), Point(447349.8449999992,6831570.93100022,102.741), Point(447356.6229999992,6831615.972000219,102.788), Point(447359.9149999991,6831635.529000218,102.846)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.State, FrozenLinkInterface,226.156,7609,64589,1,reversed = false,None,1652179948783L,45461,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.Combined,Discontinuity.Continuous,AddrMRange(910,1549),AddrMRange(910,1549),None,None,Some("test"),"891806b2-0ff8-4d77-a993-a035a7c9436e:2", 0.0,638.814,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(447359.915,6831635.529,102.846), Point(447363.3229999991,6831654.382000222,102.811), Point(447366.7169999994,6831675.944000222,102.784), Point(447372.7719999992,6831707.416000219,102.788), Point(447378.9489999992,6831745.911000223,102.771), Point(447384.3749999992,6831775.4640002195,102.72), Point(447391.6709999992,6831813.6460002195,102.69), Point(447396.9329999992,6831844.156000218,102.628), Point(447402.0449999992,6831872.750000219,102.632), Point(447407.3059999991,6831903.420000221,102.574), Point(447408.6659999991,6831913.339000219,102.569), Point(447418.8959999993,6831969.72900022,102.357), Point(447430.2349999992,6832027.722000221,102.168), Point(447442.4369999993,6832090.526000219,101.762), Point(447449.7439999991,6832123.227000221,101.506), Point(447460.4879999991,6832173.400000221,101.175), Point(447472.3579999992,6832224.20400022,100.868), Point(447481.261,6832262.548,100.69800038193654)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.State, FrozenLinkInterface,638.814,7609,64590,1,reversed = false,None,1652179948783L,45461,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.Combined,Discontinuity.Continuous,AddrMRange(1549,1553),AddrMRange(1549,1553),None,None,Some("test"),"77b09a30-9766-4044-968e-5bcc56bcbe38:2", 0.0,4.095,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(447481.261,6832262.548,100.698), Point(447482.1499999992,6832266.545000219,100.689)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.State, FrozenLinkInterface,4.095,7609,64591,1,reversed = false,None,1652179948783L,45461,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.Combined,Discontinuity.Continuous,AddrMRange(1553,1873),AddrMRange(1553,1873),None,None,Some("test"),"f07829d3-7c4c-4013-8f36-25cc5e85fb3c:2", 0.0,319.057,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(447482.15,6832266.545,100.689), Point(447494.9649999992,6832324.221000219,100.542), Point(447511.1669999992,6832400.280000221,100.269), Point(447526.7869999993,6832484.98300022,100.033), Point(447536.0619999991,6832540.830000218,99.845), Point(447541.2069999991,6832574.33800022,99.775), Point(447541.4199999991,6832575.67700022,99.773), Point(447542.088,6832579.833,99.74400046480197)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.State, FrozenLinkInterface,319.057,7609,64592,1,reversed = false,None,1652179948783L,45461,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.Combined,Discontinuity.Continuous,AddrMRange(1873,2262),AddrMRange(1873,2262),None,None,Some("test"),"4d422287-661b-4a1e-a51e-bb97acdb6c1f:2", 0.0,388.83,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(447542.088,6832579.833,99.744), Point(447551.8349999993,6832648.253000221,99.428), Point(447567.7649999994,6832755.19700022,99.168), Point(447582.1709999991,6832849.66900022,98.902), Point(447599.5249999992,6832964.39500022,98.84)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.State, FrozenLinkInterface,388.83,7609,64593,1,reversed = false,None,1652179948783L,45461,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.Combined,Discontinuity.Continuous,AddrMRange(2262,2516),AddrMRange(2262,2516),None,None,Some("test"),"4925df77-09d4-4576-b669-546c86a98848:2", 0.0,254.026,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(447599.525,6832964.395,98.84), Point(447606.9979999992,6833013.628000221,98.907), Point(447613.8029999991,6833049.26600022,98.86), Point(447619.4419999992,6833078.3500002185,98.937), Point(447627.2019999991,6833113.341000219,99.08), Point(447637.5759999992,6833155.995000222,99.333), Point(447646.9329999992,6833190.33500022,99.529), Point(447653.485,6833212.373,99.71299737959451)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.State, FrozenLinkInterface,254.026,7609,64594,1,reversed = false,None,1652179948783L,45461,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.Combined,Discontinuity.Continuous,AddrMRange(2516,2651),AddrMRange(2516,2651),None,None,Some("test"),"0cc15300-e429-49c1-b8a6-bc80bacab94e:2", 0.0,134.225,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(447653.485,6833212.373,99.713), Point(447660.6989999992,6833237.60700022,99.888), Point(447671.8259999992,6833273.375000219,100.149), Point(447682.1309999993,6833305.949000221,100.505), Point(447689.3369999992,6833329.902000219,100.72), Point(447692.612,6833340.762,100.7999972189118)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.State, FrozenLinkInterface,134.225,7609,64595,1,reversed = false,None,1652179948783L,45461,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.Combined,Discontinuity.Continuous,AddrMRange(2651,3001),AddrMRange(2651,3001),None,None,Some("test"),"3b8cc608-7ec6-4da6-8e54-4ba40d320f55:2", 0.0,350.643,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(447692.612,6833340.762,100.8), Point(447693.0869999992,6833342.285000223,100.838), Point(447703.6559999991,6833376.570000221,101.228), Point(447716.6449999992,6833417.491000221,101.809), Point(447730.9339999993,6833463.209000219,102.943), Point(447742.6219999992,6833499.814000221,104.126), Point(447762.6019999992,6833565.03500022,106.627), Point(447782.4109999992,6833628.017000221,109.25), Point(447797.029,6833675.494,111.15599885368856)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.State, FrozenLinkInterface,350.643,7609,64596,1,reversed = false,None,1652179948783L,45461,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.Combined,Discontinuity.Continuous,AddrMRange(3001,3125),AddrMRange(3001,3125),None,None,Some("test"),"e8a19080-d21f-4a1e-9621-d177e823d7c0:2", 0.0,123.634,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(447797.029,6833675.494,111.156), Point(447807.2529999992,6833706.98400022,112.396), Point(447823.1849999992,6833756.16600022,114.314), Point(447835.152,6833793.104,115.58698534251344)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.State, FrozenLinkInterface,123.634,7609,64597,1,reversed = false,None,1652179948783L,45461,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.Combined,Discontinuity.Continuous,AddrMRange(3125,4246),AddrMRange(3125,4246),None,None,Some("test"),"438b6014-34ae-42c7-af77-2bbc6b84b04b:3", 0.0,1119.359,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(447835.152,6833793.104,115.587), Point(447856.1449999992,6833860.708000221,117.526), Point(447874.8189999992,6833920.49300022,118.817), Point(447897.7229999993,6833995.467000222,119.778), Point(447911.5099999992,6834035.90400022,120.1), Point(447940.1969999991,6834115.65400022,120.015), Point(447978.3629999993,6834203.365000221,119.084), Point(448009.5249999993,6834263.89400022,117.937), Point(448046.9569999993,6834330.798000222,116.018), Point(448083.5499999993,6834388.90100022,114.098), Point(448112.0679999992,6834431.033000221,112.633), Point(448151.4189999991,6834486.575000222,110.631), Point(448177.9839999992,6834521.51200022,109.401), Point(448210.8419999994,6834567.627000222,107.519), Point(448235.3199999992,6834601.294000221,105.908), Point(448254.8509999994,6834628.367000219,104.563), Point(448280.2629999991,6834662.933000221,102.799), Point(448310.8989999992,6834705.596000219,100.659), Point(448357.0609999991,6834772.290000221,98.351)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.State, FrozenLinkInterface,1119.359,7609,64598,1,reversed = false,None,1652179948783L,45461,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.Combined,Discontinuity.Continuous,AddrMRange(4246,4338),AddrMRange(4246,4338),None,None,Some("test"),"ea6dfc06-c165-4af4-b2cc-5c131e0a94fd:2", 0.0,92.812,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(448357.061,6834772.29,98.351), Point(448373.7139999991,6834799.06000022,97.903), Point(448404.082,6834852.292,97.59400069857368)),projectId,  RoadAddressChangeType.Termination,AdministrativeClass.State, FrozenLinkInterface,92.812,7609,64599,1,reversed = false,None,1652179948783L,45461,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.Combined,Discontinuity.Continuous,AddrMRange(4338,4426),AddrMRange(4338,4426),None,None,Some("test"),"793c341e-3b1c-455c-b273-f6a75bfd60e1:2", 0.0,87.95,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(448404.082,6834852.292,97.594), Point(448419.6529999993,6834881.060000218,97.564), Point(448426.3999999993,6834895.540000222,97.54), Point(448433.1449999992,6834910.0160002215,97.516), Point(448442.423,6834931.382,97.45900047757775)),projectId,  RoadAddressChangeType.Termination,AdministrativeClass.State, FrozenLinkInterface,87.95,7609,64600,1,reversed = false,None,1652179948783L,45461,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.Combined,Discontinuity.Continuous,AddrMRange(4426,4472),AddrMRange(4426,4472),None,None,Some("test"),"cf2f5e32-9f38-43fe-b178-78004b8e931e:2", 0.0,45.586,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(448442.423,6834931.382,97.459), Point(448452.0839999992,6834954.864000223,97.391), Point(448459.047,6834973.82,97.36700012363468)),projectId,  RoadAddressChangeType.Termination,AdministrativeClass.State, FrozenLinkInterface,45.586,7609,64601,1,reversed = false,None,1652179948783L,45461,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.Combined,Discontinuity.Continuous,AddrMRange(4472,4581),AddrMRange(4472,4581),None,None,Some("test"),"d0f88407-5ab9-4107-8668-dffc22c3b035:2", 0.0,108.633,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(448459.047,6834973.82,97.367), Point(448471.4979999993,6835010.65100022,97.3), Point(448480.4069999994,6835040.736000219,97.252), Point(448489.1739999992,6835078.09900022,97.192)),projectId,  RoadAddressChangeType.Termination,AdministrativeClass.State, FrozenLinkInterface,108.633,7609,64602,1,reversed = false,None,1652179948783L,45461,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.Combined,Discontinuity.Continuous,AddrMRange(4581,4722),AddrMRange(4581,4722),None,None,Some("test"),"22ae11c6-9100-486c-9f9e-34c627d78503:2", 0.0,141.458,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(448489.174,6835078.099,97.192), Point(448498.3719999991,6835122.151000219,97.164), Point(448509.4369999991,6835176.7890002215,96.97), Point(448515.6789999992,6835217.016000221,96.689)),projectId,  RoadAddressChangeType.Termination,AdministrativeClass.State, FrozenLinkInterface,141.458,7609,64603,1,reversed = false,None,1652179948783L,45461,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.Combined,Discontinuity.Continuous,AddrMRange(4722,4751),AddrMRange(4722,4751),None,None,Some("test"),"2026d878-c0af-4580-b3e0-dd0525d788bb:2", 0.0,28.753,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(448515.679,6835217.016,96.689), Point(448519.333,6835245.536,96.57700048140815)),projectId,  RoadAddressChangeType.Termination,AdministrativeClass.State, FrozenLinkInterface,28.753,7609,64604,1,reversed = false,None,1652179948783L,45461,roadName,None,None,None,None),

        ProjectLink(Sequences.nextProjectLinkId, roadPart2,Track.Combined,Discontinuity.Continuous,AddrMRange(0,0),AddrMRange(0,0),None,None,Some("test"),"9c3fc7de-ee59-4ec5-8a6f-46e411dd7700:2", 0.0,265.489,SideCode.Unknown,(NoCP, NoCP), (NoCP, NoCP),List(Point(449918.5649999994,6839061.200000223,101.057), Point(449962.1459999993,6839186.165000221,101.814), Point(449990.7579999994,6839269.309000224,102.265), Point(450001.5809999994,6839300.786000223,102.451), Point(450005.5109999994,6839312.048000224,102.528)),projectId,  RoadAddressChangeType.New,AdministrativeClass.State, FrozenLinkInterface,265.489,0,0,1,reversed = false,None,1652179948783L,0,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart2,Track.Combined,Discontinuity.Continuous,AddrMRange(0,0),AddrMRange(0,0),None,None,Some("test"),"3db563eb-4369-4364-85fa-172af2271877:2", 0.0,117.63,SideCode.Unknown,(NoCP, NoCP), (NoCP, NoCP),List(Point(450220.8729999995,6840188.0850002235,103.506), Point(450222.0729999994,6840217.084000222,103.375), Point(450220.5199999994,6840305.677000223,103.002)),projectId,  RoadAddressChangeType.New,AdministrativeClass.State, FrozenLinkInterface,117.63,0,0,1,reversed = false,None,1652179948783L,0,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart2,Track.Combined,Discontinuity.Continuous,AddrMRange(0,0),AddrMRange(0,0),None,None,Some("test"),"1d6ab824-08d3-4cf1-8d1a-0462765d0dd3:2", 0.0,551.343,SideCode.Unknown,(NoCP, NoCP), (NoCP, NoCP),List(Point(450220.5199999994,6840305.677000223,103.002), Point(450219.5239999994,6840343.192000221,102.926), Point(450216.6259999995,6840380.865000221,102.711), Point(450214.0909999995,6840407.037000223,102.6), Point(450208.8379999994,6840445.254000223,102.385), Point(450194.7099999994,6840522.503000221,101.865), Point(450185.2919999993,6840563.799000222,101.529), Point(450172.9759999993,6840624.023000223,101.436), Point(450162.4699999993,6840681.529000223,101.69), Point(450155.2259999993,6840725.814000223,101.215), Point(450149.4299999996,6840769.102000222,101.011), Point(450146.1689999994,6840812.572000223,100.883), Point(450143.0899999994,6840850.653000224,100.968)),projectId,  RoadAddressChangeType.New,AdministrativeClass.State, FrozenLinkInterface,551.343,0,0,1,reversed = false,None,1652179948783L,0,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart2,Track.Combined,Discontinuity.Continuous,AddrMRange(0,0),AddrMRange(0,0),None,None,Some("test"),"0b655742-1ec7-452d-9caf-0d5370fbba48:2", 0.0,316.973,SideCode.Unknown,(NoCP, NoCP), (NoCP, NoCP),List(Point(450143.0899999994,6840850.653000224,100.968), Point(450140.4479999994,6840916.628000223,100.309), Point(450138.2009999993,6840978.224000225,99.866), Point(450138.8879999993,6840996.549000222,99.712), Point(450139.7769999993,6841013.067000221,99.598), Point(450141.0789999994,6841034.388000226,99.468), Point(450141.9729999993,6841044.970000225,99.429), Point(450144.7969999995,6841075.884000221,99.35), Point(450148.4109999996,6841107.545000223,99.26), Point(450156.3619999994,6841166.550000222,99.114)),projectId,  RoadAddressChangeType.New,AdministrativeClass.State, FrozenLinkInterface,316.973,0,0,1,reversed = false,None,1652179948783L,0,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart2,Track.Combined,Discontinuity.Continuous,AddrMRange(795,1872),AddrMRange(795,1872),None,None,Some("test"),"e9d243b3-2791-441f-a9e8-851e4d643c78:2", 0.0,1074.646,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(448872.114,6837289.287,127.891), Point(448886.3219999993,6837313.875000222,127.251), Point(448925.4709999995,6837381.540000224,125.074), Point(448936.3529999994,6837400.178000222,124.301), Point(448968.7659999993,6837455.691000221,121.998), Point(449009.3149999994,6837524.82700022,120.007), Point(449050.4429999994,6837594.741000222,118.388), Point(449059.5709999993,6837610.143000222,118.11), Point(449122.0639999993,6837718.192000221,117.029), Point(449165.0679999996,6837791.730000223,116.271), Point(449202.3909999993,6837855.870000224,115.64), Point(449220.7719999993,6837887.054000221,115.352), Point(449233.8769999992,6837909.110000221,115.075), Point(449270.4109999994,6837972.88100022,113.885), Point(449304.8329999993,6838031.81300022,112.19), Point(449319.2949999995,6838057.412000221,110.255), Point(449353.4059999996,6838116.018000224,108.248), Point(449365.0639999995,6838135.320000224,107.456), Point(449378.0679999994,6838157.362000223,106.604), Point(449397.7549999993,6838186.731000221,105.007), Point(449410.8369999993,6838205.885000221,103.9), Point(449417.7069999993,6838214.883000224,103.397)),projectId,  RoadAddressChangeType.Termination,AdministrativeClass.State, FrozenLinkInterface,1074.646,7691,69466,1,reversed = false,None,1652179948783L,45462,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart2,Track.Combined,Discontinuity.Continuous,AddrMRange(1872,2564),AddrMRange(1872,2564),None,None,Some("test"),"32ab31f5-72cb-40b1-9084-7fa6d2e767df:3", 0.0,690.929,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(449417.707,6838214.883,103.397), Point(449441.0809999992,6838248.37800022,101.949), Point(449469.3349999993,6838283.739000221,100.897), Point(449498.4789999992,6838320.09400022,100.457), Point(449508.4129999993,6838332.217000222,100.39), Point(449534.0019999993,6838361.736000224,100.169), Point(449583.1439999994,6838419.285000224,100.054), Point(449619.4449999993,6838463.116000222,100.215), Point(449649.7799999994,6838498.662000221,100.586), Point(449674.5539999994,6838528.762000223,101.017), Point(449704.2489999993,6838564.786000223,101.469), Point(449723.7729999993,6838591.195000223,101.52), Point(449743.1349999995,6838618.2440002225,101.486), Point(449762.3279999994,6838648.324000222,101.651), Point(449775.6069999995,6838670.332000222,101.204), Point(449787.6389999995,6838690.900000222,101.052), Point(449798.4549999993,6838712.027000222,100.837), Point(449813.3159999994,6838743.512000223,100.581), Point(449825.128,6838769.591,100.32400202815117)),projectId,  RoadAddressChangeType.Termination,AdministrativeClass.State, FrozenLinkInterface,690.929,7691,69467,1,reversed = false,None,1652179948783L,45462,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart2,Track.Combined,Discontinuity.Continuous,AddrMRange(2564,3101),AddrMRange(2564,3101),None,None,Some("test"),"bb10c163-a155-4472-8998-6c57a7519276:2", 0.0,535.631,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(449825.128,6838769.591,100.324), Point(449833.7079999994,6838790.590000222,100.201), Point(449845.2349999994,6838819.845000221,100.016), Point(449855.7719999993,6838847.6870002225,100.004), Point(449866.2299999993,6838879.0400002245,100.27), Point(449874.1599999993,6838904.548000224,100.382), Point(449881.3969999994,6838927.677000224,100.502), Point(449891.0639999993,6838959.369000222,100.659), Point(449907.3809999994,6839009.250000224,100.843), Point(449921.1429999994,6839054.132000225,101.114), Point(449946.8299999994,6839136.472000223,101.547), Point(449964.4859999992,6839191.360000223,101.828), Point(449976.9879999993,6839228.697000223,102.039), Point(449986.1829999993,6839255.928000226,102.192), Point(449992.1199999994,6839273.9490002245,102.276), Point(449992.7359999994,6839278.077000224,102.309)),projectId,  RoadAddressChangeType.Termination,AdministrativeClass.State, FrozenLinkInterface,535.631,7691,69468,1,reversed = false,None,1652179948783L,45462,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart2,Track.Combined,Discontinuity.Continuous,AddrMRange(3101,3137),AddrMRange(3101,3137),None,None,Some("test"),"6344c2d6-e3fa-4e1f-86c6-ed6be3696669:2", 0.0,36.298,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(449992.736,6839278.077,102.309), Point(450001.7639999996,6839302.762000224,102.45), Point(450005.5109999994,6839312.048000224,102.528)),projectId,  RoadAddressChangeType.Termination,AdministrativeClass.State, FrozenLinkInterface,36.298,7691,69469,1,reversed = false,None,1652179948783L,45462,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart2,Track.Combined,Discontinuity.Continuous,AddrMRange(3137,4044),AddrMRange(3137,4044),None,None,Some("test"),"2aaa2a30-9254-49aa-96e5-2c7bca77db20:1", 0.0,904.273,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(450005.511,6839312.048,102.528), Point(450023.8039999993,6839365.762000222,103.464), Point(450036.1129999994,6839401.222000223,104.532), Point(450048.1849999994,6839433.336000224,105.521), Point(450061.1639999993,6839470.216000222,106.51), Point(450076.0129999994,6839512.000000223,107.32), Point(450086.3559999994,6839545.271000221,107.842), Point(450094.3069999994,6839571.735000224,108.136), Point(450100.8869999993,6839594.083000224,108.32), Point(450106.9599999994,6839615.166000224,108.434), Point(450111.6859999993,6839633.248000222,108.423), Point(450117.4389999994,6839654.342000223,108.445), Point(450124.8399999994,6839683.205000223,108.462), Point(450131.8839999994,6839715.515000221,108.271), Point(450139.0869999994,6839747.827000223,107.96), Point(450146.1169999994,6839781.732000223,107.531), Point(450154.1899999994,6839823.469000223,106.87), Point(450161.2169999995,6839857.694000223,106.401), Point(450167.7849999992,6839889.680000223,105.972), Point(450172.9739999993,6839915.429000223,105.641), Point(450180.7549999993,6839954.290000224,105.244), Point(450188.3809999995,6839992.831000224,104.916), Point(450196.4919999994,6840030.418000223,104.617), Point(450201.0679999994,6840053.446000224,104.403), Point(450206.3919999994,6840081.9090002235,104.14), Point(450212.0049999995,6840113.567000222,103.868), Point(450215.8909999996,6840142.3360002255,103.669), Point(450219.5929999996,6840173.6580002215,103.557), Point(450220.873,6840188.085,103.50600049273254)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.State, FrozenLinkInterface,904.273,7691,69470,1,reversed = false,None,1652179948783L,45462,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart2,Track.Combined,Discontinuity.Continuous,AddrMRange(4044,4127),AddrMRange(4044,4127),None,None,Some("test"),"463b6067-7332-4e74-8b09-caa03a5fdc48:2", 0.0,82.804,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(450220.873,6840188.085,103.506), Point(450224.5869999993,6840238.194000223,103.361), Point(450225.5199999994,6840270.738000224,103.263)),projectId,  RoadAddressChangeType.Termination,AdministrativeClass.State, FrozenLinkInterface,82.804,7691,69471,1,reversed = false,None,1652179948783L,45462,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart2,Track.Combined,Discontinuity.Continuous,AddrMRange(4127,4143),AddrMRange(4127,4143),None,None,Some("test"),"062e7ea8-8db0-4f9c-8a5d-a5ae9c607135:2", 0.0,16.441,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(450225.52,6840270.738,103.263), Point(450225.4439999994,6840279.037000221,103.255), Point(450225.3699999995,6840287.178000223,103.214)),projectId,  RoadAddressChangeType.Termination,AdministrativeClass.State, FrozenLinkInterface,16.441,7691,69472,1,reversed = false,None,1652179948783L,45462,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart2,Track.Combined,Discontinuity.Continuous,AddrMRange(4143,4705),AddrMRange(4143,4705),None,None,Some("test"),"495d363a-ff22-4454-99de-c901d015765c:2", 0.0,560.618,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(450225.37,6840287.178,103.214), Point(450224.9709999995,6840313.353000224,103.183), Point(450223.9289999993,6840340.002000224,103.098), Point(450222.1039999993,6840365.047000224,102.948), Point(450219.8259999993,6840387.374000222,102.832), Point(450217.2389999993,6840408.581000224,102.701), Point(450213.3079999993,6840437.118000222,102.522), Point(450208.2669999994,6840464.848000223,102.301), Point(450202.2599999995,6840493.685000223,102.12), Point(450194.9629999994,6840523.948000222,101.888), Point(450189.4599999996,6840549.916000224,101.671), Point(450181.1799999994,6840583.044000222,101.433), Point(450172.8769999993,6840618.564000224,101.42), Point(450164.9189999994,6840651.3750002235,101.588), Point(450156.4489999996,6840687.692000221,101.862), Point(450150.4759999994,6840712.699000223,102.084), Point(450144.4989999996,6840738.18500022,102.254), Point(450138.8109999993,6840767.026000221,102.379), Point(450133.9439999993,6840793.160000224,102.452), Point(450130.8779999994,6840814.3630002225,102.453), Point(450128.11,6840837.962,102.37900151924806)),projectId,  RoadAddressChangeType.Termination,AdministrativeClass.State, FrozenLinkInterface,560.618,7691,69473,1,reversed = false,None,1652179948783L,45462,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart2,Track.Combined,Discontinuity.Continuous,AddrMRange(4705,4717),AddrMRange(4705,4717),None,None,Some("test"),"415fb07d-93be-49bf-8bc4-a3cc9a399655:2", 0.0,12.009,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(450128.11,6840837.962,102.379), Point(450126.862,6840849.906,102.27100020931147)),projectId,  RoadAddressChangeType.Termination,AdministrativeClass.State, FrozenLinkInterface,12.009,7691,69474,1,reversed = false,None,1652179948783L,45462,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart2,Track.Combined,Discontinuity.Continuous,AddrMRange(4717,5037),AddrMRange(4717,5037),None,None,Some("test"),"0e89c739-4df6-4ff3-a896-a5d33d5b7301:2", 0.0,319.237,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(450126.862,6840849.906,102.271), Point(450125.9369999995,6840863.785000225,102.151), Point(450124.9899999993,6840880.059000223,102.044), Point(450124.3349999996,6840899.367000222,101.874), Point(450123.9899999993,6840919.637000225,101.538), Point(450125.3999999993,6840965.012000223,100.743), Point(450129.6489999994,6841007.097000223,99.911), Point(450130.6309999994,6841016.825000223,99.731), Point(450136.6839999993,6841061.107000222,99.525), Point(450144.7899999993,6841104.784000222,99.362), Point(450150.5039999996,6841136.999000224,99.261), Point(450156.362,6841166.55,99.1140005916837)),projectId,  RoadAddressChangeType.Termination,AdministrativeClass.State, FrozenLinkInterface,319.237,7691,69475,1,reversed = false,None,1652179948783L,45462,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart2,Track.Combined,Discontinuity.Continuous,AddrMRange(5037,5376),AddrMRange(5037,5376),None,None,Some("test"),"38009a7e-cf41-4c74-8b34-8d02b17c1ba6:2", 0.0,337.782,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(450156.362,6841166.55,99.114), Point(450160.9469999994,6841189.420000223,99.027), Point(450164.1129999993,6841206.571000225,98.995), Point(450171.0509999993,6841243.758000223,98.875), Point(450178.1639999995,6841279.507000223,99.101), Point(450185.5579999993,6841318.778000223,98.975), Point(450193.4139999993,6841359.495000224,98.622), Point(450200.6629999996,6841397.484000224,98.217), Point(450207.7739999994,6841433.394000222,97.929), Point(450213.8059999995,6841465.7720002225,97.692), Point(450219.836,6841498.31,97.64600014729028)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.State, FrozenLinkInterface,337.782,7691,69476,1,reversed = false,None,1652179948783L,45462,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart2,Track.Combined,Discontinuity.Continuous,AddrMRange(5376,5624),AddrMRange(5376,5624),None,None,Some("test"),"c94130a2-8e1f-469d-b260-2cd075681fd3:2", 0.0,247.817,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(450219.836,6841498.31,97.646), Point(450225.5919999994,6841526.846000224,97.737), Point(450231.9309999993,6841560.347000224,97.903), Point(450238.8739999994,6841597.054000224,98.103), Point(450245.8269999993,6841632.8010002235,98.292), Point(450252.1639999993,6841666.462000225,98.426), Point(450258.5069999995,6841699.643000224,98.539), Point(450265.3149999994,6841734.110000225,98.582), Point(450266.716,6841741.65,98.57626814763272)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.State, FrozenLinkInterface,247.817,7691,69477,1,reversed = false,None,1652179948783L,45462,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart2,Track.Combined,Discontinuity.Continuous,AddrMRange(5624,5789),AddrMRange(5624,5789),None,None,Some("test"),"c94130a2-8e1f-469d-b260-2cd075681fd3:2", 247.817,412.695,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(450266.716,6841741.65,98.57626814763272), Point(450272.4019999994,6841772.258000225,98.553), Point(450278.5879999993,6841805.117000223,98.414), Point(450284.0349999993,6841832.690000223,98.17), Point(450290.8389999993,6841867.476000221,97.886), Point(450298.444,6841903.44,97.6160022034758)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.State, FrozenLinkInterface,164.878,7506,63151,1,reversed = false,None,1652179948783L,45463,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart2,Track.Combined,Discontinuity.Continuous,AddrMRange(5789,6106),AddrMRange(5789,6106),None,None,Some("test"),"7e41bae0-0eb2-48e4-afe7-6c430754fa11:2", 0.0,316.293,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(450298.444,6841903.44,97.616), Point(450307.5269999994,6841946.619000225,97.402), Point(450319.5189999994,6841993.561000224,97.249), Point(450329.1619999996,6842030.628000225,97.258), Point(450339.2309999994,6842065.643000224,97.309), Point(450349.8889999994,6842098.712000224,97.43), Point(450361.0419999994,6842130.347000224,97.598), Point(450375.9919999994,6842165.382000224,97.706), Point(450392.6529999994,6842204.595000224,97.942)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.State, FrozenLinkInterface,316.293,7506,63152,1,reversed = false,None,1652179948783L,45463,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart2,Track.Combined,Discontinuity.Continuous,AddrMRange(6106,6406),AddrMRange(6106,6406),None,None,Some("test"),"d68baa70-21e7-42c1-ad75-ceb734be907d:2", 0.0,298.875,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(450392.653,6842204.595,97.942), Point(450402.1349999994,6842225.759000224,98.071), Point(450413.6689999994,6842251.730000225,98.23), Point(450427.5919999994,6842279.795000222,98.363), Point(450440.8809999994,6842306.576000225,98.481), Point(450456.0909999994,6842333.3690002225,98.62), Point(450474.9469999994,6842365.938000224,98.819), Point(450489.2139999994,6842389.526000222,98.947), Point(450502.2259999994,6842408.789000223,96.477), Point(450504.2499999994,6842411.861000224,94.56), Point(450518.8849999994,6842433.670000224,99.181), Point(450530.1489999994,6842450.525000224,99.251), Point(450539.9939999996,6842463.853000224,99.2)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.State, FrozenLinkInterface,298.875,7506,63153,1,reversed = false,None,1652179948783L,45463,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart2,Track.Combined,Discontinuity.Continuous,AddrMRange(6406,6548),AddrMRange(6406,6548),None,None,Some("test"),"abb561d6-17f3-437b-adfc-71b29de5a831:2", 0.0,141.5,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(450539.994,6842463.853,99.2), Point(450558.7309999996,6842488.905000226,99.215), Point(450577.1539999994,6842512.996000224,99.23), Point(450597.1749999994,6842537.4150002245,99.245), Point(450613.8619999994,6842557.178000225,99.258), Point(450628.4189999994,6842574.262000225,99.269)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.State, FrozenLinkInterface,141.5,7506,63154,1,reversed = false,None,1652179948783L,45463,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart2,Track.Combined,Discontinuity.ChangingELYCode,AddrMRange(6548,6565),AddrMRange(6548,6565),None,None,Some("test"),"9936c53b-7f27-4318-9764-e99c1f3bc247:2", 0.0,17.54,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(450628.419,6842574.262,99.269), Point(450640.2799999996,6842587.183000224,99.283)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.State, FrozenLinkInterface,17.54,7506,63155,1,reversed = false,None,1652179948783L,45463,roadName,None,None,None,None),

        ProjectLink(Sequences.nextProjectLinkId, roadPart3,Track.Combined,Discontinuity.Continuous,AddrMRange(0,0),AddrMRange(0,0),None,None,Some("test"),"4131a00a-82ad-4c1c-9fc7-45d0e035a0db:2", 0.0,39.832,SideCode.Unknown,(NoCP, NoCP), (NoCP, NoCP),List(Point(448529.5099999995,6835207.6180002205,97.008), Point(448525.8739999993,6835214.19800022,96.474), Point(448521.8739999993,6835224.36300022,96.121), Point(448519.9019999993,6835234.01800022,96.556), Point(448519.5319999993,6835238.562000221,96.584), Point(448519.3329999993,6835245.536000223,96.577)),projectId,  RoadAddressChangeType.New,AdministrativeClass.State, FrozenLinkInterface,39.832,0,0,1,reversed = false,None,1652179948783L,0,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart3,Track.Combined,Discontinuity.Continuous,AddrMRange(0,0),AddrMRange(0,0),None,None,Some("test"),"d1ab7f00-7d28-4ade-a777-4a3da3aa48c2:2", 0.0,55.883,SideCode.Unknown,(NoCP, NoCP), (NoCP, NoCP),List(Point(448575.0659999993,6835176.474000222,100.074), Point(448563.7919999992,6835182.39000022,99.563), Point(448545.1979999992,6835192.13400022,99.149), Point(448540.4289999992,6835195.606000221,98.461), Point(448535.1509999992,6835200.722000222,97.856), Point(448529.5099999995,6835207.6180002205,97.008)),projectId,  RoadAddressChangeType.New,AdministrativeClass.State, FrozenLinkInterface,55.883,0,0,1,reversed = false,None,1652179948783L,0,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart3,Track.Combined,Discontinuity.Continuous,AddrMRange(0,0),AddrMRange(0,0),None,None,Some("test"),"3604868e-f3c5-4a58-b8d0-65400ec54602:1", 0.0,724.322,SideCode.Unknown,(NoCP, NoCP), (NoCP, NoCP),List(Point(448872.1139999993,6837289.287000221,127.891), Point(448889.5649999995,6837326.023000222,126.908), Point(448898.1699999995,6837343.983000223,126.36), Point(448906.6279999992,6837362.303000222,125.755), Point(448914.6739999993,6837380.402000222,125.094), Point(448922.5649999994,6837398.8570002215,124.464), Point(448930.1319999993,6837417.277000222,123.786), Point(448937.4589999991,6837435.858000222,123.092), Point(448945.0239999993,6837455.904000222,122.305), Point(448950.8839999993,6837471.548000221,121.681), Point(448952.0299999994,6837474.606000222,121.559), Point(448959.2169999993,6837493.285000222,120.832), Point(448966.6079999992,6837511.870000223,119.993), Point(448974.1729999993,6837530.36700022,119.133), Point(448981.8829999992,6837548.714000222,118.347), Point(448989.8509999993,6837567.150000222,117.511), Point(448997.9709999993,6837585.419000221,116.648), Point(449006.2659999993,6837603.609000223,115.825), Point(449014.7419999994,6837621.716000222,115.018), Point(449023.4099999993,6837639.7320002215,114.288), Point(449032.1269999992,6837657.439000224,113.695), Point(449041.1429999993,6837674.913000221,113.211), Point(449050.6099999994,6837692.147000221,112.856), Point(449060.5269999992,6837709.133000223,112.586), Point(449070.8789999992,6837725.8570002215,112.453), Point(449202.1239999993,6837931.603000223,114.56)),projectId,  RoadAddressChangeType.New,AdministrativeClass.State, FrozenLinkInterface,724.322,0,0,1,reversed = false,None,1652179948783L,0,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart3,Track.Combined,Discontinuity.Continuous,AddrMRange(0,0),AddrMRange(0,0),None,None,Some("test"),"be60ad8d-25b8-466e-a53d-447ec0a64979:1", 0.0,258.866,SideCode.Unknown,(NoCP, NoCP), (NoCP, NoCP),List(Point(449202.1239999993,6837931.603000223,114.56), Point(449226.1049999993,6837969.197000223,114.362), Point(449237.1419999993,6837986.700000222,114.059), Point(449247.9939999993,6838004.324000224,113.62), Point(449258.6659999993,6838022.039000222,113.146), Point(449269.1549999995,6838039.880000222,112.498), Point(449279.4579999993,6838057.827000223,111.737), Point(449289.5819999992,6838075.874000221,110.895), Point(449299.8459999993,6838094.646000221,109.874), Point(449312.2509999994,6838117.62300022,108.439), Point(449321.8029999994,6838134.971000222,107.36), Point(449331.5809999993,6838152.057000223,106.398), Point(449333.1779999994,6838154.743000222,106.256)),projectId,  RoadAddressChangeType.New,AdministrativeClass.State, FrozenLinkInterface,258.866,0,0,1,reversed = false,None,1652179948783L,0,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart3,Track.Combined,Discontinuity.Continuous,AddrMRange(0,0),AddrMRange(0,0),None,None,Some("test"),"bb919b0f-7d6e-4c74-8936-93dcf70f5e93:2", 0.0,613.135,SideCode.Unknown,(NoCP, NoCP), (NoCP, NoCP),List(Point(449333.1779999994,6838154.743000222,106.256), Point(449341.6359999993,6838168.974000222,105.53), Point(449351.9969999994,6838185.755000222,104.813), Point(449362.5969999994,6838202.343000222,104.137), Point(449373.4819999994,6838218.762000222,103.486), Point(449384.6279999994,6838234.991000221,102.848), Point(449396.0389999993,6838251.037000221,102.16), Point(449407.7219999993,6838266.898000224,101.525), Point(449419.6479999993,6838282.565000223,100.963), Point(449431.8369999993,6838298.035000223,100.509), Point(449444.4329999995,6838313.483000222,100.214), Point(449620.1649999994,6838525.4030002225,100.941), Point(449632.1709999992,6838540.120000223,100.993), Point(449643.8889999994,6838555.04300022,101.053), Point(449655.3829999992,6838570.187000222,101.132), Point(449666.6029999996,6838585.514000222,101.197), Point(449677.5509999995,6838601.029000222,101.217), Point(449688.2309999992,6838616.732000221,101.229), Point(449698.8289999995,6838632.916000223,101.2), Point(449704.4469999993,6838641.760000221,101.168)),projectId,  RoadAddressChangeType.New,AdministrativeClass.State, FrozenLinkInterface,613.135,0,0,1,reversed = false,None,1652179948783L,0,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart3,Track.Combined,Discontinuity.Continuous,AddrMRange(0,0),AddrMRange(0,0),None,None,Some("test"),"213caf99-39a9-4903-b18c-dad97505f11a:2", 0.0,163.072,SideCode.Unknown,(NoCP, NoCP), (NoCP, NoCP),List(Point(449704.4469999993,6838641.760000221,101.168), Point(449712.9719999994,6838655.593000222,101.159), Point(449718.8109999993,6838665.234000225,101.121), Point(449728.3759999994,6838681.639000223,101.011), Point(449737.6559999994,6838698.207000223,100.896), Point(449746.6449999993,6838714.924000223,100.817), Point(449755.3529999993,6838731.8120002225,100.726), Point(449763.9799999992,6838749.287000221,100.63), Point(449772.0869999993,6838766.4590002205,100.536), Point(449780.7389999994,6838785.717000222,100.449)),projectId,  RoadAddressChangeType.New,AdministrativeClass.State, FrozenLinkInterface,163.072,0,0,1,reversed = false,None,1652179948783L,0,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart3,Track.Combined,Discontinuity.Continuous,AddrMRange(0,0),AddrMRange(0,0),None,None,Some("test"),"5ac1811d-4d4f-4b60-81fe-fe76d63d14b9:2", 0.0,228.358,SideCode.Unknown,(NoCP, NoCP), (NoCP, NoCP),List(Point(449780.7389999994,6838785.717000222,100.449), Point(449787.4089999994,6838801.218000223,100.351), Point(449794.6209999995,6838818.797000222,100.241), Point(449801.5199999994,6838836.478000224,100.118), Point(449808.1219999993,6838854.292000221,100.056), Point(449814.5649999995,6838872.6700002225,99.944), Point(449820.5269999993,6838890.672000223,99.913), Point(449826.2059999993,6838908.827000222,99.895), Point(449831.5379999993,6838927.049000221,99.92), Point(449836.5669999995,6838945.371000222,100.014), Point(449841.2799999995,6838963.769000224,100.097), Point(449845.6709999993,6838982.248000222,100.197), Point(449849.8439999993,6839001.267000222,100.299), Point(449850.2319999993,6839002.820000222,100.298)),projectId,  RoadAddressChangeType.New,AdministrativeClass.State, FrozenLinkInterface,228.358,0,0,1,reversed = false,None,1652179948783L,0,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart3,Track.Combined,Discontinuity.Continuous,AddrMRange(0,0),AddrMRange(0,0),None,None,Some("test"),"22f7d632-fa91-4e9d-910c-b5d506f6c443:2", 0.0,79.364,SideCode.Unknown,(NoCP, NoCP), (NoCP, NoCP),List(Point(449850.2319999993,6839002.820000222,100.298), Point(449854.8579999993,6839025.650000223,100.482), Point(449857.7269999994,6839039.637000221,100.568), Point(449860.0889999995,6839048.629000223,100.62), Point(449862.6889999994,6839053.615000223,100.652), Point(449865.8329999994,6839057.673000225,100.709), Point(449868.6969999993,6839060.386000222,100.736), Point(449871.8899999993,6839062.703000221,100.763), Point(449872.5489999993,6839063.110000222,100.771), Point(449876.7039999994,6839065.15900022,100.809), Point(449881.3789999994,6839066.6080002235,100.807), Point(449884.8809999994,6839067.170000222,100.81)),projectId,  RoadAddressChangeType.New,AdministrativeClass.State, FrozenLinkInterface,79.364,0,0,1,reversed = false,None,1652179948783L,0,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart3,Track.Combined,Discontinuity.EndOfRoad,AddrMRange(0,0),AddrMRange(0,0),None,None,Some("test"),"6a0d3713-f146-4daa-a5ac-facc2b0853d1:2", 0.0,34.326,SideCode.Unknown,(NoCP, NoCP), (NoCP, NoCP),List(Point(449918.5649999994,6839061.200000223,101.057), Point(449894.7279999994,6839066.526000221,100.83), Point(449889.9939999993,6839067.2440002225,100.771), Point(449884.8809999994,6839067.170000222,100.81)),projectId,  RoadAddressChangeType.New,AdministrativeClass.State, FrozenLinkInterface,34.326,0,0,1,reversed = false,None,1652179948783L,0,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart3,Track.Combined,Discontinuity.Continuous,AddrMRange(0,534),AddrMRange(0,534),None,None,Some("test"),"32b46611-878d-4bf7-949e-7ca6499edca2:2", 0.0,532.574,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(448508.427,6836588.119,103.989), Point(448520.9989999992,6836633.473000222,104.472), Point(448534.5489999992,6836676.861000221,105.628), Point(448546.2859999993,6836708.512000222,107.015), Point(448559.5699999993,6836740.570000221,108.8), Point(448570.0769999993,6836763.850000222,110.27), Point(448580.6729999994,6836785.88500022,111.749), Point(448592.7769999993,6836808.838000221,113.313), Point(448606.1759999993,6836834.979000222,114.955), Point(448621.3369999992,6836861.591000222,116.63), Point(448640.6779999992,6836893.13800022,118.362), Point(448659.3839999993,6836925.486000221,120.012), Point(448679.2189999991,6836959.74600022,121.65), Point(448698.2459999993,6836992.253000221,123.132), Point(448721.7829999993,6837031.608000221,124.65), Point(448740.9509999993,6837064.6190002225,125.778)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.State, FrozenLinkInterface,532.574,7691,69464,1,reversed = false,None,1652179948783L,45462,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart3,Track.Combined,Discontinuity.Continuous,AddrMRange(534,795),AddrMRange(534,795),None,None,Some("test"),"2ffae796-a6e5-472e-89e1-8c47d70db815:2", 0.0,260.159,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(448740.951,6837064.619,125.778), Point(448770.7789999993,6837115.290000222,127.28), Point(448815.7449999993,6837191.15700022,128.502), Point(448846.0539999992,6837244.35000022,128.515), Point(448872.1139999993,6837289.287000221,127.891)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.State, FrozenLinkInterface,260.159,7691,69465,1,reversed = false,None,1652179948783L,45462,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart3,Track.Combined,Discontinuity.Continuous,AddrMRange(4751,4778),AddrMRange(4751,4778),None,None,Some("test"),"fc4f1111-5e0e-4d18-ae28-f7fc54c8c241:2", 0.0,26.831,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(448519.333,6835245.536,96.577), Point(448521.8319999993,6835272.250000221,96.545)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.State, FrozenLinkInterface,26.831,7609,64605,1,reversed = false,None,1652179948783L,45461,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart3,Track.Combined,Discontinuity.Continuous,AddrMRange(4778,4826),AddrMRange(4778,4826),None,None,Some("test"),"cd96b2ea-e4ee-464e-b035-c37f92e36755:2", 0.0,48.17,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(448521.832,6835272.25,96.545), Point(448524.5289999993,6835289.6980002215,96.616), Point(448526.7179999993,6835304.173000222,96.656), Point(448529.0279999993,6835319.879000221,96.805)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.State, FrozenLinkInterface,48.17,7609,64606,1,reversed = false,None,1652179948783L,45461,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart3,Track.Combined,Discontinuity.Continuous,AddrMRange(4826,5936),AddrMRange(4826,5936),None,None,Some("test"),"11e6ed2b-0f76-4120-8193-c8b3cfe75828:1", 0.0,1108.889,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(448529.028,6835319.879,96.805), Point(448537.1279999992,6835378.507000221,97.736), Point(448545.3179999993,6835444.334000221,99.937), Point(448549.8069999992,6835487.542000221,101.666), Point(448550.1669999995,6835503.421000222,102.291), Point(448550.9199999993,6835536.6690002205,103.601), Point(448550.0059999993,6835591.570000222,105.637), Point(448544.9189999993,6835658.366000221,107.489), Point(448535.2559999992,6835743.461000222,109.254), Point(448524.8449999992,6835832.479000222,110.481), Point(448513.6609999992,6835931.4590002205,111.499), Point(448506.3229999992,6836000.3400002215,112.192), Point(448496.5069999993,6836084.77000022,112.404), Point(448484.5549999993,6836189.826000222,111.235), Point(448478.0009999993,6836254.584000221,109.599), Point(448475.4969999991,6836298.06500022,108.084), Point(448474.9579999993,6836350.8060002215,105.99), Point(448476.2759999993,6836390.912000221,104.89), Point(448478.354,6836423.344,104.42300023962417)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.State, FrozenLinkInterface,1108.889,7609,64607,1,reversed = false,None,1652179948783L,45461,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart3,Track.Combined,Discontinuity.Continuous,AddrMRange(5936,5964),AddrMRange(5936,5964),None,None,Some("test"),"1602fe80-9c00-4b24-908b-05ac935b52cb:2", 0.0,27.54,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(448478.354,6836423.344,104.423), Point(448479.8889999995,6836442.83300022,104.219), Point(448480.859,6836450.765,104.16300313215446)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.State, FrozenLinkInterface,27.54,7609,64608,1,reversed = false,None,1652179948783L,45461,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart3,Track.Combined,Discontinuity.Continuous,AddrMRange(5964,6104),AddrMRange(5964,6104),None,None,Some("test"),"99d4b5b4-ccf7-43a8-99b5-31e00e72dd41:2", 0.0,140.236,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(448480.859,6836450.765,104.163), Point(448481.9039999995,6836459.263000223,104.15), Point(448487.3549999992,6836497.612000222,104.036), Point(448498.3149999993,6836547.897000222,103.937), Point(448508.4269999993,6836588.119000222,103.989)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.State, FrozenLinkInterface,140.236,7609,64609,1,reversed = false,None,1652179948783L,45461,roadName,None,None,None,None)
      )

      linearLocationDAO.create(linearLocations)
      roadwayDAO.create(roadways)
      projectDAO.create(Project(projectId, ProjectState.Incomplete, "test", "test", DateTime.now(),"test", DateTime.now(), DateTime.now(),"",
        Seq(
          ProjectReservedPart(Sequences.nextViitePrimaryKeySeqValue,roadPart1),
          ProjectReservedPart(Sequences.nextViitePrimaryKeySeqValue,roadPart2),
          ProjectReservedPart(Sequences.nextViitePrimaryKeySeqValue,roadPart3)
        ),
        Seq(
          ProjectReservedPart(Sequences.nextViitePrimaryKeySeqValue,roadPart1),
          ProjectReservedPart(Sequences.nextViitePrimaryKeySeqValue,roadPart2),
          ProjectReservedPart(Sequences.nextViitePrimaryKeySeqValue,roadPart3)
        ),
        None,None,Set(14)))
      projectReservedPartDAO.reserveRoadPart(projectId, roadPart1, "test")
      projectReservedPartDAO.reserveRoadPart(projectId, roadPart2, "test")
      projectReservedPartDAO.reserveRoadPart(projectId, roadPart3, "test")
      projectLinkDAO.create(projectLinks)
      projectService.recalculateProjectLinks(projectId, "test", Set(roadPart1, roadPart2, roadPart3))

      val recalculated = projectLinkDAO.fetchProjectLinks(projectId)

      recalculated.size should be (66)
      val roadPart1ProjectLinks = recalculated.filter(_.roadPart == roadPart1)
      val roadPart2ProjectLinks = recalculated.filter(_.roadPart == roadPart2)
      val roadPart3ProjectLinks = recalculated.filter(_.roadPart == roadPart3)

      // check road part end address
      roadPart1ProjectLinks.map(_.addrMRange.end).max should be (8898)
      val left1 = roadPart1ProjectLinks.filter(_.track == Track.LeftSide)
      val right1 = roadPart1ProjectLinks.filter(_.track == Track.RightSide)
      // check two track section start and end addresses
      left1.map(_.addrMRange.start).min should be (5688)
      left1.map(_.addrMRange.end).max should be (8467)
      right1.map(_.addrMRange.start).min should be (5688)
      right1.map(_.addrMRange.end).max should be (8467)

      // check road part end address
      roadPart2ProjectLinks.filterNot(_.status == RoadAddressChangeType.Termination).map(_.addrMRange.end).max should be (3686)

      // check road part end address
      roadPart3ProjectLinks.map(_.addrMRange.end).max should be (4345)
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

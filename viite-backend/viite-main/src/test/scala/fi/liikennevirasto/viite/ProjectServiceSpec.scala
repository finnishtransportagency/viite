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
import fi.vaylavirasto.viite.dao.{BaseDAO, ProjectLinkNameDAO, RoadName, RoadNameDAO, Sequences}
import fi.vaylavirasto.viite.geometry.{GeometryUtils, Point, PolyLine}
import fi.vaylavirasto.viite.model.CalibrationPointType.{JunctionPointCP, NoCP, RoadAddressCP, UserDefinedCP}
import fi.vaylavirasto.viite.model.LinkGeomSource.FrozenLinkInterface
import fi.vaylavirasto.viite.model.{AddrMRange, AdministrativeClass, Discontinuity, LifecycleStatus, LinkGeomSource, RoadAddressChangeType, RoadLink, RoadPart, SideCode, Track, TrafficDirection}
import fi.vaylavirasto.viite.postgis.PostGISDatabaseScalikeJDBC.runWithRollback
import org.joda.time.DateTime
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito._
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import org.scalatest.BeforeAndAfter
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar
import scalikejdbc.scalikejdbcSQLInterpolationImplicitDef

import java.sql.BatchUpdateException
import scala.concurrent.Future

class ProjectServiceSpec extends AnyFunSuite with Matchers with BeforeAndAfter with BaseDAO {
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

    override def runWithReadOnlySession[T](f: => T): T = f

    override def runWithTransaction[T](f: => T): T = f
  }
  val roadAddressServiceRealRoadwayAddressMapper: RoadAddressService = new RoadAddressService(mockRoadLinkService, roadwayDAO, linearLocationDAO, roadNetworkDAO, roadwayPointDAO, nodePointDAO, junctionPointDAO, roadwayAddressMapper, mockEventBus, frozenKGV = false) {

    override def runWithReadOnlySession[T](f: => T): T = f

    override def runWithTransaction[T](f: => T): T = f
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
                             override def runWithReadOnlySession[T](f: => T): T = f
                             override def runWithTransaction[T](f: => T): T = f
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
                            override def runWithReadOnlySession[T](f: => T): T = f
                            override def runWithTransaction[T](f: => T): T = f
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
    RoadwayChangeSection(None, None, None, None, None, None, None, None),
    RoadwayChangeSection(Option(403), Option(0), Option(8), Option(0), Option(AddrMRange(8,1001)),
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
      ProjectLink(NewIdValue, roadPart, track, discontinuity, addrMRange, addrMRange, startDate, None, Some("User"), linkId, 0.0, addrMRange.length.toDouble, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), Seq(Point(0.0, addrMRange.start), Point(0.0, addrMRange.end)), projectId, status, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, addrMRange.length.toDouble, roadwayId, linearLocationId, ely, reversed = false, None, 0L)
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

      val roadAddressProject2 = Project(0, ProjectState.apply(1), "TESTproject", "TestUser2", DateTime.now(), "TestUser2", DateTime.parse("1902-03-03"), DateTime.now(), "Some other info", List.empty[ProjectReservedPart], Seq(), None, elys = Set())
      val error = intercept[NameExistsException] {
        projectService.createRoadLinkProject(roadAddressProject2)
      }
      error.getMessage should be("Nimellä TESTproject on jo olemassa projekti. Muuta nimeä.")

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
        projectDAO.create(rap)
        projectService.preserveSingleProjectToBeTakenToRoadNetwork()
        val project = projectService.fetchProjectById(projectId).head
        project.statusInfo.getOrElse("").length should be(0)
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
      val stateCodeForProject = runSelectQuery(
        sql"""
             SELECT state
             FROM project
             WHERE id =  ${project.id}""".map(rs => rs.int("state"))
      )
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
      val p = ProjectAddressLink(idr, projectLink.linkId, projectLink.geometry, 1, AdministrativeClass.apply(1), LifecycleStatus.apply(1), projectLink.linkGeomSource, AdministrativeClass.State, None, 111, "Heinola", Some(""), Some(modifiedBy), projectLink.roadPart, 2, -1, projectLink.discontinuity.value, projectLink.addrMRange, projectLink.startMValue, projectLink.endMValue, projectLink.sideCode, calibrationPoints._1, calibrationPoints._2, projectLink.status, 12345, 123456, sourceId = "", originalAddrMRange = Some(projectLink.originalAddrMRange))

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

      runUpdateToDb(sql"""INSERT INTO road_name VALUES (nextval('road_name_seq'), ${roadPart.roadNumber}, 'road name test', TIMESTAMP '2018-03-23 12:26:36.000000', NULL, TIMESTAMP '2018-03-23 12:26:36.000000', NULL, 'test user', TIMESTAMP '2018-03-23 12:26:36.000000')""")
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

      runUpdateToDb(sql""" INSERT INTO project_link_name values (nextval('viite_general_seq'), ${roadAddressProject.id}, ${roadPart.roadNumber}, 'TestRoadName_Project_Link')""")
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

      runUpdateToDb(sql"""INSERT INTO road_name VALUES (nextval('road_name_seq'), ${roadPart.roadNumber}, 'road name test', TIMESTAMP '2018-03-23 12:26:36.000000', NULL, TIMESTAMP '2018-03-23 12:26:36.000000', NULL, 'test user', TIMESTAMP '2018-03-23 12:26:36.000000')""")
      runUpdateToDb(sql"""INSERT INTO project_link_name VALUES (nextval('viite_general_seq'), ${roadAddressProject.id}, ${roadPart.roadNumber}, 'TestRoadName_Project_Link')""")
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
    "renumber a project link to a road part not reserved with end date NULL") {
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
      runUpdateToDb(sql"""INSERT INTO road_name VALUES (nextval('road_name_seq'), 99999, 'test name', current_date, NULL, current_date, NULL, 'test user', current_date)""")
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

      runUpdateToDb(sql"""INSERT INTO link (ID) VALUES ('12345')""")
      runUpdateToDb(sql"""INSERT INTO link (ID) VALUES ('12346')""")
      runUpdateToDb(sql"""INSERT INTO link (ID) VALUES ('12347')""")
      runUpdateToDb(sql"""INSERT INTO linear_location (id,roadway_number,order_number,link_id,start_measure,end_measure,side,geometry,valid_from,valid_to,created_by,created_time)
            VALUES(nextval('linear_location_seq'), 123, 1, '12345', 0, 9, 2, ST_GeomFromText('LINESTRING(5.0 0.0 0 0, 5.0 9.0 0 9)', 3067), TIMESTAMP '2015-12-30 00:00:00.000000', NULL, 'TR', TIMESTAMP '2015-12-30 00:00:00.000000')""")
      runUpdateToDb(sql"""INSERT INTO linear_location (id,roadway_number,order_number,link_id,start_measure,end_measure,side,geometry,valid_from,valid_to,created_by,created_time)
            VALUES(nextval('linear_location_seq'), 123, 2, '12346', 0, 12, 2, ST_GeomFromText('LINESTRING(5.0 9.0 0 9, 5.0 21.0 0 21)', 3067), TIMESTAMP '2015-12-30 00:00:00.000000', NULL, 'TR', TIMESTAMP '2015-12-30 00:00:00.000000')""")
      runUpdateToDb(sql"""INSERT INTO linear_location (id,roadway_number,order_number,link_id,start_measure,end_measure,side,geometry,valid_from,valid_to,created_by,created_time)
            VALUES(nextval('linear_location_seq'), 123, 3, '12347', 0, 5, 2, ST_GeomFromText('LINESTRING(5.0 21.0 0 21, 5.0 26.0 0 26)', 3067), TIMESTAMP '2015-12-30 00:00:00.000000', NULL, 'TR', TIMESTAMP '2015-12-30 00:00:00.000000')""")

      runUpdateToDb(sql"""INSERT INTO roadway (id,roadway_number,road_number,road_part_number,track,start_addr_m,end_addr_m,reversed,discontinuity,start_date,end_date,created_by,created_time,administrative_class,ely,terminated,valid_from,valid_to)
        values (nextval('roadway_seq'), 123,9999,1,1,0,26,0,1,to_date('22-10-90','DD-MM-YY'),NULL,'TR',to_timestamp('21-09-18 12.04.42.970245000','DD-MM-YY HH24.MI.SSXFF'),1,8,0,to_date('16-10-98','DD-MM-YY'),NULL)""")



      // track2
      runUpdateToDb(sql"""INSERT INTO link (ID) VALUES ('12348')""")
      runUpdateToDb(sql"""INSERT INTO link (ID) VALUES ('12349')""")
      runUpdateToDb(sql"""INSERT INTO link (ID) VALUES ('12350')""")
      runUpdateToDb(sql"""INSERT INTO link (ID) VALUES ('12351')""")
      runUpdateToDb(sql"""INSERT INTO linear_location (id,roadway_number,order_number,link_id,start_measure,end_measure,side,geometry,valid_from,valid_to,created_by,created_time)
            VALUES(nextval('linear_location_seq'), 124, 1, '12348', 0, 10, 2, ST_GeomFromText('LINESTRING(0.0 0.0 0 0, 0.0 10.0 0 10)', 3067), TIMESTAMP '2015-12-30 00:00:00.000000', NULL, 'TR', TIMESTAMP '2015-12-30 00:00:00.000000')""")
      runUpdateToDb(sql"""INSERT INTO linear_location (id,roadway_number,order_number,link_id,start_measure,end_measure,side,geometry,valid_from,valid_to,created_by,created_time)
            VALUES(nextval('linear_location_seq'), 124, 2, '12349', 0, 8, 2, ST_GeomFromText('LINESTRING(0.0 10.0 0 10, 0.0 18.0 0 18)', 3067), TIMESTAMP '2015-12-30 00:00:00.000000', NULL, 'TR', TIMESTAMP '2015-12-30 00:00:00.000000')""")
      runUpdateToDb(sql"""INSERT INTO linear_location (id,roadway_number,order_number,link_id,start_measure,end_measure,side,geometry,valid_from,valid_to,created_by,created_time)
            VALUES(nextval('linear_location_seq'), 124, 3, '12350', 0, 5, 2, ST_GeomFromText('LINESTRING(0.0 18.0 0 18, 0.0 23.0 0 23)', 3067), TIMESTAMP '2015-12-30 00:00:00.000000', NULL, 'TR', TIMESTAMP '2015-12-30 00:00:00.000000')""")
      runUpdateToDb(sql"""INSERT INTO linear_location (id,roadway_number,order_number,link_id,start_measure,end_measure,side,geometry,valid_from,valid_to,created_by,created_time)
            VALUES(nextval('linear_location_seq'), 124, 4, '12351', 0, 3, 2, ST_GeomFromText('LINESTRING(0.0 23.0 0 23, 0.0 26.0 0 26)', 3067), TIMESTAMP '2015-12-30 00:00:00.000000', NULL, 'TR', TIMESTAMP '2015-12-30 00:00:00.000000')""")

      runUpdateToDb(sql"""INSERT INTO roadway (id,roadway_number,road_number,road_part_number,track,start_addr_m,end_addr_m,reversed,discontinuity,start_date,end_date,created_by,created_time,administrative_class,ely,terminated,valid_from,valid_to)
        values (nextval('roadway_seq'), 124,9999,1,2,0,26,0,1,to_date('22-10-90','DD-MM-YY'),NULL,'TR',to_timestamp('21-09-18 12.04.42.970245000','DD-MM-YY HH24.MI.SSXFF'),1,8,0,to_date('16-10-98','DD-MM-YY'),NULL)""")

      // part2
      // track1
      runUpdateToDb(sql"""INSERT INTO link (ID) VALUES ('12352')""")
      runUpdateToDb(sql"""INSERT INTO link (ID) VALUES ('12353')""")
      runUpdateToDb(sql"""INSERT INTO linear_location (id,roadway_number,order_number,link_id,start_measure,end_measure,side,geometry,valid_from,valid_to,created_by,created_time)
            VALUES(nextval('linear_location_seq'), 125, 1, '12352', 0, 2, 2, ST_GeomFromText('LINESTRING(5.0 26.0 0 0, 5.0 28.0 0 2)', 3067), TIMESTAMP '2015-12-30 00:00:00.000000', NULL, 'TR', TIMESTAMP '2015-12-30 00:00:00.000000')""")
      runUpdateToDb(sql"""INSERT INTO linear_location (id,roadway_number,order_number,link_id,start_measure,end_measure,side,geometry,valid_from,valid_to,created_by,created_time)
            VALUES(nextval('linear_location_seq'), 125, 2, '12353', 0, 7, 2, ST_GeomFromText('LINESTRING(5.0 28.0 0 2, 5.0 35.0 0 7)', 3067), TIMESTAMP '2015-12-30 00:00:00.000000', NULL, 'TR', TIMESTAMP '2015-12-30 00:00:00.000000')""")

      runUpdateToDb(sql"""INSERT INTO roadway (id,roadway_number,road_number,road_part_number,track,start_addr_m,end_addr_m,reversed,discontinuity,start_date,end_date,created_by,created_time,administrative_class,ely,terminated,valid_from,valid_to)
        values (nextval('roadway_seq'), 125,9999,2,1,0,7,0,1,to_date('22-10-90','DD-MM-YY'),NULL,'TR',to_timestamp('21-09-18 12.04.42.970245000','DD-MM-YY HH24.MI.SSXFF'),1,8,0,to_date('16-10-98','DD-MM-YY'),NULL)""")

      // track2
      runUpdateToDb(sql"""INSERT INTO link (ID) VALUES ('12354')""")
      runUpdateToDb(sql"""INSERT INTO link (ID) VALUES ('12355')""")
      runUpdateToDb(sql"""INSERT INTO linear_location (id,roadway_number,order_number,link_id,start_measure,end_measure,side,geometry,valid_from,valid_to,created_by,created_time)
            VALUES(nextval('linear_location_seq'), 126, 1, '12354', 0, 3, 2, ST_GeomFromText('LINESTRING(0.0 26.0 0 0, 0.0 29.0 0 3)', 3067), TIMESTAMP '2015-12-30 00:00:00.000000', NULL, 'TR', TIMESTAMP '2015-12-30 00:00:00.000000')""")
      runUpdateToDb(sql"""INSERT INTO linear_location (id,roadway_number,order_number,link_id,start_measure,end_measure,side,geometry,valid_from,valid_to,created_by,created_time)
            VALUES(nextval('linear_location_seq'), 126, 2, '12355', 0, 8, 2, ST_GeomFromText('LINESTRING(0.0 29.0 0 3, 0.0 37.0 0 11)', 3067), TIMESTAMP '2015-12-30 00:00:00.000000', NULL, 'TR', TIMESTAMP '2015-12-30 00:00:00.000000')""")
      runUpdateToDb(sql"""INSERT INTO roadway (id,roadway_number,road_number,road_part_number,track,start_addr_m,end_addr_m,reversed,discontinuity,start_date,end_date,created_by,created_time,administrative_class,ely,terminated,valid_from,valid_to)
        values (nextval('roadway_seq'), 126,9999,2,2,0,11,0,1,to_date('22-10-90','DD-MM-YY'),NULL,'TR',to_timestamp('21-09-18 12.04.42.970245000','DD-MM-YY HH24.MI.SSXFF'),1,8,0,to_date('16-10-98','DD-MM-YY'),NULL)""")

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
      runUpdateToDb(sql"""INSERT INTO link (ID) VALUES ('12345')""")
      runUpdateToDb(sql"""INSERT INTO link (ID) VALUES ('12346')""")
      runUpdateToDb(sql"""INSERT INTO link (ID) VALUES ('12347')""")
      runUpdateToDb(sql"""INSERT INTO linear_location (id,roadway_number,order_number,link_id,start_measure,end_measure,side,geometry,valid_from,valid_to,created_by,created_time)
            VALUES(nextval('linear_location_seq'), 1234567, 1, '12345', 0, 9, 2, ST_GeomFromText('LINESTRING(5.0 0.0 0 0, 5.0 9.0 0 9)', 3067), TIMESTAMP '2015-12-30 00:00:00.000000', NULL, 'TR', TIMESTAMP '2015-12-30 00:00:00.000000')""")
      runUpdateToDb(sql"""INSERT INTO linear_location (id,roadway_number,order_number,link_id,start_measure,end_measure,side,geometry,valid_from,valid_to,created_by,created_time)
            VALUES(nextval('linear_location_seq'), 1234567, 2, '12346', 0, 12, 2, ST_GeomFromText('LINESTRING(5.0 9.0 0 9, 5.0 21.0 0 21)', 3067), TIMESTAMP '2015-12-30 00:00:00.000000', NULL, 'TR', TIMESTAMP '2015-12-30 00:00:00.000000')""")
      runUpdateToDb(sql"""INSERT INTO linear_location (id,roadway_number,order_number,link_id,start_measure,end_measure,side,geometry,valid_from,valid_to,created_by,created_time)
            VALUES(nextval('linear_location_seq'), 1234567, 3, '12347', 0, 5, 2, ST_GeomFromText('LINESTRING(5.0 21.0 0 21, 5.0 26.0 0 26)', 3067), TIMESTAMP '2015-12-30 00:00:00.000000', NULL, 'TR', TIMESTAMP '2015-12-30 00:00:00.000000')""")

      runUpdateToDb(sql"""INSERT INTO roadway (id,roadway_number,road_number,road_part_number,track,start_addr_m,end_addr_m,reversed,discontinuity,start_date,end_date,created_by,created_time,administrative_class,ely,terminated,valid_from,valid_to)
        values (nextval('roadway_seq'), 1234567,9999,1,1,0,26,0,1,to_date('22-10-90','DD-MM-YY'),NULL,'TR',to_timestamp('21-09-18 12.04.42.970245000','DD-MM-YY HH24.MI.SSXFF'),1,8,0,to_date('16-10-98','DD-MM-YY'),NULL)""")



      // track2
      runUpdateToDb(sql"""INSERT INTO link (ID) VALUES ('12348')""")
      runUpdateToDb(sql"""INSERT INTO link (ID) VALUES ('12349')""")
      runUpdateToDb(sql"""INSERT INTO link (ID) VALUES ('12350')""")
      runUpdateToDb(sql"""INSERT INTO link (ID) VALUES ('12351')""")
      runUpdateToDb(sql"""INSERT INTO linear_location (id,roadway_number,order_number,link_id,start_measure,end_measure,side,geometry,valid_from,valid_to,created_by,created_time)
            VALUES(nextval('linear_location_seq'), 1234568, 1, '12348', 0, 10, 2, ST_GeomFromText('LINESTRING(0.0 0.0 0 0, 0.0 10.0 0 10)', 3067), TIMESTAMP '2015-12-30 00:00:00.000000', NULL, 'TR', TIMESTAMP '2015-12-30 00:00:00.000000')""")
      runUpdateToDb(sql"""INSERT INTO linear_location (id,roadway_number,order_number,link_id,start_measure,end_measure,side,geometry,valid_from,valid_to,created_by,created_time)
            VALUES(nextval('linear_location_seq'), 1234568, 2, '12349', 0, 8, 2, ST_GeomFromText('LINESTRING(0.0 10.0 0 10, 0.0 18.0 0 18)', 3067), TIMESTAMP '2015-12-30 00:00:00.000000', NULL, 'TR', TIMESTAMP '2015-12-30 00:00:00.000000')""")
      runUpdateToDb(sql"""INSERT INTO linear_location (id,roadway_number,order_number,link_id,start_measure,end_measure,side,geometry,valid_from,valid_to,created_by,created_time)
            VALUES(nextval('linear_location_seq'), 1234568, 3, '12350', 0, 5, 2, ST_GeomFromText('LINESTRING(0.0 18.0 0 18, 0.0 23.0 0 23)', 3067), TIMESTAMP '2015-12-30 00:00:00.000000', NULL, 'TR', TIMESTAMP '2015-12-30 00:00:00.000000')""")
      runUpdateToDb(sql"""INSERT INTO linear_location (id,roadway_number,order_number,link_id,start_measure,end_measure,side,geometry,valid_from,valid_to,created_by,created_time)
            VALUES(nextval('linear_location_seq'), 1234568, 4, '12351', 0, 3, 2, ST_GeomFromText('LINESTRING(0.0 23.0 0 23, 0.0 26.0 0 26)', 3067), TIMESTAMP '2015-12-30 00:00:00.000000', NULL, 'TR', TIMESTAMP '2015-12-30 00:00:00.000000')""")

      runUpdateToDb(sql"""INSERT INTO roadway (id,roadway_number,road_number,road_part_number,track,start_addr_m,end_addr_m,reversed,discontinuity,start_date,end_date,created_by,created_time,administrative_class,ely,terminated,valid_from,valid_to)
        values (nextval('roadway_seq'), 1234568,9999,1,2,0,26,0,1,to_date('22-10-90','DD-MM-YY'),NULL,'TR',to_timestamp('21-09-18 12.04.42.970245000','DD-MM-YY HH24.MI.SSXFF'),1,8,0,to_date('16-10-98','DD-MM-YY'),NULL)""")

      // part2
      // track1
      runUpdateToDb(sql"""INSERT INTO link (ID) VALUES ('12352')""")
      runUpdateToDb(sql"""INSERT INTO link (ID) VALUES ('12353')""")
      runUpdateToDb(sql"""INSERT INTO linear_location (id,roadway_number,order_number,link_id,start_measure,end_measure,side,geometry,valid_from,valid_to,created_by,created_time)
            VALUES(nextval('linear_location_seq'), 1234569, 1, '12352', 0, 2, 2,ST_GeomFromText('LINESTRING(5.0 26.0 0 0, 5.0 28.0 0 2)', 3067), TIMESTAMP '2015-12-30 00:00:00.000000', NULL, 'TR', TIMESTAMP '2015-12-30 00:00:00.000000')""")
      runUpdateToDb(sql"""INSERT INTO linear_location (id,roadway_number,order_number,link_id,start_measure,end_measure,side,geometry,valid_from,valid_to,created_by,created_time)
            VALUES(nextval('linear_location_seq'), 1234569, 2, '12353', 0, 7, 2, ST_GeomFromText('LINESTRING(5.0 28.0 0 2, 5.0 35.0 0 7)', 3067), TIMESTAMP '2015-12-30 00:00:00.000000', NULL, 'TR', TIMESTAMP '2015-12-30 00:00:00.000000')""")

      runUpdateToDb(sql"""INSERT INTO roadway (id,roadway_number,road_number,road_part_number,track,start_addr_m,end_addr_m,reversed,discontinuity,start_date,end_date,created_by,created_time,administrative_class,ely,terminated,valid_from,valid_to)
        values (nextval('roadway_seq'), 1234569,9999,2,1,0,7,0,1,to_date('22-10-90','DD-MM-YY'),NULL,'TR',to_timestamp('21-09-18 12.04.42.970245000','DD-MM-YY HH24.MI.SSXFF'),1,8,0,to_date('16-10-98','DD-MM-YY'),NULL)""")

      // track2
      runUpdateToDb(sql"""INSERT INTO link (ID) VALUES ('12354')""")
      runUpdateToDb(sql"""INSERT INTO link (ID) VALUES ('12355')""")
      runUpdateToDb(sql"""INSERT INTO linear_location (id,roadway_number,order_number,link_id,start_measure,end_measure,side,geometry,valid_from,valid_to,created_by,created_time)
            VALUES(nextval('linear_location_seq'), 1234570, 1, '12354', 0, 3, 2, ST_GeomFromText('LINESTRING(0.0 26.0 0 0, 0.0 29.0 0 3)', 3067), TIMESTAMP '2015-12-30 00:00:00.000000', NULL, 'TR', TIMESTAMP '2015-12-30 00:00:00.000000')""")
      runUpdateToDb(sql"""INSERT INTO linear_location (id,roadway_number,order_number,link_id,start_measure,end_measure,side,geometry,valid_from,valid_to,created_by,created_time)
            VALUES(nextval('linear_location_seq'), 1234570, 2, '12355', 0, 8, 2, ST_GeomFromText('LINESTRING(0.0 29.0 0 3, 0.0 37.0 0 11)', 3067), TIMESTAMP '2015-12-30 00:00:00.000000', NULL, 'TR', TIMESTAMP '2015-12-30 00:00:00.000000')""")

      runUpdateToDb(sql"""INSERT INTO roadway (id,roadway_number,road_number,road_part_number,track,start_addr_m,end_addr_m,reversed,discontinuity,start_date,end_date,created_by,created_time,administrative_class,ely,terminated,valid_from,valid_to)
        values (nextval('roadway_seq'), 1234570,9999,2,2,0,11,0,1,to_date('22-10-90','DD-MM-YY'),NULL,'TR',to_timestamp('21-09-18 12.04.42.970245000','DD-MM-YY HH24.MI.SSXFF'),1,8,0,to_date('16-10-98','DD-MM-YY'),NULL)""")

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
      val roadAddressLength = roadway.addrMRange.length
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
        ll.validTo should not be None // Not "NULL"
      }
      val linearLocationToBeValid = linearLocationDAO.fetchByLinkId(Set(linkId2))
      linearLocationToBeValid.foreach { ll =>
        ll.validTo should be(None) // Should be "NULL"
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
      runUpdateToDb(sql"""INSERT INTO road_name VALUES (nextval('road_name_seq'), 66666, 'ROAD TEST', TIMESTAMP '2018-03-23 12:26:36.000000', NULL, TIMESTAMP '2018-03-23 12:26:36.000000', NULL, 'test user', TIMESTAMP '2018-03-23 12:26:36.000000')""")

      runUpdateToDb(sql"""INSERT INTO project VALUES($projectId, 2, 'test project', 'silari', TIMESTAMP '2018-03-23 11:36:15.000000', '-', TIMESTAMP '2018-03-23 12:26:33.000000', NULL, NULL, NULL, 533406.572, 6994060.048, 12)""")
      runUpdateToDb(sql"""INSERT INTO project_reserved_road_part VALUES (${Sequences.nextViitePrimaryKeySeqValue}, 66666, 1, $projectId, '-')""")

      runUpdateToDb(sql"""INSERT INTO project_link (id, project_id, track, discontinuity_type, road_number, road_part_number,
          start_addr_m, end_addr_m, created_by, modified_by, created_date, modified_date, status,
          administrative_class, roadway_id, linear_location_id, connected_link_id, ely, reversed, side, start_measure, end_measure,
          link_id, adjusted_timestamp, link_source, geometry, original_start_addr_m, original_end_addr_m, roadway_number,
          start_calibration_point, end_calibration_point, orig_start_calibration_point, orig_end_calibration_point)
        VALUES (${Sequences.nextProjectLinkId}, $projectId, 0, 5, 66666, 1,
          0, 86, 'test user', 'test user', TIMESTAMP '2018-03-23 12:26:36.000000', TIMESTAMP '2018-03-23 00:00:00.000000', 2,
          1, NULL, NULL, NULL, 8, 0, NULL, 0, 85.617,
          NULL, 1543328166000, 1, NULL, 0, 0, NULL,
          3, 3, 0, 0)""")

      runUpdateToDb(sql"""INSERT INTO project_link_name VALUES (nextval('project_link_name_seq'), $projectId, 66666, 'ROAD TEST')""")
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
      runUpdateToDb(sql"""INSERT INTO project VALUES($projectId, 2, 'test project', 'silari', TIMESTAMP '2018-03-23 11:36:15.000000', '-', TIMESTAMP '2018-03-23 12:26:33.000000', NULL, TIMESTAMP '2018-03-23 00:00:00.000000', NULL, 533406.572, 6994060.048, 12)""")
      runUpdateToDb(sql"""INSERT INTO project_reserved_road_part VALUES (${Sequences.nextViitePrimaryKeySeqValue}, 70001, 1, $projectId, '-')""")

      runUpdateToDb(sql"""INSERT INTO project_link (id, project_id, track, discontinuity_type, road_number, road_part_number,
          start_addr_m, end_addr_m, created_by, modified_by, created_date, modified_date, status,
          administrative_class, roadway_id, linear_location_id, connected_link_id, ely, reversed, side, start_measure, end_measure,
          link_id, adjusted_timestamp, link_source, geometry, original_start_addr_m, original_end_addr_m, roadway_number,
          start_calibration_point, end_calibration_point, orig_start_calibration_point, orig_end_calibration_point)
        VALUES (${Sequences.nextProjectLinkId}, $projectId, 0, 5, 70001, 1,
          0, 86, 'test user', 'test user', TIMESTAMP '2018-03-23 12:26:36.000000', TIMESTAMP '2018-03-23 00:00:00.000000', 5,
          1, NULL, NULL, NULL, 8, 0, 2, 0, 85.617,
          5170979, 1500079296000, 1, NULL, 0, 86, NULL,
          3, 3, 3, 3)""")

      runUpdateToDb(sql"""INSERT INTO project_link_name VALUES (nextval('project_link_name_seq'), $projectId, 70001, NULL)""")
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
      runUpdateToDb(sql"""INSERT INTO road_name VALUES (nextval('road_name_seq'), 66666, 'road name test', TIMESTAMP '2018-03-23 12:26:36.000000', NULL, TIMESTAMP '2018-03-23 12:26:36.000000', NULL, 'test user', TIMESTAMP '2018-03-23 12:26:36.000000')""")

      runUpdateToDb(sql"""INSERT INTO project VALUES($projectId, 2, 'test project', 'silari', TIMESTAMP '2018-03-23 11:36:15.000000', '-', TIMESTAMP '2018-03-23 12:26:33.000000', NULL, TIMESTAMP '2018-03-23 00:00:00.000000', NULL, 533406.572, 6994060.048, 12)""")
      runUpdateToDb(sql"""INSERT INTO project_reserved_road_part VALUES (${Sequences.nextViitePrimaryKeySeqValue}, 66666, 1, $projectId, '-')""")

      runUpdateToDb(sql"""INSERT INTO project_link (id, project_id, track, discontinuity_type, road_number, road_part_number,
          start_addr_m, end_addr_m, created_by, modified_by, created_date, modified_date, status,
          administrative_class, roadway_id, linear_location_id, connected_link_id, ely, reversed, side, start_measure, end_measure,
          link_id, adjusted_timestamp, link_source, geometry, original_start_addr_m, original_end_addr_m, roadway_number,
          start_calibration_point, end_calibration_point, orig_start_calibration_point, orig_end_calibration_point)
        VALUES (${Sequences.nextProjectLinkId}, $projectId, 0, 5, 66666, 1,
          0, 86, 'test user', 'test user', TIMESTAMP '2018-03-23 12:26:36.000000', TIMESTAMP '2018-03-23 00:00:00.000000', 2,
          1, NULL, NULL, NULL, 8, 0, 2, 0, 85.617,
          5170979, 1500079296000, 1, NULL, 0, 86, NULL,
          3, 3, 3, 3)""")

      runUpdateToDb(sql"""INSERT INTO project_link_name VALUES (nextval('project_link_name_seq'), $projectId, 66666, 'another road name test')""")

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
      runUpdateToDb(sql"""INSERT INTO project VALUES($projectId, 2, 'test project', 'silari', TIMESTAMP '2018-03-23 11:36:15.000000', '-', TIMESTAMP '2018-03-23 12:26:33.000000', NULL, TIMESTAMP '2018-03-23 00:00:00.000000', NULL, 533406.572, 6994060.048, 12)""")
      runUpdateToDb(sql"""INSERT INTO project_reserved_road_part VALUES (${Sequences.nextViitePrimaryKeySeqValue}, 66666, 1, $projectId, '-')""")

      runUpdateToDb(sql"""INSERT INTO project_link (ID, project_ID, TRACK, DISCONTINUITY_TYPE, ROAD_NUMBER, ROAD_PART_NUMBER,
          START_ADDR_M, END_ADDR_M, CREATED_BY, MODIFIED_BY, CREATED_DATE, MODIFIED_DATE, STATUS,
          ADMINISTRATIVE_CLASS, roadway_ID, linear_location_ID, CONNECTED_link_ID, ELY, REVERSED, SIDE, START_MEASURE, END_MEASURE,
          link_ID, ADJUSTED_TIMESTAMP, link_SOURCE, GEOMETRY, ORIGINAL_START_ADDR_M, ORIGINAL_END_ADDR_M, roadway_NUMBER,
          START_calibration_point, END_calibration_point, ORIG_START_calibration_point, ORIG_END_calibration_point)
        VALUES (${Sequences.nextProjectLinkId}, $projectId, 0, 5, 66666, 1,
          0, 86, 'test user', 'test user', TIMESTAMP '2018-03-23 12:26:36.000000', TIMESTAMP '2018-03-23 00:00:00.000000', 2,
          1, NULL, NULL, NULL, 8, 0, 2, 0, 85.617,
          5170979, 1500079296000, 1, NULL, 0, 86, NULL,
          3, 3, 3, 3)""")

      runUpdateToDb(sql"""INSERT INTO project_link_name VALUES (nextval('project_link_name_seq'), $projectId, 66666, 'road name test')""")
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
      runUpdateToDb(sql"""INSERT INTO project VALUES($projectId, 2, 'test project', 'silari', TIMESTAMP '2018-03-23 11:36:15.000000', '-', TIMESTAMP '2018-03-23 12:26:33.000000', NULL, TIMESTAMP '2018-03-23 00:00:00.000000', NULL, 533406.572, 6994060.048, 12)""")
      runUpdateToDb(sql"""INSERT INTO project_reserved_road_part VALUES (${Sequences.nextViitePrimaryKeySeqValue}, 66666, 1, $projectId, '-')""")
      runUpdateToDb(sql"""INSERT INTO project_reserved_road_part VALUES (${Sequences.nextViitePrimaryKeySeqValue}, 55555, 1, $projectId, '-')""")

      runUpdateToDb(sql"""INSERT INTO project_link (id, project_id, track, discontinuity_type, road_number, road_part_number,
          start_addr_m, end_addr_m, created_by, modified_by, created_date, modified_date, status,
          administrative_class, roadway_id, linear_location_id, connected_link_id, ely, reversed, side, start_measure, end_measure,
          link_id, adjusted_timestamp, link_source, geometry, original_start_addr_m, original_end_addr_m, roadway_number,
          start_calibration_point, end_calibration_point, orig_start_calibration_point, orig_end_calibration_point)
        VALUES (${Sequences.nextProjectLinkId}, $projectId, 0, 5, 66666, 1,
          0, 86, 'test user', 'test user', TIMESTAMP '2018-03-23 12:26:36.000000', TIMESTAMP '2018-03-23 00:00:00.000000', 2,
          1, NULL, NULL, NULL, 8, 0, 2, 0, 85.617,
          5170979, 1500079296000, 1, NULL, 0, 86, NULL,
          3, 3, 3, 3)""")

      runUpdateToDb(sql"""INSERT INTO project_link (id, project_id, track, discontinuity_type, road_number, road_part_number,
          start_addr_m, end_addr_m, created_by, modified_by, created_date, modified_date, status,
          administrative_class, roadway_id, linear_location_id, connected_link_id, ely, reversed, side, start_measure, end_measure,
          link_id, adjusted_timestamp, link_source, geometry, original_start_addr_m, original_end_addr_m, roadway_number,
          start_calibration_point, end_calibration_point, orig_start_calibration_point, orig_end_calibration_point)
        VALUES (${Sequences.nextProjectLinkId}, $projectId, 0, 5, 55555, 1,
          0, 86, 'test user', 'test user', TIMESTAMP '2018-03-23 12:26:36.000000', TIMESTAMP '2018-03-23 00:00:00.000000', 2,
          1, NULL, NULL, NULL, 8, 0, 2, 0, 85.617,
          5170980, 1500079296000, 1, NULL, 0, 86, NULL,
          3, 3, 3, 3)""")

      runUpdateToDb(sql"""INSERT INTO project_link_name VALUES (nextval('project_link_name_seq'), $projectId, 66666, 'road name test')""")
      runUpdateToDb(sql"""INSERT INTO project_link_name VALUES (nextval('project_link_name_seq'), $projectId, 55555, 'road name test2')""")
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
      runUpdateToDb(sql"""INSERT INTO link (id,source,adjusted_timestamp,created_time) values ('568121','4','1446398762000',to_timestamp('27.12.2019 13:12:50','DD.MM.YYYY HH24:MI:SS'))""")
      runUpdateToDb(sql"""INSERT INTO link (id,source,adjusted_timestamp,created_time) values ('7256596','4','1498959782000',to_timestamp('27.12.2019 13:12:50','DD.MM.YYYY HH24:MI:SS'))""")
      runUpdateToDb(sql"""INSERT INTO link (id,source,adjusted_timestamp,created_time) values ('7256590','4','1498959782000',to_timestamp('27.12.2019 13:12:50','DD.MM.YYYY HH24:MI:SS'))""")
      runUpdateToDb(sql"""INSERT INTO link (id,source,adjusted_timestamp,created_time) values ('7256584','4','1498959782000',to_timestamp('27.12.2019 13:12:50','DD.MM.YYYY HH24:MI:SS'))""")
      runUpdateToDb(sql"""INSERT INTO link (id,source,adjusted_timestamp,created_time) values ('7256586','4','1503961971000',to_timestamp('27.12.2019 13:12:50','DD.MM.YYYY HH24:MI:SS'))""")
      runUpdateToDb(sql"""INSERT INTO link (id,source,adjusted_timestamp,created_time) values ('7256594','4','1533681057000',to_timestamp('27.12.2019 13:12:50','DD.MM.YYYY HH24:MI:SS'))""")
      runUpdateToDb(sql"""INSERT INTO link (id,source,adjusted_timestamp,created_time) values ('568164','4','1449097206000',to_timestamp('27.12.2019 13:12:50','DD.MM.YYYY HH24:MI:SS'))""")
      runUpdateToDb(sql"""INSERT INTO link (id,source,adjusted_timestamp,created_time) values ('568122','4','1449097206000',to_timestamp('27.12.2019 13:12:50','DD.MM.YYYY HH24:MI:SS'))""")

      runUpdateToDb(sql"""INSERT INTO roadway (id,roadway_number,road_number,road_part_number,track,start_addr_m,end_addr_m,reversed,discontinuity,start_date,end_date,created_by,created_time,administrative_class,ely,terminated,valid_from,valid_to) values ('1052907','40998','22006','12','0','0','215','0','5',to_date('01.01.2017','DD.MM.YYYY'),NULL,'import',to_timestamp('27.12.2019 13:12:51','DD.MM.YYYY HH24:MI:SS'),'1','2','0',to_date('28.12.2016','DD.MM.YYYY'),NULL)""")
      runUpdateToDb(sql"""INSERT INTO roadway (id,roadway_number,road_number,road_part_number,track,start_addr_m,end_addr_m,reversed,discontinuity,start_date,end_date,created_by,created_time,administrative_class,ely,terminated,valid_from,valid_to) values ('1052911','40998','22006','12','0','0','215','0','2',to_date('01.01.1989','DD.MM.YYYY'),to_date('31.12.2016','DD.MM.YYYY'),'import',to_timestamp('27.12.2019 13:12:51','DD.MM.YYYY HH24:MI:SS'),'1','2','0',to_date('28.12.2016','DD.MM.YYYY'),NULL)""")
      runUpdateToDb(sql"""INSERT INTO roadway (id,roadway_number,road_number,road_part_number,track,start_addr_m,end_addr_m,reversed,discontinuity,start_date,end_date,created_by,created_time,administrative_class,ely,terminated,valid_from,valid_to) values ('1052912','40999','22006','12','0','215','216','0','2',to_date('01.01.1989','DD.MM.YYYY'),to_date('30.12.1997','DD.MM.YYYY'),'import',to_timestamp('27.12.2019 13:12:51','DD.MM.YYYY HH24:MI:SS'),'1','2','1',to_date('31.01.2013','DD.MM.YYYY'),NULL)""")
      runUpdateToDb(sql"""INSERT INTO roadway (id,roadway_number,road_number,road_part_number,track,start_addr_m,end_addr_m,reversed,discontinuity,start_date,end_date,created_by,created_time,administrative_class,ely,terminated,valid_from,valid_to) values ('1052910','41000','22006','34','0','0','310','0','2',to_date('01.01.1989','DD.MM.YYYY'),NULL,'import',to_timestamp('27.12.2019 13:12:51','DD.MM.YYYY HH24:MI:SS'),'1','2','0',to_date('14.06.2016','DD.MM.YYYY'),NULL)""")
      runUpdateToDb(sql"""INSERT INTO roadway (id,roadway_number,road_number,road_part_number,track,start_addr_m,end_addr_m,reversed,discontinuity,start_date,end_date,created_by,created_time,administrative_class,ely,terminated,valid_from,valid_to) values ('1052909','6446223','22006','23','0','0','45','0','2',to_date('20.05.2007','DD.MM.YYYY'),NULL,'import',to_timestamp('27.12.2019 13:12:51','DD.MM.YYYY HH24:MI:SS'),'1','2','0',to_date('14.06.2016','DD.MM.YYYY'),NULL)""")
      runUpdateToDb(sql"""INSERT INTO roadway (id,roadway_number,road_number,road_part_number,track,start_addr_m,end_addr_m,reversed,discontinuity,start_date,end_date,created_by,created_time,administrative_class,ely,terminated,valid_from,valid_to) values ('1052906','6446225','22006','45','0','0','248','0','1',to_date('20.05.2007','DD.MM.YYYY'),NULL,'import',to_timestamp('27.12.2019 13:12:51','DD.MM.YYYY HH24:MI:SS'),'1','2','0',to_date('25.01.2017','DD.MM.YYYY'),NULL)""")
      runUpdateToDb(sql"""INSERT INTO roadway (id,roadway_number,road_number,road_part_number,track,start_addr_m,end_addr_m,reversed,discontinuity,start_date,end_date,created_by,created_time,administrative_class,ely,terminated,valid_from,valid_to) values ('1052908','166883589','22006','12','0','215','295','0','2',to_date('01.01.2017','DD.MM.YYYY'),NULL,'import',to_timestamp('27.12.2019 13:12:51','DD.MM.YYYY HH24:MI:SS'),'1','2','0',to_date('28.12.2016','DD.MM.YYYY'),NULL)""")

      runUpdateToDb(sql"""INSERT INTO linear_location (id,roadway_number,order_number,link_id,start_measure,end_measure,side,geometry,valid_from,valid_to,created_by,created_time) VALUES ('1039046',  '6446225','1','7256596',      0, 248.793,'3', ST_GeomFromText('LINESTRING(267357.266 6789458.693 0 248.793, 267131.966 6789520.79 0 248.793)', 3067),  to_date('25.01.2017','DD.MM.YYYY'),NULL,'import',to_timestamp('27.12.2019 13:12:51','DD.MM.YYYY HH24:MI:SS'))""")
      runUpdateToDb(sql"""INSERT INTO linear_location (id,roadway_number,order_number,link_id,start_measure,end_measure,side,geometry,valid_from,valid_to,created_by,created_time) VALUES ('1039047',    '40998','1', '568164',      0,   7.685,'3', ST_GeomFromText('LINESTRING(267444.126 6789234.9 0 7.685, 267437.206 6789238.158 0 7.685)', 3067),       to_date('28.12.2016','DD.MM.YYYY'),NULL,'import',to_timestamp('27.12.2019 13:12:51','DD.MM.YYYY HH24:MI:SS'))""")
      runUpdateToDb(sql"""INSERT INTO linear_location (id,roadway_number,order_number,link_id,start_measure,end_measure,side,geometry,valid_from,valid_to,created_by,created_time) VALUES ('1039048',    '40998','2','7256584',      0, 171.504,'2', ST_GeomFromText('LINESTRING(267444.126 6789234.9 0 171.504, 267597.461 6789260.633 0 171.504)', 3067),   to_date('28.12.2016','DD.MM.YYYY'),NULL,'import',to_timestamp('27.12.2019 13:12:51','DD.MM.YYYY HH24:MI:SS'))""")
      runUpdateToDb(sql"""INSERT INTO linear_location (id,roadway_number,order_number,link_id,start_measure,end_measure,side,geometry,valid_from,valid_to,created_by,created_time) VALUES ('1039049',    '40998','3','7256586',      0,  38.688,'2', ST_GeomFromText('LINESTRING(267597.461 6789260.633 0 38.688, 267518.603 6789333.458 0 38.688)', 3067),   to_date('28.12.2016','DD.MM.YYYY'),NULL,'import',to_timestamp('27.12.2019 13:12:51','DD.MM.YYYY HH24:MI:SS'))""")
      runUpdateToDb(sql"""INSERT INTO linear_location (id,roadway_number,order_number,link_id,start_measure,end_measure,side,geometry,valid_from,valid_to,created_by,created_time) VALUES ('1039050','166883589','1','7256586', 38.688, 120.135,'2', ST_GeomFromText('LINESTRING(267597.461 6789260.633 0 120.135, 267518.603 6789333.458 0 120.135)', 3067), to_date('28.12.2016','DD.MM.YYYY'),NULL,'import',to_timestamp('27.12.2019 13:12:51','DD.MM.YYYY HH24:MI:SS'))""")
      runUpdateToDb(sql"""INSERT INTO linear_location (id,roadway_number,order_number,link_id,start_measure,end_measure,side,geometry,valid_from,valid_to,created_by,created_time) VALUES ('1039051',  '6446223','1','7256594',      0,  44.211,'3', ST_GeomFromText('LINESTRING(267597.461 6789260.633 0 44.211, 267633.898 6789283.726 0 44.211)', 3067),   to_date('14.06.2016','DD.MM.YYYY'),NULL,'import',to_timestamp('27.12.2019 13:12:51','DD.MM.YYYY HH24:MI:SS'))""")
      runUpdateToDb(sql"""INSERT INTO linear_location (id,roadway_number,order_number,link_id,start_measure,end_measure,side,geometry,valid_from,valid_to,created_by,created_time) VALUES ('1039052',    '41000','1','7256590',      0, 130.538,'2', ST_GeomFromText('LINESTRING(267431.685 6789375.9 0 130.538, 267357.266 6789458.693 0 130.538)', 3067),   to_date('14.06.2016','DD.MM.YYYY'),NULL,'import',to_timestamp('27.12.2019 13:12:51','DD.MM.YYYY HH24:MI:SS'))""")
      runUpdateToDb(sql"""INSERT INTO linear_location (id,roadway_number,order_number,link_id,start_measure,end_measure,side,geometry,valid_from,valid_to,created_by,created_time) VALUES ('1039053',    '41000','2', '568121',      0, 177.547,'3', ST_GeomFromText('LINESTRING(267525.375 6789455.936 0 177.547, 267357.266 6789458.693 0 177.547)', 3067), to_date('14.06.2016','DD.MM.YYYY'),NULL,'import',to_timestamp('27.12.2019 13:12:51','DD.MM.YYYY HH24:MI:SS'))""")
      runUpdateToDb(sql"""INSERT INTO linear_location (id,roadway_number,order_number,link_id,start_measure,end_measure,side,geometry,valid_from,valid_to,created_by,created_time) VALUES ('1039054',    '41000','3', '568122',      0,   9.514,'3', ST_GeomFromText('LINESTRING(267534.612 6789453.659 0 9.514, 267525.375 6789455.936 0 9.514)', 3067),     to_date('14.06.2016','DD.MM.YYYY'),NULL,'import',to_timestamp('27.12.2019 13:12:51','DD.MM.YYYY HH24:MI:SS'))""")

      runUpdateToDb(sql"""INSERT INTO roadway_point (id,roadway_number,addr_m,created_by,created_time,modified_by,modified_time) VALUES ('1019231','6446225','0','import',to_timestamp('27.12.2019 13:12:50','DD.MM.YYYY HH24:MI:SSXFF'),'import',to_timestamp('27.12.2019 13:12:50','DD.MM.YYYY HH24:MI:SS'))""")
      runUpdateToDb(sql"""INSERT INTO roadway_point (id,roadway_number,addr_m,created_by,created_time,modified_by,modified_time) VALUES ('1019232','6446225','248','import',to_timestamp('27.12.2019 13:12:50','DD.MM.YYYY HH24:MI:SSXFF'),'import',to_timestamp('27.12.2019 13:12:50','DD.MM.YYYY HH24:MI:SS'))""")
      runUpdateToDb(sql"""INSERT INTO roadway_point (id,roadway_number,addr_m,created_by,created_time,modified_by,modified_time) VALUES ('1019233','40998','0','import',to_timestamp('27.12.2019 13:12:51','DD.MM.YYYY HH24:MI:SSXFF'),'import',to_timestamp('27.12.2019 13:12:51','DD.MM.YYYY HH24:MI:SS'))""")
      runUpdateToDb(sql"""INSERT INTO roadway_point (id,roadway_number,addr_m,created_by,created_time,modified_by,modified_time) VALUES ('1019234','166883589','295','import',to_timestamp('27.12.2019 13:12:51','DD.MM.YYYY HH24:MI:SSXFF'),'import',to_timestamp('27.12.2019 13:12:51','DD.MM.YYYY HH24:MI:SS'))""")
      runUpdateToDb(sql"""INSERT INTO roadway_point (id,roadway_number,addr_m,created_by,created_time,modified_by,modified_time) VALUES ('1019235','6446223','0','import',to_timestamp('27.12.2019 13:12:51','DD.MM.YYYY HH24:MI:SSXFF'),'import',to_timestamp('27.12.2019 13:12:51','DD.MM.YYYY HH24:MI:SS'))""")
      runUpdateToDb(sql"""INSERT INTO roadway_point (id,roadway_number,addr_m,created_by,created_time,modified_by,modified_time) VALUES ('1019236','6446223','45','import',to_timestamp('27.12.2019 13:12:51','DD.MM.YYYY HH24:MI:SSXFF'),'import',to_timestamp('27.12.2019 13:12:51','DD.MM.YYYY HH24:MI:SS'))""")
      runUpdateToDb(sql"""INSERT INTO roadway_point (id,roadway_number,addr_m,created_by,created_time,modified_by,modified_time) VALUES ('1019237','41000','0','import',to_timestamp('27.12.2019 13:12:51','DD.MM.YYYY HH24:MI:SSXFF'),'import',to_timestamp('27.12.2019 13:12:51','DD.MM.YYYY HH24:MI:SS'))""")
      runUpdateToDb(sql"""INSERT INTO roadway_point (id,roadway_number,addr_m,created_by,created_time,modified_by,modified_time) VALUES ('1019238','41000','310','import',to_timestamp('27.12.2019 13:12:51','DD.MM.YYYY HH24:MI:SSXFF'),'import',to_timestamp('27.12.2019 13:12:51','DD.MM.YYYY HH24:MI:SS'))""")
      runUpdateToDb(sql"""INSERT INTO roadway_point (id,roadway_number,addr_m,created_by,created_time,modified_by,modified_time) VALUES ('1019239','40998','215','k567997',to_timestamp('27.12.2019 13:26:55','DD.MM.YYYY HH24:MI:SSXFF'),'k567997',to_timestamp('27.12.2019 13:26:55','DD.MM.YYYY HH24:MI:SS'))""")
      runUpdateToDb(sql"""INSERT INTO roadway_point (id,roadway_number,addr_m,created_by,created_time,modified_by,modified_time) VALUES ('1019240','40998','177','k567997',to_timestamp('27.12.2019 13:26:55','DD.MM.YYYY HH24:MI:SSXFF'),'k567997',to_timestamp('27.12.2019 13:26:55','DD.MM.YYYY HH24:MI:SS'))""")
      runUpdateToDb(sql"""INSERT INTO roadway_point (id,roadway_number,addr_m,created_by,created_time,modified_by,modified_time) VALUES ('1019241','41000','275','k567997',to_timestamp('27.12.2019 13:26:55','DD.MM.YYYY HH24:MI:SSXFF'),'k567997',to_timestamp('27.12.2019 13:26:55','DD.MM.YYYY HH24:MI:SS'))""")
      runUpdateToDb(sql"""INSERT INTO roadway_point (id,roadway_number,addr_m,created_by,created_time,modified_by,modified_time) VALUES ('1019242','40998','8','k567997',to_timestamp('27.12.2019 13:26:55','DD.MM.YYYY HH24:MI:SSXFF'),'k567997',to_timestamp('27.12.2019 13:26:55','DD.MM.YYYY HH24:MI:SS'))""")
      runUpdateToDb(sql"""INSERT INTO roadway_point (id,roadway_number,addr_m,created_by,created_time,modified_by,modified_time) VALUES ('1019243','41000','300','k567997',to_timestamp('27.12.2019 13:26:55','DD.MM.YYYY HH24:MI:SSXFF'),'k567997',to_timestamp('27.12.2019 13:26:55','DD.MM.YYYY HH24:MI:SS'))""")
      runUpdateToDb(sql"""INSERT INTO roadway_point (id,roadway_number,addr_m,created_by,created_time,modified_by,modified_time) VALUES ('1019244','41000','127','k567997',to_timestamp('27.12.2019 13:26:55','DD.MM.YYYY HH24:MI:SSXFF'),'k567997',to_timestamp('27.12.2019 13:26:55','DD.MM.YYYY HH24:MI:SS'))""")

      runUpdateToDb(sql"""INSERT INTO calibration_point (id,roadway_point_id,link_id,start_end,type,valid_from,valid_to,created_by,created_time) VALUES ('1020345','1019231','7256596','0','3',to_date('27.12.2019','DD.MM.YYYY'),NULL,'import',to_timestamp('27.12.2019 13:12:51','DD.MM.YYYY HH24:MI:SS'))""")
      runUpdateToDb(sql"""INSERT INTO calibration_point (id,roadway_point_id,link_id,start_end,type,valid_from,valid_to,created_by,created_time) VALUES ('1020346','1019232','7256596','1','3',to_date('27.12.2019','DD.MM.YYYY'),NULL,'import',to_timestamp('27.12.2019 13:12:51','DD.MM.YYYY HH24:MI:SS'))""")
      runUpdateToDb(sql"""INSERT INTO calibration_point (id,roadway_point_id,link_id,start_end,type,valid_from,valid_to,created_by,created_time) VALUES ('1020347','1019233','568164','0','3',to_date('27.12.2019','DD.MM.YYYY'),NULL,'import',to_timestamp('27.12.2019 13:12:51','DD.MM.YYYY HH24:MI:SS'))""")
      runUpdateToDb(sql"""INSERT INTO calibration_point (id,roadway_point_id,link_id,start_end,type,valid_from,valid_to,created_by,created_time) VALUES ('1020348','1019234','7256586','1','3',to_date('27.12.2019','DD.MM.YYYY'),NULL,'import',to_timestamp('27.12.2019 13:12:51','DD.MM.YYYY HH24:MI:SS'))""")
      runUpdateToDb(sql"""INSERT INTO calibration_point (id,roadway_point_id,link_id,start_end,type,valid_from,valid_to,created_by,created_time) VALUES ('1020349','1019235','7256594','0','3',to_date('27.12.2019','DD.MM.YYYY'),NULL,'import',to_timestamp('27.12.2019 13:12:51','DD.MM.YYYY HH24:MI:SS'))""")
      runUpdateToDb(sql"""INSERT INTO calibration_point (id,roadway_point_id,link_id,start_end,type,valid_from,valid_to,created_by,created_time) VALUES ('1020350','1019236','7256594','1','3',to_date('27.12.2019','DD.MM.YYYY'),NULL,'import',to_timestamp('27.12.2019 13:12:51','DD.MM.YYYY HH24:MI:SS'))""")
      runUpdateToDb(sql"""INSERT INTO calibration_point (id,roadway_point_id,link_id,start_end,type,valid_from,valid_to,created_by,created_time) VALUES ('1020351','1019237','7256590','0','3',to_date('27.12.2019','DD.MM.YYYY'),NULL,'import',to_timestamp('27.12.2019 13:12:51','DD.MM.YYYY HH24:MI:SS'))""")
      runUpdateToDb(sql"""INSERT INTO calibration_point (id,roadway_point_id,link_id,start_end,type,valid_from,valid_to,created_by,created_time) VALUES ('1020352','1019238','568122','1','3',to_date('27.12.2019','DD.MM.YYYY'),NULL,'import',to_timestamp('27.12.2019 13:12:51','DD.MM.YYYY HH24:MI:SS'))""")

      runUpdateToDb(sql"""INSERT INTO project (id,state,name,created_by,created_date,modified_by,modified_date,add_info,start_date,status_info,coord_x,coord_y,zoom) VALUES ('1000351','2','aa','silari',to_date('27.12.2019','DD.MM.YYYY'),'-',to_date('27.12.2019','DD.MM.YYYY'),NULL,to_date('01.01.2020','DD.MM.YYYY'),NULL,267287.82,6789454.18,'12')""")

      runUpdateToDb(sql"""INSERT INTO project_reserved_road_part (id,road_number,road_part_number,project_id,created_by) VALUES ('1000366','22006','56','1000351','-')""")
      runUpdateToDb(sql"""INSERT INTO project_reserved_road_part (id,road_number,road_part_number,project_id,created_by) VALUES ('1000365','22006','68','1000351','-')""")
      runUpdateToDb(sql"""INSERT INTO project_reserved_road_part (id,road_number,road_part_number,project_id,created_by) VALUES ('1000352','22006','12','1000351','silari')""")
      runUpdateToDb(sql"""INSERT INTO project_reserved_road_part (id,road_number,road_part_number,project_id,created_by) VALUES ('1000353','22006','23','1000351','silari')""")
      runUpdateToDb(sql"""INSERT INTO project_reserved_road_part (id,road_number,road_part_number,project_id,created_by) VALUES ('1000354','22006','34','1000351','silari')""")
      runUpdateToDb(sql"""INSERT INTO project_reserved_road_part (id,road_number,road_part_number,project_id,created_by) VALUES ('1000369','22006','24','1000351','-')""")
      runUpdateToDb(sql"""INSERT INTO project_reserved_road_part (id,road_number,road_part_number,project_id,created_by) VALUES ('1000355','22006','45','1000351','silari')""")
      runUpdateToDb(sql"""INSERT INTO project_reserved_road_part (id,road_number,road_part_number,project_id,created_by) VALUES ('1000368','22006','67','1000351','-')""")

      //                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                  ID,project_ID,TRACK,DISCONTINUITY_TYPE,ROAD_NUMBER,ROAD_PART_NUMBER,START_ADDR_M,END_ADDR_M,CREATED_BY,MODIFIED_BY,CREATED_DATE,MODIFIED_DATE,STATUS,ROAD_TYPE,roadway_ID,linear_location_ID,CONNECTED_link_ID,ELY,REVERSED,SIDE,START_MEASURE,END_MEASURE,link_ID,ADJUSTED_TIMESTAMP,link_SOURCE,GEOMETRY,ORIGINAL_START_ADDR_M,ORIGINAL_END_ADDR_M,roadway_NUMBER,START_calibration_point,END_calibration_point,ORIG_START_calibration_point,ORIG_END_calibration_point
      runUpdateToDb(sql"""INSERT INTO project_link (id,project_id,track,discontinuity_type,road_number,road_part_number,start_addr_m,end_addr_m,created_by,modified_by,created_date,modified_date,status,administrative_class,roadway_id,linear_location_id,connected_link_id,ely,reversed,side,start_measure,end_measure,link_id,adjusted_timestamp,link_source,geometry,original_start_addr_m,original_end_addr_m,roadway_number,start_calibration_point,end_calibration_point,orig_start_calibration_point,orig_end_calibration_point) VALUES ('1000356','1000351','0','2','22006','67','169','177','silari','silari',to_date('27.12.2019','DD.MM.YYYY'),to_date('27.12.2019','DD.MM.YYYY'),'3','1','1052907','1039047',NULL,'2','1','2',0,7.685,'568164','1449097206000','4',      ST_GeomFromText('LINESTRING(267444.126 6789234.9 53.176999999996, 267440.935 6789235.99 53.2259999999951, 267437.206395653 6789238.15776997 53.2499974535623)', 3067),'0','8','166883765',0,3,0,0)""")
      runUpdateToDb(sql"""INSERT INTO project_link (id,project_id,track,discontinuity_type,road_number,road_part_number,start_addr_m,end_addr_m,created_by,modified_by,created_date,modified_date,status,administrative_class,roadway_id,linear_location_id,connected_link_id,ely,reversed,side,start_measure,end_measure,link_id,adjusted_timestamp,link_source,geometry,original_start_addr_m,original_end_addr_m,roadway_number,start_calibration_point,end_calibration_point,orig_start_calibration_point,orig_end_calibration_point) VALUES ('1000357','1000351','0','5','22006','67','0','169','silari','silari',to_date('27.12.2019','DD.MM.YYYY'),to_date('27.12.2019','DD.MM.YYYY'),'3','1','1052907','1039048',NULL,'2','1','3',0,171.504,'7256584','1498959782000','4',     ST_GeomFromText('LINESTRING(267444.126 6789234.9 53.176999999996, 267464.548 6789227.526 52.8959999999934, 267496.884 6789219.216 52.2939999999944, 267515.938 6789216.916 51.7979999999952, 267535.906 6789218.028 51.1319999999978, 267556.73 6789224.333 50.304999999993, 267574.187 6789234.039 49.5789999999979, 267588.124 6789247.565 49.0240000000049, 267597.460867063 6789260.63281394 48.7730035736591)', 3067),'8','177','166883765',3,3,0,0)""")
      runUpdateToDb(sql"""INSERT INTO project_link (id,project_id,track,discontinuity_type,road_number,road_part_number,start_addr_m,end_addr_m,created_by,modified_by,created_date,modified_date,status,administrative_class,roadway_id,linear_location_id,connected_link_id,ely,reversed,side,start_measure,end_measure,link_id,adjusted_timestamp,link_source,geometry,original_start_addr_m,original_end_addr_m,roadway_number,start_calibration_point,end_calibration_point,orig_start_calibration_point,orig_end_calibration_point) VALUES ('1000358','1000351','0','5','22006','56','80','118','silari','silari',to_date('27.12.2019','DD.MM.YYYY'),to_date('27.12.2019','DD.MM.YYYY'),'3','1','1052907','1039049',NULL,'2','1','3',0,38.688,'7256586','1503961971000','4',     ST_GeomFromText('LINESTRING(267597.461 6789260.633 48.773000000001, 267597.534 6789260.792 48.7719999999972, 267600.106 6789269.768 48.6059999999998, 267600.106 6789280.257 48.4780000000028, 267597.713 6789287.648 48.4360000000015, 267591.642 6789293.381 48.4370000000054, 267589.345139337 6789294.52943033 48.4244527667907)', 3067),'177','215','166883761',3,3,0,0)""")
      runUpdateToDb(sql"""INSERT INTO project_link (id,project_id,track,discontinuity_type,road_number,road_part_number,start_addr_m,end_addr_m,created_by,modified_by,created_date,modified_date,status,administrative_class,roadway_id,linear_location_id,connected_link_id,ely,reversed,side,start_measure,end_measure,link_id,adjusted_timestamp,link_source,geometry,original_start_addr_m,original_end_addr_m,roadway_number,start_calibration_point,end_calibration_point,orig_start_calibration_point,orig_end_calibration_point) VALUES ('1000359','1000351','0','5','22006','56','0','80','silari','silari',to_date('27.12.2019','DD.MM.YYYY'),to_date('27.12.2019','DD.MM.YYYY'),'3','1','1052908','1039050',NULL,'2','1','3',38.688,120.135,'7256586','1503961971000','4', ST_GeomFromText('LINESTRING(267589.345139337 6789294.52943033 48.4244527667907, 267578.828 6789299.788 48.3669999999984, 267559.269 6789308.892 48.2510000000038, 267546.792 6789314.963 48.2390000000014, 267533.979 6789321.707 48.2140000000072, 267524.2 6789327.103 48.226999999999, 267521.164 6789329.463 48.247000000003, 267518.603162863 6789333.45774594 48.2559994276524)', 3067),'215','295','166883589',3,0,0,0)""")
      runUpdateToDb(sql"""INSERT INTO project_link (id,project_id,track,discontinuity_type,road_number,road_part_number,start_addr_m,end_addr_m,created_by,modified_by,created_date,modified_date,status,administrative_class,roadway_id,linear_location_id,connected_link_id,ely,reversed,side,start_measure,end_measure,link_id,adjusted_timestamp,link_source,geometry,original_start_addr_m,original_end_addr_m,roadway_number,start_calibration_point,end_calibration_point,orig_start_calibration_point,orig_end_calibration_point) VALUES ('1000360','1000351','0','1','22006','68','0','45','silari','silari',to_date('27.12.2019','DD.MM.YYYY'),to_date('27.12.2019','DD.MM.YYYY'),'3','1','1052909','1039051',NULL,'2','1','2',0,44.211,'7256594','1533681057000','4',       ST_GeomFromText('LINESTRING(267597.461 6789260.633 48.773000000001, 267603.782 6789267.752 48.6589999999997, 267610.19 6789273.485 48.5580000000045, 267616.597 6789277.869 48.4689999999973, 267623.131 6789280.868 48.4400000000023, 267633.897953407 6789283.72598763 48.4409999956788)', 3067),'0','45','6446223',3,3,0,0)""")
      runUpdateToDb(sql"""INSERT INTO project_link (id,project_id,track,discontinuity_type,road_number,road_part_number,start_addr_m,end_addr_m,created_by,modified_by,created_date,modified_date,status,administrative_class,roadway_id,linear_location_id,connected_link_id,ely,reversed,side,start_measure,end_measure,link_id,adjusted_timestamp,link_source,geometry,original_start_addr_m,original_end_addr_m,roadway_number,start_calibration_point,end_calibration_point,orig_start_calibration_point,orig_end_calibration_point) VALUES ('1000361','1000351','0','5','22006','12','0','127','silari','silari',to_date('27.12.2019','DD.MM.YYYY'),to_date('27.12.2019','DD.MM.YYYY'),'3','1','1052910','1039052',NULL,'2','0','2',0,130.538,'7256590','1498959782000','4',     ST_GeomFromText('LINESTRING(267431.685 6789375.9 48.4470000000001, 267424.673 6789383.39 48.4180000000051, 267415.075 6789389.356 48.448000000004, 267401.067 6789396.879 48.5190000000002, 267384.985 6789404.661 48.6150000000052, 267366.307 6789414.259 48.6889999999985, 267356.079 6789421.259 48.6999999999971, 267351.522 6789425.931 48.7119999999995, 267349.187 6789432.416 48.7799999999988, 267348.928 6789442.533 48.9700000000012, 267352.041 6789450.574 49.1230000000069, 267357.266 6789458.693 49.3099999999977)', 3067),'0','127','166883763',3,3,0,0)""")
      runUpdateToDb(sql"""INSERT INTO project_link (id,project_id,track,discontinuity_type,road_number,road_part_number,start_addr_m,end_addr_m,created_by,modified_by,created_date,modified_date,status,administrative_class,roadway_id,linear_location_id,connected_link_id,ely,reversed,side,start_measure,end_measure,link_id,adjusted_timestamp,link_source,geometry,original_start_addr_m,original_end_addr_m,roadway_number,start_calibration_point,end_calibration_point,orig_start_calibration_point,orig_end_calibration_point) VALUES ('1000362','1000351','0','5','22006','23','0','174','silari','silari',to_date('27.12.2019','DD.MM.YYYY'),to_date('27.12.2019','DD.MM.YYYY'),'3','1','1052910','1039053',NULL,'2','0','3',0,177.547,'568121','1446398762000','4',      ST_GeomFromText('LINESTRING(267525.375 6789455.936 52.7309999999998, 267499.516 6789463.571 52.426999999996, 267458.911 6789473.392 52.0339999999997, 267426.281 6789480.881 51.4360000000015, 267403.97 6789481.494 50.7459999999992, 267378.849 6789475.013 49.9879999999976, 267357.54 6789459.007 49.3179999999993, 267357.266194778 6789458.69322321 49.3100056869441)', 3067),'127','301','166883762',3,0,0,0)""")
      runUpdateToDb(sql"""INSERT INTO project_link (id,project_id,track,discontinuity_type,road_number,road_part_number,start_addr_m,end_addr_m,created_by,modified_by,created_date,modified_date,status,administrative_class,roadway_id,linear_location_id,connected_link_id,ely,reversed,side,start_measure,end_measure,link_id,adjusted_timestamp,link_source,geometry,original_start_addr_m,original_end_addr_m,roadway_number,start_calibration_point,end_calibration_point,orig_start_calibration_point,orig_end_calibration_point) VALUES ('1000363','1000351','0','2','22006','23','174','183','silari','silari',to_date('27.12.2019','DD.MM.YYYY'),to_date('27.12.2019','DD.MM.YYYY'),'3','1','1052910','1039054',NULL,'2','0','3',0,9.514,'568122','1449097206000','4',      ST_GeomFromText('LINESTRING(267534.612 6789453.659 52.7939999999944, 267529.741 6789454.905 52.8300000000017, 267525.375 6789455.936 52.7309999999998)', 3067),'301','310','166883762',0,3,0,0)""")
      runUpdateToDb(sql"""INSERT INTO project_link (id,project_id,track,discontinuity_type,road_number,road_part_number,start_addr_m,end_addr_m,created_by,modified_by,created_date,modified_date,status,administrative_class,roadway_id,linear_location_id,connected_link_id,ely,reversed,side,start_measure,end_measure,link_id,adjusted_timestamp,link_source,geometry,original_start_addr_m,original_end_addr_m,roadway_number,start_calibration_point,end_calibration_point,orig_start_calibration_point,orig_end_calibration_point) VALUES ('1000364','1000351','0','2','22006','24','0','248','silari','silari',to_date('27.12.2019','DD.MM.YYYY'),to_date('27.12.2019','DD.MM.YYYY'),'3','1','1052906','1039046',NULL,'2','1','2',0,248.793,'7256596','1498959782000','4',     ST_GeomFromText('LINESTRING(267357.266 6789458.693 49.3099999999977, 267343.965 6789448.141 49.0489999999991, 267334.962 6789442.665 48.8600000000006, 267328.617 6789440.381 48.7949999999983, 267322.527 6789439.365 48.8000000000029, 267314.914 6789441.141 48.8439999999973, 267303.749 6789445.455 48.976999999999, 267287.508 6789453.576 49.0160000000033, 267268.999 6789462.419 49.2119999999995, 267228.658 6789482.321 49.3870000000024, 267201.225 6789496.844 49.4649999999965, 267167.876 6789511.367 49.7329999999929, 267147.437 6789519.436 49.9670000000042, 267131.966200167 6789520.78998248 50.2599962090907)', 3067),'0','248','6446225',3,3,0,0)""")

      runUpdateToDb(sql"""INSERT INTO project_link_name (ID,project_ID,ROAD_NUMBER,road_name) VALUES ('17','1000351','22006','MOMMOLAN RAMPIT')""")

      runUpdateToDb(sql"""INSERT INTO roadway_changes (project_id,change_type,old_road_number,new_road_number,old_road_part_number,new_road_part_number,old_track,new_track,old_start_addr_m,new_start_addr_m,old_end_addr_m,new_end_addr_m,new_discontinuity,new_administrative_class,new_ely,old_administrative_class,old_discontinuity,old_ely,reversed,roadway_change_id) VALUES ('1000351','3','22006','22006','12','67','0','0','0','0','177','177','2','1','2','1','5','2','1','1000753')""")
      runUpdateToDb(sql"""INSERT INTO roadway_changes (project_id,change_type,old_road_number,new_road_number,old_road_part_number,new_road_part_number,old_track,new_track,old_start_addr_m,new_start_addr_m,old_end_addr_m,new_end_addr_m,new_discontinuity,new_administrative_class,new_ely,old_administrative_class,old_discontinuity,old_ely,reversed,roadway_change_id) VALUES ('1000351','3','22006','22006','34','12','0','0','0','0','127','127','5','1','2','1','5','2','0','1000754')""")
      runUpdateToDb(sql"""INSERT INTO roadway_changes (project_id,change_type,old_road_number,new_road_number,old_road_part_number,new_road_part_number,old_track,new_track,old_start_addr_m,new_start_addr_m,old_end_addr_m,new_end_addr_m,new_discontinuity,new_administrative_class,new_ely,old_administrative_class,old_discontinuity,old_ely,reversed,roadway_change_id) VALUES ('1000351','3','22006','22006','12','56','0','0','215','0','295','80','5','1','2','1','2','2','1','1000755')""")
      runUpdateToDb(sql"""INSERT INTO roadway_changes (project_id,change_type,old_road_number,new_road_number,old_road_part_number,new_road_part_number,old_track,new_track,old_start_addr_m,new_start_addr_m,old_end_addr_m,new_end_addr_m,new_discontinuity,new_administrative_class,new_ely,old_administrative_class,old_discontinuity,old_ely,reversed,roadway_change_id) VALUES ('1000351','3','22006','22006','34','23','0','0','127','0','310','183','2','1','2','1','2','2','0','1000756')""")
      runUpdateToDb(sql"""INSERT INTO roadway_changes (project_id,change_type,old_road_number,new_road_number,old_road_part_number,new_road_part_number,old_track,new_track,old_start_addr_m,new_start_addr_m,old_end_addr_m,new_end_addr_m,new_discontinuity,new_administrative_class,new_ely,old_administrative_class,old_discontinuity,old_ely,reversed,roadway_change_id) VALUES ('1000351','3','22006','22006','23','68','0','0','0','0','45','45','1','1','2','1','2','2','1','1000757')""")
      runUpdateToDb(sql"""INSERT INTO roadway_changes (project_id,change_type,old_road_number,new_road_number,old_road_part_number,new_road_part_number,old_track,new_track,old_start_addr_m,new_start_addr_m,old_end_addr_m,new_end_addr_m,new_discontinuity,new_administrative_class,new_ely,old_administrative_class,old_discontinuity,old_ely,reversed,roadway_change_id) VALUES ('1000351','3','22006','22006','45','24','0','0','0','0','248','248','2','1','2','1','1','2','1','1000758')""")
      runUpdateToDb(sql"""INSERT INTO roadway_changes (project_id,change_type,old_road_number,new_road_number,old_road_part_number,new_road_part_number,old_track,new_track,old_start_addr_m,new_start_addr_m,old_end_addr_m,new_end_addr_m,new_discontinuity,new_administrative_class,new_ely,old_administrative_class,old_discontinuity,old_ely,reversed,roadway_change_id) VALUEs ('1000351','3','22006','22006','12','56','0','0','177','80','215','118','5','1','2','1','5','2','1','1000759')""")

      runUpdateToDb(sql"""INSERT INTO roadway_changes_link (roadway_change_id,project_id,project_link_id) VALUES ('1000753','1000351','1000357')""")
      runUpdateToDb(sql"""INSERT INTO roadway_changes_link (roadway_change_id,project_id,project_link_id) VALUES ('1000753','1000351','1000356')""")
      runUpdateToDb(sql"""INSERT INTO roadway_changes_link (roadway_change_id,project_id,project_link_id) VALUES ('1000754','1000351','1000361')""")
      runUpdateToDb(sql"""INSERT INTO roadway_changes_link (roadway_change_id,project_id,project_link_id) VALUES ('1000755','1000351','1000359')""")
      runUpdateToDb(sql"""INSERT INTO roadway_changes_link (roadway_change_id,project_id,project_link_id) VALUES ('1000756','1000351','1000362')""")
      runUpdateToDb(sql"""INSERT INTO roadway_changes_link (roadway_change_id,project_id,project_link_id) VALUES ('1000756','1000351','1000363')""")
      runUpdateToDb(sql"""INSERT INTO roadway_changes_link (roadway_change_id,project_id,project_link_id) VALUES ('1000757','1000351','1000360')""")
      runUpdateToDb(sql"""INSERT INTO roadway_changes_link (roadway_change_id,project_id,project_link_id) VALUES ('1000758','1000351','1000364')""")
      runUpdateToDb(sql"""INSERT INTO roadway_changes_link (roadway_change_id,project_id,project_link_id) VALUES ('1000759','1000351','1000358')""")
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
      runUpdateToDb(sql"""INSERT INTO project VALUES($project_id, 11, 'test project', $user, TIMESTAMP '2018-03-23 11:36:15.000000', '-', TIMESTAMP '2018-03-23 12:26:33.000000', NULL, TIMESTAMP '2018-03-23 00:00:00.000000', NULL, 564987.0, 6769633.0, 12)""")
      runUpdateToDb(sql"""INSERT INTO project_reserved_road_part VALUES (${Sequences.nextViitePrimaryKeySeqValue}, 46020, 1, $project_id, '-')""")
      runUpdateToDb(sql"""INSERT INTO roadway (id, roadway_number, road_number, road_part_number, track, start_addr_m, end_addr_m, reversed, discontinuity, start_date, end_date, created_by, created_time, administrative_class, ely, terminated, valid_from, valid_to) VALUES(107964, 335560416, 46020, 1, 0, 0, 785, 0, 1, '2022-01-01', NULL, $user, '2022-02-17 09:48:15.355', 2, 3, 0, '2022-02-17 09:48:15.355', NULL)""")
      runUpdateToDb(sql"""INSERT INTO link (id, "source", adjusted_timestamp, created_time) VALUES(2621644, 4, 1634598047000, '2022-02-17 09:48:15.355'),(2621698, 4, 1533863903000, '2022-02-17 09:48:15.355'),(2621642, 4, 1634598047000, '2022-02-17 09:48:15.355'),(2621640, 4, 1634598047000, '2022-02-17 09:48:15.355'),(2621632, 4, 1634598047000, '2022-02-17 09:48:15.355'),(2621636, 4, 1533863903000, '2022-02-17 09:48:15.355'),(2621288, 4, 1533863903000, '2022-02-17 09:48:15.355'),(2621287, 4, 1533863903000, '2022-02-17 09:48:15.355')""")

      runUpdateToDb(sql"""INSERT INTO linear_location (id, roadway_number, order_number, link_id, start_measure, end_measure, side, geometry, valid_from, valid_to, created_by, created_time) VALUES(490283, 335560416, 7, 2621644, 0.000,  94.008, 3, 'SRID=3067;LINESTRING ZM(565110.998 6769336.795  90.40499999999884 0, 565028.813 6769382.427  91.38099999999395  94.008)'::public.geometry, '2022-02-17 09:48:15.355', NULL, $createdBy, '2022-02-17 09:48:15.355')""")
      runUpdateToDb(sql"""INSERT INTO linear_location (id, roadway_number, order_number, link_id, start_measure, end_measure, side, geometry, valid_from, valid_to, created_by, created_time) VALUES(490284, 335560416, 8, 2621698, 0.000, 165.951, 3, 'SRID=3067;LINESTRING ZM(565258.278 6769260.328  82.68899999999849 0, 565110.998 6769336.795  90.40499999999884 165.951)'::public.geometry, '2022-02-17 09:48:15.355', NULL, $createdBy, '2022-02-17 09:48:15.355')""")
      runUpdateToDb(sql"""INSERT INTO linear_location (id, roadway_number, order_number, link_id, start_measure, end_measure, side, geometry, valid_from, valid_to, created_by, created_time) VALUES(490217, 335560416, 1, 2621642, 0.000, 167.250, 3, 'SRID=3067;LINESTRING ZM(564987.238 6769633.328 102.99000000000524 0, 565123.382 6769720.891 104.16400000000431 167.25)'::public.geometry,  '2022-02-17 09:48:15.355', NULL, $createdBy, '2022-02-17 09:48:15.355')""")
      runUpdateToDb(sql"""INSERT INTO linear_location (id, roadway_number, order_number, link_id, start_measure, end_measure, side, geometry, valid_from, valid_to, created_by, created_time) VALUES(490282, 335560416, 6, 2621640, 0.000, 155.349, 3, 'SRID=3067;LINESTRING ZM(565028.813 6769382.427  91.38099999999395 0, 564891.659 6769455.379  99.18700000000536 155.349)'::public.geometry, '2022-02-17 09:48:15.355', NULL, $createdBy, '2022-02-17 09:48:15.355')""")
      runUpdateToDb(sql"""INSERT INTO linear_location (id, roadway_number, order_number, link_id, start_measure, end_measure, side, geometry, valid_from, valid_to, created_by, created_time) VALUES(490218, 335560416, 2, 2621632, 0.000,  81.498, 3, 'SRID=3067;LINESTRING ZM(564948.879 6769561.432 100.8859999999986  0, 564987.238 6769633.328 102.99000000000524  81.498)'::public.geometry, '2022-02-17 09:48:15.355', NULL, $createdBy, '2022-02-17 09:48:15.355')""")
      runUpdateToDb(sql"""INSERT INTO linear_location (id, roadway_number, order_number, link_id, start_measure, end_measure, side, geometry, valid_from, valid_to, created_by, created_time) VALUES(490219, 335560416, 3, 2621636, 0.000, 101.665, 3, 'SRID=3067;LINESTRING ZM(564900.791 6769471.859  99.14599999999336 0, 564948.879 6769561.432 100.8859999999986  101.665)'::public.geometry, '2022-02-17 09:48:15.355', NULL, $createdBy, '2022-02-17 09:48:15.355')""")
      runUpdateToDb(sql"""INSERT INTO linear_location (id, roadway_number, order_number, link_id, start_measure, end_measure, side, geometry, valid_from, valid_to, created_by, created_time) VALUES(490220, 335560416, 4, 2621288, 0.000,   7.843, 3, 'SRID=3067;LINESTRING ZM(564896.99  6769464.999  99.18300000000454 0, 564900.791 6769471.859  99.14599999999336   7.843)'::public.geometry, '2022-02-17 09:48:15.355', NULL, $createdBy, '2022-02-17 09:48:15.355')""")
      runUpdateToDb(sql"""INSERT INTO linear_location (id, roadway_number, order_number, link_id, start_measure, end_measure, side, geometry, valid_from, valid_to, created_by, created_time) VALUES(490281, 335560416, 5, 2621287, 0.000,  10.998, 3, 'SRID=3067;LINESTRING ZM(564891.659 6769455.379  99.18700000000536 0, 564896.99  6769464.999  99.18300000000454  10.998)'::public.geometry, '2022-02-17 09:48:15.355', NULL, $createdBy, '2022-02-17 09:48:15.355')""")

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
      val firstRw = roadways.find(r => r.addrMRange.start == 0 && r.track == Track.Combined) // combined track start is unchanged
      firstRw should be ('defined)
      val secondRw = roadways.find(r => r.addrMRange.end == 785 && r.track == Track.RightSide) // start of right track is transferred
      secondRw should be ('defined)
      val thirdRw = roadways.find(r => r.addrMRange.start == 369 && r.addrMRange.end == 1035 && r.track == Track.LeftSide) // left track is new as a whole
      thirdRw should be ('defined)
      val fourthRw = roadways.find(r => r.addrMRange.start == 785 && r.addrMRange.end == 1035 && r.track == Track.RightSide) // end of right track is new
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
      runUpdateToDb(sql"""INSERT INTO project VALUES($project_id, 11, 'test project', $user, TIMESTAMP '2018-03-23 11:36:15.000000', '-', TIMESTAMP '2018-03-23 12:26:33.000000', NULL, TIMESTAMP '2018-03-23 00:00:00.000000', NULL, 564987.0, 6769633.0, 12)""")
      runUpdateToDb(sql"""INSERT INTO project_reserved_road_part VALUES (${Sequences.nextViitePrimaryKeySeqValue}, 46020, 1, $project_id, '-')""")
      runUpdateToDb(sql"""INSERT INTO roadway (id, roadway_number, road_number, road_part_number, track, start_addr_m, end_addr_m, reversed, discontinuity, start_date, end_date, created_by, created_time, administrative_class, ely, terminated, valid_from, valid_to) VALUES(107964, 335560416, 46020, 1, 0, 0, 369, 0, 4, '2022-01-01', NULL, $user, '2022-02-17 09:48:15.355', 2, 3, 0, '2022-02-17 09:48:15.355', NULL)""")
      runUpdateToDb(sql"""INSERT INTO roadway (id, roadway_number, road_number, road_part_number, track, start_addr_m, end_addr_m, reversed, discontinuity, start_date, end_date, created_by, created_time, administrative_class, ely, terminated, valid_from, valid_to) VALUES(1111, 335560417, 46020, 1, 0, 369, 624, 0, 1, '2022-01-01', NULL, $user, '2022-02-17 09:48:15.355', 2, 3, 0, '2022-02-17 09:48:15.355', NULL)""")
      runUpdateToDb(sql"""INSERT INTO link (id, "source", adjusted_timestamp, created_time) VALUES(2621644, 4, 1634598047000, '2022-02-17 09:48:15.355'),(2621698, 4, 1533863903000, '2022-02-17 09:48:15.355'),(2621642, 4, 1634598047000, '2022-02-17 09:48:15.355'),(2621640, 4, 1634598047000, '2022-02-17 09:48:15.355'),(2621632, 4, 1634598047000, '2022-02-17 09:48:15.355'),(2621636, 4, 1533863903000, '2022-02-17 09:48:15.355'),(2621288, 4, 1533863903000, '2022-02-17 09:48:15.355'),(2621287, 4, 1533863903000, '2022-02-17 09:48:15.355')""")

      runUpdateToDb(sql"""INSERT INTO linear_location (id, roadway_number, order_number, link_id, start_measure, end_measure, side, geometry, valid_from, valid_to, created_by, created_time) VALUES(490283, 335560416, 7, 2621644, 0.000,  94.008, 3, 'SRID=3067;LINESTRING ZM(565110.998 6769336.795  90.40499999999884 0, 565028.813 6769382.427  91.38099999999395  94.008)'::public.geometry, '2022-02-17 09:48:15.355', NULL, $createdBy, '2022-02-17 09:48:15.355')""")
      runUpdateToDb(sql"""INSERT INTO linear_location (id, roadway_number, order_number, link_id, start_measure, end_measure, side, geometry, valid_from, valid_to, created_by, created_time) VALUES(490284, 335560416, 8, 2621698, 0.000, 165.951, 3, 'SRID=3067;LINESTRING ZM(565258.278 6769260.328  82.68899999999849 0, 565110.998 6769336.795  90.40499999999884 165.951)'::public.geometry, '2022-02-17 09:48:15.355', NULL, $createdBy, '2022-02-17 09:48:15.355')""")
      runUpdateToDb(sql"""INSERT INTO linear_location (id, roadway_number, order_number, link_id, start_measure, end_measure, side, geometry, valid_from, valid_to, created_by, created_time) VALUES(490217, 335560416, 1, 2621642, 0.000, 167.250, 3, 'SRID=3067;LINESTRING ZM(564987.238 6769633.328 102.99000000000524 0, 565123.382 6769720.891 104.16400000000431 167.25 )'::public.geometry, '2022-02-17 09:48:15.355', NULL, $createdBy, '2022-02-17 09:48:15.355')""")
      runUpdateToDb(sql"""INSERT INTO linear_location (id, roadway_number, order_number, link_id, start_measure, end_measure, side, geometry, valid_from, valid_to, created_by, created_time) VALUES(490282, 335560416, 6, 2621640, 0.000, 155.349, 3, 'SRID=3067;LINESTRING ZM(565028.813 6769382.427  91.38099999999395 0, 564891.659 6769455.379  99.18700000000536 155.349)'::public.geometry, '2022-02-17 09:48:15.355', NULL, $createdBy, '2022-02-17 09:48:15.355')""")
      runUpdateToDb(sql"""INSERT INTO linear_location (id, roadway_number, order_number, link_id, start_measure, end_measure, side, geometry, valid_from, valid_to, created_by, created_time) VALUES(490218, 335560416, 2, 2621632, 0.000,  81.498, 3, 'SRID=3067;LINESTRING ZM(564948.879 6769561.432 100.8859999999986  0, 564987.238 6769633.328 102.99000000000524  81.498)'::public.geometry, '2022-02-17 09:48:15.355', NULL, $createdBy, '2022-02-17 09:48:15.355')""")
      runUpdateToDb(sql"""INSERT INTO linear_location (id, roadway_number, order_number, link_id, start_measure, end_measure, side, geometry, valid_from, valid_to, created_by, created_time) VALUES(490219, 335560416, 3, 2621636, 0.000, 101.665, 3, 'SRID=3067;LINESTRING ZM(564900.791 6769471.859  99.14599999999336 0, 564948.879 6769561.432 100.8859999999986  101.665)'::public.geometry, '2022-02-17 09:48:15.355', NULL, $createdBy, '2022-02-17 09:48:15.355')""")
      runUpdateToDb(sql"""INSERT INTO linear_location (id, roadway_number, order_number, link_id, start_measure, end_measure, side, geometry, valid_from, valid_to, created_by, created_time) VALUES(490220, 335560416, 4, 2621288, 0.000,   7.843, 3, 'SRID=3067;LINESTRING ZM(564896.99  6769464.999  99.18300000000454 0, 564900.791 6769471.859  99.14599999999336   7.843)'::public.geometry, '2022-02-17 09:48:15.355', NULL, $createdBy, '2022-02-17 09:48:15.355')""")
      runUpdateToDb(sql"""INSERT INTO linear_location (id, roadway_number, order_number, link_id, start_measure, end_measure, side, geometry, valid_from, valid_to, created_by, created_time) VALUES(490281, 335560416, 5, 2621287, 0.000,  10.998, 3, 'SRID=3067;LINESTRING ZM(564891.659 6769455.379  99.18700000000536 0, 564896.99  6769464.999  99.18300000000454  10.998)'::public.geometry, '2022-02-17 09:48:15.355', NULL, $createdBy, '2022-02-17 09:48:15.355')""")

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
        (0,80),
        (80,94),
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
        (1378,1392),
        (1392,1519),
        (1519,1559),
        (1559,1563),
        (1563,1726),
        (1726,1762),
        (1762,1801),
        (3941,4057),
        (4057,4076),
        (4076,4262),
        (4262,4292),
        (4292,4310),
        (4310,4442),
        (4442,4461),
        (4461,4474),
        (4474,4607),
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
        (1398,1412),
        (1412,1559),
        (1559,1563),
        (1563,1662),
        (1662,1722),
        (1722,1757),
        (1757,1801),
        (3941,4056),
        (4056,4074),
        (4074,4289),
        (4289,4307),
        (4307,4438),
        (4438,4458),
        (4458,4471),
        (4471,4515),
        (4515,4607),
        (4607,4666),
        (4666,4735),
        (4735,4766),
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
        (2397,2407),
        (2407,2593),
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

  test("Test projectService.recalculateProjectLinks() When terminating part of two track section and part of one track section. " +
      "Addresses need to be slided in order for the tracks to be matched at the new minor discontinuity spot.") {
    /**
     *  Based on a real life project on road part 2821/1
     *
     *                Before Termination                                      After Termination
     *              1376      1391
     *     1365  ^--->^-------->---------->-------->.......                                ---------->-------->.......
     *          /    /1365                                                                 1345
     *         /    /
     *  1348  ^    ^  1342                                           1345 ^     ^ 1345  (addresses slided and adjusted to match at the minor discontinuity spot)
     *       /    /                                                      /     /
     *      /    /                                                      /     /
     *     /    /                                                      /     /
     *    ^    ^                                                      ^     ^
     *   /    /                                                      /     /
     *  .    .                                                      .     .
     * .    .                                                      .     .
     *
     */

    runWithRollback {
      val roadPart = RoadPart(2821,1)
      val projectId = Sequences.nextViiteProjectId
      val roadName = Some("testRoad")

      val roadways = Seq(

        Roadway(6187,44928,roadPart,AdministrativeClass.Municipality,Track.RightSide,Discontinuity.Continuous,AddrMRange(615, 1135),false,DateTime.now().minusDays(2),None,"test",None,14,TerminationCode.NoTermination,DateTime.now().minusDays(2),None),
        Roadway(7297,46088,roadPart,AdministrativeClass.Municipality,Track.Combined,Discontinuity.Continuous,AddrMRange(1365, 2510),false,DateTime.now().minusDays(2),None,"test",None,14,TerminationCode.NoTermination,DateTime.now().minusDays(2),None),
        Roadway(7584,46089,roadPart,AdministrativeClass.State,Track.Combined,Discontinuity.Continuous,AddrMRange(2510, 5638),false,DateTime.now().minusDays(2),None,"test",None,14,TerminationCode.NoTermination,DateTime.now().minusDays(2),None),
        Roadway(70651,148121546,roadPart,AdministrativeClass.Municipality,Track.Combined,Discontinuity.Continuous,AddrMRange(0, 615),false,DateTime.now().minusDays(2),None,"test",None,14,TerminationCode.NoTermination,DateTime.now().minusDays(2),None),
        Roadway(73189,148122112,roadPart,AdministrativeClass.Municipality,Track.RightSide,Discontinuity.MinorDiscontinuity,AddrMRange(1135, 1148),false,DateTime.now().minusDays(2),None,"test",None,14,TerminationCode.NoTermination,DateTime.now().minusDays(2),None),
        Roadway(73470,148127435,roadPart,AdministrativeClass.Municipality,Track.LeftSide,Discontinuity.Continuous,AddrMRange(615, 1135),false,DateTime.now().minusDays(2),None,"test",None,14,TerminationCode.NoTermination,DateTime.now().minusDays(2),None),
        Roadway(73064,148127875,roadPart,AdministrativeClass.Municipality,Track.LeftSide,Discontinuity.MinorDiscontinuity,AddrMRange(1135, 1148),false,DateTime.now().minusDays(2),None,"test",None,14,TerminationCode.NoTermination,DateTime.now().minusDays(2),None),
        Roadway(83706,260730736,roadPart,AdministrativeClass.Municipality,Track.LeftSide,Discontinuity.Continuous,AddrMRange(1148, 1365),false,DateTime.now().minusDays(2),None,"test",None,14,TerminationCode.NoTermination,DateTime.now().minusDays(2),None),
        Roadway(83762,260730742,roadPart,AdministrativeClass.Municipality,Track.RightSide,Discontinuity.MinorDiscontinuity,AddrMRange(1148, 1365),false,DateTime.now().minusDays(2),None,"test",None,14,TerminationCode.NoTermination,DateTime.now().minusDays(2),None)
      )

      val linearLocations = Seq(
        LinearLocation(74915, 6.0,"4e1bf72d-a4d7-49bd-9667-dfb5b79767af:1",0.0,124.282,SideCode.AgainstDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(316643.823,6746832.132,0.0), Point(316519.807,6746840.14,0.0)),FrozenLinkInterface,46088,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(84608, 1.0,"a088c201-7eb2-4fa6-9e6c-597d2c0744c1:1",0.0,23.66,SideCode.AgainstDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(317548.213,6746738.124,0.0), Point(317525.223,6746743.713,0.0)),FrozenLinkInterface,46089,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(74929, 20.0,"692ec13e-baa6-424b-8936-13d498238c45:1",0.0,47.293,SideCode.AgainstDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(317279.382,6746792.234,0.0), Point(317232.814,6746800.481,0.0)),FrozenLinkInterface,46088,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(74914, 5.0,"4b8976c6-b101-49ad-935a-bf47b6544844:2",0.0,21.709,SideCode.AgainstDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(316519.807,6746840.14,0.0), Point(316498.102,6746840.417,0.0)),FrozenLinkInterface,46088,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(84615, 8.0,"0526c121-c04d-4f57-81bc-4d43971c9cc7:1",0.0,233.422,SideCode.AgainstDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(318755.112,6746340.239,0.0), Point(318533.391,6746413.211,0.0)),FrozenLinkInterface,46089,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(439752, 1.0,"67af9e96-b0a1-4952-b156-ead08556878d:1",0.0,64.643,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(315812.165,6746432.378,0.0), Point(315862.714,6746471.287,0.0)),FrozenLinkInterface,148127435,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(469211, 3.0,"c18f88f8-16ad-4e3e-b21d-c2b0b97b16cd:1",0.0,38.866,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(316347.796,6746802.737,0.0), Point(316378.519,6746826.541,0.0)),FrozenLinkInterface,260730742,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(469692, 2.0,"545a77ad-2ced-411e-8037-9e6658646110:1",0.0,46.325,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(316301.699,6746784.123,0.0), Point(316339.164,6746811.37,0.0)),FrozenLinkInterface,260730736,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(74910, 1.0,"83167055-d58e-4e4f-bb09-f396821b4b8b:1",0.0,11.505,SideCode.AgainstDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(316405.337,6746849.349,0.0), Point(316394.302,6746852.603,0.0)),FrozenLinkInterface,46088,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(74919, 10.0,"237dcbf1-08b8-4c70-bc2b-3a63dd3b7c5e:1",0.0,17.039,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(316847.966,6746844.821,0.0), Point(316860.436,6746856.432,0.0)),FrozenLinkInterface,46088,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(74925, 16.0,"b0ded3ac-dfe9-4a4c-bac1-ebb11bc72e16:1",0.0,48.032,SideCode.AgainstDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(317096.757,6746820.958,0.0), Point(317049.21,6746827.76,0.0)),FrozenLinkInterface,46088,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(439756, 5.0,"86c432a8-08ed-4a27-9e79-cf6b370b0cad:1",0.0,156.295,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(315967.261,6746547.281,0.0), Point(316096.821,6746634.662,0.0)),FrozenLinkInterface,148127435,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(439753, 2.0,"c2256b07-3f50-42af-8243-6325e2bc3c65:1",0.0,8.827,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(315862.714,6746471.287,0.0), Point(315870.078,6746476.154,0.0)),FrozenLinkInterface,148127435,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(434340, 6.0,"7aec4d47-6de7-478a-a285-e73b2cfa8239:1",0.0,25.169,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(315490.974,6746321.785,0.0), Point(315515.63,6746326.837,0.0)),FrozenLinkInterface,148121546,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(84620, 13.0,"539bcbb7-e153-4ceb-b62e-59444c380a03:1",0.0,235.64,SideCode.AgainstDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(319911.637,6745947.24,0.0), Point(319692.378,6746033.196,0.0)),FrozenLinkInterface,46089,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(84623, 16.0,"32724a12-ffa2-4114-8b2b-1d6ada07f6e2:1",0.0,65.435,SideCode.AgainstDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(320365.991,6745706.829,0.0), Point(320308.469,6745738.012,0.0)),FrozenLinkInterface,46089,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(84609, 2.0,"991a1632-94d0-4e91-b7b3-1c8caea6fab6:1",0.0,158.582,SideCode.AgainstDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(317698.229,6746686.731,0.0), Point(317548.213,6746738.124,0.0)),FrozenLinkInterface,46089,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(74928, 19.0,"090e5444-f3f3-4a60-bee7-9a49ac449331:1",0.0,47.329,SideCode.AgainstDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(317232.814,6746800.481,0.0), Point(317186.232,6746808.852,0.0)),FrozenLinkInterface,46088,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(65828, 3.0,"6658b9be-ecc1-480a-8d46-1e17e207571a:1",0.0,19.665,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(315955.658,6746523.39,0.0), Point(315971.661,6746534.818,0.0)),FrozenLinkInterface,44928,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(84613, 6.0,"db8ad68b-e819-4e8c-a4fc-bab05d4a65c3:1",0.0,48.844,SideCode.AgainstDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(318425.588,6746448.419,0.0), Point(318379.279,6746463.95,0.0)),FrozenLinkInterface,46089,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(434344, 10.0,"762d8ae1-fb60-4e5a-85bf-11a054dce662:1",0.0,79.39,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(315740.512,6746398.193,0.0), Point(315812.165,6746432.378,0.0)),FrozenLinkInterface,148121546,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(74916, 7.0,"091f3e47-0dce-453f-9835-a4172090e038:1",0.0,29.572,SideCode.AgainstDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(316673.363,6746830.766,0.0), Point(316643.823,6746832.132,0.0)),FrozenLinkInterface,46088,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(84610, 3.0,"7115cb86-e744-471a-a859-0cb6def5b562:1",0.0,169.963,SideCode.AgainstDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(317859.723,6746633.765,0.0), Point(317698.229,6746686.731,0.0)),FrozenLinkInterface,46089,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(74930, 21.0,"40c78727-a78b-4d00-bb65-e56f4f86ba4c:1",0.0,138.212,SideCode.AgainstDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(317415.093,6746766.103,0.0), Point(317279.382,6746792.234,0.0)),FrozenLinkInterface,46088,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(74920, 11.0,"dbdd5839-4de8-43c0-a2ee-b805f302605e:1",0.0,64.134,SideCode.AgainstDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(316922.628,6746842.933,0.0), Point(316860.436,6746856.432,0.0)),FrozenLinkInterface,46088,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(439755, 4.0,"08e3a420-6edb-47c3-8e7b-c9e64ce95af4:1",0.0,20.422,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(315950.938,6746535.009,0.0), Point(315967.261,6746547.281,0.0)),FrozenLinkInterface,148127435,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(74923, 14.0,"20209b44-4711-41d1-aeee-de075e5adabb:1",0.0,42.117,SideCode.AgainstDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(317003.219,6746833.527,0.0), Point(316961.409,6746838.525,0.0)),FrozenLinkInterface,46088,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(434335, 1.0,"463ec1e2-7744-4817-b67b-0b7453eb1a0a:1",0.0,86.774,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(315225.331,6746244.622,0.0), Point(315308.384,6746269.757,0.0)),FrozenLinkInterface,148121546,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(469212, 4.0,"84cf7440-59be-4bf6-a6cb-7b59a45ec001:1",0.0,13.057,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(316378.519,6746826.541,0.0), Point(316388.388,6746835.09,0.0)),FrozenLinkInterface,260730742,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(74924, 15.0,"ba8ea28c-f1ab-4204-a25d-cfc426981fe2:1",0.0,46.351,SideCode.AgainstDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(317049.21,6746827.76,0.0), Point(317003.219,6746833.527,0.0)),FrozenLinkInterface,46088,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(74927, 18.0,"5ac5f971-b69d-4500-ade8-3c09e6b989ab:1",0.0,44.873,SideCode.AgainstDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(317186.232,6746808.852,0.0), Point(317141.74,6746814.67,0.0)),FrozenLinkInterface,46088,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(434339, 5.0,"18c532e5-2e89-4e0a-badd-7f793695e045:1",0.0,12.649,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(315478.712,6746318.682,0.0), Point(315490.974,6746321.785,0.0)),FrozenLinkInterface,148121546,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(469693, 3.0,"67d69d87-8dc6-4d46-bdd9-ba5c82c8807f:1",0.0,39.115,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(316339.164,6746811.37,0.0), Point(316370.394,6746834.921,0.0)),FrozenLinkInterface,260730736,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(65829, 4.0,"a1880ddf-0227-4c55-a524-b1a7ff898a60:1",0.0,159.773,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(315971.661,6746534.818,0.0), Point(316101.841,6746627.408,0.0)),FrozenLinkInterface,44928,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(74912, 3.0,"b8e333d2-84c5-448d-944f-1a514d8774aa:1",0.0,12.165,SideCode.AgainstDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(316432.093,6746844.569,0.0), Point(316420.04,6746846.217,0.0)),FrozenLinkInterface,46088,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(84622, 15.0,"b7b5f7d8-c0b8-461f-85af-7ead59b96f4f:1",0.0,190.316,SideCode.AgainstDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(320308.469,6745738.012,0.0), Point(320141.882,6745830.016,0.0)),FrozenLinkInterface,46089,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(434343, 9.0,"19ebcf44-77ef-49fe-ae10-fc3428e17873:1",0.0,111.345,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(315636.749,6746358.431,0.0), Point(315740.512,6746398.193,0.0)),FrozenLinkInterface,148121546,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(436688, 2.0,"8c0f1be6-b863-4bbd-8ad8-0f3b9b1aba59:1",0.0,11.086,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(316208.349,6746703.284,0.0), Point(316217.431,6746709.642,0.0)),FrozenLinkInterface,148122112,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(84619, 12.0,"523419ef-0d23-4be1-8dbc-857ef6a01095:1",0.0,88.747,SideCode.AgainstDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(319692.378,6746033.196,0.0), Point(319608.111,6746061.036,0.0)),FrozenLinkInterface,46089,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(434338, 4.0,"b27b0fd8-6505-4b45-95cf-1e6db858b041:1",0.0,53.811,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(315426.675,6746304.987,0.0), Point(315478.712,6746318.682,0.0)),FrozenLinkInterface,148121546,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(84617, 10.0,"8c8e8f43-544f-47c9-8078-2d6f87520277:1",0.0,105.403,SideCode.AgainstDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(319125.598,6746219.101,0.0), Point(319025.452,6746251.972,0.0)),FrozenLinkInterface,46089,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(469209, 1.0,"b97557aa-b3dd-408c-866d-618de4204e88:1",0.0,92.373,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(316239.524,6746722.934,0.0), Point(316313.695,6746777.763,0.0)),FrozenLinkInterface,260730742,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(442732, 2.0,"71e43e5b-9f2e-4b12-972f-20f4a649341b:1",0.0,10.195,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(316204.389,6746709.73,0.0), Point(316212.557,6746715.831,0.0)),FrozenLinkInterface,148127875,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(84611, 4.0,"ad0cd79c-9bb5-4889-aaaa-36ee0454d806:1",0.0,175.98,SideCode.AgainstDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(318026.951,6746578.96,0.0), Point(317859.723,6746633.765,0.0)),FrozenLinkInterface,46089,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(434336, 2.0,"376d78e7-5ead-4cc0-b09c-07daa19c3fbd:1",0.0,15.503,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(315308.384,6746269.757,0.0), Point(315323.244,6746274.175,0.0)),FrozenLinkInterface,148121546,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(65830, 5.0,"2bfd5854-a3df-447e-b750-dccb14dcc6a8:1",0.0,129.854,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(316101.841,6746627.408,0.0), Point(316207.595,6746702.756,0.0)),FrozenLinkInterface,44928,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(469213, 5.0,"1e445a6e-8544-4ec7-aa30-67d5b04b63f9:1",0.0,22.149,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(316388.388,6746835.09,0.0), Point(316405.337,6746849.349,0.0)),FrozenLinkInterface,260730742,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(84618, 11.0,"4b94e77e-eefb-41f8-9bfe-1332c9a4d54a:1",0.0,507.746,SideCode.AgainstDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(319608.111,6746061.036,0.0), Point(319125.598,6746219.101,0.0)),FrozenLinkInterface,46089,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(74911, 2.0,"78fc6a27-53d2-418b-a965-e8caaa3c5703:1",0.0,15.033,SideCode.AgainstDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(316420.04,6746846.217,0.0), Point(316405.337,6746849.349,0.0)),FrozenLinkInterface,46088,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(74922, 13.0,"1ea4491c-5016-4920-8aef-3198c772f16e:1",0.0,17.042,SideCode.AgainstDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(316961.409,6746838.525,0.0), Point(316944.476,6746840.448,0.0)),FrozenLinkInterface,46088,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(65826, 1.0,"2053275a-61fd-42bb-bcc8-4a076faecb55:1",0.0,73.906,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(315812.165,6746432.378,0.0), Point(315875.621,6746468.732,0.0)),FrozenLinkInterface,44928,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(74917, 8.0,"ef814574-9a42-4cb9-94ba-3b4fc18d38d5:1",0.0,42.907,SideCode.AgainstDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(316716.137,6746827.413,0.0), Point(316673.363,6746830.766,0.0)),FrozenLinkInterface,46088,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(436687, 1.0,"2bfd5854-a3df-447e-b750-dccb14dcc6a8:1",129.854,130.775,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(316207.595,6746702.756,0.0), Point(316208.349,6746703.284,0.0)),FrozenLinkInterface,148122112,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(434342, 8.0,"7efec6fe-81c2-48f1-bf95-79b14af082a3:1",0.0,78.379,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(315561.068,6746338.044,0.0), Point(315636.749,6746358.431,0.0)),FrozenLinkInterface,148121546,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(84616, 9.0,"99e0e563-a6f7-4861-b060-a878e6b9ce69:1",0.0,284.389,SideCode.AgainstDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(319025.452,6746251.972,0.0), Point(318755.112,6746340.239,0.0)),FrozenLinkInterface,46089,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(469694, 4.0,"53b570bf-8740-4ec3-9095-7b07285c25ac:1",0.0,13.758,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(316370.394,6746834.921,0.0), Point(316381.133,6746843.52,0.0)),FrozenLinkInterface,260730736,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(84612, 5.0,"a7d73293-6f46-4a7c-bcde-da598d7b368e:1",0.0,370.626,SideCode.AgainstDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(318379.279,6746463.95,0.0), Point(318026.951,6746578.96,0.0)),FrozenLinkInterface,46089,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(74913, 4.0,"e39a60a6-7257-4a71-8a86-390ffe8b8286:1",0.0,66.148,SideCode.AgainstDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(316498.102,6746840.417,0.0), Point(316432.093,6746844.569,0.0)),FrozenLinkInterface,46088,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(469210, 2.0,"29b3558a-8f36-41a5-8c25-1db9926f0647:1",0.0,42.268,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(316313.695,6746777.763,0.0), Point(316347.796,6746802.737,0.0)),FrozenLinkInterface,260730742,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(442731, 1.0,"dcafca85-2224-472f-bef1-ed31f9e793a2:1",130.249,131.173,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(316203.633,6746709.199,0.0), Point(316204.389,6746709.73,0.0)),FrozenLinkInterface,148127875,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(434337, 3.0,"7e7e7597-f2c7-4f04-9d31-4ec15d43d438:1",0.0,107.923,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(315323.244,6746274.175,0.0), Point(315426.675,6746304.987,0.0)),FrozenLinkInterface,148121546,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(469691, 1.0,"8844800d-9f70-4186-b89f-00aef1241df9:1",0.0,87.041,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(316230.533,6746734.256,0.0), Point(316301.699,6746784.123,0.0)),FrozenLinkInterface,260730736,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(84614, 7.0,"0822e4f9-ef27-482f-b6d8-910a16d69bca:1",0.0,113.407,SideCode.AgainstDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(318533.391,6746413.211,0.0), Point(318425.588,6746448.419,0.0)),FrozenLinkInterface,46089,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(439757, 6.0,"dcafca85-2224-472f-bef1-ed31f9e793a2:1",0.0,130.249,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(316096.821,6746634.662,0.0), Point(316203.633,6746709.199,0.0)),FrozenLinkInterface,148127435,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(84621, 14.0,"ebc5f36b-7523-4646-b14b-16f3481cb80e:1",0.0,258.451,SideCode.AgainstDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(320141.882,6745830.016,0.0), Point(319911.637,6745947.24,0.0)),FrozenLinkInterface,46089,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(84624, 17.0,"419a1105-de47-4445-9661-42ffd78603fe:1",0.0,116.659,SideCode.AgainstDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(320471.66,6745657.591,0.0), Point(320365.991,6745706.829,0.0)),FrozenLinkInterface,46089,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(74926, 17.0,"c345bfdb-1283-4257-b6f9-c3c5bd416935:1",0.0,45.42,SideCode.AgainstDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(317141.74,6746814.67,0.0), Point(317096.757,6746820.958,0.0)),FrozenLinkInterface,46088,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(439754, 3.0,"ab011bee-bfad-49c1-ad75-fb70e706ff63:1",0.0,100.015,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(315870.078,6746476.154,0.0), Point(315950.938,6746535.009,0.0)),FrozenLinkInterface,148127435,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(469695, 5.0,"b14221e3-e776-41d8-9921-af3fafdd04dd:1",0.0,15.998,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(316381.133,6746843.52,0.0), Point(316394.302,6746852.603,0.0)),FrozenLinkInterface,260730736,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(74921, 12.0,"09b12c6d-a366-489e-bc14-235536b3afbd:1",0.0,21.989,SideCode.AgainstDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(316944.476,6746840.448,0.0), Point(316922.628,6746842.933,0.0)),FrozenLinkInterface,46088,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(65827, 2.0,"011f0f91-42f8-4467-b253-a476dcf43c80:1",0.0,96.92,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(315875.621,6746468.732,0.0), Point(315955.658,6746523.39,0.0)),FrozenLinkInterface,44928,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(434341, 7.0,"8b423158-f964-4602-a8af-b7e7a6c108a2:1",0.0,46.8,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(315515.63,6746326.837,0.0), Point(315561.068,6746338.044,0.0)),FrozenLinkInterface,148121546,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(74931, 22.0,"d7acce87-fdfe-4a67-b86e-fe10e6170c7c:1",0.0,112.385,SideCode.AgainstDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(317525.223,6746743.713,0.0), Point(317415.093,6746766.103,0.0)),FrozenLinkInterface,46088,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(74918, 9.0,"d907614c-61f3-4d66-b534-49c0a024a6db:1",0.0,135.607,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(316716.137,6746827.413,0.0), Point(316847.966,6746844.821,0.0)),FrozenLinkInterface,46088,Some(DateTime.now().minusDays(2)),None)
      )

      val projectLinks = Seq(
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.Combined,Discontinuity.Continuous,AddrMRange(0,86),AddrMRange(0,86),None,None,Some("test"),"463ec1e2-7744-4817-b67b-0b7453eb1a0a:1", 0.0,86.774,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(315225.331,6746244.622,102.999), Point(315226.770000984,6746245.029985757,102.962), Point(315286.9000009806,6746263.370985794,101.099), Point(315308.3840009793,6746269.756985807,100.782)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,86.774,70651,434335,1,reversed = false,None,1652179948783L,148121546,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.Combined,Discontinuity.Continuous,AddrMRange(86,101),AddrMRange(86,101),None,None,Some("test"),"376d78e7-5ead-4cc0-b09c-07daa19c3fbd:1", 0.0,15.503,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(315308.384,6746269.757,100.782), Point(315323.2440009785,6746274.174985815,100.772)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,15.503,70651,434336,1,reversed = false,None,1652179948783L,148121546,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.Combined,Discontinuity.Continuous,AddrMRange(101,209),AddrMRange(101,209),None,None,Some("test"),"7e7e7597-f2c7-4f04-9d31-4ec15d43d438:1", 0.0,107.923,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(315323.244,6746274.175,100.772), Point(315355.7800009767,6746283.840985836,101.266), Point(315426.6750009726,6746304.986985879,102.725)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,107.923,70651,434337,1,reversed = false,None,1652179948783L,148121546,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.Combined,Discontinuity.Continuous,AddrMRange(209,263),AddrMRange(209,263),None,None,Some("test"),"b27b0fd8-6505-4b45-95cf-1e6db858b041:1", 0.0,53.811,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(315426.675,6746304.987,102.725), Point(315428.7580009728,6746305.64098588,102.743), Point(315478.712,6746318.682,102.85499903765081)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,53.811,70651,434338,1,reversed = false,None,1652179948783L,148121546,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.Combined,Discontinuity.Continuous,AddrMRange(263,275),AddrMRange(263,275),None,None,Some("test"),"18c532e5-2e89-4e0a-badd-7f793695e045:1", 0.0,12.649,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(315478.712,6746318.682,102.855), Point(315490.9740009692,6746321.784985918,102.896)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,12.649,70651,434339,1,reversed = false,None,1652179948783L,148121546,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.Combined,Discontinuity.Continuous,AddrMRange(275,300),AddrMRange(275,300),None,None,Some("test"),"7aec4d47-6de7-478a-a285-e73b2cfa8239:1", 0.0,25.169,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(315490.974,6746321.785,102.896), Point(315514.8190009679,6746326.6379859345,102.987), Point(315515.6300009678,6746326.836985934,102.99)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,25.169,70651,434340,1,reversed = false,None,1652179948783L,148121546,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.Combined,Discontinuity.Continuous,AddrMRange(300,347),AddrMRange(300,347),None,None,Some("test"),"8b423158-f964-4602-a8af-b7e7a6c108a2:1", 0.0,46.8,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(315515.63,6746326.837,102.99), Point(315561.0680009652,6746338.043985962,102.725)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,46.8,70651,434341,1,reversed = false,None,1652179948783L,148121546,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.Combined,Discontinuity.Continuous,AddrMRange(347,425),AddrMRange(347,425),None,None,Some("test"),"7efec6fe-81c2-48f1-bf95-79b14af082a3:1", 0.0,78.379,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(315561.068,6746338.044,102.725), Point(315635.958000961,6746358.219986007,102.701), Point(315636.7490009609,6746358.430986009,102.708)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,78.379,70651,434342,1,reversed = false,None,1652179948783L,148121546,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.Combined,Discontinuity.Continuous,AddrMRange(425,536),AddrMRange(425,536),None,None,Some("test"),"19ebcf44-77ef-49fe-ae10-fc3428e17873:1", 0.0,111.345,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(315636.749,6746358.431,102.708), Point(315672.2000009591,6746368.694986029,102.765), Point(315701.2100009574,6746380.102986046,102.858), Point(315739.9330009553,6746397.925986071,103.096), Point(315740.5120009555,6746398.19298607,103.106)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,111.345,70651,434343,1,reversed = false,None,1652179948783L,148121546,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.Combined,Discontinuity.Continuous,AddrMRange(536,615),AddrMRange(536,615),None,None,Some("test"),"762d8ae1-fb60-4e5a-85bf-11a054dce662:1", 0.0,79.39,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(315740.512,6746398.193,103.106), Point(315770.7470009537,6746412.516986087,103.428), Point(315812.165,6746432.378,103.88699820558763)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,79.39,70651,434344,1,reversed = false,None,1652179948783L,148121546,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.LeftSide,Discontinuity.Continuous,AddrMRange(615,685),AddrMRange(615,685),None,None,Some("test"),"67af9e96-b0a1-4952-b156-ead08556878d:1", 0.0,64.643,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(315812.165,6746432.378,103.887), Point(315818.976000951,6746442.784986115,103.966), Point(315862.7140009487,6746471.286986143,104.306)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,64.643,73470,439752,1,reversed = false,None,1652179948783L,148127435,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.RightSide,Discontinuity.Continuous,AddrMRange(615,695),AddrMRange(615,695),None,None,Some("test"),"2053275a-61fd-42bb-bcc8-4a076faecb55:1", 0.0,73.906,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(315812.165,6746432.378,103.887), Point(315825.4130009506,6746435.243986121,103.996), Point(315875.621000948,6746468.731986151,104.255)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,73.906,6187,65826,1,reversed = false,None,1652179948783L,44928,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.LeftSide,Discontinuity.Continuous,AddrMRange(685,695),AddrMRange(685,695),None,None,Some("test"),"c2256b07-3f50-42af-8243-6325e2bc3c65:1", 0.0,8.827,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(315862.714,6746471.287,104.306), Point(315870.078,6746476.154,104.24600009858474)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,8.827,73470,439753,1,reversed = false,None,1652179948783L,148127435,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.RightSide,Discontinuity.Continuous,AddrMRange(695,800),AddrMRange(695,800),None,None,Some("test"),"011f0f91-42f8-4467-b253-a476dcf43c80:1", 0.0,96.92,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(315875.621,6746468.732,104.255), Point(315904.5490009466,6746488.495986168,104.145), Point(315952.5830009438,6746521.205986196,103.914), Point(315955.658,6746523.39,103.9159998202383)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,96.92,6187,65827,1,reversed = false,None,1652179948783L,44928,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.LeftSide,Discontinuity.Continuous,AddrMRange(695,804),AddrMRange(695,804),None,None,Some("test"),"ab011bee-bfad-49c1-ad75-fb70e706ff63:1", 0.0,100.015,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(315870.078,6746476.154,104.246), Point(315895.2590009473,6746494.312986163,104.087), Point(315923.4220009455,6746514.4959861785,104.012), Point(315950.938000944,6746535.008986196,103.909)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,100.015,73470,439754,1,reversed = false,None,1652179948783L,148127435,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.RightSide,Discontinuity.Continuous,AddrMRange(800,821),AddrMRange(800,821),None,None,Some("test"),"6658b9be-ecc1-480a-8d46-1e17e207571a:1", 0.0,19.665,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(315955.658,6746523.39,103.916), Point(315971.6610009428,6746534.817986206,103.976)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,19.665,6187,65828,1,reversed = false,None,1652179948783L,44928,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.LeftSide,Discontinuity.Continuous,AddrMRange(804,826),AddrMRange(804,826),None,None,Some("test"),"08e3a420-6edb-47c3-8e7b-c9e64ce95af4:1", 0.0,20.422,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(315950.938,6746535.009,103.909), Point(315952.719000944,6746536.336986196,103.911), Point(315967.2610009432,6746547.280986204,103.952)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,20.422,73470,439755,1,reversed = false,None,1652179948783L,148127435,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.RightSide,Discontinuity.Continuous,AddrMRange(821,994),AddrMRange(821,994),None,None,Some("test"),"a1880ddf-0227-4c55-a524-b1a7ff898a60:1", 0.0,159.773,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(315971.661,6746534.818,103.976), Point(316003.5270009413,6746558.021986224,104.047), Point(316043.8350009391,6746587.816986251,104.024), Point(316074.2000009377,6746608.518986269,103.668), Point(316101.8410009362,6746627.407986283,103.304)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,159.773,6187,65829,1,reversed = false,None,1652179948783L,44928,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.LeftSide,Discontinuity.Continuous,AddrMRange(826,995),AddrMRange(826,995),None,None,Some("test"),"86c432a8-08ed-4a27-9e79-cf6b370b0cad:1", 0.0,156.295,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(315967.261,6746547.281,103.952), Point(316036.9130009395,6746595.831986247,103.983), Point(316096.8210009365,6746634.66198628,103.322)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,156.295,73470,439756,1,reversed = false,None,1652179948783L,148127435,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.RightSide,Discontinuity.Continuous,AddrMRange(994,1135),AddrMRange(994,1135),None,None,Some("test"),"2bfd5854-a3df-447e-b750-dccb14dcc6a8:1", 0.0,129.854,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(316101.841,6746627.408,103.304), Point(316139.699000934,6746654.938986307,103.07), Point(316188.6300009316,6746689.477986333,102.933), Point(316207.595,6746702.756,102.886836231065)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,129.854,6187,65830,1,reversed = false,None,1652179948783L,44928,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.LeftSide,Discontinuity.Continuous,AddrMRange(995,1135),AddrMRange(995,1135),None,None,Some("test"),"dcafca85-2224-472f-bef1-ed31f9e793a2:1", 0.0,130.249,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(316096.821,6746634.662,103.322), Point(316134.6390009345,6746661.2749863025,103.128), Point(316182.2140009318,6746694.285986329,103.009), Point(316203.633,6746709.199,103.06533839691322)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,130.249,73470,439757,1,reversed = false,None,1652179948783L,148127435,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.LeftSide,Discontinuity.Continuous,AddrMRange(1135,1136),AddrMRange(1135,1136),None,None,Some("test"),"dcafca85-2224-472f-bef1-ed31f9e793a2:1", 130.249,131.173,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(316203.633,6746709.199,103.06533839691322), Point(316204.2650009307,6746709.6389863435,103.067), Point(316204.3890009307,6746709.7299863435,103.065)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,0.924,73064,442731,1,reversed = false,None,1652179948783L,148127875,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.RightSide,Discontinuity.Continuous,AddrMRange(1135,1136),AddrMRange(1135,1136),None,None,Some("test"),"2bfd5854-a3df-447e-b750-dccb14dcc6a8:1", 129.854,130.775,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(316207.595,6746702.756,102.886836231065), Point(316208.3490009305,6746703.283986345,102.885)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,0.921,73189,436687,1,reversed = false,None,1652179948783L,148122112,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.RightSide,Discontinuity.MinorDiscontinuity,AddrMRange(1136,1148),AddrMRange(1136,1148),None,None,Some("test"),"8c0f1be6-b863-4bbd-8ad8-0f3b9b1aba59:1", 0.0,11.086,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(316208.349,6746703.284,102.885), Point(316217.431,6746709.642,102.81700207257717)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,11.086,73189,436688,1,reversed = false,None,1652179948783L,148122112,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.LeftSide,Discontinuity.MinorDiscontinuity,AddrMRange(1136,1148),AddrMRange(1136,1148),None,None,Some("test"),"71e43e5b-9f2e-4b12-972f-20f4a649341b:1", 0.0,10.195,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(316204.389,6746709.73,103.065), Point(316212.557,6746715.831,102.94600022902226)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,10.195,73064,442732,1,reversed = false,None,1652179948783L,148127875,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.LeftSide,Discontinuity.Continuous,AddrMRange(1148,1241),AddrMRange(1148,1241),None,None,Some("test"),"8844800d-9f70-4186-b89f-00aef1241df9:1", 0.0,87.041,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(316230.533,6746734.256,102.817), Point(316242.2910009287,6746740.601986365,102.804), Point(316258.6040009278,6746751.658986374,102.857), Point(316280.3500009268,6746767.160986389,102.937), Point(316301.699,6746784.123,102.44800309859373)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,87.041,83706,469691,1,reversed = false,None,1652179948783L,260730736,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.RightSide,Discontinuity.Continuous,AddrMRange(1148,1244),AddrMRange(1148,1244),None,None,Some("test"),"b97557aa-b3dd-408c-866d-618de4204e88:1", 0.0,92.373,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(316239.524,6746722.934,102.689), Point(316246.6300009284,6746729.400986368,102.65), Point(316257.7910009281,6746739.052986376,102.739), Point(316270.1740009274,6746747.953986382,102.801), Point(316291.1740009262,6746761.780986395,102.89), Point(316313.6950009249,6746777.7629864095,102.497)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,92.373,83762,469209,1,reversed = false,None,1652179948783L,260730742,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.RightSide,Discontinuity.Continuous,AddrMRange(1244,1288),AddrMRange(1244,1288),None,None,Some("test"),"29b3558a-8f36-41a5-8c25-1db9926f0647:1", 0.0,42.268,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(316313.695,6746777.763,102.497), Point(316347.7960009232,6746802.736986427,103.028)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,42.268,83762,469210,1,reversed = false,None,1652179948783L,260730742,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.LeftSide,Discontinuity.Continuous,AddrMRange(1241,1291),AddrMRange(1241,1291),None,None,Some("test"),"545a77ad-2ced-411e-8037-9e6658646110:1", 0.0,46.325,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(316301.699,6746784.123,102.448), Point(316339.164,6746811.37,102.95799766983123)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,46.325,83706,469692,1,reversed = false,None,1652179948783L,260730736,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.RightSide,Discontinuity.Continuous,AddrMRange(1288,1328),AddrMRange(1288,1328),None,None,Some("test"),"c18f88f8-16ad-4e3e-b21d-c2b0b97b16cd:1", 0.0,38.866,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(316347.796,6746802.737,103.028), Point(316378.5190009215,6746826.540986445,103.226)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,38.866,83762,469211,1,reversed = false,None,1652179948783L,260730742,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.LeftSide,Discontinuity.Continuous,AddrMRange(1291,1333),AddrMRange(1291,1333),None,None,Some("test"),"67d69d87-8dc6-4d46-bdd9-ba5c82c8807f:1", 0.0,39.115,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(316339.164,6746811.37,102.958), Point(316370.394000922,6746834.92098644,103.098)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,39.115,83706,469693,1,reversed = false,None,1652179948783L,260730736,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.RightSide,Discontinuity.MinorDiscontinuity,AddrMRange(1328,1342),AddrMRange(1328,1342),None,None,Some("test"),"84cf7440-59be-4bf6-a6cb-7b59a45ec001:1", 0.0,13.057,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(316378.519,6746826.541,103.226), Point(316388.388000921,6746835.089986451,103.336)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,13.057,83762,469212,1,reversed = false,None,1652179948783L,260730742,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.LeftSide,Discontinuity.MinorDiscontinuity,AddrMRange(1333,1348),AddrMRange(1333,1348),None,None,Some("test"),"53b570bf-8740-4ec3-9095-7b07285c25ac:1", 0.0,13.758,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(316370.394,6746834.921,103.098), Point(316381.1330009217,6746843.519986448,103.227)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,13.758,83706,469694,1,reversed = false,None,1652179948783L,260730736,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.RightSide,Discontinuity.MinorDiscontinuity,AddrMRange(1342,1365),AddrMRange(1342,1365),None,None,Some("test"),"1e445a6e-8544-4ec7-aa30-67d5b04b63f9:1", 0.0,22.149,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(316388.388,6746835.09,0.0), Point(316405.337,6746849.349,0.0)),projectId,  RoadAddressChangeType.Termination,AdministrativeClass.Municipality, FrozenLinkInterface,22.149,83762,469213,1,reversed = false,None,1652179948783L,260730742,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.LeftSide,Discontinuity.Continuous,AddrMRange(1348,1365),AddrMRange(1348,1365),None,None,Some("test"),"b14221e3-e776-41d8-9921-af3fafdd04dd:1", 0.0,15.998,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(316381.133,6746843.52,0.0), Point(316394.302,6746852.603,0.0)),projectId,  RoadAddressChangeType.Termination,AdministrativeClass.Municipality, FrozenLinkInterface,15.998,83706,469695,1,reversed = false,None,1652179948783L,260730736,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.Combined,Discontinuity.Continuous,AddrMRange(1365,1376),AddrMRange(1365,1376),None,None,Some("test"),"83167055-d58e-4e4f-bb09-f396821b4b8b:1", 0.0,11.505,SideCode.AgainstDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(316405.337,6746849.349,0.0), Point(316394.302,6746852.603,0.0)),projectId,  RoadAddressChangeType.Termination,AdministrativeClass.Municipality, FrozenLinkInterface,11.505,7297,74910,1,reversed = false,None,1652179948783L,46088,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.Combined,Discontinuity.Continuous,AddrMRange(1376,1391),AddrMRange(1376,1391),None,None,Some("test"),"78fc6a27-53d2-418b-a965-e8caaa3c5703:1", 0.0,15.033,SideCode.AgainstDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(316420.04,6746846.217,0.0), Point(316405.337,6746849.349,0.0)),projectId,  RoadAddressChangeType.Termination,AdministrativeClass.Municipality, FrozenLinkInterface,15.033,7297,74911,1,reversed = false,None,1652179948783L,46088,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.Combined,Discontinuity.Continuous,AddrMRange(1391,1403),AddrMRange(1391,1403),None,None,Some("test"),"b8e333d2-84c5-448d-944f-1a514d8774aa:1", 0.0,12.165,SideCode.AgainstDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(316432.093,6746844.569,103.559), Point(316420.04,6746846.217,103.52600038888684)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.Municipality, FrozenLinkInterface,12.165,7297,74912,1,reversed = false,None,1652179948783L,46088,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.Combined,Discontinuity.Continuous,AddrMRange(1403,1469),AddrMRange(1403,1469),None,None,Some("test"),"e39a60a6-7257-4a71-8a86-390ffe8b8286:1", 0.0,66.148,SideCode.AgainstDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(316498.102,6746840.417,103.863), Point(316471.5940009165,6746842.171986499,103.751), Point(316442.1240009183,6746843.612986484,103.623), Point(316432.0930009187,6746844.568986477,103.559)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.Municipality, FrozenLinkInterface,66.148,7297,74913,1,reversed = false,None,1652179948783L,46088,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.Combined,Discontinuity.Continuous,AddrMRange(1469,1490),AddrMRange(1469,1490),None,None,Some("test"),"4b8976c6-b101-49ad-935a-bf47b6544844:2", 0.0,21.709,SideCode.AgainstDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(316519.807,6746840.14,103.81), Point(316500.764000915,6746840.273986518,103.828), Point(316498.102,6746840.417,103.86299593531027)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.Municipality, FrozenLinkInterface,21.709,7297,74914,1,reversed = false,None,1652179948783L,46088,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.Combined,Discontinuity.Continuous,AddrMRange(1490,1614),AddrMRange(1490,1614),None,None,Some("test"),"4e1bf72d-a4d7-49bd-9667-dfb5b79767af:1", 0.0,124.282,SideCode.AgainstDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(316643.823,6746832.132,103.962), Point(316625.7240009082,6746832.969986593,103.964), Point(316570.8810009112,6746836.186986559,103.824), Point(316519.807,6746840.14,103.81000011160427)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.Municipality, FrozenLinkInterface,124.282,7297,74915,1,reversed = false,None,1652179948783L,46088,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.Combined,Discontinuity.Continuous,AddrMRange(1614,1643),AddrMRange(1614,1643),None,None,Some("test"),"091f3e47-0dce-453f-9835-a4172090e038:1", 0.0,29.572,SideCode.AgainstDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(316673.363,6746830.766,103.788), Point(316643.8230009072,6746832.131986604,103.962)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.Municipality, FrozenLinkInterface,29.572,7297,74916,1,reversed = false,None,1652179948783L,46088,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.Combined,Discontinuity.Continuous,AddrMRange(1643,1685),AddrMRange(1643,1685),None,None,Some("test"),"ef814574-9a42-4cb9-94ba-3b4fc18d38d5:1", 0.0,42.907,SideCode.AgainstDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(316716.137,6746827.413,103.468), Point(316696.0440009043,6746829.1749866335,103.595), Point(316673.3630009056,6746830.76598662,103.788)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.Municipality, FrozenLinkInterface,42.907,7297,74917,1,reversed = false,None,1652179948783L,46088,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.Combined,Discontinuity.Continuous,AddrMRange(1685,1820),AddrMRange(1685,1820),None,None,Some("test"),"d907614c-61f3-4d66-b534-49c0a024a6db:1", 0.0,135.607,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(316716.137,6746827.413,103.468), Point(316745.7640009016,6746825.365986666,103.335), Point(316774.9160009002,6746825.744986681,103.211), Point(316804.0410008988,6746829.615986697,103.189), Point(316822.6990008976,6746832.189986709,103.225), Point(316834.5140008971,6746835.9259867165,103.242), Point(316843.1290008965,6746841.154986721,103.459), Point(316847.9660008963,6746844.8209867235,103.365)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.Municipality, FrozenLinkInterface,135.607,7297,74918,1,reversed = false,None,1652179948783L,46088,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.Combined,Discontinuity.Continuous,AddrMRange(1820,1837),AddrMRange(1820,1837),None,None,Some("test"),"237dcbf1-08b8-4c70-bc2b-3a63dd3b7c5e:1", 0.0,17.039,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(316847.966,6746844.821,103.365), Point(316860.4360008957,6746856.4319867315,103.526)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.Municipality, FrozenLinkInterface,17.039,7297,74919,1,reversed = false,None,1652179948783L,46088,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.Combined,Discontinuity.Continuous,AddrMRange(1837,1901),AddrMRange(1837,1901),None,None,Some("test"),"dbdd5839-4de8-43c0-a2ee-b805f302605e:1", 0.0,64.134,SideCode.AgainstDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(316922.628,6746842.933,103.531), Point(316920.8510008925,6746843.122986766,103.575), Point(316896.1340008937,6746845.863986754,103.66), Point(316883.5500008945,6746848.007986746,103.709), Point(316872.3720008949,6746850.836986737,103.626), Point(316860.4360008957,6746856.4319867315,103.526)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.Municipality, FrozenLinkInterface,64.134,7297,74920,1,reversed = false,None,1652179948783L,46088,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.Combined,Discontinuity.Continuous,AddrMRange(1901,1923),AddrMRange(1901,1923),None,None,Some("test"),"09b12c6d-a366-489e-bc14-235536b3afbd:1", 0.0,21.989,SideCode.AgainstDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(316944.476,6746840.448,103.538), Point(316932.9160008919,6746841.684986774,103.562), Point(316922.628,6746842.933,103.53100123880267)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.Municipality, FrozenLinkInterface,21.989,7297,74921,1,reversed = false,None,1652179948783L,46088,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.Combined,Discontinuity.Continuous,AddrMRange(1923,1940),AddrMRange(1923,1940),None,None,Some("test"),"1ea4491c-5016-4920-8aef-3198c772f16e:1", 0.0,17.042,SideCode.AgainstDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(316961.409,6746838.525,103.531), Point(316944.4760008911,6746840.447986781,103.538)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.Municipality, FrozenLinkInterface,17.042,7297,74922,1,reversed = false,None,1652179948783L,46088,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.Combined,Discontinuity.Continuous,AddrMRange(1940,1982),AddrMRange(1940,1982),None,None,Some("test"),"20209b44-4711-41d1-aeee-de075e5adabb:1", 0.0,42.117,SideCode.AgainstDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(317003.219,6746833.527,103.569), Point(316986.956000889,6746835.4629868055,103.575), Point(316970.4940008897,6746837.093986794,103.558), Point(316961.409,6746838.525,103.53100128520997)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.Municipality, FrozenLinkInterface,42.117,7297,74923,1,reversed = false,None,1652179948783L,46088,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.Combined,Discontinuity.Continuous,AddrMRange(1982,2028),AddrMRange(1982,2028),None,None,Some("test"),"ba8ea28c-f1ab-4204-a25d-cfc426981fe2:1", 0.0,46.351,SideCode.AgainstDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(317049.21,6746827.76,103.57), Point(317016.8910008873,6746831.891986824,103.58), Point(317003.219,6746833.527,103.56900038678378)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.Municipality, FrozenLinkInterface,46.351,7297,74924,1,reversed = false,None,1652179948783L,46088,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.Combined,Discontinuity.Continuous,AddrMRange(2028,2076),AddrMRange(2028,2076),None,None,Some("test"),"b0ded3ac-dfe9-4a4c-bac1-ebb11bc72e16:1", 0.0,48.032,SideCode.AgainstDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(317096.757,6746820.958,103.753), Point(317073.3940008841,6746824.157986857,103.635), Point(317049.2100008856,6746827.759986841,103.57)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.Municipality, FrozenLinkInterface,48.032,7297,74925,1,reversed = false,None,1652179948783L,46088,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.Combined,Discontinuity.Continuous,AddrMRange(2076,2121),AddrMRange(2076,2121),None,None,Some("test"),"c345bfdb-1283-4257-b6f9-c3c5bd416935:1", 0.0,45.42,SideCode.AgainstDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(317141.74,6746814.67,103.939), Point(317119.4890008818,6746817.827986885,103.844), Point(317096.757,6746820.958,103.75300182176159)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.Municipality, FrozenLinkInterface,45.42,7297,74926,1,reversed = false,None,1652179948783L,46088,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.Combined,Discontinuity.Continuous,AddrMRange(2121,2166),AddrMRange(2121,2166),None,None,Some("test"),"5ac5f971-b69d-4500-ade8-3c09e6b989ab:1", 0.0,44.873,SideCode.AgainstDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(317186.232,6746808.852,104.093), Point(317172.587000879,6746810.822986916,104.072), Point(317158.2990008797,6746812.685986905,104.021), Point(317141.7400008806,6746814.669986895,103.939)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.Municipality, FrozenLinkInterface,44.873,7297,74927,1,reversed = false,None,1652179948783L,46088,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.Combined,Discontinuity.Continuous,AddrMRange(2166,2213),AddrMRange(2166,2213),None,None,Some("test"),"090e5444-f3f3-4a60-bee7-9a49ac449331:1", 0.0,47.329,SideCode.AgainstDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(317232.814,6746800.481,104.38), Point(317210.0350008769,6746804.686986937,104.23), Point(317186.2320008783,6746808.851986923,104.093)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.Municipality, FrozenLinkInterface,47.329,7297,74928,1,reversed = false,None,1652179948783L,46088,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.Combined,Discontinuity.Continuous,AddrMRange(2213,2260),AddrMRange(2213,2260),None,None,Some("test"),"692ec13e-baa6-424b-8936-13d498238c45:1", 0.0,47.293,SideCode.AgainstDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(317279.382,6746792.234,104.494), Point(317254.2980008747,6746796.619986963,104.403), Point(317232.8140008757,6746800.480986951,104.38)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.Municipality, FrozenLinkInterface,47.293,7297,74929,1,reversed = false,None,1652179948783L,46088,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.Combined,Discontinuity.Continuous,AddrMRange(2260,2398),AddrMRange(2260,2398),None,None,Some("test"),"40c78727-a78b-4d00-bb65-e56f4f86ba4c:1", 0.0,138.212,SideCode.AgainstDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(317415.093,6746766.103,105.342), Point(317368.1770008686,6746774.406987029,104.95), Point(317317.2180008713,6746784.5789870005,104.664), Point(317279.382,6746792.234,104.49400065231059)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.Municipality, FrozenLinkInterface,138.212,7297,74930,1,reversed = false,None,1652179948783L,46088,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.Combined,Discontinuity.Continuous,AddrMRange(2398,2510),AddrMRange(2398,2510),None,None,Some("test"),"d7acce87-fdfe-4a67-b86e-fe10e6170c7c:1", 0.0,112.385,SideCode.AgainstDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(317525.223,6746743.713,106.495), Point(317524.9510008605,6746743.77898712,106.483), Point(317503.1860008615,6746748.427987108,106.183), Point(317453.0360008643,6746758.530987079,105.666), Point(317415.0930008661,6746766.102987058,105.342)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.Municipality, FrozenLinkInterface,112.385,7297,74931,1,reversed = false,None,1652179948783L,46088,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.Combined,Discontinuity.Continuous,AddrMRange(2510,2534),AddrMRange(2510,2534),None,None,Some("test"),"a088c201-7eb2-4fa6-9e6c-597d2c0744c1:1", 0.0,23.66,SideCode.AgainstDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(317548.213,6746738.124,106.511), Point(317525.2230008603,6746743.712987122,106.495)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.State, FrozenLinkInterface,23.66,7584,84608,1,reversed = false,None,1652179948783L,46089,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.Combined,Discontinuity.Continuous,AddrMRange(2534,2691),AddrMRange(2534,2691),None,None,Some("test"),"991a1632-94d0-4e91-b7b3-1c8caea6fab6:1", 0.0,158.582,SideCode.AgainstDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(317698.229,6746686.731,106.438), Point(317665.2290008529,6746697.643987205,106.445), Point(317622.4270008554,6746712.890987176,106.537), Point(317592.265000857,6746723.231987161,106.512), Point(317567.988000858,6746731.227987147,106.507), Point(317548.213,6746738.124,106.5109999068905)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.State, FrozenLinkInterface,158.582,7584,84609,1,reversed = false,None,1652179948783L,46089,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.Combined,Discontinuity.Continuous,AddrMRange(2691,2860),AddrMRange(2691,2860),None,None,Some("test"),"7115cb86-e744-471a-a859-0cb6def5b562:1", 0.0,169.963,SideCode.AgainstDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(317859.723,6746633.765,106.067), Point(317793.8700008462,6746654.901987276,106.217), Point(317737.7570008491,6746673.205987244,106.359), Point(317698.2290008511,6746686.730987221,106.438)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.State, FrozenLinkInterface,169.963,7584,84610,1,reversed = false,None,1652179948783L,46089,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.Combined,Discontinuity.Continuous,AddrMRange(2860,3035),AddrMRange(2860,3035),None,None,Some("test"),"ad0cd79c-9bb5-4889-aaaa-36ee0454d806:1", 0.0,175.98,SideCode.AgainstDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(318026.951,6746578.96,105.289), Point(317972.5070008369,6746596.581987378,105.577), Point(317917.1750008398,6746614.775987347,105.82), Point(317859.723,6746633.765,106.06699948474812)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.State, FrozenLinkInterface,175.98,7584,84611,1,reversed = false,None,1652179948783L,46089,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.Combined,Discontinuity.Continuous,AddrMRange(3035,3403),AddrMRange(3035,3403),None,None,Some("test"),"a7d73293-6f46-4a7c-bcde-da598d7b368e:1", 0.0,370.626,SideCode.AgainstDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(318379.279,6746463.95,103.299), Point(318378.5820008161,6746464.183987612,103.305), Point(318327.3190008188,6746480.676987582,103.628), Point(318264.985000822,6746501.2389875455,103.936), Point(318198.6320008253,6746523.06098751,104.259), Point(318147.3750008279,6746539.71398748,104.582), Point(318103.3430008303,6746553.876987455,104.843), Point(318061.8920008325,6746567.47498743,105.081), Point(318026.951000834,6746578.959987411,105.289)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.State, FrozenLinkInterface,370.626,7584,84612,1,reversed = false,None,1652179948783L,46089,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.Combined,Discontinuity.Continuous,AddrMRange(3403,3452),AddrMRange(3403,3452),None,None,Some("test"),"db8ad68b-e819-4e8c-a4fc-bab05d4a65c3:1", 0.0,48.844,SideCode.AgainstDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(318425.588,6746448.419,103.023), Point(318379.2790008161,6746463.949987613,103.299)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.State, FrozenLinkInterface,48.844,7584,84613,1,reversed = false,None,1652179948783L,46089,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.Combined,Discontinuity.Continuous,AddrMRange(3452,3565),AddrMRange(3452,3565),None,None,Some("test"),"0822e4f9-ef27-482f-b6d8-910a16d69bca:1", 0.0,113.407,SideCode.AgainstDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(318533.391,6746413.211,102.531), Point(318500.7870008101,6746423.938987681,102.643), Point(318467.3670008118,6746434.85698766,102.826), Point(318425.5880008138,6746448.418987635,103.023)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.State, FrozenLinkInterface,113.407,7584,84614,1,reversed = false,None,1652179948783L,46089,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.Combined,Discontinuity.Continuous,AddrMRange(3565,3797),AddrMRange(3565,3797),None,None,Some("test"),"0526c121-c04d-4f57-81bc-4d43971c9cc7:1", 0.0,233.422,SideCode.AgainstDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(318755.112,6746340.239,101.98), Point(318701.9390008,6746357.925987792,102.072), Point(318641.5350008028,6746377.942987757,102.189), Point(318587.0710008056,6746395.830987728,102.315), Point(318533.3910008084,6746413.210987696,102.531)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.State, FrozenLinkInterface,233.422,7584,84615,1,reversed = false,None,1652179948783L,46089,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.Combined,Discontinuity.Continuous,AddrMRange(3797,4079),AddrMRange(3797,4079),None,None,Some("test"),"99e0e563-a6f7-4861-b060-a878e6b9ce69:1", 0.0,284.389,SideCode.AgainstDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(319025.452,6746251.972,101.669), Point(318970.9100007866,6746269.283987941,101.719), Point(318893.4480007902,6746294.964987898,101.797), Point(318844.6030007928,6746310.896987869,101.873), Point(318798.5000007951,6746326.258987844,101.908), Point(318755.1120007972,6746340.238987821,101.98)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.State, FrozenLinkInterface,284.389,7584,84616,1,reversed = false,None,1652179948783L,46089,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.Combined,Discontinuity.Continuous,AddrMRange(4079,4184),AddrMRange(4079,4184),None,None,Some("test"),"8c8e8f43-544f-47c9-8078-2d6f87520277:1", 0.0,105.403,SideCode.AgainstDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(319125.598,6746219.101,101.556), Point(319026.1590007841,6746251.73898797,101.658), Point(319025.4520007839,6746251.97198797,101.669)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.State, FrozenLinkInterface,105.403,7584,84617,1,reversed = false,None,1652179948783L,46089,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.Combined,Discontinuity.Continuous,AddrMRange(4184,4689),AddrMRange(4184,4689),None,None,Some("test"),"4b94e77e-eefb-41f8-9bfe-1332c9a4d54a:1", 0.0,507.746,SideCode.AgainstDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(319608.111,6746061.036,101.323), Point(319442.6020007636,6746114.5189881995,101.196), Point(319252.3920007728,6746177.471988096,101.415), Point(319126.2620007788,6746218.883988026,101.555), Point(319125.598000779,6746219.100988025,101.556)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.State, FrozenLinkInterface,507.746,7584,84618,1,reversed = false,None,1652179948783L,46089,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.Combined,Discontinuity.Continuous,AddrMRange(4689,4777),AddrMRange(4689,4777),None,None,Some("test"),"523419ef-0d23-4be1-8dbc-857ef6a01095:1", 0.0,88.747,SideCode.AgainstDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(319692.378,6746033.196,101.598), Point(319651.3660007537,6746046.569988311,101.456), Point(319608.111,6746061.036,101.3230012159918)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.State, FrozenLinkInterface,88.747,7584,84619,1,reversed = false,None,1652179948783L,46089,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.Combined,Discontinuity.Continuous,AddrMRange(4777,5011),AddrMRange(4777,5011),None,None,Some("test"),"539bcbb7-e153-4ceb-b62e-59444c380a03:1", 0.0,235.64,SideCode.AgainstDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(319911.637,6745947.24,103.061), Point(319879.4090007428,6745962.043988433,102.816), Point(319843.7850007446,6745977.433988416,102.501), Point(319797.9990007469,6745995.48398839,102.179), Point(319751.1770007489,6746012.454988366,101.906), Point(319722.6550007503,6746022.53098835,101.793), Point(319692.3780007518,6746033.195988334,101.598)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.State, FrozenLinkInterface,235.64,7584,84620,1,reversed = false,None,1652179948783L,46089,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.Combined,Discontinuity.Continuous,AddrMRange(5011,5268),AddrMRange(5011,5268),None,None,Some("test"),"ebc5f36b-7523-4646-b14b-16f3481cb80e:1", 0.0,258.451,SideCode.AgainstDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(320141.882,6745830.016,104.721), Point(320115.8490007316,6745843.851988558,104.485), Point(320088.4050007331,6745858.580988545,104.371), Point(320067.4370007339,6745870.015988534,104.294), Point(320045.5990007349,6745882.094988521,104.201), Point(320019.5350007362,6745895.364988509,104.042), Point(319988.9580007375,6745910.7599884905,103.775), Point(319956.4560007392,6745926.590988473,103.432), Point(319934.3760007402,6745937.0319884615,103.242), Point(319911.6370007415,6745947.239988449,103.061)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.State, FrozenLinkInterface,258.451,7584,84621,1,reversed = false,None,1652179948783L,46089,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.Combined,Discontinuity.Continuous,AddrMRange(5268,5457),AddrMRange(5268,5457),None,None,Some("test"),"b7b5f7d8-c0b8-461f-85af-7ead59b96f4f:1", 0.0,190.316,SideCode.AgainstDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(320308.469,6745738.012,106.395), Point(320297.5890007232,6745743.913988657,106.326), Point(320282.617000724,6745752.162988648,106.247), Point(320272.9260007243,6745757.257988643,106.17), Point(320258.2160007252,6745765.488988635,105.993), Point(320241.9040007257,6745774.4649886275,105.841), Point(320228.5810007266,6745781.72598862,105.699), Point(320217.157000727,6745788.064988614,105.568), Point(320194.864000728,6745801.004988602,105.283), Point(320149.1310007303,6745826.208988574,104.805), Point(320141.8820007305,6745830.0159885725,104.721)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.State, FrozenLinkInterface,190.316,7584,84622,1,reversed = false,None,1652179948783L,46089,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.Combined,Discontinuity.Continuous,AddrMRange(5457,5522),AddrMRange(5457,5522),None,None,Some("test"),"32724a12-ffa2-4114-8b2b-1d6ada07f6e2:1", 0.0,65.435,SideCode.AgainstDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(320365.991,6745706.829,106.577), Point(320352.3310007207,6745713.864988686,106.582), Point(320337.6690007214,6745721.852988679,106.544), Point(320321.1990007221,6745730.917988667,106.468), Point(320308.469,6745738.012,106.39500204697875)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.State, FrozenLinkInterface,65.435,7584,84623,1,reversed = false,None,1652179948783L,46089,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.Combined,Discontinuity.Continuous,AddrMRange(5522,5638),AddrMRange(5522,5638),None,None,Some("test"),"419a1105-de47-4445-9661-42ffd78603fe:1", 0.0,116.659,SideCode.AgainstDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(320471.66,6745657.591,106.874), Point(320435.2040007168,6745672.47398873,106.767), Point(320401.2550007185,6745688.564988712,106.633), Point(320365.991,6745706.829,106.57700040892132)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.State, FrozenLinkInterface,116.659,7584,84624,1,reversed = false,None,1652179948783L,46089,roadName,None,None,None,None)
      )

      linearLocationDAO.create(linearLocations)
      roadwayDAO.create(roadways)
      projectDAO.create(Project(projectId, ProjectState.Incomplete, "test", "test", DateTime.now(),"test", DateTime.now(), DateTime.now(),"",Seq(ProjectReservedPart(Sequences.nextViitePrimaryKeySeqValue,roadPart)),Seq(ProjectReservedPart(Sequences.nextViitePrimaryKeySeqValue,roadPart)),None,None,Set(14)))
      projectReservedPartDAO.reserveRoadPart(projectId, roadPart, "test")
      projectLinkDAO.create(projectLinks)

      projectService.recalculateProjectLinks(projectId, "test", Set(roadPart))
      val recalculated = projectLinkDAO.fetchProjectLinks(projectId)
      recalculated.size should be (74)
      val (terminated, others) = recalculated.partition(_.status == RoadAddressChangeType.Termination)
      val rightTrack = others.filter(_.track == Track.RightSide)
      val leftTrack = others.filter(_.track == Track.LeftSide)
      rightTrack.map(_.addrMRange.end).max should be (1345) // right track should end at 1345
      leftTrack.map(_.addrMRange.end).max should be (1345) // left track should end at 1345
      terminated.filter(_.track == Track.LeftSide).head.addrMRange.start should be (1345) // terminated left track should start from 1345 (addresses are slided)
      terminated.filter(_.track == Track.RightSide).head.addrMRange.start should be (1345) // terminated right track should start from 1345 (addresses are slided)
      // the next link after the discontinuity jump starts from the same address (1345) as the last link before the discontinuity jump
      others.filter(_.status == RoadAddressChangeType.Transfer).map(_.addrMRange.start).min should be (1345)
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

  test("Test recalculateProjectLinks() When creating roundabout from New and Transfer links then roadway numbers should be assigned correctly. (three different roadways for the roundabout in this case)") {
    /**
     * Based on real life project on road part 33545/1 and road part 43584/1
     *
     *          Before (only 46001/1)         After (roundabout created)
     *                    ^                         ^
     *                    |                         |  RW2
     *                    |                         | 1549
     *               ---->  1591               -----<------   RW4
     *             /                   RW5   /      63      \
     *            ^  1571                   v 83         41 ^
     *  RW0       |                         | 0             |
     *            \                    RW3  \      22      /  RW4
     *              -----^ 1549               ----->^-----
     *                   |                          |  1549
     *                   |                          |   RW1
     *                   |                          |
     */
    runWithRollback{
      val roadPart = RoadPart(46001, 1)
      val roundAboutRoadPart = RoadPart(31000, 1)
      val projectId = Sequences.nextViiteProjectId
      val roadName = Some("testRoad")

      val roadways = Seq(
        Roadway(79881,190813844,roadPart,AdministrativeClass.Municipality,Track.Combined,Discontinuity.Continuous,AddrMRange(739, 957),false,DateTime.now().minusDays(2),None,"test",None,14,TerminationCode.NoTermination,DateTime.now().minusDays(2),None),
        Roadway(79679,190813846,roadPart,AdministrativeClass.Municipality,Track.Combined,Discontinuity.EndOfRoad,AddrMRange(1371, 2025),false,DateTime.now().minusDays(2),None,"test",None,14,TerminationCode.NoTermination,DateTime.now().minusDays(2),None),
        Roadway(79381,190813848,roadPart,AdministrativeClass.Municipality,Track.RightSide,Discontinuity.Continuous,AddrMRange(957, 1371),false,DateTime.now().minusDays(2),None,"test",None,14,TerminationCode.NoTermination,DateTime.now().minusDays(2),None),
        Roadway(78993,190813849,roadPart,AdministrativeClass.Municipality,Track.LeftSide,Discontinuity.Continuous,AddrMRange(957, 1371),false,DateTime.now().minusDays(2),None,"test",None,14,TerminationCode.NoTermination,DateTime.now().minusDays(2),None),
        Roadway(88915,306319328,roadPart,AdministrativeClass.Municipality,Track.Combined,Discontinuity.MinorDiscontinuity,AddrMRange(0, 739),false,DateTime.now().minusDays(2),None,"test",None,14,TerminationCode.NoTermination,DateTime.now().minusDays(2),None)
      )

      val linearLocations = Seq(
        LinearLocation(455709, 5.0,"66f51329-a242-4ecd-b31c-3073ee7702db:1",0.0,108.973,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(332473.875,6786613.27,0.0), Point(332565.361,6786670.989,0.0)),FrozenLinkInterface,190813848,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(455706, 2.0,"25ceced7-cb02-49b2-88dd-357a5456ff41:1",0.0,171.013,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(332300.741,6786520.485,0.0), Point(332451.682,6786600.823,0.0)),FrozenLinkInterface,190813848,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(454177, 2.0,"d9ffb189-9a2d-4bd0-902a-65ba0d06fcb1:1",0.0,170.993,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(332296.309,6786528.258,0.0), Point(332447.582,6786607.936,0.0)),FrozenLinkInterface,190813849,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(480582, 6.0,"74403d0e-d203-4235-ab86-2e7edc8d2119:1",0.0,120.719,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(331722.473,6785860.737,0.0), Point(331803.345,6785950.103,0.0)),FrozenLinkInterface,306319328,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(454228, 6.0,"5feef32a-43a5-468e-86e9-32ded2378a62:1",0.0,89.685,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(332761.975,6787037.154,0.0), Point(332804.651,6787116.031,0.0)),FrozenLinkInterface,190813846,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(480589, 13.0,"8eb94a29-ff14-4f73-b1fd-cc4923236c21:1",0.0,143.291,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(331983.601,6786139.592,0.0), Point(332064.38,6786257.753,0.0)),FrozenLinkInterface,306319328,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(480578, 2.0,"752f5bd5-069d-49ed-a22f-00c1133b95e4:1",0.0,34.087,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(331592.462,6785721.179,0.0), Point(331616.508,6785745.25,0.0)),FrozenLinkInterface,306319328,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(454231, 9.0,"71292433-7692-46f0-9bb9-5e81d793ddc5:1",0.0,16.134,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(332867.767,6787228.03,0.0), Point(332875.94,6787241.94,0.0)),FrozenLinkInterface,190813846,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(480579, 3.0,"c411d69e-eed6-439d-b290-1a1cb1082e46:1",0.0,62.925,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(331616.508,6785745.25,0.0), Point(331662.007,6785788.682,0.0)),FrozenLinkInterface,306319328,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(480586, 10.0,"4e6e154d-696e-4bbd-b73a-b9848c270fcc:1",0.0,92.598,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(331868.066,6786011.747,0.0), Point(331935.978,6786074.69,0.0)),FrozenLinkInterface,306319328,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(480583, 7.0,"eccbc261-54d2-412f-b8e4-20cbe63acedd:1",0.0,27.975,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(331803.345,6785950.103,0.0), Point(331823.583,6785969.417,0.0)),FrozenLinkInterface,306319328,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(454180, 5.0,"298fd801-6580-4563-8832-835737670917:1",0.0,108.263,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(332469.955,6786619.945,0.0), Point(332565.361,6786670.989,0.0)),FrozenLinkInterface,190813849,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(455427, 1.0,"03b2f7b8-c10a-42b2-b176-a7b65ada599a:1",0.0,37.151,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(332086.496,6786278.692,0.0), Point(332108.633,6786308.482,0.0)),FrozenLinkInterface,190813844,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(454227, 5.0,"95c19c8d-d27b-499a-8d44-76bf8198ceb3:1",0.0,48.417,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(332739.739,6786994.148,0.0), Point(332761.975,6787037.154,0.0)),FrozenLinkInterface,190813846,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(455430, 4.0,"c5bee88c-6e97-40f5-9bac-e1f2a31d4332:1",0.0,17.808,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(332169.478,6786388.758,0.0), Point(332179.733,6786403.312,0.0)),FrozenLinkInterface,190813844,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(454176, 1.0,"89de2788-af30-44de-8e08-2902719e5925:1",0.0,111.872,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(332215.925,6786453.566,0.0), Point(332296.309,6786528.258,0.0)),FrozenLinkInterface,190813849,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(480585, 9.0,"186c8e6d-99f8-4af1-b4c6-ec96fabf213c:1",0.0,38.363,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(331840.189,6785985.401,0.0), Point(331868.066,6786011.747,0.0)),FrozenLinkInterface,306319328,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(480580, 4.0,"ccb45d70-6636-44f3-a402-ce092dd00572:1",0.0,46.247,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(331662.007,6785788.682,0.0), Point(331693.216,6785822.764,0.0)),FrozenLinkInterface,306319328,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(454230, 8.0,"72abd829-599b-4376-a190-7775e768c6d1:1",0.0,21.579,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(332856.806,6787209.447,0.0), Point(332867.767,6787228.03,0.0)),FrozenLinkInterface,190813846,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(454179, 4.0,"7d2d10d5-357f-40a1-bda0-6470d9941784:1",0.0,15.064,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(332456.683,6786612.821,0.0), Point(332469.955,6786619.945,0.0)),FrozenLinkInterface,190813849,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(455431, 5.0,"a42f36fe-6270-4556-b9ae-8562a30704a9:1",0.0,61.981,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(332179.733,6786403.312,0.0), Point(332215.925,6786453.566,0.0)),FrozenLinkInterface,190813844,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(480584, 8.0,"1d808029-7774-4f45-9183-936427ca5cf3:1",0.0,23.049,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(331823.583,6785969.417,0.0), Point(331840.189,6785985.401,0.0)),FrozenLinkInterface,306319328,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(454226, 4.0,"b1a1b9fa-dc20-43b6-b352-f7937d1d5e97:1",0.0,160.73,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(332675.883,6786846.664,0.0), Point(332739.739,6786994.148,0.0)),FrozenLinkInterface,190813846,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(455428, 2.0,"9e70b480-3f7d-4134-9c17-4541f9673877:1",0.0,12.68,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(332108.633,6786308.482,0.0), Point(332116.043,6786318.771,0.0)),FrozenLinkInterface,190813844,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(455707, 3.0,"129efda6-f517-4a7e-b30e-86bd04db7d28:1",0.0,12.687,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(332451.682,6786600.823,0.0), Point(332462.748,6786607.029,0.0)),FrozenLinkInterface,190813848,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(480588, 12.0,"e755a060-7c85-440a-80be-0abe9777679e:1",0.0,19.226,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(331972.963,6786123.58,0.0), Point(331983.601,6786139.592,0.0)),FrozenLinkInterface,306319328,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(455429, 3.0,"4fdd164e-b713-4d86-9421-2af034c9a1ef:1",0.0,88.113,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(332116.043,6786318.771,0.0), Point(332169.478,6786388.758,0.0)),FrozenLinkInterface,190813844,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(454178, 3.0,"fbbaa48b-59b7-4280-afae-18fa62932ee1:1",0.0,10.329,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(332447.582,6786607.936,0.0), Point(332456.683,6786612.821,0.0)),FrozenLinkInterface,190813849,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(454229, 7.0,"c159ea49-e20c-4ca5-bbda-eda8059ebc15:1",0.0,107.007,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(332804.651,6787116.031,0.0), Point(332856.806,6787209.447,0.0)),FrozenLinkInterface,190813846,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(454224, 2.0,"bf35b72a-ff5e-4f8b-a0d3-56efecc27209:2",0.0,22.696,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(332664.933,6786821.914,0.0), Point(332657.739,6786840.993,0.0)),FrozenLinkInterface,190813846,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(480581, 5.0,"c1c57962-d098-467c-ab9a-24ea6ab8f14e:1",0.0,47.948,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(331693.216,6785822.764,0.0), Point(331722.473,6785860.737,0.0)),FrozenLinkInterface,306319328,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(454225, 3.0,"bc53ac33-3288-4a69-b06d-186e10ebdd0f:2",0.0,20.538,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(332657.739,6786840.993,0.0), Point(332675.883,6786846.664,0.0)),FrozenLinkInterface,190813846,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(480577, 1.0,"71d2a89f-8679-47f8-a755-d676593d3e03:1",0.0,13.471,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(331583.58,6785711.058,0.0), Point(331592.462,6785721.179,0.0)),FrozenLinkInterface,306319328,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(454223, 1.0,"42eb25a0-f537-4ba4-ab11-b2d6db2bc924:1",0.0,181.936,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(332565.361,6786670.989,0.0), Point(332664.933,6786821.914,0.0)),FrozenLinkInterface,190813846,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(480587, 11.0,"9fba3921-7b2e-439b-8c94-2ca68ceee63d:1",0.0,61.322,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(331935.978,6786074.69,0.0), Point(331972.963,6786123.58,0.0)),FrozenLinkInterface,306319328,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(455708, 4.0,"dc96e2f3-bf7a-4e7d-90ac-dc3cd93bc9ff:1",0.0,12.758,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(332462.748,6786607.029,0.0), Point(332473.875,6786613.27,0.0)),FrozenLinkInterface,190813848,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(455705, 1.0,"43b56ba1-168a-4de9-996c-4c2f405ab7a2:1",0.0,108.671,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(332215.925,6786453.566,0.0), Point(332300.741,6786520.485,0.0)),FrozenLinkInterface,190813848,Some(DateTime.now().minusDays(2)),None)
      )

      val projectLinks = Seq(
        ProjectLink(Sequences.nextProjectLinkId, roundAboutRoadPart,Track.Combined,Discontinuity.Continuous,AddrMRange(0,0),AddrMRange(0,0),None,None,Some("test"),"337abaed-099e-400c-9184-3470649fb553:1", 0.0,22.431,SideCode.Unknown,(NoCP, NoCP), (NoCP, NoCP),List(Point(332681.9020003851,6786827.140993087,85.986), Point(332683.203000385,6786832.539993089,85.527), Point(332682.0680003852,6786840.065993087,86.545), Point(332679.9220003854,6786843.827993088,87.482), Point(332675.8830003853,6786846.663993087,87.314)),projectId,  RoadAddressChangeType.New,AdministrativeClass.Municipality, FrozenLinkInterface,22.431,0,0,4,reversed = false,None,1652179948783L,0,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roundAboutRoadPart,Track.Combined,Discontinuity.Continuous,AddrMRange(0,0),AddrMRange(0,0),None,None,Some("test"),"6e4d23ca-6fdc-484b-9049-1250b956b13b:1", 0.0,18.803,SideCode.Unknown,(NoCP, NoCP), (NoCP, NoCP),List(Point(332664.9330003857,6786821.913993082,87.331), Point(332670.8320003854,6786821.198993084,87.094), Point(332676.8100003853,6786822.848993087,86.028), Point(332681.9020003851,6786827.140993087,85.986)),projectId,  RoadAddressChangeType.New,AdministrativeClass.Municipality, FrozenLinkInterface,18.803,0,0,4,reversed = false,None,1652179948783L,0,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roundAboutRoadPart,Track.Combined,Discontinuity.Continuous,AddrMRange(1549,1571),AddrMRange(1549,1571),None,None,Some("test"),"bf35b72a-ff5e-4f8b-a0d3-56efecc27209:2", 0.0,22.696,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(332664.933,6786821.914,87.331), Point(332660.4330003857,6786824.8939930815,87.387), Point(332657.635000386,6786828.930993081,87.319), Point(332656.3930003857,6786833.290993078,87.228), Point(332656.8100003859,6786837.797993081,87.174), Point(332657.739,6786840.993,87.20099903327835)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.Municipality, FrozenLinkInterface,22.696,79679,454224,4,reversed = false,None,1652179948783L,190813846,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roundAboutRoadPart,Track.Combined,Discontinuity.Continuous,AddrMRange(1571,1591),AddrMRange(1571,1591),None,None,Some("test"),"bc53ac33-3288-4a69-b06d-186e10ebdd0f:2", 0.0,20.538,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(332657.739,6786840.993,87.201), Point(332662.0680003856,6786845.425993082,87.23), Point(332666.0890003856,6786846.972993083,87.31), Point(332670.8320003855,6786847.900993085,87.524), Point(332675.8830003853,6786846.663993087,87.314)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.Municipality, FrozenLinkInterface,20.538,79679,454225,4,reversed = false,None,1652179948783L,190813846,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.Combined,Discontinuity.Continuous,AddrMRange(0,14),AddrMRange(0,14),None,None,Some("test"),"71d2a89f-8679-47f8-a755-d676593d3e03:1", 0.0,13.471,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(331583.58,6785711.058,89.237), Point(331587.5050004136,6785715.808992727,89.297), Point(331592.4620004134,6785721.178992727,89.446)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,13.471,88915,480577,4,reversed = false,None,1652179948783L,306319328,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.Combined,Discontinuity.Continuous,AddrMRange(14,48),AddrMRange(14,48),None,None,Some("test"),"752f5bd5-069d-49ed-a22f-00c1133b95e4:1", 0.0,34.087,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(331592.462,6785721.179,89.446), Point(331601.3450004132,6785731.50799273,89.83), Point(331616.5080004129,6785745.249992735,90.312)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,34.087,88915,480578,4,reversed = false,None,1652179948783L,306319328,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.Combined,Discontinuity.Continuous,AddrMRange(48,112),AddrMRange(48,112),None,None,Some("test"),"c411d69e-eed6-439d-b290-1a1cb1082e46:1", 0.0,62.925,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(331616.508,6785745.25,90.312), Point(331631.8390004123,6785760.278992739,90.77), Point(331647.8130004121,6785774.4119927455,91.124), Point(331662.0070004117,6785788.681992749,91.364)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,62.925,88915,480579,4,reversed = false,None,1652179948783L,306319328,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.Combined,Discontinuity.Continuous,AddrMRange(112,158),AddrMRange(112,158),None,None,Some("test"),"ccb45d70-6636-44f3-a402-ce092dd00572:1", 0.0,46.247,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(331662.007,6785788.682,91.364), Point(331672.8050004113,6785799.464992754,91.29), Point(331685.418000411,6785813.213992757,90.888), Point(331693.216,6785822.764,90.41501572825636)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,46.247,88915,480580,4,reversed = false,None,1652179948783L,306319328,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.Combined,Discontinuity.Continuous,AddrMRange(158,207),AddrMRange(158,207),None,None,Some("test"),"c1c57962-d098-467c-ab9a-24ea6ab8f14e:1", 0.0,47.948,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(331693.216,6785822.764,90.415), Point(331702.4180004106,6785834.029992765,89.716), Point(331711.0480004106,6785845.872992768,88.979), Point(331722.4730004099,6785860.73699277,88.006)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,47.948,88915,480581,4,reversed = false,None,1652179948783L,306319328,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.Combined,Discontinuity.Continuous,AddrMRange(207,329),AddrMRange(207,329),None,None,Some("test"),"74403d0e-d203-4235-ab86-2e7edc8d2119:1", 0.0,120.719,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(331722.473,6785860.737,88.006), Point(331733.3980004096,6785876.244992774,87.039), Point(331748.2440004095,6785893.088992779,85.867), Point(331760.0420004091,6785905.907992783,85.034), Point(331779.3190004085,6785925.7609927915,83.89), Point(331793.9880004081,6785940.5299927965,83.414), Point(331803.3450004078,6785950.102992798,83.179)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,120.719,88915,480582,4,reversed = false,None,1652179948783L,306319328,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.Combined,Discontinuity.Continuous,AddrMRange(329,357),AddrMRange(329,357),None,None,Some("test"),"eccbc261-54d2-412f-b8e4-20cbe63acedd:1", 0.0,27.975,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(331803.345,6785950.103,83.179), Point(331823.583,6785969.417,83.20999986898163)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,27.975,88915,480583,4,reversed = false,None,1652179948783L,306319328,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.Combined,Discontinuity.Continuous,AddrMRange(357,380),AddrMRange(357,380),None,None,Some("test"),"1d808029-7774-4f45-9183-936427ca5cf3:1", 0.0,23.049,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(331823.583,6785969.417,83.21), Point(331829.5880004072,6785975.116992807,83.223), Point(331834.6090004071,6785979.965992808,83.281), Point(331840.189,6785985.401,83.38399796571257)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,23.049,88915,480584,4,reversed = false,None,1652179948783L,306319328,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.Combined,Discontinuity.Continuous,AddrMRange(380,419),AddrMRange(380,419),None,None,Some("test"),"186c8e6d-99f8-4af1-b4c6-ec96fabf213c:1", 0.0,38.363,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(331840.189,6785985.401,83.384), Point(331849.065000407,6785994.004992814,83.631), Point(331857.6500004063,6786001.693992817,83.924), Point(331868.0660004062,6786011.746992818,84.527)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,38.363,88915,480585,4,reversed = false,None,1652179948783L,306319328,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.Combined,Discontinuity.Continuous,AddrMRange(419,513),AddrMRange(419,513),None,None,Some("test"),"4e6e154d-696e-4bbd-b73a-b9848c270fcc:1", 0.0,92.598,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(331868.066,6786011.747,84.527), Point(331884.0030004058,6786026.765992825,85.738), Point(331904.1350004053,6786045.02899283,87.369), Point(331935.9780004045,6786074.689992844,89.621)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,92.598,88915,480586,4,reversed = false,None,1652179948783L,306319328,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.Combined,Discontinuity.Continuous,AddrMRange(513,575),AddrMRange(513,575),None,None,Some("test"),"9fba3921-7b2e-439b-8c94-2ca68ceee63d:1", 0.0,61.322,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(331935.978,6786074.69,89.621), Point(331946.5170004041,6786087.502992844,90.167), Point(331972.963,6786123.58,90.53799717084263)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,61.322,88915,480587,4,reversed = false,None,1652179948783L,306319328,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.Combined,Discontinuity.Continuous,AddrMRange(575,594),AddrMRange(575,594),None,None,Some("test"),"e755a060-7c85-440a-80be-0abe9777679e:1", 0.0,19.226,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(331972.963,6786123.58,90.538), Point(331978.0980004032,6786131.574992857,90.537), Point(331983.6010004031,6786139.591992856,90.473)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,19.226,88915,480588,4,reversed = false,None,1652179948783L,306319328,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.Combined,Discontinuity.MinorDiscontinuity,AddrMRange(594,739),AddrMRange(594,739),None,None,Some("test"),"8eb94a29-ff14-4f73-b1fd-cc4923236c21:1", 0.0,143.291,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(331983.601,6786139.592,90.473), Point(331992.290000403,6786152.842992862,90.299), Point(332004.2200004027,6786170.869992864,90.048), Point(332016.5820004024,6786187.421992869,89.658), Point(332026.8120004022,6786200.076992872,89.283), Point(332037.4450004018,6786214.475992875,88.827), Point(332048.9420004015,6786233.483992879,88.417), Point(332057.1460004013,6786247.358992882,88.19), Point(332064.3800004012,6786257.752992884,87.598)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,143.291,88915,480589,4,reversed = false,None,1652179948783L,306319328,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.Combined,Discontinuity.Continuous,AddrMRange(739,776),AddrMRange(739,776),None,None,Some("test"),"03b2f7b8-c10a-42b2-b176-a7b65ada599a:1", 0.0,37.151,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(332086.496,6786278.692,87.341), Point(332094.6590004002,6786288.399992895,87.602), Point(332103.1140004001,6786300.819992897,87.661), Point(332108.633,6786308.482,87.82399385620998)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,37.151,79881,455427,4,reversed = false,None,1652179948783L,190813844,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.Combined,Discontinuity.Continuous,AddrMRange(776,789),AddrMRange(776,789),None,None,Some("test"),"9e70b480-3f7d-4134-9c17-4541f9673877:1", 0.0,12.68,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(332108.633,6786308.482,87.824), Point(332116.0430003998,6786318.7709929,88.032)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,12.68,79881,455428,4,reversed = false,None,1652179948783L,190813844,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.Combined,Discontinuity.Continuous,AddrMRange(789,877),AddrMRange(789,877),None,None,Some("test"),"4fdd164e-b713-4d86-9421-2af034c9a1ef:1", 0.0,88.113,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(332116.043,6786318.771,88.032), Point(332118.4050003998,6786322.048992903,87.934), Point(332136.0370003993,6786345.250992908,88.398), Point(332153.7210003988,6786366.205992915,88.948), Point(332169.4780003983,6786388.757992918,89.463)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,88.113,79881,455429,4,reversed = false,None,1652179948783L,190813844,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.Combined,Discontinuity.Continuous,AddrMRange(877,895),AddrMRange(877,895),None,None,Some("test"),"c5bee88c-6e97-40f5-9bac-e1f2a31d4332:1", 0.0,17.808,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(332169.478,6786388.758,89.463), Point(332175.3810003983,6786397.466992921,89.637), Point(332179.733,6786403.312,89.75299562069114)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,17.808,79881,455430,4,reversed = false,None,1652179948783L,190813844,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.Combined,Discontinuity.Continuous,AddrMRange(895,957),AddrMRange(895,957),None,None,Some("test"),"a42f36fe-6270-4556-b9ae-8562a30704a9:1", 0.0,61.981,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(332179.733,6786403.312,89.753), Point(332191.6200003979,6786421.341992927,90.153), Point(332206.5770003977,6786442.266992932,90.56), Point(332215.925,6786453.566,90.80199184517996)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,61.981,79881,455431,4,reversed = false,None,1652179948783L,190813844,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.RightSide,Discontinuity.Continuous,AddrMRange(957,1066),AddrMRange(957,1066),None,None,Some("test"),"43b56ba1-168a-4de9-996c-4c2f405ab7a2:1", 0.0,108.671,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(332215.925,6786453.566,90.802), Point(332224.5880003971,6786462.648992937,90.974), Point(332234.759000397,6786473.120992939,91.269), Point(332248.1970003965,6786485.864992945,91.561), Point(332264.4040003961,6786498.112992949,91.67), Point(332280.9930003955,6786508.976992955,91.89), Point(332300.741000395,6786520.484992963,91.988)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,108.671,79381,455705,4,reversed = false,None,1652179948783L,190813848,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.LeftSide,Discontinuity.Continuous,AddrMRange(957,1068),AddrMRange(957,1068),None,None,Some("test"),"89de2788-af30-44de-8e08-2902719e5925:1", 0.0,111.872,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(332215.925,6786453.566,90.802), Point(332221.8730003974,6786465.554992937,90.981), Point(332233.2910003969,6786481.189992939,91.294), Point(332242.2040003967,6786491.154992942,91.442), Point(332255.5180003963,6786502.639992948,91.675), Point(332271.2270003959,6786512.998992953,91.829), Point(332296.309,6786528.258,91.91699902583281)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,111.872,78993,454176,4,reversed = false,None,1652179948783L,190813849,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.RightSide,Discontinuity.Continuous,AddrMRange(1066,1237),AddrMRange(1066,1237),None,None,Some("test"),"25ceced7-cb02-49b2-88dd-357a5456ff41:1", 0.0,171.013,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(332300.741,6786520.485,91.988), Point(332312.7210003948,6786526.670992968,91.98), Point(332326.0460003944,6786533.498992971,92.108), Point(332340.502000394,6786541.336992978,92.198), Point(332352.5700003936,6786547.784992983,92.27), Point(332367.1520003932,6786555.370992985,92.357), Point(332380.9810003929,6786562.199992988,92.441), Point(332398.4550003925,6786571.428992996,92.567), Point(332414.0420003921,6786579.898993,92.735), Point(332431.5140003915,6786589.883993006,92.851), Point(332442.3240003913,6786595.57399301,92.894), Point(332451.682,6786600.823,92.93299906109706)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,171.013,79381,455706,4,reversed = false,None,1652179948783L,190813848,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.LeftSide,Discontinuity.Continuous,AddrMRange(1068,1238),AddrMRange(1068,1238),None,None,Some("test"),"d9ffb189-9a2d-4bd0-902a-65ba0d06fcb1:1", 0.0,170.993,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(332296.309,6786528.258,91.917), Point(332311.8950003947,6786537.105992965,91.915), Point(332333.8930003941,6786548.862992973,92.016), Point(332360.2950003936,6786562.014992982,92.208), Point(332389.3340003927,6786576.935992992,92.417), Point(332423.275000392,6786594.888993005,92.816), Point(332447.582,6786607.936,92.59300393314365)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,170.993,78993,454177,4,reversed = false,None,1652179948783L,190813849,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.LeftSide,Discontinuity.Continuous,AddrMRange(1238,1248),AddrMRange(1238,1248),None,None,Some("test"),"fbbaa48b-59b7-4280-afae-18fa62932ee1:1", 0.0,10.329,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(332447.582,6786607.936,92.593), Point(332456.683,6786612.821,91.97300925443588)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,10.329,78993,454178,4,reversed = false,None,1652179948783L,190813849,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.RightSide,Discontinuity.Continuous,AddrMRange(1237,1249),AddrMRange(1237,1249),None,None,Some("test"),"129efda6-f517-4a7e-b30e-86bd04db7d28:1", 0.0,12.687,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(332451.682,6786600.823,92.933), Point(332462.748,6786607.029,92.56501237167762)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,12.687,79381,455707,4,reversed = false,None,1652179948783L,190813848,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.RightSide,Discontinuity.Continuous,AddrMRange(1249,1262),AddrMRange(1249,1262),None,None,Some("test"),"dc96e2f3-bf7a-4e7d-90ac-dc3cd93bc9ff:1", 0.0,12.758,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(332462.748,6786607.029,92.565), Point(332468.7830003906,6786610.46999302,93.072), Point(332473.875,6786613.27,93.06700011055781)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,12.758,79381,455708,4,reversed = false,None,1652179948783L,190813848,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.LeftSide,Discontinuity.Continuous,AddrMRange(1248,1263),AddrMRange(1248,1263),None,None,Some("test"),"7d2d10d5-357f-40a1-bda0-6470d9941784:1", 0.0,15.064,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(332456.683,6786612.821,91.973), Point(332463.0700003908,6786616.329993016,93.072), Point(332469.9550003906,6786619.94499302,93.028)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,15.064,78993,454179,4,reversed = false,None,1652179948783L,190813849,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.RightSide,Discontinuity.Continuous,AddrMRange(1262,1371),AddrMRange(1262,1371),None,None,Some("test"),"66f51329-a242-4ecd-b31c-3073ee7702db:1", 0.0,108.973,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(332473.875,6786613.27,93.067), Point(332486.80800039,6786619.192993025,93.0), Point(332503.0260003896,6786626.782993031,92.737), Point(332515.4710003895,6786633.2319930345,92.585), Point(332528.5430003889,6786640.939993041,92.394), Point(332544.1260003885,6786651.675993045,92.107), Point(332553.2960003884,6786659.249993049,91.857), Point(332565.361,6786670.989,91.34700913771881)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,108.973,79381,455709,4,reversed = false,None,1652179948783L,190813848,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.LeftSide,Discontinuity.Continuous,AddrMRange(1263,1371),AddrMRange(1263,1371),None,None,Some("test"),"298fd801-6580-4563-8832-835737670917:1", 0.0,108.263,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(332469.955,6786619.945,93.028), Point(332484.3000003901,6786626.827993024,92.956), Point(332502.5300003897,6786635.680993033,92.549), Point(332523.5230003891,6786646.806993037,92.199), Point(332539.6120003889,6786655.7809930425,91.985), Point(332565.361,6786670.989,91.34700250725209)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,108.263,78993,454180,4,reversed = false,None,1652179948783L,190813849,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.Combined,Discontinuity.MinorDiscontinuity,AddrMRange(1371,1549),AddrMRange(1371,1549),None,None,Some("test"),"42eb25a0-f537-4ba4-ab11-b2d6db2bc924:1", 0.0,181.936,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(332565.361,6786670.989,91.347), Point(332580.0660003877,6786684.685993057,90.895), Point(332597.4310003871,6786705.656993062,90.244), Point(332612.6310003869,6786728.333993067,89.617), Point(332628.3200003865,6786754.880993071,88.858), Point(332630.8650003865,6786759.3619930735,88.769), Point(332640.2800003863,6786775.939993077,88.141), Point(332657.4390003859,6786804.800993081,87.593), Point(332664.933,6786821.914,87.33100536737595)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,181.936,79679,454223,4,reversed = false,None,1652179948783L,190813846,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.Combined,Discontinuity.Continuous,AddrMRange(1591,1749),AddrMRange(1591,1749),None,None,Some("test"),"b1a1b9fa-dc20-43b6-b352-f7937d1d5e97:1", 0.0,160.73,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(332675.883,6786846.664,87.314), Point(332684.3490003852,6786864.860993089,87.465), Point(332694.4890003848,6786889.300993093,87.886), Point(332701.4150003848,6786906.243993096,88.305), Point(332703.3280003846,6786910.879993095,88.475), Point(332711.1290003846,6786928.8209930975,89.075), Point(332739.739,6786994.148,91.07099992576445)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.Municipality, FrozenLinkInterface,160.73,79679,454226,4,reversed = false,None,1652179948783L,190813846,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.Combined,Discontinuity.Continuous,AddrMRange(1749,1796),AddrMRange(1749,1796),None,None,Some("test"),"95c19c8d-d27b-499a-8d44-76bf8198ceb3:1", 0.0,48.417,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(332739.739,6786994.148,91.071), Point(332748.2900003837,6787011.0009931065,91.331), Point(332760.3070003833,6787034.151993112,91.672), Point(332761.9750003832,6787037.153993113,91.704)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.Municipality, FrozenLinkInterface,48.417,79679,454227,4,reversed = false,None,1652179948783L,190813846,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.Combined,Discontinuity.Continuous,AddrMRange(1796,1884),AddrMRange(1796,1884),None,None,Some("test"),"5feef32a-43a5-468e-86e9-32ded2378a62:1", 0.0,89.685,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(332761.975,6787037.154,91.704), Point(332776.1040003831,6787062.570993118,91.883), Point(332789.6760003827,6787088.05199312,91.723), Point(332804.6510003823,6787116.030993125,91.298)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.Municipality, FrozenLinkInterface,89.685,79679,454228,4,reversed = false,None,1652179948783L,190813846,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.Combined,Discontinuity.Continuous,AddrMRange(1884,1988),AddrMRange(1884,1988),None,None,Some("test"),"c159ea49-e20c-4ca5-bbda-eda8059ebc15:1", 0.0,107.007,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(332804.651,6787116.031,91.298), Point(332815.850000382,6787137.67199313,90.785), Point(332839.1270003814,6787178.238993136,89.648), Point(332851.7600003813,6787200.8789931405,88.957), Point(332856.806,6787209.447,88.72400669025905)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.Municipality, FrozenLinkInterface,107.007,79679,454229,4,reversed = false,None,1652179948783L,190813846,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.Combined,Discontinuity.Continuous,AddrMRange(1988,2009),AddrMRange(1988,2009),None,None,Some("test"),"72abd829-599b-4376-a190-7775e768c6d1:1", 0.0,21.579,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(332856.806,6787209.447,88.724), Point(332860.018000381,6787215.287993144,88.55), Point(332864.0330003812,6787221.795993146,88.341), Point(332867.767,6787228.03,88.14001351464111)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.Municipality, FrozenLinkInterface,21.579,79679,454230,4,reversed = false,None,1652179948783L,190813846,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart,Track.Combined,Discontinuity.EndOfRoad,AddrMRange(2009,2025),AddrMRange(2009,2025),None,None,Some("test"),"71292433-7692-46f0-9bb9-5e81d793ddc5:1", 0.0,16.134,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(332867.767,6787228.03,88.14), Point(332870.5400003809,6787232.871993146,87.987), Point(332873.3090003807,6787237.439993148,87.82), Point(332875.94,6787241.94,87.72000465375005)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.Municipality, FrozenLinkInterface,16.134,79679,454231,4,reversed = false,None,1652179948783L,190813846,roadName,None,None,None,None)
      )

      linearLocationDAO.create(linearLocations)
      roadwayDAO.create(roadways)
      projectDAO.create(Project(projectId, ProjectState.Incomplete, "test", "test", DateTime.now(),"test", DateTime.now(), DateTime.now(),"",Seq(ProjectReservedPart(Sequences.nextViitePrimaryKeySeqValue,roadPart)),Seq(ProjectReservedPart(Sequences.nextViitePrimaryKeySeqValue,roadPart)),None,None,Set(14)))
      projectReservedPartDAO.reserveRoadPart(projectId, roadPart, "test")
      projectReservedPartDAO.reserveRoadPart(projectId, roundAboutRoadPart, "test")
      projectLinkDAO.create(projectLinks)

      projectService.recalculateProjectLinks(projectId, "test", Set(roadPart, roundAboutRoadPart))
      val recalculatedRoundabout = projectLinkDAO.fetchProjectLinks(projectId).filter(_.roadPart == roundAboutRoadPart)

      recalculatedRoundabout.map(_.roadwayNumber).distinct.size should be (3)
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

        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.RightSide,Discontinuity.Continuous,AddrMRange(0,0),AddrMRange(0,0),None,None,Some("test"),"f23535b9-a3cd-4f91-80a5-3bdb2593759c:1", 0.0,38.361,SideCode.Unknown,(NoCP, NoCP), (NoCP, NoCP),List(Point(520760.2580000002,7024391.0910002785,105.044), Point(520756.921,7024409.351000278,105.11), Point(520753.868,7024428.913000276,104.985)),projectId,  RoadAddressChangeType.New,AdministrativeClass.State, FrozenLinkInterface,38.361,0,0,8,reversed = false,None,1652179948783L,0,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.RightSide,Discontinuity.Continuous,AddrMRange(0,0),AddrMRange(0,0),None,None,Some("test"),"225cc60f-e8d6-4ba9-af4f-3f7ef2f8b608:1", 0.0,233.89,SideCode.Unknown,(NoCP, NoCP), (NoCP, NoCP),List(Point(520835.836,7024170.629000277,105.002), Point(520836.372,7024175.682000278,104.943), Point(520834.8,7024181.706000278,104.873), Point(520831.657,7024190.872000278,104.84), Point(520821.4420000002,7024214.967000278,104.828), Point(520809.9190000002,7024241.942000277,104.867), Point(520793.157,7024282.27400028,104.902), Point(520786.086,7024304.536000279,104.906), Point(520777.1030000002,7024331.154000279,104.973), Point(520771.701,7024350.060000278,104.957), Point(520766.749,7024366.941000276,104.972), Point(520760.2580000002,7024391.0910002785,105.044)),projectId,  RoadAddressChangeType.New,AdministrativeClass.State, FrozenLinkInterface,233.89,0,0,8,reversed = false,None,1652179948783L,0,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.RightSide,Discontinuity.Continuous,AddrMRange(0,0),AddrMRange(0,0),None,None,Some("test"),"e60ed058-be33-4da9-b67c-085194e5ec6a:1", 0.0,81.469,SideCode.Unknown,(NoCP, NoCP), (NoCP, NoCP),List(Point(520744.822,7024483.246000279,105.026), Point(520742.062,7024500.066000278,105.061), Point(520739.129,7024522.939000281,105.12), Point(520735.806,7024546.596000279,105.214), Point(520734.476,7024553.399000279,105.235), Point(520730.0100000002,7024562.949000278,105.399)),projectId,  RoadAddressChangeType.New,AdministrativeClass.State, FrozenLinkInterface,81.469,0,0,8,reversed = false,None,1652179948783L,0,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.RightSide,Discontinuity.Continuous,AddrMRange(0,0),AddrMRange(0,0),None,None,Some("test"),"68479bca-99f9-4465-87c3-2a453ce895a0:1", 0.0,8.101,SideCode.Unknown,(NoCP, NoCP), (NoCP, NoCP),List(Point(520753.868,7024428.913000276,104.985), Point(520752.6200000002,7024436.917000279,105.005)),projectId,  RoadAddressChangeType.New,AdministrativeClass.State, FrozenLinkInterface,8.101,0,0,8,reversed = false,None,1652179948783L,0,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.RightSide,Discontinuity.Continuous,AddrMRange(0,0),AddrMRange(0,0),None,None,Some("test"),"a587beed-2cb3-4c61-bafa-264e836c1914:1", 0.0,46.982,SideCode.Unknown,(NoCP, NoCP), (NoCP, NoCP),List(Point(520752.6200000002,7024436.917000279,105.005), Point(520748.9050000002,7024458.032000278,105.035), Point(520744.822,7024483.246000279,105.026)),projectId,  RoadAddressChangeType.New,AdministrativeClass.State, FrozenLinkInterface,46.982,0,0,8,reversed = false,None,1652179948783L,0,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.LeftSide,Discontinuity.Continuous,AddrMRange(5112,5351),AddrMRange(5112,5351),None,None,Some("test"),"88d0cd7e-4854-470f-9396-267cf3a17efa:1", 0.0,238.658,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(520835.836,7024170.629,105.002), Point(520833.3570000002,7024172.317000278,104.996), Point(520830.168,7024175.880000278,105.041), Point(520826.605,7024181.883000278,105.08), Point(520823.041,7024188.447000277,105.085), Point(520816.1010000002,7024204.390000277,105.09), Point(520801.096,7024238.902000277,105.122), Point(520787.778,7024273.040000278,105.153), Point(520778.9620000002,7024296.298000278,105.163), Point(520770.145,7024321.4320002785,105.136), Point(520761.143,7024350.5050002765,105.162), Point(520754.9520000002,7024372.075000278,105.219), Point(520749.693,7024391.76300028,105.239)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.State, FrozenLinkInterface,238.658,19743,166993,8,reversed = false,None,1652179948783L,54807,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.LeftSide,Discontinuity.Continuous,AddrMRange(5351,5387),AddrMRange(5351,5387),None,None,Some("test"),"47f9e320-b838-4f20-a315-5d1b767110c8:1", 0.0,35.448,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(520749.693,7024391.763,105.239), Point(520748.2390000002,7024401.765000276,105.286), Point(520744.736,7024421.614000277,105.289), Point(520743.967,7024426.742,105.28999995941493)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.State, FrozenLinkInterface,35.448,19743,166994,8,reversed = false,None,1652179948783L,54807,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.LeftSide,Discontinuity.Continuous,AddrMRange(5387,5394),AddrMRange(5387,5394),None,None,Some("test"),"9e0c17f9-68bd-4880-9e03-4b13f1992ec1:1", 0.0,7.94,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(520743.967,7024426.742,105.29), Point(520742.599,7024434.563000278,105.295)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.State, FrozenLinkInterface,7.94,19743,166995,8,reversed = false,None,1652179948783L,54807,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.LeftSide,Discontinuity.Continuous,AddrMRange(5394,5525),AddrMRange(5394,5525),None,None,Some("test"),"9f347f05-a02b-49d8-9aef-34a8476ed680:1", 0.0,129.939,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(520742.599,7024434.563,105.295), Point(520740.5470000002,7024447.0750002805,105.327), Point(520737.7310000002,7024461.310000277,105.337), Point(520733.3520000002,7024487.872000279,105.36), Point(520730.4330000002,7024509.472000277,105.405), Point(520728.682,7024526.694000279,105.406), Point(520727.806,7024541.872000279,105.438), Point(520727.2220000002,7024549.461000277,105.433), Point(520727.514,7024555.590000278,105.415), Point(520730.01,7024562.949,105.39900020271045)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.State, FrozenLinkInterface,129.939,19743,166996,8,reversed = false,None,1652179948783L,54807,roadName,None,None,None,None),

        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.Combined,Discontinuity.Continuous,AddrMRange(5525,5833),AddrMRange(5525,5833),None,None,Some("test"),"87ddfa9a-b7c5-4661-b38e-afdd34fbbc44:1", 0.0,307.573,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(520730.01,7024562.949,105.399), Point(520729.3740000002,7024577.088000281,105.4), Point(520728.263,7024596.470000276,105.421), Point(520727.7860000004,7024627.448000276,105.315), Point(520728.899,7024656.995000277,105.125), Point(520729.581,7024672.66300028,104.908), Point(520731.131,7024708.256000278,104.413), Point(520733.65,7024772.885000278,103.094), Point(520735.697,7024833.185000277,101.972), Point(520736.527,7024853.374000278,101.7), Point(520737.4560000002,7024870.276000278,101.546)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.State, FrozenLinkInterface,307.573,19743,166997,8,reversed = false,None,1652179948783L,54807,roadName,None,None,None,None),

        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.LeftSide,Discontinuity.Continuous,AddrMRange(0,0),AddrMRange(0,0),None,None,Some("test"),"09656524-27a6-46c3-b3d7-4200d68687c9:1", 0.0,175.402,SideCode.Unknown,(NoCP, NoCP), (NoCP, NoCP),List(Point(520737.4560000002,7024870.276000278,101.546), Point(520734.6800000002,7024884.377000278,101.414), Point(520734.68,7024887.306000277,101.377), Point(520734.8560000002,7024903.7440002775,101.246), Point(520735.459,7024939.899000278,101.186), Point(520736.3620000002,7024973.945000279,101.288), Point(520737.2670000002,7025002.5680002775,101.492), Point(520736.965,7025025.4670002805,101.733), Point(520737.0410000002,7025045.373000276,102.031)),projectId,  RoadAddressChangeType.New,AdministrativeClass.State, FrozenLinkInterface,175.402,0,0,8,reversed = false,None,1652179948783L,0,roadName,None,None,None,None),
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
        val roadway = roadways.find(r => r.roadPart == nl.roadPart && r.addrMRange.isSameAs(nl.addrMRange))
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

   test("Test recalculateProjectLinks() When terminating last project links from two track section (that are of different lengths), creating a minor discontinuity on both tracks, then the tracks should be adjusted to match at the discontinuous part.") {
     /**
      * Based on real life project on road part 49529/1
      *        Before        After
      *        |   |         |   |
      *        |   |         |   |
      *        |   |         |   |
      *         \ /          v   v
      *          v
      *          |             |
      *          |             |
      *          |             |
      *          |             |
      *          v             v
      * */
    runWithRollback {
      val roadPart = RoadPart(49529,1)
      val projectId = Sequences.nextViiteProjectId
      val roadName = Some("testRoad")

      val roadways = Seq(
        Roadway(82725,225079136,RoadPart(49529,1),AdministrativeClass.Municipality,Track.RightSide,Discontinuity.Continuous,AddrMRange(0, 696),false,DateTime.now().minusDays(2),None,"test",None,14,TerminationCode.NoTermination,DateTime.now().minusDays(2),None),
        Roadway(82872,225079139,RoadPart(49529,1),AdministrativeClass.Municipality,Track.LeftSide,Discontinuity.Continuous,AddrMRange(0, 696),false,DateTime.now().minusDays(2),None,"test",None,14,TerminationCode.NoTermination,DateTime.now().minusDays(2),None),
        Roadway(82220,225079142,RoadPart(49529,1),AdministrativeClass.Municipality,Track.Combined,Discontinuity.EndOfRoad,AddrMRange(696, 1042),false,DateTime.now().minusDays(2),None,"test",None,14,TerminationCode.NoTermination,DateTime.now().minusDays(2),None)
      )

      val linearLocations = Seq(
        LinearLocation(465424, 13.0,"dfab6465-6395-4ac8-b72b-85ef40408443:2",0.0,12.898,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(443615.737,7376162.543,0.0), Point(443620.46,7376174.545,0.0)),FrozenLinkInterface,225079139,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(465374, 5.0,"ebf9e382-f212-49bb-bbe8-758e42ee69ae:2",0.0,17.426,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(443502.579,7375741.41,0.0), Point(443502.217,7375758.832,0.0)),FrozenLinkInterface,225079136,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(465418, 7.0,"49ecdce7-4115-4d3d-b63d-2115d7dba40b:2",0.0,18.22,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(443492.58,7375867.982,0.0), Point(443494.906,7375886.053,0.0)),FrozenLinkInterface,225079139,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(465378, 9.0,"2a2c5c75-dc63-4d2d-9379-9c5ddaf64546:2",0.0,13.868,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(443516.236,7375900.382,0.0), Point(443522.698,7375912.652,0.0)),FrozenLinkInterface,225079136,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(465387, 18.0,"d061fce2-c526-4967-bf33-62b8005c9c97:1",0.0,22.987,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(443683.593,7376311.326,0.0), Point(443695.047,7376331.256,0.0)),FrozenLinkInterface,225079136,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(466778, 1.0,"4c184326-404e-4e3b-b13c-e256a3312aa1:2",0.0,120.399,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(443695.047,7376331.256,0.0), Point(443780.561,7376415.993,0.0)),FrozenLinkInterface,225079142,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(465382, 13.0,"a70a4c2f-c104-44fe-a4d7-3f3718b162aa:2",0.0,13.427,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(443591.418,7376059.053,0.0), Point(443594.901,7376072.02,0.0)),FrozenLinkInterface,225079136,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(465425, 14.0,"ce492f47-e821-423d-ba5a-fd71fdeb71d5:2",0.0,150.546,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(443620.46,7376174.545,0.0), Point(443676.263,7376314.303,0.0)),FrozenLinkInterface,225079139,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(465370, 1.0,"b4775d3e-9c3f-4c0a-a4cb-31bc586a3a29:2",0.0,29.986,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(443492.259,7375680.103,0.0), Point(443498.725,7375709.381,0.0)),FrozenLinkInterface,225079136,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(466782, 5.0,"3121460f-5d95-43b0-ae1d-88d71ba91ffe:2",0.0,9.109,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(443864.362,7376599.54,0.0), Point(443867.842,7376607.958,0.0)),FrozenLinkInterface,225079142,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(465379, 10.0,"fb18fd8c-64b6-4f89-a039-68de753b0bef:2",0.0,68.332,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(443522.698,7375912.652,0.0), Point(443551.677,7375974.533,0.0)),FrozenLinkInterface,225079136,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(465373, 4.0,"d7eb3af7-eb2b-4372-bfd9-0b717d153347:2",0.0,18.902,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(443500.375,7375722.637,0.0), Point(443502.579,7375741.41,0.0)),FrozenLinkInterface,225079136,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(465426, 15.0,"629b8776-c3aa-47c5-b8ad-52cb1aed8f51:1",0.0,25.303,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(443676.263,7376314.303,0.0), Point(443695.047,7376331.256,0.0)),FrozenLinkInterface,225079139,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(466783, 6.0,"92d3f0ef-db38-476f-b5ff-49b18d74b4aa:2",0.0,12.007,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(443867.842,7376607.958,0.0), Point(443871.967,7376619.225,0.0)),FrozenLinkInterface,225079142,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(465421, 10.0,"3bf22c87-ce97-4bc7-99fb-6bf157756e24:2",0.0,8.664,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(443508.565,7375904.151,0.0), Point(443514.105,7375910.812,0.0)),FrozenLinkInterface,225079139,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(465383, 14.0,"6e98cc1c-7bbe-4b88-ab24-f362665e3311:2",0.0,92.416,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(443594.901,7376072.02,0.0), Point(443626.413,7376158.882,0.0)),FrozenLinkInterface,225079136,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(465414, 3.0,"f99ae6ea-ecea-4f94-9c1e-8df98d679b37:2",0.0,5.523,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(443492.74,7375717.669,0.0), Point(443493.236,7375723.169,0.0)),FrozenLinkInterface,225079139,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(465423, 12.0,"68679010-c1a1-45a3-b752-64d483ec09ea:2",0.0,106.006,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(443583.486,7376061.579,0.0), Point(443615.737,7376162.543,0.0)),FrozenLinkInterface,225079139,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(465380, 11.0,"4401fa25-6e4e-4934-9678-d1e88e5bce2a:2",0.0,70.971,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(443551.677,7375974.533,0.0), Point(443583.051,7376038.167,0.0)),FrozenLinkInterface,225079136,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(466779, 2.0,"549ea1bc-02ef-4e66-9240-adaa77471331:2",0.0,10.049,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(443780.561,7376415.993,0.0), Point(443786.531,7376424.055,0.0)),FrozenLinkInterface,225079142,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(465386, 17.0,"96d2a31a-fafc-4a1e-aeda-ef7e047207b0:2",0.0,19.434,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(443677.179,7376292.999,0.0), Point(443683.593,7376311.326,0.0)),FrozenLinkInterface,225079136,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(466780, 3.0,"b3f70ee2-16d3-40cf-a67f-9c78c5ec879f:2",0.0,78.104,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(443786.531,7376424.055,0.0), Point(443818.744,7376495.203,0.0)),FrozenLinkInterface,225079142,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(465420, 9.0,"7987813c-6870-4883-81fe-d7e1c25fa21f:2",0.0,9.794,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(443501.416,7375897.461,0.0), Point(443508.565,7375904.151,0.0)),FrozenLinkInterface,225079139,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(465376, 7.0,"167a230f-f59a-4d3a-84b1-e131c248cb7a:2",0.0,24.18,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(443500.173,7375866.363,0.0), Point(443508.949,7375888.836,0.0)),FrozenLinkInterface,225079136,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(465416, 5.0,"32601586-1464-4e5b-be20-bf56f055aa10:2",0.0,16.967,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(443494.175,7375741.528,0.0), Point(443494.099,7375758.495,0.0)),FrozenLinkInterface,225079139,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(465417, 6.0,"38c473c7-28d5-433b-9678-c9f08d67c142:2",0.0,110.036,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(443494.099,7375758.495,0.0), Point(443492.58,7375867.982,0.0)),FrozenLinkInterface,225079139,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(465384, 15.0,"d7aa5b8d-4db7-4d3a-b438-ad32ae2de8dc:2",0.0,13.036,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(443626.413,7376158.882,0.0), Point(443630.464,7376171.269,0.0)),FrozenLinkInterface,225079136,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(465422, 11.0,"1a9c7e0a-7c0f-40aa-a8db-b8e8c1bb6dd2:2",0.0,166.014,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(443514.105,7375910.812,0.0), Point(443583.486,7376061.579,0.0)),FrozenLinkInterface,225079139,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(465415, 4.0,"ec3597c7-0c11-4f9b-bb5a-b545f49c8787:2",0.0,18.388,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(443493.236,7375723.169,0.0), Point(443494.175,7375741.528,0.0)),FrozenLinkInterface,225079139,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(465372, 3.0,"cb9d5aff-ec59-4ca0-9dc8-8252ce3ec2b9:2",0.0,4.644,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(443499.845,7375718.023,0.0), Point(443500.375,7375722.637,0.0)),FrozenLinkInterface,225079136,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(465412, 1.0,"82501ee0-0003-4c96-af57-fe9e003caa23:2",0.0,28.82,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(443485.148,7375680.886,0.0), Point(443491.386,7375709.004,0.0)),FrozenLinkInterface,225079139,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(465385, 16.0,"24b62493-3d76-41a5-a676-98589bed32be:2",0.0,130.922,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(443630.464,7376171.269,0.0), Point(443677.179,7376292.999,0.0)),FrozenLinkInterface,225079136,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(465413, 2.0,"3b4060d2-f2e4-484c-a350-36285707b93b:2",0.0,8.775,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(443491.386,7375709.004,0.0), Point(443492.74,7375717.669,0.0)),FrozenLinkInterface,225079139,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(465375, 6.0,"b3f904e5-b7b6-499c-9ff5-bde55ad65d1e:2",0.0,107.869,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(443502.217,7375758.832,0.0), Point(443500.173,7375866.363,0.0)),FrozenLinkInterface,225079136,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(465419, 8.0,"be5499ac-03bf-4fca-abac-bd8af0e7eaf3:2",0.0,13.135,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(443494.906,7375886.053,0.0), Point(443501.416,7375897.461,0.0)),FrozenLinkInterface,225079139,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(465377, 8.0,"9acdcdf3-7a11-49e8-9f5f-f62863290f5a:2",0.0,13.658,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(443508.949,7375888.836,0.0), Point(443516.236,7375900.382,0.0)),FrozenLinkInterface,225079136,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(465371, 2.0,"f2e89f78-beff-48c2-82e2-3f57ce345572:2",0.0,8.715,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(443498.725,7375709.381,0.0), Point(443499.845,7375718.023,0.0)),FrozenLinkInterface,225079136,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(465381, 12.0,"454db012-0d1e-4d2c-b5fb-f124a734ae07:2",0.0,22.514,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(443583.051,7376038.167,0.0), Point(443591.418,7376059.053,0.0)),FrozenLinkInterface,225079136,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(466781, 4.0,"bffb8c7d-aac0-4e7c-a115-101e14952a09:2",0.0,113.886,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(443818.744,7376495.203,0.0), Point(443864.362,7376599.54,0.0)),FrozenLinkInterface,225079142,Some(DateTime.now().minusDays(2)),None)
      )

      val projectLinks = Seq(
        ProjectLink(Sequences.nextProjectLinkId, RoadPart(49529,1),Track.LeftSide,Discontinuity.Continuous,AddrMRange(0,29),AddrMRange(0,29),None,None,Some("test"),"82501ee0-0003-4c96-af57-fe9e003caa23:2", 0.0,28.82,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(443485.148,7375680.886,83.991), Point(443489.3989999995,7375697.720000318,83.623), Point(443491.386,7375709.004,83.5840001928757)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,28.82,82872,465412,14,reversed = false,None,1652179948783L,225079139,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, RoadPart(49529,1),Track.RightSide,Discontinuity.Continuous,AddrMRange(0,30),AddrMRange(0,30),None,None,Some("test"),"b4775d3e-9c3f-4c0a-a4cb-31bc586a3a29:2", 0.0,29.986,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(443492.259,7375680.103,84.052), Point(443494.0409999995,7375687.676000319,83.882), Point(443497.0749999993,7375701.201000322,83.582), Point(443498.7249999994,7375709.3810003195,83.546)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,29.986,82725,465370,14,reversed = false,None,1652179948783L,225079136,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, RoadPart(49529,1),Track.LeftSide,Discontinuity.Continuous,AddrMRange(29,37),AddrMRange(29,37),None,None,Some("test"),"3b4060d2-f2e4-484c-a350-36285707b93b:2", 0.0,8.775,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(443491.386,7375709.004,83.584), Point(443492.3779999994,7375714.420000318,83.603), Point(443492.74,7375717.669,83.6149992546376)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,8.775,82872,465413,14,reversed = false,None,1652179948783L,225079139,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, RoadPart(49529,1),Track.RightSide,Discontinuity.Continuous,AddrMRange(30,39),AddrMRange(30,39),None,None,Some("test"),"f2e89f78-beff-48c2-82e2-3f57ce345572:2", 0.0,8.715,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(443498.725,7375709.381,83.546), Point(443499.3839999994,7375713.933000319,83.54), Point(443499.845,7375718.023,83.53400051520578)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,8.715,82725,465371,14,reversed = false,None,1652179948783L,225079136,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, RoadPart(49529,1),Track.LeftSide,Discontinuity.Continuous,AddrMRange(37,43),AddrMRange(37,43),None,None,Some("test"),"f99ae6ea-ecea-4f94-9c1e-8df98d679b37:2", 0.0,5.523,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(443492.74,7375717.669,83.615), Point(443493.1909999994,7375722.363000319,83.678), Point(443493.2359999992,7375723.16900032,83.68)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,5.523,82872,465414,14,reversed = false,None,1652179948783L,225079139,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, RoadPart(49529,1),Track.RightSide,Discontinuity.Continuous,AddrMRange(39,44),AddrMRange(39,44),None,None,Some("test"),"cb9d5aff-ec59-4ca0-9dc8-8252ce3ec2b9:2", 0.0,4.644,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(443499.845,7375718.023,83.534), Point(443500.1749999993,7375720.992000318,83.565), Point(443500.375,7375722.637,83.5739978456284)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,4.644,82725,465372,14,reversed = false,None,1652179948783L,225079136,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, RoadPart(49529,1),Track.LeftSide,Discontinuity.Continuous,AddrMRange(43,61),AddrMRange(43,61),None,None,Some("test"),"ec3597c7-0c11-4f9b-bb5a-b545f49c8787:2", 0.0,18.388,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(443493.236,7375723.169,83.68), Point(443493.4619999993,7375729.043000318,83.77), Point(443494.0939999993,7375737.347000319,83.842), Point(443494.175,7375741.528,83.84599986040107)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,18.388,82872,465415,14,reversed = false,None,1652179948783L,225079139,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, RoadPart(49529,1),Track.RightSide,Discontinuity.Continuous,AddrMRange(44,63),AddrMRange(44,63),None,None,Some("test"),"d7eb3af7-eb2b-4372-bfd9-0b717d153347:2", 0.0,18.902,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(443500.375,7375722.637,83.574), Point(443501.2639999993,7375729.793000318,83.692), Point(443502.579,7375741.41,83.81099797428476)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,18.902,82725,465373,14,reversed = false,None,1652179948783L,225079136,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, RoadPart(49529,1),Track.LeftSide,Discontinuity.Continuous,AddrMRange(61,78),AddrMRange(61,78),None,None,Some("test"),"32601586-1464-4e5b-be20-bf56f055aa10:2", 0.0,16.967,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(443494.175,7375741.528,83.846), Point(443494.0939999994,7375751.881000319,83.996), Point(443494.099,7375758.495,84.13399334939625)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,16.967,82872,465416,14,reversed = false,None,1652179948783L,225079139,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, RoadPart(49529,1),Track.RightSide,Discontinuity.Continuous,AddrMRange(63,80),AddrMRange(63,80),None,None,Some("test"),"ebf9e382-f212-49bb-bbe8-758e42ee69ae:2", 0.0,17.426,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(443502.579,7375741.41,83.811), Point(443502.3979999993,7375751.430000319,83.89), Point(443502.2169999994,7375758.832000319,83.951)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,17.426,82725,465374,14,reversed = false,None,1652179948783L,225079136,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, RoadPart(49529,1),Track.LeftSide,Discontinuity.Continuous,AddrMRange(78,188),AddrMRange(78,188),None,None,Some("test"),"38c473c7-28d5-433b-9678-c9f08d67c142:2", 0.0,110.036,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(443494.099,7375758.495,84.134), Point(443493.2129999993,7375789.17000032,84.502), Point(443492.5799999993,7375804.482000319,84.562), Point(443490.1759999994,7375823.843000322,84.466), Point(443488.5879999994,7375839.222000319,84.403), Point(443489.5429999994,7375848.899000319,84.416), Point(443491.6949999993,7375861.04800032,84.416), Point(443492.5799999994,7375867.982000319,84.433)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,110.036,82872,465417,14,reversed = false,None,1652179948783L,225079139,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, RoadPart(49529,1),Track.RightSide,Discontinuity.Continuous,AddrMRange(80,189),AddrMRange(80,189),None,None,Some("test"),"b3f904e5-b7b6-499c-9ff5-bde55ad65d1e:2", 0.0,107.869,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(443502.217,7375758.832,83.951), Point(443501.3309999996,7375779.81400032,84.157), Point(443501.3289999993,7375799.578000319,84.304), Point(443498.6549999993,7375830.170000319,84.378), Point(443497.5149999993,7375841.8130003195,84.37), Point(443498.9079999993,7375852.69500032,84.35), Point(443500.173,7375866.363,84.31100065015279)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,107.869,82725,465375,14,reversed = false,None,1652179948783L,225079136,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, RoadPart(49529,1),Track.LeftSide,Discontinuity.Continuous,AddrMRange(188,206),AddrMRange(188,206),None,None,Some("test"),"49ecdce7-4115-4d3d-b63d-2115d7dba40b:2", 0.0,18.22,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(443492.58,7375867.982,84.433), Point(443493.8459999995,7375877.473000319,84.346), Point(443494.906,7375886.053,84.24700335742143)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,18.22,82872,465418,14,reversed = false,None,1652179948783L,225079139,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, RoadPart(49529,1),Track.RightSide,Discontinuity.Continuous,AddrMRange(189,213),AddrMRange(189,213),None,None,Some("test"),"167a230f-f59a-4d3a-84b1-e131c248cb7a:2", 0.0,24.18,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(443500.173,7375866.363,84.311), Point(443500.9319999992,7375869.526000318,84.292), Point(443502.1969999993,7375873.51700032,84.286), Point(443508.949,7375888.836,84.18800280157677)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,24.18,82725,465376,14,reversed = false,None,1652179948783L,225079136,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, RoadPart(49529,1),Track.LeftSide,Discontinuity.Continuous,AddrMRange(206,219),AddrMRange(206,219),None,None,Some("test"),"be5499ac-03bf-4fca-abac-bd8af0e7eaf3:2", 0.0,13.135,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(443494.906,7375886.053,84.247), Point(443501.4159999995,7375897.461000321,84.106)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,13.135,82872,465419,14,reversed = false,None,1652179948783L,225079139,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, RoadPart(49529,1),Track.RightSide,Discontinuity.Continuous,AddrMRange(213,227),AddrMRange(213,227),None,None,Some("test"),"9acdcdf3-7a11-49e8-9f5f-f62863290f5a:2", 0.0,13.658,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(443508.949,7375888.836,84.188), Point(443515.4779999993,7375898.95300032,84.107), Point(443516.236,7375900.382,84.11199870184565)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,13.658,82725,465377,14,reversed = false,None,1652179948783L,225079136,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, RoadPart(49529,1),Track.LeftSide,Discontinuity.Continuous,AddrMRange(219,229),AddrMRange(219,229),None,None,Some("test"),"7987813c-6870-4883-81fe-d7e1c25fa21f:2", 0.0,9.794,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(443501.416,7375897.461,84.106), Point(443507.5759999993,7375903.09900032,84.054), Point(443508.565,7375904.151,84.05100103825822)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,9.794,82872,465420,14,reversed = false,None,1652179948783L,225079139,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, RoadPart(49529,1),Track.LeftSide,Discontinuity.Continuous,AddrMRange(229,237),AddrMRange(229,237),None,None,Some("test"),"3bf22c87-ce97-4bc7-99fb-6bf157756e24:2", 0.0,8.664,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(443508.565,7375904.151,84.051), Point(443514.1049999993,7375910.812000319,84.028)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,8.664,82872,465421,14,reversed = false,None,1652179948783L,225079139,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, RoadPart(49529,1),Track.RightSide,Discontinuity.Continuous,AddrMRange(227,241),AddrMRange(227,241),None,None,Some("test"),"2a2c5c75-dc63-4d2d-9379-9c5ddaf64546:2", 0.0,13.868,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(443516.236,7375900.382,84.112), Point(443522.6979999994,7375912.652000319,84.123)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,13.868,82725,465378,14,reversed = false,None,1652179948783L,225079136,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, RoadPart(49529,1),Track.RightSide,Discontinuity.Continuous,AddrMRange(241,309),AddrMRange(241,309),None,None,Some("test"),"fb18fd8c-64b6-4f89-a039-68de753b0bef:2", 0.0,68.332,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(443522.698,7375912.652,84.123), Point(443532.9479999992,7375934.6710003195,84.425), Point(443544.3379999994,7375959.348000321,84.916), Point(443551.677,7375974.533,85.23999766335862)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,68.332,82725,465379,14,reversed = false,None,1652179948783L,225079136,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, RoadPart(49529,1),Track.RightSide,Discontinuity.Continuous,AddrMRange(309,380),AddrMRange(309,380),None,None,Some("test"),"4401fa25-6e4e-4934-9678-d1e88e5bce2a:2", 0.0,70.971,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(443551.677,7375974.533,85.24), Point(443569.7719999993,7376013.231000318,86.063), Point(443583.0509999992,7376038.16700032,86.563)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,70.971,82725,465380,14,reversed = false,None,1652179948783L,225079136,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, RoadPart(49529,1),Track.RightSide,Discontinuity.Continuous,AddrMRange(380,403),AddrMRange(380,403),None,None,Some("test"),"454db012-0d1e-4d2c-b5fb-f124a734ae07:2", 0.0,22.514,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(443583.051,7376038.167,86.563), Point(443585.6809999994,7376043.77500032,86.687), Point(443591.4179999993,7376059.053000319,86.914)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,22.514,82725,465381,14,reversed = false,None,1652179948783L,225079136,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, RoadPart(49529,1),Track.LeftSide,Discontinuity.Continuous,AddrMRange(237,403),AddrMRange(237,403),None,None,Some("test"),"1a9c7e0a-7c0f-40aa-a8db-b8e8c1bb6dd2:2", 0.0,166.014,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(443514.105,7375910.812,84.028), Point(443521.6779999994,7375926.675000318,84.206), Point(443535.5999999993,7375956.344000321,84.693), Point(443554.0039999994,7375994.808000319,85.553), Point(443565.4009999994,7376019.545000321,86.091), Point(443578.5059999993,7376048.145000319,86.705), Point(443583.486,7376061.579,86.96499522732097)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,166.014,82872,465422,14,reversed = false,None,1652179948783L,225079139,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, RoadPart(49529,1),Track.RightSide,Discontinuity.Continuous,AddrMRange(403,416),AddrMRange(403,416),None,None,Some("test"),"a70a4c2f-c104-44fe-a4d7-3f3718b162aa:2", 0.0,13.427,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(443591.418,7376059.053,86.914), Point(443593.1079999993,7376065.19700032,86.995), Point(443594.9009999993,7376072.020000318,87.031)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,13.427,82725,465382,14,reversed = false,None,1652179948783L,225079136,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, RoadPart(49529,1),Track.LeftSide,Discontinuity.Continuous,AddrMRange(403,508),AddrMRange(403,508),None,None,Some("test"),"68679010-c1a1-45a3-b752-64d483ec09ea:2", 0.0,106.006,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(443583.486,7376061.579,86.965), Point(443588.5599999993,7376075.455000319,87.033), Point(443599.1929999994,7376108.867000319,86.924), Point(443609.0079999992,7376140.307000321,86.698), Point(443615.7369999994,7376162.54300032,86.499)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,106.006,82872,465423,14,reversed = false,None,1652179948783L,225079139,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, RoadPart(49529,1),Track.RightSide,Discontinuity.Continuous,AddrMRange(416,509),AddrMRange(416,509),None,None,Some("test"),"6e98cc1c-7bbe-4b88-ab24-f362665e3311:2", 0.0,92.416,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(443594.901,7376072.02,87.031), Point(443602.8679999993,7376094.647000318,87.035), Point(443608.4999999993,7376111.477000318,86.89), Point(443619.0969999993,7376140.316000319,86.645), Point(443626.4129999993,7376158.88200032,86.494)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,92.416,82725,465383,14,reversed = false,None,1652179948783L,225079136,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, RoadPart(49529,1),Track.LeftSide,Discontinuity.Continuous,AddrMRange(508,521),AddrMRange(508,521),None,None,Some("test"),"dfab6465-6395-4ac8-b72b-85ef40408443:2", 0.0,12.898,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(443615.737,7376162.543,86.499), Point(443620.4599999994,7376174.545000319,86.459)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,12.898,82872,465424,14,reversed = false,None,1652179948783L,225079139,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, RoadPart(49529,1),Track.RightSide,Discontinuity.Continuous,AddrMRange(509,522),AddrMRange(509,522),None,None,Some("test"),"d7aa5b8d-4db7-4d3a-b438-ad32ae2de8dc:2", 0.0,13.036,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(443626.413,7376158.882,86.494), Point(443628.1999999994,7376164.794000321,86.479), Point(443630.4639999993,7376171.269000321,86.432)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,13.036,82725,465384,14,reversed = false,None,1652179948783L,225079136,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, RoadPart(49529,1),Track.RightSide,Discontinuity.Continuous,AddrMRange(522,653),AddrMRange(522,653),None,None,Some("test"),"24b62493-3d76-41a5-a676-98589bed32be:2", 0.0,130.922,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(443630.464,7376171.269,86.432), Point(443632.9109999993,7376178.739000319,86.382), Point(443635.0769999993,7376184.996000319,86.344), Point(443635.7999999993,7376190.171000321,86.383), Point(443636.0399999993,7376196.18900032,86.389), Point(443639.6499999994,7376204.468000322,86.296), Point(443642.8999999993,7376212.2900003195,86.27), Point(443646.0289999993,7376219.535000319,86.231), Point(443652.2859999993,7376234.698000319,86.153), Point(443664.8019999994,7376263.5570003195,85.969), Point(443672.2629999995,7376281.609000319,85.874), Point(443677.179,7376292.999,85.81000004776878)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,130.922,82725,465385,14,reversed = false,None,1652179948783L,225079136,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, RoadPart(49529,1),Track.LeftSide,Discontinuity.MinorDiscontinuity,AddrMRange(521,671),AddrMRange(521,671),None,None,Some("test"),"ce492f47-e821-423d-ba5a-fd71fdeb71d5:2", 0.0,150.546,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(443620.46,7376174.545,86.459), Point(443626.8709999993,7376193.17400032,86.216), Point(443633.8369999993,7376212.708000317,86.079), Point(443649.7799999995,7376249.5510003185,85.975), Point(443664.5179999993,7376284.78700032,85.862), Point(443674.1479999994,7376308.775000319,85.732), Point(443676.263,7376314.303,85.76499785339493)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,150.546,82872,465425,14,reversed = false,None,1652179948783L,225079139,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, RoadPart(49529,1),Track.RightSide,Discontinuity.MinorDiscontinuity,AddrMRange(653,672),AddrMRange(653,672),None,None,Some("test"),"96d2a31a-fafc-4a1e-aeda-ef7e047207b0:2", 0.0,19.434,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(443677.179,7376292.999,85.81), Point(443680.1569999993,7376301.476000319,85.785), Point(443681.7609999994,7376306.97300032,85.795), Point(443683.5929999993,7376311.32600032,85.751)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,19.434,82725,465386,14,reversed = false,None,1652179948783L,225079136,roadName,None,None,None,None),
        // Different length terminated sections
        ProjectLink(Sequences.nextProjectLinkId, RoadPart(49529,1),Track.RightSide,Discontinuity.Continuous,AddrMRange(672,696),AddrMRange(672,696),None,None,Some("test"),"d061fce2-c526-4967-bf33-62b8005c9c97:1", 0.0,22.987,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(443683.593,7376311.326,0.0), Point(443695.047,7376331.256,0.0)),projectId,  RoadAddressChangeType.Termination,AdministrativeClass.Municipality, FrozenLinkInterface,22.987,82725,465387,14,reversed = false,None,1652179948783L,225079136,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, RoadPart(49529,1),Track.LeftSide,Discontinuity.Continuous,AddrMRange(671,696),AddrMRange(671,696),None,None,Some("test"),"629b8776-c3aa-47c5-b8ad-52cb1aed8f51:1", 0.0,25.303,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(443676.263,7376314.303,0.0), Point(443695.047,7376331.256,0.0)),projectId,  RoadAddressChangeType.Termination,AdministrativeClass.Municipality, FrozenLinkInterface,25.303,82872,465426,14,reversed = false,None,1652179948783L,225079139,roadName,None,None,None,None),

        ProjectLink(Sequences.nextProjectLinkId, RoadPart(49529,1),Track.Combined,Discontinuity.Continuous,AddrMRange(696,817),AddrMRange(696,817),None,None,Some("test"),"4c184326-404e-4e3b-b13c-e256a3312aa1:2", 0.0,120.399,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(443695.047,7376331.256,85.483), Point(443713.2219999993,7376349.65100032,84.513), Point(443731.8249999995,7376367.969000318,83.53), Point(443753.9519999993,7376389.32500032,82.332), Point(443771.1619999993,7376406.083000321,81.569), Point(443780.5609999993,7376415.993000319,81.168)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.Municipality, FrozenLinkInterface,120.399,82220,466778,14,reversed = false,None,1652179948783L,225079142,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, RoadPart(49529,1),Track.Combined,Discontinuity.Continuous,AddrMRange(817,827),AddrMRange(817,827),None,None,Some("test"),"549ea1bc-02ef-4e66-9240-adaa77471331:2", 0.0,10.049,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(443780.561,7376415.993,81.168), Point(443784.3299999993,7376420.59200032,80.989), Point(443786.531,7376424.055,80.79901704515324)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.Municipality, FrozenLinkInterface,10.049,82220,466779,14,reversed = false,None,1652179948783L,225079142,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, RoadPart(49529,1),Track.Combined,Discontinuity.Continuous,AddrMRange(827,906),AddrMRange(827,906),None,None,Some("test"),"b3f70ee2-16d3-40cf-a67f-9c78c5ec879f:2", 0.0,78.104,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(443786.531,7376424.055,80.799), Point(443790.5529999994,7376432.579000319,80.54), Point(443802.4219999994,7376459.50800032,80.162), Point(443810.4449999995,7376477.324000318,80.024), Point(443818.744,7376495.203,79.9240011690673)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.Municipality, FrozenLinkInterface,78.104,82220,466780,14,reversed = false,None,1652179948783L,225079142,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, RoadPart(49529,1),Track.Combined,Discontinuity.Continuous,AddrMRange(906,1021),AddrMRange(906,1021),None,None,Some("test"),"bffb8c7d-aac0-4e7c-a115-101e14952a09:2", 0.0,113.886,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(443818.744,7376495.203,79.924), Point(443828.2399999993,7376515.89300032,79.824), Point(443837.9789999993,7376539.783000319,80.241), Point(443846.7379999993,7376559.897000317,80.747), Point(443854.2689999994,7376576.892000319,81.273), Point(443864.362,7376599.54,82.02798840242914)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.Municipality, FrozenLinkInterface,113.886,82220,466781,14,reversed = false,None,1652179948783L,225079142,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, RoadPart(49529,1),Track.Combined,Discontinuity.Continuous,AddrMRange(1021,1030),AddrMRange(1021,1030),None,None,Some("test"),"3121460f-5d95-43b0-ae1d-88d71ba91ffe:2", 0.0,9.109,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(443864.362,7376599.54,82.028), Point(443867.8419999993,7376607.958000319,82.31)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.Municipality, FrozenLinkInterface,9.109,82220,466782,14,reversed = false,None,1652179948783L,225079142,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, RoadPart(49529,1),Track.Combined,Discontinuity.EndOfRoad,AddrMRange(1030,1042),AddrMRange(1030,1042),None,None,Some("test"),"92d3f0ef-db38-476f-b5ff-49b18d74b4aa:2", 0.0,12.007,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(443867.842,7376607.958,82.31), Point(443870.0459999994,7376613.328000318,82.452), Point(443871.9669999994,7376619.22500032,82.34)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.Municipality, FrozenLinkInterface,12.007,82220,466783,14,reversed = false,None,1652179948783L,225079142,roadName,None,None,None,None)
      )

      linearLocationDAO.create(linearLocations)
      roadwayDAO.create(roadways)
      projectDAO.create(Project(projectId, ProjectState.Incomplete, "test", "test", DateTime.now(),"test", DateTime.now(), DateTime.now(),"",Seq(ProjectReservedPart(Sequences.nextViitePrimaryKeySeqValue,roadPart)),Seq(ProjectReservedPart(Sequences.nextViitePrimaryKeySeqValue,roadPart)),None,None,Set(14)))
      projectReservedPartDAO.reserveRoadPart(projectId, roadPart, "test")
      projectLinkDAO.create(projectLinks)

      projectService.recalculateProjectLinks(projectId, "test", Set(roadPart))
      val recalculated = projectLinkDAO.fetchProjectLinks(projectId)
      recalculated.size should be (39)
      val (terminated, others) = recalculated.partition(_.status == RoadAddressChangeType.Termination)
      val leftSide = others.filter(_.track == Track.LeftSide)
      val rightSide = others.filter(_.track == Track.RightSide)
      val terminatedLeft = terminated.filter(_.track == Track.LeftSide)
      val terminatedRight = terminated.filter(_.track == Track.RightSide)
      leftSide.map(_.addrMRange.end).max should be (672)
      rightSide.map(_.addrMRange.end).max should be (672)
      terminatedRight.map(_.addrMRange.start).max should be (672)
      terminatedLeft.map(_.addrMRange.start).max should be (672)
    }
   }
      
   test("Test projectService.recalculateProjectLinks() Real life project on road parts 40921/2 and 31329/1, recalculation should complete successfully") {
    /**
     * There is more info of the project available on the Jira ticket VIITE-3178
     * */

    runWithRollback {
      // random road part numbers
      val roadPart1 = RoadPart(49529,1)
      val roundaboutRoadPart = RoadPart(31329,1)
      val projectId = Sequences.nextViiteProjectId
      val roadName = Some("testRoad")

      val roadways = Seq(
        Roadway(59551,43168588,roadPart1,AdministrativeClass.Municipality,Track.Combined,Discontinuity.EndOfRoad,AddrMRange(5621, 5992),false,DateTime.now().minusDays(2),None,"test",None,14,TerminationCode.NoTermination,DateTime.now().minusDays(2),None),
        Roadway(74878,148600188,roadPart1,AdministrativeClass.Municipality,Track.RightSide,Discontinuity.Continuous,AddrMRange(5209, 5528),false,DateTime.now().minusDays(2),None,"test",None,14,TerminationCode.NoTermination,DateTime.now().minusDays(2),None),
        Roadway(75119,148600207,roadPart1,AdministrativeClass.Municipality,Track.RightSide,Discontinuity.Continuous,AddrMRange(3481, 3915),false,DateTime.now().minusDays(2),None,"test",None,14,TerminationCode.NoTermination,DateTime.now().minusDays(2),None),
        Roadway(75007,148600384,roadPart1,AdministrativeClass.Municipality,Track.RightSide,Discontinuity.Continuous,AddrMRange(3400, 3481),false,DateTime.now().minusDays(2),None,"test",None,14,TerminationCode.NoTermination,DateTime.now().minusDays(2),None),
        Roadway(74886,148600417,roadPart1,AdministrativeClass.Municipality,Track.RightSide,Discontinuity.Continuous,AddrMRange(3161, 3400),false,DateTime.now().minusDays(2),None,"test",None,14,TerminationCode.NoTermination,DateTime.now().minusDays(2),None),
        Roadway(75256,148600420,roadPart1,AdministrativeClass.Municipality,Track.Combined,Discontinuity.MinorDiscontinuity,AddrMRange(1170, 1494),false,DateTime.now().minusDays(2),None,"test",None,14,TerminationCode.NoTermination,DateTime.now().minusDays(2),None),
        Roadway(74889,148600423,roadPart1,AdministrativeClass.Municipality,Track.RightSide,Discontinuity.Continuous,AddrMRange(937, 1170),false,DateTime.now().minusDays(2),None,"test",None,14,TerminationCode.NoTermination,DateTime.now().minusDays(2),None),
        Roadway(75025,148600426,roadPart1,AdministrativeClass.Municipality,Track.Combined,Discontinuity.Continuous,AddrMRange(695, 937),false,DateTime.now().minusDays(2),None,"test",None,14,TerminationCode.NoTermination,DateTime.now().minusDays(2),None),
        Roadway(74690,148600429,roadPart1,AdministrativeClass.Municipality,Track.RightSide,Discontinuity.Continuous,AddrMRange(0, 695),false,DateTime.now().minusDays(2),None,"test",None,14,TerminationCode.NoTermination,DateTime.now().minusDays(2),None),
        Roadway(74803,148694062,roadPart1,AdministrativeClass.Municipality,Track.LeftSide,Discontinuity.Continuous,AddrMRange(5209, 5528),false,DateTime.now().minusDays(2),None,"test",None,14,TerminationCode.NoTermination,DateTime.now().minusDays(2),None),
        Roadway(74576,148694072,roadPart1,AdministrativeClass.Municipality,Track.LeftSide,Discontinuity.Continuous,AddrMRange(3481, 3915),false,DateTime.now().minusDays(2),None,"test",None,14,TerminationCode.NoTermination,DateTime.now().minusDays(2),None),
        Roadway(75072,148694086,roadPart1,AdministrativeClass.Municipality,Track.LeftSide,Discontinuity.Continuous,AddrMRange(3161, 3400),false,DateTime.now().minusDays(2),None,"test",None,14,TerminationCode.NoTermination,DateTime.now().minusDays(2),None),
        Roadway(75327,148694087,roadPart1,AdministrativeClass.Municipality,Track.LeftSide,Discontinuity.Continuous,AddrMRange(937, 1170),false,DateTime.now().minusDays(2),None,"test",None,14,TerminationCode.NoTermination,DateTime.now().minusDays(2),None),
        Roadway(75100,148694088,roadPart1,AdministrativeClass.Municipality,Track.LeftSide,Discontinuity.Continuous,AddrMRange(0, 695),false,DateTime.now().minusDays(2),None,"test",None,14,TerminationCode.NoTermination,DateTime.now().minusDays(2),None),
        Roadway(76524,168748574,roadPart1,AdministrativeClass.Municipality,Track.LeftSide,Discontinuity.Continuous,AddrMRange(3400, 3481),false,DateTime.now().minusDays(2),None,"test",None,14,TerminationCode.NoTermination,DateTime.now().minusDays(2),None),
        Roadway(79525,190813336,roundaboutRoadPart,AdministrativeClass.Municipality,Track.Combined,Discontinuity.EndOfRoad,AddrMRange(0, 67),false,DateTime.now().minusDays(2),None,"test",None,14,TerminationCode.NoTermination,DateTime.now().minusDays(2),None),
        Roadway(79295,190960312,roadPart1,AdministrativeClass.Municipality,Track.RightSide,Discontinuity.Continuous,AddrMRange(5528, 5621),false,DateTime.now().minusDays(2),None,"test",None,14,TerminationCode.NoTermination,DateTime.now().minusDays(2),None),
        Roadway(79183,190960315,roadPart1,AdministrativeClass.Municipality,Track.LeftSide,Discontinuity.Continuous,AddrMRange(5528, 5621),false,DateTime.now().minusDays(2),None,"test",None,14,TerminationCode.NoTermination,DateTime.now().minusDays(2),None),
        Roadway(81391,202221121,roadPart1,AdministrativeClass.Municipality,Track.Combined,Discontinuity.Continuous,AddrMRange(1494, 3161),false,DateTime.now().minusDays(2),None,"test",None,14,TerminationCode.NoTermination,DateTime.now().minusDays(2),None),
        Roadway(85053,262360669,roadPart1,AdministrativeClass.Municipality,Track.Combined,Discontinuity.MinorDiscontinuity,AddrMRange(3915, 5209),false,DateTime.now().minusDays(2),None,"test",None,14,TerminationCode.NoTermination,DateTime.now().minusDays(2),None)
      )

      val linearLocations = Seq(
        LinearLocation(440905, 4.0,"e96810cb-5ea7-4b20-ac61-17d2fe4a9202:1",0.0,15.742,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(378537.31,6674125.471,0.0), Point(378550.164,6674134.498,0.0)),FrozenLinkInterface,148694072,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(459044, 2.0,"d226a98d-0ddd-469a-bbcd-4256de7b59f3:1",0.0,222.288,SideCode.AgainstDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(376751.346,6673888.271,0.0), Point(376648.988,6674084.332,0.0)),FrozenLinkInterface,202221121,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(443113, 8.0,"966d177a-6150-41da-8fa2-98b0c223f441:1",0.0,19.06,SideCode.AgainstDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(375763.175,6673670.359,0.0), Point(375744.127,6673671.008,0.0)),FrozenLinkInterface,148600429,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(459059, 17.0,"ac7b6f6a-1011-46ec-90fd-9ba9367656f5:1",0.0,12.52,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(377671.068,6673808.319,0.0), Point(377683.588,6673808.371,0.0)),FrozenLinkInterface,202221121,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(440723, 3.0,"0455a8e1-1711-4d17-bd5c-aa0b9525e95b:1",0.0,13.822,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(378531.033,6674107.583,0.0), Point(378543.058,6674114.399,0.0)),FrozenLinkInterface,148600207,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(455773, 1.0,"71520cc1-fa64-4884-8d43-6ff46cefb717:1",0.0,18.308,SideCode.AgainstDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(379637.918,6673866.5,0.0), Point(379620.926,6673873.315,0.0)),FrozenLinkInterface,190960315,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(443106, 1.0,"9d397221-8daa-4e4d-b68b-739b207c7d6d:1",0.0,149.578,SideCode.AgainstDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(375317.625,6673738.976,0.0), Point(375172.788,6673775.964,0.0)),FrozenLinkInterface,148600429,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(442468, 7.0,"70f47ce7-e8a0-49d0-9abd-3af4d972355e:1",0.0,19.87,SideCode.AgainstDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(375763.327,6673678.193,0.0), Point(375743.458,6673678.333,0.0)),FrozenLinkInterface,148694088,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(442469, 8.0,"8b75c0c5-2a72-44c3-959c-b7fcdacea4ac:1",0.0,109.394,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(375763.327,6673678.193,0.0), Point(375872.369,6673684.271,0.0)),FrozenLinkInterface,148694088,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(468851, 3.0,"b9dc0e76-8b9a-4b10-99f5-1e43c1da3f16:1",0.0,9.639,SideCode.AgainstDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(378862.146,6673929.721,0.0), Point(378857.421,6673938.123,0.0)),FrozenLinkInterface,262360669,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(443116, 11.0,"ac3f145c-da7d-4404-add9-abf5fdc44acd:1",0.0,9.231,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(375914.958,6673688.467,0.0), Point(375922.468,6673693.835,0.0)),FrozenLinkInterface,148600429,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(440160, 1.0,"a123a3e5-d3d3-4ab6-a6bf-f7bfcc08f381:1",34.055,112.866,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(378206.827,6673998.946,0.0), Point(378285.495,6674003.583,0.0)),FrozenLinkInterface,148600384,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(443449, 2.0,"46a2d4ef-104f-4341-bfa2-15329e219be2:1",0.0,104.264,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(378036.469,6673974.533,0.0), Point(378137.179,6674000.863,0.0)),FrozenLinkInterface,148694086,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(443110, 5.0,"6fad148a-80e2-4027-923a-27361670601a:1",0.0,54.569,SideCode.AgainstDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(375430.859,6673704.633,0.0), Point(375378.39,6673719.626,0.0)),FrozenLinkInterface,148600429,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(468863, 15.0,"c165f16e-1204-4a20-b098-72c681269d36:1",0.0,53.949,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(379523.688,6673820.097,0.0), Point(379541.177,6673871.038,0.0)),FrozenLinkInterface,262360669,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(468856, 8.0,"162052ae-b9ad-49ef-8a0a-4f57e7074022:1",0.0,81.397,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(379059.529,6673653.855,0.0), Point(379140.913,6673654.368,0.0)),FrozenLinkInterface,262360669,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(459050, 8.0,"e29625e0-cec8-4087-a0cf-e8c14a97ca01:1",0.0,11.096,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(376847.662,6673709.143,0.0), Point(376857.816,6673713.617,0.0)),FrozenLinkInterface,202221121,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(441539, 3.0,"fac1f380-66e0-4087-a420-12a0a25227e3:1",0.0,132.393,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(376227.594,6673870.272,0.0), Point(376334.443,6673947.861,0.0)),FrozenLinkInterface,148600423,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(459053, 11.0,"1a758226-5dd8-4ede-977e-032557ab195b:1",0.0,18.234,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(377259.898,6673862.802,0.0), Point(377277.675,6673866.485,0.0)),FrozenLinkInterface,202221121,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(441813, 2.0,"37902af2-406e-4c6f-9bd2-f944143a523d:1",18.308,71.068,SideCode.AgainstDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(379619.779,6673868.984,0.0), Point(379568.737,6673880.788,0.0)),FrozenLinkInterface,148600188,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(459048, 6.0,"d51360af-aaaa-4931-8ab1-43f16e7fb094:1",0.0,16.279,SideCode.AgainstDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(376840.287,6673705.924,0.0), Point(376832.092,6673719.99,0.0)),FrozenLinkInterface,202221121,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(468859, 11.0,"58c2ea47-2c12-43a3-a0dd-00dccc5c5424:1",0.0,12.978,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(379299.237,6673611.767,0.0), Point(379308.054,6673621.29,0.0)),FrozenLinkInterface,262360669,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(468852, 4.0,"cebec33d-35fb-4a88-8548-9f746ec34d7b:1",0.0,84.984,SideCode.AgainstDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(378885.698,6673848.073,0.0), Point(378862.146,6673929.721,0.0)),FrozenLinkInterface,262360669,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(459063, 21.0,"5df1b25a-1175-46c7-b89c-2cda75dec8b2:1",0.0,15.423,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(377851.59,6673857.539,0.0), Point(377863.988,6673866.712,0.0)),FrozenLinkInterface,202221121,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(468850, 2.0,"0ab12b4f-8dc8-45aa-abca-b422be549d93:1",0.0,63.408,SideCode.AgainstDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(378857.421,6673938.123,0.0), Point(378820.527,6673989.657,0.0)),FrozenLinkInterface,262360669,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(440904, 3.0,"5a1f4f77-5b1f-412a-9a4d-83a98ba93558:1",0.0,14.002,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(378526.152,6674117.012,0.0), Point(378537.31,6674125.471,0.0)),FrozenLinkInterface,148694072,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(443595, 1.0,"4d6d5d96-4ae2-41b1-8216-baec85be7a63:1",0.0,262.108,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(375922.468,6673693.835,0.0), Point(376147.918,6673823.127,0.0)),FrozenLinkInterface,148600426,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(441510, 2.0,"0dbd6f8b-3fa8-4e9b-957e-943e7466f92d:1",0.0,11.639,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(376606.118,6674092.87,0.0), Point(376617.39,6674095.754,0.0)),FrozenLinkInterface,148600420,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(442076, 1.0,"decaee8e-acb1-4bee-82e0-6dc1e10eb47a:1",0.0,79.863,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(376147.918,6673823.127,0.0), Point(376212.213,6673869.654,0.0)),FrozenLinkInterface,148694087,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(442467, 6.0,"38b3209c-e1c3-4dc0-a300-8f09a8d3ba71:1",0.0,307.145,SideCode.AgainstDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(375743.458,6673678.333,0.0), Point(375439.361,6673712.059,0.0)),FrozenLinkInterface,148694088,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(468855, 7.0,"85294d1e-5d8d-4d33-b265-306a5152cc9f:1",0.0,147.564,SideCode.AgainstDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(379059.529,6673653.855,0.0), Point(378933.977,6673721.963,0.0)),FrozenLinkInterface,262360669,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(459060, 18.0,"9f3399d8-79d6-4df9-8906-7110c5459384:1",0.0,26.701,SideCode.AgainstDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(377710.178,6673805.939,0.0), Point(377683.588,6673808.371,0.0)),FrozenLinkInterface,202221121,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(459045, 3.0,"88e82ce2-5228-42af-bb19-d7c23e54537f:1",0.0,42.668,SideCode.AgainstDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(376767.442,6673848.758,0.0), Point(376751.346,6673888.271,0.0)),FrozenLinkInterface,202221121,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(440724, 4.0,"39609beb-f80e-46d2-b1a0-2645a8cf0836:1",0.0,14.725,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(378543.058,6674114.399,0.0), Point(378555.25,6674122.613,0.0)),FrozenLinkInterface,148600207,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(442734, 2.0,"ad9de2c1-cc33-470e-b9f2-4bc2031d82f5:1",0.0,102.635,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(378038.77,6673969.44,0.0), Point(378138.229,6673994.022,0.0)),FrozenLinkInterface,148600417,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(468860, 12.0,"d16b12fd-2902-4c00-8b97-6dc437692805:1",0.0,91.195,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(379308.054,6673621.29,0.0), Point(379375.757,6673682.247,0.0)),FrozenLinkInterface,262360669,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(456571, 4.0,"a94794ff-507a-4599-8428-bf08cdc8ef8a:1",0.0,11.41,SideCode.AgainstDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(379542.943,6673900.897,0.0), Point(379554.07,6673901.7,0.0)),FrozenLinkInterface,190813336,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(459049, 7.0,"edfd9a80-6ffa-4d98-9651-728fb706aa5d:1",0.0,8.047,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(376840.287,6673705.924,0.0), Point(376847.662,6673709.143,0.0)),FrozenLinkInterface,202221121,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(442464, 3.0,"33d7f4e2-b056-4b36-89fd-611b0bfb5b7c:1",0.0,13.778,SideCode.AgainstDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(375359.317,6673735.622,0.0), Point(375346.097,6673739.505,0.0)),FrozenLinkInterface,148694088,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(443450, 3.0,"36f5cafc-b5b2-4bf4-81e5-a3e7568697ae:1",0.0,19.899,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(378137.179,6674000.863,0.0), Point(378156.852,6674003.814,0.0)),FrozenLinkInterface,148694086,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(441538, 2.0,"ddff02ce-16bd-4ad1-9e48-dd981cd5470e:1",0.0,13.806,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(376215.994,6673862.786,0.0), Point(376227.594,6673870.272,0.0)),FrozenLinkInterface,148600423,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(398453, 4.0,"d88a35c8-f1da-43b1-91d4-849aba7a32e2:1",0.0,55.1,SideCode.AgainstDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(379859.38,6673564.541,0.0), Point(379847.67,6673618.363,0.0)),FrozenLinkInterface,43168588,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(468849, 1.0,"8e765acc-fb6a-4a1c-beef-9d98221a3bad:1",0.0,236.088,SideCode.AgainstDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(378820.527,6673989.657,0.0), Point(378674.337,6674173.803,0.0)),FrozenLinkInterface,262360669,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(456572, 5.0,"64a055d4-d895-45e0-b5a9-4c4ef3d779d0:1",0.0,22.417,SideCode.AgainstDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(379544.623,6673881.951,0.0), Point(379542.943,6673900.897,0.0)),FrozenLinkInterface,190813336,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(398450, 1.0,"0f167a06-1557-4253-85c0-9d9f3de9523a:1",0.0,128.799,SideCode.AgainstDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(379724.607,6673774.644,0.0), Point(379637.918,6673866.5,0.0)),FrozenLinkInterface,43168588,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(442471, 10.0,"c3f9058f-28e9-47f5-abd0-026a9a2c6d1a:1",0.0,7.938,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(375914.534,6673693.586,0.0), Point(375922.468,6673693.835,0.0)),FrozenLinkInterface,148694088,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(459064, 22.0,"ae626bf0-f18f-4b7b-b18f-26692ae324c6:1",0.0,139.402,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(377863.988,6673866.712,0.0), Point(377979.788,6673944.3,0.0)),FrozenLinkInterface,202221121,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(443109, 4.0,"7d44abe8-394f-4ced-90bd-5ad061ccb714:1",0.0,21.861,SideCode.AgainstDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(375378.39,6673719.626,0.0), Point(375357.397,6673725.725,0.0)),FrozenLinkInterface,148600429,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(443108, 3.0,"0989c304-1b74-452f-bca4-5c9bc9e9f421:1",0.0,14.918,SideCode.AgainstDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(375357.397,6673725.725,0.0), Point(375343.054,6673729.825,0.0)),FrozenLinkInterface,148600429,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(468854, 6.0,"1411d3ad-17eb-46c3-a2f0-66aa5104d46d:1",0.0,82.849,SideCode.AgainstDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(378933.977,6673721.963,0.0), Point(378901.051,6673797.573,0.0)),FrozenLinkInterface,262360669,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(440721, 1.0,"a123a3e5-d3d3-4ab6-a6bf-f7bfcc08f381:1",112.866,146.921,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(378285.495,6674003.583,0.0), Point(378319.458,6674006.087,0.0)),FrozenLinkInterface,148600207,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(459057, 15.0,"8fbb7ab3-763e-49bf-ae96-cae737f8d322:1",0.0,10.514,SideCode.AgainstDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(377603.362,6673812.81,0.0), Point(377592.869,6673813.466,0.0)),FrozenLinkInterface,202221121,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(455758, 1.0,"37902af2-406e-4c6f-9bd2-f944143a523d:1",0.0,18.308,SideCode.AgainstDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(379637.918,6673866.5,0.0), Point(379619.779,6673868.984,0.0)),FrozenLinkInterface,190960312,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(443448, 1.0,"b424932d-201f-4bae-9df1-294fbfa1ce65:1",0.0,64.382,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(377979.788,6673944.3,0.0), Point(378036.469,6673974.533,0.0)),FrozenLinkInterface,148694086,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(468864, 16.0,"36ca0704-f761-4229-abe0-df1c711671df:1",0.0,11.444,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(379541.177,6673871.038,0.0), Point(379544.623,6673881.951,0.0)),FrozenLinkInterface,262360669,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(441509, 1.0,"2aabe2ea-7bb9-41b1-9256-9268470f6ec0:1",0.0,309.606,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(376334.443,6673947.861,0.0), Point(376606.118,6674092.87,0.0)),FrozenLinkInterface,148600420,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(442466, 5.0,"b6417c65-4064-4193-a7a5-2e5bcc15fe43:1",0.0,61.421,SideCode.AgainstDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(375439.361,6673712.059,0.0), Point(375380.543,6673729.739,0.0)),FrozenLinkInterface,148694088,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(440725, 5.0,"3c89c56b-fa39-4968-85d4-d79ddb8250e4:1",0.0,130.751,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(378555.25,6674122.613,0.0), Point(378674.337,6674173.803,0.0)),FrozenLinkInterface,148600207,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(459056, 14.0,"ccd72deb-d1df-4740-81ff-3e0849f28d2f:1",0.0,43.215,SideCode.AgainstDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(377592.869,6673813.466,0.0), Point(377549.921,6673818.015,0.0)),FrozenLinkInterface,202221121,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(459061, 19.0,"3756dad4-faeb-4645-b922-0d60cef7cbdb:1",0.0,119.207,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(377710.178,6673805.939,0.0), Point(377822.436,6673835.723,0.0)),FrozenLinkInterface,202221121,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(398452, 3.0,"deb4a845-c1fd-4653-a571-a8c900ac6a24:1",0.0,183.905,SideCode.AgainstDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(379847.67,6673618.363,0.0), Point(379737.087,6673759.694,0.0)),FrozenLinkInterface,43168588,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(443451, 4.0,"86be0fd6-8e59-48e4-9bd2-2994d055e059:1",0.0,15.106,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(378156.852,6674003.814,0.0), Point(378171.936,6674004.633,0.0)),FrozenLinkInterface,148694086,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(442733, 1.0,"3a8526e1-aecb-45e1-9299-f6d5eb8a8eb4:1",0.0,64.218,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(377979.788,6673944.3,0.0), Point(378038.77,6673969.44,0.0)),FrozenLinkInterface,148600417,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(459046, 4.0,"86e7008c-3941-4a48-b1b7-93967aa2a8bb:1",0.0,18.867,SideCode.AgainstDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(376775.196,6673831.565,0.0), Point(376767.442,6673848.758,0.0)),FrozenLinkInterface,202221121,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(468857, 9.0,"7f7e5213-0d20-4ca1-a733-62fe7ea501ea:1",0.0,91.853,SideCode.AgainstDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(379231.885,6673644.355,0.0), Point(379140.913,6673654.368,0.0)),FrozenLinkInterface,262360669,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(440902, 1.0,"1024f118-3b80-46c1-a2d0-d48769aa648f:1",112.37,147.175,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(378284.163,6674010.211,0.0), Point(378318.714,6674014.239,0.0)),FrozenLinkInterface,148694072,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(442737, 5.0,"a123a3e5-d3d3-4ab6-a6bf-f7bfcc08f381:1",0.0,34.055,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(378172.867,6673996.456,0.0), Point(378206.827,6673998.946,0.0)),FrozenLinkInterface,148600417,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(459052, 10.0,"9cc9a84b-55ae-4873-b44c-2d40d2e8c6f6:1",0.0,228.751,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(377045.18,6673784.066,0.0), Point(377259.898,6673862.802,0.0)),FrozenLinkInterface,202221121,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(441537, 1.0,"2528f8f8-cdbe-409a-ab90-626fd57350e4:1",0.0,78.97,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(376147.918,6673823.127,0.0), Point(376215.994,6673862.786,0.0)),FrozenLinkInterface,148600423,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(442463, 2.0,"deb53919-991d-4514-a6a9-c6b015bb042b:1",0.0,31.329,SideCode.AgainstDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(375346.097,6673739.505,0.0), Point(375315.646,6673746.872,0.0)),FrozenLinkInterface,148694088,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(443112, 7.0,"869cd1bf-1231-4ad9-b4df-9a7cf13bafb6:1",0.0,301.349,SideCode.AgainstDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(375744.127,6673671.008,0.0), Point(375444.814,6673701.266,0.0)),FrozenLinkInterface,148600429,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(468861, 13.0,"5ffffa6e-3448-47d9-8791-4afdb5e0e3c3:1",0.0,131.363,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(379375.757,6673682.247,0.0), Point(379474.881,6673768.386,0.0)),FrozenLinkInterface,262360669,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(442182, 2.0,"71520cc1-fa64-4884-8d43-6ff46cefb717:1",18.308,71.067,SideCode.AgainstDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(379620.926,6673873.315,0.0), Point(379570.557,6673888.718,0.0)),FrozenLinkInterface,148694062,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(456570, 3.0,"4d1377d8-6490-423d-b98d-7e4d0d821af1:1",0.0,9.54,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(379560.077,6673894.545,0.0), Point(379554.07,6673901.7,0.0)),FrozenLinkInterface,190813336,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(443114, 9.0,"1a2cbfb7-29d7-4a64-bd21-ead2eb5b6367:1",0.0,110.737,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(375763.175,6673670.359,0.0), Point(375873.553,6673677.983,0.0)),FrozenLinkInterface,148600429,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(456568, 1.0,"7bf4b8b8-b8cd-49c8-b503-349131d5dd12:1",0.0,15.267,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(379544.623,6673881.951,0.0), Point(379558.543,6673886.082,0.0)),FrozenLinkInterface,190813336,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(442077, 2.0,"6017e415-28cf-4b5d-baf3-b9e63c3bb508:1",0.0,13.404,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(376212.213,6673869.654,0.0), Point(376223.72,6673876.529,0.0)),FrozenLinkInterface,148694087,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(440722, 2.0,"6ba652f1-0284-4d0d-86a2-52778764e724:1",0.0,235.761,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(378319.458,6674006.087,0.0), Point(378531.033,6674107.583,0.0)),FrozenLinkInterface,148600207,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(443107, 2.0,"577f1426-ea90-4bf9-9f9f-08286f07130a:1",0.0,27.025,SideCode.AgainstDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(375343.054,6673729.825,0.0), Point(375317.625,6673738.976,0.0)),FrozenLinkInterface,148600429,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(442736, 4.0,"efe2fdae-d98e-4bb1-b6ba-94e93f0e9bea:1",0.0,15.366,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(378157.513,6673995.84,0.0), Point(378172.867,6673996.456,0.0)),FrozenLinkInterface,148600417,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(441812, 1.0,"eb80bddd-9d3e-40b5-b5aa-30b7ce4da857:1",0.0,11.487,SideCode.AgainstDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(379568.737,6673880.788,0.0), Point(379558.543,6673886.082,0.0)),FrozenLinkInterface,148600188,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(447590, 1.0,"1024f118-3b80-46c1-a2d0-d48769aa648f:1",31.822,112.37,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(378203.705,6674006.463,0.0), Point(378284.163,6674010.211,0.0)),FrozenLinkInterface,168748574,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(459043, 1.0,"0004883e-396d-415d-8e1d-8cec36970586:1",0.0,11.371,SideCode.AgainstDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(376648.988,6674084.332,0.0), Point(376641.408,6674092.808,0.0)),FrozenLinkInterface,202221121,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(442465, 4.0,"28d12fe4-1c80-45a7-a35c-cfacede055a3:1",0.0,22.026,SideCode.AgainstDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(375380.543,6673729.739,0.0), Point(375359.317,6673735.622,0.0)),FrozenLinkInterface,148694088,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(442462, 1.0,"e62f960d-b440-435b-87c9-376b703b6ff8:1",0.0,151.276,SideCode.AgainstDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(375315.646,6673746.872,0.0), Point(375169.282,6673785.082,0.0)),FrozenLinkInterface,148694088,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(468853, 5.0,"49706b34-ff08-4616-b338-a6857f1b513e:1",0.0,52.792,SideCode.AgainstDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(378901.051,6673797.573,0.0), Point(378885.698,6673848.073,0.0)),FrozenLinkInterface,262360669,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(442181, 1.0,"aa36fb9b-36db-4ed0-834c-26a11fbb660a:1",0.0,11.991,SideCode.AgainstDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(379570.557,6673888.718,0.0), Point(379560.077,6673894.545,0.0)),FrozenLinkInterface,148694062,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(440906, 5.0,"ba55aa47-aff5-4f34-84ea-d1e348ac34c0:1",0.0,134.131,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(378550.164,6674134.498,0.0), Point(378674.337,6674173.803,0.0)),FrozenLinkInterface,148694072,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(459058, 16.0,"5211f99d-323c-4396-a6b6-169683e6511c:1",0.0,67.866,SideCode.AgainstDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(377671.068,6673808.319,0.0), Point(377603.362,6673812.81,0.0)),FrozenLinkInterface,202221121,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(443452, 5.0,"1024f118-3b80-46c1-a2d0-d48769aa648f:1",0.0,31.822,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(378171.936,6674004.633,0.0), Point(378203.705,6674006.463,0.0)),FrozenLinkInterface,148694086,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(468858, 10.0,"3cfded00-72a7-437d-8e1c-c52c1d58dfc3:1",0.0,74.97,SideCode.AgainstDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(379299.237,6673611.767,0.0), Point(379231.885,6673644.355,0.0)),FrozenLinkInterface,262360669,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(398451, 2.0,"8cd1dec7-9fb3-4a4b-afa2-7256ceb9c529:1",0.0,19.568,SideCode.AgainstDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(379737.087,6673759.694,0.0), Point(379724.607,6673774.644,0.0)),FrozenLinkInterface,43168588,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(459055, 13.0,"174d9f43-5a10-4adb-acda-abc3ba2770a4:1",0.0,208.967,SideCode.AgainstDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(377549.921,6673818.015,0.0), Point(377344.649,6673856.256,0.0)),FrozenLinkInterface,202221121,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(442470, 9.0,"7df77afa-9e93-4dab-875d-76bb78dbd076:1",0.0,43.21,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(375872.369,6673684.271,0.0), Point(375914.534,6673693.586,0.0)),FrozenLinkInterface,148694088,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(443115, 10.0,"f00554cf-34a6-4ace-88c8-40e7e0e65b29:1",0.0,42.73,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(375873.553,6673677.983,0.0), Point(375914.958,6673688.467,0.0)),FrozenLinkInterface,148600429,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(459062, 20.0,"c592bc13-dd95-42b2-b18d-ae9216f8b8e8:1",0.0,36.425,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(377822.436,6673835.723,0.0), Point(377851.59,6673857.539,0.0)),FrozenLinkInterface,202221121,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(468862, 14.0,"8472e943-ece3-467b-af26-dbcc49f2aa51:1",0.0,71.525,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(379474.881,6673768.386,0.0), Point(379523.688,6673820.097,0.0)),FrozenLinkInterface,262360669,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(459051, 9.0,"0f4b45a0-780d-4c3c-84fd-ac0ce57470b7:1",0.0,200.195,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(376857.816,6673713.617,0.0), Point(377045.18,6673784.066,0.0)),FrozenLinkInterface,202221121,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(443111, 6.0,"e8bd8466-8640-409b-a4ff-4b462174f667:1",0.0,14.378,SideCode.AgainstDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(375444.814,6673701.266,0.0), Point(375430.859,6673704.633,0.0)),FrozenLinkInterface,148600429,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(442735, 3.0,"3b5f4b46-d316-4195-bbdc-7dabc6dd3154:1",0.0,19.372,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(378138.229,6673994.022,0.0), Point(378157.513,6673995.84,0.0)),FrozenLinkInterface,148600417,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(456569, 2.0,"2c8a82fc-d65b-432e-be28-5d222a55ce2a:1",0.0,8.741,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(379558.543,6673886.082,0.0), Point(379560.077,6673894.545,0.0)),FrozenLinkInterface,190813336,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(442078, 3.0,"b65989ab-870e-424a-944b-3186633df7dc:1",0.0,131.918,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(376223.72,6673876.529,0.0), Point(376334.443,6673947.861,0.0)),FrozenLinkInterface,148694087,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(459047, 5.0,"86f13626-65f6-42ce-bda3-6256a1292e7c:1",0.0,125.465,SideCode.AgainstDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(376832.092,6673719.99,0.0), Point(376775.196,6673831.565,0.0)),FrozenLinkInterface,202221121,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(459054, 12.0,"a87c1701-ac2b-4d56-9e8e-ffdabd5c37cf:1",0.0,68.001,SideCode.AgainstDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(377344.649,6673856.256,0.0), Point(377277.675,6673866.485,0.0)),FrozenLinkInterface,202221121,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(440903, 2.0,"7a79e99e-98db-40b0-8e50-2329faf05591:1",0.0,232.647,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(378318.714,6674014.239,0.0), Point(378526.152,6674117.012,0.0)),FrozenLinkInterface,148694072,Some(DateTime.now().minusDays(2)),None)
      )

      val projectLinks = Seq(
        ProjectLink(Sequences.nextProjectLinkId, roundaboutRoadPart,Track.Combined,Discontinuity.Continuous,AddrMRange(0,15),AddrMRange(0,15),None,None,Some("test"),"7bf4b8b8-b8cd-49c8-b503-349131d5dd12:1", 0.0,15.267,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(379544.623,6673881.951,8.801), Point(379553.640999968,6673882.212999759,8.648), Point(379558.5429999678,6673886.08199976,8.594)),projectId,  RoadAddressChangeType.Termination,AdministrativeClass.Municipality, FrozenLinkInterface,15.267,79525,456568,1,reversed = false,None,1652179948783L,190813336,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roundaboutRoadPart,Track.Combined,Discontinuity.Continuous,AddrMRange(15,24),AddrMRange(15,24),None,None,Some("test"),"2c8a82fc-d65b-432e-be28-5d222a55ce2a:1", 0.0,8.741,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(379558.543,6673886.082,8.594), Point(379559.4179999678,6673887.508999759,8.581), Point(379560.0769999681,6673894.54499976,8.494)),projectId,  RoadAddressChangeType.Termination,AdministrativeClass.Municipality, FrozenLinkInterface,8.741,79525,456569,1,reversed = false,None,1652179948783L,190813336,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roundaboutRoadPart,Track.Combined,Discontinuity.Continuous,AddrMRange(24,33),AddrMRange(24,33),None,None,Some("test"),"4d1377d8-6490-423d-b98d-7e4d0d821af1:1", 0.0,9.54,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(379560.077,6673894.545,8.494), Point(379559.4239999681,6673896.560999759,8.436), Point(379554.07,6673901.7,8.41600093711505)),projectId,  RoadAddressChangeType.Termination,AdministrativeClass.Municipality, FrozenLinkInterface,9.54,79525,456570,1,reversed = false,None,1652179948783L,190813336,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roundaboutRoadPart,Track.Combined,Discontinuity.Continuous,AddrMRange(33,44),AddrMRange(33,44),None,None,Some("test"),"a94794ff-507a-4599-8428-bf08cdc8ef8a:1", 0.0,11.41,SideCode.AgainstDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(379542.943,6673900.897,8.659), Point(379544.646999968,6673901.914999761,8.612), Point(379554.07,6673901.7,8.416007915861456)),projectId,  RoadAddressChangeType.Termination,AdministrativeClass.Municipality, FrozenLinkInterface,11.41,79525,456571,1,reversed = false,None,1652179948783L,190813336,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roundaboutRoadPart,Track.Combined,Discontinuity.EndOfRoad,AddrMRange(44,67),AddrMRange(44,67),None,None,Some("test"),"64a055d4-d895-45e0-b5a9-4c4ef3d779d0:1", 0.0,22.417,SideCode.AgainstDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(379544.623,6673881.951,8.801), Point(379538.864999968,6673888.63699976,8.775), Point(379538.6839999679,6673894.200999758,8.777), Point(379539.293999968,6673896.13199976,8.764), Point(379542.943,6673900.897,8.659006540879798)),projectId,  RoadAddressChangeType.Termination,AdministrativeClass.Municipality, FrozenLinkInterface,22.417,79525,456572,1,reversed = false,None,1652179948783L,190813336,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.Combined,Discontinuity.Continuous,AddrMRange(0,0),AddrMRange(0,0),None,None,Some("test"),"2289d516-b1fc-4d60-bee2-cd8105d885c5:1", 0.0,228.227,SideCode.Unknown,(NoCP, NoCP), (NoCP, NoCP),List(Point(379724.606999968,6673774.643999765,9.212), Point(379687.9469999681,6673814.683999764,8.987), Point(379663.5069999681,6673839.903999763,8.714), Point(379642.186999968,6673854.203999763,8.493), Point(379613.0669999682,6673866.94299976,8.397), Point(379574.066999968,6673877.862999761,8.481), Point(379556.3869999681,6673882.28299976,8.611), Point(379543.9069999679,6673880.722999759,8.87), Point(379541.1769999682,6673871.0379997585,8.926)),projectId,  RoadAddressChangeType.New,AdministrativeClass.Municipality, FrozenLinkInterface,228.227,0,0,1,reversed = false,None,1652179948783L,0,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.Combined,Discontinuity.EndOfRoad,AddrMRange(0,0),AddrMRange(0,0),None,None,Some("test"),"37930924-b992-41af-8b89-6771f649c53e:1", 0.0,233.285,SideCode.Unknown,(NoCP, NoCP), (NoCP, NoCP),List(Point(379852.5119999681,6673563.000999768,2.387), Point(379852.006999968,6673564.824999768,2.446), Point(379836.1479999683,6673618.384999766,3.736), Point(379829.1269999682,6673643.994999766,4.312), Point(379819.7669999681,6673664.533999766,5.016), Point(379805.9879999679,6673685.334999766,5.885), Point(379793.766999968,6673699.373999766,6.584), Point(379766.207999968,6673731.223999767,8.242), Point(379737.0869999681,6673759.693999765,9.145)),projectId,  RoadAddressChangeType.New,AdministrativeClass.Municipality, FrozenLinkInterface,233.285,0,0,1,reversed = false,None,1652179948783L,0,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.RightSide,Discontinuity.Continuous,AddrMRange(0,136),AddrMRange(0,136),None,None,Some("test"),"9d397221-8daa-4e4d-b68b-739b207c7d6d:1", 0.0,149.578,SideCode.AgainstDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(375317.625,6673738.976,11.711), Point(375268.0999999666,6673753.147999629,13.472), Point(375217.7709999666,6673766.9549996285,13.472), Point(375172.7879999667,6673775.963999627,13.701)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,149.578,74690,443106,1,reversed = false,None,1652179948783L,148600429,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.LeftSide,Discontinuity.Continuous,AddrMRange(0,137),AddrMRange(0,137),None,None,Some("test"),"e62f960d-b440-435b-87c9-376b703b6ff8:1", 0.0,151.276,SideCode.AgainstDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(375315.646,6673746.872,12.177), Point(375269.5529999666,6673758.994999631,13.855), Point(375214.3829999668,6673773.928999626,13.855), Point(375169.2819999664,6673785.081999628,13.816)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,151.276,75100,442462,1,reversed = false,None,1652179948783L,148694088,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.RightSide,Discontinuity.Continuous,AddrMRange(136,161),AddrMRange(136,161),None,None,Some("test"),"577f1426-ea90-4bf9-9f9f-08286f07130a:1", 0.0,27.025,SideCode.AgainstDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(375343.054,6673729.825,12.37), Point(375317.625,6673738.976,11.71101092528875)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,27.025,74690,443107,1,reversed = false,None,1652179948783L,148600429,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.LeftSide,Discontinuity.Continuous,AddrMRange(137,166),AddrMRange(137,166),None,None,Some("test"),"deb53919-991d-4514-a6a9-c6b015bb042b:1", 0.0,31.329,SideCode.AgainstDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(375346.097,6673739.505,12.502), Point(375315.646,6673746.872,12.17700494173547)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,31.329,75100,442463,1,reversed = false,None,1652179948783L,148694088,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.RightSide,Discontinuity.Continuous,AddrMRange(161,174),AddrMRange(161,174),None,None,Some("test"),"0989c304-1b74-452f-bca4-5c9bc9e9f421:1", 0.0,14.918,SideCode.AgainstDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(375357.397,6673725.725,12.136), Point(375348.9819999665,6673728.165999633,12.24), Point(375343.0539999667,6673729.824999634,12.37)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,14.918,74690,443108,1,reversed = false,None,1652179948783L,148600429,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.LeftSide,Discontinuity.Continuous,AddrMRange(166,178),AddrMRange(166,178),None,None,Some("test"),"33d7f4e2-b056-4b36-89fd-611b0bfb5b7c:1", 0.0,13.778,SideCode.AgainstDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(375359.317,6673735.622,12.195), Point(375351.8229999665,6673737.823999634,12.428), Point(375346.097,6673739.505,12.50199423663293)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,13.778,75100,442464,1,reversed = false,None,1652179948783L,148694088,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.RightSide,Discontinuity.Continuous,AddrMRange(174,194),AddrMRange(174,194),None,None,Some("test"),"7d44abe8-394f-4ced-90bd-5ad061ccb714:1", 0.0,21.861,SideCode.AgainstDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(375378.39,6673719.626,11.221), Point(375357.397,6673725.725,12.135999493617252)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,21.861,74690,443109,1,reversed = false,None,1652179948783L,148600429,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.LeftSide,Discontinuity.Continuous,AddrMRange(178,198),AddrMRange(178,198),None,None,Some("test"),"28d12fe4-1c80-45a7-a35c-cfacede055a3:1", 0.0,22.026,SideCode.AgainstDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(375380.543,6673729.739,11.277), Point(375359.317,6673735.622,12.19499234700352)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,22.026,75100,442465,1,reversed = false,None,1652179948783L,148694088,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.RightSide,Discontinuity.Continuous,AddrMRange(194,244),AddrMRange(194,244),None,None,Some("test"),"6fad148a-80e2-4027-923a-27361670601a:1", 0.0,54.569,SideCode.AgainstDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(375430.859,6673704.633,10.999), Point(375378.39,6673719.626,11.220999617959357)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,54.569,74690,443110,1,reversed = false,None,1652179948783L,148600429,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.LeftSide,Discontinuity.Continuous,AddrMRange(198,254),AddrMRange(198,254),None,None,Some("test"),"b6417c65-4064-4193-a7a5-2e5bcc15fe43:1", 0.0,61.421,SideCode.AgainstDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(375439.361,6673712.059,10.98), Point(375409.0459999665,6673720.848999634,11.47), Point(375380.5429999667,6673729.738999634,11.277)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,61.421,75100,442466,1,reversed = false,None,1652179948783L,148694088,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.RightSide,Discontinuity.Continuous,AddrMRange(244,257),AddrMRange(244,257),None,None,Some("test"),"e8bd8466-8640-409b-a4ff-4b462174f667:1", 0.0,14.378,SideCode.AgainstDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(375444.814,6673701.266,10.787), Point(375438.5489999666,6673702.369999638,10.855), Point(375430.8589999665,6673704.632999636,10.999)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,14.378,74690,443111,1,reversed = false,None,1652179948783L,148600429,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.RightSide,Discontinuity.Continuous,AddrMRange(257,530),AddrMRange(257,530),None,None,Some("test"),"869cd1bf-1231-4ad9-b4df-9a7cf13bafb6:1", 0.0,301.349,SideCode.AgainstDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(375744.127,6673671.008,2.614), Point(375670.9229999667,6673673.307999643,4.509), Point(375608.0369999666,6673676.843999642,7.367), Point(375550.8939999668,6673683.922999639,8.724), Point(375510.1689999667,6673689.033999639,9.779), Point(375477.2119999667,6673694.626999637,10.401), Point(375444.814,6673701.266,10.786997867816396)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,301.349,74690,443112,1,reversed = false,None,1652179948783L,148600429,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.LeftSide,Discontinuity.Continuous,AddrMRange(254,532),AddrMRange(254,532),None,None,Some("test"),"38b3209c-e1c3-4dc0-a300-8f09a8d3ba71:1", 0.0,307.145,SideCode.AgainstDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(375743.458,6673678.333,2.625), Point(375684.0779999665,6673679.178999644,3.976), Point(375635.6999999666,6673680.3699996425,6.276), Point(375578.4199999666,6673685.031999641,8.154), Point(375537.6949999666,6673689.887999641,9.108), Point(375500.7939999667,6673696.385999638,9.983), Point(375469.2439999666,6673703.755999638,10.51), Point(375439.3609999665,6673712.058999635,10.98)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,307.145,75100,442467,1,reversed = false,None,1652179948783L,148694088,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.RightSide,Discontinuity.Continuous,AddrMRange(530,547),AddrMRange(530,547),None,None,Some("test"),"966d177a-6150-41da-8fa2-98b0c223f441:1", 0.0,19.06,SideCode.AgainstDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(375763.175,6673670.359,2.589), Point(375753.2239999666,6673670.810999648,2.666), Point(375744.127,6673671.008,2.6140022460837264)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,19.06,74690,443113,1,reversed = false,None,1652179948783L,148600429,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.LeftSide,Discontinuity.Continuous,AddrMRange(532,550),AddrMRange(532,550),None,None,Some("test"),"70f47ce7-e8a0-49d0-9abd-3af4d972355e:1", 0.0,19.87,SideCode.AgainstDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(375763.327,6673678.193,2.538), Point(375749.6639999667,6673678.344999647,2.62), Point(375743.4579999668,6673678.332999646,2.625)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,19.87,75100,442468,1,reversed = false,None,1652179948783L,148694088,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.RightSide,Discontinuity.Continuous,AddrMRange(547,648),AddrMRange(547,648),None,None,Some("test"),"1a2cbfb7-29d7-4a64-bd21-ead2eb5b6367:1", 0.0,110.737,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(375763.175,6673670.359,2.589), Point(375784.8609999666,6673670.456999649,2.361), Point(375812.8879999666,6673671.784999648,2.144), Point(375838.9979999666,6673673.8499996485,1.954), Point(375858.8429999667,6673676.21399965,1.986), Point(375873.553,6673677.983,1.9919998008062576)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,110.737,74690,443114,1,reversed = false,None,1652179948783L,148600429,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.LeftSide,Discontinuity.Continuous,AddrMRange(550,649),AddrMRange(550,649),None,None,Some("test"),"8b75c0c5-2a72-44c3-959c-b7fcdacea4ac:1", 0.0,109.394,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(375763.327,6673678.193,2.538), Point(375775.0169999667,6673678.058999647,2.523), Point(375802.7679999666,6673678.097999648,2.284), Point(375826.4299999667,6673678.756999649,2.005), Point(375853.2179999667,6673681.441999651,2.026), Point(375872.369,6673684.271,1.9600000619614184)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,109.394,75100,442469,1,reversed = false,None,1652179948783L,148694088,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.RightSide,Discontinuity.Continuous,AddrMRange(648,687),AddrMRange(648,687),None,None,Some("test"),"f00554cf-34a6-4ace-88c8-40e7e0e65b29:1", 0.0,42.73,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(375873.553,6673677.983,1.992), Point(375889.9139999666,6673681.59399965,1.886), Point(375904.3629999667,6673685.291999651,2.398), Point(375914.9579999668,6673688.466999653,2.608)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,42.73,74690,443115,1,reversed = false,None,1652179948783L,148600429,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.LeftSide,Discontinuity.Continuous,AddrMRange(649,688),AddrMRange(649,688),None,None,Some("test"),"7df77afa-9e93-4dab-875d-76bb78dbd076:1", 0.0,43.21,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(375872.369,6673684.271,1.96), Point(375888.1599999666,6673687.007999651,1.912), Point(375909.2049999667,6673692.121999653,2.524), Point(375914.534,6673693.586,2.580996603928839)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,43.21,75100,442470,1,reversed = false,None,1652179948783L,148694088,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.LeftSide,Discontinuity.Continuous,AddrMRange(688,695),AddrMRange(688,695),None,None,Some("test"),"c3f9058f-28e9-47f5-abd0-026a9a2c6d1a:1", 0.0,7.938,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(375914.534,6673693.586,2.581), Point(375922.4679999666,6673693.834999654,1.334)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,7.938,75100,442471,1,reversed = false,None,1652179948783L,148694088,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.RightSide,Discontinuity.Continuous,AddrMRange(687,695),AddrMRange(687,695),None,None,Some("test"),"ac3f145c-da7d-4404-add9-abf5fdc44acd:1", 0.0,9.231,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(375914.958,6673688.467,2.608), Point(375922.468,6673693.835,1.334031119594006)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,9.231,74690,443116,1,reversed = false,None,1652179948783L,148600429,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.Combined,Discontinuity.Continuous,AddrMRange(695,937),AddrMRange(695,937),None,None,Some("test"),"4d6d5d96-4ae2-41b1-8216-baec85be7a63:1", 0.0,262.108,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(375922.468,6673693.835,1.334), Point(375940.4379999666,6673698.6079996545,2.597), Point(375964.2389999668,6673707.474999655,2.822), Point(375989.9649999667,6673717.134999656,2.728), Point(376020.7889999668,6673732.772999655,2.742), Point(376053.5719999668,6673753.710999657,2.669), Point(376077.9349999667,6673772.4379996555,2.679), Point(376107.4469999668,6673795.08599966,2.313), Point(376127.8709999668,6673809.761999658,2.264), Point(376147.9179999668,6673823.12699966,2.356)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,262.108,75025,443595,1,reversed = false,None,1652179948783L,148600426,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.RightSide,Discontinuity.Continuous,AddrMRange(937,1019),AddrMRange(937,1019),None,None,Some("test"),"2528f8f8-cdbe-409a-ab90-626fd57350e4:1", 0.0,78.97,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(376147.918,6673823.127,2.356), Point(376158.6629999667,6673827.265999659,2.172), Point(376175.7609999668,6673837.64499966,2.499), Point(376195.7939999668,6673849.539999662,2.67), Point(376215.994,6673862.786,2.57600095970999)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,78.97,74889,441537,1,reversed = false,None,1652179948783L,148600423,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.LeftSide,Discontinuity.Continuous,AddrMRange(937,1020),AddrMRange(937,1020),None,None,Some("test"),"decaee8e-acb1-4bee-82e0-6dc1e10eb47a:1", 0.0,79.863,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(376147.918,6673823.127,2.356), Point(376156.6929999668,6673833.29399966,2.145), Point(376170.7359999668,6673843.810999659,2.299), Point(376187.9659999667,6673855.334999661,2.516), Point(376212.2129999668,6673869.65399966,2.641)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,79.863,75327,442076,1,reversed = false,None,1652179948783L,148694087,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.RightSide,Discontinuity.Continuous,AddrMRange(1019,1033),AddrMRange(1019,1033),None,None,Some("test"),"ddff02ce-16bd-4ad1-9e48-dd981cd5470e:1", 0.0,13.806,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(376215.994,6673862.786,2.576), Point(376227.5939999668,6673870.271999661,2.662)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,13.806,74889,441538,1,reversed = false,None,1652179948783L,148600423,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.LeftSide,Discontinuity.Continuous,AddrMRange(1020,1034),AddrMRange(1020,1034),None,None,Some("test"),"6017e415-28cf-4b5d-baf3-b9e63c3bb508:1", 0.0,13.404,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(376212.213,6673869.654,2.641), Point(376223.72,6673876.529,2.673999131442794)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,13.404,75327,442077,1,reversed = false,None,1652179948783L,148694087,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.RightSide,Discontinuity.Continuous,AddrMRange(1033,1170),AddrMRange(1033,1170),None,None,Some("test"),"fac1f380-66e0-4087-a420-12a0a25227e3:1", 0.0,132.393,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(376227.594,6673870.272,2.662), Point(376234.6639999667,6673874.834999662,2.703), Point(376257.6349999667,6673889.647999662,2.618), Point(376281.2459999668,6673905.222999666,2.475), Point(376304.8599999669,6673921.689999664,2.475), Point(376326.6519999669,6673939.510999666,2.538), Point(376334.4429999669,6673947.860999666,2.63)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,132.393,74889,441539,1,reversed = false,None,1652179948783L,148600423,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.LeftSide,Discontinuity.Continuous,AddrMRange(1034,1170),AddrMRange(1034,1170),None,None,Some("test"),"b65989ab-870e-424a-944b-3186633df7dc:1", 0.0,131.918,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(376223.72,6673876.529,2.674), Point(376247.423999967,6673893.621999663,2.549), Point(376271.0339999671,6673909.069999664,2.518), Point(376292.3489999668,6673923.506999665,2.425), Point(376319.4159999668,6673940.902999664,2.525), Point(376334.4429999669,6673947.860999666,2.63)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,131.918,75327,442078,1,reversed = false,None,1652179948783L,148694087,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.Combined,Discontinuity.Continuous,AddrMRange(1170,1482),AddrMRange(1170,1482),None,None,Some("test"),"2aabe2ea-7bb9-41b1-9256-9268470f6ec0:1", 0.0,309.606,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(376334.443,6673947.861,2.63), Point(376335.6339999669,6673948.695999666,2.627), Point(376373.6129999669,6673973.734999667,2.741), Point(376451.8489999668,6674023.896999669,3.131), Point(376501.7009999668,6674053.35099967,2.932), Point(376535.4549999669,6674068.665999674,2.836), Point(376562.2699999669,6674079.415999673,2.708), Point(376586.183999967,6674086.914999675,2.729), Point(376606.1179999669,6674092.869999674,2.911)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,309.606,75256,441509,1,reversed = false,None,1652179948783L,148600420,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.Combined,Discontinuity.MinorDiscontinuity,AddrMRange(1482,1494),AddrMRange(1482,1494),None,None,Some("test"),"0dbd6f8b-3fa8-4e9b-957e-943e7466f92d:1", 0.0,11.639,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(376606.118,6674092.87,2.911), Point(376610.390999967,6674094.105999674,3.084), Point(376617.3899999669,6674095.753999677,3.078)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,11.639,75256,441510,1,reversed = false,None,1652179948783L,148600420,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.Combined,Discontinuity.Continuous,AddrMRange(1494,1505),AddrMRange(1494,1505),None,None,Some("test"),"0004883e-396d-415d-8e1d-8cec36970586:1", 0.0,11.371,SideCode.AgainstDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(376648.988,6674084.332,3.391), Point(376641.4079999669,6674092.807999676,3.333)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,11.371,81391,459043,1,reversed = false,None,1652179948783L,202221121,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.Combined,Discontinuity.Continuous,AddrMRange(1505,1730),AddrMRange(1505,1730),None,None,Some("test"),"d226a98d-0ddd-469a-bbcd-4256de7b59f3:1", 0.0,222.288,SideCode.AgainstDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(376751.346,6673888.271,2.858), Point(376744.4909999668,6673901.619999677,2.749), Point(376740.532999967,6673909.2489996785,2.71), Point(376728.5799999669,6673933.652999677,2.686), Point(376720.0599999669,6673951.303999678,2.742), Point(376710.6029999668,6673972.035999677,2.717), Point(376692.8609999669,6674012.940999676,2.77), Point(376682.1209999669,6674040.165999677,2.54), Point(376670.450999967,6674057.540999676,2.768), Point(376654.3739999669,6674078.285999675,3.303), Point(376648.988,6674084.332,3.390995524996455)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,222.288,81391,459044,1,reversed = false,None,1652179948783L,202221121,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.Combined,Discontinuity.Continuous,AddrMRange(1730,1773),AddrMRange(1730,1773),None,None,Some("test"),"88e82ce2-5228-42af-bb19-d7c23e54537f:1", 0.0,42.668,SideCode.AgainstDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(376767.442,6673848.758,3.051), Point(376760.2359999668,6673865.924999679,2.955), Point(376751.3459999669,6673888.270999679,2.858)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,42.668,81391,459045,1,reversed = false,None,1652179948783L,202221121,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.Combined,Discontinuity.Continuous,AddrMRange(1773,1792),AddrMRange(1773,1792),None,None,Some("test"),"86e7008c-3941-4a48-b1b7-93967aa2a8bb:1", 0.0,18.867,SideCode.AgainstDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(376775.196,6673831.565,3.205), Point(376771.1869999669,6673839.842999677,3.165), Point(376767.442,6673848.758,3.0510039934885973)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,18.867,81391,459046,1,reversed = false,None,1652179948783L,202221121,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.Combined,Discontinuity.Continuous,AddrMRange(1792,1919),AddrMRange(1792,1919),None,None,Some("test"),"86f13626-65f6-42ce-bda3-6256a1292e7c:1", 0.0,125.465,SideCode.AgainstDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(376832.092,6673719.99,5.29), Point(376821.299999967,6673743.439999681,4.769), Point(376811.924999967,6673762.6249996815,4.337), Point(376804.7699999669,6673773.89599968,4.085), Point(376796.6039999668,6673786.066999679,3.929), Point(376786.494999967,6673808.569999679,3.553), Point(376775.195999967,6673831.564999681,3.205)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,125.465,81391,459047,1,reversed = false,None,1652179948783L,202221121,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.Combined,Discontinuity.Continuous,AddrMRange(1919,1935),AddrMRange(1919,1935),None,None,Some("test"),"d51360af-aaaa-4931-8ab1-43f16e7fb094:1", 0.0,16.279,SideCode.AgainstDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(376840.287,6673705.924,5.421), Point(376832.092,6673719.99,5.290001122129232)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,16.279,81391,459048,1,reversed = false,None,1652179948783L,202221121,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.Combined,Discontinuity.Continuous,AddrMRange(1935,1943),AddrMRange(1935,1943),None,None,Some("test"),"edfd9a80-6ffa-4d98-9651-728fb706aa5d:1", 0.0,8.047,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(376840.287,6673705.924,5.421), Point(376847.6619999669,6673709.142999683,5.333)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,8.047,81391,459049,1,reversed = false,None,1652179948783L,202221121,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.Combined,Discontinuity.Continuous,AddrMRange(1943,1954),AddrMRange(1943,1954),None,None,Some("test"),"e29625e0-cec8-4087-a0cf-e8c14a97ca01:1", 0.0,11.096,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(376847.662,6673709.143,5.333), Point(376857.815999967,6673713.616999682,5.077)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,11.096,81391,459050,1,reversed = false,None,1652179948783L,202221121,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.Combined,Discontinuity.Continuous,AddrMRange(1954,2156),AddrMRange(1954,2156),None,None,Some("test"),"0f4b45a0-780d-4c3c-84fd-ac0ce57470b7:1", 0.0,200.195,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(376857.816,6673713.617,5.077), Point(376859.5619999669,6673714.348999683,5.037), Point(376887.949999967,6673726.000999684,3.962), Point(376889.152999967,6673726.494999684,3.923), Point(376922.884999967,6673739.377999684,3.501), Point(376992.385999967,6673765.187999685,3.614), Point(377045.1799999672,6673784.065999689,4.187)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,200.195,81391,459051,1,reversed = false,None,1652179948783L,202221121,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.Combined,Discontinuity.Continuous,AddrMRange(2156,2387),AddrMRange(2156,2387),None,None,Some("test"),"9cc9a84b-55ae-4873-b44c-2d40d2e8c6f6:1", 0.0,228.751,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(377045.18,6673784.066,4.187), Point(377072.2309999673,6673794.906999689,4.833), Point(377113.4749999672,6673809.931999692,6.251), Point(377163.5689999671,6673828.41899969,8.145), Point(377204.812999967,6673843.444999694,9.74), Point(377236.2659999673,6673855.534999695,11.051), Point(377259.8979999671,6673862.801999695,11.787)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,228.751,81391,459052,1,reversed = false,None,1652179948783L,202221121,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.Combined,Discontinuity.Continuous,AddrMRange(2387,2406),AddrMRange(2387,2406),None,None,Some("test"),"1a758226-5dd8-4ede-977e-032557ab195b:1", 0.0,18.234,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(377259.898,6673862.802,11.787), Point(377268.5659999671,6673865.463999694,11.967), Point(377277.674999967,6673866.484999695,12.036)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,18.234,81391,459053,1,reversed = false,None,1652179948783L,202221121,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.Combined,Discontinuity.Continuous,AddrMRange(2406,2474),AddrMRange(2406,2474),None,None,Some("test"),"a87c1701-ac2b-4d56-9e8e-ffdabd5c37cf:1", 0.0,68.001,SideCode.AgainstDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(377344.649,6673856.256,11.349), Point(377320.7869999672,6673861.290999697,11.57), Point(377296.0019999671,6673865.844999696,11.912), Point(377288.3729999672,6673866.931999694,12.018), Point(377277.674999967,6673866.484999695,12.036)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,68.001,81391,459054,1,reversed = false,None,1652179948783L,202221121,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.Combined,Discontinuity.Continuous,AddrMRange(2474,2685),AddrMRange(2474,2685),None,None,Some("test"),"174d9f43-5a10-4adb-acda-abc3ba2770a4:1", 0.0,208.967,SideCode.AgainstDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(377549.921,6673818.015,8.658), Point(377536.6849999671,6673819.955999704,9.129), Point(377513.9459999672,6673822.414999702,9.795), Point(377486.5879999671,6673826.4479997,10.641), Point(377456.5819999671,6673831.926999702,11.276), Point(377433.8669999672,6673836.315999699,11.423), Point(377418.6909999672,6673840.147999699,11.286), Point(377401.2069999671,6673843.9449996995,11.33), Point(377388.7089999671,6673846.6439996995,11.219), Point(377354.0129999671,6673854.186999697,11.299), Point(377350.035999967,6673855.013999697,11.314), Point(377344.6489999671,6673856.255999697,11.349)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,208.967,81391,459055,1,reversed = false,None,1652179948783L,202221121,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.Combined,Discontinuity.Continuous,AddrMRange(2685,2729),AddrMRange(2685,2729),None,None,Some("test"),"ccd72deb-d1df-4740-81ff-3e0849f28d2f:1", 0.0,43.215,SideCode.AgainstDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(377592.869,6673813.466,7.823), Point(377580.5809999674,6673814.125999706,8.1), Point(377574.1429999671,6673815.055999704,8.131), Point(377560.2279999673,6673816.579999705,8.427), Point(377549.921,6673818.015,8.65799647898719)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,43.215,81391,459056,1,reversed = false,None,1652179948783L,202221121,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.Combined,Discontinuity.Continuous,AddrMRange(2729,2740),AddrMRange(2729,2740),None,None,Some("test"),"8fbb7ab3-763e-49bf-ae96-cae737f8d322:1", 0.0,10.514,SideCode.AgainstDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(377603.362,6673812.81,7.754), Point(377598.4429999671,6673813.137999704,7.794), Point(377592.8689999671,6673813.465999705,7.823)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,10.514,81391,459057,1,reversed = false,None,1652179948783L,202221121,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.Combined,Discontinuity.Continuous,AddrMRange(2740,2808),AddrMRange(2740,2808),None,None,Some("test"),"5211f99d-323c-4396-a6b6-169683e6511c:1", 0.0,67.866,SideCode.AgainstDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(377671.068,6673808.319,7.68), Point(377668.2859999672,6673808.384999707,7.712), Point(377638.9379999671,6673810.678999704,7.77), Point(377617.1329999672,6673811.6629997045,7.744), Point(377603.362,6673812.81,7.753999871063766)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,67.866,81391,459058,1,reversed = false,None,1652179948783L,202221121,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.Combined,Discontinuity.Continuous,AddrMRange(2808,2821),AddrMRange(2808,2821),None,None,Some("test"),"ac7b6f6a-1011-46ec-90fd-9ba9367656f5:1", 0.0,12.52,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(377671.068,6673808.319,7.68), Point(377683.588,6673808.371,7.63400039675224)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,12.52,81391,459059,1,reversed = false,None,1652179948783L,202221121,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.Combined,Discontinuity.Continuous,AddrMRange(2821,2848),AddrMRange(2821,2848),None,None,Some("test"),"9f3399d8-79d6-4df9-8906-7110c5459384:1", 0.0,26.701,SideCode.AgainstDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(377710.178,6673805.939,7.341), Point(377695.0089999673,6673807.400999708,7.537), Point(377683.588,6673808.371,7.633996537627427)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,26.701,81391,459060,1,reversed = false,None,1652179948783L,202221121,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.Combined,Discontinuity.Continuous,AddrMRange(2848,2968),AddrMRange(2848,2968),None,None,Some("test"),"3756dad4-faeb-4645-b922-0d60cef7cbdb:1", 0.0,119.207,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(377710.178,6673805.939,7.341), Point(377721.6049999672,6673805.2959997095,7.215), Point(377737.4719999671,6673804.941999711,7.077), Point(377750.5869999672,6673806.25399971,6.967), Point(377763.8679999672,6673808.056999709,6.892), Point(377774.8109999672,6673810.46699971,6.826), Point(377787.5199999673,6673815.03899971,6.706), Point(377805.6899999672,6673825.18999971,6.554), Point(377822.436,6673835.723,6.392002557309743)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,119.207,81391,459061,1,reversed = false,None,1652179948783L,202221121,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.Combined,Discontinuity.Continuous,AddrMRange(2968,3005),AddrMRange(2968,3005),None,None,Some("test"),"c592bc13-dd95-42b2-b18d-ae9216f8b8e8:1", 0.0,36.425,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(377822.436,6673835.723,6.392), Point(377842.4239999673,6673850.123999711,6.21), Point(377851.59,6673857.539,6.164000991152671)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,36.425,81391,459062,1,reversed = false,None,1652179948783L,202221121,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.Combined,Discontinuity.Continuous,AddrMRange(3005,3021),AddrMRange(3005,3021),None,None,Some("test"),"5df1b25a-1175-46c7-b89c-2cda75dec8b2:1", 0.0,15.423,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(377851.59,6673857.539,6.164), Point(377863.9879999673,6673866.711999712,6.005)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,15.423,81391,459063,1,reversed = false,None,1652179948783L,202221121,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.Combined,Discontinuity.Continuous,AddrMRange(3021,3161),AddrMRange(3021,3161),None,None,Some("test"),"ae626bf0-f18f-4b7b-b18f-26692ae324c6:1", 0.0,139.402,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(377863.988,6673866.712,6.005), Point(377879.9919999673,6673877.048999714,5.598), Point(377913.1469999673,6673900.193999713,4.527), Point(377942.2479999673,6673919.590999715,3.786), Point(377979.7879999673,6673944.299999715,3.383)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,139.402,81391,459064,1,reversed = false,None,1652179948783L,202221121,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.RightSide,Discontinuity.Continuous,AddrMRange(3161,3226),AddrMRange(3161,3226),None,None,Some("test"),"3a8526e1-aecb-45e1-9299-f6d5eb8a8eb4:1", 0.0,64.218,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(377979.788,6673944.3,3.383), Point(377988.4309999674,6673946.705999716,3.338), Point(378017.7289999674,6673960.555999715,3.665), Point(378038.7699999673,6673969.439999718,3.843)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,64.218,74886,442733,1,reversed = false,None,1652179948783L,148600417,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.LeftSide,Discontinuity.Continuous,AddrMRange(3161,3226),AddrMRange(3161,3226),None,None,Some("test"),"b424932d-201f-4bae-9df1-294fbfa1ce65:1", 0.0,64.382,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(377979.788,6673944.3,3.383), Point(377990.8669999674,6673952.039999717,3.422), Point(378015.8339999673,6673965.130999717,3.689), Point(378036.4689999673,6673974.532999718,3.82)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,64.382,75072,443448,1,reversed = false,None,1652179948783L,148694086,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.RightSide,Discontinuity.Continuous,AddrMRange(3226,3330),AddrMRange(3226,3330),None,None,Some("test"),"ad9de2c1-cc33-470e-b9f2-4bc2031d82f5:1", 0.0,102.635,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(378038.77,6673969.44,3.843), Point(378071.1709999673,6673980.121999718,3.967), Point(378104.9179999676,6673988.2379997205,3.85), Point(378138.229,6673994.022,3.644000350623236)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,102.635,74886,442734,1,reversed = false,None,1652179948783L,148600417,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.LeftSide,Discontinuity.Continuous,AddrMRange(3226,3332),AddrMRange(3226,3332),None,None,Some("test"),"46a2d4ef-104f-4341-bfa2-15329e219be2:1", 0.0,104.264,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(378036.469,6673974.533,3.82), Point(378059.6659999673,6673983.042999717,3.959), Point(378085.7729999674,6673989.89299972,3.959), Point(378116.3369999673,6673996.610999719,3.889), Point(378137.179,6674000.863,3.6280040759410284)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,104.264,75072,443449,1,reversed = false,None,1652179948783L,148694086,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.RightSide,Discontinuity.Continuous,AddrMRange(3330,3350),AddrMRange(3330,3350),None,None,Some("test"),"3b5f4b46-d316-4195-bbdc-7dabc6dd3154:1", 0.0,19.372,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(378138.229,6673994.022,3.644), Point(378156.0329999674,6673995.779999721,3.462), Point(378157.5129999674,6673995.8399997195,3.447)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,19.372,74886,442735,1,reversed = false,None,1652179948783L,148600417,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.LeftSide,Discontinuity.Continuous,AddrMRange(3332,3352),AddrMRange(3332,3352),None,None,Some("test"),"36f5cafc-b5b2-4bf4-81e5-a3e7568697ae:1", 0.0,19.899,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(378137.179,6674000.863,3.628), Point(378147.5079999674,6674002.665999722,3.518), Point(378156.852,6674003.814,3.463002568667983)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,19.899,75072,443450,1,reversed = false,None,1652179948783L,148694086,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.RightSide,Discontinuity.Continuous,AddrMRange(3350,3366),AddrMRange(3350,3366),None,None,Some("test"),"efe2fdae-d98e-4bb1-b6ba-94e93f0e9bea:1", 0.0,15.366,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(378157.513,6673995.84,3.447), Point(378172.867,6673996.456,3.390001305494978)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,15.366,74886,442736,1,reversed = false,None,1652179948783L,148600417,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.LeftSide,Discontinuity.Continuous,AddrMRange(3352,3367),AddrMRange(3352,3367),None,None,Some("test"),"86be0fd6-8e59-48e4-9bd2-2994d055e059:1", 0.0,15.106,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(378156.852,6674003.814,3.463), Point(378171.936,6674004.633,3.370001341024409)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,15.106,75072,443451,1,reversed = false,None,1652179948783L,148694086,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.LeftSide,Discontinuity.Continuous,AddrMRange(3367,3400),AddrMRange(3367,3400),None,None,Some("test"),"1024f118-3b80-46c1-a2d0-d48769aa648f:1", 0.0,31.822,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(378171.936,6674004.633,3.37), Point(378197.8409999674,6674006.15699972,3.279), Point(378203.705,6674006.463,3.2451626891966874)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,31.822,75072,443452,1,reversed = false,None,1652179948783L,148694086,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.RightSide,Discontinuity.Continuous,AddrMRange(3366,3400),AddrMRange(3366,3400),None,None,Some("test"),"a123a3e5-d3d3-4ab6-a6bf-f7bfcc08f381:1", 0.0,34.055,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(378172.867,6673996.456,3.39), Point(378181.5439999675,6673997.291999723,3.413), Point(378186.5689999675,6673997.528999723,3.389), Point(378206.827,6673998.946,3.281085359486521)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,34.055,74886,442737,1,reversed = false,None,1652179948783L,148600417,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.RightSide,Discontinuity.Continuous,AddrMRange(3400,3481),AddrMRange(3400,3481),None,None,Some("test"),"a123a3e5-d3d3-4ab6-a6bf-f7bfcc08f381:1", 34.055,112.866,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(378206.827,6673998.946,3.281085359486521), Point(378246.2639999675,6674001.703999724,3.071), Point(378283.0459999675,6674003.401999725,2.876), Point(378285.496,6674003.583,2.8393355047559496)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,78.811,75007,440160,1,reversed = false,None,1652179948783L,148600384,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.LeftSide,Discontinuity.Continuous,AddrMRange(3400,3481),AddrMRange(3400,3481),None,None,Some("test"),"1024f118-3b80-46c1-a2d0-d48769aa648f:1", 31.822,112.37,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(378203.705,6674006.463,3.2451626891966874), Point(378245.8469999675,6674008.660999726,3.002), Point(378273.3989999673,6674009.635999723,3.053), Point(378284.163,6674010.211,2.918364048039759)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,80.548,76524,447590,1,reversed = false,None,1652179948783L,168748574,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.LeftSide,Discontinuity.Continuous,AddrMRange(3481,3516),AddrMRange(3481,3516),None,None,Some("test"),"1024f118-3b80-46c1-a2d0-d48769aa648f:1", 112.37,147.175,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(378284.163,6674010.211,2.918364048039759), Point(378291.6279999675,6674010.608999724,2.825), Point(378306.6559999676,6674012.488999725,2.576), Point(378318.7139999676,6674014.238999725,2.432)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,34.805,74576,440902,1,reversed = false,None,1652179948783L,148694072,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.RightSide,Discontinuity.Continuous,AddrMRange(3481,3516),AddrMRange(3481,3516),None,None,Some("test"),"a123a3e5-d3d3-4ab6-a6bf-f7bfcc08f381:1", 112.866,146.921,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(378285.496,6674003.583,2.8393355047559496), Point(378319.4579999674,6674006.086999725,2.331)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,34.055,75119,440721,1,reversed = false,None,1652179948783L,148600207,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.LeftSide,Discontinuity.Continuous,AddrMRange(3516,3751),AddrMRange(3516,3751),None,None,Some("test"),"7a79e99e-98db-40b0-8e50-2329faf05591:1", 0.0,232.647,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(378318.714,6674014.239,2.432), Point(378326.9709999674,6674015.827999725,2.307), Point(378355.1829999674,6674023.839999728,1.942), Point(378368.6849999675,6674029.683999727,2.039), Point(378387.5359999676,6674038.069999726,2.06), Point(378429.7959999676,6674060.310999727,2.045), Point(378464.3199999676,6674080.39599973,2.301), Point(378499.7359999676,6674102.007999732,2.183), Point(378526.1519999675,6674117.011999731,1.649)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,232.647,74576,440903,1,reversed = false,None,1652179948783L,148694072,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.RightSide,Discontinuity.Continuous,AddrMRange(3516,3755),AddrMRange(3516,3755),None,None,Some("test"),"6ba652f1-0284-4d0d-86a2-52778764e724:1", 0.0,235.761,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(378319.458,6674006.087,2.331), Point(378336.1559999674,6674009.426999726,2.124), Point(378353.4099999675,6674014.435999728,1.935), Point(378372.3349999675,6674022.2289997265,1.934), Point(378390.9809999675,6674030.856999727,2.09), Point(378415.6739999678,6674042.760999728,1.947), Point(378447.3949999676,6674059.664999729,2.004), Point(378474.0189999674,6674074.409999731,2.156), Point(378505.3579999677,6674092.588999729,2.063), Point(378531.0329999675,6674107.58299973,1.544)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,235.761,75119,440722,1,reversed = false,None,1652179948783L,148600207,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.LeftSide,Discontinuity.Continuous,AddrMRange(3751,3765),AddrMRange(3751,3765),None,None,Some("test"),"5a1f4f77-5b1f-412a-9a4d-83a98ba93558:1", 0.0,14.002,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(378526.152,6674117.012,1.649), Point(378537.3099999675,6674125.47099973,1.823)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,14.002,74576,440904,1,reversed = false,None,1652179948783L,148694072,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.RightSide,Discontinuity.Continuous,AddrMRange(3755,3769),AddrMRange(3755,3769),None,None,Some("test"),"0455a8e1-1711-4d17-bd5c-aa0b9525e95b:1", 0.0,13.822,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(378531.033,6674107.583,1.544), Point(378543.058,6674114.399,1.8109924555864858)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,13.822,75119,440723,1,reversed = false,None,1652179948783L,148600207,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.LeftSide,Discontinuity.Continuous,AddrMRange(3765,3781),AddrMRange(3765,3781),None,None,Some("test"),"e96810cb-5ea7-4b20-ac61-17d2fe4a9202:1", 0.0,15.742,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(378537.31,6674125.471,1.823), Point(378544.4529999675,6674129.850999733,1.717), Point(378550.1639999677,6674134.497999732,1.405)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,15.742,74576,440905,1,reversed = false,None,1652179948783L,148694072,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.RightSide,Discontinuity.Continuous,AddrMRange(3769,3783),AddrMRange(3769,3783),None,None,Some("test"),"39609beb-f80e-46d2-b1a0-2645a8cf0836:1", 0.0,14.725,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(378543.058,6674114.399,1.811), Point(378549.2619999675,6674119.083999732,1.733), Point(378555.2499999677,6674122.612999732,1.412)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,14.725,75119,440724,1,reversed = false,None,1652179948783L,148600207,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.LeftSide,Discontinuity.Continuous,AddrMRange(3781,3915),AddrMRange(3781,3915),None,None,Some("test"),"ba55aa47-aff5-4f34-84ea-d1e348ac34c0:1", 0.0,134.131,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(378550.164,6674134.498,1.405), Point(378567.5819999676,6674143.003999733,1.401), Point(378587.7079999675,6674153.043999733,1.379), Point(378609.9299999677,6674164.548999733,1.388), Point(378631.5839999676,6674173.1869997345,1.409), Point(378647.8269999675,6674176.413999734,1.405), Point(378666.1619999677,6674177.540999736,1.428), Point(378674.337,6674173.803,1.458998861967869)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,134.131,74576,440906,1,reversed = false,None,1652179948783L,148694072,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.RightSide,Discontinuity.Continuous,AddrMRange(3783,3915),AddrMRange(3783,3915),None,None,Some("test"),"3c89c56b-fa39-4968-85d4-d79ddb8250e4:1", 0.0,130.751,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(378555.25,6674122.613,1.412), Point(378588.5759999675,6674142.159999734,1.379), Point(378610.7409999675,6674153.343999733,1.385), Point(378628.7009999675,6674160.966999735,1.367), Point(378647.2949999676,6674166.805999735,1.42), Point(378662.0659999676,6674168.318999734,1.441), Point(378674.337,6674173.803,1.4589995021162676)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,130.751,75119,440725,1,reversed = false,None,1652179948783L,148600207,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.Combined,Discontinuity.Continuous,AddrMRange(3915,4150),AddrMRange(3915,4150),None,None,Some("test"),"8e765acc-fb6a-4a1c-beef-9d98221a3bad:1", 0.0,236.088,SideCode.AgainstDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(378820.527,6673989.657,1.689), Point(378815.8839999677,6673996.0189997405,1.538), Point(378805.9909999675,6674009.562999739,1.388), Point(378783.2529999677,6674040.525999738,1.374), Point(378761.2269999676,6674070.152999738,1.474), Point(378726.1559999676,6674113.716999739,1.281), Point(378720.4429999676,6674121.468999738,1.34), Point(378703.0999999676,6674147.328999736,1.295), Point(378689.5589999676,6674163.147999735,1.344), Point(378681.7769999675,6674168.966999736,1.367), Point(378674.337,6674173.803,1.458996977588795)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,236.088,85053,468849,1,reversed = false,None,1652179948783L,262360669,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.Combined,Discontinuity.Continuous,AddrMRange(4150,4213),AddrMRange(4150,4213),None,None,Some("test"),"0ab12b4f-8dc8-45aa-abca-b422be549d93:1", 0.0,63.408,SideCode.AgainstDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(378857.421,6673938.123,3.21), Point(378854.9819999676,6673942.278999743,3.075), Point(378846.0469999676,6673955.23999974,2.732), Point(378833.4779999676,6673971.96199974,2.171), Point(378820.527,6673989.657,1.689006300873586)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,63.408,85053,468850,1,reversed = false,None,1652179948783L,262360669,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.Combined,Discontinuity.Continuous,AddrMRange(4213,4223),AddrMRange(4213,4223),None,None,Some("test"),"b9dc0e76-8b9a-4b10-99f5-1e43c1da3f16:1", 0.0,9.639,SideCode.AgainstDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(378862.146,6673929.721,3.516), Point(378857.421,6673938.123,3.210014668182489)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,9.639,85053,468851,1,reversed = false,None,1652179948783L,262360669,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.Combined,Discontinuity.Continuous,AddrMRange(4223,4307),AddrMRange(4223,4307),None,None,Some("test"),"cebec33d-35fb-4a88-8548-9f746ec34d7b:1", 0.0,84.984,SideCode.AgainstDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(378885.698,6673848.073,7.366), Point(378877.5949999679,6673876.750999742,5.803), Point(378871.9689999677,6673895.752999741,4.825), Point(378868.3789999677,6673908.08299974,4.299), Point(378865.8959999677,6673917.645999741,3.943), Point(378862.146,6673929.721,3.5160044573976155)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,84.984,85053,468852,1,reversed = false,None,1652179948783L,262360669,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.Combined,Discontinuity.Continuous,AddrMRange(4307,4360),AddrMRange(4307,4360),None,None,Some("test"),"49706b34-ff08-4616-b338-a6857f1b513e:1", 0.0,52.792,SideCode.AgainstDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(378901.051,6673797.573,10.737), Point(378900.5759999677,6673799.182999742,10.611), Point(378899.9079999676,6673801.467999741,10.439), Point(378889.5549999676,6673836.846999743,8.102), Point(378885.698,6673848.073,7.3660033262016436)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,52.792,85053,468853,1,reversed = false,None,1652179948783L,262360669,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.Combined,Discontinuity.Continuous,AddrMRange(4360,4442),AddrMRange(4360,4442),None,None,Some("test"),"1411d3ad-17eb-46c3-a2f0-66aa5104d46d:1", 0.0,82.849,SideCode.AgainstDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(378933.977,6673721.963,16.015), Point(378928.2649999677,6673730.2869997425,15.407), Point(378917.8829999677,6673749.981999742,13.985), Point(378910.3049999675,6673769.9229997415,12.686), Point(378901.051,6673797.573,10.737002406260466)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,82.849,85053,468854,1,reversed = false,None,1652179948783L,262360669,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.Combined,Discontinuity.Continuous,AddrMRange(4442,4589),AddrMRange(4442,4589),None,None,Some("test"),"85294d1e-5d8d-4d33-b265-306a5152cc9f:1", 0.0,147.564,SideCode.AgainstDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(379059.529,6673653.855,21.46), Point(379054.7349999676,6673653.943999748,21.52), Point(379044.8539999677,6673654.7089997465,21.613), Point(379039.8669999676,6673655.625999746,21.593), Point(379029.4349999677,6673658.277999747,21.444), Point(379017.9339999677,6673661.468999745,21.219), Point(379009.2329999677,6673664.254999744,20.941), Point(378991.3489999676,6673672.587999745,20.264), Point(378973.8529999676,6673683.743999744,19.276), Point(378970.3179999679,6673686.182999746,19.038), Point(378956.8799999677,6673696.853999743,18.074), Point(378937.4629999678,6673717.3159997435,16.367), Point(378936.4749999677,6673718.631999743,16.268), Point(378933.9769999676,6673721.962999743,16.015)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,147.564,85053,468855,1,reversed = false,None,1652179948783L,262360669,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.Combined,Discontinuity.Continuous,AddrMRange(4589,4670),AddrMRange(4589,4670),None,None,Some("test"),"162052ae-b9ad-49ef-8a0a-4f57e7074022:1", 0.0,81.397,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(379059.529,6673653.855,21.46), Point(379062.9119999676,6673653.832999746,21.397), Point(379090.8949999677,6673654.374999747,20.936), Point(379116.5379999677,6673654.794999747,20.568), Point(379135.6399999677,6673654.418999747,20.404), Point(379138.547999968,6673654.388999749,20.417), Point(379140.9129999677,6673654.367999747,20.405)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,81.397,85053,468856,1,reversed = false,None,1652179948783L,262360669,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.Combined,Discontinuity.Continuous,AddrMRange(4670,4761),AddrMRange(4670,4761),None,None,Some("test"),"7f7e5213-0d20-4ca1-a733-62fe7ea501ea:1", 0.0,91.853,SideCode.AgainstDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(379231.885,6673644.355,18.417), Point(379224.792999968,6673645.992999751,18.639), Point(379208.4829999678,6673649.96899975,19.111), Point(379179.9649999677,6673653.319999749,19.877), Point(379162.8659999676,6673654.140999751,20.202), Point(379140.913,6673654.368,20.404996177422493)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,91.853,85053,468857,1,reversed = false,None,1652179948783L,262360669,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.Combined,Discontinuity.Continuous,AddrMRange(4761,4836),AddrMRange(4761,4836),None,None,Some("test"),"3cfded00-72a7-437d-8e1c-c52c1d58dfc3:1", 0.0,74.97,SideCode.AgainstDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(379299.237,6673611.767,15.9), Point(379293.4469999678,6673615.561999752,16.072), Point(379281.0799999678,6673621.734999753,16.543), Point(379259.3859999678,6673632.928999752,17.385), Point(379244.5059999678,6673639.869999751,17.944), Point(379231.885,6673644.355,18.41699461322266)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,74.97,85053,468858,1,reversed = false,None,1652179948783L,262360669,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.Combined,Discontinuity.Continuous,AddrMRange(4836,4849),AddrMRange(4836,4849),None,None,Some("test"),"58c2ea47-2c12-43a3-a0dd-00dccc5c5424:1", 0.0,12.978,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(379299.237,6673611.767,15.9), Point(379306.3029999677,6673619.354999754,15.919), Point(379308.054,6673621.29,15.808006627428504)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,12.978,85053,468859,1,reversed = false,None,1652179948783L,262360669,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.Combined,Discontinuity.Continuous,AddrMRange(4849,4940),AddrMRange(4849,4940),None,None,Some("test"),"d16b12fd-2902-4c00-8b97-6dc437692805:1", 0.0,91.195,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(379308.054,6673621.29,15.808), Point(379318.269999968,6673632.493999753,15.677), Point(379334.8339999679,6673647.372999753,15.408), Point(379351.7019999678,6673661.633999755,15.001), Point(379375.757,6673682.247,14.336001171298358)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,91.195,85053,468860,1,reversed = false,None,1652179948783L,262360669,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.Combined,Discontinuity.Continuous,AddrMRange(4940,5072),AddrMRange(4940,5072),None,None,Some("test"),"5ffffa6e-3448-47d9-8791-4afdb5e0e3c3:1", 0.0,131.363,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(379375.757,6673682.247,14.336), Point(379384.3039999678,6673689.550999756,14.052), Point(379397.9259999678,6673702.341999757,13.69), Point(379408.2939999679,6673711.505999757,13.33), Point(379424.4639999678,6673725.824999756,12.957), Point(379445.787999968,6673744.693999756,12.326), Point(379461.9349999678,6673757.442999756,11.867), Point(379474.881,6673768.386,11.516006468694034)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,131.363,85053,468861,1,reversed = false,None,1652179948783L,262360669,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.Combined,Discontinuity.Continuous,AddrMRange(5072,5144),AddrMRange(5072,5144),None,None,Some("test"),"8472e943-ece3-467b-af26-dbcc49f2aa51:1", 0.0,71.525,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(379474.881,6673768.386,11.516), Point(379479.4679999681,6673772.125999757,11.362), Point(379489.9739999679,6673781.464999759,11.049), Point(379510.2549999678,6673800.927999757,10.392), Point(379520.078999968,6673813.946999758,10.021), Point(379523.687999968,6673820.09699976,9.898)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,71.525,85053,468862,1,reversed = false,None,1652179948783L,262360669,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.Combined,Discontinuity.Continuous,AddrMRange(5144,5198),AddrMRange(5144,5198),None,None,Some("test"),"c165f16e-1204-4a20-b098-72c681269d36:1", 0.0,53.949,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(379523.688,6673820.097,9.898), Point(379529.3289999679,6673832.42099976,9.587), Point(379535.9869999679,6673853.574999758,9.137), Point(379539.9769999679,6673866.877999758,8.931), Point(379541.1769999682,6673871.0379997585,8.926)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,53.949,85053,468863,1,reversed = false,None,1652179948783L,262360669,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.Combined,Discontinuity.MinorDiscontinuity,AddrMRange(5198,5209),AddrMRange(5198,5209),None,None,Some("test"),"36ca0704-f761-4229-abe0-df1c711671df:1", 0.0,11.444,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(379541.177,6673871.038,8.926), Point(379544.623,6673881.951,8.801001598225529)),projectId,  RoadAddressChangeType.Termination,AdministrativeClass.Municipality, FrozenLinkInterface,11.444,85053,468864,1,reversed = false,None,1652179948783L,262360669,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.RightSide,Discontinuity.Continuous,AddrMRange(5209,5266),AddrMRange(5209,5266),None,None,Some("test"),"eb80bddd-9d3e-40b5-b5aa-30b7ce4da857:1", 0.0,11.487,SideCode.AgainstDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(379568.737,6673880.788,8.493), Point(379558.5429999678,6673886.08199976,8.594)),projectId,  RoadAddressChangeType.Termination,AdministrativeClass.Municipality, FrozenLinkInterface,11.487,74878,441812,1,reversed = false,None,1652179948783L,148600188,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.LeftSide,Discontinuity.Continuous,AddrMRange(5209,5268),AddrMRange(5209,5268),None,None,Some("test"),"aa36fb9b-36db-4ed0-834c-26a11fbb660a:1", 0.0,11.991,SideCode.AgainstDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(379570.557,6673888.718,8.455), Point(379560.077,6673894.545,8.493999966366015)),projectId,  RoadAddressChangeType.Termination,AdministrativeClass.Municipality, FrozenLinkInterface,11.991,74803,442181,1,reversed = false,None,1652179948783L,148694062,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.RightSide,Discontinuity.Continuous,AddrMRange(5266,5528),AddrMRange(5266,5528),None,None,Some("test"),"37902af2-406e-4c6f-9bd2-f944143a523d:1", 18.308,71.068,SideCode.AgainstDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(379619.779,6673868.984,8.464002058463667), Point(379615.2329999681,6673869.606999762,8.463), Point(379601.2629999679,6673871.5899997605,8.419), Point(379585.117999968,6673873.976999762,8.452), Point(379568.736999968,6673880.7879997585,8.493)),projectId,  RoadAddressChangeType.Termination,AdministrativeClass.Municipality, FrozenLinkInterface,52.76,74878,441813,1,reversed = false,None,1652179948783L,148600188,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.LeftSide,Discontinuity.Continuous,AddrMRange(5268,5528),AddrMRange(5268,5528),None,None,Some("test"),"71520cc1-fa64-4884-8d43-6ff46cefb717:1", 18.308,71.067,SideCode.AgainstDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(379620.926,6673873.315,8.327262626395294), Point(379617.633999968,6673874.634999761,8.3), Point(379592.7359999682,6673880.676999762,8.347), Point(379570.5569999679,6673888.717999759,8.455)),projectId,  RoadAddressChangeType.Termination,AdministrativeClass.Municipality, FrozenLinkInterface,52.759,74803,442182,1,reversed = false,None,1652179948783L,148694062,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.LeftSide,Discontinuity.Continuous,AddrMRange(5528,5621),AddrMRange(5528,5621),None,None,Some("test"),"71520cc1-fa64-4884-8d43-6ff46cefb717:1", 0.0,18.308,SideCode.AgainstDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(379637.918,6673866.5,8.468), Point(379620.926,6673873.315,8.327262626395294)),projectId,  RoadAddressChangeType.Termination,AdministrativeClass.Municipality, FrozenLinkInterface,18.308,79183,455773,1,reversed = false,None,1652179948783L,190960315,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.RightSide,Discontinuity.Continuous,AddrMRange(5528,5621),AddrMRange(5528,5621),None,None,Some("test"),"37902af2-406e-4c6f-9bd2-f944143a523d:1", 0.0,18.308,SideCode.AgainstDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(379637.918,6673866.5,8.468), Point(379619.779,6673868.984,8.464002058463667)),projectId,  RoadAddressChangeType.Termination,AdministrativeClass.Municipality, FrozenLinkInterface,18.308,79295,455758,1,reversed = false,None,1652179948783L,190960312,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.Combined,Discontinuity.Continuous,AddrMRange(5621,5744),AddrMRange(5621,5744),None,None,Some("test"),"0f167a06-1557-4253-85c0-9d9f3de9523a:1", 0.0,128.799,SideCode.AgainstDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(379724.607,6673774.644,9.212), Point(379713.4079999682,6673798.129999765,9.176), Point(379696.232999968,6673818.212999764,9.006), Point(379680.671999968,6673836.131999762,8.806), Point(379664.497999968,6673850.038999762,8.649), Point(379649.433999968,6673860.116999763,8.53), Point(379637.918,6673866.5,8.468001201603663)),projectId,  RoadAddressChangeType.Termination,AdministrativeClass.Municipality, FrozenLinkInterface,128.799,59551,398450,1,reversed = false,None,1652179948783L,43168588,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.Combined,Discontinuity.Continuous,AddrMRange(5744,5763),AddrMRange(5744,5763),None,None,Some("test"),"8cd1dec7-9fb3-4a4b-afa2-7256ceb9c529:1", 0.0,19.568,SideCode.AgainstDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(379737.087,6673759.694,9.145), Point(379734.2269999681,6673764.437999766,9.122), Point(379728.636999968,6673770.158999763,9.259), Point(379724.606999968,6673774.643999765,9.212)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.Municipality, FrozenLinkInterface,19.568,59551,398451,1,reversed = false,None,1652179948783L,43168588,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.Combined,Discontinuity.Continuous,AddrMRange(5763,5939),AddrMRange(5763,5939),None,None,Some("test"),"deb4a845-c1fd-4653-a571-a8c900ac6a24:1", 0.0,183.905,SideCode.AgainstDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(379847.67,6673618.363,3.718), Point(379844.902999968,6673627.983999766,3.935), Point(379839.0929999681,6673643.257999768,4.382), Point(379832.3769999681,6673656.743999768,4.86), Point(379823.4509999681,6673671.111999767,5.419), Point(379811.8079999681,6673690.412999764,6.241), Point(379801.7539999682,6673702.507999767,6.866), Point(379789.1499999681,6673716.920999767,7.54), Point(379770.5449999679,6673734.568999765,8.324), Point(379756.9189999681,6673749.5009997655,8.853), Point(379737.087,6673759.694,9.144996030044537)),projectId,  RoadAddressChangeType.Termination,AdministrativeClass.Municipality, FrozenLinkInterface,183.905,59551,398452,1,reversed = false,None,1652179948783L,43168588,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.Combined,Discontinuity.EndOfRoad,AddrMRange(5939,5992),AddrMRange(5939,5992),None,None,Some("test"),"d88a35c8-f1da-43b1-91d4-849aba7a32e2:1", 0.0,55.1,SideCode.AgainstDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(379859.38,6673564.541,2.713), Point(379853.742999968,6673593.822999767,3.144), Point(379847.6699999681,6673618.362999766,3.718)),projectId,  RoadAddressChangeType.Termination,AdministrativeClass.Municipality, FrozenLinkInterface,55.1,59551,398453,1,reversed = false,None,1652179948783L,43168588,roadName,None,None,None,None)
      )

      linearLocationDAO.create(linearLocations)
      roadwayDAO.create(roadways)
      projectDAO.create(Project(projectId, ProjectState.Incomplete, "test", "test", DateTime.now(),"test", DateTime.now(), DateTime.now(),"",
        Seq(
          ProjectReservedPart(Sequences.nextViitePrimaryKeySeqValue,roadPart1),
          ProjectReservedPart(Sequences.nextViitePrimaryKeySeqValue,roundaboutRoadPart)
        ),
        Seq(
          ProjectReservedPart(Sequences.nextViitePrimaryKeySeqValue,roadPart1),
          ProjectReservedPart(Sequences.nextViitePrimaryKeySeqValue,roundaboutRoadPart)
        ),
        None,None,Set(14)))
      projectReservedPartDAO.reserveRoadPart(projectId, roadPart1, "test")
      projectReservedPartDAO.reserveRoadPart(projectId, roundaboutRoadPart, "test")
      projectLinkDAO.create(projectLinks)
      projectService.recalculateProjectLinks(projectId, "test", Set(roadPart1, roundaboutRoadPart))

      val precalculatedAddresses = List(
        (0,137),
        (137,166),
        (166,178),
        (178,198),
        (198,254),
        (254,532),
        (532,550),
        (550,649),
        (649,688),
        (688,695),
        (937,1020),
        (1020,1034),
        (1034,1170),
        (3161,3226),
        (3226,3332),
        (3332,3352),
        (3352,3367),
        (3367,3400),
        (3400,3481),
        (3481,3516),
        (3516,3751),
        (3751,3765),
        (3765,3781),
        (3781,3915),
        (0,136),
        (136,161),
        (161,174),
        (174,194),
        (194,244),
        (244,257),
        (257,530),
        (530,547),
        (547,648),
        (648,687),
        (687,695),
        (937,1019),
        (1019,1033),
        (1033,1170),
        (3161,3226),
        (3226,3330),
        (3330,3350),
        (3350,3366),
        (3366,3400),
        (3400,3481),
        (3481,3516),
        (3516,3755),
        (3755,3769),
        (3769,3783),
        (3783,3915),
        (695,937),
        (1170,1482),
        (1482,1494),
        (1494,1505),
        (1505,1730),
        (1730,1773),
        (1773,1792),
        (1792,1919),
        (1919,1935),
        (1935,1943),
        (1943,1954),
        (1954,2156),
        (2156,2387),
        (2387,2406),
        (2406,2474),
        (2474,2685),
        (2685,2729),
        (2729,2740),
        (2740,2808),
        (2808,2821),
        (2821,2848),
        (2848,2968),
        (2968,3005),
        (3005,3021),
        (3021,3161),
        (3915,4150),
        (4150,4213),
        (4213,4223),
        (4223,4307),
        (4307,4360),
        (4360,4442),
        (4442,4589),
        (4589,4670),
        (4670,4761),
        (4761,4836),
        (4836,4849),
        (4849,4940),
        (4940,5072),
        (5072,5144),
        (5144,5198),
        (5198,5426),
        (5426,5445),
        (5445,5679)
      )

      val precalculatedTerminatedAddresses = List(
        (0,15),
        (15,24),
        (24,33),
        (33,44),
        (44,67),
        (5198,5209),
        (5621,5744),
        (5763,5939),
        (5939,5992),
        (5209,5268),
        (5268,5528),
        (5528,5621),
        (5209,5266),
        (5266,5528),
        (5528,5621)
      )

      val recalculated = projectLinkDAO.fetchProjectLinks(projectId)
      recalculated.size should be (107)

      val (terminated, others) = recalculated.partition(_.status == RoadAddressChangeType.Termination)

      val left = others.filter(_.track == Track.LeftSide).sortBy(_.addrMRange.start)
      val right = others.filter(_.track == Track.RightSide).sortBy(_.addrMRange.start)
      val combined = others.filter(_.track == Track.Combined).sortBy(_.addrMRange.start)

      val addresses = (left ++ right ++ combined).map(pl => (pl.addrMRange.start, pl.addrMRange.end)).toList

      val terminatedLeft = terminated.filter(_.track == Track.LeftSide).sortBy(_.addrMRange.start)
      val terminatedRight = terminated.filter(_.track == Track.RightSide).sortBy(_.addrMRange.start)
      val terminatedCombined = terminated.filter(_.track == Track.Combined).sortBy(_.addrMRange.start)

      val terminatedAddresses = (terminatedCombined ++ terminatedLeft ++ terminatedRight).map(pl => (pl.addrMRange.start, pl.addrMRange.end)).toList

      addresses should be (precalculatedAddresses)
      terminatedAddresses should be (precalculatedTerminatedAddresses)
    }
  }
  
  test("Test assignAddrMValues() When Terminating a small section on a two track road in the middle of road part Then the terminated sections should be adjusted to match") {
    runWithRollback {
      val roadPart1 = RoadPart(49529,1)
      val projectId = Sequences.nextViiteProjectId
      val roadName = Some("testRoad")

      val roadways = Seq(
        Roadway(85042,262301768,roadPart1,AdministrativeClass.Municipality,Track.RightSide,Discontinuity.EndOfRoad,AddrMRange(0, 1463),false,DateTime.now().minusDays(2),None,"test",None,14,TerminationCode.NoTermination,DateTime.now().minusDays(2),None),
        Roadway(85415,262301771,roadPart1,AdministrativeClass.Municipality,Track.LeftSide,Discontinuity.EndOfRoad,AddrMRange(0, 1463),false,DateTime.now().minusDays(2),None,"test",None,14,TerminationCode.NoTermination,DateTime.now().minusDays(2),None)
      )

      val linearLocations = Seq(
        LinearLocation(468561, 1.0,"3487fcef-80fd-4fce-bca7-2ce43d20aee2:1",0.0,140.424,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(241065.213,6711661.602,0.0), Point(241005.128,6711788.02,0.0)),FrozenLinkInterface,262301771,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(468524, 7.0,"649d6b7c-885c-4f0f-b3ae-1f224c15bf07:1",0.0,178.166,SideCode.AgainstDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(240465.328,6711836.817,0.0), Point(240626.95,6711910.652,0.0)),FrozenLinkInterface,262301768,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(468567, 7.0,"755920c6-366d-4ea8-a2e9-c8848a2bfcac:1",0.0,135.385,SideCode.AgainstDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(240328.277,6711815.517,0.0), Point(240463.163,6711826.951,0.0)),FrozenLinkInterface,262301771,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(468529, 12.0,"7df2e0c0-6358-43a3-ac47-13ad5c7801e5:1",0.0,63.583,SideCode.AgainstDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(239888.103,6711868.051,0.0), Point(239943.866,6711898.597,0.0)),FrozenLinkInterface,262301768,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(468528, 11.0,"3467947c-5761-4800-9e77-9bf291947606:1",0.0,316.802,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(240217.788,6711847.93,0.0), Point(239943.866,6711898.597,0.0)),FrozenLinkInterface,262301768,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(468571, 11.0,"cc903c1a-5775-4c86-b708-3c42544baca9:1",0.0,8.931,SideCode.AgainstDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(239943.124,6711881.944,0.0), Point(239950.908,6711886.322,0.0)),FrozenLinkInterface,262301771,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(468564, 4.0,"56382582-5885-4fb2-96ea-705a18933825:1",0.0,12.67,SideCode.AgainstDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(240735.29,6711951.569,0.0), Point(240746.812,6711956.84,0.0)),FrozenLinkInterface,262301771,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(468527, 10.0,"78a36ab1-1c88-41c4-9844-fdaab0481364:1",0.0,13.155,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(240229.696,6711842.339,0.0), Point(240217.788,6711847.93,0.0)),FrozenLinkInterface,262301768,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(468520, 3.0,"422a0e64-2e98-41c1-a167-1473d1cf56f1:1",0.0,108.197,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(240998.729,6711818.193,0.0), Point(240936.385,6711906.607,0.0)),FrozenLinkInterface,262301768,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(468530, 13.0,"babbca6c-d545-432d-8c94-0a82fdd410d2:1",0.0,33.621,SideCode.AgainstDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(239858.554,6711852.013,0.0), Point(239888.103,6711868.051,0.0)),FrozenLinkInterface,262301768,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(468570, 10.0,"e81c10cd-3f5a-4149-8e2b-ac943f7431cf:1",0.0,303.233,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(240210.751,6711839.164,0.0), Point(239950.908,6711886.322,0.0)),FrozenLinkInterface,262301771,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(468562, 2.0,"7fa9e40a-6f94-4dde-943f-78c7a489e360:1",0.0,25.577,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(241005.128,6711788.02,0.0), Point(240991.477,6711809.647,0.0)),FrozenLinkInterface,262301771,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(468573, 13.0,"5529a438-5cf0-430e-9e81-e2bea9be1c69:1",0.0,34.058,SideCode.AgainstDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(239864.463,6711839.79,0.0), Point(239894.296,6711856.209,0.0)),FrozenLinkInterface,262301771,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(468526, 9.0,"32b1cef7-f485-4bae-8481-fe452c952f88:1",0.0,102.295,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(240329.781,6711825.007,0.0), Point(240229.696,6711842.339,0.0)),FrozenLinkInterface,262301768,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(468523, 6.0,"7b0c9b7f-1623-4224-9bcc-963bda373e85:1",0.0,106.519,SideCode.AgainstDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(240626.95,6711910.652,0.0), Point(240722.738,6711957.225,0.0)),FrozenLinkInterface,262301768,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(468568, 8.0,"632d0a56-80b7-4f95-a964-221677a7277f:1",0.0,105.49,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(240328.277,6711815.517,0.0), Point(240224.805,6711832.765,0.0)),FrozenLinkInterface,262301771,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(468563, 3.0,"c6e8836c-83bf-4266-9626-d863912d67b5:1",0.0,316.358,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(240991.477,6711809.647,0.0), Point(240746.812,6711956.84,0.0)),FrozenLinkInterface,262301771,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(468518, 1.0,"c181f1ff-cdc1-42f4-901e-658303a00327:1",0.0,142.091,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(241080.825,6711666.306,0.0), Point(241015.709,6711792.33,0.0)),FrozenLinkInterface,262301768,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(468565, 5.0,"90685c0f-c0d2-43b4-b3e4-fa8c2a1e7ace:1",0.0,116.332,SideCode.AgainstDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(240631.624,6711898.782,0.0), Point(240735.29,6711951.569,0.0)),FrozenLinkInterface,262301771,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(468569, 9.0,"ce915cff-95a6-4243-8d5d-05c51b0e77dd:1",0.0,15.442,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(240224.805,6711832.765,0.0), Point(240210.751,6711839.164,0.0)),FrozenLinkInterface,262301771,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(468525, 8.0,"2efa6305-9ea8-4507-9c18-3ee559246bc2:1",0.0,136.083,SideCode.AgainstDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(240329.781,6711825.007,0.0), Point(240465.328,6711836.817,0.0)),FrozenLinkInterface,262301768,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(468522, 5.0,"8cccf672-17b4-47a4-bc14-29d879a69f2e:1",0.0,10.968,SideCode.AgainstDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(240722.738,6711957.225,0.0), Point(240732.657,6711961.907,0.0)),FrozenLinkInterface,262301768,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(468521, 4.0,"ecfd4b54-32c1-415c-883c-487e45ee3267:1",0.0,230.491,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(240936.385,6711906.607,0.0), Point(240732.657,6711961.907,0.0)),FrozenLinkInterface,262301768,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(468566, 6.0,"7352492d-b444-466f-9538-4522c35fd24b:1",0.0,183.625,SideCode.AgainstDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(240463.163,6711826.951,0.0), Point(240631.624,6711898.782,0.0)),FrozenLinkInterface,262301771,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(468572, 12.0,"fac68673-2f01-4b7b-8fb1-5faad68afeba:1",0.0,55.201,SideCode.AgainstDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(239894.296,6711856.209,0.0), Point(239943.124,6711881.944,0.0)),FrozenLinkInterface,262301771,Some(DateTime.now().minusDays(2)),None),
        LinearLocation(468519, 2.0,"cb3ca5ab-4ffe-4e28-b5d1-484a909b9ca7:1",0.0,30.941,SideCode.TowardsDigitizing,1698414053000L,(CalibrationPointReference(None,None),CalibrationPointReference(None,None)),List(Point(241015.709,6711792.33,0.0), Point(240998.729,6711818.193,0.0)),FrozenLinkInterface,262301768,Some(DateTime.now().minusDays(2)),None)
      )

      val projectLinks = Seq(
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.LeftSide,Discontinuity.MinorDiscontinuity,AddrMRange(0,141),AddrMRange(0,141),None,None,Some("test"),"3487fcef-80fd-4fce-bca7-2ce43d20aee2:1", 0.0,140.424,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(241065.213,6711661.602,14.464), Point(241044.3950246294,6711717.648799368,14.356), Point(241029.6260246445,6711746.925799263,14.171), Point(241008.5200246658,6711782.605799116,13.914), Point(241005.1280246694,6711788.019799092,13.89)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,140.424,85415,468561,2,reversed = false,None,1652179948783L,262301771,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.RightSide,Discontinuity.MinorDiscontinuity,AddrMRange(0,141),AddrMRange(0,141),None,None,Some("test"),"c181f1ff-cdc1-42f4-901e-658303a00327:1", 0.0,142.091,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(241080.825,6711666.306,14.796), Point(241068.1310246072,6711697.117799523,14.641), Point(241048.2930246274,6711737.285799382,14.501), Point(241028.8460246469,6711770.560799248,14.307), Point(241015.7090246602,6711792.329799158,14.08)),projectId,  RoadAddressChangeType.Unchanged,AdministrativeClass.Municipality, FrozenLinkInterface,142.091,85042,468518,2,reversed = false,None,1652179948783L,262301768,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.LeftSide,Discontinuity.Continuous,AddrMRange(141,167),AddrMRange(141,167),None,None,Some("test"),"7fa9e40a-6f94-4dde-943f-78c7a489e360:1", 0.0,25.577,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(241005.128,6711788.02,13.89), Point(240997.8590246766,6711799.862799042,13.639), Point(240991.477,6711809.647,13.428005889575788)),projectId,  RoadAddressChangeType.Termination,AdministrativeClass.Municipality, FrozenLinkInterface,25.577,85415,468562,2,reversed = false,None,1652179948783L,262301771,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.RightSide,Discontinuity.Continuous,AddrMRange(141,172),AddrMRange(141,172),None,None,Some("test"),"cb3ca5ab-4ffe-4e28-b5d1-484a909b9ca7:1", 0.0,30.941,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(241015.709,6711792.33,14.08), Point(241007.2010246686,6711805.601799099,13.907), Point(240998.7290246772,6711818.192799039,13.74)),projectId,  RoadAddressChangeType.Termination,AdministrativeClass.Municipality, FrozenLinkInterface,30.941,85042,468519,2,reversed = false,None,1652179948783L,262301768,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.RightSide,Discontinuity.Continuous,AddrMRange(172,279),AddrMRange(172,279),None,None,Some("test"),"422a0e64-2e98-41c1-a167-1473d1cf56f1:1", 0.0,108.197,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(240998.729,6711818.193,13.74), Point(240984.1590246916,6711839.632798939,13.406), Point(240958.0010247176,6711877.27779876,12.52), Point(240936.385,6711906.607,11.781002494581072)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.Municipality, FrozenLinkInterface,108.197,85042,468520,2,reversed = false,None,1652179948783L,262301768,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.LeftSide,Discontinuity.Continuous,AddrMRange(167,486),AddrMRange(167,486),None,None,Some("test"),"c6e8836c-83bf-4266-9626-d863912d67b5:1", 0.0,316.358,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(240991.477,6711809.647,13.428), Point(240983.1710246914,6711822.804798939,13.373), Point(240962.9610247115,6711851.858798804,12.821), Point(240938.5360247357,6711886.293798635,11.936), Point(240919.1720247546,6711911.275798502,11.225), Point(240894.9960247779,6711935.966798341,10.3), Point(240868.5220248029,6711953.174798169,9.322), Point(240838.4580248307,6711964.304797976,8.106), Point(240811.9530248549,6711968.368797809,6.93), Point(240779.0590248843,6711966.253797601,5.454), Point(240749.66802491,6711958.033797421,4.599), Point(240746.812,6711956.84,4.562003809446201)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.Municipality, FrozenLinkInterface,316.358,85415,468563,2,reversed = false,None,1652179948783L,262301771,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.LeftSide,Discontinuity.Continuous,AddrMRange(486,499),AddrMRange(486,499),None,None,Some("test"),"56382582-5885-4fb2-96ea-705a18933825:1", 0.0,12.67,SideCode.AgainstDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(240735.29,6711951.569,4.547), Point(240746.812,6711956.84,4.5619994849081555)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.Municipality, FrozenLinkInterface,12.67,85415,468564,2,reversed = false,None,1652179948783L,262301771,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.RightSide,Discontinuity.Continuous,AddrMRange(279,508),AddrMRange(279,508),None,None,Some("test"),"ecfd4b54-32c1-415c-883c-487e45ee3267:1", 0.0,230.491,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(240936.385,6711906.607,11.781), Point(240920.3150247548,6711925.8957985025,11.151), Point(240903.9860247704,6711940.634798395,10.66), Point(240883.3620247901,6711956.82879826,9.964), Point(240855.2290248161,6711969.668798078,8.89), Point(240829.5130248396,6711976.676797914,7.86), Point(240800.7420248656,6711978.907797733,6.621), Point(240773.3500248899,6711976.054797564,5.548), Point(240751.8550249091,6711970.189797428,4.905), Point(240732.6570249255,6711961.906797313,4.72)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.Municipality, FrozenLinkInterface,230.491,85042,468521,2,reversed = false,None,1652179948783L,262301768,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.RightSide,Discontinuity.Continuous,AddrMRange(508,519),AddrMRange(508,519),None,None,Some("test"),"8cccf672-17b4-47a4-bc14-29d879a69f2e:1", 0.0,10.968,SideCode.AgainstDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(240722.738,6711957.225,4.771), Point(240732.657,6711961.907,4.72000225980326)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.Municipality, FrozenLinkInterface,10.968,85042,468522,2,reversed = false,None,1652179948783L,262301768,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.LeftSide,Discontinuity.Continuous,AddrMRange(499,616),AddrMRange(499,616),None,None,Some("test"),"90685c0f-c0d2-43b4-b3e4-fa8c2a1e7ace:1", 0.0,116.332,SideCode.AgainstDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(240631.624,6711898.782,8.873), Point(240673.0780249762,6711919.912796957,6.849), Point(240721.4540249344,6711944.621797251,4.754), Point(240735.29,6711951.569,4.547002368712936)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.Municipality, FrozenLinkInterface,116.332,85415,468565,2,reversed = false,None,1652179948783L,262301771,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.RightSide,Discontinuity.Continuous,AddrMRange(519,625),AddrMRange(519,625),None,None,Some("test"),"7b0c9b7f-1623-4224-9bcc-963bda373e85:1", 0.0,106.519,SideCode.AgainstDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(240626.95,6711910.652,8.883), Point(240678.4250249724,6711934.921796984,6.426), Point(240719.9100249365,6711955.865797236,4.805), Point(240722.738,6711957.225,4.77100373381075)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.Municipality, FrozenLinkInterface,106.519,85042,468523,2,reversed = false,None,1652179948783L,262301768,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.LeftSide,Discontinuity.Continuous,AddrMRange(616,801),AddrMRange(616,801),None,None,Some("test"),"7352492d-b444-466f-9538-4522c35fd24b:1", 0.0,183.625,SideCode.AgainstDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(240463.163,6711826.951,10.189), Point(240483.5930251412,6711832.0897958,10.35), Point(240503.9260251233,6711838.604795928,10.532), Point(240529.7390251009,6711849.595796086,10.662), Point(240554.6870250791,6711860.894796236,10.595), Point(240581.9090250554,6711874.378796403,10.338), Point(240607.1480250334,6711887.150796557,9.771), Point(240631.6240250123,6711898.781796706,8.873)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.Municipality, FrozenLinkInterface,183.625,85415,468566,2,reversed = false,None,1652179948783L,262301771,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.RightSide,Discontinuity.Continuous,AddrMRange(625,802),AddrMRange(625,802),None,None,Some("test"),"649d6b7c-885c-4f0f-b3ae-1f224c15bf07:1", 0.0,178.166,SideCode.AgainstDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(240465.328,6711836.817,10.024), Point(240494.9900251319,6711845.266795866,10.292), Point(240522.7330251076,6711857.255796037,10.605), Point(240582.237025056,6711888.1177964,10.289), Point(240626.95,6711910.652,8.883009632346408)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.Municipality, FrozenLinkInterface,178.166,85042,468524,2,reversed = false,None,1652179948783L,262301768,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.LeftSide,Discontinuity.Continuous,AddrMRange(801,937),AddrMRange(801,937),None,None,Some("test"),"755920c6-366d-4ea8-a2e9-c8848a2bfcac:1", 0.0,135.385,SideCode.AgainstDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(240328.277,6711815.517,8.982), Point(240395.5490252205,6711820.190795248,9.583), Point(240463.163,6711826.951,10.188997619524418)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.Municipality, FrozenLinkInterface,135.385,85415,468567,2,reversed = false,None,1652179948783L,262301771,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.RightSide,Discontinuity.Continuous,AddrMRange(802,937),AddrMRange(802,937),None,None,Some("test"),"2efa6305-9ea8-4507-9c18-3ee559246bc2:1", 0.0,136.083,SideCode.AgainstDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(240329.781,6711825.007,8.81), Point(240393.499025223,6711829.306795232,9.38), Point(240465.328,6711836.817,10.02399589585926)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.Municipality, FrozenLinkInterface,136.083,85042,468525,2,reversed = false,None,1652179948783L,262301768,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.RightSide,Discontinuity.Continuous,AddrMRange(937,1039),AddrMRange(937,1039),None,None,Some("test"),"32b1cef7-f485-4bae-8481-fe452c952f88:1", 0.0,102.295,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(240329.781,6711825.007,8.81), Point(240303.0640253048,6711825.618794662,8.631), Point(240275.8270253299,6711828.824794487,8.679), Point(240238.3620253649,6711838.681794243,8.951), Point(240229.696,6711842.339,9.052999434871845)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.Municipality, FrozenLinkInterface,102.295,85042,468526,2,reversed = false,None,1652179948783L,262301768,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.LeftSide,Discontinuity.Continuous,AddrMRange(937,1043),AddrMRange(937,1043),None,None,Some("test"),"632d0a56-80b7-4f95-a964-221677a7277f:1", 0.0,105.49,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(240328.277,6711815.517,8.982), Point(240287.7730253181,6711817.578794565,8.777), Point(240257.8510253459,6711822.830794375,8.881), Point(240233.8940253683,6711829.1817942215,9.136), Point(240224.805,6711832.765,9.24999823120986)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.Municipality, FrozenLinkInterface,105.49,85415,468568,2,reversed = false,None,1652179948783L,262301771,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.RightSide,Discontinuity.Continuous,AddrMRange(1039,1052),AddrMRange(1039,1052),None,None,Some("test"),"78a36ab1-1c88-41c4-9844-fdaab0481364:1", 0.0,13.155,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(240229.696,6711842.339,9.053), Point(240217.788,6711847.93,9.182997852019739)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.Municipality, FrozenLinkInterface,13.155,85042,468527,2,reversed = false,None,1652179948783L,262301768,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.LeftSide,Discontinuity.Continuous,AddrMRange(1043,1059),AddrMRange(1043,1059),None,None,Some("test"),"ce915cff-95a6-4243-8d5d-05c51b0e77dd:1", 0.0,15.442,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(240224.805,6711832.765,9.25), Point(240210.751,6711839.164,9.35999844280315)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.Municipality, FrozenLinkInterface,15.442,85415,468569,2,reversed = false,None,1652179948783L,262301771,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.LeftSide,Discontinuity.Continuous,AddrMRange(1059,1364),AddrMRange(1059,1364),None,None,Some("test"),"e81c10cd-3f5a-4149-8e2b-ac943f7431cf:1", 0.0,303.233,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(240210.751,6711839.164,9.36), Point(240174.9270254245,6711863.599793829,9.581), Point(240136.4450254619,6711892.128793571,9.889), Point(240113.3060254844,6711909.911793415,10.051), Point(240089.1410255078,6711926.543793254,10.178), Point(240074.6940255215,6711933.8377931565,10.282), Point(240061.7790255337,6711937.677793075,10.44), Point(240051.2940255433,6711937.424793007,10.536), Point(240040.551025553,6711935.254792939,10.639), Point(240029.0420255634,6711931.165792868,10.783), Point(240013.6940255772,6711923.369792774,10.963), Point(239982.3590256045,6711905.603792581,11.231), Point(239950.9080256321,6711886.3217923865,11.132)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.Municipality, FrozenLinkInterface,303.233,85415,468570,2,reversed = false,None,1652179948783L,262301771,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.RightSide,Discontinuity.Continuous,AddrMRange(1052,1366),AddrMRange(1052,1366),None,None,Some("test"),"3467947c-5761-4800-9e77-9bf291947606:1", 0.0,316.802,SideCode.TowardsDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(240217.788,6711847.93,9.183), Point(240200.1170254013,6711858.362793991,9.247), Point(240178.511025422,6711874.993793849,9.429), Point(240148.7230254511,6711895.847793647,9.722), Point(240124.3040254747,6711914.269793483,9.894), Point(240102.8260254955,6711930.00579334,10.251), Point(240086.0770255115,6711941.008793227,10.625), Point(240068.1770255285,6711947.536793109,10.795), Point(240048.2290255471,6711948.565792982,10.91), Point(240025.9760255671,6711943.201792844,11.085), Point(239998.9900255909,6711929.910792675,11.199), Point(239973.1550256135,6711914.955792517,11.369), Point(239943.8660256394,6711898.596792336,11.301)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.Municipality, FrozenLinkInterface,316.802,85042,468528,2,reversed = false,None,1652179948783L,262301768,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.LeftSide,Discontinuity.Continuous,AddrMRange(1364,1373),AddrMRange(1364,1373),None,None,Some("test"),"cc903c1a-5775-4c86-b708-3c42544baca9:1", 0.0,8.931,SideCode.AgainstDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(239943.124,6711881.944,11.097), Point(239950.9080256321,6711886.3217923865,11.132)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.Municipality, FrozenLinkInterface,8.931,85415,468571,2,reversed = false,None,1652179948783L,262301771,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.RightSide,Discontinuity.Continuous,AddrMRange(1366,1429),AddrMRange(1366,1429),None,None,Some("test"),"7df2e0c0-6358-43a3-ac47-13ad5c7801e5:1", 0.0,63.583,SideCode.AgainstDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(239888.103,6711868.051,10.6), Point(239918.9260256615,6711885.176792183,11.017), Point(239943.8660256394,6711898.596792336,11.301)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.Municipality, FrozenLinkInterface,63.583,85042,468529,2,reversed = false,None,1652179948783L,262301768,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.LeftSide,Discontinuity.Continuous,AddrMRange(1373,1429),AddrMRange(1373,1429),None,None,Some("test"),"fac68673-2f01-4b7b-8fb1-5faad68afeba:1", 0.0,55.201,SideCode.AgainstDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(239894.296,6711856.209,10.656), Point(239931.5950256491,6711875.457792267,11.051), Point(239943.124,6711881.944,11.096998927616202)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.Municipality, FrozenLinkInterface,55.201,85415,468572,2,reversed = false,None,1652179948783L,262301771,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.LeftSide,Discontinuity.EndOfRoad,AddrMRange(1429,1463),AddrMRange(1429,1463),None,None,Some("test"),"5529a438-5cf0-430e-9e81-e2bea9be1c69:1", 0.0,34.058,SideCode.AgainstDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(239864.463,6711839.79,10.447), Point(239879.4270256952,6711848.352791944,10.553), Point(239894.2960256821,6711856.208792038,10.656)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.Municipality, FrozenLinkInterface,34.058,85415,468573,2,reversed = false,None,1652179948783L,262301771,roadName,None,None,None,None),
        ProjectLink(Sequences.nextProjectLinkId, roadPart1,Track.RightSide,Discontinuity.EndOfRoad,AddrMRange(1429,1463),AddrMRange(1429,1463),None,None,Some("test"),"babbca6c-d545-432d-8c94-0a82fdd410d2:1", 0.0,33.621,SideCode.AgainstDigitizing,(NoCP, NoCP), (NoCP, NoCP),List(Point(239858.554,6711852.013,10.502), Point(239888.1030256887,6711868.050791995,10.6)),projectId,  RoadAddressChangeType.Transfer,AdministrativeClass.Municipality, FrozenLinkInterface,33.621,85042,468530,2,reversed = false,None,1652179948783L,262301768,roadName,None,None,None,None)
      )

      linearLocationDAO.create(linearLocations)
      roadwayDAO.create(roadways)
      projectDAO.create(Project(projectId, ProjectState.Incomplete, "test", "test", DateTime.now(),"test", DateTime.now(), DateTime.now(),"",
        Seq(
          ProjectReservedPart(Sequences.nextViitePrimaryKeySeqValue,roadPart1)
        ),
        Seq(
          ProjectReservedPart(Sequences.nextViitePrimaryKeySeqValue,roadPart1)
        ),
        None,None,Set(14)))
      projectReservedPartDAO.reserveRoadPart(projectId, roadPart1, "test")
      projectLinkDAO.create(projectLinks)
      projectService.recalculateProjectLinks(projectId, "test", Set(roadPart1))

      val recalculated = projectLinkDAO.fetchProjectLinks(projectId)
      recalculated.size should be (26)

      val terminated = recalculated.filter(_.status == RoadAddressChangeType.Termination)
      val terminatedEndAddress = terminated.map(_.addrMRange.end).distinct
      terminatedEndAddress.size should be (1)
      terminatedEndAddress.head should be (170)

      val transferred = recalculated.filter(_.status == RoadAddressChangeType.Transfer)
      val left = transferred.filter(_.track == Track.LeftSide)
      val right = transferred.filter(_.track == Track.RightSide)
      val originalStartingAddrLeft = left.minBy(_.addrMRange.start).originalAddrMRange.start
      val originalStartingAddrRight = right.minBy(_.addrMRange.start).originalAddrMRange.start

      originalStartingAddrLeft should be (originalStartingAddrRight)
      originalStartingAddrLeft should be (terminatedEndAddress.head)
    }
  }

  private def projectLink(addrMRange: AddrMRange, track: Track, projectId: Long, status: RoadAddressChangeType = RoadAddressChangeType.NotHandled,
                          roadPart: RoadPart = RoadPart(19999, 1), discontinuity: Discontinuity = Discontinuity.Continuous, ely: Long = 8L, roadwayId: Long = 0L, linearLocationId: Long = 0L) = {
    val startDate = if (status !== RoadAddressChangeType.New) Some(DateTime.now()) else None
    ProjectLink(NewIdValue, roadPart, track, discontinuity, addrMRange, addrMRange, startDate, None, Some("User"), addrMRange.start.toString, 0.0, addrMRange.length.toDouble, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), Seq(Point(0.0, addrMRange.start), Point(0.0, addrMRange.end)), projectId, status, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, addrMRange.length.toDouble, roadwayId, linearLocationId, ely, reversed = false, None, 0L)
  }

}

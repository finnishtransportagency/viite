package fi.liikennevirasto.viite

import java.sql.BatchUpdateException

import fi.liikennevirasto.digiroad2.{DigiroadEventBus, GeometryUtils, Point}
import fi.liikennevirasto.digiroad2.asset._
import fi.liikennevirasto.digiroad2.asset.AdministrativeClass.State
import fi.liikennevirasto.digiroad2.asset.LifecycleStatus.InUse
import fi.liikennevirasto.digiroad2.asset.LinkGeomSource.{FrozenLinkInterface, NormalLinkInterface}
import fi.liikennevirasto.digiroad2.asset.SideCode.{AgainstDigitizing, TowardsDigitizing}
import fi.liikennevirasto.digiroad2.client.kgv._
import fi.liikennevirasto.digiroad2.dao.Sequences
import fi.liikennevirasto.digiroad2.linearasset.{PolyLine, RoadLink}
import fi.liikennevirasto.digiroad2.postgis.PostGISDatabase
import fi.liikennevirasto.digiroad2.service.RoadLinkService
import fi.liikennevirasto.digiroad2.util.Track
import fi.liikennevirasto.digiroad2.util.Track.{switch, Combined, LeftSide, RightSide}
import fi.liikennevirasto.viite.Dummies._
import fi.liikennevirasto.viite.dao.{LinkStatus, ProjectRoadwayChange, RoadwayDAO, _}
import fi.liikennevirasto.viite.dao.CalibrationPointDAO.CalibrationPointType
import fi.liikennevirasto.viite.dao.CalibrationPointDAO.CalibrationPointType.{JunctionPointCP, NoCP, RoadAddressCP, UserDefinedCP}
import fi.liikennevirasto.viite.dao.Discontinuity.{Continuous, Discontinuous, EndOfRoad}
import fi.liikennevirasto.viite.dao.ProjectCalibrationPointDAO.UserDefinedCalibrationPoint
import fi.liikennevirasto.viite.dao.ProjectState.{Incomplete, UpdatingToRoadNetwork}
import fi.liikennevirasto.viite.dao.TerminationCode.NoTermination
import fi.liikennevirasto.viite.model.{Anomaly, ProjectAddressLink, RoadAddressLinkLike}
import fi.liikennevirasto.viite.process.{ProjectSectionCalculator, RoadwayAddressMapper}
import fi.liikennevirasto.viite.process.strategy.DefaultSectionCalculatorStrategy
import fi.liikennevirasto.viite.util.CalibrationPointsUtils
import org.joda.time.DateTime
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.{when, _}
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import org.scalatest.{BeforeAndAfter, FunSuite, Matchers}
import org.scalatest.mock.MockitoSugar
import slick.driver.JdbcDriver.backend.Database
import slick.driver.JdbcDriver.backend.Database.dynamicSession
import slick.jdbc.StaticQuery.interpolation

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

  private val roadwayNumber1   = 1000000000l
  private val roadwayNumber2   = 2000000000l
  private val roadwayNumber3   = 3000000000l
  private val roadwayNumber4   = 4000000000l
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
  val projectService: ProjectService = new ProjectService(roadAddressServiceRealRoadwayAddressMapper, mockRoadLinkService, mockNodesAndJunctionsService, roadwayDAO,
    roadwayPointDAO, linearLocationDAO, projectDAO, projectLinkDAO,
    nodeDAO, nodePointDAO, junctionPointDAO, projectReservedPartDAO, roadwayChangesDAO,
    roadwayAddressMapper, mockEventBus) {
    override def withDynSession[T](f: => T): T = f

    override def withDynTransaction[T](f: => T): T = f
  }

  val projectServiceWithRoadAddressMock: ProjectService = new ProjectService(mockRoadAddressService, mockRoadLinkService, mockNodesAndJunctionsService, roadwayDAO,
    roadwayPointDAO, linearLocationDAO, projectDAO, projectLinkDAO,
    nodeDAO, nodePointDAO, junctionPointDAO, projectReservedPartDAO, roadwayChangesDAO,
    roadwayAddressMapper, mockEventBus) {
    override def withDynSession[T](f: => T): T = f

    override def withDynTransaction[T](f: => T): T = f
  }

  after {
    reset(mockRoadLinkService)
  }

  def withDynTransaction[T](f: => T): T = PostGISDatabase.withDynTransaction(f)

  def runWithRollback[T](f: => T): T = {
    Database.forDataSource(PostGISDatabase.ds).withDynTransaction {
      val t = f
      dynamicSession.rollback()
      t
    }
  }

  val linearLocations = Seq(
    dummyLinearLocation(roadwayNumber = 1L, orderNumber = 1L, linkId = 123L.toString, startMValue = 0.0, endMValue = 10.0),
    dummyLinearLocation(roadwayNumber = 1L, orderNumber = 2L, linkId = 123L.toString, startMValue = 10.0, endMValue = 20.0),
    dummyLinearLocation(roadwayNumber = 1L, orderNumber = 3L, linkId = 124L.toString, startMValue = 0.0, endMValue = 10.0),
    dummyLinearLocation(roadwayNumber = 1L, orderNumber = 4L, linkId = 125L.toString, startMValue = 0.0, endMValue = 10.0)
  )

  val roadways = Seq(
    dummyRoadway(roadwayNumber = 1L, roadNumber = 1L, roadPartNumber = 1L, startAddrM = 0L, endAddrM = 400L, DateTime.now(), None)
  )

  val vvhHistoryRoadLinks = Seq(
    dummyVvhHistoryRoadLink(linkId = 123L.toString, Seq(0.0, 10.0, 20.0)),
    dummyVvhHistoryRoadLink(linkId = 125L.toString, Seq(0.0, 10.0))
  )

  val roadLinks = Seq(
    dummyRoadLink(linkId = 123L.toString, Seq(0.0, 10.0, 20.0), NormalLinkInterface),
    dummyRoadLink(linkId = 124L.toString, Seq(0.0, 10.0), NormalLinkInterface)
  )

  // note, functionality moved here from former ViiteTierekisteriClient at Tierekisteri removal (2021-09)
  val defaultChangeInfo = RoadwayChangeInfo(
    AddressChangeType.apply(2),
    RoadwayChangeSection(None, None, None, None, None, None, None, None, None),
    RoadwayChangeSection(Option(403), Option(0), Option(8), Option(0), Option(8), Option(1001),
      Option(AdministrativeClass.State), Option(Discontinuity.Continuous), Option(5)),
    Discontinuity.apply(1),
    AdministrativeClass.apply(1),
    reversed = false,
    1)

  private def createProjectLinks(linkIds: Seq[String], projectId: Long, roadNumber: Long, roadPartNumber: Long, track: Int, discontinuity: Int, administrativeClass: Int, roadLinkSource: Int, roadEly: Long, user: String, roadName: String) = {
    projectService.createProjectLinks(linkIds, projectId, roadNumber, roadPartNumber, Track.apply(track), Discontinuity.apply(discontinuity), AdministrativeClass.apply(administrativeClass), LinkGeomSource.apply(roadLinkSource), roadEly, user, roadName)
  }

  private def toProjectLink(project: Project, status: LinkStatus)(roadAddress: RoadAddress): ProjectLink = {
    ProjectLink(id = NewIdValue, roadAddress.roadNumber, roadAddress.roadPartNumber, roadAddress.track, roadAddress.discontinuity, roadAddress.startAddrMValue, roadAddress.endAddrMValue, roadAddress.startAddrMValue, roadAddress.endAddrMValue, roadAddress.startDate, roadAddress.endDate, createdBy = Option(project.createdBy), roadAddress.linkId, roadAddress.startMValue, roadAddress.endMValue, roadAddress.sideCode, roadAddress.calibrationPointTypes, (roadAddress.startCalibrationPointType, roadAddress.endCalibrationPointType), roadAddress.geometry, project.id, status, AdministrativeClass.State, roadAddress.linkGeomSource, GeometryUtils.geometryLength(roadAddress.geometry), if (status == LinkStatus.New) 0 else roadAddress.id, if (status == LinkStatus.New) 0 else roadAddress.linearLocationId, roadAddress.ely, reversed = false, None, roadAddress.adjustedTimestamp)
  }

  private def toProjectAddressLink(ral: RoadAddressLinkLike): ProjectAddressLink = {
    ProjectAddressLink(ral.id, ral.linkId, ral.geometry, ral.length, ral.administrativeClassMML, ral.lifecycleStatus, ral.roadLinkSource, ral.administrativeClass, ral.roadName, ral.municipalityCode, ral.municipalityName, ral.modifiedAt, ral.modifiedBy, ral.roadNumber, ral.roadPartNumber, ral.trackCode, ral.elyCode, ral.discontinuity, ral.startAddressM, ral.endAddressM, ral.startMValue, ral.endMValue, ral.sideCode, ral.startCalibrationPoint, ral.endCalibrationPoint, ral.anomaly, LinkStatus.Unknown, ral.id, ral.linearLocationId, sourceId = ral.sourceId)
  }

  private def extractTrafficDirection(sideCode: SideCode, track: Track): TrafficDirection = {
    (sideCode, track) match {
      case (_, Track.Combined) => TrafficDirection.BothDirections
      case (TowardsDigitizing, Track.RightSide) => TrafficDirection.TowardsDigitizing
      case (TowardsDigitizing, Track.LeftSide) => TrafficDirection.AgainstDigitizing
      case (AgainstDigitizing, Track.RightSide) => TrafficDirection.AgainstDigitizing
      case (AgainstDigitizing, Track.LeftSide) => TrafficDirection.TowardsDigitizing
      case (_, _) => TrafficDirection.UnknownDirection
    }
  }

  private def toRoadLink(ral: ProjectLink): RoadLink = {
    RoadLink(ral.linkId, ral.geometry, ral.geometryLength, State, extractTrafficDirection(ral.sideCode, ral.track), Some(DateTime.now().toString), None, LifecycleStatus.InUse, LinkGeomSource.NormalLinkInterface, 749, "")
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
          val startP = Point(pl.map(_.startAddrMValue).min, 0.0)
          val endP = Point(pl.map(_.endAddrMValue).max, 0.0)
          val maxLen = pl.map(_.endMValue).max
          val midP = Point((startP.x + endP.x) * .5,
            if (endP.x - startP.x < maxLen) {
              Math.sqrt(maxLen * maxLen - (startP.x - endP.x) * (startP.x - endP.x)) / 2
            }
            else 0.0)
          val forcedGeom = pl.filter(l => l.id == -1000L && l.geometry.nonEmpty).sortBy(_.startAddrMValue)
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

  private def mockForProject[T <: PolyLine](id: Long, l: Seq[T] = Seq()) = {
    val roadLink = RoadLink(1.toString, Seq(Point(535602.222, 6982200.25, 89.9999), Point(535605.272, 6982204.22, 85.90899999999965)), 540.3960283713503, State, TrafficDirection.AgainstDigitizing, Some("25.06.2015 03:00:00"), Some("vvh_modified"), InUse, NormalLinkInterface, 749, "")
    val (projectLinks, palinks) = l.partition(_.isInstanceOf[ProjectLink])
    val dbLinks = projectLinkDAO.fetchProjectLinks(id)
    when(mockRoadLinkService.getRoadLinksHistoryFromVVH(any[Set[String]])).thenReturn(Seq())
    when(mockRoadLinkService.getRoadLinksByLinkIds(any[Set[String]])).thenAnswer(
      toMockAnswer(dbLinks ++ projectLinks.asInstanceOf[Seq[ProjectLink]].filterNot(l => dbLinks.map(_.linkId).contains(l.linkId)),
        roadLink, palinks.asInstanceOf[Seq[ProjectAddressLink]].map(toRoadLink)
      ))
  }

  private def setUpProjectWithLinks(linkStatus: LinkStatus, addrM: Seq[Long], changeTrack: Boolean = false, roadNumber: Long = 19999L,
                                    roadPartNumber: Long = 1L, discontinuity: Discontinuity = Discontinuity.Continuous, ely: Long = 8L, roadwayId: Long = 0L, startDate: Option[DateTime] = None) = {
    val id = Sequences.nextViiteProjectId

    def projectLink(startAddrM: Long, endAddrM: Long, track: Track, projectId: Long, status: LinkStatus = LinkStatus.NotHandled, roadNumber: Long = 19999L, roadPartNumber: Long = 1L, discontinuity: Discontinuity = Discontinuity.Continuous, ely: Long = 8L, linkId: String = 0L.toString, roadwayId: Long = 0L, linearLocationId: Long = 0L, startDate: Option[DateTime] = None) = {
      ProjectLink(NewIdValue, roadNumber, roadPartNumber, track, discontinuity, startAddrM, endAddrM, startAddrM, endAddrM, startDate, None, Some("User"), linkId, 0.0, (endAddrM - startAddrM).toDouble, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), Seq(Point(0.0, startAddrM), Point(0.0, endAddrM)), projectId, status, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, (endAddrM - startAddrM).toDouble, roadwayId, linearLocationId, ely, reversed = false, None, 0L)
    }

    def withTrack(t: Track): Seq[ProjectLink] = {
      addrM.init.zip(addrM.tail).map { case (st, en) =>
        projectLink(st, en, t, id, linkStatus, roadNumber, roadPartNumber, discontinuity, ely, roadwayId = roadwayId, startDate = startDate)
      }
    }

    val projectStartDate = if (startDate.isEmpty) DateTime.now() else startDate.get
    val project = Project(id, ProjectState.Incomplete, "f", "s", projectStartDate, "", projectStartDate, projectStartDate,
      "", Seq(), Seq(), None, None)
    projectDAO.create(project)
    val links =
      if (changeTrack) {
        withTrack(RightSide) ++ withTrack(LeftSide)
      } else {
        withTrack(Combined)
      }
    projectReservedPartDAO.reserveRoadPart(id, roadNumber, roadPartNumber, "u")
    projectLinkDAO.create(links)
    project
  }
  private def toProjectLink(project: Project)(roadAddress: RoadAddress): ProjectLink = {
    ProjectLink(id = NewIdValue, roadAddress.roadNumber, roadAddress.roadPartNumber, roadAddress.track, roadAddress.discontinuity, roadAddress.startAddrMValue, roadAddress.endAddrMValue, roadAddress.startAddrMValue, roadAddress.endAddrMValue, roadAddress.startDate, roadAddress.endDate, createdBy = Option(project.createdBy), roadAddress.linkId, roadAddress.startMValue, roadAddress.endMValue, roadAddress.sideCode, roadAddress.calibrationPointTypes, (roadAddress.startCalibrationPointType, roadAddress.endCalibrationPointType), roadAddress.geometry, project.id, LinkStatus.NotHandled, AdministrativeClass.State, roadAddress.linkGeomSource, GeometryUtils.geometryLength(roadAddress.geometry), 0, 0, roadAddress.ely, reversed = false, None, roadAddress.adjustedTimestamp)
  }

  test("Test createRoadLinkProject When no road parts reserved Then return 0 reserved parts project") {
    runWithRollback {
      val roadAddressProject = Project(0, ProjectState.apply(1), "TestProject", "TestUser", DateTime.now(), "TestUser", DateTime.parse("1901-01-01"), DateTime.now(), "Some additional info", List.empty[ProjectReservedPart], Seq(), None)
      val project = projectService.createRoadLinkProject(roadAddressProject)
      project.reservedParts should have size 0
    }
  }

  test("Test createRoadLinkProject When creating road link project without valid roadParts Then return project without the invalid parts") {
    val roadlink = RoadLink(5175306.toString, Seq(Point(535605.272, 6982204.22, 85.90899999999965)), 540.3960283713503, State, TrafficDirection.AgainstDigitizing, Some("25.06.2015 03:00:00"), Some("vvh_modified"), InUse, NormalLinkInterface, 749, "")
    when(mockRoadLinkService.getRoadLinksByLinkIds(Set(5175306L.toString))).thenReturn(Seq(roadlink))
    runWithRollback {
      val roadAddressProject = Project(0, ProjectState.apply(1), "TestProject", "TestUser", DateTime.now(), "TestUser", DateTime.parse("1901-01-01"), DateTime.now(), "Some additional info", List.empty[ProjectReservedPart], Seq(), None)
      val project = projectService.createRoadLinkProject(roadAddressProject)
      project.reservedParts should have size 0
    }
  }

  test("Test createRoadLinkProject When creating a road link project with same name as an existing project Then return error") {
    runWithRollback {
      val roadAddressProject1 = Project(0, ProjectState.apply(1), "TestProject", "TestUser1", DateTime.now(), "TestUser1", DateTime.parse("1901-01-01"), DateTime.now(), "Some additional info", List.empty[ProjectReservedPart], Seq(), None)
      projectService.createRoadLinkProject(roadAddressProject1)

      val roadAddressProject2 = Project(0, ProjectState.apply(1), "TESTPROJECT", "TestUser2", DateTime.now(), "TestUser2", DateTime.parse("1902-03-03"), DateTime.now(), "Some other info", List.empty[ProjectReservedPart], Seq(), None)
      val error = intercept[NameExistsException] {
        projectService.createRoadLinkProject(roadAddressProject2)
      }
      error.getMessage should be("Nimellä TESTPROJECT on jo olemassa projekti. Muuta nimeä.")

      val roadAddressProject3 = Project(0, ProjectState.apply(1), "testproject", "TestUser3", DateTime.now(), "TestUser3", DateTime.parse("1903-03-03"), DateTime.now(), "Some other info", List.empty[ProjectReservedPart], Seq(), None)
      val error2 = intercept[NameExistsException] {
        projectService.createRoadLinkProject(roadAddressProject3)
      }
      error2.getMessage should be("Nimellä testproject on jo olemassa projekti. Muuta nimeä.")
    }
  }

  test("Test saveProject When two projects with same road part Then throw exception on unique key constraint violation") {
    runWithRollback {
      var project2: Project = null
      val rap1 = Project(0L, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("1901-01-01"),
        "TestUser", DateTime.parse("1963-01-01"), DateTime.now(), "Some additional info",
        Seq(), Seq(), None)
      val rap2 = Project(0L, ProjectState.apply(1), "TestProject2", "TestUser", DateTime.parse("1901-01-01"),
        "TestUser", DateTime.parse("1963-01-01"), DateTime.now(), "Some additional info",
        Seq(), Seq(), None)
      val addr1 = List(ProjectReservedPart(Sequences.nextViitePrimaryKeySeqValue, 5, 207, Some(0L), Some(Continuous), Some(8L), None, None, None, None))
      val project1 = projectService.createRoadLinkProject(rap1)
      mockForProject(project1.id, roadwayAddressMapper.getRoadAddressesByRoadway(roadwayDAO.fetchAllByRoadAndPart(5, 207)).map(toProjectLink(project1)))
      projectService.saveProject(project1.copy(reservedParts = addr1))
      project2 = projectService.createRoadLinkProject(rap2)
      mockForProject(project2.id, roadwayAddressMapper.getRoadAddressesByRoadway(roadwayDAO.fetchAllByRoadAndPart(5, 207)).map(toProjectLink(project2)))

      val exception = intercept[BatchUpdateException] {
        projectService.saveProject(project2.copy(reservedParts = addr1))
      }
      exception.getMessage should include(s"""Key (project_id, road_number, road_part_number)=(${project2.id}, 5, 207) is not present in table "project_reserved_road_part"""")
    }
  }

  test("Test saveProject When new part is added Then return project with new reservation") {
    var count = 0
    runWithRollback {
      reset(mockRoadLinkService)
      val roadlink = RoadLink(12345L.toString, Seq(Point(535605.272, 6982204.22, 85.90899999999965)), 540.3960283713503, State, TrafficDirection.AgainstDigitizing, Some("25.06.2015 03:00:00"), Some("vvh_modified"), InUse, NormalLinkInterface, 749, "")
      val countCurrentProjects = projectService.getAllProjects
      val addresses: List[ProjectReservedPart] = List(ProjectReservedPart(Sequences.nextViitePrimaryKeySeqValue: Long, 5: Long, 207: Long, Some(5L), Some(Discontinuity.apply("jatkuva")), Some(8L), newLength = None, newDiscontinuity = None, newEly = None))
      val roadAddressProject = Project(0, ProjectState.apply(1), "TestProject", "TestUser", DateTime.now(), "TestUser", DateTime.parse("1963-01-01"), DateTime.now(), "Some additional info", Seq(), Seq(), None)
      val saved = projectService.createRoadLinkProject(roadAddressProject)
      mockForProject(saved.id, roadwayAddressMapper.getRoadAddressesByRoadway(roadwayDAO.fetchAllByRoadAndPart(5, 207)).map(toProjectLink(saved)))
      projectService.saveProject(saved.copy(reservedParts = addresses))
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
      val ra = Seq(Roadway(id1, roadwayNumber, roadNumber, roadStartPart, AdministrativeClass.Unknown, Track.Combined, Discontinuous, 0L, 1000L, reversed = false, DateTime.parse("1901-01-01"), None, "tester", Some("test road"), 1L))
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
      val ra = Seq(Roadway(id1, roadwayNumber, roadNumber, roadStartPart, AdministrativeClass.Unknown, Track.Combined, Discontinuous, 0L, 1000L, reversed = false, DateTime.parse("1901-01-01"), None, "tester", Some("test road"), 1L))
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
      val ra = Seq(Roadway(id1, roadwayNumber, roadNumber, roadEndPart, AdministrativeClass.Unknown, Track.Combined, Discontinuous, 0L, 1000L, reversed = false, DateTime.parse("1901-01-01"), None, "tester", Some("test road"), 1L))
      roadwayDAO.create(ra)
      val check = projectService.checkRoadPartsExist(roadNumber, roadStartPart, roadEndPart)
      check should be(Some(ErrorStartingRoadPartNotFound))
    }
  }

  test("Test validateReservations When road parts don't exist Then return error") {
    runWithRollback {
      val roadNumber = 19438
      val roadPartNumber = 1
      val projectEly = 8L
      val errorRoad = s"TIE $roadNumber OSA: $roadPartNumber"
      val reservedPart = ProjectReservedPart(0L, roadNumber, roadPartNumber, Some(1000L), Some(Continuous), Some(projectEly))
      val roadWays = roadwayDAO.fetchAllByRoadAndPart(roadNumber, roadPartNumber)
      projectService.validateReservations(reservedPart, Seq(), roadWays) should be(Some(s"$ErrorFollowingRoadPartsNotFoundInDB $errorRoad"))
    }
  }

  test("Test checkRoadPartsReservable When road does not exist Then return on right should be 0") {
    val roadNumber = 19438
    val roadStartPart = 1
    val roadEndPart = 2
    val roadLink = RoadLink(12345L.toString, Seq(Point(535605.272, 6982204.22, 85.90899999999965)), 540.3960283713503, State, TrafficDirection.AgainstDigitizing, Some("25.06.2015 03:00:00"), Some("vvh_modified"), InUse, NormalLinkInterface, 749, "")
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
    val roadLink = RoadLink(12345L.toString, Seq(Point(535605.272, 6982204.22, 85.90899999999965)), 540.3960283713503, State, TrafficDirection.AgainstDigitizing, Some("25.06.2015 03:00:00"), Some("vvh_modified"), InUse, NormalLinkInterface, 749, "")
    when(mockRoadLinkService.getRoadLinksByLinkIds(any[Set[String]])).thenReturn(Seq(roadLink))
    runWithRollback {
      val id1 = Sequences.nextRoadwayId
      val ra = Seq(Roadway(id1, roadwayNumber, roadNumber, roadStartPart, AdministrativeClass.Unknown, Track.Combined, Discontinuous, 0L, 1000L, reversed = false, DateTime.parse("1901-01-01"), None, "tester", Some("test road"), 8L))
      val ll = LinearLocation(0L, 1, 123456.toString, 0, 1000L, SideCode.TowardsDigitizing, 123456, (CalibrationPointReference.None, CalibrationPointReference.None), Seq(Point(535605.272, 6982204.22, 85.90899999999965)), LinkGeomSource.NormalLinkInterface, roadwayNumber)
      roadwayDAO.create(ra)
      linearLocationDAO.create(Seq(ll))
      val id2 = Sequences.nextRoadwayId
      val rb = Seq(Roadway(id2, roadwayNumber, roadNumber, roadEndPart, AdministrativeClass.Unknown, Track.Combined, Discontinuous, 0L, 1000L, reversed = false, DateTime.parse("1901-01-01"), None, "tester", Some("Test road 2"), 8L))
      roadwayDAO.create(rb)
      val reservationAfterB = projectService.checkRoadPartsReservable(roadNumber, roadStartPart, roadEndPart, 0L)
      reservationAfterB.right.get._1.size should be(2)
      reservationAfterB.right.get._1.map(_.roadNumber).distinct.size should be(1)
      reservationAfterB.right.get._1.map(_.roadNumber).distinct.head should be(roadNumber)
    }
  }

  test("Test getRoadAddressAllProjects When project is created Then return project") {
    var count = 0
    runWithRollback {
      val countCurrentProjects = projectService.getAllProjects
      val roadAddressProject = Project(0, ProjectState.apply(1), "TestProject", "TestUser", DateTime.now(), "TestUser", DateTime.parse("1963-01-01"), DateTime.now(), "Some additional info", Seq(), Seq(), None)
      val project = projectService.createRoadLinkProject(roadAddressProject)
      mockForProject(project.id, roadwayAddressMapper.getRoadAddressesByRoadway(roadwayDAO.fetchAllByRoadAndPart(5, 207)).map(toProjectLink(roadAddressProject)))
      projectService.saveProject(project.copy(reservedParts = Seq(
        ProjectReservedPart(0L, 5, 207, Some(0L), Some(Continuous), Some(8L), None, None, None, None))))
      val countAfterInsertProjects = projectService.getAllProjects
      count = countCurrentProjects.size + 1
      countAfterInsertProjects.size should be(count)
    }
  }

  /** @deprecated Tierekisteri connection has been removed from Viite. TRId to be removed, too. */
  test("Test getRotatingTRProjectId, removeRotatingTRId and addRotatingTRProjectId" +
    "When project has just been created, when project has no TR_ID and when project already has a TR_ID " +
    "Then returning no TR_ID, then returning a TR_ID") {
    runWithRollback {
      val projectId = Sequences.nextViiteProjectId
      val rap = Project(projectId, ProjectState.apply(3), "TestProject", "TestUser", DateTime.parse("2700-01-01"), "TestUser", DateTime.parse("2700-01-01"), DateTime.now(), "Some additional info", List.empty[ProjectReservedPart], Seq(), None)
      runWithRollback {
        projectDAO.create(rap)
        val emptyTrId = projectDAO.fetchTRIdByProjectId(projectId)
        emptyTrId.isEmpty should be(true)
        val projectNone = projectService.fetchProjectById(projectId)
        projectService.removeRotatingTRId(projectId)
        projectNone.head.statusInfo.getOrElse("").length should be(0)
        projectDAO.assignNewProjectTRId(projectId)
        val trId = projectDAO.fetchTRIdByProjectId(projectId)
        trId.nonEmpty should be(true)
        projectService.removeRotatingTRId(projectId)
        emptyTrId.isEmpty should be(true)
        projectDAO.assignNewProjectTRId(projectId)
        projectService.removeRotatingTRId(projectId)
        val project = projectService.fetchProjectById(projectId).head
        project.status should be(ProjectState.Incomplete)
        project.statusInfo.getOrElse("1").length should be > 2
      }
    }
  }

  test("Test projectService.changeDirection When project links have a defined direction Then return project_links with adjusted M addresses and side codes due to the reversing ") {
    runWithRollback {
      val rap = Project(0L, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("1963-01-01"),
        "TestUser", DateTime.parse("1963-01-01"), DateTime.now(), "Some additional info",
        Seq(), Seq(), None)
      val project = projectService.createRoadLinkProject(rap)
      val id = project.id
      mockForProject(id, roadwayAddressMapper.getRoadAddressesByLinearLocation(linearLocationDAO.fetchByRoadways(roadwayDAO.fetchAllBySection(5, 207).map(_.roadwayNumber).toSet)).map(toProjectLink(project)))
      projectService.saveProject(project.copy(reservedParts = Seq(
        ProjectReservedPart(0L, 5, 207, Some(0L), Some(Continuous), Some(8L), None, None, None, None))))
      val projectLinks = projectLinkDAO.fetchProjectLinks(id)
      projectLinkDAO.updateProjectLinksStatus(projectLinks.map(x => x.id).toSet, LinkStatus.Transfer, "test")
      mockForProject(id)
      val fetchedProjectLinks = projectLinkDAO.fetchProjectLinks(id)
      val calculatedProjectLinks = ProjectSectionCalculator.assignMValues(fetchedProjectLinks)

      projectService.changeDirection(id, 5, 207, projectLinks.map(l => LinkToRevert(l.id, l.linkId, l.status.value, l.geometry)), ProjectCoordinates(0, 0, 0), "test") should be(None)
      val updatedProjectLinks = ProjectSectionCalculator.assignMValues(projectLinkDAO.fetchProjectLinks(id))
      val maxBefore = if (projectLinks.nonEmpty) calculatedProjectLinks.maxBy(_.endAddrMValue).endAddrMValue else 0
      val maxAfter = if (updatedProjectLinks.nonEmpty) updatedProjectLinks.maxBy(_.endAddrMValue).endAddrMValue else 0
      maxBefore should be(maxAfter)
      val combined = updatedProjectLinks.filter(_.track == Track.Combined)
      val right = updatedProjectLinks.filter(_.track == Track.RightSide)
      val left = updatedProjectLinks.filter(_.track == Track.LeftSide)

      (combined ++ right).sortBy(_.startAddrMValue).foldLeft(Seq.empty[ProjectLink]) { case (seq, plink) =>
        if (seq.nonEmpty)
          seq.last.endAddrMValue should be(plink.startAddrMValue)
        seq ++ Seq(plink)
      }

      (combined ++ left).sortBy(_.startAddrMValue).foldLeft(Seq.empty[ProjectLink]) { case (seq, plink) =>
        if (seq.nonEmpty)
          seq.last.endAddrMValue should be(plink.startAddrMValue)
        seq ++ Seq(plink)
      }
      projectService.changeDirection(id, 5, 207, projectLinks.map(l => LinkToRevert(l.id, l.linkId, l.status.value, l.geometry)), ProjectCoordinates(0, 0, 0), "test")
      val secondUpdatedProjectLinks = projectLinkDAO.fetchProjectLinks(id)
      projectLinks.sortBy(_.endAddrMValue).map(_.geometry).zip(secondUpdatedProjectLinks.sortBy(_.endAddrMValue).map(_.geometry)).forall { case (x, y) => x == y }
      secondUpdatedProjectLinks.foreach(x => x.reversed should be(false))
    }
  }

  test("Test projectService.preserveSingleProjectToBeTakenToRoadNetwork() " +
    "When project has been created with no reserved parts nor project links " +
    "Then return project status info should be \"\" ") {
    runWithRollback {
      val projectId = Sequences.nextViiteProjectId
      val rap = Project(projectId, ProjectState.apply(ProjectState.InUpdateQueue.value), "TestProject", "TestUser", DateTime.parse("2700-01-01"), "TestUser", DateTime.parse("2700-01-01"), DateTime.now(), "Some additional info", List.empty[ProjectReservedPart], Seq(), None)
      runWithRollback {
        projectDAO.create(rap)
        projectDAO.assignNewProjectTRId(projectId)
        projectService.preserveSingleProjectToBeTakenToRoadNetwork()
        val project = projectService.fetchProjectById(projectId).head
        project.statusInfo.getOrElse("").length should be(0)
      }
    }
  }

  test("Test projectService.preserveSingleProjectToBeTakenToRoadNetwork() " +
    "When project has been created with no reserved parts nor project links " +
    "Then return project status info should be \"Failed to find TR-ID\" ") {
    runWithRollback {
      val projectId = Sequences.nextViiteProjectId
      val rap = Project(projectId, ProjectState.apply(ProjectState.InUpdateQueue.value), "TestProject", "TestUser", DateTime.parse("2700-01-01"), "TestUser", DateTime.parse("2700-01-01"), DateTime.now(), "Some additional info", List.empty[ProjectReservedPart], Seq(), None)
      runWithRollback {
        projectDAO.create(rap)
        projectService.preserveSingleProjectToBeTakenToRoadNetwork()
        val project = projectService.fetchProjectById(projectId).head
        project.statusInfo.getOrElse("") contains "Failed to find TR-ID" should be(true)
      }
    }
  }

  test("Test projectService.saveProject When after project creation we reserve roads to it Then return the count of created project should be increased AND Test projectService.getAllProjects When not specifying a projectId Then return the count of returned projects should be 0 ") {
    var count = 0
    runWithRollback {
      val countCurrentProjects = projectService.getAllProjects
      val id = 0
      val roadAddressProject = Project(id, ProjectState.apply(1), "TestProject", "TestUser", DateTime.now(), "TestUser", DateTime.parse("1997-01-01"), DateTime.now(), "Some additional info", List(), Seq(), None)
      val project = projectService.createRoadLinkProject(roadAddressProject)
      mockForProject(project.id, roadwayAddressMapper.getRoadAddressesByLinearLocation(linearLocationDAO.fetchByRoadways(roadwayDAO.fetchAllBySection(1130, 4).map(_.roadwayNumber).toSet)).map(toProjectLink(roadAddressProject)))
      projectService.saveProject(project.copy(reservedParts = List(
        ProjectReservedPart(Sequences.nextViitePrimaryKeySeqValue: Long, 1130: Long, 4: Long, Some(5L), Some(Discontinuity.apply("jatkuva")), Some(1L), newLength = None, newDiscontinuity = None, newEly = None))))
      val countAfterInsertProjects = projectService.getAllProjects
      count = countCurrentProjects.size + 1
      countAfterInsertProjects.size should be(count)
    }
    runWithRollback {
      projectService.getAllProjects
    } should have size (count - 1)
  }

  test("Test projectService.createRoadLinkProject(), projectService.deleteProject When a user wants to create a new, simple project and then delete it Then return the number of existing projects should increase by 1 and then decrease by 1 as well") {
    var count = 0
    runWithRollback {
      val countCurrentProjects = projectService.getAllProjects
      val roadAddressProject = Project(0, ProjectState.apply(1), "TestProject", "TestUser", DateTime.now(), "TestUser", DateTime.parse("2012-01-01"), DateTime.now(), "Some additional info", Seq(), Seq(), None)
      val project = projectService.createRoadLinkProject(roadAddressProject)
      mockForProject(project.id, roadwayAddressMapper.getRoadAddressesByLinearLocation(linearLocationDAO.fetchByRoadways(roadwayDAO.fetchAllBySection(5, 203).map(_.roadwayNumber).toSet)).map(toProjectLink(roadAddressProject)))
      projectService.saveProject(project.copy(reservedParts = Seq(ProjectReservedPart(0L, 5, 203, Some(100L), Some(Continuous), Some(8L), None, None, None, None))))
      val countAfterInsertProjects = projectService.getAllProjects
      count = countCurrentProjects.size + 1
      countAfterInsertProjects.size should be(count)
      projectService.deleteProject(project.id)
      val projectsAfterOperations = projectService.getAllProjects
      projectsAfterOperations.size should be(count)
      projectsAfterOperations.exists(_.id == project.id) should be(true)
      projectsAfterOperations.find(_.id == project.id).get.status should be(ProjectState.Deleted)
    }
  }

  test("Test projectService.validateProjectDate() When evaluating project links with start date are equal as the project start date Then return no error message") {
    runWithRollback {
      val projDate = DateTime.parse("1990-01-01")
      val addresses = List(ProjectReservedPart(5: Long, 5: Long, 205: Long, Some(5L), Some(Discontinuity.apply("jatkuva")), Some(8L), newLength = None, newDiscontinuity = None, newEly = None))
      val errorMsg = projectService.validateProjectDate(addresses, projDate)
      errorMsg should not be None
    }
  }

  test("Test projectService.validateProjectDate() When evaluating  project links whose start date is a valid one Then return no error message") {
    runWithRollback {
      val projDate = DateTime.parse("2015-01-01")
      val addresses = List(ProjectReservedPart(5: Long, 5: Long, 205: Long, Some(5L), Some(Discontinuity.apply("jatkuva")), Some(8L), newLength = None, newDiscontinuity = None, newEly = None))
      val errorMsg = projectService.validateProjectDate(addresses, projDate)
      errorMsg should be(None)
    }
  }

  test("Test projectService.validateProjectDate() When evaluating  project links whose start date and end dates are valid Then return no error message") {
    runWithRollback {
      val projDate = DateTime.parse("2018-01-01")
      val addresses = List(ProjectReservedPart(5: Long, 5: Long, 205: Long, Some(5L), Some(Discontinuity.apply("jatkuva")), Some(8L), newLength = None, newDiscontinuity = None, newEly = None))
      val errorMsg = projectService.validateProjectDate(addresses, projDate)
      errorMsg should be(None)
    }
  }

  test("Test projectService.getSingleProjectById() When after creation of a new project, with reserved parts Then return only 1 project whose name is the same that was defined. ") {
    var count = 0
    runWithRollback {
      val countCurrentProjects = projectService.getAllProjects
      val addresses: List[ProjectReservedPart] = List(ProjectReservedPart(Sequences.nextViitePrimaryKeySeqValue: Long, 5: Long, 206: Long, Some(5L), Some(Discontinuity.apply("jatkuva")), Some(8L), newLength = None, newDiscontinuity = None, newEly = None))
      val roadAddressProject = Project(0, ProjectState.apply(1), "TestProject", "TestUser", DateTime.now(), "TestUser", DateTime.parse("2017-01-01"), DateTime.now(), "Some additional info", Seq(), Seq(), None)
      val saved = projectService.createRoadLinkProject(roadAddressProject)
      mockForProject(saved.id, roadwayAddressMapper.getRoadAddressesByLinearLocation(linearLocationDAO.fetchByRoadways(roadwayDAO.fetchAllBySection(5, 206).map(_.roadwayNumber).toSet)).map(toProjectLink(saved)))
      projectService.saveProject(saved.copy(reservedParts = addresses))
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
    val roadNumber = 19438
    val roadPartNumber = 1
    val linkId = 12345L.toString
    val roadwayNumber = 8000
    runWithRollback {
      //Creation of Test road
      val id = Sequences.nextRoadwayId
      val ra = Seq(Roadway(id, roadwayNumber, roadNumber, roadPartNumber, AdministrativeClass.Unknown, Track.Combined, Discontinuous, 0L, 1000L, reversed = false, DateTime.parse("1901-01-01"), None, "Tester", Option("test name"), 8L))
      val ll = LinearLocation(0L, 1, linkId, 0, 1000L, SideCode.TowardsDigitizing, 123456, (CalibrationPointReference.None, CalibrationPointReference.None), Seq(Point(535605.272, 6982204.22, 85.90899999999965)), LinkGeomSource.NormalLinkInterface, roadwayNumber)
      val rl = RoadLink(linkId, Seq(Point(0.0, 0.0), Point(0.0, 9.8)), 9.8, State, TrafficDirection.BothDirections, None, None, municipalityCode = 167, sourceId = "")
      when(mockRoadLinkService.getRoadLinksHistoryFromVVH(any[Set[String]])).thenReturn(Seq())
      when(mockRoadLinkService.getRoadLinksByLinkIds(any[Set[String]])).thenReturn(Seq(rl))
      roadwayDAO.create(ra)
      linearLocationDAO.create(Seq(ll))

      //Creation of test project with test links
      val project = Project(projectId, ProjectState.Incomplete, "testiprojekti", "Test", DateTime.now(), "Test",
        DateTime.now(), DateTime.now(), "info",
        List(ProjectReservedPart(Sequences.nextViitePrimaryKeySeqValue: Long, roadNumber: Long, roadPartNumber: Long, Some(5L), Some(Discontinuity.apply("jatkuva")), Some(8L), newLength = None, newDiscontinuity = None, newEly = None)), Seq(), None)
      val proj = projectService.createRoadLinkProject(project)
      val returnedProject = projectService.getSingleProjectById(proj.id).get
      returnedProject.name should be("testiprojekti")
      returnedProject.reservedParts.size should be(1)
      returnedProject.reservedParts.head.roadNumber should be(roadNumber)
    }
  }

  test("Test addNewLinksToProject When reserving part connects to other project road part by same junction Then return error message") {
//    Project1 projectlink is connected to Project2 projectlink at point 1000, 10 and there is no junction which means that Project2 projectlink cannot be created with new action
    runWithRollback {
      val id = Sequences.nextViiteProjectId
      val ra1 = Seq(
        RoadAddress(12345, linearLocationId, 19999L, 1L, AdministrativeClass.State, Track.Combined, Discontinuity.Continuous, 0L, 5L, Some(DateTime.parse("1901-01-01")), None, Some("User"), 1000.toString, 0, 5, TowardsDigitizing, DateTime.now().getMillis, (None, None), Seq(Point(1000.0, 0.0), Point(1000.0, 5.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, roadwayNumber1, Some(DateTime.parse("1901-01-01")), None, None),
        RoadAddress(12346, linearLocationId + 1, 19999L, 1L, AdministrativeClass.State, Track.Combined, Discontinuity.EndOfRoad, 5L, 10L, Some(DateTime.parse("1901-01-01")), None, Some("User"), 1001.toString, 0, 5, TowardsDigitizing, DateTime.now().getMillis, (None, None), Seq(Point(1000.0, 5.0), Point(1000.0, 10.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, roadwayNumber2, Some(DateTime.parse("1901-01-01")), None, None)
      )
      val ra2 = Seq(
        RoadAddress(12347, linearLocationId + 2, 12345L, 1L, AdministrativeClass.State, Track.Combined, Discontinuity.Continuous, 10L, 15L, Some(DateTime.parse("1901-01-01")), None, Some("User"), 1002.toString, 0, 5, TowardsDigitizing, DateTime.now().getMillis, (None, None), Seq(Point(1000.0, 10.0), Point(1000.0, 25.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, roadwayNumber3, Some(DateTime.parse("1901-01-01")), None, None),
        RoadAddress(12348, linearLocationId + 3, 12345L, 1L, AdministrativeClass.State, Track.Combined, Discontinuity.EndOfRoad, 15L, 20L, Some(DateTime.parse("1901-01-01")), None, Some("User"), 1003.toString, 0, 5, TowardsDigitizing, DateTime.now().getMillis, (None, None), Seq(Point(1000.0, 25.0), Point(1000.0, 30.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, roadwayNumber4, Some(DateTime.parse("1901-01-01")), None, None)
      )

      val rap1 = Project(id, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("2700-01-01"), "TestUser", DateTime.parse("1972-03-03"), DateTime.parse("2700-01-01"), "Some additional info", List.empty[ProjectReservedPart], Seq(), None)
      val projectLinks1 = ra1.map {
        toProjectLink(rap1)(_)
      }
      projectDAO.create(rap1)
      projectService.saveProject(rap1)

      val rap2 = Project(id + 1, ProjectState.apply(1), "TestProject2", "TestUser", DateTime.parse("2700-01-01"), "TestUser", DateTime.now(), DateTime.parse("2700-01-01"), "Some additional info", List.empty[ProjectReservedPart], Seq(), None)
      val projectLinks2 = ra2.map {
        toProjectLink(rap2)(_)
      }
      projectDAO.create(rap2)
      projectService.saveProject(rap2)

      when(mockRoadLinkService.getRoadLinksByLinkIds(any[Set[String]])).thenReturn(projectLinks1.map(toRoadLink))
      val response1 = projectService.createProjectLinks(Seq(1000L.toString, 1001L.toString), rap1.id, 56, 207, Track.Combined, Discontinuity.EndOfRoad, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, 8L, "test", "road name")
      response1("success").asInstanceOf[Boolean] should be(true)

      when(mockRoadLinkService.getRoadLinksByLinkIds(any[Set[String]])).thenReturn(projectLinks2.map(toRoadLink))
      val response2 = projectService.createProjectLinks(Seq(1002L.toString,1003L.toString), rap2.id, 55, 206, Track.Combined, Discontinuity.EndOfRoad, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, 8L, "test", "road name")
      response2("success").asInstanceOf[Boolean] should be(false)
      response2("errorMessage").asInstanceOf[String] should be(ErrorWithNewAction)

    }
  }

  test("Test addNewLinksToProject When reserving part already used in other project Then return error message") {
    runWithRollback {
      val idr = Sequences.nextRoadwayId
      val id = Sequences.nextViiteProjectId
      val rap = Project(id, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("2700-01-01"), "TestUser", DateTime.parse("1972-03-03"), DateTime.parse("2700-01-01"), "Some additional info", List.empty[ProjectReservedPart], Seq(), None)
      val projectLink = toProjectLink(rap, LinkStatus.New)(RoadAddress(idr, 123, 5, 207, AdministrativeClass.Unknown, Track.Combined, Discontinuous, 0L, 10L, Some(DateTime.parse("1963-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), 12345L.toString, 0.0, 9.8, SideCode.TowardsDigitizing, 0, (None, None), Seq(Point(0.0, 0.0), Point(0.0, 9.8)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      projectDAO.create(rap)
      projectService.saveProject(rap)


      val rap2 = Project(id + 1, ProjectState.apply(1), "TestProject2", "TestUser", DateTime.parse("2700-01-01"), "TestUser", DateTime.now(), DateTime.parse("2700-01-01"), "Some additional info", List.empty[ProjectReservedPart], Seq(), None)
      val projectLink2 = toProjectLink(rap2, LinkStatus.New)(RoadAddress(idr, 1234, 5, 999, AdministrativeClass.Unknown, Track.Combined, Discontinuous, 0L, 10L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), 12345L.toString, 0.0, 9.8, SideCode.TowardsDigitizing, 0, (None, None), Seq(Point(0.0, 0.0), Point(0.0, 9.8)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      projectDAO.create(rap2)
      projectService.saveProject(rap2)
      val roadwayN = 100000L
      addProjectLinksToProject(LinkStatus.Transfer, Seq(0L, 10L), discontinuity = Discontinuity.Continuous, lastLinkDiscontinuity = Discontinuity.EndOfRoad, project = rap2, roadNumber = 5L, roadPartNumber = 999L, roadwayNumber = roadwayN, withRoadInfo = true)

      val projectLink3 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idr, 12345, 5, 999, AdministrativeClass.Unknown, Track.Combined, Discontinuous, 0L, 10L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), 12345L.toString, 0.0, 9.8, SideCode.TowardsDigitizing, 0, (None, None), Seq(Point(0.0, 0.0), Point(0.0, 9.8)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))

      val calibrationPoints = projectLink.calibrationPoints
      val p = ProjectAddressLink(idr, projectLink.linkId, projectLink.geometry, 1, AdministrativeClass.apply(1), LifecycleStatus.apply(1), projectLink.linkGeomSource, AdministrativeClass.State, None, 111, "Heinola", Some(""), Some("vvh_modified"), projectLink.roadNumber, projectLink.roadPartNumber, 2, -1, projectLink.discontinuity.value, projectLink.startAddrMValue, projectLink.endAddrMValue, projectLink.startMValue, projectLink.endMValue, projectLink.sideCode, calibrationPoints._1, calibrationPoints._2, Anomaly.None, projectLink.status, 12345, 123456, sourceId = "")

      mockForProject(id, Seq(p))

      val message1project1 = projectService.addNewLinksToProject(Seq(projectLink), id, "U", p.linkId, newTransaction = true, Discontinuous).getOrElse("")
      val links = projectLinkDAO.fetchProjectLinks(id)
      links.size should be(0)
      message1project1 should be(RoadNotAvailableMessage) //check that it is reserved in roadaddress table

      val message1project2 = projectService.addNewLinksToProject(Seq(projectLink2), id + 1, "U", p.linkId, newTransaction = true, Discontinuous)
      val links2 = projectLinkDAO.fetchProjectLinks(id + 1)
      links2.size should be(2)
      message1project2 should be(None)

      val message2project1 = projectService.addNewLinksToProject(Seq(projectLink3), id, "U", p.linkId, newTransaction = true, Discontinuous).getOrElse("")
      val links3 = projectLinkDAO.fetchProjectLinks(id)
      links3.size should be(0)
      message2project1 should be("Antamasi tienumero ja tieosanumero ovat jo käytössä. Tarkista syöttämäsi tiedot.")
    }
  }

  test("Test createProjectLinks When adding new links to a new road and roadpart Then discontinuity should be on correct link.") {
    def runTest(reversed: Boolean) = {
      val projectId      = Sequences.nextViiteProjectId
      val roadNumber     = 5
      val roadPartNumber = 207
      val user           = "TestUser"
      val continuous     = Continuous
      val discontinuity  = EndOfRoad
      val rap            = Project(projectId, ProjectState.apply(1), "TestProject", user, DateTime.parse("2700-01-01"), user, DateTime.parse("1972-03-03"), DateTime.parse("2700-01-01"), "Some additional info", List.empty[ProjectReservedPart], Seq(), None)
      val projectLinks   = Seq(
        toProjectLink(rap, LinkStatus.New)(RoadAddress(Sequences.nextRoadwayId, 123, roadNumber, roadPartNumber, AdministrativeClass.Unknown, Track.Combined, continuous, 0L, 0L, Some(DateTime.parse("1963-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), 12345L.toString, 0.0, 9.8, SideCode.Unknown, 0, (None, None), Seq(Point(0.0, 0.0), Point(0.0, 9.8)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0)),
        toProjectLink(rap, LinkStatus.New)(RoadAddress(Sequences.nextRoadwayId, 124, roadNumber, roadPartNumber, AdministrativeClass.Unknown, Track.Combined, continuous, 0L, 0L, Some(DateTime.parse("1963-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), 12346L.toString, 0.0, 19.8, SideCode.Unknown, 0, (None, None), Seq(Point(0.0, 9.8), Point(9.8, 29.6)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      )
      projectDAO.create(rap)
      projectService.saveProject(rap)
      projectReservedPartDAO.reserveRoadPart(projectId, roadNumber, roadPartNumber, user)
      when(mockRoadLinkService.getRoadLinksByLinkIds(any[Set[String]])).thenReturn(projectLinks.map(toRoadLink))

      val longs            = if (reversed) projectLinks.map(_.linkId).reverse else projectLinks.map(_.linkId)
      val message1project1 = projectService.createProjectLinks(longs, rap.id, roadNumber, roadPartNumber, Track.Combined, discontinuity, State, NormalLinkInterface, 8, user, "new road")
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
      val roadNumber     = 5
      val roadPartNumber = 207
      val user           = "TestUser"
      val rap            = Project(projectId, ProjectState.apply(1), "TestProject", user, DateTime.parse("2700-01-01"), user, DateTime.parse("1972-03-03"), DateTime.parse("2700-01-01"), "Some additional info", List.empty[ProjectReservedPart], Seq(), None)
      val projectLink1   = toProjectLink(rap, LinkStatus.New)(RoadAddress(Sequences.nextRoadwayId, 123, roadNumber, roadPartNumber, AdministrativeClass.Unknown, Track.Combined, Continuous, 0L, 0L, Some(DateTime.parse("1963-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), 12345L.toString, 0.0, 9.8, SideCode.Unknown, 0, (None, None), Seq(Point(0.0, 0.0), Point(0.0, 9.8)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink2   = toProjectLink(rap, LinkStatus.New)(RoadAddress(Sequences.nextRoadwayId, 124, roadNumber, roadPartNumber, AdministrativeClass.Unknown, Track.Combined, Continuous, 0L, 0L, Some(DateTime.parse("1963-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), 12346L.toString, 0.0, 19.8, SideCode.Unknown, 0, (None, None), Seq(Point(0.0, 9.8), Point(9.8, 29.6)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      projectDAO.create(rap)
      projectService.saveProject(rap)
      projectReservedPartDAO.reserveRoadPart(projectId, roadNumber, roadPartNumber, user)

      val message1project1 = projectService.addNewLinksToProject(Seq(projectLink1, projectLink2), projectId, user, projectLink1.linkId, newTransaction = true, Discontinuous).getOrElse("")
      val links            = projectLinkDAO.fetchProjectLinks(projectId).toList
      message1project1 should be("")
      links.size should be(2)
      val sortedLinks = links.sortBy(_.linkId)
      sortedLinks.map(_.discontinuity) should be(Seq(Continuous, Discontinuous))
    }
  }

  test("Test addNewLinksToProject When adding new links to existing road and new roadpart Then values should be correct.") {
    runWithRollback {
      val projectId = Sequences.nextViiteProjectId
      val roadNumber = 5
      val roadPartNumber = 206
      val newRoadPartNumber = 207
      val user = "TestUser"
      val rap = Project(projectId, ProjectState.apply(1), "TestProject", user, DateTime.parse("2700-01-01"), user, DateTime.parse("1972-03-03"), DateTime.parse("2700-01-01"), "Some additional info", List.empty[ProjectReservedPart], Seq(), None)
      val projectLinks = Seq(
       toProjectLink(rap, LinkStatus.New)(RoadAddress(Sequences.nextRoadwayId, 125, roadNumber, newRoadPartNumber, AdministrativeClass.Unknown, Track.Combined, Continuous, 0L, 0L, Some(DateTime.parse("1963-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), 12345L.toString, 0.0, 10.4, SideCode.Unknown, 0, (None, None), Seq(Point(532427.945,6998488.475,0.0),Point(532395.164,6998549.616,0.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0)),
       toProjectLink(rap, LinkStatus.New)(RoadAddress(Sequences.nextRoadwayId, 126, roadNumber, newRoadPartNumber, AdministrativeClass.Unknown, Track.Combined, Continuous, 0L, 0L, Some(DateTime.parse("1963-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), 12346L.toString, 0.0, 10.0, SideCode.Unknown, 0, (None, None), Seq(Point(532427.945,6998488.475,0.0),Point(532495.0,6998649.0,0.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      )
      projectDAO.create(rap)
      projectService.saveProject(rap)
      projectReservedPartDAO.reserveRoadPart(projectId, roadNumber, roadPartNumber, user)
      projectReservedPartDAO.reserveRoadPart(projectId, roadNumber, newRoadPartNumber, user)
      val roadwayN = 100000L
      addProjectLinksToProject(LinkStatus.UnChanged, Seq(0L, 10L, 20L), discontinuity = Discontinuity.Continuous, lastLinkDiscontinuity = Discontinuity.EndOfRoad, project = rap, roadNumber = roadNumber, roadPartNumber = roadPartNumber, roadwayNumber = roadwayN, withRoadInfo = true)

      val message1project1 = projectService.addNewLinksToProject(projectLinks, projectId, user, projectLinks.head.linkId, newTransaction = true, Discontinuous).getOrElse("")
      val links = projectLinkDAO.fetchProjectLinks(projectId).filter(_.status == LinkStatus.New)
      message1project1 should be("")
      links.size should be(2)
      val sortedLinks = links.sortBy(_.linkId)
      sortedLinks.map(_.discontinuity) should be(Seq(Continuous, Discontinuous))
    }
  }

  test("Test addNewLinksToProject When adding new links to existing road and existing roadpart Then values should be correct.") {
    runWithRollback {
      val projectId = Sequences.nextViiteProjectId
      val roadNumber = 5
      val roadPartNumber = 206
      val user = "TestUser"
      val rap = Project(projectId, ProjectState.apply(1), "TestProject", user, DateTime.parse("2700-01-01"), user, DateTime.parse("2700-01-01"), DateTime.parse("2700-01-01"), "Some additional info", List.empty[ProjectReservedPart], Seq(), None)
      val projectLinks = Seq(
        toProjectLink(rap, LinkStatus.New)(RoadAddress(Sequences.nextRoadwayId, 125, roadNumber, roadPartNumber, AdministrativeClass.Unknown, Track.Combined, Continuous, 0L, 0L, Some(DateTime.parse("1963-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), 12345L.toString, 0.0, 10.4, SideCode.Unknown, 0, (None, None), Seq(Point(0,20),Point(0,30,0.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0)),
        toProjectLink(rap, LinkStatus.New)(RoadAddress(Sequences.nextRoadwayId, 126, roadNumber, roadPartNumber, AdministrativeClass.Unknown, Track.Combined, Continuous, 0L, 0L, Some(DateTime.parse("1963-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), 12346L.toString, 0.0, 10.0, SideCode.Unknown, 0, (None, None), Seq(Point(0.0,30.0),Point(0.0,40.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      )
      projectDAO.create(rap)
      val savedp = projectService.saveProject(rap)
      projectReservedPartDAO.reserveRoadPart(projectId, roadNumber, roadPartNumber, user)
      val roadwayN = 100000L
      addProjectLinksToProject(LinkStatus.UnChanged, Seq(0L, 10L, 20L), discontinuity = Discontinuity.Continuous, lastLinkDiscontinuity = Discontinuity.EndOfRoad, project = rap, roadNumber = roadNumber, roadPartNumber = roadPartNumber, roadwayNumber = roadwayN, withRoadInfo = true)
      val x = projectReservedPartDAO.fetchReservedRoadPart(roadNumber, roadPartNumber)
      val y = projectReservedPartDAO.fetchReservedRoadParts(projectId)

      val message1project1 = projectService.addNewLinksToProject(projectLinks, projectId, user, projectLinks.head.linkId, newTransaction = true, Discontinuous).getOrElse("")
      val links = projectLinkDAO.fetchProjectLinks(projectId).filter(_.status == LinkStatus.New)
      message1project1 should be("")
      links.size should be(2)
      val sortedLinks = links.sortBy(_.linkId)
      sortedLinks.map(_.discontinuity) should be(Seq(Continuous, Discontinuous))
    }
  }

  test("Test defaultSectionCalculatorStrategy.assignMValues() " +
       "When a new road and part has the end of road set " +
       "Then calculated addresses should increase towards the end of the road.") {

    def runTest(reversed: Boolean) = {
      val user           = "TestUser"
      val roadNumber     = 10000
      val roadPartNumber = 1
      val project_id     = Sequences.nextViiteProjectId

      val headLink    = ProjectLink(1000, roadNumber, roadPartNumber, Combined, Continuous, 0, 0, 0, 0, None, None, Some(user), 5174997.toString, 0.0, 152.337, SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP), List(Point(536938.0, 6984394.0, 0.0), Point(536926.0, 6984546.0, 0.0)), project_id, LinkStatus.New, AdministrativeClass.Municipality, FrozenLinkInterface, 152.337, 0, 0, 8, false, None, 1548802841000L, 0, Some("testroad"), None, None, None, None, None, None)
      val lastLink    = ProjectLink(1009, roadNumber, roadPartNumber, Combined, Continuous, 0, 0, 0, 0, None, None, Some(user), 5174584.toString, 0.0, 45.762, SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP), List(Point(536528.0, 6984439.0, 0.0), Point(536522.0, 6984484.0, 0.0)), project_id, LinkStatus.New, AdministrativeClass.Municipality, FrozenLinkInterface, 45.762, 0, 0, 8, false, None, 1551999616000L, 0, Some("testroad"), None, None, None, None, None, None)
      val middleLinks = Seq(
        ProjectLink(1001,roadNumber,roadPartNumber,Combined,Continuous,0,0,0,0,None,None,Some(user),5175001.toString,0.0,72.789,SideCode.Unknown,(NoCP,NoCP),(NoCP,NoCP),List(Point(536938.0,6984394.0,0.0), Point(536865.0,6984398.0,0.0)),project_id,LinkStatus.New,AdministrativeClass.Municipality,FrozenLinkInterface,72.789,0,0,8,false,None,1500418814000L,0,Some("testroad"),None,None,None,None,None,None),
        ProjectLink(1002,roadNumber,roadPartNumber,Combined,Continuous,0,0,0,0,None,None,Some(user),5174998.toString,0.0,84.091,SideCode.Unknown,(NoCP,NoCP),(NoCP,NoCP),List(Point(536781.0,6984396.0,0.0), Point(536865.0,6984398.0,0.0)),project_id,LinkStatus.New,AdministrativeClass.Municipality,FrozenLinkInterface,84.091,0,0,8,false,None,1500418814000L,0,Some("testroad"),None,None,None,None,None,None),
        ProjectLink(1003,roadNumber,roadPartNumber,Combined,Continuous,0,0,0,0,None,None,Some(user),5174994.toString,0.0,129.02,SideCode.Unknown,(NoCP,NoCP),(NoCP,NoCP),List(Point(536781.0,6984396.0,0.0), Point(536773.0,6984525.0,0.0)),project_id,LinkStatus.New,AdministrativeClass.Municipality,FrozenLinkInterface,129.02,0,0,8,false,None,1500418814000L,0,Some("testroad"),None,None,None,None,None,None),
        ProjectLink(1004,roadNumber,roadPartNumber,Combined,Continuous,0,0,0,0,None,None,Some(user),5174545.toString,0.0,89.803,SideCode.Unknown,(NoCP,NoCP),(NoCP,NoCP),List(Point(536684.0,6984513.0,0.0), Point(536773.0,6984525.0,0.0)),project_id,LinkStatus.New,AdministrativeClass.Municipality,FrozenLinkInterface,89.803,0,0,8,false,None,1500418814000L,0,Some("testroad"),None,None,None,None,None,None),
        ProjectLink(1005,roadNumber,roadPartNumber,Combined,Continuous,0,0,0,0,None,None,Some(user),5174996.toString,0.0,138.959,SideCode.Unknown,(NoCP,NoCP),(NoCP,NoCP),List(Point(536694.0,6984375.0,0.0), Point(536684.0,6984513.0,0.0)),project_id,LinkStatus.New,AdministrativeClass.Municipality,FrozenLinkInterface,138.959,0,0,8,false,None,1551999616000L,0,Some("testroad"),None,None,None,None,None,None),
        ProjectLink(1006,roadNumber,roadPartNumber,Combined,Continuous,0,0,0,0,None,None,Some(user),5175004.toString,0.0,41.233,SideCode.Unknown,(NoCP,NoCP),(NoCP,NoCP),List(Point(536653.0,6984373.0,0.0), Point(536694.0,6984375.0,0.0)),project_id,LinkStatus.New,AdministrativeClass.Municipality,FrozenLinkInterface,41.233,0,0,8,false,None,1551999616000L,0,Some("testroad"),None,None,None,None,None,None),
        ProjectLink(1007,roadNumber,roadPartNumber,Combined,Continuous,0,0,0,0,None,None,Some(user),5174936.toString,0.0,117.582,SideCode.Unknown,(NoCP,NoCP),(NoCP,NoCP),List(Point(536535.0,6984364.0,0.0), Point(536653.0,6984373.0,0.0)),project_id,LinkStatus.New,AdministrativeClass.Municipality,FrozenLinkInterface,117.582,0,0,8,false,None,1500418814000L,0,Some("testroad"),None,None,None,None,None,None),
        ProjectLink(1008,roadNumber,roadPartNumber,Combined,Continuous,0,0,0,0,None,None,Some(user),5174956.toString,0.0,75.055,SideCode.Unknown,(NoCP,NoCP),(NoCP,NoCP),List(Point(536535.0,6984364.0,0.0), Point(536528.0,6984439.0,0.0)),project_id,LinkStatus.New,AdministrativeClass.Municipality,FrozenLinkInterface,75.055,0,0,8,false,None,1500418814000L,0,Some("testroad"),None,None,None,None,None,None)
      )

      val projectLinks = if (reversed)
        headLink +: middleLinks :+ lastLink.copy(discontinuity = EndOfRoad)
      else
        headLink.copy(discontinuity = EndOfRoad) +: middleLinks :+ lastLink

      val calculatedProjectLinks = ProjectSectionCalculator.assignMValues(projectLinks)
      val maxLink                = calculatedProjectLinks.maxBy(_.endAddrMValue)
      if (reversed)
        maxLink.id should be(lastLink.id)
      else
        maxLink.id should be(headLink.id)
      maxLink.endAddrMValue should be > 0L
      maxLink.discontinuity should be(EndOfRoad)
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
      val road     = 170
      val roadPart = 10
      val user     = "test user"
      val roadAddressProject = Project(testProjectId, Incomplete, "preFill", user, DateTime.now(), user,
        DateTime.now().plusMonths(2), DateTime.now(), "", Seq(), Seq(), None, None)

      val projectLinks = roadwayAddressMapper.getRoadAddressesByRoadway(roadwayDAO.fetchAllByRoadAndPart(road, roadPart)).map(toProjectLink(roadAddressProject))
      projectReservedPartDAO.reserveRoadPart(testProjectId, road, roadPart, user)
      projectLinkDAO.create(projectLinks)
      projectService.fetchPreFillData(projectLinks.head.linkId, roadAddressProject.id) should be(Right(PreFillInfo(road, roadPart, "", RoadNameSource.UnknownSource)))
    }
  }

  test("Test projectService.fetchPreFillData " +
       "When supplied a sequence of one valid RoadLink " +
       "Then return the correct pre-fill data for that link and it's correct name.") {
    runWithRollback {
      val road     = 170
      val roadPart = 10
      val user     = "test user"
      val roadAddressProject = Project(testProjectId, Incomplete, "preFill", user, DateTime.now(), user,
        DateTime.now().plusMonths(2), DateTime.now(), "", Seq(), Seq(), None, None)

      sqlu"""INSERT INTO ROAD_NAME VALUES (nextval('ROAD_NAME_SEQ'), $road, 'road name test', TIMESTAMP '2018-03-23 12:26:36.000000', null, TIMESTAMP '2018-03-23 12:26:36.000000', null, 'test user', TIMESTAMP '2018-03-23 12:26:36.000000')""".execute
      projectReservedPartDAO.reserveRoadPart(testProjectId, road, roadPart, user)
      val projectLinks = roadwayAddressMapper.getRoadAddressesByRoadway(roadwayDAO.fetchAllByRoadAndPart(road, roadPart)).map(toProjectLink(roadAddressProject))
      projectLinkDAO.create(projectLinks)
      projectService.fetchPreFillData(projectLinks.head.linkId, roadAddressProject.id) should be(Right(PreFillInfo(road, roadPart, "road name test", RoadNameSource.RoadAddressSource)))
    }
  }

  test("Test projectService.fetchPreFillData() " +
       "When getting road name data from the Project Link Name table " +
       "Then return the correct info with road name pre filled, and the correct sources") {
    runWithRollback{
      val road     = 170
      val roadPart = 10
      val user     = "test user"
      val roadAddressProject = Project(testProjectId, Incomplete, "preFill", user, DateTime.now(), user,
        DateTime.now().plusMonths(2), DateTime.now(), "", Seq(), Seq(), None, None)

      sqlu""" Insert into project_link_name values (nextval('viite_general_seq'), ${roadAddressProject.id}, $road, 'TestRoadName_Project_Link')""".execute
      projectReservedPartDAO.reserveRoadPart(testProjectId, road, roadPart, user)
      val projectLinks = roadwayAddressMapper.getRoadAddressesByRoadway(roadwayDAO.fetchAllByRoadAndPart(road, roadPart)).map(toProjectLink(roadAddressProject))
      projectLinkDAO.create(projectLinks)
      projectService.fetchPreFillData(projectLinks.head.linkId, roadAddressProject.id) should be(Right(PreFillInfo(road, roadPart, "TestRoadName_Project_Link", RoadNameSource.ProjectLinkSource)))
    }
  }

  test("Test projectService.fetchPreFillData() " +
       "When road name data exists both in Project Link Name table and Road_Name table " +
       "Then return the correct info with road name pre filled and the correct sources, in this case RoadAddressSource") {
    runWithRollback{

      val road     = 170
      val roadPart = 10
      val user     = "test user"

      val roadAddressProject = Project(testProjectId, Incomplete, "preFill", user, DateTime.now(), user,
        DateTime.now().plusMonths(2), DateTime.now(), "", Seq(), Seq(), None, None)

      sqlu"""INSERT INTO ROAD_NAME VALUES (nextval('ROAD_NAME_SEQ'), $road, 'road name test', TIMESTAMP '2018-03-23 12:26:36.000000', null, TIMESTAMP '2018-03-23 12:26:36.000000', null, 'test user', TIMESTAMP '2018-03-23 12:26:36.000000')""".execute
      sqlu""" Insert into project_link_name values (nextval('viite_general_seq'), ${roadAddressProject.id}, $road, 'TestRoadName_Project_Link')""".execute
      projectReservedPartDAO.reserveRoadPart(testProjectId, road, roadPart, user)

      val projectLinks = roadwayAddressMapper.getRoadAddressesByRoadway(roadwayDAO.fetchAllByRoadAndPart(road, 10)).map(toProjectLink(roadAddressProject))
      projectLinkDAO.create(projectLinks)
      projectService.fetchPreFillData(projectLinks.head.linkId, roadAddressProject.id) should be(Right(PreFillInfo(road, 10, "road name test", RoadNameSource.RoadAddressSource)))
    }
  }

  test("Test createProjectLinks When project link is created for part not reserved Then should return error message") {
    runWithRollback {
      val rap = Project(0L, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("1901-01-01"),
        "TestUser", DateTime.parse("1901-01-01"), DateTime.now(), "Some additional info",
        Seq(), Seq(), None)
      val newLink = Seq(ProjectLink(-1000L, 5L, 206L, Track.apply(99), Discontinuity.Continuous, 0L, 50L, 0L, 50L, None, None, None, 12345L.toString, 0.0, 43.1, SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP), Seq(Point(468.5, 0.5), Point(512.0, 0.0)), 0L, LinkStatus.Unknown, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, 43.1, 49704009, 1000570, 8L, reversed = false, None, 123456L, 12345L))
      val project = projectService.createRoadLinkProject(rap)
      when(mockRoadLinkService.getRoadLinksByLinkIds(any[Set[String]])).thenReturn(newLink.map(toRoadLink))
      val response = projectService.createProjectLinks(Seq(12345L.toString), project.id, 5, 206, Track.Combined, Discontinuity.Continuous, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, 8L, "test", "road name")
      response("success").asInstanceOf[Boolean] should be(false)
      response("errorMessage").asInstanceOf[String] should be(RoadNotAvailableMessage)
    }
  }

  test("Test projectService.updateProjectLinks When applying the operation \"Numerointi\" to a road part that is ALREADY reserved in a different project Then return an error message") {
    runWithRollback {
      val rap1 = Project(0L, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("1963-01-01"),
        "TestUser", DateTime.parse("1963-01-01"), DateTime.now(), "Some additional info",
        Seq(), Seq(), None)
      val rap2 = Project(0L, ProjectState.apply(1), "TestProject2", "TestUser", DateTime.parse("2012-01-01"),
        "TestUser", DateTime.parse("2017-01-01"), DateTime.now(), "Some additional info",
        Seq(), Seq(), None)
      val addr1 = List(ProjectReservedPart(Sequences.nextViitePrimaryKeySeqValue, 5, 207, Some(0L), Some(Continuous), Some(8L), None, None, None, None))
      val addr2 = List(ProjectReservedPart(Sequences.nextViitePrimaryKeySeqValue, 5, 206, Some(5L), Some(Discontinuity.apply("jatkuva")), Some(8L), None, None, None, None))
      val project1 = projectService.createRoadLinkProject(rap1)

      mockForProject(project1.id, roadAddressServiceRealRoadwayAddressMapper.getRoadAddressWithRoadAndPart(5, 207).map(toProjectLink(project1)))
      projectService.saveProject(project1.copy(reservedParts = addr1))

      val project2 = projectService.createRoadLinkProject(rap2)
      mockForProject(project2.id, roadAddressServiceRealRoadwayAddressMapper.getRoadAddressWithRoadAndPart(5, 206).map(toProjectLink(project2)))
      projectService.saveProject(project2.copy(reservedParts = addr2))

      val response = projectService.updateProjectLinks(project1.id, Set(), projectLinkDAO.fetchProjectLinks(project1.id).map(_.linkId), LinkStatus.Numbering, "TestUser", 5, 206, 0, None, AdministrativeClass.State.value, Discontinuity.Continuous.value, Some(8))
      response.get should be("Antamasi tienumero ja tieosanumero ovat jo käytössä. Tarkista syöttämäsi tiedot.")
    }
  }

  test("Test projectService.updateProjectLinks When applying the operation \"Numerointi\" to a road part that is ALREADY reserved in a different project Then return an error message" +
    "renumber a project link to a road part not reserved with end date null") {
    runWithRollback {
      val rap1 = Project(0L, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("1963-01-01"),
        "TestUser", DateTime.parse("1963-01-01"), DateTime.now(), "Some additional info",
        Seq(), Seq(), None)
      val addr1 = List(ProjectReservedPart(Sequences.nextViitePrimaryKeySeqValue, 5, 207, Some(0L), Some(Continuous), Some(8L), None, None, None, None))
      val project1 = projectService.createRoadLinkProject(rap1)
      mockForProject(project1.id, roadAddressServiceRealRoadwayAddressMapper.getRoadAddressWithRoadAndPart(5, 207).map(toProjectLink(project1)))
      projectService.saveProject(project1.copy(reservedParts = addr1))
      val response = projectService.updateProjectLinks(project1.id, Set(), projectLinkDAO.fetchProjectLinks(project1.id).map(_.linkId), LinkStatus.Numbering, "TestUser", 5, 203, 0, None, AdministrativeClass.State.value, Discontinuity.Continuous.value, Some(8))
      response.get should be("Antamasi tienumero ja tieosanumero ovat jo käytössä. Tarkista syöttämäsi tiedot.")
    }
  }

  test("Test projectService.updateProjectLinks When applying the operation \"Numerointi\" to a road part that already has some other action applied Then return an error message") {
    runWithRollback {
      val rap1 = Project(0L, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("1963-01-01"),
        "TestUser", DateTime.parse("1963-01-01"), DateTime.now(), "Some additional info",
        Seq(), Seq(), None)
      val addr1 = List(ProjectReservedPart(Sequences.nextViitePrimaryKeySeqValue, 5, 207, Some(0L), Some(Continuous), Some(8L), None, None, None, None))
      val project1 = projectService.createRoadLinkProject(rap1)
      mockForProject(project1.id, roadAddressServiceRealRoadwayAddressMapper.getRoadAddressWithRoadAndPart(5, 207).map(toProjectLink(project1)))
      projectService.saveProject(project1.copy(reservedParts = addr1))
      projectService.updateProjectLinks(project1.id, Set(), projectLinkDAO.fetchProjectLinks(project1.id).map(_.linkId), LinkStatus.Terminated, "TestUser", 5, 207, 0, None, AdministrativeClass.State.value, Discontinuity.Continuous.value, Some(8))
      val response = projectService.updateProjectLinks(project1.id, Set(), projectLinkDAO.fetchProjectLinks(project1.id).map(_.linkId), LinkStatus.Numbering, "TestUser", 5, 308, 0, None, AdministrativeClass.State.value, Discontinuity.Continuous.value, Some(8))
      response.get should be(ErrorOtherActionWithNumbering)
    }
  }

  test("Test projectLinkDAO.getProjectLinks() When after the \"Numerointi\" operation on project links of a newly created project Then the last returned link should have a discontinuity of \"EndOfRoad\", all the others should be \"Continuous\" ") {
    runWithRollback {
      val rap1 = Project(0L, ProjectState.apply(1), "TestProject", "TestUser", DateTime.now(),
        "TestUser", DateTime.now().plusDays(1), DateTime.now(), "Some additional info",
        Seq(), Seq(), None)
      val addr1 = List(ProjectReservedPart(Sequences.nextViitePrimaryKeySeqValue, 5, 207, Some(0L), Some(Continuous), Some(8L), None, None, None, None))
      val project1 = projectService.createRoadLinkProject(rap1)
      mockForProject(project1.id, roadAddressServiceRealRoadwayAddressMapper.getRoadAddressWithRoadAndPart(5, 207).map(toProjectLink(project1)))
      projectService.saveProject(project1.copy(reservedParts = addr1))
      val fetchedProjectLinks = projectLinkDAO.fetchProjectLinks(project1.id)
      projectService.updateProjectLinks(project1.id, Set(fetchedProjectLinks.maxBy(_.endAddrMValue).id), fetchedProjectLinks.map(_.linkId), LinkStatus.Numbering, "TestUser", 6, 207, 0, None, AdministrativeClass.State.value, Discontinuity.EndOfRoad.value, Some(8))

      //Descending order by end address
      val projectLinks = projectLinkDAO.fetchProjectLinks(project1.id).sortBy(-_.endAddrMValue)
      projectLinks.tail.forall(_.discontinuity == Discontinuity.Continuous) should be(true)
      projectLinks.head.discontinuity should be(Discontinuity.EndOfRoad)
    }
  }

  test("Test revertLinksByRoadPart When new roads have name Then the revert should remove the road name") {
    runWithRollback {
      val testRoad: (Long, Long, String) = {
        (99999L, 1L, "Test name")
      }

      val (project, links) = util.setUpProjectWithLinks(LinkStatus.New, Seq(0L, 10L, 20L), roads = Seq(testRoad), discontinuity = Discontinuity.Continuous)
      val roadLinks = links.map(toRoadLink)
      ProjectLinkNameDAO.get(99999L, project.id).get.roadName should be("Test name")
      val linksToRevert = links.map(l => {
        LinkToRevert(l.id, l.linkId, l.status.value, l.geometry)
      })
      projectService.revertLinksByRoadPart(project.id, 99999L, 1L, linksToRevert, "Test User")
      ProjectLinkNameDAO.get(99999L, project.id) should be(None)
    }
  }

  test("Test projectService.saveProject() When updating a project to remove ALL it's reserved roads Then the project should lose it's associated road names.") {
    runWithRollback {
      val testRoad: (Long, Long, String) = {
        (99999L, 1L, "Test name")
      }
      val (project, _) = util.setUpProjectWithLinks(LinkStatus.Transfer, Seq(0L, 10L, 20L), roads = Seq(testRoad), discontinuity = Discontinuity.Continuous)
      ProjectLinkNameDAO.get(99999L, project.id).get.roadName should be("Test name")
      projectService.saveProject(project.copy(reservedParts = Seq()))
      ProjectLinkNameDAO.get(99999L, project.id) should be(None)
    }
  }

  test("Test projectService.deleteProject() When deleting a project Then if no other project makes use of a road name then said name should be removed as well.") {
    runWithRollback {
      val testRoad: (Long, Long, String) = {
        (99999L, 1L, "Test name")
      }
      val (project, _) = util.setUpProjectWithLinks(LinkStatus.Transfer, Seq(0L, 10L, 20L), roads = Seq(testRoad), discontinuity = Discontinuity.Continuous)
      ProjectLinkNameDAO.get(99999L, project.id).get.roadName should be("Test name")
      projectService.deleteProject(project.id)
      ProjectLinkNameDAO.get(99999L, project.id) should be(None)
    }
  }

  test("Test revertLinks When road exists in another project Then the new road name should not be removed") {
    runWithRollback {
      val testRoads: Seq[(Long, Long, String)] = Seq((99999L, 1L, "Test name"), (99999L, 2L, "Test name"))
      val (project, links) = util.setUpProjectWithLinks(LinkStatus.Transfer, Seq(0L, 10L, 20L), roads = testRoads, discontinuity = Discontinuity.Continuous)
      ProjectLinkNameDAO.get(99999L, project.id).get.roadName should be("Test name")
      val linksToRevert = links.map(l => {
        LinkToRevert(l.id, l.linkId, l.status.value, l.geometry)
      })
      val roadLinks = links.map(toRoadLink)
      when(mockRoadLinkService.getCurrentAndComplementaryRoadLinks(any[Set[String]])).thenReturn(roadLinks)
      projectService.revertLinksByRoadPart(project.id, 99999L, 1L, linksToRevert, "Test User")
      projectLinkDAO.fetchProjectLinks(project.id).count(_.roadPartNumber == 2L) should be(2)
      ProjectLinkNameDAO.get(99999L, project.id).get.roadName should be("Test name")
    }
  }

  test("Test revertLinks When road name exists Then the revert should put the original name in the project link name if no other exists in project") {
    runWithRollback {
      val testRoad: (Long, Long, String) = {
        (99999L, 1L, "new name")
      }
      val (project, links) = util.setUpProjectWithLinks(LinkStatus.Transfer, Seq(0L, 10L, 20L), roads = Seq(testRoad), discontinuity = Discontinuity.Continuous)
      val roadLinks = links.map(toRoadLink)
      sqlu"""INSERT INTO ROAD_NAME VALUES (nextval('ROAD_NAME_SEQ'), 99999, 'test name', current_date, null, current_date, null, 'test user', current_date)""".execute
      ProjectLinkNameDAO.get(99999L, project.id).get.roadName should be("new name")
      val linksToRevert = links.map(l => {
        LinkToRevert(l.id, l.linkId, l.status.value, l.geometry)
      })
      when(mockRoadLinkService.getCurrentAndComplementaryRoadLinks(any[Set[String]])).thenReturn(roadLinks)
      projectService.revertLinksByRoadPart(project.id, 99999L, 1L, linksToRevert, "Test User")
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

      val rap = Project(0L, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("1901-01-01"),
        "TestUser", DateTime.parse("1901-01-01"), DateTime.now(), "Some additional info",
        Seq(), Seq(), None)

      val pl1 = ProjectLink(-1000L, 9999L, 1L, Track.apply(0), Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None, None, 12345L.toString, 0.0, 0.0, SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP), Seq(Point(10.0, 10.0), Point(20.0, 10.0)), 0L, LinkStatus.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(Seq(Point(10.0, 10.0), Point(20.0, 10.0))), 0L, 0, 0, reversed = false, None, 86400L)
      val pl2 = ProjectLink(-1000L, 9999L, 1L, Track.apply(2), Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None, None, 12346L.toString, 0.0, 0.0, SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP), Seq(Point(20.0, 10.0), Point(30.0, 15.0)), 0L, LinkStatus.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(Seq(Point(20.0, 10.0), Point(30.0, 15.0))), 0L, 0, 0, reversed = false, None, 86400L)
      val pl3 = ProjectLink(-1000L, 9999L, 1L, Track.apply(2), Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None, None, 12347L.toString, 0.0, 0.0, SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP), Seq(Point(30.0, 15.0), Point(45.0, 10.0)), 0L, LinkStatus.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(Seq(Point(30.0, 15.0), Point(45.0, 10.0))), 0L, 0, 0, reversed = false, None, 86400L)
      val pl4 = ProjectLink(-1000L, 9999L, 1L, Track.apply(1), Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None, None, 12348L.toString, 0.0, 0.0, SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP), Seq(Point(20.0, 10.0), Point(25.0, 5.0), Point(45.0, 10.0)), 0L, LinkStatus.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(Seq(Point(20.0, 10.0), Point(25.0, 5.0), Point(45.0, 10.0))), 0L, 0, 0, reversed = false, None, 86400L)
      val pl5 = ProjectLink(-1000L, 9999L, 1L, Track.apply(0), Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None, None, 12349L.toString, 0.0, 0.0, SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP), Seq(Point(45.0, 10.0), Point(60.0, 10.0)), 0L, LinkStatus.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(Seq(Point(45.0, 10.0), Point(60.0, 10.0))), 0L, 0, 0, reversed = false, None, 86400L)

      val project = projectService.createRoadLinkProject(rap)
      when(mockRoadLinkService.getRoadLinksByLinkIds(any[Set[String]])).thenReturn(Seq(pl1).map(toRoadLink))
      projectService.createProjectLinks(Seq(12345L.toString), project.id, 9999, 1, Track.Combined, Discontinuity.Continuous, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, 8L, "test", "road name")
      when(mockRoadLinkService.getRoadLinksByLinkIds(any[Set[String]])).thenReturn(Seq(pl4).map(toRoadLink))
      projectService.createProjectLinks(Seq(12348L.toString), project.id, 9999, 1, Track.RightSide, Discontinuity.Continuous, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, 8L, "test", "road name")
      when(mockRoadLinkService.getRoadLinksByLinkIds(any[Set[String]])).thenReturn(Seq(pl2, pl3).map(toRoadLink))
      projectService.createProjectLinks(Seq(12346L.toString, 12347L.toString), project.id, 9999, 1, Track.LeftSide, Discontinuity.Continuous, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, 8L, "test", "road name")
      when(mockRoadLinkService.getRoadLinksByLinkIds(any[Set[String]])).thenReturn(Seq(pl5).map(toRoadLink))
      projectService.createProjectLinks(Seq(12349L.toString), project.id, 9999, 1, Track.Combined, Discontinuity.Continuous, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, 8L, "test", "road name")
      val links = projectLinkDAO.fetchProjectLinks(project.id).sortBy(_.startAddrMValue)

      links.filterNot(_.track == Track.RightSide).sortBy(_.endAddrMValue).scanLeft(0.0) { case (m, pl) =>
        m should be(pl.startAddrMValue)
        pl.endAddrMValue
      }
      links.filterNot(_.track == Track.LeftSide).sortBy(_.endAddrMValue).scanLeft(0.0) { case (m, pl) =>
        m should be(pl.startAddrMValue)
        pl.endAddrMValue
      }

      links.sortBy(_.endAddrMValue).scanLeft(0.0) { case (m, pl) =>
        if (pl.calibrationPoints._1.nonEmpty) {
          pl.calibrationPoints._1.get.addressMValue should be(pl.startAddrMValue)
          pl.calibrationPoints._1.get.segmentMValue should be(pl.endMValue - pl.startMValue)
        }
        if (pl.calibrationPoints._2.nonEmpty) {
          pl.calibrationPoints._2.get.addressMValue should be(pl.endAddrMValue)
          pl.calibrationPoints._2.get.addressMValue should be(pl.endMValue)
        }
        0.0 //any double val, needed for expected type value in recursive scan
      }


      val linkidToIncrement = pl1.linkId
      val idsToIncrement = links.filter(_.linkId == linkidToIncrement).head.id
      val valueToIncrement = 2.0
      val newEndAddressValue = Seq(links.filter(_.linkId == linkidToIncrement).head.endAddrMValue.toInt, valueToIncrement.toInt).sum
      projectService.updateProjectLinks(project.id, Set(idsToIncrement), Seq(), LinkStatus.New, "TestUserTwo", 9999, 1, 0, Some(newEndAddressValue), 1L, 5) should be(None)
      val linksAfterGivenAddrMValue = projectLinkDAO.fetchProjectLinks(project.id)

      /**
        * Test 1.
        */
      linksAfterGivenAddrMValue.filterNot(_.track == Track.RightSide).sortBy(_.endAddrMValue).scanLeft(0.0) { case (m, pl) =>
        m should be(pl.startAddrMValue)
        pl.endAddrMValue
      }
      linksAfterGivenAddrMValue.filterNot(_.track == Track.LeftSide).sortBy(_.endAddrMValue).scanLeft(0.0) { case (m, pl) =>
        m should be(pl.startAddrMValue)
        pl.endAddrMValue
      }

      /**
        * Test 2.
        */
      linksAfterGivenAddrMValue.sortBy(_.endAddrMValue).scanLeft(0.0) { case (m, pl) =>
        if (pl.calibrationPoints._1.nonEmpty) {
          pl.calibrationPoints._1.get.addressMValue should be(pl.startAddrMValue)
          pl.calibrationPoints._1.get.segmentMValue should be(pl.endMValue - pl.startMValue)
        }
        if (pl.calibrationPoints._2.nonEmpty) {
          pl.calibrationPoints._2.get.addressMValue should be(pl.endAddrMValue)
          pl.calibrationPoints._2.get.addressMValue should be(pl.endMValue)
        }
        0.0 //any double val, needed for expected type value in recursive scan
      }

      //only link and links after linkidToIncrement should be extended
      val extendedLink = links.filter(_.linkId == linkidToIncrement).head
      val linksBefore = links.filter(_.endAddrMValue >= extendedLink.endAddrMValue).sortBy(_.endAddrMValue)
      val linksAfter = linksAfterGivenAddrMValue.filter(_.endAddrMValue >= extendedLink.endAddrMValue).sortBy(_.endAddrMValue)
      linksBefore.zip(linksAfter).foreach { case (st, en) =>
        liesInBetween(en.endAddrMValue, (st.endAddrMValue + valueToIncrement - coeff, st.endAddrMValue + valueToIncrement + coeff))
      }


      val secondLinkidToIncrement = pl4.linkId
      val secondIdToIncrement = linksAfterGivenAddrMValue.filter(_.linkId == secondLinkidToIncrement).head.id
      val secondValueToIncrement = 3.0
      val secondNewEndAddressValue = Seq(links.filter(_.linkId == secondLinkidToIncrement).head.endAddrMValue.toInt, secondValueToIncrement.toInt).sum
      projectService.updateProjectLinks(project.id, Set(secondIdToIncrement), Seq(), LinkStatus.New, "TestUserTwo", 9999, 1, 1, Some(secondNewEndAddressValue), 1L, 5) should be(None)
      val linksAfterSecondGivenAddrMValue = projectLinkDAO.fetchProjectLinks(project.id)

      /**
        * Test 3.
        */
      linksAfterSecondGivenAddrMValue.filterNot(_.track == Track.RightSide).sortBy(_.endAddrMValue).scanLeft(0.0) { case (m, pl) =>
        m should be(pl.startAddrMValue)
        pl.endAddrMValue
      }
      linksAfterSecondGivenAddrMValue.filterNot(_.track == Track.LeftSide).sortBy(_.endAddrMValue).scanLeft(0.0) { case (m, pl) =>
        m should be(pl.startAddrMValue)
        pl.endAddrMValue
      }

      /**
        * Test 4.
        */
      linksAfterSecondGivenAddrMValue.sortBy(_.endAddrMValue).scanLeft(0.0) { case (m, pl) =>
        if (pl.calibrationPoints._1.nonEmpty) {
          pl.calibrationPoints._1.get.addressMValue should be(pl.startAddrMValue)
          pl.calibrationPoints._1.get.segmentMValue should be(pl.endMValue - pl.startMValue)
        }
        if (pl.calibrationPoints._2.nonEmpty) {
          pl.calibrationPoints._2.get.addressMValue should be(pl.endAddrMValue)
          pl.calibrationPoints._2.get.addressMValue should be(pl.endMValue)
        }
        0.0 //any double val, needed for expected type value in recursive scan
      }

      //only link and links after secondLinkidToIncrement should be extended
      val secondExtendedLink = linksAfterGivenAddrMValue.filter(_.linkId == secondLinkidToIncrement).head
      val secondLinksBefore = linksAfterGivenAddrMValue.filter(_.endAddrMValue >= secondExtendedLink.endAddrMValue).sortBy(_.endAddrMValue)
      val secondLinksAfter = linksAfterSecondGivenAddrMValue.filter(_.endAddrMValue >= secondExtendedLink.endAddrMValue).sortBy(_.endAddrMValue)
      secondLinksBefore.zip(secondLinksAfter).foreach { case (st, en) =>
        liesInBetween(en.endAddrMValue, (st.endAddrMValue + valueToIncrement + secondValueToIncrement - coeff, st.endAddrMValue + valueToIncrement + secondValueToIncrement + coeff))
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

      val rap = Project(0L, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("1901-01-01"),
        "TestUser", DateTime.parse("1901-01-01"), DateTime.now(), "Some additional info",
        Seq(), Seq(), None)

      val pl1 = ProjectLink(-1000L, 9999L, 1L, Track.apply(0), Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None, None, 12345L.toString, 0.0, 0.0, SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP), Seq(Point(10.0, 10.0), Point(20.0, 10.0)), 0L, LinkStatus.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(Seq(Point(10.0, 10.0), Point(20.0, 10.0))), 0L, 0, 0, reversed = false, None, 86400L)
      val pl2 = ProjectLink(-1000L, 9999L, 1L, Track.apply(2), Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None, None, 12346L.toString, 0.0, 0.0, SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP), Seq(Point(20.0, 10.0), Point(30.0, 15.0)), 0L, LinkStatus.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(Seq(Point(20.0, 10.0), Point(30.0, 15.0))), 0L, 0, 0, reversed = false, None, 86400L)
      val pl3 = ProjectLink(-1000L, 9999L, 1L, Track.apply(2), Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None, None, 12347L.toString, 0.0, 0.0, SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP), Seq(Point(30.0, 15.0), Point(45.0, 10.0)), 0L, LinkStatus.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(Seq(Point(30.0, 15.0), Point(45.0, 10.0))), 0L, 0, 0, reversed = false, None, 86400L)
      val pl4 = ProjectLink(-1000L, 9999L, 1L, Track.apply(1), Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None, None, 12348L.toString, 0.0, 0.0, SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP), Seq(Point(20.0, 10.0), Point(25.0, 5.0), Point(45.0, 10.0)), 0L, LinkStatus.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(Seq(Point(20.0, 10.0), Point(25.0, 5.0), Point(45.0, 10.0))), 0L, 0, 0, reversed = false, None, 86400L)
      val pl5 = ProjectLink(-1000L, 9999L, 1L, Track.apply(0), Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None, None, 12349L.toString, 0.0, 0.0, SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP), Seq(Point(45.0, 10.0), Point(60.0, 10.0)), 0L, LinkStatus.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(Seq(Point(45.0, 10.0), Point(60.0, 10.0))), 0L, 0, 0, reversed = false, None, 86400L)

      val project = projectService.createRoadLinkProject(rap)
      when(mockRoadLinkService.getRoadLinksByLinkIds(any[Set[String]])).thenReturn(Seq(pl1).map(toRoadLink))
      projectService.createProjectLinks(Seq(12345L.toString), project.id, 9999, 1, Track.Combined, Discontinuity.Continuous, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, 8L, "test", "road name")
      when(mockRoadLinkService.getRoadLinksByLinkIds(any[Set[String]])).thenReturn(Seq(pl4).map(toRoadLink))
      projectService.createProjectLinks(Seq(12348L.toString), project.id, 9999, 1, Track.RightSide, Discontinuity.Continuous, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, 8L, "test", "road name")
      when(mockRoadLinkService.getRoadLinksByLinkIds(any[Set[String]])).thenReturn(Seq(pl2, pl3).map(toRoadLink))
      projectService.createProjectLinks(Seq(12346L.toString, 12347L.toString), project.id, 9999, 1, Track.LeftSide, Discontinuity.Continuous, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, 8L, "test", "road name")
      when(mockRoadLinkService.getRoadLinksByLinkIds(any[Set[String]])).thenReturn(Seq(pl5).map(toRoadLink))
      projectService.createProjectLinks(Seq(12349L.toString), project.id, 9999, 1, Track.Combined, Discontinuity.Continuous, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, 8L, "test", "road name")
      val links = projectLinkDAO.fetchProjectLinks(project.id).sortBy(_.startAddrMValue)

      val linkidToIncrement = pl1.linkId
      val idsToIncrement = links.filter(_.linkId == linkidToIncrement).head.id
      val valueToIncrement = 2.0
      val newEndAddressValue = Seq(links.filter(_.linkId == linkidToIncrement).head.endAddrMValue.toInt, valueToIncrement.toInt).sum
      projectService.updateProjectLinks(project.id, Set(idsToIncrement), Seq(linkidToIncrement), LinkStatus.New, "TestUserTwo", 9999, 1, 0, Some(newEndAddressValue), 1L, 5) should be(None)

      val links_ = projectLinkDAO.fetchProjectLinks(project.id).sortBy(_.startAddrMValue)
      val linksAfterGivenAddrMValue = ProjectSectionCalculator.assignMValues(links_).sortBy(_.endAddrMValue)

      //only link and links after linkidToIncrement should be extended
      val extendedLink = links.filter(_.linkId == linkidToIncrement).head
      val linksBefore = links.filter(_.endAddrMValue >= extendedLink.endAddrMValue).sortBy(_.endAddrMValue)
      val linksAfter = linksAfterGivenAddrMValue.filter(_.endAddrMValue >= extendedLink.endAddrMValue).sortBy(_.endAddrMValue)

      projectService.changeDirection(project.id, 9999L, 1L, Seq(LinkToRevert(pl1.id, pl1.linkId, pl1.status.value, pl1.geometry)), ProjectCoordinates(0, 0, 0), "TestUserTwo")
      val linksAfterFirstReverse = ProjectSectionCalculator.assignMValues(links_).sortBy(_.endAddrMValue)
      projectService.changeDirection(project.id, 9999L, 1L, Seq(LinkToRevert(pl1.id, pl1.linkId, pl1.status.value, pl1.geometry)), ProjectCoordinates(0, 0, 0), "TestUserTwo")
      val linksAfterSecondReverse = ProjectSectionCalculator.assignMValues(links_).sortBy(_.endAddrMValue)

      linksAfterGivenAddrMValue.sortBy(pl => (pl.endAddrMValue, pl.startAddrMValue)).zip(linksAfterSecondReverse.sortBy(pl => (pl.endAddrMValue, pl.startAddrMValue))).foreach { case (st, en) =>
        (st.startAddrMValue, st.endAddrMValue) should be(en.startAddrMValue, en.endAddrMValue)
        (st.startMValue, st.endMValue) should be(en.startMValue, en.endMValue)
      }
    }
  }

  test("Test projectService.changeDirection() When after the creation of valid project links on a project Then the discontinuity of road addresses that are not the same road number and road part number should not be altered.") {
    runWithRollback {

      val rap = Project(0L, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("1901-01-01"),
        "TestUser", DateTime.parse("1901-01-01"), DateTime.now(), "Some additional info",
        Seq(), Seq(), None)

      val pl1 = ProjectLink(-1000L, 9999L, 1L, Track.apply(0), Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None, None, 12345L.toString, 0.0, 0.0, SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP), Seq(Point(10.0, 10.0), Point(20.0, 10.0)), 0L, LinkStatus.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(Seq(Point(10.0, 10.0), Point(20.0, 10.0))), 0L, 0, 0, reversed = false, None, 86400L)
      val pl2 = ProjectLink(-1000L, 9999L, 1L, Track.apply(2), Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None, None, 12346L.toString, 0.0, 0.0, SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP), Seq(Point(20.0, 10.0), Point(30.0, 15.0)), 0L, LinkStatus.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(Seq(Point(20.0, 10.0), Point(30.0, 15.0))), 0L, 0, 0, reversed = false, None, 86400L)
      val pl3 = ProjectLink(-1000L, 9999L, 1L, Track.apply(2), Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None, None, 12347L.toString, 0.0, 0.0, SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP), Seq(Point(30.0, 15.0), Point(45.0, 10.0)), 0L, LinkStatus.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(Seq(Point(30.0, 15.0), Point(45.0, 10.0))), 0L, 0, 0, reversed = false, None, 86400L)
      val pl4 = ProjectLink(-1000L, 9999L, 1L, Track.apply(1), Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None, None, 12348L.toString, 0.0, 0.0, SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP), Seq(Point(20.0, 10.0), Point(25.0, 5.0), Point(45.0, 10.0)), 0L, LinkStatus.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(Seq(Point(20.0, 10.0), Point(25.0, 5.0), Point(45.0, 10.0))), 0L, 0, 0, reversed = false, None, 86400L)
      val pl5 = ProjectLink(-1000L, 9998L, 1L, Track.apply(0), Discontinuity.EndOfRoad, 0L, 0L, 0L, 0L, None, None, None, 12349L.toString, 0.0, 0.0, SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP), Seq(Point(45.0, 10.0), Point(60.0, 10.0)), 0L, LinkStatus.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(Seq(Point(45.0, 10.0), Point(60.0, 10.0))), 0L, 0, 0, reversed = false, None, 86400L)

      val project = projectService.createRoadLinkProject(rap)
      when(mockRoadLinkService.getRoadLinksByLinkIds(any[Set[String]])).thenReturn(Seq(pl1).map(toRoadLink))
      projectService.createProjectLinks(Seq(12345L.toString), project.id, 9999, 1, Track.Combined, Discontinuity.Continuous, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, 8L, "test", "road name")
      when(mockRoadLinkService.getRoadLinksByLinkIds(any[Set[String]])).thenReturn(Seq(pl4).map(toRoadLink))
      projectService.createProjectLinks(Seq(12348L.toString), project.id, 9999, 1, Track.RightSide, Discontinuity.Continuous, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, 8L, "test", "road name")
      when(mockRoadLinkService.getRoadLinksByLinkIds(any[Set[String]])).thenReturn(Seq(pl2, pl3).map(toRoadLink))
      projectService.createProjectLinks(Seq(12346L.toString, 12347L.toString), project.id, 9999, 1, Track.LeftSide, Discontinuity.Continuous, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, 8L, "test", "road name")
      when(mockRoadLinkService.getRoadLinksByLinkIds(any[Set[String]])).thenReturn(Seq(pl5).map(toRoadLink))
      projectService.createProjectLinks(Seq(12349L.toString), project.id, 9998, 1, Track.Combined, Discontinuity.EndOfRoad, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, 8L, "test", "road name")
      val linksBeforeChange = projectLinkDAO.fetchProjectLinks(project.id).sortBy(_.startAddrMValue)
      val linkBC = linksBeforeChange.filter(_.roadNumber == 9998L)
      linkBC.size should be(1)
      linkBC.head.discontinuity.value should be(Discontinuity.EndOfRoad.value)
      projectService.changeDirection(project.id, 9999L, 1L, Seq(LinkToRevert(pl1.id, pl1.linkId, pl1.status.value, pl1.geometry)), ProjectCoordinates(0, 0, 0), "TestUserTwo")
      val linksAfterChange = projectLinkDAO.fetchProjectLinks(project.id).sortBy(_.startAddrMValue)
      val linkAC = linksAfterChange.filter(_.roadNumber == 9998L)
      linkAC.size should be(1)
      linkAC.head.discontinuity.value should be(linkBC.head.discontinuity.value)
    }
  }

  test("Test defaultSectionCalculatorStrategy.assignMValues() " +
       "When a (complex) road has three roundabouts with two tracks and the road starts from south east toward north" +
       "Then assignMValues() should calculate succesfully. No changes are expected to the projectlink values. Addressses issues with ProjectLink ordering and discontinuities. Reversed case included.") {
    runWithRollback {
      val projectId = Sequences.nextViiteProjectId
      val roadNumber = 42810
      val roadPartNumber = 1
      val createdBy = "test"
      val roadName = None
      val projectLinks = Seq(
        ProjectLink(1000,roadNumber,roadPartNumber,Track.RightSide,Discontinuity.Continuous,0,81,0,81,None,None,Some(createdBy),4107344.toString,0.0,76.993,SideCode.TowardsDigitizing,(RoadAddressCP,NoCP),(RoadAddressCP,RoadAddressCP),List(Point(221253.0,6827429.0,0.0), Point(221226.0,6827501.0,0.0)),projectId,LinkStatus.UnChanged,AdministrativeClass.Municipality,FrozenLinkInterface,76.993,63197,0L,2,reversed = false,None,1551489610000L,52347051,roadName,None,None,None,None,None,None),
        ProjectLink(1001,roadNumber,roadPartNumber,Track.LeftSide,Discontinuity.MinorDiscontinuity,0,81,0,81,None,None,Some(createdBy),4107372.toString,0.0,73.976,SideCode.TowardsDigitizing,(RoadAddressCP,RoadAddressCP),(RoadAddressCP,RoadAddressCP),List(Point(221242.0,6827426.0,0.0), Point(221210.0,6827491.0,0.0)),projectId,LinkStatus.UnChanged,AdministrativeClass.Municipality,FrozenLinkInterface,73.976,62737,0L,2,reversed = false,None,1551489610000L,52347054,roadName,None,None,None,None,None,None),
        ProjectLink(1002,roadNumber,roadPartNumber,Track.LeftSide,Discontinuity.Continuous,81,92,81,92,None,None,Some(createdBy),4107358.toString,0.0,9.759,SideCode.TowardsDigitizing,(RoadAddressCP,NoCP),(RoadAddressCP,NoCP),List(Point(221213.0,6827520.0,0.0), Point(221218.0,6827528.0,0.0)),projectId,LinkStatus.UnChanged,AdministrativeClass.Municipality,FrozenLinkInterface,9.759,80870,0L,2,reversed = false,None,1551489610000L,203081345,roadName,None,None,None,None,None,None),
        ProjectLink(1003,roadNumber,roadPartNumber,Track.RightSide,Discontinuity.Continuous,81,102,81,102,None,None,Some(createdBy),4107356.toString,0.0,19.586,SideCode.TowardsDigitizing,(NoCP,NoCP),(RoadAddressCP,NoCP),List(Point(221226.0,6827501.0,0.0), Point(221231.0,6827520.0,0.0)),projectId,LinkStatus.UnChanged,AdministrativeClass.Municipality,FrozenLinkInterface,19.586,80639,0L,2,reversed = false,None,1551489610000L,203081355,roadName,None,None,None,None,None,None),
        ProjectLink(1004,roadNumber,roadPartNumber,Track.LeftSide,Discontinuity.Continuous,92,125,92,125,None,None,Some(createdBy),4107352.toString,0.0,29.251,SideCode.TowardsDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(221218.0,6827528.0,0.0), Point(221228.0,6827555.0,0.0)),projectId,LinkStatus.UnChanged,AdministrativeClass.Municipality,FrozenLinkInterface,29.251,80870,0L,2, reversed = false, None,1551489610000L,203081345, roadName, None, None, None, None, None, None),
        ProjectLink(1005,roadNumber,roadPartNumber,Track.RightSide,Discontinuity.Continuous,102,136,102,136,None,None,Some(createdBy),4107353.toString,0.0,32.426,SideCode.TowardsDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(221231.0,6827520.0,0.0), Point(221243.0,6827550.0,0.0)),projectId,LinkStatus.UnChanged,AdministrativeClass.Municipality,FrozenLinkInterface,32.426,80639,0L,2,reversed = false,None,1551489610000L,203081355,roadName,None,None,None,None,None,None),
        ProjectLink(1006,roadNumber,roadPartNumber,Track.LeftSide,Discontinuity.Continuous,125,208,125,208,None,None,Some(createdBy),4107348.toString,0.0,74.727,SideCode.TowardsDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(221228.0,6827555.0,0.0), Point(221251.0,6827626.0,0.0)),projectId,LinkStatus.UnChanged,AdministrativeClass.Municipality,FrozenLinkInterface,74.727,80870,0L,2,reversed = false,None,1551489610000L,203081345,roadName,None,None,None,None,None,None),
        ProjectLink(1007,roadNumber,roadPartNumber,Track.RightSide,Discontinuity.Continuous,136,215,136,215,None,None,Some(createdBy),4107349.toString,0.0,74.673,SideCode.TowardsDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(221243.0,6827550.0,0.0), Point(221266.0,6827621.0,0.0)),projectId,LinkStatus.UnChanged,AdministrativeClass.Municipality,FrozenLinkInterface,74.673,80639,0L,2,reversed = false,None,1551489610000L,203081355,roadName,None,None,None,None,None,None),
        ProjectLink(1008,roadNumber,roadPartNumber,Track.LeftSide,Discontinuity.Continuous,208,291,208,291,None,None,Some(createdBy),4107302.toString,0.0,73.948,SideCode.TowardsDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(221251.0,6827626.0,0.0), Point(221273.0,6827697.0,0.0)),projectId,LinkStatus.UnChanged,AdministrativeClass.Municipality,FrozenLinkInterface,73.948,80870,0L,2,reversed = false,None,1551489610000L,203081345,roadName,None,None,None,None,None,None),
        ProjectLink(1009,roadNumber,roadPartNumber,Track.RightSide,Discontinuity.Continuous,215,293,215,293,None,None,Some(createdBy),4107303.toString,0.0,73.938,SideCode.TowardsDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(221266.0,6827621.0,0.0), Point(221288.0,6827692.0,0.0)),projectId,LinkStatus.UnChanged,AdministrativeClass.Municipality,FrozenLinkInterface,73.938,80639,0L,2,reversed = false,None,1551489610000L,203081355,roadName,None,None,None,None,None,None),
        ProjectLink(1010,roadNumber,roadPartNumber,Track.LeftSide,Discontinuity.MinorDiscontinuity,291,372,291,372,None,None,Some(createdBy),6860514.toString,0.0,72.734,SideCode.TowardsDigitizing,(NoCP,RoadAddressCP),(NoCP,RoadAddressCP),List(Point(221273.0,6827697.0,0.0), Point(221294.0,6827766.0,0.0)),projectId,LinkStatus.UnChanged,AdministrativeClass.Municipality,FrozenLinkInterface,72.734,80870,0L,2,reversed = false,None,1551489610000L,203081345,roadName,None,None,None,None,None,None),
        ProjectLink(1011,roadNumber,roadPartNumber,Track.RightSide,Discontinuity.MinorDiscontinuity,293,372,293,372,None,None,Some(createdBy),4107324.toString,0.0,74.737,SideCode.TowardsDigitizing,(NoCP,RoadAddressCP),(NoCP,RoadAddressCP),List(Point(221288.0,6827692.0,0.0), Point(221308.0,6827764.0,0.0)),projectId,LinkStatus.UnChanged,AdministrativeClass.Municipality,FrozenLinkInterface,74.737,80639,0L,2,reversed = false,None,1551489610000L,203081355,roadName,None,None,None,None,None,None),
        ProjectLink(1012,roadNumber,roadPartNumber,Track.LeftSide,Discontinuity.Continuous,372,549,372,549,None,None,Some(createdBy),6860510.toString,0.0,162.111,SideCode.TowardsDigitizing,(RoadAddressCP,NoCP),(RoadAddressCP,NoCP),List(Point(221304.0,6827787.0,0.0), Point(221353.0,6827942.0,0.0)),projectId,LinkStatus.UnChanged,AdministrativeClass.Municipality,FrozenLinkInterface,162.111,79143,0L,2,reversed = false,None,1551489610000L,190895362,roadName,None,None,None,None,None,None),
        ProjectLink(1013,roadNumber,roadPartNumber,Track.RightSide,Discontinuity.Continuous,372,553,372,553,None,None,Some(createdBy),6860512.toString,0.0,166.163,SideCode.TowardsDigitizing,(RoadAddressCP,NoCP),(RoadAddressCP,NoCP),List(Point(221314.0,6827780.0,0.0), Point(221367.0,6827937.0,0.0)),projectId,LinkStatus.UnChanged,AdministrativeClass.Municipality,FrozenLinkInterface,166.163,79417,0L,2,reversed = false,None,1551489610000L,190895359,roadName,None,None,None,None,None,None),
        ProjectLink(1014,roadNumber,roadPartNumber,Track.RightSide,Discontinuity.MinorDiscontinuity,553,716,553,716,None,None,Some(createdBy),4107261.toString,0.0,149.998,SideCode.TowardsDigitizing,(NoCP,RoadAddressCP),(NoCP,RoadAddressCP),List(Point(221367.0,6827937.0,0.0), Point(221418.0,6828077.0,0.0)),projectId,LinkStatus.UnChanged,AdministrativeClass.Municipality,FrozenLinkInterface,149.998,79417,0L,2,reversed = false,None,1551489610000L,190895359,roadName,None,None,None,None,None,None),
        ProjectLink(1015,roadNumber,roadPartNumber,Track.LeftSide,Discontinuity.MinorDiscontinuity,549,716,549,716,None,None,Some(createdBy),4107262.toString,0.0,153.454,SideCode.TowardsDigitizing,(NoCP,RoadAddressCP),(NoCP,RoadAddressCP),List(Point(221353.0,6827942.0,0.0), Point(221402.0,6828086.0,0.0)),projectId,LinkStatus.UnChanged,AdministrativeClass.Municipality,FrozenLinkInterface,153.454,79143,0L,2,reversed = false,None,1551489610000L,190895362,roadName,None,None,None,None,None,None),
        ProjectLink(1016,roadNumber,roadPartNumber,Track.RightSide,Discontinuity.Continuous,716,732,716,732,None,None,Some(createdBy),4107220.toString,0.0,15.094,SideCode.TowardsDigitizing,(RoadAddressCP,NoCP),(RoadAddressCP,NoCP),List(Point(221431.0,6828103.0,0.0), Point(221430.0,6828118.0,0.0)),projectId,LinkStatus.UnChanged,AdministrativeClass.Municipality,FrozenLinkInterface,15.094,80905,0L,2,reversed = false,Some(4107220.toString),1548889228000L,202230394,roadName,None,None,None,None,None,None),
        ProjectLink(1017,roadNumber,roadPartNumber,Track.LeftSide,Discontinuity.Continuous,716,732,716,732,None,None,Some(createdBy),4107221.toString,0.0,14.31,SideCode.TowardsDigitizing,(RoadAddressCP,NoCP),(RoadAddressCP,NoCP),List(Point(221413.0,6828110.0,0.0), Point(221418.0,6828123.0,0.0)),projectId,LinkStatus.UnChanged,AdministrativeClass.Municipality,FrozenLinkInterface,14.31,81270,0L,2,reversed = false,None,1548889228000L,202230385,roadName,None,None,None,None,None,None),
        ProjectLink(1018,roadNumber,roadPartNumber,Track.LeftSide,Discontinuity.Continuous,732,734,732,734,None,None,Some(createdBy),4107217.toString,0.0,1.851,SideCode.TowardsDigitizing,(NoCP,NoCP),(NoCP,RoadAddressCP),List(Point(221418.0,6828123.0,0.0), Point(221419.0,6828125.0,0.0)),projectId,LinkStatus.UnChanged,AdministrativeClass.Municipality,FrozenLinkInterface,1.851,81270,0L,2,reversed = false,Some(4107217.toString),1548889228000L,202230385,roadName,None,None,None,None,None,None),
        ProjectLink(1019,roadNumber,roadPartNumber,Track.RightSide,Discontinuity.Continuous,732,734,732,734,None,None,Some(createdBy),4107220.toString,15.094,16.981,SideCode.TowardsDigitizing,(NoCP,NoCP),(RoadAddressCP,NoCP),List(Point(221430.0,6828118.0,0.0), Point(221430.0,6828120.0,0.0)),projectId,LinkStatus.UnChanged,AdministrativeClass.Municipality,FrozenLinkInterface,1.887,80905,0L,2,reversed = false,Some(4107220.toString),1548889228000L,202230394,roadName,None,None,None,None,None,None),
        ProjectLink(1020,roadNumber,roadPartNumber,Track.RightSide,Discontinuity.Continuous,734,806,734,806,None,None,Some(createdBy),4107218.toString,0.0,67.157,SideCode.TowardsDigitizing,(NoCP,RoadAddressCP),(NoCP,RoadAddressCP),List(Point(221430.0,6828120.0,0.0), Point(221445.0,6828185.0,0.0)),projectId,LinkStatus.UnChanged,AdministrativeClass.Municipality,FrozenLinkInterface,67.157,80905,0L,2,reversed = false,None,1548889228000L,202230394,roadName,None,None,None,None,None,None),
        ProjectLink(1021,roadNumber,roadPartNumber,Track.LeftSide,Discontinuity.Continuous,734,806,734,806,None,None,Some(createdBy),4107217.toString,1.851,68.478,SideCode.TowardsDigitizing,(NoCP,RoadAddressCP),(NoCP,RoadAddressCP),List(Point(221419.0,6828125.0,0.0), Point(221445.0,6828185.0,0.0)),projectId,LinkStatus.UnChanged,AdministrativeClass.Municipality,FrozenLinkInterface,66.627,81270,0L,2,reversed = false,Some(4107217.toString),1548889228000L,202230385,roadName,None,None,None,None,None,None),
        ProjectLink(1022,roadNumber,roadPartNumber,Track.Combined,Discontinuity.Continuous,806,940,806,940,None,None,Some(createdBy),4107187.toString,0.0,138.512,SideCode.TowardsDigitizing,(RoadAddressCP,NoCP),(RoadAddressCP,NoCP),List(Point(221445.0,6828185.0,0.0), Point(221487.0,6828317.0,0.0)),projectId,LinkStatus.UnChanged,AdministrativeClass.Municipality,FrozenLinkInterface,138.512,63151,0L,2,reversed = false,None,1548889228000L,52347057,roadName,None,None,None,None,None,None),
        ProjectLink(1023,roadNumber,roadPartNumber,Track.Combined,Discontinuity.Continuous,940,954,940,954,None,None,Some(createdBy),4107210.toString,0.0,14.811,SideCode.TowardsDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(221487.0,6828317.0,0.0), Point(221492.0,6828331.0,0.0)),projectId,LinkStatus.UnChanged,AdministrativeClass.Municipality,FrozenLinkInterface,14.811,63151,0L,2,reversed = false,None,1548889228000L,52347057,roadName,None,None,None,None,None,None),
        ProjectLink(1024,roadNumber,roadPartNumber,Track.Combined,Discontinuity.Continuous,954,980,954,980,None,None,Some(createdBy),4107209.toString,0.0,27.378,SideCode.TowardsDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(221492.0,6828331.0,0.0), Point(221501.0,6828357.0,0.0)),projectId,LinkStatus.UnChanged,AdministrativeClass.Municipality,FrozenLinkInterface,27.378,63151,0L,2,reversed = false,None,1548889228000L,52347057,roadName,None,None,None,None,None,None),
        ProjectLink(1025,roadNumber,roadPartNumber,Track.Combined,Discontinuity.EndOfRoad,980,1066,980,1066,None,None,Some(createdBy),4107203.toString,0.0,88.942,SideCode.TowardsDigitizing,(NoCP,RoadAddressCP),(NoCP,RoadAddressCP),List(Point(221501.0,6828357.0,0.0), Point(221531.0,6828440.0,0.0)),projectId,LinkStatus.UnChanged,AdministrativeClass.Municipality,FrozenLinkInterface,88.942,63151,0L,2,reversed = false,None,1548889228000L,52347057,roadName,None,None,None,None,None,None)
      )

      val roadways = Seq(
        Roadway(62737,52347054,42810,1,AdministrativeClass.Municipality,Track.LeftSide,Discontinuity.MinorDiscontinuity,0,81,reversed = false,DateTime.parse("2018-07-01T00:00:00.000+03:00"),None,createdBy,roadName,2,TerminationCode.NoTermination,DateTime.parse("2018-07-09T00:00:00.000+03:00"),None),
        Roadway(63151,52347057,42810,1,AdministrativeClass.Municipality,Track.Combined,Discontinuity.EndOfRoad,806,1066,reversed = false,DateTime.parse("2013-10-01T00:00:00.000+03:00"),None,createdBy,roadName,2,TerminationCode.NoTermination,DateTime.parse("2013-10-29T00:00:00.000+02:00"),None),
        Roadway(63197,52347051,42810,1,AdministrativeClass.Municipality,Track.RightSide,Discontinuity.Continuous,0,81,reversed = false,DateTime.parse("2018-07-01T00:00:00.000+03:00"),None,createdBy,roadName,2,TerminationCode.NoTermination,DateTime.parse("2018-07-09T00:00:00.000+03:00"),None),
        Roadway(79143,190895362,42810,1,AdministrativeClass.Municipality,Track.LeftSide,Discontinuity.MinorDiscontinuity,372,716,reversed = false,DateTime.parse("2018-07-01T00:00:00.000+03:00"),None,createdBy,roadName,2,TerminationCode.NoTermination,DateTime.parse("2018-07-05T00:00:00.000+03:00"),None),
        Roadway(79417,190895359,42810,1,AdministrativeClass.Municipality,Track.RightSide,Discontinuity.MinorDiscontinuity,372,716,reversed = false,DateTime.parse("2018-07-01T00:00:00.000+03:00"),None,createdBy,roadName,2,TerminationCode.NoTermination,DateTime.parse("2018-07-05T00:00:00.000+03:00"),None),
        Roadway(80639,203081355,42810,1,AdministrativeClass.Municipality,Track.RightSide,Discontinuity.MinorDiscontinuity,81,372,reversed = false,DateTime.parse("2017-10-01T00:00:00.000+03:00"),None,createdBy,roadName,2,TerminationCode.NoTermination,DateTime.parse("2018-07-09T00:00:00.000+03:00"),None),
        Roadway(80870,203081345,42810,1,AdministrativeClass.Municipality,Track.LeftSide,Discontinuity.MinorDiscontinuity,81,372,reversed = false,DateTime.parse("2017-10-01T00:00:00.000+03:00"),None,createdBy,roadName,2,TerminationCode.NoTermination,DateTime.parse("2018-07-09T00:00:00.000+03:00"),None),
        Roadway(80905,202230394,42810,1,AdministrativeClass.Municipality,Track.RightSide,Discontinuity.Continuous,716,806,reversed = false,DateTime.parse("2013-10-01T00:00:00.000+03:00"),None,createdBy,roadName,2,TerminationCode.NoTermination,DateTime.parse("2018-07-05T00:00:00.000+03:00"),None),
        Roadway(81270,202230385,42810,1,AdministrativeClass.Municipality,Track.LeftSide,Discontinuity.Continuous,716,806,reversed = false,DateTime.parse("2013-10-01T00:00:00.000+03:00"),None,createdBy,roadName,2,TerminationCode.NoTermination,DateTime.parse("2018-07-05T00:00:00.000+03:00"),None)
      )

      val linearLocations = Seq(
        LinearLocation(459379, 4.0, 4107303.toString, 0.0, 73.938, TowardsDigitizing, 1551489610000L, (CalibrationPointReference(None,None),CalibrationPointReference(None,None)), List(Point(221266.096,6827621.936,0.0), Point(221288.799,6827692.302,0.0)), FrozenLinkInterface, 203081355, Some(DateTime.parse("2018-07-09T00:00:00.000+03:00")), None),
        LinearLocation(459377, 2.0, 4107353.toString, 0.0, 32.426, TowardsDigitizing, 1551489610000L, (CalibrationPointReference(None,None),CalibrationPointReference(None,None)), List(Point(221231.221,6827520.725,0.0), Point(221243.168,6827550.87,0.0)), FrozenLinkInterface, 203081355, Some(DateTime.parse("2018-07-09T00:00:00.000+03:00")), None),
        LinearLocation(456058, 1.0, 6860512.toString, 0.0, 166.163, TowardsDigitizing, 1551489610000L, (CalibrationPointReference(Some(372),Some(RoadAddressCP)),CalibrationPointReference(None,None)), List(Point(221314.806,6827780.408,0.0), Point(221367.901,6827937.473,0.0)), FrozenLinkInterface, 190895359, Some(DateTime.parse("2018-07-05T00:00:00.000+03:00")), None),
        LinearLocation(460645, 2.0, 4107352.toString, 0.0, 29.251, TowardsDigitizing, 1551489610000L, (CalibrationPointReference(None,None),CalibrationPointReference(None,None)), List(Point(221218.934,6827528.001,0.0), Point(221228.307,6827555.71,0.0)), FrozenLinkInterface, 203081345, Some(DateTime.parse("2018-07-09T00:00:00.000+03:00")), None),
        LinearLocation(459378, 3.0, 4107349.toString, 0.0, 74.673, TowardsDigitizing, 1551489610000L, (CalibrationPointReference(None,None),CalibrationPointReference(None,None)), List(Point(221243.168,6827550.87,0.0), Point(221266.096,6827621.936,0.0)), FrozenLinkInterface, 203081355, Some(DateTime.parse("2018-07-09T00:00:00.000+03:00")), None),
        LinearLocation(413435, 1.0, 4107187.toString, 0.0, 138.512, TowardsDigitizing, 1548889228000L, (CalibrationPointReference(Some(806),Some(RoadAddressCP)),CalibrationPointReference(None,None)), List(Point(221445.08,6828185.443,0.0), Point(221487.681,6828317.241,0.0)), FrozenLinkInterface, 52347057, Some(DateTime.parse("2013-10-29T00:00:00.000+02:00")), None),
        LinearLocation(460644, 1.0, 4107358.toString, 0.0, 9.759, TowardsDigitizing, 1551489610000L, (CalibrationPointReference(Some(81),Some(RoadAddressCP)),CalibrationPointReference(None,None)), List(Point(221213.111,6827520.172,0.0), Point(221218.934,6827528.001,0.0)), FrozenLinkInterface, 203081345, Some(DateTime.parse("2018-07-09T00:00:00.000+03:00")), None),
        LinearLocation(410705, 1.0, 4107372.toString, 0.0, 73.976, TowardsDigitizing, 1551489610000L, (CalibrationPointReference(Some(0),Some(RoadAddressCP)),CalibrationPointReference(Some(81),Some(RoadAddressCP))), List(Point(221242.391,6827426.168,0.0), Point(221210.462,6827491.36,0.0)), FrozenLinkInterface, 52347054, Some(DateTime.parse("2018-07-09T00:00:00.000+03:00")), None),
        LinearLocation(462815, 2.0, 4107217.toString, 0.0, 68.478, TowardsDigitizing, 1548889228000L, (CalibrationPointReference(None,None),CalibrationPointReference(Some(806),Some(RoadAddressCP))), List(Point(221418.947,6828123.782,0.0), Point(221445.08,6828185.443,0.0)), FrozenLinkInterface, 202230385, Some(DateTime.parse("2018-07-05T00:00:00.000+03:00")), None),
        LinearLocation(454475, 1.0, 6860510.toString, 0.0, 162.111, TowardsDigitizing, 1551489610000L, (CalibrationPointReference(Some(372),Some(RoadAddressCP)),CalibrationPointReference(None,None)), List(Point(221304.851,6827787.534,0.0), Point(221353.127,6827942.257,0.0)), FrozenLinkInterface, 190895362, Some(DateTime.parse("2018-07-05T00:00:00.000+03:00")), None),
        LinearLocation(459380, 5.0, 4107324.toString, 0.0, 74.737, TowardsDigitizing, 1551489610000L, (CalibrationPointReference(None,None),CalibrationPointReference(Some(372),Some(RoadAddressCP))), List(Point(221288.799,6827692.302,0.0), Point(221308.716,6827764.201,0.0)), FrozenLinkInterface, 203081355, Some(DateTime.parse("2018-07-09T00:00:00.000+03:00")), None),
        LinearLocation(459376, 1.0, 4107356.toString, 0.0, 19.586, TowardsDigitizing, 1551489610000L, (CalibrationPointReference(Some(81),Some(RoadAddressCP)),CalibrationPointReference(None,None)), List(Point(221226.385,6827501.747,0.0), Point(221231.221,6827520.725,0.0)), FrozenLinkInterface, 203081355, Some(DateTime.parse("2018-07-09T00:00:00.000+03:00")), None),
        LinearLocation(454476, 2.0, 4107262.toString, 0.0, 153.454, TowardsDigitizing, 1551489610000L, (CalibrationPointReference(None,None),CalibrationPointReference(Some(716),Some(RoadAddressCP))), List(Point(221353.127,6827942.257,0.0), Point(221402.889,6828086.474,0.0)), FrozenLinkInterface, 190895362, Some(DateTime.parse("2018-07-05T00:00:00.000+03:00")), None),
        LinearLocation(460647, 4.0, 4107302.toString, 0.0, 73.948, TowardsDigitizing, 1551489610000L, (CalibrationPointReference(None,None),CalibrationPointReference(None,None)), List(Point(221251.269,6827626.822,0.0), Point(221273.993,6827697.192,0.0)), FrozenLinkInterface, 203081345, Some(DateTime.parse("2018-07-09T00:00:00.000+03:00")), None),
        LinearLocation(462814, 1.0, 4107221.toString, 0.0, 14.31, TowardsDigitizing, 1548889228000L, (CalibrationPointReference(Some(716),Some(RoadAddressCP)),CalibrationPointReference(None,None)), List(Point(221413.108,6828110.724,0.0), Point(221418.947,6828123.782,0.0)), FrozenLinkInterface, 202230385, Some(DateTime.parse("2018-07-05T00:00:00.000+03:00")), None),
        LinearLocation(460646, 3.0, 4107348.toString, 0.0, 74.727, TowardsDigitizing, 1551489610000L, (CalibrationPointReference(None,None),CalibrationPointReference(None,None)), List(Point(221228.307,6827555.71,0.0), Point(221251.269,6827626.822,0.0)), FrozenLinkInterface, 203081345, Some(DateTime.parse("2018-07-09T00:00:00.000+03:00")), None),
        LinearLocation(413436, 2.0, 4107210.toString, 0.0, 14.811, TowardsDigitizing, 1548889228000L, (CalibrationPointReference(None,None),CalibrationPointReference(None,None)), List(Point(221487.681,6828317.241,0.0), Point(221492.486,6828331.251,0.0)), FrozenLinkInterface, 52347057, Some(DateTime.parse("2013-10-29T00:00:00.000+02:00")), None),
        LinearLocation(413842, 1.0, 4107344.toString, 0.0, 76.993, TowardsDigitizing, 1551489610000L, (CalibrationPointReference(Some(0),Some(RoadAddressCP)),CalibrationPointReference(Some(81),Some(RoadAddressCP))), List(Point(221253.419,6827429.827,0.0), Point(221226.385,6827501.747,0.0)), FrozenLinkInterface, 52347051, Some(DateTime.parse("2018-07-09T00:00:00.000+03:00")), None),
        LinearLocation(456059, 2.0, 4107261.toString, 0.0, 149.998, TowardsDigitizing, 1551489610000L, (CalibrationPointReference(None,None),CalibrationPointReference(Some(716),Some(RoadAddressCP))), List(Point(221367.901,6827937.473,0.0), Point(221418.453,6828077.621,0.0)), FrozenLinkInterface, 190895359, Some(DateTime.parse("2018-07-05T00:00:00.000+03:00")), None),
        LinearLocation(413437, 3.0, 4107209.toString, 0.0, 27.378, TowardsDigitizing, 1548889228000L, (CalibrationPointReference(None,None),CalibrationPointReference(None,None)), List(Point(221492.486,6828331.251,0.0), Point(221501.544,6828357.087,0.0)), FrozenLinkInterface, 52347057, Some(DateTime.parse("2013-10-29T00:00:00.000+02:00")), None),
        LinearLocation(460855, 1.0, 4107220.toString, 0.0, 16.981, TowardsDigitizing, 1548889228000L, (CalibrationPointReference(Some(716),Some(RoadAddressCP)),CalibrationPointReference(None,None)), List(Point(221431.728,6828103.593,0.0), Point(221430.626,6828120.538,0.0)), FrozenLinkInterface, 202230394, Some(DateTime.parse("2018-07-05T00:00:00.000+03:00")), None),
        LinearLocation(460856, 2.0, 4107218.toString, 0.0, 67.157, TowardsDigitizing, 1548889228000L, (CalibrationPointReference(None,None),CalibrationPointReference(Some(806),Some(RoadAddressCP))), List(Point(221430.626,6828120.538,0.0), Point(221445.08,6828185.443,0.0)), FrozenLinkInterface, 202230394, Some(DateTime.parse("2018-07-05T00:00:00.000+03:00")), None),
        LinearLocation(413438, 4.0, 4107203.toString, 0.0, 88.942, TowardsDigitizing, 1548889228000L, (CalibrationPointReference(None,None),CalibrationPointReference(Some(1066),Some(RoadAddressCP))), List(Point(221501.544,6828357.087,0.0), Point(221531.267,6828440.914,0.0)), FrozenLinkInterface, 52347057, Some(DateTime.parse("2013-10-29T00:00:00.000+02:00")), None),
        LinearLocation(460648, 5.0, 6860514.toString, 0.0, 72.734, TowardsDigitizing, 1551489610000L, (CalibrationPointReference(None,None),CalibrationPointReference(Some(372),Some(RoadAddressCP))), List(Point(221273.993,6827697.192,0.0), Point(221294.912,6827766.783,0.0)), FrozenLinkInterface, 203081345, Some(DateTime.parse("2018-07-09T00:00:00.000+03:00")), None)
      )

      val project = Project(projectId, ProjectState.Incomplete, "f", "s", DateTime.now(), "", DateTime.now(), DateTime.now(), "", Seq(), Seq(), None, None)

      buildTestDataForProject(Some(project), Some(roadways), Some(linearLocations), Some(projectLinks))
      val defaultSectionCalculatorStrategy = new DefaultSectionCalculatorStrategy

      val projectLinksWithAssignedValues = defaultSectionCalculatorStrategy.assignMValues(Seq(), projectLinks, Seq.empty[UserDefinedCalibrationPoint])

      val assignedTrackGrouped = projectLinksWithAssignedValues.groupBy(_.track)
      val originalTrackGrouped = projectLinks.groupBy(_.track)

      Track.values.foreach(track => {
        assignedTrackGrouped.getOrElse(track, Seq()).sortBy(_.startAddrMValue).zip(originalTrackGrouped.getOrElse(track, Seq()).sortBy(_.startAddrMValue)).foreach { case (calculated, original) => {
          calculated.startAddrMValue should be(original.startAddrMValue)
          calculated.endAddrMValue should be(original.endAddrMValue)
          calculated.linkId should be(original.linkId)
          calculated.roadwayId should be(original.roadwayId)
          calculated.roadwayNumber should be(original.roadwayNumber)
        }}
      })

      // Check reversed case
      projectLinkDAO.updateProjectLinksStatus(projectLinks.map(_.id).toSet,LinkStatus.Transfer, createdBy)
      projectService.changeDirection(projectId, roadNumber, roadPartNumber, Seq(), ProjectCoordinates(0, 0, 0),createdBy) should be(None)

      val updatedProjectLinks = defaultSectionCalculatorStrategy.assignMValues(Seq(), projectLinkDAO.fetchProjectLinks(projectId), Seq.empty[UserDefinedCalibrationPoint])
      // There are continuity validations in defaultSectionCalculatorStrategy
      val minAddress = updatedProjectLinks.minBy(_.startAddrMValue)
      val maxAddress = updatedProjectLinks.maxBy(_.endAddrMValue)
      minAddress.originalEndAddrMValue should be(projectLinks.maxBy(_.endAddrMValue).endAddrMValue)
      maxAddress.originalStartAddrMValue should be(0)
      updatedProjectLinks.filter(_.track == Track.Combined).foreach(pl => pl.originalTrack should be(switch(pl.track)))
      updatedProjectLinks.filter(_.track == Track.RightSide).foreach(pl => pl.originalTrack should be(switch(pl.track)))
      updatedProjectLinks.filter(_.track == Track.LeftSide).foreach(pl => pl.originalTrack should be(switch(pl.track)))
    }
  }

  test("Test createRoadLinkProject When project in writable state Then  Service should identify states (Incomplete, and ErrorInViite)") {
    runWithRollback {
      val incomplete = Project(0L, ProjectState.apply(1), "I am Incomplete", "TestUser", DateTime.parse("1901-01-01"),
        "TestUser", DateTime.parse("1901-01-01"), DateTime.now(), "Some additional info",
        Seq(), Seq(), None)
      val incompleteProject = projectService.createRoadLinkProject(incomplete)
      val errorInViite = Project(0L, ProjectState.apply(1), "I am ErrorInViite", "TestUser", DateTime.parse("1901-01-01"),
        "TestUser", DateTime.parse("1901-01-01"), DateTime.now(), "Some additional info",
        Seq(), Seq(), None)
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
      val rap = Project(0L, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("1901-01-01"),
        "TestUser", DateTime.parse("1995-01-01"), DateTime.now(), "Some additional info",
        Seq(), Seq(), None)

      // part1
      // track1

      sqlu"""INSERT INTO LINK (ID) VALUES ('12345')""".execute
      sqlu"""INSERT INTO LINK (ID) VALUES ('12346')""".execute
      sqlu"""INSERT INTO LINK (ID) VALUES ('12347')""".execute
      sqlu"""INSERT INTO LINEAR_LOCATION (ID,ROADWAY_NUMBER,ORDER_NUMBER,LINK_ID,START_MEASURE,END_MEASURE,SIDE,GEOMETRY,VALID_FROM,VALID_TO,CREATED_BY,CREATED_TIME)
            VALUES(nextval('LINEAR_LOCATION_SEQ'), 123, 1, '12345', 0, 9, 2, ST_GeomFromText('LINESTRING(5.0 0.0 0 0, 5.0 9.0 0 9)', 3067), TIMESTAMP '2015-12-30 00:00:00.000000', NULL, 'TR', TIMESTAMP '2015-12-30 00:00:00.000000')""".execute
      sqlu"""INSERT INTO LINEAR_LOCATION (ID,ROADWAY_NUMBER,ORDER_NUMBER,LINK_ID,START_MEASURE,END_MEASURE,SIDE,GEOMETRY,VALID_FROM,VALID_TO,CREATED_BY,CREATED_TIME)
            VALUES(nextval('LINEAR_LOCATION_SEQ'), 123, 2, '12346', 0, 12, 2, ST_GeomFromText('LINESTRING(5.0 9.0 0 9, 5.0 21.0 0 21)', 3067), TIMESTAMP '2015-12-30 00:00:00.000000', NULL, 'TR', TIMESTAMP '2015-12-30 00:00:00.000000')""".execute
      sqlu"""INSERT INTO LINEAR_LOCATION (ID,ROADWAY_NUMBER,ORDER_NUMBER,LINK_ID,START_MEASURE,END_MEASURE,SIDE,GEOMETRY,VALID_FROM,VALID_TO,CREATED_BY,CREATED_TIME)
            VALUES(nextval('LINEAR_LOCATION_SEQ'), 123, 3, '12347', 0, 5, 2, ST_GeomFromText('LINESTRING(5.0 21.0 0 21, 5.0 26.0 0 26)', 3067), TIMESTAMP '2015-12-30 00:00:00.000000', NULL, 'TR', TIMESTAMP '2015-12-30 00:00:00.000000')""".execute

      sqlu"""Insert into ROADWAY (ID,ROADWAY_NUMBER,ROAD_NUMBER,ROAD_PART_NUMBER,TRACK,START_ADDR_M,END_ADDR_M,REVERSED,DISCONTINUITY,START_DATE,END_DATE,CREATED_BY,CREATED_TIME,ADMINISTRATIVE_CLASS,ELY,TERMINATED,VALID_FROM,VALID_TO)
        values (nextval('ROADWAY_SEQ'), 123,9999,1,1,0,26,0,1,to_date('22-10-90','DD-MM-YY'),null,'TR',to_timestamp('21-09-18 12.04.42.970245000','DD-MM-YY HH24.MI.SSXFF'),1,8,0,to_date('16-10-98','DD-MM-YY'),null)""".execute



      // track2
      sqlu"""INSERT INTO LINK (ID) VALUES ('12348')""".execute
      sqlu"""INSERT INTO LINK (ID) VALUES ('12349')""".execute
      sqlu"""INSERT INTO LINK (ID) VALUES ('12350')""".execute
      sqlu"""INSERT INTO LINK (ID) VALUES ('12351')""".execute
      sqlu"""INSERT INTO LINEAR_LOCATION (ID,ROADWAY_NUMBER,ORDER_NUMBER,LINK_ID,START_MEASURE,END_MEASURE,SIDE,GEOMETRY,VALID_FROM,VALID_TO,CREATED_BY,CREATED_TIME)
            VALUES(nextval('LINEAR_LOCATION_SEQ'), 124, 1, '12348', 0, 10, 2, ST_GeomFromText('LINESTRING(0.0 0.0 0 0, 0.0 10.0 0 10)', 3067), TIMESTAMP '2015-12-30 00:00:00.000000', NULL, 'TR', TIMESTAMP '2015-12-30 00:00:00.000000')""".execute
      sqlu"""INSERT INTO LINEAR_LOCATION (ID,ROADWAY_NUMBER,ORDER_NUMBER,LINK_ID,START_MEASURE,END_MEASURE,SIDE,GEOMETRY,VALID_FROM,VALID_TO,CREATED_BY,CREATED_TIME)
            VALUES(nextval('LINEAR_LOCATION_SEQ'), 124, 2, '12349', 0, 8, 2, ST_GeomFromText('LINESTRING(0.0 10.0 0 10, 0.0 18.0 0 18)', 3067), TIMESTAMP '2015-12-30 00:00:00.000000', NULL, 'TR', TIMESTAMP '2015-12-30 00:00:00.000000')""".execute
      sqlu"""INSERT INTO LINEAR_LOCATION (ID,ROADWAY_NUMBER,ORDER_NUMBER,LINK_ID,START_MEASURE,END_MEASURE,SIDE,GEOMETRY,VALID_FROM,VALID_TO,CREATED_BY,CREATED_TIME)
            VALUES(nextval('LINEAR_LOCATION_SEQ'), 124, 3, '12350', 0, 5, 2, ST_GeomFromText('LINESTRING(0.0 18.0 0 18, 0.0 23.0 0 23)', 3067), TIMESTAMP '2015-12-30 00:00:00.000000', NULL, 'TR', TIMESTAMP '2015-12-30 00:00:00.000000')""".execute
      sqlu"""INSERT INTO LINEAR_LOCATION (ID,ROADWAY_NUMBER,ORDER_NUMBER,LINK_ID,START_MEASURE,END_MEASURE,SIDE,GEOMETRY,VALID_FROM,VALID_TO,CREATED_BY,CREATED_TIME)
            VALUES(nextval('LINEAR_LOCATION_SEQ'), 124, 4, '12351', 0, 3, 2, ST_GeomFromText('LINESTRING(0.0 23.0 0 23, 0.0 26.0 0 26)', 3067), TIMESTAMP '2015-12-30 00:00:00.000000', NULL, 'TR', TIMESTAMP '2015-12-30 00:00:00.000000')""".execute

      sqlu"""Insert into ROADWAY (ID,ROADWAY_NUMBER,ROAD_NUMBER,ROAD_PART_NUMBER,TRACK,START_ADDR_M,END_ADDR_M,REVERSED,DISCONTINUITY,START_DATE,END_DATE,CREATED_BY,CREATED_TIME,ADMINISTRATIVE_CLASS,ELY,TERMINATED,VALID_FROM,VALID_TO)
        values (nextval('ROADWAY_SEQ'), 124,9999,1,2,0,26,0,1,to_date('22-10-90','DD-MM-YY'),null,'TR',to_timestamp('21-09-18 12.04.42.970245000','DD-MM-YY HH24.MI.SSXFF'),1,8,0,to_date('16-10-98','DD-MM-YY'),null)""".execute

      // part2
      // track1
      sqlu"""INSERT INTO LINK (ID) VALUES ('12352')""".execute
      sqlu"""INSERT INTO LINK (ID) VALUES ('12353')""".execute
      sqlu"""INSERT INTO LINEAR_LOCATION (ID,ROADWAY_NUMBER,ORDER_NUMBER,LINK_ID,START_MEASURE,END_MEASURE,SIDE,GEOMETRY,VALID_FROM,VALID_TO,CREATED_BY,CREATED_TIME)
            VALUES(nextval('LINEAR_LOCATION_SEQ'), 125, 1, '12352', 0, 2, 2, ST_GeomFromText('LINESTRING(5.0 26.0 0 0, 5.0 28.0 0 2)', 3067), TIMESTAMP '2015-12-30 00:00:00.000000', NULL, 'TR', TIMESTAMP '2015-12-30 00:00:00.000000')""".execute
      sqlu"""INSERT INTO LINEAR_LOCATION (ID,ROADWAY_NUMBER,ORDER_NUMBER,LINK_ID,START_MEASURE,END_MEASURE,SIDE,GEOMETRY,VALID_FROM,VALID_TO,CREATED_BY,CREATED_TIME)
            VALUES(nextval('LINEAR_LOCATION_SEQ'), 125, 2, '12353', 0, 7, 2, ST_GeomFromText('LINESTRING(5.0 28.0 0 2, 5.0 35.0 0 7)', 3067), TIMESTAMP '2015-12-30 00:00:00.000000', NULL, 'TR', TIMESTAMP '2015-12-30 00:00:00.000000')""".execute

      sqlu"""Insert into ROADWAY (ID,ROADWAY_NUMBER,ROAD_NUMBER,ROAD_PART_NUMBER,TRACK,START_ADDR_M,END_ADDR_M,REVERSED,DISCONTINUITY,START_DATE,END_DATE,CREATED_BY,CREATED_TIME,ADMINISTRATIVE_CLASS,ELY,TERMINATED,VALID_FROM,VALID_TO)
        values (nextval('ROADWAY_SEQ'), 125,9999,2,1,0,7,0,1,to_date('22-10-90','DD-MM-YY'),null,'TR',to_timestamp('21-09-18 12.04.42.970245000','DD-MM-YY HH24.MI.SSXFF'),1,8,0,to_date('16-10-98','DD-MM-YY'),null)""".execute

      // track2
      sqlu"""INSERT INTO LINK (ID) VALUES ('12354')""".execute
      sqlu"""INSERT INTO LINK (ID) VALUES ('12355')""".execute
      sqlu"""INSERT INTO LINEAR_LOCATION (ID,ROADWAY_NUMBER,ORDER_NUMBER,LINK_ID,START_MEASURE,END_MEASURE,SIDE,GEOMETRY,VALID_FROM,VALID_TO,CREATED_BY,CREATED_TIME)
            VALUES(nextval('LINEAR_LOCATION_SEQ'), 126, 1, '12354', 0, 3, 2, ST_GeomFromText('LINESTRING(0.0 26.0 0 0, 0.0 29.0 0 3)', 3067), TIMESTAMP '2015-12-30 00:00:00.000000', NULL, 'TR', TIMESTAMP '2015-12-30 00:00:00.000000')""".execute
      sqlu"""INSERT INTO LINEAR_LOCATION (ID,ROADWAY_NUMBER,ORDER_NUMBER,LINK_ID,START_MEASURE,END_MEASURE,SIDE,GEOMETRY,VALID_FROM,VALID_TO,CREATED_BY,CREATED_TIME)
            VALUES(nextval('LINEAR_LOCATION_SEQ'), 126, 2, '12355', 0, 8, 2, ST_GeomFromText('LINESTRING(0.0 29.0 0 3, 0.0 37.0 0 11)', 3067), TIMESTAMP '2015-12-30 00:00:00.000000', NULL, 'TR', TIMESTAMP '2015-12-30 00:00:00.000000')""".execute

      sqlu"""Insert into ROADWAY (ID,ROADWAY_NUMBER,ROAD_NUMBER,ROAD_PART_NUMBER,TRACK,START_ADDR_M,END_ADDR_M,REVERSED,DISCONTINUITY,START_DATE,END_DATE,CREATED_BY,CREATED_TIME,ADMINISTRATIVE_CLASS,ELY,TERMINATED,VALID_FROM,VALID_TO)
        values (nextval('ROADWAY_SEQ'), 126,9999,2,2,0,11,0,1,to_date('22-10-90','DD-MM-YY'),null,'TR',to_timestamp('21-09-18 12.04.42.970245000','DD-MM-YY HH24.MI.SSXFF'),1,8,0,to_date('16-10-98','DD-MM-YY'),null)""".execute

      val project = projectService.createRoadLinkProject(rap)
      val id = project.id
      val part1 = roadwayAddressMapper.getRoadAddressesByLinearLocation(linearLocationDAO.fetchByRoadways(roadwayDAO.fetchAllBySection(9999, 1).map(_.roadwayNumber).toSet))
      val part2 = roadwayAddressMapper.getRoadAddressesByLinearLocation(linearLocationDAO.fetchByRoadways(roadwayDAO.fetchAllBySection(9999, 2).map(_.roadwayNumber).toSet))
      val toProjectLinks = (part1 ++ part2).map(toProjectLink(rap))
      val roadLinks = toProjectLinks.map(toRoadLink)

      when(mockRoadLinkService.getRoadLinksHistoryFromVVH(any[Set[String]])).thenReturn(Seq())
      when(mockRoadLinkService.getRoadLinksByLinkIds(any[Set[String]])).thenReturn(roadLinks)
      projectService.saveProject(project.copy(reservedParts = Seq(ProjectReservedPart(0L, 9999, 1, null, Some(Continuous), Some(8L), None, None, None, None), ProjectReservedPart(0L, 9999, 2, null, Some(Continuous), Some(8L), None, None, None, None))))

      val projectLinks = projectLinkDAO.fetchProjectLinks(id)
      val part1track1 = Set(12345L, 12346L, 12347L).map(_.toString)
      val part1track2 = Set(12348L, 12349L, 12350L, 12351L).map(_.toString)
      val part1track1Links = projectLinks.filter(pl => part1track1.contains(pl.linkId)).map(_.id).toSet
      val part1Track2Links = projectLinks.filter(pl => part1track2.contains(pl.linkId)).map(_.id).toSet

      projectLinkDAO.updateProjectLinksStatus(part1track1Links, LinkStatus.UnChanged, "test")
      projectLinkDAO.updateProjectLinksStatus(part1Track2Links, LinkStatus.UnChanged, "test")
//
//      /**
//        * Tranfering adjacents of part1 to part2
//        */
      val part1AdjacentToPart2IdRightSide = Set(12347L.toString)
      val part1AdjacentToPart2IdLeftSide = Set(12351L.toString)
      val part1AdjacentToPart2LinkRightSide = projectLinks.filter(pl => part1AdjacentToPart2IdRightSide.contains(pl.linkId)).map(_.id).toSet
      val part1AdjacentToPart2LinkLeftSide = projectLinks.filter(pl => part1AdjacentToPart2IdLeftSide.contains(pl.linkId)).map(_.id).toSet

      projectService.updateProjectLinks(id, part1AdjacentToPart2LinkRightSide, Seq(), LinkStatus.Transfer, "test", 9999, 2, 1, None, 1, 5, Some(1L), reversed = false, None)
      projectService.updateProjectLinks(id, part1AdjacentToPart2LinkLeftSide, Seq(), LinkStatus.Transfer, "test", 9999, 2, 2, None, 1, 5, Some(1L), reversed = false, None)

      val part2track1 = Set(12352L.toString, 12353L.toString)
      val part2track2 = Set(12354L.toString, 12355L.toString)
      val part2track1Links = projectLinks.filter(pl => part2track1.contains(pl.linkId)).map(_.id).toSet
      val part2Track2Links = projectLinks.filter(pl => part2track2.contains(pl.linkId)).map(_.id).toSet
      projectService.updateProjectLinks(id, part2track1Links, Seq(), LinkStatus.Transfer, "test", 9999, 2, 1, None, 1, 5, Some(1L), reversed = false, None)
      projectService.updateProjectLinks(id, part2Track2Links, Seq(), LinkStatus.Transfer, "test", 9999, 2, 2, None, 1, 5, Some(1L), reversed = false, None)

      val projectLinks2 = projectLinkDAO.fetchProjectLinks(id)
      val projectLinks3 = ProjectSectionCalculator.assignMValues(projectLinks2).sortBy(_.endAddrMValue)

      val parts = projectLinks3.partition(_.roadPartNumber === 1)
      val part1tracks = parts._1.partition(_.track === Track.RightSide)
      part1tracks._1.maxBy(_.endAddrMValue).endAddrMValue should be(part1tracks._2.maxBy(_.endAddrMValue).endAddrMValue)
      val part2tracks = parts._2.partition(_.track === Track.RightSide)
      part2tracks._1.maxBy(_.endAddrMValue).endAddrMValue should be(part2tracks._2.maxBy(_.endAddrMValue).endAddrMValue)
    }
  }

  test("Test projectService.updateProjectLinks When transferring the rest of the part 2 and then the last ajr 1 & 2 links from part 1 to part 2 and adjust endAddrMValues for last links from transferred part " +
    "Then the mAddressValues of the last links should be equal in both sides of the tracks for part 1." )
  {
    runWithRollback {
      /**
        * Test data
        */
      val rap = Project(0L, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("1901-01-01"),
        "TestUser", DateTime.parse("1991-01-01"), DateTime.now(), "Some additional info",
        Seq(), Seq(), None)

      // part1
      // track1
      sqlu"""INSERT INTO LINK (ID) VALUES ('12345')""".execute
      sqlu"""INSERT INTO LINK (ID) VALUES ('12346')""".execute
      sqlu"""INSERT INTO LINK (ID) VALUES ('12347')""".execute
      sqlu"""INSERT INTO LINEAR_LOCATION (ID,ROADWAY_NUMBER,ORDER_NUMBER,LINK_ID,START_MEASURE,END_MEASURE,SIDE,GEOMETRY,VALID_FROM,VALID_TO,CREATED_BY,CREATED_TIME)
            VALUES(nextval('LINEAR_LOCATION_SEQ'), 1234567, 1, '12345', 0, 9, 2, ST_GeomFromText('LINESTRING(5.0 0.0 0 0, 5.0 9.0 0 9)', 3067), TIMESTAMP '2015-12-30 00:00:00.000000', NULL, 'TR', TIMESTAMP '2015-12-30 00:00:00.000000')""".execute
      sqlu"""INSERT INTO LINEAR_LOCATION (ID,ROADWAY_NUMBER,ORDER_NUMBER,LINK_ID,START_MEASURE,END_MEASURE,SIDE,GEOMETRY,VALID_FROM,VALID_TO,CREATED_BY,CREATED_TIME)
            VALUES(nextval('LINEAR_LOCATION_SEQ'), 1234567, 2, '12346', 0, 12, 2, ST_GeomFromText('LINESTRING(5.0 9.0 0 9, 5.0 21.0 0 21)', 3067), TIMESTAMP '2015-12-30 00:00:00.000000', NULL, 'TR', TIMESTAMP '2015-12-30 00:00:00.000000')""".execute
      sqlu"""INSERT INTO LINEAR_LOCATION (ID,ROADWAY_NUMBER,ORDER_NUMBER,LINK_ID,START_MEASURE,END_MEASURE,SIDE,GEOMETRY,VALID_FROM,VALID_TO,CREATED_BY,CREATED_TIME)
            VALUES(nextval('LINEAR_LOCATION_SEQ'), 1234567, 3, '12347', 0, 5, 2, ST_GeomFromText('LINESTRING(5.0 21.0 0 21, 5.0 26.0 0 26)', 3067), TIMESTAMP '2015-12-30 00:00:00.000000', NULL, 'TR', TIMESTAMP '2015-12-30 00:00:00.000000')""".execute

      sqlu"""Insert into ROADWAY (ID,ROADWAY_NUMBER,ROAD_NUMBER,ROAD_PART_NUMBER,TRACK,START_ADDR_M,END_ADDR_M,REVERSED,DISCONTINUITY,START_DATE,END_DATE,CREATED_BY,CREATED_TIME,ADMINISTRATIVE_CLASS,ELY,TERMINATED,VALID_FROM,VALID_TO)
        values (nextval('ROADWAY_SEQ'), 1234567,9999,1,1,0,26,0,1,to_date('22-10-90','DD-MM-YY'),null,'TR',to_timestamp('21-09-18 12.04.42.970245000','DD-MM-YY HH24.MI.SSXFF'),1,8,0,to_date('16-10-98','DD-MM-YY'),null)""".execute



      // track2
      sqlu"""INSERT INTO LINK (ID) VALUES ('12348')""".execute
      sqlu"""INSERT INTO LINK (ID) VALUES ('12349')""".execute
      sqlu"""INSERT INTO LINK (ID) VALUES ('12350')""".execute
      sqlu"""INSERT INTO LINK (ID) VALUES ('12351')""".execute
      sqlu"""INSERT INTO LINEAR_LOCATION (ID,ROADWAY_NUMBER,ORDER_NUMBER,LINK_ID,START_MEASURE,END_MEASURE,SIDE,GEOMETRY,VALID_FROM,VALID_TO,CREATED_BY,CREATED_TIME)
            VALUES(nextval('LINEAR_LOCATION_SEQ'), 1234568, 1, '12348', 0, 10, 2, ST_GeomFromText('LINESTRING(0.0 0.0 0 0, 0.0 10.0 0 10)', 3067), TIMESTAMP '2015-12-30 00:00:00.000000', NULL, 'TR', TIMESTAMP '2015-12-30 00:00:00.000000')""".execute
      sqlu"""INSERT INTO LINEAR_LOCATION (ID,ROADWAY_NUMBER,ORDER_NUMBER,LINK_ID,START_MEASURE,END_MEASURE,SIDE,GEOMETRY,VALID_FROM,VALID_TO,CREATED_BY,CREATED_TIME)
            VALUES(nextval('LINEAR_LOCATION_SEQ'), 1234568, 2, '12349', 0, 8, 2, ST_GeomFromText('LINESTRING(0.0 10.0 0 10, 0.0 18.0 0 18)', 3067), TIMESTAMP '2015-12-30 00:00:00.000000', NULL, 'TR', TIMESTAMP '2015-12-30 00:00:00.000000')""".execute
      sqlu"""INSERT INTO LINEAR_LOCATION (ID,ROADWAY_NUMBER,ORDER_NUMBER,LINK_ID,START_MEASURE,END_MEASURE,SIDE,GEOMETRY,VALID_FROM,VALID_TO,CREATED_BY,CREATED_TIME)
            VALUES(nextval('LINEAR_LOCATION_SEQ'), 1234568, 3, '12350', 0, 5, 2, ST_GeomFromText('LINESTRING(0.0 18.0 0 18, 0.0 23.0 0 23)', 3067), TIMESTAMP '2015-12-30 00:00:00.000000', NULL, 'TR', TIMESTAMP '2015-12-30 00:00:00.000000')""".execute
      sqlu"""INSERT INTO LINEAR_LOCATION (ID,ROADWAY_NUMBER,ORDER_NUMBER,LINK_ID,START_MEASURE,END_MEASURE,SIDE,GEOMETRY,VALID_FROM,VALID_TO,CREATED_BY,CREATED_TIME)
            VALUES(nextval('LINEAR_LOCATION_SEQ'), 1234568, 4, '12351', 0, 3, 2, ST_GeomFromText('LINESTRING(0.0 23.0 0 23, 0.0 26.0 0 26)', 3067), TIMESTAMP '2015-12-30 00:00:00.000000', NULL, 'TR', TIMESTAMP '2015-12-30 00:00:00.000000')""".execute

      sqlu"""Insert into ROADWAY (ID,ROADWAY_NUMBER,ROAD_NUMBER,ROAD_PART_NUMBER,TRACK,START_ADDR_M,END_ADDR_M,REVERSED,DISCONTINUITY,START_DATE,END_DATE,CREATED_BY,CREATED_TIME,ADMINISTRATIVE_CLASS,ELY,TERMINATED,VALID_FROM,VALID_TO)
        values (nextval('ROADWAY_SEQ'), 1234568,9999,1,2,0,26,0,1,to_date('22-10-90','DD-MM-YY'),null,'TR',to_timestamp('21-09-18 12.04.42.970245000','DD-MM-YY HH24.MI.SSXFF'),1,8,0,to_date('16-10-98','DD-MM-YY'),null)""".execute

      // part2
      // track1
      sqlu"""INSERT INTO LINK (ID) VALUES ('12352')""".execute
      sqlu"""INSERT INTO LINK (ID) VALUES ('12353')""".execute
      sqlu"""INSERT INTO LINEAR_LOCATION (ID,ROADWAY_NUMBER,ORDER_NUMBER,LINK_ID,START_MEASURE,END_MEASURE,SIDE,GEOMETRY,VALID_FROM,VALID_TO,CREATED_BY,CREATED_TIME)
            VALUES(nextval('LINEAR_LOCATION_SEQ'), 1234569, 1, '12352', 0, 2, 2,ST_GeomFromText('LINESTRING(5.0 26.0 0 0, 5.0 28.0 0 2)', 3067), TIMESTAMP '2015-12-30 00:00:00.000000', NULL, 'TR', TIMESTAMP '2015-12-30 00:00:00.000000')""".execute
      sqlu"""INSERT INTO LINEAR_LOCATION (ID,ROADWAY_NUMBER,ORDER_NUMBER,LINK_ID,START_MEASURE,END_MEASURE,SIDE,GEOMETRY,VALID_FROM,VALID_TO,CREATED_BY,CREATED_TIME)
            VALUES(nextval('LINEAR_LOCATION_SEQ'), 1234569, 2, '12353', 0, 7, 2, ST_GeomFromText('LINESTRING(5.0 28.0 0 2, 5.0 35.0 0 7)', 3067), TIMESTAMP '2015-12-30 00:00:00.000000', NULL, 'TR', TIMESTAMP '2015-12-30 00:00:00.000000')""".execute

      sqlu"""Insert into ROADWAY (ID,ROADWAY_NUMBER,ROAD_NUMBER,ROAD_PART_NUMBER,TRACK,START_ADDR_M,END_ADDR_M,REVERSED,DISCONTINUITY,START_DATE,END_DATE,CREATED_BY,CREATED_TIME,ADMINISTRATIVE_CLASS,ELY,TERMINATED,VALID_FROM,VALID_TO)
        values (nextval('ROADWAY_SEQ'), 1234569,9999,2,1,0,7,0,1,to_date('22-10-90','DD-MM-YY'),null,'TR',to_timestamp('21-09-18 12.04.42.970245000','DD-MM-YY HH24.MI.SSXFF'),1,8,0,to_date('16-10-98','DD-MM-YY'),null)""".execute

      // track2
      sqlu"""INSERT INTO LINK (ID) VALUES ('12354')""".execute
      sqlu"""INSERT INTO LINK (ID) VALUES ('12355')""".execute
      sqlu"""INSERT INTO LINEAR_LOCATION (ID,ROADWAY_NUMBER,ORDER_NUMBER,LINK_ID,START_MEASURE,END_MEASURE,SIDE,GEOMETRY,VALID_FROM,VALID_TO,CREATED_BY,CREATED_TIME)
            VALUES(nextval('LINEAR_LOCATION_SEQ'), 1234570, 1, '12354', 0, 3, 2, ST_GeomFromText('LINESTRING(0.0 26.0 0 0, 0.0 29.0 0 3)', 3067), TIMESTAMP '2015-12-30 00:00:00.000000', NULL, 'TR', TIMESTAMP '2015-12-30 00:00:00.000000')""".execute
      sqlu"""INSERT INTO LINEAR_LOCATION (ID,ROADWAY_NUMBER,ORDER_NUMBER,LINK_ID,START_MEASURE,END_MEASURE,SIDE,GEOMETRY,VALID_FROM,VALID_TO,CREATED_BY,CREATED_TIME)
            VALUES(nextval('LINEAR_LOCATION_SEQ'), 1234570, 2, '12355', 0, 8, 2, ST_GeomFromText('LINESTRING(0.0 29.0 0 3, 0.0 37.0 0 11)', 3067), TIMESTAMP '2015-12-30 00:00:00.000000', NULL, 'TR', TIMESTAMP '2015-12-30 00:00:00.000000')""".execute

      sqlu"""Insert into ROADWAY (ID,ROADWAY_NUMBER,ROAD_NUMBER,ROAD_PART_NUMBER,TRACK,START_ADDR_M,END_ADDR_M,REVERSED,DISCONTINUITY,START_DATE,END_DATE,CREATED_BY,CREATED_TIME,ADMINISTRATIVE_CLASS,ELY,TERMINATED,VALID_FROM,VALID_TO)
        values (nextval('ROADWAY_SEQ'), 1234570,9999,2,2,0,11,0,1,to_date('22-10-90','DD-MM-YY'),null,'TR',to_timestamp('21-09-18 12.04.42.970245000','DD-MM-YY HH24.MI.SSXFF'),1,8,0,to_date('16-10-98','DD-MM-YY'),null)""".execute

      val project = projectService.createRoadLinkProject(rap)
      val id = project.id
      val part1 = roadwayAddressMapper.getRoadAddressesByLinearLocation(linearLocationDAO.fetchByRoadways(roadwayDAO.fetchAllBySection(9999, 1).map(_.roadwayNumber).toSet))
      val part2 = roadwayAddressMapper.getRoadAddressesByLinearLocation(linearLocationDAO.fetchByRoadways(roadwayDAO.fetchAllBySection(9999, 2).map(_.roadwayNumber).toSet))
      val toProjectLinks = (part1 ++ part2).map(toProjectLink(rap))
      val roadLinks = toProjectLinks.map(toRoadLink)
      when(mockRoadLinkService.getRoadLinksHistoryFromVVH(any[Set[String]])).thenReturn(Seq())
      when(mockRoadLinkService.getRoadLinksByLinkIds(any[Set[String]])).thenReturn(roadLinks)
      projectService.saveProject(project.copy(reservedParts = Seq(ProjectReservedPart(0L, 9999, 1, null, Some(Continuous), Some(8L), None, None, None, None), ProjectReservedPart(0L, 9999, 2, null, Some(Continuous), Some(8L), None, None, None, None))))

      val projectLinks = projectLinkDAO.fetchProjectLinks(id)
      val part1track1 = Set(12345L, 12346L, 12347L).map(_.toString)
      val part1track2 = Set(12348L, 12349L, 12350L, 12351L).map(_.toString)
      val part1track1Links = projectLinks.filter(pl => part1track1.contains(pl.linkId)).map(_.id).toSet
      val part1Track2Links = projectLinks.filter(pl => part1track2.contains(pl.linkId)).map(_.id).toSet

      projectLinkDAO.updateProjectLinksStatus(part1track1Links, LinkStatus.UnChanged, "test")
      projectLinkDAO.updateProjectLinksStatus(part1Track2Links, LinkStatus.UnChanged, "test")

      val part2track1 = Set(12352L, 12353L).map(_.toString)
      val part2track2 = Set(12354L, 12355L).map(_.toString)
      val part2track1Links = projectLinks.filter(pl => part2track1.contains(pl.linkId)).map(_.id).toSet
      val part2Track2Links = projectLinks.filter(pl => part2track2.contains(pl.linkId)).map(_.id).toSet

      projectService.updateProjectLinks(id, part2track1Links, Seq(), LinkStatus.Transfer, "test",
        newRoadNumber = 9999, newRoadPartNumber = 2, newTrackCode = 1, userDefinedEndAddressM = None, administrativeClass = 1, discontinuity = 5, ely = Some(1L), roadName = None)
      projectService.updateProjectLinks(id, part2Track2Links, Seq(), LinkStatus.Transfer, "test",
        newRoadNumber = 9999, newRoadPartNumber = 2, newTrackCode = 2, userDefinedEndAddressM = None, administrativeClass = 1, discontinuity = 5, ely = Some(1L), roadName = None)
      /**
        * Tranfering adjacents of part1 to part2
        */
      val part1AdjacentToPart2IdRightSide = Set(12347L.toString)
      val part1AdjacentToPart2IdLeftSide = Set(12351L.toString)
      val part1AdjacentToPart2LinkRightSide = projectLinks.filter(pl => part1AdjacentToPart2IdRightSide.contains(pl.linkId)).map(_.id).toSet
      val part1AdjacentToPart2LinkLeftSide = projectLinks.filter(pl => part1AdjacentToPart2IdLeftSide.contains(pl.linkId)).map(_.id).toSet


      projectService.updateProjectLinks(id, part1AdjacentToPart2LinkRightSide, Seq(), LinkStatus.Transfer, "test", newRoadNumber = 9999, newRoadPartNumber = 2, newTrackCode = 1, userDefinedEndAddressM = None, administrativeClass = 1, discontinuity = 5, ely = Some(1L), roadName = None)
      projectService.updateProjectLinks(id, part1AdjacentToPart2LinkLeftSide, Seq(), LinkStatus.Transfer, "test", newRoadNumber = 9999, newRoadPartNumber = 2, newTrackCode = 2, userDefinedEndAddressM = None, administrativeClass = 1, discontinuity = 5, ely = Some(1L), roadName = None)

      val projectLinks_ = projectLinkDAO.fetchProjectLinks(id)
      val projectLinks2 = ProjectSectionCalculator.assignMValues(projectLinks_)

      val parts = projectLinks2.partition(_.roadPartNumber === 1)
      val part1tracks = parts._1.partition(_.track === Track.RightSide)
      part1tracks._1.maxBy(_.endAddrMValue).endAddrMValue should be(part1tracks._2.maxBy(_.endAddrMValue).endAddrMValue)
      val part2tracks = parts._2.partition(_.track === Track.RightSide)
      part2tracks._1.maxBy(_.endAddrMValue).endAddrMValue should be(part2tracks._2.maxBy(_.endAddrMValue).endAddrMValue)
    }
  }

  test("Test expireHistoryRows When link is renumbered Then set end date to old roadway") {
    runWithRollback {

      // Create roadway
      val linkId = 10000.toString
      val oldEndAddress = 100
      val roadway = Roadway(Sequences.nextRoadwayId, Sequences.nextRoadwayNumber, 9999, 1, AdministrativeClass.State, Track.Combined, Discontinuity.Continuous, 0, oldEndAddress, reversed = false, DateTime.now().minusYears(10), None, "test", Some("Test Road"), 1, TerminationCode.NoTermination, DateTime.now().minusYears(10), None)

      roadwayDAO.create(Seq(roadway))

      // Create project link
      val roadAddressLength = roadway.endAddrMValue - roadway.startAddrMValue
      val projectId = Sequences.nextViiteProjectId
      val newLength = 110.123
      val newEndAddr = 110
      val projectLink = ProjectLink(Sequences.nextProjectLinkId, roadway.roadNumber, roadway.roadPartNumber, roadway.track, roadway.discontinuity, roadway.startAddrMValue, roadway.endAddrMValue + 10, roadway.startAddrMValue, roadway.endAddrMValue, Some(DateTime.now().plusMonths(1)), None, Some("test"), linkId, 0.0, newLength, SideCode.TowardsDigitizing, (RoadAddressCP, RoadAddressCP), (NoCP, NoCP), Seq(Point(0.0, 0.0), Point(0.0, newLength)), projectId, LinkStatus.Numbering, roadway.administrativeClass, LinkGeomSource.NormalLinkInterface, newLength, roadway.id, 1234, roadway.ely, reversed = false, None, DateTime.now().minusMonths(10).getMillis, roadway.roadwayNumber, roadway.roadName, Some(roadAddressLength), Some(0), Some(newEndAddr), Some(roadway.track), Some(roadway.roadNumber), Some(roadway.roadPartNumber))

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
      val currentRoadway  = Roadway(Sequences.nextRoadwayId, roadwayNumber, 9999, 1, AdministrativeClass.State, Track.Combined, Discontinuity.Continuous, 0, oldEndAddress, reversed = false, DateTime.now().minusYears(10), None, "test", Some("Test Road"), 1, TerminationCode.NoTermination, DateTime.now().minusYears(10), None)
      val history_roadway = Seq(
                                Roadway(Sequences.nextRoadwayId, roadwayNumber, 9999, 1, AdministrativeClass.Municipality, Track.Combined, Discontinuity.Continuous, 0, oldEndAddress, reversed = false, DateTime.now().minusYears(15), Some(DateTime.now().minusYears(10)), "test", Some("Test Road"), 1, TerminationCode.NoTermination, DateTime.now().minusYears(15), None),
                                Roadway(Sequences.nextRoadwayId, roadwayNumber, 9999, 1, AdministrativeClass.State, Track.Combined, Discontinuity.Continuous, 0, oldEndAddress, reversed = true, DateTime.now().minusYears(20), Some(DateTime.now().minusYears(15)), "test", Some("Test Road"), 1, TerminationCode.NoTermination, DateTime.now().minusYears(20), None)
                               )
      roadwayDAO.create(Seq(currentRoadway) ++ history_roadway)

      // Create project
      val rap = Project(0L, ProjectState.UpdatingToRoadNetwork, "TestProject", "TestUser", DateTime.parse("1901-01-01"),
        "TestUser", currentRoadway.startDate.plusYears(1), DateTime.now(), "Some additional info",
        Seq(), Seq(), None)
      val project = projectService.createRoadLinkProject(rap)
      val projectId = project.id
      projectReservedPartDAO.reserveRoadPart(projectId, currentRoadway.roadNumber, currentRoadway.roadPartNumber, "TestUser")

      val linearLocation = dummyLinearLocation(Sequences.nextLinearLocationId, currentRoadway.roadwayNumber, 0, linkId, 0.0, currentRoadway.endAddrMValue,0L)
      val projectLink = ProjectLink(Sequences.nextProjectLinkId, currentRoadway.roadNumber, currentRoadway.roadPartNumber, currentRoadway.track, Discontinuity.EndOfRoad, currentRoadway.startAddrMValue, currentRoadway.endAddrMValue, currentRoadway.startAddrMValue, currentRoadway.endAddrMValue, Some(DateTime.now().plusMonths(1)), None, Some("test"), linkId, 0.0, currentRoadway.endAddrMValue, SideCode.TowardsDigitizing, (RoadAddressCP, RoadAddressCP), (NoCP, NoCP), Seq(Point(0.0, 0.0), Point(0.0, currentRoadway.endAddrMValue)), projectId, LinkStatus.Transfer, currentRoadway.administrativeClass, LinkGeomSource.NormalLinkInterface, currentRoadway.endAddrMValue, currentRoadway.id, linearLocation.id, currentRoadway.ely, reversed = true, None, DateTime.now().minusMonths(10).getMillis, currentRoadway.roadwayNumber, currentRoadway.roadName, None, None, None, None, None, None)

      linearLocationDAO.create(Seq(linearLocation))
      projectLinkDAO.create(Seq(projectLink))

      // Check before change
      roadwayDAO.fetchAllByRoadwayId(Seq(currentRoadway.id)).head.validTo should be(None)

      when(mockNodesAndJunctionsService.expireObsoleteNodesAndJunctions(any[Seq[ProjectLink]], any[Option[DateTime]], any[String])).thenReturn(Seq())

      projectService.updateRoadwaysAndLinearLocationsWithProjectLinks(projectId)

      // Current roadway was is expired
      roadwayDAO.fetchAllByRoadwayId(Seq(currentRoadway.id)).isEmpty should be(true)

      val AllByRoadAndPart = roadwayDAO.fetchAllByRoadAndPart(currentRoadway.roadNumber, currentRoadway.roadPartNumber, withHistory = true)
      AllByRoadAndPart should have size 4

      val (historyRows, newCurrent) = AllByRoadAndPart.partition(_.endDate.isDefined)
      newCurrent should have size 1
      historyRows should have size 3

      newCurrent.foreach(_.endAddrMValue should be(currentRoadway.endAddrMValue))
      historyRows.foreach(_.endAddrMValue should be(currentRoadway.endAddrMValue))

      newCurrent.foreach(_.reversed should be(false))
      val (reversedHistory, notReversedHistory): (Seq[Roadway], Seq[Roadway]) = historyRows.partition(_.reversed)

      reversedHistory should have size 2
      notReversedHistory should have size 1

      notReversedHistory.head.startDate.getYear should be(2002)

      val (municipalityHistory, stateHistory) = reversedHistory.partition(_.administrativeClass == AdministrativeClass.Municipality)
      municipalityHistory should have size 1
      municipalityHistory.head.startDate.getYear should be(2007)
      stateHistory.head.startDate.getYear should be(2012)
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
      val currentRoadway  = Roadway(Sequences.nextRoadwayId, roadwayNumber, 9999, 1, AdministrativeClass.State, Track.Combined, Discontinuity.Continuous, 0, oldEndAddress, reversed = false, DateTime.now().minusYears(10).withTime(0, 0, 0, 0), None, "test", Some("Test Road"), 1, TerminationCode.NoTermination, DateTime.now().minusYears(10), None)
      val history_roadway = Seq(
        Roadway(Sequences.nextRoadwayId, roadwayNumber, 9999, 1, AdministrativeClass.Municipality, Track.Combined, Discontinuity.Continuous, 0, oldEndAddress, reversed = false, DateTime.now().minusYears(15).withTime(0, 0, 0, 0), Some(DateTime.now().minusYears(10).withTime(0, 0, 0, 0)), "test", Some("Test Road"), 1, TerminationCode.NoTermination, DateTime.now().minusYears(15), None),
        Roadway(Sequences.nextRoadwayId, roadwayNumber, 9999, 1, AdministrativeClass.State, Track.Combined, Discontinuity.Continuous, 0, oldEndAddress, reversed = false, DateTime.now().minusYears(20).withTime(0, 0, 0, 0), Some(DateTime.now().minusYears(15).withTime(0, 0, 0, 0)), "test", Some("Test Road"), 1, TerminationCode.NoTermination, DateTime.now().minusYears(20), None)
      )
      roadwayDAO.create(Seq(currentRoadway) ++ history_roadway)

      // Create project link
      val rap = Project(0L, ProjectState.UpdatingToRoadNetwork, "TestProject", "TestUser", DateTime.parse("1901-01-01"),
        "TestUser", currentRoadway.startDate.plusYears(1), DateTime.now(), "Some additional info",
        Seq(), Seq(), None)
      val project = projectService.createRoadLinkProject(rap)
      val projectId = project.id

      val linearLocation = Seq(
        dummyLinearLocation(currentRoadway.roadwayNumber, 0, linkId, 0.0, currentRoadway.endAddrMValue).copy(id = linkId.toLong),
        dummyLinearLocation(currentRoadway.roadwayNumber, 0, linkId+1, 0.0, currentRoadway.endAddrMValue).copy(id = (linkId+1).toLong),
        dummyLinearLocation(currentRoadway.roadwayNumber, 0, linkId+2, 0.0, currentRoadway.endAddrMValue).copy(id = (linkId+2).toLong)
      )

      val projectLinks = Seq(
        ProjectLink(Sequences.nextProjectLinkId, currentRoadway.roadNumber, currentRoadway.roadPartNumber, currentRoadway.track, Discontinuity.Continuous,   0, 100,   0, 100, Some(DateTime.now().plusMonths(1)), None, Some("test"), linkId, 0.0, 100, SideCode.TowardsDigitizing, (RoadAddressCP, RoadAddressCP), (NoCP, NoCP), Seq(Point(0.0, 0.0), Point(0.0, currentRoadway.endAddrMValue)), projectId, LinkStatus.Transfer, currentRoadway.administrativeClass, LinkGeomSource.NormalLinkInterface, 100, currentRoadway.id, linearLocation(0).id, currentRoadway.ely, reversed = false, None, DateTime.now().minusMonths(10).getMillis, currentRoadway.roadwayNumber+1, currentRoadway.roadName, None, None, None, None, None, None),
        ProjectLink(Sequences.nextProjectLinkId, currentRoadway.roadNumber, currentRoadway.roadPartNumber, currentRoadway.track, Discontinuity.Continuous, 100, 200, 100, 200, Some(DateTime.now().plusMonths(1)), None, Some("test"), linkId+1, 0.0, 100, SideCode.TowardsDigitizing, (RoadAddressCP, RoadAddressCP), (NoCP, NoCP), Seq(Point(0.0, 0.0), Point(0.0, currentRoadway.endAddrMValue)), projectId, LinkStatus.Transfer, AdministrativeClass.Municipality, LinkGeomSource.NormalLinkInterface, 100, currentRoadway.id, linearLocation(1).id, currentRoadway.ely, reversed = false, None, DateTime.now().minusMonths(10).getMillis, currentRoadway.roadwayNumber+2, currentRoadway.roadName, None, None, None, None, None, None),
        ProjectLink(Sequences.nextProjectLinkId, currentRoadway.roadNumber, currentRoadway.roadPartNumber, currentRoadway.track, Discontinuity.Continuous, 200, 300, 200, 300, Some(DateTime.now().plusMonths(1)), None, Some("test"), linkId+2, 0.0, 100, SideCode.TowardsDigitizing, (RoadAddressCP, RoadAddressCP), (NoCP, NoCP), Seq(Point(0.0, 0.0), Point(0.0, currentRoadway.endAddrMValue)), projectId, LinkStatus.Transfer, currentRoadway.administrativeClass, LinkGeomSource.NormalLinkInterface, 100, currentRoadway.id, linearLocation(2).id, currentRoadway.ely, reversed = false, None, DateTime.now().minusMonths(10).getMillis, currentRoadway.roadwayNumber+3, currentRoadway.roadName, None, None, None, None, None, None)
      )

      linearLocationDAO.create(linearLocation)
      projectReservedPartDAO.reserveRoadPart(projectId, currentRoadway.roadNumber, currentRoadway.roadPartNumber, "TestUser")

      projectLinkDAO.create(projectLinks)

      // Check before change
      roadwayDAO.fetchAllByRoadwayId(Seq(currentRoadway.id) ++ history_roadway.map(_.id)) foreach {_.validTo should be(None)}

      when(mockNodesAndJunctionsService.expireObsoleteNodesAndJunctions(any[Seq[ProjectLink]], any[Option[DateTime]], any[String])).thenReturn(Seq())

      projectService.updateRoadwaysAndLinearLocationsWithProjectLinks(projectId)

      // Current roadway and history rows are expired.
      roadwayDAO.fetchAllByRoadwayId(Seq(currentRoadway.id) ++ history_roadway.map(_.id)).toArray shouldBe empty

      val AllByRoadAndPart = roadwayDAO.fetchAllByRoadAndPart(currentRoadway.roadNumber, currentRoadway.roadPartNumber, withHistory = true)
      val (historyRows, newCurrent) = AllByRoadAndPart.partition(_.endDate.isDefined)

      newCurrent should have size 3
      historyRows should have size 7

      // Check newly created current addresses
      newCurrent.filter(hr => hr.startAddrMValue ==   0 && hr.endAddrMValue == 100) should have size 1
      newCurrent.filter(hr => hr.startAddrMValue == 100 && hr.endAddrMValue == 200) should have size 1
      newCurrent.filter(hr => hr.startAddrMValue == 200 && hr.endAddrMValue == 300) should have size 1

      // The same validFrom date for all
      AllByRoadAndPart.map(_.validFrom).distinct should have size 1

      val historiesByAddrM = historyRows.groupBy(_.startAddrMValue)

      // Check dates
      historiesByAddrM.values.foreach(hrs => {
        hrs.filter(hr => hr.startDate == history_roadway.head.startDate && hr.endDate == history_roadway.head.endDate) should have size 1
        hrs.filter(hr => hr.startDate == history_roadway.last.startDate && hr.endDate == history_roadway.last.endDate) should have size 1
      })

      // Check history addresses
      historyRows.filter(hr => hr.startAddrMValue ==   0 && hr.endAddrMValue == 100) should have size 2
      historyRows.filter(hr => hr.startAddrMValue == 100 && hr.endAddrMValue == 200) should have size 3
      historyRows.filter(hr => hr.startAddrMValue == 200 && hr.endAddrMValue == 300) should have size 2
    }
  }

  test("Test updateRoadwaysAndLinearLocationsWithProjectLinks " +
       "When ProjectLinks have a split " +
       "Then split should be merged and geometry points in the same order as before the split.") {
    runWithRollback {

      val roadNumber     = 19511
      val roadPartNumber = 1
      val createdBy      = "UnitTest"
      val roadName       = None
      val projectId      = Sequences.nextViiteProjectId
      val linkIdToTest   = 12179260.toString

      val projectLinks = Seq(
        ProjectLink(1025,roadNumber,roadPartNumber,Track.RightSide,Discontinuity.Continuous,3689,3699,3825,3835,None,None,Some(createdBy),linkIdToTest,0.0,10.117,SideCode.TowardsDigitizing,(NoCP,NoCP),(NoCP,JunctionPointCP),List(Point(393383.0,7288225.0,0.0), Point(393376.0,7288232.0,0.0)),projectId,LinkStatus.Transfer,AdministrativeClass.State,FrozenLinkInterface,10.117,57052,393536,14,reversed = false,Some(linkIdToTest),1615244419000L,13659596,roadName,None,None,None,None,None,None),
        ProjectLink(1028,roadNumber,roadPartNumber,Track.RightSide,Discontinuity.Continuous,3699,3719,3835,3855,None,None,Some(createdBy),linkIdToTest,10.117,30.351,SideCode.TowardsDigitizing,(NoCP,RoadAddressCP),(NoCP,JunctionPointCP),List(Point(393376.0,7288232.0,0.0), Point(393372.0,7288235.0,0.0), Point(393361.0,7288245.0,0.0)),projectId,LinkStatus.Transfer,AdministrativeClass.State,FrozenLinkInterface,20.234,57052,393536,14,reversed = false,Some(linkIdToTest),1615244419000L,13659596,roadName,None,None,None,None,None,None)
      )

      val roadways = Seq(
        Roadway(57052,13659596,19511,1,AdministrativeClass.State,Track.RightSide,Discontinuity.Continuous,3659,3855,reversed = false,DateTime.parse("2016-03-01T00:00:00.000+02:00"),None,createdBy,roadName,14,TerminationCode.NoTermination,DateTime.parse("2016-03-30T00:00:00.000+03:00"),None)
      )

      val linearLocations = Seq(
        LinearLocation(393536, 2.0, linkIdToTest, 0.0, 30.351, SideCode.TowardsDigitizing, 1615244419000L, (CalibrationPointReference(None,None),CalibrationPointReference(Some(3855),Some(JunctionPointCP))), List(Point(393383.0,7288225.0,0.0), Point(393361.0,7288245.0,0.0)), FrozenLinkInterface, 13659596, Some(DateTime.parse("2016-03-30T00:00:00.000+03:00")), None)
      )

      // Create project
      val rap = Project(projectId, ProjectState.UpdatingToRoadNetwork, "TestProject", createdBy, DateTime.parse("1901-01-01"),
        createdBy, DateTime.parse("1971-01-01"), DateTime.now(), "Some additional info", Seq(), Seq(), None)
      val project = projectDAO.create(rap)
      projectReservedPartDAO.reserveRoadPart(projectId, roadNumber, roadPartNumber, "UnitTest")

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

      val roadLink = RoadLink(5170939L.toString, Seq(Point(535605.272, 6982204.22, 85.90899999999965)), 540.3960283713503, State, TrafficDirection.AgainstDigitizing, Some("25.06.2015 03:00:00"), Some("vvh_modified"), InUse, NormalLinkInterface, 749, "")

      val projectId = Sequences.nextViiteProjectId

      val roadwayId = Sequences.nextRoadwayId

      val roadAddressProject = Project(projectId, ProjectState.apply(1), "TestProject", "TestUser", DateTime.now(), "TestUser", DateTime.parse("1970-01-01"), DateTime.now(), "Some additional info", Seq(), Seq(), None)

      val projectLink = dummyProjectLink(1, 1, Track.Combined, Discontinuity.EndOfRoad, 0, 100, Some(DateTime.parse("1970-01-01")), None, 12345.toString, 0, 100, SideCode.TowardsDigitizing, LinkStatus.UnChanged, projectId, State, Seq(Point(0.0, 0.0), Point(0.0, 100.0)))

      val roadway = dummyRoadway(roadwayNumber =1234l, roadNumber = 1, roadPartNumber = 1, startAddrM = 0, endAddrM = 100, startDate = DateTime.now(), endDate = None, roadwayId = roadwayId)

      roadwayDAO.create(Seq(roadway))

      when(mockRoadLinkService.getRoadLinksHistoryFromVVH(any[Set[String]])).thenReturn(Seq())
      when(mockRoadLinkService.getRoadLinksByLinkIds(any[Set[String]])).thenAnswer(
        toMockAnswer(Seq(projectLink), roadLink)
      )
      val historicRoadId = projectService.expireHistoryRows(roadwayId)
      historicRoadId should be (1)
    }
  }

  test("Test Road names should not have valid road name for any roadnumber after TR response") {
    runWithRollback {
      val projectId = Sequences.nextViiteProjectId
      sqlu"""INSERT INTO ROAD_NAME VALUES (nextval('ROAD_NAME_SEQ'), 66666, 'ROAD TEST', TIMESTAMP '2018-03-23 12:26:36.000000', null, TIMESTAMP '2018-03-23 12:26:36.000000', null, 'test user', TIMESTAMP '2018-03-23 12:26:36.000000')""".execute

      sqlu"""INSERT INTO PROJECT VALUES($projectId, 2, 'test project', 'silari', TIMESTAMP '2018-03-23 11:36:15.000000', '-', TIMESTAMP '2018-03-23 12:26:33.000000', NULL, NULL, NULL, 1, 533406.572, 6994060.048, 12)""".execute
      sqlu"""INSERT INTO PROJECT_RESERVED_ROAD_PART VALUES (${Sequences.nextViitePrimaryKeySeqValue}, 66666, 1, $projectId, '-')""".execute

      sqlu"""INSERT INTO PROJECT_LINK (ID, PROJECT_ID, TRACK, DISCONTINUITY_TYPE, ROAD_NUMBER, ROAD_PART_NUMBER,
          START_ADDR_M, END_ADDR_M, CREATED_BY, MODIFIED_BY, CREATED_DATE, MODIFIED_DATE, STATUS,
          ADMINISTRATIVE_CLASS, ROADWAY_ID, LINEAR_LOCATION_ID, CONNECTED_LINK_ID, ELY, REVERSED, SIDE, START_MEASURE, END_MEASURE,
          LINK_ID, ADJUSTED_TIMESTAMP, LINK_SOURCE, GEOMETRY, ORIGINAL_START_ADDR_M, ORIGINAL_END_ADDR_M, ROADWAY_NUMBER,
          START_CALIBRATION_POINT, END_CALIBRATION_POINT, ORIG_START_CALIBRATION_POINT, ORIG_END_CALIBRATION_POINT)
        VALUES (${Sequences.nextProjectLinkId}, $projectId, 0, 5, 66666, 1,
          0, 86, 'test user', 'test user', TIMESTAMP '2018-03-23 12:26:36.000000', TIMESTAMP '2018-03-23 00:00:00.000000', 2,
          1, NULL, NULL, NULL, 8, 0, NULL, 0, 85.617,
          NULL, 1543328166000, 1, NULL, 0, 0, NULL,
          3, 3, 0, 0)""".execute

      sqlu"""INSERT INTO PROJECT_LINK_NAME VALUES (nextval('PROJECT_LINK_NAME_SEQ'), $projectId, 66666, 'ROAD TEST')""".execute
      val namesBeforeUpdate = RoadNameDAO.getLatestRoadName(66666)
      val changeInfos = List(
        RoadwayChangeInfo(AddressChangeType.New,
          source = dummyRoadwayChangeSection(Some(66666L), Some(1L), Some(0L), Some(0L), Some(100L), Some(AdministrativeClass.apply(1)), Some(Discontinuity.Continuous), Some(8L)),
          target = dummyRoadwayChangeSection(Some(66666L), Some(1L), Some(0L), Some(0L), Some(100L), Some(AdministrativeClass.apply(1)), Some(Discontinuity.Continuous), Some(8L)),
          Continuous, AdministrativeClass.apply(1), reversed = false, 1)
      )

      val changes = List(
        ProjectRoadwayChange(projectId, Some("test project"), 8, "Test", DateTime.now(), changeInfos.head, DateTime.now(), Some(0))
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
    val roadNumber = 5L
    val roadPartNumber = 207L
    val testRoadName = "forTestingPurposes"
    runWithRollback {
      val rap = Project(0L, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("1901-01-01"),
        "TestUser", DateTime.parse("1971-01-01"), DateTime.now(), "Some additional info",
        Seq(), Seq(), None)
      val project = projectService.createRoadLinkProject(rap)
      val projectId = project.id

      mockForProject(projectId, roadwayAddressMapper.getRoadAddressesByRoadway(roadwayDAO.fetchAllByRoadAndPart(roadNumber, roadPartNumber)).map(toProjectLink(project)))
      projectService.saveProject(project.copy(reservedParts = Seq(
        ProjectReservedPart(0L, roadNumber, roadPartNumber, Some(0L), Some(Continuous), Some(8L), None, None, None, None))))
      val projectLinks = projectLinkDAO.fetchProjectLinks(projectId)
      ProjectLinkNameDAO.create(projectId, roadNumber, testRoadName)

      projectService.fillRoadNames(projectLinks.head).roadName.get should be(testRoadName)
    }
  }

  test("Test fillRoadNames When creating one project link for one new road name Then through RoadNameDao the road name of the created project links should be same as the latest one") {
    val roadNumber = 5L
    val roadPartNumber = 207L
    runWithRollback {
      val rap = Project(0L, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("1901-01-01"),
        "TestUser", DateTime.parse("1970-01-01"), DateTime.now(), "Some additional info",
        Seq(), Seq(), None)
      val project = projectService.createRoadLinkProject(rap)
      val id = project.id
      mockForProject(project.id, roadwayAddressMapper.getRoadAddressesByRoadway(roadwayDAO.fetchAllByRoadAndPart(roadNumber, roadPartNumber)).map(toProjectLink(project)))
      projectService.saveProject(project.copy(reservedParts = Seq(
        ProjectReservedPart(0L, roadNumber, roadPartNumber, Some(0L), Some(Continuous), Some(8L), None, None, None, None))))
      val projectLinks = projectLinkDAO.fetchProjectLinks(id)

      projectService.fillRoadNames(projectLinks.head).roadName.get should be(RoadNameDAO.getLatestRoadName(roadNumber).get.roadName)
    }
  }

  test("Test getLatestRoadName When having no road name for given road number Then road name should not be saved on TR success response if road number > 70.000 (even though it has no name)") {
    runWithRollback {
      val projectId = Sequences.nextViiteProjectId
      sqlu"""INSERT INTO PROJECT VALUES($projectId, 2, 'test project', 'silari', TIMESTAMP '2018-03-23 11:36:15.000000', '-', TIMESTAMP '2018-03-23 12:26:33.000000', NULL, TIMESTAMP '2018-03-23 00:00:00.000000', NULL, 0, 533406.572, 6994060.048, 12)""".execute
      sqlu"""INSERT INTO PROJECT_RESERVED_ROAD_PART VALUES (${Sequences.nextViitePrimaryKeySeqValue}, 70001, 1, $projectId, '-')""".execute

      sqlu"""INSERT INTO PROJECT_LINK (ID, PROJECT_ID, TRACK, DISCONTINUITY_TYPE, ROAD_NUMBER, ROAD_PART_NUMBER,
          START_ADDR_M, END_ADDR_M, CREATED_BY, MODIFIED_BY, CREATED_DATE, MODIFIED_DATE, STATUS,
          ADMINISTRATIVE_CLASS, ROADWAY_ID, LINEAR_LOCATION_ID, CONNECTED_LINK_ID, ELY, REVERSED, SIDE, START_MEASURE, END_MEASURE,
          LINK_ID, ADJUSTED_TIMESTAMP, LINK_SOURCE, GEOMETRY, ORIGINAL_START_ADDR_M, ORIGINAL_END_ADDR_M, ROADWAY_NUMBER,
          START_CALIBRATION_POINT, END_CALIBRATION_POINT, ORIG_START_CALIBRATION_POINT, ORIG_END_CALIBRATION_POINT)
        VALUES (${Sequences.nextProjectLinkId}, $projectId, 0, 5, 70001, 1,
          0, 86, 'test user', 'test user', TIMESTAMP '2018-03-23 12:26:36.000000', TIMESTAMP '2018-03-23 00:00:00.000000', 5,
          1, NULL, NULL, NULL, 8, 0, 2, 0, 85.617,
          5170979, 1500079296000, 1, NULL, 0, 86, NULL,
          3, 3, 3, 3)""".execute

      sqlu"""INSERT INTO PROJECT_LINK_NAME VALUES (nextval('PROJECT_LINK_NAME_SEQ'), $projectId, 70001, NULL)""".execute
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
      sqlu"""INSERT INTO ROAD_NAME VALUES (nextval('ROAD_NAME_SEQ'), 66666, 'road name test', TIMESTAMP '2018-03-23 12:26:36.000000', null, TIMESTAMP '2018-03-23 12:26:36.000000', null, 'test user', TIMESTAMP '2018-03-23 12:26:36.000000')""".execute

      sqlu"""INSERT INTO PROJECT VALUES($projectId, 2, 'test project', 'silari', TIMESTAMP '2018-03-23 11:36:15.000000', '-', TIMESTAMP '2018-03-23 12:26:33.000000', NULL, TIMESTAMP '2018-03-23 00:00:00.000000', NULL, 0, 533406.572, 6994060.048, 12)""".execute
      sqlu"""INSERT INTO PROJECT_RESERVED_ROAD_PART VALUES (${Sequences.nextViitePrimaryKeySeqValue}, 66666, 1, $projectId, '-')""".execute

      sqlu"""INSERT INTO PROJECT_LINK (ID, PROJECT_ID, TRACK, DISCONTINUITY_TYPE, ROAD_NUMBER, ROAD_PART_NUMBER,
          START_ADDR_M, END_ADDR_M, CREATED_BY, MODIFIED_BY, CREATED_DATE, MODIFIED_DATE, STATUS,
          ADMINISTRATIVE_CLASS, ROADWAY_ID, LINEAR_LOCATION_ID, CONNECTED_LINK_ID, ELY, REVERSED, SIDE, START_MEASURE, END_MEASURE,
          LINK_ID, ADJUSTED_TIMESTAMP, LINK_SOURCE, GEOMETRY, ORIGINAL_START_ADDR_M, ORIGINAL_END_ADDR_M, ROADWAY_NUMBER,
          START_CALIBRATION_POINT, END_CALIBRATION_POINT, ORIG_START_CALIBRATION_POINT, ORIG_END_CALIBRATION_POINT)
        VALUES (${Sequences.nextProjectLinkId}, $projectId, 0, 5, 66666, 1,
          0, 86, 'test user', 'test user', TIMESTAMP '2018-03-23 12:26:36.000000', TIMESTAMP '2018-03-23 00:00:00.000000', 2,
          1, NULL, NULL, NULL, 8, 0, 2, 0, 85.617,
          5170979, 1500079296000, 1, NULL, 0, 86, NULL,
          3, 3, 3, 3)""".execute

      sqlu"""INSERT INTO PROJECT_LINK_NAME VALUES (nextval('PROJECT_LINK_NAME_SEQ'), $projectId, 66666, 'another road name test')""".execute

      val changeInfos = List(
        RoadwayChangeInfo(AddressChangeType.New,
          source = dummyRoadwayChangeSection(Some(66666L), Some(1L), Some(0L), Some(0L), Some(100L), Some(AdministrativeClass.apply(1)), Some(Discontinuity.Continuous), Some(8L)),
          target = dummyRoadwayChangeSection(Some(66666L), Some(1L), Some(0L), Some(0L), Some(100L), Some(AdministrativeClass.apply(1)), Some(Discontinuity.Continuous), Some(8L)),
          Continuous, AdministrativeClass.apply(1), reversed = false, 1)
      )

      val changes = List(
        ProjectRoadwayChange(projectId, Some("test project"), 8, "Test", DateTime.now(), changeInfos.head, DateTime.now(), Some(0))
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
      sqlu"""INSERT INTO PROJECT VALUES($projectId, 2, 'test project', 'silari', TIMESTAMP '2018-03-23 11:36:15.000000', '-', TIMESTAMP '2018-03-23 12:26:33.000000', NULL, TIMESTAMP '2018-03-23 00:00:00.000000', NULL, 0, 533406.572, 6994060.048, 12)""".execute
      sqlu"""INSERT INTO PROJECT_RESERVED_ROAD_PART VALUES (${Sequences.nextViitePrimaryKeySeqValue}, 66666, 1, $projectId, '-')""".execute

      sqlu"""INSERT INTO PROJECT_LINK (ID, PROJECT_ID, TRACK, DISCONTINUITY_TYPE, ROAD_NUMBER, ROAD_PART_NUMBER,
          START_ADDR_M, END_ADDR_M, CREATED_BY, MODIFIED_BY, CREATED_DATE, MODIFIED_DATE, STATUS,
          ADMINISTRATIVE_CLASS, ROADWAY_ID, LINEAR_LOCATION_ID, CONNECTED_LINK_ID, ELY, REVERSED, SIDE, START_MEASURE, END_MEASURE,
          LINK_ID, ADJUSTED_TIMESTAMP, LINK_SOURCE, GEOMETRY, ORIGINAL_START_ADDR_M, ORIGINAL_END_ADDR_M, ROADWAY_NUMBER,
          START_CALIBRATION_POINT, END_CALIBRATION_POINT, ORIG_START_CALIBRATION_POINT, ORIG_END_CALIBRATION_POINT)
        VALUES (${Sequences.nextProjectLinkId}, $projectId, 0, 5, 66666, 1,
          0, 86, 'test user', 'test user', TIMESTAMP '2018-03-23 12:26:36.000000', TIMESTAMP '2018-03-23 00:00:00.000000', 2,
          1, NULL, NULL, NULL, 8, 0, 2, 0, 85.617,
          5170979, 1500079296000, 1, NULL, 0, 86, NULL,
          3, 3, 3, 3)""".execute

      sqlu"""INSERT INTO PROJECT_LINK_NAME VALUES (nextval('PROJECT_LINK_NAME_SEQ'), $projectId, 66666, 'road name test')""".execute
      val changeInfos = List(
        RoadwayChangeInfo(AddressChangeType.New,
          source = dummyRoadwayChangeSection(Some(66666L), Some(1L), Some(0L), Some(0L), Some(100L), Some(AdministrativeClass.apply(1)), Some(Discontinuity.Continuous), Some(8L)),
          target = dummyRoadwayChangeSection(Some(66666L), Some(1L), Some(0L), Some(0L), Some(100L), Some(AdministrativeClass.apply(1)), Some(Discontinuity.Continuous), Some(8L)),
          Continuous, AdministrativeClass.apply(1), reversed = false, 1)
      )

      val changes = List(
        ProjectRoadwayChange(projectId, Some("test project"), 8, "Test", DateTime.now(), changeInfos.head, DateTime.now(), Some(0))
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
      sqlu"""INSERT INTO PROJECT VALUES($projectId, 2, 'test project', 'silari', TIMESTAMP '2018-03-23 11:36:15.000000', '-', TIMESTAMP '2018-03-23 12:26:33.000000', NULL, TIMESTAMP '2018-03-23 00:00:00.000000', NULL, 0, 533406.572, 6994060.048, 12)""".execute
      sqlu"""INSERT INTO PROJECT_RESERVED_ROAD_PART VALUES (${Sequences.nextViitePrimaryKeySeqValue}, 66666, 1, $projectId, '-')""".execute
      sqlu"""INSERT INTO PROJECT_RESERVED_ROAD_PART VALUES (${Sequences.nextViitePrimaryKeySeqValue}, 55555, 1, $projectId, '-')""".execute

      sqlu"""INSERT INTO PROJECT_LINK (ID, PROJECT_ID, TRACK, DISCONTINUITY_TYPE, ROAD_NUMBER, ROAD_PART_NUMBER,
          START_ADDR_M, END_ADDR_M, CREATED_BY, MODIFIED_BY, CREATED_DATE, MODIFIED_DATE, STATUS,
          ADMINISTRATIVE_CLASS, ROADWAY_ID, LINEAR_LOCATION_ID, CONNECTED_LINK_ID, ELY, REVERSED, SIDE, START_MEASURE, END_MEASURE,
          LINK_ID, ADJUSTED_TIMESTAMP, LINK_SOURCE, GEOMETRY, ORIGINAL_START_ADDR_M, ORIGINAL_END_ADDR_M, ROADWAY_NUMBER,
          START_CALIBRATION_POINT, END_CALIBRATION_POINT, ORIG_START_CALIBRATION_POINT, ORIG_END_CALIBRATION_POINT)
        VALUES (${Sequences.nextProjectLinkId}, $projectId, 0, 5, 66666, 1,
          0, 86, 'test user', 'test user', TIMESTAMP '2018-03-23 12:26:36.000000', TIMESTAMP '2018-03-23 00:00:00.000000', 2,
          1, NULL, NULL, NULL, 8, 0, 2, 0, 85.617,
          5170979, 1500079296000, 1, NULL, 0, 86, NULL,
          3, 3, 3, 3)""".execute

      sqlu"""INSERT INTO PROJECT_LINK (ID, PROJECT_ID, TRACK, DISCONTINUITY_TYPE, ROAD_NUMBER, ROAD_PART_NUMBER,
          START_ADDR_M, END_ADDR_M, CREATED_BY, MODIFIED_BY, CREATED_DATE, MODIFIED_DATE, STATUS,
          ADMINISTRATIVE_CLASS, ROADWAY_ID, LINEAR_LOCATION_ID, CONNECTED_LINK_ID, ELY, REVERSED, SIDE, START_MEASURE, END_MEASURE,
          LINK_ID, ADJUSTED_TIMESTAMP, LINK_SOURCE, GEOMETRY, ORIGINAL_START_ADDR_M, ORIGINAL_END_ADDR_M, ROADWAY_NUMBER,
          START_CALIBRATION_POINT, END_CALIBRATION_POINT, ORIG_START_CALIBRATION_POINT, ORIG_END_CALIBRATION_POINT)
        VALUES (${Sequences.nextProjectLinkId}, $projectId, 0, 5, 55555, 1,
          0, 86, 'test user', 'test user', TIMESTAMP '2018-03-23 12:26:36.000000', TIMESTAMP '2018-03-23 00:00:00.000000', 2,
          1, NULL, NULL, NULL, 8, 0, 2, 0, 85.617,
          5170980, 1500079296000, 1, NULL, 0, 86, NULL,
          3, 3, 3, 3)""".execute

      sqlu"""INSERT INTO PROJECT_LINK_NAME VALUES (nextval('PROJECT_LINK_NAME_SEQ'), $projectId, 66666, 'road name test')""".execute
      sqlu"""INSERT INTO PROJECT_LINK_NAME VALUES (nextval('PROJECT_LINK_NAME_SEQ'), $projectId, 55555, 'road name test2')""".execute
      RoadNameDAO.getLatestRoadName(55555).isEmpty should be (true)
      RoadNameDAO.getLatestRoadName(66666).isEmpty should be (true)
      val changeInfos = List(
        RoadwayChangeInfo(AddressChangeType.New,
          source = dummyRoadwayChangeSection(Some(66666L), Some(1L), Some(0L), Some(0L), Some(100L), Some(AdministrativeClass.apply(1)), Some(Discontinuity.Continuous), Some(8L)),
          target = dummyRoadwayChangeSection(Some(66666L), Some(1L), Some(0L), Some(0L), Some(100L), Some(AdministrativeClass.apply(1)), Some(Discontinuity.Continuous), Some(8L)),
          Continuous, AdministrativeClass.apply(1), reversed = false, 1),
        RoadwayChangeInfo(AddressChangeType.New,
          source = dummyRoadwayChangeSection(Some(55555L), Some(1L), Some(0L), Some(0L), Some(100L), Some(AdministrativeClass.apply(1)), Some(Discontinuity.Continuous), Some(8L)),
          target = dummyRoadwayChangeSection(Some(55555L), Some(1L), Some(0L), Some(0L), Some(100L), Some(AdministrativeClass.apply(1)), Some(Discontinuity.Continuous), Some(8L)),
          Continuous, AdministrativeClass.apply(1), reversed = false, 1)
      )

      val changes = List(
        ProjectRoadwayChange(projectId, Some("test project"), 8, "Test", DateTime.now(), changeInfos.head, DateTime.now(), Some(0)),
        ProjectRoadwayChange(projectId, Some("test project"), 8, "Test", DateTime.now(), changeInfos(1), DateTime.now(), Some(0))
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
    val roadLink = RoadLink(5170939L.toString, Seq(Point(535605.272, 6982204.22, 85.90899999999965)), 540.3960283713503, State, TrafficDirection.AgainstDigitizing, Some("25.06.2015 03:00:00"), Some("vvh_modified"), InUse, NormalLinkInterface, 749, "")
    runWithRollback {
      val countCurrentProjects = projectService.getAllProjects
      val id = 0
      val addresses = Seq(ProjectReservedPart(5: Long, 5: Long, 205: Long, Some(5L), Some(Discontinuity.apply("jatkuva")), Some(8L), newLength = None, newDiscontinuity = None, newEly = None),
        ProjectReservedPart(5: Long, 5: Long, 206: Long, Some(5L), Some(Discontinuity.apply("jatkuva")), Some(8L), newLength = None, newDiscontinuity = None, newEly = None))
      val project = Project(id, ProjectState.apply(1), "TestProject", "TestUser", DateTime.now(), "TestUser", DateTime.parse("2017-01-01"), DateTime.now(), "Some additional info", Seq(), Seq(), None)
      val savedProject = projectService.createRoadLinkProject(project)
      mockForProject(savedProject.id, (roadwayAddressMapper.getRoadAddressesByLinearLocation(linearLocationDAO.fetchByRoadways(roadwayDAO.fetchAllBySection(5, 205).map(_.roadwayNumber).toSet)) ++ roadwayAddressMapper.getRoadAddressesByLinearLocation(linearLocationDAO.fetchByRoadways(roadwayDAO.fetchAllBySection(5, 206).map(_.roadwayNumber).toSet))).map(toProjectLink(savedProject)))
      projectService.saveProject(savedProject.copy(reservedParts = addresses))
      val countAfterInsertProjects = projectService.getAllProjects
      count = countCurrentProjects.size + 1
      countAfterInsertProjects.size should be(count)
      projectService.allLinksHandled(savedProject.id) should be(false)
      projectService.getSingleProjectById(savedProject.id).nonEmpty should be(true)
      projectService.getSingleProjectById(savedProject.id).get.reservedParts.nonEmpty should be(true)
      val projectLinks = projectLinkDAO.fetchProjectLinks(savedProject.id)
      val partitioned = projectLinks.partition(_.roadPartNumber == 205)
      val linkIds205 = partitioned._1.map(_.linkId).toSet
      val linkIds206 = partitioned._2.map(_.linkId).toSet
      reset(mockRoadLinkService)
      when(mockRoadLinkService.getRoadLinksHistoryFromVVH(any[Set[String]])).thenReturn(Seq())
      when(mockRoadLinkService.getRoadLinksByLinkIds(any[Set[String]])).thenAnswer(
        toMockAnswer(projectLinks, roadLink)
      )
      projectService.updateProjectLinks(savedProject.id, Set(), linkIds205.toSeq, LinkStatus.UnChanged, "-", 5, 205, 0, Option.empty[Int]) should be(None)
      projectService.allLinksHandled(savedProject.id) should be(false)
      projectService.updateProjectLinks(savedProject.id, Set(), linkIds206.toSeq, LinkStatus.UnChanged, "-", 5, 206, 0, Option.empty[Int]) should be(None)
      projectService.allLinksHandled(savedProject.id) should be(true)
      projectService.updateProjectLinks(savedProject.id, Set(), Seq(5168573.toString), LinkStatus.Terminated, "-", 5, 206, 0, Option.empty[Int]) should be(None)
      projectService.allLinksHandled(savedProject.id) should be(true)
      val updatedProjectLinks = projectLinkDAO.fetchProjectLinks(savedProject.id)
      updatedProjectLinks.exists { x => x.status == LinkStatus.UnChanged } should be(true)
      updatedProjectLinks.exists { x => x.status == LinkStatus.Terminated } should be(true)
      val calculatedProjectLinks = ProjectSectionCalculator.assignMValues(updatedProjectLinks)
      calculatedProjectLinks.filter(pl => pl.linkId == 5168579.toString).head.calibrationPoints should be((None, Some(CalibrationPoint(5168579.toString, 15.173, 4681, RoadAddressCP))))

      projectService.updateProjectLinks(savedProject.id, Set(), Seq(5168579.toString), LinkStatus.Terminated, "-", 0, 0, 0, Option.empty[Int])
      val updatedProjectLinks_ = projectLinkDAO.fetchProjectLinks(savedProject.id).toList
      val updatedProjectLinks2 = ProjectSectionCalculator.assignMValues(updatedProjectLinks_)
      val sortedRoad206AfterTermination = updatedProjectLinks2.filter(_.roadPartNumber == 206).sortBy(_.startAddrMValue)
      val updatedProjectLinks2_ = projectLinkDAO.fetchProjectLinks(savedProject.id).toList
      updatedProjectLinks2_.filter(pl => pl.linkId == 5168579.toString).head.calibrationPoints should be((None, None))
      val lastValid = sortedRoad206AfterTermination.filter(_.status != LinkStatus.Terminated).last
      sortedRoad206AfterTermination.filter(_.status != LinkStatus.Terminated).last.calibrationPoints should be((None, Some(CalibrationPoint(lastValid.linkId, lastValid.endMValue, lastValid.endAddrMValue, RoadAddressCP))))
      updatedProjectLinks2.filter(pl => pl.roadPartNumber == 205).exists { x => x.status == LinkStatus.Terminated } should be(false)
    }
    runWithRollback {
      projectService.getAllProjects
    } should have size (count - 1)
  }

  test("Test getProjectLinks When doing some operations (Transfer and then Terminate), Then the calibration points are cleared and moved to correct positions") {
    var count = 0
    val roadLink = RoadLink(5170939L.toString, Seq(Point(535605.272, 6982204.22, 85.90899999999965)), 540.3960283713503, State, TrafficDirection.AgainstDigitizing, Some("25.06.2015 03:00:00"), Some("vvh_modified"), InUse, NormalLinkInterface, 749, "")
    runWithRollback {
      val countCurrentProjects = projectService.getAllProjects
      val id = 0
      val addresses = List(ProjectReservedPart(5: Long, 5: Long, 207: Long, Some(5L), Some(Discontinuity.apply("jatkuva")), Some(8L), newLength = None, newDiscontinuity = None, newEly = None))
      val project = Project(id, ProjectState.apply(1), "TestProject", "TestUser", DateTime.now(), "TestUser", DateTime.parse("1970-01-01"), DateTime.now(), "Some additional info", Seq(), Seq(), None)
      val savedProject = projectService.createRoadLinkProject(project)
      mockForProject(savedProject.id, (roadwayAddressMapper.getRoadAddressesByLinearLocation(
        linearLocationDAO.fetchByRoadways(roadwayDAO.fetchAllBySection(5, 207).map(_.roadwayNumber).toSet)
      ) ++ roadwayAddressMapper.getRoadAddressesByLinearLocation(
        linearLocationDAO.fetchByRoadways(roadwayDAO.fetchAllBySection(5, 207).map(_.roadwayNumber).toSet)
      )).map(toProjectLink(savedProject)))

      projectService.saveProject(savedProject.copy(reservedParts = addresses))
      val countAfterInsertProjects = projectService.getAllProjects
      count = countCurrentProjects.size + 1
      countAfterInsertProjects.size should be(count)
      projectService.allLinksHandled(savedProject.id) should be(false)
      val projectLinks = projectLinkDAO.fetchProjectLinks(savedProject.id)
      val partitioned = projectLinks.partition(_.roadPartNumber == 207)
      val highestDistanceEnd = projectLinks.map(p => p.endAddrMValue).max
      val linkIds207 = partitioned._1.map(_.linkId).toSet
      reset(mockRoadLinkService)
      when(mockRoadLinkService.getRoadLinksHistoryFromVVH(any[Set[String]])).thenReturn(Seq())
      when(mockRoadLinkService.getRoadLinksByLinkIds(any[Set[String]])).thenAnswer(
        toMockAnswer(projectLinks, roadLink)
      )
      projectService.updateProjectLinks(savedProject.id, Set(), linkIds207.toSeq, LinkStatus.Transfer, "-", 5, 207, 0, Option.empty[Int]) should be(None)
      val linkId1 = 5168510.toString
      val linkId2 = 5168540.toString
      projectService.updateProjectLinks(savedProject.id, Set(), Seq(linkId1), LinkStatus.Terminated, "-", 5, 207, 0, Option.empty[Int]) should be(None)
      projectService.allLinksHandled(savedProject.id) should be(true)
      val links_ = projectLinkDAO.fetchProjectLinks(savedProject.id)
      val updatedProjectLinks = ProjectSectionCalculator.assignMValues(links_).sortBy(_.endAddrMValue) ++ projectLinkDAO.fetchProjectLinks(savedProject.id).filter(_.status == LinkStatus.Terminated)
        updatedProjectLinks.exists { x => x.status == LinkStatus.Transfer } should be(true)
      updatedProjectLinks.exists { x => x.status == LinkStatus.Terminated } should be(true)
      val sortedProjectLinks = updatedProjectLinks.sortBy(_.startAddrMValue)
      sortedProjectLinks.head.calibrationPoints._1.nonEmpty should be (true)
      sortedProjectLinks.head.calibrationPoints._1.get.segmentMValue should be (0.0)
      sortedProjectLinks.head.calibrationPoints._1.get.addressMValue should be (0)
      sortedProjectLinks.head.startCalibrationPointType should be (RoadAddressCP)
      sortedProjectLinks.head.calibrationPoints._2.isEmpty should be (true)

      sortedProjectLinks.last.calibrationPoints._1.isEmpty should be (true)
      sortedProjectLinks.last.calibrationPoints._2.nonEmpty should be (true)
      sortedProjectLinks.last.calibrationPoints._2.get.segmentMValue should be (442.89)
      sortedProjectLinks.last.calibrationPoints._2.get.addressMValue should be (updatedProjectLinks.map(p => p.endAddrMValue).max)
      sortedProjectLinks.last.endCalibrationPointType should be (RoadAddressCP)

      projectService.updateProjectLinks(savedProject.id, Set(), Seq(linkId2), LinkStatus.Terminated, "-", 5, 207, 0, Option.empty[Int]) should be(None)
      val updatedProjectLinks2 = ProjectSectionCalculator.assignMValues(projectLinkDAO.fetchProjectLinks(savedProject.id)).sortBy(_.endAddrMValue) ++ projectLinkDAO.fetchProjectLinks(savedProject.id).filter(_.status == LinkStatus.Terminated)
      val sortedProjectLinks2 = updatedProjectLinks2.sortBy(_.startAddrMValue)

      sortedProjectLinks2.last.calibrationPoints._1.isEmpty should be (true)
      sortedProjectLinks2.last.calibrationPoints._2.nonEmpty should be (true)
      sortedProjectLinks2.last.calibrationPoints._2.get.segmentMValue should be (442.89)
      sortedProjectLinks2.last.calibrationPoints._2.get.addressMValue should be (updatedProjectLinks2.map(p => p.endAddrMValue).max)
      sortedProjectLinks2.last.endCalibrationPointType should be (RoadAddressCP)
    }

  }

  test("Test getProjectLinks When doing some operations (Terminate then transfer), Then the calibration points are cleared and moved to correct positions") {
    var count = 0
    val roadLink = RoadLink(5170939L.toString, Seq(Point(535605.272, 6982204.22, 85.90899999999965)), 540.3960283713503, State, TrafficDirection.AgainstDigitizing, Some("25.06.2015 03:00:00"), Some("vvh_modified"), InUse, NormalLinkInterface, 749, "")
    runWithRollback {
      val countCurrentProjects = projectService.getAllProjects
      val id = 0
      val addresses = List(ProjectReservedPart(5: Long, 5: Long, 207: Long, Some(5L), Some(Discontinuity.apply("jatkuva")), Some(8L), newLength = None, newDiscontinuity = None, newEly = None))
      val project = Project(id, ProjectState.apply(1), "TestProject", "TestUser", DateTime.now(), "TestUser", DateTime.parse("1970-01-01"), DateTime.now(), "Some additional info", Seq(), Seq(), None)
      val savedProject = projectService.createRoadLinkProject(project)
      mockForProject(savedProject.id, (roadwayAddressMapper.getRoadAddressesByLinearLocation(linearLocationDAO.fetchByRoadways(roadwayDAO.fetchAllBySection(5, 207).map(_.roadwayNumber).toSet)) ++ roadwayAddressMapper.getRoadAddressesByLinearLocation(linearLocationDAO.fetchByRoadways(roadwayDAO.fetchAllBySection(5, 207).map(_.roadwayNumber).toSet))).map(toProjectLink(savedProject)))

      projectService.saveProject(savedProject.copy(reservedParts = addresses))
      val countAfterInsertProjects = projectService.getAllProjects
      count = countCurrentProjects.size + 1
      countAfterInsertProjects.size should be(count)
      projectService.allLinksHandled(savedProject.id) should be(false)
      val projectLinks = projectLinkDAO.fetchProjectLinks(savedProject.id)
      val partitioned = projectLinks.partition(_.roadPartNumber == 207)
      val highestDistanceEnd = projectLinks.map(p => p.endAddrMValue).max
      val linkIds207 = partitioned._1.map(_.linkId).toSet
      reset(mockRoadLinkService)
      when(mockRoadLinkService.getRoadLinksHistoryFromVVH(any[Set[String]])).thenReturn(Seq())
      when(mockRoadLinkService.getRoadLinksByLinkIds(any[Set[String]])).thenAnswer(
        toMockAnswer(projectLinks, roadLink)
      )
      projectService.updateProjectLinks(savedProject.id, Set(), Seq(5168510.toString), LinkStatus.Terminated, "-", 5, 207, 0, Option.empty[Int])
      projectService.updateProjectLinks(savedProject.id, Set(), linkIds207.filterNot(_ == 5168510L.toString).toSeq, LinkStatus.Transfer, "-", 5, 207, 0, Option.empty[Int])
      projectService.allLinksHandled(savedProject.id) should be(true)
      projectService.updateProjectLinks(savedProject.id, Set(), Seq(6460794.toString), LinkStatus.Transfer, "-", 5, 207, 0, Option.empty[Int], discontinuity = Discontinuous.value)

      val links_ = projectLinkDAO.fetchProjectLinks(savedProject.id)
      val updatedProjectLinks = ProjectSectionCalculator.assignMValues(links_).sortBy(_.endAddrMValue) ++ projectLinkDAO.fetchProjectLinks(savedProject.id).filter(_.status == LinkStatus.Terminated)
      updatedProjectLinks.exists { x => x.status == LinkStatus.Transfer } should be(true)
      updatedProjectLinks.exists { x => x.status == LinkStatus.Terminated } should be(true)
      val sortedProjectLinks = updatedProjectLinks.sortBy(_.startAddrMValue)
      sortedProjectLinks.head.calibrationPoints._1.nonEmpty should be (true)
      sortedProjectLinks.head.calibrationPoints._1.get.segmentMValue should be (0.0)
      sortedProjectLinks.head.calibrationPoints._1.get.addressMValue should be (0)
      sortedProjectLinks.head.startCalibrationPointType should be (RoadAddressCP)
      sortedProjectLinks.head.calibrationPoints._2.isEmpty should be (true)

      sortedProjectLinks.last.calibrationPoints._1.isEmpty should be (true)
      sortedProjectLinks.last.calibrationPoints._2.nonEmpty should be (true)
      sortedProjectLinks.last.calibrationPoints._2.get.segmentMValue should be (442.89)
      val highestDistanceEnd2 = updatedProjectLinks.map(p => p.endAddrMValue).max
      sortedProjectLinks.last.calibrationPoints._2.get.addressMValue should be (updatedProjectLinks.map(p => p.endAddrMValue).max)
      sortedProjectLinks.last.endCalibrationPointType should be (RoadAddressCP)

      projectService.updateProjectLinks(savedProject.id, Set(), Seq(5168540.toString), LinkStatus.Terminated, "-", 5, 207, 0, Option.empty[Int])
      val updatedProjectLinks2 = ProjectSectionCalculator.assignMValues(links_).sortBy(_.endAddrMValue) ++ projectLinkDAO.fetchProjectLinks(savedProject.id).filter(_.status == LinkStatus.Terminated)
      val sortedProjectLinks2 = updatedProjectLinks2.sortBy(_.startAddrMValue)

      sortedProjectLinks2.last.calibrationPoints._1.isEmpty should be (true)
      sortedProjectLinks2.last.calibrationPoints._2.nonEmpty should be (true)
      sortedProjectLinks2.last.calibrationPoints._2.get.segmentMValue should be (442.89)
      sortedProjectLinks2.last.calibrationPoints._2.get.addressMValue should be (updatedProjectLinks2.map(p => p.endAddrMValue).max)
      sortedProjectLinks2.last.endCalibrationPointType should be (RoadAddressCP)
    }

  }

  test("Test revertLinksByRoadPart When reverting the road Then the road address geometry after reverting should be the same as KGV") {
    val projectId = 0L
    val user = "TestUser"
    val (roadNumber, roadPartNumber) = (26020L, 12L)
    val (newRoadNumber, newRoadPart) = (9999L, 1L)
    val smallerRoadGeom = Seq(Point(0.0, 0.0), Point(0.0, 5.0))
    val roadGeom = Seq(Point(0.0, 0.0), Point(0.0, 10.0))
    runWithRollback {
      val roadwayNumber = 1
      val roadwayId = Sequences.nextRoadwayId
      val roadAddresses = roadwayAddressMapper.getRoadAddressesByRoadway(roadwayDAO.fetchAllBySection(roadNumber, roadPartNumber))

      val rap = Project(projectId, ProjectState.apply(1), "TestProject", user, DateTime.parse("1901-01-01"),
        "TestUser", DateTime.parse("1901-01-01"), DateTime.now(), "Some additional info",
        Seq(), Seq(), None)
      projectDAO.create(rap)
      val projectLinksFromRoadAddresses = roadAddresses.map(ra => toProjectLink(rap)(ra))

      val linearLocation = dummyLinearLocation(roadwayNumber, 1, projectLinksFromRoadAddresses.head.linkId, 0.0, 10.0)
      val roadway = dummyRoadway(roadwayNumber, 9999L, 1, 0, 10, DateTime.now(), None, roadwayId)

      linearLocationDAO.create(Seq(linearLocation))
      roadwayDAO.create(Seq(roadway))

      projectReservedPartDAO.reserveRoadPart(projectId, roadNumber, roadPartNumber, "Test")
      projectLinkDAO.create(projectLinksFromRoadAddresses)

      val numberingLink = Seq(ProjectLink(-1000L, newRoadNumber, newRoadPart, Track.apply(0), Discontinuity.Continuous, 0L, 5L, 0L, 10L, None, None, Option(user), projectLinksFromRoadAddresses.head.linkId, 0.0, 10.0, SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP), smallerRoadGeom, rap.id, LinkStatus.Numbering, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, 10.0, roadAddresses.head.id, roadwayNumber+Math.round(linearLocation.orderNumber), 0, reversed = false, None, 86400L))
      projectReservedPartDAO.reserveRoadPart(projectId, newRoadNumber, newRoadPart, "Test")
      projectLinkDAO.create(numberingLink)
      val numberingLinks = projectLinkDAO.fetchProjectLinks(projectId, Option(LinkStatus.Numbering))
      numberingLinks.head.geometry should be equals smallerRoadGeom

     val projectLinks = projectLinkDAO.fetchProjectLinks(projectId)
     val linksToRevert = projectLinks.filter(_.status != LinkStatus.NotHandled).map(pl => {
       LinkToRevert(pl.id, pl.linkId, pl.status.value, pl.geometry)
     })
     val roadLinks = projectLinks.updated(0, projectLinks.head.copy(geometry = roadGeom)).map(toRoadLink)
      when(mockRoadLinkService.getCurrentAndComplementaryRoadLinks(any[Set[String]])).thenReturn(roadLinks)
      projectService.revertLinksByRoadPart(projectId, newRoadNumber, newRoadPart, linksToRevert, user)
      val geomAfterRevert = GeometryUtils.truncateGeometry3D(roadGeom, projectLinksFromRoadAddresses.head.startMValue, projectLinksFromRoadAddresses.head.endMValue)
      val linksAfterRevert = projectLinkDAO.fetchProjectLinks(projectId)
      linksAfterRevert.map(_.geometry).contains(geomAfterRevert) should be(true)
    }
  }

  test("Test changeDirection() When projectLinks are reversed the track codes must switch and start_addr_m and end_addr_m should be the same for the first and last links") {
    runWithRollback {
      val roadNumber = 9999L
      val roadPartNumber = 1L
      val project = setUpProjectWithLinks(LinkStatus.New, Seq(0, 100, 150, 300), changeTrack = true, roadNumber, roadPartNumber)
      val projectLinksBefore = projectLinkDAO.fetchProjectLinks(project.id).sortBy(pl => (pl.startAddrMValue, pl.track.value))

      val linksToRevert = projectLinksBefore.map(pl => LinkToRevert(pl.id, pl.id.toString, LinkStatus.Transfer.value, pl.geometry))
      projectService.changeDirection(project.id, roadNumber, roadPartNumber, linksToRevert, ProjectCoordinates(0, 0, 5), "testUser")

      val projectLinksAfter = projectLinkDAO.fetchProjectLinks(project.id).sortBy(pl => (pl.startAddrMValue, -pl.track.value))
      projectLinksAfter.size should be(projectLinksBefore.size)
      projectLinksAfter.head.startAddrMValue should be(projectLinksBefore.head.startAddrMValue)
      projectLinksAfter.last.endAddrMValue should be(projectLinksBefore.last.endAddrMValue)
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
      val testName = "TEST ROAD NAME"

      val rap = Project(0L, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("1901-01-01"),
        "TestUser", DateTime.parse("1901-01-01"), DateTime.now(), "Some additional info",
        Seq(), Seq(), None)
      val project = projectService.createRoadLinkProject(rap)

      val changeInfos = List(
        RoadwayChangeInfo(AddressChangeType.New,
          source = dummyRoadwayChangeSection(Some(testRoadNumber1), Some(1L), Some(0L), Some(0L), Some(100L), Some(AdministrativeClass.apply(1)), Some(Discontinuity.Continuous), Some(8L)),
          target = dummyRoadwayChangeSection(Some(testRoadNumber1), Some(1L), Some(0L), Some(100L), Some(200L), Some(AdministrativeClass.apply(3)), Some(Discontinuity.Continuous), Some(8L)),
          Continuous, AdministrativeClass.apply(1), reversed = false, 1),

        RoadwayChangeInfo(AddressChangeType.New,
          source = dummyRoadwayChangeSection(Some(testRoadNumber1), Some(1L), Some(0L), Some(100L), Some(200L), Some(AdministrativeClass.apply(1)), Some(Discontinuity.Continuous), Some(8L)),
          target = dummyRoadwayChangeSection(Some(testRoadNumber1), Some(1L), Some(0L), Some(100L), Some(200L), Some(AdministrativeClass.apply(3)), Some(Discontinuity.Continuous), Some(8L)),
          Continuous, AdministrativeClass.apply(3), reversed = false, 2),

        RoadwayChangeInfo(AddressChangeType.New,
          source = dummyRoadwayChangeSection(Some(testRoadNumber1), Some(1L), Some(0L), Some(200L), Some(400L), Some(AdministrativeClass.apply(1)), Some(Discontinuity.Continuous), Some(8L)),
          target = dummyRoadwayChangeSection(Some(testRoadNumber1), Some(1L), Some(0L), Some(200L), Some(400L), Some(AdministrativeClass.apply(1)), Some(Discontinuity.Continuous), Some(8L)),
          Continuous, AdministrativeClass.apply(3), reversed = false, 3),
        RoadwayChangeInfo(AddressChangeType.New,
          source = dummyRoadwayChangeSection(Some(testRoadNumber2), Some(1L), Some(0L), Some(200L), Some(400L), Some(AdministrativeClass.apply(1)), Some(Discontinuity.Continuous), Some(8L)),
          target = dummyRoadwayChangeSection(Some(testRoadNumber2), Some(1L), Some(0L), Some(200L), Some(400L), Some(AdministrativeClass.apply(1)), Some(Discontinuity.Continuous), Some(8L)),
          Continuous, AdministrativeClass.apply(3), reversed = false, 3),
        RoadwayChangeInfo(AddressChangeType.New,
          source = dummyRoadwayChangeSection(Some(testRoadNumber2), Some(1L), Some(0L), Some(200L), Some(400L), Some(AdministrativeClass.apply(1)), Some(Discontinuity.Continuous), Some(8L)),
          target = dummyRoadwayChangeSection(Some(testRoadNumber2), Some(1L), Some(0L), Some(200L), Some(400L), Some(AdministrativeClass.apply(1)), Some(Discontinuity.Continuous), Some(8L)),
          Continuous, AdministrativeClass.apply(3), reversed = false, 3),
        RoadwayChangeInfo(AddressChangeType.New,
          source = dummyRoadwayChangeSection(Some(testRoadNumber2), Some(1L), Some(0L), Some(200L), Some(400L), Some(AdministrativeClass.apply(1)), Some(Discontinuity.Continuous), Some(8L)),
          target = dummyRoadwayChangeSection(Some(testRoadNumber2), Some(1L), Some(0L), Some(200L), Some(400L), Some(AdministrativeClass.apply(1)), Some(Discontinuity.Continuous), Some(8L)),
          Continuous, AdministrativeClass.apply(3), reversed = false, 3)
      )

      val projectStartTime = DateTime.now()

      val changes = List(
        ProjectRoadwayChange(project.id, Some("projectName"), 8, "Test", DateTime.now(), changeInfos.head, projectStartTime, Some(0)),
        ProjectRoadwayChange(project.id, Some("projectName"), 8, "Test", DateTime.now(), changeInfos(1), projectStartTime, Some(0)),
        ProjectRoadwayChange(project.id, Some("projectName"), 8, "Test", DateTime.now(), changeInfos(2), projectStartTime, Some(0)),
        ProjectRoadwayChange(project.id, Some("projectName"), 8, "Test", DateTime.now(), changeInfos(3), projectStartTime, Some(0)),
        ProjectRoadwayChange(project.id, Some("projectName"), 8, "Test", DateTime.now(), changeInfos(4), projectStartTime, Some(0)),
        ProjectRoadwayChange(project.id, Some("projectName"), 8, "Test", DateTime.now(), changeInfos(5), projectStartTime, Some(0))
      )

      ProjectLinkNameDAO.create(project.id, testRoadNumber1, testName)
      ProjectLinkNameDAO.create(project.id, testRoadNumber2, testName)

      // Method to be tested
      projectService.handleNewRoadNames(changes)

      // Test if project link is removed from DB
      ProjectLinkNameDAO.get(project.id, testRoadNumber1) should be (None)

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
        RoadwayChangeInfo(AddressChangeType.Termination,
          source = dummyRoadwayChangeSection(Some(roadNumber), Some(1L), Some(0L), Some(0L), Some(100L), Some(AdministrativeClass.apply(1)), Some(Discontinuity.Continuous), Some(8L)),
          target = dummyRoadwayChangeSection(Some(roadNumber), Some(1L), Some(0L), Some(100L), Some(200L), Some(AdministrativeClass.apply(3)), Some(Discontinuity.Continuous), Some(8L)),
          Continuous, AdministrativeClass.apply(1), reversed = false, 1),

        RoadwayChangeInfo(AddressChangeType.Unchanged,
          source = dummyRoadwayChangeSection(Some(roadNumber), Some(1L), Some(0L), Some(100L), Some(200L), Some(AdministrativeClass.apply(1)), Some(Discontinuity.Continuous), Some(8L)),
          target = dummyRoadwayChangeSection(Some(roadNumber), Some(1L), Some(0L), Some(100L), Some(200L), Some(AdministrativeClass.apply(3)), Some(Discontinuity.Continuous), Some(8L)),
          Continuous, AdministrativeClass.apply(3), reversed = false, 2),

        RoadwayChangeInfo(AddressChangeType.Unchanged,
          source = dummyRoadwayChangeSection(Some(roadNumber), Some(1L), Some(0L), Some(200L), Some(400L), Some(AdministrativeClass.apply(1)), Some(Discontinuity.Continuous), Some(8L)),
          target = dummyRoadwayChangeSection(Some(roadNumber), Some(1L), Some(0L), Some(200L), Some(400L), Some(AdministrativeClass.apply(1)), Some(Discontinuity.Continuous), Some(8L)),
          Continuous, AdministrativeClass.apply(3), reversed = false, 3)
      )

      val projectStartTime = DateTime.now()

      val changes = List(
        ProjectRoadwayChange(0L, Some("projectName"), 8, "Test", DateTime.now(), changeInfos.head, projectStartTime, Some(0)),
        ProjectRoadwayChange(0L, Some("projectName"), 8, "Test", DateTime.now(), changeInfos(1), projectStartTime, Some(0)),
        ProjectRoadwayChange(0L, Some("projectName"), 8, "Test", DateTime.now(), changeInfos(2), projectStartTime, Some(0))
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

  test("Test handleTransferAndRenumeration: Transfer to new roadway - If source roadway exists expire its road name and create new road name for targer road number") {
    runWithRollback {

      val srcRoadNumber = 99998
      val targetRoadNumber = 99999
      val testName = "TEST ROAD NAME"

      val rap = Project(0L, ProjectState.apply(1), "TestProject", "TestUser", DateTime.now(),
        "TestUser", DateTime.now(), DateTime.now(), "Some additional info",
        Seq(), Seq(), None)
      val project = projectService.createRoadLinkProject(rap)
      ProjectLinkNameDAO.create(project.id, targetRoadNumber, testName)

      val roadnames = Seq(RoadName(99999, srcRoadNumber, testName, startDate = Some(DateTime.now()), createdBy = "Test"))
      RoadNameDAO.create(roadnames)

      val roadways = List(dummyRoadway(0L, srcRoadNumber, 0L, 0L, 0L, DateTime.now, Some(DateTime.now)))
      roadwayDAO.create(roadways)

      val changeInfos = List(
        RoadwayChangeInfo(AddressChangeType.Transfer,
          source = dummyRoadwayChangeSection(Some(srcRoadNumber), Some(1L), Some(0L), Some(0L), Some(100L), Some(AdministrativeClass.apply(1)), Some(Discontinuity.Continuous), Some(8L)),
          target = dummyRoadwayChangeSection(Some(targetRoadNumber), Some(1L), Some(0L), Some(100L), Some(200L), Some(AdministrativeClass.apply(3)), Some(Discontinuity.Continuous), Some(8L)),
          Continuous, AdministrativeClass.apply(1), reversed = false, 1)
      )

      val changes = List(
        ProjectRoadwayChange(project.id, Some("projectName"), 8, "Test", DateTime.now(), changeInfos.head, project.startDate, Some(0))
      )
      projectService.handleRoadNames(changes)
      ProjectLinkNameDAO.get(project.id, targetRoadNumber) should be(None)
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

  test("Test handleTransferAndRenumeration: Transfer to existing roadway - If source roadway exists expire its roadname") {
    runWithRollback {

      val srcRoadNumber = 99998
      val targetRoadNumber = 99999
      val testName = "TEST ROAD NAME"

      val rap = Project(0L, ProjectState.apply(1), "TestProject", "TestUser", DateTime.now(),
        "TestUser", DateTime.now(), DateTime.now(), "Some additional info",
        Seq(), Seq(), None)
      val project = projectService.createRoadLinkProject(rap)
      ProjectLinkNameDAO.create(project.id, targetRoadNumber, testName)

      val roadnames = Seq(RoadName(99999, srcRoadNumber, testName, startDate = Some(DateTime.now()), createdBy = "Test"))
      RoadNameDAO.create(roadnames)

      val roadways = List(
        Roadway(0L, 0L, srcRoadNumber, 0L, AdministrativeClass.State, Track.Combined, Continuous, 0L, 0L, reversed = false, DateTime.now, Some(DateTime.now), "dummy", None, 0L, NoTermination),
        Roadway(-1L, 0L, targetRoadNumber, 0L, AdministrativeClass.State, Track.Combined, Continuous, 0L, 0L, reversed = false, DateTime.now, Some(DateTime.now), "dummy", None, 0L, NoTermination)
      )
      roadwayDAO.create(roadways)

      val changeInfos = List(
        RoadwayChangeInfo(AddressChangeType.Transfer,
          source = dummyRoadwayChangeSection(Some(srcRoadNumber), Some(1L), Some(0L), Some(0L), Some(100L), Some(AdministrativeClass.apply(1)), Some(Discontinuity.Continuous), Some(8L)),
          target = dummyRoadwayChangeSection(Some(targetRoadNumber), Some(1L), Some(0L), Some(100L), Some(200L), Some(AdministrativeClass.apply(3)), Some(Discontinuity.Continuous), Some(8L)),
          Continuous, AdministrativeClass.apply(1), reversed = false, 1)
      )

      val changes = List(
        ProjectRoadwayChange(project.id, Some("projectName"), 8, "Test", DateTime.now(), changeInfos.head, project.startDate, Some(0))
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

  test("Test handleTransferAndRenumeration: Renumeration to new roadway - If source roadway exists expire its road name and create new road name for targer road number") {
    runWithRollback {

      val srcRoadNumber = 99998
      val targetRoadNumber = 99999
      val testName = "TEST ROAD NAME"

      val rap = Project(0L, ProjectState.apply(1), "TestProject", "TestUser", DateTime.now(),
        "TestUser", DateTime.now(), DateTime.now(), "Some additional info",
        Seq(), Seq(), None)
      val project = projectService.createRoadLinkProject(rap)
      ProjectLinkNameDAO.create(project.id, targetRoadNumber, testName)

      val roadnames = Seq(RoadName(99999, srcRoadNumber, testName, startDate = Some(DateTime.now()), createdBy = "Test"))
      RoadNameDAO.create(roadnames)

      val roadways = List(dummyRoadway(0L, srcRoadNumber, 0L, 0L, 0L, DateTime.now, Some(DateTime.now)))
      roadwayDAO.create(roadways)

      val changeInfos = List(
        RoadwayChangeInfo(AddressChangeType.ReNumeration,
          source = dummyRoadwayChangeSection(Some(srcRoadNumber), Some(1L), Some(0L), Some(0L), Some(100L), Some(AdministrativeClass.apply(1)), Some(Discontinuity.Continuous), Some(8L)),
          target = dummyRoadwayChangeSection(Some(targetRoadNumber), Some(1L), Some(0L), Some(100L), Some(200L), Some(AdministrativeClass.apply(3)), Some(Discontinuity.Continuous), Some(8L)),
          Continuous, AdministrativeClass.apply(1), reversed = false, 1)
      )

      val changes = List(
        ProjectRoadwayChange(project.id, Some("projectName"), 8, "Test", DateTime.now(), changeInfos.head, project.startDate, Some(0))
      )
      projectService.handleRoadNames(changes)
      ProjectLinkNameDAO.get(project.id, targetRoadNumber) should be(None)
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

      val rap = Project(0L, ProjectState.apply(1), "TestProject", "TestUser", DateTime.now(),
        "TestUser", DateTime.now(), DateTime.now(), "Some additional info",
        Seq(), Seq(), None)
      val project = projectService.createRoadLinkProject(rap)
      ProjectLinkNameDAO.create(project.id, targetRoadNumber, testName)

      val roadNames = Seq(RoadName(99999, srcRoadNumber, testName, startDate = Some(DateTime.now()), createdBy = "Test"))
      RoadNameDAO.create(roadNames)

      val roadways = List(
        Roadway(0L, 0L, srcRoadNumber, 0L, AdministrativeClass.State, Track.Combined, Continuous, 0L, 0L, startDate = DateTime.now, endDate = Some(DateTime.now), createdBy = "dummy", roadName = None, ely = 0L, terminated = NoTermination),
        Roadway(-1L, 0L, targetRoadNumber, 0L, AdministrativeClass.State, Track.Combined, Continuous, 0L, 0L, reversed = false, DateTime.now, Some(DateTime.now), "dummy", None, 0L, NoTermination)
      )
      roadwayDAO.create(roadways)

      val changeInfos = List(
        RoadwayChangeInfo(AddressChangeType.ReNumeration,
          source = dummyRoadwayChangeSection(Some(srcRoadNumber), Some(1L), Some(0L), Some(0L), Some(100L), Some(AdministrativeClass.apply(1)), Some(Discontinuity.Continuous), Some(8L)),
          target = dummyRoadwayChangeSection(Some(targetRoadNumber), Some(1L), Some(0L), Some(100L), Some(200L), Some(AdministrativeClass.apply(3)), Some(Discontinuity.Continuous), Some(8L)),
          Continuous, AdministrativeClass.apply(1), reversed = false, 1)
      )

      val changes = List(
        ProjectRoadwayChange(project.id, Some("projectName"), 8, "Test", DateTime.now(), changeInfos.head, project.startDate, Some(0))
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

      val rap = Project(0L, ProjectState.apply(1), "TestProject", "TestUser", DateTime.now(),
        "TestUser", DateTime.now(), DateTime.now(), "Some additional info",
        Seq(), Seq(), None)
      val reservations = List(
        ProjectReservedPart(Sequences.nextViitePrimaryKeySeqValue, roadNumber, part1, Some(0L), Some(Continuous), ely1, None, None, None, None),
        ProjectReservedPart(Sequences.nextViitePrimaryKeySeqValue, roadNumber, part2, Some(0L), Some(Continuous), ely2, None, None, None, None)
      )
      val project = projectService.createRoadLinkProject(rap)

      val address1 = roadwayAddressMapper.getRoadAddressesByLinearLocation(linearLocationDAO.fetchByRoadways(roadwayDAO.fetchAllBySection(roadNumber, part1).map(_.roadwayNumber).toSet))
      val address2 = roadwayAddressMapper.getRoadAddressesByLinearLocation(linearLocationDAO.fetchByRoadways(roadwayDAO.fetchAllBySection(roadNumber, part2).map(_.roadwayNumber).toSet))
      mockForProject(project.id, (address1 ++ address2).map(toProjectLink(rap)))
      val savedProject = projectService.saveProject(project.copy(reservedParts = reservations, formedParts = reservations))
      val originalElyPart1 = roadwayDAO.fetchAllByRoadAndPart(roadNumber, part1).map(_.ely).toSet
      val originalElyPart2 = roadwayDAO.fetchAllByRoadAndPart(roadNumber, part2).map(_.ely).toSet
      val reservedPart1 = savedProject.reservedParts.find(rp => rp.roadNumber == roadNumber && rp.roadPartNumber == part1)
      val reservedPart2 = savedProject.reservedParts.find(rp => rp.roadNumber == roadNumber && rp.roadPartNumber == part2)
      val formedPart1 = savedProject.reservedParts.find(rp => rp.roadNumber == roadNumber && rp.roadPartNumber == part1)
      val formedPart2 = savedProject.reservedParts.find(rp => rp.roadNumber == roadNumber && rp.roadPartNumber == part2)
      reservedPart1.nonEmpty should be (true)
      reservedPart1.get.ely.get should be (originalElyPart1.head)
      reservedPart2.nonEmpty should be (true)
      reservedPart2.get.ely.get should be (originalElyPart2.head)

    }
  }

  //TODO remove after cleaning all floating code
  /*test("If the supplied, old, road address has a valid_to < current_date then the outputted, new, road addresses are floating") {
    val road = 5L
    val roadPart = 205L
    val origStartM = 1024L
    val origEndM = 1547L
    val linkId = 1049L
    val endM = 520.387
    val suravageLinkId = 5774839L

    runWithRollback {

    val linearLocationId = Sequences.nextLinearLocationId
    val user = Some("user")
    val project = Project(-1L, Incomplete, "split", user.get, DateTime.now(), user.get,
      DateTime.now().plusMonths(2), DateTime.now(), "", Seq(), None, None)

    // Original road address: 1024 -> 1547
    val roadAddress = RoadAddress(1L, linearLocationId, road, roadPart, State, Track.Combined, Continuous, origStartM, origEndM, Some(DateTime.now().minusYears(10)),
      None, None, linkId, 0.0, endM, SideCode.TowardsDigitizing, 86400L, (None, None),  Seq(Point(1024.0, 0.0), Point(1025.0, 1544.386)),
      LinkGeomSource.NormalLinkInterface, 8L, NoTermination, 123)

    val projectLink = ProjectLink(0, road, roadPart, Track.Combined, Continuous, 0, 0, 0, 0, Some(DateTime.now()), None, user,
      0, 0.0, 0.0, SideCode.TowardsDigitizing, (None, None),  Seq(Point(0.0, 0.0), Point(0.0, 0.0)),
      -1L, null, State, null, 0.0, 1L, linearLocationId, 8L, reversed = false, None, 748800L)
    val transferAndNew = Seq(

      // Transferred road address: 1028 -> 1128
      projectLink.copy(id = 2, startAddrMValue = origStartM + 4, endAddrMValue = origStartM + 104, linkId = suravageLinkId,
        startMValue = 0.0, endMValue = 99.384, geometry = Seq(Point(1024.0, 0.0), Point(1024.0, 99.384)), status = LinkStatus.Transfer,
        linkGeomSource = LinkGeomSource.SuravageLinkInterface, geometryLength = 99.384, connectedLinkId = Some(linkId)),

      // New road address: 1128 -> 1205
      projectLink.copy(id = 3, startAddrMValue = origStartM + 104, endAddrMValue = origStartM + 181, linkId = suravageLinkId,
        startMValue = 99.384, endMValue = 176.495, geometry = Seq(Point(1024.0, 99.384), Point(1101.111, 99.384)), status = LinkStatus.New,
        linkGeomSource = LinkGeomSource.SuravageLinkInterface, geometryLength = 77.111, connectedLinkId = Some(linkId)),

      // Terminated road address: 1124 -> 1547
      projectLink.copy(id = 4, startAddrMValue = origStartM + 100, endAddrMValue = origEndM, linkId = linkId,
        startMValue = 99.384, endMValue = endM, geometry = Seq(Point(1024.0, 99.384), Point(1025.0, 1544.386)), status = LinkStatus.Terminated,
        linkGeomSource = LinkGeomSource.NormalLinkInterface, geometryLength = endM - 99.384, connectedLinkId = Some(suravageLinkId))

    )
    val yesterdayDate = Option(DateTime.now().plusDays(-1))
    val result = projectService.createSplitRoadAddress(roadAddress.copy(validTo = yesterdayDate), transferAndNew, project)
    result should have size 4
    result.count(_.terminated == TerminationCode.Termination) should be(1)
    result.count(_.startDate == roadAddress.startDate) should be(2)
    result.count(_.startDate.get == project.startDate) should be(2)
    result.count(_.endDate.isEmpty) should be(2)
    result.filter(res => res.terminated == TerminationCode.NoTermination && res.roadwayNumber != -1000).forall(_.isFloating) should be(true)
    }
  }*/

  test("Test changeDirection When after the creation of valid project links on a project Then the side code of road addresses should be successfully reversed.") {
    runWithRollback {

      val rap = Project(0L, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("1901-01-01"),
        "TestUser", DateTime.parse("1901-01-01"), DateTime.now(), "Some additional info",
        Seq(), Seq(), None)

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

      val pl1 = ProjectLink(-1000L, 9999L, 1L, Track.RightSide, Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None, None, 12345L.toString, 0.0, 0.0, SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP), geom1, 0L, LinkStatus.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geom1), 0L, 0, 0, reversed = false, None, NewIdValue)
      val pl2 = ProjectLink(-1000L, 9999L, 1L, Track.RightSide, Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None, None, 12346L.toString, 0.0, 0.0, SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP), geom2, 0L, LinkStatus.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geom2), 0L, 0, 0, reversed = false, None, NewIdValue)
      val pl3 = ProjectLink(-1000L, 9999L, 1L, Track.RightSide, Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None, None, 12347L.toString, 0.0, 0.0, SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP), geom3, 0L, LinkStatus.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geom3), 0L, 0, 0, reversed = false, None, NewIdValue)
      val pl4 = ProjectLink(-1000L, 9999L, 1L, Track.RightSide, Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None, None, 12348L.toString, 0.0, 0.0, SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP), geom4, 0L, LinkStatus.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geom4), 0L, 0, 0, reversed = false, None, NewIdValue)
      val pl5 = ProjectLink(-1000L, 9999L, 1L, Track.RightSide, Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None, None, 12349L.toString, 0.0, 0.0, SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP), geom5, 0L, LinkStatus.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geom5), 0L, 0, 0, reversed = false, None, NewIdValue)
      val pl6 = ProjectLink(-1000L, 9999L, 1L, Track.RightSide, Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None, None, 12350L.toString, 0.0, 0.0, SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP), geom6, 0L, LinkStatus.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geom6), 0L, 0, 0, reversed = false, None, NewIdValue)
      val pl7 = ProjectLink(-1000L, 9999L, 1L, Track.RightSide, Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None, None, 12351L.toString, 0.0, 0.0, SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP), geom7, 0L, LinkStatus.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geom7), 0L, 0, 0, reversed = false, None, NewIdValue)
      val pl8 = ProjectLink(-1000L, 9999L, 1L, Track.RightSide, Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None, None, 12352L.toString, 0.0, 0.0, SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP), geom8, 0L, LinkStatus.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geom8), 0L, 0, 0, reversed = false, None, NewIdValue)
      val pl9 = ProjectLink(-1000L, 9999L, 1L, Track.RightSide, Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None, None, 12353L.toString, 0.0, 0.0, SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP), geom9, 0L, LinkStatus.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geom9), 0L, 0, 0, reversed = false, None, NewIdValue)
      val pl10 = ProjectLink(-1000L, 9999L, 1L, Track.RightSide, Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None, None, 12354L.toString, 0.0, 0.0, SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP), geom10, 0L, LinkStatus.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geom10), 0L, 0, 0, reversed = false, None, NewIdValue)
      val pl11 = ProjectLink(-1000L, 9999L, 1L, Track.RightSide, Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None, None, 12355L.toString, 0.0, 0.0, SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP), geom11, 0L, LinkStatus.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geom11), 0L, 0, 0, reversed = false, None, NewIdValue)
      val pl12 = ProjectLink(-1000L, 9999L, 1L, Track.RightSide, Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None, None, 12356L.toString, 0.0, 0.0, SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP), geom12, 0L, LinkStatus.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geom12), 0L, 0, 0, reversed = false, None, NewIdValue)
      val pl13 = ProjectLink(-1000L, 9999L, 1L, Track.RightSide, Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None, None, 12357L.toString, 0.0, 0.0, SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP), geom13, 0L, LinkStatus.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geom13), 0L, 0, 0, reversed = false, None, NewIdValue)

      val project = projectService.createRoadLinkProject(rap)
      when(mockRoadLinkService.getRoadLinksByLinkIds(any[Set[String]])).thenReturn(Seq(pl1, pl2, pl3, pl4, pl5, pl6, pl7).map(toRoadLink))

      projectService.createProjectLinks(Seq(12345L, 12346L, 12347L, 12348L, 12349L, 12350L, 12351L).map(_.toString), project.id, roadNumber = 9999, roadPartNumber = 1, track = Track.RightSide, userGivenDiscontinuity = Discontinuity.Continuous, administrativeClass = AdministrativeClass.State, roadLinkSource = LinkGeomSource.NormalLinkInterface, roadEly = 8L, user = "test", roadName = "road name")

      when(mockRoadLinkService.getRoadLinksByLinkIds(Set(12352L, 12353L, 12354L).map(_.toString))).thenReturn(Seq(pl8, pl9, pl10).map(toRoadLink))
      projectService.createProjectLinks(Seq(12352L, 12353L, 12354L).map(_.toString), project.id, roadNumber = 9999, roadPartNumber = 1, track = Track.LeftSide, userGivenDiscontinuity = Discontinuity.Continuous, administrativeClass = AdministrativeClass.State, roadLinkSource = LinkGeomSource.NormalLinkInterface, roadEly = 8L, user = "test", roadName = "road name")
      when(mockRoadLinkService.getRoadLinksByLinkIds(Set(12355L, 12356L, 12357L).map(_.toString))).thenReturn(Seq(pl11, pl12, pl13).map(toRoadLink))
      projectService.createProjectLinks(Seq(12355L, 12356L, 12357L).map(_.toString), project.id, 9999, 1, Track.LeftSide, Discontinuity.Continuous, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, 8L, "test", "road name")

      val linksBeforeChange = projectLinkDAO.fetchProjectLinks(project.id).sortBy(_.startAddrMValue)
      projectService.changeDirection(project.id, 9999L, 1L, Seq(LinkToRevert(pl1.id, pl1.linkId, pl1.status.value, pl1.geometry)), ProjectCoordinates(0, 0, 0), "TestUserTwo")
      val linksAfterChange = projectLinkDAO.fetchProjectLinks(project.id).sortBy(_.startAddrMValue)

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
      val roadNumber = 20000L
      val roadPartNumber = 1L
      val newRoadPartNumber = 2L

      val ra = Seq(
        //Combined
        Roadway(raId, roadwayNumber1, roadNumber, roadPartNumber, AdministrativeClass.State, Track.Combined, Discontinuity.EndOfRoad, 0L, 20L, reversed = false, DateTime.now(), None, "test_user", None, 8, NoTermination, startDate, None),
        //RightSide
        Roadway(raId + 1, roadwayNumber2, roadNumber, newRoadPartNumber, AdministrativeClass.State, Track.Combined, Discontinuity.EndOfRoad, 0L, 50L, reversed = false, DateTime.now(), None, "test_user", None, 8, NoTermination, startDate, None))

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

      val rap = Project(0L, ProjectState.apply(1), "TestProject", "TestUser", DateTime.now(),
        "TestUser", DateTime.now().plusDays(5), DateTime.now(), "Some additional info",
        Seq(), Seq(), None)
      val project = projectService.createRoadLinkProject(rap)
      val project_id = project.id
      val roadway1 = roadwayDAO.fetchAllBySection(roadNumber, roadPartNumber)
      val roadway2 = roadwayDAO.fetchAllBySection(roadNumber, newRoadPartNumber)
      mockForProject(project_id, roadwayAddressMapper.getRoadAddressesByLinearLocation(linearLocationDAO.fetchByRoadways(roadway1.map(_.roadwayNumber).toSet++roadway2.map(_.roadwayNumber).toSet)).map(toProjectLink(project)))
      val reservedPart1 = ProjectReservedPart(0L, roadNumber, roadPartNumber, None, None, None, None, None, None, None)
      val reservedPart2 = ProjectReservedPart(0L, roadNumber, newRoadPartNumber, None, None, None, None, None, None, None)
      projectService.saveProject(project.copy(reservedParts = Seq(reservedPart1, reservedPart2)))
      val projectLinks = projectLinkDAO.fetchProjectLinks(project_id)
      val lastLink = Set(projectLinks.filter(_.roadPartNumber == roadPartNumber).maxBy(_.endAddrMValue).id)

      projectService.updateProjectLinks(project_id, lastLink, Seq(), LinkStatus.Transfer, "test", roadNumber, newRoadPartNumber, 0, None, 1, 5, Some(8L), reversed = false, None)

      val projectLinks_ = projectLinkDAO.fetchProjectLinks(project_id)
      val calculatedProjectLinks = ProjectSectionCalculator.assignMValues(projectLinks_)
      calculatedProjectLinks.foreach(pl => projectLinkDAO.updateAddrMValues(pl))

      val lengthOfTheTransferredPart = 5
      val lengthPart1 = linearLocations.filter(_.roadwayNumber == roadwayNumber1).map(_.endMValue).sum - linearLocations.filter(_.roadwayNumber == roadwayNumber1).map(_.startMValue).sum
      val lengthPart2 = linearLocations.filter(_.roadwayNumber == roadwayNumber2).map(_.endMValue).sum - linearLocations.filter(_.roadwayNumber == roadwayNumber2).map(_.startMValue).sum
      val newLengthOfTheRoadPart1 = lengthPart1 - lengthOfTheTransferredPart
      val newLengthOfTheRoadPart2 = lengthPart2 + lengthOfTheTransferredPart

      val reservation = projectReservedPartDAO.fetchReservedRoadParts(project_id)
      val formed      = projectReservedPartDAO.fetchFormedRoadParts(project_id)

      reservation.filter(_.roadPartNumber == 1).head.addressLength should be(Some(ra.head.endAddrMValue))
      reservation.filter(_.roadPartNumber == 2).head.addressLength should be(Some(ra.last.endAddrMValue))

      formed.filter(_.roadPartNumber == 1).head.newLength should be(Some(newLengthOfTheRoadPart1))
      formed.filter(_.roadPartNumber == 2).head.newLength should be(Some(lengthOfTheTransferredPart))

      val roadWay_2 = roadwayDAO.fetchAllBySection(roadNumber, newRoadPartNumber)
      mockForProject(project_id, roadwayAddressMapper.getRoadAddressesByLinearLocation(linearLocationDAO.fetchByRoadways(roadWay_2.map(_.roadwayNumber).toSet)).map(toProjectLink(project)))

      val projectLinksSet = projectLinkDAO.fetchProjectLinks(project_id).filter(_.roadPartNumber == 2).map(_.id).toSet
      projectService.updateProjectLinks(project_id, projectLinksSet, Seq(), LinkStatus.Transfer, "test", roadNumber, newRoadPartNumber, 0, None, 1, 5, Some(1L), reversed = false, None)

      val reservation2 = projectReservedPartDAO.fetchReservedRoadParts(project_id)
      val formed2 = projectReservedPartDAO.fetchFormedRoadParts(project_id)
      reservation2.filter(_.roadPartNumber == 1).head.addressLength should be(Some(ra.head.endAddrMValue))
      reservation.filter(_.roadPartNumber == 2).head.addressLength should be(Some(ra.last.endAddrMValue))

      formed2.filter(_.roadPartNumber == 1).head.newLength should be(Some(newLengthOfTheRoadPart1))
      formed2.filter(_.roadPartNumber == 2).head.newLength should be(Some(ra.last.endAddrMValue+lengthOfTheTransferredPart))

      projectService.validateProjectById(project_id).exists(
        _.validationError.message == s"Toimenpidettä ei saa tehdä tieosalle, jota ei ole varattu projektiin. Varaa tie $roadNumber osa $newRoadPartNumber."
      ) should be(false)
    }
  }

  test("Test projectService.createProjectLinks() discontinuity assignment When creating some links Then " +
       "the Discontinuity of the last (with bigger endAddrM) new created links should be the one the user " +
       "gave and should not change any other link discontinuity that was previosly given.") {
    runWithRollback {

      val rap = Project(0L, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("1901-01-01"),
        "TestUser", DateTime.parse("1901-01-01"), DateTime.now(), "Some additional info",
        Seq(), Seq(), None)

      val linkAfterRoundabout = ProjectLink(-1000L, 9999L, 1L, Track.apply(0), Discontinuity.EndOfRoad, 0L, 0L, 0L, 0L, None, None, None, 12345L.toString, 0.0, 5.0, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), Seq(Point(15.0, 0.0), Point(20.0, 0.0)), 0L, LinkStatus.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(Seq(Point(15.0, 0.0), Point(20.0, 0.0))), 0L, 0, 0, reversed = false, None, 86400L)
      val linkBeforeRoundaboutMinorDiscontinuous = ProjectLink(-1000L, 9999L, 1L, Track.apply(0), Discontinuity.MinorDiscontinuity, 0L, 0L, 0L, 0L, None, None, None, 12346L.toString, 0.0, 5.0, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), Seq(Point(5.0, 0.0), Point(15.0, 0.0)), 0L, LinkStatus.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(Seq(Point(5.0, 0.0), Point(15.0, 0.0))), 0L, 0, 0, reversed = false, None, 86400L)
      val linkBeforeRoundaboutContinuous = ProjectLink(-1000L, 9999L, 1L, Track.apply(0), Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None, None, 12347L.toString, 0.0, 5.0, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), Seq(Point(0.0, 1.0), Point(5.0, 0.0)), 0L, LinkStatus.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(Seq(Point(0.0, 1.0), Point(5.0, 0.0))), 0L, 0, 0, reversed = false, None, 86400L)


      val project = projectService.createRoadLinkProject(rap)
      when(mockRoadLinkService.getRoadLinksByLinkIds(any[Set[String]])).thenReturn(Seq(linkAfterRoundabout).map(toRoadLink))
      projectService.createProjectLinks(Seq(12345L.toString), project.id, 9999, 1, Track.Combined, Discontinuity.EndOfRoad, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, 8L, "test", "road name")
      when(mockRoadLinkService.getRoadLinksByLinkIds(any[Set[String]])).thenReturn(Seq(linkBeforeRoundaboutMinorDiscontinuous, linkBeforeRoundaboutContinuous).map(toRoadLink))
      projectService.createProjectLinks(Seq(12347L.toString, 12346L.toString), project.id, 9999, 1, Track.Combined, Discontinuity.MinorDiscontinuity, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, 8L, "test", "road name")

      val afterAllProjectLinksCreation = projectLinkDAO.fetchProjectLinks(project.id).sortBy(_.startAddrMValue)

      afterAllProjectLinksCreation.filter(_.linkId == 12345L.toString).head.discontinuity should be (Discontinuity.EndOfRoad)
      afterAllProjectLinksCreation.filter(_.linkId == 12346L.toString).head.discontinuity should be (Discontinuity.Continuous)
      afterAllProjectLinksCreation.filter(_.linkId == 12347L.toString).head.discontinuity should be (Discontinuity.MinorDiscontinuity)
    }
  }

  test("Test updateRoadwaysAndLinearLocationsWithProjectLinks When VIITE-2236 situation Then don't violate unique constraint on roadway_point table.") {
    runWithRollback {
      sqlu"""Insert into LINK (ID,SOURCE,ADJUSTED_TIMESTAMP,CREATED_TIME) values ('568121','4','1446398762000',to_timestamp('27.12.2019 13:12:50','DD.MM.YYYY HH24:MI:SS'))""".execute
      sqlu"""Insert into LINK (ID,SOURCE,ADJUSTED_TIMESTAMP,CREATED_TIME) values ('7256596','4','1498959782000',to_timestamp('27.12.2019 13:12:50','DD.MM.YYYY HH24:MI:SS'))""".execute
      sqlu"""Insert into LINK (ID,SOURCE,ADJUSTED_TIMESTAMP,CREATED_TIME) values ('7256590','4','1498959782000',to_timestamp('27.12.2019 13:12:50','DD.MM.YYYY HH24:MI:SS'))""".execute
      sqlu"""Insert into LINK (ID,SOURCE,ADJUSTED_TIMESTAMP,CREATED_TIME) values ('7256584','4','1498959782000',to_timestamp('27.12.2019 13:12:50','DD.MM.YYYY HH24:MI:SS'))""".execute
      sqlu"""Insert into LINK (ID,SOURCE,ADJUSTED_TIMESTAMP,CREATED_TIME) values ('7256586','4','1503961971000',to_timestamp('27.12.2019 13:12:50','DD.MM.YYYY HH24:MI:SS'))""".execute
      sqlu"""Insert into LINK (ID,SOURCE,ADJUSTED_TIMESTAMP,CREATED_TIME) values ('7256594','4','1533681057000',to_timestamp('27.12.2019 13:12:50','DD.MM.YYYY HH24:MI:SS'))""".execute
      sqlu"""Insert into LINK (ID,SOURCE,ADJUSTED_TIMESTAMP,CREATED_TIME) values ('568164','4','1449097206000',to_timestamp('27.12.2019 13:12:50','DD.MM.YYYY HH24:MI:SS'))""".execute
      sqlu"""Insert into LINK (ID,SOURCE,ADJUSTED_TIMESTAMP,CREATED_TIME) values ('568122','4','1449097206000',to_timestamp('27.12.2019 13:12:50','DD.MM.YYYY HH24:MI:SS'))""".execute

      sqlu"""Insert into ROADWAY (ID,ROADWAY_NUMBER,ROAD_NUMBER,ROAD_PART_NUMBER,TRACK,START_ADDR_M,END_ADDR_M,REVERSED,DISCONTINUITY,START_DATE,END_DATE,CREATED_BY,CREATED_TIME,ADMINISTRATIVE_CLASS,ELY,TERMINATED,VALID_FROM,VALID_TO) values ('1052907','40998','22006','12','0','0','215','0','5',to_date('01.01.2017','DD.MM.YYYY'),null,'import',to_timestamp('27.12.2019 13:12:51','DD.MM.YYYY HH24:MI:SS'),'1','2','0',to_date('28.12.2016','DD.MM.YYYY'),null)""".execute
      sqlu"""Insert into ROADWAY (ID,ROADWAY_NUMBER,ROAD_NUMBER,ROAD_PART_NUMBER,TRACK,START_ADDR_M,END_ADDR_M,REVERSED,DISCONTINUITY,START_DATE,END_DATE,CREATED_BY,CREATED_TIME,ADMINISTRATIVE_CLASS,ELY,TERMINATED,VALID_FROM,VALID_TO) values ('1052911','40998','22006','12','0','0','215','0','2',to_date('01.01.1989','DD.MM.YYYY'),to_date('31.12.2016','DD.MM.YYYY'),'import',to_timestamp('27.12.2019 13:12:51','DD.MM.YYYY HH24:MI:SS'),'1','2','0',to_date('28.12.2016','DD.MM.YYYY'),null)""".execute
      sqlu"""Insert into ROADWAY (ID,ROADWAY_NUMBER,ROAD_NUMBER,ROAD_PART_NUMBER,TRACK,START_ADDR_M,END_ADDR_M,REVERSED,DISCONTINUITY,START_DATE,END_DATE,CREATED_BY,CREATED_TIME,ADMINISTRATIVE_CLASS,ELY,TERMINATED,VALID_FROM,VALID_TO) values ('1052912','40999','22006','12','0','215','216','0','2',to_date('01.01.1989','DD.MM.YYYY'),to_date('30.12.1997','DD.MM.YYYY'),'import',to_timestamp('27.12.2019 13:12:51','DD.MM.YYYY HH24:MI:SS'),'1','2','1',to_date('31.01.2013','DD.MM.YYYY'),null)""".execute
      sqlu"""Insert into ROADWAY (ID,ROADWAY_NUMBER,ROAD_NUMBER,ROAD_PART_NUMBER,TRACK,START_ADDR_M,END_ADDR_M,REVERSED,DISCONTINUITY,START_DATE,END_DATE,CREATED_BY,CREATED_TIME,ADMINISTRATIVE_CLASS,ELY,TERMINATED,VALID_FROM,VALID_TO) values ('1052910','41000','22006','34','0','0','310','0','2',to_date('01.01.1989','DD.MM.YYYY'),null,'import',to_timestamp('27.12.2019 13:12:51','DD.MM.YYYY HH24:MI:SS'),'1','2','0',to_date('14.06.2016','DD.MM.YYYY'),null)""".execute
      sqlu"""Insert into ROADWAY (ID,ROADWAY_NUMBER,ROAD_NUMBER,ROAD_PART_NUMBER,TRACK,START_ADDR_M,END_ADDR_M,REVERSED,DISCONTINUITY,START_DATE,END_DATE,CREATED_BY,CREATED_TIME,ADMINISTRATIVE_CLASS,ELY,TERMINATED,VALID_FROM,VALID_TO) values ('1052909','6446223','22006','23','0','0','45','0','2',to_date('20.05.2007','DD.MM.YYYY'),null,'import',to_timestamp('27.12.2019 13:12:51','DD.MM.YYYY HH24:MI:SS'),'1','2','0',to_date('14.06.2016','DD.MM.YYYY'),null)""".execute
      sqlu"""Insert into ROADWAY (ID,ROADWAY_NUMBER,ROAD_NUMBER,ROAD_PART_NUMBER,TRACK,START_ADDR_M,END_ADDR_M,REVERSED,DISCONTINUITY,START_DATE,END_DATE,CREATED_BY,CREATED_TIME,ADMINISTRATIVE_CLASS,ELY,TERMINATED,VALID_FROM,VALID_TO) values ('1052906','6446225','22006','45','0','0','248','0','1',to_date('20.05.2007','DD.MM.YYYY'),null,'import',to_timestamp('27.12.2019 13:12:51','DD.MM.YYYY HH24:MI:SS'),'1','2','0',to_date('25.01.2017','DD.MM.YYYY'),null)""".execute
      sqlu"""Insert into ROADWAY (ID,ROADWAY_NUMBER,ROAD_NUMBER,ROAD_PART_NUMBER,TRACK,START_ADDR_M,END_ADDR_M,REVERSED,DISCONTINUITY,START_DATE,END_DATE,CREATED_BY,CREATED_TIME,ADMINISTRATIVE_CLASS,ELY,TERMINATED,VALID_FROM,VALID_TO) values ('1052908','166883589','22006','12','0','215','295','0','2',to_date('01.01.2017','DD.MM.YYYY'),null,'import',to_timestamp('27.12.2019 13:12:51','DD.MM.YYYY HH24:MI:SS'),'1','2','0',to_date('28.12.2016','DD.MM.YYYY'),null)""".execute

      sqlu"""Insert into LINEAR_LOCATION (ID,ROADWAY_NUMBER,ORDER_NUMBER,LINK_ID,START_MEASURE,END_MEASURE,SIDE,GEOMETRY,VALID_FROM,VALID_TO,CREATED_BY,CREATED_TIME) values ('1039046',  '6446225','1','7256596',      0, 248.793,'3', ST_GeomFromText('LINESTRING(267357.266 6789458.693 0 248.793, 267131.966 6789520.79 0 248.793)', 3067),  to_date('25.01.2017','DD.MM.YYYY'),null,'import',to_timestamp('27.12.2019 13:12:51','DD.MM.YYYY HH24:MI:SS'))""".execute
      sqlu"""Insert into LINEAR_LOCATION (ID,ROADWAY_NUMBER,ORDER_NUMBER,LINK_ID,START_MEASURE,END_MEASURE,SIDE,GEOMETRY,VALID_FROM,VALID_TO,CREATED_BY,CREATED_TIME) values ('1039047',    '40998','1', '568164',      0,   7.685,'3', ST_GeomFromText('LINESTRING(267444.126 6789234.9 0 7.685, 267437.206 6789238.158 0 7.685)', 3067),       to_date('28.12.2016','DD.MM.YYYY'),null,'import',to_timestamp('27.12.2019 13:12:51','DD.MM.YYYY HH24:MI:SS'))""".execute
      sqlu"""Insert into LINEAR_LOCATION (ID,ROADWAY_NUMBER,ORDER_NUMBER,LINK_ID,START_MEASURE,END_MEASURE,SIDE,GEOMETRY,VALID_FROM,VALID_TO,CREATED_BY,CREATED_TIME) values ('1039048',    '40998','2','7256584',      0, 171.504,'2', ST_GeomFromText('LINESTRING(267444.126 6789234.9 0 171.504, 267597.461 6789260.633 0 171.504)', 3067),   to_date('28.12.2016','DD.MM.YYYY'),null,'import',to_timestamp('27.12.2019 13:12:51','DD.MM.YYYY HH24:MI:SS'))""".execute
      sqlu"""Insert into LINEAR_LOCATION (ID,ROADWAY_NUMBER,ORDER_NUMBER,LINK_ID,START_MEASURE,END_MEASURE,SIDE,GEOMETRY,VALID_FROM,VALID_TO,CREATED_BY,CREATED_TIME) values ('1039049',    '40998','3','7256586',      0,  38.688,'2', ST_GeomFromText('LINESTRING(267597.461 6789260.633 0 38.688, 267518.603 6789333.458 0 38.688)', 3067),   to_date('28.12.2016','DD.MM.YYYY'),null,'import',to_timestamp('27.12.2019 13:12:51','DD.MM.YYYY HH24:MI:SS'))""".execute
      sqlu"""Insert into LINEAR_LOCATION (ID,ROADWAY_NUMBER,ORDER_NUMBER,LINK_ID,START_MEASURE,END_MEASURE,SIDE,GEOMETRY,VALID_FROM,VALID_TO,CREATED_BY,CREATED_TIME) values ('1039050','166883589','1','7256586', 38.688, 120.135,'2', ST_GeomFromText('LINESTRING(267597.461 6789260.633 0 120.135, 267518.603 6789333.458 0 120.135)', 3067), to_date('28.12.2016','DD.MM.YYYY'),null,'import',to_timestamp('27.12.2019 13:12:51','DD.MM.YYYY HH24:MI:SS'))""".execute
      sqlu"""Insert into LINEAR_LOCATION (ID,ROADWAY_NUMBER,ORDER_NUMBER,LINK_ID,START_MEASURE,END_MEASURE,SIDE,GEOMETRY,VALID_FROM,VALID_TO,CREATED_BY,CREATED_TIME) values ('1039051',  '6446223','1','7256594',      0,  44.211,'3', ST_GeomFromText('LINESTRING(267597.461 6789260.633 0 44.211, 267633.898 6789283.726 0 44.211)', 3067),   to_date('14.06.2016','DD.MM.YYYY'),null,'import',to_timestamp('27.12.2019 13:12:51','DD.MM.YYYY HH24:MI:SS'))""".execute
      sqlu"""Insert into LINEAR_LOCATION (ID,ROADWAY_NUMBER,ORDER_NUMBER,LINK_ID,START_MEASURE,END_MEASURE,SIDE,GEOMETRY,VALID_FROM,VALID_TO,CREATED_BY,CREATED_TIME) values ('1039052',    '41000','1','7256590',      0, 130.538,'2', ST_GeomFromText('LINESTRING(267431.685 6789375.9 0 130.538, 267357.266 6789458.693 0 130.538)', 3067),   to_date('14.06.2016','DD.MM.YYYY'),null,'import',to_timestamp('27.12.2019 13:12:51','DD.MM.YYYY HH24:MI:SS'))""".execute
      sqlu"""Insert into LINEAR_LOCATION (ID,ROADWAY_NUMBER,ORDER_NUMBER,LINK_ID,START_MEASURE,END_MEASURE,SIDE,GEOMETRY,VALID_FROM,VALID_TO,CREATED_BY,CREATED_TIME) values ('1039053',    '41000','2', '568121',      0, 177.547,'3', ST_GeomFromText('LINESTRING(267525.375 6789455.936 0 177.547, 267357.266 6789458.693 0 177.547)', 3067), to_date('14.06.2016','DD.MM.YYYY'),null,'import',to_timestamp('27.12.2019 13:12:51','DD.MM.YYYY HH24:MI:SS'))""".execute
      sqlu"""Insert into LINEAR_LOCATION (ID,ROADWAY_NUMBER,ORDER_NUMBER,LINK_ID,START_MEASURE,END_MEASURE,SIDE,GEOMETRY,VALID_FROM,VALID_TO,CREATED_BY,CREATED_TIME) values ('1039054',    '41000','3', '568122',      0,   9.514,'3', ST_GeomFromText('LINESTRING(267534.612 6789453.659 0 9.514, 267525.375 6789455.936 0 9.514)', 3067),     to_date('14.06.2016','DD.MM.YYYY'),null,'import',to_timestamp('27.12.2019 13:12:51','DD.MM.YYYY HH24:MI:SS'))""".execute

      sqlu"""Insert into ROADWAY_POINT (ID,ROADWAY_NUMBER,ADDR_M,CREATED_BY,CREATED_TIME,MODIFIED_BY,MODIFIED_TIME) values ('1019231','6446225','0','import',to_timestamp('27.12.2019 13:12:50','DD.MM.YYYY HH24:MI:SSXFF'),'import',to_timestamp('27.12.2019 13:12:50','DD.MM.YYYY HH24:MI:SS'))""".execute
      sqlu"""Insert into ROADWAY_POINT (ID,ROADWAY_NUMBER,ADDR_M,CREATED_BY,CREATED_TIME,MODIFIED_BY,MODIFIED_TIME) values ('1019232','6446225','248','import',to_timestamp('27.12.2019 13:12:50','DD.MM.YYYY HH24:MI:SSXFF'),'import',to_timestamp('27.12.2019 13:12:50','DD.MM.YYYY HH24:MI:SS'))""".execute
      sqlu"""Insert into ROADWAY_POINT (ID,ROADWAY_NUMBER,ADDR_M,CREATED_BY,CREATED_TIME,MODIFIED_BY,MODIFIED_TIME) values ('1019233','40998','0','import',to_timestamp('27.12.2019 13:12:51','DD.MM.YYYY HH24:MI:SSXFF'),'import',to_timestamp('27.12.2019 13:12:51','DD.MM.YYYY HH24:MI:SS'))""".execute
      sqlu"""Insert into ROADWAY_POINT (ID,ROADWAY_NUMBER,ADDR_M,CREATED_BY,CREATED_TIME,MODIFIED_BY,MODIFIED_TIME) values ('1019234','166883589','295','import',to_timestamp('27.12.2019 13:12:51','DD.MM.YYYY HH24:MI:SSXFF'),'import',to_timestamp('27.12.2019 13:12:51','DD.MM.YYYY HH24:MI:SS'))""".execute
      sqlu"""Insert into ROADWAY_POINT (ID,ROADWAY_NUMBER,ADDR_M,CREATED_BY,CREATED_TIME,MODIFIED_BY,MODIFIED_TIME) values ('1019235','6446223','0','import',to_timestamp('27.12.2019 13:12:51','DD.MM.YYYY HH24:MI:SSXFF'),'import',to_timestamp('27.12.2019 13:12:51','DD.MM.YYYY HH24:MI:SS'))""".execute
      sqlu"""Insert into ROADWAY_POINT (ID,ROADWAY_NUMBER,ADDR_M,CREATED_BY,CREATED_TIME,MODIFIED_BY,MODIFIED_TIME) values ('1019236','6446223','45','import',to_timestamp('27.12.2019 13:12:51','DD.MM.YYYY HH24:MI:SSXFF'),'import',to_timestamp('27.12.2019 13:12:51','DD.MM.YYYY HH24:MI:SS'))""".execute
      sqlu"""Insert into ROADWAY_POINT (ID,ROADWAY_NUMBER,ADDR_M,CREATED_BY,CREATED_TIME,MODIFIED_BY,MODIFIED_TIME) values ('1019237','41000','0','import',to_timestamp('27.12.2019 13:12:51','DD.MM.YYYY HH24:MI:SSXFF'),'import',to_timestamp('27.12.2019 13:12:51','DD.MM.YYYY HH24:MI:SS'))""".execute
      sqlu"""Insert into ROADWAY_POINT (ID,ROADWAY_NUMBER,ADDR_M,CREATED_BY,CREATED_TIME,MODIFIED_BY,MODIFIED_TIME) values ('1019238','41000','310','import',to_timestamp('27.12.2019 13:12:51','DD.MM.YYYY HH24:MI:SSXFF'),'import',to_timestamp('27.12.2019 13:12:51','DD.MM.YYYY HH24:MI:SS'))""".execute
      sqlu"""Insert into ROADWAY_POINT (ID,ROADWAY_NUMBER,ADDR_M,CREATED_BY,CREATED_TIME,MODIFIED_BY,MODIFIED_TIME) values ('1019239','40998','215','k567997',to_timestamp('27.12.2019 13:26:55','DD.MM.YYYY HH24:MI:SSXFF'),'k567997',to_timestamp('27.12.2019 13:26:55','DD.MM.YYYY HH24:MI:SS'))""".execute
      sqlu"""Insert into ROADWAY_POINT (ID,ROADWAY_NUMBER,ADDR_M,CREATED_BY,CREATED_TIME,MODIFIED_BY,MODIFIED_TIME) values ('1019240','40998','177','k567997',to_timestamp('27.12.2019 13:26:55','DD.MM.YYYY HH24:MI:SSXFF'),'k567997',to_timestamp('27.12.2019 13:26:55','DD.MM.YYYY HH24:MI:SS'))""".execute
      sqlu"""Insert into ROADWAY_POINT (ID,ROADWAY_NUMBER,ADDR_M,CREATED_BY,CREATED_TIME,MODIFIED_BY,MODIFIED_TIME) values ('1019241','41000','275','k567997',to_timestamp('27.12.2019 13:26:55','DD.MM.YYYY HH24:MI:SSXFF'),'k567997',to_timestamp('27.12.2019 13:26:55','DD.MM.YYYY HH24:MI:SS'))""".execute
      sqlu"""Insert into ROADWAY_POINT (ID,ROADWAY_NUMBER,ADDR_M,CREATED_BY,CREATED_TIME,MODIFIED_BY,MODIFIED_TIME) values ('1019242','40998','8','k567997',to_timestamp('27.12.2019 13:26:55','DD.MM.YYYY HH24:MI:SSXFF'),'k567997',to_timestamp('27.12.2019 13:26:55','DD.MM.YYYY HH24:MI:SS'))""".execute
      sqlu"""Insert into ROADWAY_POINT (ID,ROADWAY_NUMBER,ADDR_M,CREATED_BY,CREATED_TIME,MODIFIED_BY,MODIFIED_TIME) values ('1019243','41000','300','k567997',to_timestamp('27.12.2019 13:26:55','DD.MM.YYYY HH24:MI:SSXFF'),'k567997',to_timestamp('27.12.2019 13:26:55','DD.MM.YYYY HH24:MI:SS'))""".execute
      sqlu"""Insert into ROADWAY_POINT (ID,ROADWAY_NUMBER,ADDR_M,CREATED_BY,CREATED_TIME,MODIFIED_BY,MODIFIED_TIME) values ('1019244','41000','127','k567997',to_timestamp('27.12.2019 13:26:55','DD.MM.YYYY HH24:MI:SSXFF'),'k567997',to_timestamp('27.12.2019 13:26:55','DD.MM.YYYY HH24:MI:SS'))""".execute

      sqlu"""Insert into CALIBRATION_POINT (ID,ROADWAY_POINT_ID,LINK_ID,START_END,TYPE,VALID_FROM,VALID_TO,CREATED_BY,CREATED_TIME) values ('1020345','1019231','7256596','0','3',to_date('27.12.2019','DD.MM.YYYY'),null,'import',to_timestamp('27.12.2019 13:12:51','DD.MM.YYYY HH24:MI:SS'))""".execute
      sqlu"""Insert into CALIBRATION_POINT (ID,ROADWAY_POINT_ID,LINK_ID,START_END,TYPE,VALID_FROM,VALID_TO,CREATED_BY,CREATED_TIME) values ('1020346','1019232','7256596','1','3',to_date('27.12.2019','DD.MM.YYYY'),null,'import',to_timestamp('27.12.2019 13:12:51','DD.MM.YYYY HH24:MI:SS'))""".execute
      sqlu"""Insert into CALIBRATION_POINT (ID,ROADWAY_POINT_ID,LINK_ID,START_END,TYPE,VALID_FROM,VALID_TO,CREATED_BY,CREATED_TIME) values ('1020347','1019233','568164','0','3',to_date('27.12.2019','DD.MM.YYYY'),null,'import',to_timestamp('27.12.2019 13:12:51','DD.MM.YYYY HH24:MI:SS'))""".execute
      sqlu"""Insert into CALIBRATION_POINT (ID,ROADWAY_POINT_ID,LINK_ID,START_END,TYPE,VALID_FROM,VALID_TO,CREATED_BY,CREATED_TIME) values ('1020348','1019234','7256586','1','3',to_date('27.12.2019','DD.MM.YYYY'),null,'import',to_timestamp('27.12.2019 13:12:51','DD.MM.YYYY HH24:MI:SS'))""".execute
      sqlu"""Insert into CALIBRATION_POINT (ID,ROADWAY_POINT_ID,LINK_ID,START_END,TYPE,VALID_FROM,VALID_TO,CREATED_BY,CREATED_TIME) values ('1020349','1019235','7256594','0','3',to_date('27.12.2019','DD.MM.YYYY'),null,'import',to_timestamp('27.12.2019 13:12:51','DD.MM.YYYY HH24:MI:SS'))""".execute
      sqlu"""Insert into CALIBRATION_POINT (ID,ROADWAY_POINT_ID,LINK_ID,START_END,TYPE,VALID_FROM,VALID_TO,CREATED_BY,CREATED_TIME) values ('1020350','1019236','7256594','1','3',to_date('27.12.2019','DD.MM.YYYY'),null,'import',to_timestamp('27.12.2019 13:12:51','DD.MM.YYYY HH24:MI:SS'))""".execute
      sqlu"""Insert into CALIBRATION_POINT (ID,ROADWAY_POINT_ID,LINK_ID,START_END,TYPE,VALID_FROM,VALID_TO,CREATED_BY,CREATED_TIME) values ('1020351','1019237','7256590','0','3',to_date('27.12.2019','DD.MM.YYYY'),null,'import',to_timestamp('27.12.2019 13:12:51','DD.MM.YYYY HH24:MI:SS'))""".execute
      sqlu"""Insert into CALIBRATION_POINT (ID,ROADWAY_POINT_ID,LINK_ID,START_END,TYPE,VALID_FROM,VALID_TO,CREATED_BY,CREATED_TIME) values ('1020352','1019238','568122','1','3',to_date('27.12.2019','DD.MM.YYYY'),null,'import',to_timestamp('27.12.2019 13:12:51','DD.MM.YYYY HH24:MI:SS'))""".execute

      sqlu"""Insert into PROJECT (ID,STATE,NAME,CREATED_BY,CREATED_DATE,MODIFIED_BY,MODIFIED_DATE,ADD_INFO,START_DATE,STATUS_INFO,TR_ID,COORD_X,COORD_Y,ZOOM) values ('1000351','2','aa','silari',to_date('27.12.2019','DD.MM.YYYY'),'-',to_date('27.12.2019','DD.MM.YYYY'),null,to_date('01.01.2020','DD.MM.YYYY'),null,'1000109',267287.82,6789454.18,'12')""".execute

      sqlu"""Insert into PROJECT_RESERVED_ROAD_PART (ID,ROAD_NUMBER,ROAD_PART_NUMBER,PROJECT_ID,CREATED_BY) values ('1000366','22006','56','1000351','-')""".execute
      sqlu"""Insert into PROJECT_RESERVED_ROAD_PART (ID,ROAD_NUMBER,ROAD_PART_NUMBER,PROJECT_ID,CREATED_BY) values ('1000365','22006','68','1000351','-')""".execute
      sqlu"""Insert into PROJECT_RESERVED_ROAD_PART (ID,ROAD_NUMBER,ROAD_PART_NUMBER,PROJECT_ID,CREATED_BY) values ('1000352','22006','12','1000351','silari')""".execute
      sqlu"""Insert into PROJECT_RESERVED_ROAD_PART (ID,ROAD_NUMBER,ROAD_PART_NUMBER,PROJECT_ID,CREATED_BY) values ('1000353','22006','23','1000351','silari')""".execute
      sqlu"""Insert into PROJECT_RESERVED_ROAD_PART (ID,ROAD_NUMBER,ROAD_PART_NUMBER,PROJECT_ID,CREATED_BY) values ('1000354','22006','34','1000351','silari')""".execute
      sqlu"""Insert into PROJECT_RESERVED_ROAD_PART (ID,ROAD_NUMBER,ROAD_PART_NUMBER,PROJECT_ID,CREATED_BY) values ('1000369','22006','24','1000351','-')""".execute
      sqlu"""Insert into PROJECT_RESERVED_ROAD_PART (ID,ROAD_NUMBER,ROAD_PART_NUMBER,PROJECT_ID,CREATED_BY) values ('1000355','22006','45','1000351','silari')""".execute
      sqlu"""Insert into PROJECT_RESERVED_ROAD_PART (ID,ROAD_NUMBER,ROAD_PART_NUMBER,PROJECT_ID,CREATED_BY) values ('1000368','22006','67','1000351','-')""".execute

      //                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                  ID,PROJECT_ID,TRACK,DISCONTINUITY_TYPE,ROAD_NUMBER,ROAD_PART_NUMBER,START_ADDR_M,END_ADDR_M,CREATED_BY,MODIFIED_BY,CREATED_DATE,MODIFIED_DATE,STATUS,ROAD_TYPE,ROADWAY_ID,LINEAR_LOCATION_ID,CONNECTED_LINK_ID,ELY,REVERSED,SIDE,START_MEASURE,END_MEASURE,LINK_ID,ADJUSTED_TIMESTAMP,LINK_SOURCE,GEOMETRY,ORIGINAL_START_ADDR_M,ORIGINAL_END_ADDR_M,ROADWAY_NUMBER,START_CALIBRATION_POINT,END_CALIBRATION_POINT,ORIG_START_CALIBRATION_POINT,ORIG_END_CALIBRATION_POINT
      sqlu"""Insert into PROJECT_LINK (ID,PROJECT_ID,TRACK,DISCONTINUITY_TYPE,ROAD_NUMBER,ROAD_PART_NUMBER,START_ADDR_M,END_ADDR_M,CREATED_BY,MODIFIED_BY,CREATED_DATE,MODIFIED_DATE,STATUS,ADMINISTRATIVE_CLASS,ROADWAY_ID,LINEAR_LOCATION_ID,CONNECTED_LINK_ID,ELY,REVERSED,SIDE,START_MEASURE,END_MEASURE,LINK_ID,ADJUSTED_TIMESTAMP,LINK_SOURCE,GEOMETRY,ORIGINAL_START_ADDR_M,ORIGINAL_END_ADDR_M,ROADWAY_NUMBER,START_CALIBRATION_POINT,END_CALIBRATION_POINT,ORIG_START_CALIBRATION_POINT,ORIG_END_CALIBRATION_POINT) values ('1000356','1000351','0','2','22006','67','169','177','silari','silari',to_date('27.12.2019','DD.MM.YYYY'),to_date('27.12.2019','DD.MM.YYYY'),'3','1','1052907','1039047',null,'2','1','2',0,7.685,'568164','1449097206000','4',      ST_GeomFromText('LINESTRING(267444.126 6789234.9 53.176999999996, 267440.935 6789235.99 53.2259999999951, 267437.206395653 6789238.15776997 53.2499974535623)', 3067),'0','8','166883765',0,3,0,0)""".execute
      sqlu"""Insert into PROJECT_LINK (ID,PROJECT_ID,TRACK,DISCONTINUITY_TYPE,ROAD_NUMBER,ROAD_PART_NUMBER,START_ADDR_M,END_ADDR_M,CREATED_BY,MODIFIED_BY,CREATED_DATE,MODIFIED_DATE,STATUS,ADMINISTRATIVE_CLASS,ROADWAY_ID,LINEAR_LOCATION_ID,CONNECTED_LINK_ID,ELY,REVERSED,SIDE,START_MEASURE,END_MEASURE,LINK_ID,ADJUSTED_TIMESTAMP,LINK_SOURCE,GEOMETRY,ORIGINAL_START_ADDR_M,ORIGINAL_END_ADDR_M,ROADWAY_NUMBER,START_CALIBRATION_POINT,END_CALIBRATION_POINT,ORIG_START_CALIBRATION_POINT,ORIG_END_CALIBRATION_POINT) values ('1000357','1000351','0','5','22006','67','0','169','silari','silari',to_date('27.12.2019','DD.MM.YYYY'),to_date('27.12.2019','DD.MM.YYYY'),'3','1','1052907','1039048',null,'2','1','3',0,171.504,'7256584','1498959782000','4',     ST_GeomFromText('LINESTRING(267444.126 6789234.9 53.176999999996, 267464.548 6789227.526 52.8959999999934, 267496.884 6789219.216 52.2939999999944, 267515.938 6789216.916 51.7979999999952, 267535.906 6789218.028 51.1319999999978, 267556.73 6789224.333 50.304999999993, 267574.187 6789234.039 49.5789999999979, 267588.124 6789247.565 49.0240000000049, 267597.460867063 6789260.63281394 48.7730035736591)', 3067),'8','177','166883765',3,3,0,0)""".execute
      sqlu"""Insert into PROJECT_LINK (ID,PROJECT_ID,TRACK,DISCONTINUITY_TYPE,ROAD_NUMBER,ROAD_PART_NUMBER,START_ADDR_M,END_ADDR_M,CREATED_BY,MODIFIED_BY,CREATED_DATE,MODIFIED_DATE,STATUS,ADMINISTRATIVE_CLASS,ROADWAY_ID,LINEAR_LOCATION_ID,CONNECTED_LINK_ID,ELY,REVERSED,SIDE,START_MEASURE,END_MEASURE,LINK_ID,ADJUSTED_TIMESTAMP,LINK_SOURCE,GEOMETRY,ORIGINAL_START_ADDR_M,ORIGINAL_END_ADDR_M,ROADWAY_NUMBER,START_CALIBRATION_POINT,END_CALIBRATION_POINT,ORIG_START_CALIBRATION_POINT,ORIG_END_CALIBRATION_POINT) values ('1000358','1000351','0','5','22006','56','80','118','silari','silari',to_date('27.12.2019','DD.MM.YYYY'),to_date('27.12.2019','DD.MM.YYYY'),'3','1','1052907','1039049',null,'2','1','3',0,38.688,'7256586','1503961971000','4',     ST_GeomFromText('LINESTRING(267597.461 6789260.633 48.773000000001, 267597.534 6789260.792 48.7719999999972, 267600.106 6789269.768 48.6059999999998, 267600.106 6789280.257 48.4780000000028, 267597.713 6789287.648 48.4360000000015, 267591.642 6789293.381 48.4370000000054, 267589.345139337 6789294.52943033 48.4244527667907)', 3067),'177','215','166883761',3,3,0,0)""".execute
      sqlu"""Insert into PROJECT_LINK (ID,PROJECT_ID,TRACK,DISCONTINUITY_TYPE,ROAD_NUMBER,ROAD_PART_NUMBER,START_ADDR_M,END_ADDR_M,CREATED_BY,MODIFIED_BY,CREATED_DATE,MODIFIED_DATE,STATUS,ADMINISTRATIVE_CLASS,ROADWAY_ID,LINEAR_LOCATION_ID,CONNECTED_LINK_ID,ELY,REVERSED,SIDE,START_MEASURE,END_MEASURE,LINK_ID,ADJUSTED_TIMESTAMP,LINK_SOURCE,GEOMETRY,ORIGINAL_START_ADDR_M,ORIGINAL_END_ADDR_M,ROADWAY_NUMBER,START_CALIBRATION_POINT,END_CALIBRATION_POINT,ORIG_START_CALIBRATION_POINT,ORIG_END_CALIBRATION_POINT) values ('1000359','1000351','0','5','22006','56','0','80','silari','silari',to_date('27.12.2019','DD.MM.YYYY'),to_date('27.12.2019','DD.MM.YYYY'),'3','1','1052908','1039050',null,'2','1','3',38.688,120.135,'7256586','1503961971000','4', ST_GeomFromText('LINESTRING(267589.345139337 6789294.52943033 48.4244527667907, 267578.828 6789299.788 48.3669999999984, 267559.269 6789308.892 48.2510000000038, 267546.792 6789314.963 48.2390000000014, 267533.979 6789321.707 48.2140000000072, 267524.2 6789327.103 48.226999999999, 267521.164 6789329.463 48.247000000003, 267518.603162863 6789333.45774594 48.2559994276524)', 3067),'215','295','166883589',3,0,0,0)""".execute
      sqlu"""Insert into PROJECT_LINK (ID,PROJECT_ID,TRACK,DISCONTINUITY_TYPE,ROAD_NUMBER,ROAD_PART_NUMBER,START_ADDR_M,END_ADDR_M,CREATED_BY,MODIFIED_BY,CREATED_DATE,MODIFIED_DATE,STATUS,ADMINISTRATIVE_CLASS,ROADWAY_ID,LINEAR_LOCATION_ID,CONNECTED_LINK_ID,ELY,REVERSED,SIDE,START_MEASURE,END_MEASURE,LINK_ID,ADJUSTED_TIMESTAMP,LINK_SOURCE,GEOMETRY,ORIGINAL_START_ADDR_M,ORIGINAL_END_ADDR_M,ROADWAY_NUMBER,START_CALIBRATION_POINT,END_CALIBRATION_POINT,ORIG_START_CALIBRATION_POINT,ORIG_END_CALIBRATION_POINT) values ('1000360','1000351','0','1','22006','68','0','45','silari','silari',to_date('27.12.2019','DD.MM.YYYY'),to_date('27.12.2019','DD.MM.YYYY'),'3','1','1052909','1039051',null,'2','1','2',0,44.211,'7256594','1533681057000','4',       ST_GeomFromText('LINESTRING(267597.461 6789260.633 48.773000000001, 267603.782 6789267.752 48.6589999999997, 267610.19 6789273.485 48.5580000000045, 267616.597 6789277.869 48.4689999999973, 267623.131 6789280.868 48.4400000000023, 267633.897953407 6789283.72598763 48.4409999956788)', 3067),'0','45','6446223',3,3,0,0)""".execute
      sqlu"""Insert into PROJECT_LINK (ID,PROJECT_ID,TRACK,DISCONTINUITY_TYPE,ROAD_NUMBER,ROAD_PART_NUMBER,START_ADDR_M,END_ADDR_M,CREATED_BY,MODIFIED_BY,CREATED_DATE,MODIFIED_DATE,STATUS,ADMINISTRATIVE_CLASS,ROADWAY_ID,LINEAR_LOCATION_ID,CONNECTED_LINK_ID,ELY,REVERSED,SIDE,START_MEASURE,END_MEASURE,LINK_ID,ADJUSTED_TIMESTAMP,LINK_SOURCE,GEOMETRY,ORIGINAL_START_ADDR_M,ORIGINAL_END_ADDR_M,ROADWAY_NUMBER,START_CALIBRATION_POINT,END_CALIBRATION_POINT,ORIG_START_CALIBRATION_POINT,ORIG_END_CALIBRATION_POINT) values ('1000361','1000351','0','5','22006','12','0','127','silari','silari',to_date('27.12.2019','DD.MM.YYYY'),to_date('27.12.2019','DD.MM.YYYY'),'3','1','1052910','1039052',null,'2','0','2',0,130.538,'7256590','1498959782000','4',     ST_GeomFromText('LINESTRING(267431.685 6789375.9 48.4470000000001, 267424.673 6789383.39 48.4180000000051, 267415.075 6789389.356 48.448000000004, 267401.067 6789396.879 48.5190000000002, 267384.985 6789404.661 48.6150000000052, 267366.307 6789414.259 48.6889999999985, 267356.079 6789421.259 48.6999999999971, 267351.522 6789425.931 48.7119999999995, 267349.187 6789432.416 48.7799999999988, 267348.928 6789442.533 48.9700000000012, 267352.041 6789450.574 49.1230000000069, 267357.266 6789458.693 49.3099999999977)', 3067),'0','127','166883763',3,3,0,0)""".execute
      sqlu"""Insert into PROJECT_LINK (ID,PROJECT_ID,TRACK,DISCONTINUITY_TYPE,ROAD_NUMBER,ROAD_PART_NUMBER,START_ADDR_M,END_ADDR_M,CREATED_BY,MODIFIED_BY,CREATED_DATE,MODIFIED_DATE,STATUS,ADMINISTRATIVE_CLASS,ROADWAY_ID,LINEAR_LOCATION_ID,CONNECTED_LINK_ID,ELY,REVERSED,SIDE,START_MEASURE,END_MEASURE,LINK_ID,ADJUSTED_TIMESTAMP,LINK_SOURCE,GEOMETRY,ORIGINAL_START_ADDR_M,ORIGINAL_END_ADDR_M,ROADWAY_NUMBER,START_CALIBRATION_POINT,END_CALIBRATION_POINT,ORIG_START_CALIBRATION_POINT,ORIG_END_CALIBRATION_POINT) values ('1000362','1000351','0','5','22006','23','0','174','silari','silari',to_date('27.12.2019','DD.MM.YYYY'),to_date('27.12.2019','DD.MM.YYYY'),'3','1','1052910','1039053',null,'2','0','3',0,177.547,'568121','1446398762000','4',      ST_GeomFromText('LINESTRING(267525.375 6789455.936 52.7309999999998, 267499.516 6789463.571 52.426999999996, 267458.911 6789473.392 52.0339999999997, 267426.281 6789480.881 51.4360000000015, 267403.97 6789481.494 50.7459999999992, 267378.849 6789475.013 49.9879999999976, 267357.54 6789459.007 49.3179999999993, 267357.266194778 6789458.69322321 49.3100056869441)', 3067),'127','301','166883762',3,0,0,0)""".execute
      sqlu"""Insert into PROJECT_LINK (ID,PROJECT_ID,TRACK,DISCONTINUITY_TYPE,ROAD_NUMBER,ROAD_PART_NUMBER,START_ADDR_M,END_ADDR_M,CREATED_BY,MODIFIED_BY,CREATED_DATE,MODIFIED_DATE,STATUS,ADMINISTRATIVE_CLASS,ROADWAY_ID,LINEAR_LOCATION_ID,CONNECTED_LINK_ID,ELY,REVERSED,SIDE,START_MEASURE,END_MEASURE,LINK_ID,ADJUSTED_TIMESTAMP,LINK_SOURCE,GEOMETRY,ORIGINAL_START_ADDR_M,ORIGINAL_END_ADDR_M,ROADWAY_NUMBER,START_CALIBRATION_POINT,END_CALIBRATION_POINT,ORIG_START_CALIBRATION_POINT,ORIG_END_CALIBRATION_POINT) values ('1000363','1000351','0','2','22006','23','174','183','silari','silari',to_date('27.12.2019','DD.MM.YYYY'),to_date('27.12.2019','DD.MM.YYYY'),'3','1','1052910','1039054',null,'2','0','3',0,9.514,'568122','1449097206000','4',      ST_GeomFromText('LINESTRING(267534.612 6789453.659 52.7939999999944, 267529.741 6789454.905 52.8300000000017, 267525.375 6789455.936 52.7309999999998)', 3067),'301','310','166883762',0,3,0,0)""".execute
      sqlu"""Insert into PROJECT_LINK (ID,PROJECT_ID,TRACK,DISCONTINUITY_TYPE,ROAD_NUMBER,ROAD_PART_NUMBER,START_ADDR_M,END_ADDR_M,CREATED_BY,MODIFIED_BY,CREATED_DATE,MODIFIED_DATE,STATUS,ADMINISTRATIVE_CLASS,ROADWAY_ID,LINEAR_LOCATION_ID,CONNECTED_LINK_ID,ELY,REVERSED,SIDE,START_MEASURE,END_MEASURE,LINK_ID,ADJUSTED_TIMESTAMP,LINK_SOURCE,GEOMETRY,ORIGINAL_START_ADDR_M,ORIGINAL_END_ADDR_M,ROADWAY_NUMBER,START_CALIBRATION_POINT,END_CALIBRATION_POINT,ORIG_START_CALIBRATION_POINT,ORIG_END_CALIBRATION_POINT) values ('1000364','1000351','0','2','22006','24','0','248','silari','silari',to_date('27.12.2019','DD.MM.YYYY'),to_date('27.12.2019','DD.MM.YYYY'),'3','1','1052906','1039046',null,'2','1','2',0,248.793,'7256596','1498959782000','4',     ST_GeomFromText('LINESTRING(267357.266 6789458.693 49.3099999999977, 267343.965 6789448.141 49.0489999999991, 267334.962 6789442.665 48.8600000000006, 267328.617 6789440.381 48.7949999999983, 267322.527 6789439.365 48.8000000000029, 267314.914 6789441.141 48.8439999999973, 267303.749 6789445.455 48.976999999999, 267287.508 6789453.576 49.0160000000033, 267268.999 6789462.419 49.2119999999995, 267228.658 6789482.321 49.3870000000024, 267201.225 6789496.844 49.4649999999965, 267167.876 6789511.367 49.7329999999929, 267147.437 6789519.436 49.9670000000042, 267131.966200167 6789520.78998248 50.2599962090907)', 3067),'0','248','6446225',3,3,0,0)""".execute

      sqlu"""Insert into PROJECT_LINK_NAME (ID,PROJECT_ID,ROAD_NUMBER,ROAD_NAME) values ('17','1000351','22006','MOMMOLAN RAMPIT')""".execute

      sqlu"""Insert into ROADWAY_CHANGES (PROJECT_ID,CHANGE_TYPE,OLD_ROAD_NUMBER,NEW_ROAD_NUMBER,OLD_ROAD_PART_NUMBER,NEW_ROAD_PART_NUMBER,OLD_TRACK,NEW_TRACK,OLD_START_ADDR_M,NEW_START_ADDR_M,OLD_END_ADDR_M,NEW_END_ADDR_M,NEW_DISCONTINUITY,NEW_ADMINISTRATIVE_CLASS,NEW_ELY,OLD_ADMINISTRATIVE_CLASS,OLD_DISCONTINUITY,OLD_ELY,REVERSED,ROADWAY_CHANGE_ID) values ('1000351','3','22006','22006','12','67','0','0','0','0','177','177','2','1','2','1','5','2','1','1000753')""".execute
      sqlu"""Insert into ROADWAY_CHANGES (PROJECT_ID,CHANGE_TYPE,OLD_ROAD_NUMBER,NEW_ROAD_NUMBER,OLD_ROAD_PART_NUMBER,NEW_ROAD_PART_NUMBER,OLD_TRACK,NEW_TRACK,OLD_START_ADDR_M,NEW_START_ADDR_M,OLD_END_ADDR_M,NEW_END_ADDR_M,NEW_DISCONTINUITY,NEW_ADMINISTRATIVE_CLASS,NEW_ELY,OLD_ADMINISTRATIVE_CLASS,OLD_DISCONTINUITY,OLD_ELY,REVERSED,ROADWAY_CHANGE_ID) values ('1000351','3','22006','22006','34','12','0','0','0','0','127','127','5','1','2','1','5','2','0','1000754')""".execute
      sqlu"""Insert into ROADWAY_CHANGES (PROJECT_ID,CHANGE_TYPE,OLD_ROAD_NUMBER,NEW_ROAD_NUMBER,OLD_ROAD_PART_NUMBER,NEW_ROAD_PART_NUMBER,OLD_TRACK,NEW_TRACK,OLD_START_ADDR_M,NEW_START_ADDR_M,OLD_END_ADDR_M,NEW_END_ADDR_M,NEW_DISCONTINUITY,NEW_ADMINISTRATIVE_CLASS,NEW_ELY,OLD_ADMINISTRATIVE_CLASS,OLD_DISCONTINUITY,OLD_ELY,REVERSED,ROADWAY_CHANGE_ID) values ('1000351','3','22006','22006','12','56','0','0','215','0','295','80','5','1','2','1','2','2','1','1000755')""".execute
      sqlu"""Insert into ROADWAY_CHANGES (PROJECT_ID,CHANGE_TYPE,OLD_ROAD_NUMBER,NEW_ROAD_NUMBER,OLD_ROAD_PART_NUMBER,NEW_ROAD_PART_NUMBER,OLD_TRACK,NEW_TRACK,OLD_START_ADDR_M,NEW_START_ADDR_M,OLD_END_ADDR_M,NEW_END_ADDR_M,NEW_DISCONTINUITY,NEW_ADMINISTRATIVE_CLASS,NEW_ELY,OLD_ADMINISTRATIVE_CLASS,OLD_DISCONTINUITY,OLD_ELY,REVERSED,ROADWAY_CHANGE_ID) values ('1000351','3','22006','22006','34','23','0','0','127','0','310','183','2','1','2','1','2','2','0','1000756')""".execute
      sqlu"""Insert into ROADWAY_CHANGES (PROJECT_ID,CHANGE_TYPE,OLD_ROAD_NUMBER,NEW_ROAD_NUMBER,OLD_ROAD_PART_NUMBER,NEW_ROAD_PART_NUMBER,OLD_TRACK,NEW_TRACK,OLD_START_ADDR_M,NEW_START_ADDR_M,OLD_END_ADDR_M,NEW_END_ADDR_M,NEW_DISCONTINUITY,NEW_ADMINISTRATIVE_CLASS,NEW_ELY,OLD_ADMINISTRATIVE_CLASS,OLD_DISCONTINUITY,OLD_ELY,REVERSED,ROADWAY_CHANGE_ID) values ('1000351','3','22006','22006','23','68','0','0','0','0','45','45','1','1','2','1','2','2','1','1000757')""".execute
      sqlu"""Insert into ROADWAY_CHANGES (PROJECT_ID,CHANGE_TYPE,OLD_ROAD_NUMBER,NEW_ROAD_NUMBER,OLD_ROAD_PART_NUMBER,NEW_ROAD_PART_NUMBER,OLD_TRACK,NEW_TRACK,OLD_START_ADDR_M,NEW_START_ADDR_M,OLD_END_ADDR_M,NEW_END_ADDR_M,NEW_DISCONTINUITY,NEW_ADMINISTRATIVE_CLASS,NEW_ELY,OLD_ADMINISTRATIVE_CLASS,OLD_DISCONTINUITY,OLD_ELY,REVERSED,ROADWAY_CHANGE_ID) values ('1000351','3','22006','22006','45','24','0','0','0','0','248','248','2','1','2','1','1','2','1','1000758')""".execute
      sqlu"""Insert into ROADWAY_CHANGES (PROJECT_ID,CHANGE_TYPE,OLD_ROAD_NUMBER,NEW_ROAD_NUMBER,OLD_ROAD_PART_NUMBER,NEW_ROAD_PART_NUMBER,OLD_TRACK,NEW_TRACK,OLD_START_ADDR_M,NEW_START_ADDR_M,OLD_END_ADDR_M,NEW_END_ADDR_M,NEW_DISCONTINUITY,NEW_ADMINISTRATIVE_CLASS,NEW_ELY,OLD_ADMINISTRATIVE_CLASS,OLD_DISCONTINUITY,OLD_ELY,REVERSED,ROADWAY_CHANGE_ID) values ('1000351','3','22006','22006','12','56','0','0','177','80','215','118','5','1','2','1','5','2','1','1000759')""".execute

      sqlu"""Insert into ROADWAY_CHANGES_LINK (ROADWAY_CHANGE_ID,PROJECT_ID,PROJECT_LINK_ID) values ('1000753','1000351','1000357')""".execute
      sqlu"""Insert into ROADWAY_CHANGES_LINK (ROADWAY_CHANGE_ID,PROJECT_ID,PROJECT_LINK_ID) values ('1000753','1000351','1000356')""".execute
      sqlu"""Insert into ROADWAY_CHANGES_LINK (ROADWAY_CHANGE_ID,PROJECT_ID,PROJECT_LINK_ID) values ('1000754','1000351','1000361')""".execute
      sqlu"""Insert into ROADWAY_CHANGES_LINK (ROADWAY_CHANGE_ID,PROJECT_ID,PROJECT_LINK_ID) values ('1000755','1000351','1000359')""".execute
      sqlu"""Insert into ROADWAY_CHANGES_LINK (ROADWAY_CHANGE_ID,PROJECT_ID,PROJECT_LINK_ID) values ('1000756','1000351','1000362')""".execute
      sqlu"""Insert into ROADWAY_CHANGES_LINK (ROADWAY_CHANGE_ID,PROJECT_ID,PROJECT_LINK_ID) values ('1000756','1000351','1000363')""".execute
      sqlu"""Insert into ROADWAY_CHANGES_LINK (ROADWAY_CHANGE_ID,PROJECT_ID,PROJECT_LINK_ID) values ('1000757','1000351','1000360')""".execute
      sqlu"""Insert into ROADWAY_CHANGES_LINK (ROADWAY_CHANGE_ID,PROJECT_ID,PROJECT_LINK_ID) values ('1000758','1000351','1000364')""".execute
      sqlu"""Insert into ROADWAY_CHANGES_LINK (ROADWAY_CHANGE_ID,PROJECT_ID,PROJECT_LINK_ID) values ('1000759','1000351','1000358')""".execute
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
        val roadParts = pls.get.groupBy(pl => (pl.roadNumber, pl.roadPartNumber)).keys
        roadParts.foreach(rp => projectReservedPartDAO.reserveRoadPart(project.get.id, rp._1, rp._2, "user"))
        projectLinkDAO.create(pls.get.map(_.copy(projectId = project.get.id)))
      } else {
        projectLinkDAO.create(pls.get)
      }
    }
  }

  test("Test defaultSectionCalculatorStrategy.assignMValues() " +
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
      val project          = Project(projId, ProjectState.Incomplete, "f", "s", DateTime.now(), "", DateTime.now(), DateTime.now(), "", Seq(), Seq(), None, None)

      val projectLinkLeft1  = ProjectLink(projectLinkId, 9999L, 1L, Track.apply(2), Continuous, 0L, endValue, 0L, endValue, None, None, Some("user"), linkId, 0.0, endValue, SideCode.Unknown, (NoCP, NoCP), (CalibrationPointType.NoCP, CalibrationPointType.NoCP), geomLeft1, 0L, LinkStatus.UnChanged, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomLeft1), roadwayId, linearLocationId, 0, reversed = false, None, 86400L, roadwayNumber = 12345L)
      val projectLinkRight1 = ProjectLink(projectLinkId + 1, 9999L, 1L, Track.apply(1), Continuous, 0L, endValue, 0L, endValue, None, None, Some("user"), linkId+1, 0.0, endValue, SideCode.Unknown, (NoCP, NoCP), (CalibrationPointType.NoCP, CalibrationPointType.NoCP), geomRight1, 0L, LinkStatus.UnChanged, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomRight1), roadwayId + 1, linearLocationId + 1, 0, reversed = false, None, 86400L, roadwayNumber = 12346L)

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

      val updatedRoadway1 = roadway1.copy(endAddrMValue = endValue, discontinuity = EndOfRoad, id = projectLinkLeft1.roadwayId)
      val updatedRoadway2 = roadway2.copy(endAddrMValue = endValue, discontinuity = EndOfRoad, id = projectLinkRight1.roadwayId)

      val updatedLinearLocationLeft  = linearLocation1.copy(id = projectLinkLeft1.linearLocationId)
      val updatedLinearLocationRight = linearLocation2.copy(id = projectLinkRight1.linearLocationId)

      buildTestDataForProject(Some(project), Some(Seq(updatedRoadway1, updatedRoadway2)), Some(Seq(updatedLinearLocationLeft, updatedLinearLocationRight)), Some(unChangedProjectLinks))

      when(mockRoadLinkService.getRoadLinksHistoryFromVVH(any[Set[String]])).thenReturn(Seq())
      when(mockRoadLinkService.getRoadLinksByLinkIds(any[Set[String]])).thenReturn(rl1)
      val createSuccessLeft = projectService.createProjectLinks(rl1.map(_.linkId), project.id, 9999L, 1L, Track.LeftSide, EndOfRoad, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, 0, project.createdBy, "9999_1")
      when(mockRoadLinkService.getRoadLinksByLinkIds(any[Set[String]])).thenReturn(rl2)
      val createSuccessRight = projectService.createProjectLinks(rl2.map(_.linkId), project.id, 9999L, 1L, Track.RightSide, EndOfRoad, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, 0, project.createdBy, "9999_1")

      createSuccessLeft should (contain key ("success") and contain value (true))
      createSuccessRight should (contain key ("success") and contain value (true))

      projectService.recalculateProjectLinks(project.id, project.createdBy)
      val projectLinksWithAssignedValues: List[ProjectLink] = projectService.getProjectLinks(project.id).toList

      projectLinksWithAssignedValues.filter(pl => pl.status == LinkStatus.UnChanged).foreach(pl => {
        pl.startAddrMValue should be(0)
        pl.endAddrMValue should be(endValue)
        pl.discontinuity should be(Continuous)
      })

      val calculatedNewLinks = projectLinksWithAssignedValues.filter(pl => pl.status == LinkStatus.New)
      val calculatedNewLinkLefts = calculatedNewLinks.filter(_.track == Track.LeftSide)
      val calculatedNewLinkRights = calculatedNewLinks.filter(_.track == Track.RightSide)
      calculatedNewLinkLefts.minBy(_.startAddrMValue).startAddrMValue should be (endValue)
      calculatedNewLinkRights.minBy(_.startAddrMValue).startAddrMValue should be (endValue)

      val maxNewLeft  = calculatedNewLinkLefts.maxBy(_.endAddrMValue)
      val maxNewRight = calculatedNewLinkRights.maxBy(_.endAddrMValue)
      maxNewLeft.endAddrMValue should be(maxNewRight.endAddrMValue)
      maxNewLeft.discontinuity should be(EndOfRoad)
      maxNewRight.discontinuity should be(EndOfRoad)
    }
  }

  test("Test defaultSectionCalculatorStrategy.assignMValues() " +
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
      val project           = Project(projId, ProjectState.Incomplete, "f", "s", DateTime.now(), "", DateTime.now(), DateTime.now(), "", Seq(), Seq(), None, None)

      val projectLinkLeft1  = ProjectLink(projectLinkId, 9999L, roadPartNumber, Track.apply(2), Continuous, 0L, endValue, 0L, endValue, None, None, Some("user"), 12345L.toString, 0.0, endValue, SideCode.Unknown, (NoCP, NoCP), (CalibrationPointType.NoCP, CalibrationPointType.NoCP), geomLeft1, 0L, LinkStatus.UnChanged, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomLeft1), roadwayId, linearLocationId, 0, reversed = false, None, 86400L, roadwayNumber = 12345L)
      val projectLinkRight1 = ProjectLink(projectLinkId + 1, 9999L, roadPartNumber, Track.apply(1), Continuous, 0L, endValue, 0L, endValue, None, None, Some("user"), 12346L.toString, 0.0, endValue, SideCode.Unknown, (NoCP, NoCP), (CalibrationPointType.NoCP, CalibrationPointType.NoCP), geomRight1, 0L, LinkStatus.UnChanged, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomRight1), roadwayId + 1, linearLocationId + 1, 0, reversed = false, None, 86400L, roadwayNumber = 12346L)

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

      val updatedRoadway1 = roadway1.copy(endAddrMValue = endValue, discontinuity = EndOfRoad, id = projectLinkLeft1.roadwayId)
      val updatedRoadway2 = roadway2.copy(endAddrMValue = endValue, discontinuity = EndOfRoad, id = projectLinkRight1.roadwayId)

      val updatedLinearLocationLeft  = linearLocation1.copy(id = projectLinkLeft1.linearLocationId)
      val updatedLinearLocationRight = linearLocation2.copy(id = projectLinkRight1.linearLocationId)

      buildTestDataForProject(Some(project), Some(Seq(updatedRoadway1, updatedRoadway2)), Some(Seq(updatedLinearLocationLeft, updatedLinearLocationRight)), Some(unChangedProjectLinks))

      when(mockRoadLinkService.getRoadLinksHistoryFromVVH(any[Set[String]])).thenReturn(Seq())
      when(mockRoadLinkService.getRoadLinksByLinkIds(any[Set[String]])).thenReturn(rl1)
      val createSuccessLeft = projectService.createProjectLinks(rl1.map(_.linkId), project.id, 9999L, newRoadPartNumber, Track.LeftSide, EndOfRoad, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, 0, project.createdBy, "9999_1")
      when(mockRoadLinkService.getRoadLinksByLinkIds(any[Set[String]])).thenReturn(rl2)
      val createSuccessRight = projectService.createProjectLinks(rl2.map(_.linkId), project.id, 9999L, newRoadPartNumber, Track.RightSide, EndOfRoad, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, 0, project.createdBy, "9999_1")

      createSuccessLeft should (contain key ("success") and contain value (true))
      createSuccessRight should (contain key ("success") and contain value (true))

      projectService.recalculateProjectLinks(project.id, project.createdBy)
      val projectLinksWithAssignedValues: List[ProjectLink] = projectService.getProjectLinks(project.id).toList

      projectLinksWithAssignedValues.filter(pl => pl.status == LinkStatus.UnChanged).foreach(pl => {
        pl.startAddrMValue should be(0)
        pl.endAddrMValue should be(endValue)
        pl.discontinuity should be(Continuous)
      })

      val calculatedNewLinks = projectLinksWithAssignedValues.filter(pl => pl.status == LinkStatus.New)
      calculatedNewLinks should have (size (10))

      val calculatedNewLinkLefts = calculatedNewLinks.filter(_.track == Track.LeftSide)
      val calculatedNewLinkRights = calculatedNewLinks.filter(_.track == Track.RightSide)
      calculatedNewLinkLefts.minBy(_.startAddrMValue).startAddrMValue should be (0)
      calculatedNewLinkRights.minBy(_.startAddrMValue).startAddrMValue should be (0)

      val maxNewLeft  = calculatedNewLinkLefts.maxBy(_.endAddrMValue)
      val maxNewRight = calculatedNewLinkRights.maxBy(_.endAddrMValue)
      maxNewLeft.endAddrMValue should be(maxNewRight.endAddrMValue)
      maxNewLeft.discontinuity should be(EndOfRoad)
      maxNewRight.discontinuity should be(EndOfRoad)
    }
  }

  test("Test defaultSectionCalculatorStrategy.updateRoadwaysAndLinearLocationsWithProjectLinks() " +
       "When two track road with a new track 2 and partially new track 1 having new track at the end" +
       "Then formed roadways should be continuous.") {
    runWithRollback {
      val createdBy = Some("test")
      val user = createdBy.get
      val roadNumber = 46020
      val roadPartNumber = 1
      val roadName = None
      val project_id = Sequences.nextViiteProjectId

      /* Check the project layout from the ticket 2699. */
      sqlu"""INSERT INTO PROJECT VALUES($project_id, 11, 'test project', $user, TIMESTAMP '2018-03-23 11:36:15.000000', '-', TIMESTAMP '2018-03-23 12:26:33.000000', NULL, TIMESTAMP '2018-03-23 00:00:00.000000', NULL, 0, 564987.0, 6769633.0, 12)""".execute
      sqlu"""INSERT INTO PROJECT_RESERVED_ROAD_PART VALUES (${Sequences.nextViitePrimaryKeySeqValue}, 46020, 1, $project_id, '-')""".execute
      sqlu"""INSERT INTO roadway (id, roadway_number, road_number, road_part_number, track, start_addr_m, end_addr_m, reversed, discontinuity, start_date, end_date, created_by, created_time, administrative_class, ely, terminated, valid_from, valid_to) VALUES(107964, 335560416, 46020, 1, 0, 0, 785, 0, 1, '2022-01-01', NULL, $user, '2022-02-17 09:48:15.355', 2, 3, 0, '2022-02-17 09:48:15.355', NULL)""".execute
      sqlu"""INSERT INTO link (id, "source", adjusted_timestamp, created_time) VALUES(2621644, 4, 1634598047000, '2022-02-17 09:48:15.355'),(2621698, 4, 1533863903000, '2022-02-17 09:48:15.355'),(2621642, 4, 1634598047000, '2022-02-17 09:48:15.355'),(2621640, 4, 1634598047000, '2022-02-17 09:48:15.355'),(2621632, 4, 1634598047000, '2022-02-17 09:48:15.355'),(2621636, 4, 1533863903000, '2022-02-17 09:48:15.355'),(2621288, 4, 1533863903000, '2022-02-17 09:48:15.355'),(2621287, 4, 1533863903000, '2022-02-17 09:48:15.355')""".execute

      sqlu"""INSERT INTO linear_location (id, roadway_number, order_number, link_id, start_measure, end_measure, side, geometry, valid_from, valid_to, created_by, created_time) VALUES(490283, 335560416, 7, 2621644, 0.000, 94.008, 3, 'SRID=3067;LINESTRING ZM(565110.998 6769336.795 90.40499999999884 0, 565028.813 6769382.427 91.38099999999395 94.008)'::public.geometry, '2022-02-17 09:48:15.355', NULL, $createdBy, '2022-02-17 09:48:15.355')""".execute
      sqlu"""INSERT INTO linear_location (id, roadway_number, order_number, link_id, start_measure, end_measure, side, geometry, valid_from, valid_to, created_by, created_time) VALUES(490284, 335560416, 8, 2621698, 0.000, 165.951, 3, 'SRID=3067;LINESTRING ZM(565258.278 6769260.328 82.68899999999849 0, 565110.998 6769336.795 90.40499999999884 165.951)'::public.geometry, '2022-02-17 09:48:15.355', NULL, $createdBy, '2022-02-17 09:48:15.355')""".execute
      sqlu"""INSERT INTO linear_location (id, roadway_number, order_number, link_id, start_measure, end_measure, side, geometry, valid_from, valid_to, created_by, created_time) VALUES(490217, 335560416, 1, 2621642, 0.000, 167.250, 3, 'SRID=3067;LINESTRING ZM(564987.238 6769633.328 102.99000000000524 0, 565123.382 6769720.891 104.16400000000431 167.25)'::public.geometry, '2022-02-17 09:48:15.355', NULL, $createdBy, '2022-02-17 09:48:15.355')""".execute
      sqlu"""INSERT INTO linear_location (id, roadway_number, order_number, link_id, start_measure, end_measure, side, geometry, valid_from, valid_to, created_by, created_time) VALUES(490282, 335560416, 6, 2621640, 0.000, 155.349, 3, 'SRID=3067;LINESTRING ZM(565028.813 6769382.427 91.38099999999395 0, 564891.659 6769455.379 99.18700000000536 155.349)'::public.geometry, '2022-02-17 09:48:15.355', NULL, $createdBy, '2022-02-17 09:48:15.355')""".execute
      sqlu"""INSERT INTO linear_location (id, roadway_number, order_number, link_id, start_measure, end_measure, side, geometry, valid_from, valid_to, created_by, created_time) VALUES(490218, 335560416, 2, 2621632, 0.000, 81.498, 3, 'SRID=3067;LINESTRING ZM(564948.879 6769561.432 100.8859999999986 0, 564987.238 6769633.328 102.99000000000524 81.498)'::public.geometry, '2022-02-17 09:48:15.355', NULL, $createdBy, '2022-02-17 09:48:15.355')""".execute
      sqlu"""INSERT INTO linear_location (id, roadway_number, order_number, link_id, start_measure, end_measure, side, geometry, valid_from, valid_to, created_by, created_time) VALUES(490219, 335560416, 3, 2621636, 0.000, 101.665, 3, 'SRID=3067;LINESTRING ZM(564900.791 6769471.859 99.14599999999336 0, 564948.879 6769561.432 100.8859999999986 101.665)'::public.geometry, '2022-02-17 09:48:15.355', NULL, $createdBy, '2022-02-17 09:48:15.355')""".execute
      sqlu"""INSERT INTO linear_location (id, roadway_number, order_number, link_id, start_measure, end_measure, side, geometry, valid_from, valid_to, created_by, created_time) VALUES(490220, 335560416, 4, 2621288, 0.000, 7.843, 3, 'SRID=3067;LINESTRING ZM(564896.99 6769464.999 99.18300000000454 0, 564900.791 6769471.859 99.14599999999336 7.843)'::public.geometry, '2022-02-17 09:48:15.355', NULL, $createdBy, '2022-02-17 09:48:15.355')""".execute
      sqlu"""INSERT INTO linear_location (id, roadway_number, order_number, link_id, start_measure, end_measure, side, geometry, valid_from, valid_to, created_by, created_time) VALUES(490281, 335560416, 5, 2621287, 0.000, 10.998, 3, 'SRID=3067;LINESTRING ZM(564891.659 6769455.379 99.18700000000536 0, 564896.99 6769464.999 99.18300000000454 10.998)'::public.geometry, '2022-02-17 09:48:15.355', NULL, $createdBy, '2022-02-17 09:48:15.355')""".execute

      val projecLinks = Seq(
          ProjectLink(1000,roadNumber,roadPartNumber,Track.Combined,Discontinuity.Continuous,0,167,0,167,None,None,createdBy,2621642.toString,0.0,167.25,SideCode.AgainstDigitizing,(RoadAddressCP,NoCP),(RoadAddressCP,NoCP),List(Point(564987.0,6769633.0,0.0), Point(565123.0,6769720.0,0.0)),project_id,LinkStatus.UnChanged,AdministrativeClass.Municipality,FrozenLinkInterface,167.25,107964,490217,3,false,None,1634598047000L,335562039,roadName),
          ProjectLink(1001,roadNumber,roadPartNumber,Track.Combined,Discontinuity.Continuous,167,249,167,249,None,None,createdBy,2621632.toString,0.0,81.498,SideCode.AgainstDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(564948.0,6769561.0,0.0), Point(564987.0,6769633.0,0.0)),project_id,LinkStatus.UnChanged,AdministrativeClass.Municipality,FrozenLinkInterface,81.498,107964,490218,3,false,None,1634598047000L,335562039,roadName),
          ProjectLink(1002,roadNumber,roadPartNumber,Track.Combined,Discontinuity.Continuous,249,351,249,351,None,None,createdBy,2621636.toString,0.0,101.665,SideCode.AgainstDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(564900.0,6769471.0,0.0), Point(564948.0,6769561.0,0.0)),project_id,LinkStatus.UnChanged,AdministrativeClass.Municipality,FrozenLinkInterface,101.665,107964,490219,3,false,None,1533863903000L,335562039,roadName),
          ProjectLink(1003,roadNumber,roadPartNumber,Track.Combined,Discontinuity.Continuous,351,358,351,358,None,None,createdBy,2621288.toString,0.0,7.843,SideCode.AgainstDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(564896.0,6769464.0,0.0), Point(564900.0,6769471.0,0.0)),project_id,LinkStatus.UnChanged,AdministrativeClass.Municipality,FrozenLinkInterface,7.843,107964,490220,3,false,None,1533863903000L,335562039,roadName),
          ProjectLink(1004,roadNumber,roadPartNumber,Track.Combined,Discontinuity.Continuous,358,369,358,369,None,None,createdBy,2621287.toString,0.0,10.998,SideCode.AgainstDigitizing,(NoCP,RoadAddressCP),(NoCP,NoCP),List(Point(564891.0,6769455.0,0.0), Point(564896.0,6769464.0,0.0)),project_id,LinkStatus.UnChanged,AdministrativeClass.Municipality,FrozenLinkInterface,10.998,107964,490281,3,false,None,1533863903000L,335562039,roadName),
          ProjectLink(1005,roadNumber,roadPartNumber,Track.LeftSide,Discontinuity.Continuous,369,458,0,0,None,None,createdBy,2621639.toString,0.0,87.736,SideCode.AgainstDigitizing,(RoadAddressCP,NoCP),(NoCP,NoCP),List(Point(564974.0,6769424.0,0.0), Point(564896.0,6769464.0,0.0)),project_id,LinkStatus.New,AdministrativeClass.Municipality,FrozenLinkInterface,87.736,0,0,3,false,None,1533863903000L,335562043,roadName),
          ProjectLink(1006,roadNumber,roadPartNumber,Track.RightSide,Discontinuity.Continuous,369,525,369,525,None,None,createdBy,2621640.toString,0.0,155.349,SideCode.AgainstDigitizing,(RoadAddressCP,NoCP),(NoCP,NoCP),List(Point(565028.0,6769382.0,0.0), Point(564891.0,6769455.0,0.0)),project_id,LinkStatus.Transfer,AdministrativeClass.Municipality,FrozenLinkInterface,155.349,107964,490282,3,false,None,1634598047000L,335562042,roadName),
          ProjectLink(1007,roadNumber,roadPartNumber,Track.LeftSide,Discontinuity.Continuous,458,526,0,0,None,None,createdBy,2621652.toString,0.0,67.894,SideCode.AgainstDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(565034.0,6769391.0,0.0), Point(564974.0,6769424.0,0.0)),project_id,LinkStatus.New,AdministrativeClass.Municipality,FrozenLinkInterface,67.894,0,0,3,false,None,1533863903000L,335562043,roadName),
          ProjectLink(1008,roadNumber,roadPartNumber,Track.RightSide,Discontinuity.Continuous,525,619,525,619,None,None,createdBy,2621644.toString,0.0,94.008,SideCode.AgainstDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(565110.0,6769336.0,0.0), Point(565028.0,6769382.0,0.0)),project_id,LinkStatus.Transfer,AdministrativeClass.Municipality,FrozenLinkInterface,94.008,107964,490283,3,false,None,1634598047000L,335562042,roadName),
          ProjectLink(1009,roadNumber,roadPartNumber,Track.LeftSide,Discontinuity.Continuous,526,621,0,0,None,None,createdBy,2621646.toString,0.0,94.565,SideCode.AgainstDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(565117.0,6769346.0,0.0), Point(565034.0,6769391.0,0.0)),project_id,LinkStatus.New,AdministrativeClass.Municipality,FrozenLinkInterface,94.565,0,0,3,false,None,1533863903000L,335562043,roadName),
          ProjectLink(1010,roadNumber,roadPartNumber,Track.RightSide,Discontinuity.Continuous,619,785,619,785,None,None,createdBy,2621698.toString,0.0,165.951,SideCode.AgainstDigitizing,(NoCP,UserDefinedCP),(NoCP,RoadAddressCP),List(Point(565258.0,6769260.0,0.0), Point(565110.0,6769336.0,0.0)),project_id,LinkStatus.Transfer,AdministrativeClass.Municipality,FrozenLinkInterface,165.951,107964,490284,3,false,None,1533863903000L,335562042,roadName),
          ProjectLink(1011,roadNumber,roadPartNumber,Track.LeftSide,Discontinuity.Continuous,621,785,0,164,None,None,createdBy,2621704.toString,0.0,161.799,SideCode.AgainstDigitizing,(NoCP,UserDefinedCP),(NoCP,NoCP),List(Point(565259.0,6769270.0,0.0), Point(565117.0,6769346.0,0.0)),project_id,LinkStatus.New,AdministrativeClass.Municipality,FrozenLinkInterface,161.799,0,0,3,false,Some(2621704.toString),1533863903000L,335562043,roadName),
          ProjectLink(1012,roadNumber,roadPartNumber,Track.RightSide,Discontinuity.Continuous,785,794,0,0,None,None,createdBy,2621718.toString,0.0,9.277,SideCode.AgainstDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(565266.0,6769255.0,0.0), Point(565258.0,6769260.0,0.0)),project_id,LinkStatus.New,AdministrativeClass.Municipality,FrozenLinkInterface,9.277,0,0,3,false,None,1533863903000L,335562044,roadName),
          ProjectLink(1013,roadNumber,roadPartNumber,Track.LeftSide,Discontinuity.Continuous,785,796,164,164,None,None,createdBy,2621704.toString,161.799,172.651,SideCode.AgainstDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(565269.0,6769265.0,0.0), Point(565259.0,6769270.0,0.0)),project_id,LinkStatus.New,AdministrativeClass.Municipality,FrozenLinkInterface,10.852,0,0,3,false,Some(2621704.toString),1533863903000L,335562043,roadName),
          ProjectLink(1014,roadNumber,roadPartNumber,Track.LeftSide,Discontinuity.Continuous,796,804,0,0,None,None,createdBy,2621721.toString,0.0,7.956,SideCode.AgainstDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(565276.0,6769261.0,0.0), Point(565269.0,6769265.0,0.0)),project_id,LinkStatus.New,AdministrativeClass.Municipality,FrozenLinkInterface,7.956,0,0,3,false,None,1533863903000L,335562043,roadName),
          ProjectLink(1015,roadNumber,roadPartNumber,Track.LeftSide,Discontinuity.Continuous,804,936,0,0,None,None,createdBy,2621712.toString,0.0,131.415,SideCode.AgainstDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(565402.0,6769253.0,0.0), Point(565276.0,6769261.0,0.0)),project_id,LinkStatus.New,AdministrativeClass.Municipality,FrozenLinkInterface,131.415,0,0,3,false,None,1634598047000L,335562043,roadName),
          ProjectLink(1016,roadNumber,roadPartNumber,Track.RightSide,Discontinuity.Continuous,794,938,0,0,None,None,createdBy,2621709.toString,0.0,146.366,SideCode.AgainstDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(565406.0,6769245.0,0.0), Point(565266.0,6769255.0,0.0)),project_id,LinkStatus.New,AdministrativeClass.Municipality,FrozenLinkInterface,146.366,0,0,3,false,None,1634598047000L,335562044,roadName),
          ProjectLink(1017,roadNumber,roadPartNumber,Track.RightSide,Discontinuity.EndOfRoad,938,1035,0,0,None,None,createdBy,2621724.toString,0.0,99.195,SideCode.TowardsDigitizing,(NoCP,RoadAddressCP),(NoCP,NoCP),List(Point(565406.0,6769245.0,0.0), Point(565485.0,6769303.0,0.0)),project_id,LinkStatus.New,AdministrativeClass.Municipality,FrozenLinkInterface,99.195,0,0,3,false,None,1634598047000L,335562044,roadName),
          ProjectLink(1018,roadNumber,roadPartNumber,Track.LeftSide,Discontinuity.EndOfRoad,936,1035,0,0,None,None,createdBy,2621723.toString,0.0,97.837,SideCode.TowardsDigitizing,(NoCP,RoadAddressCP),(NoCP,NoCP),List(Point(565402.0,6769253.0,0.0), Point(565485.0,6769303.0,0.0)),project_id,LinkStatus.New,AdministrativeClass.Municipality,FrozenLinkInterface,97.837,0,0,3,false,None,1634598047000L,335562043,roadName)
        )
      when(mockNodesAndJunctionsService.expireObsoleteNodesAndJunctions(any[Seq[ProjectLink]], any[Option[DateTime]], any[String])).thenReturn(Seq())
      projectLinkDAO.create(projecLinks)
      projectService.updateRoadwaysAndLinearLocationsWithProjectLinks(project_id)
      val roadways = roadwayDAO.fetchAllByRoadAndPart(roadNumber,roadPartNumber)
      val firstRw = roadways.find(r => r.startAddrMValue == 0 && r.track == Track.Combined)
      firstRw should be ('defined)
      val secondRw = roadways.find(r => r.endAddrMValue == 785 && r.track == Track.RightSide)
      secondRw should be ('defined)
      val thirdRw = roadways.find(r => r.startAddrMValue == 369 && r.endAddrMValue == 1035 && r.track == Track.LeftSide)
      thirdRw should be ('defined)
      val fourthRw = roadways.find(r => r.startAddrMValue == 785 && r.endAddrMValue == 1035 && r.track == Track.RightSide)
      fourthRw should be ('defined)
    }
  }

    test("Test defaultSectionCalculatorStrategy.updateRoadwaysAndLinearLocationsWithProjectLinks() " +
         "When two track road with a new track 2 and partially new track 1 having new track in the middle" +
         "Then formed roadways should be continuous.") {
    runWithRollback {
      val createdBy = Some("test")
      val user = createdBy.get
      val roadNumber = 46020
      val roadPartNumber = 1
      val roadName = None
      val project_id = Sequences.nextViiteProjectId

      /* Check the project layout from the ticket 2699. */
      sqlu"""INSERT INTO PROJECT VALUES($project_id, 11, 'test project', $user, TIMESTAMP '2018-03-23 11:36:15.000000', '-', TIMESTAMP '2018-03-23 12:26:33.000000', NULL, TIMESTAMP '2018-03-23 00:00:00.000000', NULL, 0, 564987.0, 6769633.0, 12)""".execute
      sqlu"""INSERT INTO PROJECT_RESERVED_ROAD_PART VALUES (${Sequences.nextViitePrimaryKeySeqValue}, 46020, 1, $project_id, '-')""".execute
      sqlu"""INSERT INTO roadway (id, roadway_number, road_number, road_part_number, track, start_addr_m, end_addr_m, reversed, discontinuity, start_date, end_date, created_by, created_time, administrative_class, ely, terminated, valid_from, valid_to) VALUES(107964, 335560416, 46020, 1, 0, 0, 369, 0, 4, '2022-01-01', NULL, $user, '2022-02-17 09:48:15.355', 2, 3, 0, '2022-02-17 09:48:15.355', NULL)""".execute
      sqlu"""INSERT INTO roadway (id, roadway_number, road_number, road_part_number, track, start_addr_m, end_addr_m, reversed, discontinuity, start_date, end_date, created_by, created_time, administrative_class, ely, terminated, valid_from, valid_to) VALUES(1111, 335560417, 46020, 1, 0, 369, 624, 0, 1, '2022-01-01', NULL, $user, '2022-02-17 09:48:15.355', 2, 3, 0, '2022-02-17 09:48:15.355', NULL)""".execute
      sqlu"""INSERT INTO link (id, "source", adjusted_timestamp, created_time) VALUES(2621644, 4, 1634598047000, '2022-02-17 09:48:15.355'),(2621698, 4, 1533863903000, '2022-02-17 09:48:15.355'),(2621642, 4, 1634598047000, '2022-02-17 09:48:15.355'),(2621640, 4, 1634598047000, '2022-02-17 09:48:15.355'),(2621632, 4, 1634598047000, '2022-02-17 09:48:15.355'),(2621636, 4, 1533863903000, '2022-02-17 09:48:15.355'),(2621288, 4, 1533863903000, '2022-02-17 09:48:15.355'),(2621287, 4, 1533863903000, '2022-02-17 09:48:15.355')""".execute

      sqlu"""INSERT INTO linear_location (id, roadway_number, order_number, link_id, start_measure, end_measure, side, geometry, valid_from, valid_to, created_by, created_time) VALUES(490283, 335560416, 7, 2621644, 0.000, 94.008, 3, 'SRID=3067;LINESTRING ZM(565110.998 6769336.795 90.40499999999884 0, 565028.813 6769382.427 91.38099999999395 94.008)'::public.geometry, '2022-02-17 09:48:15.355', NULL, $createdBy, '2022-02-17 09:48:15.355')""".execute
      sqlu"""INSERT INTO linear_location (id, roadway_number, order_number, link_id, start_measure, end_measure, side, geometry, valid_from, valid_to, created_by, created_time) VALUES(490284, 335560416, 8, 2621698, 0.000, 165.951, 3, 'SRID=3067;LINESTRING ZM(565258.278 6769260.328 82.68899999999849 0, 565110.998 6769336.795 90.40499999999884 165.951)'::public.geometry, '2022-02-17 09:48:15.355', NULL, $createdBy, '2022-02-17 09:48:15.355')""".execute
      sqlu"""INSERT INTO linear_location (id, roadway_number, order_number, link_id, start_measure, end_measure, side, geometry, valid_from, valid_to, created_by, created_time) VALUES(490217, 335560416, 1, 2621642, 0.000, 167.250, 3, 'SRID=3067;LINESTRING ZM(564987.238 6769633.328 102.99000000000524 0, 565123.382 6769720.891 104.16400000000431 167.25)'::public.geometry, '2022-02-17 09:48:15.355', NULL, $createdBy, '2022-02-17 09:48:15.355')""".execute
      sqlu"""INSERT INTO linear_location (id, roadway_number, order_number, link_id, start_measure, end_measure, side, geometry, valid_from, valid_to, created_by, created_time) VALUES(490282, 335560416, 6, 2621640, 0.000, 155.349, 3, 'SRID=3067;LINESTRING ZM(565028.813 6769382.427 91.38099999999395 0, 564891.659 6769455.379 99.18700000000536 155.349)'::public.geometry, '2022-02-17 09:48:15.355', NULL, $createdBy, '2022-02-17 09:48:15.355')""".execute
      sqlu"""INSERT INTO linear_location (id, roadway_number, order_number, link_id, start_measure, end_measure, side, geometry, valid_from, valid_to, created_by, created_time) VALUES(490218, 335560416, 2, 2621632, 0.000, 81.498, 3, 'SRID=3067;LINESTRING ZM(564948.879 6769561.432 100.8859999999986 0, 564987.238 6769633.328 102.99000000000524 81.498)'::public.geometry, '2022-02-17 09:48:15.355', NULL, $createdBy, '2022-02-17 09:48:15.355')""".execute
      sqlu"""INSERT INTO linear_location (id, roadway_number, order_number, link_id, start_measure, end_measure, side, geometry, valid_from, valid_to, created_by, created_time) VALUES(490219, 335560416, 3, 2621636, 0.000, 101.665, 3, 'SRID=3067;LINESTRING ZM(564900.791 6769471.859 99.14599999999336 0, 564948.879 6769561.432 100.8859999999986 101.665)'::public.geometry, '2022-02-17 09:48:15.355', NULL, $createdBy, '2022-02-17 09:48:15.355')""".execute
      sqlu"""INSERT INTO linear_location (id, roadway_number, order_number, link_id, start_measure, end_measure, side, geometry, valid_from, valid_to, created_by, created_time) VALUES(490220, 335560416, 4, 2621288, 0.000, 7.843, 3, 'SRID=3067;LINESTRING ZM(564896.99 6769464.999 99.18300000000454 0, 564900.791 6769471.859 99.14599999999336 7.843)'::public.geometry, '2022-02-17 09:48:15.355', NULL, $createdBy, '2022-02-17 09:48:15.355')""".execute
      sqlu"""INSERT INTO linear_location (id, roadway_number, order_number, link_id, start_measure, end_measure, side, geometry, valid_from, valid_to, created_by, created_time) VALUES(490281, 335560416, 5, 2621287, 0.000, 10.998, 3, 'SRID=3067;LINESTRING ZM(564891.659 6769455.379 99.18700000000536 0, 564896.99 6769464.999 99.18300000000454 10.998)'::public.geometry, '2022-02-17 09:48:15.355', NULL, $createdBy, '2022-02-17 09:48:15.355')""".execute

      val projecLinks = Seq(
        ProjectLink(1000,roadNumber,roadPartNumber,Track.Combined,Discontinuity.Continuous,0,167,0,167,None,None,createdBy,2621642.toString,0.0,167.25,SideCode.AgainstDigitizing,(RoadAddressCP,NoCP),(RoadAddressCP,NoCP),List(Point(564987.0,6769633.0,0.0), Point(565123.0,6769720.0,0.0)),project_id,LinkStatus.UnChanged,AdministrativeClass.Municipality,FrozenLinkInterface,167.25,107964,490217,3,false,None,1634598047000L,335562039,roadName),
        ProjectLink(1001,roadNumber,roadPartNumber,Track.Combined,Discontinuity.Continuous,167,249,167,249,None,None,createdBy,2621632.toString,0.0,81.498,SideCode.AgainstDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(564948.0,6769561.0,0.0), Point(564987.0,6769633.0,0.0)),project_id,LinkStatus.UnChanged,AdministrativeClass.Municipality,FrozenLinkInterface,81.498,107964,490218,3,false,None,1634598047000L,335562039,roadName),
        ProjectLink(1002,roadNumber,roadPartNumber,Track.Combined,Discontinuity.Continuous,249,351,249,351,None,None,createdBy,2621636.toString,0.0,101.665,SideCode.AgainstDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(564900.0,6769471.0,0.0), Point(564948.0,6769561.0,0.0)),project_id,LinkStatus.UnChanged,AdministrativeClass.Municipality,FrozenLinkInterface,101.665,107964,490219,3,false,None,1533863903000L,335562039,roadName),
        ProjectLink(1003,roadNumber,roadPartNumber,Track.Combined,Discontinuity.Continuous,351,358,351,358,None,None,createdBy,2621288.toString,0.0,7.843,SideCode.AgainstDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(564896.0,6769464.0,0.0), Point(564900.0,6769471.0,0.0)),project_id,LinkStatus.UnChanged,AdministrativeClass.Municipality,FrozenLinkInterface,7.843,107964,490220,3,false,None,1533863903000L,335562039,roadName),
        ProjectLink(1004,roadNumber,roadPartNumber,Track.Combined,Discontinuity.Continuous,358,369,358,369,None,None,createdBy,2621287.toString,0.0,10.998,SideCode.AgainstDigitizing,(NoCP,RoadAddressCP),(NoCP,NoCP),List(Point(564891.0,6769455.0,0.0), Point(564896.0,6769464.0,0.0)),project_id,LinkStatus.UnChanged,AdministrativeClass.Municipality,FrozenLinkInterface,10.998,107964,490281,3,false,None,1533863903000L,335562039,roadName),
        ProjectLink(1005,roadNumber,roadPartNumber,Track.LeftSide,Discontinuity.Continuous,369,458,0,0,None,None,createdBy,2621639.toString,0.0,87.736,SideCode.AgainstDigitizing,(RoadAddressCP,NoCP),(NoCP,NoCP),List(Point(564974.0,6769424.0,0.0), Point(564896.0,6769464.0,0.0)),project_id,LinkStatus.New,AdministrativeClass.Municipality,FrozenLinkInterface,87.736,0,0,3,false,None,1533863903000L,335562043,roadName),
        ProjectLink(1007,roadNumber,roadPartNumber,Track.LeftSide,Discontinuity.Continuous,458,526,0,0,None,None,createdBy,2621652.toString,0.0,67.894,SideCode.AgainstDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(565034.0,6769391.0,0.0), Point(564974.0,6769424.0,0.0)),project_id,LinkStatus.New,AdministrativeClass.Municipality,FrozenLinkInterface,67.894,0,0,3,false,None,1533863903000L,335562043,roadName),
        ProjectLink(1009,roadNumber,roadPartNumber,Track.LeftSide,Discontinuity.Continuous,526,621,0,0,None,None,createdBy,2621646.toString,0.0,94.565,SideCode.AgainstDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(565117.0,6769346.0,0.0), Point(565034.0,6769391.0,0.0)),project_id,LinkStatus.New,AdministrativeClass.Municipality,FrozenLinkInterface,94.565,0,0,3,false,None,1533863903000L,335562043,roadName),
        ProjectLink(1011,roadNumber,roadPartNumber,Track.LeftSide,Discontinuity.Continuous,621,785,0,164,None,None,createdBy,2621704.toString,0.0,161.799,SideCode.AgainstDigitizing,(NoCP,UserDefinedCP),(NoCP,NoCP),List(Point(565259.0,6769270.0,0.0), Point(565117.0,6769346.0,0.0)),project_id,LinkStatus.New,AdministrativeClass.Municipality,FrozenLinkInterface,161.799,0,0,3,false,Some(2621704.toString),1533863903000L,335562043,roadName),
        ProjectLink(1013,roadNumber,roadPartNumber,Track.LeftSide,Discontinuity.Continuous,785,796,164,164,None,None,createdBy,2621704.toString,161.799,172.651,SideCode.AgainstDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(565269.0,6769265.0,0.0), Point(565259.0,6769270.0,0.0)),project_id,LinkStatus.New,AdministrativeClass.Municipality,FrozenLinkInterface,10.852,0,0,3,false,Some(2621704.toString),1533863903000L,335562043,roadName),
        ProjectLink(1014,roadNumber,roadPartNumber,Track.LeftSide,Discontinuity.Continuous,796,804,0,0,None,None,createdBy,2621721.toString,0.0,7.956,SideCode.AgainstDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(565276.0,6769261.0,0.0), Point(565269.0,6769265.0,0.0)),project_id,LinkStatus.New,AdministrativeClass.Municipality,FrozenLinkInterface,7.956,0,0,3,false,None,1533863903000L,335562043,roadName),
        ProjectLink(1015,roadNumber,roadPartNumber,Track.LeftSide,Discontinuity.Continuous,804,936,0,0,None,None,createdBy,2621712.toString,0.0,131.415,SideCode.AgainstDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(565402.0,6769253.0,0.0), Point(565276.0,6769261.0,0.0)),project_id,LinkStatus.New,AdministrativeClass.Municipality,FrozenLinkInterface,131.415,0,0,3,false,None,1634598047000L,335562043,roadName),
        ProjectLink(1018,roadNumber,roadPartNumber,Track.LeftSide,Discontinuity.EndOfRoad,936,1035,0,0,None,None,createdBy,2621723.toString,0.0,97.837,SideCode.TowardsDigitizing,(NoCP,RoadAddressCP),(NoCP,NoCP),List(Point(565402.0,6769253.0,0.0), Point(565485.0,6769303.0,0.0)),project_id,LinkStatus.New,AdministrativeClass.Municipality,FrozenLinkInterface,97.837,0,0,3,false,None,1634598047000L,335562043,roadName),
        ProjectLink(1006,roadNumber,roadPartNumber,Track.RightSide,Discontinuity.Continuous,369,525,0,0,None,None,createdBy,2621640.toString,0.0,155.349,SideCode.AgainstDigitizing,(RoadAddressCP,NoCP),(NoCP,NoCP),List(Point(565028.0,6769382.0,0.0), Point(564891.0,6769455.0,0.0)),project_id,LinkStatus.New,AdministrativeClass.Municipality,FrozenLinkInterface,155.349,0,0,3,false,None,1634598047000L,335562042,roadName),
        ProjectLink(1008,roadNumber,roadPartNumber,Track.RightSide,Discontinuity.Continuous,525,619,0,0,None,None,createdBy,2621644.toString,0.0,94.008,SideCode.AgainstDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(565110.0,6769336.0,0.0), Point(565028.0,6769382.0,0.0)),project_id,LinkStatus.New,AdministrativeClass.Municipality,FrozenLinkInterface,94.008,0,0,3,false,None,1634598047000L,335562042,roadName),
        ProjectLink(1010,roadNumber,roadPartNumber,Track.RightSide,Discontinuity.Continuous,619,785,0,0,None,None,createdBy,2621698.toString,0.0,165.951,SideCode.AgainstDigitizing,(NoCP,UserDefinedCP),(NoCP,RoadAddressCP),List(Point(565258.0,6769260.0,0.0), Point(565110.0,6769336.0,0.0)),project_id,LinkStatus.New,AdministrativeClass.Municipality,FrozenLinkInterface,165.951,0,0,3,false,None,1533863903000L,335562042,roadName),
        ProjectLink(1012,roadNumber,roadPartNumber,Track.RightSide,Discontinuity.Continuous,785,794,785,794,None,None,createdBy,2621718.toString,0.0,9.277,SideCode.AgainstDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(565266.0,6769255.0,0.0), Point(565258.0,6769260.0,0.0)),project_id,LinkStatus.Transfer,AdministrativeClass.Municipality,FrozenLinkInterface,9.277,1111,490282,3,false,None,1533863903000L,335560417,roadName),
        ProjectLink(1016,roadNumber,roadPartNumber,Track.RightSide,Discontinuity.Continuous,794,938,794,938,None,None,createdBy,2621709.toString,0.0,146.366,SideCode.AgainstDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(565406.0,6769245.0,0.0), Point(565266.0,6769255.0,0.0)),project_id,LinkStatus.Transfer,AdministrativeClass.Municipality,FrozenLinkInterface,146.366,1111,490283,3,false,None,1634598047000L,335560417,roadName),
        ProjectLink(1017,roadNumber,roadPartNumber,Track.RightSide,Discontinuity.EndOfRoad,938,1035,938,1035,None,None,createdBy,2621724.toString,0.0,99.195,SideCode.TowardsDigitizing,(NoCP,RoadAddressCP),(NoCP,NoCP),List(Point(565406.0,6769245.0,0.0), Point(565485.0,6769303.0,0.0)),project_id,LinkStatus.Transfer,AdministrativeClass.Municipality,FrozenLinkInterface,99.195,1111,490284,3,false,None,1634598047000L,335560417,roadName)
      )
      when(mockNodesAndJunctionsService.expireObsoleteNodesAndJunctions(any[Seq[ProjectLink]], any[Option[DateTime]], any[String])).thenReturn(Seq())
      projectLinkDAO.create(projecLinks)
      projectService.updateRoadwaysAndLinearLocationsWithProjectLinks(project_id)
      val roadways = roadwayDAO.fetchAllByRoadAndPart(roadNumber,roadPartNumber)
      val firstRw = roadways.find(r => r.startAddrMValue == 0 && r.track == Track.Combined)
      firstRw should be ('defined)
      val secondRw = roadways.find(r => r.endAddrMValue == 785 && r.track == Track.RightSide)
      secondRw should be ('defined)
      val thirdRw = roadways.find(r => r.startAddrMValue == 369 && r.endAddrMValue == 1035 && r.track == Track.LeftSide)
      thirdRw should be ('defined)
      val fourthRw = roadways.find(r => r.startAddrMValue == 785 && r.endAddrMValue == 1035 && r.track == Track.RightSide)
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
      val roadNumber = 46002
      val roadPartNumber = 1
      val createdBy = "Test"
      val roadName = None
      val projectId = Sequences.nextViiteProjectId

      val project = Project(projectId, ProjectState.UpdatingToRoadNetwork, "f", createdBy, DateTime.now(), "", DateTime.now(), DateTime.now(), "", Seq(), Seq(), None, None)

      val projectLinks = Seq(
        ProjectLink(1000,roadNumber,roadPartNumber,Track.RightSide,Discontinuity.Continuous,0,148,0,148,None,None,Some(createdBy),1633068.toString,0.0,147.618,SideCode.TowardsDigitizing,(RoadAddressCP,JunctionPointCP),(RoadAddressCP,JunctionPointCP),List(Point(388372.0,7292385.0,0.0), Point(388487.0,7292477.0,0.0)),projectId,LinkStatus.Terminated,AdministrativeClass.Municipality,FrozenLinkInterface,147.618,107929,501533,14,false,None,1614640323000L,335718837,roadName,None,None,None,None,None,None),
        ProjectLink(1001,roadNumber,roadPartNumber,Track.Combined,Discontinuity.Continuous,0,148,0,148,None,None,Some(createdBy),1633067.toString,0.0,147.652,SideCode.TowardsDigitizing,(RoadAddressCP,JunctionPointCP),(RoadAddressCP,JunctionPointCP),List(Point(388354.0,7292405.0,0.0), Point(388471.0,7292496.0,0.0)),projectId,LinkStatus.Transfer,AdministrativeClass.Municipality,FrozenLinkInterface,147.652,107926,501523,14,false,None,1614640323000L,335718838,roadName,None,None,None,None,None,None),
        ProjectLink(1002,roadNumber,roadPartNumber,Track.Combined,Discontinuity.Continuous,148,169,148,169,None,None,Some(createdBy),1633072.toString,0.0,21.437,SideCode.TowardsDigitizing,(JunctionPointCP,NoCP),(JunctionPointCP,NoCP),List(Point(388471.0,7292496.0,0.0), Point(388488.0,7292509.0,0.0)),projectId,LinkStatus.Transfer,AdministrativeClass.Municipality,FrozenLinkInterface,21.437,107926,501524,14,false,None,1614640323000L,335718838,roadName,None,None,None,None,None,None),
        ProjectLink(1003,roadNumber,roadPartNumber,Track.RightSide,Discontinuity.MinorDiscontinuity,148,169,148,169,None,None,Some(createdBy),1633074.toString,0.0,21.327,SideCode.TowardsDigitizing,(JunctionPointCP,RoadAddressCP),(JunctionPointCP,RoadAddressCP),List(Point(388487.0,7292477.0,0.0), Point(388504.0,7292490.0,0.0)),projectId,LinkStatus.Terminated,AdministrativeClass.Municipality,FrozenLinkInterface,21.327,107929,501534,14,false,None,1614640323000L,335718837,roadName,None,None,None,None,None,None),
        ProjectLink(1004,roadNumber,roadPartNumber,Track.Combined,Discontinuity.Continuous,169,193,169,193,None,None,Some(createdBy),7400060.toString,0.0,24.427,SideCode.AgainstDigitizing,(NoCP,JunctionPointCP),(NoCP,JunctionPointCP),List(Point(388504.0,7292490.0,0.0), Point(388488.0,7292509.0,0.0)),projectId,LinkStatus.Transfer,AdministrativeClass.Municipality,FrozenLinkInterface,24.427,107927,501525,14,false,None,1614640323000L,335718842,roadName,None,None,None,None,None,None),
        ProjectLink(1005,roadNumber,roadPartNumber,Track.RightSide,Discontinuity.Continuous,169,194,169,194,None,None,Some(createdBy),7400059.toString,0.0,24.644,SideCode.AgainstDigitizing,(RoadAddressCP,JunctionPointCP),(RoadAddressCP,JunctionPointCP),List(Point(388487.0,7292477.0,0.0), Point(388471.0,7292496.0,0.0)),projectId,LinkStatus.Terminated,AdministrativeClass.Municipality,FrozenLinkInterface,24.644,107928,501528,14,false,None,1614640323000L,335718841,roadName,None,None,None,None,None,None),
        ProjectLink(1006,roadNumber,roadPartNumber,Track.Combined,Discontinuity.Continuous,193,307,193,307,None,None,Some(createdBy),1633065.toString,0.0,113.892,SideCode.AgainstDigitizing,(JunctionPointCP,NoCP),(JunctionPointCP,NoCP),List(Point(388576.0,7292402.0,0.0), Point(388504.0,7292490.0,0.0)),projectId,LinkStatus.Transfer,AdministrativeClass.Municipality,FrozenLinkInterface,113.892,107927,501526,14,false,None,1614640323000L,335718842,roadName,None,None,None,None,None,None),
        ProjectLink(1007,roadNumber,roadPartNumber,Track.RightSide,Discontinuity.Continuous,194,308,194,308,None,None,Some(createdBy),1633045.toString,0.0,114.201,SideCode.AgainstDigitizing,(JunctionPointCP,NoCP),(JunctionPointCP,NoCP),List(Point(388560.0,7292389.0,0.0), Point(388487.0,7292477.0,0.0)),projectId,LinkStatus.Terminated,AdministrativeClass.Municipality,FrozenLinkInterface,114.201,107928,501529,14,false,None,1614640323000L,335718841,roadName,None,None,None,None,None,None),
        ProjectLink(1008,roadNumber,roadPartNumber,Track.Combined,Discontinuity.Continuous,307,308,307,308,None,None,Some(createdBy),1633239.toString,0.0,0.997,SideCode.AgainstDigitizing,(NoCP,NoCP),(NoCP,RoadAddressCP),List(Point(388577.0,7292401.0,0.0), Point(388576.0,7292402.0,0.0)),projectId,LinkStatus.Transfer,AdministrativeClass.Municipality,FrozenLinkInterface,0.997,107927,501527,14,false,Some(1633239.toString),1614640323000L,335718842,roadName,None,None,None,None,None,None),
        ProjectLink(1009,roadNumber,roadPartNumber,Track.Combined,Discontinuity.Continuous,308,429,308,429,None,None,Some(createdBy),1633239.toString,0.997,121.649,SideCode.AgainstDigitizing,(NoCP,NoCP),(NoCP,RoadAddressCP),List(Point(388654.0,7292308.0,0.0), Point(388577.0,7292401.0,0.0)),projectId,LinkStatus.Transfer,AdministrativeClass.Municipality,FrozenLinkInterface,120.652,107927,501527,14,false,Some(1633239.toString),1614640323000L,335718842,roadName,None,None,None,None,None,None),
        ProjectLink(1010,roadNumber,roadPartNumber,Track.RightSide,Discontinuity.Continuous,308,429,308,429,None,None,Some(createdBy),1633240.toString,0.0,121.571,SideCode.AgainstDigitizing,(NoCP,RoadAddressCP),(NoCP,RoadAddressCP),List(Point(388638.0,7292295.0,0.0), Point(388560.0,7292389.0,0.0)),projectId,LinkStatus.Terminated,AdministrativeClass.Municipality,FrozenLinkInterface,121.571,107928,501530,14,false,None,1614640323000L,335718841,roadName,None,None,None,None,None,None),
        ProjectLink(1011,roadNumber,roadPartNumber,Track.Combined,Discontinuity.Continuous,429,450,429,450,None,None,Some(createdBy),1633241.toString,0.0,20.66,SideCode.TowardsDigitizing,(RoadAddressCP,JunctionPointCP),(RoadAddressCP,JunctionPointCP),List(Point(388638.0,7292295.0,0.0), Point(388654.0,7292308.0,0.0)),projectId,LinkStatus.Terminated,AdministrativeClass.Municipality,FrozenLinkInterface,20.66,107930,501531,14,false,None,1614640323000L,335718862,roadName,None,None,None,None,None,None),
        ProjectLink(1012,roadNumber,roadPartNumber,Track.Combined,Discontinuity.EndOfRoad,429,534,450,555,None,None,Some(createdBy),1633224.toString,0.0,105.224,SideCode.TowardsDigitizing,(NoCP,RoadAddressCP),(JunctionPointCP,RoadAddressCP),List(Point(388654.0,7292308.0,0.0), Point(388736.0,7292374.0,0.0)),projectId,LinkStatus.Transfer,AdministrativeClass.Municipality,FrozenLinkInterface,105.224,107930,501532,14,false,None,1614640323000L,335718857,roadName,None,None,None,None,None,None)
      )

      val roadways = Seq(
        Roadway(107926,335718838,46002,1,AdministrativeClass.Municipality,Track.LeftSide,Discontinuity.Continuous,0,169,reversed = false,DateTime.parse("2022-06-05T00:00:00.000+03:00"),None,createdBy,roadName,14,TerminationCode.NoTermination,DateTime.parse("2022-06-03T00:00:00.000+03:00"),None),
        Roadway(107927,335718842,46002,1,AdministrativeClass.Municipality,Track.LeftSide,Discontinuity.MinorDiscontinuity,169,429,reversed = false,DateTime.parse("2022-06-05T00:00:00.000+03:00"),None,createdBy,roadName,14,TerminationCode.NoTermination,DateTime.parse("2022-06-03T00:00:00.000+03:00"),None),
        Roadway(107928,335718841,46002,1,AdministrativeClass.Municipality,Track.RightSide,Discontinuity.Continuous,169,429,reversed = false,DateTime.parse("2022-06-05T00:00:00.000+03:00"),None,createdBy,roadName,14,TerminationCode.NoTermination,DateTime.parse("2022-06-03T00:00:00.000+03:00"),None),
        Roadway(107929,335718837,46002,1,AdministrativeClass.Municipality,Track.RightSide,Discontinuity.MinorDiscontinuity,0,169,reversed = false,DateTime.parse("2022-06-05T00:00:00.000+03:00"),None,createdBy,roadName,14,TerminationCode.NoTermination,DateTime.parse("2022-06-03T00:00:00.000+03:00"),None),
        Roadway(107930,335718845,46002,1,AdministrativeClass.Municipality,Track.Combined,Discontinuity.EndOfRoad,429,555,reversed = false,DateTime.parse("2022-06-05T00:00:00.000+03:00"),None,createdBy,roadName,14,TerminationCode.NoTermination,DateTime.parse("2022-06-03T00:00:00.000+03:00"),None)
      )

      val linearLocations = Seq(
        LinearLocation(501528, 1.0, 7400059.toString, 0.0, 24.644, AgainstDigitizing, 1614640323000L, (CalibrationPointReference(Some(169),Some(RoadAddressCP)),CalibrationPointReference(Some(194),Some(JunctionPointCP))), List(Point(388487.493,7292477.245,0.0), Point(388471.717,7292496.178,0.0)), FrozenLinkInterface, 335718841, Some(DateTime.parse("2022-06-03T00:00:00.000+03:00")), None),
        LinearLocation(501529, 2.0, 1633045.toString, 0.0, 114.201, AgainstDigitizing, 1614640323000L, (CalibrationPointReference(Some(194),Some(JunctionPointCP)),CalibrationPointReference(None,None)), List(Point(388560.601,7292389.512,0.0), Point(388487.493,7292477.245,0.0)), FrozenLinkInterface, 335718841, Some(DateTime.parse("2022-06-03T00:00:00.000+03:00")), None),
        LinearLocation(501533, 1.0, 1633068.toString, 0.0, 147.618, TowardsDigitizing, 1614640323000L, (CalibrationPointReference(Some(0),Some(RoadAddressCP)),CalibrationPointReference(Some(148),Some(JunctionPointCP))), List(Point(388372.084,7292385.202,0.0), Point(388487.493,7292477.245,0.0)), FrozenLinkInterface, 335718837, Some(DateTime.parse("2022-06-03T00:00:00.000+03:00")), None),
        LinearLocation(501523, 1.0, 1633067.toString, 0.0, 147.652, TowardsDigitizing, 1614640323000L, (CalibrationPointReference(Some(0),Some(RoadAddressCP)),CalibrationPointReference(Some(148),Some(JunctionPointCP))), List(Point(388354.971,7292405.782,0.0), Point(388471.717,7292496.178,0.0)), FrozenLinkInterface, 335718838, Some(DateTime.parse("2022-06-03T00:00:00.000+03:00")), None),
        LinearLocation(501526, 2.0, 1633065.toString, 0.0, 113.892, AgainstDigitizing, 1614640323000L, (CalibrationPointReference(Some(193),Some(JunctionPointCP)),CalibrationPointReference(None,None)), List(Point(388576.757,7292402.664,0.0), Point(388504.223,7292490.472,0.0)), FrozenLinkInterface, 335718842, Some(DateTime.parse("2022-06-03T00:00:00.000+03:00")), None),
        LinearLocation(501534, 2.0, 1633074.toString, 0.0, 21.327, TowardsDigitizing, 1614640323000L, (CalibrationPointReference(Some(148),Some(JunctionPointCP)),CalibrationPointReference(Some(169),Some(RoadAddressCP))), List(Point(388487.493,7292477.245,0.0), Point(388504.223,7292490.472,0.0)), FrozenLinkInterface, 335718837, Some(DateTime.parse("2022-06-03T00:00:00.000+03:00")), None),
        LinearLocation(501524, 2.0, 1633072.toString, 0.0, 21.437, TowardsDigitizing, 1614640323000L, (CalibrationPointReference(Some(148),Some(JunctionPointCP)),CalibrationPointReference(None,None)), List(Point(388471.717,7292496.178,0.0), Point(388488.666,7292509.304,0.0)), FrozenLinkInterface, 335718838, Some(DateTime.parse("2022-06-03T00:00:00.000+03:00")), None),
        LinearLocation(501525, 1.0, 7400060.toString, 0.0, 24.427, AgainstDigitizing, 1614640323000L, (CalibrationPointReference(None,None),CalibrationPointReference(Some(193),Some(JunctionPointCP))), List(Point(388504.223,7292490.472,0.0), Point(388488.666,7292509.304,0.0)), FrozenLinkInterface, 335718842, Some(DateTime.parse("2022-06-03T00:00:00.000+03:00")), None),
        LinearLocation(501532, 2.0, 1633224.toString, 0.0, 105.224, TowardsDigitizing, 1614640323000L, (CalibrationPointReference(Some(450),Some(JunctionPointCP)),CalibrationPointReference(Some(555),Some(RoadAddressCP))), List(Point(388654.231,7292308.876,0.0), Point(388736.089,7292374.992,0.0)), FrozenLinkInterface, 335718845, Some(DateTime.parse("2022-06-03T00:00:00.000+03:00")), None),
        LinearLocation(501531, 1.0, 1633241.toString, 0.0, 20.66, TowardsDigitizing, 1614640323000L, (CalibrationPointReference(Some(429),Some(RoadAddressCP)),CalibrationPointReference(Some(450),Some(JunctionPointCP))), List(Point(388638.159,7292295.894,0.0), Point(388654.231,7292308.876,0.0)), FrozenLinkInterface, 335718845, Some(DateTime.parse("2022-06-03T00:00:00.000+03:00")), None),
        LinearLocation(501527, 3.0, 1633239.toString, 0.0, 121.649, AgainstDigitizing, 1614640323000L, (CalibrationPointReference(None,None),CalibrationPointReference(Some(429),Some(RoadAddressCP))), List(Point(388654.231,7292308.876,0.0), Point(388576.757,7292402.664,0.0)), FrozenLinkInterface, 335718842, Some(DateTime.parse("2022-06-03T00:00:00.000+03:00")), None),
        LinearLocation(501530, 3.0, 1633240.toString, 0.0, 121.571, AgainstDigitizing, 1614640323000L, (CalibrationPointReference(None,None),CalibrationPointReference(Some(429),Some(RoadAddressCP))), List(Point(388638.159,7292295.894,0.0), Point(388560.601,7292389.512,0.0)), FrozenLinkInterface, 335718841, Some(DateTime.parse("2022-06-03T00:00:00.000+03:00")), None)
      )

      when(mockNodesAndJunctionsService.expireObsoleteNodesAndJunctions(any[Seq[ProjectLink]], any[Option[DateTime]], any[String])).thenReturn(Seq())
      roadwayDAO.create(roadways)
      linearLocationDAO.create(linearLocations, createdBy)
      projectDAO.create(project)
      projectReservedPartDAO.reserveRoadPart(projectId, roadNumber, roadPartNumber, createdBy)
      projectLinkDAO.create(projectLinks)
      projectService.updateRoadwaysAndLinearLocationsWithProjectLinks(projectId)

      val newRoadway    = roadwayDAO.fetchAllByRoadAndPart(roadNumber, roadPartNumber).filter(r => r.endDate.isEmpty && r.validTo.isEmpty)
      val newLinearLocs = linearLocationDAO.fetchByLinkId(projectLinks.filter(_.status != LinkStatus.Terminated).map(_.linkId).toSet).filter(_.validTo.isEmpty)

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

      // note, functionality moved here from former ViiteTierekisteriClient at Tierekisteri removal (2021-09)
  test("Test projectService.convertToChangeProject() " +
    "When sending a default ProjectRoadwayChange " +
    "Then check that project_id is replaced with tr_id attribute") {
    val change = projectService.convertToChangeProject(
      List(ProjectRoadwayChange(100L, Some("testproject"), 1, "user", DateTime.now(),
      defaultChangeInfo, DateTime.now(), Some(2))))
    change.id should be(2)
  }

  def toRoadwayAndLinearLocation(p: ProjectLink):(LinearLocation, Roadway) = {
    val startDate = p.startDate.getOrElse(DateTime.now()).minusDays(1)

    (LinearLocation(-1000, 1, p.linkId, p.startMValue, p.endMValue, p.sideCode, p.linkGeometryTimeStamp,
      (CalibrationPointsUtils.toCalibrationPointReference(p.startCalibrationPoint),
        CalibrationPointsUtils.toCalibrationPointReference(p.endCalibrationPoint)),
      p.geometry, p.linkGeomSource,
      p.roadwayNumber, Some(startDate), p.endDate),
      Roadway(-1000, p.roadwayNumber, p.roadNumber, p.roadPartNumber, p.administrativeClass, p.track, p.discontinuity, p.startAddrMValue, p.endAddrMValue, p.reversed, startDate, p.endDate,
        p.createdBy.getOrElse("-"), p.roadName, p.ely, TerminationCode.NoTermination, DateTime.now(), None))
  }

  private def addProjectLinksToProject(linkStatus: LinkStatus, addrM: Seq[Long], changeTrack: Boolean = false, roadNumber: Long = 19999L,
                                       roadPartNumber: Long = 1L, discontinuity: Discontinuity = Discontinuity.Continuous, ely: Long = 8L, roadwayId: Long = 0L,
                                       lastLinkDiscontinuity: Discontinuity = Discontinuity.Continuous, project: Project, roadwayNumber: Long = 0L, withRoadInfo: Boolean = false): Project = {

    def withTrack(t: Track): Seq[ProjectLink] = {
      addrM.init.zip(addrM.tail).map { case (st, en) =>
        projectLink(st, en, t, project.id, linkStatus, roadNumber, roadPartNumber, discontinuity, ely, roadwayId)
      }
    }

    val links =
      if (changeTrack) {
        withTrack(RightSide) ++ withTrack(LeftSide)
      } else {
        withTrack(Combined)
      }
    if(projectReservedPartDAO.fetchReservedRoadPart(roadNumber, roadPartNumber).isEmpty)
      projectReservedPartDAO.reserveRoadPart(project.id, roadNumber, roadPartNumber, "u")
    val newLinks = links.dropRight(1) ++ Seq(links.last.copy(discontinuity = lastLinkDiscontinuity))
    val newLinksWithRoadwayInfo = if(withRoadInfo){
      val (ll, rw) = newLinks.map(_.copy(roadwayNumber = roadwayNumber)).map(toRoadwayAndLinearLocation).unzip
      linearLocationDAO.create(ll)
      roadwayDAO.create(rw)
      val roadways = newLinks.map(p => (p.roadNumber, p.roadPartNumber)).distinct.flatMap(p => roadwayDAO.fetchAllByRoadAndPart(p._1, p._2))
      newLinks.map(nl => {
        val roadway = roadways.find(r => r.roadNumber == nl.roadNumber && r.roadPartNumber == nl.roadPartNumber && r.startAddrMValue == nl.startAddrMValue && r.endAddrMValue == nl.endAddrMValue)
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

  private def projectLink(startAddrM: Long, endAddrM: Long, track: Track, projectId: Long, status: LinkStatus = LinkStatus.NotHandled,
                          roadNumber: Long = 19999L, roadPartNumber: Long = 1L, discontinuity: Discontinuity = Discontinuity.Continuous, ely: Long = 8L, roadwayId: Long = 0L, linearLocationId: Long = 0L) = {
    val startDate = if (status !== LinkStatus.New) Some(DateTime.now()) else None
    ProjectLink(NewIdValue, roadNumber, roadPartNumber, track, discontinuity, startAddrM, endAddrM, startAddrM, endAddrM, startDate, None, Some("User"), startAddrM.toString, 0.0, (endAddrM - startAddrM).toDouble, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), Seq(Point(0.0, startAddrM), Point(0.0, endAddrM)), projectId, status, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, (endAddrM - startAddrM).toDouble, roadwayId, linearLocationId, ely, reversed = false, None, 0L)
  }
}

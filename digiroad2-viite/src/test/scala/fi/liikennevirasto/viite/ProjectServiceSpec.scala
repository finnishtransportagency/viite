package fi.liikennevirasto.viite

import java.sql.BatchUpdateException

import fi.liikennevirasto.GeometryUtils
import fi.liikennevirasto.digiroad2.asset.ConstructionType.InUse
import fi.liikennevirasto.digiroad2.asset.LinkGeomSource.NormalLinkInterface
import fi.liikennevirasto.digiroad2.asset.SideCode.{AgainstDigitizing, TowardsDigitizing}
import fi.liikennevirasto.digiroad2.asset._
import fi.liikennevirasto.digiroad2.client.vvh._
import fi.liikennevirasto.digiroad2.dao.Sequences
import fi.liikennevirasto.digiroad2.linearasset.{PolyLine, RoadLink}
import fi.liikennevirasto.digiroad2.oracle.OracleDatabase
import fi.liikennevirasto.digiroad2.service.RoadLinkService
import fi.liikennevirasto.digiroad2.util.Track
import fi.liikennevirasto.digiroad2.util.Track.{Combined, LeftSide, RightSide}
import fi.liikennevirasto.digiroad2.{DigiroadEventBus, Point}
import fi.liikennevirasto.viite.Dummies._
import fi.liikennevirasto.viite.RoadType.PublicRoad
import fi.liikennevirasto.viite.dao.CalibrationPointSource.ProjectLinkSource
import fi.liikennevirasto.viite.dao.Discontinuity.{Continuous, Discontinuous}
import fi.liikennevirasto.viite.dao.ProjectState.Sent2TR
import fi.liikennevirasto.viite.dao.TerminationCode.NoTermination
import fi.liikennevirasto.viite.dao.{LinkStatus, ProjectRoadwayChange, RoadwayDAO, _}
import fi.liikennevirasto.viite.model.{Anomaly, ProjectAddressLink, RoadAddressLinkLike}
import fi.liikennevirasto.viite.process.RoadwayAddressMapper
import org.joda.time.DateTime
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.{when, _}
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import org.scalatest.mock.MockitoSugar
import org.scalatest.{BeforeAndAfter, FunSuite, Matchers}
import slick.driver.JdbcDriver.backend.Database
import slick.driver.JdbcDriver.backend.Database.dynamicSession
import slick.jdbc.StaticQuery.interpolation

class ProjectServiceSpec extends FunSuite with Matchers with BeforeAndAfter {
  val mockProjectService: ProjectService = MockitoSugar.mock[ProjectService]
  val mockRoadLinkService: RoadLinkService = MockitoSugar.mock[RoadLinkService]
  val mockRoadAddressService: RoadAddressService = MockitoSugar.mock[RoadAddressService]
  val mockNodesAndJunctionsService: NodesAndJunctionsService = MockitoSugar.mock[NodesAndJunctionsService]
  val mockEventBus: DigiroadEventBus = MockitoSugar.mock[DigiroadEventBus]
  val mockVVHClient: VVHClient = MockitoSugar.mock[VVHClient]
  val mockVVHRoadLinkClient: VVHRoadLinkClient = MockitoSugar.mock[VVHRoadLinkClient]
  val mockVVHComplementaryClient: VVHComplementaryClient = MockitoSugar.mock[VVHComplementaryClient]
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

  private val roadwayNumber1 = 1000000000l
  private val roadwayNumber2 = 2000000000l
  private val roadwayNumber3 = 3000000000l
  private val linearLocationId = 1

  val mockRoadwayAddressMapper: RoadwayAddressMapper = MockitoSugar.mock[RoadwayAddressMapper]

  val roadAddressService: RoadAddressService = new RoadAddressService(mockRoadLinkService, roadwayDAO, linearLocationDAO, roadNetworkDAO, roadwayPointDAO, nodePointDAO, junctionPointDAO, mockRoadwayAddressMapper, mockEventBus) {
    override def withDynSession[T](f: => T): T = f

    override def withDynTransaction[T](f: => T): T = f
  }
  val roadAddressServiceRealRoadwayAddressMapper: RoadAddressService = new RoadAddressService(mockRoadLinkService, roadwayDAO, linearLocationDAO, roadNetworkDAO, roadwayPointDAO, nodePointDAO, junctionPointDAO, roadwayAddressMapper, mockEventBus) {
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

  def withDynTransaction[T](f: => T): T = OracleDatabase.withDynTransaction(f)

  def runWithRollback[T](f: => T): T = {
    Database.forDataSource(OracleDatabase.ds).withDynTransaction {
      val t = f
      dynamicSession.rollback()
      t
    }
  }

  val linearLocations = Seq(
    dummyLinearLocation(roadwayNumber = 1L, orderNumber = 1L, linkId = 123L, startMValue = 0.0, endMValue = 10.0),
    dummyLinearLocation(roadwayNumber = 1L, orderNumber = 2L, linkId = 123L, startMValue = 10.0, endMValue = 20.0),
    dummyLinearLocation(roadwayNumber = 1L, orderNumber = 3L, linkId = 124L, startMValue = 0.0, endMValue = 10.0),
    dummyLinearLocation(roadwayNumber = 1L, orderNumber = 4L, linkId = 125L, startMValue = 0.0, endMValue = 10.0)
  )

  val roadways = Seq(
    dummyRoadway(roadwayNumber = 1L, roadNumber = 1L, roadPartNumber = 1L, startAddrM = 0L, endAddrM = 400L, DateTime.now(), None)
  )

  val vvhHistoryRoadLinks = Seq(
    dummyVvhHistoryRoadLink(linkId = 123L, Seq(0.0, 10.0, 20.0)),
    dummyVvhHistoryRoadLink(linkId = 125L, Seq(0.0, 10.0))
  )

  val roadLinks = Seq(
    dummyRoadLink(linkId = 123L, Seq(0.0, 10.0, 20.0), NormalLinkInterface),
    dummyRoadLink(linkId = 124L, Seq(0.0, 10.0), NormalLinkInterface)
  )

  private def createProjectLinks(linkIds: Seq[Long], projectId: Long, roadNumber: Long, roadPartNumber: Long, track: Int,
                                 discontinuity: Int, roadType: Int, roadLinkSource: Int,
                                 roadEly: Long, user: String, roadName: String): Map[String, Any] = {
    projectService.createProjectLinks(linkIds, projectId, roadNumber, roadPartNumber, Track.apply(track), Discontinuity.apply(discontinuity),
      RoadType.apply(roadType), LinkGeomSource.apply(roadLinkSource), roadEly, user, roadName)
  }

  private def toProjectLink(project: Project, status: LinkStatus)(roadAddress: RoadAddress): ProjectLink = {
    ProjectLink(id = NewIdValue, roadAddress.roadNumber, roadAddress.roadPartNumber, roadAddress.track,
      roadAddress.discontinuity, roadAddress.startAddrMValue, roadAddress.endAddrMValue, roadAddress.startAddrMValue, roadAddress.endAddrMValue, roadAddress.startDate,
      roadAddress.endDate, createdBy = Option(project.createdBy), roadAddress.linkId, roadAddress.startMValue, roadAddress.endMValue,
      roadAddress.sideCode, roadAddress.toProjectLinkCalibrationPoints(), roadAddress.geometry, project.id, status, RoadType.PublicRoad,
      roadAddress.linkGeomSource, GeometryUtils.geometryLength(roadAddress.geometry), if (status == LinkStatus.New) 0 else roadAddress.id, if (status == LinkStatus.New) 0 else roadAddress.linearLocationId, roadAddress.ely, reversed = false,
      None, roadAddress.adjustedTimestamp)
  }

  private def toProjectAddressLink(ral: RoadAddressLinkLike): ProjectAddressLink = {
    ProjectAddressLink(ral.id, ral.linkId, ral.geometry, ral.length, ral.administrativeClass, ral.linkType,
      ral.constructionType, ral.roadLinkSource, ral.roadType, ral.VVHRoadName, ral.roadName, ral.municipalityCode, ral.municipalityName, ral.modifiedAt, ral.modifiedBy,
      ral.attributes, ral.roadNumber, ral.roadPartNumber, ral.trackCode, ral.elyCode, ral.discontinuity,
      ral.startAddressM, ral.endAddressM, ral.startMValue, ral.endMValue, ral.sideCode, ral.startCalibrationPoint, ral.endCalibrationPoint,
      ral.anomaly, LinkStatus.Unknown, ral.id, ral.linearLocationId)
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
    RoadLink(ral.linkId, ral.geometry, ral.geometryLength, State, 1,
      extractTrafficDirection(ral.sideCode, ral.track), Motorway, None, None, Map(
        "MUNICIPALITYCODE" -> BigInt(749), "VERTICALLEVEL" -> BigInt(1), "SURFACETYPE" -> BigInt(1),
        "ROADNUMBER" -> BigInt(ral.roadNumber), "ROADPARTNUMBER" -> BigInt(ral.roadPartNumber)),
      ConstructionType.InUse, LinkGeomSource.NormalLinkInterface)
  }

  private def toRoadLink(ral: RoadAddressLinkLike): RoadLink = {
    RoadLink(ral.linkId, ral.geometry, ral.length, ral.administrativeClass, 1,
      extractTrafficDirection(ral.sideCode, Track.apply(ral.trackCode.toInt)), ral.linkType, ral.modifiedAt, ral.modifiedBy, Map(
        "MUNICIPALITYCODE" -> BigInt(749), "VERTICALLEVEL" -> BigInt(1), "SURFACETYPE" -> BigInt(1),
        "ROADNUMBER" -> BigInt(ral.roadNumber), "ROADPARTNUMBER" -> BigInt(ral.roadPartNumber)),
      ral.constructionType, ral.roadLinkSource)
  }

  private def toMockAnswer(projectLinks: Seq[ProjectLink], roadLink: RoadLink, seq: Seq[RoadLink] = Seq()) = {
    new Answer[Seq[RoadLink]]() {
      override def answer(invocation: InvocationOnMock): Seq[RoadLink] = {
        val ids = if (invocation.getArguments.apply(0) == null)
          Set[Long]()
        else invocation.getArguments.apply(0).asInstanceOf[Set[Long]]
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
            roadLink.copy(linkId = pl.head.linkId, geometry = Seq(startFG.get, endFG.get))
          } else
            roadLink.copy(linkId = pl.head.linkId, geometry = Seq(startP, midP, endP))
        }.values.toSeq ++ seq
      }
    }
  }

  private def toMockAnswer(roadLinks: Seq[RoadLink]) = {
    new Answer[Seq[RoadLink]]() {
      override def answer(invocation: InvocationOnMock): Seq[RoadLink] = {
        val ids = invocation.getArguments.apply(0).asInstanceOf[Set[Long]]
        roadLinks.filter(rl => ids.contains(rl.linkId))
      }
    }
  }

  private def mockForProject[T <: PolyLine](id: Long, l: Seq[T] = Seq()) = {
    val roadLink = RoadLink(1, Seq(Point(535602.222, 6982200.25, 89.9999), Point(535605.272, 6982204.22, 85.90899999999965))
      , 540.3960283713503, State, 99, TrafficDirection.AgainstDigitizing, UnknownLinkType, Some("25.06.2015 03:00:00"), Some("vvh_modified"), Map("MUNICIPALITYCODE" -> BigInt.apply(749)),
      InUse, NormalLinkInterface)
    val (projectLinks, palinks) = l.partition(_.isInstanceOf[ProjectLink])
    val dbLinks = projectLinkDAO.fetchProjectLinks(id)
    when(mockRoadLinkService.getRoadLinksHistoryFromVVH(any[Set[Long]])).thenReturn(Seq())
    when(mockRoadLinkService.getRoadLinksByLinkIdsFromVVH(any[Set[Long]])).thenAnswer(
      toMockAnswer(dbLinks ++ projectLinks.asInstanceOf[Seq[ProjectLink]].filterNot(l => dbLinks.map(_.linkId).contains(l.linkId)),
        roadLink, palinks.asInstanceOf[Seq[ProjectAddressLink]].map(toRoadLink)
      ))
  }

  private def setUpProjectWithLinks(linkStatus: LinkStatus, addrM: Seq[Long], changeTrack: Boolean = false, roadNumber: Long = 19999L,
                                    roadPartNumber: Long = 1L, discontinuity: Discontinuity = Discontinuity.Continuous, ely: Long = 8L, roadwayId: Long = 0L, startDate: Option[DateTime] = None) = {
    val id = Sequences.nextViitePrimaryKeySeqValue

    def projectLink(startAddrM: Long, endAddrM: Long, track: Track, projectId: Long, status: LinkStatus = LinkStatus.NotHandled,
                    roadNumber: Long = 19999L, roadPartNumber: Long = 1L, discontinuity: Discontinuity = Discontinuity.Continuous, ely: Long = 8L, linkId: Long = 0L, roadwayId: Long = 0L, linearLocationId: Long = 0L, startDate: Option[DateTime] = None) = {
      ProjectLink(NewIdValue, roadNumber, roadPartNumber, track, discontinuity, startAddrM, endAddrM, startAddrM, endAddrM, startDate, None,
        Some("User"), linkId, 0.0, (endAddrM - startAddrM).toDouble, SideCode.TowardsDigitizing, (None, None),
        Seq(Point(0.0, startAddrM), Point(0.0, endAddrM)), projectId, status, RoadType.PublicRoad,
        LinkGeomSource.NormalLinkInterface, (endAddrM - startAddrM).toDouble, roadwayId, linearLocationId, ely, reversed = false, None, 0L)
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
    ProjectLink(id = NewIdValue, roadAddress.roadNumber, roadAddress.roadPartNumber, roadAddress.track,
      roadAddress.discontinuity, roadAddress.startAddrMValue, roadAddress.endAddrMValue, roadAddress.startAddrMValue, roadAddress.endAddrMValue, roadAddress.startDate,
      roadAddress.endDate, createdBy = Option(project.createdBy), roadAddress.linkId, roadAddress.startMValue, roadAddress.endMValue,
      roadAddress.sideCode, roadAddress.toProjectLinkCalibrationPoints(), roadAddress.geometry, project.id, LinkStatus.NotHandled, RoadType.PublicRoad,
      roadAddress.linkGeomSource, GeometryUtils.geometryLength(roadAddress.geometry), 0, 0, roadAddress.ely, reversed = false,
      None, roadAddress.adjustedTimestamp)
  }

  test("Test createRoadLinkProject When no road parts reserved Then return 0 reserved parts project") {
    runWithRollback {
      val roadAddressProject = Project(0, ProjectState.apply(1), "TestProject", "TestUser", DateTime.now(), "TestUser", DateTime.parse("1901-01-01"), DateTime.now(), "Some additional info", List.empty[ProjectReservedPart], Seq(), None)
      val project = projectService.createRoadLinkProject(roadAddressProject)
      project.reservedParts should have size 0
    }
  }

  test("Test createRoadLinkProject When creating road link project without valid roadParts Then return project without the invalid parts") {
    val roadlink = RoadLink(5175306, Seq(Point(535605.272, 6982204.22, 85.90899999999965))
      , 540.3960283713503, State, 99, TrafficDirection.AgainstDigitizing, UnknownLinkType, Some("25.06.2015 03:00:00"), Some("vvh_modified"), Map("MUNICIPALITYCODE" -> BigInt.apply(749)),
      InUse, NormalLinkInterface)
    when(mockRoadLinkService.getRoadLinksByLinkIdsFromVVH(Set(5175306L))).thenReturn(Seq(roadlink))
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

  test("Test saveProject When two projects with same road part Then return on key error") {
    runWithRollback {
      val error = intercept[BatchUpdateException] {
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
        val project2 = projectService.createRoadLinkProject(rap2)
        mockForProject(project2.id, roadwayAddressMapper.getRoadAddressesByRoadway(roadwayDAO.fetchAllByRoadAndPart(5, 207)).map(toProjectLink(project2)))
        projectService.saveProject(project2.copy(reservedParts = addr1))
      }
      error.getErrorCode should be(2291)
    }
  }

  test("Test saveProject When new part is added Then return project with new reservation") {
    var count = 0
    runWithRollback {
      reset(mockRoadLinkService)
      val roadlink = RoadLink(12345L, Seq(Point(535605.272, 6982204.22, 85.90899999999965))
        , 540.3960283713503, State, 99, TrafficDirection.AgainstDigitizing, UnknownLinkType, Some("25.06.2015 03:00:00"), Some("vvh_modified"), Map("MUNICIPALITYCODE" -> BigInt.apply(749)),
        InUse, NormalLinkInterface)
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
      val ra = Seq(Roadway(id1, roadwayNumber, roadNumber, roadStartPart, RoadType.Unknown, Track.Combined, Discontinuous, 0L, 1000L, reversed = false,
        DateTime.parse("1901-01-01"), None, "tester", Some("test road"), 1L))
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
      val ra = Seq(Roadway(id1, roadwayNumber, roadNumber, roadStartPart, RoadType.Unknown, Track.Combined, Discontinuous, 0L, 1000L, reversed = false,
        DateTime.parse("1901-01-01"), None, "tester", Some("test road"), 1L))
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
      val ra = Seq(Roadway(id1, roadwayNumber, roadNumber, roadEndPart, RoadType.Unknown, Track.Combined, Discontinuous, 0L, 1000L, reversed = false,
        DateTime.parse("1901-01-01"), None, "tester", Some("test road"), 1L))
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
    val roadLink = RoadLink(12345L, Seq(Point(535605.272, 6982204.22, 85.90899999999965))
      , 540.3960283713503, State, 99, TrafficDirection.AgainstDigitizing, UnknownLinkType, Some("25.06.2015 03:00:00"), Some("vvh_modified"), Map("MUNICIPALITYCODE" -> BigInt.apply(749)),
      InUse, NormalLinkInterface)
    when(mockRoadLinkService.getRoadLinksByLinkIdsFromVVH(any[Set[Long]])).thenReturn(Seq(roadLink))
    runWithRollback {
      val reservation = projectService.checkRoadPartsReservable(roadNumber, roadStartPart, roadEndPart)
      reservation.right.get._1.size should be(0)
    }
  }

  test("Test checkRoadPartsReservable When road can Then return on right part 2") {
    val roadNumber = 19438
    val roadStartPart = 1
    val roadEndPart = 2
    val roadwayNumber = 8000
    val roadLink = RoadLink(12345L, Seq(Point(535605.272, 6982204.22, 85.90899999999965))
      , 540.3960283713503, State, 99, TrafficDirection.AgainstDigitizing, UnknownLinkType, Some("25.06.2015 03:00:00"), Some("vvh_modified"), Map("MUNICIPALITYCODE" -> BigInt.apply(749)),
      InUse, NormalLinkInterface)
    when(mockRoadLinkService.getRoadLinksByLinkIdsFromVVH(any[Set[Long]])).thenReturn(Seq(roadLink))
    runWithRollback {
      val id1 = Sequences.nextRoadwayId
      val ra = Seq(Roadway(id1, roadwayNumber, roadNumber, roadStartPart, RoadType.Unknown, Track.Combined, Discontinuous, 0L, 1000L,
        reversed = false, DateTime.parse("1901-01-01"), None, "tester", Some("test road"), 8L))
      val ll = LinearLocation(0L, 1, 123456, 0, 1000L, SideCode.TowardsDigitizing, 123456, (None, None),
        Seq(Point(535605.272, 6982204.22, 85.90899999999965)), LinkGeomSource.NormalLinkInterface, roadwayNumber)
      roadwayDAO.create(ra)
      linearLocationDAO.create(Seq(ll))
      val id2 = Sequences.nextRoadwayId
      val rb = Seq(Roadway(id2, roadwayNumber, roadNumber, roadEndPart, RoadType.Unknown, Track.Combined, Discontinuous, 0L, 1000L,
        reversed = false, DateTime.parse("1901-01-01"), None, "tester", Some("Test road 2"), 8L))
      roadwayDAO.create(rb)
      val reservationAfterB = projectService.checkRoadPartsReservable(roadNumber, roadStartPart, roadEndPart)
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

  test("Test getRotatingTRProjectId, removeRotatingTRId and addRotatingTRProjectId  When project has just been created, when project has no TR_ID and when project already has a TR_ID Then returning no TR_ID, then returning a TR_ID") {
    runWithRollback {
      val projectId = Sequences.nextViitePrimaryKeySeqValue
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
      projectService.changeDirection(id, 5, 207, projectLinks.map(l => LinkToRevert(l.id, l.linkId, l.status.value, l.geometry)), ProjectCoordinates(0, 0, 0), "test") should be(None)
      val updatedProjectLinks = projectLinkDAO.fetchProjectLinks(id)
      val maxBefore = if (projectLinks.nonEmpty) projectLinks.maxBy(_.endAddrMValue).endAddrMValue else 0
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

  test("Test projectService.updateProjectsWaitingResponseFromTR() When project has been created with no reserved parts nor project links Then return project status info should be \"\" ") {
    runWithRollback {
      val projectId = Sequences.nextViitePrimaryKeySeqValue
      val rap = Project(projectId, ProjectState.apply(2), "TestProject", "TestUser", DateTime.parse("2700-01-01"), "TestUser", DateTime.parse("2700-01-01"), DateTime.now(), "Some additional info", List.empty[ProjectReservedPart], Seq(), None)
      runWithRollback {
        projectDAO.create(rap)
        projectDAO.assignNewProjectTRId(projectId)
        projectService.updateProjectsWaitingResponseFromTR()
        val project = projectService.fetchProjectById(projectId).head
        project.statusInfo.getOrElse("").length should be(0)
        projectService.updateProjectsWaitingResponseFromTR()
      }
    }
  }

  test("Test projectService.updateProjectsWaitingResponseFromTR() When project has been created with no reserved parts nor project links Then return project status info should be \"Failed to find TR-ID\" ") {
    runWithRollback {
      val projectId = Sequences.nextViitePrimaryKeySeqValue
      val rap = Project(projectId, ProjectState.apply(2), "TestProject", "TestUser", DateTime.parse("2700-01-01"), "TestUser", DateTime.parse("2700-01-01"), DateTime.now(), "Some additional info", List.empty[ProjectReservedPart], Seq(), None)
      runWithRollback {
        projectDAO.create(rap)
        projectService.updateProjectsWaitingResponseFromTR()
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
      when(mockRoadLinkService.getRoadLinksHistoryFromVVH(any[Set[Long]])).thenReturn(Seq())
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
    val linkId = 12345L
    val roadwayNumber = 8000
    runWithRollback {
      //Creation of Test road
      val id = Sequences.nextRoadwayId
      val ra = Seq(Roadway(id, roadwayNumber, roadNumber, roadPartNumber, RoadType.Unknown, Track.Combined, Discontinuous, 0L, 1000L,
        reversed = false, DateTime.parse("1901-01-01"), None, "Tester", Option("test name"), 8L))
      val ll = LinearLocation(0L, 1, linkId, 0, 1000L, SideCode.TowardsDigitizing, 123456, (None, None),
        Seq(Point(535605.272, 6982204.22, 85.90899999999965)), LinkGeomSource.NormalLinkInterface, roadwayNumber)
      val rl = RoadLink(linkId, Seq(Point(0.0, 0.0), Point(0.0, 9.8)), 9.8, State, 1, TrafficDirection.BothDirections,
        Motorway, None, None, Map("MUNICIPALITYCODE" -> BigInt(167)))
      when(mockRoadLinkService.getRoadLinksHistoryFromVVH(any[Set[Long]])).thenReturn(Seq())
      when(mockRoadLinkService.getRoadLinksByLinkIdsFromVVH(any[Set[Long]])).thenReturn(Seq(rl))
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

  test("Test addNewLinksToProject When reserving part already used in other project Then return error message") {
    runWithRollback {
      val idr = Sequences.nextRoadwayId
      val id = Sequences.nextViitePrimaryKeySeqValue
      val rap = Project(id, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("2700-01-01"), "TestUser", DateTime.parse("1972-03-03"), DateTime.parse("2700-01-01"), "Some additional info", List.empty[ProjectReservedPart], Seq(), None)
      val projectLink = toProjectLink(rap, LinkStatus.New)(RoadAddress(idr, 123, 5, 207, RoadType.Unknown, Track.Combined, Discontinuous,
        0L, 10L, Some(DateTime.parse("1963-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), 12345L, 0.0, 9.8,
        SideCode.TowardsDigitizing, 0, (None, None), Seq(Point(0.0, 0.0), Point(0.0, 9.8)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      projectDAO.create(rap)
      projectService.saveProject(rap)


      val rap2 = Project(id + 1, ProjectState.apply(1), "TestProject2", "TestUser", DateTime.parse("2700-01-01"), "TestUser", DateTime.now(), DateTime.parse("2700-01-01"), "Some additional info", List.empty[ProjectReservedPart], Seq(), None)
      val projectLink2 = toProjectLink(rap2, LinkStatus.New)(RoadAddress(idr, 1234, 5, 999, RoadType.Unknown, Track.Combined, Discontinuous,
        0L, 10L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), 12345L, 0.0, 9.8,
        SideCode.TowardsDigitizing, 0, (None, None), Seq(Point(0.0, 0.0), Point(0.0, 9.8)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      projectDAO.create(rap2)
      projectService.saveProject(rap2)
      val roadwayN = 100000L
      addProjectLinksToProject(LinkStatus.Transfer, Seq(0L, 10L), discontinuity = Discontinuity.Continuous, lastLinkDiscontinuity = Discontinuity.EndOfRoad, project = rap2, roadNumber = 5L, roadPartNumber = 999L, roadwayNumber = roadwayN, withRoadInfo = true)

      val projectLink3 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idr, 12345, 5, 999, RoadType.Unknown, Track.Combined, Discontinuous,
        0L, 10L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), 12345L, 0.0, 9.8,
        SideCode.TowardsDigitizing, 0, (None, None),  Seq(Point(0.0, 0.0), Point(0.0, 9.8)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))

      val calibrationPoints = projectLink.toCalibrationPoints
      val p = ProjectAddressLink(idr, projectLink.linkId, projectLink.geometry,
        1, AdministrativeClass.apply(1), LinkType.apply(1), ConstructionType.apply(1), projectLink.linkGeomSource, RoadType.PublicUnderConstructionRoad, Some(""), None, 111, "Heinola", Some(""), Some("vvh_modified"),
        Map(), projectLink.roadNumber, projectLink.roadPartNumber, 2, -1, projectLink.discontinuity.value,
        projectLink.startAddrMValue, projectLink.endAddrMValue, projectLink.startMValue, projectLink.endMValue,
        projectLink.sideCode,
        calibrationPoints._1,
        calibrationPoints._2, Anomaly.None, projectLink.status, 12345, 123456)

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

  test("Test projectService.parsePreFillData When supplied a empty sequence of VVHRoadLinks Then return a error message.") {
    projectService.parsePreFillData(Seq.empty[VVHRoadlink]) should be(Left("Link could not be found in VVH"))
  }

  test("Test projectService.parsePreFillData When supplied a sequence of one valid VVHRoadLink Then return the correct pre-fill data for that link.") {
    runWithRollback {
      val attributes1 = Map("ROADNUMBER" -> BigInt(100), "ROADPARTNUMBER" -> BigInt(100))
      val newRoadLink1 = VVHRoadlink(1, 2, List(Point(0.0, 0.0), Point(20.0, 0.0)), AdministrativeClass.apply(1), TrafficDirection.BothDirections, FeatureClass.DrivePath, None, attributes1)
      projectService.parsePreFillData(Seq(newRoadLink1)) should be(Right(PreFillInfo(100, 100, "", RoadNameSource.UnknownSource)))
    }
  }

  test("Test projectService.parsePreFillData When supplied a sequence of one valid VVHRoadLink Then return the correct pre-fill data for that link and it's correct name.") {
    runWithRollback {
      sqlu"""INSERT INTO ROAD_NAME VALUES (ROAD_NAME_SEQ.nextval, 100, 'road name test', TIMESTAMP '2018-03-23 12:26:36.000000', null, TIMESTAMP '2018-03-23 12:26:36.000000', null, 'test user', TIMESTAMP '2018-03-23 12:26:36.000000')""".execute
      val attributes1 = Map("ROADNUMBER" -> BigInt(100), "ROADPARTNUMBER" -> BigInt(100))
      val newRoadLink1 = VVHRoadlink(1, 2, List(Point(0.0, 0.0), Point(20.0, 0.0)), AdministrativeClass.apply(1), TrafficDirection.BothDirections, FeatureClass.DrivePath, None, attributes1)
      projectService.parsePreFillData(Seq(newRoadLink1)) should be(Right(PreFillInfo(100, 100, "road name test", RoadNameSource.RoadAddressSource)))
    }
  }

  test("Test projectService.parsePrefillData() When getting road name data from the Project Link Name table Then return the  correct info with road name pre filled and the correct sources") {
    runWithRollback{

      val user = Some("user")

      val roadAddressProject = Project(0L, Sent2TR, "split", user.get, DateTime.now(), user.get,
        DateTime.now().plusMonths(2), DateTime.now(), "", Seq(), Seq(), None, None)

      val project = projectService.createRoadLinkProject(roadAddressProject)

      sqlu""" Insert into project_link_name values (VIITE_GENERAL_SEQ.nextval, ${project.id}, 100, 'TestRoadName_Project_Link')""".execute

      val attributes1 = Map("ROADNUMBER" -> BigInt(100), "ROADPARTNUMBER" -> BigInt(100))
      val newRoadLink1 = VVHRoadlink(1, 2, List(Point(0.0, 0.0), Point(20.0, 0.0)), AdministrativeClass.apply(1), TrafficDirection.BothDirections, FeatureClass.DrivePath, None, attributes1)
      projectService.parsePreFillData(Seq(newRoadLink1), project.id) should be(Right(PreFillInfo(100, 100, "TestRoadName_Project_Link", RoadNameSource.ProjectLinkSource)))
    }
  }

  test("Test projectService.parsePrefillData() When road name data exists both in Project Link Name table and Road_Name table Then return the correct info with road name pre filled and the correct sources, in this case RoadAddressSource") {
    runWithRollback{

      val user = Some("user")

      val roadAddressProject = Project(0L, Sent2TR, "split", user.get, DateTime.now(), user.get,
        DateTime.now().plusMonths(2), DateTime.now(), "", Seq(), Seq(), None, None)

      val project = projectService.createRoadLinkProject(roadAddressProject)

      sqlu"""INSERT INTO ROAD_NAME VALUES (ROAD_NAME_SEQ.nextval, 100, 'road name test', TIMESTAMP '2018-03-23 12:26:36.000000', null, TIMESTAMP '2018-03-23 12:26:36.000000', null, 'test user', TIMESTAMP '2018-03-23 12:26:36.000000')""".execute
      sqlu""" Insert into project_link_name values (VIITE_GENERAL_SEQ.nextval, ${project.id}, 100, 'TestRoadName_Project_Link')""".execute

      val attributes1 = Map("ROADNUMBER" -> BigInt(100), "ROADPARTNUMBER" -> BigInt(100))
      val newRoadLink1 = VVHRoadlink(1, 2, List(Point(0.0, 0.0), Point(20.0, 0.0)), AdministrativeClass.apply(1), TrafficDirection.BothDirections, FeatureClass.DrivePath, None, attributes1)
      projectService.parsePreFillData(Seq(newRoadLink1), project.id) should be(Right(PreFillInfo(100, 100, "road name test", RoadNameSource.RoadAddressSource)))
    }
  }

  test("Test projectService.parsePreFillData When supplied a sequence of one incomplete VVHRoadLink Then return an error message indicating that the information is incomplete.") {
    val attributes1 = Map("ROADNUMBER" -> BigInt(2))
    val newRoadLink1 = VVHRoadlink(1, 2, List(Point(0.0, 0.0), Point(20.0, 0.0)), AdministrativeClass.apply(1), TrafficDirection.BothDirections, FeatureClass.DrivePath, None, attributes1)
    projectService.parsePreFillData(Seq(newRoadLink1)) should be(Left("Link does not contain valid prefill info"))
  }

  test("Test createProjectLinks When project link is created for part not reserved Then should return error message") {
    runWithRollback {
      val rap = Project(0L, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("1901-01-01"),
        "TestUser", DateTime.parse("1901-01-01"), DateTime.now(), "Some additional info",
        Seq(), Seq(), None)
      val newLink = Seq(ProjectLink(-1000L, 5L, 206L, Track.apply(99), Discontinuity.Continuous, 0L, 50L, 0L, 50L, None, None,
        None, 12345L, 0.0, 43.1, SideCode.Unknown, (None, None),
        Seq(Point(468.5, 0.5), Point(512.0, 0.0)), 0L, LinkStatus.Unknown, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, 43.1, 49704009, 1000570, 8L, reversed = false, None, 123456L, 12345L))
      val project = projectService.createRoadLinkProject(rap)
      when(mockRoadLinkService.getRoadLinksByLinkIdsFromVVH(any[Set[Long]])).thenReturn(newLink.map(toRoadLink))
      val response = projectService.createProjectLinks(Seq(12345L), project.id, 5, 206, Track.Combined, Discontinuity.Continuous, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, 8L, "test", "road name")
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
      val addr1 = List(ProjectReservedPart(Sequences.nextViitePrimaryKeySeqValue, 5, 207, Some(0L), Some(Continuous),
        Some(8L), None, None, None, None))
      val addr2 = List(ProjectReservedPart(Sequences.nextViitePrimaryKeySeqValue, 5, 206, Some(5L), Some(Discontinuity.apply("jatkuva")),
        Some(8L), None, None, None, None))
      val project1 = projectService.createRoadLinkProject(rap1)

      mockForProject(project1.id, roadAddressServiceRealRoadwayAddressMapper.getRoadAddressWithRoadAndPart(5, 207).map(toProjectLink(project1)))
      projectService.saveProject(project1.copy(reservedParts = addr1))

      val project2 = projectService.createRoadLinkProject(rap2)
      mockForProject(project2.id, roadAddressServiceRealRoadwayAddressMapper.getRoadAddressWithRoadAndPart(5, 206).map(toProjectLink(project2)))
      projectService.saveProject(project2.copy(reservedParts = addr2))

      val response = projectService.updateProjectLinks(project1.id, Set(), projectLinkDAO.fetchProjectLinks(project1.id).map(_.linkId),
        LinkStatus.Numbering, "TestUser", 5, 206, 0, None,
        RoadType.PublicRoad.value, Discontinuity.Continuous.value, Some(8))
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
      val response = projectService.updateProjectLinks(project1.id, Set(), projectLinkDAO.fetchProjectLinks(project1.id).map(_.linkId), LinkStatus.Numbering, "TestUser", 5, 203, 0, None, RoadType.PublicRoad.value, Discontinuity.Continuous.value, Some(8))
      response.get should be("Antamasi tienumero ja tieosanumero ovat jo käytössä. Tarkista syöttämäsi tiedot.")
    }
  }

  test("Test projectService.updateProjectLinks When applying the operation \"Numerointi\" to a road part that already has some other action applied Then return an error message") {
    runWithRollback {
      val rap1 = Project(0L, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("1963-01-01"),
        "TestUser", DateTime.parse("1963-01-01"), DateTime.now(), "Some additional info",
        Seq(), Seq(), None)
      val addr1 = List(ProjectReservedPart(Sequences.nextViitePrimaryKeySeqValue, 5, 207, Some(0L), Some(Continuous),
        Some(8L), None, None, None, None))
      val project1 = projectService.createRoadLinkProject(rap1)
      mockForProject(project1.id, roadAddressServiceRealRoadwayAddressMapper.getRoadAddressWithRoadAndPart(5, 207).map(toProjectLink(project1)))
      projectService.saveProject(project1.copy(reservedParts = addr1))
      projectService.updateProjectLinks(project1.id, Set(), projectLinkDAO.fetchProjectLinks(project1.id).map(_.linkId),
        LinkStatus.Terminated, "TestUser", 5, 207, 0, None,
        RoadType.PublicRoad.value, Discontinuity.Continuous.value, Some(8))
      val response = projectService.updateProjectLinks(project1.id, Set(), projectLinkDAO.fetchProjectLinks(project1.id).map(_.linkId),
        LinkStatus.Numbering, "TestUser", 5, 308, 0, None,
        RoadType.PublicRoad.value, Discontinuity.Continuous.value, Some(8))
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
      projectService.updateProjectLinks(project1.id, Set(fetchedProjectLinks.maxBy(_.endAddrMValue).id), fetchedProjectLinks.map(_.linkId), LinkStatus.Numbering, "TestUser", 6, 207, 0, None, RoadType.PublicRoad.value, Discontinuity.EndOfRoad.value, Some(8))

      //Descending order by end address
      val projectLinks = projectLinkDAO.fetchProjectLinks(project1.id).sortBy(-_.endAddrMValue)
      projectLinks.tail.forall(_.discontinuity == Discontinuity.Continuous) should be(true)
      projectLinks.head.discontinuity should be(Discontinuity.EndOfRoad)
    }
  }

  test("Test revertLinks When new roads have name Then the revert should remove the road name") {
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
      when(mockRoadLinkService.getCurrentAndComplementaryRoadLinksFromVVH(any[Set[Long]])).thenReturn(roadLinks)
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
      sqlu"""INSERT INTO ROAD_NAME VALUES (ROAD_NAME_SEQ.NEXTVAL, 99999, 'test name', sysdate, null, sysdate, null, 'test user', sysdate)""".execute
      ProjectLinkNameDAO.get(99999L, project.id).get.roadName should be("new name")
      val linksToRevert = links.map(l => {
        LinkToRevert(l.id, l.linkId, l.status.value, l.geometry)
      })
      when(mockRoadLinkService.getCurrentAndComplementaryRoadLinksFromVVH(any[Set[Long]])).thenReturn(roadLinks)
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

      val pl1 = ProjectLink(-1000L, 9999L, 1L, Track.apply(0), Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None,
        None, 12345L, 0.0, 0.0, SideCode.Unknown, (None, None),
        Seq(Point(10.0, 10.0), Point(20.0, 10.0)), 0L, LinkStatus.New, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(Seq(Point(10.0, 10.0), Point(20.0, 10.0))), 0L, 0, 0, reversed = false,
        None, 86400L)
      val pl2 = ProjectLink(-1000L, 9999L, 1L, Track.apply(2), Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None,
        None, 12346L, 0.0, 0.0, SideCode.Unknown, (None, None),
        Seq(Point(20.0, 10.0), Point(30.0, 15.0)), 0L, LinkStatus.New, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(Seq(Point(20.0, 10.0), Point(30.0, 15.0))), 0L, 0, 0, reversed = false,
        None, 86400L)
      val pl3 = ProjectLink(-1000L, 9999L, 1L, Track.apply(2), Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None,
        None, 12347L, 0.0, 0.0, SideCode.Unknown, (None, None),
        Seq(Point(30.0, 15.0), Point(45.0, 10.0)), 0L, LinkStatus.New, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(Seq(Point(30.0, 15.0), Point(45.0, 10.0))), 0L, 0, 0, reversed = false,
        None, 86400L)
      val pl4 = ProjectLink(-1000L, 9999L, 1L, Track.apply(1), Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None,
        None, 12348L, 0.0, 0.0, SideCode.Unknown, (None, None),
        Seq(Point(20.0, 10.0), Point(25.0, 5.0), Point(45.0, 10.0)), 0L, LinkStatus.New, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(Seq(Point(20.0, 10.0), Point(25.0, 5.0), Point(45.0, 10.0))), 0L, 0, 0, reversed = false,
        None, 86400L)
      val pl5 = ProjectLink(-1000L, 9999L, 1L, Track.apply(0), Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None,
        None, 12349L, 0.0, 0.0, SideCode.Unknown, (None, None),
        Seq(Point(45.0, 10.0), Point(60.0, 10.0)), 0L, LinkStatus.New, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(Seq(Point(45.0, 10.0), Point(60.0, 10.0))), 0L, 0, 0, reversed = false,
        None, 86400L)

      val project = projectService.createRoadLinkProject(rap)
      when(mockRoadLinkService.getRoadLinksByLinkIdsFromVVH(any[Set[Long]])).thenReturn(Seq(pl1).map(toRoadLink))
      projectService.createProjectLinks(Seq(12345L), project.id, 9999, 1, Track.Combined, Discontinuity.Continuous, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, 8L, "test", "road name")
      when(mockRoadLinkService.getRoadLinksByLinkIdsFromVVH(any[Set[Long]])).thenReturn(Seq(pl4).map(toRoadLink))
      projectService.createProjectLinks(Seq(12348L), project.id, 9999, 1, Track.RightSide, Discontinuity.Continuous, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, 8L, "test", "road name")
      when(mockRoadLinkService.getRoadLinksByLinkIdsFromVVH(any[Set[Long]])).thenReturn(Seq(pl2, pl3).map(toRoadLink))
      projectService.createProjectLinks(Seq(12346L, 12347L), project.id, 9999, 1, Track.LeftSide, Discontinuity.Continuous, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, 8L, "test", "road name")
      when(mockRoadLinkService.getRoadLinksByLinkIdsFromVVH(any[Set[Long]])).thenReturn(Seq(pl5).map(toRoadLink))
      projectService.createProjectLinks(Seq(12349L), project.id, 9999, 1, Track.Combined, Discontinuity.Continuous, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, 8L, "test", "road name")
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

  test("Test projectService.updateProjectLinks() and projectService.changeDirection() When Re-Reversing direction of project links Then all links should revert to the previous fiven addressMValues.") {
    /**
      * This test checks:
      * 1.result of addressMValues for new given address value for one Track.Combined link
      * 2.result of reversing direction
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

      val pl1 = ProjectLink(-1000L, 9999L, 1L, Track.apply(0), Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None,
        None, 12345L, 0.0, 0.0, SideCode.Unknown, (None, None),
        Seq(Point(10.0, 10.0), Point(20.0, 10.0)), 0L, LinkStatus.New, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(Seq(Point(10.0, 10.0), Point(20.0, 10.0))), 0L, 0, 0, reversed = false,
        None, 86400L)
      val pl2 = ProjectLink(-1000L, 9999L, 1L, Track.apply(2), Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None,
        None, 12346L, 0.0, 0.0, SideCode.Unknown, (None, None),
        Seq(Point(20.0, 10.0), Point(30.0, 15.0)), 0L, LinkStatus.New, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(Seq(Point(20.0, 10.0), Point(30.0, 15.0))), 0L, 0, 0, reversed = false,
        None, 86400L)
      val pl3 = ProjectLink(-1000L, 9999L, 1L, Track.apply(2), Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None,
        None, 12347L, 0.0, 0.0, SideCode.Unknown, (None, None),
        Seq(Point(30.0, 15.0), Point(45.0, 10.0)), 0L, LinkStatus.New, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(Seq(Point(30.0, 15.0), Point(45.0, 10.0))), 0L, 0, 0, reversed = false,
        None, 86400L)
      val pl4 = ProjectLink(-1000L, 9999L, 1L, Track.apply(1), Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None,
        None, 12348L, 0.0, 0.0, SideCode.Unknown, (None, None),
        Seq(Point(20.0, 10.0), Point(25.0, 5.0), Point(45.0, 10.0)), 0L, LinkStatus.New, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(Seq(Point(20.0, 10.0), Point(25.0, 5.0), Point(45.0, 10.0))), 0L, 0, 0, reversed = false,
        None, 86400L)
      val pl5 = ProjectLink(-1000L, 9999L, 1L, Track.apply(0), Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None,
        None, 12349L, 0.0, 0.0, SideCode.Unknown, (None, None),
        Seq(Point(45.0, 10.0), Point(60.0, 10.0)), 0L, LinkStatus.New, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(Seq(Point(45.0, 10.0), Point(60.0, 10.0))), 0L, 0, 0, reversed = false,
        None, 86400L)

      val project = projectService.createRoadLinkProject(rap)
      when(mockRoadLinkService.getRoadLinksByLinkIdsFromVVH(any[Set[Long]])).thenReturn(Seq(pl1).map(toRoadLink))
      projectService.createProjectLinks(Seq(12345L), project.id, 9999, 1, Track.Combined, Discontinuity.Continuous, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, 8L, "test", "road name")
      when(mockRoadLinkService.getRoadLinksByLinkIdsFromVVH(any[Set[Long]])).thenReturn(Seq(pl4).map(toRoadLink))
      projectService.createProjectLinks(Seq(12348L), project.id, 9999, 1, Track.RightSide, Discontinuity.Continuous, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, 8L, "test", "road name")
      when(mockRoadLinkService.getRoadLinksByLinkIdsFromVVH(any[Set[Long]])).thenReturn(Seq(pl2, pl3).map(toRoadLink))
      projectService.createProjectLinks(Seq(12346L, 12347L), project.id, 9999, 1, Track.LeftSide, Discontinuity.Continuous, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, 8L, "test", "road name")
      when(mockRoadLinkService.getRoadLinksByLinkIdsFromVVH(any[Set[Long]])).thenReturn(Seq(pl5).map(toRoadLink))
      projectService.createProjectLinks(Seq(12349L), project.id, 9999, 1, Track.Combined, Discontinuity.Continuous, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, 8L, "test", "road name")
      val links = projectLinkDAO.fetchProjectLinks(project.id).sortBy(_.startAddrMValue)

      val linkidToIncrement = pl1.linkId
      val idsToIncrement = links.filter(_.linkId == linkidToIncrement).head.id
      val valueToIncrement = 2.0
      val newEndAddressValue = Seq(links.filter(_.linkId == linkidToIncrement).head.endAddrMValue.toInt, valueToIncrement.toInt).sum
      projectService.updateProjectLinks(project.id, Set(idsToIncrement), Seq(linkidToIncrement), LinkStatus.New, "TestUserTwo", 9999, 1, 0, Some(newEndAddressValue), 1L, 5) should be(None)
      val linksAfterGivenAddrMValue = projectLinkDAO.fetchProjectLinks(project.id)

      //only link and links after linkidToIncrement should be extended
      val extendedLink = links.filter(_.linkId == linkidToIncrement).head
      val linksBefore = links.filter(_.endAddrMValue >= extendedLink.endAddrMValue).sortBy(_.endAddrMValue)
      val linksAfter = linksAfterGivenAddrMValue.filter(_.endAddrMValue >= extendedLink.endAddrMValue).sortBy(_.endAddrMValue)
      linksBefore.zip(linksAfter).foreach { case (st, en) =>
        liesInBetween(en.endAddrMValue, (st.endAddrMValue + valueToIncrement - coeff, st.endAddrMValue + valueToIncrement + coeff))
      }

      projectService.changeDirection(project.id, 9999L, 1L, Seq(LinkToRevert(pl1.id, pl1.linkId, pl1.status.value, pl1.geometry)), ProjectCoordinates(0, 0, 0), "TestUserTwo")
      projectService.changeDirection(project.id, 9999L, 1L, Seq(LinkToRevert(pl1.id, pl1.linkId, pl1.status.value, pl1.geometry)), ProjectCoordinates(0, 0, 0), "TestUserTwo")
      val linksAfterReverse = projectLinkDAO.fetchProjectLinks(project.id).sortBy(_.startAddrMValue)

      links.sortBy(_.endAddrMValue).zip(linksAfterReverse.sortBy(_.endAddrMValue)).foreach { case (st, en) =>
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

      val pl1 = ProjectLink(-1000L, 9999L, 1L, Track.apply(0), Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None,
        None, 12345L, 0.0, 0.0, SideCode.Unknown, (None, None),
        Seq(Point(10.0, 10.0), Point(20.0, 10.0)), 0L, LinkStatus.New, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(Seq(Point(10.0, 10.0), Point(20.0, 10.0))), 0L, 0, 0, reversed = false,
        None, 86400L)
      val pl2 = ProjectLink(-1000L, 9999L, 1L, Track.apply(2), Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None,
        None, 12346L, 0.0, 0.0, SideCode.Unknown, (None, None),
        Seq(Point(20.0, 10.0), Point(30.0, 15.0)), 0L, LinkStatus.New, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(Seq(Point(20.0, 10.0), Point(30.0, 15.0))), 0L, 0, 0, reversed = false,
        None, 86400L)
      val pl3 = ProjectLink(-1000L, 9999L, 1L, Track.apply(2), Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None,
        None, 12347L, 0.0, 0.0, SideCode.Unknown, (None, None),
        Seq(Point(30.0, 15.0), Point(45.0, 10.0)), 0L, LinkStatus.New, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(Seq(Point(30.0, 15.0), Point(45.0, 10.0))), 0L, 0, 0, reversed = false,
        None, 86400L)
      val pl4 = ProjectLink(-1000L, 9999L, 1L, Track.apply(1), Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None,
        None, 12348L, 0.0, 0.0, SideCode.Unknown, (None, None),
        Seq(Point(20.0, 10.0), Point(25.0, 5.0), Point(45.0, 10.0)), 0L, LinkStatus.New, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(Seq(Point(20.0, 10.0), Point(25.0, 5.0), Point(45.0, 10.0))), 0L, 0, 0, reversed = false,
        None, 86400L)
      val pl5 = ProjectLink(-1000L, 9998L, 1L, Track.apply(0), Discontinuity.EndOfRoad, 0L, 0L, 0L, 0L, None, None,
        None, 12349L, 0.0, 0.0, SideCode.Unknown, (None, None),
        Seq(Point(45.0, 10.0), Point(60.0, 10.0)), 0L, LinkStatus.New, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(Seq(Point(45.0, 10.0), Point(60.0, 10.0))), 0L, 0, 0, reversed = false,
        None, 86400L)

      val project = projectService.createRoadLinkProject(rap)
      when(mockRoadLinkService.getRoadLinksByLinkIdsFromVVH(any[Set[Long]])).thenReturn(Seq(pl1).map(toRoadLink))
      projectService.createProjectLinks(Seq(12345L), project.id, 9999, 1, Track.Combined, Discontinuity.Continuous, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, 8L, "test", "road name")
      when(mockRoadLinkService.getRoadLinksByLinkIdsFromVVH(any[Set[Long]])).thenReturn(Seq(pl4).map(toRoadLink))
      projectService.createProjectLinks(Seq(12348L), project.id, 9999, 1, Track.RightSide, Discontinuity.Continuous, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, 8L, "test", "road name")
      when(mockRoadLinkService.getRoadLinksByLinkIdsFromVVH(any[Set[Long]])).thenReturn(Seq(pl2, pl3).map(toRoadLink))
      projectService.createProjectLinks(Seq(12346L, 12347L), project.id, 9999, 1, Track.LeftSide, Discontinuity.Continuous, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, 8L, "test", "road name")
      when(mockRoadLinkService.getRoadLinksByLinkIdsFromVVH(any[Set[Long]])).thenReturn(Seq(pl5).map(toRoadLink))
      projectService.createProjectLinks(Seq(12349L), project.id, 9998, 1, Track.Combined, Discontinuity.EndOfRoad, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, 8L, "test", "road name")
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

  test("Test createRoadLinkProject When project in writable state Then  Service should identify states (Incomplete, ErrorInViite and ErrorInTR)") {
    runWithRollback {
      val incomplete = Project(0L, ProjectState.apply(1), "I am Incomplete", "TestUser", DateTime.parse("1901-01-01"),
        "TestUser", DateTime.parse("1901-01-01"), DateTime.now(), "Some additional info",
        Seq(), Seq(), None)
      val incompleteProject = projectService.createRoadLinkProject(incomplete)
      val errorInViite = Project(0L, ProjectState.apply(1), "I am ErrorInViite", "TestUser", DateTime.parse("1901-01-01"),
        "TestUser", DateTime.parse("1901-01-01"), DateTime.now(), "Some additional info",
        Seq(), Seq(), None)
      val errorInViiteProject = projectService.createRoadLinkProject(errorInViite)
      val erroredInTR = Project(0L, ProjectState.apply(1), "I am ErroredInTR", "TestUser", DateTime.parse("1901-01-01"),
        "TestUser", DateTime.parse("1901-01-01"), DateTime.now(), "Some additional info",
        Seq(), Seq(), None)
      val erroredInTRProject = projectService.createRoadLinkProject(erroredInTR)
      projectService.isWritableState(incompleteProject.id) should be(true)
      projectService.isWritableState(errorInViiteProject.id) should be(true)
      projectService.isWritableState(erroredInTRProject.id) should be(true)
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

      sqlu"""INSERT INTO LINK (ID) VALUES (12345)""".execute
      sqlu"""INSERT INTO LINK (ID) VALUES (12346)""".execute
      sqlu"""INSERT INTO LINK (ID) VALUES (12347)""".execute
      sqlu"""INSERT INTO LINEAR_LOCATION (ID,ROADWAY_NUMBER,ORDER_NUMBER,LINK_ID,START_MEASURE,END_MEASURE,SIDE,GEOMETRY,VALID_FROM,VALID_TO,CREATED_BY,CREATED_TIME)
            VALUES(LINEAR_LOCATION_SEQ.nextval, 123, 1, 12345, 0, 9, 2, MDSYS.SDO_GEOMETRY(4002, 3067, NULL, MDSYS.SDO_ELEM_INFO_ARRAY(1, 2, 1), MDSYS.SDO_ORDINATE_ARRAY(5.0, 0.0, 0, 0, 5.0, 9.0, 0, 9)), TIMESTAMP '2015-12-30 00:00:00.000000', NULL, 'TR', TIMESTAMP '2015-12-30 00:00:00.000000')""".execute
      sqlu"""INSERT INTO LINEAR_LOCATION (ID,ROADWAY_NUMBER,ORDER_NUMBER,LINK_ID,START_MEASURE,END_MEASURE,SIDE,GEOMETRY,VALID_FROM,VALID_TO,CREATED_BY,CREATED_TIME)
            VALUES(LINEAR_LOCATION_SEQ.nextval, 123, 2, 12346, 0, 12, 2, MDSYS.SDO_GEOMETRY(4002, 3067, NULL, MDSYS.SDO_ELEM_INFO_ARRAY(1, 2, 1), MDSYS.SDO_ORDINATE_ARRAY(5.0, 9.0, 0, 9, 5.0, 21.0, 0, 21)), TIMESTAMP '2015-12-30 00:00:00.000000', NULL, 'TR', TIMESTAMP '2015-12-30 00:00:00.000000')""".execute
      sqlu"""INSERT INTO LINEAR_LOCATION (ID,ROADWAY_NUMBER,ORDER_NUMBER,LINK_ID,START_MEASURE,END_MEASURE,SIDE,GEOMETRY,VALID_FROM,VALID_TO,CREATED_BY,CREATED_TIME)
            VALUES(LINEAR_LOCATION_SEQ.nextval, 123, 3, 12347, 0, 5, 2, MDSYS.SDO_GEOMETRY(4002, 3067, NULL, MDSYS.SDO_ELEM_INFO_ARRAY(1, 2, 1), MDSYS.SDO_ORDINATE_ARRAY(5.0, 21.0, 0, 21, 5.0, 26.0, 0, 26)), TIMESTAMP '2015-12-30 00:00:00.000000', NULL, 'TR', TIMESTAMP '2015-12-30 00:00:00.000000')""".execute

      sqlu"""Insert into ROADWAY (ID,ROADWAY_NUMBER,ROAD_NUMBER,ROAD_PART_NUMBER,TRACK,START_ADDR_M,END_ADDR_M,REVERSED,DISCONTINUITY,START_DATE,END_DATE,CREATED_BY,CREATED_TIME,ROAD_TYPE,ELY,TERMINATED,VALID_FROM,VALID_TO)
        values (ROADWAY_SEQ.nextval, 123,9999,1,1,0,26,0,1,to_date('22-10-90','DD-MM-RR'),null,'TR',to_timestamp('21-09-18 12.04.42.970245000','DD-MM-RR HH24.MI.SSXFF','nls_numeric_characters=''. '''),1,8,0,to_date('16-10-98','DD-MM-RR'),null)""".execute



      // track2
      sqlu"""INSERT INTO LINK (ID) VALUES (12348)""".execute
      sqlu"""INSERT INTO LINK (ID) VALUES (12349)""".execute
      sqlu"""INSERT INTO LINK (ID) VALUES (12350)""".execute
      sqlu"""INSERT INTO LINK (ID) VALUES (12351)""".execute
      sqlu"""INSERT INTO LINEAR_LOCATION (ID,ROADWAY_NUMBER,ORDER_NUMBER,LINK_ID,START_MEASURE,END_MEASURE,SIDE,GEOMETRY,VALID_FROM,VALID_TO,CREATED_BY,CREATED_TIME)
            VALUES(LINEAR_LOCATION_SEQ.nextval, 124, 1, 12348, 0, 10, 2, MDSYS.SDO_GEOMETRY(4002, 3067, NULL, MDSYS.SDO_ELEM_INFO_ARRAY(1, 2, 1), MDSYS.SDO_ORDINATE_ARRAY(0.0, 0.0, 0, 0, 0.0, 10.0, 0, 10)), TIMESTAMP '2015-12-30 00:00:00.000000', NULL, 'TR', TIMESTAMP '2015-12-30 00:00:00.000000')""".execute
      sqlu"""INSERT INTO LINEAR_LOCATION (ID,ROADWAY_NUMBER,ORDER_NUMBER,LINK_ID,START_MEASURE,END_MEASURE,SIDE,GEOMETRY,VALID_FROM,VALID_TO,CREATED_BY,CREATED_TIME)
            VALUES(LINEAR_LOCATION_SEQ.nextval, 124, 2, 12349, 0, 8, 2, MDSYS.SDO_GEOMETRY(4002, 3067, NULL, MDSYS.SDO_ELEM_INFO_ARRAY(1, 2, 1), MDSYS.SDO_ORDINATE_ARRAY(0.0, 10.0, 0, 10, 0.0, 18.0, 0, 18)), TIMESTAMP '2015-12-30 00:00:00.000000', NULL, 'TR', TIMESTAMP '2015-12-30 00:00:00.000000')""".execute
      sqlu"""INSERT INTO LINEAR_LOCATION (ID,ROADWAY_NUMBER,ORDER_NUMBER,LINK_ID,START_MEASURE,END_MEASURE,SIDE,GEOMETRY,VALID_FROM,VALID_TO,CREATED_BY,CREATED_TIME)
            VALUES(LINEAR_LOCATION_SEQ.nextval, 124, 3, 12350, 0, 5, 2, MDSYS.SDO_GEOMETRY(4002, 3067, NULL, MDSYS.SDO_ELEM_INFO_ARRAY(1, 2, 1), MDSYS.SDO_ORDINATE_ARRAY(0.0, 18.0, 0, 18, 0.0, 23.0, 0, 23)), TIMESTAMP '2015-12-30 00:00:00.000000', NULL, 'TR', TIMESTAMP '2015-12-30 00:00:00.000000')""".execute
      sqlu"""INSERT INTO LINEAR_LOCATION (ID,ROADWAY_NUMBER,ORDER_NUMBER,LINK_ID,START_MEASURE,END_MEASURE,SIDE,GEOMETRY,VALID_FROM,VALID_TO,CREATED_BY,CREATED_TIME)
            VALUES(LINEAR_LOCATION_SEQ.nextval, 124, 4, 12351, 0, 3, 2, MDSYS.SDO_GEOMETRY(4002, 3067, NULL, MDSYS.SDO_ELEM_INFO_ARRAY(1, 2, 1), MDSYS.SDO_ORDINATE_ARRAY(0.0, 23.0, 0, 23, 0.0, 26.0, 0, 26)), TIMESTAMP '2015-12-30 00:00:00.000000', NULL, 'TR', TIMESTAMP '2015-12-30 00:00:00.000000')""".execute

      sqlu"""Insert into ROADWAY (ID,ROADWAY_NUMBER,ROAD_NUMBER,ROAD_PART_NUMBER,TRACK,START_ADDR_M,END_ADDR_M,REVERSED,DISCONTINUITY,START_DATE,END_DATE,CREATED_BY,CREATED_TIME,ROAD_TYPE,ELY,TERMINATED,VALID_FROM,VALID_TO)
        values (ROADWAY_SEQ.nextval, 124,9999,1,2,0,26,0,1,to_date('22-10-90','DD-MM-RR'),null,'TR',to_timestamp('21-09-18 12.04.42.970245000','DD-MM-RR HH24.MI.SSXFF','nls_numeric_characters=''. '''),1,8,0,to_date('16-10-98','DD-MM-RR'),null)""".execute

      // part2
      // track1
      sqlu"""INSERT INTO LINK (ID) VALUES (12352)""".execute
      sqlu"""INSERT INTO LINK (ID) VALUES (12353)""".execute
      sqlu"""INSERT INTO LINEAR_LOCATION (ID,ROADWAY_NUMBER,ORDER_NUMBER,LINK_ID,START_MEASURE,END_MEASURE,SIDE,GEOMETRY,VALID_FROM,VALID_TO,CREATED_BY,CREATED_TIME)
            VALUES(LINEAR_LOCATION_SEQ.nextval, 125, 1, 12352, 0, 2, 2, MDSYS.SDO_GEOMETRY(4002, 3067, NULL, MDSYS.SDO_ELEM_INFO_ARRAY(1, 2, 1), MDSYS.SDO_ORDINATE_ARRAY(5.0, 26.0, 0, 0, 5.0, 28.0, 0, 2)), TIMESTAMP '2015-12-30 00:00:00.000000', NULL, 'TR', TIMESTAMP '2015-12-30 00:00:00.000000')""".execute
      sqlu"""INSERT INTO LINEAR_LOCATION (ID,ROADWAY_NUMBER,ORDER_NUMBER,LINK_ID,START_MEASURE,END_MEASURE,SIDE,GEOMETRY,VALID_FROM,VALID_TO,CREATED_BY,CREATED_TIME)
            VALUES(LINEAR_LOCATION_SEQ.nextval, 125, 2, 12353, 0, 7, 2, MDSYS.SDO_GEOMETRY(4002, 3067, NULL, MDSYS.SDO_ELEM_INFO_ARRAY(1, 2, 1), MDSYS.SDO_ORDINATE_ARRAY(5.0, 28.0, 0, 2, 5.0, 35.0, 0, 7)), TIMESTAMP '2015-12-30 00:00:00.000000', NULL, 'TR', TIMESTAMP '2015-12-30 00:00:00.000000')""".execute

      sqlu"""Insert into ROADWAY (ID,ROADWAY_NUMBER,ROAD_NUMBER,ROAD_PART_NUMBER,TRACK,START_ADDR_M,END_ADDR_M,REVERSED,DISCONTINUITY,START_DATE,END_DATE,CREATED_BY,CREATED_TIME,ROAD_TYPE,ELY,TERMINATED,VALID_FROM,VALID_TO)
        values (ROADWAY_SEQ.nextval, 125,9999,2,1,0,7,0,1,to_date('22-10-90','DD-MM-RR'),null,'TR',to_timestamp('21-09-18 12.04.42.970245000','DD-MM-RR HH24.MI.SSXFF','nls_numeric_characters=''. '''),1,8,0,to_date('16-10-98','DD-MM-RR'),null)""".execute

      // track2
      sqlu"""INSERT INTO LINK (ID) VALUES (12354)""".execute
      sqlu"""INSERT INTO LINK (ID) VALUES (12355)""".execute
      sqlu"""INSERT INTO LINEAR_LOCATION (ID,ROADWAY_NUMBER,ORDER_NUMBER,LINK_ID,START_MEASURE,END_MEASURE,SIDE,GEOMETRY,VALID_FROM,VALID_TO,CREATED_BY,CREATED_TIME)
            VALUES(LINEAR_LOCATION_SEQ.nextval, 126, 1, 12354, 0, 3, 2, MDSYS.SDO_GEOMETRY(4002, 3067, NULL, MDSYS.SDO_ELEM_INFO_ARRAY(1, 2, 1), MDSYS.SDO_ORDINATE_ARRAY(0.0, 26.0, 0, 0, 0.0, 29.0, 0, 3)), TIMESTAMP '2015-12-30 00:00:00.000000', NULL, 'TR', TIMESTAMP '2015-12-30 00:00:00.000000')""".execute
      sqlu"""INSERT INTO LINEAR_LOCATION (ID,ROADWAY_NUMBER,ORDER_NUMBER,LINK_ID,START_MEASURE,END_MEASURE,SIDE,GEOMETRY,VALID_FROM,VALID_TO,CREATED_BY,CREATED_TIME)
            VALUES(LINEAR_LOCATION_SEQ.nextval, 126, 2, 12355, 0, 8, 2, MDSYS.SDO_GEOMETRY(4002, 3067, NULL, MDSYS.SDO_ELEM_INFO_ARRAY(1, 2, 1), MDSYS.SDO_ORDINATE_ARRAY(0.0, 29.0, 0, 3, 0.0, 37.0, 0, 11)), TIMESTAMP '2015-12-30 00:00:00.000000', NULL, 'TR', TIMESTAMP '2015-12-30 00:00:00.000000')""".execute

      sqlu"""Insert into ROADWAY (ID,ROADWAY_NUMBER,ROAD_NUMBER,ROAD_PART_NUMBER,TRACK,START_ADDR_M,END_ADDR_M,REVERSED,DISCONTINUITY,START_DATE,END_DATE,CREATED_BY,CREATED_TIME,ROAD_TYPE,ELY,TERMINATED,VALID_FROM,VALID_TO)
        values (ROADWAY_SEQ.nextval, 126,9999,2,2,0,11,0,1,to_date('22-10-90','DD-MM-RR'),null,'TR',to_timestamp('21-09-18 12.04.42.970245000','DD-MM-RR HH24.MI.SSXFF','nls_numeric_characters=''. '''),1,8,0,to_date('16-10-98','DD-MM-RR'),null)""".execute

      val project = projectService.createRoadLinkProject(rap)
      val id = project.id
      val part1 = roadwayAddressMapper.getRoadAddressesByLinearLocation(linearLocationDAO.fetchByRoadways(roadwayDAO.fetchAllBySection(9999, 1).map(_.roadwayNumber).toSet))
      val part2 = roadwayAddressMapper.getRoadAddressesByLinearLocation(linearLocationDAO.fetchByRoadways(roadwayDAO.fetchAllBySection(9999, 2).map(_.roadwayNumber).toSet))
      val toProjectLinks = (part1 ++ part2).map(toProjectLink(rap))
      val roadLinks = toProjectLinks.map(toRoadLink)

      when(mockRoadLinkService.getRoadLinksHistoryFromVVH(any[Set[Long]])).thenReturn(Seq())
      when(mockRoadLinkService.getRoadLinksByLinkIdsFromVVH(any[Set[Long]])).thenReturn(roadLinks)
      projectService.saveProject(project.copy(reservedParts = Seq(ProjectReservedPart(0L, 9999, 1, null, Some(Continuous), Some(8L), None, None, None, None), ProjectReservedPart(0L, 9999, 2, null, Some(Continuous), Some(8L), None, None, None, None))))

      val projectLinks = projectLinkDAO.fetchProjectLinks(id)
      val part1track1 = Set(12345L, 12346L, 12347L)
      val part1track2 = Set(12348L, 12349L, 12350L, 12351L)
      val part1track1Links = projectLinks.filter(pl => part1track1.contains(pl.linkId)).map(_.id).toSet
      val part1Track2Links = projectLinks.filter(pl => part1track2.contains(pl.linkId)).map(_.id).toSet

      when(mockRoadLinkService.getRoadLinksByLinkIdsFromVVH(any[Set[Long]])).thenReturn(projectLinks.filter(pl => part1track1Links.contains(pl.linkId)).map(toRoadLink))
      projectLinkDAO.updateProjectLinksStatus(part1track1Links, LinkStatus.UnChanged, "test")
      when(mockRoadLinkService.getRoadLinksByLinkIdsFromVVH(any[Set[Long]])).thenReturn(projectLinks.filter(pl => part1Track2Links.contains(pl.linkId)).map(toRoadLink))
      projectLinkDAO.updateProjectLinksStatus(part1Track2Links, LinkStatus.UnChanged, "test")

      /**
        * Tranfering adjacents of part1 to part2
        */
      val part1AdjacentToPart2IdRightSide = Set(12347L)
      val part1AdjacentToPart2IdLeftSide = Set(12351L)
      val part1AdjacentToPart2LinkRightSide = projectLinks.filter(pl => part1AdjacentToPart2IdRightSide.contains(pl.linkId)).map(_.id).toSet
      val part1AdjacentToPart2LinkLeftSide = projectLinks.filter(pl => part1AdjacentToPart2IdLeftSide.contains(pl.linkId)).map(_.id).toSet

      when(mockRoadLinkService.getRoadLinksByLinkIdsFromVVH(any[Set[Long]])).thenReturn(projectLinks.filter(pl => part1AdjacentToPart2IdRightSide.contains(pl.linkId)).map(toRoadLink))
      projectService.updateProjectLinks(id, part1AdjacentToPart2LinkRightSide, Seq(), LinkStatus.Transfer, "test",
        9999, 2, 1, None, 1, 5, Some(1L), reversed = false, None)
      when(mockRoadLinkService.getRoadLinksByLinkIdsFromVVH(any[Set[Long]])).thenReturn(projectLinks.filter(pl => part1Track2Links.contains(pl.linkId)).map(toRoadLink))
      projectService.updateProjectLinks(id, part1AdjacentToPart2LinkLeftSide, Seq(), LinkStatus.Transfer, "test",
        9999, 2, 2, None, 1, 5, Some(1L), reversed = false, None)

      val part2track1 = Set(12352L, 12353L)
      val part2track2 = Set(12354L, 12355L)
      val part2track1Links = projectLinks.filter(pl => part2track1.contains(pl.linkId)).map(_.id).toSet
      val part2Track2Links = projectLinks.filter(pl => part2track2.contains(pl.linkId)).map(_.id).toSet
      when(mockRoadLinkService.getRoadLinksByLinkIdsFromVVH(any[Set[Long]])).thenReturn(projectLinks.filter(pl => part2track1Links.contains(pl.linkId)).map(toRoadLink))
      projectService.updateProjectLinks(id, part2track1Links, Seq(), LinkStatus.Transfer, "test",
        9999, 2, 1, None, 1, 5, Some(1L), reversed = false, None)
      when(mockRoadLinkService.getRoadLinksByLinkIdsFromVVH(any[Set[Long]])).thenReturn(projectLinks.filter(pl => part2Track2Links.contains(pl.linkId)).map(toRoadLink))
      projectService.updateProjectLinks(id, part2Track2Links, Seq(), LinkStatus.Transfer, "test",
        9999, 2, 2, None, 1, 5, Some(1L), reversed = false, None)

      val projectLinks2 = projectLinkDAO.fetchProjectLinks(id)

      val parts = projectLinks2.partition(_.roadPartNumber === 1)
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
      sqlu"""INSERT INTO LINK (ID) VALUES (12345)""".execute
      sqlu"""INSERT INTO LINK (ID) VALUES (12346)""".execute
      sqlu"""INSERT INTO LINK (ID) VALUES (12347)""".execute
      sqlu"""INSERT INTO LINEAR_LOCATION (ID,ROADWAY_NUMBER,ORDER_NUMBER,LINK_ID,START_MEASURE,END_MEASURE,SIDE,GEOMETRY,VALID_FROM,VALID_TO,CREATED_BY,CREATED_TIME)
            VALUES(LINEAR_LOCATION_SEQ.nextval, 1234567, 1, 12345, 0, 9, 2, MDSYS.SDO_GEOMETRY(4002, 3067, NULL, MDSYS.SDO_ELEM_INFO_ARRAY(1, 2, 1), MDSYS.SDO_ORDINATE_ARRAY(5.0, 0.0, 0, 0, 5.0, 9.0, 0, 9)), TIMESTAMP '2015-12-30 00:00:00.000000', NULL, 'TR', TIMESTAMP '2015-12-30 00:00:00.000000')""".execute
      sqlu"""INSERT INTO LINEAR_LOCATION (ID,ROADWAY_NUMBER,ORDER_NUMBER,LINK_ID,START_MEASURE,END_MEASURE,SIDE,GEOMETRY,VALID_FROM,VALID_TO,CREATED_BY,CREATED_TIME)
            VALUES(LINEAR_LOCATION_SEQ.nextval, 1234567, 2, 12346, 0, 12, 2, MDSYS.SDO_GEOMETRY(4002, 3067, NULL, MDSYS.SDO_ELEM_INFO_ARRAY(1, 2, 1), MDSYS.SDO_ORDINATE_ARRAY(5.0, 9.0, 0, 9, 5.0, 21.0, 0, 21)), TIMESTAMP '2015-12-30 00:00:00.000000', NULL, 'TR', TIMESTAMP '2015-12-30 00:00:00.000000')""".execute
      sqlu"""INSERT INTO LINEAR_LOCATION (ID,ROADWAY_NUMBER,ORDER_NUMBER,LINK_ID,START_MEASURE,END_MEASURE,SIDE,GEOMETRY,VALID_FROM,VALID_TO,CREATED_BY,CREATED_TIME)
            VALUES(LINEAR_LOCATION_SEQ.nextval, 1234567, 3, 12347, 0, 5, 2, MDSYS.SDO_GEOMETRY(4002, 3067, NULL, MDSYS.SDO_ELEM_INFO_ARRAY(1, 2, 1), MDSYS.SDO_ORDINATE_ARRAY(5.0, 21.0, 0, 21, 5.0, 26.0, 0, 26)), TIMESTAMP '2015-12-30 00:00:00.000000', NULL, 'TR', TIMESTAMP '2015-12-30 00:00:00.000000')""".execute

      sqlu"""Insert into ROADWAY (ID,ROADWAY_NUMBER,ROAD_NUMBER,ROAD_PART_NUMBER,TRACK,START_ADDR_M,END_ADDR_M,REVERSED,DISCONTINUITY,START_DATE,END_DATE,CREATED_BY,CREATED_TIME,ROAD_TYPE,ELY,TERMINATED,VALID_FROM,VALID_TO)
        values (ROADWAY_SEQ.nextval, 1234567,9999,1,1,0,26,0,1,to_date('22-10-90','DD-MM-RR'),null,'TR',to_timestamp('21-09-18 12.04.42.970245000','DD-MM-RR HH24.MI.SSXFF','nls_numeric_characters=''. '''),1,8,0,to_date('16-10-98','DD-MM-RR'),null)""".execute



      // track2
      sqlu"""INSERT INTO LINK (ID) VALUES (12348)""".execute
      sqlu"""INSERT INTO LINK (ID) VALUES (12349)""".execute
      sqlu"""INSERT INTO LINK (ID) VALUES (12350)""".execute
      sqlu"""INSERT INTO LINK (ID) VALUES (12351)""".execute
      sqlu"""INSERT INTO LINEAR_LOCATION (ID,ROADWAY_NUMBER,ORDER_NUMBER,LINK_ID,START_MEASURE,END_MEASURE,SIDE,GEOMETRY,VALID_FROM,VALID_TO,CREATED_BY,CREATED_TIME)
            VALUES(LINEAR_LOCATION_SEQ.nextval, 1234568, 1, 12348, 0, 10, 2, MDSYS.SDO_GEOMETRY(4002, 3067, NULL, MDSYS.SDO_ELEM_INFO_ARRAY(1, 2, 1), MDSYS.SDO_ORDINATE_ARRAY(0.0, 0.0, 0, 0, 0.0, 10.0, 0, 10)), TIMESTAMP '2015-12-30 00:00:00.000000', NULL, 'TR', TIMESTAMP '2015-12-30 00:00:00.000000')""".execute
      sqlu"""INSERT INTO LINEAR_LOCATION (ID,ROADWAY_NUMBER,ORDER_NUMBER,LINK_ID,START_MEASURE,END_MEASURE,SIDE,GEOMETRY,VALID_FROM,VALID_TO,CREATED_BY,CREATED_TIME)
            VALUES(LINEAR_LOCATION_SEQ.nextval, 1234568, 2, 12349, 0, 8, 2, MDSYS.SDO_GEOMETRY(4002, 3067, NULL, MDSYS.SDO_ELEM_INFO_ARRAY(1, 2, 1), MDSYS.SDO_ORDINATE_ARRAY(0.0, 10.0, 0, 10, 0.0, 18.0, 0, 18)), TIMESTAMP '2015-12-30 00:00:00.000000', NULL, 'TR', TIMESTAMP '2015-12-30 00:00:00.000000')""".execute
      sqlu"""INSERT INTO LINEAR_LOCATION (ID,ROADWAY_NUMBER,ORDER_NUMBER,LINK_ID,START_MEASURE,END_MEASURE,SIDE,GEOMETRY,VALID_FROM,VALID_TO,CREATED_BY,CREATED_TIME)
            VALUES(LINEAR_LOCATION_SEQ.nextval, 1234568, 3, 12350, 0, 5, 2, MDSYS.SDO_GEOMETRY(4002, 3067, NULL, MDSYS.SDO_ELEM_INFO_ARRAY(1, 2, 1), MDSYS.SDO_ORDINATE_ARRAY(0.0, 18.0, 0, 18, 0.0, 23.0, 0, 23)), TIMESTAMP '2015-12-30 00:00:00.000000', NULL, 'TR', TIMESTAMP '2015-12-30 00:00:00.000000')""".execute
      sqlu"""INSERT INTO LINEAR_LOCATION (ID,ROADWAY_NUMBER,ORDER_NUMBER,LINK_ID,START_MEASURE,END_MEASURE,SIDE,GEOMETRY,VALID_FROM,VALID_TO,CREATED_BY,CREATED_TIME)
            VALUES(LINEAR_LOCATION_SEQ.nextval, 1234568, 4, 12351, 0, 3, 2, MDSYS.SDO_GEOMETRY(4002, 3067, NULL, MDSYS.SDO_ELEM_INFO_ARRAY(1, 2, 1), MDSYS.SDO_ORDINATE_ARRAY(0.0, 23.0, 0, 23, 0.0, 26.0, 0, 26)), TIMESTAMP '2015-12-30 00:00:00.000000', NULL, 'TR', TIMESTAMP '2015-12-30 00:00:00.000000')""".execute

      sqlu"""Insert into ROADWAY (ID,ROADWAY_NUMBER,ROAD_NUMBER,ROAD_PART_NUMBER,TRACK,START_ADDR_M,END_ADDR_M,REVERSED,DISCONTINUITY,START_DATE,END_DATE,CREATED_BY,CREATED_TIME,ROAD_TYPE,ELY,TERMINATED,VALID_FROM,VALID_TO)
        values (ROADWAY_SEQ.nextval, 1234568,9999,1,2,0,26,0,1,to_date('22-10-90','DD-MM-RR'),null,'TR',to_timestamp('21-09-18 12.04.42.970245000','DD-MM-RR HH24.MI.SSXFF','nls_numeric_characters=''. '''),1,8,0,to_date('16-10-98','DD-MM-RR'),null)""".execute

      // part2
      // track1
      sqlu"""INSERT INTO LINK (ID) VALUES (12352)""".execute
      sqlu"""INSERT INTO LINK (ID) VALUES (12353)""".execute
      sqlu"""INSERT INTO LINEAR_LOCATION (ID,ROADWAY_NUMBER,ORDER_NUMBER,LINK_ID,START_MEASURE,END_MEASURE,SIDE,GEOMETRY,VALID_FROM,VALID_TO,CREATED_BY,CREATED_TIME)
            VALUES(LINEAR_LOCATION_SEQ.nextval, 1234569, 1, 12352, 0, 2, 2,MDSYS.SDO_GEOMETRY(4002, 3067, NULL, MDSYS.SDO_ELEM_INFO_ARRAY(1, 2, 1), MDSYS.SDO_ORDINATE_ARRAY(5.0, 26.0, 0, 0, 5.0, 28.0, 0, 2)), TIMESTAMP '2015-12-30 00:00:00.000000', NULL, 'TR', TIMESTAMP '2015-12-30 00:00:00.000000')""".execute
      sqlu"""INSERT INTO LINEAR_LOCATION (ID,ROADWAY_NUMBER,ORDER_NUMBER,LINK_ID,START_MEASURE,END_MEASURE,SIDE,GEOMETRY,VALID_FROM,VALID_TO,CREATED_BY,CREATED_TIME)
            VALUES(LINEAR_LOCATION_SEQ.nextval, 1234569, 2, 12353, 0, 7, 2, MDSYS.SDO_GEOMETRY(4002, 3067, NULL, MDSYS.SDO_ELEM_INFO_ARRAY(1, 2, 1), MDSYS.SDO_ORDINATE_ARRAY(5.0, 28.0, 0, 2, 5.0, 35.0, 0, 7)), TIMESTAMP '2015-12-30 00:00:00.000000', NULL, 'TR', TIMESTAMP '2015-12-30 00:00:00.000000')""".execute

      sqlu"""Insert into ROADWAY (ID,ROADWAY_NUMBER,ROAD_NUMBER,ROAD_PART_NUMBER,TRACK,START_ADDR_M,END_ADDR_M,REVERSED,DISCONTINUITY,START_DATE,END_DATE,CREATED_BY,CREATED_TIME,ROAD_TYPE,ELY,TERMINATED,VALID_FROM,VALID_TO)
        values (ROADWAY_SEQ.nextval, 1234569,9999,2,1,0,7,0,1,to_date('22-10-90','DD-MM-RR'),null,'TR',to_timestamp('21-09-18 12.04.42.970245000','DD-MM-RR HH24.MI.SSXFF','nls_numeric_characters=''. '''),1,8,0,to_date('16-10-98','DD-MM-RR'),null)""".execute

      // track2
      sqlu"""INSERT INTO LINK (ID) VALUES (12354)""".execute
      sqlu"""INSERT INTO LINK (ID) VALUES (12355)""".execute
      sqlu"""INSERT INTO LINEAR_LOCATION (ID,ROADWAY_NUMBER,ORDER_NUMBER,LINK_ID,START_MEASURE,END_MEASURE,SIDE,GEOMETRY,VALID_FROM,VALID_TO,CREATED_BY,CREATED_TIME)
            VALUES(LINEAR_LOCATION_SEQ.nextval, 1234570, 1, 12354, 0, 3, 2, MDSYS.SDO_GEOMETRY(4002, 3067, NULL, MDSYS.SDO_ELEM_INFO_ARRAY(1, 2, 1), MDSYS.SDO_ORDINATE_ARRAY(0.0, 26.0, 0, 0, 0.0, 29.0, 0, 3)), TIMESTAMP '2015-12-30 00:00:00.000000', NULL, 'TR', TIMESTAMP '2015-12-30 00:00:00.000000')""".execute
      sqlu"""INSERT INTO LINEAR_LOCATION (ID,ROADWAY_NUMBER,ORDER_NUMBER,LINK_ID,START_MEASURE,END_MEASURE,SIDE,GEOMETRY,VALID_FROM,VALID_TO,CREATED_BY,CREATED_TIME)
            VALUES(LINEAR_LOCATION_SEQ.nextval, 1234570, 2, 12355, 0, 8, 2, MDSYS.SDO_GEOMETRY(4002, 3067, NULL, MDSYS.SDO_ELEM_INFO_ARRAY(1, 2, 1), MDSYS.SDO_ORDINATE_ARRAY(0.0, 29.0, 0, 3, 0.0, 37.0, 0, 11)), TIMESTAMP '2015-12-30 00:00:00.000000', NULL, 'TR', TIMESTAMP '2015-12-30 00:00:00.000000')""".execute

      sqlu"""Insert into ROADWAY (ID,ROADWAY_NUMBER,ROAD_NUMBER,ROAD_PART_NUMBER,TRACK,START_ADDR_M,END_ADDR_M,REVERSED,DISCONTINUITY,START_DATE,END_DATE,CREATED_BY,CREATED_TIME,ROAD_TYPE,ELY,TERMINATED,VALID_FROM,VALID_TO)
        values (ROADWAY_SEQ.nextval, 1234570,9999,2,2,0,11,0,1,to_date('22-10-90','DD-MM-RR'),null,'TR',to_timestamp('21-09-18 12.04.42.970245000','DD-MM-RR HH24.MI.SSXFF','nls_numeric_characters=''. '''),1,8,0,to_date('16-10-98','DD-MM-RR'),null)""".execute

      val project = projectService.createRoadLinkProject(rap)
      val id = project.id
      val part1 = roadwayAddressMapper.getRoadAddressesByLinearLocation(linearLocationDAO.fetchByRoadways(roadwayDAO.fetchAllBySection(9999, 1).map(_.roadwayNumber).toSet))
      val part2 = roadwayAddressMapper.getRoadAddressesByLinearLocation(linearLocationDAO.fetchByRoadways(roadwayDAO.fetchAllBySection(9999, 2).map(_.roadwayNumber).toSet))
      val toProjectLinks = (part1 ++ part2).map(toProjectLink(rap))
      val roadLinks = toProjectLinks.map(toRoadLink)
      when(mockRoadLinkService.getRoadLinksHistoryFromVVH(any[Set[Long]])).thenReturn(Seq())
      when(mockRoadLinkService.getRoadLinksByLinkIdsFromVVH(any[Set[Long]])).thenReturn(roadLinks)
      projectService.saveProject(project.copy(reservedParts = Seq(ProjectReservedPart(0L, 9999, 1, null, Some(Continuous), Some(8L), None, None, None, None), ProjectReservedPart(0L, 9999, 2, null, Some(Continuous), Some(8L), None, None, None, None))))

      val projectLinks = projectLinkDAO.fetchProjectLinks(id)
      val part1track1 = Set(12345L, 12346L, 12347L)
      val part1track2 = Set(12348L, 12349L, 12350L, 12351L)
      val part1track1Links = projectLinks.filter(pl => part1track1.contains(pl.linkId)).map(_.id).toSet
      val part1Track2Links = projectLinks.filter(pl => part1track2.contains(pl.linkId)).map(_.id).toSet

      when(mockRoadLinkService.getRoadLinksByLinkIdsFromVVH(any[Set[Long]])).thenReturn(projectLinks.filter(pl => part1track1Links.contains(pl.linkId)).map(toRoadLink))
      projectLinkDAO.updateProjectLinksStatus(part1track1Links, LinkStatus.UnChanged, "test")
      when(mockRoadLinkService.getRoadLinksByLinkIdsFromVVH(any[Set[Long]])).thenReturn(projectLinks.filter(pl => part1Track2Links.contains(pl.linkId)).map(toRoadLink))
      projectLinkDAO.updateProjectLinksStatus(part1Track2Links, LinkStatus.UnChanged, "test")

      val part2track1 = Set(12352L, 12353L)
      val part2track2 = Set(12354L, 12355L)
      val part2track1Links = projectLinks.filter(pl => part2track1.contains(pl.linkId)).map(_.id).toSet
      val part2Track2Links = projectLinks.filter(pl => part2track2.contains(pl.linkId)).map(_.id).toSet

      when(mockRoadLinkService.getRoadLinksByLinkIdsFromVVH(any[Set[Long]])).thenReturn(projectLinks.filter(pl => part2track1Links.contains(pl.linkId)).map(toRoadLink))
      projectService.updateProjectLinks(id, part2track1Links, Seq(), LinkStatus.Transfer, "test",
        newRoadNumber = 9999, newRoadPartNumber = 2, newTrackCode = 1, userDefinedEndAddressM = None, roadType = 1, discontinuity = 5, ely = Some(1L), roadName = None)
      when(mockRoadLinkService.getRoadLinksByLinkIdsFromVVH(any[Set[Long]])).thenReturn(projectLinks.filter(pl => part2Track2Links.contains(pl.linkId)).map(toRoadLink))
      projectService.updateProjectLinks(id, part2Track2Links, Seq(), LinkStatus.Transfer, "test",
        newRoadNumber = 9999, newRoadPartNumber = 2, newTrackCode = 2, userDefinedEndAddressM = None, roadType = 1, discontinuity = 5, ely = Some(1L), roadName = None)
      /**
        * Tranfering adjacents of part1 to part2
        */
      val part1AdjacentToPart2IdRightSide = Set(12347L)
      val part1AdjacentToPart2IdLeftSide = Set(12351L)
      val part1AdjacentToPart2LinkRightSide = projectLinks.filter(pl => part1AdjacentToPart2IdRightSide.contains(pl.linkId)).map(_.id).toSet
      val part1AdjacentToPart2LinkLeftSide = projectLinks.filter(pl => part1AdjacentToPart2IdLeftSide.contains(pl.linkId)).map(_.id).toSet


      when(mockRoadLinkService.getRoadLinksByLinkIdsFromVVH(any[Set[Long]])).thenReturn(projectLinks.filter(pl => part1AdjacentToPart2IdRightSide.contains(pl.linkId)).map(toRoadLink))
      projectService.updateProjectLinks(id, part1AdjacentToPart2LinkRightSide, Seq(), LinkStatus.Transfer, "test",
        newRoadNumber = 9999, newRoadPartNumber = 2, newTrackCode = 1, userDefinedEndAddressM = None, roadType = 1, discontinuity = 5, ely = Some(1L), roadName = None)
      when(mockRoadLinkService.getRoadLinksByLinkIdsFromVVH(any[Set[Long]])).thenReturn(projectLinks.filter(pl => part1Track2Links.contains(pl.linkId)).map(toRoadLink))
      projectService.updateProjectLinks(id, part1AdjacentToPart2LinkLeftSide, Seq(), LinkStatus.Transfer, "test",
        newRoadNumber = 9999, newRoadPartNumber = 2, newTrackCode = 2, userDefinedEndAddressM = None, roadType = 1, discontinuity = 5, ely = Some(1L), roadName = None)

      val projectLinks2 = projectLinkDAO.fetchProjectLinks(id)

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
      val linkId = 10000
      val oldEndAddress = 100
      val roadway = Roadway(Sequences.nextRoadwayId, Sequences.nextRoadwayNumber, 9999, 1, RoadType.PublicRoad,
        Track.Combined, Discontinuity.Continuous, 0, oldEndAddress, reversed = false, DateTime.now().minusYears(10), None, "test",
        Some("Test Road"), 1, TerminationCode.NoTermination, DateTime.now().minusYears(10), None)

      roadwayDAO.create(Seq(roadway))

      // Create project link
      val roadAddressLength = roadway.endAddrMValue - roadway.startAddrMValue
      val projectId = Sequences.nextProjectId
      val newLength = 110.123
      val newEndAddr = 110
      val projectLink = ProjectLink(Sequences.nextViitePrimaryKeySeqValue, roadway.roadNumber, roadway.roadPartNumber,
        roadway.track, roadway.discontinuity, roadway.startAddrMValue, roadway.endAddrMValue + 10,
        roadway.startAddrMValue, roadway.endAddrMValue, Some(DateTime.now().plusMonths(1)), None, Some("test"),
        linkId, 0.0, newLength, SideCode.TowardsDigitizing,
        (Some(ProjectLinkCalibrationPoint(linkId, 0, 0, CalibrationPointSource.RoadAddressSource)),
          Some(ProjectLinkCalibrationPoint(linkId, newLength, newEndAddr, CalibrationPointSource.RoadAddressSource))),
        Seq(Point(0.0, 0.0), Point(0.0, newLength)), projectId, LinkStatus.Numbering,
        roadway.roadType, LinkGeomSource.NormalLinkInterface, newLength, roadway.id, 1234, roadway.ely, reversed = false, None,
        DateTime.now().minusMonths(10).getMillis, roadway.roadwayNumber, roadway.roadName, Some(roadAddressLength),
        Some(0), Some(newEndAddr), Some(roadway.track), Some(roadway.roadNumber), Some(roadway.roadPartNumber)
      )

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

  test("Test expireHistoryRows When expiring one roadway by id Then it should be expired by validTo date") {
    runWithRollback {

      val roadLink = RoadLink(5170939L, Seq(Point(535605.272, 6982204.22, 85.90899999999965))
        , 540.3960283713503, State, 99, TrafficDirection.AgainstDigitizing, UnknownLinkType, Some("25.06.2015 03:00:00"), Some("vvh_modified"), Map("MUNICIPALITYCODE" -> BigInt.apply(749)),
        InUse, NormalLinkInterface)

      val projectId = Sequences.nextProjectId

      val roadwayId = Sequences.nextRoadwayId

      val roadAddressProject = Project(projectId, ProjectState.apply(1), "TestProject", "TestUser", DateTime.now(), "TestUser", DateTime.parse("1970-01-01"), DateTime.now(), "Some additional info", Seq(), Seq(), None)

      val projectLink = dummyProjectLink(1, 1, Track.Combined, Discontinuity.EndOfRoad, 0, 100, Some(DateTime.parse("1970-01-01")), None, 12345, 0, 100, SideCode.TowardsDigitizing, LinkStatus.UnChanged, projectId, PublicRoad, Seq(Point(0.0, 0.0), Point(0.0, 100.0)))

      val roadway = dummyRoadway(roadwayNumber =1234l, roadNumber = 1, roadPartNumber = 1, startAddrM = 0, endAddrM = 100, startDate = DateTime.now(), endDate = None, roadwayId = roadwayId)

      roadwayDAO.create(Seq(roadway))

      when(mockRoadLinkService.getRoadLinksHistoryFromVVH(any[Set[Long]])).thenReturn(Seq())
      when(mockRoadLinkService.getRoadLinksByLinkIdsFromVVH(any[Set[Long]])).thenAnswer(
        toMockAnswer(Seq(projectLink), roadLink)
      )
      val historicRoadId = projectService.expireHistoryRows(roadwayId)
      historicRoadId should be (1)
    }
  }

  test("Test Road names should not have valid road name for any roadnumber after TR response") {
    runWithRollback {
      val projectId = Sequences.nextViitePrimaryKeySeqValue
      sqlu"""INSERT INTO ROAD_NAME VALUES (ROAD_NAME_SEQ.nextval, 66666, 'ROAD TEST', TIMESTAMP '2018-03-23 12:26:36.000000', null, TIMESTAMP '2018-03-23 12:26:36.000000', null, 'test user', TIMESTAMP '2018-03-23 12:26:36.000000')""".execute

      sqlu"""INSERT INTO PROJECT VALUES($projectId, 2, 'test project', 'silari', TIMESTAMP '2018-03-23 11:36:15.000000', '-', TIMESTAMP '2018-03-23 12:26:33.000000', NULL, NULL, NULL, 1, 533406.572, 6994060.048, 12)""".execute
      sqlu"""INSERT INTO PROJECT_RESERVED_ROAD_PART VALUES (${Sequences.nextViitePrimaryKeySeqValue}, 66666, 1, $projectId, '-')""".execute

      sqlu"""INSERT INTO PROJECT_LINK VALUES (${Sequences.nextViitePrimaryKeySeqValue}, $projectId, 0, 5, 66666, 1, 0, 86, 'test user', 'test user', TIMESTAMP '2018-03-23 12:26:36.000000', TIMESTAMP '2018-03-23 00:00:00.000000', 2, 3, 1, NULL, NULL, NULL, 8, 0, NULL, NULL, NULL, NULL, 1543328166000, 1, 0, NULL, 0, 85.617, NULL)""".execute

      sqlu"""INSERT INTO PROJECT_LINK_NAME VALUES (PROJECT_LINK_NAME_SEQ.nextval, $projectId, 66666, 'ROAD TEST')""".execute
      val namesBeforeUpdate = RoadNameDAO.getLatestRoadName(66666)
      val changeInfos = List(
        RoadwayChangeInfo(AddressChangeType.New,
          source = dummyRoadwayChangeSection(Some(66666L), Some(1L), Some(0L), Some(0L), Some(100L), Some(RoadType.apply(1)), Some(Discontinuity.Continuous), Some(8L)),
          target = dummyRoadwayChangeSection(Some(66666L), Some(1L), Some(0L), Some(0L), Some(100L), Some(RoadType.apply(1)), Some(Discontinuity.Continuous), Some(8L)),
          Continuous, RoadType.apply(1), reversed = false, 1)
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

  test("Test publishProject When sending changes to TR and provoking a IOException exception when publishing a project Then check if the project state is changed to 9") {
    var count = 0
    val roadNumber = 5L
    val part = 207L
    val roadLink = RoadLink(5170939L, Seq(Point(535605.272, 6982204.22, 85.90899999999965)), 540.3960283713503, State,
      99, TrafficDirection.AgainstDigitizing, UnknownLinkType, Some("25.06.2015 03:00:00"), Some("vvh_modified"),
      Map("MUNICIPALITYCODE" -> BigInt.apply(749)), InUse, NormalLinkInterface)
    runWithRollback {
      val countCurrentProjects = projectService.getAllProjects
      val id = 0
      val addresses = List(ProjectReservedPart(5: Long, roadNumber: Long, part: Long, Some(5L), Some(Discontinuity.apply("jatkuva")),
        Some(8L), newLength = None, newDiscontinuity = None, newEly = None))
      val project = Project(id, ProjectState.Incomplete, "TestProject", "TestUser", DateTime.now(),
        "TestUser", DateTime.parse("1970-01-01"), DateTime.now(), "Some additional info", Seq(), Seq(), None)
      val savedProject = projectService.createRoadLinkProject(project)
      mockForProject(savedProject.id, roadwayAddressMapper.getRoadAddressesByRoadway(roadwayDAO.fetchAllByRoadAndPart(roadNumber, part)).map(toProjectLink(savedProject)))
      projectService.saveProject(savedProject.copy(reservedParts = addresses))
      val countAfterInsertProjects = projectService.getAllProjects
      count = countCurrentProjects.size + 1
      countAfterInsertProjects.size should be(count)
      projectService.allLinksHandled(savedProject.id) should be(false)
      val projectLinks = projectLinkDAO.fetchProjectLinks(savedProject.id)
      val partitioned = projectLinks.partition(_.roadPartNumber == part)
      val linkIds207 = partitioned._1.map(_.linkId).toSet
      reset(mockRoadLinkService)
      when(mockRoadLinkService.getRoadLinksHistoryFromVVH(any[Set[Long]])).thenReturn(Seq())
      when(mockRoadLinkService.getRoadLinksByLinkIdsFromVVH(any[Set[Long]])).thenAnswer(
        toMockAnswer(projectLinks, roadLink)
      )
      projectService.updateProjectLinks(savedProject.id, Set(), linkIds207.toSeq, LinkStatus.Transfer, "-", roadNumber, part, 0, Option.empty[Int]) should be(None)
      projectService.updateProjectLinks(savedProject.id, Set(), Seq(5168510), LinkStatus.Terminated, "-", roadNumber, part, 0, Option.empty[Int]) should be(None)
      projectService.allLinksHandled(savedProject.id) should be(true)

      projectService.updateProjectLinks(project.id, Set(), Seq(5168540), LinkStatus.Terminated, "-", roadNumber, part, 0, Option.empty[Int]) should be(None)
      //         This will result in a IO exception being thrown and caught inside the publish, making the update of the project for the state SendingToTR
      //         If the tests ever get a way to have TR connectivity then this needs to be somewhat addressed

      projectService.publishProject(savedProject.id)
      val currentProjectStatus = projectDAO.fetchProjectStatus(savedProject.id)
      currentProjectStatus.isDefined should be(true)
      currentProjectStatus.get.value should be(ProjectState.SendingToTR.value)
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
      val projectId = Sequences.nextViitePrimaryKeySeqValue
      sqlu"""INSERT INTO PROJECT VALUES($projectId, 2, 'test project', 'silari', TIMESTAMP '2018-03-23 11:36:15.000000', '-', TIMESTAMP '2018-03-23 12:26:33.000000', NULL, TIMESTAMP '2018-03-23 00:00:00.000000', NULL, 0, 533406.572, 6994060.048, 12)""".execute
      sqlu"""INSERT INTO PROJECT_RESERVED_ROAD_PART VALUES (${Sequences.nextViitePrimaryKeySeqValue}, 70001, 1, $projectId, '-')""".execute
      sqlu"""Insert into PROJECT_LINK (ID,PROJECT_ID,TRACK,DISCONTINUITY_TYPE,ROAD_NUMBER,ROAD_PART_NUMBER,START_ADDR_M,END_ADDR_M,ORIGINAL_START_ADDR_M,ORIGINAL_END_ADDR_M,CREATED_BY,MODIFIED_BY,CREATED_DATE,MODIFIED_DATE,STATUS,CALIBRATION_POINTS,ROAD_TYPE,ROADWAY_ID,CONNECTED_LINK_ID,ELY,REVERSED,SIDE,START_MEASURE,END_MEASURE,LINK_ID,ADJUSTED_TIMESTAMP,LINK_SOURCE)
                VALUES (${Sequences.nextViitePrimaryKeySeqValue}, $projectId, 0, 5, 70001, 1, 0, 86, 0, 86, 'test user', 'test user', TIMESTAMP '2018-03-23 12:26:36.000000', TIMESTAMP '2018-03-23 00:00:00.000000', 5, 3, 1, NULL, NULL, 8, 0, 2, 0, 85.617, 5170979, 1500079296000, 1)""".execute
      sqlu"""INSERT INTO PROJECT_LINK_NAME VALUES (PROJECT_LINK_NAME_SEQ.nextval, $projectId, 70001, NULL)""".execute
      val namesBeforeUpdate = RoadNameDAO.getLatestRoadName(70001)
      namesBeforeUpdate.isEmpty should be(true)
      when(mockNodesAndJunctionsService.expireObsoleteNodesAndJunctions(any[Seq[ProjectLink]], any[Option[DateTime]], any[String])).thenReturn(Seq())
      projectService.updateRoadwaysAndLinearLocationsWithProjectLinks(ProjectState.Saved2TR, projectId)

      val project = projectService.getSingleProjectById(projectId)
      val namesAfterUpdate = RoadNameDAO.getLatestRoadName(70001)
      project.get.statusInfo should be(None)
      namesAfterUpdate.isEmpty should be(true)
    }
  }

  test("Test getLatestRoadName road name exists on TR success response") {
    runWithRollback {
      val projectId = Sequences.nextViitePrimaryKeySeqValue
      sqlu"""INSERT INTO ROAD_NAME VALUES (ROAD_NAME_SEQ.nextval, 66666, 'road name test', TIMESTAMP '2018-03-23 12:26:36.000000', null, TIMESTAMP '2018-03-23 12:26:36.000000', null, 'test user', TIMESTAMP '2018-03-23 12:26:36.000000')""".execute

      sqlu"""INSERT INTO PROJECT VALUES($projectId, 2, 'test project', 'silari', TIMESTAMP '2018-03-23 11:36:15.000000', '-', TIMESTAMP '2018-03-23 12:26:33.000000', NULL, TIMESTAMP '2018-03-23 00:00:00.000000', NULL, 0, 533406.572, 6994060.048, 12)""".execute
      sqlu"""INSERT INTO PROJECT_RESERVED_ROAD_PART VALUES (${Sequences.nextViitePrimaryKeySeqValue}, 66666, 1, $projectId, '-')""".execute
      sqlu"""INSERT INTO PROJECT_LINK VALUES (${Sequences.nextViitePrimaryKeySeqValue}, $projectId, 0, 5, 66666, 1, 0, 86, 'test user', 'test user', TIMESTAMP '2018-03-23 12:26:36.000000', TIMESTAMP '2018-03-23 00:00:00.000000', 2, 3, 1, NULL, NULL, NULL, 8, 0, 2, 0, 85.617, 5170979, 1500079296000, 1, 0, NULL, 0, 86, NULL)""".execute
      sqlu"""INSERT INTO PROJECT_LINK_NAME VALUES (PROJECT_LINK_NAME_SEQ.nextval, $projectId, 66666, 'another road name test')""".execute

      val changeInfos = List(
        RoadwayChangeInfo(AddressChangeType.New,
          source = dummyRoadwayChangeSection(Some(66666L), Some(1L), Some(0L), Some(0L), Some(100L), Some(RoadType.apply(1)), Some(Discontinuity.Continuous), Some(8L)),
          target = dummyRoadwayChangeSection(Some(66666L), Some(1L), Some(0L), Some(0L), Some(100L), Some(RoadType.apply(1)), Some(Discontinuity.Continuous), Some(8L)),
          Continuous, RoadType.apply(1), reversed = false, 1)
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
      val projectId = Sequences.nextViitePrimaryKeySeqValue
      sqlu"""INSERT INTO PROJECT VALUES($projectId, 2, 'test project', 'silari', TIMESTAMP '2018-03-23 11:36:15.000000', '-', TIMESTAMP '2018-03-23 12:26:33.000000', NULL, TIMESTAMP '2018-03-23 00:00:00.000000', NULL, 0, 533406.572, 6994060.048, 12)""".execute
      sqlu"""INSERT INTO PROJECT_RESERVED_ROAD_PART VALUES (${Sequences.nextViitePrimaryKeySeqValue}, 66666, 1, $projectId, '-')""".execute
      sqlu"""INSERT INTO PROJECT_LINK VALUES (${Sequences.nextViitePrimaryKeySeqValue}, $projectId, 0, 5, 66666, 1, 0, 86, 'test user', 'test user', TIMESTAMP '2018-03-23 12:26:36.000000', TIMESTAMP '2018-03-23 00:00:00.000000', 2, 3, 1, NULL, NULL, NULL, 8, 0, 2, 0, 85.617, 5170979, 1500079296000, 1, 0, NULL, 0, 86, NULL)""".execute
      sqlu"""INSERT INTO PROJECT_LINK_NAME VALUES (PROJECT_LINK_NAME_SEQ.nextval, $projectId, 66666, 'road name test')""".execute
      val changeInfos = List(
        RoadwayChangeInfo(AddressChangeType.New,
          source = dummyRoadwayChangeSection(Some(66666L), Some(1L), Some(0L), Some(0L), Some(100L), Some(RoadType.apply(1)), Some(Discontinuity.Continuous), Some(8L)),
          target = dummyRoadwayChangeSection(Some(66666L), Some(1L), Some(0L), Some(0L), Some(100L), Some(RoadType.apply(1)), Some(Discontinuity.Continuous), Some(8L)),
          Continuous, RoadType.apply(1), reversed = false, 1)
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
      val projectId = Sequences.nextViitePrimaryKeySeqValue
      sqlu"""INSERT INTO PROJECT VALUES($projectId, 2, 'test project', 'silari', TIMESTAMP '2018-03-23 11:36:15.000000', '-', TIMESTAMP '2018-03-23 12:26:33.000000', NULL, TIMESTAMP '2018-03-23 00:00:00.000000', NULL, 0, 533406.572, 6994060.048, 12)""".execute
      sqlu"""INSERT INTO PROJECT_RESERVED_ROAD_PART VALUES (${Sequences.nextViitePrimaryKeySeqValue}, 66666, 1, $projectId, '-')""".execute
      sqlu"""INSERT INTO PROJECT_RESERVED_ROAD_PART VALUES (${Sequences.nextViitePrimaryKeySeqValue}, 55555, 1, $projectId, '-')""".execute
      sqlu"""INSERT INTO PROJECT_LINK VALUES (${Sequences.nextViitePrimaryKeySeqValue}, $projectId, 0, 5, 66666, 1, 0, 86, 'test user', 'test user', TIMESTAMP '2018-03-23 12:26:36.000000', TIMESTAMP '2018-03-23 00:00:00.000000', 2, 3, 1, NULL, NULL, NULL, 8, 0, 2, 0, 85.617, 5170979, 1500079296000, 1, 0, NULL, 0, 86, NULL)""".execute
      sqlu"""INSERT INTO PROJECT_LINK VALUES (${Sequences.nextViitePrimaryKeySeqValue}, $projectId, 0, 5, 55555, 1, 0, 86, 'test user', 'test user', TIMESTAMP '2018-03-23 12:26:36.000000', TIMESTAMP '2018-03-23 00:00:00.000000', 2, 3, 1, NULL, NULL, NULL, 8, 0, 2, 0, 85.617, 5170980, 1500079296000, 1, 0, NULL, 0, 86, NULL)""".execute
      sqlu"""INSERT INTO PROJECT_LINK_NAME VALUES (PROJECT_LINK_NAME_SEQ.nextval, $projectId, 66666, 'road name test')""".execute
      sqlu"""INSERT INTO PROJECT_LINK_NAME VALUES (PROJECT_LINK_NAME_SEQ.nextval, $projectId, 55555, 'road name test2')""".execute
      RoadNameDAO.getLatestRoadName(55555).isEmpty should be (true)
      RoadNameDAO.getLatestRoadName(66666).isEmpty should be (true)
      val changeInfos = List(
        RoadwayChangeInfo(AddressChangeType.New,
          source = dummyRoadwayChangeSection(Some(66666L), Some(1L), Some(0L), Some(0L), Some(100L), Some(RoadType.apply(1)), Some(Discontinuity.Continuous), Some(8L)),
          target = dummyRoadwayChangeSection(Some(66666L), Some(1L), Some(0L), Some(0L), Some(100L), Some(RoadType.apply(1)), Some(Discontinuity.Continuous), Some(8L)),
          Continuous, RoadType.apply(1), reversed = false, 1),
        RoadwayChangeInfo(AddressChangeType.New,
          source = dummyRoadwayChangeSection(Some(55555L), Some(1L), Some(0L), Some(0L), Some(100L), Some(RoadType.apply(1)), Some(Discontinuity.Continuous), Some(8L)),
          target = dummyRoadwayChangeSection(Some(55555L), Some(1L), Some(0L), Some(0L), Some(100L), Some(RoadType.apply(1)), Some(Discontinuity.Continuous), Some(8L)),
          Continuous, RoadType.apply(1), reversed = false, 1)
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
    val roadLink = RoadLink(5170939L, Seq(Point(535605.272, 6982204.22, 85.90899999999965))
      , 540.3960283713503, State, 99, TrafficDirection.AgainstDigitizing, UnknownLinkType, Some("25.06.2015 03:00:00"), Some("vvh_modified"), Map("MUNICIPALITYCODE" -> BigInt.apply(749)),
      InUse, NormalLinkInterface)
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
      when(mockRoadLinkService.getRoadLinksHistoryFromVVH(any[Set[Long]])).thenReturn(Seq())
      when(mockRoadLinkService.getRoadLinksByLinkIdsFromVVH(any[Set[Long]])).thenAnswer(
        toMockAnswer(projectLinks, roadLink)
      )
      projectService.updateProjectLinks(savedProject.id, Set(), linkIds205.toSeq, LinkStatus.UnChanged, "-", 0, 0, 0, Option.empty[Int]) should be(None)
      projectService.allLinksHandled(savedProject.id) should be(false)
      projectService.updateProjectLinks(savedProject.id, Set(), linkIds206.toSeq, LinkStatus.UnChanged, "-", 0, 0, 0, Option.empty[Int]) should be(None)
      projectService.allLinksHandled(savedProject.id) should be(true)
      projectService.updateProjectLinks(savedProject.id, Set(), Seq(5168573), LinkStatus.Terminated, "-", 0, 0, 0, Option.empty[Int]) should be(None)
      projectService.allLinksHandled(savedProject.id) should be(true)
      val updatedProjectLinks = projectLinkDAO.fetchProjectLinks(savedProject.id)
      updatedProjectLinks.exists { x => x.status == LinkStatus.UnChanged } should be(true)
      updatedProjectLinks.exists { x => x.status == LinkStatus.Terminated } should be(true)
      updatedProjectLinks.filter(pl => pl.linkId == 5168579).head.calibrationPoints should be((None, Some(ProjectLinkCalibrationPoint(5168579, 15.173, 4681, ProjectLinkSource))))
      projectService.updateProjectLinks(savedProject.id, Set(), Seq(5168579), LinkStatus.Terminated, "-", 0, 0, 0, Option.empty[Int])
      val updatedProjectLinks2 = projectLinkDAO.fetchProjectLinks(savedProject.id)
      val sortedRoad206AfterTermination = updatedProjectLinks2.filter(_.roadPartNumber == 206).sortBy(_.startAddrMValue)
      updatedProjectLinks2.filter(pl => pl.linkId == 5168579).head.calibrationPoints should be((None, None))
      val lastValid = sortedRoad206AfterTermination.filter(_.status != LinkStatus.Terminated).last
      sortedRoad206AfterTermination.filter(_.status != LinkStatus.Terminated).last.calibrationPoints should be((None, Some(ProjectLinkCalibrationPoint(lastValid.linkId, lastValid.endMValue, lastValid.endAddrMValue, ProjectLinkSource))))
      updatedProjectLinks2.filter(pl => pl.roadPartNumber == 205).exists { x => x.status == LinkStatus.Terminated } should be(false)
    }
    runWithRollback {
      projectService.getAllProjects
    } should have size (count - 1)
  }

  test("Test getProjectLinks When doing some operations (Transfer and then Terminate), Then the calibration points are cleared and moved to correct positions") {
    var count = 0
    val roadLink = RoadLink(5170939L, Seq(Point(535605.272, 6982204.22, 85.90899999999965))
      , 540.3960283713503, State, 99, TrafficDirection.AgainstDigitizing, UnknownLinkType, Some("25.06.2015 03:00:00"), Some("vvh_modified"), Map("MUNICIPALITYCODE" -> BigInt.apply(749)),
      InUse, NormalLinkInterface)
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
      when(mockRoadLinkService.getRoadLinksHistoryFromVVH(any[Set[Long]])).thenReturn(Seq())
      when(mockRoadLinkService.getRoadLinksByLinkIdsFromVVH(any[Set[Long]])).thenAnswer(
        toMockAnswer(projectLinks, roadLink)
      )
      projectService.updateProjectLinks(savedProject.id, Set(), linkIds207.toSeq, LinkStatus.Transfer, "-", 5, 207, 0, Option.empty[Int]) should be(None)
      projectService.updateProjectLinks(savedProject.id, Set(), Seq(5168510), LinkStatus.Terminated, "-", 5, 207, 0, Option.empty[Int]) should be(None)
      projectService.allLinksHandled(savedProject.id) should be(true)
      val changeProjectOpt = projectService.getChangeProject(savedProject.id)
      val change = changeProjectOpt._1.get
      val updatedProjectLinks = projectLinkDAO.fetchProjectLinks(savedProject.id)
      updatedProjectLinks.exists { x => x.status == LinkStatus.Transfer } should be(true)
      updatedProjectLinks.exists { x => x.status == LinkStatus.Terminated } should be(true)
      val sortedProjectLinks = updatedProjectLinks.sortBy(_.startAddrMValue)
      sortedProjectLinks.head.calibrationPoints._1.nonEmpty should be (true)
      sortedProjectLinks.head.calibrationPoints._1.get.segmentMValue should be (0.0)
      sortedProjectLinks.head.calibrationPoints._1.get.addressMValue should be (0)
      sortedProjectLinks.head.calibrationPoints._1.get.source should be (ProjectLinkSource)
      sortedProjectLinks.head.calibrationPoints._2.isEmpty should be (true)

      sortedProjectLinks.last.calibrationPoints._1.isEmpty should be (true)
      sortedProjectLinks.last.calibrationPoints._2.nonEmpty should be (true)
      sortedProjectLinks.last.calibrationPoints._2.get.segmentMValue should be (442.89)
      sortedProjectLinks.last.calibrationPoints._2.get.addressMValue should be (highestDistanceEnd - projectLinks.filter(pl => pl.linkId == 5168510).head.endAddrMValue)
      sortedProjectLinks.last.calibrationPoints._2.get.source should be (ProjectLinkSource)

      projectService.updateProjectLinks(savedProject.id, Set(), Seq(5168540), LinkStatus.Terminated, "-", 5, 207, 0, Option.empty[Int]) should be(None)
      val updatedProjectLinks2 = projectLinkDAO.fetchProjectLinks(savedProject.id)
      val sortedProjectLinks2 = updatedProjectLinks2.sortBy(_.startAddrMValue)

      sortedProjectLinks2.last.calibrationPoints._1.isEmpty should be (true)
      sortedProjectLinks2.last.calibrationPoints._2.nonEmpty should be (true)
      sortedProjectLinks2.last.calibrationPoints._2.get.segmentMValue should be (442.89)
      sortedProjectLinks2.last.calibrationPoints._2.get.addressMValue should be (highestDistanceEnd - projectLinks.filter(pl => pl.linkId == 5168510).head.endAddrMValue - updatedProjectLinks.filter(pl => pl.linkId == 5168540).head.endAddrMValue)
      sortedProjectLinks2.last.calibrationPoints._2.get.source should be (ProjectLinkSource)
    }

  }

  test("Test getProjectLinks When doing some operations (Terminate then transfer), Then the calibration points are cleared and moved to correct positions") {
    var count = 0
    val roadLink = RoadLink(5170939L, Seq(Point(535605.272, 6982204.22, 85.90899999999965))
      , 540.3960283713503, State, 99, TrafficDirection.AgainstDigitizing, UnknownLinkType, Some("25.06.2015 03:00:00"), Some("vvh_modified"), Map("MUNICIPALITYCODE" -> BigInt.apply(749)),
      InUse, NormalLinkInterface)
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
      when(mockRoadLinkService.getRoadLinksHistoryFromVVH(any[Set[Long]])).thenReturn(Seq())
      when(mockRoadLinkService.getRoadLinksByLinkIdsFromVVH(any[Set[Long]])).thenAnswer(
        toMockAnswer(projectLinks, roadLink)
      )
      projectService.updateProjectLinks(savedProject.id, Set(), Seq(5168510), LinkStatus.Terminated, "-", 5, 207, 0, Option.empty[Int])
      projectService.updateProjectLinks(savedProject.id, Set(), linkIds207.filterNot(_ == 5168510L).toSeq, LinkStatus.Transfer, "-", 5, 207, 0, Option.empty[Int])
      projectService.allLinksHandled(savedProject.id) should be(true)
      val changeProjectOpt = projectService.getChangeProject(savedProject.id)
      val change = changeProjectOpt._1.get
      val updatedProjectLinks = projectLinkDAO.fetchProjectLinks(savedProject.id)
      updatedProjectLinks.exists { x => x.status == LinkStatus.Transfer } should be(true)
      updatedProjectLinks.exists { x => x.status == LinkStatus.Terminated } should be(true)
      val sortedProjectLinks = updatedProjectLinks.sortBy(_.startAddrMValue)
      sortedProjectLinks.head.calibrationPoints._1.nonEmpty should be (true)
      sortedProjectLinks.head.calibrationPoints._1.get.segmentMValue should be (0.0)
      sortedProjectLinks.head.calibrationPoints._1.get.addressMValue should be (0)
      sortedProjectLinks.head.calibrationPoints._1.get.source should be (ProjectLinkSource)
      sortedProjectLinks.head.calibrationPoints._2.isEmpty should be (true)

      sortedProjectLinks.last.calibrationPoints._1.isEmpty should be (true)
      sortedProjectLinks.last.calibrationPoints._2.nonEmpty should be (true)
      sortedProjectLinks.last.calibrationPoints._2.get.segmentMValue should be (442.89)
      sortedProjectLinks.last.calibrationPoints._2.get.addressMValue should be (highestDistanceEnd - projectLinks.filter(pl => pl.linkId == 5168510).head.endAddrMValue)
      sortedProjectLinks.last.calibrationPoints._2.get.source should be (ProjectLinkSource)

      projectService.updateProjectLinks(savedProject.id, Set(), Seq(5168540), LinkStatus.Terminated, "-", 5, 207, 0, Option.empty[Int])
      val updatedProjectLinks2 = projectLinkDAO.fetchProjectLinks(savedProject.id)
      val sortedProjectLinks2 = updatedProjectLinks2.sortBy(_.startAddrMValue)

      sortedProjectLinks2.last.calibrationPoints._1.isEmpty should be (true)
      sortedProjectLinks2.last.calibrationPoints._2.nonEmpty should be (true)
      sortedProjectLinks2.last.calibrationPoints._2.get.segmentMValue should be (442.89)
      sortedProjectLinks2.last.calibrationPoints._2.get.addressMValue should be (highestDistanceEnd - projectLinks.filter(pl => pl.linkId == 5168510).head.endAddrMValue - updatedProjectLinks.filter(pl => pl.linkId == 5168540).head.endAddrMValue)
      sortedProjectLinks2.last.calibrationPoints._2.get.source should be (ProjectLinkSource)
    }

  }

  test("Test revertLinks When reverting the road Then the road address geometry after reverting should be the same as VVH") {
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

      val numberingLink = Seq(ProjectLink(-1000L, newRoadNumber, newRoadPart, Track.apply(0), Discontinuity.Continuous, 0L, 5L, 0L, 10L, None, None,
        Option(user), projectLinksFromRoadAddresses.head.linkId, 0.0, 10.0, SideCode.Unknown, (None, None),
        smallerRoadGeom, rap.id, LinkStatus.Numbering, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, 10.0, roadAddresses.head.id, roadwayNumber+Math.round(linearLocation.orderNumber), 0, reversed = false,
        None, 86400L))
      projectReservedPartDAO.reserveRoadPart(projectId, newRoadNumber, newRoadPart, "Test")
      projectLinkDAO.create(numberingLink)
      val numberingLinks = projectLinkDAO.fetchProjectLinks(projectId, Option(LinkStatus.Numbering))
      numberingLinks.head.geometry should be equals smallerRoadGeom

     val projectLinks = projectLinkDAO.fetchProjectLinks(projectId)
     val linksToRevert = projectLinks.filter(_.status != LinkStatus.NotHandled).map(pl => {
       LinkToRevert(pl.id, pl.linkId, pl.status.value, pl.geometry)
     })
     val roadLinks = projectLinks.updated(0, projectLinks.head.copy(geometry = roadGeom)).map(toRoadLink)
      when(mockRoadLinkService.getCurrentAndComplementaryRoadLinksFromVVH(any[Set[Long]])).thenReturn(roadLinks)
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
      val project = setUpProjectWithLinks(LinkStatus.Transfer, Seq(0, 100, 150, 300), changeTrack = true, roadNumber, roadPartNumber)
      val projectLinksBefore = projectLinkDAO.fetchProjectLinks(project.id).sortBy(_.startAddrMValue)

      val linksToRevert = projectLinksBefore.map( pl => LinkToRevert(pl.id, pl.id, LinkStatus.Transfer.value, pl.geometry))
      projectService.changeDirection(project.id, roadNumber, roadPartNumber, linksToRevert, ProjectCoordinates(0,0, 5), "testUser")

      val projectLinksAfter = projectLinkDAO.fetchProjectLinks(project.id).sortBy(_.startAddrMValue)
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
          source = dummyRoadwayChangeSection(Some(testRoadNumber1), Some(1L), Some(0L), Some(0L), Some(100L), Some(RoadType.apply(1)), Some(Discontinuity.Continuous), Some(8L)),
          target = dummyRoadwayChangeSection(Some(testRoadNumber1), Some(1L), Some(0L), Some(100L), Some(200L), Some(RoadType.apply(5)), Some(Discontinuity.Continuous), Some(8L)),
          Continuous, RoadType.apply(1), reversed = false, 1),

        RoadwayChangeInfo(AddressChangeType.New,
          source = dummyRoadwayChangeSection(Some(testRoadNumber1), Some(1L), Some(0L), Some(100L), Some(200L), Some(RoadType.apply(1)), Some(Discontinuity.Continuous), Some(8L)),
          target = dummyRoadwayChangeSection(Some(testRoadNumber1), Some(1L), Some(0L), Some(100L), Some(200L), Some(RoadType.apply(5)), Some(Discontinuity.Continuous), Some(8L)),
          Continuous, RoadType.apply(5), reversed = false, 2),

        RoadwayChangeInfo(AddressChangeType.New,
          source = dummyRoadwayChangeSection(Some(testRoadNumber1), Some(1L), Some(0L), Some(200L), Some(400L), Some(RoadType.apply(1)), Some(Discontinuity.Continuous), Some(8L)),
          target = dummyRoadwayChangeSection(Some(testRoadNumber1), Some(1L), Some(0L), Some(200L), Some(400L), Some(RoadType.apply(1)), Some(Discontinuity.Continuous), Some(8L)),
          Continuous, RoadType.apply(5), reversed = false, 3),
        RoadwayChangeInfo(AddressChangeType.New,
          source = dummyRoadwayChangeSection(Some(testRoadNumber2), Some(1L), Some(0L), Some(200L), Some(400L), Some(RoadType.apply(1)), Some(Discontinuity.Continuous), Some(8L)),
          target = dummyRoadwayChangeSection(Some(testRoadNumber2), Some(1L), Some(0L), Some(200L), Some(400L), Some(RoadType.apply(1)), Some(Discontinuity.Continuous), Some(8L)),
          Continuous, RoadType.apply(5), reversed = false, 3),
        RoadwayChangeInfo(AddressChangeType.New,
          source = dummyRoadwayChangeSection(Some(testRoadNumber2), Some(1L), Some(0L), Some(200L), Some(400L), Some(RoadType.apply(1)), Some(Discontinuity.Continuous), Some(8L)),
          target = dummyRoadwayChangeSection(Some(testRoadNumber2), Some(1L), Some(0L), Some(200L), Some(400L), Some(RoadType.apply(1)), Some(Discontinuity.Continuous), Some(8L)),
          Continuous, RoadType.apply(5), reversed = false, 3),
        RoadwayChangeInfo(AddressChangeType.New,
          source = dummyRoadwayChangeSection(Some(testRoadNumber2), Some(1L), Some(0L), Some(200L), Some(400L), Some(RoadType.apply(1)), Some(Discontinuity.Continuous), Some(8L)),
          target = dummyRoadwayChangeSection(Some(testRoadNumber2), Some(1L), Some(0L), Some(200L), Some(400L), Some(RoadType.apply(1)), Some(Discontinuity.Continuous), Some(8L)),
          Continuous, RoadType.apply(5), reversed = false, 3)
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
          source = dummyRoadwayChangeSection(Some(roadNumber), Some(1L), Some(0L), Some(0L), Some(100L), Some(RoadType.apply(1)), Some(Discontinuity.Continuous), Some(8L)),
          target = dummyRoadwayChangeSection(Some(roadNumber), Some(1L), Some(0L), Some(100L), Some(200L), Some(RoadType.apply(5)), Some(Discontinuity.Continuous), Some(8L)),
          Continuous, RoadType.apply(1), reversed = false, 1),

        RoadwayChangeInfo(AddressChangeType.Unchanged,
          source = dummyRoadwayChangeSection(Some(roadNumber), Some(1L), Some(0L), Some(100L), Some(200L), Some(RoadType.apply(1)), Some(Discontinuity.Continuous), Some(8L)),
          target = dummyRoadwayChangeSection(Some(roadNumber), Some(1L), Some(0L), Some(100L), Some(200L), Some(RoadType.apply(5)), Some(Discontinuity.Continuous), Some(8L)),
          Continuous, RoadType.apply(5), reversed = false, 2),

        RoadwayChangeInfo(AddressChangeType.Unchanged,
          source = dummyRoadwayChangeSection(Some(roadNumber), Some(1L), Some(0L), Some(200L), Some(400L), Some(RoadType.apply(1)), Some(Discontinuity.Continuous), Some(8L)),
          target = dummyRoadwayChangeSection(Some(roadNumber), Some(1L), Some(0L), Some(200L), Some(400L), Some(RoadType.apply(1)), Some(Discontinuity.Continuous), Some(8L)),
          Continuous, RoadType.apply(5), reversed = false, 3)
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
          source = dummyRoadwayChangeSection(Some(srcRoadNumber), Some(1L), Some(0L), Some(0L), Some(100L), Some(RoadType.apply(1)), Some(Discontinuity.Continuous), Some(8L)),
          target = dummyRoadwayChangeSection(Some(targetRoadNumber), Some(1L), Some(0L), Some(100L), Some(200L), Some(RoadType.apply(5)), Some(Discontinuity.Continuous), Some(8L)),
          Continuous, RoadType.apply(1), reversed = false, 1)
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
        Roadway(0L, 0L, srcRoadNumber, 0L, RoadType.PublicRoad, Track.Combined, Continuous, 0L, 0L, reversed = false, DateTime.now, Some(DateTime.now), "dummy", None, 0L, NoTermination),
        Roadway(-1L, 0L, targetRoadNumber, 0L, RoadType.PublicRoad, Track.Combined, Continuous, 0L, 0L, reversed = false, DateTime.now, Some(DateTime.now), "dummy", None, 0L, NoTermination)
      )
      roadwayDAO.create(roadways)

      val changeInfos = List(
        RoadwayChangeInfo(AddressChangeType.Transfer,
          source = dummyRoadwayChangeSection(Some(srcRoadNumber), Some(1L), Some(0L), Some(0L), Some(100L), Some(RoadType.apply(1)), Some(Discontinuity.Continuous), Some(8L)),
          target = dummyRoadwayChangeSection(Some(targetRoadNumber), Some(1L), Some(0L), Some(100L), Some(200L), Some(RoadType.apply(5)), Some(Discontinuity.Continuous), Some(8L)),
          Continuous, RoadType.apply(1), reversed = false, 1)
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
          source = dummyRoadwayChangeSection(Some(srcRoadNumber), Some(1L), Some(0L), Some(0L), Some(100L), Some(RoadType.apply(1)), Some(Discontinuity.Continuous), Some(8L)),
          target = dummyRoadwayChangeSection(Some(targetRoadNumber), Some(1L), Some(0L), Some(100L), Some(200L), Some(RoadType.apply(5)), Some(Discontinuity.Continuous), Some(8L)),
          Continuous, RoadType.apply(1), reversed = false, 1)
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
        Roadway(0L, 0L, srcRoadNumber, 0L, RoadType.PublicRoad, Track.Combined, Continuous, 0L, 0L, startDate = DateTime.now, endDate = Some(DateTime.now), createdBy = "dummy", roadName = None, ely = 0L, terminated = NoTermination),
        Roadway(-1L, 0L, targetRoadNumber, 0L, RoadType.PublicRoad, Track.Combined, Continuous, 0L, 0L, reversed = false, DateTime.now, Some(DateTime.now), "dummy", None, 0L, NoTermination)
      )
      roadwayDAO.create(roadways)

      val changeInfos = List(
        RoadwayChangeInfo(AddressChangeType.ReNumeration,
          source = dummyRoadwayChangeSection(Some(srcRoadNumber), Some(1L), Some(0L), Some(0L), Some(100L), Some(RoadType.apply(1)), Some(Discontinuity.Continuous), Some(8L)),
          target = dummyRoadwayChangeSection(Some(targetRoadNumber), Some(1L), Some(0L), Some(100L), Some(200L), Some(RoadType.apply(5)), Some(Discontinuity.Continuous), Some(8L)),
          Continuous, RoadType.apply(1), reversed = false, 1)
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
  /*test("If the suplied, old, road address has a valid_to < sysdate then the outputted, new, road addresses are floating") {
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
    val project = Project(-1L, Sent2TR, "split", user.get, DateTime.now(), user.get,
      DateTime.now().plusMonths(2), DateTime.now(), "", Seq(), None, None)

    // Original road address: 1024 -> 1547
    val roadAddress = RoadAddress(1L, linearLocationId, road, roadPart, PublicRoad, Track.Combined, Continuous, origStartM, origEndM, Some(DateTime.now().minusYears(10)),
      None, None, linkId, 0.0, endM, SideCode.TowardsDigitizing, 86400L, (None, None),  Seq(Point(1024.0, 0.0), Point(1025.0, 1544.386)),
      LinkGeomSource.NormalLinkInterface, 8L, NoTermination, 123)

    val projectLink = ProjectLink(0, road, roadPart, Track.Combined, Continuous, 0, 0, 0, 0, Some(DateTime.now()), None, user,
      0, 0.0, 0.0, SideCode.TowardsDigitizing, (None, None),  Seq(Point(0.0, 0.0), Point(0.0, 0.0)),
      -1L, null, PublicRoad, null, 0.0, 1L, linearLocationId, 8L, reversed = false, None, 748800L)
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

  test("Test changeDirection When after the creation of valid project links on a project Then the side code of road addresses should be reversed.") {
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

      val pl1 = ProjectLink(-1000L, 9999L, 1L, Track.RightSide, Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None,
        None, 12345L, 0.0, 0.0, SideCode.Unknown, (None, None),
        geom1, 0L, LinkStatus.New, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geom1), 0L, 0, 0, reversed = false,
        None, NewIdValue)
      val pl2 = ProjectLink(-1000L, 9999L, 1L, Track.RightSide, Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None,
        None, 12346L, 0.0, 0.0, SideCode.Unknown, (None, None),
        geom2, 0L, LinkStatus.New, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geom2), 0L, 0, 0, reversed = false,
        None, NewIdValue)
      val pl3 = ProjectLink(-1000L, 9999L, 1L, Track.RightSide, Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None,
        None, 12347L, 0.0, 0.0, SideCode.Unknown, (None, None),
        geom3, 0L, LinkStatus.New, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geom3), 0L, 0, 0, reversed = false,
        None, NewIdValue)
      val pl4 = ProjectLink(-1000L, 9999L, 1L, Track.RightSide, Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None,
        None, 12348L, 0.0, 0.0, SideCode.Unknown, (None, None),
        geom4, 0L, LinkStatus.New, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geom4), 0L, 0, 0, reversed = false,
        None, NewIdValue)
      val pl5 = ProjectLink(-1000L, 9999L, 1L, Track.RightSide, Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None,
        None, 12349L, 0.0, 0.0, SideCode.Unknown, (None, None),
        geom5, 0L, LinkStatus.New, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geom5), 0L, 0, 0, reversed = false,
        None, NewIdValue)
      val pl6 = ProjectLink(-1000L, 9999L, 1L, Track.RightSide, Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None,
        None, 12350L, 0.0, 0.0, SideCode.Unknown, (None, None),
        geom6, 0L, LinkStatus.New, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geom6), 0L, 0, 0, reversed = false,
        None, NewIdValue)
      val pl7 = ProjectLink(-1000L, 9999L, 1L, Track.RightSide, Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None,
        None, 12351L, 0.0, 0.0, SideCode.Unknown, (None, None),
        geom7, 0L, LinkStatus.New, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geom7), 0L, 0, 0, reversed = false,
        None, NewIdValue)
      val pl8 = ProjectLink(-1000L, 9999L, 1L, Track.RightSide, Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None,
        None, 12352L, 0.0, 0.0, SideCode.Unknown, (None, None),
        geom8, 0L, LinkStatus.New, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geom8), 0L, 0, 0, reversed = false,
        None, NewIdValue)
      val pl9 = ProjectLink(-1000L, 9999L, 1L, Track.RightSide, Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None,
        None, 12353L, 0.0, 0.0, SideCode.Unknown, (None, None),
        geom9, 0L, LinkStatus.New, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geom9), 0L, 0, 0, reversed = false,
        None, NewIdValue)
      val pl10 = ProjectLink(-1000L, 9999L, 1L, Track.RightSide, Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None,
        None, 12354L, 0.0, 0.0, SideCode.Unknown, (None, None),
        geom10, 0L, LinkStatus.New, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geom10), 0L, 0, 0, reversed = false,
        None, NewIdValue)
      val pl11 = ProjectLink(-1000L, 9999L, 1L, Track.RightSide, Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None,
        None, 12355L, 0.0, 0.0, SideCode.Unknown, (None, None),
        geom11, 0L, LinkStatus.New, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geom11), 0L, 0, 0, reversed = false,
        None, NewIdValue)
      val pl12 = ProjectLink(-1000L, 9999L, 1L, Track.RightSide, Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None,
        None, 12356L, 0.0, 0.0, SideCode.Unknown, (None, None),
        geom12, 0L, LinkStatus.New, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geom12), 0L, 0, 0, reversed = false,
        None, NewIdValue)
      val pl13 = ProjectLink(-1000L, 9999L, 1L, Track.RightSide, Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None,
        None, 12357L, 0.0, 0.0, SideCode.Unknown, (None, None),
        geom13, 0L, LinkStatus.New, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geom13), 0L, 0, 0, reversed = false,
        None, NewIdValue)

      val project = projectService.createRoadLinkProject(rap)
      when(mockRoadLinkService.getRoadLinksByLinkIdsFromVVH(any[Set[Long]])).thenReturn(Seq(pl1, pl2, pl3, pl4, pl5, pl6, pl7).map(toRoadLink))
      projectService.createProjectLinks(Seq(12345L, 12346L, 12347L, 12348L, 12349L, 12350L, 12351L), project.id, roadNumber = 9999, roadPartNumber = 1, track = Track.RightSide, discontinuity = Discontinuity.Continuous, roadType = RoadType.PublicRoad, roadLinkSource = LinkGeomSource.NormalLinkInterface, roadEly = 8L, user = "test", roadName = "road name")
      when(mockRoadLinkService.getRoadLinksByLinkIdsFromVVH(Set(12352L, 12353L, 12354L))).thenReturn(Seq(pl8, pl9, pl10).map(toRoadLink))
      projectService.createProjectLinks(Seq(12352L, 12353L, 12354L), project.id, roadNumber = 9999, roadPartNumber = 1, track = Track.LeftSide, discontinuity = Discontinuity.Continuous, roadType = RoadType.PublicRoad, roadLinkSource = LinkGeomSource.NormalLinkInterface, roadEly = 8L, user = "test", roadName = "road name")
      when(mockRoadLinkService.getRoadLinksByLinkIdsFromVVH(Set(12355L, 12356L, 12357L))).thenReturn(Seq(pl11, pl12, pl13).map(toRoadLink))
      projectService.createProjectLinks(Seq(12355L, 12356L, 12357L), project.id, 9999, 1, Track.LeftSide, Discontinuity.Continuous, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, 8L, "test", "road name")

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
        Roadway(raId, roadwayNumber1, roadNumber, roadPartNumber, RoadType.PublicRoad, Track.Combined, Discontinuity.EndOfRoad,
          0L, 20L, reversed = false, DateTime.now(), None, "test_user", None, 8, NoTermination, startDate, None),
        //RightSide
        Roadway(raId + 1, roadwayNumber2, roadNumber, newRoadPartNumber, RoadType.PublicRoad, Track.Combined, Discontinuity.EndOfRoad,
          0L, 50L, reversed = false, DateTime.now(), None, "test_user", None, 8, NoTermination, startDate, None))

      val linearLocations = Seq(
        //        part1
        LinearLocation(linearLocationId, 1, 1000l, 0.0, 15.0, SideCode.TowardsDigitizing, 10000000000l,
          (None, None), Seq(Point(0.0, 0.0), Point(0.0, 15.0)), LinkGeomSource.ComplementaryLinkInterface,
          roadwayNumber1, Some(startDate), None),
        LinearLocation(linearLocationId + 1, 2, 2000l, 0.0, 5.0, SideCode.TowardsDigitizing, 10000000000l,
          (None, None),  Seq(Point(0.0, 15.0), Point(0.0, 20.0)), LinkGeomSource.ComplementaryLinkInterface,
          roadwayNumber1, Some(startDate), None),
        //part2
        LinearLocation(linearLocationId + 2, 1, 3000l, 0.0, 5.0, SideCode.TowardsDigitizing, 10000000000l,
          (None, None), Seq(Point(0.0, 20.0), Point(0.0, 25.0)), LinkGeomSource.ComplementaryLinkInterface,
          roadwayNumber2, Some(startDate), None),
        LinearLocation(linearLocationId + 3, 2, 4000l, 0.0, 45.0, SideCode.TowardsDigitizing, 10000000000l,
          (None, None), Seq(Point(0.0, 25.0), Point(0.0, 70.0)), LinkGeomSource.ComplementaryLinkInterface,
          roadwayNumber2, Some(startDate), None)
      )
      roadwayDAO.create(ra)
      linearLocationDAO.create(linearLocations)

      val rap = Project(0L, ProjectState.apply(1), "TestProject", "TestUser", DateTime.now(),
        "TestUser", DateTime.now(), DateTime.now(), "Some additional info",
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

      projectService.updateProjectLinks(project_id, lastLink, Seq(), LinkStatus.Transfer, "test",
        roadNumber, newRoadPartNumber, 0, None, 1, 5, Some(8L), reversed = false, None)
      val lengthOfTheTransferredPart = 5
      val lengthPart1 = linearLocations.filter(_.roadwayNumber == roadwayNumber1).map(_.endMValue).sum - linearLocations.filter(_.roadwayNumber == roadwayNumber1).map(_.startMValue).sum
      val lengthPart2 = linearLocations.filter(_.roadwayNumber == roadwayNumber2).map(_.endMValue).sum - linearLocations.filter(_.roadwayNumber == roadwayNumber2).map(_.startMValue).sum
      val newLengthOfTheRoadPart1 = lengthPart1 - lengthOfTheTransferredPart
      val newLengthOfTheRoadPart2 = lengthPart2 + lengthOfTheTransferredPart

      val reservation = projectReservedPartDAO.fetchReservedRoadParts(project_id)
      val formed = projectReservedPartDAO.fetchFormedRoadParts(project_id)

      reservation.filter(_.roadPartNumber == 1).head.addressLength should be(Some(ra.head.endAddrMValue))
      reservation.filter(_.roadPartNumber == 2).head.addressLength should be(Some(ra.last.endAddrMValue))

      formed.filter(_.roadPartNumber == 1).head.newLength should be(Some(newLengthOfTheRoadPart1))
      formed.filter(_.roadPartNumber == 2).head.newLength should be(Some(lengthOfTheTransferredPart))

      val roadWay_2 = roadwayDAO.fetchAllBySection(roadNumber, newRoadPartNumber)
      mockForProject(project_id, roadwayAddressMapper.getRoadAddressesByLinearLocation(linearLocationDAO.fetchByRoadways(roadWay_2.map(_.roadwayNumber).toSet)).map(toProjectLink(project)))

      val projectLinksSet = projectLinkDAO.fetchProjectLinks(project_id).filter(_.roadPartNumber == 2).map(_.id).toSet
      projectService.updateProjectLinks(project_id, projectLinksSet, Seq(), LinkStatus.Transfer, "test",
        roadNumber, newRoadPartNumber, 0, None, 1, 5, Some(1L), reversed = false, None)

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

  test("Test projectService.createProjectLinks() discontinuity assignment When creating some links Then the Discontinuity of the last (with bigger endAddrM) new created links should be the one the user gave and should not change any other link discontinuity that was previosly given.") {
    runWithRollback {

      val rap = Project(0L, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("1901-01-01"),
        "TestUser", DateTime.parse("1901-01-01"), DateTime.now(), "Some additional info",
        Seq(), Seq(), None)

      val linkAfterRoundabout = ProjectLink(-1000L, 9999L, 1L, Track.apply(0), Discontinuity.EndOfRoad, 0L, 0L, 0L, 0L, None, None,
        None, 12345L, 0.0, 5.0, SideCode.TowardsDigitizing, (None, None),
        Seq(Point(15.0, 0.0), Point(20.0, 0.0)), 0L, LinkStatus.New, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(Seq(Point(15.0, 0.0), Point(20.0, 0.0))), 0L, 0, 0, reversed = false,
        None, 86400L)
      val linkBeforeRoundaboutMinorDiscontinuous = ProjectLink(-1000L, 9999L, 1L, Track.apply(0), Discontinuity.MinorDiscontinuity, 0L, 0L, 0L, 0L, None, None,
        None, 12346L, 0.0, 5.0, SideCode.TowardsDigitizing, (None, None),
        Seq(Point(5.0, 0.0), Point(15.0, 0.0)), 0L, LinkStatus.New, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(Seq(Point(5.0, 0.0), Point(15.0, 0.0))), 0L, 0, 0, reversed = false,
        None, 86400L)
      val linkBeforeRoundaboutContinuous = ProjectLink(-1000L, 9999L, 1L, Track.apply(0), Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None,
        None, 12347L, 0.0, 5.0, SideCode.TowardsDigitizing, (None, None),
        Seq(Point(0.0, 0.0), Point(5.0, 0.0)), 0L, LinkStatus.New, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(Seq(Point(0.0, 0.0), Point(5.0, 0.0))), 0L, 0, 0, reversed = false,
        None, 86400L)


      val project = projectService.createRoadLinkProject(rap)
      when(mockRoadLinkService.getRoadLinksByLinkIdsFromVVH(any[Set[Long]])).thenReturn(Seq(linkAfterRoundabout).map(toRoadLink))
      projectService.createProjectLinks(Seq(12345L), project.id, 9999, 1, Track.Combined, Discontinuity.EndOfRoad, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, 8L, "test", "road name")
      when(mockRoadLinkService.getRoadLinksByLinkIdsFromVVH(any[Set[Long]])).thenReturn(Seq(linkBeforeRoundaboutMinorDiscontinuous, linkBeforeRoundaboutContinuous).map(toRoadLink))
      projectService.createProjectLinks(Seq(12347L, 12346L), project.id, 9999, 1, Track.Combined, Discontinuity.MinorDiscontinuity, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, 8L, "test", "road name")

      val afterAllProjectLinksCreation = projectLinkDAO.fetchProjectLinks(project.id).sortBy(_.startAddrMValue)

      afterAllProjectLinksCreation.filter(_.linkId == 12345L).head.discontinuity should be (Discontinuity.EndOfRoad)
      afterAllProjectLinksCreation.filter(_.linkId == 12346L).head.discontinuity should be (Discontinuity.MinorDiscontinuity)
      afterAllProjectLinksCreation.filter(_.linkId == 12347L).head.discontinuity should be (Discontinuity.Continuous)
    }
  }

  test("Test updateRoadwaysAndLinearLocationsWithProjectLinks When VIITE-2236 situation Then don't violate unique constraint on roadway_point table.") {
    runWithRollback {
      sqlu"""Insert into LINK (ID,SOURCE,ADJUSTED_TIMESTAMP,CREATED_TIME) values ('568121','4','1446398762000',to_timestamp('27.12.2019 13:12:50','DD.MM.RRRR HH24:MI:SS'))""".execute
      sqlu"""Insert into LINK (ID,SOURCE,ADJUSTED_TIMESTAMP,CREATED_TIME) values ('7256596','4','1498959782000',to_timestamp('27.12.2019 13:12:50','DD.MM.RRRR HH24:MI:SS'))""".execute
      sqlu"""Insert into LINK (ID,SOURCE,ADJUSTED_TIMESTAMP,CREATED_TIME) values ('7256590','4','1498959782000',to_timestamp('27.12.2019 13:12:50','DD.MM.RRRR HH24:MI:SS'))""".execute
      sqlu"""Insert into LINK (ID,SOURCE,ADJUSTED_TIMESTAMP,CREATED_TIME) values ('7256584','4','1498959782000',to_timestamp('27.12.2019 13:12:50','DD.MM.RRRR HH24:MI:SS'))""".execute
      sqlu"""Insert into LINK (ID,SOURCE,ADJUSTED_TIMESTAMP,CREATED_TIME) values ('7256586','4','1503961971000',to_timestamp('27.12.2019 13:12:50','DD.MM.RRRR HH24:MI:SS'))""".execute
      sqlu"""Insert into LINK (ID,SOURCE,ADJUSTED_TIMESTAMP,CREATED_TIME) values ('7256594','4','1533681057000',to_timestamp('27.12.2019 13:12:50','DD.MM.RRRR HH24:MI:SS'))""".execute
      sqlu"""Insert into LINK (ID,SOURCE,ADJUSTED_TIMESTAMP,CREATED_TIME) values ('568164','4','1449097206000',to_timestamp('27.12.2019 13:12:50','DD.MM.RRRR HH24:MI:SS'))""".execute
      sqlu"""Insert into LINK (ID,SOURCE,ADJUSTED_TIMESTAMP,CREATED_TIME) values ('568122','4','1449097206000',to_timestamp('27.12.2019 13:12:50','DD.MM.RRRR HH24:MI:SS'))""".execute

      sqlu"""Insert into ROADWAY (ID,ROADWAY_NUMBER,ROAD_NUMBER,ROAD_PART_NUMBER,TRACK,START_ADDR_M,END_ADDR_M,REVERSED,DISCONTINUITY,START_DATE,END_DATE,CREATED_BY,CREATED_TIME,ROAD_TYPE,ELY,TERMINATED,VALID_FROM,VALID_TO) values ('1052907','40998','22006','12','0','0','215','0','5',to_date('01.01.2017','DD.MM.RRRR'),null,'import',to_timestamp('27.12.2019 13:12:51','DD.MM.RRRR HH24:MI:SS'),'1','2','0',to_date('28.12.2016','DD.MM.RRRR'),null)""".execute
      sqlu"""Insert into ROADWAY (ID,ROADWAY_NUMBER,ROAD_NUMBER,ROAD_PART_NUMBER,TRACK,START_ADDR_M,END_ADDR_M,REVERSED,DISCONTINUITY,START_DATE,END_DATE,CREATED_BY,CREATED_TIME,ROAD_TYPE,ELY,TERMINATED,VALID_FROM,VALID_TO) values ('1052911','40998','22006','12','0','0','215','0','2',to_date('01.01.1989','DD.MM.RRRR'),to_date('31.12.2016','DD.MM.RRRR'),'import',to_timestamp('27.12.2019 13:12:51','DD.MM.RRRR HH24:MI:SS'),'1','2','0',to_date('28.12.2016','DD.MM.RRRR'),null)""".execute
      sqlu"""Insert into ROADWAY (ID,ROADWAY_NUMBER,ROAD_NUMBER,ROAD_PART_NUMBER,TRACK,START_ADDR_M,END_ADDR_M,REVERSED,DISCONTINUITY,START_DATE,END_DATE,CREATED_BY,CREATED_TIME,ROAD_TYPE,ELY,TERMINATED,VALID_FROM,VALID_TO) values ('1052912','40999','22006','12','0','215','216','0','2',to_date('01.01.1989','DD.MM.RRRR'),to_date('30.12.1997','DD.MM.RRRR'),'import',to_timestamp('27.12.2019 13:12:51','DD.MM.RRRR HH24:MI:SS'),'1','2','1',to_date('31.01.2013','DD.MM.RRRR'),null)""".execute
      sqlu"""Insert into ROADWAY (ID,ROADWAY_NUMBER,ROAD_NUMBER,ROAD_PART_NUMBER,TRACK,START_ADDR_M,END_ADDR_M,REVERSED,DISCONTINUITY,START_DATE,END_DATE,CREATED_BY,CREATED_TIME,ROAD_TYPE,ELY,TERMINATED,VALID_FROM,VALID_TO) values ('1052910','41000','22006','34','0','0','310','0','2',to_date('01.01.1989','DD.MM.RRRR'),null,'import',to_timestamp('27.12.2019 13:12:51','DD.MM.RRRR HH24:MI:SS'),'1','2','0',to_date('14.06.2016','DD.MM.RRRR'),null)""".execute
      sqlu"""Insert into ROADWAY (ID,ROADWAY_NUMBER,ROAD_NUMBER,ROAD_PART_NUMBER,TRACK,START_ADDR_M,END_ADDR_M,REVERSED,DISCONTINUITY,START_DATE,END_DATE,CREATED_BY,CREATED_TIME,ROAD_TYPE,ELY,TERMINATED,VALID_FROM,VALID_TO) values ('1052909','6446223','22006','23','0','0','45','0','2',to_date('20.05.2007','DD.MM.RRRR'),null,'import',to_timestamp('27.12.2019 13:12:51','DD.MM.RRRR HH24:MI:SS'),'1','2','0',to_date('14.06.2016','DD.MM.RRRR'),null)""".execute
      sqlu"""Insert into ROADWAY (ID,ROADWAY_NUMBER,ROAD_NUMBER,ROAD_PART_NUMBER,TRACK,START_ADDR_M,END_ADDR_M,REVERSED,DISCONTINUITY,START_DATE,END_DATE,CREATED_BY,CREATED_TIME,ROAD_TYPE,ELY,TERMINATED,VALID_FROM,VALID_TO) values ('1052906','6446225','22006','45','0','0','248','0','1',to_date('20.05.2007','DD.MM.RRRR'),null,'import',to_timestamp('27.12.2019 13:12:51','DD.MM.RRRR HH24:MI:SS'),'1','2','0',to_date('25.01.2017','DD.MM.RRRR'),null)""".execute
      sqlu"""Insert into ROADWAY (ID,ROADWAY_NUMBER,ROAD_NUMBER,ROAD_PART_NUMBER,TRACK,START_ADDR_M,END_ADDR_M,REVERSED,DISCONTINUITY,START_DATE,END_DATE,CREATED_BY,CREATED_TIME,ROAD_TYPE,ELY,TERMINATED,VALID_FROM,VALID_TO) values ('1052908','166883589','22006','12','0','215','295','0','2',to_date('01.01.2017','DD.MM.RRRR'),null,'import',to_timestamp('27.12.2019 13:12:51','DD.MM.RRRR HH24:MI:SS'),'1','2','0',to_date('28.12.2016','DD.MM.RRRR'),null)""".execute

      sqlu"""Insert into LINEAR_LOCATION (ID,ROADWAY_NUMBER,ORDER_NUMBER,LINK_ID,START_MEASURE,END_MEASURE,SIDE,GEOMETRY,VALID_FROM,VALID_TO,CREATED_BY,CREATED_TIME) values ('1039046',  '6446225','1','7256596',      0, 248.793,'3',MDSYS.SDO_GEOMETRY(4002, 3067, NULL, MDSYS.SDO_ELEM_INFO_ARRAY(1, 2, 1), MDSYS.SDO_ORDINATE_ARRAY(267357.266, 6789458.693, 0, 248.793, 267131.966, 6789520.79, 0, 248.793)), to_date('25.01.2017','DD.MM.RRRR'),null,'import',to_timestamp('27.12.2019 13:12:51','DD.MM.RRRR HH24:MI:SS'))""".execute
      sqlu"""Insert into LINEAR_LOCATION (ID,ROADWAY_NUMBER,ORDER_NUMBER,LINK_ID,START_MEASURE,END_MEASURE,SIDE,GEOMETRY,VALID_FROM,VALID_TO,CREATED_BY,CREATED_TIME) values ('1039047',    '40998','1', '568164',      0,   7.685,'3',MDSYS.SDO_GEOMETRY(4002, 3067, NULL, MDSYS.SDO_ELEM_INFO_ARRAY(1, 2, 1), MDSYS.SDO_ORDINATE_ARRAY(267444.126, 6789234.9, 0, 7.685, 267437.206, 6789238.158, 0, 7.685)),      to_date('28.12.2016','DD.MM.RRRR'),null,'import',to_timestamp('27.12.2019 13:12:51','DD.MM.RRRR HH24:MI:SS'))""".execute
      sqlu"""Insert into LINEAR_LOCATION (ID,ROADWAY_NUMBER,ORDER_NUMBER,LINK_ID,START_MEASURE,END_MEASURE,SIDE,GEOMETRY,VALID_FROM,VALID_TO,CREATED_BY,CREATED_TIME) values ('1039048',    '40998','2','7256584',      0, 171.504,'2',MDSYS.SDO_GEOMETRY(4002, 3067, NULL, MDSYS.SDO_ELEM_INFO_ARRAY(1, 2, 1), MDSYS.SDO_ORDINATE_ARRAY(267444.126, 6789234.9, 0, 171.504, 267597.461, 6789260.633, 0, 171.504)),  to_date('28.12.2016','DD.MM.RRRR'),null,'import',to_timestamp('27.12.2019 13:12:51','DD.MM.RRRR HH24:MI:SS'))""".execute
      sqlu"""Insert into LINEAR_LOCATION (ID,ROADWAY_NUMBER,ORDER_NUMBER,LINK_ID,START_MEASURE,END_MEASURE,SIDE,GEOMETRY,VALID_FROM,VALID_TO,CREATED_BY,CREATED_TIME) values ('1039049',    '40998','3','7256586',      0,  38.688,'2',MDSYS.SDO_GEOMETRY(4002, 3067, NULL, MDSYS.SDO_ELEM_INFO_ARRAY(1, 2, 1), MDSYS.SDO_ORDINATE_ARRAY(267597.461, 6789260.633, 0, 38.688, 267518.603, 6789333.458, 0, 38.688)),  to_date('28.12.2016','DD.MM.RRRR'),null,'import',to_timestamp('27.12.2019 13:12:51','DD.MM.RRRR HH24:MI:SS'))""".execute
      sqlu"""Insert into LINEAR_LOCATION (ID,ROADWAY_NUMBER,ORDER_NUMBER,LINK_ID,START_MEASURE,END_MEASURE,SIDE,GEOMETRY,VALID_FROM,VALID_TO,CREATED_BY,CREATED_TIME) values ('1039050','166883589','1','7256586', 38.688, 120.135,'2',MDSYS.SDO_GEOMETRY(4002, 3067, NULL, MDSYS.SDO_ELEM_INFO_ARRAY(1, 2, 1), MDSYS.SDO_ORDINATE_ARRAY(267597.461, 6789260.633, 0, 120.135, 267518.603, 6789333.458, 0, 120.135)),to_date('28.12.2016','DD.MM.RRRR'),null,'import',to_timestamp('27.12.2019 13:12:51','DD.MM.RRRR HH24:MI:SS'))""".execute
      sqlu"""Insert into LINEAR_LOCATION (ID,ROADWAY_NUMBER,ORDER_NUMBER,LINK_ID,START_MEASURE,END_MEASURE,SIDE,GEOMETRY,VALID_FROM,VALID_TO,CREATED_BY,CREATED_TIME) values ('1039051',  '6446223','1','7256594',      0,  44.211,'3',MDSYS.SDO_GEOMETRY(4002, 3067, NULL, MDSYS.SDO_ELEM_INFO_ARRAY(1, 2, 1), MDSYS.SDO_ORDINATE_ARRAY(267597.461, 6789260.633, 0, 44.211, 267633.898, 6789283.726, 0, 44.211)),  to_date('14.06.2016','DD.MM.RRRR'),null,'import',to_timestamp('27.12.2019 13:12:51','DD.MM.RRRR HH24:MI:SS'))""".execute
      sqlu"""Insert into LINEAR_LOCATION (ID,ROADWAY_NUMBER,ORDER_NUMBER,LINK_ID,START_MEASURE,END_MEASURE,SIDE,GEOMETRY,VALID_FROM,VALID_TO,CREATED_BY,CREATED_TIME) values ('1039052',    '41000','1','7256590',      0, 130.538,'2',MDSYS.SDO_GEOMETRY(4002, 3067, NULL, MDSYS.SDO_ELEM_INFO_ARRAY(1, 2, 1), MDSYS.SDO_ORDINATE_ARRAY(267431.685, 6789375.9, 0, 130.538, 267357.266, 6789458.693, 0, 130.538)),  to_date('14.06.2016','DD.MM.RRRR'),null,'import',to_timestamp('27.12.2019 13:12:51','DD.MM.RRRR HH24:MI:SS'))""".execute
      sqlu"""Insert into LINEAR_LOCATION (ID,ROADWAY_NUMBER,ORDER_NUMBER,LINK_ID,START_MEASURE,END_MEASURE,SIDE,GEOMETRY,VALID_FROM,VALID_TO,CREATED_BY,CREATED_TIME) values ('1039053',    '41000','2', '568121',      0, 177.547,'3',MDSYS.SDO_GEOMETRY(4002, 3067, NULL, MDSYS.SDO_ELEM_INFO_ARRAY(1, 2, 1), MDSYS.SDO_ORDINATE_ARRAY(267525.375, 6789455.936, 0, 177.547, 267357.266, 6789458.693, 0, 177.547)),to_date('14.06.2016','DD.MM.RRRR'),null,'import',to_timestamp('27.12.2019 13:12:51','DD.MM.RRRR HH24:MI:SS'))""".execute
      sqlu"""Insert into LINEAR_LOCATION (ID,ROADWAY_NUMBER,ORDER_NUMBER,LINK_ID,START_MEASURE,END_MEASURE,SIDE,GEOMETRY,VALID_FROM,VALID_TO,CREATED_BY,CREATED_TIME) values ('1039054',    '41000','3', '568122',      0,   9.514,'3',MDSYS.SDO_GEOMETRY(4002, 3067, NULL, MDSYS.SDO_ELEM_INFO_ARRAY(1, 2, 1), MDSYS.SDO_ORDINATE_ARRAY(267534.612, 6789453.659, 0, 9.514, 267525.375, 6789455.936, 0, 9.514)),    to_date('14.06.2016','DD.MM.RRRR'),null,'import',to_timestamp('27.12.2019 13:12:51','DD.MM.RRRR HH24:MI:SS'))""".execute

      sqlu"""Insert into ROADWAY_POINT (ID,ROADWAY_NUMBER,ADDR_M,CREATED_BY,CREATED_TIME,MODIFIED_BY,MODIFIED_TIME) values ('1019231','6446225','0','import',to_timestamp('27.12.2019 13:12:50','DD.MM.RRRR HH24:MI:SSXFF'),'import',to_timestamp('27.12.2019 13:12:50','DD.MM.RRRR HH24:MI:SS'))""".execute
      sqlu"""Insert into ROADWAY_POINT (ID,ROADWAY_NUMBER,ADDR_M,CREATED_BY,CREATED_TIME,MODIFIED_BY,MODIFIED_TIME) values ('1019232','6446225','248','import',to_timestamp('27.12.2019 13:12:50','DD.MM.RRRR HH24:MI:SSXFF'),'import',to_timestamp('27.12.2019 13:12:50','DD.MM.RRRR HH24:MI:SS'))""".execute
      sqlu"""Insert into ROADWAY_POINT (ID,ROADWAY_NUMBER,ADDR_M,CREATED_BY,CREATED_TIME,MODIFIED_BY,MODIFIED_TIME) values ('1019233','40998','0','import',to_timestamp('27.12.2019 13:12:51','DD.MM.RRRR HH24:MI:SSXFF'),'import',to_timestamp('27.12.2019 13:12:51','DD.MM.RRRR HH24:MI:SS'))""".execute
      sqlu"""Insert into ROADWAY_POINT (ID,ROADWAY_NUMBER,ADDR_M,CREATED_BY,CREATED_TIME,MODIFIED_BY,MODIFIED_TIME) values ('1019234','166883589','295','import',to_timestamp('27.12.2019 13:12:51','DD.MM.RRRR HH24:MI:SSXFF'),'import',to_timestamp('27.12.2019 13:12:51','DD.MM.RRRR HH24:MI:SS'))""".execute
      sqlu"""Insert into ROADWAY_POINT (ID,ROADWAY_NUMBER,ADDR_M,CREATED_BY,CREATED_TIME,MODIFIED_BY,MODIFIED_TIME) values ('1019235','6446223','0','import',to_timestamp('27.12.2019 13:12:51','DD.MM.RRRR HH24:MI:SSXFF'),'import',to_timestamp('27.12.2019 13:12:51','DD.MM.RRRR HH24:MI:SS'))""".execute
      sqlu"""Insert into ROADWAY_POINT (ID,ROADWAY_NUMBER,ADDR_M,CREATED_BY,CREATED_TIME,MODIFIED_BY,MODIFIED_TIME) values ('1019236','6446223','45','import',to_timestamp('27.12.2019 13:12:51','DD.MM.RRRR HH24:MI:SSXFF'),'import',to_timestamp('27.12.2019 13:12:51','DD.MM.RRRR HH24:MI:SS'))""".execute
      sqlu"""Insert into ROADWAY_POINT (ID,ROADWAY_NUMBER,ADDR_M,CREATED_BY,CREATED_TIME,MODIFIED_BY,MODIFIED_TIME) values ('1019237','41000','0','import',to_timestamp('27.12.2019 13:12:51','DD.MM.RRRR HH24:MI:SSXFF'),'import',to_timestamp('27.12.2019 13:12:51','DD.MM.RRRR HH24:MI:SS'))""".execute
      sqlu"""Insert into ROADWAY_POINT (ID,ROADWAY_NUMBER,ADDR_M,CREATED_BY,CREATED_TIME,MODIFIED_BY,MODIFIED_TIME) values ('1019238','41000','310','import',to_timestamp('27.12.2019 13:12:51','DD.MM.RRRR HH24:MI:SSXFF'),'import',to_timestamp('27.12.2019 13:12:51','DD.MM.RRRR HH24:MI:SS'))""".execute
      sqlu"""Insert into ROADWAY_POINT (ID,ROADWAY_NUMBER,ADDR_M,CREATED_BY,CREATED_TIME,MODIFIED_BY,MODIFIED_TIME) values ('1019239','40998','215','k567997',to_timestamp('27.12.2019 13:26:55','DD.MM.RRRR HH24:MI:SSXFF'),'k567997',to_timestamp('27.12.2019 13:26:55','DD.MM.RRRR HH24:MI:SS'))""".execute
      sqlu"""Insert into ROADWAY_POINT (ID,ROADWAY_NUMBER,ADDR_M,CREATED_BY,CREATED_TIME,MODIFIED_BY,MODIFIED_TIME) values ('1019240','40998','177','k567997',to_timestamp('27.12.2019 13:26:55','DD.MM.RRRR HH24:MI:SSXFF'),'k567997',to_timestamp('27.12.2019 13:26:55','DD.MM.RRRR HH24:MI:SS'))""".execute
      sqlu"""Insert into ROADWAY_POINT (ID,ROADWAY_NUMBER,ADDR_M,CREATED_BY,CREATED_TIME,MODIFIED_BY,MODIFIED_TIME) values ('1019241','41000','275','k567997',to_timestamp('27.12.2019 13:26:55','DD.MM.RRRR HH24:MI:SSXFF'),'k567997',to_timestamp('27.12.2019 13:26:55','DD.MM.RRRR HH24:MI:SS'))""".execute
      sqlu"""Insert into ROADWAY_POINT (ID,ROADWAY_NUMBER,ADDR_M,CREATED_BY,CREATED_TIME,MODIFIED_BY,MODIFIED_TIME) values ('1019242','40998','8','k567997',to_timestamp('27.12.2019 13:26:55','DD.MM.RRRR HH24:MI:SSXFF'),'k567997',to_timestamp('27.12.2019 13:26:55','DD.MM.RRRR HH24:MI:SS'))""".execute
      sqlu"""Insert into ROADWAY_POINT (ID,ROADWAY_NUMBER,ADDR_M,CREATED_BY,CREATED_TIME,MODIFIED_BY,MODIFIED_TIME) values ('1019243','41000','300','k567997',to_timestamp('27.12.2019 13:26:55','DD.MM.RRRR HH24:MI:SSXFF'),'k567997',to_timestamp('27.12.2019 13:26:55','DD.MM.RRRR HH24:MI:SS'))""".execute
      sqlu"""Insert into ROADWAY_POINT (ID,ROADWAY_NUMBER,ADDR_M,CREATED_BY,CREATED_TIME,MODIFIED_BY,MODIFIED_TIME) values ('1019244','41000','127','k567997',to_timestamp('27.12.2019 13:26:55','DD.MM.RRRR HH24:MI:SSXFF'),'k567997',to_timestamp('27.12.2019 13:26:55','DD.MM.RRRR HH24:MI:SS'))""".execute

      sqlu"""Insert into CALIBRATION_POINT (ID,ROADWAY_POINT_ID,LINK_ID,START_END,TYPE,VALID_FROM,VALID_TO,CREATED_BY,CREATED_TIME) values ('1020345','1019231','7256596','0','2',to_date('27.12.2019','DD.MM.RRRR'),null,'import',to_timestamp('27.12.2019 13:12:51','DD.MM.RRRR HH24:MI:SS'))""".execute
      sqlu"""Insert into CALIBRATION_POINT (ID,ROADWAY_POINT_ID,LINK_ID,START_END,TYPE,VALID_FROM,VALID_TO,CREATED_BY,CREATED_TIME) values ('1020346','1019232','7256596','1','2',to_date('27.12.2019','DD.MM.RRRR'),null,'import',to_timestamp('27.12.2019 13:12:51','DD.MM.RRRR HH24:MI:SS'))""".execute
      sqlu"""Insert into CALIBRATION_POINT (ID,ROADWAY_POINT_ID,LINK_ID,START_END,TYPE,VALID_FROM,VALID_TO,CREATED_BY,CREATED_TIME) values ('1020347','1019233','568164','0','2',to_date('27.12.2019','DD.MM.RRRR'),null,'import',to_timestamp('27.12.2019 13:12:51','DD.MM.RRRR HH24:MI:SS'))""".execute
      sqlu"""Insert into CALIBRATION_POINT (ID,ROADWAY_POINT_ID,LINK_ID,START_END,TYPE,VALID_FROM,VALID_TO,CREATED_BY,CREATED_TIME) values ('1020348','1019234','7256586','1','2',to_date('27.12.2019','DD.MM.RRRR'),null,'import',to_timestamp('27.12.2019 13:12:51','DD.MM.RRRR HH24:MI:SS'))""".execute
      sqlu"""Insert into CALIBRATION_POINT (ID,ROADWAY_POINT_ID,LINK_ID,START_END,TYPE,VALID_FROM,VALID_TO,CREATED_BY,CREATED_TIME) values ('1020349','1019235','7256594','0','2',to_date('27.12.2019','DD.MM.RRRR'),null,'import',to_timestamp('27.12.2019 13:12:51','DD.MM.RRRR HH24:MI:SS'))""".execute
      sqlu"""Insert into CALIBRATION_POINT (ID,ROADWAY_POINT_ID,LINK_ID,START_END,TYPE,VALID_FROM,VALID_TO,CREATED_BY,CREATED_TIME) values ('1020350','1019236','7256594','1','2',to_date('27.12.2019','DD.MM.RRRR'),null,'import',to_timestamp('27.12.2019 13:12:51','DD.MM.RRRR HH24:MI:SS'))""".execute
      sqlu"""Insert into CALIBRATION_POINT (ID,ROADWAY_POINT_ID,LINK_ID,START_END,TYPE,VALID_FROM,VALID_TO,CREATED_BY,CREATED_TIME) values ('1020351','1019237','7256590','0','2',to_date('27.12.2019','DD.MM.RRRR'),null,'import',to_timestamp('27.12.2019 13:12:51','DD.MM.RRRR HH24:MI:SS'))""".execute
      sqlu"""Insert into CALIBRATION_POINT (ID,ROADWAY_POINT_ID,LINK_ID,START_END,TYPE,VALID_FROM,VALID_TO,CREATED_BY,CREATED_TIME) values ('1020352','1019238','568122','1','2',to_date('27.12.2019','DD.MM.RRRR'),null,'import',to_timestamp('27.12.2019 13:12:51','DD.MM.RRRR HH24:MI:SS'))""".execute

      sqlu"""Insert into PROJECT (ID,STATE,NAME,CREATED_BY,CREATED_DATE,MODIFIED_BY,MODIFIED_DATE,ADD_INFO,START_DATE,STATUS_INFO,TR_ID,COORD_X,COORD_Y,ZOOM) values ('1000351','2','aa','silari',to_date('27.12.2019','DD.MM.RRRR'),'-',to_date('27.12.2019','DD.MM.RRRR'),null,to_date('01.01.2020','DD.MM.RRRR'),null,'1000109',267287.82,6789454.18,'12')""".execute

      sqlu"""Insert into PROJECT_RESERVED_ROAD_PART (ID,ROAD_NUMBER,ROAD_PART_NUMBER,PROJECT_ID,CREATED_BY) values ('1000366','22006','56','1000351','-')""".execute
      sqlu"""Insert into PROJECT_RESERVED_ROAD_PART (ID,ROAD_NUMBER,ROAD_PART_NUMBER,PROJECT_ID,CREATED_BY) values ('1000365','22006','68','1000351','-')""".execute
      sqlu"""Insert into PROJECT_RESERVED_ROAD_PART (ID,ROAD_NUMBER,ROAD_PART_NUMBER,PROJECT_ID,CREATED_BY) values ('1000352','22006','12','1000351','silari')""".execute
      sqlu"""Insert into PROJECT_RESERVED_ROAD_PART (ID,ROAD_NUMBER,ROAD_PART_NUMBER,PROJECT_ID,CREATED_BY) values ('1000353','22006','23','1000351','silari')""".execute
      sqlu"""Insert into PROJECT_RESERVED_ROAD_PART (ID,ROAD_NUMBER,ROAD_PART_NUMBER,PROJECT_ID,CREATED_BY) values ('1000354','22006','34','1000351','silari')""".execute
      sqlu"""Insert into PROJECT_RESERVED_ROAD_PART (ID,ROAD_NUMBER,ROAD_PART_NUMBER,PROJECT_ID,CREATED_BY) values ('1000369','22006','24','1000351','-')""".execute
      sqlu"""Insert into PROJECT_RESERVED_ROAD_PART (ID,ROAD_NUMBER,ROAD_PART_NUMBER,PROJECT_ID,CREATED_BY) values ('1000355','22006','45','1000351','silari')""".execute
      sqlu"""Insert into PROJECT_RESERVED_ROAD_PART (ID,ROAD_NUMBER,ROAD_PART_NUMBER,PROJECT_ID,CREATED_BY) values ('1000368','22006','67','1000351','-')""".execute

      sqlu"""Insert into PROJECT_LINK (ID,PROJECT_ID,TRACK,DISCONTINUITY_TYPE,ROAD_NUMBER,ROAD_PART_NUMBER,START_ADDR_M,END_ADDR_M,CREATED_BY,MODIFIED_BY,CREATED_DATE,MODIFIED_DATE,STATUS,CALIBRATION_POINTS,ROAD_TYPE,ROADWAY_ID,LINEAR_LOCATION_ID,CONNECTED_LINK_ID,ELY,REVERSED,SIDE,START_MEASURE,END_MEASURE,LINK_ID,ADJUSTED_TIMESTAMP,LINK_SOURCE,CALIBRATION_POINTS_SOURCE,GEOMETRY,ORIGINAL_START_ADDR_M,ORIGINAL_END_ADDR_M,ROADWAY_NUMBER) values ('1000356','1000351','0','2','22006','67','169','177','silari','silari',to_date('27.12.2019','DD.MM.RRRR'),to_date('27.12.2019','DD.MM.RRRR'),'3','1','1','1052907','1039047',null,'2','1','2',0,7.685,'568164','1449097206000','4','2',MDSYS.SDO_GEOMETRY(4002, 3067, NULL, MDSYS.SDO_ELEM_INFO_ARRAY(1, 2, 1), MDSYS.SDO_ORDINATE_ARRAY(267444.126, 6789234.9, 53.176999999996, 267440.935, 6789235.99, 53.2259999999951, 267437.206395653, 6789238.15776997, 53.2499974535623)),'0','8','166883765')""".execute
      sqlu"""Insert into PROJECT_LINK (ID,PROJECT_ID,TRACK,DISCONTINUITY_TYPE,ROAD_NUMBER,ROAD_PART_NUMBER,START_ADDR_M,END_ADDR_M,CREATED_BY,MODIFIED_BY,CREATED_DATE,MODIFIED_DATE,STATUS,CALIBRATION_POINTS,ROAD_TYPE,ROADWAY_ID,LINEAR_LOCATION_ID,CONNECTED_LINK_ID,ELY,REVERSED,SIDE,START_MEASURE,END_MEASURE,LINK_ID,ADJUSTED_TIMESTAMP,LINK_SOURCE,CALIBRATION_POINTS_SOURCE,GEOMETRY,ORIGINAL_START_ADDR_M,ORIGINAL_END_ADDR_M,ROADWAY_NUMBER) values ('1000357','1000351','0','5','22006','67','0','169','silari','silari',to_date('27.12.2019','DD.MM.RRRR'),to_date('27.12.2019','DD.MM.RRRR'),'3','3','1','1052907','1039048',null,'2','1','3',0,171.504,'7256584','1498959782000','4','1',MDSYS.SDO_GEOMETRY(4002, 3067, NULL, MDSYS.SDO_ELEM_INFO_ARRAY(1, 2, 1), MDSYS.SDO_ORDINATE_ARRAY(267444.126, 6789234.9, 53.176999999996, 267464.548, 6789227.526, 52.8959999999934, 267496.884, 6789219.216, 52.2939999999944, 267515.938, 6789216.916, 51.7979999999952, 267535.906, 6789218.028, 51.1319999999978, 267556.73, 6789224.333, 50.304999999993, 267574.187, 6789234.039, 49.5789999999979, 267588.124, 6789247.565, 49.0240000000049, 267597.460867063, 6789260.63281394, 48.7730035736591)),'8','177','166883765')""".execute
      sqlu"""Insert into PROJECT_LINK (ID,PROJECT_ID,TRACK,DISCONTINUITY_TYPE,ROAD_NUMBER,ROAD_PART_NUMBER,START_ADDR_M,END_ADDR_M,CREATED_BY,MODIFIED_BY,CREATED_DATE,MODIFIED_DATE,STATUS,CALIBRATION_POINTS,ROAD_TYPE,ROADWAY_ID,LINEAR_LOCATION_ID,CONNECTED_LINK_ID,ELY,REVERSED,SIDE,START_MEASURE,END_MEASURE,LINK_ID,ADJUSTED_TIMESTAMP,LINK_SOURCE,CALIBRATION_POINTS_SOURCE,GEOMETRY,ORIGINAL_START_ADDR_M,ORIGINAL_END_ADDR_M,ROADWAY_NUMBER) values ('1000358','1000351','0','5','22006','56','80','118','silari','silari',to_date('27.12.2019','DD.MM.RRRR'),to_date('27.12.2019','DD.MM.RRRR'),'3','3','1','1052907','1039049',null,'2','1','3',0,38.688,'7256586','1503961971000','4','1',MDSYS.SDO_GEOMETRY(4002, 3067, NULL, MDSYS.SDO_ELEM_INFO_ARRAY(1, 2, 1), MDSYS.SDO_ORDINATE_ARRAY(267597.461, 6789260.633, 48.773000000001, 267597.534, 6789260.792, 48.7719999999972, 267600.106, 6789269.768, 48.6059999999998, 267600.106, 6789280.257, 48.4780000000028, 267597.713, 6789287.648, 48.4360000000015, 267591.642, 6789293.381, 48.4370000000054, 267589.345139337, 6789294.52943033, 48.4244527667907)),'177','215','166883761')""".execute
      sqlu"""Insert into PROJECT_LINK (ID,PROJECT_ID,TRACK,DISCONTINUITY_TYPE,ROAD_NUMBER,ROAD_PART_NUMBER,START_ADDR_M,END_ADDR_M,CREATED_BY,MODIFIED_BY,CREATED_DATE,MODIFIED_DATE,STATUS,CALIBRATION_POINTS,ROAD_TYPE,ROADWAY_ID,LINEAR_LOCATION_ID,CONNECTED_LINK_ID,ELY,REVERSED,SIDE,START_MEASURE,END_MEASURE,LINK_ID,ADJUSTED_TIMESTAMP,LINK_SOURCE,CALIBRATION_POINTS_SOURCE,GEOMETRY,ORIGINAL_START_ADDR_M,ORIGINAL_END_ADDR_M,ROADWAY_NUMBER) values ('1000359','1000351','0','5','22006','56','0','80','silari','silari',to_date('27.12.2019','DD.MM.RRRR'),to_date('27.12.2019','DD.MM.RRRR'),'3','2','1','1052908','1039050',null,'2','1','3',38.688,120.135,'7256586','1503961971000','4','2',MDSYS.SDO_GEOMETRY(4002, 3067, NULL, MDSYS.SDO_ELEM_INFO_ARRAY(1, 2, 1), MDSYS.SDO_ORDINATE_ARRAY(267589.345139337, 6789294.52943033, 48.4244527667907, 267578.828, 6789299.788, 48.3669999999984, 267559.269, 6789308.892, 48.2510000000038, 267546.792, 6789314.963, 48.2390000000014, 267533.979, 6789321.707, 48.2140000000072, 267524.2, 6789327.103, 48.226999999999, 267521.164, 6789329.463, 48.247000000003, 267518.603162863, 6789333.45774594, 48.2559994276524)),'215','295','166883589')""".execute
      sqlu"""Insert into PROJECT_LINK (ID,PROJECT_ID,TRACK,DISCONTINUITY_TYPE,ROAD_NUMBER,ROAD_PART_NUMBER,START_ADDR_M,END_ADDR_M,CREATED_BY,MODIFIED_BY,CREATED_DATE,MODIFIED_DATE,STATUS,CALIBRATION_POINTS,ROAD_TYPE,ROADWAY_ID,LINEAR_LOCATION_ID,CONNECTED_LINK_ID,ELY,REVERSED,SIDE,START_MEASURE,END_MEASURE,LINK_ID,ADJUSTED_TIMESTAMP,LINK_SOURCE,CALIBRATION_POINTS_SOURCE,GEOMETRY,ORIGINAL_START_ADDR_M,ORIGINAL_END_ADDR_M,ROADWAY_NUMBER) values ('1000360','1000351','0','1','22006','68','0','45','silari','silari',to_date('27.12.2019','DD.MM.RRRR'),to_date('27.12.2019','DD.MM.RRRR'),'3','3','1','1052909','1039051',null,'2','1','2',0,44.211,'7256594','1533681057000','4','2',MDSYS.SDO_GEOMETRY(4002, 3067, NULL, MDSYS.SDO_ELEM_INFO_ARRAY(1, 2, 1), MDSYS.SDO_ORDINATE_ARRAY(267597.461, 6789260.633, 48.773000000001, 267603.782, 6789267.752, 48.6589999999997, 267610.19, 6789273.485, 48.5580000000045, 267616.597, 6789277.869, 48.4689999999973, 267623.131, 6789280.868, 48.4400000000023, 267633.897953407, 6789283.72598763, 48.4409999956788)),'0','45','6446223')""".execute
      sqlu"""Insert into PROJECT_LINK (ID,PROJECT_ID,TRACK,DISCONTINUITY_TYPE,ROAD_NUMBER,ROAD_PART_NUMBER,START_ADDR_M,END_ADDR_M,CREATED_BY,MODIFIED_BY,CREATED_DATE,MODIFIED_DATE,STATUS,CALIBRATION_POINTS,ROAD_TYPE,ROADWAY_ID,LINEAR_LOCATION_ID,CONNECTED_LINK_ID,ELY,REVERSED,SIDE,START_MEASURE,END_MEASURE,LINK_ID,ADJUSTED_TIMESTAMP,LINK_SOURCE,CALIBRATION_POINTS_SOURCE,GEOMETRY,ORIGINAL_START_ADDR_M,ORIGINAL_END_ADDR_M,ROADWAY_NUMBER) values ('1000361','1000351','0','5','22006','12','0','127','silari','silari',to_date('27.12.2019','DD.MM.RRRR'),to_date('27.12.2019','DD.MM.RRRR'),'3','3','1','1052910','1039052',null,'2','0','2',0,130.538,'7256590','1498959782000','4','2',MDSYS.SDO_GEOMETRY(4002, 3067, NULL, MDSYS.SDO_ELEM_INFO_ARRAY(1, 2, 1), MDSYS.SDO_ORDINATE_ARRAY(267431.685, 6789375.9, 48.4470000000001, 267424.673, 6789383.39, 48.4180000000051, 267415.075, 6789389.356, 48.448000000004, 267401.067, 6789396.879, 48.5190000000002, 267384.985, 6789404.661, 48.6150000000052, 267366.307, 6789414.259, 48.6889999999985, 267356.079, 6789421.259, 48.6999999999971, 267351.522, 6789425.931, 48.7119999999995, 267349.187, 6789432.416, 48.7799999999988, 267348.928, 6789442.533, 48.9700000000012, 267352.041, 6789450.574, 49.1230000000069, 267357.266, 6789458.693, 49.3099999999977)),'0','127','166883763')""".execute
      sqlu"""Insert into PROJECT_LINK (ID,PROJECT_ID,TRACK,DISCONTINUITY_TYPE,ROAD_NUMBER,ROAD_PART_NUMBER,START_ADDR_M,END_ADDR_M,CREATED_BY,MODIFIED_BY,CREATED_DATE,MODIFIED_DATE,STATUS,CALIBRATION_POINTS,ROAD_TYPE,ROADWAY_ID,LINEAR_LOCATION_ID,CONNECTED_LINK_ID,ELY,REVERSED,SIDE,START_MEASURE,END_MEASURE,LINK_ID,ADJUSTED_TIMESTAMP,LINK_SOURCE,CALIBRATION_POINTS_SOURCE,GEOMETRY,ORIGINAL_START_ADDR_M,ORIGINAL_END_ADDR_M,ROADWAY_NUMBER) values ('1000362','1000351','0','5','22006','23','0','174','silari','silari',to_date('27.12.2019','DD.MM.RRRR'),to_date('27.12.2019','DD.MM.RRRR'),'3','2','1','1052910','1039053',null,'2','0','3',0,177.547,'568121','1446398762000','4','2',MDSYS.SDO_GEOMETRY(4002, 3067, NULL, MDSYS.SDO_ELEM_INFO_ARRAY(1, 2, 1), MDSYS.SDO_ORDINATE_ARRAY(267525.375, 6789455.936, 52.7309999999998, 267499.516, 6789463.571, 52.426999999996, 267458.911, 6789473.392, 52.0339999999997, 267426.281, 6789480.881, 51.4360000000015, 267403.97, 6789481.494, 50.7459999999992, 267378.849, 6789475.013, 49.9879999999976, 267357.54, 6789459.007, 49.3179999999993, 267357.266194778, 6789458.69322321, 49.3100056869441)),'127','301','166883762')""".execute
      sqlu"""Insert into PROJECT_LINK (ID,PROJECT_ID,TRACK,DISCONTINUITY_TYPE,ROAD_NUMBER,ROAD_PART_NUMBER,START_ADDR_M,END_ADDR_M,CREATED_BY,MODIFIED_BY,CREATED_DATE,MODIFIED_DATE,STATUS,CALIBRATION_POINTS,ROAD_TYPE,ROADWAY_ID,LINEAR_LOCATION_ID,CONNECTED_LINK_ID,ELY,REVERSED,SIDE,START_MEASURE,END_MEASURE,LINK_ID,ADJUSTED_TIMESTAMP,LINK_SOURCE,CALIBRATION_POINTS_SOURCE,GEOMETRY,ORIGINAL_START_ADDR_M,ORIGINAL_END_ADDR_M,ROADWAY_NUMBER) values ('1000363','1000351','0','2','22006','23','174','183','silari','silari',to_date('27.12.2019','DD.MM.RRRR'),to_date('27.12.2019','DD.MM.RRRR'),'3','1','1','1052910','1039054',null,'2','0','3',0,9.514,'568122','1449097206000','4','2',MDSYS.SDO_GEOMETRY(4002, 3067, NULL, MDSYS.SDO_ELEM_INFO_ARRAY(1, 2, 1), MDSYS.SDO_ORDINATE_ARRAY(267534.612, 6789453.659, 52.7939999999944, 267529.741, 6789454.905, 52.8300000000017, 267525.375, 6789455.936, 52.7309999999998)),'301','310','166883762')""".execute
      sqlu"""Insert into PROJECT_LINK (ID,PROJECT_ID,TRACK,DISCONTINUITY_TYPE,ROAD_NUMBER,ROAD_PART_NUMBER,START_ADDR_M,END_ADDR_M,CREATED_BY,MODIFIED_BY,CREATED_DATE,MODIFIED_DATE,STATUS,CALIBRATION_POINTS,ROAD_TYPE,ROADWAY_ID,LINEAR_LOCATION_ID,CONNECTED_LINK_ID,ELY,REVERSED,SIDE,START_MEASURE,END_MEASURE,LINK_ID,ADJUSTED_TIMESTAMP,LINK_SOURCE,CALIBRATION_POINTS_SOURCE,GEOMETRY,ORIGINAL_START_ADDR_M,ORIGINAL_END_ADDR_M,ROADWAY_NUMBER) values ('1000364','1000351','0','2','22006','24','0','248','silari','silari',to_date('27.12.2019','DD.MM.RRRR'),to_date('27.12.2019','DD.MM.RRRR'),'3','3','1','1052906','1039046',null,'2','1','2',0,248.793,'7256596','1498959782000','4','2',MDSYS.SDO_GEOMETRY(4002, 3067, NULL, MDSYS.SDO_ELEM_INFO_ARRAY(1, 2, 1), MDSYS.SDO_ORDINATE_ARRAY(267357.266, 6789458.693, 49.3099999999977, 267343.965, 6789448.141, 49.0489999999991, 267334.962, 6789442.665, 48.8600000000006, 267328.617, 6789440.381, 48.7949999999983, 267322.527, 6789439.365, 48.8000000000029, 267314.914, 6789441.141, 48.8439999999973, 267303.749, 6789445.455, 48.976999999999, 267287.508, 6789453.576, 49.0160000000033, 267268.999, 6789462.419, 49.2119999999995, 267228.658, 6789482.321, 49.3870000000024, 267201.225, 6789496.844, 49.4649999999965, 267167.876, 6789511.367, 49.7329999999929, 267147.437, 6789519.436, 49.9670000000042, 267131.966200167, 6789520.78998248, 50.2599962090907)),'0','248','6446225')""".execute

      sqlu"""Insert into PROJECT_LINK_NAME (ID,PROJECT_ID,ROAD_NUMBER,ROAD_NAME) values ('17','1000351','22006','MOMMOLAN RAMPIT')""".execute

      sqlu"""Insert into ROADWAY_CHANGES (PROJECT_ID,CHANGE_TYPE,OLD_ROAD_NUMBER,NEW_ROAD_NUMBER,OLD_ROAD_PART_NUMBER,NEW_ROAD_PART_NUMBER,OLD_TRACK,NEW_TRACK,OLD_START_ADDR_M,NEW_START_ADDR_M,OLD_END_ADDR_M,NEW_END_ADDR_M,NEW_DISCONTINUITY,NEW_ROAD_TYPE,NEW_ELY,OLD_ROAD_TYPE,OLD_DISCONTINUITY,OLD_ELY,REVERSED,ROADWAY_CHANGE_ID) values ('1000351','3','22006','22006','12','67','0','0','0','0','177','177','2','1','2','1','5','2','1','1000753')""".execute
      sqlu"""Insert into ROADWAY_CHANGES (PROJECT_ID,CHANGE_TYPE,OLD_ROAD_NUMBER,NEW_ROAD_NUMBER,OLD_ROAD_PART_NUMBER,NEW_ROAD_PART_NUMBER,OLD_TRACK,NEW_TRACK,OLD_START_ADDR_M,NEW_START_ADDR_M,OLD_END_ADDR_M,NEW_END_ADDR_M,NEW_DISCONTINUITY,NEW_ROAD_TYPE,NEW_ELY,OLD_ROAD_TYPE,OLD_DISCONTINUITY,OLD_ELY,REVERSED,ROADWAY_CHANGE_ID) values ('1000351','3','22006','22006','34','12','0','0','0','0','127','127','5','1','2','1','5','2','0','1000754')""".execute
      sqlu"""Insert into ROADWAY_CHANGES (PROJECT_ID,CHANGE_TYPE,OLD_ROAD_NUMBER,NEW_ROAD_NUMBER,OLD_ROAD_PART_NUMBER,NEW_ROAD_PART_NUMBER,OLD_TRACK,NEW_TRACK,OLD_START_ADDR_M,NEW_START_ADDR_M,OLD_END_ADDR_M,NEW_END_ADDR_M,NEW_DISCONTINUITY,NEW_ROAD_TYPE,NEW_ELY,OLD_ROAD_TYPE,OLD_DISCONTINUITY,OLD_ELY,REVERSED,ROADWAY_CHANGE_ID) values ('1000351','3','22006','22006','12','56','0','0','215','0','295','80','5','1','2','1','2','2','1','1000755')""".execute
      sqlu"""Insert into ROADWAY_CHANGES (PROJECT_ID,CHANGE_TYPE,OLD_ROAD_NUMBER,NEW_ROAD_NUMBER,OLD_ROAD_PART_NUMBER,NEW_ROAD_PART_NUMBER,OLD_TRACK,NEW_TRACK,OLD_START_ADDR_M,NEW_START_ADDR_M,OLD_END_ADDR_M,NEW_END_ADDR_M,NEW_DISCONTINUITY,NEW_ROAD_TYPE,NEW_ELY,OLD_ROAD_TYPE,OLD_DISCONTINUITY,OLD_ELY,REVERSED,ROADWAY_CHANGE_ID) values ('1000351','3','22006','22006','34','23','0','0','127','0','310','183','2','1','2','1','2','2','0','1000756')""".execute
      sqlu"""Insert into ROADWAY_CHANGES (PROJECT_ID,CHANGE_TYPE,OLD_ROAD_NUMBER,NEW_ROAD_NUMBER,OLD_ROAD_PART_NUMBER,NEW_ROAD_PART_NUMBER,OLD_TRACK,NEW_TRACK,OLD_START_ADDR_M,NEW_START_ADDR_M,OLD_END_ADDR_M,NEW_END_ADDR_M,NEW_DISCONTINUITY,NEW_ROAD_TYPE,NEW_ELY,OLD_ROAD_TYPE,OLD_DISCONTINUITY,OLD_ELY,REVERSED,ROADWAY_CHANGE_ID) values ('1000351','3','22006','22006','23','68','0','0','0','0','45','45','1','1','2','1','2','2','1','1000757')""".execute
      sqlu"""Insert into ROADWAY_CHANGES (PROJECT_ID,CHANGE_TYPE,OLD_ROAD_NUMBER,NEW_ROAD_NUMBER,OLD_ROAD_PART_NUMBER,NEW_ROAD_PART_NUMBER,OLD_TRACK,NEW_TRACK,OLD_START_ADDR_M,NEW_START_ADDR_M,OLD_END_ADDR_M,NEW_END_ADDR_M,NEW_DISCONTINUITY,NEW_ROAD_TYPE,NEW_ELY,OLD_ROAD_TYPE,OLD_DISCONTINUITY,OLD_ELY,REVERSED,ROADWAY_CHANGE_ID) values ('1000351','3','22006','22006','45','24','0','0','0','0','248','248','2','1','2','1','1','2','1','1000758')""".execute
      sqlu"""Insert into ROADWAY_CHANGES (PROJECT_ID,CHANGE_TYPE,OLD_ROAD_NUMBER,NEW_ROAD_NUMBER,OLD_ROAD_PART_NUMBER,NEW_ROAD_PART_NUMBER,OLD_TRACK,NEW_TRACK,OLD_START_ADDR_M,NEW_START_ADDR_M,OLD_END_ADDR_M,NEW_END_ADDR_M,NEW_DISCONTINUITY,NEW_ROAD_TYPE,NEW_ELY,OLD_ROAD_TYPE,OLD_DISCONTINUITY,OLD_ELY,REVERSED,ROADWAY_CHANGE_ID) values ('1000351','3','22006','22006','12','56','0','0','177','80','215','118','5','1','2','1','5','2','1','1000759')""".execute

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
      projectService.updateRoadwaysAndLinearLocationsWithProjectLinks(ProjectState.Saved2TR, 1000351)

      val roadwayPoints = roadwayPointDAO.fetch(Seq((166883763L, 0l)))
      roadwayPoints.size should be(1)

    }
  }

  def toRoadwayAndLinearLocation(p: ProjectLink):(LinearLocation, Roadway) = {
    def calibrationPoint(cp: Option[ProjectLinkCalibrationPoint]): Option[Long] = {
      cp match {
        case Some(x) =>
          Some(x.addressMValue)
        case _ => Option.empty[Long]
      }
    }

    val startDate = p.startDate.getOrElse(DateTime.now()).minusDays(1)

    (LinearLocation(-1000, 1, p.linkId, p.startMValue, p.endMValue, p.sideCode, p.linkGeometryTimeStamp,
      (calibrationPoint(p.calibrationPoints._1), calibrationPoint(p.calibrationPoints._2)), p.geometry, p.linkGeomSource,
      p.roadwayNumber, Some(startDate), p.endDate),
      Roadway(-1000, p.roadwayNumber, p.roadNumber, p.roadPartNumber, p.roadType, p.track, p.discontinuity, p.startAddrMValue, p.endAddrMValue, p.reversed, startDate, p.endDate,
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
      println(roadways)
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
    ProjectLink(NewIdValue, roadNumber, roadPartNumber, track, discontinuity, startAddrM, endAddrM, startAddrM, endAddrM, startDate, None,
      Some("User"), startAddrM, 0.0, (endAddrM - startAddrM).toDouble, SideCode.TowardsDigitizing, (None, None),
      Seq(Point(0.0, startAddrM), Point(0.0, endAddrM)), projectId, status, RoadType.PublicRoad,
      LinkGeomSource.NormalLinkInterface, (endAddrM - startAddrM).toDouble, roadwayId, linearLocationId, ely, reversed = false, None, 0L)
  }
}

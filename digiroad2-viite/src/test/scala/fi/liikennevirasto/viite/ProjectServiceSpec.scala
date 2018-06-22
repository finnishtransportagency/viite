package fi.liikennevirasto.viite

import java.util.Properties

import fi.liikennevirasto.digiroad2.asset.ConstructionType.InUse
import fi.liikennevirasto.digiroad2.asset.LinkGeomSource.{NormalLinkInterface, SuravageLinkInterface}
import fi.liikennevirasto.digiroad2.asset.SideCode.{AgainstDigitizing, TowardsDigitizing}
import fi.liikennevirasto.digiroad2.asset._
import fi.liikennevirasto.digiroad2.client.vvh._
import fi.liikennevirasto.digiroad2.dao.Sequences
import fi.liikennevirasto.digiroad2.linearasset.{PolyLine, RoadLink}
import fi.liikennevirasto.digiroad2.oracle.OracleDatabase
import fi.liikennevirasto.digiroad2.service.{RoadLinkService, RoadLinkType}
import fi.liikennevirasto.digiroad2.util.Track
import fi.liikennevirasto.digiroad2.{DigiroadEventBus, Point, _}
import fi.liikennevirasto.viite.RoadType.PublicRoad
import fi.liikennevirasto.viite.dao.AddressChangeType._
import fi.liikennevirasto.viite.dao.Discontinuity.{Continuous, Discontinuous, EndOfRoad}
import fi.liikennevirasto.viite.dao.ProjectState.Sent2TR
import fi.liikennevirasto.viite.dao.TerminationCode.{NoTermination, Subsequent}
import fi.liikennevirasto.viite.dao.{LinkStatus, RoadAddressDAO, _}
import fi.liikennevirasto.viite.model.{Anomaly, ProjectAddressLink, RoadAddressLinkLike}
import fi.liikennevirasto.viite.process.ProjectDeltaCalculator
import org.joda.time.DateTime
import org.mockito.Matchers._
import org.mockito.Mockito.{when, _}
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import org.scalatest.mock.MockitoSugar
import org.scalatest.{BeforeAndAfter, FunSuite, Matchers}
import slick.driver.JdbcDriver.backend.Database
import slick.driver.JdbcDriver.backend.Database.dynamicSession
import slick.jdbc.StaticQuery.interpolation

class ProjectServiceSpec extends FunSuite with Matchers with BeforeAndAfter {
  val properties: Properties = {
    val props = new Properties()
    props.load(getClass.getResourceAsStream("/digiroad2.properties"))
    props
  }
  val mockProjectService = MockitoSugar.mock[ProjectService]
  val mockRoadLinkService = MockitoSugar.mock[RoadLinkService]
  val mockRoadAddressService = MockitoSugar.mock[RoadAddressService]
  val mockEventBus = MockitoSugar.mock[DigiroadEventBus]
  val mockVVHClient = MockitoSugar.mock[VVHClient]
  val mockVVHRoadLinkClient = MockitoSugar.mock[VVHRoadLinkClient]
  val mockVVHSuravageClient = MockitoSugar.mock[VVHSuravageClient]
  val mockVVHComplementaryClient = MockitoSugar.mock[VVHComplementaryClient]
  val roadAddressService = new RoadAddressService(mockRoadLinkService, mockEventBus) {
    override def withDynSession[T](f: => T): T = f

    override def withDynTransaction[T](f: => T): T = f
  }
  val projectService = new ProjectService(roadAddressService, mockRoadLinkService, mockEventBus) {
    override def withDynSession[T](f: => T): T = f

    override def withDynTransaction[T](f: => T): T = f
  }

  val projectServiceWithRoadAddressMock = new ProjectService(mockRoadAddressService, mockRoadLinkService, mockEventBus) {
    override def withDynSession[T](f: => T): T = f

    override def withDynTransaction[T](f: => T): T = f
  }
  val projectValidator = ProjectValidator


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

  private def createProjectLinks(linkIds: Seq[Long], projectId: Long, roadNumber: Long, roadPartNumber: Long, track: Int,
                                 discontinuity: Int, roadType: Int, roadLinkSource: Int,
                                 roadEly: Long, user: String, roadName: String): Map[String, Any] = {
    projectService.createProjectLinks(linkIds, projectId, roadNumber, roadPartNumber, Track.apply(track), Discontinuity.apply(discontinuity),
      RoadType.apply(roadType), LinkGeomSource.apply(roadLinkSource), roadEly, user, roadName)
  }

  private def toProjectLink(project: RoadAddressProject, status: LinkStatus)(roadAddress: RoadAddress): ProjectLink = {
    ProjectLink(id = NewRoadAddress, roadAddress.roadNumber, roadAddress.roadPartNumber, roadAddress.track,
      roadAddress.discontinuity, roadAddress.startAddrMValue, roadAddress.endAddrMValue, roadAddress.startDate,
      roadAddress.endDate, createdBy = Option(project.createdBy), 0L, roadAddress.linkId, roadAddress.startMValue, roadAddress.endMValue,
      roadAddress.sideCode, roadAddress.calibrationPoints, floating = false, roadAddress.geometry, project.id, status, RoadType.PublicRoad,
      roadAddress.linkGeomSource, GeometryUtils.geometryLength(roadAddress.geometry), if (status == LinkStatus.New) 0 else roadAddress.id, roadAddress.ely, false,
      None, roadAddress.adjustedTimestamp)
  }

  private def toProjectAddressLink(ral: RoadAddressLinkLike): ProjectAddressLink = {
    ProjectAddressLink(ral.id, ral.linkId, ral.geometry, ral.length, ral.administrativeClass, ral.linkType, ral.roadLinkType,
      ral.constructionType, ral.roadLinkSource, ral.roadType, ral.VVHRoadName, ral.roadName, ral.municipalityCode, ral.modifiedAt, ral.modifiedBy,
      ral.attributes, ral.roadNumber, ral.roadPartNumber, ral.trackCode, ral.elyCode, ral.discontinuity,
      ral.startAddressM, ral.endAddressM, ral.startMValue, ral.endMValue, ral.sideCode, ral.startCalibrationPoint, ral.endCalibrationPoint,
      ral.anomaly, ral.lrmPositionId, LinkStatus.Unknown, ral.id)
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
    val dbLinks = ProjectDAO.getProjectLinks(id)
    when(mockRoadLinkService.getRoadLinksHistoryFromVVH(any[Set[Long]])).thenReturn(Seq())
    when(mockRoadLinkService.getRoadLinksByLinkIdsFromVVH(any[Set[Long]], any[Boolean], any[Boolean])).thenAnswer(
      toMockAnswer(dbLinks ++ projectLinks.asInstanceOf[Seq[ProjectLink]].filterNot(l => dbLinks.map(_.linkId).contains(l.linkId)),
        roadLink, palinks.asInstanceOf[Seq[ProjectAddressLink]].map(toRoadLink)
      ))
  }

  test("create road link project without road parts") {
    runWithRollback {
      val roadAddressProject = RoadAddressProject(0, ProjectState.apply(1), "TestProject", "TestUser", DateTime.now(), "TestUser", DateTime.parse("1901-01-01"), DateTime.now(), "Some additional info", List.empty[ReservedRoadPart], None)
      val project = projectService.createRoadLinkProject(roadAddressProject)
      project.reservedParts should have size (0)
    }
  }

  test("try to create a road link project with same name as an existing project") {
    runWithRollback {
      val roadAddressProject1 = RoadAddressProject(0, ProjectState.apply(1), "TestProject", "TestUser1", DateTime.now(), "TestUser1", DateTime.parse("1901-01-01"), DateTime.now(), "Some additional info", List.empty[ReservedRoadPart], None)
      projectService.createRoadLinkProject(roadAddressProject1)

      val roadAddressProject2 = RoadAddressProject(0, ProjectState.apply(1), "TESTPROJECT", "TestUser2", DateTime.now(), "TestUser2", DateTime.parse("1902-03-03"), DateTime.now(), "Some other info", List.empty[ReservedRoadPart], None)
      val error = intercept[NameExistsException] {
        projectService.createRoadLinkProject(roadAddressProject2)
      }
      error.getMessage should be("Nimellä TESTPROJECT on jo olemassa projekti. Muuta nimeä.")

      val roadAddressProject3 = RoadAddressProject(0, ProjectState.apply(1), "testproject", "TestUser3", DateTime.now(), "TestUser3", DateTime.parse("1903-03-03"), DateTime.now(), "Some other info", List.empty[ReservedRoadPart], None)
      val error2 = intercept[NameExistsException] {
        projectService.createRoadLinkProject(roadAddressProject3)
      }
      error2.getMessage should be("Nimellä testproject on jo olemassa projekti. Muuta nimeä.")
    }
  }

  test("Adding and removing TR_ID") {
    runWithRollback {
      val projectId = Sequences.nextViitePrimaryKeySeqValue
      val rap = RoadAddressProject(projectId, ProjectState.apply(3), "TestProject", "TestUser", DateTime.parse("2700-01-01"), "TestUser", DateTime.parse("2700-01-01"), DateTime.now(), "Some additional info", List.empty[ReservedRoadPart], None)
      runWithRollback {
        ProjectDAO.createRoadAddressProject(rap)
        val emptyTrId = ProjectDAO.getRotatingTRProjectId(projectId)
        emptyTrId.isEmpty should be(true)
        val projectNone = ProjectDAO.getRoadAddressProjectById(projectId)
        projectService.removeRotatingTRId(projectId)
        projectNone.head.statusInfo.getOrElse("").size should be(0)
        ProjectDAO.addRotatingTRProjectId(projectId)
        val trId = ProjectDAO.getRotatingTRProjectId(projectId)
        trId.nonEmpty should be(true)
        projectService.removeRotatingTRId(projectId)
        emptyTrId.isEmpty should be(true)
        ProjectDAO.addRotatingTRProjectId(projectId)
        projectService.removeRotatingTRId(projectId)
        val project = ProjectDAO.getRoadAddressProjectById(projectId).head
        project.status should be(ProjectState.Incomplete)
        project.statusInfo.getOrElse("1").size should be > 2
      }
    }
  }

  test("change roadpart direction and check reversed attribute, service level") {
    runWithRollback {
      val rap = RoadAddressProject(0L, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("1901-01-01"),
        "TestUser", DateTime.parse("1901-01-01"), DateTime.now(), "Some additional info",
        Seq(), None)
      val project = projectService.createRoadLinkProject(rap)
      val id = project.id
      mockForProject(id, RoadAddressDAO.fetchByRoadPart(5, 207).map(toProjectLink(project)))
      projectService.saveProject(project.copy(reservedParts = Seq(
        ReservedRoadPart(0L, 5, 207, Some(0L), Some(Continuous), Some(8L), None, None, None, None, true))))
      val projectLinks = ProjectDAO.getProjectLinks(id)
      ProjectDAO.updateProjectLinks(projectLinks.map(x => x.id).toSet, LinkStatus.Transfer, "test")
      mockForProject(id)
      projectService.changeDirection(id, 5, 207, projectLinks.map(l => LinkToRevert(l.id, l.linkId, l.status.value, l.geometry)), "test") should be(None)
      val updatedProjectLinks = ProjectDAO.getProjectLinks(id)
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
      updatedProjectLinks.foreach(x => x.reversed should be(true))
      projectService.changeDirection(id, 5, 207, projectLinks.map(l => LinkToRevert(l.id, l.linkId, l.status.value, l.geometry)), "test")
      val secondUpdatedProjectLinks = ProjectDAO.getProjectLinks(id)
      projectLinks.sortBy(_.endAddrMValue).map(_.geometry).zip(secondUpdatedProjectLinks.sortBy(_.endAddrMValue).map(_.geometry)).forall { case (x, y) => x == y }
      secondUpdatedProjectLinks.foreach(x => x.reversed should be(false))
    }
  }

  private def toProjectLink(project: RoadAddressProject)(roadAddress: RoadAddress): ProjectLink = {
    ProjectLink(id = NewRoadAddress, roadAddress.roadNumber, roadAddress.roadPartNumber, roadAddress.track,
      roadAddress.discontinuity, roadAddress.startAddrMValue, roadAddress.endAddrMValue, roadAddress.startDate,
      roadAddress.endDate, createdBy = Option(project.createdBy), 0L, roadAddress.linkId, roadAddress.startMValue, roadAddress.endMValue,
      roadAddress.sideCode, roadAddress.calibrationPoints, floating = false, roadAddress.geometry, project.id, LinkStatus.NotHandled, RoadType.PublicRoad,
      roadAddress.linkGeomSource, GeometryUtils.geometryLength(roadAddress.geometry), 0, roadAddress.ely, false,
      None, roadAddress.adjustedTimestamp)
  }

  test("Using TR_id as project_id when querying should be empty") {
    runWithRollback {
      val projectId = Sequences.nextViitePrimaryKeySeqValue
      val rap = RoadAddressProject(projectId, ProjectState.apply(2), "TestProject", "TestUser", DateTime.parse("2700-01-01"), "TestUser", DateTime.parse("2700-01-01"), DateTime.now(), "Some additional info", List.empty[ReservedRoadPart], None)
      runWithRollback {
        ProjectDAO.createRoadAddressProject(rap)
        ProjectDAO.addRotatingTRProjectId(projectId)
        projectService.updateProjectsWaitingResponseFromTR()
        val project = ProjectDAO.getRoadAddressProjectById(projectId).head
        project.statusInfo.getOrElse("").size should be(0)
        projectService.updateProjectsWaitingResponseFromTR()
      }
    }
  }

  test("Using TR_id as project_id when querrying info: should fail") {
    runWithRollback {
      val projectId = Sequences.nextViitePrimaryKeySeqValue
      val rap = RoadAddressProject(projectId, ProjectState.apply(2), "TestProject", "TestUser", DateTime.parse("2700-01-01"), "TestUser", DateTime.parse("2700-01-01"), DateTime.now(), "Some additional info", List.empty[ReservedRoadPart], None)
      runWithRollback {
        ProjectDAO.createRoadAddressProject(rap)
        projectService.updateProjectsWaitingResponseFromTR()
        val project = ProjectDAO.getRoadAddressProjectById(projectId).head
        project.statusInfo.getOrElse("") contains ("Failed to find TR-ID") should be(true)
      }
    }
  }

  test("create road link project without valid roadParts") {
    val roadlink = RoadLink(5175306, Seq(Point(535605.272, 6982204.22, 85.90899999999965))
      , 540.3960283713503, State, 99, TrafficDirection.AgainstDigitizing, UnknownLinkType, Some("25.06.2015 03:00:00"), Some("vvh_modified"), Map("MUNICIPALITYCODE" -> BigInt.apply(749)),
      InUse, NormalLinkInterface)
    when(mockRoadLinkService.getRoadLinksByLinkIdsFromVVH(Set(5175306L))).thenReturn(Seq(roadlink))
    runWithRollback {
      val roadAddressProject = RoadAddressProject(0, ProjectState.apply(1), "TestProject", "TestUser", DateTime.now(), "TestUser", DateTime.parse("1901-01-01"), DateTime.now(), "Some additional info", List.empty[ReservedRoadPart], None)
      val project = projectService.createRoadLinkProject(roadAddressProject)
      project.reservedParts should have size (0)
    }
  }

  test("create and get projects by id") {
    var count = 0
    runWithRollback {
      val countCurrentProjects = projectService.getRoadAddressAllProjects
      val roadAddressProject = RoadAddressProject(0, ProjectState.apply(1), "TestProject", "TestUser", DateTime.now(), "TestUser", DateTime.parse("1901-01-01"), DateTime.now(), "Some additional info", Seq(), None)
      val project = projectService.createRoadLinkProject(roadAddressProject)
      mockForProject(project.id, RoadAddressDAO.fetchByRoadPart(5, 203).map(toProjectLink(roadAddressProject)))
      projectService.saveProject(project.copy(reservedParts = Seq(
        ReservedRoadPart(0L, 5, 203, Some(0L), Some(Continuous), Some(8L), None, None, None, None, true))))
      val countAfterInsertProjects = projectService.getRoadAddressAllProjects
      count = countCurrentProjects.size + 1
      countAfterInsertProjects.size should be(count)
    }
    runWithRollback {
      projectService.getRoadAddressAllProjects.size should be(count - 1)
    }
  }

  test("save project") {
    var count = 0
    runWithRollback {
      val countCurrentProjects = projectService.getRoadAddressAllProjects
      val id = 0
      val roadAddressProject = RoadAddressProject(id, ProjectState.apply(1), "TestProject", "TestUser", DateTime.now(), "TestUser", DateTime.parse("1901-01-01"), DateTime.now(), "Some additional info", List(), None)
      val project = projectService.createRoadLinkProject(roadAddressProject)
      mockForProject(project.id, RoadAddressDAO.fetchByRoadPart(1130, 4).map(toProjectLink(roadAddressProject)))
      projectService.saveProject(project.copy(reservedParts = List(
        ReservedRoadPart(Sequences.nextViitePrimaryKeySeqValue: Long, 1130: Long, 4: Long, Some(5L), Some(Discontinuity.apply("jatkuva")), Some(8L), newLength = None, newDiscontinuity = None, newEly = None))))
      val countAfterInsertProjects = projectService.getRoadAddressAllProjects
      count = countCurrentProjects.size + 1
      countAfterInsertProjects.size should be(count)
    }
    runWithRollback {
      projectService.getRoadAddressAllProjects
    } should have size (count - 1)
  }

  test("create and delete project") {
    var count = 0
    runWithRollback {
      val countCurrentProjects = projectService.getRoadAddressAllProjects
      val roadAddressProject = RoadAddressProject(0, ProjectState.apply(1), "TestProject", "TestUser", DateTime.now(), "TestUser", DateTime.parse("1901-01-01"), DateTime.now(), "Some additional info", Seq(), None)
      val project = projectService.createRoadLinkProject(roadAddressProject)
      mockForProject(project.id, RoadAddressDAO.fetchByRoadPart(5, 203).map(toProjectLink(roadAddressProject)))
      projectService.saveProject(project.copy(reservedParts = Seq(ReservedRoadPart(0L, 5, 203, Some(100L), Some(Continuous), Some(8L), None, None, None, None))))
      val countAfterInsertProjects = projectService.getRoadAddressAllProjects
      count = countCurrentProjects.size + 1
      countAfterInsertProjects.size should be(count)
      projectService.deleteProject(project.id)
      val projectsAfterOperations = projectService.getRoadAddressAllProjects
      projectsAfterOperations.size should be(count)
      projectsAfterOperations.exists(_.id == project.id) should be(true)
      projectsAfterOperations.find(_.id == project.id).get.status should be(ProjectState.Deleted)
    }
  }

  test("Unchanged with termination test, repreats termination update, checks calibration points are cleared and moved to correct positions") {
    var count = 0
    val roadLink = RoadLink(5170939L, Seq(Point(535605.272, 6982204.22, 85.90899999999965))
      , 540.3960283713503, State, 99, TrafficDirection.AgainstDigitizing, UnknownLinkType, Some("25.06.2015 03:00:00"), Some("vvh_modified"), Map("MUNICIPALITYCODE" -> BigInt.apply(749)),
      InUse, NormalLinkInterface)
    runWithRollback {
      val countCurrentProjects = projectService.getRoadAddressAllProjects
      val id = 0
      val addresses = Seq(ReservedRoadPart(5: Long, 5: Long, 205: Long, Some(5L), Some(Discontinuity.apply("jatkuva")), Some(8L), newLength = None, newDiscontinuity = None, newEly = None),
        ReservedRoadPart(5: Long, 5: Long, 206: Long, Some(5L), Some(Discontinuity.apply("jatkuva")), Some(8L), newLength = None, newDiscontinuity = None, newEly = None))
      val roadAddressProject = RoadAddressProject(id, ProjectState.apply(1), "TestProject", "TestUser", DateTime.now(), "TestUser", DateTime.parse("1901-01-01"), DateTime.now(), "Some additional info", Seq(), None)
      val savedProject = projectService.createRoadLinkProject(roadAddressProject)
      mockForProject(savedProject.id, (RoadAddressDAO.fetchByRoadPart(5, 205) ++ RoadAddressDAO.fetchByRoadPart(5, 206)).map(toProjectLink(savedProject)))
      projectService.saveProject(savedProject.copy(reservedParts = addresses))
      val countAfterInsertProjects = projectService.getRoadAddressAllProjects
      count = countCurrentProjects.size + 1
      countAfterInsertProjects.size should be(count)
      projectService.allLinksHandled(savedProject.id) should be(false)
      projectService.getRoadAddressSingleProject(savedProject.id).nonEmpty should be(true)
      projectService.getRoadAddressSingleProject(savedProject.id).get.reservedParts.nonEmpty should be(true)
      val projectLinks = ProjectDAO.getProjectLinks(savedProject.id)
      val partitioned = projectLinks.partition(_.roadPartNumber == 205)
      val linkIds205 = partitioned._1.map(_.linkId).toSet
      val linkIds206 = partitioned._2.map(_.linkId).toSet
      reset(mockRoadLinkService)
      when(mockRoadLinkService.getRoadLinksHistoryFromVVH(any[Set[Long]])).thenReturn(Seq())
      when(mockRoadLinkService.getRoadLinksByLinkIdsFromVVH(any[Set[Long]], any[Boolean], any[Boolean])).thenAnswer(
        toMockAnswer(projectLinks, roadLink)
      )
      projectService.updateProjectLinks(savedProject.id, Set(), linkIds205.toSeq, LinkStatus.UnChanged, "-", 0, 0, 0, Option.empty[Int]) should be(None)
      projectService.allLinksHandled(savedProject.id) should be(false)
      projectService.updateProjectLinks(savedProject.id, Set(), linkIds206.toSeq, LinkStatus.UnChanged, "-", 0, 0, 0, Option.empty[Int]) should be(None)
      projectService.allLinksHandled(savedProject.id) should be(true)
      projectService.updateProjectLinks(savedProject.id, Set(), Seq(5168573), LinkStatus.Terminated, "-", 0, 0, 0, Option.empty[Int]) should be(None)
      projectService.allLinksHandled(savedProject.id) should be(true)
      val changeProjectOpt = projectService.getChangeProject(savedProject.id)
      val change = changeProjectOpt.get
      val updatedProjectLinks = ProjectDAO.getProjectLinks(savedProject.id)
      updatedProjectLinks.exists { x => x.status == LinkStatus.UnChanged } should be(true)
      updatedProjectLinks.exists { x => x.status == LinkStatus.Terminated } should be(true)
      updatedProjectLinks.filter(pl => pl.linkId == 5168579).head.calibrationPoints should be((None, Some(CalibrationPoint(5168579, 15.173, 4681))))
      projectService.updateProjectLinks(savedProject.id, Set(), Seq(5168579), LinkStatus.Terminated, "-", 0, 0, 0, Option.empty[Int])
      val updatedProjectLinks2 = ProjectDAO.getProjectLinks(savedProject.id)
      updatedProjectLinks2.filter(pl => pl.linkId == 5168579).head.calibrationPoints should be((None, None))
      updatedProjectLinks2.filter(pl => pl.linkId == 5168583).head.calibrationPoints should be((None, Some(CalibrationPoint(5168583, 63.8, 4666))))
      updatedProjectLinks2.filter(pl => pl.roadPartNumber == 205).exists { x => x.status == LinkStatus.Terminated } should be(false)
    }
    runWithRollback {
      projectService.getRoadAddressAllProjects
    } should have size (count - 1)
  }

  test("Transfer and then terminate") {
    var count = 0
    val roadLink = RoadLink(5170939L, Seq(Point(535605.272, 6982204.22, 85.90899999999965))
      , 540.3960283713503, State, 99, TrafficDirection.AgainstDigitizing, UnknownLinkType, Some("25.06.2015 03:00:00"), Some("vvh_modified"), Map("MUNICIPALITYCODE" -> BigInt.apply(749)),
      InUse, NormalLinkInterface)
    runWithRollback {
      val countCurrentProjects = projectService.getRoadAddressAllProjects
      val id = 0
      val addresses = List(ReservedRoadPart(5: Long, 5: Long, 207: Long, Some(5L), Some(Discontinuity.apply("jatkuva")), Some(8L), newLength = None, newDiscontinuity = None, newEly = None))
      val roadAddressProject = RoadAddressProject(id, ProjectState.apply(1), "TestProject", "TestUser", DateTime.now(), "TestUser", DateTime.parse("1901-01-01"), DateTime.now(), "Some additional info", Seq(), None)
      val savedProject = projectService.createRoadLinkProject(roadAddressProject)
      mockForProject(savedProject.id, RoadAddressDAO.fetchByRoadPart(5, 207).map(toProjectLink(savedProject)))
      projectService.saveProject(savedProject.copy(reservedParts = addresses))
      val countAfterInsertProjects = projectService.getRoadAddressAllProjects
      count = countCurrentProjects.size + 1
      countAfterInsertProjects.size should be(count)
      projectService.allLinksHandled(savedProject.id) should be(false)
      val projectLinks = ProjectDAO.getProjectLinks(savedProject.id)
      val partitioned = projectLinks.partition(_.roadPartNumber == 207)
      val highestDistanceEnd = projectLinks.map(p => p.endAddrMValue).max
      val linkIds207 = partitioned._1.map(_.linkId).toSet
      reset(mockRoadLinkService)
      when(mockRoadLinkService.getRoadLinksHistoryFromVVH(any[Set[Long]])).thenReturn(Seq())
      when(mockRoadLinkService.getRoadLinksByLinkIdsFromVVH(any[Set[Long]], any[Boolean], any[Boolean])).thenAnswer(
        toMockAnswer(projectLinks, roadLink)
      )
      projectService.updateProjectLinks(savedProject.id, Set(), linkIds207.toSeq, LinkStatus.Transfer, "-", 0, 0, 0, Option.empty[Int]) should be(None)
      projectService.updateProjectLinks(savedProject.id, Set(), Seq(5168510), LinkStatus.Terminated, "-", 0, 0, 0, Option.empty[Int]) should be(None)
      projectService.allLinksHandled(savedProject.id) should be(true)
      val changeProjectOpt = projectService.getChangeProject(savedProject.id)
      val change = changeProjectOpt.get
      val updatedProjectLinks = ProjectDAO.getProjectLinks(savedProject.id)
      updatedProjectLinks.exists { x => x.status == LinkStatus.Transfer } should be(true)
      updatedProjectLinks.exists { x => x.status == LinkStatus.Terminated } should be(true)
      updatedProjectLinks.filter(pl => pl.linkId == 5168540).head.calibrationPoints should be((Some(CalibrationPoint(5168540, 0.0, 0)), None))
      updatedProjectLinks.filter(pl => pl.linkId == 6463199).head.calibrationPoints should be((None, Some(CalibrationPoint(6463199, 442.89, highestDistanceEnd - projectLinks.filter(pl => pl.linkId == 5168510).head.endAddrMValue)))) //we terminated link with distance 172
      projectService.updateProjectLinks(savedProject.id, Set(), Seq(5168540), LinkStatus.Terminated, "-", 0, 0, 0, Option.empty[Int]) should be(None)
      val updatedProjectLinks2 = ProjectDAO.getProjectLinks(savedProject.id)
      updatedProjectLinks2.filter(pl => pl.linkId == 6463199).head.calibrationPoints should be(None, Some(CalibrationPoint(6463199, 442.89, highestDistanceEnd - projectLinks.filter(pl => pl.linkId == 5168510).head.endAddrMValue - updatedProjectLinks.filter(pl => pl.linkId == 5168540).head.endAddrMValue)))
    }
    runWithRollback {
      projectService.getRoadAddressAllProjects
    } should have size (count - 1)
  }

  test("Terminate then transfer") {
    var count = 0
    val roadLink = RoadLink(5170939L, Seq(Point(535605.272, 6982204.22, 85.90899999999965))
      , 540.3960283713503, State, 99, TrafficDirection.AgainstDigitizing, UnknownLinkType, Some("25.06.2015 03:00:00"), Some("vvh_modified"), Map("MUNICIPALITYCODE" -> BigInt.apply(749)),
      InUse, NormalLinkInterface)
    runWithRollback {
      val countCurrentProjects = projectService.getRoadAddressAllProjects
      val id = 0
      val addresses = List(ReservedRoadPart(5: Long, 5: Long, 207: Long, Some(5L), Some(Discontinuity.apply("jatkuva")), Some(8L), newLength = None, newDiscontinuity = None, newEly = None))
      val roadAddressProject = RoadAddressProject(id, ProjectState.apply(1), "TestProject", "TestUser", DateTime.now(), "TestUser", DateTime.parse("1901-01-01"), DateTime.now(), "Some additional info", Seq(), None)
      val savedProject = projectService.createRoadLinkProject(roadAddressProject)
      mockForProject(savedProject.id, RoadAddressDAO.fetchByRoadPart(5, 207).map(toProjectLink(savedProject)))
      projectService.saveProject(savedProject.copy(reservedParts = addresses))
      val countAfterInsertProjects = projectService.getRoadAddressAllProjects
      count = countCurrentProjects.size + 1
      countAfterInsertProjects.size should be(count)
      projectService.allLinksHandled(savedProject.id) should be(false)
      val projectLinks = ProjectDAO.getProjectLinks(savedProject.id)
      val partitioned = projectLinks.partition(_.roadPartNumber == 207)
      val highestDistanceStart = projectLinks.map(p => p.startAddrMValue).max
      val highestDistanceEnd = projectLinks.map(p => p.endAddrMValue).max
      val linkIds207 = partitioned._1.map(_.linkId).toSet
      reset(mockRoadLinkService)
      when(mockRoadLinkService.getRoadLinksHistoryFromVVH(any[Set[Long]])).thenReturn(Seq())
      when(mockRoadLinkService.getRoadLinksByLinkIdsFromVVH(any[Set[Long]], any[Boolean], any[Boolean])).thenAnswer(
        toMockAnswer(projectLinks, roadLink)
      )
      projectService.updateProjectLinks(savedProject.id, Set(), Seq(5168510), LinkStatus.Terminated, "-", 0, 0, 0, Option.empty[Int])
      projectService.updateProjectLinks(savedProject.id, Set(), linkIds207.filterNot(_ == 5168510L).toSeq, LinkStatus.Transfer, "-", 0, 0, 0, Option.empty[Int])
      projectService.allLinksHandled(savedProject.id) should be(true)
      val changeProjectOpt = projectService.getChangeProject(savedProject.id)
      val change = changeProjectOpt.get
      val updatedProjectLinks = ProjectDAO.getProjectLinks(savedProject.id)
      updatedProjectLinks.exists { x => x.status == LinkStatus.Transfer } should be(true)
      updatedProjectLinks.exists { x => x.status == LinkStatus.Terminated } should be(true)
      updatedProjectLinks.filter(pl => pl.linkId == 5168540).head.calibrationPoints should be((Some(CalibrationPoint(5168540, 0.0, 0)), None))
      updatedProjectLinks.filter(pl => pl.linkId == 6463199).head.calibrationPoints should be((None, Some(CalibrationPoint(6463199, 442.89, highestDistanceEnd - 172)))) //we terminated link with distance 172
      projectService.updateProjectLinks(savedProject.id, Set(), Seq(5168540), LinkStatus.Terminated, "-", 0, 0, 0, Option.empty[Int])
      val updatedProjectLinks2 = ProjectDAO.getProjectLinks(savedProject.id)
      updatedProjectLinks2.filter(pl => pl.linkId == 6463199).head.calibrationPoints should be(None, Some(CalibrationPoint(6463199, 442.89, highestDistanceEnd - projectLinks.filter(pl => pl.linkId == 5168510).head.endAddrMValue - updatedProjectLinks.filter(pl => pl.linkId == 5168540).head.endAddrMValue)))
    }
    runWithRollback {
      projectService.getRoadAddressAllProjects
    } should have size (count - 1)
  }

  test("Terminate, new links and then transfer") {
    val roadLink = RoadLink(51L, Seq(Point(535605.272, 6982204.22, 85.90899999999965))
      , 540.3960283713503, State, 1, TrafficDirection.AgainstDigitizing, Motorway,
      Some("25.06.2015 03:00:00"), Some("vvh_modified"), Map("MUNICIPALITYCODE" -> BigInt.apply(749)),
      InUse, NormalLinkInterface)
    runWithRollback {
      val id = 0
      val addresses = List(ReservedRoadPart(5: Long, 5: Long, 205: Long, Some(5L), Some(Discontinuity.apply("jatkuva")), Some(8L), newLength = None, newDiscontinuity = None, newEly = None))
      val roadAddressProject = RoadAddressProject(id, ProjectState.apply(1), "TestProject", "TestUser", DateTime.now(), "TestUser", DateTime.parse("2021-01-01"), DateTime.now(), "Some additional info", Seq(), None)
      val savedProject = projectService.createRoadLinkProject(roadAddressProject)
      mockForProject(savedProject.id, RoadAddressDAO.fetchByRoadPart(5, 205).map(toProjectLink(savedProject)))
      projectService.saveProject(savedProject.copy(reservedParts = addresses))
      val projectLinks = ProjectDAO.getProjectLinks(savedProject.id)
      projectLinks.size should be(66)

      val linkIds = projectLinks.map(pl => pl.track.value -> pl.linkId).groupBy(_._1).mapValues(_.map(_._2).toSet)
      val newLinkTemplates = Seq(ProjectLink(-1000L, 0L, 0L, Track.apply(99), Discontinuity.Continuous, 0L, 0L, None, None,
        None, 0L, 1234L, 0.0, 43.1, SideCode.Unknown, (None, None), false,
        Seq(Point(468.5, 0.5), Point(512.0, 0.0)), 0L, LinkStatus.Unknown, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, 43.1, 0L, 0, false,
        None, 86400L),
        ProjectLink(-1000L, 0L, 0L, Track.apply(99), Discontinuity.Continuous, 0L, 0L, None, None,
          None, 0L, 1235L, 0.0, 71.1, SideCode.Unknown, (None, None), false,
          Seq(Point(510.0, 0.0), Point(581.0, 0.0)), 0L, LinkStatus.Unknown, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, 71.1, 0L, 0, false,
          None, 86400L))
      projectService.updateProjectLinks(savedProject.id, Set(), Seq(5172715, 5172714, 5172031, 5172030), LinkStatus.Terminated, "-", 5, 205, 0, None)
      linkIds.keySet.foreach(k =>
        projectService.updateProjectLinks(savedProject.id, Set(), (linkIds(k) -- Set(5172715, 5172714, 5172031, 5172030)).toSeq, LinkStatus.Transfer, "-", 5, 205, k, None)
      )
      ProjectDAO.getProjectLinks(savedProject.id).size should be(66)
      when(mockRoadLinkService.getSuravageRoadLinksFromVVH(any[Set[Long]])).thenReturn(Seq())
      when(mockRoadLinkService.getRoadLinksByLinkIdsFromVVH(any[Set[Long]], any[Boolean], any[Boolean])).thenReturn(newLinkTemplates.take(1).map(toRoadLink))
      createProjectLinks(newLinkTemplates.take(1).map(_.linkId), savedProject.id, 5L, 205L, 1, 5, 2, 1, 8, "U", "road name").get("success") should be(Some(true))
      when(mockRoadLinkService.getRoadLinksByLinkIdsFromVVH(any[Set[Long]], any[Boolean], any[Boolean])).thenReturn(newLinkTemplates.tail.take(1).map(toRoadLink))
      createProjectLinks(newLinkTemplates.tail.take(1).map(_.linkId), savedProject.id, 5L, 205L, 2, 5, 2, 1, 8, "U", "road name").get("success") should be(Some(true))
      ProjectDAO.getProjectLinks(savedProject.id).size should be(68)
      val changeInfo = projectService.getChangeProject(savedProject.id)
      projectService.allLinksHandled(savedProject.id) should be(true)
      changeInfo.get.changeInfoSeq.foreach { ci =>
        ci.changeType match {
          case Termination =>
            ci.source.startAddressM should be(Some(0))
            ci.source.endAddressM should be(Some(546))
            ci.target.startAddressM should be(None)
            ci.target.endAddressM should be(None)
          case Transfer =>
            ci.source.startAddressM should be(Some(546))
            ci.source.endAddressM should be(Some(6730))
            ci.target.startAddressM should be(Some(57))
          case AddressChangeType.New =>
            ci.source.startAddressM should be(None)
            ci.target.startAddressM should be(Some(0))
            ci.source.endAddressM should be(None)
            ci.target.endAddressM should be(Some(57))
          case _ =>
            throw new RuntimeException(s"Nobody expects ${ci.changeType} inquisition!")
        }
      }
    }
  }

  test("Validate road part dates with project date - startDate") {
    runWithRollback {
      val projDate = DateTime.parse("1990-01-01")
      val addresses = List(ReservedRoadPart(5: Long, 5: Long, 205: Long, Some(5L), Some(Discontinuity.apply("jatkuva")), Some(8L), newLength = None, newDiscontinuity = None, newEly = None))
      val errorMsg = projectService.validateProjectDate(addresses, projDate)
      errorMsg should not be (None)
    }
  }

  test("Validate road part dates with project date - startDate valid") {
    runWithRollback {
      val projDate = DateTime.parse("2015-01-01")
      val addresses = List(ReservedRoadPart(5: Long, 5: Long, 205: Long, Some(5L), Some(Discontinuity.apply("jatkuva")), Some(8L), newLength = None, newDiscontinuity = None, newEly = None))
      val errorMsg = projectService.validateProjectDate(addresses, projDate)
      errorMsg should be(None)
    }
  }

  test("Validate road part dates with project date - startDate and endDate") {
    runWithRollback {
      val projDate = DateTime.parse("1990-01-01")
      val addresses = List(ReservedRoadPart(5: Long, 5: Long, 205: Long, Some(5L), Some(Discontinuity.apply("jatkuva")), Some(8L), newLength = None, newDiscontinuity = None, newEly = None))
      val errorMsg = projectService.validateProjectDate(addresses, projDate)
      errorMsg should not be (None)
    }
  }

  test("Validate road part dates with project date - startDate and endDate valid") {
    runWithRollback {
      val projDate = DateTime.parse("2018-01-01")
      val addresses = List(ReservedRoadPart(5: Long, 5: Long, 205: Long, Some(5L), Some(Discontinuity.apply("jatkuva")), Some(8L), newLength = None, newDiscontinuity = None, newEly = None))
      val errorMsg = projectService.validateProjectDate(addresses, projDate)
      errorMsg should be(None)
    }
  }

  test("process roadChange data and import the roadLink") {
    //First Create Mock Project, RoadLinks and

    runWithRollback {
      var projectId = 0L
      val roadNumber = 1943845
      val roadPartNumber = 1
      val linkId = 12345L
      val commonHistoryId = 123
      //Creation of Test road
      val id = RoadAddressDAO.getNextRoadAddressId
      val ra = Seq(RoadAddress(id, roadNumber, roadPartNumber, RoadType.PublicRoad, Track.Combined, Discontinuous, 0L, 10L,
        Some(DateTime.parse("1901-01-01")), None, Option("tester"), 0, linkId, 0.0, 9.8, SideCode.TowardsDigitizing, 0, (None, None), false,
        Seq(Point(0.0, 0.0), Point(0.0, 9.8)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, commonHistoryId))
      RoadAddressDAO.create(ra)
      val roadBeforeChanges = RoadAddressDAO.fetchByLinkId(Set(linkId)).head
      when(mockRoadLinkService.getRoadLinksHistoryFromVVH(any[Set[Long]])).thenReturn(Seq())
      when(mockRoadLinkService.getRoadLinksByLinkIdsFromVVH(any[Set[Long]], any[Boolean], any[Boolean])).thenReturn(Seq(RoadLink(linkId, ra.head.geometry, 9.8, State, 1, TrafficDirection.BothDirections,
        Motorway, None, None, Map("MUNICIPALITYCODE" -> BigInt(167)))))
      when(mockRoadLinkService.getVVHRoadlinks(any[Set[Long]], any[Boolean])).thenReturn(Seq(VVHRoadlink(linkId, 167,
        ra.head.geometry, State, TrafficDirection.BothDirections, FeatureClass.AllOthers, None, Map("MUNICIPALITYCODE" -> BigInt(167)),
        ConstructionType.InUse, LinkGeomSource.NormalLinkInterface)))
      when(mockRoadLinkService.getSuravageRoadLinksFromVVH(Set(linkId))).thenReturn(Seq())
      //Creation of test project with test links
      val project = RoadAddressProject(projectId, ProjectState.Incomplete, "testiprojekti", "Test", DateTime.now(), "Test",
        DateTime.parse("1990-01-01"), DateTime.now(), "info",
        List(ReservedRoadPart(Sequences.nextViitePrimaryKeySeqValue: Long, roadNumber: Long, roadPartNumber: Long, Some(5L), Some(Discontinuity.apply("jatkuva")), Some(8L), newLength = None, newDiscontinuity = None, newEly = None)), None)
      val savedProject = projectService.createRoadLinkProject(project)
      val projectLinkId = savedProject.reservedParts.head.startingLinkId
      projectLinkId.isEmpty should be(false)
      projectId = savedProject.id
      val unchangedValue = LinkStatus.UnChanged.value
      val projectLink = ProjectDAO.fetchFirstLink(projectId, roadNumber, roadPartNumber)
      projectLink.isEmpty should be(false)
      //Changing the status of the test link
      sqlu"""Update Project_Link Set Status = $unchangedValue
            Where ID = ${projectLink.get.id} And PROJECT_ID = $projectId""".execute

      //Creation of test road_address_changes
      sqlu"""insert into road_address_changes
             (project_id,change_type,new_road_number,new_road_part_number,new_track_code,new_start_addr_m,new_end_addr_m,new_discontinuity,new_road_type,new_ely,
              old_road_number,old_road_part_number,old_track_code,old_start_addr_m,old_end_addr_m)
             Values ($projectId,1,$roadNumber,$roadPartNumber,0,0,10,2,1,8,$roadNumber,$roadPartNumber,0,0,10)""".execute

      projectService.updateRoadAddressWithProjectLinks(ProjectState.Saved2TR, projectId)

      val roadsAfterChanges = RoadAddressDAO.fetchByLinkId(Set(linkId), false, true)
      roadsAfterChanges.size should be(1)
      val roadAfterPublishing = roadsAfterChanges.filter(x => x.startDate.nonEmpty && x.endDate.isEmpty).head
      val endedAddress = roadsAfterChanges.filter(x => x.endDate.nonEmpty)

      roadBeforeChanges.linkId should be(roadAfterPublishing.linkId)
      roadBeforeChanges.roadNumber should be(roadAfterPublishing.roadNumber)
      roadBeforeChanges.roadPartNumber should be(roadAfterPublishing.roadPartNumber)
      endedAddress.isEmpty should be(true)
      roadAfterPublishing.startDate.get.toString("yyyy-MM-dd") should be("1901-01-01")
      roadAfterPublishing.commonHistoryId should be(commonHistoryId)
    }
  }

  test("Calculate delta for project") {
    var count = 0
    val roadlink = RoadLink(5170939L, Seq(Point(535605.272, 6982204.22, 85.90899999999965))
      , 540.3960283713503, State, 99, TrafficDirection.AgainstDigitizing, UnknownLinkType, Some("25.06.2015 03:00:00"), Some("vvh_modified"), Map("MUNICIPALITYCODE" -> BigInt.apply(749)),
      InUse, NormalLinkInterface)
    runWithRollback {
      val countCurrentProjects = projectService.getRoadAddressAllProjects
      val addresses = List(ReservedRoadPart(Sequences.nextViitePrimaryKeySeqValue, 5L, 205L, Some(5L), Some(Discontinuity.apply("jatkuva")), Some(8L), newLength = None, newDiscontinuity = None, newEly = None))
      val roadAddressProject = RoadAddressProject(0, ProjectState.apply(1), "TestProject", "TestUser", DateTime.now(), "TestUser", DateTime.parse("1901-01-01"), DateTime.now(), "Some additional info", List(), None)
      val saved = projectService.createRoadLinkProject(roadAddressProject)
      mockForProject(saved.id, RoadAddressDAO.fetchByRoadPart(5, 205).map(toProjectLink(saved)))
      val changed = saved.copy(reservedParts = addresses)
      projectService.saveProject(changed)
      val countAfterInsertProjects = projectService.getRoadAddressAllProjects
      val projectLinks = ProjectDAO.fetchByProjectRoadPart(5, 205, saved.id)
      projectLinks.nonEmpty should be(true)
      count = countCurrentProjects.size + 1
      countAfterInsertProjects.size should be(count)
      sqlu"""UPDATE Project_link set status = ${LinkStatus.Terminated.value} Where PROJECT_ID = ${saved.id}""".execute
      val terminations = ProjectDeltaCalculator.delta(saved).terminations
      terminations should have size (projectLinks.size)
      sqlu"""UPDATE Project_link set status = ${LinkStatus.New.value} Where PROJECT_ID = ${saved.id}""".execute
      val newCreations = ProjectDeltaCalculator.delta(saved).newRoads
      newCreations should have size (projectLinks.size)
      val sections = ProjectDeltaCalculator.partition(terminations)
      sections should have size (2)
      sections.exists(_.track == Track.LeftSide) should be(true)
      sections.exists(_.track == Track.RightSide) should be(true)
      sections.groupBy(_.endMAddr).keySet.size should be(1)
    }
    runWithRollback {
      projectService.getRoadAddressAllProjects
    } should have size (count - 1)
  }

  test("process roadChange data and expire the roadLink") {
    //First Create Mock Project, RoadLinks and

    runWithRollback {
      var projectId = 0L
      val roadNumber = 1943845
      val roadPartNumber = 1
      val linkId = 12345L
      val commonHistoryId = 123
      //Creation of Test road
      val id = RoadAddressDAO.getNextRoadAddressId
      val ra = Seq(RoadAddress(id, roadNumber, roadPartNumber, RoadType.Unknown, Track.Combined, Discontinuous, 0L, 10L,
        Some(DateTime.parse("1901-01-01")), None, Option("tester"), 0, linkId, 0.0, 9.8, SideCode.TowardsDigitizing, 0, (None, None), false,
        Seq(Point(0.0, 0.0), Point(0.0, 9.8)), LinkGeomSource.NormalLinkInterface, 5, NoTermination, commonHistoryId))
      RoadAddressDAO.create(ra)
      val roadsBeforeChanges = RoadAddressDAO.fetchByLinkId(Set(linkId)).head

      when(mockRoadLinkService.getRoadLinksHistoryFromVVH(any[Set[Long]])).thenReturn(Seq())
      when(mockRoadLinkService.getRoadLinksByLinkIdsFromVVH(any[Set[Long]], any[Boolean], any[Boolean])).thenReturn(Seq(RoadLink(linkId, ra.head.geometry, 9.8, State, 1, TrafficDirection.BothDirections,
        Motorway, None, None, Map("MUNICIPALITYCODE" -> BigInt(167)))))
      when(mockRoadLinkService.getVVHRoadlinks(any[Set[Long]], any[Boolean])).thenReturn(Seq(VVHRoadlink(linkId, 167,
        ra.head.geometry, State, TrafficDirection.BothDirections, FeatureClass.AllOthers, None, Map("MUNICIPALITYCODE" -> BigInt(167)),
        ConstructionType.InUse, LinkGeomSource.NormalLinkInterface)))
      when(mockRoadLinkService.getSuravageRoadLinksFromVVH(Set(linkId))).thenReturn(Seq())
      //Creation of test project with test links
      val project = RoadAddressProject(projectId, ProjectState.Incomplete, "testiprojekti", "Test", DateTime.now(), "Test",
        DateTime.parse("2020-01-01"), DateTime.now(), "info",
        List(ReservedRoadPart(Sequences.nextViitePrimaryKeySeqValue: Long, roadNumber: Long, roadPartNumber: Long, Some(5L), Some(Discontinuity.apply("jatkuva")), Some(8L), newLength = None, newDiscontinuity = None, newEly = None)), None)
      when(mockRoadLinkService.getRoadLinksHistoryFromVVH(any[Set[Long]])).thenReturn(Seq())
      val proj = projectService.createRoadLinkProject(project)
      projectId = proj.id
      val projectLinkId = proj.reservedParts.head.startingLinkId.get
      val link = ProjectDAO.getProjectLinksByLinkIdAndProjectId(projectLinkId, projectId).head
      val terminatedValue = LinkStatus.Terminated.value
      //Changing the status of the test link
      sqlu"""Update Project_Link Set Status = $terminatedValue
            Where ID = ${link.id}""".execute

      //Creation of test road_address_changes
      sqlu"""insert into road_address_changes
             (project_id,change_type,new_road_number,new_road_part_number,new_track_code,new_start_addr_m,new_end_addr_m,new_discontinuity,new_road_type,new_ely,
              old_road_number,old_road_part_number,old_track_code,old_start_addr_m,old_end_addr_m)
             Values ($projectId,5,$roadNumber,$roadPartNumber,1,0,10,1,1,8,$roadNumber,$roadPartNumber,1,0,10)""".execute

      projectService.updateRoadAddressWithProjectLinks(ProjectState.Saved2TR, projectId)

      val roadsAfterChanges = RoadAddressDAO.fetchByLinkId(Set(linkId))
      roadsAfterChanges.filterNot(_.endDate.get.isAfter(DateTime.now())).size should be(1)
      val endedAddress = roadsAfterChanges.filter(x => x.endDate.nonEmpty && x.endDate.get.isAfter(DateTime.now()))
      endedAddress.head.endDate.nonEmpty should be(true)
      endedAddress.size should be(1)
      endedAddress.head.endDate.get.toString("yyyy-MM-dd") should be("2020-01-01")
      endedAddress.head.commonHistoryId should be(commonHistoryId)
      sql"""SELECT id FROM PROJECT_LINK WHERE project_id=$projectId""".as[Long].firstOption should be(None)
      sql"""SELECT id FROM PROJECT_RESERVED_ROAD_PART WHERE project_id=$projectId""".as[Long].firstOption should be(None)
    }
  }

  test("road name exists on TR success response") {
    runWithRollback {
      val projectId = Sequences.nextViitePrimaryKeySeqValue
      sqlu"""INSERT INTO ROAD_NAMES VALUES (ROAD_NAME_SEQ.nextval, 66666, 'road name test', TIMESTAMP '2018-03-23 12:26:36.000000', null, TIMESTAMP '2018-03-23 12:26:36.000000', null, 'test user', TIMESTAMP '2018-03-23 12:26:36.000000')""".execute

      sqlu"""INSERT INTO PROJECT VALUES($projectId, 2, 'test project', 8, 'silari', TIMESTAMP '2018-03-23 11:36:15.000000', '-', TIMESTAMP '2018-03-23 12:26:33.000000', NULL, TIMESTAMP '2018-03-23 00:00:00.000000', NULL, 0, 1, 533406.572, 6994060.048, 12)""".execute
      sqlu"""INSERT INTO PROJECT_RESERVED_ROAD_PART VALUES (${Sequences.nextViitePrimaryKeySeqValue}, 66666, 1, $projectId, '-')""".execute
      sqlu"""INSERT INTO LRM_POSITION VALUES (lrm_position_primary_key_seq.nextval, 0, 2, 0, 85.617, NULL, 5170979, 1500079296000, TIMESTAMP '2018-03-23 00:00:00.000000', 1)""".execute
      sqlu"""INSERT INTO PROJECT_LINK VALUES (${Sequences.nextViitePrimaryKeySeqValue}, $projectId, 0, 5, 66666, 1, 0, 86, lrm_position_primary_key_seq.currval, 'test user', 'test user', TIMESTAMP '2018-03-23 12:26:36.000000', TIMESTAMP '2018-03-23 00:00:00.000000', 2, 3, 1, NULL, NULL, 8, 0, '[533399.731,6994038.906,126.260],[533390.742,6994052.408,126.093],[533387.649,6994056.057,126.047],[533348.256,6994107.273,125.782]')""".execute
      sqlu"""INSERT INTO PROJECT_LINK_NAME VALUES (PROJECT_LINK_NAME_SEQ.nextval, $projectId, 66666, 'another road name test')""".execute
      val namesBeforeUpdate = RoadNameDAO.getLatestRoadName(66666)
      projectService.updateRoadAddressWithProjectLinks(ProjectState.Saved2TR, projectId)

      val project = projectService.getRoadAddressSingleProject(projectId)
      val namesAfterUpdate = RoadNameDAO.getLatestRoadName(66666)
      project.get.statusInfo.getOrElse("") should be(roadNameWasNotSavedInProject + s"${66666}")
      namesAfterUpdate.get.roadName should be(namesBeforeUpdate.get.roadName)
    }
  }

  test("road name is saved on TR success response") {
    runWithRollback {
      val projectId = Sequences.nextViitePrimaryKeySeqValue
      sqlu"""INSERT INTO PROJECT VALUES($projectId, 2, 'test project', 8, 'silari', TIMESTAMP '2018-03-23 11:36:15.000000', '-', TIMESTAMP '2018-03-23 12:26:33.000000', NULL, TIMESTAMP '2018-03-23 00:00:00.000000', NULL, 0, 1, 533406.572, 6994060.048, 12)""".execute
      sqlu"""INSERT INTO PROJECT_RESERVED_ROAD_PART VALUES (${Sequences.nextViitePrimaryKeySeqValue}, 66666, 1, $projectId, '-')""".execute
      sqlu"""INSERT INTO LRM_POSITION VALUES (lrm_position_primary_key_seq.nextval, 0, 2, 0, 85.617, NULL, 5170979, 1500079296000, TIMESTAMP '2018-03-23 00:00:00.000000', 1)""".execute
      sqlu"""INSERT INTO PROJECT_LINK VALUES (${Sequences.nextViitePrimaryKeySeqValue}, $projectId, 0, 5, 66666, 1, 0, 86, lrm_position_primary_key_seq.currval, 'test user', 'test user', TIMESTAMP '2018-03-23 12:26:36.000000', TIMESTAMP '2018-03-23 00:00:00.000000', 2, 3, 1, NULL, NULL, 8, 0, '[533399.731,6994038.906,126.260],[533390.742,6994052.408,126.093],[533387.649,6994056.057,126.047],[533348.256,6994107.273,125.782]')""".execute
      sqlu"""INSERT INTO PROJECT_LINK_NAME VALUES (PROJECT_LINK_NAME_SEQ.nextval, $projectId, 66666, 'road name test')""".execute
      val namesBeforeUpdate = RoadNameDAO.getLatestRoadName(66666)
      projectService.updateRoadAddressWithProjectLinks(ProjectState.Saved2TR, projectId)

      val project = projectService.getRoadAddressSingleProject(projectId)
      val namesAfterUpdate = RoadNameDAO.getLatestRoadName(66666)
      project.get.statusInfo should be(None)
      namesAfterUpdate.get.roadName should be("road name test")
    }
  }

  test("verify existence of roadAddressNumbersAndSEParts") {
    val roadNumber = 1943845
    val roadStartPart = 1
    val roadEndPart = 2
    runWithRollback {
      val id1 = RoadAddressDAO.getNextRoadAddressId
      val id2 = RoadAddressDAO.getNextRoadAddressId
      val ra = Seq(RoadAddress(id1, roadNumber, roadStartPart, RoadType.Unknown, Track.Combined, Discontinuous, 0L, 10L,
        Some(DateTime.parse("1901-01-01")), None, Option("tester"), 0, 12345L, 0.0, 9.8, SideCode.TowardsDigitizing, 0, (None, None), false,
        Seq(Point(0.0, 0.0), Point(0.0, 9.8)), LinkGeomSource.NormalLinkInterface, 5, NoTermination, 0))
      val rb = Seq(RoadAddress(id2, roadNumber, roadEndPart, RoadType.Unknown, Track.Combined, Discontinuous, 0L, 10L,
        Some(DateTime.parse("1901-01-01")), None, Option("tester"), 0, 12345L, 0.0, 9.8, SideCode.TowardsDigitizing, 0, (None, None), false,
        Seq(Point(0.0, 0.0), Point(0.0, 9.8)), LinkGeomSource.NormalLinkInterface, 5, NoTermination, 0))
      when(mockRoadLinkService.getRoadLinksHistoryFromVVH(any[Set[Long]])).thenReturn(Seq())
      val shouldNotExist = projectService.checkRoadPartsExist(roadNumber, roadStartPart, roadEndPart)
      shouldNotExist.get should be("Tienumeroa ei ole olemassa, tarkista tiedot")
      RoadAddressDAO.create(ra)
      val roadNumberShouldNotExist = projectService.checkRoadPartsExist(roadNumber, roadStartPart + 1, roadEndPart)
      roadNumberShouldNotExist.get should be("Tiellä ei ole olemassa valittua alkuosaa, tarkista tiedot")
      val endingPartShouldNotExist = projectService.checkRoadPartsExist(roadNumber, roadStartPart, roadEndPart)
      endingPartShouldNotExist.get should be("Tiellä ei ole olemassa valittua loppuosaa, tarkista tiedot")
      RoadAddressDAO.create(rb)
      val allIsOk = projectService.checkRoadPartsExist(roadNumber, roadStartPart, roadEndPart)
      allIsOk should be(None)
    }
  }

  test("check reservability of a road") {
    val roadNumber = 1943845
    val roadStartPart = 1
    val roadEndPart = 2
    val roadlink = RoadLink(12345L, Seq(Point(535605.272, 6982204.22, 85.90899999999965))
      , 540.3960283713503, State, 99, TrafficDirection.AgainstDigitizing, UnknownLinkType, Some("25.06.2015 03:00:00"), Some("vvh_modified"), Map("MUNICIPALITYCODE" -> BigInt.apply(749)),
      InUse, NormalLinkInterface)
    when(mockRoadLinkService.getRoadLinksByLinkIdsFromVVH(any[Set[Long]], any[Boolean], any[Boolean])).thenReturn(Seq(roadlink))
    runWithRollback {
      val id1 = RoadAddressDAO.getNextRoadAddressId
      val ra = Seq(RoadAddress(id1, roadNumber, roadStartPart, RoadType.Unknown, Track.Combined, Discontinuous, 0L, 10L,
        Some(DateTime.parse("1901-01-01")), None, Option("tester"), 0, 12345L, 0.0, 9.8, SideCode.TowardsDigitizing, 0, (None, None), false,
        Seq(Point(0.0, 0.0), Point(0.0, 9.8)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val reservation = projectService.checkRoadPartsReservable(roadNumber, roadStartPart, roadEndPart)
      reservation.right.get.size should be(0)
      RoadAddressDAO.create(ra)
      val id2 = RoadAddressDAO.getNextRoadAddressId
      val rb = Seq(RoadAddress(id2, roadNumber, roadEndPart, RoadType.Unknown, Track.Combined, Discontinuous, 0L, 10L,
        Some(DateTime.parse("1901-01-01")), None, Option("tester"), 0, 12345L, 0.0, 9.8, SideCode.TowardsDigitizing, 0, (None, None), false,
        Seq(Point(0.0, 0.0), Point(0.0, 9.8)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      RoadAddressDAO.create(rb)
      val reservationAfterB = projectService.checkRoadPartsReservable(roadNumber, roadStartPart, roadEndPart)
      reservationAfterB.right.get.size should be(2)
      reservationAfterB.right.get.map(_.roadNumber).distinct.size should be(1)
      reservationAfterB.right.get.map(_.roadNumber).distinct.head should be(roadNumber)
    }
  }

  test("check the length of a road") {
    val roadNumber = 1943845
    val roadStartPart = 1
    val roadEndPart = 2
    val linkId = 12345L
    val startDate = Some(DateTime.parse("1901-01-01"))
    val roadLink = RoadLink(linkId, Seq(Point(535605.272, 6982204.22, 85.90899999999965)),
      540.3960283713503, State, 99, TrafficDirection.AgainstDigitizing, UnknownLinkType, Some("25.06.2015 03:00:00"),
      Some("vvh_modified"), Map("MUNICIPALITYCODE" -> BigInt.apply(749)), InUse, NormalLinkInterface)
    when(mockRoadLinkService.getRoadLinksByLinkIdsFromVVH(any[Set[Long]], any[Boolean], any[Boolean])).thenReturn(Seq(roadLink))
    runWithRollback {
      val id1 = RoadAddressDAO.getNextRoadAddressId
      val ra = Seq(RoadAddress(id1, roadNumber, roadStartPart, RoadType.Unknown, Track.Combined, Discontinuous, 0L, 10L,
        startDate, None, Option("tester"), 0, linkId, 0.0, 9.8, SideCode.TowardsDigitizing, 0, (None, None), false,
        Seq(Point(0.0, 0.0), Point(0.0, 9.8)), NormalLinkInterface, 8, NoTermination, 0))
      val reservation = projectService.checkRoadPartsReservable(roadNumber, roadStartPart, roadEndPart)
      reservation.right.get.size should be(0)
      RoadAddressDAO.create(ra)
      val id2 = RoadAddressDAO.getNextRoadAddressId
      val ra2 = Seq(RoadAddress(id2, roadNumber, roadEndPart, RoadType.Unknown, Track.Combined, Discontinuous, 0L, 10L,
        startDate, None, Option("tester"), 0, linkId, 0.0, 9.8, SideCode.TowardsDigitizing, 0, (None, None), false,
        Seq(Point(0.0, 0.0), Point(0.0, 9.8)), NormalLinkInterface, 8, NoTermination, 0))
      RoadAddressDAO.create(ra2)
      //inserting a historic road for part 2
      val id3 = RoadAddressDAO.getNextRoadAddressId
      val ra3 = Seq(RoadAddress(id3, roadNumber, roadEndPart, RoadType.Unknown, Track.Combined, Discontinuous, 10L, 25L,
        startDate, Some(DateTime.parse("1901-02-03")), Option("tester"), 0, linkId, 0.0, 15.0, SideCode.TowardsDigitizing, 0, (None, None), false,
        Seq(Point(0.0, 9.8), Point(0.0, 25)), NormalLinkInterface, 8, NoTermination, 0))
      RoadAddressDAO.create(ra3)
      val reservationAfterB = projectService.checkRoadPartsReservable(roadNumber, roadStartPart, roadEndPart)
      reservationAfterB.right.get.size should be(2)
      reservationAfterB.right.get.map(_.roadNumber).distinct.size should be(1)
      reservationAfterB.right.get.map(_.roadNumber).distinct.head should be(roadNumber)
      val part2AddrLength = (ra2 ++ ra3).filter(_.endDate.isEmpty).maxBy(_.endAddrMValue).endAddrMValue
      reservationAfterB.right.get.filter(r => r.roadPartNumber == roadEndPart).head.addressLength.get should be(part2AddrLength)
    }
  }

  test("get the road address project") {
    var count = 0
    runWithRollback {
      val roadlink = RoadLink(12345L, Seq(Point(535605.272, 6982204.22, 85.90899999999965))
        , 540.3960283713503, State, 99, TrafficDirection.AgainstDigitizing, UnknownLinkType, Some("25.06.2015 03:00:00"), Some("vvh_modified"), Map("MUNICIPALITYCODE" -> BigInt.apply(749)),
        InUse, NormalLinkInterface)
      val countCurrentProjects = projectService.getRoadAddressAllProjects
      val addresses: List[ReservedRoadPart] = List(ReservedRoadPart(Sequences.nextViitePrimaryKeySeqValue: Long, 5: Long, 203: Long, Some(5L), Some(Discontinuity.apply("jatkuva")), Some(8L), newLength = None, newDiscontinuity = None, newEly = None))
      val roadAddressProject = RoadAddressProject(0, ProjectState.apply(1), "TestProject", "TestUser", DateTime.now(), "TestUser", DateTime.parse("1901-01-01"), DateTime.now(), "Some additional info", Seq(), None)
      val saved = projectService.createRoadLinkProject(roadAddressProject)
      mockForProject(saved.id, RoadAddressDAO.fetchByRoadPart(5, 203).map(toProjectLink(saved)))
      projectService.saveProject(saved.copy(reservedParts = addresses))
      when(mockRoadLinkService.getRoadLinksHistoryFromVVH(any[Set[Long]])).thenReturn(Seq())
      val countAfterInsertProjects = projectService.getRoadAddressAllProjects
      count = countCurrentProjects.size + 1
      countAfterInsertProjects.size should be(count)
      val project = projectService.getRoadAddressSingleProject(saved.id)
      project.size should be(1)
      project.head.name should be("TestProject")
    }
    runWithRollback {
      projectService.getRoadAddressAllProjects.size should be(count - 1)
    }
  }

  test("Check for new roadaddress reservation") {
    var count = 0
    runWithRollback {
      reset(mockRoadLinkService)
      val roadlink = RoadLink(12345L, Seq(Point(535605.272, 6982204.22, 85.90899999999965))
        , 540.3960283713503, State, 99, TrafficDirection.AgainstDigitizing, UnknownLinkType, Some("25.06.2015 03:00:00"), Some("vvh_modified"), Map("MUNICIPALITYCODE" -> BigInt.apply(749)),
        InUse, NormalLinkInterface)
      val countCurrentProjects = projectService.getRoadAddressAllProjects
      val addresses: List[ReservedRoadPart] = List(ReservedRoadPart(Sequences.nextViitePrimaryKeySeqValue: Long, 5: Long, 203: Long, Some(5L), Some(Discontinuity.apply("jatkuva")), Some(8L), newLength = None, newDiscontinuity = None, newEly = None))
      val roadAddressProject = RoadAddressProject(0, ProjectState.apply(1), "TestProject", "TestUser", DateTime.now(), "TestUser", DateTime.parse("1901-01-01"), DateTime.now(), "Some additional info", Seq(), None)
      val saved = projectService.createRoadLinkProject(roadAddressProject)
      mockForProject(saved.id, RoadAddressDAO.fetchByRoadPart(5, 203).map(toProjectLink(saved)))
      projectService.saveProject(saved.copy(reservedParts = addresses))
      val countAfterInsertProjects = projectService.getRoadAddressAllProjects
      count = countCurrentProjects.size + 1
      countAfterInsertProjects.size should be(count)
      val project = projectService.getRoadAddressSingleProject(saved.id)
      project.size should be(1)
      project.head.name should be("TestProject")
    }
    runWithRollback {
      projectService.getRoadAddressAllProjects.size should be(count - 1)
    }
  }

  test("Project ELY -1 update when reserving roadpart and revert to -1 when all reserved roadparts are removed") {
    val projectIdNew = 0L
    val roadNumber = 1943845
    val roadPartNumber = 1
    val linkId = 12345L

    runWithRollback {

      //Creation of Test road
      val id = RoadAddressDAO.getNextRoadAddressId
      val ra = Seq(RoadAddress(id, roadNumber, roadPartNumber, RoadType.Unknown, Track.Combined, Discontinuous, 0L, 10L,
        Some(DateTime.parse("1901-01-01")), None, Option("tester"), 0, linkId, 0.0, 9.8, SideCode.TowardsDigitizing, 0, (None, None), false,
        Seq(Point(0.0, 0.0), Point(0.0, 9.8)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val rl = RoadLink(linkId, ra.head.geometry, 9.8, State, 1, TrafficDirection.BothDirections,
        Motorway, None, None, Map("MUNICIPALITYCODE" -> BigInt(167)))
      when(mockRoadLinkService.getRoadLinksHistoryFromVVH(any[Set[Long]])).thenReturn(Seq())
      when(mockRoadLinkService.getRoadLinksByLinkIdsFromVVH(any[Set[Long]], any[Boolean], any[Boolean])).thenReturn(Seq(rl))
      RoadAddressDAO.create(ra)
      val addresses: List[ReservedRoadPart] = List(ReservedRoadPart(Sequences.nextViitePrimaryKeySeqValue: Long, 5: Long, 203: Long, Some(5L), Some(Discontinuity.apply("jatkuva")), Some(8L), newLength = None, newDiscontinuity = None, newEly = None))

      //Creation of test project with test links
      val project = RoadAddressProject(projectIdNew, ProjectState.Incomplete, "testiprojekti", "Test", DateTime.now(), "Test",
        DateTime.now(), DateTime.now(), "info",
        List.empty, None)
      val proj = projectService.createRoadLinkProject(project)
      val returnedProject = projectService.getRoadAddressSingleProject(proj.id).get
      returnedProject.name should be("testiprojekti")
      returnedProject.ely.getOrElse(-1) should be(-1)
      mockForProject(proj.id, RoadAddressDAO.fetchByRoadPart(5, 203).map(toProjectLink(proj)))
      val projupdated = projectService.saveProject(proj.copy(reservedParts = addresses))
      val updatedReturnProject = projectService.getRoadAddressSingleProject(proj.id).head
      updatedReturnProject.ely.getOrElse(-1) should be(8)
      projectService.saveProject(proj.copy(ely = None)) //returns project to null
      val updatedReturnProject2 = projectService.getRoadAddressSingleProject(proj.id).head
      updatedReturnProject2.ely.getOrElse(-1) should be(-1)
      projectService.saveProject(proj.copy(reservedParts = addresses))
      val updatedReturnProject3 = projectService.getRoadAddressSingleProject(proj.id).head
      updatedReturnProject3.ely.getOrElse(-1) should be(8)
    }
  }

  test("get the project with it's reserved road parts") {
    var projectId = 0L
    val roadNumber = 1943845
    val roadPartNumber = 1
    val linkId = 12345L

    runWithRollback {

      //Creation of Test road
      val id = RoadAddressDAO.getNextRoadAddressId
      val ra = Seq(RoadAddress(id, roadNumber, roadPartNumber, RoadType.Unknown, Track.Combined, Discontinuous, 0L, 10L,
        Some(DateTime.parse("1901-01-01")), None, Option("tester"), 0, linkId, 0.0, 9.8, SideCode.TowardsDigitizing, 0, (None, None), false,
        Seq(Point(0.0, 0.0), Point(0.0, 9.8)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val rl = RoadLink(linkId, ra.head.geometry, 9.8, State, 1, TrafficDirection.BothDirections,
        Motorway, None, None, Map("MUNICIPALITYCODE" -> BigInt(167)))
      when(mockRoadLinkService.getRoadLinksHistoryFromVVH(any[Set[Long]])).thenReturn(Seq())
      when(mockRoadLinkService.getRoadLinksByLinkIdsFromVVH(any[Set[Long]], any[Boolean], any[Boolean])).thenReturn(Seq(rl))
      RoadAddressDAO.create(ra)

      //Creation of test project with test links
      val project = RoadAddressProject(projectId, ProjectState.Incomplete, "testiprojekti", "Test", DateTime.now(), "Test",
        DateTime.now(), DateTime.now(), "info",
        List(ReservedRoadPart(Sequences.nextViitePrimaryKeySeqValue: Long, roadNumber: Long, roadPartNumber: Long, Some(5L), Some(Discontinuity.apply("jatkuva")), Some(8L), newLength = None, newDiscontinuity = None, newEly = None)), None)
      val proj = projectService.createRoadLinkProject(project)
      val returnedProject = projectService.getRoadAddressSingleProject(proj.id).get
      returnedProject.name should be("testiprojekti")
      returnedProject.reservedParts.size should be(1)
      returnedProject.reservedParts.head.roadNumber should be(roadNumber)
    }

  }

  test("error message when reserving already used road number&part (in other project ids). Empty error message if same road number&part but == proj id ") {
    runWithRollback {
      val idr = RoadAddressDAO.getNextRoadAddressId
      val id = Sequences.nextViitePrimaryKeySeqValue
      val rap = RoadAddressProject(id, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("2700-01-01"), "TestUser", DateTime.parse("1972-03-03"), DateTime.parse("2700-01-01"), "Some additional info", List.empty[ReservedRoadPart], None)
      val projectLink = toProjectLink(rap, LinkStatus.New)(RoadAddress(idr, 5, 203, RoadType.Unknown, Track.Combined, Discontinuous,
        0L, 10L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), 0, 12345L, 0.0, 9.8,
        SideCode.TowardsDigitizing, 0, (None, None), false, Seq(Point(0.0, 0.0), Point(0.0, 9.8)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      ProjectDAO.createRoadAddressProject(rap)

      val rap2 = RoadAddressProject(id + 1, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("2700-01-01"), "TestUser", DateTime.parse("1972-03-04"), DateTime.parse("2700-01-01"), "Some additional info", List.empty[ReservedRoadPart], None)
      val projectLink2 = toProjectLink(rap2, LinkStatus.New)(RoadAddress(idr, 5, 999, RoadType.Unknown, Track.Combined, Discontinuous,
        0L, 10L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), 0, 12345L, 0.0, 9.8,
        SideCode.TowardsDigitizing, 0, (None, None), false, Seq(Point(0.0, 0.0), Point(0.0, 9.8)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      ProjectDAO.createRoadAddressProject(rap2)

      val projectLink3 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idr, 5, 999, RoadType.Unknown, Track.Combined, Discontinuous,
        0L, 10L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), 0, 12345L, 0.0, 9.8,
        SideCode.TowardsDigitizing, 0, (None, None), false, Seq(Point(0.0, 0.0), Point(0.0, 9.8)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))

      val p = ProjectAddressLink(idr, projectLink.linkId, projectLink.geometry,
        1, AdministrativeClass.apply(1), LinkType.apply(1), RoadLinkType.apply(1), ConstructionType.apply(1), projectLink.linkGeomSource, RoadType.PublicUnderConstructionRoad, Some(""), None, 111, Some(""), Some("vvh_modified"),
        Map(), projectLink.roadNumber, projectLink.roadPartNumber, 2, -1, projectLink.discontinuity.value,
        projectLink.startAddrMValue, projectLink.endAddrMValue, projectLink.startMValue, projectLink.endMValue,
        projectLink.sideCode,
        projectLink.calibrationPoints._1,
        projectLink.calibrationPoints._2, Anomaly.None, projectLink.lrmPositionId, projectLink.status, 0)

      mockForProject(id, Seq(p))

      val message1project1 = projectService.addNewLinksToProject(Seq(projectLink), id, "U", p.linkId).getOrElse("")
      val links = ProjectDAO.getProjectLinks(id)
      links.size should be(0)
      message1project1 should be("TIE 5 OSA 203 on jo olemassa projektin alkupäivänä 03.03.1972, tarkista tiedot") //check that it is reserved in roadaddress table

      val message1project2 = projectService.addNewLinksToProject(Seq(projectLink2), id + 1, "U", p.linkId)
      val links2 = ProjectDAO.getProjectLinks(id + 1)
      links2.size should be(1)
      message1project2 should be(None)

      val message2project1 = projectService.addNewLinksToProject(Seq(projectLink3), id, "U", p.linkId).getOrElse("")
      val links3 = ProjectDAO.getProjectLinks(id)
      links3.size should be(0)
      message2project1 should be("TIE 5 OSA 999 on jo varattuna projektissa TestProject, tarkista tiedot")
    }
  }

  test("parsePrefillData no-link from vvh") {
    projectService.parsePreFillData(Seq.empty[VVHRoadlink]) should be(Left("Link could not be found in VVH"))
  }

  test("parsePrefillData contains correct info") {
    runWithRollback{
      val attributes1 = Map("ROADNUMBER" -> BigInt(100), "ROADPARTNUMBER" -> BigInt(100))
      val newRoadLink1 = VVHRoadlink(1, 2, List(Point(0.0, 0.0), Point(20.0, 0.0)), AdministrativeClass.apply(1), TrafficDirection.BothDirections, FeatureClass.DrivePath, None, attributes1)
      projectService.parsePreFillData(Seq(newRoadLink1)) should be(Right(PreFillInfo(100, 100, "")))
    }
  }

  test("parsePrefillData contains correct info with road name pre fillled") {
    runWithRollback{
      sqlu"""INSERT INTO ROAD_NAMES VALUES (ROAD_NAME_SEQ.nextval, 100, 'road name test', TIMESTAMP '2018-03-23 12:26:36.000000', null, TIMESTAMP '2018-03-23 12:26:36.000000', null, 'test user', TIMESTAMP '2018-03-23 12:26:36.000000')""".execute
      val attributes1 = Map("ROADNUMBER" -> BigInt(100), "ROADPARTNUMBER" -> BigInt(100))
      val newRoadLink1 = VVHRoadlink(1, 2, List(Point(0.0, 0.0), Point(20.0, 0.0)), AdministrativeClass.apply(1), TrafficDirection.BothDirections, FeatureClass.DrivePath, None, attributes1)
      projectService.parsePreFillData(Seq(newRoadLink1)) should be(Right(PreFillInfo(100, 100, "road name test")))
    }
  }

  test("parsePrefillData incomplete data") {
    val attributes1 = Map("ROADNUMBER" -> BigInt(2))
    val newRoadLink1 = VVHRoadlink(1, 2, List(Point(0.0, 0.0), Point(20.0, 0.0)), AdministrativeClass.apply(1), TrafficDirection.BothDirections, FeatureClass.DrivePath, None, attributes1)
    projectService.parsePreFillData(Seq(newRoadLink1)) should be(Left("Link does not contain valid prefill info"))
  }

  test("changing project ELY") {
    runWithRollback {
      val roadAddressProject = RoadAddressProject(0, ProjectState.apply(1), "TestProject", "TestUser", DateTime.now(), "TestUser", DateTime.parse("1901-01-01"), DateTime.now(), "Some additional info", List.empty[ReservedRoadPart], None, None)
      when(mockRoadLinkService.getRoadLinksHistoryFromVVH(any[Set[Long]])).thenReturn(Seq())
      val project = projectService.createRoadLinkProject(roadAddressProject)
      project.ely should be(None)
      val result = projectService.setProjectEly(project.id, 2)
      result should be(None)
      val result2 = projectService.setProjectEly(project.id, 2)
      result2 should be(None)
      val result3 = projectService.setProjectEly(project.id, 3)
      result3.isEmpty should be(false)
    }
  }

  test("split road address save behaves correctly on transfer + new") {
    val road = 5L
    val roadPart = 205L
    val origStartM = 1024L
    val origEndM = 1547L
    val origStartD = Some(DateTime.now().minusYears(10))
    val linkId = 1049L
    val endM = 520.387
    val suravageLinkId = 5774839L
    val user = Some("user")
    val project = RoadAddressProject(-1L, Sent2TR, "split", user.get, DateTime.now(), user.get,
      DateTime.now().plusMonths(2), DateTime.now(), "", Seq(), None, None)

    // Original road address: 1024 -> 1547
    val roadAddress = RoadAddress(1L, road, roadPart, PublicRoad, Track.Combined, Continuous, origStartM, origEndM, origStartD,
      None, None, 1L, linkId, 0.0, endM, SideCode.TowardsDigitizing, 86400L, (None, None), false, Seq(Point(1024.0, 0.0), Point(1025.0, 1544.386)),
      LinkGeomSource.NormalLinkInterface, 8L, NoTermination, 123)

    val projectLink = ProjectLink(0, road, roadPart, Track.Combined, Continuous, 0, 0, Some(DateTime.now()), None, user,
      0, 0, 0.0, 0.0, SideCode.TowardsDigitizing, (None, None), false, Seq(Point(0.0, 0.0), Point(0.0, 0.0)),
      -1L, null, PublicRoad, null, 0.0, 1L, 8L, false, None, 748800L)
    val transferAndNew = Seq(

      // Transferred road address: 1028 -> 1128
      projectLink.copy(id = 2, startAddrMValue = origStartM + 4, endAddrMValue = origStartM + 104, lrmPositionId = 2, linkId = suravageLinkId,
        startMValue = 0.0, endMValue = 99.384, geometry = Seq(Point(1024.0, 0.0), Point(1024.0, 99.384)), status = LinkStatus.Transfer,
        linkGeomSource = LinkGeomSource.SuravageLinkInterface, geometryLength = 99.384, connectedLinkId = Some(linkId)),

      // New road address: 1128 -> 1205
      projectLink.copy(id = 3, startAddrMValue = origStartM + 104, endAddrMValue = origStartM + 181, lrmPositionId = 3, linkId = suravageLinkId,
        startMValue = 99.384, endMValue = 176.495, geometry = Seq(Point(1024.0, 99.384), Point(1101.111, 99.384)), status = LinkStatus.New,
        linkGeomSource = LinkGeomSource.SuravageLinkInterface, geometryLength = 77.111, connectedLinkId = Some(linkId)),

      // Terminated road address: 1124 -> 1547
      projectLink.copy(id = 4, startAddrMValue = origStartM + 100, endAddrMValue = origEndM, lrmPositionId = 4, linkId = linkId,
        startMValue = 99.384, endMValue = endM, geometry = Seq(Point(1024.0, 99.384), Point(1025.0, 1544.386)), status = LinkStatus.Terminated,
        linkGeomSource = LinkGeomSource.NormalLinkInterface, geometryLength = endM - 99.384, connectedLinkId = Some(suravageLinkId))

    )
    val result = projectService.createSplitRoadAddress(roadAddress, transferAndNew, project)
    result should have size 4
    result.count(_.terminated == TerminationCode.Termination) should be(1)
    result.count(_.startDate == roadAddress.startDate) should be(2)
    result.count(_.startDate.get == project.startDate) should be(2)
    result.count(_.endDate.isEmpty) should be(2)
  }

  test("split road address save behaves correctly on unchanged + new") {
    val road = 5L
    val roadPart = 205L
    val origStartM = 1024L
    val origEndM = 1547L
    val origStartD = Some(DateTime.now().minusYears(10))
    val linkId = 1049L
    val endM = 520.387
    val suravageLinkId = 5774839L
    val user = Some("user")
    val project = RoadAddressProject(-1L, Sent2TR, "split", user.get, DateTime.now(), user.get,
      DateTime.now().plusMonths(2), DateTime.now(), "", Seq(), None, None)
    val roadAddress = RoadAddress(1L, 5L, 205L, PublicRoad, Track.Combined, Continuous, origStartM, origEndM, origStartD,
      None, None, 1L, linkId, 0.0, endM, SideCode.TowardsDigitizing, 86400L, (None, None), false, Seq(Point(1024.0, 0.0), Point(1025.0, 1544.386)),
      LinkGeomSource.NormalLinkInterface, 8L, TerminationCode.NoTermination, 0)
    val unchangedAndNew = Seq(ProjectLink(2L, 5, 205, Track.Combined, Continuous, origStartM, origStartM + 100L, Some(DateTime.now()), None, user,
      2L, suravageLinkId, 0.0, 99.384, SideCode.TowardsDigitizing, (None, None), false, Seq(Point(1024.0, 0.0), Point(1024.0, 99.384)),
      -1L, LinkStatus.UnChanged, PublicRoad, LinkGeomSource.SuravageLinkInterface, 99.384, 1L, 8L, false, Some(linkId), 85088L),
      ProjectLink(3L, 5, 205, Track.Combined, Continuous, origStartM + 100L, origStartM + 177L, Some(DateTime.now()), None, user,
        3L, suravageLinkId, 99.384, 176.495, SideCode.TowardsDigitizing, (None, None), false, Seq(Point(1024.0, 99.384), Point(1101.111, 99.384)),
        -1L, LinkStatus.New, PublicRoad, LinkGeomSource.SuravageLinkInterface, 77.111, 1L, 8L, false, Some(linkId), 85088L),
      ProjectLink(4L, 5, 205, Track.Combined, Continuous, origStartM + 100L, origEndM, Some(DateTime.now()), None, user,
        4L, linkId, 99.384, endM, SideCode.TowardsDigitizing, (None, None), false, Seq(Point(1024.0, 99.384), Point(1025.0, 1544.386)),
        -1L, LinkStatus.Terminated, PublicRoad, LinkGeomSource.NormalLinkInterface, endM - 99.384, 1L, 8L, false, Some(suravageLinkId), 85088L))
    val result = projectService.createSplitRoadAddress(roadAddress, unchangedAndNew, project)
    result should have size (3)
    result.count(_.terminated == TerminationCode.Termination) should be(1)
    result.count(_.startDate == roadAddress.startDate) should be(2)
    result.count(_.startDate.get == project.startDate) should be(1)
    result.count(_.endDate.isEmpty) should be(2)
  }

  test("verify correction of a null ELY code project") {
    runWithRollback {
      val rap = RoadAddressProject(0L, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("1901-01-01"),
        "TestUser", DateTime.parse("1901-01-01"), DateTime.now(), "Some additional info",
        Seq(), None)
      val project = projectService.createRoadLinkProject(rap)
      val id = project.id
      val roadParts = RoadAddressDAO.fetchByRoadPart(5, 207).map(toProjectLink(project))
      mockForProject(id, roadParts)
      ProjectDAO.reserveRoadPart(id, roadParts.head.roadNumber, roadParts.head.roadPartNumber, "TestUser")
      ProjectDAO.create(Seq(roadParts.head))
      ProjectDAO.getProjectEly(project.id).isEmpty should be(true)
      projectService.correctNullProjectEly()
      ProjectDAO.getProjectEly(project.id).isEmpty should be(false)
    }
  }

  test("split road address is splitting historic versions") {
    runWithRollback {
      val road = 19999L
      val roadPart = 205L
      val origStartM = 0L
      val origEndM = 102L
      val origStartD = Some(DateTime.now().minusYears(10))
      val linkId = 1049L
      val endM = 102.04
      val suravageLinkId = 5774839L
      val user = Some("user")
      val roadAddress = RoadAddress(NewRoadAddress, road, roadPart, PublicRoad, Track.Combined, EndOfRoad, origStartM, origEndM, origStartD,
        None, None, 1L, linkId, 0.0, endM, SideCode.TowardsDigitizing, 86400L,
        (Some(CalibrationPoint(linkId, 0.0, origStartM)), Some(CalibrationPoint(linkId, endM, origEndM))),
        false, Seq(Point(1024.0, 0.0), Point(1024.0, 102.04)),
        LinkGeomSource.NormalLinkInterface, 8L, TerminationCode.NoTermination, 0)
      val roadAddressHistory = RoadAddress(NewRoadAddress, road, roadPart + 1, PublicRoad, Track.Combined, EndOfRoad, origStartM, origEndM,
        origStartD.map(_.minusYears(5)), origStartD.map(_.minusYears(15)),
        None, 1L, linkId, 0.0, endM, SideCode.TowardsDigitizing, 86400L, (None, None), false, Seq(Point(1024.0, 0.0), Point(1025.0, 1544.386)),
        LinkGeomSource.NormalLinkInterface, 8L, TerminationCode.NoTermination, 0)
      val roadAddressHistory2 = RoadAddress(NewRoadAddress, road, roadPart + 2, PublicRoad, Track.Combined, EndOfRoad, origStartM, origEndM,
        origStartD.map(_.minusYears(15)), origStartD.map(_.minusYears(20)),
        None, 1L, linkId, 0.0, endM, SideCode.TowardsDigitizing, 86400L, (None, None), false, Seq(Point(1024.0, 0.0), Point(1025.0, 1544.386)),
        LinkGeomSource.NormalLinkInterface, 8L, TerminationCode.NoTermination, 0)
      val id = RoadAddressDAO.create(Seq(roadAddress)).head
      RoadAddressDAO.create(Seq(roadAddressHistory, roadAddressHistory2))
      val project = RoadAddressProject(-1L, Sent2TR, "split", user.get, DateTime.now(), user.get,
        DateTime.now().plusMonths(2), DateTime.now(), "", Seq(), None, None)
      val unchangedAndNew = Seq(ProjectLink(2L, road, roadPart, Track.Combined, Continuous, origStartM, origStartM + 52L, Some(DateTime.now()), None, user,
        2L, suravageLinkId, 0.0, 51.984, SideCode.TowardsDigitizing, (Some(CalibrationPoint(linkId, 0.0, origStartM)), None),
        false, Seq(Point(1024.0, 0.0), Point(1024.0, 51.984)),
        -1L, LinkStatus.UnChanged, PublicRoad, LinkGeomSource.SuravageLinkInterface, 51.984, id, 8L, false, Some(linkId), 85088L),
        ProjectLink(3L, road, roadPart, Track.Combined, EndOfRoad, origStartM + 52L, origStartM + 177L, Some(DateTime.now()), None, user,
          3L, suravageLinkId, 51.984, 176.695, SideCode.TowardsDigitizing, (None, Some(CalibrationPoint(suravageLinkId, 176.695, origStartM + 177L))),
          false, Seq(Point(1024.0, 99.384), Point(1148.711, 99.4)),
          -1L, LinkStatus.New, PublicRoad, LinkGeomSource.SuravageLinkInterface, 124.711, id, 8L, false, Some(linkId), 85088L),
        ProjectLink(4L, 5, 205, Track.Combined, EndOfRoad, origStartM + 52L, origEndM, Some(DateTime.now()), None, user,
          4L, linkId, 50.056, endM, SideCode.TowardsDigitizing, (None, Some(CalibrationPoint(linkId, endM, origEndM))), false,
          Seq(Point(1024.0, 51.984), Point(1024.0, 102.04)),
          -1L, LinkStatus.Terminated, PublicRoad, LinkGeomSource.NormalLinkInterface, endM - 50.056, id, 8L, false, Some(suravageLinkId), 85088L))
      projectService.updateTerminationForHistory(Set(), unchangedAndNew)
      val suravageAddresses = RoadAddressDAO.fetchByLinkId(Set(suravageLinkId), true, true)
      // Remove the current road address from list because it is not terminated by this procedure
      val oldLinkAddresses = RoadAddressDAO.fetchByLinkId(Set(linkId), true, true, true, true, Set(id))
      suravageAddresses.foreach { a =>
        a.terminated should be(NoTermination)
        a.endDate.nonEmpty || a.endAddrMValue == origStartM + 177L should be(true)
        a.linkGeomSource should be(SuravageLinkInterface)
      }
      oldLinkAddresses.foreach { a =>
        a.terminated should be(Subsequent)
        a.endDate.nonEmpty should be(true)
        a.linkGeomSource should be(NormalLinkInterface)
      }
    }
  }
  test("two projects with same road part") {
    runWithRollback {
      val error = intercept[RuntimeException] {
        val rap1 = RoadAddressProject(0L, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("1901-01-01"),
          "TestUser", DateTime.parse("1901-01-01"), DateTime.now(), "Some additional info",
          Seq(), None)
        val rap2 = RoadAddressProject(0L, ProjectState.apply(1), "TestProject2", "TestUser", DateTime.parse("1901-01-01"),
          "TestUser", DateTime.parse("1901-01-01"), DateTime.now(), "Some additional info",
          Seq(), None)
        val addr1 = List(ReservedRoadPart(Sequences.nextViitePrimaryKeySeqValue, 5, 207, Some(0L), Some(Continuous), Some(8L), None, None, None, None, true))
        val project1 = projectService.createRoadLinkProject(rap1)
        mockForProject(project1.id, RoadAddressDAO.fetchByRoadPart(5, 207).map(toProjectLink(project1)))
        projectService.saveProject(project1.copy(reservedParts = addr1))
        val project2 = projectService.createRoadLinkProject(rap2)
        mockForProject(project2.id, RoadAddressDAO.fetchByRoadPart(5, 207).map(toProjectLink(project2)))
        projectService.saveProject(project2.copy(reservedParts = addr1))
      }
      error.getMessage should be("Tie 5 osa 207 ei ole vapaana projektin alkupäivämääränä. Tieosoite on jo varattuna projektissa: TestProject.")

    }
  }

  test("create project link for road part that is not reserved") {
    runWithRollback {
      val rap = RoadAddressProject(0L, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("1901-01-01"),
        "TestUser", DateTime.parse("1901-01-01"), DateTime.now(), "Some additional info",
        Seq(), None)
      val newLink = Seq(ProjectLink(-1000L, 5L, 203L, Track.apply(99), Discontinuity.Continuous, 0L, 0L, None, None,
        None, 0L, 12345L, 0.0, 43.1, SideCode.Unknown, (None, None), false,
        Seq(Point(468.5, 0.5), Point(512.0, 0.0)), 0L, LinkStatus.Unknown, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, 43.1, 0L, 0, false,
        None, 86400L))
      val project = projectService.createRoadLinkProject(rap)
      when(mockRoadLinkService.getRoadLinksByLinkIdsFromVVH(any[Set[Long]], any[Boolean], any[Boolean])).thenReturn(newLink.map(toRoadLink))
      val response = projectService.createProjectLinks(Seq(12345L), project.id, 5, 203, Track.Combined, Discontinuity.Continuous, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, 8L, "test", "road name")
      response.get("success").get.asInstanceOf[Boolean] should be(false)
      response.get("errorMessage").get.asInstanceOf[String] should be("TIE 5 OSA 203 on jo olemassa projektin alkupäivänä 01.01.1901, tarkista tiedot")
    }
  }

  test("renumber a reserved road part to a road part reserved by other project") {
    runWithRollback {
      val rap1 = RoadAddressProject(0L, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("1901-01-01"),
        "TestUser", DateTime.parse("1901-01-01"), DateTime.now(), "Some additional info",
        Seq(), None)
      val rap2 = RoadAddressProject(0L, ProjectState.apply(1), "TestProject2", "TestUser", DateTime.parse("1901-01-01"),
        "TestUser", DateTime.parse("1901-01-01"), DateTime.now(), "Some additional info",
        Seq(), None)
      val addr1 = List(ReservedRoadPart(Sequences.nextViitePrimaryKeySeqValue, 5, 207, Some(0L), Some(Continuous), Some(8L), None, None, None, None, true))
      val addr2 = List(ReservedRoadPart(Sequences.nextViitePrimaryKeySeqValue, 5, 203, Some(5L), Some(Discontinuity.apply("jatkuva")), Some(8L), None, None, None, None, true))
      val project1 = projectService.createRoadLinkProject(rap1)
      mockForProject(project1.id, RoadAddressDAO.fetchByRoadPart(5, 207).map(toProjectLink(project1)))
      projectService.saveProject(project1.copy(reservedParts = addr1))

      val project2 = projectService.createRoadLinkProject(rap2)
      mockForProject(project2.id, RoadAddressDAO.fetchByRoadPart(5, 203).map(toProjectLink(project1)))
      projectService.saveProject(project2.copy(reservedParts = addr2))

      val response = projectService.updateProjectLinks(project1.id, Set(), ProjectDAO.getProjectLinks(project1.id).map(_.linkId), LinkStatus.Numbering, "TestUser", 5, 203, 0, None, RoadType.PublicRoad.value, Discontinuity.Continuous.value, Some(8))
      response.get should be("TIE 5 OSA 203 on jo olemassa projektin alkupäivänä 01.01.1901, tarkista tiedot")
    }
  }

  test("renumber a project link to a road part not reserved with end date null") {
    runWithRollback {
      val rap1 = RoadAddressProject(0L, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("1901-01-01"),
        "TestUser", DateTime.parse("1901-01-01"), DateTime.now(), "Some additional info",
        Seq(), None)
      val addr1 = List(ReservedRoadPart(Sequences.nextViitePrimaryKeySeqValue, 5, 207, Some(0L), Some(Continuous), Some(8L), None, None, None, None, true))
      val project1 = projectService.createRoadLinkProject(rap1)
      mockForProject(project1.id, RoadAddressDAO.fetchByRoadPart(5, 207).map(toProjectLink(project1)))
      projectService.saveProject(project1.copy(reservedParts = addr1))
      val response = projectService.updateProjectLinks(project1.id, Set(), ProjectDAO.getProjectLinks(project1.id).map(_.linkId), LinkStatus.Numbering, "TestUser", 5, 203, 0, None, RoadType.PublicRoad.value, Discontinuity.Continuous.value, Some(8))
      response.get should be("TIE 5 OSA 203 on jo olemassa projektin alkupäivänä 01.01.1901, tarkista tiedot")
    }
  }

  test("renumber all project links and change the last link discontinuity") {
    runWithRollback {
      val rap1 = RoadAddressProject(0L, ProjectState.apply(1), "TestProject", "TestUser", DateTime.now(),
        "TestUser", DateTime.now().plusDays(1), DateTime.now(), "Some additional info",
        Seq(), None)
      val addr1 = List(ReservedRoadPart(Sequences.nextViitePrimaryKeySeqValue, 5, 207, Some(0L), Some(Continuous), Some(8L), None, None, None, None, true))
      val project1 = projectService.createRoadLinkProject(rap1)
      mockForProject(project1.id, RoadAddressDAO.fetchByRoadPart(5, 207).map(toProjectLink(project1)))
      projectService.saveProject(project1.copy(reservedParts = addr1))
      projectService.updateProjectLinks(project1.id, Set(), ProjectDAO.getProjectLinks(project1.id).map(_.linkId), LinkStatus.Numbering, "TestUser", 6, 207, 0, None, RoadType.PublicRoad.value, Discontinuity.EndOfRoad.value, Some(8))

      //Descending order by end address
      val projectLinks = ProjectDAO.getProjectLinks(project1.id).sortBy(-_.endAddrMValue)
      projectLinks.tail.forall(_.discontinuity == Discontinuity.Continuous) should be(true)
      projectLinks.head.discontinuity should be(Discontinuity.EndOfRoad)
    }
  }

  test("Reserving new part with same linkId for existing part in same project (with status New too), should override and remove old part") {
    runWithRollback {
      val rap = RoadAddressProject(0L, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("1901-01-01"),
        "TestUser", DateTime.parse("1901-01-01"), DateTime.now(), "Some additional info",
        Seq(), None)
      val newLink = Seq(ProjectLink(-1000L, 9999L, 1L, Track.apply(0), Discontinuity.Continuous, 0L, 0L, None, None,
        None, 0L, 12345L, 0.0, 43.1, SideCode.Unknown, (None, None), false,
        Seq(Point(468.5, 0.5), Point(512.0, 0.0)), 0L, LinkStatus.Unknown, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, 43.1, 0L, 0, false,
        None, 86400L))
      val project = projectService.createRoadLinkProject(rap)
      when(mockRoadLinkService.getRoadLinksByLinkIdsFromVVH(any[Set[Long]], any[Boolean], any[Boolean])).thenReturn(newLink.map(toRoadLink))
      val createdLink = projectService.createProjectLinks(Seq(12345L), project.id, 9999, 1, Track.Combined, Discontinuity.Continuous, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, 8L, "test", "road name")
      createdLink.get("success").get.asInstanceOf[Boolean] should be(true)
      val updatedLink = ProjectDAO.getProjectLinksByLinkIdAndProjectId(12345L, project.id)

      projectService.updateProjectLinks(project.id, Set(updatedLink.head.id), Seq(), LinkStatus.New, "TestUserTwo", 9999, 2, 1, Some(30), 5L, 2) should be(None)
      val reservedParts = ProjectDAO.fetchReservedRoadParts(project.id)
      reservedParts.size should be(1)
      reservedParts.head.roadPartNumber should be(2)
      reservedParts.head.newDiscontinuity.get should be(Discontinuity.apply(2))

      val link = ProjectDAO.getProjectLinksByLinkIdAndProjectId(12345L, project.id).head
      link.status should be(LinkStatus.New)
      link.discontinuity should be(Discontinuity.apply(2))
      link.track should be(Track.apply(1))
      link.roadType should be(RoadType.apply(5))
    }
  }

  test("revert all new road should remove road name") {
    runWithRollback {
      val testRoad: (Long, Long, String) = {
        (99999L, 1L, "Test name")
      }

      val (project, links) = util.setUpProjectWithLinks(LinkStatus.New, Seq(0L, 10L, 20L), roads = Seq(testRoad), discontinuity = Discontinuity.Continuous)
      val roadLinks = links.map(toRoadLink)
      when(mockRoadLinkService.getCurrentAndComplementaryAndSuravageRoadLinksFromVVH(any[Set[Long]], any[Boolean], any[Boolean])).thenReturn(roadLinks)
      ProjectLinkNameDAO.get(99999L, project.id).get.roadName should be("Test name")
      val linksToRevert = links.map(l => {
        LinkToRevert(l.id, l.linkId, l.status.value, l.geometry)
      })
      projectService.revertLinks(project.id, 99999L, 1L, linksToRevert, "Test User")
      ProjectLinkNameDAO.get(99999L, project.id) should be(None)
    }
  }

  test("unreserved road with new road name should remove road name") {
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

  test("delete the project should remove project road name if no other project uses same road") {
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

  test("if road exists in another project the new road name should not be removed") {
    runWithRollback {
      val testRoads: Seq[(Long, Long, String)] = Seq((99999L, 1L, "Test name"), (99999L, 2L, "Test name"))
      val (project, links) = util.setUpProjectWithLinks(LinkStatus.Transfer, Seq(0L, 10L, 20L), roads = testRoads, discontinuity = Discontinuity.Continuous)
      ProjectLinkNameDAO.get(99999L, project.id).get.roadName should be("Test name")
      val linksToRevert = links.map(l => {
        LinkToRevert(l.id, l.linkId, l.status.value, l.geometry)
      })
      projectService.revertLinks(project.id, 99999L, 1L, linksToRevert, "Test User")
      ProjectDAO.getProjectLinks(project.id).count(_.roadPartNumber == 2L) should be(2)
      ProjectLinkNameDAO.get(99999L, project.id).get.roadName should be("Test name")
    }
  }

  test("if road name exists the revert should put the original name in the project link name if no other exists in project") {
    runWithRollback {
      val testRoad: (Long, Long, String) = {
        (99999L, 1L, "new name")
      }
      val (project, links) = util.setUpProjectWithLinks(LinkStatus.Transfer, Seq(0L, 10L, 20L), roads = Seq(testRoad), discontinuity = Discontinuity.Continuous)
      val roadLinks = links.map(toRoadLink)
      when(mockRoadLinkService.getCurrentAndComplementaryAndSuravageRoadLinksFromVVH(any[Set[Long]], any[Boolean], any[Boolean])).thenReturn(roadLinks)
      sqlu"""INSERT INTO ROAD_NAMES VALUES (ROAD_NAME_SEQ.NEXTVAL, 99999, 'test name', sysdate, null, sysdate, null, 'test user', sysdate)""".execute
      ProjectLinkNameDAO.get(99999L, project.id).get.roadName should be("new name")
      val linksToRevert = links.map(l => {
        LinkToRevert(l.id, l.linkId, l.status.value, l.geometry)
      })
      projectService.revertLinks(project.id, 99999L, 1L, linksToRevert, "Test User")
      ProjectLinkNameDAO.get(99999L, project.id).get.roadName should be("test name")
    }
  }

  test("Road address geometry after reverting should be the same as VVH") {
    val projectId = 0L
    val user = "TestUser"
    val (roadNumber, roadPartNumber) = (26020L, 12L)
    val (newRoadNumber, newRoadPart) = (9999L, 1L)
    val smallerRoadGeom = Seq(Point(0.0, 0.0), Point(0.0, 5.0))
    val roadGeom = Seq(Point(0.0, 0.0), Point(0.0, 10.0))
    runWithRollback {

      val roadAddresses = RoadAddressDAO.fetchByRoadPart(roadNumber, roadPartNumber)


      val rap = RoadAddressProject(projectId, ProjectState.apply(1), "TestProject", user, DateTime.parse("1901-01-01"),
        "TestUser", DateTime.parse("1901-01-01"), DateTime.now(), "Some additional info",
        Seq(), None)
      ProjectDAO.createRoadAddressProject(rap)
      val projectLinksFromRoadAddresses = roadAddresses.map(ra => toProjectLink(rap)(ra))

      ProjectDAO.reserveRoadPart(projectId, roadNumber, roadPartNumber, "Test")
      ProjectDAO.create(projectLinksFromRoadAddresses)

      val numberingLink = Seq(ProjectLink(-1000L, newRoadNumber, newRoadPart, Track.apply(0), Discontinuity.Continuous, 0L, 0L, None, None,
        Option(user), 0L, projectLinksFromRoadAddresses.head.linkId, 0.0, 10.0, SideCode.Unknown, (None, None), false,
        smallerRoadGeom, 0L, LinkStatus.Numbering, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, 10.0, roadAddresses.head.id, 0, false,
        None, 86400L))
      ProjectDAO.reserveRoadPart(projectId, newRoadNumber, newRoadPart, "Test")
      ProjectDAO.create(numberingLink)
      val numberingLinks = ProjectDAO.getProjectLinks(projectId, Option(LinkStatus.Numbering))
      numberingLinks.head.geometry should be equals (smallerRoadGeom)

      val projectLinks = ProjectDAO.getProjectLinks(projectId)
      val linksToRevert = projectLinks.filter(_.status != LinkStatus.NotHandled).map(pl => {
        LinkToRevert(pl.id, pl.linkId, pl.status.value, pl.geometry)
      })
      val roadLinks = projectLinks.map(toRoadLink).head.copy(geometry = roadGeom)
      when(mockRoadLinkService.getCurrentAndComplementaryAndSuravageRoadLinksFromVVH(any[Set[Long]], any[Boolean], any[Boolean])).thenReturn(Seq(roadLinks))


      projectService.revertLinks(projectId, newRoadNumber, newRoadPart, linksToRevert, user)
      val geomAfterRevert = GeometryUtils.truncateGeometry3D(roadGeom, projectLinksFromRoadAddresses.head.startMValue, projectLinksFromRoadAddresses.head.endMValue)
      val linksAfterRevert = ProjectDAO.getProjectLinks(projectId)
      linksAfterRevert.map(_.geometry).contains(geomAfterRevert) should be(true)


    }
  }

  test("New user given addressMValues, even on Left/Right tracks, should keep continuous and incremented address values (calibration ones included) for all links") {
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

      val rap = RoadAddressProject(0L, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("1901-01-01"),
        "TestUser", DateTime.parse("1901-01-01"), DateTime.now(), "Some additional info",
        Seq(), None)

      val pl1 = ProjectLink(-1000L, 9999L, 1L, Track.apply(0), Discontinuity.Continuous, 0L, 0L, None, None,
        None, 0L, 12345L, 0.0, 0.0, SideCode.Unknown, (None, None), false,
        Seq(Point(10.0, 10.0), Point(20.0, 10.0)), 0L, LinkStatus.New, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(Seq(Point(10.0, 10.0), Point(20.0, 10.0))), 0L, 0, false,
        None, 86400L)
      val pl2 = ProjectLink(-1000L, 9999L, 1L, Track.apply(2), Discontinuity.Continuous, 0L, 0L, None, None,
        None, 0L, 12346L, 0.0, 0.0, SideCode.Unknown, (None, None), false,
        Seq(Point(20.0, 10.0), Point(30.0, 15.0)), 0L, LinkStatus.New, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(Seq(Point(20.0, 10.0), Point(30.0, 15.0))), 0L, 0, false,
        None, 86400L)
      val pl3 = ProjectLink(-1000L, 9999L, 1L, Track.apply(2), Discontinuity.Continuous, 0L, 0L, None, None,
        None, 0L, 12347L, 0.0, 0.0, SideCode.Unknown, (None, None), false,
        Seq(Point(30.0, 15.0), Point(45.0, 10.0)), 0L, LinkStatus.New, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(Seq(Point(30.0, 15.0), Point(45.0, 10.0))), 0L, 0, false,
        None, 86400L)
      val pl4 = ProjectLink(-1000L, 9999L, 1L, Track.apply(1), Discontinuity.Continuous, 0L, 0L, None, None,
        None, 0L, 12348L, 0.0, 0.0, SideCode.Unknown, (None, None), false,
        Seq(Point(20.0, 10.0), Point(25.0, 5.0), Point(45.0, 10.0)), 0L, LinkStatus.New, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(Seq(Point(20.0, 10.0), Point(25.0, 5.0), Point(45.0, 10.0))), 0L, 0, false,
        None, 86400L)
      val pl5 = ProjectLink(-1000L, 9999L, 1L, Track.apply(0), Discontinuity.Continuous, 0L, 0L, None, None,
        None, 0L, 12349L, 0.0, 0.0, SideCode.Unknown, (None, None), false,
        Seq(Point(45.0, 10.0), Point(60.0, 10.0)), 0L, LinkStatus.New, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(Seq(Point(45.0, 10.0), Point(60.0, 10.0))), 0L, 0, false,
        None, 86400L)

      val project = projectService.createRoadLinkProject(rap)
      when(mockRoadLinkService.getRoadLinksByLinkIdsFromVVH(any[Set[Long]], any[Boolean], any[Boolean])).thenReturn(Seq(pl1).map(toRoadLink))
      projectService.createProjectLinks(Seq(12345L), project.id, 9999, 1, Track.Combined, Discontinuity.Continuous, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, 8L, "test", "road name")
      when(mockRoadLinkService.getRoadLinksByLinkIdsFromVVH(any[Set[Long]], any[Boolean], any[Boolean])).thenReturn(Seq(pl4).map(toRoadLink))
      projectService.createProjectLinks(Seq(12348L), project.id, 9999, 1, Track.RightSide, Discontinuity.Continuous, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, 8L, "test", "road name")
      when(mockRoadLinkService.getRoadLinksByLinkIdsFromVVH(any[Set[Long]], any[Boolean], any[Boolean])).thenReturn(Seq(pl2, pl3).map(toRoadLink))
      projectService.createProjectLinks(Seq(12346L, 12347L), project.id, 9999, 1, Track.LeftSide, Discontinuity.Continuous, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, 8L, "test", "road name")
      when(mockRoadLinkService.getRoadLinksByLinkIdsFromVVH(any[Set[Long]], any[Boolean], any[Boolean])).thenReturn(Seq(pl5).map(toRoadLink))
      projectService.createProjectLinks(Seq(12349L), project.id, 9999, 1, Track.Combined, Discontinuity.Continuous, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, 8L, "test", "road name")
      val links = ProjectDAO.getProjectLinks(project.id).sortBy(_.startAddrMValue)

      links.filterNot(_.track == Track.RightSide).sortBy(_.endAddrMValue).scanLeft(0.0) { case (m, pl) =>
        m should be(pl.startAddrMValue)
        pl.endAddrMValue
      }
      links.filterNot(_.track == Track.LeftSide).sortBy(_.endAddrMValue).scanLeft(0.0) { case (m, pl) =>
        m should be(pl.startAddrMValue)
        pl.endAddrMValue
      }

      links.sortBy(_.endAddrMValue).scanLeft(0.0) { case (m, pl) =>
        if (pl.calibrationPoints._1.headOption.nonEmpty) {
          pl.calibrationPoints._1.get.addressMValue should be(pl.startAddrMValue)
          pl.calibrationPoints._1.get.segmentMValue should be(pl.endMValue - pl.startMValue)
        }
        if (pl.calibrationPoints._2.headOption.nonEmpty) {
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
      val linksAfterGivenAddrMValue = ProjectDAO.getProjectLinks(project.id)

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
        if (pl.calibrationPoints._1.headOption.nonEmpty) {
          pl.calibrationPoints._1.get.addressMValue should be(pl.startAddrMValue)
          pl.calibrationPoints._1.get.segmentMValue should be(pl.endMValue - pl.startMValue)
        }
        if (pl.calibrationPoints._2.headOption.nonEmpty) {
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
      val linksAfterSecondGivenAddrMValue = ProjectDAO.getProjectLinks(project.id)

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
        if (pl.calibrationPoints._1.headOption.nonEmpty) {
          pl.calibrationPoints._1.get.addressMValue should be(pl.startAddrMValue)
          pl.calibrationPoints._1.get.segmentMValue should be(pl.endMValue - pl.startMValue)
        }
        if (pl.calibrationPoints._2.headOption.nonEmpty) {
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

  test("Re-Reversing direction should restore and ignore previous user given addressMValues") {
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

      val rap = RoadAddressProject(0L, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("1901-01-01"),
        "TestUser", DateTime.parse("1901-01-01"), DateTime.now(), "Some additional info",
        Seq(), None)

      val pl1 = ProjectLink(-1000L, 9999L, 1L, Track.apply(0), Discontinuity.Continuous, 0L, 0L, None, None,
        None, 0L, 12345L, 0.0, 0.0, SideCode.Unknown, (None, None), false,
        Seq(Point(10.0, 10.0), Point(20.0, 10.0)), 0L, LinkStatus.New, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(Seq(Point(10.0, 10.0), Point(20.0, 10.0))), 0L, 0, false,
        None, 86400L)
      val pl2 = ProjectLink(-1000L, 9999L, 1L, Track.apply(2), Discontinuity.Continuous, 0L, 0L, None, None,
        None, 0L, 12346L, 0.0, 0.0, SideCode.Unknown, (None, None), false,
        Seq(Point(20.0, 10.0), Point(30.0, 15.0)), 0L, LinkStatus.New, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(Seq(Point(20.0, 10.0), Point(30.0, 15.0))), 0L, 0, false,
        None, 86400L)
      val pl3 = ProjectLink(-1000L, 9999L, 1L, Track.apply(2), Discontinuity.Continuous, 0L, 0L, None, None,
        None, 0L, 12347L, 0.0, 0.0, SideCode.Unknown, (None, None), false,
        Seq(Point(30.0, 15.0), Point(45.0, 10.0)), 0L, LinkStatus.New, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(Seq(Point(30.0, 15.0), Point(45.0, 10.0))), 0L, 0, false,
        None, 86400L)
      val pl4 = ProjectLink(-1000L, 9999L, 1L, Track.apply(1), Discontinuity.Continuous, 0L, 0L, None, None,
        None, 0L, 12348L, 0.0, 0.0, SideCode.Unknown, (None, None), false,
        Seq(Point(20.0, 10.0), Point(25.0, 5.0), Point(45.0, 10.0)), 0L, LinkStatus.New, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(Seq(Point(20.0, 10.0), Point(25.0, 5.0), Point(45.0, 10.0))), 0L, 0, false,
        None, 86400L)
      val pl5 = ProjectLink(-1000L, 9999L, 1L, Track.apply(0), Discontinuity.Continuous, 0L, 0L, None, None,
        None, 0L, 12349L, 0.0, 0.0, SideCode.Unknown, (None, None), false,
        Seq(Point(45.0, 10.0), Point(60.0, 10.0)), 0L, LinkStatus.New, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(Seq(Point(45.0, 10.0), Point(60.0, 10.0))), 0L, 0, false,
        None, 86400L)

      val project = projectService.createRoadLinkProject(rap)
      when(mockRoadLinkService.getRoadLinksByLinkIdsFromVVH(any[Set[Long]], any[Boolean], any[Boolean])).thenReturn(Seq(pl1).map(toRoadLink))
      projectService.createProjectLinks(Seq(12345L), project.id, 9999, 1, Track.Combined, Discontinuity.Continuous, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, 8L, "test", "road name")
      when(mockRoadLinkService.getRoadLinksByLinkIdsFromVVH(any[Set[Long]], any[Boolean], any[Boolean])).thenReturn(Seq(pl4).map(toRoadLink))
      projectService.createProjectLinks(Seq(12348L), project.id, 9999, 1, Track.RightSide, Discontinuity.Continuous, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, 8L, "test", "road name")
      when(mockRoadLinkService.getRoadLinksByLinkIdsFromVVH(any[Set[Long]], any[Boolean], any[Boolean])).thenReturn(Seq(pl2, pl3).map(toRoadLink))
      projectService.createProjectLinks(Seq(12346L, 12347L), project.id, 9999, 1, Track.LeftSide, Discontinuity.Continuous, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, 8L, "test", "road name")
      when(mockRoadLinkService.getRoadLinksByLinkIdsFromVVH(any[Set[Long]], any[Boolean], any[Boolean])).thenReturn(Seq(pl5).map(toRoadLink))
      projectService.createProjectLinks(Seq(12349L), project.id, 9999, 1, Track.Combined, Discontinuity.Continuous, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, 8L, "test", "road name")
      val links = ProjectDAO.getProjectLinks(project.id).sortBy(_.startAddrMValue)

      val linkidToIncrement = pl1.linkId
      val idsToIncrement = links.filter(_.linkId == linkidToIncrement).head.id
      val valueToIncrement = 2.0
      val newEndAddressValue = Seq(links.filter(_.linkId == linkidToIncrement).head.endAddrMValue.toInt, valueToIncrement.toInt).sum
      projectService.updateProjectLinks(project.id, Set(idsToIncrement), Seq(linkidToIncrement), LinkStatus.New, "TestUserTwo", 9999, 1, 0, Some(newEndAddressValue), 1L, 5) should be(None)
      val linksAfterGivenAddrMValue = ProjectDAO.getProjectLinks(project.id)

      //only link and links after linkidToIncrement should be extended
      val extendedLink = links.filter(_.linkId == linkidToIncrement).head
      val linksBefore = links.filter(_.endAddrMValue >= extendedLink.endAddrMValue).sortBy(_.endAddrMValue)
      val linksAfter = linksAfterGivenAddrMValue.filter(_.endAddrMValue >= extendedLink.endAddrMValue).sortBy(_.endAddrMValue)
      linksBefore.zip(linksAfter).foreach { case (st, en) =>
        liesInBetween(en.endAddrMValue, (st.endAddrMValue + valueToIncrement - coeff, st.endAddrMValue + valueToIncrement + coeff))
      }

      projectService.changeDirection(project.id, 9999L, 1L, Seq(LinkToRevert(pl1.id, pl1.linkId, pl1.status.value, pl1.geometry)), "TestUserTwo")
      projectService.changeDirection(project.id, 9999L, 1L, Seq(LinkToRevert(pl1.id, pl1.linkId, pl1.status.value, pl1.geometry)), "TestUserTwo")
      val linksAfterReverse = ProjectDAO.getProjectLinks(project.id).sortBy(_.startAddrMValue)

      links.sortBy(_.endAddrMValue).zip(linksAfterReverse.sortBy(_.endAddrMValue)).foreach { case (st, en) =>
        (st.startAddrMValue, st.endAddrMValue) should be(en.startAddrMValue, en.endAddrMValue)
        (st.startMValue, st.endMValue) should be(en.startMValue, en.endMValue)
      }
    }
  }

  test("Change direction should not alter discontinuity of road addresses that are not the same road number and road part number") {
    runWithRollback {

      val rap = RoadAddressProject(0L, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("1901-01-01"),
        "TestUser", DateTime.parse("1901-01-01"), DateTime.now(), "Some additional info",
        Seq(), None)

      val pl1 = ProjectLink(-1000L, 9999L, 1L, Track.apply(0), Discontinuity.Continuous, 0L, 0L, None, None,
        None, 0L, 12345L, 0.0, 0.0, SideCode.Unknown, (None, None), false,
        Seq(Point(10.0, 10.0), Point(20.0, 10.0)), 0L, LinkStatus.New, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(Seq(Point(10.0, 10.0), Point(20.0, 10.0))), 0L, 0, false,
        None, 86400L)
      val pl2 = ProjectLink(-1000L, 9999L, 1L, Track.apply(2), Discontinuity.Continuous, 0L, 0L, None, None,
        None, 0L, 12346L, 0.0, 0.0, SideCode.Unknown, (None, None), false,
        Seq(Point(20.0, 10.0), Point(30.0, 15.0)), 0L, LinkStatus.New, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(Seq(Point(20.0, 10.0), Point(30.0, 15.0))), 0L, 0, false,
        None, 86400L)
      val pl3 = ProjectLink(-1000L, 9999L, 1L, Track.apply(2), Discontinuity.Continuous, 0L, 0L, None, None,
        None, 0L, 12347L, 0.0, 0.0, SideCode.Unknown, (None, None), false,
        Seq(Point(30.0, 15.0), Point(45.0, 10.0)), 0L, LinkStatus.New, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(Seq(Point(30.0, 15.0), Point(45.0, 10.0))), 0L, 0, false,
        None, 86400L)
      val pl4 = ProjectLink(-1000L, 9999L, 1L, Track.apply(1), Discontinuity.Continuous, 0L, 0L, None, None,
        None, 0L, 12348L, 0.0, 0.0, SideCode.Unknown, (None, None), false,
        Seq(Point(20.0, 10.0), Point(25.0, 5.0), Point(45.0, 10.0)), 0L, LinkStatus.New, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(Seq(Point(20.0, 10.0), Point(25.0, 5.0), Point(45.0, 10.0))), 0L, 0, false,
        None, 86400L)
      val pl5 = ProjectLink(-1000L, 9998L, 1L, Track.apply(0), Discontinuity.EndOfRoad, 0L, 0L, None, None,
        None, 0L, 12349L, 0.0, 0.0, SideCode.Unknown, (None, None), false,
        Seq(Point(45.0, 10.0), Point(60.0, 10.0)), 0L, LinkStatus.New, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(Seq(Point(45.0, 10.0), Point(60.0, 10.0))), 0L, 0, false,
        None, 86400L)

      val project = projectService.createRoadLinkProject(rap)
      when(mockRoadLinkService.getRoadLinksByLinkIdsFromVVH(any[Set[Long]], any[Boolean], any[Boolean])).thenReturn(Seq(pl1).map(toRoadLink))
      projectService.createProjectLinks(Seq(12345L), project.id, 9999, 1, Track.Combined, Discontinuity.Continuous, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, 8L, "test", "road name")
      when(mockRoadLinkService.getRoadLinksByLinkIdsFromVVH(any[Set[Long]], any[Boolean], any[Boolean])).thenReturn(Seq(pl4).map(toRoadLink))
      projectService.createProjectLinks(Seq(12348L), project.id, 9999, 1, Track.RightSide, Discontinuity.Continuous, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, 8L, "test", "road name")
      when(mockRoadLinkService.getRoadLinksByLinkIdsFromVVH(any[Set[Long]], any[Boolean], any[Boolean])).thenReturn(Seq(pl2, pl3).map(toRoadLink))
      projectService.createProjectLinks(Seq(12346L, 12347L), project.id, 9999, 1, Track.LeftSide, Discontinuity.Continuous, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, 8L, "test", "road name")
      when(mockRoadLinkService.getRoadLinksByLinkIdsFromVVH(any[Set[Long]], any[Boolean], any[Boolean])).thenReturn(Seq(pl5).map(toRoadLink))
      projectService.createProjectLinks(Seq(12349L), project.id, 9998, 1, Track.Combined, Discontinuity.EndOfRoad, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, 8L, "test", "road name")
      val linksBeforeChange = ProjectDAO.getProjectLinks(project.id).sortBy(_.startAddrMValue)
      val linkBC = linksBeforeChange.filter(_.roadNumber == 9998L)
      linkBC.size should be(1)
      linkBC.head.discontinuity.value should be(Discontinuity.EndOfRoad.value)
      projectService.changeDirection(project.id, 9999L, 1L, Seq(LinkToRevert(pl1.id, pl1.linkId, pl1.status.value, pl1.geometry)), "TestUserTwo")
      val linksAfterChange = ProjectDAO.getProjectLinks(project.id).sortBy(_.startAddrMValue)
      val linkAC = linksAfterChange.filter(_.roadNumber == 9998L)
      linkAC.size should be(1)
      linkAC.head.discontinuity.value should be(linkBC.head.discontinuity.value)
    }
  }

  test("Transfer last ajr 1 & 2 links from part 1 to part 2 and adjust endAddrMValues for last links from transfered part and transfer the rest of the part 2") {

    runWithRollback {
      /**
        * Test data
        */
      val rap = RoadAddressProject(0L, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("1901-01-01"),
        "TestUser", DateTime.parse("1990-01-01"), DateTime.now(), "Some additional info",
        Seq(), None)
      //part1
      //track1
      sqlu"""INSERT INTO LRM_POSITION VALUES(lrm_position_primary_key_seq.nextval, NULL, 2, 0, 9.0, NULL, 12345, 1510876800000, TIMESTAMP '2018-03-06 09:56:18.675242', 1)""".execute
      sqlu"""INSERT INTO ROAD_ADDRESS VALUES(viite_general_seq.nextval, 9999, 1, 1, 5, 0, 9, lrm_position_primary_key_seq.currval, TIMESTAMP '1980-08-01 00:00:00.000000', NULL, 'TR', TIMESTAMP '2015-12-30 00:00:00.000000', 2, '0', MDSYS.SDO_GEOMETRY(4002, 3067, NULL, MDSYS.SDO_ELEM_INFO_ARRAY(1, 2, 1), MDSYS.SDO_ORDINATE_ARRAY(5.0, 0.0, 0, 0, 5.0, 9.0, 0, 9)), NULL, 1, 1, 0, 9990)""".execute

      sqlu"""INSERT INTO LRM_POSITION VALUES(lrm_position_primary_key_seq.nextval, NULL, 2, 0, 12.0, NULL, 12346, 1510876800000, TIMESTAMP '2018-03-06 09:56:18.675242', 1)""".execute
      sqlu"""INSERT INTO ROAD_ADDRESS VALUES(viite_general_seq.nextval, 9999, 1, 1, 5, 9, 21, lrm_position_primary_key_seq.currval, TIMESTAMP '1980-08-01 00:00:00.000000', NULL, 'TR', TIMESTAMP '2015-12-30 00:00:00.000000', 2, '0', MDSYS.SDO_GEOMETRY(4002, 3067, NULL, MDSYS.SDO_ELEM_INFO_ARRAY(1, 2, 1), MDSYS.SDO_ORDINATE_ARRAY(5.0, 9.0, 0, 9, 5.0, 21.0, 0, 21)), NULL, 1, 1, 0, 9991)""".execute

      sqlu"""INSERT INTO LRM_POSITION VALUES(lrm_position_primary_key_seq.nextval, NULL, 2, 0, 5.0, NULL, 12347, 1510876800000, TIMESTAMP '2018-03-06 09:56:18.675242', 1)""".execute
      sqlu"""INSERT INTO ROAD_ADDRESS VALUES(viite_general_seq.nextval, 9999, 1, 1, 5, 21, 26, lrm_position_primary_key_seq.currval, TIMESTAMP '1980-08-01 00:00:00.000000', NULL, 'TR', TIMESTAMP '2015-12-30 00:00:00.000000', 2, '0', MDSYS.SDO_GEOMETRY(4002, 3067, NULL, MDSYS.SDO_ELEM_INFO_ARRAY(1, 2, 1), MDSYS.SDO_ORDINATE_ARRAY(5.0, 21.0, 0, 21, 5.0, 26.0, 0, 26)), NULL, 1, 1, 0, 9992)""".execute

      //track2
      sqlu"""INSERT INTO LRM_POSITION VALUES(lrm_position_primary_key_seq.nextval, NULL, 2, 0, 10.0, NULL, 12348, 1510876800000, TIMESTAMP '2018-03-06 09:56:18.675242', 1)""".execute
      sqlu"""INSERT INTO ROAD_ADDRESS VALUES(viite_general_seq.nextval, 9999, 1, 2, 5, 0, 10, lrm_position_primary_key_seq.currval, TIMESTAMP '1980-08-01 00:00:00.000000', NULL, 'TR', TIMESTAMP '2015-12-30 00:00:00.000000', 2, '0', MDSYS.SDO_GEOMETRY(4002, 3067, NULL, MDSYS.SDO_ELEM_INFO_ARRAY(1, 2, 1), MDSYS.SDO_ORDINATE_ARRAY(0.0, 0.0, 0, 0, 0.0, 10.0, 0, 10)), NULL, 1, 1, 0, 9993)""".execute

      sqlu"""INSERT INTO LRM_POSITION VALUES(lrm_position_primary_key_seq.nextval, NULL, 2, 0, 8.0, NULL, 12349, 1510876800000, TIMESTAMP '2018-03-06 09:56:18.675242', 1)""".execute
      sqlu"""INSERT INTO ROAD_ADDRESS VALUES(viite_general_seq.nextval, 9999, 1, 2, 5, 10, 18, lrm_position_primary_key_seq.currval, TIMESTAMP '1980-08-01 00:00:00.000000', NULL, 'TR', TIMESTAMP '2015-12-30 00:00:00.000000', 2, '0', MDSYS.SDO_GEOMETRY(4002, 3067, NULL, MDSYS.SDO_ELEM_INFO_ARRAY(1, 2, 1), MDSYS.SDO_ORDINATE_ARRAY(0.0, 10.0, 0, 10, 0.0, 18.0, 0, 18)), NULL, 1, 1, 0, 9994)""".execute

      sqlu"""INSERT INTO LRM_POSITION VALUES(lrm_position_primary_key_seq.nextval, NULL, 2, 0, 5.0, NULL, 12350, 1510876800000, TIMESTAMP '2018-03-06 09:56:18.675242', 1)""".execute
      sqlu"""INSERT INTO ROAD_ADDRESS VALUES(viite_general_seq.nextval, 9999, 1, 2, 5, 18, 23, lrm_position_primary_key_seq.currval, TIMESTAMP '1980-08-01 00:00:00.000000', NULL, 'TR', TIMESTAMP '2015-12-30 00:00:00.000000', 2, '0', MDSYS.SDO_GEOMETRY(4002, 3067, NULL, MDSYS.SDO_ELEM_INFO_ARRAY(1, 2, 1), MDSYS.SDO_ORDINATE_ARRAY(0.0, 18.0, 0, 18, 0.0, 23.0, 0, 23)), NULL, 1, 1, 0, 9995)""".execute

      sqlu"""INSERT INTO LRM_POSITION VALUES(lrm_position_primary_key_seq.nextval, NULL, 2, 0, 3.0, NULL, 12351, 1510876800000, TIMESTAMP '2018-03-06 09:56:18.675242', 1)""".execute
      sqlu"""INSERT INTO ROAD_ADDRESS VALUES(viite_general_seq.nextval, 9999, 1, 2, 5, 23, 26, lrm_position_primary_key_seq.currval, TIMESTAMP '1980-08-01 00:00:00.000000', NULL, 'TR', TIMESTAMP '2015-12-30 00:00:00.000000', 2, '0', MDSYS.SDO_GEOMETRY(4002, 3067, NULL, MDSYS.SDO_ELEM_INFO_ARRAY(1, 2, 1), MDSYS.SDO_ORDINATE_ARRAY(0.0, 23.0, 0, 23, 0.0, 26.0, 0, 26)), NULL, 1, 1, 0, 9996)""".execute

      //part2
      //track1
      sqlu"""INSERT INTO LRM_POSITION VALUES(lrm_position_primary_key_seq.nextval, NULL, 2, 0, 2.0, NULL, 12352, 1510876800000, TIMESTAMP '2018-03-06 09:56:18.675242', 1)""".execute
      sqlu"""INSERT INTO ROAD_ADDRESS VALUES(viite_general_seq.nextval, 9999, 2, 1, 5, 0, 2, lrm_position_primary_key_seq.currval, TIMESTAMP '1980-08-01 00:00:00.000000', NULL, 'TR', TIMESTAMP '2015-12-30 00:00:00.000000', 2, '0', MDSYS.SDO_GEOMETRY(4002, 3067, NULL, MDSYS.SDO_ELEM_INFO_ARRAY(1, 2, 1), MDSYS.SDO_ORDINATE_ARRAY(5.0, 26.0, 0, 0, 5.0, 28.0, 0, 2)), NULL, 1, 1, 0, 9997)""".execute

      sqlu"""INSERT INTO LRM_POSITION VALUES(lrm_position_primary_key_seq.nextval, NULL, 2, 0, 7.0, NULL, 12353, 1510876800000, TIMESTAMP '2018-03-06 09:56:18.675242', 1)""".execute
      sqlu"""INSERT INTO ROAD_ADDRESS VALUES(viite_general_seq.nextval, 9999, 2, 1, 5, 2, 9, lrm_position_primary_key_seq.currval, TIMESTAMP '1980-08-01 00:00:00.000000', NULL, 'TR', TIMESTAMP '2015-12-30 00:00:00.000000', 2, '0', MDSYS.SDO_GEOMETRY(4002, 3067, NULL, MDSYS.SDO_ELEM_INFO_ARRAY(1, 2, 1), MDSYS.SDO_ORDINATE_ARRAY(5.0, 28.0, 0, 2, 5.0, 35.0, 0, 7)), NULL, 1, 1, 0, 9998)""".execute

      //track2
      sqlu"""INSERT INTO LRM_POSITION VALUES(lrm_position_primary_key_seq.nextval, NULL, 2, 0, 3.0, NULL, 12354, 1510876800000, TIMESTAMP '2018-03-06 09:56:18.675242', 1)""".execute
      sqlu"""INSERT INTO ROAD_ADDRESS VALUES(viite_general_seq.nextval, 9999, 2, 2, 5, 0, 3, lrm_position_primary_key_seq.currval, TIMESTAMP '1980-08-01 00:00:00.000000', NULL, 'TR', TIMESTAMP '2015-12-30 00:00:00.000000', 2, '0', MDSYS.SDO_GEOMETRY(4002, 3067, NULL, MDSYS.SDO_ELEM_INFO_ARRAY(1, 2, 1), MDSYS.SDO_ORDINATE_ARRAY(0.0, 26.0, 0, 0, 0.0, 29.0, 0, 3)), NULL, 1, 1, 0, 9999)""".execute

      sqlu"""INSERT INTO LRM_POSITION VALUES(lrm_position_primary_key_seq.nextval, NULL, 2, 0, 8.0, NULL, 12355, 1510876800000, TIMESTAMP '2018-03-06 09:56:18.675242', 1)""".execute
      sqlu"""INSERT INTO ROAD_ADDRESS VALUES(viite_general_seq.nextval, 9999, 2, 2, 5, 3, 11, lrm_position_primary_key_seq.currval, TIMESTAMP '1980-08-01 00:00:00.000000', NULL, 'TR', TIMESTAMP '2015-12-30 00:00:00.000000', 2, '0', MDSYS.SDO_GEOMETRY(4002, 3067, NULL, MDSYS.SDO_ELEM_INFO_ARRAY(1, 2, 1), MDSYS.SDO_ORDINATE_ARRAY(0.0, 29.0, 0, 3, 0.0, 37.0, 0, 11)), NULL, 1, 1, 0, 10000)""".execute

      val project = projectService.createRoadLinkProject(rap)
      val id = project.id
      val part1 = RoadAddressDAO.fetchByRoadPart(9999, 1)
      val part2 = RoadAddressDAO.fetchByRoadPart(9999, 2)
      val toProjectLinks = (part1 ++ part2).map(toProjectLink(rap))
      val roadLinks = toProjectLinks.map(toRoadLink)
      when(mockRoadLinkService.getRoadLinksHistoryFromVVH(any[Set[Long]])).thenReturn(Seq())
      when(mockRoadLinkService.getRoadLinksByLinkIdsFromVVH(any[Set[Long]], any[Boolean], any[Boolean])).thenReturn(roadLinks)
      projectService.saveProject(project.copy(reservedParts = Seq(ReservedRoadPart(0L, 9999, 1, null, Some(Continuous), Some(1L), None, None, None, None, true), ReservedRoadPart(0L, 9999, 2, null, Some(Continuous), Some(1L), None, None, None, None, true))))

      val projectLinks = ProjectDAO.getProjectLinks(id)
      val part1track1 = Set(12345L, 12346L, 12347L)
      val part1track2 = Set(12348L, 12349L, 12350L, 12351L)
      val part1track1Links = projectLinks.filter(pl => part1track1.contains(pl.linkId)).map(_.id).toSet
      val part1Track2Links = projectLinks.filter(pl => part1track2.contains(pl.linkId)).map(_.id).toSet

      when(mockRoadLinkService.getRoadLinksByLinkIdsFromVVH(any[Set[Long]], any[Boolean], any[Boolean])).thenReturn(projectLinks.filter(pl => part1track1Links.contains(pl.linkId)).map(toRoadLink))
      ProjectDAO.updateProjectLinks(part1track1Links, LinkStatus.UnChanged, "test")
      when(mockRoadLinkService.getRoadLinksByLinkIdsFromVVH(any[Set[Long]], any[Boolean], any[Boolean])).thenReturn(projectLinks.filter(pl => part1Track2Links.contains(pl.linkId)).map(toRoadLink))
      ProjectDAO.updateProjectLinks(part1Track2Links, LinkStatus.UnChanged, "test")

      /**
        * Tranfering adjacents of part1 to part2
        */
      val part1AdjacentToPart2IdRightSide = Set(12347L)
      val part1AdjacentToPart2IdLeftSide = Set(12351L)
      val part1AdjacentToPart2LinkRightSide = projectLinks.filter(pl => part1AdjacentToPart2IdRightSide.contains(pl.linkId)).map(_.id).toSet
      val part1AdjacentToPart2LinkLeftSide = projectLinks.filter(pl => part1AdjacentToPart2IdLeftSide.contains(pl.linkId)).map(_.id).toSet

      when(mockRoadLinkService.getRoadLinksByLinkIdsFromVVH(any[Set[Long]], any[Boolean], any[Boolean])).thenReturn(projectLinks.filter(pl => part1AdjacentToPart2IdRightSide.contains(pl.linkId)).map(toRoadLink))
      projectService.updateProjectLinks(id, part1AdjacentToPart2LinkRightSide, Seq(), LinkStatus.Transfer, "test",
        9999, 2, 1, None, 1, 5, Some(1L), false, None)
      val projectLinks4 = ProjectDAO.getProjectLinks(id)
      when(mockRoadLinkService.getRoadLinksByLinkIdsFromVVH(any[Set[Long]], any[Boolean], any[Boolean])).thenReturn(projectLinks.filter(pl => part1Track2Links.contains(pl.linkId)).map(toRoadLink))
      projectService.updateProjectLinks(id, part1AdjacentToPart2LinkLeftSide, Seq(), LinkStatus.Transfer, "test",
        9999, 2, 2, None, 1, 5, Some(1L), false, None)

      val part2track1 = Set(12352L, 12353L)
      val part2track2 = Set(12354L, 12355L)
      val part2track1Links = projectLinks.filter(pl => part2track1.contains(pl.linkId)).map(_.id).toSet
      val part2Track2Links = projectLinks.filter(pl => part2track2.contains(pl.linkId)).map(_.id).toSet
      val projectLinks3 = ProjectDAO.getProjectLinks(id)
      when(mockRoadLinkService.getRoadLinksByLinkIdsFromVVH(any[Set[Long]], any[Boolean], any[Boolean])).thenReturn(projectLinks.filter(pl => part2track1Links.contains(pl.linkId)).map(toRoadLink))
      projectService.updateProjectLinks(id, part2track1Links, Seq(), LinkStatus.Transfer, "test",
        9999, 2, 1, None, 1, 5, Some(1L), false, None)
      when(mockRoadLinkService.getRoadLinksByLinkIdsFromVVH(any[Set[Long]], any[Boolean], any[Boolean])).thenReturn(projectLinks.filter(pl => part2Track2Links.contains(pl.linkId)).map(toRoadLink))
      projectService.updateProjectLinks(id, part2Track2Links, Seq(), LinkStatus.Transfer, "test",
        9999, 2, 2, None, 1, 5, Some(1L), false, None)

      val projectLinks2 = ProjectDAO.getProjectLinks(id)

      val parts = projectLinks2.partition(_.roadPartNumber === 1)
      val part1tracks = parts._1.partition(_.track === Track.RightSide)
      part1tracks._1.maxBy(_.endAddrMValue).endAddrMValue should be(part1tracks._2.maxBy(_.endAddrMValue).endAddrMValue)
      val part2tracks = parts._2.partition(_.track === Track.RightSide)
      part2tracks._1.maxBy(_.endAddrMValue).endAddrMValue should be(part2tracks._2.maxBy(_.endAddrMValue).endAddrMValue)
    }
  }

  test("Transfer the rest of the part 2 and then the last ajr 1 & 2 links from part 1 to part 2 and adjust endAddrMValues for last links from transfered part") {

    runWithRollback {
      /**
        * Test data
        */
      val rap = RoadAddressProject(0L, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("1901-01-01"),
        "TestUser", DateTime.parse("1990-01-01"), DateTime.now(), "Some additional info",
        Seq(), None)

      //part1
      //track1
      sqlu"""INSERT INTO LRM_POSITION VALUES(lrm_position_primary_key_seq.nextval, NULL, 2, 0, 9.0, NULL, 12345, 1510876800000, TIMESTAMP '2018-03-06 09:56:18.675242', 1)""".execute
      sqlu"""INSERT INTO ROAD_ADDRESS VALUES(viite_general_seq.nextval, 9999, 1, 1, 5, 0, 9, lrm_position_primary_key_seq.currval, TIMESTAMP '1980-08-01 00:00:00.000000', NULL, 'TR', TIMESTAMP '2015-12-30 00:00:00.000000', 2, '0', MDSYS.SDO_GEOMETRY(4002, 3067, NULL, MDSYS.SDO_ELEM_INFO_ARRAY(1, 2, 1), MDSYS.SDO_ORDINATE_ARRAY(5.0, 0.0, 0, 0, 5.0, 9.0, 0, 9)), NULL, 1, 1, 0, 9990)""".execute

      sqlu"""INSERT INTO LRM_POSITION VALUES(lrm_position_primary_key_seq.nextval, NULL, 2, 0, 12.0, NULL, 12346, 1510876800000, TIMESTAMP '2018-03-06 09:56:18.675242', 1)""".execute
      sqlu"""INSERT INTO ROAD_ADDRESS VALUES(viite_general_seq.nextval, 9999, 1, 1, 5, 9, 21, lrm_position_primary_key_seq.currval, TIMESTAMP '1980-08-01 00:00:00.000000', NULL, 'TR', TIMESTAMP '2015-12-30 00:00:00.000000', 2, '0', MDSYS.SDO_GEOMETRY(4002, 3067, NULL, MDSYS.SDO_ELEM_INFO_ARRAY(1, 2, 1), MDSYS.SDO_ORDINATE_ARRAY(5.0, 9.0, 0, 9, 5.0, 21.0, 0, 21)), NULL, 1, 1, 0, 9991)""".execute

      sqlu"""INSERT INTO LRM_POSITION VALUES(lrm_position_primary_key_seq.nextval, NULL, 2, 0, 5.0, NULL, 12347, 1510876800000, TIMESTAMP '2018-03-06 09:56:18.675242', 1)""".execute
      sqlu"""INSERT INTO ROAD_ADDRESS VALUES(viite_general_seq.nextval, 9999, 1, 1, 5, 21, 26, lrm_position_primary_key_seq.currval, TIMESTAMP '1980-08-01 00:00:00.000000', NULL, 'TR', TIMESTAMP '2015-12-30 00:00:00.000000', 2, '0', MDSYS.SDO_GEOMETRY(4002, 3067, NULL, MDSYS.SDO_ELEM_INFO_ARRAY(1, 2, 1), MDSYS.SDO_ORDINATE_ARRAY(5.0, 21.0, 0, 21, 5.0, 26.0, 0, 26)), NULL, 1, 1, 0, 9992)""".execute

      //track2
      sqlu"""INSERT INTO LRM_POSITION VALUES(lrm_position_primary_key_seq.nextval, NULL, 2, 0, 10.0, NULL, 12348, 1510876800000, TIMESTAMP '2018-03-06 09:56:18.675242', 1)""".execute
      sqlu"""INSERT INTO ROAD_ADDRESS VALUES(viite_general_seq.nextval, 9999, 1, 2, 5, 0, 10, lrm_position_primary_key_seq.currval, TIMESTAMP '1980-08-01 00:00:00.000000', NULL, 'TR', TIMESTAMP '2015-12-30 00:00:00.000000', 2, '0', MDSYS.SDO_GEOMETRY(4002, 3067, NULL, MDSYS.SDO_ELEM_INFO_ARRAY(1, 2, 1), MDSYS.SDO_ORDINATE_ARRAY(0.0, 0.0, 0, 0, 0.0, 10.0, 0, 10)), NULL, 1, 1, 0, 9993)""".execute

      sqlu"""INSERT INTO LRM_POSITION VALUES(lrm_position_primary_key_seq.nextval, NULL, 2, 0, 8.0, NULL, 12349, 1510876800000, TIMESTAMP '2018-03-06 09:56:18.675242', 1)""".execute
      sqlu"""INSERT INTO ROAD_ADDRESS VALUES(viite_general_seq.nextval, 9999, 1, 2, 5, 10, 18, lrm_position_primary_key_seq.currval, TIMESTAMP '1980-08-01 00:00:00.000000', NULL, 'TR', TIMESTAMP '2015-12-30 00:00:00.000000', 2, '0', MDSYS.SDO_GEOMETRY(4002, 3067, NULL, MDSYS.SDO_ELEM_INFO_ARRAY(1, 2, 1), MDSYS.SDO_ORDINATE_ARRAY(0.0, 10.0, 0, 10, 0.0, 18.0, 0, 18)), NULL, 1, 1, 0, 9994)""".execute

      sqlu"""INSERT INTO LRM_POSITION VALUES(lrm_position_primary_key_seq.nextval, NULL, 2, 0, 5.0, NULL, 12350, 1510876800000, TIMESTAMP '2018-03-06 09:56:18.675242', 1)""".execute
      sqlu"""INSERT INTO ROAD_ADDRESS VALUES(viite_general_seq.nextval, 9999, 1, 2, 5, 18, 23, lrm_position_primary_key_seq.currval, TIMESTAMP '1980-08-01 00:00:00.000000', NULL, 'TR', TIMESTAMP '2015-12-30 00:00:00.000000', 2, '0', MDSYS.SDO_GEOMETRY(4002, 3067, NULL, MDSYS.SDO_ELEM_INFO_ARRAY(1, 2, 1), MDSYS.SDO_ORDINATE_ARRAY(0.0, 18.0, 0, 18, 0.0, 23.0, 0, 23)), NULL, 1, 1, 0, 9995)""".execute

      sqlu"""INSERT INTO LRM_POSITION VALUES(lrm_position_primary_key_seq.nextval, NULL, 2, 0, 3.0, NULL, 12351, 1510876800000, TIMESTAMP '2018-03-06 09:56:18.675242', 1)""".execute
      sqlu"""INSERT INTO ROAD_ADDRESS VALUES(viite_general_seq.nextval, 9999, 1, 2, 5, 23, 26, lrm_position_primary_key_seq.currval, TIMESTAMP '1980-08-01 00:00:00.000000', NULL, 'TR', TIMESTAMP '2015-12-30 00:00:00.000000', 2, '0', MDSYS.SDO_GEOMETRY(4002, 3067, NULL, MDSYS.SDO_ELEM_INFO_ARRAY(1, 2, 1), MDSYS.SDO_ORDINATE_ARRAY(0.0, 23.0, 0, 23, 0.0, 26.0, 0, 26)), NULL, 1, 1, 0, 9996)""".execute

      //part2
      //track1
      sqlu"""INSERT INTO LRM_POSITION VALUES(lrm_position_primary_key_seq.nextval, NULL, 2, 0, 2.0, NULL, 12352, 1510876800000, TIMESTAMP '2018-03-06 09:56:18.675242', 1)""".execute
      sqlu"""INSERT INTO ROAD_ADDRESS VALUES(viite_general_seq.nextval, 9999, 2, 1, 5, 0, 2, lrm_position_primary_key_seq.currval, TIMESTAMP '1980-08-01 00:00:00.000000', NULL, 'TR', TIMESTAMP '2015-12-30 00:00:00.000000', 2, '0', MDSYS.SDO_GEOMETRY(4002, 3067, NULL, MDSYS.SDO_ELEM_INFO_ARRAY(1, 2, 1), MDSYS.SDO_ORDINATE_ARRAY(5.0, 26.0, 0, 0, 5.0, 28.0, 0, 2)), NULL, 1, 1, 0, 9997)""".execute

      sqlu"""INSERT INTO LRM_POSITION VALUES(lrm_position_primary_key_seq.nextval, NULL, 2, 0, 7.0, NULL, 12353, 1510876800000, TIMESTAMP '2018-03-06 09:56:18.675242', 1)""".execute
      sqlu"""INSERT INTO ROAD_ADDRESS VALUES(viite_general_seq.nextval, 9999, 2, 1, 5, 2, 9, lrm_position_primary_key_seq.currval, TIMESTAMP '1980-08-01 00:00:00.000000', NULL, 'TR', TIMESTAMP '2015-12-30 00:00:00.000000', 2, '0', MDSYS.SDO_GEOMETRY(4002, 3067, NULL, MDSYS.SDO_ELEM_INFO_ARRAY(1, 2, 1), MDSYS.SDO_ORDINATE_ARRAY(5.0, 28.0, 0, 2, 5.0, 35.0, 0, 7)), NULL, 1, 1, 0, 9998)""".execute

      //track2
      sqlu"""INSERT INTO LRM_POSITION VALUES(lrm_position_primary_key_seq.nextval, NULL, 2, 0, 3.0, NULL, 12354, 1510876800000, TIMESTAMP '2018-03-06 09:56:18.675242', 1)""".execute
      sqlu"""INSERT INTO ROAD_ADDRESS VALUES(viite_general_seq.nextval, 9999, 2, 2, 5, 0, 3, lrm_position_primary_key_seq.currval, TIMESTAMP '1980-08-01 00:00:00.000000', NULL, 'TR', TIMESTAMP '2015-12-30 00:00:00.000000', 2, '0', MDSYS.SDO_GEOMETRY(4002, 3067, NULL, MDSYS.SDO_ELEM_INFO_ARRAY(1, 2, 1), MDSYS.SDO_ORDINATE_ARRAY(0.0, 26.0, 0, 0, 0.0, 29.0, 0, 3)), NULL, 1, 1, 0, 9999)""".execute

      sqlu"""INSERT INTO LRM_POSITION VALUES(lrm_position_primary_key_seq.nextval, NULL, 2, 0, 8.0, NULL, 12355, 1510876800000, TIMESTAMP '2018-03-06 09:56:18.675242', 1)""".execute
      sqlu"""INSERT INTO ROAD_ADDRESS VALUES(viite_general_seq.nextval, 9999, 2, 2, 5, 3, 11, lrm_position_primary_key_seq.currval, TIMESTAMP '1980-08-01 00:00:00.000000', NULL, 'TR', TIMESTAMP '2015-12-30 00:00:00.000000', 2, '0', MDSYS.SDO_GEOMETRY(4002, 3067, NULL, MDSYS.SDO_ELEM_INFO_ARRAY(1, 2, 1), MDSYS.SDO_ORDINATE_ARRAY(0.0, 29.0, 0, 3, 0.0, 37.0, 0, 11)), NULL, 1, 1, 0, 10000)""".execute

      val project = projectService.createRoadLinkProject(rap)
      val id = project.id
      val part1 = RoadAddressDAO.fetchByRoadPart(9999, 1)
      val part2 = RoadAddressDAO.fetchByRoadPart(9999, 2)
      val toProjectLinks = (part1 ++ part2).map(toProjectLink(rap))
      val roadLinks = toProjectLinks.map(toRoadLink)
      when(mockRoadLinkService.getRoadLinksHistoryFromVVH(any[Set[Long]])).thenReturn(Seq())
      when(mockRoadLinkService.getRoadLinksByLinkIdsFromVVH(any[Set[Long]], any[Boolean], any[Boolean])).thenReturn(roadLinks)
      projectService.saveProject(project.copy(reservedParts = Seq(ReservedRoadPart(0L, 9999, 1, null, Some(Continuous), Some(1L), None, None, None, None, true), ReservedRoadPart(0L, 9999, 2, null, Some(Continuous), Some(1L), None, None, None, None, true))))

      val projectLinks = ProjectDAO.getProjectLinks(id)
      val part1track1 = Set(12345L, 12346L, 12347L)
      val part1track2 = Set(12348L, 12349L, 12350L, 12351L)
      val part1track1Links = projectLinks.filter(pl => part1track1.contains(pl.linkId)).map(_.id).toSet
      val part1Track2Links = projectLinks.filter(pl => part1track2.contains(pl.linkId)).map(_.id).toSet

      when(mockRoadLinkService.getRoadLinksByLinkIdsFromVVH(any[Set[Long]], any[Boolean], any[Boolean])).thenReturn(projectLinks.filter(pl => part1track1Links.contains(pl.linkId)).map(toRoadLink))
      ProjectDAO.updateProjectLinks(part1track1Links, LinkStatus.UnChanged, "test")
      when(mockRoadLinkService.getRoadLinksByLinkIdsFromVVH(any[Set[Long]], any[Boolean], any[Boolean])).thenReturn(projectLinks.filter(pl => part1Track2Links.contains(pl.linkId)).map(toRoadLink))
      ProjectDAO.updateProjectLinks(part1Track2Links, LinkStatus.UnChanged, "test")

      val part2track1 = Set(12352L, 12353L)
      val part2track2 = Set(12354L, 12355L)
      val part2track1Links = projectLinks.filter(pl => part2track1.contains(pl.linkId)).map(_.id).toSet
      val part2Track2Links = projectLinks.filter(pl => part2track2.contains(pl.linkId)).map(_.id).toSet

      when(mockRoadLinkService.getRoadLinksByLinkIdsFromVVH(any[Set[Long]], any[Boolean], any[Boolean])).thenReturn(projectLinks.filter(pl => part2track1Links.contains(pl.linkId)).map(toRoadLink))
      projectService.updateProjectLinks(id, part2track1Links, Seq(), LinkStatus.Transfer, "test",
        9999, 2, 1, None, 1, 5, Some(1L), false, None)
      when(mockRoadLinkService.getRoadLinksByLinkIdsFromVVH(any[Set[Long]], any[Boolean], any[Boolean])).thenReturn(projectLinks.filter(pl => part2Track2Links.contains(pl.linkId)).map(toRoadLink))
      projectService.updateProjectLinks(id, part2Track2Links, Seq(), LinkStatus.Transfer, "test",
        9999, 2, 2, None, 1, 5, Some(1L), false, None)
      /**
        * Tranfering adjacents of part1 to part2
        */
      val part1AdjacentToPart2IdRightSide = Set(12347L)
      val part1AdjacentToPart2IdLeftSide = Set(12351L)
      val part1AdjacentToPart2LinkRightSide = projectLinks.filter(pl => part1AdjacentToPart2IdRightSide.contains(pl.linkId)).map(_.id).toSet
      val part1AdjacentToPart2LinkLeftSide = projectLinks.filter(pl => part1AdjacentToPart2IdLeftSide.contains(pl.linkId)).map(_.id).toSet


      when(mockRoadLinkService.getRoadLinksByLinkIdsFromVVH(any[Set[Long]], any[Boolean], any[Boolean])).thenReturn(projectLinks.filter(pl => part1AdjacentToPart2IdRightSide.contains(pl.linkId)).map(toRoadLink))
      projectService.updateProjectLinks(id, part1AdjacentToPart2LinkRightSide, Seq(), LinkStatus.Transfer, "test",
        9999, 2, 1, None, 1, 5, Some(1L), false, None)
      when(mockRoadLinkService.getRoadLinksByLinkIdsFromVVH(any[Set[Long]], any[Boolean], any[Boolean])).thenReturn(projectLinks.filter(pl => part1Track2Links.contains(pl.linkId)).map(toRoadLink))
      projectService.updateProjectLinks(id, part1AdjacentToPart2LinkLeftSide, Seq(), LinkStatus.Transfer, "test",
        9999, 2, 2, None, 1, 5, Some(1L), false, None)

      val projectLinks2 = ProjectDAO.getProjectLinks(id)

      val parts = projectLinks2.partition(_.roadPartNumber === 1)
      val part1tracks = parts._1.partition(_.track === Track.RightSide)
      part1tracks._1.maxBy(_.endAddrMValue).endAddrMValue should be(part1tracks._2.maxBy(_.endAddrMValue).endAddrMValue)
      val part2tracks = parts._2.partition(_.track === Track.RightSide)
      part2tracks._1.maxBy(_.endAddrMValue).endAddrMValue should be(part2tracks._2.maxBy(_.endAddrMValue).endAddrMValue)
    }
  }

  test("Check creation of new RoadAddress History entries") {
    var count = 0
    val roadLink = RoadLink(5170939L, Seq(Point(535605.272, 6982204.22, 85.90899999999965))
      , 540.3960283713503, State, 99, TrafficDirection.AgainstDigitizing, UnknownLinkType, Some("25.06.2015 03:00:00"), Some("vvh_modified"), Map("MUNICIPALITYCODE" -> BigInt.apply(749)),
      InUse, NormalLinkInterface)
    runWithRollback {
      val countCurrentProjects = projectService.getRoadAddressAllProjects
      val id = 0
      val addresses = List(ReservedRoadPart(5: Long, 5: Long, 207: Long, Some(5L), Some(Discontinuity.apply("jatkuva")), Some(8L), newLength = None, newDiscontinuity = None, newEly = None))
      val roadAddressProject = RoadAddressProject(id, ProjectState.apply(1), "TestProject", "TestUser", DateTime.now(), "TestUser", DateTime.parse("1901-01-01"), DateTime.now(), "Some additional info", Seq(), None)
      val savedProject = projectService.createRoadLinkProject(roadAddressProject)
      mockForProject(savedProject.id, RoadAddressDAO.fetchByRoadPart(5, 207).map(toProjectLink(savedProject)))
      projectService.saveProject(savedProject.copy(reservedParts = addresses))
      val countAfterInsertProjects = projectService.getRoadAddressAllProjects
      count = countCurrentProjects.size + 1
      countAfterInsertProjects.size should be(count)
      projectService.allLinksHandled(savedProject.id) should be(false)
      val projectLinks = ProjectDAO.getProjectLinks(savedProject.id)
      val partitioned = projectLinks.partition(_.roadPartNumber == 207)
      val highestDistanceStart = projectLinks.map(p => p.startAddrMValue).max
      val highestDistanceEnd = projectLinks.map(p => p.endAddrMValue).max
      val linkIds207 = partitioned._1.map(_.linkId).toSet
      reset(mockRoadLinkService)
      when(mockRoadLinkService.getRoadLinksHistoryFromVVH(any[Set[Long]])).thenReturn(Seq())
      when(mockRoadLinkService.getRoadLinksByLinkIdsFromVVH(any[Set[Long]], any[Boolean], any[Boolean])).thenAnswer(
        toMockAnswer(projectLinks, roadLink)
      )
      projectService.updateProjectLinks(savedProject.id, Set(), Seq(5168510), LinkStatus.Terminated, "-", 0, 0, 0, Option.empty[Int])
      projectService.updateProjectLinks(savedProject.id, Set(), linkIds207.filterNot(_ == 5168510L).toSeq, LinkStatus.Transfer, "-", 0, 0, 0, Option.empty[Int])
      projectService.allLinksHandled(savedProject.id) should be(true)
      val roadAddresses = RoadAddressDAO.queryById(projectLinks.map(_.roadAddressId).toSet)
      val test = roadAddresses.map(ra => ra.id -> ra).toMap
      val historicRoadsIds = projectService.createHistoryRows(projectLinks, test)
      RoadAddressDAO.queryById(historicRoadsIds.toSet).map(_.endDate).forall(_.isDefined) should be(true)

    }
  }

  test("Road names should not have valid road name for any roadnumber after TR response") {
    runWithRollback {
      val projectId = Sequences.nextViitePrimaryKeySeqValue
      sqlu"""INSERT INTO ROAD_NAMES VALUES (ROAD_NAME_SEQ.nextval, 66666, 'ROAD TEST', TIMESTAMP '2018-03-23 12:26:36.000000', null, TIMESTAMP '2018-03-23 12:26:36.000000', null, 'test user', TIMESTAMP '2018-03-23 12:26:36.000000')""".execute

      sqlu"""INSERT INTO PROJECT VALUES($projectId, 2, 'test project', 8, 'silari', TIMESTAMP '2018-03-23 11:36:15.000000', '-', TIMESTAMP '2018-03-23 12:26:33.000000', NULL, TIMESTAMP '2018-03-23 00:00:00.000000', NULL, 0, 1, 533406.572, 6994060.048, 12)""".execute
      sqlu"""INSERT INTO PROJECT_RESERVED_ROAD_PART VALUES (${Sequences.nextViitePrimaryKeySeqValue}, 66666, 1, $projectId, '-')""".execute
      sqlu"""INSERT INTO LRM_POSITION VALUES (lrm_position_primary_key_seq.nextval, 0, 2, 0, 85.617, NULL, 5170979, 1500079296000, TIMESTAMP '2018-03-23 00:00:00.000000', 1)""".execute
      sqlu"""INSERT INTO PROJECT_LINK VALUES (${Sequences.nextViitePrimaryKeySeqValue}, $projectId, 0, 5, 66666, 1, 0, 86, lrm_position_primary_key_seq.currval, 'test user', 'test user', TIMESTAMP '2018-03-23 12:26:36.000000', TIMESTAMP '2018-03-23 00:00:00.000000', 2, 3, 1, NULL, NULL, 8, 0, '[533399.731,6994038.906,126.260],[533390.742,6994052.408,126.093],[533387.649,6994056.057,126.047],[533348.256,6994107.273,125.782]')""".execute
      sqlu"""INSERT INTO PROJECT_LINK_NAME VALUES (PROJECT_LINK_NAME_SEQ.nextval, $projectId, 66666, 'ROAD TEST')""".execute
      val namesBeforeUpdate = RoadNameDAO.getLatestRoadName(66666)
      projectService.updateRoadAddressWithProjectLinks(ProjectState.Saved2TR, projectId)

      val project = projectService.getRoadAddressSingleProject(projectId)
      val validNamesAfterUpdate = RoadNameDAO.getCurrentRoadNamesByRoadNumber(66666)
      validNamesAfterUpdate.size should be(1)
      validNamesAfterUpdate.head.roadName should be(namesBeforeUpdate.get.roadName)
      project.get.statusInfo.getOrElse("") should be(roadNameWasNotSavedInProject + s"${66666}")
    }
  }

    // Based on the "Terminate then transfer" test, this one checks for
  test("Provoke a IOException or ClientProtocolException exception when publishing a project and then check if the project state is changed to 9") {

      var count = 0
      val roadLink = RoadLink(5170939L, Seq(Point(535605.272, 6982204.22, 85.90899999999965)), 540.3960283713503, State,
        99, TrafficDirection.AgainstDigitizing, UnknownLinkType, Some("25.06.2015 03:00:00"), Some("vvh_modified"),
        Map("MUNICIPALITYCODE" -> BigInt.apply(749)), InUse, NormalLinkInterface)
      runWithRollback {
        val countCurrentProjects = projectService.getRoadAddressAllProjects
        val id = 0
        val addresses = List(ReservedRoadPart(5: Long, 5: Long, 207: Long, Some(5L), Some(Discontinuity.apply("jatkuva")),
          Some(8L), newLength = None, newDiscontinuity = None, newEly = None))
        val roadAddressProject = RoadAddressProject(id, ProjectState.apply(1), "TestProject", "TestUser", DateTime.now(),
          "TestUser", DateTime.parse("1901-01-01"), DateTime.now(), "Some additional info", Seq(), None)
        val savedProject = projectService.createRoadLinkProject(roadAddressProject)
        mockForProject(savedProject.id, RoadAddressDAO.fetchByRoadPart(5, 207).map(toProjectLink(savedProject)))
        projectService.saveProject(savedProject.copy(reservedParts = addresses))
        val countAfterInsertProjects = projectService.getRoadAddressAllProjects
        count = countCurrentProjects.size + 1
        countAfterInsertProjects.size should be(count)
        projectService.allLinksHandled(savedProject.id) should be(false)
        val projectLinks = ProjectDAO.getProjectLinks(savedProject.id)
        val partitioned = projectLinks.partition(_.roadPartNumber == 207)
        val linkIds207 = partitioned._1.map(_.linkId).toSet
        reset(mockRoadLinkService)
        when(mockRoadLinkService.getRoadLinksHistoryFromVVH(any[Set[Long]])).thenReturn(Seq())
        when(mockRoadLinkService.getRoadLinksByLinkIdsFromVVH(any[Set[Long]], any[Boolean], any[Boolean])).thenAnswer(
          toMockAnswer(projectLinks, roadLink)
        )
        projectService.updateProjectLinks(savedProject.id, Set(), linkIds207.toSeq, LinkStatus.Transfer, "-", 0, 0, 0, Option.empty[Int]) should be(None)
        projectService.updateProjectLinks(savedProject.id, Set(), Seq(5168510), LinkStatus.Terminated, "-", 0, 0, 0, Option.empty[Int]) should be(None)
        projectService.allLinksHandled(savedProject.id) should be(true)
        val changeProjectOpt = projectService.getChangeProject(savedProject.id)
        projectService.updateProjectLinks(savedProject.id, Set(), Seq(5168540), LinkStatus.Terminated, "-", 0, 0, 0, Option.empty[Int]) should be(None)
        // This will result in a NonFatal exception being thrown and caught inside the publish, making the update of the project for the state ErrorInViite
        // If the tests ever get a way to have TR connectivity then this needs to be somewhat addressed
        projectService.publishProject(savedProject.id)
        val currentProjectStatus = ProjectDAO.getProjectStatus(savedProject.id)
        currentProjectStatus.isDefined should be(true)
        currentProjectStatus.get.value should be(ProjectState.SendingToTR.value)
      }

    }

  test("Check correct roadName assignment via ProjectLinkName") {
    val roadNumber = 5L
    val roadPartNumber = 207L
    val testRoadName = "forTestingPurposes"
    runWithRollback {
      val rap = RoadAddressProject(0L, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("1901-01-01"),
        "TestUser", DateTime.parse("1901-01-01"), DateTime.now(), "Some additional info",
        Seq(), None)
      val project = projectService.createRoadLinkProject(rap)
      val id = project.id
      mockForProject(id, RoadAddressDAO.fetchByRoadPart(roadNumber, roadPartNumber).map(toProjectLink(project)))
      projectService.saveProject(project.copy(reservedParts = Seq(
        ReservedRoadPart(0L, 5, 207, Some(0L), Some(Continuous), Some(8L), None, None, None, None, true))))
      val projectLinks = ProjectDAO.getProjectLinks(id)
      ProjectLinkNameDAO.create(id, roadNumber, testRoadName)

      projectService.fillRoadNames(projectLinks.head).roadName.get should be(testRoadName)
    }
  }

  test("Check correct roadName assignment via RoadNameDao") {
    val roadNumber = 5L
    val roadPartNumber = 207L
    val testRoadName = "forTestingPurposes"
    runWithRollback {
      val rap = RoadAddressProject(0L, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("1901-01-01"),
        "TestUser", DateTime.parse("1901-01-01"), DateTime.now(), "Some additional info",
        Seq(), None)
      val project = projectService.createRoadLinkProject(rap)
      val id = project.id
      mockForProject(id, RoadAddressDAO.fetchByRoadPart(roadNumber, roadPartNumber).map(toProjectLink(project)))
      projectService.saveProject(project.copy(reservedParts = Seq(
        ReservedRoadPart(0L, 5, 207, Some(0L), Some(Continuous), Some(8L), None, None, None, None, true))))
      val projectLinks = ProjectDAO.getProjectLinks(id)

      projectService.fillRoadNames(projectLinks.head).roadName.get should be(RoadNameDAO.getLatestRoadName(roadNumber).get.roadName)
    }
  }

  test("Check correct roadName assignment via the name on the project link") {
    val roadNumber = 5L
    val roadPartNumber = 207L
    val testRoadName = "forTestingPurposes"
    runWithRollback {
      val rap = RoadAddressProject(0L, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("1901-01-01"),
        "TestUser", DateTime.parse("1901-01-01"), DateTime.now(), "Some additional info",
        Seq(), None)
      val project = projectService.createRoadLinkProject(rap)
      val id = project.id
      mockForProject(id, RoadAddressDAO.fetchByRoadPart(roadNumber, roadPartNumber).map(toProjectLink(project)))
      projectService.saveProject(project.copy(reservedParts = Seq(
        ReservedRoadPart(0L, 5, 207, Some(0L), Some(Continuous), Some(8L), None, None, None, None, true))))
      val projectLinks = ProjectDAO.getProjectLinks(id)

      projectService.fillRoadNames(projectLinks.head.copy(roadNumber = 9999L, roadName = Option(testRoadName))).roadName.get should be(testRoadName)
    }
  }

  test("If the suplied, old, road address has a valid_to < sysdate then the outputted, new, road addresses are floating") {
    val road = 5L
    val roadPart = 205L
    val origStartM = 1024L
    val origEndM = 1547L
    val origStartD = Some(DateTime.now().minusYears(10))
    val linkId = 1049L
    val endM = 520.387
    val suravageLinkId = 5774839L
    val user = Some("user")
    val project = RoadAddressProject(-1L, Sent2TR, "split", user.get, DateTime.now(), user.get,
      DateTime.now().plusMonths(2), DateTime.now(), "", Seq(), None, None)

    // Original road address: 1024 -> 1547
    val roadAddress = RoadAddress(1L, road, roadPart, PublicRoad, Track.Combined, Continuous, origStartM, origEndM, origStartD,
      None, None, 1L, linkId, 0.0, endM, SideCode.TowardsDigitizing, 86400L, (None, None), false, Seq(Point(1024.0, 0.0), Point(1025.0, 1544.386)),
      LinkGeomSource.NormalLinkInterface, 8L, NoTermination, 123)

    val projectLink = ProjectLink(0, road, roadPart, Track.Combined, Continuous, 0, 0, Some(DateTime.now()), None, user,
      0, 0, 0.0, 0.0, SideCode.TowardsDigitizing, (None, None), false, Seq(Point(0.0, 0.0), Point(0.0, 0.0)),
      -1L, null, PublicRoad, null, 0.0, 1L, 8L, false, None, 748800L)
    val transferAndNew = Seq(

      // Transferred road address: 1028 -> 1128
      projectLink.copy(id = 2, startAddrMValue = origStartM + 4, endAddrMValue = origStartM + 104, lrmPositionId = 2, linkId = suravageLinkId,
        startMValue = 0.0, endMValue = 99.384, geometry = Seq(Point(1024.0, 0.0), Point(1024.0, 99.384)), status = LinkStatus.Transfer,
        linkGeomSource = LinkGeomSource.SuravageLinkInterface, geometryLength = 99.384, connectedLinkId = Some(linkId)),

      // New road address: 1128 -> 1205
      projectLink.copy(id = 3, startAddrMValue = origStartM + 104, endAddrMValue = origStartM + 181, lrmPositionId = 3, linkId = suravageLinkId,
        startMValue = 99.384, endMValue = 176.495, geometry = Seq(Point(1024.0, 99.384), Point(1101.111, 99.384)), status = LinkStatus.New,
        linkGeomSource = LinkGeomSource.SuravageLinkInterface, geometryLength = 77.111, connectedLinkId = Some(linkId)),

      // Terminated road address: 1124 -> 1547
      projectLink.copy(id = 4, startAddrMValue = origStartM + 100, endAddrMValue = origEndM, lrmPositionId = 4, linkId = linkId,
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
    result.filter(res => res.terminated == TerminationCode.NoTermination && res.commonHistoryId != -1000).forall(_.floating) should be(true)

  }

  test("road name should not be saved saved on TR success response if road number > 70.000 and it has no name") {
    runWithRollback {
      val projectId = Sequences.nextViitePrimaryKeySeqValue
      sqlu"""INSERT INTO PROJECT VALUES($projectId, 2, 'test project', 8, 'silari', TIMESTAMP '2018-03-23 11:36:15.000000', '-', TIMESTAMP '2018-03-23 12:26:33.000000', NULL, TIMESTAMP '2018-03-23 00:00:00.000000', NULL, 0, 1, 533406.572, 6994060.048, 12)""".execute
      sqlu"""INSERT INTO PROJECT_RESERVED_ROAD_PART VALUES (${Sequences.nextViitePrimaryKeySeqValue}, 70001, 1, $projectId, '-')""".execute
      sqlu"""INSERT INTO LRM_POSITION VALUES (lrm_position_primary_key_seq.nextval, 0, 2, 0, 85.617, NULL, 5170979, 1500079296000, TIMESTAMP '2018-03-23 00:00:00.000000', 1)""".execute
      sqlu"""INSERT INTO PROJECT_LINK VALUES (${Sequences.nextViitePrimaryKeySeqValue}, $projectId, 0, 5, 70001, 1, 0, 86, lrm_position_primary_key_seq.currval, 'test user', 'test user', TIMESTAMP '2018-03-23 12:26:36.000000', TIMESTAMP '2018-03-23 00:00:00.000000', 2, 3, 1, NULL, NULL, 8, 0, '[533399.731,6994038.906,126.260],[533390.742,6994052.408,126.093],[533387.649,6994056.057,126.047],[533348.256,6994107.273,125.782]')""".execute
      sqlu"""INSERT INTO PROJECT_LINK_NAME VALUES (PROJECT_LINK_NAME_SEQ.nextval, $projectId, 70001, NULL)""".execute
      val namesBeforeUpdate = RoadNameDAO.getLatestRoadName(70001)
      namesBeforeUpdate.isEmpty should be(true)
      projectService.updateRoadAddressWithProjectLinks(ProjectState.Saved2TR, projectId)

      val project = projectService.getRoadAddressSingleProject(projectId)
      val namesAfterUpdate = RoadNameDAO.getLatestRoadName(70001)
      project.get.statusInfo should be(None)
      namesAfterUpdate.isEmpty should be(true)
    }
  }

}

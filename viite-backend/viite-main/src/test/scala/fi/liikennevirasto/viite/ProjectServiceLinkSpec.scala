package fi.liikennevirasto.viite

import fi.liikennevirasto.digiroad2._
import fi.liikennevirasto.digiroad2.service.RoadLinkService
import fi.liikennevirasto.viite.dao._
import fi.liikennevirasto.viite.dao.ProjectCalibrationPointDAO.UserDefinedCalibrationPoint
import fi.liikennevirasto.viite.dao.TerminationCode.NoTermination
import fi.liikennevirasto.viite.model.{ProjectAddressLink, RoadAddressLinkLike}
import fi.liikennevirasto.viite.process.{ProjectSectionCalculator, RoadwayAddressMapper}
import fi.liikennevirasto.viite.util.{StaticTestData, _}
import fi.vaylavirasto.viite.dao.{ProjectLinkNameDAO, Sequences}
import fi.vaylavirasto.viite.geometry.{GeometryUtils, Point, PolyLine}
import fi.vaylavirasto.viite.model.{AddrMRange, AdministrativeClass, CalibrationPointType, Discontinuity, LifecycleStatus, LinkGeomSource, RoadAddressChangeType, RoadLink, RoadPart, SideCode, Track, TrafficDirection}
import fi.vaylavirasto.viite.postgis.PostGISDatabaseScalikeJDBC.runWithRollback
import org.joda.time.DateTime
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.{reset, when}
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.{Answer, OngoingStubbing}
import org.scalatest.BeforeAndAfter
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import scala.concurrent.Future
import org.json4s._
import org.json4s.jackson.JsonMethods

class ProjectServiceLinkSpec extends AnyFunSuite with Matchers with BeforeAndAfter {
  val mockProjectService: ProjectService = MockitoSugar.mock[ProjectService]
  val mockRoadLinkService: RoadLinkService = MockitoSugar.mock[RoadLinkService]
  val mockRoadAddressService: RoadAddressService = MockitoSugar.mock[RoadAddressService]
  val mockRoadwayAddressMapper: RoadwayAddressMapper = MockitoSugar.mock[RoadwayAddressMapper]
  val mockNodesAndJunctionsService: NodesAndJunctionsService = MockitoSugar.mock[NodesAndJunctionsService]
  val mockEventBus: DigiroadEventBus = MockitoSugar.mock[DigiroadEventBus]
  val projectDAO = new ProjectDAO
  val projectLinkDAO = new ProjectLinkDAO
  val roadwayDAO = new RoadwayDAO
  val roadNetworkDAO = new RoadNetworkDAO
  val linearLocationDAO = new LinearLocationDAO
  val roadwayPointDAO = new RoadwayPointDAO
  val nodeDAO = new NodeDAO
  val nodePointDAO = new NodePointDAO
  val junctionPointDAO = new JunctionPointDAO
  val roadwayChangesDAO = new RoadwayChangesDAO
  val projectReservedPartDAO = new ProjectReservedPartDAO
  val roadwayAddressMapper = new RoadwayAddressMapper(roadwayDAO, linearLocationDAO)

  val roadAddressService: RoadAddressService =
    new RoadAddressService(
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

  val projectService: ProjectService =
    new ProjectService(
                        roadAddressService,
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

  private def createProjectLinks(linkIds: Seq[String], projectId: Long, roadPart: RoadPart, track: Int, discontinuity: Int, administrativeClass: Int, roadLinkSource: Int, roadEly: Long, user: String, roadName: String) = {
    projectService.createProjectLinks(linkIds, projectId, roadPart, Track.apply(track), Discontinuity.apply(discontinuity), AdministrativeClass.apply(administrativeClass), LinkGeomSource.apply(roadLinkSource), roadEly, user, roadName)
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
    RoadLink(ral.linkId, ral.geometry, ral.geometryLength, AdministrativeClass.State, extractTrafficDirection(ral.sideCode, ral.track), None, None, LifecycleStatus.InUse, LinkGeomSource.NormalLinkInterface, 749, "")
  }

  private def toRoadLink(ral: RoadAddressLinkLike): RoadLink = {
    RoadLink(ral.linkId, ral.geometry, ral.length, ral.administrativeClass, extractTrafficDirection(ral.sideCode, Track.apply(ral.trackCode.toInt)), ral.modifiedAt, ral.modifiedBy, ral.lifecycleStatus, ral.roadLinkSource, 749, "")
  }

  private def toMockAnswer(projectLinks: Seq[ProjectLink], roadLink: RoadLink, seq: Seq[RoadLink] = Seq()): Answer[Seq[RoadLink]] = {
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

  private def toMockAnswer(roadLinks: Seq[RoadLink]): Answer[Seq[RoadLink]] = {
    new Answer[Seq[RoadLink]]() {
      override def answer(invocation: InvocationOnMock): Seq[RoadLink] = {
        val ids = invocation.getArguments.apply(0).asInstanceOf[Set[String]]
        roadLinks.filter(rl => ids.contains(rl.linkId))
      }
    }
  }

  private def mockForProject[T <: PolyLine](id: Long, l: Seq[T] = Seq()): OngoingStubbing[Seq[RoadLink]] = {
    val roadLink = RoadLink(Sequences.nextViitePrimaryKeySeqValue.toString, Seq(Point(535602.222, 6982200.25, 89.9999), Point(535605.272, 6982204.22, 85.90899999999965)), 540.3960283713503, AdministrativeClass.State, TrafficDirection.AgainstDigitizing, Some("25.06.2015 03:00:00"), Some("vvh_modified"), LifecycleStatus.InUse, LinkGeomSource.NormalLinkInterface, 749, "")
    val (projectLinks, palinks) = l.partition(_.isInstanceOf[ProjectLink])
    val dbLinks = projectLinkDAO.fetchProjectLinks(id)
    when(mockRoadLinkService.getRoadLinksHistoryFromVVH(any[Set[String]])).thenReturn(Seq())
    when(mockRoadLinkService.getRoadLinksByLinkIds(any[Set[String]])).thenAnswer(
      toMockAnswer(dbLinks ++ projectLinks.asInstanceOf[Seq[ProjectLink]].filterNot(l => dbLinks.map(_.linkId).contains(l.linkId)),
        roadLink, palinks.asInstanceOf[Seq[ProjectAddressLink]].map(toRoadLink)
      ))
  }

  // Define an implicit format for case class extraction
  implicit val formats: Formats = DefaultFormats

  test("Test projectService.updateProjectLinks() When using a recently created project Then project should become publishable after the update.") {
    var count = 0
    runWithRollback {
      val countCurrentProjects = projectService.getAllProjects
      val id = 0
      val roadPart = RoadPart(13687, 1)
      val addresses = List(ProjectReservedPart(Sequences.nextViitePrimaryKeySeqValue: Long, roadPart, Some(5534), Some(Discontinuity.apply("jatkuva")), Some(4L), newLength = None, newDiscontinuity = None, newEly = None))
      val roadAddressProject = Project(id, ProjectState.apply(1), "TestProject", "TestUser", DateTime.now(), "TestUser", DateTime.parse("2017-01-01"), DateTime.now(), "Some additional info", Seq(), Seq(), None)
      val saved = projectService.createRoadLinkProject(roadAddressProject)
      mockForProject(saved.id, roadwayAddressMapper.getRoadAddressesByLinearLocation(linearLocationDAO.fetchByRoadways(roadwayDAO.fetchAllBySection(roadPart).map(_.roadwayNumber).toSet)).map(toProjectLink(saved)))
      projectService.saveProject(saved.copy(reservedParts = addresses))
      val countAfterInsertProjects = projectService.getAllProjects
      count = countCurrentProjects.size + 1
      countAfterInsertProjects.size should be(count)
      projectService.isProjectPublishable(saved.id) should be(false)
      val linkIds = projectLinkDAO.fetchProjectLinks(saved.id).map(_.linkId).toSet
      projectService.updateProjectLinks(saved.id, Set(), linkIds.toSeq, RoadAddressChangeType.Termination, "-", RoadPart(0, 0), 0, Option.empty[Int])
      projectService.isProjectPublishable(saved.id) should be(true)
    }
    runWithRollback {
      projectService.getAllProjects
    } should have size (count - 1)
  }

  test("Test projectService.updateProjectLinks() When changing the project links numbering Then project should become publishable after the update and it's links should reflect the number change.") {
    var count = 0

    runWithRollback {
      val countCurrentProjects = projectService.getAllProjects
      val id = 0
      val addresses = List(
        ProjectReservedPart(Sequences.nextViitePrimaryKeySeqValue: Long, RoadPart(5, 207), Some(5L), Some(Discontinuity.apply("jatkuva")), Some(8L), newLength = None, newDiscontinuity = None, newEly = None))
      val roadAddressProject = Project(id, ProjectState.apply(1), "TestProject", "TestUser", DateTime.now(),
        "TestUser", DateTime.parse("1962-11-02"), DateTime.now(), "Some additional info", Seq(), Seq(), None)
      val saved = projectService.createRoadLinkProject(roadAddressProject)
      mockForProject(saved.id, roadwayAddressMapper.getRoadAddressesByLinearLocation(linearLocationDAO.fetchByRoadways(roadwayDAO.fetchAllBySection(RoadPart(5, 207)).map(_.roadwayNumber).toSet)).map(toProjectLink(saved)))
      projectService.saveProject(saved.copy(reservedParts = addresses))
      val countAfterInsertProjects = projectService.getAllProjects
      count = countCurrentProjects.size + 1
      countAfterInsertProjects.size should be(count)
      val projectLinks = projectLinkDAO.fetchProjectLinks(saved.id)
      projectLinks.isEmpty should be(false)

      projectService.isProjectPublishable(saved.id) should be(false)
      val linkIds = projectLinkDAO.fetchProjectLinks(saved.id).map(_.linkId).toSet
      projectService.updateProjectLinks(saved.id, Set(), linkIds.toSeq, RoadAddressChangeType.Renumeration, "-", RoadPart(99999, 1), 0, Option.empty[Int])
      val afterNumberingLinks = projectLinkDAO.fetchProjectLinks(saved.id)
      afterNumberingLinks.foreach(l => (l.roadPart == RoadPart(99999, 1)) should be(true))
    }

  }

  test("Test projectService.addNewLinksToProject() When adding a nonexistent roadlink to project Then when querying for it it should return that one project link was entered.") {
    runWithRollback {
      val idr = Sequences.nextRoadwayId
      val id = Sequences.nextViiteProjectId
      val rap = Project(id, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("2700-01-01"), "TestUser", DateTime.parse("2700-01-01"), DateTime.now(), "Some additional info", List.empty[ProjectReservedPart], Seq(), None)
      val projectLink = toProjectLink(rap, RoadAddressChangeType.New)(RoadAddress(idr, 1, RoadPart(12345, 1), AdministrativeClass.Private, Track.Combined, Discontinuity.Discontinuous, AddrMRange(0L, 10L), Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), 1234522L.toString, 0.0, 9.8, SideCode.TowardsDigitizing, 0, (None, None), Seq(Point(0.0, 0.0), Point(0.0, 9.8)), LinkGeomSource.NormalLinkInterface, 5, NoTermination, 0))
      projectDAO.create(rap)
      mockForProject(id, Seq(projectLink))
      projectService.addNewLinksToProject(Seq(projectLink), id, "U", projectLink.linkId, newTransaction = true, Discontinuity.Discontinuous)
      val links = projectLinkDAO.fetchProjectLinks(id)
      links.size should be(1)
    }
  }

  test("Test projectService.addNewLinksToProject() When adding two consecutive roadlinks to project road number & road part Then check the correct insertion of the roadlinks.") {
    val roadLink = RoadLink(5175306.toString, Seq(Point(535602.222, 6982200.25, 89.9999), Point(535605.272, 6982204.22, 85.90899999999965)), 540.3960283713503, AdministrativeClass.State, TrafficDirection.AgainstDigitizing, Some("25.06.2015 03:00:00"), Some("vvh_modified"), LifecycleStatus.InUse, LinkGeomSource.NormalLinkInterface, 749, "")
    when(mockRoadLinkService.getRoadLinksHistoryFromVVH(any[Set[String]])).thenReturn(Seq())
    when(mockRoadLinkService.getRoadLinksByLinkIds(Set(5175306L.toString))).thenReturn(Seq(roadLink))
    runWithRollback {

      val idr1 = Sequences.nextRoadwayId
      val idr2 = Sequences.nextRoadwayId
      val id = Sequences.nextViiteProjectId
      val rap = Project(id, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("2700-01-01"), "TestUser", DateTime.parse("2700-01-01"), DateTime.now(), "Some additional info", List.empty[ProjectReservedPart], Seq(), None)
      projectDAO.create(rap)

      val projectLink1 = toProjectLink(rap, RoadAddressChangeType.New)(RoadAddress(idr1, 0, RoadPart(12345, 1), AdministrativeClass.Unknown, Track.Combined, Discontinuity.Discontinuous, AddrMRange(0L, 10L), Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), 5175306L.toString, 0.0, 9.8, SideCode.TowardsDigitizing, 0, (None, None), Seq(Point(535602.222, 6982200.25, 89.9999), Point(535605.272, 6982204.22, 85.90899999999965)), LinkGeomSource.NormalLinkInterface, 5, NoTermination, 0))
      val projectLink2 = toProjectLink(rap, RoadAddressChangeType.New)(RoadAddress(idr2, 0, RoadPart(12345, 1), AdministrativeClass.Unknown, Track.Combined, Discontinuity.Discontinuous, AddrMRange(0L, 10L), Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), 1610976L.toString, 0.0, 5.8, SideCode.TowardsDigitizing, 0, (None, None), Seq(Point(535605.272, 6982204.22, 85.90899999999965), Point(535608.555, 6982204.33, 86.90)), LinkGeomSource.NormalLinkInterface, 5, NoTermination, 0))


      val calibrationPoints1 = CalibrationPointsUtils.toCalibrationPoints(projectLink1.calibrationPoints)
      val calibrationPoints2 = CalibrationPointsUtils.toCalibrationPoints(projectLink2.calibrationPoints)

      val p1 = ProjectAddressLink(idr1, projectLink1.linkId, projectLink1.geometry, 1, AdministrativeClass.apply(1), LifecycleStatus.apply(1), projectLink1.linkGeomSource, AdministrativeClass.State, None, 111, "Heinola", Some(""), Some("vvh_modified"), projectLink1.roadPart, 2, -1, projectLink1.discontinuity.value, projectLink1.addrMRange, projectLink1.startMValue, projectLink1.endMValue, projectLink1.sideCode, calibrationPoints1._1, calibrationPoints1._2, projectLink1.status, 0, 0, sourceId = "", originalAddrMRange = Some(projectLink1.originalAddrMRange))
      val p2 = ProjectAddressLink(idr2, projectLink2.linkId, projectLink2.geometry, 1, AdministrativeClass.apply(1), LifecycleStatus.apply(1), projectLink2.linkGeomSource, AdministrativeClass.State, None, 111, "Heinola", Some(""), Some("vvh_modified"), projectLink2.roadPart, 2, -1, projectLink2.discontinuity.value, projectLink2.addrMRange, projectLink2.startMValue, projectLink2.endMValue, projectLink2.sideCode, calibrationPoints2._1, calibrationPoints2._2, projectLink2.status, 0, 0, sourceId = "", originalAddrMRange = Some(projectLink2.originalAddrMRange))

      mockForProject(id, Seq(p1, p2))
      projectService.addNewLinksToProject(Seq(projectLink1), id, "U", p1.linkId, true, Discontinuity.Discontinuous)
      val links = projectLinkDAO.fetchProjectLinks(id)
      links.size should be(1)
      reset(mockRoadLinkService)
      mockForProject(id, Seq(p2))

      projectService.addNewLinksToProject(Seq(projectLink2), id, "U", p2.linkId, true, Discontinuity.Discontinuous)
      val linksAfter = projectLinkDAO.fetchProjectLinks(id)
      linksAfter.size should be(2)
    }
  }

  test("Test projectService.addNewLinksToProject() When adding three new consecutive links having a loop end " +
       "Then discontinuity should be set correctly to the loop end and calculation should success.") {
    val geom1 = Seq(Point(284024.822, 6773956.109, 82.93799999999464), Point( 284024.375, 6773961.664, 82.86800000000221), Point( 284014.196, 6773982.02, 82.65099999999802), Point( 283993.275, 6774000.115, 81.71899999999732), Point( 283964.862, 6774025.098, 81.29499999999825), Point( 283939.701, 6774047.204, 81.16300000000047), Point( 283914.039, 6774069.058, 81.19999999999709), Point( 283888.126, 6774087.896, 81.46300000000338), Point( 283861.718, 6774098.434, 81.778999999995), Point( 283839.086, 6774102.188, 81.85000000000582), Point( 283811.258, 6774102.078, 82.30199999999604))
    val geom2 = Seq(Point(283945.677, 6773839.793, 82.4890000000014 ), Point( 283952.215, 6773850.343, 82.58999999999651), Point( 283959.347, 6773861.01, 82.68300000000454), Point( 283981.966, 6773888.718, 82.84399999999732), Point( 284007.411, 6773918.688, 83.58800000000338), Point( 284021.548, 6773940.175, 83.22999999999593), Point( 284024.822, 6773956.109, 82.93799999999464))
    val geom3 = Seq(Point(283945.677, 6773839.793, 82.4890000000014 ), Point( 283941.368, 6773838.689, 82.38300000000163), Point( 283933.836, 6773837,    82.24000000000524), Point( 283925.983, 6773834.495, 82.18799999999464), Point( 283920.768, 6773828.944, 82.22900000000664), Point( 283921.384, 6773821.824, 82.28399999999965), Point( 283930.303, 6773813.467, 82.2100000000064),  Point( 283938.025, 6773811.022, 82.2100000000064), Point( 283946.367, 6773813.728, 82.20799999999872), Point( 283950.47, 6773818.263, 82.29499999999825), Point( 283949.432, 6773827.206, 82.44700000000012), Point( 283947.648, 6773835.308, 82.46199999999953), Point( 283945.677, 6773839.793, 82.4890000000014))
    val discontinuity = Discontinuity.EndOfRoad

    runWithRollback {
      val idr1 = Sequences.nextRoadwayId
      val idr2 = Sequences.nextRoadwayId
      val idr3 = Sequences.nextRoadwayId
      val projectId = Sequences.nextViiteProjectId
      val rap = Project(projectId, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("2700-01-01"), "TestUser", DateTime.parse("2700-01-01"), DateTime.now(), "Some additional info", List.empty[ProjectReservedPart], Seq(), None)
      projectDAO.create(rap)

      val projectLink1 = toProjectLink(rap, RoadAddressChangeType.New)(RoadAddress(idr1, 0, RoadPart(12345, 1), AdministrativeClass.Unknown, Track.Combined, Discontinuity.Continuous, AddrMRange(0L, 0L), Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), 4224569L.toString, 0.0, 272.266, SideCode.Unknown, 0, (None, None), geom1, LinkGeomSource.NormalLinkInterface, 5, NoTermination, 0))
      val projectLink2 = toProjectLink(rap, RoadAddressChangeType.New)(RoadAddress(idr2, 0, RoadPart(12345, 1), AdministrativeClass.Unknown, Track.Combined, Discontinuity.Continuous, AddrMRange(0L, 0L), Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), 4224590L.toString, 0.0, 142.313, SideCode.Unknown, 0, (None, None), geom2, LinkGeomSource.NormalLinkInterface, 5, NoTermination, 0))
      val projectLink3 = toProjectLink(rap, RoadAddressChangeType.New)(RoadAddress(idr3, 0, RoadPart(12345, 1), AdministrativeClass.Unknown, Track.Combined, Discontinuity.Continuous, AddrMRange(0L, 0L), Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), 12531914L.toString, 0.0, 92.579, SideCode.Unknown, 0, (None, None), geom3, LinkGeomSource.NormalLinkInterface, 5, NoTermination, 0))

      projectService.addNewLinksToProject(Seq(projectLink1, projectLink2, projectLink3), projectId, "U", projectLink1.linkId, true, discontinuity)

      // Check projectLinks are created into database
      val links = projectLinkDAO.fetchProjectLinks(projectId)
      links.size should be(3)

      // Check the last link has given discontinuity set
      val (lastLink, others) = links.partition(_.linkId == projectLink3.linkId)
      lastLink should have size 1
      lastLink.head.discontinuity should be(discontinuity)
      others.forall(_.discontinuity == Discontinuity.Continuous) should be (true)

      // Check calculation succeeds with expected order and values
      val calculated = ProjectSectionCalculator.assignAddrMValues(links)
      val calculatedPl1 = calculated.find(_.linkId == 4224569.toString)
      val calculatedPl2 = calculated.find(_.linkId == 4224590.toString)
      val calculatedPl3 = calculated.find(_.linkId == 12531914.toString)

      calculatedPl1 should be ('defined)
      calculatedPl1.get.addrMRange      should be(AddrMRange(0,272))
      calculatedPl1.get.discontinuity   should be (Discontinuity.Continuous)
      calculatedPl2 should be ('defined)
      calculatedPl2.get.addrMRange      should be(AddrMRange(272,414))
      calculatedPl2.get.discontinuity   should be (Discontinuity.Continuous)
      calculatedPl3 should be ('defined)
      calculatedPl3.get.addrMRange      should be(AddrMRange(414,507))
      calculatedPl3.get.discontinuity   should be (discontinuity)
    }
  }

  test("Test projectService.addNewLinksToProject() When adding three new consecutive links having a loop link start " +
       "Then discontinuity should be set correctly to the loop end and calculation should success.") {
    // Reversed case of previous set up
    val geom1 = Seq(Point(284024.822, 6773956.109, 82.93799999999464), Point( 284024.375, 6773961.664, 82.86800000000221), Point( 284014.196, 6773982.02, 82.65099999999802), Point( 283993.275, 6774000.115, 81.71899999999732), Point( 283964.862, 6774025.098, 81.29499999999825), Point( 283939.701, 6774047.204, 81.16300000000047), Point( 283914.039, 6774069.058, 81.19999999999709), Point( 283888.126, 6774087.896, 81.46300000000338), Point( 283861.718, 6774098.434, 81.778999999995), Point( 283839.086, 6774102.188, 81.85000000000582), Point( 283811.258, 6774102.078, 82.30199999999604))
    val geom2 = Seq(Point(283945.677, 6773839.793, 82.4890000000014), Point( 283952.215, 6773850.343, 82.58999999999651), Point( 283959.347, 6773861.01, 82.68300000000454), Point( 283981.966, 6773888.718, 82.84399999999732), Point( 284007.411, 6773918.688, 83.58800000000338), Point( 284021.548, 6773940.175, 83.22999999999593), Point( 284024.822, 6773956.109, 82.93799999999464))
    val geom3 = Seq(Point(283945.677, 6773839.793, 82.4890000000014), Point( 283941.368, 6773838.689, 82.38300000000163), Point( 283933.836, 6773837, 82.24000000000524), Point( 283925.983, 6773834.495, 82.18799999999464), Point( 283920.768, 6773828.944, 82.22900000000664), Point( 283921.384, 6773821.824, 82.28399999999965), Point( 283930.303, 6773813.467, 82.2100000000064), Point( 283938.025, 6773811.022, 82.2100000000064), Point( 283946.367, 6773813.728, 82.20799999999872), Point( 283950.47, 6773818.263, 82.29499999999825), Point( 283949.432, 6773827.206, 82.44700000000012), Point( 283947.648, 6773835.308, 82.46199999999953), Point( 283945.677, 6773839.793, 82.4890000000014))
    val discontinuity = Discontinuity.EndOfRoad

    runWithRollback {
      val idr1 = Sequences.nextRoadwayId
      val idr2 = Sequences.nextRoadwayId
      val idr3 = Sequences.nextRoadwayId
      val projectId = Sequences.nextViiteProjectId
      val rap = Project(projectId, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("2700-01-01"), "TestUser", DateTime.parse("2700-01-01"), DateTime.now(), "Some additional info", List.empty[ProjectReservedPart], Seq(), None)
      projectDAO.create(rap)

      val projectLink1 = toProjectLink(rap, RoadAddressChangeType.New)(RoadAddress(idr1, 0, RoadPart(12345, 1), AdministrativeClass.Unknown, Track.Combined, Discontinuity.Continuous, AddrMRange(0L, 0L), Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), 4224569L.toString, 0.0, 272.266, SideCode.Unknown, 0, (None, None), geom1, LinkGeomSource.NormalLinkInterface, 5, NoTermination, 0))
      val projectLink2 = toProjectLink(rap, RoadAddressChangeType.New)(RoadAddress(idr2, 0, RoadPart(12345, 1), AdministrativeClass.Unknown, Track.Combined, Discontinuity.Continuous, AddrMRange(0L, 0L), Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), 4224590L.toString, 0.0, 142.313, SideCode.Unknown, 0, (None, None), geom2, LinkGeomSource.NormalLinkInterface, 5, NoTermination, 0))
      val projectLink3 = toProjectLink(rap, RoadAddressChangeType.New)(RoadAddress(idr3, 0, RoadPart(12345, 1), AdministrativeClass.Unknown, Track.Combined, Discontinuity.Continuous, AddrMRange(0L, 0L), Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), 12531914L.toString, 0.0, 92.579, SideCode.Unknown, 0, (None, None), geom3, LinkGeomSource.NormalLinkInterface, 5, NoTermination, 0))

      projectService.addNewLinksToProject(Seq(projectLink3, projectLink2, projectLink1), projectId, "U", projectLink3.linkId, true, discontinuity)

      // Check projectLinks are created into database
      val links = projectLinkDAO.fetchProjectLinks(projectId)
      links.size should be(3)

      // Check the last link has given discontinuity set
      val (lastLink, others) = links.partition(_.linkId == projectLink1.linkId)
      lastLink should have size 1
      lastLink.head.discontinuity should be(discontinuity)
      others.forall(_.discontinuity == Discontinuity.Continuous) should be (true)

      // Check calculation succeeds with expected order and values
      val calculated = ProjectSectionCalculator.assignAddrMValues(links)
      val calculatedPl1 = calculated.find(_.linkId == 12531914.toString)
      val calculatedPl2 = calculated.find(_.linkId == 4224590.toString)
      val calculatedPl3 = calculated.find(_.linkId == 4224569.toString)

      calculatedPl1 should be ('defined)
      calculatedPl1.get.addrMRange      should be(AddrMRange(0,93))
      calculatedPl1.get.discontinuity   should be (Discontinuity.Continuous)
      calculatedPl2 should be ('defined)
      calculatedPl2.get.addrMRange      should be(AddrMRange(93,235))
      calculatedPl2.get.discontinuity   should be (Discontinuity.Continuous)
      calculatedPl3 should be ('defined)
      calculatedPl3.get.addrMRange      should be(AddrMRange(235,507))
      calculatedPl3.get.discontinuity   should be (discontinuity)
    }
  }

  test("Test projectService.addNewLinksToProject() " +
                 "When adding a nonexistent roadlinks to project having minor discontinuities " +
                 "Then when querying should return all fours links entered with discontinuity set to last link.") {
    runWithRollback {
      val (idr1,idr2,idr3,idr4) = (Sequences.nextRoadwayId, Sequences.nextRoadwayId, Sequences.nextRoadwayId, Sequences.nextRoadwayId)
      val id = Sequences.nextViiteProjectId
      val discontinuity = Discontinuity.Discontinuous
      val rap = Project(id, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("2700-01-01"), "TestUser", DateTime.parse("2700-01-01"), DateTime.now(), "Some additional info", List.empty[ProjectReservedPart], Seq(), None)
      val projectLink1 = toProjectLink(rap, RoadAddressChangeType.New)(RoadAddress(idr1, 1, RoadPart(12345, 1), AdministrativeClass.Private, Track.Combined, Discontinuity.Continuous, AddrMRange(0L, 0L), Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), "c502334c-b2df-43d4-b5fd-e6ec384e5c9c:1", 0.0, 101.590, SideCode.Unknown, 0, (None, None), Seq(Point(388102.086,7292352.045, 5.38 ), Point(388181.32 ,7292415.626, 9.949)), LinkGeomSource.NormalLinkInterface, 5, NoTermination, 0))
      val projectLink2 = toProjectLink(rap, RoadAddressChangeType.New)(RoadAddress(idr2, 1, RoadPart(12345, 1), AdministrativeClass.Private, Track.Combined, Discontinuity.Continuous, AddrMRange(0L, 0L), Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), "49bd02c4-697f-4e88-a7b9-59a33549eeec:1", 0.0, 108.620, SideCode.Unknown, 0, (None, None), Seq(Point(388196.49 ,7292427.8  ,10.256), Point(388281.207,7292495.78 , 5.848)), LinkGeomSource.NormalLinkInterface, 5, NoTermination, 0))
      val projectLink3 = toProjectLink(rap, RoadAddressChangeType.New)(RoadAddress(idr3, 1, RoadPart(12345, 1), AdministrativeClass.Private, Track.Combined, Discontinuity.Continuous, AddrMRange(0L, 0L), Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), "5f60a893-52d8-4de1-938b-2e4a04accc8c:1", 0.0, 147.232, SideCode.Unknown, 0, (None, None), Seq(Point(388281.207,7292495.78 , 5.848), Point(388396.039,7292587.926, 7.322)), LinkGeomSource.NormalLinkInterface, 5, NoTermination, 0))
      val projectLink4 = toProjectLink(rap, RoadAddressChangeType.New)(RoadAddress(idr4, 1, RoadPart(12345, 1), AdministrativeClass.Private, Track.Combined, Discontinuity.Continuous, AddrMRange(0L, 0L), Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), "a87b5257-72cd-4d48-9fff-54e81782cb64:1", 0.0, 105.259, SideCode.Unknown, 0, (None, None), Seq(Point(388412.663,7292601.266, 8.252), Point(388494.758,7292667.143,11.648)), LinkGeomSource.NormalLinkInterface, 5, NoTermination, 0))
      projectDAO.create(rap)
      mockForProject(id, Seq(projectLink1, projectLink2, projectLink3, projectLink4))
      projectService.addNewLinksToProject(Seq(projectLink1, projectLink2, projectLink3, projectLink4), id, "U", projectLink1.linkId, newTransaction = true, discontinuity)
      val links = projectLinkDAO.fetchProjectLinks(id)
      links.size should be(4)
      links.find(_.linkId == projectLink4.linkId).get.discontinuity should be(discontinuity)
    }
  }

  test("Test projectService.changeDirection() When issuing said command to a newly created project link in a newly created project Then check if the side code changed correctly.") {

    runWithRollback {
      val links = projectLinkDAO.fetchProjectLinks(7081807)
      links.nonEmpty should be(true)
      val mappedGeoms = StaticTestData.mappedGeoms(links.map(_.linkId))
      val geomToLinks: Seq[ProjectLink] = links.map { l =>
        val geom = mappedGeoms(l.linkId)
        l.copy(endMValue = GeometryUtils.geometryLength(geom), geometry = geom, geometryLength = GeometryUtils.geometryLength(geom))
      }
      val adjusted = ProjectSectionCalculator.assignAddrMValues(geomToLinks)
      val projectLinkIds = geomToLinks.map(_.id)
      projectLinkDAO.create(adjusted.filter(pl => !projectLinkIds.contains(pl.id)))
      projectLinkDAO.updateProjectLinks(adjusted, "-", Seq())

      reset(mockRoadLinkService)
      when(mockRoadLinkService.getRoadLinksHistoryFromVVH(any[Set[String]])).thenReturn(Seq())
      when(mockRoadLinkService.getRoadLinksByLinkIds(any[Set[String]])).thenAnswer(
        toMockAnswer(adjusted.map(toRoadLink))
      )
      val beforeChange = projectLinkDAO.fetchProjectLinks(7081807)
      projectService.changeDirection(7081807, RoadPart(77997, 1), links.map(l => LinkToRevert(l.id, l.linkId, l.status.value, l.geometry)), ProjectCoordinates(0, 0, 0), "testuser")
      val changedLinks = projectLinkDAO.fetchProjectLinks(7081807)

      val maxBefore = if (beforeChange.nonEmpty) beforeChange.maxBy(_.addrMRange.end).addrMRange.end else 0
      val maxAfter  = if (changedLinks.nonEmpty) changedLinks.maxBy(_.addrMRange.end).addrMRange.end else 0
      maxBefore should be(maxAfter)
      val combinedLeft = changedLinks.filter(_.track != Track.RightSide).sortBy(_.addrMRange.start)
      val combinedRight = changedLinks.filter(_.track != Track.LeftSide).sortBy(_.addrMRange.start)


      combinedRight.foldLeft(Seq.empty[ProjectLink]) { case (seq, plink) =>
        if (seq.nonEmpty)
          seq.last.addrMRange.end should be(plink.addrMRange.start)
        seq ++ Seq(plink)
      }

      combinedLeft.foldLeft(Seq.empty[ProjectLink]) { case (seq, plink) =>
        if (seq.nonEmpty)
          seq.last.addrMRange.end should be(plink.addrMRange.start)
        seq ++ Seq(plink)
      }
      // Test that for every link there should be the address before it or after it (unless it's the first or last link)
      changedLinks.foreach(l =>
        (l == changedLinks.head || changedLinks.exists(c => c.addrMRange.continuesTo(l.addrMRange) &&
          c.track == l.track || (c.track.value * l.track.value == 0))) && (l == changedLinks.last ||
          changedLinks.exists(c => c.addrMRange.continuesFrom(l.addrMRange) &&
            c.track == l.track || (c.track.value * l.track.value == 0))) should be(true)
      )
      adjusted.filterNot(_.isSplit).foreach { l =>
        GeometryUtils.geometryEndpoints(mappedGeoms(l.linkId)) should be(GeometryUtils.geometryEndpoints(l.geometry))
      }

      val linksFirst = adjusted.minBy(_.id)
      val linksLast = adjusted.maxBy(_.id)
      val changedLinksFirst = changedLinks.maxBy(_.id)
      val changedLinksLast = changedLinks.minBy(_.id)
      val paired = (adjusted ++ changedLinks).groupBy(_.id).mapValues(v => (v.head, v.last)).values
      paired.foreach {
        case (oldLink, newLink) =>
          oldLink.addrMRange.start should be((linksLast.addrMRange.end - newLink.addrMRange.end) +- 1)
          oldLink.addrMRange.end should be((linksLast.addrMRange.end - newLink.addrMRange.start) +- 1)
          val sideCodeChangeCorrect = (oldLink.sideCode, newLink.sideCode) match {
            case (SideCode.AgainstDigitizing, SideCode.TowardsDigitizing) => true
            case (SideCode.TowardsDigitizing, SideCode.AgainstDigitizing) => true
            case _ => false
          }
          sideCodeChangeCorrect should be (true)
      }
      linksFirst.id should be(changedLinksLast.id)
      linksLast.id should be(changedLinksFirst.id)
      linksLast.geometryLength should be(changedLinks.maxBy(_.id).geometryLength +- .1)
      linksLast.endMValue should be(changedLinks.maxBy(_.id).endMValue +- .1)
      linksFirst.endMValue should be(changedLinksLast.endMValue +- .1)
      linksLast.endMValue should be(changedLinksFirst.endMValue +- .1)
    }
  }

  test("Test projectService.updateProjectLinks() When issuing a numbering change on transfer operation with same road number Then check if all the roads have proper m address values defined.") {
    runWithRollback {
      val address1 = roadwayAddressMapper.getRoadAddressesByLinearLocation(linearLocationDAO.fetchByRoadways(roadwayDAO.fetchAllBySection(RoadPart( 11, 8)).map(_.roadwayNumber).toSet)).sortBy(_.addrMRange.start)
      val address2 = roadwayAddressMapper.getRoadAddressesByLinearLocation(linearLocationDAO.fetchByRoadways(roadwayDAO.fetchAllBySection(RoadPart(259, 1)).map(_.roadwayNumber).toSet)).sortBy(_.addrMRange.start)
      val reservedRoadPart1 = ProjectReservedPart(address1.head.id, address1.head.roadPart, Some(address1.last.addrMRange.end), Some(address1.head.discontinuity), Some(address1.head.ely), newLength = None, newDiscontinuity = None, newEly = None)
      val reservedRoadPart2 = ProjectReservedPart(address2.head.id, address2.head.roadPart, Some(address2.last.addrMRange.end), Some(address2.head.discontinuity), Some(address2.head.ely), newLength = None, newDiscontinuity = None, newEly = None)
      val rap = Project(0, ProjectState.apply(1), "TestProject", "TestUser", DateTime.now(), "TestUser", DateTime.now(), DateTime.now(), "Some additional info", Seq(reservedRoadPart1) ++ Seq(reservedRoadPart2), Seq(), None, None)

      val links = (address1 ++ address2).map(address => {
        toProjectLink(rap, RoadAddressChangeType.NotHandled)(address)
      })
      when(mockRoadLinkService.getRoadLinksHistoryFromVVH(any[Set[String]])).thenReturn(Seq())
      when(mockRoadLinkService.getRoadLinksByLinkIds(any[Set[String]])).thenReturn(links.map(toRoadLink))
      val project = projectService.createRoadLinkProject(rap)

      val transferLink = address2.minBy(_.addrMRange.start)
      projectService.updateProjectLinks(project.id, Set(), address1.map(_.linkId),   RoadAddressChangeType.Unchanged, "TestUser", RoadPart( 0, 0), 0, Option.empty[Int]) should be(None)
      projectService.updateProjectLinks(project.id, Set(), Seq(transferLink.linkId), RoadAddressChangeType.Transfer,  "TestUser", RoadPart(11, 8), 0, Option.empty[Int]) should be(None)

      val updatedLinks = projectLinkDAO.fetchProjectLinks(project.id)
      val linksRoad259 = updatedLinks.filter(_.roadPart.roadNumber == 259).sortBy(_.addrMRange.start)

      val mappedLinks = links.groupBy(_.linkId)
      val mapping = linksRoad259.map(tl => tl -> mappedLinks(tl.linkId)).filterNot(_._2.size > 1)
      mapping.foreach { case (link, l) =>
        val before = l.head
        before.addrMRange.length should be(link.addrMRange.length +- 1)
      }

      //sum of all link values should persist
      updatedLinks.groupBy(_.roadPart.roadNumber).mapValues(_.map(_.addrMRange.end).max).values.sum should be
      address1.map(_.addrMRange.end).max + address2.map(_.addrMRange.end).max
    }
  }

  test("Test projectService.updateProjectLinks() When issuing a numbering change on transfer operation with different road number Then check that the amount of roads for one road number goes down and the other goes up.") {
    runWithRollback {

      val address1 = roadwayAddressMapper.getRoadAddressesByLinearLocation(linearLocationDAO.fetchByRoadways(roadwayDAO.fetchAllBySection(RoadPart( 11, 8)).map(_.roadwayNumber).toSet)).sortBy(_.addrMRange.start)
      val address2 = roadwayAddressMapper.getRoadAddressesByLinearLocation(linearLocationDAO.fetchByRoadways(roadwayDAO.fetchAllBySection(RoadPart(259, 1)).map(_.roadwayNumber).toSet)).sortBy(_.addrMRange.start)
      val reservedRoadPart1 = ProjectReservedPart(address1.head.id, address1.head.roadPart, Some(address1.last.addrMRange.end), Some(address1.head.discontinuity), Some(address1.head.ely), newLength = None, newDiscontinuity = None, newEly = None)
      val reservedRoadPart2 = ProjectReservedPart(address2.head.id, address2.head.roadPart, Some(address2.last.addrMRange.end), Some(address2.head.discontinuity), Some(address2.head.ely), newLength = None, newDiscontinuity = None, newEly = None)
      val rap = Project(0, ProjectState.apply(1), "TestProject", "TestUser", DateTime.now(), "TestUser", DateTime.now(), DateTime.now(), "Some additional info", Seq(reservedRoadPart1) ++ Seq(reservedRoadPart2), Seq(), None, None)

      val links = (address1 ++ address2).map(address => {
        toProjectLink(rap, RoadAddressChangeType.NotHandled)(address)
      })
      when(mockRoadLinkService.getRoadLinksHistoryFromVVH(any[Set[String]])).thenReturn(Seq())
      when(mockRoadLinkService.getRoadLinksByLinkIds(any[Set[String]])).thenReturn(links.map(toRoadLink))
      val project = projectService.createRoadLinkProject(rap)

      val transferLink = address2.minBy(_.addrMRange.start)
      projectService.updateProjectLinks(project.id, Set(), address1.map(_.linkId),   RoadAddressChangeType.Unchanged, "TestUser", RoadPart( 0, 0), 0, Option.empty[Int]) should be(None)
      projectService.updateProjectLinks(project.id, Set(), Seq(transferLink.linkId), RoadAddressChangeType.Transfer,  "TestUser", RoadPart(11, 8), 0, Option.empty[Int]) should be(None)

      val updatedLinks = projectLinkDAO.fetchProjectLinks(project.id)
      val linksRoad11  = updatedLinks.filter(_.roadPart.roadNumber == 11).sortBy(_.addrMRange.start)
      val linksRoad259 = updatedLinks.filter(_.roadPart.roadNumber == 259).sortBy(_.addrMRange.start)

      linksRoad11.size should be(address1.size + 1)
      linksRoad259.size should be(address2.size - 1)

    }
  }

  test("Test projectService.updateProjectLinks() When reserving a road part, try to renumber it to the same number Then it should produce an error.") {
    runWithRollback {
      reset(mockRoadLinkService)
      val addresses = roadwayAddressMapper.getRoadAddressesByLinearLocation(linearLocationDAO.fetchByRoadways(roadwayDAO.fetchAllBySection(RoadPart(5, 201)).map(_.roadwayNumber).toSet)).sortBy(_.addrMRange.start)
      val reservedRoadPart = ProjectReservedPart(addresses.head.id, addresses.head.roadPart, Some(addresses.last.addrMRange.end), Some(addresses.head.discontinuity), Some(8L), newLength = None, newDiscontinuity = None, newEly = None)
      val rap = Project(0, ProjectState.apply(1), "TestProject", "TestUser", DateTime.now(), "TestUser", DateTime.now(), DateTime.now(), "Some additional info", Seq(), Seq(), None, None)
      val project = projectService.createRoadLinkProject(rap)
      mockForProject(project.id, addresses.map(toProjectLink(project)))
      projectService.saveProject(project.copy(reservedParts = Seq(reservedRoadPart)))
      val savedProject = projectService.getSingleProjectById(project.id).get
      savedProject.reservedParts should have size 1
      reset(mockRoadLinkService)

      projectService.updateProjectLinks(project.id, Set(), addresses.map(_.linkId), RoadAddressChangeType.Renumeration, "TestUser", RoadPart(5, 201), 1, Option.empty[Int]) should be(Some(ErrorRenumberingToOriginalNumber))
    }
  }

  test("Test projectService.createProjectLinks() When trying to create project links onto a roundabout Then check if the created project links are actually a roundabout.") {
    runWithRollback {
      val rap = Project(0, ProjectState.apply(1), "TestProject", "TestUser", DateTime.now(), "TestUser",
        DateTime.now(), DateTime.now(), "Some additional info", Seq(), Seq(), None, None)
      val project = projectService.createRoadLinkProject(rap)
      val roadLinks = Seq(RoadLink(121L.toString, Seq(Point(1.0, 0.0), Point(10.0, 0.0)), 10.0, AdministrativeClass.State, TrafficDirection.TowardsDigitizing, None, None, LifecycleStatus.InUse, LinkGeomSource.NormalLinkInterface, 456, ""),
        RoadLink(122L.toString, Seq(Point(10.0, 0.0), Point(5.0, 8.0)), 9.434, AdministrativeClass.State, TrafficDirection.TowardsDigitizing, None, None, LifecycleStatus.InUse, LinkGeomSource.NormalLinkInterface, 456, ""),
        RoadLink(123L.toString, Seq(Point(1.0, 0.0), Point(5.0, 8.0)), 9.434, AdministrativeClass.State, TrafficDirection.AgainstDigitizing, None, None, LifecycleStatus.InUse, LinkGeomSource.NormalLinkInterface, 456, ""))
      when(mockRoadLinkService.getRoadLinksHistoryFromVVH(any[Set[String]])).thenReturn(Seq())
      when(mockRoadLinkService.getRoadLinksByLinkIds(any[Set[String]])).thenAnswer(
        toMockAnswer(roadLinks))
      ProjectLinkNameDAO.create(project.id, 39999, "road name")
      val resp = createProjectLinks(Seq(123L.toString, 121L.toString, 122L.toString), project.id, RoadPart(39999, 12), 0, 1, 1, 1, 8, "user", "road name")
      resp.get("success") should be(Some(true))
      val links =
        projectLinkDAO.fetchProjectLinks(project.id).groupBy(_.linkId).map {
          pl => pl._1 -> ProjectAddressLinkBuilder.build(pl._2.head)
        }.values.toSeq
      val start = links.find(_.linkId == 123L.toString)
      start.isEmpty should be(false)
      start.get.sideCode should be(SideCode.AgainstDigitizing)
      start.get.addrMRange should be(AddrMRange(0L,9L))
      val end = links.find(_.linkId == 122L.toString)
      end.isEmpty should be(false)
      end.get.sideCode should be(SideCode.TowardsDigitizing)
      end.get.addrMRange should be(AddrMRange(19L,28L))
      end.get.discontinuity should be(Discontinuity.EndOfRoad.value)
      links.count(_.discontinuity != Discontinuity.Continuous.value) should be(1)

      // Test if roadwaynumbers are assigned.
      val projectLinksFromDB = projectLinkDAO.fetchProjectLinks(project.id)
      val afterAssign: Seq[ProjectLink] = ProjectSectionCalculator.assignAddrMValues(projectLinksFromDB)
      afterAssign.forall(pl => pl.roadwayNumber != 0 && pl.roadwayNumber != NewIdValue)

      // Test if RoadAddressCPs are assigned.
      afterAssign.minBy(_.addrMRange.start).startCalibrationPointType should be(CalibrationPointType.RoadAddressCP)
      afterAssign.maxBy(_.addrMRange.end).endCalibrationPointType     should be(CalibrationPointType.RoadAddressCP)

      // Test if user defined end address is assigned.
      val userAddress = 1000
      val calibrationPoint = UserDefinedCalibrationPoint(NewIdValue, afterAssign.maxBy(_.addrMRange.end).id, project.id, userAddress - afterAssign.maxBy(_.addrMRange.end).startMValue, userAddress)
      val afterAssignWithUserAddress  = ProjectSectionCalculator.assignAddrMValues(projectLinksFromDB, Seq(calibrationPoint))
      afterAssignWithUserAddress.maxBy(_.addrMRange.end).addrMRange.end should be(userAddress)
    }
  }

  test("Test projectService.createProjectLinks() When creating a new road with track 2, then updating it to track 0 Then the whole process should not crash.") {
    runWithRollback {
      val id = Sequences.nextViiteProjectId
      val rap = Project(id, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("2018-01-01"),
        "TestUser", DateTime.parse("2100-01-01"), DateTime.now(), "Some additional info", List.empty, Seq(), None)
      projectDAO.create(rap)

      val points6552 = "[{\"x\":537869.292,\"y\":6997722.466,\"z\":110.39800000000105}," +
        "{\"x\":538290.056,\"y\":6998265.169,\"z\":85.4429999999993}]"
      val geom = JsonMethods.parse(points6552).extract[List[Point]]


      val addProjectAddressLink552 = ProjectAddressLink(NewIdValue, 6552.toString, geom, GeometryUtils.geometryLength(geom), AdministrativeClass.State, LifecycleStatus.InUse, LinkGeomSource.NormalLinkInterface, AdministrativeClass.State, None, 749, "Siilinjärvi", Some(DateTime.now().toString()), None, RoadPart(19999, 1), 2L, 8L, 5L, AddrMRange(0L, 0L), 0.0, GeometryUtils.geometryLength(geom), SideCode.TowardsDigitizing, None, None, RoadAddressChangeType.New, 0, 0, sourceId = "", originalAddrMRange = Some(AddrMRange(0L, 0L)))
      val addresses = Seq(addProjectAddressLink552)
      mockForProject(id, addresses)

      // Adding left track alone
      projectService.addNewLinksToProject(addresses.map(backToProjectLink(rap)), id, "U", addresses.head.linkId, true, Discontinuity.Continuous) should be(None)

      val links = projectLinkDAO.fetchProjectLinks(id)
      projectReservedPartDAO.fetchReservedRoadParts(id) should have size 1

      // Update link to become track 0
      projectService.updateProjectLinks(id, Set(), links.map(_.linkId), RoadAddressChangeType.New, "test", RoadPart(19999, 1), 0, None, AdministrativeClass.State.value, Discontinuity.EndOfRoad.value, Some(8))
      val linksAfterUpdate = projectLinkDAO.fetchProjectLinks(id)
      val firstLink = linksAfterUpdate.head
      firstLink.roadPart should be(RoadPart(19999,1))
      firstLink.track.value should be(0)
    }
  }
}

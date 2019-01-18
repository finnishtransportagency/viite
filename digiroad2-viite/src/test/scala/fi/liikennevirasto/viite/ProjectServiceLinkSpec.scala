package fi.liikennevirasto.viite

import java.util.Properties

import fi.liikennevirasto.digiroad2._
import fi.liikennevirasto.digiroad2.asset.ConstructionType.InUse
import fi.liikennevirasto.digiroad2.asset.LinkGeomSource.NormalLinkInterface
import fi.liikennevirasto.digiroad2.asset.SideCode.{AgainstDigitizing, TowardsDigitizing}
import fi.liikennevirasto.digiroad2.asset._
import fi.liikennevirasto.digiroad2.dao.{Queries, Sequences}
import fi.liikennevirasto.digiroad2.linearasset.{PolyLine, RoadLink}
import fi.liikennevirasto.digiroad2.oracle.OracleDatabase
import fi.liikennevirasto.digiroad2.service.RoadLinkService
import fi.liikennevirasto.digiroad2.util.Track
import fi.liikennevirasto.digiroad2.util.Track.{Combined, LeftSide, RightSide}
import fi.liikennevirasto.viite.dao.AddressChangeType.{Termination, Transfer}
import fi.liikennevirasto.viite.dao.CalibrationPointSource.{ProjectLinkSource, RoadAddressSource}
import fi.liikennevirasto.viite.dao.Discontinuity.Discontinuous
import fi.liikennevirasto.viite.dao.FloatingReason.NoFloating
import fi.liikennevirasto.viite.dao.TerminationCode.NoTermination
import fi.liikennevirasto.viite.dao._
import fi.liikennevirasto.viite.model.{Anomaly, ProjectAddressLink, RoadAddressLinkLike}
import fi.liikennevirasto.viite.process.{ProjectSectionCalculator, RoadwayAddressMapper}
import fi.liikennevirasto.viite.util.{SplitOptions, StaticTestData, _}
import org.joda.time.DateTime
import org.mockito.ArgumentMatchers.any
import org.scalatest.mockito.MockitoSugar
import org.mockito.Mockito.{reset, when}
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{BeforeAndAfter, FunSuite, Matchers}
import slick.driver.JdbcDriver.backend.Database
import slick.driver.JdbcDriver.backend.Database.dynamicSession
import slick.jdbc.StaticQuery.interpolation

import scala.util.parsing.json.JSON

class ProjectServiceLinkSpec extends FunSuite with Matchers with BeforeAndAfter {
  val properties: Properties = {
    val props = new Properties()
    props.load(getClass.getResourceAsStream("/digiroad2.properties"))
    props
  }
  val mockProjectService = MockitoSugar.mock[ProjectService]
  val mockRoadLinkService = MockitoSugar.mock[RoadLinkService]
  val mockRoadAddressService = MockitoSugar.mock[RoadAddressService]
  val mockRoadwayAddressMapper = MockitoSugar.mock[RoadwayAddressMapper]
  val mockEventBus = MockitoSugar.mock[DigiroadEventBus]
  val projectDAO = new ProjectDAO
  val projectLinkDAO = new ProjectLinkDAO
  val roadwayDAO = new RoadwayDAO
  val roadNetworkDAO = new RoadNetworkDAO
  val linearLocationDAO = new LinearLocationDAO
  val projectReservedPartDAO = new ProjectReservedPartDAO
  val unaddressedRoadLinkDAO = new UnaddressedRoadLinkDAO
  val roadwayAddressMapper = new RoadwayAddressMapper(roadwayDAO, linearLocationDAO)

  val roadAddressService = new RoadAddressService(mockRoadLinkService, new RoadwayDAO, new LinearLocationDAO, new RoadNetworkDAO, new UnaddressedRoadLinkDAO, roadwayAddressMapper, mockEventBus) {
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

  private def projectLink(startAddrM: Long, endAddrM: Long, track: Track, projectId: Long, status: LinkStatus = LinkStatus.NotHandled,
                          roadNumber: Long = 19999L, roadPartNumber: Long = 1L, discontinuity: Discontinuity = Discontinuity.Continuous, ely: Long = 8L, roadwayId: Long = 0L, linearLocationId: Long = 0L) = {
    ProjectLink(NewRoadway, roadNumber, roadPartNumber, track, discontinuity, startAddrM, endAddrM, startAddrM, endAddrM, None, None,
      Some("User"), startAddrM, 0.0, (endAddrM - startAddrM).toDouble, SideCode.TowardsDigitizing, (None, None),
      floating = NoFloating, Seq(Point(0.0, startAddrM), Point(0.0, endAddrM)), projectId, status, RoadType.PublicRoad,
      LinkGeomSource.NormalLinkInterface, (endAddrM - startAddrM).toDouble, roadwayId, linearLocationId, ely, reversed = false, None, 0L)
  }

  private def setUpProjectWithLinks(linkStatus: LinkStatus, addrM: Seq[Long], changeTrack: Boolean = false, roadNumber: Long = 19999L,
                                    roadPartNumber: Long = 1L, discontinuity: Discontinuity = Discontinuity.Continuous, ely: Long = 8L, roadwayId: Long = 0L) = {
    val id = Sequences.nextViitePrimaryKeySeqValue

    def withTrack(t: Track): Seq[ProjectLink] = {
      addrM.init.zip(addrM.tail).map { case (st, en) =>
        projectLink(st, en, t, id, linkStatus, roadNumber, roadPartNumber, discontinuity, ely, roadwayId)
      }
    }

    val project = Project(id, ProjectState.Incomplete, "f", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
      "", Seq(), None, Some(8), None)
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


  private def createProjectLinks(linkIds: Seq[Long], projectId: Long, roadNumber: Long, roadPartNumber: Long, track: Int,
                                 discontinuity: Int, roadType: Int, roadLinkSource: Int,
                                 roadEly: Long, user: String, roadName: String): Map[String, Any] = {
    projectService.createProjectLinks(linkIds, projectId, roadNumber, roadPartNumber, Track.apply(track), Discontinuity.apply(discontinuity),
      RoadType.apply(roadType), LinkGeomSource.apply(roadLinkSource), roadEly, user, roadName)
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

  private def addressToRoadLink(ral: RoadAddress): RoadLink = {
    val geomLength = GeometryUtils.geometryLength(ral.geometry)
    val adminClass = ral.roadType match {
      case RoadType.FerryRoad => AdministrativeClass.apply(1)
      case RoadType.PublicRoad => AdministrativeClass.apply(1)
      case RoadType.MunicipalityStreetRoad => AdministrativeClass.apply(2)
      case RoadType.PrivateRoadType => AdministrativeClass.apply(3)
      case _ => AdministrativeClass.apply(99)
    }
    RoadLink(ral.linkId, ral.geometry, geomLength, adminClass, 1,
      extractTrafficDirection(ral.sideCode, ral.track), LinkType.apply(99), Option(ral.startDate.toString), ral.createdBy, Map(
        "MUNICIPALITYCODE" -> BigInt(749), "VERTICALLEVEL" -> BigInt(1), "SURFACETYPE" -> BigInt(1),
        "ROADNUMBER" -> BigInt(ral.roadNumber), "ROADPARTNUMBER" -> BigInt(ral.roadPartNumber)),
      ConstructionType.InUse, ral.linkGeomSource)
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
    val roadLink = RoadLink(Sequences.nextViitePrimaryKeySeqValue, Seq(Point(535602.222, 6982200.25, 89.9999), Point(535605.272, 6982204.22, 85.90899999999965))
      , 540.3960283713503, State, 99, TrafficDirection.AgainstDigitizing, UnknownLinkType, Some("25.06.2015 03:00:00"), Some("vvh_modified"), Map("MUNICIPALITYCODE" -> BigInt.apply(749)),
      InUse, NormalLinkInterface)
    val (projectLinks, palinks) = l.partition(_.isInstanceOf[ProjectLink])
    val dbLinks = projectLinkDAO.fetchProjectLinks(id)
    when(mockRoadLinkService.getRoadLinksHistoryFromVVH(any[Set[Long]])).thenReturn(Seq())
    when(mockRoadLinkService.getRoadLinksByLinkIdsFromVVH(any[Set[Long]], any[Boolean])).thenAnswer(
      toMockAnswer(dbLinks ++ projectLinks.asInstanceOf[Seq[ProjectLink]].filterNot(l => dbLinks.map(_.linkId).contains(l.linkId)),
        roadLink, palinks.asInstanceOf[Seq[ProjectAddressLink]].map(toRoadLink)
      ))
  }

  private def mockRoadLinksWithStaticTestData(linkIds: Set[Long], linkSource: LinkGeomSource = LinkGeomSource.NormalLinkInterface): Seq[RoadLink] = {
    val roadLinkTemplate =
      RoadLink(-1, Seq(), 540.3960283713503, State, 99, TrafficDirection.BothDirections, UnknownLinkType, Some("25.06.2015 03:00:00"),
        Some("vvh_modified"), Map("MUNICIPALITYCODE" -> BigInt.apply(749)), InUse, NormalLinkInterface)
    StaticTestData.roadLinkMocker(roadLinkTemplate)(linkIds)
  }

  test("Test projectService.updateProjectLinks() When using a recently created project Then project should become publishable after the update.") {
    var count = 0
    runWithRollback {
      val countCurrentProjects = projectService.getAllProjects
      val id = 0
      val addresses = List(ProjectReservedPart(Sequences.nextViitePrimaryKeySeqValue: Long, 5: Long, 206: Long, Some(5L), Some(Discontinuity.apply("jatkuva")), Some(8L), newLength = None, newDiscontinuity = None, newEly = None))
      val roadAddressProject = Project(id, ProjectState.apply(1), "TestProject", "TestUser", DateTime.now(), "TestUser", DateTime.parse("2017-01-01"), DateTime.now(), "Some additional info", Seq(), None)
      val saved = projectService.createRoadLinkProject(roadAddressProject)
      mockForProject(saved.id, roadwayAddressMapper.getRoadAddressesByLinearLocation(linearLocationDAO.fetchByRoadways(roadwayDAO.fetchAllBySection(5, 206).map(_.roadwayNumber).toSet)).map(toProjectLink(saved)))
      when(mockRoadLinkService.getSuravageRoadLinksByLinkIdsFromVVH(any[Set[Long]])).thenReturn(Seq())
      projectService.saveProject(saved.copy(reservedParts = addresses))
      val countAfterInsertProjects = projectService.getAllProjects
      count = countCurrentProjects.size + 1
      countAfterInsertProjects.size should be(count)
      projectService.isProjectPublishable(saved.id) should be(false)
      val linkIds = projectLinkDAO.fetchProjectLinks(saved.id).map(_.linkId).toSet
      projectService.updateProjectLinks(saved.id, Set(), linkIds.toSeq, LinkStatus.Terminated, "-", 0, 0, 0, Option.empty[Int])
      projectService.isProjectPublishable(saved.id) should be(true)
    }
    runWithRollback {
      projectService.getAllProjects
    } should have size (count - 1)
  }

  test("Test projectService.updateProjectLinks() When changing the project links numbering Then project should become publishable after the update and it's links should reflect the number change.") {
    var count = 0

    val roadLinks = Seq(
      RoadLink(5170939L, Seq(Point(535605.272, 6982204.22, 85.90899999999965))
        , 540.3960283713503, State, 99, TrafficDirection.AgainstDigitizing, UnknownLinkType, Some("25.06.2015 03:00:00"), Some("vvh_modified"), Map("MUNICIPALITYCODE" -> BigInt.apply(749)),
        InUse, NormalLinkInterface))
    runWithRollback {
      val countCurrentProjects = projectService.getAllProjects
      val id = 0
      val addresses = List(
        ProjectReservedPart(Sequences.nextViitePrimaryKeySeqValue: Long, 5: Long, 207: Long, Some(5L), Some(Discontinuity.apply("jatkuva")), Some(8L), newLength = None, newDiscontinuity = None, newEly = None))
      val roadAddressProject = Project(id, ProjectState.apply(1), "TestProject", "TestUser", DateTime.now(),
        "TestUser", DateTime.parse("1962-11-02"), DateTime.now(), "Some additional info", Seq(), None)
      val saved = projectService.createRoadLinkProject(roadAddressProject)
      mockForProject(saved.id, roadwayAddressMapper.getRoadAddressesByLinearLocation(linearLocationDAO.fetchByRoadways(roadwayDAO.fetchAllBySection(5, 207).map(_.roadwayNumber).toSet)).map(toProjectLink(saved)))
      when(mockRoadLinkService.getSuravageRoadLinksByLinkIdsFromVVH(any[Set[Long]])).thenReturn(Seq())
      projectService.saveProject(saved.copy(reservedParts = addresses))
      val countAfterInsertProjects = projectService.getAllProjects
      count = countCurrentProjects.size + 1
      countAfterInsertProjects.size should be(count)
      val projectLinks = projectLinkDAO.fetchProjectLinks(saved.id)
      projectLinks.isEmpty should be(false)
      val partitioned = projectLinks.partition(_.roadPartNumber == 207)

      projectService.isProjectPublishable(saved.id) should be(false)
      val linkIds = projectLinkDAO.fetchProjectLinks(saved.id).map(_.linkId).toSet
      projectService.updateProjectLinks(saved.id, Set(), linkIds.toSeq, LinkStatus.Numbering, "-", 99999, 1, 0, Option.empty[Int])
      val afterNumberingLinks = projectLinkDAO.fetchProjectLinks(saved.id)
      afterNumberingLinks.foreach(l => (l.roadNumber == 99999 && l.roadPartNumber == 1) should be(true))
    }

  }

  private def checkSplit(projectLinks: Seq[ProjectLink], splitMValue: Double, startAddrMValue: Long,
                         splitAddrMValue: Long, endAddrMValue: Long, survageEndMValue: Double) = {
    projectLinks.count(x => x.connectedLinkId.isDefined) should be(3)
    val unchangedLink = projectLinks.filter(x => x.status == LinkStatus.UnChanged || x.status == LinkStatus.Transfer).head
    val newLink = projectLinks.filter(x => x.status == LinkStatus.New).head
    val templateLink = projectLinks.filter(x => x.linkGeomSource != LinkGeomSource.SuravageLinkInterface).head
    newLink.connectedLinkId should be(Some(templateLink.linkId))
    unchangedLink.connectedLinkId should be(Some(templateLink.linkId))
    templateLink.connectedLinkId should be(Some(newLink.linkId))
    newLink.startMValue should be(splitMValue)
    newLink.startAddrMValue should be(splitAddrMValue)
    newLink.endAddrMValue should be > (splitAddrMValue)
    newLink.endMValue should be(survageEndMValue)
    unchangedLink.startMValue should be(0)
    unchangedLink.startAddrMValue should be(startAddrMValue)
    unchangedLink.endAddrMValue should be(splitAddrMValue)
    unchangedLink.endMValue should be(splitMValue)
    templateLink.status should be(LinkStatus.Terminated)
    templateLink.startAddrMValue should be(newLink.startAddrMValue)
    templateLink.roadwayId should be(newLink.roadwayId)
  }

  test("Test projectService.addNewLinksToProject() When adding a nonexistent roadlink to project Then when querying for it it should return that one project link was entered.") {
    runWithRollback {
      val idr = roadwayDAO.getNextRoadwayId
      val id = Sequences.nextViitePrimaryKeySeqValue
      val rap = Project(id, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("2700-01-01"), "TestUser", DateTime.parse("2700-01-01"), DateTime.now(), "Some additional info", List.empty[ProjectReservedPart], None)
      val projectLink = toProjectLink(rap, LinkStatus.New)(RoadAddress(idr, 1, 12345, 1, RoadType.PrivateRoadType, Track.Combined, Discontinuous, 0L, 10L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), 1234522L, 0.0, 9.8, SideCode.TowardsDigitizing, 0, (None, None), NoFloating,
        Seq(Point(0.0, 0.0), Point(0.0, 9.8)), LinkGeomSource.NormalLinkInterface, 5, NoTermination, 0))
      projectDAO.create(rap)
      mockForProject(id, Seq(projectLink))
      projectService.addNewLinksToProject(Seq(projectLink), id, "U", projectLink.linkId)
      val links = projectLinkDAO.fetchProjectLinks(id)
      links.size should be(1)
    }
  }

  test("Test projectService.addNewLinksToProject() When adding two consecutive roadlinks to project road number & road part Then check the correct insertion of the roadlinks.") {
    val roadLink = RoadLink(5175306, Seq(Point(535602.222, 6982200.25, 89.9999), Point(535605.272, 6982204.22, 85.90899999999965))
      , 540.3960283713503, State, 99, TrafficDirection.AgainstDigitizing, UnknownLinkType, Some("25.06.2015 03:00:00"), Some("vvh_modified"), Map("MUNICIPALITYCODE" -> BigInt.apply(749)),
      InUse, NormalLinkInterface)
    when(mockRoadLinkService.getRoadLinksHistoryFromVVH(any[Set[Long]])).thenReturn(Seq())
    when(mockRoadLinkService.getRoadLinksByLinkIdsFromVVH(Set(5175306L))).thenReturn(Seq(roadLink))
    runWithRollback {

      val idr1 = roadwayDAO.getNextRoadwayId
      val idr2 = roadwayDAO.getNextRoadwayId
      val id = Sequences.nextViitePrimaryKeySeqValue
      val rap = Project(id, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("2700-01-01"), "TestUser", DateTime.parse("2700-01-01"), DateTime.now(), "Some additional info", List.empty[ProjectReservedPart], None)
      projectDAO.create(rap)

      val projectLink1 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idr1, 0, 12345, 1, RoadType.Unknown, Track.Combined, Discontinuous, 0L, 10L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), 5175306L, 0.0, 9.8, SideCode.TowardsDigitizing, 0, (None, None), NoFloating,
        Seq(Point(535602.222, 6982200.25, 89.9999), Point(535605.272, 6982204.22, 85.90899999999965)), LinkGeomSource.NormalLinkInterface, 5, NoTermination, 0))

      val projectLink2 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idr2, 0, 12345, 1, RoadType.Unknown, Track.Combined, Discontinuous, 0L, 10L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), 1610976L, 0.0, 5.8, SideCode.TowardsDigitizing, 0, (None, None), NoFloating,
        Seq(Point(535605.272, 6982204.22, 85.90899999999965), Point(535608.555, 6982204.33, 86.90)), LinkGeomSource.NormalLinkInterface, 5, NoTermination, 0))


      val calibrationPoints1 = CalibrationPointsUtils.toCalibrationPoints(projectLink1.calibrationPoints)
      val calibrationPoints2 = CalibrationPointsUtils.toCalibrationPoints(projectLink2.calibrationPoints)

      val p1 = ProjectAddressLink(idr1, projectLink1.linkId, projectLink1.geometry,
        1, AdministrativeClass.apply(1), LinkType.apply(1), ConstructionType.apply(1), projectLink1.linkGeomSource, RoadType.PublicUnderConstructionRoad, Some(""), None, 111, Some(""), Some("vvh_modified"),
        Map(), projectLink1.roadNumber, projectLink1.roadPartNumber, 2, -1, projectLink1.discontinuity.value,
        projectLink1.startAddrMValue, projectLink1.endAddrMValue, projectLink1.startMValue, projectLink1.endMValue,
        projectLink1.sideCode,
        calibrationPoints1._1,
        calibrationPoints1._2, Anomaly.None, projectLink1.status, 0, 0)

      val p2 = ProjectAddressLink(idr2, projectLink2.linkId, projectLink2.geometry,
        1, AdministrativeClass.apply(1), LinkType.apply(1), ConstructionType.apply(1), projectLink2.linkGeomSource, RoadType.PublicUnderConstructionRoad, Some(""), None, 111, Some(""), Some("vvh_modified"),
        Map(), projectLink2.roadNumber, projectLink2.roadPartNumber, 2, -1, projectLink2.discontinuity.value,
        projectLink2.startAddrMValue, projectLink2.endAddrMValue, projectLink2.startMValue, projectLink2.endMValue,
        projectLink2.sideCode,
        calibrationPoints2._1,
        calibrationPoints2._2, Anomaly.None, projectLink2.status, 0, 0)

      mockForProject(id, Seq(p1, p2))
      projectService.addNewLinksToProject(Seq(projectLink1), id, "U", p1.linkId)
      val links = projectLinkDAO.fetchProjectLinks(id)
      links.size should be(1)
      reset(mockRoadLinkService)
      mockForProject(id, Seq(p2))

      projectService.addNewLinksToProject(Seq(projectLink2), id, "U", p2.linkId)
      val linksAfter = projectLinkDAO.fetchProjectLinks(id)
      linksAfter.size should be(2)
    }
  }

  test("Test projectService.changeDirection() When issuing said command to a newly created project link in a newly created project Then check if the side code changed correctly.") {
    def prettyPrint(links: List[ProjectLink]) = {

      val sortedLinks = links.sortBy(_.id)
      sortedLinks.foreach { link =>
        println(s""" ${link.linkId} trackCode ${link.track.value} -> |--- (${link.startAddrMValue}, ${link.endAddrMValue}) ---|  MValue = """ + (link.endMValue - link.startMValue))
      }
      println("\n Total length (0+1/2):" + (sortedLinks.filter(_.track != Track.Combined).map(_.geometryLength).sum / 2 +
        sortedLinks.filter(_.track == Track.Combined).map(_.geometryLength).sum))
    }

    runWithRollback {
      val links = projectLinkDAO.fetchProjectLinks(7081807)
      links.nonEmpty should be(true)
      val mappedGeoms = StaticTestData.mappedGeoms(links.map(_.linkId))
      val geomToLinks: Seq[ProjectLink] = links.map { l =>
        val geom = mappedGeoms(l.linkId)
        l.copy(geometry = geom,
          geometryLength = GeometryUtils.geometryLength(geom),
          endMValue = GeometryUtils.geometryLength(geom)
        )
      }
      val adjusted = ProjectSectionCalculator.assignMValues(geomToLinks)
      projectLinkDAO.updateProjectLinks(adjusted, "-", Seq())

      reset(mockRoadLinkService)
      when(mockRoadLinkService.getRoadLinksHistoryFromVVH(any[Set[Long]])).thenReturn(Seq())
      when(mockRoadLinkService.getRoadLinksByLinkIdsFromVVH(any[Set[Long]], any[Boolean])).thenAnswer(
        toMockAnswer(adjusted.map(toRoadLink))
      )
      val beforeChange = projectLinkDAO.fetchProjectLinks(7081807)
      projectService.changeDirection(7081807, 77997, 1, links.map(l => LinkToRevert(l.id, l.linkId, l.status.value, l.geometry)), ProjectCoordinates(0, 0, 0), "testuser")
      val changedLinks = projectLinkDAO.fetchProjectLinks(7081807)

      val maxBefore = if (beforeChange.nonEmpty) beforeChange.maxBy(_.endAddrMValue).endAddrMValue else 0
      val maxAfter = if (changedLinks.nonEmpty) changedLinks.maxBy(_.endAddrMValue).endAddrMValue else 0
      maxBefore should be(maxAfter)
      val combined = changedLinks.filter(_.track == Track.Combined)
      val right = changedLinks.filter(_.track == Track.RightSide)
      val left = changedLinks.filter(_.track == Track.LeftSide)

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
      // Test that for every link there should be the address before it or after it (unless it's the first or last link)
      changedLinks.foreach(l =>
        (l == changedLinks.head || changedLinks.exists(c => c.endAddrMValue == l.startAddrMValue &&
          c.track == l.track || (c.track.value * l.track.value == 0))) && (l == changedLinks.last ||
          changedLinks.exists(c => c.startAddrMValue == l.endAddrMValue &&
            c.track == l.track || (c.track.value * l.track.value == 0))) should be(true)
      )
      adjusted.foreach { l =>
        GeometryUtils.geometryEndpoints(mappedGeoms(l.linkId)) should be(GeometryUtils.geometryEndpoints(l.geometry))
      }

      val linksFirst = adjusted.minBy(_.id)
      val linksLast = adjusted.maxBy(_.id)
      val changedLinksFirst = changedLinks.minBy(_.id)
      val changedLinksLast = changedLinks.maxBy(_.id)
      adjusted.sortBy(_.id).zip(changedLinks.sortBy(_.id)).foreach {
        case (oldLink, newLink) =>
          oldLink.startAddrMValue should be((linksLast.endAddrMValue - newLink.endAddrMValue) +- 1)
          oldLink.endAddrMValue should be((linksLast.endAddrMValue - newLink.startAddrMValue) +- 1)
          val sideCodeChangeCorrect = (oldLink.sideCode, newLink.sideCode) match {
            case (SideCode.BothDirections, SideCode.BothDirections) => true
            case (SideCode.AgainstDigitizing, SideCode.TowardsDigitizing) => true
            case (SideCode.TowardsDigitizing, SideCode.AgainstDigitizing) => true
            case _ => false
          }
          sideCodeChangeCorrect should be (true)
      }
      linksFirst.id should be(changedLinksFirst.id)
      linksLast.id should be(changedLinksLast.id)
      linksLast.geometryLength should be(changedLinks.maxBy(_.id).geometryLength +- .1)
      linksLast.endMValue should be(changedLinks.maxBy(_.id).endMValue +- .1)
      linksFirst.endMValue should be(changedLinksFirst.endMValue +- .1)
      linksLast.endMValue should be(changedLinksLast.endMValue +- .1)
    }
  }

  test("Test projectService.addNewLinksToProject() When adding new link in beginning and transfer the remaining Then find said project links when querying for them") {
    runWithRollback {

      val reservedRoadPart1 = ProjectReservedPart(164, 77, 35, Some(5405L), Some(Discontinuity.EndOfRoad), Some(8), newLength = None, newDiscontinuity = None, newEly = None)
      val rap = Project(0, ProjectState.apply(1), "TestProject", "TestUser", DateTime.now(), "TestUser", DateTime.parse("2022-01-01"), DateTime.now(), "Some additional info", Seq(), None, None)
      val addressesOnPart = roadwayAddressMapper.getRoadAddressesByLinearLocation(linearLocationDAO.fetchByRoadways(roadwayDAO.fetchAllBySection(77, 35).map(_.roadwayNumber).toSet))
      val l = addressesOnPart.map(address => {
        toProjectLink(rap, LinkStatus.NotHandled)(address)
      })
      val project = projectService.createRoadLinkProject(rap)
      mockForProject(project.id, l)
      when(mockRoadLinkService.getSuravageRoadLinksByLinkIdsFromVVH(any[Set[Long]])).thenReturn(Seq())
      projectService.saveProject(project.copy(reservedParts = Seq(reservedRoadPart1)))

      val linksBefore = projectLinkDAO.fetchByProjectRoadPart(77, 35, project.id).groupBy(_.linkId).map(_._2.head).toList

      val mappedGeoms = StaticTestData.mappedGeoms(l.map(_.linkId))

      val geomToLinks: List[ProjectLink] = linksBefore.map { l =>
        val geom = mappedGeoms(l.linkId)
        l.copy(geometry = geom,
          geometryLength = GeometryUtils.geometryLength(geom),
          endMValue = GeometryUtils.geometryLength(geom)
        )
      }

      val points = "[{\"x\": 528105.957, \"y\": 6995221.607, \"z\": 120.3530000000028}," +
        "{\"x\": 528104.681, \"y\": 6995222.485, \"z\": 120.35099999999511}," +
        "{\"x\": 528064.931, \"y\": 6995249.45, \"z\": 120.18099999999686}," +
        "{\"x\": 528037.789, \"y\": 6995266.234, \"z\": 120.03100000000268}," +
        "{\"x\": 528008.332, \"y\": 6995285.521, \"z\": 119.8969999999972}," +
        "{\"x\": 527990.814, \"y\": 6995296.039, \"z\": 119.77300000000105}," +
        "{\"x\": 527962.009, \"y\": 6995313.215, \"z\": 119.57099999999627}," +
        "{\"x\": 527926.972, \"y\": 6995333.398, \"z\": 119.18799999999464}," +
        "{\"x\": 527890.962, \"y\": 6995352.332, \"z\": 118.82200000000012}," +
        "{\"x\": 527867.18, \"y\": 6995364.458, \"z\": 118.5219999999972}," +
        "{\"x\": 527843.803, \"y\": 6995376.389, \"z\": 118.35099999999511}," +
        "{\"x\": 527815.902, \"y\": 6995389.54, \"z\": 117.94599999999627}," +
        "{\"x\": 527789.731, \"y\": 6995401.53, \"z\": 117.6420000000071}," +
        "{\"x\": 527762.707, \"y\": 6995413.521, \"z\": 117.2960000000021}," +
        "{\"x\": 527737.556, \"y\": 6995424.518, \"z\": 117.09799999999814}," +
        "{\"x\": 527732.52, \"y\": 6995426.729, \"z\": 116.98600000000442}]"
      val geom = JSON.parseFull(points).get.asInstanceOf[List[Map[String, Double]]].map(m => Point(m("x"), m("y"), m("z")))

      val newLink = ProjectAddressLink(NewLinearLocation, 5167571, geom, GeometryUtils.geometryLength(geom),
        State, Motorway, ConstructionType.InUse, LinkGeomSource.NormalLinkInterface,
        RoadType.PublicRoad, Some("X"), None, 749, None, None, Map.empty, 77, 35, 0L, 8L, 5L, 0L, 0L, 0.0, GeometryUtils.geometryLength(geom),
        SideCode.AgainstDigitizing, None, None, Anomaly.None, LinkStatus.New, 0, 0)

      when(mockRoadLinkService.getRoadLinksByLinkIdsFromVVH(any[Set[Long]], any[Boolean])).thenReturn(geomToLinks.map(toRoadLink))
      projectService.addNewLinksToProject(Seq(backToProjectLink(rap.copy(id = project.id))(newLink)).map(_.copy(status = LinkStatus.New)), project.id, "U", newLink.linkId) should be(None)

      val allLinks = projectLinkDAO.fetchProjectLinks(project.id)
      val transferLinks = allLinks.filter(_.status != LinkStatus.New)

      when(mockRoadLinkService.getRoadLinksByLinkIdsFromVVH(allLinks.map(_.linkId).toSet)).thenReturn(geomToLinks.map(toRoadLink) ++ Seq(toRoadLink(newLink)))
      projectService.updateProjectLinks(project.id, Set(), transferLinks.map(_.linkId), LinkStatus.Transfer, "Test", 77, 35, 0, Option.empty[Int]) should be(None)

      val (resultNew, resultTransfer) = projectLinkDAO.fetchProjectLinks(project.id).partition(_.status == LinkStatus.New)
      resultTransfer.head.calibrationPoints._1 should be(Some(ProjectLinkCalibrationPoint(5167598, 296.597, 0, ProjectLinkSource)))
      resultTransfer.head.calibrationPoints._2 should be(None)
      allLinks.size should be(resultNew.size + resultTransfer.size)
    }
  }

  test("Test projectService.updateProjectLinks() When terminating a link and aading a new link in the beginning of road part then transfer to the rest of road part Then check the changed road parts when querying for them.") {
    runWithRollback {
      val reservedRoadPart1 = ProjectReservedPart(3192, 77, 35, Some(3192), Some(Discontinuity.EndOfRoad), Some(8L), newLength = None, newDiscontinuity = None, newEly = None)
      val rap = Project(0, ProjectState.apply(1), "TestProject", "TestUser", DateTime.now(), "TestUser", DateTime.parse("2022-01-01"), DateTime.now(), "Some additional info", Seq(), None, None)
      val addressesOnPart = roadwayAddressMapper.getRoadAddressesByLinearLocation(linearLocationDAO.fetchByRoadways(roadwayDAO.fetchAllBySection(77, 35).map(_.roadwayNumber).toSet))
      val geometries = StaticTestData.mappedGeoms(addressesOnPart.map(_.linkId).toSet)
      val l = addressesOnPart.map(address => {
        toProjectLink(rap, LinkStatus.NotHandled)(address)
      }).map(pl => pl.copy(geometry = geometries(pl.linkId)))
      when(mockRoadLinkService.getSuravageRoadLinksByLinkIdsFromVVH(any[Set[Long]])).thenReturn(Seq())
      when(mockRoadLinkService.getRoadLinksByLinkIdsFromVVH(any[Set[Long]], any[Boolean])).thenReturn(
        mockRoadLinksWithStaticTestData(addressesOnPart.map(_.linkId).toSet))

      val project = projectService.createRoadLinkProject(rap)
      projectService.saveProject(project.copy(reservedParts = Seq(reservedRoadPart1)))

      val linksBefore = projectLinkDAO.fetchByProjectRoadPart(77, 35, project.id).groupBy(_.linkId).map(_._2.head).toList
      val mappedGeoms2 = StaticTestData.mappedGeoms(l.map(_.linkId))

      val geomToLinks: List[ProjectLink] = linksBefore.map { l =>
        val geom = mappedGeoms2(l.linkId)
        l.copy(geometry = geom,
          geometryLength = GeometryUtils.geometryLength(geom),
          endMValue = GeometryUtils.geometryLength(geom)
        )
      }
      val geom = StaticTestData.mappedGeoms(Seq(3730091L)).head._2

      val newLink = ProjectAddressLink(NewLinearLocation, 3730091L, geom, GeometryUtils.geometryLength(geom),
        State, Motorway, ConstructionType.InUse, LinkGeomSource.NormalLinkInterface,
        RoadType.PublicRoad, Some("X"), None, 749, None, None, Map.empty, 77, 35, 0L, 12L, 5L, 0L, 0L, 0.0, GeometryUtils.geometryLength(geom),
        SideCode.Unknown, None, None, Anomaly.None, LinkStatus.New, 0, 0)

      val linkToTerminate = geomToLinks.minBy(_.startAddrMValue)
      when(mockRoadLinkService.getRoadLinksByLinkIdsFromVVH(any[Set[Long]], any[Boolean])).thenReturn(geomToLinks.map(toRoadLink))
      projectService.updateProjectLinks(project.id, Set(), Seq(linkToTerminate.linkId), LinkStatus.Terminated, "Test User", 0, 0, 0, Option.empty[Int]) should be(None)

      projectService.addNewLinksToProject(Seq(backToProjectLink(rap.copy(id = project.id))(newLink))
        .map(l => l.copy(status = LinkStatus.New, geometry = geom)), project.id, "U", newLink.linkId) should be(None)

      val allLinks = projectLinkDAO.fetchProjectLinks(project.id)
      val transferLinks = allLinks.filter(al => {
        al.status != LinkStatus.New && al.status != LinkStatus.Terminated
      })

      when(mockRoadLinkService.getRoadLinksByLinkIdsFromVVH(allLinks.map(_.linkId).toSet)).thenReturn(
        geomToLinks.map(toRoadLink) ++ Seq(toRoadLink(newLink)))
      transferLinks.groupBy(_.track.value).foreach {
        case (k, seq) =>
          projectService.updateProjectLinks(project.id, Set(), seq.map(_.linkId), LinkStatus.Transfer, "Test", 77, 35, k, Option.empty[Int]) should be(None)
      }

      val (resultNew, resultOther) = projectLinkDAO.fetchProjectLinks(project.id).partition(_.status == LinkStatus.New)
      val (resultTransfer, resultTerm) = resultOther.partition(_.status == LinkStatus.Transfer)
      allLinks.size should be(resultNew.size + resultTransfer.size + resultTerm.size)
    }
  }

  test("Test projectService.updateProjectLinks() When issuing a numbering change on transfer operation with same road number Then check if all the roads have proper m address values defined.") {
    runWithRollback {
      val address1 = roadwayAddressMapper.getRoadAddressesByLinearLocation(linearLocationDAO.fetchByRoadways(roadwayDAO.fetchAllBySection(5, 206).map(_.roadwayNumber).toSet))
      val address2 = roadwayAddressMapper.getRoadAddressesByLinearLocation(linearLocationDAO.fetchByRoadways(roadwayDAO.fetchAllBySection(5, 207).map(_.roadwayNumber).toSet))
      val reservedRoadPart1 = ProjectReservedPart(address1.head.id, address1.head.roadNumber, address1.head.roadPartNumber,
        Some(address1.last.endAddrMValue), Some(address1.last.discontinuity), Some(8L), newLength = None, newDiscontinuity = None, newEly = None)
      val reservedRoadPart2 = ProjectReservedPart(address2.head.id, address2.head.roadNumber, address2.head.roadPartNumber,
        Some(address2.last.endAddrMValue), Some(address2.last.discontinuity), Some(8L), newLength = None, newDiscontinuity = None, newEly = None)
      val rap = Project(0, ProjectState.apply(1), "TestProject", "TestUser", DateTime.now(), "TestUser", DateTime.now(), DateTime.now(), "Some additional info", Seq(reservedRoadPart1) ++ Seq(reservedRoadPart2), None, None)

      val links = (address1 ++ address2).map(address => {
        toProjectLink(rap, LinkStatus.NotHandled)(address)
      })
      when(mockRoadLinkService.getSuravageRoadLinksByLinkIdsFromVVH(any[Set[Long]])).thenReturn(Seq())
      when(mockRoadLinkService.getRoadLinksHistoryFromVVH(any[Set[Long]])).thenReturn(Seq())
      when(mockRoadLinkService.getRoadLinksByLinkIdsFromVVH(any[Set[Long]], any[Boolean])).thenReturn(links.map(toRoadLink))
      val project = projectService.createRoadLinkProject(rap)

      //Unchanged + Transfer
      val transferLinkId = address2.minBy(_.startAddrMValue).linkId
      projectService.updateProjectLinks(project.id, Set(), address1.map(_.linkId), LinkStatus.UnChanged, "TestUser",
        0, 0, 0, Option.empty[Int]) should be(None)
      projectService.updateProjectLinks(project.id, Set(), Seq(transferLinkId), LinkStatus.Transfer, "TestUser", 5, 206, 0, Option.empty[Int]) should be(None)
      val firstTransferLinks = projectLinkDAO.fetchProjectLinks(project.id)
      //TODO- this should be uncommented after VIITE-1541 implementation
      //firstTransferLinks.filter(_.roadPartNumber == 1).map(_.endAddrMValue).max should be(address1.map(_.endAddrMValue).max +
      //address2.find(_.linkId == transferLinkId).map(a => a.endAddrMValue - a.startAddrMValue).get)
      //Transfer the rest
      projectService.updateProjectLinks(project.id, Set(), address2.sortBy(_.startAddrMValue).tail.map(_.linkId),
        LinkStatus.Transfer, "TestUser", 5, 207, 0, Option.empty[Int]) should be(None)
      val secondTransferLinks = projectLinkDAO.fetchProjectLinks(project.id)
      //TODO- this should be uncommented after VIITE-1541 implementation
//      secondTransferLinks.filter(_.roadPartNumber == 2).maxBy(_.startAddrMValue).endAddrMValue should be(address2.maxBy(_.startAddrMValue).endAddrMValue - address2.minBy(_.startAddrMValue).endAddrMValue)
      val mappedLinks = links.groupBy(_.linkId)
      val mapping = secondTransferLinks.filter(_.roadPartNumber == 207).map(tl => tl -> mappedLinks(tl.linkId)).filterNot(_._2.size > 1)
      mapping.foreach { case (link, l) =>
        val before = l.head
        before.endAddrMValue - before.startAddrMValue should be(link.endAddrMValue - link.startAddrMValue +- 1)
      }
      secondTransferLinks.groupBy(_.roadPartNumber).mapValues(_.map(_.endAddrMValue).max).values.sum should be
      address1.map(_.endAddrMValue).max + address2.map(_.endAddrMValue).max
    }
  }

  test("Test projectService.updateProjectLinks() When issuing a numbering change on transfer operation with different road number Then check that thwe ammount of roads for one road number goes down and the other goes up.") {
    runWithRollback {

      val address1 = roadwayAddressMapper.getRoadAddressesByLinearLocation(linearLocationDAO.fetchByRoadways(roadwayDAO.fetchAllBySection(11, 8).map(_.roadwayNumber).toSet)).sortBy(_.startAddrMValue)
      val address2 = roadwayAddressMapper.getRoadAddressesByLinearLocation(linearLocationDAO.fetchByRoadways(roadwayDAO.fetchAllBySection(259, 1).map(_.roadwayNumber).toSet)).sortBy(_.startAddrMValue)
      val reservedRoadPart1 = ProjectReservedPart(address1.head.id, address1.head.roadNumber, address1.head.roadPartNumber,
        Some(address1.last.endAddrMValue), Some(address1.head.discontinuity), Some(address1.head.ely), newLength = None, newDiscontinuity = None, newEly = None)
      val reservedRoadPart2 = ProjectReservedPart(address2.head.id, address2.head.roadNumber, address2.head.roadPartNumber,
        Some(address2.last.endAddrMValue), Some(address2.head.discontinuity), Some(address2.head.ely), newLength = None, newDiscontinuity = None, newEly = None)
      val rap = Project(0, ProjectState.apply(1), "TestProject", "TestUser", DateTime.now(), "TestUser", DateTime.now(), DateTime.now(), "Some additional info", Seq(reservedRoadPart1) ++ Seq(reservedRoadPart2), None, None)

      val links = (address1 ++ address2).map(address => {
        toProjectLink(rap, LinkStatus.NotHandled)(address)
      })
      when(mockRoadLinkService.getSuravageRoadLinksByLinkIdsFromVVH(any[Set[Long]])).thenReturn(Seq())
      when(mockRoadLinkService.getRoadLinksHistoryFromVVH(any[Set[Long]])).thenReturn(Seq())
      when(mockRoadLinkService.getRoadLinksByLinkIdsFromVVH(any[Set[Long]], any[Boolean])).thenReturn(links.map(toRoadLink))
      val project = projectService.createRoadLinkProject(rap)

      val transferLink = address2.minBy(_.startAddrMValue)
      projectService.updateProjectLinks(project.id, Set(), address1.map(_.linkId), LinkStatus.UnChanged, "TestUser",
        0, 0, 0, Option.empty[Int]) should be(None)
      projectService.updateProjectLinks(project.id, Set(), Seq(transferLink.linkId), LinkStatus.Transfer, "TestUser",
        11, 8, 0, Option.empty[Int]) should be(None)

      val updatedLinks = projectLinkDAO.fetchProjectLinks(project.id)
      val linksRoad11 = updatedLinks.filter(_.roadNumber == 11).sortBy(_.startAddrMValue)
      val linksRoad259 = updatedLinks.filter(_.roadNumber == 259).sortBy(_.startAddrMValue)

      linksRoad11.size should be(address1.size + 1)
      linksRoad259.size should be(address2.size - 1)

    }
  }

  test("Test projectService.updateProjectLinks() When reserving a road part, try to renumber it to the same number Then it should produce an error.") {
    runWithRollback {
      reset(mockRoadLinkService)
      val addresses = roadwayAddressMapper.getRoadAddressesByLinearLocation(linearLocationDAO.fetchByRoadways(roadwayDAO.fetchAllBySection(5, 201).map(_.roadwayNumber).toSet)).sortBy(_.startAddrMValue)
      val reservedRoadPart = ProjectReservedPart(addresses.head.id, addresses.head.roadNumber, addresses.head.roadPartNumber,
        Some(addresses.last.endAddrMValue), Some(addresses.head.discontinuity), Some(8L), newLength = None, newDiscontinuity = None, newEly = None)
      val rap = Project(0, ProjectState.apply(1), "TestProject", "TestUser", DateTime.now(), "TestUser", DateTime.now(), DateTime.now(), "Some additional info", Seq(), None, None)
      when(mockRoadLinkService.getSuravageRoadLinksByLinkIdsFromVVH(any[Set[Long]])).thenReturn(Seq())
      val project = projectService.createRoadLinkProject(rap)
      mockForProject(project.id, addresses.map(toProjectLink(project)))
      projectService.saveProject(project.copy(reservedParts = Seq(reservedRoadPart)))
      val savedProject = projectService.getSingleProjectById(project.id).get
      savedProject.reservedParts should have size 1
      reset(mockRoadLinkService)

      projectService.updateProjectLinks(project.id, Set(), addresses.map(_.linkId), LinkStatus.Numbering, "TestUser",
        5, 201, 1, Option.empty[Int]) should be(Some(ErrorRenumberingToOriginalNumber))
    }
  }

  test("Test projectService.createProjectLinks() When trying to create project links onto a roundabout Then check if the created project links are actually a roundabout.") {
    runWithRollback {
      val rap = Project(0, ProjectState.apply(1), "TestProject", "TestUser", DateTime.now(), "TestUser",
        DateTime.now(), DateTime.now(), "Some additional info", Seq(), None, None)
      val project = projectService.createRoadLinkProject(rap)
      val map = Map("MUNICIPALITYCODE" -> BigInt(456))
      val roadLinks = Seq(RoadLink(121L, Seq(Point(0.0, 0.0), Point(10.0, 0.0)), 10.0, State, 1, TrafficDirection.TowardsDigitizing, LinkType.apply(1), None,
        None, map, ConstructionType.InUse, LinkGeomSource.NormalLinkInterface),
        RoadLink(122L, Seq(Point(10.0, 0.0), Point(5.0, 8.0)), 9.434, State, 1, TrafficDirection.TowardsDigitizing, LinkType.apply(1), None,
          None, map, ConstructionType.InUse, LinkGeomSource.NormalLinkInterface),
        RoadLink(123L, Seq(Point(0.0, 0.0), Point(5.0, 8.0)), 9.434, State, 1, TrafficDirection.AgainstDigitizing, LinkType.apply(1), None,
          None, map, ConstructionType.InUse, LinkGeomSource.NormalLinkInterface))
      when(mockRoadLinkService.getRoadLinksHistoryFromVVH(any[Set[Long]])).thenReturn(Seq())
      when(mockRoadLinkService.getRoadLinksByLinkIdsFromVVH(any[Set[Long]], any[Boolean])).thenAnswer(
        toMockAnswer(roadLinks))
      val resp = createProjectLinks(Seq(123L, 121L, 122L), project.id, 39999, 12, 0, 1, 1, 1, 8, "user", "road name")
      resp.get("success") should be(Some(true))
      val links =
        projectLinkDAO.fetchProjectLinks(project.id).groupBy(_.linkId).map {
          pl => pl._1 -> ProjectAddressLinkBuilder.build(pl._2.head)
        }.values.toSeq
      val start = links.find(_.linkId == 123L)
      start.isEmpty should be(false)
      start.get.sideCode should be(AgainstDigitizing)
      start.get.startAddressM should be(0L)
      start.get.endAddressM should be(9L)
      val end = links.find(_.linkId == 122L)
      end.isEmpty should be(false)
      end.get.sideCode should be(TowardsDigitizing)
      end.get.startAddressM should be(19L)
      end.get.endAddressM should be(28L)
      end.get.discontinuity should be(Discontinuity.EndOfRoad.value)
      links.count(_.discontinuity != Discontinuity.Continuous.value) should be(1)
    }
  }

  test("Test projectService.createProjectLinks() When creating a new road with track 2, then updating it to track 0 Then the whole process should not crash.") {
    runWithRollback {
      val id = Sequences.nextViitePrimaryKeySeqValue
      val rap = Project(id, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("2018-01-01"),
        "TestUser", DateTime.parse("2100-01-01"), DateTime.now(), "Some additional info", List.empty, None)
      projectDAO.create(rap)

      val points6552 = "[{\"x\":537869.292,\"y\":6997722.466,\"z\":110.39800000000105}," +
        "{\"x\":538290.056,\"y\":6998265.169,\"z\":85.4429999999993}]"
      val geom = JSON.parseFull(points6552).get.asInstanceOf[List[Map[String, Double]]].map(m => Point(m("x"), m("y"), m("z")))


      val addProjectAddressLink552 = ProjectAddressLink(NewRoadway, 6552, geom, GeometryUtils.geometryLength(geom),
        State, Motorway, ConstructionType.InUse, LinkGeomSource.NormalLinkInterface,
        RoadType.PublicRoad, Some("X"), None, 749, None, None, Map.empty, 19999, 1, 2L, 8L, 5L, 0L, 0L, 0.0, GeometryUtils.geometryLength(geom),
        SideCode.TowardsDigitizing, None, None, Anomaly.None, LinkStatus.New, 0, 0)
      val addresses = Seq(addProjectAddressLink552)
      mockForProject(id, addresses)
      projectService.addNewLinksToProject(addresses.map(backToProjectLink(rap)), id, "U", addresses.head.linkId) should be(None)
      val links = projectLinkDAO.fetchProjectLinks(id)
      projectReservedPartDAO.fetchReservedRoadParts(id) should have size 1
      projectService.updateProjectLinks(id, Set(), links.map(_.linkId), LinkStatus.New, "test", 19999, 1, 0, None,
        RoadType.FerryRoad.value, Discontinuity.EndOfRoad.value, Some(8))
      val linksAfterUpdate = projectLinkDAO.fetchProjectLinks(id)
      val firstLink = linksAfterUpdate.head
      firstLink.roadNumber should be(19999)
      firstLink.roadPartNumber should be(1)
      firstLink.track.value should be(0)
    }
  }


  //TODO Will be implemented with SPLIT
  //  test("Splitting link test") {
  //    runWithRollback {
  //      val roadLink = RoadLink(1, Seq(Point(0, 0), Point(0, 45.3), Point(0, 87))
  //        , 87.0, State, 99, TrafficDirection.AgainstDigitizing, UnknownLinkType, Some("25.06.2015 03:00:00"),
  //        Some("vvh_modified"), Map("MUNICIPALITYCODE" -> BigInt.apply(749)),
  //        InUse, NormalLinkInterface)
  //      val suravageAddressLink = RoadLink(2, Seq(Point(0, 0), Point(0, 45.3), Point(0, 123)), 123,
  //        State, 99, TrafficDirection.AgainstDigitizing, UnknownLinkType, Some("25.06.2015 03:00:00"),
  //        Some("vvh_modified"), Map("MUNICIPALITYCODE" -> BigInt.apply(749)),
  //        InUse, LinkGeomSource.SuravageLinkInterface)
  //      when(mockRoadLinkService.getSuravageRoadLinksByLinkIdsFromVVH(any[Set[Long]])).thenReturn(Seq(suravageAddressLink))
  //      when(mockRoadLinkService.getRoadLinksAndComplementaryFromVVH(any[BoundingRectangle], any[Set[Int]])).thenReturn(Seq(roadLink))
  //      when(mockRoadLinkService.getRoadLinkByLinkIdFromVVH(any[Long])).thenReturn(Some(roadLink))
  //      val rap = createProjectDataForSplit()
  //      val options = SplitOptions(Point(0, 45.3), LinkStatus.UnChanged, LinkStatus.New, 1, 1, Track.Combined, Discontinuity.Continuous,
  //        1, LinkGeomSource.NormalLinkInterface, RoadType.PublicRoad, rap.id, ProjectCoordinates(0,0,0))
  //      val errorOpt = projectServiceWithRoadAddressMock.splitSuravageLinkInTX(suravageAddressLink.linkId, "testUser", options)
  //      errorOpt should be(None)
  //      val projectLinks = ProjectDAO.getProjectLinks(rap.id)
  //      checkSplit(projectLinks, 45.3, 0, 45, 87, 123.0)
  //    }
  //  }


  //TODO this will be implemented at VIITE-1541
  //  test("Reserve road part, renumber it and revert it; reservation should be freed") {
  //    runWithRollback {
  //      reset(mockRoadLinkService)
  //      setUpProjectWithLinks(LinkStatus.New, Seq(0L, 10L, 20L, 30L, 40L))
  //      val createdRoadAddresses = Seq(RoadAddress(NewRoadAddress, 19999L, 2L, RoadType.PublicRoad, Track.Combined, Discontinuity.EndOfRoad,
  //        0L, 10L, Some(DateTime.now()), None, None, 39399L, 0.0, 10.0, TowardsDigitizing, 0L, (Some(CalibrationPoint(39399L, 0.0, 0L)), Some(CalibrationPoint(39399L, 10.0, 10L))),
  //        floating = NoFloating, Seq(Point(10.0, 40.0), Point(10.0, 50.0)), LinkGeomSource.ComplimentaryLinkInterface, 9L, NoTermination, 0))
  //      RoadAddressDAO.create(createdRoadAddresses).head
  //
  //      val reservedRoadPart = ReservedRoadPart(createdRoadAddresses.head.id, createdRoadAddresses.head.roadNumber, createdRoadAddresses.head.roadPartNumber,
  //        Some(createdRoadAddresses.last.endAddrMValue), Some(createdRoadAddresses.head.discontinuity), Some(8L), newLength = None, newDiscontinuity = None, newEly = None)
  //      val rap = RoadAddressProject(0, ProjectState.apply(1), "TestProject", "TestUser", DateTime.now(), "TestUser", DateTime.now(), DateTime.now(), "Some additional info", Seq(), None, None)
  //      val project = projectService.createRoadLinkProject(rap)
  //
  //      mockForProject(project.id, createdRoadAddresses.map(toProjectLink(project)))
  //      projectService.saveProject(project.copy(reservedParts = Seq(reservedRoadPart)))
  //      val savedProject = projectService.getRoadAddressSingleProject(project.id).get
  //      savedProject.reservedParts should have size (1)
  //      reset(mockRoadLinkService)
  //
  //      projectService.updateProjectLinks(project.id, Set(), createdRoadAddresses.map(_.linkId), LinkStatus.Numbering, "TestUser",
  //        19998, 101, 0, Option.empty[Int]) should be(None)
  //
  //      val upProject = projectService.getRoadAddressSingleProject(project.id)
  //      upProject.nonEmpty should be(true)
  //      upProject.get.reservedParts should have size (2)
  //      upProject.get.reservedParts.map(_.roadNumber).toSet should be(Set(19998L, 19999L))
  //      upProject.get.reservedParts.map(_.roadPartNumber).toSet should be(Set(101L, 2L))
  //
  //      val renumberedLinks = ProjectDAO.getProjectLinks(project.id, Some(LinkStatus.Numbering))
  //      val mockRoadLinks = createdRoadAddresses.map(ra => {
  //        addressToRoadLink(ra)
  //      })
  //      when(mockRoadLinkService.getCurrentAndComplementaryAndSuravageRoadLinksFromVVH(any[Set[Long]], any[Boolean])).thenReturn(mockRoadLinks)
  //      projectService.revertLinks(renumberedLinks, "user") should be(None)
  //
  //      val revertedProject = projectService.getRoadAddressSingleProject(project.id)
  //      revertedProject.nonEmpty should be(true)
  //      revertedProject.get.reservedParts should have size (1)
  //      revertedProject.get.reservedParts.map(_.roadPartNumber).toSet should be(Set(2L))
  //    }
  //  }

  //TODO this will be implemented at VIITE-1541
  //  test("Termination and creation of new road links in tracks 1 and 2") {
  //    def toProjectAddressLink(linkId: Long, road: Long, part: Long, geometry: Seq[Point]): ProjectAddressLink = {
  //      ProjectAddressLink(NewRoadAddress, linkId, geometry, GeometryUtils.geometryLength(geometry),
  //        State, Motorway, RoadLinkType.NormalRoadLinkType, ConstructionType.InUse, LinkGeomSource.NormalLinkInterface,
  //        RoadType.PublicRoad, Some("X"), None, 749, None, None, Map.empty, road, part, 1L, 8L, 5L, 0L, 0L, 0.0, GeometryUtils.geometryLength(geometry),
  //        SideCode.Unknown, None, None, Anomaly.None, LinkStatus.New, 0)
  //    }
  //
  //    runWithRollback {
  //      val address1 = RoadAddressDAO.fetchByRoadPart(5, 201, false).sortBy(_.startAddrMValue)
  //      val address2 = RoadAddressDAO.fetchByRoadPart(5, 202, false).sortBy(_.startAddrMValue)
  //      val reservedRoadPart1 = ReservedRoadPart(address1.head.id, address1.head.roadNumber, address1.head.roadPartNumber,
  //        Some(address1.last.endAddrMValue), Some(address1.head.discontinuity), Some(8L), newLength = None, newDiscontinuity = None, newEly = None)
  //      val reservedRoadPart2 = ReservedRoadPart(address2.head.id, address2.head.roadNumber, address2.head.roadPartNumber,
  //        Some(address2.last.endAddrMValue), Some(address2.head.discontinuity), Some(8L), newLength = None, newDiscontinuity = None, newEly = None)
  //      val rap = RoadAddressProject(0, ProjectState.apply(1), "TestProject", "TestUser", DateTime.now(), "TestUser", DateTime.now(), DateTime.now(), "Some additional info", Seq(), None, None)
  //
  //      val ad = address1.map(a => {
  //        a.copy(geometry = StaticTestData.mappedGeoms(Seq(a.linkId))(a.linkId))
  //      })
  //
  //      val allRoadParts = (ad ++ address2).map(address => {
  //        toProjectLink(rap, LinkStatus.NotHandled)(address)
  //      })
  //      val project = projectService.createRoadLinkProject(rap)
  //      reset(mockRoadLinkService)
  //      when(mockRoadLinkService.getRoadLinksByLinkIdsFromVVH(any[Set[Long]], any[Boolean])).thenReturn(
  //        mockRoadLinksWithStaticTestData((address1 ++ address2).map(_.linkId).toSet))
  //      projectService.saveProject(project.copy(reservedParts = Seq(reservedRoadPart1, reservedRoadPart2)))
  //      projectService.updateProjectLinks(project.id, Set(), Seq(2226690, 2226637), LinkStatus.Terminated, "TestUser", 0, 0, 0, Option.empty[Int]) should be(None)
  //      projectService.updateProjectLinks(project.id, Set(), Seq(2226636), LinkStatus.Terminated, "TestUser", 0, 0, 0, Option.empty[Int]) should be(None)
  //      projectService.updateProjectLinks(project.id, Set(), Seq(2226676, 2226658), LinkStatus.Terminated, "TestUser", 0, 0, 0, Option.empty[Int]) should be(None)
  //      projectService.updateProjectLinks(project.id, Set(), Seq(2226677, 2226482), LinkStatus.Terminated, "TestUser", 0, 0, 0, Option.empty[Int]) should be(None)
  //      val mappedGeomsNewLinks = StaticTestData.mappedGeoms(Seq(2226681, 6564541, 2226632, 2226660, 2226480))
  //      val geom1 = mappedGeomsNewLinks(2226681)
  //      val geom2 = mappedGeomsNewLinks(6564541)
  //      val geom3 = mappedGeomsNewLinks(2226632)
  //      val geom4 = mappedGeomsNewLinks(2226660)
  //      val geom5 = mappedGeomsNewLinks(2226480)
  //
  //      val link1 = toProjectAddressLink(2226681, 5, 201, geom1)
  //      val link2 = toProjectAddressLink(6564541, 5, 201, geom2)
  //      val link3 = toProjectAddressLink(2226632, 5, 201, geom3)
  //      val link4 = toProjectAddressLink(2226660, 5, 202, geom4)
  //      val link5 = toProjectAddressLink(2226480, 5, 202, geom5)
  //
  //      sqlu"""UPDATE PROJECT_LINK SET status = ${LinkStatus.Transfer.value} WHERE project_id = ${project.id} AND status = ${LinkStatus.NotHandled.value}""".execute
  //
  //      mockForProject(project.id, Seq(link1, link2))
  //      projectService.addNewLinksToProject(Seq(link1, link2).map(backToProjectLink(rap.copy(id = project.id)))
  //        .map(_.copy(status = LinkStatus.New)), project.id, "U", Seq(link1, link2).minBy(_.endMValue).linkId) should be(None)
  //      mockForProject(project.id, Seq(link3))
  //      projectService.addNewLinksToProject(Seq(backToProjectLink(rap.copy(id = project.id))(link3))
  //        .map(_.copy(status = LinkStatus.New, track = Track.LeftSide)), project.id, "U", link3.linkId) should be(None)
  //      mockForProject(project.id, Seq(link4))
  //      projectService.addNewLinksToProject(Seq(backToProjectLink(rap.copy(id = project.id))(link4))
  //        .map(_.copy(status = LinkStatus.New)), project.id, "U", link4.linkId) should be(None)
  //      mockForProject(project.id, Seq(link5))
  //      projectService.addNewLinksToProject(Seq(backToProjectLink(rap.copy(id = project.id))(link5))
  //        .map(_.copy(status = LinkStatus.New, track = Track.LeftSide)), project.id, "U", link5.linkId) should be(None)
  //
  //      val linksAfter = ProjectDAO.getProjectLinks(project.id)
  //      val newLinks = linksAfter.filter(_.status == LinkStatus.New).sortBy(_.startAddrMValue)
  //      linksAfter.size should be(allRoadParts.size + newLinks.size)
  //
  //      val (new201R, new201L) = newLinks.filter(_.roadPartNumber == 201).partition(_.track == Track.RightSide)
  //      val (new202R, new202L) = newLinks.filter(_.roadPartNumber == 202).partition(_.track == Track.RightSide)
  //      new201R.maxBy(_.startAddrMValue).endAddrMValue should be(new201L.maxBy(_.startAddrMValue).endAddrMValue)
  //
  //      projectService.getChangeProject(project.id).isEmpty should be(false)
  //
  //      val changesList = RoadwayChangesDAO.fetchRoadwayChanges(Set(project.id))
  //      changesList.isEmpty should be(false)
  //      val terminationChangesRightSide201 = changesList.filter(cl => {
  //        cl.changeInfo.source.startRoadPartNumber.getOrElse(-1L) == 201L && cl.changeInfo.changeType == Termination && cl.changeInfo.source.trackCode.getOrElse(-1L) == Track.RightSide.value
  //      })
  //      val terminationChangesLeftSide201 = changesList.filter(cl => {
  //        cl.changeInfo.source.startRoadPartNumber.getOrElse(-1L) == 201 && cl.changeInfo.changeType == Termination && cl.changeInfo.source.trackCode.getOrElse(-1L) == Track.LeftSide.value
  //      })
  //      val terminationChangesRightSide202 = changesList.filter(cl => {
  //        cl.changeInfo.source.startRoadPartNumber.getOrElse(-1L) == 202 && cl.changeInfo.changeType == Termination && cl.changeInfo.source.trackCode.getOrElse(-1L) == Track.RightSide.value
  //      })
  //      val terminationChangesLeftSide202 = changesList.filter(cl => {
  //        cl.changeInfo.source.startRoadPartNumber.getOrElse(-1L) == 202 && cl.changeInfo.changeType == Termination && cl.changeInfo.source.trackCode.getOrElse(-1L) == Track.LeftSide.value
  //      })
  //
  //      terminationChangesRightSide201.map(t => t.changeInfo.source.endAddressM).containsSlice(terminationChangesLeftSide201.map(t => t.changeInfo.source.endAddressM)) should be (true)
  //      terminationChangesRightSide202.map(t => t.changeInfo.source.endAddressM).containsSlice(terminationChangesLeftSide202.map(t => t.changeInfo.source.endAddressM)) should be (true)
  //
  //      val newChanges = changesList.filter(_.changeInfo.changeType == AddressChangeType.New)
  //      newChanges.foreach(nc => {
  //        nc.changeInfo.source.startAddressM should be(None)
  //        nc.changeInfo.source.endAddressM should be(None)
  //        nc.changeInfo.target.startAddressM shouldNot be(None)
  //        nc.changeInfo.target.endAddressM shouldNot be(None)
  //      })
  //    }
  //  }

  //  //TODO: Fix road link geometry -> two segments on link 5169973, but returning geometry for 0-315m segment (geomToLinks is the culprit)
  //  ignore("Growing direction should be same after adding new links to a reserved part") {
  //    runWithRollback {
  //
  //      def toGeom(json: Option[Any]): List[Point] = {
  //        json.get.asInstanceOf[List[Map[String, Double]]].map(m => Point(m("x"), m("y"), m("z")))
  //      }
  //
  //      val id = Sequences.nextViitePrimaryKeySeqValue
  //      val reservedRoadPart1 = ReservedRoadPart(164, 77, 35, Some(5405), Some(Discontinuity.EndOfRoad), Some(8L), newLength = None, newDiscontinuity = None, newEly = None)
  //      val rap = RoadAddressProject(id, ProjectState.apply(1), "TestProject", "TestUser", DateTime.now(), "TestUser", DateTime.now(), DateTime.now(), "Some additional info", Seq(reservedRoadPart1), None)
  //      ProjectDAO.createRoadAddressProject(rap)
  //      ProjectDAO.reserveRoadPart(rap.id, 77, 35, "TestUser")
  //      val addressesOnPart = RoadAddressDAO.fetchByRoadPart(77, 35, false)
  //      ProjectDAO.create(addressesOnPart.map(address => {
  //        toProjectLink(rap, LinkStatus.NotHandled)(address)
  //      }))
  //
  //      val linksBefore = ProjectDAO.fetchByProjectRoadPart(77, 35, id)
  //
  //      val points5170271 = "[ {\"x\": 530492.408, \"y\": 6994103.892, \"z\": 114.60400000000664},{\"x\": 530490.492, \"y\": 6994104.815, \"z\": 114.63800000000629},{\"x\": 530459.903, \"y\": 6994118.958, \"z\": 114.97299999999814},{\"x\": 530427.446, \"y\": 6994134.189, \"z\": 115.30400000000373},{\"x\": 530392.422, \"y\": 6994153.545, \"z\": 115.721000000005},{\"x\": 530385.114, \"y\": 6994157.976, \"z\": 115.71099999999569},{\"x\": 530381.104, \"y\": 6994161.327, \"z\": 115.77000000000407},{\"x\": 530367.101, \"y\": 6994170.075, \"z\": 115.93099999999686},{\"x\": 530330.275, \"y\": 6994195.603, \"z\": 116.37200000000303}]"
  //      val points5170414 = "[ {\"x\": 531540.842, \"y\": 6993806.017, \"z\": 114.1530000000057},{\"x\": 531515.135, \"y\": 6993815.644, \"z\": 114.74400000000605}]"
  //      val points5170067 = "[ {\"x\": 529169.924, \"y\": 6994631.929, \"z\": 121.52999999999884},{\"x\": 529158.557, \"y\": 6994635.609, \"z\": 121.47999999999593},{\"x\": 529149.47, \"y\": 6994638.618, \"z\": 121.43300000000454}]"
  //      val points5170066 = "[ {\"x\": 529149.47, \"y\": 6994638.618, \"z\": 121.43300000000454},{\"x\": 529147.068, \"y\": 6994639.416, \"z\": 121.45200000000477},{\"x\": 529142.91, \"y\": 6994640.794, \"z\": 121.41700000000128},{\"x\": 529116.198, \"y\": 6994650.179, \"z\": 121.32600000000093},{\"x\": 529099.946, \"y\": 6994655.993, \"z\": 121.2670000000071}]"
  //      val points5170074 = "[ {\"x\": 528982.934, \"y\": 6994703.835, \"z\": 120.9030000000057},{\"x\": 528972.656, \"y\": 6994708.219, \"z\": 120.87699999999313},{\"x\": 528948.747, \"y\": 6994719.171, \"z\": 120.72999999999593},{\"x\": 528924.998, \"y\": 6994730.062, \"z\": 120.64500000000407},{\"x\": 528915.753, \"y\": 6994734.337, \"z\": 120.62799999999697}]"
  //      val points5170057 = "[ {\"x\": 529099.946, \"y\": 6994655.993, \"z\": 121.2670000000071},{\"x\": 529090.588, \"y\": 6994659.353, \"z\": 121.22400000000198},{\"x\": 529065.713, \"y\": 6994668.98, \"z\": 121.1469999999972},{\"x\": 529037.245, \"y\": 6994680.687, \"z\": 121.11000000000058},{\"x\": 529015.841, \"y\": 6994689.617, \"z\": 121.03100000000268},{\"x\": 528994.723, \"y\": 6994698.806, \"z\": 120.93499999999767},{\"x\": 528982.934, \"y\": 6994703.835, \"z\": 120.9030000000057}]"
  //      val points5170208 = "[ {\"x\": 531208.529, \"y\": 6993930.35, \"z\": 113.57600000000093},{\"x\": 531206.956, \"y\": 6993930.852, \"z\": 113.52400000000489},{\"x\": 531206.551, \"y\": 6993930.982, \"z\": 113.51799999999639},{\"x\": 531152.258, \"y\": 6993947.596, \"z\": 112.50900000000547},{\"x\": 531097.601, \"y\": 6993961.148, \"z\": 111.63300000000163},{\"x\": 531035.674, \"y\": 6993974.085, \"z\": 111.00199999999313},{\"x\": 531000.05, \"y\": 6993980.598, \"z\": 110.81100000000151},{\"x\": 530972.845, \"y\": 6993985.159, \"z\": 110.65600000000268}]"
  //      val points5170419 = "[ {\"x\": 531580.116, \"y\": 6993791.375, \"z\": 113.05299999999988},{\"x\": 531559.788, \"y\": 6993798.928, \"z\": 113.63000000000466}]"
  //      val points5170105 = "[ {\"x\": 528699.202, \"y\": 6994841.305, \"z\": 119.86999999999534},{\"x\": 528679.331, \"y\": 6994852.48, \"z\": 119.7390000000014},{\"x\": 528655.278, \"y\": 6994865.047, \"z\": 119.68700000000536},{\"x\": 528627.407, \"y\": 6994880.448, \"z\": 119.5679999999993},{\"x\": 528605.245, \"y\": 6994891.79, \"z\": 119.5219999999972},{\"x\": 528580.964, \"y\": 6994906.041, \"z\": 119.48200000000361}]"
  //      val points5170278 = "[ {\"x\": 530685.408, \"y\": 6994033.6, \"z\": 112.65899999999965},{\"x\": 530681.24, \"y\": 6994034.74, \"z\": 112.66800000000512},{\"x\": 530639.419, \"y\": 6994047.211, \"z\": 113.10400000000664},{\"x\": 530635.275, \"y\": 6994048.447, \"z\": 113.14400000000023},{\"x\": 530624.882, \"y\": 6994051.624, \"z\": 113.22599999999511},{\"x\": 530603.496, \"y\": 6994059.168, \"z\": 113.48699999999371},{\"x\": 530570.252, \"y\": 6994070.562, \"z\": 113.73600000000442},{\"x\": 530537.929, \"y\": 6994083.499, \"z\": 114.09399999999732},{\"x\": 530512.29, \"y\": 6994094.305, \"z\": 114.38899999999558},{\"x\": 530508.822, \"y\": 6994095.977, \"z\": 114.39999999999418}]"
  //      val points5170104 = "[ {\"x\": 528833.042, \"y\": 6994773.324, \"z\": 120.32499999999709},{\"x\": 528806.698, \"y\": 6994786.487, \"z\": 120.21099999999569},{\"x\": 528778.343, \"y\": 6994800.373, \"z\": 120.12699999999313},{\"x\": 528754.485, \"y\": 6994812.492, \"z\": 120.03200000000652},{\"x\": 528728.694, \"y\": 6994826.297, \"z\": 119.9210000000021},{\"x\": 528710.804, \"y\": 6994835.775, \"z\": 119.86599999999453},{\"x\": 528700.208, \"y\": 6994840.792, \"z\": 119.8640000000014},{\"x\": 528699.202, \"y\": 6994841.305, \"z\": 119.86999999999534}]"
  //      val points5170250 = "[ {\"x\": 530972.845, \"y\": 6993985.159, \"z\": 110.65600000000268},{\"x\": 530934.626, \"y\": 6993989.73, \"z\": 110.67999999999302},{\"x\": 530884.749, \"y\": 6993996.905, \"z\": 110.87300000000687},{\"x\": 530849.172, \"y\": 6994001.746, \"z\": 111.07600000000093},{\"x\": 530787.464, \"y\": 6994011.154, \"z\": 111.68300000000454}]"
  //      val points5170274 = "[ {\"x\": 530508.822, \"y\": 6994095.977, \"z\": 114.39999999999418},{\"x\": 530492.408, \"y\": 6994103.892, \"z\": 114.60400000000664}]"
  //      val points5170253 = "[ {\"x\": 530787.464, \"y\": 6994011.154, \"z\": 111.68300000000454},{\"x\": 530735.969, \"y\": 6994021.569, \"z\": 112.14800000000105},{\"x\": 530685.408, \"y\": 6994033.6, \"z\": 112.65899999999965}]"
  //      val points5170071 = "[ {\"x\": 528915.753, \"y\": 6994734.337, \"z\": 120.62799999999697},{\"x\": 528870.534, \"y\": 6994755.246, \"z\": 120.46899999999732},{\"x\": 528853.387, \"y\": 6994763.382, \"z\": 120.41899999999441}]"
  //      val points5170200 = "[ {\"x\": 531515.135, \"y\": 6993815.644, \"z\": 114.74400000000605},{\"x\": 531490.088, \"y\": 6993825.357, \"z\": 115.1469999999972},{\"x\": 531434.788, \"y\": 6993847.717, \"z\": 115.81100000000151},{\"x\": 531382.827, \"y\": 6993867.291, \"z\": 115.9320000000007},{\"x\": 531341.785, \"y\": 6993883.123, \"z\": 115.70500000000175},{\"x\": 531279.229, \"y\": 6993906.106, \"z\": 114.83800000000338},{\"x\": 531263.983, \"y\": 6993911.659, \"z\": 114.55400000000373},{\"x\": 531244.769, \"y\": 6993918.512, \"z\": 114.25299999999697},{\"x\": 531235.891, \"y\": 6993921.64, \"z\": 114.028999999995},{\"x\": 531208.529, \"y\": 6993930.35, \"z\": 113.57600000000093}]"
  //      val points5167598 = "[ {\"x\": 528349.166, \"y\": 6995051.88, \"z\": 119.27599999999802},{\"x\": 528334.374, \"y\": 6995062.151, \"z\": 119.37900000000081},{\"x\": 528318.413, \"y\": 6995072.576, \"z\": 119.49800000000687},{\"x\": 528296.599, \"y\": 6995087.822, \"z\": 119.59200000000419},{\"x\": 528278.343, \"y\": 6995100.519, \"z\": 119.69999999999709},{\"x\": 528232.133, \"y\": 6995133.027, \"z\": 119.97299999999814},{\"x\": 528212.343, \"y\": 6995147.292, \"z\": 120.07700000000477},{\"x\": 528190.409, \"y\": 6995162.14, \"z\": 120.19000000000233},{\"x\": 528161.952, \"y\": 6995182.369, \"z\": 120.3579999999929},{\"x\": 528137.864, \"y\": 6995199.658, \"z\": 120.34200000000419},{\"x\": 528105.957, \"y\": 6995221.607, \"z\": 120.3530000000028}]"
  //      val points5170095 = "[ {\"x\": 528580.964, \"y\": 6994906.041, \"z\": 119.48200000000361},{\"x\": 528562.314, \"y\": 6994917.077, \"z\": 119.4030000000057},{\"x\": 528545.078, \"y\": 6994926.326, \"z\": 119.37200000000303},{\"x\": 528519.958, \"y\": 6994942.165, \"z\": 119.23099999999977},{\"x\": 528497.113, \"y\": 6994955.7, \"z\": 119.18600000000151},{\"x\": 528474.271, \"y\": 6994969.872, \"z\": 119.07200000000012},{\"x\": 528452.7, \"y\": 6994983.398, \"z\": 119.05400000000373},{\"x\": 528435.576, \"y\": 6994994.982, \"z\": 119.01900000000023},{\"x\": 528415.274, \"y\": 6995007.863, \"z\": 119.0460000000021},{\"x\": 528398.486, \"y\": 6995018.309, \"z\": 119.07399999999325},{\"x\": 528378.206, \"y\": 6995031.988, \"z\": 119.12799999999697},{\"x\": 528355.441, \"y\": 6995047.458, \"z\": 119.2390000000014},{\"x\": 528349.166, \"y\": 6995051.88, \"z\": 119.27599999999802}]"
  //      val points5170060 = "[ {\"x\": 528853.387, \"y\": 6994763.382, \"z\": 120.41899999999441},{\"x\": 528843.513, \"y\": 6994768.09, \"z\": 120.37399999999616},{\"x\": 528833.042, \"y\": 6994773.324, \"z\": 120.32499999999709}]"
  //      val points5169973 = "[ {\"x\": 530293.785, \"y\": 6994219.573, \"z\": 116.8070000000007},{\"x\": 530284.91, \"y\": 6994225.31, \"z\": 116.93399999999383},{\"x\": 530236.998, \"y\": 6994260.627, \"z\": 117.38700000000244},{\"x\": 530201.104, \"y\": 6994288.586, \"z\": 117.58599999999569},{\"x\": 530151.371, \"y\": 6994326.968, \"z\": 117.95799999999872},{\"x\": 530124.827, \"y\": 6994345.782, \"z\": 118.0399999999936},{\"x\": 530085.669, \"y\": 6994374.285, \"z\": 118.43399999999383},{\"x\": 530046.051, \"y\": 6994399.019, \"z\": 118.89900000000489},{\"x\": 530004.759, \"y\": 6994422.268, \"z\": 119.39900000000489}]"
  //      val points5170344 = "[ {\"x\": 531642.975, \"y\": 6993763.489, \"z\": 110.8579999999929},{\"x\": 531600.647, \"y\": 6993781.993, \"z\": 112.40600000000268},{\"x\": 531580.116, \"y\": 6993791.375, \"z\": 113.05299999999988}]"
  //      val points5170036 = "[ {\"x\": 530004.759, \"y\": 6994422.268, \"z\": 119.39900000000489},{\"x\": 529971.371, \"y\": 6994440.164, \"z\": 119.82799999999406},{\"x\": 529910.61, \"y\": 6994469.099, \"z\": 120.69400000000314},{\"x\": 529849.474, \"y\": 6994494.273, \"z\": 121.42600000000675},{\"x\": 529816.479, \"y\": 6994506.294, \"z\": 121.8350000000064},{\"x\": 529793.423, \"y\": 6994513.982, \"z\": 122.00699999999779},{\"x\": 529746.625, \"y\": 6994527.76, \"z\": 122.31900000000314},{\"x\": 529708.779, \"y\": 6994537.658, \"z\": 122.49700000000303},{\"x\": 529696.431, \"y\": 6994540.722, \"z\": 122.54099999999744},{\"x\": 529678.274, \"y\": 6994544.52, \"z\": 122.57200000000012},{\"x\": 529651.158, \"y\": 6994549.764, \"z\": 122.63700000000244},{\"x\": 529622.778, \"y\": 6994555.281, \"z\": 122.65899999999965},{\"x\": 529605.13, \"y\": 6994557.731, \"z\": 122.6929999999993},{\"x\": 529530.471, \"y\": 6994567.94, \"z\": 122.75500000000466},{\"x\": 529502.649, \"y\": 6994571.568, \"z\": 122.74199999999837}]"
  //      val points5170418 = "[ {\"x\": 531559.788, \"y\": 6993798.928, \"z\": 113.63000000000466},{\"x\": 531558.07, \"y\": 6993799.566, \"z\": 113.67799999999988},{\"x\": 531540.842, \"y\": 6993806.017, \"z\": 114.1530000000057}]"
  //      val points5170114 = "[ {\"x\": 532675.864, \"y\": 6993667.121, \"z\": 119.63899999999558},{\"x\": 532585, \"y\": 6993623.826, \"z\": 119.29899999999907},{\"x\": 532524.074, \"y\": 6993601.11, \"z\": 119.1420000000071},{\"x\": 532471.813, \"y\": 6993584.678, \"z\": 118.99300000000221},{\"x\": 532432.652, \"y\": 6993575.034, \"z\": 118.85099999999511},{\"x\": 532390.813, \"y\": 6993567.143, \"z\": 118.47699999999895},{\"x\": 532344.481, \"y\": 6993559.882, \"z\": 117.69999999999709},{\"x\": 532300.07, \"y\": 6993555.626, \"z\": 116.75400000000081},{\"x\": 532254.457, \"y\": 6993553.43, \"z\": 115.49499999999534},{\"x\": 532213.217, \"y\": 6993553.879, \"z\": 114.13999999999942},{\"x\": 532166.868, \"y\": 6993558.077, \"z\": 112.27599999999802},{\"x\": 532123.902, \"y\": 6993564.359, \"z\": 110.53599999999278},{\"x\": 532078.039, \"y\": 6993574.524, \"z\": 108.90499999999884},{\"x\": 532026.264, \"y\": 6993589.43, \"z\": 107.60099999999511},{\"x\": 531990.015, \"y\": 6993602.5, \"z\": 106.84299999999348},{\"x\": 531941.753, \"y\": 6993623.417, \"z\": 106.15499999999884},{\"x\": 531885.2, \"y\": 6993648.616, \"z\": 105.94100000000617},{\"x\": 531847.551, \"y\": 6993667.432, \"z\": 106.03100000000268},{\"x\": 531829.085, \"y\": 6993676.017, \"z\": 106.096000000005},{\"x\": 531826.495, \"y\": 6993677.286, \"z\": 106.17600000000675},{\"x\": 531795.338, \"y\": 6993692.819, \"z\": 106.59100000000035},{\"x\": 531750.277, \"y\": 6993714.432, \"z\": 107.46099999999569},{\"x\": 531702.109, \"y\": 6993736.085, \"z\": 108.73500000000058},{\"x\": 531652.731, \"y\": 6993759.226, \"z\": 110.49000000000524},{\"x\": 531642.975, \"y\": 6993763.489, \"z\": 110.8579999999929}]"
  //      val points5170266 = "[ {\"x\": 530330.275, \"y\": 6994195.603, \"z\": 116.37200000000303},{\"x\": 530328.819, \"y\": 6994196.919, \"z\": 116.34900000000198},{\"x\": 530293.785, \"y\": 6994219.573, \"z\": 116.8070000000007}]"
  //      val points5170076 = "[ {\"x\": 529502.649, \"y\": 6994571.568, \"z\": 122.74199999999837},{\"x\": 529488.539, \"y\": 6994573.408, \"z\": 122.75999999999476},{\"x\": 529461.147, \"y\": 6994576.534, \"z\": 122.63099999999395},{\"x\": 529432.538, \"y\": 6994579.398, \"z\": 122.49700000000303},{\"x\": 529402.112, \"y\": 6994583.517, \"z\": 122.36199999999371},{\"x\": 529383.649, \"y\": 6994585.553, \"z\": 122.22500000000582},{\"x\": 529366.46, \"y\": 6994587.58, \"z\": 122.16700000000128},{\"x\": 529340.392, \"y\": 6994591.142, \"z\": 122.0679999999993},{\"x\": 529316.184, \"y\": 6994596.203, \"z\": 121.92500000000291},{\"x\": 529292.004, \"y\": 6994600.827, \"z\": 121.79200000000128},{\"x\": 529274.998, \"y\": 6994603.419, \"z\": 121.74300000000221},{\"x\": 529245.538, \"y\": 6994610.622, \"z\": 121.74899999999616},{\"x\": 529215.54, \"y\": 6994618.628, \"z\": 121.68499999999767},{\"x\": 529200.025, \"y\": 6994623.205, \"z\": 121.58400000000256},{\"x\": 529182.346, \"y\": 6994628.596, \"z\": 121.5109999999986},{\"x\": 529172.437, \"y\": 6994631.118, \"z\": 121.50999999999476},{\"x\": 529169.924, \"y\": 6994631.929, \"z\": 121.52999999999884}]"
  //      val points5171309 = "[ {\"x\": 532675.864, \"y\": 6993667.121, \"z\": 119.63899999999558},{\"x\": 532683.902, \"y\": 6993675.669, \"z\": 119.55599999999686},{\"x\": 532705.617, \"y\": 6993689.231, \"z\": 119.68700000000536},{\"x\": 532738.146, \"y\": 6993711.117, \"z\": 120.0170000000071},{\"x\": 532746.793, \"y\": 6993717.431, \"z\": 120.10199999999895}]"
  //      val points5171311 = "[ {\"x\": 532746.793, \"y\": 6993717.431, \"z\": 120.10199999999895},{\"x\": 532772.872, \"y\": 6993736.47, \"z\": 120.65099999999802},{\"x\": 532796.699, \"y\": 6993755.46, \"z\": 121.12600000000384},{\"x\": 532823.779, \"y\": 6993779.309, \"z\": 121.846000000005},{\"x\": 532851.887, \"y\": 6993806.211, \"z\": 122.5},{\"x\": 532872.336, \"y\": 6993827.537, \"z\": 123.10000000000582},{\"x\": 532888.184, \"y\": 6993844.293, \"z\": 123.59900000000198}]"
  //      val points5171041 = "[ {\"x\": 532900.164, \"y\": 6993858.933, \"z\": 123.9600000000064},{\"x\": 532900.464, \"y\": 6993859.263, \"z\": 123.96099999999569},{\"x\": 532913.982, \"y\": 6993873.992, \"z\": 124.37900000000081},{\"x\": 532945.588, \"y\": 6993907.014, \"z\": 125.26499999999942},{\"x\": 532967.743, \"y\": 6993930.553, \"z\": 125.91099999999278}]"
  //      val points5171044 = "[ {\"x\": 532888.184, \"y\": 6993844.293, \"z\": 123.59900000000198},{\"x\": 532895.422, \"y\": 6993852.852, \"z\": 123.8179999999993},{\"x\": 532900.164, \"y\": 6993858.933, \"z\": 123.9600000000064}]"
  //      val points5171310 = "[ {\"x\": 532752.967, \"y\": 6993710.487, \"z\": 120.50299999999697},{\"x\": 532786.845, \"y\": 6993735.945, \"z\": 121.07200000000012},{\"x\": 532821.582, \"y\": 6993764.354, \"z\": 121.84900000000198},{\"x\": 532852.237, \"y\": 6993791.247, \"z\": 122.63400000000547},{\"x\": 532875.743, \"y\": 6993813.072, \"z\": 123.15700000000652},{\"x\": 532895.051, \"y\": 6993834.921, \"z\": 123.71400000000722}]"
  //      val points5171042 = "[ {\"x\": 532895.051, \"y\": 6993834.921, \"z\": 123.71400000000722},{\"x\": 532904.782, \"y\": 6993844.523, \"z\": 123.94599999999627},{\"x\": 532911.053, \"y\": 6993850.749, \"z\": 124.0789999999979}]"
  //      val points5171040 = "[ {\"x\": 532911.053, \"y\": 6993850.749, \"z\": 124.0789999999979},{\"x\": 532915.004, \"y\": 6993854.676, \"z\": 124.16400000000431},{\"x\": 532934.432, \"y\": 6993875.496, \"z\": 124.625},{\"x\": 532952.144, \"y\": 6993896.59, \"z\": 125.21799999999348},{\"x\": 532976.907, \"y\": 6993922.419, \"z\": 125.43700000000536}]"
  //      val points5171308 = "[ {\"x\": 532675.864, \"y\": 6993667.121, \"z\": 119.63899999999558},{\"x\": 532706.975, \"y\": 6993682.696, \"z\": 119.89999999999418},{\"x\": 532731.983, \"y\": 6993696.366, \"z\": 120.1820000000007},{\"x\": 532752.967, \"y\": 6993710.487, \"z\": 120.50299999999697}]"
  //
  //      val geom5170271 = toGeom(JSON.parseFull(points5170271))
  //      val geom5170414 = toGeom(JSON.parseFull(points5170414))
  //      val geom5170067 = toGeom(JSON.parseFull(points5170067))
  //      val geom5170066 = toGeom(JSON.parseFull(points5170066))
  //      val geom5170074 = toGeom(JSON.parseFull(points5170074))
  //      val geom5170057 = toGeom(JSON.parseFull(points5170057))
  //      val geom5170208 = toGeom(JSON.parseFull(points5170208))
  //      val geom5170419 = toGeom(JSON.parseFull(points5170419))
  //      val geom5170105 = toGeom(JSON.parseFull(points5170105))
  //      val geom5170278 = toGeom(JSON.parseFull(points5170278))
  //      val geom5170104 = toGeom(JSON.parseFull(points5170104))
  //      val geom5170250 = toGeom(JSON.parseFull(points5170250))
  //      val geom5170274 = toGeom(JSON.parseFull(points5170274))
  //      val geom5170253 = toGeom(JSON.parseFull(points5170253))
  //      val geom5170071 = toGeom(JSON.parseFull(points5170071))
  //      val geom5170200 = toGeom(JSON.parseFull(points5170200))
  //      val geom5167598 = toGeom(JSON.parseFull(points5167598))
  //      val geom5170095 = toGeom(JSON.parseFull(points5170095))
  //      val geom5170060 = toGeom(JSON.parseFull(points5170060))
  //      val geom5169973 = toGeom(JSON.parseFull(points5169973))
  //      val geom5170344 = toGeom(JSON.parseFull(points5170344))
  //      val geom5170036 = toGeom(JSON.parseFull(points5170036))
  //      val geom5170418 = toGeom(JSON.parseFull(points5170418))
  //      val geom5170114 = toGeom(JSON.parseFull(points5170114))
  //      val geom5170266 = toGeom(JSON.parseFull(points5170266))
  //      val geom5170076 = toGeom(JSON.parseFull(points5170076))
  //      val geom5171309 = toGeom(JSON.parseFull(points5171309))
  //      val geom5171311 = toGeom(JSON.parseFull(points5171311))
  //      val geom5171041 = toGeom(JSON.parseFull(points5171041))
  //      val geom5171044 = toGeom(JSON.parseFull(points5171044))
  //      val geom5171310 = toGeom(JSON.parseFull(points5171310))
  //      val geom5171042 = toGeom(JSON.parseFull(points5171042))
  //      val geom5171040 = toGeom(JSON.parseFull(points5171040))
  //      val geom5171308 = toGeom(JSON.parseFull(points5171308))
  //
  //      val mappedGeoms = Map(
  //        5170271l -> geom5170271,
  //        5170414l -> geom5170414,
  //        5170067l -> geom5170067,
  //        5170066l -> geom5170066,
  //        5170074l -> geom5170074,
  //        5170057l -> geom5170057,
  //        5170208l -> geom5170208,
  //        5170419l -> geom5170419,
  //        5170105l -> geom5170105,
  //        5170278l -> geom5170278,
  //        5170104l -> geom5170104,
  //        5170250l -> geom5170250,
  //        5170274l -> geom5170274,
  //        5170253l -> geom5170253,
  //        5170071l -> geom5170071,
  //        5170200l -> geom5170200,
  //        5167598l -> geom5167598,
  //        5170095l -> geom5170095,
  //        5170060l -> geom5170060,
  //        5169973l -> geom5169973,
  //        5170344l -> geom5170344,
  //        5170036l -> geom5170036,
  //        5170418l -> geom5170418,
  //        5170114l -> geom5170114,
  //        5170266l -> geom5170266,
  //        5170076l -> geom5170076,
  //        5171309l -> geom5171309,
  //        5171311l -> geom5171311,
  //        5171041l -> geom5171041,
  //        5171044l -> geom5171044,
  //        5171310l -> geom5171310,
  //        5171042l -> geom5171042,
  //        5171040l -> geom5171040,
  //        5171308l -> geom5171308
  //      )
  //
  //      //links.nonEmpty should be (true)
  //      val geomToLinks: Seq[ProjectLink] = linksBefore.map { l =>
  //        val geom = GeometryUtils.truncateGeometry2D(mappedGeoms(l.linkId), l.startMValue, l.endMValue)
  //        l.copy(geometry = geom,
  //          geometryLength = GeometryUtils.geometryLength(geom)
  //        )
  //      }
  //
  //      val points = "[{\"x\": 528105.957, \"y\": 6995221.607, \"z\": 120.3530000000028}," +
  //        "{\"x\": 528104.681, \"y\": 6995222.485, \"z\": 120.35099999999511}," +
  //        "{\"x\": 528064.931, \"y\": 6995249.45, \"z\": 120.18099999999686}," +
  //        "{\"x\": 528037.789, \"y\": 6995266.234, \"z\": 120.03100000000268}," +
  //        "{\"x\": 528008.332, \"y\": 6995285.521, \"z\": 119.8969999999972}," +
  //        "{\"x\": 527990.814, \"y\": 6995296.039, \"z\": 119.77300000000105}," +
  //        "{\"x\": 527962.009, \"y\": 6995313.215, \"z\": 119.57099999999627}," +
  //        "{\"x\": 527926.972, \"y\": 6995333.398, \"z\": 119.18799999999464}," +
  //        "{\"x\": 527890.962, \"y\": 6995352.332, \"z\": 118.82200000000012}," +
  //        "{\"x\": 527867.18, \"y\": 6995364.458, \"z\": 118.5219999999972}," +
  //        "{\"x\": 527843.803, \"y\": 6995376.389, \"z\": 118.35099999999511}," +
  //        "{\"x\": 527815.902, \"y\": 6995389.54, \"z\": 117.94599999999627}," +
  //        "{\"x\": 527789.731, \"y\": 6995401.53, \"z\": 117.6420000000071}," +
  //        "{\"x\": 527762.707, \"y\": 6995413.521, \"z\": 117.2960000000021}," +
  //        "{\"x\": 527737.556, \"y\": 6995424.518, \"z\": 117.09799999999814}," +
  //        "{\"x\": 527732.52, \"y\": 6995426.729, \"z\": 116.98600000000442}]"
  //
  //      val reversedPoints = "[{\"x\": 527732.52, \"y\": 6995426.729, \"z\": 116.98600000000442}," +
  //        "{\"x\": 527742.972, \"y\": 6995532.398, \"z\": 117.18799999999464}," +
  //        "{\"x\": 527752.52, \"y\": 6995555.729, \"z\": 118.98600000000442}]"
  //
  //      val geom = JSON.parseFull(points).get.asInstanceOf[List[Map[String, Double]]].map(m => Point(m("x"), m("y"), m("z")))
  //
  //      val reverserGeom = JSON.parseFull(reversedPoints).get.asInstanceOf[List[Map[String, Double]]].map(m => Point(m("x"), m("y"), m("z")))
  //
  //      val newLink = ProjectAddressLink(NewRoadAddress, 5167571, geom, GeometryUtils.geometryLength(geom),
  //        State, Motorway, RoadLinkType.NormalRoadLinkType, ConstructionType.InUse, LinkGeomSource.NormalLinkInterface,
  //        RoadType.PublicRoad, Some("X"), None, 749, None, None, Map.empty, 77, 35, 0L, 8L, 5L, 0L, 0L, 0.0, GeometryUtils.geometryLength(geom),
  //        SideCode.Unknown, None, None, Anomaly.None, LinkStatus.New, 0)
  //
  //      val newLink2 = ProjectAddressLink(NewRoadAddress, 5167559, reverserGeom, GeometryUtils.geometryLength(reverserGeom),
  //        State, Motorway, RoadLinkType.NormalRoadLinkType, ConstructionType.InUse, LinkGeomSource.NormalLinkInterface,
  //        RoadType.PublicRoad, Some("X"), None, 749, None, None, Map.empty, 77, 35, 0L, 8L, 5L, 0L, 0L, 0.0, GeometryUtils.geometryLength(reverserGeom),
  //        SideCode.Unknown, None, None, Anomaly.None, LinkStatus.New, 0)
  //      when(mockRoadLinkService.getRoadLinksHistoryFromVVH(any[Set[Long]])).thenReturn(Seq())
  //      when(mockRoadLinkService.getRoadLinksByLinkIdsFromVVH(any[Set[Long]], any[Boolean])).thenReturn(
  //        (Seq(newLink, newLink2).map(toRoadLink) ++ geomToLinks.map(toRoadLink)))
  //      projectService.addNewLinksToProject(Seq(backToProjectLink(rap)(newLink)), id, "U", newLink.linkId) should be(None)
  //
  //      val linksAfter = ProjectDAO.fetchByProjectRoadPart(77, 35, id)
  //      linksAfter should have size (linksBefore.size + 1)
  //      linksBefore.map(b => b -> linksAfter.find(_.id == b.id)).foreach { case (x, y) =>
  //        println(s"${x.linkId} ${x.startAddrMValue} ${x.endAddrMValue} ${x.sideCode} => ${y.map(l => l.startAddrMValue + " " + l.endAddrMValue + " " + l.sideCode)} ")
  //      }
  //      linksAfter.filterNot(la => {
  //        linksBefore.exists(lb => {
  //          lb.linkId == la.linkId && lb.sideCode.value == la.sideCode.value
  //        })
  //      }) should have size (1)
  //
  //      projectService.addNewLinksToProject(Seq(backToProjectLink(rap)(newLink2)), id, "U", newLink2.linkId) should be(None)
  //      val linksAfter2 = ProjectDAO.fetchByProjectRoadPart(77, 35, id)
  //      linksAfter2 should have size (linksBefore.size + 2)
  //      linksAfter2.head.linkId should be(5167559)
  //      linksAfter2.head.startAddrMValue should be(0)
  //      //Validate the new link sideCode
  //      linksAfter2.head.sideCode should be(TowardsDigitizing)
  //      //Validate second link sideCode
  //      linksAfter2.tail.head.sideCode should be(AgainstDigitizing)
  //    }
  //  }

  //TODO Will be implemented at VIITE-1541
  //  test("Project should not allow adding branching links") {
  //    runWithRollback {
  //      sqlu"DELETE FROM ROADWAY WHERE ROAD_NUMBER=75 AND ROAD_PART_NUMBER=2".execute
  //      val id = Sequences.nextViitePrimaryKeySeqValue
  //      val rap = RoadAddressProject(id, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("1901-01-01"), "TestUser", DateTime.parse("1901-01-01"), DateTime.now(), "Some additional info", List.empty, None)
  //      ProjectDAO.createRoadAddressProject(rap)
  //
  //      // Alternate geometries for these links
  //      val points5176552 = "[{\"x\":537869.292,\"y\":6997722.466,\"z\":110.39800000000105}," +
  //        "{\"x\":538290.056,\"y\":6998265.169,\"z\":85.4429999999993}]"
  //      val points5176512 = "[{\"x\":537152.306,\"y\":6996873.826,\"z\":108.27700000000186}," +
  //        "{\"x\":538290.056,\"y\":6998265.169,\"z\":85.4429999999993}]"
  //      val points5176584 = "[{\"x\":538290.056,\"y\":6998265.169,\"z\":85.4429999999993}," +
  //        "{\"x\":538418.3307786948,\"y\":6998426.422734798,\"z\":88.17597963771014}]"
  //      val geometries =
  //        Map(5176584 -> StaticTestData.toGeom(JSON.parseFull(points5176584)),
  //          5176552 -> StaticTestData.toGeom(JSON.parseFull(points5176552)),
  //          5176512 -> StaticTestData.toGeom(JSON.parseFull(points5176512)))
  //
  //      val addProjectAddressLink512 = ProjectAddressLink(NewRoadway, 5176512, geometries(5176512), GeometryUtils.geometryLength(geometries(5176512)),
  //        State, Motorway, ConstructionType.InUse, LinkGeomSource.NormalLinkInterface,
  //        RoadType.PublicRoad, Some("X"), None, 749, None, None, Map.empty, 75, 2, 0L, 8L, 5L, 0L, 0L, 0.0, GeometryUtils.geometryLength(geometries(5176512)),
  //        SideCode.TowardsDigitizing, None, None, Anomaly.None, LinkStatus.New, 0)
  //      val addProjectAddressLink552 = ProjectAddressLink(NewRoadway, 5176552, geometries(5176552), GeometryUtils.geometryLength(geometries(5176552)),
  //        State, Motorway,  ConstructionType.InUse, LinkGeomSource.NormalLinkInterface,
  //        RoadType.PublicRoad, Some("X"), None, 749, None, None, Map.empty, 75, 2, 0L, 8L, 5L, 0L, 0L, 0.0, GeometryUtils.geometryLength(geometries(5176552)),
  //        SideCode.TowardsDigitizing, None, None, Anomaly.None, LinkStatus.New, 0)
  //      val addProjectAddressLink584 = ProjectAddressLink(NewRoadway, 5176584, geometries(5176584), GeometryUtils.geometryLength(geometries(5176584)),
  //        State, Motorway,  ConstructionType.InUse, LinkGeomSource.NormalLinkInterface,
  //        RoadType.PublicRoad, Some("X"), None, 749, None, None, Map.empty, 75, 2, 0L, 8L, 5L, 0L, 0L, 0.0, GeometryUtils.geometryLength(geometries(5176584)),
  //        SideCode.TowardsDigitizing, None, None, Anomaly.None, LinkStatus.New, 0)
  //
  //      val addresses = Seq(addProjectAddressLink512, addProjectAddressLink552, addProjectAddressLink584)
  //      mockForProject(id, addresses)
  //      val links = addresses.map(backToProjectLink(rap))
  //      projectService.addNewLinksToProject(links, id, "U", links.minBy(_.endMValue).linkId) should be(Some("Valittu tiegeometria sisältää haarautumia ja pitää käsitellä osina. Tallennusta ei voi tehdä."))
  //      val readLinks = ProjectDAO.getProjectLinks(id)
  //      readLinks.size should be(0)
  //    }
  //  }

  //TODO Will be implemented at VIITE-1541
  //  test("Project links direction change shouldn't work due to unchanged links on road") {
  //    runWithRollback {
  //      sqlu"DELETE FROM ROADWAY WHERE ROAD_NUMBER=75 AND ROAD_PART_NUMBER=2".execute
  //      val id = Sequences.nextViitePrimaryKeySeqValue
  //      val rap = RoadAddressProject(id, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("1901-01-01"), "TestUser", DateTime.parse("1901-01-01"), DateTime.now(), "Some additional info", List.empty, None)
  //      ProjectDAO.createRoadAddressProject(rap)
  //
  //      val geometries = StaticTestData.mappedGeoms(Set(5176552, 5176512, 5176584))
  //      val geom512 = geometries(5176512)
  //      val geom552 = geometries(5176552)
  //
  //      val addProjectAddressLink512 = ProjectAddressLink(NewRoadway, 5176512, geom512, GeometryUtils.geometryLength(geom512),
  //        State, Motorway, ConstructionType.InUse, LinkGeomSource.NormalLinkInterface,
  //        RoadType.PublicRoad, Some("X"), None, 749, None, None, Map.empty, 75, 2, 0L, 8L, 5L, 0L, 0L, 0.0, GeometryUtils.geometryLength(geom512),
  //        SideCode.TowardsDigitizing, None, None, Anomaly.None, LinkStatus.New, 0)
  //      val addProjectAddressLink552 = ProjectAddressLink(NewRoadway, 5176552, geom552, GeometryUtils.geometryLength(geom552),
  //        State, Motorway, ConstructionType.InUse, LinkGeomSource.NormalLinkInterface,
  //        RoadType.PublicRoad, Some("X"), None, 749, None, None, Map.empty, 75, 2, 0L, 8L, 5L, 0L, 0L, 0.0, GeometryUtils.geometryLength(geom552),
  //        SideCode.TowardsDigitizing, None, None, Anomaly.None, LinkStatus.New, 0)
  //      val addresses = Seq(addProjectAddressLink512, addProjectAddressLink552)
  //      mockForProject(id, addresses)
  //      when(mockRoadLinkService.getRoadLinksHistoryFromVVH(any[Set[Long]])).thenReturn(Seq())
  //      when(mockRoadLinkService.getRoadLinksByLinkIdsFromVVH(addresses.map(_.linkId).toSet)).thenReturn(addresses.map(toRoadLink))
  //      projectService.addNewLinksToProject(addresses.map(backToProjectLink(rap)), id, "U", addresses.minBy(_.endMValue).linkId) should be(None)
  //      val links = ProjectDAO.getProjectLinks(id)
  //      ProjectDAO.updateProjectLinks(Set(links.head.id), LinkStatus.UnChanged, "test")
  //      links.map(_.linkId).toSet should be(addresses.map(_.linkId).toSet)
  //      val result = projectService.changeDirection(id, 75, 2, links.map(l => LinkToRevert(l.id, l.linkId, l.status.value, l.geometry)), "testuser")
  //      result should be(Some("Tieosalle ei voi tehdä kasvusuunnan kääntöä, koska tieosalla on linkkejä, joita ei ole käsitelty tai jotka on tässä projektissa määritelty säilymään ennallaan."))
  //    }
  //  }


  //TODO Will be implemented at VIITE-1541
  //  test("Project link direction change should remain after adding new links") {
  //    runWithRollback {
  //      sqlu"DELETE FROM ROADWAY WHERE ROAD_NUMBER=75 AND ROAD_PART_NUMBER=2".execute
  //      val id = Sequences.nextViitePrimaryKeySeqValue
  //      val rap = RoadAddressProject(id, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("1901-01-01"), "TestUser", DateTime.parse("1901-01-01"), DateTime.now(), "Some additional info", List.empty, None)
  //      ProjectDAO.createRoadAddressProject(rap)
  //
  //      val points5176552 = "[{\"x\":537869.292,\"y\":6997722.466,\"z\":110.39800000000105}," +
  //        "{\"x\":538290.056,\"y\":6998265.169,\"z\":85.4429999999993}]"
  //      val points5176512 = "[{\"x\":537152.306,\"y\":6996873.826,\"z\":108.27700000000186}," +
  //        "{\"x\":537869.292,\"y\":6997722.466,\"z\":110.39800000000105}]"
  //      val oldgeom512 = JSON.parseFull(points5176512).get.asInstanceOf[List[Map[String, Double]]].map(m => Point(m("x"), m("y"), m("z")))
  //      val oldgeom552 = JSON.parseFull(points5176552).get.asInstanceOf[List[Map[String, Double]]].map(m => Point(m("x"), m("y"), m("z")))
  //
  //      val geometries = StaticTestData.mappedGeoms(Set(5176552, 5176512))
  //      val geom512 = geometries(5176512)
  //      val geom552 = geometries(5176552)
  //
  //      oldgeom512 should be(geom512)
  //      oldgeom552 should be(geom552)
  //      val addProjectAddressLink512 = ProjectAddressLink(NewRoadway, 5176512, geom512, GeometryUtils.geometryLength(geom512),
  //        State, Motorway, ConstructionType.InUse, LinkGeomSource.NormalLinkInterface,
  //        RoadType.PublicRoad, Some("X"), None, 749, None, None, Map.empty, 75, 2, 0L, 8L, 5L, 0L, 0L, 0.0, GeometryUtils.geometryLength(geom512),
  //        SideCode.TowardsDigitizing, None, None, Anomaly.None, LinkStatus.New, 0)
  //      val addProjectAddressLink552 = ProjectAddressLink(NewRoadway, 5176552, geom552, GeometryUtils.geometryLength(geom552),
  //        State, Motorway, ConstructionType.InUse, LinkGeomSource.NormalLinkInterface,
  //        RoadType.PublicRoad, Some("X"), None, 749, None, None, Map.empty, 75, 2, 0L, 8L, 5L, 0L, 0L, 0.0, GeometryUtils.geometryLength(geom552),
  //        SideCode.TowardsDigitizing, None, None, Anomaly.None, LinkStatus.New, 0)
  //      val addresses = Seq(addProjectAddressLink512, addProjectAddressLink552)
  //      mockForProject(id, addresses)
  //      val newLinks = addresses.map(backToProjectLink(rap)).map(_.copy(status = LinkStatus.New))
  //      projectService.addNewLinksToProject(newLinks, id, "U", addresses.minBy(_.endMValue).linkId) should be(None)
  //      val links = ProjectDAO.getProjectLinks(id)
  //      links.map(_.linkId).toSet should be(addresses.map(_.linkId).toSet)
  //      val sideCodes = links.map(l => l.id -> l.sideCode).toMap
  //      projectService.updateProjectLinks(id, Set(), addresses.map(_.linkId), LinkStatus.New, "Test", 75, 2, 0, None)
  //      projectService.changeDirection(id, 75, 2, links.map(l => LinkToRevert(l.id, l.linkId, l.status.value, l.geometry)), "testuser") should be(None)
  //      val changedLinks = ProjectDAO.getProjectLinksByIds(links.map { l => l.id })
  //      changedLinks.foreach(cl => cl.sideCode should not be (sideCodes(cl.id)))
  //      changedLinks.foreach(cl => cl.reversed should be(true))
  //      val geom584 = StaticTestData.mappedGeoms(Seq(5176584L)).values.head
  //      val addProjectAddressLink584 = ProjectAddressLink(NewRoadway, 5176584, geom584, GeometryUtils.geometryLength(geom584),
  //        State, Motorway, ConstructionType.InUse, LinkGeomSource.NormalLinkInterface,
  //        RoadType.PublicRoad, Some("X"), None, 749, None, None, Map.empty, 75, 2, 0L, 8L, 5L, 0L, 0L, 0.0, GeometryUtils.geometryLength(geom584),
  //        SideCode.TowardsDigitizing, None, None, Anomaly.None, LinkStatus.New, 0)
  //      when(mockRoadLinkService.getRoadLinksHistoryFromVVH(any[Set[Long]])).thenReturn(Seq())
  //      when(mockRoadLinkService.getRoadLinksByLinkIdsFromVVH(addresses.map(_.linkId).toSet)).thenReturn(addresses.map(toRoadLink))
  //      mockForProject(id, addresses ++ Seq(addProjectAddressLink584))
  //      projectService.addNewLinksToProject(Seq(backToProjectLink(rap)(addProjectAddressLink584).copy(status = LinkStatus.New)),
  //        id, "U", addProjectAddressLink584.linkId) should be(None)
  //
  //      val linksAfter = ProjectDAO.getProjectLinks(id).sortBy(_.startAddrMValue)
  //      linksAfter should have size (links.size + 1)
  //      linksAfter.find(_.linkId == 5176512).get.sideCode should be(changedLinks.find(_.linkId == 5176512).get.sideCode)
  //      linksAfter.find(_.linkId == 5176552).get.sideCode should be(changedLinks.find(_.linkId == 5176552).get.sideCode)
  //      linksAfter.find(_.linkId == addProjectAddressLink584.linkId).map(_.sideCode) should be(Some(SideCode.TowardsDigitizing))
  //      linksAfter.head.startAddrMValue should be(0)
  //      linksAfter.head.endAddrMValue > linksAfter.head.startAddrMValue should be(true)
  //      linksAfter.tail.head.startAddrMValue == linksAfter.head.endAddrMValue should be(true)
  //      linksAfter.tail.head.endAddrMValue == linksAfter.tail.last.startAddrMValue should be(true)
  //    }
  //  }

  //TODO this will be implemented at VIITE-1541
  //  test("New Project link (Uusi) update of road_number, road_part_number and TRACK") {
  //    runWithRollback {
  //      sqlu"DELETE FROM ROADWAY WHERE ROAD_NUMBER=75 AND ROAD_PART_NUMBER=2".execute
  //      val id = Sequences.nextViitePrimaryKeySeqValue
  //      val rap = RoadAddressProject(id, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("1901-01-01"), "TestUser", DateTime.parse("1901-01-01"), DateTime.now(), "Some additional info", List.empty, None)
  //      ProjectDAO.createRoadAddressProject(rap)
  //
  //      val points5176552 = "[{\"x\":537869.292,\"y\":6997722.466,\"z\":110.39800000000105}," +
  //        "{\"x\":538290.056,\"y\":6998265.169,\"z\":85.4429999999993}]"
  //      val points5176512 = "[{\"x\":537152.306,\"y\":6996873.826,\"z\":108.27700000000186}," +
  //        "{\"x\":537869.292,\"y\":6997722.466,\"z\":110.39800000000105}]"
  //      val oldgeom512 = JSON.parseFull(points5176512).get.asInstanceOf[List[Map[String, Double]]].map(m => Point(m("x"), m("y"), m("z")))
  //      val oldgeom552 = JSON.parseFull(points5176552).get.asInstanceOf[List[Map[String, Double]]].map(m => Point(m("x"), m("y"), m("z")))
  //
  //      val geometries = StaticTestData.mappedGeoms(Set(5176552, 5176512))
  //      val geom512 = geometries(5176512)
  //      val geom552 = geometries(5176552)
  //
  //      oldgeom512 should be(geom512)
  //      oldgeom552 should be(geom552)
  //      val addProjectAddressLink512 = ProjectAddressLink(NewRoadAddress, 5176512, geom512, GeometryUtils.geometryLength(geom512),
  //        State, Motorway, RoadLinkType.NormalRoadLinkType, ConstructionType.InUse, LinkGeomSource.NormalLinkInterface,
  //        RoadType.PublicRoad, Some("X"), None, 749, None, None, Map.empty, 75, 2, 0L, 8L, 5L, 0L, 0L, 0.0, GeometryUtils.geometryLength(geom512),
  //        SideCode.TowardsDigitizing, None, None, Anomaly.None, LinkStatus.New, RoadAddressDAO.getNextRoadwayId)
  //      val addProjectAddressLink552 = ProjectAddressLink(NewRoadAddress, 5176552, geom552, GeometryUtils.geometryLength(geom552),
  //        State, Motorway, RoadLinkType.NormalRoadLinkType, ConstructionType.InUse, LinkGeomSource.NormalLinkInterface,
  //        RoadType.PublicRoad, Some("X"), None, 749, None, None, Map.empty, 75, 2, 0L, 8L, 5L, 0L, 0L, 0.0, GeometryUtils.geometryLength(geom552),
  //        SideCode.TowardsDigitizing, None, None, Anomaly.None, LinkStatus.New, RoadAddressDAO.getNextRoadwayId)
  //      val addresses = Seq(addProjectAddressLink512, addProjectAddressLink552)
  //      mockForProject(id, addresses)
  //      projectService.addNewLinksToProject(addresses.map(addressToProjectLink(rap)), id, "U", addresses.minBy(_.endMValue).linkId, false) should be(None)
  //      val links = ProjectDAO.getProjectLinks(id)
  //      val updated = projectService.updateProjectLinks(id, Set(), links.map(_.linkId), LinkStatus.New, "test", 123456, 1, 0, None,
  //        RoadType.FerryRoad.value, Discontinuity.EndOfRoad.value, Some(8), false)
  //      val linksAfterUpdate = ProjectDAO.getProjectLinks(id)
  //      val firstLink = linksAfterUpdate.head
  //      firstLink.roadNumber should be(123456)
  //      firstLink.roadPartNumber should be(1)
  //      firstLink.track.value should be(0)
  //    }
  //  }

  //TODO this will be implemented at VIITE-1541
  //  test("get change table test with update change table on every road link change") {
  //    var count = 0
  //    val roadLink = RoadLink(5170939L, Seq(Point(535605.272, 6982204.22, 85.90899999999965))
  //      , 540.3960283713503, State, 99, TrafficDirection.AgainstDigitizing, UnknownLinkType, Some("25.06.2015 03:00:00"), Some("vvh_modified"), Map("MUNICIPALITYCODE" -> BigInt.apply(749)),
  //      InUse, NormalLinkInterface)
  //    runWithRollback {
  //      val countCurrentProjects = projectService.getRoadAddressAllProjects
  //      val id = 0
  //      val addresses = List(ReservedRoadPart(Sequences.nextViitePrimaryKeySeqValue: Long, 5: Long, 205: Long, Some(5L), Some(Discontinuity.apply("jatkuva")), Some(8L), newLength = None, newDiscontinuity = None, newEly = None),
  //        ReservedRoadPart(Sequences.nextViitePrimaryKeySeqValue: Long, 5: Long, 206: Long, Some(5L), Some(Discontinuity.apply("jatkuva")), Some(8L), newLength = None, newDiscontinuity = None, newEly = None))
  //      val roadAddressProject = RoadAddressProject(id, ProjectState.apply(1), "TestProject", "TestUser", DateTime.now(), "TestUser", DateTime.parse("1901-01-01"), DateTime.now(), "Some additional info", Seq(), None)
  //      val saved = projectService.createRoadLinkProject(roadAddressProject)
  //      mockForProject(saved.id, (RoadAddressDAO.fetchByRoadPart(5, 205) ++ RoadAddressDAO.fetchByRoadPart(5, 206)).map(toProjectLink(saved)))
  //      projectService.saveProject(saved.copy(reservedParts = addresses))
  //      val afterSaveProject = projectService.getRoadAddressSingleProject(saved.id).get
  //      afterSaveProject.reservedParts should have size (2)
  //      val countAfterInsertProjects = projectService.getRoadAddressAllProjects
  //      count = countCurrentProjects.size + 1
  //      countAfterInsertProjects.size should be(count)
  //      projectService.isProjectPublishable(saved.id) should be(false)
  //      val projectLinks = ProjectDAO.getProjectLinks(saved.id)
  //      val partitioned = projectLinks.partition(_.roadPartNumber == 205)
  //      val linkIds205 = partitioned._1.map(_.linkId).toSet
  //      val linkIds206 = partitioned._2.map(_.linkId).toSet
  //      reset(mockRoadLinkService)
  //      when(mockRoadLinkService.getRoadLinksHistoryFromVVH(any[Set[Long]])).thenReturn(Seq())
  //      when(mockRoadLinkService.getRoadLinksByLinkIdsFromVVH(any[Set[Long]], any[Boolean])).thenAnswer(
  //        toMockAnswer(projectLinks, roadLink)
  //      )
  //
  //      projectService.updateProjectLinks(saved.id, Set(), linkIds205.toSeq, LinkStatus.Terminated, "-", 0, 0, 0, Option.empty[Int])
  //      projectService.isProjectPublishable(saved.id) should be(false)
  //
  //
  //      projectService.updateProjectLinks(saved.id, Set(), linkIds206.toSeq, LinkStatus.Terminated, "-", 0, 0, 0, Option.empty[Int])
  //      projectService.isProjectPublishable(saved.id) should be(true)
  //
  //      val changeProjectOpt = projectService.getChangeProjectInTX(saved.id)
  //      changeProjectOpt.map(_.changeInfoSeq).getOrElse(Seq()) should have size (5)
  //
  //      val change = changeProjectOpt.get
  //
  //      change.changeDate should be(roadAddressProject.startDate.toString("YYYY-MM-DD"))
  //      change.ely should be(8)
  //      change.user should be("TestUser")
  //      change.name should be("TestProject")
  //      change.changeInfoSeq.foreach(rac => {
  //        val s = rac.source
  //        val t = rac.target
  //        val (sTie, sAosa, sAjr, sAet, sLet) = (s.roadNumber, s.startRoadPartNumber, s.trackCode, s.startAddressM, s.endAddressM)
  //        val (tTie, tAosa, tAjr, tAet, tLet) = (t.roadNumber, t.startRoadPartNumber, t.trackCode, t.startAddressM, t.endAddressM)
  //        sTie should be(Some(5))
  //        sAosa.isEmpty should be(false)
  //        sAjr.isEmpty should be(false)
  //        sAet.isEmpty should be(false)
  //        sLet.isEmpty should be(false)
  //        tTie should be(None)
  //        tAosa.isEmpty should be(true)
  //        tAjr.isEmpty should be(true)
  //        tAet.isEmpty should be(true)
  //        tLet.isEmpty should be(true)
  //      })
  //
  //      change.changeInfoSeq.foreach(_.changeType should be(Termination))
  //      change.changeInfoSeq.foreach(_.discontinuity should be(Discontinuity.Continuous))
  //
  //      // TODO: When road types are properly generated
  //      //      change.changeInfoSeq.foreach(_.roadType should be(RoadType.UnknownOwnerRoad))
  //    }
  //    runWithRollback {
  //      projectService.getRoadAddressAllProjects
  //    } should have size (count - 1)
  //  }

  //TODO Will be implemented with SPLIT
  //  test("Split and revert links") {
  //    runWithRollback {
  //      val roadLink = RoadLink(1, Seq(Point(0, 0), Point(0, 45.3), Point(0, 87))
  //        , 87.0, State, 99, TrafficDirection.AgainstDigitizing, UnknownLinkType, Some("25.06.2015 03:00:00"),
  //        Some("vvh_modified"), Map("MUNICIPALITYCODE" -> BigInt.apply(749)),
  //        InUse, NormalLinkInterface)
  //      val suravageAddressLink = RoadLink(2, Seq(Point(0, 0), Point(0, 45.3), Point(0, 123)), 123,
  //        State, 99, TrafficDirection.AgainstDigitizing, UnknownLinkType, Some("25.06.2015 03:00:00"),
  //        Some("vvh_modified"), Map("MUNICIPALITYCODE" -> BigInt.apply(749)),
  //        InUse, LinkGeomSource.SuravageLinkInterface)
  //      when(mockRoadLinkService.getSuravageRoadLinksByLinkIdsFromVVH(any[Set[Long]])).thenReturn(Seq(suravageAddressLink))
  //      when(mockRoadLinkService.getRoadLinksAndComplementaryFromVVH(any[BoundingRectangle], any[Set[Int]])).thenReturn(Seq(roadLink))
  //      when(mockRoadLinkService.getCurrentAndHistoryRoadLinksFromVVH(any[Set[Long]], any[Boolean])).thenReturn((Seq(roadLink), Seq()))
  //      when(mockRoadLinkService.getRoadLinkByLinkIdFromVVH(any[Long])).thenReturn(Some(roadLink))
  //      when(mockRoadLinkService.getCurrentAndComplementaryAndSuravageRoadLinksFromVVH(any[Set[Long]], any[Boolean])).thenReturn(Seq(roadLink, suravageAddressLink))
  //      val rap = createProjectDataForSplit()
  //      val options = SplitOptions(Point(0, 25.3), LinkStatus.UnChanged, LinkStatus.New, 1, 1, Track.Combined, Discontinuity.Continuous,
  //        1, LinkGeomSource.NormalLinkInterface, RoadType.PublicRoad, rap.id, ProjectCoordinates(0,0,0))
  //      val errorOpt = projectServiceWithRoadAddressMock.splitSuravageLinkInTX(suravageAddressLink.linkId, "testUser", options)
  //      errorOpt should be(None)
  //      val projectLinks = ProjectDAO.getProjectLinks(rap.id)
  //      checkSplit(projectLinks, 25.3, 0, 25, 87, 123.0)
  //      val options2 = SplitOptions(Point(0, 65.3), LinkStatus.Transfer, LinkStatus.New, 1, 1, Track.Combined,
  //        Discontinuity.Continuous, 1, LinkGeomSource.NormalLinkInterface, RoadType.PublicRoad, rap.id, ProjectCoordinates(0,0,0))
  //      val preSplitData = projectServiceWithRoadAddressMock.preSplitSuravageLinkInTX(suravageAddressLink.linkId, "testUser", options2)._1.map(rs => rs.toSeqWithMergeTerminated).getOrElse(Seq())
  //      preSplitData should have size (3)
  //      // Test that the transfer is not returned back in pre-split for already split suravage but the old values are
  //      preSplitData.exists(_.status == Transfer) should be (false)
  //      projectServiceWithRoadAddressMock.revertSplit(rap.id, 1, "user") should be (None)
  //      ProjectDAO.getProjectLinks(rap.id) should have size (1)
  //    }
  //  }

  //TODO Will be implemented with SPLIT
  //  test("Updating split link test") {
  //    runWithRollback {
  //      val roadLink = RoadLink(1, Seq(Point(0, 0), Point(0, 45.3), Point(0, 87))
  //        , 87.0, State, 99, TrafficDirection.AgainstDigitizing, UnknownLinkType, Some("25.06.2015 03:00:00"),
  //        Some("vvh_modified"), Map("MUNICIPALITYCODE" -> BigInt.apply(749)),
  //        InUse, NormalLinkInterface)
  //      val suravageAddressLink = RoadLink(2, Seq(Point(0, 0), Point(0, 45.3), Point(0, 123)), 123,
  //        State, 99, TrafficDirection.AgainstDigitizing, UnknownLinkType, Some("25.06.2015 03:00:00"),
  //        Some("vvh_modified"), Map("MUNICIPALITYCODE" -> BigInt.apply(749)),
  //        InUse, LinkGeomSource.SuravageLinkInterface)
  //      when(mockRoadLinkService.getSuravageRoadLinksByLinkIdsFromVVH(any[Set[Long]])).thenReturn(Seq(suravageAddressLink))
  //      when(mockRoadLinkService.getRoadLinksAndComplementaryFromVVH(any[BoundingRectangle], any[Set[Int]])).thenReturn(Seq(roadLink))
  //      when(mockRoadLinkService.getCurrentAndHistoryRoadLinksFromVVH(any[Set[Long]], any[Boolean])).thenReturn((Seq(roadLink), Seq()))
  //      when(mockRoadLinkService.getRoadLinkByLinkIdFromVVH(any[Long])).thenReturn(Some(roadLink))
  //      when(mockRoadLinkService.getCurrentAndComplementaryAndSuravageRoadLinksFromVVH(any[Set[Long]], any[Boolean])).thenReturn(Seq(roadLink, suravageAddressLink))
  //      val rap = createProjectDataForSplit()
  //      val options = SplitOptions(Point(0, 25.3), LinkStatus.UnChanged, LinkStatus.New, 1, 1, Track.Combined, Discontinuity.Continuous,
  //        1, LinkGeomSource.NormalLinkInterface, RoadType.PublicRoad, rap.id, ProjectCoordinates(0,0,0))
  //      val errorOpt = projectServiceWithRoadAddressMock.splitSuravageLinkInTX(suravageAddressLink.linkId, "testUser", options)
  //      errorOpt should be(None)
  //      val projectLinks = ProjectDAO.getProjectLinks(rap.id)
  //      checkSplit(projectLinks, 25.3, 0, 25, 87, 123.0)
  //      val options2 = SplitOptions(Point(0, 45.3), LinkStatus.UnChanged, LinkStatus.New, 1, 1, Track.Combined,
  //        Discontinuity.Continuous, 1, LinkGeomSource.NormalLinkInterface, RoadType.PublicRoad, rap.id, ProjectCoordinates(0,0,0))
  //      projectServiceWithRoadAddressMock.splitSuravageLinkInTX(suravageAddressLink.linkId, "testUser", options2) should be (None)
  //      checkSplit(ProjectDAO.getProjectLinks(rap.id), 45.3, 0, 45, 87, 123.0)
  //      ProjectDAO.getProjectLinks(rap.id).exists(_.status == LinkStatus.Transfer) should be (false)
  //    }
  //  }

  test("Test projectService.updateProjectLinks() When the project link to update already has a calibration point associated with it Then no user defined calibration points should be created.") {
    runWithRollback {
      val rap = Project(0L, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("1901-01-01"),
        "TestUser", DateTime.parse("1901-01-01"), DateTime.now(), "Some additional info",
        Seq(), None)
      val newLink = Seq(ProjectLink(-1000L, 9999L, 1L, Track.apply(0), Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None,
        None, 12345L, 0.0, 43.1, SideCode.Unknown, (None, None), NoFloating,
        Seq(Point(468.5, 0.5), Point(512.0, 0.0)), 0L, LinkStatus.Unknown, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, 43.1, 0L, 0, 0, reversed = false,
        None, 86400L))
      val project = projectService.createRoadLinkProject(rap)
      when(mockRoadLinkService.getRoadLinksByLinkIdsFromVVH(any[Set[Long]], any[Boolean])).thenReturn(newLink.map(toRoadLink))
      val createdLink = projectService.createProjectLinks(Seq(12345L), project.id, 9999, 1, Track.Combined, Discontinuity.Continuous, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, 8L, "test", "road name")
      createdLink.get("success").get.asInstanceOf[Boolean] should be(true)
      val updatedLink = projectLinkDAO.fetchProjectLinksByLinkId(Seq(12345L))
      projectService.updateProjectLinks(project.id, Set(updatedLink.head.id), Seq(), updatedLink.head.status, updatedLink.head.createdBy.get, updatedLink.head.roadNumber, updatedLink.head.roadPartNumber, updatedLink.head.track.value, Some(updatedLink.head.endAddrMValue.toInt), updatedLink.head.roadType.value, updatedLink.head.discontinuity.value) should be(None)
      val userDefinedCalibrationPoints = CalibrationPointDAO.fetchByRoadPart(project.id, updatedLink.head.roadNumber, updatedLink.head.roadPartNumber)
      userDefinedCalibrationPoints.size should be (0)
    }
  }
}

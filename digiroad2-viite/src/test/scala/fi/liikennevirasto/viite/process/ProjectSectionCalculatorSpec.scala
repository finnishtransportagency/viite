package fi.liikennevirasto.viite.process

import fi.liikennevirasto.digiroad2.asset.SideCode.{AgainstDigitizing, TowardsDigitizing}
import fi.liikennevirasto.digiroad2.{DigiroadEventBus, Point}
import fi.liikennevirasto.digiroad2.asset.{LinkGeomSource, SideCode}
import fi.liikennevirasto.digiroad2.dao.Sequences
import fi.liikennevirasto.digiroad2.oracle.OracleDatabase
import fi.liikennevirasto.digiroad2.service.RoadLinkService
import fi.liikennevirasto.digiroad2.util.Track
import fi.liikennevirasto.viite.dao.Discontinuity.{Continuous, Discontinuous, EndOfRoad, MinorDiscontinuity}
import fi.liikennevirasto.viite._
import fi.liikennevirasto.viite.dao.CalibrationPointSource.ProjectLinkSource
import fi.liikennevirasto.viite.dao.TerminationCode.NoTermination
import fi.liikennevirasto.viite.dao.{LinkStatus, _}
import org.joda.time.DateTime
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{FunSuite, Matchers}
import fi.liikennevirasto.viite.util._
import slick.driver.JdbcDriver.backend.Database
import slick.driver.JdbcDriver.backend.Database.dynamicSession

class ProjectSectionCalculatorSpec extends FunSuite with Matchers {
  val mockRoadLinkService: RoadLinkService = MockitoSugar.mock[RoadLinkService]
  val mockNodesAndJunctionsService = MockitoSugar.mock[NodesAndJunctionsService]
  val mockEventBus: DigiroadEventBus = MockitoSugar.mock[DigiroadEventBus]
  val mockRoadwayAddressMapper: RoadwayAddressMapper = MockitoSugar.mock[RoadwayAddressMapper]
  val projectDAO = new ProjectDAO
  val projectReservedPartDAO = new ProjectReservedPartDAO
  val projectLinkDAO = new ProjectLinkDAO
  val roadwayPointDAO = new RoadwayPointDAO
  val nodeDAO = new NodeDAO
  val nodePointDAO = new NodePointDAO
  val junctionDAO = new JunctionDAO
  val junctionPointDAO = new JunctionPointDAO
  val roadwayChangesDAO = new RoadwayChangesDAO
  val roadwayDAO = new RoadwayDAO
  val linearLocationDAO = new LinearLocationDAO
  val roadNetworkDAO = new RoadNetworkDAO
  val roadwayAddressMapper = new RoadwayAddressMapper(roadwayDAO, linearLocationDAO)
  val roadAddressService: RoadAddressService {
  } = new RoadAddressService(mockRoadLinkService, roadwayDAO, linearLocationDAO, roadNetworkDAO, roadwayPointDAO, nodePointDAO, junctionPointDAO, mockRoadwayAddressMapper, mockEventBus) {
    override def withDynSession[T](f: => T): T = f

    override def withDynTransaction[T](f: => T): T = f
  }
  val projectService: ProjectService = new ProjectService(roadAddressService, mockRoadLinkService, mockNodesAndJunctionsService, roadwayDAO,
    roadwayPointDAO, linearLocationDAO, projectDAO, projectLinkDAO,
    nodeDAO, nodePointDAO, junctionPointDAO, projectReservedPartDAO, roadwayChangesDAO,
    roadwayAddressMapper, mockEventBus) {
    override def withDynSession[T](f: => T): T = f

    override def withDynTransaction[T](f: => T): T = f
  }

  def runWithRollback[T](f: => T): T = {
    Database.forDataSource(OracleDatabase.ds).withDynTransaction {
      val t = f
      dynamicSession.rollback()
      t
    }
  }

  val projectId = 1
  val rap = Project(projectId, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("2700-01-01"),
    "TestUser", DateTime.parse("1972-03-03"), DateTime.parse("2700-01-01"), "Some additional info",
    List.empty[ProjectReservedPart], Seq(), None)

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

  test("Test assignMValues When assigning values for new road addresses Then values should be properly assigned") {
    runWithRollback {
      val idRoad0 = 0L
      val idRoad1 = 1L
      val idRoad2 = 2L
      val idRoad3 = 3L
      val projectLink0 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad0, 0, 5, 1, RoadType.Unknown, Track.Combined, Continuous,
        0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), 12345L, 0.0, 9.8, SideCode.TowardsDigitizing,
        0, (None, None), Seq(Point(0.0, 0.0), Point(0.0, 9.8)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink1 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad1, 0, 5, 1, RoadType.Unknown, Track.Combined, Continuous,
        0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), 12346L, 0.0, 9.8, SideCode.TowardsDigitizing,
        0, (None, None), Seq(Point(0.0, 30.0), Point(0.0, 39.8)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink2 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad2, 0, 5, 1, RoadType.Unknown, Track.Combined, Continuous,
        0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), 12347L, 0.0, 9.8, SideCode.TowardsDigitizing,
        0, (None, None), Seq(Point(0.0, 20.2), Point(0.0, 30.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink3 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad3, 0, 5, 1, RoadType.Unknown, Track.Combined, Continuous,
        0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), 12348L, 0.0, 10.4, SideCode.TowardsDigitizing,
        0, (None, None), Seq(Point(0.0, 9.8), Point(0.0, 20.2)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))

      val projectLinkSeq = Seq(projectLink0, projectLink1, projectLink2, projectLink3)
      val output = ProjectSectionCalculator.assignMValues(projectLinkSeq)
      output.length should be(4)
      output.foreach(o =>
        o.sideCode == SideCode.TowardsDigitizing || o.id == idRoad1 && o.sideCode == SideCode.AgainstDigitizing should be(true)
      )
      output(3).id should be(idRoad1)
      output(3).startMValue should be(0.0)
      output(3).endMValue should be(output(3).geometryLength +- 0.001)
      output(3).startAddrMValue should be(30L)
      output(3).endAddrMValue should be(40L)

      output(2).id should be(idRoad2)
      output(2).startMValue should be(0.0)
      output(2).endMValue should be(output(2).geometryLength +- 0.001)
      output(2).startAddrMValue should be(20L)
      output(2).endAddrMValue should be(30L)

      output(1).id should be(idRoad3)
      output(1).startMValue should be(0.0)
      output(1).endMValue should be(output(1).geometryLength +- 0.001)
      output(1).startAddrMValue should be(10L)
      output(1).endAddrMValue should be(20L)

      output.head.id should be(idRoad0)
      output.head.startMValue should be(0.0)
      output.head.endMValue should be(output.head.geometryLength +- 0.001)
      output.head.startAddrMValue should be(0L)
      output.head.endAddrMValue should be(10L)

      output(3).calibrationPoints should be(None, Some(ProjectLinkCalibrationPoint(12346, Math.round(9.799999999999997*10.0)/10.0, 40, ProjectLinkSource)))

      output.head.calibrationPoints should be(Some(ProjectLinkCalibrationPoint(12345, 0.0, 0, ProjectLinkSource)), None)
    }
  }

  test("Test assignMValues When assigning values for new road addresses in a complex case Then values should be properly assigned") {
    runWithRollback {
      val idRoad0 = 0L //   |
      val idRoad1 = 1L //  /
      val idRoad2 = 2L //    \
      val idRoad3 = 3L //  \
      val idRoad4 = 4L //    /
      val idRoad5 = 5L //   |
      val idRoad6 = 6L //  /
      val idRoad7 = 7L //    \
      val idRoad8 = 8L //   |
      val projectLink0 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad0, 0, 5, 1, RoadType.Unknown, Track.Combined, Continuous,
        0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad0, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), 
        Seq(Point(0.0, 0.0), Point(0.0, 9.8)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink1 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad1, 0, 5, 1, RoadType.Unknown, Track.RightSide, Continuous,
        0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad1, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), 
        Seq(Point(0.0, 9.8), Point(-2.0, 20.2)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink2 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad2, 0, 5, 1, RoadType.Unknown, Track.LeftSide, Continuous,
        0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad2, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), 
        Seq(Point(0.0, 9.8), Point(2.0, 19.2)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink3 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad3, 0, 5, 1, RoadType.Unknown, Track.RightSide, Continuous,
        0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad3, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), 
        Seq(Point(-2.0, 20.2), Point(1.0, 30.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink4 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad4, 0, 5, 1, RoadType.Unknown, Track.LeftSide, Continuous,
        0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad4, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), 
        Seq(Point(2.0, 19.2), Point(1.0, 30.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink5 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad5, 0, 5, 1, RoadType.Unknown, Track.Combined, Continuous,
        0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad5, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), 
        Seq(Point(1.0, 30.0), Point(0.0, 48.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink6 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad6, 0, 5, 1, RoadType.Unknown, Track.RightSide, Continuous,
        0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad6, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), 
        Seq(Point(0.0, 48.0), Point(2.0, 68.0), Point(0.0, 96.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink7 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad7, 0, 5, 1, RoadType.Unknown, Track.LeftSide, Continuous,
        0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad7, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), 
        Seq(Point(0.0, 48.0), Point(-2.0, 68.0), Point(0.0, 96.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink8 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad8, 0, 5, 1, RoadType.Unknown, Track.Combined, Continuous,
        0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad8, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), 
        Seq(Point(0.0, 96.0), Point(0.0, 148.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))

      val projectLinkSeq = Seq(projectLink0, projectLink1, projectLink2, projectLink3, projectLink4, projectLink5, projectLink6, projectLink7, projectLink8).map(pl =>
        pl.copy(endMValue = pl.geometryLength))
      val output = ProjectSectionCalculator.assignMValues(projectLinkSeq).sortBy(_.linkId)
      output.length should be(9)
      output.foreach(pl => pl.sideCode == AgainstDigitizing should be(true))
      val start = output.find(_.id == idRoad0).get
      start.calibrationPoints._1.nonEmpty should be(true)
      start.calibrationPoints._2.nonEmpty should be(true)
      start.startAddrMValue should be(139L)

      output.filter(pl => pl.id == idRoad1 || pl.id == idRoad2).foreach { pl =>
        pl.calibrationPoints._1.nonEmpty should be(false)
        pl.calibrationPoints._2.nonEmpty should be(true)
      }

      output.filter(pl => pl.id == idRoad3 || pl.id == idRoad4).foreach { pl =>
        pl.calibrationPoints._1.nonEmpty should be(true)
        pl.calibrationPoints._2.nonEmpty should be(false)
      }

      output.filter(pl => pl.id > idRoad4).foreach { pl =>
        pl.calibrationPoints._1.nonEmpty should be(true)
        pl.calibrationPoints._2.nonEmpty should be(true)
      }

      output.find(_.id == idRoad0).get.endAddrMValue should be(149L)
    }
  }

  test("Test assignMValues When giving against digitization case Then values should be properly assigned") {
    runWithRollback {
      val idRoad0 = 0L //   |
      val idRoad1 = 1L //  /
      val idRoad2 = 2L //    \
      val idRoad3 = 3L //  \
      val idRoad4 = 4L //    /
      val idRoad5 = 5L //   |
      val idRoad6 = 6L //  /
      val idRoad7 = 7L //    \
      val idRoad8 = 8L //   |
      val projectLink0 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad0, 0, 5, 1, RoadType.Unknown, Track.Combined, Continuous,
        0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad0, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), 
        Seq(Point(0.0, 0.0), Point(0.0, 9.8)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink1 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad1, 0, 5, 1, RoadType.Unknown, Track.RightSide, Continuous,
        0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad1, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), 
        Seq(Point(0.0, 9.8), Point(-2.0, 20.2)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink2 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad2, 0, 5, 1, RoadType.Unknown, Track.LeftSide, Continuous,
        0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad2, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), 
        Seq(Point(0.0, 9.8), Point(2.0, 19.2)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink3 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad3, 0, 5, 1, RoadType.Unknown, Track.RightSide, Continuous,
        0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad3, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), 
        Seq(Point(-2.0, 20.2), Point(1.0, 30.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink4 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad4, 0, 5, 1, RoadType.Unknown, Track.LeftSide, Continuous,
        0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad4, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), 
        Seq(Point(2.0, 19.2), Point(1.0, 30.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink5 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad5, 0, 5, 1, RoadType.Unknown, Track.Combined, Continuous,
        0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad5, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), 
        Seq(Point(1.0, 30.0), Point(0.0, 48.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink6 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad6, 0, 5, 1, RoadType.Unknown, Track.RightSide, Continuous,
        0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad6, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), 
        Seq(Point(0.0, 48.0), Point(2.0, 68.0), Point(0.0, 96.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink7 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad7, 0, 5, 1, RoadType.Unknown, Track.LeftSide, Continuous,
        0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad7, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), 
        Seq(Point(0.0, 48.0), Point(-2.0, 68.0), Point(0.0, 96.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink8 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad8, 0, 5, 1, RoadType.Unknown, Track.Combined, Continuous,
        0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad8, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), 
        Seq(Point(0.0, 96.0), Point(0.0, 148.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))

      val projectLinkSeq = Seq(projectLink0, projectLink1, projectLink2, projectLink3, projectLink4, projectLink5, projectLink6, projectLink7, projectLink8).map(
        pl => pl.copy(sideCode = SideCode.AgainstDigitizing)
      )
      val output = ProjectSectionCalculator.assignMValues(projectLinkSeq).sortBy(_.linkId)
      output.length should be(9)
      output.foreach(pl => pl.sideCode == TowardsDigitizing should be(true))
      val start = output.find(_.id == idRoad0).get
      start.calibrationPoints._1.nonEmpty should be(true)
      start.calibrationPoints._2.nonEmpty should be(true)
      start.endAddrMValue should be(10L)

      output.filter(pl => pl.id == idRoad1 || pl.id == idRoad2).foreach { pl =>
        pl.calibrationPoints._1.nonEmpty should be(true)
        pl.calibrationPoints._2.nonEmpty should be(false)
      }

      output.filter(pl => pl.id == idRoad3 || pl.id == idRoad4).foreach { pl =>
        pl.calibrationPoints._1.nonEmpty should be(false)
        pl.calibrationPoints._2.nonEmpty should be(true)
      }

      output.filter(pl => pl.id > idRoad4).foreach { pl =>
        pl.calibrationPoints._1.nonEmpty should be(true)
        pl.calibrationPoints._2.nonEmpty should be(true)
      }

      output.find(_.id == idRoad0).get.startAddrMValue should be(0L)
    }
  }

  test("Test assignMValues When addressing calibration points and mixed directions Then values should be properly assigned") {
    runWithRollback {
      val idRoad0 = 0L //   >
      val idRoad1 = 1L //     <
      val idRoad2 = 2L //   >
      val idRoad3 = 3L //     <
      val projectLink0 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad0, 0, 5, 1, RoadType.Unknown, Track.Combined, Continuous,
        0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad0, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), 
        Seq(Point(0.0, 0.0), Point(0.0, 9.8)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink1 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad1, 0, 5, 1, RoadType.Unknown, Track.Combined, Continuous,
        0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad1, 0.0, 0.0, SideCode.AgainstDigitizing, 0, (None, None), 
        Seq(Point(4.0, 7.5), Point(0.0, 9.8)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink2 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad2, 0, 5, 1, RoadType.Unknown, Track.Combined, Continuous,
        0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad2, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), 
        Seq(Point(4.0, 7.5), Point(6.0, 19.2)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink3 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad3, 0, 5, 1, RoadType.Unknown, Track.Combined, Continuous,
        0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad3, 0.0, 0.0, SideCode.AgainstDigitizing, 0, (None, None), 
        Seq(Point(10.0, 15.0), Point(6.0, 19.2)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))

      val projectLinkSeq = Seq(projectLink0, projectLink1, projectLink2, projectLink3)
      val output = ProjectSectionCalculator.assignMValues(projectLinkSeq).sortBy(_.linkId)
      output.length should be(4)
      output.foreach(pl => pl.sideCode == AgainstDigitizing || pl.id % 2 == 0 should be(true))
      output.foreach(pl => pl.sideCode == TowardsDigitizing || pl.id % 2 != 0 should be(true))
      val start = output.find(_.id == idRoad0).get
      start.calibrationPoints._1.nonEmpty should be(true)
      start.calibrationPoints._2.nonEmpty should be(false)
      start.startAddrMValue should be(0L)
      val end = output.find(_.id == idRoad3).get
      end.calibrationPoints._1.nonEmpty should be(false)
      end.calibrationPoints._2.nonEmpty should be(true)
      end.endAddrMValue should be(32L)
    }
  }

  test("Test assignMValues When assigning values for Tracks 0+1+2 Then the result should not be dependent on the order of the links") {
    runWithRollback {
      def trackMap(pl: ProjectLink) = {
        pl.linkId -> (pl.track, pl.sideCode)
      }

      val idRoad0 = 0L //   0 Track
      val idRoad1 = 1L //   1 Track
      val idRoad2 = 2L //   2 Track
      val projectLink0 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad0, 0, 5, 1, RoadType.Unknown, Track.Combined, Continuous,
        0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad0, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), 
        Seq(Point(20.0, 10.0), Point(28, 15)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink1 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad1, 0, 5, 1, RoadType.Unknown, Track.RightSide, Continuous,
        0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad1, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), 
        Seq(Point(42, 14), Point(28, 15)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink2 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad2, 0, 5, 1, RoadType.Unknown, Track.LeftSide, Continuous,
        0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad2, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), 
        Seq(Point(28, 15), Point(75, 19.2)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val list = List(projectLink0, projectLink1, projectLink2)
      val ordered = ProjectSectionCalculator.assignMValues(list).map(trackMap).toMap
      // Test that the result is not dependent on the order of the links
      list.permutations.foreach(l => {
        ProjectSectionCalculator.assignMValues(l).map(trackMap).toMap should be(ordered)
      })
    }
  }

  test("Test assignMValues When giving one link Towards and one Against Digitizing Then calibrations points should be properly assigned") {
    runWithRollback {
      val projectLink0T = toProjectLink(rap, LinkStatus.New)(RoadAddress(0L, 0, 5, 1, RoadType.Unknown, Track.Combined, Continuous,
        0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), 0L, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), 
        Seq(Point(20.0, 10.0), Point(28, 15)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink0A = toProjectLink(rap, LinkStatus.New)(RoadAddress(0L, 0, 5, 1, RoadType.Unknown, Track.Combined, Continuous,
        0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), 0L, 0.0, 0.0, SideCode.AgainstDigitizing, 0, (None, None), 
        Seq(Point(20.0, 10.0), Point(28, 15)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))

      val towards = ProjectSectionCalculator.assignMValues(Seq(projectLink0T)).head
      val against = ProjectSectionCalculator.assignMValues(Seq(projectLink0A)).head
      towards.sideCode should be(SideCode.TowardsDigitizing)
      against.sideCode should be(SideCode.AgainstDigitizing)
      towards.calibrationPoints._1 should be(Some(ProjectLinkCalibrationPoint(0, 0.0, 0, ProjectLinkSource)))
      towards.calibrationPoints._2 should be(Some(ProjectLinkCalibrationPoint(0, projectLink0T.geometryLength, 9, ProjectLinkSource)))
      against.calibrationPoints._2 should be(Some(ProjectLinkCalibrationPoint(0, 0.0, 9, ProjectLinkSource)))
      against.calibrationPoints._1 should be(Some(ProjectLinkCalibrationPoint(0, projectLink0A.geometryLength, 0, ProjectLinkSource)))
    }
  }

  test("Test assignMValues When giving links without opposite track Then exception is thrown and links are returned as-is") {
    runWithRollback {
      val projectLink0 = toProjectLink(rap, LinkStatus.New)(RoadAddress(0L, 0, 5, 1, RoadType.Unknown, Track.RightSide, Continuous,
        0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), 0L, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), 
        Seq(Point(20.0, 10.0), Point(28, 15)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink1 = toProjectLink(rap, LinkStatus.New)(RoadAddress(1L, 0, 5, 1, RoadType.Unknown, Track.RightSide, Continuous,
        0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), 1L, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), 
        Seq(Point(28.0, 15.0), Point(38, 15)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))

      val output = ProjectSectionCalculator.assignMValues(Seq(projectLink0, projectLink1))
      output.foreach { pl =>
        pl.startAddrMValue should be(0L)
        pl.endAddrMValue should be(0L)
      }
    }
  }

  test("Test assignMValues When giving incompatible digitization on tracks Then the direction of left track is corrected to match the right track") {
    runWithRollback {
      val idRoad0 = 0L //   R<
      val idRoad1 = 1L //   R<
      val idRoad2 = 2L //   L<    <- Note! Incompatible, means the addressing direction is against the right track
      val idRoad3 = 3L //   L<    <- Note! Incompatible, means the addressing direction is against the right track
      val projectLink0 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad0, 0, 5, 1, RoadType.Unknown, Track.RightSide, Continuous,
        0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad0, 0.0, 0.0, SideCode.AgainstDigitizing, 0, (Some(CalibrationPoint(0L, 0.0, 0L)), None), 
        Seq(Point(28, 9.8), Point(20.0, 10.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink1 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad1, 0, 5, 1, RoadType.Unknown, Track.RightSide, Continuous,
        0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad1, 0.0, 0.0, SideCode.AgainstDigitizing, 0, (None, None), 
        Seq(Point(42, 9.7), Point(28, 9.8)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink2 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad2, 0, 5, 1, RoadType.Unknown, Track.LeftSide, Continuous,
        0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad2, 0.0, 0.0, SideCode.AgainstDigitizing, 0, (None, None), 
        Seq(Point(20, 10.1), Point(28, 10.2)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink3 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad3, 0, 5, 1, RoadType.Unknown, Track.LeftSide, Continuous,
        0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad3, 0.0, 0.0, SideCode.AgainstDigitizing, 0, (None, None), 
        Seq(Point(28, 10.2), Point(42, 10.3)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val list = List(projectLink0, projectLink1, projectLink2, projectLink3)
      val ordered = ProjectSectionCalculator.assignMValues(list)
      // Test that the direction of left track is corrected to match the right track
      val (right, left) = ordered.partition(_.track == Track.RightSide)
      right.foreach(
        _.sideCode should be(AgainstDigitizing)
      )
      left.foreach(
        _.sideCode should be(TowardsDigitizing)
      )
    }
  }

  test("Test assignMValues When giving links with different track lengths Then different track lengths are adjusted") {
    runWithRollback {
      // Left track = 89.930 meters
      val idRoad0 = 0L //   L>
      val idRoad1 = 1L //   L>
      val idRoad2 = 2L //   L>
      val idRoad3 = 3L //   L<
      // Right track = 83.154 meters
      val idRoad4 = 4L //   R>
      val idRoad5 = 5L //   R>
      val projectLink0 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad0, 0, 5, 1, RoadType.Unknown, Track.LeftSide, Continuous,
        0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad0, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), 
        Seq(Point(20.0, 10.0), Point(28, 15)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink1 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad1, 0,5, 1, RoadType.Unknown, Track.LeftSide, Continuous,
        0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad1, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), 
        Seq(Point(28, 15), Point(42, 19)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink2 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad2, 0, 5, 1, RoadType.Unknown, Track.LeftSide, Continuous,
        0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad2, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), 
        Seq(Point(42, 19), Point(75, 29.2)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink3 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad3, 0, 5, 1, RoadType.Unknown, Track.LeftSide, Continuous,
        0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad3, 0.0, 0.0, SideCode.AgainstDigitizing, 0, (None, None), 
        Seq(Point(103.0, 15.0), Point(75, 29.2)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink4 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad4, 0, 5, 1, RoadType.Unknown, Track.RightSide, Continuous,
        0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad4, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), 
        Seq(Point(20.0, 10.0), Point(42, 11)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink5 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad5, 0, 5, 1, RoadType.Unknown, Track.RightSide, Continuous,
        0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad5, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), 
        Seq(Point(42, 11), Point(103, 15)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val list = List(projectLink0, projectLink1, projectLink2, projectLink3, projectLink4, projectLink5)
      val ordered = ProjectSectionCalculator.assignMValues(list)
      ordered.map(fi.liikennevirasto.viite.util.prettyPrint).foreach(println)
      ordered.flatMap(_.calibrationPoints._1).foreach(
        _.addressMValue should be(0L)
      )
      ordered.flatMap(_.calibrationPoints._2).foreach(
        _.addressMValue should be(86L)
      )
    }
  }

  test("Test assignMValues When giving links in different tracks Then proper calibration points should be created for each track") {
    runWithRollback {
      // Left track = 85.308 meters
      val idRoad0 = 0L //   L>
      val idRoad1 = 1L //   L>
      val idRoad2 = 2L //   L>
      val idRoad3 = 3L //   L<
      // Right track = 83.154 meters
      val idRoad4 = 4L //   R>
      val idRoad5 = 5L //   R>
      val projectLink0 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad0, 0, 5, 1, RoadType.Unknown, Track.LeftSide, Continuous,
        0L, 9L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad0, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, Some(CalibrationPoint(idRoad0, 9.0, 9L))), 
        Seq(Point(20.0, 10.0), Point(28, 15)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink1 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad1, 0, 5, 1, RoadType.Unknown, Track.LeftSide, Continuous,
        9L, 20L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad1, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (Some(CalibrationPoint(idRoad1, 0.0, 9L)), None), 
        Seq(Point(28, 15), Point(42, 19)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink2 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad2, 0, 5, 1, RoadType.Unknown, Track.LeftSide, Continuous,
        0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad2, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), 
        Seq(Point(42, 19), Point(75, 29.2)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink3 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad3, 0, 5, 1, RoadType.Unknown, Track.LeftSide, Continuous,
        0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad3, 0.0, 0.0, SideCode.AgainstDigitizing, 0, (None, None), 
        Seq(Point(103.0, 15.0), Point(75, 29.2)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink4 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad4, 0, 5, 1, RoadType.Unknown, Track.RightSide, Continuous,
        0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad4, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), 
        Seq(Point(20.0, 10.0), Point(42, 11)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink5 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad5, 0, 5, 1, RoadType.Unknown, Track.RightSide, Continuous,
        0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad5, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), 
        Seq(Point(42, 11), Point(103, 15)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val list = List(projectLink0, projectLink1, projectLink2, projectLink3, projectLink4, projectLink5)
      val ordered = ProjectSectionCalculator.assignMValues(list)
      ordered.flatMap(_.calibrationPoints._1) should have size 2
      ordered.flatMap(_.calibrationPoints._2) should have size 2
    }
  }

  test("Test assignMValues When track sections are combined to start from the same position Then tracks should still be different") {
    runWithRollback {
      // Left track = 85.308 meters
      val idRoad0 = 0L //   C>
      val idRoad1 = 1L //   L>
      val idRoad2 = 2L //   R>
      val roadwayId = Sequences.nextRoadwayId
      val roadwayNumber = Sequences.nextRoadwayNumber
      val linearLocationId = Sequences.nextLinearLocationId
      val projectLink0 = toProjectLink(rap, LinkStatus.UnChanged)(RoadAddress(idRoad0, 0, 5, 1, RoadType.Unknown, Track.Combined, Continuous,
        0L, 8L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), idRoad0, 0.0, 8.0, SideCode.TowardsDigitizing, 0, (Some(CalibrationPoint(0L, 0.0, 0L)), None),
        Seq(Point(20.0, 10.0), Point(28, 10)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0)).copy(projectId = rap.id, roadwayNumber = roadwayNumber, roadwayId = roadwayId, linearLocationId = linearLocationId)
      val projectLink1 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad1, 0, 5, 1, RoadType.Unknown, Track.LeftSide, Continuous,
        0L, 0L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), idRoad1, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None),
        Seq(Point(28, 10), Point(28, 19)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0)).copy(projectId = rap.id)
      val projectLink2 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad2, 0, 5, 1, RoadType.Unknown, Track.RightSide, Continuous,
        0L, 0L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), idRoad2, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None),
        Seq(Point(28, 1), Point(28, 10)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0)).copy(projectId = rap.id)

      val (linearCombined, rwComb): (LinearLocation, Roadway) = Seq(projectLink0).map(toRoadwayAndLinearLocation).head
      val rwCombWithId = rwComb.copy(id = roadwayId, ely = 8L)
      val linearRightWithId = linearCombined.copy(id = linearLocationId)
      buildTestDataForProject(Some(rap), Some(Seq(rwCombWithId)), Some(Seq(linearRightWithId)), None)

      val list = List(projectLink0, projectLink1, projectLink2)
      val ordered = ProjectSectionCalculator.assignMValues(list)
      val left = ordered.find(_.linkId == idRoad1).get
      val right = ordered.find(_.linkId == idRoad2).get
      left.sideCode == right.sideCode should be(false)
    }
  }

  test("Test assignMValues When giving created + unchanged links Then links should be calculated properly") {
    runWithRollback {
      val idRoad0 = 0L //   U>
      val idRoad1 = 1L //   U>
      val idRoad2 = 2L //   N>
      /*
      |--U-->|--U-->|--N-->|
       */
      val projectLink0 = toProjectLink(rap, LinkStatus.UnChanged)(RoadAddress(idRoad0, 0, 5, 1, RoadType.Unknown, Track.Combined, Continuous,
        0L, 9L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), idRoad0, 0.0, 8.0, SideCode.TowardsDigitizing, 0, (Some(CalibrationPoint(idRoad0, 0.0, 0L)), None),
        Seq(Point(20.0, 10.0), Point(28, 10)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink1 = toProjectLink(rap, LinkStatus.UnChanged)(RoadAddress(idRoad1, 0, 5, 1, RoadType.Unknown, Track.Combined, Continuous,
        9L, 19L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), idRoad1, 0.0, 9.0, SideCode.TowardsDigitizing, 0, (None, Some(CalibrationPoint(idRoad1, 9.0, 19L))),
        Seq(Point(28, 10), Point(28, 19)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink2 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad2, 0, 5, 1, RoadType.Unknown, Track.Combined, Continuous,
        0L, 0L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), idRoad2, 0.0, 11.0, SideCode.TowardsDigitizing, 0, (None, None),
        Seq(Point(28, 19), Point(28, 30)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val list = List(projectLink0, projectLink1, projectLink2)
      val (created, unchanged) = list.partition(_.status == LinkStatus.New)
      val ordered = ProjectSectionCalculator.assignMValues(created ++ unchanged)
      val road2 = ordered.find(_.linkId == idRoad2).get
      road2.startAddrMValue should be(19L)
      road2.endAddrMValue should be(30L)
      road2.calibrationPoints._1 should be(None)
      road2.calibrationPoints._2.nonEmpty should be(true)
      ordered.count(_.calibrationPoints._2.nonEmpty) should be(1)
    }
  }

  test("Test assignMValues When giving unchanged + terminated links Then links should be calculated properly") {
    runWithRollback {
      val idRoad0 = 0L //   U>
      val idRoad1 = 1L //   U>
      val idRoad2 = 2L //   T>

      val roadwayId = Sequences.nextRoadwayId
      val roadwayNumber = Sequences.nextRoadwayNumber
      val linearLocationId = Sequences.nextLinearLocationId

      val projectLink0 = toProjectLink(rap, LinkStatus.UnChanged)(RoadAddress(idRoad0, 0, 5, 1, RoadType.Unknown, Track.Combined, Continuous,
        0L, 9L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), idRoad0, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (Some(CalibrationPoint(idRoad0, 0.0, 0L)), None),
        Seq(Point(20.0, 10.0), Point(28, 10)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0)).copy(projectId = rap.id, roadwayNumber = roadwayNumber, roadwayId = roadwayId, linearLocationId = linearLocationId)
      val projectLink1 = toProjectLink(rap, LinkStatus.UnChanged)(RoadAddress(idRoad1, 0, 5, 1, RoadType.Unknown, Track.Combined, Continuous,
        9L, 19L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), idRoad1, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None),
        Seq(Point(28, 10), Point(28, 19)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0)).copy(projectId = rap.id, roadwayNumber = roadwayNumber+1, roadwayId = roadwayId+1, linearLocationId = linearLocationId+1)
      val projectLink2 = toProjectLink(rap, LinkStatus.Terminated)(RoadAddress(idRoad2, 0, 5, 1, RoadType.Unknown, Track.Combined, Continuous,
        19L, 30L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), idRoad2, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, Some(CalibrationPoint(idRoad2, 11.0, 30L))),
        Seq(Point(28, 19), Point(28, 30)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0)).copy(projectId = rap.id, roadwayNumber = roadwayNumber+2, roadwayId = roadwayId+2, linearLocationId = linearLocationId+2)

      val (linearCombined1, rwComb1): (LinearLocation, Roadway) = Seq(projectLink0).map(toRoadwayAndLinearLocation).head
      val (linearCombined2, rwComb2): (LinearLocation, Roadway) = Seq(projectLink1).map(toRoadwayAndLinearLocation).head
      val (linearCombined3, rwComb3): (LinearLocation, Roadway) = Seq(projectLink2).map(toRoadwayAndLinearLocation).head
      val rwComb1WithId = rwComb1.copy(id = roadwayId, ely = 8L)
      val rwComb2WithId = rwComb2.copy(id = roadwayId+1, ely = 8L)
      val rwComb3WithId = rwComb3.copy(id = roadwayId+2, ely = 8L)
      val linearCombined1WithId = linearCombined1.copy(id = linearLocationId)
      val linearCombined2WithId = linearCombined2.copy(id = linearLocationId+1)
      val linearCombined3WithId = linearCombined3.copy(id = linearLocationId+2)
      buildTestDataForProject(Some(rap), Some(Seq(rwComb1WithId, rwComb2WithId, rwComb3WithId)), Some(Seq(linearCombined1WithId, linearCombined2WithId, linearCombined3WithId)), None)

      val list = List(projectLink0, projectLink1, projectLink2)
      val (_, unchanged) = list.partition(_.status == LinkStatus.Terminated)
      val ordered = ProjectSectionCalculator.assignMValues(unchanged)
      ordered.find(_.linkId == idRoad2) should be(None)
      ordered.head.startAddrMValue should be(0L)
      ordered.last.endAddrMValue should be(19L)
      ordered.count(_.calibrationPoints._1.nonEmpty) should be(1)
      ordered.count(_.calibrationPoints._2.nonEmpty) should be(1)
    }
  }

  test("Test assignMValues When giving new + transfer links Then Project section calculator should assign values properly") {
    runWithRollback {
      val idRoad0 = 0L // T
      val idRoad1 = 1L // N
      val idRoad2 = 2L // N
      val idRoad3 = 3L // T

      val roadwayId = Sequences.nextRoadwayId
      val roadwayNumber = Sequences.nextRoadwayNumber
      val linearLocationId = Sequences.nextLinearLocationId
      val projectLink0 = toProjectLink(rap, LinkStatus.Transfer)(RoadAddress(idRoad0, 0, 5, 1, RoadType.Unknown, Track.Combined, Continuous, 0L, 12L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), 12345L, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None),
        Seq(Point(0.0, 0.0), Point(0.0, 9.8)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0)).copy(projectId = rap.id, roadwayNumber = roadwayNumber, roadwayId = roadwayId, linearLocationId = linearLocationId)
      val projectLink1 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad1, 0, 5, 1, RoadType.Unknown, Track.Combined, Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), 12346L, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None),
        Seq(Point(0.0, -10.0), Point(0.0, 0.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0)).copy(projectId = rap.id)
      val projectLink2 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad2, 0, 5, 1, RoadType.Unknown, Track.Combined, Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), 12347L, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None),
        Seq(Point(0.0, -20.2), Point(0.0, -10.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0)).copy(projectId = rap.id)
      val projectLink3 = toProjectLink(rap, LinkStatus.Transfer)(RoadAddress(idRoad3, 0, 5, 1, RoadType.Unknown, Track.Combined, Discontinuous, 12L, 24L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), 12348L, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None),
        Seq(Point(0.0, 9.8), Point(0.0, 20.2)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0)).copy(projectId = rap.id, roadwayNumber = roadwayNumber+3, roadwayId = roadwayId+3, linearLocationId = linearLocationId+3)

      val (linearCombined0, rwComb0): (LinearLocation, Roadway) = Seq(projectLink0).map(toRoadwayAndLinearLocation).head
      val (linearCombined3, rwComb3): (LinearLocation, Roadway) = Seq(projectLink3).map(toRoadwayAndLinearLocation).head
      val rwComb0WithId = rwComb0.copy(id = roadwayId, ely = 8L)
      val rwComb3WithId = rwComb3.copy(id = roadwayId+3, ely = 8L)
      val linearCombined0WithId = linearCombined0.copy(id = linearLocationId)
      val linearCombined3WithId = linearCombined3.copy(id = linearLocationId+3)
      buildTestDataForProject(Some(rap), Some(Seq(rwComb0WithId, rwComb3WithId)), Some(Seq(linearCombined0WithId, linearCombined3WithId)), None)

      val projectLinkSeqT = Seq(projectLink0, projectLink3)
      val projectLinkSeqN = Seq(projectLink1, projectLink2)
      val output = ProjectSectionCalculator.assignMValues(projectLinkSeqN ++ projectLinkSeqT)
      output.length should be(4)
      val maxAddr = output.map(_.endAddrMValue).max
      output.filter(_.id == idRoad0).foreach { r =>
        r.calibrationPoints should be(None, None)
        // new value = original + (new end - old end)
        r.startAddrMValue should be(projectLink0.startAddrMValue + maxAddr - projectLink3.endAddrMValue)
        r.endAddrMValue should be(projectLink0.endAddrMValue + maxAddr - projectLink3.endAddrMValue)
      }
      output.filter(_.id == idRoad3).foreach { r =>
        r.calibrationPoints should be(None, Some(ProjectLinkCalibrationPoint(12348, Math.round(10.399999999999999*10.0)/10.0, 44, ProjectLinkSource)))
        r.startAddrMValue should be(maxAddr + projectLink3.startAddrMValue - projectLink3.endAddrMValue)
        r.endAddrMValue should be(maxAddr)
      }

      output.head.calibrationPoints should be(Some(ProjectLinkCalibrationPoint(12347, 0.0, 0, ProjectLinkSource)), None)
    }
  }

  test("Test assignMValues When there is a MinorDiscontinuity at end of address Then start of next one with 2 tracks (Left and Right) should also have calibration points, 4 in total") {
    runWithRollback{
      // Left track = 85.308 meters
      val idRoad0 = 0L //   L>
      val idRoad1 = 1L //   L>
      val idRoad2 = 2L //   L>
      val idRoad3 = 3L //   L>
      // Right track = 83.154 meters
      val idRoad4 = 4L //   R>
      val idRoad5 = 5L //   R>
      val projectLink0 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad0, 0, 5, 1, RoadType.Unknown, Track.LeftSide, Continuous,
        0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad0, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), 
        Seq(Point(20.0, 10.0), Point(28, 15)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink1 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad1, 0, 5, 1, RoadType.Unknown, Track.LeftSide, MinorDiscontinuity,
        0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad1, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), 
        Seq(Point(28, 15), Point(41, 18)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink2 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad2, 0, 5, 1, RoadType.Unknown, Track.LeftSide, Continuous,
        0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad2, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), 
        Seq(Point(42, 19), Point(75, 29.2)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink3 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad3, 0, 5, 1, RoadType.Unknown, Track.LeftSide, Continuous,
        0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad3, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), 
        Seq(Point(103.0, 15.0),Point(75, 29.2)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink4 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad4, 0, 5, 1, RoadType.Unknown, Track.RightSide, MinorDiscontinuity,
        0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad4, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), 
        Seq(Point(20.0, 10.0), Point(42, 11)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink5 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad5, 0, 5, 1, RoadType.Unknown, Track.RightSide, Continuous,
        0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad5, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), 
        Seq(Point(42, 11), Point(103, 15)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val list = List(projectLink0, projectLink1, projectLink2, projectLink3, projectLink4, projectLink5)
      val ordered = ProjectSectionCalculator.assignMValues(list)
      // check left and right track - should have 4 calibration points: start, end, minor discontinuity and next one after discontinuity
      (ordered.filter(_.track == Track.LeftSide).flatMap(_.calibrationPoints._1) ++ ordered.filter(_.track == Track.LeftSide).flatMap(_.calibrationPoints._2)) should have size 4
      (ordered.filter(_.track == Track.RightSide).flatMap(_.calibrationPoints._1) ++ ordered.filter(_.track == Track.RightSide).flatMap(_.calibrationPoints._2)) should have size 4
    }
  }

  test("Test assignMValues When there is a Discontinuity at end of address Then start of next one with 2 tracks (Left and Right) should also have calibration points, 4 in total") {
    runWithRollback {
      // Left track = 85.308 meters
      val idRoad0 = 0L //   L>
      val idRoad1 = 1L //   L>
      val idRoad2 = 2L //   L>
      val idRoad3 = 3L //   L>
      // Right track = 83.154 meters
      val idRoad4 = 4L //   R>
      val idRoad5 = 5L //   R>
      val projectLink0 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad0, 0, 5, 1, RoadType.Unknown, Track.LeftSide, Continuous,
        0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad0, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), 
        Seq(Point(20.0, 10.0), Point(28, 15)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink1 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad1, 0, 5, 1, RoadType.Unknown, Track.LeftSide, Discontinuous,
        0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad1, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), 
        Seq(Point(28, 15), Point(41, 18)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink2 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad2, 0, 5, 1, RoadType.Unknown, Track.LeftSide, Continuous,
        0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad2, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), 
        Seq(Point(42, 19), Point(75, 29.2)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink3 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad3, 0, 5, 1, RoadType.Unknown, Track.LeftSide, Continuous,
        0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad3, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), 
        Seq(Point(103.0, 15.0),Point(75, 29.2)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink4 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad4, 0, 5, 1, RoadType.Unknown, Track.RightSide, Discontinuous,
        0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad4, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), 
        Seq(Point(20.0, 10.0), Point(42, 11)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink5 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad5, 0, 5, 1, RoadType.Unknown, Track.RightSide, Continuous,
        0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad5, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), 
        Seq(Point(42, 11), Point(103, 15)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val list = List(projectLink0, projectLink1, projectLink2, projectLink3, projectLink4, projectLink5)
      val ordered = ProjectSectionCalculator.assignMValues(list)
      // check left and right track - should have 4 calibration points: start, end, discontinuity and next one after discontinuity
      (ordered.filter(_.track == Track.LeftSide).flatMap(_.calibrationPoints._1) ++ ordered.filter(_.track == Track.LeftSide).flatMap(_.calibrationPoints._2)) should have size 4
      (ordered.filter(_.track == Track.RightSide).flatMap(_.calibrationPoints._1) ++ ordered.filter(_.track == Track.RightSide).flatMap(_.calibrationPoints._2)) should have size 4
    }
  }

  test("Test assignMValues When there is a MinorDiscontinuity at end of address Then start of next one with 1 track (Combined) should also have calibration points, 4 in total") {
    runWithRollback {
      // Combined track = 85.308 meters with MinorDiscontinuity
      val idRoad0 = 0L //   C>
      val idRoad1 = 1L //   C>
      val idRoad2 = 2L //   C>
      val idRoad3 = 3L //   C>
      val projectLink0 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad0, 0, 5, 1, RoadType.PublicRoad, Track.Combined, Continuous,
        0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad0, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), 
        Seq(Point(20.0, 10.0), Point(28, 15)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink1 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad1, 0, 5, 1, RoadType.PublicRoad, Track.Combined, MinorDiscontinuity,
        0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad1, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), 
        Seq(Point(28, 15), Point(41, 18)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink2 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad2, 0, 5, 1, RoadType.PublicRoad, Track.Combined, Continuous,
        0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad2, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), 
        Seq(Point(42, 19), Point(75, 29.2)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink3 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad3, 0, 5, 1, RoadType.PublicRoad, Track.Combined, Continuous,
        0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad3, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), 
        Seq(Point(103.0, 15.0),Point(75, 29.2)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val list = List(projectLink0, projectLink1, projectLink2, projectLink3)
      val ordered = ProjectSectionCalculator.assignMValues(list)
      // check combined track - should have 4 calibration points: start, end, minor discontinuity and next one after discontinuity
      (ordered.filter(_.track == Track.Combined).flatMap(_.calibrationPoints._1) ++ ordered.filter(_.track == Track.Combined).flatMap(_.calibrationPoints._2)) should have size 4
    }
  }

  test("Test assignMValues When there is a Discontinuity at end of address Then start of next one with 1 track (Combined) should also have calibration points, 4 in total") {
    runWithRollback {
      // Combined track = 85.308 meters with Discontinuous
      val idRoad0 = 0L //   C>
      val idRoad1 = 1L //   C>
      val idRoad2 = 2L //   C>
      val idRoad3 = 3L //   C>
      val projectLink0 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad0, 0, 5, 1, RoadType.PublicRoad, Track.Combined, Continuous,
        0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad0, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), 
        Seq(Point(20.0, 10.0), Point(28, 15)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink1 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad1, 0, 5, 1, RoadType.PublicRoad, Track.Combined, Discontinuous,
        0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad1, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), 
        Seq(Point(28, 15), Point(41, 18)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink2 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad2, 0, 5, 1, RoadType.PublicRoad, Track.Combined, Continuous,
        0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad2, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), 
        Seq(Point(42, 19), Point(75, 29.2)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink3 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad3, 0, 5, 1, RoadType.PublicRoad, Track.Combined, Continuous,
        0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad3, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), 
        Seq(Point(103.0, 15.0),Point(75, 29.2)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val list = List(projectLink0, projectLink1, projectLink2, projectLink3)
      val ordered = ProjectSectionCalculator.assignMValues(list)
      // check combined track - should have 4 calibration points: start, end, discontinuity and next one after discontinuity
      (ordered.filter(_.track == Track.Combined).flatMap(_.calibrationPoints._1) ++ ordered.filter(_.track == Track.Combined).flatMap(_.calibrationPoints._2)) should have size 4
    }
  }

  test("Test assignMValues When projects links ends on a right and left track Then the section calculator for new + transfer should have the same end address") {
    runWithRollback {
      val idRoad6 = 6L // N   /
      val idRoad5 = 5L // T \
      val idRoad4 = 4L // N   /
      val idRoad3 = 3L // T \
      val idRoad2 = 2L // N   /
      val idRoad1 = 1L // U  |
      val roadwayId = Sequences.nextRoadwayId
      val roadwayNumber = Sequences.nextRoadwayNumber
      val linearLocationId = Sequences.nextLinearLocationId

      val projectLink1 = toProjectLink(rap, LinkStatus.UnChanged)(RoadAddress(idRoad1, 0, 5, 1, RoadType.Unknown, Track.Combined, Continuous, 0L, 10L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), 12347L, 0.0, 0.0, SideCode.AgainstDigitizing, 0, (None, None),
        Seq(Point(3.0, 0.0), Point(3.0, 2.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0)).copy(projectId = rap.id, roadwayNumber = roadwayNumber, roadwayId = roadwayId, linearLocationId = linearLocationId)

      val projectLink2 = toProjectLink(rap, LinkStatus.Transfer)(RoadAddress(idRoad2, 0, 5, 1, RoadType.Unknown, Track.RightSide, Continuous, 0L, 12L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), 12345L, 0.0, 0.0, SideCode.AgainstDigitizing, 0, (None, None),
        Seq(Point(3.0, 2.0), Point(1.0, 4.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0)).copy(projectId = rap.id, roadwayNumber = roadwayNumber+1, roadwayId = roadwayId+1, linearLocationId = linearLocationId+1)
      val projectLink3 = toProjectLink(rap, LinkStatus.Transfer)(RoadAddress(idRoad3, 0, 5, 1, RoadType.Unknown, Track.RightSide, EndOfRoad, 12L, 24L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), 12348L, 0.0, 0.0, SideCode.AgainstDigitizing, 0, (None, None),
        Seq(Point(1.0, 4.0), Point(0.0, 6.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0)).copy(projectId = rap.id, roadwayNumber = roadwayNumber+1, roadwayId = roadwayId+1, linearLocationId = linearLocationId+2)

      val projectLink4 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad4, 0, 5, 1, RoadType.Unknown, Track.LeftSide, Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), 12346L, 0.0, 0.0, SideCode.AgainstDigitizing, 0, (None, None),
        Seq(Point(3.0, 2.0), Point(5.0, 4.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0)).copy(projectId = rap.id)
      val projectLink5 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad5, 0, 5, 1, RoadType.Unknown, Track.LeftSide, Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), 12347L, 0.0, 0.0, SideCode.AgainstDigitizing, 0, (None, None),
        Seq(Point(5.0, 4.0), Point(6.0, 6.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0)).copy(projectId = rap.id)
      val projectLink6 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad6, 0, 5, 1, RoadType.Unknown, Track.LeftSide, EndOfRoad, 0L, 0L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), 12347L, 0.0, 0.0, SideCode.AgainstDigitizing, 0, (None, None),
        Seq(Point(6.0, 6.0), Point(7.0, 7.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0)).copy(projectId = rap.id)

      val (linearCombined, rwComb): (LinearLocation, Roadway) = Seq(projectLink1).map(toRoadwayAndLinearLocation).head
      val (linearRight1, rwRight1): (LinearLocation, Roadway) = Seq(projectLink2).map(toRoadwayAndLinearLocation).head
      val (linearRight2, rwRight2): (LinearLocation, Roadway) = Seq(projectLink3).map(toRoadwayAndLinearLocation).head
      val rwCombWithId = rwComb.copy(id = roadwayId, ely = 8L)
      val rwRight1And2WithId = rwRight1.copy(id = roadwayId+1, ely = 8L, startAddrMValue = projectLink2.startAddrMValue, endAddrMValue = projectLink3.endAddrMValue)
      val linearCombinedWithId = linearCombined.copy(id = linearLocationId)
      val linearRight1WithId = linearRight1.copy(id = linearLocationId+1)
      val linearRight2WithId = linearRight2.copy(id = linearLocationId+2)
      buildTestDataForProject(Some(rap), Some(Seq(rwCombWithId, rwRight1And2WithId)), Some(Seq(linearCombinedWithId, linearRight1WithId, linearRight2WithId)), None)

      val projectLinks = ProjectSectionCalculator.assignMValues(Seq(projectLink1, projectLink2, projectLink3, projectLink4, projectLink5, projectLink6))
      projectLinks.find(_.id == idRoad6).get.endAddrMValue should be(projectLinks.find(_.id == idRoad3).get.endAddrMValue)
    }
  }

  test("Test assignTerminatedMValues When Terminating links on Left/Right tracks and different addrMValues Then they should be adjusted in order to have the same end address") {
    runWithRollback {
      val roadwayDAO = new RoadwayDAO
      val linearLocationDAO = new LinearLocationDAO

      val roadNumber1 = 990
      val roadPartNumber1 = 1

      val roadwayNumber1 = 1000000000L
      val roadwayNumber2 = 2000000000L
      val roadwayNumber3 = 3000000000L

      val roadwayId1 = Sequences.nextRoadwayId
      val roadwayId2 = roadwayId1 + 1
      val roadwayId3 = roadwayId2 + 1
      val linearLocationId1 = Sequences.nextLinearLocationId
      val linearLocationId2 = linearLocationId1 + 1
      val linearLocationId3 = linearLocationId2 + 1

      val testRoadway1 = Roadway(roadwayId1, roadwayNumber1, roadNumber1, roadPartNumber1, RoadType.PublicRoad, Track.Combined, Discontinuity.Continuous,
        0, 50, reversed = false, DateTime.parse("2000-01-01"), None, "test", Some("TEST ROAD 1"), 1, TerminationCode.NoTermination)

      val testRoadway2 = Roadway(roadwayId2, roadwayNumber2, roadNumber1, roadPartNumber1, RoadType.PublicRoad, Track.RightSide, Discontinuity.EndOfRoad,
        50, 105, reversed = false, DateTime.parse("2000-01-01"), None, "test", Some("TEST ROAD 2"), 1, TerminationCode.NoTermination)

      val testRoadway3 = Roadway(roadwayId3, roadwayNumber3, roadNumber1, roadPartNumber1, RoadType.PublicRoad, Track.LeftSide, Discontinuity.EndOfRoad,
        50, 107, reversed = false, DateTime.parse("2000-01-01"), None, "test", Some("TEST ROAD 3"), 1, TerminationCode.NoTermination)


      val linearLocation1 = LinearLocation(linearLocationId1, 1, 12345, 0.0, 50.0, SideCode.TowardsDigitizing, 10000000000L,
        (Some(0L), None), Seq(Point(0.0, 0.0), Point(50.0, 5.0)), LinkGeomSource.NormalLinkInterface,
        roadwayNumber1)

      val linearLocation2 = LinearLocation(linearLocationId2, 1, 12346, 0.0, 55.0, SideCode.TowardsDigitizing, 10000000000L,
        (None, Some(105)), Seq(Point(0.0, 0.0), Point(100.0, 10.0)), LinkGeomSource.NormalLinkInterface,
        roadwayNumber2)

      val linearLocation3 = LinearLocation(linearLocationId3, 1, 12347, 0.0, 57.0, SideCode.TowardsDigitizing, 10000000000L,
        (None, Some(107)), Seq(Point(0.0, 0.0), Point(100.0, 0.0)), LinkGeomSource.NormalLinkInterface,
        roadwayNumber3)

      roadwayDAO.create(List(testRoadway1, testRoadway2, testRoadway3))

      linearLocationDAO.create(List(linearLocation1, linearLocation2, linearLocation3))

      val idRoad3 = 3L // T \
      val idRoad2 = 2L // T   /
      val idRoad1 = 1L // U  |

      val projectLink1 = toProjectLink(rap, LinkStatus.UnChanged)(RoadAddress(idRoad1, linearLocationId1, roadNumber1, roadPartNumber1, RoadType.Unknown, Track.Combined, Continuous, 0L, 50L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), 12345L, 0.0, 50.0, SideCode.TowardsDigitizing, 0, (None, None),
        Seq(Point(0.0, 5.0), Point(50.0, 5.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0)).copy(roadAddressStartAddrM = Some(0L), roadAddressEndAddrM = Some(50L), roadAddressTrack = Some(Track.Combined))

      val projectLink2 = toProjectLink(rap, LinkStatus.Terminated)(RoadAddress(idRoad2, linearLocationId2, roadNumber1, roadPartNumber1, RoadType.Unknown, Track.RightSide, EndOfRoad, 50L, 105L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), 12346L, 0.0, 55.0, SideCode.TowardsDigitizing, 0, (None, None),
        Seq(Point(50.0, 5.0), Point(100.0, 10.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0)).copy(roadAddressStartAddrM = Some(50L), roadAddressEndAddrM = Some(105L), roadAddressTrack = Some(Track.RightSide))
      val projectLink3 = toProjectLink(rap, LinkStatus.Terminated)(RoadAddress(idRoad3, linearLocationId3, roadNumber1, roadPartNumber1, RoadType.Unknown, Track.LeftSide, EndOfRoad, 50L, 107L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), 12347L, 0.0, 57.0, SideCode.TowardsDigitizing, 0, (None, None),
        Seq(Point(50.0, 5.0), Point(100.0, 0.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0)).copy(roadAddressStartAddrM = Some(50L), roadAddressEndAddrM = Some(107L), roadAddressTrack = Some(Track.LeftSide))

      val projectLinks = ProjectSectionCalculator.assignTerminatedMValues(Seq(projectLink2, projectLink3), Seq(projectLink1))
      projectLinks.filter(_.track == Track.LeftSide).head.endAddrMValue should be (projectLinks.filter(_.track == Track.RightSide).head.endAddrMValue)
      projectLinks.filter(_.track == Track.LeftSide).head.endAddrMValue should be ((projectLink2.endAddrMValue+projectLink3.endAddrMValue)/2)
    }
  }

  test("Test assignMValues When adding New link sections to existing ones Then Direction for those should always be the same as the existing section (which are for e.g. Transfer, Unchanged, Numbering) making the addressMValues be sequential") {
    runWithRollback{
                              //(x,y)   -> (x,y)
      val idRoad0 = 0L //   N>  C(0, 90)   ->  (10, 100)
      val idRoad1 = 1L //   N>  C(10, 100) ->  (20, 80)

      val idRoad2 = 2L //   T>  C(20, 80)  ->  (30, 90)
      val idRoad3 = 3L //   T>  L(30, 90)  ->  (50, 20)
      val idRoad4 = 4L //   T>  R(30, 90)  ->  (40, 10)
      val idRoad5 = 5L //   T>  L(50, 20)  ->  (60, 30)
      val idRoad6 = 6L //   T>  R(40, 10)  ->  (60, 20)

      val idRoad7 = 7L //   N>  L(60, 30)  ->  (70, 40)
      val idRoad8 = 8L //   N>  R(60, 20)  ->  (70, 30)
      val idRoad9 = 9L //   N>  L(70, 40)  ->  (80, 50)
      val idRoad10 = 10L // N>  R(70, 30)  ->  (80, 50)
      val idRoad11 = 11L // N>  C(80, 50)  ->  (90, 60)
      val roadwayId = Sequences.nextRoadwayId
      val roadwayNumber = Sequences.nextRoadwayNumber
      val linearLocationId = Sequences.nextLinearLocationId

      //NEW
      val projectLink0 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad0, 0, 5, 1, RoadType.Unknown, Track.Combined, Continuous,
        0L, 0L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), idRoad0, 0.0, 14.1, SideCode.Unknown, 0, (None, None), 
        Seq(Point(0.0, 90.0), Point(10.0, 100.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0)).copy(projectId = rap.id)
      val projectLink1 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad1, 0, 5, 1, RoadType.Unknown, Track.Combined, MinorDiscontinuity,
        0L, 0L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), idRoad1, 0.0, 22.3, SideCode.Unknown, 0, (None, None), 
        Seq(Point(10.0, 100.0), Point(18.0, 78.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0)).copy(projectId = rap.id)

      //TRANSFER
      val projectLink2 = toProjectLink(rap, LinkStatus.Transfer)(RoadAddress(idRoad2, 0, 5, 1, RoadType.Unknown, Track.Combined, Continuous,
        0L, 14L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), idRoad2, 0.0, 14.1, SideCode.TowardsDigitizing, 0, (None, None), 
        Seq(Point(20.0, 80.0), Point(30.0, 90.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0)).copy(projectId = rap.id, roadwayId = roadwayId, roadwayNumber = roadwayNumber, linearLocationId = linearLocationId)
      val projectLink3 = toProjectLink(rap, LinkStatus.Transfer)(RoadAddress(idRoad3, 0, 5, 1, RoadType.Unknown, Track.LeftSide, Continuous,
        14L, 87L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), idRoad3, 0.0, 72.8, SideCode.TowardsDigitizing, 0, (None, None), 
        Seq(Point(30.0, 90.0),Point(50.0, 20.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0)).copy(projectId = rap.id, roadwayId = roadwayId+7, roadwayNumber = roadwayNumber+7, linearLocationId = linearLocationId+7)
      val projectLink4 = toProjectLink(rap, LinkStatus.Transfer)(RoadAddress(idRoad4, 0, 5, 1, RoadType.Unknown, Track.RightSide, Continuous,
        14L, 95L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), idRoad4, 0.0, 80.6, SideCode.TowardsDigitizing, 0, (None, None), 
        Seq(Point(30.0, 90.0), Point(40.0, 10.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0)).copy(projectId = rap.id, roadwayId = roadwayId+8, roadwayNumber = roadwayNumber+8, linearLocationId = linearLocationId+9)
      val projectLink5 = toProjectLink(rap, LinkStatus.Transfer)(RoadAddress(idRoad5, 0, 5, 1, RoadType.Unknown, Track.LeftSide, Continuous,
        87L, 101L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), idRoad5, 0.0, 14.1, SideCode.TowardsDigitizing, 0, (None, None), 
        Seq(Point(50.0, 20.0), Point(60.0, 30.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0)).copy(projectId = rap.id, roadwayId = roadwayId+7, roadwayNumber = roadwayNumber+7, linearLocationId = linearLocationId+8)
      val projectLink6 = toProjectLink(rap, LinkStatus.Transfer)(RoadAddress(idRoad6, 0, 5, 1, RoadType.Unknown, Track.RightSide, Continuous,
        95L, 118L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), idRoad6, 0.0, 22.3, SideCode.TowardsDigitizing, 0, (None, None), 
        Seq(Point(40.0, 10.0), Point(60.0, 20.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0)).copy(projectId = rap.id, roadwayId = roadwayId+8, roadwayNumber = roadwayNumber+8, linearLocationId = linearLocationId+10)

      //NEW
      val projectLink7 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad7, 0, 5, 1, RoadType.Unknown, Track.LeftSide, Continuous,
        0L, 0L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), idRoad7, 0.0, 0.0, SideCode.Unknown, 0, (None, None), 
        Seq(Point(60.0, 30.0), Point(70.0, 40.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0)).copy(projectId = rap.id)
      val projectLink8 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad8, 0, 5, 1, RoadType.Unknown, Track.RightSide, Continuous,
        0L, 0L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), idRoad8, 0.0, 0.0, SideCode.Unknown, 0, (None, None), 
        Seq(Point(60.0, 20.0),Point(70.0, 30.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0)).copy(projectId = rap.id)
      val projectLink9 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad9, 0, 5, 1, RoadType.Unknown, Track.LeftSide, Continuous,
        0L, 0L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), idRoad9, 0.0, 0.0, SideCode.Unknown, 0, (None, None), 
        Seq(Point(70.0, 40.0), Point(80.0, 50.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0)).copy(projectId = rap.id)
      val projectLink10 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad10, 0, 5, 1, RoadType.Unknown, Track.RightSide, Continuous,
        0L, 0L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), idRoad10, 0.0, 0.0, SideCode.Unknown, 0, (None, None),
        Seq(Point(70.0, 30.0), Point(80.0, 50.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0)).copy(projectId = rap.id)
      val projectLink11 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad11, 0, 5, 1, RoadType.Unknown, Track.Combined, Continuous,
        0L, 0L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), idRoad11, 0.0, 0.0, SideCode.Unknown, 0, (None, None), 
        Seq(Point(80.0, 50.0), Point(90.0, 60.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0)).copy(projectId = rap.id)


      val (linearCombined, rwComb): (LinearLocation, Roadway) = Seq(projectLink2).map(toRoadwayAndLinearLocation).head
      val (linearLeft1, rwLeft1): (LinearLocation, Roadway) = Seq(projectLink3).map(toRoadwayAndLinearLocation).head
      val (linearLeft2, rwLeft2): (LinearLocation, Roadway) = Seq(projectLink5).map(toRoadwayAndLinearLocation).head
      val (linearRight1, rwRight1): (LinearLocation, Roadway) = Seq(projectLink4).map(toRoadwayAndLinearLocation).head
      val (linearRight2, rwRight2): (LinearLocation, Roadway) = Seq(projectLink6).map(toRoadwayAndLinearLocation).head
      val rwCombWithId = rwComb.copy(id = roadwayId, ely = 8L)
      val rwLeft1And2WithId = rwLeft1.copy(id = roadwayId+7, ely = 8L, startAddrMValue = projectLink3.startAddrMValue, endAddrMValue = projectLink5.endAddrMValue)
      val rwRight1And2WithId = rwRight1.copy(id = roadwayId+8, ely = 8L, startAddrMValue = projectLink4.startAddrMValue, endAddrMValue = projectLink6.endAddrMValue)
      val linearCombinedWithId = linearCombined.copy(id = linearLocationId)
      val linearLeft1WithId = linearLeft1.copy(id = linearLocationId+7)
      val linearLeft2WithId = linearLeft2.copy(id = linearLocationId+8)
      val linearRight1WithId = linearRight1.copy(id = linearLocationId+9)
      val linearRight2WithId = linearRight2.copy(id = linearLocationId+10)
      buildTestDataForProject(Some(rap), Some(Seq(rwCombWithId, rwLeft1And2WithId, rwRight1And2WithId)), Some(Seq(linearCombinedWithId, linearLeft1WithId, linearLeft2WithId, linearRight1WithId, linearRight2WithId)), None)

      val list1 = List(projectLink0, projectLink1, projectLink2, projectLink3, projectLink4, projectLink5, projectLink6)
      val ordered = ProjectSectionCalculator.assignMValues(list1)
      ordered.minBy(_.startAddrMValue).id should be (idRoad0)

      val list2 = ordered.toList ::: List(projectLink7, projectLink8, projectLink9, projectLink10, projectLink11)
      val ordered2 = ProjectSectionCalculator.assignMValues(list2)
      ordered2.maxBy(_.endAddrMValue).id should be (idRoad11)
    }
  }

  test("Test assignMValues When adding only New sections Then Direction for those should always be the same (on both Tracks) as the existing section (which are for e.g. Transfer, Unchanged, Numbering) making the addressMValues be sequential") {
    runWithRollback{
      //(x,y)   -> (x,y)
      val idRoad0 = 0L //   N>  L(30, 0)   ->  (30, 10)
      val idRoad1 = 1L //   N>  R(40, 0) ->  (40, 10)
      val idRoad2 = 2L //   N>  L(30, 10)  ->  (30, 20)
      val idRoad3 = 3L //   N>  R(40, 10)  ->  (40, 20)
      val idRoad4 = 4L //   N>  L(40, 10)  ->  (30, 10)
      val idRoad5 = 5L //   N>  L(30, 10)  ->  (10, 10)
      val idRoad6 = 6L //   N>  R(40, 20)  ->  (30, 20)
      val idRoad7 = 7L //   N>  L(10, 10)  ->  (0, 10)
      val idRoad8 = 8L //   N>  R(30, 20)  ->  (10, 20)
      val idRoad9 = 9L //   N>  L(0, 10)  ->  (0, 20)
      val idRoad10 = 10L //   N>  R(10, 20)  ->  (0, 20)
      val idRoad11 = 11L // N>  L(0, 20)  ->  (0, 30)
      val idRoad12 = 12L // N>  R(10, 20)  ->  (10, 30)


      //NEW
      val projectLink0 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad0, 0, 5, 1, RoadType.Unknown, Track.LeftSide, Continuous,
        0L, 0L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), idRoad0, 0.0, 10.0, SideCode.Unknown, 0, (None, None), 
        Seq(Point(30.0, 0.0), Point(30.0, 10.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink1 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad1, 0, 5, 1, RoadType.Unknown, Track.RightSide, Continuous,
        0L, 0L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), idRoad1, 0.0, 10.0, SideCode.Unknown, 0, (None, None), 
        Seq(Point(40.0, 0.0), Point(40.0, 10.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink2 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad2, 0, 5, 1, RoadType.Unknown, Track.LeftSide, Continuous,
        0L, 0L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), idRoad2, 0.0, 10.0, SideCode.Unknown, 0, (None, None), 
        Seq(Point(30.0, 10.0), Point(30.0, 20.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink3 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad3, 0, 5, 1, RoadType.Unknown, Track.RightSide, Continuous,
        0L, 0L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), idRoad3, 0.0, 10.0, SideCode.Unknown, 0, (None, None), 
        Seq(Point(40.0, 10.0),Point(40.0, 20.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink4 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad4, 0, 5, 1, RoadType.Unknown, Track.LeftSide, Continuous,
        0L, 0L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), idRoad4, 0.0, 10.0, SideCode.Unknown, 0, (None, None), 
        Seq(Point(40.0, 10.0), Point(30.0, 10.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink5 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad5, 0, 5, 1, RoadType.Unknown, Track.LeftSide, Continuous,
        0L, 0L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), idRoad5, 0.0, 20.0, SideCode.Unknown, 0, (None, None), 
        Seq(Point(30.0, 10.0), Point(10.0, 10.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink6 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad6, 0, 5, 1, RoadType.Unknown, Track.RightSide, Continuous,
        0L, 0L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), idRoad6, 0.0, 10.0, SideCode.Unknown, 0, (None, None), 
        Seq(Point(40.0, 20.0), Point(30.0, 20.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink7 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad7, 0, 5, 1, RoadType.Unknown, Track.LeftSide, Continuous,
        0L, 0L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), idRoad7, 0.0, 10.0, SideCode.Unknown, 0, (None, None), 
        Seq(Point(10.0, 10.0), Point(0.0, 10.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink8 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad8, 0, 5, 1, RoadType.Unknown, Track.RightSide, Continuous,
        0L, 0L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), idRoad8, 0.0, 20.0, SideCode.Unknown, 0, (None, None), 
        Seq(Point(30.0, 20.0),Point(10.0, 20.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink9 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad9, 0, 5, 1, RoadType.Unknown, Track.LeftSide, Continuous,
        0L, 0L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), idRoad9, 0.0, 10.0, SideCode.Unknown, 0, (None, None), 
        Seq(Point(0.0, 10.0), Point(0.0, 20.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink10 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad10, 0, 5, 1, RoadType.Unknown, Track.RightSide, Continuous,
        0L, 0L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), idRoad10, 0.0, 10.0, SideCode.Unknown, 0, (None, None), 
        Seq(Point(10.0, 20.0), Point(0.0, 20.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink11 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad11, 0, 5, 1, RoadType.Unknown, Track.LeftSide, Continuous,
        0L, 0L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), idRoad11, 0.0, 10.0, SideCode.Unknown, 0, (None, None), 
        Seq(Point(0.0, 20.0), Point(0.0, 30.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink12 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad12, 0, 5, 1, RoadType.Unknown, Track.RightSide, Continuous,
        0L, 0L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), idRoad12, 0.0, 10.0, SideCode.Unknown, 0, (None, None), 
        Seq(Point(10.0, 20.0), Point(10.0, 30.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))

      val listRight = List(projectLink1, projectLink3, projectLink6, projectLink8,
        projectLink10, projectLink12)

      val rightList = ProjectSectionCalculator.assignMValues(listRight).toList

      val list = ProjectSectionCalculator.assignMValues(rightList ::: List(projectLink0, projectLink2, projectLink4,projectLink5, projectLink7, projectLink9, projectLink11))
      val (left, right) = list.partition(_.track == Track.LeftSide)

      val (sortedLeft, sortedRight) = (left.sortBy(_.startAddrMValue), right.sortBy(_.startAddrMValue))
      sortedLeft.zip(sortedLeft.tail).forall{
        case (curr, next) => (curr.discontinuity == Continuous && curr.endAddrMValue == next.startAddrMValue && curr.connected(next)) || curr.discontinuity == MinorDiscontinuity
      }
      sortedRight.zip(sortedRight.tail).forall{
        case (curr, next) => (curr.discontinuity == Continuous && curr.endAddrMValue == next.startAddrMValue && curr.connected(next)) || curr.discontinuity == MinorDiscontinuity
      }

      ((sortedLeft.head.startAddrMValue == 0 && sortedLeft.head.startAddrMValue == 0) || (sortedLeft.last.startAddrMValue == 0 && sortedLeft.last.startAddrMValue == 0)) should be (true)

    }
  }

  test("Test yet another assignMValues When adding only New sections Then Direction for those should always be the same (on both Tracks) as the existing section (which are for e.g. Transfer, Unchanged, Numbering) making the addressMValues be sequential ") {
    runWithRollback{
      //(x,y)   -> (x,y)
      val idRoad0 = 0L //   N>  L(10, 0)   ->  (0, 10)
      val idRoad1 = 1L //   N>  R(20, 0) ->  (15, 10)
      val idRoad2 = 2L //   N>  L(0, 10)  ->  (10, 20)
      val idRoad3 = 3L //   N>  R(15, 10)  ->  (10, 20)
      val idRoad4 = 4L //   N>  L(10, 20)  ->  (40, 30)
      val idRoad5 = 5L //   N>  R(10, 20)  ->  (45, 25)
      val idRoad6 = 6L //   N>  L(40, 30)  ->  (50, 35)
      val idRoad7 = 7L //   N>  R(45, 25)  ->  (55, 30)
      val idRoad8 = 8L //   N>  L(45, 25)  ->  (40, 30)
      val idRoad9 = 9L //   N>  R(55, 30)  ->  (50, 35)
      val idRoad10 = 10L // N>  L(40, 30)  ->  (30, 40)
      val idRoad11 = 11L // N>  R(50, 35)  ->  (40, 50)


      //NEW
      val projectLink0 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad0, 0, 5, 1, RoadType.Unknown, Track.LeftSide, Continuous,
        0L, 0L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), idRoad0, 0.0, 10.0, SideCode.Unknown, 0, (None, None), 
        Seq(Point(10.0, 0.0), Point(0.0, 10.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink1 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad1, 0, 5, 1, RoadType.Unknown, Track.RightSide, Continuous,
        0L, 0L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), idRoad1, 0.0, 10.0, SideCode.Unknown, 0, (None, None), 
        Seq(Point(20.0, 0.0), Point(15.0, 10.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink2 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad2, 0, 5, 1, RoadType.Unknown, Track.LeftSide, Continuous,
        0L, 0L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), idRoad2, 0.0, 10.0, SideCode.Unknown, 0, (None, None), 
        Seq(Point(0.0, 10.0), Point(10.0, 20.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink3 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad3, 0, 5, 1, RoadType.Unknown, Track.RightSide, Continuous,
        0L, 0L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), idRoad3, 0.0, 10.0, SideCode.Unknown, 0, (None, None), 
        Seq(Point(15.0, 10.0),Point(10.0, 20.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink4 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad4, 0, 5, 1, RoadType.Unknown, Track.LeftSide, Continuous,
        0L, 0L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), idRoad4, 0.0, 10.0, SideCode.Unknown, 0, (None, None), 
        Seq(Point(10.0, 20.0), Point(40.0, 30.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink5 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad5, 0, 5, 1, RoadType.Unknown, Track.RightSide, Continuous,
        0L, 0L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), idRoad5, 0.0, 20.0, SideCode.Unknown, 0, (None, None), 
        Seq(Point(10.0, 20.0), Point(45.0, 25.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink6 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad6, 0, 5, 1, RoadType.Unknown, Track.LeftSide, Continuous,
        0L, 0L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), idRoad6, 0.0, 10.0, SideCode.Unknown, 0, (None, None), 
        Seq(Point(40.0, 30.0), Point(50.0, 35.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink7 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad7, 0, 5, 1, RoadType.Unknown, Track.RightSide, Continuous,
        0L, 0L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), idRoad7, 0.0, 10.0, SideCode.Unknown, 0, (None, None), 
        Seq(Point(45.0, 25.0), Point(55.0, 30.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink8 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad8, 0, 5, 1, RoadType.Unknown, Track.LeftSide, Continuous,
        0L, 0L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), idRoad8, 0.0, 20.0, SideCode.Unknown, 0, (None, None), 
        Seq(Point(45.0, 25.0),Point(40.0, 30.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink9 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad9, 0, 5, 1, RoadType.Unknown, Track.RightSide, Continuous,
        0L, 0L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), idRoad9, 0.0, 10.0, SideCode.Unknown, 0, (None, None), 
        Seq(Point(55.0, 30.0), Point(55.0, 35.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink10 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad10, 0, 5, 1, RoadType.Unknown, Track.LeftSide, Continuous,
        0L, 0L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), idRoad10, 0.0, 10.0, SideCode.Unknown, 0, (None, None), 
        Seq(Point(40.0, 30.0), Point(30.0, 40.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink11 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad11, 0, 5, 1, RoadType.Unknown, Track.RightSide, Continuous,
        0L, 0L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), idRoad11, 0.0, 10.0, SideCode.Unknown, 0, (None, None), 
        Seq(Point(55.0, 35.0), Point(40.0, 50.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))

      val listRight = List(projectLink1, projectLink3, projectLink5, projectLink7,
        projectLink9, projectLink11)

      val rightList = ProjectSectionCalculator.assignMValues(listRight).toList

      val list = ProjectSectionCalculator.assignMValues(rightList ::: List(projectLink0, projectLink2, projectLink4, projectLink6, projectLink8, projectLink10))
      val (left, right) = list.partition(_.track == Track.LeftSide)

      val (sortedLeft, sortedRight) = (left.sortBy(_.startAddrMValue), right.sortBy(_.startAddrMValue))
      sortedLeft.zip(sortedLeft.tail).forall{
        case (curr, next) => (curr.discontinuity == Continuous && curr.endAddrMValue == next.startAddrMValue && curr.connected(next)) || curr.discontinuity == MinorDiscontinuity
      }
      sortedRight.zip(sortedRight.tail).forall{
        case (curr, next) => (curr.discontinuity == Continuous && curr.endAddrMValue == next.startAddrMValue && curr.connected(next)) || curr.discontinuity == MinorDiscontinuity
      }

      ((sortedLeft.head.startAddrMValue == 0 && sortedLeft.head.startAddrMValue == 0) || (sortedLeft.last.startAddrMValue == 0 && sortedLeft.last.startAddrMValue == 0)) should be (true)

    }
  }

  test("Test assignMValues When having square MinorDiscontinuity links Then values should be properly assigned") {
    runWithRollback {
      val idRoad1 = 1L
      val idRoad2 = 2L
      val idRoad3 = 3L
      val idRoad4 = 4L
      val idRoad5 = 5L
      val idRoad6 = 6L
      val idRoad7 = 7L
      val idRoad8 = 8L
      val idRoad9 = 9L

      val geom1 = Seq(Point(188.243, 340.933), Point(257.618, 396.695))
      val geom2 = Seq(Point(193.962, 333.886), Point(263.596, 390.49))
      val geom3 = Seq(Point(163.456, 321.325), Point(188.243, 340.933))
      val geom4 = Seq(Point(169.578, 313.621), Point(193.962, 333.886))
      val geom5 = Seq(Point(150.106, 310.28), Point(163.456, 321.325))
      val geom6 = Seq(Point(155.553, 303.429), Point(169.578, 313.621))
      val geom7 = Seq(Point(155.553, 303.429), Point(150.106, 310.28))
      val geom8 = Seq(Point(162.991, 293.056), Point(155.553, 303.429))
      val geom9 = Seq(Point(370.169, 63.814), Point(162.991, 293.056))
      val projectLink1 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad1, 0, 5, 1, RoadType.MunicipalityStreetRoad, Track.RightSide, Continuous,
        0L, 0L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), 12345L, 0.0, 89.0, SideCode.AgainstDigitizing,
        0, (None, None), geom1, LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink2 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad2, 0, 5, 1, RoadType.MunicipalityStreetRoad, Track.LeftSide, Continuous,
        0L, 0L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), 12346L, 0.0, 89.7, SideCode.AgainstDigitizing,
        0, (None, None), geom2, LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink3 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad3, 0, 5, 1, RoadType.MunicipalityStreetRoad, Track.RightSide, Continuous,
        0L, 0L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), 12347L, 0.0, 31.6, SideCode.AgainstDigitizing,
        0, (None, None), geom3, LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink4 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad4, 0, 5, 1, RoadType.MunicipalityStreetRoad, Track.LeftSide, Continuous,
        0L, 0L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), 12348L, 0.0, 31.7, SideCode.AgainstDigitizing,
        0, (None, None), geom4, LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink5 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad5, 0, 5, 1, RoadType.MunicipalityStreetRoad, Track.RightSide, Continuous,
        0L, 0L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), 12349L, 0.0, 17.3, SideCode.AgainstDigitizing,
        0, (None, None), geom5, LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink6 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad6, 0, 5, 1, RoadType.MunicipalityStreetRoad, Track.LeftSide, MinorDiscontinuity,
        0L, 0L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), 12350L, 0.0, 17.3, SideCode.AgainstDigitizing,
        0, (None, None), geom6, LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink7 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad7, 0, 5, 1, RoadType.MunicipalityStreetRoad, Track.Combined, Continuous,
        0L, 0L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), 12351L, 0.0, 8.7, SideCode.AgainstDigitizing,
        0, (None, None), geom7, LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink8 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad8, 0, 5, 1, RoadType.MunicipalityStreetRoad, Track.Combined, Continuous,
        0L, 0L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), 12352L, 0.0, 12.8, SideCode.AgainstDigitizing,
        0, (None, None), geom8, LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink9 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad9, 0, 5, 1, RoadType.MunicipalityStreetRoad, Track.Combined, EndOfRoad,
        0L, 0L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), 12353L, 0.0, 308.9, SideCode.AgainstDigitizing,
        0, (None, None), geom9, LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))

      val projectLinkSeq = Seq(projectLink1, projectLink2, projectLink3, projectLink4, projectLink5, projectLink6, projectLink7, projectLink8, projectLink9)
      val output = ProjectSectionCalculator.assignMValues(projectLinkSeq)
      output.length should be(9)

      val (leftCombined, rightCombined): (Seq[ProjectLink], Seq[ProjectLink]) = (output.filter(_.track != Track.RightSide).sortBy(_.startAddrMValue), output.filter(_.track != Track.LeftSide).sortBy(_.startAddrMValue))

      leftCombined.zip(leftCombined.tail).foreach {
        case (prev, next) =>
          prev.endAddrMValue should be(next.startAddrMValue)
      }

      rightCombined.zip(rightCombined.tail).foreach {
        case (prev, next) =>
          prev.endAddrMValue should be(next.startAddrMValue)
      }
    }
  }

  test("Test assignMValues When having triangular MinorDiscontinuity links Then values should be properly assigned") {
    runWithRollback {
      val idRoad1 = 1L
      val idRoad2 = 2L
      val idRoad3 = 3L
      val idRoad4 = 4L
      val idRoad5 = 5L
      val idRoad6 = 6L
      val idRoad7 = 7L
      val idRoad8 = 8L
      val idRoad9 = 9L
      val idRoad10 = 10L
      val idRoad11 = 11L
      val idRoad12 = 12L
      val idRoad13 = 13L
      val idRoad14 = 14L
      val idRoad15 = 15L

      val geom1 = Seq(Point(4070.023, 474.741), Point(4067.966, 485.143))
      val geom2 = Seq(Point(4067.966, 485.143), Point(4060.025, 517.742))
      val geom3 = Seq(Point(4060.025, 517.742), Point(4063.384, 533.616))
      val geom4 = Seq(Point(4060.025, 517.742), Point(4071.924, 520.061))
      val geom5 = Seq(Point(3960.625, 513.012), Point(4060.025, 517.742))
      val geom6 = Seq(Point(3941.276, 512.61), Point(3960.625, 513.012))
      val geom7 = Seq(Point(3872.632, 510.988), Point(3941.2755, 512.6099))
      val geom8 = Seq(Point(4081.276, 475.638), Point(4078.719, 485.954))
      val geom9 = Seq(Point(4078.719, 485.954), Point(4071.924, 520.061))
      val geom10 = Seq(Point(4071.924, 520.061), Point(4065.662, 530.002))
      val geom11 = Seq(Point(4065.662, 530.002), Point(4063.384, 533.617))
      val geom12 = Seq(Point(3955.063, 524.51), Point(4063.384, 533.617))
      val geom13 = Seq(Point(3935.859, 524.055), Point(3955.062, 524.509))
      val geom14 = Seq(Point(3879.928, 522.732), Point(3935.859, 524.055))
      val geom15 = Seq(Point(3879.928, 522.732), Point(3834.911, 658.374))

//      LeftSide
      val projectLink1 = toProjectLink(rap, LinkStatus.Transfer)(RoadAddress(idRoad1, 0, 5, 1, RoadType.MunicipalityStreetRoad, Track.LeftSide, Continuous,
        0L, 9L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), 12345L, 0.0, 10.6, SideCode.TowardsDigitizing,
        0, (None, None), geom1, LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink2 = toProjectLink(rap, LinkStatus.Transfer)(RoadAddress(idRoad2, 0, 5, 1, RoadType.MunicipalityStreetRoad, Track.LeftSide, Continuous,
        9L, 38L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), 12346L, 0.0, 33.55, SideCode.TowardsDigitizing,
        0, (None, None), geom2, LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink3 = toProjectLink(rap, LinkStatus.Transfer)(RoadAddress(idRoad3, 0, 5, 1, RoadType.MunicipalityStreetRoad, Track.LeftSide, MinorDiscontinuity,
        38L, 52L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), 12347L, 0.0, 16.22, SideCode.TowardsDigitizing,
        0, (None, None), geom3, LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink4 = toProjectLink(rap, LinkStatus.Transfer)(RoadAddress(idRoad4, 0, 5, 1, RoadType.MunicipalityStreetRoad, Track.LeftSide, Continuous,
        52L, 62L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), 12348L, 0.0, 12.1, SideCode.AgainstDigitizing,
        0, (None, None), geom4, LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink5 = toProjectLink(rap, LinkStatus.Transfer)(RoadAddress(idRoad5, 0, 5, 1, RoadType.MunicipalityStreetRoad, Track.LeftSide, Continuous,
        62L, 148L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), 12349L, 0.0, 99.51, SideCode.AgainstDigitizing,
        0, (None, None), geom5, LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink6 = toProjectLink(rap, LinkStatus.Transfer)(RoadAddress(idRoad6, 0, 5, 1, RoadType.MunicipalityStreetRoad, Track.LeftSide, Continuous,
        148L, 165L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), 12350L, 0.0, 19.35, SideCode.AgainstDigitizing,
        0, (None, None), geom6, LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink7 = toProjectLink(rap, LinkStatus.Transfer)(RoadAddress(idRoad7, 0, 5, 1, RoadType.MunicipalityStreetRoad, Track.LeftSide, MinorDiscontinuity,
        165L, 224L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), 12351L, 0.0, 68.66, SideCode.AgainstDigitizing,
        0, (None, None), geom7, LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      //RightSide
      val projectLink8 = toProjectLink(rap, LinkStatus.NotHandled)(RoadAddress(idRoad8, 0, 5, 1, RoadType.MunicipalityStreetRoad, Track.RightSide, Continuous,
        0L, 10L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), 12352L, 0.0, 10.6, SideCode.TowardsDigitizing,
        0, (None, None), geom8, LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink9 = toProjectLink(rap, LinkStatus.NotHandled)(RoadAddress(idRoad9, 0, 5, 1, RoadType.MunicipalityStreetRoad, Track.RightSide, Continuous,
        10L, 41L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), 12353L, 0.0, 34.77, SideCode.TowardsDigitizing,
        0, (None, None), geom9, LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink10 = toProjectLink(rap, LinkStatus.NotHandled)(RoadAddress(idRoad10, 0, 5, 1, RoadType.MunicipalityStreetRoad, Track.RightSide, Continuous,
        41L, 52L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), 12354L, 0.0, 11.74, SideCode.TowardsDigitizing,
        0, (None, None), geom10, LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink11 = toProjectLink(rap, LinkStatus.NotHandled)(RoadAddress(idRoad11, 0, 5, 1, RoadType.MunicipalityStreetRoad, Track.RightSide, Continuous,
        52L, 56L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), 12355L, 0.0, 4.27, SideCode.TowardsDigitizing,
        0, (None, None), geom11, LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink12 = toProjectLink(rap, LinkStatus.NotHandled)(RoadAddress(idRoad12, 0, 5, 1, RoadType.MunicipalityStreetRoad, Track.RightSide, Continuous,
        56L, 155L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), 12356L, 0.0, 108.7, SideCode.AgainstDigitizing,
        0, (None, None), geom12, LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink13 = toProjectLink(rap, LinkStatus.NotHandled)(RoadAddress(idRoad13, 0, 5, 1, RoadType.MunicipalityStreetRoad, Track.RightSide, Continuous,
        155L, 173L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), 12357L, 0.0, 19.2, SideCode.AgainstDigitizing,
        0, (None, None), geom13, LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink14 = toProjectLink(rap, LinkStatus.NotHandled)(RoadAddress(idRoad14, 0, 5, 1, RoadType.MunicipalityStreetRoad, Track.RightSide, Continuous,
        173L, 224L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), 12358L, 0.0, 55.94, SideCode.AgainstDigitizing,
        0, (None, None), geom14, LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))

      val projectLinkSeq = Seq(projectLink1, projectLink2, projectLink3, projectLink4, projectLink5, projectLink6, projectLink7, projectLink8, projectLink9, projectLink10,
        projectLink11, projectLink12, projectLink13, projectLink14)

      val output = ProjectSectionCalculator.assignMValues(projectLinkSeq)

      output.length should be(14)

      val (leftCombined, rightCombined): (Seq[ProjectLink], Seq[ProjectLink]) = (output.filter(_.track != Track.RightSide).sortBy(_.startAddrMValue), output.filter(_.track != Track.LeftSide).sortBy(_.startAddrMValue))

      leftCombined.zip(leftCombined.tail).foreach {
        case (prev, next) =>
          prev.endAddrMValue should be(next.startAddrMValue)
      }

      rightCombined.zip(rightCombined.tail).foreach {
        case (prev, next) =>
          prev.endAddrMValue should be(next.startAddrMValue)
      }
    }

  }
}

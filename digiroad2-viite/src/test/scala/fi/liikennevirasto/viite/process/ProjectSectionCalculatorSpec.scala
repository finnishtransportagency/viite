package fi.liikennevirasto.viite.process

import fi.liikennevirasto.GeometryUtils
import fi.liikennevirasto.digiroad2.asset.SideCode.{AgainstDigitizing, TowardsDigitizing}
import fi.liikennevirasto.digiroad2.asset.{AdministrativeClass, LinkGeomSource, SideCode}
import fi.liikennevirasto.digiroad2.dao.Sequences
import fi.liikennevirasto.digiroad2.postgis.PostGISDatabase
import fi.liikennevirasto.digiroad2.service.RoadLinkService
import fi.liikennevirasto.digiroad2.util.Track
import fi.liikennevirasto.digiroad2.{DigiroadEventBus, Point, asset}
import fi.liikennevirasto.viite._
import fi.liikennevirasto.viite.dao.CalibrationPointDAO.CalibrationPointType.{JunctionPointCP, NoCP, RoadAddressCP}
import fi.liikennevirasto.viite.dao.Discontinuity._
import fi.liikennevirasto.viite.dao.ProjectCalibrationPointDAO.UserDefinedCalibrationPoint
import fi.liikennevirasto.viite.dao.TerminationCode.NoTermination
import fi.liikennevirasto.viite.dao.{LinkStatus, _}
import fi.liikennevirasto.viite.util._
import org.joda.time.DateTime
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{FunSuite, Matchers}
import slick.driver.JdbcDriver.backend.Database
import slick.driver.JdbcDriver.backend.Database.dynamicSession

class ProjectSectionCalculatorSpec extends FunSuite with Matchers {
  val mockRoadLinkService: RoadLinkService = MockitoSugar.mock[RoadLinkService]
  val mockNodesAndJunctionsService = MockitoSugar.mock[NodesAndJunctionsService]
  val mockEventBus: DigiroadEventBus = MockitoSugar.mock[DigiroadEventBus]
  val mockRoadwayAddressMapper: RoadwayAddressMapper = MockitoSugar.mock[RoadwayAddressMapper]
  val mockRoadwayDAO = MockitoSugar.mock[RoadwayDAO]
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
  val roadAddressService: RoadAddressService = new RoadAddressService(mockRoadLinkService, roadwayDAO, linearLocationDAO,
    roadNetworkDAO, roadwayPointDAO, nodePointDAO, junctionPointDAO, mockRoadwayAddressMapper, mockEventBus, frozenVVH = false) {

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
    Database.forDataSource(PostGISDatabase.ds).withDynTransaction {
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
    val startDate = p.startDate.getOrElse(DateTime.now()).minusDays(1)

    (LinearLocation(p.linearLocationId, 1, p.linkId, p.startMValue, p.endMValue, p.sideCode, p.linkGeometryTimeStamp,
      (CalibrationPointsUtils.toCalibrationPointReference(p.startCalibrationPoint),
        CalibrationPointsUtils.toCalibrationPointReference(p.endCalibrationPoint)),
      p.geometry, p.linkGeomSource,
      p.roadwayNumber, Some(startDate), p.endDate),
      Roadway(p.roadwayId, p.roadwayNumber, p.roadNumber, p.roadPartNumber, p.administrativeClass, p.track, p.discontinuity, p.startAddrMValue, p.endAddrMValue, p.reversed, startDate, p.endDate,
        p.createdBy.getOrElse("-"), p.roadName, p.ely, TerminationCode.NoTermination, DateTime.now(), None))
  }

  def toRoadwaysAndLinearLocations(pls: Seq[ProjectLink]): (Seq[LinearLocation], Seq[Roadway]) = {
    pls.foldLeft((Seq.empty[LinearLocation], Seq.empty[Roadway])) { (list, p) =>
      val startDate = p.startDate.getOrElse(DateTime.now()).minusDays(1)

      (list._1 :+ LinearLocation(p.linearLocationId, 1, p.linkId, p.startMValue, p.endMValue, p.sideCode, p.linkGeometryTimeStamp,
        (CalibrationPointsUtils.toCalibrationPointReference(p.startCalibrationPoint),
          CalibrationPointsUtils.toCalibrationPointReference(p.endCalibrationPoint)),
        p.geometry, p.linkGeomSource,
        p.roadwayNumber, Some(startDate), p.endDate), list._2 :+ Roadway(p.roadwayId, p.roadwayNumber, p.roadNumber, p.roadPartNumber, p.administrativeClass, p.track, p.discontinuity, p.startAddrMValue, p.endAddrMValue, p.reversed, startDate, p.endDate,
        p.createdBy.getOrElse("-"), p.roadName, p.ely, TerminationCode.NoTermination, DateTime.now(), None))
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

  test("Test assignMValues When assigning values for new road addresses Then values should be properly assigned") {
    runWithRollback {
      val idRoad0 = 0L
      val idRoad1 = 1L
      val idRoad2 = 2L
      val idRoad3 = 3L
      val projectLink0 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad0, 0, 5, 1, AdministrativeClass.Unknown, Track.Combined, Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), 12345L, 0.0, 9.8, SideCode.TowardsDigitizing, 0, (None, None), Seq(Point(0.0, 0.0), Point(0.0, 9.8)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink1 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad1, 0, 5, 1, AdministrativeClass.Unknown, Track.Combined, Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), 12346L, 0.0, 9.8, SideCode.TowardsDigitizing, 0, (None, None), Seq(Point(0.0, 30.0), Point(0.0, 39.8)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink2 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad2, 0, 5, 1, AdministrativeClass.Unknown, Track.Combined, Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), 12347L, 0.0, 9.8, SideCode.TowardsDigitizing, 0, (None, None), Seq(Point(0.0, 20.2), Point(0.0, 30.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink3 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad3, 0, 5, 1, AdministrativeClass.Unknown, Track.Combined, Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), 12348L, 0.0, 10.4, SideCode.TowardsDigitizing, 0, (None, None), Seq(Point(0.0, 9.8), Point(0.0, 20.2)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))

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

      output(3).calibrationPoints should be(None, Some(CalibrationPoint(12346, Math.round(9.799999999999997*10.0)/10.0, 40, RoadAddressCP)))

      output.head.calibrationPoints should be(Some(CalibrationPoint(12345, 0.0, 0, RoadAddressCP)), None)
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
      val projectLink0 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad0, 0, 5, 1, AdministrativeClass.Unknown, Track.Combined, Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad0, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), Seq(Point(0.0, 0.0), Point(0.0, 9.8)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink1 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad1, 0, 5, 1, AdministrativeClass.Unknown, Track.RightSide, Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad1, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), Seq(Point(0.0, 9.8), Point(-2.0, 20.2)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink2 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad2, 0, 5, 1, AdministrativeClass.Unknown, Track.LeftSide, Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad2, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), Seq(Point(0.0, 9.8), Point(2.0, 19.2)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink3 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad3, 0, 5, 1, AdministrativeClass.Unknown, Track.RightSide, Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad3, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), Seq(Point(-2.0, 20.2), Point(1.0, 30.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink4 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad4, 0, 5, 1, AdministrativeClass.Unknown, Track.LeftSide, Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad4, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), Seq(Point(2.0, 19.2), Point(1.0, 30.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink5 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad5, 0, 5, 1, AdministrativeClass.Unknown, Track.Combined, Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad5, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), Seq(Point(1.0, 30.0), Point(0.0, 48.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink6 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad6, 0, 5, 1, AdministrativeClass.Unknown, Track.RightSide, Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad6, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), Seq(Point(0.0, 48.0), Point(2.0, 68.0), Point(0.0, 96.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink7 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad7, 0, 5, 1, AdministrativeClass.Unknown, Track.LeftSide, Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad7, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), Seq(Point(0.0, 48.0), Point(-2.0, 68.0), Point(0.0, 96.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink8 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad8, 0, 5, 1, AdministrativeClass.Unknown, Track.Combined, Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad8, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), Seq(Point(0.0, 96.0), Point(0.0, 148.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))

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
      val projectLink0 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad0, 0, 5, 1, AdministrativeClass.Unknown, Track.Combined, Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad0, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), Seq(Point(0.0, 0.0), Point(0.0, 9.8)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink1 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad1, 0, 5, 1, AdministrativeClass.Unknown, Track.RightSide, Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad1, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), Seq(Point(0.0, 9.8), Point(-2.0, 20.2)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink2 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad2, 0, 5, 1, AdministrativeClass.Unknown, Track.LeftSide, Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad2, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), Seq(Point(0.0, 9.8), Point(2.0, 19.2)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink3 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad3, 0, 5, 1, AdministrativeClass.Unknown, Track.RightSide, Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad3, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), Seq(Point(-2.0, 20.2), Point(1.0, 30.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink4 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad4, 0, 5, 1, AdministrativeClass.Unknown, Track.LeftSide, Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad4, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), Seq(Point(2.0, 19.2), Point(1.0, 30.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink5 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad5, 0, 5, 1, AdministrativeClass.Unknown, Track.Combined, Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad5, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), Seq(Point(1.0, 30.0), Point(0.0, 48.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink6 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad6, 0, 5, 1, AdministrativeClass.Unknown, Track.RightSide, Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad6, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), Seq(Point(0.0, 48.0), Point(2.0, 68.0), Point(0.0, 96.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink7 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad7, 0, 5, 1, AdministrativeClass.Unknown, Track.LeftSide, Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad7, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), Seq(Point(0.0, 48.0), Point(-2.0, 68.0), Point(0.0, 96.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink8 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad8, 0, 5, 1, AdministrativeClass.Unknown, Track.Combined, Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad8, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), Seq(Point(0.0, 96.0), Point(0.0, 148.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))

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

  test("Test assignMValues When addressing calibration Points and mixed directions Then values should be properly assigned") {
    runWithRollback {
      val idRoad0 = 0L //   >
      val idRoad1 = 1L //     <
      val idRoad2 = 2L //   >
      val idRoad3 = 3L //     <
      val projectLink0 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad0, 0, 5, 1, AdministrativeClass.Unknown, Track.Combined, Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad0, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), Seq(Point(0.0, 0.0), Point(0.0, 9.8)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink1 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad1, 0, 5, 1, AdministrativeClass.Unknown, Track.Combined, Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad1, 0.0, 0.0, SideCode.AgainstDigitizing, 0, (None, None), Seq(Point(4.0, 7.5), Point(0.0, 9.8)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink2 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad2, 0, 5, 1, AdministrativeClass.Unknown, Track.Combined, Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad2, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), Seq(Point(4.0, 7.5), Point(6.0, 19.2)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink3 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad3, 0, 5, 1, AdministrativeClass.Unknown, Track.Combined, Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad3, 0.0, 0.0, SideCode.AgainstDigitizing, 0, (None, None), Seq(Point(10.0, 15.0), Point(6.0, 19.2)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))

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
      val projectLink0 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad0, 0, 5, 1, AdministrativeClass.Unknown, Track.Combined, Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad0, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), Seq(Point(20.0, 10.0), Point(28, 15)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink1 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad1, 0, 5, 1, AdministrativeClass.Unknown, Track.RightSide, Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad1, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), Seq(Point(42, 14), Point(28, 15)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink2 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad2, 0, 5, 1, AdministrativeClass.Unknown, Track.LeftSide, Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad2, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), Seq(Point(28, 15), Point(75, 19.2)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val list = List(projectLink0, projectLink1, projectLink2)
      val ordered = ProjectSectionCalculator.assignMValues(list).map(trackMap).toMap
      // Test that the result is not dependent on the order of the links
      list.permutations.foreach(l => {
        ProjectSectionCalculator.assignMValues(l).map(trackMap).toMap should be(ordered)
      })
    }
  }

  test("Test assignMValues When giving one link Towards and one Against Digitizing Then calibrations Points should be properly assigned") {
    runWithRollback {
      val geometry = Seq(Point(20.0, 10.0), Point(28, 15))
      val projectLink0T = toProjectLink(rap, LinkStatus.New)(RoadAddress(0L, 0, 5, 1, AdministrativeClass.Unknown, Track.Combined, Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), 0L, 0.0, GeometryUtils.geometryLength(geometry), SideCode.TowardsDigitizing, 0, (None, None), geometry, LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink0A = toProjectLink(rap, LinkStatus.New)(RoadAddress(0L, 0, 5, 1, AdministrativeClass.Unknown, Track.Combined, Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), 0L, 0.0, GeometryUtils.geometryLength(geometry), SideCode.AgainstDigitizing, 0, (None, None), geometry, LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))

      val towards = ProjectSectionCalculator.assignMValues(Seq(projectLink0T)).head
      val against = ProjectSectionCalculator.assignMValues(Seq(projectLink0A)).head
      towards.sideCode should be(SideCode.TowardsDigitizing)
      against.sideCode should be(SideCode.AgainstDigitizing)
      towards.calibrationPoints._1 should be(Some(CalibrationPoint(0, 0.0, 0, RoadAddressCP)))
      towards.calibrationPoints._2 should be(Some(CalibrationPoint(0, projectLink0T.geometryLength, 9, RoadAddressCP)))
      against.calibrationPoints._2 should be(Some(CalibrationPoint(0, 0.0, 9, RoadAddressCP)))
      against.calibrationPoints._1 should be(Some(CalibrationPoint(0, projectLink0A.geometryLength, 0, RoadAddressCP)))
    }
  }

  test("Test assignMValues When giving links without opposite track Then exception is thrown and links are returned as-is") {
    runWithRollback {
      val projectLink0 = toProjectLink(rap, LinkStatus.New)(RoadAddress(0L, 0, 5, 1, AdministrativeClass.Unknown, Track.RightSide, Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), 0L, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), Seq(Point(20.0, 10.0), Point(28, 15)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink1 = toProjectLink(rap, LinkStatus.New)(RoadAddress(1L, 0, 5, 1, AdministrativeClass.Unknown, Track.RightSide, Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), 1L, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), Seq(Point(28.0, 15.0), Point(38, 15)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))

      ProjectSectionCalculator.assignMValues(Seq(projectLink0, projectLink1))
    }
  }

  test("Test assignMValues When giving incompatible digitization on tracks Then the direction of left track is corrected to match the right track") {
    runWithRollback {
      val idRoad0 = 0L //   R<
      val idRoad1 = 1L //   R<
      val idRoad2 = 2L //   L<    <- Note! Incompatible, means the addressing direction is against the right track
      val idRoad3 = 3L //   L<    <- Note! Incompatible, means the addressing direction is against the right track
      val projectLink0 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad0, 0, 5, 1, AdministrativeClass.Unknown, Track.RightSide, Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad0, 0.0, 0.0, SideCode.AgainstDigitizing, 0, (Some(CalibrationPoint(0L, 0.0, 0L)), None), Seq(Point(28, 9.8), Point(20.0, 10.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink1 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad1, 0, 5, 1, AdministrativeClass.Unknown, Track.RightSide, Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad1, 0.0, 0.0, SideCode.AgainstDigitizing, 0, (None, None), Seq(Point(42, 9.7), Point(28, 9.8)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink2 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad2, 0, 5, 1, AdministrativeClass.Unknown, Track.LeftSide, Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad2, 0.0, 0.0, SideCode.AgainstDigitizing, 0, (None, None), Seq(Point(20, 10.1), Point(28, 10.2)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink3 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad3, 0, 5, 1, AdministrativeClass.Unknown, Track.LeftSide, Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad3, 0.0, 0.0, SideCode.AgainstDigitizing, 0, (None, None), Seq(Point(28, 10.2), Point(42, 10.3)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
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
      val projectLink0 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad0, 0, 5, 1, AdministrativeClass.Unknown, Track.LeftSide, Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad0, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), Seq(Point(20.0, 10.0), Point(28, 15)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink1 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad1, 0, 5, 1, AdministrativeClass.Unknown, Track.LeftSide, Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad1, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), Seq(Point(28, 15), Point(42, 19)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink2 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad2, 0, 5, 1, AdministrativeClass.Unknown, Track.LeftSide, Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad2, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), Seq(Point(42, 19), Point(75, 29.2)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink3 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad3, 0, 5, 1, AdministrativeClass.Unknown, Track.LeftSide, Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad3, 0.0, 0.0, SideCode.AgainstDigitizing, 0, (None, None), Seq(Point(103.0, 15.0), Point(75, 29.2)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink4 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad4, 0, 5, 1, AdministrativeClass.Unknown, Track.RightSide, Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad4, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), Seq(Point(20.0, 10.0), Point(42, 11)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink5 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad5, 0, 5, 1, AdministrativeClass.Unknown, Track.RightSide, Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad5, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), Seq(Point(42, 11), Point(103, 15)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
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

  test("Test assignMValues When giving links in different tracks and RoadAddressCP in the middle Then proper calibration Points should be created for each track and RoadAddressCPs cleared from the middle") {
    runWithRollback {
      // Left track = 85.308 meters
      val idRoad0 = 0L //   L>
      val idRoad1 = 1L //   L>
      val idRoad2 = 2L //   L>
      val idRoad3 = 3L //   L<
      // Right track = 83.154 meters
      val idRoad4 = 4L //   R>
      val idRoad5 = 5L //   R>

      val geom0 = Seq(Point(20.0, 10.0), Point(28, 15))
      val geom1 = Seq(Point(28, 15), Point(42, 19))
      val geom2 = Seq(Point(42, 19), Point(75, 29.2))
      val geom3 = Seq(Point(103.0, 15.0), Point(75, 29.2))
      val geom4 = Seq(Point(20.0, 10.0), Point(42, 11))
      val geom5 = Seq(Point(42, 11), Point(103, 15))
      val projectLink0 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad0, 0, 5, 1, AdministrativeClass.Unknown, Track.LeftSide, Continuous, 0L, 9L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad0, 0.0, GeometryUtils.geometryLength(geom0), SideCode.TowardsDigitizing, 0, (None, Some(CalibrationPoint(idRoad0, 9.0, 9L, RoadAddressCP))), geom0, LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink1 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad1, 0, 5, 1, AdministrativeClass.Unknown, Track.LeftSide, Continuous, 9L, 20L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad1, 0.0, GeometryUtils.geometryLength(geom1), SideCode.TowardsDigitizing, 0, (Some(CalibrationPoint(idRoad1, 0.0, 9L, RoadAddressCP)), None), geom1, LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink2 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad2, 0, 5, 1, AdministrativeClass.Unknown, Track.LeftSide, Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad2, 0.0, GeometryUtils.geometryLength(geom2), SideCode.TowardsDigitizing, 0, (None, None), geom2, LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink3 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad3, 0, 5, 1, AdministrativeClass.Unknown, Track.LeftSide, Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad3, 0.0, GeometryUtils.geometryLength(geom3), SideCode.AgainstDigitizing, 0, (None, None), geom3, LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink4 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad4, 0, 5, 1, AdministrativeClass.Unknown, Track.RightSide, Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad4, 0.0, GeometryUtils.geometryLength(geom4), SideCode.TowardsDigitizing, 0, (None, None), geom4, LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink5 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad5, 0, 5, 1, AdministrativeClass.Unknown, Track.RightSide, Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad5, 0.0, GeometryUtils.geometryLength(geom5), SideCode.TowardsDigitizing, 0, (None, None), geom5, LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val list = List(projectLink0, projectLink1, projectLink2, projectLink3, projectLink4, projectLink5)
      val ordered = ProjectSectionCalculator.assignMValues(list)
      ordered.flatMap(_.calibrationPoints._1) should have size 2
      ordered.flatMap(_.calibrationPoints._2) should have size 2
    }
  }

  test("Test assignMValues When giving links in one track with JunctionPointCP in the middle Then proper calibration Points should be created and JunctionPointCPs kept in place") {
    runWithRollback {
      val idRoad0 = 0L
      val idRoad1 = 1L
      val idRoad2 = 2L
      val idRoad3 = 3L

      val geom0 = Seq(Point(20.0, 10.0), Point(28, 15))
      val geom1 = Seq(Point(28, 15), Point(42, 19))
      val geom2 = Seq(Point(42, 19), Point(75, 29.2))
      val geom3 = Seq(Point(103.0, 15.0), Point(75, 29.2))
      val projectLink0 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad0, 0, 5, 1, AdministrativeClass.Unknown, Track.Combined, Continuous, 0L, 9L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad0, 0.0, GeometryUtils.geometryLength(geom0), SideCode.TowardsDigitizing, 0, (None, Some(CalibrationPoint(idRoad0, 9.0, 9L, JunctionPointCP))), geom0, LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink1 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad1, 0, 5, 1, AdministrativeClass.Unknown, Track.Combined, Continuous, 9L, 20L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad1, 0.0, GeometryUtils.geometryLength(geom1), SideCode.TowardsDigitizing, 0, (Some(CalibrationPoint(idRoad1, 0.0, 9L, JunctionPointCP)), None), geom1, LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink2 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad2, 0, 5, 1, AdministrativeClass.Unknown, Track.Combined, Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad2, 0.0, GeometryUtils.geometryLength(geom2), SideCode.TowardsDigitizing, 0, (None, None), geom2, LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink3 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad3, 0, 5, 1, AdministrativeClass.Unknown, Track.Combined, Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad3, 0.0, GeometryUtils.geometryLength(geom3), SideCode.AgainstDigitizing, 0, (None, None), geom3, LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val list = List(projectLink0, projectLink1, projectLink2, projectLink3)
      val ordered = ProjectSectionCalculator.assignMValues(list)
      ordered.flatMap(_.calibrationPoints._1) should have size 2
      ordered.flatMap(_.calibrationPoints._2) should have size 2
      ordered.head.startCalibrationPointType should be(RoadAddressCP)
      ordered.filter(p => p.startAddrMValue == 0).head.endCalibrationPointType should be(JunctionPointCP)
      ordered.filter(p => p.startAddrMValue == 9).head.startCalibrationPointType should be(JunctionPointCP)
      ordered.last.endCalibrationPointType should be(RoadAddressCP)
    }
  }

  test("Test assignMValues When giving links in one track with JunctionPointCP at the beginning and end Then JunctionPointCPs should be changed to RoadAddressCPs") {
    runWithRollback {
      val idRoad0 = 0L
      val idRoad1 = 1L
      val idRoad2 = 2L
      val idRoad3 = 3L

      val geom0 = Seq(Point(20.0, 10.0), Point(28, 15))
      val geom1 = Seq(Point(28, 15), Point(42, 19))
      val geom2 = Seq(Point(42, 19), Point(75, 29.2))
      val geom3 = Seq(Point(103.0, 15.0), Point(75, 29.2))
      val projectLink0 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad0, 0, 5, 1, AdministrativeClass.Unknown, Track.Combined, Continuous, 0L, 9L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad0, 0.0, GeometryUtils.geometryLength(geom0), SideCode.TowardsDigitizing, 0, (Some(CalibrationPoint(idRoad0, 0.0, 0L, JunctionPointCP)), None), geom0, LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink1 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad1, 0, 5, 1, AdministrativeClass.Unknown, Track.Combined, Continuous, 9L, 20L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad1, 0.0, GeometryUtils.geometryLength(geom1), SideCode.TowardsDigitizing, 0, (None, None), geom1, LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink2 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad2, 0, 5, 1, AdministrativeClass.Unknown, Track.Combined, Continuous, 20L, 30L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad2, 0.0, GeometryUtils.geometryLength(geom2), SideCode.TowardsDigitizing, 0, (None, None), geom2, LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink3 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad3, 0, 5, 1, AdministrativeClass.Unknown, Track.Combined, Continuous, 30L, 40L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad3, 0.0, GeometryUtils.geometryLength(geom3), SideCode.AgainstDigitizing, 0, (None, Some(CalibrationPoint(idRoad3, GeometryUtils.geometryLength(geom3), 40L, JunctionPointCP))), geom3, LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val list = List(projectLink0, projectLink1, projectLink2, projectLink3)
      val ordered = ProjectSectionCalculator.assignMValues(list)
      ordered.flatMap(_.calibrationPoints._1) should have size 1
      ordered.flatMap(_.calibrationPoints._2) should have size 1
      ordered.head.startCalibrationPointType should be(RoadAddressCP)
      ordered.last.endCalibrationPointType should be(RoadAddressCP)
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
      val projectLink0 = toProjectLink(rap, LinkStatus.UnChanged)(RoadAddress(idRoad0, 0, 5, 1, AdministrativeClass.Unknown, Track.Combined, Continuous, 0L, 8L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), idRoad0, 0.0, 8.0, SideCode.TowardsDigitizing, 0, (Some(CalibrationPoint(0L, 0.0, 0L)), None), Seq(Point(20.0, 10.0), Point(28, 10)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0)).copy(projectId = rap.id, roadwayId = roadwayId, linearLocationId = linearLocationId, roadwayNumber = roadwayNumber)
      val projectLink1 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad1, 0, 5, 1, AdministrativeClass.Unknown, Track.LeftSide, Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), idRoad1, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), Seq(Point(28, 10), Point(28, 19)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0)).copy(projectId = rap.id)
      val projectLink2 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad2, 0, 5, 1, AdministrativeClass.Unknown, Track.RightSide, Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), idRoad2, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), Seq(Point(28, 1), Point(28, 10)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0)).copy(projectId = rap.id)

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
      val idRoad1 = 1L //   U>
      val idRoad2 = 2L //   U>
      val idRoad3 = 3L //   N>
      /*
      |--U-->|--U-->|--N-->|
       */
      val linearLocationId = Sequences.nextLinearLocationId
      val roadwayNumber1 = Sequences.nextRoadwayNumber
      val roadwayNumber2 = Sequences.nextRoadwayNumber

      val geom1 = Seq(Point(20.0, 10.0), Point(28, 10))
      val geom2 = Seq(Point(28, 10), Point(28, 19))
      val geom3 = Seq(Point(28, 19), Point(28, 30))
      val raMap: Map[Long, RoadAddress] = Map(
        idRoad1 -> RoadAddress(idRoad1, linearLocationId, 5, 1, AdministrativeClass.Unknown, Track.Combined, Continuous, 0L, 9L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), idRoad1, 0.0, GeometryUtils.geometryLength(geom1), SideCode.TowardsDigitizing, 0, (Some(CalibrationPoint(idRoad1, 0.0, 0L, RoadAddressCP)), None), geom1, LinkGeomSource.NormalLinkInterface, 8, NoTermination, roadwayNumber1),
        idRoad2 -> RoadAddress(idRoad2, linearLocationId + 1, 5, 1, AdministrativeClass.Unknown, Track.Combined, Continuous, 9L, 19L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), idRoad2, 0.0, GeometryUtils.geometryLength(geom2), SideCode.TowardsDigitizing, 0, (None, Some(CalibrationPoint(idRoad2, 9.0, 19L, RoadAddressCP))), geom2, LinkGeomSource.NormalLinkInterface, 8, NoTermination, roadwayNumber2),
        idRoad3 -> RoadAddress(idRoad3, 0, 5, 1, AdministrativeClass.Unknown, Track.Combined, Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), idRoad3, 0.0, GeometryUtils.geometryLength(geom3), SideCode.TowardsDigitizing, 0, (None, None), geom3, LinkGeomSource.NormalLinkInterface, 8, NoTermination, NewIdValue)
      )

      val projId = Sequences.nextViiteProjectId
      val project = Project(projId, ProjectState.Incomplete, "f", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
        "", Seq(), Seq(), None, None)

      val projectLink1 = toProjectLink(rap, LinkStatus.UnChanged)(raMap(idRoad1))
      val projectLink2 = toProjectLink(rap, LinkStatus.UnChanged)(raMap(idRoad2))
      val projectLink3 = toProjectLink(rap, LinkStatus.New)(raMap(idRoad3))
      val list = List(projectLink1, projectLink2, projectLink3).map(_.copy(projectId = projId))

      val (linearLocation1, roadway1) = toRoadwayAndLinearLocation(projectLink1)
      val (linearLocation2, roadway2) = toRoadwayAndLinearLocation(projectLink2)

      buildTestDataForProject(Some(project), Some(Seq(roadway1, roadway2)), Some(Seq(linearLocation1, linearLocation2)), Some(Seq(projectLink1, projectLink2)))

      val (created, unchanged) = list.partition(_.status == LinkStatus.New)
      val ordered = ProjectSectionCalculator.assignMValues(created ++ unchanged)
      val road3 = ordered.find(_.linkId == idRoad3).get
      road3.startAddrMValue should be(19L)
      road3.endAddrMValue should be(30L)
      road3.calibrationPoints._1 should be(None)
      road3.calibrationPoints._2.nonEmpty should be(true)
      ordered.size should be (3)
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

      val projectLink0 = toProjectLink(rap, LinkStatus.UnChanged)(RoadAddress(idRoad0, 0, 5, 1, AdministrativeClass.Unknown, Track.Combined, Continuous, 0L, 9L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), idRoad0, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (Some(CalibrationPoint(idRoad0, 0.0, 0L)), None), Seq(Point(20.0, 10.0), Point(28, 10)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0)).copy(projectId = rap.id, roadwayId = roadwayId, linearLocationId = linearLocationId, roadwayNumber = roadwayNumber)
      val projectLink1 = toProjectLink(rap, LinkStatus.UnChanged)(RoadAddress(idRoad1, 0, 5, 1, AdministrativeClass.Unknown, Track.Combined, Continuous, 9L, 19L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), idRoad1, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), Seq(Point(28, 10), Point(28, 19)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0)).copy(projectId = rap.id, roadwayId = roadwayId+1, linearLocationId = linearLocationId+1, roadwayNumber = roadwayNumber+1)
      val projectLink2 = toProjectLink(rap, LinkStatus.Terminated)(RoadAddress(idRoad2, 0, 5, 1, AdministrativeClass.Unknown, Track.Combined, Continuous, 19L, 30L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), idRoad2, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, Some(CalibrationPoint(idRoad2, 11.0, 30L))), Seq(Point(28, 19), Point(28, 30)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0)).copy(projectId = rap.id, roadwayId = roadwayId+2, linearLocationId = linearLocationId+2, roadwayNumber = roadwayNumber+2)

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
      val geom0 = Seq(Point(0.0, 0.0), Point(0.0, 9.8))
      val geom1 = Seq(Point(0.0, -10.0), Point(0.0, 0.0))
      val geom2 = Seq(Point(0.0, -20.2), Point(0.0, -10.0))
      val projectLink0 = toProjectLink(rap, LinkStatus.Transfer)(RoadAddress(idRoad0, 0, 5, 1, AdministrativeClass.Unknown, Track.Combined, Continuous, 0L, 12L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), 12345L, 0.0, GeometryUtils.geometryLength(geom0), SideCode.TowardsDigitizing, 0, (None, None), geom0, LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
                          .copy(projectId = rap.id, roadwayId = roadwayId, linearLocationId = linearLocationId, roadwayNumber = roadwayNumber)
      val projectLink1 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad1, 0, 5, 1, AdministrativeClass.Unknown,
                          Track.Combined, Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), 12346L,
                          0.0, GeometryUtils.geometryLength(geom1), SideCode.TowardsDigitizing, 0, (None, None), geom1, LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
                          .copy(projectId = rap.id)
      val projectLink2 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad2, 0, 5, 1, AdministrativeClass.Unknown, Track.Combined, Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), 12347L, 0.0, GeometryUtils.geometryLength(geom2), SideCode.TowardsDigitizing, 0, (None, None), geom2, LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
                          .copy(projectId = rap.id)
      val geom3 = Seq(Point(0.0, 9.8), Point(0.0, 20.2))
      val projectLink3 = toProjectLink(rap, LinkStatus.Transfer)(RoadAddress(idRoad3, 0, 5, 1, AdministrativeClass.Unknown, Track.Combined, Discontinuous, 12L, 24L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), 12348L, 0.0, GeometryUtils.geometryLength(geom3), SideCode.TowardsDigitizing, 0, (None, None), geom3, LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
                          .copy(projectId = rap.id, roadwayId = roadwayId+3, linearLocationId = linearLocationId+3, roadwayNumber = roadwayNumber+3)

      val (linearCombined0, rwComb0): (LinearLocation, Roadway) = Seq(projectLink0).map(toRoadwayAndLinearLocation).head
      val (linearCombined3, rwComb3): (LinearLocation, Roadway) = Seq(projectLink3).map(toRoadwayAndLinearLocation).head
      val rwComb0WithId = rwComb0.copy(id = roadwayId, ely = 8L)
      val rwComb3WithId = rwComb3.copy(id = roadwayId+3, ely = 8L)
      val linearCombined0WithId = linearCombined0.copy(id = linearLocationId)
      val linearCombined3WithId = linearCombined3.copy(id = linearLocationId + 3)
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
        r.calibrationPoints should be(None, Some(CalibrationPoint(12348, Math.round(10.399999999999999 * 10.0) / 10.0, 44, RoadAddressCP)))
        r.startAddrMValue should be(maxAddr + projectLink3.startAddrMValue - projectLink3.endAddrMValue)
        r.endAddrMValue should be(maxAddr)
      }

      output.head.calibrationPoints should be(Some(CalibrationPoint(12347, 0.0, 0, RoadAddressCP)), None)
    }
  }

  test("Test assignMValues When there is a MinorDiscontinuity at end of address Then start of next one with 2 tracks (Left and Right) should also have calibration Points, 4 in total") {
    runWithRollback{
      // Left track = 85.308 meters
      val idRoad0 = 0L //   L>
      val idRoad1 = 1L //   L>
      val idRoad2 = 2L //   L>
      val idRoad3 = 3L //   L>
      // Right track = 83.154 meters
      val idRoad4 = 4L //   R>
      val idRoad5 = 5L //   R>
      val projectLink0 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad0, 0, 5, 1, AdministrativeClass.Unknown, Track.LeftSide, Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad0, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), Seq(Point(20.0, 10.0), Point(28, 15)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink1 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad1, 0, 5, 1, AdministrativeClass.Unknown, Track.LeftSide, MinorDiscontinuity, 0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad1, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), Seq(Point(28, 15), Point(41, 18)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink2 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad2, 0, 5, 1, AdministrativeClass.Unknown, Track.LeftSide, Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad2, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), Seq(Point(42, 19), Point(75, 29.2)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink3 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad3, 0, 5, 1, AdministrativeClass.Unknown, Track.LeftSide, Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad3, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), Seq(Point(103.0, 15.0),Point(75, 29.2)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink4 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad4, 0, 5, 1, AdministrativeClass.Unknown, Track.RightSide, MinorDiscontinuity, 0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad4, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), Seq(Point(20.0, 10.0), Point(42, 11)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink5 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad5, 0, 5, 1, AdministrativeClass.Unknown, Track.RightSide, Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad5, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), Seq(Point(42, 11), Point(103, 15)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val list = List(projectLink0, projectLink1, projectLink2, projectLink3, projectLink4, projectLink5)
      val ordered = ProjectSectionCalculator.assignMValues(list)
      // check left and right track - should have 4 calibration Points: start, end, minor discontinuity and next one after discontinuity
      (ordered.filter(_.track == Track.LeftSide).flatMap(_.calibrationPoints._1) ++ ordered.filter(_.track == Track.LeftSide).flatMap(_.calibrationPoints._2)) should have size 4
      (ordered.filter(_.track == Track.RightSide).flatMap(_.calibrationPoints._1) ++ ordered.filter(_.track == Track.RightSide).flatMap(_.calibrationPoints._2)) should have size 4
    }
  }

  test("Test assignMValues When there is a Discontinuity at end of address Then start of next one with 2 tracks (Left and Right) should also have calibration Points, 4 in total") {
    runWithRollback {
      // Left track = 85.308 meters
      val idRoad0 = 0L //   L>
      val idRoad1 = 1L //   L>
      val idRoad2 = 2L //   L>
      val idRoad3 = 3L //   L>
      // Right track = 83.154 meters
      val idRoad4 = 4L //   R>
      val idRoad5 = 5L //   R>
      val projectLink0 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad0, 0, 5, 1, AdministrativeClass.Unknown, Track.LeftSide, Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad0, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), Seq(Point(20.0, 10.0), Point(28, 15)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink1 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad1, 0, 5, 1, AdministrativeClass.Unknown, Track.LeftSide, Discontinuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad1, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), Seq(Point(28, 15), Point(41, 18)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink2 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad2, 0, 5, 1, AdministrativeClass.Unknown, Track.LeftSide, Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad2, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), Seq(Point(42, 19), Point(75, 29.2)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink3 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad3, 0, 5, 1, AdministrativeClass.Unknown, Track.LeftSide, Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad3, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), Seq(Point(103.0, 15.0),Point(75, 29.2)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink4 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad4, 0, 5, 1, AdministrativeClass.Unknown, Track.RightSide, Discontinuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad4, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), Seq(Point(20.0, 10.0), Point(42, 11)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink5 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad5, 0, 5, 1, AdministrativeClass.Unknown, Track.RightSide, Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad5, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), Seq(Point(42, 11), Point(103, 15)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val list = List(projectLink0, projectLink1, projectLink2, projectLink3, projectLink4, projectLink5)
      val ordered = ProjectSectionCalculator.assignMValues(list)
      // check left and right track - should have 4 calibration Points: start, end, discontinuity and next one after discontinuity
      (ordered.filter(_.track == Track.LeftSide).flatMap(_.calibrationPoints._1) ++ ordered.filter(_.track == Track.LeftSide).flatMap(_.calibrationPoints._2)) should have size 4
      (ordered.filter(_.track == Track.RightSide).flatMap(_.calibrationPoints._1) ++ ordered.filter(_.track == Track.RightSide).flatMap(_.calibrationPoints._2)) should have size 4
    }
  }

  test("Test assignMValues When there is a MinorDiscontinuity at end of address Then start of next one with 1 track (Combined) should also have calibration Points, 4 in total") {
    runWithRollback {
      // Combined track = 85.308 meters with MinorDiscontinuity
      val idRoad0 = 0L //   C>
      val idRoad1 = 1L //   C>
      val idRoad2 = 2L //   C>
      val idRoad3 = 3L //   C>
      val projectLink0 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad0, 0, 5, 1, AdministrativeClass.State, Track.Combined, Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad0, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), Seq(Point(20.0, 10.0), Point(28, 15)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink1 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad1, 0, 5, 1, AdministrativeClass.State, Track.Combined, MinorDiscontinuity, 0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad1, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), Seq(Point(28, 15), Point(41, 18)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink2 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad2, 0, 5, 1, AdministrativeClass.State, Track.Combined, Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad2, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), Seq(Point(42, 19), Point(75, 29.2)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink3 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad3, 0, 5, 1, AdministrativeClass.State, Track.Combined, Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad3, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), Seq(Point(103.0, 15.0),Point(75, 29.2)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val list = List(projectLink0, projectLink1, projectLink2, projectLink3)
      val ordered = ProjectSectionCalculator.assignMValues(list)
      // check combined track - should have 4 calibration Points: start, end, minor discontinuity and next one after discontinuity
      (ordered.filter(_.track == Track.Combined).flatMap(_.calibrationPoints._1) ++ ordered.filter(_.track == Track.Combined).flatMap(_.calibrationPoints._2)) should have size 4
    }
  }

  test("Test assignMValues When there is a Discontinuity at end of address Then start of next one with 1 track (Combined) should also have calibration Points, 4 in total") {
    runWithRollback {
      // Combined track = 85.308 meters with Discontinuous
      val idRoad0 = 0L //   C>
      val idRoad1 = 1L //   C>
      val idRoad2 = 2L //   C>
      val idRoad3 = 3L //   C>
      val projectLink0 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad0, 0, 5, 1, AdministrativeClass.State, Track.Combined, Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad0, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), Seq(Point(20.0, 10.0), Point(28, 15)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink1 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad1, 0, 5, 1, AdministrativeClass.State, Track.Combined, MinorDiscontinuity, 0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad1, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), Seq(Point(28, 15), Point(41, 18)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink2 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad2, 0, 5, 1, AdministrativeClass.State, Track.Combined, Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad2, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), Seq(Point(42, 19), Point(75, 29.2)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink3 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad3, 0, 5, 1, AdministrativeClass.State, Track.Combined, Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad3, 0.0, 0.0, SideCode.AgainstDigitizing, 0, (None, None), Seq(Point(103.0, 15.0),Point(75, 29.2)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val list = List(projectLink0, projectLink1, projectLink2, projectLink3)
      val ordered = ProjectSectionCalculator.assignMValues(list)
      // check combined track - should have 4 calibration Points: start, end, discontinuity and next one after discontinuity
      (ordered.filter(_.track == Track.Combined).flatMap(_.calibrationPoints._1) ++ ordered.filter(_.track == Track.Combined).flatMap(_.calibrationPoints._2)) should have size 4
    }
  }

//  test("Test assignMValues When projects links ends on a right and left track Then the section calculator for new + transfer should have the same end address") {
//    runWithRollback {
//      val idRoad6 = 6L // N   /
//      val idRoad5 = 5L // T \
//      val idRoad4 = 4L // N   /
//      val idRoad3 = 3L // T \
//      val idRoad2 = 2L // N   /
//      val idRoad1 = 1L // U  |
//      val roadwayId = Sequences.nextRoadwayId
//      val roadwayNumber = Sequences.nextRoadwayNumber
//      val linearLocationId = Sequences.nextLinearLocationId
//
//      val projectLink1 = toProjectLink(rap, LinkStatus.UnChanged)(RoadAddress(idRoad1, 0, 5, 1, RoadType.Unknown, Track.Combined, Continuous, 0L, 10L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), 12347L, 0.0, 0.0, SideCode.AgainstDigitizing, 0, (None, None),
//        Seq(Point(3.0, 0.0), Point(3.0, 2.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0)).copy(projectId = rap.id, roadwayNumber = roadwayNumber, roadwayId = roadwayId, linearLocationId = linearLocationId)
//
//      val projectLink2 = toProjectLink(rap, LinkStatus.Transfer)(RoadAddress(idRoad2, 0, 5, 1, RoadType.Unknown, Track.RightSide, Continuous, 0L, 12L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), 12345L, 0.0, 0.0, SideCode.AgainstDigitizing, 0, (None, None),
//        Seq(Point(3.0, 2.0), Point(1.0, 4.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0)).copy(projectId = rap.id, roadwayNumber = roadwayNumber+1, roadwayId = roadwayId+1, linearLocationId = linearLocationId+1)
//      val projectLink3 = toProjectLink(rap, LinkStatus.Transfer)(RoadAddress(idRoad3, 0, 5, 1, RoadType.Unknown, Track.RightSide, EndOfRoad, 12L, 24L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), 12348L, 0.0, 0.0, SideCode.AgainstDigitizing, 0, (None, None),
//        Seq(Point(1.0, 4.0), Point(0.0, 6.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0)).copy(projectId = rap.id, roadwayNumber = roadwayNumber+1, roadwayId = roadwayId+1, linearLocationId = linearLocationId+2)
//
//      val projectLink4 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad4, 0, 5, 1, RoadType.Unknown, Track.LeftSide, Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), 12346L, 0.0, 0.0, SideCode.AgainstDigitizing, 0, (None, None),
//        Seq(Point(3.0, 2.0), Point(5.0, 4.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0)).copy(projectId = rap.id)
//      val projectLink5 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad5, 0, 5, 1, RoadType.Unknown, Track.LeftSide, Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), 12347L, 0.0, 0.0, SideCode.AgainstDigitizing, 0, (None, None),
//        Seq(Point(5.0, 4.0), Point(6.0, 6.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0)).copy(projectId = rap.id)
//      val projectLink6 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad6, 0, 5, 1, RoadType.Unknown, Track.LeftSide, EndOfRoad, 0L, 0L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), 12347L, 0.0, 0.0, SideCode.AgainstDigitizing, 0, (None, None),
//        Seq(Point(6.0, 6.0), Point(7.0, 7.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0)).copy(projectId = rap.id)
//
//      val (linearCombined, rwComb): (LinearLocation, Roadway) = Seq(projectLink1).map(toRoadwayAndLinearLocation).head
//      val (linearRight1, rwRight1): (LinearLocation, Roadway) = Seq(projectLink2).map(toRoadwayAndLinearLocation).head
//      val (linearRight2, rwRight2): (LinearLocation, Roadway) = Seq(projectLink3).map(toRoadwayAndLinearLocation).head
//      val rwCombWithId = rwComb.copy(id = roadwayId, ely = 8L)
//      val rwRight1And2WithId = rwRight1.copy(id = roadwayId+1, ely = 8L, startAddrMValue = projectLink2.startAddrMValue, endAddrMValue = projectLink3.endAddrMValue)
//      val linearCombinedWithId = linearCombined.copy(id = linearLocationId)
//      val linearRight1WithId = linearRight1.copy(id = linearLocationId+1)
//      val linearRight2WithId = linearRight2.copy(id = linearLocationId+2)
//      buildTestDataForProject(Some(rap), Some(Seq(rwCombWithId, rwRight1And2WithId)), Some(Seq(linearCombinedWithId, linearRight1WithId, linearRight2WithId)), None)
//
//      val projectLinks = ProjectSectionCalculator.assignMValues(Seq(projectLink1, projectLink2, projectLink3, projectLink4, projectLink5, projectLink6))
//      projectLinks.find(_.id == idRoad6).get.endAddrMValue should be(projectLinks.find(_.id == idRoad3).get.endAddrMValue)
//    }
//  }

  test("Test assignTerminatedMValues When Terminating links on Left/Right tracks and different addrMValues Then they should be adjusted in order to have the same end address") {
    runWithRollback {
      val roadwayDAO = new RoadwayDAO
      val linearLocationDAO = new LinearLocationDAO

      val roadNumber1 = 990
      val roadPartNumber1 = 1

      val testRoadway1 = Roadway(Sequences.nextRoadwayId, Sequences.nextRoadwayNumber, roadNumber1, roadPartNumber1, AdministrativeClass.State, Track.Combined, Discontinuity.Continuous,
        0, 50, reversed = false, DateTime.parse("2000-01-01"), None, "test", Some("TEST ROAD 1"), 1, TerminationCode.NoTermination)

      val testRoadway2 = Roadway(Sequences.nextRoadwayId, Sequences.nextRoadwayNumber, roadNumber1, roadPartNumber1, AdministrativeClass.State, Track.RightSide, Discontinuity.EndOfRoad,
        50, 105, reversed = false, DateTime.parse("2000-01-01"), None, "test", Some("TEST ROAD 2"), 1, TerminationCode.NoTermination)

      val testRoadway3 = Roadway(Sequences.nextRoadwayId, Sequences.nextRoadwayNumber, roadNumber1, roadPartNumber1, AdministrativeClass.State, Track.LeftSide, Discontinuity.EndOfRoad,
        50, 107, reversed = false, DateTime.parse("2000-01-01"), None, "test", Some("TEST ROAD 3"), 1, TerminationCode.NoTermination)

      val linearLocation1 = LinearLocation(Sequences.nextLinearLocationId, 1, 12345, 0.0, 50.0, SideCode.TowardsDigitizing, 10000000000L,
        (CalibrationPointReference(Some(0L)), CalibrationPointReference.None),
        Seq(Point(0.0, 0.0), Point(50.0, 5.0)), LinkGeomSource.NormalLinkInterface, testRoadway1.roadwayNumber)

      val linearLocation2 = LinearLocation(Sequences.nextLinearLocationId, 1, 12346, 0.0, 55.0, SideCode.TowardsDigitizing, 10000000000L,
        (CalibrationPointReference.None, CalibrationPointReference(Some(105))),
        Seq(Point(0.0, 0.0), Point(100.0, 10.0)), LinkGeomSource.NormalLinkInterface, testRoadway2.roadwayNumber)

      val linearLocation3 = LinearLocation(Sequences.nextLinearLocationId, 1, 12347, 0.0, 57.0, SideCode.TowardsDigitizing, 10000000000L,
        (CalibrationPointReference.None, CalibrationPointReference(Some(107))),
        Seq(Point(0.0, 0.0), Point(100.0, 0.0)), LinkGeomSource.NormalLinkInterface, testRoadway3.roadwayNumber)

      roadwayDAO.create(List(testRoadway1, testRoadway2, testRoadway3))

      linearLocationDAO.create(List(linearLocation1, linearLocation2, linearLocation3))

      val projectLink1 = toProjectLink(rap, LinkStatus.UnChanged)(RoadAddress(testRoadway1.id, linearLocation1.id, roadNumber1, roadPartNumber1, AdministrativeClass.Unknown, Track.Combined, Continuous, 0L, 50L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), 12345L, 0.0, 50.0, SideCode.TowardsDigitizing, 0, (None, None),
                          Seq(Point(0.0, 5.0), Point(50.0, 5.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, testRoadway1.roadwayNumber)).copy(roadAddressStartAddrM = Some(0L), roadAddressEndAddrM = Some(50L), roadAddressTrack = Some(Track.Combined))

      val projectLink2 = toProjectLink(rap, LinkStatus.Terminated)(RoadAddress(testRoadway2.id, linearLocation2.id, roadNumber1, roadPartNumber1, AdministrativeClass.Unknown, Track.RightSide, EndOfRoad, 50L, 105L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), 12346L, 0.0, 55.0, SideCode.TowardsDigitizing, 0, (None, None),
                          Seq(Point(50.0, 5.0), Point(100.0, 10.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, testRoadway2.roadwayNumber)).copy(roadAddressStartAddrM = Some(50L), roadAddressEndAddrM = Some(105L), roadAddressTrack = Some(Track.RightSide))
      val projectLink3 = toProjectLink(rap, LinkStatus.Terminated)(RoadAddress(testRoadway3.id, linearLocation3.id, roadNumber1, roadPartNumber1, AdministrativeClass.Unknown, Track.LeftSide, EndOfRoad, 50L, 107L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), 12347L, 0.0, 57.0, SideCode.TowardsDigitizing, 0, (None, None),
                          Seq(Point(50.0, 5.0), Point(100.0, 0.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, testRoadway3.roadwayNumber)).copy(roadAddressStartAddrM = Some(50L), roadAddressEndAddrM = Some(107L), roadAddressTrack = Some(Track.LeftSide))

      val (terminated, others) = (Seq(projectLink2, projectLink3), Seq(projectLink1))
      val recalculated = ProjectSectionCalculator.assignMValues(others, Seq.empty[UserDefinedCalibrationPoint]).sortBy(_.endAddrMValue)

      val terminatedProjectLinks = ProjectSectionCalculator.assignTerminatedMValues(terminated, recalculated)

      terminatedProjectLinks.filter(_.track == Track.LeftSide).head.startAddrMValue should be (terminatedProjectLinks.filter(_.track == Track.RightSide).head.startAddrMValue)
      terminatedProjectLinks.filter(_.track == Track.LeftSide).last.endAddrMValue should be (terminatedProjectLinks.filter(_.track == Track.RightSide).last.endAddrMValue)
      terminatedProjectLinks.filter(_.track == Track.LeftSide).head.endAddrMValue should be ((projectLink2.endAddrMValue + projectLink3.endAddrMValue) / 2)
    }
  }

//  test("Test assignTerminatedMValues When Terminating links on Left/Right tracks and different addrMValues Then they should be adjusted in order to have the same end address and same amount of roadway numbers") {
//    runWithRollback {
//      val roadNumber = 990
//      val roadPartNumber = 1
//
//      // Track 0
//      val testRoadway1 = Roadway(Sequences.nextRoadwayId, Sequences.nextRoadwayNumber, roadNumber, roadPartNumber, RoadType.PublicRoad, Track.Combined, Discontinuity.Continuous,
//        0L, 50L, reversed = false, DateTime.parse("2000-01-01"), None, "tester", Some("TEST ROADWAY 1, TRACK 0"), 1, TerminationCode.NoTermination)
//
//      val linearLocation1 = LinearLocation(Sequences.nextLinearLocationId, 1, 12345, 0.0, 50.0, SideCode.TowardsDigitizing, 10000000000L,
//        (CalibrationPointReference(Some(0L)), CalibrationPointReference(Some(50L))),
//        Seq(Point(0.0, 5.0), Point(50.0, 5.0)), LinkGeomSource.NormalLinkInterface, testRoadway1.roadwayNumber)
//
//      val projectLink1 = toProjectLink(rap, LinkStatus.UnChanged)(RoadAddress(
//        testRoadway1.id, linearLocation1.id, roadNumber, roadPartNumber, testRoadway1.roadType, testRoadway1.track, Discontinuity.Discontinuous,
//        testRoadway1.startAddrMValue, testRoadway1.endAddrMValue, Some(testRoadway1.startDate), None, Some(testRoadway1.createdBy),
//        linearLocation1.linkId, linearLocation1.startMValue, linearLocation1.endMValue, linearLocation1.sideCode, 0, (None, None),
//        linearLocation1.geometry, LinkGeomSource.NormalLinkInterface, 8, NoTermination, testRoadway1.roadwayNumber))
//        .copy(roadAddressStartAddrM = Some(testRoadway1.startAddrMValue), roadAddressEndAddrM = Some(testRoadway1.endAddrMValue), roadAddressTrack = Some(testRoadway1.track))
//
//      // Track 1
//      val testRoadway2 = Roadway(Sequences.nextRoadwayId, Sequences.nextRoadwayNumber, roadNumber, roadPartNumber, RoadType.PublicRoad, Track.RightSide, Discontinuity.Continuous,
//        50L, 150L, reversed = false, DateTime.parse("2000-01-01"), None, "tester", Some("TEST ROADWAY 2, TRACK 1"), 1, TerminationCode.NoTermination)
//
//      val linearLocation2 = LinearLocation(Sequences.nextLinearLocationId, 1, 12347, 0.0, 100.0, SideCode.TowardsDigitizing, 10000000000L,
//        (CalibrationPointReference(Some(50L)), CalibrationPointReference.None),
//        Seq(Point(50.0, 5.0), Point(150.0, 0.0)), LinkGeomSource.NormalLinkInterface, testRoadway2.roadwayNumber)
//
//      val projectLink2 = toProjectLink(rap, LinkStatus.Terminated)(RoadAddress(
//        testRoadway2.id, linearLocation2.id, roadNumber, roadPartNumber, testRoadway2.roadType, testRoadway2.track, testRoadway2.discontinuity,
//        testRoadway2.startAddrMValue, testRoadway2.endAddrMValue, Some(testRoadway2.startDate), None, Some(testRoadway2.createdBy),
//        linearLocation2.linkId, linearLocation2.startMValue, linearLocation2.endMValue, linearLocation2.sideCode, 0, (None, None),
//        linearLocation2.geometry, LinkGeomSource.NormalLinkInterface, 8, NoTermination, testRoadway2.roadwayNumber))
//        .copy(roadAddressStartAddrM = Some(testRoadway2.startAddrMValue), roadAddressEndAddrM = Some(testRoadway2.endAddrMValue), roadAddressTrack = Some(testRoadway2.track))
//
//      val testRoadway3 = Roadway(Sequences.nextRoadwayId, Sequences.nextRoadwayNumber, roadNumber, roadPartNumber, RoadType.PublicRoad, Track.RightSide, Discontinuity.EndOfRoad,
//        150L, 200L, reversed = false, DateTime.parse("2000-01-01"), None, "tester", Some("TEST ROADWAY 3, TRACK 1"), 1, TerminationCode.NoTermination)
//
//      val linearLocation3_1 = LinearLocation(Sequences.nextLinearLocationId, 1, 12349, 0.0, 10.0, SideCode.TowardsDigitizing, 10000000000L,
//        (CalibrationPointReference.None, CalibrationPointReference.None),
//        Seq(Point(150.0, 0.0), Point(160.0, 0.0)), LinkGeomSource.NormalLinkInterface, testRoadway3.roadwayNumber)
//
//      val projectLink3_1 = toProjectLink(rap, LinkStatus.Terminated)(RoadAddress(
//        testRoadway3.id, linearLocation3_1.id, roadNumber, roadPartNumber, testRoadway3.roadType, testRoadway3.track, Discontinuity.Continuous,
//        testRoadway3.startAddrMValue, 160, Some(testRoadway3.startDate), None, Some(testRoadway3.createdBy),
//        linearLocation3_1.linkId, linearLocation3_1.startMValue, linearLocation3_1.endMValue, linearLocation3_1.sideCode, 0, (None, None),
//        linearLocation3_1.geometry, LinkGeomSource.NormalLinkInterface, 8, NoTermination, testRoadway3.roadwayNumber))
//        .copy(roadAddressStartAddrM = Some(testRoadway3.startAddrMValue), roadAddressEndAddrM = Some(160), roadAddressTrack = Some(testRoadway3.track))
//
//      val linearLocation3_2 = LinearLocation(Sequences.nextLinearLocationId, 2, 12351, 0.0, 40.0, SideCode.TowardsDigitizing, 10000000000L,
//        (CalibrationPointReference.None, CalibrationPointReference.None),
//        Seq(Point(160.0, 0.0), Point(200.0, 0.0)), LinkGeomSource.NormalLinkInterface, testRoadway3.roadwayNumber)
//
//      val projectLink3_2 = toProjectLink(rap, LinkStatus.Transfer)(RoadAddress(
//        testRoadway3.id, linearLocation3_2.id, roadNumber, roadPartNumber, testRoadway3.roadType, testRoadway3.track, testRoadway3.discontinuity,
//        160, testRoadway3.endAddrMValue, Some(testRoadway3.startDate), None, Some(testRoadway3.createdBy),
//        linearLocation3_2.linkId, linearLocation3_2.startMValue, linearLocation3_2.endMValue, linearLocation3_2.sideCode, 0, (None, None),
//        linearLocation3_2.geometry, LinkGeomSource.NormalLinkInterface, 8, NoTermination, testRoadway3.roadwayNumber))
//        .copy(roadAddressStartAddrM = Some(160), roadAddressEndAddrM = Some(testRoadway3.endAddrMValue), roadAddressTrack = Some(testRoadway3.track))
//
//      // Track 2
//      val testRoadway4 = Roadway(Sequences.nextRoadwayId, Sequences.nextRoadwayNumber, roadNumber, roadPartNumber, RoadType.PublicRoad, Track.LeftSide, Discontinuity.Continuous,
//        50L, 150L, reversed = false, DateTime.parse("2000-01-01"), None, "tester", Some("TEST ROADWAY 4, TRACK 2"), 1, TerminationCode.NoTermination)
//
//      val linearLocation4 = LinearLocation(Sequences.nextLinearLocationId, 1, 12346, 0.0, 100.0, SideCode.TowardsDigitizing, 10000000000L,
//        (CalibrationPointReference(Some(50L)), CalibrationPointReference.None),
//        Seq(Point(50.0, 5.0), Point(150.0, 10.0)), LinkGeomSource.NormalLinkInterface, testRoadway4.roadwayNumber)
//
//      val projectLink4 = toProjectLink(rap, LinkStatus.Terminated)(RoadAddress(
//        testRoadway4.id, linearLocation4.id, roadNumber, roadPartNumber, testRoadway4.roadType, testRoadway4.track, testRoadway4.discontinuity,
//        testRoadway4.startAddrMValue, testRoadway4.endAddrMValue, Some(testRoadway4.startDate), None, Some(testRoadway4.createdBy),
//        linearLocation4.linkId, linearLocation4.startMValue, linearLocation4.endMValue, linearLocation4.sideCode, 0, (None, None),
//        linearLocation4.geometry, LinkGeomSource.NormalLinkInterface, 8, NoTermination, testRoadway4.roadwayNumber))
//        .copy(roadAddressStartAddrM = Some(testRoadway4.startAddrMValue), roadAddressEndAddrM = Some(testRoadway4.endAddrMValue), roadAddressTrack = Some(testRoadway4.track))
//
//      val testRoadway5 = Roadway(Sequences.nextRoadwayId, Sequences.nextRoadwayNumber, roadNumber, roadPartNumber, RoadType.PublicRoad, Track.LeftSide, Discontinuity.EndOfRoad,
//        150L, 200L, reversed = false, DateTime.parse("2000-01-01"), None, "tester", Some("TEST ROADWAY 5, TRACK 2"), 1, TerminationCode.NoTermination)
//
//      val linearLocation5 = LinearLocation(Sequences.nextLinearLocationId, 1, 12348, 0.0, 50.0, SideCode.TowardsDigitizing, 10000000000L,
//        (CalibrationPointReference.None, CalibrationPointReference.None),
//        Seq(Point(150.0, 10.0), Point(200.0, 10.0)), LinkGeomSource.NormalLinkInterface, testRoadway5.roadwayNumber)
//
//      val projectLink5 = toProjectLink(rap, LinkStatus.Transfer)(RoadAddress(
//        testRoadway5.id, linearLocation5.id, roadNumber, roadPartNumber, testRoadway5.roadType, testRoadway5.track, testRoadway5.discontinuity,
//        testRoadway5.startAddrMValue, testRoadway5.endAddrMValue, Some(testRoadway5.startDate), None, Some(testRoadway5.createdBy),
//        linearLocation5.linkId, linearLocation5.startMValue, linearLocation5.endMValue, linearLocation5.sideCode, 0, (None, None),
//        linearLocation5.geometry, LinkGeomSource.NormalLinkInterface, 8, NoTermination, testRoadway5.roadwayNumber))
//        .copy(roadAddressStartAddrM = Some(testRoadway5.startAddrMValue), roadAddressEndAddrM = Some(testRoadway5.endAddrMValue), roadAddressTrack = Some(testRoadway5.track))
//
//      roadwayDAO.create(List(testRoadway1, testRoadway2, testRoadway3, testRoadway4, testRoadway5))
//      linearLocationDAO.create(List(linearLocation1, linearLocation2, linearLocation3_1, linearLocation3_2, linearLocation4, linearLocation5))
//
//      val terminatedProjectLinks = ProjectSectionCalculator.assignTerminatedMValues(
//        Seq(projectLink2, projectLink3_1, projectLink4),
//        Seq(projectLink1, projectLink3_2, projectLink5))
//
//      val (rightPrjLinks, leftPrjLinks) = terminatedProjectLinks.filterNot(_.track == Track.Combined).partition(_.track == Track.RightSide)
//
//      rightPrjLinks.map(_.roadwayNumber).distinct.size should be(leftPrjLinks.map(_.roadwayNumber).distinct.size)
//
//      rightPrjLinks.zipWithIndex.foreach { case (r, i) =>
//          r.startAddrMValue should be(leftPrjLinks(i).startAddrMValue)
//          r.endAddrMValue should be(leftPrjLinks(i).endAddrMValue)
//      }
//
//        val strategy = TrackCalculatorContext.getStrategy(leftPrjLinks, rightPrjLinks)
//        val estimatedEnd = strategy.getFixedAddress(projectLink4, projectLink3_1)._2
//        leftPrjLinks.last.endAddrMValue should be (estimatedEnd)
//        rightPrjLinks.last.endAddrMValue should be (estimatedEnd)
//    }
//  }

  test("Test assignTerminatedMValues When Terminating links on Left/Right tracks and different addrMValues Then both Unchanged links should be adjusted in order to have the same end address" +
    " and Terminated should have same adjusted start addr") {
    runWithRollback {
      val roadwayDAO = new RoadwayDAO
      val linearLocationDAO = new LinearLocationDAO

      val roadNumber1 = 990
      val roadPartNumber1 = 1

      val roadwayNumber1 = 1000000000L
      val roadwayNumber2 = 2000000000L
      val roadwayNumber3 = 3000000000L
      val roadwayNumber4 = 4000000000L

      val roadwayId1 = Sequences.nextRoadwayId
      val roadwayId2 = roadwayId1 + 1
      val roadwayId3 = roadwayId2 + 1
      val roadwayId4 = roadwayId3 + 1
      val linearLocationId1 = Sequences.nextLinearLocationId
      val linearLocationId2 = linearLocationId1 + 1
      val linearLocationId3 = linearLocationId2 + 1
      val linearLocationId4 = linearLocationId3 + 1

      val testRoadway1 = Roadway(roadwayId1, roadwayNumber1, roadNumber1, roadPartNumber1, AdministrativeClass.State, Track.RightSide, Discontinuity.Continuous, 0, 50, reversed = false, DateTime.parse("2000-01-01"), None, "test", Some("TEST ROAD 1"), 1, TerminationCode.NoTermination)

      val testRoadway2 = Roadway(roadwayId2, roadwayNumber2, roadNumber1, roadPartNumber1, AdministrativeClass.State, Track.LeftSide, Discontinuity.Continuous, 0, 52, reversed = false, DateTime.parse("2000-01-01"), None, "test", Some("TEST ROAD 2"), 1, TerminationCode.NoTermination)

      val testRoadway3 = Roadway(roadwayId3, roadwayNumber3, roadNumber1, roadPartNumber1, AdministrativeClass.State, Track.RightSide, Discontinuity.EndOfRoad, 50, 105, reversed = false, DateTime.parse("2000-01-01"), None, "test", Some("TEST ROAD 3"), 1, TerminationCode.NoTermination)

      val testRoadway4 = Roadway(roadwayId4, roadwayNumber4, roadNumber1, roadPartNumber1, AdministrativeClass.State, Track.LeftSide, Discontinuity.EndOfRoad, 52, 109, reversed = false, DateTime.parse("2000-01-01"), None, "test", Some("TEST ROAD 4"), 1, TerminationCode.NoTermination)


      val linearLocation1 = LinearLocation(linearLocationId1, 1, 12345, 0.0, 50.0, SideCode.TowardsDigitizing, 10000000000L,
        (CalibrationPointReference(Some(0L)), CalibrationPointReference.None),
        Seq(Point(0.0, 0.0), Point(50.0, 10.0)), LinkGeomSource.NormalLinkInterface, roadwayNumber1)

      val linearLocation2 = LinearLocation(linearLocationId2, 1, 12346, 0.0, 52.0, SideCode.TowardsDigitizing, 10000000000L,
        (CalibrationPointReference(Some(0L)), CalibrationPointReference.None),
        Seq(Point(0.0, 0.0), Point(52.0, 0.0)), LinkGeomSource.NormalLinkInterface, roadwayNumber2)

      val linearLocation3 = LinearLocation(linearLocationId3, 1, 12347, 0.0, 55.0, SideCode.TowardsDigitizing, 10000000000L,
        (CalibrationPointReference.None, CalibrationPointReference(Some(105))),
        Seq(Point(0.0, 0.0), Point(55.0, 10.0)), LinkGeomSource.NormalLinkInterface, roadwayNumber3)

      val linearLocation4 = LinearLocation(linearLocationId4, 1, 12348, 0.0, 57.0, SideCode.TowardsDigitizing, 10000000000L,
        (CalibrationPointReference.None, CalibrationPointReference(Some(109))),
        Seq(Point(0.0, 0.0), Point(57.0, 0.0)), LinkGeomSource.NormalLinkInterface, roadwayNumber4)

      roadwayDAO.create(List(testRoadway1, testRoadway2, testRoadway3, testRoadway4))

      linearLocationDAO.create(List(linearLocation1, linearLocation2, linearLocation3, linearLocation4))

      val projectLink1 = toProjectLink(rap, LinkStatus.UnChanged)(RoadAddress(roadwayId1, linearLocationId1, roadNumber1, roadPartNumber1, AdministrativeClass.Unknown, Track.RightSide,
                          Continuous, 0L, 50L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), 12345L, 0.0, 50.0, SideCode.TowardsDigitizing, 0, (None, None),
                          Seq(Point(0.0, 10.0), Point(50.0, 10.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0)).copy(roadAddressStartAddrM = Some(0L), roadAddressEndAddrM =
                          Some(50L), roadAddressTrack = Some(Track.RightSide))

      val projectLink2 = toProjectLink(rap, LinkStatus.UnChanged)(RoadAddress(roadwayId2, linearLocationId2, roadNumber1, roadPartNumber1, AdministrativeClass.Unknown, Track.LeftSide,
                          Continuous, 0L, 52L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), 12346L, 0.0, 52.0, SideCode.TowardsDigitizing, 0, (None, None),
                          Seq(Point(0.0, 0.0), Point(52.0, 0.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0)).copy(roadAddressStartAddrM = Some(0L), roadAddressEndAddrM =
                          Some(52L), roadAddressTrack = Some(Track.LeftSide))

      val projectLink3 = toProjectLink(rap, LinkStatus.Terminated)(RoadAddress(roadwayId3, linearLocationId3, roadNumber1, roadPartNumber1, AdministrativeClass.Unknown, Track.RightSide,
                          EndOfRoad, 50L, 105L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), 12347L, 0.0, 55.0, SideCode.TowardsDigitizing, 0, (None, None),
                          Seq(Point(50.0, 10.0), Point(100.0, 10.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0)).copy(roadAddressStartAddrM = Some(50L), roadAddressEndAddrM =
                          Some(105L), roadAddressTrack = Some(Track.RightSide))

      val projectLink4 = toProjectLink(rap, LinkStatus.Terminated)(RoadAddress(roadwayId4, linearLocationId4, roadNumber1, roadPartNumber1, AdministrativeClass.Unknown, Track.LeftSide,
                          EndOfRoad, 52L, 109L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), 12348L, 0.0, 57.0, SideCode.TowardsDigitizing, 0, (None, None),
                          Seq(Point(52.0, 0.0), Point(102.0, 0.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0)).copy(roadAddressStartAddrM = Some(50L), roadAddressEndAddrM =
                          Some(109L), roadAddressTrack = Some(Track.LeftSide))

      val (terminated, others) = (Seq(projectLink3, projectLink4), Seq(projectLink1, projectLink2))
      val recalculated = ProjectSectionCalculator.assignMValues(others).sortBy(_.endAddrMValue)

      val terminatedProjectLinks = ProjectSectionCalculator.assignTerminatedMValues(terminated, recalculated)

      terminatedProjectLinks.filter(_.track == Track.LeftSide).head.startAddrMValue should be((projectLink1.endAddrMValue + projectLink2.endAddrMValue) / 2)
      terminatedProjectLinks.filter(_.track == Track.RightSide).head.startAddrMValue should be((projectLink1.endAddrMValue + projectLink2.endAddrMValue) / 2)
      terminatedProjectLinks.filter(_.track == Track.LeftSide).head.startAddrMValue should be(terminatedProjectLinks.filter(_.track == Track.RightSide).head.startAddrMValue)

      // Terminated end addresses are not average matched when not connected. Values remain.
      terminatedProjectLinks.filter(_.track == Track.LeftSide).last.endAddrMValue should be (terminatedProjectLinks.filter(_.track == Track.RightSide).last.endAddrMValue)
      terminatedProjectLinks.filter(_.track == Track.LeftSide).head.startAddrMValue should be (terminatedProjectLinks.filter(_.track == Track.RightSide).head.startAddrMValue)
    }
  }

  /*
           #2
    #0 /\  __
      #1 \/ /\
            ||
            ||
            || #3 /  #11
         #4 ||  /|  #9/#10
            || //  #7
            ||//  #5/#8
            |_/  #6

 */
  test("Test assignMValues When adding New link sections to the existing ones Then Direction for those should always be the same as the existing section (which are for e.g. Transfer, Unchanged, Numbering) making the addressMValues be sequential") {
    runWithRollback {
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
      val projectLink0 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad0, 0, 5, 1, AdministrativeClass.Unknown, Track.Combined, Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), idRoad0, 0.0, 14.1, SideCode.Unknown, 0, (None, None), Seq(Point(0.0, 90.0), Point(10.0, 100.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0)).copy(projectId = rap.id)
      val projectLink1 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad1, 0, 5, 1, AdministrativeClass.Unknown, Track.Combined, Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), idRoad1, 0.0, 22.3, SideCode.Unknown, 0, (None, None), Seq(Point(10.0, 100.0), Point(20.0, 80.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0)).copy(projectId = rap.id)

      //TRANSFER
      val projectLink2 = toProjectLink(rap, LinkStatus.Transfer)(RoadAddress(idRoad2, 0, 5, 1, AdministrativeClass.Unknown, Track.Combined, Continuous, 0L, 14L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), idRoad2, 0.0, 14.1, SideCode.TowardsDigitizing, 0, (None, None), Seq(Point(20.0, 80.0), Point(30.0, 90.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0)).copy(projectId = rap.id, roadwayId = roadwayId, linearLocationId = linearLocationId, roadwayNumber = roadwayNumber)
      val projectLink3 = toProjectLink(rap, LinkStatus.Transfer)(RoadAddress(idRoad3, 0, 5, 1, AdministrativeClass.Unknown, Track.LeftSide, Continuous, 14L, 87L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), idRoad3, 0.0, 72.8, SideCode.AgainstDigitizing, 0, (None, None), Seq(Point(30.0, 90.0), Point(50.0, 20.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0)).copy(projectId = rap.id, roadwayId = roadwayId + 7, linearLocationId = linearLocationId + 7, roadwayNumber = roadwayNumber + 7)
      val projectLink4 = toProjectLink(rap, LinkStatus.Transfer)(RoadAddress(idRoad4, 0, 5, 1, AdministrativeClass.Unknown, Track.RightSide, Continuous, 14L, 95L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), idRoad4, 0.0, 80.6, SideCode.AgainstDigitizing, 0, (None, None), Seq(Point(30.0, 90.0), Point(40.0, 10.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0)).copy(projectId = rap.id, roadwayId = roadwayId + 8, linearLocationId = linearLocationId + 9, roadwayNumber = roadwayNumber + 8)
      val projectLink5 = toProjectLink(rap, LinkStatus.Transfer)(RoadAddress(idRoad5, 0, 5, 1, AdministrativeClass.Unknown, Track.LeftSide, Continuous, 87L, 101L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), idRoad5, 0.0, 14.1, SideCode.TowardsDigitizing, 0, (None, None), Seq(Point(50.0, 20.0), Point(60.0, 30.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0)).copy(projectId = rap.id, roadwayId = roadwayId + 7, linearLocationId = linearLocationId + 8, roadwayNumber = roadwayNumber + 7)
      val projectLink6 = toProjectLink(rap, LinkStatus.Transfer)(RoadAddress(idRoad6, 0, 5, 1, AdministrativeClass.Unknown, Track.RightSide, Continuous, 95L, 118L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), idRoad6, 0.0, 22.3, SideCode.TowardsDigitizing, 0, (None, None), Seq(Point(40.0, 10.0), Point(60.0, 20.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0)).copy(projectId = rap.id, roadwayId = roadwayId + 8, linearLocationId = linearLocationId + 10, roadwayNumber = roadwayNumber + 8)

      //NEW
      val projectLink7 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad7, 0, 5, 1, AdministrativeClass.Unknown, Track.LeftSide, Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), idRoad7, 0.0, 0.0, SideCode.Unknown, 0, (None, None), Seq(Point(60.0, 30.0), Point(70.0, 40.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0)).copy(projectId = rap.id)
      val projectLink8 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad8, 0, 5, 1, AdministrativeClass.Unknown, Track.RightSide, Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), idRoad8, 0.0, 0.0, SideCode.Unknown, 0, (None, None), Seq(Point(60.0, 20.0), Point(70.0, 30.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0)).copy(projectId = rap.id)
      val projectLink9 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad9, 0, 5, 1, AdministrativeClass.Unknown, Track.LeftSide, Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), idRoad9, 0.0, 0.0, SideCode.Unknown, 0, (None, None), Seq(Point(70.0, 40.0), Point(80.0, 50.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0)).copy(projectId = rap.id)
      val projectLink10 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad10, 0, 5, 1, AdministrativeClass.Unknown, Track.RightSide, Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), idRoad10, 0.0, 0.0, SideCode.Unknown, 0, (None, None), Seq(Point(70.0, 30.0), Point(80.0, 50.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0)).copy(projectId = rap.id)
      val projectLink11 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad11, 0, 5, 1, AdministrativeClass.Unknown, Track.Combined, Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), idRoad11, 0.0, 0.0, SideCode.Unknown, 0, (None, None), Seq(Point(80.0, 50.0), Point(90.0, 60.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0)).copy(projectId = rap.id)


      val (linearCombined, rwComb): (LinearLocation, Roadway) = Seq(projectLink2).map(toRoadwayAndLinearLocation).head
      val (linearLeft1, rwLeft1): (LinearLocation, Roadway) = Seq(projectLink3).map(toRoadwayAndLinearLocation).head
      val (linearLeft2, rwLeft2): (LinearLocation, Roadway) = Seq(projectLink5).map(toRoadwayAndLinearLocation).head
      val (linearRight1, rwRight1): (LinearLocation, Roadway) = Seq(projectLink4).map(toRoadwayAndLinearLocation).head
      val (linearRight2, rwRight2): (LinearLocation, Roadway) = Seq(projectLink6).map(toRoadwayAndLinearLocation).head
      val rwCombWithId = rwComb.copy(id = roadwayId, ely = 8L)
      val rwLeft1And2WithId = rwLeft1.copy(id = roadwayId + 7, startAddrMValue = projectLink3.startAddrMValue, endAddrMValue = projectLink5.endAddrMValue, ely = 8L)
      val rwRight1And2WithId = rwRight1.copy(id = roadwayId + 8, startAddrMValue = projectLink4.startAddrMValue, endAddrMValue = projectLink6.endAddrMValue, ely = 8L)
      val linearCombinedWithId = linearCombined.copy(id = linearLocationId)
      val linearLeft1WithId = linearLeft1.copy(id = linearLocationId + 7)
      val linearLeft2WithId = linearLeft2.copy(id = linearLocationId + 8)
      val linearRight1WithId = linearRight1.copy(id = linearLocationId + 9)
      val linearRight2WithId = linearRight2.copy(id = linearLocationId + 10)
      val list1 = List(projectLink0, projectLink1, projectLink2, projectLink3, projectLink4, projectLink5, projectLink6)

      buildTestDataForProject(Some(rap), Some(Seq(rwCombWithId, rwLeft1And2WithId, rwRight1And2WithId)), Some(Seq(linearCombinedWithId, linearLeft1WithId, linearLeft2WithId, linearRight1WithId, linearRight2WithId)),
        Some(list1 ++ Seq(projectLink7, projectLink8, projectLink9, projectLink10, projectLink11)))

      val ordered = ProjectSectionCalculator.assignMValues(list1)
      ordered.minBy(_.startAddrMValue).id should be(idRoad0) // TODO "6 was not equal to 0"

      val list2 = ordered.toList ::: List(projectLink7, projectLink8, projectLink9, projectLink10, projectLink11)
      val ordered2 = ProjectSectionCalculator.assignMValues(list2)
      ordered2.maxBy(_.endAddrMValue).id should be(idRoad11)
    }
  }

  /*
               #2
      #0 /\    __
        #1 \  / /\
                ||
    Minor --^   ||
    disc.       || #3 /  #11
             #4 ||  /|  #9/#10
                || //  #7
                ||//  #5/#8
                |_/  #6

   */
  test("Test assignMValues When adding New link sections with minor discontinuity to the existing ones Then Direction for those should always be the same as the existing section (which are for e.g. Transfer, Unchanged, Numbering) making the addressMValues be sequential") {
    runWithRollback {
      //(x,y)   -> (x,y)
      val idRoad0 = 0L //   N>  C(0, 90)   ->  (10, 100)
      val idRoad1 = 1L //   N>  C(10, 100) ->  (18, 78)

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
      val projectLink0 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad0, 0, 5, 1, AdministrativeClass.Unknown, Track.Combined, Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), idRoad0, 0.0, 14.1, SideCode.Unknown, 0, (None, None), Seq(Point(0.0, 90.0), Point(10.0, 100.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0)).copy(projectId = rap.id)
      val projectLink1 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad1, 0, 5, 1, AdministrativeClass.Unknown, Track.Combined, MinorDiscontinuity, 0L, 0L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), idRoad1, 0.0, 22.3, SideCode.Unknown, 0, (None, None), Seq(Point(10.0, 100.0), Point(18.0, 78.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0)).copy(projectId = rap.id)

      //TRANSFER
      val projectLink2 = toProjectLink(rap, LinkStatus.Transfer)(RoadAddress(idRoad2, 0, 5, 1, AdministrativeClass.Unknown, Track.Combined, Continuous, 0L, 14L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), idRoad2, 0.0, 14.1, SideCode.TowardsDigitizing, 0, (None, None), Seq(Point(20.0, 80.0), Point(30.0, 90.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0)).copy(projectId = rap.id, roadwayId = roadwayId, linearLocationId = linearLocationId, roadwayNumber = roadwayNumber)
      val projectLink3 = toProjectLink(rap, LinkStatus.Transfer)(RoadAddress(idRoad3, 0, 5, 1, AdministrativeClass.Unknown, Track.LeftSide, Continuous, 14L, 87L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), idRoad3, 0.0, 72.8, SideCode.AgainstDigitizing, 0, (None, None), Seq(Point(30.0, 90.0), Point(50.0, 20.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0)).copy(projectId = rap.id, roadwayId = roadwayId + 7, linearLocationId = linearLocationId + 7, roadwayNumber = roadwayNumber + 7)
      val projectLink4 = toProjectLink(rap, LinkStatus.Transfer)(RoadAddress(idRoad4, 0, 5, 1, AdministrativeClass.Unknown, Track.RightSide, Continuous, 14L, 95L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), idRoad4, 0.0, 80.6, SideCode.AgainstDigitizing, 0, (None, None), Seq(Point(30.0, 90.0), Point(40.0, 10.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0)).copy(projectId = rap.id, roadwayId = roadwayId + 8, linearLocationId = linearLocationId + 9, roadwayNumber = roadwayNumber + 8)
      val projectLink5 = toProjectLink(rap, LinkStatus.Transfer)(RoadAddress(idRoad5, 0, 5, 1, AdministrativeClass.Unknown, Track.LeftSide, Continuous, 87L, 101L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), idRoad5, 0.0, 14.1, SideCode.TowardsDigitizing, 0, (None, None), Seq(Point(50.0, 20.0), Point(60.0, 30.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0)).copy(projectId = rap.id, roadwayId = roadwayId + 7, linearLocationId = linearLocationId + 8, roadwayNumber = roadwayNumber + 7)
      val projectLink6 = toProjectLink(rap, LinkStatus.Transfer)(RoadAddress(idRoad6, 0, 5, 1, AdministrativeClass.Unknown, Track.RightSide, Continuous, 95L, 118L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), idRoad6, 0.0, 22.3, SideCode.TowardsDigitizing, 0, (None, None), Seq(Point(40.0, 10.0), Point(60.0, 20.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0)).copy(projectId = rap.id, roadwayId = roadwayId + 8, linearLocationId = linearLocationId + 10, roadwayNumber = roadwayNumber + 8)

      //NEW
      val projectLink7 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad7, 0, 5, 1, AdministrativeClass.Unknown, Track.LeftSide, Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), idRoad7, 0.0, 0.0, SideCode.Unknown, 0, (None, None), Seq(Point(60.0, 30.0), Point(70.0, 40.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0)).copy(projectId = rap.id)
      val projectLink8 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad8, 0, 5, 1, AdministrativeClass.Unknown, Track.RightSide, Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), idRoad8, 0.0, 0.0, SideCode.Unknown, 0, (None, None), Seq(Point(60.0, 20.0), Point(70.0, 30.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0)).copy(projectId = rap.id)
      val projectLink9 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad9, 0, 5, 1, AdministrativeClass.Unknown, Track.LeftSide, Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), idRoad9, 0.0, 0.0, SideCode.Unknown, 0, (None, None), Seq(Point(70.0, 40.0), Point(80.0, 50.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0)).copy(projectId = rap.id)
      val projectLink10 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad10, 0, 5, 1, AdministrativeClass.Unknown, Track.RightSide, Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), idRoad10, 0.0, 0.0, SideCode.Unknown, 0, (None, None), Seq(Point(70.0, 30.0), Point(80.0, 50.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0)).copy(projectId = rap.id)
      val projectLink11 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad11, 0, 5, 1, AdministrativeClass.Unknown, Track.Combined, Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), idRoad11, 0.0, 0.0, SideCode.Unknown, 0, (None, None), Seq(Point(80.0, 50.0), Point(90.0, 60.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0)).copy(projectId = rap.id)


      val (linearCombined, rwComb): (LinearLocation, Roadway) = Seq(projectLink2).map(toRoadwayAndLinearLocation).head
      val (linearLeft1, rwLeft1): (LinearLocation, Roadway) = Seq(projectLink3).map(toRoadwayAndLinearLocation).head
      val (linearLeft2, rwLeft2): (LinearLocation, Roadway) = Seq(projectLink5).map(toRoadwayAndLinearLocation).head
      val (linearRight1, rwRight1): (LinearLocation, Roadway) = Seq(projectLink4).map(toRoadwayAndLinearLocation).head
      val (linearRight2, rwRight2): (LinearLocation, Roadway) = Seq(projectLink6).map(toRoadwayAndLinearLocation).head
      val rwCombWithId = rwComb.copy(id = roadwayId, ely = 8L)
      val rwLeft1And2WithId = rwLeft1.copy(id = roadwayId + 7, startAddrMValue = projectLink3.startAddrMValue, endAddrMValue = projectLink5.endAddrMValue, ely = 8L)
      val rwRight1And2WithId = rwRight1.copy(id = roadwayId + 8, startAddrMValue = projectLink4.startAddrMValue, endAddrMValue = projectLink6.endAddrMValue, ely = 8L)
      val linearCombinedWithId = linearCombined.copy(id = linearLocationId)
      val linearLeft1WithId = linearLeft1.copy(id = linearLocationId + 7)
      val linearLeft2WithId = linearLeft2.copy(id = linearLocationId + 8)
      val linearRight1WithId = linearRight1.copy(id = linearLocationId + 9)
      val linearRight2WithId = linearRight2.copy(id = linearLocationId + 10)
      val list1 = List(projectLink0, projectLink1, projectLink2, projectLink3, projectLink4, projectLink5, projectLink6)

      buildTestDataForProject(Some(rap), Some(Seq(rwCombWithId, rwLeft1And2WithId, rwRight1And2WithId)), Some(Seq(linearCombinedWithId, linearLeft1WithId, linearLeft2WithId, linearRight1WithId, linearRight2WithId)),
        Some(list1 ++ Seq(projectLink7, projectLink8, projectLink9, projectLink10, projectLink11)))

      val ordered = ProjectSectionCalculator.assignMValues(list1)
      ordered.minBy(_.startAddrMValue).id should be(idRoad0) // TODO "6 was not equal to 0"

      val list2 = ordered.toList ::: List(projectLink7, projectLink8, projectLink9, projectLink10, projectLink11)
      val ordered2 = ProjectSectionCalculator.assignMValues(list2)
      ordered2.maxBy(_.endAddrMValue).id should be(idRoad11)
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
      val projectLink0 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad0, 0, 5, 1, AdministrativeClass.Unknown, Track.LeftSide, Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), idRoad0, 0.0, 10.0, SideCode.Unknown, 0, (None, None), Seq(Point(30.0, 0.0), Point(30.0, 10.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink1 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad1, 0, 5, 1, AdministrativeClass.Unknown, Track.RightSide, Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), idRoad1, 0.0, 10.0, SideCode.Unknown, 0, (None, None), Seq(Point(40.0, 0.0), Point(40.0, 10.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink2 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad2, 0, 5, 1, AdministrativeClass.Unknown, Track.LeftSide, Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), idRoad2, 0.0, 10.0, SideCode.Unknown, 0, (None, None), Seq(Point(30.0, 10.0), Point(30.0, 20.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink3 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad3, 0, 5, 1, AdministrativeClass.Unknown, Track.RightSide, Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), idRoad3, 0.0, 10.0, SideCode.Unknown, 0, (None, None), Seq(Point(40.0, 10.0),Point(40.0, 20.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink4 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad4, 0, 5, 1, AdministrativeClass.Unknown, Track.LeftSide, Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), idRoad4, 0.0, 10.0, SideCode.Unknown, 0, (None, None), Seq(Point(40.0, 10.0), Point(30.0, 10.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink5 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad5, 0, 5, 1, AdministrativeClass.Unknown, Track.LeftSide, Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), idRoad5, 0.0, 20.0, SideCode.Unknown, 0, (None, None), Seq(Point(30.0, 10.0), Point(10.0, 10.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink6 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad6, 0, 5, 1, AdministrativeClass.Unknown, Track.RightSide, Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), idRoad6, 0.0, 10.0, SideCode.Unknown, 0, (None, None), Seq(Point(40.0, 20.0), Point(30.0, 20.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink7 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad7, 0, 5, 1, AdministrativeClass.Unknown, Track.LeftSide, Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), idRoad7, 0.0, 10.0, SideCode.Unknown, 0, (None, None), Seq(Point(10.0, 10.0), Point(0.0, 10.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink8 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad8, 0, 5, 1, AdministrativeClass.Unknown, Track.RightSide, Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), idRoad8, 0.0, 20.0, SideCode.Unknown, 0, (None, None), Seq(Point(30.0, 20.0),Point(10.0, 20.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink9 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad9, 0, 5, 1, AdministrativeClass.Unknown, Track.LeftSide, Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), idRoad9, 0.0, 10.0, SideCode.Unknown, 0, (None, None), Seq(Point(0.0, 10.0), Point(0.0, 20.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink10 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad10, 0, 5, 1, AdministrativeClass.Unknown, Track.RightSide, Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), idRoad10, 0.0, 10.0, SideCode.Unknown, 0, (None, None), Seq(Point(10.0, 20.0), Point(0.0, 20.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink11 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad11, 0, 5, 1, AdministrativeClass.Unknown, Track.LeftSide, Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), idRoad11, 0.0, 10.0, SideCode.Unknown, 0, (None, None), Seq(Point(0.0, 20.0), Point(0.0, 30.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink12 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad12, 0, 5, 1, AdministrativeClass.Unknown, Track.RightSide, Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), idRoad12, 0.0, 10.0, SideCode.Unknown, 0, (None, None), Seq(Point(10.0, 20.0), Point(10.0, 30.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))

      val listRight = List(projectLink1, projectLink3, projectLink6, projectLink8, projectLink10, projectLink12)

      ProjectSectionCalculator.assignMValues(listRight).toList

      val listLeft = List(projectLink0, projectLink2, projectLink4,projectLink5, projectLink7, projectLink9, projectLink11)

      ProjectSectionCalculator.assignMValues(listLeft).toList

      val list = ProjectSectionCalculator.assignMValues(listRight ::: listLeft)
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
      val projectLink0 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad0, 0, 5, 1, AdministrativeClass.Unknown, Track.LeftSide, Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), idRoad0, 0.0, 10.0, SideCode.Unknown, 0, (None, None), Seq(Point(10.0, 0.0), Point(0.0, 10.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink1 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad1, 0, 5, 1, AdministrativeClass.Unknown, Track.RightSide, Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), idRoad1, 0.0, 10.0, SideCode.Unknown, 0, (None, None), Seq(Point(20.0, 0.0), Point(15.0, 10.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink2 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad2, 0, 5, 1, AdministrativeClass.Unknown, Track.LeftSide, Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), idRoad2, 0.0, 10.0, SideCode.Unknown, 0, (None, None), Seq(Point(0.0, 10.0), Point(10.0, 20.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink3 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad3, 0, 5, 1, AdministrativeClass.Unknown, Track.RightSide, Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), idRoad3, 0.0, 10.0, SideCode.Unknown, 0, (None, None), Seq(Point(15.0, 10.0),Point(10.0, 20.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink4 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad4, 0, 5, 1, AdministrativeClass.Unknown, Track.LeftSide, Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), idRoad4, 0.0, 10.0, SideCode.Unknown, 0, (None, None), Seq(Point(10.0, 20.0), Point(40.0, 30.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink5 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad5, 0, 5, 1, AdministrativeClass.Unknown, Track.RightSide, Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), idRoad5, 0.0, 20.0, SideCode.Unknown, 0, (None, None), Seq(Point(10.0, 20.0), Point(45.0, 25.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink6 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad6, 0, 5, 1, AdministrativeClass.Unknown, Track.LeftSide, Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), idRoad6, 0.0, 10.0, SideCode.Unknown, 0, (None, None), Seq(Point(40.0, 30.0), Point(50.0, 35.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink7 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad7, 0, 5, 1, AdministrativeClass.Unknown, Track.RightSide, Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), idRoad7, 0.0, 10.0, SideCode.Unknown, 0, (None, None), Seq(Point(45.0, 25.0), Point(55.0, 30.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink8 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad8, 0, 5, 1, AdministrativeClass.Unknown, Track.LeftSide, Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), idRoad8, 0.0, 20.0, SideCode.Unknown, 0, (None, None), Seq(Point(45.0, 25.0),Point(40.0, 30.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink9 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad9, 0, 5, 1, AdministrativeClass.Unknown, Track.RightSide, Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), idRoad9, 0.0, 10.0, SideCode.Unknown, 0, (None, None), Seq(Point(55.0, 30.0), Point(55.0, 35.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink10 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad10, 0, 5, 1, AdministrativeClass.Unknown, Track.LeftSide, Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), idRoad10, 0.0, 10.0, SideCode.Unknown, 0, (None, None), Seq(Point(40.0, 30.0), Point(30.0, 40.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink11 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad11, 0, 5, 1, AdministrativeClass.Unknown, Track.RightSide, Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), idRoad11, 0.0, 10.0, SideCode.Unknown, 0, (None, None), Seq(Point(55.0, 35.0), Point(40.0, 50.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))

      val listRight = List(projectLink1, projectLink3, projectLink5, projectLink7,
        projectLink9, projectLink11)

      ProjectSectionCalculator.assignMValues(listRight).toList

      val listLeft = List(projectLink0, projectLink2, projectLink4, projectLink6, projectLink8, projectLink10)

      ProjectSectionCalculator.assignMValues(listLeft).toList

      val list = ProjectSectionCalculator.assignMValues(listRight ::: listLeft)
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
      val projectLink1 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad1, 0, 5, 1, AdministrativeClass.Municipality, Track.RightSide, Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), 12345L, 0.0, 89.0, SideCode.AgainstDigitizing, 0, (None, None), geom1, LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink2 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad2, 0, 5, 1, AdministrativeClass.Municipality, Track.LeftSide, Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), 12346L, 0.0, 89.7, SideCode.AgainstDigitizing, 0, (None, None), geom2, LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink3 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad3, 0, 5, 1, AdministrativeClass.Municipality, Track.RightSide, Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), 12347L, 0.0, 31.6, SideCode.AgainstDigitizing, 0, (None, None), geom3, LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink4 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad4, 0, 5, 1, AdministrativeClass.Municipality, Track.LeftSide, Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), 12348L, 0.0, 31.7, SideCode.AgainstDigitizing, 0, (None, None), geom4, LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink5 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad5, 0, 5, 1, AdministrativeClass.Municipality, Track.RightSide, Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), 12349L, 0.0, 17.3, SideCode.AgainstDigitizing, 0, (None, None), geom5, LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink6 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad6, 0, 5, 1, AdministrativeClass.Municipality, Track.LeftSide, MinorDiscontinuity, 0L, 0L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), 12350L, 0.0, 17.3, SideCode.AgainstDigitizing, 0, (None, None), geom6, LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink7 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad7, 0, 5, 1, AdministrativeClass.Municipality, Track.Combined, Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), 12351L, 0.0, 8.7, SideCode.AgainstDigitizing, 0, (None, None), geom7, LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink8 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad8, 0, 5, 1, AdministrativeClass.Municipality, Track.Combined, Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), 12352L, 0.0, 12.8, SideCode.AgainstDigitizing, 0, (None, None), geom8, LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink9 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad9, 0, 5, 1, AdministrativeClass.Municipality, Track.Combined, EndOfRoad, 0L, 0L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), 12353L, 0.0, 308.9, SideCode.AgainstDigitizing, 0, (None, None), geom9, LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))

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

      val linearLocationId = Sequences.nextLinearLocationId
      val roadwayNumber = Sequences.nextRoadwayNumber

      val raMap: Map[Long, RoadAddress] = Map(
        idRoad1 -> RoadAddress(idRoad1, linearLocationId + 1, 5, 1, AdministrativeClass.Municipality, Track.LeftSide, Continuous, 0L, 9L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), 12345L, 0.0, 10.6, SideCode.TowardsDigitizing, 0, (None, None), geom1, LinkGeomSource.NormalLinkInterface, 8, NoTermination, roadwayNumber + 1),
        idRoad2 -> RoadAddress(idRoad2, linearLocationId + 2, 5, 1, AdministrativeClass.Municipality, Track.LeftSide, Continuous, 9L, 38L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), 12346L, 0.0, 33.55, SideCode.TowardsDigitizing, 0, (None, None), geom2, LinkGeomSource.NormalLinkInterface, 8, NoTermination, roadwayNumber + 2),
        idRoad3 -> RoadAddress(idRoad3, linearLocationId + 3, 5, 1, AdministrativeClass.Municipality, Track.LeftSide, MinorDiscontinuity, 38L, 52L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), 12347L, 0.0, 16.22, SideCode.TowardsDigitizing, 0, (None, None), geom3, LinkGeomSource.NormalLinkInterface, 8, NoTermination, roadwayNumber + 3),
        idRoad4 -> RoadAddress(idRoad4, linearLocationId + 4, 5, 1, AdministrativeClass.Municipality, Track.LeftSide, Continuous, 52L, 62L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), 12348L, 0.0, 12.1, SideCode.AgainstDigitizing, 0, (None, None), geom4, LinkGeomSource.NormalLinkInterface, 8, NoTermination, roadwayNumber + 4),
        idRoad5 -> RoadAddress(idRoad5, linearLocationId + 5, 5, 1, AdministrativeClass.Municipality, Track.LeftSide, Continuous, 62L, 148L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), 12349L, 0.0, 99.51, SideCode.AgainstDigitizing, 0, (None, None), geom5, LinkGeomSource.NormalLinkInterface, 8, NoTermination, roadwayNumber + 5),
        idRoad6 -> RoadAddress(idRoad6, linearLocationId + 6, 5, 1, AdministrativeClass.Municipality, Track.LeftSide, Continuous, 148L, 165L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), 12350L, 0.0, 19.35, SideCode.AgainstDigitizing, 0, (None, None), geom6, LinkGeomSource.NormalLinkInterface, 8, NoTermination, roadwayNumber + 6),
        idRoad7 -> RoadAddress(idRoad7, linearLocationId + 7, 5, 1, AdministrativeClass.Municipality, Track.LeftSide, MinorDiscontinuity, 165L, 224L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), 12351L, 0.0, 68.66, SideCode.AgainstDigitizing, 0, (None, None), geom7, LinkGeomSource.NormalLinkInterface, 8, NoTermination, roadwayNumber + 7),
        idRoad8 -> RoadAddress(idRoad8, linearLocationId + 8, 5, 1, AdministrativeClass.Municipality, Track.RightSide, Continuous, 0L, 10L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), 12352L, 0.0, 10.6, SideCode.TowardsDigitizing, 0, (None, None), geom8, LinkGeomSource.NormalLinkInterface, 8, NoTermination, roadwayNumber + 8),
        idRoad9 -> RoadAddress(idRoad9, linearLocationId + 9, 5, 1, AdministrativeClass.Municipality, Track.RightSide, Continuous, 10L, 41L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), 12353L, 0.0, 34.77, SideCode.TowardsDigitizing, 0, (None, None), geom9, LinkGeomSource.NormalLinkInterface, 8, NoTermination, roadwayNumber + 9),
        idRoad10 -> RoadAddress(idRoad10, linearLocationId + 10, 5, 1, AdministrativeClass.Municipality, Track.RightSide, ParallelLink, 41L, 52L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), 12354L, 0.0, 11.74, SideCode.TowardsDigitizing, 0, (None, None), geom10, LinkGeomSource.NormalLinkInterface, 8, NoTermination, roadwayNumber + 10),
        idRoad11 -> RoadAddress(idRoad11, linearLocationId + 11, 5, 1, AdministrativeClass.Municipality, Track.RightSide, Continuous, 52L, 56L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), 12355L, 0.0, 4.27, SideCode.TowardsDigitizing, 0, (None, None), geom11, LinkGeomSource.NormalLinkInterface, 8, NoTermination, roadwayNumber + 11),
        idRoad12 -> RoadAddress(idRoad12, linearLocationId + 12, 5, 1, AdministrativeClass.Municipality, Track.RightSide, Continuous, 56L, 155L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), 12356L, 0.0, 108.7, SideCode.AgainstDigitizing, 0, (None, None), geom12, LinkGeomSource.NormalLinkInterface, 8, NoTermination, roadwayNumber + 12),
        idRoad13 -> RoadAddress(idRoad13, linearLocationId + 13, 5, 1, AdministrativeClass.Municipality, Track.RightSide, Continuous, 155L, 173L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), 12357L, 0.0, 19.2, SideCode.AgainstDigitizing, 0, (None, None), geom13, LinkGeomSource.NormalLinkInterface, 8, NoTermination, roadwayNumber + 13),
        idRoad14 -> RoadAddress(idRoad14, linearLocationId + 14, 5, 1, AdministrativeClass.Municipality, Track.RightSide, ParallelLink, 173L, 224L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), 12358L, 0.0, 55.94, SideCode.AgainstDigitizing, 0, (None, None), geom14, LinkGeomSource.NormalLinkInterface, 8, NoTermination, roadwayNumber + 14)
      )


      val projId = Sequences.nextViitePrimaryKeySeqValue
      val project = Project(projId, ProjectState.Incomplete, "f", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
        "", Seq(), Seq(), None, None)

      // Left pls
      val projectLink1 = toProjectLink(rap, LinkStatus.Transfer)(raMap(idRoad1))
      val projectLink2 = toProjectLink(rap, LinkStatus.Transfer)(raMap(idRoad2))
      val projectLink3 = toProjectLink(rap, LinkStatus.Transfer)(raMap(idRoad3))
      val projectLink4 = toProjectLink(rap, LinkStatus.Transfer)(raMap(idRoad4))
      val projectLink5 = toProjectLink(rap, LinkStatus.Transfer)(raMap(idRoad5))
      val projectLink6 = toProjectLink(rap, LinkStatus.Transfer)(raMap(idRoad6))
      val projectLink7 = toProjectLink(rap, LinkStatus.Transfer)(raMap(idRoad7))

      // Right pls
      val projectLink8 = toProjectLink(rap, LinkStatus.Transfer)(raMap(idRoad8))
      val projectLink9 = toProjectLink(rap, LinkStatus.Transfer)(raMap(idRoad9))
      val projectLink10 = toProjectLink(rap, LinkStatus.Transfer)(raMap(idRoad10))
      val projectLink11 = toProjectLink(rap, LinkStatus.Transfer)(raMap(idRoad11))
      val projectLink12 = toProjectLink(rap, LinkStatus.Transfer)(raMap(idRoad12))
      val projectLink13 = toProjectLink(rap, LinkStatus.Transfer)(raMap(idRoad13))
      val projectLink14 = toProjectLink(rap, LinkStatus.Transfer)(raMap(idRoad14))


      val leftProjectLinks = Seq(projectLink1, projectLink2, projectLink3, projectLink4, projectLink5, projectLink6, projectLink7).map(_.copy(projectId = projId))
      val rightProjectLinks = Seq(projectLink8, projectLink9, projectLink10,
        projectLink11, projectLink12, projectLink13, projectLink14).map(_.copy(projectId = projId))

      val (leftLinearLocations, leftRoadways) = toRoadwaysAndLinearLocations(leftProjectLinks)
      val (rightLinearLocations, rightRoadways) = toRoadwaysAndLinearLocations(rightProjectLinks)

      buildTestDataForProject(Some(project), Some(leftRoadways ++ rightRoadways), Some(leftLinearLocations ++ rightLinearLocations), Some(leftProjectLinks ++ rightProjectLinks))

      val output = ProjectSectionCalculator.assignMValues(leftProjectLinks ++ rightProjectLinks)

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

  /*
        ^
        | #1
           #3  #2
           --  <--
   */
  test("Test assignMValues When new link with discontinuity on both sides is added in the between of two other links Then the direction should stay same and new address values should be properly assigned") {
    runWithRollback {
      val idRoad1 = 1L
      val idRoad2 = 2L
      val idRoad3 = 3L

      val geom1 = Seq(Point(0.0, 20.0), Point(0.0, 30.0))
      val geom2 = Seq(Point(50.0, 0.0), Point(40.0, 2.0))
      val geom3 = Seq(Point(20.0, 5.0), Point(10.0, 10.0))

      val projectLink1 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad1, 0, 5, 1, AdministrativeClass.Municipality, Track.Combined, Continuous, 10L, 20L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), 12345L, 0.0, 10.6, SideCode.TowardsDigitizing, 0, (None, None), geom1, LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink2 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad2, 0, 5, 1, AdministrativeClass.Municipality, Track.Combined, MinorDiscontinuity, 0L, 10L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), 12346L, 0.0, 11.2, SideCode.TowardsDigitizing, 0, (None, None), geom2, LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink3 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad3, 0, 5, 1, AdministrativeClass.Municipality, Track.Combined, Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), 12347L, 0.0, 12.3, SideCode.Unknown, 0, (None, None), geom3, LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))

      val projectLinkSeq = Seq(projectLink1, projectLink2, projectLink3)

      val output = ProjectSectionCalculator.assignMValues(projectLinkSeq)

      output.length should be(3)

      output.zip(output.tail).foreach {
        case (prev, next) =>
          prev.endAddrMValue should be(next.startAddrMValue)
      }

      output.foreach(pl => {
        (pl.endAddrMValue - pl.startAddrMValue) should be >= 10L
        pl.sideCode should be(asset.SideCode.TowardsDigitizing)
        pl.reversed should be(false)
      }
      )
    }
  }

  /*
        ^
        c
        | #1
        c  #3  #2
           --  c--c
   */
  test("Test assignMValues When new link with discontinuity on both sides is added in the between of two other links having calibration Points at the beginning and end and discontinuity places Then the direction should stay same and new address values should be properly assigned") {
    runWithRollback {
      val idRoad1 = 1L
      val idRoad2 = 2L
      val idRoad3 = 3L

      val geom1 = Seq(Point(0.0, 20.0), Point(0.0, 30.0))
      val geom2 = Seq(Point(50.0, 0.0), Point(40.0, 2.0))
      val geom3 = Seq(Point(20.0, 5.0), Point(11.0, 10.0))

      val projectLink1 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad1, 0, 5, 1, AdministrativeClass.Municipality, Track.Combined, Continuous, 10L, 20L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), 12345L, 0.0, 10.6, SideCode.TowardsDigitizing, 0, (Some(CalibrationPoint(12345L, 0, 10L)), Some(CalibrationPoint(12345L, 10.6, 20L))), geom1, LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink2 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad2, 0, 5, 1, AdministrativeClass.Municipality, Track.Combined, MinorDiscontinuity, 0L, 10L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), 12346L, 0.0, 11.2, SideCode.TowardsDigitizing, 0, (Some(CalibrationPoint(12346L, 0, 0L)), Some(CalibrationPoint(12346L, 11.2, 10L))), geom2, LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink3 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad3, 0, 5, 1, AdministrativeClass.Municipality, Track.Combined, Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), 12347L, 0.0, 12.3, SideCode.Unknown, 0, (None, None), geom3, LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))

      val projectLinkSeq = Seq(projectLink1, projectLink2, projectLink3)

      val output = ProjectSectionCalculator.assignMValues(projectLinkSeq)

      output.length should be(3)

      output.zip(output.tail).foreach {
        case (prev, next) =>
          prev.endAddrMValue should be(next.startAddrMValue)
      }

      output.foreach(pl => {
        (pl.endAddrMValue - pl.startAddrMValue) should be(10L)
        pl.sideCode should be(asset.SideCode.TowardsDigitizing)
        pl.reversed should be(false)
      }
      )
    }
  }

  /*
        ^
        c
        | #1
        c  #3  #2
           --  --
   */
  test("Test assignMValues When two new links with discontinuities are added before existing and transfer operation is done last Then the direction should stay same and new address values should be properly assigned") {
    runWithRollback {
      val geom1 = Seq(Point(0.0, 20.0), Point(0.0, 30.0))
      val geom2 = Seq(Point(50.0, 0.0), Point(40.0, 2.0))
      val geom3 = Seq(Point(20.0, 5.0), Point(11.0, 10.0))

      val roadNumber = 9999L
      val roadPartNumber = 1L
      val administrativeClass = AdministrativeClass.State
      val track = Track.Combined
      val roadwayId1 = Sequences.nextRoadwayId
      val roadway1 = Roadway(roadwayId1, Sequences.nextRoadwayNumber, roadNumber, roadPartNumber, administrativeClass, track, Discontinuity.Continuous, 0L, 10L, reversed = false, DateTime.parse("2000-01-01"), None, "Test", None, 0, NoTermination)
      roadwayDAO.create(Seq(roadway1))

      val linkId1 = 12345L
      val link1 = Link(linkId1, LinkGeomSource.NormalLinkInterface.value, 0, None)
      LinkDAO.create(link1.id, link1.adjustedTimestamp, link1.source)

      val linearLocationId1 = Sequences.nextLinearLocationId
      val linearLocation1 = LinearLocation(linearLocationId1, 1, linkId1, 0.0, 10.0, SideCode.TowardsDigitizing, 0L,
        (CalibrationPointReference(Some(0L)), CalibrationPointReference(Some(10L))),
        geom1, LinkGeomSource.NormalLinkInterface, roadway1.roadwayNumber)
      linearLocationDAO.create(Seq(linearLocation1))

      val plId = Sequences.nextProjectLinkId
      val projectLink1 = ProjectLink(plId + 1, roadNumber, roadPartNumber, track, roadway1.discontinuity, 0L, 10L, roadway1.startAddrMValue, roadway1.endAddrMValue, None, None, None, linkId1, linearLocation1.startMValue, linearLocation1.endMValue, linearLocation1.sideCode, (RoadAddressCP, RoadAddressCP), (RoadAddressCP, RoadAddressCP), linearLocation1.geometry, 0L, LinkStatus.Transfer, roadway1.administrativeClass, LinkGeomSource.apply(link1.source.intValue()), GeometryUtils.geometryLength(linearLocation1.geometry), roadway1.id, linearLocation1.id, 0, roadway1.reversed, None, 86400L)
      val projectLink2 = ProjectLink(plId + 2, roadNumber, roadPartNumber, track, Discontinuity.MinorDiscontinuity, 0L, 10L, 0L, 0L, None, None, None, 12346L, 0.0, 11.2, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geom2, 0L, LinkStatus.New, administrativeClass, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geom2), 0L, 0, 0, reversed = false, None, 86400L)
      val projectLink3 = ProjectLink(plId + 3, roadNumber, roadPartNumber, track, Discontinuity.Continuous, 10L, 20L, 0L, 0L, None, None, None, 12347L, 0.0, 12.3, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geom3, 0L, LinkStatus.New, administrativeClass, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geom3), 0L, 0, 0, reversed = false, None, 86400L)

      val projectLinkSeq = Seq(projectLink1, projectLink2, projectLink3)

      val output = ProjectSectionCalculator.assignMValues(projectLinkSeq)

      output.length should be(3)

      output.zip(output.tail).foreach {
        case (prev, next) =>
          prev.endAddrMValue should be(next.startAddrMValue)
      }

      output.foreach(pl => {
        (pl.endAddrMValue - pl.startAddrMValue) should be(10L)
        pl.sideCode should be(asset.SideCode.TowardsDigitizing)
        pl.reversed should be(false)
      }
      )
    }
  }

  /*
        | #1
        v  #3  #2
           --  -->
   */
  test("Test assignMValues When new link with discontinuity on both sides is added in the between of two other links (against digitizing) Then the direction should stay same and new address values should be properly assigned") {
    runWithRollback {
      val idRoad1 = 1L
      val idRoad2 = 2L
      val idRoad3 = 3L

      val geom1 = Seq(Point(0.0, 20.0), Point(0.0, 30.0))
      val geom2 = Seq(Point(50.0, 0.0), Point(40.0, 2.0))
      val geom3 = Seq(Point(20.0, 5.0), Point(10.0, 10.0))

      val projectLink1 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad1, 0, 5, 1, AdministrativeClass.Municipality, Track.Combined, MinorDiscontinuity, 0L, 10L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), 12345L, 0.0, 10.6, SideCode.AgainstDigitizing, 0, (None, None), geom1, LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink2 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad2, 0, 5, 1, AdministrativeClass.Municipality, Track.Combined, Continuous, 10L, 20L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), 12346L, 0.0, 11.2, SideCode.AgainstDigitizing, 0, (None, None), geom2, LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink3 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad3, 0, 5, 1, AdministrativeClass.Municipality, Track.Combined, Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), 12347L, 0.0, 12.3, SideCode.Unknown, 0, (None, None), geom3, LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))

      val projectLinkSeq = Seq(projectLink1, projectLink2, projectLink3)

      val output = ProjectSectionCalculator.assignMValues(projectLinkSeq)

      output.length should be(3)

      output.zip(output.tail).foreach {
        case (prev, next) =>
          prev.endAddrMValue should be(next.startAddrMValue)
      }

      output.foreach(pl => {
        (pl.endAddrMValue - pl.startAddrMValue) should be >= 10L
        pl.sideCode should be(asset.SideCode.AgainstDigitizing)
        pl.reversed should be(false)
      }
      )
    }
  }

  /*
          #3  #2
          --  <--
    #1 |
       v
   */
  test("Test assignMValues When new link with discontinuity on both sides is added in the between of two other links (against digitizing) (geom 2) Then the direction should stay same and new address values should be properly assigned") {
    runWithRollback {
      val geom1 = Seq(Point(0.0, 0.0), Point(0.0, 10.0))
      val geom2 = Seq(Point(30.0, 22.0), Point(40.0, 23.0))
      val geom3 = Seq(Point(10.0, 20.0), Point(20.0, 21.0))

      val roadNumber = 5
      val roadPartNumber = 1
      val administrativeClass = AdministrativeClass.Municipality
      val track = Track.Combined
      val plId = Sequences.nextProjectLinkId
      val projectLink1 = ProjectLink(plId + 1, roadNumber, roadPartNumber, track, Discontinuity.MinorDiscontinuity, 0L, 10L, 0L, 0L, None, None, None, 12345L, 0.0, 10.0, SideCode.AgainstDigitizing, (NoCP, NoCP), (NoCP, NoCP), geom1, 0L, LinkStatus.New, administrativeClass, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geom1), 0L, 0, 0, reversed = false, None, 86400L)
      val projectLink2 = ProjectLink(plId + 2, roadNumber, roadPartNumber, track, Discontinuity.MinorDiscontinuity, 0L, 10L, 0L, 0L, None, None, None, 12346L, 0.0, 11.2, SideCode.AgainstDigitizing, (NoCP, NoCP), (NoCP, NoCP), geom2, 0L, LinkStatus.New, administrativeClass, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geom2), 0L, 0, 0, reversed = false, None, 86400L)
      val projectLink3 = ProjectLink(plId + 3, roadNumber, roadPartNumber, track, Discontinuity.Continuous, 10L, 20L, 0L, 0L, None, None, None, 12347L, 0.0, 12.3, SideCode.AgainstDigitizing, (NoCP, NoCP), (NoCP, NoCP), geom3, 0L, LinkStatus.New, administrativeClass, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geom3), 0L, 0, 0, reversed = false, None, 86400L)

      val projectLinkSeq = Seq(projectLink1, projectLink2, projectLink3)

      val output = ProjectSectionCalculator.assignMValues(projectLinkSeq)

      output.length should be(3)

      output.zip(output.tail).foreach {
        case (prev, next) =>
          prev.endAddrMValue should be(next.startAddrMValue)
      }

      output.foreach(pl => {
        (pl.endAddrMValue - pl.startAddrMValue) should be >= 10L
        pl.sideCode should be(asset.SideCode.AgainstDigitizing)
        pl.reversed should be(false)
      }
      )
    }
  }

  /*
            #3  #2
         ^  --  -->
      #1 |
   */
  test("Test assignMValues When new link with discontinuity on both sides is added in the between of two other links (geom 2) Then the direction should stay same and new address values should be properly assigned") {
    runWithRollback {
      val idRoad2 = 2L
      val idRoad3 = 3L

      val geom1 = Seq(Point(0.0, 0.0), Point(0.0, 10.0))
      val geom2 = Seq(Point(30.0, 22.0), Point(40.0, 23.0))
      val geom3 = Seq(Point(10.0, 20.0), Point(20.0, 21.0))

      val roadNumber = 5
      val roadPartNumber = 1
      val administrativeClass = AdministrativeClass.Municipality
      val track = Track.Combined
      val roadwayId1 = Sequences.nextRoadwayId
      val roadway1 = Roadway(roadwayId1, Sequences.nextRoadwayNumber, roadNumber, roadPartNumber, administrativeClass, track, MinorDiscontinuity, 0L, 10L, reversed = false, DateTime.parse("1901-01-01"), None, "tester", None, 8, NoTermination)
      roadwayDAO.create(Seq(roadway1))

      val linkId1 = 12345L
      val link1 = Link(linkId1, LinkGeomSource.NormalLinkInterface.value, 0, None)
      LinkDAO.create(link1.id, link1.adjustedTimestamp, link1.source)

      val linearLocationId1 = Sequences.nextLinearLocationId
      val linearLocation1 = LinearLocation(linearLocationId1, 1, linkId1, 0.0, 10.0, SideCode.TowardsDigitizing, 0L,
        (CalibrationPointReference(Some(0L)), CalibrationPointReference(Some(10L))),
        geom1, LinkGeomSource.NormalLinkInterface, roadway1.roadwayNumber)
      linearLocationDAO.create(Seq(linearLocation1))

      val plId = Sequences.nextProjectLinkId
      val projectLink1 = ProjectLink(plId + 1, roadNumber, roadPartNumber, track, roadway1.discontinuity, 0L, 10L, roadway1.startAddrMValue, roadway1.endAddrMValue, None, None, None, linkId1, linearLocation1.startMValue, linearLocation1.endMValue, linearLocation1.sideCode, (RoadAddressCP, RoadAddressCP), (RoadAddressCP, RoadAddressCP), linearLocation1.geometry, 0L, LinkStatus.Transfer, roadway1.administrativeClass, LinkGeomSource.apply(link1.source.intValue()), GeometryUtils.geometryLength(linearLocation1.geometry), roadway1.id, linearLocation1.id, 0, roadway1.reversed, None, 86400L)

      val projectLink2 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad2, 0, 5, 1, AdministrativeClass.Municipality, Track.Combined, Continuous, 10L, 20L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), 12346L, 0.0, 11.2, SideCode.TowardsDigitizing, 0, (None, None), geom2, LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink3 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad3, 0, 5, 1, AdministrativeClass.Municipality, Track.Combined, Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), 12347L, 0.0, 12.3, SideCode.Unknown, 0, (None, None), geom3, LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))

      val projectLinkSeq = Seq(projectLink1, projectLink2, projectLink3)

      val output = ProjectSectionCalculator.assignMValues(projectLinkSeq)

      output.length should be(3)

      output.zip(output.tail).foreach {
        case (prev, next) =>
          prev.endAddrMValue should be(next.startAddrMValue)
      }

      output.foreach(pl => {
        (pl.endAddrMValue - pl.startAddrMValue) should be >= 10L
        pl.sideCode should be(asset.SideCode.TowardsDigitizing)
        pl.reversed should be(false)
      }
      )
    }
  }

  test("Test assignMValues When ") {
    runWithRollback {
      val idRoad1 = 1L
      val idRoad2 = 2L
      val idRoad3 = 3L
      val idRoad4 = 4L
      val idRoad5 = 5L

      val geom1 = Seq(Point(370391.578, 6670661.466), Point(375054.876, 6672431.039)) //Seq(Point(0.0, 0.0), Point(20.0, 0.0))
      val geom2 = Seq(Point(370379.496, 6670659.548), Point(375052.461, 6672443.259)) //Seq(Point(0.0, 5.0), Point(20.0, 5.0))
      val geom3 = Seq(Point(375054.876, 6672431.039), Point(375053.012, 6672438.833)) //Seq(Point(20.0, 0.0), Point(20.0, 2.0))
      val geom4 = Seq(Point(375053.012, 6672438.833), Point(375052.461, 6672443.259)) //Seq(Point(20.0, 2.0), Point(20.0, 5.0))
      val geom5 = Seq(Point(375052.461, 6672443.259), Point(375169.282, 6673785.082)) //Seq(Point(20.0, 5.0), Point(20.0, 10.0))

      val projectLink1 = toProjectLink(rap, LinkStatus.Transfer)(RoadAddress(idRoad1, 0, 5, 1, AdministrativeClass.Municipality, Track.RightSide, Continuous, 0L, 20L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), 12345L, 0.0, geom1.head.distance2DTo(geom1.last), SideCode.TowardsDigitizing, 0, (None, Some(CalibrationPoint(12345L, geom1.head.distance2DTo(geom1.last), 20, RoadAddressCP))), geom1, LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink2 = toProjectLink(rap, LinkStatus.Transfer)(RoadAddress(idRoad2, 0, 5, 1, AdministrativeClass.Municipality, Track.LeftSide, MinorDiscontinuity, 0L, 20L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), 12346L, 0.0, geom2.head.distance2DTo(geom2.last), SideCode.TowardsDigitizing, 0, (None, Some(CalibrationPoint(12346L, geom2.head.distance2DTo(geom2.last), 20, RoadAddressCP))), geom2, LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink3 = toProjectLink(rap, LinkStatus.Transfer)(RoadAddress(idRoad3, 0, 5, 1, AdministrativeClass.Municipality, Track.Combined, Continuous, 20L, 22L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), 12347L, 0.0, geom3.head.distance2DTo(geom3.last), SideCode.TowardsDigitizing, 0, (Some(CalibrationPoint(12347L, geom3.head.distance2DTo(geom3.last), 20, RoadAddressCP)), None), geom3, LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))

      val projectLink4 = toProjectLink(rap, LinkStatus.Transfer)(RoadAddress(idRoad4, 0, 5, 1, AdministrativeClass.Municipality, Track.Combined, Continuous, 22L, 25L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), 12348L, 0.0, geom4.head.distance2DTo(geom4.last), SideCode.TowardsDigitizing, 0, (None, Some(CalibrationPoint(12348L, geom4.head.distance2DTo(geom4.last), 25, JunctionPointCP))), geom4, LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink5 = toProjectLink(rap, LinkStatus.Transfer)(RoadAddress(idRoad5, 0, 5, 1, AdministrativeClass.Municipality, Track.Combined, Continuous, 25L, 30L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), 12349L, 0.0, geom5.head.distance2DTo(geom5.last), SideCode.TowardsDigitizing, 0, (Some(CalibrationPoint(12349L, geom5.head.distance2DTo(geom5.last), 25, JunctionPointCP)), None), geom5, LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))

      val projectLinks = Seq(
toProjectLink(rap,LinkStatus.Transfer)(RoadAddress(109756,435778,40921,1,AdministrativeClass.Municipality,Track.LeftSide,Continuous,0,9,Some(DateTime.parse("1901-01-01")),None,Option("tester"),147951,0.000,9.393,SideCode.TowardsDigitizing,1533770939000L,(None,None),Seq(Point(370379.496,6670659.548,13.694000000003143),Point(370378.954,6670668.925,14.017999999996391)),LinkGeomSource.NormalLinkInterface,1,NoTermination,310124210)),
toProjectLink(rap,LinkStatus.Transfer)(RoadAddress(109723,433871,40921,1,AdministrativeClass.Municipality,Track.RightSide,Continuous,0,10,Some(DateTime.parse("1901-01-01")),None,Option("tester"),147952,0.000,9.821,SideCode.TowardsDigitizing,1533770939000L,(None,None),Seq(Point(370391.578,6670661.466,13.735000000000582),Point(370390.948,6670671.267,14.059992486161898)),LinkGeomSource.NormalLinkInterface,1,NoTermination,310124209)),
toProjectLink(rap,LinkStatus.Transfer)(RoadAddress(109757,435779,40921,1,AdministrativeClass.Municipality,Track.LeftSide,Continuous,9,95,Some(DateTime.parse("1901-01-01")),None,Option("tester"),147921,0.000,88.944,SideCode.TowardsDigitizing,1533770939000L,(None,None),Seq(Point(370378.954,6670668.925,14.017999999996391),Point(370376.139,6670688.793,14.622000000003027),Point(370373.49,6670712.64,15.426000000006752),Point(370370.527,6670735.433,16.21799999999348),Point(370369.774,6670757.319,16.436000000001513)),LinkGeomSource.NormalLinkInterface,1,NoTermination,310124210)),
toProjectLink(rap,LinkStatus.Transfer)(RoadAddress(109724,433872,40921,1,AdministrativeClass.Municipality,Track.RightSide,Continuous,10,93,Some(DateTime.parse("1901-01-01")),None,Option("tester"),147922,0.000,85.315,SideCode.TowardsDigitizing,1533770939000L,(None,None),Seq(Point(370390.948,6670671.267,14.059999999997672),Point(370389.849,6670687.447,14.460999999995693),Point(370386.205,6670718.573,15.554999999993015),Point(370383.528,6670740.788,16.379000000000815),Point(370381.446,6670756.03,16.79399691959179)),LinkGeomSource.NormalLinkInterface,1,NoTermination,310124209)),
toProjectLink(rap,LinkStatus.Transfer)(RoadAddress(109725,433873,40921,1,AdministrativeClass.Municipality,Track.RightSide,Continuous,93,152,Some(DateTime.parse("1901-01-01")),None,Option("tester"),147939,0.000,60.695,SideCode.TowardsDigitizing,1533770939000L,(None,None),Seq(Point(370381.446,6670756.03,16.793999999994412),Point(370378.22,6670773.862,17.35899999999674),Point(370374.116,6670794.926,18.028000000005704),Point(370369.698,6670815.572,18.68613615839637)),LinkGeomSource.NormalLinkInterface,1,NoTermination,310124209)),
toProjectLink(rap,LinkStatus.Transfer)(RoadAddress(109758,435780,40921,1,AdministrativeClass.Municipality,Track.LeftSide,Continuous,95,152,Some(DateTime.parse("1901-01-01")),None,Option("tester"),147940,0.000,58.099,SideCode.TowardsDigitizing,1533770939000L,(None,None),Seq(Point(370369.774,6670757.319,16.436000000001513),Point(370363.325,6670792.983,16.65799999999581),Point(370359.431,6670814.49,16.792345918317803)),LinkGeomSource.NormalLinkInterface,1,NoTermination,310124210)),
toProjectLink(rap,LinkStatus.Transfer)(RoadAddress(109718,433866,40921,1,AdministrativeClass.Municipality,Track.LeftSide,Continuous,152,162,Some(DateTime.parse("1901-01-01")),None,Option("tester"),147940,58.099,68.292,SideCode.TowardsDigitizing,1533770939000L,(None,None),Seq(Point(370359.431,6670814.49,16.792345918317803),Point(370357.615,6670824.52,16.85499916852895)),LinkGeomSource.NormalLinkInterface,1,NoTermination,310124216)),
toProjectLink(rap,LinkStatus.Transfer)(RoadAddress(109735,434617,40921,1,AdministrativeClass.Municipality,Track.RightSide,Continuous,152,169,Some(DateTime.parse("1901-01-01")),None,Option("tester"),147939,60.695,78.184,SideCode.TowardsDigitizing,1533770939000L,(None,None),Seq(Point(370369.698,6670815.572,18.68613615839637),Point(370369.498,6670816.509,18.71600000000035),Point(370366.351,6670832.738,19.230988852010835)),LinkGeomSource.NormalLinkInterface,1,NoTermination,310124215)),
toProjectLink(rap,LinkStatus.Transfer)(RoadAddress(109719,433867,40921,1,AdministrativeClass.Municipality,Track.LeftSide,Continuous,162,291,Some(DateTime.parse("1901-01-01")),None,Option("tester"),147862,0.000,133.392,SideCode.TowardsDigitizing,1533770939000L,(None,None),Seq(Point(370357.615,6670824.52,16.854999999995925),Point(370352.85,6670843.394,19.618000000002212),Point(370346.536,6670867.332,20.070000000006985),Point(370338.018,6670896.146,20.63300000000163),Point(370329.003,6670925.265,21.25),Point(370321.09,6670952.79,21.982999999832842)),LinkGeomSource.NormalLinkInterface,1,NoTermination,310124216)),
toProjectLink(rap,LinkStatus.Transfer)(RoadAddress(109736,434618,40921,1,AdministrativeClass.Municipality,Track.RightSide,Continuous,169,194,Some(DateTime.parse("1901-01-01")),None,Option("tester"),147934,0.000,25.550,SideCode.TowardsDigitizing,1533770939000L,(None,None),Seq(Point(370366.351,6670832.738,19.230999999999767),Point(370365.244,6670840.231,19.404999999998836),Point(370361.444,6670857.8,19.819000000003143)),LinkGeomSource.NormalLinkInterface,1,NoTermination,310124215)),
toProjectLink(rap,LinkStatus.Transfer)(RoadAddress(109737,434619,40921,1,AdministrativeClass.Municipality,Track.RightSide,Continuous,194,313,Some(DateTime.parse("1901-01-01")),None,Option("tester"),147858,0.000,121.963,SideCode.TowardsDigitizing,1533770939000L,(None,None),Seq(Point(370361.444,6670857.8,19.819000000003143),Point(370352.929,6670886.497,20.562000000005355),Point(370341.073,6670927.333,21.49000000000524),Point(370332.701,6670951.827,22.14299999999639),Point(370324.47,6670973.968,22.804000000003725)),LinkGeomSource.NormalLinkInterface,1,NoTermination,310124215)),
toProjectLink(rap,LinkStatus.Transfer)(RoadAddress(109720,433868,40921,1,AdministrativeClass.Municipality,Track.LeftSide,Continuous,291,311,Some(DateTime.parse("1901-01-01")),None,Option("tester"),147918,0.000,20.045,SideCode.TowardsDigitizing,1533770939000L,(None,None),Seq(Point(370321.09,6670952.79,21.9829999999929),Point(370316.195,6670971.161,22.555999999996857),Point(370315.777,6670972.106,22.58999076926776)),LinkGeomSource.NormalLinkInterface,1,NoTermination,310124216)),
toProjectLink(rap,LinkStatus.Transfer)(RoadAddress(109721,433869,40921,1,AdministrativeClass.Municipality,Track.LeftSide,Continuous,311,327,Some(DateTime.parse("1901-01-01")),None,Option("tester"),147910,0.000,16.276,SideCode.TowardsDigitizing,1533770939000L,(None,None),Seq(Point(370315.777,6670972.106,22.589999999996508),Point(370309.185,6670986.987,23.145999999993364)),LinkGeomSource.NormalLinkInterface,1,NoTermination,310124216)),
toProjectLink(rap,LinkStatus.Transfer)(RoadAddress(109738,434620,40921,1,AdministrativeClass.Municipality,Track.RightSide,Continuous,313,327,Some(DateTime.parse("1901-01-01")),None,Option("tester"),147902,0.000,14.434,SideCode.TowardsDigitizing,1533770939000L,(None,None),Seq(Point(370324.47,6670973.968,22.804000000003725),Point(370319.707,6670987.593,23.198000000003958)),LinkGeomSource.NormalLinkInterface,1,NoTermination,310124215)),
toProjectLink(rap,LinkStatus.Transfer)(RoadAddress(109722,433870,40921,1,AdministrativeClass.Municipality,Track.LeftSide,Continuous,327,339,Some(DateTime.parse("1901-01-01")),None,Option("tester"),147914,0.000,13.019,SideCode.TowardsDigitizing,1533770939000L,(None,None),Seq(Point(370309.185,6670986.987,23.145999999993364),Point(370304.192,6670999.01,23.463000000003376)),LinkGeomSource.NormalLinkInterface,1,NoTermination,310124216)),
toProjectLink(rap,LinkStatus.Transfer)(RoadAddress(109739,434621,40921,1,AdministrativeClass.Municipality,Track.RightSide,MinorDiscontinuity,327,339,Some(DateTime.parse("1901-01-01")),None,Option("tester"),147915,0.000,11.981,SideCode.TowardsDigitizing,1533770939000L,(None,None),Seq(Point(370319.707,6670987.593,23.198000000003958),Point(370315.122,6670998.662,23.53899925766114)),LinkGeomSource.NormalLinkInterface,1,NoTermination,310124215)),
toProjectLink(rap,LinkStatus.Transfer)(RoadAddress(109814,444034,40921,1,AdministrativeClass.Municipality,Track.Combined,Continuous,339,350,Some(DateTime.parse("1901-01-01")),None,Option("tester"),147888,0.000,10.936,SideCode.AgainstDigitizing,1533770939000L,(None,None),Seq(Point(370315.122,6670998.662,23.539000000004307),Point(370304.192,6670999.01,23.463000000003376)),LinkGeomSource.NormalLinkInterface,1,NoTermination,310124219)),
toProjectLink(rap,LinkStatus.Transfer)(RoadAddress(109815,444035,40921,1,AdministrativeClass.Municipality,Track.Combined,Continuous,350,366,Some(DateTime.parse("1901-01-01")),None,Option("tester"),147883,0.000,16.557,SideCode.TowardsDigitizing,1533770939000L,(None,None),Seq(Point(370315.122,6670998.662,23.539000000004307),Point(370331.675,6670999.018,23.5)),LinkGeomSource.NormalLinkInterface,1,NoTermination,310124219)),
toProjectLink(rap,LinkStatus.Transfer)(RoadAddress(109816,444036,40921,1,AdministrativeClass.Municipality,Track.Combined,Continuous,366,440,Some(DateTime.parse("1901-01-01")),None,Option("tester"),147896,0.000,74.343,SideCode.TowardsDigitizing,1533770939000L,(None,None),Seq(Point(370331.675,6670999.018,23.5),Point(370348.117,6671000.093,23.444000000003143),Point(370370.383,6671001.926,23.236999999993714),Point(370387.861,6671003.25,22.936000000001513),Point(370405.681,6671005.763,22.539999999993597)),LinkGeomSource.NormalLinkInterface,1,NoTermination,310124219)),
toProjectLink(rap,LinkStatus.Transfer)(RoadAddress(109817,444037,40921,1,AdministrativeClass.Municipality,Track.Combined,Continuous,440,603,Some(DateTime.parse("1901-01-01")),None,Option("tester"),148792,0.000,163.020,SideCode.TowardsDigitizing,1533770939000L,(None,None),Seq(Point(370405.681,6671005.763,22.539999999993597),Point(370455.111,6671013.1,21.269000000000233),Point(370470.092,6671016.028,20.913000000000466),Point(370484.884,6671019.56,20.679000000003725),Point(370513.356,6671024.959,20.072000000000116),Point(370541.06,6671029.698,19.47400000000198),Point(370566.29,6671033.333,18.862003100330355)),LinkGeomSource.NormalLinkInterface,1,NoTermination,310124219)),
toProjectLink(rap,LinkStatus.Transfer)(RoadAddress(109818,444038,40921,1,AdministrativeClass.Municipality,Track.Combined,Continuous,603,718,Some(DateTime.parse("1901-01-01")),None,Option("tester"),148813,0.000,116.177,SideCode.AgainstDigitizing,1533770939000L,(None,None),Seq(Point(370682.073,6671031.858,17.53299999999581),Point(370658.59,6671034.985,17.480999999999767),Point(370633.158,6671036.702,17.69100000000617),Point(370609.476,6671036.594,17.995999999999185),Point(370589.638,6671035.459,18.45799999999872),Point(370566.29,6671033.333,18.861992114458975)),LinkGeomSource.NormalLinkInterface,1,NoTermination,310124219)),
toProjectLink(rap,LinkStatus.Transfer)(RoadAddress(109819,444039,40921,1,AdministrativeClass.Municipality,Track.Combined,Continuous,718,780,Some(DateTime.parse("1901-01-01")),None,Option("tester"),148805,0.000,62.309,SideCode.AgainstDigitizing,1533770939000L,(None,None),Seq(Point(370742.383,6671016.282,18.245999999999185),Point(370731.287,6671019.41,18.043000000005122),Point(370721.946,6671022.045,17.8920000000071),Point(370706.498,6671026.31,17.630000000004657),Point(370692.8,6671029.596,17.53200000000652),Point(370682.073,6671031.858,17.532999956277934)),LinkGeomSource.NormalLinkInterface,1,NoTermination,310124219)),
toProjectLink(rap,LinkStatus.Transfer)(RoadAddress(109820,444040,40921,1,AdministrativeClass.Municipality,Track.Combined,Continuous,780,890,Some(DateTime.parse("1901-01-01")),None,Option("tester"),148810,0.000,109.990,SideCode.AgainstDigitizing,1533770939000L,(None,None),Seq(Point(370851.203,6671004.05,21.32600000000093),Point(370841.525,6671003.606,21.04099999999744),Point(370806.883,6671004.526,20.054999999993015),Point(370790.985,6671006.314,19.56500000000233),Point(370778.825,6671008.287,19.214000000007218),Point(370761.241,6671011.5,18.793999999994412),Point(370743.664,6671015.921,18.28299999999581),Point(370742.383,6671016.282,18.2460038632835)),LinkGeomSource.NormalLinkInterface,1,NoTermination,310124219)),
toProjectLink(rap,LinkStatus.Transfer)(RoadAddress(109821,444041,40921,1,AdministrativeClass.Municipality,Track.Combined,Continuous,890,1094,Some(DateTime.parse("1901-01-01")),None,Option("tester"),148765,0.000,205.846,SideCode.TowardsDigitizing,1533770939000L,(None,None),Seq(Point(370851.203,6671004.05,21.32600000000093),Point(370880.594,6671007.709,22.020000000004075),Point(370918.686,6671018.516,22.335999999995693),Point(370968.251,6671033.62,22.186000000001513),Point(371002.316,6671041.994,21.78299999999581),Point(371037.41,6671050.06,20.794999999998254),Point(371050.854,6671052.844,20.29200000000128)),LinkGeomSource.NormalLinkInterface,1,NoTermination,310124219)),
toProjectLink(rap,LinkStatus.Transfer)(RoadAddress(109822,444042,40921,1,AdministrativeClass.Municipality,Track.Combined,Continuous,1094,1145,Some(DateTime.parse("1901-01-01")),None,Option("tester"),148771,0.000,50.495,SideCode.TowardsDigitizing,1533770939000L,(None,None),Seq(Point(371050.854,6671052.844,20.29200000000128),Point(371091.137,6671060.152,18.802999999999884),Point(371100.556,6671061.755,18.346999999994296)),LinkGeomSource.NormalLinkInterface,1,NoTermination,310124219)),
toProjectLink(rap,LinkStatus.Transfer)(RoadAddress(109823,444043,40921,1,AdministrativeClass.Municipality,Track.Combined,Continuous,1145,1362,Some(DateTime.parse("1901-01-01")),None,Option("tester"),148761,0.000,219.008,SideCode.TowardsDigitizing,1533770939000L,(None,None),Seq(Point(371100.556,6671061.755,18.346999999994296),Point(371117.965,6671064.416,17.52599999999802),Point(371142.376,6671067.223,16.418999999994412),Point(371166.739,6671068.989,15.320999999996275),Point(371196.836,6671070.493,13.947000000000116),Point(371227.217,6671072.507,12.563999999998487),Point(371263.674,6671076.072,11.096000000005006),Point(371294.895,6671079.353,10.123999999996158),Point(371318.324,6671083.491,9.490999999994528)),LinkGeomSource.NormalLinkInterface,1,NoTermination,310124219)),
toProjectLink(rap,LinkStatus.Transfer)(RoadAddress(109824,444044,40921,1,AdministrativeClass.Municipality,Track.Combined,Continuous,1362,1609,Some(DateTime.parse("1901-01-01")),None,Option("tester"),148776,0.000,248.287,SideCode.TowardsDigitizing,1533770939000L,(None,None),Seq(Point(371318.324,6671083.491,9.490999999994528),Point(371349.044,6671087.854,9.039999999993597),Point(371391.538,6671100.045,8.236000000004424),Point(371430.309,6671112.947,7.438999999998487),Point(371467.186,6671126.563,6.724000000001979),Point(371503.424,6671143.366,5.9429999999993015),Point(371541.102,6671160.498,4.755000000004657),Point(371542.624,6671161.229,4.732000000003609),Point(371551.479,6671165.558,4.549007195487763)),LinkGeomSource.NormalLinkInterface,1,NoTermination,310124219)),
toProjectLink(rap,LinkStatus.Transfer)(RoadAddress(109825,444045,40921,1,AdministrativeClass.Municipality,Track.Combined,Continuous,1609,1752,Some(DateTime.parse("1901-01-01")),None,Option("tester"),148896,0.000,143.210,SideCode.TowardsDigitizing,1533770939000L,(None,None),Seq(Point(371551.479,6671165.558,4.548999999999069),Point(371576.866,6671177.938,4.415999999997439),Point(371594.616,6671186.538,4.3730000000068685),Point(371647.94,6671213.809,3.747000000003027),Point(371679.304,6671230.113,3.55800000000454)),LinkGeomSource.NormalLinkInterface,1,NoTermination,310124219)),
toProjectLink(rap,LinkStatus.Transfer)(RoadAddress(109826,444046,40921,1,AdministrativeClass.Municipality,Track.Combined,Continuous,1752,1813,Some(DateTime.parse("1901-01-01")),None,Option("tester"),148867,0.000,61.480,SideCode.TowardsDigitizing,1533770939000L,(None,None),Seq(Point(371679.304,6671230.113,3.55800000000454),Point(371698.023,6671241.87,3.456000000005588),Point(371726.065,6671260.524,3.1739999999990687),Point(371730.648,6671263.905,3.1150000000052387)),LinkGeomSource.NormalLinkInterface,1,NoTermination,310124219)),
toProjectLink(rap,LinkStatus.Transfer)(RoadAddress(109827,444047,40921,1,AdministrativeClass.Municipality,Track.Combined,Continuous,1813,1822,Some(DateTime.parse("1901-01-01")),None,Option("tester"),148871,0.000,9.054,SideCode.TowardsDigitizing,1533770939000L,(None,None),Seq(Point(371730.648,6671263.905,3.1150000000052387),Point(371737.934,6671269.28,3.0110009546679435)),LinkGeomSource.NormalLinkInterface,1,NoTermination,310124219)),
toProjectLink(rap,LinkStatus.Transfer)(RoadAddress(109828,444048,40921,1,AdministrativeClass.Municipality,Track.Combined,Continuous,1822,1957,Some(DateTime.parse("1901-01-01")),None,Option("tester"),11632617,0.000,135.334,SideCode.TowardsDigitizing,1575588960000L,(None,None),Seq(Point(371737.934,6671269.28,3.010999999998603),Point(371765.007,6671289.046,2.9689999999973224),Point(371786.179,6671307.289,2.8369999999995343),Point(371806.365,6671324.392,2.804999999993015),Point(371826.552,6671343.177,2.959000000002561),Point(371837.487,6671353.271,3.2920000000012806),Point(371841.049,6671356.71,3.3880000000062864)),LinkGeomSource.NormalLinkInterface,1,NoTermination,310124219)),
toProjectLink(rap,LinkStatus.Transfer)(RoadAddress(109829,444049,40921,1,AdministrativeClass.Municipality,Track.Combined,Continuous,1957,1975,Some(DateTime.parse("1901-01-01")),None,Option("tester"),11632613,0.000,17.876,SideCode.TowardsDigitizing,1575588960000L,(None,None),Seq(Point(371841.049,6671356.71,3.3880000000062864),Point(371845.617,6671361.12,3.6199999999953434),Point(371854.309,6671368.69,3.8950000000040745)),LinkGeomSource.NormalLinkInterface,1,NoTermination,310124219)),
toProjectLink(rap,LinkStatus.Transfer)(RoadAddress(109830,444050,40921,1,AdministrativeClass.Municipality,Track.Combined,Continuous,1975,2138,Some(DateTime.parse("1901-01-01")),None,Option("tester"),10739627,0.000,164.162,SideCode.TowardsDigitizing,1575588960000L,(None,None),Seq(Point(371854.309,6671368.69,3.8950000000040745),Point(371868.047,6671378.784,4.076000000000931),Point(371880.103,6671389.158,4.13300000000163),Point(371890.196,6671398.691,4.131999999997788),Point(371904.775,6671411.868,4.19999999999709),Point(371947.424,6671452.2,4.115000000005239),Point(371964.246,6671467.296,4.168999999994412),Point(371976.11,6671478.579,4.054000000003725)),LinkGeomSource.NormalLinkInterface,1,NoTermination,310124219)),
toProjectLink(rap,LinkStatus.Transfer)(RoadAddress(109831,444051,40921,1,AdministrativeClass.Municipality,Track.Combined,Continuous,2138,2426,Some(DateTime.parse("1901-01-01")),None,Option("tester"),141799,0.000,289.661,SideCode.TowardsDigitizing,1533770939000L,(None,None),Seq(Point(371976.11,6671478.579,4.054000000003725),Point(372001.926,6671499.745,3.9360000000015134),Point(372047.488,6671537.607,3.801000000006752),Point(372097.155,6671573.607,4.085000000006403),Point(372120.326,6671588.472,4.070000000006985),Point(372135.409,6671598.118,4.25800000000163),Point(372184.607,6671623.807,4.457999999998719),Point(372216.656,6671637.267,4.6929999999993015)),LinkGeomSource.NormalLinkInterface,1,NoTermination,310124219)),
toProjectLink(rap,LinkStatus.Transfer)(RoadAddress(109779,442108,40921,1,AdministrativeClass.Municipality,Track.LeftSide,Continuous,2426,2466,Some(DateTime.parse("1901-01-01")),None,Option("tester"),6899376,0.000,40.347,SideCode.TowardsDigitizing,1533770939000L,(None,None),Seq(Point(372216.656,6671637.267,4.6929999999993015),Point(372226.142,6671644.864,4.884999999994761),Point(372236.946,6671649.574,5.054999999993015),Point(372252.599,6671654.492,5.228000000002794)),LinkGeomSource.NormalLinkInterface,1,NoTermination,310124223)),
toProjectLink(rap,LinkStatus.Transfer)(RoadAddress(109750,435525,40921,1,AdministrativeClass.Municipality,Track.RightSide,Continuous,2426,2466,Some(DateTime.parse("1901-01-01")),None,Option("tester"),6899375,0.000,39.735,SideCode.TowardsDigitizing,1533770939000L,(None,None),Seq(Point(372216.656,6671637.267,4.6929999999993015),Point(372221.987,6671636.969,4.687000000005355),Point(372227.943,6671638.632,4.744999999995343),Point(372239.578,6671641.818,4.889999999999418),Point(372255.152,6671646.088,5.183996074667934)),LinkGeomSource.NormalLinkInterface,1,NoTermination,310124222)),
toProjectLink(rap,LinkStatus.Transfer)(RoadAddress(109780,442109,40921,1,AdministrativeClass.Municipality,Track.LeftSide,Continuous,2466,2666,Some(DateTime.parse("1901-01-01")),None,Option("tester"),6899360,0.000,201.811,SideCode.TowardsDigitizing,1533770939000L,(None,None),Seq(Point(372252.599,6671654.492,5.228000000002794),Point(372278.084,6671661.418,5.645999999993364),Point(372314.376,6671672.222,6.17500000000291),Point(372347.896,6671680.256,6.713000000003376),Point(372380.17,6671687.805,6.994000000006054),Point(372410.504,6671690.229,7.120999999999185),Point(372438.484,6671691.061,7.229000000006636),Point(372449.982,6671691.135,7.282999306402636)),LinkGeomSource.NormalLinkInterface,1,NoTermination,310124223)),
toProjectLink(rap,LinkStatus.Transfer)(RoadAddress(109751,435526,40921,1,AdministrativeClass.Municipality,Track.RightSide,Continuous,2466,2665,Some(DateTime.parse("1901-01-01")),None,Option("tester"),6899357,0.000,197.715,SideCode.TowardsDigitizing,1533770939000L,(None,None),Seq(Point(372255.152,6671646.088,5.18399999999383),Point(372280.578,6671652.207,5.68300000000454),Point(372308.696,6671659.825,6.00800000000163),Point(372358.839,6671672.014,6.918999999994412),Point(372403.163,6671677.832,7.319000000003143),Point(372449.283,6671677.203,7.450999008528791)),LinkGeomSource.NormalLinkInterface,1,NoTermination,310124222)),
toProjectLink(rap,LinkStatus.Transfer)(RoadAddress(109752,435527,40921,1,AdministrativeClass.Municipality,Track.RightSide,Continuous,2665,2682,Some(DateTime.parse("1901-01-01")),None,Option("tester"),6899366,0.000,16.782,SideCode.AgainstDigitizing,1533770939000L,(None,None),Seq(Point(372466.052,6671676.534,7.559999999997672),Point(372458.568,6671676.862,7.5019999999931315),Point(372449.283,6671677.203,7.451002436078033)),LinkGeomSource.NormalLinkInterface,1,NoTermination,310124222)),
toProjectLink(rap,LinkStatus.Transfer)(RoadAddress(109781,442110,40921,1,AdministrativeClass.Municipality,Track.LeftSide,Continuous,2666,2681,Some(DateTime.parse("1901-01-01")),None,Option("tester"),6899367,0.000,15.374,SideCode.TowardsDigitizing,1533770939000L,(None,None),Seq(Point(372449.982,6671691.135,7.282999999995809),Point(372465.356,6671691.199,7.3509994107994965)),LinkGeomSource.NormalLinkInterface,1,NoTermination,310124223)),
toProjectLink(rap,LinkStatus.Transfer)(RoadAddress(109782,442111,40921,1,AdministrativeClass.Municipality,Track.LeftSide,MinorDiscontinuity,2681,2760,Some(DateTime.parse("1901-01-01")),None,Option("tester"),141884,0.000,79.345,SideCode.AgainstDigitizing,1533770939000L,(None,None),Seq(Point(372544.536,6671686.427,7.237999999997555),Point(372525.609,6671686.905,8.247000000003027),Point(372498.045,6671688.429,7.812000000005355),Point(372465.356,6671691.199,7.351004013374894)),LinkGeomSource.NormalLinkInterface,1,NoTermination,310124223)),
toProjectLink(rap,LinkStatus.Transfer)(RoadAddress(109753,435528,40921,1,AdministrativeClass.Municipality,Track.RightSide,MinorDiscontinuity,2682,2760,Some(DateTime.parse("1901-01-01")),None,Option("tester"),6899349,0.000,77.836,SideCode.AgainstDigitizing,1533770939000L,(None,None),Seq(Point(372543.795,6671672.756,8.436000000001513),Point(372506.356,6671674.785,7.970000000001164),Point(372466.052,6671676.534,7.559999999997672)),LinkGeomSource.NormalLinkInterface,1,NoTermination,310124222)),
toProjectLink(rap,LinkStatus.Transfer)(RoadAddress(109832,444189,40921,1,AdministrativeClass.Municipality,Track.LeftSide,Continuous,2760,2902,Some(DateTime.parse("1901-01-01")),None,Option("tester"),141917,0.000,142.948,SideCode.AgainstDigitizing,1533770939000L,(None,None),Seq(Point(372733.591,6671658.665,6.167000000001281),Point(372714.12,6671662.897,6.490000000005239),Point(372686.748,6671667.977,6.936000000001513),Point(372631.438,6671676.724,7.975000000005821),Point(372593.085,6671684.759,8.646999609071697)),LinkGeomSource.NormalLinkInterface,1,NoTermination,310124231)),
toProjectLink(rap,LinkStatus.Transfer)(RoadAddress(109726,434077,40921,1,AdministrativeClass.Municipality,Track.RightSide,Continuous,2760,2901,Some(DateTime.parse("1901-01-01")),None,Option("tester"),6929437,0.000,141.678,SideCode.AgainstDigitizing,1533770939000L,(None,None),Seq(Point(372732.735,6671645.647,6.1450000000040745),Point(372637.365,6671661.205,7.80000000000291),Point(372592.864,6671668.199,7.710999999995693)),LinkGeomSource.NormalLinkInterface,1,NoTermination,310124230)),
toProjectLink(rap,LinkStatus.Transfer)(RoadAddress(109727,434078,40921,1,AdministrativeClass.Municipality,Track.RightSide,Continuous,2901,2917,Some(DateTime.parse("1901-01-01")),None,Option("tester"),6929445,0.000,15.795,SideCode.AgainstDigitizing,1533770939000L,(None,None),Seq(Point(372748.256,6671642.719,5.926000000006752),Point(372732.735,6671645.647,6.1450000000040745)),LinkGeomSource.NormalLinkInterface,1,NoTermination,310124230)),
toProjectLink(rap,LinkStatus.Transfer)(RoadAddress(109833,444190,40921,1,AdministrativeClass.Municipality,Track.LeftSide,Continuous,2902,2919,Some(DateTime.parse("1901-01-01")),None,Option("tester"),141941,0.000,16.705,SideCode.AgainstDigitizing,1533770939000L,(None,None),Seq(Point(372750.034,6671655.716,5.93399999999383),Point(372733.591,6671658.665,6.1669950634883195)),LinkGeomSource.NormalLinkInterface,1,NoTermination,310124231)),
toProjectLink(rap,LinkStatus.Transfer)(RoadAddress(109728,434079,40921,1,AdministrativeClass.Municipality,Track.RightSide,Continuous,2917,3090,Some(DateTime.parse("1901-01-01")),None,Option("tester"),146076,0.000,173.704,SideCode.AgainstDigitizing,1533770939000L,(None,None),Seq(Point(372920.705,6671622.229,5.755000000004657),Point(372841.387,6671629.882,5.792000000001281),Point(372781.563,6671638.629,5.646999999997206),Point(372748.256,6671642.719,5.926000000006752)),LinkGeomSource.NormalLinkInterface,1,NoTermination,310124230)),
toProjectLink(rap,LinkStatus.Transfer)(RoadAddress(109834,444191,40921,1,AdministrativeClass.Municipality,Track.LeftSide,Continuous,2919,3089,Some(DateTime.parse("1901-01-01")),None,Option("tester"),146078,0.000,170.843,SideCode.AgainstDigitizing,1533770939000L,(None,None),Seq(Point(372919.698,6671635.833,5.760999999998603),Point(372876.379,6671640.323,5.980999999999767),Point(372829.817,6671645.12,5.73399999999674),Point(372781.281,6671651.61,5.630000000004657),Point(372750.034,6671655.716,5.933998794543409)),LinkGeomSource.NormalLinkInterface,1,NoTermination,310124231)),
toProjectLink(rap,LinkStatus.Transfer)(RoadAddress(109835,444192,40921,1,AdministrativeClass.Municipality,Track.LeftSide,Continuous,3089,3111,Some(DateTime.parse("1901-01-01")),None,Option("tester"),146075,0.000,21.332,SideCode.AgainstDigitizing,1533770939000L,(None,None),Seq(Point(372940.999,6671634.68,5.5899999999965075),Point(372919.698,6671635.833,5.760998537107941)),LinkGeomSource.NormalLinkInterface,1,NoTermination,310124231)),
toProjectLink(rap,LinkStatus.Transfer)(RoadAddress(109729,434080,40921,1,AdministrativeClass.Municipality,Track.RightSide,Continuous,3090,3111,Some(DateTime.parse("1901-01-01")),None,Option("tester"),6929456,0.000,21.002,SideCode.AgainstDigitizing,1533770939000L,(None,None),Seq(Point(372941.616,6671620.272,5.607999999992899),Point(372920.705,6671622.229,5.754997372908844)),LinkGeomSource.NormalLinkInterface,1,NoTermination,310124230)),
toProjectLink(rap,LinkStatus.Transfer)(RoadAddress(109836,444193,40921,1,AdministrativeClass.Municipality,Track.LeftSide,Continuous,3111,3309,Some(DateTime.parse("1901-01-01")),None,Option("tester"),145933,0.000,199.144,SideCode.AgainstDigitizing,1533770939000L,(None,None),Seq(Point(373140,6671632.811,4.463000000003376),Point(373105.395,6671632.245,4.635999999998603),Point(373054.36,6671631,4.982999999992899),Point(373006.219,6671630.693,5.2480000000068685),Point(372967.243,6671632.986,5.429000000003725),Point(372940.999,6671634.68,5.5899999999965075)),LinkGeomSource.NormalLinkInterface,1,NoTermination,310124231)),
toProjectLink(rap,LinkStatus.Transfer)(RoadAddress(109730,434081,40921,1,AdministrativeClass.Municipality,Track.RightSide,Continuous,3111,3430,Some(DateTime.parse("1901-01-01")),None,Option("tester"),145934,0.000,320.156,SideCode.TowardsDigitizing,1533770939000L,(None,None),Seq(Point(372941.616,6671620.272,5.607999999992899),Point(372969.757,6671619.335,5.501000000003842),Point(373014.217,6671618.555,5.217000000004191),Point(373085.457,6671618.296,4.771999999997206),Point(373155.657,6671620.896,4.342999999993481),Point(373222.737,6671626.096,4.688999999998487),Point(373261.262,6671630.384,5.196997514610281)),LinkGeomSource.NormalLinkInterface,1,NoTermination,310124230)),
toProjectLink(rap,LinkStatus.Transfer)(RoadAddress(109837,444194,40921,1,AdministrativeClass.Municipality,Track.LeftSide,Continuous,3309,3427,Some(DateTime.parse("1901-01-01")),None,Option("tester"),146026,0.000,118.298,SideCode.TowardsDigitizing,1533770939000L,(None,None),Seq(Point(373140,6671632.811,4.463000000003376),Point(373160.019,6671634.04,4.350999999995111),Point(373190.065,6671636.24,4.301999999996042),Point(373237.687,6671640.848,4.918999999994412),Point(373257.824,6671643.166,5.2149999999965075)),LinkGeomSource.NormalLinkInterface,1,NoTermination,310124231)),
toProjectLink(rap,LinkStatus.Transfer)(RoadAddress(109838,444195,40921,1,AdministrativeClass.Municipality,Track.LeftSide,Continuous,3427,3464,Some(DateTime.parse("1901-01-01")),None,Option("tester"),146041,0.000,36.946,SideCode.TowardsDigitizing,1533770939000L,(None,None),Seq(Point(373257.824,6671643.166,5.2149999999965075),Point(373282.124,6671645.959,5.1450000000040745),Point(373294.559,6671647.091,5.109001162562755)),LinkGeomSource.NormalLinkInterface,1,NoTermination,310124231)),
toProjectLink(rap,LinkStatus.Transfer)(RoadAddress(109731,434082,40921,1,AdministrativeClass.Municipality,Track.RightSide,Continuous,3430,3469,Some(DateTime.parse("1901-01-01")),None,Option("tester"),146042,0.000,38.733,SideCode.TowardsDigitizing,1533770939000L,(None,None),Seq(Point(373261.262,6671630.384,5.197000000000116),Point(373269.277,6671631.296,5.167000000001281),Point(373299.599,6671635.879,5.053000414228631)),LinkGeomSource.NormalLinkInterface,1,NoTermination,310124230)),
toProjectLink(rap,LinkStatus.Transfer)(RoadAddress(109839,444196,40921,1,AdministrativeClass.Municipality,Track.LeftSide,Continuous,3464,3479,Some(DateTime.parse("1901-01-01")),None,Option("tester"),146037,0.000,15.183,SideCode.TowardsDigitizing,1533770939000L,(None,None),Seq(Point(373294.559,6671647.091,5.10899999999674),Point(373309.501,6671649.784,4.851875583240156)),LinkGeomSource.NormalLinkInterface,1,NoTermination,310124231)),
toProjectLink(rap,LinkStatus.Transfer)(RoadAddress(109732,434083,40921,1,AdministrativeClass.Municipality,Track.RightSide,Continuous,3469,3479,Some(DateTime.parse("1901-01-01")),None,Option("tester"),146038,0.000,9.935,SideCode.TowardsDigitizing,1533770939000L,(None,None),Seq(Point(373299.599,6671635.879,5.052999999999884),Point(373309.423,6671637.362,4.926675671131255)),LinkGeomSource.NormalLinkInterface,1,NoTermination,310124230)),
toProjectLink(rap,LinkStatus.Transfer)(RoadAddress(109701,432295,40921,1,AdministrativeClass.Municipality,Track.LeftSide,Continuous,3479,3537,Some(DateTime.parse("1901-01-01")),None,Option("tester"),146037,15.183,73.893,SideCode.TowardsDigitizing,1533770939000L,(None,None),Seq(Point(373309.501,6671649.784,4.851875583240156),Point(373317.223,6671651.176,4.7189999999973224),Point(373346.506,6671656.205,4.263999999995576),Point(373367.342,6671659.851,4.20700132537043)),LinkGeomSource.NormalLinkInterface,1,NoTermination,310124237)),
toProjectLink(rap,LinkStatus.Transfer)(RoadAddress(109769,435960,40921,1,AdministrativeClass.Municipality,Track.RightSide,Continuous,3479,3539,Some(DateTime.parse("1901-01-01")),None,Option("tester"),146038,9.935,70.537,SideCode.TowardsDigitizing,1533770939000L,(None,None),Seq(Point(373309.423,6671637.362,4.926675671131255),Point(373319.196,6671638.837,4.801000000006752),Point(373368.856,6671646.117,4.17500000000291),Point(373369.373,6671646.221,4.176999553139369)),LinkGeomSource.NormalLinkInterface,1,NoTermination,310124236)),
toProjectLink(rap,LinkStatus.Transfer)(RoadAddress(109702,432296,40921,1,AdministrativeClass.Municipality,Track.LeftSide,Continuous,3537,3554,Some(DateTime.parse("1901-01-01")),None,Option("tester"),146050,0.000,17.027,SideCode.TowardsDigitizing,1533770939000L,(None,None),Seq(Point(373367.342,6671659.851,4.206999999994878),Point(373374.53,6671661.362,4.388999999995576),Point(373384.031,6671663.226,4.429999065959265)),LinkGeomSource.NormalLinkInterface,1,NoTermination,310124237)),
toProjectLink(rap,LinkStatus.Transfer)(RoadAddress(109770,435961,40921,1,AdministrativeClass.Municipality,Track.RightSide,Continuous,3539,3560,Some(DateTime.parse("1901-01-01")),None,Option("tester"),146052,0.000,20.429,SideCode.TowardsDigitizing,1533770939000L,(None,None),Seq(Point(373369.373,6671646.221,4.176999999996042),Point(373389.396,6671650.276,4.400994763601137)),LinkGeomSource.NormalLinkInterface,1,NoTermination,310124236)),
toProjectLink(rap,LinkStatus.Transfer)(RoadAddress(109703,432297,40921,1,AdministrativeClass.Municipality,Track.LeftSide,Continuous,3554,3745,Some(DateTime.parse("1901-01-01")),None,Option("tester"),145977,0.000,191.353,SideCode.TowardsDigitizing,1533770939000L,(None,None),Seq(Point(373384.031,6671663.226,4.429999999993015),Point(373400.71,6671666.373,4.599000000001979),Point(373430.109,6671671.467,4.9689999999973224),Point(373453.309,6671676.614,5.341000000000349),Point(373477.468,6671682.636,5.729000000006636),Point(373498.721,6671690.397,6.006999999997788),Point(373521.41,6671699.471,6.304999999993015),Point(373544.903,6671711.034,6.679000000003725),Point(373565.003,6671721.441,6.985994575919156)),LinkGeomSource.NormalLinkInterface,1,NoTermination,310124237)),
toProjectLink(rap,LinkStatus.Transfer)(RoadAddress(109771,435962,40921,1,AdministrativeClass.Municipality,Track.RightSide,Continuous,3560,3752,Some(DateTime.parse("1901-01-01")),None,Option("tester"),145976,0.000,192.748,SideCode.TowardsDigitizing,1533770939000L,(None,None),Seq(Point(373389.396,6671650.276,4.400999999998021),Point(373414.617,6671655.216,4.877999999996973),Point(373449.717,6671662.756,5.595000000001164),Point(373479.979,6671672.187,5.915999999997439),Point(373505.649,6671681.125,6.254000000000815),Point(373532.349,6671692.543,6.649000000004889),Point(373558.685,6671706.044,7.048999999999069),Point(373570.792,6671712.313,7.237999999997555)),LinkGeomSource.NormalLinkInterface,1,NoTermination,310124236)),
toProjectLink(rap,LinkStatus.Transfer)(RoadAddress(109704,432298,40921,1,AdministrativeClass.Municipality,Track.LeftSide,Continuous,3745,3761,Some(DateTime.parse("1901-01-01")),None,Option("tester"),146015,0.000,16.042,SideCode.TowardsDigitizing,1533770939000L,(None,None),Seq(Point(373565.003,6671721.441,6.986000000004424),Point(373567.488,6671722.869,7.032999999995809),Point(373578.604,6671729.943,7.170999189772006)),LinkGeomSource.NormalLinkInterface,1,NoTermination,310124237)),
toProjectLink(rap,LinkStatus.Transfer)(RoadAddress(109772,435963,40921,1,AdministrativeClass.Municipality,Track.RightSide,Continuous,3752,3767,Some(DateTime.parse("1901-01-01")),None,Option("tester"),146006,0.000,15.673,SideCode.TowardsDigitizing,1533770939000L,(None,None),Seq(Point(373570.792,6671712.313,7.237999999997555),Point(373584.714,6671719.511,7.4649999999965075)),LinkGeomSource.NormalLinkInterface,1,NoTermination,310124236)),
toProjectLink(rap,LinkStatus.Transfer)(RoadAddress(109705,432299,40921,1,AdministrativeClass.Municipality,Track.LeftSide,Continuous,3761,3777,Some(DateTime.parse("1901-01-01")),None,Option("tester"),146009,0.000,16.737,SideCode.TowardsDigitizing,1533770939000L,(None,None),Seq(Point(373578.604,6671729.943,7.1710000000020955),Point(373585.22,6671734.006,7.263999999995576),Point(373593.001,6671738.475,7.406999218724745)),LinkGeomSource.NormalLinkInterface,1,NoTermination,310124237)),
toProjectLink(rap,LinkStatus.Transfer)(RoadAddress(109773,435964,40921,1,AdministrativeClass.Municipality,Track.RightSide,Continuous,3767,3784,Some(DateTime.parse("1901-01-01")),None,Option("tester"),146010,0.000,16.354,SideCode.TowardsDigitizing,1533770939000L,(None,None),Seq(Point(373584.714,6671719.511,7.4649999999965075),Point(373591.637,6671723.608,7.562999999994645),Point(373598.753,6671727.898,7.706000000005588)),LinkGeomSource.NormalLinkInterface,1,NoTermination,310124236)),
toProjectLink(rap,LinkStatus.Transfer)(RoadAddress(109706,432300,40921,1,AdministrativeClass.Municipality,Track.LeftSide,Continuous,3777,3979,Some(DateTime.parse("1901-01-01")),None,Option("tester"),145973,0.000,202.531,SideCode.TowardsDigitizing,1533770939000L,(None,None),Seq(Point(373593.001,6671738.475,7.407000000006519),Point(373615.524,6671753.987,7.775999999998021),Point(373634.807,6671768.957,8.059999999997672),Point(373654.392,6671785.522,8.414000000004307),Point(373676.978,6671807.462,8.614000000001397),Point(373695.554,6671827.058,8.623000000006869),Point(373712.076,6671846.976,8.456999999994878),Point(373726.213,6671864.937,8.273000000001048),Point(373737.411,6671878.896,8.179001250517546)),LinkGeomSource.NormalLinkInterface,1,NoTermination,310124237)),
toProjectLink(rap,LinkStatus.Transfer)(RoadAddress(109774,435965,40921,1,AdministrativeClass.Municipality,Track.RightSide,Continuous,3784,3990,Some(DateTime.parse("1901-01-01")),None,Option("tester"),145975,0.000,206.773,SideCode.TowardsDigitizing,1533770939000L,(None,None),Seq(Point(373598.753,6671727.898,7.706000000005588),Point(373615.076,6671739.079,8.013999999995576),Point(373631.443,6671750.508,8.331000000005588),Point(373652.632,6671766.999,8.682000000000698),Point(373676.311,6671787.741,8.955000000001746),Point(373694.814,6671805.733,9.097999999998137),Point(373717.348,6671831.579,8.956999999994878),Point(373737.776,6671856.602,8.451000000000931),Point(373747.111,6671869.958,8.240999999994528)),LinkGeomSource.NormalLinkInterface,1,NoTermination,310124236)),
toProjectLink(rap,LinkStatus.Transfer)(RoadAddress(109707,432301,40921,1,AdministrativeClass.Municipality,Track.LeftSide,Continuous,3979,4234,Some(DateTime.parse("1901-01-01")),None,Option("tester"),145794,0.000,255.774,SideCode.TowardsDigitizing,1533770939000L,(None,None),Seq(Point(373737.411,6671878.896,8.179000000003725),Point(373743.048,6671886.678,8.10099999999511),Point(373753.309,6671900.222,7.947000000000116),Point(373774.999,6671927.715,8.027000000001863),Point(373794.199,6671950.956,8.25800000000163),Point(373810.627,6671968.812,8.365000000005239),Point(373819.806,6671978.73,8.474000000001979),Point(373832.255,6671989.877,8.486000000004424),Point(373849.59,6672002.41,8.30899999999383),Point(373868.738,6672014.401,8.097999999998137),Point(373891.084,6672026.016,7.81699999999546),Point(373911.919,6672034.715,7.642999999996391),Point(373928.515,6672041.08,7.524002941854747)),LinkGeomSource.NormalLinkInterface,1,NoTermination,310124237)),
toProjectLink(rap,LinkStatus.Transfer)(RoadAddress(109775,435966,40921,1,AdministrativeClass.Municipality,Track.RightSide,Continuous,3990,4239,Some(DateTime.parse("1901-01-01")),None,Option("tester"),145795,0.000,250.380,SideCode.TowardsDigitizing,1533770939000L,(None,None),Seq(Point(373747.111,6671869.958,8.240999999994528),Point(373753.924,6671878.376,8.14999999999418),Point(373770.332,6671900.827,7.966000000000349),Point(373787.928,6671924.141,7.9429999999993015),Point(373804.94,6671944.727,7.980999999999767),Point(373820.889,6671962.145,8.051000000006752),Point(373838.891,6671979.241,8.063999999998487),Point(373856.018,6671992.242,7.9980000000068685),Point(373876.364,6672005.327,7.775999999998021),Point(373896.606,6672016.119,7.562999999994645),Point(373917.67,6672024.808,7.334000000002561),Point(373932.567,6672029.183,7.190000000002328),Point(373932.994,6672029.291,7.186000000001513)),LinkGeomSource.NormalLinkInterface,1,NoTermination,310124236)),
toProjectLink(rap,LinkStatus.Transfer)(RoadAddress(109708,432302,40921,1,AdministrativeClass.Municipality,Track.LeftSide,Continuous,4234,4250,Some(DateTime.parse("1901-01-01")),None,Option("tester"),145812,0.000,16.832,SideCode.TowardsDigitizing,1533770939000L,(None,None),Seq(Point(373928.515,6672041.08,7.524000000004889),Point(373929.404,6672041.358,7.5200000000040745),Point(373944.669,6672045.81,7.474001195258813)),LinkGeomSource.NormalLinkInterface,1,NoTermination,310124237)),
toProjectLink(rap,LinkStatus.Transfer)(RoadAddress(109776,435967,40921,1,AdministrativeClass.Municipality,Track.RightSide,Continuous,4239,4255,Some(DateTime.parse("1901-01-01")),None,Option("tester"),145816,0.000,15.536,SideCode.TowardsDigitizing,1533770939000L,(None,None),Seq(Point(373932.994,6672029.291,7.186000000001513),Point(373948.058,6672033.091,7.085000000006403)),LinkGeomSource.NormalLinkInterface,1,NoTermination,310124236)),
toProjectLink(rap,LinkStatus.Transfer)(RoadAddress(109709,432303,40921,1,AdministrativeClass.Municipality,Track.LeftSide,Continuous,4250,4454,Some(DateTime.parse("1901-01-01")),None,Option("tester"),145789,0.000,204.770,SideCode.AgainstDigitizing,1533770939000L,(None,None),Seq(Point(374147.329,6672040.178,7.794999999998254),Point(374143.246,6672040.966,7.802999999999884),Point(374119.784,6672045.249,7.737999999997555),Point(374091.021,6672049.082,7.710000000006403),Point(374055.621,6672053.447,7.790999999997439),Point(374030.537,6672055.68,7.75800000000163),Point(374007.284,6672056.103,7.706999999994878),Point(373993.928,6672055.332,7.6710000000020955),Point(373974.061,6672052.79,7.592999999993481),Point(373954.351,6672048.634,7.5019999999931315),Point(373944.669,6672045.81,7.474000000001979)),LinkGeomSource.NormalLinkInterface,1,NoTermination,310124237)),
toProjectLink(rap,LinkStatus.Transfer)(RoadAddress(109777,435968,40921,1,AdministrativeClass.Municipality,Track.RightSide,Continuous,4255,4454,Some(DateTime.parse("1901-01-01")),None,Option("tester"),145790,0.000,200.146,SideCode.AgainstDigitizing,1533770939000L,(None,None),Seq(Point(374146.926,6672027.767,7.563999999998487),Point(374129.921,6672030.779,7.4689999999973224),Point(374096.804,6672034.81,7.419999999998254),Point(374059.242,6672038.386,7.350999999995111),Point(374036.641,6672040.07,7.31600000000617),Point(374001.512,6672040.289,7.210999999995693),Point(373969.446,6672037.153,7.1049999999959255),Point(373948.829,6672033.268,7.072000000000116),Point(373948.058,6672033.091,7.0849972088348885)),LinkGeomSource.NormalLinkInterface,1,NoTermination,310124236)),
toProjectLink(rap,LinkStatus.Transfer)(RoadAddress(109778,435969,40921,1,AdministrativeClass.Municipality,Track.RightSide,MinorDiscontinuity,4454,4469,Some(DateTime.parse("1901-01-01")),None,Option("tester"),145843,0.000,14.661,SideCode.AgainstDigitizing,1533770939000L,(None,None),Seq(Point(374161.519,6672026.352,7.6710000000020955),Point(374146.926,6672027.767,7.564003223867063)),LinkGeomSource.NormalLinkInterface,1,NoTermination,310124236)),
toProjectLink(rap,LinkStatus.Transfer)(RoadAddress(109710,432304,40921,1,AdministrativeClass.Municipality,Track.LeftSide,Continuous,4454,4469,Some(DateTime.parse("1901-01-01")),None,Option("tester"),145842,0.000,14.854,SideCode.AgainstDigitizing,1533770939000L,(None,None),Seq(Point(374161.914,6672037.362,7.790999999997439),Point(374147.329,6672040.178,7.794999902422619)),LinkGeomSource.NormalLinkInterface,1,NoTermination,310124237)),
toProjectLink(rap,LinkStatus.Transfer)(RoadAddress(109863,470085,40921,1,AdministrativeClass.Municipality,Track.Combined,Continuous,4469,4479,Some(DateTime.parse("1901-01-01")),None,Option("tester"),145837,0.000,11.017,SideCode.AgainstDigitizing,1533770939000L,(None,None),Seq(Point(374161.519,6672026.352,7.6710000000020955),Point(374161.914,6672037.362,7.790999092404433)),LinkGeomSource.NormalLinkInterface,1,NoTermination,310124240)),
toProjectLink(rap,LinkStatus.Transfer)(RoadAddress(109864,470086,40921,1,AdministrativeClass.Municipality,Track.Combined,Continuous,4479,4490,Some(DateTime.parse("1901-01-01")),None,Option("tester"),145845,0.000,11.539,SideCode.AgainstDigitizing,1533770939000L,(None,None),Seq(Point(374159.97,6672014.917,7.327999999994063),Point(374161.519,6672026.352,7.670986985157892)),LinkGeomSource.NormalLinkInterface,1,NoTermination,310124240)),
toProjectLink(rap,LinkStatus.Transfer)(RoadAddress(109865,470087,40921,1,AdministrativeClass.Municipality,Track.Combined,Continuous,4490,4551,Some(DateTime.parse("1901-01-01")),None,Option("tester"),145859,0.000,63.502,SideCode.AgainstDigitizing,1533770939000L,(None,None),Seq(Point(374171.281,6671952.875,5.849000000001979),Point(374165.773,6671970.722,6.230999999999767),Point(374161.017,6671989.016,6.728000000002794),Point(374160.391,6672004.163,7.0350000000034925),Point(374159.97,6672014.917,7.327999999994063)),LinkGeomSource.NormalLinkInterface,1,NoTermination,310124240)),
toProjectLink(rap,LinkStatus.Transfer)(RoadAddress(109866,470088,40921,1,AdministrativeClass.Municipality,Track.Combined,Continuous,4551,4625,Some(DateTime.parse("1901-01-01")),None,Option("tester"),145858,0.000,76.757,SideCode.AgainstDigitizing,1533770939000L,(None,None),Seq(Point(374199.807,6671881.62,5.202999999994063),Point(374194.544,6671895.382,5.3439999999973224),Point(374180.252,6671930.015,5.680999999996857),Point(374171.281,6671952.875,5.848997618425553)),LinkGeomSource.NormalLinkInterface,1,NoTermination,310124240)),
toProjectLink(rap,LinkStatus.Transfer)(RoadAddress(109859,467649,40921,1,AdministrativeClass.Municipality,Track.LeftSide,Continuous,4625,4639,Some(DateTime.parse("1901-01-01")),None,Option("tester"),145854,0.000,14.365,SideCode.TowardsDigitizing,1556146816000L,(None,None),Seq(Point(374199.807,6671881.62,5.202999999994063),Point(374212.316,6671888.683,5.091002123483666)),LinkGeomSource.NormalLinkInterface,1,NoTermination,310124244)),
toProjectLink(rap,LinkStatus.Transfer)(RoadAddress(109850,461013,40921,1,AdministrativeClass.Municipality,Track.RightSide,Continuous,4625,4640,Some(DateTime.parse("1901-01-01")),None,Option("tester"),145852,0.000,15.132,SideCode.TowardsDigitizing,1533770939000L,(None,None),Seq(Point(374199.807,6671881.62,5.202999999994063),Point(374214.932,6671882.096,5.050004936672008)),LinkGeomSource.NormalLinkInterface,1,NoTermination,310124243)),
toProjectLink(rap,LinkStatus.Transfer)(RoadAddress(109860,467650,40921,1,AdministrativeClass.Municipality,Track.LeftSide,Continuous,4639,4812,Some(DateTime.parse("1901-01-01")),None,Option("tester"),145849,0.000,177.439,SideCode.TowardsDigitizing,1556146816000L,(None,None),Seq(Point(374212.316,6671888.683,5.091000000000349),Point(374378.111,6671951.902,12.021997219094684)),LinkGeomSource.NormalLinkInterface,1,NoTermination,310124244)),
toProjectLink(rap,LinkStatus.Transfer)(RoadAddress(109851,461014,40921,1,AdministrativeClass.Municipality,Track.RightSide,Continuous,4640,4813,Some(DateTime.parse("1901-01-01")),None,Option("tester"),145851,0.000,175.831,SideCode.TowardsDigitizing,1533770939000L,(None,None),Seq(Point(374214.932,6671882.096,5.05000000000291),Point(374219.253,6671882.55,5.100000000005821),Point(374247.399,6671892.442,5.563999999998487),Point(374289.819,6671909.133,7.612999999997555),Point(374357.808,6671935.369,11.06699999999546),Point(374379.139,6671944.471,12.055987999613624)),LinkGeomSource.NormalLinkInterface,1,NoTermination,310124243)),
toProjectLink(rap,LinkStatus.Transfer)(RoadAddress(109861,467651,40921,1,AdministrativeClass.Municipality,Track.LeftSide,Continuous,4812,4823,Some(DateTime.parse("1901-01-01")),None,Option("tester"),145668,0.000,11.763,SideCode.TowardsDigitizing,1556146816000L,(None,None),Seq(Point(374378.111,6671951.902,12.021999999997206),Point(374389.428,6671955.111,12.433994043507967)),LinkGeomSource.NormalLinkInterface,1,NoTermination,310124244)),
toProjectLink(rap,LinkStatus.Transfer)(RoadAddress(109852,461015,40921,1,AdministrativeClass.Municipality,Track.RightSide,Continuous,4813,4824,Some(DateTime.parse("1901-01-01")),None,Option("tester"),145669,0.000,11.800,SideCode.TowardsDigitizing,1533770939000L,(None,None),Seq(Point(374379.139,6671944.471,12.055999999996857),Point(374390.348,6671948.159,12.415996089546923)),LinkGeomSource.NormalLinkInterface,1,NoTermination,310124243)),
toProjectLink(rap,LinkStatus.Transfer)(RoadAddress(109862,467652,40921,1,AdministrativeClass.Municipality,Track.LeftSide,MinorDiscontinuity,4823,4836,Some(DateTime.parse("1901-01-01")),None,Option("tester"),145677,0.000,14.310,SideCode.TowardsDigitizing,1556146816000L,(None,None),Seq(Point(374389.428,6671955.111,12.43399999999383),Point(374403.081,6671959.396,12.622000000003027)),LinkGeomSource.NormalLinkInterface,1,NoTermination,310124244)),
toProjectLink(rap,LinkStatus.Transfer)(RoadAddress(109853,461016,40921,1,AdministrativeClass.Municipality,Track.RightSide,Continuous,4824,4836,Some(DateTime.parse("1901-01-01")),None,Option("tester"),145679,0.000,12.444,SideCode.TowardsDigitizing,1533770939000L,(None,None),Seq(Point(374390.348,6671948.159,12.415999999997439),Point(374402.301,6671951.621,12.535141663873846)),LinkGeomSource.NormalLinkInterface,1,NoTermination,310124243)),
toProjectLink(rap,LinkStatus.Transfer)(RoadAddress(109740,435228,40921,1,AdministrativeClass.Municipality,Track.RightSide,Continuous,4836,4838,Some(DateTime.parse("1901-01-01")),None,Option("tester"),145679,12.444,14.518,SideCode.TowardsDigitizing,1533770939000L,(None,None),Seq(Point(374402.301,6671951.621,12.535141663873846),Point(374404.293,6671952.198,12.554998607853246)),LinkGeomSource.NormalLinkInterface,1,NoTermination,310124257)),
toProjectLink(rap,LinkStatus.Transfer)(RoadAddress(109854,461818,40921,1,AdministrativeClass.Municipality,Track.LeftSide,Continuous,4836,4843,Some(DateTime.parse("1901-01-01")),None,Option("tester"),145680,0.000,7.013,SideCode.TowardsDigitizing,1533770939000L,(None,None),Seq(Point(374390.348,6671948.159,12.415999999997439),Point(374389.428,6671955.111,12.43399999999383)),LinkGeomSource.NormalLinkInterface,1,NoTermination,310124258)),
toProjectLink(rap,LinkStatus.Transfer)(RoadAddress(109741,435229,40921,1,AdministrativeClass.Municipality,Track.RightSide,Continuous,4838,4845,Some(DateTime.parse("1901-01-01")),None,Option("tester"),145674,0.000,7.299,SideCode.TowardsDigitizing,1533770939000L,(None,None),Seq(Point(374404.293,6671952.198,12.554999999993015),Point(374403.081,6671959.396,12.621997015252047)),LinkGeomSource.NormalLinkInterface,1,NoTermination,310124257)),
toProjectLink(rap,LinkStatus.Transfer)(RoadAddress(109855,461819,40921,1,AdministrativeClass.Municipality,Track.LeftSide,Continuous,4843,4889,Some(DateTime.parse("1901-01-01")),None,Option("tester"),145667,0.000,49.787,SideCode.TowardsDigitizing,1533770939000L,(None,None),Seq(Point(374389.428,6671955.111,12.43399999999383),Point(374388.477,6671965.318,12.63300000000163),Point(374388.103,6671989.095,12.911999999996624),Point(374388.913,6672004.83,13.043999999994412)),LinkGeomSource.NormalLinkInterface,1,NoTermination,310124258)),
toProjectLink(rap,LinkStatus.Transfer)(RoadAddress(109742,435230,40921,1,AdministrativeClass.Municipality,Track.RightSide,Continuous,4845,4893,Some(DateTime.parse("1901-01-01")),None,Option("tester"),145670,0.000,49.138,SideCode.TowardsDigitizing,1533770939000L,(None,None),Seq(Point(374403.081,6671959.396,12.622000000003027),Point(374401.912,6671975.282,12.870999999999185),Point(374401.489,6672008.488,12.668000000005122)),LinkGeomSource.NormalLinkInterface,1,NoTermination,310124257)),
toProjectLink(rap,LinkStatus.Transfer)(RoadAddress(109856,461820,40921,1,AdministrativeClass.Municipality,Track.LeftSide,Continuous,4889,4951,Some(DateTime.parse("1901-01-01")),None,Option("tester"),145609,0.000,66.135,SideCode.TowardsDigitizing,1533770939000L,(None,None),Seq(Point(374388.913,6672004.83,13.043999999994412),Point(374391.283,6672070.923,12.790001838668466)),LinkGeomSource.NormalLinkInterface,1,NoTermination,310124258)),
toProjectLink(rap,LinkStatus.Transfer)(RoadAddress(109743,435231,40921,1,AdministrativeClass.Municipality,Track.RightSide,Continuous,4893,4955,Some(DateTime.parse("1901-01-01")),None,Option("tester"),145607,0.000,63.745,SideCode.TowardsDigitizing,1533770939000L,(None,None),Seq(Point(374401.489,6672008.488,12.668000000005122),Point(374402.299,6672072.228,11.922001714264617)),LinkGeomSource.NormalLinkInterface,1,NoTermination,310124257)),
toProjectLink(rap,LinkStatus.Transfer)(RoadAddress(109857,461821,40921,1,AdministrativeClass.Municipality,Track.LeftSide,Continuous,4951,4993,Some(DateTime.parse("1901-01-01")),None,Option("tester"),145647,0.000,45.135,SideCode.TowardsDigitizing,1533770939000L,(None,None),Seq(Point(374391.283,6672070.923,12.789999999993597),Point(374391.84,6672111.972,12.459000000002561),Point(374391.277,6672116.015,12.494000000006054)),LinkGeomSource.NormalLinkInterface,1,NoTermination,310124258)),
toProjectLink(rap,LinkStatus.Transfer)(RoadAddress(109744,435232,40921,1,AdministrativeClass.Municipality,Track.RightSide,Continuous,4955,4994,Some(DateTime.parse("1901-01-01")),None,Option("tester"),145649,0.000,40.878,SideCode.TowardsDigitizing,1533770939000L,(None,None),Seq(Point(374402.299,6672072.228,11.922000000005937),Point(374402.915,6672112.15,12.305999999996857),Point(374402.772,6672113.09,12.30899999999383)),LinkGeomSource.NormalLinkInterface,1,NoTermination,310124257)),
toProjectLink(rap,LinkStatus.Transfer)(RoadAddress(109858,461822,40921,1,AdministrativeClass.Municipality,Track.LeftSide,Continuous,4993,5001,Some(DateTime.parse("1901-01-01")),None,Option("tester"),145627,0.000,8.156,SideCode.TowardsDigitizing,1533770939000L,(None,None),Seq(Point(374391.277,6672116.015,12.494000000006054),Point(374398.854,6672119.032,12.436546539907896)),LinkGeomSource.NormalLinkInterface,1,NoTermination,310124258)),
toProjectLink(rap,LinkStatus.Transfer)(RoadAddress(109745,435233,40921,1,AdministrativeClass.Municipality,Track.RightSide,MinorDiscontinuity,4994,5001,Some(DateTime.parse("1901-01-01")),None,Option("tester"),145630,0.000,7.155,SideCode.TowardsDigitizing,1533770939000L,(None,None),Seq(Point(374402.772,6672113.09,12.30899999999383),Point(374401.696,6672120.164,12.414994589014393)),LinkGeomSource.NormalLinkInterface,1,NoTermination,310124257)),
toProjectLink(rap,LinkStatus.Transfer)(RoadAddress(109845,460878,40921,1,AdministrativeClass.Municipality,Track.LeftSide,Continuous,5001,5004,Some(DateTime.parse("1901-01-01")),None,Option("tester"),145627,8.156,11.215,SideCode.TowardsDigitizing,1533770939000L,(None,None),Seq(Point(374398.854,6672119.032,12.436546539907896),Point(374401.696,6672120.164,12.414999999993597)),LinkGeomSource.NormalLinkInterface,1,NoTermination,310124270)),
toProjectLink(rap,LinkStatus.Transfer)(RoadAddress(109840,458487,40921,1,AdministrativeClass.Municipality,Track.RightSide,Continuous,5001,5012,Some(DateTime.parse("1901-01-01")),None,Option("tester"),145628,0.000,11.861,SideCode.AgainstDigitizing,1533770939000L,(None,None),Seq(Point(374402.772,6672113.09,12.30899999999383),Point(374391.277,6672116.015,12.49399518134029)),LinkGeomSource.NormalLinkInterface,1,NoTermination,310124269)),
toProjectLink(rap,LinkStatus.Transfer)(RoadAddress(109846,460879,40921,1,AdministrativeClass.Municipality,Track.LeftSide,Continuous,5004,5011,Some(DateTime.parse("1901-01-01")),None,Option("tester"),145629,0.000,7.895,SideCode.TowardsDigitizing,1533770939000L,(None,None),Seq(Point(374401.696,6672120.164,12.414999999993597),Point(374409.563,6672120.826,12.110000000000582)),LinkGeomSource.NormalLinkInterface,1,NoTermination,310124270)),
toProjectLink(rap,LinkStatus.Transfer)(RoadAddress(109847,460880,40921,1,AdministrativeClass.Municipality,Track.LeftSide,Continuous,5011,5107,Some(DateTime.parse("1901-01-01")),None,Option("tester"),145622,0.000,103.934,SideCode.AgainstDigitizing,1533770939000L,(None,None),Seq(Point(374506.404,6672104.05,8.375),Point(374499.757,6672111.152,8.551999999996042),Point(374491.332,6672117.738,8.857999999992899),Point(374482.405,6672122.031,9.188999999998487),Point(374473.484,6672125.052,9.49199999999837),Point(374464.826,6672125.782,9.798999999999069),Point(374452.609,6672125.226,10.269000000000233),Point(374434.289,6672123.372,11.123999999996158),Point(374409.563,6672120.826,12.110000000000582)),LinkGeomSource.NormalLinkInterface,1,NoTermination,310124270)),
toProjectLink(rap,LinkStatus.Transfer)(RoadAddress(109841,458488,40921,1,AdministrativeClass.Municipality,Track.RightSide,Continuous,5012,5019,Some(DateTime.parse("1901-01-01")),None,Option("tester"),145632,0.000,7.222,SideCode.AgainstDigitizing,1533770939000L,(None,None),Seq(Point(374409.994,6672113.036,12.070999999996275),Point(374402.772,6672113.09,12.308993347242131)),LinkGeomSource.NormalLinkInterface,1,NoTermination,310124269)),
toProjectLink(rap,LinkStatus.Transfer)(RoadAddress(109842,458489,40921,1,AdministrativeClass.Municipality,Track.RightSide,Continuous,5019,5110,Some(DateTime.parse("1901-01-01")),None,Option("tester"),145623,0.000,92.946,SideCode.AgainstDigitizing,1533770939000L,(None,None),Seq(Point(374497.122,6672098.534,8.103000000002794),Point(374490.222,6672105.483,8.279999999998836),Point(374482.818,6672111.056,8.595000000001164),Point(374475.423,6672114.336,9.03200000000652),Point(374467.015,6672116.34,9.547999999995227),Point(374458.617,6672115.799,9.967000000004191),Point(374447.416,6672115.755,10.592000000004191),Point(374436.984,6672114.696,11.089000000007218),Point(374421.967,6672114.128,11.688999999998487),Point(374409.994,6672113.036,12.070999999996275)),LinkGeomSource.NormalLinkInterface,1,NoTermination,310124269)),
toProjectLink(rap,LinkStatus.Transfer)(RoadAddress(109848,460881,40921,1,AdministrativeClass.Municipality,Track.LeftSide,Continuous,5107,5117,Some(DateTime.parse("1901-01-01")),None,Option("tester"),11114740,0.000,11.062,SideCode.AgainstDigitizing,1543649361000L,(None,None),Seq(Point(374511.966,6672094.488,8.46799999999348),Point(374506.404,6672104.05,8.375)),LinkGeomSource.NormalLinkInterface,1,NoTermination,310124270)),
toProjectLink(rap,LinkStatus.Transfer)(RoadAddress(109843,458490,40921,1,AdministrativeClass.Municipality,Track.RightSide,Continuous,5110,5121,Some(DateTime.parse("1901-01-01")),None,Option("tester"),11114743,0.000,10.970,SideCode.AgainstDigitizing,1543649361000L,(None,None),Seq(Point(374504.592,6672090.501,8.298999999999069),Point(374497.122,6672098.534,8.103000000002794)),LinkGeomSource.NormalLinkInterface,1,NoTermination,310124269)),
toProjectLink(rap,LinkStatus.Transfer)(RoadAddress(109849,460882,40921,1,AdministrativeClass.Municipality,Track.LeftSide,MinorDiscontinuity,5117,5129,Some(DateTime.parse("1901-01-01")),None,Option("tester"),11114744,0.000,12.092,SideCode.AgainstDigitizing,1543649361000L,(None,None),Seq(Point(374513.374,6672082.478,8.164999999993597),Point(374511.966,6672094.488,8.467993679773139)),LinkGeomSource.NormalLinkInterface,1,NoTermination,310124270)),
toProjectLink(rap,LinkStatus.Transfer)(RoadAddress(109844,458491,40921,1,AdministrativeClass.Municipality,Track.RightSide,Continuous,5121,5129,Some(DateTime.parse("1901-01-01")),None,Option("tester"),11114741,3.965,11.895,SideCode.AgainstDigitizing,1543649361000L,(None,None),Seq(Point(374510.447,6672085.152,8.209666504400488),Point(374504.592,6672090.501,8.298999513214268)),LinkGeomSource.NormalLinkInterface,1,NoTermination,310124269)),
toProjectLink(rap,LinkStatus.Transfer)(RoadAddress(109746,435369,40921,1,AdministrativeClass.Municipality,Track.RightSide,Continuous,5129,5133,Some(DateTime.parse("1901-01-01")),None,Option("tester"),11114741,0.000,3.965,SideCode.AgainstDigitizing,1543649361000L,(None,None),Seq(Point(374513.374,6672082.478,8.164999999993597),Point(374510.447,6672085.152,8.209666504400488)),LinkGeomSource.NormalLinkInterface,1,NoTermination,310124279)),
toProjectLink(rap,LinkStatus.Transfer)(RoadAddress(109733,434506,40921,1,AdministrativeClass.Municipality,Track.LeftSide,Continuous,5129,5137,Some(DateTime.parse("1901-01-01")),None,Option("tester"),11114742,0.000,8.383,SideCode.TowardsDigitizing,1543649361000L,(None,None),Seq(Point(374504.592,6672090.501,8.298999999999069),Point(374511.966,6672094.488,8.46799999999348)),LinkGeomSource.NormalLinkInterface,1,NoTermination,310124280)),
toProjectLink(rap,LinkStatus.Transfer)(RoadAddress(109747,435370,40921,1,AdministrativeClass.Municipality,Track.RightSide,Continuous,5133,5217,Some(DateTime.parse("1901-01-01")),None,Option("tester"),145611,0.000,85.746,SideCode.TowardsDigitizing,1533770939000L,(None,None),Seq(Point(374513.374,6672082.478,8.164999999993597),Point(374534.634,6672092.34,8.963000000003376),Point(374545.642,6672098.914,9.331999999994878),Point(374560.789,6672109.245,9.94999999999709),Point(374585.244,6672128.545,10.22599999999511)),LinkGeomSource.NormalLinkInterface,1,NoTermination,310124279)),
toProjectLink(rap,LinkStatus.Transfer)(RoadAddress(109734,434507,40921,1,AdministrativeClass.Municipality,Track.LeftSide,Continuous,5137,5249,Some(DateTime.parse("1901-01-01")),None,Option("tester"),11114736,0.000,113.774,SideCode.TowardsDigitizing,1543649361000L,(None,None),Seq(Point(374511.966,6672094.488,8.46799999999348),Point(374514.005,6672095.204,8.53200000000652),Point(374542.43,6672113.719,9.452999999994063),Point(374576.7,6672138.292,11.017000000007101),Point(374603.223,6672158.252,12.038000000000466),Point(374604.963,6672159.795,12.098865084283974)),LinkGeomSource.NormalLinkInterface,1,NoTermination,310124280)),
toProjectLink(rap,LinkStatus.Transfer)(RoadAddress(109748,435371,40921,1,AdministrativeClass.Municipality,Track.RightSide,Continuous,5217,5230,Some(DateTime.parse("1901-01-01")),None,Option("tester"),145657,0.000,13.343,SideCode.TowardsDigitizing,1533770939000L,(None,None),Seq(Point(374585.244,6672128.545,10.22599999999511),Point(374595.68,6672136.859,10.365999999994528)),LinkGeomSource.NormalLinkInterface,1,NoTermination,310124279)),
toProjectLink(rap,LinkStatus.Transfer)(RoadAddress(109749,435372,40921,1,AdministrativeClass.Municipality,Track.RightSide,Continuous,5230,5249,Some(DateTime.parse("1901-01-01")),None,Option("tester"),145653,0.000,19.259,SideCode.TowardsDigitizing,1533770939000L,(None,None),Seq(Point(374595.68,6672136.859,10.365999999994528),Point(374596.825,6672137.77,11.49199999999837),Point(374610.602,6672149.034,11.974244816149124)),LinkGeomSource.NormalLinkInterface,1,NoTermination,310124279)),
toProjectLink(rap,LinkStatus.Transfer)(RoadAddress(109809,443624,40921,1,AdministrativeClass.Municipality,Track.LeftSide,Continuous,5249,5315,Some(DateTime.parse("1901-01-01")),None,Option("tester"),11114736,113.774,181.835,SideCode.TowardsDigitizing,1543649361000L,(None,None),Seq(Point(374604.963,6672159.795,12.098865084283974),Point(374627.577,6672179.858,12.889999999999418),Point(374655.326,6672205.57,13.410999999992782)),LinkGeomSource.NormalLinkInterface,1,NoTermination,310124288)),
toProjectLink(rap,LinkStatus.Transfer)(RoadAddress(109711,433715,40921,1,AdministrativeClass.Municipality,Track.RightSide,Continuous,5249,5318,Some(DateTime.parse("1901-01-01")),None,Option("tester"),145653,19.259,90.212,SideCode.TowardsDigitizing,1533770939000L,(None,None),Seq(Point(374610.602,6672149.034,11.974244816149124),Point(374629.051,6672164.117,12.619999999995343),Point(374662.531,6672195.305,13.437999999994645),Point(374663.513,6672196.256,11.982000000003609)),LinkGeomSource.NormalLinkInterface,1,NoTermination,310124287)),
toProjectLink(rap,LinkStatus.Transfer)(RoadAddress(109810,443625,40921,1,AdministrativeClass.Municipality,Track.LeftSide,Continuous,5315,5429,Some(DateTime.parse("1901-01-01")),None,Option("tester"),145507,0.000,116.199,SideCode.TowardsDigitizing,1533770939000L,(None,None),Seq(Point(374655.326,6672205.57,13.410999999992782),Point(374655.858,6672206.063,13.529999999998836),Point(374688.955,6672237.632,14.085999999995693),Point(374716.986,6672262.817,14.101999999998952),Point(374740.651,6672284.434,13.919999999998254)),LinkGeomSource.NormalLinkInterface,1,NoTermination,310124288)),
toProjectLink(rap,LinkStatus.Transfer)(RoadAddress(109712,433716,40921,1,AdministrativeClass.Municipality,Track.RightSide,Continuous,5318,5433,Some(DateTime.parse("1901-01-01")),None,Option("tester"),145506,0.000,116.576,SideCode.TowardsDigitizing,1533770939000L,(None,None),Seq(Point(374663.513,6672196.256,11.982000000003609),Point(374685.863,6672217.926,13.90700000000652),Point(374716.93,6672247.706,14.130000000004657),Point(374744.834,6672272.89,14.013000000006286),Point(374748.318,6672276.224,13.948999999993248)),LinkGeomSource.NormalLinkInterface,1,NoTermination,310124287)),
toProjectLink(rap,LinkStatus.Transfer)(RoadAddress(109811,443626,40921,1,AdministrativeClass.Municipality,Track.LeftSide,Continuous,5429,5442,Some(DateTime.parse("1901-01-01")),None,Option("tester"),145556,0.000,13.212,SideCode.TowardsDigitizing,1533770939000L,(None,None),Seq(Point(374740.651,6672284.434,13.919999999998254),Point(374750.403,6672293.348,13.803001325767307)),LinkGeomSource.NormalLinkInterface,1,NoTermination,310124288)),
toProjectLink(rap,LinkStatus.Transfer)(RoadAddress(109713,433717,40921,1,AdministrativeClass.Municipality,Track.RightSide,Continuous,5433,5445,Some(DateTime.parse("1901-01-01")),None,Option("tester"),145555,0.000,12.631,SideCode.TowardsDigitizing,1533770939000L,(None,None),Seq(Point(374748.318,6672276.224,13.948999999993248),Point(374757.408,6672284.994,13.91700000000128)),LinkGeomSource.NormalLinkInterface,1,NoTermination,310124287)),
toProjectLink(rap,LinkStatus.Transfer)(RoadAddress(109812,443627,40921,1,AdministrativeClass.Municipality,Track.LeftSide,Continuous,5442,5459,Some(DateTime.parse("1901-01-01")),None,Option("tester"),145546,0.000,17.732,SideCode.TowardsDigitizing,1533770939000L,(None,None),Seq(Point(374750.403,6672293.348,13.802999999999884),Point(374752.244,6672295.031,13.793000000005122),Point(374763.574,6672305.22,13.635999999998603)),LinkGeomSource.NormalLinkInterface,1,NoTermination,310124288)),
toProjectLink(rap,LinkStatus.Transfer)(RoadAddress(109714,433718,40921,1,AdministrativeClass.Municipality,Track.RightSide,Continuous,5445,5463,Some(DateTime.parse("1901-01-01")),None,Option("tester"),145553,0.000,18.692,SideCode.TowardsDigitizing,1533770939000L,(None,None),Seq(Point(374757.408,6672284.994,13.91700000000128),Point(374771.295,6672297.505,13.709000000002561)),LinkGeomSource.NormalLinkInterface,1,NoTermination,310124287)),
toProjectLink(rap,LinkStatus.Transfer)(RoadAddress(109813,443628,40921,1,AdministrativeClass.Municipality,Track.LeftSide,Continuous,5459,5669,Some(DateTime.parse("1901-01-01")),None,Option("tester"),145535,0.000,215.127,SideCode.TowardsDigitizing,1533749925000L,(None,None),Seq(Point(374763.574,6672305.22,13.635999999998603),Point(374776.85,6672317.147,13.369000000006054),Point(374804.372,6672342.203,12.760999999998603),Point(374828.601,6672363.173,11.849000000001979),Point(374857.417,6672383.015,10.811000000001513),Point(374892.088,6672402.625,9.525999999998021),Point(374927.03,6672417.78,8.442999999999302),Point(374941.446,6672422.312,8.002446431467781)),LinkGeomSource.NormalLinkInterface,1,NoTermination,310124288)),
toProjectLink(rap,LinkStatus.Transfer)(RoadAddress(109715,433719,40921,1,AdministrativeClass.Municipality,Track.RightSide,Continuous,5463,5669,Some(DateTime.parse("1901-01-01")),None,Option("tester"),145533,0.000,209.972,SideCode.TowardsDigitizing,1533749925000L,(None,None),Seq(Point(374771.295,6672297.505,13.709000000002561),Point(374774.151,6672300.079,13.668000000005122),Point(374801.904,6672325.683,13.039000000004307),Point(374837.298,6672355.733,11.786999999996624),Point(374869.035,6672377.114,10.601999999998952),Point(374900.534,6672394.039,9.453999999997905),Point(374931.537,6672407.906,8.342000000004191),Point(374944.849,6672412.074,7.95354008401753)),LinkGeomSource.NormalLinkInterface,1,NoTermination,310124287)),
toProjectLink(rap,LinkStatus.Transfer)(RoadAddress(109754,435568,40921,1,AdministrativeClass.Municipality,Track.RightSide,Continuous,5669,5760,Some(DateTime.parse("1901-01-01")),None,Option("tester"),145533,209.972,302.279,SideCode.TowardsDigitizing,1533749925000L,(None,None),Seq(Point(374944.849,6672412.074,7.95354008401753),Point(374966.114,6672418.733,7.332999999998719),Point(374994.473,6672424.826,6.657000000006519),Point(375015.588,6672428.344,6.51600000000326),Point(375035.117,6672430.143,6.437000295201901)),LinkGeomSource.NormalLinkInterface,1,NoTermination,310124293)),
toProjectLink(rap,LinkStatus.Transfer)(RoadAddress(109716,433858,40921,1,AdministrativeClass.Municipality,Track.LeftSide,Continuous,5669,5761,Some(DateTime.parse("1901-01-01")),None,Option("tester"),145535,215.127,309.946,SideCode.TowardsDigitizing,1533749925000L,(None,None),Seq(Point(374941.446,6672422.312,8.002446431467781),Point(374963.515,6672429.251,7.327999999994063),Point(375001.538,6672437.673,6.36699999999837),Point(375034.054,6672441.499,6.486999414300687)),LinkGeomSource.NormalLinkInterface,1,NoTermination,310124294)),
toProjectLink(rap,LinkStatus.Transfer)(RoadAddress(109755,435569,40921,1,AdministrativeClass.Municipality,Track.RightSide,Continuous,5760,5779,Some(DateTime.parse("1901-01-01")),None,Option("tester"),144575,0.000,19.779,SideCode.TowardsDigitizing,1533749925000L,(None,None),Seq(Point(375035.117,6672430.143,6.437000000005355),Point(375054.876,6672431.039,6.30100209553433)),LinkGeomSource.NormalLinkInterface,1,NoTermination,310124293)),
toProjectLink(rap,LinkStatus.Transfer)(RoadAddress(109717,433859,40921,1,AdministrativeClass.Municipality,Track.LeftSide,MinorDiscontinuity,5761,5779,Some(DateTime.parse("1901-01-01")),None,Option("tester"),144569,0.000,18.565,SideCode.TowardsDigitizing,1533749925000L,(None,None),Seq(Point(375034.054,6672441.499,6.486999999993714),Point(375051.592,6672442.809,6.305999999996857),Point(375052.461,6672443.259,6.305000468714075)),LinkGeomSource.NormalLinkInterface,1,NoTermination,310124294)),
toProjectLink(rap,LinkStatus.Transfer)(RoadAddress(109794,442717,40921,1,AdministrativeClass.Municipality,Track.Combined,Continuous,5779,5787,Some(DateTime.parse("1901-01-01")),None,Option("tester"),144572,0.000,8.014,SideCode.TowardsDigitizing,1554332423000L,(None,None),Seq(Point(375054.876,6672431.039,6.301000000006752),Point(375053.012,6672438.833,6.389999999999418)),LinkGeomSource.NormalLinkInterface,1,NoTermination,310124297)),
toProjectLink(rap,LinkStatus.Transfer)(RoadAddress(109795,442718,40921,1,AdministrativeClass.Municipality,Track.Combined,Continuous,5787,5791,Some(DateTime.parse("1901-01-01")),None,Option("tester"),144571,0.000,4.460,SideCode.TowardsDigitizing,1533749925000L,(None,None),Seq(Point(375053.012,6672438.833,6.389999999999418),Point(375052.461,6672443.259,6.305003155545862)),LinkGeomSource.NormalLinkInterface,1,NoTermination,310124297)),
toProjectLink(rap,LinkStatus.Transfer)(RoadAddress(109796,442719,40921,1,AdministrativeClass.Municipality,Track.Combined,Continuous,5791,5804,Some(DateTime.parse("1901-01-01")),None,Option("tester"),144567,0.000,12.418,SideCode.TowardsDigitizing,1533749925000L,(None,None),Seq(Point(375052.461,6672443.259,6.304999999993015),Point(375049.879,6672455.406,6.135005296317026)),LinkGeomSource.NormalLinkInterface,1,NoTermination,310124297)),
toProjectLink(rap,LinkStatus.Transfer)(RoadAddress(109797,442720,40921,1,AdministrativeClass.Municipality,Track.Combined,Continuous,5804,5954,Some(DateTime.parse("1901-01-01")),None,Option("tester"),144441,0.000,142.612,SideCode.TowardsDigitizing,1533749925000L,(None,None),Seq(Point(375049.879,6672455.406,6.134999999994761),Point(375047.389,6672463.393,6.0789999999979045),Point(375041.689,6672481.29,6.0090000000054715),Point(375036.273,6672499.217,5.9210000000020955),Point(375027.124,6672527.821,6.584000000002561),Point(375020.43,6672547.398,6.710000000006403),Point(375012.397,6672567.115,6.673999999999069),Point(375001.306,6672589.212,6.548001778916966)),LinkGeomSource.NormalLinkInterface,1,NoTermination,310124297)),
toProjectLink(rap,LinkStatus.Transfer)(RoadAddress(109798,442721,40921,1,AdministrativeClass.Municipality,Track.Combined,Continuous,5954,5963,Some(DateTime.parse("1901-01-01")),None,Option("tester"),144468,0.000,8.374,SideCode.TowardsDigitizing,1533749925000L,(None,None),Seq(Point(375001.306,6672589.212,6.547999999995227),Point(374997.529,6672596.041,6.429000000003725),Point(374997.258,6672596.543,6.415009453707763)),LinkGeomSource.NormalLinkInterface,1,NoTermination,310124297)),
toProjectLink(rap,LinkStatus.Transfer)(RoadAddress(109799,442722,40921,1,AdministrativeClass.Municipality,Track.Combined,Continuous,5963,6082,Some(DateTime.parse("1901-01-01")),None,Option("tester"),144465,0.000,113.448,SideCode.TowardsDigitizing,1533770939000L,(None,None),Seq(Point(374997.258,6672596.543,6.414999999993597),Point(374986.487,6672620.547,6.387000000002445),Point(374973.784,6672646.719,6.783999999999651),Point(374962.869,6672671.498,7.677999999999884),Point(374950.911,6672700.066,8.964000000007218)),LinkGeomSource.NormalLinkInterface,1,NoTermination,310124297)),
toProjectLink(rap,LinkStatus.Transfer)(RoadAddress(109800,442723,40921,1,AdministrativeClass.Municipality,Track.Combined,Continuous,6082,6104,Some(DateTime.parse("1901-01-01")),None,Option("tester"),144451,0.000,21.334,SideCode.TowardsDigitizing,1533770939000L,(None,None),Seq(Point(374950.911,6672700.066,8.964000000007218),Point(374943.144,6672719.936,9.92699615660005)),LinkGeomSource.NormalLinkInterface,1,NoTermination,310124297)),
toProjectLink(rap,LinkStatus.Transfer)(RoadAddress(109801,442724,40921,1,AdministrativeClass.Municipality,Track.Combined,Continuous,6104,6120,Some(DateTime.parse("1901-01-01")),None,Option("tester"),144452,0.000,15.145,SideCode.TowardsDigitizing,1533770939000L,(None,None),Seq(Point(374943.144,6672719.936,9.926999999996042),Point(374938.245,6672734.267,10.460992157936577)),LinkGeomSource.NormalLinkInterface,1,NoTermination,310124297)),
toProjectLink(rap,LinkStatus.Transfer)(RoadAddress(109802,442725,40921,1,AdministrativeClass.Municipality,Track.Combined,Continuous,6120,6212,Some(DateTime.parse("1901-01-01")),None,Option("tester"),144447,0.000,86.833,SideCode.TowardsDigitizing,1533770939000L,(None,None),Seq(Point(374938.245,6672734.267,10.460999999995693),Point(374934.424,6672745.711,10.735000000000582),Point(374927.497,6672764.093,11.048999999999069),Point(374916.195,6672798.161,11.27400000000489),Point(374910.212,6672816.437,11.459999129872692)),LinkGeomSource.NormalLinkInterface,1,NoTermination,310124297)),
toProjectLink(rap,LinkStatus.Transfer)(RoadAddress(109803,442726,40921,1,AdministrativeClass.Municipality,Track.Combined,Continuous,6212,6510,Some(DateTime.parse("1901-01-01")),None,Option("tester"),145145,0.000,283.478,SideCode.TowardsDigitizing,1533770939000L,(None,None),Seq(Point(374910.212,6672816.437,11.460000000006403),Point(374904.103,6672831.09,11.793000000005122),Point(374894.835,6672857.912,12.755000000004657),Point(374885.058,6672884.732,14.165999999997439),Point(374874.686,6672912.971,15.95799999999872),Point(374856.917,6672954.96,18.589999999996508),Point(374831.583,6673004.595,21.452999999994063),Point(374801.474,6673056.274,22.887000000002445),Point(374791.847,6673073.19,23.014999999999418)),LinkGeomSource.NormalLinkInterface,1,NoTermination,310124297)),
toProjectLink(rap,LinkStatus.Transfer)(RoadAddress(109804,442727,40921,1,AdministrativeClass.Municipality,Track.Combined,Continuous,6510,6610,Some(DateTime.parse("1901-01-01")),None,Option("tester"),145081,0.000,95.389,SideCode.TowardsDigitizing,1533770939000L,(None,None),Seq(Point(374791.847,6673073.19,23.014999999999418),Point(374782.855,6673091.231,22.812999999994645),Point(374773.569,6673114.507,22.37399999999616),Point(374763.585,6673143.587,21.527000000001863),Point(374757.33,6673161.978,21.084010339372146)),LinkGeomSource.NormalLinkInterface,1,NoTermination,310124297)),
toProjectLink(rap,LinkStatus.Transfer)(RoadAddress(109805,442728,40921,1,AdministrativeClass.Municipality,Track.Combined,Continuous,6610,6778,Some(DateTime.parse("1901-01-01")),None,Option("tester"),145119,0.000,160.082,SideCode.TowardsDigitizing,1533770939000L,(None,None),Seq(Point(374757.33,6673161.978,21.08400000000256),Point(374751.836,6673183.346,20.71600000000035),Point(374747.923,6673207.771,20.562000000005355),Point(374746.797,6673235.261,20.45100000000093),Point(374747.839,6673278.776,20.380000000004657),Point(374747.709,6673302.357,20.187000000005355),Point(374746.148,6673320.952,20.22400000000198)),LinkGeomSource.NormalLinkInterface,1,NoTermination,310124297)),
toProjectLink(rap,LinkStatus.Transfer)(RoadAddress(109806,442729,40921,1,AdministrativeClass.Municipality,Track.Combined,Continuous,6778,6828,Some(DateTime.parse("1901-01-01")),None,Option("tester"),145132,0.000,47.741,SideCode.TowardsDigitizing,1533770939000L,(None,None),Seq(Point(374746.148,6673320.952,20.22400000000198),Point(374740.179,6673344.117,20.085999999995693),Point(374731.962,6673359.293,19.631999999997788),Point(374727.892,6673364.44,19.040015098758865)),LinkGeomSource.NormalLinkInterface,1,NoTermination,310124297)),
toProjectLink(rap,LinkStatus.Transfer)(RoadAddress(109807,442730,40921,1,AdministrativeClass.Municipality,Track.Combined,Continuous,6828,6844,Some(DateTime.parse("1901-01-01")),None,Option("tester"),145136,0.000,15.058,SideCode.TowardsDigitizing,1533770939000L,(None,None),Seq(Point(374727.892,6673364.44,19.039999999993597),Point(374722.833,6673369.651,18.135999999998603),Point(374716.694,6673374.455,17.16600249756526)),LinkGeomSource.NormalLinkInterface,1,NoTermination,310124297)),
toProjectLink(rap,LinkStatus.Transfer)(RoadAddress(109808,442731,40921,1,AdministrativeClass.Municipality,Track.Combined,MinorDiscontinuity,6844,6777,Some(DateTime.parse("1901-01-01")),None,Option("tester"),145101,0.000,5.666,SideCode.TowardsDigitizing,1533770939000L,(None,None),Seq(Point(374716.694,6673374.455,17.16599999999744),Point(374712.245,6673377.964,18.634928485254022)),LinkGeomSource.NormalLinkInterface,1,NoTermination,310124297)),
toProjectLink(rap,LinkStatus.Transfer)(RoadAddress(109759,435882,40921,1,AdministrativeClass.Municipality,Track.RightSide,Continuous,6777,6804,Some(DateTime.parse("1901-01-01")),None,Option("tester"),145121,0.000,26.638,SideCode.TowardsDigitizing,1533770939000L,(None,None),Seq(Point(374713.379,6673391.764,17.94100000000617),Point(374727.811,6673414.154,16.740009884322465)),LinkGeomSource.NormalLinkInterface,1,NoTermination,310124300)),
toProjectLink(rap,LinkStatus.Transfer)(RoadAddress(109783,442171,40921,1,AdministrativeClass.Municipality,Track.LeftSide,Continuous,6777,6877,Some(DateTime.parse("1901-01-01")),None,Option("tester"),145093,0.000,27.411,SideCode.TowardsDigitizing,1533770939000L,(None,None),Seq(Point(374702.261,6673396.254,17.812999999994645),Point(374720.042,6673417.115,16.6929999999993)),LinkGeomSource.NormalLinkInterface,1,NoTermination,310124301)),
toProjectLink(rap,LinkStatus.Transfer)(RoadAddress(109760,435883,40921,1,AdministrativeClass.Municipality,Track.RightSide,Continuous,6804,6826,Some(DateTime.parse("1901-01-01")),None,Option("tester"),145129,0.000,20.733,SideCode.TowardsDigitizing,1533770939000L,(None,None),Seq(Point(374727.811,6673414.154,16.74000000000524),Point(374737.986,6673432.218,15.948999999993248)),LinkGeomSource.NormalLinkInterface,1,NoTermination,310124300)),
toProjectLink(rap,LinkStatus.Transfer)(RoadAddress(109784,442172,40921,1,AdministrativeClass.Municipality,Track.LeftSide,Continuous,6877,7175,Some(DateTime.parse("1901-01-01")),None,Option("tester"),145128,0.000,22.510,SideCode.TowardsDigitizing,1533770939000L,(None,None),Seq(Point(374720.042,6673417.115,16.6929999999993),Point(374731.819,6673436.298,15.703999999997905)),LinkGeomSource.NormalLinkInterface,1,NoTermination,310124301)),
toProjectLink(rap,LinkStatus.Transfer)(RoadAddress(109761,435884,40921,1,AdministrativeClass.Municipality,Track.RightSide,Continuous,6826,6871,Some(DateTime.parse("1901-01-01")),None,Option("tester"),145065,0.000,44.449,SideCode.TowardsDigitizing,1533770939000L,(None,None),Seq(Point(374737.986,6673432.218,15.948999999993248),Point(374738.559,6673433.243,15.985000000000582),Point(374756.083,6673472.811,14.078008433456297)),LinkGeomSource.NormalLinkInterface,1,NoTermination,310124300)),
toProjectLink(rap,LinkStatus.Transfer)(RoadAddress(109785,442173,40921,1,AdministrativeClass.Municipality,Track.LeftSide,Continuous,7175,7267,Some(DateTime.parse("1901-01-01")),None,Option("tester"),145064,0.000,42.310,SideCode.TowardsDigitizing,1533770939000L,(None,None),Seq(Point(374731.819,6673436.298,15.703999999997905),Point(374738.216,6673454.834,14.898000000001048),Point(374745.572,6673476.31,13.96799999999348)),LinkGeomSource.NormalLinkInterface,1,NoTermination,310124301)),
toProjectLink(rap,LinkStatus.Transfer)(RoadAddress(109786,442174,40921,1,AdministrativeClass.Municipality,Track.LeftSide,Continuous,7267,7283,Some(DateTime.parse("1901-01-01")),None,Option("tester"),11939985,0.000,199.479,SideCode.TowardsDigitizing,1599778815000L,(None,None),Seq(Point(374745.572,6673476.31,13.96799999999348),Point(374754.321,6673495.793,13.080000000001746),Point(374766.718,6673523.717,11.854999999995925),Point(374778.038,6673547.535,10.90700000000652),Point(374787.199,6673569.522,10.172000000005937),Point(374799.891,6673597.432,9.554000000003725),Point(374811.077,6673618.227,9.315000000002328),Point(374832.069,6673654.99,9.240000000005239),Point(374832.516,6673655.62,9.237001583064394)),LinkGeomSource.NormalLinkInterface,1,NoTermination,310124301)),
toProjectLink(rap,LinkStatus.Transfer)(RoadAddress(109762,435885,40921,1,AdministrativeClass.Municipality,Track.RightSide,Continuous,6871,7062,Some(DateTime.parse("1901-01-01")),None,Option("tester"),11939986,0.000,186.259,SideCode.TowardsDigitizing,1599778815000L,(None,None),Seq(Point(374756.083,6673472.811,14.077999999994063),Point(374767.043,6673497.21,12.971999999994296),Point(374777.791,6673522.621,11.865999999994528),Point(374789.725,6673547.739,10.820000000006985),Point(374800.701,6673572.901,10.023000000001048),Point(374810.48,6673592.84,9.536999999996624),Point(374821.022,6673613.705,9.237999999997555),Point(374833.314,6673636.169,9.164999999993597),Point(374836.282,6673640.762,9.182999021332805)),LinkGeomSource.NormalLinkInterface,1,NoTermination,310124300)),
toProjectLink(rap,LinkStatus.Transfer)(RoadAddress(109763,435886,40921,1,AdministrativeClass.Municipality,Track.RightSide,Continuous,7062,7074,Some(DateTime.parse("1901-01-01")),None,Option("tester"),11939970,0.000,11.167,SideCode.TowardsDigitizing,1599778815000L,(None,None),Seq(Point(374836.282,6673640.762,9.18300000000454),Point(374842.369,6673650.124,9.277000000001863)),LinkGeomSource.NormalLinkInterface,1,NoTermination,310124300)),
toProjectLink(rap,LinkStatus.Transfer)(RoadAddress(109787,442175,40921,1,AdministrativeClass.Municipality,Track.LeftSide,Continuous,7283,7305,Some(DateTime.parse("1901-01-01")),None,Option("tester"),11939960,0.000,170.011,SideCode.TowardsDigitizing,1599778815000L,(None,None),Seq(Point(374832.516,6673655.62,9.236999999993714),Point(374864.244,6673694.703,9.673999999999069),Point(374892.124,6673724.065,10.47500000000582),Point(374918.522,6673743.516,11.194000000003143),Point(374938.331,6673755.558,11.770999999993364),Point(374958.756,6673766.579,12.221999999994296)),LinkGeomSource.NormalLinkInterface,1,NoTermination,310124301)),
toProjectLink(rap,LinkStatus.Transfer)(RoadAddress(109764,435887,40921,1,AdministrativeClass.Municipality,Track.RightSide,Continuous,7074,7247,Some(DateTime.parse("1901-01-01")),None,Option("tester"),11939964,0.000,169.170,SideCode.TowardsDigitizing,1599778815000L,(None,None),Seq(Point(374842.369,6673650.124,9.277000000001863),Point(374850.879,6673662.029,9.364000000001397),Point(374870.258,6673686.417,9.657999999995809),Point(374892.951,6673709.545,10.202999999994063),Point(374922.262,6673732.698,11.080000000001746),Point(374942.448,6673746.015,11.646999999997206),Point(374968.036,6673760.592,12.376999999993131)),LinkGeomSource.NormalLinkInterface,1,NoTermination,310124300)),
toProjectLink(rap,LinkStatus.Transfer)(RoadAddress(109788,442176,40921,1,AdministrativeClass.Municipality,Track.LeftSide,Continuous,7305,7424,Some(DateTime.parse("1901-01-01")),None,Option("tester"),143931,0.000,14.452,SideCode.TowardsDigitizing,1533770939000L,(None,None),Seq(Point(374958.756,6673766.579,12.221999999994296),Point(374971.618,6673773.169,12.430999999996857)),LinkGeomSource.NormalLinkInterface,1,NoTermination,310124301)),
toProjectLink(rap,LinkStatus.Transfer)(RoadAddress(109765,435888,40921,1,AdministrativeClass.Municipality,Track.RightSide,Continuous,7247,7271,Some(DateTime.parse("1901-01-01")),None,Option("tester"),143936,0.000,23.138,SideCode.TowardsDigitizing,1533770939000L,(None,None),Seq(Point(374968.036,6673760.592,12.376999999993131),Point(374989.72,6673768.664,12.630999999993946)),LinkGeomSource.NormalLinkInterface,1,NoTermination,310124300)),
toProjectLink(rap,LinkStatus.Transfer)(RoadAddress(109789,442177,40921,1,AdministrativeClass.Municipality,Track.LeftSide,Continuous,7424,7433,Some(DateTime.parse("1901-01-01")),None,Option("tester"),143935,0.000,13.917,SideCode.TowardsDigitizing,1533770939000L,(None,None),Seq(Point(374971.618,6673773.169,12.430999999996857),Point(374980.269,6673777.455,12.455000000001746),Point(374984.213,6673779.071,12.53200000000652)),LinkGeomSource.NormalLinkInterface,1,NoTermination,310124301)),
toProjectLink(rap,LinkStatus.Transfer)(RoadAddress(109766,435889,40921,1,AdministrativeClass.Municipality,Track.RightSide,Continuous,7271,7309,Some(DateTime.parse("1901-01-01")),None,Option("tester"),143920,0.000,37.174,SideCode.TowardsDigitizing,1533749925000L,(None,None),Seq(Point(374989.72,6673768.664,12.630999999993946),Point(374990.19,6673768.851,12.630999999993946),Point(375015.276,6673777.302,12.61699999999837),Point(375025.153,6673779.836,12.611999999993714)),LinkGeomSource.NormalLinkInterface,1,NoTermination,310124300)),
toProjectLink(rap,LinkStatus.Transfer)(RoadAddress(109790,442178,40921,1,AdministrativeClass.Municipality,Track.LeftSide,Continuous,7433,7583,Some(DateTime.parse("1901-01-01")),None,Option("tester"),143933,0.000,38.542,SideCode.TowardsDigitizing,1599778815000L,(None,None),Seq(Point(374984.213,6673779.071,12.53200000000652),Point(375001.253,6673785.374,11.982000000003609),Point(375021.028,6673790.278,11.36501096271906)),LinkGeomSource.NormalLinkInterface,1,NoTermination,310124301)),
toProjectLink(rap,LinkStatus.Transfer)(RoadAddress(109767,435890,40921,1,AdministrativeClass.Municipality,Track.RightSide,Continuous,7309,7346,Some(DateTime.parse("1901-01-01")),None,Option("tester"),143942,0.000,35.917,SideCode.TowardsDigitizing,1533749925000L,(None,None),Seq(Point(375025.153,6673779.836,12.611999999993714),Point(375035.889,6673782.36,12.688999999998487),Point(375050.839,6673784.982,12.82499999999709),Point(375060.517,6673785.777,12.838999296837926)),LinkGeomSource.NormalLinkInterface,1,NoTermination,310124300)),
toProjectLink(rap,LinkStatus.Transfer)(RoadAddress(109791,442179,40921,1,AdministrativeClass.Municipality,Track.LeftSide,Continuous,7583,7596,Some(DateTime.parse("1901-01-01")),None,Option("tester"),143863,0.000,32.498,SideCode.TowardsDigitizing,1533749925000L,(None,None),Seq(Point(375021.028,6673790.278,11.365000000005239),Point(375036.543,6673793.775,12.706999999994878),Point(375053.04,6673795.563,12.83299999999872)),LinkGeomSource.NormalLinkInterface,1,NoTermination,310124301)),
toProjectLink(rap,LinkStatus.Transfer)(RoadAddress(109792,442180,40921,1,AdministrativeClass.Municipality,Track.LeftSide,Continuous,7596,7600,Some(DateTime.parse("1901-01-01")),None,Option("tester"),143861,0.000,10.749,SideCode.TowardsDigitizing,1533749925000L,(None,None),Seq(Point(375053.04,6673795.563,12.83299999999872),Point(375063.756,6673796.41,12.919996587695966)),LinkGeomSource.NormalLinkInterface,1,NoTermination,310124301)),
toProjectLink(rap,LinkStatus.Transfer)(RoadAddress(109768,435891,40921,1,AdministrativeClass.Municipality,Track.RightSide,Continuous,7346,7534,Some(DateTime.parse("1901-01-01")),None,Option("tester"),143939,0.000,112.967,SideCode.AgainstDigitizing,1533749925000L,(None,None),Seq(Point(375172.788,6673775.964,13.448000000003958),Point(375155.844,6673779.19,13.375),Point(375139.996,6673781.262,13.271999999997206),Point(375124.957,6673783.296,13.171000000002095),Point(375110.163,6673784.78,13.107000000003609),Point(375089.413,6673785.993,12.952999999994063),Point(375068.437,6673786.157,12.872000000003027),Point(375060.517,6673785.777,12.839000000007218)),LinkGeomSource.NormalLinkInterface,1,NoTermination,310124300)),
toProjectLink(rap,LinkStatus.Transfer)(RoadAddress(109793,442181,40921,1,AdministrativeClass.Municipality,Track.LeftSide,Continuous,7600,7534,Some(DateTime.parse("1901-01-01")),None,Option("tester"),143858,0.000,106.352,SideCode.AgainstDigitizing,1599778815000L,(None,None),Seq(Point(375169.282,6673785.082,13.562999999994645),Point(375142.486,6673790.226,13.489000000001397),Point(375116.509,6673793.804,13.35899999999674),Point(375088.552,6673796.034,13.111999999993714),Point(375064.624,6673796.41,12.929000000003725),Point(375063.756,6673796.41,12.920002931440251)),LinkGeomSource.NormalLinkInterface,1,NoTermination,310124301))
)
//val projectLinkSeq = Seq(projectLink1, projectLink2, projectLink3,projectLink4, projectLink5)

      val output = ProjectSectionCalculator.assignMValues(projectLinks).sortBy(_.startAddrMValue)
      //output.foreach(println)
      //output.length should be(5)

      val lefts = output.filterNot(_.track == Track.RightSide)
      val rights = output.filterNot(_.track == Track.LeftSide)

      lefts.zip(lefts.tail).foreach {
        case (prev, next) =>
//          prev.endAddrMValue should be(next.startAddrMValue)
      }
      rights.zip(rights.tail).foreach {
        case (prev, next) =>
//          prev.endAddrMValue should be(next.startAddrMValue)
      }

      output.foreach(pl => {
        //(pl.endAddrMValue - pl.startAddrMValue) should be > 0L
        (pl.endAddrMValue, pl.startAddrMValue) should be((pl.originalEndAddrMValue, pl.originalStartAddrMValue))
        //pl.sideCode should be(asset.SideCode.TowardsDigitizing)
      }
      )
    }
  }

}

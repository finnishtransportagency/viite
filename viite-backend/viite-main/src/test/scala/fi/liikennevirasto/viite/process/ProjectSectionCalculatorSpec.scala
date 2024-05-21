package fi.liikennevirasto.viite.process

import fi.liikennevirasto.digiroad2.DigiroadEventBus
import fi.liikennevirasto.digiroad2.service.RoadLinkService
import fi.liikennevirasto.digiroad2.util.MissingTrackException
import fi.liikennevirasto.viite._
import fi.liikennevirasto.viite.dao._
import fi.liikennevirasto.viite.dao.ProjectCalibrationPointDAO.UserDefinedCalibrationPoint
import fi.liikennevirasto.viite.dao.TerminationCode.NoTermination
import fi.liikennevirasto.viite.util._
import fi.vaylavirasto.viite.dao.{Link, LinkDAO, Sequences}
import fi.vaylavirasto.viite.geometry.{GeometryUtils, Point}
import fi.vaylavirasto.viite.model.CalibrationPointType.{JunctionPointCP, NoCP, RoadAddressCP, UserDefinedCP}
import fi.vaylavirasto.viite.model.{AdministrativeClass, Discontinuity, LinkGeomSource, RoadAddressChangeType, RoadPart, SideCode, Track}
import fi.vaylavirasto.viite.postgis.DbUtils.runUpdateToDb
import fi.vaylavirasto.viite.postgis.PostGISDatabase.runWithRollback
import org.joda.time.DateTime
import org.scalatest.{FunSuite, Matchers}
import org.scalatest.mockito.MockitoSugar

class ProjectSectionCalculatorSpec extends FunSuite with Matchers {
  val mockRoadLinkService: RoadLinkService = MockitoSugar.mock[RoadLinkService]
  private val mockNodesAndJunctionsService = MockitoSugar.mock[NodesAndJunctionsService]
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

  val roadAddressService: RoadAddressService =
    new RoadAddressService(
                            mockRoadLinkService,
                            roadwayDAO,
                            linearLocationDAO,
                            roadNetworkDAO,
                            roadwayPointDAO,
                            nodePointDAO,
                            junctionPointDAO,
                            mockRoadwayAddressMapper,
                            mockEventBus,
                            frozenKGV = false
                            ) {
                                override def withDynSession[T](f: => T): T = f
                                override def withDynTransaction[T](f: => T): T = f
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
                            override def withDynSession[T](f: => T): T = f
                            override def withDynTransaction[T](f: => T): T = f
                          }

  val projectId = 1
  private val rap = Project(projectId, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("2700-01-01"),
    "TestUser", DateTime.parse("1972-03-03"), DateTime.parse("2700-01-01"), "Some additional info",
    List.empty[ProjectReservedPart], Seq(), None)

  def toRoadwayAndLinearLocation(p: ProjectLink):(LinearLocation, Roadway) = {
    val startDate = p.startDate.getOrElse(DateTime.now()).minusDays(1)

    (LinearLocation(p.linearLocationId, 1, p.linkId, p.startMValue, p.endMValue, p.sideCode, p.linkGeometryTimeStamp,
      (CalibrationPointsUtils.toCalibrationPointReference(p.startCalibrationPoint),
        CalibrationPointsUtils.toCalibrationPointReference(p.endCalibrationPoint)),
      p.geometry, p.linkGeomSource,
      p.roadwayNumber, Some(startDate), p.endDate),
      Roadway(p.roadwayId, p.roadwayNumber, p.roadPart, p.administrativeClass, p.track, p.discontinuity, p.startAddrMValue, p.endAddrMValue, p.reversed, startDate, p.endDate,
        p.createdBy.getOrElse("-"), p.roadName, p.ely, TerminationCode.NoTermination, DateTime.now(), None))
  }

  def toRoadwaysAndLinearLocations(pls: Seq[ProjectLink]): (Seq[LinearLocation], Seq[Roadway]) = {
    pls.foldLeft((Seq.empty[LinearLocation], Seq.empty[Roadway])) { (list, p) =>
      val startDate = p.startDate.getOrElse(DateTime.now()).minusDays(1)

      (list._1 :+ LinearLocation(p.linearLocationId, 1, p.linkId, p.startMValue, p.endMValue, p.sideCode, p.linkGeometryTimeStamp,
        (CalibrationPointsUtils.toCalibrationPointReference(p.startCalibrationPoint),
          CalibrationPointsUtils.toCalibrationPointReference(p.endCalibrationPoint)),
        p.geometry, p.linkGeomSource,
        p.roadwayNumber, Some(startDate), p.endDate), list._2 :+ Roadway(p.roadwayId, p.roadwayNumber, p.roadPart, p.administrativeClass, p.track, p.discontinuity, p.startAddrMValue, p.endAddrMValue, p.reversed, startDate, p.endDate,
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
        val roadParts = pls.get.groupBy(pl => (pl.roadPart)).keys
        roadParts.foreach(rp => projectReservedPartDAO.reserveRoadPart(project.get.id, rp, "user"))
        projectLinkDAO.create(pls.get.map(_.copy(projectId = project.get.id)))
      } else {
        projectLinkDAO.create(pls.get)
      }
    }
  }

  test("Test assignAddrMValues When assigning values for new road addresses Then values should be properly assigned") {
    runWithRollback {
      val idRoad0 = 0L
      val idRoad1 = 1L
      val idRoad2 = 2L
      val idRoad3 = 3L
      val projectLink0 = toProjectLink(rap, RoadAddressChangeType.New)(RoadAddress(idRoad0, 0, RoadPart(5, 1), AdministrativeClass.Unknown, Track.Combined, Discontinuity.Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), 12345L.toString, 0.0,  9.8, SideCode.TowardsDigitizing, 0, (None, None), Seq(Point(0.0,  0.0), Point(0.0,  9.8)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink1 = toProjectLink(rap, RoadAddressChangeType.New)(RoadAddress(idRoad1, 0, RoadPart(5, 1), AdministrativeClass.Unknown, Track.Combined, Discontinuity.Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), 12346L.toString, 0.0,  9.8, SideCode.TowardsDigitizing, 0, (None, None), Seq(Point(0.0, 30.0), Point(0.0, 39.8)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink2 = toProjectLink(rap, RoadAddressChangeType.New)(RoadAddress(idRoad2, 0, RoadPart(5, 1), AdministrativeClass.Unknown, Track.Combined, Discontinuity.Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), 12347L.toString, 0.0,  9.8, SideCode.TowardsDigitizing, 0, (None, None), Seq(Point(0.0, 20.2), Point(0.0, 30.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink3 = toProjectLink(rap, RoadAddressChangeType.New)(RoadAddress(idRoad3, 0, RoadPart(5, 1), AdministrativeClass.Unknown, Track.Combined, Discontinuity.Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), 12348L.toString, 0.0, 10.4, SideCode.TowardsDigitizing, 0, (None, None), Seq(Point(0.0,  9.8), Point(0.0, 20.2)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))

      val projectLinkSeq = Seq(projectLink0, projectLink1, projectLink2, projectLink3)
      val output = ProjectSectionCalculator.assignAddrMValues(projectLinkSeq)
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

      output(3).calibrationPoints should be(None, Some(ProjectCalibrationPoint(12346.toString, Math.round(9.799999999999997*10.0)/10.0, 40, RoadAddressCP)))

      output.head.calibrationPoints should be(Some(ProjectCalibrationPoint(12345.toString, 0.0, 0, RoadAddressCP)), None)
    }
  }

  test("Test assignAddrMValues When assigning values for new road addresses in a complex case Then values should be properly assigned") {
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
      val projectLink0 = toProjectLink(rap, RoadAddressChangeType.New)(RoadAddress(idRoad0, 0, RoadPart(5, 1), AdministrativeClass.Unknown, Track.Combined,  Discontinuity.Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad0.toString, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), Seq(Point( 0.0,  0.0), Point( 0.0,  9.8)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink1 = toProjectLink(rap, RoadAddressChangeType.New)(RoadAddress(idRoad1, 0, RoadPart(5, 1), AdministrativeClass.Unknown, Track.RightSide, Discontinuity.Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad1.toString, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), Seq(Point( 0.0,  9.8), Point(-2.0, 20.2)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink2 = toProjectLink(rap, RoadAddressChangeType.New)(RoadAddress(idRoad2, 0, RoadPart(5, 1), AdministrativeClass.Unknown, Track.LeftSide,  Discontinuity.Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad2.toString, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), Seq(Point( 0.0,  9.8), Point( 2.0, 19.2)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink3 = toProjectLink(rap, RoadAddressChangeType.New)(RoadAddress(idRoad3, 0, RoadPart(5, 1), AdministrativeClass.Unknown, Track.RightSide, Discontinuity.Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad3.toString, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), Seq(Point(-2.0, 20.2), Point( 1.0, 30.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink4 = toProjectLink(rap, RoadAddressChangeType.New)(RoadAddress(idRoad4, 0, RoadPart(5, 1), AdministrativeClass.Unknown, Track.LeftSide,  Discontinuity.Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad4.toString, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), Seq(Point( 2.0, 19.2), Point( 1.0, 30.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink5 = toProjectLink(rap, RoadAddressChangeType.New)(RoadAddress(idRoad5, 0, RoadPart(5, 1), AdministrativeClass.Unknown, Track.Combined,  Discontinuity.Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad5.toString, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), Seq(Point( 1.0, 30.0), Point( 0.0, 48.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink6 = toProjectLink(rap, RoadAddressChangeType.New)(RoadAddress(idRoad6, 0, RoadPart(5, 1), AdministrativeClass.Unknown, Track.RightSide, Discontinuity.Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad6.toString, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), Seq(Point( 0.0, 48.0), Point( 2.0, 68.0), Point(0.0, 96.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink7 = toProjectLink(rap, RoadAddressChangeType.New)(RoadAddress(idRoad7, 0, RoadPart(5, 1), AdministrativeClass.Unknown, Track.LeftSide,  Discontinuity.Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad7.toString, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), Seq(Point( 0.0, 48.0), Point(-2.0, 68.0), Point(0.0, 96.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink8 = toProjectLink(rap, RoadAddressChangeType.New)(RoadAddress(idRoad8, 0, RoadPart(5, 1), AdministrativeClass.Unknown, Track.Combined,  Discontinuity.Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad8.toString, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), Seq(Point( 0.0, 96.0), Point( 0.0,148.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))

      val projectLinkSeq = Seq(projectLink0, projectLink1, projectLink2, projectLink3, projectLink4, projectLink5, projectLink6, projectLink7, projectLink8).map(pl =>
        pl.copy(endMValue = pl.geometryLength))
      val output = ProjectSectionCalculator.assignAddrMValues(projectLinkSeq).sortBy(_.linkId)
      output.length should be(9)
      output.foreach(pl => pl.sideCode == SideCode.AgainstDigitizing should be(true))
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

  test("Test assignAddrMValues When giving against digitization case Then values should be properly assigned") {
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
      val projectLink0 = toProjectLink(rap, RoadAddressChangeType.New)(RoadAddress(idRoad0, 0, RoadPart(5, 1), AdministrativeClass.Unknown, Track.Combined,  Discontinuity.Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad0.toString, 0.0, 9.8, SideCode.TowardsDigitizing, 0, (None, None), Seq(Point( 0.0,  0.0), Point( 0.0,   9.8)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink1 = toProjectLink(rap, RoadAddressChangeType.New)(RoadAddress(idRoad1, 0, RoadPart(5, 1), AdministrativeClass.Unknown, Track.RightSide, Discontinuity.Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad1.toString, 0.0, 10.6, SideCode.TowardsDigitizing, 0, (None, None), Seq(Point( 0.0,  9.8), Point(-2.0,  20.2)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink2 = toProjectLink(rap, RoadAddressChangeType.New)(RoadAddress(idRoad2, 0, RoadPart(5, 1), AdministrativeClass.Unknown, Track.LeftSide,  Discontinuity.Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad2.toString, 0.0, 9.21, SideCode.TowardsDigitizing, 0, (None, None), Seq(Point( 0.0,  9.8), Point( 2.0,  19.2)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink3 = toProjectLink(rap, RoadAddressChangeType.New)(RoadAddress(idRoad3, 0, RoadPart(5, 1), AdministrativeClass.Unknown, Track.RightSide, Discontinuity.Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad3.toString, 0.0, 10.29, SideCode.TowardsDigitizing, 0, (None, None), Seq(Point(-2.0, 20.2), Point( 1.0,  30.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink4 = toProjectLink(rap, RoadAddressChangeType.New)(RoadAddress(idRoad4, 0, RoadPart(5, 1), AdministrativeClass.Unknown, Track.LeftSide,  Discontinuity.Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad4.toString, 0.0, 10.60, SideCode.TowardsDigitizing, 0, (None, None), Seq(Point( 2.0, 19.2), Point( 1.0,  30.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink5 = toProjectLink(rap, RoadAddressChangeType.New)(RoadAddress(idRoad5, 0, RoadPart(5, 1), AdministrativeClass.Unknown, Track.Combined,  Discontinuity.Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad5.toString, 0.0, 18.02, SideCode.TowardsDigitizing, 0, (None, None), Seq(Point( 1.0, 30.0), Point( 0.0,  48.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink6 = toProjectLink(rap, RoadAddressChangeType.New)(RoadAddress(idRoad6, 0, RoadPart(5, 1), AdministrativeClass.Unknown, Track.RightSide, Discontinuity.Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad6.toString, 0.0, 48.17, SideCode.TowardsDigitizing, 0, (None, None), Seq(Point( 0.0, 48.0), Point( 2.0,  68.0), Point(0.0, 96.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink7 = toProjectLink(rap, RoadAddressChangeType.New)(RoadAddress(idRoad7, 0, RoadPart(5, 1), AdministrativeClass.Unknown, Track.LeftSide,  Discontinuity.Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad7.toString, 0.0, 48.17, SideCode.TowardsDigitizing, 0, (None, None), Seq(Point( 0.0, 48.0), Point(-2.0,  68.0), Point(0.0, 96.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink8 = toProjectLink(rap, RoadAddressChangeType.New)(RoadAddress(idRoad8, 0, RoadPart(5, 1), AdministrativeClass.Unknown, Track.Combined,  Discontinuity.Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad8.toString, 0.0, 52.0, SideCode.TowardsDigitizing, 0, (None, None), Seq(Point( 0.0, 96.0), Point( 0.0, 148.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))

      val projectLinkSeq = Seq(projectLink0, projectLink1, projectLink2, projectLink3, projectLink4, projectLink5, projectLink6, projectLink7, projectLink8).map(
        pl => pl.copy(sideCode = SideCode.AgainstDigitizing)
      )
      val output = ProjectSectionCalculator.assignAddrMValues(projectLinkSeq).sortBy(_.linkId)
      output.length should be(9)
      output.foreach(pl => pl.sideCode == SideCode.TowardsDigitizing should be(true))
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

  test("Test assignAddrMValues When addressing calibration points and mixed directions Then values should be properly assigned") {
    runWithRollback {
      val idRoad0 = 0L //   >
      val idRoad1 = 1L //     <
      val idRoad2 = 2L //   >
      val idRoad3 = 3L //     <
      val projectLink0 = toProjectLink(rap, RoadAddressChangeType.New)(RoadAddress(idRoad0, 0, RoadPart(5, 1), AdministrativeClass.Unknown, Track.Combined, Discontinuity.Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad0.toString, 0.0, 9.8, SideCode.TowardsDigitizing, 0, (None, None), Seq(Point( 0.0,  0.0), Point(0.0,  9.8)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink1 = toProjectLink(rap, RoadAddressChangeType.New)(RoadAddress(idRoad1, 0, RoadPart(5, 1), AdministrativeClass.Unknown, Track.Combined, Discontinuity.Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad1.toString, 0.0, 4.61, SideCode.AgainstDigitizing, 0, (None, None), Seq(Point( 4.0,  7.5), Point(0.0,  9.8)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink2 = toProjectLink(rap, RoadAddressChangeType.New)(RoadAddress(idRoad2, 0, RoadPart(5, 1), AdministrativeClass.Unknown, Track.Combined, Discontinuity.Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad2.toString, 0.0, 11.86, SideCode.TowardsDigitizing, 0, (None, None), Seq(Point( 4.0,  7.5), Point(6.0, 19.2)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink3 = toProjectLink(rap, RoadAddressChangeType.New)(RoadAddress(idRoad3, 0, RoadPart(5, 1), AdministrativeClass.Unknown, Track.Combined, Discontinuity.Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad3.toString, 0.0, 5.8, SideCode.AgainstDigitizing, 0, (None, None), Seq(Point(10.0, 15.0), Point(6.0, 19.2)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))

      val projectLinkSeq = Seq(projectLink0, projectLink1, projectLink2, projectLink3)
      val output = ProjectSectionCalculator.assignAddrMValues(projectLinkSeq).sortBy(_.linkId)
      output.length should be(4)
      output.foreach(pl => pl.sideCode == SideCode.AgainstDigitizing || pl.id % 2 == 0 should be(true))
      output.foreach(pl => pl.sideCode == SideCode.TowardsDigitizing || pl.id % 2 != 0 should be(true))
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

  test("Test assignAddrMValues When assigning values for Tracks 0+1+2 Then the result should not be dependent on the order of the links") {
    runWithRollback {
      def trackMap(pl: ProjectLink) = {
        pl.linkId -> (pl.track, pl.sideCode)
      }

      val idRoad0 = 0L //   0 Track
      val idRoad1 = 1L //   1 Track
      val idRoad2 = 2L //   2 Track
      val projectLink0 = toProjectLink(rap, RoadAddressChangeType.New)(RoadAddress(idRoad0, 0, RoadPart(5, 1), AdministrativeClass.Unknown, Track.Combined,  Discontinuity.Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad0.toString, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), Seq(Point(20.0, 10.0), Point(28, 15  )), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink1 = toProjectLink(rap, RoadAddressChangeType.New)(RoadAddress(idRoad1, 0, RoadPart(5, 1), AdministrativeClass.Unknown, Track.RightSide, Discontinuity.Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad1.toString, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), Seq(Point(42,   14),   Point(28, 15  )), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink2 = toProjectLink(rap, RoadAddressChangeType.New)(RoadAddress(idRoad2, 0, RoadPart(5, 1), AdministrativeClass.Unknown, Track.LeftSide,  Discontinuity.Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad2.toString, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), Seq(Point(28,   15),   Point(75, 19.2)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val list = List(projectLink0, projectLink1, projectLink2)
      val ordered = ProjectSectionCalculator.assignAddrMValues(list).map(trackMap).toMap
      // Test that the result is not dependent on the order of the links
      list.permutations.foreach(l => {
        ProjectSectionCalculator.assignAddrMValues(l).map(trackMap).toMap should be(ordered)
      })
    }
  }

  test("Test assignAddrMValues When giving one link Towards and one Against Digitizing Then calibrations points should be properly assigned") {
    runWithRollback {
      val geometry = Seq(Point(20.0, 10.0), Point(28, 15))
      val projectLink0T = toProjectLink(rap, RoadAddressChangeType.New)(RoadAddress(0L, 0, RoadPart(5, 1), AdministrativeClass.Unknown, Track.Combined, Discontinuity.Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), 0L.toString, 0.0, GeometryUtils.geometryLength(geometry), SideCode.TowardsDigitizing, 0, (None, None), geometry, LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink0A = toProjectLink(rap, RoadAddressChangeType.New)(RoadAddress(0L, 0, RoadPart(5, 1), AdministrativeClass.Unknown, Track.Combined, Discontinuity.Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), 0L.toString, 0.0, GeometryUtils.geometryLength(geometry), SideCode.AgainstDigitizing, 0, (None, None), geometry, LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))

      val towards = ProjectSectionCalculator.assignAddrMValues(Seq(projectLink0T)).head
      val against = ProjectSectionCalculator.assignAddrMValues(Seq(projectLink0A)).head
      towards.sideCode should be(SideCode.TowardsDigitizing)
      against.sideCode should be(SideCode.AgainstDigitizing)
      towards.calibrationPoints._1 should be(Some(ProjectCalibrationPoint(0.toString, 0.0, 0, RoadAddressCP)))
      towards.calibrationPoints._2 should be(Some(ProjectCalibrationPoint(0.toString, projectLink0T.geometryLength, 9, RoadAddressCP)))
      against.calibrationPoints._2 should be(Some(ProjectCalibrationPoint(0.toString, 0.0, 9, RoadAddressCP)))
      against.calibrationPoints._1 should be(Some(ProjectCalibrationPoint(0.toString, projectLink0A.geometryLength, 0, RoadAddressCP)))
    }
  }

  test("Test assignAddrMValues When giving links without opposite track Then exception is thrown and links are returned as-is") {
    runWithRollback {
      val projectLink0 = toProjectLink(rap, RoadAddressChangeType.New)(RoadAddress(0L, 0, RoadPart(5, 1), AdministrativeClass.Unknown, Track.RightSide, Discontinuity.Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), 0L.toString, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), Seq(Point(20.0, 10.0), Point(28, 15)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink1 = toProjectLink(rap, RoadAddressChangeType.New)(RoadAddress(1L, 0, RoadPart(5, 1), AdministrativeClass.Unknown, Track.RightSide, Discontinuity.Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), 1L.toString, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), Seq(Point(28.0, 15.0), Point(38, 15)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))

      a [MissingTrackException] should be thrownBy ProjectSectionCalculator.assignAddrMValues(Seq(projectLink0, projectLink1))
    }
  }

  test("Test assignAddrMValues When giving incompatible digitization on tracks Then the direction of left track is corrected to match the right track") {
    runWithRollback {
      val idRoad0 = 0L //   R<
      val idRoad1 = 1L //   R<
      val idRoad2 = 2L //   L<    <- Note! Incompatible, means the addressing direction is against the right track
      val idRoad3 = 3L //   L<    <- Note! Incompatible, means the addressing direction is against the right track
      val projectLink0 = toProjectLink(rap, RoadAddressChangeType.New)(RoadAddress(idRoad0, 0, RoadPart(5, 1), AdministrativeClass.Unknown, Track.RightSide, Discontinuity.Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad0.toString, 0.0, 0.0, SideCode.AgainstDigitizing, 0, (Some(ProjectCalibrationPoint(0L.toString, 0.0, 0L)), None), Seq(Point(28, 9.8), Point(20.0, 10.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink1 = toProjectLink(rap, RoadAddressChangeType.New)(RoadAddress(idRoad1, 0, RoadPart(5, 1), AdministrativeClass.Unknown, Track.RightSide, Discontinuity.Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad1.toString, 0.0, 0.0, SideCode.AgainstDigitizing, 0, (None, None), Seq(Point(42,  9.7), Point(28,  9.8)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink2 = toProjectLink(rap, RoadAddressChangeType.New)(RoadAddress(idRoad2, 0, RoadPart(5, 1), AdministrativeClass.Unknown, Track.LeftSide,  Discontinuity.Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad2.toString, 0.0, 0.0, SideCode.AgainstDigitizing, 0, (None, None), Seq(Point(20, 10.1), Point(28, 10.2)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink3 = toProjectLink(rap, RoadAddressChangeType.New)(RoadAddress(idRoad3, 0, RoadPart(5, 1), AdministrativeClass.Unknown, Track.LeftSide,  Discontinuity.Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad3.toString, 0.0, 0.0, SideCode.AgainstDigitizing, 0, (None, None), Seq(Point(28, 10.2), Point(42, 10.3)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val list = List(projectLink0, projectLink1, projectLink2, projectLink3)
      val ordered = ProjectSectionCalculator.assignAddrMValues(list)
      // Test that the direction of left track is corrected to match the right track
      val (right, left) = ordered.partition(_.track == Track.RightSide)
      right.foreach(
        _.sideCode should be(SideCode.AgainstDigitizing)
      )
      left.foreach(
        _.sideCode should be(SideCode.TowardsDigitizing)
      )
    }
  }

  test("Test assignAddrMValues When giving links with different track lengths Then different track lengths are adjusted") {
    runWithRollback {
      // Left track = 89.930 meters
      val idRoad0 = 0L //   L>
      val idRoad1 = 1L //   L>
      val idRoad2 = 2L //   L>
      val idRoad3 = 3L //   L<
      // Right track = 83.154 meters
      val idRoad4 = 4L //   R>
      val idRoad5 = 5L //   R>
      val projectLink0 = toProjectLink(rap, RoadAddressChangeType.New)(RoadAddress(idRoad0, 0, RoadPart(5, 1), AdministrativeClass.Unknown, Track.LeftSide,  Discontinuity.Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad0.toString, 0.0, 12.04, SideCode.TowardsDigitizing, 0, (None, None), Seq(Point( 20.0, 10.0), Point(28,  15  )), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink1 = toProjectLink(rap, RoadAddressChangeType.New)(RoadAddress(idRoad1, 0, RoadPart(5, 1), AdministrativeClass.Unknown, Track.LeftSide,  Discontinuity.Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad1.toString, 0.0, 14.56, SideCode.TowardsDigitizing, 0, (None, None), Seq(Point( 28,   15),   Point(42,  19  )), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink2 = toProjectLink(rap, RoadAddressChangeType.New)(RoadAddress(idRoad2, 0, RoadPart(5, 1), AdministrativeClass.Unknown, Track.LeftSide,  Discontinuity.Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad2.toString, 0.0, 34.54, SideCode.TowardsDigitizing, 0, (None, None), Seq(Point( 42,   19),   Point(75,  29.2)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink3 = toProjectLink(rap, RoadAddressChangeType.New)(RoadAddress(idRoad3, 0, RoadPart(5, 1), AdministrativeClass.Unknown, Track.LeftSide,  Discontinuity.Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad3.toString, 0.0, 31.39, SideCode.AgainstDigitizing, 0, (None, None), Seq(Point(103.0, 15.0), Point(75,  29.2)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink4 = toProjectLink(rap, RoadAddressChangeType.New)(RoadAddress(idRoad4, 0, RoadPart(5, 1), AdministrativeClass.Unknown, Track.RightSide, Discontinuity.Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad4.toString, 0.0, 22.02, SideCode.TowardsDigitizing, 0, (None, None), Seq(Point( 20.0, 10.0), Point(42,  11  )), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink5 = toProjectLink(rap, RoadAddressChangeType.New)(RoadAddress(idRoad5, 0, RoadPart(5, 1), AdministrativeClass.Unknown, Track.RightSide, Discontinuity.Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad5.toString, 0.0, 61.13, SideCode.TowardsDigitizing, 0, (None, None), Seq(Point( 42,   11),   Point(103, 15  )), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val list = List(projectLink0, projectLink1, projectLink2, projectLink3, projectLink4, projectLink5)
      val ordered = ProjectSectionCalculator.assignAddrMValues(list)
      ordered.map(fi.liikennevirasto.viite.util.prettyPrint).foreach(println)
      ordered.flatMap(_.calibrationPoints._1).foreach(
        _.addressMValue should be(0L)
      )
      ordered.flatMap(_.calibrationPoints._2).foreach(
        _.addressMValue should be(86L)
      )
    }
  }

  test("Test assignAddrMValues When giving links in different tracks and RoadAddressCP in the middle Then proper calibration points should be created for each track and RoadAddressCPs cleared from the middle") {
    runWithRollback {
      // Left track = 85.308 meters
      val idRoad0 = 0L.toString //   L>
      val idRoad1 = 1L.toString //   L>
      val idRoad2 = 2L.toString //   L>
      val idRoad3 = 3L.toString //   L<
      // Right track = 83.154 meters
      val idRoad4 = 4L.toString //   R>
      val idRoad5 = 5L.toString //   R>

      val geom0 = Seq(Point(20.0, 10.0), Point(28, 15))
      val geom1 = Seq(Point(28, 15), Point(42, 19))
      val geom2 = Seq(Point(42, 19), Point(75, 29.2))
      val geom3 = Seq(Point(103.0, 15.0), Point(75, 29.2))
      val geom4 = Seq(Point(20.0, 10.0), Point(42, 11))
      val geom5 = Seq(Point(42, 11), Point(103, 15))
      val projectLink0 = toProjectLink(rap, RoadAddressChangeType.New)(RoadAddress(idRoad0.toLong, 0, RoadPart(5, 1), AdministrativeClass.Unknown, Track.LeftSide,  Discontinuity.Continuous, 0L,  9L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad0, 0.0, GeometryUtils.geometryLength(geom0), SideCode.TowardsDigitizing, 0, (None, Some(ProjectCalibrationPoint(idRoad0, 9.0, 9L, RoadAddressCP))), geom0, LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink1 = toProjectLink(rap, RoadAddressChangeType.New)(RoadAddress(idRoad1.toLong, 0, RoadPart(5, 1), AdministrativeClass.Unknown, Track.LeftSide,  Discontinuity.Continuous, 9L, 20L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad1, 0.0, GeometryUtils.geometryLength(geom1), SideCode.TowardsDigitizing, 0, (Some(ProjectCalibrationPoint(idRoad1, 0.0, 9L, RoadAddressCP)), None), geom1, LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink2 = toProjectLink(rap, RoadAddressChangeType.New)(RoadAddress(idRoad2.toLong, 0, RoadPart(5, 1), AdministrativeClass.Unknown, Track.LeftSide,  Discontinuity.Continuous, 0L,  0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad2, 0.0, GeometryUtils.geometryLength(geom2), SideCode.TowardsDigitizing, 0, (None, None), geom2, LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink3 = toProjectLink(rap, RoadAddressChangeType.New)(RoadAddress(idRoad3.toLong, 0, RoadPart(5, 1), AdministrativeClass.Unknown, Track.LeftSide,  Discontinuity.Continuous, 0L,  0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad3, 0.0, GeometryUtils.geometryLength(geom3), SideCode.AgainstDigitizing, 0, (None, None), geom3, LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink4 = toProjectLink(rap, RoadAddressChangeType.New)(RoadAddress(idRoad4.toLong, 0, RoadPart(5, 1), AdministrativeClass.Unknown, Track.RightSide, Discontinuity.Continuous, 0L,  0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad4, 0.0, GeometryUtils.geometryLength(geom4), SideCode.TowardsDigitizing, 0, (None, None), geom4, LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink5 = toProjectLink(rap, RoadAddressChangeType.New)(RoadAddress(idRoad5.toLong, 0, RoadPart(5, 1), AdministrativeClass.Unknown, Track.RightSide, Discontinuity.Continuous, 0L,  0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad5, 0.0, GeometryUtils.geometryLength(geom5), SideCode.TowardsDigitizing, 0, (None, None), geom5, LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val list = List(projectLink0, projectLink1, projectLink2, projectLink3, projectLink4, projectLink5)
      val ordered = ProjectSectionCalculator.assignAddrMValues(list)
      ordered.flatMap(_.calibrationPoints._1) should have size 2
      ordered.flatMap(_.calibrationPoints._2) should have size 2
    }
  }

  test("Test assignAddrMValues When giving links in one track with JunctionPointCP in the middle Then proper calibration points should be created and JunctionPointCPs kept in place") {
    runWithRollback {
      val idRoad0 = 0L.toString
      val idRoad1 = 1L.toString
      val idRoad2 = 2L.toString
      val idRoad3 = 3L.toString

      val geom0 = Seq(Point(20.0, 10.0), Point(28, 15))
      val geom1 = Seq(Point(28, 15), Point(42, 19))
      val geom2 = Seq(Point(42, 19), Point(75, 29.2))
      val geom3 = Seq(Point(103.0, 15.0), Point(75, 29.2))
      val projectLink0 = toProjectLink(rap, RoadAddressChangeType.Unchanged)(RoadAddress(idRoad0.toLong, 0, RoadPart(5, 1), AdministrativeClass.Unknown, Track.Combined, Discontinuity.Continuous,  0L, 9L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad0, 0.0, GeometryUtils.geometryLength(geom0), SideCode.TowardsDigitizing, 0, (None, Some(ProjectCalibrationPoint(idRoad0, 9.0, 9L, JunctionPointCP))), geom0, LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink1 = toProjectLink(rap, RoadAddressChangeType.Unchanged)(RoadAddress(idRoad1.toLong, 0, RoadPart(5, 1), AdministrativeClass.Unknown, Track.Combined, Discontinuity.Continuous, 9L, 20L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad1, 0.0, GeometryUtils.geometryLength(geom1), SideCode.TowardsDigitizing, 0, (Some(ProjectCalibrationPoint(idRoad1, 0.0, 9L, JunctionPointCP)), None), geom1, LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink2 = toProjectLink(rap, RoadAddressChangeType.New)      (RoadAddress(idRoad2.toLong, 0, RoadPart(5, 1), AdministrativeClass.Unknown, Track.Combined, Discontinuity.Continuous,  0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad2, 0.0, GeometryUtils.geometryLength(geom2), SideCode.TowardsDigitizing, 0, (None, None), geom2, LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink3 = toProjectLink(rap, RoadAddressChangeType.New)      (RoadAddress(idRoad3.toLong, 0, RoadPart(5, 1), AdministrativeClass.Unknown, Track.Combined, Discontinuity.Continuous,  0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad3, 0.0, GeometryUtils.geometryLength(geom3), SideCode.AgainstDigitizing, 0, (None, None), geom3, LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val list = List(projectLink0, projectLink1, projectLink2, projectLink3)
      val ordered = ProjectSectionCalculator.assignAddrMValues(list)
      ordered.flatMap(_.calibrationPoints._1) should have size 2
      ordered.flatMap(_.calibrationPoints._2) should have size 2
      ordered.head.startCalibrationPointType should be(RoadAddressCP)
      ordered.filter(p => p.startAddrMValue == 0).head.endCalibrationPointType should be(JunctionPointCP)
      ordered.filter(p => p.startAddrMValue == 9).head.startCalibrationPointType should be(JunctionPointCP)
      ordered.last.endCalibrationPointType should be(RoadAddressCP)
    }
  }

  test("Test assignAddrMValues When giving links in one track with JunctionPointCP at the beginning and end Then JunctionPointCPs should be changed to RoadAddressCPs") {
    runWithRollback {
      val idRoad0 = 0L
      val idRoad1 = 1L
      val idRoad2 = 2L
      val idRoad3 = 3L

      val geom0 = Seq(Point(20.0, 10.0), Point(28, 15))
      val geom1 = Seq(Point(28, 15), Point(42, 19))
      val geom2 = Seq(Point(42, 19), Point(75, 29.2))
      val geom3 = Seq(Point(103.0, 15.0), Point(75, 29.2))
      val projectLink0 = toProjectLink(rap, RoadAddressChangeType.New)(RoadAddress(idRoad0, 0, RoadPart(5, 1), AdministrativeClass.Unknown, Track.Combined, Discontinuity.Continuous,  0L,  9L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad0.toString, 0.0, GeometryUtils.geometryLength(geom0), SideCode.TowardsDigitizing, 0, (Some(ProjectCalibrationPoint(idRoad0.toString, 0.0, 0L, JunctionPointCP)), None), geom0, LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink1 = toProjectLink(rap, RoadAddressChangeType.New)(RoadAddress(idRoad1, 0, RoadPart(5, 1), AdministrativeClass.Unknown, Track.Combined, Discontinuity.Continuous,  9L, 20L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad1.toString, 0.0, GeometryUtils.geometryLength(geom1), SideCode.TowardsDigitizing, 0, (None, None), geom1, LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink2 = toProjectLink(rap, RoadAddressChangeType.New)(RoadAddress(idRoad2, 0, RoadPart(5, 1), AdministrativeClass.Unknown, Track.Combined, Discontinuity.Continuous, 20L, 30L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad2.toString, 0.0, GeometryUtils.geometryLength(geom2), SideCode.TowardsDigitizing, 0, (None, None), geom2, LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink3 = toProjectLink(rap, RoadAddressChangeType.New)(RoadAddress(idRoad3, 0, RoadPart(5, 1), AdministrativeClass.Unknown, Track.Combined, Discontinuity.Continuous, 30L, 40L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad3.toString, 0.0, GeometryUtils.geometryLength(geom3), SideCode.AgainstDigitizing, 0, (None, Some(ProjectCalibrationPoint(idRoad3.toString, GeometryUtils.geometryLength(geom3), 40L, JunctionPointCP))), geom3, LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val list = List(projectLink0, projectLink1, projectLink2, projectLink3)
      val ordered = ProjectSectionCalculator.assignAddrMValues(list)
      ordered.flatMap(_.calibrationPoints._1) should have size 1
      ordered.flatMap(_.calibrationPoints._2) should have size 1
      ordered.head.startCalibrationPointType should be(RoadAddressCP)
      ordered.last.endCalibrationPointType should be(RoadAddressCP)
    }
  }

  test("Test assignAddrMValues When track sections are combined to start from the same position Then tracks should still be different") {
    runWithRollback {
      // Left track = 85.308 meters
      val idRoad0 = 0L.toString //   C>
      val idRoad1 = 1L.toString //   L>
      val idRoad2 = 2L.toString //   R>
      val roadwayId = Sequences.nextRoadwayId
      val roadwayNumber = Sequences.nextRoadwayNumber
      val linearLocationId = Sequences.nextLinearLocationId
      val projectLink0 = toProjectLink(rap, RoadAddressChangeType.Unchanged)(RoadAddress(idRoad0.toLong, 0, RoadPart(5, 1), AdministrativeClass.Unknown, Track.Combined,  Discontinuity.Continuous, 0L, 8L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), idRoad0, 0.0, 8.0, SideCode.TowardsDigitizing, 0, (Some(ProjectCalibrationPoint(0L.toString, 0.0, 0L)), None), Seq(Point(20.0, 10.0), Point(28, 10)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0)).copy(projectId = rap.id, roadwayId = roadwayId, linearLocationId = linearLocationId, roadwayNumber = roadwayNumber)
      val projectLink1 = toProjectLink(rap, RoadAddressChangeType.New)      (RoadAddress(idRoad1.toLong, 0, RoadPart(5, 1), AdministrativeClass.Unknown, Track.LeftSide,  Discontinuity.Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), idRoad1, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), Seq(Point(28, 10), Point(28, 19)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0)).copy(projectId = rap.id)
      val projectLink2 = toProjectLink(rap, RoadAddressChangeType.New)      (RoadAddress(idRoad2.toLong, 0, RoadPart(5, 1), AdministrativeClass.Unknown, Track.RightSide, Discontinuity.Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), idRoad2, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), Seq(Point(28,  1), Point(28, 10)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0)).copy(projectId = rap.id)

      val (linearCombined, rwComb): (LinearLocation, Roadway) = Seq(projectLink0).map(toRoadwayAndLinearLocation).head
      val rwCombWithId = rwComb.copy(id = roadwayId, ely = 8L)
      val linearRightWithId = linearCombined.copy(id = linearLocationId)
      buildTestDataForProject(Some(rap), Some(Seq(rwCombWithId)), Some(Seq(linearRightWithId)), None)

      val list = List(projectLink0, projectLink1, projectLink2)
      val ordered = ProjectSectionCalculator.assignAddrMValues(list)
      val left = ordered.find(_.linkId == idRoad1).get
      val right = ordered.find(_.linkId == idRoad2).get
      left.sideCode == right.sideCode should be(false)
    }
  }

  test("Test assignAddrMValues When giving created + unchanged links Then links should be calculated properly") {
    runWithRollback {
      val idRoad1 = 1L.toString //   U>
      val idRoad2 = 2L.toString //   U>
      val idRoad3 = 3L.toString //   N>
      /*
      |--U-->|--U-->|--N-->|
       */
      val linearLocationId = Sequences.nextLinearLocationId
      val roadwayNumber1 = Sequences.nextRoadwayNumber
      val roadwayNumber2 = Sequences.nextRoadwayNumber

      val geom1 = Seq(Point(20.0, 10.0), Point(28, 10))
      val geom2 = Seq(Point(28, 10), Point(28, 19))
      val geom3 = Seq(Point(28, 19), Point(28, 30))
      val raMap: Map[String, RoadAddress] = Map(
        idRoad1 -> RoadAddress(idRoad1.toLong, linearLocationId,     RoadPart(5, 1), AdministrativeClass.Unknown, Track.Combined, Discontinuity.Continuous, 0L,  9L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), idRoad1, 0.0, GeometryUtils.geometryLength(geom1), SideCode.TowardsDigitizing, 0, (Some(ProjectCalibrationPoint(idRoad1, 0.0, 0L, RoadAddressCP)), None),  geom1, LinkGeomSource.NormalLinkInterface, 8, NoTermination, roadwayNumber1),
        idRoad2 -> RoadAddress(idRoad2.toLong, linearLocationId + 1, RoadPart(5, 1), AdministrativeClass.Unknown, Track.Combined, Discontinuity.Continuous, 9L, 19L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), idRoad2, 0.0, GeometryUtils.geometryLength(geom2), SideCode.TowardsDigitizing, 0, (None, Some(ProjectCalibrationPoint(idRoad2, 9.0, 19L, RoadAddressCP))), geom2, LinkGeomSource.NormalLinkInterface, 8, NoTermination, roadwayNumber2),
        idRoad3 -> RoadAddress(idRoad3.toLong, 0,                    RoadPart(5, 1), AdministrativeClass.Unknown, Track.Combined, Discontinuity.Continuous,  0L, 0L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), idRoad3, 0.0, GeometryUtils.geometryLength(geom3), SideCode.TowardsDigitizing, 0, (None, None), geom3, LinkGeomSource.NormalLinkInterface, 8, NoTermination, NewIdValue)
      )

      val projId = Sequences.nextViiteProjectId
      val project = Project(projId, ProjectState.Incomplete, "f", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
        "", Seq(), Seq(), None, None)

      val projectLink1 = toProjectLink(rap, RoadAddressChangeType.Unchanged)(raMap(idRoad1))
      val projectLink2 = toProjectLink(rap, RoadAddressChangeType.Unchanged)(raMap(idRoad2))
      val projectLink3 = toProjectLink(rap, RoadAddressChangeType.New)(raMap(idRoad3))
      val list = List(projectLink1, projectLink2, projectLink3).map(_.copy(projectId = projId))

      val (linearLocation1, roadway1) = toRoadwayAndLinearLocation(projectLink1)
      val (linearLocation2, roadway2) = toRoadwayAndLinearLocation(projectLink2)

      buildTestDataForProject(Some(project), Some(Seq(roadway1, roadway2)), Some(Seq(linearLocation1, linearLocation2)), Some(Seq(projectLink1, projectLink2)))

      val (created, unchanged) = list.partition(_.status == RoadAddressChangeType.New)
      val recalculatedProjectLinks = ProjectSectionCalculator.assignAddrMValues(created ++ unchanged)
      recalculatedProjectLinks.size should be (3)
      val recalculatedProjectLink3 = recalculatedProjectLinks.find(_.linkId == idRoad3).get
      val recalculatedProjectLink2 = recalculatedProjectLinks.find(_.linkId == idRoad2).get
      recalculatedProjectLink3.startAddrMValue should be(recalculatedProjectLink2.endAddrMValue)
      recalculatedProjectLink3.endAddrMValue should be(30L)
      recalculatedProjectLink3.calibrationPoints._1 should be(None) // last project link shouldn't have start calibration point
      recalculatedProjectLink3.calibrationPoints._2.nonEmpty should be(true) // last project link should have end calibration point
      recalculatedProjectLink2.calibrationPoints._1 should be(None) // middle project link should not have start calibration point no more
      recalculatedProjectLink2.calibrationPoints._2 should be(None) // middle project link should not have end calibration point no more
      recalculatedProjectLinks.count(_.calibrationPoints._2.nonEmpty) should be(1) // only one end calibration point should exist in the recalculated project links
    }
  }

  test("Test assignAddrMValues When giving unchanged + terminated links Then links should be calculated properly") {
    runWithRollback {
      val idRoad0 = 0L.toString //   U>
      val idRoad1 = 1L.toString //   U>
      val idRoad2 = 2L.toString //   T>

      val roadwayId = Sequences.nextRoadwayId
      val roadwayNumber = Sequences.nextRoadwayNumber
      val linearLocationId = Sequences.nextLinearLocationId

      val projectLink0 = toProjectLink(rap, RoadAddressChangeType.Unchanged)  (RoadAddress(idRoad0.toLong, 0, RoadPart(5, 1), AdministrativeClass.Unknown, Track.Combined, Discontinuity.Continuous,  0L,  9L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), idRoad0, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (Some(ProjectCalibrationPoint(idRoad0, 0.0, 0L)), None),   Seq(Point(20.0, 10.0), Point(28, 10)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0)).copy(projectId = rap.id, roadwayId = roadwayId,   linearLocationId = linearLocationId,   roadwayNumber = roadwayNumber)
      val projectLink1 = toProjectLink(rap, RoadAddressChangeType.Unchanged)  (RoadAddress(idRoad1.toLong, 0, RoadPart(5, 1), AdministrativeClass.Unknown, Track.Combined, Discontinuity.Continuous,  9L, 19L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), idRoad1, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None),                                              Seq(Point(28,   10  ), Point(28, 19)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0)).copy(projectId = rap.id, roadwayId = roadwayId+1, linearLocationId = linearLocationId+1, roadwayNumber = roadwayNumber+1)
      val projectLink2 = toProjectLink(rap, RoadAddressChangeType.Termination)(RoadAddress(idRoad2.toLong, 0, RoadPart(5, 1), AdministrativeClass.Unknown, Track.Combined, Discontinuity.Continuous, 19L, 30L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), idRoad2, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, Some(ProjectCalibrationPoint(idRoad2, 11.0, 30L))), Seq(Point(28,   19  ), Point(28, 30)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0)).copy(projectId = rap.id, roadwayId = roadwayId+2, linearLocationId = linearLocationId+2, roadwayNumber = roadwayNumber+2)

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
      val (_, unchanged) = list.partition(_.status == RoadAddressChangeType.Termination)
      val ordered = ProjectSectionCalculator.assignAddrMValues(unchanged)
      ordered.find(_.linkId == idRoad2) should be(None)
      ordered.head.startAddrMValue should be(0L)
      ordered.last.endAddrMValue should be(19L)
      ordered.count(_.calibrationPoints._1.nonEmpty) should be(1)
      ordered.count(_.calibrationPoints._2.nonEmpty) should be(1)
    }
  }

  test("Test assignAddrMValues When giving new + transfer links Then Project section calculator should assign values properly") {
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
      val geom3 = Seq(Point(0.0, 9.8), Point(0.0, 20.2))

      val projectLink0 = toProjectLink(rap, RoadAddressChangeType.Transfer)(RoadAddress(idRoad0, 0, RoadPart(5, 1), AdministrativeClass.Unknown, Track.Combined, Discontinuity.Continuous,     0L, 12L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), 12345L.toString, 0.0, GeometryUtils.geometryLength(geom0), SideCode.TowardsDigitizing, 0, (None, None), geom0, LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
        .copy(projectId = rap.id, roadwayId = roadwayId, linearLocationId = linearLocationId, roadwayNumber = roadwayNumber)
      val projectLink1 = toProjectLink(rap, RoadAddressChangeType.New)     (RoadAddress(idRoad1, 0, RoadPart(5, 1), AdministrativeClass.Unknown, Track.Combined, Discontinuity.Continuous,     0L,  0L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), 12346L.toString, 0.0, GeometryUtils.geometryLength(geom1), SideCode.TowardsDigitizing, 0, (None, None), geom1, LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
        .copy(projectId = rap.id)
      val projectLink2 = toProjectLink(rap, RoadAddressChangeType.New)     (RoadAddress(idRoad2, 0, RoadPart(5, 1), AdministrativeClass.Unknown, Track.Combined, Discontinuity.Continuous,     0L,  0L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), 12347L.toString, 0.0, GeometryUtils.geometryLength(geom2), SideCode.TowardsDigitizing, 0, (None, None), geom2, LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
        .copy(projectId = rap.id)
      val projectLink3 = toProjectLink(rap, RoadAddressChangeType.Transfer)(RoadAddress(idRoad3, 0, RoadPart(5, 1), AdministrativeClass.Unknown, Track.Combined, Discontinuity.Discontinuous, 12L, 24L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), 12348L.toString, 0.0, GeometryUtils.geometryLength(geom3), SideCode.TowardsDigitizing, 0, (None, None), geom3, LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
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
      val projectLinksWithNewAddrMValues = ProjectSectionCalculator.assignAddrMValues(projectLinkSeqN ++ projectLinkSeqT)
      projectLinksWithNewAddrMValues.length should be(4)
      val updatedProjectLink0 = projectLinksWithNewAddrMValues.find(_.id == projectLink0.id)
      val updatedProjectLink1 = projectLinksWithNewAddrMValues.find(_.id == projectLink1.id)
      val updatedProjectLink2 = projectLinksWithNewAddrMValues.find(_.id == projectLink2.id)
      val updatedProjectLink3 = projectLinksWithNewAddrMValues.find(_.id == projectLink3.id)
      val maxAddr = projectLinksWithNewAddrMValues.map(_.endAddrMValue).max
      projectLinksWithNewAddrMValues.filter(_.id == idRoad0).foreach { r =>
        r.calibrationPoints should be(None, None)
        // new value = original + (new end - old end)
        r.startAddrMValue should be(updatedProjectLink0.get.startAddrMValue + maxAddr - updatedProjectLink3.get.endAddrMValue)
        r.endAddrMValue should be(updatedProjectLink0.get.endAddrMValue + maxAddr - updatedProjectLink3.get.endAddrMValue)
      }
      projectLinksWithNewAddrMValues.filter(_.id == idRoad3).foreach { r =>
        r.calibrationPoints should be(None, Some(ProjectCalibrationPoint(12348.toString, Math.round(10.399999999999999 * 10.0) / 10.0, 44, RoadAddressCP)))
        r.startAddrMValue should be(maxAddr + updatedProjectLink3.get.startAddrMValue - updatedProjectLink3.get.endAddrMValue)
        r.endAddrMValue should be(maxAddr)
      }

      projectLinksWithNewAddrMValues.head.calibrationPoints should be(Some(ProjectCalibrationPoint(12347.toString, 0.0, 0, RoadAddressCP)), None)
    }
  }

  test("Test assignAddrMValues When there is a MinorDiscontinuity at end of address Then start of next one with 2 tracks (Left and Right) should also have calibration points, 4 in total") {
    runWithRollback{
      // Left track = 85.308 meters
      val idRoad0 = 0L.toString //   L>
      val idRoad1 = 1L.toString //   L>
      val idRoad2 = 2L.toString //   L>
      val idRoad3 = 3L.toString //   L>
      // Right track = 83.154 meters
      val idRoad4 = 4L.toString //   R>
      val idRoad5 = 5L.toString //   R>
      val projectLink0 = toProjectLink(rap, RoadAddressChangeType.New)(RoadAddress(idRoad0.toLong, 0, RoadPart(5, 1), AdministrativeClass.Unknown, Track.LeftSide,  Discontinuity.Continuous,         0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad0, 0.0, 9.43, SideCode.TowardsDigitizing, 0, (None, None), Seq(Point( 20.0, 10.0), Point( 28, 15  )), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink1 = toProjectLink(rap, RoadAddressChangeType.New)(RoadAddress(idRoad1.toLong, 0, RoadPart(5, 1), AdministrativeClass.Unknown, Track.LeftSide,  Discontinuity.MinorDiscontinuity, 0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad1, 0.0, 13.45, SideCode.TowardsDigitizing, 0, (None, None), Seq(Point( 28,   15  ), Point( 41, 18  )), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink2 = toProjectLink(rap, RoadAddressChangeType.New)(RoadAddress(idRoad2.toLong, 0, RoadPart(5, 1), AdministrativeClass.Unknown, Track.LeftSide,  Discontinuity.Continuous,         0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad2, 0.0, 34.05, SideCode.TowardsDigitizing, 0, (None, None), Seq(Point( 42,   19  ), Point( 75, 29.2)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink3 = toProjectLink(rap, RoadAddressChangeType.New)(RoadAddress(idRoad3.toLong, 0, RoadPart(5, 1), AdministrativeClass.Unknown, Track.LeftSide,  Discontinuity.Continuous,         0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad3, 0.0, 30.67, SideCode.TowardsDigitizing, 0, (None, None), Seq(Point(103.0, 15.0), Point( 75, 29.2)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink4 = toProjectLink(rap, RoadAddressChangeType.New)(RoadAddress(idRoad4.toLong, 0, RoadPart(5, 1), AdministrativeClass.Unknown, Track.RightSide, Discontinuity.MinorDiscontinuity, 0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad4, 0.0, 22.02, SideCode.TowardsDigitizing, 0, (None, None), Seq(Point( 20.0, 10.0), Point( 42, 11  )), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink5 = toProjectLink(rap, RoadAddressChangeType.New)(RoadAddress(idRoad5.toLong, 0, RoadPart(5, 1), AdministrativeClass.Unknown, Track.RightSide, Discontinuity.Continuous,         0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad5, 0.0, 61.13, SideCode.TowardsDigitizing, 0, (None, None), Seq(Point( 42,   11  ), Point(103, 15  )), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val list = List(projectLink0, projectLink1, projectLink2, projectLink3, projectLink4, projectLink5)
      val ordered = ProjectSectionCalculator.assignAddrMValues(list)
      // check left and right track - should have 4 calibration points: start, end, minor discontinuity and next one after discontinuity
      (ordered.filter(_.track == Track.LeftSide).flatMap(_.calibrationPoints._1) ++ ordered.filter(_.track == Track.LeftSide).flatMap(_.calibrationPoints._2)) should have size 4
      (ordered.filter(_.track == Track.RightSide).flatMap(_.calibrationPoints._1) ++ ordered.filter(_.track == Track.RightSide).flatMap(_.calibrationPoints._2)) should have size 4
    }
  }

  test("Test assignAddrMValues When there is a Discontinuity at end of address Then start of next one with 2 tracks (Left and Right) should also have calibration points, 4 in total") {
    runWithRollback {
      // Left track = 85.308 meters
      val idRoad0 = 0L.toString //   L>
      val idRoad1 = 1L.toString //   L>
      val idRoad2 = 2L.toString //   L>
      val idRoad3 = 3L.toString //   L>
      // Right track = 83.154 meters
      val idRoad4 = 4L.toString //   R>
      val idRoad5 = 5L.toString //   R>
      val projectLink0 = toProjectLink(rap, RoadAddressChangeType.New)(RoadAddress(idRoad0.toLong, 0, RoadPart(5, 1), AdministrativeClass.Unknown, Track.LeftSide,  Discontinuity.Continuous,         0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad0, 0.0, 9.43, SideCode.TowardsDigitizing, 0, (None, None), Seq(Point( 20.0, 10.0), Point( 28, 15  )), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink1 = toProjectLink(rap, RoadAddressChangeType.New)(RoadAddress(idRoad1.toLong, 0, RoadPart(5, 1), AdministrativeClass.Unknown, Track.LeftSide,  Discontinuity.MinorDiscontinuity, 0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad1, 0.0, 13.60, SideCode.TowardsDigitizing, 0, (None, None), Seq(Point( 28,   15  ), Point( 41, 18  )), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink2 = toProjectLink(rap, RoadAddressChangeType.New)(RoadAddress(idRoad2.toLong, 0, RoadPart(5, 1), AdministrativeClass.Unknown, Track.LeftSide,  Discontinuity.Continuous,         0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad2, 0.0, 35.97, SideCode.TowardsDigitizing, 0, (None, None), Seq(Point( 42,   19  ), Point( 75, 29.2)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink3 = toProjectLink(rap, RoadAddressChangeType.New)(RoadAddress(idRoad3.toLong, 0, RoadPart(5, 1), AdministrativeClass.Unknown, Track.LeftSide,  Discontinuity.Continuous,         0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad3, 0.0, 30.89, SideCode.AgainstDigitizing, 0, (None, None), Seq(Point(103.0, 15.0), Point( 75, 29.2)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink4 = toProjectLink(rap, RoadAddressChangeType.New)(RoadAddress(idRoad4.toLong, 0, RoadPart(5, 1), AdministrativeClass.Unknown, Track.RightSide, Discontinuity.Continuous,         0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad4, 0.0, 22.02, SideCode.TowardsDigitizing, 0, (None, None), Seq(Point( 20.0, 10.0), Point( 42, 11  )), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink5 = toProjectLink(rap, RoadAddressChangeType.New)(RoadAddress(idRoad5.toLong, 0, RoadPart(5, 1), AdministrativeClass.Unknown, Track.RightSide, Discontinuity.Continuous,         0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad5, 0.0, 61.61, SideCode.TowardsDigitizing, 0, (None, None), Seq(Point( 42,   11  ), Point(103, 15  )), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val list = List(projectLink0, projectLink1, projectLink2, projectLink3, projectLink4, projectLink5)
      val ordered = ProjectSectionCalculator.assignAddrMValues(list)
      // check left and right track - should have 4 calibration points: start, end, discontinuity and next one after discontinuity
      (ordered.filter(_.track == Track.LeftSide).flatMap(_.calibrationPoints._1) ++ ordered.filter(_.track == Track.LeftSide).flatMap(_.calibrationPoints._2)) should have size 4
      (ordered.filter(_.track == Track.RightSide).flatMap(_.calibrationPoints._1) ++ ordered.filter(_.track == Track.RightSide).flatMap(_.calibrationPoints._2)) should have size 2
    }
  }

  test("Test assignAddrMValues When there is a MinorDiscontinuity at end of address Then start of next one with 1 track (Combined) should also have calibration points, 4 in total") {
    runWithRollback {
      // Combined track = 85.308 meters with MinorDiscontinuity
      val idRoad0 = 0L.toString //   C>
      val idRoad1 = 1L.toString //   C>
      val idRoad2 = 2L.toString //   C>
      val idRoad3 = 3L.toString //   C>
      val projectLink0 = toProjectLink(rap, RoadAddressChangeType.New)(RoadAddress(idRoad0.toLong, 0, RoadPart(5, 1), AdministrativeClass.State, Track.Combined, Discontinuity.Continuous,         0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad0, 0.0, 9.43, SideCode.TowardsDigitizing, 0, (None, None), Seq(Point( 20.0, 10.0), Point(28, 15  )), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink1 = toProjectLink(rap, RoadAddressChangeType.New)(RoadAddress(idRoad1.toLong, 0, RoadPart(5, 1), AdministrativeClass.State, Track.Combined, Discontinuity.MinorDiscontinuity, 0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad1, 0.0, 15.13, SideCode.TowardsDigitizing, 0, (None, None), Seq(Point( 28,   15  ), Point(41, 18  )), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink2 = toProjectLink(rap, RoadAddressChangeType.New)(RoadAddress(idRoad2.toLong, 0, RoadPart(5, 1), AdministrativeClass.State, Track.Combined, Discontinuity.Continuous,         0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad2, 0.0, 37.18, SideCode.TowardsDigitizing, 0, (None, None), Seq(Point( 42,   19  ), Point(75, 29.2)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink3 = toProjectLink(rap, RoadAddressChangeType.New)(RoadAddress(idRoad3.toLong, 0, RoadPart(5, 1), AdministrativeClass.State, Track.Combined, Discontinuity.Continuous,         0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad3, 0.0, 30.61, SideCode.TowardsDigitizing, 0, (None, None), Seq(Point(103.0, 15.0), Point(75, 29.2)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val list = List(projectLink0, projectLink1, projectLink2, projectLink3)
      val ordered = ProjectSectionCalculator.assignAddrMValues(list)
      // check combined track - should have 4 calibration points: start, end, minor discontinuity and next one after discontinuity
      (ordered.filter(_.track == Track.Combined).flatMap(_.calibrationPoints._1) ++ ordered.filter(_.track == Track.Combined).flatMap(_.calibrationPoints._2)) should have size 4
    }
  }

  test("Test assignAddrMValues When there is a Discontinuity at end of address Then start of next one with 1 track (Combined) should also have calibration points, 4 in total") {
    runWithRollback {
      // Combined track = 85.308 meters with Discontinuous
      val idRoad0 = 0L.toString //   C>
      val idRoad1 = 1L.toString //   C>
      val idRoad2 = 2L.toString //   C>
      val idRoad3 = 3L.toString //   C>
      val projectLink0 = toProjectLink(rap, RoadAddressChangeType.New)(RoadAddress(idRoad0.toLong, 0, RoadPart(5, 1), AdministrativeClass.State, Track.Combined, Discontinuity.Continuous,         0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad0, 0.0, 9.43, SideCode.TowardsDigitizing, 0, (None, None), Seq(Point( 20.0, 10.0), Point(28, 15  )), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink1 = toProjectLink(rap, RoadAddressChangeType.New)(RoadAddress(idRoad1.toLong, 0, RoadPart(5, 1), AdministrativeClass.State, Track.Combined, Discontinuity.MinorDiscontinuity, 0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad1, 0.0, 5.13, SideCode.TowardsDigitizing, 0, (None, None), Seq(Point( 28,   15  ), Point(41, 18  )), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink2 = toProjectLink(rap, RoadAddressChangeType.New)(RoadAddress(idRoad2.toLong, 0, RoadPart(5, 1), AdministrativeClass.State, Track.Combined, Discontinuity.Continuous,         0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad2, 0.0, 37.18, SideCode.TowardsDigitizing, 0, (None, None), Seq(Point( 42,   19  ), Point(75, 29.2)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink3 = toProjectLink(rap, RoadAddressChangeType.New)(RoadAddress(idRoad3.toLong, 0, RoadPart(5, 1), AdministrativeClass.State, Track.Combined, Discontinuity.Continuous,         0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad3, 0.0, 30.61, SideCode.AgainstDigitizing, 0, (None, None), Seq(Point(103.0, 15.0), Point(75, 29.2)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val list = List(projectLink0, projectLink1, projectLink2, projectLink3)
      val ordered = ProjectSectionCalculator.assignAddrMValues(list)
      // check combined track - should have 4 calibration points: start, end, discontinuity and next one after discontinuity
      (ordered.filter(_.track == Track.Combined).flatMap(_.calibrationPoints._1) ++ ordered.filter(_.track == Track.Combined).flatMap(_.calibrationPoints._2)) should have size 4
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
  test("Test assignAddrMValues When adding New link sections to the existing ones Then Direction for those should always be the same as the existing section (which are for e.g. Transfer, Unchanged, Numbering) making the addressMValues be sequential") {
    runWithRollback {
      //(x,y)   -> (x,y)
      val idRoad0 = 0L.toString //   N>  C(0, 90)   ->  (10, 100)
      val idRoad1 = 1L.toString //   N>  C(10, 100) ->  (20, 80)

      val idRoad2 = 2L.toString //   T>  C(20, 80)  ->  (30, 90)
      val idRoad3 = 3L.toString //   T>  L(30, 90)  ->  (50, 20)
      val idRoad4 = 4L.toString //   T>  R(30, 90)  ->  (40, 10)
      val idRoad5 = 5L.toString //   T>  L(50, 20)  ->  (60, 30)
      val idRoad6 = 6L.toString //   T>  R(40, 10)  ->  (60, 20)

      val idRoad7 = 7L.toString //   N>  L(60, 30)  ->  (70, 40)
      val idRoad8 = 8L.toString //   N>  R(60, 20)  ->  (70, 30)
      val idRoad9 = 9L.toString //   N>  L(70, 40)  ->  (80, 50)
      val idRoad10 = 10L.toString // N>  R(70, 30)  ->  (80, 50)
      val idRoad11 = 11L.toString // N>  C(80, 50)  ->  (90, 60)
      val roadwayId = Sequences.nextRoadwayId
      val roadwayNumber = Sequences.nextRoadwayNumber
      val linearLocationId = Sequences.nextLinearLocationId

      //NEW
      val projectLink0 = toProjectLink(rap, RoadAddressChangeType.New)(RoadAddress(idRoad0.toLong, 0, RoadPart(5, 1), AdministrativeClass.Unknown, Track.Combined, Discontinuity.Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), idRoad0, 0.0, 14.1, SideCode.TowardsDigitizing, 0, (None, None), Seq(Point( 0.0,  90.0), Point(10.0, 100.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0)).copy(projectId = rap.id)
      val projectLink1 = toProjectLink(rap, RoadAddressChangeType.New)(RoadAddress(idRoad1.toLong, 0, RoadPart(5, 1), AdministrativeClass.Unknown, Track.Combined, Discontinuity.Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), idRoad1, 0.0, 22.3, SideCode.TowardsDigitizing, 0, (None, None), Seq(Point(10.0, 100.0), Point(20.0,  80.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0)).copy(projectId = rap.id)

      //TRANSFER
      val projectLink2 = toProjectLink(rap, RoadAddressChangeType.Transfer)(RoadAddress(idRoad2.toLong, 0, RoadPart(5, 1), AdministrativeClass.Unknown, Track.Combined,  Discontinuity.Continuous,  0L,  14L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), idRoad2, 0.0, 14.1, SideCode.TowardsDigitizing, 0, (None, None), Seq(Point(20.0, 80.0), Point(30.0, 90.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0)).copy(projectId = rap.id, roadwayId = roadwayId,     linearLocationId = linearLocationId,      roadwayNumber = roadwayNumber)
      val projectLink3 = toProjectLink(rap, RoadAddressChangeType.Transfer)(RoadAddress(idRoad3.toLong, 0, RoadPart(5, 1), AdministrativeClass.Unknown, Track.LeftSide,  Discontinuity.Continuous, 14L,  87L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), idRoad3, 0.0, 72.8, SideCode.AgainstDigitizing, 0, (None, None), Seq(Point(30.0, 90.0), Point(50.0, 20.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0)).copy(projectId = rap.id, roadwayId = roadwayId + 7, linearLocationId = linearLocationId +  7, roadwayNumber = roadwayNumber + 7)
      val projectLink4 = toProjectLink(rap, RoadAddressChangeType.Transfer)(RoadAddress(idRoad4.toLong, 0, RoadPart(5, 1), AdministrativeClass.Unknown, Track.RightSide, Discontinuity.Continuous, 14L,  95L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), idRoad4, 0.0, 80.6, SideCode.AgainstDigitizing, 0, (None, None), Seq(Point(30.0, 90.0), Point(40.0, 10.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0)).copy(projectId = rap.id, roadwayId = roadwayId + 8, linearLocationId = linearLocationId +  9, roadwayNumber = roadwayNumber + 8)
      val projectLink5 = toProjectLink(rap, RoadAddressChangeType.Transfer)(RoadAddress(idRoad5.toLong, 0, RoadPart(5, 1), AdministrativeClass.Unknown, Track.LeftSide,  Discontinuity.Continuous, 87L, 101L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), idRoad5, 0.0, 14.1, SideCode.TowardsDigitizing, 0, (None, None), Seq(Point(50.0, 20.0), Point(60.0, 30.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0)).copy(projectId = rap.id, roadwayId = roadwayId + 7, linearLocationId = linearLocationId +  8, roadwayNumber = roadwayNumber + 7)
      val projectLink6 = toProjectLink(rap, RoadAddressChangeType.Transfer)(RoadAddress(idRoad6.toLong, 0, RoadPart(5, 1), AdministrativeClass.Unknown, Track.RightSide, Discontinuity.Continuous, 95L, 118L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), idRoad6, 0.0, 22.3, SideCode.TowardsDigitizing, 0, (None, None), Seq(Point(40.0, 10.0), Point(60.0, 20.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0)).copy(projectId = rap.id, roadwayId = roadwayId + 8, linearLocationId = linearLocationId + 10, roadwayNumber = roadwayNumber + 8)

      //NEW
      val projectLink7  = toProjectLink(rap, RoadAddressChangeType.New)(RoadAddress(idRoad7.toLong,  0, RoadPart(5, 1), AdministrativeClass.Unknown, Track.LeftSide,  Discontinuity.Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), idRoad7,  0.0, 14.14, SideCode.TowardsDigitizing, 0, (None, None), Seq(Point(60.0, 30.0), Point(70.0, 40.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0)).copy(projectId = rap.id)
      val projectLink8  = toProjectLink(rap, RoadAddressChangeType.New)(RoadAddress(idRoad8.toLong,  0, RoadPart(5, 1), AdministrativeClass.Unknown, Track.RightSide, Discontinuity.Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), idRoad8,  0.0, 14.14, SideCode.TowardsDigitizing, 0, (None, None), Seq(Point(60.0, 20.0), Point(70.0, 30.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0)).copy(projectId = rap.id)
      val projectLink9  = toProjectLink(rap, RoadAddressChangeType.New)(RoadAddress(idRoad9.toLong,  0, RoadPart(5, 1), AdministrativeClass.Unknown, Track.LeftSide,  Discontinuity.Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), idRoad9,  0.0, 14.14, SideCode.TowardsDigitizing, 0, (None, None), Seq(Point(70.0, 40.0), Point(80.0, 50.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0)).copy(projectId = rap.id)
      val projectLink10 = toProjectLink(rap, RoadAddressChangeType.New)(RoadAddress(idRoad10.toLong, 0, RoadPart(5, 1), AdministrativeClass.Unknown, Track.RightSide, Discontinuity.Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), idRoad10, 0.0, 22.36, SideCode.TowardsDigitizing, 0, (None, None), Seq(Point(70.0, 30.0), Point(80.0, 50.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0)).copy(projectId = rap.id)
      val projectLink11 = toProjectLink(rap, RoadAddressChangeType.New)(RoadAddress(idRoad11.toLong, 0, RoadPart(5, 1), AdministrativeClass.Unknown, Track.Combined,  Discontinuity.Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), idRoad11, 0.0, 14.14, SideCode.TowardsDigitizing, 0, (None, None), Seq(Point(80.0, 50.0), Point(90.0, 60.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0)).copy(projectId = rap.id)


      val (linearCombined,  rwComb    ): (LinearLocation, Roadway) = Seq(projectLink2).map(toRoadwayAndLinearLocation).head
      val (linearLeft1,     rwLeft1   ): (LinearLocation, Roadway) = Seq(projectLink3).map(toRoadwayAndLinearLocation).head
      val (linearLeft2,  _/*rwLeft2*/ ): (LinearLocation, Roadway) = Seq(projectLink5).map(toRoadwayAndLinearLocation).head
      val (linearRight1,    rwRight1  ): (LinearLocation, Roadway) = Seq(projectLink4).map(toRoadwayAndLinearLocation).head
      val (linearRight2, _/*rwRight2*/): (LinearLocation, Roadway) = Seq(projectLink6).map(toRoadwayAndLinearLocation).head
      val rwCombWithId = rwComb.copy(id = roadwayId, ely = 8L)
      val rwLeft1And2WithId  = rwLeft1.copy(id = roadwayId + 7, startAddrMValue = projectLink3.startAddrMValue, endAddrMValue = projectLink5.endAddrMValue, ely = 8L)
      val rwRight1And2WithId = rwRight1.copy(id = roadwayId + 8, startAddrMValue = projectLink4.startAddrMValue, endAddrMValue = projectLink6.endAddrMValue, ely = 8L)
      val linearCombinedWithId = linearCombined.copy(id = linearLocationId)
      val linearLeft1WithId  = linearLeft1.copy(id = linearLocationId + 7)
      val linearLeft2WithId  = linearLeft2.copy(id = linearLocationId + 8)
      val linearRight1WithId = linearRight1.copy(id = linearLocationId + 9)
      val linearRight2WithId = linearRight2.copy(id = linearLocationId + 10)
      val list1 = List(projectLink0, projectLink1, projectLink2, projectLink3, projectLink4, projectLink5, projectLink6)
      val projectLinks = Seq(projectLink0, projectLink1, projectLink2, projectLink3, projectLink4, projectLink5, projectLink6, projectLink7, projectLink8, projectLink9, projectLink10, projectLink11)

      buildTestDataForProject(Some(rap), Some(Seq(rwCombWithId, rwLeft1And2WithId, rwRight1And2WithId)), Some(Seq(linearCombinedWithId, linearLeft1WithId, linearLeft2WithId, linearRight1WithId, linearRight2WithId)),
        Some(list1 ++ Seq(projectLink7, projectLink8, projectLink9, projectLink10, projectLink11)))

      val ordered = ProjectSectionCalculator.assignAddrMValues(projectLinks)
      ordered.minBy(_.startAddrMValue).id.toString should be(idRoad0) // TODO "6 was not equal to 0"
      ordered.maxBy(_.endAddrMValue).id.toString should be(idRoad11)
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
  test("Test assignAddrMValues When adding New link sections with minor discontinuity to the existing ones Then Direction for those should always be the same as the existing section (which are for e.g. Transfer, Unchanged, Numbering) making the addressMValues be sequential") {
    runWithRollback {
      //(x,y)   -> (x,y)
      val idRoad0 = 0L.toString //   N>  C(0, 90)   ->  (10, 100)
      val idRoad1 = 1L.toString //   N>  C(10, 100) ->  (18, 78)

      val idRoad2 = 2L.toString //   T>  C(20, 80)  ->  (30, 90)
      val idRoad3 = 3L.toString //   T>  L(30, 90)  ->  (50, 20)
      val idRoad4 = 4L.toString //   T>  R(30, 90)  ->  (40, 10)
      val idRoad5 = 5L.toString //   T>  L(50, 20)  ->  (60, 30)
      val idRoad6 = 6L.toString //   T>  R(40, 10)  ->  (60, 20)

      val idRoad7 = 7L.toString //   N>  L(60, 30)  ->  (70, 40)
      val idRoad8 = 8L.toString //   N>  R(60, 20)  ->  (70, 30)
      val idRoad9 = 9L.toString //   N>  L(70, 40)  ->  (80, 50)
      val idRoad10 = 10L.toString // N>  R(70, 30)  ->  (80, 50)
      val idRoad11 = 11L.toString // N>  C(80, 50)  ->  (90, 60)
      val roadwayId = Sequences.nextRoadwayId
      val roadwayNumber = Sequences.nextRoadwayNumber
      val linearLocationId = Sequences.nextLinearLocationId

      //NEW
      val projectLink0 = toProjectLink(rap, RoadAddressChangeType.New)(RoadAddress(idRoad0.toLong, 0, RoadPart(5, 1), AdministrativeClass.Unknown, Track.Combined, Discontinuity.Continuous,         0L, 0L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), idRoad0, 0.0, 14.1, SideCode.Unknown, 0, (None, None), Seq(Point( 0.0,  90.0), Point(10.0, 100.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0)).copy(projectId = rap.id)
      val projectLink1 = toProjectLink(rap, RoadAddressChangeType.New)(RoadAddress(idRoad1.toLong, 0, RoadPart(5, 1), AdministrativeClass.Unknown, Track.Combined, Discontinuity.MinorDiscontinuity, 0L, 0L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), idRoad1, 0.0, 22.3, SideCode.Unknown, 0, (None, None), Seq(Point(10.0, 100.0), Point(18.0,  78.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0)).copy(projectId = rap.id)

      //TRANSFER
      val projectLink2 = toProjectLink(rap, RoadAddressChangeType.Transfer)(RoadAddress(idRoad2.toLong, 0, RoadPart(5, 1), AdministrativeClass.Unknown, Track.Combined,  Discontinuity.Continuous,  0L,  14L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), idRoad2, 0.0, 14.1, SideCode.TowardsDigitizing, 0, (None, None), Seq(Point(20.0, 80.0), Point(30.0, 90.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0)).copy(projectId = rap.id, roadwayId = roadwayId,     linearLocationId = linearLocationId,      roadwayNumber = roadwayNumber)
      val projectLink3 = toProjectLink(rap, RoadAddressChangeType.Transfer)(RoadAddress(idRoad3.toLong, 0, RoadPart(5, 1), AdministrativeClass.Unknown, Track.LeftSide,  Discontinuity.Continuous, 14L,  87L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), idRoad3, 0.0, 72.8, SideCode.AgainstDigitizing, 0, (None, None), Seq(Point(30.0, 90.0), Point(50.0, 20.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0)).copy(projectId = rap.id, roadwayId = roadwayId + 7, linearLocationId = linearLocationId +  7, roadwayNumber = roadwayNumber + 7)
      val projectLink4 = toProjectLink(rap, RoadAddressChangeType.Transfer)(RoadAddress(idRoad4.toLong, 0, RoadPart(5, 1), AdministrativeClass.Unknown, Track.RightSide, Discontinuity.Continuous, 14L,  95L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), idRoad4, 0.0, 80.6, SideCode.AgainstDigitizing, 0, (None, None), Seq(Point(30.0, 90.0), Point(40.0, 10.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0)).copy(projectId = rap.id, roadwayId = roadwayId + 8, linearLocationId = linearLocationId +  9, roadwayNumber = roadwayNumber + 8)
      val projectLink5 = toProjectLink(rap, RoadAddressChangeType.Transfer)(RoadAddress(idRoad5.toLong, 0, RoadPart(5, 1), AdministrativeClass.Unknown, Track.LeftSide,  Discontinuity.Continuous, 87L, 101L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), idRoad5, 0.0, 14.1, SideCode.TowardsDigitizing, 0, (None, None), Seq(Point(50.0, 20.0), Point(60.0, 30.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0)).copy(projectId = rap.id, roadwayId = roadwayId + 7, linearLocationId = linearLocationId +  8, roadwayNumber = roadwayNumber + 7)
      val projectLink6 = toProjectLink(rap, RoadAddressChangeType.Transfer)(RoadAddress(idRoad6.toLong, 0, RoadPart(5, 1), AdministrativeClass.Unknown, Track.RightSide, Discontinuity.Continuous, 95L, 118L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), idRoad6, 0.0, 22.3, SideCode.TowardsDigitizing, 0, (None, None), Seq(Point(40.0, 10.0), Point(60.0, 20.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0)).copy(projectId = rap.id, roadwayId = roadwayId + 8, linearLocationId = linearLocationId + 10, roadwayNumber = roadwayNumber + 8)

      //NEW
      val projectLink7 = toProjectLink(rap, RoadAddressChangeType.New) (RoadAddress(idRoad7.toLong,  0, RoadPart(5, 1), AdministrativeClass.Unknown, Track.LeftSide,  Discontinuity.Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), idRoad7,  0.0, 14.14, SideCode.Unknown, 0, (None, None), Seq(Point(60.0, 30.0), Point(70.0, 40.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0)).copy(projectId = rap.id)
      val projectLink8 = toProjectLink(rap, RoadAddressChangeType.New) (RoadAddress(idRoad8.toLong,  0, RoadPart(5, 1), AdministrativeClass.Unknown, Track.RightSide, Discontinuity.Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), idRoad8,  0.0, 14.14, SideCode.Unknown, 0, (None, None), Seq(Point(60.0, 20.0), Point(70.0, 30.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0)).copy(projectId = rap.id)
      val projectLink9 = toProjectLink(rap, RoadAddressChangeType.New) (RoadAddress(idRoad9.toLong,  0, RoadPart(5, 1), AdministrativeClass.Unknown, Track.LeftSide,  Discontinuity.Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), idRoad9,  0.0, 14.14, SideCode.Unknown, 0, (None, None), Seq(Point(70.0, 40.0), Point(80.0, 50.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0)).copy(projectId = rap.id)
      val projectLink10 = toProjectLink(rap, RoadAddressChangeType.New)(RoadAddress(idRoad10.toLong, 0, RoadPart(5, 1), AdministrativeClass.Unknown, Track.RightSide, Discontinuity.Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), idRoad10, 0.0, 22.36, SideCode.Unknown, 0, (None, None), Seq(Point(70.0, 30.0), Point(80.0, 50.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0)).copy(projectId = rap.id)
      val projectLink11 = toProjectLink(rap, RoadAddressChangeType.New)(RoadAddress(idRoad11.toLong, 0, RoadPart(5, 1), AdministrativeClass.Unknown, Track.Combined,  Discontinuity.Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), idRoad11, 0.0, 14.14, SideCode.Unknown, 0, (None, None), Seq(Point(80.0, 50.0), Point(90.0, 60.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0)).copy(projectId = rap.id)


      val (linearCombined, rwComb    ): (LinearLocation, Roadway) = Seq(projectLink2).map(toRoadwayAndLinearLocation).head
      val (linearLeft1,    rwLeft1   ): (LinearLocation, Roadway) = Seq(projectLink3).map(toRoadwayAndLinearLocation).head
      val (linearLeft2, _/*rwLeft2*/ ): (LinearLocation, Roadway) = Seq(projectLink5).map(toRoadwayAndLinearLocation).head
      val (linearRight1,   rwRight1  ): (LinearLocation, Roadway) = Seq(projectLink4).map(toRoadwayAndLinearLocation).head
      val (linearRight2,_/*rwRight2*/): (LinearLocation, Roadway) = Seq(projectLink6).map(toRoadwayAndLinearLocation).head
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

      val ordered = ProjectSectionCalculator.assignAddrMValues(Seq(projectLink0, projectLink1, projectLink2, projectLink3, projectLink4, projectLink5, projectLink6,projectLink7, projectLink8, projectLink9, projectLink10, projectLink11))
      ordered.minBy(_.startAddrMValue).id.toString should be(idRoad0) // TODO "6 was not equal to 0"
      ordered.maxBy(_.endAddrMValue).id.toString should be(idRoad11)
    }
  }

  test("Test assignAddrMValues When adding only New sections Then Direction for those should always be the same (on both Tracks) as the existing section (which are for e.g. Transfer, Unchanged, Numbering) making the addressMValues be sequential") {
    runWithRollback{
      //(x,y)   -> (x,y)
      val idRoad0 = 0L.toString //   N>  L(30, 0)   ->  (30, 10)
      val idRoad1 = 1L.toString //   N>  R(40, 0) ->  (40, 10)
      val idRoad2 = 2L.toString //   N>  L(30, 10)  ->  (30, 20)
      val idRoad3 = 3L.toString //   N>  R(40, 10)  ->  (40, 20)
      val idRoad4 = 4L.toString //   N>  L(40, 10)  ->  (30, 10)
      val idRoad5 = 5L.toString //   N>  L(30, 10)  ->  (10, 10)
      val idRoad6 = 6L.toString //   N>  R(40, 20)  ->  (30, 20)
      val idRoad7 = 7L.toString //   N>  L(10, 10)  ->  (0, 10)
      val idRoad8 = 8L.toString //   N>  R(30, 20)  ->  (10, 20)
      val idRoad9 = 9L.toString //   N>  L(0, 10)  ->  (0, 20)
      val idRoad10 = 10L.toString //   N>  R(10, 20)  ->  (0, 20)
      val idRoad11 = 11L.toString // N>  L(0, 20)  ->  (0, 30)
      val idRoad12 = 12L.toString // N>  R(10, 20)  ->  (10, 30)


      //NEW
      val projectLink0  = toProjectLink(rap, RoadAddressChangeType.New)(RoadAddress(idRoad0.toLong,  0, RoadPart(5, 1), AdministrativeClass.Unknown, Track.LeftSide,  Discontinuity.Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), idRoad0,  0.0, 10.0, SideCode.Unknown, 0, (None, None), Seq(Point(30.0,  0.0), Point(30.0, 10.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink1  = toProjectLink(rap, RoadAddressChangeType.New)(RoadAddress(idRoad1.toLong,  0, RoadPart(5, 1), AdministrativeClass.Unknown, Track.RightSide, Discontinuity.Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), idRoad1,  0.0, 10.0, SideCode.Unknown, 0, (None, None), Seq(Point(40.0,  0.0), Point(40.0, 10.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink2  = toProjectLink(rap, RoadAddressChangeType.New)(RoadAddress(idRoad2.toLong,  0, RoadPart(5, 1), AdministrativeClass.Unknown, Track.LeftSide,  Discontinuity.Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), idRoad2,  0.0, 10.0, SideCode.Unknown, 0, (None, None), Seq(Point(30.0, 10.0), Point(30.0, 20.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink3  = toProjectLink(rap, RoadAddressChangeType.New)(RoadAddress(idRoad3.toLong,  0, RoadPart(5, 1), AdministrativeClass.Unknown, Track.RightSide, Discontinuity.Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), idRoad3,  0.0, 10.0, SideCode.Unknown, 0, (None, None), Seq(Point(40.0, 10.0), Point(40.0, 20.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink4  = toProjectLink(rap, RoadAddressChangeType.New)(RoadAddress(idRoad4.toLong,  0, RoadPart(5, 1), AdministrativeClass.Unknown, Track.LeftSide,  Discontinuity.Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), idRoad4,  0.0, 10.0, SideCode.Unknown, 0, (None, None), Seq(Point(40.0, 10.0), Point(30.0, 10.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink5  = toProjectLink(rap, RoadAddressChangeType.New)(RoadAddress(idRoad5.toLong,  0, RoadPart(5, 1), AdministrativeClass.Unknown, Track.LeftSide,  Discontinuity.Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), idRoad5,  0.0, 20.0, SideCode.Unknown, 0, (None, None), Seq(Point(30.0, 10.0), Point(10.0, 10.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink6  = toProjectLink(rap, RoadAddressChangeType.New)(RoadAddress(idRoad6.toLong,  0, RoadPart(5, 1), AdministrativeClass.Unknown, Track.RightSide, Discontinuity.Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), idRoad6,  0.0, 10.0, SideCode.Unknown, 0, (None, None), Seq(Point(40.0, 20.0), Point(30.0, 20.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink7  = toProjectLink(rap, RoadAddressChangeType.New)(RoadAddress(idRoad7.toLong,  0, RoadPart(5, 1), AdministrativeClass.Unknown, Track.LeftSide,  Discontinuity.Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), idRoad7,  0.0, 10.0, SideCode.Unknown, 0, (None, None), Seq(Point(10.0, 10.0), Point( 0.0, 10.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink8  = toProjectLink(rap, RoadAddressChangeType.New)(RoadAddress(idRoad8.toLong,  0, RoadPart(5, 1), AdministrativeClass.Unknown, Track.RightSide, Discontinuity.Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), idRoad8,  0.0, 20.0, SideCode.Unknown, 0, (None, None), Seq(Point(30.0, 20.0), Point(10.0, 20.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink9  = toProjectLink(rap, RoadAddressChangeType.New)(RoadAddress(idRoad9.toLong,  0, RoadPart(5, 1), AdministrativeClass.Unknown, Track.LeftSide,  Discontinuity.Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), idRoad9,  0.0, 10.0, SideCode.Unknown, 0, (None, None), Seq(Point( 0.0, 10.0), Point( 0.0, 20.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink10 = toProjectLink(rap, RoadAddressChangeType.New)(RoadAddress(idRoad10.toLong, 0, RoadPart(5, 1), AdministrativeClass.Unknown, Track.RightSide, Discontinuity.Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), idRoad10, 0.0, 10.0, SideCode.Unknown, 0, (None, None), Seq(Point(10.0, 20.0), Point( 0.0, 20.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink11 = toProjectLink(rap, RoadAddressChangeType.New)(RoadAddress(idRoad11.toLong, 0, RoadPart(5, 1), AdministrativeClass.Unknown, Track.LeftSide,  Discontinuity.Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), idRoad11, 0.0, 10.0, SideCode.Unknown, 0, (None, None), Seq(Point( 0.0, 20.0), Point( 0.0, 30.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink12 = toProjectLink(rap, RoadAddressChangeType.New)(RoadAddress(idRoad12.toLong, 0, RoadPart(5, 1), AdministrativeClass.Unknown, Track.RightSide, Discontinuity.Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), idRoad12, 0.0, 10.0, SideCode.Unknown, 0, (None, None), Seq(Point(10.0, 20.0), Point(10.0, 30.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))

      val listRight = List(projectLink1, projectLink3, projectLink6, projectLink8, projectLink10, projectLink12)
      val listLeft = List(projectLink0, projectLink2, projectLink4,projectLink5, projectLink7, projectLink9, projectLink11)

      val list = ProjectSectionCalculator.assignAddrMValues(listRight ::: listLeft)
      val (left, right) = list.partition(_.track == Track.LeftSide)

      val (sortedLeft, sortedRight) = (left.sortBy(_.startAddrMValue), right.sortBy(_.startAddrMValue))
      sortedLeft.zip(sortedLeft.tail).forall{
        case (curr, next) => (curr.discontinuity == Discontinuity.Continuous && curr.endAddrMValue == next.startAddrMValue && curr.connected(next)) || curr.discontinuity == Discontinuity.MinorDiscontinuity
      }
      sortedRight.zip(sortedRight.tail).forall{
        case (curr, next) => (curr.discontinuity == Discontinuity.Continuous && curr.endAddrMValue == next.startAddrMValue && curr.connected(next)) || curr.discontinuity == Discontinuity.MinorDiscontinuity
      }

      ((sortedLeft.head.startAddrMValue == 0 && sortedLeft.head.startAddrMValue == 0) || (sortedLeft.last.startAddrMValue == 0 && sortedLeft.last.startAddrMValue == 0)) should be (true)

    }
  }

  test("Test yet another assignAddrMValues When adding only New sections Then Direction for those should always be the same (on both Tracks) as the existing section (which are for e.g. Transfer, Unchanged, Numbering) making the addressMValues be sequential ") {
    runWithRollback{
      //(x,y)   -> (x,y)
      val idRoad0 = 0L.toString //   N>  L(10, 0)   ->  (0, 10)
      val idRoad1 = 1L.toString //   N>  R(20, 0) ->  (15, 10)
      val idRoad2 = 2L.toString //   N>  L(0, 10)  ->  (10, 20)
      val idRoad3 = 3L.toString //   N>  R(15, 10)  ->  (10, 20)
      val idRoad4 = 4L.toString //   N>  L(10, 20)  ->  (40, 30)
      val idRoad5 = 5L.toString //   N>  R(10, 20)  ->  (45, 25)
      val idRoad6 = 6L.toString //   N>  L(40, 30)  ->  (50, 35)
      val idRoad7 = 7L.toString //   N>  R(45, 25)  ->  (55, 30)
      val idRoad8 = 8L.toString //   N>  L(45, 25)  ->  (40, 30)
      val idRoad9 = 9L.toString //   N>  R(55, 30)  ->  (50, 35)
      val idRoad10 = 10L.toString // N>  L(40, 30)  ->  (30, 40)
      val idRoad11 = 11L.toString // N>  R(50, 35)  ->  (40, 50)


      //NEW
      val projectLink0  = toProjectLink(rap, RoadAddressChangeType.New)(RoadAddress(idRoad0.toLong,  0, RoadPart(5, 1), AdministrativeClass.Unknown, Track.LeftSide,  Discontinuity.Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), idRoad0,  0.0, 10.0, SideCode.Unknown, 0, (None, None), Seq(Point(10.0,  0.0), Point( 0.0, 10.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink1  = toProjectLink(rap, RoadAddressChangeType.New)(RoadAddress(idRoad1.toLong,  0, RoadPart(5, 1), AdministrativeClass.Unknown, Track.RightSide, Discontinuity.Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), idRoad1,  0.0, 10.0, SideCode.Unknown, 0, (None, None), Seq(Point(20.0,  0.0), Point(15.0, 10.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink2  = toProjectLink(rap, RoadAddressChangeType.New)(RoadAddress(idRoad2.toLong,  0, RoadPart(5, 1), AdministrativeClass.Unknown, Track.LeftSide,  Discontinuity.Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), idRoad2,  0.0, 10.0, SideCode.Unknown, 0, (None, None), Seq(Point( 0.0, 10.0), Point(10.0, 20.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink3  = toProjectLink(rap, RoadAddressChangeType.New)(RoadAddress(idRoad3.toLong,  0, RoadPart(5, 1), AdministrativeClass.Unknown, Track.RightSide, Discontinuity.Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), idRoad3,  0.0, 10.0, SideCode.Unknown, 0, (None, None), Seq(Point(15.0, 10.0), Point(10.0, 20.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink4  = toProjectLink(rap, RoadAddressChangeType.New)(RoadAddress(idRoad4.toLong,  0, RoadPart(5, 1), AdministrativeClass.Unknown, Track.LeftSide,  Discontinuity.Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), idRoad4,  0.0, 10.0, SideCode.Unknown, 0, (None, None), Seq(Point(10.0, 20.0), Point(40.0, 30.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink5  = toProjectLink(rap, RoadAddressChangeType.New)(RoadAddress(idRoad5.toLong,  0, RoadPart(5, 1), AdministrativeClass.Unknown, Track.RightSide, Discontinuity.Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), idRoad5,  0.0, 20.0, SideCode.Unknown, 0, (None, None), Seq(Point(10.0, 20.0), Point(45.0, 25.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink6  = toProjectLink(rap, RoadAddressChangeType.New)(RoadAddress(idRoad6.toLong,  0, RoadPart(5, 1), AdministrativeClass.Unknown, Track.LeftSide,  Discontinuity.Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), idRoad6,  0.0, 10.0, SideCode.Unknown, 0, (None, None), Seq(Point(40.0, 30.0), Point(50.0, 35.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink7  = toProjectLink(rap, RoadAddressChangeType.New)(RoadAddress(idRoad7.toLong,  0, RoadPart(5, 1), AdministrativeClass.Unknown, Track.RightSide, Discontinuity.Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), idRoad7,  0.0, 10.0, SideCode.Unknown, 0, (None, None), Seq(Point(45.0, 25.0), Point(55.0, 30.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink8  = toProjectLink(rap, RoadAddressChangeType.New)(RoadAddress(idRoad8.toLong,  0, RoadPart(5, 1), AdministrativeClass.Unknown, Track.LeftSide,  Discontinuity.Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), idRoad8,  0.0, 20.0, SideCode.Unknown, 0, (None, None), Seq(Point(45.0, 25.0), Point(40.0, 30.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink9  = toProjectLink(rap, RoadAddressChangeType.New)(RoadAddress(idRoad9.toLong,  0, RoadPart(5, 1), AdministrativeClass.Unknown, Track.RightSide, Discontinuity.Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), idRoad9,  0.0, 10.0, SideCode.Unknown, 0, (None, None), Seq(Point(55.0, 30.0), Point(55.0, 35.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink10 = toProjectLink(rap, RoadAddressChangeType.New)(RoadAddress(idRoad10.toLong, 0, RoadPart(5, 1), AdministrativeClass.Unknown, Track.LeftSide,  Discontinuity.Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), idRoad10, 0.0, 10.0, SideCode.Unknown, 0, (None, None), Seq(Point(40.0, 30.0), Point(30.0, 40.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink11 = toProjectLink(rap, RoadAddressChangeType.New)(RoadAddress(idRoad11.toLong, 0, RoadPart(5, 1), AdministrativeClass.Unknown, Track.RightSide, Discontinuity.Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), idRoad11, 0.0, 10.0, SideCode.Unknown, 0, (None, None), Seq(Point(55.0, 35.0), Point(40.0, 50.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))

      val listRight = List(projectLink1, projectLink3, projectLink5, projectLink7, projectLink9, projectLink11)
      val listLeft = List(projectLink0, projectLink2, projectLink4, projectLink6, projectLink8, projectLink10)

      val list = ProjectSectionCalculator.assignAddrMValues(listRight ::: listLeft)
      val (left, right) = list.partition(_.track == Track.LeftSide)

      val (sortedLeft, sortedRight) = (left.sortBy(_.startAddrMValue), right.sortBy(_.startAddrMValue))
      sortedLeft.zip(sortedLeft.tail).forall{
        case (curr, next) => (curr.discontinuity == Discontinuity.Continuous && curr.endAddrMValue == next.startAddrMValue && curr.connected(next)) || curr.discontinuity == Discontinuity.MinorDiscontinuity
      }
      sortedRight.zip(sortedRight.tail).forall{
        case (curr, next) => (curr.discontinuity == Discontinuity.Continuous && curr.endAddrMValue == next.startAddrMValue && curr.connected(next)) || curr.discontinuity == Discontinuity.MinorDiscontinuity
      }

      ((sortedLeft.head.startAddrMValue == 0 && sortedLeft.head.startAddrMValue == 0) || (sortedLeft.last.startAddrMValue == 0 && sortedLeft.last.startAddrMValue == 0)) should be (true)

    }
  }

  test("Test assignAddrMValues When having square MinorDiscontinuity links Then values should be properly assigned") {
    runWithRollback {
      val idRoad1 = 1L.toString
      val idRoad2 = 2L.toString
      val idRoad3 = 3L.toString
      val idRoad4 = 4L.toString
      val idRoad5 = 5L.toString
      val idRoad6 = 6L.toString
      val idRoad7 = 7L.toString
      val idRoad8 = 8L.toString
      val idRoad9 = 9L.toString

      val geom1 = Seq(Point(188.243, 340.933), Point(257.618, 396.695))
      val geom2 = Seq(Point(193.962, 333.886), Point(263.596, 390.49 ))
      val geom3 = Seq(Point(163.456, 321.325), Point(188.243, 340.933))
      val geom4 = Seq(Point(169.578, 313.621), Point(193.962, 333.886))
      val geom5 = Seq(Point(150.106, 310.28 ), Point(163.456, 321.325))
      val geom6 = Seq(Point(155.553, 303.429), Point(169.578, 313.621))
      val geom7 = Seq(Point(155.553, 303.429), Point(150.106, 310.28 ))
      val geom8 = Seq(Point(162.991, 293.056), Point(155.553, 303.429))
      val geom9 = Seq(Point(370.169,  63.814), Point(162.991, 293.056))
      val projectLink1 = toProjectLink(rap, RoadAddressChangeType.New)(RoadAddress(idRoad1.toLong, 0, RoadPart(5, 1), AdministrativeClass.Municipality, Track.RightSide, Discontinuity.Continuous,         0L, 0L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), 12345L.toString, 0.0,  89.0, SideCode.AgainstDigitizing, 0, (None, None), geom1, LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink2 = toProjectLink(rap, RoadAddressChangeType.New)(RoadAddress(idRoad2.toLong, 0, RoadPart(5, 1), AdministrativeClass.Municipality, Track.LeftSide,  Discontinuity.Continuous,         0L, 0L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), 12346L.toString, 0.0,  89.7, SideCode.AgainstDigitizing, 0, (None, None), geom2, LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink3 = toProjectLink(rap, RoadAddressChangeType.New)(RoadAddress(idRoad3.toLong, 0, RoadPart(5, 1), AdministrativeClass.Municipality, Track.RightSide, Discontinuity.Continuous,         0L, 0L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), 12347L.toString, 0.0,  31.6, SideCode.AgainstDigitizing, 0, (None, None), geom3, LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink4 = toProjectLink(rap, RoadAddressChangeType.New)(RoadAddress(idRoad4.toLong, 0, RoadPart(5, 1), AdministrativeClass.Municipality, Track.LeftSide,  Discontinuity.Continuous,         0L, 0L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), 12348L.toString, 0.0,  31.7, SideCode.AgainstDigitizing, 0, (None, None), geom4, LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink5 = toProjectLink(rap, RoadAddressChangeType.New)(RoadAddress(idRoad5.toLong, 0, RoadPart(5, 1), AdministrativeClass.Municipality, Track.RightSide, Discontinuity.Continuous,         0L, 0L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), 12349L.toString, 0.0,  17.3, SideCode.AgainstDigitizing, 0, (None, None), geom5, LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink6 = toProjectLink(rap, RoadAddressChangeType.New)(RoadAddress(idRoad6.toLong, 0, RoadPart(5, 1), AdministrativeClass.Municipality, Track.LeftSide,  Discontinuity.MinorDiscontinuity, 0L, 0L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), 12350L.toString, 0.0,  17.3, SideCode.AgainstDigitizing, 0, (None, None), geom6, LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink7 = toProjectLink(rap, RoadAddressChangeType.New)(RoadAddress(idRoad7.toLong, 0, RoadPart(5, 1), AdministrativeClass.Municipality, Track.Combined,  Discontinuity.Continuous,         0L, 0L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), 12351L.toString, 0.0,   8.7, SideCode.AgainstDigitizing, 0, (None, None), geom7, LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink8 = toProjectLink(rap, RoadAddressChangeType.New)(RoadAddress(idRoad8.toLong, 0, RoadPart(5, 1), AdministrativeClass.Municipality, Track.Combined,  Discontinuity.Continuous,         0L, 0L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), 12352L.toString, 0.0,  12.8, SideCode.AgainstDigitizing, 0, (None, None), geom8, LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink9 = toProjectLink(rap, RoadAddressChangeType.New)(RoadAddress(idRoad9.toLong, 0, RoadPart(5, 1), AdministrativeClass.Municipality, Track.Combined,  Discontinuity.EndOfRoad,          0L, 0L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), 12353L.toString, 0.0, 308.9, SideCode.AgainstDigitizing, 0, (None, None), geom9, LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))

      val projectLinkSeq = Seq(projectLink1, projectLink2, projectLink3, projectLink4, projectLink5, projectLink6, projectLink7, projectLink8, projectLink9)
      val output = ProjectSectionCalculator.assignAddrMValues(projectLinkSeq)
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

  test("Test assignAddrMValues When having triangular MinorDiscontinuity links Then values should be properly assigned") {
    runWithRollback {
      val idRoad1 = 1L.toString
      val idRoad2 = 2L.toString
      val idRoad3 = 3L.toString
      val idRoad4 = 4L.toString
      val idRoad5 = 5L.toString
      val idRoad6 = 6L.toString
      val idRoad7 = 7L.toString
      val idRoad8 = 8L.toString
      val idRoad9 = 9L.toString
      val idRoad10 = 10L.toString
      val idRoad11 = 11L.toString
      val idRoad12 = 12L.toString
      val idRoad13 = 13L.toString
      val idRoad14 = 14L.toString

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

      val raMap: Map[String, RoadAddress] = Map(
        idRoad1  -> RoadAddress(idRoad1.toLong,  linearLocationId +  1, RoadPart(5, 1), AdministrativeClass.Municipality, Track.LeftSide,  Discontinuity.Continuous,          0L,    9L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), 12345L.toString, 0.0,  10.6,  SideCode.TowardsDigitizing, 0, (None, None), geom1,  LinkGeomSource.NormalLinkInterface, 8, NoTermination, roadwayNumber +  1),
        idRoad2  -> RoadAddress(idRoad2.toLong,  linearLocationId +  2, RoadPart(5, 1), AdministrativeClass.Municipality, Track.LeftSide,  Discontinuity.Continuous,          9L,   38L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), 12346L.toString, 0.0,  33.55, SideCode.TowardsDigitizing, 0, (None, None), geom2,  LinkGeomSource.NormalLinkInterface, 8, NoTermination, roadwayNumber +  2),
        idRoad3  -> RoadAddress(idRoad3.toLong,  linearLocationId +  3, RoadPart(5, 1), AdministrativeClass.Municipality, Track.LeftSide,  Discontinuity.MinorDiscontinuity, 38L,   52L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), 12347L.toString, 0.0,  16.22, SideCode.TowardsDigitizing, 0, (None, None), geom3,  LinkGeomSource.NormalLinkInterface, 8, NoTermination, roadwayNumber +  3),
        idRoad4  -> RoadAddress(idRoad4.toLong,  linearLocationId +  4, RoadPart(5, 1), AdministrativeClass.Municipality, Track.LeftSide,  Discontinuity.Continuous,          52L,  62L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), 12348L.toString, 0.0,  12.1,  SideCode.AgainstDigitizing, 0, (None, None), geom4,  LinkGeomSource.NormalLinkInterface, 8, NoTermination, roadwayNumber +  4),
        idRoad5  -> RoadAddress(idRoad5.toLong,  linearLocationId +  5, RoadPart(5, 1), AdministrativeClass.Municipality, Track.LeftSide,  Discontinuity.Continuous,          62L, 148L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), 12349L.toString, 0.0,  99.51, SideCode.AgainstDigitizing, 0, (None, None), geom5,  LinkGeomSource.NormalLinkInterface, 8, NoTermination, roadwayNumber +  5),
        idRoad6  -> RoadAddress(idRoad6.toLong,  linearLocationId +  6, RoadPart(5, 1), AdministrativeClass.Municipality, Track.LeftSide,  Discontinuity.Continuous,         148L, 165L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), 12350L.toString, 0.0,  19.35, SideCode.AgainstDigitizing, 0, (None, None), geom6,  LinkGeomSource.NormalLinkInterface, 8, NoTermination, roadwayNumber +  6),
        idRoad7  -> RoadAddress(idRoad7.toLong,  linearLocationId +  7, RoadPart(5, 1), AdministrativeClass.Municipality, Track.LeftSide,  Discontinuity.MinorDiscontinuity, 165L, 224L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), 12351L.toString, 0.0,  68.66, SideCode.AgainstDigitizing, 0, (None, None), geom7,  LinkGeomSource.NormalLinkInterface, 8, NoTermination, roadwayNumber +  7),
        idRoad8  -> RoadAddress(idRoad8.toLong,  linearLocationId +  8, RoadPart(5, 1), AdministrativeClass.Municipality, Track.RightSide, Discontinuity.Continuous,           0L,  10L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), 12352L.toString, 0.0,  10.6,  SideCode.TowardsDigitizing, 0, (None, None), geom8,  LinkGeomSource.NormalLinkInterface, 8, NoTermination, roadwayNumber +  8),
        idRoad9  -> RoadAddress(idRoad9.toLong,  linearLocationId +  9, RoadPart(5, 1), AdministrativeClass.Municipality, Track.RightSide, Discontinuity.Continuous,          10L,  41L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), 12353L.toString, 0.0,  34.77, SideCode.TowardsDigitizing, 0, (None, None), geom9,  LinkGeomSource.NormalLinkInterface, 8, NoTermination, roadwayNumber +  9),
        idRoad10 -> RoadAddress(idRoad10.toLong, linearLocationId + 10, RoadPart(5, 1), AdministrativeClass.Municipality, Track.RightSide, Discontinuity.Continuous,          41L,  52L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), 12354L.toString, 0.0,  11.74, SideCode.TowardsDigitizing, 0, (None, None), geom10, LinkGeomSource.NormalLinkInterface, 8, NoTermination, roadwayNumber + 10),
        idRoad11 -> RoadAddress(idRoad11.toLong, linearLocationId + 11, RoadPart(5, 1), AdministrativeClass.Municipality, Track.RightSide, Discontinuity.Continuous,          52L,  56L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), 12355L.toString, 0.0,   4.27, SideCode.TowardsDigitizing, 0, (None, None), geom11, LinkGeomSource.NormalLinkInterface, 8, NoTermination, roadwayNumber + 11),
        idRoad12 -> RoadAddress(idRoad12.toLong, linearLocationId + 12, RoadPart(5, 1), AdministrativeClass.Municipality, Track.RightSide, Discontinuity.Continuous,          56L, 155L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), 12356L.toString, 0.0, 108.7,  SideCode.AgainstDigitizing, 0, (None, None), geom12, LinkGeomSource.NormalLinkInterface, 8, NoTermination, roadwayNumber + 12),
        idRoad13 -> RoadAddress(idRoad13.toLong, linearLocationId + 13, RoadPart(5, 1), AdministrativeClass.Municipality, Track.RightSide, Discontinuity.Continuous,         155L, 173L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), 12357L.toString, 0.0,  19.2,  SideCode.AgainstDigitizing, 0, (None, None), geom13, LinkGeomSource.NormalLinkInterface, 8, NoTermination, roadwayNumber + 13),
        idRoad14 -> RoadAddress(idRoad14.toLong, linearLocationId + 14, RoadPart(5, 1), AdministrativeClass.Municipality, Track.RightSide, Discontinuity.Continuous,         173L, 224L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), 12358L.toString, 0.0,  55.94, SideCode.AgainstDigitizing, 0, (None, None), geom14, LinkGeomSource.NormalLinkInterface, 8, NoTermination, roadwayNumber + 14)
      )


      val projId = Sequences.nextViitePrimaryKeySeqValue
      val project = Project(projId, ProjectState.Incomplete, "f", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
        "", Seq(), Seq(), None, None)

      // Left pls
      val projectLink1 = toProjectLink(rap, RoadAddressChangeType.Transfer)(raMap(idRoad1))
      val projectLink2 = toProjectLink(rap, RoadAddressChangeType.Transfer)(raMap(idRoad2))
      val projectLink3 = toProjectLink(rap, RoadAddressChangeType.Transfer)(raMap(idRoad3))
      val projectLink4 = toProjectLink(rap, RoadAddressChangeType.Transfer)(raMap(idRoad4))
      val projectLink5 = toProjectLink(rap, RoadAddressChangeType.Transfer)(raMap(idRoad5))
      val projectLink6 = toProjectLink(rap, RoadAddressChangeType.Transfer)(raMap(idRoad6))
      val projectLink7 = toProjectLink(rap, RoadAddressChangeType.Transfer)(raMap(idRoad7))

      // Right pls
      val projectLink8  = toProjectLink(rap, RoadAddressChangeType.Transfer)(raMap(idRoad8))
      val projectLink9  = toProjectLink(rap, RoadAddressChangeType.Transfer)(raMap(idRoad9))
      val projectLink10 = toProjectLink(rap, RoadAddressChangeType.Transfer)(raMap(idRoad10))
      val projectLink11 = toProjectLink(rap, RoadAddressChangeType.Transfer)(raMap(idRoad11))
      val projectLink12 = toProjectLink(rap, RoadAddressChangeType.Transfer)(raMap(idRoad12))
      val projectLink13 = toProjectLink(rap, RoadAddressChangeType.Transfer)(raMap(idRoad13))
      val projectLink14 = toProjectLink(rap, RoadAddressChangeType.Transfer)(raMap(idRoad14))


      val leftProjectLinks = Seq(projectLink1, projectLink2, projectLink3, projectLink4, projectLink5, projectLink6, projectLink7).map(_.copy(projectId = projId))
      val rightProjectLinks = Seq(projectLink8, projectLink9, projectLink10,
        projectLink11, projectLink12, projectLink13, projectLink14).map(_.copy(projectId = projId))

      val (leftLinearLocations, leftRoadways) = toRoadwaysAndLinearLocations(leftProjectLinks)
      val (rightLinearLocations, rightRoadways) = toRoadwaysAndLinearLocations(rightProjectLinks)

      buildTestDataForProject(Some(project), Some(leftRoadways ++ rightRoadways), Some(leftLinearLocations ++ rightLinearLocations), Some(leftProjectLinks ++ rightProjectLinks))

      val output = ProjectSectionCalculator.assignAddrMValues(leftProjectLinks ++ rightProjectLinks)

      val splittedLinkCount = output.groupBy(_.connectedLinkId).filterKeys(_.isDefined).mapValues(_.size-1).values.sum
      output.length should be(leftProjectLinks.size + rightProjectLinks.size + splittedLinkCount)

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
  test("Test assignAddrMValues When new link with discontinuity on both sides is added in the between of two other links Then the direction should stay same and new address values should be properly assigned") {
    runWithRollback {
      val idRoad1 = 1L
      val idRoad2 = 2L
      val idRoad3 = 3L

      val geom1 = Seq(Point(0.0, 20.0), Point(0.0, 30.0))
      val geom2 = Seq(Point(50.0, 0.0), Point(40.0, 2.0))
      val geom3 = Seq(Point(20.0, 5.0), Point(10.0, 10.0))

      val projectLink1 = toProjectLink(rap, RoadAddressChangeType.New)(RoadAddress(idRoad1, 0, RoadPart(5, 1), AdministrativeClass.Municipality, Track.Combined, Discontinuity.Continuous,        10L, 20L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), 12345L.toString, 0.0, 10.6, SideCode.TowardsDigitizing, 0, (None, None), geom1, LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink2 = toProjectLink(rap, RoadAddressChangeType.New)(RoadAddress(idRoad2, 0, RoadPart(5, 1), AdministrativeClass.Municipality, Track.Combined, Discontinuity.MinorDiscontinuity, 0L, 10L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), 12346L.toString, 0.0, 11.2, SideCode.TowardsDigitizing, 0, (None, None), geom2, LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink3 = toProjectLink(rap, RoadAddressChangeType.New)(RoadAddress(idRoad3, 0, RoadPart(5, 1), AdministrativeClass.Municipality, Track.Combined, Discontinuity.Continuous,         0L,  0L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), 12347L.toString, 0.0, 12.3, SideCode.Unknown,           0, (None, None), geom3, LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))

      val projectLinkSeq = Seq(projectLink1, projectLink2, projectLink3)

      val output = ProjectSectionCalculator.assignAddrMValues(projectLinkSeq)

      output.length should be(3)

      output.zip(output.tail).foreach {
        case (prev, next) =>
          prev.endAddrMValue should be(next.startAddrMValue)
      }

      output.foreach(pl => {
        (pl.endAddrMValue - pl.startAddrMValue) should be >= 10L
        pl.sideCode should be(SideCode.TowardsDigitizing)
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
  test("Test assignAddrMValues When new link with discontinuity on both sides is added in the between of two other links having calibration points at the beginning and end and discontinuity places Then the direction should stay same and new address values should be properly assigned") {
    runWithRollback {
      val idRoad1 = 1L.toString
      val idRoad2 = 2L.toString
      val idRoad3 = 3L.toString

      val geom1 = Seq(Point(0.0, 20.0), Point(0.0, 30.0))
      val geom2 = Seq(Point(50.0, 0.0), Point(40.0, 2.0))
      val geom3 = Seq(Point(20.0, 5.0), Point(11.0, 10.0))

      val projectLink1 = toProjectLink(rap, RoadAddressChangeType.New)(RoadAddress(idRoad1.toLong, 0, RoadPart(5, 1), AdministrativeClass.Municipality, Track.Combined, Discontinuity.Continuous,        10L, 20L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), 12345L.toString, 0.0, 10.6, SideCode.TowardsDigitizing, 0, (Some(ProjectCalibrationPoint(12345L.toString, 0, 10L)), Some(ProjectCalibrationPoint(12345L.toString, 10.6, 20L))), geom1, LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink2 = toProjectLink(rap, RoadAddressChangeType.New)(RoadAddress(idRoad2.toLong, 0, RoadPart(5, 1), AdministrativeClass.Municipality, Track.Combined, Discontinuity.MinorDiscontinuity, 0L, 10L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), 12346L.toString, 0.0, 11.2, SideCode.TowardsDigitizing, 0, (Some(ProjectCalibrationPoint(12346L.toString, 0,  0L)), Some(ProjectCalibrationPoint(12346L.toString, 11.2, 10L))), geom2, LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink3 = toProjectLink(rap, RoadAddressChangeType.New)(RoadAddress(idRoad3.toLong, 0, RoadPart(5, 1), AdministrativeClass.Municipality, Track.Combined, Discontinuity.Continuous,         0L,  0L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), 12347L.toString, 0.0, 12.3, SideCode.Unknown,           0, (None, None),                                                                                          geom3, LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))

      val projectLinkSeq = Seq(projectLink1, projectLink2, projectLink3)

      val output = ProjectSectionCalculator.assignAddrMValues(projectLinkSeq)

      output.length should be(3)

      output.zip(output.tail).foreach {
        case (prev, next) =>
          prev.endAddrMValue should be(next.startAddrMValue)
      }

      output.foreach(pl => {
        (pl.endAddrMValue - pl.startAddrMValue) should be(10L)
        pl.sideCode should be(SideCode.TowardsDigitizing)
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
  test("Test assignAddrMValues When two new links with discontinuities are added before existing and transfer operation is done last Then the direction should stay same and new address values should be properly assigned") {
    runWithRollback {
      val geom1 = Seq(Point(0.0, 20.0), Point(0.0, 30.0))
      val geom2 = Seq(Point(50.0, 0.0), Point(40.0, 2.0))
      val geom3 = Seq(Point(20.0, 5.0), Point(11.0, 10.0))

      val roadPart = RoadPart(9999, 1)
      val administrativeClass = AdministrativeClass.State
      val track = Track.Combined
      val roadwayId1 = Sequences.nextRoadwayId
      val roadway1 = Roadway(roadwayId1, Sequences.nextRoadwayNumber, roadPart, administrativeClass, track, Discontinuity.Continuous, 0L, 10L, reversed = false, DateTime.parse("2000-01-01"), None, "Test", None, 0, NoTermination)
      roadwayDAO.create(Seq(roadway1))

      val linkId1 = 12345L.toString
      val link1 = Link(linkId1, LinkGeomSource.NormalLinkInterface.value, 0, None)
      LinkDAO.create(link1.id, link1.adjustedTimestamp, link1.source)

      val linearLocationId1 = Sequences.nextLinearLocationId
      val linearLocation1 = LinearLocation(linearLocationId1, 1, linkId1, 0.0, 10.0, SideCode.TowardsDigitizing, 0L, (CalibrationPointReference(Some(0L)), CalibrationPointReference(Some(10L))), geom1, LinkGeomSource.NormalLinkInterface, roadway1.roadwayNumber)
      linearLocationDAO.create(Seq(linearLocation1))

      val plId = Sequences.nextProjectLinkId
      val projectLink1 = ProjectLink(plId + 1, roadPart, track, roadway1.discontinuity,           0L, 10L, roadway1.startAddrMValue, roadway1.endAddrMValue, None, None, None, linkId1, linearLocation1.startMValue, linearLocation1.endMValue, linearLocation1.sideCode, (RoadAddressCP, RoadAddressCP), (RoadAddressCP, RoadAddressCP), linearLocation1.geometry, 0L, RoadAddressChangeType.Transfer, roadway1.administrativeClass, LinkGeomSource.apply(link1.source.intValue()), GeometryUtils.geometryLength(linearLocation1.geometry), roadway1.id, linearLocation1.id, 0, roadway1.reversed, None, 86400L)
      val projectLink2 = ProjectLink(plId + 2, roadPart, track, Discontinuity.MinorDiscontinuity, 0L, 10L, 0L, 0L, None, None, None, 12346L.toString, 0.0, 11.2, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geom2, 0L, RoadAddressChangeType.New, administrativeClass, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geom2), 0L, 0, 0, reversed = false, None, 86400L)
      val projectLink3 = ProjectLink(plId + 3, roadPart, track, Discontinuity.Continuous,        10L, 20L, 0L, 0L, None, None, None, 12347L.toString, 0.0, 12.3, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geom3, 0L, RoadAddressChangeType.New, administrativeClass, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geom3), 0L, 0, 0, reversed = false, None, 86400L)

      val projectLinkSeq = Seq(projectLink1, projectLink2, projectLink3)

      val output = ProjectSectionCalculator.assignAddrMValues(projectLinkSeq)

      output.length should be(3)

      output.zip(output.tail).foreach {
        case (prev, next) =>
          prev.endAddrMValue should be(next.startAddrMValue)
      }

      output.foreach(pl => {
        (pl.endAddrMValue - pl.startAddrMValue) should be(10L)
        pl.sideCode should be(SideCode.TowardsDigitizing)
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
  test("Test assignAddrMValues When new link with discontinuity on both sides is added in the between of two other links (against digitizing) Then the direction should stay same and new address values should be properly assigned") {
    runWithRollback {
      val idRoad1 = 1L.toString
      val idRoad2 = 2L.toString
      val idRoad3 = 3L.toString

      val geom1 = Seq(Point(0.0, 20.0), Point(0.0, 30.0))
      val geom2 = Seq(Point(50.0, 0.0), Point(40.0, 2.0))
      val geom3 = Seq(Point(20.0, 5.0), Point(10.0, 10.0))

      val projectLink1 = toProjectLink(rap, RoadAddressChangeType.New)(RoadAddress(idRoad1.toLong, 0, RoadPart(5, 1), AdministrativeClass.Municipality, Track.Combined, Discontinuity.MinorDiscontinuity, 0L, 10L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), 12345L.toString, 0.0, 10.6, SideCode.AgainstDigitizing, 0, (None, None), geom1, LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink2 = toProjectLink(rap, RoadAddressChangeType.New)(RoadAddress(idRoad2.toLong, 0, RoadPart(5, 1), AdministrativeClass.Municipality, Track.Combined, Discontinuity.Continuous,        10L, 20L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), 12346L.toString, 0.0, 11.2, SideCode.AgainstDigitizing, 0, (None, None), geom2, LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink3 = toProjectLink(rap, RoadAddressChangeType.New)(RoadAddress(idRoad3.toLong, 0, RoadPart(5, 1), AdministrativeClass.Municipality, Track.Combined, Discontinuity.Continuous,         0L,  0L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), 12347L.toString, 0.0, 12.3, SideCode.Unknown,           0, (None, None), geom3, LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))

      val projectLinkSeq = Seq(projectLink1, projectLink2, projectLink3)

      val output = ProjectSectionCalculator.assignAddrMValues(projectLinkSeq)

      output.length should be(3)

      output.zip(output.tail).foreach {
        case (prev, next) =>
          prev.endAddrMValue should be(next.startAddrMValue)
      }

      output.foreach(pl => {
        (pl.endAddrMValue - pl.startAddrMValue) should be >= 10L
        pl.sideCode should be(SideCode.AgainstDigitizing)
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
  test("Test assignAddrMValues When new link with discontinuity on both sides is added in the between of two other links (against digitizing) (geom 2) Then the direction should stay same and new address values should be properly assigned") {
    runWithRollback {
      val geom1 = Seq(Point(0.0, 0.0), Point(0.0, 10.0))
      val geom2 = Seq(Point(30.0, 22.0), Point(40.0, 23.0))
      val geom3 = Seq(Point(10.0, 20.0), Point(20.0, 21.0))

      val roadPart = RoadPart(5, 1)
      val administrativeClass = AdministrativeClass.Municipality
      val track = Track.Combined
      val plId = Sequences.nextProjectLinkId
      val projectLink1 = ProjectLink(plId + 1, roadPart, track, Discontinuity.MinorDiscontinuity, 0L, 10L, 0L, 0L, None, None, None, 12345L.toString, 0.0, 10.0, SideCode.AgainstDigitizing, (NoCP, NoCP), (NoCP, NoCP), geom1, 0L, RoadAddressChangeType.New, administrativeClass, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geom1), 0L, 0, 0, reversed = false, None, 86400L)
      val projectLink2 = ProjectLink(plId + 2, roadPart, track, Discontinuity.MinorDiscontinuity, 0L, 10L, 0L, 0L, None, None, None, 12346L.toString, 0.0, 11.2, SideCode.AgainstDigitizing, (NoCP, NoCP), (NoCP, NoCP), geom2, 0L, RoadAddressChangeType.New, administrativeClass, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geom2), 0L, 0, 0, reversed = false, None, 86400L)
      val projectLink3 = ProjectLink(plId + 3, roadPart, track, Discontinuity.Continuous,        10L, 20L, 0L, 0L, None, None, None, 12347L.toString, 0.0, 12.3, SideCode.AgainstDigitizing, (NoCP, NoCP), (NoCP, NoCP), geom3, 0L, RoadAddressChangeType.New, administrativeClass, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geom3), 0L, 0, 0, reversed = false, None, 86400L)

      val projectLinkSeq = Seq(projectLink1, projectLink2, projectLink3)

      val output = ProjectSectionCalculator.assignAddrMValues(projectLinkSeq)

      output.length should be(3)

      output.zip(output.tail).foreach {
        case (prev, next) =>
          prev.endAddrMValue should be(next.startAddrMValue)
      }

      output.foreach(pl => {
        (pl.endAddrMValue - pl.startAddrMValue) should be >= 10L
        pl.sideCode should be(SideCode.AgainstDigitizing)
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
  test("Test assignAddrMValues When new link with discontinuity on both sides is added in the between of two other links (geom 2) Then the direction should stay same and new address values should be properly assigned") {
    runWithRollback {
      val idRoad2 = 2L
      val idRoad3 = 3L

      val geom1 = Seq(Point(0.0, 0.0), Point(0.0, 10.0))
      val geom2 = Seq(Point(30.0, 22.0), Point(40.0, 23.0))
      val geom3 = Seq(Point(10.0, 20.0), Point(20.0, 21.0))

      val roadPart = RoadPart(5, 1)
      val administrativeClass = AdministrativeClass.Municipality
      val track = Track.Combined
      val roadwayId1 = Sequences.nextRoadwayId
      val roadway1 = Roadway(roadwayId1, Sequences.nextRoadwayNumber, roadPart, administrativeClass, track, Discontinuity.MinorDiscontinuity, 0L, 10L, reversed = false, DateTime.parse("1901-01-01"), None, "tester", None, 8, NoTermination)
      roadwayDAO.create(Seq(roadway1))

      val linkId1 = 12345L.toString
      val link1 = Link(linkId1, LinkGeomSource.NormalLinkInterface.value, 0, None)
      LinkDAO.create(link1.id, link1.adjustedTimestamp, link1.source)

      val linearLocationId1 = Sequences.nextLinearLocationId
      val linearLocation1 = LinearLocation(linearLocationId1, 1, linkId1, 0.0, 10.0, SideCode.TowardsDigitizing, 0L, (CalibrationPointReference(Some(0L)), CalibrationPointReference(Some(10L))), geom1, LinkGeomSource.NormalLinkInterface, roadway1.roadwayNumber)
      linearLocationDAO.create(Seq(linearLocation1))

      val plId = Sequences.nextProjectLinkId
      val projectLink1 = ProjectLink(plId + 1, roadPart, track, roadway1.discontinuity, 0L, 10L, roadway1.startAddrMValue, roadway1.endAddrMValue, None, None, None, linkId1, linearLocation1.startMValue, linearLocation1.endMValue, linearLocation1.sideCode, (RoadAddressCP, RoadAddressCP), (RoadAddressCP, RoadAddressCP), linearLocation1.geometry, 0L, RoadAddressChangeType.Transfer, roadway1.administrativeClass, LinkGeomSource.apply(link1.source.intValue()), GeometryUtils.geometryLength(linearLocation1.geometry), roadway1.id, linearLocation1.id, 0, roadway1.reversed, None, 86400L)

      val projectLink2 = toProjectLink(rap, RoadAddressChangeType.New)(RoadAddress(idRoad2, 0, roadPart, AdministrativeClass.Municipality, Track.Combined, Discontinuity.Continuous, 10L, 20L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), 12346L.toString, 0.0, 11.2, SideCode.TowardsDigitizing, 0, (None, None), geom2, LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink3 = toProjectLink(rap, RoadAddressChangeType.New)(RoadAddress(idRoad3, 0, roadPart, AdministrativeClass.Municipality, Track.Combined, Discontinuity.Continuous,  0L,  0L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), 12347L.toString, 0.0, 12.3, SideCode.Unknown,           0, (None, None), geom3, LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))

      val projectLinkSeq = Seq(projectLink1, projectLink2, projectLink3)

      val output = ProjectSectionCalculator.assignAddrMValues(projectLinkSeq)

      output.length should be(3)

      output.zip(output.tail).foreach {
        case (prev, next) =>
          prev.endAddrMValue should be(next.startAddrMValue)
      }

      output.foreach(pl => {
        (pl.endAddrMValue - pl.startAddrMValue) should be >= 10L
        pl.sideCode should be(SideCode.TowardsDigitizing)
        pl.reversed should be(false)
      }
      )
    }
  }

  test("Test assignAddrMValues " +
    "When a combined road has a loopend is reversed with transfer status" +
    "Then addresses, directions and link ordering should be correct.") {
    /*
        ______________
              |      |
              --------
     */
    runWithRollback {
      val triplePoint = Point(371826, 6669765)

      val geom1 = Seq(Point(372017, 6669721), triplePoint)
      val geom2 = Seq(Point(372017, 6669721), Point(372026, 6669819))
      val geom3 = Seq(Point(372026, 6669819), Point(371880, 6669863))
      val geom4 = Seq(triplePoint, Point(371880, 6669863))
      val geom5 = Seq(Point(371704, 6669673), triplePoint)
      val geom6 = Seq(Point(371637, 6669626), Point(371704, 6669673))

      val plId = Sequences.nextProjectLinkId

      // ProjectLinks after ChangeDirection() and set to correct addresses.
      val projectLinkSeq = Seq(
        ProjectLink(plId + 1, RoadPart(9999, 1), Track.Combined, Discontinuity.Continuous,   0L, 199L, 602L, 801L, None, None, None, 12345L.toString, 0.0, 198.937, SideCode.AgainstDigitizing, (NoCP, NoCP), (RoadAddressCP, NoCP), geom1, 0L, RoadAddressChangeType.Transfer, AdministrativeClass.Municipality, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geom1), 0L, 0, 0, reversed = true, None, 86400L),
        ProjectLink(plId + 2, RoadPart(9999, 1), Track.Combined, Discontinuity.Continuous, 199L, 298L, 503L, 602L, None, None, None, 12346L.toString, 0.0, 98.905, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geom2, 0L, RoadAddressChangeType.Transfer, AdministrativeClass.Municipality, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geom2), 0L, 0, 0, reversed = true, None, 86400L),
        ProjectLink(plId + 3, RoadPart(9999, 1), Track.Combined, Discontinuity.Continuous, 298L, 453L, 348L, 503L, None, None, None, 12347L.toString, 0.0, 154.991, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geom3, 0L, RoadAddressChangeType.Transfer, AdministrativeClass.Municipality, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geom3), 0L, 0, 0, reversed = true, None, 86400L),
        ProjectLink(plId + 4, RoadPart(9999, 1), Track.Combined, Discontinuity.Continuous, 453L, 565L, 236L, 348L, None, None, None, 12348L.toString, 0.0, 112.174, SideCode.AgainstDigitizing, (NoCP, JunctionPointCP), (NoCP, JunctionPointCP), geom4, 0L, RoadAddressChangeType.Transfer, AdministrativeClass.Municipality, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geom4), 0L, 0, 0, reversed = true, None, 86400L),
        ProjectLink(plId + 5, RoadPart(9999, 1), Track.Combined, Discontinuity.Continuous, 565L, 719L,  82L, 236L, None, None, None, 12349L.toString, 0.0, 153.726, SideCode.AgainstDigitizing, (JunctionPointCP, NoCP), (JunctionPointCP, NoCP), geom5, 0L, RoadAddressChangeType.Transfer, AdministrativeClass.Municipality, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geom5), 0L, 0, 0, reversed = true, None, 86400L),
        ProjectLink(plId + 6, RoadPart(9999, 1), Track.Combined, Discontinuity.EndOfRoad,  719L, 801L,   0L,  82L, None, None, None, 12350L.toString, 0.0, 81.851, SideCode.AgainstDigitizing, (NoCP, NoCP), (NoCP, RoadAddressCP), geom6, 0L, RoadAddressChangeType.Transfer, AdministrativeClass.Municipality, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geom6), 0L, 0, 0, reversed = true, None, 86400L)
      )

      val updatedProjectLinks = ProjectSectionCalculator.assignAddrMValues(projectLinkSeq).sortBy(_.startAddrMValue)

      updatedProjectLinks.length should be(6)
      updatedProjectLinks.map(_.linkId) shouldBe sorted

      // Check that correct addresses have not changed.
      updatedProjectLinks.foreach(pl => {
        val projectLinkBefore = projectLinkSeq.find(_.id == pl.id).get
        pl.startAddrMValue should be(projectLinkBefore.startAddrMValue)
        pl.endAddrMValue should be(projectLinkBefore.endAddrMValue)
        pl.sideCode should be(projectLinkBefore.sideCode)
        pl.reversed should be(projectLinkBefore.reversed)
      })
    }
  }

  test("Test assignAddrMValues " +
    "When a long road with one part having discontinuities and track changes is transferred" +
    "Then addresses should be calculated in correct order. ") {
    runWithRollback {
      runUpdateToDb("""INSERT INTO public.project (id,state,"name",created_by,created_date,modified_by,modified_date,add_info,start_date,status_info,coord_x,coord_y,zoom) VALUES
        (1088,1,'40921','silari','2021-09-10 10:52:28.106','silari','2021-09-10 10:52:28.106','','2021-09-11','',370312.481,6670979.546,12)""")
      runUpdateToDb("""INSERT INTO public.project_reserved_road_part (id,road_number,road_part_number,project_id,created_by) VALUES (1121,40921,1,1088,'silari')""")
      runUpdateToDb("""INSERT INTO PROJECT_LINK (ID,PROJECT_ID,TRACK,DISCONTINUITY_TYPE,ROAD_NUMBER,ROAD_PART_NUMBER,START_ADDR_M,END_ADDR_M,CREATED_BY,MODIFIED_BY,CREATED_DATE,MODIFIED_DATE,STATUS,ADMINISTRATIVE_CLASS,ROADWAY_ID,LINEAR_LOCATION_ID,CONNECTED_LINK_ID,ELY,REVERSED,SIDE,START_MEASURE,END_MEASURE,LINK_ID,ADJUSTED_TIMESTAMP,LINK_SOURCE,GEOMETRY,ORIGINAL_START_ADDR_M,ORIGINAL_END_ADDR_M,ROADWAY_NUMBER,START_CALIBRATION_POINT,END_CALIBRATION_POINT,ORIG_START_CALIBRATION_POINT,ORIG_END_CALIBRATION_POINT) VALUES
       (109845,1088,2,5,40921,1,5001,5004,'silari','silari',to_date('13.09.2021','DD.MM.YYYY'),to_date('13.09.2021','DD.MM.YYYY'),3,2,NULL,NULL,  NULL,1,0,2,8.156,11.215,145627,1533770939000,4,ST_GeomFromText('LINESTRING(371976.11 6671478.579 4.054000000003725, 374401.696 6672120.164 12.414999999993597)', 3067),5001,5004,310124270,0,2,0,2)
          """)
      runUpdateToDb("""INSERT INTO project_link (id,project_id,track,discontinuity_type,road_number,road_part_number,start_addr_m,end_addr_m,created_by,modified_by,created_date,modified_date,status,administrative_class,roadway_id,linear_location_id,connected_link_id,ely,reversed,side,start_measure,end_measure,link_id,adjusted_timestamp,link_source,geometry,original_start_addr_m,original_end_addr_m,roadway_number,start_calibration_point,end_calibration_point,orig_start_calibration_point,orig_end_calibration_point) VALUES
       (109840,1088,1,5,40921,1,5001,5012,'silari','silari',to_date('13.09.2021','DD.MM.YYYY'),to_date('13.09.2021','DD.MM.YYYY'),3,2,NULL,NULL,NULL,1,0,2,0.000,11.861,145628,1533770939000,4,ST_GeomFromText('LINESTRING(371976.11 6671478.579 4.054000000003725, 374402.772 6672113.09 12.308993347242131)', 3067),5001,5012,310124269,3,2,3,2)
          """)
      runUpdateToDb("""INSERT INTO project_link (id,project_id,track,discontinuity_type,road_number,road_part_number,start_addr_m,end_addr_m,created_by,modified_by,created_date,modified_date,status,administrative_class,roadway_id,linear_location_id,connected_link_id,ely,reversed,side,start_measure,end_measure,link_id,adjusted_timestamp,link_source,geometry,original_start_addr_m,original_end_addr_m,roadway_number,start_calibration_point,end_calibration_point,orig_start_calibration_point,orig_end_calibration_point) VALUES       (109846,1088,2,5,40921,1,5004,5011,'silari','silari',to_date('13.09.2021','DD.MM.YYYY'),to_date('13.09.2021','DD.MM.YYYY'),3,2,NULL,NULL,NULL,1,0,2,  0.000,  7.895,  145629,1533770939000,4,ST_GeomFromText('LINESTRING(374401.696 6672120.164 12.414999999993597, 374409.563 6672120.826 12.110000000000582)', 3067),5004,5011,310124270,2,0,2,0)""")
      runUpdateToDb("""INSERT INTO project_link (id,project_id,track,discontinuity_type,road_number,road_part_number,start_addr_m,end_addr_m,created_by,modified_by,created_date,modified_date,status,administrative_class,roadway_id,linear_location_id,connected_link_id,ely,reversed,side,start_measure,end_measure,link_id,adjusted_timestamp,link_source,geometry,original_start_addr_m,original_end_addr_m,roadway_number,start_calibration_point,end_calibration_point,orig_start_calibration_point,orig_end_calibration_point) VALUES       (109847,1088,2,5,40921,1,5011,5107,'silari','silari',to_date('13.09.2021','DD.MM.YYYY'),to_date('13.09.2021','DD.MM.YYYY'),3,2,NULL,NULL,NULL,1,0,3,  0.000,103.934,  145622,1533770939000,4,ST_GeomFromText('LINESTRING(374506.404 6672104.05   8.375,             374499.757 6672111.152  8.551999999996042, 374491.332 6672117.738 8.857999999992899, 374482.405 6672122.031 9.188999999998487, 374473.484 6672125.052 9.49199999999837, 374464.826 6672125.782 9.798999999999069, 374452.609 6672125.226 10.269000000000233, 374434.289 6672123.372 11.123999999996158, 374409.563 6672120.826 12.110000000000582)', 3067),5011,5107,310124270,0,0,0,0)""")
      runUpdateToDb("""INSERT INTO project_link (id,project_id,track,discontinuity_type,road_number,road_part_number,start_addr_m,end_addr_m,created_by,modified_by,created_date,modified_date,status,administrative_class,roadway_id,linear_location_id,connected_link_id,ely,reversed,side,start_measure,end_measure,link_id,adjusted_timestamp,link_source,geometry,original_start_addr_m,original_end_addr_m,roadway_number,start_calibration_point,end_calibration_point,orig_start_calibration_point,orig_end_calibration_point) VALUES       (109841,1088,1,5,40921,1,5012,5019,'silari','silari',to_date('13.09.2021','DD.MM.YYYY'),to_date('13.09.2021','DD.MM.YYYY'),3,2,NULL,NULL,NULL,1,0,3,  0.000,  7.222,  145632,1533770939000,4,ST_GeomFromText('LINESTRING(374409.994 6672113.036 12.070999999996275, 374402.772 6672113.09  12.308993347242131)', 3067),5012,5019,310124269,2,0,2,0)""")
      runUpdateToDb("""INSERT INTO project_link (id,project_id,track,discontinuity_type,road_number,road_part_number,start_addr_m,end_addr_m,created_by,modified_by,created_date,modified_date,status,administrative_class,roadway_id,linear_location_id,connected_link_id,ely,reversed,side,start_measure,end_measure,link_id,adjusted_timestamp,link_source,geometry,original_start_addr_m,original_end_addr_m,roadway_number,start_calibration_point,end_calibration_point,orig_start_calibration_point,orig_end_calibration_point) VALUES       (109842,1088,1,5,40921,1,5019,5110,'silari','silari',to_date('13.09.2021','DD.MM.YYYY'),to_date('13.09.2021','DD.MM.YYYY'),3,2,NULL,NULL,NULL,1,0,3,  0.000, 92.946,  145623,1533770939000,4,ST_GeomFromText('LINESTRING(374497.122 6672098.534  8.103000000002794, 374490.222 6672105.483  8.279999999998836, 374482.818 6672111.056 8.595000000001164, 374475.423 6672114.336 9.03200000000652, 374467.015 6672116.34 9.547999999995227, 374458.617 6672115.799 9.967000000004191, 374447.416 6672115.755 10.592000000004191, 374436.984 6672114.696 11.089000000007218, 374421.967 6672114.128 11.688999999998487, 374409.994 6672113.036 12.070999999996275)', 3067),5019,5110,310124269,0,0,0,0)""")
      runUpdateToDb("""INSERT INTO project_link (id,project_id,track,discontinuity_type,road_number,road_part_number,start_addr_m,end_addr_m,created_by,modified_by,created_date,modified_date,status,administrative_class,roadway_id,linear_location_id,connected_link_id,ely,reversed,side,start_measure,end_measure,link_id,adjusted_timestamp,link_source,geometry,original_start_addr_m,original_end_addr_m,roadway_number,start_calibration_point,end_calibration_point,orig_start_calibration_point,orig_end_calibration_point) VALUES       (109848,1088,2,5,40921,1,5107,5117,'silari','silari',to_date('13.09.2021','DD.MM.YYYY'),to_date('13.09.2021','DD.MM.YYYY'),3,2,NULL,NULL,NULL,1,0,3,  0.000, 11.062,11114740,1543649361000,4,ST_GeomFromText('LINESTRING(374511.966 6672094.488  8.46799999999348,  374506.404 6672104.05   8.375)',             3067),5107,5117,310124270,0,2,0,2)""")
      runUpdateToDb("""INSERT INTO project_link (id,project_id,track,discontinuity_type,road_number,road_part_number,start_addr_m,end_addr_m,created_by,modified_by,created_date,modified_date,status,administrative_class,roadway_id,linear_location_id,connected_link_id,ely,reversed,side,start_measure,end_measure,link_id,adjusted_timestamp,link_source,geometry,original_start_addr_m,original_end_addr_m,roadway_number,start_calibration_point,end_calibration_point,orig_start_calibration_point,orig_end_calibration_point) VALUES       (109843,1088,1,5,40921,1,5110,5121,'silari','silari',to_date('13.09.2021','DD.MM.YYYY'),to_date('13.09.2021','DD.MM.YYYY'),3,2,NULL,NULL,NULL,1,0,3,  0.000, 10.970,11114743,1543649361000,4,ST_GeomFromText('LINESTRING(374504.592 6672090.501  8.298999999999069, 374497.122 6672098.534  8.103000000002794)', 3067),5110,5121,310124269,0,2,0,2)""")
      runUpdateToDb("""INSERT INTO project_link (id,project_id,track,discontinuity_type,road_number,road_part_number,start_addr_m,end_addr_m,created_by,modified_by,created_date,modified_date,status,administrative_class,roadway_id,linear_location_id,connected_link_id,ely,reversed,side,start_measure,end_measure,link_id,adjusted_timestamp,link_source,geometry,original_start_addr_m,original_end_addr_m,roadway_number,start_calibration_point,end_calibration_point,orig_start_calibration_point,orig_end_calibration_point) VALUES       (109849,1088,2,4,40921,1,5117,5129,'silari','silari',to_date('13.09.2021','DD.MM.YYYY'),to_date('13.09.2021','DD.MM.YYYY'),3,2,NULL,NULL,NULL,1,0,3,  0.000, 12.092,11114744,1543649361000,4,ST_GeomFromText('LINESTRING(374513.374 6672082.478  8.164999999993597, 374511.966 6672094.488  8.467993679773139)', 3067),5117,5129,310124270,2,3,2,3)""")
      runUpdateToDb("""INSERT INTO project_link (id,project_id,track,discontinuity_type,road_number,road_part_number,start_addr_m,end_addr_m,created_by,modified_by,created_date,modified_date,status,administrative_class,roadway_id,linear_location_id,connected_link_id,ely,reversed,side,start_measure,end_measure,link_id,adjusted_timestamp,link_source,geometry,original_start_addr_m,original_end_addr_m,roadway_number,start_calibration_point,end_calibration_point,orig_start_calibration_point,orig_end_calibration_point) VALUES       (109844,1088,1,5,40921,1,5121,5129,'silari','silari',to_date('13.09.2021','DD.MM.YYYY'),to_date('13.09.2021','DD.MM.YYYY'),3,2,NULL,NULL,NULL,1,0,3,  3.965, 11.895,11114741,1543649361000,4,ST_GeomFromText('LINESTRING(374510.447 6672085.152  8.209666504400488, 374504.592 6672090.501  8.298999513214268)', 3067),5121,5129,310124269,2,0,2,0)""")
      runUpdateToDb("""INSERT INTO project_link (id,project_id,track,discontinuity_type,road_number,road_part_number,start_addr_m,end_addr_m,created_by,modified_by,created_date,modified_date,status,administrative_class,roadway_id,linear_location_id,connected_link_id,ely,reversed,side,start_measure,end_measure,link_id,adjusted_timestamp,link_source,geometry,original_start_addr_m,original_end_addr_m,roadway_number,start_calibration_point,end_calibration_point,orig_start_calibration_point,orig_end_calibration_point) VALUES       (109746,1088,1,5,40921,1,5129,5133,'silari','silari',to_date('13.09.2021','DD.MM.YYYY'),to_date('13.09.2021','DD.MM.YYYY'),3,2,NULL,NULL,NULL,1,0,3,  0.000,  3.965,11114741,1543649361000,4,ST_GeomFromText('LINESTRING(374513.374 6672082.478  8.164999999993597, 374510.447 6672085.152  8.209666504400488)', 3067),5129,5133,310124279,0,2,0,2)""")
      runUpdateToDb("""INSERT INTO project_link (id,project_id,track,discontinuity_type,road_number,road_part_number,start_addr_m,end_addr_m,created_by,modified_by,created_date,modified_date,status,administrative_class,roadway_id,linear_location_id,connected_link_id,ely,reversed,side,start_measure,end_measure,link_id,adjusted_timestamp,link_source,geometry,original_start_addr_m,original_end_addr_m,roadway_number,start_calibration_point,end_calibration_point,orig_start_calibration_point,orig_end_calibration_point) VALUES       (109733,1088,2,5,40921,1,5129,5137,'silari','silari',to_date('13.09.2021','DD.MM.YYYY'),to_date('13.09.2021','DD.MM.YYYY'),3,2,NULL,NULL,NULL,1,0,2,  0.000,  8.383,11114742,1543649361000,4,ST_GeomFromText('LINESTRING(374504.592 6672090.501  8.298999999999069, 374511.966 6672094.488  8.46799999999348)',  3067),5129,5137,310124280,3,2,3,2)""")
      runUpdateToDb("""INSERT INTO project_link (id,project_id,track,discontinuity_type,road_number,road_part_number,start_addr_m,end_addr_m,created_by,modified_by,created_date,modified_date,status,administrative_class,roadway_id,linear_location_id,connected_link_id,ely,reversed,side,start_measure,end_measure,link_id,adjusted_timestamp,link_source,geometry,original_start_addr_m,original_end_addr_m,roadway_number,start_calibration_point,end_calibration_point,orig_start_calibration_point,orig_end_calibration_point) VALUES       (109747,1088,1,5,40921,1,5133,5217,'silari','silari',to_date('13.09.2021','DD.MM.YYYY'),to_date('13.09.2021','DD.MM.YYYY'),3,2,NULL,NULL,NULL,1,0,2,  0.000, 85.746,  145611,1533770939000,4,ST_GeomFromText('LINESTRING(374513.374 6672082.478  8.164999999993597, 374534.634 6672092.34   8.963000000003376, 374545.642 6672098.914 9.331999999994878, 374560.789 6672109.245 9.94999999999709, 374585.244 6672128.545 10.22599999999511)', 3067),5133,5217,310124279,2,0,2,0)""")
      runUpdateToDb("""INSERT INTO project_link (id,project_id,track,discontinuity_type,road_number,road_part_number,start_addr_m,end_addr_m,created_by,modified_by,created_date,modified_date,status,administrative_class,roadway_id,linear_location_id,connected_link_id,ely,reversed,side,start_measure,end_measure,link_id,adjusted_timestamp,link_source,geometry,original_start_addr_m,original_end_addr_m,roadway_number,start_calibration_point,end_calibration_point,orig_start_calibration_point,orig_end_calibration_point) VALUES       (109734,1088,2,5,40921,1,5137,5249,'silari','silari',to_date('13.09.2021','DD.MM.YYYY'),to_date('13.09.2021','DD.MM.YYYY'),3,2,NULL,NULL,NULL,1,0,2,  0.000,113.774,11114736,1543649361000,4,ST_GeomFromText('LINESTRING(374511.966 6672094.488  8.46799999999348,  374514.005 6672095.204  8.53200000000652,  374542.43  6672113.719 9.452999999994063, 374576.7 6672138.292 11.017000000007101, 374603.223 6672158.252 12.038000000000466, 374604.963 6672159.795 12.098865084283974)', 3067),5137,5249,310124280,2,0,2,0)""")
      runUpdateToDb("""INSERT INTO project_link (id,project_id,track,discontinuity_type,road_number,road_part_number,start_addr_m,end_addr_m,created_by,modified_by,created_date,modified_date,status,administrative_class,roadway_id,linear_location_id,connected_link_id,ely,reversed,side,start_measure,end_measure,link_id,adjusted_timestamp,link_source,geometry,original_start_addr_m,original_end_addr_m,roadway_number,start_calibration_point,end_calibration_point,orig_start_calibration_point,orig_end_calibration_point) VALUES       (109748,1088,1,5,40921,1,5217,5230,'silari','silari',to_date('13.09.2021','DD.MM.YYYY'),to_date('13.09.2021','DD.MM.YYYY'),3,2,NULL,NULL,NULL,1,0,2,  0.000, 13.343,  145657,1533770939000,4,ST_GeomFromText('LINESTRING(374585.244 6672128.545 10.22599999999511,  374595.68  6672136.859 10.365999999994528)', 3067),5217,5230,310124279,0,0,0,0)""")
      runUpdateToDb("""INSERT INTO project_link (id,project_id,track,discontinuity_type,road_number,road_part_number,start_addr_m,end_addr_m,created_by,modified_by,created_date,modified_date,status,administrative_class,roadway_id,linear_location_id,connected_link_id,ely,reversed,side,start_measure,end_measure,link_id,adjusted_timestamp,link_source,geometry,original_start_addr_m,original_end_addr_m,roadway_number,start_calibration_point,end_calibration_point,orig_start_calibration_point,orig_end_calibration_point) VALUES       (109749,1088,1,5,40921,1,5230,5249,'silari','silari',to_date('13.09.2021','DD.MM.YYYY'),to_date('13.09.2021','DD.MM.YYYY'),3,2,NULL,NULL,NULL,1,0,2,  0.000, 19.259,  145653,1533770939000,4,ST_GeomFromText('LINESTRING(374595.68  6672136.859 10.365999999994528, 374596.825 6672137.77  11.49199999999837,  374610.602 6672149.034 11.974244816149124)', 3067),5230,5249,310124279,0,0,0,0)""")
      runUpdateToDb("""INSERT INTO project_link (id,project_id,track,discontinuity_type,road_number,road_part_number,start_addr_m,end_addr_m,created_by,modified_by,created_date,modified_date,status,administrative_class,roadway_id,linear_location_id,connected_link_id,ely,reversed,side,start_measure,end_measure,link_id,adjusted_timestamp,link_source,geometry,original_start_addr_m,original_end_addr_m,roadway_number,start_calibration_point,end_calibration_point,orig_start_calibration_point,orig_end_calibration_point) VALUES       (109711,1088,1,5,40921,1,5249,5318,'silari','silari',to_date('13.09.2021','DD.MM.YYYY'),to_date('13.09.2021','DD.MM.YYYY'),3,2,NULL,NULL,NULL,1,0,2, 19.259, 90.212,  145653,1533770939000,4,ST_GeomFromText('LINESTRING(374610.602 6672149.034 11.974244816149124, 374629.051 6672164.117 12.619999999995343, 374662.531 6672195.305 13.437999999994645, 374663.513 6672196.256 11.982000000003609)', 3067),5249,5318,310124287,0,0,0,0)""")
      runUpdateToDb("""INSERT INTO project_link (id,project_id,track,discontinuity_type,road_number,road_part_number,start_addr_m,end_addr_m,created_by,modified_by,created_date,modified_date,status,administrative_class,roadway_id,linear_location_id,connected_link_id,ely,reversed,side,start_measure,end_measure,link_id,adjusted_timestamp,link_source,geometry,original_start_addr_m,original_end_addr_m,roadway_number,start_calibration_point,end_calibration_point,orig_start_calibration_point,orig_end_calibration_point) VALUES       (109809,1088,2,5,40921,1,5249,5315,'silari','silari',to_date('13.09.2021','DD.MM.YYYY'),to_date('13.09.2021','DD.MM.YYYY'),3,2,NULL,NULL,NULL,1,0,2,113.774,181.835,11114736,1543649361000,4,ST_GeomFromText('LINESTRING(374604.963 6672159.795 12.098865084283974, 374627.577 6672179.858 12.889999999999418, 374655.326 6672205.57 13.410999999992782)', 3067),5249,5315,310124288,0,0,0,0)""")
      runUpdateToDb("""INSERT INTO project_link (id,project_id,track,discontinuity_type,road_number,road_part_number,start_addr_m,end_addr_m,created_by,modified_by,created_date,modified_date,status,administrative_class,roadway_id,linear_location_id,connected_link_id,ely,reversed,side,start_measure,end_measure,link_id,adjusted_timestamp,link_source,geometry,original_start_addr_m,original_end_addr_m,roadway_number,start_calibration_point,end_calibration_point,orig_start_calibration_point,orig_end_calibration_point) VALUES       (109810,1088,2,5,40921,1,5315,5429,'silari','silari',to_date('13.09.2021','DD.MM.YYYY'),to_date('13.09.2021','DD.MM.YYYY'),3,2,NULL,NULL,NULL,1,0,2,  0.000,116.199,  145507,1533770939000,4,ST_GeomFromText('LINESTRING(374655.326 6672205.57  13.410999999992782, 374655.858 6672206.063 13.529999999998836, 374688.955 6672237.632 14.085999999995693, 374716.986 6672262.817 14.101999999998952, 374740.651 6672284.434 13.919999999998254)', 3067),5315,5429,310124288,0,0,0,0)""")
      runUpdateToDb("""INSERT INTO project_link (id,project_id,track,discontinuity_type,road_number,road_part_number,start_addr_m,end_addr_m,created_by,modified_by,created_date,modified_date,status,administrative_class,roadway_id,linear_location_id,connected_link_id,ely,reversed,side,start_measure,end_measure,link_id,adjusted_timestamp,link_source,geometry,original_start_addr_m,original_end_addr_m,roadway_number,start_calibration_point,end_calibration_point,orig_start_calibration_point,orig_end_calibration_point) VALUES       (109712,1088,1,5,40921,1,5318,5433,'silari','silari',to_date('13.09.2021','DD.MM.YYYY'),to_date('13.09.2021','DD.MM.YYYY'),3,2,NULL,NULL,NULL,1,0,2,  0.000,116.576,  145506,1533770939000,4,ST_GeomFromText('LINESTRING(374663.513 6672196.256 11.982000000003609, 374685.863 6672217.926 13.90700000000652,  374716.93  6672247.706 14.130000000004657, 374744.834 6672272.89 14.013000000006286, 374748.318 6672276.224 13.948999999993248)', 3067),5318,5433,310124287,0,0,0,0)""")
      runUpdateToDb("""INSERT INTO project_link (id,project_id,track,discontinuity_type,road_number,road_part_number,start_addr_m,end_addr_m,created_by,modified_by,created_date,modified_date,status,administrative_class,roadway_id,linear_location_id,connected_link_id,ely,reversed,side,start_measure,end_measure,link_id,adjusted_timestamp,link_source,geometry,original_start_addr_m,original_end_addr_m,roadway_number,start_calibration_point,end_calibration_point,orig_start_calibration_point,orig_end_calibration_point) VALUES       (109811,1088,2,5,40921,1,5429,5442,'silari','silari',to_date('13.09.2021','DD.MM.YYYY'),to_date('13.09.2021','DD.MM.YYYY'),3,2,NULL,NULL,NULL,1,0,2,  0.000, 13.212,  145556,1533770939000,4,ST_GeomFromText('LINESTRING(374740.651 6672284.434 13.919999999998254, 374750.403 6672293.348 13.803001325767307)', 3067),5429,5442,310124288,0,0,0,0)""")
      runUpdateToDb("""INSERT INTO project_link (id,project_id,track,discontinuity_type,road_number,road_part_number,start_addr_m,end_addr_m,created_by,modified_by,created_date,modified_date,status,administrative_class,roadway_id,linear_location_id,connected_link_id,ely,reversed,side,start_measure,end_measure,link_id,adjusted_timestamp,link_source,geometry,original_start_addr_m,original_end_addr_m,roadway_number,start_calibration_point,end_calibration_point,orig_start_calibration_point,orig_end_calibration_point) VALUES       (109713,1088,1,5,40921,1,5433,5445,'silari','silari',to_date('13.09.2021','DD.MM.YYYY'),to_date('13.09.2021','DD.MM.YYYY'),3,2,NULL,NULL,NULL,1,0,2,  0.000, 12.631,  145555,1533770939000,4,ST_GeomFromText('LINESTRING(374748.318 6672276.224 13.948999999993248, 374757.408 6672284.994 13.91700000000128)',  3067),5433,5445,310124287,0,0,0,0)""")
      runUpdateToDb("""INSERT INTO project_link (id,project_id,track,discontinuity_type,road_number,road_part_number,start_addr_m,end_addr_m,created_by,modified_by,created_date,modified_date,status,administrative_class,roadway_id,linear_location_id,connected_link_id,ely,reversed,side,start_measure,end_measure,link_id,adjusted_timestamp,link_source,geometry,original_start_addr_m,original_end_addr_m,roadway_number,start_calibration_point,end_calibration_point,orig_start_calibration_point,orig_end_calibration_point) VALUES       (109812,1088,2,5,40921,1,5442,5459,'silari','silari',to_date('13.09.2021','DD.MM.YYYY'),to_date('13.09.2021','DD.MM.YYYY'),3,2,NULL,NULL,NULL,1,0,2,  0.000, 17.732,  145546,1533770939000,4,ST_GeomFromText('LINESTRING(374750.403 6672293.348 13.802999999999884, 374752.244 6672295.031 13.793000000005122, 374763.574 6672305.22 13.635999999998603)', 3067),5442,5459,310124288,0,0,0,0)""")
      runUpdateToDb("""INSERT INTO project_link (id,project_id,track,discontinuity_type,road_number,road_part_number,start_addr_m,end_addr_m,created_by,modified_by,created_date,modified_date,status,administrative_class,roadway_id,linear_location_id,connected_link_id,ely,reversed,side,start_measure,end_measure,link_id,adjusted_timestamp,link_source,geometry,original_start_addr_m,original_end_addr_m,roadway_number,start_calibration_point,end_calibration_point,orig_start_calibration_point,orig_end_calibration_point) VALUES       (109714,1088,1,5,40921,1,5445,5463,'silari','silari',to_date('13.09.2021','DD.MM.YYYY'),to_date('13.09.2021','DD.MM.YYYY'),3,2,NULL,NULL,NULL,1,0,2,  0.000, 18.692,  145553,1533770939000,4,ST_GeomFromText('LINESTRING(374757.408 6672284.994 13.91700000000128,  374771.295 6672297.505 13.709000000002561)', 3067),5445,5463,310124287,0,0,0,0)""")
      runUpdateToDb("""INSERT INTO project_link (id,project_id,track,discontinuity_type,road_number,road_part_number,start_addr_m,end_addr_m,created_by,modified_by,created_date,modified_date,status,administrative_class,roadway_id,linear_location_id,connected_link_id,ely,reversed,side,start_measure,end_measure,link_id,adjusted_timestamp,link_source,geometry,original_start_addr_m,original_end_addr_m,roadway_number,start_calibration_point,end_calibration_point,orig_start_calibration_point,orig_end_calibration_point) VALUES       (109813,1088,2,5,40921,1,5459,5669,'silari','silari',to_date('13.09.2021','DD.MM.YYYY'),to_date('13.09.2021','DD.MM.YYYY'),3,2,NULL,NULL,NULL,1,0,2,  0.000,215.127,  145535,1533749925000,4,ST_GeomFromText('LINESTRING(374763.574 6672305.22  13.635999999998603, 374776.85  6672317.147 13.369000000006054, 374804.372 6672342.203 12.760999999998603, 374828.601 6672363.173 11.849000000001979, 374857.417 6672383.015 10.811000000001513, 374892.088 6672402.625 9.525999999998021, 374927.03 6672417.78 8.442999999999302, 374941.446 6672422.312 8.002446431467781)', 3067),5459,5669,310124288,0,0,0,0)""")
      runUpdateToDb("""INSERT INTO project_link (id,project_id,track,discontinuity_type,road_number,road_part_number,start_addr_m,end_addr_m,created_by,modified_by,created_date,modified_date,status,administrative_class,roadway_id,linear_location_id,connected_link_id,ely,reversed,side,start_measure,end_measure,link_id,adjusted_timestamp,link_source,geometry,original_start_addr_m,original_end_addr_m,roadway_number,start_calibration_point,end_calibration_point,orig_start_calibration_point,orig_end_calibration_point) VALUES       (109715,1088,1,5,40921,1,5463,5669,'silari','silari',to_date('13.09.2021','DD.MM.YYYY'),to_date('13.09.2021','DD.MM.YYYY'),3,2,NULL,NULL,NULL,1,0,2,  0.000,209.972,  145533,1533749925000,4,ST_GeomFromText('LINESTRING(374771.295 6672297.505 13.709000000002561, 374774.151 6672300.079 13.668000000005122, 374801.904 6672325.683 13.039000000004307, 374837.298 6672355.733 11.786999999996624, 374869.035 6672377.114 10.601999999998952, 374900.534 6672394.039 9.453999999997905, 374931.537 6672407.906 8.342000000004191, 374944.849 6672412.074 7.95354008401753)', 3067),5463,5669,310124287,0,0,0,0)""")
      runUpdateToDb("""INSERT INTO project_link (id,project_id,track,discontinuity_type,road_number,road_part_number,start_addr_m,end_addr_m,created_by,modified_by,created_date,modified_date,status,administrative_class,roadway_id,linear_location_id,connected_link_id,ely,reversed,side,start_measure,end_measure,link_id,adjusted_timestamp,link_source,geometry,original_start_addr_m,original_end_addr_m,roadway_number,start_calibration_point,end_calibration_point,orig_start_calibration_point,orig_end_calibration_point) VALUES       (109754,1088,1,5,40921,1,5669,5760,'silari','silari',to_date('13.09.2021','DD.MM.YYYY'),to_date('13.09.2021','DD.MM.YYYY'),3,2,NULL,NULL,NULL,1,0,2,209.972,302.279,  145533,1533749925000,4,ST_GeomFromText('LINESTRING(374944.849 6672412.074  7.95354008401753,  374966.114 6672418.733  7.332999999998719, 374994.473 6672424.826 6.657000000006519, 375015.588 6672428.344 6.51600000000326, 375035.117 6672430.143 6.437000295201901)', 3067),5669,5760,310124293,0,0,0,0)""")
      runUpdateToDb("""INSERT INTO project_link (id,project_id,track,discontinuity_type,road_number,road_part_number,start_addr_m,end_addr_m,created_by,modified_by,created_date,modified_date,status,administrative_class,roadway_id,linear_location_id,connected_link_id,ely,reversed,side,start_measure,end_measure,link_id,adjusted_timestamp,link_source,geometry,original_start_addr_m,original_end_addr_m,roadway_number,start_calibration_point,end_calibration_point,orig_start_calibration_point,orig_end_calibration_point) VALUES       (109716,1088,2,5,40921,1,5669,5761,'silari','silari',to_date('13.09.2021','DD.MM.YYYY'),to_date('13.09.2021','DD.MM.YYYY'),3,2,NULL,NULL,NULL,1,0,2,215.127,309.946,  145535,1533749925000,4,ST_GeomFromText('LINESTRING(374941.446 6672422.312  8.002446431467781, 374963.515 6672429.251  7.327999999994063, 375001.538 6672437.673 6.36699999999837, 375034.054 6672441.499 6.486999414300687)', 3067),5669,5761,310124294,0,0,0,0)""")
      runUpdateToDb("""INSERT INTO project_link (id,project_id,track,discontinuity_type,road_number,road_part_number,start_addr_m,end_addr_m,created_by,modified_by,created_date,modified_date,status,administrative_class,roadway_id,linear_location_id,connected_link_id,ely,reversed,side,start_measure,end_measure,link_id,adjusted_timestamp,link_source,geometry,original_start_addr_m,original_end_addr_m,roadway_number,start_calibration_point,end_calibration_point,orig_start_calibration_point,orig_end_calibration_point) VALUES       (109755,1088,1,5,40921,1,5760,5779,'silari','silari',to_date('13.09.2021','DD.MM.YYYY'),to_date('13.09.2021','DD.MM.YYYY'),3,2,NULL,NULL,NULL,1,0,2,  0.000, 19.779,  144575,1533749925000,4,ST_GeomFromText('LINESTRING(375035.117 6672430.143  6.437000000005355, 375054.876 6672431.039  6.30100209553433)',  3067),5760,5779,310124293,0,3,0,3)""")
      runUpdateToDb("""INSERT INTO project_link (id,project_id,track,discontinuity_type,road_number,road_part_number,start_addr_m,end_addr_m,created_by,modified_by,created_date,modified_date,status,administrative_class,roadway_id,linear_location_id,connected_link_id,ely,reversed,side,start_measure,end_measure,link_id,adjusted_timestamp,link_source,geometry,original_start_addr_m,original_end_addr_m,roadway_number,start_calibration_point,end_calibration_point,orig_start_calibration_point,orig_end_calibration_point) VALUES       (109717,1088,2,4,40921,1,5761,5779,'silari','silari',to_date('13.09.2021','DD.MM.YYYY'),to_date('13.09.2021','DD.MM.YYYY'),3,2,NULL,NULL,NULL,1,0,2,  0.000, 18.565,  144569,1533749925000,4,ST_GeomFromText('LINESTRING(375034.054 6672441.499  6.486999999993714, 375051.592 6672442.809  6.305999999996857, 375052.461 6672443.259 6.305000468714075)', 3067),5761,5779,310124294,0,3,0,3)""")
      runUpdateToDb("""INSERT INTO project_link (id,project_id,track,discontinuity_type,road_number,road_part_number,start_addr_m,end_addr_m,created_by,modified_by,created_date,modified_date,status,administrative_class,roadway_id,linear_location_id,connected_link_id,ely,reversed,side,start_measure,end_measure,link_id,adjusted_timestamp,link_source,geometry,original_start_addr_m,original_end_addr_m,roadway_number,start_calibration_point,end_calibration_point,orig_start_calibration_point,orig_end_calibration_point) VALUES       (109794,1088,0,5,40921,1,5779,5787,'silari','silari',to_date('13.09.2021','DD.MM.YYYY'),to_date('13.09.2021','DD.MM.YYYY'),3,2,NULL,NULL,NULL,1,0,2,  0.000,  8.014,  144572,1554332423000,4,ST_GeomFromText('LINESTRING(375054.876 6672431.039  6.301000000006752, 375053.012 6672438.833  6.389999999999418)', 3067),5779,5787,310124297,3,0,3,0)""")
      runUpdateToDb("""INSERT INTO project_link (id,project_id,track,discontinuity_type,road_number,road_part_number,start_addr_m,end_addr_m,created_by,modified_by,created_date,modified_date,status,administrative_class,roadway_id,linear_location_id,connected_link_id,ely,reversed,side,start_measure,end_measure,link_id,adjusted_timestamp,link_source,geometry,original_start_addr_m,original_end_addr_m,roadway_number,start_calibration_point,end_calibration_point,orig_start_calibration_point,orig_end_calibration_point) VALUES      (109795,1088,0,5,40921,1,5787,5791,'silari','silari',to_date('13.09.2021','DD.MM.YYYY'),to_date('13.09.2021','DD.MM.YYYY'),3,2,NULL,NULL,NULL,1,0,2,0.000,  4.460,144571,1533749925000,4,ST_GeomFromText('LINESTRING(375053.012 6672438.833  6.389999999999418, 375052.461 6672443.259  6.305003155545862)', 3067),5787,5791,310124297,0,2,0,2)""")
      runUpdateToDb("""INSERT INTO project_link (id,project_id,track,discontinuity_type,road_number,road_part_number,start_addr_m,end_addr_m,created_by,modified_by,created_date,modified_date,status,administrative_class,roadway_id,linear_location_id,connected_link_id,ely,reversed,side,start_measure,end_measure,link_id,adjusted_timestamp,link_source,geometry,original_start_addr_m,original_end_addr_m,roadway_number,start_calibration_point,end_calibration_point,orig_start_calibration_point,orig_end_calibration_point) VALUES      (109796,1088,0,5,40921,1,5791,5804,'silari','silari',to_date('13.09.2021','DD.MM.YYYY'),to_date('13.09.2021','DD.MM.YYYY'),3,2,NULL,NULL,NULL,1,0,2,0.000, 12.418,144567,1533749925000,4,ST_GeomFromText('LINESTRING(375052.461 6672443.259  6.304999999993015, 375049.879 6672455.406  6.135005296317026)', 3067),5791,5804,310124297,2,0,2,0)""")
      runUpdateToDb("""INSERT INTO project_link (id,project_id,track,discontinuity_type,road_number,road_part_number,start_addr_m,end_addr_m,created_by,modified_by,created_date,modified_date,status,administrative_class,roadway_id,linear_location_id,connected_link_id,ely,reversed,side,start_measure,end_measure,link_id,adjusted_timestamp,link_source,geometry,original_start_addr_m,original_end_addr_m,roadway_number,start_calibration_point,end_calibration_point,orig_start_calibration_point,orig_end_calibration_point) VALUES      (109797,1088,0,5,40921,1,5804,5954,'silari','silari',to_date('13.09.2021','DD.MM.YYYY'),to_date('13.09.2021','DD.MM.YYYY'),3,2,NULL,NULL,NULL,1,0,2,0.000,142.612,144441,1533749925000,4,ST_GeomFromText('LINESTRING(375049.879 6672455.406  6.134999999994761, 375047.389 6672463.393  6.0789999999979045, 375041.689 6672481.29 6.0090000000054715, 375036.273 6672499.217 5.9210000000020955, 375027.124 6672527.821 6.584000000002561, 375020.43 6672547.398 6.710000000006403, 375012.397 6672567.115 6.673999999999069, 375001.306 6672589.212 6.548001778916966)', 3067),5804,5954,310124297,0,0,0,0)""")
      runUpdateToDb("""INSERT INTO project_link (id,project_id,track,discontinuity_type,road_number,road_part_number,start_addr_m,end_addr_m,created_by,modified_by,created_date,modified_date,status,administrative_class,roadway_id,linear_location_id,connected_link_id,ely,reversed,side,start_measure,end_measure,link_id,adjusted_timestamp,link_source,geometry,original_start_addr_m,original_end_addr_m,roadway_number,start_calibration_point,end_calibration_point,orig_start_calibration_point,orig_end_calibration_point) VALUES      (109798,1088,0,5,40921,1,5954,5963,'silari','silari',to_date('13.09.2021','DD.MM.YYYY'),to_date('13.09.2021','DD.MM.YYYY'),3,2,NULL,NULL,NULL,1,0,2,0.000,  8.374,144468,1533749925000,4,ST_GeomFromText('LINESTRING(375001.306 6672589.212  6.547999999995227, 374997.529 6672596.041  6.429000000003725,  374997.258 6672596.543 6.415009453707763)', 3067),5954,5963,310124297,0,0,0,0)""")
      runUpdateToDb("""INSERT INTO project_link (id,project_id,track,discontinuity_type,road_number,road_part_number,start_addr_m,end_addr_m,created_by,modified_by,created_date,modified_date,status,administrative_class,roadway_id,linear_location_id,connected_link_id,ely,reversed,side,start_measure,end_measure,link_id,adjusted_timestamp,link_source,geometry,original_start_addr_m,original_end_addr_m,roadway_number,start_calibration_point,end_calibration_point,orig_start_calibration_point,orig_end_calibration_point) VALUES      (109799,1088,0,5,40921,1,5963,6082,'silari','silari',to_date('13.09.2021','DD.MM.YYYY'),to_date('13.09.2021','DD.MM.YYYY'),3,2,NULL,NULL,NULL,1,0,2,0.000,113.448,144465,1533770939000,4,ST_GeomFromText('LINESTRING(374997.258 6672596.543  6.414999999993597, 374986.487 6672620.547  6.387000000002445,  374973.784 6672646.719 6.783999999999651, 374962.869 6672671.498 7.677999999999884, 374950.911 6672700.066 8.964000000007218)', 3067),5963,6082,310124297,0,0,0,0)""")
      runUpdateToDb("""INSERT INTO project_link (id,project_id,track,discontinuity_type,road_number,road_part_number,start_addr_m,end_addr_m,created_by,modified_by,created_date,modified_date,status,administrative_class,roadway_id,linear_location_id,connected_link_id,ely,reversed,side,start_measure,end_measure,link_id,adjusted_timestamp,link_source,geometry,original_start_addr_m,original_end_addr_m,roadway_number,start_calibration_point,end_calibration_point,orig_start_calibration_point,orig_end_calibration_point) VALUES      (109800,1088,0,5,40921,1,6082,6104,'silari','silari',to_date('13.09.2021','DD.MM.YYYY'),to_date('13.09.2021','DD.MM.YYYY'),3,2,NULL,NULL,NULL,1,0,2,0.000, 21.334,144451,1533770939000,4,ST_GeomFromText('LINESTRING(374950.911 6672700.066  8.964000000007218, 374943.144 6672719.936  9.92699615660005)',  3067),6082,6104,310124297,0,0,0,0)""")
      runUpdateToDb("""INSERT INTO project_link (id,project_id,track,discontinuity_type,road_number,road_part_number,start_addr_m,end_addr_m,created_by,modified_by,created_date,modified_date,status,administrative_class,roadway_id,linear_location_id,connected_link_id,ely,reversed,side,start_measure,end_measure,link_id,adjusted_timestamp,link_source,geometry,original_start_addr_m,original_end_addr_m,roadway_number,start_calibration_point,end_calibration_point,orig_start_calibration_point,orig_end_calibration_point) VALUES      (109801,1088,0,5,40921,1,6104,6120,'silari','silari',to_date('13.09.2021','DD.MM.YYYY'),to_date('13.09.2021','DD.MM.YYYY'),3,2,NULL,NULL,NULL,1,0,2,0.000, 15.145,144452,1533770939000,4,ST_GeomFromText('LINESTRING(374943.144 6672719.936  9.926999999996042, 374938.245 6672734.267 10.460992157936577)', 3067),6104,6120,310124297,0,0,0,0)""")
      runUpdateToDb("""INSERT INTO project_link (id,project_id,track,discontinuity_type,road_number,road_part_number,start_addr_m,end_addr_m,created_by,modified_by,created_date,modified_date,status,administrative_class,roadway_id,linear_location_id,connected_link_id,ely,reversed,side,start_measure,end_measure,link_id,adjusted_timestamp,link_source,geometry,original_start_addr_m,original_end_addr_m,roadway_number,start_calibration_point,end_calibration_point,orig_start_calibration_point,orig_end_calibration_point) VALUES      (109802,1088,0,5,40921,1,6120,6212,'silari','silari',to_date('13.09.2021','DD.MM.YYYY'),to_date('13.09.2021','DD.MM.YYYY'),3,2,NULL,NULL,NULL,1,0,2,0.000, 86.833,144447,1533770939000,4,ST_GeomFromText('LINESTRING(374938.245 6672734.267 10.460999999995693, 374934.424 6672745.711 10.735000000000582, 374927.497 6672764.093 11.048999999999069, 374916.195 6672798.161 11.27400000000489, 374910.212 6672816.437 11.459999129872692)', 3067),6120,6212,310124297,0,0,0,0)""")
      runUpdateToDb("""INSERT INTO project_link (id,project_id,track,discontinuity_type,road_number,road_part_number,start_addr_m,end_addr_m,created_by,modified_by,created_date,modified_date,status,administrative_class,roadway_id,linear_location_id,connected_link_id,ely,reversed,side,start_measure,end_measure,link_id,adjusted_timestamp,link_source,geometry,original_start_addr_m,original_end_addr_m,roadway_number,start_calibration_point,end_calibration_point,orig_start_calibration_point,orig_end_calibration_point) VALUES      (109803,1088,0,5,40921,1,6212,6510,'silari','silari',to_date('13.09.2021','DD.MM.YYYY'),to_date('13.09.2021','DD.MM.YYYY'),3,2,NULL,NULL,NULL,1,0,2,0.000,283.478,145145,1533770939000,4,ST_GeomFromText('LINESTRING(374910.212 6672816.437 11.460000000006403, 374904.103 6672831.09  11.793000000005122, 374894.835 6672857.912 12.755000000004657, 374885.058 6672884.732 14.165999999997439, 374874.686 6672912.971 15.95799999999872, 374856.917 6672954.96 18.589999999996508, 374831.583 6673004.595 21.452999999994063, 374801.474 6673056.274 22.887000000002445, 374791.847 6673073.19 23.014999999999418)', 3067),6212,6510,310124297,0,0,0,0)""")
      runUpdateToDb("""INSERT INTO project_link (id,project_id,track,discontinuity_type,road_number,road_part_number,start_addr_m,end_addr_m,created_by,modified_by,created_date,modified_date,status,administrative_class,roadway_id,linear_location_id,connected_link_id,ely,reversed,side,start_measure,end_measure,link_id,adjusted_timestamp,link_source,geometry,original_start_addr_m,original_end_addr_m,roadway_number,start_calibration_point,end_calibration_point,orig_start_calibration_point,orig_end_calibration_point) VALUES      (109804,1088,0,5,40921,1,6510,6610,'silari','silari',to_date('13.09.2021','DD.MM.YYYY'),to_date('13.09.2021','DD.MM.YYYY'),3,2,NULL,NULL,NULL,1,0,2,0.000, 95.389,145081,1533770939000,4,ST_GeomFromText('LINESTRING(374791.847 6673073.19  23.014999999999418, 374782.855 6673091.231 22.812999999994645, 374773.569 6673114.507 22.37399999999616, 374763.585 6673143.587 21.527000000001863, 374757.33 6673161.978 21.084010339372146)', 3067),6510,6610,310124297,0,0,0,0)""")
      runUpdateToDb("""INSERT INTO project_link (id,project_id,track,discontinuity_type,road_number,road_part_number,start_addr_m,end_addr_m,created_by,modified_by,created_date,modified_date,status,administrative_class,roadway_id,linear_location_id,connected_link_id,ely,reversed,side,start_measure,end_measure,link_id,adjusted_timestamp,link_source,geometry,original_start_addr_m,original_end_addr_m,roadway_number,start_calibration_point,end_calibration_point,orig_start_calibration_point,orig_end_calibration_point) VALUES      (109805,1088,0,5,40921,1,6610,6778,'silari','silari',to_date('13.09.2021','DD.MM.YYYY'),to_date('13.09.2021','DD.MM.YYYY'),3,2,NULL,NULL,NULL,1,0,2,0.000,160.082,145119,1533770939000,4,ST_GeomFromText('LINESTRING(374757.33  6673161.978 21.08400000000256,  374751.836 6673183.346 20.71600000000035, 374747.923 6673207.771 20.562000000005355, 374746.797 6673235.261 20.45100000000093, 374747.839 6673278.776 20.380000000004657, 374747.709 6673302.357 20.187000000005355, 374746.148 6673320.952 20.22400000000198)', 3067),6610,6778,310124297,0,0,0,0)""")
      runUpdateToDb("""INSERT INTO project_link (id,project_id,track,discontinuity_type,road_number,road_part_number,start_addr_m,end_addr_m,created_by,modified_by,created_date,modified_date,status,administrative_class,roadway_id,linear_location_id,connected_link_id,ely,reversed,side,start_measure,end_measure,link_id,adjusted_timestamp,link_source,geometry,original_start_addr_m,original_end_addr_m,roadway_number,start_calibration_point,end_calibration_point,orig_start_calibration_point,orig_end_calibration_point) VALUES      (109806,1088,0,5,40921,1,6778,6828,'silari','silari',to_date('13.09.2021','DD.MM.YYYY'),to_date('13.09.2021','DD.MM.YYYY'),3,2,NULL,NULL,NULL,1,0,2,0.000, 47.741,145132,1533770939000,4,ST_GeomFromText('LINESTRING(374746.148 6673320.952 20.22400000000198,  374740.179 6673344.117 20.085999999995693, 374731.962 6673359.293 19.631999999997788, 374727.892 6673364.44 19.040015098758865)', 3067),6778,6828,310124297,0,0,0,0)""")
      runUpdateToDb("""INSERT INTO project_link (id,project_id,track,discontinuity_type,road_number,road_part_number,start_addr_m,end_addr_m,created_by,modified_by,created_date,modified_date,status,administrative_class,roadway_id,linear_location_id,connected_link_id,ely,reversed,side,start_measure,end_measure,link_id,adjusted_timestamp,link_source,geometry,original_start_addr_m,original_end_addr_m,roadway_number,start_calibration_point,end_calibration_point,orig_start_calibration_point,orig_end_calibration_point) VALUES      (109807,1088,0,5,40921,1,6828,6844,'silari','silari',to_date('13.09.2021','DD.MM.YYYY'),to_date('13.09.2021','DD.MM.YYYY'),3,2,NULL,NULL,NULL,1,0,2,0.000, 15.058,145136,1533770939000,4,ST_GeomFromText('LINESTRING(374727.892 6673364.44  19.039999999993597, 374722.833 6673369.651 18.135999999998603, 374716.694 6673374.455 17.16600249756526)', 3067),6828,6844,310124297,0,0,0,0)""")
      runUpdateToDb("""INSERT INTO project_link (id,project_id,track,discontinuity_type,road_number,road_part_number,start_addr_m,end_addr_m,created_by,modified_by,created_date,modified_date,status,administrative_class,roadway_id,linear_location_id,connected_link_id,ely,reversed,side,start_measure,end_measure,link_id,adjusted_timestamp,link_source,geometry,original_start_addr_m,original_end_addr_m,roadway_number,start_calibration_point,end_calibration_point,orig_start_calibration_point,orig_end_calibration_point) VALUES      (109808,1088,0,4,40921,1,6844,6850,'silari','silari',to_date('13.09.2021','DD.MM.YYYY'),to_date('13.09.2021','DD.MM.YYYY'),3,2,NULL,NULL,NULL,1,0,2,0.000,  5.666,145101,1533770939000,4,ST_GeomFromText('LINESTRING(374716.694 6673374.455 17.16599999999744,  374712.245 6673377.964 18.634928485254022)', 3067),6844,6850,310124297,0,3,0,3)""")
      runUpdateToDb("""INSERT INTO project_link (id,project_id,track,discontinuity_type,road_number,road_part_number,start_addr_m,end_addr_m,created_by,modified_by,created_date,modified_date,status,administrative_class,roadway_id,linear_location_id,connected_link_id,ely,reversed,side,start_measure,end_measure,link_id,adjusted_timestamp,link_source,geometry,original_start_addr_m,original_end_addr_m,roadway_number,start_calibration_point,end_calibration_point,orig_start_calibration_point,orig_end_calibration_point) VALUES      (109759,1088,1,5,40921,1,6850,6877,'silari','silari',to_date('13.09.2021','DD.MM.YYYY'),to_date('13.09.2021','DD.MM.YYYY'),3,2,NULL,NULL,NULL,1,0,2,0.000, 26.638,145121,1533770939000,4,ST_GeomFromText('LINESTRING(374713.379 6673391.764 17.94100000000617,  374727.811 6673414.154 16.740009884322465)', 3067),6850,6877,310124300,3,0,3,0)""")
      runUpdateToDb("""INSERT INTO project_link (id,project_id,track,discontinuity_type,road_number,road_part_number,start_addr_m,end_addr_m,created_by,modified_by,created_date,modified_date,status,administrative_class,roadway_id,linear_location_id,connected_link_id,ely,reversed,side,start_measure,end_measure,link_id,adjusted_timestamp,link_source,geometry,original_start_addr_m,original_end_addr_m,roadway_number,start_calibration_point,end_calibration_point,orig_start_calibration_point,orig_end_calibration_point) VALUES      (109783,1088,2,5,40921,1,6850,6878,'silari','silari',to_date('13.09.2021','DD.MM.YYYY'),to_date('13.09.2021','DD.MM.YYYY'),3,2,NULL,NULL,NULL,1,0,2,0.000, 27.411,145093,1533770939000,4,ST_GeomFromText('LINESTRING(374702.261 6673396.254 17.812999999994645, 374720.042 6673417.115 16.6929999999993)',   3067),6850,6878,310124301,3,0,3,0)""")
      runUpdateToDb("""INSERT INTO project_link (id,project_id,track,discontinuity_type,road_number,road_part_number,start_addr_m,end_addr_m,created_by,modified_by,created_date,modified_date,status,administrative_class,roadway_id,linear_location_id,connected_link_id,ely,reversed,side,start_measure,end_measure,link_id,adjusted_timestamp,link_source,geometry,original_start_addr_m,original_end_addr_m,roadway_number,start_calibration_point,end_calibration_point,orig_start_calibration_point,orig_end_calibration_point) VALUES      (109760,1088,1,5,40921,1,6877,6899,'silari','silari',to_date('13.09.2021','DD.MM.YYYY'),to_date('13.09.2021','DD.MM.YYYY'),3,2,NULL,NULL,NULL,1,0,2,0.000, 20.733,145129,1533770939000,4,ST_GeomFromText('LINESTRING(374727.811 6673414.154 16.74000000000524,  374737.986 6673432.218 15.948999999993248)', 3067),6877,6899,310124300,0,0,0,0)""")
      runUpdateToDb("""INSERT INTO project_link (id,project_id,track,discontinuity_type,road_number,road_part_number,start_addr_m,end_addr_m,created_by,modified_by,created_date,modified_date,status,administrative_class,roadway_id,linear_location_id,connected_link_id,ely,reversed,side,start_measure,end_measure,link_id,adjusted_timestamp,link_source,geometry,original_start_addr_m,original_end_addr_m,roadway_number,start_calibration_point,end_calibration_point,orig_start_calibration_point,orig_end_calibration_point) VALUES      (109784,1088,2,5,40921,1,6878,6900,'silari','silari',to_date('13.09.2021','DD.MM.YYYY'),to_date('13.09.2021','DD.MM.YYYY'),3,2,NULL,NULL,NULL,1,0,2,0.000, 22.510,145128,1533770939000,4,ST_GeomFromText('LINESTRING(374720.042 6673417.115 16.6929999999993,   374731.819 6673436.298 15.703999999997905)', 3067),6878,6900,310124301,0,0,0,0)""")
      runUpdateToDb("""INSERT INTO project_link (id,project_id,track,discontinuity_type,road_number,road_part_number,start_addr_m,end_addr_m,created_by,modified_by,created_date,modified_date,status,administrative_class,roadway_id,linear_location_id,connected_link_id,ely,reversed,side,start_measure,end_measure,link_id,adjusted_timestamp,link_source,geometry,original_start_addr_m,original_end_addr_m,roadway_number,start_calibration_point,end_calibration_point,orig_start_calibration_point,orig_end_calibration_point) VALUES      (109761,1088,1,5,40921,1,6899,6944,'silari','silari',to_date('13.09.2021','DD.MM.YYYY'),to_date('13.09.2021','DD.MM.YYYY'),3,2,NULL,NULL,NULL,1,0,2,0.000, 44.449,145065,1533770939000,4,ST_GeomFromText('LINESTRING(374737.986 6673432.218 15.948999999993248, 374738.559 6673433.243 15.985000000000582, 374756.083 6673472.811 14.078008433456297)', 3067),6899,6944,310124300,0,0,0,0)""")
      runUpdateToDb("""INSERT INTO project_link (id,project_id,track,discontinuity_type,road_number,road_part_number,start_addr_m,end_addr_m,created_by,modified_by,created_date,modified_date,status,administrative_class,roadway_id,linear_location_id,connected_link_id,ely,reversed,side,start_measure,end_measure,link_id,adjusted_timestamp,link_source,geometry,original_start_addr_m,original_end_addr_m,roadway_number,start_calibration_point,end_calibration_point,orig_start_calibration_point,orig_end_calibration_point) VALUES      (109785,1088,2,5,40921,1,6900,6943,'silari','silari',to_date('13.09.2021','DD.MM.YYYY'),to_date('13.09.2021','DD.MM.YYYY'),3,2,NULL,NULL,NULL,1,0,2,0.000, 42.310,145064,1533770939000,4,ST_GeomFromText('LINESTRING(374731.819 6673436.298 15.703999999997905, 374738.216 6673454.834 14.898000000001048, 374745.572 6673476.31 13.96799999999348)', 3067),6900,6943,310124301,0,0,0,0)""")
      runUpdateToDb("""INSERT INTO project_link (id,project_id,track,discontinuity_type,road_number,road_part_number,start_addr_m,end_addr_m,created_by,modified_by,created_date,modified_date,status,administrative_class,roadway_id,linear_location_id,connected_link_id,ely,reversed,side,start_measure,end_measure,link_id,adjusted_timestamp,link_source,geometry,original_start_addr_m,original_end_addr_m,roadway_number,start_calibration_point,end_calibration_point,orig_start_calibration_point,orig_end_calibration_point) VALUES      (109786,1088,2,5,40921,1,6943,7145,'silari','silari',to_date('13.09.2021','DD.MM.YYYY'),to_date('13.09.2021','DD.MM.YYYY'),3,2,NULL,NULL,NULL,1,0,2,0.000,199.479,11939985,1599778815000,4,ST_GeomFromText('LINESTRING(374745.572 6673476.31 13.96799999999348, 374754.321 6673495.793 13.080000000001746, 374766.718 6673523.717 11.854999999995925, 374778.038 6673547.535 10.90700000000652, 374787.199 6673569.522 10.172000000005937, 374799.891 6673597.432 9.554000000003725, 374811.077 6673618.227 9.315000000002328, 374832.069 6673654.99 9.240000000005239, 374832.516 6673655.62 9.237001583064394)', 3067),6943,7145,310124301,0,0,0,0)""")
      runUpdateToDb("""INSERT INTO project_link (id,project_id,track,discontinuity_type,road_number,road_part_number,start_addr_m,end_addr_m,created_by,modified_by,created_date,modified_date,status,administrative_class,roadway_id,linear_location_id,connected_link_id,ely,reversed,side,start_measure,end_measure,link_id,adjusted_timestamp,link_source,geometry,original_start_addr_m,original_end_addr_m,roadway_number,start_calibration_point,end_calibration_point,orig_start_calibration_point,orig_end_calibration_point) VALUES      (109762,1088,1,5,40921,1,6944,7135,'silari','silari',to_date('13.09.2021','DD.MM.YYYY'),to_date('13.09.2021','DD.MM.YYYY'),3,2,NULL,NULL,NULL,1,0,2,0.000,186.259,11939986,1599778815000,4,ST_GeomFromText('LINESTRING(374756.083 6673472.811 14.077999999994063, 374767.043 6673497.21 12.971999999994296, 374777.791 6673522.621 11.865999999994528, 374789.725 6673547.739 10.820000000006985, 374800.701 6673572.901 10.023000000001048, 374810.48 6673592.84 9.536999999996624, 374821.022 6673613.705 9.237999999997555, 374833.314 6673636.169 9.164999999993597, 374836.282 6673640.762 9.182999021332805)', 3067),6944,7135,310124300,0,0,0,0)""")
      runUpdateToDb("""INSERT INTO project_link (id,project_id,track,discontinuity_type,road_number,road_part_number,start_addr_m,end_addr_m,created_by,modified_by,created_date,modified_date,status,administrative_class,roadway_id,linear_location_id,connected_link_id,ely,reversed,side,start_measure,end_measure,link_id,adjusted_timestamp,link_source,geometry,original_start_addr_m,original_end_addr_m,roadway_number,start_calibration_point,end_calibration_point,orig_start_calibration_point,orig_end_calibration_point) VALUES      (109763,1088,1,5,40921,1,7135,7147,'silari','silari',to_date('13.09.2021','DD.MM.YYYY'),to_date('13.09.2021','DD.MM.YYYY'),3,2,NULL,NULL,NULL,1,0,2,0.000, 11.167,11939970,1599778815000,4,ST_GeomFromText('LINESTRING(374836.282 6673640.762 9.18300000000454, 374842.369 6673650.124 9.277000000001863)', 3067),7135,7147,310124300,0,0,0,0)""")
      runUpdateToDb("""INSERT INTO project_link (id,project_id,track,discontinuity_type,road_number,road_part_number,start_addr_m,end_addr_m,created_by,modified_by,created_date,modified_date,status,administrative_class,roadway_id,linear_location_id,connected_link_id,ely,reversed,side,start_measure,end_measure,link_id,adjusted_timestamp,link_source,geometry,original_start_addr_m,original_end_addr_m,roadway_number,start_calibration_point,end_calibration_point,orig_start_calibration_point,orig_end_calibration_point) VALUES      (109787,1088,2,5,40921,1,7145,7317,'silari','silari',to_date('13.09.2021','DD.MM.YYYY'),to_date('13.09.2021','DD.MM.YYYY'),3,2,NULL,NULL,NULL,1,0,2,0.000,170.011,11939960,1599778815000,4,ST_GeomFromText('LINESTRING(374832.516 6673655.62 9.236999999993714, 374864.244 6673694.703 9.673999999999069, 374892.124 6673724.065 10.47500000000582, 374918.522 6673743.516 11.194000000003143, 374938.331 6673755.558 11.770999999993364, 374958.756 6673766.579 12.221999999994296)', 3067),7145,7317,310124301,0,0,0,0)""")
      runUpdateToDb("""INSERT INTO project_link (id,project_id,track,discontinuity_type,road_number,road_part_number,start_addr_m,end_addr_m,created_by,modified_by,created_date,modified_date,status,administrative_class,roadway_id,linear_location_id,connected_link_id,ely,reversed,side,start_measure,end_measure,link_id,adjusted_timestamp,link_source,geometry,original_start_addr_m,original_end_addr_m,roadway_number,start_calibration_point,end_calibration_point,orig_start_calibration_point,orig_end_calibration_point) VALUES      (109764,1088,1,5,40921,1,7147,7320,'silari','silari',to_date('13.09.2021','DD.MM.YYYY'),to_date('13.09.2021','DD.MM.YYYY'),3,2,NULL,NULL,NULL,1,0,2,0.000,169.170,11939964,1599778815000,4,ST_GeomFromText('LINESTRING(374842.369 6673650.124 9.277000000001863, 374850.879 6673662.029 9.364000000001397, 374870.258 6673686.417 9.657999999995809, 374892.951 6673709.545 10.202999999994063, 374922.262 6673732.698 11.080000000001746, 374942.448 6673746.015 11.646999999997206, 374968.036 6673760.592 12.376999999993131)', 3067),7147,7320,310124300,0,0,0,0)""")
      runUpdateToDb("""INSERT INTO project_link (id,project_id,track,discontinuity_type,road_number,road_part_number,start_addr_m,end_addr_m,created_by,modified_by,created_date,modified_date,status,administrative_class,roadway_id,linear_location_id,connected_link_id,ely,reversed,side,start_measure,end_measure,link_id,adjusted_timestamp,link_source,geometry,original_start_addr_m,original_end_addr_m,roadway_number,start_calibration_point,end_calibration_point,orig_start_calibration_point,orig_end_calibration_point) VALUES      (109788,1088,2,5,40921,1,7317,7331,'silari','silari',to_date('13.09.2021','DD.MM.YYYY'),to_date('13.09.2021','DD.MM.YYYY'),3,2,NULL,NULL,NULL,1,0,2,0.000, 14.452,143931,1533770939000,4,ST_GeomFromText('LINESTRING(374958.756 6673766.579 12.221999999994296, 374971.618 6673773.169 12.430999999996857)', 3067),7317,7331,310124301,0,0,0,0)""")
      runUpdateToDb("""INSERT INTO project_link (id,project_id,track,discontinuity_type,road_number,road_part_number,start_addr_m,end_addr_m,created_by,modified_by,created_date,modified_date,status,administrative_class,roadway_id,linear_location_id,connected_link_id,ely,reversed,side,start_measure,end_measure,link_id,adjusted_timestamp,link_source,geometry,original_start_addr_m,original_end_addr_m,roadway_number,start_calibration_point,end_calibration_point,orig_start_calibration_point,orig_end_calibration_point) VALUES      (109765,1088,1,5,40921,1,7247,7344,'silari','silari',to_date('13.09.2021','DD.MM.YYYY'),to_date('13.09.2021','DD.MM.YYYY'),3,2,NULL,NULL,NULL,1,0,2,0.000, 23.138,143936,1533770939000,4,ST_GeomFromText('LINESTRING(374968.036 6673760.592 12.376999999993131, 374989.72 6673768.664 12.630999999993946)', 3067),7320,7344,310124300,0,0,0,0)""")
      runUpdateToDb("""INSERT INTO project_link (id,project_id,track,discontinuity_type,road_number,road_part_number,start_addr_m,end_addr_m,created_by,modified_by,created_date,modified_date,status,administrative_class,roadway_id,linear_location_id,connected_link_id,ely,reversed,side,start_measure,end_measure,link_id,adjusted_timestamp,link_source,geometry,original_start_addr_m,original_end_addr_m,roadway_number,start_calibration_point,end_calibration_point,orig_start_calibration_point,orig_end_calibration_point) VALUES      (109789,1088,2,5,40921,1,7331,7345,'silari','silari',to_date('13.09.2021','DD.MM.YYYY'),to_date('13.09.2021','DD.MM.YYYY'),3,2,NULL,NULL,NULL,1,0,2,0.000, 13.917,143935,1533770939000,4,ST_GeomFromText('LINESTRING(374971.618 6673773.169 12.430999999996857, 374980.269 6673777.455 12.455000000001746, 374984.213 6673779.071 12.53200000000652)', 3067),7331,7345,310124301,0,0,0,0)""")
      runUpdateToDb("""INSERT INTO project_link (id,project_id,track,discontinuity_type,road_number,road_part_number,start_addr_m,end_addr_m,created_by,modified_by,created_date,modified_date,status,administrative_class,roadway_id,linear_location_id,connected_link_id,ely,reversed,side,start_measure,end_measure,link_id,adjusted_timestamp,link_source,geometry,original_start_addr_m,original_end_addr_m,roadway_number,start_calibration_point,end_calibration_point,orig_start_calibration_point,orig_end_calibration_point) VALUES      (109766,1088,1,5,40921,1,7344,7382,'silari','silari',to_date('13.09.2021','DD.MM.YYYY'),to_date('13.09.2021','DD.MM.YYYY'),3,2,NULL,NULL,NULL,1,0,2,0.000, 37.174,143920,1533749925000,4,ST_GeomFromText('LINESTRING(374989.72 6673768.664 12.630999999993946, 374990.19 6673768.851 12.630999999993946, 375015.276 6673777.302 12.61699999999837, 375025.153 6673779.836 12.611999999993714)', 3067),7344,7382,310124300,0,0,0,0)""")
      runUpdateToDb("""INSERT INTO project_link (id,project_id,track,discontinuity_type,road_number,road_part_number,start_addr_m,end_addr_m,created_by,modified_by,created_date,modified_date,status,administrative_class,roadway_id,linear_location_id,connected_link_id,ely,reversed,side,start_measure,end_measure,link_id,adjusted_timestamp,link_source,geometry,original_start_addr_m,original_end_addr_m,roadway_number,start_calibration_point,end_calibration_point,orig_start_calibration_point,orig_end_calibration_point) VALUES      (109790,1088,2,5,40921,1,7345,7384,'silari','silari',to_date('13.09.2021','DD.MM.YYYY'),to_date('13.09.2021','DD.MM.YYYY'),3,2,NULL,NULL,NULL,1,0,2,0.000, 38.542,143933,1599778815000,4,ST_GeomFromText('LINESTRING(374984.213 6673779.071 12.53200000000652, 375001.253 6673785.374 11.982000000003609, 375021.028 6673790.278 11.36501096271906)', 3067),7345,7384,310124301,0,0,0,0)""")
      runUpdateToDb("""INSERT INTO project_link (id,project_id,track,discontinuity_type,road_number,road_part_number,start_addr_m,end_addr_m,created_by,modified_by,created_date,modified_date,status,administrative_class,roadway_id,linear_location_id,connected_link_id,ely,reversed,side,start_measure,end_measure,link_id,adjusted_timestamp,link_source,geometry,original_start_addr_m,original_end_addr_m,roadway_number,start_calibration_point,end_calibration_point,orig_start_calibration_point,orig_end_calibration_point) VALUES      (109767,1088,1,5,40921,1,7382,7419,'silari','silari',to_date('13.09.2021','DD.MM.YYYY'),to_date('13.09.2021','DD.MM.YYYY'),3,2,NULL,NULL,NULL,1,0,2,0.000, 35.917,143942,1533749925000,4,ST_GeomFromText('LINESTRING(375025.153 6673779.836 12.611999999993714, 375035.889 6673782.36 12.688999999998487, 375050.839 6673784.982 12.82499999999709, 375060.517 6673785.777 12.838999296837926)', 3067),7382,7419,310124300,0,2,0,2)""")
      runUpdateToDb("""INSERT INTO project_link (id,project_id,track,discontinuity_type,road_number,road_part_number,start_addr_m,end_addr_m,created_by,modified_by,created_date,modified_date,status,administrative_class,roadway_id,linear_location_id,connected_link_id,ely,reversed,side,start_measure,end_measure,link_id,adjusted_timestamp,link_source,geometry,original_start_addr_m,original_end_addr_m,roadway_number,start_calibration_point,end_calibration_point,orig_start_calibration_point,orig_end_calibration_point) VALUES      (109791,1088,2,5,40921,1,7384,7417,'silari','silari',to_date('13.09.2021','DD.MM.YYYY'),to_date('13.09.2021','DD.MM.YYYY'),3,2,NULL,NULL,NULL,1,0,2,0.000, 32.498,143863,1533749925000,4,ST_GeomFromText('LINESTRING(375021.028 6673790.278 11.365000000005239, 375036.543 6673793.775 12.706999999994878, 375053.04 6673795.563 12.83299999999872)', 3067),7384,7417,310124301,0,2,0,2)""")
      runUpdateToDb("""INSERT INTO project_link (id,project_id,track,discontinuity_type,road_number,road_part_number,start_addr_m,end_addr_m,created_by,modified_by,created_date,modified_date,status,administrative_class,roadway_id,linear_location_id,connected_link_id,ely,reversed,side,start_measure,end_measure,link_id,adjusted_timestamp,link_source,geometry,original_start_addr_m,original_end_addr_m,roadway_number,start_calibration_point,end_calibration_point,orig_start_calibration_point,orig_end_calibration_point) VALUES      (109792,1088,2,5,40921,1,7417,7427,'silari','silari',to_date('13.09.2021','DD.MM.YYYY'),to_date('13.09.2021','DD.MM.YYYY'),3,2,NULL,NULL,NULL,1,0,2,0.000, 10.749,143861,1533749925000,4,ST_GeomFromText('LINESTRING(375053.04 6673795.563 12.83299999999872, 375063.756 6673796.41 12.919996587695966)', 3067),7417,7427,310124301,2,2,2,2)""")
      runUpdateToDb("""INSERT INTO project_link (id,project_id,track,discontinuity_type,road_number,road_part_number,start_addr_m,end_addr_m,created_by,modified_by,created_date,modified_date,status,administrative_class,roadway_id,linear_location_id,connected_link_id,ely,reversed,side,start_measure,end_measure,link_id,adjusted_timestamp,link_source,geometry,original_start_addr_m,original_end_addr_m,roadway_number,start_calibration_point,end_calibration_point,orig_start_calibration_point,orig_end_calibration_point) VALUES      (109768,1088,1,5,40921,1,7419,7534,'silari','silari',to_date('13.09.2021','DD.MM.YYYY'),to_date('13.09.2021','DD.MM.YYYY'),3,2,NULL,NULL,NULL,1,0,3,0.000,112.967,143939,1533749925000,4,ST_GeomFromText('LINESTRING(375172.788 6673775.964 13.448000000003958, 375155.844 6673779.19 13.375, 375139.996 6673781.262 13.271999999997206, 375124.957 6673783.296 13.171000000002095, 375110.163 6673784.78 13.107000000003609, 375089.413 6673785.993 12.952999999994063, 375068.437 6673786.157 12.872000000003027, 375060.517 6673785.777 12.839000000007218)', 3067),7419,7534,310124300,2,3,2,2)""")
      runUpdateToDb("""INSERT INTO project_link (id,project_id,track,discontinuity_type,road_number,road_part_number,start_addr_m,end_addr_m,created_by,modified_by,created_date,modified_date,status,administrative_class,roadway_id,linear_location_id,connected_link_id,ely,reversed,side,start_measure,end_measure,link_id,adjusted_timestamp,link_source,geometry,original_start_addr_m,original_end_addr_m,roadway_number,start_calibration_point,end_calibration_point,orig_start_calibration_point,orig_end_calibration_point) VALUES      (109793,1088,2,5,40921,1,7427,7534,'silari','silari',to_date('13.09.2021','DD.MM.YYYY'),to_date('13.09.2021','DD.MM.YYYY'),3,2,NULL,NULL,NULL,1,0,3,0.000,106.352,143858,1599778815000,4,ST_GeomFromText('LINESTRING(375169.282 6673785.082 13.562999999994645, 375142.486 6673790.226 13.489000000001397, 375116.509 6673793.804 13.35899999999674, 375088.552 6673796.034 13.111999999993714, 375064.624 6673796.41 12.929000000003725, 375063.756 6673796.41 12.920002931440251)', 3067),7427,7534,310124301,2,3,2,2)""")

      val projectLinkDAO = new ProjectLinkDAO
      val projectLinkSeq = projectLinkDAO.fetchProjectLinks(1088)

      val output = ProjectSectionCalculator.assignAddrMValues(projectLinkSeq).sortBy(_.startAddrMValue)

      val leftsBefore  = projectLinkSeq.filterNot(_.track == Track.RightSide).sortBy(_.startAddrMValue)
      val lefts        = output.filterNot(_.track == Track.RightSide).sortBy(_.startAddrMValue)
      val rightsBefore = projectLinkSeq.filterNot(_.track == Track.LeftSide).sortBy(_.startAddrMValue)
      val rights       = output.filterNot(_.track == Track.LeftSide).sortBy(_.startAddrMValue)

      /* Check that order of links excluding splitted links remains. */
      leftsBefore.map(_.id).toList should be(lefts.filterNot(pl => (pl.connectedLinkId.isDefined && pl.startMValue != 0)).map(_.id).toList)
      rightsBefore.map(_.id).toList should be(rights.filterNot(pl => (pl.connectedLinkId.isDefined && pl.startMValue != 0)).map(_.id).toList)

      lefts.zip(lefts.tail).foreach { case (prev, next) => prev.originalEndAddrMValue should be(next.originalStartAddrMValue)
      }
      lefts.zip(lefts.tail).foreach { case (prev, next) => prev.endAddrMValue should be(next.startAddrMValue)
      }
      rights.zip(rights.tail).foreach { case (prev, next) => prev.originalEndAddrMValue should be(next.originalStartAddrMValue)
      }
      rights.zip(rights.tail).foreach { case (prev, next) => prev.endAddrMValue should be(next.startAddrMValue)
      }
      /* check positive lengths. */
      output.foreach(pl => {
        (pl.endAddrMValue - pl.startAddrMValue) should be > 0L
      })
    }
  }

  /*
   | #0
   |
   O
   |
   O
  | | # New
   |
  | | #1562
*/
  test("Test assignAddrMValues " +
    "When a new link is added after an unchanged link as a left track, and the opposite right side track is transferred from the original link" +
    "Then the previously existing lengths of the links must not change, and the end address must remain the same, but the new left side at the middle must change by the length calculation, to comply with the transferred right side.") {
    runWithRollback {
      val project_id = 1
      val roadPart = RoadPart(6016,1)
      val newLinkId = 1005

      val projectLinkSeq = Seq(
        ProjectLink(1000,      roadPart, Track.Combined , Discontinuity.Continuous,           0,   15,    0,   15, None, None, None, 11811190.toString, 0.0,   15.178, SideCode.TowardsDigitizing, (RoadAddressCP, NoCP         ), (RoadAddressCP, NoCP         ), List(Point(  0.0,    0.0), Point(  15.178,  0.0)),                   project_id, RoadAddressChangeType.Unchanged, AdministrativeClass.Municipality, LinkGeomSource.FrozenLinkInterface,  15.178, 108248, 491052, 9, false, None,                   1627945220000L, 335584839),
        ProjectLink(1001,      roadPart, Track.Combined,  Discontinuity.MinorDiscontinuity,  15,  201,   15,  201, None, None, None,  1215621.toString, 0.0,  203.32 , SideCode.TowardsDigitizing, (NoCP,          RoadAddressCP), (NoCP,          RoadAddressCP), List(Point( 16.178,  0.0), Point( 219.498,  0.0)),                   project_id, RoadAddressChangeType.Unchanged, AdministrativeClass.Municipality, LinkGeomSource.FrozenLinkInterface, 203.32,  108245, 491108, 9, false, None,                   1605135620000L, 335584844),
        ProjectLink(1002,      roadPart, Track.Combined,  Discontinuity.Continuous,         201,  346,  201,  346, None, None, None,  1215618.toString, 0.0,  146.927, SideCode.TowardsDigitizing, (RoadAddressCP, NoCP         ), (RoadAddressCP, NoCP         ), List(Point(219.498,  0.0), Point( 365.425,  0.0)),                   project_id, RoadAddressChangeType.Unchanged, AdministrativeClass.Municipality, LinkGeomSource.FrozenLinkInterface, 146.927, 108244, 491118, 9, false, None,                   1605135620000L, 284576519),
        ProjectLink(1003,      roadPart, Track.Combined,  Discontinuity.MinorDiscontinuity, 346,  370,  346,  370, None, None, None, 11892932.toString, 0.0,   24.165, SideCode.TowardsDigitizing, (NoCP,          RoadAddressCP), (NoCP,          RoadAddressCP), List(Point(365.425,  0.0), Point( 389.59,   0.0)),                   project_id, RoadAddressChangeType.Unchanged, AdministrativeClass.Municipality, LinkGeomSource.FrozenLinkInterface,  24.165, 108226, 491117, 9, false, None,                   1631228420000L, 306511489),
        ProjectLink(1004,      roadPart, Track.RightSide, Discontinuity.Continuous,         370,  513,  370,  513, None, None, None, 11892926.toString, 0.0,  143.101, SideCode.TowardsDigitizing, (RoadAddressCP, UserDefinedCP), (RoadAddressCP, RoadAddressCP), List(Point(532.691,  0.0), Point( 671.69,   0.0)),                   project_id, RoadAddressChangeType.Transfer,  AdministrativeClass.Municipality, LinkGeomSource.FrozenLinkInterface, 143.101, 108239, 491136, 9, false, None,                   1605135620000L, 306511492),
        ProjectLink(newLinkId, roadPart, Track.LeftSide,  Discontinuity.Continuous,         370,  513,    0,    0, None, None, None, 11892924.toString, 0.0,  138.999, SideCode.TowardsDigitizing, (RoadAddressCP, UserDefinedCP), (NoCP,          NoCP         ), List(Point(532.691, 10.0), Point( 671.69,   0.0)),                   project_id, RoadAddressChangeType.New,       AdministrativeClass.Municipality, LinkGeomSource.FrozenLinkInterface, 138.999,      0,      0, 9, false, None,                   1631228420000L, 335718220),
        ProjectLink(1006,      roadPart, Track.LeftSide,  Discontinuity.Continuous,         513, 1004,  513, 1004, None, None, None, 11105130.toString, 0.0,  486.598, SideCode.TowardsDigitizing, (NoCP,          RoadAddressCP), (RoadAddressCP, RoadAddressCP), List(Point(671.69,   0.0), Point(1663.558,  0.0)),                   project_id, RoadAddressChangeType.Transfer,  AdministrativeClass.Municipality, LinkGeomSource.FrozenLinkInterface, 486.598, 108259, 491114, 9, false, None,                   1605135620000L, 148128088),
        ProjectLink(1007,      roadPart, Track.RightSide, Discontinuity.Continuous,         513, 1004,  513, 1004, None, None, None, 11105129.toString, 0.0,  505.27 , SideCode.TowardsDigitizing, (NoCP,          RoadAddressCP), (RoadAddressCP, RoadAddressCP), List(Point(671.69,   0.0), Point(1158.288,0.0),Point(1663.558,0.0)), project_id, RoadAddressChangeType.Transfer,  AdministrativeClass.Municipality, LinkGeomSource.FrozenLinkInterface, 505.27,  108236, 491126, 9, false, None,                   1605135620000L, 148124313),
        ProjectLink(1008,      roadPart, Track.Combined,  Discontinuity.Continuous,        1004, 1466, 1004, 1466, None, None, None, 12432937.toString, 0.0,  474.23 , SideCode.TowardsDigitizing, (RoadAddressCP, RoadAddressCP), (RoadAddressCP, RoadAddressCP), List(Point(1663.558, 0.0), Point(2137.788,  0.0)),                   project_id, RoadAddressChangeType.Transfer,  AdministrativeClass.Municipality, LinkGeomSource.FrozenLinkInterface, 474.23,  108235, 491131, 9, false, None,                   1635289229000L, 148121645),
        ProjectLink(1009,      roadPart, Track.LeftSide,  Discontinuity.EndOfRoad,         1466, 1562, 1466, 1562, None, None, None,  1215166.toString, 2.504, 59.638, SideCode.TowardsDigitizing, (RoadAddressCP, RoadAddressCP), (RoadAddressCP, RoadAddressCP), List(Point(2137.788, 0.0), Point(2197.426, 10.0)),                   project_id, RoadAddressChangeType.Transfer,  AdministrativeClass.Municipality, LinkGeomSource.FrozenLinkInterface,  59.638, 108250, 491116, 9, false, Some(1215166.toString), 1605135620000L, 148127567),
        ProjectLink(1010,      roadPart, Track.RightSide, Discontinuity.EndOfRoad,         1466, 1562, 1466, 1562, None, None, None,  1215168.toString, 0.0,   48.72 , SideCode.TowardsDigitizing, (RoadAddressCP, RoadAddressCP), (RoadAddressCP, RoadAddressCP), List(Point(2197.426, 0.0), Point(2246.146,  0.0)),                   project_id, RoadAddressChangeType.Transfer,  AdministrativeClass.Municipality, LinkGeomSource.FrozenLinkInterface,  48.72,  108242, 491133, 9, false, None,                   1605135620000L,     57360)
      )

      val output = ProjectSectionCalculator.assignAddrMValues(projectLinkSeq).sortBy(_.startAddrMValue)
      output.filterNot(_.id == newLinkId).foreach(pl => {
        pl.startAddrMValue shouldBe pl.originalStartAddrMValue
        pl.endAddrMValue   shouldBe pl.originalEndAddrMValue
      })

      val newLink = output.filter(_.id == newLinkId)
      newLink should have size 1
      newLink.head.startAddrMValue should be (370)
      newLink.head.endAddrMValue should be (513)

    }
  }
}

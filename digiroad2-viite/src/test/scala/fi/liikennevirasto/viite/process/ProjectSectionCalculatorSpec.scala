package fi.liikennevirasto.viite.process

import fi.liikennevirasto.digiroad2.asset.SideCode.{AgainstDigitizing, TowardsDigitizing}
import fi.liikennevirasto.digiroad2.{DigiroadEventBus, Point}
import fi.liikennevirasto.digiroad2.asset.{LinkGeomSource, SideCode}
import fi.liikennevirasto.digiroad2.oracle.OracleDatabase
import fi.liikennevirasto.digiroad2.service.RoadLinkService
import fi.liikennevirasto.digiroad2.util.Track
import fi.liikennevirasto.viite.dao.Discontinuity.{Continuous, Discontinuous, EndOfRoad, MinorDiscontinuity}
import fi.liikennevirasto.viite._
import fi.liikennevirasto.viite.dao.TerminationCode.NoTermination
import fi.liikennevirasto.viite.dao.{LinkStatus, _}
import org.joda.time.DateTime
import org.scalatest.mock.MockitoSugar
import org.scalatest.{FunSuite, Matchers}
import fi.liikennevirasto.viite.util._
import slick.driver.JdbcDriver.backend.Database
import slick.driver.JdbcDriver.backend.Database.dynamicSession

class ProjectSectionCalculatorSpec extends FunSuite with Matchers {
  val mockRoadLinkService: RoadLinkService = MockitoSugar.mock[RoadLinkService]
  val mockEventBus: DigiroadEventBus = MockitoSugar.mock[DigiroadEventBus]
  val roadAddressService: RoadAddressService {
  } = new RoadAddressService(mockRoadLinkService, mockEventBus) {
    override def withDynSession[T](f: => T): T = f

    override def withDynTransaction[T](f: => T): T = f
  }
  val projectService: ProjectService {
  } = new ProjectService(roadAddressService, mockRoadLinkService, mockEventBus) {
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
  val rap = RoadAddressProject(projectId, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("2700-01-01"),
    "TestUser", DateTime.parse("1972-03-03"), DateTime.parse("2700-01-01"), "Some additional info",
    List.empty[ReservedRoadPart], None)

  private def dummyProjectLink(id: Long, roadNumber: Long, roadPartNumber: Long, linkStatus: LinkStatus, track: Track, discontinuaty: Discontinuity,
                               sideCode: SideCode, startAddrM: Long, endAddrM: Long, startM: Double, endM: Double, geometry: Seq[Point]): ProjectLink ={
    toProjectLink(rap, LinkStatus.New)(RoadAddress(id, roadNumber, roadPartNumber, RoadType.Unknown, track, discontinuaty,
      startAddrM, endAddrM, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), 12345L, 0.0, 9.8, sideCode,
      0, (None, None), floating = false, geometry, LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
  }

  test("MValues && AddressMValues && CalibrationPoints calculation for new road addresses") {
    runWithRollback {

      //TODO Just notice that the start M and end M on the test are no matching the geometry that means the is not used
      val projectLinks = Seq(
        dummyProjectLink(id = 0L, roadNumber = 5L, roadPartNumber = 1L, LinkStatus.New, Track.Combined, Continuous, SideCode.TowardsDigitizing, startAddrM = 0L, endAddrM = 0L, startM = 0.0, endM = 9.8, Seq(Point(0.0, 0.0), Point(0.0, 9.8))),
        dummyProjectLink(id = 1L, roadNumber = 5L, roadPartNumber = 1L, LinkStatus.New, Track.Combined, Continuous, SideCode.TowardsDigitizing, startAddrM = 0L, endAddrM = 0L, startM = 0.0, endM = 9.8, Seq(Point(0.0, 30.0), Point(0.0, 39.8))),
        dummyProjectLink(id = 2L, roadNumber = 5L, roadPartNumber = 1L, LinkStatus.New, Track.Combined, Continuous, SideCode.TowardsDigitizing, startAddrM = 0L, endAddrM = 0L, startM = 0.0, endM = 9.8, Seq(Point(0.0, 20.2), Point(0.0, 30.0))),
        dummyProjectLink(id = 3L, roadNumber = 5L, roadPartNumber = 1L, LinkStatus.New, Track.Combined, Continuous, SideCode.TowardsDigitizing, startAddrM = 0L, endAddrM = 0L, startM = 0.0, endM = 10.4, Seq(Point(0.0, 9.8), Point(0.0, 20.2)))
      )

      val output = ProjectSectionCalculator.assignMValues(projectLinks)
      output.length should be(4)

      output.map(_.id) should be (Seq(4L, 3L, 1L, 0L))

      output(3).id should be(0L)
      output(3).startMValue should be(0.0)
      output(3).endMValue should be(output(3).geometryLength +- 0.001)
      output(3).startAddrMValue should be(30L)
      output(3).endAddrMValue should be(40L)
      output(3).sideCode should be(SideCode.TowardsDigitizing)

      output(2).id should be(1L)
      output(2).startMValue should be(0.0)
      output(2).endMValue should be(output(2).geometryLength +- 0.001)
      output(2).startAddrMValue should be(20L)
      output(2).endAddrMValue should be(30L)
      output(2).sideCode should be(SideCode.AgainstDigitizing)

      output(1).id should be(3L)
      output(1).startMValue should be(0.0)
      output(1).endMValue should be(output(1).geometryLength +- 0.001)
      output(1).startAddrMValue should be(10L)
      output(1).endAddrMValue should be(20L)
      output(1).sideCode should be(SideCode.TowardsDigitizing)

      output.head.id should be(4L)
      output.head.startMValue should be(0.0)
      output.head.endMValue should be(output.head.geometryLength +- 0.001)
      output.head.startAddrMValue should be(0L)
      output.head.endAddrMValue should be(10L)
      output.head.sideCode should be(SideCode.TowardsDigitizing)

      output(3).calibrationPoints should be(None, Some(CalibrationPoint(12346, 9.799999999999997, 40)))

      output.head.calibrationPoints should be(Some(CalibrationPoint(12345, 0.0, 0)), None)
    }
  }

  test("Mvalues calculation for complex case") {
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
      val projectLink0 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad0, 5, 1, RoadType.Unknown, Track.Combined, Continuous,
        0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad0, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), floating = false,
        Seq(Point(0.0, 0.0), Point(0.0, 9.8)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink1 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad1, 5, 1, RoadType.Unknown, Track.RightSide, Continuous,
        0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad1, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), floating = false,
        Seq(Point(0.0, 9.8), Point(-2.0, 20.2)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink2 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad2, 5, 1, RoadType.Unknown, Track.LeftSide, Continuous,
        0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad2, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), floating = false,
        Seq(Point(0.0, 9.8), Point(2.0, 19.2)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink3 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad3, 5, 1, RoadType.Unknown, Track.RightSide, Continuous,
        0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad3, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), floating = false,
        Seq(Point(-2.0, 20.2), Point(1.0, 30.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink4 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad4, 5, 1, RoadType.Unknown, Track.LeftSide, Continuous,
        0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad4, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), floating = false,
        Seq(Point(2.0, 19.2), Point(1.0, 30.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink5 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad5, 5, 1, RoadType.Unknown, Track.Combined, Continuous,
        0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad5, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), floating = false,
        Seq(Point(1.0, 30.0), Point(0.0, 48.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink6 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad6, 5, 1, RoadType.Unknown, Track.RightSide, Continuous,
        0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad6, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), floating = false,
        Seq(Point(0.0, 48.0), Point(2.0, 68.0), Point(0.0, 96.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink7 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad7, 5, 1, RoadType.Unknown, Track.LeftSide, Continuous,
        0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad7, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), floating = false,
        Seq(Point(0.0, 48.0), Point(-2.0, 68.0), Point(0.0, 96.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink8 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad8, 5, 1, RoadType.Unknown, Track.Combined, Continuous,
        0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad8, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), floating = false,
        Seq(Point(0.0, 96.0), Point(0.0, 148.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))

      val projectLinkSeq = Seq(projectLink0, projectLink1, projectLink2, projectLink3, projectLink4, projectLink5, projectLink6, projectLink7, projectLink8).map(pl =>
        pl.copy(endMValue = pl.geometryLength))
      val output = ProjectSectionCalculator.assignMValues(projectLinkSeq).sortBy(_.linkId)
      output.length should be(9)
      output.foreach(pl => pl.sideCode == TowardsDigitizing should be(true))
      val start = output.find(_.id == idRoad0).get
      start.calibrationPoints._1.nonEmpty should be(true)
      start.calibrationPoints._2.nonEmpty should be(true)
      start.startAddrMValue should be(0L)

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

      output.find(_.id == idRoad8).get.endAddrMValue should be(149L)
    }
  }

  test("Mvalues calculation for against digitization case") {
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
      val projectLink0 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad0, 5, 1, RoadType.Unknown, Track.Combined, Continuous,
        0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad0, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), floating = false,
        Seq(Point(0.0, 0.0), Point(0.0, 9.8)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink1 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad1, 5, 1, RoadType.Unknown, Track.RightSide, Continuous,
        0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad1, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), floating = false,
        Seq(Point(0.0, 9.8), Point(-2.0, 20.2)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink2 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad2, 5, 1, RoadType.Unknown, Track.LeftSide, Continuous,
        0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad2, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), floating = false,
        Seq(Point(0.0, 9.8), Point(2.0, 19.2)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink3 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad3, 5, 1, RoadType.Unknown, Track.RightSide, Continuous,
        0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad3, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), floating = false,
        Seq(Point(-2.0, 20.2), Point(1.0, 30.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink4 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad4, 5, 1, RoadType.Unknown, Track.LeftSide, Continuous,
        0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad4, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), floating = false,
        Seq(Point(2.0, 19.2), Point(1.0, 30.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink5 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad5, 5, 1, RoadType.Unknown, Track.Combined, Continuous,
        0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad5, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), floating = false,
        Seq(Point(1.0, 30.0), Point(0.0, 48.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink6 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad6, 5, 1, RoadType.Unknown, Track.RightSide, Continuous,
        0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad6, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), floating = false,
        Seq(Point(0.0, 48.0), Point(2.0, 68.0), Point(0.0, 96.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink7 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad7, 5, 1, RoadType.Unknown, Track.LeftSide, Continuous,
        0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad7, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), floating = false,
        Seq(Point(0.0, 48.0), Point(-2.0, 68.0), Point(0.0, 96.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink8 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad8, 5, 1, RoadType.Unknown, Track.Combined, Continuous,
        0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad8, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), floating = false,
        Seq(Point(0.0, 96.0), Point(0.0, 148.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))

      val projectLinkSeq = Seq(projectLink0, projectLink1, projectLink2, projectLink3, projectLink4, projectLink5, projectLink6, projectLink7, projectLink8).map(
        pl => pl.copy(sideCode = SideCode.AgainstDigitizing)
      )
      val output = ProjectSectionCalculator.assignMValues(projectLinkSeq).sortBy(_.linkId)
      output.length should be(9)
      output.foreach(pl => pl.sideCode == AgainstDigitizing should be(true))
      val start = output.find(_.id == idRoad0).get
      start.calibrationPoints._1.nonEmpty should be(true)
      start.calibrationPoints._2.nonEmpty should be(true)
      start.endAddrMValue should be(149L)

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

      output.find(_.id == idRoad8).get.startAddrMValue should be(0L)
    }
  }

  test("New addressing calibration points, mixed directions") {
    runWithRollback {
      val idRoad0 = 0L //   >
      val idRoad1 = 1L //     <
      val idRoad2 = 2L //   >
      val idRoad3 = 3L //     <
      val projectLink0 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad0, 5, 1, RoadType.Unknown, Track.Combined, Continuous,
        0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad0, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), floating = false,
        Seq(Point(0.0, 0.0), Point(0.0, 9.8)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink1 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad1, 5, 1, RoadType.Unknown, Track.Combined, Continuous,
        0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad1, 0.0, 0.0, SideCode.AgainstDigitizing, 0, (None, None), floating = false,
        Seq(Point(4.0, 7.5), Point(0.0, 9.8)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink2 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad2, 5, 1, RoadType.Unknown, Track.Combined, Continuous,
        0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad2, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), floating = false,
        Seq(Point(4.0, 7.5), Point(6.0, 19.2)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink3 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad3, 5, 1, RoadType.Unknown, Track.Combined, Continuous,
        0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad3, 0.0, 0.0, SideCode.AgainstDigitizing, 0, (None, None), floating = false,
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

  test("determineMValues Tracks 0+1+2") {
    runWithRollback {
      def trackMap(pl: ProjectLink) = {
        pl.linkId -> (pl.track, pl.sideCode)
      }

      val idRoad0 = 0L //   0 Track
      val idRoad1 = 1L //   1 Track
      val idRoad2 = 2L //   2 Track
      val projectLink0 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad0, 5, 1, RoadType.Unknown, Track.Combined, Continuous,
        0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad0, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), floating = false,
        Seq(Point(20.0, 10.0), Point(28, 15)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink1 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad1, 5, 1, RoadType.Unknown, Track.RightSide, Continuous,
        0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad1, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), floating = false,
        Seq(Point(42, 14), Point(28, 15)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink2 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad2, 5, 1, RoadType.Unknown, Track.LeftSide, Continuous,
        0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad2, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), floating = false,
        Seq(Point(28, 15), Point(75, 19.2)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val list = List(projectLink0, projectLink1, projectLink2)
      val ordered = ProjectSectionCalculator.assignMValues(list).map(trackMap).toMap
      // Test that the result is not dependent on the order of the links
      list.permutations.foreach(l => {
        ProjectSectionCalculator.assignMValues(l).map(trackMap).toMap should be(ordered)
      })
    }
  }

  test("determineMValues one link") {
    runWithRollback {
      val projectLink0T = toProjectLink(rap, LinkStatus.New)(RoadAddress(0L, 5, 1, RoadType.Unknown, Track.Combined, Continuous,
        0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), 0L, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), floating = false,
        Seq(Point(20.0, 10.0), Point(28, 15)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink0A = toProjectLink(rap, LinkStatus.New)(RoadAddress(0L, 5, 1, RoadType.Unknown, Track.Combined, Continuous,
        0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), 0L, 0.0, 0.0, SideCode.AgainstDigitizing, 0, (None, None), floating = false,
        Seq(Point(20.0, 10.0), Point(28, 15)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))

      val towards = ProjectSectionCalculator.assignMValues(Seq(projectLink0T)).head
      val against = ProjectSectionCalculator.assignMValues(Seq(projectLink0A)).head
      towards.sideCode should be(SideCode.TowardsDigitizing)
      against.sideCode should be(SideCode.AgainstDigitizing)
      towards.calibrationPoints._1 should be(Some(CalibrationPoint(0, 0.0, 0)))
      towards.calibrationPoints._2 should be(Some(CalibrationPoint(0, projectLink0T.geometryLength, 9)))
      against.calibrationPoints._2 should be(Some(CalibrationPoint(0, 0.0, 9)))
      against.calibrationPoints._1 should be(Some(CalibrationPoint(0, projectLink0A.geometryLength, 0)))
    }
  }

  test("determineMValues missing other track - exception is thrown and links are returned as-is") {
    runWithRollback {
      val projectLink0 = toProjectLink(rap, LinkStatus.New)(RoadAddress(0L, 5, 1, RoadType.Unknown, Track.RightSide, Continuous,
        0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), 0L, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), floating = false,
        Seq(Point(20.0, 10.0), Point(28, 15)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink1 = toProjectLink(rap, LinkStatus.New)(RoadAddress(1L, 5, 1, RoadType.Unknown, Track.RightSide, Continuous,
        0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), 1L, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), floating = false,
        Seq(Point(28.0, 15.0), Point(38, 15)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))

      val output = ProjectSectionCalculator.assignMValues(Seq(projectLink0, projectLink1))
      output.foreach { pl =>
        pl.startAddrMValue should be(0L)
        pl.endAddrMValue should be(0L)
      }
    }
  }

  test("determineMValues incompatible digitization on tracks is accepted and corrected") {
    runWithRollback {
      val idRoad0 = 0L //   R<
      val idRoad1 = 1L //   R<
      val idRoad2 = 2L //   L<    <- Note! Incompatible, means the addressing direction is against the right track
      val idRoad3 = 3L //   L<    <- Note! Incompatible, means the addressing direction is against the right track
      val projectLink0 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad0, 5, 1, RoadType.Unknown, Track.RightSide, Continuous,
        0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad0, 0.0, 0.0, SideCode.AgainstDigitizing, 0, (Some(CalibrationPoint(0L, 0.0, 0L)), None), floating = false,
        Seq(Point(28, 9.8), Point(20.0, 10.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink1 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad1, 5, 1, RoadType.Unknown, Track.RightSide, Continuous,
        0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad1, 0.0, 0.0, SideCode.AgainstDigitizing, 0, (None, None), floating = false,
        Seq(Point(42, 9.7), Point(28, 9.8)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink2 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad2, 5, 1, RoadType.Unknown, Track.LeftSide, Continuous,
        0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad2, 0.0, 0.0, SideCode.AgainstDigitizing, 0, (None, None), floating = false,
        Seq(Point(20, 10.1), Point(28, 10.2)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink3 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad3, 5, 1, RoadType.Unknown, Track.LeftSide, Continuous,
        0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad3, 0.0, 0.0, SideCode.AgainstDigitizing, 0, (None, None), floating = false,
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

  test("determineMValues different track lengths are adjusted") {
    runWithRollback {
      // Left track = 89.930 meters
      val idRoad0 = 0L //   L>
      val idRoad1 = 1L //   L>
      val idRoad2 = 2L //   L>
      val idRoad3 = 3L //   L<
      // Right track = 83.154 meters
      val idRoad4 = 4L //   R>
      val idRoad5 = 5L //   R>
      val projectLink0 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad0, 5, 1, RoadType.Unknown, Track.LeftSide, Continuous,
        0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad0, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), floating = false,
        Seq(Point(20.0, 10.0), Point(28, 15)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink1 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad1, 5, 1, RoadType.Unknown, Track.LeftSide, Continuous,
        0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad1, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), floating = false,
        Seq(Point(28, 15), Point(42, 19)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink2 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad2, 5, 1, RoadType.Unknown, Track.LeftSide, Continuous,
        0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad2, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), floating = false,
        Seq(Point(42, 19), Point(75, 29.2)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink3 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad3, 5, 1, RoadType.Unknown, Track.LeftSide, Continuous,
        0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad3, 0.0, 0.0, SideCode.AgainstDigitizing, 0, (None, None), floating = false,
        Seq(Point(103.0, 15.0), Point(75, 29.2)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink4 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad4, 5, 1, RoadType.Unknown, Track.RightSide, Continuous,
        0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad4, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), floating = false,
        Seq(Point(20.0, 10.0), Point(42, 11)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink5 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad5, 5, 1, RoadType.Unknown, Track.RightSide, Continuous,
        0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad5, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), floating = false,
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

  test("determineMValues calibration points are cleared") {
    runWithRollback {
      // Left track = 85.308 meters
      val idRoad0 = 0L //   L>
      val idRoad1 = 1L //   L>
      val idRoad2 = 2L //   L>
      val idRoad3 = 3L //   L<
      // Right track = 83.154 meters
      val idRoad4 = 4L //   R>
      val idRoad5 = 5L //   R>
      val projectLink0 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad0, 5, 1, RoadType.Unknown, Track.LeftSide, Continuous,
        0L, 9L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad0, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, Some(CalibrationPoint(idRoad0, 9.0, 9L))), floating = false,
        Seq(Point(20.0, 10.0), Point(28, 15)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink1 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad1, 5, 1, RoadType.Unknown, Track.LeftSide, Continuous,
        9L, 20L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad1, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (Some(CalibrationPoint(idRoad1, 0.0, 9L)), None), floating = false,
        Seq(Point(28, 15), Point(42, 19)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink2 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad2, 5, 1, RoadType.Unknown, Track.LeftSide, Continuous,
        0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad2, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), floating = false,
        Seq(Point(42, 19), Point(75, 29.2)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink3 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad3, 5, 1, RoadType.Unknown, Track.LeftSide, Continuous,
        0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad3, 0.0, 0.0, SideCode.AgainstDigitizing, 0, (None, None), floating = false,
        Seq(Point(103.0, 15.0), Point(75, 29.2)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink4 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad4, 5, 1, RoadType.Unknown, Track.RightSide, Continuous,
        0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad4, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), floating = false,
        Seq(Point(20.0, 10.0), Point(42, 11)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink5 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad5, 5, 1, RoadType.Unknown, Track.RightSide, Continuous,
        0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad5, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), floating = false,
        Seq(Point(42, 11), Point(103, 15)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val list = List(projectLink0, projectLink1, projectLink2, projectLink3, projectLink4, projectLink5)
      val ordered = ProjectSectionCalculator.assignMValues(list)
      ordered.flatMap(_.calibrationPoints._1) should have size 2
      ordered.flatMap(_.calibrationPoints._2) should have size 2
    }
  }

  test("Track sections are combined to start from the same position") {
    runWithRollback {
      // Left track = 85.308 meters
      val idRoad0 = 0L //   C>
      val idRoad1 = 1L //   L>
      val idRoad2 = 2L //   R>
      val projectLink0 = toProjectLink(rap, LinkStatus.UnChanged)(RoadAddress(idRoad0, 5, 1, RoadType.Unknown, Track.Combined, Continuous,
        0L, 8L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad0, 0.0, 8.0, SideCode.TowardsDigitizing, 0, (Some(CalibrationPoint(0L, 0.0, 0L)), None), floating = false,
        Seq(Point(20.0, 10.0), Point(28, 10)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink1 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad1, 5, 1, RoadType.Unknown, Track.LeftSide, Continuous,
        0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad1, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), floating = false,
        Seq(Point(28, 10), Point(28, 19)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink2 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad2, 5, 1, RoadType.Unknown, Track.RightSide, Continuous,
        0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad2, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), floating = false,
        Seq(Point(28, 1), Point(28, 10)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val list = List(projectLink0, projectLink1, projectLink2)
      val ordered = ProjectSectionCalculator.assignMValues(list)
      val left = ordered.find(_.linkId == idRoad1).get
      val right = ordered.find(_.linkId == idRoad2).get
      left.sideCode == right.sideCode should be(false)
    }
  }

  test("Unchanged + New project links are calculated properly") {
    runWithRollback {
      val idRoad0 = 0L //   U>
      val idRoad1 = 1L //   U>
      val idRoad2 = 2L //   N>
      val projectLink0 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad0, 5, 1, RoadType.Unknown, Track.Combined, Continuous,
        0L, 9L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad0, 0.0, 8.0, SideCode.TowardsDigitizing, 0, (Some(CalibrationPoint(idRoad0, 0.0, 0L)), None), floating = false,
        Seq(Point(20.0, 10.0), Point(28, 10)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0)).copy(status = LinkStatus.UnChanged)
      val projectLink1 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad1, 5, 1, RoadType.Unknown, Track.Combined, Continuous,
        9L, 19L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad1, 0.0, 9.0, SideCode.TowardsDigitizing, 0, (None, Some(CalibrationPoint(idRoad1, 9.0, 19L))), floating = false,
        Seq(Point(28, 10), Point(28, 19)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0)).copy(status = LinkStatus.UnChanged)
      val projectLink2 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad2, 5, 1, RoadType.Unknown, Track.Combined, Continuous,
        0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad2, 0.0, 11.0, SideCode.TowardsDigitizing, 0, (None, None), floating = false,
        Seq(Point(28, 19), Point(28, 30)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0)).copy(status = LinkStatus.New)
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

  test("Unchanged + Terminated links are calculated properly") {
    runWithRollback {
      val idRoad0 = 0L //   U>
      val idRoad1 = 1L //   U>
      val idRoad2 = 2L //   T>
      val projectLink0 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad0, 5, 1, RoadType.Unknown, Track.Combined, Continuous,
        0L, 9L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad0, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (Some(CalibrationPoint(idRoad0, 0.0, 0L)), None), floating = false,
        Seq(Point(20.0, 10.0), Point(28, 10)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0)).copy(status = LinkStatus.UnChanged)
      val projectLink1 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad1, 5, 1, RoadType.Unknown, Track.Combined, Continuous,
        9L, 19L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad1, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), floating = false,
        Seq(Point(28, 10), Point(28, 19)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0)).copy(status = LinkStatus.UnChanged)
      val projectLink2 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad2, 5, 1, RoadType.Unknown, Track.Combined, Continuous,
        19L, 30L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad2, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, Some(CalibrationPoint(idRoad2, 11.0, 30L))), floating = false,
        Seq(Point(28, 19), Point(28, 30)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0)).copy(status = LinkStatus.Terminated)
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

  test("Project section calculator test for new + transfer") {
    runWithRollback {
      val idRoad0 = 0L // T
      val idRoad1 = 1L // N
      val idRoad2 = 2L // N
      val idRoad3 = 3L // T
      val projectLink0 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad0, 5, 1, RoadType.Unknown, Track.Combined, Continuous, 0L, 12L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), 12345L, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), floating = false,
        Seq(Point(0.0, 0.0), Point(0.0, 9.8)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink1 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad1, 5, 1, RoadType.Unknown, Track.Combined, Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), 12346L, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), floating = false,
        Seq(Point(0.0, -10.0), Point(0.0, 0.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink2 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad2, 5, 1, RoadType.Unknown, Track.Combined, Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), 12347L, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), floating = false,
        Seq(Point(0.0, -20.2), Point(0.0, -10.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink3 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad3, 5, 1, RoadType.Unknown, Track.Combined, Discontinuous, 12L, 24L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), 12348L, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), floating = false,
        Seq(Point(0.0, 9.8), Point(0.0, 20.2)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))

      val projectLinkSeqT = Seq(projectLink0, projectLink3).map(_.copy(status = LinkStatus.Transfer))
      val projectLinkSeqN = Seq(projectLink1, projectLink2).map(_.copy(status = LinkStatus.New))
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
        r.calibrationPoints should be(None, Some(CalibrationPoint(12348, 10.399999999999999, 44)))
        r.startAddrMValue should be(maxAddr + projectLink3.startAddrMValue - projectLink3.endAddrMValue)
        r.endAddrMValue should be(maxAddr)
      }

      output.head.calibrationPoints should be(Some(CalibrationPoint(12347, 0.0, 0)), None)
    }
  }

  test("validate if there is a calibration point when has MinorDiscontinuity at end of address and start of next one with 2 tracks (Left and Right)") {
    runWithRollback{
      // Left track = 85.308 meters
      val idRoad0 = 0L //   L>
      val idRoad1 = 1L //   L>
      val idRoad2 = 2L //   L>
      val idRoad3 = 3L //   L>
      // Right track = 83.154 meters
      val idRoad4 = 4L //   R>
      val idRoad5 = 5L //   R>
      val projectLink0 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad0, 5, 1, RoadType.Unknown, Track.LeftSide, Continuous,
        0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad0, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), floating = false,
        Seq(Point(20.0, 10.0), Point(28, 15)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink1 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad1, 5, 1, RoadType.Unknown, Track.LeftSide, MinorDiscontinuity,
        0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad1, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), floating = false,
        Seq(Point(28, 15), Point(41, 18)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink2 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad2, 5, 1, RoadType.Unknown, Track.LeftSide, Continuous,
        0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad2, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), floating = false,
        Seq(Point(42, 19), Point(75, 29.2)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink3 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad3, 5, 1, RoadType.Unknown, Track.LeftSide, Continuous,
        0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad3, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), floating = false,
        Seq(Point(103.0, 15.0),Point(75, 29.2)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink4 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad4, 5, 1, RoadType.Unknown, Track.RightSide, MinorDiscontinuity,
        0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad4, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), floating = false,
        Seq(Point(20.0, 10.0), Point(42, 11)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink5 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad5, 5, 1, RoadType.Unknown, Track.RightSide, Continuous,
        0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad5, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), floating = false,
        Seq(Point(42, 11), Point(103, 15)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val list = List(projectLink0, projectLink1, projectLink2, projectLink3, projectLink4, projectLink5)
      val ordered = ProjectSectionCalculator.assignMValues(list)
      // check left and right track - should have 4 calibration points: start, end, minor discontinuity and next one after discontinuity
      (ordered.filter(_.track == Track.LeftSide).flatMap(_.calibrationPoints._1) ++ ordered.filter(_.track == Track.LeftSide).flatMap(_.calibrationPoints._2)) should have size 4
      (ordered.filter(_.track == Track.RightSide).flatMap(_.calibrationPoints._1) ++ ordered.filter(_.track == Track.RightSide).flatMap(_.calibrationPoints._2)) should have size 4
    }
  }

  test("validate if there is a calibration point when has Discontinuous at end of address and start of next one with 2 tracks (Left and Right)") {
    runWithRollback{
      // Left track = 85.308 meters
      val idRoad0 = 0L //   L>
      val idRoad1 = 1L //   L>
      val idRoad2 = 2L //   L>
      val idRoad3 = 3L //   L>
      // Right track = 83.154 meters
      val idRoad4 = 4L //   R>
      val idRoad5 = 5L //   R>
      val projectLink0 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad0, 5, 1, RoadType.Unknown, Track.LeftSide, Continuous,
        0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad0, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), floating = false,
        Seq(Point(20.0, 10.0), Point(28, 15)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink1 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad1, 5, 1, RoadType.Unknown, Track.LeftSide, Discontinuous,
        0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad1, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), floating = false,
        Seq(Point(28, 15), Point(41, 18)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink2 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad2, 5, 1, RoadType.Unknown, Track.LeftSide, Continuous,
        0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad2, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), floating = false,
        Seq(Point(42, 19), Point(75, 29.2)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink3 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad3, 5, 1, RoadType.Unknown, Track.LeftSide, Continuous,
        0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad3, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), floating = false,
        Seq(Point(103.0, 15.0),Point(75, 29.2)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink4 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad4, 5, 1, RoadType.Unknown, Track.RightSide, Discontinuous,
        0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad4, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), floating = false,
        Seq(Point(20.0, 10.0), Point(42, 11)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink5 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad5, 5, 1, RoadType.Unknown, Track.RightSide, Continuous,
        0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad5, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), floating = false,
        Seq(Point(42, 11), Point(103, 15)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val list = List(projectLink0, projectLink1, projectLink2, projectLink3, projectLink4, projectLink5)
      val ordered = ProjectSectionCalculator.assignMValues(list)
      // check left and right track - should have 4 calibration points: start, end, discontinuity and next one after discontinuity
      (ordered.filter(_.track == Track.LeftSide).flatMap(_.calibrationPoints._1) ++ ordered.filter(_.track == Track.LeftSide).flatMap(_.calibrationPoints._2)) should have size 4
      (ordered.filter(_.track == Track.RightSide).flatMap(_.calibrationPoints._1) ++ ordered.filter(_.track == Track.RightSide).flatMap(_.calibrationPoints._2)) should have size 4
    }
  }

  test("validate if there is a calibration point when has MinorDiscontinuity at end of address and start of next one with track Combined") {
    runWithRollback{
      // Combined track = 85.308 meters with MinorDiscontinuity
      val idRoad0 = 0L //   C>
      val idRoad1 = 1L //   C>
      val idRoad2 = 2L //   C>
      val idRoad3 = 3L //   C>
      val projectLink0 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad0, 5, 1, RoadType.PublicRoad, Track.Combined, Continuous,
        0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad0, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), floating = false,
        Seq(Point(20.0, 10.0), Point(28, 15)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink1 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad1, 5, 1, RoadType.PublicRoad, Track.Combined, MinorDiscontinuity,
        0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad1, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), floating = false,
        Seq(Point(28, 15), Point(41, 18)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink2 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad2, 5, 1, RoadType.PublicRoad, Track.Combined, Continuous,
        0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad2, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), floating = false,
        Seq(Point(42, 19), Point(75, 29.2)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink3 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad3, 5, 1, RoadType.PublicRoad, Track.Combined, Continuous,
        0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad3, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), floating = false,
        Seq(Point(103.0, 15.0),Point(75, 29.2)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val list = List(projectLink0, projectLink1, projectLink2, projectLink3)
      val ordered = ProjectSectionCalculator.assignMValues(list)
      // check combined track - should have 4 calibration points: start, end, minor discontinuity and next one after discontinuity
      (ordered.filter(_.track == Track.Combined).flatMap(_.calibrationPoints._1) ++ ordered.filter(_.track == Track.Combined).flatMap(_.calibrationPoints._2)) should have size 4
    }
  }

  test("validate if there is a calibration point when has Discontinuous at end of address and start of next one with track Combined") {
    runWithRollback{
      // Combined track = 85.308 meters with Discontinuous
      val idRoad0 = 0L //   C>
      val idRoad1 = 1L //   C>
      val idRoad2 = 2L //   C>
      val idRoad3 = 3L //   C>
      val projectLink0 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad0, 5, 1, RoadType.PublicRoad, Track.Combined, Continuous,
        0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad0, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), floating = false,
        Seq(Point(20.0, 10.0), Point(28, 15)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink1 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad1, 5, 1, RoadType.PublicRoad, Track.Combined, Discontinuous,
        0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad1, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), floating = false,
        Seq(Point(28, 15), Point(41, 18)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink2 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad2, 5, 1, RoadType.PublicRoad, Track.Combined, Continuous,
        0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad2, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), floating = false,
        Seq(Point(42, 19), Point(75, 29.2)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink3 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad3, 5, 1, RoadType.PublicRoad, Track.Combined, Continuous,
        0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad3, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), floating = false,
        Seq(Point(103.0, 15.0),Point(75, 29.2)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val list = List(projectLink0, projectLink1, projectLink2, projectLink3)
      val ordered = ProjectSectionCalculator.assignMValues(list)
      // check combined track - should have 4 calibration points: start, end, discontinuity and next one after discontinuity
      (ordered.filter(_.track == Track.Combined).flatMap(_.calibrationPoints._1) ++ ordered.filter(_.track == Track.Combined).flatMap(_.calibrationPoints._2)) should have size 4
    }
  }

  test("When projects links ends on a right and left track section calculator for new + transfer should have the same end address") {
    runWithRollback {
      val idRoad6 = 6L // N   /
      val idRoad5 = 5L // T \
      val idRoad4 = 4L // N   /
      val idRoad3 = 3L // T \
      val idRoad2 = 2L // N   /
      val idRoad1 = 1L // U  |

      val projectLink1 = toProjectLink(rap, LinkStatus.UnChanged)(RoadAddress(idRoad1, 5, 1, RoadType.Unknown, Track.Combined, Continuous, 0L, 10L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), 12347L, 0.0, 0.0, SideCode.AgainstDigitizing, 0, (None, None), false,
        Seq(Point(3.0, 0.0), Point(3.0, 2.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))

      val projectLink2 = toProjectLink(rap, LinkStatus.Transfer)(RoadAddress(idRoad2, 5, 1, RoadType.Unknown, Track.RightSide, Continuous, 0L, 12L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), 12345L, 0.0, 0.0, SideCode.AgainstDigitizing, 0, (None, None), false,
        Seq(Point(3.0, 2.0), Point(1.0, 4.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink3 = toProjectLink(rap, LinkStatus.Transfer)(RoadAddress(idRoad3, 5, 1, RoadType.Unknown, Track.RightSide, EndOfRoad, 12L, 24L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), 12348L, 0.0, 0.0, SideCode.AgainstDigitizing, 0, (None, None), false,
        Seq(Point(1.0, 4.0), Point(0.0, 6.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))

      val projectLink4 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad4, 5, 1, RoadType.Unknown, Track.LeftSide, Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), 12346L, 0.0, 0.0, SideCode.AgainstDigitizing, 0, (None, None), false,
        Seq(Point(3.0, 2.0), Point(5.0, 4.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink5 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad5, 5, 1, RoadType.Unknown, Track.LeftSide, Continuous, 0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), 12347L, 0.0, 0.0, SideCode.AgainstDigitizing, 0, (None, None), false,
        Seq(Point(5.0, 4.0), Point(6.0, 6.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
      val projectLink6 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad6, 5, 1, RoadType.Unknown, Track.LeftSide, EndOfRoad, 0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), 12347L, 0.0, 0.0, SideCode.AgainstDigitizing, 0, (None, None), false,
        Seq(Point(6.0, 6.0), Point(7.0, 7.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))


      val projectLinks = ProjectSectionCalculator.assignMValues(Seq(projectLink1, projectLink2, projectLink3, projectLink4, projectLink5, projectLink6))
      projectLinks.find(_.id == idRoad6).get.endAddrMValue should be(projectLinks.find(_.id == idRoad3).get.endAddrMValue)
    }
  }

}

package fi.liikennevirasto.viite.util

import fi.liikennevirasto.viite._
import fi.liikennevirasto.viite.dao._
import fi.liikennevirasto.viite.dao.ProjectCalibrationPointDAO.UserDefinedCalibrationPoint
import fi.vaylavirasto.viite.dao.Sequences
import fi.vaylavirasto.viite.geometry.{GeometryUtils, Point}
import fi.vaylavirasto.viite.model.CalibrationPointType.{JunctionPointCP, NoCP, UserDefinedCP}
import fi.vaylavirasto.viite.model.{AddrMRange, AdministrativeClass, ArealRoadMaintainer, Discontinuity, LinkGeomSource, RoadAddressChangeType, RoadPart, SideCode, Track}
import fi.vaylavirasto.viite.postgis.PostGISDatabaseScalikeJDBC.runWithRollback
import org.joda.time.DateTime
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class TwoTrackRoadUtilsSpec extends AnyFunSuite with Matchers {

  val projectDAO = new ProjectDAO
  val projectLinkDAO = new ProjectLinkDAO
  val projectReservedPartDAO = new ProjectReservedPartDAO

  private def setUpProjectWithLinks(
                                     testTrack1: TestTrack,
                                     testTrack2: TestTrack
                                   ) = {
    val roadPart            = RoadPart(19999, 1)
    val discontinuity       = Discontinuity.Continuous
    val arealRoadMaintainer = ArealRoadMaintainer.ELYPohjoisSavo
    val roadwayId           = 0L
    val startDate           = None


    val id = Sequences.nextViiteProjectId

    def projectLink(
                     addrMRange      : AddrMRange,
                     track           : Track,
                     projectId       : Long,
                     status          : RoadAddressChangeType = RoadAddressChangeType.NotHandled,
                     roadPart        : RoadPart = RoadPart(19999,1),
                     discontinuity   : Discontinuity = Discontinuity.Continuous,
                     arealRoadMaintainer: ArealRoadMaintainer = ArealRoadMaintainer.ELYPohjoisSavo,
                     linkId          : String = 0L.toString,
                     geom            : Seq[Point],
                     roadwayId       : Long = 0L,
                     linearLocationId: Long = 0L,
                     startDate       : Option[DateTime] = None
                   ) = {
      ProjectLink(NewIdValue, roadPart, track, discontinuity, addrMRange, addrMRange, startDate, None, Some("User"), linkId, 0.0, addrMRange.length.toDouble, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geom: Seq[Point], projectId, status, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, addrMRange.length.toDouble, roadwayId, linearLocationId, arealRoadMaintainer, reversed = false, None, 0L)
    }

    def withTrack(testTrack: TestTrack): Seq[ProjectLink] = {
      testTrack.status.zipWithIndex.map {
        case (status, index) =>
          val addrMRange = AddrMRange(testTrack.geom(index).minBy(_.x).x.toLong, testTrack.geom(index).maxBy(_.x).x.toLong)

          projectLink(addrMRange, testTrack.track, id, status, roadPart, discontinuity, arealRoadMaintainer, geom = testTrack.geom(index), linkId = index.toString, roadwayId = roadwayId,
            startDate = startDate)
      }
    }

    val projectStartDate = if (startDate.isEmpty) DateTime.now() else startDate.get
    val project          = Project(id, ProjectState.Incomplete, "f", "s", projectStartDate, "", projectStartDate, projectStartDate,
      "", Seq(), Seq(), None, None)
    projectDAO.create(project)
    val links =
      withTrack(testTrack1) ++ withTrack(testTrack2)

    projectReservedPartDAO.reserveRoadPart(id, roadPart, "u")
    val ids: Seq[Long] = projectLinkDAO.create(links)
    links.zipWithIndex.map(pl_with_index => {
      pl_with_index._1.copy(id = ids(pl_with_index._2))
    })
  }

  case class TestTrack(status: Seq[RoadAddressChangeType], track: Track, geom: Seq[Seq[Point]])


  test("Test splitPlsAtStatusChange() When both track have the same status Then should return unmodified pls without udcp.") {
    val geomTrack1_1 = Seq(Point(0.0, 0.0),   Point(100.0, 0.0))
    val geomTrack1_2 = Seq(Point(100.0, 0.0), Point(200.0, 0.0))
    val geomTrack2   = Seq(Point(0.0, 10.0),  Point(200.0, 10.0))

    val projectLinkTrack1_1 = ProjectLink(1001L, RoadPart(9999, 1), Track.apply(1), Discontinuity.Continuous, AddrMRange(  0L, 100L), AddrMRange(  0L, 100L), None, None, None, 1L.toString, 0.0, 0.0, SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP), geomTrack1_1, 0L, RoadAddressChangeType.Unchanged, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomTrack1_1), 0L, 0, ArealRoadMaintainer.ARMInvalid, reversed = false, None, 86400L)
    val projectLinkTrack1_2 = ProjectLink(1002L, RoadPart(9999, 1), Track.apply(1), Discontinuity.Continuous, AddrMRange(100L, 200L), AddrMRange(100L, 200L), None, None, None, 2L.toString, 0.0, 0.0, SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP), geomTrack1_2, 0L, RoadAddressChangeType.Unchanged, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomTrack1_2), 0L, 0, ArealRoadMaintainer.ARMInvalid, reversed = false, None, 86400L)
    val projectLinkTrack2   = ProjectLink(1003L, RoadPart(9999, 1), Track.apply(2), Discontinuity.Continuous, AddrMRange(  0L, 200L), AddrMRange(  0L, 200L), None, None, None, 3L.toString, 0.0, 0.0, SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP), geomTrack2,   0L, RoadAddressChangeType.Unchanged, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomTrack2),   0L, 0, ArealRoadMaintainer.ARMInvalid, reversed = false, None, 86400L)

    val (track1, track2, udcp) = TwoTrackRoadUtils.splitPlsAtStatusChange(Seq(projectLinkTrack1_1, projectLinkTrack1_2), Seq(projectLinkTrack2))
    track1 should have size 2
    track2 should have size 1
    udcp   should have size 0
    track1 should be(Seq(projectLinkTrack1_1, projectLinkTrack1_2))
    track2 should be(Seq(projectLinkTrack2))
  }


  test("Test splitPlsAtStatusChange() When there is status change at the same distance Then should return unmodified pls with two udcps.") {
    runWithRollback {
      val geomTrack1_1 = Seq(Point(0.0, 0.0), Point(100.0, 0.0))
      val geomTrack1_2 = Seq(Point(100.0, 0.0), Point(200.0, 0.0))
      val geomTrack2_1 = Seq(Point(0.0, 10.0), Point(100.0, 10.0))
      val geomTrack2_2 = Seq(Point(100.0, 10.0), Point(200.0, 10.0))

      val testTrack1 = TestTrack(Seq(RoadAddressChangeType.Unchanged, RoadAddressChangeType.Transfer), Track.apply(1), Seq(geomTrack1_1, geomTrack1_2))
      val testTrack2 = TestTrack(Seq(RoadAddressChangeType.Unchanged, RoadAddressChangeType.Transfer), Track.apply(2), Seq(geomTrack2_1, geomTrack2_2))

      val (rightProjectLinks, leftProjectLinks) = setUpProjectWithLinks(testTrack1, testTrack2).partition(_.track == Track.RightSide)
      val (track1, track2, udcp)                = TwoTrackRoadUtils.splitPlsAtStatusChange(rightProjectLinks, leftProjectLinks)

      track1 should have size 2
      track2 should have size 2
      udcp   should have size 2

      track1.head.addrMRange shouldBe AddrMRange(  0,100)
      track1.last.addrMRange shouldBe AddrMRange(100,200)

      track1.head.calibrationPointTypes shouldBe (NoCP, UserDefinedCP)
      track1.last.calibrationPointTypes shouldBe (NoCP, NoCP)

      track2.head.addrMRange shouldBe AddrMRange(  0,100)
      track2.last.addrMRange shouldBe AddrMRange(100,200)

      track2.head.calibrationPointTypes shouldBe (NoCP, UserDefinedCP)
      track2.last.calibrationPointTypes shouldBe (NoCP, NoCP)

    }
  }

  test("Test splitPlsAtStatusChange() When there is status change at the same distance other side having junction calibrationPoint Then should return unmodified pls with two udcps.") {
    runWithRollback {
      val geomTrack1_1 = Seq(Point(0.0, 0.0), Point(100.0, 0.0))
      val geomTrack1_2 = Seq(Point(100.0, 0.0), Point(200.0, 0.0))
      val geomTrack2_1 = Seq(Point(0.0, 10.0), Point(100.0, 10.0))
      val geomTrack2_2 = Seq(Point(100.0, 10.0), Point(200.0, 10.0))

      val testTrack1 = TestTrack(Seq(RoadAddressChangeType.Unchanged, RoadAddressChangeType.Transfer), Track.apply(1), Seq(geomTrack1_1, geomTrack1_2))
      val testTrack2 = TestTrack(Seq(RoadAddressChangeType.Unchanged, RoadAddressChangeType.Transfer), Track.apply(2), Seq(geomTrack2_1, geomTrack2_2))

      var (rightProjectLinks, leftProjectLinks) = setUpProjectWithLinks(testTrack1, testTrack2).partition(_.track == Track.RightSide)
      rightProjectLinks = Seq(rightProjectLinks.head.copy(calibrationPointTypes = (NoCP,JunctionPointCP)),
                              rightProjectLinks.last.copy(calibrationPointTypes = (JunctionPointCP,NoCP)))

      val (track1, track2, udcp) = TwoTrackRoadUtils.splitPlsAtStatusChange(rightProjectLinks, leftProjectLinks)

      track1 should have size 2
      track2 should have size 2
      udcp   should have size 2

      track1.head.addrMRange shouldBe AddrMRange(  0,100)
      track1.last.addrMRange shouldBe AddrMRange(100,200)

      track1.head.calibrationPointTypes shouldBe (NoCP, JunctionPointCP)
      track1.last.calibrationPointTypes shouldBe (JunctionPointCP, NoCP)

      track2.head.addrMRange shouldBe AddrMRange(  0,100)
      track2.last.addrMRange shouldBe AddrMRange(100,200)

      track2.head.calibrationPointTypes shouldBe (NoCP, UserDefinedCP)
      track2.last.calibrationPointTypes shouldBe (NoCP, NoCP)

    }
  }
  
  test("Test splitPlsAtStatusChange() When other track has two parts with different status Then returns opposite track splitted at status change distance with udcp.") {
    runWithRollback {
      val geomTrack1_1 = Seq(Point(0.0, 0.0), Point(100.0, 0.0))
      val geomTrack1_2 = Seq(Point(100.0, 0.0), Point(200.0, 0.0))
      val geomTrack2 = Seq(Point(0.0, 10.0), Point(200.0, 10.0))

      val testTrack1 = TestTrack(Seq(RoadAddressChangeType.Unchanged, RoadAddressChangeType.Transfer), Track.apply(1), Seq(geomTrack1_1, geomTrack1_2))
      val testTrack2 = TestTrack(Seq(RoadAddressChangeType.Unchanged), Track.apply(2), Seq(geomTrack2))

      val (rightProjectLinks, leftProjectLinks) = setUpProjectWithLinks(testTrack1, testTrack2).partition(_.track == Track.RightSide)
      val (track1, track2, udcp) = TwoTrackRoadUtils.splitPlsAtStatusChange(rightProjectLinks, leftProjectLinks)

      track1 should have size 2
      track2 should have size 2
        udcp should have size 2

      track1.head.addrMRange shouldBe AddrMRange(  0,100)
      track1.last.addrMRange shouldBe AddrMRange(100,200)

      track1.head.geometry shouldBe geomTrack1_1
      track1.last.geometry shouldBe geomTrack1_2

      track1.head.calibrationPointTypes shouldBe (NoCP, UserDefinedCP)
      track1.last.calibrationPointTypes shouldBe (NoCP, NoCP)

      track2.head.addrMRange shouldBe AddrMRange(  0,100)
      track2.last.addrMRange shouldBe AddrMRange(100,200)

      track2.head.geometry shouldBe Seq(Point(0.0, 10.0), Point(100.0, 10.0))
      track2.last.geometry shouldBe Seq(Point(100.0, 10.0), Point(200.0, 10.0))

      track2.head.calibrationPointTypes shouldBe (NoCP, UserDefinedCP)
      track2.last.calibrationPointTypes shouldBe (NoCP, NoCP)
    }
  }

  test("Test splitPlsAtStatusChange() When there are two status changes on track1 and zero on track 2 Then should split track 2 and create 2 udcps.") {
    runWithRollback {
      val geomTrack1_1 = Seq(Point(  0.0, 0.0), Point(100.0,  0.0))
      val geomTrack1_2 = Seq(Point(100.0, 0.0), Point(200.0,  0.0))
      val geomTrack1_3 = Seq(Point(200.0, 0.0), Point(300.0,  0.0))
      val geomTrack2   = Seq(Point(0.0,  10.0), Point(300.0, 10.0))

      val testTrack1 = TestTrack(Seq(RoadAddressChangeType.Unchanged, RoadAddressChangeType.New, RoadAddressChangeType.Transfer), Track.apply(1), Seq(geomTrack1_1, geomTrack1_2, geomTrack1_3))
      val testTrack2 = TestTrack(Seq(RoadAddressChangeType.Unchanged), Track.apply(2), Seq(geomTrack2))

      val (rightProjectLinks, leftProjectLinks) = setUpProjectWithLinks(testTrack1, testTrack2).partition(_.track == Track.RightSide)
      val (track1, track2, udcp) = TwoTrackRoadUtils.splitPlsAtStatusChange(rightProjectLinks, leftProjectLinks)

      track1 should have size 3
      track2 should have size 3
      udcp should   have size 4

      track1(0).addrMRange shouldBe AddrMRange(  0,100)
      track1(1).addrMRange shouldBe AddrMRange(100,200)
      track1(2).addrMRange shouldBe AddrMRange(200,300)

      track1(0).geometry shouldBe Seq(Point(0.0, 0.0), Point(100.0, 0.0))
      track1(1).geometry shouldBe Seq(Point(100.0, 0.0), Point(200.0, 0.0))
      track1(2).geometry shouldBe Seq(Point(200.0, 0.0), Point(300.0, 0.0))

      track1(0).calibrationPointTypes shouldBe (NoCP, UserDefinedCP)
      track1(1).calibrationPointTypes shouldBe (NoCP, UserDefinedCP)
      track1(2).calibrationPointTypes shouldBe (NoCP, NoCP)

      track2(0).addrMRange shouldBe AddrMRange(  0,100)
      track2(1).addrMRange shouldBe AddrMRange(100,200)
      track2(2).addrMRange shouldBe AddrMRange(200,300)

      track2(0).geometry shouldBe Seq(Point(0.0, 10.0), Point(100.0, 10.0))
      track2(1).geometry shouldBe Seq(Point(100.0, 10.0), Point(200.0, 10.0))
      track2(2).geometry shouldBe Seq(Point(200.0, 10.0), Point(300.0, 10.0))

      track2(0).calibrationPointTypes shouldBe (NoCP, UserDefinedCP)
      track2(1).calibrationPointTypes shouldBe (NoCP, UserDefinedCP)
      track2(2).calibrationPointTypes shouldBe (NoCP, NoCP)
    }
  }

  test("Test splitPlsAtStatusChange() When there are two status changes on track1 and zero on track 2 with two links Then should split track 2 and create 2 udcps.") {
    runWithRollback {
      val geomTrack1_1 = Seq(Point(  0.0, 0.0),  Point(100.0,  0.0))
      val geomTrack1_2 = Seq(Point(100.0, 0.0),  Point(200.0,  0.0))
      val geomTrack1_3 = Seq(Point(200.0, 0.0),  Point(300.0,  0.0))
      val geomTrack2_1 = Seq(Point(  0.0, 10.0), Point(150.0, 10.0))
      val geomTrack2_2 = Seq(Point(150.0, 10.0), Point(300.0, 10.0))

      val testTrack1 = TestTrack(Seq(RoadAddressChangeType.Unchanged, RoadAddressChangeType.New, RoadAddressChangeType.Transfer), Track.apply(1), Seq(geomTrack1_1, geomTrack1_2, geomTrack1_3))
      val testTrack2 = TestTrack(Seq(RoadAddressChangeType.Unchanged, RoadAddressChangeType.Unchanged), Track.apply(2), Seq(geomTrack2_1, geomTrack2_2))

      val (rightProjectLinks, leftProjectLinks) = setUpProjectWithLinks(testTrack1, testTrack2).partition(_.track == Track.RightSide)
      val (track1, track2, udcp) = TwoTrackRoadUtils.splitPlsAtStatusChange(rightProjectLinks, leftProjectLinks)

      track1 should have size 3
      track2 should have size 4
      udcp should   have size 4

      track1(0).addrMRange shouldBe AddrMRange(  0,100)
      track1(1).addrMRange shouldBe AddrMRange(100,200)
      track1(2).addrMRange shouldBe AddrMRange(200,300)

      track1(0).geometry shouldBe Seq(Point(0.0, 0.0), Point(100.0, 0.0))
      track1(1).geometry shouldBe Seq(Point(100.0, 0.0), Point(200.0, 0.0))
      track1(2).geometry shouldBe Seq(Point(200.0, 0.0), Point(300.0, 0.0))

      track1(0).calibrationPointTypes shouldBe (NoCP, UserDefinedCP)
      track1(1).calibrationPointTypes shouldBe (NoCP, UserDefinedCP)
      track1(2).calibrationPointTypes shouldBe (NoCP, NoCP)

      track2(0).addrMRange shouldBe AddrMRange(  0,100)
      track2(1).addrMRange shouldBe AddrMRange(100,150)
      track2(2).addrMRange shouldBe AddrMRange(150,200)
      track2(3).addrMRange shouldBe AddrMRange(200,300)

      track2(0).geometry shouldBe Seq(Point(0.0, 10.0), Point(100.0, 10.0))
      track2(1).geometry shouldBe Seq(Point(100.0, 10.0), Point(150.0, 10.0))
      track2(2).geometry shouldBe Seq(Point(150.0, 10.0), Point(200.0, 10.0))
      track2(3).geometry shouldBe Seq(Point(200.0, 10.0), Point(300.0, 10.0))

      track2(0).calibrationPointTypes shouldBe (NoCP, UserDefinedCP)
      track2(1).calibrationPointTypes shouldBe (NoCP, NoCP)
      track2(2).calibrationPointTypes shouldBe (NoCP, UserDefinedCP)
      track2(3).calibrationPointTypes shouldBe (NoCP, NoCP)
    }
  }

  test("Test splitPlsAtStatusChange() When there are two status changes on track1 and one on track 2 Then should split track 2 and create 2 udcps.") {
    runWithRollback {
      val geomTrack1_1 = Seq(Point(  0.0, 0.0),  Point(100.0,  0.0))
      val geomTrack1_2 = Seq(Point(100.0, 0.0),  Point(200.0,  0.0))
      val geomTrack1_3 = Seq(Point(200.0, 0.0),  Point(300.0,  0.0))
      val geomTrack2_1 = Seq(Point(  0.0, 10.0), Point(150.0, 10.0))
      val geomTrack2_2 = Seq(Point(150.0, 10.0), Point(300.0, 10.0))

      val testTrack1 = TestTrack(Seq(RoadAddressChangeType.Unchanged, RoadAddressChangeType.New, RoadAddressChangeType.Transfer), Track.apply(1), Seq(geomTrack1_1, geomTrack1_2, geomTrack1_3))
      val testTrack2 = TestTrack(Seq(RoadAddressChangeType.Unchanged, RoadAddressChangeType.Transfer), Track.apply(2), Seq(geomTrack2_1, geomTrack2_2))

      val (rightProjectLinks, leftProjectLinks) = setUpProjectWithLinks(testTrack1, testTrack2).partition(_.track == Track.RightSide)
      val (track1, track2, udcp) = TwoTrackRoadUtils.splitPlsAtStatusChange(rightProjectLinks, leftProjectLinks)

      track1 should have size 3
      track2 should have size 4
      udcp should   have size 4

      track1(0).addrMRange shouldBe AddrMRange(  0,100)
      track1(1).addrMRange shouldBe AddrMRange(100,200)
      track1(2).addrMRange shouldBe AddrMRange(200,300)

      track1(0).geometry shouldBe Seq(Point(0.0, 0.0), Point(100.0, 0.0))
      track1(1).geometry shouldBe Seq(Point(100.0, 0.0), Point(200.0, 0.0))
      track1(2).geometry shouldBe Seq(Point(200.0, 0.0), Point(300.0, 0.0))

      track1(0).calibrationPointTypes shouldBe (NoCP, UserDefinedCP)
      track1(1).calibrationPointTypes shouldBe (NoCP, UserDefinedCP)
      track1(2).calibrationPointTypes shouldBe (NoCP, NoCP)

      track2(0).addrMRange shouldBe AddrMRange(  0,100)
      track2(1).addrMRange shouldBe AddrMRange(100,150)
      track2(2).addrMRange shouldBe AddrMRange(150,200)
      track2(3).addrMRange shouldBe AddrMRange(200,300)

      track2(0).geometry shouldBe Seq(Point(0.0, 10.0), Point(100.0, 10.0))
      track2(1).geometry shouldBe Seq(Point(100.0, 10.0), Point(150.0, 10.0))
      track2(2).geometry shouldBe Seq(Point(150.0, 10.0), Point(200.0, 10.0))
      track2(3).geometry shouldBe Seq(Point(200.0, 10.0), Point(300.0, 10.0))

      track2(0).calibrationPointTypes shouldBe (NoCP, UserDefinedCP)
      track2(1).calibrationPointTypes shouldBe (NoCP, NoCP)
      track2(2).calibrationPointTypes shouldBe (NoCP, UserDefinedCP)
      track2(3).calibrationPointTypes shouldBe (NoCP, NoCP)
    }
  }

  test("Test splitPlsAtStatusChange() When there are two status changes on track1 and zero on track 2 after both sides split processsed Then should split both tracks and create " +
       "3 udcps.") {
    runWithRollback {
      val geomTrack1_1 = Seq(Point(  0.0, 0.0),  Point(100.0,  0.0))
      val geomTrack1_2 = Seq(Point(100.0, 0.0),  Point(200.0,  0.0))
      val geomTrack1_3 = Seq(Point(200.0, 0.0),  Point(300.0,  0.0))
      val geomTrack2_1 = Seq(Point(  0.0, 10.0), Point(150.0, 10.0))
      val geomTrack2_2 = Seq(Point(150.0, 10.0), Point(300.0, 10.0))

      val testTrack1 = TestTrack(Seq(RoadAddressChangeType.Unchanged, RoadAddressChangeType.New, RoadAddressChangeType.Transfer), Track.apply(1), Seq(geomTrack1_1, geomTrack1_2, geomTrack1_3))
      val testTrack2 = TestTrack(Seq(RoadAddressChangeType.Unchanged, RoadAddressChangeType.Transfer), Track.apply(2), Seq(geomTrack2_1, geomTrack2_2))

      val (rightProjectLinks, leftProjectLinks) = setUpProjectWithLinks(testTrack1, testTrack2).partition(_.track == Track.RightSide)
      val (track1AfterFirstSplitcCheck, track2AfterFirstSplitcCheck, udcptAfterFirstSplitcCheck) = TwoTrackRoadUtils.splitPlsAtStatusChange(rightProjectLinks, leftProjectLinks)
      val (track2, track1, udcp) = TwoTrackRoadUtils.splitPlsAtStatusChange(track2AfterFirstSplitcCheck, track1AfterFirstSplitcCheck)

      track1                     should have size 4
      track2                     should have size 4
      udcp                       should have size 2
      udcptAfterFirstSplitcCheck should have size 4

      track1.foreach(_.track shouldBe Track.RightSide)
      track2.foreach(_.track shouldBe Track.LeftSide)

      (udcptAfterFirstSplitcCheck ++ udcp).foreach(_.get shouldBe a [UserDefinedCalibrationPoint])
      udcp.head.get.addressMValue shouldBe 150
      udcp.last.get.addressMValue shouldBe 150
      udcp.head.get.projectLinkId shouldBe track1(1).id
      udcp.last.get.projectLinkId shouldBe track2(1).id

      track1(0).addrMRange shouldBe AddrMRange(  0,100)
      track1(1).addrMRange shouldBe AddrMRange(100,150)
      track1(2).addrMRange shouldBe AddrMRange(150,200)
      track1(3).addrMRange shouldBe AddrMRange(200,300)

      track1(0).geometry shouldBe Seq(Point(0.0, 0.0), Point(100.0, 0.0))
      track1(1).geometry shouldBe Seq(Point(100.0, 0.0), Point(150.0, 0.0))
      track1(2).geometry shouldBe Seq(Point(150.0, 0.0), Point(200.0, 0.0))
      track1(3).geometry shouldBe Seq(Point(200.0, 0.0), Point(300.0, 0.0))

      track1(0).calibrationPointTypes shouldBe (NoCP, UserDefinedCP)
      track1(1).calibrationPointTypes shouldBe (NoCP, UserDefinedCP)
      track1(2).calibrationPointTypes shouldBe (NoCP, UserDefinedCP)
      track1(3).calibrationPointTypes shouldBe (NoCP, NoCP)

      track2(0).addrMRange shouldBe AddrMRange(  0,100)
      track2(1).addrMRange shouldBe AddrMRange(100,150)
      track2(2).addrMRange shouldBe AddrMRange(150,200)
      track2(3).addrMRange shouldBe AddrMRange(200,300)

      track2(0).geometry shouldBe Seq(Point(0.0, 10.0), Point(100.0, 10.0))
      track2(1).geometry shouldBe Seq(Point(100.0, 10.0), Point(150.0, 10.0))
      track2(2).geometry shouldBe Seq(Point(150.0, 10.0), Point(200.0, 10.0))
      track2(3).geometry shouldBe Seq(Point(200.0, 10.0), Point(300.0, 10.0))

      track2(0).calibrationPointTypes shouldBe (NoCP, UserDefinedCP)
      track2(1).calibrationPointTypes shouldBe (NoCP, UserDefinedCP)
      track2(2).calibrationPointTypes shouldBe (NoCP, UserDefinedCP)
      track2(3).calibrationPointTypes shouldBe (NoCP, NoCP)
    }
  }

  test("Test splitPlsAtStatusChange() When there are two status changes on track1 and one on track 2 and two links having different sidecode Then should split and create udcp " +
       "correctly.") {
    runWithRollback {
      val geomTrack1_1 = Seq(Point(  0.0, 0.0), Point(100.0,  0.0))
      val geomTrack1_2 = Seq(Point(200.0, 0.0), Point(100.0,  0.0))
      val geomTrack1_3 = Seq(Point(200.0, 0.0), Point(300.0,  0.0))
      val geomTrack2_1 = Seq(Point(150.0, 10.0), Point(  0.0, 10.0))
      val geomTrack2_2 = Seq(Point(150.0, 10.0), Point(300.0, 10.0))

      val testTrack1 = TestTrack(Seq(RoadAddressChangeType.Unchanged, RoadAddressChangeType.New, RoadAddressChangeType.Transfer), Track.apply(1), Seq(geomTrack1_1, geomTrack1_2, geomTrack1_3))
      val testTrack2 = TestTrack(Seq(RoadAddressChangeType.Unchanged, RoadAddressChangeType.Transfer), Track.apply(2), Seq(geomTrack2_1, geomTrack2_2))

      var (rightProjectLinks, leftProjectLinks) = setUpProjectWithLinks(testTrack1, testTrack2).partition(_.track == Track.RightSide)
      // Update side code
      rightProjectLinks = rightProjectLinks.head.copy(sideCode = SideCode.AgainstDigitizing) +: rightProjectLinks.tail

      val (track1, track2, udcp) = TwoTrackRoadUtils.splitPlsAtStatusChange(rightProjectLinks, leftProjectLinks)

      track1 should have size 3
      track2 should have size 4
      udcp should   have size 4

      track1(0).addrMRange shouldBe AddrMRange(  0,100)
      track1(1).addrMRange shouldBe AddrMRange(100,200)
      track1(2).addrMRange shouldBe AddrMRange(200,300)

      track1(0).geometry shouldBe Seq(Point(0.0, 0.0), Point(100.0, 0.0))
      track1(1).geometry shouldBe Seq(Point(200.0, 0.0), Point(100.0, 0.0))
      track1(2).geometry shouldBe Seq(Point(200.0, 0.0), Point(300.0, 0.0))

      track1(0).calibrationPointTypes shouldBe (NoCP, UserDefinedCP)
      track1(1).calibrationPointTypes shouldBe (NoCP, UserDefinedCP)
      track1(2).calibrationPointTypes shouldBe (NoCP, NoCP)

      track2(0).addrMRange shouldBe AddrMRange(  0,100)
      track2(1).addrMRange shouldBe AddrMRange(100,150)
      track2(2).addrMRange shouldBe AddrMRange(150,200)
      track2(3).addrMRange shouldBe AddrMRange(200,300)

      track2(0).geometry shouldBe Seq(Point(150.0, 10.0), Point( 50.0, 10.0))
      track2(1).geometry shouldBe Seq(Point( 50.0, 10.0), Point(  0.0, 10.0))
      track2(2).geometry shouldBe Seq(Point(150.0, 10.0), Point(200.0, 10.0))
      track2(3).geometry shouldBe Seq(Point(200.0, 10.0), Point(300.0, 10.0))

      track2(0).calibrationPointTypes shouldBe (NoCP, UserDefinedCP)
      track2(1).calibrationPointTypes shouldBe (NoCP, NoCP)
      track2(2).calibrationPointTypes shouldBe (NoCP, UserDefinedCP)
      track2(3).calibrationPointTypes shouldBe (NoCP, NoCP)
    }
  }

//  TODO VIITE-3120 The commented code below seems obsolete in current Viite app, commented out so they are available if needed after all (if you are deleting these lines, be sure to delete the other functions and code tagged with "TODO VIITE-3120")
  //  test("Test findAndCreateSplitsAtOriginalAddress() When split address is in address range of a new link Then originalAddress splitting should have no effect.") {
//    val geomTrack1_1 = Seq(Point(0.0, 0.0),   Point(100.0, 0.0))
//    val geomTrack1_2 = Seq(Point(100.0, 0.0), Point(200.0, 0.0))
//
//    val projectLinkTrack1_1 = ProjectLink(1001L, RoadPart(9999, 1), Track.apply(1), Discontinuity.Continuous, AddrMRange(  0L, 100L), AddrMRange(  0L, 100L), None, None, None, 1L.toString, 0.0, 0.0, SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP), geomTrack1_1, 0L, RoadAddressChangeType.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomTrack1_1), 0L, 0, 0, reversed = false, None, 86400L)
//    val projectLinkTrack1_2 = ProjectLink(1002L, RoadPart(9999, 1), Track.apply(1), Discontinuity.Continuous, AddrMRange(100L, 200L), AddrMRange(100L, 200L), None, None, None, 2L.toString, 0.0, 0.0, SideCode.Unknown, (NoCP, NoCP), (NoCP, NoCP), geomTrack1_2, 0L, RoadAddressChangeType.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomTrack1_2), 0L, 0, 0, reversed = false, None, 86400L)
//
//    val newLinks = runWithRollback {
//      TwoTrackRoadUtils.findAndCreateSplitsAtOriginalAddress(99, Seq(projectLinkTrack1_1, projectLinkTrack1_2))
//    }
//    newLinks should not be 'defined
//  }
    }

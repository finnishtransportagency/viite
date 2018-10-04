package fi.liikennevirasto.viite.process

import fi.liikennevirasto.digiroad2.asset.SideCode.{AgainstDigitizing, TowardsDigitizing}
import fi.liikennevirasto.digiroad2.{GeometryUtils, Point}
import fi.liikennevirasto.digiroad2.asset.{LinkGeomSource, SideCode}
import fi.liikennevirasto.digiroad2.util.Track
import fi.liikennevirasto.viite.{ReservedRoadPart, RoadType}
import fi.liikennevirasto.viite.dao.Discontinuity.Continuous
import fi.liikennevirasto.viite.dao.FloatingReason.NoFloating
import fi.liikennevirasto.viite.dao.TerminationCode.NoTermination
import fi.liikennevirasto.viite.dao._
import org.joda.time.DateTime
import org.scalatest.{FunSuite, Matchers}
import fi.liikennevirasto.viite.util._

class TrackSectionOrderSpec extends FunSuite with Matchers {
  val projectId = 1
  val rap = RoadAddressProject(projectId, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("2700-01-01"),
    "TestUser", DateTime.parse("1972-03-03"), DateTime.parse("2700-01-01"), "Some additional info",
    List.empty[ProjectReservedPart], None)

  private def dummyProjectLink(id: Long, geometry: Seq[Point], track: Track = Track.Combined) = {
    //TODO the road address now have the linear location id and as been setted to 1L
    toProjectLink(rap, LinkStatus.New)(RoadAddress(id, 1L, 5, 1, RoadType.Unknown, track, Continuous,
      0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), id, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), NoFloating,
      geometry, LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
  }

  test("check that orderProjectLinksTopologyByGeometry is not dependent on the links order") {
    val idRoad0 = 0L //   >
    val idRoad1 = 1L //     <
    val idRoad2 = 2L //   >
    val idRoad3 = 3L //     <
    //TODO the road address now have the linear location id and as been setted to 1L
    val projectLink0 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad0, 1L, 5, 1, RoadType.Unknown, Track.Combined, Continuous,
      0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad0, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), NoFloating,
      Seq(Point(20.0, 10.0), Point(28, 15)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
    val projectLink1 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad1, 1L, 5, 1, RoadType.Unknown, Track.Combined, Continuous,
      0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad1, 0.0, 0.0, SideCode.AgainstDigitizing, 0, (None, None), NoFloating,
      Seq(Point(42, 14),Point(28, 15)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
    val projectLink2 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad2, 1L, 5, 1, RoadType.Unknown, Track.Combined, Continuous,
      0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad2, 0.0, 0.0, SideCode.TowardsDigitizing, 0, (None, None), NoFloating,
      Seq(Point(42, 14), Point(75, 19.2)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
    val projectLink3 = toProjectLink(rap, LinkStatus.New)(RoadAddress(idRoad3, 1L, 5, 1, RoadType.Unknown, Track.Combined, Continuous,
      0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), idRoad3, 0.0, 0.0, SideCode.AgainstDigitizing, 0, (None, None), NoFloating,
      Seq(Point(103.0, 15.0),Point(75, 19.2)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
    val list = List(projectLink0, projectLink1, projectLink2, projectLink3)
    val (ordered, _) = TrackSectionOrder.orderProjectLinksTopologyByGeometry((Point(20.0, 10.0), Point(20.0, 10.0)), list)
    // Test that the result is not dependent on the order of the links
    list.permutations.foreach(l => {
      TrackSectionOrder.orderProjectLinksTopologyByGeometry((Point(20.0, 10.0), Point(20.0, 10.0)), l)._1 should be(ordered)
    })
  }

  test("combined track with one ill-fitting link direction after discontinuity") {
    val points = Seq(Seq(Point(100,110), Point(75, 130), Point(50,159)),
      Seq(Point(50,160), Point(0, 110), Point(0,60)),
      Seq(Point(0,60), Point(-50, 80), Point(-100, 110)),
      Seq(Point(-100,110), Point(-120, 140), Point(-150,210)))
    val geom = points.map(g =>
      if (g.head.y > g.last.y)
        g.reverse
      else g
    )
    //TODO the road address now have the linear location id and as been setted to 1L
    val list = geom.zip(0 to 3).map{ case (g, id) =>
      toProjectLink(rap, LinkStatus.New)(RoadAddress(id, 1L, 5, 1, RoadType.Unknown, Track.Combined, Continuous,
        0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), id, 0.0, 0.0, SideCode.Unknown, 0, (None, None), NoFloating,
        g, LinkGeomSource.NormalLinkInterface, 5, NoTermination, 0))
    }
    val (ordered, _) = TrackSectionOrder.orderProjectLinksTopologyByGeometry((Point(100,110), Point(100,110)), list)
    ordered.map(_.id) should be (Seq(0L, 1L, 2L, 3L))
  }

  test("roundabout with Towards facing starting link") {
    val points = Seq(Seq(Point(150.00, 110.00),Point(146.19, 129.13),Point(135.36, 145.36)),
      Seq(Point(135.36, 145.36),Point(119.13, 156.19),Point(100.00, 160.00)),
      Seq(Point(100.00, 160.00),Point(80.87, 156.19),Point(64.64, 145.36)),
      Seq(Point(64.64, 145.36),Point(53.81, 129.13),Point(50.00, 110.00)),
      Seq(Point(50.00, 110.00),Point(53.81, 90.87),Point(64.64, 74.64)),
      Seq(Point(64.64, 74.64),Point(80.87, 63.81),Point(100.00, 60.00)),
      Seq(Point(100.00, 60.00),Point(119.13, 63.81),Point(135.36, 74.64)),
      Seq(Point(135.36, 74.64),Point(146.19, 90.87),Point(150.00, 110.00)))
    val geom = points.map(g =>
      if (g.head.y > g.last.y)
        g.reverse
      else g
    )
    //TODO the road address now have the linear location id and as been setted to 1L
    val list = geom.zip(0 to 7).map{ case (g, id) =>
      toProjectLink(rap, LinkStatus.New)(RoadAddress(id, 1L, 5, 1, RoadType.Unknown, Track.Combined, Continuous,
        0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), id, 0.0, 0.0, SideCode.Unknown, 0, (None, None), NoFloating,
        g, LinkGeomSource.NormalLinkInterface, 5, NoTermination, 0))
    }
    TrackSectionOrder.isRoundabout(list) should be (true)
    TrackSectionOrder.isRoundabout(list.init) should be (false)
    TrackSectionOrder.isRoundabout(list.tail) should be (false)
    val result = TrackSectionOrder.mValueRoundabout(list)
    result should have size(8)
    result.head.sideCode should be (TowardsDigitizing)
    result.forall(_.sideCode == result.head.sideCode) should be (false)
    result.head.geometry should be (list.head.geometry)
  }

  test("invalid roundabout geometry throws exception") {
    val points = Seq(Seq(Point(150.00, 110.00),Point(146.19, 129.13),Point(135.36, 145.36)),
      Seq(Point(135.36, 145.36),Point(119.13, 156.19),Point(100.00, 160.00)),
      Seq(Point(100.00, 160.00),Point(80.87, 156.19),Point(80, 140)),
      Seq(Point(80, 140), Point(90, 130),Point(70, 100)),
      Seq(Point(70, 100),Point(60.00, 120.00),Point(50.00, 110.00)),
      Seq(Point(50.00, 110.00),Point(60.00, 83.81),Point(100.00, 60.00)),
      Seq(Point(100.00, 60.00),Point(119.13, 63.81),Point(135.36, 74.64)),
      Seq(Point(135.36, 74.64),Point(146.19, 90.87),Point(150.00, 110.00)))
    val geom = points.map(g =>
      if (g.head.y > g.last.y)
        g.reverse
      else g
    )
    //TODO the road address now have the linear location id and as been setted to 1L
    val list = geom.zip(0 to 7).map{ case (g, id) =>
      toProjectLink(rap, LinkStatus.New)(RoadAddress(id, 1L, 5, 1, RoadType.Unknown, Track.Combined, Continuous,
        0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), id, 0.0, 0.0, SideCode.Unknown, 0, (None, None), NoFloating,
        g, LinkGeomSource.NormalLinkInterface, 5, NoTermination, 0))
    }
    TrackSectionOrder.isRoundabout(list) should be (true)
    intercept[InvalidGeometryException] {
      TrackSectionOrder.mValueRoundabout(list)
    }
  }

  test("roundabout with Against facing starting link") {
    val points = Seq(Seq(Point(100.00, 160.00),Point(80.87, 156.19),Point(64.64, 145.36)),
      Seq(Point(64.64, 145.36),Point(53.81, 129.13),Point(50.00, 110.00)),
      Seq(Point(50.00, 110.00),Point(53.81, 90.87),Point(64.64, 74.64)),
      Seq(Point(64.64, 74.64),Point(80.87, 63.81),Point(100.00, 60.00)),
      Seq(Point(100.00, 60.00),Point(119.13, 63.81),Point(135.36, 74.64)),
      Seq(Point(135.36, 74.64),Point(146.19, 90.87),Point(150.00, 110.00)),
      Seq(Point(150.00, 110.00),Point(146.19, 129.13),Point(135.36, 145.36)),
      Seq(Point(135.36, 145.36),Point(119.13, 156.19),Point(100.00, 160.00))
    )
    val geom = points.map(g =>
      if (g.head.y > g.last.y)
        g.reverse
      else g
    )
    //TODO the road address now have the linear location id and as been setted to 1L
    val list = geom.zip(0 to 7).map{ case (g, id) =>
      toProjectLink(rap, LinkStatus.New)(RoadAddress(id, 1L, 5, 1, RoadType.Unknown, Track.Combined, Continuous,
        0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), id, 0.0, 0.0, SideCode.Unknown, 0, (None, None), NoFloating,
        g, LinkGeomSource.NormalLinkInterface, 5, NoTermination, 0))
    }
    TrackSectionOrder.isRoundabout(list) should be (true)
    TrackSectionOrder.isRoundabout(list.init) should be (false)
    TrackSectionOrder.isRoundabout(list.tail) should be (false)
    val result = TrackSectionOrder.mValueRoundabout(list)
    result should have size(8)
    result.head.sideCode should be (AgainstDigitizing)
    result.forall(_.sideCode == result.head.sideCode) should be (false)
    result.head.geometry should be (list.head.geometry)
  }

  test("Ramp doesn't pass as a roundabout") {
    val points = Seq(Seq(Point(150.00, 40.00),Point(100.00, 160.00),Point(80.87, 156.19),Point(64.64, 145.36)),
      Seq(Point(64.64, 145.36),Point(53.81, 129.13),Point(50.00, 110.00)),
      Seq(Point(50.00, 110.00),Point(53.81, 90.87),Point(90.0, 74.64)),
      Seq(Point(90.0, 74.64), Point(160.00, 75.0))
    )
    val geom = points.map(g =>
      if (g.head.y > g.last.y)
        g.reverse
      else g
    )
    //TODO the road address now have the linear location id and as been setted to 1L
    val list = geom.zip(0 to 7).map{ case (g, id) =>
      toProjectLink(rap, LinkStatus.New)(RoadAddress(id, 1l, 5, 1, RoadType.Unknown, Track.Combined, Continuous,
        0L, 0L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), id, 0.0, 0.0, SideCode.Unknown, 0, (None, None), NoFloating,
        g, LinkGeomSource.NormalLinkInterface, 5, NoTermination, 0))
    }
    list.permutations.forall(l => !TrackSectionOrder.isRoundabout(l)) should be (true)
  }

  test("Pick the most in right project link on orderProjectLinksTopologyByGeometry") {
    //                                 (25,15)
    //                                  / |
    //                                /   |
    //                              /     |
    //                            2L      2L
    //                           /        |
    //                         /          |
    //   |---------0L---------|-----1L----|
    //(10,10)            (20,10)       (30,10)
    val projectLinks = List(
      dummyProjectLink(1L, Seq(Point(20, 10), Point(30, 10))),
      dummyProjectLink(0L, Seq(Point(10, 10), Point(15, 10), Point(20, 10))),
      dummyProjectLink(2L, Seq(Point(20, 10), Point(25, 15), Point(30, 10)))
    )

    val (ordered, _) = TrackSectionOrder.orderProjectLinksTopologyByGeometry((Point(10, 10), Point(10, 10)), projectLinks)

    ordered.map(_.linkId) should be (List(0L, 1L, 2L))
  }

  test("Pick the forward when there is more than 2 connected links on orderProjectLinksTopologyByGeometry") {
    //                                            3L
    //                                   /|------------------|
    //                              2L /  |
    //                               /    |
    //                              |     | 4L
    //                              |\    |
    //                              |  \  |
    //                              | 7L \|-------------------|
    //                          1L  |     -         5L
    //                              |     |
    //                              |     | 6L
    //                              |     |
    //                              -     -
    //

    val projectLinks = List(
      dummyProjectLink(1L, Seq(Point(2, 1), Point(2, 3), Point(2, 6)), Track.LeftSide),
      dummyProjectLink(2L, Seq(Point(2, 6), Point(3, 7), Point(4, 8)), Track.LeftSide),
      dummyProjectLink(3L, Seq(Point(4, 8), Point(6, 8), Point(8, 8)), Track.LeftSide),
      dummyProjectLink(4L, Seq(Point(4, 4), Point(4, 6), Point(4, 8)), Track.RightSide),
      dummyProjectLink(5L, Seq(Point(4, 4), Point(6, 4), Point(8, 4)), Track.RightSide),
      dummyProjectLink(6L, Seq(Point(4, 1), Point(4, 2), Point(4, 4)), Track.RightSide),
      dummyProjectLink(7L, Seq(Point(2, 6), Point(3, 5), Point(4, 4)), Track.RightSide)
    )

    val (rightOrdered, leftOrdered) = TrackSectionOrder.orderProjectLinksTopologyByGeometry((Point(4, 1), Point(2, 1)), projectLinks)

    rightOrdered.map(_.linkId) should be(List(6L, 4L, 7L, 5L))
    leftOrdered.map(_.linkId) should be(List(1L, 2L, 3L))
  }

  test("Pick the once connected when there is any with the same track code on orderProjectLinksTopologyByGeometry") {
    //                                 3l         4L
    //                             |--------|-----------|
    //                             |        |
    //                          1L |        | 2l
    //                             |        |
    //                             |        |
    //                             -        -

    val projectLinks = List(
      dummyProjectLink(1L, Seq(Point(1, 1), Point(1, 2), Point(1, 4)), Track.LeftSide),
      dummyProjectLink(2L, Seq(Point(3, 1), Point(3, 2), Point(3, 4)), Track.RightSide),
      dummyProjectLink(3L, Seq(Point(1, 4), Point(2, 4), Point(3, 4)), Track.Combined),
      dummyProjectLink(4L, Seq(Point(3, 4), Point(4, 4), Point(50, 4)), Track.Combined)
    )

    val (rightOrdered, leftOrdered) = TrackSectionOrder.orderProjectLinksTopologyByGeometry((Point(3, 1), Point(1, 1)), projectLinks)

    rightOrdered.map(_.linkId) should be(List(2L, 3L, 4L))
    leftOrdered.map(_.linkId) should be(List(1L, 3L, 4L))
  }

  test("Pick the same track when there is 2 connected links with only one with same track code on orderProjectLinksTopologyByGeometry") {
    //                             -        -
    //                             |        |
    //                          3L |        | 4l
    //                             |        |
    //                             |        |
    //                   |---------|--------|
    //                        1L        2L

    val projectLinks = List(
      dummyProjectLink(1L, Seq(Point(1, 1), Point(2, 1), Point(3, 1)), Track.Combined),
      dummyProjectLink(2L, Seq(Point(3, 1), Point(4, 1), Point(5, 1)), Track.Combined),
      dummyProjectLink(3L, Seq(Point(3, 1), Point(3, 2), Point(3, 4)), Track.LeftSide),
      dummyProjectLink(4L, Seq(Point(5, 1), Point(5, 3), Point(5, 4)), Track.RightSide)
    )

    val (rightOrdered, leftOrdered) = TrackSectionOrder.orderProjectLinksTopologyByGeometry((Point(1, 1), Point(1, 1)), projectLinks)

    rightOrdered.map(_.linkId) should be(List(1L, 2L, 4L))
    leftOrdered.map(_.linkId) should be(List(1L, 2L, 3L))
  }

}

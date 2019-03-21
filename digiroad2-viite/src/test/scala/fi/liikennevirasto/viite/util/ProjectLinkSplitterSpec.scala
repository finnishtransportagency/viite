package fi.liikennevirasto.viite.util

import java.util.Properties

import fi.liikennevirasto.digiroad2.asset.LinkGeomSource.{NormalLinkInterface, SuravageLinkInterface}
import fi.liikennevirasto.digiroad2.asset.SideCode.{AgainstDigitizing, TowardsDigitizing}
import fi.liikennevirasto.digiroad2.asset._
import fi.liikennevirasto.digiroad2.util.Track
import fi.liikennevirasto.digiroad2.util.Track.{Combined, Unknown}
import fi.liikennevirasto.digiroad2._
import fi.liikennevirasto.digiroad2.asset.ConstructionType.{InUse, Planned}
import fi.liikennevirasto.digiroad2.linearasset.RoadLink
import fi.liikennevirasto.digiroad2.oracle.OracleDatabase
import fi.liikennevirasto.digiroad2.client.vvh.{FeatureClass, VVHRoadlink}
import fi.liikennevirasto.digiroad2.dao.Sequences
import fi.liikennevirasto.digiroad2.service.RoadLinkService
import fi.liikennevirasto.viite.RoadType.{PublicRoad, UnknownOwnerRoad}
import fi.liikennevirasto.viite._
import fi.liikennevirasto.viite.dao.Discontinuity.{Continuous, MinorDiscontinuity}
import fi.liikennevirasto.viite.dao.LinkStatus.{New, NotHandled}
import fi.liikennevirasto.viite.dao._
import fi.liikennevirasto.viite.MaxSuravageToleranceToGeometry
import fi.liikennevirasto.viite.dao.CalibrationPointSource.ProjectLinkSource
import fi.liikennevirasto.viite.dao.FloatingReason.NoFloating
import fi.liikennevirasto.viite.model.{Anomaly, RoadAddressLink, RoadAddressLinkLike}
import fi.liikennevirasto.viite.process.RoadwayAddressMapper
import org.joda.time.DateTime
import org.scalatest.mockito.MockitoSugar
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.{reset, when}
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{BeforeAndAfter, FunSuite, Matchers}
import slick.driver.JdbcDriver.backend.Database
import slick.driver.JdbcDriver.backend.Database.dynamicSession
import slick.jdbc.StaticQuery.interpolation

class ProjectLinkSplitterSpec extends FunSuite with Matchers with BeforeAndAfter {

  val mockProjectService = MockitoSugar.mock[ProjectService]
  val mockRoadLinkService = MockitoSugar.mock[RoadLinkService]
  val mockRoadAddressService = MockitoSugar.mock[RoadAddressService]
  val mockRoadwayAddressMapper = MockitoSugar.mock[RoadwayAddressMapper]
  val mockEventBus = MockitoSugar.mock[DigiroadEventBus]
  val roadAddressService = new RoadAddressService(mockRoadLinkService, new RoadwayDAO, new LinearLocationDAO, new RoadNetworkDAO, new UnaddressedRoadLinkDAO, mockRoadwayAddressMapper, mockEventBus) {
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

  val projectDAO = new ProjectDAO

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

  test("Test ProjectLinkSplitter.intersectionPoint() When using Segment1 [(0.0, 0.0), (10.0, 0.0)] and Segment2 [(0.0, 1.0), (10.0, -1.0)] Then returns (5.0, 0.0).") {
    ProjectLinkSplitter.intersectionPoint((Point(0.0, 0.0), Point(10.0, 0.0)), (Point(0.0, 1.0), Point(10.0, -1.0))) should be (Some(Point(5.0, 0.0)))
  }

  test("Test ProjectLinkSplitter.intersectionPoint() When using Segment1 [(10.0, 2.0), (0.0, 1.0)] and Segment2 [(0.0, 1.0), (10.0, 2.0)], the segments are overlapping Then returns None.") {
    ProjectLinkSplitter.intersectionPoint((Point(10.0, 2.0), Point(0.0, 1.0)), (Point(0.0, 1.0), Point(10.0, 2.0))) should be (None)
  }

  test("Test ProjectLinkSplitter.intersectionPoint() When using Segment1 [(0.0, 1.5), (10.0, 2.5)] and Segment2 [(0.0, 1.0), (10.0, 2.0)], the segments are parallel Then returns None.") {
    ProjectLinkSplitter.intersectionPoint((Point(0.0, 1.5), Point(10.0, 2.5)), (Point(0.0, 1.0), Point(10.0, 2.0))) should be (None)
  }

  test("Test ProjectLinkSplitter.intersectionPoint() When using Segment1 [(5.0, 0.0), (5.0, 10.0)] and Segment2 [(0.0, 1.0), (10.0, -1.0)] Then returns (5.0, 0.0).") {
    ProjectLinkSplitter.intersectionPoint((Point(5.0, 0.0), Point(5.0, 10.0)), (Point(0.0, 1.0), Point(10.0, -1.0))) should be (Some(Point(5.0, 0.0)))
  }

  test("Test ProjectLinkSplitter.intersectionPoint() When using Segment1 [(5.0, 0.0), (5.0, 10.0)] and Segment2 [(5.0, 0.0), (5.0, 5.0)] then repeat but adding 1 to Point1 of Segment1 Then both should return None.") {
    ProjectLinkSplitter.intersectionPoint((Point(5.0, 0.0), Point(5.0, 10.0)), (Point(5.0, 0.0), Point(5.0, 5.0))) should be (None)
    ProjectLinkSplitter.intersectionPoint((Point(6.0, 0.0), Point(6.0, 10.0)), (Point(5.0, 0.0), Point(5.0, 5.0))) should be (None)
  }

  test("Test ProjectLinkSplitter.intersectionPoint() When using Segment1 [(5.0, 0.0), (5.0005, 10.0)] and Segment2 [(5.0, 0.0), (4.9995, 5.0)] Then return (5.0, 0.0).") {
    ProjectLinkSplitter.intersectionPoint((Point(5.0, 0.0), Point(5.0005, 10.0)), (Point(5.0, 0.0), Point(4.9995, 5.0))) should be (Some(Point(5.0, 0.0)))
  }

  test("Test ProjectLinkSplitter.intersectionPoint() When using Segment1 with both X and Y components and Segment2 fully vertical Then return the confirmation that there is an interception.") {
    ProjectLinkSplitter.intersectionPoint((Point(4.5, 10.041381265149107), Point(5.526846871753764, 16.20246249567169)),
      (Point(5.0, 10.0), Point(5.0, 16.0))).isEmpty should be (false)
  }

  test("Test ProjectLinkSplitter.intersectionPoint() When using nearly vertical segments Then return the interception point when applicable.") {
    ProjectLinkSplitter.intersectionPoint((Point(5.0, 0.0), Point(5.0005, 10.0)), (Point(5.0, 0.0), Point(5.0005, 5.0))) should be (Some(Point(5.0, 0.0)))
    ProjectLinkSplitter.intersectionPoint((Point(5.0, 0.0), Point(5.0005, 10.0)), (Point(5.0, 11.0), Point(5.0005, 15.0))) should be (None)
  }

  test("Test ProjectLinkSplitter.geometryToBoundaries() When using a regular geometry Then return the correct boundaries that said geometry translate to.") {
    val geom = Seq(Point(5.0, 0.0), Point(5.0, 10.0), Point(6.0, 16.0), Point(9.0, 20.0), Point(14.0, 18.0), Point(16.0, 6.0))
    val (left, right) = ProjectLinkSplitter.geometryToBoundaries(geom)
    left.size should be (geom.size)
    right.size should be (geom.size)
    left.zip(right).zip(geom).foreach { case ((l, r), g) =>
      // Assume max 90 degree turn on test data: left and right corners are sqrt(2) * MaxSuravageTolerance away
      l.distance2DTo(g) should be >= MaxSuravageToleranceToGeometry
      l.distance2DTo(g) should be <= (MaxSuravageToleranceToGeometry * Math.sqrt(2.0))
      l.distance2DTo(g) should be (r.distance2DTo(g) +- 0.001)
    }
  }

  test("Test ProjectLinkSplitter.geometryToBoundaries() When using 2 geometries that intercept but the findIntersection cannot find it Then return a possible interception point for them using the left boundary") {
    val geom = Seq(Point(5.0, 0.0), Point(5.0, 10.0), Point(6.0, 16.0), Point(9.0, 20.0), Point(14.0, 18.0), Point(16.0, 6.0))
    val (left, right) = ProjectLinkSplitter.geometryToBoundaries(geom)
    val template = Seq(Point(5.0, 0.0), Point(5.0, 10.0), Point(5.0, 16.0), Point(4.0, 20.0))
    val interOpt = ProjectLinkSplitter.findIntersection(left, template, Some(Epsilon), Some(Epsilon))
    ProjectLinkSplitter.findIntersection(right, template, Some(Epsilon), Some(Epsilon)) should be (None)
    interOpt.isEmpty should be (false)
    interOpt.foreach { p =>
      p.x should be (5.0 +- 0.001)
      p.y should be (13.0414 +- 0.001)
    }
  }

  test("Test ProjectLinkSplitter.geometryToBoundaries() When using 2 geometries that intercept but the findIntersection cannot find it Then return a possible interception point for them using the right boundary.") {
    val geom = Seq(Point(5.0, 0.0), Point(5.0, 10.0), Point(6.0, 16.0), Point(9.0, 20.0), Point(14.0, 18.0), Point(16.0, 6.0))
    val (left, right) = ProjectLinkSplitter.geometryToBoundaries(geom)
    val template = Seq(Point(5.0, 0.0), Point(5.0, 10.0), Point(5.5, 13.0), Point(10.0, 15.0))
    val interOpt = ProjectLinkSplitter.findIntersection(right, template, Some(Epsilon), Some(Epsilon))
    ProjectLinkSplitter.findIntersection(left, template, Some(Epsilon), Some(Epsilon)) should be (None)
    interOpt.isEmpty should be (false)
    interOpt.foreach { p =>
      p.x should be (6.04745 +- 0.001)
      p.y should be (13.2433 +- 0.001)
    }
  }

  test("Test ProjectLinkSplitter.findMatchingGeometrySegment() When using a geometry with a slight difference from a specific template Then return a geometry that fits the template.") {
    val geom = Seq(Point(5.0, 0.0), Point(5.0, 10.0), Point(6.0, 16.0), Point(9.0, 20.0), Point(14.0, 18.0), Point(16.0, 6.0))
    val geomT = Seq(Point(5.0, 0.0), Point(5.0, 10.0), Point(5.0, 16.0), Point(4.0, 20.0))
    val suravage = VVHRoadlink(1234L, 0, geom, State, TrafficDirection.BothDirections, FeatureClass.AllOthers)
    val template = VVHRoadlink(1235L, 0, geomT, State, TrafficDirection.BothDirections, FeatureClass.AllOthers)
    val splitGeometryOpt = ProjectLinkSplitter.findMatchingGeometrySegment(suravage, template)
    splitGeometryOpt.isEmpty should be (false)
    val g = splitGeometryOpt.get
    GeometryUtils.geometryLength(g) should be < GeometryUtils.geometryLength(geomT)
    GeometryUtils.geometryLength(g) should be > (0.0)
    GeometryUtils.minimumDistance(g.last, geomT) should be < MaxDistanceForConnectedLinks
    g.length should be (3)
    g.lastOption.foreach { p =>
      p.x should be (5.0 +- 0.001)
      p.y should be (13.0414 +- 0.001)
    }
  }

  test("Test ProjectLinkSplitter.findMatchingGeometrySegment() When using a geometry with a slight difference and having differing digitization directions from a specific template Then return a geometry that fits the template.") {
    val geom = Seq(Point(5.0, 0.0), Point(15.0, 0.0), Point(16.0, 0.5), Point(20.0, 0.8))
    val geomT = Seq(Point(5.0, 0.0), Point(15.0, 0.0), Point(17.0, -0.5), Point(20.0, -2.0)).reverse
    val suravage = VVHRoadlink(1234L, 0, geom, State, TrafficDirection.BothDirections, FeatureClass.AllOthers)
    val template = VVHRoadlink(1235L, 0, geomT, State, TrafficDirection.BothDirections, FeatureClass.AllOthers)
    val splitGeometryOpt = ProjectLinkSplitter.findMatchingGeometrySegment(suravage, template)
    splitGeometryOpt.isEmpty should be (false)
    val g = splitGeometryOpt.get
    GeometryUtils.geometryLength(g) should be < GeometryUtils.geometryLength(geomT)
    GeometryUtils.geometryLength(g) should be > (0.0)
    GeometryUtils.minimumDistance(g.last, geomT) should be < MaxDistanceForConnectedLinks
    g.length should be (3)
    g.headOption.foreach { p =>
      p.x should be (15.75 +- 0.1)
      p.y should be (-.19 +- 0.1)
    }
  }

  test("Test ProjectLinkSplitter.findMatchingGeometrySegment() When using geometries that are only touching each other  Then the return should be None.") {
    val template = ProjectLink(452389, 77, 14, Combined, Continuous, 4286, 4612, 4286, 4612, None, None, None, 6138625, 0.0, 327.138,
      TowardsDigitizing, (None, Some(ProjectLinkCalibrationPoint(6138625, 327.138, 4612, ProjectLinkSource))), NoFloating, List(Point(445417.266, 7004142.049, 0.0),
        Point(445420.674, 7004144.679, 0.0), Point(445436.147, 7004155.708, 0.0), Point(445448.743, 7004164.052, 0.0),
        Point(445461.586, 7004172.012, 0.0), Point(445551.316, 7004225.769, 0.0), Point(445622.099, 7004268.174, 0.0),
        Point(445692.288, 7004310.224, 0.0), Point(445696.301, 7004312.628, 0.0)), 452278, NotHandled, PublicRoad,
      NormalLinkInterface, 327.13776793597697, 295486, 295487, 9L, false, None, 85088L)
    val suravage = ProjectLink(-1000, 0, 0, Unknown, Continuous, 0, 0, 0, 0, None, None, Some("silari"), 499972931, 0.0, 313.38119201522017,
      TowardsDigitizing, (None, None), NoFloating, List(Point(445186.594, 7003930.051, 0.0), Point(445278.988, 7004016.523, 0.0),
        Point(445295.313, 7004031.801, 0.0), Point(445376.923, 7004108.181, 0.0), Point(445391.041, 7004120.899, 0.0),
        Point(445405.631, 7004133.071, 0.0), Point(445417.266, 7004142.049, 0.0)), 452278, New, UnknownOwnerRoad,
      SuravageLinkInterface, 313.38119201522017, 0, 0, 9L, false, None, 85088L)
    ProjectLinkSplitter.findMatchingGeometrySegment(suravage, template) should be (None)
  }

  test("Test ProjectLinkSplitter.split() When sending 3 project links, 2 of them identical, the other one has a light difference Then return the aligned split result.") {
    val sGeom = Seq(Point(5.0, 0.0), Point(15.0, 0.0), Point(16.0, 0.5), Point(20.0, 0.8))
    val tGeom = Seq(Point(5.0, 0.0), Point(15.0, 0.0), Point(16.0, 1.5), Point(20.0, 4.8))
    val rGeom = Seq(Point(5.0, 0.0), Point(15.0, 0.0), Point(16.0, 0.5), Point(20.0, 0.8))

    val sLen = GeometryUtils.geometryLength(sGeom)
    val tLen = GeometryUtils.geometryLength(tGeom)
    val suravage = ProjectLink(0L, 0L, 0L, Track.Unknown, Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None, None, 123L, 0.0, sLen,
      SideCode.Unknown, (None, None), NoFloating, sGeom, 1L, LinkStatus.NotHandled, RoadType.Unknown, LinkGeomSource.SuravageLinkInterface,
      sLen, 0L, 0L, 5, reversed = false, None, 85088L)
    val template = ProjectLink(2L, 5L, 205L, Track.Combined, Discontinuity.Continuous, 1024L, 1040L, 1024L, 1040L, None, None, None, 124L, 0.0, tLen,
      SideCode.TowardsDigitizing, (None, None), NoFloating, tGeom, 1L, LinkStatus.NotHandled, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface,
      tLen, 0L, 0L, 5, reversed = false, None, 85088L)
    val roadLink = RoadLink(12345l, rGeom, GeometryUtils.geometryLength(rGeom), State, 99, TrafficDirection.TowardsDigitizing,
      UnknownLinkType, Some("25.06.2015 03:00:00"), Some("vvh_modified"), Map("MUNICIPALITYCODE" -> BigInt.apply(749)), Planned, NormalLinkInterface)
    val result = ProjectLinkSplitter.split(roadLink, suravage, template, Seq(template), SplitOptions(Point(14.5, 0.0), LinkStatus.UnChanged,
  LinkStatus.New, 5L, 205L, Track.Combined, Discontinuity.EndOfRoad, 8L, LinkGeomSource.NormalLinkInterface,
  RoadType.PublicRoad, 1L, ProjectCoordinates(0, 1, 1)))
    val (splitA, splitB, terminatedLink) = (result.splitA, result.splitB, result.allTerminatedProjectLinks)
    terminatedLink.foreach(t => t.status should be (LinkStatus.Terminated))
    terminatedLink.maxBy(_.endAddrMValue).endAddrMValue should be (template.endAddrMValue)
    terminatedLink.minBy(_.startAddrMValue).startAddrMValue should be (splitB.startAddrMValue)
    splitA.endAddrMValue should be (splitB.startAddrMValue)
    splitB.discontinuity should be (Discontinuity.EndOfRoad)
    splitA.discontinuity should be (Continuous)
  }

  test("Test ProjectLinkSplitter.split() When using project links with 2 template roadlinks with same linkId Then return the aligned split result.") {
    val sGeom = Seq(Point(5.0, 0.0), Point(15.0, 0.0), Point(20.0, 0.0))
    val tGeom = Seq(Point(5.0, 0.0), Point(15.0, 0.0), Point(21.0, 0.0))
    val t2Geom = Seq(Point(19.9, 0.0), Point(23, 0.0), Point(25.0, 0.0))
    val rGeom = Seq(Point(5.0, 0.0), Point(15.0, 0.0), Point(16.0, 0.0), Point(25.0, 0.0))

    val sLen = GeometryUtils.geometryLength(sGeom)
    val tLen = GeometryUtils.geometryLength(tGeom)
    val suravage = ProjectLink(0L, 0L, 0L, Track.Unknown, Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None, None, 123L, 0.0, sLen,
      SideCode.Unknown, (None, None), NoFloating, sGeom, 1L, LinkStatus.NotHandled, RoadType.Unknown, LinkGeomSource.SuravageLinkInterface,
      sLen, 0L, 0L, 5, reversed = false, None, 85088L)
    val template = ProjectLink(2L, 5L, 205L, Track.Combined, Discontinuity.Continuous, 1024L, 1040L, 1024L, 1040L, None, None, None, 124L, 0.0, tLen,
      SideCode.TowardsDigitizing, (None, None), NoFloating, tGeom, 1L, LinkStatus.NotHandled, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface,
      tLen, 0L, 0L, 5, reversed = false, None, 85088L)
    val template2 = ProjectLink(3L, 5L, 205L, Track.Combined, Discontinuity.Continuous, 1024L, 1040L, 1024L, 1040L, None, None, None, 124L, 0.0, tLen,
      SideCode.TowardsDigitizing, (None, None), NoFloating, t2Geom, 1L, LinkStatus.NotHandled, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface,
      tLen, 0L, 0L, 5, reversed = false, None, 85088L)
    val roadLink = RoadLink(12345l, rGeom, GeometryUtils.geometryLength(rGeom), State, 99, TrafficDirection.TowardsDigitizing,
      UnknownLinkType, Some("25.06.2015 03:00:00"), Some("vvh_modified"), Map("MUNICIPALITYCODE" -> BigInt.apply(749)), Planned, NormalLinkInterface)
    val result = ProjectLinkSplitter.split(roadLink, suravage, template, Seq(template, template2), SplitOptions(Point(14.5, 0.0), LinkStatus.UnChanged,
      LinkStatus.New, 5L, 205L, Track.Combined, Discontinuity.EndOfRoad, 8L, LinkGeomSource.NormalLinkInterface,
      RoadType.PublicRoad, 1L, ProjectCoordinates(0, 1, 1)))
    val (splitA, splitB, terminatedLinks) = (result.splitA, result.splitB, result.allTerminatedProjectLinks)
    terminatedLinks.foreach(t => t.status should be (LinkStatus.Terminated))
    terminatedLinks.size should be (2)
    terminatedLinks.maxBy(_.endAddrMValue).endAddrMValue should be (Math.min(template.endAddrMValue, template2.endAddrMValue))
    terminatedLinks.minBy(_.startAddrMValue).startAddrMValue should be (Math.min(splitB.startAddrMValue,splitA.startAddrMValue))
    splitA.endAddrMValue should be (splitB.startAddrMValue)
    splitB.discontinuity should be (Discontinuity.EndOfRoad)
    splitA.discontinuity should be (Continuous)
  }

  test("Test ProjectLinkSplitter.split() When using project links with incorrect digitization Then return the aligned split result, independently of the digitization.") {
    val sGeom = Seq(Point(5.0, 0.0), Point(15.0, 0.0), Point(16.0, 0.6), Point(20.0, 0.1))
    val tGeom = Seq(Point(5.0, 0.0), Point(15.0, 0.0), Point(16.0, 5.0), Point(20.0, 1.0)).reverse
    val rGeom = sGeom
    val sLen = GeometryUtils.geometryLength(sGeom)
    val tLen = GeometryUtils.geometryLength(tGeom)
    val suravage = ProjectLink(0L, 0L, 0L, Track.Unknown, Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None, None, 123L, 0.0, sLen,
      SideCode.Unknown, (None, None), NoFloating, sGeom, 1L, LinkStatus.NotHandled, RoadType.Unknown, LinkGeomSource.SuravageLinkInterface,
      sLen, 0L, 0L, 5, false, None, 85088L)
    val template = ProjectLink(2L, 5L, 205L, Track.Combined, Discontinuity.Continuous, 1024L, 1040L, 1024L, 1040L, None, None, None, 124L, 0.0, tLen,
      SideCode.TowardsDigitizing, (None, None), NoFloating, tGeom, 1L, LinkStatus.NotHandled, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface,
      tLen, 0L, 0L, 5, false, None, 85088L)
    val templateList = Seq(template)
    val roadLink = RoadLink(12345l, rGeom, GeometryUtils.geometryLength(rGeom), State, 99, TrafficDirection.TowardsDigitizing,
      UnknownLinkType, Some("25.06.2015 03:00:00"), Some("vvh_modified"), Map("MUNICIPALITYCODE" -> BigInt.apply(749)), Planned, NormalLinkInterface)

    val splitResult = ProjectLinkSplitter.split(roadLink, suravage, template, templateList, SplitOptions(Point(15.5, -0.75), LinkStatus.UnChanged,
      LinkStatus.New, 5L, 205L, Track.Combined, Discontinuity.Continuous, 8L, LinkGeomSource.NormalLinkInterface,
      RoadType.PublicRoad, 1L, ProjectCoordinates(0, 1, 1)))
    val (splitA, splitB) = (splitResult.splitA, splitResult.splitB)
    val terminatedLink = splitResult.terminatedProjectLink
    terminatedLink.status should be (LinkStatus.Terminated)
    splitB.endAddrMValue should be (splitA.startAddrMValue)
    splitA.geometry.head should be (template.geometry.last)
    Seq(splitA, splitB).foreach { l =>
      l.startAddrMValue == terminatedLink.startAddrMValue || l.status == LinkStatus.UnChanged should be(true)
      l.linkGeomSource should be (LinkGeomSource.SuravageLinkInterface)
      l.status == LinkStatus.New || l.status == LinkStatus.UnChanged || l.status == LinkStatus.Transfer should be (true)
      l.roadNumber should be (template.roadNumber)
      l.roadPartNumber should be (template.roadPartNumber)
      l.sideCode should be (SideCode.AgainstDigitizing)
    }
  }

  test("Test ProjectLinkSplitter.split() When using oposing project links Then return the aligned split result.") {
    val sGeom = Seq(Point(5.0, 0.0), Point(15.0, 0.0), Point(16.0, 0.5), Point(20.0, -0.8)).reverse
    val tGeom = Seq(Point(5.0, 0.0), Point(15.0, 0.0), Point(16.0, 0.5), Point(20.0, 4.8))
    val rGeom = tGeom
    val sLen = GeometryUtils.geometryLength(sGeom)
    val tLen = GeometryUtils.geometryLength(tGeom)
    val rLen = GeometryUtils.geometryLength(rGeom)
    val suravage = ProjectLink(0L, 0L, 0L, Track.Unknown, Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None, None, 123L, 0.0, sLen,
      SideCode.Unknown, (None, None), NoFloating, sGeom, 1L, LinkStatus.NotHandled, RoadType.Unknown, LinkGeomSource.SuravageLinkInterface,
      sLen, 0L, 0L, 5, false, None, 85088L)
    val template = ProjectLink(2L, 5L, 205L, Track.Combined, Discontinuity.EndOfRoad, 1024L, 1040L, 1024L, 1040L, None, None, None, 124L, 0.0, tLen,
      SideCode.TowardsDigitizing, (None, None), NoFloating, tGeom, 1L, LinkStatus.NotHandled, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface,
      tLen, 0L, 0L, 5, false, None, 85088L)
    val roadLink = RoadLink(12345l, rGeom, rLen, State, 99, TrafficDirection.TowardsDigitizing,
      UnknownLinkType, Some("25.06.2015 03:00:00"), Some("vvh_modified"), Map("MUNICIPALITYCODE" -> BigInt.apply(749)), Planned, NormalLinkInterface)

    val splitResult = ProjectLinkSplitter.split(roadLink, suravage, template, Seq(template), SplitOptions(Point(15.5, 0.75), LinkStatus.UnChanged,
      LinkStatus.New, 5L, 205L, Track.Combined, Discontinuity.MinorDiscontinuity, 8L, LinkGeomSource.NormalLinkInterface,
      RoadType.PublicRoad, 1L, ProjectCoordinates(0, 1, 1)))

    val (splitA, splitB) =(splitResult.splitA, splitResult.splitB)
    val terminatedLink = splitResult.terminatedProjectLink
    terminatedLink.status should be (LinkStatus.Terminated)
    terminatedLink.endAddrMValue should be (template.endAddrMValue)
    terminatedLink.startAddrMValue should be (splitB.endAddrMValue)
    terminatedLink.startAddrMValue should be (splitA.startAddrMValue)
    splitB.discontinuity should be (Continuous)
    splitA.discontinuity should be (MinorDiscontinuity)
    GeometryUtils.minimumDistance(terminatedLink.geometry.head, splitB.geometry) should be < MaxSuravageToleranceToGeometry
    GeometryUtils.areAdjacent(terminatedLink.geometry, template.geometry) should be (true)
    Seq(splitA, splitB).foreach { l =>
      l.sideCode should be (SideCode.AgainstDigitizing)
      l.startAddrMValue == template.startAddrMValue || l.startMValue == 0.0 should be (true)
    }
  }

  test("Test ProjectLinkSplitter.split() When using project links that join at the end Then return the aligned split result.") {
    val sGeom = Seq(Point(5.0, 0.0), Point(15.0, 0.0), Point(16.0, 0.5), Point(20.0, 3.8))
    val tGeom = Seq(Point(5.0, 0.0), Point(15.0, 0.0), Point(16.0, 0.5), Point(20.0, 3.8))
    val rGeom = Seq(Point(5.0, 0.0), Point(15.0, 0.0), Point(16.0, 0.5), Point(20.0, 3.8))
    val sLen = GeometryUtils.geometryLength(sGeom)
    val tLen = GeometryUtils.geometryLength(tGeom)
    val rLen = GeometryUtils.geometryLength(rGeom)
    val suravage = ProjectLink(0L, 0L, 0L, Track.Unknown, Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None, None, 123L, 0.0, sLen,
      SideCode.Unknown, (None, None), NoFloating, sGeom, 1L, LinkStatus.NotHandled, RoadType.Unknown, LinkGeomSource.SuravageLinkInterface,
      sLen, 0L, 0L, 5, false, None, 85088L)
    val template = ProjectLink(2L, 5L, 205L, Track.Combined, Discontinuity.Continuous, 1024L, 1040L, 1024L, 1040L, None, None, None, 124L, 0.0, sLen,
      SideCode.TowardsDigitizing, (None, None), NoFloating, tGeom, 1L, LinkStatus.NotHandled, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface,
      tLen, 10L, 0L, 5, false, None, 85088L)
    val roadLink = RoadLink(12345l, rGeom, rLen, State, 99, TrafficDirection.TowardsDigitizing,
      UnknownLinkType, Some("25.06.2015 03:00:00"), Some("vvh_modified"), Map("MUNICIPALITYCODE" -> BigInt.apply(749)), Planned, NormalLinkInterface)


    val splitResult = ProjectLinkSplitter.split(roadLink, suravage, template,Seq(template), SplitOptions(Point(15.0, 0.0), LinkStatus.Transfer,
      LinkStatus.New, 5L, 205L, Track.Combined, Discontinuity.Continuous, 8L, LinkGeomSource.NormalLinkInterface,
      RoadType.PublicRoad, 1L, ProjectCoordinates(0, 1, 1)))

    val (splitA, splitB) = if(splitResult.splitA.status == New) (splitResult.splitB, splitResult.splitA) else (splitResult.splitA, splitResult.splitB)
    val terminatedLink = splitResult.terminatedProjectLink
    terminatedLink.status should be (LinkStatus.Terminated)
    terminatedLink.startAddrMValue should be(splitA.startAddrMValue)
    terminatedLink.endAddrMValue should be(template.endAddrMValue)
    splitA.endAddrMValue should be (template.endAddrMValue)
    GeometryUtils.areAdjacent(terminatedLink.geometry, splitB.geometry) should be (true)
    GeometryUtils.areAdjacent(terminatedLink.geometry, splitA.geometry) should be (true)
    GeometryUtils.areAdjacent(terminatedLink.geometry, template.geometry) should be (true)
    Seq(splitA, splitB).foreach { l =>
      l.roadwayId should be (template.roadwayId)
      GeometryUtils.areAdjacent(l.geometry, suravage.geometry) should be (true)
      l.roadNumber should be (template.roadNumber)
      l.roadPartNumber should be (template.roadPartNumber)
      l.sideCode should be (SideCode.TowardsDigitizing)
    }

  }

  test("Test ProjectLinkSplitter.split() When splitting by suravage border -> uniq suravage link -> (Newlink length -> 0, Unchangedlink length -> original suravage length Then return the aligned split result.") {
    val sGeom = Seq(Point(480428.187, 7059183.911), Point(480441.534, 7059195.878), Point(480445.646, 7059199.566),
      Point(480451.056, 7059204.417), Point(480453.065, 7059206.218), Point(480456.611, 7059209.042), Point(480463.941, 7059214.747))
    val tGeom = Seq(Point(480428.187, 7059183.911), Point(480453.614, 7059206.710), Point(480478.813, 7059226.322),
      Point(480503.826, 7059244.02), Point(480508.221, 7059247.010))
    val rGeom = Seq(Point(480428.187, 7059183.911), Point(480441.534, 7059195.878), Point(480445.646, 7059199.566),
      Point(480451.056, 7059204.417), Point(480453.065, 7059206.218), Point(480456.611, 7059209.042), Point(480463.941, 7059214.747))
    val sLen = GeometryUtils.geometryLength(sGeom)
    val tLen = GeometryUtils.geometryLength(tGeom)
    val rLen = GeometryUtils.geometryLength(rGeom)
    val suravage = ProjectLink(0L, 0L, 0L, Track.Unknown, Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None, None, 123L, 0.0, sLen,
      SideCode.Unknown, (None, None), NoFloating, sGeom, 1L, LinkStatus.NotHandled, RoadType.Unknown, LinkGeomSource.SuravageLinkInterface,
      sLen, 0L, 0L, 9L, false, None, 85088L)
    val template = ProjectLink(2L, 27L, 22L, Track.Combined, Discontinuity.Continuous, 5076L, 5131L, 5076L, 5131L, None, None, None, 124L, 0.0, tLen,
      SideCode.TowardsDigitizing, (None, None), NoFloating, tGeom, 1L, LinkStatus.NotHandled, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface,
      tLen, 0L, 0L, 9L, false, None, 85088L)
    val roadLink = RoadLink(12345l, rGeom, rLen, State, 99, TrafficDirection.TowardsDigitizing,
      UnknownLinkType, Some("25.06.2015 03:00:00"), Some("vvh_modified"), Map("MUNICIPALITYCODE" -> BigInt.apply(749)), Planned, NormalLinkInterface)

    val splitResult = ProjectLinkSplitter.split(roadLink, suravage, template, Seq(template), SplitOptions(Point(480463.941, 7059214.747), LinkStatus.UnChanged,
      LinkStatus.New, 27L, 22L, Track.Combined, Discontinuity.Continuous, 8L, LinkGeomSource.NormalLinkInterface,
      RoadType.PublicRoad, 1L, ProjectCoordinates(0, 1, 1)))

    val terminatedLink = splitResult.terminatedProjectLink
    val unChangedLink = if (splitResult.splitA.status == LinkStatus.UnChanged) splitResult.splitA else splitResult.splitB
    terminatedLink.status should be (LinkStatus.Terminated)
    terminatedLink.endAddrMValue should be (template.endAddrMValue)
    terminatedLink.endMValue should be (template.endMValue)
    GeometryUtils.areAdjacent(terminatedLink.geometry, unChangedLink.geometry) should be (true)
    (GeometryUtils.areAdjacent(unChangedLink.geometry.head, sGeom.head) || GeometryUtils.areAdjacent(unChangedLink.geometry.last, sGeom.last)) should be (true)
    GeometryUtils.geometryLength(unChangedLink.geometry) should be (sLen)
    unChangedLink.startAddrMValue should be (template.startAddrMValue)
    unChangedLink.endAddrMValue should be (terminatedLink.startAddrMValue)
  }

  test("Test ProjectLinkSplitter.split() When splitting by ssuravage border -> uniq suravage link ->  (Newlink length -> original suravage length, Unchangedlink length -> 0) Then return the aligned split result.") {
    val sGeom = Seq(Point(480428.187, 7059183.911), Point(480441.534, 7059195.878), Point(480445.646, 7059199.566),
      Point(480451.056, 7059204.417), Point(480453.065, 7059206.218), Point(480456.611, 7059209.042), Point(480463.941, 7059214.747))
    val tGeom = Seq(Point(480428.187, 7059183.911), Point(480453.614, 7059206.710), Point(480478.813, 7059226.322),
      Point(480503.826, 7059244.02), Point(480508.221, 7059247.010))
    val rGeom = Seq(Point(480428.187, 7059183.911), Point(480441.534, 7059195.878), Point(480445.646, 7059199.566),
      Point(480451.056, 7059204.417), Point(480453.065, 7059206.218), Point(480456.611, 7059209.042), Point(480463.941, 7059214.747))
    val sLen = GeometryUtils.geometryLength(sGeom)
    val tLen = GeometryUtils.geometryLength(tGeom)
    val rLen = GeometryUtils.geometryLength(rGeom)
    val suravage = ProjectLink(0L, 0L, 0L, Track.Unknown, Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None, None, 123L, 0.0, sLen,
      SideCode.Unknown, (None, None), NoFloating, sGeom, 1L, LinkStatus.NotHandled, RoadType.Unknown, LinkGeomSource.SuravageLinkInterface,
      sLen, 0L, 0L, 9L, false, None, 85088L)
    val template = ProjectLink(2L, 27L, 22L, Track.Combined, Discontinuity.Continuous, 5076L, 5131L, 5076L, 5131L, None, None, None, 124L, 0.0, tLen,
      SideCode.TowardsDigitizing, (None, None), NoFloating, tGeom, 1L, LinkStatus.NotHandled, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface,
      tLen, 0L, 0L, 9L, false, None, 85088L)
    val roadLink = RoadLink(12345l, rGeom, rLen, State, 99, TrafficDirection.TowardsDigitizing,
      UnknownLinkType, Some("25.06.2015 03:00:00"), Some("vvh_modified"), Map("MUNICIPALITYCODE" -> BigInt.apply(749)), Planned, NormalLinkInterface)

    val splitResult = ProjectLinkSplitter.split(roadLink, suravage, template, Seq(template), SplitOptions(Point(480428.187, 7059183.911), LinkStatus.UnChanged,
      LinkStatus.New, 27L, 22L, Track.Combined, Discontinuity.Continuous, 8L, LinkGeomSource.NormalLinkInterface,
      RoadType.PublicRoad, 1L, ProjectCoordinates(0, 1, 1)))

    val terminatedLink = splitResult.terminatedProjectLink
    val newLink = if(splitResult.splitA.status == New) splitResult.splitA else splitResult.splitB
    terminatedLink.status should be (LinkStatus.Terminated)
    terminatedLink.startAddrMValue should be (template.startAddrMValue)
    terminatedLink.endAddrMValue should be (template.endAddrMValue)
    terminatedLink.endMValue should be (template.endMValue)
    newLink.startAddrMValue should be (template.startAddrMValue)
    newLink.endAddrMValue should be <= template.endAddrMValue
    GeometryUtils.areAdjacent(terminatedLink.geometry, newLink.geometry) should be (true)
    GeometryUtils.areAdjacent(newLink.geometry.head, sGeom.head) should be (true)
    GeometryUtils.areAdjacent(newLink.geometry.last, sGeom.last) should be (true)
    GeometryUtils.geometryLength(newLink.geometry) should be (GeometryUtils.geometryLength(sGeom))
  }

  test("Test ProjectLinkSplitter.split() When simulating a live split Then return the aligned split result.") {
    runWithRollback {
      val projectId = Sequences.nextViitePrimaryKeySeqValue
      val geom = Seq(Point(0, 0), Point(0, 45.3), Point(0, 87))
      val roadLink = RoadLink(1, geom , GeometryUtils.geometryLength(geom), State, 99, TrafficDirection.AgainstDigitizing,
        UnknownLinkType, Some("25.06.2015 03:00:00"), Some("vvh_modified"), Map("MUNICIPALITYCODE" -> BigInt.apply(749)), InUse, NormalLinkInterface)
      //TODO this roadAddressLink have linearLocationId equal to zero, just to compile.
      val suravageAddressLink = RoadAddressLink(Sequences.nextViitePrimaryKeySeqValue, 0, 2, Seq(Point(0, 0), Point(0, 45.3), Point(0, 123)), 123,
        AdministrativeClass.apply(1), LinkType.apply(1), ConstructionType.Planned, LinkGeomSource.SuravageLinkInterface, RoadType.PublicRoad, Some("testRoad"), None,
        8, None, None, null, 1, 1, Track.Combined.value, 8, Discontinuity.Continuous.value, 0, 123, "", "", 0, 123, SideCode.Unknown, None, None, Anomaly.None)

      when(mockRoadAddressService.getSuravageRoadLinkAddressesByLinkIds(any[Set[Long]])).thenReturn(Seq(suravageAddressLink))
      when(mockRoadLinkService.getRoadLinksAndComplementaryFromVVH(any[BoundingRectangle], any[Set[Int]])).thenReturn(Seq(roadLink))
      when(mockRoadLinkService.getRoadLinkByLinkIdFromVVH(any[Long])).thenReturn(Some(roadLink))
      val rap = Project(projectId, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("2700-01-01"), "TestUser", DateTime.parse("2700-01-01"), DateTime.now(), "Some additional info", List.empty[ProjectReservedPart], None)
      projectDAO.create(rap)
      val points = Seq(Point(0.0, 0.0, 0.0), Point(0.0, 45.3 ,0.0), Point(0.0, 123.5 ,0.0), Point(0.5, 140.0 ,0.0)).flatMap(p => Seq(p.x, p.y, p.z))
      val templateGeom = s"MDSYS.SDO_GEOMETRY(4002, 3067, NULL, MDSYS.SDO_ELEM_INFO_ARRAY(1,2,1), MDSYS.SDO_ORDINATE_ARRAY(${points.mkString(",")}))"
      sqlu""" INSERT INTO PROJECT_RESERVED_ROAD_PART (ID, ROAD_NUMBER, ROAD_PART_NUMBER, PROJECT_ID, CREATED_BY) VALUES (${Sequences.nextViitePrimaryKeySeqValue},1,1,$projectId,'""')""".execute
      sqlu""" INSERT INTO PROJECT_LINK (ID, PROJECT_ID, TRACK, DISCONTINUITY_TYPE, ROAD_NUMBER, ROAD_PART_NUMBER, START_ADDR_M, END_ADDR_M, ORIGINAL_START_ADDR_M, ORIGINAL_END_ADDR_M, CREATED_BY, CREATED_DATE, STATUS, GEOMETRY,
            start_Measure, end_Measure, Link_id, side)
            VALUES (${Sequences.nextViitePrimaryKeySeqValue},$projectId,0,0,1,1,0,87,0,87,'testuser',TO_DATE('2017-10-06 14:54:41', 'YYYY-MM-DD HH24:MI:SS'), 0, #$templateGeom ,0, 87, 1, 2)""".execute

      when(mockRoadLinkService.getRoadLinksHistoryFromVVH(any[Set[Long]])).thenReturn(Seq())
      when(mockRoadLinkService.getSuravageRoadLinksByLinkIdsFromVVH(any[Set[Long]])).thenReturn(Seq(toRoadLink(suravageAddressLink)))
      when(mockRoadLinkService.getRoadLinksByLinkIdsFromVVH(any[Set[Long]], any[Boolean])).thenReturn(Seq(roadLink))
      val splitOptions = SplitOptions(Point(0, 45.3), LinkStatus.Transfer, LinkStatus.New, 0, 0, Track.Combined, Discontinuity.Continuous, 0, LinkGeomSource.Unknown, RoadType.Unknown, projectId, ProjectCoordinates(0, 45.3, 10))
      val (splitResult, errorMessage, vector) = projectServiceWithRoadAddressMock.preSplitSuravageLinkInTX(suravageAddressLink.linkId, "TestUser", splitOptions)
      errorMessage.isEmpty should be (true)
      splitResult.nonEmpty should be (true)
      val splitLinks = splitResult.get.toSeqWithMergeTerminated
      splitLinks.size should be (3)
      val (sl, tl) = splitLinks.partition(_.linkGeomSource == LinkGeomSource.SuravageLinkInterface)
      val (splitA, splitB) = sl.partition(_.status != LinkStatus.New)
      splitA.size should be (1)
      splitB.size should be (1)
      tl.size should be (1)
      splitA.head.endAddrMValue should be (tl.head.startAddrMValue)
      splitB.head.startAddrMValue should be (splitA.head.endAddrMValue)
    }
  }

  //TODO this will be implemented with SPLIT
  //  test("split road address is splitting historic versions") {
  //    runWithRollback {
  //      val linearLocationId = 100L
  //      val roadwayNumber = 1000000L
  //      val road = 19999L
  //      val roadPart = 205L
  //      val origStartM = 0L
  //      val origEndM = 102L
  //      val origStartD = DateTime.now().minusYears(10)
  //      val linkId = 1049L
  //      val endM = 102.04
  //      val suravageLinkId = 5774839L
  //      val createdBy = "user"
  //      val ely = 8L;
  //      val roadway = Roadway(NewRoadway, roadwayNumber, road, roadPart, PublicRoad, Track.Combined, EndOfRoad, origStartM,
  //        origEndM, false, origStartD, None, createdBy, None, ely, TerminationCode.NoTermination)
  //      val linearLocation = LinearLocation(NewLinearLocation, 1, linkId, origStartM, origEndM, SideCode.TowardsDigitizing,
  //        86400L, (Some(origStartM), Some(origEndM)), NoFloating, Seq(Point(1024.0, 0.0), Point(1024.0, 102.04)),
  //        LinkGeomSource.NormalLinkInterface, roadwayNumber)
  //      val roadAddressHistory = RoadAddress(NewRoadway, road, roadPart + 1, PublicRoad, Track.Combined, EndOfRoad, origStartM, origEndM,
  //        origStartD.map(_.minusYears(5)), origStartD.map(_.minusYears(15)),
  //        None, linkId, 0.0, endM, SideCode.TowardsDigitizing, 86400L, (None, None), NoFloating, Seq(Point(1024.0, 0.0), Point(1025.0, 1544.386)),
  //        LinkGeomSource.NormalLinkInterface, 8L, TerminationCode.NoTermination, 0)
  //      val roadwayHistory = Roadway(NewRoadway, road, roadPart + 1, PublicRoad, Track.Combined, EndOfRoad, origStartM, origEndM,
  //        origStartD.map(_.minusYears(5)), origStartD.map(_.minusYears(15)),
  //        None, linkId, 0.0, endM, SideCode.TowardsDigitizing, 86400L, (None, None), NoFloating, Seq(Point(1024.0, 0.0), Point(1025.0, 1544.386)),
  //        LinkGeomSource.NormalLinkInterface, 8L, TerminationCode.NoTermination, 0)
  //      val roadwayHistory2 = Roadway(NewRoadway, road, roadPart + 2, PublicRoad, Track.Combined, EndOfRoad, origStartM, origEndM,
  //        origStartD.map(_.minusYears(15)), origStartD.map(_.minusYears(20)),
  //        None, linkId, 0.0, endM, SideCode.TowardsDigitizing, 86400L, (None, None), NoFloating, Seq(Point(1024.0, 0.0), Point(1025.0, 1544.386)),
  //        LinkGeomSource.NormalLinkInterface, 8L, TerminationCode.NoTermination, 0)
  //      val id = roadwayDAO.create(Seq(roadway)).head
  //      roadwayDAO.create(Seq(roadwayHistory, roadwayHistory2))
  //      val project = RoadAddressProject(-1L, Sent2TR, "split", createdBy.get, DateTime.now(), createdBy.get,
  //        DateTime.now().plusMonths(2), DateTime.now(), "", Seq(), None, None)
  //      val unchangedAndNew = Seq(ProjectLink(2L, road, roadPart, Track.Combined, Continuous, origStartM, origStartM + 52L, Some(DateTime.now()), None, createdBy,
  //        suravageLinkId, 0.0, 51.984, SideCode.TowardsDigitizing, (Some(ProjectLinkCalibrationPoint(linkId, 0.0, origStartM, UnknownSource)), None),
  //        NoFloating, Seq(Point(1024.0, 0.0), Point(1024.0, 51.984)),
  //        -1L, LinkStatus.UnChanged, PublicRoad, LinkGeomSource.SuravageLinkInterface, 51.984, id, 8L, false, Some(linkId), 85088L),
  //        ProjectLink(3L, road, roadPart, Track.Combined, EndOfRoad, origStartM + 52L, origStartM + 177L, Some(DateTime.now()), None, createdBy,
  //          suravageLinkId, 51.984, 176.695, SideCode.TowardsDigitizing, (None, Some(ProjectLinkCalibrationPoint(suravageLinkId, 176.695, origStartM + 177L, UnknownSource))),
  //          NoFloating, Seq(Point(1024.0, 99.384), Point(1148.711, 99.4)),
  //          -1L, LinkStatus.New, PublicRoad, LinkGeomSource.SuravageLinkInterface, 124.711, id, 8L, false, Some(linkId), 85088L),
  //        ProjectLink(4L, 5, 205, Track.Combined, EndOfRoad, origStartM + 52L, origEndM, Some(DateTime.now()), None, createdBy,
  //          linkId, 50.056, endM, SideCode.TowardsDigitizing, (None, Some(ProjectLinkCalibrationPoint(linkId, endM, origEndM, UnknownSource))), NoFloating,
  //          Seq(Point(1024.0, 51.984), Point(1024.0, 102.04)),
  //          -1L, LinkStatus.Terminated, PublicRoad, LinkGeomSource.NormalLinkInterface, endM - 50.056, id, 8L, false, Some(suravageLinkId), 85088L))
  //      projectService.updateTerminationForHistory(Set(), unchangedAndNew)
  //      val suravageAddresses = RoadAddressDAO.fetchByLinkId(Set(suravageLinkId), true, true)
  //      // Remove the current road address from list because it is not terminated by this procedure
  //      val oldLinkAddresses = RoadAddressDAO.fetchByLinkId(Set(linkId), true, true, true, true, Set(id))
  //      suravageAddresses.foreach { a =>
  //        a.terminated should be(NoTermination)
  //        a.endDate.nonEmpty || a.endAddrMValue == origStartM + 177L should be(true)
  //        a.linkGeomSource should be(SuravageLinkInterface)
  //      }
  //      oldLinkAddresses.foreach { a =>
  //        a.terminated should be(Subsequent)
  //        a.endDate.nonEmpty should be(true)
  //        a.linkGeomSource should be(NormalLinkInterface)
  //      }
  //    }
  //  }

  private def toRoadLink(ral: RoadAddressLinkLike): RoadLink = {
    RoadLink(ral.linkId, ral.geometry, ral.length, ral.administrativeClass, 1,
      extractTrafficDirection(ral.sideCode, Track.apply(ral.trackCode.toInt)), ral.linkType, ral.modifiedAt, ral.modifiedBy, Map(
        "MUNICIPALITYCODE" -> BigInt(749), "VERTICALLEVEL" -> BigInt(1), "SURFACETYPE" -> BigInt(1),
        "ROADNUMBER" -> BigInt(ral.roadNumber), "ROADPARTNUMBER" -> BigInt(ral.roadPartNumber)),
      ral.constructionType, ral.roadLinkSource)
  }

  def toGeomString(geometry: Seq[Point]): String = {
    def toBD(d: Double): String = {
      val zeroEndRegex = """(\.0+)$""".r
      val lastZero = """(\.[0-9])0*$""".r
      val bd = BigDecimal(d).setScale(3, BigDecimal.RoundingMode.HALF_UP).toString
      lastZero.replaceAllIn(zeroEndRegex.replaceFirstIn(bd, ""), { m => m.group(0) })
    }

    geometry.map(p =>
      (if (p.z != 0.0)
        Seq(p.x, p.y, p.z)
      else
        Seq(p.x, p.y)).map(toBD).mkString("[", ",", "]")).mkString(",")
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
}

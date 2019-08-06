package fi.liikennevirasto.viite.util

import java.util.Properties

import fi.liikennevirasto.GeometryUtils
import fi.liikennevirasto.digiroad2.asset.LinkGeomSource.NormalLinkInterface
import fi.liikennevirasto.digiroad2.asset.SideCode.{AgainstDigitizing, TowardsDigitizing}
import fi.liikennevirasto.digiroad2.asset._
import fi.liikennevirasto.digiroad2.util.Track
import fi.liikennevirasto.digiroad2.util.Track.{Combined, Unknown}
import fi.liikennevirasto.digiroad2._
import fi.liikennevirasto.digiroad2.asset.ConstructionType.Planned
import fi.liikennevirasto.digiroad2.linearasset.RoadLink
import fi.liikennevirasto.digiroad2.oracle.OracleDatabase
import fi.liikennevirasto.digiroad2.client.vvh.{FeatureClass, VVHRoadlink}
import fi.liikennevirasto.digiroad2.service.RoadLinkService
import fi.liikennevirasto.viite.RoadType.{PublicRoad, UnknownOwnerRoad}
import fi.liikennevirasto.viite._
import fi.liikennevirasto.viite.dao.Discontinuity.{Continuous, MinorDiscontinuity}
import fi.liikennevirasto.viite.dao.LinkStatus.{New, NotHandled}
import fi.liikennevirasto.viite.dao._
import fi.liikennevirasto.viite.dao.CalibrationPointSource.ProjectLinkSource
import fi.liikennevirasto.viite.process.RoadwayAddressMapper
import org.mockito.Mockito.reset
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
  val mockNodesAndJunctionsService = MockitoSugar.mock[NodesAndJunctionsService]
  val mockEventBus = MockitoSugar.mock[DigiroadEventBus]
  val roadAddressService = new RoadAddressService(mockRoadLinkService, new RoadwayDAO, new LinearLocationDAO, new RoadNetworkDAO, mockRoadwayAddressMapper, mockEventBus) {
    override def withDynSession[T](f: => T): T = f

    override def withDynTransaction[T](f: => T): T = f
  }
  val projectService = new ProjectService(roadAddressService, mockRoadLinkService, mockNodesAndJunctionsService, mockEventBus) {
    override def withDynSession[T](f: => T): T = f

    override def withDynTransaction[T](f: => T): T = f
  }

  val projectServiceWithRoadAddressMock = new ProjectService(mockRoadAddressService, mockRoadLinkService, mockNodesAndJunctionsService, mockEventBus) {
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

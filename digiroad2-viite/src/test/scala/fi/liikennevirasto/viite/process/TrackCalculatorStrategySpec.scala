package fi.liikennevirasto.viite.process

import fi.liikennevirasto.GeometryUtils
import fi.liikennevirasto.digiroad2.asset.{LinkGeomSource, SideCode}
import fi.liikennevirasto.digiroad2.util.Track
import fi.liikennevirasto.digiroad2.Point
import fi.liikennevirasto.viite.RoadType
import fi.liikennevirasto.viite.dao.{Discontinuity, LinkStatus, ProjectLink}
import fi.liikennevirasto.viite.process.strategy.{LinkStatusChangeTrackCalculatorStrategy, TrackCalculatorContext, TrackCalculatorResult}
import org.scalatest.{FunSuite, Matchers}

class TrackCalculatorStrategySpec extends FunSuite with Matchers {

  test("Test TrackCalculatorContext.getNextStrategy() When dealing with operation changes in tracks Then return the correct strategy: LinkStatusChangeTrackCalculatorStrategy") {

    val geomLeft1 = Seq(Point(10.0, 10.0), Point(20.0, 10.0))
    val geomLeft2 = Seq(Point(20.0, 10.0), Point(30.0, 10.0))

    val projectLinkLeft1 = ProjectLink(-1000L, 9999L, 1L, Track.apply(2), Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None,
      None, 12345L, 0.0, 0.0, SideCode.Unknown, (None, None),
      geomLeft1, 0L, LinkStatus.UnChanged, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomLeft1), 0L, 0, 1L, reversed = false,
      None, 86400L)
    val projectLinkLeft2 = ProjectLink(-1000L, 9999L, 1L, Track.apply(2), Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None,
      None, 12346L, 0.0, 0.0, SideCode.Unknown, (None, None),
      geomLeft2, 0L, LinkStatus.Transfer, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomLeft2), 0L, 0, 1L, reversed = false,
      None, 86400L)

    val geomRight1 = Seq(Point(10.0, 20.0), Point(20.0, 20.0))
    val geomRight2 = Seq(Point(20.0, 20.0), Point(30.0, 20.0))

    val projectLinkRight1 = ProjectLink(-1000L, 9999L, 1L, Track.apply(1), Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None,
      None, 12347L, 0.0, 0.0, SideCode.Unknown, (None, None),
      geomRight1, 0L, LinkStatus.UnChanged, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomRight1), 0L, 0, 1L, reversed = false,
      None, 86400L)

    val projectLinkRight2 = ProjectLink(-1000L, 9999L, 1L, Track.apply(1), Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None,
      None, 12348L, 0.0, 0.0, SideCode.Unknown, (None, None),
      geomRight2, 0L, LinkStatus.Transfer, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomRight2), 0L, 0,1L, reversed = false,
      None, 86400L)


    val leftSideProjectLinks = Seq(projectLinkLeft1, projectLinkLeft2)
    val rightSideProjectLinks = Seq(projectLinkRight1, projectLinkRight2)
    val projectLinks = leftSideProjectLinks ++ rightSideProjectLinks

    val strategySequence = TrackCalculatorContext.getNextStrategy(projectLinks)
    strategySequence.size should be(1)
    strategySequence.head._1 should be(projectLinks.minBy(_.startAddrMValue).startAddrMValue)
    strategySequence.head._2.isInstanceOf[LinkStatusChangeTrackCalculatorStrategy] should be(true)
  }

  test("Test TrackCalculatorStrategy.setCalibrationPoints() When having different road types between links on same Track Then one calibration point should be created") {

    val geomLeft1 = Seq(Point(10.0, 20.0), Point(20.0, 20.0))
    val geomLeft2 = Seq(Point(20.0, 20.0), Point(30.0, 20.0))
    val geomLeft3 = Seq(Point(30.0, 20.0), Point(40.0, 20.0))


    val projectLinkLeft1 = ProjectLink(-1000L, 9999L, 1L, Track.apply(2), Discontinuity.Continuous, 0L, 10L, 0L, 10L, None, None,
      None, 12345L, 0.0, 10.0, SideCode.TowardsDigitizing, (None, None),
      geomLeft1, 0L, LinkStatus.Transfer, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomLeft1), 0L, 0, 1L, reversed = false,
      None, 86400L)
    val projectLinkLeft2 = ProjectLink(-1000L, 9999L, 1L, Track.apply(2), Discontinuity.Continuous, 10L, 20L, 10L, 20L, None, None,
      None, 12346L, 0.0, 10.0, SideCode.TowardsDigitizing, (None, None),
      geomLeft2, 0L, LinkStatus.Transfer, RoadType.FerryRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomLeft2), 0L, 0, 1L, reversed = false,
      None, 86400L)
    val projectLinkLeft3 = ProjectLink(-1000L, 9999L, 1L, Track.apply(2), Discontinuity.Continuous, 20L, 30L, 20L, 30L, None, None,
      None, 12347L, 0.0, 10.0, SideCode.TowardsDigitizing, (None, None),
      geomLeft2, 0L, LinkStatus.Transfer, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomLeft3), 0L, 0, 1L, reversed = false,
      None, 86400L)

    val geomRight1 = Seq(Point(10.0, 10.0), Point(20.0, 10.0))
    val geomRight2 = Seq(Point(20.0, 10.0), Point(30.0, 10.0))
    val geomRight3 = Seq(Point(30.0, 10.0), Point(40.0, 10.0))

    val projectLinkRight1 = ProjectLink(-1000L, 9999L, 1L, Track.apply(1), Discontinuity.Continuous, 0L, 10L, 0L, 10L, None, None,
      None, 12348L, 0.0, 10.0, SideCode.TowardsDigitizing, (None, None),
      geomRight1, 0L, LinkStatus.Transfer, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomRight1), 0L, 0, 1L, reversed = false,
      None, 86400L)

    val projectLinkRight2 = ProjectLink(-1000L, 9999L, 1L, Track.apply(1), Discontinuity.Continuous, 10L, 20L, 10L, 20L, None, None,
      None, 12349L, 0.0, 10.0, SideCode.TowardsDigitizing, (None, None),
      geomRight2, 0L, LinkStatus.Transfer, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomRight2), 0L, 0,1L, reversed = false,
      None, 86400L)

    val projectLinkRight3 = ProjectLink(-1000L, 9999L, 1L, Track.apply(1), Discontinuity.Continuous, 20L, 30L, 20L, 30L, None, None,
      None, 12350L, 0.0, 10.0, SideCode.TowardsDigitizing, (None, None),
      geomRight2, 0L, LinkStatus.Transfer, RoadType.FerryRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomRight3), 0L, 0,1L, reversed = false,
      None, 86400L)


    val leftSideProjectLinks = Seq(projectLinkLeft1, projectLinkLeft2, projectLinkLeft3)
    val rightSideProjectLinks = Seq(projectLinkRight1, projectLinkRight2, projectLinkRight3)
    val strategy = TrackCalculatorContext.getStrategy(leftSideProjectLinks, rightSideProjectLinks)
    val trackCalcResult = TrackCalculatorResult(leftSideProjectLinks, rightSideProjectLinks, 0L, 0L, Seq(), Seq())

    val (adjustedLeft, adjustedRight) = strategy.setCalibrationPoints(trackCalcResult, Map())

    adjustedLeft.foreach{r =>
      r.calibrationPoints._1.nonEmpty should be (true)
      r.calibrationPoints._2.nonEmpty should be (true)
    }
    adjustedRight.filter(_.linkId == projectLinkRight1.linkId).head.calibrationPoints._1.nonEmpty should be (true)
    adjustedRight.filter(_.linkId == projectLinkRight1.linkId).head.calibrationPoints._2.isEmpty should be (true)

    adjustedRight.filter(_.linkId == projectLinkRight2.linkId).head.calibrationPoints._1.isEmpty should be (true)
    adjustedRight.filter(_.linkId == projectLinkRight2.linkId).head.calibrationPoints._2.nonEmpty should be (true)

    adjustedRight.filter(_.linkId == projectLinkRight3.linkId).head.calibrationPoints._1.nonEmpty should be (true)
    adjustedRight.filter(_.linkId == projectLinkRight3.linkId).head.calibrationPoints._2.nonEmpty should be (true)
  }


}

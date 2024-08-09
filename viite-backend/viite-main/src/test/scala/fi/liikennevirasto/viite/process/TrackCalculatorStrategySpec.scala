package fi.liikennevirasto.viite.process

import fi.liikennevirasto.viite.dao.ProjectLink
import fi.liikennevirasto.viite.process.strategy.TrackCalculatorResult
import fi.vaylavirasto.viite.geometry.{GeometryUtils, Point}
import fi.vaylavirasto.viite.model.CalibrationPointType.NoCP
import fi.vaylavirasto.viite.model.{AddrMRange, AdministrativeClass, Discontinuity, LinkGeomSource, RoadAddressChangeType, RoadPart, SideCode, Track}
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class TrackCalculatorStrategySpec extends AnyFunSuite with Matchers {
  test("Test TrackCalculatorStrategy.setCalibrationPoints() When having different road types between links on same Track Then one calibration point should be created") {

    val geomLeft1 = Seq(Point(10.0, 20.0), Point(20.0, 20.0))
    val geomLeft2 = Seq(Point(20.0, 20.0), Point(30.0, 20.0))
    val geomLeft3 = Seq(Point(30.0, 20.0), Point(40.0, 20.0))


    val projectLinkLeft1 = ProjectLink(-1000L, RoadPart(9999, 1), Track.apply(2), Discontinuity.Continuous, AddrMRange( 0L, 10L), AddrMRange( 0L, 10L), None, None, None, 12345L.toString, 0.0, 10.0, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geomLeft1, 0L, RoadAddressChangeType.Transfer, AdministrativeClass.State,        LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomLeft1), 0L, 0, 1L, reversed = false, None, 86400L)
    val projectLinkLeft2 = ProjectLink(-1000L, RoadPart(9999, 1), Track.apply(2), Discontinuity.Continuous, AddrMRange(10L, 20L), AddrMRange(10L, 20L), None, None, None, 12346L.toString, 0.0, 10.0, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geomLeft2, 0L, RoadAddressChangeType.Transfer, AdministrativeClass.Municipality, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomLeft2), 0L, 0, 1L, reversed = false, None, 86400L)
    val projectLinkLeft3 = ProjectLink(-1000L, RoadPart(9999, 1), Track.apply(2), Discontinuity.Continuous, AddrMRange(20L, 30L), AddrMRange(20L, 30L), None, None, None, 12347L.toString, 0.0, 10.0, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geomLeft2, 0L, RoadAddressChangeType.Transfer, AdministrativeClass.State,        LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomLeft3), 0L, 0, 1L, reversed = false, None, 86400L)

    val geomRight1 = Seq(Point(10.0, 10.0), Point(20.0, 10.0))
    val geomRight2 = Seq(Point(20.0, 10.0), Point(30.0, 10.0))
    val geomRight3 = Seq(Point(30.0, 10.0), Point(40.0, 10.0))

    val projectLinkRight1 = ProjectLink(-1000L, RoadPart(9999, 1), Track.apply(1), Discontinuity.Continuous, AddrMRange( 0L, 10L), AddrMRange( 0L, 10L), None, None, None, 12348L.toString, 0.0, 10.0, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geomRight1, 0L, RoadAddressChangeType.Transfer, AdministrativeClass.State,        LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomRight1), 0L, 0, 1L, reversed = false, None, 86400L)
    val projectLinkRight2 = ProjectLink(-1000L, RoadPart(9999, 1), Track.apply(1), Discontinuity.Continuous, AddrMRange(10L, 20L), AddrMRange(10L, 20L), None, None, None, 12349L.toString, 0.0, 10.0, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geomRight2, 0L, RoadAddressChangeType.Transfer, AdministrativeClass.State,        LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomRight2), 0L, 0, 1L, reversed = false, None, 86400L)
    val projectLinkRight3 = ProjectLink(-1000L, RoadPart(9999, 1), Track.apply(1), Discontinuity.Continuous, AddrMRange(20L, 30L), AddrMRange(20L, 30L), None, None, None, 12350L.toString, 0.0, 10.0, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), geomRight2, 0L, RoadAddressChangeType.Transfer, AdministrativeClass.Municipality, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomRight3), 0L, 0, 1L, reversed = false, None, 86400L)


    val leftSideProjectLinks = Seq(projectLinkLeft1, projectLinkLeft2, projectLinkLeft3)
    val rightSideProjectLinks = Seq(projectLinkRight1, projectLinkRight2, projectLinkRight3)
    val trackCalcResult = TrackCalculatorResult(leftSideProjectLinks, rightSideProjectLinks, AddrMRange(0L, 0L), Seq(), Seq())

    val (adjustedLeft, adjustedRight) = TrackSectionOrder.setCalibrationPoints(trackCalcResult.leftProjectLinks, trackCalcResult.rightProjectLinks, Map())

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

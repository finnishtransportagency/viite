package fi.liikennevirasto.viite.process

import fi.liikennevirasto.digiroad2.asset.{LinkGeomSource, SideCode}
import fi.liikennevirasto.digiroad2.util.Track
import fi.liikennevirasto.digiroad2.{GeometryUtils, Point}
import fi.liikennevirasto.viite.RoadType
import fi.liikennevirasto.viite.dao.FloatingReason.NoFloating
import fi.liikennevirasto.viite.dao.{Discontinuity, LinkStatus, ProjectLink}
import fi.liikennevirasto.viite.process.strategy.{LinkStatusChangeTrackCalculatorStrategy, TrackCalculatorContext}
import org.scalatest.{FunSuite, Matchers}

class TrackCalculatorStrategySpec extends FunSuite with Matchers {

  test("Test TrackCalculatorContext.getNextStrategy() When dealing with operation changes in tracks Then return the correct strategy: LinkStatusChangeTrackCalculatorStrategy") {

    val geomLeft1 = Seq(Point(10.0, 10.0), Point(20.0, 10.0))
    val geomLeft2 = Seq(Point(20.0, 10.0), Point(30.0, 10.0))

    val projectLinkLeft1 = ProjectLink(-1000L, 9999L, 1L, Track.apply(2), Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None,
      None, 12345L, 0.0, 0.0, SideCode.Unknown, (None, None), NoFloating,
      geomLeft1, 0L, LinkStatus.UnChanged, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomLeft1), 0L, 0, 1L, false,
      None, 86400L)
    val projectLinkLeft2 = ProjectLink(-1000L, 9999L, 1L, Track.apply(2), Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None,
      None, 12346L, 0.0, 0.0, SideCode.Unknown, (None, None), NoFloating,
      geomLeft2, 0L, LinkStatus.Transfer, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomLeft2), 0L, 0, 1L, false,
      None, 86400L)

    val geomRight1 = Seq(Point(10.0, 20.0), Point(20.0, 20.0))
    val geomRight2 = Seq(Point(20.0, 20.0), Point(30.0, 20.0))

    val projectLinkRight1 = ProjectLink(-1000L, 9999L, 1L, Track.apply(1), Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None,
      None, 12347L, 0.0, 0.0, SideCode.Unknown, (None, None), NoFloating,
      geomRight1, 0L, LinkStatus.UnChanged, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomRight1), 0L, 0, 1L, false,
      None, 86400L)

    val projectLinkRight2 = ProjectLink(-1000L, 9999L, 1L, Track.apply(1), Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None,
      None, 12348L, 0.0, 0.0, SideCode.Unknown, (None, None), NoFloating,
      geomRight2, 0L, LinkStatus.Transfer, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomRight2), 0L, 0,1L, false,
      None, 86400L)


    val leftSideProjectLinks = Seq(projectLinkLeft1, projectLinkLeft2)
    val rightSideProjectLinks = Seq(projectLinkRight1, projectLinkRight2)
    val projectLinks = leftSideProjectLinks ++ rightSideProjectLinks

    val strategySequence = TrackCalculatorContext.getNextStrategy(projectLinks)
    strategySequence.size should be(1)
    strategySequence.head._1 should be(projectLinks.minBy(_.startAddrMValue).startAddrMValue)
    strategySequence.head._2.isInstanceOf[LinkStatusChangeTrackCalculatorStrategy] should be(true)
  }

}

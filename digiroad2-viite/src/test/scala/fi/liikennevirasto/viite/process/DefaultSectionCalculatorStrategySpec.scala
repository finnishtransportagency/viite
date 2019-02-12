package fi.liikennevirasto.viite.process

import fi.liikennevirasto.digiroad2.asset.{LinkGeomSource, SideCode}
import fi.liikennevirasto.digiroad2.util.Track
import fi.liikennevirasto.digiroad2.{GeometryUtils, Point}
import fi.liikennevirasto.viite.RoadType
import fi.liikennevirasto.viite.dao.CalibrationPointDAO.UserDefinedCalibrationPoint
import fi.liikennevirasto.viite.dao.FloatingReason.NoFloating
import fi.liikennevirasto.viite.dao.{Discontinuity, LinkStatus, ProjectLink}
import fi.liikennevirasto.viite.process.strategy.DefaultSectionCalculatorStrategy
import org.scalatest.{FunSuite, Matchers}

class DefaultSectionCalculatorStrategySpec extends FunSuite with Matchers {

  val defaultSectionCalculatorStrategy = new DefaultSectionCalculatorStrategy

  def setUpSideCodeDeterminationTestData(): Seq[ProjectLink] = {
    //1st four cases, lines parallel to the axis
    // | Case
    val geom1 = Seq(Point(10.0, 10.0), Point(10.0, 20.0))
    // | Case
    val geom2 = Seq(Point(10.0, 0.0), Point(10.0, 10.0))
    //- Case
    val geom3 = Seq(Point(10.0, 10.0), Point(20.0, 10.0))
    // - Case
    val geom4 = Seq(Point(0.0, 10.0), Point(10.0, 10.0))
    //Last four cases, 45º to the axis
    // / Case
    val geom5 = Seq(Point(10.0, 10.0), Point(20.0, 20.0))
    // / Case
    val geom6 = Seq(Point(20.0, 0.0), Point(10.0, 10.0))
    // \ Case
    val geom7 = Seq(Point(0.0, 0.0), Point(10.0, 10.0))
    // \ Case
    val geom8 = Seq(Point(10.0, 10.0), Point(0.0, 20.0))

    val projectLink1 = ProjectLink(-1000L, 9999L, 1L, Track.apply(0), Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None,
      None, 1L, 0.0, 0.0, SideCode.Unknown, (None, None), NoFloating,
      geom1, 0L, LinkStatus.New, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geom1), 0L, 0, 0, reversed = false,
      None, 86400L)
    val projectLink2 = ProjectLink(-1000L, 9999L, 1L, Track.apply(0), Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None,
      None, 2L, 0.0, 0.0, SideCode.Unknown, (None, None), NoFloating,
      geom2, 0L, LinkStatus.New, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geom2), 0L, 0, 0, reversed = false,
      None, 86400L)
    val projectLink3 = ProjectLink(-1000L, 9999L, 1L, Track.apply(0), Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None,
      None, 3L, 0.0, 0.0, SideCode.Unknown, (None, None), NoFloating,
      geom3, 0L, LinkStatus.New, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geom3), 0L, 0, 0, reversed = false,
      None, 86400L)
    val projectLink4 = ProjectLink(-1000L, 9999L, 1L, Track.apply(0), Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None,
      None, 4L, 0.0, 0.0, SideCode.Unknown, (None, None), NoFloating,
      geom4, 0L, LinkStatus.New, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geom4), 0L, 0, 0, reversed = false,
      None, 86400L)
    val projectLink5 = ProjectLink(-1000L, 9999L, 1L, Track.apply(0), Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None,
      None, 5L, 0.0, 0.0, SideCode.Unknown, (None, None), NoFloating,
      geom5, 0L, LinkStatus.New, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geom5), 0L, 0, 0, reversed = false,
      None, 86400L)
    val projectLink6 = ProjectLink(-1000L, 9999L, 1L, Track.apply(0), Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None,
      None, 6L, 0.0, 0.0, SideCode.Unknown, (None, None), NoFloating,
      geom6, 0L, LinkStatus.New, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geom6), 0L, 0, 0, reversed = false,
      None, 86400L)
    val projectLink7 = ProjectLink(-1000L, 9999L, 1L, Track.apply(0), Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None,
      None, 7L, 0.0, 0.0, SideCode.Unknown, (None, None), NoFloating,
      geom7, 0L, LinkStatus.New, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geom7), 0L, 0, 0, reversed = false,
      None, 86400L)
    val projectLink8 = ProjectLink(-1000L, 9999L, 1L, Track.apply(0), Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None,
      None, 8L, 0.0, 0.0, SideCode.Unknown, (None, None), NoFloating,
      geom8, 0L, LinkStatus.New, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geom8), 0L, 0, 0, reversed = false,
      None, 86400L)
    Seq(projectLink1, projectLink2, projectLink3, projectLink4, projectLink5, projectLink6, projectLink7, projectLink8).sortBy(_.linkId)
  }

  test("Test defaultSectionCalculatorStrategy.assignMValues() and defaultSectionCalculatorStrategy.findStartingPoints() When using 4 geometries that end up in a point " +
    "Then return the same project links, but now with correct MValues and directions")
  {
    val geomLeft1 = Seq(Point(10.0, 10.0), Point(20.0, 10.0))
    val geomLeft2 = Seq(Point(20.0, 10.0), Point(30.0, 10.0))

    val projectLinkLeft1 = ProjectLink(-1000L, 9999L, 1L, Track.apply(2), Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None,
      None, 12345L, 0.0, 0.0, SideCode.Unknown, (None, None), NoFloating,
      geomLeft1, 0L, LinkStatus.New, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomLeft1), 0L, 0, 0, reversed = false,
      None, 86400L)
    val projectLinkLeft2 = ProjectLink(-1000L, 9999L, 1L, Track.apply(2), Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None,
      None, 12346L, 0.0, 0.0, SideCode.Unknown, (None, None), NoFloating,
      geomLeft2, 0L, LinkStatus.New, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomLeft2), 0L, 0, 0, reversed = false,
      None, 86400L)

    val geomRight1 = Seq(Point(10.0, 20.0), Point(20.0, 20.0))
    val geomRight2 = Seq(Point(20.0, 20.0), Point(30.0, 20.0))

    val projectLinkRight1 = ProjectLink(-1000L, 9999L, 1L, Track.apply(1), Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None,
      None, 12347L, 0.0, 0.0, SideCode.Unknown, (None, None), NoFloating,
      geomRight1, 0L, LinkStatus.New, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomRight1), 0L, 0, 0, reversed = false,
      None, 86400L)

    val projectLinkRight2 = ProjectLink(-1000L, 9999L, 1L, Track.apply(1), Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None,
      None, 12348L, 0.0, 0.0, SideCode.Unknown, (None, None), NoFloating,
      geomRight2, 0L, LinkStatus.New, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomRight2), 0L, 0, 0, reversed = false,
      None, 86400L)

    val leftSideProjectLinks = Seq(projectLinkLeft1, projectLinkLeft2)
    val rightSideProjectLinks = Seq(projectLinkRight1, projectLinkRight2)
    val newProjectLinks = leftSideProjectLinks ++ rightSideProjectLinks

    val projectLinksWithAssignedValues = defaultSectionCalculatorStrategy.assignMValues(newProjectLinks, Seq.empty[ProjectLink], Seq.empty[UserDefinedCalibrationPoint])
    val startingPointsForCalculations = defaultSectionCalculatorStrategy.findStartingPoints(newProjectLinks, Seq.empty[ProjectLink], Seq.empty[UserDefinedCalibrationPoint])
    projectLinksWithAssignedValues.forall(_.sideCode == projectLinksWithAssignedValues.head.sideCode) should be(true)
    startingPointsForCalculations should be((geomRight1.head, geomLeft1.head))

    val additionalGeomLeft1 = Seq(Point(40.0, 10.0), Point(30.0, 10.0))
    val additionalGeomRight1 = Seq(Point(40.0, 20.0), Point(30.0, 20.0))

    val additionalProjectLinkLeft1 = ProjectLink(-1000L, 9999L, 1L, Track.apply(2), Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None,
      None, 12349L, 0.0, 0.0, SideCode.Unknown, (None, None), NoFloating,
      additionalGeomLeft1, 0L, LinkStatus.New, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(additionalGeomLeft1), 0L, 0, 0, reversed = false,
      None, 86400L)

    val additionalProjectLinkRight1 = ProjectLink(-1000L, 9999L, 1L, Track.apply(1), Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None,
      None, 12350L, 0.0, 0.0, SideCode.Unknown, (None, None), NoFloating,
      additionalGeomRight1, 0L, LinkStatus.New, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(additionalGeomRight1), 0L, 0, 0, reversed = false,
      None, 86400L)

    val leftSideAdditionalProjectLinks = Seq(additionalProjectLinkLeft1)
    val rightSideAdditionalProjectLinks = Seq(additionalProjectLinkRight1)
    val additionalProjectLinks = leftSideAdditionalProjectLinks ++ rightSideAdditionalProjectLinks

    val projectLinksWithAssignedValuesPlus = defaultSectionCalculatorStrategy.assignMValues(projectLinksWithAssignedValues ++ additionalProjectLinks, Seq.empty[ProjectLink], Seq.empty[UserDefinedCalibrationPoint])
    val findStartingPointsPlus = defaultSectionCalculatorStrategy.findStartingPoints(projectLinksWithAssignedValues ++ additionalProjectLinks, Seq.empty[ProjectLink], Seq.empty[UserDefinedCalibrationPoint])
    projectLinksWithAssignedValuesPlus.filter(p => projectLinksWithAssignedValues.map(_.linkId).contains(p.linkId)).forall(_.sideCode == projectLinksWithAssignedValuesPlus.filter(p => projectLinksWithAssignedValues.map(_.linkId).contains(p.linkId)).head.sideCode) should be(true)
    projectLinksWithAssignedValuesPlus.map(_.sideCode.value).sorted.containsSlice(projectLinksWithAssignedValues.map(_.sideCode.value).sorted) should be(true)
    projectLinksWithAssignedValues.map(_.sideCode.value).containsSlice(projectLinksWithAssignedValuesPlus.filter(p => additionalProjectLinks.map(_.linkId).contains(p.linkId)).map(_.sideCode).map(SideCode.switch).map(_.value))
    findStartingPointsPlus should be(startingPointsForCalculations)


    val additionalGeomLeftBefore = Seq(Point(10.0, 10.0), Point(0.0, 10.0))
    val additionalGeomRightBefore = Seq(Point(10.0, 20.0), Point(0.0, 20.0))

    val additionalProjectLinkLeftBefore = ProjectLink(-1000L, 9999L, 1L, Track.apply(2), Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None,
      None, 12351L, 0.0, 0.0, SideCode.Unknown, (None, None), NoFloating,
      additionalGeomLeftBefore, 0L, LinkStatus.New, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(additionalGeomLeftBefore), 0L, 0, 0, reversed = false,
      None, 86400L)

    val additionalProjectLinkRightBefore = ProjectLink(-1000L, 9999L, 1L, Track.apply(1), Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None,
      None, 12352L, 0.0, 0.0, SideCode.Unknown, (None, None), NoFloating,
      additionalGeomRightBefore, 0L, LinkStatus.New, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(additionalGeomRightBefore), 0L, 0, 0, reversed = false,
      None, 86400L)

    val leftSideBeforeProjectLinks = Seq(additionalProjectLinkLeftBefore)
    val rightSideBeforeProjectLinks = Seq(additionalProjectLinkRightBefore)
    val beforeProjectLinks = leftSideBeforeProjectLinks ++ rightSideBeforeProjectLinks

    val projectLinksWithAssignedValuesBefore = defaultSectionCalculatorStrategy.assignMValues(projectLinksWithAssignedValues ++ beforeProjectLinks, Seq.empty[ProjectLink], Seq.empty[UserDefinedCalibrationPoint])
    val findStartingPointsBefore = defaultSectionCalculatorStrategy.findStartingPoints(projectLinksWithAssignedValuesBefore, Seq.empty[ProjectLink], Seq.empty[UserDefinedCalibrationPoint])
    projectLinksWithAssignedValuesBefore.filter(p => projectLinksWithAssignedValues.map(_.linkId).contains(p.linkId)).forall(_.sideCode == projectLinksWithAssignedValuesBefore.filter(p => projectLinksWithAssignedValues.map(_.linkId).contains(p.linkId)).head.sideCode) should be(true)
    projectLinksWithAssignedValuesBefore.map(_.sideCode.value).containsSlice(projectLinksWithAssignedValuesPlus.filter(p => additionalProjectLinks.map(_.linkId).contains(p.linkId)).map(_.sideCode).map(SideCode.switch).map(_.value))
  }

  test("Test defaultSectionCalculatorStrategy.assignMValues() When supplying a variety of project links Then return said project links but EVERY SideCode should be TowardsDigitizing")
  {
    val projectLinks = setUpSideCodeDeterminationTestData()
    projectLinks.foreach(p => {
      val assigned = defaultSectionCalculatorStrategy.assignMValues(Seq(p), Seq.empty[ProjectLink], Seq.empty[UserDefinedCalibrationPoint])
      assigned.head.linkId should be(p.linkId)
      assigned.head.geometry should be(p.geometry)
      assigned.head.sideCode should be(SideCode.TowardsDigitizing)
    })
  }

  test("Test defaultSectionCalculatorStrategy.assignMValues() When supplying a variety of project links Then return said project links but EVERY SideCode should be AgainstDigitizing")
  {
    val projectLinks = setUpSideCodeDeterminationTestData()
    projectLinks.foreach(p => {
      val pl = p.copyWithGeometry(p.geometry.reverse)
      val assigned = defaultSectionCalculatorStrategy.assignMValues(Seq(pl), Seq.empty[ProjectLink], Seq.empty[UserDefinedCalibrationPoint])
      assigned.head.linkId should be(pl.linkId)
      assigned.head.geometry should be(pl.geometry)
      assigned.head.sideCode should be(SideCode.AgainstDigitizing)
    })
  }

}

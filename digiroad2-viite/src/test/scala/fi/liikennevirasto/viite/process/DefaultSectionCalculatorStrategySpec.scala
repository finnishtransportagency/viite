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

  //TODO Will be implemented at VIITE-1540
  /*test("Test the correct assignation of the MValues and it's start directions") {

    val geomLeft1 = Seq(Point(10.0, 10.0), Point(20.0, 10.0))
    val geomLeft2 = Seq(Point(20.0, 10.0), Point(30.0, 10.0))

    val projectLinkLeft1 = ProjectLink(-1000L, 9999L, 1L, Track.apply(2), Discontinuity.Continuous, 0L, 0L, None, None,
      None, 12345L, 0.0, 0.0, SideCode.Unknown, (None, None), NoFloating,
      geomLeft1, 0L, LinkStatus.New, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomLeft1), 0L, 0, false,
      None, 86400L)
    val projectLinkLeft2 = ProjectLink(-1000L, 9999L, 1L, Track.apply(2), Discontinuity.Continuous, 0L, 0L, None, None,
      None, 12346L, 0.0, 0.0, SideCode.Unknown, (None, None), NoFloating,
      geomLeft2, 0L, LinkStatus.New, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomLeft2), 0L, 0, false,
      None, 86400L)

    val geomRight1 = Seq(Point(10.0, 20.0), Point(20.0, 20.0))
    val geomRight2 = Seq(Point(20.0, 20.0), Point(30.0, 20.0))

    val projectLinkRight1 = ProjectLink(-1000L, 9999L, 1L, Track.apply(1), Discontinuity.Continuous, 0L, 0L, None, None,
      None, 12347L, 0.0, 0.0, SideCode.Unknown, (None, None), NoFloating,
      geomRight1, 0L, LinkStatus.New, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomRight1), 0L, 0, false,
      None, 86400L)

    val projectLinkRight2 = ProjectLink(-1000L, 9999L, 1L, Track.apply(1), Discontinuity.Continuous, 0L, 0L, None, None,
      None, 12348L, 0.0, 0.0, SideCode.Unknown, (None, None), NoFloating,
      geomRight2, 0L, LinkStatus.New, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomRight2), 0L, 0, false,
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

    val additionalProjectLinkLeft1 = ProjectLink(-1000L, 9999L, 1L, Track.apply(2), Discontinuity.Continuous, 0L, 0L, None, None,
      None, 12349L, 0.0, 0.0, SideCode.Unknown, (None, None), NoFloating,
      additionalGeomLeft1, 0L, LinkStatus.New, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(additionalGeomLeft1), 0L, 0, false,
      None, 86400L)

    val additionalProjectLinkRight1 = ProjectLink(-1000L, 9999L, 1L, Track.apply(1), Discontinuity.Continuous, 0L, 0L, None, None,
      None, 12350L, 0.0, 0.0, SideCode.Unknown, (None, None), NoFloating,
      additionalGeomRight1, 0L, LinkStatus.New, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(additionalGeomRight1), 0L, 0, false,
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

    val additionalProjectLinkLeftBefore = ProjectLink(-1000L, 9999L, 1L, Track.apply(2), Discontinuity.Continuous, 0L, 0L, None, None,
      None, 12351L, 0.0, 0.0, SideCode.Unknown, (None, None), NoFloating,
      additionalGeomLeftBefore, 0L, LinkStatus.New, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(additionalGeomLeftBefore), 0L, 0, false,
      None, 86400L)

    val additionalProjectLinkRightBefore = ProjectLink(-1000L, 9999L, 1L, Track.apply(1), Discontinuity.Continuous, 0L, 0L, None, None,
      None, 12352L, 0.0, 0.0, SideCode.Unknown, (None, None), NoFloating,
      additionalGeomRightBefore, 0L, LinkStatus.New, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(additionalGeomRightBefore), 0L, 0, false,
      None, 86400L)

    val leftSideBeforeProjectLinks = Seq(additionalProjectLinkLeftBefore)
    val rightSideBeforeProjectLinks = Seq(additionalProjectLinkRightBefore)
    val beforeProjectLinks = leftSideBeforeProjectLinks ++ rightSideBeforeProjectLinks

    val projectLinksWithAssignedValuesBefore = defaultSectionCalculatorStrategy.assignMValues(projectLinksWithAssignedValues ++ beforeProjectLinks, Seq.empty[ProjectLink], Seq.empty[UserDefinedCalibrationPoint])
    val findStartingPointsBefore = defaultSectionCalculatorStrategy.findStartingPoints(projectLinksWithAssignedValues ++ beforeProjectLinks, Seq.empty[ProjectLink], Seq.empty[UserDefinedCalibrationPoint])
    projectLinksWithAssignedValuesBefore.filter(p => projectLinksWithAssignedValues.map(_.linkId).contains(p.linkId)).forall(_.sideCode == projectLinksWithAssignedValuesPlus.filter(p => projectLinksWithAssignedValues.map(_.linkId).contains(p.linkId)).head.sideCode) should be(true)
    projectLinksWithAssignedValuesBefore.map(_.sideCode.value).sorted.containsSlice(projectLinksWithAssignedValues.map(p => p.sideCode.value).sorted) should be(true)
    projectLinksWithAssignedValuesBefore.map(_.sideCode.value).containsSlice(projectLinksWithAssignedValuesPlus.filter(p => additionalProjectLinks.map(_.linkId).contains(p.linkId)).map(_.sideCode).map(SideCode.switch).map(_.value))
    findStartingPointsBefore should be((additionalGeomRightBefore.last, additionalGeomLeftBefore.last))
  }*/
}

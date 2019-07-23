package fi.liikennevirasto.viite.process

import fi.liikennevirasto.GeometryUtils
import fi.liikennevirasto.digiroad2.asset.{LinkGeomSource, SideCode}
import fi.liikennevirasto.digiroad2.dao.Sequences
import fi.liikennevirasto.digiroad2.oracle.OracleDatabase
import fi.liikennevirasto.digiroad2.util.Track
import fi.liikennevirasto.digiroad2.util.Track.LeftSide
import fi.liikennevirasto.digiroad2.Point
import fi.liikennevirasto.viite.RoadType
import fi.liikennevirasto.viite.dao.ProjectCalibrationPointDAO.UserDefinedCalibrationPoint
import fi.liikennevirasto.viite.dao.{Discontinuity, LinkStatus, ProjectLink}
import fi.liikennevirasto.viite.process.strategy.DefaultSectionCalculatorStrategy
import org.scalatest.{FunSuite, Matchers}
import slick.driver.JdbcDriver.backend.Database
import slick.driver.JdbcDriver.backend.Database.dynamicSession

class DefaultSectionCalculatorStrategySpec extends FunSuite with Matchers {
  def runWithRollback(f: => Unit): Unit = {
   Database.forDataSource(OracleDatabase.ds).withDynTransaction {
      f
      dynamicSession.rollback()
    }
  }
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
      None, 1L, 0.0, 0.0, SideCode.Unknown, (None, None),
      geom1, 0L, LinkStatus.New, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geom1), 0L, 0, 0, reversed = false,
      None, 86400L)
    val projectLink2 = ProjectLink(-1000L, 9999L, 1L, Track.apply(0), Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None,
      None, 2L, 0.0, 0.0, SideCode.Unknown, (None, None),
      geom2, 0L, LinkStatus.New, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geom2), 0L, 0, 0, reversed = false,
      None, 86400L)
    val projectLink3 = ProjectLink(-1000L, 9999L, 1L, Track.apply(0), Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None,
      None, 3L, 0.0, 0.0, SideCode.Unknown, (None, None),
      geom3, 0L, LinkStatus.New, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geom3), 0L, 0, 0, reversed = false,
      None, 86400L)
    val projectLink4 = ProjectLink(-1000L, 9999L, 1L, Track.apply(0), Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None,
      None, 4L, 0.0, 0.0, SideCode.Unknown, (None, None),
      geom4, 0L, LinkStatus.New, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geom4), 0L, 0, 0, reversed = false,
      None, 86400L)
    val projectLink5 = ProjectLink(-1000L, 9999L, 1L, Track.apply(0), Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None,
      None, 5L, 0.0, 0.0, SideCode.Unknown, (None, None),
      geom5, 0L, LinkStatus.New, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geom5), 0L, 0, 0, reversed = false,
      None, 86400L)
    val projectLink6 = ProjectLink(-1000L, 9999L, 1L, Track.apply(0), Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None,
      None, 6L, 0.0, 0.0, SideCode.Unknown, (None, None),
      geom6, 0L, LinkStatus.New, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geom6), 0L, 0, 0, reversed = false,
      None, 86400L)
    val projectLink7 = ProjectLink(-1000L, 9999L, 1L, Track.apply(0), Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None,
      None, 7L, 0.0, 0.0, SideCode.Unknown, (None, None),
      geom7, 0L, LinkStatus.New, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geom7), 0L, 0, 0, reversed = false,
      None, 86400L)
    val projectLink8 = ProjectLink(-1000L, 9999L, 1L, Track.apply(0), Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None,
      None, 8L, 0.0, 0.0, SideCode.Unknown, (None, None),
      geom8, 0L, LinkStatus.New, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geom8), 0L, 0, 0, reversed = false,
      None, 86400L)
    Seq(projectLink1, projectLink2, projectLink3, projectLink4, projectLink5, projectLink6, projectLink7, projectLink8).sortBy(_.linkId)
  }

  test("Test defaultSectionCalculatorStrategy.assignMValues() and defaultSectionCalculatorStrategy.findStartingPoints() When using 4 geometries that end up in a point " +
    "Then return the same project links, but now with correct MValues and directions") {
    val geomLeft1 = Seq(Point(10.0, 10.0), Point(20.0, 10.0))
    val geomLeft2 = Seq(Point(20.0, 10.0), Point(30.0, 10.0))

    val projectLinkLeft1 = ProjectLink(-1000L, 9999L, 1L, Track.apply(2), Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None,
      None, 12345L, 0.0, 0.0, SideCode.Unknown, (None, None),
      geomLeft1, 0L, LinkStatus.New, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomLeft1), 0L, 0, 0, reversed = false,
      None, 86400L)
    val projectLinkLeft2 = ProjectLink(-1000L, 9999L, 1L, Track.apply(2), Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None,
      None, 12346L, 0.0, 0.0, SideCode.Unknown, (None, None),
      geomLeft2, 0L, LinkStatus.New, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomLeft2), 0L, 0, 0, reversed = false,
      None, 86400L)

    val geomRight1 = Seq(Point(10.0, 20.0), Point(20.0, 20.0))
    val geomRight2 = Seq(Point(20.0, 20.0), Point(30.0, 20.0))

    val projectLinkRight1 = ProjectLink(-1000L, 9999L, 1L, Track.apply(1), Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None,
      None, 12347L, 0.0, 0.0, SideCode.Unknown, (None, None),
      geomRight1, 0L, LinkStatus.New, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomRight1), 0L, 0, 0, reversed = false,
      None, 86400L)

    val projectLinkRight2 = ProjectLink(-1000L, 9999L, 1L, Track.apply(1), Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None,
      None, 12348L, 0.0, 0.0, SideCode.Unknown, (None, None),
      geomRight2, 0L, LinkStatus.New, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomRight2), 0L, 0, 0, reversed = false,
      None, 86400L)

    val leftSideProjectLinks = Seq(projectLinkLeft1, projectLinkLeft2)
    val rightSideProjectLinks = Seq(projectLinkRight1, projectLinkRight2)
    val newProjectLinks = leftSideProjectLinks ++ rightSideProjectLinks

    val projectLinksWithAssignedValues = defaultSectionCalculatorStrategy.assignMValues(newProjectLinks, Seq.empty[ProjectLink], Seq.empty[UserDefinedCalibrationPoint])
    val startingPointsForCalculations = defaultSectionCalculatorStrategy.findStartingPoints(newProjectLinks, Seq.empty[ProjectLink], Seq.empty[ProjectLink], Seq.empty[UserDefinedCalibrationPoint])
    projectLinksWithAssignedValues.forall(_.sideCode == projectLinksWithAssignedValues.head.sideCode) should be(true)
    startingPointsForCalculations should be((geomRight2.last, geomLeft2.last))

    val additionalGeomLeft1 = Seq(Point(40.0, 10.0), Point(30.0, 10.0))
    val additionalGeomRight1 = Seq(Point(40.0, 20.0), Point(30.0, 20.0))

    val additionalProjectLinkLeft1 = ProjectLink(-1000L, 9999L, 1L, Track.apply(2), Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None,
      None, 12349L, 0.0, 0.0, SideCode.Unknown, (None, None),
      additionalGeomLeft1, 0L, LinkStatus.New, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(additionalGeomLeft1), 0L, 0, 0, reversed = false,
      None, 86400L)

    val additionalProjectLinkRight1 = ProjectLink(-1000L, 9999L, 1L, Track.apply(1), Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None,
      None, 12350L, 0.0, 0.0, SideCode.Unknown, (None, None),
      additionalGeomRight1, 0L, LinkStatus.New, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(additionalGeomRight1), 0L, 0, 0, reversed = false,
      None, 86400L)

    val leftSideAdditionalProjectLinks = Seq(additionalProjectLinkLeft1)
    val rightSideAdditionalProjectLinks = Seq(additionalProjectLinkRight1)
    val additionalProjectLinks = leftSideAdditionalProjectLinks ++ rightSideAdditionalProjectLinks

    val projectLinksWithAssignedValuesPlus = defaultSectionCalculatorStrategy.assignMValues(projectLinksWithAssignedValues ++ additionalProjectLinks, Seq.empty[ProjectLink], Seq.empty[UserDefinedCalibrationPoint])
    val findStartingPointsPlus = defaultSectionCalculatorStrategy.findStartingPoints(projectLinksWithAssignedValues ++ additionalProjectLinks, Seq.empty[ProjectLink], Seq.empty[ProjectLink], Seq.empty[UserDefinedCalibrationPoint])
    projectLinksWithAssignedValuesPlus.filter(p => projectLinksWithAssignedValues.map(_.linkId).contains(p.linkId)).forall(_.sideCode == projectLinksWithAssignedValuesPlus.filter(p => projectLinksWithAssignedValues.map(_.linkId).contains(p.linkId)).head.sideCode) should be(true)
    projectLinksWithAssignedValuesPlus.map(_.sideCode.value).sorted.containsSlice(projectLinksWithAssignedValues.map(_.sideCode.value).sorted) should be(true)
    projectLinksWithAssignedValues.map(_.sideCode.value).containsSlice(projectLinksWithAssignedValuesPlus.filter(p => additionalProjectLinks.map(_.linkId).contains(p.linkId)).map(_.sideCode).map(SideCode.switch).map(_.value))
    findStartingPointsPlus should be(startingPointsForCalculations)


    val additionalGeomLeftBefore = Seq(Point(10.0, 10.0), Point(0.0, 10.0))
    val additionalGeomRightBefore = Seq(Point(10.0, 20.0), Point(0.0, 20.0))

    val additionalProjectLinkLeftBefore = ProjectLink(-1000L, 9999L, 1L, Track.apply(2), Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None,
      None, 12351L, 0.0, 0.0, SideCode.Unknown, (None, None),
      additionalGeomLeftBefore, 0L, LinkStatus.New, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(additionalGeomLeftBefore), 0L, 0, 0, reversed = false,
      None, 86400L)

    val additionalProjectLinkRightBefore = ProjectLink(-1000L, 9999L, 1L, Track.apply(1), Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None,
      None, 12352L, 0.0, 0.0, SideCode.Unknown, (None, None),
      additionalGeomRightBefore, 0L, LinkStatus.New, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(additionalGeomRightBefore), 0L, 0, 0, reversed = false,
      None, 86400L)

    val leftSideBeforeProjectLinks = Seq(additionalProjectLinkLeftBefore)
    val rightSideBeforeProjectLinks = Seq(additionalProjectLinkRightBefore)
    val beforeProjectLinks = leftSideBeforeProjectLinks ++ rightSideBeforeProjectLinks

    val projectLinksWithAssignedValuesBefore = defaultSectionCalculatorStrategy.assignMValues(projectLinksWithAssignedValues ++ beforeProjectLinks, Seq.empty[ProjectLink], Seq.empty[UserDefinedCalibrationPoint])
    projectLinksWithAssignedValuesBefore.filter(p => projectLinksWithAssignedValues.map(_.linkId).contains(p.linkId)).forall(_.sideCode == projectLinksWithAssignedValuesBefore.filter(p => projectLinksWithAssignedValues.map(_.linkId).contains(p.linkId)).head.sideCode) should be(true)
    projectLinksWithAssignedValuesBefore.map(_.sideCode.value).containsSlice(projectLinksWithAssignedValuesPlus.filter(p => additionalProjectLinks.map(_.linkId).contains(p.linkId)).map(_.sideCode).map(SideCode.switch).map(_.value))
  }

  test("Test defaultSectionCalculatorStrategy.assignMValues() When supplying a variety of project links Then return said project links but EVERY SideCode should be TowardsDigitizing") {
    runWithRollback {
      val projectLinks = setUpSideCodeDeterminationTestData()
      projectLinks.foreach(p => {
        val assigned = defaultSectionCalculatorStrategy.assignMValues(Seq(p), Seq.empty[ProjectLink], Seq.empty[UserDefinedCalibrationPoint])
        assigned.head.linkId should be(p.linkId)
        assigned.head.geometry should be(p.geometry)
        assigned.head.sideCode should be(SideCode.TowardsDigitizing)
      })
    }
  }

  test("Test defaultSectionCalculatorStrategy.assignMValues() When supplying a variety of project links Then return said project links but EVERY SideCode should be AgainstDigitizing") {
    runWithRollback {
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

  test("Test defaultSectionCalculatorStrategy.assignMValues() and the attribution of roadway_numbers for new Left Right sections with same number of links Then " +
    "if there are for e.g. 3 (three) consecutive links with same roadway_number (and all Transfer status), the first 3 (three) opposite track links (with all New status) should share some new generated roadway_number between them") {
    runWithRollback {
      //geoms
      //Left
      //before roundabout
      val geomTransferLeft1 = Seq(Point(0.0, 5.0), Point(5.0, 5.0))
      val geomTransferLeft2 = Seq(Point(5.0, 5.0), Point(10.0, 5.0))
      //after roundabout
      val geomTransferLeft3 = Seq(Point(10.0, 5.0), Point(11.0, 10.0))
      val geomTransferLeft4 = Seq(Point(11.0, 10.0), Point(13.0, 15.0))
      val geomTransferLeft5 = Seq(Point(13.0, 15.0), Point(15.0, 25.0))

      //Right
      //before roundabout
      val geomTransferRight1 = Seq(Point(0.0, 0.0), Point(5.0, 0.0))
      val geomTransferRight2 = Seq(Point(5.0, 0.0), Point(10.0, 0.0))
      //after roundabout
      val geomTransferRight3 = Seq(Point(20.0, 0.0), Point(19.0, 5.0))
      val geomTransferRight4 = Seq(Point(19.0, 5.0), Point(18.0, 10.0))
      val geomTransferRight5 = Seq(Point(18.0, 10.0), Point(15.0, 25.0))

      val roadwayId = Sequences.nextRoadwayId
      val linearLocationId = Sequences.nextLinearLocationId
      val roadwayNumber = Sequences.nextRoadwayNumber
      //projectlinks
      //Left
      //before roundabout
      val projectLinkLeft1 = ProjectLink(-1000L, 9999L, 1L, Track.apply(2), Discontinuity.Continuous, 0L, 5L, 0L, 5L, None, None,
        None, 12345L, 0.0, 5.0, SideCode.TowardsDigitizing, (None, None),
        geomTransferLeft1, 0L, LinkStatus.Transfer, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomTransferLeft1), roadwayId, linearLocationId, 8L, reversed = false,
        None, 86400L, roadwayNumber = roadwayNumber)
      val projectLinkLeft2 = ProjectLink(-1000L, 9999L, 1L, Track.apply(2), Discontinuity.Continuous, 0L, 5L, 0L, 5L, None, None,
        None, 12346L, 0.0, 5.0, SideCode.TowardsDigitizing, (None, None),
        geomTransferLeft2, 0L, LinkStatus.Transfer, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomTransferLeft2), roadwayId + 1, linearLocationId + 1, 8L, reversed = false,
        None, 86400L, roadwayNumber = roadwayNumber)
      //Right
      //before roundabout
      val projectLinkRight1 = ProjectLink(-1000L, 9999L, 1L, Track.apply(1), Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None,
        None, 12347L, 0.0, 5.0, SideCode.TowardsDigitizing, (None, None),
        geomTransferLeft1, 0L, LinkStatus.New, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomTransferLeft1), roadwayId + 2, linearLocationId + 2, 8L, reversed = false,
        None, 86400L)
      val projectLinkRight2 = ProjectLink(-1000L, 9999L, 1L, Track.apply(1), Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None,
        None, 12348L, 0.0, 5.0, SideCode.TowardsDigitizing, (None, None),
        geomTransferLeft2, 0L, LinkStatus.New, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomTransferLeft2), roadwayId + 3, linearLocationId + 3, 8L, reversed = false,
        None, 86400L)

      //Left
      //before roundabout
      val projectLinkLeft3 = ProjectLink(-1000L, 9999L, 1L, Track.apply(2), Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None,
        None, 12349L, 0.0, 5.1, SideCode.TowardsDigitizing, (None, None),
        geomTransferLeft3, 0L, LinkStatus.New, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomTransferLeft3), roadwayId+4, linearLocationId + 4, 8L, reversed = false,
        None, 86400L)
      val projectLinkLeft4 = ProjectLink(-1000L, 9999L, 1L, Track.apply(2), Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None,
        None, 12350L, 0.0, 5.3, SideCode.TowardsDigitizing, (None, None),
        geomTransferLeft4, 0L, LinkStatus.New, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomTransferLeft4), roadwayId + 5, linearLocationId + 5, 8L, reversed = false,
        None, 86400L)
      val projectLinkLeft5 = ProjectLink(-1000L, 9999L, 1L, Track.apply(2), Discontinuity.Continuous, 0L, 0L, 0L, 0L, None, None,
        None, 12351L, 0.0, 10.1, SideCode.TowardsDigitizing, (None, None),
        geomTransferLeft5, 0L, LinkStatus.New, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomTransferLeft5), roadwayId + 6, linearLocationId + 6, 8L, reversed = false,
        None, 86400L)
      //Right
      //before roundabout
      val projectLinkRight3 = ProjectLink(-1000L, 9999L, 1L, Track.apply(1), Discontinuity.Continuous, 0L, 5L, 0L, 5L, None, None,
        None, 12352L, 0.0, 5.1, SideCode.TowardsDigitizing, (None, None),
        geomTransferRight3, 0L, LinkStatus.Transfer, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomTransferRight3), roadwayId + 7, linearLocationId + 7, 8L, reversed = false,
        None, 86400L, roadwayNumber = Sequences.nextRoadwayNumber)
      val nextRwNumber = Sequences.nextRoadwayNumber
      val projectLinkRight4 = ProjectLink(-1000L, 9999L, 1L, Track.apply(1), Discontinuity.Continuous, 0L, 5L, 0L, 5L, None, None,
        None, 12352L, 0.0, 5.1, SideCode.TowardsDigitizing, (None, None),
        geomTransferRight4, 0L, LinkStatus.Transfer, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomTransferRight4), roadwayId + 8, linearLocationId + 8, 8L, reversed = false,
        None, 86400L, roadwayNumber = nextRwNumber)
      val projectLinkRight5 = ProjectLink(-1000L, 9999L, 1L, Track.apply(1), Discontinuity.Continuous, 0L, 15L, 0L, 15L, None, None,
        None, 12353L, 0.0, 15.2, SideCode.TowardsDigitizing, (None, None),
        geomTransferRight5, 0L, LinkStatus.Transfer, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geomTransferRight5), roadwayId + 9, linearLocationId + 9, 8L, reversed = false,
        None, 86400L, roadwayNumber = nextRwNumber)

      /*
      assignMValues before roundabout
       */
      val assignedValues = defaultSectionCalculatorStrategy.assignMValues(Seq(projectLinkRight1, projectLinkRight2), Seq(projectLinkLeft1, projectLinkLeft2), Seq.empty[UserDefinedCalibrationPoint])

      val (left, right) = assignedValues.partition(_.track == Track.LeftSide)

      left.map(_.roadwayNumber).distinct.size should be (1)
      right.map(_.roadwayNumber).distinct.size should be (left.map(_.roadwayNumber).distinct.size)

      val assignedValues2 = defaultSectionCalculatorStrategy.assignMValues(Seq(projectLinkRight1, projectLinkRight2), Seq(projectLinkLeft1, projectLinkLeft2.copy(roadwayNumber = Sequences.nextRoadwayNumber)), Seq.empty[UserDefinedCalibrationPoint])

      val (left2, right2) = assignedValues2.partition(_.track == Track.LeftSide)

      left2.map(_.roadwayNumber).distinct.size should be (2)
      right2.map(_.roadwayNumber).distinct.size should be (left2.map(_.roadwayNumber).distinct.size)

      /*
        assignMValues after roundabout
       */
      val assignedValues3 = defaultSectionCalculatorStrategy.assignMValues(Seq(projectLinkLeft3, projectLinkLeft4, projectLinkLeft5), assignedValues++Seq(projectLinkRight3, projectLinkRight4, projectLinkRight5), Seq.empty[UserDefinedCalibrationPoint])

      val (left3, right3) = assignedValues3.partition(_.track == Track.LeftSide)
      left3.map(_.roadwayNumber).distinct.size should be (3)
      right3.map(_.roadwayNumber).distinct.size should be (left3.map(_.roadwayNumber).distinct.size)

      assignedValues3.find(_.linearLocationId == projectLinkRight4.linearLocationId).get.roadwayNumber should be (assignedValues3.find(_.linearLocationId == projectLinkRight5.linearLocationId).get.roadwayNumber)
      assignedValues3.find(_.linearLocationId == projectLinkLeft4.linearLocationId).get.roadwayNumber should be (assignedValues3.find(_.linearLocationId == projectLinkLeft5.linearLocationId).get.roadwayNumber)
    }
  }

}
